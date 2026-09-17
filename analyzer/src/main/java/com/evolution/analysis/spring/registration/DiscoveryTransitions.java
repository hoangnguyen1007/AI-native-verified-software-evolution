package com.evolution.analysis.spring.registration;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.spring.condition.*;
import java.util.*;
import static com.evolution.analysis.spring.registration.RegistrationProcessing.Reason.*;
import static com.evolution.analysis.spring.registration.RegistrationEvent.*;

/** Bounded discovery transitions over evidenced invocation order. Never a final bean registry. */
public final class DiscoveryTransitions {
    private DiscoveryTransitions() {}
    public enum Outcome { DISCOVERED, SKIPPED, UNKNOWN, ERROR, NOT_REACHED }
    public enum DiscoveryStatus { NOT_PROCESSED, DISCOVERED, SKIPPED, UNKNOWN }
    public enum RegistrationStatus { SUPPLIED_INITIAL_DEFINITION, NOT_EVALUATED }
    public enum InvocationStatus { EVALUATED, DEFERRED, NOT_INVOKED, UNKNOWN_PHASE }
    public record Invocation(ConditionOccurrence.Identity occurrence, ConditionSite site, InvocationStatus status, LogicalValue truth) {
        public Invocation { Objects.requireNonNull(occurrence); Objects.requireNonNull(site); Objects.requireNonNull(status); Objects.requireNonNull(truth); }
    }
    public record Row(RegistrationEvent.Identity event, Outcome outcome, LogicalValue conditionTruth,
                      List<Invocation> conditions, Optional<TransitionIdentity> transition) {
        public Row { Objects.requireNonNull(event); Objects.requireNonNull(outcome); Objects.requireNonNull(conditionTruth); conditions = List.copyOf(conditions); Objects.requireNonNull(transition); }
    }
    public record CandidateState(BeanDefinitionCandidate.Identity candidate, DiscoveryStatus discovery,
                                 RegistrationStatus registration, List<RegistrationEvent.Identity> discoveryEvents) {
        public CandidateState { Objects.requireNonNull(candidate); Objects.requireNonNull(discovery); Objects.requireNonNull(registration); discoveryEvents = ContractChecks.sortedDistinct(discoveryEvents, Comparator.naturalOrder(), "candidate discovery events"); }
    }
    public record State(ContextIdentity semanticsContextIdentity, String containerKey,
                         List<RegistrationEvent.Identity> processedEventPrefix, List<CandidateState> definitions, boolean discoveryComplete) {
        public State {
            Objects.requireNonNull(semanticsContextIdentity); containerKey = RegistrationIdentity.text(containerKey);
            processedEventPrefix = ContractChecks.distinctInOrder(processedEventPrefix, "processed prefix");
            definitions = ContractChecks.sortedDistinct(definitions, Comparator.comparing(CandidateState::candidate), "candidate states");
        }
        public StateIdentity identity() {
            return new StateIdentity(RegistrationIdentity.derive("spring-definition-state", Map.of(
                    "semanticsContextIdentity", semanticsContextIdentity, "containerKey", containerKey,
                    "processedEventPrefix", processedEventPrefix, "definitions", definitions, "aliases", Map.of("status", "NOT_EVALUATED"),
                    "completeness", Map.of("discoveryPrefix", discoveryComplete, "registration", "NOT_EVALUATED"))));
        }
    }
    public record Transition(ContextIdentity semanticsContextIdentity, RegistrationEvent.Identity eventIdentity,
                              StateIdentity inputStateIdentity, StateIdentity outputStateIdentity,
                              LogicalValue conditionTruth, Outcome operation, ContentDigest branchKey) {
        public Transition {
            Objects.requireNonNull(semanticsContextIdentity); Objects.requireNonNull(eventIdentity); Objects.requireNonNull(inputStateIdentity);
            Objects.requireNonNull(outputStateIdentity); Objects.requireNonNull(conditionTruth); Objects.requireNonNull(operation); Objects.requireNonNull(branchKey);
        }
        public TransitionIdentity identity() {
            return new TransitionIdentity(RegistrationIdentity.derive("spring-registration-transition", Map.of(
                    "semanticsContextIdentity", semanticsContextIdentity, "eventIdentity", eventIdentity,
                    "inputStateIdentity", inputStateIdentity, "outputStateIdentity", outputStateIdentity,
                    "conditionTruth", conditionTruth, "operation", operation, "branchKey", branchKey)));
        }
    }
    public record Coverage(int inputEvents, int discovered, int skipped, int unknown, int errors, int notReached) {
        public Coverage {
            if (inputEvents < 0 || discovered < 0 || skipped < 0 || unknown < 0 || errors < 0 || notReached < 0
                    || (long) discovered + skipped + unknown + errors + notReached != inputEvents)
                throw new IllegalArgumentException("Discovery event denominator must close");
        }
    }
    public static final class Result {
        private final ContentDigest input;
        private final ContextIdentity context;
        private final List<Row> rows;
        private final List<State> states;
        private final List<Transition> transitions;
        private final List<CandidateState> candidates;
        private final List<RegistrationProcessing.Issue> issues;
        private final List<CapabilityGapRecord> gaps;
        private final boolean discoveryClosed;
        private final ExogenousConditionEvaluator.Result conditions;
        private Result(Evaluation evaluation) {
            input = evaluation.input; context = evaluation.context; conditions = evaluation.conditionResult;
            rows = evaluation.rows.values().stream().sorted(Comparator.comparing(Row::event)).toList();
            states = List.copyOf(evaluation.states); transitions = List.copyOf(evaluation.transitions);
            candidates = List.copyOf(evaluation.candidates.values());
            issues = evaluation.issues.stream().distinct().sorted(Comparator.comparing(RegistrationProcessing.Issue::identity)).toList();
            var allGaps = new TreeSet<>(evaluation.plan.capabilityGaps());
            allGaps.addAll(evaluation.plan.lowering().capabilityGaps()); allGaps.addAll(conditions.capabilityGaps());
            allGaps.addAll(RegistrationProcessing.gaps(input, evaluation.plan.buildContext(), issues)); gaps = List.copyOf(allGaps);
            discoveryClosed = evaluation.discoveryClosed && rows.stream().allMatch(r -> r.outcome() == Outcome.DISCOVERED || r.outcome() == Outcome.SKIPPED);
        }
        public ContentDigest inputIdentity() { return input; }
        public ContentDigest identity() { return RegistrationIdentity.digest(canonicalForm()); }
        public ContextIdentity semanticsContextIdentity() { return context; }
        public List<Row> rows() { return rows; }
        public List<State> states() { return states; }
        public List<Transition> transitions() { return transitions; }
        public List<CandidateState> candidates() { return candidates; }
        public List<RegistrationProcessing.Issue> issues() { return issues; }
        public List<CapabilityGapRecord> capabilityGaps() { return gaps; }
        public boolean discoveryClosed() { return discoveryClosed; }
        public ExogenousConditionEvaluator.Result exogenousEvaluation() { return conditions; }
        public Coverage coverage() { return new Coverage(rows.size(), count(Outcome.DISCOVERED), count(Outcome.SKIPPED), count(Outcome.UNKNOWN), count(Outcome.ERROR), count(Outcome.NOT_REACHED)); }
        private int count(Outcome outcome) { return (int) rows.stream().filter(r -> r.outcome() == outcome).count(); }
        public Object canonicalForm() {
            return Map.of("schema", "spring-discovery-transitions-v1", "inputIdentity", input, "semanticsContextIdentity", context,
                    "rows", rows, "states", states, "transitions", transitions, "candidates", candidates,
                    "evidence", Map.of("issues", issues, "capabilityGaps", gaps, "exogenousEvaluation", conditions.identity()),
                    "coverage", coverage(), "discoveryClosed", discoveryClosed);
        }
    }
    public static Result evaluate(RegistrationPlan plan, ConditionModel model, ConfigurationAssignment assignment, ExogenousConditionEvaluator.Limits limits) {
        return new Evaluation(plan, model, assignment, limits, false).run();
    }
    /** M4C.2 preparation: register-site conditions/callbacks belong to the separate evidenced
     * registration schedule. This mode establishes membership only, never their effects. */
    public static Result prepareRegistration(RegistrationPlan plan, ConditionModel model, ConfigurationAssignment assignment,
                                             ExogenousConditionEvaluator.Limits limits) {
        return new Evaluation(plan, model, assignment, limits, true).run();
    }
    private static final class Evaluation {
        final RegistrationPlan plan;
        final ContentDigest input;
        final ContextIdentity context;
        final ConfigurationAssignment assignment;
        final ExogenousConditionEvaluator.Result conditionResult;
        final Map<RegistrationEvent.Identity, RegistrationEvent> events = new HashMap<>();
        final Map<RegistrationEvent.Identity, Row> rows = new HashMap<>();
        final Map<ConditionOccurrence.Identity, LogicalValue> truths = new HashMap<>();
        final Set<ConditionOccurrence.Identity> opaqueConditions = new HashSet<>();
        final Map<BeanDefinitionCandidate.Identity, CandidateState> candidates = new TreeMap<>();
        final List<State> states = new ArrayList<>();
        final List<Transition> transitions = new ArrayList<>();
        final List<RegistrationProcessing.Issue> issues = new ArrayList<>();
        final boolean validContext;
        final boolean registrationPreparation;
        boolean discoveryClosed, environmentUnknown, stopped;
        long stateCells;
        Evaluation(RegistrationPlan plan, ConditionModel model, ConfigurationAssignment assignment, ExogenousConditionEvaluator.Limits limits,
                   boolean registrationPreparation) {
            this.plan = Objects.requireNonNull(plan); Objects.requireNonNull(model); this.assignment = Objects.requireNonNull(assignment);
            this.registrationPreparation = registrationPreparation;
            context = new ContextIdentity(RegistrationIdentity.derive("spring-semantics-context", Map.ofEntries(
                    Map.entry("buildContextIdentity", plan.buildContext().identity()), Map.entry("configurationSpaceIdentity", model.space().identity()),
                    Map.entry("registrationPlanIdentity", plan.identity()), Map.entry("mechanismCatalog", plan.inventory().mechanismCatalog()),
                    Map.entry("conditionIrVersion", ConditionExpression.IR), Map.entry("frameworkSemantics", plan.lowering().semantics()),
                    Map.entry("registrationSemantics", RegistrationProcessing.SEMANTICS), Map.entry("bindingSemantics", "NOT_IMPLEMENTED"),
                    Map.entry("reasonerPolicy", registrationPreparation ? "registration-preparation:m4c.2-v1" : "evidenced-order-prefix:no-branching-v1"), Map.entry("deterministicLimits", Map.of("discovery", plan.limits(), "exogenous", limits)))));
            input = RegistrationIdentity.digest(Map.of("context", context, "model", model.identity(), "assignment", assignment.identity(), "provider", RegistrationProcessing.PROVIDER));
            var modelOccurrences = new HashSet<ConditionOccurrence.Identity>(); model.sourceRows().forEach(o -> modelOccurrences.add(o.identity()));
            validContext = model.space().buildContext().identity().equals(plan.buildContext().identity())
                    && plan.lowering().occurrences().stream().allMatch(o -> modelOccurrences.contains(o.identity()));
            conditionResult = ExogenousConditionEvaluator.evaluate(model, plan.lowering().semantics(), assignment, limits);
            conditionResult.rows().forEach(r -> truths.put(r.occurrence(), r.truth()));
            plan.lowering().occurrences().stream().filter(o -> o.expression().dependencies().contains(ConditionExpression.Dependency.OPAQUE))
                    .forEach(o -> opaqueConditions.add(o.identity()));
            plan.events().forEach(e -> { events.put(e.identity(), e); e.candidate().ifPresent(c -> candidates.putIfAbsent(c.identity(),
                    new CandidateState(c.identity(), DiscoveryStatus.NOT_PROCESSED, RegistrationStatus.NOT_EVALUATED, List.of()))); });
            plan.initialDefinitions().forEach(c -> candidates.put(c.identity(), new CandidateState(c.identity(), DiscoveryStatus.DISCOVERED,
                    RegistrationStatus.SUPPLIED_INITIAL_DEFINITION, List.of())));
            discoveryClosed = plan.issues().isEmpty() && validContext && conditionResult.assignmentFeasibility() == LogicalValue.TRUE;
            if (!validContext) issue(BUILD_CONTEXT_MISMATCH, "condition-model", null);
            if (conditionResult.assignmentFeasibility() != LogicalValue.TRUE) issue(INVALID_CONFIGURATION, "assignment-feasibility", null);
        }
        Result run() {
            if (candidates.size() <= plan.limits().maxStateCells()) {
                states.add(new State(context, plan.container().key(), List.of(), List.copyOf(candidates.values()), discoveryClosed));
                stateCells = candidates.size();
            }
            boolean budgetExhausted = false;
            for (var id : plan.establishedOrderPrefix()) {
                if (stopped) break;
                var event = events.get(id);
                if (transitions.size() >= plan.limits().maxTransitions() || states.isEmpty()
                        || stateCells + candidates.size() + transitions.size() + 1 > plan.limits().maxStateCells()) {
                    issue(DISCOVERY_LIMIT, "transition-state-budget", event); budgetExhausted = true; break;
                }
                var row = process(event);
                updateCandidate(event, row.outcome());
                var previous = states.getLast(); var prefix = new ArrayList<>(previous.processedEventPrefix()); prefix.add(id);
                var state = new State(context, plan.container().key(), prefix, List.copyOf(candidates.values()), discoveryClosed);
                stateCells += candidates.size() + prefix.size();
                var transition = new Transition(context, id, previous.identity(), state.identity(), row.conditionTruth(), row.outcome(), assignment.identity());
                states.add(state); transitions.add(transition);
                rows.put(id, new Row(id, row.outcome(), row.conditionTruth(), row.conditions(), Optional.of(transition.identity())));
            }
            for (var event : plan.events()) if (!rows.containsKey(event.identity())) {
                issue(stopped ? PRIOR_CONTAINER_ERROR : budgetExhausted || plan.orderStatus() == RegistrationPlan.OrderStatus.LIMIT_EXCEEDED ? DISCOVERY_LIMIT : ORDER_EVIDENCE_MISSING,
                        "unprocessed-event", event);
                var outcome = stopped ? Outcome.NOT_REACHED : Outcome.UNKNOWN;
                rows.put(event.identity(), row(event, outcome, LogicalValue.UNKNOWN, InvocationStatus.NOT_INVOKED));
                updateCandidate(event, outcome);
            }
            for (var event : plan.events()) event.candidate().ifPresent(candidate -> {
                if (candidate.declaredNameKey().status() == BeanDefinitionCandidate.NameStatus.UNRESOLVED) issue(CANDIDATE_NAMES_UNRESOLVED, candidate.identity().value(), event);
                if (candidate.exposedTypes().isEmpty()) issue(CANDIDATE_TYPES_UNRESOLVED, candidate.identity().value(), event);
            });
            if (candidates.values().stream().anyMatch(c -> c.registration() == RegistrationStatus.NOT_EVALUATED))
                issue(REGISTRATION_NOT_EVALUATED, "candidate-definition-registration", null);
            return new Result(this);
        }
        Row process(RegistrationEvent event) {
            if (stopped) return row(event, Outcome.NOT_REACHED, LogicalValue.UNKNOWN, InvocationStatus.NOT_INVOKED);
            if (!validContext || conditionResult.assignmentFeasibility() != LogicalValue.TRUE || invalidEvidence(event))
                return row(event, Outcome.UNKNOWN, LogicalValue.UNKNOWN, InvocationStatus.NOT_INVOKED);
            if (event.parent().isPresent()) {
                var parentRow = rows.get(event.parent().orElseThrow());
                if (parentRow == null || parentRow.outcome() == Outcome.UNKNOWN) return row(event, Outcome.UNKNOWN, LogicalValue.UNKNOWN, InvocationStatus.NOT_INVOKED);
                if (parentRow.outcome() == Outcome.SKIPPED) return row(event, Outcome.SKIPPED, LogicalValue.FALSE, InvocationStatus.NOT_INVOKED);
                if (parentRow.outcome() != Outcome.DISCOVERED) return row(event, Outcome.NOT_REACHED, LogicalValue.UNKNOWN, InvocationStatus.NOT_INVOKED);
            }
            if (event.membership() == Completeness.UNKNOWN) {
                issue(MEMBERSHIP_UNPROVEN, event.eventSlot(), event); return row(event, Outcome.UNKNOWN, LogicalValue.UNKNOWN, InvocationStatus.NOT_INVOKED);
            }
            if (event.kind() == Kind.COMPONENT_SCAN && hasRegisterCondition(event)) {
                issue(SCAN_REGISTER_PHASE_CONDITION, event.eventSlot(), event);
                boolean framework62 = plan.inventory().frameworkEvidence().artifacts().stream().anyMatch(a -> a.coordinate().equals("org.springframework:spring-context:6.2.0"));
                if (framework62) stopped = true;
                return row(event, framework62 ? Outcome.ERROR : Outcome.UNKNOWN, LogicalValue.UNKNOWN, InvocationStatus.NOT_INVOKED);
            }
            if (importCycle(event)) { issue(IMPORT_CYCLE, event.eventSlot(), event); stopped = true; return row(event, Outcome.ERROR, LogicalValue.UNKNOWN, InvocationStatus.NOT_INVOKED); }
            if (registrationPreparation && (event.conditionSite() == ConditionSite.REGISTER_BEAN
                    || event.kind() == Kind.IMPORT_REGISTRAR || event.kind() == Kind.REGISTRY_POST_PROCESSOR
                    || event.kind() == Kind.XML_READER)) {
                return row(event, environmentUnknown ? Outcome.UNKNOWN : Outcome.DISCOVERED,
                        environmentUnknown ? LogicalValue.UNKNOWN : LogicalValue.TRUE, InvocationStatus.DEFERRED);
            }
            LogicalValue guard = event.conditionMetadata() == Completeness.COMPLETE ? LogicalValue.TRUE : LogicalValue.UNKNOWN;
            if (event.conditionMetadata() != Completeness.COMPLETE) issue(CONDITION_METADATA_INCOMPLETE, event.eventSlot(), event);
            var invocations = new ArrayList<Invocation>();
            for (var condition : event.conditions()) {
                InvocationStatus status; LogicalValue truth = LogicalValue.UNKNOWN;
                if (guard == LogicalValue.FALSE) status = InvocationStatus.NOT_INVOKED;
                else if (event.conditionSite() == ConditionSite.DISCOVERY_ONLY || deferred(event, condition)) {
                    status = InvocationStatus.DEFERRED; issue(REGISTRATION_CONDITION_DEFERRED, condition.occurrence().value(), event);
                } else if (condition.requiredPhase() == RequiredPhase.UNKNOWN) {
                    status = InvocationStatus.UNKNOWN_PHASE; guard = guard.and(LogicalValue.UNKNOWN); issue(CONDITION_PHASE_UNKNOWN, condition.occurrence().value(), event);
                } else {
                    status = InvocationStatus.EVALUATED;
                    truth = environmentUnknown ? LogicalValue.UNKNOWN : truths.getOrDefault(condition.occurrence(), LogicalValue.UNKNOWN);
                    // An invoked opaque condition has no proven purity contract. Its possible
                    // environment effects also qualify subsequent condition evaluations.
                    if (opaqueConditions.contains(condition.occurrence())) environmentUnknown = true;
                    guard = guard.and(truth);
                    if (truth == LogicalValue.UNKNOWN) issue(PARSE_CONDITION_UNKNOWN, condition.occurrence().value(), event);
                }
                invocations.add(new Invocation(condition.occurrence(), event.conditionSite(), status, truth));
            }
            if (guard == LogicalValue.FALSE) return new Row(event.identity(), Outcome.SKIPPED, guard, invocations, Optional.empty());
            if (guard == LogicalValue.UNKNOWN) { discoveryClosed = false; return new Row(event.identity(), Outcome.UNKNOWN, guard, invocations, Optional.empty()); }
            RegistrationProcessing.Reason opaque = switch (event.kind()) {
                case IMPORT_SELECTOR -> UNKNOWN_IMPORT_SELECTOR; case IMPORT_REGISTRAR -> CUSTOM_REGISTRAR;
                case REGISTRY_POST_PROCESSOR -> REGISTRY_MUTATION_UNKNOWN; case ENVIRONMENT_MUTATION -> ENVIRONMENT_MUTATION_UNKNOWN;
                case XML_READER -> XML_READER_UNSUPPORTED; default -> null;
            };
            if (opaque != null) {
                issue(opaque, event.eventSlot(), event); discoveryClosed = false;
                // Arbitrary callbacks may mutate the Environment too. Never reuse precomputed conditions across them.
                environmentUnknown = true;
                return new Row(event.identity(), Outcome.UNKNOWN, LogicalValue.UNKNOWN, invocations, Optional.empty());
            }
            return new Row(event.identity(), Outcome.DISCOVERED, guard, invocations, Optional.empty());
        }
        boolean invalidEvidence(RegistrationEvent event) {
            return plan.issues().stream().anyMatch(i -> (i.event().isEmpty() || i.event().filter(event.identity()::equals).isPresent())
                    && switch (i.reason()) {
                        case BUILD_CONTEXT_MISMATCH, VERSION_FRAGMENT_NOT_VALIDATED, SOURCE_EVIDENCE_MISMATCH, MISSING_SOURCE_SPAN,
                                CONDITION_REFERENCE_MISSING, CONDITION_PHASE_CONFLICT, PARENT_REFERENCE_MISSING, INVALID_ORDER_DOMAIN, DISCOVERY_LIMIT -> true;
                        default -> false;
                    });
        }
        boolean deferred(RegistrationEvent event, ConditionUse condition) {
            return condition.requiredPhase() == RequiredPhase.REGISTER_BEAN && event.conditionSite() == ConditionSite.PARSE_CONFIGURATION
                    || condition.requiredPhase() == RequiredPhase.PARSE_CONFIGURATION && event.conditionSite() == ConditionSite.REGISTER_BEAN;
        }
        boolean hasRegisterCondition(RegistrationEvent event) {
            // S12 collects the scan owner's conditions and its actual enclosing configuration,
            // not conditions from arbitrary importing ancestors. Enclosing metadata is supplied on this site.
            return event.conditions().stream().anyMatch(c -> c.requiredPhase() == RequiredPhase.REGISTER_BEAN)
                    || event.parent().map(events::get).stream().flatMap(p -> p.conditions().stream())
                    .anyMatch(c -> c.requiredPhase() == RequiredPhase.REGISTER_BEAN);
        }
        boolean importCycle(RegistrationEvent event) {
            if (event.kind() != Kind.DIRECT_IMPORT || event.candidate().isEmpty()) return false;
            var producer = event.candidate().orElseThrow().producer(); var parent = event.parent().map(events::get).orElse(null); int depth = 0;
            while (parent != null && depth++ <= plan.limits().maxNestingDepth()) {
                if (parent.candidate().map(c -> c.producer().declarationEvidenceKey().equals(producer.declarationEvidenceKey())
                        && c.producer().declarationSlot().equals(producer.declarationSlot())).orElse(false)) return true;
                parent = parent.parent().map(events::get).orElse(null);
            }
            return false;
        }
        Row row(RegistrationEvent event, Outcome outcome, LogicalValue truth, InvocationStatus status) {
            if (outcome != Outcome.DISCOVERED && outcome != Outcome.SKIPPED) discoveryClosed = false;
            return new Row(event.identity(), outcome, truth, event.conditions().stream()
                    .map(c -> new Invocation(c.occurrence(), event.conditionSite(), status, LogicalValue.UNKNOWN)).toList(), Optional.empty());
        }
        void updateCandidate(RegistrationEvent event, Outcome outcome) {
            event.candidate().ifPresent(candidate -> {
                var previous = candidates.get(candidate.identity());
                DiscoveryStatus now = switch (outcome) { case DISCOVERED -> DiscoveryStatus.DISCOVERED; case SKIPPED -> DiscoveryStatus.SKIPPED; default -> DiscoveryStatus.UNKNOWN; };
                if (previous.discovery() == DiscoveryStatus.DISCOVERED || now == DiscoveryStatus.DISCOVERED) now = DiscoveryStatus.DISCOVERED;
                else if (previous.discovery() == DiscoveryStatus.UNKNOWN) now = DiscoveryStatus.UNKNOWN;
                var paths = new ArrayList<>(previous.discoveryEvents()); paths.add(event.identity());
                candidates.put(candidate.identity(), new CandidateState(candidate.identity(), now, previous.registration(), paths));
            });
        }
        void issue(RegistrationProcessing.Reason reason, String subject, RegistrationEvent event) {
            RegistrationPlan.issue(issues, reason, subject, event, event == null ? plan.container().bootstrapEvidence() : event.evidence());
            if (reason != REGISTRATION_NOT_EVALUATED && reason != REGISTRATION_CONDITION_DEFERRED && reason != CANDIDATE_NAMES_UNRESOLVED && reason != CANDIDATE_TYPES_UNRESOLVED)
                discoveryClosed = false;
        }
    }
    public record ContextIdentity(String value) implements CanonicalIdentifier {
        public ContextIdentity { value = RegistrationIdentity.require(value, "spring-semantics-context"); }
    }
    public record StateIdentity(String value) implements CanonicalIdentifier {
        public StateIdentity { value = RegistrationIdentity.require(value, "spring-definition-state"); }
    }
    public record TransitionIdentity(String value) implements CanonicalIdentifier {
        public TransitionIdentity { value = RegistrationIdentity.require(value, "spring-registration-transition"); }
    }
}
