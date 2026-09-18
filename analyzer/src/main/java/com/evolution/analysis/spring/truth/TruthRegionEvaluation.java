package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.ConfigurationIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.spring.binding.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import java.math.BigInteger;
import java.util.*;

import static com.evolution.analysis.spring.condition.LogicalValue.*;

/**
 * Bounded M4D aggregation of evidenced M4C registration/binding results over one finite
 * configuration space. This provider performs no I/O, class loading or target execution.
 */
public final class TruthRegionEvaluation {
    public static final VersionedIdentifier REGION_ENCODING =
            new VersionedIdentifier("spring.truth-region-encoding", "explicit-worlds-m4d-v1");
    public static final VersionedIdentifier WITNESS_POLICY =
            new VersionedIdentifier("spring.witness-policy", "minimum-cardinality-revalidated-m4d-v1");
    public static final VersionedIdentifier BASELINE_CONFIGURATION_SCHEMA =
            new VersionedIdentifier("spring.realized-configuration", "m4d-v1");

    public enum Classification { MUST, MAY, NEVER, UNKNOWN }
    public enum Feasibility { FEASIBLE, UNKNOWN, INVALID_MODEL }
    public enum OperationalStatus { COMPLETE, PARTIAL, LIMIT_EXCEEDED, ERROR, NOT_EVALUATED }

    public record Limits(FiniteConfigurationEvaluation.Limits enumeration, int maxFacts,
                         long maxRegionCells, int maxWitnessReplays) {
        public Limits {
            Objects.requireNonNull(enumeration);
            if (maxFacts < 1 || maxRegionCells < 1 || maxWitnessReplays < 0)
                throw new IllegalArgumentException("Invalid truth-region limits");
        }
        public static Limits conservative() {
            return new Limits(FiniteConfigurationEvaluation.Limits.conservative(), 100_000, 1_000_000, 10_000);
        }
    }

    public record Request(InjectionBindingPlan bindingPlan, ConditionModel conditionModel,
                          ConditionExpression.Semantics conditionSemantics,
                          ConfigurationAssignment baseline, ConfigurationReasoner reasoner,
                          Limits limits, ContentDigest evaluatorArtifactDigest) {
        public Request {
            Objects.requireNonNull(bindingPlan); Objects.requireNonNull(conditionModel);
            Objects.requireNonNull(conditionSemantics); Objects.requireNonNull(baseline);
            Objects.requireNonNull(reasoner); Objects.requireNonNull(limits);
            Objects.requireNonNull(evaluatorArtifactDigest);
        }
    }

    public record ContextIdentity(String value) implements CanonicalIdentifier, Comparable<ContextIdentity> {
        public ContextIdentity { value = TruthIdentity.require(value, "spring-semantics-context"); }
        @Override public int compareTo(ContextIdentity other) { return value.compareTo(other.value); }
    }
    public record LegalOrderIdentity(String value) implements CanonicalIdentifier, Comparable<LegalOrderIdentity> {
        public LegalOrderIdentity { value = TruthIdentity.require(value, "spring-legal-order"); }
        @Override public int compareTo(LegalOrderIdentity other) { return value.compareTo(other.value); }
    }
    public record RegionIdentity(String value) implements CanonicalIdentifier, Comparable<RegionIdentity> {
        public RegionIdentity { value = TruthIdentity.require(value, "spring-fact-region"); }
        @Override public int compareTo(RegionIdentity other) { return value.compareTo(other.value); }
    }
    public record WitnessIdentity(String value) implements CanonicalIdentifier, Comparable<WitnessIdentity> {
        public WitnessIdentity { value = TruthIdentity.require(value, "spring-witness"); }
        @Override public int compareTo(WitnessIdentity other) { return value.compareTo(other.value); }
    }
    public record EvaluationIdentity(String value) implements CanonicalIdentifier, Comparable<EvaluationIdentity> {
        public EvaluationIdentity { value = TruthIdentity.require(value, "spring-evaluation-result"); }
        @Override public int compareTo(EvaluationIdentity other) { return value.compareTo(other.value); }
    }

    /** Exact differences from the declared baseline; omitted dimensions inherit the baseline. */
    public record ValueChange(FiniteDomain.Variable variable, FiniteDomain.Value value) {
        public ValueChange { Objects.requireNonNull(variable); Objects.requireNonNull(value); }
    }

    public record DeltaAssignment(List<ValueChange> baselineValues,
                                  List<ConfigurationAssignment.SourceChoice> sourceChoices) {
        public DeltaAssignment {
            baselineValues = baselineValues.stream().sorted(Comparator.comparing(ValueChange::variable)).toList();
            sourceChoices = sourceChoices.stream().sorted(Comparator.comparing(c -> TruthIdentity.digest(c).value())).toList();
        }
        public int changedDimensions() { return baselineValues.size() + sourceChoices.size(); }
    }

