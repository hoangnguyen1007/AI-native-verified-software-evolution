package com.evolution.analysis.polyglot;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticContextCoordinatorTest {
    final SemanticReconcilerTest.Fixture fixture;
    final SemanticContextCoordinator coordinator;
    SemanticContextCoordinatorTest()throws Exception{fixture=new SemanticReconcilerTest.Fixture();coordinator=new SemanticContextCoordinator(fixture.content,fixture.tables);}
    LanguageSemanticRequest request(String module)throws IOException{
        var r=fixture.request;var selected=new LanguageSemanticRequest(r.context(),r.inventory(),r.root(),module,r.sourceSet(),r.variant(),r.language(),r.dialect(),r.documents(),r.census(),r.resolution(),r.provider(),r.tool(),r.policy(),r.workPolicy());
        var documents=new ArrayList<SemanticTables.Row>();try(var reader=fixture.tables.open(r.documents(),()->false)){SemanticTables.Row row;while((row=reader.next())!=null)documents.add(new SemanticTables.Row(row.kind(),row.key(),selected.scope(),row.fields()));}
        var census=fixture.requirements.stream().map(row->new SemanticTables.Row(row.kind(),row.key(),selected.scope(),row.fields())).toList();
        return new LanguageSemanticRequest(selected.context(),selected.inventory(),selected.root(),selected.module(),selected.sourceSet(),selected.variant(),selected.language(),selected.dialect(),fixture.tables.write(SemanticTables.Kind.DOCUMENT,documents.iterator(),()->false),fixture.tables.write(SemanticTables.Kind.CENSUS,census.iterator(),()->false),selected.resolution(),selected.provider(),selected.tool(),selected.policy(),selected.workPolicy());
    }
    SemanticTables.Table output(LanguageSemanticRequest request)throws IOException{
        var rows=new ArrayList<SemanticTables.Row>();try(var reader=fixture.tables.open(request.census(),()->false)){SemanticTables.Row row;while((row=reader.next())!=null)rows.add(new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,row.key(),request.scope(),Map.of("obligation",row.identity().value(),"status",Set.of("A01","A11","A12").contains(row.key())?"RESOLVED":"NOT_APPLICABLE","targets",List.of())));}
        return fixture.tables.write(SemanticTables.Kind.OBSERVATION,rows.iterator(),()->false);
    }
    SemanticContextCoordinator.Plan plan(List<SemanticContextCoordinator.Selection> contexts)throws IOException{
        var inventory=new PolyglotInventory.Report(fixture.request.inventory(),fixture.request.documents(),1,0);
        return coordinator.plan(fixture.request.context(),inventory,contexts.iterator(),fixture.request.policy(),()->false);
    }
    @Test void oneBrokenAndOneMissingProviderCannotEraseHealthySiblingOrSelectedDenominator()throws Exception{
        var healthy=request("healthy");var broken=request("broken");var absent=request("absent");var plan=plan(List.of(new SemanticContextCoordinator.Selection("a",healthy),new SemanticContextCoordinator.Selection("b",broken),new SemanticContextCoordinator.Selection("c",absent)));var receipts=new ArrayList<SemanticContextCoordinator.Checkpoint>();
        var result=coordinator.run(plan,r->r.module().equals("absent")?Optional.empty():Optional.of((input,cancel)->{if(input.module().equals("broken"))throw new IOException("AUTHORED_PROVIDER_FAILURE");return output(input);}),r->Map.of(),Map.of(),Set.of(),fixture.request::policy,receipts::add,()->false);
        assertEquals(3,result.selected());assertEquals(3,result.processed());assertEquals(1,result.failed());assertEquals(1,result.missingProviders());assertEquals(3,result.incomplete());assertFalse(result.complete());assertFalse(result.selectionQualified());assertEquals(3,receipts.size());
        var states=new ArrayList<String>();try(var reader=fixture.tables.open(result.contexts(),()->false)){SemanticTables.Row row;while((row=reader.next())!=null)states.add((String)row.fields().get("state"));}assertEquals(List.of("PARTIAL","FAILED","MISSING_PROVIDER"),states);
        assertTrue(fixture.content.verify(result.report()));
    }
    @Test void cancellationKeepsCompletedReceiptAndResumeRechecksRatherThanCallingProviderAgain()throws Exception{
        var first=request("first");var second=request("second");var plan=plan(List.of(new SemanticContextCoordinator.Selection("a",first),new SemanticContextCoordinator.Selection("b",second)));var stopped=new AtomicBoolean();var saved=new TreeMap<String,ContentHandle>();
        assertThrows(IOException.class,()->coordinator.run(plan,r->Optional.of((input,cancel)->output(input)),r->Map.of(),Map.of(),Set.of(),fixture.request::policy,checkpoint->{saved.put(checkpoint.key(),checkpoint.receipt());stopped.set(true);},stopped::get));assertEquals(Set.of("a"),saved.keySet());
        var result=coordinator.run(plan,r->{assertEquals("second",r.module());return Optional.of((input,cancel)->output(input));},r->Map.of(),saved,Set.of(),fixture.request::policy,checkpoint->{},()->false);assertEquals(2,result.processed());assertFalse(result.complete());
        var foreign=plan(List.of(new SemanticContextCoordinator.Selection("a",second)));
        assertThrows(IOException.class,()->coordinator.run(foreign,r->Optional.empty(),r->Map.of(),saved,Set.of(),fixture.request::policy,c->{},()->false));
    }
    @Test void producerWrittenCompleteBitCannotRestoreAuthorityAndCorruptOutputIsRetriedAsFailure()throws Exception{
        var request=request("first");var plan=plan(List.of(new SemanticContextCoordinator.Selection("a",request)));var saved=new TreeMap<String,ContentHandle>();
        coordinator.run(plan,r->Optional.of((input,cancel)->output(input)),r->Map.of(),Map.of(),Set.of(),fixture.request::policy,c->saved.put(c.key(),c.receipt()),()->false);
        var handle=saved.get("a");Map<String,Object> value;try(var input=fixture.content.open(handle)){value=new TreeMap<>(GraphJson.object(GraphJson.read(new String(input.readAllBytes(),StandardCharsets.UTF_8),GraphJson.Limits.defaults())));}
        value.put("state","COMPLETE");value.put("coverage",Map.of("complete",true));saved.put("a",fixture.content.text(CanonicalJson.write(value)));
        var result=coordinator.run(plan,r->{fail("Captured output must be revalidated without rerunning the provider");return Optional.empty();},r->Map.of(),saved,Set.of(),fixture.request::policy,c->{},()->false);assertEquals(1,result.incomplete());assertFalse(result.complete());
        var observations=GraphJson.object(value.get("observations"));var bytes=GraphJson.object(observations.get("content"));fixture.content.blobs.remove(new ContentDigest((String)bytes.get("digest")));
        var broken=coordinator.run(plan,r->Optional.empty(),r->Map.of(),saved,Set.of(),fixture.request::policy,c->{},()->false);assertEquals(1,broken.failed());assertFalse(broken.complete());
    }
    @Test void duplicateContextScopeAndChangedRootPolicyDoNotPublishACompleteRoot()throws Exception{
        var request=request("same");var plan=plan(List.of(new SemanticContextCoordinator.Selection("a",request),new SemanticContextCoordinator.Selection("b",request)));
        assertThrows(IOException.class,()->coordinator.run(plan,r->Optional.empty(),r->Map.of(),Map.of(),Set.of(),fixture.request::policy,c->{},()->false));
        assertThrows(IOException.class,()->coordinator.run(plan,r->Optional.empty(),r->Map.of(),Map.of(),Set.of(),()->ContentDigest.sha256Utf8("revoked"),c->{},()->false));
    }
    @Test void olderContradictoryProofPreventsLatestOutputFromClosingAndRevocationIsRecomputedOnResume()throws Exception{
        var authority=new SemanticAnswerLedgerTest.Authority();var f=authority.fixture;var request=f.request;var first=authority.answer("first");var second=authority.answer("second");var firstProofs=authority.prove(first);var secondProofs=authority.prove(second);SemanticAnswerLedger.Proofs proofs=(r,t)->t.equals(first)?firstProofs:secondProofs;var ledger=new SemanticAnswerLedger(f.content,f.tables);var older=ledger.append(request,first,Optional.empty(),()->false);var coordinator=new SemanticContextCoordinator(f.content,f.tables);var plan=coordinator.plan(request.context(),new PolyglotInventory.Report(request.inventory(),request.documents(),1,0),List.of(new SemanticContextCoordinator.Selection("one",request)).iterator(),request.policy(),()->false);var saved=new TreeMap<String,ContentHandle>();
        var conflicted=coordinator.runWithHistory(plan,r->Optional.of((input,cancel)->second),proofs,r->Optional.of(older),Map.of(),Set.of(),request::policy,c->saved.put(c.key(),c.receipt()),()->false);assertEquals(1,conflicted.incomplete());assertFalse(conflicted.complete());Map<String,Object> receipt;try(var stream=f.content.open(saved.get("one"))){receipt=GraphJson.object(GraphJson.read(new String(stream.readAllBytes(),StandardCharsets.UTF_8),GraphJson.Limits.defaults()));}assertEquals("PARTIAL",receipt.get("state"));assertEquals(Boolean.TRUE,GraphJson.object(receipt.get("coverage")).get("complete"));var answerHandle=LanguageSemanticRequest.handle(receipt.get("answers"));var answers=ledger.verifyStoredState(request,answerHandle,()->false);assertEquals(1,answers.conflicts().rows());assertFalse(answers.answersComplete());
        var resumed=coordinator.runWithHistory(plan,r->{fail("Resume must use captured observations");return Optional.empty();},proofs,r->Optional.empty(),saved,Set.of(firstProofs.get("observations").identity()),request::policy,c->{},()->false);assertEquals(0,resumed.incomplete());assertFalse(resumed.complete());assertTrue(f.content.verify(answerHandle));assertEquals(1,ledger.verifyStoredState(request,answerHandle,()->false).conflicts().rows());
    }
    @Test void planCapturesCoordinatorVersionAndRejectsForeignSnapshotContexts()throws Exception{
        var request=request("one");var plan=plan(List.of(new SemanticContextCoordinator.Selection("one",request)));var captured=plan.identity();var future=new SemanticContextCoordinator.Plan(plan.root(),plan.inventory(),plan.contexts(),plan.policy(),new com.evolution.analysis.contract.common.VersionedIdentifier(SemanticContextCoordinator.PROVIDER.id(),"future-version"));assertNotEquals(captured,future.identity());assertEquals(captured,plan.identity());assertThrows(IOException.class,()->coordinator.run(future,r->Optional.empty(),r->Map.of(),Map.of(),Set.of(),fixture.request::policy,c->{},()->false));
        var otherContext=com.evolution.analysis.evidence.EvidenceContext.forSnapshot(com.evolution.analysis.contract.identity.SnapshotIdentity.from(com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:foreign-semantic-repository"),request.inventory()));var foreign=new LanguageSemanticRequest(otherContext,request.inventory(),request.root(),request.module(),request.sourceSet(),request.variant(),request.language(),request.dialect(),request.documents(),request.census(),request.resolution(),request.provider(),request.tool(),request.policy(),request.workPolicy());var invalid=coordinator.plan(plan.root(),plan.inventory(),List.of(new SemanticContextCoordinator.Selection("foreign",foreign)).iterator(),plan.policy(),()->false);assertThrows(IOException.class,()->coordinator.run(invalid,r->Optional.empty(),r->Map.of(),Map.of(),Set.of(),fixture.request::policy,c->{},()->false));
    }
    @Test void equalSnapshotCannotHideForeignInventoryOrTrustPolicy()throws Exception{
        var request=request("one");for(boolean foreignInventory:List.of(true,false)){
            var bad=new LanguageSemanticRequest(request.context(),foreignInventory?ContentDigest.sha256Utf8("foreign-inventory"):request.inventory(),request.root(),request.module(),request.sourceSet(),request.variant(),request.language(),request.dialect(),request.documents(),request.census(),request.resolution(),request.provider(),request.tool(),foreignInventory?request.policy():ContentDigest.sha256Utf8("foreign-policy"),request.workPolicy());var plan=plan(List.of(new SemanticContextCoordinator.Selection("foreign",bad)));assertThrows(IOException.class,()->coordinator.run(plan,r->Optional.empty(),r->Map.of(),Map.of(),Set.of(),fixture.request::policy,c->{},()->false));
        }
    }
    @Test void providerFailureAndAbsencePreserveAndRevalidateEarlierAnswers()throws Exception{
        var authority=new SemanticAnswerLedgerTest.Authority();var f=authority.fixture;var request=f.request;var output=authority.answer("retained");var proofs=authority.prove(output);var ledger=new SemanticAnswerLedger(f.content,f.tables);var history=ledger.append(request,output,Optional.empty(),()->false);
        var coordinator=new SemanticContextCoordinator(f.content,f.tables);var plan=coordinator.plan(request.context(),new PolyglotInventory.Report(request.inventory(),request.documents(),1,0),List.of(new SemanticContextCoordinator.Selection("one",request)).iterator(),request.policy(),()->false);
        for(boolean absent:List.of(true,false)){
            var saved=new TreeMap<String,ContentHandle>();var result=coordinator.runWithHistory(plan,r->absent?Optional.empty():Optional.of((input,cancel)->{throw new IOException("NATIVE_AUTHORED_FAILURE");}),(r,o)->proofs,r->Optional.of(history),Map.of(),Set.of(proofs.get("observations").identity()),request::policy,c->saved.put(c.key(),c.receipt()),()->false);
            Map<String,Object> receipt;try(var stream=f.content.open(saved.get("one"))){receipt=GraphJson.object(GraphJson.read(new String(stream.readAllBytes(),StandardCharsets.UTF_8),GraphJson.Limits.defaults()));}
            assertEquals(absent?"MISSING_PROVIDER":"FAILED",receipt.get("state"));assertFalse(result.complete());assertEquals(1,result.incomplete());assertNull(receipt.get("observations"));assertNull(receipt.get("coverage"));assertNotNull(receipt.get("answers"),"A provider outage must retain the independently replayed answer history");
            var answers=ledger.verifyStoredState(request,LanguageSemanticRequest.handle(receipt.get("answers")),()->false);assertEquals(history,answers.history());assertEquals(12L,answers.counts().get("REOPENED"));assertFalse(answers.answersComplete());
            var resumed=new TreeMap<String,ContentHandle>();coordinator.runWithHistory(plan,r->Optional.empty(),(r,o)->proofs,r->Optional.empty(),saved,Set.of(),request::policy,c->resumed.put(c.key(),c.receipt()),()->false);
            try(var stream=f.content.open(resumed.get("one"))){receipt=GraphJson.object(GraphJson.read(new String(stream.readAllBytes(),StandardCharsets.UTF_8),GraphJson.Limits.defaults()));}var restored=ledger.verifyStoredState(request,LanguageSemanticRequest.handle(receipt.get("answers")),()->false);assertEquals(history,restored.history());assertTrue(restored.answersComplete());assertEquals("MISSING_PROVIDER",receipt.get("state"),"Historical answers do not turn a missing current provider into operational completion");
        }
    }
}
