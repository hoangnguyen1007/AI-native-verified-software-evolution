package com.evolution.backend.query;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaparser.slice.JavaMethodSlicer;
import com.evolution.analysis.policy.PolicyEvaluator;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.*;
import com.evolution.analysis.query.ArchitectureQuery.Classification;
import com.evolution.analysis.treesitter.GoErrorPropagationSummarizer;
import com.evolution.backend.query.mcp.McpToolDispatcher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FailCloseResilienceAnalyzerTest {

    @Test
    void endToEndPolyglotJavaAndGoSummariesFeedPolicyAndMcpFlamegraph() {
        String javaSrc = """
                package com.acme.ingress;
                import org.springframework.web.client.RestTemplate;
                public class EdgeController {
                    private RestTemplate restTemplate;
                    public String handleOrder(String id) {
                        return restTemplate.getForObject("/v1/ledger/" + id, String.class);
                    }
                    public String handleRecommendations(String id) {
                        try {
                            return restTemplate.getForObject("/v1/recs/" + id, String.class);
                        } catch (Exception e) {
                            return "[]";
                        }
                    }
                }
                """;
        String goSrc = """
                package worker
                import (
                    "context"
                    "fmt"
                )
                func Execute(ctx context.Context, primary PrimaryClient, fallback FallbackClient) (string, error) {
                    res, err := primary.Fetch(ctx)
                    if err != nil {
                        var err2 error
                        res, err2 = fallback.Fetch(ctx)
                        if err2 != nil {
                            return "", fmt.Errorf("fallback error: %w", err2)
                        }
                    }
                    return res, nil
                }
                """;

        List<MethodErrorSummary> javaSummaries = JavaMethodSlicer.summarizeErrorPropagation(
                "services/edge/EdgeController.java", javaSrc);
        List<MethodErrorSummary> goSummaries = GoErrorPropagationSummarizer.summarizeErrorPropagation(
                "services/worker/worker.go", goSrc);

        assertEquals(2, javaSummaries.size());
        assertEquals(2, goSummaries.size());

        // Build cross-language call graph with Java ingress & Go worker
        var nodes = new TreeMap<String, CallNode>();
        nodes.put("ep:order", new CallNode("ep:order", "POST /api/orders", "ENDPOINT", "edge-ingress", "services/edge/EdgeController.java", 5, 7, true));
        nodes.put("ep:recs", new CallNode("ep:recs", "GET /api/recs", "ENDPOINT", "edge-ingress", "services/edge/EdgeController.java", 8, 14, true));
        nodes.put("ep:worker", new CallNode("ep:worker", "WorkerGrpc.Execute", "GRPC_HANDLER", "worker-svc", "services/worker/worker.go", 6, 17, true));
        nodes.put("ob:ledger", new CallNode("ob:ledger", "restTemplate.getForObject", "SERVICE", "external-ledger", "external/ledger.yaml", 1, 1, false));
        nodes.put("ob:recs", new CallNode("ob:recs", "restTemplate.getForObject", "SERVICE", "recs-svc", "services/recs/api.yaml", 1, 1, false));
        nodes.put("ob:primary", new CallNode("ob:primary", "primary.Fetch", "SERVICE", "cache-svc", "services/cache/api.proto", 1, 1, false));
        nodes.put("ob:fallback", new CallNode("ob:fallback", "fallback.Fetch", "SERVICE", "storage-svc", "services/storage/api.proto", 1, 1, false));

        var edges = List.of(
                new CallEdge("ep:order", "ob:ledger", "CALLS_SERVICE"),
                new CallEdge("ep:recs", "ob:recs", "CALLS_SERVICE"),
                new CallEdge("ep:worker", "ob:primary", "INVOKES_RPC"),
                new CallEdge("ep:worker", "ob:fallback", "INVOKES_RPC")
        );

        var summaryMap = new HashMap<String, List<MethodErrorSummary>>();
        summaryMap.put("ep:order", javaSummaries.stream().filter(s -> s.methodId().contains("handleOrder")).toList());
        summaryMap.put("ep:recs", javaSummaries.stream().filter(s -> s.methodId().contains("handleRecommendations")).toList());
        summaryMap.put("ep:worker", goSummaries);

        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/polyglot-resilience");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("polyglot-snap"));
        var evCtx = EvidenceContext.forSnapshot(snap);
        ContentDigest graphDigest = ContentDigest.sha256Utf8("polyglot-graph");

        ResilienceReport report = FailCloseResilienceAnalyzer.analyzeCallGraph(
                graphDigest, evCtx, new CallGraph(nodes, edges), summaryMap, AnalysisConfig.defaults());

        assertEquals(4, report.pathVerdicts().size());

        // Evaluate declarative fail-close-tier-policy
        String policyYaml = """
                schema: architecture-policy:v1
                id: resilience.tier-guard
                version: "1"
                fail-close-tier-policy:
                  tiers:
                    - {name: T0, rank: 0}
                    - {name: T1, rank: 1}
                    - {name: T3, rank: 3}
                    - {name: EXTERNAL, rank: 4}
                  rules:
                    - id: ingress.no-fail-close-lower-or-external
                      severity: ERROR
                      fromTiers: [T0, T1]
                      forbidLowerTierFailClose: true
                      forbidExternalFailClose: true
                """;
        var declaredTiers = Map.of(
                "edge-ingress", "T0",
                "worker-svc", "T1",
                "external-ledger", "EXTERNAL",
                "recs-svc", "T3",
                "cache-svc", "T3",
                "storage-svc", "T3"
        );
        var policyResult = new PolicyEvaluator().evaluateFailCloseTierPolicy(
                policyYaml.getBytes(StandardCharsets.UTF_8), report, declaredTiers, evCtx);

        assertEquals(AssessmentStatus.COMPLETE, policyResult.status());
        assertEquals(Classification.MUST, policyResult.classification());
        // ep:order -> external-ledger (T0 -> EXTERNAL FAIL_CLOSE) AND ep:worker -> storage-svc (T1 -> T3 FAIL_CLOSE)
        // ep:recs -> recs-svc (FAIL_OPEN) and ep:worker -> cache-svc (D2.2 primary FAIL_OPEN) are CLEAN!
        assertEquals(2, policyResult.findings().size());
        assertTrue(policyResult.findings().stream()
                .anyMatch(f -> f.outboundService().equals("external-ledger")
                        && f.flamegraphChain().contains("services/edge/EdgeController.java:5")));
        assertTrue(policyResult.findings().stream()
                .anyMatch(f -> f.outboundService().equals("storage-svc")
                        && f.flamegraphChain().contains("services/worker/worker.go:6")));

        // Verify MCP vertical_trace and policy_findings flamegraph enrichment
        String traceJson = CanonicalJson.write(Map.of(
                "data", Map.of(
                        "graphDigest", graphDigest.value(),
                        "seed", "EdgeController",
                        "hops", List.of(
                                Map.of("nodeId", "ep:order", "label", "POST /api/orders", "kind", "ENDPOINT",
                                        "layer", "API_ROUTE", "sourcePath", "services/edge/EdgeController.java",
                                        "startLine", 5, "endLine", 7, "trustTier", "SOURCE_SYNTAX",
                                        "edgeKind", "CALLS_SERVICE")))));
        String enrichedTrace = McpToolDispatcher.enrichVerticalTracePayload(traceJson, Optional.of(report));
        var traceObj = GraphJson.object(GraphJson.read(enrichedTrace, GraphJson.Limits.defaults()));
        var traceData = GraphJson.object(traceObj.get("data"));
        var fg = GraphJson.object(traceData.get("callPathFlamegraph"));
        assertEquals("MIXED_PATHS", fg.get("resilienceVerdict"));
        assertEquals(TrustTier.SOURCE_SYNTAX.name(), fg.get("trustTier"));
        assertTrue(fg.get("chain").toString().contains("services/edge/EdgeController.java:5"));
        assertEquals(4, GraphJson.array(fg.get("paths")).size());

        // Verify MCP live callTool("vertical_trace") and callTool("policy_findings") via stub backend
        McpToolDispatcher.ApiBackend stubBackend = (method, path, payload) -> {
            if (path.endsWith("/queries/vertical-trace")) {
                return new McpToolDispatcher.BackendResponse(200, traceJson);
            }
            if (path.endsWith("/captured-source")) {
                return new McpToolDispatcher.BackendResponse(200, CanonicalJson.write(Map.of(
                        "data", Map.of("path", "services/edge/EdgeController.java", "content", javaSrc))));
            }
            if (path.endsWith("/findings")) {
                return new McpToolDispatcher.BackendResponse(200, CanonicalJson.write(Map.of(
                        "data", Map.of("items", List.of(Map.of(
                                "identity", ContentDigest.sha256Utf8("f1").value(),
                                "result", Map.of(
                                        "resilienceVerdict", "FAIL_CLOSE",
                                        "bundle", Map.of(
                                                "path", List.of("ep:order", "ob:ledger"),
                                                "sources", List.of(Map.of(
                                                        "span", Map.of("path", "services/edge/EdgeController.java",
                                                                "startLine", 6, "endLine", 6)))))))))));
            }
            return new McpToolDispatcher.BackendResponse(404, "{}");
        };

        var vtCall = McpToolDispatcher.callTool(
                Map.of("name", "vertical_trace", "arguments", Map.of("graph", graphDigest.value(), "seed", "EdgeController")),
                stubBackend, new AtomicReference<>(graphDigest.value()), new AtomicReference<>("ctx"));
        assertEquals(false, vtCall.get("isError"));
        String vtText = GraphJson.string(GraphJson.object(GraphJson.array(vtCall.get("content")).getFirst()).get("text"));
        assertTrue(vtText.contains("\"callPathFlamegraph\""), vtText);
        assertTrue(vtText.contains("services/edge/EdgeController.java:5"), vtText);
        assertTrue(vtText.contains("\"resilienceVerdict\":\"MIXED_PATHS\""), vtText);

        String reportId = ContentDigest.sha256Utf8("rep1").value();
        var pfCall = McpToolDispatcher.callTool(
                Map.of("name", "policy_findings", "arguments", Map.of("graph", graphDigest.value(), "report", reportId)),
                stubBackend, new AtomicReference<>(graphDigest.value()), new AtomicReference<>("ctx"));
        assertEquals(false, pfCall.get("isError"));
        String pfText = GraphJson.string(GraphJson.object(GraphJson.array(pfCall.get("content")).getFirst()).get("text"));
        assertTrue(pfText.contains("\"callPathFlamegraph\""), pfText);
        assertTrue(pfText.contains("services/edge/EdgeController.java:6"), pfText);
        assertTrue(pfText.contains("\"resilienceVerdict\":\"FAIL_CLOSE\""), pfText);

        String enrichedFindings = McpToolDispatcher.enrichPolicyFindingsPayload(pfText, Optional.of(policyResult));
        assertTrue(enrichedFindings.contains("\"failCloseTierFindings\""));
        assertTrue(enrichedFindings.contains("services/worker/worker.go:6"));
    }
}
