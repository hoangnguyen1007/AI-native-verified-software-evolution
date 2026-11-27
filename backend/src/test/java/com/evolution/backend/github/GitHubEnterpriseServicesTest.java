package com.evolution.backend.github;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.policy.ArchitecturePolicy;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureCondition;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.analysis.RepositoryConnections;
import com.evolution.backend.query.*;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GitHubEnterpriseServicesTest {
    private static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void officialGitHubHmacVectorWebhookReplayDedupAndChatOps(@TempDir Path tempDir) throws Exception {
        // Official GitHub webhook validation test vector from docs.github.com
        String officialSig = GitHubWebhookHandler.computeSignature(
                "It's a Secret to Everybody".getBytes(StandardCharsets.UTF_8),
                "Hello, World!".getBytes(StandardCharsets.UTF_8));
        assertEquals("sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17", officialSig);
        assertTrue(GitHubWebhookHandler.verifySignature(
                "It's a Secret to Everybody".getBytes(StandardCharsets.UTF_8),
                "Hello, World!".getBytes(StandardCharsets.UTF_8),
                officialSig));

        var handler = new GitHubWebhookHandler(Optional.of("whsec_test_secret"), false);
        byte[] pingBody = "{\"zen\":\"Keep it logically awesome.\",\"repository\":{\"full_name\":\"acme/demo\"}}".getBytes(StandardCharsets.UTF_8);
        String badResCode = handler.handleDelivery("ping", "del-1", "sha256=0000000000000000000000000000000000000000000000000000000000000000", pingBody).code();
        assertEquals("INVALID_WEBHOOK_SIGNATURE", badResCode);

        String validPingSig = GitHubWebhookHandler.computeSignature("whsec_test_secret".getBytes(StandardCharsets.UTF_8), pingBody);
        var okPing = handler.handleDelivery("ping", "del-1", validPingSig, pingBody);
        assertEquals(200, okPing.httpStatus());
        assertEquals("PONG", okPing.code());

        // Duplicate delivery ID rejected cleanly
        var dupPing = handler.handleDelivery("ping", "del-1", validPingSig, pingBody);
        assertEquals("DUPLICATE_IGNORED", dupPing.code());

        // PR ChatOps /evo analyze accepted for human, ignored for bot
        byte[] chatBody = ("{\"action\":\"created\",\"repository\":{\"full_name\":\"acme/demo\"},"
                + "\"issue\":{\"number\":7,\"pull_request\":{\"url\":\"https://api.github.com/repos/acme/demo/pulls/7\"}},"
                + "\"sender\":{\"login\":\"alice\",\"type\":\"User\"},"
                + "\"comment\":{\"body\":\"Looks great!\\n/evo analyze\"}}").getBytes(StandardCharsets.UTF_8);
        String chatSig = GitHubWebhookHandler.computeSignature("whsec_test_secret".getBytes(StandardCharsets.UTF_8), chatBody);
        var chatRes = handler.handleDelivery("issue_comment", "del-chat-1", chatSig, chatBody);
        assertEquals(200, chatRes.httpStatus());
        assertEquals("CHATOPS_ACCEPTED", chatRes.code());
        assertEquals("/evo analyze", chatRes.payload().get("chatOpsCommand"));

        byte[] botBody = ("{\"action\":\"created\",\"repository\":{\"full_name\":\"acme/demo\"},"
                + "\"issue\":{\"number\":7,\"pull_request\":{}},"
                + "\"sender\":{\"login\":\"evo[bot]\",\"type\":\"Bot\"},"
                + "\"comment\":{\"body\":\"/evo analyze\"}}").getBytes(StandardCharsets.UTF_8);
        String botSig = GitHubWebhookHandler.computeSignature("whsec_test_secret".getBytes(StandardCharsets.UTF_8), botBody);
        var botRes = handler.handleDelivery("issue_comment", "del-bot-1", botSig, botBody);
        assertEquals("IGNORED_BOT", botRes.code());

        // Unconfigured handler fails closed with 503
        var unconfigured = new GitHubWebhookHandler(Optional.empty(), false);
        assertEquals(503, unconfigured.handleDelivery("ping", "del-x", validPingSig, pingBody).httpStatus());
    }

    @Test
    void orgRepoPickerCodeownersEnrichedPrReviewSarifUploadIssuesWebhooksAndCiWorkflowEndToEnd(@TempDir Path tempDir) throws Exception {
        var uploadedSarifBody = new AtomicReference<String>();
        var createdReviewBody = new AtomicReference<String>();
        var createdIssueBody = new AtomicReference<String>();

        var ghMock = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        ghMock.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals("/user/orgs")) {
                GitHubRestClientTest.respondJson(ex, 200,
                        "[{\"id\":10,\"login\":\"acme\",\"avatar_url\":\"https://avatars.githubusercontent.com/u/10\",\"description\":\"Acme Org\"}]", Map.of());
                return;
            }
            if (path.equals("/user/repos") || path.equals("/orgs/acme/repos")) {
                GitHubRestClientTest.respondJson(ex, 200,
                        "[{\"id\":101,\"full_name\":\"acme/billing-api\",\"name\":\"billing-api\",\"owner\":{\"login\":\"acme\"},"
                                + "\"description\":\"Billing microservice\",\"private\":true,\"default_branch\":\"main\",\"language\":\"Java\","
                                + "\"html_url\":\"https://github.com/acme/billing-api\",\"clone_url\":\"https://github.com/acme/billing-api.git\","
                                + "\"pushed_at\":\"2026-11-25T10:00:00Z\",\"stargazers_count\":42,\"permissions\":{\"admin\":true,\"push\":true,\"pull\":true}},"
                                + "{\"id\":102,\"full_name\":\"acme/docs-site\",\"name\":\"docs-site\",\"owner\":{\"login\":\"acme\"},"
                                + "\"description\":\"Docs\",\"private\":false,\"default_branch\":\"main\",\"language\":\"TypeScript\"}]", Map.of());
                return;
            }
            if (path.equals("/repos/acme/billing-api")) {
                GitHubRestClientTest.respondJson(ex, 200,
                        "{\"full_name\":\"acme/billing-api\",\"default_branch\":\"main\",\"private\":true,\"html_url\":\"https://github.com/acme/billing-api\",\"language\":\"Java\"}", Map.of());
                return;
            }
            if (path.equals("/repos/acme/billing-api/pulls")) {
                GitHubRestClientTest.respondJson(ex, 200,
                        "[{\"number\":7,\"title\":\"Refactor BillingService\",\"state\":\"open\",\"draft\":false,"
                                + "\"html_url\":\"https://github.com/acme/billing-api/pull/7\","
                                + "\"user\":{\"login\":\"alice\",\"avatar_url\":\"https://avatars.githubusercontent.com/u/1001\"},"
                                + "\"base\":{\"ref\":\"main\",\"sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},"
                                + "\"head\":{\"ref\":\"feat/billing\",\"sha\":\"" + HEAD_SHA + "\"}}]", Map.of());
                return;
            }
            if (path.equals("/repos/acme/billing-api/pulls/7")) {
                GitHubRestClientTest.respondJson(ex, 200,
                        "{\"number\":7,\"title\":\"Refactor BillingService\",\"state\":\"open\",\"draft\":false,"
                                + "\"html_url\":\"https://github.com/acme/billing-api/pull/7\","
                                + "\"user\":{\"login\":\"alice\",\"avatar_url\":\"https://avatars.githubusercontent.com/u/1001\"},"
                                + "\"base\":{\"ref\":\"main\",\"sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},"
                                + "\"head\":{\"ref\":\"feat/billing\",\"sha\":\"" + HEAD_SHA + "\"}}", Map.of());
                return;
            }
            if (path.equals("/repos/acme/billing-api/pulls/7/files")) {
                String patch = "@@ -1,3 +1,3 @@\\n class BillingService {\\n-    int fee() { return 1; }\\n+    int fee() { return 99; }\\n }";
                GitHubRestClientTest.respondJson(ex, 200,
                        "[{\"filename\":\"src/BillingService.java\",\"status\":\"modified\",\"additions\":1,\"deletions\":1,\"changes\":2,\"patch\":\"" + patch + "\"}]", Map.of());
                return;
            }
            if (path.equals("/repos/acme/billing-api/commits/" + HEAD_SHA + "/status")) {
                GitHubRestClientTest.respondJson(ex, 200, "{\"state\":\"success\",\"total_count\":2}", Map.of());
                return;
            }
            if (path.equals("/repos/acme/billing-api/pulls/7/reviews") && "POST".equals(method)) {
                createdReviewBody.set(reqBody);
                GitHubRestClientTest.respondJson(ex, 200,
                        "{\"id\":8801,\"html_url\":\"https://github.com/acme/billing-api/pull/7#pullrequestreview-8801\",\"state\":\"COMMENTED\"}", Map.of());
                return;
            }
            if (path.equals("/repos/acme/billing-api/statuses/" + HEAD_SHA) && "POST".equals(method)) {
                GitHubRestClientTest.respondJson(ex, 201,
                        "{\"id\":9901,\"state\":\"failure\",\"context\":\"evo/architecture-gate\",\"description\":\"Gate failed\"}", Map.of());
                return;
            }
            if (path.equals("/repos/acme/billing-api/check-runs") && "POST".equals(method)) {
                GitHubRestClientTest.respondJson(ex, 403, "{\"message\":\"Resource not accessible by integration\"}", Map.of());
                return;
            }
            if (path.equals("/repos/acme/billing-api/code-scanning/sarifs") && "POST".equals(method)) {
                uploadedSarifBody.set(reqBody);
                GitHubRestClientTest.respondJson(ex, 202,
                        "{\"id\":\"sarif-uuid-42\",\"url\":\"https://api.github.com/repos/acme/billing-api/code-scanning/sarifs/sarif-uuid-42\"}", Map.of());
                return;
            }
            if (path.equals("/repos/acme/billing-api/issues") && "POST".equals(method)) {
                createdIssueBody.set(reqBody);
                GitHubRestClientTest.respondJson(ex, 201,
                        "{\"number\":55,\"html_url\":\"https://github.com/acme/billing-api/issues/55\",\"title\":\"Created\",\"state\":\"open\",\"labels\":[{\"name\":\"architecture\"}],\"assignees\":[{\"login\":\"alice\"}]}", Map.of());
                return;
            }
            GitHubRestClientTest.respondJson(ex, 404, "{\"message\":\"Not Found\"}", Map.of());
        });
        ghMock.start();

        // Build snapshot graph with GitHub provenance, .github/CODEOWNERS, source content, and a policy violation on line 2
        String billingBefore = "class BillingService {\n    int fee() { return 99; }\n}\n";
        String billingAfter = "class BillingService {\n    int fee() { return 10; }\n}\n";
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("https://github.com/acme/billing-api");
        var snap = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("billing-snap"));
        var evSnap = GraphEvidence.capture("repository.snapshot", Map.of(
                "repository", "https://github.com/acme/billing-api",
                "revision", HEAD_SHA));
        String codeownersText = "src/BillingService.java @alice @acme/billing-team\n";
        var evCodeowners = GraphEvidence.capture("source.bytes", Map.of(
                "document", Map.of("path", ".github/CODEOWNERS", "language", "text"),
                "digest", ContentDigest.sha256Utf8(codeownersText).value(),
                "bytesBase64", Base64.getEncoder().encodeToString(codeownersText.getBytes(StandardCharsets.UTF_8))));
        var evBillingContent = GraphEvidence.capture("source.bytes", Map.of(
                "document", Map.of("path", "src/BillingService.java", "language", "java"),
                "digest", ContentDigest.sha256Utf8(billingBefore).value(),
                "bytesBase64", Base64.getEncoder().encodeToString(billingBefore.getBytes(StandardCharsets.UTF_8))));
        var evDecl = GraphEvidence.capture("java.declaration", Map.of(
                "span", Map.of("path", "src/BillingService.java", "startLine", 2, "endLine", 2, "startColumn", 5)));
        String billingNodeId = "type:" + ContentDigest.sha256Utf8("com.acme.BillingService").value();
        String dbNodeId = "type:" + ContentDigest.sha256Utf8("com.acme.InternalDb").value();
        String edgeId = "edge:" + ContentDigest.sha256Utf8("e1").value();
        var graph = new KnowledgeGraph(snap,
                List.of(new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), evSnap.identity())),
                List.of(
                        new KnowledgeGraph.Node(billingNodeId, snap.value(), "TYPE", List.of(evDecl.identity())),
                        new KnowledgeGraph.Node(dbNodeId, snap.value(), "TYPE", List.of(evDecl.identity()))),
                List.of(new KnowledgeGraph.Edge(edgeId, snap.value(), "graph.depends-on", billingNodeId,
                        new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dbNodeId), Optional.empty()),
                        List.of(evDecl.identity()), Optional.empty())),
                List.of(evSnap, evCodeowners, evBillingContent, evDecl));

        var graphs = new InMemoryGraphStore();
        graphs.save(graph);
        var reports = new PublishedReports();

        var ruleHeader = new ArchitecturePolicy.Header("acme.no-direct-db", "1",
                ArchitecturePolicy.Severity.ERROR, ArchitectureQuery.Scope.TYPE, Set.of("graph.depends-on"));
        var selector = new ArchitecturePolicy.Selector(Set.of(), Set.of("*"), Set.of(), Set.of(), Set.of(), Set.of());
        var rule = new ArchitecturePolicy.Forbidden(ruleHeader, selector, selector);
        var req = new ArchitectureQuery.Request(graph.identity(), snap.value(), ArchitectureQuery.Scope.TYPE,
                ArchitectureQuery.Projection.BOUNDED_UNION, ArchitectureQuery.Direction.OUTGOING,
                Set.of("graph.depends-on"), ArchitectureQuery.Population.all(), false, Optional.empty(),
                ArchitectureQuery.Limits.defaults());
        var pred = new ArchitectureCondition.RulePredicate(
                List.of(new ArchitectureCondition.Alternative(new ArchitectureCondition.Term(List.of(edgeId)), true)), true);
        var evalRegion = new ArchitectureCondition.Evaluation(
                ContentDigest.sha256Utf8("reg"), ArchitectureQuery.Classification.MUST,
                Optional.empty(), Optional.empty(), List.of(), List.of());
        var edgeView = new ArchitectureQuery.EdgeView(edgeId, billingNodeId, dbNodeId,
                List.of("graph.depends-on"), List.of(edgeId), List.of(evDecl.identity()), List.of(),
                ArchitectureQuery.Classification.MUST, true, 1, false);
        var srcEv = new PolicyEvaluation.SourceEvidence(
                new SourceSpan(SourceDocumentIdentity.from(repoId, "src/BillingService.java"), 2, 5, 2, 30),
                ContentDigest.sha256Utf8(billingBefore), evDecl.identity());
        var bundle = new PolicyEvaluation.EvidenceBundle(
                snap, Optional.empty(), graph.identity(), snap.value(),
                ContentDigest.sha256Utf8("pol"), ContentDigest.sha256Utf8("pol-src"),
                ContentDigest.sha256Utf8("comp"), ContentDigest.sha256Utf8("map"),
                List.of(), List.of(), List.of(),
                rule, req, ContentDigest.sha256Utf8("q"), pred, evalRegion,
                List.of(billingNodeId, dbNodeId),
                List.of(edgeView), List.of(), List.of(srcEv),
                List.of(new PolicyEvaluation.EvidenceReference(evDecl.identity(), "java.declaration")),
                AssessmentStatus.COMPLETE, List.of(), List.of());
        var finding = new PolicyEvaluation.Finding(ruleHeader, ArchitecturePolicy.Kind.FORBIDDEN_DEPENDENCY,
                ArchitectureQuery.Classification.MUST, bundle);
        var ruleResult = new PolicyEvaluation.RuleResult(rule, AssessmentStatus.COMPLETE,
                ArchitectureQuery.Classification.MUST, ContentDigest.sha256Utf8("q"), pred, evalRegion,
                List.of(finding), List.of());
        var eval = new PolicyEvaluation.Result(
                ContentDigest.sha256Utf8("comp"), ContentDigest.sha256Utf8("map"),
                graph.identity(), snap.value(), PolicyEvaluation.Limits.defaults(),
                AssessmentStatus.COMPLETE, List.of(ruleResult), List.of(), List.of());
        var policyReport = new PublishedReports.Report(eval, Optional.empty(), Optional.empty());
        var reportId = reports.publish(policyReport);

        // Also store a repair proposal targeting line 2 of src/BillingService.java for clickable ```suggestion block!
        String patch = RepairPatch.file("src/BillingService.java", billingBefore, billingAfter);
        var edit = new CapturedRepair.Edit("src/BillingService.java",
                ContentDigest.sha256Utf8(billingBefore), ContentDigest.sha256Utf8(billingAfter),
                ContentDigest.sha256Utf8("b"), List.of());
        var pin = new FindingDiagnosis.Pin(snap, Optional.empty(), graph.identity(), snap.value(),
                reportId, finding.identity(), ContentDigest.sha256Utf8("b"));
        var proof = new FindingDiagnosis.Proof(new VersionedIdentifier("acme.no-direct-db", "1"), ArchitectureQuery.Scope.TYPE,
                billingNodeId, dbNodeId, "graph.depends-on",
                List.of(edgeId), ArchitectureQuery.Classification.MUST, ContentDigest.sha256Utf8("reg"));
        var candidate = new CapturedRepair.Candidate(pin, ContentDigest.sha256Utf8("d"), proof, List.of(),
                CapturedRepair.Strategy.RECIPE, Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(edit), List.of(), ContentDigest.sha256Utf8(patch), patch, "SYNTAX_ONLY");
        var propId = CanonicalJson.sha256(List.of(CapturedRepair.VERSION, candidate));
        var repairStore = new RepairProposalStore(tempDir.resolve("repairs"));
        repairStore.put(new CapturedRepair.Proposal(propId, candidate));

        URI mockUri = URI.create("http://127.0.0.1:" + ghMock.getAddress().getPort());
        try (var broker = new GitHubTokenBroker(k -> "EVO_GITHUB_TOKEN".equals(k) ? "ghp_ent_token_123456789" : null, h -> Optional.empty());
             var jobs = new AnalysisJobs(List.of(), graphs, reports, AnalysisJobs.Limits.defaults())) {
            var connections = new RepositoryConnections(jobs, Path.of(System.getProperty("java.home")), 21,
                    tempDir.resolve("m2"), tempDir.resolve("store"), Optional.empty(), "git", new GitHostPolicy());
            var conn = connections.connect("acme/billing-api", Optional.empty());
            var rest = new GitHubRestClient(broker, GitHubRestClientTest.loopbackTransport(), mockUri);
            var diffSvc = new GitHubPrDiffService(rest, jobs, connections, graphs, reports, tempDir.resolve("diffs"));
            var pub = new GitHubRepairPublisher(rest, broker, repairStore, connections, new GitHostPolicy(), tempDir.resolve("pub"), "git");
            var reviewSvc = new GitHubPrReviewService(rest, diffSvc, connections, graphs, reports, repairStore);
            var issueSvc = new GitHubIssueService(rest, graphs, reports);
            var webhooks = new GitHubWebhookHandler(Optional.of("whsec_live_123"), false);
            var ghApi = new GitHubHttpApi(broker, rest, connections, diffSvc, pub, ApiLimits.defaults())
                    .withEnterpriseServices(null, reviewSvc, issueSvc, webhooks, graphs, reports);
            var api = ArchitectureQueryApi.withGitHub(
                    new ArchitectureQueryApi(graphs, reports, ApiLimits.defaults(), jobs, connections), ghApi);

            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                String root = server.uri().toString();
                var http = HttpClient.newHttpClient();

                // 1. Orgs & Repo picker with search filter
                var orgsResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/orgs")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, orgsResp.statusCode());
                assertTrue(orgsResp.body().contains("\"login\":\"acme\""));

                var reposResp = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/github/user/repos?org=acme&q=billing&visibility=all")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, reposResp.statusCode(), reposResp.body());
                assertTrue(reposResp.body().contains("acme/billing-api"));
                assertFalse(reposResp.body().contains("docs-site"));

                // 2. Passive CODEOWNERS endpoint
                var coResp = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/graphs/" + graph.identity().value() + "/codeowners?path=src%2FBillingService.java")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, coResp.statusCode(), coResp.body());
                assertTrue(coResp.body().contains("@alice"));
                assertTrue(coResp.body().contains("@acme/billing-team"));

                // 3. Enriched PRs dashboard
                var enrichedResp = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/github/connections/" + conn.setup() + "/pulls/enriched?state=open")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, enrichedResp.statusCode(), enrichedResp.body());
                assertTrue(enrichedResp.body().contains("\"ciState\":\"success\""));
                assertTrue(enrichedResp.body().contains("\"verdict\":\"NOT_ANALYZED\""));

                // 4. Inline PR Review Preview (with ```suggestion block + CODEOWNERS + Quality Gate FAILED) -> Confirmed Publish!
                var previewResp = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/github/connections/" + conn.setup() + "/pulls/7/review/preview"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "graph", graph.identity().value(),
                                        "report", reportId.value(),
                                        "proposalId", propId.value(),
                                        "postCommitStatus", true))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, previewResp.statusCode(), previewResp.body());
                assertTrue(previewResp.body().contains("```suggestion"), "Preview must embed clickable GitHub suggestion block");
                assertTrue(previewResp.body().contains("\"verdict\":\"FAILED\""));
                assertTrue(previewResp.body().contains("@acme/billing-team"));
                var prevData = extractData(previewResp.body());

                var pubResp = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/github/connections/" + conn.setup() + "/pulls/7/review/publish"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "confirmationToken", prevData.get("confirmationToken"),
                                        "reviewPlanDigest", prevData.get("reviewPlanDigest"),
                                        "confirmedByUser", true))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, pubResp.statusCode(), pubResp.body());
                assertTrue(pubResp.body().contains("\"commitStatusPosted\":true"));
                assertNotNull(createdReviewBody.get());
                assertTrue(createdReviewBody.get().contains("\"side\":\"RIGHT\""));
                assertTrue(createdReviewBody.get().contains("suggestion"));

                // 5. Commit Status endpoint + explicit 403 GITHUB_APP_TOKEN_REQUIRED on Check Run
                var checkFail = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/github/connections/" + conn.setup() + "/commits/" + HEAD_SHA + "/status"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "confirmedByUser", true,
                                        "state", "failure",
                                        "description", "Quality Gate failed",
                                        "createCheckRun", true))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, checkFail.statusCode(), checkFail.body());
                assertTrue(checkFail.body().contains("GITHUB_APP_TOKEN_REQUIRED"));

                // 6. SARIF 2.1.0 download + confirmed gzip+base64 upload
                var sarifDl = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/graphs/" + graph.identity().value() + "/reports/" + reportId.value() + "/sarif?download=1"))
                                .header("Accept", "application/sarif+json").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, sarifDl.statusCode(), sarifDl.body());
                assertTrue(sarifDl.headers().firstValue("Content-Type").orElse("").contains("application/sarif+json"));
                assertTrue(sarifDl.body().contains("\"version\":\"2.1.0\""));
                assertTrue(sarifDl.body().contains("acme.no-direct-db@1"));

                var sarifUp = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/github/connections/" + conn.setup() + "/code-scanning/sarif"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "graph", graph.identity().value(),
                                        "report", reportId.value(),
                                        "commitSha", HEAD_SHA,
                                        "ref", "refs/heads/main",
                                        "confirmedByUser", true))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, sarifUp.statusCode(), sarifUp.body());
                assertTrue(sarifUp.body().contains("\"sarifId\":\"sarif-uuid-42\""));
                // Decompress uploaded gzip+base64 SARIF and verify exact match!
                var upObj = GraphJson.object(GraphJson.read(uploadedSarifBody.get(), GraphJson.Limits.defaults()));
                byte[] gzBytes = Base64.getDecoder().decode(String.valueOf(upObj.get("sarif")));
                try (var gzIn = new GZIPInputStream(new ByteArrayInputStream(gzBytes))) {
                    String inflated = new String(gzIn.readAllBytes(), StandardCharsets.UTF_8);
                    assertEquals(sarifDl.body(), inflated);
                }

                // 7. Two-step GitHub Issue creation with CODEOWNERS + Mermaid + Permalink
                var issPrep = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/github/connections/" + conn.setup() + "/issues/prepare"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "graph", graph.identity().value(),
                                        "report", reportId.value(),
                                        "findingId", finding.identity().value()))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, issPrep.statusCode(), issPrep.body());
                assertTrue(issPrep.body().contains("```mermaid"));
                assertTrue(issPrep.body().contains("https://github.com/acme/billing-api/blob/" + HEAD_SHA + "/src/BillingService.java#L2"));
                var issData = extractData(issPrep.body());

                var issPub = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/github/connections/" + conn.setup() + "/issues/publish"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of(
                                        "confirmationToken", issData.get("confirmationToken"),
                                        "issuePlanDigest", issData.get("issuePlanDigest"),
                                        "confirmedByUser", true))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(201, issPub.statusCode(), issPub.body());
                assertTrue(issPub.body().contains("\"issueNumber\":55"));
                assertTrue(createdIssueBody.get().contains("\"assignees\":[\"alice\"]"),
                        "Only individual login @alice should be in assignees[] (not @acme/billing-team)");

                // 8. Live HTTP Webhook endpoint (raw body HMAC verification + deliveries audit log)
                byte[] whBody = "{\"action\":\"opened\",\"number\":7,\"repository\":{\"full_name\":\"acme/billing-api\"}}".getBytes(StandardCharsets.UTF_8);
                String whSig = GitHubWebhookHandler.computeSignature("whsec_live_123".getBytes(StandardCharsets.UTF_8), whBody);
                var whResp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/webhooks"))
                                .header("Content-Type", "application/json")
                                .header("X-GitHub-Event", "pull_request")
                                .header("X-GitHub-Delivery", "live-del-1")
                                .header("X-Hub-Signature-256", whSig)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(whBody))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(202, whResp.statusCode(), whResp.body());
                assertTrue(whResp.body().contains("QUEUED_PR_ANALYSIS"));

                var delLog = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/webhooks/deliveries")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, delLog.statusCode());
                assertTrue(delLog.body().contains("live-del-1"));

                // 9. GitHub Actions CI Workflow generator
                var ciResp = http.send(HttpRequest.newBuilder(URI.create(
                        root + "/api/v1/github/connections/" + conn.setup() + "/ci-workflow?failOnMust=true&minScore=75")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, ciResp.statusCode(), ciResp.body());
                assertTrue(ciResp.body().contains("github/codeql-action/upload-sarif@v3"));
                assertTrue(ciResp.body().contains("EVO_MIN_SCORE: \\\"75\\\""));
            }
        } finally {
            ghMock.stop(0);
        }
    }

    @Test
    void multiUserWebhookDeliveriesAndPrDiffCacheAreStrictlyTenantIsolated(@TempDir Path tempDir) throws Exception {
        var ghMock = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        ghMock.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (path.equals("/user")) {
                if ("Bearer pat_alice".equals(auth)) {
                    respondJson(ex, 200, "{\"id\":101,\"login\":\"alice\",\"name\":\"Alice\",\"email\":\"alice@acme.dev\"}");
                } else if ("Bearer pat_bob".equals(auth)) {
                    respondJson(ex, 200, "{\"id\":202,\"login\":\"bob\",\"name\":\"Bob\",\"email\":\"bob@labs.dev\"}");
                } else {
                    respondJson(ex, 200, "{\"id\":303,\"login\":\"charlie\",\"name\":\"Charlie\",\"email\":\"charlie@other.dev\"}");
                }
                return;
            }
            if (path.equals("/user/orgs")) {
                respondJson(ex, 200, "[]");
                return;
            }
            if (path.equals("/repos/acme/shared-app/commits/main")) {
                respondJson(ex, 200, "{\"sha\":\"1111111111111111111111111111111111111111\"}");
                return;
            }
            if (path.equals("/repos/acme/shared-app/commits/feature")) {
                respondJson(ex, 200, "{\"sha\":\"2222222222222222222222222222222222222222\"}");
                return;
            }
            if (path.equals("/repos/acme/shared-app/compare/1111111111111111111111111111111111111111...2222222222222222222222222222222222222222")) {
                respondJson(ex, 200, "{\"files\":[{\"filename\":\"src/App.java\"}]}");
                return;
            }
            respondJson(ex, 404, "{\"message\":\"Not Found\"}");
        });
        ghMock.start();
        URI mockUri = URI.create("http://127.0.0.1:" + ghMock.getAddress().getPort());

        var graphs = new InMemoryGraphStore();
        var reports = new PublishedReports();
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("https://github.com/acme/shared-app");
        var baseSnap = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("base-snap"));
        var headSnap = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("head-snap"));
        var baseEv = GraphEvidence.capture("repository.snapshot", Map.of("ref", "main"));
        var headEv = GraphEvidence.capture("repository.snapshot", Map.of("ref", "feature"));
        var baseGraph = new KnowledgeGraph(baseSnap,
                List.of(new KnowledgeGraph.Context(baseSnap.value(), baseSnap, Optional.empty(), baseEv.identity())),
                List.of(new KnowledgeGraph.Node(repoId.value(), baseSnap.value(), "REPOSITORY", List.of(baseEv.identity()))),
                List.of(), List.of(baseEv));
        var headGraph = new KnowledgeGraph(headSnap,
                List.of(new KnowledgeGraph.Context(headSnap.value(), headSnap, Optional.empty(), headEv.identity())),
                List.of(new KnowledgeGraph.Node(repoId.value(), headSnap.value(), "REPOSITORY", List.of(headEv.identity()))),
                List.of(), List.of(headEv));
        graphs.save(baseGraph);
        graphs.save(headGraph);

        var oauthCfg = new com.evolution.backend.auth.GitHubOAuthService.Config(
                true, "", "", "github.com", com.evolution.backend.auth.GitHubOAuthService.DEFAULT_SCOPES,
                false, Optional.empty(), Optional.of(mockUri), Optional.of(mockUri));
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

        try (var broker = new GitHubTokenBroker(k -> null, h -> Optional.empty());
             var sessionStore = new com.evolution.backend.auth.UserSessionStore();
             var jobs = new AnalysisJobs(List.of(), graphs, reports, AnalysisJobs.Limits.defaults())) {
            broker.setMultiUserMode(true);
            var rest = new GitHubRestClient(broker, transport, mockUri);
            var oauth = new com.evolution.backend.auth.GitHubOAuthService(oauthCfg, sessionStore, transport);
            var authApi = new com.evolution.backend.auth.AuthHttpApi(oauth, rest, ApiLimits.defaults());
            var connections = new RepositoryConnections(jobs, Path.of(System.getProperty("java.home")), 21,
                    tempDir.resolve("m2"), tempDir.resolve("store"), Optional.empty(), "git", new GitHostPolicy());
            var wh = new GitHubWebhookHandler(Optional.of("whsec_tenant_test"), false);
            var diffSvc = new GitHubPrDiffService(rest, jobs, connections, graphs, reports, tempDir.resolve("diffs"));
            var ghApi = new GitHubHttpApi(broker, rest, connections, diffSvc, null, ApiLimits.defaults())
                    .withEnterpriseServices(authApi, null, null, wh, graphs, reports);
            var api = ArchitectureQueryApi.withAuth(
                    ArchitectureQueryApi.withGitHub(
                            new ArchitectureQueryApi(graphs, reports, ApiLimits.defaults(), jobs, connections),
                            ghApi),
                    authApi);

            // Seed two webhook deliveries for distinct repos
            byte[] d1 = "{\"action\":\"opened\",\"number\":1,\"repository\":{\"full_name\":\"acme/repo-a\"}}".getBytes(StandardCharsets.UTF_8);
            byte[] d2 = "{\"action\":\"opened\",\"number\":2,\"repository\":{\"full_name\":\"bob/repo-b\"}}".getBytes(StandardCharsets.UTF_8);
            assertEquals(202, wh.handleDelivery("pull_request", "del-a",
                    GitHubWebhookHandler.computeSignature("whsec_tenant_test".getBytes(StandardCharsets.UTF_8), d1), d1).httpStatus());
            assertEquals(202, wh.handleDelivery("pull_request", "del-b",
                    GitHubWebhookHandler.computeSignature("whsec_tenant_test".getBytes(StandardCharsets.UTF_8), d2), d2).httpStatus());

            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                String root = server.uri().toString();

                // Authenticate Alice, Bob, Charlie via PAT session
                var aliceLogin = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"pat_alice\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, aliceLogin.statusCode(), aliceLogin.body());
                String aliceCookie = aliceLogin.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                String aliceCsrf = String.valueOf(extractData(aliceLogin.body()).get("csrfToken"));

                var bobLogin = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"pat_bob\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, bobLogin.statusCode(), bobLogin.body());
                String bobCookie = bobLogin.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                String bobCsrf = String.valueOf(extractData(bobLogin.body()).get("csrfToken"));

                var charlieLogin = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/auth/session"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"pat_charlie\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, charlieLogin.statusCode(), charlieLogin.body());
                String charlieCookie = charlieLogin.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

                // Alice connects acme/repo-a; Bob connects bob/repo-b; Charlie connects nothing
                assertEquals(201, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/analysis/connections"))
                                .header("Cookie", aliceCookie).header(com.evolution.backend.auth.AuthHttpApi.CSRF_HEADER, aliceCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"source\":\"acme/repo-a\"}")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(201, http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/analysis/connections"))
                                .header("Cookie", bobCookie).header(com.evolution.backend.auth.AuthHttpApi.CSRF_HEADER, bobCsrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"source\":\"bob/repo-b\"}")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());

                var aliceDel = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/webhooks/deliveries"))
                        .header("Cookie", aliceCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, aliceDel.statusCode());
                assertTrue(aliceDel.body().contains("del-a") && !aliceDel.body().contains("del-b"), aliceDel.body());

                var bobDel = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/webhooks/deliveries"))
                        .header("Cookie", bobCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, bobDel.statusCode());
                assertTrue(bobDel.body().contains("del-b") && !bobDel.body().contains("del-a"), bobDel.body());

                var charlieDel = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/github/webhooks/deliveries"))
                        .header("Cookie", charlieCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, charlieDel.statusCode());
                assertFalse(charlieDel.body().contains("del-a") || charlieDel.body().contains("del-b"), charlieDel.body());
            }

            // P2 #8: PR diff cache keys by (ownerUserId, diffId) — identical commits never overwrite across tenants,
            // and a third user reading another user's diffId gets SecurityException("ACCESS_DENIED").
            var aliceUser = new com.evolution.backend.auth.AuthenticatedUser("101", "alice", "Alice", "", "alice@acme.dev",
                    List.of(), List.of("repo"), com.evolution.backend.auth.AuthenticatedUser.AuthMethod.SESSION_PAT,
                    "github.com", "csrf-a", System.currentTimeMillis(), 0L);
            var bobUser = new com.evolution.backend.auth.AuthenticatedUser("202", "bob", "Bob", "", "bob@labs.dev",
                    List.of(), List.of("repo"), com.evolution.backend.auth.AuthenticatedUser.AuthMethod.SESSION_PAT,
                    "github.com", "csrf-b", System.currentTimeMillis(), 0L);
            var charlieUser = new com.evolution.backend.auth.AuthenticatedUser("303", "charlie", "Charlie", "", "charlie@other.dev",
                    List.of(), List.of("repo"), com.evolution.backend.auth.AuthenticatedUser.AuthMethod.SESSION_PAT,
                    "github.com", "csrf-c", System.currentTimeMillis(), 0L);
            var aliceReq = new com.evolution.backend.auth.RequestContext.ActiveRequest(
                    Optional.of("sid-a"), aliceUser, true, true, h -> Optional.of(new GitHubTokenBroker.ResolvedToken("pat_alice", "SESSION_PAT")));
            var bobReq = new com.evolution.backend.auth.RequestContext.ActiveRequest(
                    Optional.of("sid-b"), bobUser, true, true, h -> Optional.of(new GitHubTokenBroker.ResolvedToken("pat_bob", "SESSION_PAT")));
            var charlieReq = new com.evolution.backend.auth.RequestContext.ActiveRequest(
                    Optional.of("sid-c"), charlieUser, true, true, h -> Optional.of(new GitHubTokenBroker.ResolvedToken("pat_charlie", "SESSION_PAT")));

            var aliceSetup = com.evolution.backend.auth.RequestContext.runWith(aliceReq, () -> {
                var c = connections.connect("acme/shared-app", Optional.empty());
                jobs.unregister(c.setup());
                jobs.register(new AnalysisJobs.Registration(
                        new AnalysisJobs.Setup(c.setup(), repoId, 21, false, Optional.empty(), Optional.empty()),
                        ctrl -> new AnalysisJobs.Outcome(AnalysisJobs.Completion.COMPLETE,
                                Optional.of("1111111111111111111111111111111111111111".equals(ctrl.requestedRef().orElse("")) ? baseGraph : headGraph),
                                List.of(), List.of(), Optional.empty(), Optional.empty())));
                return c.setup();
            });
            var bobSetup = com.evolution.backend.auth.RequestContext.runWith(bobReq, () -> {
                var c = connections.connect("acme/shared-app", Optional.empty());
                jobs.unregister(c.setup());
                jobs.register(new AnalysisJobs.Registration(
                        new AnalysisJobs.Setup(c.setup(), repoId, 21, false, Optional.empty(), Optional.empty()),
                        ctrl -> new AnalysisJobs.Outcome(AnalysisJobs.Completion.COMPLETE,
                                Optional.of("1111111111111111111111111111111111111111".equals(ctrl.requestedRef().orElse("")) ? baseGraph : headGraph),
                                List.of(), List.of(), Optional.empty(), Optional.empty())));
                return c.setup();
            });

            var aliceDiff = com.evolution.backend.auth.RequestContext.runWith(aliceReq,
                    () -> diffSvc.compare(aliceSetup, OptionalInt.empty(), Optional.of("main"), Optional.of("feature"),
                            com.evolution.backend.analysis.AnalysisMode.FAST_ARCHITECTURE, () -> false));
            var bobDiff = com.evolution.backend.auth.RequestContext.runWith(bobReq,
                    () -> diffSvc.compare(bobSetup, OptionalInt.empty(), Optional.of("main"), Optional.of("feature"),
                            com.evolution.backend.analysis.AnalysisMode.FAST_ARCHITECTURE, () -> false));
            assertEquals(aliceDiff.diffId(), bobDiff.diffId(), "Same commits yield same deterministic diffId");
            assertEquals(aliceSetup, com.evolution.backend.auth.RequestContext.runWith(aliceReq,
                    () -> diffSvc.get(aliceDiff.diffId()).orElseThrow().setup()), "Bob MUST NOT overwrite Alice's cached diff");
            assertEquals(bobSetup, com.evolution.backend.auth.RequestContext.runWith(bobReq,
                    () -> diffSvc.get(bobDiff.diffId()).orElseThrow().setup()));
            assertThrows(SecurityException.class, () -> com.evolution.backend.auth.RequestContext.runWith(charlieReq,
                    () -> diffSvc.get(aliceDiff.diffId())));
        } finally {
            ghMock.stop(0);
        }
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
