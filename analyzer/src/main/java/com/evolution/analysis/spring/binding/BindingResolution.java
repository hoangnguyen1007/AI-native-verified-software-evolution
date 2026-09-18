package com.evolution.analysis.spring.binding;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;
import static com.evolution.analysis.spring.binding.BindingEvidence.*;
import static com.evolution.analysis.spring.binding.BindingProcessing.Reason.*;
import static com.evolution.analysis.spring.binding.InjectionBindingPlan.*;
import static com.evolution.analysis.spring.binding.InjectionBindings.*;
import static com.evolution.analysis.spring.condition.LogicalValue.*;
import static com.evolution.analysis.spring.registration.RegistrationEvent.Completeness.COMPLETE;

/** Framework 6.2.0 doResolveDependency/findAutowireCandidates selection fragment.
 * The engine manipulates definitions and evidence; it does not call resolveInstance/getBean. */
final class BindingResolution {
    private final InjectionBindingPlan plan;
    private final BeanRegistrationTransitions.Result registration;
    private final SpringBuildContext build;
    private final DiscoveryTransitions.ContextIdentity context;
    private final ContentDigest input;
    private final Map<BeanDefinitionCandidate.Identity, Definition> definitions = new HashMap<>();
    private final Map<BeanDefinitionCandidate.Identity, BeanRegistrationEvidence.Definition> registeredMetadata = new HashMap<>();
    private final Map<String, Match> matches = new HashMap<>();
    private final Map<ContentDigest, Dependency> dependencies = new TreeMap<>();
    private final Map<ContentDigest, Row> rows = new TreeMap<>();
    private final List<BindingProcessing.Issue> issues = new ArrayList<>();
    private final List<ObligationRow> obligations = new ArrayList<>();
    private final List<BindingProcessing.Reason> barriers = new ArrayList<>();
    private final Map<String, BeanDefinitionCandidate.Identity> registry;
    private final Map<String, String> aliases;
    private long checks, traceRows, evaluated;

