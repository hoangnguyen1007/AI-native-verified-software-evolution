package com.evolution.analysis.execution.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.output.OutputCensus;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SemanticCaptureImportTest {
    @TempDir Path root;
    final OutputCensus.Scope scope=new OutputCensus.Scope("a".repeat(32),"b".repeat(32),new OutputCensus.Budget(65536,134217728,536870912));
    ContentHandle put(StreamingContentStore store,byte[] bytes)throws IOException{return store.put(new ByteArrayInputStream(bytes),()->false);}
    ContentHandle json(StreamingContentStore store,Object value)throws IOException{return put(store,CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8));}
    Path fixture(){return Path.of("../analyzer/src/test/resources/polyglot/typescript-native-v2");}
    SemanticCaptureImport.Expected expected(byte[] raw)throws IOException{
        var metadata=GraphJson.object(GraphJson.read(Files.readString(fixture().resolve("fixture.json")),GraphJson.Limits.defaults()));
        var tool=CanonicalJson.sha256(List.of("polyglot-toolset:m8.4-v1",new ContentDigest((String)metadata.get("compiler")),new ContentDigest((String)metadata.get("extractor"))));
        return new SemanticCaptureImport.Expected(LogicalPath.utf8("results/typescript.ndjson"),new ContentHandle(ContentDigest.sha256(raw),raw.length),new ContentDigest((String)metadata.get("scope")),"typescript","5.9.3",tool);
    }
    OutputCensus.Header header(StreamingContentStore store,SemanticCaptureImport.Expected expected)throws IOException{
        var row=new TreeMap<String,Object>();row.put("path",Base64.getEncoder().encodeToString("results/typescript.ndjson".getBytes(StandardCharsets.UTF_8)));row.put("kind","FILE");row.put("size",expected.bytes().size());row.put("mode",384);row.put("blocks",0);row.put("links",1);row.put("content",expected.bytes());row.put("linkTarget",null);row.put("linkGroup",null);row.put("disposition","PRESENT");row.put("reason","");
        var manifest=put(store,(CanonicalJson.write(row)+"\n").getBytes(StandardCharsets.UTF_8));
        var scopeBytes=json(store,Map.of("schema","guest-output-scope:m8.4-v1","nonce",scope.nonce(),"work",Base64.getEncoder().encodeToString(("/work/"+scope.attempt()).getBytes(StandardCharsets.UTF_8)),"budget",scope.budget()));
        var source=json(store,Map.ofEntries(Map.entry("schema","guest-output-census:m8.4-v2"),Map.entry("attempt",scope.attempt()),Map.entry("nonce",scope.nonce()),Map.entry("entries",1),Map.entry("logicalBytes",expected.bytes().size()),Map.entry("capturedBytes",expected.bytes().size()),Map.entry("pending",0),Map.entry("rejected",0),Map.entry("inventoryClosed",true),Map.entry("captureClosed",true),Map.entry("manifest",manifest),Map.entry("scope",scopeBytes),Map.entry("trust","PRODUCER_ASSERTION")));
        return new OutputCensus(store).header(source,scope);
    }
    @Test void opaqueNativeFixtureReachesNeutralTablesAndStableProducerReport()throws Exception{
        var raw=Files.readAllBytes(fixture().resolve("capture.ndjson"));var expected=expected(raw);var store=new StreamingContentStore(root.resolve("cas"));var tables=new SemanticTables(store,65536);var header=header(store,expected);var importer=new SemanticCaptureImport();var rows=new ArrayList<OutputCensus.RowOutcome>();
        var first=importer.read(header,h->new ByteArrayInputStream(raw),Map.of("typescript",expected),store,tables,65536,rows::add,()->false);
        assertTrue(first.bytesClosed());assertEquals(1,rows.size());assertEquals(17,first.outcomes().get("typescript").capture().orElseThrow().observations().rows());
        var replay=importer.read(header,h->new ByteArrayInputStream(raw),Map.of("typescript",expected),store,tables,65536,row->{},()->false);assertEquals(first.report(),replay.report());
        try(var input=store.open(first.report())){var report=GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),GraphJson.Limits.defaults()));assertEquals(false,report.get("qualified"));assertEquals("PRODUCER_ASSERTION",report.get("trust"));}
    }
    @Test void neutralImportBindsExactProviderWhileKeepingHealthySiblingAndEveryCensusRow()throws Exception{
        // Protocol conversion control only: these retained TS rows do not qualify a Kotlin engine.
        var original=Files.readAllBytes(fixture().resolve("capture.ndjson"));var old=expected(original);
        var lines=new String(original,StandardCharsets.UTF_8).lines().toList();var nativeLines=new ArrayList<String>();
        var provider=new VersionedIdentifier("frontend.kotlin-native","1.9.25-m8.4-v1");
        nativeLines.add(CanonicalJson.write(Map.of("schema","language-capture-result:m8.4-v1","scope",old.scope().value(),"language","kotlin","dialect","1.9.25","provider",provider,"tool",old.toolset().value(),"trust","PRODUCER_ASSERTION")));
        nativeLines.addAll(lines.subList(1,lines.size()-1));var end=new TreeMap<>(GraphJson.object(GraphJson.read(lines.getLast(),GraphJson.Limits.defaults())));end.put("schema","language-capture-end:m8.4-v1");nativeLines.add(CanonicalJson.write(end));
        var raw=(String.join("\n",nativeLines)+"\n").getBytes(StandardCharsets.UTF_8);var bytes=new ContentHandle(ContentDigest.sha256(raw),raw.length);
        var transport=new SemanticCaptureImport.Expected(old.path(),bytes,old.scope(),old.language(),old.engine(),old.toolset());
        var expected=new SemanticCaptureImport.NativeExpected(old.path(),bytes,old.scope(),"kotlin","1.9.25",provider,old.toolset());
        var absent=new SemanticCaptureImport.NativeExpected(LogicalPath.utf8("results/missing.ndjson"),bytes,old.scope(),"scala","2.13.16",provider,old.toolset());
        var store=new StreamingContentStore(root.resolve("cas"));var tables=new SemanticTables(store,65536);var header=header(store,transport);var importer=new SemanticCaptureImport();var rows=new ArrayList<OutputCensus.RowOutcome>();
        var result=importer.readNative(header,h->new ByteArrayInputStream(raw),Map.of("healthy",expected,"absent",absent),store,tables,65536,rows::add,()->false);
        assertFalse(result.bytesClosed());assertEquals(1,rows.size());assertEquals(17,result.outcomes().get("healthy").capture().orElseThrow().observations().rows());assertEquals(SemanticCaptureImport.State.MISSING,result.outcomes().get("absent").state());
        var foreign=new SemanticCaptureImport.NativeExpected(expected.path(),bytes,expected.scope(),expected.language(),expected.dialect(),new VersionedIdentifier(provider.id(),"different-version"),expected.tool());
        var rejected=importer.readNative(header,h->new ByteArrayInputStream(raw),Map.of("wrong",foreign),store,tables,65536,row->{},()->false);
        assertEquals(SemanticCaptureImport.State.INVALID,rejected.outcomes().get("wrong").state());
        var closed=importer.readNative(header,h->new ByteArrayInputStream(raw),Map.of("healthy",expected),store,tables,65536,row->{},()->false);assertTrue(closed.bytesClosed());
        assertEquals(closed.report(),importer.readNative(header,h->new ByteArrayInputStream(raw),Map.of("healthy",expected),store,tables,65536,row->{},()->false).report());
    }
    @Test void corruptedTransportAndMissingRolesStayExplicit()throws Exception{
        var raw=Files.readAllBytes(fixture().resolve("capture.ndjson"));var expected=expected(raw);var store=new StreamingContentStore(root.resolve("cas"));var tables=new SemanticTables(store,65536);var header=header(store,expected);
        var missing=new SemanticCaptureImport.Expected(LogicalPath.utf8("results/missing.ndjson"),expected.bytes(),expected.scope(),expected.language(),expected.engine(),expected.toolset());
        var result=new SemanticCaptureImport().read(header,h->new ByteArrayInputStream(new byte[]{1}),Map.of("typescript",expected,"missing",missing),store,tables,65536,row->{},()->false);
        assertFalse(result.bytesClosed());assertEquals(SemanticCaptureImport.State.UNAVAILABLE,result.outcomes().get("typescript").state());assertEquals(SemanticCaptureImport.State.MISSING,result.outcomes().get("missing").state());assertEquals("SEMANTIC_OUTPUT_ABSENCE_UNPROVEN",result.outcomes().get("missing").reason());
    }
    @Test void wrongContextAndWrongExpectedOutputCannotBeAccepted()throws Exception{
        var raw=Files.readAllBytes(fixture().resolve("capture.ndjson"));var expected=expected(raw);var store=new StreamingContentStore(root.resolve("cas"));var tables=new SemanticTables(store,65536);var header=header(store,expected);
        var foreign=new SemanticCaptureImport.Expected(expected.path(),expected.bytes(),ContentDigest.sha256Utf8("another context"),expected.language(),expected.engine(),expected.toolset());
        var result=new SemanticCaptureImport().read(header,h->new ByteArrayInputStream(raw),Map.of("typescript",foreign),store,tables,65536,row->{},()->false);assertEquals(SemanticCaptureImport.State.INVALID,result.outcomes().get("typescript").state());assertFalse(result.bytesClosed());
        var changed=new SemanticCaptureImport.Expected(expected.path(),new ContentHandle(ContentDigest.sha256Utf8("different"),9),expected.scope(),expected.language(),expected.engine(),expected.toolset());
        result=new SemanticCaptureImport().read(header,h->new ByteArrayInputStream(raw),Map.of("typescript",changed),store,tables,65536,row->{},()->false);assertEquals("SEMANTIC_OUTPUT_HANDLE_MISMATCH",result.outcomes().get("typescript").reason());
    }
}
