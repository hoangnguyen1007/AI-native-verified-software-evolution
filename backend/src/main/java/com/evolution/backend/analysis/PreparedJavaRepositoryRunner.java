package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.javaevidence.*;
import com.evolution.analysis.readiness.ReadinessPlan;
import java.util.*;
import java.util.function.*;

/** An additive root coverage index. Each context keeps its own immutable snapshot/graph.
 * It runs captured analysis only; it neither executes builds nor certifies semantic accuracy. */
public final class PreparedJavaRepositoryRunner implements AnalysisJobs.Runner {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("source.java-root-coverage","m8.4-v1");
    @FunctionalInterface interface ContextTask {AnalysisJobs.Outcome run(BooleanSupplier cancelled)throws Exception;}
    public record ContextRow(String root,String module,String sourceSet,String variant,String state,
            Optional<ContentDigest> graph,Optional<SnapshotIdentity> snapshot,long activeGaps,String reason) {}
    public record Result(KnowledgeGraph graph,JavaReadinessClosure.View readiness,Map<String,ContextRow> contexts,List<CapabilityGapRecord> gaps){
        public Result {contexts=Collections.unmodifiableMap(new TreeMap<>(contexts));gaps=gaps.stream().distinct().sorted().toList();}
        public boolean complete(){return readiness.readinessClosed()&&!contexts.isEmpty()&&gaps.isEmpty()&&contexts.values().stream().allMatch(r->r.state().equals("COMPLETE"));}
    }
    private final ReadinessPlan plan;private final ContentDigest policy;private final Supplier<ContentDigest> livePolicy;
    private final List<JavaEvidenceAuthority.Verified> proofs;private final SortedMap<String,ContextTask> tasks;private final GraphStore graphs;
    private final Optional<Checkpoint> checkpoint;
    private record Checkpoint(com.evolution.analysis.filesystem.JavaEvidencePublicationStore store,
            com.evolution.analysis.acquisition.streaming.RepositoryContentStore content,ContentDigest key){}
    private static final class PublicationFence {
        final ContentDigest components;boolean loaded,lost;Optional<ContentDigest> expected=Optional.empty();
        PublicationFence(ContentDigest components){this.components=components;}
    }

