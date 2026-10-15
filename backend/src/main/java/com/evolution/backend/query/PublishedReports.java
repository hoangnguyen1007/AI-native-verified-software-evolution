package com.evolution.backend.query;

import com.evolution.analysis.assessment.ArchitectureAssessment;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.metrics.PolicyMetrics;
import com.evolution.analysis.policy.PolicyEvaluation;
import java.util.*;

/** Bounded, explicitly supplied canonical M6 results. HTTP never starts repository analysis. */
public final class PublishedReports {
    public record Report(PolicyEvaluation.Result evaluation, Optional<PolicyMetrics.Report> metrics,
                         Optional<ArchitectureAssessment.Result> assessment) {
        public Report {
            Objects.requireNonNull(evaluation); Objects.requireNonNull(metrics); Objects.requireNonNull(assessment);
            if (evaluation.rules().size()>256 || evaluation.findings().size()>50)
                throw new IllegalArgumentException("Published report exceeds canonical row limits");
            for (var finding : evaluation.findings()) {
                var bundle=finding.bundle();
                if (!bundle.graph().equals(evaluation.graph()) || !bundle.context().equals(evaluation.context())
                        || !bundle.compilation().equals(evaluation.compilation()) || !bundle.mapping().equals(evaluation.mapping()))
                    throw new IllegalArgumentException("Foreign finding evidence");
            }
            metrics.ifPresent(m -> {
                var c = m.context();
                if (!c.graph().equals(evaluation.graph()) || !c.partition().equals(evaluation.context())
                        || !c.evaluation().equals(evaluation.identity()) || !c.compilation().equals(evaluation.compilation())
                        || !c.mapping().equals(evaluation.mapping())) throw new IllegalArgumentException("Foreign policy metrics");
            });
            assessment.ifPresent(a -> {
                if (metrics.isEmpty() || !a.context().equals(metrics.orElseThrow().context())
                        || !a.policyMetrics().equals(metrics.orElseThrow().identity())) throw new IllegalArgumentException("Foreign assessment");
            });
        }
        public ContentDigest identity() { return ContentDigest.sha256Utf8(CanonicalJson.write(this)); }
    }
    private final SortedMap<ContentDigest,Report> reports = new TreeMap<>();
    public synchronized ContentDigest publish(Report report) {
        Objects.requireNonNull(report);
        BoundedJson.encode(report,16_777_216);
        var identity = report.identity();
        if (!reports.containsKey(identity) && reports.size() >= 32) throw new IllegalStateException("Published report capacity");
        reports.putIfAbsent(identity,report);
        return identity;
    }
    public synchronized Optional<Report> get(ContentDigest identity) { return Optional.ofNullable(reports.get(identity)); }
    public synchronized List<ContentDigest> forGraph(ContentDigest graph) {
        return reports.entrySet().stream().filter(e -> e.getValue().evaluation().graph().equals(graph)).map(Map.Entry::getKey).toList();
    }
}
