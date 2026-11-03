package com.evolution.backend.query;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.polyglot.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BridgeQueryApiTest {
    @Test void unavailableStoreLogsBoundedDiagnosticsWithoutExceptionSecrets()throws Exception{
        var sink=new ArrayList<String>();var logger=java.util.logging.Logger.getLogger(BridgeQueryApi.class.getName());boolean parents=logger.getUseParentHandlers();var previousLevel=logger.getLevel();logger.setLevel(java.util.logging.Level.ALL);
        var handler=new java.util.logging.Handler(){public void publish(java.util.logging.LogRecord record){sink.add(record.getMessage());}public void flush(){}public void close(){}};logger.addHandler(handler);logger.setUseParentHandlers(false);
        try{
            var outer=new java.io.IOException("secret-token=/private/customer/repository");outer.initCause(new java.io.IOException("SEMANTIC_TABLE_CONTENT_UNAVAILABLE"));
            var tables=new SemanticTables(new StreamingContentStore(directory.resolve("failure-cas")),65536);var api=new BridgeQueryApi(new BridgeQueryService((scope,cancelled)->{throw outer;},tables),ApiLimits.defaults());
            var response=api.handle("POST","/api/v1/bridges/summary","application/json",CanonicalJson.write(Map.of("scope",ContentDigest.sha256Utf8("fixture"))).getBytes(StandardCharsets.UTF_8),()->false);
            assertEquals(503,response.status());assertFalse(new String(response.body(),StandardCharsets.UTF_8).contains("secret-token"));assertEquals(1,sink.size());assertFalse(sink.getFirst().contains("secret-token"));assertFalse(sink.getFirst().contains("/private/customer"));assertTrue(sink.getFirst().contains("SEMANTIC_TABLE_CONTENT_UNAVAILABLE"));assertTrue(sink.getFirst().contains("BridgeQueryApiTest"));
        }finally{logger.removeHandler(handler);logger.setUseParentHandlers(parents);logger.setLevel(previousLevel);}
    }
    @TempDir Path directory;
    record Fixture(ArchitectureBridges.Input input,BridgeQueryService queries,ArchitectureQueryApi api){}
    Fixture fixture()throws Exception{return fixture(Optional.empty());}
    Fixture fixture(Optional<Path> nativeDirectory)throws Exception{
        var content=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(content,65536);var wire=nativeDirectory.isPresent()?read(nativeDirectory.orElseThrow().resolve("input.json")):Map.<String,Object>of();var inventory=nativeDirectory.isPresent()?CanonicalJson.sha256(wire):ContentDigest.sha256Utf8("fixed api inventory");var policy=ContentDigest.sha256Utf8("operator");var context=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:bridge-api"),inventory));var scope=ArchitectureBridges.scope(context,inventory);var empty=tables.write(SemanticTables.Kind.DOCUMENT,Collections.emptyIterator(),()->false);var rows=new ArrayList<SemanticTables.Row>();
        for(String key:List.of("healthy","missing"))rows.add(ArchitectureBridges.row(SemanticTables.Kind.CONTEXT,key,scope,new ArchitectureBridges.Partition(ContentDigest.sha256Utf8(key),context.snapshotIdentity().value(),".",key,"main","default","python",key.equals("healthy")?"COMPLETE":"MISSING_PROVIDER",empty,Optional.empty())));
        var endpoints=tables.write(SemanticTables.Kind.OBSERVATION,Collections.emptyIterator(),()->false);var census=tables.write(SemanticTables.Kind.CENSUS,Collections.emptyIterator(),()->false);
        if(nativeDirectory.isPresent()){
            var report=read(nativeDirectory.orElseThrow().resolve("capture.json"));var nativeScope=new ContentDigest(GraphJson.string(wire.get("scope")));var documents=new TreeMap<String,SemanticTables.Row>();for(Object value:GraphJson.array(wire.get("files"))){var file=GraphJson.object(value);var source=content.put(new ByteArrayInputStream(GraphJson.string(file.get("text")).getBytes(StandardCharsets.UTF_8)),()->false);assertEquals(file.get("sha256"),source.digest().value());String path=GraphJson.string(file.get("path"));documents.put(path,new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,path,nativeScope,Map.of("content",source,"role",file.get("role"),"encoding","UTF-8")));}
            var request=new ArchitectureCapture.Request(context,nativeScope,GraphJson.string(wire.get("repository")),GraphJson.string(wire.get("app")),GraphJson.object(wire.get("context")),GraphJson.array(wire.get("profiles")).stream().map(GraphJson::object).toList(),tables.write(SemanticTables.Kind.DOCUMENT,documents.values().iterator(),()->false),new ContentDigest(GraphJson.string(report.get("tool"))));var raw=content.put(new ByteArrayInputStream(CanonicalJson.write(report).getBytes(StandardCharsets.UTF_8)),()->false);var capture=ArchitectureCapture.read(request,raw,tables,content,1_000_000,g->{},()->false);var imported=new ArchitectureBridgeImport(content,tables).read("frontend",inventory,request,capture,1_000_000,()->false);rows.add(imported.partition());rows.sort(Comparator.comparing(SemanticTables.Row::key));endpoints=imported.endpoints();census=imported.requirements();
        }
        var partitions=tables.write(SemanticTables.Kind.CONTEXT,rows.iterator(),()->false);var input=new ArchitectureBridges.Input(context,inventory,policy,partitions,endpoints,census);var store=new BridgePublicationStore(directory.resolve("bridges"),content,tables,()->policy,Set::of);store.commit(input,Optional.empty(),()->false);var queries=store.queries();return new Fixture(input,queries,ArchitectureQueryApi.withBridgeQueries(new ArchitectureQueryApi(new InMemoryGraphStore()),queries));
    }
    static Map<String,Object> read(Path file)throws Exception{return GraphJson.object(GraphJson.read(Files.readString(file,StandardCharsets.UTF_8),new GraphJson.Limits(1_000_000,64,1_000_000)));}
    ArchitectureQueryApi.Response call(ArchitectureQueryApi api,String path,Object input){return api.handle("POST","/api/v1/bridges/"+path,"application/json",CanonicalJson.write(input).getBytes(StandardCharsets.UTF_8),()->false);}
    Map<String,Object> body(ArchitectureQueryApi.Response response){return GraphJson.object(GraphJson.read(new String(response.body(),StandardCharsets.UTF_8),GraphJson.Limits.defaults()));}
    @Test void apiUsesSharedGlobalAccountingAndDoesNotLoseMissingSiblings()throws Exception{
        var f=fixture();var summary=call(f.api(),"summary",Map.of("scope",f.input().scope()));assertEquals(200,summary.status());assertEquals(CanonicalJson.write(f.queries().summary(f.input().scope(),()->false)),CanonicalJson.write(body(summary).get("data")));
        var first=call(f.api(),"page",Map.of("scope",f.input().scope(),"section","PARTITIONS","size",1));assertEquals(200,first.status());var data=GraphJson.object(body(first).get("data"));var next=call(f.api(),"page",Map.of("scope",f.input().scope(),"section","PARTITIONS","size",1,"cursor",data.get("continuation")));assertEquals(200,next.status());assertTrue(new String(next.body(),StandardCharsets.UTF_8).contains("MISSING_PROVIDER"));
        assertEquals(409,call(f.api(),"page",Map.of("scope",f.input().scope(),"section","GAPS","cursor",data.get("continuation"))).status());
        var gaps=call(f.api(),"page",Map.of("scope",f.input().scope(),"section","GAPS"));assertTrue(new String(gaps.body(),StandardCharsets.UTF_8).contains("BRIDGE_CHILD_MISSING_PROVIDER"));
    }
    @Test void topologyUsesTheSameBoundedQualifiedPathService()throws Exception{
        var f=fixture();var target=ContentDigest.sha256Utf8("scoped target");var request=Map.of("scope",f.input().scope(),"target",target,"depth",3,"nodes",20,"scanBudget",100);var response=call(f.api(),"topology",request);assertEquals(200,response.status());assertEquals(CanonicalJson.write(f.queries().topology(f.input().scope(),target,3,20,100,()->false)),CanonicalJson.write(body(response).get("data")));assertEquals(400,call(f.api(),"topology",Map.of("scope",f.input().scope(),"target",target,"scanBudget",1001)).status());
    }
    @Test void requestCannotChoosePathsPolicyCommandsOrUnboundedPages()throws Exception{
        var f=fixture();for(String forbidden:List.of("store","policy","command","qualified"))assertEquals(400,call(f.api(),"summary",Map.of("scope",f.input().scope(),forbidden,"untrusted")).status());assertEquals(400,call(f.api(),"page",Map.of("scope",f.input().scope(),"section","RELATIONS","size",51)).status());
        assertEquals(400,call(f.api(),"page",Map.of("scope",f.input().scope(),"section","FACTS")).status());assertEquals(400,call(f.api(),"page",Map.of("scope",f.input().scope(),"section","RELATIONS","document","page.ts")).status());
        assertEquals(405,f.api().handle("GET","/api/v1/bridges/summary","application/json",new byte[0],()->false).status());assertEquals("POST",ArchitectureQueryApi.allowedMethods("/api/v1/bridges/summary"));assertEquals(409,f.api().handle("POST","/api/v1/bridges/summary","application/json",CanonicalJson.write(Map.of("scope",f.input().scope())).getBytes(StandardCharsets.UTF_8),()->true).status());
    }
    @Test void nativeSourcePagesUseTheExactSharedChildAndKeepWholeRootAccounting()throws Exception{
        var f=fixture(Optional.of(Path.of("../analyzer/src/test/resources/polyglot/architecture-native-v4")));var selection=new BridgeQueryService.Selection(BridgeQueryService.Section.FACTS,Optional.empty(),Optional.of("frontend"),Optional.empty(),Optional.of("App.vue"));var page=call(f.api(),"page",Map.of("scope",f.input().scope(),"section","FACTS","partition","frontend","document","App.vue","size",50));assertEquals(200,page.status());var expected=f.queries().page(f.input().scope(),selection,50,Optional.empty(),()->false);assertEquals(CanonicalJson.write(expected),CanonicalJson.write(body(page).get("data")));assertFalse(expected.items().isEmpty());assertTrue(expected.items().stream().allMatch(row->"App.vue".equals(row.fields().get("path"))));assertEquals("3",expected.summary().partitions());assertEquals("16",expected.summary().missingCensus());assertEquals(404,call(f.api(),"page",Map.of("scope",f.input().scope(),"section","FACTS","partition","absent")).status());
    }
    @Test void savedAnalysisAndSourcePickersDoNotRequireMemorizingIdentities()throws Exception{
        var f=fixture(Optional.of(Path.of("../analyzer/src/test/resources/polyglot/architecture-native-v4")));var catalog=call(f.api(),"catalog",Map.of());assertEquals(200,catalog.status());var entries=GraphJson.array(GraphJson.object(body(catalog).get("data")).get("items"));assertEquals(1,entries.size());var saved=GraphJson.object(entries.getFirst());assertEquals(f.input().scope().value(),saved.get("scope"));assertFalse(GraphJson.string(saved.get("name")).contains("sha256:"));
        var documents=call(f.api(),"page",Map.of("scope",f.input().scope(),"section","DOCUMENTS","partition","frontend","document","App.vue","size",20));assertEquals(200,documents.status());var rows=GraphJson.array(GraphJson.object(body(documents).get("data")).get("items"));assertEquals(1,rows.size());assertEquals("App.vue",GraphJson.object(rows.getFirst()).get("key"));
    }
}
