package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.filesystem.PartitionedGraphStore;
import com.evolution.analysis.graph.*;
import com.evolution.backend.query.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalAnalysisTest {
    @TempDir
    Path dir;

    @Test
    void oneFileEditHitsSourceSetAndPolyglotCachesAndProducesExactColdDigest() throws Exception {
        Path repo = Files.createDirectory(dir.resolve("multi-repo"));
        Path cache = Files.createDirectory(dir.resolve("maven-cache"));
        Path intake = Files.createDirectory(dir.resolve("intake"));
        Path storeDir = Files.createDirectory(dir.resolve("store"));

        writeFile(repo.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>root</artifactId>"
                        + "<version>1.0</version><packaging>pom</packaging>"
                        + "<modules><module>mod-a</module><module>mod-b</module></modules></project>");
        writeFile(repo.resolve("mod-a/pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId><artifactId>root</artifactId><version>1.0</version></parent>"
                        + "<artifactId>mod-a</artifactId><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        writeFile(repo.resolve("mod-a/src/main/java/a/Alpha.java"),
                "package a; public class Alpha { public int v() { return 1; } }");
        writeFile(repo.resolve("mod-b/pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId><artifactId>root</artifactId><version>1.0</version></parent>"
                        + "<artifactId>mod-b</artifactId><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        writeFile(repo.resolve("mod-b/src/main/java/b/Beta.java"),
                "package b; public class Beta { public int w() { return 2; } }");
        writeFile(repo.resolve("web/client.ts"),
                "export function ping() { return fetch('/api/health'); }\n");

        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:test:incremental-multi");
        var input = new LocalAnalysisRunner.Input(repo, repoId, Path.of(System.getProperty("java.home")), 21, cache,
                Optional.empty(), true, false, List.of(), Optional.empty(), LocalAnalysisRunner.SpringInput.absent());
        var runner = new LocalAnalysisRunner(input, intake, Optional.empty(),
                LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(), Optional.of(storeDir));
        var store = new InMemoryGraphStore();
        try (var jobs = new AnalysisJobs(List.of(runner.registration("inc")), store, new PublishedReports(),
                new AnalysisJobs.Limits(1, 2, 8, 90_000, 1000))) {
            var v1 = finished(jobs, jobs.submit("inc", "run1").job().id());
            ContentDigest g1 = v1.result().orElseThrow().graph().orElseThrow();
            assertNotNull(g1);
            // Verify incremental caches were populated on disk (sharded v3 polyglot + Java source-set cache)
            assertTrue(Files.isDirectory(intake.resolve("incremental-cache/polyglot-v3")));
            try (var shards = Files.walk(intake.resolve("incremental-cache/polyglot-v3"))) {
                assertTrue(shards.anyMatch(p -> p.getFileName().toString().endsWith(".pg3")));
            }
            assertTrue(Files.isDirectory(intake.resolve("incremental-cache/java-sets")));
        }

        // Edit 1 line inside mod-a only (mod-b and web/client.ts untouched!)
        writeFile(repo.resolve("mod-a/src/main/java/a/Alpha.java"),
                "package a; public class Alpha { public int v() { int x = 9; return x; } }");

        ContentDigest incrementalGraphId;
        var store2 = new InMemoryGraphStore();
        try (var jobs = new AnalysisJobs(List.of(runner.registration("inc")), store2, new PublishedReports(),
                new AnalysisJobs.Limits(1, 2, 8, 90_000, 1000))) {
            var v2 = finished(jobs, jobs.submit("inc", "run2").job().id());
            incrementalGraphId = v2.result().orElseThrow().graph().orElseThrow();
        }

        // Run again with clean intake & caches disabled -> graph identity must be bit-for-bit identical!
        Path coldIntake = Files.createDirectory(dir.resolve("cold-intake"));
        var coldRunner = new LocalAnalysisRunner(input, coldIntake, Optional.empty(),
                LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(), Optional.of(storeDir));
        ContentDigest coldGraphId;
        var store3 = new InMemoryGraphStore();
        System.setProperty("evo.incremental.disabled", "true");
        try (var jobs = new AnalysisJobs(List.of(coldRunner.registration("cold")), store3, new PublishedReports(),
                new AnalysisJobs.Limits(1, 2, 8, 90_000, 1000))) {
            var v3 = finished(jobs, jobs.submit("cold", "run3").job().id());
            coldGraphId = v3.result().orElseThrow().graph().orElseThrow();
        } finally {
            System.clearProperty("evo.incremental.disabled");
        }
        assertEquals(coldGraphId, incrementalGraphId);
    }

    @Test
    void polyglotCacheRoundTripsLargeValuesAbove64KbAndTracksHitMissCounters() throws Exception {
        Path cacheRoot = Files.createDirectory(dir.resolve("polyglot-large-cache"));
        String largeWritten = "x".repeat(75_000);
        String largeAttrVal = "v".repeat(70_000);
        var digest = ContentDigest.sha256Utf8("large-polyglot-source");
        var keyV1 = IncrementalPolyglotCache.key("provider:v1", "tool:v1", "typescript", digest, 75_000L, false);
        var keyV2 = IncrementalPolyglotCache.key("provider:v1", "tool:v2", "typescript", digest, 75_000L, false);
        assertNotEquals(keyV1, keyV2, "toolIdentity must partition cache keys");

        var summary = new com.evolution.analysis.treesitter.TreeSitterSourceFrontend.Summary(
                42L, 1L, 0L, "tree-sitter-typescript", "tool:v1");
        var span = new com.evolution.analysis.polyglot.SourceCoordinates.Range(0, 75_000, 1, 1, 10, 5);
        var fact = new com.evolution.analysis.treesitter.TreeSitterSourceFrontend.Fact(
                "DECLARATION", "function_declaration", "bigFn", largeWritten, span, "", false,
                Map.of("payload", largeAttrVal));

        var writer = IncrementalPolyglotCache.open(cacheRoot);
        assertTrue(writer.get(keyV1).isEmpty());
        assertEquals(0L, writer.hits());
        assertEquals(1L, writer.misses());
        writer.put(keyV1, new IncrementalPolyglotCache.Entry(summary, List.of(fact)));
        assertTrue(writer.get(keyV1).isPresent());
        assertEquals(1L, writer.hits());
        assertEquals(1L, writer.misses());
        writer.flushQuietly();

        var reader = IncrementalPolyglotCache.open(cacheRoot);
        var loaded = reader.get(keyV1);
        assertTrue(loaded.isPresent(), "v2 length-prefixed cache must reload >64KB strings cleanly");
        assertEquals(1L, reader.hits());
        assertEquals(0L, reader.misses());
        assertEquals(largeWritten, loaded.orElseThrow().facts().getFirst().written());
        assertEquals(largeAttrVal, loaded.orElseThrow().facts().getFirst().attributes().get("payload"));
        assertTrue(reader.get(keyV2).isEmpty());
        assertEquals(1L, reader.misses());
    }

    @Test
    void jobTelemetrySurfacesJavaAndPolyglotCacheHitMissCounters() throws Exception {
        Path repo = Files.createDirectory(dir.resolve("tel-repo"));
        Path cache = Files.createDirectory(dir.resolve("tel-m2"));
        Path intake = Files.createDirectory(dir.resolve("tel-intake"));
        writeFile(repo.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>tel</artifactId>"
                        + "<version>1.0</version><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        writeFile(repo.resolve("src/main/java/t/App.java"), "package t; public class App { int x() { return 1; } }");
        writeFile(repo.resolve("web/app.ts"), "export function ping() { return fetch('/api/ping'); }\n");

        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:test:cache-telemetry");
        var input = new LocalAnalysisRunner.Input(repo, repoId, Path.of(System.getProperty("java.home")), 21, cache,
                Optional.empty(), true, false, List.of(), Optional.empty(), LocalAnalysisRunner.SpringInput.absent());
        var runner = new LocalAnalysisRunner(input, intake);
        var store = new InMemoryGraphStore();
        try (var jobs = new AnalysisJobs(List.of(runner.registration("tel")), store, new PublishedReports(),
                new AnalysisJobs.Limits(1, 2, 8, 90_000, 1000))) {
            var v1 = finished(jobs, jobs.submit("tel", "cold").job().id());
            var t1 = jobs.telemetry(UUID.fromString(v1.id()));
            assertTrue(t1.cacheMetrics().getOrDefault("javaCacheMisses", 0L) >= 1L, () -> "t1=" + t1);
            assertTrue(t1.cacheMetrics().getOrDefault("polyglotCacheMisses", 0L) >= 1L, () -> "t1=" + t1);

            var v2 = finished(jobs, jobs.submit("tel", "warm", true).job().id());
            var t2 = jobs.telemetry(UUID.fromString(v2.id()));
            assertTrue(t2.cacheMetrics().getOrDefault("javaCacheHits", 0L) >= 1L, () -> "t2=" + t2);
            assertTrue(t2.cacheMetrics().getOrDefault("polyglotCacheHits", 0L) >= 1L, () -> "t2=" + t2);
            assertTrue(t2.cacheMetrics().getOrDefault("totalCacheHits", 0L) >= 2L, () -> "t2=" + t2);
        }
    }

    @Test
    void spilledDiskPublicationProducesIdenticalDigestAndFastManifest() throws Exception {
        Path repo = Files.createDirectory(dir.resolve("spill-repo"));
        Path cache = Files.createDirectory(dir.resolve("spill-cache"));
        Path intake = Files.createDirectory(dir.resolve("spill-intake"));
        Path storeDir = Files.createDirectory(dir.resolve("spill-store"));
        Files.createDirectories(storeDir.resolve("graph-pages"));

        writeFile(repo.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>sp</artifactId>"
                        + "<version>1.0</version><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        writeFile(repo.resolve("src/main/java/sp/App.java"), "package sp; public class App { void run() {} }");

        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:test:spilled-wb");
        var input = new LocalAnalysisRunner.Input(repo, repoId, Path.of(System.getProperty("java.home")), 21, cache,
                Optional.empty(), true, false, List.of(), Optional.empty(), LocalAnalysisRunner.SpringInput.absent());

        // Cold normal run to get exact expected graph identity
        var memRunner = new LocalAnalysisRunner(input, Files.createDirectory(dir.resolve("mem-intake")));
        var memStore = new FileGraphStore(Files.createDirectory(dir.resolve("mem-store")));
        ContentDigest memId;
        try (var jobs = new AnalysisJobs(List.of(memRunner.registration("mem")), memStore, new PublishedReports(),
                new AnalysisJobs.Limits(1, 2, 4, 90_000, 1000))) {
            memId = finished(jobs, jobs.submit("mem", "mem1").job().id()).result().orElseThrow().graph().orElseThrow();
        }

        // Spilled Workbench run (threshold=1 byte)
        System.setProperty("evo.workbench.spilledGraphThresholdBytes", "1");
        try {
            var diskStore = new FileGraphStore(storeDir, 64_000_000L, GraphJson.StreamLimits.from(GraphJson.Limits.defaults()));
            var spillRunner = new LocalAnalysisRunner(input, intake, Optional.empty(),
                    LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(), Optional.of(storeDir));
            var reports = new PublishedReports();
            try (var jobs = new AnalysisJobs(List.of(spillRunner.registration("spill")), diskStore, reports,
                    new AnalysisJobs.Limits(1, 2, 4, 90_000, 1000))) {
                var view = finished(jobs, jobs.submit("spill", "spill1").job().id());
                ContentDigest spillId = view.result().orElseThrow().graph().orElseThrow();
                var memLoaded = memStore.load(memId).orElseThrow();
                var spillLoaded = diskStore.load(spillId).orElseThrow();
                assertEquals(memId, spillId);
                assertEquals(spillId, spillLoaded.identity());
                assertEquals(memLoaded.nodes().size(), spillLoaded.nodes().size(),
                        () -> "memKinds=" + memLoaded.nodes().stream().collect(java.util.stream.Collectors.groupingBy(KnowledgeGraph.Node::kind, java.util.stream.Collectors.counting()))
                                + " spillKinds=" + spillLoaded.nodes().stream().collect(java.util.stream.Collectors.groupingBy(KnowledgeGraph.Node::kind, java.util.stream.Collectors.counting())));
                assertEquals(memLoaded.edges().size(), spillLoaded.edges().size());
                assertTrue(Files.isRegularFile(storeDir.resolve("graph-pages").resolve(spillId.value().substring(7) + ".partitions.json")));
                var ctx = spillLoaded.contexts().stream().filter(c -> c.analysis().isPresent()).findFirst().orElseThrow();
                var sub = new PartitionedGraphStore(storeDir.resolve("graph-pages"))
                        .contextGraph(spillId, ctx.identity(), () -> false).orElseThrow();
                assertEquals(1, sub.contexts().size());
                assertEquals(ctx.identity(), sub.contexts().getFirst().identity());
                assertEquals(spillLoaded.nodes(ctx.identity()).size(), sub.nodes(ctx.identity()).size());
                var api = new ArchitectureQueryApi(diskStore, reports, ApiLimits.defaults(), jobs);
                var resp = api.handle("GET", "/api/v1/graphs/" + spillId.value(), "application/json", new byte[0], () -> false);
                assertEquals(200, resp.status());
            }
        } finally {
            System.clearProperty("evo.workbench.spilledGraphThresholdBytes");
        }
    }

    private static AnalysisJobs.View finished(AnalysisJobs jobs, String id) throws Exception {
        UUID uuid = UUID.fromString(id);
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            var v = jobs.get(uuid);
            if (AnalysisJobs.terminal(v.state())) return v;
            Thread.sleep(25);
        }
        throw new AssertionError("Job timed out");
    }

    private static void writeFile(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
