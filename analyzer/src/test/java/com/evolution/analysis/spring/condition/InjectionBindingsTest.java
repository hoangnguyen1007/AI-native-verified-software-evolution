package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.JavaType;
import com.evolution.analysis.spring.binding.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.condition.RegistrationDiscoveryTest.*;
import static com.evolution.analysis.spring.condition.BeanRegistrationTransitionsTest.*;
import static com.evolution.analysis.spring.condition.LogicalValue.TRUE;
import static com.evolution.analysis.spring.condition.LogicalValue.FALSE;
import static com.evolution.analysis.spring.registration.RegistrationEvent.Completeness.COMPLETE;
import static com.evolution.analysis.spring.binding.InjectionBindingPlan.*;
import static com.evolution.analysis.spring.binding.InjectionBindings.Outcome.*;

/** Compact, authored normalized descriptors. Expected selection is specified independently. */
class InjectionBindingsTest {
    record Fixture(RegistrationDiscoveryTest.Fixture source, List<RegistrationEvent> events, BeanRegistrationPlan registration) {}
    static Fixture setup(String... names) throws Exception {
        var f = fixture("@Profile(\"dev\")");
        List<RegistrationEvent> events = new ArrayList<>(); List<RegistrationPlan.Precedence> order = new ArrayList<>();
        for (var name : names) {
            var e = event(f, name, RegistrationEvent.Kind.CONFIGURATION, null, RegistrationEvent.RequiredPhase.ORDINARY);
            if (!events.isEmpty()) order.add(before(f, events.getLast(), e));
            events.add(e);
        }
        if (events.isEmpty()) {
            var gate = event(f, "empty-container-gate", RegistrationEvent.Kind.CONFIGURATION, null, RegistrationEvent.RequiredPhase.ORDINARY);
            var step = new BeanRegistrationPlan.Step("empty-skip", gate.identity(), BeanRegistrationPlan.Operation.REGISTER_DEFINITION,
                    Optional.empty(), FALSE, Optional.empty(), f.source().evidence());
            return new Fixture(f, List.of(), rp(f, plan(f, List.of(gate)), List.of(step), List.of("empty-skip")));
        }
        var dp = plan(f, events, order, RegistrationPlan.Limits.conservative());
        var steps = events.stream().map(e -> regStep("reg-" + e.eventSlot(), e, Optional.empty(), f.source().evidence())).toList();
        return new Fixture(f, events, rp(f, dp, steps, steps.stream().map(BeanRegistrationPlan.Step::key).toList()));
    }
    static BeanDefinitionCandidate.Identity id(Fixture f, String name) {
        return f.events().stream().filter(e -> e.eventSlot().equals(name)).findFirst().orElseThrow().candidate().orElseThrow().identity();
    }
    static Dependency dependency(Fixture f, Shape shape, Name name, Required required) {
        var ev = f.source().source().evidence();
        var typeId = EntityIdentity.from(EntityOrigin.SYNTHETIC, EntityScope.external(EntityOrigin.SYNTHETIC,
                "authored-type", ContentDigest.sha256Utf8("interface Token {}")), EntityKind.TYPE, "Token");
        var type = new JavaType(JavaType.Kind.DECLARED, "Token", Optional.of(typeId), List.of(), Optional.empty(), SemanticStatus.RESOLVED);
        return new Dependency(new InjectionPoint(f.source().source().build().identity(), "Consumer", InjectionPoint.SiteKind.FIELD, "token", ev),
                Optional.empty(), type, shape, Mode.AUTOWIRE, required, name, Name.absent(), FALSE,
                true, false, false, false, FALSE, Normalization.COMPLETE, ev);
    }
    static BindingEvidence.Definition definition(Fixture f, String name, boolean primary, boolean fallback, Integer priority) {
        return new BindingEvidence.Definition(id(f, name), TRUE, TRUE, primary ? TRUE : FALSE, fallback ? TRUE : FALSE,
                Optional.empty(), BindingEvidence.RuntimeKind.ORDINARY,
                priority == null ? BindingEvidence.Rank.absent() : BindingEvidence.Rank.of(priority), BindingEvidence.Rank.absent(), FALSE,
                f.source().source().evidence());
    }
    static BindingEvidence.Match match(Fixture f, Dependency d, String name, BindingEvidence.Lane lane,
                                       LogicalValue raw, LogicalValue strict, LogicalValue fallback, LogicalValue qualifier) {
        return new BindingEvidence.Match(d.identity(), id(f, name), lane, raw, strict, fallback, qualifier,
                BindingEvidence.Knowledge.KNOWN, f.source().source().evidence());
    }
    static InjectionBindingPlan bindingPlan(Fixture f, Dependency d, List<BindingEvidence.Definition> defs) {
        return bindingPlan(f, List.of(d), defs, f.events().stream().map(e -> match(f, d, e.eventSlot(), BindingEvidence.Lane.DIRECT, TRUE, TRUE, TRUE, TRUE)).toList());
    }
    static InjectionBindingPlan bindingPlan(Fixture f, List<Dependency> deps, List<BindingEvidence.Definition> defs, List<BindingEvidence.Match> matches) {
        return new InjectionBindingPlan(f.registration(), deps, defs, matches, List.of(),
                new Environment(COMPLETE, COMPLETE, COMPLETE, ComparatorPolicy.SPRING_ORDER,
                        f.events().stream().map(RegistrationEvent::eventSlot).toList(), COMPLETE, f.source().source().evidence()), Limits.conservative());
    }
    static InjectionBindings.Result resolve(Fixture f, InjectionBindingPlan p) {
        return InjectionBindings.evaluate(p, ConditionModel.create(f.source().space(), f.source().lowering().occurrences()),
                new ConfigurationAssignment(Map.of(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"), FiniteDomain.Value.bool(true)), f.source().source().evidence()),
                ExogenousConditionEvaluator.Limits.conservative());
    }
    static void selected(InjectionBindings.Result r, String name) {
        assertEquals(SELECTED, r.rows().getFirst().outcome());
        assertEquals(List.of(name), r.rows().getFirst().selected().stream().map(InjectionBindings.Target::beanName).toList());
    }
    @Test void primaryWinsOverDependencyNameAndPriority() throws Exception {
        var f = setup("primary", "named", "priority");
        var d = dependency(f, Shape.SINGLE, Name.of("named"), Required.REQUIRED);
        selected(resolve(f, bindingPlan(f, d, List.of(definition(f, "primary", true, false, null),
                definition(f, "named", false, false, null), definition(f, "priority", false, false, 1)))), "primary");
    }

    static Dependency change(Dependency d, Shape shape, Mode mode, Required required, Name name, Name suggested,
                             LogicalValue qualifier, boolean shortcut, boolean defaultName, boolean typeFallback,
                             boolean empty, Normalization normalization, Optional<BeanDefinitionCandidate.Identity> owner) {
        return new Dependency(d.point(), owner, d.requestedType(), shape, mode, required, name, suggested, qualifier,
                shortcut, defaultName, typeFallback, empty, d.elementIndicatesMultiple(), normalization, d.evidence());
    }
    static BindingEvidence.Definition flags(BindingEvidence.Definition d, LogicalValue autowire, LogicalValue defaults,
                                            LogicalValue primary, LogicalValue fallback) {
        return new BindingEvidence.Definition(d.candidate(), autowire, defaults, primary, fallback, d.factoryOwner(),
                d.runtimeKind(), d.priority(), d.order(), d.priorityOrdered(), d.evidence());
    }
    static InjectionBindingPlan with(InjectionBindingPlan p, Environment environment, Limits limits) {
        return new InjectionBindingPlan(p.registrationPlan(), p.dependencies(), p.definitions(), p.matches(), p.groups(), environment, limits);
    }
    static List<BindingEvidence.Definition> ordinary(Fixture f) {
        return f.events().stream().map(e -> definition(f, e.eventSlot(), false, false, null)).toList();
    }
    static void outcome(InjectionBindings.Result r, InjectionBindings.Outcome outcome) {
        assertEquals(outcome, r.rows().getFirst().outcome(), () -> r.issues().stream().map(i -> i.reason().name()).toList()
                + "; registration=" + r.registration().issues().stream().map(i -> i.reason().name()).toList());
        if (outcome != SELECTED && outcome != AGGREGATE) assertTrue(r.rows().getFirst().selected().isEmpty());
    }
    static void reason(InjectionBindings.Result r, BindingProcessing.Reason reason) {
        assertTrue(r.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals(reason.name())), reason.name());
    }
    @Test void namePrecedesPriorityInExact620Fragment() throws Exception {
        var f = setup("named", "priority"); var d = dependency(f, Shape.SINGLE, Name.of("named"), Required.REQUIRED);
        selected(resolve(f, bindingPlan(f, d, List.of(definition(f, "named", false, false, null), definition(f, "priority", false, false, -1)))), "named");
    }
    @Test void uniqueNonFallbackPrecedesEvenFallbackPrimaryNameShortcut() throws Exception {
        var f = setup("normal", "fallback"); var d = dependency(f, Shape.SINGLE, Name.of("fallback"), Required.REQUIRED);
        selected(resolve(f, bindingPlan(f, d, List.of(definition(f, "normal", false, false, 100), definition(f, "fallback", false, true, -100)))), "normal");
    }
    @Test void primaryFallbackBeanStillWinsOverOrdinaryNonPrimary() throws Exception {
        var f = setup("normal", "fallback"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        selected(resolve(f, bindingPlan(f, d, List.of(definition(f, "normal", false, false, null), definition(f, "fallback", true, true, null)))), "fallback");
    }
    @Test void twoPrimariesAreAnErrorEvenWithNameAndDifferentPriority() throws Exception {
        var f = setup("a", "b"); var d = dependency(f, Shape.SINGLE, Name.of("a"), Required.OPTIONAL);
        var result = resolve(f, bindingPlan(f, d, List.of(definition(f, "a", true, false, 1), definition(f, "b", true, false, 2))));
        outcome(result, AMBIGUOUS); reason(result, BindingProcessing.Reason.PRIMARY_CONFLICT);
    }
    @Test void optionalAndOptionalWrapperDoNotSuppressScalarAmbiguity() throws Exception {
        var f = setup("a", "b");
        for (var shape : List.of(Shape.SINGLE, Shape.OPTIONAL)) {
            var d = dependency(f, shape, Name.absent(), Required.OPTIONAL);
            outcome(resolve(f, bindingPlan(f, d, ordinary(f))), AMBIGUOUS);
        }
    }
    @Test void requiredAndOptionalAbsenceHaveDifferentOutcomes() throws Exception {
        var f = setup();
        for (var required : Required.values()) {
            var d = dependency(f, Shape.SINGLE, Name.absent(), required);
            outcome(resolve(f, bindingPlan(f, d, List.of())), required == Required.REQUIRED ? UNSATISFIED : ABSENT_OPTIONAL);
        }
        var optional = dependency(f, Shape.OPTIONAL, Name.absent(), Required.REQUIRED);
        outcome(resolve(f, bindingPlan(f, optional, List.of())), ABSENT_OPTIONAL);
    }
    @Test void allFallbacksCanStillBeSelectedByPriority() throws Exception {
        var f = setup("a", "b"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        selected(resolve(f, bindingPlan(f, d, List.of(definition(f, "a", false, true, -5), definition(f, "b", false, true, 8)))), "a");
    }
    @Test void highestPriorityTieIsErrorButLowerTiesDoNotMatter() throws Exception {
        var f = setup("a", "b", "c"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        selected(resolve(f, bindingPlan(f, d, List.of(definition(f, "a", false, false, 4), definition(f, "b", false, false, 4), definition(f, "c", false, false, -9)))), "c");
        var r = resolve(f, bindingPlan(f, d, List.of(definition(f, "a", false, false, 4), definition(f, "b", false, false, 4), definition(f, "c", false, false, 9))));
        outcome(r, AMBIGUOUS); reason(r, BindingProcessing.Reason.PRIORITY_CONFLICT);
    }
    @Test void orderMetadataNeverSelectsScalarAndDisabledComparatorIgnoresPriority() throws Exception {
        var f = setup("a", "b"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var p = bindingPlan(f, d, List.of(definition(f, "a", false, false, -9), definition(f, "b", false, false, 5)));
        var e = p.environment();
        outcome(resolve(f, with(p, new Environment(COMPLETE, COMPLETE, COMPLETE, ComparatorPolicy.NONE,
                e.candidateOrder(), COMPLETE, e.evidence()), p.limits())), AMBIGUOUS);
    }
    @Test void qualifiersExcludePrimaryAndAdmitNonDefaultCandidateButNeverAutowireFalse() throws Exception {
        var f = setup("primary", "qualified"); var initial = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var d = change(initial, Shape.SINGLE, Mode.AUTOWIRE, Required.REQUIRED, Name.absent(), Name.absent(), TRUE, true, false, false, false, Normalization.COMPLETE, Optional.empty());
        var primary = definition(f, "primary", true, false, null);
        var qualified = flags(definition(f, "qualified", false, false, null), TRUE, FALSE, FALSE, FALSE);
        var proofs = List.of(match(f, d, "primary", BindingEvidence.Lane.DIRECT, TRUE, TRUE, TRUE, FALSE),
                match(f, d, "qualified", BindingEvidence.Lane.DIRECT, TRUE, TRUE, TRUE, TRUE));
        selected(resolve(f, bindingPlan(f, List.of(d), List.of(primary, qualified), proofs)), "qualified");
        outcome(resolve(f, bindingPlan(f, List.of(d), List.of(primary, flags(qualified, FALSE, FALSE, FALSE, FALSE)), proofs)), UNSATISFIED);
        var unqualified = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        selected(resolve(f, bindingPlan(f, unqualified, List.of(primary, qualified))), "primary");
    }
    @Test void absentMatchProofNeverEstablishesZeroCandidates() throws Exception {
        var f = setup("a"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.OPTIONAL);
        var r = resolve(f, bindingPlan(f, List.of(d), ordinary(f), List.of()));
        outcome(r, UNKNOWN); reason(r, BindingProcessing.Reason.MATCH_EVIDENCE_MISSING);
    }
    @Test void strictGenericCandidatesPreventRawFallbackCandidatesFromCompeting() throws Exception {
        var f = setup("typed", "raw"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var defs = List.of(definition(f, "typed", false, false, null), definition(f, "raw", true, false, null));
        var proofs = List.of(match(f, d, "typed", BindingEvidence.Lane.DIRECT, TRUE, TRUE, TRUE, TRUE),
                match(f, d, "raw", BindingEvidence.Lane.DIRECT, TRUE, FALSE, TRUE, TRUE));
        selected(resolve(f, bindingPlan(f, List.of(d), defs, proofs)), "typed");
    }
    @Test void genericFallbackRunsOnlyAfterEmptyStrictPass() throws Exception {
        var f = setup("raw"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var r = resolve(f, bindingPlan(f, List.of(d), ordinary(f), List.of(match(f, d, "raw", BindingEvidence.Lane.DIRECT, TRUE, FALSE, TRUE, TRUE))));
        selected(r, "raw"); assertTrue(r.rows().getFirst().trace().stream().anyMatch(t -> t.stage() == InjectionBindings.Stage.GENERIC_FALLBACK));
    }
    @Test void unknownGenericCompatibilityCannotBeReplacedByPositiveFallback() throws Exception {
        var f = setup("raw"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        outcome(resolve(f, bindingPlan(f, List.of(d), ordinary(f), List.of(match(f, d, "raw", BindingEvidence.Lane.DIRECT, TRUE, LogicalValue.UNKNOWN, TRUE, TRUE)))), UNKNOWN);
    }
    @Test void scalarSelfReferenceIsLastResortAndLosesToNonSelfEvenIfPrimary() throws Exception {
        var f = setup("self", "other"); var base = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var d = change(base, Shape.SINGLE, Mode.AUTOWIRE, Required.REQUIRED, Name.absent(), Name.absent(), FALSE, true, false, false, false, Normalization.COMPLETE, Optional.of(id(f, "self")));
        selected(resolve(f, bindingPlan(f, d, List.of(definition(f, "self", true, false, null), definition(f, "other", false, false, null)))), "other");
        var proofs = List.of(match(f, d, "self", BindingEvidence.Lane.DIRECT, TRUE, TRUE, TRUE, TRUE),
                match(f, d, "other", BindingEvidence.Lane.DIRECT, FALSE, FALSE, FALSE, TRUE));
        selected(resolve(f, bindingPlan(f, List.of(d), ordinary(f), proofs)), "self");
    }
    @Test void resourceExplicitNameBypassesAutowireFlagsAndPrimary() throws Exception {
        var f = setup("named", "primary"); var base = dependency(f, Shape.SINGLE, Name.of("named"), Required.REQUIRED);
        var d = change(base, Shape.SINGLE, Mode.RESOURCE, Required.REQUIRED, Name.of("named"), Name.absent(), FALSE, true, false, true, false, Normalization.COMPLETE, Optional.empty());
        selected(resolve(f, bindingPlan(f, d, List.of(flags(definition(f, "named", false, false, null), FALSE, FALSE, FALSE, FALSE),
                definition(f, "primary", true, false, null)))), "named");
    }
    @Test void resourceFallsBackToTypeOnlyForAbsentDefaultName() throws Exception {
        var f = setup("available"); var base = dependency(f, Shape.SINGLE, Name.of("missing"), Required.REQUIRED);
        for (boolean defaultName : List.of(false, true)) {
            var d = change(base, Shape.SINGLE, Mode.RESOURCE, Required.REQUIRED, Name.of("missing"), Name.absent(), FALSE, true, defaultName, true, false, Normalization.COMPLETE, Optional.empty());
            var r = resolve(f, bindingPlan(f, d, ordinary(f)));
            if (defaultName) selected(r, "available"); else outcome(r, UNSATISFIED);
        }
    }
    @Test void resourceWrongTypeAtExistingNameDoesNotFallBack() throws Exception {
        var f = setup("named", "other"); var base = dependency(f, Shape.SINGLE, Name.of("named"), Required.REQUIRED);
        var d = change(base, Shape.SINGLE, Mode.RESOURCE, Required.REQUIRED, Name.of("named"), Name.absent(), FALSE, true, true, true, false, Normalization.COMPLETE, Optional.empty());
        var r = resolve(f, bindingPlan(f, List.of(d), ordinary(f), List.of(match(f, d, "named", BindingEvidence.Lane.DIRECT, FALSE, FALSE, FALSE, FALSE))));
        outcome(r, ERROR); reason(r, BindingProcessing.Reason.NAMED_TYPE_MISMATCH);
    }
    @Test void providersAndLazyDoNotInventImmediateTargetOrAbsence() throws Exception {
        var f = setup();
        for (var shape : List.of(Shape.OBJECT_FACTORY, Shape.OBJECT_PROVIDER, Shape.JAKARTA_PROVIDER, Shape.LAZY)) {
            var d = dependency(f, shape, Name.absent(), Required.REQUIRED); var r = resolve(f, bindingPlan(f, d, List.of()));
            outcome(r, DEFERRED); reason(r, BindingProcessing.Reason.DEFERRED_RUNTIME_TARGET);
        }
    }
    @Test void closedRegistryIsNotProofOfNoResolvableDependenciesOrNoProcessors() throws Exception {
        var f = setup("a"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var p = bindingPlan(f, d, ordinary(f)); var e = p.environment();
        for (var env : List.of(new Environment(COMPLETE, RegistrationEvent.Completeness.UNKNOWN, COMPLETE, e.comparator(), e.candidateOrder(), COMPLETE, e.evidence()),
                new Environment(COMPLETE, COMPLETE, RegistrationEvent.Completeness.UNKNOWN, e.comparator(), e.candidateOrder(), COMPLETE, e.evidence())))
            outcome(resolve(f, with(p, env, p.limits())), UNKNOWN);
    }
    @Test void replayAndCanonicalInputReorderingAreIdenticalAndLimitsChangeIdentity() throws Exception {
        var f = setup("a", "b"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var p = bindingPlan(f, d, ordinary(f)); var r = resolve(f, p);
        assertEquals(r.identity(), resolve(f, p).identity());
        var defs = new ArrayList<>(p.definitions()); Collections.reverse(defs);
        var matches = new ArrayList<>(p.matches()); Collections.reverse(matches);
        var reordered = new InjectionBindingPlan(p.registrationPlan(), p.dependencies(), defs, matches, p.groups(), p.environment(), p.limits());
        assertEquals(r.identity(), resolve(f, reordered).identity());
        assertNotEquals(r.semanticsContextIdentity(), resolve(f, with(p, p.environment(), new Limits(10001, 100000, 100000, 200000))).semanticsContextIdentity());
        assertEquals(1, r.coverage().dependencies());
        assertTrue(r.capabilityGaps().containsAll(BeanRegistrationTransitionsTest.eval(f.source(), f.registration(), true).capabilityGaps()));
    }
    @Test void deterministicLimitsRetainEveryDependencyWithTypedGap() throws Exception {
        var f = setup("a", "b"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var p = bindingPlan(f, d, ordinary(f));
        for (var limits : List.of(new Limits(10, 1, 100, 100), new Limits(10, 100, 1, 100), new Limits(10, 100, 100, 1))) {
            var r = resolve(f, with(p, p.environment(), limits)); outcome(r, UNKNOWN);
            reason(r, BindingProcessing.Reason.BINDING_LIMIT); assertEquals(1, r.coverage().dependencies());
        }
    }
    @Test void operationErrorsAreNotLogicalNoMatch() throws Exception {
        var f = setup("a"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.OPTIONAL);
        var m = new BindingEvidence.Match(d.identity(), id(f, "a"), BindingEvidence.Lane.DIRECT, FALSE, FALSE, FALSE, FALSE,
                BindingEvidence.Knowledge.ERROR, d.evidence());
        var r = resolve(f, bindingPlan(f, List.of(d), ordinary(f), List.of(m)));
        outcome(r, ERROR); reason(r, BindingProcessing.Reason.MATCH_OPERATION_ERROR);
    }

    static InjectionBindingPlan aggregatePlan(Fixture f, Dependency d, List<BindingEvidence.Definition> defs, boolean direct) {
        List<BindingEvidence.Match> matches = new ArrayList<>();
        for (var event : f.events()) {
            matches.add(match(f, d, event.eventSlot(), BindingEvidence.Lane.ELEMENT, TRUE, TRUE, TRUE, TRUE));
            matches.add(match(f, d, event.eventSlot(), BindingEvidence.Lane.DIRECT, direct ? TRUE : FALSE, TRUE, TRUE, TRUE));
        }
        return bindingPlan(f, List.of(d), defs, matches);
    }
    @Test void aggregatesContainAllEligibleCandidatesIncludingFallbackAndIgnorePrimary() throws Exception {
        var f = setup("a", "b");
        for (var shape : List.of(Shape.LIST, Shape.COLLECTION, Shape.SET, Shape.ARRAY, Shape.STRING_MAP)) {
            var d = dependency(f, shape, Name.absent(), Required.REQUIRED);
            var r = resolve(f, aggregatePlan(f, d, List.of(definition(f, "a", true, false, null), definition(f, "b", false, true, null)), false));
            outcome(r, AGGREGATE); assertEquals(List.of("a", "b"), r.rows().getFirst().selected().stream().map(InjectionBindings.Target::beanName).toList());
        }
    }
    @Test void namedCollectionShortcutPrecedesElementCollection() throws Exception {
        var f = setup("named", "element"); var d = dependency(f, Shape.LIST, Name.of("named"), Required.REQUIRED);
        selected(resolve(f, aggregatePlan(f, d, ordinary(f), true)), "named");
    }
    @Test void collectionElementsPrecedeDirectCollectionBeanWhenNoNameShortcut() throws Exception {
        var f = setup("a", "b"); var d = dependency(f, Shape.LIST, Name.absent(), Required.REQUIRED);
        outcome(resolve(f, aggregatePlan(f, d, ordinary(f), true)), AGGREGATE);
    }
    @Test void emptyRequiredAggregateIsUnsatisfiedUnlessExplicitConstructorFallback() throws Exception {
        var f = setup(); var base = dependency(f, Shape.LIST, Name.absent(), Required.REQUIRED);
        outcome(resolve(f, bindingPlan(f, base, List.of())), UNSATISFIED);
        var d = change(base, Shape.LIST, Mode.AUTOWIRE, Required.REQUIRED, Name.absent(), Name.absent(), FALSE, true, false, false, true, Normalization.COMPLETE, Optional.empty());
        outcome(resolve(f, bindingPlan(f, d, List.of())), AGGREGATE);
    }
    @Test void unknownAggregateOrderPreservesMembershipButDoesNotClaimOrder() throws Exception {
        var f = setup("b", "a"); var d = dependency(f, Shape.LIST, Name.absent(), Required.REQUIRED);
        var p = aggregatePlan(f, d, ordinary(f), false); var e = p.environment();
        var r = resolve(f, with(p, new Environment(COMPLETE, COMPLETE, COMPLETE, ComparatorPolicy.UNKNOWN,
                List.of(), RegistrationEvent.Completeness.UNKNOWN, e.evidence()), p.limits()));
        outcome(r, AGGREGATE); assertFalse(r.rows().getFirst().orderEstablished()); reason(r, BindingProcessing.Reason.AGGREGATE_ORDER_UNKNOWN);
    }
    @Test void invalidSourceEvidenceCannotCertifyBinding() throws Exception {
        var f = setup("a"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var source = (ConditionEvidence.Source) d.evidence();
        var wrong = new ConditionEvidence.Source(source.document(), ContentDigest.sha256Utf8("wrong"), source.span(), 0);
        var m = new BindingEvidence.Match(d.identity(), id(f, "a"), BindingEvidence.Lane.DIRECT, TRUE, TRUE, TRUE, TRUE, BindingEvidence.Knowledge.KNOWN, wrong);
        var r = resolve(f, bindingPlan(f, List.of(d), ordinary(f), List.of(m)));
        outcome(r, UNKNOWN); reason(r, BindingProcessing.Reason.SOURCE_EVIDENCE_MISMATCH);
    }
}