    public record OperationalOutcome(ConfigurationReasoner.WorldKey world, OperationalStatus status,
                                     Optional<ContentDigest> resultIdentity, List<String> reasons) {
        public OperationalOutcome {
            Objects.requireNonNull(world); Objects.requireNonNull(status); Objects.requireNonNull(resultIdentity);
            reasons = reasons.stream().distinct().sorted().toList();
        }
    }

    public record World(ConfigurationReasoner.WorldKey identity, ConfigurationAssignment assignment,
                        LogicalValue feasibility, Optional<ContentDigest> exogenousEvaluationIdentity,
                        Optional<ContentDigest> bindingResultIdentity,
                        Map<ConditionalFactKey.Identity, LogicalValue> facts,
                        OperationalStatus operationalStatus) {
        public World {
            Objects.requireNonNull(identity); Objects.requireNonNull(assignment); Objects.requireNonNull(feasibility);
            Objects.requireNonNull(exogenousEvaluationIdentity); Objects.requireNonNull(bindingResultIdentity);
            facts = Collections.unmodifiableMap(new TreeMap<>(facts));
            Objects.requireNonNull(operationalStatus);
        }
        public Object canonicalForm() {
            return Map.of("identity", identity, "assignment", assignment.canonicalForm(),
                    "feasibility", feasibility, "exogenousEvaluationIdentity", exogenousEvaluationIdentity,
                    "bindingResultIdentity", bindingResultIdentity,
                    "facts", facts.entrySet().stream().map(entry -> Map.of(
                            "fact", entry.getKey(), "truth", entry.getValue())).toList(),
                    "operationalStatus", operationalStatus);
        }
    }

    public record RegionFeasibility(Feasibility status, boolean enumerationComplete,
                                    int feasibleWorlds, int unknownFeasibilityWorlds,
                                    BigInteger declaredAssignments) {
        public RegionFeasibility {
            Objects.requireNonNull(status); Objects.requireNonNull(declaredAssignments);
            if (feasibleWorlds < 0 || unknownFeasibilityWorlds < 0 || declaredAssignments.signum() < 0)
                throw new IllegalArgumentException("Invalid region feasibility summary");
        }
    }

    public record Region(ConditionalFactKey fact, List<ConfigurationReasoner.WorldKey> trueWorlds,
                         List<ConfigurationReasoner.WorldKey> falseWorlds,
                         List<ConfigurationReasoner.WorldKey> unknownWorlds,
                         RegionFeasibility feasibility, Classification classification,
                         RegionIdentity identity) {
        public Region {
            Objects.requireNonNull(fact); trueWorlds = distinct(trueWorlds); falseWorlds = distinct(falseWorlds);
            unknownWorlds = distinct(unknownWorlds); Objects.requireNonNull(feasibility);
            Objects.requireNonNull(classification); Objects.requireNonNull(identity);
            var all = new HashSet<ConfigurationReasoner.WorldKey>();
            if (!add(all, trueWorlds) || !add(all, falseWorlds) || !add(all, unknownWorlds))
                throw new IllegalArgumentException("T/F/U regions must be disjoint");
            if (all.size() != feasibility.feasibleWorlds())
                throw new IllegalArgumentException("T/F/U regions must exhaust known feasible worlds");
        }
        private static List<ConfigurationReasoner.WorldKey> distinct(List<ConfigurationReasoner.WorldKey> values) {
            return List.copyOf(new TreeSet<>(values));
        }
        private static boolean add(Set<ConfigurationReasoner.WorldKey> target,
                                   List<ConfigurationReasoner.WorldKey> values) {
            for (var value : values) if (!target.add(value)) return false;
            return true;
        }
    }

    public record ConfigurationWitness(RegionIdentity factRegionIdentity,
                                       ConfigurationIdentity baselineConfigurationIdentity,
                                       ConfigurationAssignment fullAssignment,
                                       DeltaAssignment deltaAssignment,
                                       LegalOrderIdentity legalOrderIdentity,
                                       List<ContentDigest> branchEvidence,
                                       LogicalValue expectedTruth,
                                       VersionedIdentifier witnessPolicyVersion,
                                       WitnessIdentity identity) {
        public ConfigurationWitness {
            Objects.requireNonNull(factRegionIdentity); Objects.requireNonNull(baselineConfigurationIdentity);
            Objects.requireNonNull(fullAssignment); Objects.requireNonNull(deltaAssignment);
            Objects.requireNonNull(legalOrderIdentity); branchEvidence = branchEvidence.stream().distinct().sorted().toList();
            Objects.requireNonNull(expectedTruth); Objects.requireNonNull(witnessPolicyVersion); Objects.requireNonNull(identity);
        }
        public Object canonicalForm() {
            return Map.ofEntries(Map.entry("factRegionIdentity", factRegionIdentity),
                    Map.entry("baselineConfigurationIdentity", baselineConfigurationIdentity),
                    Map.entry("fullAssignment", fullAssignment.canonicalForm()),
                    Map.entry("deltaAssignment", deltaAssignment), Map.entry("legalOrderIdentity", legalOrderIdentity),
                    Map.entry("branchEvidence", branchEvidence), Map.entry("expectedTruth", expectedTruth),
                    Map.entry("witnessPolicyVersion", witnessPolicyVersion), Map.entry("identity", identity));
        }
    }

