package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SemanticQueryApiTest {
    @TempDir Path directory;
    record Fixture(ContentDigest plan,AtomicReference<ContentDigest> policy,SemanticQueryService queries){}
    Fixture fixture()throws Exception{
        var content=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(content,65536);var inventory=ContentDigest.sha256Utf8("semantic api fixture");var context=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:semantic-api"),inventory));var entries=tables.write(SemanticTables.Kind.DOCUMENT,Collections.emptyIterator(),()->false);var census=tables.write(SemanticTables.Kind.CENSUS,Collections.emptyIterator(),()->false);var policy=new AtomicReference<>(ContentDigest.sha256Utf8("no semantic authority"));var resolution=content.put(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),()->false);var selections=new ArrayList<SemanticContextCoordinator.Selection>();
        var source=content.put(new ByteArrayInputStream("def run(): pass\n".getBytes(StandardCharsets.UTF_8)),()->false);var outputs=new HashMap<String,SemanticTables.Table>();
        for(String module:List.of("a","b")){var prototype=new LanguageSemanticRequest(context,inventory,"root",module,"main","default","python","3.11",entries,census,resolution,new VersionedIdentifier("authored.python","v1"),ContentDigest.sha256Utf8("tool"),policy.get(),ContentDigest.sha256Utf8("work"));var documents=tables.write(SemanticTables.Kind.DOCUMENT,List.of(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"main.py",prototype.scope(),Map.of("content",source))).iterator(),()->false);var questions=new ArrayList<SemanticTables.Row>();var answers=new ArrayList<SemanticTables.Row>();for(String category:LanguageProfile.requiredCategories()){var question=new SemanticTables.Row(SemanticTables.Kind.CENSUS,category,prototype.scope(),Map.of("category",category,"document","main.py"));questions.add(question);answers.add(new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,category,prototype.scope(),Map.of("obligation",question.identity().value(),"status",Set.of("A01","A11","A12").contains(category)?"RESOLVED":"NOT_APPLICABLE","targets",List.of())));}var request=new LanguageSemanticRequest(context,inventory,"root",module,"main","default","python","3.11",documents,tables.write(SemanticTables.Kind.CENSUS,questions.iterator(),()->false),resolution,prototype.provider(),prototype.tool(),policy.get(),prototype.workPolicy());selections.add(new SemanticContextCoordinator.Selection(module,request));outputs.put(module,tables.write(SemanticTables.Kind.OBSERVATION,answers.iterator(),()->false));}
        var coordinator=new SemanticContextCoordinator(content,tables);var plan=coordinator.plan(context,new PolyglotInventory.Report(inventory,entries,0,0),selections.iterator(),policy.get(),()->false);var result=coordinator.run(plan,r->r.module().equals("a")?Optional.of((request,cancel)->outputs.get("a")):Optional.empty(),r->Map.of(),Map.of(),Set.of(),policy::get,c->{},()->false);var store=new SemanticPublicationStore(directory.resolve("publications"),content,tables);store.commit(plan,result,Optional.empty(),policy::get,()->false);return new Fixture(plan.identity(),policy,store.queries(policy::get));
    }
    ArchitectureQueryApi.Response request(ArchitectureQueryApi api,String operation,Object input){return api.handle("POST","/api/v1/semantic/"+operation,"application/json",CanonicalJson.write(input).getBytes(StandardCharsets.UTF_8),()->false);}
    Map<String,Object> object(ArchitectureQueryApi.Response response){return GraphJson.object(GraphJson.read(new String(response.body(),StandardCharsets.UTF_8),GraphJson.Limits.defaults()));}
    @Test void publishedDenominatorContextPagesAndTypedGapsUseTheSameRevalidatedService()throws Exception{
        var f=fixture();var api=ArchitectureQueryApi.withSemanticQueries(new ArchitectureQueryApi(new InMemoryGraphStore()),f.queries());var summary=request(api,"summary",Map.of("plan",f.plan()));assertEquals(200,summary.status());assertEquals(CanonicalJson.write(f.queries().summary(f.plan(),()->false)),CanonicalJson.write(object(summary).get("data")));
        var first=request(api,"contexts",Map.of("plan",f.plan(),"size",1));assertEquals(200,first.status());var page=GraphJson.object(object(first).get("data"));var cursor=page.get("continuation");assertNotNull(cursor);assertEquals("a",GraphJson.object(GraphJson.array(page.get("items")).getFirst()).get("key"));assertEquals("PRODUCER_ASSERTION",GraphJson.object(page.get("summary")).get("trust"));assertEquals(false,GraphJson.object(page.get("summary")).get("complete"));
        var second=request(api,"contexts",Map.of("plan",f.plan(),"size",1,"cursor",cursor));assertEquals(200,second.status());var next=GraphJson.object(object(second).get("data"));assertEquals("b",GraphJson.object(GraphJson.array(next.get("items")).getFirst()).get("key"));assertNull(next.get("continuation"));var gaps=request(api,"gaps",Map.of("plan",f.plan(),"context","b"));assertEquals(200,gaps.status());assertTrue(new String(gaps.body(),StandardCharsets.UTF_8).contains("SEMANTIC_PROVIDER_NOT_REGISTERED"));assertEquals(409,request(api,"gaps",Map.of("plan",f.plan(),"context","a","cursor",cursor)).status());
        for(String view:List.of("CURRENT","HISTORY","CONFLICTS"))assertEquals(200,request(api,"answers",Map.of("plan",f.plan(),"context","a","view",view)).status());assertEquals(503,request(api,"answers",Map.of("plan",f.plan(),"context","b")).status());
        var rootGaps=request(api,"root-gaps",Map.of("plan",f.plan(),"size",1));assertEquals(200,rootGaps.status());assertTrue(new String(rootGaps.body(),StandardCharsets.UTF_8).contains("ROOT_SOURCE_CONTEXT_CENSUS_METHOD_UNQUALIFIED"));assertEquals(400,request(api,"root-gaps",Map.of("plan",f.plan(),"context","a")).status());assertEquals(409,request(api,"root-gaps",Map.of("plan",f.plan(),"cursor",cursor)).status());
    }
    @Test void invalidRequestsUnavailableServiceAndRevocationCannotCreateAuthority()throws Exception{
        var f=fixture();var base=new ArchitectureQueryApi(new InMemoryGraphStore());assertEquals(503,request(base,"summary",Map.of("plan",f.plan())).status());var api=ArchitectureQueryApi.withSemanticQueries(base,f.queries());assertThrows(IllegalArgumentException.class,()->ArchitectureQueryApi.withSemanticQueries(api,f.queries()));
        for(var value:List.of(Map.of("plan",f.plan(),"size",51),Map.of("plan",f.plan(),"size",new java.math.BigDecimal("1.5")),Map.of("plan",f.plan(),"command","native tool"),Map.of("plan",f.plan(),"cursor",Map.of("approved",true))))assertEquals(400,request(api,"contexts",value).status());
        var nullSize=new TreeMap<String,Object>(Map.of("plan",f.plan()));nullSize.put("size",null);assertEquals(400,request(api,"contexts",nullSize).status());
        assertEquals(405,api.handle("GET","/api/v1/semantic/summary","application/json",new byte[0],()->false).status());assertEquals(409,api.handle("POST","/api/v1/semantic/summary","application/json",new byte[0],()->true).status());assertEquals(404,request(api,"summary",Map.of("plan",ContentDigest.sha256Utf8("missing"))).status());f.policy().set(ContentDigest.sha256Utf8("revoked"));assertEquals(409,request(api,"summary",Map.of("plan",f.plan())).status());
    }
    @Test void semanticAndExecutionCompositionPreserveEachOtherInEitherOrder()throws Exception{
        var f=fixture();var id=ContentDigest.sha256Utf8("registered execution");var calls=new java.util.concurrent.atomic.AtomicInteger();com.evolution.analysis.execution.RegisteredExecutionPort port=(operation,registration)->{assertEquals(id,registration);calls.incrementAndGet();return new com.evolution.analysis.execution.RegisteredExecutionPort.View(id,f.plan(),id,"attempt","RUNNING",com.evolution.analysis.execution.ExecutionState.Phase.RUNNING,"1","1",com.evolution.analysis.execution.SandboxExecutor.Availability.AVAILABLE,"");};
        var first=ArchitectureQueryApi.withExecutions(ArchitectureQueryApi.withSemanticQueries(new ArchitectureQueryApi(new InMemoryGraphStore()),f.queries()),port);var second=ArchitectureQueryApi.withSemanticQueries(ArchitectureQueryApi.withExecutions(new ArchitectureQueryApi(new InMemoryGraphStore()),port),f.queries());
        for(var api:List.of(first,second)){assertEquals(200,request(api,"summary",Map.of("plan",f.plan())).status());assertEquals(200,api.handle("POST","/api/v2/executions/status","application/json",CanonicalJson.write(Map.of("registration",id)).getBytes(StandardCharsets.UTF_8),()->false).status());}assertEquals(2,calls.get());
    }
    @Test void loopbackHttpPreservesSemanticViewsAndSharedOriginProtection()throws Exception{
        var f=fixture();var api=ArchitectureQueryApi.withSemanticQueries(new ArchitectureQueryApi(new InMemoryGraphStore()),f.queries());try(var server=new ArchitectureHttpServer(api,0);var client=java.net.http.HttpClient.newHttpClient()){
            server.start();var uri=server.uri().resolve("/api/v1/semantic/answers");String body=CanonicalJson.write(Map.of("plan",f.plan(),"context","a","view","HISTORY"));var request=java.net.http.HttpRequest.newBuilder(uri).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertTrue(response.body().contains(SemanticQueryService.VERSION));assertTrue(response.body().contains("PRODUCER_ASSERTION"));var forbidden=java.net.http.HttpRequest.newBuilder(uri).header("Content-Type","application/json").header("Origin","https://untrusted.invalid").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();assertEquals(403,client.send(forbidden,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
