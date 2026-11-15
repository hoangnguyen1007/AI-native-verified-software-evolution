package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evolution.SnapshotComparison.*;
import com.evolution.analysis.filesystem.PartitionedGraphStore;
import com.evolution.analysis.graph.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EvolutionApiTest {
    @TempDir Path directory;

    @Test void reopensExactResultAcrossRestartAndRejectsForeignCursor() throws Exception {
        var fixture = new QueryFixture().node("A");
        var graph = fixture.graph();
        var graphs = new PartitionedGraphStore(directory.resolve("graphs"));
        graphs.save(graph, () -> false);
        var root = directory.resolve("evolution");
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/http.git");
        var missing = ContentDigest.sha256Utf8("missing");
        var request = new EvolutionRequest(EvolutionRequest.VERSION, new Request(repo,graph.identity(),missing,Mode.STRICT,
                new Selection(Set.of("MAIN"),Set.of("TYPE"),Set.of("graph.depends-on"))),List.of(),List.of(),Optional.empty());
        ContentDigest result;
        String cursor;
        try (var jobs = new EvolutionJobs(root,graphs,new PublishedReports())) {
            var queries = new EvolutionQueries(jobs.results(),graphs);
            var api = ArchitectureQueryApi.withEvolution(new ArchitectureQueryApi(new InMemoryGraphStore()),jobs,queries);
            var submitted = route(api,"POST","jobs",Map.of("request",request,"idempotencyKey","same"));
            assertEquals(202,submitted.status());
            var job = new ContentDigest(GraphJson.string(data(submitted).get("job")));
            var finished = await(jobs,job);
            assertEquals("SUCCEEDED",finished.state(),finished.reason());
            result = finished.result().orElseThrow();
            var pair = queries.summary(result,()->false);
            assertNotEquals(Status.COMPLETE,pair.status());
            assertEquals(List.of("AVAILABLE","MISSING"),pair.inputs().stream().map(row->row.get("status")).toList());
            var page = route(api,"POST","results/"+result.value()+"/query",Map.of("section","INPUTS","size",1));
            cursor = GraphJson.string(data(page).get("continuation"));
            assertEquals(200,route(api,"POST","jobs",Map.of("request",request,"idempotencyKey","same")).status());
            var reversed = new EvolutionRequest(EvolutionRequest.VERSION,new Request(repo,missing,graph.identity(),
                    Mode.STRICT,request.comparison().selection()),List.of(),List.of(),Optional.empty());
            assertEquals(409,route(api,"POST","jobs",Map.of("request",reversed,"idempotencyKey","same")).status());
            assertEquals(400,route(api,"POST","jobs",Map.of("request",request,"path","unregistered")).status());
            assertEquals(404,route(api,"POST","results/"+result.value()+"/graph",
                    Map.of("side","RIGHT","context",fixture.context)).status());
        }
        try (var jobs = new EvolutionJobs(root,graphs,new PublishedReports())) {
            var queries = new EvolutionQueries(jobs.results(),graphs);
            var api = ArchitectureQueryApi.withEvolution(new ArchitectureQueryApi(new InMemoryGraphStore()),jobs,queries);
            var query = Map.of("section","INPUTS","size",1,"cursor",cursor);
            var body = data(route(api,"POST","results/"+result.value()+"/query",query));
            assertEquals("RIGHT",GraphJson.object(GraphJson.array(body.get("items")).getFirst()).get("side"));
            var exported = data(route(api,"POST","results/"+result.value()+"/export",query));
            assertEquals(body,exported.get("page"));
            assertEquals(400,route(api,"POST","results/"+result.value()+"/query",
                    Map.of("section","CONTEXTS","size",1,"cursor",cursor)).status());
            assertEquals(400,route(api,"POST","results/"+result.value()+"/query",
                    Map.of("section","INPUTS","size",2,"cursor",cursor)).status());
            assertEquals(400,route(api,"POST","results/"+result.value()+"/query",
                    Map.of("section","INPUTS","size",1,"cursor",cursor,"filter",Map.of("side","RIGHT"))).status());
            var pinned = queries.summary(result,()->false);
            assertEquals(result,pinned.result());
            assertEquals(1,GraphJson.array(data(route(api,"POST","results/"+result.value()+"/graph",
                    Map.of("side","LEFT","context",fixture.context))).get("items")).size());
        }
    }

    @Test void redactionPreservesProofReferencesAndWithholdsCapturedLiterals() {
        var digest = ContentDigest.sha256Utf8("proof").value();
        var row = EvolutionDisclosure.row(Map.of("kind","REGION_INTRODUCED","proof",digest,"before",Map.of(
                "assignment",Map.of("property","database.password","exact","fixture-secret-xyz"),
                "metadata",Map.of("path","C:/private/workspace","havingValue","fixture-secret-xyz",
                        "value",Map.of("kind","EXACT","value","fixture-secret-xyz")),
                "evidence",List.of(digest))));
        var value = CanonicalJson.write(row);
        assertTrue(value.contains(digest));
        assertTrue(value.contains("REGION_INTRODUCED"));
        assertTrue(value.contains("operandDigest"));
        assertFalse(value.contains("fixture-secret-xyz"));
        assertFalse(value.contains("private/workspace"));
        assertEquals("{\"value\":{\"amount\":2,\"unit\":\"edges\"}}",
                CanonicalJson.write(EvolutionDisclosure.row(Map.of("value",Map.of("amount",2,"unit","edges")))));
    }

    @Test void historyRegistrationBindsProofAndCapturedProvenance() throws Exception {
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:fixture:history");
        var content=ContentDigest.sha256Utf8("parent provenance");
        var snapshot=com.evolution.analysis.contract.identity.SnapshotIdentity.from(repo,content);
        var from=ContentDigest.sha256Utf8("from");
        var to=ContentDigest.sha256Utf8("to");
        var proof=new HistoryProof(HistoryBasis.AUTHENTICATED_PARENT,repo,from,to,snapshot,snapshot,List.of(from),true,content);
        var registry=new EvolutionHistoryProofs(directory).reader();
        assertTrue(registry.read(proof.identity(),()->false).isEmpty());
        var folder=Files.createDirectories(directory.resolve("history"));
        var path=folder.resolve(proof.identity().value().substring(7)+".json");
        var bytes=Base64.getEncoder().encodeToString("parent provenance".getBytes(StandardCharsets.UTF_8));
        var schema="architecture.evolution.history-registration:v1";
        Files.writeString(path,CanonicalJson.write(new EvolutionHistoryProofs.Registration(schema,proof,bytes)));
        assertEquals(Optional.of(proof),registry.read(proof.identity(),()->false));
        Files.writeString(path,CanonicalJson.write(new EvolutionHistoryProofs.Registration(schema,proof,"eA==")));
        assertEquals(com.evolution.analysis.evolution.EvolutionSnapshot.Failure.CORRUPT,
                assertThrows(com.evolution.analysis.evolution.EvolutionSnapshot.ReadFailure.class,
                        ()->registry.read(proof.identity(),()->false)).reason());
    }

    @Test void disclosureRetainsOnlyContentAddressedHistoryProofLinks() {
        var interval = ContentDigest.sha256Utf8("interval").value();
        var absence = ContentDigest.sha256Utf8("absence").value();
        var presence = ContentDigest.sha256Utf8("presence").value();
        Map<String,Object> proofLinks = Map.of("intervalProof", interval, "previousAbsenceProof", absence,
                "priorPresenceProof", presence);
        assertEquals(proofLinks, EvolutionDisclosure.row(proofLinks));
        var invalid = EvolutionDisclosure.row(Map.of("intervalProof", "private-value"));
        assertFalse(CanonicalJson.write(invalid).contains("private-value"));
    }

    @Test void declarationDisclosureKeepsFileEvidenceWithoutYamlKeysOrValueHashes() {
        var source = ContentDigest.sha256Utf8("captured source").value();
        var value = ContentDigest.sha256Utf8("8084").value();
        var row = EvolutionDisclosure.row(Map.of("kind", "DECLARATION_REMOVED", "family", "spring.declared-yaml",
                "identity", ContentDigest.sha256Utf8("first-private-coordinate").value(),
                "path", "src/main/resources/application.yml", "configurationKey", List.of("private-key"),
                "left", Map.of("evidence", List.of(source), "valueDigest", value)));
        assertEquals("src/main/resources/application.yml", row.get("path"));
        assertEquals(List.of(source), GraphJson.object(row.get("left")).get("evidence"));
        assertFalse(CanonicalJson.write(row).contains("private-key"));
        assertFalse(CanonicalJson.write(row).contains(value));
        var different = EvolutionDisclosure.row(Map.of("kind", "DECLARATION_REMOVED", "family", "spring.declared-yaml",
                "identity", ContentDigest.sha256Utf8("second-private-coordinate").value(),
                "path", "src/main/resources/application.yml", "configurationKey", List.of("another-key"),
                "left", Map.of("evidence", List.of(source), "valueDigest", ContentDigest.sha256Utf8("8085").value())));
        assertEquals(row, different);
    }

    @Test void cancellationIsTerminalAndInterruptedJobCanResume() throws Exception {
        var fixture = new QueryFixture().node("A");
        var graph = fixture.graph();
        var graphs = new PartitionedGraphStore(directory.resolve("graphs"));
        graphs.save(graph,()->false);
        var entered = new CountDownLatch(1);
        var state = new java.util.concurrent.atomic.AtomicBoolean(true);
        ReportReader blocked = (g,ref,stop) -> {
            entered.countDown();
            while (state.get()) {
                com.evolution.analysis.evolution.EvolutionSnapshot.check(stop);
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            }
            return ReportStatus.MISSING;
        };
        var root = directory.resolve("evolution");
        var request = new EvolutionRequest(EvolutionRequest.VERSION,new Request(
                RepositoryIdentity.fromCanonicalCoordinate("https://example.test/http.git"),graph.identity(),graph.identity(),Mode.STRICT,
                new Selection(Set.of("MAIN"),Set.of("TYPE"),Set.of())),List.of(new ReportRef(Side.LEFT,
                ContentDigest.sha256Utf8("report"),fixture.context)),List.of(),Optional.empty());
        ContentDigest pending;
        try (var jobs = new EvolutionJobs(root,graphs,blocked,(r,s,o,c)->ReportStatus.UNAVAILABLE,(d,c)->Optional.empty())) {
            pending = jobs.submit(request,Optional.of("restart")).job().job();
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals("SCOPE_CHECKPOINT",jobs.get(pending).orElseThrow().reason());
            var cancelled = jobs.submit(request,Optional.of("cancel")).job().job();
            assertEquals("CANCELLED",jobs.cancel(cancelled).orElseThrow().state());
            assertTrue(jobs.get(cancelled).orElseThrow().result().isEmpty());
        }
        state.set(false);
        try (var jobs = new EvolutionJobs(root,graphs,blocked,(r,s,o,c)->ReportStatus.UNAVAILABLE,(d,c)->Optional.empty())) {
            assertTrue(jobs.get(pending).orElseThrow().result().isEmpty());
            jobs.resume(pending).orElseThrow();
            var completed = await(jobs,pending);
            assertTrue(completed.resumed());
            assertEquals("SUCCEEDED",completed.state(),completed.reason());
            assertTrue(completed.result().isPresent());
            var uninterrupted=await(jobs,jobs.submit(request,Optional.of("uninterrupted")).job().job());
            assertEquals(uninterrupted.result(),completed.result());
        }
    }

    @Test void resultReuseRequiresTheSameVerifiedRealizedInputs() throws Exception {
        var repository = RepositoryIdentity.fromCanonicalCoordinate("urn:fixture:evolution-reuse");
        var snapshot = com.evolution.analysis.contract.source.RepositorySnapshot.create(repository,
                Optional.of("a".repeat(40)),false,List.of(),List.of());
        var evidence = GraphEvidence.capture("repository.snapshot",snapshot);
        var context = new KnowledgeGraph.Context(snapshot.identity().value(),snapshot.identity(),Optional.empty(),evidence.identity());
        var left = new KnowledgeGraph(snapshot.identity(),List.of(context),List.of(),List.of(),List.of(evidence));
        var targetSnapshot = com.evolution.analysis.contract.source.RepositorySnapshot.create(repository,
                Optional.of("b".repeat(40)),false,List.of(),List.of());
        var targetEvidence = GraphEvidence.capture("repository.snapshot",targetSnapshot);
        var targetContext = new KnowledgeGraph.Context(targetSnapshot.identity().value(),targetSnapshot.identity(),Optional.empty(),targetEvidence.identity());
        var right = new KnowledgeGraph(targetSnapshot.identity(),List.of(targetContext),List.of(),List.of(),List.of(targetEvidence));
        var graphs = new PartitionedGraphStore(directory.resolve("graphs"));
        graphs.save(left,()->false);
        var request = new EvolutionRequest(EvolutionRequest.VERSION,new Request(repository,left.identity(),right.identity(),
                Mode.STRICT,new Selection(Set.of(),Set.of(),Set.of(),false,true)),List.of(),List.of(),Optional.empty());
        try (var jobs = new EvolutionJobs(directory.resolve("evolution"),graphs,new PublishedReports())) {
            var first = await(jobs,jobs.submit(request,Optional.empty()).job().job());
            assertEquals("SUCCEEDED",first.state(),first.reason());
            graphs.save(right,()->false);
            var restored = await(jobs,jobs.submit(request,Optional.empty()).job().job());
            assertEquals("SUCCEEDED",restored.state(),restored.reason());
            assertNotEquals(first.result(),restored.result());
            var reused = await(jobs,jobs.submit(request,Optional.empty()).job().job());
            assertEquals("VERIFIED_RESULT_REUSED",reused.reason());
            assertEquals(restored.result(),reused.result());
        }
    }

    private static EvolutionJobs.View await(EvolutionJobs jobs, ContentDigest id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var view = jobs.get(id).orElseThrow();
            if (!Set.of("QUEUED","RUNNING").contains(view.state())) return view;
            Thread.sleep(20);
        }
        throw new AssertionError("comparison did not reach a terminal state");
    }

    private static ArchitectureQueryApi.Response route(ArchitectureQueryApi api, String method, String path, Object body) {
        return api.handle(method,"/api/v1/evolution/"+path,"application/json",
                body == null ? new byte[0] : CanonicalJson.write(body).getBytes(StandardCharsets.UTF_8),()->false);
    }

    private static Map<String,Object> data(ArchitectureQueryApi.Response response) {
        assertTrue(response.status() >= 200 && response.status() < 300,
                ()->new String(response.body(),StandardCharsets.UTF_8));
        var body = GraphJson.object(GraphJson.read(new String(response.body(),StandardCharsets.UTF_8),GraphJson.Limits.defaults()));
        assertEquals(CanonicalJson.sha256(body.get("data")).value(),body.get("resultIdentity"));
        return GraphJson.object(body.get("data"));
    }
}
