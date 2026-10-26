package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.readiness.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaReadinessClosureTest {
    static ReadinessPlan plan(boolean unknown,boolean unselected){
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:evo:readiness-closure:test");
        var d=ContentDigest.sha256Utf8("inventory");
        var inventory=new InventoryManifest(InventoryManifest.SCHEMA,repo,d,d,0,0,0,0,
                Optional.of(new SnapshotIdentity("snapshot:"+d.value())),Optional.of(d));
        var a=ReadinessPlanner.context("a","root-a","main","baseline",List.of("a/src"),Map.of(),List.of(),inventory,true);
        var b=ReadinessPlanner.context("b","root-b","integration","baseline",List.of("b/src"),Map.of(),List.of(),inventory,true);
        var requirements=new ArrayList<>(List.of(ReadinessPlanner.requirement(ReadinessPlan.Kind.MODEL,"root-a","MODEL_REQUIRED",List.of(),List.of()),
                ReadinessPlanner.requirement(ReadinessPlan.Kind.MODEL,"root-b","MODEL_REQUIRED",List.of(),List.of()),
                ReadinessPlanner.requirement(ReadinessPlan.Kind.BUILD_VALIDATION,a.id(),"BUILD_REQUIRED",List.of(),List.of()),
                ReadinessPlanner.requirement(ReadinessPlan.Kind.BUILD_VALIDATION,b.id(),"BUILD_REQUIRED",List.of(),List.of())));
        for(String root:List.of("root-a","root-b"))for(var kind:List.of(ReadinessPlan.Kind.TOOLCHAIN,ReadinessPlan.Kind.DEPENDENCIES))
            requirements.add(ReadinessPlanner.requirement(kind,root,"ROOT_INPUT_REQUIRED",List.of(),List.of()));
        for(var context:List.of(a,b))for(var kind:List.of(ReadinessPlan.Kind.PLATFORM,ReadinessPlan.Kind.GENERATION))
            requirements.add(ReadinessPlanner.requirement(kind,context.id(),"CONTEXT_INPUT_REQUIRED",List.of(),List.of()));
        return new ReadinessPlan(ReadinessPlan.SCHEMA,inventory,new BuildContextSelection(BuildContextSelection.SCHEMA,Map.of(),
                List.of(a.id(),b.id()),unselected?List.of(a.id()):List.of(a.id(),b.id()),unselected?List.of(b.id()):List.of(),unknown?List.of("unopened-root"):List.of()),
                List.of(new ReadinessPlan.Root("root-a","a",ReadinessPlan.Family.PLAIN,List.of(),List.of()),new ReadinessPlan.Root("root-b","b",ReadinessPlan.Family.PLAIN,List.of(),List.of())),
                List.of(new ReadinessPlan.Module("a","root-a","a","a",List.of("java"),List.of(),List.of()),new ReadinessPlan.Module("b","root-b","b","b",List.of("java"),List.of(),List.of())),
                List.of(a,b),requirements,List.of(),new ReadinessPlan.OwnershipSummary(0,Map.of(),new ContentHandle(d,0)));
    }
    @Test void emptyProviderCannotEraseAnotherRootCustomContextOrDiscoveryFrontier(){
        var plan=plan(true,true);var view=new JavaReadinessClosure().reconcile(plan,ContentDigest.sha256Utf8("policy"),List.of());
        assertEquals(12,view.requirements().size(),"The denominator comes from readiness, not emitted proof rows");
        assertEquals(2,view.contexts().size(),"An unselected custom context must remain visible");
        assertTrue(view.active()>=4);assertFalse(view.frontier().isEmpty());
    }
    @Test void everyCapturedScopeDimensionMustMatchEvenForAnAuthenticatedCurrentProof()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var plan=captured(f,false,false);
        var context=plan.contexts().stream().filter(c->!c.sourceSet().equals("main")).findFirst().orElseThrow();
        var requirement=plan.requirements().stream().filter(r->r.subject().equals(context.id())&&r.kind()==ReadinessPlan.Kind.BUILD_VALIDATION).findFirst().orElseThrow();
        var module=plan.modules().stream().filter(m->m.id().equals(context.module())).findFirst().orElseThrow();
        var scope=JavaEvidenceScopes.scope(plan,context,ModuleDescriptor.create(plan.inventory().repository(),module.directory(),module.id()));
        assertEquals(JavaReadinessClosure.State.SATISFIED,new JavaReadinessClosure().reconcile(plan,f.verifier.policyIdentity(),
                List.of(proof(f,plan,requirement,context,JavaReadinessClosure.State.SATISFIED,true))).requirements().get(requirement.id()).state());
        var refs=List.of(f.store.put(CanonicalJson.write(plan)),plan.ownership().ledger());
        for(String field:scope.keySet()){
            var foreign=new TreeMap<>(scope);String digest=ContentDigest.sha256Utf8("foreign "+field).value();
            foreign.put(field,switch(field){case "sourceSet"->"another-custom-set";case "variant"->"another-variant";case "module"->"module:"+digest;case "baseSnapshot"->"snapshot:"+digest;default->digest;});
            var candidate=f.proof(foreign,JavaReadinessClosure.QUESTION,JavaReadinessClosure.subject(plan,requirement).value(),
                    CanonicalJson.write(JavaReadinessClosure.answer(plan,requirement,JavaReadinessClosure.State.SATISFIED)),refs);
            assertTrue(candidate.current(),field+" is a valid authenticated observation, not a malformed token");
            assertEquals(JavaReadinessClosure.State.PENDING,new JavaReadinessClosure().reconcile(plan,f.verifier.policyIdentity(),List.of(candidate)).requirements().get(requirement.id()).state(),field);
        }
        assertEquals(context.sourceSet(),new JavaReadinessClosure().reconcile(plan,f.verifier.policyIdentity(),List.of()).contexts().get(context.id()).sourceSet());
    }
    static ReadinessPlan captured(JavaEvidenceTestStore.AuthorityFixture fixture,boolean unknown,boolean unselected)throws Exception{
        var p=plan(unknown,unselected);
        return new ReadinessPlan(p.schema(),p.inventory(),p.selection(),p.roots(),p.modules(),p.contexts(),p.requirements(),p.recipes(),
                new ReadinessPlan.OwnershipSummary(0,Map.of(),fixture.store.put("")));
    }
    static JavaEvidenceAuthority.Verified proof(JavaEvidenceTestStore.AuthorityFixture fixture,ReadinessPlan plan,ReadinessPlan.Requirement requirement,JavaReadinessClosure.State state)throws Exception{
        var context=plan.contexts().stream().filter(c->c.id().equals(requirement.subject())||c.root().equals(requirement.subject())).findFirst().orElseThrow();
        return proof(fixture,plan,requirement,context,state,true);
    }
    static JavaEvidenceAuthority.Verified proof(JavaEvidenceTestStore.AuthorityFixture fixture,ReadinessPlan plan,ReadinessPlan.Requirement requirement,ReadinessPlan.Context context,JavaReadinessClosure.State state,boolean includeFootprint)throws Exception{
        var module=plan.modules().stream().filter(m->m.id().equals(context.module())).findFirst().orElseThrow();
        var scope=JavaEvidenceScopes.scope(plan,context,ModuleDescriptor.create(plan.inventory().repository(),module.directory(),module.id()));
        var references=includeFootprint?List.of(fixture.store.put(CanonicalJson.write(plan)),plan.ownership().ledger()):List.<ContentHandle>of();
        return fixture.proof(scope,JavaReadinessClosure.QUESTION,JavaReadinessClosure.subject(plan,requirement).value(),
                CanonicalJson.write(JavaReadinessClosure.answer(plan,requirement,state)),references);
    }
    @Test void exactCurrentProofsCloseTheReadinessCensusWithoutClaimingSemanticCoverage()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var plan=captured(f,false,false);var proofs=new ArrayList<JavaEvidenceAuthority.Verified>();
        for(var r:plan.requirements())proofs.add(proof(f,plan,r,JavaReadinessClosure.State.SATISFIED));
        var closure=new JavaReadinessClosure();var view=closure.reconcile(plan,f.verifier.policyIdentity(),proofs);
        assertEquals(0,view.active());assertTrue(view.readinessClosed());
        assertTrue(view.contexts().values().stream().allMatch(JavaReadinessClosure.ContextRow::readinessClosed));
        assertEquals("NOT_EVALUATED",view.wire().get("semanticCoverage"));
        assertEquals(1,view.remainingContexts(plan.inventory().compatibleV1Snapshot().orElseThrow(),plan.contexts().getFirst().identity()).size());
        assertEquals(view.identity(),closure.reconcile(plan,f.verifier.policyIdentity(),proofs.reversed()).identity());
        f.store.blobs.remove(proofs.getFirst().answer());
        assertFalse(closure.reconcile(plan,f.verifier.policyIdentity(),proofs).readinessClosed());
        assertTrue(view.readinessClosed(),"Old captured views remain immutable history");
    }
    @Test void anotherRootContextOrUncitedPlanCannotLendItsProof()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var plan=captured(f,false,false);var r=plan.requirements().getFirst();
        var other=plan.contexts().stream().filter(c->!c.root().equals(r.subject())).findFirst().orElseThrow();
        var foreign=proof(f,plan,r,other,JavaReadinessClosure.State.SATISFIED,true);
        var uncited=proof(f,plan,r,plan.contexts().getFirst(),JavaReadinessClosure.State.SATISFIED,false);
        var view=new JavaReadinessClosure().reconcile(plan,f.verifier.policyIdentity(),List.of(foreign,uncited));
        assertEquals(JavaReadinessClosure.State.PENDING,view.requirements().get(r.id()).state());
        var valid=proof(f,plan,r,JavaReadinessClosure.State.SATISFIED);
        assertEquals(JavaReadinessClosure.State.REOPENED,new JavaReadinessClosure().reconcile(plan,ContentDigest.sha256Utf8("new registry"),List.of(valid)).requirements().get(r.id()).state());
    }
    @Test void applicabilityProofDoesNotBypassPrerequisitesOrDiscoveryFrontiers()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var p=captured(f,false,false);var root=p.requirements().getFirst();
        var build=ReadinessPlanner.requirement(ReadinessPlan.Kind.BUILD_VALIDATION,p.contexts().getFirst().id(),"NO_COMPILE",List.of(root.id()),List.of());
        var plan=new ReadinessPlan(p.schema(),p.inventory(),p.selection(),p.roots(),p.modules(),p.contexts(),List.of(root,build),p.recipes(),p.ownership());
        var np=proof(f,plan,build,JavaReadinessClosure.State.NOT_APPLICABLE);
        var view=new JavaReadinessClosure().reconcile(plan,f.verifier.policyIdentity(),List.of(np));
        assertEquals(List.of(root.id()),view.requirements().get(build.id()).blockers());
        var proven=proof(f,plan,root,JavaReadinessClosure.State.SATISFIED);
        view=new JavaReadinessClosure().reconcile(plan,f.verifier.policyIdentity(),List.of(proven,np));
        assertEquals(JavaReadinessClosure.State.NOT_APPLICABLE,view.requirements().get(build.id()).state());
        var contrary=proof(f,plan,build,JavaReadinessClosure.State.SATISFIED);
        assertEquals(JavaReadinessClosure.State.CONFLICTED,new JavaReadinessClosure().reconcile(plan,f.verifier.policyIdentity(),List.of(proven,np,contrary)).requirements().get(build.id()).state());
    }
    @Test void aSignedIncompleteRequirementListDoesNotCloseAnUnrepresentedContext()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var p=captured(f,false,false);
        var root=p.requirements().getFirst();var plan=new ReadinessPlan(p.schema(),p.inventory(),p.selection(),p.roots(),p.modules(),p.contexts(),List.of(root),p.recipes(),p.ownership());
        var view=new JavaReadinessClosure().reconcile(plan,f.verifier.policyIdentity(),List.of(proof(f,plan,root,JavaReadinessClosure.State.SATISFIED)));
        assertFalse(view.readinessClosed(),"Every discovered root/context needs its independent minimum census");
        assertFalse(view.frontier().isEmpty());
    }
    @Test void aFreshPolicyProofCanRecoverWithoutDeletingTheOldAuditObservation()throws Exception{
        var old=new JavaEvidenceTestStore.AuthorityFixture();var current=new JavaEvidenceTestStore.AuthorityFixture();
        var plan=captured(old,false,false);captured(current,false,false);var requirement=plan.requirements().getFirst();
        var prior=proof(old,plan,requirement,JavaReadinessClosure.State.SATISFIED);
        var fresh=proof(current,plan,requirement,JavaReadinessClosure.State.SATISFIED);
        var view=new JavaReadinessClosure().reconcile(plan,current.verifier.policyIdentity(),List.of(prior,fresh));
        assertEquals(JavaReadinessClosure.State.SATISFIED,view.requirements().get(requirement.id()).state(),
                "A current scoped proof recovers the requirement while the prior policy observation remains in audit history");
        assertEquals(List.of(fresh.identity()),view.requirements().get(requirement.id()).proofs());
    }
}
