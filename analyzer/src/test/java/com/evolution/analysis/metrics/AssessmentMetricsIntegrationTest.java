package com.evolution.analysis.metrics;

import com.evolution.analysis.assessment.*;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.metrics.StructuralMetricsTest.*;

/** Real M5 metric computations feed all five candidate dimensions, including their provider versions. */
class AssessmentMetricsIntegrationTest {
    static final String POLICY = """
            schema: architecture-policy:v1
            id: test.assessment
            version: "1"
            rules:
              - {id: boundary.rule, version: "1", kind: FORBIDDEN_DEPENDENCY, severity: ERROR, scope: PACKAGE, relationships: [graph.depends-on], from: {names: ['*']}, to: {names: ['*']}}
              - {id: cycle.rule, version: "1", kind: ACYCLIC, severity: ERROR, scope: PACKAGE, relationships: [graph.depends-on], within: {names: ['*']}}
            """;
    static Fixture fixture() {
        var f = new Fixture().type("p", "P", false).type("q", "Q", false).dependency("P", "Q");
        for (int i = 0; i < f.edges.size(); i++) {
            var edge = f.edges.get(i); if (!edge.kind().equals("graph.depends-on")) continue;
            var node = f.nodes.stream().filter(n -> n.identity().equals(edge.source())).findFirst().orElseThrow();
            var declaration = f.evidence.stream().filter(e -> node.evidence().contains(e.identity()) && e.family().equals("java.declaration")).findFirst().orElseThrow();
            var span = GraphJson.object(GraphJson.object(GraphJson.read(declaration.canonicalJson(), GraphJson.Limits.defaults())).get("entity")).get("declaration");
            var proof = GraphEvidence.capture("java.occurrence", Map.of("status", "RESOLVED", "derivation", "DIRECT", "span", span)); f.evidence.add(proof);
            f.edges.set(i, new KnowledgeGraph.Edge(edge.identity(), edge.context(), edge.kind(), edge.source(), edge.target(), List.of(proof.identity()), edge.region()));
        }
        return f;
    }
    static ArchitectureAssessment.Result assess(KnowledgeGraph graph, ArchitectureMetric.Limits metricLimits, java.util.function.Supplier<ArchitectureQuery.StopReason> guard) {
        var queries = new ArchitectureQueryService(graph, guard, Optional.empty());
        var compiled = new PolicyCompiler().compile(POLICY.getBytes(StandardCharsets.UTF_8), EvidenceContext.forSnapshot(graph.snapshot()), PolicyCompiler.Limits.defaults());
        var plan = new PolicyGraphMapper().bind(compiled, queries, graph.snapshot().value(), PolicyGraphMapper.Limits.defaults());
        var evaluation = new PolicyEvaluator().evaluate(compiled, plan, queries, PolicyEvaluation.Limits.defaults());
        return new ArchitectureAssessmentService().assess(compiled, plan, evaluation, queries, Optional.of(ArchitectureScoreModel.candidate(ArchitectureQuery.Scope.PACKAGE)),
                PolicyMetrics.Limits.defaults(), metricLimits, ArchitectureAssessment.Limits.defaults());
    }
    @Test void goldenGraphComputesSixtySevenPointFiveFromCanonicalInputs() {
        var result = assess(fixture().graph(), ArchitectureMetric.Limits.defaults(), () -> ArchitectureQuery.StopReason.NONE);
        assertEquals(new BigDecimal("67.5"), result.score().orElseThrow()); assertEquals(5, result.dimensions().size());
        assertEquals(AssessmentStatus.PARTIAL, result.status()); assertTrue(result.confidence().sufficient());
        assertEquals(2, result.structuralInputs().size());
        assertEquals(Set.of("m5.3-v1"), result.structuralInputs().stream().map(m -> m.metric().version()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("metric.concentration", "metric.density"), result.structuralInputs().stream().map(m -> m.metric().id()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.structuralInputs().stream().allMatch(m -> m.status() == AssessmentStatus.COMPLETE && m.context().projection().projection() == ArchitectureQuery.Projection.BOUNDED_UNION));
    }
    @Test void aSourcePopulationGapCannotBecomeAnImprovedScore() {
        var f = fixture(); var good = assess(f.graph(), ArchitectureMetric.Limits.defaults(), () -> ArchitectureQuery.StopReason.NONE); assertTrue(good.score().isPresent());
        var doc = f.nodes.stream().filter(n -> n.kind().equals("SOURCE_DOCUMENT")).findFirst().orElseThrow();
        f.nodes.remove(doc);
        var degraded = assess(f.graph(), ArchitectureMetric.Limits.defaults(), () -> ArchitectureQuery.StopReason.NONE);
        assertEquals(AssessmentStatus.WITHHELD, degraded.status()); assertTrue(degraded.score().isEmpty()); assertFalse(degraded.confidence().sufficient());
        assertFalse(degraded.gaps().isEmpty());
    }
    @Test void metricBudgetAndSharedCancellationPropagateIntoWithheldAssessment() {
        var graph = fixture().graph();
        var limited = assess(graph, new ArchitectureMetric.Limits(1, 1000, 1000, 1), () -> ArchitectureQuery.StopReason.NONE);
        assertEquals(AssessmentStatus.WITHHELD, limited.status()); assertTrue(limited.score().isEmpty());
        var cancelled = assess(graph, ArchitectureMetric.Limits.defaults(), () -> ArchitectureQuery.StopReason.CANCELLED);
        assertEquals(AssessmentStatus.WITHHELD, cancelled.status()); assertTrue(cancelled.score().isEmpty());
        assertTrue(cancelled.gaps().stream().anyMatch(g -> g.reasonCode().equals("ASSESSMENT_CANCELLED")));
    }
    @Test void codecRoundTripAndTelemetryDoNotChangeCanonicalAssessmentIdentity() {
        var graph = fixture().graph(); var before = assess(graph, ArchitectureMetric.Limits.defaults(), () -> ArchitectureQuery.StopReason.NONE);
        var restored = new GraphSnapshotCodec().decode(new GraphSnapshotCodec().encode(graph), GraphJson.Limits.defaults());
        var after = assess(restored, ArchitectureMetric.Limits.defaults(), () -> ArchitectureQuery.StopReason.NONE);
        assertEquals(before.identity(), after.identity()); assertEquals(before.gaps(), after.gaps());
        assertEquals(before.identity(), before.withComputedAt(java.time.Instant.parse("2025-01-01T00:00:00Z")).identity());
    }
    @Test void twoDimensionsCanReuseOneMetricWithoutRecomputingDuplicateOperands() {
        var graph = fixture().graph(); var queries = new ArchitectureQueryService(graph);
        var compiled = new PolicyCompiler().compile(POLICY.getBytes(StandardCharsets.UTF_8), EvidenceContext.forSnapshot(graph.snapshot()), PolicyCompiler.Limits.defaults());
        var plan = new PolicyGraphMapper().bind(compiled, queries, graph.snapshot().value(), PolicyGraphMapper.Limits.defaults());
        var evaluation = new PolicyEvaluator().evaluate(compiled, plan, queries, PolicyEvaluation.Limits.defaults());
        var baseline = ArchitectureScoreModel.candidate(ArchitectureQuery.Scope.PACKAGE);
        var original = baseline.dimensions().stream().filter(d -> d.id().equals("health.coupling")).findFirst().orElseThrow();
        var extra = new ArchitectureScoreModel.Dimension("health.density-lens", BigDecimal.ONE, original.penaltyCap(), Set.of(), original.metric());
        var dimensions = new ArrayList<>(baseline.dimensions()); dimensions.add(extra);
        var model = new ArchitectureScoreModel(new com.evolution.analysis.contract.common.VersionedIdentifier("test.reused-metric", "1"), dimensions, baseline.severityPenalties());
        var result = new ArchitectureAssessmentService().assess(compiled, plan, evaluation, queries, Optional.of(model), PolicyMetrics.Limits.defaults(), ArchitectureMetric.Limits.defaults(), ArchitectureAssessment.Limits.defaults());
        assertTrue(result.score().isPresent()); assertEquals(2, result.structuralInputs().size());
        assertEquals(3, result.confidence().requiredMetrics()); assertEquals(3, result.confidence().completeMetrics());
    }
}