    public record WitnessReplay(WitnessIdentity witness, LogicalValue actualTruth,
                                LogicalValue assignmentFeasibility, boolean valid,
                                ContentDigest independentEvaluationDigest) {
        public WitnessReplay {
            Objects.requireNonNull(witness); Objects.requireNonNull(actualTruth);
            Objects.requireNonNull(assignmentFeasibility); Objects.requireNonNull(independentEvaluationDigest);
        }
    }

    public record Coverage(ConfigurationSpace.CartesianSize declaredAssignments, int enumeratedAssignments,
                           int feasibleAssignments, int infeasibleAssignments, int unknownFeasibilityAssignments,
                           int inputFacts, int emittedRegions, int withheldFacts, long regionCells,
                           boolean complete) {
        public Coverage {
            Objects.requireNonNull(declaredAssignments);
            if (enumeratedAssignments < 0 || feasibleAssignments < 0 || infeasibleAssignments < 0
                    || unknownFeasibilityAssignments < 0 || inputFacts < 0 || emittedRegions < 0
                    || withheldFacts < 0 || regionCells < 0
                    || (long) feasibleAssignments + infeasibleAssignments + unknownFeasibilityAssignments != enumeratedAssignments
                    || (long) emittedRegions + withheldFacts != inputFacts)
                throw new IllegalArgumentException("Truth-region denominator is not closed");
        }
    }

    public static final class Result {
        private final ContentDigest input;
        private final ContextIdentity context;
        private final LegalOrderIdentity legalOrder;
        private final List<World> worlds;
        private final List<Region> regions;
        private final List<ConfigurationWitness> witnesses;
        private final List<WitnessReplay> replays;
        private final List<OperationalOutcome> operations;
        private final List<TruthProcessing.Issue> issues;
        private final List<CapabilityGapRecord> gaps;
        private final Coverage coverage;
        private final Feasibility feasibility;
        private final ContentDigest evaluatorArtifact;

        private Result(Evaluation evaluation, List<CapabilityGapRecord> gaps) {
            input = evaluation.input; context = evaluation.context; legalOrder = evaluation.legalOrder;
            worlds = List.copyOf(evaluation.worlds); regions = List.copyOf(evaluation.regions);
            witnesses = List.copyOf(evaluation.witnesses); replays = List.copyOf(evaluation.replays);
            operations = List.copyOf(evaluation.operations);
            issues = evaluation.issues.stream().distinct()
                    .sorted(Comparator.comparing(TruthProcessing.Issue::identity)).toList();
            this.gaps = List.copyOf(new TreeSet<>(gaps)); coverage = evaluation.coverage();
            feasibility = evaluation.feasibility(); evaluatorArtifact = evaluation.request.evaluatorArtifactDigest();
        }
        public ContentDigest inputIdentity() { return input; }
        public ContextIdentity semanticsContextIdentity() { return context; }
        public LegalOrderIdentity legalOrderIdentity() { return legalOrder; }
        public List<World> worlds() { return worlds; }
        public List<Region> regions() { return regions; }
        public List<ConfigurationWitness> witnesses() { return witnesses; }
        public List<WitnessReplay> replays() { return replays; }
        public List<OperationalOutcome> operationalOutcomes() { return operations; }
        public List<TruthProcessing.Issue> issues() { return issues; }
        public List<CapabilityGapRecord> capabilityGaps() { return gaps; }
        public Coverage coverage() { return coverage; }
        public Feasibility feasibility() { return feasibility; }
        public EvaluationIdentity identity() {
            return new EvaluationIdentity(TruthIdentity.derive("spring-evaluation-result", Map.of(
                    "semanticsContextIdentity", context,
                    "regionIdentities", regions.stream().map(Region::identity).toList(),
                    "witnessIdentities", witnesses.stream().map(ConfigurationWitness::identity).toList(),
                    "operationalOutcomeDigest", TruthIdentity.digest(operations),
                    "evidenceLedgerDigest", TruthIdentity.digest(gaps),
                    "evaluatorArtifactDigest", evaluatorArtifact)));
        }
        public Object canonicalForm() {
            return Map.ofEntries(Map.entry("schema", "spring-truth-region-result-v1"),
                    Map.entry("provider", TruthProcessing.PROVIDER), Map.entry("inputIdentity", input),
                    Map.entry("semanticsContextIdentity", context), Map.entry("legalOrderIdentity", legalOrder),
                    Map.entry("worlds", worlds.stream().map(World::canonicalForm).toList()),
                    Map.entry("regions", regions), Map.entry("witnesses", witnesses.stream().map(ConfigurationWitness::canonicalForm).toList()),
                    Map.entry("replays", replays), Map.entry("operationalOutcomes", operations),
                    Map.entry("issues", issues), Map.entry("capabilityGaps", gaps),
                    Map.entry("coverage", coverage), Map.entry("feasibility", feasibility),
                    Map.entry("identity", identity()));
        }
    }

