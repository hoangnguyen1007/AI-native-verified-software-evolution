package com.evolution.analysis.spring.registration;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** A potential definition source, independent of configuration assignment and evaluation results. */
public record BeanProducer(SpringBuildContext.Identity buildContextIdentity, String containerKey,
                           ConditionEvidence declarationEvidenceKey, Kind producerKind,
                           String declarationSlot, String discoveryPathKey) {
    public enum Kind { CONFIGURATION_CLASS, COMPONENT_CLASS, BEAN_METHOD, XML_DEFINITION, SUPPLIED_DEFINITION }
    public BeanProducer {
        Objects.requireNonNull(buildContextIdentity); Objects.requireNonNull(declarationEvidenceKey);
        Objects.requireNonNull(producerKind); containerKey = RegistrationIdentity.text(containerKey);
        declarationSlot = RegistrationIdentity.text(declarationSlot); discoveryPathKey = RegistrationIdentity.text(discoveryPathKey);
    }
    public Identity identity() {
        return new Identity(RegistrationIdentity.derive("spring-producer", Map.of(
                "buildContextIdentity", buildContextIdentity, "containerKey", containerKey,
                "declarationEvidenceKey", declarationEvidenceKey, "producerKind", producerKind,
                "declarationSlot", declarationSlot, "discoveryPathKey", discoveryPathKey)));
    }
    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = RegistrationIdentity.require(value, "spring-producer"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }
}
