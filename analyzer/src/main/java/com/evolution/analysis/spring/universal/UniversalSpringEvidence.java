package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.ConditionEvidence;
import java.util.*;

/** Additive, closed obligations for the passive universal Spring providers. */
public final class UniversalSpringEvidence {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.universal", "m4u.2");
    public static final VersionedIdentifier CATALOG = new VersionedIdentifier("evidence.spring-universal-gaps", "m4u.2-v1");
    public enum Reason {
        EVIDENCE_MISSING, EVIDENCE_CONFLICT, FRAMEWORK_VERSION_UNKNOWN, NAMESPACE_INCOMPATIBLE,
        REPOSITORY_ANCESTRY_UNKNOWN, REPOSITORY_SCAN_UNKNOWN, REPOSITORY_STORE_AMBIGUOUS,
        ANNOTATION_UNSUPPORTED, METADATA_INVALID, RESOURCE_LIMIT, CONTEXT_HIERARCHY_INVALID,
        CONTEXT_INCOMPLETE, BINDING_AMBIGUOUS, BINDING_UNSATISFIED, BINDING_UNKNOWN,
        ROUTE_UNSUPPORTED, ROUTE_AMBIGUOUS, EXPRESSION_UNSUPPORTED, EXPRESSION_INVALID,
        PROPERTY_UNRESOLVED, SYMBOLIC_INPUT_UNKNOWN, SOLVER_LIMIT, WITNESS_REPLAY_FAILED
    }
    public record Issue(Reason reason, String subject, List<ConditionEvidence> evidence) {
        public Issue {
            Objects.requireNonNull(reason); ContractChecks.text(subject, "Spring issue subject");
            evidence = evidence.stream().distinct().sorted(Comparator.comparing(ConditionEvidence::identity)).toList();
            if (evidence.isEmpty()) throw new IllegalArgumentException("An obligation needs evidence");
        }
        public ContentDigest identity() { return IngestionEvidence.digest(this); }
    }
    public static ConditionEvidence derived(ContentDigest input, String slot) {
        return new ConditionEvidence.Derived(List.of(input), PROVIDER, slot);
    }
    public static List<CapabilityGapRecord> gaps(SnapshotIdentity snapshot, ContentDigest input, Collection<Issue> issues) {
        return issues.stream().distinct().map(issue -> {
            var observation = ProviderObservationReference.create(PROVIDER, "spring.universal.issue", input, issue.identity());
            var subject = EvidenceSubject.observation(observation.identity());
            return CapabilityGapRecord.create(CATALOG, EvidenceContext.forSnapshot(snapshot), PROVIDER,
                    "spring.universal", issue.reason().name(), subject,
                    issue.evidence().stream().flatMap(e -> e.spans().stream()).distinct().sorted().toList(),
                    List.of(observation), List.of(new EvidenceRequirement(EvidenceRequirement.Kind.CONFIGURATION,
                            "spring.universal." + issue.reason().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                            List.of(subject), EvidenceRequirement.AuthorizationClass.PASSIVE,
                            List.of("Supply the exact evidence identified by this obligation."))), List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.universal")),
                    List.of(), List.of(), List.of("Candidate structure and selected definitions do not prove successful runtime instantiation."));
        }).distinct().sorted().toList();
    }
    private UniversalSpringEvidence() {}
}