    private TruthRegionEvaluation() {}

    public static Result evaluate(Request request) {
        var evaluation = new Evaluation(request);
        evaluation.run();
        var gaps = new TreeSet<CapabilityGapRecord>(evaluation.inheritedGaps);
        gaps.addAll(TruthProcessing.gaps(evaluation.input, evaluation.build, evaluation.issues));
        return new Result(evaluation, List.copyOf(gaps));
    }

    private static final class Evaluation {
        final Request request;
        final InjectionBindingPlan bindingPlan;
        final BeanRegistrationPlan registrationPlan;
        final RegistrationPlan discoveryPlan;
        final SpringBuildContext build;
        final List<ConditionalFactKey> allFacts;
        final List<ConditionalFactKey> facts;
        final ContextIdentity context;
        final LegalOrderIdentity legalOrder;
        final ContentDigest input;
        final List<World> worlds = new ArrayList<>();
        final List<Region> regions = new ArrayList<>();
        final List<ConfigurationWitness> witnesses = new ArrayList<>();
        final List<WitnessReplay> replays = new ArrayList<>();
        final List<OperationalOutcome> operations = new ArrayList<>();
        final List<TruthProcessing.Issue> issues = new ArrayList<>();
        final List<CapabilityGapRecord> inheritedGaps = new ArrayList<>();
        final Map<ConfigurationReasoner.WorldKey, World> worldsById = new TreeMap<>();
        final Map<ContentDigest, InjectionBindingPlan.Dependency> dependencies = new HashMap<>();
        final boolean contextMatches;
        FiniteConfigurationEvaluation.Result enumeration;
        boolean regionLimit;
        boolean baselineValid;

        Evaluation(Request request) {
            this.request = request; bindingPlan = request.bindingPlan();
            registrationPlan = bindingPlan.registrationPlan(); discoveryPlan = registrationPlan.discoveryPlan();
            build = discoveryPlan.buildContext();
            bindingPlan.dependencies().forEach(d -> dependencies.put(d.identity(), d));
            allFacts = deriveFacts(bindingPlan, dependencies);
            facts = allFacts.stream().limit(request.limits().maxFacts()).toList();
            context = contextIdentity(request);
            legalOrder = legalOrderIdentity(registrationPlan);
            input = TruthIdentity.digest(Map.ofEntries(
                    Map.entry("schema", "spring-truth-region-input-v1"),
                    Map.entry("provider", TruthProcessing.PROVIDER),
                    Map.entry("context", context), Map.entry("bindingPlan", bindingPlan.identity()),
                    Map.entry("conditionModel", request.conditionModel().identity()),
                    Map.entry("conditionSemantics", request.conditionSemantics()),
                    Map.entry("baseline", request.baseline().identity()),
                    Map.entry("reasoner", request.reasoner().policy()),
                    Map.entry("limits", request.limits()),
                    Map.entry("evaluatorArtifact", request.evaluatorArtifactDigest())));
            contextMatches = build.identity().equals(request.conditionModel().space().buildContext().identity());
        }

        void run() {
            if (!contextMatches) issue(TruthProcessing.Reason.CONTEXT_MISMATCH, "build-context", Optional.empty());
            if (allFacts.size() > facts.size()) issue(TruthProcessing.Reason.FACT_LIMIT, "fact-denominator", Optional.empty());
            enumeration = request.reasoner().enumerate(request.conditionModel(), request.conditionSemantics(),
                    request.limits().enumeration());
            inheritedGaps.addAll(enumeration.capabilityGaps());
            if (!enumeration.coverage().complete()) issue(TruthProcessing.Reason.ENUMERATION_INCOMPLETE,
                    enumeration.inputIdentity().value(), Optional.empty());
            if (enumeration.feasibility() == FiniteConfigurationEvaluation.Feasibility.UNKNOWN)
                issue(TruthProcessing.Reason.FEASIBILITY_UNKNOWN, enumeration.inputIdentity().value(), Optional.empty());
            if (enumeration.feasibility() == FiniteConfigurationEvaluation.Feasibility.INVALID_MODEL)
                issue(TruthProcessing.Reason.INVALID_MODEL, request.conditionModel().space().identity().value(), Optional.empty());
            baselineValid = enumeration.assignments().stream().anyMatch(a -> projection(a).equals(projection(request.baseline())));
            if (!baselineValid && enumeration.feasibility() != FiniteConfigurationEvaluation.Feasibility.INVALID_MODEL)
                issue(TruthProcessing.Reason.BASELINE_INVALID, request.baseline().identity().value(), Optional.empty());
            evaluateWorlds();
            aggregateRegions();
            createWitnesses();
        }

