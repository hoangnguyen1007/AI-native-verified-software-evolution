package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.metrics.ArchitectureMetricsService;
import com.evolution.analysis.query.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.backend.query.QueryFixture.id;

class ArchitectureQueryApiTest {
    static byte[] bytes(Object value) { return CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8); }
    static Map<String,Object> body(ArchitectureQueryApi.Response response) {
        return GraphJson.object(GraphJson.read(new String(response.body(),StandardCharsets.UTF_8),GraphJson.Limits.defaults()));
    }
    static String route(Request r,String operation) { return "/api/v1/graphs/"+r.graph().value()+"/queries/"+operation; }
    static ArchitectureQueryApi.Response call(ArchitectureQueryApi api,Request r,String operation,Map<String,Object> args) {
        return api.handle("POST",route(r,operation),"application/json",bytes(Map.of("request",r,"arguments",args)),() -> false);
    }
    static void parity(ArchitectureQueryApi api,Request r,String operation,Map<String,Object> args,Object expected) {
        var response=call(api,r,operation,args); assertEquals(200,response.status(),() -> new String(response.body(),StandardCharsets.UTF_8));
        var result=body(response);
        assertEquals(CanonicalJson.write(expected),CanonicalJson.write(result.get("data")));
        assertEquals(ContentDigest.sha256(bytes(expected)).value(),result.get("resultIdentity"));
        assertEquals(ArchitectureQueryApi.VERSION,result.get("apiVersion"));
    }
    @Test void everyPublicQueryDelegatesWithoutChangingCanonicalValues() {
        var f=new QueryFixture().node("a").node("b").node("c").edge("a","b").edge("b","c").edge("c","a");
        var api=new ArchitectureQueryApi(f.store()); var q=new ArchitectureMetricsService(f.graph()).queries(); var r=f.request(); var p=Page.first();
        parity(api,r,"summary",Map.of(),q.summary(r)); parity(api,r,"provenance",Map.of(),q.provenance(r));
        parity(api,r,"entities",Map.of("search",""),q.entities(r,"",p)); parity(api,r,"entity",Map.of("identity",id("a")),q.entity(r,id("a")));
        parity(api,r,"edges",Map.of(),q.edges(r,p)); parity(api,r,"dependencies",Map.of("identity",id("a")),q.dependencies(r,id("a"),p));
        parity(api,r,"relationships",Map.of(),q.relationships(r,p)); parity(api,r,"expand",Map.of("identity",id("a")),q.expand(r,id("a")));
        parity(api,r,"paths",Map.of("source",id("a"),"target",id("c")),q.findPaths(r,id("a"),id("c")));
        parity(api,r,"shortest-path",Map.of("source",id("a"),"target",id("c")),q.shortestPath(r,id("a"),id("c")));
        parity(api,r,"cycles",Map.of(),q.findCycles(r)); parity(api,r,"reachability",Map.of("identity",id("a")),q.reachability(r,id("a")));
        var incoming=new Request(r.graph(),r.context(),r.scope(),r.projection(),Direction.INCOMING,r.relationshipKinds(),r.population(),false,r.realization(),r.limits());
        parity(api,incoming,"impact",Map.of("identity",id("c")),q.impact(incoming,id("c")));
        parity(api,r,"evidence",Map.of("reference",f.base.identity()),q.evidence(r,f.base.identity()));
        parity(api,r,"explain",Map.of("identity",id("a")),q.explain(r,id("a"),p)); parity(api,r,"gaps",Map.of(),q.gaps(r,p));
        parity(api,r,"spring",Map.of("kinds",Set.of("ENDPOINT")),q.spring(r,Set.of("ENDPOINT"),p));
        parity(api,r,"configurations",Map.of(),q.configurations(r,p)); parity(api,r,"artifacts",Map.of("families",Set.of("fixture.context")),q.artifacts(r,Set.of("fixture.context"),p));
        parity(api,r,"metric-catalog",Map.of(),q.metricCatalog(r,p)); parity(api,r,"metric",Map.of("metric","metric.inventory","subject",r.context()),q.metric(r,"metric.inventory",r.context()));
        parity(api,r,"comparison-preparation",Map.of("graph",r.graph(),"context",r.context()),q.comparisonPreparation(r,f.graph(),r.context()));
        assertEquals(22,ArchitectureQueryApi.OPERATIONS.size()); assertEquals("m7.2-v2",VERSION.version());
    }
    @Test void paginationKeepsTotalsAndBindsCursorToFiltersAcrossSnapshotReload() {
        var f=new QueryFixture(); for(int i=0;i<5;i++) f.node("n"+i);
        var api=new ArchitectureQueryApi(f.store()); var r=f.request(); var p=new Page(2,Optional.empty());
        var first=api.handle("POST",route(r,"entities"),"application/json",bytes(Map.of("request",r,"page",p)),() -> false);
        var data=GraphJson.object(body(first).get("data")); assertEquals(new java.math.BigDecimal(5),data.get("exactTotal"));
        String cursor=GraphJson.string(data.get("continuation"));
        var next=Map.of("request",r,"page",new Page(2,Optional.of(cursor)));
        var reload=new GraphSnapshotCodec().decode(new GraphSnapshotCodec().encode(f.graph()),GraphJson.Limits.defaults());
        var store=new InMemoryGraphStore(); store.save(reload);
        var second=api.handle("POST",route(r,"entities"),"application/json",bytes(next),() -> false);
        assertArrayEquals(second.body(),new ArchitectureQueryApi(store).handle("POST",route(r,"entities"),"application/json",bytes(next),() -> false).body());
        assertEquals(2,GraphJson.array(GraphJson.object(body(second).get("data")).get("items")).size());
        assertEquals(400,api.handle("POST",route(r,"entities"),"application/json",bytes(Map.of("request",r,"page",new Page(2,Optional.of(cursor)),"arguments",Map.of("search","changed"))),() -> false).status());
        assertEquals(400,api.handle("POST",route(r,"summary"),"application/json",bytes(Map.of("request",r,"page",p)),() -> false).status());
        var invalid=new TreeMap<String,Object>(); invalid.put("request",r); invalid.put("page",null);
        assertEquals(400,api.handle("POST",route(r,"entities"),"application/json",bytes(invalid),() -> false).status());
    }
    @Test void mutuallyExclusiveEdgesNeverBecomeOneFeasibleHttpPath() {
        var f=new QueryFixture().node("a").node("b").node("c").conditional("a","b","dev").conditional("b","c","prod");
        var api=new ArchitectureQueryApi(f.store()); var r=f.request();
        var paths=body(call(api,r,"paths",Map.of("source",id("a"),"target",id("c"))));
        assertTrue(GraphJson.array(GraphJson.object(paths.get("data")).get("items")).isEmpty());
        var realized=new Request(r.graph(),r.context(),r.scope(),Projection.REALIZED,r.direction(),r.relationshipKinds(),r.population(),false,
                Optional.of(new Realization(Optional.of(id("dev")),Map.of())),r.limits());
        var result=GraphJson.object(body(call(api,realized,"edges",Map.of())).get("data"));
        assertEquals(1,GraphJson.array(result.get("items")).size());
        var may=new Request(r.graph(),r.context(),r.scope(),Projection.MAY,r.direction(),r.relationshipKinds(),r.population(),false,Optional.empty(),r.limits());
        result=GraphJson.object(body(call(api,may,"edges",Map.of())).get("data")); assertEquals(2,GraphJson.array(result.get("items")).size());
    }
    @Test void resourceAndCancellationOutcomesRetainTypedGapsAndFoundEvidence() {
        var f=new QueryFixture().node("a").node("b").edge("a","b").edge("b","a"); var r=f.request();
        var api=new ArchitectureQueryApi(f.store());
        var response=api.handle("POST",route(r,"cycles"),"application/json",bytes(Map.of("request",r)),() -> true);
        var data=GraphJson.object(body(response).get("data")); assertEquals(200,response.status()); assertEquals("CANCELLED",data.get("operation"));
        assertEquals("PARTIAL",data.get("status")); assertFalse(GraphJson.array(data.get("gaps")).isEmpty()); assertNull(data.get("exactTotal"));
        var limited=new Request(r.graph(),r.context(),r.scope(),r.projection(),r.direction(),r.relationshipKinds(),r.population(),false,Optional.empty(),
                new Limits(20,1,50,100,100,1000,100,1000));
        data=GraphJson.object(body(call(api,limited,"cycles",Map.of())).get("data"));
        assertEquals("PARTIAL",data.get("status")); assertEquals("STEPS",GraphJson.object(data.get("bounds")).get("stopReason"));
        assertFalse(GraphJson.array(data.get("gaps")).isEmpty());
    }
    @Test void unavailableMetricsAndMissingEvidenceRemainQualified() {
        var f=new QueryFixture().node("a"); var r=f.request();
        var api=new ArchitectureQueryApi(f.store(),new PublishedReports(),ApiLimits.defaults(),(g,guard) -> new ArchitectureQueryService(g,guard,Optional.empty()));
        var data=GraphJson.object(body(call(api,r,"metric-catalog",Map.of())).get("data"));
        assertEquals("PARTIAL",data.get("status")); assertEquals("METRICS_PROVIDER_UNAVAILABLE",GraphJson.object(GraphJson.array(data.get("gaps")).getFirst()).get("reasonCode"));
        data=GraphJson.object(body(call(api,r,"evidence",Map.of("reference",ContentDigest.sha256Utf8("missing")))).get("data"));
        assertEquals("PARTIAL",data.get("status")); assertTrue(GraphJson.array(data.get("items")).isEmpty());
    }
    @Test void exactSourceCoordinatesSurviveWhileSecretsAndHostPathsAreRedacted() {
        var f=new QueryFixture();
        var secret=GraphEvidence.capture("source.configuration",Map.of("password","never-show-secret","path","C:/private/host"));
        var source=GraphEvidence.capture("java.occurrence",Map.of("status","PARTIAL","span",Map.of("document",id("document"),"startLine",3,"startColumn",4,"endLine",5,"endColumn",9),
                "derivation",Map.of("kind","DERIVED","provider",Map.of("id","test.provider","version","1"))));
        f.evidence.addAll(List.of(secret,source)); f.nodes.add(new KnowledgeGraph.Node(id("a"),f.context,"TYPE",List.of(secret.identity(),source.identity())));
        var api=new ArchitectureQueryApi(f.store()); var r=f.request();
        var response=call(api,r,"evidence",Map.of("reference",source.identity()));
        assertTrue(new String(response.body(),StandardCharsets.UTF_8).contains("\"endColumn\":9"));
        response=call(api,r,"evidence",Map.of("reference",secret.identity()));
        String json=new String(response.body(),StandardCharsets.UTF_8); assertFalse(json.contains("never-show-secret")); assertFalse(json.contains("private/host")); assertTrue(json.contains("\"redacted\":true"));
    }
    @Test void malformedRequestsHaveDeterministicSanitizedErrors() {
        var f=new QueryFixture().node("a"); var api=new ArchitectureQueryApi(f.store()); var r=f.request();
        for(byte[] bad : List.of("{\"request\":{},\"request\":{}}".getBytes(StandardCharsets.UTF_8),new byte[]{(byte)0xc3,0x28},bytes(Map.of("request",r,"password","secret")))) {
            var result=api.handle("POST",route(r,"summary"),"application/json",bad,() -> false);
            assertEquals(400,result.status()); assertFalse(new String(result.body(),StandardCharsets.UTF_8).contains("secret"));
            assertArrayEquals(result.body(),api.handle("POST",route(r,"summary"),"application/json",bad,() -> false).body());
        }
        assertEquals(415,api.handle("POST",route(r,"summary"),"text/plain",bytes(Map.of("request",r)),() -> false).status());
        assertEquals(405,api.handle("DELETE",route(r,"summary"),"application/json",new byte[0],() -> false).status());
        assertEquals(404,api.handle("GET","/api/v2",null,new byte[0],() -> false).status());
        assertEquals(404,api.handle("GET","/api/v1/graphs/"+ContentDigest.sha256Utf8("missing").value(),null,new byte[0],() -> false).status());
        var args=Map.<String,Object>of("identity",id("a"),"ignored",true); assertEquals(400,call(api,r,"entity",args).status());
        var foreign=new Request(r.graph(),id("foreign-context"),r.scope(),r.projection(),r.direction(),r.relationshipKinds(),r.population(),false,Optional.empty(),r.limits());
        assertEquals(400,call(api,foreign,"summary",Map.of()).status());
    }
    @Test void requestStructureAndResponseLimitsNeverSilentlyTrimPayloads() {
        var f=new QueryFixture().node("a"); var r=f.request();
        var limits=new ApiLimits(1024,1024,4,100,1,5000); var api=new ArchitectureQueryApi(f.store(),new PublishedReports(),limits);
        assertEquals(413,api.handle("POST",route(r,"summary"),"application/json",new byte[1025],() -> false).status());
        assertEquals(413,api.handle("POST",route(r,"summary"),"application/json","{\"a\":[[[[[1]]]]]}".getBytes(StandardCharsets.UTF_8),() -> false).status());
        var big=new QueryFixture(); for(int i=0;i<30;i++) big.node("node"+i);
        var bounded=new ArchitectureQueryApi(big.store(),new PublishedReports(),new ApiLimits(65536,1024,32,10000,1,5000));
        var result=call(bounded,big.request(),"entities",Map.of()); assertEquals(422,result.status()); assertEquals("RESPONSE_TOO_LARGE",GraphJson.object(body(result).get("error")).get("code"));
    }
    @Test void unexpectedProviderAndStoreFailuresCannotLeakExceptions() {
        var f=new QueryFixture().node("a"); var r=f.request();
        var broken=new ArchitectureQueryApi(f.store(),new PublishedReports(),ApiLimits.defaults(),(g,guard) -> {throw new IllegalStateException("secret C:/host");});
        var result=call(broken,r,"summary",Map.of()); assertEquals(500,result.status()); assertFalse(new String(result.body(),StandardCharsets.UTF_8).contains("secret"));
        GraphStore failed=new GraphStore() {
            public void save(KnowledgeGraph graph,java.util.function.BooleanSupplier cancelled) { throw new UnsupportedOperationException(); }
            public Optional<KnowledgeGraph> load(ContentDigest identity) { throw new GraphStoreException(GraphStoreException.Reason.CORRUPT_SNAPSHOT); }
        };
        assertEquals(503,call(new ArchitectureQueryApi(failed),r,"summary",Map.of()).status());
    }
}
