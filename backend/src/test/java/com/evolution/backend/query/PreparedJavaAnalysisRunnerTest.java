package com.evolution.backend.query;

import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import com.evolution.analysis.javaevidence.*;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.analysis.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;

/** Real frozen native bytes under the public fixture authority. No target execution.
 * The fixture classes are explicit trusted platform test inputs, not production code. */
@Tag("native-campaign")
class PreparedJavaAnalysisRunnerTest {
    @org.junit.jupiter.api.io.TempDir Path publicationDirectory;
    @Test void registeredCapturedContextPublishesCurrentAndHistoricalViewsThroughJobsAndApi()throws Exception{
        {
            var type=Class.forName("com.evolution.analysis.javaparser.NativePipelineWiringFixture");
            var constructor=type.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
            var fixture=constructor.newInstance(Path.of(System.getProperty("native.fixture.root")));
            var execute=type.getDeclaredMethod("execute",String.class);execute.setAccessible(true);
            var result=execute.invoke(fixture,"protobuf-client");
            var prepared=(JavaEvidenceAssembler.Prepared)value(result,"prepared");
            var semantic=(JavaSemanticEvidence.Bundle)value(result,"semantic");
            var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER)
                    .truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();
            var publicationContent=new com.evolution.analysis.filesystem.StreamingContentStore(publicationDirectory.resolve("cas"));
            var publicationStore=new com.evolution.analysis.filesystem.JavaEvidencePublicationStore(publicationDirectory.resolve("publications"),publicationContent);
            var publication=new PreparedAnalysisPublication(publicationStore,publicationContent,com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("registered captured context"));
            var runner=new PreparedJavaAnalysisRunner(prepared,policy,Optional.of(semantic),Optional.empty()).withPublication(publication);
            var reopened=new PreparedAnalysisPublication(new com.evolution.analysis.filesystem.JavaEvidencePublicationStore(publicationDirectory.resolve("publications"),publicationContent),publicationContent,
                    com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("registered captured context"));
            var restarted=new PreparedJavaAnalysisRunner(prepared,policy,Optional.of(semantic),Optional.empty()).withPublication(reopened);
            var revoked=new PreparedJavaAnalysisRunner(prepared,policy,Optional.of(semantic),Optional.empty(),()->com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("changed authority registry"));
            var bytesOnly=new PreparedJavaAnalysisRunner(prepared,policy,Optional.empty(),Optional.empty());
            var graphs=new InMemoryGraphStore();var reports=new PublishedReports();
            var rootRunner=new PreparedJavaRepositoryRunner(prepared.readiness(),prepared.policy(),prepared::policy,List.of(),List.of(restarted),graphs)
                    .withCheckpoint(publicationStore,publicationContent,com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("registered native root"));
            var rootSetup=new AnalysisJobs.Setup("root-coverage",prepared.base().snapshot().repository(),21,false,Optional.empty(),Optional.empty());
            var changedPolicy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER)
                    .maxTypes(policy.maxTypes()+1).truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();
            var changedWork=new PreparedJavaAnalysisRunner(prepared,changedPolicy,Optional.of(semantic),Optional.empty()).withPublication(reopened);
            try(var jobs=new AnalysisJobs(List.of(runner.registration("captured-context"),restarted.registration("restarted-context"),revoked.registration("revoked-context"),bytesOnly.registration("no-semantic-census"),rootRunner.registration(rootSetup),changedWork.registration("changed-work-policy")),graphs,reports,new AnalysisJobs.Limits(1,1,8,180_000,1000))){
                var submitted=jobs.submit("captured-context","native-history");
                var view=finished(jobs,submitted.job().id());
                assertEquals(AnalysisJobs.State.PARTIAL,view.state(),()->view.error().toString());
                var graph=graphs.load(view.result().orElseThrow().graph().orElseThrow()).orElseThrow();
                assertTrue(publication.latest().isPresent(),"The real composed native runner must durably publish the captured evidence revision");
                var committed=publication.latest().orElseThrow();assertEquals(committed,reopened.latest().orElseThrow());
                assertEquals(Set.of("graph","input-footprint","readiness","semantic-view"),committed.artifacts().keySet());
                try(var input=publicationContent.open(committed.artifacts().get("graph"));var reader=new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)){
                    assertEquals(graph.identity(),new GraphSnapshotCodec().decode(reader,GraphJson.Limits.defaults()).identity());
                }
                assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.readiness-coverage")),
                        "The product must retain the complete readiness denominator alongside one analyzed context");
                assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.semantic-projection")),
                        "The real runner must expose the typed context semantic projection");
                assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.semantic-audit-binding")));
                var historical=graph.nodes().stream().filter(n->n.kind().equals("HISTORICAL_CAPABILITY_GAP")).map(KnowledgeGraph.Node::identity).collect(java.util.stream.Collectors.toSet());
                assertFalse(historical.isEmpty());
                var jobGaps=jobs.gaps(UUID.fromString(view.id()),50,Optional.empty()).items();
                assertTrue(jobGaps.stream().noneMatch(g->historical.contains(g.gapIdentity().value())));
                var current=graph.nodes().stream().filter(n->n.kind().equals("CAPABILITY_GAP")).map(KnowledgeGraph.Node::identity).collect(java.util.stream.Collectors.toSet());
                var union=new HashSet<>(current);jobGaps.forEach(g->union.add(g.gapIdentity().value()));
                assertEquals(union.size(),view.result().orElseThrow().gapCount());
                var restored=new GraphSnapshotCodec().decode(new GraphSnapshotCodec().encode(graph),GraphJson.Limits.defaults());
                var store=new InMemoryGraphStore();store.save(restored);
                var api=new ArchitectureQueryApi(store);
                var request=ArchitectureQuery.Request.dependencies(restored.identity(),restored.snapshot().value());
                byte[] body=com.evolution.analysis.contract.serialization.CanonicalJson.write(Map.of("request",request,"arguments",Map.of("view","HISTORY"),"page",ArchitectureQuery.Page.first())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var response=api.handle("POST","/api/v1/graphs/"+graph.identity().value()+"/queries/gaps","application/json",body,()->false);
                assertEquals(200,response.status());
                var json=GraphJson.object(GraphJson.read(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8),new GraphJson.Limits(4_000_000,128,1_000_000)));
                var data=GraphJson.object(json.get("data"));
                assertEquals(historical,GraphJson.array(data.get("items")).stream().map(GraphJson::object).map(row->GraphJson.string(row.get("identity"))).collect(java.util.stream.Collectors.toSet()));
                var rejected=finished(jobs,jobs.submit("revoked-context","changed-policy").job().id());
                assertEquals(AnalysisJobs.State.FAILED,rejected.state());
                assertTrue(jobs.gaps(UUID.fromString(rejected.id()),50,Optional.empty()).items().stream().anyMatch(g->g.reasonCode().equals("EVIDENCE_REVOKED")));
                var incomplete=finished(jobs,jobs.submit("no-semantic-census","without-occurrence-validation").job().id());
                assertEquals(AnalysisJobs.State.PARTIAL,incomplete.state());
                assertTrue(jobs.gaps(UUID.fromString(incomplete.id()),50,Optional.empty()).items().stream().anyMatch(g->g.reasonCode().equals("SEMANTIC_MAPPING_UNPROVEN")));
                var replayed=finished(jobs,jobs.submit("restarted-context","restart-frozen-inputs").job().id());
                assertEquals(AnalysisJobs.State.PARTIAL,replayed.state());
                assertEquals(graph.identity(),replayed.result().orElseThrow().graph().orElseThrow());
                assertEquals(committed.document(),reopened.latest().orElseThrow().document(),"A new job UUID cannot create a different revision for identical captured inputs");
                var rootView=finished(jobs,jobs.submit("root-coverage","all-selected-contexts").job().id());
                assertEquals(AnalysisJobs.State.PARTIAL,rootView.state());
                var rootGraph=graphs.load(rootView.result().orElseThrow().graph().orElseThrow()).orElseThrow();
                assertEquals(prepared.base().snapshot().identity(),rootGraph.snapshot());
                assertEquals(1,rootGraph.nodes().stream().filter(n->n.kind().equals("JAVA_CONTEXT_RESULT")).count());
                assertTrue(rootGraph.nodes().stream().noneMatch(n->n.kind().equals("JAVA_TYPE")),"The root indexes the exact child graph without flattening its snapshot");
                assertTrue(rootGraph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.repository-coverage")&&e.canonicalJson().contains(graph.identity().value())));
                assertEquals(graph.identity(),graphs.load(graph.identity()).orElseThrow().identity());
                var summary=rootGraph.evidence().stream().filter(e->e.family().equals("java.evidence.repository-coverage")).findFirst().orElseThrow();
                var counts=GraphJson.object(GraphJson.object(GraphJson.read(summary.canonicalJson(),GraphJson.Limits.defaults())).get("counts"));
                assertEquals(new java.math.BigDecimal(historical.size()),((java.math.BigDecimal)counts.get("rootCauseBefore")).subtract((java.math.BigDecimal)counts.get("rootCauseAfter")));
                var changed=finished(jobs,jobs.submit("changed-work-policy","new-bounded-policy").job().id());
                assertEquals(AnalysisJobs.State.PARTIAL,changed.state());
                var next=reopened.latest().orElseThrow();assertNotEquals(committed.artifacts().get("input-footprint"),next.artifacts().get("input-footprint"));
                assertEquals(Optional.of(committed.document().digest()),next.parent());
                try(var input=publicationContent.open(next.artifacts().get("input-footprint"))){
                    var footprint=GraphJson.object(GraphJson.read(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),GraphJson.Limits.defaults()));
                    assertEquals(new java.math.BigDecimal(changedPolicy.maxTypes()),GraphJson.object(footprint.get("work")).get("maxTypes"));
                }
            }
        }
    }
    private static Object value(Object result,String name)throws Exception{var method=result.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(result);}
    private static AnalysisJobs.View finished(AnalysisJobs jobs,String id)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
        while(System.nanoTime()<until){var result=jobs.get(UUID.fromString(id));if(AnalysisJobs.terminal(result.state()))return result;Thread.sleep(10);}
        throw new AssertionError("Captured-context job did not finish within the registered test budget");
    }
}
