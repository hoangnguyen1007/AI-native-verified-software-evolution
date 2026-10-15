package com.evolution.analysis.policy;

import com.evolution.analysis.assessment.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.metrics.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.query.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureAssessmentTest {
    record Input(PolicyGraphMapperTest.Fixture fixture, PolicyCompiler.Result compiled, PolicyGraphMapper.Plan plan, PolicyEvaluation.Result evaluation,
                 ArchitectureQueryService queries, PolicyMetrics.Report metrics) {}
    static Input input(PolicyGraphMapperTest.Fixture f, String yaml) {
        var compiled = PolicyCompilerTest.compile(yaml); var queries = new ArchitectureQueryService(f.graph());
        var plan = new PolicyGraphMapper().bind(compiled, queries, f.context, PolicyGraphMapper.Limits.defaults());
        var evaluation = new PolicyEvaluator().evaluate(compiled, plan, queries, PolicyEvaluation.Limits.defaults());
        return new Input(f, compiled, plan, evaluation, queries, new PolicyMetricsService().compute(compiled, plan, evaluation, queries, PolicyMetrics.Limits.defaults()));
    }
    static ArchitectureAssessment.Result score(Input in, ArchitectureScoreModel model, List<ArchitectureMetric.Result> structural, ArchitectureAssessment.Limits limits) {
        return new ArchitectureAssessmentService().evaluate(in.metrics(), in.evaluation(), in.queries(), Optional.of(model), structural, limits);
    }
    static ArchitectureScoreModel policyModel() {
        return new ArchitectureScoreModel(new VersionedIdentifier("test.policy-score", "1"), List.of(
                new ArchitectureScoreModel.Dimension("health.policy", BigDecimal.ONE, new BigDecimal("100"), Set.of(ArchitecturePolicy.Kind.values()), Optional.empty())),
                Map.of("INFO", new BigDecimal("2"), "WARNING", new BigDecimal("10"), "ERROR", new BigDecimal("25")));
    }
    static ArchitectureAssessment.Result assess(PolicyGraphMapperTest.Fixture f, String yaml, Optional<ArchitectureScoreModel> model, List<ArchitectureMetric.Result> structural) {
        var compiled = PolicyCompilerTest.compile(yaml); var service = new ArchitectureQueryService(f.graph());
        var plan = new PolicyGraphMapper().bind(compiled, service, f.context, PolicyGraphMapper.Limits.defaults());
        var evaluated = new PolicyEvaluator().evaluate(compiled, plan, service, PolicyEvaluation.Limits.defaults());
        var metrics = new PolicyMetricsService().compute(compiled, plan, evaluated, service, PolicyMetrics.Limits.defaults());
        return new ArchitectureAssessmentService().evaluate(metrics, evaluated, service, model, structural, ArchitectureAssessment.Limits.defaults());
    }
    @Test void aKnownViolationHasAnExplainableTwentyFivePointPenalty() {
        var f = PolicyEvaluatorTest.fixture().edge("Controller", "Store"); PolicyEvaluatorTest.prove(f);
        var result = assess(f, PolicyCompilerTest.VALID, Optional.of(policyModel()), List.of());
        assertEquals(new BigDecimal("75"), result.score().orElseThrow());
        var dimension = result.dimensions().getFirst();
        assertEquals(new BigDecimal("25"), dimension.appliedPenalty());
        assertEquals(1, dimension.contributions().size()); assertFalse(dimension.contributions().getFirst().affectedRegions().isEmpty());
        assertEquals("PROVISIONAL", result.calibration()); assertTrue(result.confidence().sufficient());
    }
    @Test void cleanPolicyHasOneHundredButMissingPolicyOrModelHasNoScore() {
        var f = PolicyEvaluatorTest.fixture();
        var clean = assess(f, PolicyCompilerTest.VALID, Optional.of(policyModel()), List.of());
        assertEquals(new BigDecimal("100").stripTrailingZeros(), clean.score().orElseThrow()); assertEquals(AssessmentStatus.PARTIAL, clean.status());
        assertTrue(clean.gaps().stream().anyMatch(g -> g.reasonCode().equals("SCORE_MODEL_NOT_APPROVED")));
        assertTrue(clean.confidence().sufficient());
        for (var result : List.of(assess(f, PolicyCompilerTest.VALID.replace("ERROR", "INVALID"), Optional.of(policyModel()), List.of()),
                assess(f, PolicyCompilerTest.VALID, Optional.empty(), List.of()))) {
            assertEquals(AssessmentStatus.WITHHELD, result.status()); assertTrue(result.score().isEmpty()); assertFalse(result.gaps().isEmpty());
        }
    }
    @Test void evidenceLossAndUnknownSelectorsWithholdHealthInsteadOfImprovingIt() {
        var good = PolicyEvaluatorTest.fixture().edge("Controller", "Store"); PolicyEvaluatorTest.prove(good);
        assertEquals(new BigDecimal("75"), assess(good, PolicyCompilerTest.VALID, Optional.of(policyModel()), List.of()).score().orElseThrow());
        for (var result : List.of(assess(PolicyEvaluatorTest.fixture().edge("Controller", "Store"), PolicyCompilerTest.VALID, Optional.of(policyModel()), List.of()),
                assess(good, PolicyCompilerTest.VALID.replace("com.example.web.**", "missing"), Optional.of(policyModel()), List.of()))) {
            assertEquals(AssessmentStatus.WITHHELD, result.status()); assertTrue(result.score().isEmpty()); assertFalse(result.confidence().sufficient());
        }
    }
    @Test void duplicateSymptomsAcrossRulesUseTheMaximumSeverityOnce() {
        var f = PolicyEvaluatorTest.fixture().edge("Controller", "Store"); PolicyEvaluatorTest.prove(f);
        var duplicate = PolicyCompilerTest.VALID.substring(PolicyCompilerTest.VALID.indexOf("  - id:")).replace("web.persistence", "duplicate.symptom").replace("ERROR", "WARNING");
        var result = assess(f, PolicyCompilerTest.VALID + duplicate, Optional.of(policyModel()), List.of());
        assertEquals(new BigDecimal("75"), result.score().orElseThrow());
        assertEquals(1, result.dimensions().getFirst().contributions().size());
        assertEquals(6, result.dimensions().getFirst().contributions().getFirst().inputs().size());
    }
    @Test void severitySensitivityAndAdditionalViolationsAreMonotoneAndCapsAreVisible() {
        var f = PolicyEvaluatorTest.fixture().edge("Controller", "Store"); PolicyEvaluatorTest.prove(f);
        var expected = Map.of("INFO", "98", "WARNING", "90", "ERROR", "75");
        for (var entry : expected.entrySet()) assertEquals(new BigDecimal(entry.getValue()).stripTrailingZeros(), assess(f, PolicyCompilerTest.VALID.replace("ERROR", entry.getKey()), Optional.of(policyModel()), List.of()).score().orElseThrow());
        for (int count = 1; count <= 5; count++) {
            var many = PolicyEvaluatorTest.fixture(); for (int i = 0; i < count; i++) many.type("com.example.web", "Client" + i).edge("Client" + i, "Store"); PolicyEvaluatorTest.prove(many);
            var result = assess(many, PolicyCompilerTest.VALID, Optional.of(policyModel()), List.of());
            assertEquals(BigDecimal.valueOf(Math.max(0, 100 - 25L * count)).stripTrailingZeros(), result.score().orElseThrow());
            assertEquals(BigDecimal.valueOf(25L * count), result.dimensions().getFirst().uncappedPenalty());
            assertEquals(0, BigDecimal.valueOf(Math.min(100, 25L * count)).compareTo(result.dimensions().getFirst().appliedPenalty()));
        }
    }
    @Test void modelWeightsDoNotDilutePenaltyWhenAnUnrelatedCleanRuleIsAdded() {
        var f = PolicyEvaluatorTest.fixture().edge("Controller", "Store"); PolicyEvaluatorTest.prove(f);
        String clean = PolicyCompilerTest.VALID.substring(PolicyCompilerTest.VALID.indexOf("  - id:")).replace("web.persistence", "clean.rule").replace("FORBIDDEN_DEPENDENCY", "BOUNDARY").replace("to:", "allowed:");
        assertEquals(new BigDecimal("75"), assess(f, PolicyCompilerTest.VALID + clean, Optional.of(policyModel()), List.of()).score().orElseThrow());
        var baseline = policyModel(); var cap = new ArchitectureScoreModel(baseline.model(), List.of(new ArchitectureScoreModel.Dimension("health.policy", BigDecimal.ONE, new BigDecimal("10"), Set.of(ArchitecturePolicy.Kind.values()), Optional.empty())), baseline.severityPenalties());
        var limited = assess(f, PolicyCompilerTest.VALID, Optional.of(cap), List.of());
        assertEquals(new BigDecimal("90").stripTrailingZeros(), limited.score().orElseThrow()); assertEquals(new BigDecimal("25"), limited.dimensions().getFirst().uncappedPenalty());
        assertEquals(new BigDecimal("10").stripTrailingZeros(), limited.dimensions().getFirst().cap());
    }
    static ArchitectureMetric.Result metric(Input in, String id, String numerator, String denominator) {
        var definition = MetricCatalog.require(id); var request = new ArchitectureQuery.Request(in.queries().graph().identity(), in.fixture().context, ArchitectureQuery.Scope.PACKAGE,
                ArchitectureQuery.Projection.BOUNDED_UNION, ArchitectureQuery.Direction.OUTGOING, Set.of("graph.depends-on"), ArchitectureQuery.Population.all(), false, Optional.empty(), ArchitectureQuery.Limits.defaults());
        var context = new ArchitectureMetric.Context(request.graph(), request.context(), List.of(), request, definition.population(), ArchitectureMetric.Limits.defaults(), Optional.empty());
        var n = new BigDecimal(numerator); var d = new BigDecimal(denominator); boolean zero = d.signum() == 0;
        return new ArchitectureMetric.Result(definition.metric(), definition.identity(), context, request.context(), zero ? AssessmentStatus.NOT_APPLICABLE : AssessmentStatus.COMPLETE,
                zero ? Optional.empty() : Optional.of(new MetricValue(ArchitectureMetric.divide(n, d), "ratio")), Optional.of(new RatioEvidence(n, d)), Map.of(), List.of(), List.of(), zero ? List.of("ZERO_DENOMINATOR") : List.of(), Optional.empty());
    }
    @Test void fiveDimensionGoldenCaseShowsExactWeightsOperandsAndContributions() {
        var f = PolicyEvaluatorTest.fixture().edge("Controller", "Store"); PolicyEvaluatorTest.prove(f);
        var in = input(f, PolicyCompilerTest.VALID + PolicyEvaluatorTest.CYCLE.substring(PolicyEvaluatorTest.CYCLE.indexOf("  - id:")).replace("web.persistence", "cycle.rule"));
        var result = score(in, ArchitectureScoreModel.candidate(ArchitectureQuery.Scope.PACKAGE), List.of(metric(in, "metric.density", "1", "4"), metric(in, "metric.concentration", "1", "2")), ArchitectureAssessment.Limits.defaults());
        // 25*75 + 20*100 + 20*75 + 25*75 + 10*50 = 7750, total weight = 100.
        assertEquals(new BigDecimal("77.5"), result.score().orElseThrow()); assertEquals(new BigDecimal("7750"), result.weightedNumerator());
        assertEquals(new BigDecimal("100"), result.applicableWeight()); assertEquals(5, result.dimensions().size());
        var coupling = result.dimensions().stream().filter(d -> d.id().equals("health.coupling")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("25"), coupling.appliedPenalty()); assertEquals(new BigDecimal("4"), coupling.contributions().getFirst().denominator());
        assertEquals(2, result.confidence().requiredMetrics()); assertEquals(2, result.confidence().completeMetrics());
    }
    @Test void missingRequiredDimensionNeverRenormalizesTheRemainingWeights() {
        var in = input(PolicyEvaluatorTest.fixture(), PolicyCompilerTest.VALID);
        var result = score(in, ArchitectureScoreModel.candidate(ArchitectureQuery.Scope.PACKAGE), List.of(metric(in, "metric.density", "0", "2")), ArchitectureAssessment.Limits.defaults());
        assertEquals(AssessmentStatus.WITHHELD, result.status()); assertTrue(result.score().isEmpty());
        assertTrue(result.gaps().stream().anyMatch(g -> g.reasonCode().equals("REQUIRED_METRIC_UNAVAILABLE")));
    }
    @Test void verifiedInapplicableMetricsAreDistinctFromMissingMetrics() {
        var in = input(PolicyEvaluatorTest.fixture(), PolicyCompilerTest.VALID);
        var result = score(in, ArchitectureScoreModel.candidate(ArchitectureQuery.Scope.PACKAGE), List.of(metric(in, "metric.density", "0", "0"), metric(in, "metric.concentration", "0", "0")), ArchitectureAssessment.Limits.defaults());
        assertEquals(new BigDecimal("100").stripTrailingZeros(), result.score().orElseThrow()); assertEquals(new BigDecimal("50"), result.applicableWeight());
        assertEquals(3, result.dimensions().stream().filter(d -> d.status() == AssessmentStatus.NOT_APPLICABLE).count()); assertTrue(result.confidence().sufficient());
    }
    @Test void contributionExhaustionPreservesKnownPenaltyButWithholdsTheTotal() {
        var f = PolicyEvaluatorTest.fixture().type("com.example.web", "Other").edge("Controller", "Store").edge("Other", "Store"); PolicyEvaluatorTest.prove(f);
        var result = score(input(f, PolicyCompilerTest.VALID), policyModel(), List.of(), new ArchitectureAssessment.Limits(1, 16));
        assertEquals(AssessmentStatus.WITHHELD, result.status()); assertTrue(result.score().isEmpty()); assertEquals(1, result.dimensions().getFirst().contributions().size());
        assertTrue(result.gaps().stream().anyMatch(g -> g.reasonCode().equals("ASSESSMENT_CONTRIBUTION_LIMIT")));
    }
    @Test void formulaVersionModelParametersAndTelemetryHaveExplicitIdentityBehavior() {
        var in = input(PolicyEvaluatorTest.fixture(), PolicyCompilerTest.VALID); var model = policyModel();
        var a = score(in, model, List.of(), ArchitectureAssessment.Limits.defaults());
        var b = score(in, new ArchitectureScoreModel(new VersionedIdentifier(model.model().id(), "2"), model.dimensions(), model.severityPenalties()), List.of(), ArchitectureAssessment.Limits.defaults());
        assertNotEquals(a.identity(), b.identity()); assertEquals(a.score(), b.score());
        assertEquals(a.identity(), a.withComputedAt(java.time.Instant.parse("2020-01-01T00:00:00Z")).identity());
        assertEquals("m6.3-v1", ArchitectureAssessment.VERSION.version()); assertEquals("m6.3-exposure-v1", a.formula().version());
        var locale = Locale.getDefault(); var zone = TimeZone.getDefault();
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); TimeZone.setDefault(TimeZone.getTimeZone("UTC")); assertEquals(a.identity(), score(in, model, List.of(), ArchitectureAssessment.Limits.defaults()).identity()); }
        finally { Locale.setDefault(locale); TimeZone.setDefault(zone); }
    }
    @Test void invalidParametersCannotCreateNonmonotoneOrUnboundedScores() {
        var model = policyModel();
        assertThrows(IllegalArgumentException.class, () -> new ArchitectureScoreModel(model.model(), model.dimensions(), Map.of("INFO", BigDecimal.ONE, "WARNING", new BigDecimal("50"), "ERROR", new BigDecimal("25"))));
        assertThrows(IllegalArgumentException.class, () -> new ArchitectureScoreModel.Dimension("health.invalid", BigDecimal.ZERO, BigDecimal.ONE, Set.of(ArchitecturePolicy.Kind.ACYCLIC), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ArchitectureScoreModel.MetricInput(MetricCatalog.require("metric.density").metric(), ArchitectureQuery.Scope.PACKAGE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> new ArchitectureAssessment.Limits(401, 16));
        assertThrows(UnsupportedOperationException.class, () -> model.severityPenalties().clear());
    }
    @Test void thresholdAndSaturationSensitivityUsesExactRatioOperands() {
        var in = input(PolicyEvaluatorTest.fixture(), PolicyCompilerTest.VALID); var baseline = policyModel();
        var spec = new ArchitectureScoreModel.MetricInput(MetricCatalog.require("metric.density").metric(), ArchitectureQuery.Scope.PACKAGE, new BigDecimal("0.25"), new BigDecimal("0.75"), new BigDecimal("100"));
        var model = new ArchitectureScoreModel(baseline.model(), List.of(new ArchitectureScoreModel.Dimension("health.coupling", BigDecimal.ONE, new BigDecimal("100"), Set.of(), Optional.of(spec))), baseline.severityPenalties());
        String[] expected = {"100", "100", "50", "0", "0"};
        for (int numerator = 0; numerator <= 4; numerator++) {
            var result = score(in, model, List.of(metric(in, "metric.density", Integer.toString(numerator), "4")), ArchitectureAssessment.Limits.defaults());
            assertEquals(0, new BigDecimal(expected[numerator]).compareTo(result.score().orElseThrow()));
        }
    }
    @Test void incompatibleProjectionVersionAndMalformedRatioCannotCertifyHealth() {
        var in = input(PolicyEvaluatorTest.fixture(), PolicyCompilerTest.VALID); var good = metric(in, "metric.density", "1", "2");
        var projection = good.context().projection();
        var must = new ArchitectureQuery.Request(projection.graph(), projection.context(), projection.scope(), ArchitectureQuery.Projection.MUST, projection.direction(), projection.relationshipKinds(), projection.population(), false, Optional.empty(), projection.limits());
        var foreignContext = new ArchitectureMetric.Context(good.context().graph(), good.context().partition(), List.of(), must, good.context().population(), good.context().limits(), Optional.empty());
        var wrongProjection = new ArchitectureMetric.Result(good.metric(), good.definition(), foreignContext, good.subject(), good.status(), good.value(), good.ratio(), good.details(), good.evidence(), good.gaps(), good.limitations(), Optional.empty());
        var wrongVersion = new ArchitectureMetric.Result(new VersionedIdentifier(good.metric().id(), "future"), good.definition(), good.context(), good.subject(), good.status(), good.value(), good.ratio(), good.details(), good.evidence(), good.gaps(), good.limitations(), Optional.empty());
        var wrongValue = new ArchitectureMetric.Result(good.metric(), good.definition(), good.context(), good.subject(), good.status(), Optional.of(new MetricValue(BigDecimal.ZERO, "ratio")), good.ratio(), good.details(), good.evidence(), good.gaps(), good.limitations(), Optional.empty());
        for (var invalid : List.of(wrongProjection, wrongVersion, wrongValue)) {
            var result = score(in, ArchitectureScoreModel.candidate(ArchitectureQuery.Scope.PACKAGE), List.of(invalid, metric(in, "metric.concentration", "1", "2")), ArchitectureAssessment.Limits.defaults());
            assertEquals(AssessmentStatus.WITHHELD, result.status()); assertTrue(result.score().isEmpty());
        }
    }
    @Test void duplicateInputsAndForeignGraphsAreCallerErrorsWhileInputBudgetsAreTyped() {
        var in = input(PolicyEvaluatorTest.fixture(), PolicyCompilerTest.VALID); var metric = metric(in, "metric.density", "1", "2");
        assertThrows(IllegalArgumentException.class, () -> score(in, policyModel(), List.of(metric, metric), ArchitectureAssessment.Limits.defaults()));
        var limited = score(in, ArchitectureScoreModel.candidate(ArchitectureQuery.Scope.PACKAGE), List.of(metric, metric(in, "metric.concentration", "1", "2")), new ArchitectureAssessment.Limits(400, 1));
        assertEquals(AssessmentStatus.WITHHELD, limited.status()); assertTrue(limited.gaps().stream().anyMatch(g -> g.reasonCode().equals("ASSESSMENT_INPUT_LIMIT")));
        assertThrows(IllegalArgumentException.class, () -> new ArchitectureAssessmentService().evaluate(in.metrics(), in.evaluation(), new ArchitectureQueryService(PolicyEvaluatorTest.fixture().type("extra", "Extra").graph()), Optional.of(policyModel()), List.of(), ArchitectureAssessment.Limits.defaults()));
    }
    @Test void cancellationMemoryAndTimeGuardsNeverReturnAPartialHealthyScore() {
        var in = input(PolicyEvaluatorTest.fixture(), PolicyCompilerTest.VALID);
        for (var stop : List.of(ArchitectureQuery.StopReason.CANCELLED, ArchitectureQuery.StopReason.MEMORY, ArchitectureQuery.StopReason.TIME)) {
            var interrupted = new ArchitectureQueryService(in.queries().graph(), () -> stop, Optional.empty());
            var result = new ArchitectureAssessmentService().evaluate(in.metrics(), in.evaluation(), interrupted, Optional.of(policyModel()), List.of(), ArchitectureAssessment.Limits.defaults());
            assertEquals(AssessmentStatus.WITHHELD, result.status()); assertTrue(result.score().isEmpty());
            assertTrue(result.gaps().stream().anyMatch(g -> g.reasonCode().equals("ASSESSMENT_" + stop.name())));
        }
    }
    @Test void graphPersistenceAndModelReorderingPreserveTheAssessmentIdentity() {
        var in = input(PolicyEvaluatorTest.fixture(), PolicyCompilerTest.VALID); var model = policyModel();
        var before = score(in, model, List.of(), ArchitectureAssessment.Limits.defaults());
        var restored = new GraphSnapshotCodec().decode(new GraphSnapshotCodec().encode(in.queries().graph()), GraphJson.Limits.defaults());
        var after = new ArchitectureAssessmentService().evaluate(in.metrics(), in.evaluation(), new ArchitectureQueryService(restored), Optional.of(model), List.of(), ArchitectureAssessment.Limits.defaults());
        assertEquals(before.identity(), after.identity()); assertEquals(before, after);
    }
    @Test void dimensionWeightSensitivityHasIndependentlyHandComputedExpectedResults() {
        var f = PolicyEvaluatorTest.fixture().edge("Controller", "Store"); PolicyEvaluatorTest.prove(f);
        var in = input(f, PolicyCompilerTest.VALID + PolicyEvaluatorTest.CYCLE.substring(PolicyEvaluatorTest.CYCLE.indexOf("  - id:")).replace("web.persistence", "cycle.rule"));
        var baseline = ArchitectureScoreModel.candidate(ArchitectureQuery.Scope.PACKAGE);
        var operands = List.of(metric(in, "metric.density", "1", "4"), metric(in, "metric.concentration", "1", "2"));
        var changes = List.of(List.of("health.policy", "50", "77"), List.of("health.coupling", "10", "77.777777777778"),
                List.of("health.coupling", "30", "77.272727272727"), List.of("health.hotspots", "20", "75"));
        int variant = 0;
        for (var change : changes) {
            var dimensions = baseline.dimensions().stream().map(d -> d.id().equals(change.getFirst()) ? new ArchitectureScoreModel.Dimension(d.id(), new BigDecimal(change.get(1)), d.penaltyCap(), d.ruleKinds(), d.metric()) : d).toList();
            var model = new ArchitectureScoreModel(new VersionedIdentifier("test.weight-sensitivity", Integer.toString(++variant)), dimensions, baseline.severityPenalties());
            var result = score(in, model, operands, ArchitectureAssessment.Limits.defaults());
            assertEquals(0, new BigDecimal(change.get(2)).compareTo(result.score().orElseThrow()));
        }
    }
    @Test void aKnownMustRuleWithAnOpenFindingDenominatorStillWithholdsHealth() {
        var f = PolicyEvaluatorTest.fixture().type("com.example.web", "Other").edge("Controller", "Store").edge("Other", "Store"); PolicyEvaluatorTest.prove(f);
        var in = input(f, PolicyCompilerTest.VALID);
        var limited = new PolicyEvaluator().evaluate(in.compiled(), in.plan(), in.queries(), new PolicyEvaluation.Limits(1, 1024, 500_000));
        assertEquals(ArchitectureQuery.Classification.MUST, limited.rules().getFirst().classification()); assertEquals(AssessmentStatus.PARTIAL, limited.status());
        var metrics = new PolicyMetricsService().compute(in.compiled(), in.plan(), limited, in.queries(), PolicyMetrics.Limits.defaults());
        var result = new ArchitectureAssessmentService().evaluate(metrics, limited, in.queries(), Optional.of(policyModel()), List.of(), ArchitectureAssessment.Limits.defaults());
        assertEquals(AssessmentStatus.WITHHELD, result.status()); assertTrue(result.score().isEmpty());
        assertEquals(new BigDecimal("25"), result.dimensions().getFirst().uncappedPenalty());
    }
}
