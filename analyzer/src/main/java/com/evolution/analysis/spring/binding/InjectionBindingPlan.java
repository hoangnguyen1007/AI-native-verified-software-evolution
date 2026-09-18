package com.evolution.analysis.spring.binding;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.frontend.JavaType;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;

/** Normalized descriptor acquisition boundary following the M4C.2 registration boundary. */
public record InjectionBindingPlan(BeanRegistrationPlan registrationPlan, List<Dependency> dependencies,
                                   List<BindingEvidence.Definition> definitions, List<BindingEvidence.Match> matches,
                                   List<Group> groups, List<ObligationBinding> obligations, Environment environment, Limits limits) {
    public InjectionBindingPlan(BeanRegistrationPlan registrationPlan, List<Dependency> dependencies,
                                List<BindingEvidence.Definition> definitions, List<BindingEvidence.Match> matches,
                                List<Group> groups, Environment environment, Limits limits) {
        this(registrationPlan, dependencies, definitions, matches, groups, List.of(), environment, limits);
    }
    /** Explicit M4A-to-descriptor provenance. Missing mappings remain normalization gaps. */
    public record ObligationBinding(ContentDigest obligation, List<ContentDigest> dependencies, ConditionEvidence evidence) {
        public ObligationBinding {
            Objects.requireNonNull(obligation); Objects.requireNonNull(evidence);
            dependencies = ContractChecks.sortedDistinct(dependencies, Comparator.naturalOrder(), "obligation descriptors");
            if (dependencies.isEmpty()) throw new IllegalArgumentException("An injection mapping needs descriptors");
        }
    }
    public enum Shape { SINGLE, OPTIONAL, ARRAY, LIST, COLLECTION, SET, STRING_MAP, CUSTOM_COLLECTION, CUSTOM_MAP,
                        OBJECT_PROVIDER, OBJECT_FACTORY, JAKARTA_PROVIDER, LAZY, UNSUPPORTED }
    public enum Mode { AUTOWIRE, RESOURCE, EXPLICIT_REFERENCE }
    public enum Required { REQUIRED, OPTIONAL }
    public enum NameStatus { PRESENT, ABSENT, UNKNOWN }
    public record Name(NameStatus status, Optional<String> value) {
        public Name {
            Objects.requireNonNull(status); Objects.requireNonNull(value); value = value.map(BindingIdentity::text);
            if ((status == NameStatus.PRESENT) != value.isPresent()) throw new IllegalArgumentException("Invalid dependency name evidence");
        }
        public static Name absent() { return new Name(NameStatus.ABSENT, Optional.empty()); }
        public static Name of(String name) { return new Name(NameStatus.PRESENT, Optional.of(name)); }
        public static Name unknown() { return new Name(NameStatus.UNKNOWN, Optional.empty()); }
    }
    public enum Normalization { COMPLETE, INCOMPLETE, CUSTOM_RESOLVER, VALUE_EXPRESSION, CONSTRUCTOR_SELECTION,
                                GENERATED_MEMBER, RESOURCE_NAMESPACE, JNDI_LOOKUP }
    public record Dependency(InjectionPoint point, Optional<BeanDefinitionCandidate.Identity> owner,
                             JavaType requestedType, Shape shape, Mode mode, Required required,
                             Name dependencyName, Name suggestedName, LogicalValue hasQualifier,
                             boolean standardLookup, boolean resourceDefaultName, boolean resourceTypeFallback,
                             boolean emptyAggregateFallback, LogicalValue elementIndicatesMultiple,
                             Normalization normalization, ConditionEvidence evidence) {
        public Dependency {
            Objects.requireNonNull(point); Objects.requireNonNull(owner); Objects.requireNonNull(requestedType);
            Objects.requireNonNull(shape); Objects.requireNonNull(mode); Objects.requireNonNull(required);
            Objects.requireNonNull(dependencyName); Objects.requireNonNull(suggestedName); Objects.requireNonNull(hasQualifier);
            Objects.requireNonNull(elementIndicatesMultiple);
            Objects.requireNonNull(normalization); Objects.requireNonNull(evidence);
            if (mode != Mode.AUTOWIRE && dependencyName.status() == NameStatus.ABSENT)
                throw new IllegalArgumentException("Name-based request requires a name or explicit unknown");
            if (mode != Mode.RESOURCE && (resourceDefaultName || resourceTypeFallback))
                throw new IllegalArgumentException("Resource policy only applies to Resource");
        }
        public ContentDigest identity() { return BindingIdentity.digest(this); }
        public boolean aggregate() { return switch (shape) {
            case ARRAY, LIST, COLLECTION, SET, STRING_MAP, CUSTOM_COLLECTION, CUSTOM_MAP -> true;
            default -> false;
        }; }
        public boolean deferred() { return switch (shape) {
            case OBJECT_PROVIDER, OBJECT_FACTORY, JAKARTA_PROVIDER, LAZY -> true;
            default -> false;
        }; }
    }
    /** All dependency parameters of one optional autowired method. Missing one skips the
     * invocation, so none of the tentative selections become dependency edges. */
    public record Group(String key, List<ContentDigest> dependencies, List<ContentDigest> skipOnAbsent, ConditionEvidence evidence) {
        public Group {
            key = BindingIdentity.text(key);
            dependencies = ContractChecks.distinctInOrder(dependencies, "optional method parameters");
            skipOnAbsent = ContractChecks.sortedDistinct(skipOnAbsent, Comparator.naturalOrder(), "method skip triggers");
            if (dependencies.isEmpty()) throw new IllegalArgumentException("Empty injection group");
            if (!dependencies.containsAll(skipOnAbsent)) throw new IllegalArgumentException("Foreign method skip trigger");
            Objects.requireNonNull(evidence);
        }
    }
    public enum ComparatorPolicy { NONE, SPRING_ORDER, UNKNOWN }
    /** These are explicit closure proofs, not analyzer defaults. Registration closure does
     * not prove absence of custom resolvers, resolvable dependencies or later mutations. */
    public record Environment(RegistrationEvent.Completeness descriptorsComplete,
                              RegistrationEvent.Completeness noResolvableDependencies,
                              RegistrationEvent.Completeness noPostRegistrationMutation,
                              ComparatorPolicy comparator, List<String> candidateOrder,
                              RegistrationEvent.Completeness candidateOrderComplete, ConditionEvidence evidence) {
        public Environment {
            Objects.requireNonNull(descriptorsComplete); Objects.requireNonNull(noResolvableDependencies);
            Objects.requireNonNull(noPostRegistrationMutation); Objects.requireNonNull(comparator);
            candidateOrder = ContractChecks.distinctInOrder(candidateOrder.stream().map(BindingIdentity::text).toList(), "candidate enumeration order");
            Objects.requireNonNull(candidateOrderComplete); Objects.requireNonNull(evidence);
        }
    }
    public record Limits(int maxDependencies, int maxCandidateChecks, int maxTraceRows, int maxEvidenceCells) {
        public Limits {
            if (maxDependencies < 1 || maxCandidateChecks < 1 || maxTraceRows < 1 || maxEvidenceCells < 1)
                throw new IllegalArgumentException("Positive binding limits required");
        }
        public static Limits conservative() { return new Limits(10000, 100000, 100000, 200000); }
    }
    public InjectionBindingPlan {
        Objects.requireNonNull(registrationPlan); Objects.requireNonNull(environment); Objects.requireNonNull(limits);
        dependencies = ContractChecks.sortedDistinct(dependencies, Comparator.comparing(Dependency::identity), "injection requests");
        definitions = ContractChecks.sortedDistinct(definitions, Comparator.comparing(BindingEvidence.Definition::candidate), "binding definitions");
        matches = ContractChecks.sortedDistinct(matches, Comparator.comparing(BindingEvidence.Match::key), "binding matches");
        groups = ContractChecks.sortedDistinct(groups, Comparator.comparing(Group::key), "injection groups");
        obligations = ContractChecks.sortedDistinct(obligations, Comparator.comparing(ObligationBinding::obligation), "injection obligation mappings");
        Set<ContentDigest> ids = new HashSet<>(); dependencies.forEach(d -> ids.add(d.identity()));
        Set<ContentDigest> injectionObligations = new HashSet<>();
        registrationPlan.discoveryPlan().inventory().obligations().stream().filter(o -> o.primaryMechanism().startsWith("spring.injection."))
                .forEach(o -> injectionObligations.add(o.identity()));
        if (obligations.stream().anyMatch(o -> !injectionObligations.contains(o.obligation()) || !ids.containsAll(o.dependencies())))
            throw new IllegalArgumentException("Foreign injection obligation mapping");
        Set<String> sites = new HashSet<>();
        for (var d : dependencies) if (!sites.add(d.point().identity().value() + ":" + d.owner().map(BeanDefinitionCandidate.Identity::value).orElse("external")))
            throw new IllegalArgumentException("Conflicting descriptors for one injection site and owner");
        Set<BeanDefinitionCandidate.Identity> candidates = new HashSet<>();
        registrationPlan.discoveryPlan().initialDefinitions().forEach(c -> candidates.add(c.identity()));
        registrationPlan.discoveryPlan().events().forEach(e -> e.candidate().ifPresent(c -> candidates.add(c.identity())));
        if (dependencies.stream().anyMatch(d -> d.owner().filter(c -> !candidates.contains(c)).isPresent())
                || definitions.stream().anyMatch(d -> !candidates.contains(d.candidate()) || d.factoryOwner().filter(c -> !candidates.contains(c)).isPresent())
                || matches.stream().anyMatch(m -> !ids.contains(m.dependency()) || !candidates.contains(m.candidate())))
            throw new IllegalArgumentException("Foreign binding input reference");
        Set<ContentDigest> grouped = new HashSet<>();
        for (var group : groups) for (var id : group.dependencies()) {
            if (!ids.contains(id) || !grouped.add(id)) throw new IllegalArgumentException("Foreign or multiply grouped dependency");
        }
    }
    public ContentDigest identity() { return BindingIdentity.digest(canonicalForm()); }
    public Object canonicalForm() { return Map.of("schema", "spring-injection-binding-plan-v1",
            "registrationPlan", registrationPlan.identity(), "dependencies", dependencies, "definitions", definitions,
            "matches", matches, "groups", groups, "obligations", obligations, "environment", environment, "limits", limits); }
}
