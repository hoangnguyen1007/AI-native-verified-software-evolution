package com.evolution.analysis.evolution;

import com.evolution.analysis.assessment.ArchitectureAssessment;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.metrics.PolicyMetrics;
import com.evolution.analysis.policy.*;
import java.util.*;

/** Exact report operands. Legacy publications carry the first three fields only. */
public record EvolutionReport(PolicyEvaluation.Result evaluation, Optional<PolicyMetrics.Report> metrics,
                              Optional<ArchitectureAssessment.Result> assessment,
                              Optional<PolicyCompiler.Result> compilation, Optional<PolicyGraphMapper.Plan> mapping) {
    public EvolutionReport {
        Objects.requireNonNull(evaluation);
        Objects.requireNonNull(metrics);
        Objects.requireNonNull(assessment);
        Objects.requireNonNull(compilation);
        Objects.requireNonNull(mapping);
        compilation.ifPresent(c -> {
            if (!c.identity().equals(evaluation.compilation())) throw new IllegalArgumentException("Foreign compilation");
        });
        mapping.ifPresent(m -> {
            if (!m.identity().equals(evaluation.mapping()) || !m.compilation().equals(evaluation.compilation())
                    || !m.graph().equals(evaluation.graph()) || !m.context().equals(evaluation.context()))
                throw new IllegalArgumentException("Foreign policy mapping");
        });
        metrics.ifPresent(m -> {
            var c = m.context();
            if (!c.evaluation().equals(evaluation.identity()) || !c.graph().equals(evaluation.graph())
                    || !c.partition().equals(evaluation.context()) || !c.mapping().equals(evaluation.mapping())
                    || !c.compilation().equals(evaluation.compilation()))
                throw new IllegalArgumentException("Foreign policy metrics");
        });
        assessment.ifPresent(a -> {
            var m = metrics.orElseThrow(() -> new IllegalArgumentException("Missing policy metric report"));
            if (!a.policyMetrics().equals(m.identity()) || !a.context().equals(m.context()))
                throw new IllegalArgumentException("Foreign assessment");
        });
    }

    public ContentDigest identity() {
        if (compilation.isPresent() || mapping.isPresent()) return CanonicalJson.sha256(List.of("evolution.report:v1", this));
        return CanonicalJson.sha256(Map.of("evaluation", evaluation, "metrics", metrics, "assessment", assessment));
    }
}
