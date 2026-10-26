package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class JavaGeneratedOverlayTest {
    @Test void twoRoleBindingsForTheSameOwnedBytesRemainSeparateAcceptedCensusRows()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var tables=new JavaEvidenceTables(f.store,100000);var census=f.store.put("one physical output with two roles");var bytes=f.store.put("class Generated {}");var lineage=lineage(f.store,tables,census);var proof=f.proof("java.generated-lineage",lineage.identity().value(),"same exact owner",List.of());var required=output(census,bytes,lineage,proof.identity());
        var fields=new LinkedHashMap<>(required.fields());fields.put("role","observed-java");fields.put("expectation","OBSERVED");var observed=new JavaEvidenceCodec().create(fields);fields.put("key",JavaGeneratedOverlay.outputKey(observed));observed=new JavaEvidenceCodec().create(fields);
        var result=importRows(f,census,List.of(lineage),List.of(required,observed),Map.of(proof.identity(),proof),true);assertEquals("COMPLETE",result.overlay().text("closure"));assertEquals(2,((Number)result.overlay().object("counts").get("accepted")).intValue());assertEquals(2,JavaEvidenceTables.Table.from(result.overlay().fields().get("outputs")).rows());
    }
    @ParameterizedTest @ValueSource(strings={"generator","compiler","toolchain","recipe","configuration","environment","nativeResolution","inputClosure","inputs","outputCensus"})
    void everyLineageInputChangeInvalidatesTheOldProofAndKeepsTheOldRevision(String field)throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var tables=new JavaEvidenceTables(f.store,100000);var census=f.store.put("captured census");var bytes=f.store.put("class Generated {}");var original=lineage(f.store,tables,census);
        var proof=f.proof("java.generated-lineage",original.identity().value(),"independently checked original",List.of());
        var fields=new LinkedHashMap<>(original.fields());
        if(Set.of("generator","compiler").contains(field))fields.put(field,new VersionedIdentifier("fixture."+field,"changed"));
        else if(field.equals("inputs")){var input=new JavaEvidenceCodec().create(Map.of("schema","java-evidence-input:m8.4-v1","role","CONFIG","content",f.store.put("changed configuration"),"locator",Map.of("container",census,"members",List.of(),"path",LogicalPath.utf8("configuration")),"provider",new VersionedIdentifier("fixture.config","2")));fields.put(field,tables.write(input.kind(),List.of(input).iterator(),()->false).wire());}
        else if(field.equals("outputCensus"))fields.put(field,f.store.put("foreign census"));
        else fields.put(field,ContentDigest.sha256Utf8("changed "+field));
        var changed=new JavaEvidenceCodec().create(fields);assertNotEquals(original.identity(),changed.identity());
        assertEquals("PARTIAL",importRows(f,census,List.of(changed),List.of(output(census,bytes,changed,proof.identity())),Map.of(proof.identity(),proof),true).overlay().text("closure"));
        assertEquals("COMPLETE",importRows(f,census,List.of(original),List.of(output(census,bytes,original,proof.identity())),Map.of(proof.identity(),proof),true).overlay().text("closure"));
    }
    @Test void rawPathsAndNonJavaOutputsRemainInTheClosedDenominator()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var tables=new JavaEvidenceTables(f.store,100000);var census=f.store.put("all observed outputs");var bytes=f.store.put(new java.io.ByteArrayInputStream(new byte[]{0,(byte)255,10}),()->false);var lineage=lineage(f.store,tables,census);var proof=f.proof("java.generated-lineage",lineage.identity().value(),"all output ownership",List.of());
        var paths=List.of(LogicalPath.utf8("A/resource"),LogicalPath.utf8("a/resource"),LogicalPath.utf8("é/resource"),LogicalPath.utf8("é/resource"),LogicalPath.of(new byte[]{(byte)255,'/','r'}));var rows=new ArrayList<JavaEvidenceRecord>();
        for(var path:paths){var fields=new LinkedHashMap<>(output(census,bytes,lineage,proof.identity()).fields());fields.put("category","RESOURCE");fields.put("locator",Map.of("container",census,"members",List.of(),"path",path));var row=new JavaEvidenceCodec().create(fields);fields.put("key",JavaGeneratedOverlay.outputKey(row));rows.add(new JavaEvidenceCodec().create(fields));}
        var result=importRows(f,census,List.of(lineage),rows,Map.of(proof.identity(),proof),true);assertEquals("COMPLETE",result.overlay().text("closure"));assertEquals(5,((Number)result.overlay().object("counts").get("accepted")).intValue());
        var actual=new ArrayList<JavaEvidenceRecord>();tables.read(JavaEvidenceTables.Table.from(result.overlay().fields().get("outputs")),actual::add,()->false);assertEquals(new HashSet<>(paths),actual.stream().map(r->JavaGeneratedOverlay.path(r.object("locator").get("path"))).collect(java.util.stream.Collectors.toSet()));assertArrayEquals(new byte[]{0,(byte)255,10},f.store.open(bytes).readAllBytes());
        var fields=new LinkedHashMap<>(rows.getFirst().fields());fields.put("locator",Map.of("container",census,"members",List.of(),"path",Map.of("rawBase64",Base64.getEncoder().encodeToString("../escape".getBytes(java.nio.charset.StandardCharsets.UTF_8)))));var unsafe=new JavaEvidenceCodec().create(fields);fields.put("key",JavaGeneratedOverlay.outputKey(unsafe));unsafe=new JavaEvidenceCodec().create(fields);
        var rejected=importRows(f,census,List.of(lineage),List.of(unsafe),Map.of(proof.identity(),proof),true);assertEquals("PARTIAL",rejected.overlay().text("closure"));assertTrue(rejected.gaps().stream().anyMatch(g->g.reasonCode().equals("UNSAFE_OUTPUT_LOCATOR")));assertTrue(f.store.verify(bytes));
    }
    @Test void missingNestedInputReferenceCannotCloseLineageAndKeepsTheOutputBytes()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var tables=new JavaEvidenceTables(f.store,100000);var census=f.store.put("census");var source=f.store.put("class Generated {}");
        var lineage=lineage(f.store,tables,census);var input=new LinkedHashMap<String,Object>();
        input.put("schema","java-evidence-input:m8.4-v1");input.put("role","TOOL");input.put("content",source);
        input.put("locator",Map.of("container",new ContentHandle(ContentDigest.sha256Utf8("absent container"),16),"members",List.of(),"path",LogicalPath.utf8("tools/generator")));
        input.put("provider",new VersionedIdentifier("fixture.tool","1"));var fields=new LinkedHashMap<>(lineage.fields());
        fields.put("inputs",tables.write("java-evidence-input",List.of(new JavaEvidenceCodec().create(input)).iterator(),()->false).wire());lineage=new JavaEvidenceCodec().create(fields);
        var proof=f.proof("java.generated-lineage",lineage.identity().value(),"checked lineage",List.of());var row=output(census,source,lineage,proof.identity());
        var result=new JavaGeneratedOverlay(f.store,100000).importOverlay(JavaEvidenceLedgerTest.scope(),census,
                tables.write(lineage.kind(),List.of(lineage).iterator(),()->false),tables.write(row.kind(),List.of(row).iterator(),()->false),Map.of(proof.identity(),proof),true,100,()->false);
        assertEquals("PARTIAL",result.overlay().text("closure"));assertTrue(f.store.verify(source));
        assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("GENERATOR_INPUT_MISSING")));
    }
    @Test void compilerApplicabilityNeedsItsOwnScopedProof()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var tables=new JavaEvidenceTables(f.store,100000);var census=f.store.put("census");var source=f.store.put("resource bytes");
        var fields=new LinkedHashMap<>(lineage(f.store,tables,census).fields());fields.put("compilerApplicability","NOT_APPLICABLE");fields.put("compiler",null);fields.put("compilerApplicabilityProof",D);var lineage=new JavaEvidenceCodec().create(fields);
        var proof=f.proof("java.generated-lineage",lineage.identity().value(),"checked lineage",List.of());var row=output(census,source,lineage,proof.identity());
        var result=new JavaGeneratedOverlay(f.store,100000).importOverlay(JavaEvidenceLedgerTest.scope(),census,
                tables.write(lineage.kind(),List.of(lineage).iterator(),()->false),tables.write(row.kind(),List.of(row).iterator(),()->false),Map.of(proof.identity(),proof),true,100,()->false);
        assertEquals("PARTIAL",result.overlay().text("closure"));assertFalse(result.gaps().isEmpty());
    }
    @Test void contradictoryOwnersNeverChooseTheFirstAcceptedPath()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var tables=new JavaEvidenceTables(f.store,100000);var census=f.store.put("census");var source=f.store.put("class Generated {}");
        var a=lineage(f.store,tables,census);var fields=new LinkedHashMap<>(a.fields());fields.put("generator",new VersionedIdentifier("fixture.other-generator","1"));var b=new JavaEvidenceCodec().create(fields);
        var ap=f.proof("java.generated-lineage",a.identity().value(),"a",List.of());var bp=f.proof("java.generated-lineage",b.identity().value(),"b",List.of());
        var rows=new ArrayList<>(List.of(output(census,source,a,ap.identity()),output(census,source,b,bp.identity())));
        // Separate expected roles have different stable keys while pointing at the same physical path.
        var changed=new LinkedHashMap<>(rows.getLast().fields());changed.put("role","other-role");var second=new JavaEvidenceCodec().create(changed);changed.put("key",JavaGeneratedOverlay.outputKey(second));rows.set(1,new JavaEvidenceCodec().create(changed));rows.sort(JavaEvidenceTables::compare);
        var result=new JavaGeneratedOverlay(f.store,100000).importOverlay(JavaEvidenceLedgerTest.scope(),census,
                tables.write(a.kind(),List.of(a,b).stream().sorted(JavaEvidenceTables::compare).iterator(),()->false),tables.write("java-output-row",rows.iterator(),()->false),Map.of(ap.identity(),ap,bp.identity(),bp),true,100,()->false);
        var imported=new ArrayList<JavaEvidenceRecord>();tables.read(JavaEvidenceTables.Table.from(result.overlay().fields().get("outputs")),imported::add,()->false);
        assertEquals(2,imported.size());assertTrue(imported.stream().noneMatch(r->r.text("disposition").equals("ACCEPTED")),"Both conflicting origins must remain candidates");
    }
    static final String D=JavaEvidenceCodecTest.D;
    private static JavaGeneratedOverlay.Result importRows(JavaEvidenceTestStore.AuthorityFixture f,ContentHandle census,List<JavaEvidenceRecord> lineages,List<JavaEvidenceRecord> rows,Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,boolean closed)throws Exception{
        var tables=new JavaEvidenceTables(f.store,100000);
        return new JavaGeneratedOverlay(f.store,100000).importOverlay(JavaEvidenceLedgerTest.scope(),census,
                tables.write("java-generation-lineage",lineages.stream().sorted(JavaEvidenceTables::compare).iterator(),()->false),
                tables.write("java-output-row",rows.stream().sorted(JavaEvidenceTables::compare).iterator(),()->false),proofs,closed,100,()->false);
    }
    @Test void resourceOnlyCompilerApplicabilityHasAnIndependentProofAndRevokesWithIt()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var tables=new JavaEvidenceTables(f.store,100000);var census=f.store.put("resource-only captured census");var bytes=f.store.put("key=value\n");
        var fields=new LinkedHashMap<>(lineage(f.store,tables,census).fields());fields.put("compiler",null);fields.put("compilerApplicability","NOT_APPLICABLE");fields.put("compilerApplicabilityProof",D);
        var provisional=new JavaEvidenceCodec().create(fields);var subject=JavaGeneratedOverlay.compilerApplicabilitySubject(provisional);
        var applicability=f.proof("java.compiler-not-applicable",subject.value(),CanonicalJson.write(JavaGeneratedOverlay.compilerNotApplicableAnswer(provisional)),List.of(census));
        fields.put("compilerApplicabilityProof",applicability.identity());var lineage=new JavaEvidenceCodec().create(fields);assertEquals(subject,JavaGeneratedOverlay.compilerApplicabilitySubject(lineage));
        var proof=f.proof("java.generated-lineage",lineage.identity().value(),"checked resources",List.of());
        var rowFields=new LinkedHashMap<>(output(census,bytes,lineage,proof.identity()).fields());rowFields.put("category","RESOURCE");rowFields.put("role","generated-resource");
        rowFields.put("locator",Map.of("container",census,"members",List.of(),"path",LogicalPath.utf8("resources/config.properties")));var row=new JavaEvidenceCodec().create(rowFields);rowFields.put("key",JavaGeneratedOverlay.outputKey(row));row=new JavaEvidenceCodec().create(rowFields);
        var proofs=Map.of(proof.identity(),proof,applicability.identity(),applicability);
        assertEquals("COMPLETE",importRows(f,census,List.of(lineage),List.of(row),proofs,true).overlay().text("closure"));
        f.store.blobs.remove(applicability.answer());assertEquals("PARTIAL",importRows(f,census,List.of(lineage),List.of(row),proofs,true).overlay().text("closure"));assertTrue(f.store.verify(bytes));
    }
    @Test void zeroSourceZeroOutputAndNoGeneratorAreDifferentProvenQuestions()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var tables=new JavaEvidenceTables(f.store,100000);var census=f.store.put("zero-output captured census");var lineage=lineage(f.store,tables,census);
        var inputs=new ArrayList<JavaEvidenceRecord>();tables.read(JavaEvidenceTables.Table.from(lineage.fields().get("inputs")),inputs::add,()->false);assertEquals(List.of("TOOL"),inputs.stream().map(r->r.text("role")).toList());
        var generation=f.proof("java.generated-lineage",lineage.identity().value(),"validated zero output",List.of());var noGenerator=f.proof("java.generator-not-applicable",D,"validated absent generator",List.of());
        assertEquals("COMPLETE",importRows(f,census,List.of(lineage),List.of(),Map.of(generation.identity(),generation),true).overlay().text("closure"));
        assertEquals("PARTIAL",importRows(f,census,List.of(lineage),List.of(),Map.of(noGenerator.identity(),noGenerator),true).overlay().text("closure"));
        assertEquals("PARTIAL",importRows(f,census,List.of(lineage),List.of(),Map.of(generation.identity(),generation),false).overlay().text("closure"));
        var fields=new LinkedHashMap<>(lineage.fields());fields.put("closure","OPEN");fields.put("gaps",List.of("gap:"+D));var crashed=new JavaEvidenceCodec().create(fields);
        assertEquals("PARTIAL",importRows(f,census,List.of(crashed),List.of(),Map.of(generation.identity(),generation),true).overlay().text("closure"));
    }
    @Test void missingInputKeepsAnIndependentAcceptedOutputAndEveryLineageRow()throws Exception{
        var f=new JavaEvidenceTestStore.AuthorityFixture();var tables=new JavaEvidenceTables(f.store,100000);var census=f.store.put("two generators");var bytes=f.store.put("class Generated {}");
        var healthy=lineage(f.store,tables,census);var badFields=new LinkedHashMap<>(healthy.fields());badFields.put("generator",new VersionedIdentifier("fixture.broken-input","1"));
        var input=new JavaEvidenceCodec().create(Map.of("schema","java-evidence-input:m8.4-v1","role","TOOL","content",new ContentHandle(ContentDigest.sha256Utf8("missing"),7),"locator",Map.of("container",census,"members",List.of(),"path",LogicalPath.utf8("missing-tool")),"provider",new VersionedIdentifier("fixture.tool","1")));
        badFields.put("inputs",tables.write(input.kind(),List.of(input).iterator(),()->false).wire());var bad=new JavaEvidenceCodec().create(badFields);
        var hp=f.proof("java.generated-lineage",healthy.identity().value(),"healthy",List.of());var bp=f.proof("java.generated-lineage",bad.identity().value(),"bad",List.of());
        var badOutput=new LinkedHashMap<>(output(census,bytes,bad,bp.identity()).fields());badOutput.put("locator",Map.of("container",census,"members",List.of(),"path",LogicalPath.utf8("broken/Generated.java")));var badRow=new JavaEvidenceCodec().create(badOutput);badOutput.put("key",JavaGeneratedOverlay.outputKey(badRow));badRow=new JavaEvidenceCodec().create(badOutput);
        var result=importRows(f,census,List.of(healthy,bad),List.of(output(census,bytes,healthy,hp.identity()),badRow),Map.of(hp.identity(),hp,bp.identity(),bp),true);
        assertEquals("PARTIAL",result.overlay().text("closure"));assertEquals(2,((Number)result.overlay().object("counts").get("total")).longValue());assertEquals(1,((Number)result.overlay().object("counts").get("accepted")).longValue());
        assertEquals(2,JavaEvidenceTables.Table.from(result.overlay().fields().get("lineages")).rows());
    }
    static JavaEvidenceRecord lineage(JavaEvidenceTestStore store,JavaEvidenceTables tables,ContentHandle census)throws Exception{
        var tool=store.put("actual authored tool bytes");var input=new JavaEvidenceCodec().create(Map.of("schema","java-evidence-input:m8.4-v1","role","TOOL","content",tool,"locator",Map.of("container",tool,"members",List.of(),"path",LogicalPath.utf8("tools/generator")),"provider",new VersionedIdentifier("fixture.tool","1")));
        var inputs=tables.write("java-evidence-input",List.of(input).iterator(),()->false);
        var data=new LinkedHashMap<String,Object>();data.put("schema","java-generation-lineage:m8.4-v1");data.put("scope",JavaEvidenceLedgerTest.scope());data.put("generator",new VersionedIdentifier("fixture.generator","1"));data.put("compiler",new VersionedIdentifier("fixture.compiler","21"));data.put("compilerApplicability","APPLICABLE");data.put("compilerApplicabilityProof",null);for(String field:List.of("toolchain","recipe","configuration","environment","nativeResolution","inputClosure"))data.put(field,D);data.put("inputs",inputs.wire());data.put("outputCensus",census);data.put("closure","COMPLETE");data.put("gaps",List.of());return new JavaEvidenceCodec().create(data);
    }
    static JavaEvidenceRecord output(ContentHandle container,ContentHandle source,JavaEvidenceRecord lineage,ContentDigest proof){
        var fields=new LinkedHashMap<String,Object>();fields.put("schema","java-output-row:m8.4-v1");fields.put("key",D);fields.put("scope",JavaEvidenceLedgerTest.scope());fields.put("role","generated-java");fields.put("expectation","REQUIRED");fields.put("locator",Map.of("container",container,"members",List.of(),"path",LogicalPath.utf8("custom-generated/Generated.java")));fields.put("kind","FILE");fields.put("content",source);fields.put("lineage",lineage.identity());fields.put("rootProof",proof);fields.put("category","JAVA_SOURCE");fields.put("disposition","ACCEPTED");fields.put("reason",null);var record=new JavaEvidenceCodec().create(fields);fields.put("key",JavaGeneratedOverlay.outputKey(record));return new JavaEvidenceCodec().create(fields);
    }
    @Test void byteHashesAndProducerAssertionsCannotCloseGeneratedLineage()throws Exception{
        var store=new JavaEvidenceTestStore();var tables=new JavaEvidenceTables(store,10000);var census=store.put("authored raw census");var source=store.put("class Generated {}");var lineage=lineage(store,tables,census);var row=output(census,source,lineage,new ContentDigest(D));
        var result=new JavaGeneratedOverlay(store,10000).importOverlay(JavaEvidenceLedgerTest.scope(),census,tables.write(lineage.kind(),List.of(lineage).iterator(),()->false),tables.write(row.kind(),List.of(row).iterator(),()->false),Map.of(),true,10,()->false);
        assertEquals("PARTIAL",result.overlay().text("closure"));assertEquals(2,result.gaps().size());assertTrue(result.gaps().stream().allMatch(g->g.reasonCode().equals("VALIDATION_METHOD_UNAVAILABLE")),"Both output ownership and the unclosed generator remain obligations");
        var rows=new ArrayList<JavaEvidenceRecord>();tables.read(JavaEvidenceTables.Table.from(result.overlay().fields().get("outputs")),rows::add,()->false);assertEquals("RETAINED",rows.getFirst().text("disposition"));assertTrue(store.verify(source));
    }
    @Test void independentlySignedLineageClosesOnlyMatchingScopedOutput()throws Exception{
        var fixture=new JavaEvidenceTestStore.AuthorityFixture();var store=fixture.store;var tables=new JavaEvidenceTables(store,10000);var census=store.put("authored census");var source=store.put("class Generated {}");var lineage=lineage(store,tables,census);
        var proof=fixture.proof("java.generated-lineage",lineage.identity().value(),"scoped authored lineage answer",List.of());var output=output(census,source,lineage,proof.identity());
        var lineages=tables.write(lineage.kind(),List.of(lineage).iterator(),()->false);var outputs=tables.write(output.kind(),List.of(output).iterator(),()->false);
        var importer=new JavaGeneratedOverlay(store,10000);var result=importer.importOverlay(JavaEvidenceLedgerTest.scope(),census,lineages,outputs,Map.of(proof.identity(),proof),true,10,()->false);
        assertEquals("COMPLETE",result.overlay().text("closure"));assertTrue(result.gaps().isEmpty());
        store.blobs.remove(source.digest());var changed=importer.importOverlay(JavaEvidenceLedgerTest.scope(),census,lineages,outputs,Map.of(proof.identity(),proof),true,10,()->false);
        assertEquals("PARTIAL",changed.overlay().text("closure"));assertEquals("CONTENT_HASH_MISMATCH",changed.gaps().getFirst().reasonCode());
    }
    @Test void sealedCensusWithRejectedObservedOutputCannotBecomeComplete()throws Exception{
        var store=new JavaEvidenceTestStore();var tables=new JavaEvidenceTables(store,10000);var census=store.put("closed census retaining rejected row");var source=store.put("class Generated {}");var lineage=lineage(store,tables,census);
        var values=new LinkedHashMap<>(output(census,source,lineage,new ContentDigest(D)).fields());values.put("expectation","OBSERVED");values.put("disposition","REJECTED");values.put("reason","unsafe output");var row=new JavaEvidenceCodec().create(values);values.put("key",JavaGeneratedOverlay.outputKey(row));row=new JavaEvidenceCodec().create(values);
        var result=new JavaGeneratedOverlay(store,10000).importOverlay(JavaEvidenceLedgerTest.scope(),census,tables.write(lineage.kind(),List.of(lineage).iterator(),()->false),tables.write(row.kind(),List.of(row).iterator(),()->false),Map.of(),true,10,()->false);
        assertEquals("SEALED",result.overlay().text("census"));assertEquals("PARTIAL",result.overlay().text("closure"));assertFalse(result.gaps().isEmpty());assertEquals(1,result.overlay().object("counts").get("rejected") instanceof java.math.BigDecimal n?n.longValueExact():-1);
    }
    @Test void emptyClosedFolderNeedsAnIndependentNoGeneratorProof()throws Exception{
        var fixture=new JavaEvidenceTestStore.AuthorityFixture();var tables=new JavaEvidenceTables(fixture.store,10000);var census=fixture.store.put("empty captured folder");var emptyLineages=tables.write("java-generation-lineage",Collections.emptyIterator(),()->false);var emptyOutputs=tables.write("java-output-row",Collections.emptyIterator(),()->false);var importer=new JavaGeneratedOverlay(fixture.store,10000);
        var unproved=importer.importOverlay(JavaEvidenceLedgerTest.scope(),census,emptyLineages,emptyOutputs,Map.of(),true,10,()->false);assertEquals("PARTIAL",unproved.overlay().text("closure"));
        var absentFields=new LinkedHashMap<>(output(census,fixture.store.put("unused"),lineage(fixture.store,tables,census),new ContentDigest(D)).fields());absentFields.put("expectation","OPTIONAL");absentFields.put("disposition","ABSENT");absentFields.put("content",null);absentFields.put("lineage",null);absentFields.put("rootProof",null);absentFields.put("reason","OPTIONAL_OUTPUT_ABSENT");var absent=new JavaEvidenceCodec().create(absentFields);absentFields.put("key",JavaGeneratedOverlay.outputKey(absent));absent=new JavaEvidenceCodec().create(absentFields);var absentTable=tables.write(absent.kind(),List.of(absent).iterator(),()->false);
        assertEquals("PARTIAL",importer.importOverlay(JavaEvidenceLedgerTest.scope(),census,emptyLineages,absentTable,Map.of(),true,10,()->false).overlay().text("closure"),"An absent optional role does not prove the generator census");
        var proof=fixture.proof("java.generator-not-applicable",D,"no generators in independently enumerated fixture",List.of());
        var proved=importer.importOverlay(JavaEvidenceLedgerTest.scope(),census,emptyLineages,emptyOutputs,Map.of(proof.identity(),proof),true,10,()->false);assertEquals("COMPLETE",proved.overlay().text("closure"));
        assertEquals("COMPLETE",importer.importOverlay(JavaEvidenceLedgerTest.scope(),census,emptyLineages,absentTable,Map.of(proof.identity(),proof),true,10,()->false).overlay().text("closure"));
        assertEquals("PARTIAL",importer.importOverlay(JavaEvidenceLedgerTest.scope(),census,emptyLineages,emptyOutputs,Map.of(new ContentDigest("sha256:"+"1".repeat(64)),proof),true,10,()->false).overlay().text("closure"));
    }
}
