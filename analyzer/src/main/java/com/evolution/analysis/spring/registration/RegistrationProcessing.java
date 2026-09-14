package com.evolution.analysis.spring.registration;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Additive typed M4C.1 obligations; no I/O or permission is implied by a requirement. */
public final class RegistrationProcessing {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.registration-discovery", "m4c.1");
    public static final VersionedIdentifier SEMANTICS = new VersionedIdentifier("spring.registration-discovery-semantics", "m4c.1-v1");
    public static final VersionedIdentifier GAP_CATALOG = new VersionedIdentifier("evidence.spring-registration-gaps", "m4c.1-v1");
    public enum Reason {
        VERSION_FRAGMENT_NOT_VALIDATED, BUILD_CONTEXT_MISMATCH, SOURCE_EVIDENCE_MISMATCH, MISSING_SOURCE_SPAN,
        MEMBERSHIP_UNPROVEN, CONDITION_METADATA_INCOMPLETE, CONDITION_PHASE_UNKNOWN, CONDITION_REFERENCE_MISSING,
        PARSE_CONDITION_UNKNOWN, REGISTRATION_CONDITION_DEFERRED, DISCOVERY_EVIDENCE_REQUIRED,
        CONDITION_PHASE_CONFLICT, CONDITION_ATTACHMENT_REQUIRED, PRIOR_CONTAINER_ERROR,
        ORDER_EVIDENCE_MISSING, ORDER_CYCLE, INVALID_ORDER_DOMAIN, PARENT_REFERENCE_MISSING,
        IMPORT_CYCLE, UNKNOWN_IMPORT_SELECTOR, CUSTOM_REGISTRAR, REGISTRY_MUTATION_UNKNOWN,
        ENVIRONMENT_MUTATION_UNKNOWN, XML_READER_UNSUPPORTED, DISCOVERY_LIMIT, INVALID_CONFIGURATION,
        CANDIDATE_NAMES_UNRESOLVED, CANDIDATE_TYPES_UNRESOLVED, REGISTRATION_NOT_EVALUATED,
        SCAN_REGISTER_PHASE_CONDITION, AUTO_CONFIGURATION_METADATA_INCOMPLETE
    }
    public record Issue(Reason reason, String subject, Optional<RegistrationEvent.Identity> event,
                        List<ConditionEvidence> evidence) {
        public Issue {
            Objects.requireNonNull(reason); subject = RegistrationIdentity.text(subject); Objects.requireNonNull(event);
            evidence = evidence.stream().distinct().sorted(Comparator.comparing(ConditionEvidence::identity)).toList();
        }
        public ContentDigest identity() { return RegistrationIdentity.digest(this); }
    }
    private RegistrationProcessing() {}
    static List<CapabilityGapRecord> gaps(ContentDigest input, SpringBuildContext build, Collection<Issue> issues) {
        return issues.stream().map(issue -> {
            var observation = ProviderObservationReference.create(PROVIDER, "spring.registration.issue", input, issue.identity());
            var subject = EvidenceSubject.observation(observation.identity());
            var kind = switch (issue.reason()) {
                case BUILD_CONTEXT_MISMATCH -> EvidenceRequirement.Kind.EXACT_CLASSPATH;
                case SOURCE_EVIDENCE_MISMATCH, MISSING_SOURCE_SPAN, MEMBERSHIP_UNPROVEN, DISCOVERY_EVIDENCE_REQUIRED -> EvidenceRequirement.Kind.REPOSITORY_CONTENT;
                case UNKNOWN_IMPORT_SELECTOR, CUSTOM_REGISTRAR, REGISTRY_MUTATION_UNKNOWN, ENVIRONMENT_MUTATION_UNKNOWN -> EvidenceRequirement.Kind.RUNTIME_OBSERVATION;
                case VERSION_FRAGMENT_NOT_VALIDATED, CONDITION_PHASE_UNKNOWN, CONDITION_PHASE_CONFLICT, AUTO_CONFIGURATION_METADATA_INCOMPLETE -> EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT;
                default -> EvidenceRequirement.Kind.CONFIGURATION;
            };
            var requirement = new EvidenceRequirement(kind, "spring.registration." + issue.reason().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    List.of(subject), kind == EvidenceRequirement.Kind.RUNTIME_OBSERVATION
                    ? EvidenceRequirement.AuthorizationClass.RUNTIME_ACCESS : EvidenceRequirement.AuthorizationClass.PASSIVE,
                    List.of("Supply exact evidence for this bounded registration/discovery obligation."));
            return CapabilityGapRecord.create(GAP_CATALOG, EvidenceContext.forSnapshot(build.snapshotIdentity()), PROVIDER,
                    "spring.registration-discovery", issue.reason().name(), subject,
                    issue.evidence().stream().flatMap(e -> e.spans().stream()).distinct().sorted().toList(),
                    List.of(observation), List.of(requirement), List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.registration-discovery")),
                    List.of(), List.of(), List.of("Discovery does not establish final registration, bean activation, binding or runtime behavior."));
        }).sorted().toList();
    }
}
