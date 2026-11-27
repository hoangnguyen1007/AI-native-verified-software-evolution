package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.backend.query.PublishedReports;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link WorkbenchApiMain} composition root invariants:
 * shared {@link WorkbenchPreviewStore}, shared long-lived {@link LocalAnalysisRunner.SharedCaches},
 * {@link FileGraphStore} + {@code GraphBinaryCache} v2 reload, and preview guardrails.
 */
class WorkbenchApiMainTest {
    @TempDir
    Path temp;

    @Test
    void sharedPreviewStoreAndLongLivedCachesWireCleanlyAcrossRunnerJobsAndQueryApi() throws Exception {
        Path source = Files.createDirectory(temp.resolve("src"));
        Path cache = Files.createDirectory(temp.resolve("m2"));
        Path storeDir = Files.createDirectory(temp.resolve("store"));
        Path acquisitionDir = storeDir.resolve("acquisition");
        Path jdk = Path.of(System.getProperty("java.home"));
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:workbench-wiring");

        // 1. Multiple runners on the same intake directory automatically share the exact same SharedCaches
        var shared = LocalAnalysisRunner.sharedCaches(acquisitionDir);
        var input = new LocalAnalysisRunner.Input(source, repo, jdk, 21, cache,
                Optional.empty(), true, false, List.of(), Optional.empty(), LocalAnalysisRunner.SpringInput.absent());
        var runnerA = new LocalAnalysisRunner(input, acquisitionDir, Optional.empty(),
                LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(), Optional.of(storeDir), shared);
        var runnerB = new LocalAnalysisRunner(input, acquisitionDir, Optional.empty(),
                LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(), Optional.of(storeDir));

        assertSame(shared, runnerA.sharedCaches());
        assertSame(shared, runnerB.sharedCaches());
        assertSame(runnerA.sharedCaches().buildDelta(), runnerB.sharedCaches().buildDelta());
        assertSame(runnerA.sharedCaches().springCache(), runnerB.sharedCaches().springCache());
        assertSame(runnerA.sharedCaches().graphProjector(), runnerB.sharedCaches().graphProjector());

        // 2. Shared WorkbenchPreviewStore bridges AnalysisJobs and ArchitectureQueryApi via createWorkbenchQueryApi
        var previewStore = new WorkbenchPreviewStore();
        System.setProperty("evo.graph.binaryCacheMinBytes", "0");
        try {
            var graphStore = new FileGraphStore(storeDir, 64_000_000L, GraphJson.StreamLimits.from(GraphJson.Limits.defaults()));
            var reports = new PublishedReports();

            try (var jobs = new AnalysisJobs(List.of(runnerA.registration("local")), graphStore, reports,
                    AnalysisJobs.Limits.defaults(), null, previewStore)) {
                assertSame(previewStore, jobs.previewStore());
                var connections = new RepositoryConnections(jobs, jdk, 21, cache, storeDir, Optional.empty());
                var api = WorkbenchApiMain.createWorkbenchQueryApi(graphStore, reports, jobs, connections, null, previewStore);

                // Publish an ephemeral Stage 1 preview into the shared preview store
                var snapshot = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("preview-snap"));
                var skeleton = new FastJavaSkeletonScanner().scan(List.of(
                        new FastJavaSkeletonScanner.SourceFile("src/main/java/demo/App.java", ".",
                                ContentDigest.sha256Utf8("class App {}"),
                                "package demo; public class App {}")),
                        () -> false);
                KnowledgeGraph previewGraph = new PreliminaryGraphBuilder().build(
                        PreliminaryGraphBuilder.Input.stage1(repo, snapshot,
                                List.of(new PreliminaryGraphBuilder.ModuleInfo(".", "demo", "MAVEN", 1)),
                                skeleton, List.of(".")));
                previewStore.putPreview("job-preview-1", previewGraph, AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE,
                        0, 1, List.of("."));

                // GET /api/v1/graphs/{preview} succeeds with authoritative=false & truthTier=PRELIMINARY_STRUCTURE
                var getResp = api.handle("GET", "/api/v1/graphs/" + previewGraph.identity().value(),
                        null, new byte[0], () -> false);
                assertEquals(200, getResp.status());
                String getBody = new String(getResp.body(), StandardCharsets.UTF_8);
                assertTrue(getBody.contains("\"authoritative\":false"), () -> "body=" + getBody);
                assertTrue(getBody.contains("\"truthTier\":\"PRELIMINARY_STRUCTURE\""), () -> "body=" + getBody);

                // Policy assessment against Stage 1 preview MUST return 409 PRELIMINARY_GRAPH_PENDING
                var policyResp = api.handle("POST", "/api/v1/graphs/" + previewGraph.identity().value() + "/suggested-policy",
                        "application/json", "{}".getBytes(StandardCharsets.UTF_8), () -> false);
                assertEquals(409, policyResp.status(), () -> "Expected 409 on preliminary preview, body="
                        + new String(policyResp.body(), StandardCharsets.UTF_8));
                assertTrue(new String(policyResp.body(), StandardCharsets.UTF_8).contains("PRELIMINARY_GRAPH_PENDING"));

                // 3. FileGraphStore save writes both canonical .graph.json and GraphBinaryCache v2 .graph.bin & reloads cleanly
                var canonical = new KnowledgeGraph(snapshot, List.of(), List.of(), List.of(), List.of());
                graphStore.save(canonical, () -> false);
                assertTrue(Files.isRegularFile(storeDir.resolve(canonical.identity().value().substring(7) + ".graph.json")));
                assertTrue(Files.isRegularFile(
                        storeDir.resolve("graph-cache").resolve(canonical.identity().value().substring(7) + ".graph.bin")));
                var reloaded = graphStore.load(canonical.identity()).orElseThrow();
                assertEquals(canonical.identity(), reloaded.identity());
            }
        } finally {
            System.clearProperty("evo.graph.binaryCacheMinBytes");
        }
    }

    @Test
    void launchOptionsResolveBindHostPaasPortAndProxyHeaderEnvSafely() {
        String[] baseArgs = {"/src", "urn:test:app", "/jdk", "21", "/m2", "/store"};

        // 1. Safe local defaults: 127.0.0.1:8080, proxy headers fail-closed
        var defaults = WorkbenchApiMain.parseLaunchOptions(baseArgs, k -> null, k -> null);
        assertEquals("127.0.0.1", defaults.bindHost());
        assertEquals(8080, defaults.port());
        assertTrue(defaults.policyPath().isEmpty());
        assertFalse(com.evolution.backend.query.ArchitectureHttpServer.trustProxyHeadersFromEnvironment(k -> null, k -> null));

        // 2. PaaS env fallback: EVO_BIND_HOST=0.0.0.0, PORT=9090, EVO_TRUST_PROXY_HEADERS=true
        var env = java.util.Map.of(
                "EVO_BIND_HOST", "0.0.0.0",
                "PORT", "9090",
                "EVO_TRUST_PROXY_HEADERS", "true");
        var paas = WorkbenchApiMain.parseLaunchOptions(baseArgs, env::get, k -> null);
        assertEquals("0.0.0.0", paas.bindHost());
        assertEquals(9090, paas.port());
        assertTrue(com.evolution.backend.query.ArchitectureHttpServer.trustProxyHeadersFromEnvironment(env::get, k -> null));

        // 3. Explicit --host / --port / --policy flags override env vars
        var explicitFlags = WorkbenchApiMain.parseLaunchOptions(
                new String[]{"--host", "127.0.0.1", "--port=7070", "--policy", "rules.yaml",
                        "/src", "urn:test:app", "/jdk", "21", "/m2", "/store"},
                env::get, k -> null);
        assertEquals("127.0.0.1", explicitFlags.bindHost());
        assertEquals(7070, explicitFlags.port());
        assertEquals(Optional.of("rules.yaml"), explicitFlags.policyPath());

        // 4. Backward-compatible positional [port] [policy.yaml] overrides PORT env var
        var positional = WorkbenchApiMain.parseLaunchOptions(
                new String[]{"/src", "urn:test:app", "/jdk", "21", "/m2", "/store", "6060", "legacy.yaml"},
                env::get, k -> null);
        assertEquals("0.0.0.0", positional.bindHost());
        assertEquals(6060, positional.port());
        assertEquals(Optional.of("legacy.yaml"), positional.policyPath());
    }

    @Test
    void readBoundedCasContentEnforces16MiBLimitAndReadsNormalBlobs() throws Exception {
        Path contentRoot = Files.createDirectory(temp.resolve("cas-content"));
        var cas = new com.evolution.analysis.filesystem.StreamingContentStore(contentRoot);
        byte[] normalBytes = "package demo; class Ok {}".getBytes(StandardCharsets.UTF_8);
        var normalHandle = cas.put(new java.io.ByteArrayInputStream(normalBytes), () -> false);

        var ref = new java.util.concurrent.atomic.AtomicReference<com.evolution.analysis.filesystem.StreamingContentStore>();
        var loaded = WorkbenchApiMain.readBoundedCasContent(
                contentRoot, ref, normalHandle.digest(), java.util.OptionalLong.of(normalHandle.size()));
        assertTrue(loaded.isPresent());
        assertArrayEquals(normalBytes, loaded.orElseThrow());

        // Reject negative, missing, and > 16 MiB expectedSize without loading into RAM
        assertTrue(WorkbenchApiMain.readBoundedCasContent(
                contentRoot, ref, normalHandle.digest(), java.util.OptionalLong.empty()).isEmpty());
        assertTrue(WorkbenchApiMain.readBoundedCasContent(
                contentRoot, ref, normalHandle.digest(), java.util.OptionalLong.of(-1L)).isEmpty());
        assertTrue(WorkbenchApiMain.readBoundedCasContent(
                contentRoot, ref, normalHandle.digest(), java.util.OptionalLong.of(WorkbenchApiMain.MAX_CAS_READ_BYTES + 1L)).isEmpty());

        // Even if a > 16 MiB blob exists in CAS, readBoundedCasContent refuses it
        byte[] oversized = new byte[WorkbenchApiMain.MAX_CAS_READ_BYTES + 64];
        var oversizedHandle = cas.put(new java.io.ByteArrayInputStream(oversized), () -> false);
        assertTrue(WorkbenchApiMain.readBoundedCasContent(
                contentRoot, ref, oversizedHandle.digest(), java.util.OptionalLong.of(oversizedHandle.size())).isEmpty());
    }
}
