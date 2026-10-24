package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaEvidenceLedgerTest {
    private static final String D=JavaEvidenceCodecTest.D;
    static Map<String,Object> scope(){return Map.of("inventory",D,"baseSnapshot","snapshot:"+D,"readiness",D,"selection",D,"context",D,"logicalScope",D,"module","module:"+D,"sourceSet","main","variant","baseline");}
    static JavaEvidenceRecord requirement(String subject,List<String> prerequisites){return new JavaEvidenceCodec().create(Map.of("schema","java-closure-requirement:m8.4-v1","scope",scope(),"question","java.output-bytes","subject",subject,"category","generation","predicate",Map.of("id","java.output-bytes","version","m8.4-step4-v1"),"gapRefs",List.of(),"occurrences",List.of(),"readinessRefs",List.of(),"prerequisites",prerequisites));}
    @Test void aDisappearedDiagnosticOrNakedSatisfiedDecisionCannotCloseTheCensus(){
        var requirement=requirement("generated-source",List.of());
        var key=JavaEvidenceLedger.requirementKey(requirement);
        var decision=JavaEvidenceCodecTest.decision();decision.put("requirement",key.value());decision.put("reason",null);
        var result=new JavaEvidenceLedger().fold(List.of(requirement),List.of(new JavaEvidenceCodec().create(decision)),Map.of(),Set.of(),Set.of(key));
        assertEquals(JavaEvidenceLedger.State.PENDING,result.rows().get(key).state());
        assertEquals(1,result.active());
        decision.put("state","NARROWED");decision.put("proofs",List.of());decision.put("reason","subset claimed without evidence");
        assertEquals(JavaEvidenceLedger.State.PENDING,new JavaEvidenceLedger().fold(List.of(requirement),List.of(new JavaEvidenceCodec().create(decision)),Map.of(),Set.of(),Set.of(key)).rows().get(key).state());
    }
    @Test void cyclicAndForeignPrerequisitesAreRejectedRatherThanDropped(){
        var a=requirement("a",List.of(D));
        assertThrows(IllegalArgumentException.class,()->new JavaEvidenceLedger().fold(List.of(a),List.of(),Map.of(),Set.of(),Set.of()));
        var b=requirement("b",List.of());var ak=JavaEvidenceLedger.requirementKey(a);var bk=JavaEvidenceLedger.requirementKey(b);
        var cycleA=requirement("a",List.of(bk.value()));var cycleB=requirement("b",List.of(ak.value()));
        assertThrows(IllegalArgumentException.class,()->new JavaEvidenceLedger().fold(List.of(cycleA,cycleB),List.of(),Map.of(),Set.of(),Set.of()));
    }
    static JavaEvidenceRecord decision(ContentDigest requirement,JavaEvidenceAuthority.Verified proof,List<String> supersedes){var data=JavaEvidenceCodecTest.decision();data.put("requirement",requirement.value());data.put("proofs",List.of(proof.identity().value()));data.put("supersedes",supersedes);data.put("reason",null);return new JavaEvidenceCodec().create(data);}
    @Test void supersedingContraryProofsRequiresActualAdjudicationEvidence()throws Exception{
        var fixture=new JavaEvidenceTestStore.AuthorityFixture();var req=requirement("generated",List.of());var key=JavaEvidenceLedger.requirementKey(req);
        var a=fixture.proof("java.output-bytes","generated","answer-a",List.of());var b=fixture.proof("java.output-bytes","generated","answer-b",List.of());
        var da=decision(key,a,List.of());var db=decision(key,b,List.of());
        var all=Map.of(a.identity(),a,b.identity(),b);var ledger=new JavaEvidenceLedger();
        assertEquals(JavaEvidenceLedger.State.CONFLICTED,ledger.fold(List.of(req),List.of(da),all,Set.of(),Set.of(key)).rows().get(key).state(),"Discovered contrary proof cannot be hidden by omitting its decision");
        var conflicted=ledger.fold(List.of(req),List.of(da,db),all,Set.of(),Set.of(key));
        assertEquals(JavaEvidenceLedger.State.CONFLICTED,conflicted.rows().get(key).state());
        assertEquals(conflicted.identity(),ledger.fold(List.of(req),List.of(db,da,db),all,Set.of(),Set.of(key)).identity());
        var forged=decision(key,a,List.of(da.identity().value(),db.identity().value()));
        assertEquals(JavaEvidenceLedger.State.CONFLICTED,ledger.fold(List.of(req),List.of(da,db,forged),all,Set.of(),Set.of(key)).rows().get(key).state());
        var adjudicated=fixture.proof("java.output-bytes","generated","answer-a",List.of(JavaEvidenceRecord.handle(b.record().fields().get("answer"))));var dc=decision(key,adjudicated,List.of(da.identity().value(),db.identity().value()));
        var complete=ledger.fold(List.of(req),List.of(db,dc,da),Map.of(a.identity(),a,b.identity(),b,adjudicated.identity(),adjudicated),Set.of(),Set.of(key));assertEquals(JavaEvidenceLedger.State.SATISFIED,complete.rows().get(key).state());
    }
    @Test void signedClosureAndRevocationPreserveHistoryAndArrivalIndependentProjection()throws Exception{
        var fixture=new JavaEvidenceTestStore.AuthorityFixture();var req=requirement("generated",List.of());var key=JavaEvidenceLedger.requirementKey(req);var proof=fixture.proof("java.output-bytes","generated","bytes",List.of());var decision=decision(key,proof,List.of());var ledger=new JavaEvidenceLedger();
        var closed=ledger.fold(List.of(req),List.of(decision),Map.of(proof.identity(),proof),Set.of(),Set.of(key));
        assertEquals(0,closed.active());assertEquals(JavaEvidenceLedger.State.SATISFIED,closed.rows().get(key).state());
        assertEquals(closed.identity(),ledger.fold(List.of(req),List.of(decision,decision),Map.of(proof.identity(),proof),Set.of(),Set.of(key)).identity());
        var revoked=ledger.fold(List.of(req),List.of(decision),Map.of(proof.identity(),proof),Set.of(proof.identity()),Set.of(key));
        assertEquals(JavaEvidenceLedger.State.REOPENED,revoked.rows().get(key).state());assertEquals(1,revoked.active());
        fixture.store.blobs.remove(proof.answer());assertEquals(JavaEvidenceLedger.State.REOPENED,ledger.fold(List.of(req),List.of(decision),Map.of(proof.identity(),proof),Set.of(),Set.of(key)).rows().get(key).state());assertEquals(JavaEvidenceLedger.State.SATISFIED,closed.rows().get(key).state());
    }
    @Test void ledgerCanPublishBeforeValidationExistsAndLaterAppendWithoutDeletingHistory()throws Exception{
        var fixture=new JavaEvidenceTestStore.AuthorityFixture();var req=requirement("generated",List.of());var key=JavaEvidenceLedger.requirementKey(req);var tables=new JavaEvidenceTables(fixture.store,100000);var ledger=new JavaEvidenceLedger();var census=fixture.store.put("registered requirement census");
        var pending=ledger.publish(scope(),census,fixture.verifier.policyIdentity(),List.of(req),List.of(),List.of(),Optional.empty(),Map.of(),Set.of(),Set.of(),tables,()->false);
        assertEquals("OPEN",pending.projection().text("denominator"));assertEquals(1,pending.current().active());
        var inventedFields=new LinkedHashMap<>(pending.ledger().fields());inventedFields.put("previous",D);var invented=new JavaEvidenceCodec().create(inventedFields);
        assertThrows(java.io.IOException.class,()->ledger.publish(scope(),census,fixture.verifier.policyIdentity(),List.of(req),List.of(),List.of(),Optional.of(invented),Map.of(),Set.of(),Set.of(),tables,()->false));
        var proof=fixture.proof("java.output-bytes","generated","bytes",List.of());var decision=decision(key,proof,List.of());
        var completed=ledger.publish(scope(),census,fixture.verifier.policyIdentity(),List.of(req),List.of(decision),List.of(),Optional.of(pending.ledger()),Map.of(proof.identity(),proof),Set.of(),Set.of(key),tables,()->false);
        assertEquals("CLOSED",completed.projection().text("denominator"));assertEquals(0,completed.current().active());
        assertThrows(IllegalArgumentException.class,()->ledger.publish(scope(),census,fixture.verifier.policyIdentity(),List.of(req),List.of(),List.of(),Optional.of(completed.ledger()),Map.of(proof.identity(),proof),Set.of(),Set.of(key),tables,()->false));
        var changedPolicy=ledger.publish(scope(),census,ContentDigest.sha256Utf8("revoked policy"),List.of(req),List.of(decision),List.of(),Optional.of(completed.ledger()),Map.of(proof.identity(),proof),Set.of(),Set.of(key),tables,()->false);
        assertEquals(JavaEvidenceLedger.State.REOPENED,changedPolicy.current().rows().get(key).state());
    }
    @Test void historicalGapRemainsActiveUntilEveryBoundRequirementIsClosed()throws Exception{
        var fixture=new JavaEvidenceTestStore.AuthorityFixture();var gap=JavaEvidenceGaps.create(scope(),JavaEvidenceGaps.Reason.GENERATOR_INPUT_MISSING,new ContentDigest(D),new ContentDigest(D));
        var aFields=new LinkedHashMap<>(requirement("a",List.of()).fields());aFields.put("gapRefs",List.of(gap.gapIdentity().value()));var bFields=new LinkedHashMap<>(requirement("b",List.of()).fields());bFields.put("gapRefs",List.of(gap.gapIdentity().value()));var a=new JavaEvidenceCodec().create(aFields);var b=new JavaEvidenceCodec().create(bFields);var ak=JavaEvidenceLedger.requirementKey(a);var bk=JavaEvidenceLedger.requirementKey(b);
        var ap=fixture.proof("java.output-bytes","a","a-bytes",List.of());var bp=fixture.proof("java.output-bytes","b","b-bytes",List.of());var ad=decision(ak,ap,List.of());var bd=decision(bk,bp,List.of());var ledger=new JavaEvidenceLedger();var proofs=Map.of(ap.identity(),ap,bp.identity(),bp);
        var partial=ledger.fold(List.of(a,b),List.of(ad),proofs,Set.of(),Set.of(ak,bk));assertTrue(JavaEvidenceLedger.gapDispositions(List.of(gap),List.of(a,b),partial).getFirst().active());
        var complete=ledger.fold(List.of(a,b),List.of(ad,bd),proofs,Set.of(),Set.of(ak,bk));var history=JavaEvidenceLedger.gapDispositions(List.of(gap),List.of(a,b),complete);assertFalse(history.getFirst().active());assertEquals(gap,history.getFirst().gap());assertEquals(2,history.getFirst().requirements().size());
        assertTrue(JavaEvidenceLedger.gapDispositions(List.of(gap),List.of(),complete).getFirst().active());
    }
    @Test void aDisposedFailedAlternativeDoesNotVetoAnotherValidatedAnswer()throws Exception{
        var fixture=new JavaEvidenceTestStore.AuthorityFixture();var req=requirement("generated",List.of());var key=JavaEvidenceLedger.requirementKey(req);var proof=fixture.proof("java.output-bytes","generated","bytes",List.of());var accepted=decision(key,proof,List.of());
        var noAnswer=JavaEvidenceCodecTest.decision();noAnswer.put("requirement",key.value());noAnswer.put("state","PENDING");noAnswer.put("proofs",List.of());noAnswer.put("reason","PRODUCER_FAILED");var failed=new JavaEvidenceCodec().create(noAnswer);var ledger=new JavaEvidenceLedger();
        var result=ledger.fold(List.of(req),List.of(accepted,failed),Map.of(proof.identity(),proof),Set.of(),Set.of(key));assertEquals(JavaEvidenceLedger.State.SATISFIED,result.rows().get(key).state());
        assertEquals(JavaEvidenceLedger.State.PENDING,ledger.fold(List.of(req),List.of(failed),Map.of(),Set.of(),Set.of(key)).rows().get(key).state());
    }
    @Test void reopeningAPrerequisiteInvalidatesItsPreviouslySatisfiedDependent()throws Exception{
        var fixture=new JavaEvidenceTestStore.AuthorityFixture();var a=requirement("a",List.of());var ak=JavaEvidenceLedger.requirementKey(a);var b=requirement("b",List.of(ak.value()));var bk=JavaEvidenceLedger.requirementKey(b);
        var ap=fixture.proof("java.output-bytes","a","a",List.of());var bp=fixture.proof("java.output-bytes","b","b",List.of());var ad=decision(ak,ap,List.of());var fields=new LinkedHashMap<>(decision(bk,bp,List.of()).fields());fields.put("prerequisites",List.of(ad.identity()));var bd=new JavaEvidenceCodec().create(fields);var proofs=Map.of(ap.identity(),ap,bp.identity(),bp);var ledger=new JavaEvidenceLedger();
        assertEquals(0,ledger.fold(List.of(b,a),List.of(bd,ad),proofs,Set.of(),Set.of(ak,bk)).active());
        var reopened=ledger.fold(List.of(a,b),List.of(ad,bd),proofs,Set.of(ap.identity()),Set.of(ak,bk));assertEquals(JavaEvidenceLedger.State.REOPENED,reopened.rows().get(bk).state());assertEquals(List.of(ak),reopened.rows().get(bk).blockers());
        var contrary=fixture.proof("java.output-bytes","a","contrary-a",List.of());var conflicted=ledger.fold(List.of(a,b),List.of(ad,bd),Map.of(ap.identity(),ap,bp.identity(),bp,contrary.identity(),contrary),Set.of(),Set.of(ak,bk));assertEquals(JavaEvidenceLedger.State.CONFLICTED,conflicted.rows().get(ak).state());assertEquals(JavaEvidenceLedger.State.REOPENED,conflicted.rows().get(bk).state());
        var cross=decision(bk,bp,List.of(ad.identity().value()));assertThrows(IllegalArgumentException.class,()->ledger.fold(List.of(a,b),List.of(ad,cross),proofs,Set.of(),Set.of(ak,bk)));
    }
}
