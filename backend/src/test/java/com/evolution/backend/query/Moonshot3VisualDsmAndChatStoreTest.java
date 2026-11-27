package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.Entity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.backend.auth.AuthHttpApi;
import com.evolution.backend.auth.GitHubOAuthConfigStore;
import com.evolution.backend.auth.GitHubOAuthService;
import com.evolution.backend.auth.UserAccountStore;
import com.evolution.backend.auth.UserSessionStore;
import com.evolution.backend.query.chat.ChatConversationStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Moonshot3VisualDsmAndChatStoreTest {

    @Test
    void chatConversationStorePersistsReloadsCascadesEnforcesTenancyAndRedactsSecrets(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("chat").resolve("conversations.db");
        String graphDigest = ContentDigest.sha256Utf8("m3-chat-graph").value();
        String otherGraphDigest = ContentDigest.sha256Utf8("m3-other-graph").value();
        String snapDigest = "snapshot:m3-chat-snap";
        String ctxId = "analysis:m3-chat-ctx";

        String ghSecret = "ghp_1234567890abcdef1234567890abcdef";
        String stripeSecret = "sk_" + "live_1234567890abcdef1234";
        String patSecret = "evo_pat_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdef0";
        String highEntropySecret = "K9xR7mP2vQ4nW8zB3tY6jL1cF5hD0gS9";
        String pemSecret = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAtokenbody1234567890\n-----END RSA PRIVATE KEY-----";

        String convId;
        String autoTitleConvId;
        try (var store = new ChatConversationStore(dbFile)) {
            // Verify PRAGMAs and schema_meta version == '1'
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                 var st = conn.createStatement()) {
                try (var rs = st.executeQuery("PRAGMA journal_mode")) {
                    assertTrue(rs.next());
                    assertEquals("wal", rs.getString(1).toLowerCase(Locale.ROOT));
                }
                try (var rs = st.executeQuery("SELECT value FROM schema_meta WHERE key = 'schema_version'")) {
                    assertTrue(rs.next());
                    assertEquals("1", rs.getString(1));
                }
            }

            var created = store.createConversation(
                    "usr_alice", graphDigest, snapDigest, ctxId, "DESIGN",
                    "Review token " + ghSecret, Optional.empty());
            convId = created.conversationId();
            assertTrue(ChatConversationStore.isValidConversationId(convId));
            assertFalse(created.title().contains(ghSecret), "Secret must be redacted from title before insert");

            var autoCreated = store.createConversation(
                    "usr_alice", graphDigest, snapDigest, ctxId, "EXPLORE",
                    "", Optional.empty());
            autoTitleConvId = autoCreated.conversationId();
            assertEquals(ChatConversationStore.DEFAULT_TITLE, autoCreated.title());

            var pair1 = store.appendTurnPair(
                    "usr_alice", convId, graphDigest,
                    "Inspect leak " + stripeSecret + " and " + highEntropySecret,
                    "Found leaked key " + patSecret + " and PEM:\n" + pemSecret,
                    List.of(Map.of("id", "E1", "secret", ghSecret, "label", "Captured source")),
                    List.of(Map.of("step", 1, "tool", "inspect_symbol", "detail", stripeSecret)),
                    List.of(Map.of("id", "E1", "kind", "SOURCE", "nodeIds", List.of("java:v1:OrderService"),
                            "path", "src/OrderService.java", "startLine", 10, "endLine", 20,
                            "trustTier", TrustTier.COMPILER.name())),
                    Map.of("provider", "OPENAI_COMPAT", "inputTokens", 1200, "cacheReadTokens", 900, "cacheCreationTokens", 0),
                    Optional.of(ContentDigest.sha256Utf8("ev-1").value()),
                    Optional.of("gpt-test-model"),
                    Optional.of("Custom Title Should Not Override Explicit"));
            assertEquals(1, pair1.userSeq());
            assertEquals(2, pair1.assistantSeq());

            // Auto-title conversation should update from DEFAULT_TITLE on first turn pair
            var autoPair = store.appendTurnPair(
                    "usr_alice", autoTitleConvId, graphDigest,
                    "How are billing services coupled?",
                    "Billing depends on ledger [E1].",
                    List.of(), List.of(), List.of(), Map.of(),
                    Optional.empty(), Optional.empty(), Optional.of("Billing Coupling Review"));
            assertEquals("Billing Coupling Review", autoPair.title());

            // Oversized turn JSON (> 512 KiB) must be bounded cleanly
            String hugeChunk = "x".repeat(40_000);
            var hugeEvidence = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 25; i++) {
                hugeEvidence.add(Map.of("id", "E" + (i + 1), "blob", hugeChunk));
            }
            var pair2 = store.appendTurnPair(
                    "usr_alice", convId, graphDigest,
                    "Follow-up question",
                    "Follow-up answer",
                    hugeEvidence, List.of(), List.of(), Map.of(),
                    Optional.empty(), Optional.empty(), Optional.empty());
            assertEquals(3, pair2.userSeq());
            assertEquals(4, pair2.assistantSeq());

            // Strict cross-tenant isolation
            assertTrue(store.listConversations("usr_bob", graphDigest, 50).isEmpty());
            assertThrows(ChatConversationStore.AccessDeniedException.class,
                    () -> store.getConversation("usr_bob", convId));
            assertThrows(ChatConversationStore.AccessDeniedException.class,
                    () -> store.appendTurnPair("usr_bob", convId, graphDigest, "hi", "no",
                            List.of(), List.of(), List.of(), Map.of(), Optional.empty(), Optional.empty(), Optional.empty()));
            assertThrows(ChatConversationStore.AccessDeniedException.class,
                    () -> store.deleteConversation("usr_bob", convId));
            assertThrows(ChatConversationStore.NotFoundException.class,
                    () -> store.getConversation("usr_alice", "conv_00000000000000000000000000000000"));
            assertThrows(IllegalArgumentException.class,
                    () -> store.verifyAccess("usr_alice", convId, otherGraphDigest));
        }

        // Raw SQLite file + WAL byte-scan: 0 raw secrets on disk!
        for (Path candidate : List.of(dbFile, dbFile.resolveSibling("conversations.db-wal"), dbFile.resolveSibling("conversations.db-shm"))) {
            if (Files.isRegularFile(candidate)) {
                String raw = new String(Files.readAllBytes(candidate), StandardCharsets.ISO_8859_1);
                assertFalse(raw.contains(ghSecret), "Raw GitHub token leaked to " + candidate.getFileName());
                assertFalse(raw.contains(stripeSecret), "Raw Stripe token leaked to " + candidate.getFileName());
                assertFalse(raw.contains(patSecret), "Raw PAT leaked to " + candidate.getFileName());
                assertFalse(raw.contains(highEntropySecret), "Raw high-entropy secret leaked to " + candidate.getFileName());
                assertFalse(raw.contains("MIIEowIBAAKCAQEAtokenbody1234567890"), "Raw PEM body leaked to " + candidate.getFileName());
            }
        }

        // Reload store across restart and verify persisted turns + bounded JSON + cascade delete
        try (var reopened = new ChatConversationStore(dbFile)) {
            var listed = reopened.listConversations("usr_alice", graphDigest, 50);
            assertEquals(2, listed.size());

            var detail = reopened.getConversation("usr_alice", convId);
            assertEquals(4, detail.turns().size());
            assertEquals(List.of(1, 2, 3, 4), detail.turns().stream().map(ChatConversationStore.ConversationTurn::seq).toList());
            assertEquals(List.of("USER", "ASSISTANT", "USER", "ASSISTANT"),
                    detail.turns().stream().map(ChatConversationStore.ConversationTurn::role).toList());
            var turn2 = detail.turns().get(1);
            assertFalse(turn2.content().contains(patSecret));
            assertEquals(900, ((Number) turn2.promptCacheMetrics().get("cacheReadTokens")).intValue());
            assertEquals(1, turn2.citations().size());

            // Verify combined JSON columns for oversized turn 4 are <= 512 KiB in SQLite
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                 var ps = conn.prepareStatement(
                         "SELECT LENGTH(CAST(evidence_json AS BLOB)) + LENGTH(CAST(tool_trace_json AS BLOB)) "
                                 + "+ LENGTH(CAST(citations_json AS BLOB)) + LENGTH(CAST(prompt_cache_metrics_json AS BLOB)) "
                                 + "FROM conversation_turns WHERE conversation_id = ? AND seq = 4")) {
                ps.setString(1, convId);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertTrue(rs.getInt(1) <= ChatConversationStore.MAX_TURN_JSON_BYTES,
                            "Stored turn JSON exceeded 512 KiB: " + rs.getInt(1));
                }
            }

            // Delete conversation -> verify ON DELETE CASCADE wipes conversation_turns
            assertTrue(reopened.deleteConversation("usr_alice", convId));
            assertThrows(ChatConversationStore.NotFoundException.class,
                    () -> reopened.getConversation("usr_alice", convId));
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                 var ps = conn.prepareStatement("SELECT COUNT(*) FROM conversation_turns WHERE conversation_id = ?")) {
                ps.setString(1, convId);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "ON DELETE CASCADE must remove all turns");
                }
            }
        }
    }

    @Test
    void structuredCitationsAndConversationPersistenceWorkAcrossSyncAndSseWithPromptCacheMetrics(@TempDir Path tempDir) throws Exception {
        var rich = buildMultiModuleCyclicGraph(12);
        var graph = rich.graph();
        var graphId = graph.identity().value();
        var ctx = rich.contextId();
        var store = new InMemoryGraphStore();
        store.save(graph);

        var mockLlm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockLlm.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean stream = reqBody.contains("\"stream\":true");
                if (stream) {
                    String sse = "data: " + CanonicalJson.write(Map.of(
                            "model", "m3-mock-llm",
                            "choices", List.of(Map.of(
                                    "delta", Map.of("content", "<conversation_title>Cyclic Billing Dependencies</conversation_title>\n"
                                            + "Decouple BillingService0 using an interface [E1] and [E2]."),
                                    "finish_reason", "stop")),
                            "usage", Map.of(
                                    "prompt_tokens", 1600,
                                    "completion_tokens", 140,
                                    "prompt_tokens_details", Map.of("cached_tokens", 1100)))) + "\n\n"
                            + "data: [DONE]\n\n";
                    byte[] resp = sse.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    exchange.sendResponseHeaders(200, resp.length);
                    exchange.getResponseBody().write(resp);
                } else {
                    byte[] resp = CanonicalJson.write(Map.of(
                            "model", "m3-mock-llm",
                            "choices", List.of(Map.of(
                                    "finish_reason", "stop",
                                    "message", Map.of(
                                            "role", "assistant",
                                            "content", "<conversation_title>Cyclic Billing Dependencies</conversation_title>\n"
                                                    + "OrderController depends on InvoiceService [E1] and [E2]."))),
                            "usage", Map.of(
                                    "prompt_tokens", 1400,
                                    "completion_tokens", 120,
                                    "prompt_tokens_details", Map.of("cached_tokens", 950))))
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, resp.length);
                    exchange.getResponseBody().write(resp);
                }
            }
        });
        mockLlm.start();

        try (var chatStore = new ChatConversationStore(tempDir.resolve("chat").resolve("conversations.db"))) {
            var provider = new ArchitectureChatModel(
                    URI.create("http://127.0.0.1:" + mockLlm.getAddress().getPort() + "/v1/"),
                    "m3-mock-llm", null, false);
            var api = ArchitectureQueryApi.withChatStore(
                    ArchitectureQueryApi.withArchitectureChat(new ArchitectureQueryApi(store), provider),
                    chatStore);

            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                String root = server.uri().toString();
                var http = HttpClient.newHttpClient();

                // 1. Sync chat WITHOUT conversationId still returns structured resolvedCitations[]!
                var anonChat = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/architecture-chat"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                "context", ctx,
                                "mode", "EXPLORE",
                                "focus", rich.sampleTypeId(),
                                "messages", List.of(Map.of("role", "user", "content", "Explain dependencies around BillingService0"))))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, anonChat.statusCode(), anonChat.body());
                var anonData = GraphJson.object(GraphJson.object(GraphJson.read(anonChat.body(), GraphJson.Limits.defaults())).get("data"));
                assertNull(anonData.get("conversationId"));
                assertNull(anonData.get("turnId"));
                var anonResolved = GraphJson.array(anonData.get("resolvedCitations"));
                assertFalse(anonResolved.isEmpty(), "resolvedCitations must always be populated even without conversationId");
                boolean hasNodeIds = anonResolved.stream().map(GraphJson::object)
                        .anyMatch(c -> !GraphJson.array(c.get("nodeIds")).isEmpty());
                assertTrue(hasNodeIds, "At least one resolved citation must extract graph nodeIds for Chat<->Canvas sync");
                for (Object item : anonResolved) {
                    var rc = GraphJson.object(item);
                    assertNotNull(TrustTier.valueOf(GraphJson.string(rc.get("trustTier"))));
                }
                boolean hasAdvisoryTier = anonResolved.stream().map(GraphJson::object)
                        .anyMatch(c -> TrustTier.ADVISORY_UNVERIFIED.name().equals(c.get("trustTier")));
                assertTrue(hasAdvisoryTier, "Advisory evidence must resolve to ADVISORY_UNVERIFIED trustTier");

                // 2. Create conversation via POST /graphs/{graph}/conversations (with contextId & snapshotDigest like web UI)
                var createResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/conversations"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                "contextId", ctx,
                                "snapshotDigest", graph.snapshot().value(),
                                "mode", "EXPLORE"))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(201, createResp.statusCode(), createResp.body());
                var createData = GraphJson.object(GraphJson.object(GraphJson.read(createResp.body(), GraphJson.Limits.defaults())).get("data"));
                var convObj = GraphJson.object(createData.get("conversation"));
                String convId = GraphJson.string(convObj.get("conversationId"));
                assertTrue(ChatConversationStore.isValidConversationId(convId));

                // 3. Sync chat WITH conversationId persists user+assistant turns and returns conversationId + turnId
                var syncPersisted = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/architecture-chat"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                "context", ctx,
                                "mode", "EXPLORE",
                                "conversationId", convId,
                                "messages", List.of(Map.of("role", "user", "content", "Analyze cyclic billing dependencies"))))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, syncPersisted.statusCode(), syncPersisted.body());
                var syncData = GraphJson.object(GraphJson.object(GraphJson.read(syncPersisted.body(), GraphJson.Limits.defaults())).get("data"));
                assertEquals(convId, syncData.get("conversationId"));
                assertNotNull(syncData.get("turnId"));
                assertFalse(GraphJson.array(syncData.get("resolvedCitations")).isEmpty());

                // 4. SSE /architecture-chat/stream WITH conversationId persists turn 2 and includes fields in complete event
                var sseResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/architecture-chat/stream"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                "context", ctx,
                                "mode", "EXPLORE",
                                "conversationId", convId,
                                "messages", List.of(Map.of("role", "user", "content", "How do we decouple the cycle?"))))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, sseResp.statusCode(), sseResp.body());
                String completePayload = extractSseEventData(sseResp.body(), "complete");
                assertNotNull(completePayload, "SSE stream must emit terminal complete event: " + sseResp.body());
                var sseData = GraphJson.object(GraphJson.object(GraphJson.read(completePayload, GraphJson.Limits.defaults())).get("data"));
                assertEquals(convId, sseData.get("conversationId"));
                assertNotNull(sseData.get("turnId"));
                assertFalse(GraphJson.array(sseData.get("resolvedCitations")).isEmpty());

                // 5. GET /api/v1/conversations/{id} returns 4 ordered turns with promptCacheMetrics & citations
                var getConv = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/conversations/" + convId))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, getConv.statusCode(), getConv.body());
                var detailData = GraphJson.object(GraphJson.object(GraphJson.read(getConv.body(), GraphJson.Limits.defaults())).get("data"));
                var turns = GraphJson.array(detailData.get("turns"));
                assertEquals(4, turns.size());
                var firstAssistant = GraphJson.object(turns.get(1));
                assertEquals("ASSISTANT", firstAssistant.get("role"));
                assertFalse(GraphJson.array(firstAssistant.get("citations")).isEmpty());
                var metrics = GraphJson.object(firstAssistant.get("promptCacheMetrics"));
                assertEquals(950, ((Number) metrics.get("cacheReadTokens")).intValue());
                var updatedConv = GraphJson.object(detailData.get("conversation"));
                assertEquals("Cyclic Billing Dependencies", updatedConv.get("title"));

                // 5b. P1-1 guard: reviewer: true with conversationId must NOT append duplicate turns to conversations.db
                var reviewerResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/architecture-chat"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                "context", ctx,
                                "mode", "EXPLORE",
                                "reviewer", true,
                                "conversationId", convId,
                                "messages", List.of(Map.of("role", "user", "content", "How do we decouple the cycle?"))))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, reviewerResp.statusCode(), reviewerResp.body());
                var reviewerData = GraphJson.object(GraphJson.object(GraphJson.read(reviewerResp.body(), GraphJson.Limits.defaults())).get("data"));
                assertNull(reviewerData.get("turnId"), "Reviewer pass must not create a persisted turnId");
                var afterReviewerConv = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/conversations/" + convId))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, afterReviewerConv.statusCode(), afterReviewerConv.body());
                var afterReviewerDetail = GraphJson.object(GraphJson.object(GraphJson.read(afterReviewerConv.body(), GraphJson.Limits.defaults())).get("data"));
                assertEquals(4, GraphJson.array(afterReviewerDetail.get("turns")).size(),
                        "reviewer: true pass must NOT duplicate user+assistant turns in conversations.db");

                // 6. GET /graphs/{graph}/conversations?limit=10 lists the thread cleanly
                var listResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/conversations?limit=10"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, listResp.statusCode(), listResp.body());
                var listData = GraphJson.object(GraphJson.object(GraphJson.read(listResp.body(), GraphJson.Limits.defaults())).get("data"));
                assertEquals(1, GraphJson.array(listData.get("conversations")).size());

                // 7. Invalid / missing / mismatched conversationId errors
                var badFormat = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/architecture-chat"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                "context", ctx, "mode", "EXPLORE", "conversationId", "not-valid-id",
                                "messages", List.of(Map.of("role", "user", "content", "Check"))))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(400, badFormat.statusCode());

                var missingConv = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/architecture-chat"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                "context", ctx, "mode", "EXPLORE", "conversationId", "conv_deadbeefdeadbeefdeadbeefdeadbeef",
                                "messages", List.of(Map.of("role", "user", "content", "Check"))))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, missingConv.statusCode());

                // 8. DELETE /api/v1/conversations/{id} removes thread cleanly
                var delResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/conversations/" + convId))
                        .DELETE().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, delResp.statusCode(), delResp.body());
                var delData = GraphJson.object(GraphJson.object(GraphJson.read(delResp.body(), GraphJson.Limits.defaults())).get("data"));
                assertEquals(true, delData.get("deleted"));
                assertEquals(convId, delData.get("conversationId"));

                var afterDel = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/conversations/" + convId))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, afterDel.statusCode());
            }
        } finally {
            mockLlm.stop(0);
        }
    }

    @Test
    void visualGraphMultiScaleAndDsmQueriesWorkDeterministicallyWithZeroGraphDrift() throws Exception {
        // 270 project nodes (> 250 AUTO->MACRO threshold) across 3 modules & 6 packages with explicit cycles
        var rich = buildMultiModuleCyclicGraph(270);
        var graph = rich.graph();
        ContentDigest beforeDigest = graph.identity();
        String graphId = beforeDigest.value();
        String ctx = rich.contextId();

        var store = new InMemoryGraphStore();
        store.save(graph);
        var api = new ArchitectureQueryApi(store);

        // 1. POST /api/v1/graphs/{graph}/visual-graph with AUTO resolves to MACRO (< 50 KiB super-nodes)
        var autoResp = api.handle("POST", "/api/v1/graphs/" + graphId + "/visual-graph", "application/json",
                CanonicalJson.write(Map.of("context", ctx, "level", "AUTO", "cycleMode", "SCC"))
                        .getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, autoResp.status(), () -> new String(autoResp.body(), StandardCharsets.UTF_8));
        assertTrue(autoResp.body().length < 50 * 1024, "MACRO payload must be compact (< 50 KiB), was " + autoResp.body().length);
        var autoData = GraphJson.object(GraphJson.object(GraphJson.read(new String(autoResp.body(), StandardCharsets.UTF_8),
                GraphJson.Limits.defaults())).get("data"));
        var autoMeta = GraphJson.object(autoData.get("metadata"));
        assertEquals("AUTO", autoMeta.get("level"));
        assertEquals("MACRO", autoMeta.get("resolvedLevel"));
        assertEquals("SCC", autoMeta.get("cycleMode"));
        var macroNodes = GraphJson.array(autoData.get("nodes"));
        assertFalse(macroNodes.isEmpty());
        assertTrue(macroNodes.size() < 50, "MACRO should emit compact super-nodes, not 270 raw nodes");
        var firstSuper = GraphJson.object(macroNodes.getFirst());
        assertEquals("COMMUNITY_SUPER_NODE", firstSuper.get("kind"));
        var superProps = GraphJson.object(firstSuper.get("properties"));
        assertNotNull(superProps.get("x"));
        assertNotNull(superProps.get("y"));
        String commToExpand = GraphJson.string(superProps.get("communityId"));

        // 2. MESO expandCommunities expands selected community while capping honestly
        var mesoResp = api.handle("POST", "/api/v1/graphs/" + graphId + "/visual-graph", "application/json",
                CanonicalJson.write(Map.of(
                        "context", ctx,
                        "level", "MESO",
                        "expandCommunities", List.of(commToExpand),
                        "maxNodes", 80,
                        "maxEdges", 250)).getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, mesoResp.status());
        var mesoData = GraphJson.object(GraphJson.object(GraphJson.read(new String(mesoResp.body(), StandardCharsets.UTF_8),
                GraphJson.Limits.defaults())).get("data"));
        var mesoMeta = GraphJson.object(mesoData.get("metadata"));
        assertEquals("MESO", mesoMeta.get("resolvedLevel"));
        var mesoNodes = GraphJson.array(mesoData.get("nodes"));
        assertTrue(mesoNodes.stream().map(GraphJson::object).anyMatch(n -> "TYPE".equals(n.get("kind"))),
                "MESO must expand member TYPE nodes of requested community");

        // 3. MICRO with focusNodes returns bounded focused neighborhood + SCC metadata
        var microResp = api.handle("POST", "/api/v1/graphs/" + graphId + "/visual-graph", "application/json",
                CanonicalJson.write(Map.of(
                        "context", ctx,
                        "level", "MICRO",
                        "focusNodes", List.of(rich.sampleTypeId()),
                        "cycleMode", "SCC",
                        "maxNodes", 40)).getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, microResp.status());
        var microData = GraphJson.object(GraphJson.object(GraphJson.read(new String(microResp.body(), StandardCharsets.UTF_8),
                GraphJson.Limits.defaults())).get("data"));
        assertEquals("MICRO", GraphJson.object(microData.get("metadata")).get("resolvedLevel"));
        var microNodes = GraphJson.array(microData.get("nodes"));
        assertTrue(microNodes.stream().map(GraphJson::object).anyMatch(n -> rich.sampleTypeId().equals(n.get("id"))));

        // 4. Legacy /queries/visual-graph with groupBy preserves backward compatibility, and level delegates to multi-scale
        var legacyReq = Map.of(
                "request", Map.of(
                        "context", ctx, "scope", "TYPE", "projection", "BOUNDED_UNION",
                        "direction", "OUTGOING", "relationshipKinds", List.of("graph.depends-on")),
                "arguments", Map.of("groupBy", "COMMUNITY"));
        var legacyResp = api.handle("POST", "/api/v1/graphs/" + graphId + "/queries/visual-graph", "application/json",
                CanonicalJson.write(legacyReq).getBytes(StandardCharsets.UTF_8), () -> false);
        assertEquals(200, legacyResp.status());
        var legacyData = GraphJson.object(GraphJson.object(GraphJson.read(new String(legacyResp.body(), StandardCharsets.UTF_8),
                new GraphJson.Limits(16_777_216, 64, 250_000))).get("data"));
        assertEquals("COMMUNITY", GraphJson.object(legacyData.get("metadata")).get("groupBy"));

        var upgradedQueryReq = Map.of(
                "request", Map.of(
                        "context", ctx, "scope", "TYPE", "projection", "BOUNDED_UNION",
                        "direction", "OUTGOING", "relationshipKinds", List.of("graph.depends-on")),
                "arguments", Map.of("level", "MACRO"));
        var upgradedQueryResp = api.handle("POST", "/api/v1/graphs/" + graphId + "/queries/visual-graph", "application/json",
                CanonicalJson.write(upgradedQueryReq).getBytes(StandardCharsets.UTF_8), () -> false);
        assertEquals(200, upgradedQueryResp.status());
        var upgradedQueryData = GraphJson.object(GraphJson.object(GraphJson.read(
                new String(upgradedQueryResp.body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults())).get("data"));
        assertEquals("MACRO", GraphJson.object(upgradedQueryData.get("metadata")).get("resolvedLevel"));

        // 5. POST /api/v1/graphs/{graph}/queries/dsm for MODULE, PACKAGE, TYPE granularities
        for (String gran : List.of("MODULE", "PACKAGE", "TYPE")) {
            var dsmResp = api.handle("POST", "/api/v1/graphs/" + graphId + "/queries/dsm", "application/json",
                    CanonicalJson.write(Map.of("context", ctx, "granularity", gran, "maxItems", 40))
                            .getBytes(StandardCharsets.UTF_8),
                    () -> false);
            assertEquals(200, dsmResp.status(), () -> new String(dsmResp.body(), StandardCharsets.UTF_8));
            var dsmData = GraphJson.object(GraphJson.object(GraphJson.read(
                    new String(dsmResp.body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults())).get("data"));
            assertEquals(graphId, dsmData.get("graphDigest"));
            assertEquals(ctx, dsmData.get("context"));
            assertEquals(gran, dsmData.get("granularity"));
            assertNotNull(dsmData.get("levelizationScore"));
            assertTrue(((Number) dsmData.get("totalItems")).intValue() > 0);
            var order = GraphJson.array(dsmData.get("order"));
            var sccBlocks = GraphJson.array(dsmData.get("sccBlocks"));
            var cells = GraphJson.array(dsmData.get("cells"));
            assertFalse(order.isEmpty(), gran + " order empty");
            assertFalse(sccBlocks.isEmpty(), gran + " sccBlocks empty");
            assertFalse(cells.isEmpty(), gran + " cells empty: " + dsmData);
            if ("MODULE".equals(gran)) {
                assertEquals(3, order.size(), "Expected 3 modules in MODULE DSM");
            }
            if ("PACKAGE".equals(gran)) {
                assertEquals(6, order.size(), "Expected 6 packages in PACKAGE DSM");
            }
            if ("TYPE".equals(gran)) {
                assertEquals(true, dsmData.get("truncated"), "270 types with maxItems=40 must honestly report truncated=true");
                assertEquals(40, order.size());
            }
            for (Object cObj : cells) {
                var cell = GraphJson.object(cObj);
                assertNotNull(TrustTier.valueOf(GraphJson.string(cell.get("trustTier"))));
            }
        }

        // Invalid granularity -> 400; unknown context -> 404
        assertEquals(400, api.handle("POST", "/api/v1/graphs/" + graphId + "/queries/dsm", "application/json",
                CanonicalJson.write(Map.of("context", ctx, "granularity", "INVALID")).getBytes(StandardCharsets.UTF_8),
                () -> false).status());
        assertEquals(404, api.handle("POST", "/api/v1/graphs/" + graphId + "/queries/dsm", "application/json",
                CanonicalJson.write(Map.of("context", "missing-context", "granularity", "PACKAGE")).getBytes(StandardCharsets.UTF_8),
                () -> false).status());

        // Zero canonical KnowledgeGraph digest drift
        assertEquals(beforeDigest, graph.identity());
    }

    @Test
    void multiUserAuthPatTiersCsrfTenantIsolationAndOpenApiParityAreEnforced(@TempDir Path tempDir) throws Exception {
        var rich = buildMultiModuleCyclicGraph(12);
        var graph = rich.graph();
        String graphId = graph.identity().value();
        String ctx = rich.contextId();
        var graphStore = new InMemoryGraphStore();
        graphStore.save(graph);

        Path authDir = tempDir.resolve("auth");
        var cfgStore = new GitHubOAuthConfigStore(authDir, k -> null);
        var oauthConfig = new GitHubOAuthService.Config(
                true, "", "", "github.com", GitHubOAuthService.DEFAULT_SCOPES,
                false, Optional.empty(), Optional.empty(), Optional.empty());
        try (var accountStore = new UserAccountStore(authDir.resolve("accounts.db"));
             var sessionStore = new UserSessionStore(Optional.empty(), Optional.of(cfgStore.masterKeyBytes()));
             var chatStore = new ChatConversationStore(tempDir.resolve("chat").resolve("conversations.db"))) {
            var oauthService = new GitHubOAuthService(oauthConfig, sessionStore,
                    (uri, method, headers, body, cancelled) -> { throw new UnsupportedOperationException("offline"); },
                    cfgStore, accountStore);
            var authApi = new AuthHttpApi(oauthService, null, ApiLimits.defaults(), () -> false);
            var api = ArchitectureQueryApi.withChatStore(
                    ArchitectureQueryApi.withAuth(new ArchitectureQueryApi(graphStore), authApi),
                    chatStore);

            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                String root = server.uri().toString();
                var http = HttpClient.newHttpClient();

                // 1. Unauthenticated requests -> 401 AUTH_REQUIRED
                assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/conversations"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/conversations"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/visual-graph"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("context", ctx))))
                        .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/queries/dsm"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("context", ctx))))
                        .build(), HttpResponse.BodyHandlers.ofString()).statusCode());

                // 2. Register Alice & Bob
                var alice = registerUser(http, root, "alice@example.test", "alice", "StrongPass!123");
                var bob = registerUser(http, root, "bob@example.test", "bob", "StrongPass!456");

                // 3. Cookie mutations without X-Evo-CSRF -> 403 CSRF_TOKEN_INVALID
                assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/conversations"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", alice.cookie())
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("context", ctx))))
                        .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/visual-graph"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", alice.cookie())
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("context", ctx))))
                        .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/queries/dsm"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", alice.cookie())
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("context", ctx))))
                        .build(), HttpResponse.BodyHandlers.ofString()).statusCode());

                // 4. Cookie WITH X-Evo-CSRF succeeds; Bob gets 403 ACCESS_DENIED on Alice's conversation!
                var aliceConvResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/conversations"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", alice.cookie())
                        .header("X-Evo-CSRF", alice.csrf())
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("context", ctx, "title", "Alice Thread"))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(201, aliceConvResp.statusCode(), aliceConvResp.body());
                String aliceConvId = GraphJson.string(GraphJson.object(
                        GraphJson.object(GraphJson.object(GraphJson.read(aliceConvResp.body(), GraphJson.Limits.defaults())).get("data"))
                                .get("conversation")).get("conversationId"));

                assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/conversations/" + aliceConvId))
                        .header("Cookie", bob.cookie()).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/conversations/" + aliceConvId))
                        .header("Cookie", bob.cookie())
                        .header("X-Evo-CSRF", bob.csrf())
                        .DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/architecture-chat"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", bob.cookie())
                        .header("X-Evo-CSRF", bob.csrf())
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                "context", ctx, "mode", "EXPLORE", "evidenceOnly", true, "conversationId", aliceConvId,
                                "messages", List.of(Map.of("role", "user", "content", "Steal Alice thread"))))))
                        .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                // DELETE without CSRF for Alice -> 403 CSRF_TOKEN_INVALID
                assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/conversations/" + aliceConvId))
                        .header("Cookie", alice.cookie()).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());

                // 5. PAT Tier Enforcement (mcp:read, api:read, api:write)
                String mcpPat = mintPat(http, root, alice.cookie(), alice.csrf(), "mcp-ro", List.of("mcp:read"));
                String readPat = mintPat(http, root, alice.cookie(), alice.csrf(), "api-ro", List.of("api:read"));
                String writePat = mintPat(http, root, alice.cookie(), alice.csrf(), "api-rw", List.of("api:read", "api:write", "mcp:read"));

                for (String roPat : List.of(mcpPat, readPat)) {
                    assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/conversations"))
                            .header("Authorization", "Bearer " + roPat).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/conversations/" + aliceConvId))
                            .header("Authorization", "Bearer " + roPat).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/visual-graph"))
                            .header("Authorization", "Bearer " + roPat)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("context", ctx))))
                            .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/queries/dsm"))
                            .header("Authorization", "Bearer " + roPat)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("context", ctx, "granularity", "PACKAGE"))))
                            .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                    // Mutations blocked for read-only PATs!
                    assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/conversations"))
                            .header("Authorization", "Bearer " + roPat)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("context", ctx))))
                            .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/conversations/" + aliceConvId))
                            .header("Authorization", "Bearer " + roPat)
                            .DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                }

                // api:write PAT succeeds on POST /conversations & DELETE /conversations/{id} (CSRF-exempt)
                var patCreated = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graphId + "/conversations"))
                        .header("Authorization", "Bearer " + writePat)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("context", ctx, "title", "PAT Thread"))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(201, patCreated.statusCode(), patCreated.body());
                String patConvId = GraphJson.string(GraphJson.object(
                        GraphJson.object(GraphJson.object(GraphJson.read(patCreated.body(), GraphJson.Limits.defaults())).get("data"))
                                .get("conversation")).get("conversationId"));
                assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/conversations/" + patConvId))
                        .header("Authorization", "Bearer " + writePat)
                        .DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
        }

        // 6. OpenAPI parity verification for all Moonshot #3 routes & schemas
        try (var input = getClass().getResourceAsStream("/api/openapi.yaml")) {
            assertNotNull(input);
            @SuppressWarnings("unchecked")
            var spec = (Map<String, Object>) new Yaml().load(input);
            @SuppressWarnings("unchecked")
            var paths = (Map<String, Object>) spec.get("paths");
            for (String route : List.of(
                    "/api/v1/graphs/{graph}/conversations",
                    "/api/v1/conversations/{id}",
                    "/api/v1/graphs/{graph}/visual-graph",
                    "/api/v1/graphs/{graph}/queries/dsm")) {
                assertTrue(paths.containsKey(route), "OpenAPI missing route: " + route);
            }
            @SuppressWarnings("unchecked")
            var schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
            for (String schema : List.of(
                    "ResolvedCitation", "ChatConversationSummary", "ChatConversationTurn",
                    "ProjectionLevel", "CycleMode", "DsmGranularity", "DsmMatrixResponse")) {
                assertTrue(schemas.containsKey(schema), "OpenAPI missing schema: " + schema);
            }
        }
    }

    private record RegisteredSession(String cookie, String csrf) {}

    private static RegisteredSession registerUser(HttpClient http, String root, String email, String username, String password) throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                        "email", email, "username", username, "displayName", username, "password", password))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp.statusCode(), resp.body());
        String setCookie = resp.headers().firstValue("Set-Cookie").orElseThrow();
        String cookie = setCookie.split(";", 2)[0];
        var data = GraphJson.object(GraphJson.object(GraphJson.read(resp.body(), GraphJson.Limits.defaults())).get("data"));
        return new RegisteredSession(cookie, GraphJson.string(data.get("csrfToken")));
    }

    private static String mintPat(HttpClient http, String root, String cookie, String csrf,
                                  String label, List<String> scopes) throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/tokens"))
                .header("Content-Type", "application/json")
                .header("Cookie", cookie)
                .header("X-Evo-CSRF", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                        "label", label, "scopes", scopes))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp.statusCode(), resp.body());
        var data = GraphJson.object(GraphJson.object(GraphJson.read(resp.body(), GraphJson.Limits.defaults())).get("data"));
        return GraphJson.string(data.get("token"));
    }

    private static String extractSseEventData(String rawSse, String targetEvent) {
        String currentEvent = null;
        for (String line : rawSse.split("\\R")) {
            if (line.startsWith("event:")) {
                currentEvent = line.substring("event:".length()).strip();
            } else if (line.startsWith("data:") && targetEvent.equals(currentEvent)) {
                return line.substring("data:".length()).strip();
            } else if (line.isBlank()) {
                currentEvent = null;
            }
        }
        return null;
    }

    private record RichFixture(KnowledgeGraph graph, String contextId, String sampleTypeId) {}

    private static RichFixture buildMultiModuleCyclicGraph(int typeCount) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/m3-backend-" + typeCount + ".git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("m3-snap-" + typeCount));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("m3-mod-" + typeCount);
        String ctx = analysis.value();

        var modules = List.of(
                ModuleIdentity.from(repo, "web-api"),
                ModuleIdentity.from(repo, "billing-core"),
                ModuleIdentity.from(repo, "ledger-store"));
        var packages = List.of(
                "com.acme.web.ctrl", "com.acme.web.dto",
                "com.acme.billing.service", "com.acme.billing.rules",
                "com.acme.ledger.repo", "com.acme.ledger.model");

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", modules.stream()
                .map(m -> Map.<String, Object>of("identity", m, "path", m.value())).toList()));
        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();

        for (var mod : modules) {
            var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", mod, "path", mod.value()));
            evidence.add(modEv);
            nodes.add(new KnowledgeGraph.Node(mod.value(), ctx, "MODULE", List.of(modEv.identity())));
        }

        // Capture one SQL DDL & one Markdown ADR so AdvisoryOverlay emits ADVISORY_UNVERIFIED evidence in chat
        byte[] sqlBytes = "CREATE TABLE billing_invoice (id BIGINT PRIMARY KEY, total NUMERIC);\n".getBytes(StandardCharsets.UTF_8);
        var sqlEv = GraphEvidence.capture("source.bytes", Map.of(
                "document", Map.of("path", "db/V1__billing.sql", "contentDigest", ContentDigest.sha256(sqlBytes).value()),
                "size", sqlBytes.length,
                "bytesBase64", Base64.getEncoder().encodeToString(sqlBytes)));
        evidence.add(sqlEv);
        nodes.add(new KnowledgeGraph.Node(KnowledgeGraph.derivedIdentity("source-doc", List.of("db/V1__billing.sql")),
                snap.value(), "SOURCE_DOCUMENT", List.of(sqlEv.identity())));

        byte[] mdBytes = "# ADR-001 Billing\nDocuments `BillingService0` and `billing_invoice`.\n".getBytes(StandardCharsets.UTF_8);
        var mdEv = GraphEvidence.capture("source.bytes", Map.of(
                "document", Map.of("path", "docs/ADR-001.md", "contentDigest", ContentDigest.sha256(mdBytes).value()),
                "size", mdBytes.length,
                "bytesBase64", Base64.getEncoder().encodeToString(mdBytes)));
        evidence.add(mdEv);
        nodes.add(new KnowledgeGraph.Node(KnowledgeGraph.derivedIdentity("source-doc", List.of("docs/ADR-001.md")),
                snap.value(), "SOURCE_DOCUMENT", List.of(mdEv.identity())));

        var typeIds = new ArrayList<String>(typeCount);
        int perCluster = Math.max(2, typeCount / packages.size());
        for (int i = 0; i < typeCount; i++) {
            int pkgIdx = Math.min(packages.size() - 1, i / perCluster);
            int modIdx = pkgIdx / 2;
            var mod = modules.get(modIdx);
            String pkg = packages.get(pkgIdx);
            String simpleName = "BillingService" + i;
            var ent = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                    JavaSymbolName.topLevelType(pkg, simpleName).canonicalName(), Optional.empty());
            var decl = GraphEvidence.capture("java.declaration", Map.of(
                    "entity", Map.of(
                            "identity", ent.identity().value(),
                            "origin", "PROJECT",
                            "kind", "TYPE",
                            "canonicalName", ent.canonicalName(),
                            "stableScope", mod.value(),
                            "module", mod.value()),
                    "spelling", simpleName,
                    "status", "RESOLVED"));
            evidence.add(decl);
            nodes.add(new KnowledgeGraph.Node(ent.identity().value(), ctx, "TYPE", List.of(decl.identity())));
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("m3-contains", List.of(mod.value(), ent.identity().value())),
                    ctx, "graph.contains", mod.value(),
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(ent.identity().value()), Optional.empty()),
                    List.of(decl.identity()), Optional.empty()));
            typeIds.add(ent.identity().value());
        }

        // Dense intra-cluster edges + cross-cluster dependencies including a deliberate cycle between cluster 0 & cluster 2
        int edgeSeq = 0;
        for (int c = 0; c < packages.size(); c++) {
            int start = c * perCluster;
            int end = Math.min(typeCount, (c == packages.size() - 1) ? typeCount : (c + 1) * perCluster);
            for (int i = start; i + 1 < end; i++) {
                edgeSeq = addDep(edges, evidence, ctx, typeIds.get(i), typeIds.get(i + 1), edgeSeq);
                if (i + 2 < end) {
                    edgeSeq = addDep(edges, evidence, ctx, typeIds.get(i), typeIds.get(i + 2), edgeSeq);
                }
            }
            if (end - start >= 2) {
                // Close intra-cluster loop on first cluster so TYPE/PACKAGE/MODULE have real SCC cycles
                if (c == 0) {
                    edgeSeq = addDep(edges, evidence, ctx, typeIds.get(start + 1), typeIds.get(start), edgeSeq);
                }
            }
            if (c + 1 < packages.size() && end < typeCount) {
                edgeSeq = addDep(edges, evidence, ctx, typeIds.get(start), typeIds.get(end), edgeSeq);
            }
        }
        // Back-edge from module 1 -> module 0 creating a cross-module / cross-package cycle
        int mod1Start = Math.min(typeCount - 1, 2 * perCluster);
        edgeSeq = addDep(edges, evidence, ctx, typeIds.get(mod1Start), typeIds.get(0), edgeSeq);

        var graph = new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(ctx, snap, Optional.of(analysis), manEv.identity())),
                nodes, edges, evidence);
        return new RichFixture(graph, ctx, typeIds.getFirst());
    }

    private static int addDep(List<KnowledgeGraph.Edge> edges, List<GraphEvidence> evidence,
                              String ctx, String src, String dst, int seq) {
        var refEv = GraphEvidence.capture("java.reference", Map.of("from", src, "to", dst, "seq", seq));
        evidence.add(refEv);
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("m3-dep", List.of(src, dst, String.valueOf(seq))),
                ctx, "graph.depends-on", src,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                List.of(refEv.identity()), Optional.empty()));
        return seq + 1;
    }
}
