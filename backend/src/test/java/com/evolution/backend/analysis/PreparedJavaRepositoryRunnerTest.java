package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.javaevidence.*;
import com.evolution.analysis.readiness.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Authored orchestration controls; these graphs do not mint semantic capabilities. */
class PreparedJavaRepositoryRunnerTest {
    static final ContentDigest POLICY=ContentDigest.sha256Utf8("operator policy");
    @TempDir Path directory;
    static ReadinessPlan plan(){
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:evo:root-coverage-control");var hash=ContentDigest.sha256Utf8("empty inventory");
        var inventory=new InventoryManifest(InventoryManifest.SCHEMA,repo,hash,hash,0,0,0,0,Optional.of(new SnapshotIdentity("snapshot:"+hash.value())),Optional.of(hash));
        var contexts=List.of(ReadinessPlanner.context("a","root-a","main","baseline",List.of("a/src"),Map.of(),List.of(),inventory,true),
                ReadinessPlanner.context("b","root-b","integration","baseline",List.of("b/src"),Map.of(),List.of(),inventory,true));
        var requirements=new ArrayList<ReadinessPlan.Requirement>();
        for(String root:List.of("root-a","root-b"))for(var kind:List.of(ReadinessPlan.Kind.MODEL,ReadinessPlan.Kind.TOOLCHAIN,ReadinessPlan.Kind.DEPENDENCIES))
            requirements.add(ReadinessPlanner.requirement(kind,root,"ROOT_REQUIRED",List.of(),List.of()));
        for(var context:contexts)for(var kind:List.of(ReadinessPlan.Kind.PLATFORM,ReadinessPlan.Kind.GENERATION,ReadinessPlan.Kind.BUILD_VALIDATION))
            requirements.add(ReadinessPlanner.requirement(kind,context.id(),"CONTEXT_REQUIRED",List.of(),List.of()));
        return new ReadinessPlan(ReadinessPlan.SCHEMA,inventory,new BuildContextSelection(BuildContextSelection.SCHEMA,Map.of(),contexts.stream().map(ReadinessPlan.Context::id).toList(),contexts.stream().map(ReadinessPlan.Context::id).toList(),List.of(),List.of()),
                List.of(new ReadinessPlan.Root("root-a","a",ReadinessPlan.Family.PLAIN,List.of(),List.of()),new ReadinessPlan.Root("root-b","b",ReadinessPlan.Family.PLAIN,List.of(),List.of())),
                List.of(new ReadinessPlan.Module("a","root-a","a","a",List.of("java"),List.of(),List.of()),new ReadinessPlan.Module("b","root-b","b","b",List.of("java"),List.of(),List.of())),contexts,requirements,List.of(),new ReadinessPlan.OwnershipSummary(0,Map.of(),new ContentHandle(ContentDigest.sha256Utf8(""),0)));
    }
    static KnowledgeGraph child(ReadinessPlan plan,ReadinessPlan.Context context,boolean foreignModule)throws Exception{
        var owner=plan.modules().stream().filter(m->m.id().equals(context.module())).findFirst().orElseThrow();
        var scope=new TreeMap<>(JavaEvidenceScopes.scope(plan,context,ModuleDescriptor.create(plan.inventory().repository(),owner.directory(),owner.id())));
        if(foreignModule)scope.put("module","module:"+ContentDigest.sha256Utf8("foreign owner").value());
        var snapshot=new SnapshotIdentity("snapshot:"+ContentDigest.sha256Utf8(context.id()+" derived").value());
        var evidence=GraphEvidence.capture("java.evidence.view",Map.of("scope",scope,"derivedSnapshot",snapshot,"policy",POLICY));
        String partition=KnowledgeGraph.derivedIdentity("authored-context",List.of(context.id()));
        return new KnowledgeGraph(snapshot,List.of(new KnowledgeGraph.Context(partition,snapshot,Optional.empty(),evidence.identity())),
                List.of(new KnowledgeGraph.Node(KnowledgeGraph.derivedIdentity("authored-type",List.of(context.id())),partition,"JAVA_TYPE",List.of(evidence.identity()))),List.of(),List.of(evidence));
    }
    static AnalysisJobs.Outcome outcome(KnowledgeGraph graph){return new AnalysisJobs.Outcome(AnalysisJobs.Completion.COMPLETE,Optional.of(graph),List.of(),List.of(),Optional.empty(),Optional.empty());}
    static PreparedJavaRepositoryRunner runner(ReadinessPlan plan,Map<String,PreparedJavaRepositoryRunner.ContextTask> tasks,GraphStore graphs){
        return new PreparedJavaRepositoryRunner(plan,POLICY,()->POLICY,List.of(),tasks,graphs);
    }
    @Test void changedContextIsRecomputedWhileTheUnchangedSiblingKeepsItsExactGraphAndBothRevisions()throws Exception{
        var plan=plan();var graphs=new InMemoryGraphStore();var tasks=new TreeMap<String,PreparedJavaRepositoryRunner.ContextTask>();var calls=new AtomicInteger();
        var first=plan.contexts().getFirst();var second=plan.contexts().getLast();var stable=child(plan,first,false);var old=child(plan,second,false);
        tasks.put(first.id(),cancelled->{calls.incrementAndGet();return outcome(stable);});tasks.put(second.id(),cancelled->{calls.incrementAndGet();return outcome(old);});
        var before=runner(plan,tasks,graphs).runCaptured(()->false);assertEquals(2,calls.get());
        var changedEvidence=GraphEvidence.capture("authored.changed-captured-input",Map.of("content",ContentDigest.sha256Utf8("changed generated input")));
        var evidence=new ArrayList<>(old.evidence());evidence.add(changedEvidence);var changed=new KnowledgeGraph(old.snapshot(),old.contexts(),old.nodes(),old.edges(),evidence);
        tasks.put(second.id(),cancelled->{calls.incrementAndGet();return outcome(changed);});var after=runner(plan,tasks,graphs).runCaptured(()->false);
        assertEquals(4,calls.get(),"Conservative recomputation is used rather than an unproved cache hit");assertNotEquals(before.graph().identity(),after.graph().identity());
        assertEquals(stable.identity(),graphs.load(stable.identity()).orElseThrow().identity());assertEquals(old.identity(),graphs.load(old.identity()).orElseThrow().identity());assertEquals(changed.identity(),graphs.load(changed.identity()).orElseThrow().identity());
        assertEquals(stable.identity(),after.contexts().get(first.id()).graph().orElseThrow());assertEquals(changed.identity(),after.contexts().get(second.id()).graph().orElseThrow());
    }
    @Test void separateRootAndCustomSetSnapshotsRemainIndependentAndTheFullCensusStaysOpen()throws Exception{
        var plan=plan();var graphs=new InMemoryGraphStore();var tasks=new TreeMap<String,PreparedJavaRepositoryRunner.ContextTask>();
        var captured=new ArrayList<KnowledgeGraph>();
        for(var context:plan.contexts()){var graph=child(plan,context,false);captured.add(graph);tasks.put(context.id(),cancelled->outcome(graph));}
        var result=runner(plan,tasks,graphs).runCaptured(()->false);
        assertEquals(2,result.contexts().size());assertTrue(result.contexts().values().stream().allMatch(r->r.state().equals("COMPLETE")));
        assertEquals(12,result.readiness().active());assertFalse(result.complete(),"Child completion cannot certify the pending root readiness requirements");
        assertEquals(plan.inventory().compatibleV1Snapshot().orElseThrow(),result.graph().snapshot());
        assertEquals(2,captured.stream().map(KnowledgeGraph::snapshot).distinct().count());
        for(var child:captured)assertTrue(graphs.available(child.identity()));
        assertTrue(result.graph().nodes().stream().noneMatch(n->n.kind().equals("JAVA_TYPE")),"The root indexes graphs without flattening their symbols/snapshots");
        assertTrue(result.contexts().values().stream().anyMatch(r->r.sourceSet().equals("integration")));
    }
    @Test void aForeignOwnerCannotBorrowAnOtherwiseMatchingContextProof()throws Exception{
        var plan=plan();var context=plan.contexts().getFirst();var graph=child(plan,context,true);
        var result=runner(plan,Map.of(context.id(),cancelled->outcome(graph)),new InMemoryGraphStore()).runCaptured(()->false);
        assertEquals("FAILED",result.contexts().get(context.id()).state());
        assertEquals("PENDING",result.contexts().get(plan.contexts().getLast().id()).state());
    }
    @Test void aFailedContextDoesNotDiscardTheIndependentSibling()throws Exception{
        var plan=plan();var first=plan.contexts().getFirst();var second=plan.contexts().getLast();var graph=child(plan,second,false);
        var result=runner(plan,Map.of(first.id(),cancelled->{throw new java.io.IOException("private failure details");},second.id(),cancelled->outcome(graph)),new InMemoryGraphStore()).runCaptured(()->false);
        assertEquals("FAILED",result.contexts().get(first.id()).state());assertEquals("COMPLETE",result.contexts().get(second.id()).state());
        assertFalse(new GraphSnapshotCodec().encode(result.graph()).contains("private failure details"));
    }
    @Test void failedOutcomeWithRetainedGraphRemainsFailedAndKeepsItsHealthySibling()throws Exception{
        var plan=plan();var first=plan.contexts().getFirst();var second=plan.contexts().getLast();
        var failed=child(plan,first,false);var healthy=child(plan,second,false);var graphs=new InMemoryGraphStore();
        var result=runner(plan,Map.of(first.id(),cancelled->new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,
                Optional.of(failed),List.of(),List.of(),Optional.empty(),Optional.empty()),
                second.id(),cancelled->outcome(healthy)),graphs).runCaptured(()->false);
        assertEquals("FAILED",result.contexts().get(first.id()).state());
        assertEquals("CONTEXT_ANALYSIS_FAILED",result.contexts().get(first.id()).reason());
        assertEquals("COMPLETE",result.contexts().get(second.id()).state());
        assertEquals(failed.identity(),graphs.load(failed.identity()).orElseThrow().identity());
        assertEquals(healthy.identity(),graphs.load(healthy.identity()).orElseThrow().identity());
        var summary=result.graph().evidence().stream().filter(e->e.family().equals("java.evidence.repository-coverage")).findFirst().orElseThrow();
        var counts=GraphJson.object(GraphJson.object(GraphJson.read(summary.canonicalJson(),GraphJson.Limits.defaults())).get("counts"));
        assertEquals(new java.math.BigDecimal(1),counts.get("FAILED"));
        assertEquals(new java.math.BigDecimal(1),counts.get("COMPLETE"));
        assertEquals(counts.get("total"),List.of("PENDING","COMPLETE","PARTIAL","FAILED","UNSELECTED").stream()
                .map(k->(java.math.BigDecimal)counts.get(k)).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add));
        assertFalse(result.complete());
    }
    @Test void cancellationRetainsTheExactPendingFrontierAndReplayUsesCapturedTasksOnly()throws Exception{
        var plan=plan();var content=new StreamingContentStore(directory.resolve("cas"));var store=new JavaEvidencePublicationStore(directory.resolve("publication"),content);var key=ContentDigest.sha256Utf8("root registration");
        var calls=new AtomicInteger();var tasks=new TreeMap<String,PreparedJavaRepositoryRunner.ContextTask>();
        for(var context:plan.contexts()){var graph=child(plan,context,false);tasks.put(context.id(),cancelled->{calls.incrementAndGet();return outcome(graph);});}
        var runner=runner(plan,tasks,new InMemoryGraphStore()).withCheckpoint(store,content,key);
        assertThrows(java.util.concurrent.CancellationException.class,()->runner.runCaptured(()->calls.get()>=2));
        var progress=store.load(key).orElseThrow();String json;try(var in=content.open(progress.artifacts().get("coverage"))){json=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        var rows=GraphJson.object(GraphJson.read(json,GraphJson.Limits.defaults())).values().stream().map(GraphJson::object).toList();
        assertEquals(1,rows.stream().filter(r->r.get("state").equals("PENDING")).count());assertEquals(1,rows.stream().filter(r->r.get("state").equals("COMPLETE")).count());
        var result=runner.runCaptured(()->false);var committed=store.load(key).orElseThrow();
        var again=runner(plan,tasks,new InMemoryGraphStore()).withCheckpoint(new JavaEvidencePublicationStore(directory.resolve("publication"),content),content,key).runCaptured(()->false);
        assertEquals(result.graph().identity(),again.graph().identity());assertEquals(committed.artifacts(),store.load(key).orElseThrow().artifacts());
    }
    @Test void publicationStorageFailureRetainsBothSoundChildrenAndThePreviousRevision()throws Exception{
        var plan=plan();var content=new StreamingContentStore(directory.resolve("cas"));var store=new JavaEvidencePublicationStore(directory.resolve("publication"),content);var key=ContentDigest.sha256Utf8("root registration");
        var oldBytes=content.put(new java.io.ByteArrayInputStream("previous revision".getBytes(java.nio.charset.StandardCharsets.UTF_8)),()->false);
        var previous=store.commit(key,Optional.empty(),Map.of("graph",oldBytes));
        RepositoryContentStore exhausted=new RepositoryContentStore(){
            public ContentHandle put(java.io.InputStream input,java.util.function.BooleanSupplier cancelled)throws java.io.IOException{throw new java.io.IOException("private disk-full details");}
            public java.io.InputStream open(ContentHandle handle)throws java.io.IOException{return content.open(handle);}
            public java.io.InputStream range(ContentHandle handle,long offset,long length)throws java.io.IOException{return content.range(handle,offset,length);}
            public boolean verify(ContentHandle handle)throws java.io.IOException{return content.verify(handle);}
        };
        var tasks=new TreeMap<String,PreparedJavaRepositoryRunner.ContextTask>();var calls=new AtomicInteger();
        for(var context:plan.contexts()){var graph=child(plan,context,false);tasks.put(context.id(),cancelled->{calls.incrementAndGet();return outcome(graph);});}
        var result=runner(plan,tasks,new InMemoryGraphStore()).withCheckpoint(store,exhausted,key).runCaptured(()->false);
        assertEquals(2,calls.get());assertTrue(result.contexts().values().stream().allMatch(r->r.graph().isPresent()));
        assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("STORAGE_UNAVAILABLE")));
        assertEquals(previous,store.load(key).orElseThrow());assertFalse(new GraphSnapshotCodec().encode(result.graph()).contains("private disk-full details"));
    }
    @Test void rootCauseDeltaCountsCurrentHistoryAndGraphlessOutcomeGapsOnTheSameSelectedScope()throws Exception{
        var plan=plan();var tasks=new TreeMap<String,PreparedJavaRepositoryRunner.ContextTask>();int index=0;
        for(var context:plan.contexts()){
            var base=child(plan,context,false);boolean historical=index++==0;
            var gap=JavaEvidenceGaps.create(Map.of("baseSnapshot",base.snapshot().value()),JavaEvidenceGaps.Reason.GENERATOR_TOOL_UNPINNED,plan.identity(),POLICY);
            var evidence=GraphEvidence.capture("evidence.gap",gap);var nodes=new ArrayList<>(base.nodes());
            nodes.add(new KnowledgeGraph.Node(gap.gapIdentity().value(),base.contexts().getFirst().identity(),historical?"HISTORICAL_CAPABILITY_GAP":"CAPABILITY_GAP",List.of(evidence.identity())));
            var all=new ArrayList<>(base.evidence());all.add(evidence);var graph=new KnowledgeGraph(base.snapshot(),base.contexts(),nodes,List.of(),all);
            var unpublished=JavaEvidenceGaps.create(Map.of("baseSnapshot",base.snapshot().value()),JavaEvidenceGaps.Reason.STORAGE_UNAVAILABLE,plan.identity(),POLICY);
            tasks.put(context.id(),cancelled->new AnalysisJobs.Outcome(historical?AnalysisJobs.Completion.COMPLETE:AnalysisJobs.Completion.PARTIAL,Optional.of(graph),List.of(),historical?List.of():List.of(gap,unpublished),Optional.empty(),Optional.empty()));
        }
        var result=runner(plan,tasks,new InMemoryGraphStore()).runCaptured(()->false);
        var summary=result.graph().evidence().stream().filter(e->e.family().equals("java.evidence.repository-coverage")).findFirst().orElseThrow();
        var delta=GraphJson.array(GraphJson.object(GraphJson.read(summary.canonicalJson(),GraphJson.Limits.defaults())).get("rootCauseDelta")).stream().map(GraphJson::object).toList();
        var generation=delta.stream().filter(row->row.get("reasonCode").equals("GENERATOR_TOOL_UNPINNED")).findFirst().orElseThrow();
        assertEquals(new java.math.BigDecimal(2),generation.get("before"));assertEquals(new java.math.BigDecimal(1),generation.get("after"));
        assertTrue(delta.stream().anyMatch(row->row.get("reasonCode").equals("STORAGE_UNAVAILABLE")&&row.get("after").equals(new java.math.BigDecimal(1))),"Outcome-only gaps must not disappear from root reporting");
    }
    @Test void anOlderRunCannotAdoptAnotherWritersFenceAndOverwriteItsCommittedRevision()throws Exception{
        var plan=plan();var content=new StreamingContentStore(directory.resolve("cas"));var store=new JavaEvidencePublicationStore(directory.resolve("publication"),content);var key=ContentDigest.sha256Utf8("root registration");
        var once=new java.util.concurrent.atomic.AtomicBoolean();var winner=new java.util.concurrent.atomic.AtomicReference<JavaEvidencePublicationStore.Publication>();
        var tasks=new TreeMap<String,PreparedJavaRepositoryRunner.ContextTask>();
        for(var context:plan.contexts()){var graph=child(plan,context,false);tasks.put(context.id(),cancelled->{
            if(once.compareAndSet(false,true)){
                var prior=store.load(key).orElseThrow();var bytes=content.put(new java.io.ByteArrayInputStream("other fenced writer".getBytes(java.nio.charset.StandardCharsets.UTF_8)),()->false);
                winner.set(store.commit(key,Optional.of(prior.document().digest()),Map.of("graph",bytes)));
            }
            return outcome(graph);
        });}
        var result=runner(plan,tasks,new InMemoryGraphStore()).withCheckpoint(store,content,key).runCaptured(()->false);
        assertEquals(winner.get(),store.load(key).orElseThrow(),"A stale run must retain its original fence across all context checkpoints");
        assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("VALIDATION_REJECTED")));
    }
}
