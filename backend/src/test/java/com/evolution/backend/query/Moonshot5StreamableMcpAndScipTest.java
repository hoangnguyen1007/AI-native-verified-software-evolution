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
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.scip.ScipProtobufReader;
import com.evolution.analysis.execution.scip.ScipProtobufReader.Document;
import com.evolution.analysis.execution.scip.ScipProtobufReader.Metadata;
import com.evolution.analysis.execution.scip.ScipProtobufReader.Occurrence;
import com.evolution.analysis.execution.scip.ScipProtobufReader.Relationship;
import com.evolution.analysis.execution.scip.ScipProtobufReader.ScipIndex;
import com.evolution.analysis.execution.scip.ScipProtobufReader.SymbolInformation;
import com.evolution.analysis.execution.scip.ScipProtobufReader.ToolInfo;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.analysis.RepositoryConnections;
import com.evolution.backend.auth.AuthHttpApi;
import com.evolution.backend.auth.GitHubOAuthConfigStore;
import com.evolution.backend.auth.GitHubOAuthService;
import com.evolution.backend.auth.UserAccountStore;
import com.evolution.backend.auth.UserSessionStore;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.backend.github.GitHubRestClient;
import com.evolution.backend.github.GitHubTokenBroker;
import com.evolution.backend.github.GitHubWebhookHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Moonshot #5 Integration Tests:
 * <ul>
 *   <li>Streamable HTTP {@code POST|GET|DELETE /mcp} (MCP {@code 2025-11-25}) + Anti-DNS-Rebinding Origin guard + {@code Mcp-Session-Id} + Multi-user PAT &amp; CSRF</li>
 *   <li>Enhanced {@code GET /api/v1/mcp/setup} ({@code streamableHttpUrl}, {@code clients}, {@code toolCount=21}, {@code resourceCount=6})</li>
 *   <li>{@code POST /api/v1/graphs/{graph}/scip/ingest} (binary {@code index.scip} -> {@code COMPILER} upgrades, 0 canonical digest drift, SHA-256 verification, {@code api:write} PAT guard)</li>
 * </ul>
 */
class Moonshot5StreamableMcpAndScipTest {

