package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.input.ConditionTestInputs.EVIDENCE;
import static com.evolution.analysis.spring.condition.ConfigurationSpaceTest.*;

class ExogenousConditionEvaluatorTest {
    static final ConditionExpression.Semantics SEM = new ConditionExpression.Semantics(ExogenousConditionEvaluator.SEMANTICS, ContentDigest.sha256Utf8("normalized-fragment-fixture"));
    static FiniteDomain.Variable property(String key) { return new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, key); }
    static ConditionExpression atom(ConditionExpression.Operand operand) { return ConditionExpression.atom(SEM, operand); }
    static ConditionExpression prop(String key, String having, boolean missing) { return atom(new ConditionExpression.Property("", List.of(key), having, missing)); }
    static ConfigurationSpace normalized(List<FiniteDomain> domains) {
        var base = space(domains);
        return new ConfigurationSpace(BUILD, base.repositoryEnvelope(), Optional.empty(), domains, List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE, List.of(), EVIDENCE),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES, List.of(), Map.of(), List.of(), EVIDENCE),
                VERSION, base.feasibilityPolicy());
    }
    static ExogenousConditionEvaluator.Result evaluate(ConfigurationSpace space, List<ConditionExpression> expressions, Map<FiniteDomain.Variable, FiniteDomain.Value> assignment) {
        List<ConditionOccurrence> rows = new ArrayList<>();
        expressions.forEach(e -> rows.add(occurrence(e, rows.size())));
        return ExogenousConditionEvaluator.evaluate(ConditionModel.create(space, rows), SEM,
                new ConfigurationAssignment(assignment, EVIDENCE), ExogenousConditionEvaluator.Limits.conservative());
    }
    @Test void missingEmptyFalseAndWhitespaceFollowTheAcceptedPropertyTable() {
        var variable = property("p");
        var values = List.of(FiniteDomain.Value.missing(), FiniteDomain.Value.exact(""), FiniteDomain.Value.exact("FALSE"), FiniteDomain.Value.exact(" false "));
        var space = normalized(List.of(new FiniteDomain(variable, values, EVIDENCE)));
        var expected = List.of(LogicalValue.FALSE, LogicalValue.TRUE, LogicalValue.FALSE, LogicalValue.TRUE);
        for (int i = 0; i < values.size(); i++) {
            var result = evaluate(space, List.of(prop("p", "", false)), Map.of(variable, values.get(i)));
            assertEquals(expected.get(i), result.rows().getFirst().truth());
            assertEquals(LogicalValue.TRUE, result.assignmentFeasibility());
        }
    }
    @Test void absentAssignmentIsUnknownButAnExplicitMissingValueUsesMatchIfMissing() {
        var p = property("p"); var space = normalized(List.of(new FiniteDomain(p, List.of(FiniteDomain.Value.missing()), EVIDENCE)));
        var unknown = evaluate(space, List.of(prop("p", "true", true)), Map.of());
        assertEquals(LogicalValue.UNKNOWN, unknown.rows().getFirst().truth());
        assertTrue(unknown.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("ASSIGNMENT_MISSING")));
        assertEquals(LogicalValue.TRUE, evaluate(space, List.of(prop("p", "true", true)), Map.of(p, FiniteDomain.Value.missing())).rows().getFirst().truth());
    }
    @Test void allNamesAreConjoinedAndOpaqueGapsSurviveAbsorbingBooleanValues() {
        var p = property("p"); var q = property("q");
        var value = FiniteDomain.Value.exact("false");
        var space = normalized(List.of(new FiniteDomain(p, List.of(value), EVIDENCE), new FiniteDomain(q, List.of(value), EVIDENCE)));
        var condition = atom(new ConditionExpression.Property("", List.of("p", "q"), "", false));
        assertEquals(LogicalValue.FALSE, evaluate(space, List.of(condition), Map.of(p, value)).rows().getFirst().truth());
        var opaque = atom(new ConditionExpression.Opaque(ContentDigest.sha256Utf8("opaque"), ConditionExpression.OpaqueReason.CUSTOM_CODE));
        var result = evaluate(normalized(List.of()), List.of(ConditionExpression.all(SEM, List.of(atom(new ConditionExpression.Constant(LogicalValue.FALSE)), opaque))), Map.of());
        assertEquals(LogicalValue.FALSE, result.rows().getFirst().truth());
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("OPAQUE_CONDITION")));
        assertEquals(ExogenousConditionEvaluator.Status.PARTIAL, result.status());
    }
    static ConfigurationEnvelope.DeclaredSource declared(String key, ConfigurationEnvelope.Availability availability,
                                                        Optional<ConditionExpression.Identity> guard, Map<FiniteDomain.Variable, List<FiniteDomain.Value>> values) {
        return new ConfigurationEnvelope.DeclaredSource(key, ConfigurationEnvelope.SourceKind.DOCUMENT, EVIDENCE, guard,
                List.of(), availability, values, ExogenousConditionEvaluator.CONVERSION);
    }
    static ConfigurationSpace sources(ConfigurationSpace base, List<ConfigurationEnvelope.DeclaredSource> sources) {
        var envelope = envelope(sources);
        return new ConfigurationSpace(BUILD, envelope, Optional.empty(), base.domains(), base.constraints(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE, sources.stream().map(s ->
                        new ConfigurationSpace.SourceReference(envelope.layer(), s.identity())).toList(), EVIDENCE),
                base.profilePolicy(), base.abstractionVersion(), base.feasibilityPolicy());
    }
    @Test void lastActiveSourceWinsIncludingEmptyWhileMissingFallsThrough() {
        var p = property("p"); var no = FiniteDomain.Value.exact("false"); var empty = FiniteDomain.Value.exact("");
        var missing = FiniteDomain.Value.missing();
        var base = normalized(List.of(new FiniteDomain(p, List.of(no, empty, missing), EVIDENCE)));
        var first = declared("low", ConfigurationEnvelope.Availability.AVAILABLE, Optional.empty(), Map.of(p, List.of(no)));
        var high = declared("high", ConfigurationEnvelope.Availability.AVAILABLE, Optional.empty(), Map.of(p, List.of(empty)));
        var absent = declared("absent", ConfigurationEnvelope.Availability.AVAILABLE, Optional.empty(), Map.of(p, List.of(missing)));
        var result = evaluate(sources(base, List.of(first, high, absent)), List.of(prop("p", "", false)), Map.of(p, missing));
        assertEquals(LogicalValue.TRUE, result.rows().getFirst().truth());
        assertEquals(Optional.of(empty), result.environment().getFirst().value());
        assertEquals(LogicalValue.FALSE, evaluate(sources(base, List.of(high, first)), List.of(prop("p", "", false)), Map.of(p, missing)).rows().getFirst().truth());
    }
    @Test void unavailableHighSourcesTaintOnlyTheirFootprintAndNeverUseTheirSuppliedValues() {
        var p = property("p"); var q = property("q"); var yes = FiniteDomain.Value.exact("true");
        var base = normalized(List.of(new FiniteDomain(p, List.of(yes), EVIDENCE), new FiniteDomain(q, List.of(yes), EVIDENCE)));
        for (var availability : ConfigurationEnvelope.Availability.values()) {
            var source = declared("high", availability, Optional.empty(), Map.of(p, List.of(yes)));
            var result = evaluate(sources(base, List.of(source)), List.of(prop("p", "", false), prop("q", "", false)), Map.of(p, yes, q, yes));
            var values = result.rows().stream().map(ExogenousConditionEvaluator.Row::truth).toList();
            assertTrue(values.contains(LogicalValue.TRUE));
            assertEquals(availability == ConfigurationEnvelope.Availability.AVAILABLE || availability == ConfigurationEnvelope.Availability.MISSING_OPTIONAL ? 0 : 1,
                    result.coverage().unknownRows(), availability.name());
            if (availability == ConfigurationEnvelope.Availability.MISSING_REQUIRED) assertEquals(LogicalValue.UNKNOWN, result.assignmentFeasibility());
        }
    }
    @Test void sourceAlternativesNeedIndependentChoicesAndDoNotBorrowBaselineValues() {
        var p = property("p"); var yes = FiniteDomain.Value.exact("true"); var no = FiniteDomain.Value.exact("false");
        var source = declared("external", ConfigurationEnvelope.Availability.AVAILABLE, Optional.empty(), Map.of(p, List.of(yes, no)));
        var space = sources(normalized(List.of(new FiniteDomain(p, List.of(yes, no), EVIDENCE))), List.of(source));
        var row = occurrence(prop("p", "", false), 0); var model = ConditionModel.create(space, List.of(row));
        var missingChoice = ExogenousConditionEvaluator.evaluate(model, SEM, new ConfigurationAssignment(Map.of(p, yes), EVIDENCE), ExogenousConditionEvaluator.Limits.conservative());
        assertEquals(LogicalValue.UNKNOWN, missingChoice.rows().getFirst().truth());
        var assignment = new ConfigurationAssignment(Map.of(p, yes), List.of(new ConfigurationAssignment.SourceChoice(
                space.precedencePolicy().lowToHigh().getFirst(), p, no)), EVIDENCE);
        assertEquals(LogicalValue.FALSE, ExogenousConditionEvaluator.evaluate(model, SEM, assignment, ExogenousConditionEvaluator.Limits.conservative()).rows().getFirst().truth());
    }
    @Test void defaultsApplyOnlyWithNoActiveProfilesAndGroupsIncludesCloseWithCycles() {
        var base = normalized(List.of(profileDomain("dev"), profileDomain("default"), profileDomain("group")));
        var policy = new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES, List.of("default"),
                Map.of("group", List.of("dev"), "dev", List.of("group")), List.of(), EVIDENCE);
        var space = new ConfigurationSpace(BUILD, base.repositoryEnvelope(), Optional.empty(), base.domains(), List.of(),
                base.precedencePolicy(), policy, VERSION, base.feasibilityPolicy());
        var assignment = new TreeMap<FiniteDomain.Variable, FiniteDomain.Value>();
        base.domains().forEach(d -> assignment.put(d.variable(), FiniteDomain.Value.bool(false)));
        assertEquals(LogicalValue.TRUE, evaluate(space, List.of(atom(new ConditionExpression.Profile("default"))), assignment).rows().getFirst().truth());
        assignment.put(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "group"), FiniteDomain.Value.bool(true));
        assertEquals(LogicalValue.FALSE, evaluate(space, List.of(atom(new ConditionExpression.Profile("default"))), assignment).rows().getFirst().truth());
        assertEquals(LogicalValue.TRUE, evaluate(space, List.of(atom(new ConditionExpression.Profile("dev"))), assignment).rows().getFirst().truth());
        assignment.remove(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "group"));
        assertEquals(LogicalValue.UNKNOWN, evaluate(space, List.of(atom(new ConditionExpression.Profile("default"))), assignment).rows().getFirst().truth());
    }
    @Test void profileGuardUsesBootstrapProfilesAndPropertyGuardIsNeverAnUnorderedFixpoint() {
        var p = property("p"); var yes = FiniteDomain.Value.exact("true"); var no = FiniteDomain.Value.exact("false");
        var base = normalized(List.of(profileDomain("dev"), new FiniteDomain(p, List.of(yes, no), EVIDENCE)));
        var guard = atom(new ConditionExpression.Profile("dev"));
        var source = declared("profiled", ConfigurationEnvelope.Availability.AVAILABLE, Optional.of(guard.identity()), Map.of(p, List.of(yes)));
        var result = evaluate(sources(base, List.of(source)), List.of(guard, prop("p", "", false)),
                Map.of(p, no, new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"), FiniteDomain.Value.bool(false)));
        assertEquals(2, result.coverage().falseRows());
        var propertyGuard = prop("p", "", false);
        source = declared("cyclic", ConfigurationEnvelope.Availability.AVAILABLE, Optional.of(propertyGuard.identity()), Map.of(p, List.of(yes)));
        result = evaluate(sources(base, List.of(source)), List.of(propertyGuard), Map.of(p, no));
        assertEquals(LogicalValue.UNKNOWN, result.rows().getFirst().truth());
        assertTrue(result.issues().stream().anyMatch(i -> i.reason() == ConditionProcessing.Reason.ACTIVATION_NOT_SUPPORTED));
    }
    @Test void otherUnicodeAndPlaceholdersStayQualifiedAndStatePredicatesRemainUnknown() {
        var p = property("p");
        for (var value : List.of(FiniteDomain.Value.other(Optional.empty()), FiniteDomain.Value.exact("\u0130"), FiniteDomain.Value.exact("${secret}"))) {
            var result = evaluate(normalized(List.of(new FiniteDomain(p, List.of(value), EVIDENCE))), List.of(prop("p", "", false)), Map.of(p, value));
            assertEquals(LogicalValue.UNKNOWN, result.rows().getFirst().truth()); assertFalse(result.capabilityGaps().isEmpty());
        }
        var bean = atom(new ConditionExpression.Bean(ConditionExpression.BeanPredicate.MISSING, "container", List.of("A"), List.of(), List.of(), ConditionExpression.Search.CURRENT));
        assertEquals(LogicalValue.UNKNOWN, evaluate(normalized(List.of()), List.of(bean), Map.of()).rows().getFirst().truth());
    }
    @Test void constraintsArePerAssignmentAndInvalidValuesNeverBecomeWitnesses() {
        var base = normalized(List.of(profileDomain("dev")));
        var profile = atom(new ConditionExpression.Profile("dev"));
        var constraint = occurrence(profile, 1);
        var space = new ConfigurationSpace(BUILD, base.repositoryEnvelope(), Optional.empty(), base.domains(), List.of(constraint),
                base.precedencePolicy(), base.profilePolicy(), VERSION, base.feasibilityPolicy());
        var result = evaluate(space, List.of(profile), Map.of(base.domains().getFirst().variable(), FiniteDomain.Value.bool(false)));
        assertEquals(LogicalValue.FALSE, result.assignmentFeasibility());
        result = evaluate(space, List.of(profile), Map.of(property("foreign"), FiniteDomain.Value.exact("x")));
        assertEquals(ExogenousConditionEvaluator.Status.INVALID_ASSIGNMENT, result.status());
        assertEquals(0, result.coverage().trueRows());
    }
    @Test void exactBuildConstantsNeedTheirOriginalContextEvidenceAndSemantics() {
        var build = atom(new ConditionExpression.Build(ConditionExpression.BuildPredicate.CLASS_PRESENT, "fixture.Type", BUILD.identity(), LogicalValue.TRUE, EVIDENCE));
        assertEquals(LogicalValue.TRUE, evaluate(normalized(List.of()), List.of(build), Map.of()).rows().getFirst().truth());
        var foreign = com.evolution.analysis.input.ConditionTestInputs.context(List.of("b", "a"), "locator", 21);
        build = atom(new ConditionExpression.Build(ConditionExpression.BuildPredicate.CLASS_PRESENT, "fixture.Type", foreign.identity(), LogicalValue.FALSE, EVIDENCE));
        assertEquals(LogicalValue.UNKNOWN, evaluate(normalized(List.of()), List.of(build), Map.of()).rows().getFirst().truth());
        assertEquals(LogicalValue.UNKNOWN, evaluate(normalized(List.of(profileDomain("a"))), List.of(ConditionExpressionTest.profile("a")),
                Map.of(profileDomain("a").variable(), FiniteDomain.Value.bool(true))).rows().getFirst().truth());
    }
    @Test void replayPreservesIdentitiesRowsAndGapsAtEveryDeterministicBudgetBoundary() {
        var expression = ConditionExpression.not(atom(new ConditionExpression.Constant(LogicalValue.TRUE)));
        var model = ConditionModel.create(normalized(List.of()), List.of(occurrence(expression, 0), occurrence(expression, 1)));
        var assignment = new ConfigurationAssignment(Map.of(), EVIDENCE);
        var limits = new ExogenousConditionEvaluator.Limits(1, 1);
        var first = ExogenousConditionEvaluator.evaluate(model, SEM, assignment, limits);
        assertEquals(2, first.coverage().unknownRows()); assertEquals(1, first.steps());
        assertEquals(first.identity(), ExogenousConditionEvaluator.evaluate(model, SEM, assignment, limits).identity());
        assertNotEquals(first.inputIdentity(), ExogenousConditionEvaluator.evaluate(model, SEM, assignment, new ExogenousConditionEvaluator.Limits(2, 1)).inputIdentity());
        var complete = ExogenousConditionEvaluator.evaluate(model, SEM, assignment, new ExogenousConditionEvaluator.Limits(6, 1));
        assertEquals(2, complete.coverage().falseRows()); assertEquals(6, complete.steps());
    }
}
