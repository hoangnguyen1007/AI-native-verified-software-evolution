package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.query.ArchitectureQueryApi;
import com.evolution.backend.query.PublishedReports;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end Wave 2B verification through {@link LocalAnalysisRunner} and {@link AnalysisJobs}:
 * <ul>
 *   <li>3-stage progressive lifecycle: {@code PRELIMINARY_STRUCTURE} -&gt; {@code PARTIAL_CONTEXTS}
 *       -&gt; {@code DEEP_SEMANTIC_COMPLETE} with ephemeral preview isolation and {@code 410 PREVIEW_SUPERSEDED}.</li>
 *   <li>4-layer incremental delta re-analysis equivalence:
 *       {@code CleanFullRun(B).equals(IncrementalRun(A -> B))} bit-for-bit with live {@code IncrementalMetrics}
 *       and durable {@code m8.4-v5} persistence.</li>
 * </ul>
 */
class ProgressiveAndIncrementalRunnerTest {
    @TempDir
    Path temp;

    @Test
    void progressiveThreeStageLifecyclePublishesPreviewsNeverTouchesDiskAndSupersedesOnCompletion() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("prog-repo"));
        Path cache = Files.createDirectory(temp.resolve("prog-m2"));
        Path intake = Files.createDirectory(temp.resolve("prog-intake"));
        Path storeDir = Files.createDirectory(temp.resolve("prog-store"));

        writeMultiModuleRepo(repo, "return 1;");

        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:test:progressive-three-stage");
        var input = new LocalAnalysisRunner.Input(repo, repoId, Path.of(System.getProperty("java.home")), 21, cache,
                Optional.empty(), true, false, List.of(), Optional.empty(),
                LocalAnalysisRunner.SpringInput.autoMultiConfig());
        var runner = new LocalAnalysisRunner(input, intake, Optional.empty(),
                LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(), Optional.of(storeDir));

        long[] clock = { System.currentTimeMillis() };
        System.setProperty("evo.progressive.partialPreviewIntervalMs", "0");
        System.setProperty("evo.graph.binaryCacheMinBytes", "0");
        var previewStore = new WorkbenchPreviewStore(2, 5_000L, () -> clock[0]);
        var graphStore = new FileGraphStore(storeDir, 64_000_000L, GraphJson.StreamLimits.from(GraphJson.Limits.defaults()));
        var reports = new PublishedReports();

        try (var jobs = new AnalysisJobs(List.of(runner.registration("prog")), graphStore, reports,
                new AnalysisJobs.Limits(1, 2, 8, 120_000, 1000), null, previewStore)) {
            var connections = new RepositoryConnections(jobs, Path.of(System.getProperty("java.home")), 21,
                    cache, storeDir, Optional.empty());
            var api = WorkbenchApiMain.createWorkbenchQueryApi(graphStore, reports, jobs, connections, null, previewStore);

            // Submit job and subscribe to live SSE event stream immediately
            var submitted = jobs.submit("prog", "stage-test");
            UUID jobId = UUID.fromString(submitted.job().id());
            var observedEvents = new ArrayList<AnalysisJobEventBroker.JobStreamEvent>();
            var previewTiers = new ArrayList<String>();
            var previewGraphIds = new ArrayList<ContentDigest>();
            var completedModules = new HashSet<String>();
            boolean[] sawIncrementalSummary = { false };

            java.util.function.Consumer<AnalysisJobEventBroker.JobStreamEvent> recordEvent = ev -> {
                observedEvents.add(ev);
                var payload = GraphJson.object(GraphJson.read(ev.json(), GraphJson.Limits.defaults()));
                if ("preview_ready".equals(ev.event())) {
                    Object tier = payload.get("truthTier");
                    if (tier != null) previewTiers.add(tier.toString());
                    Object gid = payload.get("graphId");
                    if (gid != null) previewGraphIds.add(new ContentDigest(gid.toString()));
                } else if ("module_completed".equals(ev.event())) {
                    Object mod = payload.get("module");
                    if (mod != null) completedModules.add(mod.toString());
                } else if ("incremental_summary".equals(ev.event())) {
                    sawIncrementalSummary[0] = true;
                }
            };

            try (var sub = jobs.subscribe(jobId, 0L)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                while (System.nanoTime() < deadline) {
                    var ev = sub.poll(200, TimeUnit.MILLISECONDS);
                    if (ev != null) {
                        recordEvent.accept(ev);
                        if (ev.isTerminal()) {
                            break;
                        }
                    } else if (AnalysisJobs.terminal(jobs.get(jobId).state())) {
                        AnalysisJobEventBroker.JobStreamEvent tail;
                        while ((tail = sub.poll(20, TimeUnit.MILLISECONDS)) != null) {
                            recordEvent.accept(tail);
                        }
                        break;
                    }
                }
            }

            var finalView = jobs.get(jobId);
            assertTrue(finalView.state() == AnalysisJobs.State.SUCCEEDED || finalView.state() == AnalysisJobs.State.PARTIAL,
                    () -> "Unexpected final state: " + finalView);
            ContentDigest finalGraphId = finalView.result().orElseThrow().graph().orElseThrow();
            assertEquals(AnalysisJobs.TruthTier.DEEP_SEMANTIC_COMPLETE, jobs.truthTier(jobId));

            // 1. Verify Stage 1 PRELIMINARY_STRUCTURE -> Stage 2 PARTIAL_CONTEXTS -> Stage 3 DEEP_SEMANTIC_COMPLETE
            assertTrue(previewTiers.contains("PRELIMINARY_STRUCTURE"),
                    () -> "Expected Stage 1 PRELIMINARY_STRUCTURE preview, got tiers=" + previewTiers);
            assertTrue(previewTiers.contains("PARTIAL_CONTEXTS"),
                    () -> "Expected Stage 2 PARTIAL_CONTEXTS preview, got tiers=" + previewTiers);
            assertTrue(previewTiers.indexOf("PRELIMINARY_STRUCTURE") < previewTiers.indexOf("PARTIAL_CONTEXTS"),
                    () -> "Stage 1 must precede Stage 2, got " + previewTiers);
            assertTrue(completedModules.contains("mod-a") && completedModules.contains("mod-b"),
                    () -> "Expected module_completed events for mod-a & mod-b, got " + completedModules);
            assertTrue(sawIncrementalSummary[0], "Expected incremental_summary SSE event");

            // 2. Hard invariant: ephemeral preview graphs NEVER touch disk!
            for (ContentDigest pId : previewGraphIds) {
                assertNotEquals(finalGraphId, pId);
                assertFalse(Files.exists(storeDir.resolve(pId.value().substring(7) + ".graph.json")),
                        "Preview graph must NEVER be written to disk: " + pId);
                assertFalse(previewStore.verifyPublished(pId, () -> false));
            }
            assertTrue(Files.isRegularFile(storeDir.resolve(finalGraphId.value().substring(7) + ".graph.json")));
            assertTrue(Files.isRegularFile(
                    storeDir.resolve("graph-cache").resolve(finalGraphId.value().substring(7) + ".graph.bin")));

            // 3. Advance clock past grace period -> preview graph returns 410 PREVIEW_SUPERSEDED pointing to finalGraphId!
            clock[0] += 10_000L;
            ContentDigest supersededPreview = previewGraphIds.getLast();
            var goneResp = api.handle("GET", "/api/v1/graphs/" + supersededPreview.value(), null, new byte[0], () -> false);
            assertEquals(410, goneResp.status(), () -> "Expected 410 PREVIEW_SUPERSEDED, body="
                    + new String(goneResp.body(), StandardCharsets.UTF_8));
            String goneBody = new String(goneResp.body(), StandardCharsets.UTF_8);
            assertTrue(goneBody.contains("PREVIEW_SUPERSEDED") && goneBody.contains(finalGraphId.value()),
                    () -> "Expected supersededBy=" + finalGraphId + " in " + goneBody);

            // 4. Capabilities & Architecture Scoring v2 live over the authoritative published graph
            var capsResp = api.handle("GET", "/api/v1", null, new byte[0], () -> false);
            assertEquals(200, capsResp.status());
            String capsBody = new String(capsResp.body(), StandardCharsets.UTF_8);
            assertTrue(capsBody.contains("\"architectureScoreV2Enabled\":true"));
            assertTrue(capsBody.contains("\"progressiveAnalysisEnabled\":true"));
            assertTrue(capsBody.contains("\"incrementalDeltaEnabled\":true"));

            var scoreResp = api.handle("POST", "/api/v1/graphs/" + finalGraphId.value() + "/architecture-score",
                    "application/json", "{}".getBytes(StandardCharsets.UTF_8), () -> false);
            assertEquals(200, scoreResp.status(), () -> "Score v2 failed: "
                    + new String(scoreResp.body(), StandardCharsets.UTF_8));
            String scoreBody = new String(scoreResp.body(), StandardCharsets.UTF_8);
            assertTrue(scoreBody.contains("\"score\"") && scoreBody.contains("\"confidence\"")
                    && scoreBody.contains("\"dimensions\""), () -> "scoreBody=" + scoreBody);
        } finally {
            System.clearProperty("evo.progressive.partialPreviewIntervalMs");
            System.clearProperty("evo.graph.binaryCacheMinBytes");
        }
    }

    @Test
    void incrementalRunMatchesCleanFullRunBitForBitAndPersistsIncrementalMetricsV5() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("inc-repo"));
        Path cache = Files.createDirectory(temp.resolve("inc-m2"));
        Path intake = Files.createDirectory(temp.resolve("inc-intake"));
        Path storeDir = Files.createDirectory(temp.resolve("inc-store"));
        Path jobsDir = Files.createDirectory(temp.resolve("inc-jobs"));

        writeMultiModuleRepo(repo, "return 1;");
        // Backdate mtimes beyond the 2-second Racy-Git guard so L1 stat-cache hits deterministically
        backdateTree(repo, System.currentTimeMillis() - 10_000L);

        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:test:runner-inc-parity");
        var input = new LocalAnalysisRunner.Input(repo, repoId, Path.of(System.getProperty("java.home")), 21, cache,
                Optional.empty(), true, false, List.of(), Optional.empty(), LocalAnalysisRunner.SpringInput.absent());
        var sharedCaches = LocalAnalysisRunner.sharedCaches(intake);
        var runner = new LocalAnalysisRunner(input, intake, Optional.empty(),
                LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(), Optional.of(storeDir), sharedCaches);

        var graphStore = new FileGraphStore(storeDir, 64_000_000L, GraphJson.StreamLimits.from(GraphJson.Limits.defaults()));
        var reports = new PublishedReports();

        String snapshotA;
        ContentDigest graphA;
        long coldMs;
        long incMs;
        UUID job2Id;
        ContentDigest graphBIncremental;

        try (var disk = new AnalysisJobDiskStore(jobsDir);
             var jobs = new AnalysisJobs(List.of(runner.registration("inc")), graphStore, reports,
                     new AnalysisJobs.Limits(1, 2, 16, 120_000, 1000), disk)) {
            long t0 = System.nanoTime();
            var v1 = awaitDone(jobs, jobs.submit("inc", "snap-a").job().id());
            coldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(v1.state() == AnalysisJobs.State.SUCCEEDED || v1.state() == AnalysisJobs.State.PARTIAL);
            snapshotA = v1.result().orElseThrow().snapshot().orElseThrow().value();
            graphA = v1.result().orElseThrow().graph().orElseThrow();

            // Modify ONLY mod-a/Alpha.java method body (leaving mod-a ABI, mod-b, mod-c, web/client.ts untouched!)
            writeFile(repo.resolve("mod-a/src/main/java/a/Alpha.java"),
                    "package a;\npublic class Alpha {\n    public int value() { int y = 42; return y; }\n}\n");

            long t1 = System.nanoTime();
            var v2 = awaitDone(jobs, jobs.submit("inc", "snap-b").job().id());
            incMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1);
            assertTrue(v2.state() == AnalysisJobs.State.SUCCEEDED || v2.state() == AnalysisJobs.State.PARTIAL);
            job2Id = UUID.fromString(v2.id());
            graphBIncremental = v2.result().orElseThrow().graph().orElseThrow();
            assertNotEquals(graphA, graphBIncremental, "Modified method body must produce a new snapshot/graph");

            var incMetricsOpt = jobs.incrementalMetrics(job2Id);
            assertTrue(incMetricsOpt.isPresent(), "Expected IncrementalMetrics on incremental run");
            var incMetrics = incMetricsOpt.orElseThrow();
            assertEquals(Optional.of(snapshotA), incMetrics.baseSnapshotId(),
                    "baseSnapshotId must match prior sealed snapshot for 1-click Compare with Previous Snapshot");
            assertTrue(incMetrics.modifiedFiles() >= 1, () -> "Expected >=1 modified file, got " + incMetrics);
            assertTrue(incMetrics.reusedFiles() >= 3, () -> "Expected >=3 reused files, got " + incMetrics);
            assertTrue(incMetrics.reusedModules() >= 1, () -> "Expected >=1 reused module, got " + incMetrics);
            assertTrue(incMetrics.timeSavedEstimateMs() > 0L, () -> "Expected positive timeSavedEstimateMs, got " + incMetrics);

            var tel2 = jobs.telemetry(job2Id);
            assertTrue(tel2.cacheMetrics().getOrDefault("polyglotCacheHits", 0L) >= 1L,
                    () -> "Expected polyglot cache hit on unchanged web/client.ts, tel2=" + tel2);
        }

        // Verify durable m8.4-v5 persistence across AnalysisJobDiskStore reopen!
        try (var reopenedDisk = new AnalysisJobDiskStore(jobsDir);
             var reopenedJobs = new AnalysisJobs(List.of(runner.registration("inc")), graphStore, reports,
                     new AnalysisJobs.Limits(1, 2, 16, 120_000, 1000), reopenedDisk)) {
            var restoredMetrics = reopenedJobs.incrementalMetrics(job2Id);
            assertTrue(restoredMetrics.isPresent(), "Expected v5 IncrementalMetrics restored after restart");
            assertEquals(Optional.of(snapshotA), restoredMetrics.orElseThrow().baseSnapshotId());
        }

        // Now run a completely CLEAN full cold analysis of B with -Devo.incremental.disabled=true
        Path coldIntake = Files.createDirectory(temp.resolve("cold-b-intake"));
        Path coldStoreDir = Files.createDirectory(temp.resolve("cold-b-store"));
        var coldGraphStore = new FileGraphStore(coldStoreDir, 64_000_000L, GraphJson.StreamLimits.from(GraphJson.Limits.defaults()));
        var coldRunner = new LocalAnalysisRunner(input, coldIntake, Optional.empty(),
                LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(), Optional.of(coldStoreDir));

        ContentDigest graphBClean;
        System.setProperty("evo.incremental.disabled", "true");
        try (var jobs = new AnalysisJobs(List.of(coldRunner.registration("cold-b")), coldGraphStore, new PublishedReports(),
                new AnalysisJobs.Limits(1, 2, 8, 120_000, 1000))) {
            var vClean = awaitDone(jobs, jobs.submit("cold-b", "clean-b").job().id());
            graphBClean = vClean.result().orElseThrow().graph().orElseThrow();
        } finally {
            System.clearProperty("evo.incremental.disabled");
        }

        System.out.printf("[PERF-BENCH] LocalAnalysisRunner (3-module + polyglot): cold=%dms, incremental=%dms%n",
                coldMs, incMs);
        assertEquals(graphBClean, graphBIncremental,
                "CleanFullRun(B) MUST equal IncrementalRun(A -> B) bit-for-bit!");
    }

    private static void writeMultiModuleRepo(Path repo, String alphaBody) throws IOException {
        writeFile(repo.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>root</artifactId>"
                        + "<version>1.0</version><packaging>pom</packaging>"
                        + "<modules><module>mod-a</module><module>mod-b</module><module>mod-c</module></modules></project>");
        writeFile(repo.resolve("mod-a/pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<parent><groupId>demo</groupId><artifactId>root</artifactId><version>1.0</version></parent>"
                        + "<artifactId>mod-a</artifactId>"
                        + "<properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        writeFile(repo.resolve("mod-a/src/main/java/a/Alpha.java"),
                "package a;\npublic class Alpha {\n    public int value() { " + alphaBody + " }\n}\n");
        writeFile(repo.resolve("mod-a/src/main/java/a/AlphaHelper.java"),
                "package a;\npublic class AlphaHelper {\n    public int compute(Alpha a) { return a.value() + 1; }\n}\n");

        writeFile(repo.resolve("mod-b/pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<parent><groupId>demo</groupId><artifactId>root</artifactId><version>1.0</version></parent>"
                        + "<artifactId>mod-b</artifactId>"
                        + "<properties><maven.compiler.release>21</maven.compiler.release></properties>"
                        + "<dependencies><dependency><groupId>demo</groupId><artifactId>mod-a</artifactId><version>1.0</version></dependency></dependencies>"
                        + "</project>");
        writeFile(repo.resolve("mod-b/src/main/java/b/BetaService.java"),
                "package b;\nimport a.Alpha;\npublic class BetaService {\n    private final Alpha alpha = new Alpha();\n    public int run() { return alpha.value(); }\n}\n");

        writeFile(repo.resolve("mod-c/pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<parent><groupId>demo</groupId><artifactId>root</artifactId><version>1.0</version></parent>"
                        + "<artifactId>mod-c</artifactId>"
                        + "<properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        writeFile(repo.resolve("mod-c/src/main/java/c/GammaUtil.java"),
                "package c;\npublic class GammaUtil {\n    public String tag() { return \"gamma\"; }\n}\n");

        writeFile(repo.resolve("web/client.ts"),
                "export async function fetchStatus() { return fetch('/api/v1/status'); }\n");
    }

    private static void backdateTree(Path root, long millis) throws IOException {
        FileTime past = FileTime.fromMillis(millis);
        try (var stream = Files.walk(root)) {
            for (Path p : stream.toList()) {
                if (Files.isRegularFile(p)) {
                    Files.setLastModifiedTime(p, past);
                }
            }
        }
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static AnalysisJobs.View awaitDone(AnalysisJobs jobs, String id) throws InterruptedException {
        UUID uuid = UUID.fromString(id);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            var view = jobs.get(uuid);
            if (AnalysisJobs.terminal(view.state())) {
                return view;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Job timed out: " + id);
    }
}
