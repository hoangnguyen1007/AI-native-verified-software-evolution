package com.evolution.backend.query;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.evolution.backend.query.ArchitectureQueryApiTest.body;
import static com.evolution.backend.query.ArchitectureQueryApiTest.bytes;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureAgentTest {
    @Test void executesMultiStepToolLoopAndRecordsVerifiedCitationsAndTrace() throws Exception {
        var fixture = new QueryFixture()
                .node("controller")
                .node("service")
                .node("repository")
                .edge("controller", "service")
                .edge("service", "repository");
        var calls = new AtomicInteger();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/chat/completions", exchange -> {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                var req = GraphJson.object(GraphJson.read(new String(requestBody, StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
                int call = calls.incrementAndGet();
                String replyJson;
                if (call == 1) {
                    assertTrue(req.containsKey("tools"));
                    replyJson = CanonicalJson.write(Map.of(
                            "model", "agent-model",
                            "choices", List.of(Map.of("finish_reason", "tool_calls", "message", Map.of(
                                    "role", "assistant",
                                    "content", "",
                                    "tool_calls", List.of(
                                            Map.of("id", "call_1", "type", "function", "function", Map.of(
                                                    "name", "search_symbols",
                                                    "arguments", "{\"query\":\"service\",\"scope\":\"TYPE\"}")),
                                            Map.of("id", "call_2", "type", "function", "function", Map.of(
                                                    "name", "get_architecture_insights",
                                                    "arguments", "{}"))))))));
                } else {
                    String content = CanonicalJson.write(Map.of(
                            "status", "ADVICE",
                            "conversationTitle", "Service dependency trace",
                            "answer", "The snapshot shows `controller -> service -> repository` [E4].",
                            "citations", List.of("E4", "E5"),
                            "limitations", List.of()));
                    replyJson = CanonicalJson.write(Map.of(
                            "model", "agent-model",
                            "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of(
                                    "role", "assistant",
                                    "content", content)))));
                }
                byte[] out = replyJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            mock.start();

            var endpoint = URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1/chat/completions");
            var model = new ArchitectureChatModel(endpoint, "agent-model", null, false);
            var api = ArchitectureQueryApi.withArchitectureChat(new ArchitectureQueryApi(fixture.store()), model);

            var response = api.handle("POST",
                    "/api/v1/graphs/" + fixture.request().graph().value() + "/architecture-chat",
                    "application/json",
                    bytes(Map.of(
                            "context", fixture.context,
                            "mode", "EXPLORE",
                            "agentic", true,
                            "messages", List.of(Map.of("role", "user", "content", "Trace how controller reaches repository and identify hotspots.")))),
                    () -> false);

            assertEquals(200, response.status(), () -> body(response).toString());
            var data = GraphJson.object(body(response).get("data"));
            assertEquals("ADVICE", data.get("status"));
            assertEquals(2, calls.get());
            var trace = GraphJson.array(data.get("agentTrace"));
            assertEquals(2, trace.size());
            assertEquals("search_symbols", GraphJson.object(trace.get(0)).get("tool"));
            assertEquals("get_architecture_insights", GraphJson.object(trace.get(1)).get("tool"));
            var citations = GraphJson.array(data.get("citations"));
            assertFalse(citations.isEmpty());
        } finally {
            mock.stop(0);
        }
    }

    @Test void fallsBackToSingleTurnWhenToolsRejectedAndRespectsSourceConsentGate() throws Exception {
        var fixture = new QueryFixture().node("controller").node("repository").edge("controller", "repository");
        var calls = new AtomicInteger();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/chat/completions", exchange -> {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                var req = GraphJson.object(GraphJson.read(new String(requestBody, StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
                int call = calls.incrementAndGet();
                if (call == 1 && req.containsKey("tools")) {
                    byte[] err = "{\"error\":\"tools unsupported\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, err.length);
                    exchange.getResponseBody().write(err);
                    exchange.close();
                    return;
                }
                String content = CanonicalJson.write(Map.of(
                        "status", "ADVICE",
                        "conversationTitle", "Fallback explanation",
                        "answer", "Verified fallback insight using [E1].",
                        "citations", List.of("E1"),
                        "limitations", List.of()));
                byte[] out = CanonicalJson.write(Map.of(
                        "model", "fallback-model",
                        "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("role", "assistant", "content", content)))))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            mock.start();

            var endpoint = URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1/chat/completions");
            var model = new ArchitectureChatModel(endpoint, "fallback-model", null, false);
            var api = ArchitectureQueryApi.withArchitectureChat(new ArchitectureQueryApi(fixture.store()), model);

            var response = api.handle("POST",
                    "/api/v1/graphs/" + fixture.request().graph().value() + "/architecture-chat",
                    "application/json",
                    bytes(Map.of(
                            "context", fixture.context,
                            "mode", "EXPLORE",
                            "agentic", true,
                            "includeSource", false,
                            "messages", List.of(Map.of("role", "user", "content", "Explain the captured structure.")))),
                    () -> false);

            assertEquals(200, response.status(), () -> body(response).toString());
            var data = GraphJson.object(body(response).get("data"));
            assertEquals("ADVICE", data.get("status"));
            assertEquals(2, calls.get());
        } finally {
            mock.stop(0);
        }
    }

    @Test void streamsSseEventsAndSynthesizesSuggestedPolicyOverHttp() throws Exception {
        var fixture = new QueryFixture()
                .node("controller")
                .node("service")
                .node("repository")
                .edge("controller", "service")
                .edge("service", "repository");
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/chat/completions", exchange -> {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                var req = GraphJson.object(GraphJson.read(new String(requestBody, StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
                if (Boolean.TRUE.equals(req.get("stream"))) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    String chunk1 = "data: " + CanonicalJson.write(Map.of("model", "stream-model", "choices",
                            List.of(Map.of("delta", Map.of("content", "## Summary\n\nObserved dependency edge "))))) + "\n\n";
                    String chunk2 = "data: " + CanonicalJson.write(Map.of("model", "stream-model", "choices",
                            List.of(Map.of("delta", Map.of("content", "in [E1]."))))) + "\n\n";
                    String done = "data: [DONE]\n\n";
                    exchange.getResponseBody().write((chunk1 + chunk2 + done).getBytes(StandardCharsets.UTF_8));
                    exchange.close();
                    return;
                }
                byte[] out = CanonicalJson.write(Map.of(
                        "model", "stream-model",
                        "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of(
                                "role", "assistant",
                                "content", "## Summary\n\nObserved dependency edge in [E1].")))))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            mock.start();

            var endpoint = URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1/chat/completions");
            var model = new ArchitectureChatModel(endpoint, "stream-model", null, false);
            var api = ArchitectureQueryApi.withArchitectureChat(new ArchitectureQueryApi(fixture.store()), model);

            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

                var sseReq = HttpRequest.newBuilder(server.uri().resolve(
                                "/api/v1/graphs/" + fixture.request().graph().value() + "/architecture-chat/stream"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                "context", fixture.context,
                                "mode", "EXPLORE",
                                "messages", List.of(Map.of("role", "user", "content", "What types were analyzed?"))))))
                        .build();
                var sseResp = client.send(sseReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertEquals(200, sseResp.statusCode());
                assertTrue(sseResp.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
                assertTrue(sseResp.body().contains("event: stage"));
                assertTrue(sseResp.body().contains("event: delta"));
                assertTrue(sseResp.body().contains("event: complete"));
                assertTrue(sseResp.body().contains("Observed dependency edge in [E1]."));

                var policyReq = HttpRequest.newBuilder(server.uri().resolve(
                                "/api/v1/graphs/" + fixture.request().graph().value() + "/suggested-policy"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                "context", fixture.context))))
                        .build();
                var policyResp = client.send(policyReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertEquals(200, policyResp.statusCode(), policyResp::body);
                var policyData = GraphJson.object(GraphJson.object(GraphJson.read(policyResp.body(), GraphJson.Limits.defaults())).get("data"));
                assertTrue(GraphJson.string(policyData.get("yaml")).contains("architecture-policy:v1"));
                assertFalse(GraphJson.string(policyData.get("yaml")).isBlank());
            }
        } finally {
            mock.stop(0);
        }
    }

    @Test void wholeSnapshotToolsAndReadCapturedSourceResolveContentEvidenceAndFastFallback() throws Exception {
        byte[] sourceBytes = "package demo;\nclass OrderService {\n    void place() {}\n}\n".getBytes(StandardCharsets.UTF_8);
        var sourceDigest = com.evolution.analysis.contract.common.ContentDigest.sha256(sourceBytes);
        var fixture = new QueryFixture();
        var analysisIdentity = com.evolution.analysis.contract.identity.AnalysisIdentity.fromCanonicalManifestInputs("module-app");
        var analysisCtx = new com.evolution.analysis.graph.KnowledgeGraph.Context(
                analysisIdentity.value(), fixture.snapshot, Optional.of(analysisIdentity), fixture.base.identity());

        var fastContentEv = com.evolution.analysis.graph.GraphEvidence.capture(
                "source.content", Map.of(
                        "contentDigest", sourceDigest.value(),
                        "size", sourceBytes.length));
        var docIdentity = com.evolution.analysis.contract.identity.SourceDocumentIdentity.from(
                com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("https://example.test/http.git"),
                "app/src/main/java/demo/OrderService.java");
        var sourceBytesEv = com.evolution.analysis.graph.GraphEvidence.capture(
                "source.bytes", Map.of(
                        "document", Map.of(
                                "identity", docIdentity.value(),
                                "path", "app/src/main/java/demo/OrderService.java",
                                "contentDigest", sourceDigest.value()),
                        "decoding", Map.of("charset", "UTF-8", "bom", "NONE"),
                        "contentDigest", sourceDigest.value(),
                        "contentEvidence", fastContentEv.identity().value()));
        var typeId = QueryFixture.id("OrderService");
        var typeNode = new com.evolution.analysis.graph.KnowledgeGraph.Node(
                typeId, analysisCtx.identity(), "TYPE",
                List.of(fixture.base.identity()));
        var graph = new com.evolution.analysis.graph.KnowledgeGraph(
                fixture.snapshot,
                List.of(new com.evolution.analysis.graph.KnowledgeGraph.Context(
                        fixture.context, fixture.snapshot, Optional.empty(), fixture.base.identity()), analysisCtx),
                List.of(typeNode), List.of(), List.of(fixture.base, fastContentEv, sourceBytesEv));
        var store = new com.evolution.analysis.graph.InMemoryGraphStore();
        store.save(graph);

        RepairSource.registerContentFallback((digest, size) ->
                digest.equals(sourceDigest) ? Optional.of(sourceBytes) : Optional.empty());
        var calls = new AtomicInteger();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/chat/completions", exchange -> {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                var req = GraphJson.object(GraphJson.read(new String(requestBody, StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
                int call = calls.incrementAndGet();
                String replyJson;
                if (call == 1) {
                    replyJson = CanonicalJson.write(Map.of(
                            "model", "agent-model",
                            "choices", List.of(Map.of("finish_reason", "tool_calls", "message", Map.of(
                                    "role", "assistant",
                                    "content", "",
                                    "tool_calls", List.of(
                                            Map.of("id", "c1", "type", "function", "function", Map.of(
                                                    "name", "search_symbols",
                                                    "arguments", "{\"query\":\"\",\"scope\":\"TYPE\"}")),
                                            Map.of("id", "c2", "type", "function", "function", Map.of(
                                                    "name", "read_captured_source",
                                                    "arguments", "{\"path\":\"OrderService.java\",\"startLine\":1,\"endLine\":10}"))))))));
                } else {
                    var msgs = GraphJson.array(req.get("messages"));
                    String tool1Json = GraphJson.string(GraphJson.object(msgs.get(msgs.size() - 2)).get("content"));
                    String tool2Json = GraphJson.string(GraphJson.object(msgs.get(msgs.size() - 1)).get("content"));
                    assertTrue(tool1Json.contains(typeId), () -> "tool1=" + tool1Json);
                    assertTrue(tool1Json.contains(analysisCtx.identity()), () -> "tool1=" + tool1Json);
                    assertTrue(tool2Json.contains("void place() {}"), () -> "tool2=" + tool2Json);
                    String content = CanonicalJson.write(Map.of(
                            "status", "ADVICE",
                            "conversationTitle", "OrderService analysis",
                            "answer", "Found `OrderService` [E3] with `place()` [E4].",
                            "citations", List.of("E3", "E4"),
                            "limitations", List.of()));
                    replyJson = CanonicalJson.write(Map.of(
                            "model", "agent-model",
                            "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of(
                                    "role", "assistant",
                                    "content", content)))));
                }
                byte[] out = replyJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            mock.start();
            var endpoint = URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1/chat/completions");
            var model = new ArchitectureChatModel(endpoint, "agent-model", null, false);
            var api = ArchitectureQueryApi.withArchitectureChat(new ArchitectureQueryApi(store), model);
            var response = api.handle("POST",
                    "/api/v1/graphs/" + graph.identity().value() + "/architecture-chat",
                    "application/json",
                    bytes(Map.of(
                            "context", fixture.context,
                            "mode", "EXPLORE",
                            "agentic", true,
                            "includeSource", true,
                            "messages", List.of(Map.of("role", "user", "content", "Inspect OrderService.")))),
                    () -> false);
            assertEquals(200, response.status(), () -> body(response).toString());
            var data = GraphJson.object(body(response).get("data"));
            assertEquals("ADVICE", data.get("status"));
            assertEquals(2, GraphJson.array(data.get("agentTrace")).size());
        } finally {
            RepairSource.registerContentFallback(null);
            mock.stop(0);
        }
    }
}
