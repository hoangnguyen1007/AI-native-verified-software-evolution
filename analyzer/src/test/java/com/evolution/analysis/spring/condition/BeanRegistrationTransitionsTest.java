package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.condition.RegistrationDiscoveryTest.*;
import static com.evolution.analysis.spring.registration.RegistrationEvent.*;
import static com.evolution.analysis.spring.condition.LogicalValue.*;
import static com.evolution.analysis.spring.registration.BeanRegistrationPlan.Operation.*;
import static com.evolution.analysis.spring.registration.BeanRegistrationTransitions.Outcome.*;

/**
 * M4C.2 independent behavioral verification. All fixtures are compact authored plans.
 * No real repository execution, class loading, or network access.
 * Covers the deferred-verification checklist from the M4C.2 architecture contract.
 */
class BeanRegistrationTransitionsTest {

    // ---- Fixture helpers -------------------------------------------------

    static RegistrationDiscoveryTest.Fixture pf() throws Exception { return fixture("@Profile(\"dev\")"); }

    /** Build BeanRegistrationPlan with explicit parameters. */
    static BeanRegistrationPlan rp(
            RegistrationDiscoveryTest.Fixture f, RegistrationPlan dp,
            List<BeanRegistrationPlan.Step> steps, List<String> prefix,
            RegistrationEvent.Completeness order, RegistrationEvent.Completeness registry,
            RegistrationEvent.Completeness noParent,
            List<BeanRegistrationEvidence.Definition> defs,
            List<BeanRegistrationEvidence.Query> queries,
            List<BeanRegistrationEvidence.QueryType> qtypes) {
        return new BeanRegistrationPlan(dp, steps, prefix, order, f.source().evidence(),
                registry, noParent, f.source().evidence(), defs, queries, qtypes,
                BeanRegistrationPlan.Limits.conservative());
    }

    /** Simplified: complete order, closed registry, no parent, no endogenous evidence. */
    static BeanRegistrationPlan rp(
            RegistrationDiscoveryTest.Fixture f, RegistrationPlan dp,
            List<BeanRegistrationPlan.Step> steps, List<String> prefix) {
        return rp(f, dp, steps, prefix,
                RegistrationEvent.Completeness.COMPLETE, RegistrationEvent.Completeness.COMPLETE,
                RegistrationEvent.Completeness.COMPLETE, List.of(), List.of(), List.of());
    }

    /** Evaluate BeanRegistrationTransitions with a dev-profile assignment. */
    static BeanRegistrationTransitions.Result eval(
            RegistrationDiscoveryTest.Fixture f, BeanRegistrationPlan plan, boolean dev) {
        return BeanRegistrationTransitions.evaluate(plan,
                ConditionModel.create(f.space(), f.lowering().occurrences()),
                new ConfigurationAssignment(
                        Map.of(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"),
                                FiniteDomain.Value.bool(dev)),
                        f.source().evidence()),
                ExogenousConditionEvaluator.Limits.conservative());
    }

    static BeanRegistrationTransitions.Outcome out(BeanRegistrationTransitions.Result r, String key) {
        return r.rows().stream().filter(row -> row.step().equals(key)).findFirst().orElseThrow().outcome();
    }

    static BeanRegistrationPlan.Step gateStep(String key, RegistrationEvent e, ConditionEvidence ev) {
        return new BeanRegistrationPlan.Step(key, e.identity(), CONFIGURATION_GATE,
                Optional.empty(), TRUE, Optional.empty(), ev);
    }

    static BeanRegistrationPlan.Step regStep(String key, RegistrationEvent e,
                                              Optional<String> gate, ConditionEvidence ev) {
        return new BeanRegistrationPlan.Step(key, e.identity(), REGISTER_DEFINITION,
                gate, TRUE, Optional.empty(), ev);
    }

    static BeanRegistrationPlan.Step aliasStep(String key, RegistrationEvent e,
                                                String gate, String name, String target, ConditionEvidence ev) {
        return new BeanRegistrationPlan.Step(key, e.identity(), REGISTER_ALIAS,
                Optional.of(gate), TRUE, Optional.of(new BeanRegistrationPlan.Alias(name, target)), ev);
    }

    static BeanRegistrationPlan.Step removeStep(String key, RegistrationEvent e,
                                                 String gate, ConditionEvidence ev) {
        return new BeanRegistrationPlan.Step(key, e.identity(), REMOVE_DEFINITION,
                Optional.of(gate), TRUE, Optional.empty(), ev,
                BeanRegistrationPlan.GateExpectation.NO_MATCH);
    }

    // ---- 1. Ordered fallbacks: prefix order drives execution ---------------