        void evaluateWorlds() {
            long cells = 0;
            for (int index = 0; index < enumeration.assignments().size(); index++) {
                var assignment = enumeration.assignments().get(index);
                var exogenous = enumeration.evaluations().get(index);
                var worldKey = worldIdentity(assignment);
                var values = new TreeMap<ConditionalFactKey.Identity, LogicalValue>();
                Optional<InjectionBindings.Result> binding = Optional.empty();
                OperationalStatus status = OperationalStatus.NOT_EVALUATED;
                List<String> reasons = new ArrayList<>();
                if (exogenous.assignmentFeasibility() == TRUE) {
                    cells += facts.size();
                    if (cells > request.limits().maxRegionCells()) {
                        if (!regionLimit) issue(TruthProcessing.Reason.REGION_LIMIT, "region-cells", Optional.empty());
                        regionLimit = true; status = OperationalStatus.LIMIT_EXCEEDED; reasons.add("REGION_LIMIT");
                    } else if (!contextMatches) {
                        status = OperationalStatus.NOT_EVALUATED; reasons.add("CONTEXT_MISMATCH");
                    } else {
                        var result = InjectionBindings.evaluate(bindingPlan, request.conditionModel(), assignment,
                                request.limits().enumeration().perAssignment());
                        binding = Optional.of(result); inheritedGaps.addAll(result.capabilityGaps());
                        status = operationalStatus(result); reasons.addAll(operationalReasons(result));
                    }
                    for (var fact : facts) values.put(fact.identity(),
                            binding.map(result -> factTruth(fact, result, dependencies)).orElse(UNKNOWN));
                } else if (exogenous.assignmentFeasibility() == FALSE) {
                    status = OperationalStatus.NOT_EVALUATED; reasons.add("INFEASIBLE_ASSIGNMENT");
                } else {
                    status = OperationalStatus.NOT_EVALUATED; reasons.add("FEASIBILITY_UNKNOWN");
                }
                var world = new World(worldKey, assignment, exogenous.assignmentFeasibility(),
                        Optional.of(exogenous.identity()), binding.map(InjectionBindings.Result::identity), values, status);
                worlds.add(world); worldsById.put(worldKey, world);
                operations.add(new OperationalOutcome(worldKey, status, binding.map(InjectionBindings.Result::identity), reasons));
                if (status == OperationalStatus.ERROR || status == OperationalStatus.LIMIT_EXCEEDED)
                    issue(TruthProcessing.Reason.OPERATIONAL_FAILURE, worldKey.value(), Optional.empty());
            }
        }

        void aggregateRegions() {
            var knownWorlds = worlds.stream().filter(w -> w.feasibility() == TRUE).map(World::identity).toList();
            var summary = new RegionFeasibility(feasibility(), enumeration.coverage().complete(), knownWorlds.size(),
                    enumeration.coverage().unknownAssignments(), enumeration.coverage().assignmentCount().value());
            boolean classifiable = contextMatches && !regionLimit && enumeration.coverage().complete()
                    && enumeration.coverage().unknownAssignments() == 0 && feasibility() == Feasibility.FEASIBLE;
            for (var fact : facts) {
                List<ConfigurationReasoner.WorldKey> yes = new ArrayList<>(), no = new ArrayList<>(), unknown = new ArrayList<>();
                for (var world : worlds) if (world.feasibility() == TRUE) {
                    switch (world.facts().getOrDefault(fact.identity(), UNKNOWN)) {
                        case TRUE -> yes.add(world.identity());
                        case FALSE -> no.add(world.identity());
                        case UNKNOWN -> unknown.add(world.identity());
                    }
                }
                var classification = classify(classifiable, knownWorlds.size(), yes.size(), no.size(), unknown.size());
                var regionIdentity = new RegionIdentity(TruthIdentity.derive("spring-fact-region", Map.of(
                        "factKey", fact.identity(), "semanticsContextIdentity", context,
                        "regionEncodingVersion", REGION_ENCODING, "trueRegion", yes,
                        "falseRegion", no, "unknownRegion", unknown, "feasibilityStatus", summary)));
                regions.add(new Region(fact, yes, no, unknown, summary, classification, regionIdentity));
            }
        }

