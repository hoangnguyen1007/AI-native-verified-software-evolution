package com.evolution.analysis.graph;

import com.evolution.analysis.buildmodel.UniversalBuildModel;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.truth.*;
import com.evolution.analysis.spring.universal.SourceToSpringPlan;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/** Full neutral artifacts captured during one execution, before report summarization. */
public record CanonicalGraphInput(RepositoryInputs repository, Optional<UniversalBuildModel> build,
        Optional<UniversalIngestionPipeline.Result> ingestion, List<SpringSlice> spring,
        List<IngestionEvidence.Issue> issues, List<CapabilityGapRecord> gaps,
        Optional<com.evolution.analysis.acquisition.RepositoryAcquisitionResult> acquisition) {
    public CanonicalGraphInput(RepositoryInputs repository,Optional<UniversalBuildModel> build,
            Optional<UniversalIngestionPipeline.Result> ingestion,List<SpringSlice> spring,
            List<IngestionEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        this(repository,build,ingestion,spring,issues,gaps,Optional.empty());
    }
    public record SpringSlice(UniversalSourceIngestion.SourceSet sourceSet, SourceToSpringPlan.Result plan,
            Optional<ConfigurationSpace> space, Optional<ConfigurationAssignment> baseline,
            Optional<TruthRegionEvaluation.Result> explicitTruth,
            Optional<SymbolicTruthRegions.StagedResult> symbolicTruth, Optional<EvaluationParameters> parameters) {
        public SpringSlice(UniversalSourceIngestion.SourceSet sourceSet,SourceToSpringPlan.Result plan,
                Optional<ConfigurationSpace> space,Optional<ConfigurationAssignment> baseline,
                Optional<TruthRegionEvaluation.Result> explicitTruth,Optional<SymbolicTruthRegions.StagedResult> symbolicTruth) {
            this(sourceSet,plan,space,baseline,explicitTruth,symbolicTruth,Optional.empty());
        }
        public SpringSlice {
            Objects.requireNonNull(sourceSet);Objects.requireNonNull(plan);Objects.requireNonNull(space);
            Objects.requireNonNull(baseline);Objects.requireNonNull(explicitTruth);Objects.requireNonNull(symbolicTruth);
            Objects.requireNonNull(parameters);
            if(explicitTruth.isPresent()&&symbolicTruth.isPresent())throw new IllegalArgumentException("Two truth encodings in one slice");
            if((explicitTruth.isPresent()||symbolicTruth.isPresent()) && (space.isEmpty()||baseline.isEmpty()||parameters.isEmpty()))
                throw new IllegalArgumentException("Truth result requires exact space and baseline");
            space.ifPresent(s->{if(!s.buildContext().identity().equals(plan.binding().registrationPlan().discoveryPlan().buildContext().identity()))
                throw new IllegalArgumentException("Foreign Spring space");});
            if(explicitTruth.isPresent()||symbolicTruth.isPresent()) {
                var p=parameters.orElseThrow();var model=ConditionModel.create(space.orElseThrow(),plan.conditions().occurrences());
                if(explicitTruth.isPresent()) {
                    var truth=explicitTruth.orElseThrow();
                    var expected=ContentDigest.sha256Utf8(CanonicalJson.write(Map.ofEntries(
                            Map.entry("schema","spring-truth-region-input-v1"),Map.entry("provider",TruthProcessing.PROVIDER),
                            Map.entry("context",truth.semanticsContextIdentity()),Map.entry("bindingPlan",plan.binding().identity()),
                            Map.entry("conditionModel",model.identity()),Map.entry("conditionSemantics",plan.conditions().semantics()),
                            Map.entry("baseline",baseline.orElseThrow().identity()),Map.entry("reasoner",p.reasonerPolicy()),
                            Map.entry("limits",p.limits()),Map.entry("evaluatorArtifact",p.evaluatorArtifact()))));
                    if(!expected.equals(truth.inputIdentity()))throw new IllegalArgumentException("Foreign explicit truth result");
                } else {
                    var compilation=IngestionEvidence.digest(List.of("spring.symbolic-regions:m4u.2-v1",model.identity(),plan.conditions().semantics(),p.reasonerPolicy(),p.symbolicLimits().orElseThrow()));
                    var expected=IngestionEvidence.digest(List.of("spring.symbolic-staged:m4u.2-v1",compilation,plan.binding().identity(),baseline.orElseThrow().identity(),p.limits(),p.evaluatorArtifact(),p.maxSignatures()));
                    if(!expected.equals(symbolicTruth.orElseThrow().inputIdentity()))throw new IllegalArgumentException("Foreign symbolic truth result");
                }
            }
        }
    }
    public record EvaluationParameters(TruthRegionEvaluation.Limits limits, VersionedIdentifier reasonerPolicy,
            ContentDigest evaluatorArtifact, int maxSignatures, Optional<SymbolicConfiguration.Limits> symbolicLimits) {
        public EvaluationParameters {
            Objects.requireNonNull(limits);Objects.requireNonNull(reasonerPolicy);Objects.requireNonNull(evaluatorArtifact);Objects.requireNonNull(symbolicLimits);
            if(maxSignatures<0 || (maxSignatures>0)!=symbolicLimits.isPresent())throw new IllegalArgumentException("Invalid graph evaluation parameters");
        }
    }
    public CanonicalGraphInput {
        Objects.requireNonNull(repository);Objects.requireNonNull(build);Objects.requireNonNull(ingestion);
        Objects.requireNonNull(acquisition);
        acquisition.ifPresent(a->{if(a.snapshot().isEmpty()||!a.snapshot().orElseThrow().identity().equals(repository.snapshot().identity()))throw new IllegalArgumentException("Foreign acquisition snapshot");});
        spring=List.copyOf(spring);issues=List.copyOf(issues);gaps=List.copyOf(gaps);
        build.ifPresent(b->{if(!b.repositoryInputIdentity().equals(repository.identity()))throw new IllegalArgumentException("Foreign graph build inputs");});
        if(ingestion.isPresent()&&build.isEmpty())throw new IllegalArgumentException("Ingestion has no build provenance");
        if(!spring.isEmpty()&&ingestion.isEmpty())throw new IllegalArgumentException("Spring has no frontend provenance");
        if(ingestion.isPresent()) {
            var result=ingestion.orElseThrow();
            var keys=new HashSet<UniversalSourceIngestion.SourceSet>();
            for(var unit:result.units())if(!keys.add(unit.sourceSet()))throw new IllegalArgumentException("Duplicate ingestion source set");
            for(var outcome:result.sourceIngestion().outcomes())outcome.request().ifPresent(request->{
                if(!request.manifest().snapshot().identity().equals(repository.snapshot().identity())
                        || !build.orElseThrow().identity().value().equals(request.manifest().configuration().values().get("ingestion.build-model")))
                    throw new IllegalArgumentException("Foreign graph source assembly");
            });
            for(var slice:spring) {
                var unit=result.units().stream().filter(u->u.sourceSet().equals(slice.sourceSet())).findFirst().orElseThrow(()->new IllegalArgumentException("Spring source set absent"));
                if(unit.frontend().isEmpty()||!unit.frontend().orElseThrow().analysis().equals(slice.plan().binding().registrationPlan().discoveryPlan().buildContext().analysisIdentity()))
                    throw new IllegalArgumentException("Foreign graph Spring analysis");
            }
        }
    }
}
