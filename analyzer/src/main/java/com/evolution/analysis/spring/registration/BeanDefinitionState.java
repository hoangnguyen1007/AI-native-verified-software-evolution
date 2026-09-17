package com.evolution.analysis.spring.registration;

import com.evolution.analysis.contract.common.*;
import java.util.*;

/** Registry snapshot at a particular evidenced prefix. Never a claim about instantiation. */
public record BeanDefinitionState(DiscoveryTransitions.ContextIdentity semanticsContextIdentity,
                                  String containerKey, List<String> processedSteps,
                                  Map<String, BeanDefinitionCandidate.Identity> definitions,
                                  Map<String, String> aliases, boolean registryClosed, boolean executionEstablished) {
    public BeanDefinitionState {
        Objects.requireNonNull(semanticsContextIdentity); containerKey = RegistrationIdentity.text(containerKey);
        processedSteps = ContractChecks.distinctInOrder(processedSteps.stream().map(RegistrationIdentity::text).toList(), "processed registration steps");
        definitions.forEach((name, candidate) -> { RegistrationIdentity.text(name); Objects.requireNonNull(candidate); });
        aliases.forEach((name, target) -> { RegistrationIdentity.text(name); RegistrationIdentity.text(target); });
        definitions = Collections.unmodifiableMap(new TreeMap<>(definitions));
        aliases = Collections.unmodifiableMap(new TreeMap<>(aliases));
    }
    public DiscoveryTransitions.StateIdentity identity() {
        return new DiscoveryTransitions.StateIdentity(RegistrationIdentity.derive("spring-definition-state", Map.of(
                "semanticsContextIdentity", semanticsContextIdentity, "containerKey", containerKey,
                "processedEventPrefix", processedSteps, "definitions", definitions, "aliases", aliases,
                "completeness", Map.of("registryClosed", registryClosed, "executionEstablished", executionEstablished))));
    }
}