        void createWitnesses() {
            if (!baselineValid || request.limits().maxWitnessReplays() == 0) {
                if (baselineValid && !regions.isEmpty()) issue(TruthProcessing.Reason.WITNESS_LIMIT, "witness-replays", Optional.empty());
                return;
            }
            int used = 0;
            for (var region : regions) {
                for (var truth : List.of(TRUE, FALSE, UNKNOWN)) {
                    var candidates = switch (truth) {
                        case TRUE -> region.trueWorlds(); case FALSE -> region.falseWorlds(); case UNKNOWN -> region.unknownWorlds();
                    };
                    if (candidates.isEmpty()) continue;
                    if (used >= request.limits().maxWitnessReplays()) {
                        issue(TruthProcessing.Reason.WITNESS_LIMIT, region.identity().value(), Optional.of(region.fact().identity()));
                        return;
                    }
                    var selected = candidates.stream().map(worldsById::get)
                            .filter(this::witnessEligible)
                            .min(Comparator.comparingInt((World w) -> delta(w.assignment()).changedDimensions())
                                    .thenComparing(w -> TruthIdentity.digest(w.assignment().canonicalForm()).value()))
                            .orElse(null);
                    if (selected == null) continue;
                    var delta = delta(selected.assignment());
                    var identity = new WitnessIdentity(TruthIdentity.derive("spring-witness", Map.ofEntries(
                            Map.entry("factRegionIdentity", region.identity()),
                            Map.entry("baselineConfigurationIdentity", baselineIdentity(request.baseline())),
                            Map.entry("fullAssignment", selected.assignment().canonicalForm()),
                            Map.entry("deltaAssignment", delta), Map.entry("legalOrderIdentity", legalOrder),
                            Map.entry("branchEvidence", List.of()), Map.entry("expectedTruth", truth),
                            Map.entry("witnessPolicyVersion", WITNESS_POLICY))));
                    var witness = new ConfigurationWitness(region.identity(), baselineIdentity(request.baseline()),
                            selected.assignment(), delta, legalOrder, List.of(), truth, WITNESS_POLICY, identity);
                    var replay = replay(witness, region.fact()); replays.add(replay); used++;
                    if (!replay.valid()) issue(TruthProcessing.Reason.WITNESS_REPLAY_FAILED,
                            witness.identity().value(), Optional.of(region.fact().identity()));
                    else witnesses.add(witness);
                }
            }
        }

        WitnessReplay replay(ConfigurationWitness witness, ConditionalFactKey fact) {
            var exogenous = ExogenousConditionEvaluator.evaluate(request.conditionModel(), request.conditionSemantics(),
                    witness.fullAssignment(), request.limits().enumeration().perAssignment());
            LogicalValue actual = UNKNOWN;
            Optional<ContentDigest> bindingIdentity = Optional.empty();
            if (contextMatches && exogenous.assignmentFeasibility() == TRUE) {
                var result = InjectionBindings.evaluate(bindingPlan, request.conditionModel(), witness.fullAssignment(),
                        request.limits().enumeration().perAssignment());
                actual = factTruth(fact, result, dependencies); bindingIdentity = Optional.of(result.identity());
            }
            boolean valid = exogenous.assignmentFeasibility() == TRUE && actual == witness.expectedTruth();
            var digest = TruthIdentity.digest(Map.of("schema", "spring-witness-replay-v1",
                    "witness", witness.identity(), "exogenous", exogenous.identity(),
                    "binding", bindingIdentity, "actualTruth", actual,
                    "assignmentFeasibility", exogenous.assignmentFeasibility(),
                    "evaluatorArtifactDigest", request.evaluatorArtifactDigest()));
            return new WitnessReplay(witness.identity(), actual, exogenous.assignmentFeasibility(), valid, digest);
        }

        boolean witnessEligible(World world) {
            return world.bindingResultIdentity().isPresent()
                    && (world.operationalStatus() == OperationalStatus.COMPLETE
                    || world.operationalStatus() == OperationalStatus.PARTIAL);
        }

        Coverage coverage() {
            long cells = (long) facts.size() * enumeration.coverage().feasibleAssignments();
            return new Coverage(enumeration.coverage().assignmentCount(), enumeration.coverage().evaluatedAssignments(),
                    enumeration.coverage().feasibleAssignments(), enumeration.coverage().infeasibleAssignments(),
                    enumeration.coverage().unknownAssignments(), allFacts.size(), regions.size(),
                    allFacts.size() - regions.size(), cells,
                    contextMatches && !regionLimit && enumeration.coverage().complete()
                            && allFacts.size() == regions.size() && enumeration.coverage().unknownAssignments() == 0);
        }

