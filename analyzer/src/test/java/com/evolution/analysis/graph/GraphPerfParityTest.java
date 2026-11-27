package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.input.VerifiedArtifactSession;
import com.evolution.analysis.spring.universal.SpringSourceEvidence;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GraphPerfParityTest {
    @TempDir
    Path temp;

    record SamplePayload(
            String text,
            Optional<String> optPresent,
            Optional<String> optEmpty,
            Set<String> tags,
            Map<String, Object> props,
            Map<Integer, String> intMap,
            BigDecimal score,
            Instant at,
            URI uri,
            byte[] raw) {}

    @Test
    void graphEvidenceAndCanonicalJsonFastPathsMatchLegacyBitForBit() {
        var props = new LinkedHashMap<String, Object>();
        props.put("zeta", 42);
        props.put("alpha", "Tiếng Việt \uD83D\uDCFA \"quote\" \\ \n\t\u0007");
        props.put("nested", List.of(new BigDecimal("0.000"), new BigDecimal("12.3400"), true, false));
        var payload = new SamplePayload(
                "hello \"world\"",
                Optional.of("present"),
                Optional.empty(),
                new LinkedHashSet<>(List.of("c", "a", "b")),
                props,
                Map.of(2, "two", 1, "one"),
                new BigDecimal("99.500"),
                Instant.parse("2026-11-25T00:00:00Z"),
                URI.create("https://example.test/repo"),
                "binary-bytes".getBytes(StandardCharsets.UTF_8));

        GraphEvidence fast = withFastCodec(true, () -> GraphEvidence.capture("fixture.complex", payload));
        GraphEvidence legacy = withFastCodec(false, () -> GraphEvidence.capture("fixture.complex", payload));

        assertEquals(legacy.canonicalJson(), fast.canonicalJson());
        assertEquals(legacy.identity(), fast.identity());
        assertEquals(legacy, fast);
        assertEquals(CanonicalJson.write(legacy), GraphCanonicalWriter.encodeValue(fast));
    }

    @Test
    void knowledgeGraphDigestSemanticIdentityAndEnvelopeMatchLegacyBitForBit() {
        KnowledgeGraph fast = withFastCodec(true, () -> buildFixtureGraph(120));
        KnowledgeGraph legacy = withFastCodec(false, () -> buildFixtureGraph(120));

        assertEquals(legacy.identity(), fast.identity());
        assertEquals(legacy.semanticIdentity(), fast.semanticIdentity());
        assertEquals(legacy.manifest(), fast.manifest());

        var codec = new GraphSnapshotCodec();
        String fastJson = withFastCodec(true, () -> codec.encode(fast));
        String legacyJson = withFastCodec(false, () -> codec.encode(legacy));
        assertEquals(legacyJson, fastJson);

        // Verify exact mathematical fitsEnvelopeStructure against CanonicalJson.fitsStructure across depth & value boundaries.
        var envelope = Map.of("format", GraphSnapshotCodec.FORMAT, "identity", fast.identity(), "graph", fast.canonicalForm());
        for (int depth = 1; depth <= 8; depth++) {
            assertEquals(
                    CanonicalJson.fitsStructure(envelope, depth, 1_000_000L),
                    GraphCanonicalWriter.fitsEnvelopeStructure(fast, depth, 1_000_000L),
                    "Depth mismatch at maxDepth=" + depth);
        }
        // Binary search exact value count of CanonicalJson.fitsStructure and assert exact boundary match.
        long lo = 1, hi = 100_000;
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            if (CanonicalJson.fitsStructure(envelope, 16, mid)) hi = mid;
            else lo = mid + 1;
        }
        assertTrue(GraphCanonicalWriter.fitsEnvelopeStructure(fast, 16, lo));
        assertFalse(GraphCanonicalWriter.fitsEnvelopeStructure(fast, 16, lo - 1));
    }

    @Test
    void verifiedArtifactSessionCachesWithinJobAndNeverLeaksStandalone() throws Exception {
        Path jar = temp.resolve("artifact.jar");
        byte[] content = "immutable-jar-bytes-v1".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, content);
        ContentDigest expected = ContentDigest.sha256(content);

        try (var session = VerifiedArtifactSession.open(true)) {
            assertTrue(session.verifyDigest(jar, expected));
            assertEquals(1, session.misses());
            assertEquals(0, session.hits());

            assertTrue(VerifiedArtifactSession.verifyFileOrDirect(jar, expected));
            assertEquals(1, session.misses());
            assertEquals(1, session.hits());

            var pool = Executors.newFixedThreadPool(2);
            try {
                var f1 = pool.submit(session.wrap(() -> VerifiedArtifactSession.verifyFileOrDirect(jar, expected)));
                var f2 = pool.submit(session.wrap(() -> VerifiedArtifactSession.verifyFileOrDirect(jar, expected)));
                assertTrue(f1.get());
                assertTrue(f2.get());
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, session.misses());
            assertEquals(3, session.hits());
            assertEquals(1, session.uniqueVerifiedFiles());
        }

        assertTrue(VerifiedArtifactSession.current().isEmpty());
        // Mutate bytes while preserving exact length and mtime -> standalone check MUST detect tampering.
        var mtime = Files.getLastModifiedTime(jar);
        content[content.length - 1] ^= 0x01;
        Files.write(jar, content);
        Files.setLastModifiedTime(jar, mtime);
        assertFalse(VerifiedArtifactSession.verifyFileOrDirect(jar, expected));
    }

    @Test
    void springImportScannerMatchesLegacyRegexAcrossCommentsStringsAndLargeBodies() throws Exception {
        var method = SpringSourceEvidence.class.getDeclaredMethod("parseImports", String.class);
        method.setAccessible(true);
        StringBuilder source = new StringBuilder("""
                /* block comment with fake import fake.Ignored; */
                package com.example.demo;
                // line comment import fake.LineIgnored;
                import org.springframework.stereotype.Service;
                import static org.junit.jupiter.api.Assertions.*;
                import /* inline */ jakarta.inject.Inject;
                import org.springframework.context.annotation.*;
                @Service(value = "{ not a body }")
                public class BigService {
                    String s = "import fake.InString;";
                    String tb = \"\"\"
                            { import fake.InTextBlock; }
                            \"\"\";
                """);
        for (int i = 0; i < 500; i++) {
            source.append("    void m").append(i).append("() { /* comment */ int x = ").append(i).append("; }\n");
        }
        source.append("}\n");

        Object fastResult = withProp("evo.perf.fastExtraction", "true", () -> invokeUnchecked(method, source.toString()));
        Object legacyResult = withProp("evo.perf.fastExtraction", "false", () -> invokeUnchecked(method, source.toString()));
        assertEquals(legacyResult, fastResult);
    }

    @Test
    void microBenchmarkGraphEvidenceAndGraphEncoding() throws Exception {
        int count = 5000;
        // Warm up both fast and legacy code paths for stable JIT measurement
        for (int w = 0; w < 3; w++) {
            withFastCodec(false, () -> buildFixtureGraph(400));
            withFastCodec(true, () -> buildFixtureGraph(400));
        }

        // 1. Measure GraphEvidence.capture() on complex nested records (zero-TreeMap streaming vs TreeMap form())
        var sample = new SamplePayload(
                "com.example.order.OrderService#submitOrder(OrderRequest)",
                Optional.of("src/main/java/com/example/order/OrderService.java"),
                Optional.empty(),
                Set.of("spring.service", "arch.application", "java.type"),
                Map.of("module", "orders", "line", 142, "status", "RESOLVED"),
                Map.of(1, "a", 2, "b"),
                new BigDecimal("98.500"),
                Instant.parse("2026-11-25T00:00:00Z"),
                URI.create("https://example.test/orders"),
                "payload-bytes".getBytes(StandardCharsets.UTF_8));
        int evIters = 10_000;
        long evLegacyT0 = System.nanoTime();
        GraphEvidence evLegacy = withFastCodec(false, () -> {
            GraphEvidence last = null;
            for (int i = 0; i < evIters; i++) last = GraphEvidence.capture("bench.rec", sample);
            return last;
        });
        long evLegacyMs = (System.nanoTime() - evLegacyT0) / 1_000_000L;

        long evFastT0 = System.nanoTime();
        GraphEvidence evFast = withFastCodec(true, () -> {
            GraphEvidence last = null;
            for (int i = 0; i < evIters; i++) last = GraphEvidence.capture("bench.rec", sample);
            return last;
        });
        long evFastMs = (System.nanoTime() - evFastT0) / 1_000_000L;
        assertEquals(evLegacy, evFast);

        // 2. Measure KnowledgeGraph + semanticIdentity + encodeTo(StreamLimits)
        KnowledgeGraph graph = withFastCodec(true, () -> buildFixtureGraph(count));
        var codec = new GraphSnapshotCodec();
        var limits = new GraphJson.StreamLimits(64_000_000L, 128, 10_000_000L);
        var nullWriter = java.io.Writer.nullWriter();

        long encLegacyT0 = System.nanoTime();
        withFastCodec(false, () -> {
            try { codec.encodeTo(graph, nullWriter, limits); } catch (Exception e) { throw new RuntimeException(e); }
            return null;
        });
        long encLegacyMs = (System.nanoTime() - encLegacyT0) / 1_000_000L;

        long encFastT0 = System.nanoTime();
        withFastCodec(true, () -> {
            try { codec.encodeTo(graph, nullWriter, limits); } catch (Exception e) { throw new RuntimeException(e); }
            return null;
        });
        long encFastMs = (System.nanoTime() - encFastT0) / 1_000_000L;

        System.out.printf("[PERF-BENCH] GraphEvidence.capture (%d recs): legacy=%dms, fast=%dms, speedup=%.2fx%n",
                evIters, evLegacyMs, evFastMs, evLegacyMs / (double) Math.max(1L, evFastMs));
        System.out.printf("[PERF-BENCH] GraphSnapshotCodec.encodeTo+limits (%d nodes/edges/ev): legacy=%dms, fast=%dms, speedup=%.2fx%n",
                count, encLegacyMs, encFastMs, encLegacyMs / (double) Math.max(1L, encFastMs));
    }

    private static KnowledgeGraph buildFixtureGraph(int count) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:perf:fixture");
        var snapId = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("perf-snapshot-v1"));
        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of(
                "repository", repo.value(), "revision", "rev-123", "dirty", false));
        var attemptEv = GraphEvidence.capture("evidence.attempt", Map.of(
                "provider", "perf.test", "startedAt", "2026-11-25T00:00:00Z", "completedAt", "2026-11-25T00:00:01Z"));
        var ctx = new KnowledgeGraph.Context(snapId.value(), snapId, Optional.empty(), snapEv.identity());

        var evidence = new ArrayList<GraphEvidence>(count + 2);
        evidence.add(snapEv);
        evidence.add(attemptEv);
        var nodes = new ArrayList<KnowledgeGraph.Node>(count);
        var edges = new ArrayList<KnowledgeGraph.Edge>(count);
        for (int i = 0; i < count; i++) {
            var ev = GraphEvidence.capture("java.observation", Map.of(
                    "index", i,
                    "symbol", "com.example.Service" + i + "#execute(java.lang.String)",
                    "file", "src/main/java/com/example/Service" + i + ".java",
                    "line", i + 10));
            evidence.add(ev);
            String nodeId = KnowledgeGraph.derivedIdentity("type", i);
            nodes.add(new KnowledgeGraph.Node(nodeId, ctx.identity(), "TYPE", List.of(ev.identity())));
        }
        for (int i = 0; i < count; i++) {
            String src = nodes.get(i).identity();
            String dst = nodes.get((i + 1) % count).identity();
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("calls", i),
                    ctx.identity(),
                    "java.calls",
                    src,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                    List.of(evidence.get(i + 2).identity(), attemptEv.identity()),
                    Optional.empty()));
        }
        return new KnowledgeGraph(snapId, List.of(ctx), nodes, edges, evidence);
    }

    private static <T> T withFastCodec(boolean enabled, java.util.function.Supplier<T> action) {
        return withProp("evo.perf.fastGraphCodec", Boolean.toString(enabled), action);
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

    private static Object invokeUnchecked(java.lang.reflect.Method m, Object arg) {
        try {
            return m.invoke(null, arg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
