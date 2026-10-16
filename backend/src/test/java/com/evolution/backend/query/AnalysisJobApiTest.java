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
            ArchitectureQueryApiTest.parity(api,f.request(),"summary",Map.of(),new com.evolution.analysis.query.ArchitectureQueryService(f.graph()).summary(f.request()));
            assertEquals(200,call(api,"POST","/api/v1/analysis/jobs/"+id+"/gaps","{}").status());
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
                    "{\"setup\":\"local\",\"idempotencyKey\":true}")) assertEquals(400,call(api,"POST",route,input).status());
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
