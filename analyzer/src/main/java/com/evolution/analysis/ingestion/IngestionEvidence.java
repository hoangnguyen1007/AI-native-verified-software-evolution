package com.evolution.analysis.ingestion;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import java.util.*;

/** Shared passive-ingestion evidence; issue subjects contain paths/selectors, never configuration values. */
public final class IngestionEvidence {
    public static final VersionedIdentifier CATALOG = new VersionedIdentifier("evidence.ingestion-gaps", "m4u.1-v1");
    public enum Reason {
        INPUT_UNAVAILABLE, INPUT_LIMIT, MALFORMED_INPUT, UNSAFE_PATH, AMBIGUOUS_INPUT,
        DYNAMIC_BUILD_LOGIC, UNRESOLVED_COORDINATE, DEPENDENCY_VERSION_UNRESOLVED,
        CLASSPATH_RESOLUTION_REQUIRED, COMPILER_CONFIGURATION_MISSING, CUSTOM_SOURCE_LAYOUT,
        CUSTOM_SOURCE_SET_ROLE_UNRESOLVED,
        CONFIG_IMPORT_REQUIRED, CONFIG_ACTIVATION_UNKNOWN, CONFIG_PROFILE_INVALID,
        CONFIG_PLACEHOLDER_UNRESOLVED, CONFIG_PRECEDENCE_UNKNOWN, CONFIG_EXTERNAL_INPUT_REQUIRED, CONFIG_FORMAT_UNSUPPORTED,
        ANNOTATION_EVIDENCE_MISSING, SCAN_FILTER_UNSUPPORTED, SCAN_ROOT_UNRESOLVED,
        COMPONENT_ELIGIBILITY_UNKNOWN, BEAN_NAME_UNRESOLVED, SOURCE_INCOMPLETE, SEMANTIC_INPUT_REJECTED, SEMANTIC_PROVIDER_FAILED
    }
    public record Issue(Reason reason, String subject, List<ContentDigest> inputs) {
        public Issue {
            Objects.requireNonNull(reason); ContractChecks.text(subject, "issue subject");
            inputs = inputs.stream().distinct().sorted().toList();
            if(inputs.isEmpty())throw new IllegalArgumentException("Ingestion issue requires content-addressed input evidence");
        }
        public ContentDigest identity() { return digest(this); }
    }
    public static ContentDigest digest(Object value) { return ContentDigest.sha256Utf8(CanonicalJson.write(value)); }
    public static List<CapabilityGapRecord> gaps(SnapshotIdentity snapshot, VersionedIdentifier provider,
                                                ContentDigest input, Collection<Issue> issues) {
        return issues.stream().distinct().map(issue -> {
            var observation = ProviderObservationReference.create(provider, "ingestion.issue", input, issue.identity());
            var subject = EvidenceSubject.observation(observation.identity());
            var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                    "ingestion." + issue.reason().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    List.of(subject), EvidenceRequirement.AuthorizationClass.PASSIVE,
                    List.of("Supply exact declarative evidence for the recorded ingestion obligation."));
            return CapabilityGapRecord.create(CATALOG, EvidenceContext.forSnapshot(snapshot), provider,
                    "repository.ingestion", issue.reason().name(), subject, List.of(), List.of(observation),
                    List.of(requirement), List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "repository.ingestion")),
                    List.of(), List.of(), List.of("Partial ingestion is not proof of a complete runtime configuration or build."));
        }).distinct().sorted().toList();
    }
    private IngestionEvidence() {}
}
