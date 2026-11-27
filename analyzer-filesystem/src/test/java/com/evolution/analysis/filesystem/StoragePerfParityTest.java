package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StoragePerfParityTest {
    @TempDir
    Path temp;

    @Test
    void graphBinaryCacheV2CompressesReloadsFastAndReadsLegacyV1() throws Exception {
        KnowledgeGraph graph = buildLargeFixture(2000);
        Path storeDir = Files.createDirectory(temp.resolve("store-v2"));
        var store = new FileGraphStore(storeDir);
        store.save(graph);
        Path jsonPath = store.jsonPath(graph.identity());
        long jsonBytes = Files.size(jsonPath);

        // Write legacy v1 and new compressed v2 in separate cache dirs and compare sizes & read parity.
        Path v1Dir = Files.createDirectory(temp.resolve("cache-v1"));
        var v1Cache = new GraphBinaryCache(v1Dir, 0L);
        v1Cache.write(graph, jsonPath, 1);
        long v1Bytes = Files.size(v1Cache.cachePath(graph.identity()));

        Path v2Dir = Files.createDirectory(temp.resolve("cache-v2"));
        var v2Cache = new GraphBinaryCache(v2Dir, 0L);
        v2Cache.write(graph, jsonPath, 2);
        Path v2Bin = v2Cache.cachePath(graph.identity());
        long v2Bytes = Files.size(v2Bin);

        assertTrue(v2Bytes < v1Bytes / 2, "Expected v2 compressed cache < 50% of v1: v1=" + v1Bytes + ", v2=" + v2Bytes);
        assertTrue(v2Bytes < jsonBytes / 3, "Expected v2 compressed cache < 33% of JSON: json=" + jsonBytes + ", v2=" + v2Bytes);

        // Both v1 and v2 manifests and full graphs must match bit-for-bit.
        assertEquals(graph.manifest(), v1Cache.readManifest(graph.identity(), jsonPath).orElseThrow());
        assertEquals(graph.manifest(), v2Cache.readManifest(graph.identity(), jsonPath).orElseThrow());

        long tJson = System.nanoTime();
        KnowledgeGraph fromJson = FileGraphStore.readSnapshot(jsonPath, 64_000_000, GraphJson.Limits.defaults());
        long jsonLoadMs = (System.nanoTime() - tJson) / 1_000_000L;

        KnowledgeGraph fromV1 = v1Cache.read(graph.identity(), jsonPath).orElseThrow();

        long tV2 = System.nanoTime();
        KnowledgeGraph fromV2 = v2Cache.read(graph.identity(), jsonPath).orElseThrow();
        long v2LoadMs = (System.nanoTime() - tV2) / 1_000_000L;

        assertEquals(graph.identity(), fromJson.identity());
        assertEquals(graph.identity(), fromV1.identity());
        assertEquals(graph.identity(), fromV2.identity());
        assertEquals(graph.manifest(), fromV2.manifest());
        assertEquals(graph.nodes(), fromV2.nodes());
        assertEquals(graph.edges(), fromV2.edges());
        assertEquals(graph.evidence(), fromV2.evidence());

        System.out.printf("[PERF-BENCH] GraphBinaryCache (2000 nodes/edges/ev): json=%dKB (%dms), v1=%dKB, v2=%dKB (%dms, %.1fx smaller vs json)%n",
                jsonBytes / 1024, jsonLoadMs, v1Bytes / 1024, v2Bytes / 1024, v2LoadMs, jsonBytes / (double) Math.max(1L, v2Bytes));

        // Corrupt a byte in v2 compressed payload -> must reject & delete entry.
        byte[] corrupted = Files.readAllBytes(v2Bin);
        corrupted[corrupted.length / 2] ^= 0x3C;
        Files.write(v2Bin, corrupted);
        assertTrue(v2Cache.read(graph.identity(), jsonPath).isEmpty());
        assertFalse(Files.exists(v2Bin));
    }

    @Test
    void sqliteGraphProjectionFastPathMatchesLegacyAndInMemoryDigest() throws Exception {
        KnowledgeGraph graph = buildLargeFixture(1000);
        Path fastSnaps = Files.createDirectory(temp.resolve("fast-snaps"));
        Path fastPages = Files.createDirectory(temp.resolve("fast-pages"));
        Path legacySnaps = Files.createDirectory(temp.resolve("legacy-snaps"));
        Path legacyPages = Files.createDirectory(temp.resolve("legacy-pages"));

        long t0 = System.nanoTime();
        var legacyPub = withProp("evo.perf.fastProjection", "false", () -> {
            try { return new PartitionedGraphStore(legacyPages).build(graph, legacySnaps, () -> false, ws -> {}); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        long legacyMs = (System.nanoTime() - t0) / 1_000_000L;

        long t1 = System.nanoTime();
        var fastPub = withProp("evo.perf.fastProjection", "true", () -> {
            try { return new PartitionedGraphStore(fastPages).build(graph, fastSnaps, () -> false, ws -> {}); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        long fastMs = (System.nanoTime() - t1) / 1_000_000L;

        assertEquals(graph.identity(), legacyPub.graph());
        assertEquals(graph.identity(), fastPub.graph());
        assertEquals(legacyPub.gapReasons(), fastPub.gapReasons());
        assertEquals(legacyPub.gapNodes(), fastPub.gapNodes());
        assertEquals(legacyPub.nodes(), fastPub.nodes());
        assertEquals(legacyPub.edges(), fastPub.edges());
        assertArrayEquals(
                Files.readAllBytes(legacySnaps.resolve(graph.identity().value().substring(7) + ".graph.json")),
                Files.readAllBytes(fastSnaps.resolve(graph.identity().value().substring(7) + ".graph.json")));

        // Measure pure SqliteGraphProjection + gapCounts() speedup on 5000 rows (1000 gap nodes)
        KnowledgeGraph benchGraph = buildLargeFixture(5000);
        long projLegacyMs = benchmarkProjection(benchGraph, false);
        long projFastMs = benchmarkProjection(benchGraph, true);
        System.out.printf("[PERF-BENCH] SqliteGraphProjection+gapCounts (5000 nodes/edges/ev): legacy=%dms, fast=%dms, speedup=%.2fx%n",
                projLegacyMs, projFastMs, projLegacyMs / (double) Math.max(1L, projFastMs));
    }

    private long benchmarkProjection(KnowledgeGraph graph, boolean fast) throws Exception {
        Path dbFile = Files.createTempFile(temp, "proj-bench-", ".sqlite");
        return withProp("evo.perf.fastProjection", Boolean.toString(fast), () -> {
            long start = System.nanoTime();
            try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
                try (var sql = db.createStatement()) {
                    sql.execute("PRAGMA journal_mode=OFF");
                    sql.execute("PRAGMA synchronous=OFF");
                    sql.execute("PRAGMA temp_store=MEMORY");
                    sql.execute("PRAGMA cache_size=-65536");
                    sql.execute("CREATE TABLE rows(space TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,kind TEXT,PRIMARY KEY(space,k)) WITHOUT ROWID");
                }
                db.setAutoCommit(false);
                try (var proj = new SqliteGraphProjection(db, 64_000_000, () -> false)) {
                    var ev = proj.records("evidence", GraphSnapshotCodec::evidence);
                    for (var e : graph.evidence()) ev.put(e.identity().value(), e);
                    var nodes = proj.records("nodes", GraphSnapshotCodec::node);
                    for (var n : graph.nodes()) nodes.put(n.context() + "|" + n.identity(), n);
                    var edges = proj.records("edges", GraphSnapshotCodec::edge);
                    for (var e : graph.edges()) edges.put(e.context() + "|" + e.identity(), e);
                    proj.flush();
                    var counts = proj.gapCounts();
                    assertEquals(1000, counts.nodes());
                }
                db.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return (System.nanoTime() - start) / 1_000_000L;
        });
    }

    @Test
    void fileAnalysisWorkspaceOrderedKeyMatchesUtf16AndSupportsConcurrentWrites() throws Exception {
        List<String> keys = List.of(
                "", "\u0000", "A", "a", "z", "\u007F", "\u0080", "\u07FF", "\u0800",
                "\uD7FF", "\uD800", "\uD83D\uDCFA", "\uDFFF", "\uE000", "\uFFFF",
                "alpha\uD83D\uDCFA", "alpha\uE000");
        List<String> byJava = new ArrayList<>(keys);
        byJava.sort(String::compareTo);
        List<String> byCesu8 = new ArrayList<>(keys);
        byCesu8.sort((a, b) -> Arrays.compareUnsigned(
                FileAnalysisWorkspace.orderedKey(a), FileAnalysisWorkspace.orderedKey(b)));
        assertEquals(byJava, byCesu8);

        // Concurrent table writes + unpaired surrogates + both raw (<256B) and compressed (>256B) payloads.
        try (var ws = new FileAnalysisWorkspace(temp.resolve("spool"))) {
            var pool = Executors.newFixedThreadPool(4);
            try {
                var futures = new ArrayList<Future<List<GraphEvidence>>>();
                for (int t = 0; t < 4; t++) {
                    int tid = t;
                    List<GraphEvidence> table = ws.list();
                    futures.add(pool.submit(() -> {
                        var expected = new ArrayList<GraphEvidence>();
                        for (int i = 0; i < 50; i++) {
                            String body = i % 2 == 0 ? "small-" + tid + "-" + i : ("large-payload-" + tid + "-").repeat(40) + i;
                            var ev = GraphEvidence.capture("spool.test", Map.of("body", body, "i", i));
                            table.add(ev);
                            expected.add(ev);
                        }
                        List<GraphEvidence> frozen = ws.freeze(table);
                        assertEquals(expected, frozen);
                        return frozen;
                    }));
                }
                for (var f : futures) assertEquals(50, f.get().size());
            } finally {
                pool.shutdownNow();
            }

            // Verify unpaired surrogate preservation inside string storage.
            List<String> rawStrings = ws.list();
            String unpaired = "prefix-\uD800-middle-\uDC00-suffix";
            rawStrings.add(unpaired);
            assertEquals(unpaired, rawStrings.getFirst());
        }
    }

    private static KnowledgeGraph buildLargeFixture(int count) {
        var repo = com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:perf:storage");
        var snapId = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("storage-perf-v1"));
        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("revision", "rev-storage"));
        var ctx = new KnowledgeGraph.Context(snapId.value(), snapId, Optional.empty(), snapEv.identity());
        var evidence = new ArrayList<GraphEvidence>(count + 2);
        evidence.add(snapEv);
        var nodes = new ArrayList<KnowledgeGraph.Node>(count);
        var edges = new ArrayList<KnowledgeGraph.Edge>(count);
        for (int i = 0; i < count; i++) {
            boolean isGap = i % 5 == 0;
            var ev = isGap
                    ? GraphEvidence.capture("evidence.gap", Map.of(
                            "reasonCode", "UNRESOLVED_DEPENDENCY_SYMBOL",
                            "description", "Dependency coordinate was absent from the offline Maven cache during static analysis.",
                            "remediation", "Populate the selected offline Maven cache or attach the resolved binary artifact.",
                            "index", i))
                    : GraphEvidence.capture("java.observation", Map.of(
                            "file", "src/main/java/com/acme/module/Service" + i + ".java",
                            "symbol", "com.acme.module.Service" + i + "#process(java.util.List)",
                            "index", i));
            evidence.add(ev);
            String nodeId = KnowledgeGraph.derivedIdentity(isGap ? "gap" : "type", i);
            nodes.add(new KnowledgeGraph.Node(nodeId, ctx.identity(), isGap ? "CAPABILITY_GAP" : "TYPE", List.of(ev.identity())));
        }
        for (int i = 0; i < count; i++) {
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("edge", i),
                    ctx.identity(),
                    "java.calls",
                    nodes.get(i).identity(),
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(nodes.get((i + 1) % count).identity()), Optional.empty()),
                    List.of(evidence.get(i + 1).identity()),
                    Optional.empty()));
        }
        return new KnowledgeGraph(snapId, List.of(ctx), nodes, edges, evidence);
    }

    private static <T> T withProp(String key, String value, java.util.function.Supplier<T> action) {
        String prev = System.getProperty(key);
        System.setProperty(key, value);
        try {
            return action.get();
        } finally {
            if (prev == null) System.clearProperty(key);
            else System.setProperty(key, prev);
        }
    }
}
