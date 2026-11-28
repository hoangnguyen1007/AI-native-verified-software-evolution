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

    @Test
    void onlineLaunchOptionsResolveDefaultsAndRejectPositionalLocalPaths() {
        var zeroArgEnv = java.util.Map.of(
                "EVO_ONLINE_MODE", "true",
                "EVO_STATE_DIR", "/data/evo-store",
                "JAVA_HOME", "/opt/jdk-21");
        var zeroArg = WorkbenchApiMain.parseLaunchOptions(new String[0], zeroArgEnv::get, k -> null);
        assertTrue(zeroArg.onlineMode());
        assertNull(zeroArg.source());
        assertNull(zeroArg.coordinate());
        assertEquals("0.0.0.0", zeroArg.bindHost());
        assertEquals(8080, zeroArg.port());
        assertEquals("/data/evo-store", zeroArg.graphDirectory());
        assertEquals(Path.of("/data/evo-store", "m2-cache").toString(), zeroArg.mavenCache());
        assertEquals("/opt/jdk-21", zeroArg.jdk());
        assertEquals(21, zeroArg.release());

        var explicitOnline = WorkbenchApiMain.parseLaunchOptions(
                new String[]{"--online", "--host=127.0.0.1", "--port", "9191", "--state-dir=/srv/evo",
                        "--jdk=/jdk21", "--release", "21", "--maven-cache", "/srv/m2"},
                k -> null, k -> null);
        assertTrue(explicitOnline.onlineMode());
        assertNull(explicitOnline.source());
        assertNull(explicitOnline.coordinate());
        assertEquals("127.0.0.1", explicitOnline.bindHost());
        assertEquals(9191, explicitOnline.port());
        assertEquals("/srv/evo", explicitOnline.graphDirectory());
        assertEquals("/srv/m2", explicitOnline.mavenCache());

        assertThrows(IllegalArgumentException.class, () -> WorkbenchApiMain.parseLaunchOptions(
                new String[]{"--online", "/local/repo"}, zeroArgEnv::get, k -> null));
    }

    @Test
    void onlineModeHealthEndpointAndLocalCheckoutBlocking() throws Exception {
        Path storeDir = Files.createDirectory(temp.resolve("online-store"));
        Path cache = Files.createDirectory(temp.resolve("m2"));
        Path localRepo = Files.createDirectory(temp.resolve("local-repo"));
        Path jdk = Path.of(System.getProperty("java.home"));

        var graphs = new com.evolution.analysis.graph.InMemoryGraphStore();
        var reports = new PublishedReports();
        try (var jobs = new AnalysisJobs(List.of(), graphs, reports, AnalysisJobs.Limits.defaults())) {
            var onlineConnections = new RepositoryConnections(
                    jobs, jdk, 21, cache, storeDir, Optional.empty(),
                    "git", new com.evolution.analysis.filesystem.GitHostPolicy(), Optional.empty(), true);
            assertTrue(onlineConnections.isOnlineOnly());

            // 1. Direct RepositoryConnections rejects any local host directory in online mode
            var sec = assertThrows(SecurityException.class,
                    () -> onlineConnections.connect(localRepo.toString(), Optional.empty()));
            assertEquals("LOCAL_CHECKOUT_DISABLED_IN_ONLINE_MODE", sec.getMessage());

            // 2. Wire multi-user AuthHttpApi alongside online-only RepositoryConnections
            var authDir = temp.resolve("auth");
            var cfgStore = new com.evolution.backend.auth.GitHubOAuthConfigStore(authDir, k -> null);
            try (var accounts = new com.evolution.backend.auth.UserAccountStore(authDir.resolve("accounts.db"));
                 var sessions = new com.evolution.backend.auth.UserSessionStore(
                         Optional.of(temp.resolve("sessions.enc")), Optional.of(cfgStore.masterKeyBytes()));
                 var broker = new com.evolution.backend.github.GitHubTokenBroker(k -> null, h -> Optional.empty())) {
                broker.setMultiUserMode(true);
                var oauthCfg = new com.evolution.backend.auth.GitHubOAuthService.Config(
                        true, "", "", "github.com", com.evolution.backend.auth.GitHubOAuthService.DEFAULT_SCOPES,
                        false, Optional.empty(), Optional.empty(), Optional.empty());
                var rest = new com.evolution.backend.github.GitHubRestClient(
                        broker, new com.evolution.analysis.filesystem.GitHostPolicy());
                var oauth = new com.evolution.backend.auth.GitHubOAuthService(
                        oauthCfg, sessions,
                        com.evolution.backend.github.GitHubRestClient.createPinnedTransport(
                                new com.evolution.analysis.filesystem.GitHostPolicy()),
                        cfgStore, accounts);
                var authApi = new com.evolution.backend.auth.AuthHttpApi(
                        oauth, rest, com.evolution.backend.query.ApiLimits.defaults(), () -> false);

                var api = com.evolution.backend.query.ArchitectureQueryApi.withAuth(
                        new com.evolution.backend.query.ArchitectureQueryApi(
                                graphs, reports, com.evolution.backend.query.ApiLimits.defaults(),
                                jobs, onlineConnections),
                        authApi);

                // GET /api/v1/health succeeds with 200 even when unauthenticated in multi-user mode
                var health = api.handle("GET", "/api/v1/health", null, new byte[0], () -> false);
                assertEquals(200, health.status());
                String healthJson = new String(health.body(), StandardCharsets.UTF_8);
                assertTrue(healthJson.contains("\"status\":\"ok\""), healthJson);
                assertTrue(healthJson.contains("\"onlineMode\":true"), healthJson);
                assertTrue(healthJson.contains("\"localCheckoutAllowed\":false"), healthJson);
                assertTrue(healthJson.contains("\"mcpEndpoint\":\"/mcp\""), healthJson);
                assertTrue(healthJson.contains("\"mcpProtocolVersion\":\"2025-11-25\""), healthJson);
                assertTrue(healthJson.contains("\"projectionProfiles\":[\"strict\",\"dfd-export\"]"), healthJson);
                assertTrue(healthJson.contains("\"defaultProjectionProfile\":\"strict\""), healthJson);

                // GET /api/v1 capabilities advertise onlineMode, localCheckoutAllowed=false, MCP & projection profiles
                var caps = api.handle("GET", "/api/v1", null, new byte[0], () -> false);
                assertEquals(200, caps.status());
                String capsJson = new String(caps.body(), StandardCharsets.UTF_8);
                assertTrue(capsJson.contains("\"onlineMode\":true"), capsJson);
                assertTrue(capsJson.contains("\"localCheckoutAllowed\":false"), capsJson);
                assertTrue(capsJson.contains("\"mcpEndpoint\":\"/mcp\""), capsJson);
                assertTrue(capsJson.contains("\"mcpProtocolVersion\":\"2025-11-25\""), capsJson);
                assertTrue(capsJson.contains("\"projectionProfiles\":[\"strict\",\"dfd-export\"]"), capsJson);
                assertTrue(capsJson.contains("\"defaultProjectionProfile\":\"strict\""), capsJson);

                // Job API maps online-mode local checkout rejection to HTTP 403 + exact code
                var nonMultiApi = new com.evolution.backend.query.ArchitectureQueryApi(
                        graphs, reports, com.evolution.backend.query.ApiLimits.defaults(), jobs, onlineConnections);
                var blocked = nonMultiApi.handle("POST", "/api/v1/analysis/connections", "application/json",
                        ("{\"source\":\"" + localRepo.toString().replace("\\", "\\\\") + "\"}")
                                .getBytes(StandardCharsets.UTF_8),
                        () -> false);
                assertEquals(403, blocked.status());
                String blockedJson = new String(blocked.body(), StandardCharsets.UTF_8);
                assertTrue(blockedJson.contains("LOCAL_CHECKOUT_DISABLED_IN_ONLINE_MODE"), blockedJson);
            }
        }
    }

    @Test
    void projectionProfileIsolatesReuseKeysAndKeepsOperationalTelemetrySeparateFromCanonicalDigest() throws Exception {
        Path graphsDir = Files.createDirectory(temp.resolve("prof-graphs"));
        Path jobsDir = Files.createDirectory(temp.resolve("prof-jobs"));
        var graphs = new FileGraphStore(graphsDir, 16_000_000L, GraphJson.StreamLimits.from(GraphJson.Limits.defaults()));
        var reports = new PublishedReports();
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:test:profile-reuse-isolation");

        var snapStrict = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("snap-strict"));
        var evStrict = com.evolution.analysis.graph.GraphEvidence.capture("fixture.profile", java.util.Map.of("profile", "strict"));
        var strictGraph = new KnowledgeGraph(snapStrict,
                List.of(new KnowledgeGraph.Context(snapStrict.value(), snapStrict, Optional.empty(), evStrict.identity())),
                List.of(), List.of(), List.of(evStrict));

        var snapDfd = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("snap-dfd"));
        var evDfd = com.evolution.analysis.graph.GraphEvidence.capture("fixture.profile", java.util.Map.of("profile", "dfd-export"));
        var dfdGraph = new KnowledgeGraph(snapDfd,
                List.of(new KnowledgeGraph.Context(snapDfd.value(), snapDfd, Optional.empty(), evDfd.identity())),
                List.of(), List.of(), List.of(evDfd));

        var verifiedInputs = ContentDigest.sha256Utf8("same-commit-and-inputs");
        var setup = new AnalysisJobs.Setup("repo", repoId, 21, false, Optional.empty(), Optional.empty());
        var runCount = new java.util.concurrent.atomic.AtomicInteger();
        var runner = (AnalysisJobs.Runner) control -> {
            control.stage(AnalysisJobs.Stage.ACQUISITION);
            int seq = runCount.incrementAndGet();
            // Simulate Cold (non-zero remote attempts) vs Warm (0 attempts) operational telemetry
            control.recordAcquisitionTelemetry(seq == 1 ? 9L : 0L, seq == 1 ? 3L : 0L);
            control.beginReuseCheck();
            var reused = control.reuse(verifiedInputs);
            if (reused.isPresent()) {
                return reused.orElseThrow();
            }
            control.stage(AnalysisJobs.Stage.ANALYSIS);
            KnowledgeGraph selected = "dfd-export".equals(control.projectionProfile()) ? dfdGraph : strictGraph;
            return new AnalysisJobs.Outcome(AnalysisJobs.Completion.COMPLETE, Optional.of(selected), List.of(), List.of(),
                    Optional.of(ContentDigest.sha256Utf8("acq")), Optional.of(ContentDigest.sha256Utf8("src")));
        };

        try (var disk = new AnalysisJobDiskStore(jobsDir);
             var jobs = new AnalysisJobs(List.of(new AnalysisJobs.Registration(setup, runner)),
                     graphs, reports, new AnalysisJobs.Limits(1, 4, 16, 30_000, 1_000), disk)) {
            var api = new com.evolution.backend.query.ArchitectureQueryApi(
                    graphs, reports, com.evolution.backend.query.ApiLimits.defaults(), jobs);

            // 1. Cold "strict" job executes (NO_MATCH), records acquisitionAttempts=9, pomAttempts=3
            var s1 = awaitTerminal(jobs, submitHttp(api, "{\"setup\":\"repo\",\"idempotencyKey\":\"s1\",\"projectionProfile\":\"strict\"}"));
            assertEquals(Optional.of("strict"), s1.projectionProfile());
            assertEquals(Optional.of(AnalysisJobs.ReuseDecision.NO_MATCH), s1.reuseDecision());
            assertEquals(Optional.of(strictGraph.identity()), s1.result().orElseThrow().graph());
            assertEquals(new AnalysisJobs.AcquisitionSummary(9L, 3L, "strict"),
                    jobs.acquisitionSummary(java.util.UUID.fromString(s1.id())).orElseThrow());

            // 2. "dfd-export" job on the EXACT same repository/commit MUST NOT reuse s1's strict graph!
            var d1 = awaitTerminal(jobs, submitHttp(api, "{\"setup\":\"repo\",\"idempotencyKey\":\"d1\",\"projectionProfile\":\"dfd-export\"}"));
            assertEquals(Optional.of("dfd-export"), d1.projectionProfile());
            assertEquals(Optional.of(AnalysisJobs.ReuseDecision.NO_MATCH), d1.reuseDecision());
            assertEquals(Optional.of(dfdGraph.identity()), d1.result().orElseThrow().graph());
            assertNotEquals(s1.result().orElseThrow().graph(), d1.result().orElseThrow().graph());

            // 3. Second "dfd-export" job reuses d1 (NOT s1)
            var d2 = awaitTerminal(jobs, submitHttp(api, "{\"setup\":\"repo\",\"idempotencyKey\":\"d2\",\"projectionProfile\":\"dfd-export\"}"));
            assertEquals(Optional.of(AnalysisJobs.ReuseDecision.REUSED), d2.reuseDecision());
            assertEquals(d1.id(), d2.reuse().orElseThrow().sourceJob());
            assertEquals(Optional.of(dfdGraph.identity()), d2.result().orElseThrow().graph());

            // 4. Forced re-run on "strict" with DIFFERENT operational acquisitionAttempts (0 vs 9) keeps canonical
            //    Result bit-identical and MUST NOT poison the reuse index with ANALYSIS_REPLAY_CONFLICT!
            var sForced = awaitTerminal(jobs, submitHttp(api,
                    "{\"setup\":\"repo\",\"idempotencyKey\":\"s-forced\",\"force\":true,\"projectionProfile\":\"strict\"}"));
            assertEquals(Optional.of(strictGraph.identity()), sForced.result().orElseThrow().graph());
            assertEquals(s1.result(), sForced.result(), "Canonical Result must remain invariant to operational attempt counts");
            assertEquals(new AnalysisJobs.AcquisitionSummary(0L, 0L, "strict"),
                    jobs.acquisitionSummary(java.util.UUID.fromString(sForced.id())).orElseThrow());

            // 5. Subsequent non-forced "strict" job still gets a clean REUSED hit on strictGraph
            var s2 = awaitTerminal(jobs, submitHttp(api, "{\"setup\":\"repo\",\"idempotencyKey\":\"s2\",\"projectionProfile\":\"strict\"}"));
            assertEquals(Optional.of(AnalysisJobs.ReuseDecision.REUSED), s2.reuseDecision());
            assertEquals(Optional.of(strictGraph.identity()), s2.result().orElseThrow().graph());
        }
    }

    @Test
    void localAnalysisRunnerThreadsPerJobProjectionProfileAndExposesAcquisitionSummaryWithoutAlteringGraphDigest() throws Exception {
        Path repoDir = Files.createDirectory(temp.resolve("real-runner-repo"));
        Files.writeString(repoDir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>svc-a</artifactId>"
                        + "<version>1.0</version><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        Files.createDirectories(repoDir.resolve("src/main/java/demo"));
        Files.writeString(repoDir.resolve("src/main/java/demo/App.java"),
                "package demo; public class App { public String ping() { return \"ok\"; } }\n");

        Path cache = Files.createDirectory(temp.resolve("real-runner-m2"));
        Path storeDir = Files.createDirectory(temp.resolve("real-runner-store"));
        Path intakeDir = storeDir.resolve("acquisition");
        Path jobsDir = storeDir.resolve("jobs");
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:test:real-runner-profile");
        var input = new LocalAnalysisRunner.Input(repoDir, repoId, Path.of(System.getProperty("java.home")), 21, cache,
                Optional.of("deadbeef"), false, false, List.of(), Optional.empty(), LocalAnalysisRunner.SpringInput.absent());
        var runner = new LocalAnalysisRunner(input, intakeDir, Optional.empty(),
                LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(), Optional.of(storeDir));
        var graphStore = new FileGraphStore(storeDir, 64_000_000L, GraphJson.StreamLimits.from(GraphJson.Limits.defaults()));
        var reports = new PublishedReports();

        try (var disk = new AnalysisJobDiskStore(jobsDir);
             var jobs = new AnalysisJobs(List.of(runner.registration("demo")), graphStore, reports,
                     new AnalysisJobs.Limits(1, 4, 16, 120_000, 1_000), disk)) {
            var api = new com.evolution.backend.query.ArchitectureQueryApi(
                    graphStore, reports, com.evolution.backend.query.ApiLimits.defaults(), jobs);

            String strictJobId = submitHttp(api, "{\"setup\":\"demo\",\"idempotencyKey\":\"real-strict\",\"projectionProfile\":\"strict\"}");
            var strictDone = awaitTerminal(jobs, strictJobId);
            assertTrue(strictDone.state() == AnalysisJobs.State.SUCCEEDED || strictDone.state() == AnalysisJobs.State.PARTIAL);

            String dfdJobId = submitHttp(api, "{\"setup\":\"demo\",\"idempotencyKey\":\"real-dfd\",\"projectionProfile\":\"dfd-export\"}");
            var dfdDone = awaitTerminal(jobs, dfdJobId);
            assertTrue(dfdDone.state() == AnalysisJobs.State.SUCCEEDED || dfdDone.state() == AnalysisJobs.State.PARTIAL);
            // Distinct projection profiles must never reuse each other even on identical repo/commit
            assertNotEquals(Optional.of(AnalysisJobs.ReuseDecision.REUSED), dfdDone.reuseDecision());

            var statusResp = api.handle("GET", "/api/v1/analysis/jobs/" + strictJobId, null, new byte[0], () -> false);
            assertEquals(200, statusResp.status());
            var statusData = GraphJson.object(GraphJson.object(
                    GraphJson.read(new String(statusResp.body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults())).get("data"));
            assertEquals("strict", statusData.get("projectionProfile"));
            var acqSummary = GraphJson.object(statusData.get("acquisitionSummary"));
            assertTrue(((Number) acqSummary.get("acquisitionAttempts")).longValue() >= 0L);
            assertTrue(((Number) acqSummary.get("pomAttempts")).longValue() >= 0L);
            assertEquals("strict", acqSummary.get("projectionProfile"));
        }
    }

    private static String submitHttp(com.evolution.backend.query.ArchitectureQueryApi api, String json) {
        var resp = api.handle("POST", "/api/v1/analysis/jobs", "application/json",
                json.getBytes(StandardCharsets.UTF_8), () -> false);
        assertEquals(202, resp.status(), () -> new String(resp.body(), StandardCharsets.UTF_8));
        var data = GraphJson.object(GraphJson.object(
                GraphJson.read(new String(resp.body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults())).get("data"));
        return GraphJson.string(data.get("id"));
    }

    private static AnalysisJobs.View awaitTerminal(AnalysisJobs jobs, String id) throws InterruptedException {
        var uuid = java.util.UUID.fromString(id);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            var v = jobs.get(uuid);
            if (AnalysisJobs.terminal(v.state())) {
                return v;
            }
            Thread.sleep(20);
        }
        fail("Timed out awaiting job " + id);
        return null;
    }
}
