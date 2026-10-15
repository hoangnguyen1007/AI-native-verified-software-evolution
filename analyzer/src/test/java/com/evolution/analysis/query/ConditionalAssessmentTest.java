package com.evolution.analysis.query;

import com.evolution.analysis.assessment.*;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.policy.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ConditionalPathCycleTest.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;

/** Independent Boolean/world expectations; exposure penalties never assert simultaneous activation. */
class ConditionalAssessmentTest {
    record Output(PolicyEvaluation.Result evaluation, PolicyMetrics.Report metrics, ArchitectureAssessment.Result score) {}
    static Output assess(Fixture fixture, boolean cycles) {
        return assess(fixture, cycles, PolicyMetrics.Limits.defaults());
    }
    static Output assess(Fixture fixture, boolean cycles, PolicyMetrics.Limits limits) {
        var graph = PolicyConditionalEvaluationTest.graph(fixture); var queries = new ArchitectureQueryService(graph);
        var compiled = new PolicyCompiler().compile(PolicyConditionalEvaluationTest.yaml(cycles).getBytes(StandardCharsets.UTF_8), EvidenceContext.forSnapshot(graph.snapshot()), PolicyCompiler.Limits.defaults());
        var plan = new PolicyGraphMapper().bind(compiled, queries, fixture.context, PolicyGraphMapper.Limits.defaults());
        var evaluation = new PolicyEvaluator().evaluate(compiled, plan, queries, PolicyEvaluation.Limits.defaults());
        var metrics = new PolicyMetricsService().compute(compiled, plan, evaluation, queries, limits);
        var model = new ArchitectureScoreModel(new VersionedIdentifier("test.conditional-score", "1"), List.of(new ArchitectureScoreModel.Dimension("health.policy", BigDecimal.ONE, new BigDecimal("100"), Set.of(ArchitecturePolicy.Kind.values()), Optional.empty())),
                Map.of("INFO", new BigDecimal("2"), "WARNING", new BigDecimal("10"), "ERROR", new BigDecimal("25")));
        return new Output(evaluation, metrics, new ArchitectureAssessmentService().evaluate(metrics, evaluation, queries, Optional.of(model), List.of(), ArchitectureAssessment.Limits.defaults()));
    }
    @Test void severeMayExposureRetainsFullSeverityAndBothRegionWitnesses() {
        var p = variable("p"); var output = assess(new Fixture().node("a").node("b").edge("a", "b", p, not(p), FALSE), false);
        assertEquals(ArchitectureQuery.Classification.MAY, output.evaluation().rules().getFirst().classification());
        assertEquals(1L, output.metrics().counts().findingQuantifiers().get("MAY"));
        assertEquals(new BigDecimal("75"), output.score().score().orElseThrow());
        var bundle = output.evaluation().findings().getFirst().bundle();
        assertTrue(bundle.region().witness().isPresent()); assertTrue(bundle.region().counterWitness().isPresent());
        assertEquals(List.of(bundle.region().jointRegion()), output.score().dimensions().getFirst().contributions().getFirst().affectedRegions());
        assertEquals(List.of(ArchitectureQuery.Classification.MAY), output.score().dimensions().getFirst().contributions().getFirst().quantifiers());
    }
    @Test void complementaryMayFindingsDoNotAcquireAHalfSeverityDiscount() {
        var p = variable("p"); var output = assess(new Fixture().node("a").node("b").node("c").edge("a", "b", p, not(p), FALSE).edge("b", "c", not(p), p, FALSE), false);
        assertEquals(ArchitectureQuery.Classification.MUST, output.evaluation().rules().getFirst().classification());
        assertEquals(2L, output.metrics().counts().findingQuantifiers().get("MAY"));
        assertEquals(BigDecimal.ONE, output.metrics().require("metric.policy-density").value().orElseThrow().amount());
        assertEquals(0, new BigDecimal("50").compareTo(output.score().score().orElseThrow()));
        assertTrue(output.score().reasons().stream().anyMatch(r -> r.contains("no configuration probability")));
    }
    @Test void mutuallyInfeasibleEdgesNeverCreateACyclePenalty() {
        var p = variable("p"); var output = assess(new Fixture().node("a").node("b").edge("a", "b", p, not(p), FALSE).edge("b", "a", not(p), p, FALSE), true);
        assertEquals(ArchitectureQuery.Classification.NEVER, output.evaluation().rules().getFirst().classification());
        assertTrue(output.score().dimensions().getFirst().contributions().isEmpty());
        assertEquals(0, new BigDecimal("100").compareTo(output.score().score().orElseThrow()));
    }
    @Test void explicitAndSymbolicEncodingsAgreeOnHealthWithoutConflatingTheirIdentities() {
        var p = variable("p"); var symbolic = assess(new Fixture().node("a").node("b").edge("a", "b", p, not(p), FALSE), false);
        var explicit = assess(new Fixture().node("a").node("b").explicit("a", "b", Set.of("on"), Set.of(), Set.of("off", "on"), true), false);
        assertEquals(symbolic.score().score(), explicit.score().score()); assertNotEquals(symbolic.score().identity(), explicit.score().identity());
        assertEquals(symbolic.metrics().counts().ruleQuantifiers(), explicit.metrics().counts().ruleQuantifiers());
    }
    @Test void unknownIncompleteAndEmptySpacesWithholdInsteadOfCertifyingCleanHealth() {
        for (var fixture : List.of(new Fixture().node("a").node("b").edge("a", "b", FALSE, FALSE, TRUE),
                new Fixture().node("a").node("b").explicit("a", "b", Set.of("on"), Set.of(), Set.of("off", "on"), false),
                new Fixture().node("a").node("b").explicit("a", "b", Set.of(), Set.of(), Set.of(), true))) {
            var output = assess(fixture, false); assertEquals(AssessmentStatus.WITHHELD, output.score().status());
            assertTrue(output.score().score().isEmpty()); assertFalse(output.score().confidence().sufficient());
            assertEquals(1, output.metrics().counts().declaredRules()); assertFalse(output.score().gaps().isEmpty());
        }
    }
    @Test void repeatedConditionalEvaluationPreservesDigestsAndCapabilityRecords() {
        var p = variable("p"); var fixture = new Fixture().node("a").node("b").edge("a", "b", p, not(p), FALSE);
        var first = assess(fixture, false); var second = assess(fixture, false);
        assertEquals(first.metrics().identity(), second.metrics().identity()); assertEquals(first.score().identity(), second.score().identity()); assertEquals(first.score().gaps(), second.score().gaps());
    }
    @Test void cleanConditionalRuleStillRetainsTheModeledSpaceIdentity() {
        var p = variable("p"); var output = assess(new Fixture().node("a").node("b").edge("a", "b", p, not(p), FALSE).edge("b", "a", not(p), p, FALSE), true);
        assertTrue(output.evaluation().findings().isEmpty());
        assertFalse(output.metrics().context().configurationSpaces().isEmpty(), "A clean rule still belongs to its exact modeled space");
    }
    @Test void spaceEvidenceReadLimitRetainsTheCleanRuleButWithholdsHealth() {
        var p = variable("p"); var fixture = new Fixture().node("a").node("b").edge("a", "b", p, not(p), FALSE).edge("b", "a", not(p), p, FALSE);
        var output = assess(fixture, true, new PolicyMetrics.Limits(200_000, 1));
        assertEquals(ArchitectureQuery.Classification.NEVER, output.metrics().counts().coverage().getFirst().verdict());
        assertTrue(output.metrics().gaps().stream().anyMatch(g -> g.reasonCode().equals("POLICY_METRIC_LIMIT")));
        assertEquals(AssessmentStatus.WITHHELD, output.score().status()); assertTrue(output.score().score().isEmpty());
    }
}
