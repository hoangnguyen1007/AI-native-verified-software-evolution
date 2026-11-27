package com.evolution.backend.auth;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.analysis.RepositoryConnections;
import com.evolution.backend.github.*;
import com.evolution.backend.query.*;
import com.evolution.backend.query.repair.RepairVerificationJobs;
import com.evolution.backend.query.repair.SafePatchApplier;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GitHubOAuthAndSessionTest {

    @Test
    void oauthWebPkceDeviceFlowRefreshAndStrictMultiUserTenantCsrfIsolation(@TempDir Path tempDir) throws Exception {
        var pollCount = new AtomicInteger();
        var capturedVerifier = new AtomicReference<String>();
        var capturedChallenge = new AtomicReference<String>();

        var ghMock = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        ghMock.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (path.equals("/login/device/code")) {
                respondJson(ex, 200, "{\"device_code\":\"dev_secret_code_123\",\"user_code\":\"ABCD-1234\","
                        + "\"verification_uri\":\"https://github.com/login/device\",\"expires_in\":900,\"interval\":5}");
                return;
            }
            if (path.equals("/login/oauth/access_token")) {
                if (body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")) {
                    int n = pollCount.incrementAndGet();
                    if (n == 1) {
                        respondJson(ex, 200, "{\"error\":\"authorization_pending\"}");
                    } else if (n == 2) {
                        respondJson(ex, 200, "{\"error\":\"slow_down\",\"interval\":10}");
                    } else {
                        respondJson(ex, 200, "{\"access_token\":\"gho_bob_device_token_99999\",\"token_type\":\"bearer\",\"scope\":\"read:user,repo\"}");
                    }
                    return;
                }
                if (body.contains("grant_type=refresh_token")) {
                    respondJson(ex, 200, "{\"access_token\":\"ghu_refreshed_alice_77777\",\"refresh_token\":\"ghr_next_88888\","
                            + "\"expires_in\":28800,\"refresh_token_expires_in\":15897600,\"scope\":\"read:user,repo\"}");
                    return;
                }
                // Web flow PKCE code exchange
                String verifier = parseFormParam(body, "code_verifier");
                capturedVerifier.set(verifier);
                if (verifier == null || !GitHubOAuthService.pkceS256(verifier).equals(capturedChallenge.get())) {
                    respondJson(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"PKCE mismatch\"}");
                    return;
                }
                respondJson(ex, 200, "{\"access_token\":\"gho_alice_web_token_11111\",\"refresh_token\":\"ghr_alice_init_11111\","
                        + "\"expires_in\":28800,\"token_type\":\"bearer\",\"scope\":\"read:user,repo,read:org\"}");
                return;
            }
            if (path.equals("/user")) {
                if ("Bearer gho_alice_web_token_11111".equals(auth) || "Bearer ghu_refreshed_alice_77777".equals(auth)) {
                    ex.getResponseHeaders().set("X-OAuth-Scopes", "read:user, repo, read:org");
                    respondJson(ex, 200, "{\"id\":1001,\"login\":\"alice\",\"name\":\"Alice Architect\","
                            + "\"avatar_url\":\"https://avatars.githubusercontent.com/u/1001\",\"email\":\"alice@acme.dev\"}");
                    return;
                }
                if ("Bearer gho_bob_device_token_99999".equals(auth)) {
                    ex.getResponseHeaders().set("X-OAuth-Scopes", "read:user, repo");
                    respondJson(ex, 200, "{\"id\":2002,\"login\":\"bob\",\"name\":\"Bob Reviewer\","
                            + "\"avatar_url\":\"https://avatars.githubusercontent.com/u/2002\",\"email\":\"bob@acme.dev\"}");
                    return;
                }
                respondJson(ex, 401, "{\"message\":\"Bad credentials\"}");
                return;
            }
            if (path.equals("/user/orgs")) {
                ex.getResponseHeaders().set("X-RateLimit-Limit", "5000");
                ex.getResponseHeaders().set("X-RateLimit-Reset", "1800000000");
                if ("Bearer gho_alice_web_token_11111".equals(auth)) {
                    ex.getResponseHeaders().set("X-RateLimit-Remaining", "4999");
                    respondJson(ex, 200, "[{\"id\":50,\"login\":\"acme-core\",\"description\":\"Core\"}]");
                } else {
                    ex.getResponseHeaders().set("X-RateLimit-Remaining", "123");
                    respondJson(ex, 200, "[{\"id\":99,\"login\":\"bob-labs\",\"description\":\"Labs\"}]");
                }
                return;
            }
            respondJson(ex, 404, "{\"message\":\"Not Found\"}");
        });
        ghMock.start();

        URI mockUri = URI.create("http://127.0.0.1:" + ghMock.getAddress().getPort());
        var graphs = new InMemoryGraphStore();
        var reports = new PublishedReports();
        Path localRepo = tempDir.resolve("alice-local-repo");
        Files.createDirectories(localRepo.resolve("src"));
        String relPath = "src/Sample.java";
        String before = "class Sample { int v() { return 1; } }\n";
        String after = "class Sample { int v() { return 2; } }\n";
        Files.writeString(localRepo.resolve(relPath), before, StandardCharsets.UTF_8);

        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:evo:local:alice-sample");
        var snap = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("snap-alice"));
        var ev = GraphEvidence.capture("repository.snapshot", Map.of("seed", "alice"));
        var graph = new KnowledgeGraph(snap,
                List.of(new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), ev.identity())),
                List.of(new KnowledgeGraph.Node(repoId.value(), snap.value(), "REPOSITORY", List.of(ev.identity()))),
                List.of(), List.of(ev));
        graphs.save(graph);

        String patch = RepairPatch.file(relPath, before, after);
        var edit = new CapturedRepair.Edit(relPath, ContentDigest.sha256Utf8(before), ContentDigest.sha256Utf8(after),
                ContentDigest.sha256Utf8("bytes"), List.of());
        var pin = new FindingDiagnosis.Pin(snap, Optional.empty(), graph.identity(), snap.value(),
                ContentDigest.sha256Utf8("r"), ContentDigest.sha256Utf8("f"), ContentDigest.sha256Utf8("b"));
        var proof = new FindingDiagnosis.Proof(new VersionedIdentifier("rule.demo", "1"), ArchitectureQuery.Scope.TYPE,
                "A", "B", "graph.depends-on", List.of("e"), ArchitectureQuery.Classification.MUST, ContentDigest.sha256Utf8("reg"));
        var candidate = new CapturedRepair.Candidate(pin, ContentDigest.sha256Utf8("d"), proof, List.of(),
                CapturedRepair.Strategy.RECIPE, Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(edit), List.of(), ContentDigest.sha256Utf8(patch), patch, "SYNTAX_ONLY");
        var propId = CanonicalJson.sha256(List.of(CapturedRepair.VERSION, candidate));
        var repairStore = new RepairProposalStore(tempDir.resolve("repairs"));
        repairStore.put(new CapturedRepair.Proposal(propId, candidate));

        var oauthCfg = new GitHubOAuthService.Config(
                true, "evo-client-id", "evo-client-secret", "github.com",
                GitHubOAuthService.DEFAULT_SCOPES, true, Optional.empty(),
                Optional.of(mockUri), Optional.of(mockUri));

        try (var broker = new GitHubTokenBroker(k -> null, h -> Optional.empty());
             var sessionStore = new UserSessionStore();
             var jobs = new AnalysisJobs(List.of(), graphs, reports, AnalysisJobs.Limits.defaults());
             var repairJobs = new RepairVerificationJobs()) {
            broker.setMultiUserMode(true);
            var connections = new RepositoryConnections(jobs, Path.of(System.getProperty("java.home")), 21,
                    tempDir.resolve("m2"), tempDir.resolve("store"), Optional.empty(), "git", new GitHostPolicy());
            connections.describeLocal("shared-local", "alice-local", repoId, localRepo, Optional.empty());

            var transport = loopbackTransport();
            var rest = new GitHubRestClient(broker, transport, mockUri);
            var oauth = new GitHubOAuthService(oauthCfg, sessionStore, transport);
            var authApi = new AuthHttpApi(oauth, rest, ApiLimits.defaults());
            var diffSvc = new GitHubPrDiffService(rest, jobs, connections, graphs, reports, tempDir.resolve("diffs"));
            var pub = new GitHubRepairPublisher(rest, broker, repairStore, connections, new GitHostPolicy(),
                    tempDir.resolve("pub"), "git");
            var ghApi = new GitHubHttpApi(broker, rest, connections, diffSvc, pub, repairJobs, ApiLimits.defaults())
                    .withEnterpriseServices(authApi, null, null, null, graphs, reports);

            var patchApplier = new SafePatchApplier(tempDir.resolve("applies"));
            var api = ArchitectureQueryApi.withAuth(
                    ArchitectureQueryApi.withRepairVerificationJobs(
                            ArchitectureQueryApi.withSafePatchApplier(
                                    ArchitectureQueryApi.withRepairStore(
                                            ArchitectureQueryApi.withGitHub(
                                                    new ArchitectureQueryApi(graphs, reports, ApiLimits.defaults(), jobs, connections),
                                                    ghApi),
                                            repairStore),
                                    patchApplier),
                            repairJobs),
                    authApi);

            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                String root = server.uri().toString();
                var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

                // 1. Multi-user mode blocks unauthenticated protected routes AND dedicated HTTP lanes (401)
                var cfgResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/config")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, cfgResp.statusCode());
                assertTrue(cfgResp.body().contains("\"multiUserMode\":true"));
                assertTrue(cfgResp.body().contains("\"oauthWebEnabled\":true"));
                assertTrue(cfgResp.body().contains("\"deviceFlowEnabled\":true"));
                assertFalse(cfgResp.body().contains("evo-client-secret"), "Client secret must NEVER leak");

                var unauthOrgs = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/orgs")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(401, unauthOrgs.statusCode());

                // P0 #1: Dedicated HTTP lanes MUST reject unauthenticated callers with 401 AUTH_REQUIRED
                UUID randomJobId = UUID.randomUUID();
                assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/jobs/" + randomJobId + "/events"))
                                .header("Accept", "text/event-stream").GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/jobs/" + randomJobId + "/report.md"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/graphs/" + graph.identity().value() + "/architecture-chat/stream"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());

                // P2 #7: Unsafe returnTo values MUST be rejected explicitly with 400 INVALID_REQUEST
                for (String unsafe : List.of("//evil.com", "https://evil.com/x", "/\\evil.com", "evil.com")) {
                    var badRedirect = http.send(HttpRequest.newBuilder(URI.create(
                            root + "/api/v1/auth/github/login?returnTo=" + java.net.URLEncoder.encode(unsafe, StandardCharsets.UTF_8)))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(400, badRedirect.statusCode(), "Unsafe returnTo must return 400: " + unsafe + " -> " + badRedirect.body());
                    assertTrue(badRedirect.body().contains("INVALID_REQUEST"), badRedirect.body());
                }
                // Even when OAuth Web is unconfigured, unsafe returnTo MUST still fail 400 (NOT 503!)
                var unconfiguredOAuth = new GitHubOAuthService(
                        new GitHubOAuthService.Config(true, "", "", "github.com",
                                GitHubOAuthService.DEFAULT_SCOPES, false, Optional.empty(), Optional.empty(), Optional.empty()),
                        sessionStore, transport);
                var uncfgErr = assertThrows(GitHubOAuthService.OAuthException.class,
                        () -> unconfiguredOAuth.startWebLogin(root, Optional.of("//evil.com")));
                assertEquals(400, uncfgErr.status());
                assertEquals("INVALID_REQUEST", uncfgErr.code());

                // 2. User A (@alice) signs in via OAuth 2.0 Web Flow + PKCE S256
                var loginResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/login?returnTo=%2F%23github")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(302, loginResp.statusCode());
                String authorizeLocation = loginResp.headers().firstValue("Location").orElseThrow();
                assertTrue(authorizeLocation.contains("code_challenge_method=S256"));
                String state = parseQueryParam(URI.create(authorizeLocation).getRawQuery(), "state");
                capturedChallenge.set(parseQueryParam(URI.create(authorizeLocation).getRawQuery(), "code_challenge"));
                assertNotNull(state);
                assertNotNull(capturedChallenge.get());

                var cbResp = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/auth/github/callback?code=auth_code_1&state=" + state)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(302, cbResp.statusCode(), cbResp.body());
                assertEquals("/?auth=signed_in#github", cbResp.headers().firstValue("Location").orElse(""));
                String aliceSetCookie = cbResp.headers().firstValue("Set-Cookie").orElseThrow();
                assertTrue(aliceSetCookie.contains("HttpOnly") && aliceSetCookie.contains("SameSite=Lax"));
                String aliceCookie = aliceSetCookie.split(";", 2)[0];
                assertNotNull(capturedVerifier.get());

                // Replaying single-use state fails 400
                var replayCb = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/auth/github/callback?code=auth_code_1&state=" + state)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(400, replayCb.statusCode());

                var aliceMe = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/me"))
                                .header("Cookie", aliceCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, aliceMe.statusCode());
                assertTrue(aliceMe.body().contains("\"login\":\"alice\""));
                assertFalse(aliceMe.body().contains("gho_alice_web_token"));
                String aliceCsrf = extractDataString(aliceMe.body(), "csrfToken");
                assertFalse(aliceCsrf.isBlank());

                // 3. User B (@bob) signs in via Device Flow (PENDING -> SLOW_DOWN -> AUTHORIZED)
                var devStart = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/device/start"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, devStart.statusCode(), devStart.body());
                assertTrue(devStart.body().contains("\"userCode\":\"ABCD-1234\""));
                assertFalse(devStart.body().contains("dev_secret_code_123"), "Raw device_code must never leak to browser");
                String deviceFlowId = extractDataString(devStart.body(), "deviceFlowId");

                var poll1 = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/device/poll"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"deviceFlowId\":\"" + deviceFlowId + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(poll1.body().contains("\"status\":\"PENDING\""));

                var poll2 = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/device/poll"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"deviceFlowId\":\"" + deviceFlowId + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(poll2.body().contains("\"status\":\"SLOW_DOWN\""));

                var poll3 = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/device/poll"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"deviceFlowId\":\"" + deviceFlowId + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, poll3.statusCode());
                assertTrue(poll3.body().contains("\"status\":\"AUTHORIZED\""));
                assertTrue(poll3.body().contains("\"login\":\"bob\""));
                assertFalse(poll3.body().contains("gho_bob_device_token"));
                String bobCookie = poll3.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                String bobCsrf = extractDataString(poll3.body(), "csrfToken");
                assertNotEquals(aliceCsrf, bobCsrf);

                // 4. Per-user token, org & rate-limit isolation (Zero cross-tenant ETag, rateLimit or token leakage!)
                var aliceOrgs = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/orgs"))
                                .header("Cookie", aliceCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(aliceOrgs.body().contains("acme-core") && !aliceOrgs.body().contains("bob-labs"));

                var bobOrgs = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/orgs"))
                                .header("Cookie", bobCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(bobOrgs.body().contains("bob-labs") && !bobOrgs.body().contains("acme-core"));

                // P2 #8: Rate limit metadata MUST stay isolated per user
                var aliceMeRl = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/me"))
                                .header("Cookie", aliceCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(aliceMeRl.body().contains("\"remaining\":4999"), aliceMeRl.body());
                var bobMeRl = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/me"))
                                .header("Cookie", bobCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(bobMeRl.body().contains("\"remaining\":123"), bobMeRl.body());

                // 5. CSRF enforcement on cookie-authenticated mutations (including chat stream SSE & DELETE session!)
                var noCsrf = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/analysis/connections"))
                                .header("Cookie", aliceCookie)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"source\":\"acme/private-app\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, noCsrf.statusCode());
                assertTrue(noCsrf.body().contains("CSRF_TOKEN_INVALID"));

                var chatStreamNoCsrf = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/graphs/" + graph.identity().value() + "/architecture-chat/stream"))
                                .header("Cookie", aliceCookie)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"hi\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, chatStreamNoCsrf.statusCode());
                assertTrue(chatStreamNoCsrf.body().contains("CSRF_TOKEN_INVALID"));

                // 6. Per-user connection & analysis-job isolation: Alice connects repo; Bob CANNOT list, submit jobs to, or disconnect it (403)
                var aliceConn = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/analysis/connections"))
                                .header("Cookie", aliceCookie)
                                .header(AuthHttpApi.CSRF_HEADER, aliceCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"source\":\"acme/private-app\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, aliceConn.statusCode(), aliceConn.body());
                String aliceSetupId = extractDataString(aliceConn.body(), "setup");

                var bobList = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/analysis/connections"))
                                .header("Cookie", bobCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, bobList.statusCode());
                assertFalse(bobList.body().contains(aliceSetupId), "Bob must NOT see Alice's private connection");

                // P0 #2: Bob CANNOT submit jobs against Alice's setup OR the hidden host "shared-local" setup (403)
                for (String forbiddenSetup : List.of(aliceSetupId, "shared-local")) {
                    var bobSubmit = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/analysis/jobs"))
                                    .header("Cookie", bobCookie)
                                    .header(AuthHttpApi.CSRF_HEADER, bobCsrf)
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                            "setup", forbiddenSetup, "idempotencyKey", "bob-hijack-" + forbiddenSetup))))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(403, bobSubmit.statusCode(), "Expected 403 for setup " + forbiddenSetup + " -> " + bobSubmit.body());
                    assertTrue(bobSubmit.body().contains("ACCESS_DENIED"), bobSubmit.body());
                }

                var bobDisconnect = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/analysis/connections/" + aliceSetupId))
                                .header("Cookie", bobCookie)
                                .header(AuthHttpApi.CSRF_HEADER, bobCsrf)
                                .DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, bobDisconnect.statusCode());
                assertTrue(bobDisconnect.body().contains("ACCESS_DENIED"));

                // 7a. P1 #4: Host local checkout connect & local patch apply are disabled for OAuth users in multiUserMode=true
                var aliceLocalConnect = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/analysis/connections"))
                                .header("Cookie", aliceCookie)
                                .header(AuthHttpApi.CSRF_HEADER, aliceCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("source", localRepo.toString()))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, aliceLocalConnect.statusCode(), aliceLocalConnect.body());
                assertTrue(aliceLocalConnect.body().contains("LOCAL_CHECKOUT_DISABLED_IN_MULTI_USER"), aliceLocalConnect.body());

                var aliceLocalPreviewBlocked = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/" + propId.value() + "/apply/preview"))
                                .header("Cookie", aliceCookie)
                                .header(AuthHttpApi.CSRF_HEADER, aliceCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "repoDir", localRepo.toString(), "mode", "DIRECT_WORKING_TREE"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, aliceLocalPreviewBlocked.statusCode(), aliceLocalPreviewBlocked.body());
                assertTrue(aliceLocalPreviewBlocked.body().contains("LOCAL_CHECKOUT_DISABLED_IN_MULTI_USER"), aliceLocalPreviewBlocked.body());

                // 7b. P1 #4: RepairVerificationJobs multi-user tenant isolation across REST + SSE + report.md + cancel
                var aliceVerify = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/" + propId.value() + "/verify"))
                                .header("Cookie", aliceCookie)
                                .header(AuthHttpApi.CSRF_HEADER, aliceCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"runIsolated\":false}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(202, aliceVerify.statusCode(), aliceVerify.body());
                String aliceRepairJobId = extractDataString(aliceVerify.body(), "jobId");

                // Wait for Alice's repair verification job to finish via Alice's SSE lane
                var aliceSse = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/jobs/" + aliceRepairJobId + "/events"))
                                .header("Cookie", aliceCookie)
                                .header("Accept", "text/event-stream").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, aliceSse.statusCode(), aliceSse.body());
                assertTrue(aliceSse.body().contains("event: completed"), aliceSse.body());

                // Bob cannot list, get, stream events, read markdown report, or cancel Alice's repair job!
                var bobRepairList = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/jobs"))
                                .header("Cookie", bobCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, bobRepairList.statusCode());
                assertFalse(bobRepairList.body().contains(aliceRepairJobId), "Bob must NOT see Alice's repair job");

                var bobRepairGet = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/jobs/" + aliceRepairJobId))
                                .header("Cookie", bobCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, bobRepairGet.statusCode());
                assertTrue(bobRepairGet.body().contains("APPLY_ACCESS_DENIED"));

                var bobRepairMd = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/jobs/" + aliceRepairJobId + "/report.md"))
                                .header("Cookie", bobCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, bobRepairMd.statusCode());
                assertTrue(bobRepairMd.body().contains("APPLY_ACCESS_DENIED"));

                var bobRepairSse = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/jobs/" + aliceRepairJobId + "/events"))
                                .header("Cookie", bobCookie).header("Accept", "text/event-stream").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, bobRepairSse.statusCode());
                assertTrue(bobRepairSse.body().contains("APPLY_ACCESS_DENIED"));

                var bobRepairCancel = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/jobs/" + aliceRepairJobId + "/cancel"))
                                .header("Cookie", bobCookie).header(AuthHttpApi.CSRF_HEADER, bobCsrf)
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, bobRepairCancel.statusCode());
                assertTrue(bobRepairCancel.body().contains("APPLY_ACCESS_DENIED"));

                // Alice can still read her own markdown report!
                var aliceRepairMd = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/jobs/" + aliceRepairJobId + "/report.md"))
                                .header("Cookie", aliceCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, aliceRepairMd.statusCode());

                // 7c. Per-user SafePatchApplier isolation directly: Bob gets AccessDeniedException WITHOUT consuming Alice's token!
                var prop = repairStore.get(propId).orElseThrow();
                var preview = patchApplier.previewApply("1001", prop, Optional.empty(), localRepo,
                        SafePatchApplier.ApplyMode.DIRECT_WORKING_TREE, Optional.empty());
                assertTrue(preview.canApply());
                String directToken = preview.approvalToken().orElseThrow();
                assertThrows(SafePatchApplier.AccessDeniedException.class, () -> patchApplier.confirmApply(
                        "2002", prop, Optional.empty(), directToken, true, localRepo,
                        SafePatchApplier.ApplyMode.DIRECT_WORKING_TREE, Optional.empty(), Optional.empty(),
                        true, Optional.empty()));
                assertEquals(before, Files.readString(localRepo.resolve(relPath), StandardCharsets.UTF_8));
                var applied = patchApplier.confirmApply(
                        "1001", prop, Optional.empty(), directToken, true, localRepo,
                        SafePatchApplier.ApplyMode.DIRECT_WORKING_TREE, Optional.empty(), Optional.empty(),
                        true, Optional.empty());
                assertEquals(after, Files.readString(localRepo.resolve(relPath), StandardCharsets.UTF_8));
                assertThrows(SafePatchApplier.AccessDeniedException.class,
                        () -> patchApplier.rollbackApply("2002", applied.applyId(), false));
                assertEquals(after, Files.readString(localRepo.resolve(relPath), StandardCharsets.UTF_8));
                patchApplier.rollbackApply("1001", applied.applyId(), false);
                assertEquals(before, Files.readString(localRepo.resolve(relPath), StandardCharsets.UTF_8));

                // 8. Logout-all revokes Alice's session cleanly
                var logoutAll = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/logout-all"))
                                .header("Cookie", aliceCookie)
                                .header(AuthHttpApi.CSRF_HEADER, aliceCsrf)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, logoutAll.statusCode());
                assertTrue(logoutAll.body().contains("\"revokedSessions\":1"));
                var aliceAfterLogout = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/orgs"))
                                .header("Cookie", aliceCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(401, aliceAfterLogout.statusCode());
            }
        } finally {
            ghMock.stop(0);
        }
    }

    @Test
    void encryptedAes256GcmSessionStoreRoundTripAndExpiringTokenAutoRefresh(@TempDir Path tempDir) throws Exception {
        Path encFile = tempDir.resolve("sessions.enc");
        byte[] secret = "0123456789abcdef0123456789abcdef_secret_key".getBytes(StandardCharsets.UTF_8);
        String sid;
        try (var store = new UserSessionStore(Optional.of(encFile), Optional.of(secret))) {
            assertTrue(store.isEncryptedPersistenceEnabled());
            var user = new AuthenticatedUser("1001", "alice", "Alice", "", "alice@acme.dev",
                    List.of("acme"), List.of("repo"), AuthenticatedUser.AuthMethod.OAUTH_WEB,
                    "github.com", store.newCsrfToken(), System.currentTimeMillis(), System.currentTimeMillis() + 60_000L);
            // Token expires in 30 seconds (within 5-min refresh window!)
            var handle = store.createSession(user, "ghu_expiring_access_12345".toCharArray(),
                    "ghr_refresh_token_67890".toCharArray(),
                    System.currentTimeMillis() + 30_000L,
                    System.currentTimeMillis() + 3_600_000L);
            sid = handle.sessionId();
        }
        // Verify ZERO plaintext token on disk!
        String onDisk = Files.readString(encFile, StandardCharsets.UTF_8);
        assertTrue(onDisk.contains(UserSessionStore.PERSIST_SCHEMA));
        assertFalse(onDisk.contains("ghu_expiring_access_12345"), "Access token MUST be encrypted on disk");
        assertFalse(onDisk.contains("ghr_refresh_token_67890"), "Refresh token MUST be encrypted on disk");

        // Reopen store and verify transparent refresh-token renewal!
        try (var reopened = new UserSessionStore(Optional.of(encFile), Optional.of(secret))) {
            assertTrue(reopened.get(sid).isPresent());
            var resolved = reopened.resolveToken(sid, "github.com", (host, refresh) -> {
                assertEquals("ghr_refresh_token_67890", new String(refresh));
                return Optional.of(new UserSessionStore.RefreshedToken(
                        "ghu_brand_new_access_55555".toCharArray(),
                        "ghr_brand_new_refresh_66666".toCharArray(),
                        System.currentTimeMillis() + 28_800_000L,
                        System.currentTimeMillis() + 86_400_000L,
                        List.of("repo")));
            });
            assertTrue(resolved.isPresent());
            assertEquals("ghu_brand_new_access_55555", resolved.orElseThrow().token());
        }
    }

    @Test
    void concurrentRefreshAndLogoutHasNoRaceOrDeadlock() throws Exception {
        try (var store = new UserSessionStore()) {
            var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                for (int round = 0; round < 20; round++) {
                    var user = new AuthenticatedUser("u-" + round, "user" + round, "User", "", "u@acme.dev",
                            List.of(), List.of("repo"), AuthenticatedUser.AuthMethod.OAUTH_WEB,
                            "github.com", store.newCsrfToken(), System.currentTimeMillis(),
                            System.currentTimeMillis() + 30_000L);
                    String sid = store.createSession(user,
                            ("ghu_old_" + round).toCharArray(),
                            ("ghr_ref_" + round).toCharArray(),
                            System.currentTimeMillis() + 30_000L,
                            System.currentTimeMillis() + 3_600_000L).sessionId();
                    var enteredRefresh = new java.util.concurrent.CountDownLatch(1);
                    var releaseRefresh = new java.util.concurrent.CountDownLatch(1);
                    final int idx = round;
                    var refreshFuture = pool.submit(() -> store.resolveToken(sid, "github.com", (h, ref) -> {
                        assertEquals("ghr_ref_" + idx, new String(ref));
                        enteredRefresh.countDown();
                        try { releaseRefresh.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        return Optional.of(new UserSessionStore.RefreshedToken(
                                ("ghu_new_" + idx).toCharArray(),
                                ("ghr_new_" + idx).toCharArray(),
                                System.currentTimeMillis() + 600_000L,
                                System.currentTimeMillis() + 3_600_000L,
                                List.of("repo")));
                    }));
                    assertTrue(enteredRefresh.await(3, java.util.concurrent.TimeUnit.SECONDS));
                    var logoutFuture = pool.submit(() -> store.invalidate(sid));
                    // Wait until invalidate(sid) has removed the session from the map and is parked on entry.refreshLock
                    long waitDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                    while (store.get(sid).isPresent() && System.nanoTime() < waitDeadline) {
                        Thread.onSpinWait();
                    }
                    assertTrue(store.get(sid).isEmpty(), "invalidate must remove session while refresh is in-flight");
                    releaseRefresh.countDown();
                    assertTrue(logoutFuture.get(5, java.util.concurrent.TimeUnit.SECONDS));
                    var tok = refreshFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertTrue(tok.isEmpty(), "Logged-out session must NOT return a refreshed token");
                    assertTrue(store.resolveToken(sid, "github.com", null).isEmpty());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static GitHubRestClient.Transport loopbackTransport() {
        var http = HttpClient.newHttpClient();
        return (uri, method, headers, body, cancelled) -> {
            var builder = HttpRequest.newBuilder(uri);
            headers.forEach(builder::header);
            if ("POST".equals(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
            } else {
                builder.GET();
            }
            try {
                var resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                var flat = new LinkedHashMap<String, String>();
                resp.headers().map().forEach((k, v) -> {
                    if (!v.isEmpty()) flat.put(k, String.join(", ", v));
                });
                return new GitHubRestClient.RawResponse(resp.statusCode(), flat, resp.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(e);
            }
        };
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange ex, int status, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String parseFormParam(String body, String key) {
        return parseQueryParam(body, key);
    }

    private static String parseQueryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            if (key.equals(URLDecoder.decode(k, StandardCharsets.UTF_8))) {
                return URLDecoder.decode(v, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String extractDataString(String json, String field) {
        var root = GraphJson.object(GraphJson.read(json, GraphJson.Limits.defaults()));
        var data = GraphJson.object(root.get("data"));
        return String.valueOf(data.get(field));
    }
}
