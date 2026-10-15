package com.evolution.analysis.assessment;

import com.evolution.analysis.metrics.*;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.*;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import java.math.*;
import java.util.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Stable, storage-neutral assessment boundary consuming canonical metrics and evaluated policy. */
public final class ArchitectureAssessmentService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** Obtain structural operands through the existing canonical metric service, with explicit query/metric bounds. */
    public ArchitectureAssessment.Result assess(PolicyCompiler.Result compilation, PolicyGraphMapper.Plan plan, PolicyEvaluation.Result evaluation,
                                                ArchitectureQueryService queries, Optional<ArchitectureScoreModel> model,
                                                PolicyMetrics.Limits policyLimits, ArchitectureMetric.Limits metricLimits, ArchitectureAssessment.Limits limits) {
        var metrics = new PolicyMetricsService().compute(compilation, plan, evaluation, queries, policyLimits);
        var inputs = new ArrayList<ArchitectureMetric.Result>();
        var engine = new ArchitectureMetricsService(queries.graph(), metricLimits, queries::assessmentStopReason, Optional.empty(), List.of());
        var requested = new TreeMap<String, ArchitectureScoreModel.MetricInput>();
        model.ifPresent(m -> m.dimensions().forEach(d -> d.metric().ifPresent(spec -> requested.putIfAbsent(spec.metric().id(), spec))));
        requested.values().forEach(spec -> inputs.add(engine.compute(request(metrics.context(), spec.scope(), plan.limits().query()), spec.metric().id(), plan.context())));
        return evaluate(metrics, evaluation, queries, model, inputs, limits);
    }
    public ArchitectureAssessment.Result evaluate(PolicyMetrics.Report metrics, PolicyEvaluation.Result evaluation, ArchitectureQueryService queries,
                                                  Optional<ArchitectureScoreModel> model, List<ArchitectureMetric.Result> structuralInputs,
                                                  ArchitectureAssessment.Limits limits) {
        Objects.requireNonNull(model); Objects.requireNonNull(structuralInputs); Objects.requireNonNull(limits);
        var context = metrics.context();
        if (!context.evaluation().equals(evaluation.identity()) || !context.graph().equals(queries.graph().identity())
                || !context.graph().equals(evaluation.graph()) || !context.partition().equals(evaluation.context())
                || !context.compilation().equals(evaluation.compilation()) || !context.mapping().equals(evaluation.mapping()))
            throw new IllegalArgumentException("Foreign assessment inputs");
        var partition = queries.graph().contexts().stream().filter(c -> c.identity().equals(context.partition())).findFirst().orElseThrow();
        if (!context.snapshot().equals(partition.snapshot()) || !context.analysis().equals(partition.analysis())) throw new IllegalArgumentException("Foreign assessment provenance");
        var evidence = new EvidenceContext(context.snapshot(), context.analysis()); var gaps = new TreeSet<>(metrics.gaps());
        var reasons = new TreeSet<String>(); var dimensions = new ArrayList<ArchitectureAssessment.Dimension>();
        var accepted = new ArrayList<ArchitectureMetric.Result>();
        if (metrics.status() != AssessmentStatus.COMPLETE || evaluation.status() != AssessmentStatus.COMPLETE
                || metrics.counts().declaredRules() != evaluation.rules().size() || evaluation.rules().isEmpty()
                || evaluation.rules().stream().anyMatch(r -> r.status() != AssessmentStatus.COMPLETE || r.classification() == Classification.UNKNOWN || !r.predicate().complete()))
            add(gaps, evidence, evaluation.identity(), "POLICY_EVIDENCE_INCOMPLETE", metrics.status());
        if (model.isEmpty()) add(gaps, evidence, evaluation.identity(), "SCORE_MODEL_UNAVAILABLE", context.identity());
        if (structuralInputs.size() > limits.maxMetricInputs()) add(gaps, evidence, evaluation.identity(), "ASSESSMENT_INPUT_LIMIT", List.of(limits, structuralInputs.size()));
        else accepted.addAll(structuralInputs.stream().sorted(Comparator.comparing(m -> m.metric().id())).toList());
        var metricIds = new HashSet<String>();
        for (var input : accepted) {
            if (!input.context().graph().equals(context.graph()) || !input.context().partition().equals(context.partition())
                    || !input.subject().equals(context.partition()) || !input.context().analyses().equals(context.analysis().stream().toList()))
                throw new IllegalArgumentException("Foreign structural score input");
            if (!metricIds.add(input.metric().id())) throw new IllegalArgumentException("Duplicate structural score metric");
        }
        var stop = queries.assessmentStopReason();
        if (stop != StopReason.NONE) add(gaps, evidence, evaluation.identity(), "ASSESSMENT_" + stop.name(), stop);
        int remaining = limits.maxContributions(), requiredMetrics = 0, completeMetrics = 0;
        BigDecimal weighted = BigDecimal.ZERO, totalWeight = BigDecimal.ZERO;
        if (model.isPresent()) for (var specification : model.orElseThrow().dimensions()) {
            var local = new TreeSet<CapabilityGapRecord>(); var localReasons = new ArrayList<String>();
            var contributions = new ArrayList<ArchitectureAssessment.Contribution>(); var inputs = new TreeSet<ContentDigest>();
            boolean applicable = true;
            if (queries.assessmentStopReason() != StopReason.NONE) add(local, evidence, evaluation.identity(), "ASSESSMENT_INTERRUPTED", specification.id());
            if (specification.metric().isPresent()) {
                requiredMetrics++; var spec = specification.metric().orElseThrow();
                var metric = accepted.stream().filter(m -> m.metric().id().equals(spec.metric().id())).findFirst();
                if (metric.isEmpty()) add(local, evidence, evaluation.identity(), "REQUIRED_METRIC_UNAVAILABLE", spec);
                else {
                    var operand = metric.orElseThrow(); inputs.add(operand.identity()); local.addAll(operand.gaps());
                    if (!compatible(operand, spec, context)) add(local, evidence, evaluation.identity(), "METRIC_SEMANTICS_INCOMPATIBLE", operand.identity());
                    else if (operand.status() == AssessmentStatus.NOT_APPLICABLE && operand.gaps().isEmpty() && operand.ratio().isPresent()
                            && operand.ratio().orElseThrow().denominator().signum() == 0 && operand.ratio().orElseThrow().numerator().signum() == 0) {
                        applicable = false; completeMetrics++; localReasons.add("VERIFIED_ZERO_DENOMINATOR");
                    } else if (operand.status() != AssessmentStatus.COMPLETE || operand.ratio().isEmpty() || operand.value().isEmpty())
                        add(local, evidence, evaluation.identity(), "REQUIRED_METRIC_INCOMPLETE", operand.identity());
                    else {
                        var ratio = operand.ratio().orElseThrow(); var amount = operand.value().orElseThrow();
                        if (!amount.unit().equals("ratio") || ratio.denominator().signum() <= 0 || ratio.numerator().signum() < 0 || ratio.numerator().compareTo(ratio.denominator()) > 0
                                || amount.amount().compareTo(ArchitectureMetric.divide(ratio.numerator(), ratio.denominator())) != 0)
                            add(local, evidence, evaluation.identity(), "METRIC_OPERANDS_INVALID", operand.identity());
                        else {
                            completeMetrics++;
                            BigDecimal raw = ratio.numerator().subtract(spec.threshold().multiply(ratio.denominator()));
                            BigDecimal width = ratio.denominator().multiply(spec.saturation().subtract(spec.threshold()));
                            BigDecimal penalty = ArchitectureMetric.divide(raw.max(BigDecimal.ZERO).min(width).multiply(spec.maximumPenalty()), width);
                            contributions.add(new ArchitectureAssessment.Contribution(operand.identity(), "ratio", ratio.numerator(), ratio.denominator(), spec.threshold(), spec.saturation(),
                                    spec.maximumPenalty(), penalty, List.of(operand.identity()), List.of(), List.of()));
                        }
                    }
                }
            } else {
                var selected = evaluation.rules().stream().filter(r -> specification.ruleKinds().contains(r.rule().kind())).toList();
                if (selected.isEmpty()) { applicable = false; localReasons.add("NO_APPLICABLE_POLICY_RULES"); }
                var groups = new TreeMap<ContentDigest, List<PolicyEvaluation.Finding>>();
                for (var rule : selected) {
                    inputs.add(rule.identity()); local.addAll(rule.gaps());
                    if (rule.status() != AssessmentStatus.COMPLETE || rule.classification() == Classification.UNKNOWN || !rule.predicate().complete())
                        add(local, evidence, evaluation.identity(), "RULE_EVIDENCE_INCOMPLETE", rule.identity());
                    for (var finding : rule.findings()) if (finding.classification() == Classification.MUST || finding.classification() == Classification.MAY)
                        groups.computeIfAbsent(root(finding), k -> new ArrayList<>()).add(finding);
                }
                for (var entry : groups.entrySet()) {
                    var findings = entry.getValue(); BigDecimal penalty = findings.stream().map(f -> model.orElseThrow().severityPenalties().get(f.rule().severity().name())).max(BigDecimal::compareTo).orElseThrow();
                    var references = new TreeSet<ContentDigest>(); var regions = new TreeSet<ContentDigest>(); var quantifiers = new TreeSet<Classification>();
                    for (var finding : findings) { references.add(finding.identity()); references.add(finding.bundle().identity()); references.add(finding.bundle().rule().identity());
                        regions.add(finding.bundle().region().jointRegion()); quantifiers.add(finding.classification()); }
                    contributions.add(new ArchitectureAssessment.Contribution(entry.getKey(), "potential-root", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
                            penalty, penalty, List.copyOf(references), List.copyOf(regions), List.copyOf(quantifiers)));
                }
            }
            if (contributions.size() > remaining) {
                contributions.subList(remaining, contributions.size()).clear(); add(local, evidence, evaluation.identity(), "ASSESSMENT_CONTRIBUTION_LIMIT", List.of(limits, specification.id()));
            }
            remaining -= contributions.size();
            BigDecimal rawPenalty = contributions.stream().map(ArchitectureAssessment.Contribution::penalty).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal applied = rawPenalty.min(specification.penaltyCap());
            AssessmentStatus status = !local.isEmpty() ? AssessmentStatus.WITHHELD : !applicable ? AssessmentStatus.NOT_APPLICABLE : AssessmentStatus.PARTIAL;
            Optional<BigDecimal> score = status == AssessmentStatus.PARTIAL ? Optional.of(HUNDRED.subtract(applied)) : Optional.empty();
            if (status == AssessmentStatus.WITHHELD) localReasons.add("REQUIRED_DIMENSION_EVIDENCE_INCOMPLETE");
            if (score.isPresent()) { localReasons.add("PROVISIONAL_SCORE_MODEL"); weighted = weighted.add(score.orElseThrow().multiply(specification.weight())); totalWeight = totalWeight.add(specification.weight()); }
            dimensions.add(new ArchitectureAssessment.Dimension(specification.id(), specification.weight(), status, score, HUNDRED, rawPenalty,
                    specification.penaltyCap(), applied, contributions, List.copyOf(inputs), List.copyOf(local), localReasons)); gaps.addAll(local);
        }
        boolean sufficient = gaps.isEmpty();
        var confidence = new ArchitectureAssessment.EvidenceConfidence(sufficient, metrics.counts().declaredRules(), metrics.counts().completeRules(), requiredMetrics, completeMetrics,
                List.copyOf(gaps), List.of("Sufficiency of required assessment operands only; not calibrated semantic accuracy or whole-repository analysis confidence."));
        AssessmentStatus status; Optional<BigDecimal> score;
        if (!sufficient) { status = AssessmentStatus.WITHHELD; score = Optional.empty(); reasons.add("REQUIRED_ASSESSMENT_EVIDENCE_INCOMPLETE"); }
        else if (totalWeight.signum() == 0) { status = AssessmentStatus.NOT_APPLICABLE; score = Optional.empty(); reasons.add("NO_APPLICABLE_DIMENSIONS"); }
        else { status = AssessmentStatus.PARTIAL; score = Optional.of(ArchitectureMetric.divide(weighted, totalWeight)); reasons.add("PROVISIONAL_SCORE_MODEL"); }
        if (model.isPresent()) add(gaps, evidence, model.orElseThrow().identity(), "SCORE_MODEL_NOT_APPROVED", model.orElseThrow().identity());
        reasons.add("POTENTIAL_EXPOSURE_ACROSS_MODELED_SPACE; MAY receives full severity; no configuration probability or simultaneous-runtime-world claim.");
        return new ArchitectureAssessment.Result(context, metrics.identity(), model, ArchitectureScoreModel.FORMULA, ArchitectureAssessment.REGION_POLICY,
                ArchitectureScoreModel.CALIBRATION, limits, status, score, dimensions, weighted, totalWeight, accepted, confidence, List.copyOf(gaps), List.copyOf(reasons), Optional.empty());
    }
    private static Request request(PolicyMetrics.Context context, Scope scope, ArchitectureQuery.Limits limits) {
        return new Request(context.graph(), context.partition(), scope, Projection.BOUNDED_UNION, Direction.OUTGOING,
                scope == Scope.SPRING_COMPONENT ? Set.of("spring.selected-binding") : Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), limits);
    }
    private static boolean compatible(ArchitectureMetric.Result metric, ArchitectureScoreModel.MetricInput spec, PolicyMetrics.Context context) {
        var definition = MetricCatalog.require(spec.metric().id()); var projection = metric.context().projection();
        return metric.metric().equals(spec.metric()) && metric.definition().equals(definition.identity()) && metric.context().population().equals(definition.population())
                && metric.context().layers().isEmpty() && projection.equals(request(context, spec.scope(), projection.limits()));
    }
    private static ContentDigest root(PolicyEvaluation.Finding finding) {
        var directed = finding.bundle().relationships().stream().map(e -> List.of(e.source(), e.target(), e.kinds())).sorted(Comparator.comparing(com.evolution.analysis.contract.serialization.CanonicalJson::write)).toList();
        return ArchitectureAssessment.digest(List.of(ArchitectureAssessment.REGION_POLICY, finding.rule().scope(), finding.kind() == ArchitecturePolicy.Kind.ACYCLIC ? "cycle" : "dependency", directed));
    }
    private static void add(Set<CapabilityGapRecord> gaps, EvidenceContext context, ContentDigest input, String reason, Object detail) {
        gaps.add(AssessmentGaps.create(context, ArchitectureAssessment.VERSION, input, reason, detail, AffectedOutput.Kind.ASSESSMENT, "assessment.architecture-health"));
    }
}
