package com.evolution.analysis.spring;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import java.util.List;

/** Versioned M4A.1 reason mapping; it reuses the M3 CapabilityGapRecord schema unchanged. */
public final class SpringCapabilityGapCatalog {
    public static final VersionedIdentifier CATALOG =
            new VersionedIdentifier("evidence.spring-mechanism-gaps", "m4a.1-v1");
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
                            "spring.establish-mechanism-semantics");
            case VERSION_FRAGMENT_NOT_VALIDATED ->
                    entry(EvidenceRequirement.Kind.EXACT_CLASSPATH,
                            "spring.validate-version-fragment");
        };
    }

    private static Entry entry(EvidenceRequirement.Kind kind, String question) {
        return new Entry(kind, question, EvidenceRequirement.AuthorizationClass.PASSIVE,
                new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,
                        "spring.mechanism-inventory"));
    }
}
