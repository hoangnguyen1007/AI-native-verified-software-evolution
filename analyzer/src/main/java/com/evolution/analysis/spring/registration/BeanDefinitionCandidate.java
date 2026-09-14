package com.evolution.analysis.spring.registration;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.EntityIdentity;
import java.util.*;

/** Candidate metadata is not evidence of registration, activation, selection or instantiation. */
public record BeanDefinitionCandidate(BeanProducer producer, String definitionSlot, Names declaredNameKey,
                                      List<EntityIdentity> exposedTypes) {
    public enum NameStatus { EXACT, UNRESOLVED }
    public record Names(NameStatus status, Optional<String> primary, List<String> aliases) {
        public Names {
            Objects.requireNonNull(status); Objects.requireNonNull(primary);
            primary = primary.map(RegistrationIdentity::text);
            aliases = ContractChecks.sortedDistinct(aliases.stream().map(RegistrationIdentity::text).toList(),
                    Comparator.naturalOrder(), "bean aliases");
            if ((status == NameStatus.EXACT) != primary.isPresent() || status == NameStatus.UNRESOLVED && !aliases.isEmpty()
                    || primary.filter(aliases::contains).isPresent()) throw new IllegalArgumentException("Invalid candidate names");
        }
        public static Names exact(String name) { return new Names(NameStatus.EXACT, Optional.of(name), List.of()); }
        public static Names unresolved() { return new Names(NameStatus.UNRESOLVED, Optional.empty(), List.of()); }
    }
    public BeanDefinitionCandidate {
        Objects.requireNonNull(producer); definitionSlot = RegistrationIdentity.text(definitionSlot);
        Objects.requireNonNull(declaredNameKey);
        exposedTypes = ContractChecks.sortedDistinct(exposedTypes, Comparator.naturalOrder(), "exposed candidate types");
    }
    public Identity identity() {
        return new Identity(RegistrationIdentity.derive("spring-bean-candidate", Map.of(
                "producerIdentity", producer.identity(), "containerKey", producer.containerKey(),
                "definitionSlot", definitionSlot, "declaredNameKey", declaredNameKey)));
    }
    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = RegistrationIdentity.require(value, "spring-bean-candidate"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }
}
