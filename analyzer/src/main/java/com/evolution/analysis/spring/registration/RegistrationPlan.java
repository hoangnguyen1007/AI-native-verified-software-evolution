package com.evolution.analysis.spring.registration;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.spring.*;
import com.evolution.analysis.spring.condition.*;
import java.util.*;
import static com.evolution.analysis.spring.registration.RegistrationProcessing.Reason.*;

/** Evidence-normalized discovery plan. The supplying provider proves scan/import membership;
 * annotation names alone cannot construct that proof. No target code is executed. */
public final class RegistrationPlan {
    public enum OrderStatus { COMPLETE, MISSING_EVIDENCE, CYCLIC, LIMIT_EXCEEDED }
    public enum OrderDomain { EXPLICIT_SEQUENCE, NESTED_DISCOVERY, DEFERRED_GROUP, AUTO_CONFIGURATION, DEFINITION_READER, REGISTRY_PROCESSOR }
    public enum OverridePolicy { ALLOW, FORBID, UNKNOWN }
    public enum ObligationStatus { PLANNED, CONDITION_RETAINED, NOT_DISCOVERY, GAP }
    public record Limits(int maxEvents, int maxEdges, int maxNestingDepth, int maxTransitions, int maxStateCells) {
        public Limits(int events, int edges, int depth, int transitions) { this(events, edges, depth, transitions, 100000); }
        public Limits {
            if (maxEvents < 1 || maxEdges < 1 || maxNestingDepth < 1 || maxTransitions < 1 || maxStateCells < 1)
                throw new IllegalArgumentException("Positive registration limits required");
        }
        public static Limits conservative() { return new Limits(10000, 50000, 256, 10000); }
    }
    public record Container(String key, ConditionEvidence bootstrapEvidence) {
        public Container { key = RegistrationIdentity.text(key); Objects.requireNonNull(bootstrapEvidence); }
    }
    public record Precedence(RegistrationEvent.Identity before, RegistrationEvent.Identity after,
                             OrderDomain domain, ConditionEvidence evidence) {
        public Precedence { Objects.requireNonNull(before); Objects.requireNonNull(after); Objects.requireNonNull(domain); Objects.requireNonNull(evidence); }
        public ContentDigest identity() { return RegistrationIdentity.digest(this); }
    }
    public record ObligationRow(ContentDigest obligation, ObligationStatus status, List<RegistrationEvent.Identity> events) {
        public ObligationRow {
            Objects.requireNonNull(obligation); Objects.requireNonNull(status);
            events = ContractChecks.sortedDistinct(events, Comparator.naturalOrder(), "obligation event references");
            if ((status == ObligationStatus.PLANNED) != !events.isEmpty()) throw new IllegalArgumentException("Obligation coverage mismatch");
        }
    }
    public record Coverage(int inputObligations, int planned, int conditions, int notDiscovery, int gaps) {
        public Coverage {
            if (inputObligations < 0 || planned < 0 || conditions < 0 || notDiscovery < 0 || gaps < 0
                    || (long) planned + conditions + notDiscovery + gaps != inputObligations)
                throw new IllegalArgumentException("Registration obligation denominator must close");
        }
    }
    private final SpringBuildContext build;
    private final SpringMechanismInventory inventory;
    private final ConditionEvidenceLowering.Result lowering;
    private final List<RegistrationEvent> events;
    private final List<Precedence> precedence;
    private final Container container;
    private final List<BeanDefinitionCandidate> initialDefinitions;
    private final OverridePolicy overridePolicy;
    private final Limits limits;
    private final List<ObligationRow> rows;
    private final List<RegistrationProcessing.Issue> issues;
    private final List<CapabilityGapRecord> gaps;
    private final List<RegistrationEvent.Identity> establishedOrderPrefix;
    private final OrderStatus orderStatus;
    private final Identity identity;

