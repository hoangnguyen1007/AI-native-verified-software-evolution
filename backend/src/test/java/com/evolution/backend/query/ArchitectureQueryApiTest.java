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
    @Test void preparingRawGraphPagesReturnsATypedRetryableOutcome() {
        var api = ArchitectureQueryApi.withPartitionedGraphs(new ArchitectureQueryApi(new InMemoryGraphStore()),
                cancelled -> new PartitionedGraphReader() {
                    @Override public Page<Partition> partitions(ContentDigest graph, int size, Optional<String> cursor) throws java.io.IOException {
                        throw new IndexPending(size > 1);
                    }
                    @Override public Page<KnowledgeGraph.Node> nodes(Partition partition, int size, Optional<String> cursor) {
                        throw new AssertionError("Unexpected node request");
                    }
                    @Override public Page<KnowledgeGraph.Edge> adjacency(Partition partition, String node, boolean incoming, int size, Optional<String> cursor) {
                        throw new AssertionError("Unexpected adjacency request");
                    }
                });
        var graph = ContentDigest.sha256Utf8("raw-page-preparation");
        for (int size : List.of(1, 2)) {
            var response = api.handle("POST", "/api/v1/graph-pages/" + graph.value() + "/partitions",
                    "application/json", ("{\"size\":" + size + "}").getBytes(StandardCharsets.UTF_8), () -> false);
            assertEquals(503, response.status());
            assertEquals(size == 1 ? "PARTITION_INDEX_PREPARING" : "PARTITION_INDEX_BUSY",
                    GraphJson.object(body(response).get("error")).get("code"));
        }
    }
    @Test void currentAndHistoricalGapViewsAreExplicitAndKeepTheirDenominators(){
        var f=new QueryFixture().node("a");
        f.nodes.add(new KnowledgeGraph.Node(id("old-gap"),f.context,"HISTORICAL_CAPABILITY_GAP",List.of(f.evidence.getFirst().identity())));
        f.nodes.add(new KnowledgeGraph.Node(id("active-gap"),f.context,"CAPABILITY_GAP",List.of(f.evidence.getFirst().identity())));
        var api=new ArchitectureQueryApi(f.store());
        var current=new String(call(api,f.request(),"gaps",Map.of()).body(),StandardCharsets.UTF_8);
        var history=new String(call(api,f.request(),"gaps",Map.of("view","HISTORY")).body(),StandardCharsets.UTF_8);
        var all=new String(call(api,f.request(),"gaps",Map.of("view","ALL")).body(),StandardCharsets.UTF_8);
        assertTrue(current.contains(id("active-gap")));assertFalse(current.contains(id("old-gap")));
        assertTrue(history.contains(id("old-gap")));assertFalse(history.contains(id("active-gap")));
        assertTrue(all.contains(id("old-gap")));assertTrue(all.contains(id("active-gap")));
        assertEquals(400,call(api,f.request(),"gaps",Map.of("view","INVENTED")).status());
    }
    @Test void visualDepthCannotBeFractionalOverflowedOrIgnoredWithoutASeed(){
        var f=new QueryFixture().node("a").node("b").edge("a","b");var api=new ArchitectureQueryApi(f.store());
        for(Object depth:List.of(new java.math.BigDecimal("1.5"),new java.math.BigDecimal("4294967297"),"2",0))
            assertEquals(400,call(api,f.request(),"visual-graph",Map.of("seed",id("a"),"depth",depth)).status());
        assertEquals(400,call(api,f.request(),"visual-graph",Map.of("depth",2)).status());
    }
    @Test void configurationChoicesExposeProfileBooleansWhilePropertyValuesStayRedacted(){
        var f=new QueryFixture().node("a").node("b").conditional("a","b","dev");
        var proof=GraphEvidence.capture("spring.world",Map.of("assignment",Map.of("baseline",List.of(
                Map.of("variable",Map.of("kind","PROFILE","name","dev"),"value",Map.of("kind","BOOLEAN","bool",true)),
                Map.of("variable",Map.of("kind","PROPERTY","name","api.key"),"value",Map.of("kind","EXACT","exact","PRIVATE_SENTINEL"))))));
        f.evidence.add(proof);f.nodes.replaceAll(node->node.identity().equals(id("dev"))?new KnowledgeGraph.Node(node.identity(),f.context,node.kind(),List.of(proof.identity())):node);
        var response=call(new ArchitectureQueryApi(f.store()),f.request(),"realizations",Map.of());assertEquals(200,response.status());
        String json=new String(response.body(),StandardCharsets.UTF_8);assertFalse(json.contains("PRIVATE_SENTINEL"));assertFalse(json.contains("api.key"));
        assertTrue(json.contains("\"profiles\":{\"dev\":true}"));
    }
    @Test void structureChildrenArePagedContextBoundAndNeverDependencyTargets() {
        var f=new QueryFixture().node("parent").node("child").node("dependency").edge("parent","dependency");
        f.edges.add(new KnowledgeGraph.Edge(id("contains"),f.context,"graph.declares",id("parent"),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(id("child")),Optional.empty()),List.of(f.base.identity()),Optional.empty()));
        var api=new ArchitectureQueryApi(f.store());
        var response=call(api,f.request(),"children",Map.of("identity",id("parent")));
        assertEquals(200,response.status());
        var items=GraphJson.array(GraphJson.object(body(response).get("data")).get("items"));
        assertEquals(1,items.size());assertEquals(id("child"),GraphJson.object(items.getFirst()).get("identity"));
        assertEquals(400,call(api,f.request(),"children",Map.of("identity",id("foreign"))).status());
    }
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
        parity(api,r,"visual-graph",Map.of(),new com.evolution.analysis.query.projection.GraphProjectionService(q).project(r,Optional.empty()));
        parity(api,r,"children",Map.of("identity",id("a")),q.children(r,id("a"),p));
        parity(api,r,"realizations",Map.of(),q.realizations(r,p));
        parity(api,r,"metric-definition",Map.of("metric","metric.inventory"),q.metricDefinition(r,"metric.inventory"));
        assertEquals(26,ArchitectureQueryApi.OPERATIONS.size()); assertEquals("m8.3-v1",VERSION.version());
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
    @Test void graphDiscoveryEnrichesCapturedContextMetadataWithoutInventingOrLeakingHostPaths() {
        var f = new QueryFixture();
        var modId = "module:sha256:" + "1".repeat(64);
        var analysisId = new com.evolution.analysis.contract.identity.AnalysisIdentity("analysis:sha256:" + "2".repeat(64));
        var javaEvidence = GraphEvidence.capture("analysis.manifest", Map.of(
                "modules", List.of(Map.of("identity", modId, "path", "backend")),
                "configuration", Map.of("values", Map.of("java.module", modId, "java.source-set", "MAIN"))));
        var springId = KnowledgeGraph.derivedIdentity("spring-context", "backend-spring");
        var springEvidence = GraphEvidence.capture("spring.context", Map.of(
                "analysis", analysisId.value(),
                "build", Map.of("moduleSourceSet", Map.of("module", modId, "sourceSet", "MAIN"))));
        var polyglotId = ContentDigest.sha256Utf8("polyglot-ts").value();
        var polyglotEvidence = GraphEvidence.capture("polyglot.context", Map.of(
                "request", Map.of("module", "web-app", "root", ".", "language", "typescript", "sourceSet", "main")));
        var unsafeId = ContentDigest.sha256Utf8("polyglot-unsafe").value();
        var unsafeEvidence = GraphEvidence.capture("polyglot.context", Map.of(
                "request", Map.of("module", "C:/private/host/repo", "root", "../escape", "language", "python")));
        var contexts = List.of(
                new KnowledgeGraph.Context(f.snapshot.value(), f.snapshot, Optional.empty(), f.base.identity()),
                new KnowledgeGraph.Context(analysisId.value(), f.snapshot, Optional.of(analysisId), javaEvidence.identity()),
                new KnowledgeGraph.Context(springId, f.snapshot, Optional.of(analysisId), springEvidence.identity()),
                new KnowledgeGraph.Context(polyglotId, f.snapshot, Optional.empty(), polyglotEvidence.identity()),
                new KnowledgeGraph.Context(unsafeId, f.snapshot, Optional.empty(), unsafeEvidence.identity()));
        var graph = new KnowledgeGraph(f.snapshot, contexts, List.of(), List.of(),
                List.of(f.base, javaEvidence, springEvidence, polyglotEvidence, unsafeEvidence));
        var store = new InMemoryGraphStore();
        store.save(graph);
        var api = new ArchitectureQueryApi(store);
        var response = api.handle("GET", "/api/v1/graphs/" + graph.identity().value(), null, new byte[0], () -> false);
        assertEquals(200, response.status());
        String raw = new String(response.body(), StandardCharsets.UTF_8);
        assertFalse(raw.contains("private/host"));
        assertFalse(raw.contains("../escape"));
        var items = GraphJson.array(GraphJson.object(body(response).get("data")).get("contexts"));
        assertEquals(5, items.size());
        var byId = new HashMap<String, Map<String, Object>>();
        for (var item : items) {
            var map = GraphJson.object(item);
            byId.put(GraphJson.string(map.get("identity")), map);
        }
        assertEquals("Whole repository", byId.get(f.snapshot.value()).get("label"));
        assertEquals("repository", byId.get(f.snapshot.value()).get("kind"));

        var javaCtx = byId.get(analysisId.value());
        assertEquals("analysis.manifest", javaCtx.get("family"));
        assertEquals("java", javaCtx.get("kind"));
        assertEquals("java", javaCtx.get("language"));
        assertEquals("backend", javaCtx.get("modulePath"));
        assertEquals("MAIN", javaCtx.get("sourceSet"));
        assertEquals("backend / main", javaCtx.get("label"));
        assertEquals(analysisId.value(), javaCtx.get("analysis"));

        var springCtx = byId.get(springId);
        assertEquals("spring.context", springCtx.get("family"));
        assertEquals("spring", springCtx.get("kind"));
        assertEquals("backend", springCtx.get("modulePath"));
        assertEquals("backend / Spring", springCtx.get("label"));

        var tsCtx = byId.get(polyglotId);
        assertEquals("polyglot.context", tsCtx.get("family"));
        assertEquals("polyglot", tsCtx.get("kind"));
        assertEquals("typescript", tsCtx.get("language"));
        assertEquals("web-app", tsCtx.get("modulePath"));
        assertEquals("web-app · TypeScript", tsCtx.get("label"));

        var pyCtx = byId.get(unsafeId);
        assertEquals("polyglot", pyCtx.get("kind"));
        assertEquals("python", pyCtx.get("language"));
        assertNull(pyCtx.get("modulePath"));
        assertEquals("Python", pyCtx.get("label"));

        // Verify disposable FileGraphStore sidecar cache serves identical enriched contexts across fresh API instances.
        try {
            var dir = java.nio.file.Files.createTempDirectory("evo-discovery-cache-test-");
            try {
                var fileStore = new com.evolution.analysis.filesystem.FileGraphStore(dir);
                fileStore.save(graph);
                var firstApi = new ArchitectureQueryApi(fileStore);
                var firstResp = firstApi.handle("GET", "/api/v1/graphs/" + graph.identity().value(), null, new byte[0], () -> false);
                assertEquals(200, firstResp.status());
                var cacheFile = dir.resolve(com.evolution.analysis.filesystem.GraphBinaryCache.CACHE_DIR_NAME)
                        .resolve(graph.identity().value().substring(7) + ".contexts.v1.json");
                assertTrue(java.nio.file.Files.isRegularFile(cacheFile));
                var secondApi = new ArchitectureQueryApi(fileStore);
                var secondResp = secondApi.handle("GET", "/api/v1/graphs/" + graph.identity().value(), null, new byte[0], () -> false);
                assertEquals(200, secondResp.status());
                assertArrayEquals(firstResp.body(), secondResp.body());
            } finally {
                try (var walk = java.nio.file.Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
                }
            }
        } catch (java.io.IOException ioe) {
            fail(ioe);
        }
    }

    @Test void architectureScoreV2RouteEvaluatesWholeRepositoryContextAndCustomPolicy() {
        var repo = com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("https://example.test/api-score-v2.git");
        var snapshot = com.evolution.analysis.contract.identity.SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("api-v2-snap"));
        var analysisId = com.evolution.analysis.contract.identity.AnalysisIdentity.fromCanonicalManifestInputs("api-v2-mod");
        String analysisCtx = analysisId.value();
        var module = com.evolution.analysis.contract.identity.ModuleIdentity.from(repo, ".");
        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snapshot));
        var manifestEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", module, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", module, "path", "."));

        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manifestEv, modEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        nodes.add(new KnowledgeGraph.Node(module.value(), analysisCtx, "MODULE", List.of(modEv.identity())));
        var typeIds = new HashMap<String, String>();
        var pkgIds = new HashSet<String>();
        for (var spec : List.of(
                List.of("com.acme.web", "OrderController"),
                List.of("com.acme.web", "BillingController"),
                List.of("com.acme.service", "OrderService"),
                List.of("com.acme.service", "BillingService"),
                List.of("com.acme.repository", "OrderRepository"),
                List.of("com.acme.repository", "BillingRepository"))) {
            var typeEntity = com.evolution.analysis.contract.semantic.Entity.create(
                    com.evolution.analysis.contract.semantic.EntityOrigin.PROJECT,
                    com.evolution.analysis.contract.identity.EntityScope.project(module),
                    com.evolution.analysis.contract.semantic.EntityKind.TYPE,
                    com.evolution.analysis.frontend.JavaSymbolName.topLevelType(spec.get(0), spec.get(1)).canonicalName(),
                    Optional.empty());
            var decl = GraphEvidence.capture("java.declaration", Map.of("entity", typeEntity, "spelling", spec.get(1), "status", "RESOLVED"));
            var shape = GraphEvidence.capture("java.type-shape", Map.of("type", typeEntity.identity().value(), "kind", "CLASS", "abstractType", false));
            evidence.add(decl);
            evidence.add(shape);
            String pkgId = com.evolution.analysis.contract.identity.EntityIdentity.from(
                    com.evolution.analysis.contract.semantic.EntityOrigin.PROJECT,
                    com.evolution.analysis.contract.identity.EntityScope.project(module),
                    com.evolution.analysis.contract.semantic.EntityKind.PACKAGE,
                    com.evolution.analysis.frontend.JavaSymbolName.packageName(spec.get(0)).canonicalName()).value();
            if (pkgIds.add(pkgId)) {
                nodes.add(new KnowledgeGraph.Node(pkgId, analysisCtx, "PACKAGE", List.of(decl.identity())));
                edges.add(new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("c", List.of(module.value(), pkgId)),
                        analysisCtx, "graph.contains", module.value(),
                        new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(pkgId), Optional.empty()),
                        List.of(decl.identity()), Optional.empty()));
            }
            nodes.add(new KnowledgeGraph.Node(typeEntity.identity().value(), analysisCtx, "TYPE", List.of(decl.identity(), shape.identity())));
            edges.add(new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("c", List.of(pkgId, typeEntity.identity().value())),
                    analysisCtx, "graph.contains", pkgId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(typeEntity.identity().value()), Optional.empty()),
                    List.of(decl.identity()), Optional.empty()));
            typeIds.put(spec.get(1), typeEntity.identity().value());
        }
        int eIdx = 0;
        for (var dep : List.of(
                List.of("OrderController", "OrderService"),
                List.of("OrderService", "OrderRepository"),
                List.of("BillingController", "BillingService"),
                List.of("BillingService", "BillingRepository"))) {
            edges.add(new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("dep", List.of(dep.get(0), dep.get(1), eIdx++)),
                    analysisCtx, "graph.depends-on", typeIds.get(dep.get(0)),
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(typeIds.get(dep.get(1))), Optional.empty()),
                    List.of(manifestEv.identity()), Optional.empty()));
        }
        var graph = new KnowledgeGraph(snapshot,
                List.of(
                        new KnowledgeGraph.Context(snapshot.value(), snapshot, Optional.empty(), snapEv.identity()),
                        new KnowledgeGraph.Context(analysisCtx, snapshot, Optional.of(analysisId), manifestEv.identity())),
                nodes, edges, evidence);
        var store = new InMemoryGraphStore();
        store.save(graph);
        var api = new ArchitectureQueryApi(store);

        // Capability flags
        var capsResp = api.handle("GET", "/api/v1", null, new byte[0], () -> false);
        assertEquals(200, capsResp.status());
        var caps = GraphJson.object(body(capsResp).get("data"));
        assertEquals(true, caps.get("architectureScoreV2Enabled"));
        assertEquals(true, caps.get("progressiveAnalysisEnabled"));
        assertEquals(true, caps.get("incrementalDeltaEnabled"));
        assertEquals(true, caps.get("capturedFilesEnabled"));
        assertEquals(true, caps.get("repoMapEnabled"));

        // Whole Repository default (empty body & {})
        String scoreUrl = "/api/v1/graphs/" + graph.identity().value() + "/architecture-score";
        var wholeResp = api.handle("POST", scoreUrl, "application/json", new byte[0], () -> false);
        assertEquals(200, wholeResp.status(), () -> body(wholeResp).toString());
        var wholeResp2 = api.handle("POST", scoreUrl, "application/json", bytes(Map.of()), () -> false);
        assertArrayEquals(wholeResp.body(), wholeResp2.body());
        var wholeData = GraphJson.object(body(wholeResp).get("data"));
        assertEquals("WHOLE_REPOSITORY", wholeData.get("scopeMode"));
        assertEquals("COMPLETE", wholeData.get("status"));
        assertEquals(7, GraphJson.array(wholeData.get("dimensions")).size());
        assertEquals(1, GraphJson.array(wholeData.get("contextBreakdown")).size());

        // Single Java context
        var ctxResp = api.handle("POST", scoreUrl, "application/json", bytes(Map.of("context", analysisCtx)), () -> false);
        assertEquals(200, ctxResp.status(), () -> body(ctxResp).toString());
        var ctxData = GraphJson.object(body(ctxResp).get("data"));
        assertEquals("JAVA_CONTEXT", ctxData.get("scopeMode"));
        assertEquals("Layered Architecture", GraphJson.object(ctxData.get("inferredLayers")).get("detectedStyle"));
        assertEquals("HIGH", GraphJson.object(ctxData.get("confidence")).get("tier"));

        // Custom YAML policy
        String customYaml = """
                schema: architecture-policy:v1
                id: test.no-web-to-repo
                version: "1"
                rules:
                  - id: test.no-web-to-repo
                    version: "1"
                    kind: FORBIDDEN_DEPENDENCY
                    severity: ERROR
                    scope: TYPE
                    relationships: [graph.depends-on]
                    from: {packages: ["com.acme.web.**"], origins: [PROJECT]}
                    to: {packages: ["com.acme.repository.**"], origins: [PROJECT]}
                """;
        var policyResp = api.handle("POST", scoreUrl, "application/json",
                bytes(Map.of("context", analysisCtx, "policy", customYaml)), () -> false);
        assertEquals(200, policyResp.status(), () -> body(policyResp).toString());
        assertEquals("COMPLETE", GraphJson.object(body(policyResp).get("data")).get("status"));

        // Invalid args & missing context
        assertEquals(404, api.handle("POST", scoreUrl, "application/json", bytes(Map.of("context", "unknown-ctx")), () -> false).status());
        assertEquals(400, api.handle("POST", scoreUrl, "application/json",
                bytes(Map.of("policy", customYaml, "report", ContentDigest.sha256Utf8("r").value())), () -> false).status());
    }

    @Test void progressivePreviewStoreSupportsReadOnlyQueriesEnforces409AndReturns410Superseded() {
        var durableFx = new QueryFixture().node("OrderController");
        var durableStore = durableFx.store();
        var durableId = durableFx.graph().identity();

        var repo = com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("https://example.test/preview-api.git");
        var snapshot = com.evolution.analysis.contract.identity.SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("preview-api-snap"));
        var skeleton = new com.evolution.backend.analysis.FastJavaSkeletonScanner().scan(
                List.of(new com.evolution.backend.analysis.FastJavaSkeletonScanner.SourceFile(
                        "core/src/main/java/com/acme/web/OrderController.java", "core",
                        ContentDigest.sha256Utf8("package com.acme.web; class OrderController {}"),
                        "package com.acme.web;\npublic class OrderController {}\n")),
                () -> false);
        var previewGraph = new com.evolution.backend.analysis.PreliminaryGraphBuilder().build(
                com.evolution.backend.analysis.PreliminaryGraphBuilder.Input.stage1(
                        repo, snapshot,
                        List.of(new com.evolution.backend.analysis.PreliminaryGraphBuilder.ModuleInfo("core", "core", "MAVEN", 1)),
                        skeleton, List.of("core")));
        var previewId = previewGraph.identity();

        // gracePeriodMillis = 0 so markJobFinished transitions immediately to SUPERSEDED (410)
        var previewStore = new com.evolution.backend.analysis.WorkbenchPreviewStore(2, 0L);
        String jobId = "22222222-2222-2222-2222-222222222222";
        previewStore.putPreview(jobId, previewGraph,
                com.evolution.backend.analysis.AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE, 0, 1, List.of("core"));

        var api = ArchitectureQueryApi.withPreviewStore(new ArchitectureQueryApi(durableStore), previewStore);

        // Discovery on canonical durable graph -> authoritative: true, DEEP_SEMANTIC_COMPLETE
        var durableDisc = api.handle("GET", "/api/v1/graphs/" + durableId.value(), null, new byte[0], () -> false);
        assertEquals(200, durableDisc.status());
        var durableData = GraphJson.object(body(durableDisc).get("data"));
        assertEquals(true, durableData.get("authoritative"));
        assertEquals("DEEP_SEMANTIC_COMPLETE", durableData.get("truthTier"));

        // Discovery on active preview graph -> authoritative: false, PRELIMINARY_STRUCTURE, previewMeta
        var prevDisc = api.handle("GET", "/api/v1/graphs/" + previewId.value(), null, new byte[0], () -> false);
        assertEquals(200, prevDisc.status(), () -> body(prevDisc).toString());
        var prevData = GraphJson.object(body(prevDisc).get("data"));
        assertEquals(false, prevData.get("authoritative"));
        assertEquals("PRELIMINARY_STRUCTURE", prevData.get("truthTier"));
        var prevMeta = GraphJson.object(prevData.get("previewMeta"));
        assertEquals(jobId, prevMeta.get("jobId"));
        assertEquals(List.of("core"), GraphJson.array(prevMeta.get("pendingModules")));

        // Read-only routes work over active preview
        assertEquals(200, api.handle("GET", "/api/v1/graphs/" + previewId.value() + "/captured-files", null, new byte[0], () -> false).status());
        assertEquals(200, api.handle("GET", "/api/v1/graphs/" + previewId.value() + "/repo-map", null, new byte[0], () -> false).status());
        var prevScore = api.handle("POST", "/api/v1/graphs/" + previewId.value() + "/architecture-score",
                "application/json", bytes(Map.of()), () -> false);
        assertEquals(200, prevScore.status(), () -> body(prevScore).toString());
        var prevScoreData = GraphJson.object(body(prevScore).get("data"));
        assertEquals("WITHHELD", prevScoreData.get("status"));
        assertNull(prevScoreData.get("score"));
        assertTrue(GraphJson.array(prevScoreData.get("reasons")).contains("PRELIMINARY_GRAPH_PENDING"));

        // Policy, suggested-policy, and repair endpoints strictly enforce 409 PRELIMINARY_GRAPH_PENDING
        var policyAttempt = api.handle("POST", "/api/v1/graphs/" + previewId.value() + "/policy-assessment",
                "application/json", bytes(Map.of("context", snapshot.value())), () -> false);
        assertEquals(409, policyAttempt.status());
        assertEquals("PRELIMINARY_GRAPH_PENDING", GraphJson.object(body(policyAttempt).get("error")).get("code"));

        var suggestAttempt = api.handle("POST", "/api/v1/graphs/" + previewId.value() + "/suggested-policy",
                "application/json", bytes(Map.of("context", snapshot.value())), () -> false);
        assertEquals(409, suggestAttempt.status());
        assertEquals("PRELIMINARY_GRAPH_PENDING", GraphJson.object(body(suggestAttempt).get("error")).get("code"));

        var repairAttempt = api.handle("POST",
                "/api/v1/graphs/" + previewId.value() + "/reports/" + ContentDigest.sha256Utf8("rep").value() + "/repair-options",
                "application/json", bytes(Map.of("arguments", Map.of())), () -> false);
        assertEquals(409, repairAttempt.status());
        assertEquals("PRELIMINARY_GRAPH_PENDING", GraphJson.object(body(repairAttempt).get("error")).get("code"));

        // After superseding, preview graph ID returns 410 PREVIEW_SUPERSEDED with supersededBy digest
        previewStore.markJobFinished(jobId, Optional.of(durableId));
        var goneDisc = api.handle("GET", "/api/v1/graphs/" + previewId.value(), null, new byte[0], () -> false);
        assertEquals(410, goneDisc.status(), () -> body(goneDisc).toString());
        var goneErr = GraphJson.object(body(goneDisc).get("error"));
        assertEquals("PREVIEW_SUPERSEDED", goneErr.get("code"));
        assertEquals(durableId.value(), goneErr.get("supersededBy"));

        var goneScore = api.handle("POST", "/api/v1/graphs/" + previewId.value() + "/architecture-score",
                "application/json", bytes(Map.of()), () -> false);
        assertEquals(410, goneScore.status());
        assertEquals(durableId.value(), GraphJson.object(body(goneScore).get("error")).get("supersededBy"));
    }
}
