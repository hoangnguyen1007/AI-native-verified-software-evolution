package com.evolution.analysis.spring.condition;

import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.input.FrontendAssemblyResult;
import java.util.*;

/** Projection of exactly one successfully assembled M3 source set. Performs no discovery or I/O. */
public final class SpringBuildContext {
    private final Identity identity;
    private final SnapshotIdentity snapshot;
    private final Map<String, Object> canonicalForm;
    private final Map<SourceDocumentIdentity, ContentDigest> snapshotSources;

    private SpringBuildContext(FrontendAssemblyResult assembly, FrontendAssemblyResult.Outcome outcome) {
        FrontendRequest request = outcome.request().orElseThrow();
        if (request.plan().equals(FrontendPlan.legacy())) {
            throw new IllegalArgumentException("M4 build context requires an evidence-bound M3 plan");
        }
        if (!request.module().equals(outcome.module()) || !request.sourceSet().name().equals(outcome.sourceSet().name())) {
            throw new IllegalArgumentException("Assembly source-set scope does not match its request");
        }
        snapshot = request.manifest().snapshot().identity();
        Map<SourceDocumentIdentity, ContentDigest> sourceEvidence = new HashMap<>();
        request.manifest().snapshot().files().forEach(file -> sourceEvidence.put(
                SourceDocumentIdentity.from(request.manifest().snapshot().repository(), file.path()), file.contentDigest()));
        snapshotSources = Map.copyOf(sourceEvidence);
        Map<Integer, ReactorSourceInput> reactors = new HashMap<>();
        request.reactorSources().forEach(value -> reactors.put(value.order(), value));
        List<Object> ordered = new ArrayList<>();
        Iterator<BinaryInput> binaries = request.dependencies().iterator();
        int size = request.dependencies().size() + request.reactorSources().size();
        for (int index = 0; index < size; index++) {
            ReactorSourceInput reactor = reactors.get(index);
            ordered.add(reactor == null ? Map.of("kind", "BINARY", "entry", binaries.next().entry())
                    : Map.of("kind", "REACTOR_SOURCE", "identity", reactor.identity()));
        }
        // Existing M3 request binds source selection, decoding, platform and the classpath manifest.
        ContentDigest sourcePlan = ConditionIdentitySupport.digest(Map.of("plan", request.plan(),
                "documents", request.sources().stream().map(SourceInput::document).toList()));
        ContentDigest platform = ConditionIdentitySupport.digest(Map.of("entry", request.platform().entry(),
                "release", request.platform().release(), "version", request.platform().version(),
                "vendor", request.platform().vendor()));
        canonicalForm = Map.of("snapshotIdentity", snapshot, "sourcePlanIdentity", sourcePlan,
                "moduleSourceSet", Map.of("module", request.module(), "sourceSet", outcome.sourceSet()),
                "frontendAssemblyIdentity", assembly.identity(), "orderedResolutionInputs", List.copyOf(ordered),
                "platformViewIdentity", platform, "buildPolicyIdentity", request.manifest().configuration().identity());
        identity = new Identity(ConditionIdentitySupport.derive("spring-build-context", canonicalForm));
    }

    public static SpringBuildContext from(FrontendAssemblyResult assembly, ModuleIdentity module, SourcePlanModel.Kind sourceSet) {
        Objects.requireNonNull(assembly); Objects.requireNonNull(module); Objects.requireNonNull(sourceSet);
        var outcome = assembly.outcomes().stream()
                .filter(value -> value.module().equals(module) && value.sourceSet() == sourceSet).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Source set absent from M3 assembly"));
        if (outcome.status() != FrontendAssemblyResult.Status.ASSEMBLED) {
            throw new IllegalArgumentException("Withheld M3 inputs cannot become an exact Spring build context");
        }
        return new SpringBuildContext(assembly, outcome);
    }
    public Identity identity() { return identity; }
    public SnapshotIdentity snapshotIdentity() { return snapshot; }
    public Map<String, Object> canonicalForm() { return canonicalForm; }
    public boolean containsSource(ConditionEvidence.Source evidence) {
        Objects.requireNonNull(evidence);
        return evidence.rawDigest().equals(snapshotSources.get(evidence.document()));
    }
    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = ConditionIdentitySupport.require(value, "spring-build-context"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }
}
