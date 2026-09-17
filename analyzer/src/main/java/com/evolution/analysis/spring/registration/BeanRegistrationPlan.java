package com.evolution.analysis.spring.registration;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Normalized definition-reader invocations. Discovery order is NOT registration order.
 * A supplying adapter proves this schedule, reader decisions and complete condition attachments.
 * Alias calls are separate steps because readers differ in alias/definition call ordering. */
public record BeanRegistrationPlan(RegistrationPlan discoveryPlan, List<Step> steps,
                                   List<String> establishedOrderPrefix, RegistrationEvent.Completeness order,
                                   ConditionEvidence orderEvidence, RegistrationEvent.Completeness initialRegistry,
                                   RegistrationEvent.Completeness noParentContainer, ConditionEvidence closureEvidence,
                                   List<BeanRegistrationEvidence.Definition> definitions,
                                   List<BeanRegistrationEvidence.Query> queries, List<BeanRegistrationEvidence.QueryType> queryTypes, Limits limits) {
    public BeanRegistrationPlan(RegistrationPlan discoveryPlan, List<Step> steps, List<String> establishedOrderPrefix,
                                RegistrationEvent.Completeness order, ConditionEvidence orderEvidence,
                                RegistrationEvent.Completeness initialRegistry, RegistrationEvent.Completeness noParentContainer,
                                ConditionEvidence closureEvidence, List<BeanRegistrationEvidence.Definition> definitions,
                                List<BeanRegistrationEvidence.Query> queries, Limits limits) {
        this(discoveryPlan, steps, establishedOrderPrefix, order, orderEvidence, initialRegistry, noParentContainer,
                closureEvidence, definitions, queries, List.of(), limits);
    }
    public enum Operation { CONFIGURATION_GATE, REGISTER_DEFINITION, REGISTER_ALIAS, REMOVE_DEFINITION, OPAQUE_MUTATION }
    public enum GateExpectation { MATCH, NO_MATCH }
    public record Alias(String name, String target) {
        public Alias { name = RegistrationIdentity.text(name); target = RegistrationIdentity.text(target); }
    }
    /** gate is a prior configuration/definition step whose successful guard is required.
     * NO_MATCH removal requires an invoked CONFIGURATION_GATE with readerDecision TRUE;
     * a reader/discovery skip cannot substitute for an evaluated false condition.
     * readerDecision is the evidence-normalized reader's decision to perform this operation,
     * AFTER reader-specific deduplication/scan conflicts/overloaded-method rules. */
    public record Step(String key, RegistrationEvent.Identity discoveryEvent, Operation operation,
                       Optional<String> gate, LogicalValue readerDecision, Optional<Alias> alias,
                       ConditionEvidence evidence, GateExpectation gateExpectation) {
        public Step(String key, RegistrationEvent.Identity discoveryEvent, Operation operation, Optional<String> gate,
                    LogicalValue readerDecision, Optional<Alias> alias, ConditionEvidence evidence) {
            this(key, discoveryEvent, operation, gate, readerDecision, alias, evidence, GateExpectation.MATCH);
        }
        public Step {
            key = RegistrationIdentity.text(key); Objects.requireNonNull(discoveryEvent); Objects.requireNonNull(operation);
            Objects.requireNonNull(gate); gate = gate.map(RegistrationIdentity::text);
            Objects.requireNonNull(readerDecision); Objects.requireNonNull(alias); Objects.requireNonNull(evidence);
            Objects.requireNonNull(gateExpectation);
            if (gateExpectation == GateExpectation.NO_MATCH && (gate.isEmpty() || operation != Operation.REMOVE_DEFINITION))
                throw new IllegalArgumentException("Only evidenced definition removal supports a negative gate");
            if ((operation == Operation.REGISTER_ALIAS) != alias.isPresent()) throw new IllegalArgumentException("Alias operation/payload mismatch");
        }
        public ContentDigest identity() { return RegistrationIdentity.digest(this); }
    }
    public record Limits(int maxSteps, int maxStateCells, int maxMatchChecks, int maxEvidenceCells) {
        public Limits {
            if (maxSteps < 1 || maxStateCells < 1 || maxMatchChecks < 1 || maxEvidenceCells < 1)
                throw new IllegalArgumentException("Positive bean registration limits required");
        }
        public static Limits conservative() { return new Limits(10000, 100000, 100000, 100000); }
    }
    public BeanRegistrationPlan {
        Objects.requireNonNull(discoveryPlan); Objects.requireNonNull(order); Objects.requireNonNull(orderEvidence);
        Objects.requireNonNull(initialRegistry); Objects.requireNonNull(noParentContainer); Objects.requireNonNull(closureEvidence);
        Objects.requireNonNull(limits);
        steps = ContractChecks.sortedDistinct(steps, Comparator.comparing(Step::key), "registration steps");
        establishedOrderPrefix = ContractChecks.distinctInOrder(establishedOrderPrefix, "registration prefix");
        definitions = ContractChecks.sortedDistinct(definitions, Comparator.comparing(BeanRegistrationEvidence.Definition::candidate), "definition metadata");
        queries = ContractChecks.sortedDistinct(queries, Comparator.comparing(BeanRegistrationEvidence.Query::occurrence), "bean queries");
        queryTypes = ContractChecks.sortedDistinct(queryTypes, Comparator.comparing(BeanRegistrationEvidence.QueryType::key), "query type evidence");
        Map<String, Step> byKey = new HashMap<>(); steps.forEach(s -> byKey.put(s.key(), s));
        Set<RegistrationEvent.Identity> events = new HashSet<>(); discoveryPlan.events().forEach(e -> events.add(e.identity()));
        Set<BeanDefinitionCandidate.Identity> candidates = new HashSet<>();
        discoveryPlan.initialDefinitions().forEach(c -> candidates.add(c.identity()));
        discoveryPlan.events().forEach(e -> e.candidate().ifPresent(c -> candidates.add(c.identity())));
        Set<ConditionOccurrence.Identity> occurrences = new HashSet<>(); discoveryPlan.lowering().occurrences().forEach(c -> occurrences.add(c.identity()));
        if (!byKey.keySet().containsAll(establishedOrderPrefix)
                || order == RegistrationEvent.Completeness.COMPLETE && establishedOrderPrefix.size() != steps.size()
                || steps.stream().anyMatch(s -> !events.contains(s.discoveryEvent()) || s.gate().filter(g -> !byKey.containsKey(g) || g.equals(s.key())).isPresent())
                || definitions.stream().anyMatch(d -> !candidates.contains(d.candidate()))
                || queries.stream().anyMatch(q -> !occurrences.contains(q.occurrence())))
            throw new IllegalArgumentException("Foreign or inconsistent registration input reference");
        Set<String> processed = new HashSet<>();
        for (var key : establishedOrderPrefix) {
            if (byKey.get(key).gate().filter(g -> !processed.contains(g)).isPresent())
                throw new IllegalArgumentException("A gate must precede its dependent operation");
            processed.add(key);
        }
    }
    public RegistrationPlan.Identity identity() {
        return new RegistrationPlan.Identity(RegistrationIdentity.derive("spring-registration-plan", Map.of(
                "buildContextIdentity", discoveryPlan.buildContext().identity(), "frameworkSemantics", discoveryPlan.lowering().semantics(),
                "eventDescriptors", canonicalForm(), "precedenceConstraints", establishedOrderPrefix,
                "initialDefinitions", discoveryPlan.initialDefinitions(),
                "containerHierarchy", Map.of("container", discoveryPlan.container(), "noParent", noParentContainer, "evidence", closureEvidence),
                "orderCompleteness", order, "overridePolicy", discoveryPlan.overridePolicy())));
    }
    public Object canonicalForm() {
        return Map.ofEntries(Map.entry("schema", "spring-bean-registration-plan-v1"),
                Map.entry("discoveryPlan", discoveryPlan.identity()), Map.entry("steps", steps),
                Map.entry("establishedOrderPrefix", establishedOrderPrefix), Map.entry("order", order),
                Map.entry("orderEvidence", orderEvidence), Map.entry("initialRegistry", initialRegistry),
                Map.entry("noParentContainer", noParentContainer), Map.entry("closureEvidence", closureEvidence),
                Map.entry("definitions", definitions), Map.entry("queries", queries), Map.entry("queryTypes", queryTypes), Map.entry("limits", limits));
    }
}
