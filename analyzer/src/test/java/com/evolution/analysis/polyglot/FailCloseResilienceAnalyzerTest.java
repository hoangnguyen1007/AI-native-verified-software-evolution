package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.policy.PolicyEvaluator;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.*;
import com.evolution.analysis.query.ArchitectureQuery.Classification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FailCloseResilienceAnalyzerTest {

    private static EvidenceContext sampleContext() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/fail-close-resilience");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("wp1.2-snapshot"));
        return EvidenceContext.forSnapshot(snap);
    }

    @Test
    void mixedInboundOutboundPathsAreNeverFlattenedAndKeyedDeterministically() {
        var nodes = new TreeMap<String, CallNode>();
        nodes.put("ib:getOrder", new CallNode("ib:getOrder", "GET /api/orders", "ENDPOINT", "gateway", "src/OrderController.java", 12, 30, true));
        nodes.put("m:directPath", new CallNode("m:directPath", "OrderService.directFetch", "METHOD", "gateway", "src/OrderService.java", 40, 52, false));
        nodes.put("m:fallbackPath", new CallNode("m:fallbackPath", "OrderService.cachedFetch", "METHOD", "gateway", "src/OrderService.java", 60, 75, false));
        nodes.put("m:clientCall", new CallNode("m:clientCall", "InventoryGateway.fetch", "METHOD", "gateway", "src/InventoryGateway.java", 18, 28, false));
        nodes.put("ob:inventory", new CallNode("ob:inventory", "inventory-svc", "SERVICE", "inventory", "inventory/api.proto", 5, 10, false));

        var edges = List.of(
                new CallEdge("ib:getOrder", "m:directPath", "java.calls"),
                new CallEdge("ib:getOrder", "m:fallbackPath", "java.calls"),
                new CallEdge("m:directPath", "m:clientCall", "java.calls"),
                new CallEdge("m:fallbackPath", "m:clientCall", "java.calls"),
                new CallEdge("m:clientCall", "ob:inventory", "CALLS_SERVICE")
        );
        var cg = new CallGraph(nodes, edges);

        var obSummary = new MethodErrorSummary(
                "java", "m:clientCall", "src/InventoryGateway.java:22#inventoryClient.fetch",
                "src/InventoryGateway.java", 22, 22, "inventoryClient.fetch", "FEIGN",
                Set.of("FeignException", "RuntimeException"), Set.of("FeignException", "RuntimeException"),
                List.of(), PropagationDisposition.PROPAGATES_ERROR, true, false, false, false,
                Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);

        var wrapSummary = new MethodErrorSummary(
                "java", "m:directPath", "src/OrderService.java:45#clientCall",
                "src/OrderService.java", 45, 45, "InventoryGateway.fetch", "METHOD_CALL",
                Set.of("FeignException"), Set.of("OrderUnavailableException"),
                List.of(new ExceptionTransformation("FeignException", "OrderUnavailableException", "CATCH_WRAP_RETHROW")),
                PropagationDisposition.PROPAGATES_ERROR, true, false, false, false,
                Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);

        var suppressSummary = new MethodErrorSummary(
                "java", "m:fallbackPath", "src/OrderService.java:65#clientCall",
                "src/OrderService.java", 65, 65, "InventoryGateway.fetch", "METHOD_CALL",
                Set.of("FeignException", "RuntimeException"), Set.of(),
                List.of(), PropagationDisposition.SUPPRESSED_FAIL_OPEN, true, false, false, false,
                Optional.empty(), Optional.of("TRY_CATCH_DEFAULT"), TrustTier.SOURCE_SYNTAX);

        Map<String, List<MethodErrorSummary>> summaries = Map.of(
                "m:clientCall", List.of(obSummary),
                "m:directPath", List.of(wrapSummary),
                "m:fallbackPath", List.of(suppressSummary));

        ContentDigest digest = ContentDigest.sha256Utf8("mixed-graph");
        ResilienceReport r1 = FailCloseResilienceAnalyzer.analyzeCallGraph(
                digest, sampleContext(), cg, summaries, AnalysisConfig.defaults());
        ResilienceReport r2 = FailCloseResilienceAnalyzer.analyzeCallGraph(
                digest, sampleContext(), cg, summaries, AnalysisConfig.defaults());

        // I2 determinism
        assertEquals(r1, r2);
        assertEquals(r1.identity(), r2.identity());
        assertEquals(CanonicalJson.write(r1.toView()), CanonicalJson.write(r2.toView()));
        assertEquals(TrustTier.SOURCE_SYNTAX, r1.trustTier());

        // Two distinct PathVerdicts for the same (IB, OB) pair: one FAIL_CLOSE, one FAIL_OPEN!
        assertEquals(2, r1.pathVerdicts().size());
        var verdicts = new ArrayList<>(r1.pathVerdicts().values());
        assertTrue(verdicts.stream().anyMatch(v -> v.verdict() == ResilienceVerdict.FAIL_CLOSE
                && v.propagatedExceptions().contains("OrderUnavailableException")
                && v.flamegraphChain().contains("src/OrderController.java:12")
                && v.flamegraphChain().contains("src/OrderService.java:40")));
        assertTrue(verdicts.stream().anyMatch(v -> v.verdict() == ResilienceVerdict.FAIL_OPEN
                && v.flamegraphChain().contains("src/OrderService.java:60")));

        // PairSummary MUST label the pair MIXED_PATHS rather than flattening!
        PairSummary pair = r1.pairSummaries().get("ib:getOrder->ob:inventory");
        assertNotNull(pair);
        assertEquals(ResilienceVerdict.MIXED_PATHS, pair.aggregateVerdict());
        assertEquals(2, pair.totalPaths());
        assertEquals(1, pair.failClosePathCount());
        assertEquals(1, pair.failOpenPathCount());
    }

    @Test
    void xorPathPermutationDedupIsOrderIndependentAndBoundsBranching() {
        // 1. Order-independent XOR property
        long xor1 = FailCloseResilienceAnalyzer.computePathXor(List.of("ib:entry", "m:a", "m:b", "m:sink"));
        long xor2 = FailCloseResilienceAnalyzer.computePathXor(List.of("m:sink", "m:b", "ib:entry", "m:a"));
        long xor3 = FailCloseResilienceAnalyzer.computePathXor(List.of("ib:entry", "m:a", "m:c", "m:sink"));
        assertEquals(xor1, xor2, "XOR fingerprint must be strictly permutation-invariant");
        assertNotEquals(xor1, xor3, "Different method sets must produce distinct XOR fingerprints");

        // 2. Graph with two orderings of the same method set {m:a, m:b}:
        //    ib -> a -> b -> sink  AND  ib -> b -> a -> sink
        var nodes = new TreeMap<String, CallNode>();
        nodes.put("ib:entry", new CallNode("ib:entry", "POST /checkout", "GRPC_HANDLER", "checkout", "svc/handler.go", 10, 20, true));
        nodes.put("m:a", new CallNode("m:a", "stepA", "METHOD", "checkout", "svc/a.go", 5, 15, false));
        nodes.put("m:b", new CallNode("m:b", "stepB", "METHOD", "checkout", "svc/b.go", 5, 15, false));
        nodes.put("m:sink", new CallNode("m:sink", "rpcCall", "METHOD", "checkout", "svc/client.go", 30, 40, false));
        nodes.put("ob:billing", new CallNode("ob:billing", "billing.Charge", "SERVICE", "billing", "proto/billing.proto", 1, 5, false));

        var edges = List.of(
                new CallEdge("ib:entry", "m:a", "polyglot.calls"),
                new CallEdge("ib:entry", "m:b", "polyglot.calls"),
                new CallEdge("m:a", "m:b", "polyglot.calls"),
                new CallEdge("m:b", "m:a", "polyglot.calls"),
                new CallEdge("m:a", "m:sink", "polyglot.calls"),
                new CallEdge("m:b", "m:sink", "polyglot.calls"),
                new CallEdge("m:sink", "ob:billing", "INVOKES_RPC")
        );
        var sinkSummary = new MethodErrorSummary(
                "go", "m:sink", "svc/client.go:32#billingClient.Charge",
                "svc/client.go", 32, 32, "billingClient.Charge", "GRPC_STUB",
                Set.of("error"), Set.of("error"), List.of(),
                PropagationDisposition.PROPAGATES_ERROR, true, false, false, false,
                Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);

        ResilienceReport report = FailCloseResilienceAnalyzer.analyzeCallGraph(
                ContentDigest.sha256Utf8("xor-perm-graph"),
                sampleContext(),
                new CallGraph(nodes, edges),
                Map.of("m:sink", List.of(sinkSummary)),
                AnalysisConfig.defaults());

        // Permutations {ib, a, b, sink} and {ib, b, a, sink} have identical XOR -> collapsed!
        assertTrue(report.xorDedupCollapsedCount() >= 1,
                "Expected XOR permutation dedup to collapse duplicate {a,b}/{b,a} path orderings");

        // 3. Bounded branching (maxBranchFactor = 4 on fan-in of 10 callers)
        var denseNodes = new TreeMap<String, CallNode>();
        var denseEdges = new ArrayList<CallEdge>();
        denseNodes.put("m:hot", new CallNode("m:hot", "hotRpc", "METHOD", "core", "src/Hot.java", 10, 15, false));
        denseNodes.put("ob:ext", new CallNode("ob:ext", "ext-api", "SERVICE", "external", "src/Ext.java", 1, 2, false));
        denseEdges.add(new CallEdge("m:hot", "ob:ext", "CALLS_SERVICE"));
        for (int i = 0; i < 10; i++) {
            String cid = String.format("ib:ep%02d", i);
            denseNodes.put(cid, new CallNode(cid, "GET /ep/" + i, "ENDPOINT", "core", "src/Ep.java", i + 1, i + 2, true));
            denseEdges.add(new CallEdge(cid, "m:hot", "java.calls"));
        }
        var hotSummary = new MethodErrorSummary(
                "java", "m:hot", "src/Hot.java:12#extClient.call",
                "src/Hot.java", 12, 12, "extClient.call", "FEIGN",
                Set.of("FeignException"), Set.of("FeignException"), List.of(),
                PropagationDisposition.PROPAGATES_ERROR, true, false, false, false,
                Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);

        ResilienceReport bounded = FailCloseResilienceAnalyzer.analyzeCallGraph(
                ContentDigest.sha256Utf8("dense-graph"),
                sampleContext(),
                new CallGraph(denseNodes, denseEdges),
                Map.of("m:hot", List.of(hotSummary)),
                new AnalysisConfig(4, 8, 64));

        assertTrue(bounded.branchBoundedTruncated());
        assertEquals(4, bounded.pathVerdicts().size());
    }

    @Test
    void opaqueHandlerEmitsTypedCapabilityGapWithSourceSyntaxTrustTier() {
        var nodes = new TreeMap<String, CallNode>();
        nodes.put("ib:ep", new CallNode("ib:ep", "GET /items", "ENDPOINT", "catalog", "src/CatalogController.java", 10, 20, true));
        nodes.put("m:caller", new CallNode("m:caller", "CatalogClient.get", "METHOD", "catalog", "src/CatalogClient.java", 15, 25, false));
        nodes.put("ob:remote", new CallNode("ob:remote", "remote-svc", "SERVICE", "remote", "src/Remote.java", 1, 5, false));

        var edges = List.of(
                new CallEdge("ib:ep", "m:caller", "java.calls"),
                new CallEdge("m:caller", "ob:remote", "CALLS_SERVICE"));

        var opaqueSummary = new MethodErrorSummary(
                "java", "m:caller", "src/CatalogClient.java:18#remoteClient.fetch",
                "src/CatalogClient.java", 18, 18, "remoteClient.fetch", "FEIGN",
                Set.of("FeignException"), Set.of("FeignException"), List.of(),
                PropagationDisposition.UNKNOWN_OPAQUE, true, false, false, true,
                Optional.of("UNKNOWN_ASYNC_HANDLER"), Optional.empty(), TrustTier.SOURCE_SYNTAX);

        ResilienceReport report = FailCloseResilienceAnalyzer.analyzeCallGraph(
                ContentDigest.sha256Utf8("opaque-graph"),
                sampleContext(),
                new CallGraph(nodes, edges),
                Map.of("m:caller", List.of(opaqueSummary)),
                AnalysisConfig.defaults());

        assertEquals(1, report.pathVerdicts().size());
        PathVerdict pv = report.pathVerdicts().values().iterator().next();
        assertEquals(ResilienceVerdict.UNKNOWN, pv.verdict());
        assertEquals(TrustTier.SOURCE_SYNTAX, pv.trustTier());
        assertEquals(Optional.of(FailCloseResilienceAnalyzer.GAP_REASON_HANDLER_OPAQUE), pv.gapReason());
        assertTrue(report.gaps().stream().anyMatch(g ->
                FailCloseResilienceAnalyzer.GAP_REASON_HANDLER_OPAQUE.equals(g.reasonCode())));
    }

    @Test
    void failCloseTierPolicyFlagsLowerTierAndExternalWithoutInventingMissingTiers() {
        var nodes = new TreeMap<String, CallNode>();
        nodes.put("ib:edge", new CallNode("ib:edge", "GET /checkout", "ENDPOINT", "edge-api", "src/EdgeController.java", 14, 28, true));
        nodes.put("m:rpcLow", new CallNode("m:rpcLow", "EdgeService.callPromo", "METHOD", "edge-api", "src/EdgeService.java", 40, 48, false));
        nodes.put("ob:promo", new CallNode("ob:promo", "promo-svc", "SERVICE", "promo-svc", "promo/api.proto", 1, 5, false));
        nodes.put("ob:partner", new CallNode("ob:partner", "partner-api", "SERVICE", "partner-api", "partner/spec.yaml", 1, 5, false));
        nodes.put("ob:undeclared", new CallNode("ob:undeclared", "mystery-svc", "SERVICE", "mystery-svc", "mystery/api.proto", 1, 5, false));

        var edges = List.of(
                new CallEdge("ib:edge", "m:rpcLow", "java.calls"),
                new CallEdge("m:rpcLow", "ob:promo", "CALLS_SERVICE"),
                new CallEdge("m:rpcLow", "ob:partner", "CALLS_SERVICE"),
                new CallEdge("m:rpcLow", "ob:undeclared", "CALLS_SERVICE"));

        var failClosePromo = new MethodErrorSummary(
                "java", "m:rpcLow", "src/EdgeService.java:42#promo.fetch",
                "src/EdgeService.java", 42, 42, "promo-svc", "FEIGN",
                Set.of("FeignException"), Set.of("FeignException"), List.of(),
                PropagationDisposition.PROPAGATES_ERROR, true, false, false, false,
                Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);
        var failClosePartner = new MethodErrorSummary(
                "java", "m:rpcLow", "src/EdgeService.java:44#partner.call",
                "src/EdgeService.java", 44, 44, "partner-api", "REST_TEMPLATE",
                Set.of("RestClientException"), Set.of("RestClientException"), List.of(),
                PropagationDisposition.PROPAGATES_ERROR, true, false, false, false,
                Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);
        var failCloseMystery = new MethodErrorSummary(
                "java", "m:rpcLow", "src/EdgeService.java:46#mystery.call",
                "src/EdgeService.java", 46, 46, "mystery-svc", "GRPC_STUB",
                Set.of("StatusRuntimeException"), Set.of("StatusRuntimeException"), List.of(),
                PropagationDisposition.PROPAGATES_ERROR, true, false, false, false,
                Optional.empty(), Optional.empty(), TrustTier.SOURCE_SYNTAX);

        ResilienceReport resilience = FailCloseResilienceAnalyzer.analyzeCallGraph(
                ContentDigest.sha256Utf8("tier-policy-graph"),
                sampleContext(),
                new CallGraph(nodes, edges),
                Map.of("m:rpcLow", List.of(failClosePromo, failClosePartner, failCloseMystery)),
                AnalysisConfig.defaults());

        String policyYaml = """
                schema: architecture-policy:v1
                id: resilience.tier-isolation
                version: "1"
                fail-close-tier-policy:
                  tiers:
                    - {name: T0, rank: 0}
                    - {name: T1, rank: 1}
                    - {name: T2, rank: 2}
                    - {name: T3, rank: 3}
                    - {name: EXTERNAL, rank: 4}
                  rules:
                    - id: ingress.no-fail-close-lower-or-external
                      severity: ERROR
                      fromTiers: [T0, T1, T2]
                      forbidLowerTierFailClose: true
                      forbidExternalFailClose: true
                """;

        // Repo metadata explicitly declares edge-api=T0, promo-svc=T3, partner-api=EXTERNAL,
        // and intentionally omits mystery-svc (must NEVER invent a tier per I4!).
        Map<String, String> declaredTiers = Map.of(
                "edge-api", "T0",
                "promo-svc", "T3",
                "partner-api", "EXTERNAL");

        var evaluator = new PolicyEvaluator();
        var result = evaluator.evaluateFailCloseTierPolicy(
                policyYaml.getBytes(StandardCharsets.UTF_8),
                resilience,
                declaredTiers,
                sampleContext());

        assertEquals(Classification.MUST, result.classification());
        assertEquals(AssessmentStatus.PARTIAL, result.status());
        assertEquals(TrustTier.SOURCE_SYNTAX, result.trustTier());
        assertEquals(3, result.findings().size());

        // T0 -> T3 flagged as MUST with file:line flamegraph
        var promoFinding = result.findings().stream()
                .filter(f -> f.outboundService().equals("promo-svc"))
                .findFirst().orElseThrow();
        assertEquals(Classification.MUST, promoFinding.classification());
        assertEquals(Optional.of("T0"), promoFinding.inboundTier());
        assertEquals(Optional.of("T3"), promoFinding.outboundTier());
        assertTrue(promoFinding.flamegraphChain().contains("src/EdgeController.java:14"));

        // T0 -> EXTERNAL flagged as MUST
        var partnerFinding = result.findings().stream()
                .filter(f -> f.outboundService().equals("partner-api"))
                .findFirst().orElseThrow();
        assertEquals(Classification.MUST, partnerFinding.classification());
        assertEquals(Optional.of("EXTERNAL"), partnerFinding.outboundTier());

        // Undeclared mystery-svc MUST stay UNKNOWN with FAIL_CLOSE_TIER_METADATA_MISSING (I4: zero invented tiers!)
        var mysteryFinding = result.findings().stream()
                .filter(f -> f.outboundService().equals("mystery-svc"))
                .findFirst().orElseThrow();
        assertEquals(Classification.UNKNOWN, mysteryFinding.classification());
        assertTrue(mysteryFinding.outboundTier().isEmpty());
        assertEquals(Optional.of(FailCloseResilienceAnalyzer.GAP_REASON_TIER_METADATA_MISSING), mysteryFinding.gapReason());
        assertTrue(result.gaps().stream().anyMatch(g ->
                FailCloseResilienceAnalyzer.GAP_REASON_TIER_METADATA_MISSING.equals(g.reasonCode())));
    }
}
