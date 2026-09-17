package com.evolution.analysis.spring.registration;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.spring.condition.*;
import java.util.*;
import static com.evolution.analysis.spring.condition.LogicalValue.*;
import static com.evolution.analysis.spring.registration.BeanRegistrationPlan.Operation.*;
import static com.evolution.analysis.spring.registration.BeanRegistrationProcessing.Reason.*;

/** Ordered, passive definition transitions. Unproven execution stops the evidenced prefix;
 * no speculative final registry, lexical execution order or independent opaque branches. */
public final class BeanRegistrationTransitions {
    private BeanRegistrationTransitions() {}
    public enum Outcome { ACCEPTED, REGISTERED, OVERRIDDEN, ALIASED, REMOVED, SKIPPED, UNKNOWN, ERROR, NOT_REACHED }
    public enum InvocationStatus { EVALUATED, PHASE_NOT_APPLICABLE, NOT_INVOKED, INHERITED_GATE }
    public record SelectorMatch(String selector, Map<String, LogicalValue> candidates, LogicalValue exists) {
        public SelectorMatch { candidates = Collections.unmodifiableMap(new TreeMap<>(candidates)); }
    }
    public record Invocation(ConditionOccurrence.Identity occurrence, InvocationStatus status, LogicalValue truth,
                             Optional<DiscoveryTransitions.StateIdentity> inputState, List<SelectorMatch> matches) {
        public Invocation { Objects.requireNonNull(inputState); matches = List.copyOf(matches); }
    }
    public record Change(String kind, String name, Optional<String> before, Optional<String> after) {}
    public record Row(String step, RegistrationEvent.Identity discoveryEvent, Outcome outcome, LogicalValue conditionTruth,
                      List<Invocation> conditions, List<Change> changes, Optional<DiscoveryTransitions.TransitionIdentity> transition) {
        public Row { conditions = List.copyOf(conditions); changes = List.copyOf(changes); }
    }
    public record Transition(DiscoveryTransitions.ContextIdentity semanticsContextIdentity, String step, RegistrationEvent.Identity eventIdentity,
                             ContentDigest stepIdentity, DiscoveryTransitions.StateIdentity inputState,
                             DiscoveryTransitions.StateIdentity outputState, LogicalValue conditionTruth,
                             Outcome operation, ContentDigest assignment) {
        public DiscoveryTransitions.TransitionIdentity identity() {
            return new DiscoveryTransitions.TransitionIdentity(RegistrationIdentity.derive("spring-registration-transition", Map.of(
                    "semanticsContextIdentity", semanticsContextIdentity, "eventIdentity", eventIdentity,
                    "inputStateIdentity", inputState, "outputStateIdentity", outputState,
                    "conditionTruth", conditionTruth, "operation", operation, "branchKey", assignment)));
        }
    }
    /** registrationSteps are canonical row references; transitions carry execution order. */
    public record Candidate(BeanDefinitionCandidate.Identity candidate, List<String> presentNamesAtPrefix,
                            LogicalValue finalPresence, List<String> registrationSteps) {
        public Candidate { presentNamesAtPrefix = List.copyOf(presentNamesAtPrefix); registrationSteps = List.copyOf(registrationSteps); }
    }
    public record Coverage(int inputSteps, int accepted, int registered, int overridden, int aliased, int removed,
                           int skipped, int unknown, int errors, int notReached) {
        public Coverage {
            if (inputSteps < 0 || accepted < 0 || registered < 0 || overridden < 0 || aliased < 0 || skipped < 0
                    || removed < 0 || unknown < 0 || errors < 0 || notReached < 0
                    || (long) accepted + registered + overridden + aliased + removed + skipped + unknown + errors + notReached != inputSteps)
                throw new IllegalArgumentException("Registration step denominator must close");
        }
    }
    public static final class Result {
        private final ContentDigest input;
        private final DiscoveryTransitions.ContextIdentity context;
        private final DiscoveryTransitions.Result discovery;
        private final List<Row> rows;
        private final List<BeanDefinitionState> states;
        private final List<Transition> transitions;
        private final List<Candidate> candidates;
        private final List<BeanRegistrationProcessing.Issue> issues;
        private final List<CapabilityGapRecord> gaps;
        private final List<BeanConditionLowering.Row> refinements;
        private final boolean registrationClosed;
        private final boolean containerError;
        private Result(Evaluation evaluation) {
            input = evaluation.input; context = evaluation.context; discovery = evaluation.discovery;
            refinements = List.copyOf(evaluation.refinements);
            rows = List.copyOf(evaluation.rows.values()); states = List.copyOf(evaluation.states);
            transitions = List.copyOf(evaluation.transitions);
            containerError = evaluation.failed;
            registrationClosed = !evaluation.uncertain && !evaluation.failed && evaluation.registryClosed
                    && evaluation.plan.order() == RegistrationEvent.Completeness.COMPLETE
                    && rows.stream().noneMatch(r -> r.outcome() == Outcome.UNKNOWN || r.outcome() == Outcome.NOT_REACHED || r.outcome() == Outcome.ERROR);
            candidates = evaluation.candidates.values().stream().map(c -> {
                var names = evaluation.registry.entrySet().stream().filter(e -> e.getValue().equals(c.identity())).map(Map.Entry::getKey).toList();
                return new Candidate(c.identity(), names, registrationClosed ? (names.isEmpty() ? FALSE : TRUE) : UNKNOWN,
                        rows.stream().filter(r -> evaluation.steps.get(r.step()).operation() == REGISTER_DEFINITION
                                && evaluation.events.get(r.discoveryEvent()).candidate().filter(v -> v.identity().equals(c.identity())).isPresent())
                                .map(Row::step).toList());
            }).toList();
            issues = evaluation.issues.stream().distinct().sorted(Comparator.comparing(BeanRegistrationProcessing.Issue::identity)).toList();
            var all = new TreeSet<>(discovery.capabilityGaps());
            all.addAll(BeanRegistrationProcessing.gaps(input, evaluation.source.buildContext(), issues)); gaps = List.copyOf(all);
        }
        public ContentDigest inputIdentity() { return input; }
        public DiscoveryTransitions.ContextIdentity semanticsContextIdentity() { return context; }
        public ContentDigest identity() { return RegistrationIdentity.digest(canonicalForm()); }
        public DiscoveryTransitions.Result discovery() { return discovery; }
        public List<Row> rows() { return rows; }
        public List<BeanDefinitionState> states() { return states; }
        public List<Transition> transitions() { return transitions; }
        public List<Candidate> candidates() { return candidates; }
        public List<BeanRegistrationProcessing.Issue> issues() { return issues; }
        public List<CapabilityGapRecord> capabilityGaps() { return gaps; }
        public List<BeanConditionLowering.Row> literalRefinements() { return refinements; }
        public boolean registrationClosed() { return registrationClosed; }
        public boolean containerError() { return containerError; }
        public Coverage coverage() { return new Coverage(rows.size(), count(Outcome.ACCEPTED), count(Outcome.REGISTERED), count(Outcome.OVERRIDDEN),
                count(Outcome.ALIASED), count(Outcome.REMOVED), count(Outcome.SKIPPED), count(Outcome.UNKNOWN), count(Outcome.ERROR), count(Outcome.NOT_REACHED)); }
        private int count(Outcome outcome) { return (int) rows.stream().filter(r -> r.outcome() == outcome).count(); }
        public Object canonicalForm() {
            return Map.ofEntries(Map.entry("schema", "spring-bean-registration-result-v1"), Map.entry("provider", BeanRegistrationProcessing.PROVIDER),
                    Map.entry("inputIdentity", input), Map.entry("semanticsContextIdentity", context), Map.entry("discovery", discovery.identity()), Map.entry("rows", rows),
                    Map.entry("states", states), Map.entry("transitions", transitions), Map.entry("candidates", candidates),
                    Map.entry("issues", issues), Map.entry("capabilityGaps", gaps), Map.entry("coverage", coverage()),
                    Map.entry("registrationClosed", registrationClosed), Map.entry("containerError", containerError), Map.entry("literalRefinements", refinements));
        }
    }
    public static Result evaluate(BeanRegistrationPlan plan, ConditionModel model, ConfigurationAssignment assignment,
                                  ExogenousConditionEvaluator.Limits exogenousLimits) {
        return new Evaluation(plan, model, assignment, exogenousLimits).run();
    }
    private static final class Evaluation {
        final BeanRegistrationPlan plan;
        final RegistrationPlan source;
        final ConfigurationAssignment assignment;
        final ContentDigest input;
        final DiscoveryTransitions.ContextIdentity context;
        final DiscoveryTransitions.Result discovery;
        final Map<RegistrationEvent.Identity, RegistrationEvent> events = new HashMap<>();
        final Map<RegistrationEvent.Identity, DiscoveryTransitions.Row> discoveryRows = new HashMap<>();
        final Map<String, BeanRegistrationPlan.Step> steps = new HashMap<>();
        final Map<ConditionOccurrence.Identity, ConditionOccurrence> occurrences = new HashMap<>();
        final Map<ConditionOccurrence.Identity, LogicalValue> exogenous = new HashMap<>();
        final Map<ConditionOccurrence.Identity, BeanRegistrationEvidence.Query> queries = new HashMap<>();
        final Map<BeanDefinitionCandidate.Identity, BeanDefinitionCandidate> candidates = new TreeMap<>();
        final Map<BeanDefinitionCandidate.Identity, BeanRegistrationEvidence.Definition> metadata = new HashMap<>();
        final Map<String, BeanRegistrationEvidence.QueryType> queryTypes = new HashMap<>();
        final Map<String, BeanDefinitionCandidate.Identity> registry = new TreeMap<>();
        final Map<String, String> aliases = new TreeMap<>();
        final Map<String, Row> rows = new TreeMap<>();
        final List<String> prefix = new ArrayList<>();
        final List<BeanDefinitionState> states = new ArrayList<>();
        final List<Transition> transitions = new ArrayList<>();
        final List<BeanRegistrationProcessing.Issue> issues = new ArrayList<>();
        final List<BeanConditionLowering.Row> refinements = new ArrayList<>();
        final boolean modelMatches;
        boolean registryClosed, uncertain, failed;
        long stateCells;
        int matchChecks;

