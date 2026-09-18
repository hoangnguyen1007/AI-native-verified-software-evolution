package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.spring.binding.*;
import com.evolution.analysis.spring.registration.*;
import com.evolution.analysis.spring.truth.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

import static com.evolution.analysis.spring.condition.BeanRegistrationTransitionsTest.regStep;
import static com.evolution.analysis.spring.condition.BeanRegistrationTransitionsTest.rp;
import static com.evolution.analysis.spring.condition.InjectionBindingsTest.*;
import static com.evolution.analysis.spring.condition.RegistrationDiscoveryTest.*;
import static com.evolution.analysis.spring.condition.LogicalValue.*;
import static com.evolution.analysis.spring.registration.RegistrationEvent.Completeness.COMPLETE;
import static org.junit.jupiter.api.Assertions.*;

/** Authored finite-world controls for M4D. No target application or Spring container is executed. */
class TruthRegionEvaluationTest {
    private static final ContentDigest ARTIFACT = ContentDigest.sha256Utf8("authored-m4d-evaluator");

    record TruthFixture(RegistrationDiscoveryTest.Fixture source, RegistrationEvent event,
                        InjectionBindingPlan binding, ConditionModel model,
                        ConfigurationAssignment baseline) {}

    private static TruthFixture conditionalFixture(LogicalValue genericMatch) throws Exception {
        var source = fixture("@Profile(\"dev\")");
        var event = event(source, "token", RegistrationEvent.Kind.CONFIGURATION, null,
                RegistrationEvent.RequiredPhase.ORDINARY);
        var discovery = plan(source, List.of(event));
        var registration = rp(source, discovery,
                List.of(regStep("register-token", event, Optional.empty(), source.source().evidence())),
                List.of("register-token"));
        var bridge = new InjectionBindingsTest.Fixture(source, List.of(event), registration);
        var dependency = dependency(bridge, InjectionBindingPlan.Shape.SINGLE,
                InjectionBindingPlan.Name.absent(), InjectionBindingPlan.Required.OPTIONAL);
        var match = match(bridge, dependency, "token", BindingEvidence.Lane.DIRECT,
                TRUE, genericMatch, genericMatch, TRUE);
        var binding = bindingPlan(bridge, List.of(dependency), ordinary(bridge), List.of(match));
        var baseline = new ConfigurationAssignment(Map.of(
                new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"), FiniteDomain.Value.bool(false)),
                source.source().evidence());
        return new TruthFixture(source, event, binding,
                ConditionModel.create(source.space(), source.lowering().occurrences()), baseline);
    }

    private static TruthRegionEvaluation.Result evaluate(TruthFixture fixture) {
        return TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(
                fixture.binding(), fixture.model(), fixture.source().lowering().semantics(), fixture.baseline(),
                ExhaustiveConfigurationReasoner.INSTANCE, TruthRegionEvaluation.Limits.conservative(), ARTIFACT));
    }

    private static TruthRegionEvaluation.Region region(TruthRegionEvaluation.Result result,
                                                        ConditionalFactKey.Kind kind) {
        return result.regions().stream().filter(r -> r.fact().kind() == kind).findFirst().orElseThrow();
    }

