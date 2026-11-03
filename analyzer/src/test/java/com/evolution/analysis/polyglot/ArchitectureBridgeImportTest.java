package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureBridgeImportTest {
    @Test void reportedCaptureStateDoesNotConflateMethodQualificationWithNativeGaps()throws Exception{
        // Protocol control for projection, not new native qualification evidence.
        var f=new ArchitectureCaptureTest.Fixture();var report=f.edit();
        com.evolution.analysis.graph.GraphJson.array(report.get("facts")).removeIf(value->!"RESOLVED".equals(com.evolution.analysis.graph.GraphJson.object(value).get("status")));
        report.put("obligations",List.of());f.rehash(report);var capture=f.read(report);assertEquals(0,capture.gapCount());
        var imported=new ArchitectureBridgeImport(f.memory,f.tables).read("config",ContentDigest.sha256Utf8("inventory"),f.request,capture,1_000_000,()->false);
        assertEquals("COMPLETE",ArchitectureBridges.partition(imported.partition().fields()).state());
        assertTrue(f.gaps.stream().anyMatch(gap->"ARCHITECTURE_METHOD_UNQUALIFIED".equals(gap.reasonCode())));
        var partial=new ArchitectureBridgeImport(f.memory,f.tables).read("config",ContentDigest.sha256Utf8("inventory"),f.request,f.read(f.report),1_000_000,()->false);
        assertEquals("PARTIAL",ArchitectureBridges.partition(partial.partition().fields()).state());
    }
    @Test void actualNativeGeneratedHelpersReachLineageBridgesWithoutInventingCallsOrAbsence()throws Exception{
        var f=new ArchitectureCaptureTest.Fixture("architecture-native-v2");var capture=f.read(f.report);var inventory=ContentDigest.sha256Utf8("sealed authored architecture inputs");var imported=new ArchitectureBridgeImport(f.memory,f.tables).read("web",inventory,f.request,capture,1_000_000,()->false);
        var input=new ArchitectureBridges.Input(f.request.context(),inventory,ContentDigest.sha256Utf8("producer-only policy"),f.tables.write(SemanticTables.Kind.CONTEXT,List.of(imported.partition()).iterator(),()->false),imported.endpoints(),imported.requirements());
        var result=new ArchitectureBridges(f.memory,f.tables,1_000_000,100).reconcile(input,new ArchitectureBridgesTest.Index(),Set.of(),input::policy,()->false);assertEquals(capture.artifacts().rows(),result.counts().get("BOUND"));assertEquals(8L,result.counts().get("UNRESOLVED"));assertEquals(0,result.missingCensus());
        var queries=new BridgeQueryService((scope,cancelled)->Optional.of(new BridgeQueryService.View(capture.raw(),1,input,result)),f.tables);var selection=new BridgeQueryService.Selection(BridgeQueryService.Section.FACTS,Optional.empty(),Optional.of("web"),Optional.empty());Optional<BridgeQueryService.Cursor> cursor=Optional.empty();long count=0;do{var page=queries.page(input.scope(),selection,5,cursor,()->false);count+=page.items().size();cursor=page.continuation();}while(cursor.isPresent());assertEquals(capture.factCount(),count);
        assertThrows(IllegalArgumentException.class,()->new BridgeQueryService.Selection(BridgeQueryService.Section.FACTS,Optional.empty(),Optional.empty(),Optional.empty()));assertFalse(queries.page(input.scope(),new BridgeQueryService.Selection(BridgeQueryService.Section.FACTS,Optional.of("F02"),Optional.of("web"),Optional.empty(),Optional.of("App.vue")),50,Optional.empty(),()->false).items().isEmpty());
        try(var reader=f.tables.open(result.relations(),()->false)){SemanticTables.Row row;while((row=reader.next())!=null)if("BOUND".equals(row.fields().get("state"))){assertEquals("GENERATED_FROM",row.fields().get("relation"));assertEquals("DOCUMENT_SUPPORT",com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.object(row.fields().get("from")).get("source")).get("precision"));}}
        var replay=new ArchitectureBridgeImport(f.memory,f.tables).read("web",inventory,f.request,capture,1_000_000,()->false);assertEquals(imported,replay);assertFalse(result.complete());
    }
    @Test void nativeBridgeProjectionRetainsVerifiedArtifactOperandsAndDirections()throws Exception{
        // Protocol control only; native extractor controls run in analyzer-execution.
        var f=new ArchitectureCaptureTest.Fixture("architecture-native-v2");var report=f.edit();
        var nativeRow=com.evolution.analysis.graph.GraphJson.array(report.get("facts")).stream().map(com.evolution.analysis.graph.GraphJson::object).filter(r->"RESOLVED".equals(r.get("status"))&&r.get("target") instanceof String).findFirst().orElseThrow();
        var artifact=com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.array(report.get("artifacts")).getFirst());
        var bytes=com.evolution.analysis.graph.GraphJson.object(artifact.get("content")).get("digest");
        nativeRow.put("target",ContentDigest.sha256Utf8("native table").value());nativeRow.put("kind","BRIDGE_ENDPOINT");nativeRow.put("attributes",Map.of("profile","B05","direction","PROVIDES","descriptor",Map.of("database",bytes,"connection",bytes,"schema","app","object","users"),"artifacts",List.of(artifact.get("id")),"candidates",List.of(),"rangePrecision","DOCUMENT_SUPPORT"));
        nativeRow.remove("id");nativeRow.put("id",com.evolution.analysis.contract.serialization.CanonicalJson.sha256(nativeRow).value());f.rehash(report);
        var capture=f.read(report);var imported=new ArchitectureBridgeImport(f.memory,f.tables).read("web",ContentDigest.sha256Utf8("inventory"),f.request,capture,1_000_000,()->false);
        boolean found=false;try(var rows=f.tables.open(imported.endpoints(),()->false)){SemanticTables.Row row;while((row=rows.next())!=null){var endpoint=ArchitectureBridges.endpoint(row.fields());if(endpoint.profile()==ArchitectureBridges.Profile.B05){found=true;assertEquals("PROVIDES",endpoint.direction());assertTrue(endpoint.evidence().stream().anyMatch(h->h.digest().value().equals(bytes)));}}}assertTrue(found);
    }
    @Test void suppliedDerivedTablesCannotReplaceTheRawNativeCapture()throws Exception{
        var f=new ArchitectureCaptureTest.Fixture();var capture=f.read(f.report);var empty=f.tables.write(SemanticTables.Kind.OBSERVATION,Collections.emptyIterator(),()->false);var forged=new ArchitectureCapture.Result(capture.raw(),capture.identity(),empty,capture.obligations(),capture.artifacts(),capture.documents(),capture.census(),0,capture.gapCount());
        assertThrows(IOException.class,()->new ArchitectureBridgeImport(f.memory,f.tables).read("web",ContentDigest.sha256Utf8("inventory"),f.request,forged,1_000_000,()->false));
    }
    @Test void rehashedParentCannotSubstituteAChildFactTable()throws Exception{
        var f=new ArchitectureCaptureTest.Fixture("architecture-native-v2");var capture=f.read(f.report);var inventory=ContentDigest.sha256Utf8("sealed inputs");var imported=new ArchitectureBridgeImport(f.memory,f.tables).read("web",inventory,f.request,capture,1_000_000,()->false);var partition=ArchitectureBridges.partition(imported.partition().fields());var child=partition.child().orElseThrow();var forged=new ArchitectureBridges.Child(child.request(),child.identity(),f.tables.write(SemanticTables.Kind.OBSERVATION,Collections.emptyIterator(),()->false),child.obligations(),child.artifacts());
        var row=ArchitectureBridges.row(SemanticTables.Kind.CONTEXT,"web",imported.partition().scope(),new ArchitectureBridges.Partition(partition.context(),partition.snapshot(),partition.root(),partition.module(),partition.sourceSet(),partition.variant(),partition.language(),partition.state(),partition.documents(),partition.capture(),Optional.of(forged)));var input=new ArchitectureBridges.Input(f.request.context(),inventory,ContentDigest.sha256Utf8("producer policy"),f.tables.write(SemanticTables.Kind.CONTEXT,List.of(row).iterator(),()->false),imported.endpoints(),imported.requirements());
        assertThrows(IOException.class,()->new ArchitectureBridges(f.memory,f.tables,1_000_000,100).reconcile(input,new ArchitectureBridgesTest.Index(),Set.of(),input::policy,()->false));
    }
}
