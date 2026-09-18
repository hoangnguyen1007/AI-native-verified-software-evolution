package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.spring.binding.InjectionPoint;
import com.evolution.analysis.spring.registration.BeanDefinitionCandidate;
import java.util.*;

/** A storage-neutral conditional fact identity. Runtime objects are never fact endpoints. */
public record ConditionalFactKey(Kind kind,
                                 Optional<BeanDefinitionCandidate.Identity> definition,
                                 Optional<InjectionPoint.Identity> injectionPoint,
                                 Optional<BeanDefinitionCandidate.Identity> bindingOwner,
                                 Optional<BeanDefinitionCandidate.Identity> target,
                                 VersionedIdentifier factSemanticsVersion)
        implements Comparable<ConditionalFactKey> {
    public static final VersionedIdentifier SEMANTICS =
            new VersionedIdentifier("spring.conditional-fact", "m4d-v1");

    public enum Kind { DEFINITION_PRESENT, INJECTION_CANDIDATE, SELECTED_BINDING }

    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = TruthIdentity.require(value, "spring-fact-key"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }

    public ConditionalFactKey {
        Objects.requireNonNull(kind); Objects.requireNonNull(definition);
        Objects.requireNonNull(injectionPoint); Objects.requireNonNull(bindingOwner); Objects.requireNonNull(target);
        Objects.requireNonNull(factSemanticsVersion);
        boolean valid = switch (kind) {
            case DEFINITION_PRESENT -> definition.isPresent() && injectionPoint.isEmpty() && bindingOwner.isEmpty() && target.isEmpty();
            case INJECTION_CANDIDATE, SELECTED_BINDING -> definition.isEmpty() && injectionPoint.isPresent() && target.isPresent();
        };
        if (!valid) throw new IllegalArgumentException("Fact endpoints do not match the fact kind");
    }

    public static ConditionalFactKey definitionPresent(BeanDefinitionCandidate.Identity definition) {
        return new ConditionalFactKey(Kind.DEFINITION_PRESENT, Optional.of(definition), Optional.empty(),
                Optional.empty(), Optional.empty(), SEMANTICS);
    }

    public static ConditionalFactKey selectedBinding(InjectionPoint.Identity point,
                                                      BeanDefinitionCandidate.Identity target) {
        return new ConditionalFactKey(Kind.SELECTED_BINDING, Optional.empty(), Optional.of(point),
                Optional.empty(), Optional.of(target), SEMANTICS);
    }

    public static ConditionalFactKey selectedBinding(InjectionPoint.Identity point,
                                                      Optional<BeanDefinitionCandidate.Identity> owner,
                                                      BeanDefinitionCandidate.Identity target) {
        return new ConditionalFactKey(Kind.SELECTED_BINDING, Optional.empty(), Optional.of(point),
                owner, Optional.of(target), SEMANTICS);
    }

    public static ConditionalFactKey injectionCandidate(InjectionPoint.Identity point,
                                                        Optional<BeanDefinitionCandidate.Identity> owner,
                                                        BeanDefinitionCandidate.Identity target) {
        return new ConditionalFactKey(Kind.INJECTION_CANDIDATE, Optional.empty(), Optional.of(point),
                owner, Optional.of(target), SEMANTICS);
    }

    public Identity identity() {
        return new Identity(TruthIdentity.derive("spring-fact-key", canonicalForm()));
    }

    public Map<String, Object> canonicalForm() {
        Object source = switch (kind) {
            case DEFINITION_PRESENT -> Map.of("type", "bean-definition-candidate", "identity", definition.orElseThrow());
            case INJECTION_CANDIDATE, SELECTED_BINDING -> bindingOwner.<Object>map(owner -> Map.of(
                    "type", "bean-definition-candidate", "identity", owner))
                    .orElseGet(() -> Map.of("type", "injection-point", "identity", injectionPoint.orElseThrow()));
        };
        List<Object> targets = switch (kind) {
            case DEFINITION_PRESENT -> List.of();
            case INJECTION_CANDIDATE, SELECTED_BINDING -> List.of(Map.of("type", "bean-definition-candidate", "identity", target.orElseThrow()));
        };
        return Map.of("kind", kind, "typedSource", source, "typedTargetOrCandidates", targets,
                "siteIdentity", injectionPoint, "factSemanticsVersion", factSemanticsVersion);
    }

    @Override public int compareTo(ConditionalFactKey other) {
        return identity().compareTo(other.identity());
    }
}