    @Test void conditionalRegistrationAndBindingAreMayWithMinimalRevalidatedWitnesses() throws Exception {
        var result = evaluate(conditionalFixture(TRUE));
        assertEquals(TruthRegionEvaluation.Feasibility.FEASIBLE, result.feasibility());
        assertTrue(result.coverage().complete());
        assertEquals(2, result.coverage().enumeratedAssignments());

        for (var kind : ConditionalFactKey.Kind.values()) {
            var region = region(result, kind);
            assertEquals(TruthRegionEvaluation.Classification.MAY, region.classification());
            assertEquals(1, region.trueWorlds().size());
            assertEquals(1, region.falseWorlds().size());
            assertTrue(region.unknownWorlds().isEmpty());
            var witnesses = result.witnesses().stream().filter(w -> w.factRegionIdentity().equals(region.identity())).toList();
            assertEquals(Set.of(TRUE, FALSE), witnesses.stream().map(TruthRegionEvaluation.ConfigurationWitness::expectedTruth)
                    .collect(java.util.stream.Collectors.toSet()));
            assertEquals(Set.of(0, 1), witnesses.stream().map(w -> w.deltaAssignment().changedDimensions())
                    .collect(java.util.stream.Collectors.toSet()));
        }
        assertFalse(result.replays().isEmpty());
        assertTrue(result.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        assertEquals(result.identity(), evaluate(conditionalFixture(TRUE)).identity());
    }

    @Test void unknownBindingEvidenceNeverBecomesFalseOrNever() throws Exception {
        var result = evaluate(conditionalFixture(UNKNOWN));
        var presence = region(result, ConditionalFactKey.Kind.DEFINITION_PRESENT);
        assertEquals(TruthRegionEvaluation.Classification.MAY, presence.classification());
        var binding = region(result, ConditionalFactKey.Kind.SELECTED_BINDING);
        assertEquals(TruthRegionEvaluation.Classification.UNKNOWN, binding.classification());
        assertEquals(1, binding.falseWorlds().size(), "inactive world is a definite non-selection");
        assertEquals(1, binding.unknownWorlds().size(), "active world retains missing match evidence");
        assertTrue(binding.trueWorlds().isEmpty());
        assertTrue(result.operationalOutcomes().stream().anyMatch(o -> o.status() == TruthRegionEvaluation.OperationalStatus.PARTIAL));
        assertTrue(result.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
    }

    @Test void unconditionalWinnerIsMustAndDefiniteNonWinnerIsNever() throws Exception {
        var source = fixture("@Profile(\"dev\")");
        var root = event(source, "root", RegistrationEvent.Kind.CONFIGURATION, null,
                RegistrationEvent.RequiredPhase.ORDINARY);
        var winner = event(source, "winner", RegistrationEvent.Kind.DIRECT_IMPORT, root, null);
        var other = event(source, "other", RegistrationEvent.Kind.DIRECT_IMPORT, root, null);
        var discovery = plan(source, List.of(root, winner, other),
                List.of(before(source, winner, other)),
                RegistrationPlan.Limits.conservative());
        var registration = rp(source, discovery, List.of(
                regStep("register-root", root, Optional.empty(), source.source().evidence()),
                regStep("register-winner", winner, Optional.empty(), source.source().evidence()),
                regStep("register-other", other, Optional.empty(), source.source().evidence())),
                List.of("register-root", "register-winner", "register-other"));
        var bridge = new InjectionBindingsTest.Fixture(source, List.of(root, winner, other), registration);
        var dependency = dependency(bridge, InjectionBindingPlan.Shape.SINGLE,
                InjectionBindingPlan.Name.absent(), InjectionBindingPlan.Required.REQUIRED);
        var definitions = List.of(definition(bridge, "root", false, false, null),
                definition(bridge, "winner", true, false, null),
                definition(bridge, "other", false, false, null));
        var rawBinding = bindingPlan(bridge, List.of(dependency), definitions, List.of(
                match(bridge, dependency, "root", BindingEvidence.Lane.DIRECT, FALSE, FALSE, FALSE, TRUE),
                match(bridge, dependency, "winner", BindingEvidence.Lane.DIRECT, TRUE, TRUE, TRUE, TRUE),
                match(bridge, dependency, "other", BindingEvidence.Lane.DIRECT, TRUE, TRUE, TRUE, TRUE)));
        var binding = with(rawBinding, new InjectionBindingPlan.Environment(COMPLETE, COMPLETE, COMPLETE,
                InjectionBindingPlan.ComparatorPolicy.SPRING_ORDER, List.of("root", "winner", "other"), COMPLETE,
                source.source().evidence()), rawBinding.limits());
        var oldSpace = source.space();
        var onlyTrue = new FiniteDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"),
                List.of(FiniteDomain.Value.bool(true)), source.source().evidence());
        var space = new ConfigurationSpace(oldSpace.buildContext(), oldSpace.repositoryEnvelope(), oldSpace.deploymentEnvelope(),
                List.of(onlyTrue), oldSpace.constraints(), oldSpace.precedencePolicy(), oldSpace.profilePolicy(),
                oldSpace.abstractionVersion(), oldSpace.feasibilityPolicy());
        var baseline = new ConfigurationAssignment(Map.of(
                new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"), FiniteDomain.Value.bool(true)),
                source.source().evidence());
        var result = TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(binding,
                ConditionModel.create(space, source.lowering().occurrences()), source.lowering().semantics(),
                baseline, ExhaustiveConfigurationReasoner.INSTANCE, TruthRegionEvaluation.Limits.conservative(), ARTIFACT));

        assertTrue(result.regions().stream().filter(r -> r.fact().kind() == ConditionalFactKey.Kind.DEFINITION_PRESENT)
                .filter(r -> Set.of(id(bridge, "winner"), id(bridge, "other")).contains(r.fact().definition().orElseThrow()))
                .allMatch(r -> r.classification() == TruthRegionEvaluation.Classification.MUST),
                () -> result.regions().stream().map(r -> r.fact().kind() + ":" + r.classification()
                        + ":" + r.trueWorlds().size() + "/" + r.falseWorlds().size() + "/" + r.unknownWorlds().size()).toList()
                        + "; ops=" + result.operationalOutcomes() + "; gaps="
                        + result.capabilityGaps().stream().map(g -> g.reasonCode()).distinct().toList());
        var selected = result.regions().stream().filter(r -> r.fact().kind() == ConditionalFactKey.Kind.SELECTED_BINDING).toList();
        assertEquals(3, selected.size());
        var must = selected.stream().filter(r -> r.fact().target().orElseThrow().equals(id(bridge, "winner"))).findFirst().orElseThrow();
        var never = selected.stream().filter(r -> r.fact().target().orElseThrow().equals(id(bridge, "other"))).findFirst().orElseThrow();
        assertEquals(TruthRegionEvaluation.Classification.MUST, must.classification(),
                () -> must + "; ops=" + result.operationalOutcomes());
        assertEquals(TruthRegionEvaluation.Classification.NEVER, never.classification(),
                () -> never + "; ops=" + result.operationalOutcomes());
        assertTrue(result.witnesses().stream()
                .filter(w -> w.factRegionIdentity().equals(must.identity()) || w.factRegionIdentity().equals(never.identity()))
                .allMatch(w -> w.deltaAssignment().changedDimensions() == 0));
        assertTrue(result.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
    }

    @Test void enumerationLimitAndUnknownFeasibilityCannotPromoteUniversalFacts() throws Exception {
        var fixture = conditionalFixture(TRUE);
        var limits = new TruthRegionEvaluation.Limits(
                new FiniteConfigurationEvaluation.Limits(1, 100, 100,
                        ExogenousConditionEvaluator.Limits.conservative()), 100, 1000, 100);
        var result = TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(
                fixture.binding(), fixture.model(), fixture.source().lowering().semantics(), fixture.baseline(),
                ExhaustiveConfigurationReasoner.INSTANCE, limits, ARTIFACT));
        assertFalse(result.coverage().complete());
        assertEquals(TruthRegionEvaluation.Feasibility.UNKNOWN, result.feasibility());
        assertTrue(result.regions().stream().allMatch(r -> r.classification() == TruthRegionEvaluation.Classification.UNKNOWN));
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("ENUMERATION_INCOMPLETE")));
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("FEASIBILITY_UNKNOWN")));
    }

    @Test void emptyModeledSpaceIsInvalidNotVacuouslyMustOrNever() throws Exception {
        var fixture = conditionalFixture(TRUE);
        var source = fixture.source();
        var emptyDomain = new FiniteDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"),
                List.of(), source.source().evidence());
        var old = source.space();
        var empty = new ConfigurationSpace(old.buildContext(), old.repositoryEnvelope(), old.deploymentEnvelope(),
                List.of(emptyDomain), old.constraints(), old.precedencePolicy(), old.profilePolicy(),
                old.abstractionVersion(), old.feasibilityPolicy());
        var result = TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(
                fixture.binding(), ConditionModel.create(empty, source.lowering().occurrences()),
                source.lowering().semantics(), fixture.baseline(), ExhaustiveConfigurationReasoner.INSTANCE,
                TruthRegionEvaluation.Limits.conservative(), ARTIFACT));
        assertEquals(TruthRegionEvaluation.Feasibility.INVALID_MODEL, result.feasibility());
        assertTrue(result.regions().stream().allMatch(r -> r.classification() == TruthRegionEvaluation.Classification.UNKNOWN));
        assertTrue(result.witnesses().isEmpty());
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("INVALID_MODEL")));
    }

    @Test void incompatibleBuildContextsRemainExplicitAndUnknown() throws Exception {
        var fixture = conditionalFixture(TRUE);
        var foreign = fixture("@Profile(\"other\")");
        var local = fixture.model().space();
        var foreignSpace = new ConfigurationSpace(foreign.space().buildContext(), local.repositoryEnvelope(),
                local.deploymentEnvelope(), local.domains(), local.constraints(), local.precedencePolicy(),
                local.profilePolicy(), local.abstractionVersion(), local.feasibilityPolicy());
        var foreignModel = ConditionModel.create(foreignSpace, fixture.source().lowering().occurrences());
        var result = TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(
                fixture.binding(), foreignModel, foreign.lowering().semantics(), fixture.baseline(),
                ExhaustiveConfigurationReasoner.INSTANCE, TruthRegionEvaluation.Limits.conservative(), ARTIFACT));
        assertTrue(result.regions().stream().allMatch(r -> r.classification() == TruthRegionEvaluation.Classification.UNKNOWN));
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("CONTEXT_MISMATCH")));
        assertTrue(result.witnesses().isEmpty(), "an invalid top-level context cannot support a semantic witness");
        assertTrue(result.replays().isEmpty());
    }

    @Test void reasonerSupportsBoundedSetQueriesWithoutTreatingIncompleteAsUnsat() {
        var a = new ConfigurationReasoner.WorldKey("spring-world:sha256:" + "a".repeat(64));
        var b = new ConfigurationReasoner.WorldKey("spring-world:sha256:" + "b".repeat(64));
        var complete = new ConfigurationReasoner.Region(Set.of(a, b), Set.of(a), true);
        var right = new ConfigurationReasoner.Region(Set.of(a, b), Set.of(a, b), true);
        assertEquals(ConfigurationReasoner.Satisfiability.SATISFIABLE,
                ExhaustiveConfigurationReasoner.INSTANCE.satisfiability(complete));
        assertEquals(TRUE, ExhaustiveConfigurationReasoner.INSTANCE.implies(complete, right));
        assertEquals(FALSE, ExhaustiveConfigurationReasoner.INSTANCE.equivalent(complete, right));
        assertEquals(a, ExhaustiveConfigurationReasoner.INSTANCE.witness(complete).orElseThrow());
        assertEquals(ConfigurationReasoner.Satisfiability.UNKNOWN,
                ExhaustiveConfigurationReasoner.INSTANCE.satisfiability(
                        new ConfigurationReasoner.Region(Set.of(a, b), Set.of(), false)));

        var antecedentIncomplete = new ConfigurationReasoner.Region(Set.of(a), Set.of(a), false);
        var consequentUnexplored = new ConfigurationReasoner.Region(Set.of(), Set.of(), false);
        assertEquals(UNKNOWN, ExhaustiveConfigurationReasoner.INSTANCE.implies(
                antecedentIncomplete, consequentUnexplored));
        assertEquals(UNKNOWN, ExhaustiveConfigurationReasoner.INSTANCE.equivalent(
                antecedentIncomplete, consequentUnexplored));

        var consequentKnownFalse = new ConfigurationReasoner.Region(Set.of(a), Set.of(), false);
        assertEquals(FALSE, ExhaustiveConfigurationReasoner.INSTANCE.implies(
                antecedentIncomplete, consequentKnownFalse));
        assertEquals(FALSE, ExhaustiveConfigurationReasoner.INSTANCE.equivalent(
                antecedentIncomplete, consequentKnownFalse));
    }

    @Test void invalidBaselineIsExplicitAndCannotProduceWitnesses() throws Exception {
        var fixture = conditionalFixture(TRUE);
        var invalid = new ConfigurationAssignment(Map.of(
                new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "not-in-the-space"),
                FiniteDomain.Value.bool(true)), fixture.baseline().evidence());
        var result = TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(
                fixture.binding(), fixture.model(), fixture.source().lowering().semantics(), invalid,
                ExhaustiveConfigurationReasoner.INSTANCE, TruthRegionEvaluation.Limits.conservative(), ARTIFACT));

        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("BASELINE_INVALID")));
        assertTrue(result.witnesses().isEmpty());
        assertTrue(result.replays().isEmpty());
    }

    @Test void regionLimitRetainsUnknownCellsWithoutPublishingFalseReplayFailures() throws Exception {
        var fixture = conditionalFixture(TRUE);
        var normal = TruthRegionEvaluation.Limits.conservative();
        var limited = new TruthRegionEvaluation.Limits(normal.enumeration(), normal.maxFacts(),
                3, normal.maxWitnessReplays());
        var result = TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(
                fixture.binding(), fixture.model(), fixture.source().lowering().semantics(), fixture.baseline(),
                ExhaustiveConfigurationReasoner.INSTANCE, limited, ARTIFACT));

        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("REGION_LIMIT")));
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("OPERATIONAL_FAILURE")));
        assertTrue(result.capabilityGaps().stream().noneMatch(g -> g.reasonCode().equals("WITNESS_REPLAY_FAILED")));
        assertTrue(result.operationalOutcomes().stream().anyMatch(o ->
                o.status() == TruthRegionEvaluation.OperationalStatus.LIMIT_EXCEEDED));
        assertTrue(result.regions().stream().allMatch(r -> r.classification() == TruthRegionEvaluation.Classification.UNKNOWN));
        assertTrue(result.witnesses().stream().noneMatch(w -> w.expectedTruth() == UNKNOWN));
        assertTrue(result.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
    }

    @Test void operationalErrorWorldsRemainVisibleButAreNotSemanticWitnesses() throws Exception {
        var fixture = conditionalFixture(TRUE);
        var original = fixture.binding().matches().getFirst();
        var failed = new BindingEvidence.Match(original.dependency(), original.candidate(), original.lane(),
                original.rawType(), original.typeLookup(), original.strictGeneric(), original.fallbackGeneric(),
                original.qualifierMatch(), BindingEvidence.Knowledge.ERROR, original.evidence());
        var plan = new InjectionBindingPlan(fixture.binding().registrationPlan(), fixture.binding().dependencies(),
                fixture.binding().definitions(), List.of(failed), fixture.binding().groups(),
                fixture.binding().obligations(), fixture.binding().environment(), fixture.binding().limits());
        var result = TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(
                plan, fixture.model(), fixture.source().lowering().semantics(), fixture.baseline(),
                ExhaustiveConfigurationReasoner.INSTANCE, TruthRegionEvaluation.Limits.conservative(), ARTIFACT));

        var failedWorlds = result.worlds().stream().filter(w ->
                w.operationalStatus() == TruthRegionEvaluation.OperationalStatus.ERROR).toList();
        assertFalse(failedWorlds.isEmpty());
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("OPERATIONAL_FAILURE")));
        assertTrue(result.witnesses().stream().noneMatch(witness -> failedWorlds.stream().anyMatch(world ->
                world.assignment().equals(witness.fullAssignment()))));
    }

    @Test void inconsistentReasonerOutputIsRejectedByIndependentWitnessReplay() throws Exception {
        var fixture = conditionalFixture(TRUE);
        var outsideSpace = new ConfigurationAssignment(Map.of(
                new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "outside-space"),
                FiniteDomain.Value.bool(true)), fixture.baseline().evidence());
        ConfigurationReasoner inconsistent = new ConfigurationReasoner() {
            @Override public VersionedIdentifier policy() {
                return new VersionedIdentifier("spring.configuration-reasoner", "adversarial-test-v1");
            }
            @Override public FiniteConfigurationEvaluation.Result enumerate(ConditionModel model,
                    ConditionExpression.Semantics semantics, FiniteConfigurationEvaluation.Limits limits) {
                var honest = ExhaustiveConfigurationReasoner.INSTANCE.enumerate(model, semantics, limits);
                return new FiniteConfigurationEvaluation.Result(honest.inputIdentity(), List.of(outsideSpace),
                        List.of(honest.evaluations().getFirst()), honest.capabilityGaps(),
                        new FiniteConfigurationEvaluation.Coverage(
                                new ConfigurationSpace.CartesianSize(BigInteger.ONE, true), 1, 1, 0, 0, true));
            }
            @Override public Satisfiability satisfiability(Region region) {
                return ExhaustiveConfigurationReasoner.INSTANCE.satisfiability(region);
            }
            @Override public Optional<WorldKey> witness(Region region) {
                return ExhaustiveConfigurationReasoner.INSTANCE.witness(region);
            }
            @Override public LogicalValue implies(Region antecedent, Region consequent) {
                return ExhaustiveConfigurationReasoner.INSTANCE.implies(antecedent, consequent);
            }
            @Override public LogicalValue equivalent(Region left, Region right) {
                return ExhaustiveConfigurationReasoner.INSTANCE.equivalent(left, right);
            }
        };
        var result = TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(
                fixture.binding(), fixture.model(), fixture.source().lowering().semantics(), outsideSpace,
                inconsistent, TruthRegionEvaluation.Limits.conservative(), ARTIFACT));

        assertTrue(result.replays().stream().anyMatch(replay -> !replay.valid()));
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("WITNESS_REPLAY_FAILED")));
        var rejected = result.replays().stream().filter(replay -> !replay.valid())
                .map(TruthRegionEvaluation.WitnessReplay::witness).collect(java.util.stream.Collectors.toSet());
        assertTrue(result.witnesses().stream().noneMatch(witness -> rejected.contains(witness.identity())),
                "a failed replay is diagnostic evidence, not a published revalidated witness");
    }

    @Test void resultAffectingLimitsAndEvaluatorArtifactAreIdentityInputs() throws Exception {
        var fixture = conditionalFixture(TRUE);
        var normal = evaluate(fixture);
        assertEquals(CanonicalJson.write(normal.canonicalForm()), CanonicalJson.write(evaluate(fixture).canonicalForm()));
        var changedLimits = new TruthRegionEvaluation.Limits(
                TruthRegionEvaluation.Limits.conservative().enumeration(), 99, 1000, 100);
        var otherLimits = TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(
                fixture.binding(), fixture.model(), fixture.source().lowering().semantics(), fixture.baseline(),
                ExhaustiveConfigurationReasoner.INSTANCE, changedLimits, ARTIFACT));
        var otherArtifact = TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(
                fixture.binding(), fixture.model(), fixture.source().lowering().semantics(), fixture.baseline(),
                ExhaustiveConfigurationReasoner.INSTANCE, TruthRegionEvaluation.Limits.conservative(),
                ContentDigest.sha256Utf8("different-evaluator")));
        assertNotEquals(normal.semanticsContextIdentity(), otherLimits.semanticsContextIdentity());
        assertNotEquals(normal.identity(), otherArtifact.identity());
    }

    @Test void factLimitIsClosedAndExplicitRatherThanSilentlyTruncated() throws Exception {
        var fixture = conditionalFixture(TRUE);
        var normal = TruthRegionEvaluation.Limits.conservative();
        var limited = new TruthRegionEvaluation.Limits(normal.enumeration(), 1, normal.maxRegionCells(),
                normal.maxWitnessReplays());
        var result = TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(
                fixture.binding(), fixture.model(), fixture.source().lowering().semantics(), fixture.baseline(),
                ExhaustiveConfigurationReasoner.INSTANCE, limited, ARTIFACT));
        assertEquals(3, result.coverage().inputFacts());
        assertEquals(1, result.coverage().emittedRegions());
        assertEquals(2, result.coverage().withheldFacts());
        assertFalse(result.coverage().complete());
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("FACT_LIMIT")));
    }

    @Test void contextualBindingOwnerParticipatesInFactIdentity() throws Exception {
        var fixture = conditionalFixture(TRUE);
        var dependency = fixture.binding().dependencies().getFirst();
        var target = fixture.event().candidate().orElseThrow().identity();
        var external = ConditionalFactKey.selectedBinding(dependency.point().identity(), Optional.empty(), target);
        var owned = ConditionalFactKey.selectedBinding(dependency.point().identity(), Optional.of(target), target);
        assertNotEquals(external.identity(), owned.identity());
        assertNotEquals(external.canonicalForm(), owned.canonicalForm());
    }

    @Test void witnessBudgetNeverInventsMissingReplayEvidence() throws Exception {
        var fixture = conditionalFixture(TRUE);
        var normal = TruthRegionEvaluation.Limits.conservative();
        var limited = new TruthRegionEvaluation.Limits(normal.enumeration(), normal.maxFacts(),
                normal.maxRegionCells(), 1);
        var result = TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(
                fixture.binding(), fixture.model(), fixture.source().lowering().semantics(), fixture.baseline(),
                ExhaustiveConfigurationReasoner.INSTANCE, limited, ARTIFACT));
        assertEquals(1, result.replays().size());
        assertTrue(result.replays().getFirst().valid());
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("WITNESS_LIMIT")));
        assertTrue(result.regions().stream().allMatch(r -> r.classification() == TruthRegionEvaluation.Classification.MAY));
    }
}
