package com.evolution.analysis.spring.binding;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;

/** Passive definition selection for one exact M4C.2 result. No target code execution. */
public final class InjectionBindings {
    private InjectionBindings() {}
    public enum Outcome { SELECTED, AGGREGATE, ABSENT_OPTIONAL, UNSATISFIED, AMBIGUOUS, DEFERRED,
                          NOT_ACTIVE, GROUP_SKIPPED, UNKNOWN, ERROR, NOT_REACHED }
    public enum Operation { COMPLETE, INCOMPLETE, LIMIT_EXCEEDED, ERROR, NOT_INVOKED, DEFERRED }
    public enum Stage { NAME_SHORTCUT, STRICT, GENERIC_FALLBACK, SELF_REFERENCE, RESOURCE_NAME,
                        PRIMARY, NON_FALLBACK, DEPENDENCY_NAME, SUGGESTED_NAME, PRIORITY, UNIQUE, AGGREGATE }
    public enum ObligationStatus { NORMALIZED, NORMALIZATION_REQUIRED, NOT_INJECTION }
    public record ObligationRow(ContentDigest obligation, ObligationStatus status, List<ContentDigest> dependencies) {
        public ObligationRow { Objects.requireNonNull(obligation); Objects.requireNonNull(status); dependencies = List.copyOf(dependencies); }
    }
    public record Target(String beanName, BeanDefinitionCandidate.Identity candidate) {
        public Target { beanName = BindingIdentity.text(beanName); Objects.requireNonNull(candidate); }
    }
    public record CandidateTrace(Target target, BindingEvidence.Lane lane, Stage stage, LogicalValue eligible,
                                 List<ConditionEvidence> evidence) {
        public CandidateTrace {
            Objects.requireNonNull(target); Objects.requireNonNull(lane); Objects.requireNonNull(stage); Objects.requireNonNull(eligible);
            evidence = evidence.stream().distinct().sorted(Comparator.comparing(ConditionEvidence::identity)).toList();
        }
    }
    public record Row(ContentDigest dependency, InjectionPoint.Identity point, Outcome outcome, Operation operation,
                      List<Target> selected, List<Target> eligibleCandidates, List<CandidateTrace> trace,
                      Optional<Stage> selectionStage, boolean orderEstablished) {
        public Row {
            Objects.requireNonNull(dependency); Objects.requireNonNull(point); Objects.requireNonNull(outcome); Objects.requireNonNull(operation);
            selected = List.copyOf(selected); eligibleCandidates = List.copyOf(eligibleCandidates); trace = List.copyOf(trace);
            Objects.requireNonNull(selectionStage);
            if (outcome == Outcome.SELECTED && selected.size() != 1 || outcome != Outcome.SELECTED && outcome != Outcome.AGGREGATE && !selected.isEmpty())
                throw new IllegalArgumentException("Only a definite selection may have targets");
        }
    }
    public record Coverage(int dependencies, Map<String, Integer> outcomes) {
        public Coverage {
            outcomes = Collections.unmodifiableMap(new TreeMap<>(outcomes));
            if (dependencies < 0 || outcomes.values().stream().anyMatch(n -> n < 0)
                    || outcomes.values().stream().mapToLong(Integer::longValue).sum() != dependencies)
                throw new IllegalArgumentException("Binding denominator must close");
        }
    }
    public static final class Result {
        private final ContentDigest input;
        private final DiscoveryTransitions.ContextIdentity context;
        private final BeanRegistrationTransitions.Result registration;
        private final List<Row> rows;
        private final List<BindingProcessing.Issue> issues;
        private final List<ObligationRow> obligations;
        private final List<CapabilityGapRecord> gaps;
        Result(ContentDigest input, DiscoveryTransitions.ContextIdentity context, BeanRegistrationTransitions.Result registration,
               List<Row> rows, List<ObligationRow> obligations, List<BindingProcessing.Issue> issues, SpringBuildContext build) {
            this.input = input; this.context = context; this.registration = registration;
            this.rows = rows.stream().sorted(Comparator.comparing(Row::dependency)).toList();
            this.obligations = List.copyOf(obligations);
            this.issues = issues.stream().distinct().sorted(Comparator.comparing(BindingProcessing.Issue::identity)).toList();
            var all = new TreeSet<>(registration.capabilityGaps()); all.addAll(BindingProcessing.gaps(input, build, this.issues));
            gaps = List.copyOf(all);
        }
        public ContentDigest inputIdentity() { return input; }
        public DiscoveryTransitions.ContextIdentity semanticsContextIdentity() { return context; }
        public List<Row> rows() { return rows; }
        public List<BindingProcessing.Issue> issues() { return issues; }
        public List<ObligationRow> obligations() { return obligations; }
        public List<CapabilityGapRecord> capabilityGaps() { return gaps; }
        public Coverage coverage() {
            Map<String, Integer> counts = new TreeMap<>();
            for (var outcome : Outcome.values()) counts.put(outcome.name(), 0);
            rows.forEach(r -> counts.merge(r.outcome().name(), 1, Integer::sum));
            return new Coverage(rows.size(), counts);
        }
        public ContentDigest identity() { return BindingIdentity.digest(canonicalForm()); }
        public BeanRegistrationTransitions.Result registration() { return registration; }
        public BindingCandidateIdentity candidateIdentity(InjectionPoint.Identity point, BeanDefinitionCandidate.Identity candidate) {
            if (rows.stream().noneMatch(r -> r.point().equals(point)) || registration.candidates().stream().noneMatch(c -> c.candidate().equals(candidate)))
                throw new IllegalArgumentException("Foreign binding candidate reference");
            return new BindingCandidateIdentity(BindingIdentity.derive("spring-binding-candidate", Map.of(
                    "semanticsContextIdentity", context, "injectionPointIdentity", point,
                    "beanCandidateIdentity", candidate, "bindingSemanticsVersion", BindingProcessing.SEMANTICS)));
        }
        public Object canonicalForm() { return Map.of("schema", "spring-injection-binding-result-v1", "provider", BindingProcessing.PROVIDER,
                "input", input, "context", context, "registration", registration.identity(), "rows", rows,
                "issues", issues, "capabilityGaps", gaps, "coverage", coverage(), "obligations", obligations); }
    }
    public record BindingCandidateIdentity(String value) implements CanonicalIdentifier {
        public BindingCandidateIdentity { value = BindingIdentity.require(value, "spring-binding-candidate"); }
    }
    public static Result evaluate(InjectionBindingPlan plan, ConditionModel model, ConfigurationAssignment assignment,
                                  ExogenousConditionEvaluator.Limits exogenousLimits) {
        var registration = BeanRegistrationTransitions.evaluate(plan.registrationPlan(), model, assignment, exogenousLimits);
        return new BindingResolution(plan, registration, model.space().identity(), exogenousLimits).run();
    }
}