        Evaluation(BeanRegistrationPlan plan, ConditionModel model, ConfigurationAssignment assignment, ExogenousConditionEvaluator.Limits limits) {
            this.plan = Objects.requireNonNull(plan); source = plan.discoveryPlan(); this.assignment = Objects.requireNonNull(assignment);
            Set<ConditionOccurrence.Identity> modelRows = new HashSet<>(); model.sourceRows().forEach(o -> modelRows.add(o.identity()));
            modelMatches = model.space().buildContext().identity().equals(source.buildContext().identity())
                    && source.lowering().occurrences().stream().allMatch(o -> modelRows.contains(o.identity()));
            context = new DiscoveryTransitions.ContextIdentity(RegistrationIdentity.derive("spring-semantics-context", Map.ofEntries(
                    Map.entry("buildContextIdentity", source.buildContext().identity()), Map.entry("configurationSpaceIdentity", model.space().identity()),
                    Map.entry("registrationPlanIdentity", plan.identity()), Map.entry("mechanismCatalog", source.inventory().mechanismCatalog()),
                    Map.entry("conditionIrVersion", ConditionExpression.IR), Map.entry("frameworkSemantics", source.lowering().semantics()),
                    Map.entry("registrationSemantics", BeanRegistrationProcessing.SEMANTICS), Map.entry("bindingSemantics", "NOT_IMPLEMENTED"),
                    Map.entry("reasonerPolicy", "evidenced-prefix:uncertainty-barrier-v1"),
                    Map.entry("deterministicLimits", Map.of("discovery", source.limits(), "registration", plan.limits(), "exogenous", limits)))));
            input = RegistrationIdentity.digest(Map.of("context", context, "model", model.identity(), "assignment", assignment.identity(), "provider", BeanRegistrationProcessing.PROVIDER));
            discovery = DiscoveryTransitions.prepareRegistration(source, model, assignment, limits);
            discovery.rows().forEach(r -> discoveryRows.put(r.event(), r));
            discovery.exogenousEvaluation().rows().forEach(r -> exogenous.put(r.occurrence(), r.truth()));
            source.events().forEach(e -> { events.put(e.identity(), e); e.candidate().ifPresent(c -> candidates.put(c.identity(), c)); });
            source.initialDefinitions().forEach(c -> candidates.put(c.identity(), c));
            source.lowering().occurrences().forEach(o -> occurrences.put(o.identity(), o));
            plan.steps().forEach(s -> steps.put(s.key(), s));
            plan.definitions().forEach(d -> metadata.put(d.candidate(), d));
            plan.queryTypes().forEach(t -> queryTypes.put(t.key(), t));
            plan.queries().forEach(q -> queries.put(q.occurrence(), q));
            registryClosed = plan.initialRegistry() == RegistrationEvent.Completeness.COMPLETE;
        }
        Result run() {
            validate(); initialize();
            if (1L + registry.size() + aliases.size() <= plan.limits().maxStateCells()) saveState();
            else stop(REGISTRATION_LIMIT, "initial-state", null);
            for (String key : plan.establishedOrderPrefix()) {
                if (uncertain || failed) break;
                var step = steps.get(key);
                long nextCells = 1L + prefix.size() + registry.size() + aliases.size() + 2;
                if (transitions.size() >= plan.limits().maxSteps() || stateCells + nextCells > plan.limits().maxStateCells()) {
                    stop(REGISTRATION_LIMIT, "transition-state-budget", step); break;
                }
                var before = states.getLast();
                var row = process(step, before);
                prefix.add(key); saveState();
                var event = events.get(step.discoveryEvent());
                var invocation = new RegistrationEvent.Identity(RegistrationIdentity.derive("spring-registration-event", Map.of(
                        "buildContextIdentity", source.buildContext().identity(), "triggerOccurrenceIdentity", step.identity(),
                        "parentInvocationPath", event.invocationPath(), "phase", "DEFINITION_READING", "eventSlot", step.key(),
                        "frameworkSemantics", source.lowering().semantics())));
                var transition = new Transition(context, key, invocation, step.identity(), before.identity(), states.getLast().identity(), row.conditionTruth(), row.outcome(), assignment.identity());
                transitions.add(transition);
                rows.put(key, new Row(key, step.discoveryEvent(), row.outcome(), row.conditionTruth(), row.conditions(), row.changes(), Optional.of(transition.identity())));
            }
            for (var step : plan.steps()) if (!rows.containsKey(step.key())) {
                issue(failed ? PRIOR_CONTAINER_ERROR : uncertain ? PRIOR_EXECUTION_UNKNOWN : ORDER_EVIDENCE_MISSING, "unprocessed-step", step);
                rows.put(step.key(), row(step, failed ? Outcome.NOT_REACHED : Outcome.UNKNOWN, UNKNOWN,
                        notInvoked(events.get(step.discoveryEvent()), states.isEmpty() ? null : states.getLast()), List.of()));
            }
            if (plan.order() != RegistrationEvent.Completeness.COMPLETE) {
                uncertain = true; issue(ORDER_EVIDENCE_MISSING, "registration-order", null);
            }
            return new Result(this);
        }
        void validate() {
            if (!modelMatches || source.issues().stream().anyMatch(i -> i.reason() == RegistrationProcessing.Reason.BUILD_CONTEXT_MISMATCH))
                stop(CONTEXT_MISMATCH, "build-model-context", null);
            if (source.issues().stream().anyMatch(i -> i.event().isEmpty()
                    && (i.reason() == RegistrationProcessing.Reason.SOURCE_EVIDENCE_MISMATCH || i.reason() == RegistrationProcessing.Reason.MISSING_SOURCE_SPAN)))
                stop(SOURCE_EVIDENCE_MISMATCH, "discovery-plan", null);
            boolean version = source.inventory().frameworkEvidence().acceptedConditionFragment(true)
                    && source.inventory().frameworkEvidence().artifacts().stream().anyMatch(a -> a.coordinate().equals("org.springframework.boot:spring-boot-autoconfigure:3.4.0"));
            if (!version) stop(VERSION_FRAGMENT_NOT_VALIDATED, "registration-fragment", null);
            if (discovery.exogenousEvaluation().assignmentFeasibility() != TRUE) stop(INVALID_CONFIGURATION, "assignment", null);
            if (discovery.rows().stream().anyMatch(r -> r.outcome() == DiscoveryTransitions.Outcome.ERROR)) {
                failed = true; issue(PRIOR_CONTAINER_ERROR, "discovery-failed", null);
            }
            if (!BeanRegistrationEvidence.valid(plan.orderEvidence(), source.buildContext())
                    || !BeanRegistrationEvidence.valid(plan.closureEvidence(), source.buildContext())) stop(SOURCE_EVIDENCE_MISMATCH, "registration-plan", null);
            long cells = plan.steps().size() + (long) plan.queries().size() + plan.definitions().size()
                    + plan.queryTypes().size() + source.lowering().occurrences().size();
            for (var q : plan.queries()) cells += q.selector().names().size() + (long) q.selector().types().size() + q.selector().annotations().size()
                    + q.ignoredTypes().size() + q.parameterizedContainers().size();
            for (var d : plan.definitions()) cells += d.matches().size();
            for (var step : plan.steps()) cells += events.get(step.discoveryEvent()).conditions().size();
            for (var candidate : source.initialDefinitions()) cells += 1L + candidate.declaredNameKey().aliases().size();
            if (cells > plan.limits().maxEvidenceCells() || plan.steps().size() > plan.limits().maxSteps())
                stop(REGISTRATION_LIMIT, "input-evidence-budget", null);
            if (uncertain) return;
            refinements.addAll(BeanConditionLowering.lower(source.buildContext(), source.inventory(), source.lowering(), source.container().key(),
                    16384, (int) (plan.limits().maxEvidenceCells() - cells)));
            refinements.stream().filter(r -> r.outcome() == BeanConditionLowering.Outcome.LIMIT_EXCEEDED)
                    .forEach(r -> issue(REGISTRATION_LIMIT, "literal-refinement/" + r.occurrence().value(), null));
            for (var refinement : refinements) refinement.query().ifPresent(q -> {
                var inferred = new BeanRegistrationEvidence.Query(refinement.occurrence(), q.selector(), q.ignoredTypes(), List.of(),
                        RegistrationEvent.Completeness.COMPLETE, q.evidence());
                var supplied = queries.putIfAbsent(refinement.occurrence(), inferred);
                if (supplied != null && (!supplied.selector().equals(inferred.selector()) || !supplied.ignoredTypes().equals(inferred.ignoredTypes())
                        || !supplied.parameterizedContainers().isEmpty())) stop(QUERY_REFINEMENT_INVALID, refinement.occurrence().value(), null);
            });
            if (!registryClosed) issue(INITIAL_REGISTRY_OPEN, "initial-registry", null);
            // Unaccounted discovery can have occurred before any purported registration point.
            if (source.rows().stream().anyMatch(r -> r.status() == RegistrationPlan.ObligationStatus.GAP))
                stop(DISCOVERY_INCOMPLETE, "discovery-denominator", null);
            for (var event : source.events()) {
                boolean hasStep = plan.steps().stream().anyMatch(s -> s.discoveryEvent().equals(event.identity()));
                var discoveryRow = discoveryRows.get(event.identity());
                if (!hasStep && discoveryRow.outcome() != DiscoveryTransitions.Outcome.DISCOVERED
                        && discoveryRow.outcome() != DiscoveryTransitions.Outcome.SKIPPED)
                    stop(DISCOVERY_INCOMPLETE, event.identity().value(), null);
                if (event.candidate().isPresent() && !source.initialDefinitions().contains(event.candidate().orElseThrow())
                        && plan.steps().stream().noneMatch(s -> s.discoveryEvent().equals(event.identity()) && s.operation() == REGISTER_DEFINITION))
                    stop(REGISTRATION_STEP_MISSING, event.identity().value(), null);
                boolean mutation = switch (event.kind()) { case IMPORT_REGISTRAR, REGISTRY_POST_PROCESSOR, XML_READER -> true; default -> false; };
                if (mutation && plan.steps().stream().noneMatch(s -> s.discoveryEvent().equals(event.identity()) && s.operation() == OPAQUE_MUTATION))
                    stop(REGISTRATION_STEP_MISSING, event.identity().value(), null);
            }
            for (var step : plan.steps()) {
                var event = events.get(step.discoveryEvent());
                if (!BeanRegistrationEvidence.valid(step.evidence(), source.buildContext())
                        || !BeanRegistrationEvidence.valid(event.evidence(), source.buildContext())
                        || event.candidate().filter(c -> !BeanRegistrationEvidence.valid(c.producer().declarationEvidenceKey(), source.buildContext())).isPresent()
                        || event.conditions().stream().anyMatch(c -> !BeanRegistrationEvidence.valid(c.phaseEvidence(), source.buildContext())))
                    stop(SOURCE_EVIDENCE_MISMATCH, step.key(), step);
                if ((step.operation() == REGISTER_DEFINITION || step.operation() == REMOVE_DEFINITION) && event.candidate().isEmpty())
                    stop(REGISTRATION_STEP_MISSING, "candidate", step);
                if (step.gate().isPresent()) {
                    var gate = steps.get(step.gate().orElseThrow());
                    if (gate.operation() != CONFIGURATION_GATE && gate.operation() != REGISTER_DEFINITION)
                        stop(CONFIGURATION_GATE_MISSING, "invalid-gate-operation", step);
                    if (step.gateExpectation() == BeanRegistrationPlan.GateExpectation.NO_MATCH
                            && (gate.operation() != CONFIGURATION_GATE || gate.readerDecision() != TRUE))
                        stop(CONFIGURATION_GATE_MISSING, "negative-gate-requires-condition-invocation", step);
                }
                if ((event.kind() == RegistrationEvent.Kind.BEAN_METHOD || event.kind() == RegistrationEvent.Kind.IMPORT_REGISTRAR)
                        && !hasOwnerGate(step, event)) stop(CONFIGURATION_GATE_MISSING, "bean-method-owner", step);
                if (step.operation() == REGISTER_DEFINITION && event.candidate().isPresent()) {
                    var names = event.candidate().orElseThrow().declaredNameKey();
                    for (String alias : names.aliases()) if (plan.steps().stream().noneMatch(s -> s.discoveryEvent().equals(event.identity())
                            && s.alias().filter(a -> a.name().equals(alias) && names.primary().filter(a.target()::equals).isPresent()).isPresent()))
                        stop(ALIAS_ORDER_REQUIRED, alias, step);
                }
                if (step.operation() == REGISTER_ALIAS && (step.gate().isEmpty()
                        || !steps.get(step.gate().orElseThrow()).discoveryEvent().equals(event.identity())))
                    stop(CONFIGURATION_GATE_MISSING, "alias-condition-gate", step);
            }
            for (var query : plan.queries()) {
                var occurrence = occurrences.get(query.occurrence());
                boolean beanObligation = source.lowering().issues().stream().anyMatch(i -> i.reason() == ConditionProcessing.Reason.BEAN_STATE_REQUIRED
                        && i.occurrence().filter(query.occurrence()::equals).isPresent());
                boolean identicalIr = occurrence.expression().operand() instanceof ConditionExpression.Bean bean && bean.equals(query.selector());
                if ((!beanObligation && !identicalIr) || !BeanRegistrationEvidence.valid(occurrence.declarationEvidenceKey(), source.buildContext()))
                    stop(QUERY_REFINEMENT_INVALID, query.occurrence().value(), null);
                if (beanObligation && BeanConditionLowering.predicate(source.inventory(), source.lowering(), query.occurrence())
                        .filter(query.selector().predicate()::equals).isEmpty()) stop(QUERY_REFINEMENT_INVALID, query.occurrence().value(), null);
            }
        }
        boolean hasOwnerGate(BeanRegistrationPlan.Step step, RegistrationEvent event) {
            var next = step; int hops = 0;
            while (next.gate().isPresent() && hops++ <= plan.steps().size()) {
                next = steps.get(next.gate().orElseThrow());
                if (event.parent().filter(next.discoveryEvent()::equals).isPresent()) return true;
            }
            return false;
        }
        void initialize() {
            if (uncertain) return;
            for (var candidate : source.initialDefinitions()) {
                var names = candidate.declaredNameKey();
                if (names.status() != BeanDefinitionCandidate.NameStatus.EXACT || names.primary().orElse("").isBlank()
                        || !BeanRegistrationEvidence.valid(candidate.producer().declarationEvidenceKey(), source.buildContext())) {
                    stop(CANDIDATE_NAMES_UNRESOLVED, candidate.identity().value(), null); continue;
                }
                var old = registry.putIfAbsent(names.primary().orElseThrow(), candidate.identity());
                if (old != null && !old.equals(candidate.identity())) stop(INITIAL_REGISTRY_CONFLICT, "duplicate-initial-name", null);
            }
            for (var candidate : source.initialDefinitions()) if (candidate.declaredNameKey().primary().isPresent()) {
                String target = candidate.declaredNameKey().primary().orElseThrow();
                for (String alias : candidate.declaredNameKey().aliases()) {
                    String old = aliases.putIfAbsent(alias, target);
                    if (alias.isBlank() || registry.containsKey(alias) || old != null && !old.equals(target))
                        stop(INITIAL_REGISTRY_CONFLICT, "initial-alias-conflict", null);
                }
            }
        }
        Row process(BeanRegistrationPlan.Step step, BeanDefinitionState before) {
            var event = events.get(step.discoveryEvent());
            var discovered = discoveryRows.get(event.identity());
            if (discovered.outcome() == DiscoveryTransitions.Outcome.SKIPPED)
                return skipped(step, before);
            if (discovered.outcome() != DiscoveryTransitions.Outcome.DISCOVERED) return unknown(step, before, DISCOVERY_INCOMPLETE);
            if (step.readerDecision() == FALSE) return skipped(step, before);
            if (step.gate().isPresent()) {
                var gate = rows.get(step.gate().orElseThrow());
                if (gate.conditionTruth() == UNKNOWN) return unknown(step, before, CONFIGURATION_GATE_MISSING);
                boolean wantedMatch = step.gateExpectation() == BeanRegistrationPlan.GateExpectation.MATCH;
                if ((gate.conditionTruth() == TRUE) != wantedMatch) return skipped(step, before);
                // A reader/discovery skip is not an evaluated negative condition. Only an
                // actual failed guard can justify the reader's conditional removal path.
                if (!wantedMatch && gate.conditions().stream().noneMatch(c -> c.truth() == FALSE
                        && (c.status() == InvocationStatus.EVALUATED || c.status() == InvocationStatus.INHERITED_GATE)))
                    return unknown(step, before, CONFIGURATION_GATE_MISSING);
            }
            if (step.readerDecision() != TRUE) return unknown(step, before, PRIOR_EXECUTION_UNKNOWN);
            boolean inherited = step.gate().map(steps::get).filter(g -> g.discoveryEvent().equals(event.identity())).isPresent();
            List<Invocation> invocations = new ArrayList<>();
            LogicalValue guard = event.conditionMetadata() == RegistrationEvent.Completeness.COMPLETE ? TRUE : UNKNOWN;
            if (guard != TRUE) return unknown(step, before, QUERY_METADATA_INCOMPLETE);
            for (var use : event.conditions()) {
                if (guard == FALSE) { invocations.add(invocation(use, InvocationStatus.NOT_INVOKED, UNKNOWN, before)); continue; }
                if (inherited) {
                    var cached = rows.get(step.gate().orElseThrow()).conditions().get(invocations.size());
                    invocations.add(new Invocation(use.occurrence(), InvocationStatus.INHERITED_GATE, cached.truth(), cached.inputState(), List.of()));
                    continue;
                }
                if (use.requiredPhase() == RegistrationEvent.RequiredPhase.PARSE_CONFIGURATION) {
                    invocations.add(invocation(use, InvocationStatus.PHASE_NOT_APPLICABLE, UNKNOWN, before)); continue;
                }
                if (use.requiredPhase() == RegistrationEvent.RequiredPhase.UNKNOWN) {
                    stop(CONDITION_PHASE_UNKNOWN, use.occurrence().value(), step); guard = UNKNOWN;
                    invocations.add(invocation(use, InvocationStatus.NOT_INVOKED, UNKNOWN, before)); break;
                }
                var query = queries.get(use.occurrence());
                LogicalValue truth;
                if (query != null) {
                    var evaluated = EndogenousBeanConditions.evaluate(query, before, metadata, queryTypes, source.buildContext(),
                            plan.noParentContainer() == RegistrationEvent.Completeness.COMPLETE,
                            Math.max(0, plan.limits().maxMatchChecks() - matchChecks));
                    matchChecks += evaluated.checks(); truth = evaluated.truth();
                    evaluated.reasons().stream().sorted().forEach(r -> conditionIssue(r, use.occurrence(), step));
                    invocations.add(new Invocation(use.occurrence(), InvocationStatus.EVALUATED, truth, Optional.of(before.identity()),
                            evaluated.matches().stream().map(m -> new SelectorMatch(m.selector(), m.candidates(), m.exists())).toList()));
                    if (evaluated.reasons().contains(QUERY_TYPE_ERROR)) { failed = true; guard = UNKNOWN; break; }
                } else {
                    var occurrence = occurrences.get(use.occurrence());
                    truth = exogenous.getOrDefault(use.occurrence(), UNKNOWN);
                    invocations.add(invocation(use, InvocationStatus.EVALUATED, truth, before));
                    if (occurrence == null || occurrence.expression().dependencies().contains(ConditionExpression.Dependency.OPAQUE)) {
                        // An opaque invocation has no proven purity or failure behavior. Even a later
                        // FALSE condition cannot erase its possible registry/environment mutation.
                        boolean missingBeanMetadata = source.lowering().issues().stream().anyMatch(i -> i.reason() == ConditionProcessing.Reason.BEAN_STATE_REQUIRED
                                && i.occurrence().filter(use.occurrence()::equals).isPresent());
                        uncertain = true; conditionIssue(missingBeanMetadata ? QUERY_METADATA_INCOMPLETE : REGISTRY_MUTATION_UNKNOWN, use.occurrence(), step);
                        guard = UNKNOWN; break;
                    }
                }
                guard = guard.and(truth);
                if (truth == UNKNOWN) {
                    // The next condition is reached only if this invocation matched. Without
                    // that evidence neither its execution nor its operational safety is known.
                    uncertain = true; conditionIssue(CONDITION_UNKNOWN, use.occurrence(), step); break;
                }
            }
            // Every attached occurrence stays accounted for even after an opaque barrier.
            while (invocations.size() < event.conditions().size()) invocations.add(invocation(event.conditions().get(invocations.size()), InvocationStatus.NOT_INVOKED, UNKNOWN, before));
            if (failed) return row(step, Outcome.ERROR, UNKNOWN, invocations, List.of());
            if (guard == FALSE) return row(step, Outcome.SKIPPED, FALSE, invocations, List.of());
            if (guard != TRUE || uncertain) {
                stop(CONDITION_UNKNOWN, event.identity().value(), step);
                return row(step, Outcome.UNKNOWN, UNKNOWN, invocations, List.of());
            }
            if (step.operation() == CONFIGURATION_GATE) return row(step, Outcome.ACCEPTED, TRUE, invocations, List.of());
            if (step.operation() == OPAQUE_MUTATION) {
                stop(REGISTRY_MUTATION_UNKNOWN, event.identity().value(), step);
                return row(step, Outcome.UNKNOWN, TRUE, invocations, List.of());
            }
            if (step.operation() == REGISTER_ALIAS) return alias(step, invocations);
            if (step.operation() == REMOVE_DEFINITION) return remove(step, invocations);
            return register(step, invocations);
        }
        Row remove(BeanRegistrationPlan.Step step, List<Invocation> invocations) {
            var candidate = events.get(step.discoveryEvent()).candidate().orElseThrow();
            var name = candidate.declaredNameKey().primary();
            if (name.isEmpty() || !registryClosed) {
                stop(name.isEmpty() ? CANDIDATE_NAMES_UNRESOLVED : INITIAL_REGISTRY_OPEN, "definition-removal", step);
                return row(step, Outcome.UNKNOWN, TRUE, invocations, List.of());
            }
            var old = registry.remove(name.orElseThrow());
            if (old == null) {
                failed = true; issue(DEFINITION_NOT_FOUND, name.orElseThrow(), step);
                return row(step, Outcome.ERROR, TRUE, invocations, List.of());
            }
            return row(step, Outcome.REMOVED, TRUE, invocations,
                    List.of(new Change("DEFINITION", name.orElseThrow(), Optional.of(old.value()), Optional.empty())));
        }
        Row register(BeanRegistrationPlan.Step step, List<Invocation> invocations) {
            var candidate = events.get(step.discoveryEvent()).candidate().orElseThrow();
            var names = candidate.declaredNameKey();
            if (names.status() != BeanDefinitionCandidate.NameStatus.EXACT) {
                stop(CANDIDATE_NAMES_UNRESOLVED, candidate.identity().value(), step);
                return row(step, Outcome.UNKNOWN, TRUE, invocations, List.of());
            }
            if (names.primary().orElseThrow().isBlank()) {
                failed = true; issue(INVALID_DEFINITION_NAME, candidate.identity().value(), step);
                return row(step, Outcome.ERROR, TRUE, invocations, List.of());
            }
            String name = names.primary().orElseThrow(); var old = registry.get(name); String oldAlias = aliases.get(name);
            if (!registryClosed) {
                stop(INITIAL_REGISTRY_OPEN, "registration-name-collision", step);
                return row(step, Outcome.UNKNOWN, TRUE, invocations, List.of());
            }
            if (old != null || oldAlias != null) {
                if (source.overridePolicy() == RegistrationPlan.OverridePolicy.FORBID) {
                    failed = true; issue(DEFINITION_OVERRIDE_FORBIDDEN, name, step);
                    return row(step, Outcome.ERROR, TRUE, invocations, List.of());
                }
                if (source.overridePolicy() == RegistrationPlan.OverridePolicy.UNKNOWN) {
                    stop(OVERRIDE_POLICY_UNKNOWN, name, step); return row(step, Outcome.UNKNOWN, TRUE, invocations, List.of());
                }
            }
            var changes = new ArrayList<Change>();
            // Spring removes an alias colliding with a NEW definition, not when replacing an existing one.
            if (old == null && oldAlias != null) { aliases.remove(name); changes.add(new Change("ALIAS", name, Optional.of(oldAlias), Optional.empty())); }
            registry.put(name, candidate.identity());
            changes.add(new Change("DEFINITION", name, Optional.ofNullable(old).map(BeanDefinitionCandidate.Identity::value), Optional.of(candidate.identity().value())));
            return row(step, old == null ? Outcome.REGISTERED : Outcome.OVERRIDDEN, TRUE, invocations, changes);
        }
        Row alias(BeanRegistrationPlan.Step step, List<Invocation> invocations) {
            var alias = step.alias().orElseThrow(); String name = alias.name(), target = alias.target(), old = aliases.get(name);
            if (name.isBlank() || target.isBlank()) {
                failed = true; issue(INVALID_ALIAS_NAME, "blank-alias", step);
                return row(step, Outcome.ERROR, TRUE, invocations, List.of());
            }
            if (!registryClosed) {
                stop(INITIAL_REGISTRY_OPEN, "alias-collision", step); return row(step, Outcome.UNKNOWN, TRUE, invocations, List.of());
            }
            if (name.equals(target)) {
                aliases.remove(name);
                return row(step, Outcome.ALIASED, TRUE, invocations, List.of(new Change("ALIAS", name, Optional.ofNullable(old), Optional.empty())));
            }
            if (target.equals(old)) return row(step, Outcome.ALIASED, TRUE, invocations, List.of());
            if (old != null || registry.containsKey(name)) {
                if (source.overridePolicy() == RegistrationPlan.OverridePolicy.FORBID) {
                    failed = true; issue(ALIAS_OVERRIDE_FORBIDDEN, name, step);
                    return row(step, Outcome.ERROR, TRUE, invocations, List.of());
                }
                if (source.overridePolicy() == RegistrationPlan.OverridePolicy.UNKNOWN) {
                    stop(OVERRIDE_POLICY_UNKNOWN, name, step); return row(step, Outcome.UNKNOWN, TRUE, invocations, List.of());
                }
            }
            String cursor = target; Set<String> visited = new HashSet<>();
            while (true) {
                if (cursor.equals(name) || !visited.add(cursor)) {
                    failed = true; issue(ALIAS_CYCLE, name, step); return row(step, Outcome.ERROR, TRUE, invocations, List.of());
                }
                if (!aliases.containsKey(cursor)) break;
                cursor = aliases.get(cursor);
            }
            aliases.put(name, target);
            return row(step, Outcome.ALIASED, TRUE, invocations, List.of(new Change("ALIAS", name, Optional.ofNullable(old), Optional.of(target))));
        }
        Row skipped(BeanRegistrationPlan.Step step, BeanDefinitionState before) {
            return row(step, Outcome.SKIPPED, FALSE, notInvoked(events.get(step.discoveryEvent()), before), List.of());
        }
        Row unknown(BeanRegistrationPlan.Step step, BeanDefinitionState before, BeanRegistrationProcessing.Reason reason) {
            stop(reason, step.key(), step); return row(step, Outcome.UNKNOWN, UNKNOWN, notInvoked(events.get(step.discoveryEvent()), before), List.of());
        }
        List<Invocation> notInvoked(RegistrationEvent event, BeanDefinitionState before) {
            return event.conditions().stream().map(c -> invocation(c, InvocationStatus.NOT_INVOKED, UNKNOWN, before)).toList();
        }
        Invocation invocation(RegistrationEvent.ConditionUse use, InvocationStatus status, LogicalValue truth, BeanDefinitionState before) {
            return new Invocation(use.occurrence(), status, truth, Optional.ofNullable(before).map(BeanDefinitionState::identity), List.of());
        }
        Row row(BeanRegistrationPlan.Step step, Outcome outcome, LogicalValue truth, List<Invocation> conditions, List<Change> changes) {
            return new Row(step.key(), step.discoveryEvent(), outcome, truth, conditions, changes, Optional.empty());
        }
        void saveState() {
            states.add(new BeanDefinitionState(context, source.container().key(), prefix, registry, aliases,
                    registryClosed && !uncertain && !failed, !uncertain && !failed));
            stateCells += 1L + prefix.size() + registry.size() + aliases.size();
        }
        void stop(BeanRegistrationProcessing.Reason reason, String subject, BeanRegistrationPlan.Step step) {
            uncertain = true; issue(reason, subject, step);
        }
        void issue(BeanRegistrationProcessing.Reason reason, String subject, BeanRegistrationPlan.Step step) {
            var evidence = step == null ? plan.closureEvidence() : step.evidence();
            issues.add(new BeanRegistrationProcessing.Issue(reason, subject, Optional.ofNullable(step).map(BeanRegistrationPlan.Step::key), List.of(evidence)));
        }
        void conditionIssue(BeanRegistrationProcessing.Reason reason, ConditionOccurrence.Identity occurrence, BeanRegistrationPlan.Step step) {
            var evidence = new ArrayList<ConditionEvidence>(); evidence.add(step.evidence());
            if (occurrences.containsKey(occurrence)) evidence.add(occurrences.get(occurrence).declarationEvidenceKey());
            if (queries.containsKey(occurrence)) {
                var query = queries.get(occurrence); evidence.add(query.evidence());
                if (reason == QUERY_TYPE_UNKNOWN || reason == QUERY_TYPE_ERROR || reason == QUERY_TYPE_CONFLICT) {
                    var typeNames = new HashSet<>(query.selector().types()); typeNames.addAll(query.ignoredTypes());
                    queryTypes.values().stream().filter(t -> t.kind() == BeanRegistrationEvidence.SelectorKind.TYPE
                            ? typeNames.contains(t.selector()) : query.selector().annotations().contains(t.selector()))
                            .map(BeanRegistrationEvidence.QueryType::evidence).forEach(evidence::add);
                }
            }
            issues.add(new BeanRegistrationProcessing.Issue(reason, occurrence.value(), Optional.of(step.key()), evidence));
        }
    }
}
