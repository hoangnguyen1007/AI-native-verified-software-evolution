package com.evolution.backend.query;

import com.evolution.analysis.assessment.ArchitectureAssessment;
import com.evolution.analysis.assessment.ArchitectureAssessmentService;
import com.evolution.analysis.assessment.ArchitectureScoreModel;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.metrics.ArchitectureMetric;
import com.evolution.analysis.metrics.PolicyMetrics;
import com.evolution.analysis.metrics.PolicyMetricsService;
import com.evolution.analysis.policy.PolicyCompiler;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.policy.PolicyEvaluator;
import com.evolution.analysis.policy.PolicyGraphMapper;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Evaluates built-in or owner-authored policy against one already-published context. */
final class WorkbenchPolicyAssessment {
    enum Kind { DEFAULT, CUSTOM }

    record Result(Kind policyKind, String policy, ContentDigest policyDigest, Scope scope,
                  AssessmentStatus status, Optional<ContentDigest> report,
                  Optional<ArchitectureAssessment.Result> assessment,
                  List<PolicyCompiler.Problem> problems, List<CapabilityGapRecord> gaps) {}

    static Scope scope(KnowledgeGraph graph, KnowledgeGraph.Context context) {
        return graph.evidence(context.evidence()).filter(evidence -> evidence.family().equals("spring.context"))
                .map(evidence -> Scope.SPRING_COMPONENT).orElse(Scope.TYPE);
    }

    static Result evaluate(ArchitectureQueryService queries, KnowledgeGraph.Context context, Scope scope,
                           Optional<String> custom, PublishedReports reports, Runnable checkpoint) {
        String text = custom.orElseGet(() -> defaultPolicy(scope));
        Kind kind = custom.isPresent() ? Kind.CUSTOM : Kind.DEFAULT;
        var compilation = new PolicyCompiler().compile(text.getBytes(StandardCharsets.UTF_8),
                EvidenceContext.forSnapshot(context.snapshot()), PolicyCompiler.Limits.defaults());
        if (compilation.policy().isEmpty()) {
            return new Result(kind, text, compilation.source(), scope, AssessmentStatus.WITHHELD,
                    Optional.empty(), Optional.empty(), compilation.problems(), compilation.gaps());
        }
        checkpoint.run();
        var plan = new PolicyGraphMapper().bind(compilation, queries, context.identity(), PolicyGraphMapper.Limits.defaults());
        var evaluation = new PolicyEvaluator().evaluate(compilation, plan, queries, PolicyEvaluation.Limits.defaults());
        var metrics = new PolicyMetricsService().compute(compilation, plan, evaluation, queries, PolicyMetrics.Limits.defaults());
        var score = new ArchitectureAssessmentService().assess(metrics, evaluation, queries,
                Optional.of(ArchitectureScoreModel.candidate(scope)), plan.limits().query(),
                ArchitectureMetric.Limits.defaults(), ArchitectureAssessment.Limits.defaults());
        checkpoint.run();
        var report = reports.publish(new PublishedReports.Report(evaluation, Optional.of(metrics), Optional.of(score)));
        return new Result(kind, text, compilation.source(), scope, score.status(),
                Optional.of(report), Optional.of(score), List.of(), score.gaps());
    }

    private static String defaultPolicy(Scope scope) {
        return """
                schema: architecture-policy:v1
                id: workbench.default
                version: "1"
                rules:
                  - id: workbench.no-dependency-cycles
                    version: "1"
                    kind: ACYCLIC
                    severity: ERROR
                    scope: %s
                    relationships: [%s]
                    within: {origins: [PROJECT]}
                """.formatted(scope, scope == Scope.SPRING_COMPONENT ? "spring.selected-binding" : "graph.depends-on");
    }
}
