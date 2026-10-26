package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.CanonicalGraphBuilder;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import com.evolution.analysis.javaevidence.*;
import java.util.*;

/** Operator-composed execution of one exact captured Java context. This runner performs
 * no target build, does not mint proofs, and is never constructed from an HTTP payload.
 * Each run rechecks the prepared capabilities before graph/job publication. */
public final class PreparedJavaAnalysisRunner implements AnalysisJobs.Runner {
    private final JavaEvidenceAssembler.Prepared prepared;
    private final UniversalArchitecturePipeline.Policy policy;
    private final Optional<JavaSemanticEvidence.Bundle> semantic;
    private final Optional<JavaCompilerAttribution.Bundle> compiler;
    private final java.util.function.Supplier<ContentDigest> authorityPolicy;
    private final List<JavaEvidenceAuthority.Verified> readinessProofs;
    private final Optional<PreparedAnalysisPublication> publication;

    public PreparedJavaAnalysisRunner(JavaEvidenceAssembler.Prepared prepared,UniversalArchitecturePipeline.Policy policy,
            Optional<JavaSemanticEvidence.Bundle> semantic,Optional<JavaCompilerAttribution.Bundle> compiler){
        this(prepared,policy,semantic,compiler,prepared::policy);
    }
    /** Mutable operator registries supply their current policy identity on every dispatch. */
    public PreparedJavaAnalysisRunner(JavaEvidenceAssembler.Prepared prepared,UniversalArchitecturePipeline.Policy policy,
            Optional<JavaSemanticEvidence.Bundle> semantic,Optional<JavaCompilerAttribution.Bundle> compiler,
            java.util.function.Supplier<ContentDigest> authorityPolicy){
        this(prepared,policy,semantic,compiler,authorityPolicy,List.of());
    }
    public PreparedJavaAnalysisRunner(JavaEvidenceAssembler.Prepared prepared,UniversalArchitecturePipeline.Policy policy,
            Optional<JavaSemanticEvidence.Bundle> semantic,Optional<JavaCompilerAttribution.Bundle> compiler,
            java.util.function.Supplier<ContentDigest> authorityPolicy,Collection<JavaEvidenceAuthority.Verified> readinessProofs){
        this(prepared,policy,semantic,compiler,authorityPolicy,readinessProofs,Optional.empty());
    }
    private PreparedJavaAnalysisRunner(JavaEvidenceAssembler.Prepared prepared,UniversalArchitecturePipeline.Policy policy,
            Optional<JavaSemanticEvidence.Bundle> semantic,Optional<JavaCompilerAttribution.Bundle> compiler,
            java.util.function.Supplier<ContentDigest> authorityPolicy,Collection<JavaEvidenceAuthority.Verified> readinessProofs,Optional<PreparedAnalysisPublication> publication){
        this.prepared=Objects.requireNonNull(prepared);this.policy=Objects.requireNonNull(policy);
        this.semantic=Objects.requireNonNull(semantic);this.compiler=Objects.requireNonNull(compiler);
        this.authorityPolicy=Objects.requireNonNull(authorityPolicy);
        this.readinessProofs=List.copyOf(readinessProofs);
        this.publication=publication;
    }
    public PreparedJavaAnalysisRunner withPublication(PreparedAnalysisPublication publication){return new PreparedJavaAnalysisRunner(prepared,policy,semantic,compiler,authorityPolicy,readinessProofs,Optional.of(publication));}
    public Map<String,Object> scope(){return prepared.view().object("scope");}
    public AnalysisJobs.Registration registration(String id){
        int release=prepared.resolutions().values().iterator().next().platform().release();
        boolean tests=prepared.inputs().files().values().stream().anyMatch(s->s.document().classification().name().endsWith("TEST"));
        return new AnalysisJobs.Registration(new AnalysisJobs.Setup(id,prepared.base().snapshot().repository(),release,tests,
                Optional.empty(),policy.configurationSpace().map(s->s.identity().value())),this);
    }
    JavaEvidenceAssembler.Prepared prepared(){return prepared;}
    private record CapturedControl(java.util.function.BooleanSupplier cancelled,java.util.function.Consumer<AnalysisJobs.Stage> progress) implements java.util.function.BooleanSupplier {
        public boolean getAsBoolean(){return cancelled.getAsBoolean()||Thread.currentThread().isInterrupted();}
        void checkpoint(){if(getAsBoolean())throw new java.util.concurrent.CancellationException();}
        void stage(AnalysisJobs.Stage stage){checkpoint();progress.accept(stage);}
    }
    @Override public AnalysisJobs.Outcome run(AnalysisJobs.Control control)throws Exception{return run(new CapturedControl(control,control::stage),true);}
    AnalysisJobs.Outcome runCaptured(java.util.function.BooleanSupplier cancelled)throws Exception{return run(new CapturedControl(cancelled,stage->{}),false);}
    private AnalysisJobs.Outcome run(CapturedControl control,boolean accountSiblings)throws Exception{
        control.stage(AnalysisJobs.Stage.ACQUISITION);control.checkpoint();
        if(!prepared.policy().equals(authorityPolicy.get()))return revoked();
        com.evolution.analysis.graph.GraphEvidence components;
        try{components=CapturedAnalysisComponents.prepared(policy);}catch(java.io.IOException unavailable){return unavailableComponents();}
        var readiness=new JavaReadinessClosure().reconcile(prepared.readiness(),prepared.policy(),readinessProofs);
        var coverageGaps=new TreeSet<com.evolution.analysis.evidence.CapabilityGapRecord>(readiness.gaps(prepared.base().snapshot().identity()));
        if(accountSiblings)coverageGaps.addAll(readiness.remainingContexts(prepared.base().snapshot().identity(),new ContentDigest(scope().get("context").toString())));
        control.stage(AnalysisJobs.Stage.PLATFORM);control.checkpoint();
        control.stage(AnalysisJobs.Stage.ANALYSIS);
        UniversalArchitecturePipeline.DetailedResult detailed;
        var pipeline=new UniversalArchitecturePipeline();
        if(compiler.isPresent())detailed=pipeline.runWithEvidence(prepared,policy,compiler.orElseThrow(),semantic);
        else if(semantic.isPresent())detailed=pipeline.runWithEvidence(prepared,policy,semantic.orElseThrow());
        else detailed=pipeline.runWithEvidence(prepared,policy);
        control.checkpoint();
        var status=detailed.report().status();
        var gaps=new TreeSet<com.evolution.analysis.evidence.CapabilityGapRecord>(detailed.report().gaps());gaps.addAll(coverageGaps);
        if(status==com.evolution.analysis.ingestion.UniversalArchitectureReport.Status.TERMINAL_FAILURE
                ||status==com.evolution.analysis.ingestion.UniversalArchitectureReport.Status.CANCELLED)
            return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.copyOf(gaps),Optional.empty(),Optional.of(detailed.report().identity()));
        control.stage(AnalysisJobs.Stage.GRAPH);
        var original=detailed.graphInput().orElseThrow();gaps.addAll(original.gaps());
        var input=new com.evolution.analysis.graph.CanonicalGraphInput(original.repository(),original.build(),original.ingestion(),original.spring(),original.issues(),List.copyOf(gaps),original.acquisition(),original.javaEvidence());
        var graph=new CanonicalGraphBuilder().build(input);var evidence=new ArrayList<>(graph.evidence());evidence.add(readiness.evidence());
        evidence.add(components);
        Optional<JavaSemanticProjection.Result> projection=Optional.empty();
        try{projection=Optional.of(new JavaSemanticProjection().project(prepared,detailed));evidence.addAll(projection.orElseThrow().evidence());}
        catch(java.io.IOException withheld){
            String code=withheld.getMessage();
            String reason=code!=null&&code.matches("JAVA_SEMANTIC_PROJECTION_[A-Z_]+")?code:"JAVA_SEMANTIC_PROJECTION_UNAVAILABLE";
            evidence.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.semantic-projection-withheld",Map.of("reason",reason,"inputView",prepared.view().identity(),"scope","CAPTURED_JAVA_CONTEXT")));
        }
        if(projection.isPresent())try{
            evidence.add(new JavaSpringSemanticProjection().project(prepared,detailed,policy,projection.orElseThrow()));
        }catch(java.io.IOException|IllegalArgumentException withheld){
            String code=withheld.getMessage();String reason=code!=null&&code.matches("JAVA_SPRING_PROJECTION_[A-Z_]+")?code:"JAVA_SPRING_PROJECTION_UNAVAILABLE";
            evidence.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.spring-semantic-projection-withheld",Map.of("scope","DEFAULT_SCOPE_TRUTH_NONE","reason",reason)));
        }
        graph=new com.evolution.analysis.graph.KnowledgeGraph(graph.snapshot(),graph.contexts(),graph.nodes(),graph.edges(),evidence);control.checkpoint();
        if(!prepared.policy().equals(authorityPolicy.get()))return revoked();
        try{if(!components.identity().equals(CapturedAnalysisComponents.prepared(policy).identity()))return revoked();}catch(java.io.IOException unavailable){return revoked();}
        if(!readiness.identity().equals(new JavaReadinessClosure().reconcile(prepared.readiness(),prepared.policy(),readinessProofs).identity()))return revoked();
        if(publication.isPresent())try{
            publication.orElseThrow().publish(prepared,policy,readiness,graph,control,()->{
                if(!prepared.policy().equals(authorityPolicy.get())||!prepared.invalidationGaps().isEmpty()
                        ||!readiness.identity().equals(new JavaReadinessClosure().reconcile(prepared.readiness(),prepared.policy(),readinessProofs).identity()))throw new java.io.IOException("JAVA_PUBLICATION_INPUT_REVOKED");
                original.javaEvidence().orElseThrow().requireCurrent();
            });
        }catch(java.io.IOException unavailable){
            control.checkpoint();
            if(!prepared.policy().equals(authorityPolicy.get())||!prepared.invalidationGaps().isEmpty()
                    ||!readiness.identity().equals(new JavaReadinessClosure().reconcile(prepared.readiness(),prepared.policy(),readinessProofs).identity()))return revoked();
            String message=unavailable.getMessage();String reason=message!=null&&message.matches("JAVA_PUBLICATION_[A-Z_]+")?message:"JAVA_PUBLICATION_IO_FAILED";
            var kind=reason.equals("JAVA_PUBLICATION_REPLAY_CONFLICT")?JavaEvidenceGaps.Reason.VALIDATION_REJECTED:JavaEvidenceGaps.Reason.STORAGE_UNAVAILABLE;
            gaps.add(JavaEvidenceGaps.create(scope(),kind,prepared.view().identity(),ContentDigest.sha256Utf8(reason)));
            var failedInput=new com.evolution.analysis.graph.CanonicalGraphInput(input.repository(),input.build(),input.ingestion(),input.spring(),input.issues(),List.copyOf(gaps),input.acquisition(),input.javaEvidence());
            graph=new CanonicalGraphBuilder().build(failedInput);evidence=new ArrayList<>(graph.evidence());evidence.add(readiness.evidence());
            evidence.add(components);
            if(projection.isPresent())evidence.addAll(projection.orElseThrow().evidence());
            evidence.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.publication-failure",Map.of("reason",reason,"inputView",prepared.view().identity())));
            graph=new com.evolution.analysis.graph.KnowledgeGraph(graph.snapshot(),graph.contexts(),graph.nodes(),graph.edges(),evidence);
        }
        var complete=status==com.evolution.analysis.ingestion.UniversalArchitectureReport.Status.COMPLETE
                &&readiness.readinessClosed()&&coverageGaps.isEmpty()
                &&graph.nodes().stream().noneMatch(n->Set.of("CAPABILITY_GAP","PROVIDER_CONFLICT").contains(n.kind()));
        return new AnalysisJobs.Outcome(complete?AnalysisJobs.Completion.COMPLETE:AnalysisJobs.Completion.PARTIAL,
                Optional.of(graph),List.of(),List.copyOf(gaps),Optional.empty(),Optional.of(detailed.report().identity()));
    }
    private AnalysisJobs.Outcome revoked(){
        var gap=JavaEvidenceGaps.create(scope(),JavaEvidenceGaps.Reason.EVIDENCE_REVOKED,prepared.view().identity(),prepared.policy());
        return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.of(gap),Optional.empty(),Optional.empty());
    }
    private AnalysisJobs.Outcome unavailableComponents(){
        var gap=JavaEvidenceGaps.create(scope(),JavaEvidenceGaps.Reason.VALIDATION_METHOD_UNAVAILABLE,prepared.view().identity(),ContentDigest.sha256Utf8("JAVA_CAPTURED_COMPONENT_BYTES_UNAVAILABLE"));
        return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.of(gap),Optional.empty(),Optional.empty());
    }
}
