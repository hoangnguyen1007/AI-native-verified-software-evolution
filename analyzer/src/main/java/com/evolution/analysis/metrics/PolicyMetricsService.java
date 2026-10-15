package com.evolution.analysis.metrics;

import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.*;
import com.evolution.analysis.assessment.AssessmentGaps;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.metrics.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import java.math.BigDecimal;
import java.util.*;
import static com.evolution.analysis.query.ArchitectureQuery.Classification;

/** Policy metrics consume trusted compiler/mapper/evaluator outputs, never raw storage queries. */
public final class PolicyMetricsService {
    public PolicyMetrics.Report compute(PolicyCompiler.Result compilation, PolicyGraphMapper.Plan plan,
                                        PolicyEvaluation.Result evaluation, ArchitectureQueryService queries, PolicyMetrics.Limits limits) {
        Objects.requireNonNull(limits);
        var graph = queries.graph();
        if (!compilation.identity().equals(plan.compilation()) || !compilation.identity().equals(evaluation.compilation())
                || !plan.identity().equals(evaluation.mapping()) || !plan.graph().equals(graph.identity())
                || !evaluation.graph().equals(graph.identity()) || !plan.context().equals(evaluation.context()))
            throw new IllegalArgumentException("Foreign policy metric inputs");
        var expected = compilation.policy().stream().flatMap(p -> p.rules().stream()).toList();
        if (!expected.equals(plan.rules().stream().map(PolicyGraphMapper.RulePlan::rule).toList())
                || !expected.equals(evaluation.rules().stream().map(PolicyEvaluation.RuleResult::rule).toList()))
            throw new IllegalArgumentException("Incomplete policy metric rule denominator");
        var partition = graph.contexts().stream().filter(c -> c.identity().equals(plan.context())).findFirst().orElseThrow();
        var evidenceContext = new EvidenceContext(graph.snapshot(), partition.analysis());
        var gaps = new TreeSet<>(evaluation.gaps()); gaps.addAll(plan.gaps()); gaps.addAll(compilation.gaps());
        var spaces = new TreeSet<String>(); evaluation.findings().forEach(f -> spaces.addAll(f.bundle().configurationSpaces()));
        int remaining = limits.maxInputItems();
        try {
            var payload = GraphJson.object(GraphJson.read(graph.evidence(partition.evidence()).orElseThrow().canonicalJson(), new GraphJson.Limits(1_000_000, 64, 20_000)));
            if (payload.get("space") instanceof String space) spaces.add(space);
        } catch (IllegalArgumentException invalid) {
            gaps.add(gap(evidenceContext, evaluation.identity(), "CONTEXT_EVIDENCE_UNAVAILABLE", plan.context(), "metric.policy-coverage"));
        }
        // A clean rule can have no route/bundle. Its universe still owns exact space provenance.
        if (spaces.isEmpty()) {
            var reads = new HashSet<ContentDigest>(); long characters = 0; boolean exhausted = false;
            for (var node : graph.nodes()) {
                if (remaining == 0) { exhausted = true; break; } remaining--;
                if (!node.context().equals(plan.context()) || !node.kind().equals("TRUTH_REGION")) continue;
                for (var ref : node.evidence()) {
                    if (remaining == 0) { exhausted = true; break; } remaining--;
                    var payload = graph.evidence(ref).orElseThrow();
                    if (!Set.of("spring.explicit-region", "spring.symbolic-region").contains(payload.family()) || reads.contains(ref)) continue;
                    if (reads.size() == limits.maxRegionEvidenceReads() || characters + payload.canonicalJson().length() > 8_000_000) { exhausted = true; break; }
                    reads.add(ref); characters += payload.canonicalJson().length();
                    try {
                        var row = GraphJson.object(GraphJson.read(payload.canonicalJson(), new GraphJson.Limits(1_000_000, 64, 20_000)));
                        if (row.get("space") instanceof String space) spaces.add(space);
                        else gaps.add(gap(evidenceContext, evaluation.identity(), "REGION_SPACE_UNAVAILABLE", ref, "metric.policy-coverage"));
                    } catch (IllegalArgumentException invalid) { gaps.add(gap(evidenceContext, evaluation.identity(), "REGION_SPACE_UNAVAILABLE", ref, "metric.policy-coverage")); }
                }
                if (exhausted) break;
            }
            if (exhausted) gaps.add(gap(evidenceContext, evaluation.identity(), "POLICY_METRIC_LIMIT", limits, "metric.policy-coverage"));
        }
        var context = new PolicyMetrics.Context(graph.snapshot(), partition.analysis(), graph.identity(), plan.context(),
                compilation.policy().map(ArchitecturePolicy::identity), compilation.source(), compilation.identity(), plan.identity(), evaluation.identity(),
                List.copyOf(spaces), plan.rules().stream().map(PolicyGraphMapper.RulePlan::request).toList(), limits);
        var ruleCounts = zeros(Classification.values()); var findingCounts = zeros(Classification.values());
        var byRule = new TreeMap<String, Map<String, Long>>(); var severity = new TreeMap<String, Map<String, Long>>();
        var scope = new TreeMap<String, Map<String, Long>>(); var kind = new TreeMap<String, Map<String, Long>>(); var semantic = new TreeMap<String, Map<String, Long>>();
        for (var value : ArchitecturePolicy.Severity.values()) severity.put(value.name(), zeros(Classification.values()));
        for (var value : ArchitectureQuery.Scope.values()) scope.put(value.name(), zeros(Classification.values()));
        for (var value : ArchitecturePolicy.Kind.values()) kind.put(value.name(), zeros(Classification.values()));
        var rows = new ArrayList<PolicyMetrics.FindingRow>(); var coverage = new ArrayList<PolicyMetrics.Coverage>();
        var keys = new HashSet<ContentDigest>(); long completeRules = 0, completeBundles = 0;
        for (int index = 0; index < expected.size(); index++) {
            var mapped = plan.rules().get(index); var result = evaluation.rules().get(index); var header = result.rule().header();
            gaps.addAll(result.gaps());
            ruleCounts.merge(result.classification().name(), 1L, Long::sum); byRule.put(header.id(), zeros(Classification.values()));
            if (result.status() == AssessmentStatus.COMPLETE && result.classification() != Classification.UNKNOWN && result.predicate().complete()) completeRules++;
            var selections = new TreeMap<String, Map<String, Long>>(); boolean limited = false;
            for (var selection : mapped.selections().entrySet()) {
                if (selection.getValue().size() > remaining) { limited = true; remaining = 0; break; }
                remaining -= selection.getValue().size(); var counts = zeros(PolicySelector.Match.values());
                selection.getValue().values().forEach(m -> counts.merge(m.name(), 1L, Long::sum)); selections.put(selection.getKey(), counts);
            }
            if (limited) gaps.add(gap(evidenceContext, evaluation.identity(), "POLICY_METRIC_LIMIT", List.of(limits, result.rule().identity()), "metric.policy-coverage"));
            var unselected = new TreeSet<>(ArchitecturePolicy.DEPENDENCIES); unselected.removeAll(header.relationships());
            coverage.add(new PolicyMetrics.Coverage(result.rule().identity(), result.classification(), header.scope(), header.relationships(), unselected,
                    limited ? AssessmentStatus.WITHHELD : result.status(), selections, mapped.request().identity()));
            for (var finding : result.findings()) {
                var bundle = finding.bundle();
                if (!bundle.graph().equals(graph.identity()) || !bundle.context().equals(context.partition()) || !bundle.compilation().equals(compilation.identity())
                        || !bundle.mapping().equals(plan.identity()) || !bundle.policy().equals(context.policy().orElseThrow()))
                    throw new IllegalArgumentException("Foreign policy finding evidence");
                if (!keys.add(finding.key())) throw new IllegalArgumentException("Duplicate policy finding key");
                gaps.addAll(bundle.gaps());
                boolean complete = bundle.status() == AssessmentStatus.COMPLETE && bundle.gaps().isEmpty() && !bundle.sources().isEmpty()
                        && !bundle.supports().isEmpty() && finding.classification() != Classification.UNKNOWN;
                if (complete) completeBundles++;
                var statuses = bundle.supports().stream().flatMap(s -> s.semanticStatuses().stream()).sorted().distinct().toList();
                if (statuses.isEmpty()) statuses = List.of("NOT_RECORDED");
                var row = new PolicyMetrics.FindingRow(finding.identity(), finding.key(), bundle.identity(), result.rule().identity(), header.severity(),
                        result.rule().kind(), header.scope(), finding.classification(), statuses, complete); rows.add(row);
                String quantifier = finding.classification().name(); findingCounts.merge(quantifier, 1L, Long::sum);
                increment(byRule, header.id(), quantifier); increment(severity, header.severity().name(), quantifier);
                increment(scope, header.scope().name(), quantifier); increment(kind, result.rule().kind().name(), quantifier);
                for (var status : statuses) increment(semantic, status, quantifier);
            }
        }
        boolean complete = evaluation.status() == AssessmentStatus.COMPLETE && gaps.isEmpty() && completeRules == expected.size() && !expected.isEmpty();
        var reportStatus = complete ? AssessmentStatus.COMPLETE : expected.isEmpty() || evaluation.status() == AssessmentStatus.WITHHELD ? AssessmentStatus.WITHHELD : AssessmentStatus.PARTIAL;
        var counts = new PolicyMetrics.Counts(ruleCounts, findingCounts, byRule, severity, scope, kind, semantic, rows, coverage, expected.size(), completeRules, completeBundles);
        var metrics = new ArrayList<PolicyMetrics.Metric>();
        for (var definition : PolicyMetrics.catalog()) {
            var local = new TreeSet<>(gaps); var reasons = new ArrayList<String>(); Optional<MetricValue> value = Optional.empty(); Optional<RatioEvidence> ratio = Optional.empty();
            AssessmentStatus status = reportStatus;
            if (definition.id().equals("metric.policy-waivers") || definition.id().equals("metric.policy-audit")) {
                String reason = definition.id().endsWith("waivers") ? "WAIVER_EVALUATION_UNSUPPORTED" : "ADJUDICATED_LABELS_UNAVAILABLE";
                local.clear(); local.add(gap(evidenceContext, evaluation.identity(), reason, definition.identity(), definition.id())); reasons.add(reason);
                status = definition.id().endsWith("waivers") ? AssessmentStatus.NOT_APPLICABLE : AssessmentStatus.WITHHELD;
            } else if (definition.id().equals("metric.policy-findings")) {
                if (!expected.isEmpty()) { value = Optional.of(new MetricValue(BigDecimal.valueOf(rows.size()), definition.unit())); status = complete ? AssessmentStatus.COMPLETE : AssessmentStatus.PARTIAL; }
            } else {
                long numerator = definition.id().endsWith("density") ? ruleCounts.get("MUST") + ruleCounts.get("MAY")
                        : definition.id().endsWith("coverage") ? completeRules : completeBundles;
                long denominator = definition.id().endsWith("evidence") ? rows.size() : expected.size();
                if (definition.id().endsWith("density") && !complete || expected.isEmpty()) { status = AssessmentStatus.WITHHELD; reasons.add("POLICY_EVIDENCE_INCOMPLETE"); }
                else { ratio = Optional.of(new RatioEvidence(BigDecimal.valueOf(numerator), BigDecimal.valueOf(denominator)));
                    if (denominator == 0) { status = AssessmentStatus.NOT_APPLICABLE; reasons.add("NO_OBSERVED_FINDINGS"); }
                    else { value = Optional.of(new MetricValue(ArchitectureMetric.divide(BigDecimal.valueOf(numerator), BigDecimal.valueOf(denominator)), definition.unit())); status = complete ? AssessmentStatus.COMPLETE : AssessmentStatus.PARTIAL; }
                }
            }
            if (status != AssessmentStatus.COMPLETE && reasons.isEmpty()) reasons.add(expected.isEmpty() ? "POLICY_UNAVAILABLE" : "OBSERVED_COUNTS_WITH_OPEN_EVIDENCE");
            metrics.add(new PolicyMetrics.Metric(definition, context, status, value, ratio, List.copyOf(local), reasons));
        }
        return new PolicyMetrics.Report(context, reportStatus, counts, metrics, List.copyOf(gaps), evaluation.upstreamEvidence());
    }
    private static CapabilityGapRecord gap(EvidenceContext context, ContentDigest input, String reason, Object detail, String output) {
        return AssessmentGaps.create(context, PolicyMetrics.VERSION, input, reason, detail, AffectedOutput.Kind.METRIC, output);
    }
    private static <E extends Enum<E>> Map<String, Long> zeros(E[] values) { var counts = new TreeMap<String, Long>(); for (var value : values) counts.put(value.name(), 0L); return counts; }
    private static void increment(Map<String, Map<String, Long>> groups, String group, String quantifier) {
        groups.computeIfAbsent(group, k -> zeros(Classification.values())).merge(quantifier, 1L, Long::sum);
    }
}