    private RegistrationPlan(SpringBuildContext build, SpringMechanismInventory inventory, ConditionEvidenceLowering.Result lowering,
                             List<RegistrationEvent> events, List<Precedence> precedence, Container container,
                             List<BeanDefinitionCandidate> initialDefinitions, OverridePolicy overridePolicy, Limits limits) {
        this.build = Objects.requireNonNull(build); this.inventory = Objects.requireNonNull(inventory);
        this.lowering = Objects.requireNonNull(lowering); this.container = Objects.requireNonNull(container);
        this.overridePolicy = Objects.requireNonNull(overridePolicy); this.limits = Objects.requireNonNull(limits);
        this.events = ContractChecks.sortedDistinct(events, Comparator.comparing(RegistrationEvent::identity), "registration events");
        this.initialDefinitions = ContractChecks.sortedDistinct(initialDefinitions, Comparator.comparing(BeanDefinitionCandidate::identity), "initial definitions");
        List<RegistrationProcessing.Issue> problems = new ArrayList<>();
        var byId = new HashMap<RegistrationEvent.Identity, RegistrationEvent>(); this.events.forEach(e -> byId.put(e.identity(), e));
        boolean contextMatches = build.analysisIdentity().equals(inventory.analysis()) && build.containsFrameworkEvidence(inventory.frameworkEvidence())
                && lowering.inventoryIdentity().equals(inventory.identity())
                && lowering.issues().stream().noneMatch(i -> i.reason() == ConditionProcessing.Reason.BUILD_CONTEXT_MISMATCH);
        if (!contextMatches) issue(problems, BUILD_CONTEXT_MISMATCH, "plan-inputs", null, container.bootstrapEvidence());
        if (!inventory.frameworkEvidence().acceptedConditionFragment(this.events.stream().anyMatch(e -> e.kind() == RegistrationEvent.Kind.AUTO_CONFIGURATION)))
            issue(problems, VERSION_FRAGMENT_NOT_VALIDATED, "framework", null, container.bootstrapEvidence());
        checkEvidence(problems, container.bootstrapEvidence(), null);
        Map<BeanDefinitionCandidate.Identity, BeanDefinitionCandidate> candidateTable = new HashMap<>();
        this.initialDefinitions.forEach(c -> {
            if (!c.producer().buildContextIdentity().equals(build.identity()) || !c.producer().containerKey().equals(container.key()))
                throw new IllegalArgumentException("Initial definition context mismatch");
            candidateTable.put(c.identity(), c); checkEvidence(problems, c.producer().declarationEvidenceKey(), null);
        });
        var knownConditions = new HashSet<ConditionOccurrence.Identity>(); lowering.occurrences().forEach(c -> knownConditions.add(c.identity()));
        var knownObligations = new HashSet<ContentDigest>(); inventory.obligations().forEach(o -> knownObligations.add(o.identity()));
        var obligationsById = new HashMap<ContentDigest, SpringMechanismInventory.SemanticObligation>(); inventory.obligations().forEach(o -> obligationsById.put(o.identity(), o));
        var loweringRows = new HashMap<ContentDigest, ConditionEvidenceLowering.Row>(); lowering.rows().forEach(r -> loweringRows.put(r.obligation(), r));
        var knownPhases = new HashMap<ConditionOccurrence.Identity, RegistrationEvent.RequiredPhase>();
        for (var lowered : lowering.rows()) lowered.occurrence().ifPresent(occurrence -> {
            var obligation = obligationsById.get(lowered.obligation());
            if (obligation == null) return;
            switch (obligation.primaryMechanism()) {
                case "spring.condition.profile", "spring.condition.property" -> knownPhases.put(occurrence.identity(), RegistrationEvent.RequiredPhase.ORDINARY);
                case "spring.condition.bean-state" -> knownPhases.put(occurrence.identity(), RegistrationEvent.RequiredPhase.REGISTER_BEAN);
                default -> { }
            }
        });
        var eventReferences = new HashMap<ContentDigest, List<RegistrationEvent.Identity>>();
        var attachedConditions = new HashSet<ConditionOccurrence.Identity>();
        List<Precedence> allEdges = new ArrayList<>(ContractChecks.sortedDistinct(precedence, Comparator.comparing(Precedence::identity), "precedence edges"));
        for (var event : this.events) {
            if (!event.buildContextIdentity().equals(build.identity()) || !event.frameworkSemantics().equals(lowering.semantics())
                    || !event.containerKey().equals(container.key()))
                issue(problems, BUILD_CONTEXT_MISMATCH, event.eventSlot(), event, event.evidence());
            checkEvidence(problems, event.evidence(), event);
            if (event.parentInvocationPath().size() > limits.maxNestingDepth()) issue(problems, DISCOVERY_LIMIT, "nesting", event, event.evidence());
            event.candidate().ifPresent(c -> {
                var previous = candidateTable.putIfAbsent(c.identity(), c);
                if (previous != null && !previous.equals(c)) throw new IllegalArgumentException("Conflicting metadata for the same candidate");
                checkEvidence(problems, c.producer().declarationEvidenceKey(), event);
            });
            for (var condition : event.conditions()) {
                attachedConditions.add(condition.occurrence());
                if (!knownConditions.contains(condition.occurrence())) issue(problems, CONDITION_REFERENCE_MISSING, condition.occurrence().value(), event, condition.phaseEvidence());
                var knownPhase = knownPhases.get(condition.occurrence());
                if (knownPhase != null && condition.requiredPhase() != RegistrationEvent.RequiredPhase.UNKNOWN && condition.requiredPhase() != knownPhase)
                    issue(problems, CONDITION_PHASE_CONFLICT, condition.occurrence().value(), event, condition.phaseEvidence());
                checkEvidence(problems, condition.phaseEvidence(), event);
            }
            event.obligations().forEach(o -> eventReferences.computeIfAbsent(o, ignored -> new ArrayList<>()).add(event.identity()));
            if (!knownObligations.containsAll(event.obligations())) throw new IllegalArgumentException("Event references a foreign inventory obligation");
            if (event.parent().isPresent()) {
                var parent = byId.get(event.parent().orElseThrow());
                if (parent == null || !parent.invocationPath().equals(event.parentInvocationPath()) || !parent.containerKey().equals(event.containerKey()))
                    issue(problems, PARENT_REFERENCE_MISSING, event.eventSlot(), event, event.evidence());
                else allEdges.add(new Precedence(parent.identity(), event.identity(), OrderDomain.NESTED_DISCOVERY, event.evidence()));
            } else if (!event.parentInvocationPath().isEmpty()) issue(problems, PARENT_REFERENCE_MISSING, event.eventSlot(), event, event.evidence());
        }
        this.precedence = allEdges.stream().distinct().sorted(Comparator.comparing(Precedence::identity)).toList();
        var evidencedEdges = new ArrayList<Precedence>();
        for (var edge : this.precedence) {
            var before = byId.get(edge.before()); var after = byId.get(edge.after());
            if (before == null || after == null) throw new IllegalArgumentException("Precedence references an absent event");
            checkEvidence(problems, edge.evidence(), after);
            boolean valid = switch (edge.domain()) {
                case AUTO_CONFIGURATION -> before.kind() == RegistrationEvent.Kind.AUTO_CONFIGURATION && after.kind() == RegistrationEvent.Kind.AUTO_CONFIGURATION
                        && before.parentInvocationPath().equals(after.parentInvocationPath());
                case DEFERRED_GROUP -> before.phase() == RegistrationEvent.Phase.DEFERRED_IMPORT_SELECTION && after.phase() == before.phase();
                case DEFINITION_READER -> before.phase() == RegistrationEvent.Phase.DEFINITION_READING && after.phase() == before.phase();
                case REGISTRY_PROCESSOR -> before.phase() == RegistrationEvent.Phase.REGISTRY_CALLBACK && after.phase() == before.phase();
                case NESTED_DISCOVERY -> after.parent().filter(before.identity()::equals).isPresent();
                case EXPLICIT_SEQUENCE -> true;
            };
            if (!valid) issue(problems, INVALID_ORDER_DOMAIN, edge.domain().name(), after, edge.evidence());
            if (valid && (!(edge.evidence() instanceof ConditionEvidence.Source source) || build.containsSource(source) && source.span().isPresent()))
                evidencedEdges.add(edge);
        }
        var raw = new HashMap<ContentDigest, SpringMechanismInventory.RawObservation>(); inventory.rawObservations().forEach(r -> raw.put(r.identity(), r));
        this.rows = inventory.obligations().stream().map(obligation -> {
            var references = eventReferences.getOrDefault(obligation.identity(), List.of());
            var condition = Optional.ofNullable(loweringRows.get(obligation.identity())).flatMap(ConditionEvidenceLowering.Row::occurrence);
            ObligationStatus status;
            if (!references.isEmpty()) status = ObligationStatus.PLANNED;
            else if (condition.isPresent() && obligation.primaryMechanism().startsWith("spring.condition.")) {
                status = attachedConditions.contains(condition.orElseThrow().identity()) ? ObligationStatus.CONDITION_RETAINED : ObligationStatus.GAP;
                if (status == ObligationStatus.GAP) issue(problems, CONDITION_ATTACHMENT_REQUIRED, obligation.identity().value(), null, condition.orElseThrow().declarationEvidenceKey());
            }
            else if (discoveryMechanism(obligation.primaryMechanism())) {
                status = ObligationStatus.GAP;
                var observation = raw.get(obligation.rawObservationIdentity());
                ConditionEvidence evidence = build.sourceDigest(observation.document()).<ConditionEvidence>map(digest ->
                        new ConditionEvidence.Source(observation.document(), digest, observation.span(), observation.ordinal())).orElseGet(() ->
                        new ConditionEvidence.Derived(List.of(inventory.identity(), observation.identity()), RegistrationProcessing.PROVIDER, "unavailable-discovery-source"));
                issue(problems, DISCOVERY_EVIDENCE_REQUIRED, obligation.identity().value(), null, evidence);
            } else status = ObligationStatus.NOT_DISCOVERY;
            return new ObligationRow(obligation.identity(), status, references);
        }).toList();
        var order = this.precedence.size() > limits.maxEdges() ? new Order(List.of(), OrderStatus.LIMIT_EXCEEDED) : order(this.events, evidencedEdges, limits);
        establishedOrderPrefix = order.prefix(); orderStatus = order.status();
        if (orderStatus != OrderStatus.COMPLETE) issue(problems, switch (orderStatus) {
            case MISSING_EVIDENCE -> ORDER_EVIDENCE_MISSING; case CYCLIC -> ORDER_CYCLE; default -> DISCOVERY_LIMIT;
        }, "registration-order", null, container.bootstrapEvidence());
        issues = problems.stream().distinct().sorted(Comparator.comparing(RegistrationProcessing.Issue::identity)).toList();
        identity = new Identity(RegistrationIdentity.derive("spring-registration-plan", identityFields()));
        var inherited = new TreeSet<>(RegistrationProcessing.gaps(RegistrationIdentity.digest(identity), build, issues));
        if (contextMatches) inherited.addAll(CapabilityGapNormalizer.normalize(new EvidenceContext(build.snapshotIdentity(), Optional.of(build.analysisIdentity())),
                EvidenceNormalizationInput.builder().springInventories(List.of(inventory)).build()).gaps());
        gaps = List.copyOf(inherited);
    }
    public static RegistrationPlan create(SpringBuildContext build, SpringMechanismInventory inventory, ConditionEvidenceLowering.Result lowering,
                                           List<RegistrationEvent> events, List<Precedence> precedence, Container container,
                                           List<BeanDefinitionCandidate> initialDefinitions, OverridePolicy overridePolicy, Limits limits) {
        return new RegistrationPlan(build, inventory, lowering, events, precedence, container, initialDefinitions, overridePolicy, limits);
    }
    private static boolean discoveryMechanism(String mechanism) {
        return mechanism.startsWith("spring.registration.") || mechanism.startsWith("spring.bean.")
                || mechanism.startsWith("spring.discovery.")
                || mechanism.equals("spring.mechanism.unclassified");
    }
    private void checkEvidence(List<RegistrationProcessing.Issue> issues, ConditionEvidence evidence, RegistrationEvent event) {
        if (evidence instanceof ConditionEvidence.Source source) {
            if (!build.containsSource(source)) issue(issues, SOURCE_EVIDENCE_MISMATCH, source.document().value(), event, evidence);
            if (source.span().isEmpty()) issue(issues, MISSING_SOURCE_SPAN, source.document().value(), event, evidence);
        }
    }
    static void issue(Collection<RegistrationProcessing.Issue> issues, RegistrationProcessing.Reason reason, String subject,
                      RegistrationEvent event, ConditionEvidence evidence) {
        issues.add(new RegistrationProcessing.Issue(reason, subject, Optional.ofNullable(event).map(RegistrationEvent::identity), List.of(evidence)));
    }
    private record Order(List<RegistrationEvent.Identity> prefix, OrderStatus status) {}
    private static Order order(List<RegistrationEvent> events, List<Precedence> edges, Limits limits) {
        if (events.size() > limits.maxEvents() || edges.size() > limits.maxEdges()) return new Order(List.of(), OrderStatus.LIMIT_EXCEEDED);
        Map<RegistrationEvent.Identity, Set<RegistrationEvent.Identity>> next = new HashMap<>();
        Map<RegistrationEvent.Identity, Integer> degree = new HashMap<>();
        events.forEach(e -> { next.put(e.identity(), new HashSet<>()); degree.put(e.identity(), 0); });
        edges.forEach(e -> { if (next.get(e.before()).add(e.after())) degree.merge(e.after(), 1, Integer::sum); });
        TreeSet<RegistrationEvent.Identity> frontier = new TreeSet<>(); degree.forEach((id, d) -> { if (d == 0) frontier.add(id); });
        List<RegistrationEvent.Identity> prefix = new ArrayList<>(); boolean unique = true; int visited = 0;
        while (!frontier.isEmpty()) {
            if (frontier.size() > 1) unique = false;
            // Lexical choice below is solely a cycle check after ambiguity; never exported as a legal order.
            var id = frontier.pollFirst(); visited++; if (unique) prefix.add(id);
            for (var target : next.get(id)) if (degree.merge(target, -1, Integer::sum) == 0) frontier.add(target);
        }
        return new Order(List.copyOf(prefix), visited != events.size() ? OrderStatus.CYCLIC : unique ? OrderStatus.COMPLETE : OrderStatus.MISSING_EVIDENCE);
    }
    private Map<String, Object> identityFields() {
        return Map.of("buildContextIdentity", build.identity(), "frameworkSemantics", lowering.semantics(),
                "eventDescriptors", Map.of("events", events, "inventory", inventory.identity(), "lowering", lowering.identity(), "limits", limits),
                "precedenceConstraints", precedence, "initialDefinitions", initialDefinitions,
                "containerHierarchy", List.of(container), "orderCompleteness", orderStatus, "overridePolicy", overridePolicy);
    }
    public Object canonicalForm() {
        return Map.of("schema", "spring-registration-plan-v1", "identity", identity, "inputs", identityFields(),
                "rows", rows, "coverage", coverage(), "establishedOrderPrefix", establishedOrderPrefix, "issues", issues, "capabilityGaps", gaps);
    }
    public Identity identity() { return identity; }
    public SpringBuildContext buildContext() { return build; }
    public SpringMechanismInventory inventory() { return inventory; }
    public ConditionEvidenceLowering.Result lowering() { return lowering; }
    public List<RegistrationEvent> events() { return events; }
    public List<Precedence> precedence() { return precedence; }
    public Container container() { return container; }
    public List<BeanDefinitionCandidate> initialDefinitions() { return initialDefinitions; }
    public OverridePolicy overridePolicy() { return overridePolicy; }
    public Limits limits() { return limits; }
    public List<ObligationRow> rows() { return rows; }
    public List<RegistrationProcessing.Issue> issues() { return issues; }
    public List<CapabilityGapRecord> capabilityGaps() { return gaps; }
    public List<RegistrationEvent.Identity> establishedOrderPrefix() { return establishedOrderPrefix; }
    public OrderStatus orderStatus() { return orderStatus; }
    public Coverage coverage() {
        return new Coverage(rows.size(), count(ObligationStatus.PLANNED), count(ObligationStatus.CONDITION_RETAINED), count(ObligationStatus.NOT_DISCOVERY), count(ObligationStatus.GAP));
    }
    private int count(ObligationStatus status) { return (int) rows.stream().filter(r -> r.status() == status).count(); }
    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = RegistrationIdentity.require(value, "spring-registration-plan"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }
}