        Feasibility feasibility() {
            return switch (enumeration.feasibility()) {
                case FEASIBLE -> Feasibility.FEASIBLE;
                case UNKNOWN -> Feasibility.UNKNOWN;
                case INVALID_MODEL -> Feasibility.INVALID_MODEL;
            };
        }

        void issue(TruthProcessing.Reason reason, String subject, Optional<ConditionalFactKey.Identity> fact) {
            issues.add(new TruthProcessing.Issue(reason, subject, fact,
                    List.of(request.baseline().evidence(), bindingPlan.environment().evidence())));
        }

        ConfigurationReasoner.WorldKey worldIdentity(ConfigurationAssignment assignment) {
            return new ConfigurationReasoner.WorldKey(TruthIdentity.derive("spring-world", Map.of(
                    "semanticsContextIdentity", context, "fullAssignment", assignment.canonicalForm(),
                    "legalOrderIdentity", legalOrder, "branchEvidence", List.of())));
        }

        DeltaAssignment delta(ConfigurationAssignment assignment) {
            List<ValueChange> values = new ArrayList<>();
            assignment.baseline().forEach((key, value) -> {
                if (!Objects.equals(request.baseline().baseline().get(key), value)) values.add(new ValueChange(key, value));
            });
            Map<String, ConfigurationAssignment.SourceChoice> baselineChoices = new HashMap<>();
            request.baseline().sourceChoices().forEach(c -> baselineChoices.put(choiceKey(c), c));
            List<ConfigurationAssignment.SourceChoice> choices = assignment.sourceChoices().stream()
                    .filter(c -> !Objects.equals(baselineChoices.get(choiceKey(c)), c)).toList();
            return new DeltaAssignment(values, choices);
        }
    }

    private static ContextIdentity contextIdentity(Request request) {
        var plan = request.bindingPlan(); var registration = plan.registrationPlan(); var discovery = registration.discoveryPlan();
        return new ContextIdentity(TruthIdentity.derive("spring-semantics-context", Map.ofEntries(
                Map.entry("buildContextIdentity", discovery.buildContext().identity()),
                Map.entry("configurationSpaceIdentity", request.conditionModel().space().identity()),
                Map.entry("registrationPlanIdentity", registration.identity()),
                Map.entry("mechanismCatalog", discovery.inventory().mechanismCatalog()),
                Map.entry("conditionIrVersion", ConditionExpression.IR),
                Map.entry("frameworkSemantics", discovery.lowering().semantics()),
                Map.entry("registrationSemantics", BeanRegistrationProcessing.SEMANTICS),
                Map.entry("bindingSemantics", BindingProcessing.SEMANTICS),
                Map.entry("bindingPlanIdentity", plan.identity()),
                Map.entry("reasonerPolicy", request.reasoner().policy()),
                Map.entry("deterministicLimits", Map.of("discovery", discovery.limits(),
                        "registration", registration.limits(), "binding", plan.limits(),
                        "truth", request.limits())))));
    }

    private static LegalOrderIdentity legalOrderIdentity(BeanRegistrationPlan plan) {
        Map<String, BeanRegistrationPlan.Step> steps = new HashMap<>();
        plan.steps().forEach(step -> steps.put(step.key(), step));
        var events = plan.establishedOrderPrefix().stream().map(key -> steps.get(key).discoveryEvent()).toList();
        return new LegalOrderIdentity(TruthIdentity.derive("spring-legal-order", Map.of(
                "registrationPlanIdentity", plan.identity(), "orderedEventIdentities", events,
                "realizabilityEvidence", Map.of("evidence", plan.orderEvidence().identity(), "completeness", plan.order()),
                "orderInterpretation", "evidenced-registration-prefix-v1")));
    }

    private static List<ConditionalFactKey> deriveFacts(InjectionBindingPlan plan,
                                                         Map<ContentDigest, InjectionBindingPlan.Dependency> dependencies) {
        var facts = new TreeSet<ConditionalFactKey>();
        var discovery = plan.registrationPlan().discoveryPlan();
        discovery.initialDefinitions().forEach(c -> facts.add(ConditionalFactKey.definitionPresent(c.identity())));
        discovery.events().forEach(e -> e.candidate().ifPresent(c -> facts.add(ConditionalFactKey.definitionPresent(c.identity()))));
        for (var match : plan.matches()) {
            var dependency = dependencies.get(match.dependency());
            if (dependency != null) {
                facts.add(ConditionalFactKey.injectionCandidate(
                        dependency.point().identity(), dependency.owner(), match.candidate()));
                facts.add(ConditionalFactKey.selectedBinding(
                        dependency.point().identity(), dependency.owner(), match.candidate()));
            }
        }
        return List.copyOf(facts);
    }

