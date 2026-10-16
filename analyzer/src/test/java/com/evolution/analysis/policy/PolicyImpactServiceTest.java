package com.evolution.analysis.policy;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

class PolicyImpactServiceTest {

    @Test
    void directPolicyImpactIsDetectedWhenChangedEntityViolatesRule() {
        var f = PolicyEvaluatorTest.fixture().edge("Controller", "Store");
        PolicyEvaluatorTest.prove(f);
        var evaluation = PolicyEvaluatorTest.evaluate(f, PolicyCompilerTest.VALID);
        assertEquals(1, evaluation.findings().size());

        var queries = new ArchitectureQueryService(f.graph());
        var incoming = new Request(
            f.graph().identity(),
            f.context,
            Scope.TYPE,
            Projection.BOUNDED_UNION,
            Direction.INCOMING,
            java.util.Set.of("graph.depends-on"),
            Population.all(),
            false,
            Optional.empty(),
            Limits.defaults()
        );

        String controllerId = f.types.get("Controller");
        var impactService = new PolicyImpactService();
        var impact = impactService.evaluate(queries, incoming, evaluation, controllerId);

        assertEquals(controllerId, impact.changedEntity());
        assertEquals(AssessmentStatus.COMPLETE, impact.status());
        assertEquals(1, impact.directFindings().size());
        assertEquals(0, impact.transitiveFindings().size());
        assertEquals(1, impact.affectedRules().size());

        var ruleSummary = impact.affectedRules().getFirst();
        assertEquals("web.persistence", ruleSummary.ruleId());
        assertEquals(ArchitecturePolicy.Severity.ERROR, ruleSummary.severity());
        assertEquals(1, ruleSummary.directFindingsCount());
        assertEquals(0, ruleSummary.transitiveFindingsCount());

        assertEquals(Optional.of(ArchitecturePolicy.Severity.ERROR), impact.worstSeverity());
        assertEquals(Optional.of(Classification.MUST), impact.highestQuantifier());

        // Deterministic identity
        assertEquals(impact.identity(), impactService.evaluate(queries, incoming, evaluation, controllerId).identity());
    }

    @Test
    void transitivePolicyImpactIsDetectedWhenDependentViolatesRule() {
        var f = PolicyEvaluatorTest.fixture()
            .type("com.example.common", "Helper")
            .edge("Controller", "Store")
            .edge("Controller", "Helper");
        PolicyEvaluatorTest.prove(f);

        var evaluation = PolicyEvaluatorTest.evaluate(f, PolicyCompilerTest.VALID);
        assertEquals(1, evaluation.findings().size());

        var queries = new ArchitectureQueryService(f.graph());
        var incoming = new Request(
            f.graph().identity(),
            f.context,
            Scope.TYPE,
            Projection.BOUNDED_UNION,
            Direction.INCOMING,
            java.util.Set.of("graph.depends-on"),
            Population.all(),
            false,
            Optional.empty(),
            Limits.defaults()
        );

        String helperId = f.types.get("Helper");
        var impactService = new PolicyImpactService();
        var impact = impactService.evaluate(queries, incoming, evaluation, helperId);

        assertEquals(helperId, impact.changedEntity());
        assertEquals(AssessmentStatus.COMPLETE, impact.status());
        // Helper is not in the violation path (Controller -> Store), but Controller depends on Helper!
        assertEquals(0, impact.directFindings().size());
        assertEquals(1, impact.transitiveFindings().size());
        assertEquals(1, impact.affectedRules().size());

        var ruleSummary = impact.affectedRules().getFirst();
        assertEquals("web.persistence", ruleSummary.ruleId());
        assertEquals(0, ruleSummary.directFindingsCount());
        assertEquals(1, ruleSummary.transitiveFindingsCount());

        assertEquals(Optional.of(ArchitecturePolicy.Severity.ERROR), impact.worstSeverity());
        assertEquals(Optional.of(Classification.MUST), impact.highestQuantifier());
    }

    @Test
    void missingEntityProducesExplicitGapsAndPartialStatus() {
        var f = PolicyEvaluatorTest.fixture().edge("Controller", "Store");
        PolicyEvaluatorTest.prove(f);
        var evaluation = PolicyEvaluatorTest.evaluate(f, PolicyCompilerTest.VALID);

        var queries = new ArchitectureQueryService(f.graph());
        var incoming = new Request(
            f.graph().identity(),
            f.context,
            Scope.TYPE,
            Projection.BOUNDED_UNION,
            Direction.INCOMING,
            java.util.Set.of("graph.depends-on"),
            Population.all(),
            false,
            Optional.empty(),
            Limits.defaults()
        );

        var impactService = new PolicyImpactService();
        var impact = impactService.evaluate(queries, incoming, evaluation, "absent:sha256:0000000000000000000000000000000000000000000000000000000000000000");

        assertEquals(AssessmentStatus.PARTIAL, impact.status());
        assertFalse(impact.gaps().isEmpty());
        assertTrue(impact.directFindings().isEmpty());
        assertTrue(impact.transitiveFindings().isEmpty());
    }

    @Test
    void incomingDirectionIsEnforced() {
        var f = PolicyEvaluatorTest.fixture();
        var queries = new ArchitectureQueryService(f.graph());
        var outgoing = new Request(
            f.graph().identity(),
            f.context,
            Scope.TYPE,
            Projection.BOUNDED_UNION,
            Direction.OUTGOING,
            java.util.Set.of("graph.depends-on"),
            Population.all(),
            false,
            Optional.empty(),
            Limits.defaults()
        );

        var impactService = new PolicyImpactService();
        var evaluation = PolicyEvaluatorTest.evaluate(f, PolicyCompilerTest.VALID);
        assertThrows(IllegalArgumentException.class, () ->
            impactService.evaluate(queries, outgoing, evaluation, "dummy")
        );
    }
}
