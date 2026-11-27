package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.backend.analysis.AnalysisJobDiskStore;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.analysis.FastJavaSkeletonScanner;
import com.evolution.backend.analysis.PreliminaryGraphBuilder;
import com.evolution.backend.analysis.WorkbenchPreviewStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisJobSseAndProgressTest {
    @TempDir Path tempDir;

    private record SseFrame(String id, String event, String data) {
    }

    @Test
    void streamsLiveProgressiveStagesPreviewIncrementalMetricsAndPersistsV5AcrossRestart() throws Exception {
        var finalGraph = new QueryFixture().node("alpha").graph();
        var store = new InMemoryGraphStore();
        var reports = new PublishedReports();
        var previewStore = new WorkbenchPreviewStore();
        var allowProceed = new CountDownLatch(1);
        var previewPublished = new CountDownLatch(1);
        var previewGraphIdRef = new AtomicReference<ContentDigest>();

        var registration = AnalysisJobsTest.registration("progressive", control -> {
            control.stage(AnalysisJobs.Stage.ACQUISITION);
            control.registerModules(List.of("core", "api"));
            control.reportProgress(1, 2, "Acquiring repository");

            // Build real Stage 1 preliminary skeleton graph
            var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:jobs");
            var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("snap-progressive"));
            String src = "package com.demo;\n@Service\npublic class BillingService {}\n";
            var skeleton = new FastJavaSkeletonScanner().scan(List.of(
                    new FastJavaSkeletonScanner.SourceFile("core/src/main/java/com/demo/BillingService.java", "core",
                            ContentDigest.sha256Utf8(src), src)),
                    control);
            var previewGraph = new PreliminaryGraphBuilder().build(PreliminaryGraphBuilder.Input.stage1(
                    repo, snap,
                    List.of(new PreliminaryGraphBuilder.ModuleInfo("core", "core", "MAVEN", 1)),
                    skeleton, List.of("core", "api")), control);
            var pub = control.publishPreview(previewGraph, AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE,
                    0, 2, List.of("core", "api"));
            previewGraphIdRef.set(pub.graphId());
            previewPublished.countDown();

            assertTrue(allowProceed.await(5, TimeUnit.SECONDS));

            control.stage(AnalysisJobs.Stage.ANALYSIS);
            control.reportModuleUpdate("core", "COMPLETED", 1, 1, 42L, Optional.of("ctx-core"), Optional.empty());
            control.reportModuleUpdate("api", "REUSED", 0, 0, 5L, Optional.of("ctx-api"), Optional.of("INCREMENTAL_HIT"));
            control.recordIncrementalMetrics(new AnalysisJobs.IncrementalMetrics(
                    Optional.of("snapshot:sha256:0000000000000000000000000000000000000000000000000000000000000001"),
                    1, 2, 0, 48, 3, 1, 1, 9500L));
            control.recordCacheMetrics(Map.of("frontendCacheHits", 12L));
            return AnalysisJobsTest.output(finalGraph);
        });

        String jobId;
        try (var disk = new AnalysisJobDiskStore(tempDir);
             var jobs = new AnalysisJobs(List.of(registration), store, reports, AnalysisJobs.Limits.defaults(), disk, previewStore);
             var server = new ArchitectureHttpServer(new ArchitectureQueryApi(store, reports, ApiLimits.defaults(), jobs), 0, 120L)) {
            server.start();
            var client = HttpClient.newHttpClient();

            jobId = jobs.submit("progressive", "prog-1").job().id();
            assertTrue(previewPublished.await(5, TimeUnit.SECONDS));

            // 1. Check GET /api/v1/analysis/jobs/{id}/progress while running
            var progRes = client.send(HttpRequest.newBuilder(server.uri().resolve("/api/v1/analysis/jobs/" + jobId + "/progress"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, progRes.statusCode());
            var progData = GraphJson.object(GraphJson.object(GraphJson.read(progRes.body(), GraphJson.Limits.defaults())).get("data"));
            assertEquals("PRELIMINARY_STRUCTURE", progData.get("truthTier"));
            assertEquals(previewGraphIdRef.get().value(), progData.get("previewGraphId"));

            // 2. Check enriched GET /api/v1/analysis/jobs/{id}
            var statusRes = client.send(HttpRequest.newBuilder(server.uri().resolve("/api/v1/analysis/jobs/" + jobId))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, statusRes.statusCode());
            var statusData = GraphJson.object(GraphJson.object(GraphJson.read(statusRes.body(), GraphJson.Limits.defaults())).get("data"));
            assertEquals("PRELIMINARY_STRUCTURE", statusData.get("publicationStage"));
            assertEquals(previewGraphIdRef.get().value(), statusData.get("previewGraphId"));

            // 3. Open live SSE stream, verify heartbeat + live events through job_completed
            var sseReq = HttpRequest.newBuilder(server.uri().resolve("/api/v1/analysis/jobs/" + jobId + "/events"))
                    .header("Accept", "text/event-stream")
                    .GET().build();
            var sseRes = client.send(sseReq, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, sseRes.statusCode());
            assertEquals("text/event-stream; charset=utf-8", sseRes.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("no-store", sseRes.headers().firstValue("Cache-Control").orElseThrow());

            var frames = new ArrayList<SseFrame>();
            boolean sawPing = false;
            try (var reader = new BufferedReader(new InputStreamReader(sseRes.body(), StandardCharsets.UTF_8))) {
                String id = null, event = null, data = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.equals(": ping")) {
                        sawPing = true;
                        // Unblock job after seeing at least one heartbeat
                        allowProceed.countDown();
                        continue;
                    }
                    if (line.startsWith("id: ")) id = line.substring(4);
                    else if (line.startsWith("event: ")) event = line.substring(7);
                    else if (line.startsWith("data: ")) data = line.substring(6);
                    else if (line.isEmpty() && event != null) {
                        frames.add(new SseFrame(id, event, data));
                        if ("snapshot".equals(event)) {
                            // Snapshot arrived; wait briefly for heartbeat then release
                        }
                        if ("job_completed".equals(event)) {
                            break;
                        }
                        id = null; event = null; data = null;
                    }
                }
            }
            assertTrue(sawPing, "Expected at least one SSE heartbeat ': ping'");
            var eventNames = frames.stream().map(SseFrame::event).toList();
            assertTrue(eventNames.contains("snapshot"));
            assertTrue(eventNames.contains("module_completed"));
            assertTrue(eventNames.contains("incremental_summary"));
            assertTrue(eventNames.contains("job_completed"));

            // 4. Test Last-Event-ID reconnect replays missed events cleanly
            String firstSeq = frames.get(1).id();
            var reconnectRes = client.send(HttpRequest.newBuilder(server.uri().resolve("/api/v1/analysis/jobs/" + jobId + "/events"))
                    .header("Accept", "text/event-stream")
                    .header("Last-Event-ID", firstSeq)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reconnectRes.statusCode());
            assertTrue(reconnectRes.body().contains("event: job_completed"));
        }

        // 5. Restart from disk (m8.4-v5) and verify stageMillis + cacheMetrics + IncrementalMetrics survive!
        try (var disk = new AnalysisJobDiskStore(tempDir);
             var jobs = new AnalysisJobs(List.of(registration), store, reports, AnalysisJobs.Limits.defaults(), disk)) {
            var saved = disk.get(UUID.fromString(jobId)).orElseThrow();
            assertEquals(AnalysisJobDiskStore.SCHEMA_V5, saved.schema());
            assertTrue(saved.telemetry().isPresent());

            var telemetry = jobs.telemetry(UUID.fromString(jobId));
            assertEquals(12L, telemetry.cacheMetrics().get("frontendCacheHits"));
            assertTrue(telemetry.incremental().isPresent());
            assertEquals(48, telemetry.incremental().orElseThrow().reusedFiles());
            assertEquals(3, telemetry.incremental().orElseThrow().abiEarlyCutoffCount());
            assertEquals(9500L, telemetry.incremental().orElseThrow().timeSavedEstimateMs());

            var progress = jobs.progress(UUID.fromString(jobId));
            assertEquals(AnalysisJobs.TruthTier.DEEP_SEMANTIC_COMPLETE, progress.truthTier());
            assertTrue(progress.incremental().isPresent());
            assertEquals(48, progress.incremental().orElseThrow().reusedFiles());
        }
    }

    @Test
    void sseStreamsDoNotConsumeQuerySlotsAndClientDisconnectDoesNotCancelJob() throws Exception {
        var graph = new QueryFixture().node("x").graph();
        var store = new InMemoryGraphStore();
        var reports = new PublishedReports();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var jobCancelledObserved = new AtomicBoolean(false);

        var reg = AnalysisJobsTest.registration("long", control -> {
            started.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                jobCancelledObserved.set(true);
                throw interrupted;
            }
            return AnalysisJobsTest.output(graph);
        });

        // Only 2 general HTTP query slots, yet we will hold 4 simultaneous SSE streams open!
        var tightLimits = new ApiLimits(65_536, 16_777_216, 32, 100_000, 2, 10_000L);
        try (var jobs = new AnalysisJobs(List.of(reg), store, reports, AnalysisJobs.Limits.defaults());
             var server = new ArchitectureHttpServer(new ArchitectureQueryApi(store, reports, tightLimits, jobs), 0, 500L)) {
            server.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            String jobId = jobs.submit("long", "iso-1").job().id();
            assertTrue(started.await(3, TimeUnit.SECONDS));

            // Open 4 simultaneous SSE streams (2x larger than ApiLimits.concurrentRequests == 2!)
            var openStreams = new ArrayList<HttpResponse<java.io.InputStream>>();
            for (int i = 0; i < 4; i++) {
                var res = client.send(
                        HttpRequest.newBuilder(server.uri().resolve("/api/v1/analysis/jobs/" + jobId + "/events"))
                                .header("Accept", "text/event-stream").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, res.statusCode());
                openStreams.add(res);
            }
            // Wait until all 4 subscribers are registered
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (jobs.eventBroker().subscriberCount(jobId) < 4 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(4, jobs.eventBroker().subscriberCount(jobId));

            // Normal API calls MUST still succeed immediately (zero query slots consumed by SSE!)
            var discovery = client.send(HttpRequest.newBuilder(server.uri().resolve("/api/v1"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, discovery.statusCode());
            var prog = client.send(HttpRequest.newBuilder(server.uri().resolve("/api/v1/analysis/jobs/" + jobId + "/progress"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, prog.statusCode());

            // Security & protocol checks on SSE endpoint
            var badOrigin = client.send(HttpRequest.newBuilder(server.uri().resolve("/api/v1/analysis/jobs/" + jobId + "/events"))
                    .header("Origin", "https://evil.test").header("Accept", "text/event-stream").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, badOrigin.statusCode());
            var badMethod = client.send(HttpRequest.newBuilder(server.uri().resolve("/api/v1/analysis/jobs/" + jobId + "/events"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, badMethod.statusCode());
            assertEquals("GET", badMethod.headers().firstValue("Allow").orElseThrow());
            var badAccept = client.send(HttpRequest.newBuilder(server.uri().resolve("/api/v1/analysis/jobs/" + jobId + "/events"))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(406, badAccept.statusCode());
            var badLastId = client.send(HttpRequest.newBuilder(server.uri().resolve("/api/v1/analysis/jobs/" + jobId + "/events"))
                    .header("Accept", "text/event-stream").header("Last-Event-ID", "not-a-number").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, badLastId.statusCode());
            var withQuery = client.send(HttpRequest.newBuilder(server.uri().resolve("/api/v1/analysis/jobs/" + jobId + "/events?x=1"))
                    .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, withQuery.statusCode());
            var missing = client.send(HttpRequest.newBuilder(server.uri().resolve("/api/v1/analysis/jobs/" + UUID.randomUUID() + "/events"))
                    .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());

            // Abruptly close all 4 SSE client streams -> job MUST remain RUNNING (not cancelled)!
            for (var s : openStreams) {
                s.body().close();
            }
            assertEquals(AnalysisJobs.State.RUNNING, jobs.get(UUID.fromString(jobId)).state());
            assertFalse(jobCancelledObserved.get());

            // Release job -> completes cleanly
            release.countDown();
            var completed = AnalysisJobsTest.finished(jobs, jobId);
            assertEquals(AnalysisJobs.State.SUCCEEDED, completed.state());
            assertFalse(jobCancelledObserved.get());
        } finally {
            release.countDown();
        }
    }
}
