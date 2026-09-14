package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import java.util.*;

/** Shared, additive M4B.2 operational evidence. Logical truth is a separate axis. */
public final class ConditionProcessing {
    public static final VersionedIdentifier GAP_CATALOG = new VersionedIdentifier("evidence.spring-condition-gaps", "m4b.2-v1");
    public enum Reason {
        VERSION_FRAGMENT_NOT_VALIDATED, BUILD_CONTEXT_MISMATCH, SOURCE_EVIDENCE_MISMATCH, MISSING_SOURCE_SPAN,
        ANNOTATION_METADATA_UNSUPPORTED, ANNOTATION_COMPOSITION_UNSUPPORTED, POTENTIAL_CONDITION_UNCLASSIFIED,
        BUILD_EVIDENCE_UNAVAILABLE, BUILD_EVIDENCE_CONFLICT, OPAQUE_CONDITION, BEAN_STATE_REQUIRED,
        LOWERING_LIMIT, EVALUATION_LIMIT, ASSIGNMENT_MISSING, ASSIGNMENT_OUTSIDE_DOMAIN,
        SOURCE_CHOICE_MISSING, SOURCE_CHOICE_INVALID, SOURCE_VALUE_UNSUPPORTED, SOURCE_UNAVAILABLE,
        REQUIRED_SOURCE_MISSING, POLICY_NOT_SUPPORTED, ACTIVATION_NOT_SUPPORTED, PROFILE_CLOSURE_INCOMPLETE,
        OTHER_ABSTRACTION_NOT_VALIDATED, PROPERTY_SEMANTICS_UNSUPPORTED, UNRESOLVED_CONSTANT,
        INVALID_MODEL, PRECEDENCE_INCOMPLETE, EXPRESSION_NOT_EXPANDED
    }
    public record Issue(Reason reason, String subject, Optional<ConditionOccurrence.Identity> occurrence,
                        List<ConditionEvidence> evidence) {
        public Issue {
            Objects.requireNonNull(reason); subject = ConditionIdentitySupport.name(subject);
            Objects.requireNonNull(occurrence);
            evidence = evidence.stream().distinct().sorted(Comparator.comparing(ConditionEvidence::identity)).toList();
        }
        public ContentDigest identity() { return ConditionIdentitySupport.digest(this); }
    }
    private ConditionProcessing() {}
    static List<CapabilityGapRecord> gaps(VersionedIdentifier provider, ContentDigest input,
                                          SpringBuildContext build, Collection<Issue> issues) {
        return issues.stream().map(issue -> {
            var observation = ProviderObservationReference.create(provider, "spring.condition.processing-issue", input, issue.identity());
            var subject = EvidenceSubject.observation(observation.identity());
            var kind = switch (issue.reason()) {
                case BUILD_CONTEXT_MISMATCH, BUILD_EVIDENCE_UNAVAILABLE, BUILD_EVIDENCE_CONFLICT -> EvidenceRequirement.Kind.EXACT_CLASSPATH;
                case SOURCE_EVIDENCE_MISMATCH, MISSING_SOURCE_SPAN -> EvidenceRequirement.Kind.REPOSITORY_CONTENT;
                case OPAQUE_CONDITION -> EvidenceRequirement.Kind.RUNTIME_OBSERVATION;
                case VERSION_FRAGMENT_NOT_VALIDATED, ANNOTATION_METADATA_UNSUPPORTED,
                        ANNOTATION_COMPOSITION_UNSUPPORTED, POTENTIAL_CONDITION_UNCLASSIFIED -> EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT;
                default -> EvidenceRequirement.Kind.CONFIGURATION;
            };
            var requirement = new EvidenceRequirement(kind,
                    "spring.condition." + issue.reason().name().toLowerCase(Locale.ROOT).replace('_', '-'), List.of(subject),
                    kind == EvidenceRequirement.Kind.RUNTIME_OBSERVATION ? EvidenceRequirement.AuthorizationClass.RUNTIME_ACCESS
                            : EvidenceRequirement.AuthorizationClass.PASSIVE,
                    List.of("Supply exact evidence for this bounded condition-processing obligation."));
            return CapabilityGapRecord.create(GAP_CATALOG, EvidenceContext.forSnapshot(build.snapshotIdentity()), provider,
                    "spring.condition-processing", issue.reason().name(), subject,
                    issue.evidence().stream().flatMap(e -> e.spans().stream()).distinct().sorted().toList(),
                    List.of(observation), List.of(requirement), List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.condition-processing")),
                    List.of(), List.of(), List.of("Exogenous condition evidence is not bean activation, registration, binding or a universal architecture fact."));
        }).sorted().toList();
    }
}
