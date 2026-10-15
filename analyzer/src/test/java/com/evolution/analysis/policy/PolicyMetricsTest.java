package com.evolution.analysis.policy;

import com.evolution.analysis.metrics.*;
import com.evolution.analysis.query.*;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PolicyMetricsTest {
    static PolicyMetrics.Report metrics(PolicyGraphMapperTest.Fixture fixture, String yaml) {
        var compilation = PolicyCompilerTest.compile(yaml);
        var queries = new ArchitectureQueryService(fixture.graph());
        var plan = new PolicyGraphMapper().bind(compilation, queries, fixture.context, PolicyGraphMapper.Limits.defaults());
        var evaluation = new PolicyEvaluator().evaluate(compilation, plan, queries, PolicyEvaluation.Limits.defaults());
        return new PolicyMetricsService().compute(compilation, plan, evaluation, queries, PolicyMetrics.Limits.defaults());
    }
    @Test void violationAndCleanRuleStayInTheDeclaredDenominator() {
        var f = PolicyEvaluatorTest.fixture().edge("Controller", "Store"); PolicyEvaluatorTest.prove(f);
        String second = PolicyCompilerTest.VALID.substring(PolicyCompilerTest.VALID.indexOf("  - id:"))
                .replace("web.persistence", "reverse.clean").replace("com.example.web.**", "TEMP")
                .replace("com.example.persistence.**", "com.example.web.**").replace("TEMP", "com.example.persistence.**");
        var report = metrics(f, PolicyCompilerTest.VALID + second);
        assertEquals(2, report.counts().declaredRules());
        assertEquals(1L, report.counts().ruleQuantifiers().get("MUST"));
        assertEquals(1L, report.counts().ruleQuantifiers().get("NEVER"));
        assertEquals(1, report.counts().findings().size());
        assertEquals(new BigDecimal("0.5"), report.require("metric.policy-density").value().orElseThrow().amount());
        assertEquals(BigDecimal.ONE, report.require("metric.policy-coverage").value().orElseThrow().amount());
        assertEquals(AssessmentStatus.COMPLETE, report.status());
    }
    @Test void unknownFindingsAreVisibleAndCannotReduceViolationDensityToZero() {
        var report = metrics(PolicyEvaluatorTest.fixture().edge("Controller", "Store"), PolicyCompilerTest.VALID);
        assertEquals(1L, report.counts().ruleQuantifiers().get("UNKNOWN"));
        assertEquals(1L, report.counts().findingQuantifiers().get("UNKNOWN"));
        assertEquals(1L, report.counts().bySeverity().get("ERROR").get("UNKNOWN"));
        assertEquals(1L, report.counts().byScope().get("TYPE").get("UNKNOWN"));
        assertEquals(1L, report.counts().byKind().get("FORBIDDEN_DEPENDENCY").get("UNKNOWN"));
        assertEquals(AssessmentStatus.WITHHELD, report.require("metric.policy-density").status());
        assertTrue(report.require("metric.policy-density").value().isEmpty());
        assertEquals(BigDecimal.ZERO, report.require("metric.policy-coverage").value().orElseThrow().amount());
        assertEquals(BigDecimal.ONE, report.require("metric.policy-coverage").ratio().orElseThrow().denominator());
        assertFalse(report.gaps().isEmpty());
    }
    @Test void cleanRulesHaveNoWarningAndNoInventedEvidenceRatio() {
        var report = metrics(PolicyEvaluatorTest.fixture(), PolicyCompilerTest.VALID);
        assertEquals(1L, report.counts().ruleQuantifiers().get("NEVER")); assertTrue(report.counts().findings().isEmpty());
        assertEquals(BigDecimal.ZERO, report.require("metric.policy-density").value().orElseThrow().amount());
        assertEquals(AssessmentStatus.NOT_APPLICABLE, report.require("metric.policy-evidence").status());
        assertEquals(BigDecimal.ZERO, report.require("metric.policy-evidence").ratio().orElseThrow().denominator());
        assertEquals(0L, report.counts().byRule().get("web.persistence").get("NEVER"));
    }
    @Test void missingSelectorAndInvalidPolicyNeverBecomePerfectCoverage() {
        var report = metrics(PolicyEvaluatorTest.fixture(), PolicyCompilerTest.VALID.replace("com.example.web.**", "missing"));
        assertEquals(1, report.counts().declaredRules()); assertEquals(0, report.counts().completeRules());
        assertEquals(AssessmentStatus.WITHHELD, report.require("metric.policy-density").status());
        report = metrics(PolicyEvaluatorTest.fixture(), PolicyCompilerTest.VALID.replace("ERROR", "INVALID"));
        assertEquals(AssessmentStatus.WITHHELD, report.status()); assertEquals(0, report.counts().declaredRules());
        for (String id : List.of("metric.policy-findings", "metric.policy-density", "metric.policy-coverage", "metric.policy-evidence")) {
            assertEquals(AssessmentStatus.WITHHELD, report.require(id).status()); assertTrue(report.require(id).value().isEmpty());
        }
    }
    @Test void waiverAndAuditNumbersRequireTheirOwnEvidence() {
        var report = metrics(PolicyEvaluatorTest.fixture(), PolicyCompilerTest.VALID);
        assertEquals(AssessmentStatus.NOT_APPLICABLE, report.require("metric.policy-waivers").status());
        assertEquals("WAIVER_EVALUATION_UNSUPPORTED", report.require("metric.policy-waivers").gaps().getFirst().reasonCode());
        assertEquals(AssessmentStatus.WITHHELD, report.require("metric.policy-audit").status());
        assertTrue(report.require("metric.policy-audit").value().isEmpty());
        assertEquals("ADJUDICATED_LABELS_UNAVAILABLE", report.require("metric.policy-audit").gaps().getFirst().reasonCode());
        assertTrue(report.gaps().isEmpty(), "Unavailable auxiliary audit data does not corrupt complete policy counts");
    }
    @Test void selectorBudgetRetainsEveryRuleAndFindingWithATypedGap() {
        var f = PolicyEvaluatorTest.fixture().edge("Controller", "Store"); PolicyEvaluatorTest.prove(f);
        var compiled = PolicyCompilerTest.compile(PolicyCompilerTest.VALID); var queries = new ArchitectureQueryService(f.graph());
        var plan = new PolicyGraphMapper().bind(compiled, queries, f.context, PolicyGraphMapper.Limits.defaults());
        var evaluation = new PolicyEvaluator().evaluate(compiled, plan, queries, PolicyEvaluation.Limits.defaults());
        var report = new PolicyMetricsService().compute(compiled, plan, evaluation, queries, new PolicyMetrics.Limits(1));
        assertEquals(1, report.counts().declaredRules()); assertEquals(1, report.counts().findings().size());
        assertEquals(AssessmentStatus.PARTIAL, report.status());
        assertEquals(AssessmentStatus.WITHHELD, report.counts().coverage().getFirst().status());
        assertTrue(report.gaps().stream().anyMatch(g -> g.reasonCode().equals("POLICY_METRIC_LIMIT")));
        assertEquals(AssessmentStatus.WITHHELD, report.require("metric.policy-density").status());
    }
    @Test void foreignAndIncompleteEvaluationInputsAreRejected() {
        var f = PolicyEvaluatorTest.fixture(); var compiled = PolicyCompilerTest.compile(PolicyCompilerTest.VALID); var queries = new ArchitectureQueryService(f.graph());
        var plan = new PolicyGraphMapper().bind(compiled, queries, f.context, PolicyGraphMapper.Limits.defaults());
        var evaluation = new PolicyEvaluator().evaluate(compiled, plan, queries, PolicyEvaluation.Limits.defaults());
        var missing = new PolicyEvaluation.Result(evaluation.compilation(), evaluation.mapping(), evaluation.graph(), evaluation.context(), evaluation.limits(), evaluation.status(), List.of(), evaluation.gaps(), evaluation.upstreamEvidence());
        var engine = new PolicyMetricsService();
        assertThrows(IllegalArgumentException.class, () -> engine.compute(compiled, plan, missing, queries, PolicyMetrics.Limits.defaults()));
        assertThrows(IllegalArgumentException.class, () -> engine.compute(PolicyCompilerTest.compile("# changed\n" + PolicyCompilerTest.VALID), plan, evaluation, queries, PolicyMetrics.Limits.defaults()));
    }
    @Test void evidenceAndScopeCountsUseDistinctFindingsAndOriginalStatuses() {
        var f = PolicyEvaluatorTest.fixture().edge("Controller", "Store").edge("Controller", "Store"); PolicyEvaluatorTest.prove(f);
        var report = metrics(f, PolicyCompilerTest.VALID);
        assertEquals(1, report.counts().findings().size()); assertEquals(1, report.counts().completeBundles());
        assertEquals(BigDecimal.ONE, report.require("metric.policy-evidence").value().orElseThrow().amount());
        assertEquals(1L, report.counts().bySemanticStatus().get("RESOLVED").get("MUST"));
        assertEquals(1L, report.counts().coverage().getFirst().selectorCounts().get("from").get("YES"));
        assertEquals(1L, report.counts().coverage().getFirst().selectorCounts().get("from").get("NO"));
        assertEquals(Set.of("graph.depends-on"), report.counts().coverage().getFirst().relationships());
        assertThrows(UnsupportedOperationException.class, () -> report.counts().bySeverity().clear());
        assertNotEquals(report.identity(), metrics(f, "# formatting\n" + PolicyCompilerTest.VALID).identity());
        assertEquals(report.counts().ruleQuantifiers(), metrics(f, "# formatting\n" + PolicyCompilerTest.VALID).counts().ruleQuantifiers());
    }
}
