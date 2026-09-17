package com.evolution.analysis.spring.registration;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** M4C.2 catalog; M4C.1 identities and historical gaps remain unchanged. */
public final class BeanRegistrationProcessing {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.bean-registration", "m4c.2");
    public static final VersionedIdentifier SEMANTICS = new VersionedIdentifier("spring.bean-registration-semantics", "m4c.2-v1");
    public static final VersionedIdentifier GAPS = new VersionedIdentifier("evidence.spring-bean-registration-gaps", "m4c.2-v1");
    public enum Reason {
        VERSION_FRAGMENT_NOT_VALIDATED, CONTEXT_MISMATCH, SOURCE_EVIDENCE_MISMATCH,
        ORDER_EVIDENCE_MISSING, DISCOVERY_INCOMPLETE, REGISTRATION_STEP_MISSING,
        INITIAL_REGISTRY_OPEN, INITIAL_REGISTRY_CONFLICT, CONDITION_UNKNOWN, CONDITION_PHASE_UNKNOWN,
        QUERY_METADATA_INCOMPLETE, QUERY_REFINEMENT_INVALID, HIERARCHY_UNSUPPORTED,
        PARAMETERIZED_CONTAINER_UNSUPPORTED, MATCH_EVIDENCE_MISSING, CANDIDATE_FLAGS_UNKNOWN,
        CANDIDATE_NAMES_UNRESOLVED, OVERRIDE_POLICY_UNKNOWN, DEFINITION_OVERRIDE_FORBIDDEN,
        ALIAS_OVERRIDE_FORBIDDEN, ALIAS_CYCLE, REGISTRY_MUTATION_UNKNOWN,
        CONFIGURATION_GATE_MISSING, PRIOR_EXECUTION_UNKNOWN, PRIOR_CONTAINER_ERROR,
        REGISTRATION_LIMIT, INVALID_CONFIGURATION, ALIAS_ORDER_REQUIRED, DEFINITION_NOT_FOUND,
        FACTORY_QUERY_UNSUPPORTED, INVALID_DEFINITION_NAME, INVALID_ALIAS_NAME,
        QUERY_TYPE_UNKNOWN, QUERY_TYPE_ERROR, QUERY_TYPE_CONFLICT, ALIAS_SHADOW_ANNOTATION_UNSUPPORTED
    }
    public record Issue(Reason reason, String subject, Optional<String> step, List<ConditionEvidence> evidence) {
        public Issue {
            Objects.requireNonNull(reason); subject = RegistrationIdentity.text(subject); Objects.requireNonNull(step);
            evidence = evidence.stream().distinct().sorted(Comparator.comparing(ConditionEvidence::identity)).toList();
        }
        public ContentDigest identity() { return RegistrationIdentity.digest(this); }
    }
    private BeanRegistrationProcessing() {}
    static List<CapabilityGapRecord> gaps(ContentDigest input, SpringBuildContext build, Collection<Issue> issues) {
        return issues.stream().map(issue -> {
            var observation = ProviderObservationReference.create(PROVIDER, "spring.bean-registration.issue", input, issue.identity());
            var subject = EvidenceSubject.observation(observation.identity());
            var kind = switch (issue.reason()) {
                case CONTEXT_MISMATCH -> EvidenceRequirement.Kind.EXACT_CLASSPATH;
                case SOURCE_EVIDENCE_MISMATCH, DISCOVERY_INCOMPLETE, REGISTRATION_STEP_MISSING -> EvidenceRequirement.Kind.REPOSITORY_CONTENT;
                case VERSION_FRAGMENT_NOT_VALIDATED, MATCH_EVIDENCE_MISSING, QUERY_REFINEMENT_INVALID -> EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT;
                case REGISTRY_MUTATION_UNKNOWN -> EvidenceRequirement.Kind.RUNTIME_OBSERVATION;
                default -> EvidenceRequirement.Kind.CONFIGURATION;
            };
            var requirement = new EvidenceRequirement(kind,
                    "spring.bean-registration." + issue.reason().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    List.of(subject), kind == EvidenceRequirement.Kind.RUNTIME_OBSERVATION
                    ? EvidenceRequirement.AuthorizationClass.RUNTIME_ACCESS : EvidenceRequirement.AuthorizationClass.PASSIVE,
                    List.of("Supply exact evidence for the recorded ordered registration obligation."));
            return CapabilityGapRecord.create(GAPS, EvidenceContext.forSnapshot(build.snapshotIdentity()), PROVIDER,
                    "spring.bean-registration", issue.reason().name(), subject,
                    issue.evidence().stream().flatMap(e -> e.spans().stream()).distinct().sorted().toList(),
                    List.of(observation), List.of(requirement), List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.bean-registration")),
                    List.of(), List.of(), List.of("One supplied configuration and evidenced registration prefix; no binding or runtime equivalence claim."));
        }).sorted().toList();
    }
}
