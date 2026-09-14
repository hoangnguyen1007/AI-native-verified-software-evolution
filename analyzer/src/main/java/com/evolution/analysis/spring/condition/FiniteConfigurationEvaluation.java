package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import java.math.BigInteger;
import java.util.*;

/** Exhaustive small-space baseline, not a production SAT solver or an architecture truth-region engine. */
public final class FiniteConfigurationEvaluation {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.finite-exogenous-evaluator", "m4b.2");
    public record Limits(int maxAssignments, int maxOutputRows, int maxTotalSteps, ExogenousConditionEvaluator.Limits perAssignment) {
        public Limits {
            if (maxAssignments < 1 || maxOutputRows < 1 || maxTotalSteps < 1) throw new IllegalArgumentException("Positive enumeration limits required");
            Objects.requireNonNull(perAssignment);
        }
        public static Limits conservative() { return new Limits(4096, 100000, 100000, ExogenousConditionEvaluator.Limits.conservative()); }
    }
    public enum Feasibility { FEASIBLE, UNKNOWN, INVALID_MODEL }
    public record Coverage(ConfigurationSpace.CartesianSize assignmentCount, int evaluatedAssignments,
                           int feasibleAssignments, int infeasibleAssignments, int unknownAssignments, boolean complete) {
        public Coverage {
            Objects.requireNonNull(assignmentCount);
            if (evaluatedAssignments < 0 || feasibleAssignments < 0 || infeasibleAssignments < 0 || unknownAssignments < 0
                    || (long) feasibleAssignments + infeasibleAssignments + unknownAssignments != evaluatedAssignments
                    || assignmentCount.value().compareTo(BigInteger.valueOf(evaluatedAssignments)) < 0
                    || complete && (!assignmentCount.exact() || !assignmentCount.value().equals(BigInteger.valueOf(evaluatedAssignments))))
                throw new IllegalArgumentException("Finite assignment denominator is not closed");
        }
    }
    public static final class Result {
        private final ContentDigest input;
        private final List<ConfigurationAssignment> assignments;
        private final List<ExogenousConditionEvaluator.Result> evaluations;
        private final List<CapabilityGapRecord> gaps;
        private final Coverage coverage;
        Result(ContentDigest input, List<ConfigurationAssignment> assignments, List<ExogenousConditionEvaluator.Result> evaluations,
               List<CapabilityGapRecord> gaps, Coverage coverage) {
            this.input = input; this.assignments = List.copyOf(assignments); this.evaluations = List.copyOf(evaluations);
            this.gaps = List.copyOf(new TreeSet<>(gaps)); this.coverage = coverage;
        }
        public ContentDigest inputIdentity() { return input; }
        public List<ConfigurationAssignment> assignments() { return assignments; }
        public List<ExogenousConditionEvaluator.Result> evaluations() { return evaluations; }
        public List<CapabilityGapRecord> capabilityGaps() { return gaps; }
        public Coverage coverage() { return coverage; }
        public Feasibility feasibility() {
            if (coverage.feasibleAssignments() > 0) return Feasibility.FEASIBLE;
            return coverage.complete() && coverage.unknownAssignments() == 0 ? Feasibility.INVALID_MODEL : Feasibility.UNKNOWN;
        }
        public ContentDigest identity() { return ConditionIdentitySupport.digest(canonicalForm()); }
        public Object canonicalForm() {
            List<Object> outcomes = new ArrayList<>();
            for (int i = 0; i < assignments.size(); i++) outcomes.add(Map.of("assignment", assignments.get(i).canonicalForm(), "evaluation", evaluations.get(i).canonicalForm()));
            return Map.of("schema", "spring-finite-exogenous-evaluation-v1", "provider", PROVIDER, "inputIdentity", input,
                    "outcomes", outcomes, "coverage", coverage, "feasibility", feasibility(), "capabilityGaps", gaps);
        }
    }
    private record Dimension(Optional<ConfigurationSpace.SourceReference> source, FiniteDomain.Variable variable, List<FiniteDomain.Value> values) {}
    private FiniteConfigurationEvaluation() {}
    public static Result evaluate(ConditionModel model, ConditionExpression.Semantics semantics, Limits limits) {
        Objects.requireNonNull(model); Objects.requireNonNull(semantics); Objects.requireNonNull(limits);
        var space = model.space();
        var input = ConditionIdentitySupport.digest(Map.of("provider", PROVIDER, "model", model.identity(), "semantics", semantics, "limits", limits));
        List<CapabilityGapRecord> gaps = new ArrayList<>(model.capabilityGaps());
        List<ConditionProcessing.Issue> issues = new ArrayList<>();
        List<Dimension> dimensions = new ArrayList<>();
        space.domains().forEach(d -> dimensions.add(new Dimension(Optional.empty(), d.variable(), d.values())));
        // Each source's alternatives are independent; using the baseline to pick every source loses worlds.
        for (var envelope : space.envelopes()) for (var source : envelope.declaredSources()) {
            for (var entry : source.values().entrySet()) if (entry.getValue().size() > 1) dimensions.add(new Dimension(
                    Optional.of(new ConfigurationSpace.SourceReference(envelope.layer(), source.identity())), entry.getKey(), entry.getValue()));
        }
        dimensions.sort(Comparator.comparing(d -> ConditionIdentitySupport.digest(Map.of("source", d.source(), "variable", d.variable())).value()));
        long cap = Math.min(limits.maxAssignments(), space.feasibilityPolicy().limits().maxCartesianAssignments());
        BigInteger size = BigInteger.ONE;
        boolean exact = true;
        if (model.status() == ConditionModel.Status.INVALID_MODEL) size = BigInteger.ZERO;
        else for (var dimension : dimensions) {
            size = size.multiply(BigInteger.valueOf(dimension.values().size()));
            if (size.compareTo(BigInteger.valueOf(cap)) > 0) { size = BigInteger.valueOf(cap + 1); exact = false; break; }
        }
        var count = new ConfigurationSpace.CartesianSize(size, exact);
        List<ConfigurationAssignment> assignments = new ArrayList<>(); List<ExogenousConditionEvaluator.Result> evaluations = new ArrayList<>();
        boolean admitted = exact && size.multiply(BigInteger.valueOf(Math.max(1, model.sourceRows().size())))
                .compareTo(BigInteger.valueOf(limits.maxOutputRows())) <= 0
                && dimensions.size() <= limits.perAssignment().maxSourceEntries();
        int steps = 0;
        if (admitted) {
            int[] positions = new int[dimensions.size()];
            var evidence = new ConditionEvidence.Derived(List.of(input), PROVIDER, "finite-assignment");
            for (int index = 0; index < size.intValueExact(); index++) {
                if (steps >= limits.maxTotalSteps()) break;
                Map<FiniteDomain.Variable, FiniteDomain.Value> baseline = new TreeMap<>();
                List<ConfigurationAssignment.SourceChoice> choices = new ArrayList<>();
                for (int axis = 0; axis < dimensions.size(); axis++) {
                    var dimension = dimensions.get(axis); var value = dimension.values().get(positions[axis]);
                    if (dimension.source().isEmpty()) baseline.put(dimension.variable(), value);
                    else choices.add(new ConfigurationAssignment.SourceChoice(dimension.source().orElseThrow(), dimension.variable(), value));
                }
                var assignment = new ConfigurationAssignment(baseline, choices, evidence);
                var result = ExogenousConditionEvaluator.evaluate(model, semantics, assignment, new ExogenousConditionEvaluator.Limits(
                        Math.min(limits.perAssignment().maxSteps(), limits.maxTotalSteps() - steps), limits.perAssignment().maxSourceEntries()));
                steps += Math.max(1, result.steps()); // Every assignment itself consumes deterministic work, even with zero condition rows.
                assignments.add(assignment); evaluations.add(result); gaps.addAll(result.capabilityGaps());
                for (int axis = dimensions.size() - 1; axis >= 0; axis--) {
                    if (++positions[axis] < dimensions.get(axis).values().size()) break;
                    positions[axis] = 0;
                }
            }
        }
        boolean complete = exact && size.equals(BigInteger.valueOf(evaluations.size()));
        if (!admitted || !complete) issues.add(new ConditionProcessing.Issue(ConditionProcessing.Reason.EVALUATION_LIMIT,
                input.value(), Optional.empty(), List.of(space.feasibilityPolicy().evidence())));
        gaps.addAll(ConditionProcessing.gaps(PROVIDER, input, space.buildContext(), issues));
        int yes = 0, no = 0, unknown = 0;
        for (var result : evaluations) switch (result.assignmentFeasibility()) { case TRUE -> yes++; case FALSE -> no++; case UNKNOWN -> unknown++; }
        if (complete && yes == 0 && unknown == 0 && size.signum() != 0) {
            gaps.addAll(ConditionProcessing.gaps(PROVIDER, input, space.buildContext(), List.of(new ConditionProcessing.Issue(
                    ConditionProcessing.Reason.INVALID_MODEL, input.value(), Optional.empty(), List.of(space.feasibilityPolicy().evidence())))));
        }
        return new Result(input, assignments, evaluations, gaps, new Coverage(count, evaluations.size(), yes, no, unknown, complete));
    }
}
