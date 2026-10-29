package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.EvidenceVerifier;
import java.security.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticRootCensusTest {
    static final class MemoryIndex implements SemanticRootCensus.Index {
        final Map<String,TreeMap<String,SemanticTables.Row>> values=new HashMap<>();final Set<String> marked=new HashSet<>();
        public void put(String space,String key,SemanticTables.Row row)throws IOException{if(values.computeIfAbsent(space,s->new TreeMap<>()).putIfAbsent(key,row)!=null)throw new IOException("SEMANTIC_ROOT_INDEX_DUPLICATE");}
        public Optional<SemanticTables.Row> get(String space,String key){return Optional.ofNullable(values.getOrDefault(space,new TreeMap<>()).get(key));}
        public void mark(String space,String key)throws IOException{if(get(space,key).isEmpty())throw new IOException("SEMANTIC_ROOT_INDEX_MISSING");marked.add(space+"/"+key);}
        public SemanticRootCensus.Rows rows(String space,boolean unmarked){var iterator=values.getOrDefault(space,new TreeMap<>()).entrySet().stream().filter(e->!unmarked||!marked.contains(space+"/"+e.getKey())).map(Map.Entry::getValue).toList().iterator();return new SemanticRootCensus.Rows(){public SemanticTables.Row next(){return iterator.hasNext()?iterator.next():null;}public void close(){}};}
        public void close(){}
    }
    static final class Fixture {
        final SemanticReconcilerTest.Fixture f=new SemanticReconcilerTest.Fixture();final SemanticContextCoordinator coordinator=new SemanticContextCoordinator(f.content,f.tables);final SemanticContextCoordinator.Plan plan;final SemanticTables.Row entry;final ContentHandle source;final SemanticTables.Row document;final SemanticRootCensus census=new SemanticRootCensus(f.content,f.tables);
        Fixture()throws Exception{this(null);}
        Fixture(ContentDigest policy)throws Exception{
            if(policy!=null){var r=f.request;f.request=new LanguageSemanticRequest(r.context(),r.inventory(),r.root(),r.module(),r.sourceSet(),r.variant(),r.language(),r.dialect(),r.documents(),r.census(),r.resolution(),r.provider(),r.tool(),policy,r.workPolicy());}
            try(var reader=f.tables.open(f.request.documents(),()->false)){document=reader.next();}source=LanguageSemanticRequest.handle(document.fields().get("content"));
            entry=new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"src",f.request.inventory(),Map.of("kind","FILE","disposition","ACQUIRED","content",source));
            var inventory=new PolyglotInventory.Report(f.request.inventory(),write(SemanticTables.Kind.DOCUMENT,List.of(entry)),1,0);
            plan=coordinator.plan(f.request.context(),inventory,List.of(new SemanticContextCoordinator.Selection("main",f.request)).iterator(),f.request.policy(),()->false);
        }
        SemanticTables.Table write(SemanticTables.Kind kind,List<SemanticTables.Row> rows)throws IOException{return f.tables.write(kind,rows.stream().sorted(Comparator.comparing(SemanticTables.Row::key)).iterator(),()->false);}
        SemanticTables.Row row(SemanticTables.Kind kind,String key,Map<String,Object> fields){return new SemanticTables.Row(kind,key,plan.identity(),fields);}
        SemanticTables.Row source(String disposition){return row(SemanticTables.Kind.CENSUS,"src",Map.of("entry",entry.identity(),"disposition",disposition,"reason","AUTHORED_SOURCE_MEMBERSHIP","evidence",source));}
        SemanticTables.Row context(){return row(SemanticTables.Kind.CONTEXT,"main",Map.of("disposition","SELECTED","request",f.request.identity(),"reason","AUTHORED_EFFECTIVE_CONTEXT","evidence",source));}
        SemanticTables.Row membership(String origin,String sourceKey,ContentHandle bytes){return row(SemanticTables.Kind.DOCUMENT,SemanticRootCensus.documentKey("main",document.key()),Map.of("context","main","document",document.key(),"documentIdentity",document.identity(),"origin",origin,"source",sourceKey,"content",bytes,"lineage",source));}
        SemanticRootCensus.Input input(List<SemanticTables.Row> sources,List<SemanticTables.Row> contexts,List<SemanticTables.Row> memberships)throws IOException{return new SemanticRootCensus.Input(write(SemanticTables.Kind.CENSUS,sources),write(SemanticTables.Kind.CONTEXT,contexts),write(SemanticTables.Kind.DOCUMENT,memberships),ContentDigest.sha256Utf8("authored-validator-tool"));}
        SemanticRootCensus.Result run(SemanticRootCensus.Input input)throws IOException{return census.reconcile(plan,input,new MemoryIndex(),Optional.empty(),Set.of(),plan::policy,()->false);}
        Set<String> reasons(SemanticRootCensus.Result result)throws IOException{var reasons=new TreeSet<String>();try(var reader=f.tables.open(result.gaps(),()->false)){SemanticTables.Row row;while((row=reader.next())!=null)reasons.add((String)row.fields().get("reasonCode"));}return reasons;}
    }
    @Test void exactSourceAndContextJoinClosesAccountingWithoutManufacturingAuthority()throws Exception{
        var f=new Fixture();var input=f.input(List.of(f.source("REQUIRED")),List.of(f.context()),List.of(f.membership("INVENTORY","src",f.source)));var result=f.run(input);
        assertTrue(result.structurallyClosed());assertEquals(1,result.accounting().entries());assertEquals(1,result.accounting().documents());assertEquals(1,result.accounting().memberships());assertFalse(result.qualified());assertEquals(Set.of("ROOT_SOURCE_CONTEXT_CENSUS_METHOD_UNQUALIFIED"),f.reasons(result));assertEquals(result,f.run(input));
    }
    @Test void sourceOrDocumentOmissionAndUnknownContextRemainInTheRootDenominator()throws Exception{
        var f=new Fixture();var missing=f.run(f.input(List.of(f.source("REQUIRED")),List.of(f.context()),List.of()));
        assertFalse(missing.structurallyClosed());assertEquals(2,missing.accounting().open());assertTrue(f.reasons(missing).containsAll(Set.of("ROOT_SOURCE_NOT_ASSIGNED","ROOT_DOCUMENT_MEMBERSHIP_OMITTED")));
        var unknown=f.row(SemanticTables.Kind.CONTEXT,"unknown",Map.of("disposition","UNKNOWN","request",ContentDigest.sha256Utf8("unknown-effective-context"),"reason","DYNAMIC_BUILD_CONTEXT","evidence",f.source));
        var unresolved=f.run(f.input(List.of(f.source("REQUIRED")),List.of(f.context(),unknown),List.of(f.membership("INVENTORY","src",f.source))));assertFalse(unresolved.structurallyClosed());assertTrue(f.reasons(unresolved).contains("ROOT_CONTEXT_ENUMERATION_OPEN"));
        var omitted=f.run(f.input(List.of(),List.of(),List.of()));assertTrue(f.reasons(omitted).containsAll(Set.of("ROOT_SOURCE_DISPOSITION_OMITTED","ROOT_SELECTED_CONTEXT_OMITTED")));
    }
    @Test void forgedSourceBytesForeignContextAndDuplicateDocumentBindingCannotPass()throws Exception{
        var f=new Fixture();var wrong=f.f.content.text("different source");assertThrows(IOException.class,()->f.run(f.input(List.of(f.source("REQUIRED")),List.of(f.context()),List.of(f.membership("INVENTORY","src",wrong)))));
        var foreign=new TreeMap<String,Object>(f.membership("INVENTORY","src",f.source).fields());foreign.put("context","foreign");assertThrows(IOException.class,()->f.run(f.input(List.of(f.source("REQUIRED")),List.of(f.context()),List.of(f.row(SemanticTables.Kind.DOCUMENT,SemanticRootCensus.documentKey("foreign",f.document.key()),foreign)))));
        var absent=f.f.content.text("lineage");f.f.content.blobs.remove(absent.digest());var generated=new TreeMap<String,Object>(f.membership("GENERATED","",f.source).fields());generated.put("lineage",absent);assertThrows(IOException.class,()->f.run(f.input(List.of(f.source("DEFERRED")),List.of(f.context()),List.of(f.row(SemanticTables.Kind.DOCUMENT,SemanticRootCensus.documentKey("main",f.document.key()),generated)))));
    }
    @Test void generatedLineageIsRetainedAndCancellationOrPolicyChangeCannotClose()throws Exception{
        var f=new Fixture();var input=f.input(List.of(f.source("NON_SEMANTIC")),List.of(f.context()),List.of(f.membership("GENERATED","",f.source)));var result=f.run(input);assertTrue(result.structurallyClosed());assertEquals(1,result.accounting().generated());assertFalse(result.qualified());
        assertThrows(IOException.class,()->f.census.reconcile(f.plan,input,new MemoryIndex(),Optional.empty(),Set.of(),f.plan::policy,()->true));assertThrows(IOException.class,()->f.census.reconcile(f.plan,input,new MemoryIndex(),Optional.empty(),Set.of(),()->ContentDigest.sha256Utf8("new-policy"),()->false));
    }
    @Test void independentNativeInputCannotDisappearWhenExporterOmitsItsDocument()throws Exception{
        var f=new Fixture();var missing=new TreeMap<String,Object>(f.membership("INVENTORY","src",f.source).fields());missing.put("document","not-exported");missing.put("documentIdentity",null);var expected=f.row(SemanticTables.Kind.DOCUMENT,SemanticRootCensus.documentKey("main","not-exported"),missing);var result=f.run(f.input(List.of(f.source("REQUIRED")),List.of(f.context()),List.of(f.membership("INVENTORY","src",f.source),expected)));
        assertFalse(result.structurallyClosed());assertEquals(1,result.accounting().documents());assertEquals(2,result.accounting().memberships());assertEquals(1,result.accounting().open());assertTrue(f.reasons(result).contains("ROOT_NATIVE_DOCUMENT_OMITTED"));
    }
    @Test void onlyExactOpaqueRootPredicateCanQualifyAndRevocationReopensIt()throws Exception{
        // Ephemeral test authorities verify the protocol, not a production method.
        var generator=KeyPairGenerator.getInstance("Ed25519");var controller=generator.generateKeyPair();var validator=generator.generateKeyPair();var tool=ContentDigest.sha256Utf8("authored-validator-tool");var method=new EvidenceVerifier.Method(new VersionedIdentifier("authored.root-census","v1"),new VersionedIdentifier("polyglot.root-census","m8.4-v1"),tool,List.of("SEALED_SOURCE_MEMBERSHIP","EFFECTIVE_CONTEXT_CLOSURE","GENERATED_EXTERNAL_LINEAGE","EMBEDDED_SOURCE_MAPPING"));
        var verifier=new EvidenceVerifier(Map.of("controller",controller.getPublic()),Map.of("validator",validator.getPublic()),List.of(method));var f=new Fixture(verifier.policyIdentity());var input=f.input(List.of(f.source("REQUIRED")),List.of(f.context()),List.of(f.membership("INVENTORY","src",f.source)));var preliminary=f.run(input);
        var claim=f.f.content.text(CanonicalJson.write(SemanticRootCensus.proofPayload(f.plan,input,preliminary.accounting())));var expected=new EvidenceVerifier.Expected(ContentDigest.sha256Utf8("launch"),ContentDigest.sha256Utf8("profile"),1,f.plan.identity(),SemanticRootCensus.inputClosure(f.plan,input),claim,method.method(),method.predicate(),tool);var receipt=new EvidenceVerifier.Receipt("execution-protected-receipt:m8.4-v1","authored-root-control",1,expected.launch(),expected.profile(),ContentDigest.sha256Utf8("manifest"),ContentDigest.sha256Utf8("materials"),ContentDigest.sha256Utf8("termination"));var attestation=new EvidenceVerifier.Attestation("execution-protected-attestation:m8.4-v1",receipt.identity(),claim.digest(),expected.scope(),expected.inputClosure(),tool,method.method(),method.predicate(),method.requiredChecks(),true);
        var proof=verifier.verifyAccepted(expected,receipt,SemanticAnswerLedgerTest.Authority.sign("controller",receipt,controller.getPrivate()),attestation,SemanticAnswerLedgerTest.Authority.sign("validator",attestation,validator.getPrivate()),f.f.content).orElseThrow();var qualified=f.census.reconcile(f.plan,input,new MemoryIndex(),Optional.of(proof),Set.of(),f.plan::policy,()->false);assertTrue(qualified.qualified());assertEquals(0,qualified.gaps().rows());
        var revoked=f.census.reconcile(f.plan,input,new MemoryIndex(),Optional.of(proof),Set.of(proof.identity()),f.plan::policy,()->false);assertFalse(revoked.qualified());assertEquals(preliminary,revoked);assertTrue(qualified.structurallyClosed());
        var changed=new SemanticRootCensus.Input(input.sources(),input.contexts(),input.memberships(),ContentDigest.sha256Utf8("wrong-tool"));assertFalse(f.census.reconcile(f.plan,changed,new MemoryIndex(),Optional.of(proof),Set.of(),f.plan::policy,()->false).qualified());
    }
    @Test void rootAndChildPredicatesAreSeparateAndEachRevocationWithholdsCompletion()throws Exception{
        // Protocol-only fixtures. No keys or methods escape into runtime registration.
        var generator=KeyPairGenerator.getInstance("Ed25519");var controller=generator.generateKeyPair();var validator=generator.generateKeyPair();var baseline=new Fixture();var rootMethod=new EvidenceVerifier.Method(new VersionedIdentifier("authored.root","v1"),new VersionedIdentifier("polyglot.root-census","m8.4-v1"),ContentDigest.sha256Utf8("authored-validator-tool"),List.of("SEALED_SOURCE_MEMBERSHIP","EFFECTIVE_CONTEXT_CLOSURE","GENERATED_EXTERNAL_LINEAGE","EMBEDDED_SOURCE_MAPPING"));var censusMethod=new EvidenceVerifier.Method(new VersionedIdentifier("authored.census","v1"),new VersionedIdentifier("polyglot.census","m8.4-v1"),baseline.f.request.tool(),List.of("INDEPENDENT_CENSUS","DOCUMENT_MEMBERSHIP","CATEGORY_CLOSURE"));var observationsMethod=new EvidenceVerifier.Method(new VersionedIdentifier("authored.observations","v1"),new VersionedIdentifier("polyglot.observations","m8.4-v1"),baseline.f.request.tool(),List.of("CALLER_TARGET_ORIGIN","SOURCE_COORDINATES","APPLICABILITY_VALIDATED"));
        var verifier=new EvidenceVerifier(Map.of("controller",controller.getPublic()),Map.of("validator",validator.getPublic()),List.of(rootMethod,censusMethod,observationsMethod));var f=new Fixture(verifier.policyIdentity());var input=f.input(List.of(f.source("REQUIRED")),List.of(f.context()),List.of(f.membership("INVENTORY","src",f.source)));var accounting=f.run(input).accounting();var request=f.f.request;var output=f.f.emissions(f.f.requirements);
        var rootClaim=f.f.content.text(CanonicalJson.write(SemanticRootCensus.proofPayload(f.plan,input,accounting)));var rootProof=prove(verifier,rootMethod,rootClaim,f.plan.identity(),SemanticRootCensus.inputClosure(f.plan,input),controller,validator,f.f.content);var childClaim=f.f.content.text(CanonicalJson.write(SemanticReconciler.proofPayload(request,output)));var censusProof=prove(verifier,censusMethod,childClaim,request.scope(),request.inputClosure(),controller,validator,f.f.content);var observationsProof=prove(verifier,observationsMethod,childClaim,request.scope(),request.inputClosure(),controller,validator,f.f.content);var claims=Map.of("census",censusProof,"observations",observationsProof);var rootCensus=new SemanticContextCoordinator.RootCensus(input,MemoryIndex::new,Optional.of(rootProof));
        for(var revoked:List.of(Set.<ContentDigest>of(),Set.of(rootProof.identity()),Set.of(observationsProof.identity()))){
            var result=f.coordinator.runWithRootCensus(f.plan,r->Optional.of((r2,cancel)->output),(r,o)->claims,r->Optional.empty(),Optional.of(rootCensus),Map.of(),revoked,f.plan::policy,c->{},()->false);
            assertEquals(revoked.isEmpty(),result.complete());assertEquals(!revoked.contains(rootProof.identity()),result.selectionQualified());assertEquals(revoked.contains(observationsProof.identity())?1:0,result.incomplete());
        }
        var complete=f.coordinator.runWithRootCensus(f.plan,r->Optional.of((r2,cancel)->output),(r,o)->claims,r->Optional.empty(),Optional.of(rootCensus),Map.of(),Set.of(),f.plan::policy,c->{},()->false);
        var revocations=new java.util.concurrent.atomic.AtomicReference<Set<ContentDigest>>(Set.of());var validation=new SemanticPublicationValidation(f.f.content,f.f.tables,(r,o)->claims,(p,i)->Optional.of(rootProof),MemoryIndex::new,revocations::get,f.plan::policy);
        var capability=validation.validate(f.plan,complete.report(),()->false);assertTrue(capability.complete());assertTrue(capability.matches(f.plan.identity(),f.plan.policy(),complete.report()));assertFalse(capability.matches(f.plan.identity(),f.plan.policy(),f.f.content.text("{}")));assertThrows(IOException.class,()->validation.validate(f.plan,complete.report(),()->true));
        revocations.set(Set.of(observationsProof.identity()));assertFalse(capability.matches(f.plan.identity(),f.plan.policy(),complete.report()));assertThrows(IOException.class,()->validation.validate(f.plan,complete.report(),()->false));
        revocations.set(Set.of(rootProof.identity()));assertThrows(IOException.class,()->validation.validate(f.plan,complete.report(),()->false));
    }
    static EvidenceVerifier.VerifiedClaim prove(EvidenceVerifier verifier,EvidenceVerifier.Method method,ContentHandle claim,ContentDigest scope,ContentDigest closure,KeyPair controller,KeyPair validator,SemanticTablesTest.Memory content)throws Exception{
        var expected=new EvidenceVerifier.Expected(ContentDigest.sha256Utf8("launch"),ContentDigest.sha256Utf8("profile"),1,scope,closure,claim,method.method(),method.predicate(),method.tool());var receipt=new EvidenceVerifier.Receipt("execution-protected-receipt:m8.4-v1","authored-root-control",1,expected.launch(),expected.profile(),ContentDigest.sha256Utf8("manifest"),ContentDigest.sha256Utf8("materials"),ContentDigest.sha256Utf8("termination"));var attestation=new EvidenceVerifier.Attestation("execution-protected-attestation:m8.4-v1",receipt.identity(),claim.digest(),scope,closure,method.tool(),method.method(),method.predicate(),method.requiredChecks(),true);return verifier.verifyAccepted(expected,receipt,SemanticAnswerLedgerTest.Authority.sign("controller",receipt,controller.getPrivate()),attestation,SemanticAnswerLedgerTest.Authority.sign("validator",attestation,validator.getPrivate()),content).orElseThrow();
    }
}
