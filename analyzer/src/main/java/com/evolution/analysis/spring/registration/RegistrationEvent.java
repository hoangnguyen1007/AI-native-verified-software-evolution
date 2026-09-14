package com.evolution.analysis.spring.registration;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** One evidenced invocation site. Phase labels never imply a global phase sort. */
public record RegistrationEvent(SpringBuildContext.Identity buildContextIdentity,
                                ConditionExpression.Semantics frameworkSemantics,
                                ContentDigest triggerOccurrenceIdentity, List<String> parentInvocationPath,
                                Phase phase, String eventSlot, String containerKey, Kind kind, ConditionSite conditionSite,
                                Optional<Identity> parent, Optional<BeanDefinitionCandidate> candidate,
                                List<ConditionUse> conditions, Completeness membership,
                                Completeness conditionMetadata, List<ContentDigest> obligations,
                                ConditionEvidence evidence) {
    public enum Phase { CONFIGURATION_PARSE, COMPONENT_SCAN, DEFERRED_IMPORT_SELECTION, DEFINITION_READING, REGISTRY_CALLBACK }
    public enum Kind {
        CONFIGURATION, COMPONENT, DIRECT_IMPORT, DEFERRED_IMPORT, AUTO_CONFIGURATION,
        BEAN_METHOD, COMPONENT_SCAN, IMPORT_SELECTOR, IMPORT_REGISTRAR,
        REGISTRY_POST_PROCESSOR, ENVIRONMENT_MUTATION, XML_READER
    }
    public enum Completeness { COMPLETE, UNKNOWN }
    public enum ConditionSite { PARSE_CONFIGURATION, REGISTER_BEAN, DISCOVERY_ONLY }
    public enum RequiredPhase { ORDINARY, PARSE_CONFIGURATION, REGISTER_BEAN, UNKNOWN }
    /** Condition order is the supplied invocation order; it is never canonicalized as a set. */
    public record ConditionUse(ConditionOccurrence.Identity occurrence, RequiredPhase requiredPhase,
                               ConditionEvidence phaseEvidence) {
        public ConditionUse { Objects.requireNonNull(occurrence); Objects.requireNonNull(requiredPhase); Objects.requireNonNull(phaseEvidence); }
    }
    public RegistrationEvent {
        Objects.requireNonNull(buildContextIdentity); Objects.requireNonNull(frameworkSemantics);
        Objects.requireNonNull(triggerOccurrenceIdentity); Objects.requireNonNull(phase); Objects.requireNonNull(kind); Objects.requireNonNull(conditionSite);
        parentInvocationPath = parentInvocationPath.stream().map(RegistrationIdentity::text).toList();
        eventSlot = RegistrationIdentity.text(eventSlot); containerKey = RegistrationIdentity.text(containerKey);
        Objects.requireNonNull(parent); Objects.requireNonNull(candidate); conditions = List.copyOf(conditions);
        Objects.requireNonNull(membership); Objects.requireNonNull(conditionMetadata); Objects.requireNonNull(evidence);
        obligations = ContractChecks.sortedDistinct(obligations, Comparator.naturalOrder(), "event obligations");
        if (candidate.isPresent() && (!candidate.orElseThrow().producer().buildContextIdentity().equals(buildContextIdentity)
                || !candidate.orElseThrow().producer().containerKey().equals(containerKey)))
            throw new IllegalArgumentException("Candidate/event context mismatch");
        Phase expected = switch (kind) {
            case CONFIGURATION, DIRECT_IMPORT, IMPORT_SELECTOR, ENVIRONMENT_MUTATION, BEAN_METHOD -> Phase.CONFIGURATION_PARSE;
            case COMPONENT, COMPONENT_SCAN -> Phase.COMPONENT_SCAN;
            case DEFERRED_IMPORT, AUTO_CONFIGURATION -> Phase.DEFERRED_IMPORT_SELECTION;
            case IMPORT_REGISTRAR, XML_READER -> Phase.DEFINITION_READING;
            case REGISTRY_POST_PROCESSOR -> Phase.REGISTRY_CALLBACK;
        };
        if (phase != expected) throw new IllegalArgumentException("Event kind/phase mismatch");
        if (kind == Kind.BEAN_METHOD && conditionSite != ConditionSite.DISCOVERY_ONLY)
            throw new IllegalArgumentException("Bean method conditions belong to later registration, not discovery");
        if ((kind == Kind.CONFIGURATION || kind == Kind.DIRECT_IMPORT || kind == Kind.DEFERRED_IMPORT || kind == Kind.AUTO_CONFIGURATION)
                && conditionSite != ConditionSite.PARSE_CONFIGURATION)
            throw new IllegalArgumentException("Configuration discovery requires the parse condition site");
        if (kind == Kind.BEAN_METHOD && candidate.filter(c -> c.producer().producerKind() == BeanProducer.Kind.BEAN_METHOD).isEmpty())
            throw new IllegalArgumentException("Bean method discovery needs its producer candidate");
    }
    public Identity identity() {
        return new Identity(RegistrationIdentity.derive("spring-registration-event", Map.of(
                "buildContextIdentity", buildContextIdentity, "triggerOccurrenceIdentity", triggerOccurrenceIdentity,
                "parentInvocationPath", parentInvocationPath, "phase", phase, "eventSlot", eventSlot,
                "frameworkSemantics", frameworkSemantics)));
    }
    public List<String> invocationPath() {
        var result = new ArrayList<>(parentInvocationPath); result.add(eventSlot); return List.copyOf(result);
    }
    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = RegistrationIdentity.require(value, "spring-registration-event"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }
}
