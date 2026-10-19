package com.evolution.analysis.readiness;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

public final class CapturedBuildEvidence {
    public enum Trust { CAPTURED_BYTES, PRODUCER_ASSERTION, VALIDATED_OBSERVATION }
    public record Manifest(ContentDigest context, ContentDigest selection, ContentDigest inputClosure,
            VersionedIdentifier provider, ContentDigest toolchain, List<ContentDigest> orderedClasspath,
            List<ContentDigest> orderedModulepath, List<String> compilerArguments, List<ContentDigest> generatedInputs) {
        public Manifest {
            Objects.requireNonNull(context);Objects.requireNonNull(selection);Objects.requireNonNull(inputClosure);Objects.requireNonNull(provider);Objects.requireNonNull(toolchain);
            orderedClasspath=List.copyOf(orderedClasspath);orderedModulepath=List.copyOf(orderedModulepath);compilerArguments=List.copyOf(compilerArguments);generatedInputs=List.copyOf(generatedInputs);
        }
    }
    public record Observation(Manifest manifest, Trust trust, int exitCode, boolean compilationApplicable,
            boolean nativeSemanticValidation, Optional<ContentDigest> noCompilePhaseProof,
            List<ContentDigest> outputs, ContentDigest protectedReceipt) {
        public Observation {Objects.requireNonNull(manifest);Objects.requireNonNull(trust);Objects.requireNonNull(noCompilePhaseProof);outputs=List.copyOf(outputs);Objects.requireNonNull(protectedReceipt);}
        public ContentDigest identity(){return CanonicalJson.sha256(this);}
    }
    @FunctionalInterface public interface ProtectedCaptureVerifier {
        boolean verifies(Observation observation);
    }
    public static final class Validated {
        private final Observation observation;
        private Validated(Observation observation){this.observation=observation;}
        public Observation observation(){return observation;}
    }
    public static Optional<Validated> validate(Manifest expected,Observation observation,ProtectedCaptureVerifier verifier){
        Objects.requireNonNull(expected);Objects.requireNonNull(observation);Objects.requireNonNull(verifier);
        if(!expected.equals(observation.manifest())||observation.trust()!=Trust.VALIDATED_OBSERVATION||observation.exitCode()!=0
                ||!observation.nativeSemanticValidation()||observation.outputs().isEmpty()
                ||!observation.compilationApplicable()&&observation.noCompilePhaseProof().isEmpty()||!verifier.verifies(observation))return Optional.empty();
        return Optional.of(new Validated(observation));
    }
    public static ReadinessPlan reconcile(ReadinessPlan plan,Collection<Validated> captures){
        var requirements=new ArrayList<ReadinessPlan.Requirement>();
        for(var requirement:plan.requirements()){
            var matches=captures.stream().map(Validated::observation).filter(observation->observation.manifest().selection().equals(plan.selection().identity()))
                    .filter(observation->plan.contexts().stream().anyMatch(context->context.id().equals(requirement.subject())
                            &&context.identity().equals(observation.manifest().context())&&context.inputClosure().equals(observation.manifest().inputClosure())))
                    .filter(observation->requirement.kind()==ReadinessPlan.Kind.BUILD_VALIDATION||requirement.kind()==ReadinessPlan.Kind.SEMANTIC_VALIDATION).toList();
            if(matches.size()==1){
                var capture=matches.getFirst();requirements.add(new ReadinessPlan.Requirement(requirement.id(),requirement.kind(),requirement.subject(),requirement.reason(),requirement.prerequisites(),requirement.evidence(),
                        capture.compilationApplicable()?ReadinessPlan.State.SATISFIED:ReadinessPlan.State.NOT_APPLICABLE,Optional.of(capture.identity())));
            }else requirements.add(requirement);
        }
        return new ReadinessPlan(plan.schema(),plan.inventory(),plan.selection(),plan.roots(),plan.modules(),plan.contexts(),requirements,plan.recipes(),plan.ownership());
    }
    private CapturedBuildEvidence(){}
}
