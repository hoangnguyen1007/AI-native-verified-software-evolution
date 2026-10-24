package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaSemanticEvidenceTest {
    final JavaEvidenceTestStore.AuthorityFixture authority;
    final JavaEvidenceTables tables;
    final Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs=new HashMap<>();
    final Map<String,Object> viewPayload=Map.of("schema","authored-semantic-view:m8.4-v1");
    final ContentDigest view=CanonicalJson.sha256(viewPayload);
    final Map<String,Object> scope;
    final FrontendRequest request;
    final SourceInput source;
    final Entity caller,target;
    final RelationshipOccurrence occurrence;
    final VersionedIdentifier provider=new VersionedIdentifier("frontend.authored-contract","1");
    JavaSemanticEvidenceTest()throws Exception{
        authority=new JavaEvidenceTestStore.AuthorityFixture();tables=new JavaEvidenceTables(authority.store,100000);
        var repo=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/semantic-census.git");var module=ModuleDescriptor.create(repo,"fixture","fixture");byte[] raw="class A { void f(){ g(); } void g(){} }".getBytes(StandardCharsets.UTF_8);source=new SourceInput(SourceDocument.create(repo,module,"fixture/A.java",ContentDigest.sha256(raw),SourceClassification.MAIN),raw);authority.store.put(new java.io.ByteArrayInputStream(raw),()->false);
        var docs=List.of(source.document());var snapshot=RepositorySnapshot.create(repo,Optional.empty(),false,docs.stream().map(SnapshotFile::from).toList(),docs);var values=new TreeMap<String,Object>(JavaEvidenceLedgerTest.scope());values.put("module",module.identity().value());values.put("baseSnapshot",snapshot.identity().value());scope=Map.copyOf(values);
        var platform=PlatformInput.create(21,"21-fixture","fixture",List.of(new PlatformInput.Artifact("platform.jar",ContentDigest.sha256Utf8("fixture-platform"),Path.of("platform.jar"),PlatformInput.Format.JAR)));var component=new ManifestComponent(provider,view);var options=new TreeMap<>(FrontendRequest.options(module.identity(),SourceClassification.MAIN,docs));options.put("java.evidence-view",view.value());options.put("java.native-context",(String)scope.get("context"));options.put("ingestion.source-set-name","main");
        var manifest=AnalysisManifest.create(new VersionedIdentifier("analysis.manifest","1"),snapshot,List.of(module),List.of(platform.entry()),AnalysisConfiguration.create(new VersionedIdentifier("analysis.configuration","1"),options),component,component,component);request=new FrontendRequest(manifest,module.identity(),SourceClassification.MAIN,List.of(source),platform,List.of());
        var owner=JavaSymbolName.topLevelType("","A");caller=Entity.create(EntityOrigin.PROJECT,EntityScope.project(module.identity()),EntityKind.METHOD,JavaSymbolName.method(owner,"f",List.of()).canonicalName(),Optional.of(new SourceSpan(source.document().identity(),1,11,1,27)));target=Entity.create(EntityOrigin.PROJECT,EntityScope.project(module.identity()),EntityKind.METHOD,JavaSymbolName.method(owner,"g",List.of()).canonicalName(),Optional.of(new SourceSpan(source.document().identity(),1,28,1,38)));
        occurrence=RelationshipOccurrence.create(SemanticRelationship.create(caller.identity(),new RelationshipKind("java.calls"),new RelationshipTarget.Resolved(target.identity())),new SourceSpan(source.document().identity(),1,21,1,24),0,SemanticStatus.RESOLVED,new Derivation(DerivationKind.DIRECT,provider,List.of()),List.of(),List.of());
    }
    ContentHandle put(Object data)throws Exception{return authority.store.put(CanonicalJson.write(data));}
    Map<String,Object> signed(Map<String,Object> data,String subject)throws Exception{var proof=authority.proof(scope,"java.semantic-occurrence",subject,CanonicalJson.write(data),List.of());proofs.put(proof.identity(),proof);var result=new TreeMap<>(data);result.put("proof",proof.identity());return result;}
    JavaEvidenceTables.Table inputTable(List<ContentHandle> payloads)throws Exception{
        var codec=new JavaEvidenceCodec();var rows=new ArrayList<JavaEvidenceRecord>();for(var payload:payloads){var values=new LinkedHashMap<String,Object>();values.put("schema","java-evidence-input:m8.4-v1");values.put("role","SOURCE");values.put("content",payload);values.put("locator",null);values.put("provider",new VersionedIdentifier("compiler.authored-index","1"));rows.add(codec.create(values));}rows.sort(JavaEvidenceTables::compare);return tables.write("java-evidence-input",rows.iterator(),()->false);
    }
    Map<String,Object> answer(Entity expected)throws Exception{return Map.of("schema","java-semantic-occurrence:m8.4-v1","inputView",view,"analysis",request.manifest().identity(),"document",source.document().identity(),"category","java.calls","span",occurrence.span(),"caller",put(caller),"targets",List.of(put(expected)),"status","RESOLVED");}
    String subject(Map<String,Object> row){return CanonicalJson.sha256(Map.of("schema","java-semantic-location:m8.4-v1","scope",scope,"inputView",view,"analysis",request.manifest().identity(),"document",row.get("document"),"category",row.get("category"),"span",row.get("span"))).value();}
    JavaSemanticEvidence.Bundle bundle(Entity expected,boolean closed)throws Exception{
        var answer=answer(expected);var occurrences=inputTable(List.of(put(signed(answer,subject(answer)))));var categories=new TreeMap<String,Long>();FrontendRequest.CATEGORIES.forEach(c->categories.put("java."+c,c.equals("calls")?1L:0L));
        var document=Map.of("schema","java-semantic-document:m8.4-v1","document",source.document().identity(),"content",new ContentHandle(source.document().contentDigest(),source.bytes().length),"disposition",closed?"CLOSED":"OPAQUE","categories",categories,"occurrences",occurrences.wire());var documents=inputTable(List.of(put(document)));
        var census=Map.of("schema","java-semantic-census:m8.4-v1","scope",scope,"inputView",view,"analysis",request.manifest().identity(),"provider",new VersionedIdentifier("compiler.authored-index","1"),"documents",documents.wire());String key=CanonicalJson.sha256(Map.of("schema","java-semantic-census-key:m8.4-v1","scope",scope,"inputView",view,"analysis",request.manifest().identity())).value();
        return new JavaSemanticEvidence.Bundle(authority.store,put(signed(census,key)),proofs,100,()->false);
    }
    FrontendResult actual(boolean emitted){
        var declarations=List.of(caller,target).stream().map(e->new DeclarationRecord(e,"fixture",SemanticStatus.RESOLVED,new Derivation(DerivationKind.DIRECT,provider,List.of()),List.of(),List.of())).toList();
        var observations=emitted?List.of(new ObservationRecord(source.document().identity(),new RelationshipKind("java.calls"),Optional.of(occurrence.span()),SemanticStatus.RESOLVED,ObservationRecord.EvidenceState.VERIFIED,ObservationRecord.EvidenceState.VERIFIED,Optional.of(occurrence.identity()),"g()",List.of())):List.<ObservationRecord>of();
        var coverage=FrontendRequest.CATEGORIES.stream().map(c->new CategoryCoverage(new RelationshipKind("java."+c),CategoryCoverage.Support.PARTIAL,emitted&&c.equals("calls")?1:0,emitted&&c.equals("calls")?1:0,0)).toList();return new FrontendResult(request.manifest().identity(),provider,FrontendResult.State.COMPLETED,declarations,emitted?List.of(occurrence):List.of(),observations,List.of(new SourceOutcome(source.document().identity(),SourceOutcome.State.PROCESSED,List.of())),coverage,List.of());
    }
    @Test void anIndependentSignedCensusCanCloseOnlyTheExactMappedOccurrence()throws Exception{
        assertEquals("void f(){ g(); }",source.text().substring(10,26));assertEquals("void g(){}",source.text().substring(27,37));assertEquals("g()",source.text().substring(20,23));
        var result=new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),bundle(target,true));assertEquals(0,result.active());
        assertEquals(2,result.publication().current().rows().size());assertTrue(result.gaps().isEmpty());assertTrue(result.current());
        assertTrue(result.evidence().stream().anyMatch(e->e.family().equals("java.evidence.post-projection")));
    }
    @Test void anEmptySuccessfulFrontendCannotEraseAnIndependentlyEnumeratedCall()throws Exception{
        var result=new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(false),bundle(target,true));assertTrue(result.active()>0);assertFalse(result.gaps().isEmpty());
    }
    @Test void aWrongTargetOrOriginCannotBeClosedByMatchingSpelling()throws Exception{
        var wrong=new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),bundle(caller,true));assertTrue(wrong.active()>0);
        var impostor=Entity.create(EntityOrigin.DEPENDENCY,EntityScope.external(EntityOrigin.DEPENDENCY,"fixture:impostor:1",view),EntityKind.METHOD,target.canonicalName(),Optional.empty());
        assertTrue(new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),bundle(impostor,true)).active()>0);
    }
    @Test void opaqueDocumentsAndUnsignedCensusesDoNotCloseTheDenominator()throws Exception{
        var opaque=new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),bundle(target,false));assertTrue(opaque.active()>0);assertEquals("OPEN",opaque.publication().projection().text("denominator"));
        var signed=bundle(target,true);var unsigned=new JavaSemanticEvidence.Bundle(signed.content(),signed.census(),Map.of(),100,()->false);assertTrue(new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),unsigned).active()>0);
    }
    @Test void discoveredContraryProofsCannotBeHiddenByChoosingTheMatchingAnswer()throws Exception{
        bundle(caller,true);var result=new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),bundle(target,true));assertTrue(result.publication().current().rows().values().stream().anyMatch(row->row.state()==JavaEvidenceLedger.State.CONFLICTED));assertTrue(result.active()>0);
        var retained=result.evidence().stream().map(e->ContentDigest.sha256Utf8(e.canonicalJson())).collect(java.util.stream.Collectors.toSet());
        for(var row:result.publication().current().rows().values())for(var proof:row.proofs())assertTrue(retained.contains(proofs.get(proof).answer()),"Every discovered conflicting answer must remain inspectable in the graph");
    }
    @Test void changedViewsBudgetCancellationAndMissingCasCannotAuthorizeReuse()throws Exception{
        var captured=bundle(target,true);assertThrows(java.io.IOException.class,()->new JavaSemanticEvidence().reconcile(scope,ContentDigest.sha256Utf8("other-view"),authority.verifier.policyIdentity(),request,actual(true),captured));
        assertThrows(java.io.IOException.class,()->new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),captured,Set.of("compiler.authored-index")),"An attribution producer cannot certify its own independent denominator");
        var small=new JavaSemanticEvidence.Bundle(captured.content(),captured.census(),captured.proofs(),1,()->false);assertThrows(java.io.IOException.class,()->new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),small));
        var cancelled=new JavaSemanticEvidence.Bundle(captured.content(),captured.census(),captured.proofs(),100,()->true);assertThrows(java.io.IOException.class,()->new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),cancelled));
        var result=new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),captured);authority.store.blobs.remove(captured.census().digest());assertFalse(result.current());assertThrows(java.io.IOException.class,()->new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),captured));
    }
    @Test void replayPreservesHistoryAndReopensRevokedOccurrenceEvidence()throws Exception{
        var captured=bundle(target,true);var engine=new JavaSemanticEvidence();var first=engine.reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),captured);
        var resumed=new JavaSemanticEvidence.Bundle(captured.content(),captured.census(),captured.proofs(),100,()->false,Optional.of(first.publication()));
        var replay=engine.reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),resumed);
        assertEquals(first.publication().projection().identity(),replay.publication().projection().identity());assertEquals(first.publication().ledger().identity(),replay.publication().ledger().digest("previous"));
        String occurrenceSubject=subject(answer(target));var occurrenceProof=captured.proofs().values().stream().filter(p->p.record().text("subject").equals(occurrenceSubject)).findFirst().orElseThrow();byte[] original=authority.store.blobs.remove(occurrenceProof.answer());
        var reopened=engine.reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),resumed);assertTrue(reopened.active()>0);assertTrue(reopened.publication().current().rows().values().stream().anyMatch(r->r.state()==JavaEvidenceLedger.State.REOPENED));assertFalse(first.current());
        var oldDecisions=JavaEvidenceTables.Table.from(first.publication().ledger().fields().get("decisions"));var newDecisions=JavaEvidenceTables.Table.from(reopened.publication().ledger().fields().get("decisions"));assertTrue(newDecisions.rows()>=oldDecisions.rows());
        authority.store.blobs.put(occurrenceProof.answer(),original);var recovery=new JavaSemanticEvidence.Bundle(captured.content(),captured.census(),captured.proofs(),100,()->false,Optional.of(reopened.publication()));assertEquals(0,engine.reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),recovery).active());
    }
    JavaSemanticEvidence.Bundle changedDocument(JavaSemanticEvidence.Bundle captured,java.util.function.Consumer<Map<String,Object>> change)throws Exception{
        var census=new TreeMap<>(com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(new java.io.InputStreamReader(authority.store.open(captured.census()),StandardCharsets.UTF_8),com.evolution.analysis.graph.GraphJson.Limits.defaults())));
        var docs=new ArrayList<JavaEvidenceRecord>();tables.read(JavaEvidenceTables.Table.from(census.get("documents")),docs::add,()->false);
        var handle=JavaEvidenceRecord.handle(docs.getFirst().fields().get("content"));var doc=new TreeMap<>(com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(new java.io.InputStreamReader(authority.store.open(handle),StandardCharsets.UTF_8),com.evolution.analysis.graph.GraphJson.Limits.defaults())));
        change.accept(doc);census.put("documents",inputTable(List.of(put(doc))).wire());return new JavaSemanticEvidence.Bundle(authority.store,put(census),captured.proofs(),100,()->false);
    }
    @Test void missingCategoriesWrongCountsAndChangedSourceCannotDisappear()throws Exception{
        var captured=bundle(target,true);var engine=new JavaSemanticEvidence();
        var missing=changedDocument(captured,doc->{var categories=new TreeMap<>(com.evolution.analysis.graph.GraphJson.object(doc.get("categories")));categories.remove("java.calls");doc.put("categories",categories);});
        assertThrows(java.io.IOException.class,()->engine.reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),missing));
        var counts=changedDocument(captured,doc->{var categories=new TreeMap<>(com.evolution.analysis.graph.GraphJson.object(doc.get("categories")));categories.put("java.calls",2);doc.put("categories",categories);});
        assertThrows(java.io.IOException.class,()->engine.reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),counts));
        var foreign=put(Map.of("other","source"));var changed=changedDocument(captured,doc->doc.put("content",foreign));assertThrows(java.io.IOException.class,()->engine.reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),changed));
    }
    @Test void postGraphRejectsForeignViewMissingFrontendAndRevokedEvidence()throws Exception{
        var captured=bundle(target,true);var post=new JavaSemanticEvidence().reconcile(scope,view,authority.verifier.policyIdentity(),request,actual(true),captured);
        var snapshot=request.manifest().snapshot().identity();var graph=new JavaEvidenceGraph(snapshot,snapshot,List.of(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.view",viewPayload))).withPostAnalysis(post);graph.requireCurrent();
        assertThrows(IllegalArgumentException.class,()->graph.validateFor(Optional.empty()));assertThrows(IllegalArgumentException.class,()->graph.withPostAnalysis(post));
        assertThrows(IllegalArgumentException.class,()->new JavaEvidenceGraph(snapshot,snapshot,List.of()).withPostAnalysis(post));
        authority.store.blobs.remove(captured.census().digest());assertThrows(java.io.IOException.class,graph::requireCurrent);
    }
}
