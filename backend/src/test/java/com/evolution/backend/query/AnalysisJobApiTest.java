package com.evolution.backend.query;

import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.analysis.RepositoryConnections;
import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisJobApiTest {
    @TempDir Path connectionRoot;
    @Test void optInConnectionsRegisterRealSourcesWithoutExposingHostPaths() throws Exception {
        var source=Files.createDirectory(connectionRoot.resolve("application"));
        var store=new InMemoryGraphStore(); var reports=new PublishedReports();
        try (var jobs=new AnalysisJobs(List.of(),store,reports,AnalysisJobs.Limits.defaults())) {
            var disabled=new ArchitectureQueryApi(store,reports,ApiLimits.defaults(),jobs);
            assertEquals(503,call(disabled,"GET","/api/v1/analysis/connections","").status());
            var connections=new RepositoryConnections(jobs,Path.of(System.getProperty("java.home")),21,connectionRoot,connectionRoot,Optional.empty());
            var api=new ArchitectureQueryApi(store,reports,ApiLimits.defaults(),jobs,connections);
            var created=call(api,"POST","/api/v1/analysis/connections",CanonicalJson.write(Map.of("source",source.toString())));
            assertEquals(201,created.status()); assertEquals("application",data(created).get("label"));
            assertFalse(new String(created.body(),StandardCharsets.UTF_8).contains(connectionRoot.toString()));
            assertEquals(1,jobs.setups().size());
            assertEquals(200,call(api,"GET","/api/v1/analysis/connections","").status());
            assertEquals(400,call(api,"POST","/api/v1/analysis/connections","{\"source\":\"https://user:secret@github.com/team/repo\"}").status());
            assertEquals(400,call(api,"POST","/api/v1/analysis/connections","{\"source\":\"https://github.com/team/repo\",\"command\":\"exec\"}").status());
            assertEquals(405,call(api,"DELETE","/api/v1/analysis/connections","").status());
            var setup = (String) data(created).get("setup");
            var deleted = call(api, "DELETE", "/api/v1/analysis/connections/" + setup, "");
            assertEquals(200, deleted.status());
            assertEquals(setup, data(deleted).get("disconnected"));
            assertEquals(0, connections.list().size());
            assertEquals(0, jobs.setups().size());
            assertEquals(404, call(api, "DELETE", "/api/v1/analysis/connections/missing-repo", "").status());
        }
    }
    static Map<String,Object> data(ArchitectureQueryApi.Response response) {
        var body = GraphJson.object(GraphJson.read(new String(response.body(),StandardCharsets.UTF_8),GraphJson.Limits.defaults()));
        assertEquals(ContentDigest.sha256Utf8(CanonicalJson.write(body.get("data"))).value(),body.get("resultIdentity"));
        return GraphJson.object(body.get("data"));
    }
    static ArchitectureQueryApi.Response call(ArchitectureQueryApi api,String method,String path,String body) {
        return api.handle(method,path,"application/json",body.getBytes(StandardCharsets.UTF_8),() -> false);
    }
    @Test void discoveryExplicitlyDescribesAnalysisJobAvailability() {
        var api = new ArchitectureQueryApi(new QueryFixture().store());
        var response = api.handle("GET", "/api/v1", null, new byte[0], () -> false);
        assertEquals(200, response.status());
        var envelope = GraphJson.object(GraphJson.read(new String(response.body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
        var data = GraphJson.object(envelope.get("data"));
        assertEquals("architecture.jobs:m8.1-v1", data.get("jobVersion"));
        assertEquals(false, data.get("analysisJobsEnabled"));
        assertEquals("/mcp", data.get("mcpEndpoint"));
        assertEquals("2025-11-25", data.get("mcpProtocolVersion"));
        assertEquals(List.of("strict", "dfd-export"), data.get("projectionProfiles"));
        assertEquals("strict", data.get("defaultProjectionProfile"));

        var healthResp = api.handle("GET", "/api/v1/health", null, new byte[0], () -> false);
        assertEquals(200, healthResp.status());
        var health = data(healthResp);
        assertEquals("ok", health.get("status"));
        assertEquals("/mcp", health.get("mcpEndpoint"));
        assertEquals("2025-11-25", health.get("mcpProtocolVersion"));
        assertEquals(List.of("strict", "dfd-export"), health.get("projectionProfiles"));
        assertEquals("strict", health.get("defaultProjectionProfile"));

        String previousProp = System.getProperty("evo.projection.profile");
        System.setProperty("evo.projection.profile", "dfd-export");
        try {
            assertEquals("dfd-export", data(api.handle("GET", "/api/v1", null, new byte[0], () -> false))
                    .get("defaultProjectionProfile"));
            assertEquals("dfd-export", data(api.handle("GET", "/api/v1/health", null, new byte[0], () -> false))
                    .get("defaultProjectionProfile"));
        } finally {
            if (previousProp == null) {
                System.clearProperty("evo.projection.profile");
            } else {
                System.setProperty("evo.projection.profile", previousProp);
            }
        }
    }

    @Test void perJobProjectionProfileValidationPersistenceAndOperationalTelemetry() throws Exception {
        var strictGraph = new QueryFixture().node("strict-node").graph();
        var dfdGraph = new QueryFixture().node("dfd-node").graph();
        var store = new InMemoryGraphStore();
        var reports = new PublishedReports();
        var seenProfiles = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var registration = AnalysisJobsTest.registration("local", control -> {
            String profile = control.projectionProfile();
            seenProfiles.add(profile);
            control.recordAcquisitionTelemetry("strict".equals(profile) ? 5L : 12L, "strict".equals(profile) ? 2L : 4L);
            return AnalysisJobsTest.output("dfd-export".equals(profile) ? dfdGraph : strictGraph);
        });
        Path jobsDir = Files.createDirectory(connectionRoot.resolve("profile-jobs"));
        String strictId;
        String dfdId;
        String previousProp = System.getProperty("evo.projection.profile");
        System.clearProperty("evo.projection.profile");
        try {
            try (var disk = new com.evolution.backend.analysis.AnalysisJobDiskStore(jobsDir);
                 var jobs = new AnalysisJobs(List.of(registration), store, reports, AnalysisJobs.Limits.defaults(), disk)) {
                var api = new ArchitectureQueryApi(store, reports, ApiLimits.defaults(), jobs);
                String route = "/api/v1/analysis/jobs";

                // Invalid non-blank values and non-strings MUST fail-closed with HTTP 400
                for (String invalid : List.of(
                        "{\"setup\":\"local\",\"idempotencyKey\":\"bad-1\",\"projectionProfile\":\"bogus\"}",
                        "{\"setup\":\"local\",\"idempotencyKey\":\"bad-2\",\"projectionProfile\":\"relaxed\"}",
                        "{\"setup\":\"local\",\"idempotencyKey\":\"bad-3\",\"projectionProfile\":123}",
                        "{\"setup\":\"local\",\"idempotencyKey\":\"bad-4\",\"projectionProfile\":true}",
                        "{\"setup\":\"local\",\"idempotencyKey\":\"bad-5\",\"projectionProfile\":[]}")) {
                    assertEquals(400, call(api, "POST", route, invalid).status(), invalid);
                }

                // Omitted / blank defaults to configuredProjectionProfile() ("strict")
                var createdStrict = call(api, "POST", route,
                        "{\"setup\":\"local\",\"idempotencyKey\":\"k-strict\",\"projectionProfile\":\"   \"}");
                assertEquals(202, createdStrict.status());
                assertEquals("strict", data(createdStrict).get("projectionProfile"));
                strictId = GraphJson.string(data(createdStrict).get("id"));
                AnalysisJobsTest.finished(jobs, strictId);

                // Explicit case-insensitive "DFD-EXPORT" normalizes to "dfd-export"
                var createdDfd = call(api, "POST", route,
                        "{\"setup\":\"local\",\"idempotencyKey\":\"k-dfd\",\"projectionProfile\":\"DFD-EXPORT\"}");
                assertEquals(202, createdDfd.status());
                assertEquals("dfd-export", data(createdDfd).get("projectionProfile"));
                dfdId = GraphJson.string(data(createdDfd).get("id"));
                AnalysisJobsTest.finished(jobs, dfdId);

                // Idempotency retry with mismatched projectionProfile fails with 409 IDEMPOTENCY_CONFLICT
                var conflict = call(api, "POST", route,
                        "{\"setup\":\"local\",\"idempotencyKey\":\"k-dfd\",\"projectionProfile\":\"strict\"}");
                assertEquals(409, conflict.status());
                assertTrue(new String(conflict.body(), StandardCharsets.UTF_8).contains("IDEMPOTENCY_CONFLICT"));

                // Same key + same profile is idempotent (200)
                var retryOk = call(api, "POST", route,
                        "{\"setup\":\"local\",\"idempotencyKey\":\"k-dfd\",\"projectionProfile\":\"dfd-export\"}");
                assertEquals(200, retryOk.status());
                assertEquals(dfdId, data(retryOk).get("id"));
                assertEquals("dfd-export", data(retryOk).get("projectionProfile"));

                // Status endpoint exposes projectionProfile + operational acquisitionSummary separate from canonical graph
                var strictView = data(call(api, "GET", route + "/" + strictId, ""));
                assertEquals("strict", strictView.get("projectionProfile"));
                assertEquals(strictGraph.identity().value(), GraphJson.object(strictView.get("result")).get("graph"));
                var strictAcq = GraphJson.object(strictView.get("acquisitionSummary"));
                assertEquals(5, ((Number) strictAcq.get("acquisitionAttempts")).intValue());
                assertEquals(2, ((Number) strictAcq.get("pomAttempts")).intValue());
                assertEquals("strict", strictAcq.get("projectionProfile"));

                var dfdView = data(call(api, "GET", route + "/" + dfdId, ""));
                assertEquals("dfd-export", dfdView.get("projectionProfile"));
                assertEquals(dfdGraph.identity().value(), GraphJson.object(dfdView.get("result")).get("graph"));
                var dfdAcq = GraphJson.object(dfdView.get("acquisitionSummary"));
                assertEquals(12, ((Number) dfdAcq.get("acquisitionAttempts")).intValue());
                assertEquals(4, ((Number) dfdAcq.get("pomAttempts")).intValue());
                assertEquals("dfd-export", dfdAcq.get("projectionProfile"));

                // Telemetry endpoint also exposes projectionProfile + acquisitionSummary
                var dfdTel = data(call(api, "GET", route + "/" + dfdId + "/telemetry", ""));
                assertEquals("dfd-export", dfdTel.get("projectionProfile"));
                assertEquals(12, ((Number) GraphJson.object(dfdTel.get("acquisitionSummary")).get("acquisitionAttempts")).intValue());

                // List endpoint & history endpoint persist projectionProfile on every item
                var listItems = GraphJson.array(data(call(api, "GET", route, "")).get("items"));
                assertEquals(2, listItems.size());
                var listedProfiles = listItems.stream().map(o -> GraphJson.string(GraphJson.object(o).get("projectionProfile"))).toList();
                assertTrue(listedProfiles.contains("strict") && listedProfiles.contains("dfd-export"), listedProfiles.toString());

                // When EVO_PROJECTION_PROFILE / -Devo.projection.profile is set to dfd-export, omitted field uses it
                System.setProperty("evo.projection.profile", "dfd-export");
                var envDefaulted = call(api, "POST", route, "{\"setup\":\"local\",\"idempotencyKey\":\"k-env-default\"}");
                assertEquals(202, envDefaulted.status());
                assertEquals("dfd-export", data(envDefaulted).get("projectionProfile"));
                AnalysisJobsTest.finished(jobs, GraphJson.string(data(envDefaulted).get("id")));
                assertEquals(List.of("strict", "dfd-export", "dfd-export"), seenProfiles);
            }

            // Reopen AnalysisJobDiskStore across restart: projectionProfile & acquisitionSummary survive durably
            System.clearProperty("evo.projection.profile");
            try (var reopenedDisk = new com.evolution.backend.analysis.AnalysisJobDiskStore(jobsDir);
                 var reopenedJobs = new AnalysisJobs(List.of(registration), store, reports, AnalysisJobs.Limits.defaults(), reopenedDisk)) {
                var reopenedApi = new ArchitectureQueryApi(store, reports, ApiLimits.defaults(), reopenedJobs);
                var reloadedDfd = data(call(reopenedApi, "GET", "/api/v1/analysis/jobs/" + dfdId, ""));
                assertEquals("dfd-export", reloadedDfd.get("projectionProfile"));
                var reloadedAcq = GraphJson.object(reloadedDfd.get("acquisitionSummary"));
                assertEquals(12, ((Number) reloadedAcq.get("acquisitionAttempts")).intValue());
                assertEquals(4, ((Number) reloadedAcq.get("pomAttempts")).intValue());
                assertEquals("dfd-export", reloadedAcq.get("projectionProfile"));

                // Cross-restart idempotency check also enforces projectionProfile match
                assertEquals(409, call(reopenedApi, "POST", "/api/v1/analysis/jobs",
                        "{\"setup\":\"local\",\"idempotencyKey\":\"k-dfd\",\"projectionProfile\":\"strict\"}").status());
            }
        } finally {
            if (previousProp == null) {
                System.clearProperty("evo.projection.profile");
            } else {
                System.setProperty("evo.projection.profile", previousProp);
            }
        }
    }
    @Test void submitDiscoverPollAndQueryUseTheExactPublishedDomainResults() throws Exception {
        var f = new QueryFixture().node("a"); var store = new InMemoryGraphStore(); var reports = new PublishedReports();
        try (var jobs = new AnalysisJobs(List.of(AnalysisJobsTest.registration("local",control -> AnalysisJobsTest.output(f.graph()))),store,reports,AnalysisJobsTest.limits())) {
            var api = new ArchitectureQueryApi(store,reports,ApiLimits.defaults(),jobs);
            assertEquals(true,data(call(api,"GET","/api/v1","")).get("analysisJobsEnabled"));
            var setups = call(api,"GET","/api/v1/analysis/setups",""); assertEquals(200,setups.status()); assertEquals(1,GraphJson.array(data(setups).get("items")).size());
            var created = call(api,"POST","/api/v1/analysis/jobs","{\"setup\":\"local\",\"idempotencyKey\":\"once\"}"); assertEquals(202,created.status());
            var id = GraphJson.string(data(created).get("id")); AnalysisJobsTest.finished(jobs,id);
            var view = call(api,"GET","/api/v1/analysis/jobs/"+id,""); assertEquals("SUCCEEDED",data(view).get("state"));
            assertEquals(f.graph().identity().value(),GraphJson.object(data(view).get("result")).get("graph"));
            var retry = call(api,"POST","/api/v1/analysis/jobs","{\"setup\":\"local\",\"idempotencyKey\":\"once\"}"); assertEquals(200,retry.status()); assertEquals(id,data(retry).get("id"));
            assertEquals(409,call(api,"POST","/api/v1/analysis/jobs","{\"setup\":\"local\",\"idempotencyKey\":\"once\",\"force\":true}").status());
            ArchitectureQueryApiTest.parity(api,f.request(),"summary",Map.of(),new com.evolution.analysis.query.ArchitectureQueryService(f.graph()).summary(f.request()));
            assertEquals(200,call(api,"POST","/api/v1/analysis/jobs/"+id+"/gaps","{}").status());
            var coverage = call(api,"GET","/api/v1/analysis/jobs/"+id+"/coverage", "");
            assertEquals(200,coverage.status());
            assertEquals("COMPLETE",data(coverage).get("completion"));
            assertEquals(0,((Number)data(coverage).get("totalGaps")).intValue());
            assertEquals(List.of(),data(coverage).get("topReasons"));
            assertEquals(200,call(api,"POST","/api/v1/analysis/jobs/"+id+"/cancel","{}").status());
            assertEquals("SUCCEEDED",jobs.get(UUID.fromString(id)).state().name());
            assertThrows(IllegalArgumentException.class,() -> new ArchitectureQueryApi(new InMemoryGraphStore(),reports,ApiLimits.defaults(),jobs));
        }
    }
    @Test void strictInputsRejectPathsDuplicatesMalformedEncodingUnknownFieldsAndForeignIds() throws Exception {
        var store = new InMemoryGraphStore(); var reports = new PublishedReports();
        try (var jobs = new AnalysisJobs(List.of(AnalysisJobsTest.registration("local",control -> AnalysisJobsTest.output(new QueryFixture().graph()))),store,reports,AnalysisJobsTest.limits())) {
            var api = new ArchitectureQueryApi(store,reports,ApiLimits.defaults(),jobs); String route = "/api/v1/analysis/jobs";
            for (String input : List.of("{}","[]","null","{\"setup\":\"local\"}","{\"setup\":\"local\",\"setup\":\"local\",\"idempotencyKey\":\"x\"}",
                    "{\"setup\":\"local\",\"idempotencyKey\":\"x\",\"source\":\"C:/private\"}","{\"setup\":\"../escape\",\"idempotencyKey\":\"x\"}",
                    "{\"setup\":\"local\",\"idempotencyKey\":true}",
                    "{\"setup\":\"local\",\"idempotencyKey\":\"x\",\"force\":\"true\"}",
                    "{\"setup\":\"local\",\"idempotencyKey\":\"x\",\"force\":null}")) assertEquals(400,call(api,"POST",route,input).status());
            assertEquals(400,api.handle("POST",route,"application/json",new byte[]{(byte)0xc3,0x28},() -> false).status());
            assertEquals(415,api.handle("POST",route,"text/plain",new byte[0],() -> false).status());
            assertEquals(413,api.handle("POST",route,"application/json",new byte[65_537],() -> false).status());
            assertEquals(404,call(api,"GET",route+"/"+UUID.randomUUID(),"").status());
            assertEquals(400,call(api,"GET",route+"/1-1-1-1-1","").status());
            assertEquals(404,call(api,"POST",route,"{\"setup\":\"unknown\",\"idempotencyKey\":\"x\"}").status());
            assertEquals(405,call(api,"DELETE",route,"").status()); assertEquals(400,call(api,"GET",route,"{}").status());
            assertEquals(400,call(api,"POST",route+"/history","{\"size\":1.5}").status());
            assertEquals(400,call(api,"POST",route+"/history","{\"size\":51}").status());
            assertEquals(409,api.handle("POST",route,"application/json","{}".getBytes(StandardCharsets.UTF_8),() -> true).status());
            assertEquals(0,jobs.history(20,Optional.empty()).total());
        }
    }
    @Test void actualHttpPreservesOriginControlsCancellationAndMethodHeaders() throws Exception {
        var entered = new CountDownLatch(1); var store = new InMemoryGraphStore(); var reports = new PublishedReports();
        try (var jobs = new AnalysisJobs(List.of(AnalysisJobsTest.registration("local",control -> { entered.countDown(); new CountDownLatch(1).await(); return AnalysisJobsTest.output(new QueryFixture().graph()); })),store,reports,AnalysisJobsTest.limits());
             var server = new ArchitectureHttpServer(new ArchitectureQueryApi(store,reports,ApiLimits.defaults(),jobs),0)) {
            server.start(); var client = HttpClient.newHttpClient(); var route = server.uri().resolve("/api/v1/analysis/jobs");
            var denied = client.send(HttpRequest.newBuilder(route).header("Origin","https://foreign.test").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"setup\":\"local\",\"idempotencyKey\":\"x\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(403,denied.statusCode()); assertEquals(0,jobs.history(20,Optional.empty()).total());
            var created = client.send(HttpRequest.newBuilder(route).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"setup\":\"local\",\"idempotencyKey\":\"once\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(202,created.statusCode()); AnalysisJobsTest.await(entered);
            var id = GraphJson.string(GraphJson.object(GraphJson.object(GraphJson.read(created.body(),GraphJson.Limits.defaults())).get("data")).get("id"));
            var wrongMethod = client.send(HttpRequest.newBuilder(route).DELETE().build(),HttpResponse.BodyHandlers.ofString()); assertEquals(405,wrongMethod.statusCode()); assertEquals("GET, POST",wrongMethod.headers().firstValue("Allow").orElseThrow());
            var cancel = server.uri().resolve("/api/v1/analysis/jobs/"+id+"/cancel");
            var wrongCancel = client.send(HttpRequest.newBuilder(cancel).GET().build(),HttpResponse.BodyHandlers.ofString()); assertEquals(405,wrongCancel.statusCode()); assertEquals("POST",wrongCancel.headers().firstValue("Allow").orElseThrow());
            var cancelled = client.send(HttpRequest.newBuilder(cancel).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            assertTrue(Set.of(200,202).contains(cancelled.statusCode())); assertEquals(AnalysisJobs.State.CANCELLED,AnalysisJobsTest.finished(jobs,id).state());
            assertEquals("no-store",cancelled.headers().firstValue("Cache-Control").orElseThrow());
        }
    }
}
