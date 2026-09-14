package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.spring.registration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.condition.RegistrationDiscoveryTest.*;
import static com.evolution.analysis.spring.registration.RegistrationEvent.*;

class RegistrationPlanTest {
    @Test void unrelatedOrderDomainCannotEstablishARegistrationPrefix() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var a = event(f, "a", Kind.CONFIGURATION, null, null); var b = event(f, "b", Kind.CONFIGURATION, null, null);
        var wrong = new RegistrationPlan.Precedence(a.identity(), b.identity(), RegistrationPlan.OrderDomain.AUTO_CONFIGURATION, f.source().evidence());
        var plan = plan(f, List.of(a, b), List.of(wrong), RegistrationPlan.Limits.conservative());
        assertTrue(plan.establishedOrderPrefix().isEmpty(), "An invalid auto-configuration edge cannot order ordinary configurations");
        assertEquals(DiscoveryTransitions.Outcome.UNKNOWN, outcome(run(f, plan, true), a));
    }
    @Test void sourceMismatchedOrderingProofDoesNotInventSequence() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var a = event(f, "a", Kind.CONFIGURATION, null, null); var b = event(f, "b", Kind.CONFIGURATION, null, null);
        var original = (ConditionEvidence.Source) f.source().evidence();
        var stale = new ConditionEvidence.Source(original.document(), ContentDigest.sha256Utf8("stale"), original.span(), original.ordinal());
        var edge = new RegistrationPlan.Precedence(a.identity(), b.identity(), RegistrationPlan.OrderDomain.EXPLICIT_SEQUENCE, stale);
        var plan = plan(f, List.of(a, b), List.of(edge), RegistrationPlan.Limits.conservative());
        assertTrue(plan.establishedOrderPrefix().isEmpty());
        assertTrue(plan.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("SOURCE_EVIDENCE_MISMATCH")));
    }
    @Test void cyclicEdgesRemainVisibleAndEveryBlockedEventHasAnOutcome() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var a = event(f, "a", Kind.CONFIGURATION, null, null); var b = event(f, "b", Kind.CONFIGURATION, null, null);
        var plan = plan(f, List.of(a, b), List.of(before(f, a, b), before(f, b, a)), RegistrationPlan.Limits.conservative());
        assertEquals(RegistrationPlan.OrderStatus.CYCLIC, plan.orderStatus()); assertEquals(2, plan.precedence().size());
        assertEquals(2, run(f, plan, true).coverage().unknown());
    }
    @Test void exactPhaseProvenanceCannotMislabelProfileAsRegisterOnly() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var root = event(f, "root", Kind.CONFIGURATION, null, RequiredPhase.REGISTER_BEAN);
        assertEquals(DiscoveryTransitions.Outcome.UNKNOWN, outcome(run(f, plan(f, List.of(root)), false), root));
    }
    @Test void canonicalIdentityDoesNotDependOnListEncounterOrderButDoesBindPrecedenceAndOverridePolicy() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var a = event(f, "A\u0301", Kind.CONFIGURATION, null, null); var b = event(f, "\u00c1", Kind.CONFIGURATION, null, null);
        var left = plan(f, List.of(a, b), List.of(before(f, a, b)), RegistrationPlan.Limits.conservative());
        var permuted = plan(f, List.of(b, a), List.of(before(f, a, b)), RegistrationPlan.Limits.conservative());
        assertEquals(CanonicalJson.write(left.canonicalForm()), CanonicalJson.write(permuted.canonicalForm()));
        assertNotEquals(a.candidate().orElseThrow().identity(), b.candidate().orElseThrow().identity());
        assertNotEquals(left.identity(), plan(f, List.of(a, b), List.of(before(f, b, a)), RegistrationPlan.Limits.conservative()).identity());
        var allow = RegistrationPlan.create(f.source().build(), f.source().inventory(), f.lowering(), left.events(), left.precedence(), left.container(),
                List.of(), RegistrationPlan.OverridePolicy.ALLOW, left.limits());
        assertNotEquals(left.identity(), allow.identity());
    }
    @Test void duplicateInvocationIdentityAndForeignObligationsAreInvalidApiInputs() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var a = event(f, "a", Kind.CONFIGURATION, null, null);
        assertThrows(IllegalArgumentException.class, () -> plan(f, List.of(a, a)));
        var foreign = new RegistrationEvent(a.buildContextIdentity(), a.frameworkSemantics(), a.triggerOccurrenceIdentity(), a.parentInvocationPath(), a.phase(),
                a.eventSlot(), a.containerKey(), a.kind(), a.conditionSite(), a.parent(), a.candidate(), a.conditions(), a.membership(), a.conditionMetadata(),
                List.of(ContentDigest.sha256Utf8("not-in-inventory")), a.evidence());
        assertThrows(IllegalArgumentException.class, () -> plan(f, List.of(foreign)));
    }
    @Test void missingParentAndSourceSpanRemainTypedGaps() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var a = event(f, "a", Kind.CONFIGURATION, null, null); var b = event(f, "b", Kind.DIRECT_IMPORT, a, null);
        var original = (ConditionEvidence.Source) b.evidence();
        var missing = copy(b, b.candidate(), b.conditions(), b.membership(), b.conditionMetadata(), new ConditionEvidence.Source(original.document(), original.rawDigest(), Optional.empty(), 0));
        var plan = plan(f, List.of(missing));
        assertTrue(plan.issues().stream().anyMatch(i -> i.reason() == RegistrationProcessing.Reason.PARENT_REFERENCE_MISSING));
        assertTrue(plan.issues().stream().anyMatch(i -> i.reason() == RegistrationProcessing.Reason.MISSING_SOURCE_SPAN));
        assertEquals(1, run(f, plan, true).coverage().unknown());
    }
    @Test void candidateNamesAreContainerScopedAndUnresolvedNamesNeverAcquireDefaults() throws Exception {
        var f = fixture("@Profile(\"dev\")"); var event = event(f, "root", Kind.CONFIGURATION, null, null); var original = event.candidate().orElseThrow();
        var unresolved = new BeanDefinitionCandidate(original.producer(), original.definitionSlot(), BeanDefinitionCandidate.Names.unresolved(), List.of());
        var otherProducer = new BeanProducer(original.producer().buildContextIdentity(), "another-root", original.producer().declarationEvidenceKey(),
                original.producer().producerKind(), original.producer().declarationSlot(), original.producer().discoveryPathKey());
        assertNotEquals(original.identity(), new BeanDefinitionCandidate(otherProducer, original.definitionSlot(), original.declaredNameKey(), List.of()).identity());
        var changed = copy(event, Optional.of(unresolved), List.of(), Completeness.COMPLETE, Completeness.COMPLETE, event.evidence());
        var result = run(f, plan(f, List.of(changed)), true);
        assertTrue(unresolved.declaredNameKey().primary().isEmpty());
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("CANDIDATE_NAMES_UNRESOLVED")));
        assertThrows(IllegalArgumentException.class, () -> BeanDefinitionCandidate.Names.exact("\uD800"));
    }
}
