package com.evolution.backend.auth;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.analysis.RepositoryConnections;
import com.evolution.backend.github.*;
import com.evolution.backend.query.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end offline tests for Zero-Env Multi-User Accounts (PBKDF2 210k), RBAC,
 * GitHub account linking/unlinking, Encrypted In-App OAuth hot-reload, and 1-Click GitHub App Manifest setup.
 */
class MultiUserAccountsAndSetupTest {

    @Test
    void localRegistrationLoginRbacLinkingAndUnlinkingWithZeroEnv(@TempDir Path tempDir) throws Exception {
        var ghMock = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        ghMock.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (path.equals("/user") && "Bearer pat_alice_link".equals(auth)) {
                ex.getResponseHeaders().set("X-OAuth-Scopes", "read:user, repo");
                respondJson(ex, 200, "{\"id\":5001,\"login\":\"alice-gh\",\"name\":\"Alice GH\","
                        + "\"avatar_url\":\"https://avatars.githubusercontent.com/u/5001\",\"email\":\"alice@acme.dev\"}");
                return;
            }
            if (path.equals("/user") && "Bearer pat_carol_direct".equals(auth)) {
                ex.getResponseHeaders().set("X-OAuth-Scopes", "read:user, repo");
                respondJson(ex, 200, "{\"id\":6002,\"login\":\"carol-gh\",\"name\":\"Carol Direct\","
                        + "\"avatar_url\":\"https://avatars.githubusercontent.com/u/6002\",\"email\":\"carol@acme.dev\"}");
                return;
            }
            if (path.equals("/user/orgs")) {
                respondJson(ex, 200, "[{\"id\":10,\"login\":\"acme-eng\"}]");
                return;
            }
            respondJson(ex, 404, "{\"message\":\"Not Found\"}");
        });
        ghMock.start();
        URI mockUri = URI.create("http://127.0.0.1:" + ghMock.getAddress().getPort());

        Path authDir = tempDir.resolve("auth");
        var cfgStore = new GitHubOAuthConfigStore(authDir, k -> null);
        assertTrue(Files.isRegularFile(cfgStore.masterKeyFile()), "Auto-generated master.key must exist without env vars");

        var graphs = new InMemoryGraphStore();
        var reports = new PublishedReports();
        try (var accountStore = new UserAccountStore(authDir.resolve("accounts.db"));
             var sessionStore = new UserSessionStore(Optional.of(tempDir.resolve("auth-sessions.enc")),
                     Optional.of(cfgStore.masterKeyBytes()));
             var broker = new GitHubTokenBroker(k -> null, h -> Optional.empty());
             var jobs = new AnalysisJobs(List.of(), graphs, reports, AnalysisJobs.Limits.defaults())) {
            broker.setMultiUserMode(true);
            var oauthCfg = new GitHubOAuthService.Config(
                    true, "", "", "github.com", GitHubOAuthService.DEFAULT_SCOPES,
                    false, Optional.empty(), Optional.of(mockUri), Optional.of(mockUri));
            var webhooks = new GitHubWebhookHandler(Optional.empty(), false);
            var transport = loopbackTransport();
            var rest = new GitHubRestClient(broker, transport, mockUri);
            var oauth = new GitHubOAuthService(oauthCfg, sessionStore, transport, cfgStore, accountStore)
                    .withWebhookSecretListener(webhooks::updateSecret);
            var authApi = new AuthHttpApi(oauth, rest, ApiLimits.defaults(), webhooks::isConfigured);
            var connections = new RepositoryConnections(jobs, Path.of(System.getProperty("java.home")), 21,
                    tempDir.resolve("m2"), tempDir.resolve("store"), Optional.empty(), "git", new GitHostPolicy());
            var ghApi = new GitHubHttpApi(broker, rest, connections, null, null, ApiLimits.defaults())
                    .withEnterpriseServices(authApi, null, null, webhooks, graphs, reports);
            var api = ArchitectureQueryApi.withAuth(
                    ArchitectureQueryApi.withGitHub(
                            new ArchitectureQueryApi(graphs, reports, ApiLimits.defaults(), jobs, connections),
                            ghApi),
                    authApi);

            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                String root = server.uri().toString();
                var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

                // 0. /auth/config advertises localAccountsEnabled=true, hasRegisteredUsers=false, canConfigureGitHubSetup=true
                var cfg0 = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/config")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, cfg0.statusCode());
                assertTrue(cfg0.body().contains("\"localAccountsEnabled\":true"));
                assertTrue(cfg0.body().contains("\"hasRegisteredUsers\":false"));
                assertTrue(cfg0.body().contains("\"canConfigureGitHubSetup\":true"));
                assertTrue(cfg0.body().contains("\"manifestFlowSupported\":true"));

                // 1. Register first user -> automatically ADMIN + HttpOnly cookie + csrfToken
                var reg1 = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "alice@acme.dev",
                                        "username", "alice",
                                        "displayName", "Alice Lead",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, reg1.statusCode(), reg1.body());
                assertTrue(reg1.body().contains("\"role\":\"ADMIN\""));
                assertTrue(reg1.body().contains("\"displayName\":\"Alice Lead\""));
                assertFalse(reg1.body().contains("StrongPass!123"));
                String aliceCookie = reg1.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                var aliceRegData = extractData(reg1.body());
                String aliceCsrf = String.valueOf(aliceRegData.get("csrfToken"));
                String aliceUserId = String.valueOf(GraphJson.object(aliceRegData.get("user")).get("userId"));

                // Duplicate email/username rejected 409
                var dupReg = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "ALICE@acme.dev",
                                        "username", "alice2",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(409, dupReg.statusCode());

                // 2. Register second user -> automatically MEMBER
                var reg2 = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "bob@acme.dev",
                                        "username", "bob",
                                        "password", "BobSecret!456"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, reg2.statusCode(), reg2.body());
                assertTrue(reg2.body().contains("\"role\":\"MEMBER\""));
                String bobCookie = reg2.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                var bobRegData = extractData(reg2.body());
                String bobCsrf = String.valueOf(bobRegData.get("csrfToken"));
                String bobUserId = String.valueOf(GraphJson.object(bobRegData.get("user")).get("userId"));

                // 3. Wrong password & unknown account both return 401 INVALID_CREDENTIALS
                for (var badCreds : List.of(
                        Map.of("identifier", "bob", "password", "WrongPass!999"),
                        Map.of("identifier", "nobody@acme.dev", "password", "WrongPass!999"))) {
                    var badLogin = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/login"))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(badCreds)))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(401, badLogin.statusCode());
                    var badErr = GraphJson.object(GraphJson.object(GraphJson.read(badLogin.body(), GraphJson.Limits.defaults())).get("error"));
                    assertEquals("INVALID_CREDENTIALS", badErr.get("code"), badLogin.body());
                }

                // 4. Profile update (displayName + password change) requires CSRF and works end-to-end
                var noCsrfProfile = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/profile"))
                                .header("Cookie", bobCookie)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"displayName\":\"Bob Architect\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, noCsrfProfile.statusCode());

                var okProfile = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/profile"))
                                .header("Cookie", bobCookie)
                                .header(AuthHttpApi.CSRF_HEADER, bobCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "displayName", "Bob Architect",
                                        "currentPassword", "BobSecret!456",
                                        "newPassword", "BobNewPass!789"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, okProfile.statusCode(), okProfile.body());
                assertTrue(okProfile.body().contains("\"displayName\":\"Bob Architect\""));

                // Login with new password succeeds; old password fails
                assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "identifier", "bob@acme.dev", "password", "BobSecret!456"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "identifier", "bob@acme.dev", "password", "BobNewPass!789"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());

                // 5. RBAC: Bob (MEMBER) cannot list users or modify roles (403 ADMIN_REQUIRED)
                assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/users"))
                                .header("Cookie", bobCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());

                // Alice (ADMIN) cannot demote or disable the last active admin (409 LAST_ADMIN_PROTECTED)
                var demoteSelf = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/users/" + aliceUserId + "/role"))
                                .header("Cookie", aliceCookie)
                                .header(AuthHttpApi.CSRF_HEADER, aliceCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"role\":\"MEMBER\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(409, demoteSelf.statusCode(), demoteSelf.body());
                assertTrue(demoteSelf.body().contains("LAST_ADMIN_PROTECTED"));

                // Alice promotes Bob to ADMIN, then disables Bob -> Bob's active session is immediately revoked & login rejected 403!
                var promoteBob = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/users/" + bobUserId + "/role"))
                                .header("Cookie", aliceCookie)
                                .header(AuthHttpApi.CSRF_HEADER, aliceCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"role\":\"ADMIN\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, promoteBob.statusCode(), promoteBob.body());
                assertTrue(promoteBob.body().contains("\"role\":\"ADMIN\""));

                var disableBob = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/users/" + bobUserId + "/role"))
                                .header("Cookie", aliceCookie)
                                .header(AuthHttpApi.CSRF_HEADER, aliceCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"role\":\"MEMBER\",\"disabled\":true}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, disableBob.statusCode());
                assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/orgs"))
                                .header("Cookie", bobCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                var disabledLogin = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "identifier", "bob", "password", "BobNewPass!789"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, disabledLogin.statusCode());
                assertTrue(disabledLogin.body().contains("ACCOUNT_DISABLED"));

                // 6. Multi-session GitHub link + unlink: open Session 2 for Alice, link GitHub on BOTH sessions,
                // then unlink from Session 1 and verify BOTH Session 1 and Session 2 immediately lose GitHub tokens!
                var aliceLogin2 = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "identifier", "alice", "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, aliceLogin2.statusCode());
                String aliceCookie2 = aliceLogin2.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

                var linkPat = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session"))
                                .header("Cookie", aliceCookie)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"pat_alice_link\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, linkPat.statusCode(), linkPat.body());
                assertTrue(linkPat.body().contains("\"githubLinked\":true"));
                assertTrue(linkPat.body().contains("\"githubLogin\":\"alice-gh\""));
                assertTrue(linkPat.body().contains("\"role\":\"ADMIN\""));

                var linkPat2 = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session"))
                                .header("Cookie", aliceCookie2)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"pat_alice_link\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, linkPat2.statusCode(), linkPat2.body());

                var aliceOrgs = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/orgs"))
                                .header("Cookie", aliceCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, aliceOrgs.statusCode());
                assertTrue(aliceOrgs.body().contains("acme-eng"));

                var aliceOrgs2Before = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/orgs"))
                                .header("Cookie", aliceCookie2).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, aliceOrgs2Before.statusCode());
                assertTrue(aliceOrgs2Before.body().contains("acme-eng"));

                // Unlink GitHub from Session 1 keeps Alice's local sessions signed in while revoking GitHub tokens across ALL sessions!
                var unlinkResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/unlink"))
                                .header("Cookie", aliceCookie)
                                .header(AuthHttpApi.CSRF_HEADER, aliceCsrf)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, unlinkResp.statusCode(), unlinkResp.body());
                assertTrue(unlinkResp.body().contains("\"unlinked\":true"));
                assertTrue(unlinkResp.body().contains("\"githubLinked\":false"));

                var aliceMeAfterUnlink = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/me"))
                                .header("Cookie", aliceCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, aliceMeAfterUnlink.statusCode());
                assertTrue(aliceMeAfterUnlink.body().contains("\"authenticated\":true"));
                assertTrue(aliceMeAfterUnlink.body().contains("\"githubLinked\":false"));

                // Session 2 MUST ALSO immediately lose its stored GitHub token (401 on /orgs) while staying signed in locally!
                var aliceOrgs2After = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/orgs"))
                                .header("Cookie", aliceCookie2).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(401, aliceOrgs2After.statusCode(),
                        "Session 2 must lose GitHub token immediately after Session 1 unlinks: " + aliceOrgs2After.body());
                var aliceMe2After = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/me"))
                                .header("Cookie", aliceCookie2).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, aliceMe2After.statusCode());
                assertTrue(aliceMe2After.body().contains("\"authenticated\":true"));
                assertTrue(aliceMe2After.body().contains("\"githubLinked\":false"));

                // 7. Direct GitHub sign-in auto-provisions Carol (password-less!) into the unified UserAccountStore directory!
                var carolDirect = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"pat_carol_direct\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, carolDirect.statusCode(), carolDirect.body());
                assertTrue(carolDirect.body().contains("\"role\":\"MEMBER\""));
                assertTrue(carolDirect.body().contains("\"githubLinked\":true"));
                String carolCookie = carolDirect.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                String carolCsrf = String.valueOf(extractData(carolDirect.body()).get("csrfToken"));

                // P2 #6: Password-less GitHub-only user CANNOT unlink GitHub until setting a local password (409 PASSWORD_REQUIRED_BEFORE_UNLINK)!
                var carolUnlinkBlocked = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/unlink"))
                                .header("Cookie", carolCookie)
                                .header(AuthHttpApi.CSRF_HEADER, carolCsrf)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(409, carolUnlinkBlocked.statusCode(), carolUnlinkBlocked.body());
                var carolErr = GraphJson.object(GraphJson.object(GraphJson.read(carolUnlinkBlocked.body(), GraphJson.Limits.defaults())).get("error"));
                assertEquals("PASSWORD_REQUIRED_BEFORE_UNLINK", carolErr.get("code"), carolUnlinkBlocked.body());

                // Once Carol sets a local password in Profile, unlink succeeds cleanly!
                var carolSetPw = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/profile"))
                                .header("Cookie", carolCookie)
                                .header(AuthHttpApi.CSRF_HEADER, carolCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"newPassword\":\"CarolLocal!999\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, carolSetPw.statusCode(), carolSetPw.body());
                var carolUnlinkOk = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/unlink"))
                                .header("Cookie", carolCookie)
                                .header(AuthHttpApi.CSRF_HEADER, carolCsrf)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, carolUnlinkOk.statusCode(), carolUnlinkOk.body());
                // Re-link Carol's GitHub so userList assertion below still verifies githubLogin="carol-gh"
                var carolRelink = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session"))
                                .header("Cookie", carolCookie)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"pat_carol_direct\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, carolRelink.statusCode());

                var userList = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/users"))
                                .header("Cookie", aliceCookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, userList.statusCode());
                assertTrue(userList.body().contains("\"login\":\"alice\""));
                assertTrue(userList.body().contains("\"login\":\"bob\""));
                assertTrue(userList.body().contains("\"githubLogin\":\"carol-gh\""));
                assertFalse(userList.body().contains("password"), "Zero password hashes/salts in /users response");
            }
        } finally {
            ghMock.stop(0);
        }
    }

    @Test
    void inAppEncryptedOAuthHotReloadAndOneClickAppManifestFlow(@TempDir Path tempDir) throws Exception {
        var convertedCode = new AtomicReference<String>();
        var ghMock = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        ghMock.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if ("POST".equals(ex.getRequestMethod()) && path.startsWith("/app-manifests/") && path.endsWith("/conversions")) {
                String code = path.substring("/app-manifests/".length(), path.length() - "/conversions".length());
                convertedCode.set(code);
                respondJson(ex, 201, "{\"id\":98765,\"slug\":\"evo-workbench-app\","
                        + "\"client_id\":\"Iv23liManifestClient\","
                        + "\"client_secret\":\"manifest_super_secret_xyz\","
                        + "\"webhook_secret\":\"whsec_manifest_hmac_777\","
                        + "\"pem\":\"-----BEGIN RSA PRIVATE KEY-----\\nFAKE\\n-----END RSA PRIVATE KEY-----\"}");
                return;
            }
            respondJson(ex, 404, "{\"message\":\"Not Found\"}");
        });
        ghMock.start();
        URI mockUri = URI.create("http://127.0.0.1:" + ghMock.getAddress().getPort());

        Path authDir = tempDir.resolve("auth");
        var cfgStore = new GitHubOAuthConfigStore(authDir, k -> null);
        var transport = loopbackTransport();

        var graphs = new InMemoryGraphStore();
        var reports = new PublishedReports();
        try (var accountStore = new UserAccountStore(authDir.resolve("accounts.db"));
             var sessionStore = new UserSessionStore(Optional.of(tempDir.resolve("auth-sessions.enc")),
                     Optional.of(cfgStore.masterKeyBytes()));
             var broker = new GitHubTokenBroker(k -> null, h -> Optional.empty());
             var jobs = new AnalysisJobs(List.of(), graphs, reports, AnalysisJobs.Limits.defaults())) {
            broker.setMultiUserMode(true);
            var baseCfg = new GitHubOAuthService.Config(
                    true, "", "", "github.com", GitHubOAuthService.DEFAULT_SCOPES,
                    false, Optional.empty(), Optional.of(mockUri), Optional.of(mockUri));
            var webhooks = new GitHubWebhookHandler(Optional.empty(), false);
            var rest = new GitHubRestClient(broker, transport, mockUri);
            var oauth = new GitHubOAuthService(baseCfg, sessionStore, transport, cfgStore, accountStore)
                    .withWebhookSecretListener(webhooks::updateSecret);
            var authApi = new AuthHttpApi(oauth, rest, ApiLimits.defaults(), webhooks::isConfigured);
            var connections = new RepositoryConnections(jobs, Path.of(System.getProperty("java.home")), 21,
                    tempDir.resolve("m2"), tempDir.resolve("store"), Optional.empty(), "git", new GitHostPolicy());
            var ghApi = new GitHubHttpApi(broker, rest, connections, null, null, ApiLimits.defaults())
                    .withEnterpriseServices(authApi, null, null, webhooks, graphs, reports);
            var api = ArchitectureQueryApi.withAuth(
                    ArchitectureQueryApi.withGitHub(
                            new ArchitectureQueryApi(graphs, reports, ApiLimits.defaults(), jobs, connections),
                            ghApi),
                    authApi);

            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                String root = server.uri().toString();
                var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

                // Initially unconfigured (source=NONE, webhook 503, loopback flags reported honestly)
                var setup0 = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/setup")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, setup0.statusCode());
                assertTrue(setup0.body().contains("\"configured\":false"));
                assertTrue(setup0.body().contains("\"source\":\"NONE\""));
                assertTrue(setup0.body().contains("\"canConfigure\":true"));
                assertTrue(setup0.body().contains("\"isLoopbackHost\":true"));
                assertTrue(setup0.body().contains("\"webhookReachable\":false"));
                assertTrue(setup0.body().contains("\"publicBaseUrl\":\"" + root + "\""));
                assertFalse(webhooks.isConfigured());

                // 1. Register Admin + Member
                var regAdmin = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "admin@acme.dev", "username", "admin", "password", "AdminPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                String adminCookie = regAdmin.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                String adminCsrf = String.valueOf(extractData(regAdmin.body()).get("csrfToken"));

                var regMember = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "member@acme.dev", "username", "member", "password", "MemberPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                String memberCookie = regMember.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                String memberCsrf = String.valueOf(extractData(regMember.body()).get("csrfToken"));

                // Non-admin & unauthenticated CANNOT save setup once users exist!
                assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/setup"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"clientId\":\"x\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/setup"))
                                .header("Cookie", memberCookie)
                                .header(AuthHttpApi.CSRF_HEADER, memberCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"clientId\":\"x\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());

                // 2. Admin saves in-app OAuth + Webhook credentials -> encrypted on disk & hot-reloaded LIVE!
                var saveResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/setup"))
                                .header("Cookie", adminCookie)
                                .header(AuthHttpApi.CSRF_HEADER, adminCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "clientId", "Ov23liManualClient",
                                        "clientSecret", "manual_secret_value_abc",
                                        "webhookSecret", "whsec_manual_live_999",
                                        "deviceFlowEnabled", true))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, saveResp.statusCode(), saveResp.body());
                assertTrue(saveResp.body().contains("\"configured\":true"));
                assertTrue(saveResp.body().contains("\"source\":\"IN_APP\""));
                assertTrue(saveResp.body().contains("\"hasClientSecret\":true"));
                assertTrue(saveResp.body().contains("\"hasWebhookSecret\":true"));
                assertFalse(saveResp.body().contains("manual_secret_value_abc"), "clientSecret MUST NEVER leak in response");
                assertFalse(saveResp.body().contains("whsec_manual_live_999"), "webhookSecret MUST NEVER leak in response");

                // Zero plaintext secrets on disk!
                String encDisk = Files.readString(cfgStore.configFile(), StandardCharsets.UTF_8);
                assertTrue(encDisk.contains(GitHubOAuthConfigStore.SCHEMA));
                assertFalse(encDisk.contains("manual_secret_value_abc"));
                assertFalse(encDisk.contains("whsec_manual_live_999"));

                // Live hot-reload activated OAuth login & Webhook HMAC immediately without restart!
                var loginRedirect = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/login"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(302, loginRedirect.statusCode());
                assertTrue(loginRedirect.headers().firstValue("Location").orElseThrow().contains("client_id=Ov23liManualClient"));

                byte[] pingBody = "{\"zen\":\"hot reloaded\"}".getBytes(StandardCharsets.UTF_8);
                String pingSig = GitHubWebhookHandler.computeSignature("whsec_manual_live_999".getBytes(StandardCharsets.UTF_8), pingBody);
                var pingResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/webhooks"))
                                .header("Content-Type", "application/json")
                                .header("X-GitHub-Event", "ping")
                                .header("X-GitHub-Delivery", "hot-del-1")
                                .header("X-Hub-Signature-256", pingSig)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(pingBody))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, pingResp.statusCode(), pingResp.body());

                // 3. 1-Click GitHub App Manifest Flow: prepare -> bad state rejected 400 -> valid callback converts & hot-reloads!
                var prepResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/manifest/prepare"))
                                .header("Cookie", adminCookie)
                                .header(AuthHttpApi.CSRF_HEADER, adminCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"appName\":\"Acme Evo App\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, prepResp.statusCode(), prepResp.body());
                var prepData = extractData(prepResp.body());
                String state = String.valueOf(prepData.get("state"));
                String actionUrl = String.valueOf(prepData.get("actionUrl"));
                String manifestJson = String.valueOf(prepData.get("manifestJson"));
                assertTrue(actionUrl.contains("/settings/apps/new?state=" + state));
                assertTrue(manifestJson.contains("\"request_oauth_on_install\":true"));
                assertTrue(manifestJson.contains("/api/v1/auth/github/manifest/callback"));
                // On loopback (http://127.0.0.1), hook_attributes.active MUST be false so GitHub accepts local callback URLs
                var manifestObj = GraphJson.object(GraphJson.read(manifestJson, GraphJson.Limits.defaults()));
                var hookAttrs = GraphJson.object(manifestObj.get("hook_attributes"));
                assertEquals(Boolean.FALSE, hookAttrs.get("active"), "Loopback manifest must set hook_attributes.active=false");
                assertTrue(String.valueOf(hookAttrs.get("url")).endsWith("/api/v1/github/webhooks"));

                // Preparing a manifest for a public HTTPS origin sets hook_attributes.active=true
                var httpsPrepResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/manifest/prepare"))
                                .header("Cookie", adminCookie)
                                .header(AuthHttpApi.CSRF_HEADER, adminCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "appName", "Acme Prod App",
                                        "publicBaseUrl", "https://evo.acme.dev"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, httpsPrepResp.statusCode(), httpsPrepResp.body());
                var httpsManifestObj = GraphJson.object(GraphJson.read(
                        String.valueOf(extractData(httpsPrepResp.body()).get("manifestJson")), GraphJson.Limits.defaults()));
                var httpsHooks = GraphJson.object(httpsManifestObj.get("hook_attributes"));
                assertEquals(Boolean.TRUE, httpsHooks.get("active"), "Public HTTPS manifest must set hook_attributes.active=true");
                assertEquals("https://evo.acme.dev/api/v1/github/webhooks", httpsHooks.get("url"));

                // Bad state rejected 400
                var badStateCb = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/auth/github/manifest/callback?code=temp_code_99&state=forged_state")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(400, badStateCb.statusCode());

                // Valid callback converts code -> saves encrypted -> hot-reloads -> 302 redirect!
                var goodCb = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/auth/github/manifest/callback?code=temp_code_99&state=" + state)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(302, goodCb.statusCode(), goodCb.body());
                assertEquals("/?github_setup=manifest_connected", goodCb.headers().firstValue("Location").orElse(""));
                assertEquals("temp_code_99", convertedCode.get());

                // Replaying single-use manifest state fails 400
                assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/auth/github/manifest/callback?code=temp_code_99&state=" + state)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());

                // Verify live hot-reloaded manifest credentials & restart persistence!
                var setupAfter = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/setup")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(setupAfter.body().contains("\"clientId\":\"Iv23liManifestClient\""));
                assertTrue(setupAfter.body().contains("\"appSlug\":\"evo-workbench-app\""));
                assertFalse(setupAfter.body().contains("manifest_super_secret_xyz"));
                assertFalse(setupAfter.body().contains("whsec_manifest_hmac_777"));

                // Simulate server restart: new GitHubOAuthConfigStore + GitHubOAuthService reloads saved config cleanly!
                var reloadedStore = new GitHubOAuthConfigStore(authDir, k -> null);
                var reloadedOAuth = new GitHubOAuthService(baseCfg, sessionStore, transport, reloadedStore, accountStore);
                assertEquals("Iv23liManifestClient", reloadedOAuth.config().clientId());
                assertTrue(reloadedOAuth.config().oauthWebEnabled());
                assertTrue(reloadedOAuth.config().githubAppEnabled());

                // 4. DELETE /api/v1/auth/github/setup clears saved config and reverts live
                var delResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/setup"))
                                .header("Cookie", adminCookie)
                                .header(AuthHttpApi.CSRF_HEADER, adminCsrf)
                                .DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, delResp.statusCode(), delResp.body());
                assertTrue(delResp.body().contains("\"configured\":false"));
                assertFalse(oauth.config().oauthWebEnabled());
                assertFalse(webhooks.isConfigured());
            }
        } finally {
            ghMock.stop(0);
        }
    }

    @Test
    void streamlinedEmailOnlySignUpAndHttpsDeployReadiness(@TempDir Path tempDir) throws Exception {
        Path authDir = tempDir.resolve("auth");
        var cfgStore = new GitHubOAuthConfigStore(authDir, k -> null);
        var graphs = new InMemoryGraphStore();
        var reports = new PublishedReports();
        try (var accountStore = new UserAccountStore(authDir.resolve("accounts.db"));
             var sessionStore = new UserSessionStore(Optional.empty(), Optional.of(cfgStore.masterKeyBytes()));
             var broker = new GitHubTokenBroker(k -> null, h -> Optional.empty());
             var jobs = new AnalysisJobs(List.of(), graphs, reports, AnalysisJobs.Limits.defaults())) {
            var baseCfg = new GitHubOAuthService.Config(
                    true, "", "", "github.com", GitHubOAuthService.DEFAULT_SCOPES,
                    false, Optional.empty(), Optional.empty(), Optional.empty());
            var transport = loopbackTransport();
            var rest = new GitHubRestClient(broker, transport, URI.create("http://127.0.0.1:9"));
            var oauth = new GitHubOAuthService(baseCfg, sessionStore, transport, cfgStore, accountStore);
            var authApi = new AuthHttpApi(oauth, rest, ApiLimits.defaults(), () -> false);
            var connections = new RepositoryConnections(jobs, Path.of(System.getProperty("java.home")), 21,
                    tempDir.resolve("m2"), tempDir.resolve("store"), Optional.empty(), "git", new GitHostPolicy());
            var api = ArchitectureQueryApi.withAuth(
                    new ArchitectureQueryApi(graphs, reports, ApiLimits.defaults(), jobs, connections), authApi);

            var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

            // 1. Default loopback server (trustProxyHeaders = false): streamlined email+password sign-up & fail-closed proxy headers
            try (var server = new ArchitectureHttpServer(api, "127.0.0.1", 0, 15_000L, false)) {
                server.start();
                String root = server.uri().toString();

                // Email + password only (no username, no displayName) -> derives lowercase handle & ADMIN role
                var r1 = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "Alice.Lead+1@acme.dev",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, r1.statusCode(), r1.body());
                var u1 = GraphJson.object(extractData(r1.body()).get("user"));
                assertEquals("alice-lead-1", u1.get("login"));
                assertEquals("alice-lead-1", u1.get("displayName"));
                assertEquals("ADMIN", u1.get("role"));
                String r1Cookie = r1.headers().firstValue("Set-Cookie").orElseThrow();
                assertFalse(r1Cookie.contains("; Secure"), "HTTP loopback cookie must not force Secure");

                // Collision on derived prefix appends -2, -3 sequentially
                var r2 = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "alice.lead+1@other.dev",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, r2.statusCode(), r2.body());
                assertEquals("alice-lead-1-2", GraphJson.object(extractData(r2.body()).get("user")).get("login"));

                var r3 = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "alice.lead+1@third.dev",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, r3.statusCode(), r3.body());
                assertEquals("alice-lead-1-3", GraphJson.object(extractData(r3.body()).get("user")).get("login"));

                // Blank username & blank displayName also auto-derive cleanly (-4)
                var rBlank = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "alice.lead+1@fourth.dev",
                                        "username", "   ",
                                        "displayName", "   ",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, rBlank.statusCode(), rBlank.body());
                var uBlank = GraphJson.object(extractData(rBlank.body()).get("user"));
                assertEquals("alice-lead-1-4", uBlank.get("login"));
                assertEquals("alice-lead-1-4", uBlank.get("displayName"));

                // Backward compatibility: explicit username works, explicit duplicate fails 409, reserved "local" fails 400
                var rExplicit = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "custom@acme.dev",
                                        "username", "Custom_Handle",
                                        "displayName", "Custom User",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, rExplicit.statusCode(), rExplicit.body());
                assertEquals("Custom_Handle", GraphJson.object(extractData(rExplicit.body()).get("user")).get("login"));
                assertEquals(409, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "custom2@acme.dev",
                                        "username", "custom_handle",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "reserved@acme.dev",
                                        "username", "local",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());

                // Reserved "local" email prefix & 1-char prefix both derive valid non-reserved handles (length 2..40)
                var rLocal = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "local@acme.dev",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, rLocal.statusCode(), rLocal.body());
                String localDerived = String.valueOf(GraphJson.object(extractData(rLocal.body()).get("user")).get("login"));
                assertNotEquals("local", localDerived);
                assertEquals("user-local", localDerived);

                var rShort = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "email", "x@acme.dev",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, rShort.statusCode(), rShort.body());
                assertEquals("user-x", GraphJson.object(extractData(rShort.body()).get("user")).get("login"));

                // Login works with both email and auto-derived handle
                var loginDerived = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "identifier", "alice-lead-1-2",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, loginDerived.statusCode(), loginDerived.body());

                // Fail-closed proxy headers: X-Forwarded-* MUST be ignored when trustProxyHeaders=false
                var spoofCfg = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/config"))
                                .header("X-Forwarded-Proto", "https")
                                .header("X-Forwarded-Host", "evil.spoof.test")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, spoofCfg.statusCode(), spoofCfg.body());
                var spoofData = extractData(spoofCfg.body());
                assertEquals(root, spoofData.get("publicBaseUrl"));
                assertEquals(Boolean.TRUE, spoofData.get("isLoopbackHost"));
                assertEquals(Boolean.FALSE, spoofData.get("webhookReachable"));

                var spoofOrigin = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/config"))
                                .header("X-Forwarded-Proto", "https")
                                .header("X-Forwarded-Host", "evil.spoof.test")
                                .header("Origin", "https://evil.spoof.test")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, spoofOrigin.statusCode(), "Spoofed proxy Origin must be rejected when trustProxyHeaders=false");
            }

            // 2. Reverse-proxy opted-in server (trustProxyHeaders = true): honors validated X-Forwarded-Proto/Host + Secure cookie
            try (var proxyServer = new ArchitectureHttpServer(api, "127.0.0.1", 0, 15_000L, true)) {
                proxyServer.start();
                String root = proxyServer.uri().toString();

                var proxyCfg = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/config"))
                                .header("X-Forwarded-Proto", "https")
                                .header("X-Forwarded-Host", "evo.proxy.dev:443")
                                .header("Origin", "https://evo.proxy.dev")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, proxyCfg.statusCode(), proxyCfg.body());
                var proxyData = extractData(proxyCfg.body());
                assertEquals("https://evo.proxy.dev", proxyData.get("publicBaseUrl"));
                assertEquals("https://evo.proxy.dev/api/v1/auth/github/callback", proxyData.get("callbackUrl"));
                assertEquals("https://evo.proxy.dev/api/v1/github/webhooks", proxyData.get("webhookUrl"));
                assertEquals(Boolean.FALSE, proxyData.get("isLoopbackHost"));
                assertEquals(Boolean.TRUE, proxyData.get("webhookReachable"));

                var proxyLogin = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .header("X-Forwarded-Proto", "https")
                                .header("X-Forwarded-Host", "evo.proxy.dev:443")
                                .header("Origin", "https://evo.proxy.dev")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "identifier", "alice-lead-1",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, proxyLogin.statusCode(), proxyLogin.body());
                String proxySetCookie = proxyLogin.headers().firstValue("Set-Cookie").orElseThrow();
                assertTrue(proxySetCookie.contains("; Secure"),
                        "Proxied HTTPS login must emit ; Secure on evo_session cookie");
                String proxyCookie = proxySetCookie.split(";", 2)[0];
                String proxyCsrf = String.valueOf(extractData(proxyLogin.body()).get("csrfToken"));

                // Proxied HTTPS manifest prepare sets hook_attributes.active=true automatically!
                var proxyManifest = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/manifest/prepare"))
                                .header("Cookie", proxyCookie)
                                .header(AuthHttpApi.CSRF_HEADER, proxyCsrf)
                                .header("Content-Type", "application/json")
                                .header("X-Forwarded-Proto", "https")
                                .header("X-Forwarded-Host", "evo.proxy.dev")
                                .header("Origin", "https://evo.proxy.dev")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, proxyManifest.statusCode(), proxyManifest.body());
                var proxyManifestObj = GraphJson.object(GraphJson.read(
                        String.valueOf(extractData(proxyManifest.body()).get("manifestJson")), GraphJson.Limits.defaults()));
                assertEquals(Boolean.TRUE, GraphJson.object(proxyManifestObj.get("hook_attributes")).get("active"));
                assertEquals("https://evo.proxy.dev/api/v1/github/webhooks",
                        GraphJson.object(proxyManifestObj.get("hook_attributes")).get("url"));

                // Verify RequestContext serverOrigin also drives 1-arg cookie helpers used by GitHubHttpApi
                String ctxSetCookie = RequestContext.runWith(null, "https://evo.proxy.dev",
                        () -> authApi.sessionCookieHeader("sid_test_1"));
                String ctxClearCookie = RequestContext.runWith(null, "https://evo.proxy.dev",
                        authApi::clearSessionCookieHeader);
                assertTrue(ctxSetCookie.contains("; Secure"), ctxSetCookie);
                assertTrue(ctxClearCookie.contains("; Secure"), ctxClearCookie);
            }

            // 3. Explicit EVO_PUBLIC_BASE_URL=https://evo.acme.dev (even with trustProxyHeaders=false):
            //    drives callbackUrl, webhookUrl, OAuth authorize redirect_uri, flags, Secure cookie, and Origin check cleanly!
            var httpsCfg = new GitHubOAuthService.Config(
                    true, "Ov23liProdClient", "prod_secret_val", "github.com", GitHubOAuthService.DEFAULT_SCOPES,
                    false, Optional.of("https://evo.acme.dev///"), Optional.empty(), Optional.empty());
            var httpsOAuth = new GitHubOAuthService(httpsCfg, sessionStore, transport, cfgStore, accountStore);
            var httpsAuthApi = new AuthHttpApi(httpsOAuth, rest, ApiLimits.defaults(), () -> false);
            var httpsApi = ArchitectureQueryApi.withAuth(
                    new ArchitectureQueryApi(graphs, reports, ApiLimits.defaults(), jobs, connections), httpsAuthApi);

            try (var httpsServer = new ArchitectureHttpServer(httpsApi, "0.0.0.0", 0, 15_000L, true)) {
                httpsServer.start();
                assertEquals("0.0.0.0", httpsServer.bindHost());
                String root = httpsServer.uri().toString();
                assertTrue(root.startsWith("http://127.0.0.1:"), "0.0.0.0 bind should report clickable loopback uri: " + root);

                var cfgResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/config"))
                                .header("Origin", "https://evo.acme.dev")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, cfgResp.statusCode(), cfgResp.body());
                var data = extractData(cfgResp.body());
                assertEquals("https://evo.acme.dev", data.get("publicBaseUrl"));
                assertEquals("https://evo.acme.dev/api/v1/auth/github/callback", data.get("callbackUrl"));
                assertEquals("https://evo.acme.dev/api/v1/github/webhooks", data.get("webhookUrl"));
                assertEquals(Boolean.FALSE, data.get("isLoopbackHost"));
                assertEquals(Boolean.TRUE, data.get("webhookReachable"));

                var setupResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/setup"))
                                .header("Origin", "https://evo.acme.dev")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, setupResp.statusCode(), setupResp.body());
                var setupData = extractData(setupResp.body());
                assertEquals("https://evo.acme.dev", setupData.get("publicBaseUrl"));
                assertEquals(Boolean.FALSE, setupData.get("isLoopbackHost"));
                assertEquals(Boolean.TRUE, setupData.get("webhookReachable"));

                // OAuth Web login uses EVO_PUBLIC_BASE_URL for redirect_uri!
                var oauthStart = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/github/login"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(302, oauthStart.statusCode(), oauthStart.body());
                assertTrue(oauthStart.headers().firstValue("Location").orElseThrow()
                        .contains("redirect_uri=https%3A%2F%2Fevo.acme.dev%2Fapi%2Fv1%2Fauth%2Fgithub%2Fcallback"));

                var loginResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/login"))
                                .header("Content-Type", "application/json")
                                .header("Origin", "https://evo.acme.dev")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "identifier", "alice.lead+1@acme.dev",
                                        "password", "StrongPass!123"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, loginResp.statusCode(), loginResp.body());
                assertTrue(loginResp.headers().firstValue("Set-Cookie").orElseThrow().contains("; Secure"));

                // Foreign Origin AND spoofed X-Forwarded-Host both blocked 403 when publicBaseUrl is configured!
                var evilOrigin = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/config"))
                                .header("Origin", "https://attacker.invalid")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, evilOrigin.statusCode());

                var spoofFwdHost = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/config"))
                                .header("X-Forwarded-Host", "attacker.invalid")
                                .header("X-Forwarded-Proto", "https")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, spoofFwdHost.statusCode(), "Spoofed X-Forwarded-Host must be rejected when publicBaseUrl is pinned");
            }
        }
    }

    private static GitHubRestClient.Transport loopbackTransport() {
        var http = HttpClient.newHttpClient();
        return (uri, method, headers, body, cancelled) -> {
            var builder = HttpRequest.newBuilder(uri);
            headers.forEach(builder::header);
            builder.method(method, body == null || body.length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
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

    private static Map<String, Object> extractData(String json) {
        var root = GraphJson.object(GraphJson.read(json, GraphJson.Limits.defaults()));
        return GraphJson.object(root.get("data"));
    }
}
