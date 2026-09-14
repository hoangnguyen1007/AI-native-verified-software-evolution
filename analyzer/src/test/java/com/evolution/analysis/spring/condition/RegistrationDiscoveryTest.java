package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.registration.RegistrationEvent.*;

/** Authored normalized plans: membership is an explicit provider input, not inferred from names. */
class RegistrationDiscoveryTest {
    record Fixture(ConditionLoweringTest.Fixture source, ConditionEvidenceLowering.Result lowering, ConfigurationSpace space) {}
    static Fixture fixture(String annotation) throws Exception {
        String type = annotation.startsWith("@Profile") ? ConditionLoweringTest.PROFILE : annotation.startsWith("@ConditionalOnMissingBean")
                ? "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean" : "org.springframework.context.annotation.Conditional";
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
        return new Fixture(source, lowering, space);
    }
    static RegistrationEvent event(Fixture f, String slot, Kind kind, RegistrationEvent parent, RequiredPhase condition) {
        var producer = new BeanProducer(f.source.build().identity(), "authored-root", f.source.evidence(),
                kind == Kind.BEAN_METHOD ? BeanProducer.Kind.BEAN_METHOD : BeanProducer.Kind.CONFIGURATION_CLASS, slot, slot);
        var candidate = new BeanDefinitionCandidate(producer, "definition", BeanDefinitionCandidate.Names.exact(slot), List.of());
        Phase phase = switch (kind) {
            case CONFIGURATION, DIRECT_IMPORT, IMPORT_SELECTOR, ENVIRONMENT_MUTATION, BEAN_METHOD -> Phase.CONFIGURATION_PARSE;
            case COMPONENT, COMPONENT_SCAN -> Phase.COMPONENT_SCAN;
            case DEFERRED_IMPORT, AUTO_CONFIGURATION -> Phase.DEFERRED_IMPORT_SELECTION;
            case IMPORT_REGISTRAR, XML_READER -> Phase.DEFINITION_READING;
            case REGISTRY_POST_PROCESSOR -> Phase.REGISTRY_CALLBACK;
        };
        return new RegistrationEvent(f.source.build().identity(), f.lowering.semantics(), ContentDigest.sha256Utf8(slot),
                parent == null ? List.of() : parent.invocationPath(), phase, slot, "authored-root", kind,
                kind == Kind.BEAN_METHOD || kind == Kind.COMPONENT_SCAN ? ConditionSite.DISCOVERY_ONLY :
                        kind == Kind.COMPONENT ? ConditionSite.REGISTER_BEAN : ConditionSite.PARSE_CONFIGURATION,
                Optional.ofNullable(parent).map(RegistrationEvent::identity), Optional.of(candidate),
                condition == null ? List.of() : List.of(new ConditionUse(f.lowering.occurrences().getFirst().identity(), condition, f.source.evidence())),
                Completeness.COMPLETE, Completeness.COMPLETE, List.of(), f.source.evidence());
    }
    static RegistrationPlan plan(Fixture f, List<RegistrationEvent> events, List<RegistrationPlan.Precedence> order, RegistrationPlan.Limits limits) {
        return RegistrationPlan.create(f.source.build(), f.source.inventory(), f.lowering, events, order,
                new RegistrationPlan.Container("authored-root", f.source.evidence()), List.of(), RegistrationPlan.OverridePolicy.FORBID, limits);
    }
    static RegistrationPlan plan(Fixture f, List<RegistrationEvent> events) {
        return plan(f, events, List.of(), RegistrationPlan.Limits.conservative());
    }
    static DiscoveryTransitions.Result run(Fixture f, RegistrationPlan plan, boolean dev) {
        return DiscoveryTransitions.evaluate(plan, ConditionModel.create(f.space, f.lowering.occurrences()),
                new ConfigurationAssignment(Map.of(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"), FiniteDomain.Value.bool(dev)), f.source.evidence()),
                ExogenousConditionEvaluator.Limits.conservative());
    }
    static DiscoveryTransitions.Outcome outcome(DiscoveryTransitions.Result result, RegistrationEvent event) {
        return result.rows().stream().filter(r -> r.event().equals(event.identity())).findFirst().orElseThrow().outcome();
    }
    static RegistrationEvent copy(RegistrationEvent e, Optional<BeanDefinitionCandidate> candidate, List<ConditionUse> conditions,
                                   Completeness membership, Completeness metadata, ConditionEvidence evidence) {
        return new RegistrationEvent(e.buildContextIdentity(), e.frameworkSemantics(), e.triggerOccurrenceIdentity(), e.parentInvocationPath(), e.phase(),
                e.eventSlot(), e.containerKey(), e.kind(), e.conditionSite(), e.parent(), candidate, conditions, membership, metadata, e.obligations(), evidence);
    }
    static RegistrationPlan.Precedence before(Fixture f, RegistrationEvent first, RegistrationEvent second) {
        return new RegistrationPlan.Precedence(first.identity(), second.identity(), RegistrationPlan.OrderDomain.EXPLICIT_SEQUENCE, f.source.evidence());
    }
    @Test void falseParseGuardSuppressesImportSubtreeAndDoesNotInvokeChildCondition() throws Exception {
        var f = fixture("@Profile(\"dev\")");
        var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var imported = event(f, "imported", Kind.DIRECT_IMPORT, root, null);
        var result = run(f, plan(f, List.of(root, imported)), false);
        assertEquals(2, result.rows().size(), "Every planned event must retain its outcome");
        assertEquals(DiscoveryTransitions.Outcome.SKIPPED, outcome(result, root));
        assertEquals(DiscoveryTransitions.Outcome.SKIPPED, outcome(result, imported));
    }
    @Test void registerOnlyConditionDoesNotPreventParsingOrDiscoveryOfBeanMethod() throws Exception {
        var f = fixture("@ConditionalOnMissingBean(Token.class)");
        var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.REGISTER_BEAN);
        var bean = event(f, "bean", Kind.BEAN_METHOD, root, RequiredPhase.REGISTER_BEAN);
        var result = run(f, plan(f, List.of(bean, root)), false);
        assertEquals(DiscoveryTransitions.Outcome.DISCOVERED, outcome(result, root));
        assertEquals(DiscoveryTransitions.Outcome.DISCOVERED, outcome(result, bean));
    }
    @Test void unknownCustomParseConditionTaintsAllDescendants() throws Exception {
        var f = fixture("@Conditional(Custom.class)");
        var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var child = event(f, "child", Kind.DIRECT_IMPORT, root, null);
        var result = run(f, plan(f, List.of(root, child)), true);
        assertEquals(DiscoveryTransitions.Outcome.UNKNOWN, outcome(result, root));
        assertEquals(DiscoveryTransitions.Outcome.UNKNOWN, outcome(result, child));
    }
    @Test void absentOrderEvidenceNeverBecomesALexicalExecutionOrder() throws Exception {
        var f = fixture("@Profile(\"dev\")");
        var a = event(f, "a", Kind.CONFIGURATION, null, null);
        var b = event(f, "b", Kind.CONFIGURATION, null, null);
        var plan = plan(f, List.of(b, a));
        assertEquals(RegistrationPlan.OrderStatus.MISSING_EVIDENCE, plan.orderStatus());
        assertTrue(plan.establishedOrderPrefix().isEmpty());
        var result = run(f, plan, true);
        assertEquals(DiscoveryTransitions.Outcome.UNKNOWN, outcome(result, a));
        assertEquals(DiscoveryTransitions.Outcome.UNKNOWN, outcome(result, b));
    }
    @Test void registerConditionOnAnImporterDoesNotBecomeAConditionOnAnUnrelatedImportedScan() throws Exception {
        var f = fixture("@ConditionalOnMissingBean(Token.class)");
        var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.REGISTER_BEAN);
        var imported = event(f, "imported", Kind.DIRECT_IMPORT, root, null);
        var scan = event(f, "scan", Kind.COMPONENT_SCAN, imported, null);
        assertEquals(DiscoveryTransitions.Outcome.DISCOVERED, outcome(run(f, plan(f, List.of(root, imported, scan)), true), scan));
    }
    @Test void definiteScanRegisterPhaseErrorStopsLaterEventsWithoutCallingThem() throws Exception {
        var f = fixture("@ConditionalOnMissingBean(Token.class)");
        var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.REGISTER_BEAN);
        var scan = event(f, "scan", Kind.COMPONENT_SCAN, root, null);
        var child = event(f, "component", Kind.COMPONENT, scan, null);
        var result = run(f, plan(f, List.of(root, scan, child)), true);
        assertEquals(DiscoveryTransitions.Outcome.ERROR, outcome(result, scan));
        assertEquals(DiscoveryTransitions.Outcome.NOT_REACHED, outcome(result, child));
        assertEquals(2, result.transitions().size(), "No transition may claim that the child ran after a container error");
        assertTrue(result.rows().stream().filter(r -> r.event().equals(child.identity())).findFirst().orElseThrow().transition().isEmpty());
    }
    @Test void exactTransitionChainAndCandidateDiscoveryAreSeparateFromRegistration() throws Exception {
        var f = fixture("@Profile(\"dev\")");
        var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var child = event(f, "child", Kind.DIRECT_IMPORT, root, null);
        var result = run(f, plan(f, List.of(root, child)), true);
        assertEquals(2, result.transitions().size()); assertEquals(3, result.states().size());
        for (int i = 0; i < 2; i++) {
            assertEquals(result.states().get(i).identity(), result.transitions().get(i).inputStateIdentity());
            assertEquals(result.states().get(i + 1).identity(), result.transitions().get(i).outputStateIdentity());
        }
        assertTrue(result.candidates().stream().allMatch(c -> c.discovery() == DiscoveryTransitions.DiscoveryStatus.DISCOVERED
                && c.registration() == DiscoveryTransitions.RegistrationStatus.NOT_EVALUATED));
        assertEquals(result.identity(), run(f, plan(f, List.of(child, root)), true).identity());
    }
    @Test void limitsRetainEveryEventAndBindDifferentContextIdentities() throws Exception {
        var f = fixture("@Profile(\"dev\")");
        var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var child = event(f, "child", Kind.DIRECT_IMPORT, root, null);
        var normal = run(f, plan(f, List.of(root, child)), true);
        for (var bounds : List.of(new RegistrationPlan.Limits(1, 10, 10, 10), new RegistrationPlan.Limits(10, 10, 10, 1),
                new RegistrationPlan.Limits(10, 10, 10, 10, 1))) {
            var result = run(f, plan(f, List.of(root, child), List.of(), bounds), true);
            assertEquals(2, result.coverage().inputEvents()); assertFalse(result.discoveryClosed());
            assertNotEquals(normal.semanticsContextIdentity(), result.semanticsContextIdentity());
            assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("DISCOVERY_LIMIT")));
        }
    }
    @Test void independentlyReconstructedConditionObjectsAreAcceptedByContentIdentity() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var anotherLowering = ConditionLoweringTest.lower(f.source);
        var result = DiscoveryTransitions.evaluate(plan(f, List.of(root)), ConditionModel.create(f.space, anotherLowering.occurrences()),
                new ConfigurationAssignment(Map.of(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"), FiniteDomain.Value.bool(true)), f.source.evidence()),
                ExogenousConditionEvaluator.Limits.conservative());
        assertEquals(DiscoveryTransitions.Outcome.DISCOVERED, outcome(result, root));
    }
    @Test void unknownMembershipAndMissingConditionMetadataCannotCertifyDiscovery() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var root = event(f, "root", Kind.CONFIGURATION, null, null);
        for (var changed : List.of(copy(root, root.candidate(), root.conditions(), Completeness.UNKNOWN, Completeness.COMPLETE, root.evidence()),
                copy(root, root.candidate(), root.conditions(), Completeness.COMPLETE, Completeness.UNKNOWN, root.evidence()))) {
            var result = run(f, plan(f, List.of(changed)), true);
            assertEquals(DiscoveryTransitions.Outcome.UNKNOWN, outcome(result, changed)); assertFalse(result.capabilityGaps().isEmpty());
        }
    }
    @Test void registrarAndSelectorCallbacksRetainTypedGapsAndTaintLaterConditionInputs() throws Exception {
        var f = fixture("@Profile(\"dev\")");
        for (var kind : List.of(Kind.IMPORT_SELECTOR, Kind.IMPORT_REGISTRAR, Kind.REGISTRY_POST_PROCESSOR, Kind.ENVIRONMENT_MUTATION, Kind.XML_READER)) {
            var opaque = event(f, "opaque", kind, null, null);
            var later = event(f, "later", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
            var result = run(f, plan(f, List.of(opaque, later), List.of(before(f, opaque, later)), RegistrationPlan.Limits.conservative()), true);
            assertEquals(DiscoveryTransitions.Outcome.UNKNOWN, outcome(result, opaque));
            assertEquals(DiscoveryTransitions.Outcome.UNKNOWN, outcome(result, later));
            assertFalse(result.discoveryClosed());
        }
    }
    @Test void skippedParentDoesNotInvokeOpaqueChildOrTaintAnUnrelatedConfiguration() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var opaque = event(f, "opaque", Kind.IMPORT_SELECTOR, root, null);
        var later = event(f, "later", Kind.CONFIGURATION, null, null);
        var result = run(f, plan(f, List.of(root, opaque, later), List.of(before(f, opaque, later)), RegistrationPlan.Limits.conservative()), false);
        assertEquals(DiscoveryTransitions.Outcome.SKIPPED, outcome(result, opaque));
        assertEquals(DiscoveryTransitions.Outcome.DISCOVERED, outcome(result, later));
        assertTrue(result.issues().stream().noneMatch(i -> i.reason() == RegistrationProcessing.Reason.UNKNOWN_IMPORT_SELECTOR));
    }
    @Test void multipleDiscoveryPathsRetainHistoryWithoutDuplicatingACanonicalCandidate() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var a = event(f, "a", Kind.CONFIGURATION, null, RequiredPhase.ORDINARY);
        var pathA = event(f, "pathA", Kind.DIRECT_IMPORT, a, null);
        var b = event(f, "b", Kind.CONFIGURATION, null, null); var originalB = event(f, "pathB", Kind.DIRECT_IMPORT, b, null);
        var pathB = copy(originalB, pathA.candidate(), List.of(), Completeness.COMPLETE, Completeness.COMPLETE, originalB.evidence());
        var result = run(f, plan(f, List.of(a, pathA, b, pathB), List.of(before(f, pathA, b)), RegistrationPlan.Limits.conservative()), false);
        var shared = result.candidates().stream().filter(c -> c.candidate().equals(pathA.candidate().orElseThrow().identity())).findFirst().orElseThrow();
        assertEquals(2, shared.discoveryEvents().size()); assertEquals(DiscoveryTransitions.DiscoveryStatus.DISCOVERED, shared.discovery());
        assertEquals(DiscoveryTransitions.Outcome.SKIPPED, outcome(result, pathA)); assertEquals(DiscoveryTransitions.Outcome.DISCOVERED, outcome(result, pathB));
    }
    @Test void actualImportCycleHasAnErrorOutcomeAndCannotBeHiddenByCandidateDeduplication() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var root = event(f, "root", Kind.CONFIGURATION, null, null);
        var importA = event(f, "importA", Kind.DIRECT_IMPORT, root, null);
        var repeated = copy(importA, root.candidate(), List.of(), Completeness.COMPLETE, Completeness.COMPLETE, importA.evidence());
        assertEquals(DiscoveryTransitions.Outcome.ERROR, outcome(run(f, plan(f, List.of(root, repeated)), true), repeated));
    }
    @Test void unknownPhaseIsNotTreatedAsAnOrdinaryOrRegisterOnlyCondition() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.UNKNOWN);
        assertEquals(DiscoveryTransitions.Outcome.UNKNOWN, outcome(run(f, plan(f, List.of(root)), false), root));
    }
    @Test void ordinaryBeanMethodConditionIsRecordedDuringParseButEvaluatedOnlyAtRegistration() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var root = event(f, "root", Kind.CONFIGURATION, null, null);
        var bean = event(f, "bean", Kind.BEAN_METHOD, root, RequiredPhase.ORDINARY);
        var result = run(f, plan(f, List.of(root, bean)), false);
        assertEquals(Phase.CONFIGURATION_PARSE, bean.phase());
        assertEquals(DiscoveryTransitions.Outcome.DISCOVERED, outcome(result, bean));
        assertEquals(DiscoveryTransitions.InvocationStatus.DEFERRED, result.rows().stream().filter(r -> r.event().equals(bean.identity())).findFirst().orElseThrow().conditions().getFirst().status());
    }
    @Test void aPlainScannedComponentEvaluatesOrdinaryConditionsAtItsRegisterConditionSite() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var component = event(f, "component", Kind.COMPONENT, null, RequiredPhase.ORDINARY);
        assertEquals(ConditionSite.REGISTER_BEAN, component.conditionSite());
        assertEquals(DiscoveryTransitions.Outcome.SKIPPED, outcome(run(f, plan(f, List.of(component)), false), component));
        assertEquals(DiscoveryTransitions.Outcome.DISCOVERED, outcome(run(f, plan(f, List.of(component)), true), component));
    }
    @Test void endogenousConditionAtARegisterSiteCannotBeEvaluatedAgainstTheFinalCandidateInventory() throws Exception {
        var f = fixture("@ConditionalOnMissingBean(Token.class)"); var component = event(f, "component", Kind.COMPONENT, null, RequiredPhase.REGISTER_BEAN);
        var result = run(f, plan(f, List.of(component)), true);
        assertEquals(DiscoveryTransitions.Outcome.UNKNOWN, outcome(result, component));
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("BEAN_STATE_REQUIRED")));
    }
}
