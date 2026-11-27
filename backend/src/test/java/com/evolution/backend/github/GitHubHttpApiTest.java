package com.evolution.backend.github;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphStore;
import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.analysis.RepositoryConnections;
import com.evolution.backend.query.ApiLimits;
import com.evolution.backend.query.ArchitectureHttpServer;
import com.evolution.backend.query.ArchitectureQueryApi;
import com.evolution.backend.query.CapturedRepair;
import com.evolution.backend.query.FindingDiagnosis;
import com.evolution.backend.query.PublishedReports;
import com.evolution.backend.query.RepairPatch;
import com.evolution.backend.query.RepairProposalStore;
import com.evolution.backend.query.llm.LlmConfigRegistry;
import com.evolution.backend.query.repair.RepairVerificationJobs;
import com.evolution.backend.query.repair.SafePatchApplier;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GitHubHttpApiTest {

    @Test
    void exposesGitHubAiProvidersRepairEndpointsAllowlistedQueryStringsAndStaticUxAssets(@TempDir Path tempDir) throws Exception {
        var ghMock = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        ghMock.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (path.equals("/user")) {
                if (auth != null && auth.contains("ghp_validtoken1234567890")) {
                    GitHubRestClientTest.respondJson(ex, 200, "{\"login\":\"octocat\"}",
                            Map.of("X-RateLimit-Limit", "5000", "X-RateLimit-Remaining", "4999"));
                } else {
                    GitHubRestClientTest.respondJson(ex, 401, "{\"message\":\"Bad credentials\"}", Map.of());
                }
                return;
            }
            if (path.equals("/repos/acme/demo")) {
                GitHubRestClientTest.respondJson(ex, 200,
                        "{\"full_name\":\"acme/demo\",\"default_branch\":\"main\",\"private\":false,\"html_url\":\"https://github.com/acme/demo\",\"language\":\"Java\"}",
                        Map.of());
                return;
            }
            if (path.equals("/repos/acme/demo/branches")) {
                GitHubRestClientTest.respondJson(ex, 200, "[{\"name\":\"main\",\"protected\":true,\"commit\":{\"sha\":\"abc1234\"}}]", Map.of());
                return;
            }
            if (path.equals("/repos/acme/demo/tags")) {
                GitHubRestClientTest.respondJson(ex, 200, "[{\"name\":\"v1.0.0\",\"commit\":{\"sha\":\"abc1234\"}}]", Map.of());
                return;
            }
            if (path.equals("/repos/acme/demo/pulls")) {
                GitHubRestClientTest.respondJson(ex, 200,
                        "[{\"number\":7,\"title\":\"Improve layering\",\"state\":\"open\",\"draft\":false,\"html_url\":\"https://github.com/acme/demo/pull/7\","
                                + "\"user\":{\"login\":\"dev\"},\"base\":{\"ref\":\"main\",\"sha\":\"111\"},\"head\":{\"ref\":\"feat/layer\",\"sha\":\"222\"}}]",
                        Map.of());
                return;
            }
            GitHubRestClientTest.respondJson(ex, 404, "{\"message\":\"Not Found\"}", Map.of());
        });
        ghMock.start();

        GraphStore emptyGraphs = new InMemoryGraphStore();
        var reports = new PublishedReports();
        try (var broker = new GitHubTokenBroker(k -> null, h -> Optional.empty());
             var jobs = new AnalysisJobs(List.of(), emptyGraphs, reports, AnalysisJobs.Limits.defaults());
             var repairJobs = new RepairVerificationJobs()) {
            var connections = new RepositoryConnections(jobs, Path.of(System.getProperty("java.home")), 21,
                    tempDir.resolve("m2"), tempDir.resolve("store"), Optional.empty(), "git",
                    new GitHostPolicy());
            URI mockBase = URI.create("http://127.0.0.1:" + ghMock.getAddress().getPort());
            var rest = new GitHubRestClient(broker, GitHubRestClientTest.loopbackTransport(), mockBase);
            var diffSvc = new GitHubPrDiffService(rest, jobs, connections, emptyGraphs, reports, tempDir.resolve("diffs"));
            var repairStore = new RepairProposalStore(tempDir.resolve("repairs"));
            var unverifiedProposal = sampleUnverifiedProposal();
            repairStore.put(unverifiedProposal);
            var pub = new GitHubRepairPublisher(rest, broker, repairStore,
                    connections, new GitHostPolicy(), tempDir.resolve("pub"), "git");
            var ghApi = new GitHubHttpApi(broker, rest, connections, diffSvc, pub, repairJobs, ApiLimits.defaults());

            var api = ArchitectureQueryApi.withSafePatchApplier(
                    ArchitectureQueryApi.withRepairVerificationJobs(
                            ArchitectureQueryApi.withLlmRegistry(
                                    ArchitectureQueryApi.withGitHub(
                                            new ArchitectureQueryApi(emptyGraphs, reports, ApiLimits.defaults(), jobs, connections),
                                            ghApi),
                                    LlmConfigRegistry.ofAdapters(Map.of())),
                            repairJobs),
                    new SafePatchApplier(tempDir.resolve("applies")));

            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                var http = HttpClient.newHttpClient();
                String root = server.uri().toString();

                // 1. Capabilities discovery
                var capResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, capResp.statusCode());
                assertTrue(capResp.body().contains("\"githubIntegrationEnabled\":true"));
                assertTrue(capResp.body().contains("\"aiProvidersEnabled\":true"));
                assertTrue(capResp.body().contains("\"repairVerificationJobsEnabled\":true"));
                assertTrue(capResp.body().contains("\"safePatchApplyEnabled\":true"));

                // 2. Session PAT auth: valid token stored in RAM, never echoed in HTTP response!
                var authResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"ghp_validtoken1234567890\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, authResp.statusCode());
                assertTrue(authResp.body().contains("\"authenticated\":true"));
                assertTrue(authResp.body().contains("\"login\":\"octocat\""));
                assertFalse(authResp.body().contains("ghp_validtoken"), "Secret token must NEVER be echoed in response");

                // 3. Allowlisted GET /api/v1/github/refs?repo=acme/demo works; non-allowlisted query string still rejected 400
                var refsResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/refs?repo=acme%2Fdemo")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, refsResp.statusCode());
                assertTrue(refsResp.body().contains("\"defaultBranch\":\"main\""));
                assertTrue(refsResp.body().contains("\"v1.0.0\""));
                assertTrue(refsResp.body().contains("\"Improve layering\""));

                var badQueryResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1?foo=bar")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(400, badQueryResp.statusCode());

                // 4. DELETE session PAT clears RAM token
                var delResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session")).DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, delResp.statusCode());
                assertFalse(broker.hasSessionToken());

                // 5. AI providers descriptor & probe
                var aiResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/ai/providers")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, aiResp.statusCode());
                assertTrue(aiResp.body().contains("\"OPENAI_COMPAT\""));
                assertTrue(aiResp.body().contains("\"ANTHROPIC\""));
                assertTrue(aiResp.body().contains("\"GEMINI\""));

                var probeResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/ai/providers/probe"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, probeResp.statusCode());
                assertTrue(probeResp.body().contains("architecture.ai-providers-probe:v1"));

                // 6. Repair jobs list returns empty array cleanly
                var jobsResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/jobs")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, jobsResp.statusCode());
                assertTrue(jobsResp.body().contains("\"items\":[]"));

                // 7. P2-2: /api/v1/github/repairs/prepare NEVER silently defaults allowUnverified=true when setup is present
                String prepareOmittedReq = CanonicalJson.write(Map.of(
                        "proposalId", unverifiedProposal.identity().value(),
                        "setup", "gh-setup",
                        "repoCoordinate", "acme/demo"));
                var prepareOmittedResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/repairs/prepare"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(prepareOmittedReq))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(409, prepareOmittedResp.statusCode(), prepareOmittedResp.body());
                assertTrue(prepareOmittedResp.body().contains("REPAIR_UNVERIFIED_REQUIRES_OPT_IN"), prepareOmittedResp.body());

                String prepareFalseReq = CanonicalJson.write(Map.of(
                        "proposalId", unverifiedProposal.identity().value(),
                        "setup", "gh-setup",
                        "repoCoordinate", "acme/demo",
                        "allowUnverified", false));
                var prepareFalseResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/repairs/prepare"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(prepareFalseReq))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(409, prepareFalseResp.statusCode(), prepareFalseResp.body());
                assertTrue(prepareFalseResp.body().contains("REPAIR_UNVERIFIED_REQUIRES_OPT_IN"), prepareFalseResp.body());

                String prepareOptInReq = CanonicalJson.write(Map.of(
                        "proposalId", unverifiedProposal.identity().value(),
                        "setup", "gh-setup",
                        "repoCoordinate", "acme/demo",
                        "allowUnverified", true));
                var prepareOptInResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/repairs/prepare"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(prepareOptInReq))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, prepareOptInResp.statusCode(), prepareOptInResp.body());
                assertTrue(prepareOptInResp.body().contains("\"draft\":true"), prepareOptInResp.body());

                // 8. New UX static assets from Phase 2 serve cleanly with safe headers
                for (String asset : List.of("/github-hub.js", "/github-hub.css", "/repair-studio.js", "/repair-studio.css")) {
                    var assetResp = http.send(HttpRequest.newBuilder(URI.create(root + asset)).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(200, assetResp.statusCode(), "Expected static asset to serve: " + asset);
                    assertTrue(assetResp.body().length > 100);
                }
            }
        } finally {
            ghMock.stop(0);
        }
    }

    @Test
    void repairVerificationJobsSseMarkdownReportAndSafeApplyRollbackWorkEndToEnd(@TempDir Path tempDir) throws Exception {
        Path localRepo = tempDir.resolve("repo");
        Files.createDirectories(localRepo.resolve("src"));
        String relPath = "src/Sample.java";
        String before = "class Sample { int x() { return 1; } }\n";
        String after = "class Sample { int x() { return 2; } }\n";
        Files.writeString(localRepo.resolve(relPath), before, StandardCharsets.UTF_8);

        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:evo:local:sample");
        var snap = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("sample-snap"));
        var ev = GraphEvidence.capture("repository.snapshot", Map.of("seed", "sample"));
        var graph = new KnowledgeGraph(snap,
                List.of(new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), ev.identity())),
                List.of(new KnowledgeGraph.Node(repoId.value(), snap.value(), "REPOSITORY", List.of(ev.identity()))),
                List.of(), List.of(ev));
        var graphs = new InMemoryGraphStore();
        graphs.save(graph);
        var reports = new PublishedReports();

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
        var proposal = new CapturedRepair.Proposal(propId, candidate);

        var repairStore = new RepairProposalStore(tempDir.resolve("repairs"));
        repairStore.put(proposal);

        try (var jobs = new AnalysisJobs(List.of(), graphs, reports, AnalysisJobs.Limits.defaults());
             var repairJobs = new RepairVerificationJobs()) {
            var connections = new RepositoryConnections(jobs, Path.of(System.getProperty("java.home")), 21,
                    tempDir.resolve("m2"), tempDir.resolve("store"), Optional.empty(), "git",
                    new GitHostPolicy());
            connections.describeLocal("sample-setup", "sample", repoId, localRepo, Optional.empty());
            var api = ArchitectureQueryApi.withSafePatchApplier(
                    ArchitectureQueryApi.withRepairVerificationJobs(
                            ArchitectureQueryApi.withRepairStore(
                                    new ArchitectureQueryApi(graphs, reports, ApiLimits.defaults(), jobs, connections),
                                    repairStore),
                            repairJobs),
                    new SafePatchApplier(tempDir.resolve("applies")));
            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                var http = HttpClient.newHttpClient();
                String root = server.uri().toString();

                // 1. Start async repair verification job -> 202
                var startResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/" + propId.value() + "/verify"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"runIsolated\":false}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(202, startResp.statusCode(), startResp.body());
                var startObj = com.evolution.analysis.graph.GraphJson.object(
                        com.evolution.analysis.graph.GraphJson.read(startResp.body(), com.evolution.analysis.graph.GraphJson.Limits.defaults()));
                var startData = com.evolution.analysis.graph.GraphJson.object(startObj.get("data"));
                String jobId = String.valueOf(startData.get("jobId"));

                // 2. Stream SSE events until terminal completed event
                var sseResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/jobs/" + jobId + "/events"))
                                .header("Accept", "text/event-stream")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, sseResp.statusCode());
                assertTrue(sseResp.body().contains("event: stage"));
                assertTrue(sseResp.body().contains("event: completed"), sseResp.body());

                // 3. Download text/markdown report
                var mdResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/jobs/" + jobId + "/report.md"))
                                .header("Accept", "text/markdown")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, mdResp.statusCode());
                assertTrue(mdResp.headers().firstValue("Content-Type").orElse("").contains("text/markdown"));
                assertTrue(mdResp.body().contains("# Repair Verification Report") || mdResp.body().contains("Verification"));

                // 4a. P2-2 negative test: unregistered repoDir and traversal paths are rejected 403
                Path outsideDir = tempDir.resolve("evil-outside");
                Files.createDirectories(outsideDir);
                for (String badRepoDir : List.of(
                        outsideDir.toString(),
                        localRepo.resolve("..").resolve("evil-outside").toString())) {
                    String badPreviewReq = CanonicalJson.write(Map.of(
                            "repoDir", badRepoDir,
                            "mode", "DIRECT_WORKING_TREE",
                            "verificationJobId", jobId));
                    var badPrevResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/" + propId.value() + "/apply/preview"))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(badPreviewReq))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(403, badPrevResp.statusCode(), badPrevResp.body());
                    assertTrue(badPrevResp.body().contains("REPO_DIR_NOT_REGISTERED"), badPrevResp.body());
                }

                // 4b. Two-phase local apply preview -> confirm (DIRECT_WORKING_TREE) -> rollback
                String previewReq = CanonicalJson.write(Map.of(
                        "repoDir", localRepo.toString(),
                        "mode", "DIRECT_WORKING_TREE",
                        "verificationJobId", jobId));
                var prevResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/" + propId.value() + "/apply/preview"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(previewReq))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, prevResp.statusCode(), prevResp.body());
                var prevData = com.evolution.analysis.graph.GraphJson.object(
                        com.evolution.analysis.graph.GraphJson.object(
                                com.evolution.analysis.graph.GraphJson.read(prevResp.body(), com.evolution.analysis.graph.GraphJson.Limits.defaults())).get("data"));
                assertEquals(Boolean.TRUE, prevData.get("canApply"));
                String approvalToken = String.valueOf(prevData.get("approvalToken"));

                String confirmReq = CanonicalJson.write(Map.of(
                        "approvalToken", approvalToken,
                        "userConfirmed", true,
                        "mode", "DIRECT_WORKING_TREE",
                        "acknowledgeUnverified", true,
                        "repoDir", localRepo.toString(),
                        "verificationJobId", jobId));
                var confResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/" + propId.value() + "/apply/confirm"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(confirmReq))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, confResp.statusCode(), confResp.body());
                assertEquals(after, Files.readString(localRepo.resolve(relPath), StandardCharsets.UTF_8));
                var confData = com.evolution.analysis.graph.GraphJson.object(
                        com.evolution.analysis.graph.GraphJson.object(
                                com.evolution.analysis.graph.GraphJson.read(confResp.body(), com.evolution.analysis.graph.GraphJson.Limits.defaults())).get("data"));
                String applyId = String.valueOf(confData.get("applyId"));

                // P1-4 negative tests: empty body, {}, and confirmedByUser=false MUST be rejected 400 with disk untouched
                for (String badRollbackBody : List.of("", "{}", "{\"confirmedByUser\":false}")) {
                    var badRbReq = HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/applies/" + applyId + "/rollback"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(badRollbackBody))
                            .build();
                    var badRbResp = http.send(badRbReq, HttpResponse.BodyHandlers.ofString());
                    assertEquals(400, badRbResp.statusCode(), "Expected 400 for rollback payload: " + badRollbackBody + " -> " + badRbResp.body());
                    assertEquals(after, Files.readString(localRepo.resolve(relPath), StandardCharsets.UTF_8),
                            "Rollback without explicit confirmedByUser=true must leave disk untouched");
                }

                // Confirmed rollback restores exact original bytes!
                var rbResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/repairs/applies/" + applyId + "/rollback"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"confirmedByUser\":true}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, rbResp.statusCode(), rbResp.body());
                assertEquals(before, Files.readString(localRepo.resolve(relPath), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void deleteGithubAuthSessionRequiresCsrfAndCrossUserVerificationJobIdIsRejected(@TempDir Path tempDir) throws Exception {
        var ghMock = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 8);
        ghMock.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            byte[] out;
            if ("/user".equals(path) && "Bearer pat_alice_csrf".equals(auth)) {
                out = "{\"id\":101,\"login\":\"alice\",\"name\":\"Alice\",\"email\":\"alice@acme.dev\"}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
            } else if ("/user".equals(path) && "Bearer pat_bob_csrf".equals(auth)) {
                out = "{\"id\":202,\"login\":\"bob\",\"name\":\"Bob\",\"email\":\"bob@labs.dev\"}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
            } else if ("/user/orgs".equals(path)) {
                out = "[]".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
            } else {
                out = "{}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(404, out.length);
            }
            ex.getResponseBody().write(out);
            ex.close();
        });
        ghMock.start();
        URI mockUri = URI.create("http://127.0.0.1:" + ghMock.getAddress().getPort());
        var http = HttpClient.newHttpClient();
        GitHubRestClient.Transport transport = (uri, method, headers, body, cancelled) -> {
            var b = HttpRequest.newBuilder(uri);
            headers.forEach(b::header);
            b.method(method, body == null || body.length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
            try {
                var r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
                var flat = new LinkedHashMap<String, String>();
                r.headers().map().forEach((k, v) -> { if (!v.isEmpty()) flat.put(k, String.join(", ", v)); });
                return new GitHubRestClient.RawResponse(r.statusCode(), flat, r.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(e);
            }
        };

        var graphs = new InMemoryGraphStore();
        var reports = new PublishedReports();
        var proposal = sampleUnverifiedProposal();
        var repairStore = new RepairProposalStore(tempDir.resolve("repairs"));
        repairStore.put(proposal);

        try (var broker = new GitHubTokenBroker(k -> null, h -> Optional.empty());
             var sessions = new com.evolution.backend.auth.UserSessionStore();
             var jobs = new AnalysisJobs(List.of(), graphs, reports, AnalysisJobs.Limits.defaults());
             var repairJobs = new RepairVerificationJobs()) {
            broker.setMultiUserMode(true);
            var rest = new GitHubRestClient(broker, transport, mockUri);
            var oauthCfg = new com.evolution.backend.auth.GitHubOAuthService.Config(
                    true, "", "", "github.com", com.evolution.backend.auth.GitHubOAuthService.DEFAULT_SCOPES,
                    false, Optional.empty(), Optional.of(mockUri), Optional.of(mockUri));
            var oauth = new com.evolution.backend.auth.GitHubOAuthService(oauthCfg, sessions, transport);
            var authApi = new com.evolution.backend.auth.AuthHttpApi(oauth, rest, ApiLimits.defaults());
            var connections = new RepositoryConnections(jobs, Path.of(System.getProperty("java.home")), 21,
                    tempDir.resolve("m2"), tempDir.resolve("store"), Optional.empty(), "git", new GitHostPolicy());
            var publisher = new GitHubRepairPublisher(rest, broker, repairStore, connections, new GitHostPolicy(),
                    tempDir.resolve("pub"), "git");
            var ghApi = new GitHubHttpApi(broker, rest, connections, null, publisher, repairJobs, ApiLimits.defaults())
                    .withEnterpriseServices(authApi, null, null, null, graphs, reports);
            var api = ArchitectureQueryApi.withAuth(
                    ArchitectureQueryApi.withRepairVerificationJobs(
                            ArchitectureQueryApi.withGitHub(
                                    new ArchitectureQueryApi(graphs, reports, ApiLimits.defaults(), jobs, connections),
                                    ghApi),
                            repairJobs),
                    authApi);

            // Submit a repair verification job owned by Alice ("101")
            var aliceJob = repairJobs.submit("101", proposal.identity(), (p, c) -> {
                throw new IllegalStateException("stop");
            });

            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                String root = server.uri().toString();

                // POST /api/v1/github/auth/session is CSRF-exempt (pre-auth PAT login)
                var aliceLogin = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"pat_alice_csrf\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, aliceLogin.statusCode(), aliceLogin.body());
                String aliceCookie = aliceLogin.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                var aliceData = com.evolution.analysis.graph.GraphJson.object(
                        com.evolution.analysis.graph.GraphJson.object(
                                com.evolution.analysis.graph.GraphJson.read(aliceLogin.body(),
                                        com.evolution.analysis.graph.GraphJson.Limits.defaults())).get("data"));
                String aliceCsrf = String.valueOf(aliceData.get("csrfToken"));

                var bobLogin = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"pat_bob_csrf\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, bobLogin.statusCode(), bobLogin.body());
                String bobCookie = bobLogin.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                var bobData = com.evolution.analysis.graph.GraphJson.object(
                        com.evolution.analysis.graph.GraphJson.object(
                                com.evolution.analysis.graph.GraphJson.read(bobLogin.body(),
                                        com.evolution.analysis.graph.GraphJson.Limits.defaults())).get("data"));
                String bobCsrf = String.valueOf(bobData.get("csrfToken"));

                // Bob connects a repo and tries to reference Alice's verificationJobId -> 403 APPLY_ACCESS_DENIED
                var bobConn = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/analysis/connections"))
                                .header("Cookie", bobCookie).header(com.evolution.backend.auth.AuthHttpApi.CSRF_HEADER, bobCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"source\":\"bob/demo\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, bobConn.statusCode(), bobConn.body());
                String bobSetup = String.valueOf(com.evolution.analysis.graph.GraphJson.object(
                        com.evolution.analysis.graph.GraphJson.object(
                                com.evolution.analysis.graph.GraphJson.read(bobConn.body(),
                                        com.evolution.analysis.graph.GraphJson.Limits.defaults())).get("data")).get("setup"));

                var bobCrossJob = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/repairs/prepare"))
                                .header("Cookie", bobCookie).header(com.evolution.backend.auth.AuthHttpApi.CSRF_HEADER, bobCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "setup", bobSetup,
                                        "proposalId", proposal.identity().value(),
                                        "verificationJobId", aliceJob.jobId().toString())))).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, bobCrossJob.statusCode(), bobCrossJob.body());
                assertTrue(bobCrossJob.body().contains("APPLY_ACCESS_DENIED"), bobCrossJob.body());

                // P2 #9: DELETE /api/v1/github/auth/session WITHOUT X-Evo-CSRF MUST fail 403 CSRF_TOKEN_INVALID
                var delNoCsrf = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session"))
                                .header("Cookie", aliceCookie).DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, delNoCsrf.statusCode(), delNoCsrf.body());
                assertTrue(delNoCsrf.body().contains("CSRF_TOKEN_INVALID"));

                // DELETE /api/v1/github/auth/session WITH valid X-Evo-CSRF succeeds 200
                var delWithCsrf = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session"))
                                .header("Cookie", aliceCookie)
                                .header(com.evolution.backend.auth.AuthHttpApi.CSRF_HEADER, aliceCsrf)
                                .DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, delWithCsrf.statusCode(), delWithCsrf.body());
            }
        } finally {
            ghMock.stop(0);
        }
    }

    private static CapturedRepair.Proposal sampleUnverifiedProposal() {
        String relPath = "src/Sample.java";
        String before = "class Sample { int x() { return 1; } }\n";
        String after = "class Sample { int x() { return 2; } }\n";
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:evo:github:acme/demo");
        var snap = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("gh-snap"));
        String patch = RepairPatch.file(relPath, before, after);
        var edit = new CapturedRepair.Edit(relPath, ContentDigest.sha256Utf8(before), ContentDigest.sha256Utf8(after),
                ContentDigest.sha256Utf8("bytes"), List.of());
        var pin = new FindingDiagnosis.Pin(snap, Optional.empty(), ContentDigest.sha256Utf8("g"), snap.value(),
                ContentDigest.sha256Utf8("r"), ContentDigest.sha256Utf8("f"), ContentDigest.sha256Utf8("b"));
        var proof = new FindingDiagnosis.Proof(new VersionedIdentifier("rule.demo", "1"), ArchitectureQuery.Scope.TYPE,
                "A", "B", "graph.depends-on", List.of("e"), ArchitectureQuery.Classification.MUST, ContentDigest.sha256Utf8("reg"));
        var candidate = new CapturedRepair.Candidate(pin, ContentDigest.sha256Utf8("d"), proof, List.of(),
                CapturedRepair.Strategy.RECIPE, Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(edit), List.of(), ContentDigest.sha256Utf8(patch), patch, "SYNTAX_ONLY");
        var propId = CanonicalJson.sha256(List.of(CapturedRepair.VERSION, candidate));
        return new CapturedRepair.Proposal(propId, candidate);
    }
}
