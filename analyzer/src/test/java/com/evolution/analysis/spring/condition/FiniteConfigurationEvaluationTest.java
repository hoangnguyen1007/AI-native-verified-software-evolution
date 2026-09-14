package com.evolution.analysis.spring.condition;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.condition.ExogenousConditionEvaluatorTest.*;
import static com.evolution.analysis.spring.condition.ConfigurationSpaceTest.*;
import static com.evolution.analysis.input.ConditionTestInputs.EVIDENCE;

class FiniteConfigurationEvaluationTest {
    @Test void exhaustiveIndependentProfilesDoNotAcquireInventedMutualExclusion() {
        var a = atom(new ConditionExpression.Profile("a")); var b = atom(new ConditionExpression.Profile("b"));
        var both = ConditionExpression.all(SEM, List.of(a, b));
        var model = ConditionModel.create(normalized(List.of(profileDomain("a"), profileDomain("b"))), List.of(occurrence(both, 0)));
        var result = FiniteConfigurationEvaluation.evaluate(model, SEM, FiniteConfigurationEvaluation.Limits.conservative());
        assertEquals(4, result.coverage().evaluatedAssignments()); assertEquals(4, result.coverage().feasibleAssignments());
        assertEquals(1, result.evaluations().stream().filter(e -> e.rows().getFirst().truth() == LogicalValue.TRUE).count());
        assertEquals(3, result.evaluations().stream().filter(e -> e.rows().getFirst().truth() == LogicalValue.FALSE).count());
        assertEquals(FiniteConfigurationEvaluation.Feasibility.FEASIBLE, result.feasibility());
        assertEquals(result.identity(), FiniteConfigurationEvaluation.evaluate(model, SEM, FiniteConfigurationEvaluation.Limits.conservative()).identity());
    }
    @Test void noFeasibleAssignmentIsInvalidModelAndUnknownConstraintsCannotProveUnsatisfiability() {
        var a = atom(new ConditionExpression.Profile("a"));
        var falseCondition = ConditionExpression.all(SEM, List.of(a, ConditionExpression.not(a)));
        var base = normalized(List.of(profileDomain("a")));
        var constraint = occurrence(falseCondition, 0);
        var space = new ConfigurationSpace(BUILD, base.repositoryEnvelope(), Optional.empty(), base.domains(), List.of(constraint), base.precedencePolicy(), base.profilePolicy(), VERSION, base.feasibilityPolicy());
        var result = FiniteConfigurationEvaluation.evaluate(ConditionModel.create(space, List.of()), SEM, FiniteConfigurationEvaluation.Limits.conservative());
        assertEquals(FiniteConfigurationEvaluation.Feasibility.INVALID_MODEL, result.feasibility());
        assertEquals(2, result.coverage().infeasibleAssignments());
        var opaque = atom(new ConditionExpression.Opaque(com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("same-opaque"), ConditionExpression.OpaqueReason.CUSTOM_CODE));
        var unknownConstraint = occurrence(ConditionExpression.any(SEM, List.of(opaque, ConditionExpression.not(opaque))), 1);
        space = new ConfigurationSpace(BUILD, base.repositoryEnvelope(), Optional.empty(), base.domains(), List.of(unknownConstraint), base.precedencePolicy(), base.profilePolicy(), VERSION, base.feasibilityPolicy());
        result = FiniteConfigurationEvaluation.evaluate(ConditionModel.create(space, List.of()), SEM, FiniteConfigurationEvaluation.Limits.conservative());
        assertEquals(FiniteConfigurationEvaluation.Feasibility.UNKNOWN, result.feasibility());
        assertEquals(2, result.coverage().unknownAssignments());
    }
    @Test void emptyProductIsOneAndEmptyDomainIsInvalidWithoutVacuousCertainty() {
        var result = FiniteConfigurationEvaluation.evaluate(ConditionModel.create(normalized(List.of()), List.of()), SEM, FiniteConfigurationEvaluation.Limits.conservative());
        assertEquals(1, result.coverage().evaluatedAssignments()); assertEquals(FiniteConfigurationEvaluation.Feasibility.FEASIBLE, result.feasibility());
        var domain = new FiniteDomain(property("empty"), List.of(), EVIDENCE);
        result = FiniteConfigurationEvaluation.evaluate(ConditionModel.create(normalized(List.of(domain)), List.of()), SEM, FiniteConfigurationEvaluation.Limits.conservative());
        assertEquals(0, result.coverage().evaluatedAssignments()); assertEquals(FiniteConfigurationEvaluation.Feasibility.INVALID_MODEL, result.feasibility());
    }
    @Test void sourceAlternativesAreSeparateAxesAndEveryResourceCapPreservesTheDenominator() {
        var p = property("p"); var yes = FiniteDomain.Value.exact("true"); var no = FiniteDomain.Value.exact("false");
        var base = normalized(List.of(new FiniteDomain(p, List.of(yes, no), EVIDENCE)));
        var low = declared("low", ConfigurationEnvelope.Availability.AVAILABLE, Optional.empty(), Map.of(p, List.of(yes, no)));
        var high = declared("high", ConfigurationEnvelope.Availability.AVAILABLE, Optional.empty(), Map.of(p, List.of(yes, no)));
        var model = ConditionModel.create(sources(base, List.of(low, high)), List.of(occurrence(prop("p", "", false), 0)));
        var all = FiniteConfigurationEvaluation.evaluate(model, SEM, FiniteConfigurationEvaluation.Limits.conservative());
        assertEquals(8, all.coverage().evaluatedAssignments()); assertEquals(4, all.evaluations().stream().filter(e -> e.rows().getFirst().truth() == LogicalValue.TRUE).count());
        var cap = new FiniteConfigurationEvaluation.Limits(4, 100, 1000, ExogenousConditionEvaluator.Limits.conservative());
        var limited = FiniteConfigurationEvaluation.evaluate(model, SEM, cap);
        assertFalse(limited.coverage().complete()); assertFalse(limited.coverage().assignmentCount().exact());
        assertEquals(FiniteConfigurationEvaluation.Feasibility.UNKNOWN, limited.feasibility());
        cap = new FiniteConfigurationEvaluation.Limits(10, 1, 1000, ExogenousConditionEvaluator.Limits.conservative());
        limited = FiniteConfigurationEvaluation.evaluate(model, SEM, cap); assertEquals(0, limited.coverage().evaluatedAssignments());
        cap = new FiniteConfigurationEvaluation.Limits(10, 100, 1, ExogenousConditionEvaluator.Limits.conservative());
        limited = FiniteConfigurationEvaluation.evaluate(model, SEM, cap); assertTrue(limited.coverage().evaluatedAssignments() < 8);
        assertFalse(limited.capabilityGaps().isEmpty());
    }
}
