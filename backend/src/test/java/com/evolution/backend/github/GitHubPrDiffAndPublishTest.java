package com.evolution.backend.github;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphStore;
import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.analysis.RepositoryConnections;
import com.evolution.backend.query.CapturedRepair;
import com.evolution.backend.query.FindingDiagnosis;
import com.evolution.backend.query.PublishedReports;
import com.evolution.backend.query.RepairPatch;
import com.evolution.backend.query.RepairProposalStore;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GitHubPrDiffAndPublishTest {

    private static String runGit(Path dir, List<String> args) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(args);
        var pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_AUTHOR_NAME", "Evo Test");
        pb.environment().put("GIT_AUTHOR_EMAIL", "test@evo.local");
        pb.environment().put("GIT_COMMITTER_NAME", "Evo Test");
        pb.environment().put("GIT_COMMITTER_EMAIL", "test@evo.local");
        var proc = pb.start();
        var out = new ByteArrayOutputStream();
        proc.getInputStream().transferTo(out);
        assertTrue(proc.waitFor(30, TimeUnit.SECONDS), "git command timed out: " + args);
        String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, proc.exitValue(), "git failed: " + args + "\n" + text);
        return text;
    }

    private static KnowledgeGraph minimalGraph(String repoUrl, String seed, int gapCount) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate(repoUrl);
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8(seed));
        var ctxEv = GraphEvidence.capture("repository.snapshot", Map.of("seed", seed));
        var evidence = new ArrayList<GraphEvidence>();
        evidence.add(ctxEv);
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        nodes.add(new KnowledgeGraph.Node(repo.value(), snap.value(), "REPOSITORY", List.of(ctxEv.identity())));
        for (int i = 0; i < gapCount; i++) {
            var gapEv = GraphEvidence.capture("evidence.gap", Map.of("reasonCode", "UNRESOLVED_TYPE", "idx", i));
            evidence.add(gapEv);
            nodes.add(new KnowledgeGraph.Node("gap:" + gapEv.identity().value(), snap.value(), "CAPABILITY_GAP", List.of(gapEv.identity())));
        }
        return new KnowledgeGraph(
                snap,
                List.of(new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), ctxEv.identity())),
                nodes,
                List.of(),
                evidence);
    }

    private static CapturedRepair.Proposal buildProposal(KnowledgeGraph graph, String relPath, String beforeText, String afterText) {
        var beforeDigest = ContentDigest.sha256Utf8(beforeText);
        var afterDigest = ContentDigest.sha256Utf8(afterText);
        String patch = RepairPatch.file(relPath, beforeText, afterText);
        var patchDigest = ContentDigest.sha256Utf8(patch);
        var pin = new FindingDiagnosis.Pin(graph.snapshot(), Optional.empty(), graph.identity(),
                graph.snapshot().value(), ContentDigest.sha256Utf8("rep"), ContentDigest.sha256Utf8("finding"),
                ContentDigest.sha256Utf8("bundle"));
        var proof = new FindingDiagnosis.Proof(new VersionedIdentifier("rule.demo", "1"),
                ArchitectureQuery.Scope.TYPE, "A", "B", "graph.depends-on", List.of("e1"),
                ArchitectureQuery.Classification.MUST, ContentDigest.sha256Utf8("region"));
        var edit = new CapturedRepair.Edit(relPath, beforeDigest, afterDigest, ContentDigest.sha256Utf8("bytes"), List.of());
        var candidate = new CapturedRepair.Candidate(pin, ContentDigest.sha256Utf8("diag"), proof, List.of(),
                CapturedRepair.Strategy.RECIPE, Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(edit), List.of(), patchDigest, patch, "SYNTAX_ONLY");
        var id = com.evolution.analysis.contract.serialization.CanonicalJson.sha256(List.of(CapturedRepair.VERSION, candidate));
        return new CapturedRepair.Proposal(id, candidate);
    }

    @Test
    void prDiffComputesScoreAndGapDeltaAndGatesReviewCommentBehindConfirmation(@TempDir Path tempDir) throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        var postedCommentBody = new AtomicReference<String>();
        server.createContext("/repos/acme/demo", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.endsWith("/issues/12/comments") && "GET".equals(method)) {
                GitHubRestClientTest.respondJson(ex, 200, "[]", Map.of());
                return;
            }
            if (path.endsWith("/issues/12/comments") && "POST".equals(method)) {
                postedCommentBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                GitHubRestClientTest.respondJson(ex, 201,
                        "{\"id\":555,\"html_url\":\"https://github.com/acme/demo/pull/12#issuecomment-555\"}", Map.of());
                return;
            }
            GitHubRestClientTest.respondJson(ex, 404, "{\"message\":\"Not Found\"}", Map.of());
        });
        server.start();
        try (var broker = new GitHubTokenBroker(k -> null, h -> Optional.empty())) {
            broker.setSessionToken("ghp_difftesttoken1234567890".toCharArray(), Optional.empty());
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var rest = new GitHubRestClient(broker, GitHubRestClientTest.loopbackTransport(), base);

            var baseGraph = minimalGraph("https://github.com/acme/demo", "base-v1", 2);
            var headGraph = minimalGraph("https://github.com/acme/demo", "head-v2", 1);
            var graphs = new InMemoryGraphStore();
            graphs.save(baseGraph);
            graphs.save(headGraph);
            var reports = new PublishedReports();
            try (var jobs = new AnalysisJobs(List.of(), graphs, reports, AnalysisJobs.Limits.defaults())) {
                var connections = new RepositoryConnections(jobs, Path.of(System.getProperty("java.home")), 21,
                        tempDir.resolve("m2"), tempDir.resolve("store"), Optional.empty(), "git",
                        new GitHostPolicy());
                // Register a fake remote connection via reflection-free GitHubPrDiffService.compareGraphs
                var diffSvc = new GitHubPrDiffService(rest, jobs, connections, graphs, reports, tempDir.resolve("diffs"));
                var diff = diffSvc.compareGraphs("setup-1", "acme/demo", OptionalInt.of(12), Optional.of("Refactor core"),
                        Optional.of("https://github.com/acme/demo/pull/12"), "main", "aaaa1111", "feat/x", "bbbb2222",
                        "job-base", "job-head", baseGraph.identity(), headGraph.identity(), List.of("src/Demo.java"), () -> false);

                assertEquals("acme/demo", diff.repository());
                assertEquals(2, diff.gapDelta().baseTotalGaps());
                assertEquals(1, diff.gapDelta().headTotalGaps());
                assertEquals(-1, diff.gapDelta().deltaGaps());
                assertTrue(diff.markdownReport().contains("## Architecture Evolution Diff"));
                assertTrue(diff.markdownReport().contains(GitHubRestClient.COMMENT_MARKER));
                assertTrue(diffSvc.get(diff.diffId()).isPresent());

                // Unconfirmed review comment must be rejected
                assertThrows(IllegalArgumentException.class, () -> diffSvc.postReviewComment(
                        "setup-1", 12, diff.diffId(), diff.diffDigest(), false, () -> false));
                // Wrong digest must be rejected
                assertThrows(IllegalStateException.class, () -> diffSvc.postReviewComment(
                        "setup-1", 12, diff.diffId(), ContentDigest.sha256Utf8("wrong"), true, () -> false));
                assertNull(postedCommentBody.get());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void repairPublisherEnforcesTwoStepConfirmationDriftDetectionAndPushesToBareRemote(@TempDir Path tempDir) throws Exception {
        // 1. Seed a real local bare Git remote with main branch containing src/main/java/Demo.java
        Path bareRemote = tempDir.resolve("origin.git");
        Files.createDirectories(bareRemote);
        runGit(bareRemote, List.of("init", "--bare", "--initial-branch=main"));

        Path seedWork = tempDir.resolve("seed");
        Files.createDirectories(seedWork);
        runGit(seedWork, List.of("init", "--initial-branch=main"));
        String relPath = "src/main/java/Demo.java";
        String beforeSrc = "public class Demo {\n    String status() { return \"old\"; }\n}\n";
        String afterSrc = "public class Demo {\n    String status() { return \"fixed\"; }\n}\n";
        Path javaFile = seedWork.resolve(relPath);
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, beforeSrc, StandardCharsets.UTF_8);
        runGit(seedWork, List.of("add", relPath));
        runGit(seedWork, List.of("commit", "-m", "initial commit"));
        runGit(seedWork, List.of("remote", "add", "origin", bareRemote.toString()));
        runGit(seedWork, List.of("push", "origin", "main"));
        String baseCommitSha = runGit(seedWork, List.of("rev-parse", "HEAD")).strip();

        // 2. Start loopback mock GitHub REST API for repository metadata + PR creation
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        var prRequestBody = new AtomicReference<String>();
        server.createContext("/repos/acme/demo", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/repos/acme/demo") && "GET".equals(method)) {
                GitHubRestClientTest.respondJson(ex, 200,
                        "{\"full_name\":\"acme/demo\",\"default_branch\":\"main\",\"private\":false,\"html_url\":\"https://github.com/acme/demo\"}",
                        Map.of());
                return;
            }
            if (path.equals("/repos/acme/demo/pulls") && "POST".equals(method)) {
                prRequestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                GitHubRestClientTest.respondJson(ex, 201,
                        "{\"number\":88,\"title\":\"Repair\",\"state\":\"open\",\"draft\":true,\"html_url\":\"https://github.com/acme/demo/pull/88\","
                                + "\"user\":{\"login\":\"evo-bot\"},\"base\":{\"ref\":\"main\",\"sha\":\"" + baseCommitSha + "\"},"
                                + "\"head\":{\"ref\":\"evo/repair-demo\",\"sha\":\"deadbeef\"}}",
                        Map.of());
                return;
            }
            GitHubRestClientTest.respondJson(ex, 404, "{\"message\":\"Not Found\"}", Map.of());
        });
        server.start();

        try (var broker = new GitHubTokenBroker(k -> null, h -> Optional.empty())) {
            broker.setSessionToken("ghp_publishtesttoken123456".toCharArray(), Optional.empty());
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var rest = new GitHubRestClient(broker, GitHubRestClientTest.loopbackTransport(), base);

            var baseGraph = minimalGraph("https://github.com/acme/demo", "seed-1", 0);
            var proposal = buildProposal(baseGraph, relPath, beforeSrc, afterSrc);
            var repairStore = new RepairProposalStore(tempDir.resolve("repairs"));
            repairStore.put(proposal);

            var publisher = new GitHubRepairPublisher(rest, broker, repairStore, null, new GitHostPolicy(),
                    tempDir.resolve("pub-work"), "git", Optional.of(bareRemote));

            // Guard 1: Unverified proposal without allowUnverified=true must fail closed
            var unverifiedErr = assertThrows(GitHubRepairPublisher.PublishException.class, () -> publisher.prepare(
                    new GitHubRepairPublisher.PrepareRequest(proposal.identity(), Optional.empty(), Optional.of("acme/demo"),
                            Optional.of("main"), Optional.of("evo/repair-demo"), Optional.empty(), Optional.of(false), false, Optional.empty()),
                    () -> false));
            assertEquals("REPAIR_UNVERIFIED_REQUIRES_OPT_IN", unverifiedErr.code());

            // Guard 2: Protected branch target must fail closed
            var protectedErr = assertThrows(GitHubRepairPublisher.PublishException.class, () -> publisher.prepare(
                    new GitHubRepairPublisher.PrepareRequest(proposal.identity(), Optional.empty(), Optional.of("acme/demo"),
                            Optional.of("main"), Optional.of("main"), Optional.empty(), Optional.of(true), true, Optional.empty()),
                    () -> false));
            assertEquals("PROTECTED_BRANCH_DENIED", protectedErr.code());

            // Step 1 (dry-run): prepare with allowUnverified=true forces draft=true + warning banner, zero pushes!
            var plan = publisher.prepare(
                    new GitHubRepairPublisher.PrepareRequest(proposal.identity(), Optional.empty(), Optional.of("acme/demo"),
                            Optional.of("main"), Optional.of("evo/repair-demo"), Optional.empty(), Optional.of(false), true, Optional.empty()),
                    () -> false);
            assertTrue(plan.draft(), "Unverified proposal must force draft=true");
            assertTrue(plan.bodyMarkdown().contains("UNVERIFIED REPAIR PROPOSAL"));
            assertNull(prRequestBody.get(), "prepare() must perform zero GitHub PR writes");

            // Guard 3: publish with confirmedByUser=false must fail
            var noConfirmErr = assertThrows(GitHubRepairPublisher.PublishException.class, () -> publisher.publish(
                    new GitHubRepairPublisher.PublishRequest(plan.confirmationToken(), plan.publishPlanDigest(), false,
                            Optional.empty(), Optional.empty(), Optional.empty()),
                    () -> false));
            assertEquals("EXPLICIT_USER_CONFIRMATION_REQUIRED", noConfirmErr.code());

            // Guard 4: Base branch drift detection -> modify Demo.java on bare remote before publish
            Files.writeString(javaFile, "public class Demo {\n    String status() { return \"drifted\"; }\n}\n", StandardCharsets.UTF_8);
            runGit(seedWork, List.of("add", relPath));
            runGit(seedWork, List.of("commit", "-m", "drifted base"));
            runGit(seedWork, List.of("push", "origin", "main"));

            var driftErr = assertThrows(GitHubRepairPublisher.PublishException.class, () -> publisher.publish(
                    new GitHubRepairPublisher.PublishRequest(plan.confirmationToken(), plan.publishPlanDigest(), true,
                            Optional.empty(), Optional.empty(), Optional.empty()),
                    () -> false));
            assertEquals("BASE_BRANCH_DRIFTED", driftErr.code());
            assertNull(prRequestBody.get(), "Drifted base must abort before pushing or creating PR");

            // Restore main to original commit & run clean Step 1 -> Step 2 publish
            runGit(seedWork, List.of("reset", "--hard", baseCommitSha));
            runGit(seedWork, List.of("push", "--force", "origin", "main"));

            var plan2 = publisher.prepare(
                    new GitHubRepairPublisher.PrepareRequest(proposal.identity(), Optional.empty(), Optional.of("acme/demo"),
                            Optional.of("main"), Optional.of("evo/repair-demo"), Optional.of("Fix Demo status"),
                            Optional.of(false), true, Optional.empty()),
                    () -> false);
            var receipt = publisher.publish(
                    new GitHubRepairPublisher.PublishRequest(plan2.confirmationToken(), plan2.publishPlanDigest(), true,
                            Optional.empty(), Optional.of("Custom body without warning"), Optional.of(false)),
                    () -> false);

            assertEquals(88, receipt.prNumber());
            assertEquals("evo/repair-demo", receipt.branch());
            assertTrue(receipt.draft(), "Unverified PR must stay draft even if draftOverride=false");
            assertNotNull(prRequestBody.get());
            assertTrue(prRequestBody.get().contains("UNVERIFIED REPAIR PROPOSAL"),
                    "Unverified warning banner must be non-removable");

            // Verify real branch & commit provenance trailers exist in the bare Git remote!
            String commitLog = runGit(bareRemote, List.of("log", "-1", "refs/heads/evo/repair-demo"));
            assertTrue(commitLog.contains("Evo-Proposal-Digest: " + proposal.identity().value()));
            assertTrue(commitLog.contains("Evo-Patch-Digest: " + proposal.candidate().patchDigest().value()));
            assertTrue(commitLog.contains("Evo-Verdict: UNVERIFIED"));
            String pushedFile = runGit(bareRemote, List.of("show", "refs/heads/evo/repair-demo:" + relPath));
            assertEquals(afterSrc, pushedFile);

            // Guard 5: Replaying consumed single-use confirmation token must fail
            var replayErr = assertThrows(GitHubRepairPublisher.PublishException.class, () -> publisher.publish(
                    new GitHubRepairPublisher.PublishRequest(plan2.confirmationToken(), plan2.publishPlanDigest(), true,
                            Optional.empty(), Optional.empty(), Optional.empty()),
                    () -> false));
            assertEquals("CONFIRMATION_TOKEN_INVALID_OR_EXPIRED", replayErr.code());
        } finally {
            server.stop(0);
        }
    }
}
