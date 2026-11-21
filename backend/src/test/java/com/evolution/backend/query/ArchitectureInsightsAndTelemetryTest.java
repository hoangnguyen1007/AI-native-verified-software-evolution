package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.query.ArchitectureQuery.*;
import com.evolution.backend.analysis.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureInsightsAndTelemetryTest {

    @Test
    void architectureInsightsDetectsExactTarjanTanglesCutEdgesBlastRadiusAndMarkdown() {
        var f = new QueryFixture()
                .node("Entry")
                .node("A")
                .node("B")
                .node("C")
                .node("Core")
                .edge("Entry", "A")
                .edge("A", "B")
                .edge("B", "C")
                .edge("C", "A")
                .edge("C", "Core");
        var api = new ArchitectureQueryApi(f.store());
        var graph = f.graph();
        byte[] payload = CanonicalJson.write(Map.of(
                "request", f.request(),
                "arguments", Map.of("topN", 10))).getBytes(StandardCharsets.UTF_8);

        var r1 = api.handle("POST", "/api/v1/graphs/" + graph.identity().value() + "/architecture-insights",
                "application/json", payload, () -> false);
        var r2 = api.handle("POST", "/api/v1/graphs/" + graph.identity().value() + "/architecture-insights",
                "application/json", payload, () -> false);
        assertEquals(200, r1.status());
        assertArrayEquals(r1.body(), r2.body(), "Architecture insights must be byte-for-bit deterministic");

        var env = GraphJson.object(GraphJson.read(new String(r1.body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
        var data = GraphJson.object(env.get("data"));
        assertEquals(CanonicalJson.sha256(data).value(), env.get("resultIdentity"));

        var summary = GraphJson.object(data.get("summary"));
        assertEquals(5, ((Number) summary.get("analyzedEntities")).intValue());
        assertEquals(5, ((Number) summary.get("internalDependencies")).intValue());
        assertEquals(1, ((Number) summary.get("tangleCount")).intValue());
        assertEquals(3, ((Number) summary.get("tangledEntityCount")).intValue());
        assertEquals(4, ((Number) summary.get("maxBlastRadius")).intValue());

        var tangles = GraphJson.array(data.get("tangles"));
        assertEquals(1, tangles.size());
        var tangle = GraphJson.object(tangles.getFirst());
        assertEquals(3, ((Number) tangle.get("memberCount")).intValue());
        var cuts = GraphJson.array(tangle.get("breakCandidates"));
        assertFalse(cuts.isEmpty());
        var bestCut = GraphJson.object(cuts.getFirst());
        assertEquals(Boolean.TRUE, bestCut.get("breaksEntireTangle"));
        assertEquals(1, ((Number) bestCut.get("remainingLargestComponentSize")).intValue());

        String md = GraphJson.string(data.get("markdownReport"));
        assertTrue(md.contains("# Architecture Intelligence & Refactoring Executive Report"));
        assertTrue(md.contains("Tangle #1"));
    }

    @Test
    void fastArchitectureModeAndPerStageTelemetryStayIsolatedFromFullReuse(@TempDir Path temp) throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo"));
        Path cache = Files.createDirectory(temp.resolve("cache"));
        Path storeDir = Files.createDirectory(temp.resolve("store"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>sample</groupId>
                  <artifactId>fast-demo</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """);
        Path pkg = Files.createDirectories(repo.resolve("src/main/java/sample"));
        Files.writeString(pkg.resolve("OrderController.java"), """
                package sample;
                public class OrderController {
                    private final OrderService service = new OrderService();
                    public String handle() { return service.process(); }
                }
                """);
        Files.writeString(pkg.resolve("OrderService.java"), """
                package sample;
                public class OrderService {
                    public String process() {
                        String localNoise = "intra-method";
                        return localNoise.trim();
                    }
                }
                """);

        Path graphsDir = Files.createDirectory(storeDir.resolve("graphs"));
        var graphs = new FileGraphStore(graphsDir, 64_000_000L, new GraphJson.StreamLimits(64_000_000L, 128, 64_000_000L));
        var reports = new PublishedReports();
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/fast-demo.git");
        var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(
                repo, repoId, Path.of(System.getProperty("java.home")), 21, cache,
                Optional.of("rev-1"), false, false, List.of(), Optional.empty(), LocalAnalysisRunner.SpringInput.absent()),
                storeDir.resolve("intake"));

        try (var disk = new AnalysisJobDiskStore(storeDir.resolve("jobs"));
             var jobs = new AnalysisJobs(List.of(runner.registration("app")), graphs, reports,
                new AnalysisJobs.Limits(1, 4, 16, 60_000, 1_000), disk)) {
            var api = new ArchitectureQueryApi(graphs, reports, ApiLimits.defaults(), jobs);

            var subFast1 = postJson(api, "/api/v1/analysis/jobs",
                    Map.of("setup", "app", "idempotencyKey", "fast-1", "mode", "FAST_ARCHITECTURE"));
            assertEquals(202, subFast1.status());
            String fastId1 = GraphJson.string(GraphJson.object(body(subFast1).get("data")).get("id"));
            var fastDone1 = awaitJob(jobs, fastId1);
            assertTrue(List.of(AnalysisJobs.State.SUCCEEDED, AnalysisJobs.State.PARTIAL).contains(fastDone1.state()));

            var telResp = api.handle("GET", "/api/v1/analysis/jobs/" + fastId1 + "/telemetry", null, new byte[0], () -> false);
            assertEquals(200, telResp.status());
            var tel = GraphJson.object(body(telResp).get("data"));
            assertEquals("FAST_ARCHITECTURE", tel.get("mode"));
            assertEquals(Boolean.FALSE, tel.get("reused"));
            var stageMillis = GraphJson.object(tel.get("stageMillis"));
            assertTrue(stageMillis.containsKey("ACQUISITION"));
            assertTrue(stageMillis.containsKey("ANALYSIS"));
            assertTrue(stageMillis.containsKey("GRAPH"));

            // Verify FAST_ARCHITECTURE vs FULL graph distinction (omits raw bytesBase64 bloat while keeping structure)
            var subFull = postJson(api, "/api/v1/analysis/jobs",
                    Map.of("setup", "app", "idempotencyKey", "full-1"));
            String fullId = GraphJson.string(GraphJson.object(body(subFull).get("data")).get("id"));
            var fullDone = awaitJob(jobs, fullId);
            assertTrue(List.of(AnalysisJobs.State.SUCCEEDED, AnalysisJobs.State.PARTIAL).contains(fullDone.state()));
            var fastGraphId = fastDone1.result().orElseThrow().graph().orElseThrow();
            var fullGraphId = fullDone.result().orElseThrow().graph().orElseThrow();
            assertNotEquals(fastGraphId, fullGraphId);
            var fastGraph = graphs.load(fastGraphId).orElseThrow();
            var fullGraph = graphs.load(fullGraphId).orElseThrow();
            assertTrue(fullGraph.evidence().stream().anyMatch(e -> e.family().equals("source.content") && e.canonicalJson().contains("bytesBase64")));
            assertFalse(fastGraph.evidence().stream().anyMatch(e -> e.family().equals("source.content") && e.canonicalJson().contains("bytesBase64")));
        }
    }

    private static ArchitectureQueryApi.Response postJson(ArchitectureQueryApi api, String path, Object payload) {
        return api.handle("POST", path, "application/json",
                CanonicalJson.write(payload).getBytes(StandardCharsets.UTF_8), () -> false);
    }

    private static Map<String, Object> body(ArchitectureQueryApi.Response response) {
        return GraphJson.object(GraphJson.read(new String(response.body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
    }

    private static AnalysisJobs.View awaitJob(AnalysisJobs jobs, String id) throws InterruptedException {
        var uuid = java.util.UUID.fromString(id);
        for (int i = 0; i < 600; i++) {
            var view = jobs.get(uuid);
            if (AnalysisJobs.terminal(view.state())) return view;
            Thread.sleep(25);
        }
        fail("Timed out waiting for job " + id);
        return null;
    }
}