    private static LogicalValue factTruth(ConditionalFactKey fact, InjectionBindings.Result result,
                                          Map<ContentDigest, InjectionBindingPlan.Dependency> dependencies) {
        return switch (fact.kind()) {
            case DEFINITION_PRESENT -> result.registration().candidates().stream()
                    .filter(c -> c.candidate().equals(fact.definition().orElseThrow()))
                    .map(BeanRegistrationTransitions.Candidate::finalPresence).findFirst().orElse(UNKNOWN);
            case INJECTION_CANDIDATE, SELECTED_BINDING -> {
                var row = result.rows().stream().filter(r -> r.point().equals(fact.injectionPoint().orElseThrow()))
                        .filter(r -> {
                            var descriptor = dependencies.get(r.dependency());
                            return descriptor != null && descriptor.owner().equals(fact.bindingOwner());
                        }).findFirst();
                if (row.isEmpty()) yield UNKNOWN;
                boolean established = (fact.kind() == ConditionalFactKey.Kind.INJECTION_CANDIDATE
                        ? row.orElseThrow().eligibleCandidates() : row.orElseThrow().selected()).stream()
                        .anyMatch(t -> t.candidate().equals(fact.target().orElseThrow()));
                if (established) yield TRUE;
                yield switch (row.orElseThrow().outcome()) {
                    case SELECTED, AGGREGATE, ABSENT_OPTIONAL, UNSATISFIED, AMBIGUOUS, NOT_ACTIVE, GROUP_SKIPPED -> FALSE;
                    case DEFERRED, UNKNOWN, ERROR, NOT_REACHED -> UNKNOWN;
                };
            }
        };
    }

    private static OperationalStatus operationalStatus(InjectionBindings.Result result) {
        if (result.registration().containerError() || result.rows().stream().anyMatch(r -> r.operation() == InjectionBindings.Operation.ERROR))
            return OperationalStatus.ERROR;
        if (result.rows().stream().anyMatch(r -> r.operation() == InjectionBindings.Operation.LIMIT_EXCEEDED))
            return OperationalStatus.LIMIT_EXCEEDED;
        if (!result.registration().registrationClosed() || result.rows().stream().anyMatch(r ->
                r.operation() == InjectionBindings.Operation.INCOMPLETE || r.operation() == InjectionBindings.Operation.NOT_INVOKED
                        || r.operation() == InjectionBindings.Operation.DEFERRED)) return OperationalStatus.PARTIAL;
        return OperationalStatus.COMPLETE;
    }

    private static List<String> operationalReasons(InjectionBindings.Result result) {
        var reasons = new TreeSet<String>();
        result.rows().forEach(row -> {
            if (row.operation() != InjectionBindings.Operation.COMPLETE) reasons.add(row.operation().name());
        });
        result.registration().issues().forEach(issue -> reasons.add(issue.reason().name()));
        return List.copyOf(reasons);
    }

    private static Classification classify(boolean classifiable, int worlds, int yes, int no, int unknown) {
        if (!classifiable || worlds == 0) return Classification.UNKNOWN;
        if (yes == worlds && no == 0 && unknown == 0) return Classification.MUST;
        if (no == worlds && yes == 0 && unknown == 0) return Classification.NEVER;
        if (yes > 0 && no > 0) return Classification.MAY;
        return Classification.UNKNOWN;
    }

    private record AssignmentProjection(Map<FiniteDomain.Variable, FiniteDomain.Value> baseline,
                                        Map<String, FiniteDomain.Value> sources) {}

    private static AssignmentProjection projection(ConfigurationAssignment assignment) {
        Map<String, FiniteDomain.Value> sources = new TreeMap<>();
        assignment.sourceChoices().forEach(c -> sources.put(choiceKey(c), c.value()));
        return new AssignmentProjection(assignment.baseline(), Collections.unmodifiableMap(sources));
    }

    private static String choiceKey(ConfigurationAssignment.SourceChoice choice) {
        return choice.source().layer() + ":" + choice.source().sourceIdentity().value() + ":"
                + choice.variable().kind() + ":" + choice.variable().name();
    }

    private static ConfigurationIdentity baselineIdentity(ConfigurationAssignment assignment) {
        Map<String, String> values = new TreeMap<>();
        assignment.baseline().forEach((variable, value) -> values.put(
                "baseline:" + variable.kind() + ":" + variable.name(), CanonicalJson.write(value)));
        assignment.sourceChoices().forEach(choice -> values.put(
                "source:" + choiceKey(choice), CanonicalJson.write(choice.value())));
        return ConfigurationIdentity.from(BASELINE_CONFIGURATION_SCHEMA, values);
    }
}
