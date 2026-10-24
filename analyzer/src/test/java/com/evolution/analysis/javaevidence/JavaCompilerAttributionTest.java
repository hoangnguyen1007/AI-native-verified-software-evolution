package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independently authored call labels; no native compiler qualification claim. */
class JavaCompilerAttributionTest {
    final JavaSemanticEvidenceTest f=new JavaSemanticEvidenceTest();
    final VersionedIdentifier compiler=new VersionedIdentifier("compiler.authored-index","1");
    JavaCompilerAttributionTest()throws Exception{}
    JavaCompilerAttribution.Bundle bundle(Entity target,boolean recovery)throws Exception{
        return bundle(target,recovery,row->{});
    }
    JavaCompilerAttribution.Bundle bundle(Entity target,boolean recovery,java.util.function.Consumer<Map<String,Object>> change)throws Exception{
        var row=new TreeMap<String,Object>();row.put("schema","java-compiler-attribution-row:m8.4-v1");row.put("scope",f.scope);row.put("inputView",f.view);row.put("primary",f.provider);row.put("provider",compiler);row.put("document",f.source.document().identity());row.put("sourceContent",new ContentHandle(f.source.document().contentDigest(),f.source.bytes().length));row.put("category","java.calls");row.put("span",f.occurrence.span());row.put("caller",f.put(f.caller));row.put("targets",List.of(f.put(target)));row.put("status","RESOLVED");row.put("recovery",recovery);
        change.accept(row);var proof=f.authority.proof(f.scope,"java.semantic-occurrence",JavaCompilerAttribution.subject(f.scope,f.view,(SourceDocumentIdentity)row.get("document"),(String)row.get("category"),(com.evolution.analysis.contract.source.SourceSpan)row.get("span")).value(),CanonicalJson.write(row),List.of());f.proofs.put(proof.identity(),proof);
        var index=Map.of("schema","java-compiler-attribution:m8.4-v1","scope",f.scope,"inputView",f.view,"primary",f.provider,"provider",compiler,"rows",f.inputTable(List.of(f.put(row))).wire());
        return new JavaCompilerAttribution.Bundle(f.authority.store,f.put(index),f.proofs,100,()->false);
    }
    FrontendRequest request(JavaCompilerAttribution.Bundle bundle){
        var old=f.request.manifest();var options=new TreeMap<>(old.configuration().values());options.putAll(bundle.options(f.authority.verifier.policyIdentity()));
        var manifest=AnalysisManifest.create(old.manifestVersion(),old.snapshot(),old.modules(),old.classpath(),AnalysisConfiguration.create(old.configuration().schema(),options),old.analyzer(),old.ruleSet(),old.graphSchema());
        return new FrontendRequest(manifest,f.request.module(),f.request.sourceSet(),f.request.plan(),f.request.sources(),f.request.platform(),f.request.dependencies());
    }
    FrontendResult primary(FrontendRequest request,SemanticStatus status){
        var old=f.actual(true);var uncertainty=new Uncertainty("java.unresolved","Primary binding unavailable",List.of("compiler.binding"));
        var relation=SemanticRelationship.create(f.caller.identity(),new RelationshipKind("java.calls"),status==SemanticStatus.RESOLVED?new RelationshipTarget.Resolved(f.target.identity()):new RelationshipTarget.Unresolved("g()"));
        var occurrence=RelationshipOccurrence.create(relation,f.occurrence.span(),0,status,new Derivation(DerivationKind.DIRECT,f.provider,List.of()),status==SemanticStatus.RESOLVED?List.of():List.of(uncertainty),List.of());
        var observation=new ObservationRecord(f.source.document().identity(),new RelationshipKind("java.calls"),Optional.of(f.occurrence.span()),status,ObservationRecord.EvidenceState.VERIFIED,ObservationRecord.EvidenceState.VERIFIED,Optional.of(occurrence.identity()),"g()",List.of());
        return new FrontendResult(request.manifest().identity(),f.provider,old.state(),old.declarations(),List.of(occurrence),List.of(observation),old.sources(),old.coverage(),old.diagnostics());
    }
    @Test void verifiedCompilerAnswerResolvesOnlyTheRecordedUnresolvedCall()throws Exception{
        var bundle=bundle(f.target,false);var request=request(bundle);var before=primary(request,SemanticStatus.UNRESOLVED);
        var result=new JavaCompilerAttribution().apply(f.scope,f.view,f.authority.verifier.policyIdentity(),request,before,bundle);var after=result.frontend();
        assertEquals(JavaCompilerAttribution.PROVIDER,after.frontend());assertEquals(SemanticStatus.RESOLVED,after.observations().getFirst().attribution());
        assertEquals(new RelationshipTarget.Resolved(f.target.identity()),after.occurrences().getFirst().relationship().target());assertEquals(f.occurrence.span(),after.occurrences().getFirst().span());
        assertEquals(before.declarations(),after.declarations());assertEquals(DerivationKind.DERIVED,after.occurrences().getFirst().derivation().kind());assertEquals(SemanticStatus.UNRESOLVED,before.observations().getFirst().attribution());
        assertTrue(result.gaps().isEmpty());assertTrue(result.current());assertTrue(result.evidence().stream().anyMatch(e->e.family().equals("java.evidence.compiler-primary")));
        assertEquals(after,new JavaCompilerAttribution().apply(f.scope,f.view,f.authority.verifier.policyIdentity(),request,before,bundle).frontend());
    }
    JavaCompilerAttribution.Result apply(JavaCompilerAttribution.Bundle bundle,SemanticStatus status)throws Exception{var request=request(bundle);return new JavaCompilerAttribution().apply(f.scope,f.view,f.authority.verifier.policyIdentity(),request,primary(request,status),bundle);}
    @Test void compilerCannotOverwriteAResolvedContradictoryPrimaryTarget()throws Exception{
        var result=apply(bundle(f.caller,false),SemanticStatus.RESOLVED);assertEquals(new RelationshipTarget.Resolved(f.target.identity()),result.frontend().occurrences().getFirst().relationship().target());assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("SOURCE_BINARY_CONFLICT")));
    }
    @Test void aHiddenContraryProofCannotBeDiscardedBySelectingAnIndex()throws Exception{
        bundle(f.caller,false);var result=apply(bundle(f.target,false),SemanticStatus.UNRESOLVED);assertEquals(SemanticStatus.UNRESOLVED,result.frontend().observations().getFirst().attribution());assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("SOURCE_BINARY_CONFLICT")));
    }
    @Test void recoveryAndNonresolvedCompilerAnswersCannotBecomeResolvedFacts()throws Exception{
        var recovery=apply(bundle(f.target,true),SemanticStatus.UNRESOLVED);assertFalse(recovery.gaps().isEmpty());assertEquals(SemanticStatus.UNRESOLVED,recovery.frontend().observations().getFirst().attribution());
        var absent=bundle(f.target,false,row->{row.put("status","UNRESOLVED");row.put("targets",List.of());});assertEquals(SemanticStatus.UNRESOLVED,apply(absent,SemanticStatus.UNRESOLVED).frontend().observations().getFirst().attribution());
    }
    @Test void missingProofOrAnUnrequestedLocationPreservesTheGap()throws Exception{
        var captured=bundle(f.target,false);var unsigned=new JavaCompilerAttribution.Bundle(captured.content(),captured.index(),Map.of(),100,()->false);assertFalse(apply(unsigned,SemanticStatus.UNRESOLVED).gaps().isEmpty());
        var extra=bundle(f.target,false,row->row.put("span",new com.evolution.analysis.contract.source.SourceSpan(f.source.document().identity(),1,20,1,24)));assertEquals(SemanticStatus.UNRESOLVED,apply(extra,SemanticStatus.UNRESOLVED).frontend().observations().getFirst().attribution());
    }
    @Test void unboundChangedSourceBudgetAndCancellationCannotAuthorizeTheIndex()throws Exception{
        var captured=bundle(f.target,false);var engine=new JavaCompilerAttribution();var request=request(captured);
        assertThrows(java.io.IOException.class,()->engine.apply(f.scope,f.view,f.authority.verifier.policyIdentity(),f.request,f.actual(true),captured));
        var changed=bundle(f.target,false,row->row.put("sourceContent",new ContentHandle(ContentDigest.sha256Utf8("wrong bytes"),11)));assertThrows(java.io.IOException.class,()->apply(changed,SemanticStatus.UNRESOLVED));
        var cancelled=new JavaCompilerAttribution.Bundle(captured.content(),captured.index(),captured.proofs(),100,()->true);assertThrows(java.io.IOException.class,()->engine.apply(f.scope,f.view,f.authority.verifier.policyIdentity(),request,primary(request,SemanticStatus.UNRESOLVED),cancelled));
    }
    @Test void proofRevocationInvalidatesPreviouslyAppliedResults()throws Exception{
        var captured=bundle(f.target,false);var result=apply(captured,SemanticStatus.UNRESOLVED);var proof=captured.proofs().values().iterator().next();f.authority.store.blobs.remove(proof.answer());assertFalse(result.current());assertThrows(java.io.IOException.class,()->apply(captured,SemanticStatus.UNRESOLVED));
    }
    @Test void aDeclaredTypeCannotBeUsedAsTheTargetOfAMethodCall()throws Exception{
        var type=Entity.create(EntityOrigin.PROJECT,EntityScope.project(f.request.module()),EntityKind.TYPE,JavaSymbolName.topLevelType("","A").canonicalName(),Optional.of(new com.evolution.analysis.contract.source.SourceSpan(f.source.document().identity(),1,1,1,f.source.text().length()+1)));
        var captured=bundle(type,false);var request=request(captured);var p=primary(request,SemanticStatus.UNRESOLVED);var declarations=new ArrayList<>(p.declarations());declarations.add(new DeclarationRecord(type,"A",SemanticStatus.RESOLVED,new Derivation(DerivationKind.DIRECT,f.provider,List.of()),List.of(),List.of()));
        var withType=new FrontendResult(p.analysis(),p.frontend(),p.state(),declarations,p.occurrences(),p.observations(),p.sources(),p.coverage(),p.diagnostics());
        var result=new JavaCompilerAttribution().apply(f.scope,f.view,f.authority.verifier.policyIdentity(),request,withType,captured);assertEquals(SemanticStatus.UNRESOLVED,result.frontend().observations().getFirst().attribution());assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("SOURCE_BINARY_CONFLICT")));
    }
    @Test void partialSourceCannotPromoteCompilerRecoveryIntoASoundBinding()throws Exception{
        var captured=bundle(f.target,false);var request=request(captured);var p=primary(request,SemanticStatus.UNRESOLVED);var diagnostic=new Diagnostic(DiagnosticSeverity.ERROR,"java.parse-error","Authored malformed source",Optional.of(f.occurrence.span()),Map.of());
        var partial=new FrontendResult(p.analysis(),p.frontend(),FrontendResult.State.PARTIAL,p.declarations(),p.occurrences(),p.observations(),List.of(new SourceOutcome(f.source.document().identity(),SourceOutcome.State.PARTIAL,List.of(diagnostic))),p.coverage(),List.of(diagnostic));
        var result=new JavaCompilerAttribution().apply(f.scope,f.view,f.authority.verifier.policyIdentity(),request,partial,captured);assertEquals(p.declarations(),result.frontend().declarations());assertEquals(SemanticStatus.UNRESOLVED,result.frontend().observations().getFirst().attribution());assertFalse(result.gaps().isEmpty());
    }
    @Test void aGenericReturnCanResolveToAnExistingTypeParameterWithoutInventingAClass()throws Exception{
        String text="class A<T> { T value(){ return null; } }";
        var module=f.request.manifest().modules().getFirst();byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var document=com.evolution.analysis.contract.source.SourceDocument.create(module.repository(),module,"fixture/A.java",ContentDigest.sha256(bytes),com.evolution.analysis.contract.source.SourceClassification.MAIN);
        var source=new SourceInput(document,bytes);f.authority.store.put(new java.io.ByteArrayInputStream(bytes),()->false);
        var snapshot=com.evolution.analysis.contract.source.RepositorySnapshot.create(module.repository(),Optional.empty(),false,
                List.of(com.evolution.analysis.contract.source.SnapshotFile.from(document)),List.of(document));
        var scope=new TreeMap<>(f.scope);scope.put("baseSnapshot",snapshot.identity().value());
        var view=CanonicalJson.sha256(Map.of("fixture","authored generic return","source",document));
        int methodStart=text.indexOf("T value()"),parameterStart=text.indexOf("<T>")+1;
        var site=new com.evolution.analysis.contract.source.SourceSpan(document.identity(),1,methodStart+1,1,methodStart+2);
        var owner=JavaSymbolName.topLevelType("","A");
        var caller=Entity.create(EntityOrigin.PROJECT,EntityScope.project(module.identity()),EntityKind.METHOD,JavaSymbolName.method(owner,"value",List.of()).canonicalName(),
                Optional.of(new com.evolution.analysis.contract.source.SourceSpan(document.identity(),1,methodStart+1,1,text.indexOf('}',methodStart)+2)));
        var target=Entity.create(EntityOrigin.PROJECT,EntityScope.project(module.identity()),EntityKind.TYPE_PARAMETER,JavaSymbolName.typeParameter(owner,0).canonicalName(),
                Optional.of(new com.evolution.analysis.contract.source.SourceSpan(document.identity(),1,parameterStart+1,1,parameterStart+2)));
        var row=new TreeMap<String,Object>();row.put("schema","java-compiler-attribution-row:m8.4-v1");row.put("scope",scope);row.put("inputView",view);row.put("primary",f.provider);row.put("provider",compiler);
        row.put("document",document.identity());row.put("sourceContent",new ContentHandle(document.contentDigest(),bytes.length));row.put("category","java.returns");row.put("span",site);
        row.put("caller",f.put(caller));row.put("targets",List.of(f.put(target)));row.put("status","RESOLVED");row.put("recovery",false);
        var proof=f.authority.proof(scope,"java.semantic-occurrence",JavaCompilerAttribution.subject(scope,view,document.identity(),"java.returns",site).value(),CanonicalJson.write(row),List.of());
        var index=f.put(Map.of("schema","java-compiler-attribution:m8.4-v1","scope",scope,"inputView",view,"primary",f.provider,"provider",compiler,"rows",f.inputTable(List.of(f.put(row))).wire()));
        var bundle=new JavaCompilerAttribution.Bundle(f.authority.store,index,Map.of(proof.identity(),proof),100,()->false);
        var options=new TreeMap<>(FrontendRequest.options(module.identity(),com.evolution.analysis.contract.source.SourceClassification.MAIN,List.of(document)));
        options.putAll(bundle.options(f.authority.verifier.policyIdentity()));options.put("java.evidence-view",view.value());options.put("java.native-context",(String)scope.get("context"));options.put("ingestion.source-set-name","main");
        var old=f.request.manifest();var manifest=AnalysisManifest.create(old.manifestVersion(),snapshot,List.of(module),old.classpath(),AnalysisConfiguration.create(old.configuration().schema(),options),old.analyzer(),old.ruleSet(),old.graphSchema());
        var request=new FrontendRequest(manifest,module.identity(),com.evolution.analysis.contract.source.SourceClassification.MAIN,List.of(source),f.request.platform(),List.of());
        var derivation=new Derivation(DerivationKind.DIRECT,f.provider,List.of());var kind=new RelationshipKind("java.returns");
        var occurrence=RelationshipOccurrence.create(SemanticRelationship.create(caller.identity(),kind,new RelationshipTarget.Unresolved("T")),site,0,SemanticStatus.UNRESOLVED,derivation,List.of(new Uncertainty("java.unresolved","Type binding unavailable",List.of("compiler.binding"))),List.of());
        var observation=new ObservationRecord(document.identity(),kind,Optional.of(site),SemanticStatus.UNRESOLVED,ObservationRecord.EvidenceState.VERIFIED,ObservationRecord.EvidenceState.VERIFIED,Optional.of(occurrence.identity()),"T",List.of());
        var declarations=List.of(caller,target).stream().map(e->new DeclarationRecord(e,"generic fixture",SemanticStatus.RESOLVED,derivation,List.of(),List.of())).toList();
        var coverage=FrontendRequest.CATEGORIES.stream().map(c->new CategoryCoverage(new RelationshipKind("java."+c),CategoryCoverage.Support.PARTIAL,c.equals("returns")?1:0,c.equals("returns")?1:0,0)).toList();
        var primary=new FrontendResult(manifest.identity(),f.provider,FrontendResult.State.COMPLETED,declarations,List.of(occurrence),List.of(observation),List.of(new SourceOutcome(document.identity(),SourceOutcome.State.PROCESSED,List.of())),coverage,List.of());
        var result=new JavaCompilerAttribution().apply(scope,view,f.authority.verifier.policyIdentity(),request,primary,bundle);
        assertEquals(SemanticStatus.RESOLVED,result.frontend().observations().getFirst().attribution());
        assertEquals(new RelationshipTarget.Resolved(target.identity()),result.frontend().occurrences().getFirst().relationship().target());
        assertEquals(primary.declarations(),result.frontend().declarations());assertTrue(result.gaps().isEmpty());
    }
}
