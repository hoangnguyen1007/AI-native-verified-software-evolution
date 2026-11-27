package com.evolution.cli;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
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
import com.evolution.backend.auth.GitHubOAuthService;
import com.evolution.backend.auth.UserSessionStore;
import com.evolution.backend.github.GitHubRestClient;
import com.evolution.backend.github.GitHubTokenBroker;
import com.evolution.backend.query.PublishedReports;
import com.sun.net.httpserver.HttpServer;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class EvoGitHubAuthCliTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void cliLoginWhoamiLogoutReposOrgsCiInitPrReviewStatusSarifAndIssueCreateWorkOffline(@TempDir Path tempDir) throws Exception {
        var devicePolls = new AtomicInteger();
        var ghMock = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        ghMock.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/login/device/code")) {
                respond(ex, 200, "{\"device_code\":\"dev_cli_123\",\"user_code\":\"WXYZ-9876\","
                        + "\"verification_uri\":\"https://github.com/login/device\",\"expires_in\":900,\"interval\":1}");
                return;
            }
            if (path.equals("/login/oauth/access_token")) {
                if (devicePolls.incrementAndGet() == 1) {
                    respond(ex, 200, "{\"error\":\"authorization_pending\"}");
                } else {
                    respond(ex, 200, "{\"access_token\":\"gho_cli_device_tok_999\",\"token_type\":\"bearer\",\"scope\":\"read:user,repo\"}");
                }
                return;
            }
            if (path.equals("/user")) {
                ex.getResponseHeaders().set("X-OAuth-Scopes", "read:user, repo");
                respond(ex, 200, "{\"id\":707,\"login\":\"cliuser\",\"name\":\"CLI User\",\"email\":\"cli@acme.dev\"}");
                return;
            }
            if (path.equals("/user/orgs")) {
                respond(ex, 200, "[{\"id\":1,\"login\":\"acme-cli\",\"description\":\"CLI Org\"}]");
                return;
            }
            if (path.equals("/user/repos")) {
                respond(ex, 200, "[{\"id\":2,\"full_name\":\"acme-cli/platform\",\"name\":\"platform\",\"owner\":{\"login\":\"acme-cli\"},"
                        + "\"description\":\"Evo CLI repo\",\"private\":false,\"default_branch\":\"main\",\"language\":\"Java\"}]");
                return;
            }
            if (path.equals("/repos/acme-cli/platform")) {
                respond(ex, 200, "{\"full_name\":\"acme-cli/platform\",\"default_branch\":\"main\",\"private\":false}");
                return;
            }
            if (path.equals("/repos/acme-cli/platform/pulls/9")) {
                respond(ex, 200, "{\"number\":9,\"title\":\"CLI PR\",\"state\":\"open\",\"draft\":false,"
                        + "\"html_url\":\"https://github.com/acme-cli/platform/pull/9\","
                        + "\"user\":{\"login\":\"cliuser\"},\"base\":{\"ref\":\"main\",\"sha\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"},"
                        + "\"head\":{\"ref\":\"feat/cli\",\"sha\":\"" + SHA + "\"}}");
                return;
            }
            if (path.equals("/repos/acme-cli/platform/pulls/9/files")) {
                respond(ex, 200, "[{\"filename\":\"src/App.java\",\"status\":\"modified\",\"additions\":1,\"deletions\":0,\"changes\":1,"
                        + "\"patch\":\"@@ -1,1 +1,2 @@\\n class App {}\\n+// edit\"}]");
                return;
            }
            if (path.equals("/repos/acme-cli/platform/pulls/9/reviews") && "POST".equals(method)) {
                respond(ex, 200, "{\"id\":4001,\"html_url\":\"https://github.com/acme-cli/platform/pull/9#review-4001\",\"state\":\"COMMENTED\"}");
                return;
            }
            if (path.equals("/repos/acme-cli/platform/statuses/" + SHA) && "POST".equals(method)) {
                respond(ex, 201, "{\"id\":5001,\"state\":\"success\",\"context\":\"evo/architecture-gate\",\"description\":\"Gate passed\"}");
                return;
            }
            if (path.equals("/repos/acme-cli/platform/code-scanning/sarifs") && "POST".equals(method)) {
                respond(ex, 202, "{\"id\":\"cli-sarif-9\",\"url\":\"https://api.github.com/sarif/9\"}");
                return;
            }
            if (path.equals("/repos/acme-cli/platform/issues") && "POST".equals(method)) {
                respond(ex, 201, "{\"number\":88,\"html_url\":\"https://github.com/acme-cli/platform/issues/88\",\"title\":\"CLI Issue\",\"state\":\"open\"}");
                return;
            }
            respond(ex, 404, "{\"message\":\"Not Found\"}");
        });
        ghMock.start();

        URI mockUri = URI.create("http://127.0.0.1:" + ghMock.getAddress().getPort());
        GitHubRestClient.Transport transport = loopbackTransport();
        GitHubCommand.ContextFactory factory = new GitHubCommand.ContextFactory() {
            @Override public GitHubTokenBroker tokenBroker() {
                return new GitHubTokenBroker(k -> "EVO_GITHUB_TOKEN".equals(k) ? "ghp_cli_tok_1234567890" : null, h -> Optional.empty());
            }
            @Override public GitHubRestClient restClient(GitHubTokenBroker broker, GitHostPolicy hostPolicy) {
                return new GitHubRestClient(broker, transport, mockUri);
            }
            @Override public Optional<Path> localBareRemoteOverride() { return Optional.empty(); }
            @Override public GitHubOAuthService oauthService(UserSessionStore sessions, GitHostPolicy hostPolicy,
                    Optional<String> clientIdOverride, Optional<String> hostOverride) {
                var cfg = new GitHubOAuthService.Config(false, "cli-client-id", "", "github.com",
                        GitHubOAuthService.DEFAULT_SCOPES, false, Optional.empty(), Optional.of(mockUri), Optional.of(mockUri));
                return new GitHubOAuthService(cfg, sessions, transport);
            }
        };

        // Write minimal graph + policy report files for sarif/review/issue CLI tests
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("https://github.com/acme-cli/platform");
        var snap = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("cli-snap"));
        var evSnap = GraphEvidence.capture("repository.snapshot", Map.of("repository", "https://github.com/acme-cli/platform", "revision", SHA));
        String appNodeId = "type:" + ContentDigest.sha256Utf8("com.acme.App").value();
        String dbNodeId = "type:" + ContentDigest.sha256Utf8("com.acme.Db").value();
        String edgeId = "edge:" + ContentDigest.sha256Utf8("e-cli").value();
        var evDecl = GraphEvidence.capture("java.declaration", Map.of(
                "span", Map.of("path", "src/App.java", "startLine", 1, "endLine", 1, "startColumn", 1)));
        var graph = new KnowledgeGraph(snap,
                List.of(new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), evSnap.identity())),
                List.of(new KnowledgeGraph.Node(appNodeId, snap.value(), "TYPE", List.of(evDecl.identity()))),
                List.of(), List.of(evSnap, evDecl));
        Path graphFile = tempDir.resolve("snap.graph.json");
        Files.writeString(graphFile, new GraphSnapshotCodec().encode(graph), StandardCharsets.UTF_8);

        var ruleHeader = new ArchitecturePolicy.Header("acme.cli-rule", "1",
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
        var srcEv = new PolicyEvaluation.SourceEvidence(
                new SourceSpan(SourceDocumentIdentity.from(repoId, "src/App.java"), 1, 1, 1, 12),
                ContentDigest.sha256Utf8("class App {}"), evDecl.identity());
        var bundle = new PolicyEvaluation.EvidenceBundle(
                snap, Optional.empty(), graph.identity(), snap.value(),
                ContentDigest.sha256Utf8("pol"), ContentDigest.sha256Utf8("pol-src"),
                ContentDigest.sha256Utf8("comp"), ContentDigest.sha256Utf8("map"),
                List.of(), List.of(), List.of(),
                rule, req, ContentDigest.sha256Utf8("q"), pred, evalRegion,
                List.of(appNodeId, dbNodeId),
                List.of(), List.of(), List.of(srcEv), List.of(),
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
        var report = new PublishedReports.Report(eval, Optional.empty(), Optional.empty());
        Path reportFile = tempDir.resolve("report.json");
        Files.writeString(reportFile, CanonicalJson.write(report), StandardCharsets.UTF_8);

        try {
            // 1. evo login (Device Flow polling)
            var loginRes = run(tempDir, factory, "--json", "login", "--client-id", "cli-client-id", "--poll-ms", "10", "--max-polls", "5");
            assertEquals(0, loginRes.code(), loginRes.err());
            assertTrue(loginRes.out().contains("\"login\":\"cliuser\""));
            assertFalse(loginRes.out().contains("gho_cli_device_tok"), "Token must NEVER appear in CLI stdout");

            // 2. evo whoami & evo logout
            var whoRes = run(tempDir, factory, "--json", "whoami");
            assertEquals(0, whoRes.code());
            assertTrue(whoRes.out().contains("\"login\":\"cliuser\""));
            var outRes = run(tempDir, factory, "--json", "logout");
            assertEquals(0, outRes.code());
            assertTrue(outRes.out().contains("\"signedOut\":true"));

            // 3. evo github orgs, repos, ci-init
            var orgsRes = run(tempDir, factory, "--json", "github", "orgs");
            assertEquals(0, orgsRes.code());
            assertTrue(orgsRes.out().contains("acme-cli"));

            var reposRes = run(tempDir, factory, "--json", "github", "repos", "--q", "platform");
            assertEquals(0, reposRes.code());
            assertTrue(reposRes.out().contains("acme-cli/platform"));

            Path wfFile = tempDir.resolve("gate.yml");
            var ciRes = run(tempDir, factory, "github", "ci-init", "acme-cli/platform", "--out", wfFile.toString());
            assertEquals(0, ciRes.code());
            assertTrue(Files.readString(wfFile, StandardCharsets.UTF_8).contains("upload-sarif@v3"));

            // 4. evo pr status: rejected without --confirm, succeeds with --confirm
            var statusDenied = run(tempDir, factory, "pr", "status", "acme-cli/platform", "--sha", SHA, "--state", "success", "--desc", "OK");
            assertEquals(2, statusDenied.code());
            var statusOk = run(tempDir, factory, "--json", "pr", "status", "acme-cli/platform", "--sha", SHA, "--state", "success", "--desc", "OK", "--confirm");
            assertEquals(0, statusOk.code(), statusOk.err());
            assertTrue(statusOk.out().contains("\"id\":5001"));

            // 5. evo pr sarif export & confirmed upload
            Path sarifOut = tempDir.resolve("out.sarif");
            var sarifRes = run(tempDir, factory, "--json", "pr", "sarif",
                    "--graph-file", graphFile.toString(), "--report-file", reportFile.toString(),
                    "--out", sarifOut.toString(), "--repo", "acme-cli/platform",
                    "--sha", SHA, "--ref", "refs/heads/main", "--upload", "--confirm");
            assertEquals(0, sarifRes.code(), sarifRes.err());
            assertTrue(sarifRes.out().contains("\"sarifId\":\"cli-sarif-9\""));
            assertTrue(Files.readString(sarifOut, StandardCharsets.UTF_8).contains("acme.cli-rule@1"));

            // 6. evo pr review preview + confirmed publish
            var revDry = run(tempDir, factory, "--json", "pr", "review", "acme-cli/platform", "--pr", "9",
                    "--graph-file", graphFile.toString(), "--report-file", reportFile.toString());
            assertEquals(0, revDry.code(), revDry.err());
            String revDigest = extractNestedField(revDry.out(), "plan", "reviewPlanDigest");
            var revPub = run(tempDir, factory, "--json", "pr", "review", "acme-cli/platform", "--pr", "9",
                    "--graph-file", graphFile.toString(), "--report-file", reportFile.toString(), "--confirm", revDigest);
            assertEquals(0, revPub.code(), revPub.err());
            assertTrue(revPub.out().contains("\"reviewId\":4001"));

            // 7. evo issue create dry-run + confirmed publish
            var issDry = run(tempDir, factory, "--json", "issue", "create", "acme-cli/platform",
                    "--graph-file", graphFile.toString(), "--report-file", reportFile.toString());
            assertEquals(0, issDry.code(), issDry.err());
            String issDigest = extractNestedField(issDry.out(), "plan", "issuePlanDigest");
            var issPub = run(tempDir, factory, "--json", "issue", "create", "acme-cli/platform",
                    "--graph-file", graphFile.toString(), "--report-file", reportFile.toString(), "--confirm", issDigest);
            assertEquals(0, issPub.code(), issPub.err());
            assertTrue(issPub.out().contains("\"issueNumber\":88"));

            // 8. evo users list (read-only SQLite account store inspection, zero password/secret exposure)
            Path accountsDb = tempDir.resolve("custom-accounts.db");
            try (var acctStore = new com.evolution.backend.auth.UserAccountStore(accountsDb)) {
                var admin = acctStore.register("admin@cli.dev", "cliadmin", Optional.of("CLI Admin"), "CliSecret!123".toCharArray());
                acctStore.linkGitHub(admin.userId(), "9001", "cliadmin-gh", "https://avatars.githubusercontent.com/u/9001");
                acctStore.register("member@cli.dev", "climember", Optional.empty(), "MemberSec!456".toCharArray());
            }
            var usersRes = run(tempDir, factory, "--json", "users", "list", "--db", accountsDb.toString());
            assertEquals(0, usersRes.code(), usersRes.err());
            assertTrue(usersRes.out().contains("\"login\":\"cliadmin\""));
            assertTrue(usersRes.out().contains("\"role\":\"ADMIN\""));
            assertTrue(usersRes.out().contains("\"githubLogin\":\"cliadmin-gh\""));
            assertTrue(usersRes.out().contains("\"login\":\"climember\""));
            assertTrue(usersRes.out().contains("\"role\":\"MEMBER\""));
            assertFalse(usersRes.out().contains("CliSecret!123"));
            assertFalse(usersRes.out().contains("password"));
        } finally {
            ghMock.stop(0);
        }
    }

    private record CliRun(int code, String out, String err) {}

    private static CliRun run(Path cwd, GitHubCommand.ContextFactory factory, String... args) {
        var outSw = new StringWriter();
        var errSw = new StringWriter();
        var app = new EvoApp(cwd, new StringReader(""), new PrintWriter(outSw), new PrintWriter(errSw), false, Map.of())
                .withGitHubFactory(factory);
        int code = app.execute(args);
        return new CliRun(code, outSw.toString(), errSw.toString());
    }

    private static String extractNestedField(String json, String outerKey, String innerKey) {
        var root = GraphJson.object(GraphJson.read(json, GraphJson.Limits.defaults()));
        var data = GraphJson.object(root.get("data"));
        var inner = GraphJson.object(data.get(outerKey));
        return String.valueOf(inner.get(innerKey));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static GitHubRestClient.Transport loopbackTransport() {
        var http = HttpClient.newHttpClient();
        return (uri, method, headers, body, cancelled) -> {
            var b = HttpRequest.newBuilder(uri);
            headers.forEach(b::header);
            if ("POST".equals(method)) b.POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
            else b.GET();
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
    }
}
