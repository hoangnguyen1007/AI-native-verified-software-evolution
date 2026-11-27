package com.evolution.cli;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.github.GitHubRestClient;
import com.evolution.backend.github.GitHubTokenBroker;
import com.evolution.backend.query.CapturedRepair;
import com.evolution.backend.query.FindingDiagnosis;
import com.evolution.backend.query.RepairPatch;
import com.evolution.backend.query.RepairProposalStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class EvoGitHubCliTest {

    private static GitHubRestClient.Transport loopbackTransport() {
        var http = HttpClient.newHttpClient();
        return (uri, method, headers, body, cancelled) -> {
            var builder = HttpRequest.newBuilder(uri);
            headers.forEach(builder::header);
            var pub = body == null || body.length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body);
            builder.method(method, pub);
            try {
                var resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                var map = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
                resp.headers().map().forEach((k, v) -> map.put(k, String.join(", ", v)));
                return new GitHubRestClient.RawResponse(resp.statusCode(), map, resp.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        };
    }

    private static void respondJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String runGit(Path dir, List<String> args) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(args);
        var pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_AUTHOR_NAME", "Evo CLI Test");
        pb.environment().put("GIT_AUTHOR_EMAIL", "cli@evo.local");
        pb.environment().put("GIT_COMMITTER_NAME", "Evo CLI Test");
        pb.environment().put("GIT_COMMITTER_EMAIL", "cli@evo.local");
        var proc = pb.start();
        var out = new ByteArrayOutputStream();
        proc.getInputStream().transferTo(out);
        assertTrue(proc.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, proc.exitValue(), out.toString(StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int runCli(Path cwd, GitHubCommand.ContextFactory factory, StringWriter out, StringWriter err, String... args) {
        var app = new EvoApp(cwd, new StringReader(""), new PrintWriter(out, true), new PrintWriter(err, true), false, Map.of())
                .withGitHubFactory(factory);
        return app.execute(args);
    }

    @Test
    void githubDiscoveryAndConfirmedPrOpenCliWorkOffline(@TempDir Path tempDir) throws Exception {
        // Seed local bare Git remote
        Path bare = tempDir.resolve("origin.git");
        Files.createDirectories(bare);
        runGit(bare, List.of("init", "--bare", "--initial-branch=main"));

        Path seed = tempDir.resolve("seed");
        Files.createDirectories(seed);
        runGit(seed, List.of("init", "--initial-branch=main"));
        String relPath = "src/App.java";
        String before = "class App { int v() { return 1; } }\n";
        String after = "class App { int v() { return 2; } }\n";
        Files.createDirectories(seed.resolve("src"));
        Files.writeString(seed.resolve(relPath), before, StandardCharsets.UTF_8);
        runGit(seed, List.of("add", relPath));
        runGit(seed, List.of("commit", "-m", "init"));
        runGit(seed, List.of("remote", "add", "origin", bare.toString()));
        runGit(seed, List.of("push", "origin", "main"));

        var prPosts = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/user")) {
                respondJson(ex, 200, "{\"login\":\"cli-tester\"}");
                return;
            }
            if (path.equals("/repos/acme/cli-demo") && "GET".equals(method)) {
                respondJson(ex, 200, "{\"full_name\":\"acme/cli-demo\",\"default_branch\":\"main\",\"private\":false,\"html_url\":\"https://github.com/acme/cli-demo\"}");
                return;
            }
            if (path.equals("/repos/acme/cli-demo/branches")) {
                respondJson(ex, 200, "[{\"name\":\"main\",\"protected\":true,\"commit\":{\"sha\":\"1234567890\"}}]");
                return;
            }
            if (path.equals("/repos/acme/cli-demo/pulls") && "GET".equals(method)) {
                respondJson(ex, 200, "[{\"number\":3,\"title\":\"Fix cycle\",\"state\":\"open\",\"draft\":false,\"html_url\":\"https://github.com/acme/cli-demo/pull/3\",\"user\":{\"login\":\"dev\"},\"base\":{\"ref\":\"main\",\"sha\":\"111\"},\"head\":{\"ref\":\"fix/cycle\",\"sha\":\"222\"}}]");
                return;
            }
            if (path.equals("/repos/acme/cli-demo/pulls") && "POST".equals(method)) {
                prPosts.incrementAndGet();
                respondJson(ex, 201, "{\"number\":99,\"title\":\"Repair\",\"state\":\"open\",\"draft\":true,\"html_url\":\"https://github.com/acme/cli-demo/pull/99\",\"user\":{\"login\":\"cli-tester\"},\"base\":{\"ref\":\"main\",\"sha\":\"111\"},\"head\":{\"ref\":\"evo/cli-repair\",\"sha\":\"222\"}}");
                return;
            }
            respondJson(ex, 404, "{\"message\":\"Not Found\"}");
        });
        server.start();

        try {
            URI mockBase = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            GitHubCommand.ContextFactory factory = new GitHubCommand.ContextFactory() {
                @Override
                public GitHubTokenBroker tokenBroker() {
                    var b = new GitHubTokenBroker(k -> null, h -> Optional.empty());
                    b.setSessionToken("ghp_clitesttoken1234567890".toCharArray(), Optional.empty());
                    return b;
                }

                @Override
                public GitHubRestClient restClient(GitHubTokenBroker broker, GitHostPolicy hostPolicy) {
                    return new GitHubRestClient(broker, loopbackTransport(), mockBase);
                }

                @Override
                public Optional<Path> localBareRemoteOverride() {
                    return Optional.of(bare);
                }
            };

            // 1. evo github auth status --json
            var out1 = new StringWriter();
            var err1 = new StringWriter();
            assertEquals(0, runCli(tempDir, factory, out1, err1, "--json", "github", "auth", "status"), err1.toString());
            assertTrue(out1.toString().contains("\"login\":\"cli-tester\""));
            assertFalse(out1.toString().contains("ghp_clitest"));

            // 2. evo github branches acme/cli-demo
            var out2 = new StringWriter();
            var err2 = new StringWriter();
            assertEquals(0, runCli(tempDir, factory, out2, err2, "github", "branches", "acme/cli-demo"), err2.toString());
            assertTrue(out2.toString().contains("main"));

            // 3. evo github pulls acme/cli-demo
            var out3 = new StringWriter();
            var err3 = new StringWriter();
            assertEquals(0, runCli(tempDir, factory, out3, err3, "github", "pulls", "acme/cli-demo"), err3.toString());
            assertTrue(out3.toString().contains("#3"));

            // 4. Write proposal JSON and test evo pr open (dry-run vs --confirm <publishPlanDigest>)
            var repoId = RepositoryIdentity.fromCanonicalCoordinate("https://github.com/acme/cli-demo");
            var snap = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("snap"));
            var ev = GraphEvidence.capture("repository.snapshot", Map.of());
            var graph = new KnowledgeGraph(snap, List.of(new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), ev.identity())),
                    List.of(), List.of(), List.of(ev));
            String patch = RepairPatch.file(relPath, before, after);
            var edit = new CapturedRepair.Edit(relPath, ContentDigest.sha256Utf8(before), ContentDigest.sha256Utf8(after),
                    ContentDigest.sha256Utf8("b"), List.of());
            var pin = new FindingDiagnosis.Pin(snap, Optional.empty(), graph.identity(), snap.value(),
                    ContentDigest.sha256Utf8("r"), ContentDigest.sha256Utf8("f"), ContentDigest.sha256Utf8("b"));
            var proof = new FindingDiagnosis.Proof(new VersionedIdentifier("rule.x", "1"), ArchitectureQuery.Scope.TYPE,
                    "A", "B", "graph.depends-on", List.of("e"), ArchitectureQuery.Classification.MUST, ContentDigest.sha256Utf8("reg"));
            var cand = new CapturedRepair.Candidate(pin, ContentDigest.sha256Utf8("d"), proof, List.of(),
                    CapturedRepair.Strategy.RECIPE, Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(edit), List.of(), ContentDigest.sha256Utf8(patch), patch, "SYNTAX_ONLY");
            var propId = CanonicalJson.sha256(List.of(CapturedRepair.VERSION, cand));
            var proposal = new CapturedRepair.Proposal(propId, cand);
            Path proposalFile = tempDir.resolve("proposal.json");
            Files.writeString(proposalFile, CanonicalJson.write(proposal), StandardCharsets.UTF_8);

            // Dry-run without --confirm: exits 0, prints plan + publishPlanDigest, performs ZERO pushes/PRs!
            var outDry = new StringWriter();
            var errDry = new StringWriter();
            assertEquals(0, runCli(tempDir, factory, outDry, errDry, "--json", "pr", "open",
                    proposalFile.toString(), "--repo", "acme/cli-demo",
                    "--head", "evo/cli-repair", "--allow-unverified"),
                    errDry.toString());
            assertEquals(0, prPosts.get(), "Dry-run without --confirm must never open a PR");
            var dryRoot = com.evolution.analysis.graph.GraphJson.object(
                    com.evolution.analysis.graph.GraphJson.read(outDry.toString(), com.evolution.analysis.graph.GraphJson.Limits.defaults()));
            var dryData = com.evolution.analysis.graph.GraphJson.object(dryRoot.get("data"));
            assertEquals(Boolean.TRUE, dryData.get("dryRun"));
            var dryPlan = com.evolution.analysis.graph.GraphJson.object(dryData.get("plan"));
            String planDigest = String.valueOf(dryPlan.get("publishPlanDigest"));
            assertTrue(planDigest.startsWith("sha256:"));

            // Mismatched --confirm digest must fail closed with exit 2 and ZERO pushes
            var outBad = new StringWriter();
            var errBad = new StringWriter();
            assertEquals(2, runCli(tempDir, factory, outBad, errBad, "pr", "open",
                    proposalFile.toString(), "--repo", "acme/cli-demo",
                    "--head", "evo/cli-repair", "--allow-unverified",
                    "--confirm", ContentDigest.sha256Utf8("wrong-digest").value()));
            assertEquals(0, prPosts.get());

            // Confirmed push + PR open with exact --confirm <publishPlanDigest>
            var outPub = new StringWriter();
            var errPub = new StringWriter();
            assertEquals(0, runCli(tempDir, factory, outPub, errPub, "pr", "open",
                    proposalFile.toString(), "--repo", "acme/cli-demo",
                    "--head", "evo/cli-repair", "--allow-unverified", "--confirm", planDigest),
                    errPub.toString());
            assertEquals(1, prPosts.get());
            assertTrue(outPub.toString().contains("#99"), outPub.toString());
            assertEquals(after, runGit(bare, List.of("show", "refs/heads/evo/cli-repair:" + relPath)));
        } finally {
            server.stop(0);
        }
    }
}
