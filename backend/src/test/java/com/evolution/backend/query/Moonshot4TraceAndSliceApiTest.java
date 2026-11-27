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
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.backend.auth.AuthHttpApi;
import com.evolution.backend.auth.AuthenticatedUser;
import com.evolution.backend.auth.GitHubOAuthService;
import com.evolution.backend.auth.RequestContext;
import com.evolution.backend.auth.UserAccountStore;
import com.evolution.backend.auth.UserSessionStore;
import com.evolution.backend.query.llm.LlmTransport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Moonshot4TraceAndSliceApiTest {

    // Synthetic test secrets built via concatenation so secret scanners never false-positive.
    private static final String SYNTHETIC_STRIPE_SECRET = "sk_" + "live_1234567890abcdef1234";
    private static final String SYNTHETIC_GH_SECRET = "ghp_" + "1234567890abcdef1234567890abcdef";

    private record FullStackFixture(
            KnowledgeGraph graph,
            String contextId,
            String orderServicePath,
            String orderControllerId,
            String orderEntityId,
            int totalReturnLine,
            int inputLine) {}

    @Test
    void verticalTraceEndpointStitchesQuarantinedAdvisoryAndPreservesCanonicalDigest(@TempDir Path tempDir) throws Exception {
        var fx = buildFullStackFixture();
        ContentDigest digestBefore = fx.graph().identity();
        var store = saveGraph(tempDir, fx.graph());
        var api = new ArchitectureQueryApi(store);
        String graphId = fx.graph().identity().value();

        // 1. includeAdvisory = true (default): canonical hops + SQL_TABLE + TERRAFORM_RESOURCE strictly ADVISORY_UNVERIFIED
        var withAdvResp = api.handle(
                "POST",
                "/api/v1/graphs/" + graphId + "/queries/vertical-trace",
                "application/json",
                CanonicalJson.write(Map.of(
                        "context", fx.contextId(),
                        "seed", "OrderController",
                        "includeAdvisory", true)).getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, withAdvResp.status(), () -> new String(withAdvResp.body(), StandardCharsets.UTF_8));

        var advData = extractData(withAdvResp);
        assertEquals(graphId, advData.get("graphDigest"));
        assertEquals("OrderController", advData.get("seed"));
        int canonicalCount = intField(advData, "canonicalHopCount");
        int advisoryCount = intField(advData, "advisoryHopCount");
        int totalHops = intField(advData, "totalHops");
        assertTrue(canonicalCount >= 3, "Expected >=3 canonical hops, got " + canonicalCount);
        assertTrue(advisoryCount >= 2, "Expected SQL_TABLE + TERRAFORM_RESOURCE advisory hops, got " + advisoryCount);
        assertEquals(canonicalCount + advisoryCount, totalHops);

        var hops = GraphJson.array(advData.get("hops")).stream().map(GraphJson::object).toList();
        assertEquals(totalHops, hops.size());

        boolean sawSqlTable = false;
        boolean sawTerraform = false;
        for (var hop : hops) {
            String layer = GraphJson.string(hop.get("layer"));
            String tier = GraphJson.string(hop.get("trustTier"));
            if ("SQL_TABLE".equals(layer)) {
                sawSqlTable = true;
                assertEquals("ADVISORY_UNVERIFIED", tier, "SQL_TABLE hop must NEVER promote out of ADVISORY_UNVERIFIED");
                assertFalse(hop.containsKey("classification"));
                assertFalse(hop.containsKey("witnessId"));
            } else if ("TERRAFORM_RESOURCE".equals(layer)) {
                sawTerraform = true;
                assertEquals("ADVISORY_UNVERIFIED", tier, "TERRAFORM_RESOURCE hop must NEVER promote out of ADVISORY_UNVERIFIED");
                assertFalse(hop.containsKey("classification"));
                assertFalse(hop.containsKey("witnessId"));
            } else {
                assertNotEquals("ADVISORY_UNVERIFIED", tier, "Canonical hop should keep canonical trust tier");
            }
        }
        assertTrue(sawSqlTable, "Expected SQL_TABLE hop stitched from SqlDdlExtractor: " + hops);
        assertTrue(sawTerraform, "Expected TERRAFORM_RESOURCE hop stitched from TerraformHclExtractor: " + hops);

        // 2. includeAdvisory = false: canonical hops only, zero advisory hops
        var canonResp = api.handle(
                "POST",
                "/api/v1/graphs/" + graphId + "/queries/vertical-trace",
                "application/json",
                CanonicalJson.write(Map.of(
                        "context", fx.contextId(),
                        "seed", "OrderController",
                        "includeAdvisory", false)).getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, canonResp.status());
        var canonData = extractData(canonResp);
        assertEquals(canonicalCount, intField(canonData, "canonicalHopCount"));
        assertEquals(0, intField(canonData, "advisoryHopCount"));
        assertEquals(canonicalCount, intField(canonData, "totalHops"));
        var canonHops = GraphJson.array(canonData.get("hops")).stream().map(GraphJson::object).toList();
        assertTrue(canonHops.stream().noneMatch(h -> "ADVISORY_UNVERIFIED".equals(h.get("trustTier"))));
        assertTrue(canonHops.stream().noneMatch(h -> "SQL_TABLE".equals(h.get("layer")) || "TERRAFORM_RESOURCE".equals(h.get("layer"))));

        // 3. Direct SQL_TABLE seed and TERRAFORM_RESOURCE seed both resolve cleanly as ADVISORY_UNVERIFIED
        var sqlSeedResp = api.handle(
                "POST",
                "/api/v1/graphs/" + graphId + "/queries/vertical-trace",
                "application/json",
                CanonicalJson.write(Map.of("seed", "orders", "seedKind", "SQL_TABLE", "includeAdvisory", true))
                        .getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, sqlSeedResp.status());
        var sqlSeedData = extractData(sqlSeedResp);
        assertTrue(intField(sqlSeedData, "advisoryHopCount") >= 1);
        assertTrue(GraphJson.array(sqlSeedData.get("hops")).stream().map(GraphJson::object)
                .anyMatch(h -> "SQL_TABLE".equals(h.get("layer")) && "ADVISORY_UNVERIFIED".equals(h.get("trustTier"))));

        var tfSeedResp = api.handle(
                "POST",
                "/api/v1/graphs/" + graphId + "/queries/vertical-trace",
                "application/json",
                CanonicalJson.write(Map.of("seed", "aws_db_instance.orders_db", "seedKind", "TERRAFORM_RESOURCE", "includeAdvisory", true))
                        .getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, tfSeedResp.status());
        var tfSeedData = extractData(tfSeedResp);
        assertTrue(intField(tfSeedData, "advisoryHopCount") >= 1);
        assertTrue(GraphJson.array(tfSeedData.get("hops")).stream().map(GraphJson::object)
                .anyMatch(h -> "TERRAFORM_RESOURCE".equals(h.get("layer")) && "ADVISORY_UNVERIFIED".equals(h.get("trustTier"))));

        // 4. maxHops cap truncates honestly
        var cappedResp = api.handle(
                "POST",
                "/api/v1/graphs/" + graphId + "/queries/vertical-trace",
                "application/json",
                CanonicalJson.write(Map.of("context", fx.contextId(), "seed", "OrderController", "maxHops", 3, "includeAdvisory", true))
                        .getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, cappedResp.status());
        var cappedData = extractData(cappedResp);
        assertEquals(Boolean.TRUE, cappedData.get("truncated"));
        assertTrue(intField(cappedData, "totalHops") <= 3);

        // 5. Zero canonical KnowledgeGraph digest drift
        assertEquals(digestBefore, fx.graph().identity());
    }

    @Test
    void codeSliceEndpointComputesBackwardForwardBothSlicesWithZeroSecretLeakage(@TempDir Path tempDir) throws Exception {
        var fx = buildFullStackFixture();
        var store = saveGraph(tempDir, fx.graph());
        var api = new ArchitectureQueryApi(store);
        String graphId = fx.graph().identity().value();

        // 1. BACKWARD slice from return statement
        var backResp = api.handle(
                "POST",
                "/api/v1/graphs/" + graphId + "/queries/code-slice",
                "application/json",
                CanonicalJson.write(Map.of(
                        "path", fx.orderServicePath(),
                        "line", fx.totalReturnLine(),
                        "direction", "BACKWARD",
                        "maxStatements", 80)).getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, backResp.status(), () -> new String(backResp.body(), StandardCharsets.UTF_8));
        String backRaw = new String(backResp.body(), StandardCharsets.UTF_8);
        assertNoRawSecrets(backRaw);

        var backData = extractData(backResp);
        assertEquals(fx.orderServicePath(), backData.get("path"));
        assertEquals("BACKWARD", backData.get("direction"));
        assertEquals("SOURCE_SYNTAX", backData.get("trustTier"));
        assertEquals(fx.totalReturnLine(), intField(backData, "seedLine"));
        assertTrue(GraphJson.string(backData.get("methodSignature")).contains("computeTotal"));
        var backStmts = GraphJson.array(backData.get("statements")).stream().map(GraphJson::object).toList();
        assertTrue(backStmts.size() >= 3, "Expected backward slice statements: " + backStmts);
        assertTrue(backStmts.stream().anyMatch(s -> "SEED".equals(s.get("dependencyKind"))));
        assertTrue(backStmts.stream().anyMatch(s -> GraphJson.string(s.get("code")).contains(LlmTransport.REDACTED_PLACEHOLDER)),
                "Redacted placeholder should appear in place of secret literal: " + backStmts);
        assertTrue(backStmts.stream().noneMatch(s -> GraphJson.string(s.get("code")).contains("unrelatedBanner")),
                "Unrelated statement should be excluded from backward slice");

        // 2. FORWARD slice from input variable
        var fwdResp = api.handle(
                "POST",
                "/api/v1/graphs/" + graphId + "/queries/code-slice",
                "application/json",
                CanonicalJson.write(Map.of(
                        "path", fx.orderServicePath(),
                        "line", fx.inputLine(),
                        "direction", "FORWARD")).getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, fwdResp.status());
        String fwdRaw = new String(fwdResp.body(), StandardCharsets.UTF_8);
        assertNoRawSecrets(fwdRaw);
        var fwdData = extractData(fwdResp);
        assertEquals("FORWARD", fwdData.get("direction"));
        var fwdLines = GraphJson.array(fwdData.get("sliceLines"));
        assertTrue(fwdLines.size() >= 2, "Forward slice from baseAmount should flow to downstream statements");

        // 3. BOTH direction
        var bothResp = api.handle(
                "POST",
                "/api/v1/graphs/" + graphId + "/queries/code-slice",
                "application/json",
                CanonicalJson.write(Map.of(
                        "path", fx.orderServicePath(),
                        "line", fx.inputLine() + 1,
                        "direction", "BOTH")).getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, bothResp.status());
        assertNoRawSecrets(new String(bothResp.body(), StandardCharsets.UTF_8));
        var bothData = extractData(bothResp);
        assertEquals("BOTH", bothData.get("direction"));
        assertFalse(GraphJson.array(bothData.get("statements")).isEmpty());

        // 4. Error handling: missing file -> 404, traversal -> 400, non-Java -> honest limitation
        var missingResp = api.handle(
                "POST",
                "/api/v1/graphs/" + graphId + "/queries/code-slice",
                "application/json",
                CanonicalJson.write(Map.of("path", "src/main/java/com/acme/DoesNotExist.java", "line", 5))
                        .getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(404, missingResp.status());

        var traversalResp = api.handle(
                "POST",
                "/api/v1/graphs/" + graphId + "/queries/code-slice",
                "application/json",
                CanonicalJson.write(Map.of("path", "../../etc/passwd", "line", 1)).getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(400, traversalResp.status());

        var nonJavaResp = api.handle(
                "POST",
                "/api/v1/graphs/" + graphId + "/queries/code-slice",
                "application/json",
                CanonicalJson.write(Map.of("path", "db/migration/V1__orders.sql", "line", 1)).getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, nonJavaResp.status());
        var nonJavaData = extractData(nonJavaResp);
        assertTrue(GraphJson.array(nonJavaData.get("statements")).isEmpty());
        assertFalse(GraphJson.array(nonJavaData.get("limitations")).isEmpty());
    }

    @Test
    void multiUserAuthCsrfAndPatScopesAreEnforcedForVerticalTraceAndCodeSlice(@TempDir Path tempDir) throws Exception {
        var fx = buildFullStackFixture();
        var store = saveGraph(tempDir, fx.graph());
        String graphId = fx.graph().identity().value();
        String tracePath = "/api/v1/graphs/" + graphId + "/queries/vertical-trace";
        String slicePath = "/api/v1/graphs/" + graphId + "/queries/code-slice";
        byte[] traceBody = CanonicalJson.write(Map.of("context", fx.contextId(), "seed", "OrderController"))
                .getBytes(StandardCharsets.UTF_8);
        byte[] sliceBody = CanonicalJson.write(Map.of("path", fx.orderServicePath(), "line", fx.totalReturnLine()))
                .getBytes(StandardCharsets.UTF_8);

        // Static PAT policy checks
        assertTrue(ArchitectureQueryApi.isPatAuthorized(List.of("api:read"), "POST", tracePath));
        assertTrue(ArchitectureQueryApi.isPatAuthorized(List.of("mcp:read"), "POST", tracePath));
        assertTrue(ArchitectureQueryApi.isPatAuthorized(List.of("api:read"), "POST", slicePath));
        assertTrue(ArchitectureQueryApi.isPatAuthorized(List.of("mcp:read"), "POST", slicePath));
        assertFalse(ArchitectureQueryApi.isPatAuthorized(List.of("unrelated:scope"), "POST", tracePath));
        assertFalse(ArchitectureQueryApi.isPatAuthorized(List.of("unrelated:scope"), "POST", slicePath));

        try (var sessions = new UserSessionStore()) {
            var oauthCfg = new GitHubOAuthService.Config(
                    true, "", "", "github.com", GitHubOAuthService.DEFAULT_SCOPES,
                    false, Optional.empty(), Optional.empty(), Optional.empty());
            var oauthSvc = new GitHubOAuthService(oauthCfg, sessions,
                    (uri, method, headers, body, cancelled) -> { throw new UnsupportedOperationException("offline"); });
            var authApi = new AuthHttpApi(oauthSvc, null, ApiLimits.defaults());
            var api = ArchitectureQueryApi.withAuth(new ArchitectureQueryApi(store), authApi);

            // 1. Unauthenticated -> 401 AUTH_REQUIRED
            assertEquals(401, api.handle("POST", tracePath, "application/json", traceBody, () -> false).status());
            assertEquals(401, api.handle("POST", slicePath, "application/json", sliceBody, () -> false).status());

            // 2. PAT scopes (api:read & mcp:read -> 200; unknown scope -> 403)
            for (String scope : List.of("api:read", "mcp:read")) {
                var patUser = new AuthenticatedUser(
                        "acct:pat", "pat-bot", "PAT Bot", "", "pat@example.test",
                        List.of(), List.of(scope), AuthenticatedUser.AuthMethod.PERSONAL_ACCESS_TOKEN,
                        "github.com", "", System.currentTimeMillis(), 0L, UserAccountStore.ROLE_MEMBER, false, "");
                var reqCtx = new RequestContext.ActiveRequest(Optional.empty(), patUser, false, true, host -> Optional.empty());
                assertEquals(200, RequestContext.runWith(reqCtx, () -> api.handle("POST", tracePath, "application/json", traceBody, () -> false)).status(),
                        "PAT scope " + scope + " should be allowed on vertical-trace");
                assertEquals(200, RequestContext.runWith(reqCtx, () -> api.handle("POST", slicePath, "application/json", sliceBody, () -> false)).status(),
                        "PAT scope " + scope + " should be allowed on code-slice");
            }
            var badPatUser = new AuthenticatedUser(
                    "acct:badpat", "bad-bot", "Bad Bot", "", "bad@example.test",
                    List.of(), List.of("none"), AuthenticatedUser.AuthMethod.PERSONAL_ACCESS_TOKEN,
                    "github.com", "", System.currentTimeMillis(), 0L, UserAccountStore.ROLE_MEMBER, false, "");
            var badCtx = new RequestContext.ActiveRequest(Optional.empty(), badPatUser, false, true, host -> Optional.empty());
            assertEquals(403, RequestContext.runWith(badCtx, () -> api.handle("POST", tracePath, "application/json", traceBody, () -> false)).status());
            assertEquals(403, RequestContext.runWith(badCtx, () -> api.handle("POST", slicePath, "application/json", sliceBody, () -> false)).status());

            // 3. Live HTTP cookie CSRF check (missing CSRF -> 403; valid CSRF -> 200)
            var sessionUser = new AuthenticatedUser(
                    "acct:alice", "alice", "Alice", "", "alice@example.test",
                    List.of(), List.of(), AuthenticatedUser.AuthMethod.LOCAL_PASSWORD,
                    "github.com", sessions.newCsrfToken(), System.currentTimeMillis(), 0L,
                    UserAccountStore.ROLE_ADMIN, false, "");
            var session = sessions.createSession(sessionUser, new char[0], null, 0L, 0L);

            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                var client = HttpClient.newHttpClient();
                String cookieHeader = UserSessionStore.SESSION_COOKIE_NAME + "=" + session.sessionId();

                var noCsrf = client.send(HttpRequest.newBuilder(server.uri().resolve(tracePath))
                        .header("Content-Type", "application/json")
                        .header("Cookie", cookieHeader)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(traceBody))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(403, noCsrf.statusCode(), "Cookie POST without CSRF token must fail 403");

                var withCsrf = client.send(HttpRequest.newBuilder(server.uri().resolve(tracePath))
                        .header("Content-Type", "application/json")
                        .header("Cookie", cookieHeader)
                        .header(AuthHttpApi.CSRF_HEADER, sessionUser.csrfToken())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(traceBody))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, withCsrf.statusCode(), "Cookie POST with valid CSRF token must succeed 200");
            }
        }
    }

    @Test
    void agentAndMcpExposeAndExecuteVerticalTraceAndTraceSlice(@TempDir Path tempDir) throws Exception {
        var fx = buildFullStackFixture();
        var store = saveGraph(tempDir, fx.graph());
        String graphId = fx.graph().identity().value();

        // 1. ArchitectureAgent tool schema counts: 22 tools with includeSource=true, 18 with includeSource=false
        var schemasWithSource = ArchitectureAgent.toolSchemas(true);
        assertEquals(22, schemasWithSource.size(), "ArchitectureAgent should expose 22 tools with includeSource=true");
        assertEquals(18, ArchitectureAgent.toolSchemas(false).size(), "ArchitectureAgent should expose 18 tools with includeSource=false");
        var schemaNames = schemasWithSource.stream()
                .map(s -> GraphJson.string(GraphJson.object(s.get("function")).get("name")))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(schemaNames.contains("vertical_trace"));
        assertTrue(schemaNames.contains("trace_slice"));

        // 2. Run ArchitectureAgent loop calling vertical_trace + trace_slice via mock OpenAI server
        var capturedToolMessages = new ArrayList<Map<String, Object>>();
        var calls = new AtomicInteger();
        var mockLlm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mockLlm.createContext("/v1/chat/completions", exchange -> {
                var req = GraphJson.object(GraphJson.read(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                        new GraphJson.Limits(4_194_304, 64, 100_000)));
                int call = calls.incrementAndGet();
                String reply;
                if (call == 1) {
                    reply = CanonicalJson.write(Map.of(
                            "model", "test-m4-agent",
                            "choices", List.of(Map.of("finish_reason", "tool_calls", "message", Map.of(
                                    "role", "assistant", "content", "",
                                    "tool_calls", List.of(
                                            Map.of("id", "vt1", "type", "function", "function", Map.of(
                                                    "name", "vertical_trace",
                                                    "arguments", "{\"seed\":\"OrderController\",\"includeAdvisory\":true}")),
                                            Map.of("id", "ts1", "type", "function", "function", Map.of(
                                                    "name", "trace_slice",
                                                    "arguments", "{\"path\":\"" + fx.orderServicePath()
                                                            + "\",\"line\":" + fx.totalReturnLine()
                                                            + ",\"direction\":\"BACKWARD\"}"))))))));
                } else {
                    for (Object m : GraphJson.array(req.get("messages"))) {
                        var mo = GraphJson.object(m);
                        if ("tool".equals(mo.get("role"))) capturedToolMessages.add(mo);
                    }
                    String answer = CanonicalJson.write(Map.of(
                            "status", "ADVICE",
                            "conversationTitle", "Vertical Trace & Slice",
                            "answer", "OrderController traces to OrderEntity, SQL orders, and Terraform orders_db.",
                            "citations", List.of(),
                            "limitations", List.of()));
                    reply = CanonicalJson.write(Map.of(
                            "model", "test-m4-agent",
                            "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of(
                                    "role", "assistant", "content", answer)))));
                }
                byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            mockLlm.start();

            var endpoint = URI.create("http://127.0.0.1:" + mockLlm.getAddress().getPort() + "/v1/chat/completions");
            var model = new ArchitectureChatModel(endpoint, "test-m4-agent", null, false);
            var api = ArchitectureQueryApi.withArchitectureChat(new ArchitectureQueryApi(store), model);
            var resp = api.handle("POST", "/api/v1/graphs/" + graphId + "/architecture-chat",
                    "application/json",
                    CanonicalJson.write(Map.of(
                            "context", fx.contextId(), "mode", "EXPLORE", "agentic", true, "includeSource", true,
                            "messages", List.of(Map.of("role", "user", "content", "Trace OrderController and slice computeTotal."))))
                            .getBytes(StandardCharsets.UTF_8),
                    () -> false);
            assertEquals(200, resp.status(), () -> new String(resp.body(), StandardCharsets.UTF_8));
            assertEquals(2, capturedToolMessages.size());

            String vtToolJson = GraphJson.string(capturedToolMessages.get(0).get("content"));
            var vtOut = GraphJson.object(GraphJson.read(vtToolJson, GraphJson.Limits.defaults()));
            assertTrue(intField(vtOut, "canonicalHopCount") >= 3);
            assertTrue(intField(vtOut, "advisoryHopCount") >= 2);

            String tsToolJson = GraphJson.string(capturedToolMessages.get(1).get("content"));
            assertNoRawSecrets(tsToolJson);
            var tsOut = GraphJson.object(GraphJson.read(tsToolJson, GraphJson.Limits.defaults()));
            assertEquals("SOURCE_SYNTAX", tsOut.get("trustTier"));
            assertFalse(GraphJson.array(tsOut.get("statements")).isEmpty());
        } finally {
            mockLlm.stop(0);
        }

        // 3. WorkbenchMcpMain: offline --store direct mode (21 MCP tools total!)
        var directMcp = WorkbenchMcpMain.openDirectStore(tempDir, null, null);
        var directResponses = runMcp(directMcp, List.of(
                mcpMsg(1, "initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Map.of("name", "t", "version", "1"))),
                mcpMsg(2, "tools/list", Map.of()),
                mcpMsg(3, "tools/call", Map.of("name", "vertical_trace", "arguments", Map.of("seed", "OrderController", "includeAdvisory", true))),
                mcpMsg(4, "tools/call", Map.of("name", "trace_slice", "arguments", Map.of("path", fx.orderServicePath(), "line", fx.totalReturnLine(), "direction", "BACKWARD")))
        ));
        var toolList = GraphJson.array(GraphJson.object(directResponses.get(1).get("result")).get("tools"));
        assertEquals(21, toolList.size(), "WorkbenchMcpMain must expose 21 MCP tools total");
        var mcpNames = toolList.stream().map(t -> GraphJson.string(GraphJson.object(t).get("name")))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(mcpNames.contains("vertical_trace"));
        assertTrue(mcpNames.contains("trace_slice"));

        var vtMcpRes = GraphJson.object(directResponses.get(2).get("result"));
        assertEquals(false, vtMcpRes.get("isError"), "Direct MCP vertical_trace failed: " + vtMcpRes);
        String vtMcpText = extractMcpText(vtMcpRes);
        assertTrue(vtMcpText.contains("ADVISORY_UNVERIFIED"));
        assertTrue(vtMcpText.contains("SQL_TABLE"));

        var tsMcpRes = GraphJson.object(directResponses.get(3).get("result"));
        assertEquals(false, tsMcpRes.get("isError"), "Direct MCP trace_slice failed: " + tsMcpRes);
        String tsMcpText = extractMcpText(tsMcpRes);
        assertNoRawSecrets(tsMcpText);
        assertTrue(tsMcpText.contains("SOURCE_SYNTAX"));

        // 4. WorkbenchMcpMain: HTTP --port proxy mode
        var httpApi = new ArchitectureQueryApi(store);
        try (var server = new ArchitectureHttpServer(httpApi, 0)) {
            server.start();
            var proxyMcp = new WorkbenchMcpMain(server.uri().getPort(), null, null, null, graphId, fx.contextId());
            var proxyResponses = runMcp(proxyMcp, List.of(
                    mcpMsg(1, "initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Map.of("name", "t", "version", "1"))),
                    mcpMsg(2, "tools/call", Map.of("name", "vertical_trace", "arguments", Map.of("seed", "OrderController"))),
                    mcpMsg(3, "tools/call", Map.of("name", "trace_slice", "arguments", Map.of("path", fx.orderServicePath(), "line", fx.totalReturnLine(), "direction", "BOTH")))
            ));
            assertEquals(false, GraphJson.object(proxyResponses.get(1).get("result")).get("isError"));
            assertEquals(false, GraphJson.object(proxyResponses.get(2).get("result")).get("isError"));
            assertNoRawSecrets(extractMcpText(GraphJson.object(proxyResponses.get(2).get("result"))));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void openApiSpecificationRegistersVerticalTraceAndCodeSliceRoutesAndSchemas() throws Exception {
        try (var input = getClass().getResourceAsStream("/api/openapi.yaml")) {
            assertNotNull(input);
            var spec = (Map<String, Object>) new Yaml().load(input);
            var paths = (Map<String, Object>) spec.get("paths");
            assertTrue(paths.containsKey("/api/v1/graphs/{graph}/queries/vertical-trace"));
            assertTrue(paths.containsKey("/api/v1/graphs/{graph}/queries/code-slice"));
            var schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
            for (String s : List.of("VerticalLayer", "TraceHop", "VerticalTraceResult",
                    "SliceDirection", "SliceStatement", "MethodSliceResult")) {
                assertTrue(schemas.containsKey(s), "Missing OpenAPI schema: " + s);
            }
        }
    }

    // ─── Helpers ───

    private static FullStackFixture buildFullStackFixture() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/m4-fullstack.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("m4-fullstack-snap"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("m4-mod");
        String ctx = analysis.value();
        var mod = ModuleIdentity.from(repo, ".");

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", mod, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", mod, "path", "."));

        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv, modEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        nodes.add(new KnowledgeGraph.Node(mod.value(), ctx, "MODULE", List.of(modEv.identity())));

        String ctrlPath = "src/main/java/com/acme/OrderController.java";
        String svcPath = "src/main/java/com/acme/OrderService.java";
        String entPath = "src/main/java/com/acme/OrderEntity.java";
        String sqlPath = "db/migration/V1__orders.sql";
        String propsPath = "src/main/resources/application.properties";
        String tfPath = "infra/main.tf";

        String ctrlSrc = """
                package com.acme;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class OrderController {
                    private final OrderService service;
                    public OrderController(OrderService service) { this.service = service; }
                    public int getOrderTotal(int qty, boolean vip) {
                        return service.computeTotal(qty, vip);
                    }
                }
                """;
        // Line numbers in svcSrc:
        // 1: package com.acme;
        // 2: public class OrderService {
        // 3:     public int computeTotal(int qty, boolean vip) {
        // 4:         String unrelatedBanner = "ok";
        // 5:         int baseAmount = qty * 100;
        // 6:         String apiSecret = "<synthetic-stripe>";
        // 7:         String ghToken = "<synthetic-gh>";
        // 8:         int discount = 0;
        // 9:         if (vip && !apiSecret.isEmpty()) {
        // 10:            discount = baseAmount / 10;
        // 11:        }
        // 12:        int total = baseAmount - discount;
        // 13:        return total;
        // 14:    }
        // 15: }
        String svcSrc = String.join("\n",
                "package com.acme;",
                "public class OrderService {",
                "    public int computeTotal(int qty, boolean vip) {",
                "        String unrelatedBanner = \"ok\";",
                "        int baseAmount = qty * 100;",
                "        String apiSecret = \"" + SYNTHETIC_STRIPE_SECRET + "\";",
                "        String ghToken = \"" + SYNTHETIC_GH_SECRET + "\";",
                "        int discount = 0;",
                "        if (vip && !apiSecret.isEmpty()) {",
                "            discount = baseAmount / 10;",
                "        }",
                "        int total = baseAmount - discount;",
                "        return total;",
                "    }",
                "}");
        String entSrc = """
                package com.acme;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                @Entity
                @Table(name = "orders")
                public class OrderEntity {
                    private Long id;
                }
                """;
        String sqlSrc = """
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    total_cents INT NOT NULL
                );
                CREATE TABLE order_items (
                    id BIGINT PRIMARY KEY,
                    order_id BIGINT REFERENCES orders(id)
                );
                """;
        String propsSrc = """
                spring.application.name=orders-api
                spring.datasource.url=jdbc:postgresql://orders_db.internal:5432/orders
                """;
        String tfSrc = """
                resource "aws_security_group" "orders_sg" {
                  name = "orders-sg"
                }
                resource "aws_db_instance" "orders_db" {
                  engine = "postgres"
                  name   = "orders"
                  vpc_security_group_ids = [aws_security_group.orders_sg.id]
                }
                """;

        var docIds = new LinkedHashMap<String, String>();
        for (var fileEntry : Map.of(
                ctrlPath, ctrlSrc,
                svcPath, svcSrc,
                entPath, entSrc,
                sqlPath, sqlSrc,
                propsPath, propsSrc,
                tfPath, tfSrc).entrySet()) {
            byte[] bytes = fileEntry.getValue().getBytes(StandardCharsets.UTF_8);
            var bytesEv = GraphEvidence.capture("source.bytes", Map.of(
                    "document", Map.of("path", fileEntry.getKey(), "contentDigest", ContentDigest.sha256(bytes).value()),
                    "size", bytes.length,
                    "bytesBase64", Base64.getEncoder().encodeToString(bytes)));
            evidence.add(bytesEv);
            String docId = KnowledgeGraph.derivedIdentity("source-doc", List.of(fileEntry.getKey()));
            docIds.put(fileEntry.getKey(), docId);
            nodes.add(new KnowledgeGraph.Node(docId, snap.value(), "SOURCE_DOCUMENT", List.of(bytesEv.identity())));
        }

        // Canonical Java types: OrderController -> OrderService -> OrderEntity
        var ctrlEnt = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                JavaSymbolName.topLevelType("com.acme", "OrderController").canonicalName(), Optional.empty());
        var svcEnt = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                JavaSymbolName.topLevelType("com.acme", "OrderService").canonicalName(), Optional.empty());
        var jpaEnt = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                JavaSymbolName.topLevelType("com.acme", "OrderEntity").canonicalName(), Optional.empty());

        var ctrlDecl = GraphEvidence.capture("java.declaration", Map.of(
                "entity", ctrlEnt,
                "spelling", "OrderController",
                "declaration", Map.of("document", docIds.get(ctrlPath), "startLine", 4, "endLine", 10),
                "status", "RESOLVED"));
        var svcDecl = GraphEvidence.capture("java.declaration", Map.of(
                "entity", svcEnt,
                "spelling", "OrderService",
                "declaration", Map.of("document", docIds.get(svcPath), "startLine", 2, "endLine", 15),
                "status", "RESOLVED"));
        var jpaDecl = GraphEvidence.capture("java.declaration", Map.of(
                "entity", jpaEnt,
                "spelling", "OrderEntity",
                "declaration", Map.of("document", docIds.get(entPath), "startLine", 6, "endLine", 8),
                "status", "RESOLVED"));
        var jpaAnn = GraphEvidence.capture("java.annotation-use", Map.of(
                "owner", jpaEnt.identity().value(),
                "annotation", "jakarta.persistence.Entity",
                "span", Map.of("document", docIds.get(entPath), "startLine", 4, "endLine", 4)));
        var epEv = GraphEvidence.capture("spring.endpoint-declaration", Map.of(
                "module", mod.value(),
                "sourceSet", "main",
                "route", Map.of("path", "/api/orders", "methods", List.of("GET")),
                "span", Map.of("document", docIds.get(ctrlPath), "startLine", 7, "endLine", 7)));
        evidence.addAll(List.of(ctrlDecl, svcDecl, jpaDecl, jpaAnn, epEv));

        String endpointId = KnowledgeGraph.derivedIdentity("endpoint", List.of("GET", "/api/orders"));
        nodes.add(new KnowledgeGraph.Node(endpointId, ctx, "ENDPOINT", List.of(epEv.identity())));
        nodes.add(new KnowledgeGraph.Node(ctrlEnt.identity().value(), ctx, "TYPE", List.of(ctrlDecl.identity())));
        nodes.add(new KnowledgeGraph.Node(svcEnt.identity().value(), ctx, "TYPE", List.of(svcDecl.identity())));
        nodes.add(new KnowledgeGraph.Node(jpaEnt.identity().value(), ctx, "TYPE", List.of(jpaDecl.identity())));
        nodes.add(new KnowledgeGraph.Node(KnowledgeGraph.derivedIdentity("ann", List.of("OrderEntity", "Entity")),
                ctx, "ANNOTATION_USE", List.of(jpaAnn.identity())));

        // Edges: controller declares endpoint; controller -> service -> entity
        var declEpEv = GraphEvidence.capture("spring.endpoint-owner", Map.of("owner", ctrlEnt.identity().value(), "endpoint", endpointId));
        var c2sEv = GraphEvidence.capture("java.reference", Map.of("from", ctrlEnt.identity().value(), "to", svcEnt.identity().value()));
        var s2eEv = GraphEvidence.capture("java.reference", Map.of("from", svcEnt.identity().value(), "to", jpaEnt.identity().value()));
        evidence.addAll(List.of(declEpEv, c2sEv, s2eEv));

        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("contains", List.of(ctrlEnt.identity().value(), endpointId)),
                ctx, "graph.contains", ctrlEnt.identity().value(),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(endpointId), Optional.empty()),
                List.of(declEpEv.identity()), Optional.empty()));
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("dep", List.of("ctrl", "svc")),
                ctx, "graph.depends-on", ctrlEnt.identity().value(),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(svcEnt.identity().value()), Optional.empty()),
                List.of(c2sEv.identity()), Optional.empty()));
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("dep", List.of("svc", "ent")),
                ctx, "graph.depends-on", svcEnt.identity().value(),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(jpaEnt.identity().value()), Optional.empty()),
                List.of(s2eEv.identity()), Optional.empty()));

        var graph = new KnowledgeGraph(
                snap,
                List.of(
                        new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                        new KnowledgeGraph.Context(ctx, snap, Optional.of(analysis), manEv.identity())),
                nodes,
                edges,
                evidence);
        return new FullStackFixture(graph, ctx, svcPath, ctrlEnt.identity().value(), jpaEnt.identity().value(), 13, 5);
    }

    private static FileGraphStore saveGraph(Path tempDir, KnowledgeGraph graph) throws Exception {
        Path graphsDir = tempDir.resolve("graphs");
        Files.createDirectories(graphsDir);
        var store = new FileGraphStore(graphsDir);
        store.save(graph);
        return store;
    }

    private static Map<String, Object> extractData(ArchitectureQueryApi.Response resp) {
        var root = GraphJson.object(GraphJson.read(
                new String(resp.body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
        return GraphJson.object(root.get("data"));
    }

    private static int intField(Map<String, Object> map, String key) {
        Object v = map.get(key);
        assertInstanceOf(Number.class, v, "Expected numeric field " + key + " in " + map);
        return ((Number) v).intValue();
    }

    private static void assertNoRawSecrets(String payload) {
        assertFalse(payload.contains("sk_" + "live_"), "Raw Stripe-style secret leaked in payload: " + payload);
        assertFalse(payload.contains("ghp_" + "1234567890"), "Raw GitHub-style secret leaked in payload: " + payload);
    }

    private static String mcpMsg(int id, String method, Map<String, Object> params) {
        return CanonicalJson.write(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    }

    private static List<Map<String, Object>> runMcp(WorkbenchMcpMain mcp, List<String> lines) throws Exception {
        var in = new StringReader(String.join("\n", lines) + "\n");
        var out = new StringWriter();
        mcp.run(in, new PrintWriter(out, true));
        var results = new ArrayList<Map<String, Object>>();
        for (String line : out.toString().split("\\R")) {
            if (!line.isBlank()) {
                results.add(GraphJson.object(GraphJson.read(line, GraphJson.Limits.defaults())));
            }
        }
        return results;
    }

    private static String extractMcpText(Map<String, Object> toolResult) {
        var content = GraphJson.array(toolResult.get("content"));
        return GraphJson.string(GraphJson.object(content.getFirst()).get("text"));
    }
}
