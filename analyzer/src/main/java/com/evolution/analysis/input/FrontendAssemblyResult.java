package com.evolution.analysis.input;

import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.FrontendRequest;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Per-source-set assembly ledger; withheld inputs cannot disappear from coverage. */
public record FrontendAssemblyResult(
        ContentDigest identity,
        String schemaVersion,
        ContentDigest inputIdentity,
        VersionedIdentifier provider,
        List<Outcome> outcomes,
        List<String> limitations) {
    public static final String SCHEMA = "frontend-input-assembly-v2";
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("frontend.input-assembler", "m3.8.1");
    public static final List<String> LIMITATIONS = List.of(
            "Verified reactor output JARs are preferred; complete owned and decoded sibling-module sources may occupy the same exact classpath position without filesystem discovery or lifecycle execution. Class directories and generated sources require later providers.",
            "A source set is withheld when required source, platform, dependency or reactor resolution evidence is invalid or absent; inactive-profile qualifiers, recovered descriptor warnings and deterministically truncated dependency cycles remain visible without masquerading as missing evidence, and no ambient fallback is added.");

    public FrontendAssemblyResult {
        ContractChecks.notNull(identity, "assembly identity");
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported assembly schema");
        ContractChecks.notNull(inputIdentity, "assembly input identity");
        ContractChecks.notNull(provider, "assembly provider");
        outcomes = ContractChecks.sortedDistinct(outcomes, FrontendAssemblyResult::compare, "assembly outcomes");
        limitations = ContractChecks.sortedStrings(limitations, "assembly limitations");
        if (!identity.equals(derive(inputIdentity, provider, outcomes, limitations))) {
            throw new IllegalArgumentException("Assembly identity does not match outcomes");
        }
    }

    static FrontendAssemblyResult create(ContentDigest inputIdentity, List<Outcome> outcomes) {
        List<Outcome> sorted = outcomes.stream().sorted(FrontendAssemblyResult::compare).toList();
        List<String> limitations = LIMITATIONS.stream().sorted().toList();
        return new FrontendAssemblyResult(derive(inputIdentity, PROVIDER, sorted, limitations), SCHEMA,
                inputIdentity, PROVIDER, sorted, limitations);
    }

    private static int compare(Outcome left, Outcome right) {
        int module = left.module().compareTo(right.module());
        return module != 0 ? module : left.sourceSet().compareTo(right.sourceSet());
    }

    private static ContentDigest derive(
            ContentDigest inputIdentity,
            VersionedIdentifier provider, List<Outcome> outcomes, List<String> limitations) {
        List<OutcomeView> views = outcomes.stream().map(value -> new OutcomeView(
                value.module(), value.sourceSet(), value.status(),
                value.request().map(request -> request.manifest().identity()), value.problems())).toList();
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", SCHEMA, "inputs", inputIdentity, "provider", provider,
                "outcomes", views, "limitations", limitations)));
    }

    public boolean hasGaps() { return outcomes.stream().anyMatch(value -> value.status() == Status.WITHHELD); }
    public enum Status { ASSEMBLED, WITHHELD }
    public enum Reason {
        MISSING_SOURCE_INPUT, INVALID_SOURCE_INPUT, MISSING_SOURCE_PLAN, INVALID_SOURCE_PLAN,
        UNSUPPORTED_SYNTAX_LEVEL, PREVIEW_UNSUPPORTED,
        PLATFORM_UNAVAILABLE, PLATFORM_RELEASE_MISMATCH, CLASSPATH_PROBLEM,
        MISSING_DEPENDENCY_BINARY, MISSING_REACTOR_OUTPUT, REACTOR_OUTPUT_MISMATCH,
        UNREFERENCED_BINARY_INPUT
    }
    public enum Requirement {
        DECODED_SOURCE, BUILD_SOURCE_PLAN, PLATFORM_SYMBOLS, EXACT_CLASSPATH,
        DEPENDENCY_ARTIFACT, REACTOR_OUTPUT, INPUT_CONFIGURATION
    }
    public record Problem(Reason reason, String subject, Requirement requirement) {
        public Problem {
            ContractChecks.notNull(reason, "assembly reason");
            ContractChecks.text(subject, "assembly problem subject");
            ContractChecks.notNull(requirement, "assembly requirement");
        }
    }
    public record Outcome(
            ModuleIdentity module,
            SourcePlanModel.Kind sourceSet,
            Status status,
            Optional<FrontendRequest> request,
            List<Problem> problems) {
        public Outcome {
            ContractChecks.notNull(module, "assembly module");
            ContractChecks.notNull(sourceSet, "assembly source set");
            ContractChecks.notNull(status, "assembly status");
            ContractChecks.notNull(request, "frontend request");
            problems = ContractChecks.sortedDistinct(problems, Comparator.comparing(CanonicalJson::write), "assembly problems");
            if ((status == Status.ASSEMBLED) != request.isPresent()
                    || (status == Status.ASSEMBLED) != problems.isEmpty()) {
                throw new IllegalArgumentException("Assembly status does not match request/problems");
            }
        }
    }
    private record OutcomeView(
            ModuleIdentity module,
            SourcePlanModel.Kind sourceSet,
            Status status,
            Optional<com.evolution.analysis.contract.identity.AnalysisIdentity> analysis,
            List<Problem> problems) {}
}