    private record MultiUserFixture(
            ArchitectureHttpServer server,
            String root,
            String graphId,
            String contextId,
            UserSession cookieSessionBob,
            UserSession cookieSessionAlice,
            String bobMcpReadPat,
            String bobApiReadPat,
            String aliceApiWritePat,
            AutoCloseable resources) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            server.close();
            resources.close();
        }
    }

    private record UserSession(String cookie, String csrf) {}

    @Test
    void streamableHttpMcpFullLifecycleOriginGuardAndSessionTermination(@TempDir Path tempDir) throws Exception {
        var fx = buildSingleGraphWithJob(tempDir);
        var api = new ArchitectureQueryApi(fx.store(), fx.reports(), ApiLimits.defaults(), fx.jobs());
        try (var jobs = fx.jobs();
             var server = new ArchitectureHttpServer(api, 0)) {
            server.start();
            String root = server.uri().toString();
            var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

            // 1. POST /mcp initialize -> 200 + Mcp-Session-Id header + 2025-11-25 capabilities
            var initResp = postMcp(http, root, null, null, Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "method", "initialize",
                    "params", Map.of(
                            "protocolVersion", "2025-11-25",
                            "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "moonshot5-test", "version", "1.0"))));
            assertEquals(200, initResp.statusCode(), initResp.body());
            String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElse("");
            assertFalse(sessionId.isBlank(), "initialize must emit Mcp-Session-Id header");
            var initJson = parseJson(initResp.body());
            var initResult = GraphJson.object(initJson.get("result"));
            assertEquals("2025-11-25", initResult.get("protocolVersion"));
            assertEquals("evo-workbench", GraphJson.object(initResult.get("serverInfo")).get("name"));
            assertTrue(GraphJson.object(initResult.get("capabilities")).containsKey("tools"));
            assertTrue(GraphJson.object(initResult.get("capabilities")).containsKey("resources"));

            // 2. notifications/initialized -> 202 Accepted (empty body)
            var notifResp = postMcp(http, root, sessionId, null, Map.of(
                    "jsonrpc", "2.0",
                    "method", "notifications/initialized"));
            assertEquals(202, notifResp.statusCode(), notifResp.body());
            assertTrue(notifResp.body().isEmpty());

            // 3. ping -> 200
            var pingResp = postMcp(http, root, sessionId, null, Map.of(
                    "jsonrpc", "2.0", "id", 2, "method", "ping", "params", Map.of()));
            assertEquals(200, pingResp.statusCode());
            assertTrue(GraphJson.object(parseJson(pingResp.body()).get("result")).isEmpty());

            // 4. tools/list -> 21 read-only tools
            var toolsResp = postMcp(http, root, sessionId, null, Map.of(
                    "jsonrpc", "2.0", "id", 3, "method", "tools/list", "params", Map.of()));
            assertEquals(200, toolsResp.statusCode());
            var toolsList = GraphJson.array(GraphJson.object(parseJson(toolsResp.body()).get("result")).get("tools"));
            assertEquals(21, toolsList.size());

            // 5. tools/call graph_get + vertical_trace + unlisted operation rejection (-32602)
            var callGraphGet = postMcp(http, root, sessionId, null, Map.of(
                    "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                    "params", Map.of("name", "graph_get", "arguments", Map.of("graph", fx.graphId()))));
            assertEquals(200, callGraphGet.statusCode());
            var graphGetRes = GraphJson.object(parseJson(callGraphGet.body()).get("result"));
            assertEquals(false, graphGetRes.get("isError"), () -> "graph_get failed: " + graphGetRes);
            assertTrue(graphGetRes.get("content").toString().contains(fx.graphId()));

            var callVerticalTrace = postMcp(http, root, sessionId, null, Map.of(
                    "jsonrpc", "2.0", "id", 41, "method", "tools/call",
                    "params", Map.of("name", "vertical_trace",
                            "arguments", Map.of("graph", fx.graphId(), "seed", "OrderService"))));
            assertEquals(200, callVerticalTrace.statusCode());
            var vtRes = GraphJson.object(parseJson(callVerticalTrace.body()).get("result"));
            assertEquals(false, vtRes.get("isError"), () -> "vertical_trace failed: " + vtRes);
            String vtBody = GraphJson.string(GraphJson.object(GraphJson.array(vtRes.get("content")).getFirst()).get("text"));
            assertTrue(vtBody.contains("\"callPathFlamegraph\""), () -> "vertical_trace must expose callPathFlamegraph: " + vtBody);
            assertTrue(vtBody.contains("\"resilienceVerdict\""), () -> "vertical_trace must expose resilienceVerdict: " + vtBody);

            var callRejected = postMcp(http, root, sessionId, null, Map.of(
                    "jsonrpc", "2.0", "id", 5, "method", "tools/call",
                    "params", Map.of("name", "graph_query",
                            "arguments", Map.of("graph", fx.graphId(), "context", fx.contextId(), "operation", "delete"))));
            assertEquals(200, callRejected.statusCode());
            assertEquals(-32602, ((Number) GraphJson.object(parseJson(callRejected.body()).get("error")).get("code")).intValue());

            // 6. resources/list (6) & resources/read (evo://score-v2)
            var resListResp = postMcp(http, root, sessionId, null, Map.of(
                    "jsonrpc", "2.0", "id", 6, "method", "resources/list", "params", Map.of()));
            assertEquals(200, resListResp.statusCode());
            var resources = GraphJson.array(GraphJson.object(parseJson(resListResp.body()).get("result")).get("resources"));
            assertEquals(6, resources.size());

            var resReadResp = postMcp(http, root, sessionId, null, Map.of(
                    "jsonrpc", "2.0", "id", 7, "method", "resources/read",
                    "params", Map.of("uri", "evo://score-v2")));
            assertEquals(200, resReadResp.statusCode());
            var contents = GraphJson.array(GraphJson.object(parseJson(resReadResp.body()).get("result")).get("contents"));
            String scoreText = GraphJson.string(GraphJson.object(contents.getFirst()).get("text"));
            assertTrue(scoreText.contains("architecture.score-formula"), scoreText);

            // 7. GET /mcp -> 405 Method Not Allowed with Allow: POST, DELETE (even with text/event-stream Accept)
            var getMcp = http.send(HttpRequest.newBuilder(URI.create(root + "/mcp"))
                    .header("Accept", "text/event-stream")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, getMcp.statusCode(), getMcp.body());
            assertEquals("POST, DELETE", getMcp.headers().firstValue("Allow").orElse(""));

            // 8. Anti-DNS-Rebinding Origin Guard on /mcp
            assertEquals(200, postMcp(http, root, sessionId, "http://127.0.0.1:5173",
                    Map.of("jsonrpc", "2.0", "id", 8, "method", "ping")).statusCode());
            assertEquals(200, postMcp(http, root, sessionId, "http://localhost:3000",
                    Map.of("jsonrpc", "2.0", "id", 9, "method", "ping")).statusCode());
            assertEquals(200, postMcp(http, root, sessionId, "http://[::1]:4321",
                    Map.of("jsonrpc", "2.0", "id", 10, "method", "ping")).statusCode());

            for (String evilOrigin : List.of("https://evil.example", "http://evil.example:8080", "null", "https://127.0.0.1:8080")) {
                var blocked = postMcp(http, root, sessionId, evilOrigin,
                        Map.of("jsonrpc", "2.0", "id", 11, "method", "ping"));
                assertEquals(403, blocked.statusCode(), "Must reject Origin=" + evilOrigin + ": " + blocked.body());
                assertTrue(blocked.body().contains("FORBIDDEN_ORIGIN"), blocked.body());
            }

            // 9. DELETE /mcp terminates Mcp-Session-Id -> 200, subsequent use -> 404
            var delResp = http.send(HttpRequest.newBuilder(URI.create(root + "/mcp"))
                    .header("Mcp-Session-Id", sessionId)
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, delResp.statusCode(), delResp.body());
            assertEquals(true, parseJson(delResp.body()).get("terminated"));

            var afterDelete = postMcp(http, root, sessionId, null,
                    Map.of("jsonrpc", "2.0", "id", 12, "method", "ping"));
            assertEquals(404, afterDelete.statusCode(), afterDelete.body());
        }
    }

    @Test
    void multiUserMcpEnforcesAuthPatScopesCsrfAndEnhancedMcpSetup(@TempDir Path tempDir) throws Exception {
        try (var fx = startMultiUserServer(tempDir)) {
            var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

            // 1. Unauthenticated POST /mcp -> 401 AUTH_REQUIRED
            var unauth = postMcp(http, fx.root(), null, null,
                    Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of()));
            assertEquals(401, unauth.statusCode(), unauth.body());
            assertTrue(unauth.body().contains("AUTH_REQUIRED"));

            // 2. Cookie-authenticated POST /mcp without X-Evo-CSRF -> 403 CSRF_TOKEN_INVALID
            var noCsrf = http.send(HttpRequest.newBuilder(URI.create(fx.root() + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Cookie", fx.cookieSessionBob().cookie())
                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(
                            Map.of("jsonrpc", "2.0", "id", 2, "method", "ping"))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, noCsrf.statusCode(), noCsrf.body());
            assertTrue(noCsrf.body().contains("CSRF_TOKEN_INVALID"));

            // 3. Cookie-authenticated POST /mcp WITH X-Evo-CSRF -> 200
            var withCsrf = http.send(HttpRequest.newBuilder(URI.create(fx.root() + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Cookie", fx.cookieSessionBob().cookie())
                    .header("X-Evo-CSRF", fx.cookieSessionBob().csrf())
                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(
                            Map.of("jsonrpc", "2.0", "id", 3, "method", "initialize", "params", Map.of()))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, withCsrf.statusCode(), withCsrf.body());
            String bobCookieSession = withCsrf.headers().firstValue("Mcp-Session-Id").orElseThrow();

            // 4. Alice cannot DELETE Bob's Mcp-Session-Id (403 ACCESS_DENIED)
            var crossDel = http.send(HttpRequest.newBuilder(URI.create(fx.root() + "/mcp"))
                    .header("Cookie", fx.cookieSessionAlice().cookie())
                    .header("X-Evo-CSRF", fx.cookieSessionAlice().csrf())
                    .header("Mcp-Session-Id", bobCookieSession)
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, crossDel.statusCode(), crossDel.body());

            // 5. Bearer PAT with mcp:read (NO CSRF header!) -> 200 across initialize, tools/list, tools/call, DELETE /mcp
            var patInit = http.send(HttpRequest.newBuilder(URI.create(fx.root() + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + fx.bobMcpReadPat())
                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(
                            Map.of("jsonrpc", "2.0", "id", 4, "method", "initialize", "params", Map.of()))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, patInit.statusCode(), patInit.body());
            String patSessionId = patInit.headers().firstValue("Mcp-Session-Id").orElseThrow();

            var patTools = http.send(HttpRequest.newBuilder(URI.create(fx.root() + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + fx.bobMcpReadPat())
                    .header("Mcp-Session-Id", patSessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(
                            Map.of("jsonrpc", "2.0", "id", 5, "method", "tools/call",
                                    "params", Map.of("name", "graph_get", "arguments", Map.of("graph", fx.graphId()))))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, patTools.statusCode(), patTools.body());
            assertEquals(false, GraphJson.object(parseJson(patTools.body()).get("result")).get("isError"));

            var patDel = http.send(HttpRequest.newBuilder(URI.create(fx.root() + "/mcp"))
                    .header("Authorization", "Bearer " + fx.bobMcpReadPat())
                    .header("Mcp-Session-Id", patSessionId)
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, patDel.statusCode(), patDel.body());

            // 6. Enhanced GET /api/v1/mcp/setup fields consumed by web/mcp-scip-hub.js
            var setupResp = http.send(HttpRequest.newBuilder(URI.create(fx.root() + "/api/v1/mcp/setup"))
                    .header("Authorization", "Bearer " + fx.bobMcpReadPat())
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, setupResp.statusCode(), setupResp.body());
            var setupData = GraphJson.object(parseJson(setupResp.body()).get("data"));
            assertEquals(fx.root() + "/mcp", setupData.get("streamableHttpUrl"));
            assertEquals("2025-11-25", setupData.get("protocolVersion"));
            assertEquals(21, ((Number) setupData.get("toolCount")).intValue());
            assertEquals(6, ((Number) setupData.get("resourceCount")).intValue());
            assertEquals(true, setupData.get("patRequired"));
            assertTrue(setupData.containsKey("workbenchProxyConfig"));
            assertTrue(setupData.containsKey("directOfflineConfig"));

            var clients = GraphJson.object(setupData.get("clients"));
            assertEquals(Set.of("antigravity", "cursor", "claude", "vscode", "stdio", "offlineStore"), clients.keySet());
            assertTrue(clients.get("antigravity").toString().contains(fx.root() + "/mcp"));
            assertTrue(clients.get("cursor").toString().contains("Authorization"));
            assertTrue(clients.get("vscode").toString().contains("servers"));
        }
    }

    @Test
    void scipIngestEndpointVerifiesSha256UpgradesToCompilerEnforcesWritePatAndPreservesGraphDigest(@TempDir Path tempDir) throws Exception {
        byte[] scipBytes = buildSampleScipArchive();
        String validBase64 = Base64.getEncoder().encodeToString(scipBytes);
        ContentDigest expectedSha = ContentDigest.sha256(scipBytes);
        String wrongSha = ContentDigest.sha256Utf8("tampered").value();

        try (var fx = startMultiUserServer(tempDir)) {
            var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String ingestUrl = fx.root() + "/api/v1/graphs/" + fx.graphId() + "/scip/ingest";

            // 1. Unauthenticated -> 401 AUTH_REQUIRED
            var unauth = http.send(HttpRequest.newBuilder(URI.create(ingestUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("scipBase64", validBase64))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauth.statusCode(), unauth.body());

            // 2. Read-only PATs (mcp:read & api:read) -> 403 INSUFFICIENT_TOKEN_SCOPE
            for (String roPat : List.of(fx.bobMcpReadPat(), fx.bobApiReadPat())) {
                var denied = http.send(HttpRequest.newBuilder(URI.create(ingestUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + roPat)
                        .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("scipBase64", validBase64))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(403, denied.statusCode(), denied.body());
                assertTrue(denied.body().contains("INSUFFICIENT_TOKEN_SCOPE"), denied.body());
            }

            // 3. Cookie without CSRF -> 403 CSRF_TOKEN_INVALID
            var noCsrf = http.send(HttpRequest.newBuilder(URI.create(ingestUrl))
                    .header("Content-Type", "application/json")
                    .header("Cookie", fx.cookieSessionAlice().cookie())
                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("scipBase64", validBase64))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, noCsrf.statusCode(), noCsrf.body());

            // 4. Read canonical graph snapshot BEFORE SCIP ingest
            var graphBefore = http.send(HttpRequest.newBuilder(URI.create(fx.root() + "/api/v1/graphs/" + fx.graphId()))
                    .header("Authorization", "Bearer " + fx.aliceApiWritePat())
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, graphBefore.statusCode());

            // 5. Valid binary index.scip with api:write PAT -> 200 COMPILER upgrades (accepts bare 64-hex expectedSha256 too!)
            var okResp = http.send(HttpRequest.newBuilder(URI.create(ingestUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + fx.aliceApiWritePat())
                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                            "scipBase64", validBase64,
                            "expectedSha256", expectedSha.value().substring("sha256:".length()),
                            "sourceLabel", "ci-scip-polyglot"))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, okResp.statusCode(), okResp.body());
            var data = GraphJson.object(parseJson(okResp.body()).get("data"));
            assertEquals(expectedSha.value(), data.get("artifactSha256"));
            assertEquals("scip-multi", data.get("toolName"));
            assertEquals("0.5.0", data.get("toolVersion"));
            assertEquals(2, ((Number) data.get("documentCount")).intValue());
            assertEquals(2, ((Number) data.get("symbolCount")).intValue());
            assertEquals(2, ((Number) data.get("upgradedToCompilerCount")).intValue());
            assertEquals(4, ((Number) data.get("occurrenceCount")).intValue());
            assertEquals(0, ((Number) data.get("ambiguousDefinitionCount")).intValue());
            assertEquals("COMPILER", data.get("trustTier"));
            assertFalse(GraphJson.array(data.get("coverageGaps")).isEmpty());
            var samples = GraphJson.array(data.get("samples"));
            assertEquals(2, samples.size());
            assertEquals("COMPILER", GraphJson.object(samples.getFirst()).get("trustTier"));

            // 6. Zero canonical KnowledgeGraph drift after SCIP ingest
            var graphAfter = http.send(HttpRequest.newBuilder(URI.create(fx.root() + "/api/v1/graphs/" + fx.graphId()))
                    .header("Authorization", "Bearer " + fx.aliceApiWritePat())
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, graphAfter.statusCode());
            assertEquals(graphBefore.body(), graphAfter.body(), "SCIP ingest must not mutate canonical graph or discovery");

            // 7. Tampered expectedSha256 -> 400 SCIP_SHA256_MISMATCH
            var mismatch = http.send(HttpRequest.newBuilder(URI.create(ingestUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + fx.aliceApiWritePat())
                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                            "scipBase64", validBase64,
                            "expectedSha256", wrongSha))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, mismatch.statusCode(), mismatch.body());
            assertTrue(mismatch.body().contains("SCIP_SHA256_MISMATCH"), mismatch.body());

            // 8. Invalid base64 & corrupt .scip bytes -> 400
            var badB64 = http.send(HttpRequest.newBuilder(URI.create(ingestUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + fx.aliceApiWritePat())
                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                            "scipBase64", "not-valid-base64!!!"))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, badB64.statusCode(), badB64.body());

            String corruptB64 = Base64.getEncoder().encodeToString("not-a-protobuf-scip".getBytes(StandardCharsets.UTF_8));
            var corruptScip = http.send(HttpRequest.newBuilder(URI.create(ingestUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + fx.aliceApiWritePat())
                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                            "scipBase64", corruptB64))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, corruptScip.statusCode(), corruptScip.body());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers & Fixtures (Zero raw secret token prefixes)
    // -------------------------------------------------------------------------

    private static HttpResponse<String> postMcp(HttpClient http, String root, String sessionId,
                                                String origin, Map<String, Object> body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(root + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return http.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> parseJson(String raw) {
        return GraphJson.object(GraphJson.read(raw, GraphJson.Limits.defaults()));
    }

    private static byte[] buildSampleScipArchive() {
        String tsSym = "scip-typescript npm web-app 1.0.0 src/`OrderClient.ts`/OrderClient#submit().";
        String pySym = "scip-python pypi billing 1.0.0 billing/LedgerService#post().";
        String extSym = "scip-npm npm axios 1.7.0 AxiosInstance#post().";

        Document tsDoc = new Document(
                "typescript",
                "web/OrderClient.ts",
                List.of(
                        new Occurrence(List.of(4, 2, 8), tsSym, ScipProtobufReader.ROLE_DEFINITION, 0, List.of()),
                        new Occurrence(List.of(6, 10, 14), extSym, 0, 0, List.of())),
                List.of(new SymbolInformation(tsSym, List.of(), List.of(), 6, "OrderClient.submit",
                        Optional.empty(), "")),
                0);
        Document pyDoc = new Document(
                "python",
                "billing/ledger.py",
                List.of(
                        new Occurrence(List.of(10, 4, 8), pySym, ScipProtobufReader.ROLE_DEFINITION, 0, List.of()),
                        new Occurrence(List.of(15, 8, 14), tsSym, 0, 0, List.of())),
                List.of(new SymbolInformation(pySym, List.of(),
                        List.of(new Relationship(tsSym, true, false, false, false)),
                        6, "LedgerService.post", Optional.empty(), "")),
                0);
        return ScipProtobufReader.encodeIndex(new ScipIndex(
                new Metadata(1, new ToolInfo("scip-multi", "0.5.0", List.of()), "file:///workspace", 1),
                List.of(tsDoc, pyDoc),
                List.of()));
    }

    private record SingleFixture(
            InMemoryGraphStore store,
            PublishedReports reports,
            AnalysisJobs jobs,
            String graphId,
            String contextId) {}

    private static SingleFixture buildSingleGraphWithJob(Path tempDir) throws Exception {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/moonshot5.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("moonshot5-snap"));
        var analysisId = AnalysisIdentity.fromCanonicalManifestInputs("moonshot5-mod");
        String ctxId = analysisId.value();
        var module = ModuleIdentity.from(repo, ".");

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", module, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", module, "path", "."));

        var pkg = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module), EntityKind.PACKAGE,
                JavaSymbolName.packageName("com.acme.orders").canonicalName(), Optional.empty());
        var pkgEv = GraphEvidence.capture("java.declaration", Map.of("entity", pkg, "spelling", "com.acme.orders", "status", "RESOLVED"));
        var type = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module), EntityKind.TYPE,
                JavaSymbolName.topLevelType("com.acme.orders", "OrderService").canonicalName(), Optional.empty());
        var typeEv = GraphEvidence.capture("java.declaration", Map.of("entity", type, "spelling", "OrderService", "status", "RESOLVED"));
        var shapeEv = GraphEvidence.capture("java.type-shape", Map.of("type", type.identity().value(), "kind", "CLASS", "abstractType", false));

        var graph = new KnowledgeGraph(snap,
                List.of(
                        new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                        new KnowledgeGraph.Context(ctxId, snap, Optional.of(analysisId), manEv.identity())),
                List.of(
                        new KnowledgeGraph.Node(module.value(), ctxId, "MODULE", List.of(modEv.identity())),
                        new KnowledgeGraph.Node(pkg.identity().value(), ctxId, "PACKAGE", List.of(pkgEv.identity())),
                        new KnowledgeGraph.Node(type.identity().value(), ctxId, "TYPE", List.of(typeEv.identity(), shapeEv.identity()))),
                List.of(new KnowledgeGraph.Edge(
                        KnowledgeGraph.derivedIdentity("contains", List.of(pkg.identity().value(), type.identity().value())),
                        ctxId, "graph.contains", pkg.identity().value(),
                        new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(type.identity().value()), Optional.empty()),
                        List.of(typeEv.identity()), Optional.empty())),
                List.of(snapEv, manEv, modEv, pkgEv, typeEv, shapeEv));

        var store = new InMemoryGraphStore();
        store.save(graph);
        var reports = new PublishedReports();
        var reg = new AnalysisJobs.Registration(
                new AnalysisJobs.Setup("m5-repo", repo, 21, false, Optional.empty(), Optional.empty()),
                control -> new AnalysisJobs.Outcome(
                        AnalysisJobs.Completion.COMPLETE,
                        Optional.of(graph), List.of(), List.of(), Optional.empty(), Optional.empty()));
        var jobs = new AnalysisJobs(List.of(reg), store, reports, AnalysisJobs.Limits.defaults());
        var submitted = jobs.submit("m5-repo", "m5-init");
        long deadline = System.currentTimeMillis() + 5_000L;
        UUID jobUuid = UUID.fromString(submitted.job().id());
        while (System.currentTimeMillis() < deadline) {
            var cur = jobs.get(jobUuid);
            if (cur.state() == AnalysisJobs.State.SUCCEEDED) {
                break;
            }
            Thread.sleep(20L);
        }
        return new SingleFixture(store, reports, jobs, graph.identity().value(), ctxId);
    }

    private static MultiUserFixture startMultiUserServer(Path tempDir) throws Exception {
        Path authDir = Files.createDirectories(tempDir.resolve("auth"));
        Path storeDir = Files.createDirectories(tempDir.resolve("store"));
        var cfgStore = new GitHubOAuthConfigStore(authDir, k -> null);
        var fx = buildSingleGraphWithJob(tempDir);

        var accountStore = new UserAccountStore(authDir.resolve("accounts.db"));
        var sessionStore = new UserSessionStore(Optional.of(tempDir.resolve("auth-sessions.enc")),
                Optional.of(cfgStore.masterKeyBytes()));
        var broker = new GitHubTokenBroker(k -> null, h -> Optional.empty());
        broker.setMultiUserMode(true);
        var oauthCfg = new GitHubOAuthService.Config(
                true, "", "", "github.com", GitHubOAuthService.DEFAULT_SCOPES,
                false, Optional.empty(), Optional.empty(), Optional.empty());
        var webhooks = new GitHubWebhookHandler(Optional.empty(), false);
        var rest = new GitHubRestClient(broker, new GitHostPolicy());
        var oauth = new GitHubOAuthService(oauthCfg, sessionStore,
                (uri, method, headers, body, cancel) -> new GitHubRestClient.RawResponse(
                        404, Map.of(), "{}".getBytes(StandardCharsets.UTF_8)),
                cfgStore, accountStore);
        var authApi = new AuthHttpApi(oauth, rest, ApiLimits.defaults(), webhooks::isConfigured);
        var connections = new RepositoryConnections(fx.jobs(), Path.of(System.getProperty("java.home")), 21,
                tempDir.resolve("m2"), storeDir, Optional.empty(), "git", new GitHostPolicy());
        var api = ArchitectureQueryApi.withStoreDirectory(
                ArchitectureQueryApi.withAuth(
                        new ArchitectureQueryApi(fx.store(), fx.reports(), ApiLimits.defaults(), fx.jobs(), connections),
                        authApi),
                storeDir);
        var server = new ArchitectureHttpServer(api, 0);
        server.start();
        String root = server.uri().toString();
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

        UserSession alice = registerUser(http, root, "alice@m5.dev", "alice", "Alice Admin", "StrongPass!123");
        UserSession bob = registerUser(http, root, "bob@m5.dev", "bob", "Bob Member", "StrongPass!456");

        String bobMcpRead = mintPat(http, root, bob, "bob-mcp-read", List.of("mcp:read"));
        String bobApiRead = mintPat(http, root, bob, "bob-api-read", List.of("api:read"));
        String aliceApiWrite = mintPat(http, root, alice, "alice-api-write", List.of("api:read", "api:write"));

        AutoCloseable closer = () -> {
            fx.jobs().close();
            broker.close();
            sessionStore.close();
            accountStore.close();
        };
        return new MultiUserFixture(server, root, fx.graphId(), fx.contextId(),
                bob, alice, bobMcpRead, bobApiRead, aliceApiWrite, closer);
    }

    private static UserSession registerUser(HttpClient http, String root, String email,
                                            String username, String displayName, String password) throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                        "email", email,
                        "username", username,
                        "displayName", displayName,
                        "password", password))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp.statusCode(), resp.body());
        String cookie = resp.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        var data = GraphJson.object(parseJson(resp.body()).get("data"));
        String csrf = GraphJson.string(data.get("csrfToken"));
        return new UserSession(cookie, csrf);
    }

    private static String mintPat(HttpClient http, String root, UserSession session,
                                  String label, List<String> scopes) throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/tokens"))
                .header("Content-Type", "application/json")
                .header("Cookie", session.cookie())
                .header("X-Evo-CSRF", session.csrf())
                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                        "label", label,
                        "scopes", scopes,
                        "expiresInDays", 30))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp.statusCode(), resp.body());
        var data = GraphJson.object(parseJson(resp.body()).get("data"));
        return GraphJson.string(data.get("token"));
    }
}
