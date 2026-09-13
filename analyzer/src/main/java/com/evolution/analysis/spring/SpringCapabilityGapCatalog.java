package com.evolution.analysis.spring;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import java.util.List;

/** Versioned M4A.2 reason mapping; it reuses the M3 CapabilityGapRecord schema unchanged. */
public final class SpringCapabilityGapCatalog {
    public static final VersionedIdentifier CATALOG =
            new VersionedIdentifier("evidence.spring-mechanism-gaps", "m4a.2-v1");
    private static final String SATISFACTION =
            "Exact evidence establishes the mechanism role and its applicable Spring version fragment.";

    private SpringCapabilityGapCatalog() {}

    public record Entry(EvidenceRequirement.Kind requirementKind, String question,
            EvidenceRequirement.AuthorizationClass authorizationClass, AffectedOutput affectedOutput) {
        public Entry {
            ContractChecks.notNull(requirementKind, "Spring gap requirement");
            question = ContractChecks.namespacedId(question, "Spring gap question");
            ContractChecks.notNull(authorizationClass, "Spring gap authorization");
            ContractChecks.notNull(affectedOutput, "Spring gap affected output");
        }

        public EvidenceRequirement requirement(EvidenceSubject subject) {
            return new EvidenceRequirement(requirementKind, question, List.of(subject), authorizationClass,
                    List.of(SATISFACTION));
        }
    }

    public static Entry entryFor(SpringMechanismInventory.ProblemReason reason) {
        return switch (reason) {
            case UNCLASSIFIED_MECHANISM, ANNOTATION_SEMANTICS_NOT_ADJUDICATED ->
                    entry(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                            "spring.establish-mechanism-semantics",
                            EvidenceRequirement.AuthorizationClass.LOCAL_READ);
            case VERSION_FRAGMENT_NOT_VALIDATED ->
                    entry(EvidenceRequirement.Kind.EXACT_CLASSPATH,
                            "spring.validate-version-fragment", EvidenceRequirement.AuthorizationClass.PASSIVE);
            case ANNOTATION_GRAPH_INCOMPLETE ->
                    entry(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                            "spring.resolve-annotation-declaration-graph",
                            EvidenceRequirement.AuthorizationClass.LOCAL_READ);
            case CONSTRUCTOR_SET_INCOMPLETE ->
                    entry(EvidenceRequirement.Kind.BYTECODE, "spring.resolve-constructor-set",
                            EvidenceRequirement.AuthorizationClass.LOCAL_READ);
            case GENERATED_MEMBER_NOT_ACQUIRED ->
                    entry(EvidenceRequirement.Kind.GENERATED_SOURCE, "spring.resolve-generated-member",
                            EvidenceRequirement.AuthorizationClass.LOCAL_READ);
            case PARAMETER_EVIDENCE_INCOMPLETE ->
                    entry(EvidenceRequirement.Kind.BYTECODE, "spring.resolve-parameter-evidence",
                            EvidenceRequirement.AuthorizationClass.LOCAL_READ);
            case AGGREGATE_OR_PROVIDER_UNMODELED ->
                    entry(EvidenceRequirement.Kind.BYTECODE, "spring.resolve-aggregate-provider-shape",
                            EvidenceRequirement.AuthorizationClass.LOCAL_READ);
            case FACTORY_PRODUCT_TYPE_UNKNOWN ->
                    entry(EvidenceRequirement.Kind.BYTECODE, "spring.resolve-factory-product-type",
                            EvidenceRequirement.AuthorizationClass.LOCAL_READ);
            case REPOSITORY_REGISTRATION_UNPROVED ->
                    entry(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                            "spring.resolve-repository-registration",
                            EvidenceRequirement.AuthorizationClass.LOCAL_READ);
            case DYNAMIC_REGISTRY_MUTATION ->
                    entry(EvidenceRequirement.Kind.RUNTIME_OBSERVATION,
                            "spring.observe-registry-mutation",
                            EvidenceRequirement.AuthorizationClass.RUNTIME_ACCESS);
            case DYNAMIC_LOOKUP_TARGET ->
                    entry(EvidenceRequirement.Kind.RUNTIME_OBSERVATION,
                            "spring.observe-container-lookup",
                            EvidenceRequirement.AuthorizationClass.RUNTIME_ACCESS);
            case ENTRYPOINT_OR_LIFECYCLE_UNMODELED ->
                    entry(EvidenceRequirement.Kind.RUNTIME_OBSERVATION,
                            "spring.observe-framework-entrypoint",
                            EvidenceRequirement.AuthorizationClass.RUNTIME_ACCESS);
        };
    }

    private static Entry entry(EvidenceRequirement.Kind kind, String question,
            EvidenceRequirement.AuthorizationClass authorizationClass) {
        return new Entry(kind, question, authorizationClass,
                new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,
                        "spring.mechanism-inventory"));
    }
}
