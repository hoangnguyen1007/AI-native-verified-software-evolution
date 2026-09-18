package com.evolution.analysis.spring.binding;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Closed M4C.3 reason catalog, additive to upstream historical gaps. */
public final class BindingProcessing {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.injection-binding", "m4c.3");
    public static final VersionedIdentifier SEMANTICS = new VersionedIdentifier("spring.binding-semantics", "6.2.0-m4c.3-v1");
    public static final VersionedIdentifier GAPS = new VersionedIdentifier("evidence.spring-binding-gaps", "m4c.3-v1");
    public enum Reason {
        VERSION_FRAGMENT_NOT_VALIDATED, CONTEXT_MISMATCH, SOURCE_EVIDENCE_MISMATCH,
        REGISTRATION_INCOMPLETE, PRIOR_CONTAINER_ERROR, DESCRIPTOR_INVENTORY_OPEN,
        DESCRIPTOR_INCOMPLETE, CUSTOM_RESOLVER, VALUE_EXPRESSION, CONSTRUCTOR_SELECTION_REQUIRED,
        GENERATED_MEMBER_REQUIRED, RESOURCE_NAMESPACE_UNSUPPORTED, JNDI_LOOKUP_UNSUPPORTED,
        HIERARCHY_UNSUPPORTED, RESOLVABLE_DEPENDENCIES_UNMODELED, POST_REGISTRATION_MUTATION,
        TYPE_EVIDENCE_MISSING, MATCH_EVIDENCE_MISSING, MATCH_OPERATION_ERROR, FLAGS_UNKNOWN,
        METADATA_CONFLICT, FACTORY_OR_PROXY_UNSUPPORTED, DEPENDENCY_NAME_UNKNOWN,
        PRIORITY_UNKNOWN, COMPARATOR_UNKNOWN, AGGREGATE_ORDER_UNKNOWN,
        DEFERRED_RUNTIME_TARGET, UNSUPPORTED_SHAPE, BINDING_LIMIT, OPTIONAL_GROUP_UNKNOWN,
        MISSING_REQUIRED_DEPENDENCY, NON_UNIQUE_DEPENDENCY, PRIMARY_CONFLICT,
        PRIORITY_CONFLICT, NAMED_TYPE_MISMATCH, INVALID_ALIAS_STATE, ALIAS_SHADOW_SHORTCUT_UNSUPPORTED
    }
    public record Issue(Reason reason, Optional<ContentDigest> dependency, String subject, List<ConditionEvidence> evidence) {
        public Issue {
            Objects.requireNonNull(reason); Objects.requireNonNull(dependency); subject = BindingIdentity.text(subject);
            evidence = evidence.stream().distinct().sorted(Comparator.comparing(ConditionEvidence::identity)).toList();
        }
        public ContentDigest identity() { return BindingIdentity.digest(this); }
    }
    private BindingProcessing() {}
    static List<CapabilityGapRecord> gaps(ContentDigest input, SpringBuildContext build, Collection<Issue> issues) {
        return issues.stream().map(issue -> {
            var observation = ProviderObservationReference.create(PROVIDER, "spring.injection-binding.issue", input, issue.identity());
            var subject = EvidenceSubject.observation(observation.identity());
            var kind = switch (issue.reason()) {
                case CONTEXT_MISMATCH, TYPE_EVIDENCE_MISSING -> EvidenceRequirement.Kind.EXACT_CLASSPATH;
                case SOURCE_EVIDENCE_MISMATCH, DESCRIPTOR_INCOMPLETE, DESCRIPTOR_INVENTORY_OPEN -> EvidenceRequirement.Kind.REPOSITORY_CONTENT;
                case VERSION_FRAGMENT_NOT_VALIDATED, MATCH_EVIDENCE_MISSING, RESOURCE_NAMESPACE_UNSUPPORTED -> EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT;
                case GENERATED_MEMBER_REQUIRED -> EvidenceRequirement.Kind.GENERATED_SOURCE;
                case CUSTOM_RESOLVER, POST_REGISTRATION_MUTATION, FACTORY_OR_PROXY_UNSUPPORTED, DEFERRED_RUNTIME_TARGET,
                        RESOLVABLE_DEPENDENCIES_UNMODELED, JNDI_LOOKUP_UNSUPPORTED -> EvidenceRequirement.Kind.RUNTIME_OBSERVATION;
                default -> EvidenceRequirement.Kind.CONFIGURATION;
            };
            var requirement = new EvidenceRequirement(kind,
                    "spring.binding." + issue.reason().name().toLowerCase(Locale.ROOT).replace('_', '-'), List.of(subject),
                    kind == EvidenceRequirement.Kind.RUNTIME_OBSERVATION ? EvidenceRequirement.AuthorizationClass.RUNTIME_ACCESS
                            : EvidenceRequirement.AuthorizationClass.PASSIVE,
                    List.of("Supply exact evidence for this dependency-resolution obligation; a provider recommendation is not execution permission."));
            return CapabilityGapRecord.create(GAPS, EvidenceContext.forSnapshot(build.snapshotIdentity()), PROVIDER,
                    "spring.injection-binding", issue.reason().name(), subject,
                    issue.evidence().stream().flatMap(e -> e.spans().stream()).distinct().sorted().toList(),
                    List.of(observation), List.of(requirement), List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.injection-binding")),
                    List.of(), List.of(), List.of("One realized registration result; definition selection does not establish successful instantiation or universal truth."));
        }).sorted().toList();
    }
}
