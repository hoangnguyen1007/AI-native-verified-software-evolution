package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.spring.binding.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.condition.RegistrationDiscoveryTest.*;
import static com.evolution.analysis.spring.condition.InjectionBindingsTest.*;
import static com.evolution.analysis.spring.condition.BeanRegistrationTransitionsTest.*;
import static com.evolution.analysis.spring.condition.LogicalValue.TRUE;
import static com.evolution.analysis.spring.condition.LogicalValue.FALSE;
import static com.evolution.analysis.spring.registration.RegistrationEvent.Completeness.COMPLETE;
import static com.evolution.analysis.spring.binding.InjectionBindingPlan.*;
import static com.evolution.analysis.spring.binding.InjectionBindings.Outcome.*;

class InjectionBindingBoundaryTest {
    static BeanRegistrationPlan registration(BeanRegistrationPlan p, List<BeanRegistrationPlan.Step> steps, List<String> order,
                                             RegistrationEvent.Completeness complete, RegistrationEvent.Completeness noParent,
                                             List<BeanRegistrationEvidence.Definition> defs) {
        return new BeanRegistrationPlan(p.discoveryPlan(), steps, order, complete, p.orderEvidence(), p.initialRegistry(),
                noParent, p.closureEvidence(), defs, p.queries(), p.queryTypes(), p.limits());
    }
    static InjectionBindingPlan withRegistration(InjectionBindingPlan p, BeanRegistrationPlan registration) {
        return new InjectionBindingPlan(registration,p.dependencies(),p.definitions(),p.matches(),p.groups(),p.environment(),p.limits());
    }
    @Test void unresolvedRegistrationCannotCertifyBindingEvenAtKnownPrefix() throws Exception {
        var f=setup("a","b"); var d=dependency(f,Shape.SINGLE,Name.of("a"),Required.REQUIRED); var p=bindingPlan(f,d,ordinary(f));
        var incomplete=registration(f.registration(),f.registration().steps(),List.of("reg-a"),RegistrationEvent.Completeness.UNKNOWN,COMPLETE,List.of());
        var r=resolve(f,withRegistration(p,incomplete)); outcome(r,UNKNOWN); reason(r,BindingProcessing.Reason.REGISTRATION_INCOMPLETE);
    }
    @Test void inactiveOwnerProducesNoInjectionBinding() throws Exception {
        var f=setup("a"); var base=dependency(f,Shape.SINGLE,Name.absent(),Required.REQUIRED);
        var d=change(base,Shape.SINGLE,Mode.AUTOWIRE,Required.REQUIRED,Name.absent(),Name.absent(),FALSE,true,false,false,false,Normalization.COMPLETE,Optional.of(id(f,"a")));
        var p=bindingPlan(f,d,ordinary(f));
        var r=InjectionBindings.evaluate(p,ConditionModel.create(f.source().space(),f.source().lowering().occurrences()),
                new ConfigurationAssignment(Map.of(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev"),FiniteDomain.Value.bool(false)),d.evidence()),ExogenousConditionEvaluator.Limits.conservative());
        outcome(r,NOT_ACTIVE); assertTrue(r.rows().getFirst().trace().isEmpty());
    }
    @Test void beanNameAliasSelectsCanonicalDefinitionWithoutDuplicateCandidate() throws Exception {
        var f=setup("a","b"); var d=dependency(f,Shape.SINGLE,Name.of("chosen"),Required.REQUIRED); var p=bindingPlan(f,d,ordinary(f));
        var steps=new ArrayList<>(f.registration().steps()); steps.add(aliasStep("alias",f.events().getFirst(),"reg-a","chosen","a",d.evidence()));
        var order=new ArrayList<>(f.registration().establishedOrderPrefix()); order.add("alias");
        var r=resolve(f,withRegistration(p,registration(f.registration(),steps,order,COMPLETE,COMPLETE,List.of())));
        selected(r,"a"); assertEquals(2,r.registration().states().getLast().definitions().size());
        assertEquals(2,r.registration().candidates().size());
    }
    @Test void parentSearchRemainsExplicitGapInsteadOfAssumingLocalWinner() throws Exception {
        var f=setup("a"); var d=dependency(f,Shape.SINGLE,Name.absent(),Required.REQUIRED); var p=bindingPlan(f,d,ordinary(f));
        var r=resolve(f,withRegistration(p,registration(f.registration(),f.registration().steps(),f.registration().establishedOrderPrefix(),COMPLETE,RegistrationEvent.Completeness.UNKNOWN,List.of())));
        outcome(r,UNKNOWN); reason(r,BindingProcessing.Reason.HIERARCHY_UNSUPPORTED);
    }
    @Test void flagsConflictingWithRegistrationCannotSilentlyReplaceEarlierEvidence() throws Exception {
        var f=setup("a"); var d=dependency(f,Shape.SINGLE,Name.absent(),Required.REQUIRED); var p=bindingPlan(f,d,ordinary(f));
        var earlier=new BeanRegistrationEvidence.Definition(id(f,"a"),List.of(),FALSE,TRUE,FALSE,FALSE,FALSE,d.evidence());
        var r=resolve(f,withRegistration(p,registration(f.registration(),f.registration().steps(),f.registration().establishedOrderPrefix(),COMPLETE,COMPLETE,List.of(earlier))));
        outcome(r,UNKNOWN); reason(r,BindingProcessing.Reason.METADATA_CONFLICT);
    }
    @Test void namedLookupAndTypeEnumerationAreDifferentProofs() throws Exception {
        var f=setup("a"); var base=dependency(f,Shape.SINGLE,Name.absent(),Required.REQUIRED);
        var proof=new BindingEvidence.Match(base.identity(),id(f,"a"),BindingEvidence.Lane.DIRECT,TRUE,FALSE,TRUE,TRUE,TRUE,BindingEvidence.Knowledge.KNOWN,base.evidence());
        outcome(resolve(f,bindingPlan(f,List.of(base),ordinary(f),List.of(proof))),UNSATISFIED);
        var named=change(base,Shape.SINGLE,Mode.RESOURCE,Required.REQUIRED,Name.of("a"),Name.absent(),FALSE,true,false,true,false,Normalization.COMPLETE,Optional.empty());
        var namedProof=new BindingEvidence.Match(named.identity(),id(f,"a"),BindingEvidence.Lane.DIRECT,TRUE,FALSE,TRUE,TRUE,TRUE,BindingEvidence.Knowledge.KNOWN,named.evidence());
        selected(resolve(f,bindingPlan(f,List.of(named),ordinary(f),List.of(namedProof))),"a");
    }
    @Test void scalarUnknownPrimaryOrPriorityDoesNotBecomeLexicalWinner() throws Exception {
        var f=setup("a","b"); var d=dependency(f,Shape.SINGLE,Name.absent(),Required.REQUIRED);
        var a=definition(f,"a",false,false,null); var b=definition(f,"b",false,false,null);
        var unknownPrimary=flags(a,TRUE,TRUE,LogicalValue.UNKNOWN,FALSE);
        outcome(resolve(f,bindingPlan(f,d,List.of(unknownPrimary,b))),UNKNOWN);
        var unknownPriority=new BindingEvidence.Definition(a.candidate(),TRUE,TRUE,FALSE,FALSE,Optional.empty(),a.runtimeKind(),BindingEvidence.Rank.unknown(),a.order(),FALSE,a.evidence());
        var r=resolve(f,bindingPlan(f,d,List.of(unknownPriority,b))); outcome(r,UNKNOWN); reason(r,BindingProcessing.Reason.PRIORITY_UNKNOWN);
    }
    @Test void unknownNameMetadataHasTypedGap() throws Exception {
        var f=setup("a","b"); var d=dependency(f,Shape.SINGLE,Name.unknown(),Required.REQUIRED);
        var r=resolve(f,bindingPlan(f,d,ordinary(f))); outcome(r,UNKNOWN); reason(r,BindingProcessing.Reason.DEPENDENCY_NAME_UNKNOWN);
    }
    @Test void factoryProductsAndScopedProxiesRemainSeparateRuntimeObligations() throws Exception {
        var f=setup("a"); var d=dependency(f,Shape.SINGLE,Name.absent(),Required.REQUIRED); var def=definition(f,"a",false,false,null);
        for(var kind:List.of(BindingEvidence.RuntimeKind.FACTORY_BEAN,BindingEvidence.RuntimeKind.SCOPED_PROXY,BindingEvidence.RuntimeKind.UNKNOWN)) {
            var changed=new BindingEvidence.Definition(def.candidate(),TRUE,TRUE,FALSE,FALSE,Optional.empty(),kind,def.priority(),def.order(),FALSE,def.evidence());
            var r=resolve(f,bindingPlan(f,d,List.of(changed))); outcome(r,UNKNOWN); reason(r,BindingProcessing.Reason.FACTORY_OR_PROXY_UNSUPPORTED);
        }
    }
    @Test void unsupportedDescriptorFormsAreAllAccountedForWithoutInventedTargets() throws Exception {
        var f=setup("a"); var base=dependency(f,Shape.SINGLE,Name.absent(),Required.REQUIRED);
        for(var normalization:Normalization.values()) if(normalization!=Normalization.COMPLETE) {
            var d=change(base,Shape.SINGLE,Mode.AUTOWIRE,Required.REQUIRED,Name.absent(),Name.absent(),FALSE,true,false,false,false,normalization,Optional.empty());
            var r=resolve(f,bindingPlan(f,d,ordinary(f))); outcome(r,UNKNOWN); assertEquals(1,r.coverage().dependencies()); assertFalse(r.issues().isEmpty());
        }
    }
    @Test void orderingUsesPriorityOrderedThenOrderAndKeepsStableTies() throws Exception {
        var f=setup("z","a","b"); var d=dependency(f,Shape.LIST,Name.absent(),Required.REQUIRED);
        var defs=new ArrayList<BindingEvidence.Definition>();
        for(var event:f.events()) {
            var original=definition(f,event.eventSlot(),false,false,null);
            defs.add(new BindingEvidence.Definition(original.candidate(),TRUE,TRUE,FALSE,FALSE,Optional.empty(),original.runtimeKind(),
                    original.priority(),BindingEvidence.Rank.of(event.eventSlot().equals("a")?100:1),event.eventSlot().equals("a")?TRUE:FALSE,original.evidence()));
        }
        var r=resolve(f,aggregatePlan(f,d,defs,false)); outcome(r,AGGREGATE);
        assertEquals(List.of("a","z","b"),r.rows().getFirst().selected().stream().map(InjectionBindings.Target::beanName).toList());
    }
    @Test void aggregateOrderErrorIsNotHiddenAsLogicalUncertainty() throws Exception {
        var f=setup("a","b"); var d=dependency(f,Shape.LIST,Name.absent(),Required.REQUIRED); var a=definition(f,"a",false,false,null);
        var bad=new BindingEvidence.Definition(a.candidate(),TRUE,TRUE,FALSE,FALSE,Optional.empty(),a.runtimeKind(),a.priority(),
                new BindingEvidence.Rank(BindingEvidence.Knowledge.ERROR,Optional.empty()),FALSE,a.evidence());
        var r=resolve(f,aggregatePlan(f,d,List.of(bad,definition(f,"b",false,false,null)),false));
        outcome(r,ERROR); reason(r,BindingProcessing.Reason.MATCH_OPERATION_ERROR);
    }
    @Test void aggregateDoesNotInjectTheVerySameBeanInItsSelfReferencePass() throws Exception {
        var f=setup("self"); var base=dependency(f,Shape.LIST,Name.absent(),Required.REQUIRED);
        var d=change(base,Shape.LIST,Mode.AUTOWIRE,Required.REQUIRED,Name.absent(),Name.absent(),FALSE,true,false,false,false,Normalization.COMPLETE,Optional.of(id(f,"self")));
        var p=aggregatePlan(f,d,ordinary(f),false); outcome(resolve(f,p),UNSATISFIED);
    }
    @Test void instanceFactorySiblingIsSelfReferenceButCanBeLastResortElement() throws Exception {
        var f=setup("owner","product"); var base=dependency(f,Shape.LIST,Name.absent(),Required.REQUIRED);
        var d=change(base,Shape.LIST,Mode.AUTOWIRE,Required.REQUIRED,Name.absent(),Name.absent(),FALSE,true,false,false,false,Normalization.COMPLETE,Optional.of(id(f,"owner")));
        var product=definition(f,"product",false,false,null);
        product=new BindingEvidence.Definition(product.candidate(),TRUE,TRUE,FALSE,FALSE,Optional.of(id(f,"owner")),product.runtimeKind(),product.priority(),product.order(),FALSE,product.evidence());
        var r=resolve(f,aggregatePlan(f,d,List.of(definition(f,"owner",false,false,null),product),false));
        outcome(r,AGGREGATE); assertEquals(List.of("product"),r.rows().getFirst().selected().stream().map(InjectionBindings.Target::beanName).toList());
    }
    static Dependency parameter(Dependency d,String slot) {
        var point=new InjectionPoint(d.point().buildContextIdentity(),"Consumer#wire(Token,Missing)",InjectionPoint.SiteKind.METHOD_PARAMETER,slot,d.evidence());
        return new Dependency(point,d.owner(),d.requestedType(),d.shape(),d.mode(),d.required(),d.dependencyName(),d.suggestedName(),d.hasQualifier(),
                d.standardLookup(),d.resourceDefaultName(),d.resourceTypeFallback(),d.emptyAggregateFallback(),d.elementIndicatesMultiple(),d.normalization(),d.evidence());
    }
    @Test void missingOptionalMethodParameterRemovesAllTentativeBindings() throws Exception {
        var f=setup("a"); var first=parameter(dependency(f,Shape.SINGLE,Name.absent(),Required.OPTIONAL),"0"); var second=parameter(first,"1");
        var p=bindingPlan(f,List.of(first,second),ordinary(f),List.of(match(f,first,"a",BindingEvidence.Lane.DIRECT,TRUE,TRUE,TRUE,TRUE),
                match(f,second,"a",BindingEvidence.Lane.DIRECT,FALSE,FALSE,FALSE,TRUE)));
        p=new InjectionBindingPlan(p.registrationPlan(),p.dependencies(),p.definitions(),p.matches(),
                List.of(new Group("wire",List.of(first.identity(),second.identity()),List.of(second.identity()),first.evidence())),p.environment(),p.limits());
        var r=resolve(f,p); assertEquals(2,r.coverage().dependencies());
        assertTrue(r.rows().stream().allMatch(row->row.outcome()==GROUP_SKIPPED && row.selected().isEmpty()));
        assertFalse(r.rows().stream().filter(row->row.dependency().equals(first.identity())).findFirst().orElseThrow().trace().isEmpty());
    }
    @Test void nullableParameterDoesNotSkipOptionalMethod() throws Exception {
        var f=setup("a"); var first=parameter(dependency(f,Shape.SINGLE,Name.absent(),Required.OPTIONAL),"0"); var second=parameter(first,"1");
        var p=bindingPlan(f,List.of(first,second),ordinary(f),List.of(match(f,first,"a",BindingEvidence.Lane.DIRECT,TRUE,TRUE,TRUE,TRUE),
                match(f,second,"a",BindingEvidence.Lane.DIRECT,FALSE,FALSE,FALSE,TRUE)));
        p=new InjectionBindingPlan(p.registrationPlan(),p.dependencies(),p.definitions(),p.matches(),
                List.of(new Group("wire",List.of(first.identity(),second.identity()),List.of(first.identity()),first.evidence())),p.environment(),p.limits());
        var r=resolve(f,p); assertEquals(1,r.rows().stream().filter(row->row.outcome()==SELECTED).count());
        assertEquals(1,r.rows().stream().filter(row->row.outcome()==ABSENT_OPTIONAL).count());
    }
    @Test void duplicateDescriptorsAndForeignReferencesAreRejected() throws Exception {
        var f=setup("a"); var a=dependency(f,Shape.SINGLE,Name.absent(),Required.REQUIRED);
        var b=change(a,Shape.SINGLE,Mode.AUTOWIRE,Required.OPTIONAL,Name.absent(),Name.absent(),FALSE,true,false,false,false,Normalization.COMPLETE,Optional.empty());
        assertThrows(IllegalArgumentException.class,()->bindingPlan(f,List.of(a,b),ordinary(f),List.of()));
        var p=bindingPlan(f,a,ordinary(f));
        assertThrows(IllegalArgumentException.class,()->new InjectionBindingPlan(p.registrationPlan(),p.dependencies(),p.definitions(),p.matches(),
                List.of(new Group("foreign",List.of(ContentDigest.sha256Utf8("foreign")),List.of(),a.evidence())),p.environment(),p.limits()));
    }
    @Test void candidateIdentityIsScopedAndDescriptorChangesDoNotChangeSiteIdentity() throws Exception {
        var f=setup("a"); var a=dependency(f,Shape.SINGLE,Name.absent(),Required.REQUIRED);
        var b=change(a,Shape.SINGLE,Mode.AUTOWIRE,Required.OPTIONAL,Name.of("a"),Name.absent(),FALSE,true,false,false,false,Normalization.COMPLETE,Optional.empty());
        assertEquals(a.point().identity(),b.point().identity()); assertNotEquals(a.identity(),b.identity());
        var ra=resolve(f,bindingPlan(f,a,ordinary(f))); var rb=resolve(f,bindingPlan(f,b,ordinary(f)));
        assertNotEquals(ra.candidateIdentity(a.point().identity(),id(f,"a")),rb.candidateIdentity(b.point().identity(),id(f,"a")));
        assertTrue(ra.candidateIdentity(a.point().identity(),id(f,"a")).value().matches("spring-binding-candidate:sha256:[0-9a-f]{64}"));
    }
    @Test void explicitReferenceToProviderBeanIsNamedSelectionRatherThanDeferredWrapper() throws Exception {
        var f=setup("a"); var base=dependency(f,Shape.OBJECT_PROVIDER,Name.of("a"),Required.REQUIRED);
        var d=change(base,Shape.OBJECT_PROVIDER,Mode.EXPLICIT_REFERENCE,Required.REQUIRED,Name.of("a"),Name.absent(),FALSE,true,false,false,false,Normalization.COMPLETE,Optional.empty());
        selected(resolve(f,bindingPlan(f,d,ordinary(f))),"a");
    }
    @Test void excludedFactoryDoesNotPoisonAnEstablishedOrdinaryCandidate() throws Exception {
        var f=setup("factory","ordinary"); var d=dependency(f,Shape.SINGLE,Name.absent(),Required.REQUIRED);
        var factory=definition(f,"factory",false,false,null);
        factory=new BindingEvidence.Definition(factory.candidate(),FALSE,TRUE,FALSE,FALSE,Optional.empty(),BindingEvidence.RuntimeKind.FACTORY_BEAN,
                factory.priority(),factory.order(),FALSE,factory.evidence());
        selected(resolve(f,bindingPlan(f,d,List.of(factory,definition(f,"ordinary",false,false,null)))),"ordinary");
    }
    @Test void unknownEligibilityFlagRetainsItsActualRootCause() throws Exception {
        var f=setup("a"); var d=dependency(f,Shape.SINGLE,Name.absent(),Required.REQUIRED);
        var def=flags(definition(f,"a",false,false,null),LogicalValue.UNKNOWN,TRUE,FALSE,FALSE);
        var r=resolve(f,bindingPlan(f,d,List.of(def))); outcome(r,UNKNOWN); reason(r,BindingProcessing.Reason.FLAGS_UNKNOWN);
    }
    @Test void m4aInjectionObligationsCannotDisappearWhenDescriptorsAreMissing() throws Exception {
        var f=setup("a"); var d=dependency(f,Shape.SINGLE,Name.absent(),Required.REQUIRED); var p=bindingPlan(f,d,ordinary(f));
        var source=f.source().source();
        var inventory=com.evolution.analysis.spring.BindingInventoryFixtures.withInjectionObligation(source.inventory());
        var lowering=ConditionEvidenceLowering.lower(source.build(),inventory,List.of(),ConditionEvidenceLowering.Limits.conservative());
        var old=f.registration().discoveryPlan();
        var discovery=RegistrationPlan.create(source.build(),inventory,lowering,old.events(),old.precedence(),old.container(),old.initialDefinitions(),old.overridePolicy(),old.limits());
        var rp=f.registration();
        var registration=new BeanRegistrationPlan(discovery,rp.steps(),rp.establishedOrderPrefix(),rp.order(),rp.orderEvidence(),rp.initialRegistry(),rp.noParentContainer(),
                rp.closureEvidence(),rp.definitions(),rp.queries(),rp.queryTypes(),rp.limits());
        p=withRegistration(p,registration);
        var r=resolve(f,p); assertEquals(inventory.obligations().size(),r.obligations().size());
        assertEquals(1,r.obligations().stream().filter(o->o.status()==InjectionBindings.ObligationStatus.NORMALIZATION_REQUIRED).count());
        reason(r,BindingProcessing.Reason.DESCRIPTOR_INCOMPLETE);
        var injection=inventory.obligations().stream().filter(o->o.primaryMechanism().equals("spring.injection.field")).findFirst().orElseThrow();
        p=new InjectionBindingPlan(p.registrationPlan(),p.dependencies(),p.definitions(),p.matches(),p.groups(),
                List.of(new ObligationBinding(injection.identity(),List.of(d.identity()),d.evidence())),p.environment(),p.limits());
        r=resolve(f,p);
        assertEquals(1,r.obligations().stream().filter(o->o.status()==InjectionBindings.ObligationStatus.NORMALIZED).count());
        assertEquals(1,r.obligations().stream().filter(o->o.status()==InjectionBindings.ObligationStatus.NOT_INJECTION).count());
    }
    @Test void aliasShadowShortcutThrowsAliasShadowShortcutUnsupported() throws Exception {
        var f = setup("a", "b"); var d = dependency(f, Shape.SINGLE, Name.of("b"), Required.REQUIRED);
        var a = f.events().getFirst(); var b = f.events().get(1);
        var dp = RegistrationPlan.create(f.source().source().build(), f.source().source().inventory(), f.source().lowering(),
                List.of(a, b), List.of(before(f.source(), a, b)), new RegistrationPlan.Container("authored-root", f.source().source().evidence()),
                List.of(), RegistrationPlan.OverridePolicy.ALLOW, RegistrationPlan.Limits.conservative());
        var rA = regStep("reg-a", a, Optional.empty(), f.source().source().evidence());
        var rB = regStep("reg-b", b, Optional.empty(), f.source().source().evidence());
        var shadow = aliasStep("shadow", a, "reg-a", "a", "b", d.evidence());
        var rp = rp(f.source(), dp, List.of(rA, rB, shadow), List.of("reg-a", "reg-b", "shadow"));
        var p = withRegistration(bindingPlan(f, d, ordinary(f)), rp);
        var r = resolve(f, p);
        outcome(r, UNKNOWN); reason(r, BindingProcessing.Reason.ALIAS_SHADOW_SHORTCUT_UNSUPPORTED);
    }
    @Test void priorContainerErrorYieldsNotReached() throws Exception {
        var f = setup("a"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED); var p = bindingPlan(f, d, ordinary(f));
        var a = f.events().getFirst();
        var dp = RegistrationPlan.create(f.source().source().build(), f.source().source().inventory(), f.source().lowering(),
                List.of(a), List.of(), new RegistrationPlan.Container("authored-root", f.source().source().evidence()),
                List.of(), RegistrationPlan.OverridePolicy.FORBID, RegistrationPlan.Limits.conservative());
        var r1 = regStep("r1", a, Optional.empty(), f.source().source().evidence());
        var r2 = regStep("r2", a, Optional.empty(), f.source().source().evidence());
        var rp = rp(f.source(), dp, List.of(r1, r2), List.of("r1", "r2"));
        var r = resolve(f, withRegistration(p, rp));
        outcome(r, NOT_REACHED); reason(r, BindingProcessing.Reason.PRIOR_CONTAINER_ERROR);
    }
    @Test void comparatorPolicyUnknownInScalarSelectionThrowsComparatorUnknown() throws Exception {
        var f = setup("a", "b"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var p = bindingPlan(f, d, ordinary(f)); var e = p.environment();
        var unknownEnv = new Environment(COMPLETE, COMPLETE, COMPLETE, ComparatorPolicy.UNKNOWN, e.candidateOrder(), COMPLETE, e.evidence());
        var r = resolve(f, with(p, unknownEnv, p.limits()));
        outcome(r, UNKNOWN); reason(r, BindingProcessing.Reason.COMPARATOR_UNKNOWN);
    }
    @Test void contextMismatchBetweenInjectionPointAndBuild() throws Exception {
        var f = setup("a"); var base = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var foreignBuildId = new SpringBuildContext.Identity("spring-build-context:sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        var foreignPoint = new InjectionPoint(foreignBuildId, base.point().ownerDeclarationKey(), base.point().siteKind(), base.point().siteSlot(), base.point().declarationEvidenceKey());
        var d = new Dependency(foreignPoint, base.owner(), base.requestedType(), base.shape(), base.mode(), base.required(), base.dependencyName(),
                base.suggestedName(), base.hasQualifier(), base.standardLookup(), base.resourceDefaultName(), base.resourceTypeFallback(),
                base.emptyAggregateFallback(), base.elementIndicatesMultiple(), base.normalization(), base.evidence());
        var r = resolve(f, bindingPlan(f, List.of(d), ordinary(f), List.of(match(f, d, "a", BindingEvidence.Lane.DIRECT, TRUE, TRUE, TRUE, TRUE))));
        outcome(r, UNKNOWN); reason(r, BindingProcessing.Reason.CONTEXT_MISMATCH);
    }
    @Test void openDescriptorInventoryEmitsDescriptorInventoryOpen() throws Exception {
        var f = setup("a"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var p = bindingPlan(f, d, ordinary(f)); var e = p.environment();
        var openEnv = new Environment(RegistrationEvent.Completeness.UNKNOWN, COMPLETE, COMPLETE, e.comparator(), e.candidateOrder(), COMPLETE, e.evidence());
        var r = resolve(f, with(p, openEnv, p.limits()));
        reason(r, BindingProcessing.Reason.DESCRIPTOR_INVENTORY_OPEN);
    }
    @Test void primaryConflictDistinguishesFlagsUnknownFromMissingTypeEvidence() throws Exception {
        var f = setup("target", "other");
        var d = dependency(f, Shape.SINGLE, Name.of("target"), Required.REQUIRED);
        var targetDef = definition(f, "target", false, false, null);
        var otherPrimary = definition(f, "other", true, false, null);
        var proofs = List.of(
                match(f, d, "target", BindingEvidence.Lane.DIRECT, TRUE, TRUE, TRUE, TRUE),
                match(f, d, "other", BindingEvidence.Lane.DIRECT, LogicalValue.UNKNOWN, TRUE, TRUE, TRUE));
        var rTypeMissing = resolve(f, bindingPlan(f, List.of(d), List.of(targetDef, otherPrimary), proofs));
        outcome(rTypeMissing, UNKNOWN); reason(rTypeMissing, BindingProcessing.Reason.TYPE_EVIDENCE_MISSING);

        var otherUnknownPrimary = flags(definition(f, "other", false, false, null), TRUE, TRUE, LogicalValue.UNKNOWN, FALSE);
        var proofsKnownType = List.of(
                match(f, d, "target", BindingEvidence.Lane.DIRECT, TRUE, TRUE, TRUE, TRUE),
                match(f, d, "other", BindingEvidence.Lane.DIRECT, TRUE, TRUE, TRUE, TRUE));
        var rFlagsUnknown = resolve(f, bindingPlan(f, List.of(d), List.of(targetDef, otherUnknownPrimary), proofsKnownType));
        outcome(rFlagsUnknown, UNKNOWN); reason(rFlagsUnknown, BindingProcessing.Reason.FLAGS_UNKNOWN);
    }
    @Test void bidirectionalFactoryBeanConflictDetected() throws Exception {
        var f = setup("a"); var d = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var def = new BindingEvidence.Definition(id(f, "a"), TRUE, TRUE, FALSE, FALSE, Optional.empty(),
                BindingEvidence.RuntimeKind.FACTORY_BEAN, BindingEvidence.Rank.absent(), BindingEvidence.Rank.absent(), FALSE, d.evidence());
        var earlierNotFactory = new BeanRegistrationEvidence.Definition(id(f, "a"), List.of(), FALSE, TRUE, FALSE, FALSE, FALSE, d.evidence());
        var p = withRegistration(bindingPlan(f, d, List.of(def)),
                registration(f.registration(), f.registration().steps(), f.registration().establishedOrderPrefix(), COMPLETE, COMPLETE, List.of(earlierNotFactory)));
        var r = resolve(f, p); outcome(r, UNKNOWN); reason(r, BindingProcessing.Reason.METADATA_CONFLICT);
    }
    @Test void unsatisfiedAndAmbiguousDependenciesEmitTypedGaps() throws Exception {
        var f = setup(); var unsat = dependency(f, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var rUnsat = resolve(f, bindingPlan(f, unsat, List.of()));
        outcome(rUnsat, UNSATISFIED); reason(rUnsat, BindingProcessing.Reason.MISSING_REQUIRED_DEPENDENCY);

        var f2 = setup("a", "b"); var ambig = dependency(f2, Shape.SINGLE, Name.absent(), Required.REQUIRED);
        var rAmbig = resolve(f2, bindingPlan(f2, ambig, ordinary(f2)));
        outcome(rAmbig, AMBIGUOUS); reason(rAmbig, BindingProcessing.Reason.NON_UNIQUE_DEPENDENCY);
    }
}
