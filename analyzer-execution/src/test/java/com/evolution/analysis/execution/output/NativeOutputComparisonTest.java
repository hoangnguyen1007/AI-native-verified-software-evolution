package com.evolution.analysis.execution.output;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.StreamingContentStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeOutputComparisonTest {
    @TempDir Path root;
    final NativeOutputComparison comparison=new NativeOutputComparison();
    final LogicalPath output=LogicalPath.utf8("generated/Generated.java"),audit=LogicalPath.utf8("audit.json");
    ContentHandle put(StreamingContentStore store,String text)throws Exception{return store.put(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),()->false);}
    NativeOutputComparison.Revision revision(String attempt,String body,String inputs,boolean closed)throws Exception{
        return revision(attempt,body,inputs,closed,root.resolve("cas"));
    }
    NativeOutputComparison.Revision revision(String attempt,String body,String inputs,boolean closed,Path store)throws Exception{
        var content=new StreamingContentStore(store);var exactInputs=new NativeOutputComparison.Inputs(put(content,inputs),put(content,"fixed toolchain, recipe and environment"));
        var payload=put(content,body);var auditPayload=put(content,attempt);String nonce=ContentDigest.sha256Utf8(attempt).value().substring(7,39);
        var expected=new OutputCensus.Scope(attempt,nonce,new OutputCensus.Budget(65536,4096,65536));
        var rows=List.of(OutputCensusTest.file("audit.json",attempt),OutputCensusTest.file("generated/Generated.java",body));
        var text=rows.stream().map(CanonicalJson::write).collect(java.util.stream.Collectors.joining("\n","","\n"));
        var manifest=put(content,text);var scope=put(content,CanonicalJson.write(Map.of("schema","guest-output-scope:m8.4-v1","nonce",nonce,"work",OutputCensusTest.b64("/work/"+attempt),"budget",expected.budget())));
        var header=new TreeMap<String,Object>();header.put("schema","guest-output-census:m8.4-v2");header.put("attempt",attempt);header.put("nonce",nonce);header.put("entries",2);header.put("logicalBytes",payload.size()+auditPayload.size());header.put("capturedBytes",payload.size()+auditPayload.size());header.put("pending",0);header.put("rejected",0);header.put("inventoryClosed",true);header.put("captureClosed",closed);header.put("manifest",manifest);header.put("scope",scope);header.put("trust","PRODUCER_ASSERTION");
        return comparison.read(content,exactInputs,put(content,CanonicalJson.write(header)),expected,100,()->false);
    }
    Map<String,Object> scope(){
        var repository=com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:evo:fixture:native-comparison:v1");
        var snapshot=com.evolution.analysis.contract.source.RepositorySnapshot.create(repository,Optional.empty(),false,
                List.of(new com.evolution.analysis.contract.source.SnapshotFile("fixture-inputs",ContentDigest.sha256Utf8("authored M1 context"))),List.of());
        return Map.of("baseSnapshot",snapshot.identity().value());
    }
    Map<LogicalPath,NativeOutputComparison.Role> roles(){return Map.of(output,NativeOutputComparison.Role.OUTPUT,audit,NativeOutputComparison.Role.AUDIT);}
    @Test void sameCapturedOutputWithDifferentAttemptsRetainsTheAuditDifference()throws Exception{
        var left=revision("a".repeat(32),"real captured output","same input bytes",true);var right=revision("b".repeat(32),"real captured output","same input bytes",true);
        var result=comparison.compare(scope(),left,right,roles());assertEquals(NativeOutputComparison.State.IDENTICAL,result.state());assertTrue(result.outputs().isEmpty());assertEquals(List.of(audit),result.audit().stream().map(NativeOutputComparison.Change::path).toList());assertTrue(result.gaps().isEmpty());assertTrue(result.current());
        assertEquals(result.canonicalForm(),comparison.compare(scope(),left,right,roles()).canonicalForm());
        assertThrows(IllegalArgumentException.class,()->comparison.compare(Map.of("baseSnapshot",ContentDigest.sha256Utf8("not an M1 snapshot identity").value()),left,right,roles()));
    }
    @Test void changedOutputRetainsBothRevisionsAndATypedNonreproducibilityFinding()throws Exception{
        var left=revision("a".repeat(32),"generated timestamp one","same input bytes",true);var right=revision("b".repeat(32),"generated timestamp two","same input bytes",true);
        var result=comparison.compare(scope(),left,right,roles());assertEquals(NativeOutputComparison.State.DIFFERENT,result.state());assertEquals(1,result.outputs().size());var changed=result.outputs().getFirst();assertEquals(output,changed.path());assertNotEquals(changed.before(),changed.after());assertTrue(changed.before().isPresent());assertTrue(changed.after().isPresent());assertEquals(List.of("GENERATOR_NONREPRODUCIBLE"),result.gaps().stream().map(g->g.reasonCode()).toList());
    }
    @Test void differentInputsCannotBeReportedAsGeneratorNondeterminism()throws Exception{
        var result=comparison.compare(scope(),revision("a".repeat(32),"one","input one",true),revision("b".repeat(32),"two","input two",true),roles());
        assertEquals(NativeOutputComparison.State.INCOMPARABLE,result.state());assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("LINEAGE_SCOPE_UNPROVEN")));assertTrue(result.gaps().stream().noneMatch(g->g.reasonCode().equals("GENERATOR_NONREPRODUCIBLE")));
    }
    @Test void incompleteCaptureOrMissingClassificationCannotCloseTheComparison()throws Exception{
        var left=revision("a".repeat(32),"one","same",false);var right=revision("b".repeat(32),"one","same",true);
        assertEquals(NativeOutputComparison.State.INCOMPLETE,comparison.compare(scope(),left,right,roles()).state());
        var missing=comparison.compare(scope(),right,right,Map.of(output,NativeOutputComparison.Role.OUTPUT));assertEquals(NativeOutputComparison.State.INCOMPLETE,missing.state());assertEquals(Set.of(audit),missing.unclassified());assertTrue(missing.gaps().stream().anyMatch(g->g.reasonCode().equals("OUTPUT_OWNER_UNPROVEN")));
    }
    @Test void changedCasBytesInvalidateEarlierFindingsAndPreventAnotherComparison()throws Exception{
        var left=revision("a".repeat(32),"one","same",true);var right=revision("b".repeat(32),"two","same",true);var result=comparison.compare(scope(),left,right,roles());
        String digest=ContentDigest.sha256Utf8("two").value().substring(7);Files.writeString(root.resolve("cas/sha256").resolve(digest.substring(0,2)).resolve(digest.substring(2)),"bad");
        assertFalse(result.current());assertThrows(IOException.class,()->comparison.compare(scope(),left,right,roles()));
    }
    NativeOutputComparison.Revision link(String attempt,String target)throws Exception{
        var content=new StreamingContentStore(root.resolve("cas"));var inputs=new NativeOutputComparison.Inputs(put(content,"same input bytes"),put(content,"same registered environment"));
        var expected=new OutputCensus.Scope(attempt,"c".repeat(32),new OutputCensus.Budget(65536,4096,65536));
        var row=OutputCensusTest.row("generated-link","SYMLINK",target.length(),"PRESENT");row.put("linkTarget",OutputCensusTest.b64(target));
        var manifest=put(content,CanonicalJson.write(row)+"\n");var scope=put(content,CanonicalJson.write(Map.of("schema","guest-output-scope:m8.4-v1","nonce",expected.nonce(),"work",OutputCensusTest.b64("/work/"+attempt),"budget",expected.budget())));
        var header=new TreeMap<String,Object>();header.put("schema","guest-output-census:m8.4-v2");header.put("attempt",attempt);header.put("nonce",expected.nonce());header.put("entries",1);header.put("logicalBytes",0);header.put("capturedBytes",0);header.put("pending",0);header.put("rejected",0);header.put("inventoryClosed",true);header.put("captureClosed",true);header.put("manifest",manifest);header.put("scope",scope);header.put("trust","PRODUCER_ASSERTION");
        return comparison.read(content,inputs,put(content,CanonicalJson.write(header)),expected,100,()->false);
    }
    @Test void aChangedLinkTargetIsARealOutputDifferenceWithoutFollowingTheLink()throws Exception{
        var left=link("a".repeat(32),"one");var right=link("b".repeat(32),"two");var path=LogicalPath.utf8("generated-link");
        var result=comparison.compare(scope(),left,right,Map.of(path,NativeOutputComparison.Role.OUTPUT));
        assertEquals(NativeOutputComparison.State.DIFFERENT,result.state());assertEquals(List.of(path),result.outputs().stream().map(NativeOutputComparison.Change::path).toList());
        assertEquals(1,result.metadata().size());assertNotEquals(result.metadata().getFirst().before(),result.metadata().getFirst().after());
        assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("GENERATOR_NONREPRODUCIBLE")));
    }
    @Test void separatelyPublishedCapturesAreCopiedByContentIdentityBeforeComparison()throws Exception{
        var left=revision("a".repeat(32),"one","same",true,root.resolve("before"));var right=revision("b".repeat(32),"two","same",true,root.resolve("after"));
        var result=comparison.compare(scope(),left,right,roles());assertEquals(NativeOutputComparison.State.DIFFERENT,result.state());assertTrue(result.current());
        String digest=ContentDigest.sha256Utf8("two").value().substring(7);
        assertEquals("two",Files.readString(root.resolve("before/sha256").resolve(digest.substring(0,2)).resolve(digest.substring(2))));
    }
}
