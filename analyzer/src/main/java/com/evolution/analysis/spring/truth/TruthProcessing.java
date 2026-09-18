package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Closed M4D issue catalog, additive to all M4A-M4C capability records. */
public final class TruthProcessing {
    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("spring.truth-region-evaluator", "m4d-v1");
    public static final VersionedIdentifier SEMANTICS =
            new VersionedIdentifier("spring.truth-region-semantics", "m4d-v1");
    public static final VersionedIdentifier GAPS =
            new VersionedIdentifier("evidence.spring-truth-region-gaps", "m4d-v1");

    public enum Reason {
        CONTEXT_MISMATCH,
        BASELINE_INVALID,
        ENUMERATION_INCOMPLETE,
        FEASIBILITY_UNKNOWN,
        INVALID_MODEL,
        FACT_LIMIT,
        REGION_LIMIT,
        OPERATIONAL_FAILURE,
        WITNESS_LIMIT,
        WITNESS_REPLAY_FAILED
    }

    public record Issue(Reason reason, String subject,
                        Optional<ConditionalFactKey.Identity> fact,
                        List<ConditionEvidence> evidence) {
        public Issue {
            Objects.requireNonNull(reason); Objects.requireNonNull(subject); Objects.requireNonNull(fact);
            if (subject.isEmpty()) throw new IllegalArgumentException("Empty truth-region issue subject");
            evidence = evidence.stream().distinct()
                    .sorted(Comparator.comparing(ConditionEvidence::identity)).toList();
        }
        public ContentDigest identity() { return TruthIdentity.digest(this); }
    }

    private TruthProcessing() {}

    static List<CapabilityGapRecord> gaps(ContentDigest input, SpringBuildContext build,
                                          Collection<Issue> issues) {
        return issues.stream().map(issue -> {
            var observation = ProviderObservationReference.create(PROVIDER,
                    "spring.truth-region.issue", input, issue.identity());
            var subject = EvidenceSubject.observation(observation.identity());
            var kind = issue.reason() == Reason.CONTEXT_MISMATCH
                    ? EvidenceRequirement.Kind.EXACT_CLASSPATH
                    : EvidenceRequirement.Kind.CONFIGURATION;
            var requirement = new EvidenceRequirement(kind,
                    "spring.truth-region." + issue.reason().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    List.of(subject), EvidenceRequirement.AuthorizationClass.PASSIVE,
                    List.of("Supply compatible, bounded evidence and replay the affected truth-region obligation."));
            return CapabilityGapRecord.create(GAPS,
                    EvidenceContext.forSnapshot(build.snapshotIdentity()), PROVIDER,
                    "spring.truth-region", issue.reason().name(), subject,
                    issue.evidence().stream().flatMap(e -> e.spans().stream()).distinct().sorted().toList(),
                    List.of(observation), List.of(requirement), List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.truth-region")),
                    List.of(), List.of(), List.of(
                            "Truth is bounded to one exact build, finite configuration space and M4C semantics context; no runtime-container or G3 claim."));
        }).sorted().toList();
    }
}