    @Test void orderedFallbackStepsExecuteInPrefixOrderNotLexical() throws Exception {
        var f = pf();
        var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var b = event(f, "b", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a, b), List.of(before(f, a, b)), RegistrationPlan.Limits.conservative());
        var gA = gateStep("gate-a", a, f.source().evidence());
        var rA = regStep("reg-a", a, Optional.of("gate-a"), f.source().evidence());
        var gB = gateStep("gate-b", b, f.source().evidence());
        var rB = regStep("reg-b", b, Optional.of("gate-b"), f.source().evidence());
        var plan = rp(f, dp, List.of(gA, rA, gB, rB), List.of("gate-a", "reg-a", "gate-b", "reg-b"));
        var rf = eval(f, plan, false);
        assertEquals(SKIPPED, out(rf, "gate-a")); assertEquals(SKIPPED, out(rf, "reg-a"));
        assertEquals(SKIPPED, out(rf, "gate-b")); assertEquals(SKIPPED, out(rf, "reg-b"));
        var rt = eval(f, plan, true);
        assertEquals(ACCEPTED, out(rt, "gate-a")); assertEquals(REGISTERED, out(rt, "reg-a"));
        assertEquals(ACCEPTED, out(rt, "gate-b")); assertEquals(REGISTERED, out(rt, "reg-b"));
        assertEquals("gate-a", rt.transitions().get(0).step());
        assertEquals("reg-a",  rt.transitions().get(1).step());
        assertEquals("gate-b", rt.transitions().get(2).step());
        assertEquals("reg-b",  rt.transitions().get(3).step());
    }

    // ---- 2. Current state: input snapshot used, not later registry ----------

    @Test void inputSnapshotIsUsedForConditionEvaluationNotFutureRegistry() throws Exception {
        var f = pf();
        var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var b = event(f, "b", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a, b), List.of(before(f, a, b)), RegistrationPlan.Limits.conservative());
        var gA = gateStep("gate-a", a, f.source().evidence());
        var rA = regStep("reg-a", a, Optional.of("gate-a"), f.source().evidence());
        var rB = regStep("reg-b", b, Optional.empty(), f.source().evidence());
        var plan = rp(f, dp, List.of(gA, rA, rB), List.of("gate-a", "reg-a", "reg-b"));
        var result = eval(f, plan, true);
        // 4 states: initial + 3 steps
        assertEquals(4, result.states().size());
        // Input state of reg-b transition = state after reg-a = definitions contains "a"
        assertEquals(result.transitions().get(2).inputState(), result.states().get(2).identity());
        assertTrue(result.states().get(2).definitions().containsKey("a"));
    }

    // ---- 3. Coverage denominator closes ------------------------------------

    @Test void coverageDenominatorIsClosedAndEveryStepHasExactlyOneOutcome() throws Exception {
        var f = pf();
        var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var b = event(f, "b", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a, b), List.of(before(f, a, b)), RegistrationPlan.Limits.conservative());
        var gA = gateStep("gate-a", a, f.source().evidence()); var rA = regStep("reg-a", a, Optional.of("gate-a"), f.source().evidence());
        var gB = gateStep("gate-b", b, f.source().evidence()); var rB = regStep("reg-b", b, Optional.of("gate-b"), f.source().evidence());
        var plan = rp(f, dp, List.of(gA, rA, gB, rB), List.of("gate-a", "reg-a", "gate-b", "reg-b"));
        var result = eval(f, plan, true);
        var cov = result.coverage();
        assertEquals(cov.inputSteps(), cov.accepted() + cov.registered() + cov.overridden()
                + cov.aliased() + cov.removed() + cov.skipped() + cov.unknown() + cov.errors() + cov.notReached());
        assertEquals(4, cov.inputSteps()); assertEquals(2, cov.accepted()); assertEquals(2, cov.registered());
    }

    // ---- 4. Flags/names: registry name and alias semantics -----------------

    @Test void registeredDefinitionIsNamedCorrectlyAndAliasTargetResolvesUnderNameSemantics() throws Exception {
        var f = pf();
        var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a));
        var reg = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var alias = aliasStep("alias-a", a, "reg-a", "a-alias", "a", f.source().evidence());
        var plan = rp(f, dp, List.of(reg, alias), List.of("reg-a", "alias-a"));
        var result = eval(f, plan, true);
        var fs = result.states().getLast();
        assertTrue(fs.definitions().containsKey("a"));
        assertEquals("a", fs.aliases().get("a-alias"));
    }

    // ---- 5. Cardinality: registrationClosed --------------------------------

    @Test void registrationClosedRequiresCompleteScheduleAndNoUnknownOutcomes() throws Exception {
        var f = pf();
        var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a));
        var reg = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var plan = rp(f, dp, List.of(reg), List.of("reg-a"));
        var result = eval(f, plan, true);
        assertTrue(result.registrationClosed());
        assertFalse(result.containerError());
        assertEquals(TRUE, result.candidates().getFirst().finalPresence());
    }

    @Test void incompleteOrderPreventsRegistrationClosed() throws Exception {
        var f = pf();
        var a = event(f, "a", Kind.CONFIGURATION, null, null);
        var b = event(f, "b", Kind.CONFIGURATION, null, null);
        var dp = plan(f, List.of(a, b));
        var rA = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var rB = regStep("reg-b", b, Optional.empty(), f.source().evidence());
        var plan = new BeanRegistrationPlan(dp, List.of(rA, rB), List.of(),
                RegistrationEvent.Completeness.UNKNOWN, f.source().evidence(),
                RegistrationEvent.Completeness.COMPLETE, RegistrationEvent.Completeness.COMPLETE,
                f.source().evidence(), List.of(), List.of(), BeanRegistrationPlan.Limits.conservative());
        var result = eval(f, plan, true);
        assertFalse(result.registrationClosed());
        result.candidates().forEach(c -> assertEquals(LogicalValue.UNKNOWN, c.finalPresence()));
    }

    // ---- 6. Type resolution: ERROR query type stops execution ---------------

    @Test void queryTypeErrorTerminatesExecutionAndMakesSubsequentStepsNotReached() throws Exception {
        var f = fixture("@ConditionalOnMissingBean(Token.class)");
        var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.REGISTER_BEAN);
        var b = event(f, "b", Kind.CONFIGURATION, null, null);
        var dp = plan(f, List.of(a, b), List.of(before(f, a, b)), RegistrationPlan.Limits.conservative());
        var occ = f.lowering().occurrences().getFirst().identity();
        var selector = new ConditionExpression.Bean(ConditionExpression.BeanPredicate.MISSING,
                "authored-root", List.of("com.example.Token"), List.of(), List.of(), ConditionExpression.Search.CURRENT);
        var query = new BeanRegistrationEvidence.Query(occ, selector, List.of(), List.of(),
                RegistrationEvent.Completeness.COMPLETE, f.source().evidence());
        var qt = new BeanRegistrationEvidence.QueryType(BeanRegistrationEvidence.SelectorKind.TYPE,
                "com.example.Token", BeanRegistrationEvidence.TypeStatus.ERROR, f.source().evidence());
        var gA = new BeanRegistrationPlan.Step("gate-a", a.identity(), CONFIGURATION_GATE,
                Optional.empty(), TRUE, Optional.empty(), f.source().evidence());
        var rA = regStep("reg-a", a, Optional.of("gate-a"), f.source().evidence());
        var rB = regStep("reg-b", b, Optional.empty(), f.source().evidence());
        var plan = rp(f, dp, List.of(gA, rA, rB), List.of("gate-a", "reg-a", "reg-b"),
                RegistrationEvent.Completeness.COMPLETE, RegistrationEvent.Completeness.COMPLETE,
                RegistrationEvent.Completeness.COMPLETE, List.of(), List.of(query), List.of(qt));
        var result = BeanRegistrationTransitions.evaluate(plan,
                ConditionModel.create(f.space(), f.lowering().occurrences()),
                new ConfigurationAssignment(Map.of(
                        new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"), FiniteDomain.Value.bool(true)),
                        f.source().evidence()),
                ExogenousConditionEvaluator.Limits.conservative());
        var gateOut = out(result, "gate-a");
        assertTrue(gateOut == ERROR || gateOut == BeanRegistrationTransitions.Outcome.UNKNOWN, "Got " + gateOut);
        var bOut = out(result, "reg-b");
        assertTrue(bOut == NOT_REACHED || bOut == BeanRegistrationTransitions.Outcome.UNKNOWN, "Got " + bOut);
        assertFalse(result.capabilityGaps().isEmpty());
    }

    // ---- 7. Phase/gates ----------------------------------------------------

    @Test void falseParseGateSuppressesRegistrationStep() throws Exception {
        var f = pf();
        var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(root));
        var gate = gateStep("gate", root, f.source().evidence());
        var reg = regStep("reg", root, Optional.of("gate"), f.source().evidence());
        var plan = rp(f, dp, List.of(gate, reg), List.of("gate", "reg"));
        var result = eval(f, plan, false);
        assertEquals(SKIPPED, out(result, "gate")); assertEquals(SKIPPED, out(result, "reg"));
        assertTrue(result.registrationClosed());
    }

    @Test void beanMethodRegistrationRequiresOwnerGateInChain() throws Exception {
        var f = pf();
        var root = event(f, "root", Kind.CONFIGURATION, null, null);
        var bean = event(f, "bean", Kind.BEAN_METHOD, root, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(root, bean));
        var gRoot = gateStep("gate-root", root, f.source().evidence());
        var rRoot = regStep("reg-root", root, Optional.of("gate-root"), f.source().evidence());
        var rBean = new BeanRegistrationPlan.Step("reg-bean", bean.identity(), REGISTER_DEFINITION,
                Optional.of("gate-root"), TRUE, Optional.empty(), f.source().evidence());
        var plan = rp(f, dp, List.of(gRoot, rRoot, rBean), List.of("gate-root", "reg-root", "reg-bean"));
        var result = eval(f, plan, true);
        assertEquals(ACCEPTED, out(result, "gate-root"));
        assertEquals(REGISTERED, out(result, "reg-root"));
        assertEquals(REGISTERED, out(result, "reg-bean"));
    }

    static RegistrationDiscoveryTest.Fixture buildFixture() throws Exception {
        var src = ConditionLoweringTest.fixture(List.of(new ConditionLoweringTest.Spec(
                "org.springframework.boot.autoconfigure.condition.ConditionalOnClass",
                "@ConditionalOnClass(name=\"com.example.Type\")")));
        var raw = src.inventory().rawObservations().getFirst().identity();
        var trueProof = new ConditionEvidenceLowering.BuildObservation(
                raw, ConditionExpression.BuildPredicate.CLASS_PRESENT, src.build().identity(),
                LogicalValue.TRUE, src.evidence());
        var lowering = ConditionEvidenceLowering.lower(src.build(), src.inventory(),
                List.of(trueProof), ConditionEvidenceLowering.Limits.conservative());
        var profile = new FiniteDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"),
                List.of(FiniteDomain.Value.bool(false), FiniteDomain.Value.bool(true)), src.evidence());
        var space = new ConfigurationSpace(src.build(),
                new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,
                        List.of(), List.of(), ContentDigest.sha256Utf8("authored-environment")),
                Optional.empty(), List.of(profile), List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE, List.of(), src.evidence()),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES, List.of(), Map.of(), List.of(), src.evidence()),
                ConfigurationSpaceTest.VERSION,
                new ConfigurationSpace.FeasibilityPolicy(ConfigurationSpaceTest.VERSION,
                        ConfigurationSpace.Limits.conservative(), src.evidence()));
        return new RegistrationDiscoveryTest.Fixture(src, lowering, space);
    }

    @Test void parsePhaseConditionIsNotApplicableAtRegistrationStep() throws Exception {
        var f = buildFixture();
        var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.PARSE_CONFIGURATION);
        var dp = plan(f, List.of(root));
        var gate = gateStep("gate", root, f.source().evidence());
        var reg = regStep("reg", root, Optional.of("gate"), f.source().evidence());
        var plan = rp(f, dp, List.of(gate, reg), List.of("gate", "reg"));
        var result = eval(f, plan, true);
        var gRow = result.rows().stream().filter(r -> r.step().equals("gate")).findFirst().orElseThrow();
        assertTrue(gRow.conditions().stream()
                .anyMatch(c -> c.status() == BeanRegistrationTransitions.InvocationStatus.PHASE_NOT_APPLICABLE));
    }

    @Test void readerSkipCannotSubstituteForNegativeConditionInRemovalGate() throws Exception {
        var f = pf();
        var config = event(f, "config", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(config));
        var gate = gateStep("gate", config, f.source().evidence());
        var reg = regStep("reg", config, Optional.of("gate"), f.source().evidence());
        var remove = removeStep("remove", config, "gate", f.source().evidence());
        var plan = rp(f, dp, List.of(gate, reg, remove), List.of("gate", "reg", "remove"));
        // dev=true: gate ACCEPTED (condition TRUE), reg REGISTERED, remove wants NO_MATCH but gate was TRUE → SKIPPED
        var rt = eval(f, plan, true);
        assertEquals(ACCEPTED, out(rt, "gate")); assertEquals(REGISTERED, out(rt, "reg")); assertEquals(SKIPPED, out(rt, "remove"));
        // dev=false: discovery outcome is SKIPPED for all steps (profile guard false → parse skip)
        var rf = eval(f, plan, false);
        assertEquals(SKIPPED, out(rf, "gate")); assertEquals(SKIPPED, out(rf, "reg")); assertEquals(SKIPPED, out(rf, "remove"));
    }

    // ---- 8. Definition history: replacement and forbidden ------------------

    @Test void allowedDefinitionReplacementRegistersNewCandidateAtSameName() throws Exception {
        var f = pf();
        var a = event(f, "config", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var b = event(f, "config2", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var bc = b.candidate().orElseThrow();
        var bSame = new BeanDefinitionCandidate(bc.producer(), bc.definitionSlot(),
                BeanDefinitionCandidate.Names.exact("config"), bc.exposedTypes());
        var bEv = new RegistrationEvent(b.buildContextIdentity(), b.frameworkSemantics(),
                b.triggerOccurrenceIdentity(), b.parentInvocationPath(), b.phase(), b.eventSlot(),
                b.containerKey(), b.kind(), b.conditionSite(), b.parent(), Optional.of(bSame),
                b.conditions(), b.membership(), b.conditionMetadata(), b.obligations(), b.evidence());
        var dp = RegistrationPlan.create(f.source().build(), f.source().inventory(), f.lowering(),
                List.of(a, bEv), List.of(before(f, a, bEv)),
                new RegistrationPlan.Container("authored-root", f.source().evidence()),
                List.of(), RegistrationPlan.OverridePolicy.ALLOW, RegistrationPlan.Limits.conservative());
        var rA = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var rB = regStep("reg-b", bEv, Optional.empty(), f.source().evidence());
        var result = eval(f, rp(f, dp, List.of(rA, rB), List.of("reg-a", "reg-b")), true);
        assertEquals(REGISTERED, out(result, "reg-a")); assertEquals(OVERRIDDEN, out(result, "reg-b"));
        assertTrue(result.states().getLast().definitions().containsKey("config"));
    }

    @Test void forbiddenDefinitionOverrideProducesErrorAndContainerErrorIsSet() throws Exception {
        var f = pf();
        var a = event(f, "config", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var b = event(f, "config2", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var bc = b.candidate().orElseThrow();
        var bSame = new BeanDefinitionCandidate(bc.producer(), bc.definitionSlot(),
                BeanDefinitionCandidate.Names.exact("config"), bc.exposedTypes());
        var bEv = new RegistrationEvent(b.buildContextIdentity(), b.frameworkSemantics(),
                b.triggerOccurrenceIdentity(), b.parentInvocationPath(), b.phase(), b.eventSlot(),
                b.containerKey(), b.kind(), b.conditionSite(), b.parent(), Optional.of(bSame),
                b.conditions(), b.membership(), b.conditionMetadata(), b.obligations(), b.evidence());
        var dp = RegistrationPlan.create(f.source().build(), f.source().inventory(), f.lowering(),
                List.of(a, bEv), List.of(before(f, a, bEv)),
                new RegistrationPlan.Container("authored-root", f.source().evidence()),
                List.of(), RegistrationPlan.OverridePolicy.FORBID, RegistrationPlan.Limits.conservative());
        var rA = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var rB = regStep("reg-b", bEv, Optional.empty(), f.source().evidence());
        var result = eval(f, rp(f, dp, List.of(rA, rB), List.of("reg-a", "reg-b")), true);
        assertEquals(REGISTERED, out(result, "reg-a")); assertEquals(ERROR, out(result, "reg-b"));
        assertTrue(result.containerError());
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("DEFINITION_OVERRIDE_FORBIDDEN")));
    }

    // ---- 9. Aliases --------------------------------------------------------

    @Test void aliasSelfRegistrationRemovesTheAlias() throws Exception {
        var f = pf(); var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a));
        var reg = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var add = aliasStep("add", a, "reg-a", "a-alias", "a", f.source().evidence());
        var self = aliasStep("self", a, "reg-a", "a-alias", "a-alias", f.source().evidence());
        var result = eval(f, rp(f, dp, List.of(reg, add, self), List.of("reg-a", "add", "self")), true);
        assertEquals(ALIASED, out(result, "add")); assertEquals(ALIASED, out(result, "self"));
        assertFalse(result.states().getLast().aliases().containsKey("a-alias"));
    }

    @Test void aliasCycleProducesError() throws Exception {
        var f = pf(); var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a));
        var reg = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var ab = aliasStep("ab", a, "reg-a", "alias-b", "alias-a", f.source().evidence());
        var ba = aliasStep("ba", a, "reg-a", "alias-a", "alias-b", f.source().evidence());
        var result = eval(f, rp(f, dp, List.of(reg, ab, ba), List.of("reg-a", "ab", "ba")), true);
        assertEquals(ALIASED, out(result, "ab")); assertEquals(ERROR, out(result, "ba"));
        assertTrue(result.containerError());
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("ALIAS_CYCLE")));
    }

    @Test void duplicateAliasRegistrationIsIdempotent() throws Exception {
        var f = pf(); var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a));
        var reg = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var a1 = aliasStep("a1", a, "reg-a", "a-alias", "a", f.source().evidence());
        var a2 = aliasStep("a2", a, "reg-a", "a-alias", "a", f.source().evidence());
        var result = eval(f, rp(f, dp, List.of(reg, a1, a2), List.of("reg-a", "a1", "a2")), true);
        assertEquals(ALIASED, out(result, "a1")); assertEquals(ALIASED, out(result, "a2")); assertFalse(result.containerError());
    }

    @Test void aliasChainTargetingAnIntermediateAliasIsSupported() throws Exception {
        var f = pf(); var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a));
        var reg = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var inner = aliasStep("inner", a, "reg-a", "alias-a", "a", f.source().evidence());
        var outer = aliasStep("outer", a, "reg-a", "outer-alias", "alias-a", f.source().evidence());
        var result = eval(f, rp(f, dp, List.of(reg, inner, outer), List.of("reg-a", "inner", "outer")), true);
        assertEquals(ALIASED, out(result, "inner")); assertEquals(ALIASED, out(result, "outer"));
        assertFalse(result.containerError());
        assertEquals("a", result.states().getLast().aliases().get("alias-a"));
        assertEquals("alias-a", result.states().getLast().aliases().get("outer-alias"));
    }

    // ---- 10. Failure propagation: denominator always closed ----------------

    @Test void everyStepReceivesAnOutcomeEvenAfterContainerError() throws Exception {
        var f = pf();
        var a = event(f, "config", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var b = event(f, "config2", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var bc = b.candidate().orElseThrow();
        var bSame = new BeanDefinitionCandidate(bc.producer(), bc.definitionSlot(),
                BeanDefinitionCandidate.Names.exact("config"), bc.exposedTypes());
        var bEv = new RegistrationEvent(b.buildContextIdentity(), b.frameworkSemantics(),
                b.triggerOccurrenceIdentity(), b.parentInvocationPath(), b.phase(), b.eventSlot(),
                b.containerKey(), b.kind(), b.conditionSite(), b.parent(), Optional.of(bSame),
                b.conditions(), b.membership(), b.conditionMetadata(), b.obligations(), b.evidence());
        var dp = RegistrationPlan.create(f.source().build(), f.source().inventory(), f.lowering(),
                List.of(a, bEv), List.of(before(f, a, bEv)),
                new RegistrationPlan.Container("authored-root", f.source().evidence()),
                List.of(), RegistrationPlan.OverridePolicy.FORBID, RegistrationPlan.Limits.conservative());
        var rA = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var rB = regStep("reg-b", bEv, Optional.empty(), f.source().evidence());
        var result = eval(f, rp(f, dp, List.of(rA, rB), List.of("reg-a", "reg-b")), true);
        assertEquals(2, result.rows().size());
        var cov = result.coverage();
        assertEquals(cov.inputSteps(), cov.accepted() + cov.registered() + cov.overridden()
                + cov.aliased() + cov.removed() + cov.skipped() + cov.unknown() + cov.errors() + cov.notReached());
    }

    @Test void resourceExhaustionProducesUnknownButDenominatorRemainsClosed() throws Exception {
        var f = pf();
        var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var b = event(f, "b", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a, b), List.of(before(f, a, b)), RegistrationPlan.Limits.conservative());
        var rA = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var rB = regStep("reg-b", b, Optional.empty(), f.source().evidence());
        // maxStateCells=1 forces immediate limit at initial state storage
        var plan = new BeanRegistrationPlan(dp, List.of(rA, rB), List.of("reg-a", "reg-b"),
                RegistrationEvent.Completeness.COMPLETE, f.source().evidence(),
                RegistrationEvent.Completeness.COMPLETE, RegistrationEvent.Completeness.COMPLETE,
                f.source().evidence(), List.of(), List.of(), new BeanRegistrationPlan.Limits(100, 1, 100, 100));
        var result = eval(f, plan, true);
        assertEquals(2, result.rows().size());
        var cov = result.coverage();
        assertEquals(cov.inputSteps(), cov.accepted() + cov.registered() + cov.overridden()
                + cov.aliased() + cov.removed() + cov.skipped() + cov.unknown() + cov.errors() + cov.notReached());
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("REGISTRATION_LIMIT")));
    }

    // ---- 11. Identity / provenance -----------------------------------------

    @Test void equivalentInputsProduceIdenticalResultIdentity() throws Exception {
        var f = pf(); var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a));
        var gate = gateStep("gate", a, f.source().evidence()); var reg = regStep("reg", a, Optional.of("gate"), f.source().evidence());
        var plan = rp(f, dp, List.of(gate, reg), List.of("gate", "reg"));
        var r1 = eval(f, plan, true); var r2 = eval(f, plan, true);
        assertEquals(r1.identity(), r2.identity()); assertEquals(r1.semanticsContextIdentity(), r2.semanticsContextIdentity());
    }

    @Test void changedLimitsProduceDifferentContextIdentity() throws Exception {
        var f = pf(); var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a));
        var gate = gateStep("gate", a, f.source().evidence()); var reg = regStep("reg", a, Optional.of("gate"), f.source().evidence());
        var p1 = rp(f, dp, List.of(gate, reg), List.of("gate", "reg"));
        var p2 = new BeanRegistrationPlan(dp, List.of(gate, reg), List.of("gate", "reg"),
                RegistrationEvent.Completeness.COMPLETE, f.source().evidence(),
                RegistrationEvent.Completeness.COMPLETE, RegistrationEvent.Completeness.COMPLETE,
                f.source().evidence(), List.of(), List.of(), new BeanRegistrationPlan.Limits(10, 1000, 1000, 1000));
        var r1 = eval(f, p1, true); var r2 = eval(f, p2, true);
        assertNotEquals(r1.semanticsContextIdentity(), r2.semanticsContextIdentity());
        assertNotEquals(r1.identity(), r2.identity());
    }

    @Test void existingDiscoveryApiIsUnaffectedByRegistrationEvaluation() throws Exception {
        // BeanRegistrationTransitions uses prepareRegistration (registration-preparation mode)
        // which has a different reasonerPolicy than the public evaluate() (evidenced-order mode).
        // Their discovery identities intentionally differ to track which analysis mode was used.
        var f = pf(); var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(root));
        var discoveryResult = run(f, dp, true);
        assertEquals(DiscoveryTransitions.Outcome.DISCOVERED,
                discoveryResult.rows().stream().filter(r -> r.event().equals(root.identity()))
                        .findFirst().orElseThrow().outcome());
        var gate = gateStep("gate", root, f.source().evidence()); var reg = regStep("reg", root, Optional.of("gate"), f.source().evidence());
        var rp = rp(f, dp, List.of(gate, reg), List.of("gate", "reg"));
        var regResult = eval(f, rp, true);
        // prepareRegistration discovery has same plan identity but different context reasonerPolicy
        assertNotEquals(discoveryResult.identity(), regResult.discovery().identity());
        // The embedded discovery result still covers the same event with DISCOVERED outcome
        assertEquals(DiscoveryTransitions.Outcome.DISCOVERED,
                regResult.discovery().rows().stream().filter(r -> r.event().equals(root.identity()))
                        .findFirst().orElseThrow().outcome());
    }

    @Test void transitionChainAndStateSnapshotsGrowMonotonically() throws Exception {
        var f = pf();
        var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var b = event(f, "b", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(f, List.of(a, b), List.of(before(f, a, b)), RegistrationPlan.Limits.conservative());
        var rA = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var rB = regStep("reg-b", b, Optional.empty(), f.source().evidence());
        var plan = rp(f, dp, List.of(rA, rB), List.of("reg-a", "reg-b"));
        var result = eval(f, plan, true);
        assertEquals(3, result.states().size()); assertEquals(2, result.transitions().size());
        for (int i = 0; i < result.transitions().size(); i++) {
            assertEquals(result.states().get(i).identity(), result.transitions().get(i).inputState(), "Transition " + i + " input");
            assertEquals(result.states().get(i + 1).identity(), result.transitions().get(i).outputState(), "Transition " + i + " output");
        }
    }

    @Test void wrongFrameworkVersionProducesVersionFragmentGap() throws Exception {
        var pins = ConditionLoweringTest.pins("6.1.14", "3.3.5");
        var src = ConditionLoweringTest.fixture(
                List.of(new ConditionLoweringTest.Spec(ConditionLoweringTest.PROFILE, "@Profile(\"dev\")")), pins);
        var lowering = ConditionLoweringTest.lower(src);
        var profile = new FiniteDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"),
                List.of(FiniteDomain.Value.bool(false), FiniteDomain.Value.bool(true)), src.evidence());
        var space = new ConfigurationSpace(src.build(),
                new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY, List.of(), List.of(),
                        ContentDigest.sha256Utf8("authored-environment")),
                Optional.empty(), List.of(profile), List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE, List.of(), src.evidence()),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES, List.of(), Map.of(), List.of(), src.evidence()),
                ConfigurationSpaceTest.VERSION,
                new ConfigurationSpace.FeasibilityPolicy(ConfigurationSpaceTest.VERSION,
                        ConfigurationSpace.Limits.conservative(), src.evidence()));
        var wf = new RegistrationDiscoveryTest.Fixture(src, lowering, space);
        var a = event(wf, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var dp = plan(wf, List.of(a));
        var reg = regStep("reg-a", a, Optional.empty(), wf.source().evidence());
        var plan = rp(wf, dp, List.of(reg), List.of("reg-a"));
        var result = BeanRegistrationTransitions.evaluate(plan,
                ConditionModel.create(wf.space(), wf.lowering().occurrences()),
                new ConfigurationAssignment(Map.of(
                        new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"), FiniteDomain.Value.bool(true)),
                        wf.source().evidence()),
                ExogenousConditionEvaluator.Limits.conservative());
        assertTrue(result.capabilityGaps().stream()
                .anyMatch(g -> g.reasonCode().equals("VERSION_FRAGMENT_NOT_VALIDATED")));
    }

    // ---- 12. Ordered fallbacks: missing-bean suppression -------------------

    @Test void reversedMissingBeanFallbacksFirstAdmittedDefinitionSuppressesSecond() throws Exception {
        var f = fixture("@ConditionalOnMissingBean(Token.class)");
        var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.REGISTER_BEAN);
        var b = event(f, "b", Kind.CONFIGURATION, null, RequiredPhase.REGISTER_BEAN);
        var dp = plan(f, List.of(a, b), List.of(before(f, a, b)), RegistrationPlan.Limits.conservative());
        var occ = f.lowering().occurrences().getFirst().identity();
        var selector = new ConditionExpression.Bean(ConditionExpression.BeanPredicate.MISSING,
                "authored-root", List.of("com.example.Token"), List.of(), List.of(), ConditionExpression.Search.CURRENT);
        var query = new BeanRegistrationEvidence.Query(occ, selector, List.of(), List.of(),
                RegistrationEvent.Completeness.COMPLETE, f.source().evidence());
        var qt = new BeanRegistrationEvidence.QueryType(BeanRegistrationEvidence.SelectorKind.TYPE,
                "com.example.Token", BeanRegistrationEvidence.TypeStatus.AVAILABLE, f.source().evidence());

        var matchA = new BeanRegistrationEvidence.Match(BeanRegistrationEvidence.SelectorKind.TYPE, "com.example.Token", TRUE, f.source().evidence());
        var defA = new BeanRegistrationEvidence.Definition(a.candidate().orElseThrow().identity(), List.of(matchA),
                TRUE, TRUE, FALSE, FALSE, FALSE, f.source().evidence());
        var matchB = new BeanRegistrationEvidence.Match(BeanRegistrationEvidence.SelectorKind.TYPE, "com.example.Token", TRUE, f.source().evidence());
        var defB = new BeanRegistrationEvidence.Definition(b.candidate().orElseThrow().identity(), List.of(matchB),
                TRUE, TRUE, FALSE, FALSE, FALSE, f.source().evidence());

        var rA = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var rB = regStep("reg-b", b, Optional.empty(), f.source().evidence());

        // Forward schedule: A runs first, admits, suppresses B
        var planAB = rp(f, dp, List.of(rA, rB), List.of("reg-a", "reg-b"),
                RegistrationEvent.Completeness.COMPLETE, RegistrationEvent.Completeness.COMPLETE,
                RegistrationEvent.Completeness.COMPLETE, List.of(defA, defB), List.of(query), List.of(qt));
        var resAB = eval(f, planAB, true);
        assertEquals(REGISTERED, out(resAB, "reg-a"));
        assertEquals(SKIPPED, out(resAB, "reg-b"));
        assertTrue(resAB.states().getLast().definitions().containsKey("a"));
        assertFalse(resAB.states().getLast().definitions().containsKey("b"));

        // Reverse schedule: B runs first, admits, suppresses A
        var dpBA = plan(f, List.of(b, a), List.of(before(f, b, a)), RegistrationPlan.Limits.conservative());
        var planBA = rp(f, dpBA, List.of(rB, rA), List.of("reg-b", "reg-a"),
                RegistrationEvent.Completeness.COMPLETE, RegistrationEvent.Completeness.COMPLETE,
                RegistrationEvent.Completeness.COMPLETE, List.of(defA, defB), List.of(query), List.of(qt));
        var resBA = eval(f, planBA, true);
        assertEquals(REGISTERED, out(resBA, "reg-b"));
        assertEquals(SKIPPED, out(resBA, "reg-a"));
        assertTrue(resBA.states().getLast().definitions().containsKey("b"));
        assertFalse(resBA.states().getLast().definitions().containsKey("a"));
    }

    static RegistrationDiscoveryTest.Fixture beanFixture(String annotation) throws Exception {
        String type = annotation.startsWith("@ConditionalOnBean")
                ? "org.springframework.boot.autoconfigure.condition.ConditionalOnBean"
                : annotation.startsWith("@ConditionalOnSingleCandidate")
                ? "org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate"
                : "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean";
        var source = ConditionLoweringTest.fixture(List.of(new ConditionLoweringTest.Spec(type, annotation)));
        var lowering = ConditionLoweringTest.lower(source);
        var evidence = source.evidence();
        var profile = new FiniteDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"),
                List.of(FiniteDomain.Value.bool(false), FiniteDomain.Value.bool(true)), evidence);
        var space = new ConfigurationSpace(source.build(), new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,
                List.of(), List.of(), ContentDigest.sha256Utf8("authored-environment")), Optional.empty(), List.of(profile), List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE, List.of(), evidence),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES, List.of(), Map.of(), List.of(), evidence),
                ConfigurationSpaceTest.VERSION, new ConfigurationSpace.FeasibilityPolicy(ConfigurationSpaceTest.VERSION, ConfigurationSpace.Limits.conservative(), evidence));
        return new RegistrationDiscoveryTest.Fixture(source, lowering, space);
    }

    // ---- 13. Selector logic: distinct definitions satisfy distinct selectors -

    @Test void distinctDefinitionsCanSatisfyDistinctPresenceSelectors() throws Exception {
        var f = beanFixture("@ConditionalOnBean(Token.class)");
        var a = event(f, "a", Kind.CONFIGURATION, null, null);
        var b = event(f, "b", Kind.CONFIGURATION, null, null);
        var c = event(f, "c", Kind.CONFIGURATION, null, RequiredPhase.REGISTER_BEAN);
        var dp = plan(f, List.of(a, b, c), List.of(before(f, a, b), before(f, b, c)), RegistrationPlan.Limits.conservative());
        var occ = f.lowering().occurrences().getFirst().identity();

        // C requires PRESENT for BOTH TypeA and TypeB
        var selector = new ConditionExpression.Bean(ConditionExpression.BeanPredicate.PRESENT,
                "authored-root", List.of("com.example.TypeA", "com.example.TypeB"), List.of(), List.of(), ConditionExpression.Search.CURRENT);
        var query = new BeanRegistrationEvidence.Query(occ, selector, List.of(), List.of(),
                RegistrationEvent.Completeness.COMPLETE, f.source().evidence());
        var qtA = new BeanRegistrationEvidence.QueryType(BeanRegistrationEvidence.SelectorKind.TYPE,
                "com.example.TypeA", BeanRegistrationEvidence.TypeStatus.AVAILABLE, f.source().evidence());
        var qtB = new BeanRegistrationEvidence.QueryType(BeanRegistrationEvidence.SelectorKind.TYPE,
                "com.example.TypeB", BeanRegistrationEvidence.TypeStatus.AVAILABLE, f.source().evidence());

        // Def A matches TypeA only
        var matchA = new BeanRegistrationEvidence.Match(BeanRegistrationEvidence.SelectorKind.TYPE, "com.example.TypeA", TRUE, f.source().evidence());
        var defA = new BeanRegistrationEvidence.Definition(a.candidate().orElseThrow().identity(), List.of(matchA),
                TRUE, TRUE, FALSE, FALSE, FALSE, f.source().evidence());
        // Def B matches TypeB only
        var matchB = new BeanRegistrationEvidence.Match(BeanRegistrationEvidence.SelectorKind.TYPE, "com.example.TypeB", TRUE, f.source().evidence());
        var defB = new BeanRegistrationEvidence.Definition(b.candidate().orElseThrow().identity(), List.of(matchB),
                TRUE, TRUE, FALSE, FALSE, FALSE, f.source().evidence());

        var rA = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var rB = regStep("reg-b", b, Optional.empty(), f.source().evidence());
        var rC = regStep("reg-c", c, Optional.empty(), f.source().evidence());

        var plan = rp(f, dp, List.of(rA, rB, rC), List.of("reg-a", "reg-b", "reg-c"),
                RegistrationEvent.Completeness.COMPLETE, RegistrationEvent.Completeness.COMPLETE,
                RegistrationEvent.Completeness.COMPLETE, List.of(defA, defB), List.of(query), List.of(qtA, qtB));
        var result = eval(f, plan, true);
        assertEquals(REGISTERED, out(result, "reg-a"));
        assertEquals(REGISTERED, out(result, "reg-b"));
        assertEquals(REGISTERED, out(result, "reg-c"));
        assertTrue(result.states().getLast().definitions().containsKey("c"));
    }

    // ---- 14. Flags/names: autowire-ineligible excluded from type queries -----

    @Test void autowireIneligibleCandidateExcludedFromTypeQueries() throws Exception {
        var f = beanFixture("@ConditionalOnBean(Token.class)");
        var a = event(f, "a", Kind.CONFIGURATION, null, null);
        var b = event(f, "b", Kind.CONFIGURATION, null, RequiredPhase.REGISTER_BEAN);
        var dp = plan(f, List.of(a, b), List.of(before(f, a, b)), RegistrationPlan.Limits.conservative());
        var occ = f.lowering().occurrences().getFirst().identity();

        // B requires PRESENT of Token
        var selector = new ConditionExpression.Bean(ConditionExpression.BeanPredicate.PRESENT,
                "authored-root", List.of("com.example.Token"), List.of(), List.of(), ConditionExpression.Search.CURRENT);
        var query = new BeanRegistrationEvidence.Query(occ, selector, List.of(), List.of(),
                RegistrationEvent.Completeness.COMPLETE, f.source().evidence());
        var qt = new BeanRegistrationEvidence.QueryType(BeanRegistrationEvidence.SelectorKind.TYPE,
                "com.example.Token", BeanRegistrationEvidence.TypeStatus.AVAILABLE, f.source().evidence());

        // A matches Token, but autowireCandidate = FALSE
        var matchA = new BeanRegistrationEvidence.Match(BeanRegistrationEvidence.SelectorKind.TYPE, "com.example.Token", TRUE, f.source().evidence());
        var defA = new BeanRegistrationEvidence.Definition(a.candidate().orElseThrow().identity(), List.of(matchA),
                FALSE, TRUE, FALSE, FALSE, FALSE, f.source().evidence());

        var rA = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var rB = regStep("reg-b", b, Optional.empty(), f.source().evidence());

        var plan = rp(f, dp, List.of(rA, rB), List.of("reg-a", "reg-b"),
                RegistrationEvent.Completeness.COMPLETE, RegistrationEvent.Completeness.COMPLETE,
                RegistrationEvent.Completeness.COMPLETE, List.of(defA), List.of(query), List.of(qt));
        var result = eval(f, plan, true);
        assertEquals(REGISTERED, out(result, "reg-a"));
        // Since A is autowire-ineligible, type lookup excludes it, so Token is NOT present → B is SKIPPED
        assertEquals(SKIPPED, out(result, "reg-b"));
    }

    // ---- 15. Cardinality: singleCandidate variants -------------------------

    @Test void singleCandidateUniquePrimaryAndFallbackResolvesCorrectly() throws Exception {
        var f = beanFixture("@ConditionalOnSingleCandidate(Token.class)");
        var a = event(f, "a", Kind.CONFIGURATION, null, null);
        var b = event(f, "b", Kind.CONFIGURATION, null, null);
        var c = event(f, "c", Kind.CONFIGURATION, null, RequiredPhase.REGISTER_BEAN);
        var dp = plan(f, List.of(a, b, c), List.of(before(f, a, b), before(f, b, c)), RegistrationPlan.Limits.conservative());
        var occ = f.lowering().occurrences().getFirst().identity();

        var selector = new ConditionExpression.Bean(ConditionExpression.BeanPredicate.SINGLE_CANDIDATE,
                "authored-root", List.of("com.example.Token"), List.of(), List.of(), ConditionExpression.Search.CURRENT);
        var query = new BeanRegistrationEvidence.Query(occ, selector, List.of(), List.of(),
                RegistrationEvent.Completeness.COMPLETE, f.source().evidence());
        var qt = new BeanRegistrationEvidence.QueryType(BeanRegistrationEvidence.SelectorKind.TYPE,
                "com.example.Token", BeanRegistrationEvidence.TypeStatus.AVAILABLE, f.source().evidence());

        // A matches Token, primary = TRUE
        var matchA = new BeanRegistrationEvidence.Match(BeanRegistrationEvidence.SelectorKind.TYPE, "com.example.Token", TRUE, f.source().evidence());
        var defA = new BeanRegistrationEvidence.Definition(a.candidate().orElseThrow().identity(), List.of(matchA),
                TRUE, TRUE, TRUE, FALSE, FALSE, f.source().evidence());
        // B matches Token, primary = FALSE
        var matchB = new BeanRegistrationEvidence.Match(BeanRegistrationEvidence.SelectorKind.TYPE, "com.example.Token", TRUE, f.source().evidence());
        var defB = new BeanRegistrationEvidence.Definition(b.candidate().orElseThrow().identity(), List.of(matchB),
                TRUE, TRUE, FALSE, FALSE, FALSE, f.source().evidence());

        var rA = regStep("reg-a", a, Optional.empty(), f.source().evidence());
        var rB = regStep("reg-b", b, Optional.empty(), f.source().evidence());
        var rC = regStep("reg-c", c, Optional.empty(), f.source().evidence());

        var plan = rp(f, dp, List.of(rA, rB, rC), List.of("reg-a", "reg-b", "reg-c"),
                RegistrationEvent.Completeness.COMPLETE, RegistrationEvent.Completeness.COMPLETE,
                RegistrationEvent.Completeness.COMPLETE, List.of(defA, defB), List.of(query), List.of(qt));
        var result = eval(f, plan, true);
        assertEquals(REGISTERED, out(result, "reg-a"));
        assertEquals(REGISTERED, out(result, "reg-b"));
        // C evaluates SINGLE_CANDIDATE: A is primary, B is not → exactly one primary → TRUE → REGISTERED
        assertEquals(REGISTERED, out(result, "reg-c"));
    }
}
