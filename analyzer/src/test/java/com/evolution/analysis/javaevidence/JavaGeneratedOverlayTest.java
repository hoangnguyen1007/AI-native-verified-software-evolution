package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaGeneratedOverlayTest {
    static final String D=JavaEvidenceCodecTest.D;
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
