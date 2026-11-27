package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2Service;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchMcpTest {
    private static Map<String,Object> message(int id, String method, Map<String,Object> params) {
        return Map.of("jsonrpc","2.0","id",id,"method",method,"params",params);
    }
    private static Map<String,Object> parse(String json) {
        return GraphJson.object(GraphJson.read(json,GraphJson.Limits.defaults()));
    }
    @Test void stdioToolsReadRealGraphAndRejectUnlistedOperations() throws Exception {
        var fixture=new QueryFixture().node("a");
        try (var server=new ArchitectureHttpServer(new ArchitectureQueryApi(fixture.store()),0)) {
            server.start();
            var bridge=new WorkbenchMcpMain(server.uri().getPort());
            String graph=fixture.graph().identity().value();
            var calls=List.of(
                message(1,"initialize",Map.of("protocolVersion","2025-11-25","capabilities",Map.of(),"clientInfo",Map.of("name","test","version","1"))),
                Map.of("jsonrpc","2.0","method","notifications/initialized"),
                message(2,"tools/list",Map.of()),
                message(3,"tools/call",Map.of("name","graph_get","arguments",Map.of("graph",graph))),
                message(4,"tools/call",Map.of("name","graph_query","arguments",Map.of("graph",graph,"context",fixture.context,"operation","summary"))),
                message(5,"tools/call",Map.of("name","architecture_insights","arguments",Map.of("graph",graph,"context",fixture.context,"scope","TYPE","topN",5))),
                message(6,"tools/call",Map.of("name","graph_query","arguments",Map.of("graph",graph,"context",fixture.context,"operation","spring"))),
                message(7,"tools/call",Map.of("name","list_captured_files","arguments",Map.of("graph",graph))),
                message(8,"tools/call",Map.of("name","repo_map","arguments",Map.of("graph",graph,"maxFiles",5))),
                message(9,"tools/call",Map.of("name","connections","arguments",Map.of("graph",graph,"context",fixture.context))),
                message(10,"tools/call",Map.of("name","graph_query","arguments",Map.of("graph",graph,"context",fixture.context,"operation","delete")))
            );
            var input=new StringReader(String.join("\n",calls.stream().map(CanonicalJson::write).toList())+"\n");
            var output=new StringWriter();
            bridge.run(input,new PrintWriter(output,true));
            var responses=output.toString().lines().map(WorkbenchMcpTest::parse).toList();
            assertEquals(10,responses.size());
            assertEquals("2025-11-25",GraphJson.object(responses.get(0).get("result")).get("protocolVersion"));
            var toolList = GraphJson.array(GraphJson.object(responses.get(1).get("result")).get("tools"));
            assertEquals(19,toolList.size());
            var toolNames = toolList.stream().map(t -> GraphJson.string(GraphJson.object(t).get("name"))).collect(java.util.stream.Collectors.toSet());
            assertTrue(toolNames.containsAll(Set.of("get_architecture_score","architecture_brief","read_markdown_section","inspect_advisory_links","list_captured_files","read_captured_source","search_captured_source","inspect_codeowners","repo_map","suggest_policy","repair_options","connections")));
            var scoreTool = toolList.stream().map(GraphJson::object)
                    .filter(t -> "get_architecture_score".equals(t.get("name"))).findFirst().orElseThrow();
            assertEquals(true, GraphJson.object(scoreTool.get("annotations")).get("readOnlyHint"));
            var scoreSchema = GraphJson.object(scoreTool.get("inputSchema"));
            assertEquals(List.of("graph"), GraphJson.array(scoreSchema.get("required")));
            assertTrue(GraphJson.object(scoreSchema.get("properties")).keySet().containsAll(Set.of("graph","context","policy","report")));
            for (int index : List.of(2,3,4,5,6,7,8)) {
                var result=GraphJson.object(responses.get(index).get("result"));
                assertEquals(false,result.get("isError"), () -> "Tool index " + index + " failed: " + result);
                var text=GraphJson.object(GraphJson.array(result.get("content")).get(0)).get("text").toString();
                assertTrue(text.contains(graph) || text.contains("apiVersion"));
            }
            assertEquals(-32602,((Number)GraphJson.object(responses.get(9).get("error")).get("code")).intValue());
        }
    }

    @Test void getArchitectureScoreMcpToolForwardsRequestReturnsV2ScoreAndRejectsInvalidArgs() throws Exception {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/mcp-v2.git");
        var snapshot = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("mcp-v2-snap"));
        var analysisId = AnalysisIdentity.fromCanonicalManifestInputs("mcp-mod");
        String analysisCtx = analysisId.value();
        var module = ModuleIdentity.from(repo, ".");
        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snapshot));
        var manifestEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", module, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", module, "path", "."));

        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manifestEv, modEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        nodes.add(new KnowledgeGraph.Node(module.value(), analysisCtx, "MODULE", List.of(modEv.identity())));
        var typeIds = new HashMap<String, String>();
        var pkgIds = new HashSet<String>();
        for (var spec : List.of(
                List.of("com.acme.web", "OrderController"),
                List.of("com.acme.service", "OrderService"),
                List.of("com.acme.repository", "OrderRepository"))) {
            var pkgEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module), EntityKind.PACKAGE,
                    JavaSymbolName.packageName(spec.get(0)).canonicalName(), Optional.empty());
            if (pkgIds.add(pkgEntity.identity().value())) {
                var pkgDecl = GraphEvidence.capture("java.declaration", Map.of("entity", pkgEntity, "spelling", spec.get(0), "status", "RESOLVED"));
                evidence.add(pkgDecl);
                nodes.add(new KnowledgeGraph.Node(pkgEntity.identity().value(), analysisCtx, "PACKAGE", List.of(pkgDecl.identity())));
            }
            var typeEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module), EntityKind.TYPE,
                    JavaSymbolName.topLevelType(spec.get(0), spec.get(1)).canonicalName(), Optional.empty());
            var decl = GraphEvidence.capture("java.declaration", Map.of("entity", typeEntity, "spelling", spec.get(1), "status", "RESOLVED"));
            var shape = GraphEvidence.capture("java.type-shape", Map.of("type", typeEntity.identity().value(), "kind", "CLASS", "abstractType", false));
            evidence.add(decl);
            evidence.add(shape);
            nodes.add(new KnowledgeGraph.Node(typeEntity.identity().value(), analysisCtx, "TYPE", List.of(decl.identity(), shape.identity())));
            edges.add(new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("contains", List.of(pkgEntity.identity().value(), typeEntity.identity().value())),
                    analysisCtx, "graph.contains", pkgEntity.identity().value(),
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(typeEntity.identity().value()), Optional.empty()),
                    List.of(decl.identity()), Optional.empty()));
            typeIds.put(spec.get(1), typeEntity.identity().value());
        }
        edges.add(new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("dep", List.of("c", "s")), analysisCtx, "graph.depends-on",
                typeIds.get("OrderController"), new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(typeIds.get("OrderService")), Optional.empty()),
                List.of(manifestEv.identity()), Optional.empty()));
        edges.add(new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("dep", List.of("s", "r")), analysisCtx, "graph.depends-on",
                typeIds.get("OrderService"), new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(typeIds.get("OrderRepository")), Optional.empty()),
                List.of(manifestEv.identity()), Optional.empty()));
        var graph = new KnowledgeGraph(snapshot,
                List.of(
                        new KnowledgeGraph.Context(snapshot.value(), snapshot, Optional.empty(), snapEv.identity()),
                        new KnowledgeGraph.Context(analysisCtx, snapshot, Optional.of(analysisId), manifestEv.identity())),
                nodes, edges, evidence);
        var queries = new ArchitectureQueryService(graph);
        var scoreService = new ArchitectureAssessmentV2Service();
        String graphDigest = graph.identity().value();
        var receivedPayloads = new ArrayList<Map<String, Object>>();

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/v1/graphs/" + graphDigest + "/architecture-score", exchange -> {
                assertEquals("POST", exchange.getRequestMethod());
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var reqBody = GraphJson.object(GraphJson.read(raw, GraphJson.Limits.defaults()));
                receivedPayloads.add(reqBody);
                String targetCtx = reqBody.get("context") instanceof String s ? s : snapshot.value();
                var result = scoreService.assess(queries, targetCtx);
                byte[] out = CanonicalJson.write(Map.of("apiVersion", "v1", "data", result)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();

            var bridge = new WorkbenchMcpMain(server.getAddress().getPort());
            String fakeReportDigest = ContentDigest.sha256Utf8("rep").value();
            var calls = List.of(
                    message(0, "initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "test", "version", "1"))),
                    Map.of("jsonrpc", "2.0", "method", "notifications/initialized"),
                    message(1, "tools/call", Map.of("name", "get_architecture_score",
                            "arguments", Map.of("graph", graphDigest, "context", analysisCtx))),
                    message(2, "tools/call", Map.of("name", "get_architecture_score",
                            "arguments", Map.of("graph", graphDigest, "policy", "schema: architecture-policy:v1", "report", fakeReportDigest))),
                    message(3, "tools/call", Map.of("name", "get_architecture_score",
                            "arguments", Map.of("graph", "not-a-sha256"))),
                    message(4, "tools/call", Map.of("name", "get_architecture_score",
                            "arguments", Map.of("graph", graphDigest, "unexpectedKey", "nope")))
            );
            var input = new StringReader(String.join("\n", calls.stream().map(CanonicalJson::write).toList()) + "\n");
            var output = new StringWriter();
            bridge.run(input, new PrintWriter(output, true));
            var responses = output.toString().lines().map(WorkbenchMcpTest::parse).toList();
            assertEquals(5, responses.size());
            assertEquals(2, receivedPayloads.size());
            assertEquals(analysisCtx, receivedPayloads.get(0).get("context"));
            assertEquals("schema: architecture-policy:v1", receivedPayloads.get(1).get("policy"));
            assertEquals(fakeReportDigest, receivedPayloads.get(1).get("report"));

            var res1 = GraphJson.object(responses.get(1).get("result"));
            assertEquals(false, res1.get("isError"));
            String text1 = GraphJson.object(GraphJson.array(res1.get("content")).get(0)).get("text").toString();
            assertTrue(text1.contains("architecture.score-formula"));
            assertTrue(text1.contains("arch.acyclic-integrity"));
            assertTrue(text1.contains("Layered Architecture"));

            var res2 = GraphJson.object(responses.get(2).get("result"));
            assertEquals(false, res2.get("isError"));
            String text2 = GraphJson.object(GraphJson.array(res2.get("content")).get(0)).get("text").toString();
            assertTrue(text2.contains("WHOLE_REPOSITORY"));

            assertEquals(-32602, ((Number) GraphJson.object(responses.get(3).get("error")).get("code")).intValue());
            assertEquals(-32602, ((Number) GraphJson.object(responses.get(4).get("error")).get("code")).intValue());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void proxyAutoBindReadsJobsItemsAndFetchInsightsPropagatesUpstreamErrors() throws Exception {
        String graphDigest = ContentDigest.sha256Utf8("proxy-autobind-graph").value();
        String snapId = "snapshot:" + ContentDigest.sha256Utf8("proxy-autobind-snap").value();
        String ctxId = "analysis:main";

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            // Live GET /api/v1/analysis/jobs returns data.items (NOT data.jobs)
            server.createContext("/api/v1/analysis/jobs", exchange -> {
                byte[] out = CanonicalJson.write(Map.of(
                        "apiVersion", "v1",
                        "data", Map.of("items", List.of(Map.of(
                                "id", UUID.randomUUID().toString(),
                                "state", "SUCCEEDED",
                                "result", Map.of("graph", graphDigest, "snapshot", snapId))))))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.createContext("/api/v1/graphs/" + graphDigest, exchange -> {
                String path = exchange.getRequestURI().getPath();
                int status = 200;
                String body;
                if (path.endsWith("/architecture-insights")) {
                    // P2-4: Simulate 503 upstream failure on insights
                    status = 503;
                    body = CanonicalJson.write(Map.of("error", Map.of("code", "INSIGHTS_UNAVAILABLE", "message", "Upstream failed")));
                } else if (path.endsWith("/architecture-score")) {
                    body = CanonicalJson.write(Map.of("data", Map.of("score", new java.math.BigDecimal("85.5"), "status", "COMPLETE")));
                } else if (path.endsWith("/advisory-links")) {
                    body = CanonicalJson.write(Map.of("data", Map.of("links", List.of(), "nodes", List.of())));
                } else {
                    body = CanonicalJson.write(Map.of("data", Map.of(
                            "snapshot", snapId,
                            "contexts", List.of(Map.of("identity", ctxId, "analysis", Map.of("module", "."))))));
                }
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();

            // Proxy mode with NO explicit --graph / --context: must auto-bind via data.items!
            var bridge = new WorkbenchMcpMain(server.getAddress().getPort());
            var calls = List.of(
                    message(1, "initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "test", "version", "1"))),
                    // P1-2: Auto-bound graph_get with empty arguments {} and resource evo://score-v2
                    message(2, "tools/call", Map.of("name", "graph_get", "arguments", Map.of())),
                    message(3, "resources/read", Map.of("uri", "evo://score-v2")),
                    // P2-4: Insights returns 503 -> architecture_brief must set isError=true, resources must return UPSTREAM_ERROR
                    message(4, "tools/call", Map.of("name", "architecture_brief", "arguments", Map.of())),
                    message(5, "resources/read", Map.of("uri", "evo://report")),
                    message(6, "resources/read", Map.of("uri", "evo://hotspots")),
                    message(7, "resources/read", Map.of("uri", "evo://cycles"))
            );
            var input = new StringReader(String.join("\n", calls.stream().map(CanonicalJson::write).toList()) + "\n");
            var output = new StringWriter();
            bridge.run(input, new PrintWriter(output, true));
            var responses = output.toString().lines().map(WorkbenchMcpTest::parse).toList();
            assertEquals(7, responses.size());

            // P1-2 assertions: auto-bound graph_get and evo://score-v2 succeeded
            var graphGetRes = GraphJson.object(responses.get(1).get("result"));
            assertEquals(false, graphGetRes.get("isError"), "Auto-bind via data.items must succeed: " + graphGetRes);
            String scoreText = GraphJson.string(GraphJson.object(
                    GraphJson.array(GraphJson.object(responses.get(2).get("result")).get("contents")).getFirst()).get("text"));
            assertFalse(scoreText.contains("NO_DEFAULT_GRAPH"), "evo://score-v2 must auto-bind default graph: " + scoreText);
            assertTrue(scoreText.contains("85.5"), "Unexpected scoreText: " + scoreText);

            // P2-4 assertions: 503 from /architecture-insights must NEVER be swallowed as empty success
            var briefRes = GraphJson.object(responses.get(3).get("result"));
            assertEquals(true, briefRes.get("isError"), "architecture_brief must return isError=true on 503 insights: " + briefRes);
            for (int rIdx : List.of(4, 5, 6)) {
                String resText = GraphJson.string(GraphJson.object(
                        GraphJson.array(GraphJson.object(responses.get(rIdx).get("result")).get("contents")).getFirst()).get("text"));
                assertTrue(resText.contains("UPSTREAM_ERROR"), "Resource index " + rIdx + " must report UPSTREAM_ERROR: " + resText);
                assertTrue(resText.contains("503"), "Resource index " + rIdx + " must include status 503: " + resText);
                assertFalse(resText.contains("\"hotspots\":[]"), "Must not fabricate empty hotspots on 503: " + resText);
                assertFalse(resText.contains("\"tangles\":[]"), "Must not fabricate empty tangles on 503: " + resText);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directStoreMcpWiresCasContentFallbackForPolyglotAdvisoryAndMarkdown(@TempDir Path tempDir) throws Exception {
        // Store SQL + Markdown strictly inside acquisition/content CAS (zero inline bytesBase64 in graph JSON!)
        Path casDir = tempDir.resolve("acquisition/content");
        var cas = new StreamingContentStore(casDir);
        byte[] sqlBytes = "CREATE TABLE settlement_ledger (id BIGINT PRIMARY KEY, amount NUMERIC);\n".getBytes(StandardCharsets.UTF_8);
        byte[] mdBytes = "# Ledger Spec\n\n## Settlement Rules\nAll payouts settle via `settlement_ledger`.\n".getBytes(StandardCharsets.UTF_8);
        var sqlHandle = cas.put(new ByteArrayInputStream(sqlBytes), () -> false);
        var mdHandle = cas.put(new ByteArrayInputStream(mdBytes), () -> false);

        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/direct-cas.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("direct-cas-snap"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("direct-cas-mod");
        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of()));
        var sqlInvEv = GraphEvidence.capture("polyglot.inventory-entry", Map.of(
                "fields", Map.of(
                        "kind", "FILE",
                        "disposition", "ACQUIRED",
                        "rawPath", LogicalPath.utf8("db/V1__settlement.sql").rawBase64(),
                        "languageCandidates", List.of("sql"),
                        "content", Map.of("digest", sqlHandle.digest().value(), "size", sqlHandle.size()))));
        var mdInvEv = GraphEvidence.capture("polyglot.inventory-entry", Map.of(
                "fields", Map.of(
                        "kind", "FILE",
                        "disposition", "ACQUIRED",
                        "rawPath", LogicalPath.utf8("docs/ledger.md").rawBase64(),
                        "languageCandidates", List.of("markdown"),
                        "content", Map.of("digest", mdHandle.digest().value(), "size", mdHandle.size()))));

        var graph = new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(analysis.value(), snap, Optional.of(analysis), manEv.identity())),
                List.of(), List.of(), List.of(snapEv, manEv, sqlInvEv, mdInvEv));

        Path graphsDir = tempDir.resolve("graphs");
        Files.createDirectories(graphsDir);
        new FileGraphStore(graphsDir).save(graph);

        // Reset fallback and prime AdvisoryOverlayService BEFORE openDirectStore() to prove cache invalidates on CAS registration!
        RepairSource.registerContentFallback(null);
        var preCasOverlay = com.evolution.backend.query.advisory.AdvisoryOverlayService.forGraph(graph);
        assertTrue(preCasOverlay.nodes().stream().noneMatch(n -> "SQL_TABLE".equals(n.kind())),
                "Without CAS fallback, CAS-only SQL table cannot be read yet");
        try {
            var directMcp = WorkbenchMcpMain.openDirectStore(tempDir, null, null);
            var calls = List.of(
                    message(1, "initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "test", "version", "1"))),
                    message(2, "tools/call", Map.of("name", "inspect_advisory_links", "arguments", Map.of())),
                    message(3, "tools/call", Map.of("name", "read_markdown_section",
                            "arguments", Map.of("path", "docs/ledger.md", "heading", "Settlement Rules")))
            );
            var input = new StringReader(String.join("\n", calls.stream().map(CanonicalJson::write).toList()) + "\n");
            var output = new StringWriter();
            directMcp.run(input, new PrintWriter(output, true));
            var responses = output.toString().lines().map(WorkbenchMcpTest::parse).toList();
            assertEquals(3, responses.size());

            var advRes = GraphJson.object(responses.get(1).get("result"));
            assertEquals(false, advRes.get("isError"));
            String advText = GraphJson.string(GraphJson.object(GraphJson.array(advRes.get("content")).getFirst()).get("text"));
            assertTrue(advText.contains("settlement_ledger"), "CAS-backed SQL table must be extracted in --store mode: " + advText);
            assertTrue(advText.contains("SQL_TABLE"), "Expected SQL_TABLE advisory node from CAS: " + advText);

            var mdRes = GraphJson.object(responses.get(2).get("result"));
            assertEquals(false, mdRes.get("isError"));
            String mdText = GraphJson.string(GraphJson.object(GraphJson.array(mdRes.get("content")).getFirst()).get("text"));
            assertTrue(mdText.contains("\"found\":true"), "CAS-backed Markdown section must be found in --store mode: " + mdText);
            assertTrue(mdText.contains("All payouts settle via"), "Expected Markdown section content from CAS: " + mdText);
        } finally {
            RepairSource.registerContentFallback(null);
        }
    }
}
