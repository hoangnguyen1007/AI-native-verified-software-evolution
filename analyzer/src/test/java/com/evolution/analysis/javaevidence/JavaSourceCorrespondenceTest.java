package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaSourceCorrespondenceTest {
    final JavaEvidenceTestStore.AuthorityFixture authority;
    final JavaEvidenceTestStore store;
    final JavaEvidenceTables tables;
    final JavaEvidenceCodec codec=new JavaEvidenceCodec();
    final Map<String,Object> scope=JavaEvidenceLedgerTest.scope();
    final SourceInput source;
    final Entity owner;
    final ContentHandle output;
    final JavaEvidenceRecord slot;
    JavaSourceCorrespondenceTest()throws Exception{
        authority=new JavaEvidenceTestStore.AuthorityFixture();store=authority.store;tables=new JavaEvidenceTables(store,100000);
        var repo=new RepositoryIdentity("repository:"+JavaEvidenceCodecTest.D);var module=new ModuleIdentity((String)scope.get("module"));
        byte[] raw="class Model { int handwritten(){ return 7; } }".getBytes(StandardCharsets.UTF_8);
        source=new SourceInput(new SourceDocument(SourceDocumentIdentity.from(repo,"Model.java"),repo,module,"Model.java",ContentDigest.sha256(raw),SourceClassification.MAIN),raw);
        owner=Entity.create(EntityOrigin.PROJECT,EntityScope.project(module),EntityKind.TYPE,JavaSymbolName.topLevelType("","Model").canonicalName(),Optional.of(new SourceSpan(source.document().identity(),1,1,1,46)));
        store.put(new java.io.ByteArrayInputStream(raw),()->false);output=store.put("authored bytecode endpoint; bytecode extraction is a separate adapter check");
        var data=new LinkedHashMap<String,Object>();data.put("schema","java-resolution-slot:m8.4-v1");data.put("scope",scope);data.put("space","CLASS_PATH");data.put("ordinal",0);data.put("logicalName","fixture:model:1@jar");data.put("kind","JAR");data.put("original",output);data.put("tree",null);data.put("selectedView",null);data.put("origin","PROJECT");data.put("module",module.value());data.put("proof",JavaEvidenceCodecTest.D);data.put("disposition","ACCEPTED");data.put("gaps",List.of());slot=codec.create(data);
    }
    ContentHandle put(Object data)throws Exception{return store.put(CanonicalJson.write(data));}
    JavaEvidenceRecord row(String descriptor)throws Exception{
        var endpoint=Map.of("schema","java-binary-member:m8.4-v1","slot",slot.identity(),"package","","owners",List.of("Model"),"kind","CONSTRUCTOR","name","<init>","descriptor",descriptor,"transformation",store.put("captured transformation inputs"));
        var data=new LinkedHashMap<String,Object>();data.put("schema","java-correspondence:m8.4-v1");data.put("scope",scope);data.put("sourceEvidence",put(owner));data.put("binaryOrGeneratedEvidence",put(endpoint));data.put("sourceContent",new ContentHandle(source.document().contentDigest(),source.bytes().length));data.put("outputContent",output);data.put("outcome","AUGMENTS");data.put("predicate",new VersionedIdentifier("java.source-binary-correspondence",JavaEvidenceAuthority.VERSION));data.put("proof",JavaEvidenceCodecTest.D);data.put("supportingSpans",List.of());data.put("reason",null);return codec.create(data);
    }
    JavaEvidenceTables.Table table(List<JavaEvidenceRecord> rows)throws Exception{
        var inputs=new ArrayList<JavaEvidenceRecord>();for(var row:rows){var data=new LinkedHashMap<String,Object>();data.put("schema","java-evidence-input:m8.4-v1");data.put("role","CORRESPONDENCE");data.put("content",put(row.fields()));data.put("locator",null);data.put("provider",new VersionedIdentifier("fixture.mapping","1"));inputs.add(codec.create(data));}inputs.sort(JavaEvidenceTables::compare);return tables.write("java-evidence-input",inputs.iterator(),()->false);
    }
    Map.Entry<JavaEvidenceRecord,JavaEvidenceAuthority.Verified> signed(JavaEvidenceRecord row)throws Exception{
        var proof=authority.proof("java.source-binary-correspondence",slot.identity().value(),CanonicalJson.write(JavaSourceCorrespondence.answerFields(scope,slot,List.of(row))),List.of());var data=new LinkedHashMap<>(row.fields());data.put("proof",proof.identity());return Map.entry(codec.create(data),proof);
    }
    @Test void acceptedMemberRequiresAProtectedExactCensusAndKeepsItsSourceOwner()throws Exception{
        var signed=signed(row("(I)V"));var result=new JavaSourceCorrespondence(store).importTable(scope,table(List.of(signed.getKey())),List.of(slot),List.of(source),Map.of(signed.getValue().identity(),signed.getValue()),10,()->false);
        assertEquals(1,result.size());
        assertEquals(owner,result.mappings().getFirst().source());assertEquals("(I)V",result.mappings().getFirst().endpoint().descriptor());assertEquals("Model",result.mappings().getFirst().endpoint().binaryName());assertTrue(result.current());
        store.blobs.remove(output.digest());assertFalse(result.current(),"A prepared mapping cannot outlive its captured binary bytes");
    }
    @Test void binaryMemberEvidenceHasAnExplicitSpaceOutsideTheCompilerClasspath(){
        var fields=new LinkedHashMap<>(slot.fields());fields.put("space","BINARY_EVIDENCE");fields.put("selectedView",output);
        var evidence=assertDoesNotThrow(()->codec.create(fields),"Post-compile member evidence must not be appended to the native compiler classpath");
        assertEquals("BINARY_EVIDENCE",evidence.text("space"));
    }
    @Test void aMemberViewRequiresBothShapeAndCorrespondenceProofsAndRevokesWithItsBytes()throws Exception{
        var shape=authority.proof("java.binary-shape",output.digest().value(),"authored native shape",List.of());
        var fields=new LinkedHashMap<>(slot.fields());fields.put("space","BINARY_EVIDENCE");fields.put("selectedView",output);fields.put("proof",shape.identity());var memberSlot=codec.create(fields);
        var endpoint=Map.of("schema","java-binary-member:m8.4-v1","slot",memberSlot.identity(),"package","","owners",List.of("Model"),"kind","CONSTRUCTOR","name","<init>","descriptor","(I)V","transformation",store.put("captured member transformation"));
        var data=new LinkedHashMap<>(row("(I)V").fields());data.put("binaryOrGeneratedEvidence",put(endpoint));var unsigned=codec.create(data);
        var correspondence=authority.proof("java.source-binary-correspondence",memberSlot.identity().value(),CanonicalJson.write(JavaSourceCorrespondence.answerFields(scope,memberSlot,List.of(unsigned))),List.of());data.put("proof",correspondence.identity());var input=table(List.of(codec.create(data)));
        assertThrows(java.io.IOException.class,()->new JavaSourceCorrespondence(store).importTable(scope,input,List.of(memberSlot),List.of(source),Map.of(correspondence.identity(),correspondence),10,()->false));
        assertThrows(java.io.IOException.class,()->new JavaSourceCorrespondence(store).importTable(scope,input,List.of(memberSlot),List.of(source),Map.of(correspondence.identity(),correspondence,shape.identity(),shape),10,()->false));
        var slotTable=tables.write("java-resolution-slot",List.of(memberSlot).iterator(),()->false);
        var context=authority.proof("java.resolution-context",(String)scope.get("context"),CanonicalJson.write(Map.of("scope",scope,"slots",slotTable.wire())),List.of());
        var accepted=new JavaSourceCorrespondence(store).importTable(scope,input,List.of(memberSlot),List.of(source),Map.of(correspondence.identity(),correspondence,shape.identity(),shape,context.identity(),context),10,()->false);
        assertEquals(Map.of(memberSlot.identity(),output),accepted.binaryViews());try(var bytes=accepted.openBinaryView(memberSlot.identity())){assertArrayEquals(store.blobs.get(output.digest()),bytes.readAllBytes());}
        store.blobs.remove(output.digest());assertFalse(accepted.current());assertThrows(java.io.IOException.class,()->accepted.openBinaryView(memberSlot.identity()));
    }
    @Test void producerMetadataOrAProofForAnotherCensusCannotAuthorizeMembers()throws Exception{
        var row=row("(I)V");var table=table(List.of(row));
        assertThrows(java.io.IOException.class,()->new JavaSourceCorrespondence(store).importTable(scope,table,List.of(slot),List.of(source),Map.of(),10,()->false));
        var signed=signed(row);var changed=new LinkedHashMap<>(signed.getKey().fields());changed.put("binaryOrGeneratedEvidence",row("(J)V").fields().get("binaryOrGeneratedEvidence"));var tampered=table(List.of(codec.create(changed)));
        assertThrows(java.io.IOException.class,()->new JavaSourceCorrespondence(store).importTable(scope,tampered,List.of(slot),List.of(source),Map.of(signed.getValue().identity(),signed.getValue()),10,()->false));
    }
    @Test void conflictsBadDescriptorsForeignSourcesAndDuplicateMembersAreNeverDropped()throws Exception{
        for(String descriptor:List.of("(V)V","(I)","([V)V","(I)Vjunk","(Lbad.name;)V","(I)I")){
            var signed=signed(row(descriptor));var table=table(List.of(signed.getKey()));
            assertThrows(java.io.IOException.class,()->new JavaSourceCorrespondence(store).importTable(scope,table,List.of(slot),List.of(source),Map.of(signed.getValue().identity(),signed.getValue()),10,()->false),descriptor);
        }
        var signed=signed(row("(I)V"));var conflict=new LinkedHashMap<>(signed.getKey().fields());conflict.put("outcome","CONFLICT");conflict.put("reason","MEMBER_MISMATCH");var conflicted=table(List.of(codec.create(conflict)));
        assertThrows(java.io.IOException.class,()->new JavaSourceCorrespondence(store).importTable(scope,conflicted,List.of(slot),List.of(source),Map.of(signed.getValue().identity(),signed.getValue()),10,()->false));
        var wrong=new LinkedHashMap<>(signed.getKey().fields());wrong.put("sourceContent",store.put("class Model { }"));var foreign=table(List.of(codec.create(wrong)));
        assertThrows(java.io.IOException.class,()->new JavaSourceCorrespondence(store).importTable(scope,foreign,List.of(slot),List.of(source),Map.of(signed.getValue().identity(),signed.getValue()),10,()->false));
        var duplicate=new LinkedHashMap<>(signed.getKey().fields());duplicate.put("reason","duplicate with separate audit reason");var duplicates=table(List.of(signed.getKey(),codec.create(duplicate)));
        assertThrows(java.io.IOException.class,()->new JavaSourceCorrespondence(store).importTable(scope,duplicates,List.of(slot),List.of(source),Map.of(signed.getValue().identity(),signed.getValue()),10,()->false));
    }
    @Test void foreignSlotsUnavailableReferencesAndCancellationWithholdTheCapability()throws Exception{
        var signed=signed(row("(I)V"));var table=table(List.of(signed.getKey()));
        assertThrows(java.io.IOException.class,()->new JavaSourceCorrespondence(store).importTable(scope,table,List.of(slot),List.of(source),Map.of(signed.getValue().identity(),signed.getValue()),10,()->true));
        assertThrows(java.io.IOException.class,()->new JavaSourceCorrespondence(store).importTable(scope,table,List.of(),List.of(source),Map.of(signed.getValue().identity(),signed.getValue()),10,()->false));
        store.blobs.remove(signed.getValue().answer());
        assertThrows(java.io.IOException.class,()->new JavaSourceCorrespondence(store).importTable(scope,table,List.of(slot),List.of(source),Map.of(signed.getValue().identity(),signed.getValue()),10,()->false));
    }
    @Test void aHiddenContraryCensusRequiresExplicitAdjudicationBeforeUse()throws Exception{
        var signed=signed(row("(I)V"));var contrary=authority.proof("java.source-binary-correspondence",slot.identity().value(),"different member census",List.of());var table=table(List.of(signed.getKey()));
        assertThrows(java.io.IOException.class,()->new JavaSourceCorrespondence(store).importTable(scope,table,List.of(slot),List.of(source),Map.of(signed.getValue().identity(),signed.getValue(),contrary.identity(),contrary),10,()->false));
        var adjudicated=authority.proof("java.source-binary-correspondence",slot.identity().value(),CanonicalJson.write(JavaSourceCorrespondence.answerFields(scope,slot,List.of(signed.getKey()))),List.of(JavaEvidenceRecord.handle(contrary.record().fields().get("answer"))));
        var data=new LinkedHashMap<>(signed.getKey().fields());data.put("proof",adjudicated.identity());var resolved=table(List.of(codec.create(data)));
        assertEquals(1,new JavaSourceCorrespondence(store).importTable(scope,resolved,List.of(slot),List.of(source),Map.of(adjudicated.identity(),adjudicated,contrary.identity(),contrary),10,()->false).size());
    }
}
