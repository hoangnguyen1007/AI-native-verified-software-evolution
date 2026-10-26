package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Authored protocol controls; no compiler, processor, target class loading or native claim. */
class JavaResolutionPlannerTest {
    private final JavaEvidenceTestStore.AuthorityFixture f;
    private final JavaEvidenceTables tables;
    private final JavaEvidenceRecord overlay;
    private final PlatformInput platform;
    private final Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs=new HashMap<>();
    JavaResolutionPlannerTest()throws Exception{
        f=new JavaEvidenceTestStore.AuthorityFixture();tables=new JavaEvidenceTables(f.store,100000);
        var scope=JavaEvidenceLedgerTest.scope();var na=f.proof("java.generator-not-applicable",scope.get("context").toString(),"empty generator census",List.of());
        overlay=new JavaGeneratedOverlay(f.store,100000).importOverlay(scope,f.store.put("closed output census"),tables.write("java-generation-lineage",Collections.emptyIterator(),()->false),tables.write("java-output-row",Collections.emptyIterator(),()->false),Map.of(na.identity(),na),true,100,()->false).overlay();
        var captured=f.store.put("authored platform bytes");platform=PlatformInput.create(21,"fixture21","fixture",List.of(new PlatformInput.Artifact("fixture-platform",captured.digest(),Path.of("never-read-platform.jar"),PlatformInput.Format.JAR)));
    }
    private JavaEvidenceRecord manifest(List<JavaEvidenceRecord> slots)throws Exception{
        JavaEvidenceRecord template;
        try(var stream=getClass().getResourceAsStream("/fixtures/java-evidence-codec-positives.jsonl");var reader=new java.io.BufferedReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8))){template=reader.lines().map(line->new JavaEvidenceCodec().read(line)).filter(r->r.kind().equals("java-resolution-manifest")&&r.text("status").equals("EXACT")).findFirst().orElseThrow();}
        var fields=new LinkedHashMap<>(template.fields());fields.put("scope",JavaEvidenceLedgerTest.scope());fields.put("overlay",overlay.identity());fields.put("platform",platform.entry().contentDigest());fields.put("sourceMembership",tables.write("java-evidence-input",Collections.emptyIterator(),()->false).wire());
        fields.put("slots",tables.write("java-resolution-slot",slots.stream().sorted(JavaEvidenceTables::compare).iterator(),()->false).wire());return sign(new JavaEvidenceCodec().create(fields));
    }
    private JavaEvidenceRecord sign(JavaEvidenceRecord manifest)throws Exception{
        var proof=f.proof("java.resolution-context",JavaEvidenceLedgerTest.scope().get("context").toString(),CanonicalJson.write(JavaResolutionPlanner.answerFields(manifest)),List.of());proofs.put(proof.identity(),proof);var fields=new LinkedHashMap<>(manifest.fields());fields.put("contextProof",proof.identity());return new JavaEvidenceCodec().create(fields);
    }
    private JavaEvidenceRecord slot(String space,int ordinal,String name,String kind)throws Exception{
        var bytes=f.store.put("captured "+name);var shape=f.proof("java.binary-shape",bytes.digest().value(),"checked "+name,List.of(bytes));proofs.put(shape.identity(),shape);
        var fields=new LinkedHashMap<String,Object>();fields.put("schema","java-resolution-slot:m8.4-v1");fields.put("scope",JavaEvidenceLedgerTest.scope());fields.put("space",space);fields.put("ordinal",ordinal);fields.put("logicalName",name);fields.put("kind",kind);fields.put("original",bytes);fields.put("tree",null);fields.put("selectedView",null);fields.put("origin","DEPENDENCY");fields.put("module",null);fields.put("proof",shape.identity());fields.put("disposition","ACCEPTED");fields.put("gaps",List.of());return new JavaEvidenceCodec().create(fields);
    }
    private JavaResolutionPlanner.Result plan(JavaEvidenceRecord manifest,List<String> calls)throws Exception{
        return new JavaResolutionPlanner(f.store,100000).plan(manifest,overlay,platform,proofs,slot->{calls.add(slot.text("space")+":"+slot.text("logicalName"));var handle=JavaEvidenceRecord.handle(slot.fields().get("original"));var nativeEntry=new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,slot.text("logicalName"),handle.digest());return new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,slot.text("logicalName")+"@"+slot.number("ordinal"),handle.digest()),Path.of("never-opened-"+slot.number("ordinal")+".jar"),Optional.empty(),Optional.of(new BinaryInput.NativeOrigin(nativeEntry,slot.identity())));},100,()->false);
    }
    @Test void exactEmptySearchSpaceIsDifferentFromUnknownAndMissingSlots()throws Exception{
        var exact=manifest(List.of());assertEquals(List.of(),plan(exact,new ArrayList<>()).binaries().orElseThrow());
        var fields=new LinkedHashMap<>(exact.fields());fields.put("status","WITHHELD");fields.put("contextProof",null);fields.put("gaps",List.of("gap:"+JavaEvidenceCodecTest.D));assertTrue(plan(new JavaEvidenceCodec().create(fields),new ArrayList<>()).binaries().isEmpty());
        var withSlot=manifest(List.of(slot("CLASS_PATH",0,"required","JAR")));var slotTable=JavaEvidenceTables.Table.from(withSlot.fields().get("slots"));f.store.blobs.remove(slotTable.content().digest());assertThrows(java.io.IOException.class,()->plan(withSlot,new ArrayList<>()));
    }
    @Test void orderedRepeatedSlotsAndProcessorInputsNeverInvokeAProcessor()throws Exception{
        var first=slot("CLASS_PATH",0,"same","JAR");var repeat=slot("CLASS_PATH",1,"same","JAR");var processor=slot("PROCESSOR_PATH",0,"processor-must-not-run","JAR");var calls=new ArrayList<String>();var result=plan(manifest(List.of(first,repeat,processor)),calls);
        assertEquals(List.of("CLASS_PATH:same","CLASS_PATH:same"),calls);assertEquals(2,result.binaries().orElseThrow().size());assertEquals(result.binaries().orElseThrow().getFirst().originEntry(),result.binaries().orElseThrow().getLast().originEntry());
        var changed=manifest(List.of(slot("CLASS_PATH",0,"other","JAR"),slot("CLASS_PATH",1,"same","JAR")));assertNotEquals(JavaResolutionPlanner.answer(changed),JavaResolutionPlanner.answer(manifest(List.of(first,repeat))));
    }
    @ParameterizedTest @ValueSource(strings={"MODULE_PATH","REACTOR_SOURCE"})
    void unsupportedModuleOrReactorSemanticsAreWithheldBeforeMaterialization(String mode)throws Exception{
        var calls=new ArrayList<String>();var manifest=manifest(List.of(slot(mode.equals("MODULE_PATH")?mode:"CLASS_PATH",0,"input",mode.equals("MODULE_PATH")?"JAR":mode)));var result=plan(manifest,calls);assertTrue(calls.isEmpty());assertTrue(result.binaries().isEmpty());assertEquals(mode.equals("MODULE_PATH")?"JPMS_CONTEXT_UNPROVEN":"CORRESPONDENCE_UNPROVEN",result.gaps().getFirst().reasonCode());
    }
    @ParameterizedTest @ValueSource(strings={"compiler","sourceLanguage","bytecodeTarget","release","preview","encoding","platform","arguments","argfiles","sourceMembership"})
    void eachContextDimensionInvalidatesAProofRatherThanBorrowingTheHost(String field)throws Exception{
        var original=manifest(List.of());var values=new LinkedHashMap<>(original.fields());switch(field){
            case "compiler" -> values.put(field,new VersionedIdentifier("different.compiler","22"));
            case "sourceLanguage","bytecodeTarget","release" -> values.put(field,"17");
            case "preview" -> values.put(field,true);
            case "encoding" -> values.put(field,"ISO-8859-1");
            case "platform" -> values.put(field,ContentDigest.sha256Utf8("other platform"));
            case "arguments" -> values.put(field,List.of("-Aprocessor.option=different"));
            case "argfiles" -> values.put(field,List.of(f.store.put("captured arguments")));
            case "sourceMembership" -> {var member=new JavaEvidenceCodec().create(Map.of("schema","java-evidence-input:m8.4-v1","role","SOURCE_MEMBERSHIP","content",f.store.put("member"),"locator",Map.of("container",f.store.put("member"),"members",List.of(),"path",LogicalPath.utf8("Changed.java")),"provider",new VersionedIdentifier("fixture.membership","1")));values.put(field,tables.write(member.kind(),List.of(member).iterator(),()->false).wire());}
            default -> throw new AssertionError(field);
        }
        var changed=new JavaEvidenceCodec().create(values);var calls=new ArrayList<String>();assertTrue(plan(changed,calls).binaries().isEmpty());assertTrue(calls.isEmpty());assertNotEquals(JavaResolutionPlanner.answer(original),JavaResolutionPlanner.answer(changed));assertTrue(plan(original,new ArrayList<>()).binaries().isPresent());
    }
    @Test void aSignedArgfileCannotSurviveMissingOrChangedCapturedBytes()throws Exception{
        var original=manifest(List.of(slot("PROCESSOR_PATH",0,"not-executed","JAR")));var argfile=f.store.put("-Aconfigured=captured");var fields=new LinkedHashMap<>(original.fields());fields.put("argfiles",List.of(argfile));var bound=sign(new JavaEvidenceCodec().create(fields));assertTrue(plan(bound,new ArrayList<>()).binaries().isPresent());f.store.blobs.put(argfile.digest(),new byte[]{1});var calls=new ArrayList<String>();assertTrue(plan(bound,calls).binaries().isEmpty());assertTrue(calls.isEmpty());assertTrue(plan(bound,calls).gaps().stream().anyMatch(g->g.reasonCode().equals("GENERATOR_INPUT_MISSING")));
    }
    @ParameterizedTest @CsvSource({"JAVA_BINARY_WORKER_CAPACITY_REQUIRED,WORKER_CAPACITY_REQUIRED","JAVA_BINARY_CONTENT_UNAVAILABLE,CONTENT_HASH_MISMATCH","JAVA_ARCHIVE_MEMBER_AMBIGUOUS_OR_OVERSIZED,ARCHIVE_MEMBER_AMBIGUOUS","JAVA_ARCHIVE_UNSAFE_MEMBER,UNSAFE_OUTPUT_LOCATOR","STORAGE_DEVICE_UNAVAILABLE,STORAGE_UNAVAILABLE"})
    void materializationFailureKeepsItsActualRecoveryCause(String failure,String expected)throws Exception{
        var manifest=manifest(List.of(slot("CLASS_PATH",0,"required","JAR")));var result=new JavaResolutionPlanner(f.store,100000).plan(manifest,overlay,platform,proofs,slot->{throw new java.io.IOException(failure);},100,()->false);assertTrue(result.binaries().isEmpty());assertEquals(expected,result.gaps().getFirst().reasonCode());
    }
}