    BindingResolution(InjectionBindingPlan plan, BeanRegistrationTransitions.Result registration,
                      ConfigurationSpaceIdentity space, ExogenousConditionEvaluator.Limits exogenousLimits) {
        this.plan = plan; this.registration = registration; build = plan.registrationPlan().discoveryPlan().buildContext();
        var source = plan.registrationPlan().discoveryPlan();
        context = new DiscoveryTransitions.ContextIdentity(BindingIdentity.derive("spring-semantics-context", Map.ofEntries(
                Map.entry("buildContextIdentity", build.identity()), Map.entry("configurationSpaceIdentity", space),
                Map.entry("registrationPlanIdentity", plan.registrationPlan().identity()), Map.entry("mechanismCatalog", source.inventory().mechanismCatalog()),
                Map.entry("conditionIrVersion", ConditionExpression.IR), Map.entry("frameworkSemantics", source.lowering().semantics()),
                Map.entry("registrationSemantics", BeanRegistrationProcessing.SEMANTICS), Map.entry("bindingSemantics", BindingProcessing.SEMANTICS),
                Map.entry("bindingPlanIdentity", plan.identity()), Map.entry("bindingContextSchema", "spring-binding-context-v1"),
                Map.entry("reasonerPolicy", "complete-candidates:evidence-barrier-v1"),
                Map.entry("deterministicLimits", Map.of("discovery", source.limits(), "registration", plan.registrationPlan().limits(),
                        "exogenous", exogenousLimits, "binding", plan.limits())))));
        input = BindingIdentity.digest(Map.of("context", context, "registration", registration.identity(), "provider", BindingProcessing.PROVIDER));
        var state = registration.states().isEmpty() ? null : registration.states().getLast();
        registry = state == null ? Map.of() : state.definitions(); aliases = state == null ? Map.of() : state.aliases();
        plan.definitions().forEach(d -> definitions.put(d.candidate(), d));
        plan.registrationPlan().definitions().forEach(d -> registeredMetadata.put(d.candidate(), d));
        plan.matches().forEach(m -> matches.put(m.key(), m));
        plan.dependencies().forEach(d -> dependencies.put(d.identity(), d));
    }
    Result run() {
        validateEnvironment();
        accountForObligations();
        for (var group : plan.groups()) evaluateGroup(group);
        for (var d : plan.dependencies()) if (!rows.containsKey(d.identity())) rows.put(d.identity(), evaluate(d));
        return new Result(input, context, registration, List.copyOf(rows.values()), obligations, issues, build);
    }
    private void accountForObligations() {
        var inventory = plan.registrationPlan().discoveryPlan().inventory();
        var mappings = new HashMap<ContentDigest, ObligationBinding>(); plan.obligations().forEach(o -> mappings.put(o.obligation(), o));
        var raw = new HashMap<ContentDigest, com.evolution.analysis.spring.SpringMechanismInventory.RawObservation>();
        inventory.rawObservations().forEach(o -> raw.put(o.identity(), o));
        for (var obligation : inventory.obligations()) {
            var status = ObligationStatus.NOT_INJECTION; var references = List.<ContentDigest>of();
            if (obligation.primaryMechanism().startsWith("spring.injection.")) {
                var mapping = mappings.get(obligation.identity());
                boolean mapped = mapping != null && valid(mapping.evidence(), build) && !barriers.contains(BINDING_LIMIT);
                status = mapped ? ObligationStatus.NORMALIZED : ObligationStatus.NORMALIZATION_REQUIRED;
                if (mapping != null) references = mapping.dependencies();
                if (!mapped) {
                    var observation = raw.get(obligation.rawObservationIdentity());
                    ConditionEvidence evidence = build.sourceDigest(observation.document())
                            .<ConditionEvidence>map(digest -> new ConditionEvidence.Source(observation.document(), digest, observation.span(), observation.ordinal()))
                            .orElseGet(() -> new ConditionEvidence.Derived(List.of(inventory.identity(), observation.identity()), BindingProcessing.PROVIDER, "missing-injection-source"));
                    issue(mapping == null ? DESCRIPTOR_INCOMPLETE : !valid(mapping.evidence(), build) ? SOURCE_EVIDENCE_MISMATCH : BINDING_LIMIT,
                            null, obligation.identity().value(), mapping == null ? List.of(evidence) : List.of(evidence, mapping.evidence()));
                }
            }
            obligations.add(new ObligationRow(obligation.identity(), status, references));
        }
    }
    private void validateEnvironment() {
        var source = plan.registrationPlan().discoveryPlan();
        var framework = source.inventory().frameworkEvidence();
        if (!framework.acceptedConditionFragment(false) || framework.artifacts().stream()
                .noneMatch(a -> a.coordinate().equals("org.springframework:spring-beans:6.2.0"))) barrier(VERSION_FRAGMENT_NOT_VALIDATED);
        if (!registration.registrationClosed()) barrier(REGISTRATION_INCOMPLETE);
        if (plan.registrationPlan().noParentContainer() != COMPLETE) barrier(HIERARCHY_UNSUPPORTED);
        if (plan.environment().noResolvableDependencies() != COMPLETE) barrier(RESOLVABLE_DEPENDENCIES_UNMODELED);
        if (plan.environment().noPostRegistrationMutation() != COMPLETE) barrier(POST_REGISTRATION_MUTATION);
        if (!valid(plan.environment().evidence(), build)) barrier(SOURCE_EVIDENCE_MISMATCH);
        if (plan.environment().descriptorsComplete() != COMPLETE)
            issue(DESCRIPTOR_INVENTORY_OPEN, null, "descriptor-denominator", List.of(plan.environment().evidence()));
        if ((long) plan.dependencies().size() + plan.definitions().size() + plan.matches().size()
                + plan.groups().stream().mapToLong(g -> g.dependencies().size() + 1L).sum()
                + plan.obligations().stream().mapToLong(o -> o.dependencies().size() + 1L).sum()
                + plan.environment().candidateOrder().size() > plan.limits().maxEvidenceCells()) barrier(BINDING_LIMIT);
    }
    private void barrier(BindingProcessing.Reason reason) {
        barriers.add(reason); issue(reason, null, "binding-environment", List.of(plan.environment().evidence()));
    }
    private Row evaluate(Dependency d) {
        var s = new Session(d);
        try {
            if (registration.containerError()) throw new Halt(PRIOR_CONTAINER_ERROR, Outcome.NOT_REACHED, Operation.NOT_INVOKED);
            if (!barriers.isEmpty()) throw halt(barriers.getFirst());
            if (!d.point().buildContextIdentity().equals(build.identity())) throw halt(CONTEXT_MISMATCH);
            s.evidence(d.point().declarationEvidenceKey()); s.evidence(d.evidence());
            if (d.owner().isPresent() && registry.entrySet().stream().noneMatch(e -> !aliases.containsKey(e.getKey()) && e.getValue().equals(d.owner().orElseThrow())))
                return s.row(Outcome.NOT_ACTIVE, Operation.NOT_INVOKED, List.of(), null, true);
            if (++evaluated > plan.limits().maxDependencies()) throw halt(BINDING_LIMIT);
            if (d.normalization() != Normalization.COMPLETE) throw halt(switch (d.normalization()) {
                case INCOMPLETE -> DESCRIPTOR_INCOMPLETE;
                case CUSTOM_RESOLVER -> CUSTOM_RESOLVER;
                case VALUE_EXPRESSION -> VALUE_EXPRESSION;
                case CONSTRUCTOR_SELECTION -> CONSTRUCTOR_SELECTION_REQUIRED;
                case GENERATED_MEMBER -> GENERATED_MEMBER_REQUIRED;
                case RESOURCE_NAMESPACE -> RESOURCE_NAMESPACE_UNSUPPORTED;
                case JNDI_LOOKUP -> JNDI_LOOKUP_UNSUPPORTED;
                case COMPLETE -> throw new AssertionError();
            });
            if (d.shape() == Shape.UNSUPPORTED) throw halt(UNSUPPORTED_SHAPE);
            if (d.requestedType().status() != SemanticStatus.RESOLVED) throw halt(TYPE_EVIDENCE_MISSING);
            return s.resolve();
        } catch (Halt h) {
            issue(h.reason, d, "dependency-resolution", s.usedEvidence());
            return s.row(h.outcome, h.operation, List.of(), null, false);
        }
    }
    private void evaluateGroup(Group group) {
        List<Row> completed = new ArrayList<>(); boolean stopped = false, skipped = false;
        boolean groupValid = valid(group.evidence(), build);
        var first = dependencies.get(group.dependencies().getFirst());
        groupValid &= group.dependencies().stream().map(dependencies::get).allMatch(d ->
                d.point().siteKind() == InjectionPoint.SiteKind.METHOD_PARAMETER && d.owner().equals(first.owner())
                        && d.point().ownerDeclarationKey().equals(first.point().ownerDeclarationKey()));
        for (var id : group.dependencies()) {
            var d = dependencies.get(id);
            Row row;
            if (!groupValid || stopped) {
                row = empty(d, skipped ? Outcome.GROUP_SKIPPED : Outcome.UNKNOWN, Operation.NOT_INVOKED);
                if (!skipped) issue(OPTIONAL_GROUP_UNKNOWN, d, group.key(), List.of(group.evidence()));
            } else {
                row = evaluate(d); completed.add(row);
                skipped = row.outcome() == Outcome.ABSENT_OPTIONAL && group.skipOnAbsent().contains(id);
                stopped = skipped || switch (row.outcome()) {
                    case UNKNOWN, ERROR, NOT_REACHED, UNSATISFIED, AMBIGUOUS, NOT_ACTIVE -> true;
                    default -> false;
                };
            }
            rows.put(id, row);
        }
        if (stopped || !groupValid) for (var old : completed) {
            if (old.outcome() == Outcome.SELECTED || old.outcome() == Outcome.AGGREGATE || old.outcome() == Outcome.ABSENT_OPTIONAL || old.outcome() == Outcome.DEFERRED) {
                rows.put(old.dependency(), new Row(old.dependency(), old.point(), skipped ? Outcome.GROUP_SKIPPED : Outcome.UNKNOWN,
                        Operation.NOT_INVOKED, List.of(), old.eligibleCandidates(), old.trace(), Optional.empty(), false));
                if (!skipped) issue(OPTIONAL_GROUP_UNKNOWN, dependencies.get(old.dependency()), group.key(), List.of(group.evidence()));
            }
        }
    }
    private Row empty(Dependency d, Outcome outcome, Operation operation) {
        return new Row(d.identity(), d.point().identity(), outcome, operation, List.of(), List.of(), List.of(), Optional.empty(), false);
    }
    private final class Session {
        final Dependency d;
        final List<CandidateTrace> trace = new ArrayList<>();
        final List<Target> eligible = new ArrayList<>();
        final Set<ConditionEvidence> evidence = new LinkedHashSet<>();
        Session(Dependency dependency) { d = dependency; evidence.add(d.evidence()); evidence.add(d.point().declarationEvidenceKey()); }
        Row resolve() {
            if (d.mode() != Mode.AUTOWIRE) {
                if (d.dependencyName().status() == NameStatus.UNKNOWN) throw halt(DEPENDENCY_NAME_UNKNOWN);
                String name = canonical(d.dependencyName().value().orElseThrow());
                if (registry.containsKey(name)) return named(name);
                if (d.mode() == Mode.EXPLICIT_REFERENCE || !d.resourceDefaultName() || !d.resourceTypeFallback())
                    throw new Halt(MISSING_REQUIRED_DEPENDENCY, Outcome.UNSATISFIED, Operation.ERROR);
            }
            if (d.deferred()) {
                issue(DEFERRED_RUNTIME_TARGET, d, "deferred-wrapper", List.of(d.evidence()));
                return row(Outcome.DEFERRED, Operation.DEFERRED, List.of(), null, false);
            }
            if (d.standardLookup()) {
                String name = shortcutName();
                if (name != null) {
                    var target = target(name);
                    var match = match(target, Lane.DIRECT);
                    var permitted = eligibility(target, match, false);
                    addTrace(target, Lane.DIRECT, Stage.NAME_SHORTCUT, permitted);
                    if (permitted == UNKNOWN) { unknownMatch(target, match, false); throw halt(MATCH_EVIDENCE_MISSING); }
                    if (permitted == TRUE) {
                        var definition = definition(target);
                        if (definition.fallback() == UNKNOWN) throw halt(FLAGS_UNKNOWN);
                        if (definition.fallback() == FALSE && !self(target) && !primaryConflict(target))
                            return selected(target, Stage.NAME_SHORTCUT);
                    }
                }
            }
            boolean standardAggregate = d.aggregate() && d.shape() != Shape.CUSTOM_COLLECTION && d.shape() != Shape.CUSTOM_MAP;
            if (standardAggregate) {
                var elements = find(Lane.ELEMENT);
                if (!elements.isEmpty()) return aggregate(elements);
            }
            var direct = find(Lane.DIRECT);
            if (!direct.isEmpty()) return select(direct);
            if (d.shape() == Shape.CUSTOM_COLLECTION || d.shape() == Shape.CUSTOM_MAP) {
                // Conversion to a custom collection interface needs provider-specific evidence.
                throw halt(UNSUPPORTED_SHAPE);
            }
            if (d.aggregate() && d.emptyAggregateFallback()) return row(Outcome.AGGREGATE, Operation.COMPLETE, List.of(), Stage.AGGREGATE, true);
            return absent();
        }
        Row named(String name) {
            var target = target(name); var m = match(target, Lane.DIRECT);
            addTrace(target, Lane.DIRECT, Stage.RESOURCE_NAME, m.rawType());
            if (m.rawType() == UNKNOWN) throw halt(TYPE_EVIDENCE_MISSING);
            if (m.rawType() == FALSE) throw new Halt(NAMED_TYPE_MISMATCH, Outcome.ERROR, Operation.ERROR);
            // resolveBeanByName/getBean(name,type) bypasses autowire/qualifier/default/primary filters.
            ordinary(target);
            return selected(target, Stage.RESOURCE_NAME);
        }
        String shortcutName() {
            if (d.dependencyName().status() == NameStatus.UNKNOWN) throw halt(DEPENDENCY_NAME_UNKNOWN);
            if (d.dependencyName().value().isPresent()) {
                String name = canonical(d.dependencyName().value().orElseThrow());
                if (registry.containsKey(name)) return name;
            }
            if (d.suggestedName().status() == NameStatus.UNKNOWN) throw halt(DEPENDENCY_NAME_UNKNOWN);
            if (d.suggestedName().value().isPresent()) {
                String name = canonical(d.suggestedName().value().orElseThrow());
                if (registry.containsKey(name)) return name;
            }
            return null;
        }
        boolean primaryConflict(Target selected) {
            if (registry.keySet().stream().anyMatch(aliases::containsKey)) throw halt(ALIAS_SHADOW_SHORTCUT_UNSUPPORTED);
            BindingProcessing.Reason missing = null;
            for (var candidate : targets()) if (!candidate.equals(selected)) {
                tick(); var def = definition(candidate);
                if (def.primary() == FALSE) continue;
                var m = match(candidate, Lane.DIRECT);
                var conflict = def.primary().and(m.rawType());
                if (conflict == TRUE) return true;
                if (conflict == UNKNOWN && missing == null) {
                    missing = def.primary() == UNKNOWN ? FLAGS_UNKNOWN : TYPE_EVIDENCE_MISSING;
                }
            }
            if (missing != null) throw halt(missing);
            return false;
        }
        List<Target> find(Lane lane) {
            var found = pass(lane, Stage.STRICT, false, false);
            if (!found.isEmpty()) return found;
            boolean multiple = lane == Lane.DIRECT ? d.aggregate() : truth(d.elementIndicatesMultiple(), DESCRIPTOR_INCOMPLETE);
            if (!multiple || truth(d.hasQualifier(), DESCRIPTOR_INCOMPLETE)) {
                found = pass(lane, Stage.GENERIC_FALLBACK, true, false);
                if (!found.isEmpty()) return found;
            }
            if (!multiple) return pass(lane, Stage.SELF_REFERENCE, true, true);
            return List.of();
        }
        List<Target> pass(Lane lane, Stage stage, boolean fallback, boolean selfPass) {
            List<Target> result = new ArrayList<>(); BindingProcessing.Reason firstUnknown = null;
            for (var target : targets()) {
                tick(); boolean self = self(target);
                if (self != selfPass || selfPass && lane == Lane.ELEMENT && d.owner().filter(target.candidate()::equals).isPresent()) continue;
                var match = match(target, lane); var value = match.typeLookup() == FALSE ? FALSE : match.typeLookup().and(eligibility(target, match, fallback));
                addTrace(target, lane, stage, value);
                if (value == TRUE) result.add(target);
                if (value == UNKNOWN) {
                    var r = unknownMatch(target, match, true);
                    if (firstUnknown == null) firstUnknown = r;
                }
            }
            eligible.clear(); eligible.addAll(result);
            if (firstUnknown != null) throw halt(firstUnknown);
            return result;
        }
        LogicalValue eligibility(Target target, Match match, boolean fallback) {
            if (match.rawType() == FALSE) return FALSE;
            var def = definition(target);
            var generic = fallback ? match.fallbackGeneric() : match.strictGeneric();
            var qualifier = d.hasQualifier() == TRUE ? match.qualifierMatch() : d.hasQualifier() == FALSE
                    ? def.defaultCandidate() : UNKNOWN;
            var result = match.rawType().and(generic).and(def.autowireCandidate()).and(qualifier);
            if (result != FALSE) ordinary(target);
            return result;
        }
        BindingProcessing.Reason unknownMatch(Target target, Match match, boolean enumeration) {
            var def = definitions.get(target.candidate());
            if (match.rawType() == UNKNOWN || enumeration && match.typeLookup() == UNKNOWN) {
                issue(TYPE_EVIDENCE_MISSING, d, target.beanName(), List.of(match.evidence()));
                return TYPE_EVIDENCE_MISSING;
            }
            if (def != null && (def.autowireCandidate() == UNKNOWN || d.hasQualifier() != TRUE && def.defaultCandidate() == UNKNOWN)) {
                issue(FLAGS_UNKNOWN, d, target.beanName(), List.of(def.evidence()));
                return FLAGS_UNKNOWN;
            }
            if (d.hasQualifier() == UNKNOWN) {
                issue(DESCRIPTOR_INCOMPLETE, d, target.beanName(), List.of(d.evidence()));
                return DESCRIPTOR_INCOMPLETE;
            }
            return MATCH_EVIDENCE_MISSING;
        }
        Row select(List<Target> candidates) {
            eligible.clear(); eligible.addAll(candidates);
            if (candidates.size() == 1) return selected(candidates.getFirst(), Stage.UNIQUE);
            var primaries = new ArrayList<Target>(); boolean primaryUnknown = false;
            for (var target : candidates) {
                tick(); var flag = definition(target).primary();
                if (flag == TRUE) primaries.add(target); primaryUnknown |= flag == UNKNOWN;
            }
            if (primaries.size() > 1) throw new Halt(PRIMARY_CONFLICT, Outcome.AMBIGUOUS, Operation.ERROR);
            if (primaryUnknown) throw halt(FLAGS_UNKNOWN);
            if (!primaries.isEmpty()) return selected(primaries.getFirst(), Stage.PRIMARY);
            var normal = new ArrayList<Target>(); boolean fallbackUnknown = false;
            for (var target : candidates) {
                tick(); var flag = definition(target).fallback();
                if (flag == FALSE) normal.add(target); fallbackUnknown |= flag == UNKNOWN;
            }
            if (normal.size() < 2 && fallbackUnknown) throw halt(FLAGS_UNKNOWN);
            if (normal.size() == 1) return selected(normal.getFirst(), Stage.NON_FALLBACK);
            var named = byName(candidates, d.dependencyName());
            if (named != null) return selected(named, Stage.DEPENDENCY_NAME);
            named = byName(candidates, d.suggestedName());
            if (named != null) return selected(named, Stage.SUGGESTED_NAME);
            if (plan.environment().comparator() == ComparatorPolicy.UNKNOWN) throw halt(COMPARATOR_UNKNOWN);
            if (plan.environment().comparator() == ComparatorPolicy.SPRING_ORDER) {
                Integer best = null; var highest = new ArrayList<Target>();
                for (var target : candidates) {
                    tick(); var priority = definition(target).priority();
                    if (priority.knowledge() == Knowledge.ERROR) throw new Halt(MATCH_OPERATION_ERROR, Outcome.ERROR, Operation.ERROR);
                    if (priority.knowledge() == Knowledge.UNKNOWN) throw halt(PRIORITY_UNKNOWN);
                    if (priority.value().isEmpty()) continue;
                    int value = priority.value().orElseThrow();
                    if (best == null || value < best) { best = value; highest.clear(); highest.add(target); }
                    else if (value == best) highest.add(target);
                }
                if (highest.size() > 1) throw new Halt(PRIORITY_CONFLICT, Outcome.AMBIGUOUS, Operation.ERROR);
                if (highest.size() == 1) return selected(highest.getFirst(), Stage.PRIORITY);
            }
            // 6.2.0 has NO later determineDefaultCandidate rule. Optional scalar ambiguity is still an error.
            if (d.aggregate() && d.required() == Required.OPTIONAL) return row(Outcome.ABSENT_OPTIONAL, Operation.COMPLETE, List.of(), null, true);
            throw new Halt(NON_UNIQUE_DEPENDENCY, Outcome.AMBIGUOUS, Operation.ERROR);
        }
        Target byName(List<Target> candidates, Name name) {
            if (name.status() == NameStatus.UNKNOWN) throw halt(DEPENDENCY_NAME_UNKNOWN);
            if (name.value().isEmpty()) return null;
            String canonical = canonical(name.value().orElseThrow());
            return candidates.stream().filter(c -> c.beanName().equals(canonical)).findFirst().orElse(null);
        }
        Row aggregate(List<Target> members) {
            eligible.clear(); eligible.addAll(members);
            var ordered = new ArrayList<>(members);
            var index = new HashMap<String, Integer>();
            for (int i = 0; i < plan.environment().candidateOrder().size(); i++) index.put(plan.environment().candidateOrder().get(i), i);
            boolean orderKnown = plan.environment().candidateOrderComplete() == COMPLETE
                    && members.stream().allMatch(t -> index.containsKey(t.beanName()));
            if (orderKnown) ordered.sort(Comparator.comparingInt(t -> index.get(t.beanName())));
            boolean sorted = d.shape() == Shape.LIST || d.shape() == Shape.COLLECTION || d.shape() == Shape.ARRAY;
            if (sorted && members.size() > 1) {
                if (plan.environment().comparator() == ComparatorPolicy.UNKNOWN) orderKnown = false;
                else if (plan.environment().comparator() == ComparatorPolicy.SPRING_ORDER) {
                    boolean metadataKnown = true;
                    for (var t : ordered) {
                        tick(); var def = definition(t);
                        if (def.order().knowledge() == Knowledge.ERROR) throw new Halt(MATCH_OPERATION_ERROR, Outcome.ERROR, Operation.ERROR);
                        metadataKnown &= def.order().knowledge() == Knowledge.KNOWN && def.priorityOrdered() != UNKNOWN;
                    }
                    if (metadataKnown) {
                        Comparator<Target> comparator = Comparator.comparingInt((Target t) -> definition(t).priorityOrdered() == TRUE ? 0 : 1)
                                .thenComparingInt(t -> definition(t).order().value().orElse(Integer.MAX_VALUE));
                        ordered.sort(comparator);
                        if (!orderKnown) orderKnown = java.util.stream.IntStream.range(1, ordered.size())
                                .allMatch(i -> comparator.compare(ordered.get(i - 1), ordered.get(i)) != 0);
                    } else orderKnown = false;
                }
            }
            if (members.size() < 2) orderKnown = true;
            if (!orderKnown) {
                ordered.sort(Comparator.comparing(Target::beanName));
                issue(AGGREGATE_ORDER_UNKNOWN, d, "aggregate-membership-only", usedEvidence());
            }
            return row(Outcome.AGGREGATE, orderKnown ? Operation.COMPLETE : Operation.INCOMPLETE, ordered, Stage.AGGREGATE, orderKnown);
        }
        Row selected(Target target, Stage stage) {
            if (!eligible.contains(target)) eligible.add(target);
            return row(Outcome.SELECTED, Operation.COMPLETE, List.of(target), stage, true);
        }
        Row absent() {
            if (d.shape() == Shape.OPTIONAL || d.mode() == Mode.AUTOWIRE && d.required() == Required.OPTIONAL)
                return row(Outcome.ABSENT_OPTIONAL, Operation.COMPLETE, List.of(), null, true);
            throw new Halt(MISSING_REQUIRED_DEPENDENCY, Outcome.UNSATISFIED, Operation.ERROR);
        }
        Row row(Outcome outcome, Operation operation, List<Target> selected, Stage stage, boolean ordered) {
            return new Row(d.identity(), d.point().identity(), outcome, operation, selected, eligible, trace, Optional.ofNullable(stage), ordered);
        }
        List<Target> targets() {
            return registry.entrySet().stream().filter(e -> !aliases.containsKey(e.getKey()))
                    .map(e -> new Target(e.getKey(), e.getValue())).toList();
        }
        Target target(String name) { return new Target(name, registry.get(name)); }
        Definition definition(Target target) {
            var def = definitions.get(target.candidate());
            if (def == null) throw halt(FLAGS_UNKNOWN);
            evidence(def.evidence());
            var earlier = registeredMetadata.get(target.candidate());
            if (earlier != null && (conflicts(def.autowireCandidate(), earlier.autowireCandidate())
                    || conflicts(def.defaultCandidate(), earlier.defaultCandidate()) || conflicts(def.primary(), earlier.primary())
                    || conflicts(def.fallback(), earlier.fallback())
                    || earlier.factoryBean() == TRUE && def.runtimeKind() == RuntimeKind.ORDINARY
                    || earlier.factoryBean() == FALSE && def.runtimeKind() == RuntimeKind.FACTORY_BEAN)) {
                evidence.add(earlier.evidence()); throw halt(METADATA_CONFLICT);
            }
            return def;
        }
        void ordinary(Target target) {
            if (definition(target).runtimeKind() != RuntimeKind.ORDINARY) throw halt(FACTORY_OR_PROXY_UNSUPPORTED);
        }
        boolean self(Target target) {
            if (d.owner().isEmpty()) return false;
            return target.candidate().equals(d.owner().orElseThrow()) || definition(target).factoryOwner().equals(d.owner());
        }
        Match match(Target target, Lane lane) {
            tick(); var m = matches.get(d.identity().value() + ":" + target.candidate().value() + ":" + lane);
            if (m == null) throw halt(MATCH_EVIDENCE_MISSING);
            evidence(m.evidence());
            if (m.operation() == Knowledge.ERROR) throw new Halt(MATCH_OPERATION_ERROR, Outcome.ERROR, Operation.ERROR);
            if (m.operation() == Knowledge.UNKNOWN) throw halt(MATCH_EVIDENCE_MISSING);
            if (m.typeLookup() == TRUE && m.rawType() == FALSE) throw halt(METADATA_CONFLICT);
            return m;
        }
        void evidence(ConditionEvidence value) {
            evidence.add(value);
            if (!valid(value, build)) throw halt(SOURCE_EVIDENCE_MISMATCH);
        }
        List<ConditionEvidence> usedEvidence() { return List.copyOf(evidence); }
        void addTrace(Target target, Lane lane, Stage stage, LogicalValue value) {
            if (++traceRows > plan.limits().maxTraceRows()) throw halt(BINDING_LIMIT);
            var direct = new ArrayList<ConditionEvidence>(); direct.add(d.evidence());
            var def = definitions.get(target.candidate()); if (def != null) direct.add(def.evidence());
            var match = matches.get(d.identity().value() + ":" + target.candidate().value() + ":" + lane);
            if (match != null) direct.add(match.evidence());
            trace.add(new CandidateTrace(target, lane, stage, value, direct));
        }
    }
    private static boolean conflicts(LogicalValue a, LogicalValue b) { return a != UNKNOWN && b != UNKNOWN && a != b; }
    private String canonical(String name) {
        if (name.startsWith("&")) throw halt(FACTORY_OR_PROXY_UNSUPPORTED);
        Set<String> seen = new HashSet<>();
        while (aliases.containsKey(name)) {
            tick(); if (!seen.add(name)) throw halt(INVALID_ALIAS_STATE);
            name = aliases.get(name);
        }
        return name;
    }
    private boolean truth(LogicalValue value, BindingProcessing.Reason reason) {
        if (value == UNKNOWN) throw halt(reason); return value == TRUE;
    }
    private void tick() { if (++checks > plan.limits().maxCandidateChecks()) throw halt(BINDING_LIMIT); }
    private void issue(BindingProcessing.Reason reason, Dependency d, String subject, List<ConditionEvidence> evidence) {
        issues.add(new BindingProcessing.Issue(reason, Optional.ofNullable(d).map(Dependency::identity), subject, evidence));
    }
    private static Halt halt(BindingProcessing.Reason reason) {
        return new Halt(reason, Outcome.UNKNOWN, reason == BINDING_LIMIT ? Operation.LIMIT_EXCEEDED : Operation.INCOMPLETE);
    }
    /** Internal deterministic short-circuit only. Provider exceptions are never swallowed. */
    private static final class Halt extends RuntimeException {
        final BindingProcessing.Reason reason; final Outcome outcome; final Operation operation;
        Halt(BindingProcessing.Reason reason, Outcome outcome, Operation operation) {
            super(reason.name(), null, false, false); this.reason = reason; this.outcome = outcome; this.operation = operation;
        }
    }
}
