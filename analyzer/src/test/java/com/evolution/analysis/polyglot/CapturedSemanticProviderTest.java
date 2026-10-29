package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapturedSemanticProviderTest {
    @Test void actualNativeTypeScriptExportReachesNeutralTablesAndCoverageWithoutInventingTrust()throws Exception{
        var memory=new SemanticTablesTest.Memory();memory.text(resource("main.ts"));var tables=new SemanticTables(memory,65536);
        var exported=memory.text(resource("capture.ndjson"));var capture=CapturedSemanticProvider.read(exported,tables,memory,65536,()->false);
        var metadata=GraphJson.object(GraphJson.read(resource("fixture.json"),GraphJson.Limits.defaults()));
        var inventory=new ContentDigest(GraphJson.string(metadata.get("inventory")));var repository=RepositoryIdentity.fromCanonicalCoordinate("urn:test:typescript-native");
        var request=new LanguageSemanticRequest(EvidenceContext.forSnapshot(SnapshotIdentity.from(repository,inventory)),inventory,"root","app","main","baseline","typescript","5.9.3",
            capture.documents(),capture.census(),memory.text("{\"fixtureScope\":\"CAPTURED_SOURCE_OBSERVATIONS_ONLY\"}"),new VersionedIdentifier("frontend.typescript-capture","m8.4-v1"),capture.toolset(),ContentDigest.sha256Utf8("no-registered-native-method"),ContentDigest.sha256Utf8("fixture-worker"));
        assertEquals(capture.scope(),request.scope());
        var provider=new CapturedSemanticProvider(capture,tables,memory);var output=provider.analyze(request,()->false);
        var actual=new ArrayList<SemanticTables.Row>();try(var reader=tables.open(output,()->false)){SemanticTables.Row row;while((row=reader.next())!=null)actual.add(row);}
        var call=actual.stream().filter(row->row.key().endsWith(":A06:call")).findFirst().orElseThrow();
        assertEquals("RESOLVED",call.fields().get("status"));var symbol=GraphJson.object(GraphJson.array(call.fields().get("symbols")).getFirst());
        assertEquals("twice",symbol.get("name"));assertEquals("PROJECT",symbol.get("origin"));assertEquals("main.ts",symbol.get("path"));
        var span=GraphJson.object(call.fields().get("span"));String source=resource("main.ts");
        assertEquals("twice(4)",source.substring(((java.math.BigDecimal)span.get("start")).intValueExact(),((java.math.BigDecimal)span.get("end")).intValueExact()));
        var gaps=new ArrayList<CapabilityGapRecord>();var view=new SemanticReconciler().reconcile(request,output,tables,memory,Map.of(),Set.of(),request::policy,gaps::add,()->false);
        assertEquals(17,view.total());assertEquals(0,view.omitted());assertFalse(view.complete());assertEquals(2,gaps.size());
        // Dropping an actual checker-produced call remains detectable by the independent census.
        var dropped=tables.write(SemanticTables.Kind.OBSERVATION,actual.stream().filter(row->!row.key().equals(call.key())).iterator(),()->false);
        var missing=new SemanticReconciler().reconcile(request,dropped,tables,memory,Map.of(),Set.of(),request::policy,gap->{},()->false);
        assertEquals(1,missing.omitted());
    }
    @Test void incompleteChangedCountForeignScopeAndTrailingNativeOutputAreRejected()throws Exception{
        var memory=new SemanticTablesTest.Memory();var tables=new SemanticTables(memory,65536);String raw=resource("capture.ndjson");
        for(String bad:List.of(raw.substring(0,raw.lastIndexOf('{')),raw+"{}\n",raw.replace("\"observations\":17","\"observations\":18"),raw.replaceFirst("\"scope\":\"sha256:[0-9a-f]{64}\"","\"scope\":\"sha256:"+"0".repeat(64)+"\""))){
            var handle=memory.text(bad);assertThrows(IOException.class,()->CapturedSemanticProvider.read(handle,tables,memory,65536,()->false));
        }
    }
    @Test void currentNativeFixtureKeepsImplicitGenerationGapAndTheOlderRevisionRemainsReadable()throws Exception{
        var memory=new SemanticTablesTest.Memory();var tables=new SemanticTables(memory,65536);memory.text(resource("typescript-native-v3","main.ts"));var capture=CapturedSemanticProvider.read(memory.text(resource("typescript-native-v3","capture.ndjson")),tables,memory,65536,()->false);var metadata=GraphJson.object(GraphJson.read(resource("typescript-native-v3","fixture.json"),GraphJson.Limits.defaults()));var inventory=new ContentDigest(GraphJson.string(metadata.get("inventory")));var request=new LanguageSemanticRequest(EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:typescript-native"),inventory)),inventory,"root","app","main","baseline","typescript","5.9.3",capture.documents(),capture.census(),memory.text("[]"),new VersionedIdentifier("frontend.typescript-capture","m8.4-v1"),capture.toolset(),ContentDigest.sha256Utf8("unqualified-policy"),ContentDigest.sha256Utf8("work"));var output=new CapturedSemanticProvider(capture,tables,memory).analyze(request,()->false);var gaps=new ArrayList<CapabilityGapRecord>();var coverage=new SemanticReconciler().reconcile(request,output,tables,memory,Map.of(),Set.of(),request::policy,gaps::add,()->false);assertEquals(17,coverage.total());assertEquals(1,coverage.unsupported());assertFalse(coverage.complete());assertTrue(gaps.stream().filter(g->g.reasonCode().equals("SEMANTIC_UNSUPPORTED")).anyMatch(g->g.diagnostics().stream().anyMatch(d->d.details().get("nativeReason").equals("NATIVE_IMPLICIT_GENERATION_CENSUS_REQUIRED"))));assertNotEquals(capture.toolset(),CapturedSemanticProvider.read(memory.text(resource("capture.ndjson")),tables,memory,65536,()->false).toolset());
    }
    @Test void actualExtractorUpgradePreservesDeclarationsWhileAddingInferredTypeQuestions()throws Exception{
        var memory=new SemanticTablesTest.Memory();var tables=new SemanticTables(memory,65536);var declarations=new ArrayList<Map<String,String>>();var tools=new HashSet<ContentDigest>();
        for(String revision:List.of("typescript-native-v3","typescript-native-v4")){
            memory.text(resource(revision,"main.ts"));var capture=CapturedSemanticProvider.read(memory.text(resource(revision,"capture.ndjson")),tables,memory,65536,()->false);tools.add(capture.toolset());var metadata=GraphJson.object(GraphJson.read(resource(revision,"fixture.json"),GraphJson.Limits.defaults()));var inventory=new ContentDigest(GraphJson.string(metadata.get("inventory")));var request=new LanguageSemanticRequest(EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:typescript-native"),inventory)),inventory,"root","app","main","baseline","typescript","5.9.3",capture.documents(),capture.census(),memory.text("[]"),new VersionedIdentifier("frontend.typescript-capture","m8.4-v1"),capture.toolset(),ContentDigest.sha256Utf8("unqualified-policy"),ContentDigest.sha256Utf8("work"));var output=new CapturedSemanticProvider(capture,tables,memory).analyze(request,()->false);
            var ids=new TreeMap<String,String>();try(var rows=tables.open(output,()->false)){SemanticTables.Row row;while((row=rows.next())!=null)if(row.key().endsWith(":A02:declaration"))for(var raw:GraphJson.array(row.fields().get("symbols"))){var symbol=GraphJson.object(raw);ids.put(GraphJson.string(symbol.get("name")),GraphJson.string(symbol.get("id")));}}assertFalse(ids.isEmpty());declarations.add(ids);
            var result=new SemanticReconciler().reconcile(request,output,tables,memory,Map.of(),Set.of(),request::policy,g->{},()->false);assertEquals(revision.endsWith("v4")?20:17,result.total());assertEquals(0,result.omitted());assertEquals(1,result.unsupported());assertFalse(result.complete());
        }
        assertEquals(2,tools.size());assertEquals(declarations.getFirst(),declarations.getLast());
    }
    private static String resource(String name)throws IOException{return resource("typescript-native-v2",name);}
    private static String resource(String revision,String name)throws IOException{try(var in=CapturedSemanticProviderTest.class.getResourceAsStream("/polyglot/"+revision+"/"+name)){if(in==null)throw new IOException("Native fixture missing");return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
}