    public PreparedJavaRepositoryRunner(ReadinessPlan plan,ContentDigest policy,Supplier<ContentDigest> livePolicy,
            Collection<JavaEvidenceAuthority.Verified> proofs,Collection<PreparedJavaAnalysisRunner> runners,GraphStore graphs){
        this(plan,policy,livePolicy,proofs,captured(plan,policy,runners),graphs,Optional.empty());
    }
    PreparedJavaRepositoryRunner(ReadinessPlan plan,ContentDigest policy,Supplier<ContentDigest> livePolicy,
            Collection<JavaEvidenceAuthority.Verified> proofs,Map<String,ContextTask> tasks,GraphStore graphs){
        this(plan,policy,livePolicy,proofs,tasks,graphs,Optional.empty());
    }
    private PreparedJavaRepositoryRunner(ReadinessPlan plan,ContentDigest policy,Supplier<ContentDigest> livePolicy,
            Collection<JavaEvidenceAuthority.Verified> proofs,Map<String,ContextTask> tasks,GraphStore graphs,Optional<Checkpoint> checkpoint){
        this.plan=Objects.requireNonNull(plan);this.policy=Objects.requireNonNull(policy);this.livePolicy=Objects.requireNonNull(livePolicy);
        this.proofs=List.copyOf(proofs);this.tasks=Collections.unmodifiableSortedMap(new TreeMap<>(tasks));this.graphs=Objects.requireNonNull(graphs);this.checkpoint=checkpoint;
        if(plan.inventory().compatibleV1Snapshot().isEmpty()||!new HashSet<>(plan.selection().selected()).containsAll(tasks.keySet()))throw new IllegalArgumentException("JAVA_ROOT_CONTEXT_SCOPE");
    }
    public PreparedJavaRepositoryRunner withCheckpoint(com.evolution.analysis.filesystem.JavaEvidencePublicationStore store,
            com.evolution.analysis.acquisition.streaming.RepositoryContentStore content,ContentDigest key){
        return new PreparedJavaRepositoryRunner(plan,policy,livePolicy,proofs,tasks,graphs,Optional.of(new Checkpoint(store,content,key)));
    }
    private static Map<String,ContextTask> captured(ReadinessPlan plan,ContentDigest policy,Collection<PreparedJavaAnalysisRunner> runners){
        var tasks=new TreeMap<String,ContextTask>();
        for(var runner:runners){var prepared=runner.prepared();
            if(!plan.identity().equals(prepared.readiness().identity())||!policy.equals(prepared.policy()))throw new IllegalArgumentException("JAVA_ROOT_PREPARED_SCOPE");
            var context=plan.contexts().stream().filter(c->c.identity().value().equals(runner.scope().get("context"))).findFirst().orElseThrow();
            if(tasks.putIfAbsent(context.id(),runner::runCaptured)!=null)throw new IllegalArgumentException("JAVA_ROOT_DUPLICATE_CONTEXT");
        }
        return tasks;
    }
    public AnalysisJobs.Registration registration(AnalysisJobs.Setup setup){
        if(!setup.repository().equals(plan.inventory().repository()))throw new IllegalArgumentException("JAVA_ROOT_SETUP_REPOSITORY");
        return new AnalysisJobs.Registration(setup,this);
    }
    @Override public AnalysisJobs.Outcome run(AnalysisJobs.Control control)throws Exception{
        control.stage(AnalysisJobs.Stage.ACQUISITION);control.stage(AnalysisJobs.Stage.PLATFORM);control.stage(AnalysisJobs.Stage.ANALYSIS);
        Result result;
        try{result=runCaptured(control);}
        catch(java.io.IOException unavailable){
            var reason=unavailable.getMessage()!=null&&unavailable.getMessage().contains("COMPONENT")?JavaEvidenceGaps.Reason.VALIDATION_METHOD_UNAVAILABLE:JavaEvidenceGaps.Reason.EVIDENCE_REVOKED;
            var gap=JavaEvidenceGaps.create(Map.of("baseSnapshot",plan.inventory().compatibleV1Snapshot().orElseThrow().value()),reason,plan.identity(),policy);
            return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.of(gap),Optional.empty(),Optional.empty());
        }
        control.stage(AnalysisJobs.Stage.GRAPH);
        return new AnalysisJobs.Outcome(result.complete()?AnalysisJobs.Completion.COMPLETE:AnalysisJobs.Completion.PARTIAL,
                Optional.of(result.graph()),List.of(),result.gaps(),Optional.empty(),Optional.of(result.readiness().identity()));
    }
    public Result runCaptured(BooleanSupplier cancelled)throws Exception{
        var readiness=current();var outcomes=new TreeMap<String,AnalysisJobs.Outcome>();var failures=new TreeMap<String,String>();
        var components=CapturedAnalysisComponents.repository();var fence=new PublicationFence(components.identity());var result=publishProgress(index(readiness,outcomes,failures,components),cancelled,fence);
        // No total-context admission ceiling: one isolated semantic work item at a time.
        for(var entry:tasks.entrySet()){
            check(cancelled);current(readiness);currentComponents(components.identity());
            try{
                var outcome=entry.getValue().run(cancelled);check(cancelled);current(readiness);
                if(outcome.graph().isPresent()){
                    var graph=outcome.graph().orElseThrow();requireContext(entry.getKey(),graph);graphs.save(graph,cancelled);
                }
                outcomes.put(entry.getKey(),outcome);
            }catch(java.util.concurrent.CancellationException stopped){throw stopped;}
            catch(Exception unavailable){check(cancelled);current(readiness);failures.put(entry.getKey(),unavailable instanceof GraphStoreException?"CONTEXT_STORAGE_UNAVAILABLE":"CONTEXT_ANALYSIS_FAILED");}
            result=publishProgress(index(readiness,outcomes,failures,components),cancelled,fence);
        }
        check(cancelled);current(readiness);currentComponents(components.identity());return result;
    }
    private JavaReadinessClosure.View current()throws java.io.IOException{
        if(!policy.equals(livePolicy.get()))throw new java.io.IOException("JAVA_ROOT_POLICY_REVOKED");
        return new JavaReadinessClosure().reconcile(plan,policy,proofs);
    }
    private void current(JavaReadinessClosure.View expected)throws java.io.IOException{
        if(!expected.identity().equals(current().identity()))throw new java.io.IOException("JAVA_ROOT_READINESS_REVOKED");
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();}
    private static void currentComponents(ContentDigest expected)throws java.io.IOException{
        if(!expected.equals(CapturedAnalysisComponents.repository().identity()))throw new java.io.IOException("JAVA_ROOT_COMPONENTS_REVOKED");
    }
    private void requireContext(String id,KnowledgeGraph graph)throws java.io.IOException{
        var context=plan.contexts().stream().filter(c->c.id().equals(id)).findFirst().orElseThrow();
        var module=plan.modules().stream().filter(m->m.id().equals(context.module())).findFirst().orElseThrow();
        var expectedScope=JavaEvidenceScopes.scope(plan,context,com.evolution.analysis.contract.source.ModuleDescriptor.create(plan.inventory().repository(),module.directory(),module.id()));
        long matches=graph.evidence().stream().filter(e->e.family().equals("java.evidence.view")).map(e->GraphJson.object(GraphJson.read(e.canonicalJson(),GraphJson.Limits.defaults())))
                .filter(view->graph.snapshot().value().equals(view.get("derivedSnapshot"))&&policy.value().equals(view.get("policy"))&&expectedScope.equals(view.get("scope"))).count();
        if(matches!=1)throw new java.io.IOException("JAVA_ROOT_CHILD_SCOPE_MISMATCH");
    }
    private Result index(JavaReadinessClosure.View readiness,Map<String,AnalysisJobs.Outcome> outcomes,Map<String,String> failures,GraphEvidence components){
        var snapshot=plan.inventory().compatibleV1Snapshot().orElseThrow();var rows=new TreeMap<String,ContextRow>();var gaps=new TreeSet<>(readiness.gaps(snapshot));
        var delta=new TreeMap<String,long[]>();var causeLabels=new TreeMap<String,Map<String,Object>>();
        for(var gap:gaps)addCause(delta,causeLabels,gap.catalog(),gap.detectingProvider(),gap.reasonCode(),true);
        for(var context:plan.contexts()){
            var outcome=outcomes.get(context.id());boolean selected=plan.selection().selected().contains(context.id());
            var graph=outcome==null?Optional.<KnowledgeGraph>empty():outcome.graph();var active=new TreeSet<String>();
            if(outcome!=null)outcome.gaps().forEach(g->active.add(g.gapIdentity().value()));
            var seen=new HashSet<String>();
            graph.ifPresent(g->{
                g.nodes().stream().filter(n->Set.of("CAPABILITY_GAP","PROVIDER_CONFLICT").contains(n.kind())).forEach(n->active.add(n.identity()));
                for(var node:g.nodes())if(Set.of("CAPABILITY_GAP","HISTORICAL_CAPABILITY_GAP","PROVIDER_CONFLICT").contains(node.kind())&&seen.add(node.identity())){
                    var rawGap=node.evidence().stream().map(g::evidence).flatMap(Optional::stream).filter(e->e.family().equals("evidence.gap")).findFirst();
                    if(rawGap.isPresent()){
                        var raw=GraphJson.object(GraphJson.read(rawGap.orElseThrow().canonicalJson(),GraphJson.Limits.defaults()));
                        if(!"evidence.java-readiness".equals(GraphJson.object(raw.get("catalog")).get("id")))
                            addCause(delta,causeLabels,raw.get("catalog"),raw.get("detectingProvider"),GraphJson.string(raw.get("reasonCode")),active.contains(node.identity()));
                    }else addCause(delta,causeLabels,new VersionedIdentifier("evidence.java-root-frontier","m8.4-v1"),PROVIDER,"UNCLASSIFIED_GRAPH_GAP",active.contains(node.identity()));
                }
            });
            if(outcome!=null)for(var gap:outcome.gaps())if(seen.add(gap.gapIdentity().value())&&!gap.catalog().id().equals("evidence.java-readiness"))
                addCause(delta,causeLabels,gap.catalog(),gap.detectingProvider(),gap.reasonCode(),true);
            String state=!selected?"UNSELECTED":failures.containsKey(context.id())||outcome!=null&&(outcome.completion()==AnalysisJobs.Completion.FAILED||graph.isEmpty())?"FAILED":outcome==null?"PENDING":
                    outcome.completion()==AnalysisJobs.Completion.COMPLETE&&active.isEmpty()?"COMPLETE":"PARTIAL";
            String reason=failures.getOrDefault(context.id(),state.equals("PENDING")?"CONTEXT_PROVIDER_REQUIRED":state.equals("FAILED")?"CONTEXT_ANALYSIS_FAILED":state.equals("PARTIAL")?"CONTEXT_ACTIVE_GAPS":state.equals("UNSELECTED")?"CONTEXT_NOT_SELECTED":"");
            rows.put(context.id(),new ContextRow(context.root(),context.module(),context.sourceSet(),context.variant(),state,graph.map(KnowledgeGraph::identity),graph.map(KnowledgeGraph::snapshot),(long)active.size(),reason));
            if(!state.equals("COMPLETE"))gaps.add(contextGap(snapshot,context,reason,graph.map(KnowledgeGraph::identity).orElse(context.identity())));
        }
        var counts=new TreeMap<String,Long>();counts.put("total",(long)rows.size());for(String state:List.of("PENDING","COMPLETE","PARTIAL","FAILED","UNSELECTED"))counts.put(state,rows.values().stream().filter(r->r.state().equals(state)).count());
        counts.put("readinessRequirements",(long)readiness.requirements().size());counts.put("readinessActive",readiness.active());
        long before=0,after=0;for(var cause:delta.values()){before=Math.addExact(before,cause[0]);after=Math.addExact(after,cause[1]);}counts.put("rootCauseBefore",before);counts.put("rootCauseAfter",after);
        var causes=new ArrayList<Map<String,Object>>();delta.forEach((key,count)->{var value=new TreeMap<>(causeLabels.get(key));value.put("before",count[0]);value.put("after",count[1]);causes.add(value);});
        var summary=GraphEvidence.capture("java.evidence.repository-coverage",Map.of("schema","java-repository-coverage:m8.4-v1","readiness",readiness.identity(),"plan",plan.identity(),
                "policy",policy,"implementation",components.identity(),"contexts",rows,"counts",counts,"semanticAccuracy","NOT_CERTIFIED","rootCausePopulation","ROOT_AND_SELECTED_CONTEXT_GAP_IDENTITIES","rootCauseDelta",causes));
        String contextId=KnowledgeGraph.derivedIdentity("java-root-coverage-context",List.of(plan.identity(),policy));
        var evidence=new ArrayList<>(List.of(summary,readiness.evidence(),components));var nodes=new ArrayList<KnowledgeGraph.Node>();
        rows.forEach((id,row)->{var captured=GraphEvidence.capture("java.evidence.context-result",Map.of("context",id,"result",row));evidence.add(captured);nodes.add(new KnowledgeGraph.Node(KnowledgeGraph.derivedIdentity("java-context-result",List.of(plan.identity(),id)),contextId,"JAVA_CONTEXT_RESULT",List.of(captured.identity())));});
        gaps.forEach(gap->{var captured=GraphEvidence.capture("evidence.gap",gap);evidence.add(captured);nodes.add(new KnowledgeGraph.Node(gap.gapIdentity().value(),contextId,"CAPABILITY_GAP",List.of(captured.identity())));});
        var root=new KnowledgeGraph(snapshot,List.of(new KnowledgeGraph.Context(contextId,snapshot,Optional.empty(),summary.identity())),nodes,List.of(),evidence);
        return new Result(root,readiness,rows,List.copyOf(gaps));
    }
    private static void addCause(Map<String,long[]> delta,Map<String,Map<String,Object>> causeLabels,Object catalog,Object provider,String reason,boolean active){
        var labels=Map.<String,Object>of("catalog",catalog,"provider",provider,"reasonCode",reason);String key=CanonicalJson.write(labels);causeLabels.put(key,labels);
        var count=delta.computeIfAbsent(key,k->new long[2]);count[0]=Math.addExact(count[0],1);if(active)count[1]=Math.addExact(count[1],1);
    }
    private CapabilityGapRecord contextGap(SnapshotIdentity snapshot,ReadinessPlan.Context context,String reason,ContentDigest payload){
        var subject=new EvidenceSubject(EvidenceSubject.Kind.BUILD_INPUT,context.id());var observation=ProviderObservationReference.create(PROVIDER,"java.context-coverage",plan.identity(),payload);
        var requirement=new EvidenceRequirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND,"java.context-coverage",List.of(subject),EvidenceRequirement.AuthorizationClass.PASSIVE,List.of("Complete this exact selected context; retain each child snapshot/graph and remaining frontier."));
        return CapabilityGapRecord.create(new VersionedIdentifier("evidence.java-root-coverage","m8.4-v1"),EvidenceContext.forSnapshot(snapshot),PROVIDER,"repository.context-coverage",reason,subject,
                List.of(),List.of(observation),List.of(requirement),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"repository.context-coverage")),List.of(),List.of(),List.of("Context coverage is separate from semantic accuracy and runtime configuration closure."));
    }
    private void publish(Result result,BooleanSupplier cancelled,PublicationFence fence)throws java.io.IOException{
        check(cancelled);current(result.readiness());currentComponents(fence.components);graphs.save(result.graph(),cancelled);
        if(checkpoint.isEmpty())return;var target=checkpoint.orElseThrow();
        if(fence.lost)throw new java.io.IOException("JAVA_PUBLICATION_FENCE_MISMATCH");
        if(!fence.loaded){fence.expected=target.store().head(target.key()).map(p->p.document().digest());fence.loaded=true;}
        var artifacts=new TreeMap<String,com.evolution.analysis.acquisition.streaming.ContentHandle>();
        for(var item:Map.of("graph",new GraphSnapshotCodec().encode(result.graph()),"coverage",CanonicalJson.write(result.contexts()),"readiness",CanonicalJson.write(result.readiness().wire())).entrySet())
            artifacts.put(item.getKey(),target.content().put(new java.io.ByteArrayInputStream(item.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)),cancelled));
        current(result.readiness());
        try{fence.expected=Optional.of(target.store().commit(target.key(),fence.expected,artifacts,cancelled).document().digest());}
        catch(java.io.IOException failed){if("JAVA_PUBLICATION_FENCE_MISMATCH".equals(failed.getMessage()))fence.lost=true;throw failed;}
    }
    private Result publishProgress(Result result,BooleanSupplier cancelled,PublicationFence fence)throws java.io.IOException{
        try{publish(result,cancelled,fence);return result;}
        catch(java.io.IOException|GraphStoreException unavailable){
            check(cancelled);current(result.readiness());currentComponents(fence.components);
            var reason=fence.lost?JavaEvidenceGaps.Reason.VALIDATION_REJECTED:JavaEvidenceGaps.Reason.STORAGE_UNAVAILABLE;
            var gap=JavaEvidenceGaps.create(Map.of("baseSnapshot",result.graph().snapshot().value()),reason,plan.identity(),result.graph().identity());
            var gaps=new TreeSet<>(result.gaps());gaps.add(gap);var evidence=new ArrayList<>(result.graph().evidence());
            var captured=GraphEvidence.capture("evidence.gap",gap);evidence.add(captured);
            evidence.add(GraphEvidence.capture("java.evidence.publication-failure",Map.of("reason",fence.lost?"JAVA_PUBLICATION_FENCE_MISMATCH":"JAVA_ROOT_CHECKPOINT_STORAGE_UNAVAILABLE","plan",plan.identity())));
            var nodes=new ArrayList<>(result.graph().nodes());nodes.add(new KnowledgeGraph.Node(gap.gapIdentity().value(),result.graph().contexts().getFirst().identity(),"CAPABILITY_GAP",List.of(captured.identity())));
            return new Result(new KnowledgeGraph(result.graph().snapshot(),result.graph().contexts(),nodes,result.graph().edges(),evidence),result.readiness(),result.contexts(),List.copyOf(gaps));
        }
    }
}
