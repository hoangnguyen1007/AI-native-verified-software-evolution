package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.spring.binding.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.evolution.analysis.spring.condition.BeanRegistrationTransitionsTest.*;
import static com.evolution.analysis.spring.condition.InjectionBindingsTest.*;
import static com.evolution.analysis.spring.condition.RegistrationDiscoveryTest.*;
import static com.evolution.analysis.spring.condition.LogicalValue.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exhaustive, authored interaction oracle. This is normalized semantic replay, not runtime replay. */
class M9InteractionTest {
    @Test void profilePropertyOrderQualifierAndPrimaryFallbackHaveClosedWorldOutcomes() throws Exception {
        var source = ConditionLoweringTest.fixture(List.of(
                new ConditionLoweringTest.Spec(ConditionLoweringTest.PROFILE, "@Profile(\"dev\")"),
                new ConditionLoweringTest.Spec(ConditionLoweringTest.PROPERTY, "@ConditionalOnProperty(name=\"enabled\",havingValue=\"true\")")));
        var lowering = ConditionLoweringTest.lower(source); var evidence = source.evidence();
        var profile = new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev");
        var property = new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, "enabled");
        var space = new ConfigurationSpace(source.build(), new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,
                List.of(), List.of(), ContentDigest.sha256Utf8("m9-authored-interaction")), Optional.empty(),
                List.of(new FiniteDomain(profile,List.of(FiniteDomain.Value.bool(false),FiniteDomain.Value.bool(true)),evidence),
                        new FiniteDomain(property,List.of(FiniteDomain.Value.missing(),FiniteDomain.Value.exact("false"),FiniteDomain.Value.exact("true")),evidence)),
                List.of(), new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(),evidence),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of(),Map.of(),List.of(),evidence),
                ConfigurationSpaceTest.VERSION,new ConfigurationSpace.FeasibilityPolicy(ConfigurationSpaceTest.VERSION,ConfigurationSpace.Limits.conservative(),evidence));
        var f = new RegistrationDiscoveryTest.Fixture(source, lowering, space);
        var raw = event(f,"configured",RegistrationEvent.Kind.CONFIGURATION,null,null);
        var configured = copy(raw,raw.candidate(),lowering.occurrences().stream()
                .map(o -> new RegistrationEvent.ConditionUse(o.identity(),RegistrationEvent.RequiredPhase.ORDINARY,evidence)).toList(),
                RegistrationEvent.Completeness.COMPLETE,RegistrationEvent.Completeness.COMPLETE,evidence);
        var fallback = event(f,"fallback",RegistrationEvent.Kind.CONFIGURATION,null,null);
        var model = ConditionModel.create(space,lowering.occurrences()); int checked = 0;
        for (boolean reverse : List.of(false,true)) {
            var events = reverse ? List.of(fallback,configured) : List.of(configured,fallback);
            var discovery = plan(f,events,List.of(before(f,events.getFirst(),events.getLast())),RegistrationPlan.Limits.conservative());
            var steps = events.stream().map(e -> regStep("reg-"+e.eventSlot(),e,Optional.empty(),evidence)).toList();
            var registration = rp(f,discovery,steps,steps.stream().map(BeanRegistrationPlan.Step::key).toList());
            var fixture = new InjectionBindingsTest.Fixture(f,events,registration);
            var unqualified = dependency(fixture,InjectionBindingPlan.Shape.SINGLE,InjectionBindingPlan.Name.absent(),InjectionBindingPlan.Required.OPTIONAL);
            var dependency = change(unqualified,unqualified.shape(),unqualified.mode(),unqualified.required(),
                    unqualified.dependencyName(),unqualified.suggestedName(),TRUE,true,false,false,false,
                    InjectionBindingPlan.Normalization.COMPLETE,Optional.empty());
            for (boolean dev : List.of(false,true)) for (var enabled : List.of(FiniteDomain.Value.missing(),FiniteDomain.Value.exact("false"),FiniteDomain.Value.exact("true")))
                for (boolean configuredQualifier : List.of(false,true)) for (boolean fallbackQualifier : List.of(false,true)) for (boolean fallbackPrimary : List.of(false,true)) {
                    var definitions = List.of(definition(fixture,"configured",false,false,null),definition(fixture,"fallback",fallbackPrimary,true,null));
                    var matches = List.of(match(fixture,dependency,"configured",BindingEvidence.Lane.DIRECT,TRUE,TRUE,TRUE,configuredQualifier?TRUE:FALSE),
                            match(fixture,dependency,"fallback",BindingEvidence.Lane.DIRECT,TRUE,TRUE,TRUE,fallbackQualifier?TRUE:FALSE));
                    var binding = bindingPlan(fixture,List.of(dependency),definitions,matches);
                    var assignment = new ConfigurationAssignment(Map.of(profile,FiniteDomain.Value.bool(dev),property,enabled),evidence);
                    var actual = InjectionBindings.evaluate(binding,model,assignment,ExogenousConditionEvaluator.Limits.conservative());
                    boolean hasConfigured = dev && enabled.equals(FiniteDomain.Value.exact("true")) && configuredQualifier;
                    String expected = fallbackQualifier && fallbackPrimary ? "fallback" : hasConfigured ? "configured" : fallbackQualifier ? "fallback" : null;
                    if (expected == null) outcome(actual,InjectionBindings.Outcome.ABSENT_OPTIONAL); else selected(actual,expected);
                    assertTrue(actual.issues().isEmpty());
                    assertTrue(actual.registration().issues().isEmpty());
                    assertEquals(Set.of("REGISTRATION_NOT_EVALUATED","CANDIDATE_TYPES_UNRESOLVED"),
                            actual.capabilityGaps().stream().map(g -> g.reasonCode()).collect(java.util.stream.Collectors.toSet()),
                            "Discovery-only gaps remain inspectable after normalized registration/binding");
                    checked++;
                }
        }
        assertEquals(96,checked,"Every assignment/order/qualifier/primary cell is retained");
    }
}
