package com.evolution.analysis.execution;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.readiness.*;
import java.io.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.execution.ExecutionGraphTest.*;
import static com.evolution.analysis.execution.EvidenceVerifier.*;

class RequirementReconcilerTest {
    static ReadinessPlan plan(boolean prerequisite){
        var inventory=new InventoryManifest(InventoryManifest.SCHEMA,RepositoryIdentity.fromCanonicalCoordinate("urn:test:execution"),digest("selection"),digest("inventory"),0,0,0,0,Optional.empty(),Optional.empty());
        var ctx=new ReadinessPlan.Context("ctx","module","root","main","baseline",List.of(),Map.of(),List.of(),digest("closure"),true);
        var model=ReadinessPlanner.requirement(ReadinessPlan.Kind.MODEL,"root","MODEL",List.of(),List.of());
        var build=ReadinessPlanner.requirement(ReadinessPlan.Kind.BUILD_VALIDATION,"ctx","BUILD",prerequisite?List.of(model.id()):List.of(),List.of());
        return new ReadinessPlan(ReadinessPlan.SCHEMA,inventory,new BuildContextSelection(BuildContextSelection.SCHEMA,Map.of(),List.of("ctx"),List.of("ctx"),List.of(),List.of()),
                List.of(new ReadinessPlan.Root("root",".",ReadinessPlan.Family.MAVEN,List.of(),List.of())),List.of(new ReadinessPlan.Module("module","root",".","module",List.of("java"),List.of(),List.of())),List.of(ctx),prerequisite?List.of(model,build):List.of(build),List.of(),new ReadinessPlan.OwnershipSummary(0,Map.of(),new ContentHandle(digest("ledger"),0)));
    }
    static class Fixture {
        final KeyPair controller,validator;final EvidenceVerifier verifier;final RepositoryContentStore content;
        Fixture()throws Exception{
            controller=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();validator=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            verifier=new EvidenceVerifier(Map.of("c",controller.getPublic()),Map.of("v",validator.getPublic()),List.of(new Method(VERSION,VERSION,digest("tool"),List.of("inputs","census"))));
            content=new RepositoryContentStore(){
                final Map<ContentDigest,byte[]> blobs=new HashMap<>();
                public ContentHandle put(InputStream in,java.util.function.BooleanSupplier cancel)throws IOException{byte[] bytes=in.readAllBytes();var handle=new ContentHandle(ContentDigest.sha256(bytes),bytes.length);blobs.put(handle.digest(),bytes);return handle;}
                public InputStream open(ContentHandle h){return new ByteArrayInputStream(blobs.get(h.digest()));}
                public InputStream range(ContentHandle h,long o,long n){throw new UnsupportedOperationException();}
                public boolean verify(ContentHandle h){return blobs.containsKey(h.digest())&&blobs.get(h.digest()).length==h.size();}
            };
        }
        VerifiedClaim proof(ReadinessPlan plan,ReadinessPlan.Requirement req,String answer)throws Exception{
            var h=content.put(new ByteArrayInputStream(answer.getBytes(java.nio.charset.StandardCharsets.UTF_8)),()->false);
            var receipt=new Receipt("execution-protected-receipt:m8.4-v1",UUID.randomUUID().toString(),1,digest("launch"),digest("profile"),digest("output"),digest("material"),digest("termination"));
            var expected=new Expected(receipt.launch(),receipt.profile(),1,RequirementReconciler.scope(plan,req),digest("closure"),h,VERSION,VERSION,digest("tool"));
            var att=new Attestation("execution-protected-attestation:m8.4-v1",receipt.identity(),h.digest(),expected.scope(),expected.inputClosure(),digest("tool"),VERSION,VERSION,List.of("inputs","census"),true);
            return verifier.verifyAccepted(expected,receipt,EvidenceVerifierTest.sign(receipt,"c",controller),att,EvidenceVerifierTest.sign(att,"v",validator),content).orElseThrow();
        }
    }
    static ExecutionGraph.Task producer(String name){return new ExecutionGraph.Task("execution-task:m8.4-v1",ExecutionGraph.Kind.VALIDATE,"root",List.of("ctx"),digest(name),List.of(),List.of("proof"),"configured",VERSION);}
    static ExecutionPlan.Candidate candidate(ExecutionGraph.Task task,String role){return new ExecutionPlan.Candidate(task.identity(),"proof",VERSION,role);}
    static ExecutionPlan.Binding binding(ReadinessPlan p,ReadinessPlan.Requirement r,ExecutionPlan.Mode mode,List<ExecutionPlan.Candidate> candidates){return new ExecutionPlan.Binding("execution-binding:m8.4-v1",new ExecutionPlan.ScopedRef(p.identity(),r.id()),mode,candidates,Optional.empty(),Optional.empty());}
    @Test void equivalentAlternativesAndAllOfRolesAreNotFirstArrivalWins()throws Exception{
        var f=new Fixture();var p=plan(false);var r=p.requirements().getFirst();var a=producer("a");var b=producer("b");var c=producer("c");var ca=candidate(a,"compile");var cb=candidate(b,"compile");var cc=candidate(c,"census");
        var graph=new ExecutionGraph(List.of(a,b,c),List.of());var bindings=List.of(binding(p,r,ExecutionPlan.Mode.ALL_OF,List.of(ca,cb,cc)));
        var proof=f.proof(p,r,"same");var results=Map.of(ca,new RequirementReconciler.ProducerResult(Optional.of(proof),"accepted"),cb,new RequirementReconciler.ProducerResult(Optional.empty(),"unavailable"),cc,new RequirementReconciler.ProducerResult(Optional.of(proof),"accepted"));
        var reconciler=new RequirementReconciler(f.verifier.policyIdentity());var view=reconciler.reconcile(p,graph,bindings,results,Map.of());
        assertEquals(0,view.active());assertEquals(RequirementReconciler.State.SATISFIED,view.requirements().get(r.id()).state());
        var missing=new HashMap<>(results);missing.remove(cc);assertEquals(1,reconciler.reconcile(p,graph,bindings,missing,Map.of()).active());
        var contradiction=new HashMap<>(results);contradiction.put(cb,new RequirementReconciler.ProducerResult(Optional.of(f.proof(p,r,"different")),"accepted"));
        assertEquals(RequirementReconciler.State.CONFLICTED,reconciler.reconcile(p,graph,bindings,contradiction,Map.of()).requirements().get(r.id()).state());
    }
    @Test void prerequisiteAndTrustRevocationKeepUnmetRequirementsVisible()throws Exception{
        var f=new Fixture();var p=plan(true);var build=p.requirements().stream().filter(r->r.kind()==ReadinessPlan.Kind.BUILD_VALIDATION).findFirst().orElseThrow();var model=p.requirements().stream().filter(r->r.kind()==ReadinessPlan.Kind.MODEL).findFirst().orElseThrow();
        var a=producer("a");var candidate=candidate(a,"compile");var graph=new ExecutionGraph(List.of(a),List.of());
        var pending=new ExecutionPlan.Binding("execution-binding:m8.4-v1",new ExecutionPlan.ScopedRef(p.identity(),model.id()),ExecutionPlan.Mode.PENDING,List.of(),Optional.empty(),Optional.of("MODEL_REQUIRED"));
        var bindings=List.of(pending,binding(p,build,ExecutionPlan.Mode.ANY_EQUIVALENT,List.of(candidate)));
        var view=new RequirementReconciler(f.verifier.policyIdentity()).reconcile(p,graph,bindings,Map.of(candidate,new RequirementReconciler.ProducerResult(Optional.of(f.proof(p,build,"compile")),"accepted")),Map.of());
        assertEquals(2,view.active());assertEquals(List.of(model.id()),view.requirements().get(build.id()).blockers());
        var simple=plan(false);var req=simple.requirements().getFirst();var simpleBindings=List.of(binding(simple,req,ExecutionPlan.Mode.ANY_EQUIVALENT,List.of(candidate)));
        var revoked=new RequirementReconciler(digest("new-trust-policy"));assertEquals(1,revoked.reconcile(simple,graph,simpleBindings,Map.of(candidate,new RequirementReconciler.ProducerResult(Optional.of(f.proof(simple,req,"compile")),"accepted")),Map.of()).active());
    }
    @Test void operationalAttemptChangesDoNotChangeValidatedSemanticProjection()throws Exception{
        var f=new Fixture();var p=plan(false);var r=p.requirements().getFirst();assertEquals(f.proof(p,r,"answer").identity(),f.proof(p,r,"answer").identity());
    }
}
