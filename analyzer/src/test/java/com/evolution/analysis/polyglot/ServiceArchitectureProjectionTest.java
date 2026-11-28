package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.graph.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServiceArchitectureProjectionTest {
    private final Map<String,Map<String,?>> tables=new HashMap<>();
    private final GraphProjectionStore store=new GraphProjectionStore(){@SuppressWarnings("unchecked") public <T> Map<String,T> records(String name,java.util.function.Function<Object,T> decode){return (Map<String,T>)tables.computeIfAbsent(name,ignored->new TreeMap<>());}};
    private final String root=id("root"),client=id("client"),server=id("server");
    private String id(String value){return ContentDigest.sha256Utf8(value).value();}
    private void node(String context,String identity,String kind,GraphEvidence proof){store.records("evidence",GraphSnapshotCodec::evidence).put(proof.identity().value(),proof);store.records("nodes",GraphSnapshotCodec::node).put(context+"|"+identity,new KnowledgeGraph.Node(identity,context,kind,List.of(proof.identity())));}
    private void edge(String context,String kind,String from,List<String> to,GraphEvidence proof){String key=id(context+kind+from+to);store.records("edges",GraphSnapshotCodec::edge).put(context+"|"+key,new KnowledgeGraph.Edge(key,context,kind,from,new KnowledgeGraph.Target(to.size()==1?KnowledgeGraph.TargetState.RESOLVED:KnowledgeGraph.TargetState.CANDIDATES,to.stream().sorted().toList(),Optional.empty()),List.of(proof.identity()),Optional.empty()));}
    @Test void httpEdgesReferToActualCallerAndHandlerWithoutFlatteningContextsAndReplayIsStable()throws Exception {
        var support=GraphEvidence.capture("fixture",List.of("opaque","non-object-proof"));
        node(client,id("caller"),"METHOD",support);node(server,id("handler"),"METHOD",support);
        node(client,id("call"),"HTTP_CLIENT_CALL",GraphEvidence.capture("java.http-client-call",Map.of("analysis",client,"call",Map.of("caller",id("caller")))));
        node(server,id("endpoint"),"ENDPOINT",GraphEvidence.capture("spring.endpoint-declaration",Map.of("route",Map.of("handler",id("handler")))));
        node(root,id("call-ref"),"HTTP_CALL_REFERENCE",GraphEvidence.capture("polyglot.http-call-reference",Map.of("context",client,"call",id("call"))));
        node(root,id("endpoint-ref"),"HTTP_ENDPOINT_REFERENCE",GraphEvidence.capture("polyglot.http-endpoint-reference",Map.of("context",server,"endpoint",id("endpoint"))));
        edge(root,"polyglot.service-call.candidate",id("call-ref"),List.of(id("endpoint-ref")),support);
        ServiceArchitectureProjection.project(store,root,()->false);
        var edges=store.records("edges",GraphSnapshotCodec::edge);var actual=edges.values().stream().filter(e->e.kind().equals("CALLS_SERVICE")).toList();assertEquals(1,actual.size());
        var call=actual.getFirst();assertEquals(root,call.context());assertEquals(KnowledgeGraph.TargetState.RESOLVED,call.target().state());
        assertTrue(call.evidence().stream().map(p->store.records("evidence",GraphSnapshotCodec::evidence).get(p.value())).anyMatch(p->p.family().equals("architecture.service-call")&&p.canonicalJson().contains("CALLER_DECLARATION")&&p.canonicalJson().contains("HANDLER_DECLARATION")&&p.canonicalJson().contains("\"runtimeObserved\":false")));
        var nodes=store.records("nodes",GraphSnapshotCodec::node);assertEquals("SERVICE_ENDPOINT_REFERENCE",nodes.get(root+"|"+call.source()).kind());assertTrue(nodes.containsKey(server+"|"+id("handler")));
        var beforeEdges=new TreeMap<>(edges);var beforeNodes=new TreeMap<>(nodes);ServiceArchitectureProjection.project(store,root,()->false);assertEquals(beforeEdges,edges);assertEquals(beforeNodes,nodes);
    }
    @Test void rpcDeclarationMatchingPreservesAmbiguityAndDoesNotCreateEdgesFromUnmatchedSites()throws Exception {
        var proof=GraphEvidence.capture("fixture",List.of("opaque"));
        for(String name:List.of("caller","site","h1","h2","unmatched"))node(root,id(name),"DECLARATION",proof);
        edge(root,"syntax.rpc-call-candidate",id("caller"),List.of(id("site")),proof);
        edge(root,"polyglot.grpc-service-call.candidate",id("site"),List.of(id("h1"),id("h2")),proof);
        ServiceArchitectureProjection.project(store,root,()->false);
        var actual=store.records("edges",GraphSnapshotCodec::edge).values().stream().filter(e->e.kind().equals("INVOKES_RPC")).toList();assertEquals(1,actual.size());assertEquals(id("caller"),actual.getFirst().source());assertEquals(KnowledgeGraph.TargetState.CANDIDATES,actual.getFirst().target().state());assertEquals(2,actual.getFirst().target().identities().size());
        assertThrows(java.io.IOException.class,()->ServiceArchitectureProjection.project(store,root,()->true));
    }
    @Test void projectionsQueryTargetedKindsWithoutFullTableScan()throws Exception {
        var guarded=new GraphProjectionStore() {
            @SuppressWarnings("unchecked")
            public <T> Map<String,T> records(String space,java.util.function.Function<Object,T> decode) {
                var backing=(Map<String,T>)tables.computeIfAbsent(space,ignored->new TreeMap<>());
                if(!Set.of("nodes","edges").contains(space))return backing;
                return new AbstractMap<>() {
                    @Override public T get(Object key){return backing.get(key);}
                    @Override public boolean containsKey(Object key){return backing.containsKey(key);}
                    @Override public T put(String key,T value){return backing.put(key,value);}
                    @Override public T putIfAbsent(String key,T value){return backing.putIfAbsent(key,value);}
                    @Override public void putAll(Map<? extends String,? extends T> m){backing.putAll(m);}
                    @Override public Set<Entry<String,T>> entrySet(){throw new AssertionError("Full "+space+" scan is forbidden; use valuesByKind");}
                };
            }
            @SuppressWarnings("unchecked")
            public <T> Iterable<T> valuesByKind(String space,Set<String> kinds,java.util.function.Function<Object,T> decode,java.util.function.Function<T,String> kindExtractor) {
                var backing=(Map<String,T>)tables.computeIfAbsent(space,ignored->new TreeMap<>());
                return ()->backing.values().stream().filter(v->kinds.contains(kindExtractor.apply(v))).iterator();
            }
        };
        String snapRoot="snapshot:"+root;
        var support=GraphEvidence.capture("fixture",List.of("opaque"));
        node(client,id("http-caller"),"METHOD",support);node(server,id("http-handler"),"METHOD",support);
        node(client,id("http-call"),"SOURCE_HTTP_CALL_CANDIDATE",GraphEvidence.capture("java.http-client-call",Map.of("analysis",client,"call",Map.of("caller",id("http-caller"),"uri","/api/items","methods",List.of("GET")))));
        node(server,id("http-ep"),"ENDPOINT",GraphEvidence.capture("spring.endpoint-declaration",Map.of("route",Map.of("handler",id("http-handler"),"path","/api/items","methods",List.of("GET"),"implicitHead",false))));
        edge(client,"graph.has-http-client-call",id("http-caller"),List.of(id("http-call")),support);
        node(snapRoot,id("rpc-method"),"PROTOBUF_RPC_METHOD",GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of("fact",Map.of("recovered",false,"attributes",Map.of("qualifiedName","demo.OrderService.Place"))))));
        node(server,id("rpc-handler-site"),"SOURCE_RPC_HANDLER_CANDIDATE",GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of("fact",Map.of("recovered",false,"attributes",Map.of("service","demo.OrderService","method","Place","methodStyle","EXACT","serviceQualification","QUALIFIED"))))));
        node(server,id("rpc-handler-decl"),"DECLARATION",support);
        edge(server,"syntax.rpc-handler-declaration",id("rpc-handler-site"),List.of(id("rpc-handler-decl")),support);
        node(client,id("rpc-call-site"),"SOURCE_RPC_CALL_CANDIDATE",GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of("fact",Map.of("recovered",false,"attributes",Map.of("service","demo.OrderService","method","Place","methodStyle","EXACT","serviceQualification","QUALIFIED"))))));
        node(client,id("rpc-caller-decl"),"DECLARATION",support);
        edge(client,"syntax.rpc-call-candidate",id("rpc-caller-decl"),List.of(id("rpc-call-site")),support);

        HttpServiceCallProjection.project(guarded,snapRoot,()->false);
        GrpcServiceCallProjection.project(guarded,snapRoot,()->false);
        ServiceArchitectureProjection.project(guarded,snapRoot,()->false);

        @SuppressWarnings("unchecked")
        var allEdges=((Map<String,KnowledgeGraph.Edge>)tables.get("edges")).values();
        assertEquals(1,allEdges.stream().filter(e->e.kind().equals("CALLS_SERVICE")).count());
        assertEquals(1,allEdges.stream().filter(e->e.kind().equals("INVOKES_RPC")).count());
    }
    @Test void declarativeTopologyDfdExportAndEventBrokerEdgesPreserveStrictBoundaries()throws Exception {
        var support=GraphEvidence.capture("fixture",List.of("opaque"));
        var gwMod=GraphEvidence.capture("polyglot.build-component",Map.of("id","mod-gw","directory","api-gateway"));
        var ordMod=GraphEvidence.capture("polyglot.build-component",Map.of("id","mod-orders","directory","order-service"));
        node(root,id("gw-comp"),"BUILD_COMPONENT",gwMod);
        node(root,id("ord-comp"),"BUILD_COMPONENT",ordMod);
        node(root,id("zuul-role"),"SOURCE_SPRING_CLOUD_ROLE",GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of(
                "path",Base64.getEncoder().encodeToString("api-gateway/src/Gw.java".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "fact",Map.of("name","ZUUL_PROXY","attributes",Map.of("role","ZUUL_PROXY"))))));
        node(root,id("eureka-role"),"SOURCE_SPRING_CLOUD_ROLE",GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of(
                "path",Base64.getEncoder().encodeToString("order-service/src/App.java".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "fact",Map.of("name","EUREKA_CLIENT","attributes",Map.of("role","EUREKA_CLIENT"))))));
        node(root,id("prom-scrape"),"SOURCE_INFRA_SCRAPE_TARGET",GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of(
                "path",Base64.getEncoder().encodeToString("monitoring/prometheus.yml".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "fact",Map.of("name","order-service","attributes",Map.of("target","order-service","job","orders","spec","prometheus"))))));
        node(root,id("rabbit-cfg"),"SOURCE_INFRA_CONFIG_REF",GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of(
                "path",Base64.getEncoder().encodeToString("order-service/src/main/resources/application.yml".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "fact",Map.of("name","rabbitmq","attributes",Map.of("role","rabbitmq","target","rabbitmq","spec","spring-config"))))));
        node(root,id("prod"),"SOURCE_EVENT_PRODUCER_CANDIDATE",GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of(
                "fact",Map.of("name","\"orders.created\"","recovered",false,"attributes",Map.of("protocol","RABBITMQ"))))));
        node(root,id("cons"),"SOURCE_EVENT_CONSUMER_CANDIDATE",GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of(
                "fact",Map.of("name","\"orders.created\"","recovered",false,"attributes",Map.of("protocol","RABBITMQ"))))));

        // STRICT mode: Prometheus + RabbitMQ INFRA_COMPONENT promoted, 0 EXTERNAL_ACTOR, 0 DERIVED_DFD_POLICY
        ServiceArchitectureProjection.project(store,root,ServiceArchitectureProjection.ProjectionProfile.STRICT,()->false);
        EventFlowProjection.project(store,root,()->false);
        var strictNodes=store.records("nodes",GraphSnapshotCodec::node).values();
        var strictEdges=store.records("edges",GraphSnapshotCodec::edge).values();
        assertTrue(strictNodes.stream().noneMatch(n->n.kind().equals("EXTERNAL_ACTOR")));
        assertTrue(strictNodes.stream().anyMatch(n->n.kind().equals("INFRA_COMPONENT")));
        assertTrue(strictEdges.stream().anyMatch(e->e.kind().equals("EVENT_FLOW")));
        assertTrue(strictEdges.stream().anyMatch(e->e.kind().equals("PUBLISHES_TO")));
        assertTrue(strictEdges.stream().anyMatch(e->e.kind().equals("CONSUMES_FROM")));

        // DFD_EXPORT mode: adds EXTERNAL_ACTOR("user") and Zuul auto-proxy edges marked DERIVED_DFD_POLICY
        ServiceArchitectureProjection.project(store,root,ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT,()->false);
        var dfdNodes=store.records("nodes",GraphSnapshotCodec::node).values();
        var dfdEvidence=store.records("evidence",GraphSnapshotCodec::evidence).values();
        assertTrue(dfdNodes.stream().anyMatch(n->n.kind().equals("EXTERNAL_ACTOR")));
        assertTrue(dfdEvidence.stream().anyMatch(e->e.canonicalJson().contains("DERIVED_DFD_POLICY")));
        assertTrue(dfdEvidence.stream().anyMatch(e->e.canonicalJson().contains("ZUUL_EUREKA_AUTO_PROXY")));
    }

    private static GraphProjectionStore newMemoryStore() {
        return GraphProjectionStore.memory();
    }

    private static void putNode(GraphProjectionStore s, String ctx, String id, String kind, GraphEvidence proof) {
        s.records("evidence", GraphSnapshotCodec::evidence).put(proof.identity().value(), proof);
        s.records("nodes", GraphSnapshotCodec::node).put(ctx + "|" + id,
                new KnowledgeGraph.Node(id, ctx, kind, List.of(proof.identity())));
    }

    private static String b64(String path) {
        return Base64.getEncoder().encodeToString(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Set<String> declarativePairs(GraphProjectionStore s) {
        var evs = s.records("evidence", GraphSnapshotCodec::evidence);
        var out = new TreeSet<String>();
        for (var edge : s.records("edges", GraphSnapshotCodec::edge).values()) {
            if (!"CALLS_SERVICE".equals(edge.kind())) continue;
            for (var ref : edge.evidence()) {
                var ev = evs.get(ref.value());
                if (ev != null && "architecture.declarative-topology".equals(ev.family())) {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                    out.add(GraphJson.string(obj.get("source")) + "->" + GraphJson.string(obj.get("target")));
                }
            }
        }
        return out;
    }

    private static boolean hasDeclarativeEdgeWithTrust(GraphProjectionStore s, String src, String dst, String trust) {
        var evs = s.records("evidence", GraphSnapshotCodec::evidence);
        for (var edge : s.records("edges", GraphSnapshotCodec::edge).values()) {
            if (!"CALLS_SERVICE".equals(edge.kind())) continue;
            for (var ref : edge.evidence()) {
                var ev = evs.get(ref.value());
                if (ev != null && "architecture.declarative-topology".equals(ev.family())) {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                    if (src.equals(obj.get("source")) && dst.equals(obj.get("target")) && trust.equals(obj.get("trust"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Test void strictLongestPrefixServiceIdentityComposeAliasingConfigRepoAndTestGuard() throws Exception {
        var s = newMemoryStore();
        putNode(s, root, id("agg-platform"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "platform", "directory", "platform")));
        putNode(s, root, id("cat-comp"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "catalog-dir", "directory", "platform/core/catalog-dir")));
        putNode(s, root, id("ord-comp"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "order-dir", "directory", "platform/core/order-dir")));
        putNode(s, root, id("cfg-comp"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "config-dir", "directory", "platform/infra/config-dir")));
        putNode(s, root, id("euk-comp"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "eureka-dir", "directory", "platform/infra/eureka-dir")));

        // Canonical spring.application.name for catalog-dir -> catalog-svc
        putNode(s, root, id("cat-appname"), "SOURCE_CONFIG_SERVICE_IDENTITY",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("platform/core/catalog-dir/src/main/resources/application.yml"),
                        "fact", Map.of("nodeKind", "spring_application_name", "name", "catalog-svc",
                                "attributes", Map.of("serviceName", "catalog-svc", "spec", "spring-config"))))));
        // server.port=8085 on order-dir
        putNode(s, root, id("ord-port"), "SOURCE_INFRA_PORT",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("platform/core/order-dir/src/main/resources/application.yml"),
                        "fact", Map.of("nodeKind", "spring_server_port", "name", "8085",
                                "attributes", Map.of("port", "8085", "spec", "spring-config"))))));
        // Roles
        putNode(s, root, id("cfg-role"), "SOURCE_SPRING_CLOUD_ROLE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("platform/infra/config-dir/src/main/java/ConfigApp.java"),
                        "fact", Map.of("name", "CONFIG_SERVER", "attributes", Map.of("role", "CONFIG_SERVER"))))));
        putNode(s, root, id("euk-role"), "SOURCE_SPRING_CLOUD_ROLE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("platform/infra/eureka-dir/src/main/java/EurekaApp.java"),
                        "fact", Map.of("name", "EUREKA_SERVER", "attributes", Map.of("role", "EUREKA_SERVER"))))));
        // Config Server git repo ref -> github-repository
        putNode(s, root, id("cfg-git"), "SOURCE_INFRA_CONFIG_REF",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("platform/infra/config-dir/src/main/resources/application.yml"),
                        "fact", Map.of("name", "github-repository",
                                "attributes", Map.of("role", "config-git-repo", "target", "github-repository"))))));
        // Config client refs so shared/application.yml applies to catalog-svc & order-dir
        putNode(s, root, id("cat-cfgclient"), "SOURCE_INFRA_CONFIG_REF",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("platform/core/catalog-dir/src/main/resources/bootstrap.yml"),
                        "fact", Map.of("name", "config-dir",
                                "attributes", Map.of("role", "config-client", "target", "localhost", "port", "8888"))))));
        // Config Server shared/catalog-svc.yml -> mongodb (must attribute to catalog-svc, NOT config-dir!)
        putNode(s, root, id("shared-cat-mongo"), "SOURCE_INFRA_CONFIG_REF",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("platform/infra/config-dir/src/main/resources/shared/catalog-svc.yml"),
                        "fact", Map.of("name", "catalog-mongodb",
                                "attributes", Map.of("role", "mongodb", "target", "catalog-mongodb", "configRepoFile", "catalog-svc"))))));
        // Config Server shared/application.yml -> eureka-client
        putNode(s, root, id("shared-app-eureka"), "SOURCE_INFRA_CONFIG_REF",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("platform/infra/config-dir/src/main/resources/shared/application.yml"),
                        "fact", Map.of("name", "eureka-dir",
                                "attributes", Map.of("role", "eureka-client", "target", "localhost", "port", "8761", "configRepoFile", "application"))))));
        // Docker Compose alias: "cat" builds platform/core/catalog-dir (must NOT spawn INFRA_COMPONENT "cat")
        putNode(s, root, id("compose-cat"), "SOURCE_DECLARATION",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "document", "compose-doc",
                        "path", b64("docker-compose.yml"),
                        "fact", Map.of("nodeKind", "compose_service", "name", "cat",
                                "span", Map.of("start", 10, "end", 20),
                                "attributes", Map.of("buildDir", "platform/core/catalog-dir", "containerName", "catalog-container"))))));
        putNode(s, root, id("compose-ord"), "SOURCE_DECLARATION",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "document", "compose-doc",
                        "path", b64("docker-compose.yml"),
                        "fact", Map.of("nodeKind", "compose_service", "name", "orders",
                                "span", Map.of("start", 30, "end", 40),
                                "attributes", Map.of("buildDir", "platform/core/order-dir"))))));
        putNode(s, root, id("compose-dep"), "SOURCE_INFRA_DEPENDENCY",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "document", "compose-doc",
                        "path", b64("docker-compose.yml"),
                        "fact", Map.of("nodeKind", "compose_depends_on", "name", "orders", "owner", "10:20",
                                "attributes", Map.of("target", "orders"))))));
        // @Value propertyKey + CONFIG_PROPERTY resolving to localhost:8085 (order-dir)
        putNode(s, root, id("val-prop"), "SOURCE_CONFIG_PROPERTY",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("platform/core/catalog-dir/src/main/resources/application.yml"),
                        "fact", Map.of("nodeKind", "config_property", "name", "app.order.url",
                                "attributes", Map.of("key", "app.order.url", "value", "http://localhost:8085/api",
                                        "target", "localhost", "port", "8085"))))));
        putNode(s, root, id("val-call"), "SOURCE_HTTP_CALL_CANDIDATE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("platform/core/catalog-dir/src/main/java/OrderClient.java"),
                        "fact", Map.of("nodeKind", "method_call", "name", "\"\"",
                                "attributes", Map.of("method", "GET", "propertyKey", "app.order.url"))))));
        // Test source HTTP call -> must be ignored
        putNode(s, root, id("test-call"), "SOURCE_HTTP_CALL_CANDIDATE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("platform/core/order-dir/src/test/java/FakeTest.java"),
                        "fact", Map.of("nodeKind", "method_call", "name", "\"http://catalog-svc/items\"",
                                "attributes", Map.of("method", "GET", "targetService", "catalog-svc", "testSource", "true"))))));

        DeclarativeTopologyProjection.project(s, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);

        var nodes = s.records("nodes", GraphSnapshotCodec::node);
        var evs = s.records("evidence", GraphSnapshotCodec::evidence);
        // BUILD_COMPONENT for catalog-dir has architecture.service-identity attached
        var catNode = nodes.get(root + "|" + id("cat-comp"));
        assertTrue(catNode.evidence().stream().map(d -> evs.get(d.value()))
                .anyMatch(e -> e != null && "architecture.service-identity".equals(e.family())
                        && e.canonicalJson().contains("catalog-svc")));
        // Compose aliases "cat" and "orders" must NOT spawn duplicate INFRA_COMPONENTs
        var infraNames = nodes.values().stream()
                .filter(n -> "INFRA_COMPONENT".equals(n.kind()))
                .flatMap(n -> n.evidence().stream().map(d -> evs.get(d.value())))
                .filter(e -> e != null && "architecture.infra-component".equals(e.family()))
                .map(e -> GraphJson.string(GraphJson.object(GraphJson.read(e.canonicalJson(), GraphJson.Limits.defaults())).get("name")))
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(infraNames.contains("cat"));
        assertFalse(infraNames.contains("orders"));
        assertTrue(infraNames.contains("github-repository"));
        assertTrue(infraNames.contains("catalog-mongodb"));

        var pairs = declarativePairs(s);
        assertTrue(pairs.contains("config-dir->github-repository"));
        assertTrue(pairs.contains("catalog-svc->config-dir"));
        assertTrue(pairs.contains("catalog-svc->catalog-mongodb"));
        assertFalse(pairs.contains("config-dir->catalog-mongodb"), "shared/<svc>.yml must not be attributed to config-server");
        assertTrue(pairs.contains("catalog-svc->eureka-dir"), "shared/application.yml must apply to config clients");
        assertFalse(pairs.contains("config-dir->eureka-dir"));
        assertTrue(pairs.contains("catalog-svc->order-dir"));
        assertFalse(pairs.contains("order-dir->catalog-svc"), "testSource=true call must be ignored");
        assertTrue(nodes.values().stream().noneMatch(n -> "EXTERNAL_ACTOR".equals(n.kind())));
        assertTrue(evs.values().stream().noneMatch(e -> e.canonicalJson().contains("DERIVED_DFD_POLICY")));
    }

    private void populateOrientationAndIngressFixture(GraphProjectionStore s) {
        putNode(s, root, id("gw"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "gateway", "directory", "gateway")));
        putNode(s, root, id("cfg"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "config-server", "directory", "config-server")));
        putNode(s, root, id("euk"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "eureka-server", "directory", "eureka-server")));
        putNode(s, root, id("adm"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "admin-server", "directory", "admin-server")));
        putNode(s, root, id("auth"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "auth-server", "directory", "auth-server")));
        putNode(s, root, id("hyst"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "hystrix-dashboard", "directory", "hystrix-dashboard")));
        putNode(s, root, id("turb"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "turbine", "directory", "turbine")));
        putNode(s, root, id("ord"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "order-service", "directory", "order-service")));
        putNode(s, root, id("fetchonly"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "edge-lookup", "directory", "edge-lookup")));

        putNode(s, root, id("r-gw"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("gateway/src/Gw.java"), "fact", Map.of("name", "ZUUL_PROXY", "attributes", Map.of("role", "ZUUL_PROXY"))))));
        putNode(s, root, id("r-cfg"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("config-server/src/C.java"), "fact", Map.of("name", "CONFIG_SERVER", "attributes", Map.of("role", "CONFIG_SERVER"))))));
        putNode(s, root, id("r-euk"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("eureka-server/src/E.java"), "fact", Map.of("name", "EUREKA_SERVER", "attributes", Map.of("role", "EUREKA_SERVER"))))));
        putNode(s, root, id("r-adm"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("admin-server/src/A.java"), "fact", Map.of("name", "ADMIN_SERVER", "attributes", Map.of("role", "ADMIN_SERVER"))))));
        putNode(s, root, id("r-auth"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("auth-server/src/Auth.java"), "fact", Map.of("name", "OAUTH2_AUTH_SERVER", "attributes", Map.of("role", "OAUTH2_AUTH_SERVER"))))));
        putNode(s, root, id("r-hyst"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("hystrix-dashboard/src/H.java"), "fact", Map.of("name", "HYSTRIX_DASHBOARD", "attributes", Map.of("role", "HYSTRIX_DASHBOARD"))))));
        putNode(s, root, id("r-turb"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("turbine/src/T.java"), "fact", Map.of("name", "TURBINE", "attributes", Map.of("role", "TURBINE"))))));
        putNode(s, root, id("r-ord-e"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("order-service/src/O.java"), "fact", Map.of("name", "EUREKA_CLIENT", "attributes", Map.of("role", "EUREKA_CLIENT"))))));
        putNode(s, root, id("r-ord-o"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("order-service/src/O.java"), "fact", Map.of("name", "OAUTH2_RESOURCE", "attributes", Map.of("role", "OAUTH2_RESOURCE"))))));
        // Control plane also registers with Eureka (must be excluded from Zuul auto-proxy!)
        putNode(s, root, id("r-adm-e"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("admin-server/src/A.java"), "fact", Map.of("name", "DISCOVERY_CLIENT", "attributes", Map.of("role", "DISCOVERY_CLIENT"))))));
        putNode(s, root, id("r-cfg-e"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("config-server/src/C.java"), "fact", Map.of("name", "DISCOVERY_CLIENT", "attributes", Map.of("role", "DISCOVERY_CLIENT"))))));

        // External ports on gateway, order-service, and mysql (DFD must connect user ONLY to gateway!)
        putNode(s, root, id("p-gw"), "SOURCE_INFRA_PORT", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("gateway/Dockerfile"), "fact", Map.of("nodeKind", "compose_port", "name", "8080", "attributes", Map.of("port", "8080", "externalPort", "true"))))));
        putNode(s, root, id("p-ord"), "SOURCE_INFRA_PORT", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("order-service/Dockerfile"), "fact", Map.of("nodeKind", "compose_port", "name", "8081", "attributes", Map.of("port", "8081", "externalPort", "true"))))));

        // Config / monitoring / datastore / discovery / auth / ELK / kafka-zk refs
        putNode(s, root, id("c-git"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("config-server/src/main/resources/application.yml"), "fact", Map.of("name", "github-repository", "attributes", Map.of("role", "config-git-repo", "target", "github-repository"))))));
        putNode(s, root, id("c-cfg"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("order-service/src/main/resources/bootstrap.yml"), "fact", Map.of("name", "config-server", "attributes", Map.of("role", "config-client", "target", "config-server"))))));
        putNode(s, root, id("c-gw-euk"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("gateway/src/main/resources/application.yml"), "fact", Map.of("name", "eureka-server", "attributes", Map.of("role", "eureka-client", "target", "eureka-server"))))));
        putNode(s, root, id("c-adm-euk"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("admin-server/src/main/resources/application.yml"), "fact", Map.of("name", "eureka-server", "attributes", Map.of("role", "eureka-client", "target", "eureka-server"))))));
        putNode(s, root, id("c-ord-euk"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("order-service/src/main/resources/application.yml"), "fact", Map.of("name", "eureka-server", "attributes", Map.of("role", "eureka-client", "target", "eureka-server"))))));
        putNode(s, root, id("c-fo-euk"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("edge-lookup/src/main/resources/application.yml"), "fact", Map.of("name", "eureka-server", "attributes", Map.of("role", "eureka-client", "target", "eureka-server"))))));
        putNode(s, root, id("p-fo-noreg"), "SOURCE_CONFIG_PROPERTY", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("edge-lookup/src/main/resources/application.yml"), "fact", Map.of("nodeKind", "config_property", "name", "eureka.client.register-with-eureka", "attributes", Map.of("key", "eureka.client.register-with-eureka", "value", "false"))))));
        putNode(s, root, id("c-ord-db"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("order-service/src/main/resources/application.yml"), "fact", Map.of("name", "mysql", "attributes", Map.of("role", "datasource", "target", "mysql"))))));
        putNode(s, root, id("c-ord-adm"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("order-service/src/main/resources/application.yml"), "fact", Map.of("name", "admin-server", "attributes", Map.of("role", "admin-client", "target", "admin-server"))))));
        putNode(s, root, id("c-ord-auth"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("order-service/src/main/resources/application.yml"), "fact", Map.of("name", "auth-server", "attributes", Map.of("role", "oauth2-auth", "target", "auth-server"))))));
        putNode(s, root, id("c-turb"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("turbine/src/main/resources/application.yml"), "fact", Map.of("name", "order-service", "attributes", Map.of("role", "turbine-monitor", "target", "order-service"))))));
        putNode(s, root, id("c-prom"), "SOURCE_INFRA_SCRAPE_TARGET", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("monitoring/prometheus.yml"), "fact", Map.of("name", "order-service", "attributes", Map.of("target", "order-service"))))));
        putNode(s, root, id("c-graf"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("monitoring/grafana/datasource.yml"), "fact", Map.of("name", "prometheus", "attributes", Map.of("role", "grafana-datasource", "target", "prometheus"))))));

        // Compose ELK & Kafka-Zookeeper dependencies
        putNode(s, root, id("dc-es"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_service", "name", "elasticsearch", "span", Map.of("start", 1, "end", 2), "attributes", Map.of())))));
        putNode(s, root, id("dc-kib"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_service", "name", "kibana", "span", Map.of("start", 3, "end", 4), "attributes", Map.of())))));
        putNode(s, root, id("dc-kib-es"), "SOURCE_INFRA_DEPENDENCY", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_depends_on", "name", "elasticsearch", "owner", "3:4", "attributes", Map.of("target", "elasticsearch"))))));
        putNode(s, root, id("dc-zk"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_service", "name", "zookeeper", "span", Map.of("start", 5, "end", 6), "attributes", Map.of())))));
        putNode(s, root, id("dc-kaf"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_service", "name", "kafka", "span", Map.of("start", 7, "end", 8), "attributes", Map.of())))));
        putNode(s, root, id("dc-kaf-zk"), "SOURCE_INFRA_DEPENDENCY", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_depends_on", "name", "zookeeper", "owner", "7:8", "attributes", Map.of("target", "zookeeper"))))));
    }

    @Test void dfdExportTightensUserActorZuulAutoProxyAndAppliesRoleBasedDataFlowOrientation() throws Exception {
        var strictStore = newMemoryStore();
        populateOrientationAndIngressFixture(strictStore);
        DeclarativeTopologyProjection.project(strictStore, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);

        var strictPairs = declarativePairs(strictStore);
        // STRICT preserves caller -> callee dependency orientation with 0 EXTERNAL_ACTOR and 0 DERIVED_DFD_POLICY
        assertTrue(strictPairs.contains("order-service->config-server"));
        assertTrue(strictPairs.contains("config-server->github-repository"));
        assertTrue(strictPairs.contains("gateway->eureka-server"));
        assertTrue(strictPairs.contains("admin-server->eureka-server"));
        assertTrue(strictPairs.contains("order-service->eureka-server"));
        assertTrue(strictPairs.contains("order-service->mysql"));
        assertTrue(strictPairs.contains("prometheus->order-service"));
        assertTrue(strictPairs.contains("grafana->prometheus"));
        assertTrue(strictPairs.contains("kibana->elasticsearch"));
        assertTrue(strictPairs.contains("kafka->zookeeper"));
        assertTrue(strictPairs.contains("turbine->order-service"));
        assertTrue(strictPairs.contains("order-service->admin-server"));
        assertTrue(strictPairs.contains("order-service->auth-server"));
        assertTrue(strictStore.records("nodes", GraphSnapshotCodec::node).values().stream().noneMatch(n -> "EXTERNAL_ACTOR".equals(n.kind())));
        assertTrue(strictStore.records("evidence", GraphSnapshotCodec::evidence).values().stream().noneMatch(e -> e.canonicalJson().contains("DERIVED_DFD_POLICY")));

        var dfdStore = newMemoryStore();
        populateOrientationAndIngressFixture(dfdStore);
        DeclarativeTopologyProjection.project(dfdStore, root, ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT, () -> false);

        var dfdPairs = declarativePairs(dfdStore);
        // 1. DFD data-flow orientation (tagged DERIVED_DFD_POLICY)
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "config-server", "order-service", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "github-repository", "config-server", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "eureka-server", "gateway", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "eureka-server", "edge-lookup", "DERIVED_DFD_POLICY"),
                "fetch-only eureka client (register-with-eureka=false) reverses to eureka -> client");
        assertTrue(dfdPairs.contains("admin-server->eureka-server"),
                "admin-server registers with eureka unless register-with-eureka=false");
        assertTrue(dfdPairs.contains("order-service->eureka-server"), "business service registration remains service -> discovery");
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "mysql", "order-service", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "order-service", "prometheus", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "prometheus", "grafana", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "elasticsearch", "kibana", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "zookeeper", "kafka", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "order-service", "turbine", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "admin-server", "order-service", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "auth-server", "order-service", "DERIVED_DFD_POLICY"));
        // 2. Tightened user external actor: explicit ingress ONLY + bidirectional response
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "user", "gateway", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "gateway", "user", "DERIVED_DFD_POLICY"));
        assertFalse(dfdPairs.contains("user->order-service"), "user must not fan out to non-ingress externalPort services when gateway exists");
        assertFalse(dfdPairs.contains("user->mysql"));
        // 3. Tightened Zuul auto-proxy: business service included, control-plane & infra excluded
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "gateway", "order-service", "DERIVED_DFD_POLICY"));
        assertFalse(dfdPairs.contains("gateway->admin-server"));
        assertFalse(dfdPairs.contains("gateway->config-server"));
        assertFalse(dfdPairs.contains("gateway->hystrix-dashboard"));
        assertFalse(dfdPairs.contains("gateway->turbine"));
    }

    private void populateStarterMonitorProxyAndExternalHttpsFixture(GraphProjectionStore s) {
        putNode(s, root, id("euk"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "eureka-server", "directory", "eureka-server")));
        putNode(s, root, id("cfg"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "config-server", "directory", "config-server")));
        putNode(s, root, id("zip"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "zipkin-server", "directory", "zipkin-server")));
        putNode(s, root, id("adm"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "admin-server", "directory", "admin-server")));
        putNode(s, root, id("auth"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "auth-server", "directory", "auth-server")));
        putNode(s, root, id("bill"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "billing-service", "directory", "billing-service")));
        putNode(s, root, id("rep"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "report-service", "directory", "report-service")));

        putNode(s, root, id("re"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("eureka-server/src/App.java"), "fact", Map.of("name", "EUREKA_SERVER", "attributes", Map.of("role", "EUREKA_SERVER"))))));
        putNode(s, root, id("rc"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("config-server/src/App.java"), "fact", Map.of("name", "CONFIG_SERVER", "attributes", Map.of("role", "CONFIG_SERVER"))))));
        putNode(s, root, id("rz"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("zipkin-server/src/App.java"), "fact", Map.of("name", "ZIPKIN_SERVER", "attributes", Map.of("role", "ZIPKIN_SERVER"))))));
        putNode(s, root, id("ra"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("admin-server/src/App.java"), "fact", Map.of("name", "ADMIN_SERVER", "attributes", Map.of("role", "ADMIN_SERVER"))))));
        putNode(s, root, id("ro"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("auth-server/src/App.java"), "fact", Map.of("name", "OAUTH2_AUTH_SERVER", "attributes", Map.of("role", "OAUTH2_AUTH_SERVER"))))));

        // Starters declared in billing-service/pom.xml (explicit zipkin & stream-rabbit)
        for (String starter : List.of("eureka-client", "config-client", "zipkin", "stream-rabbit", "boot-admin-client",
                "data-jpa", "jdbc-postgres", "mail", "logstash-logback", "oauth2-resource")) {
            putNode(s, root, id("st-" + starter), "SOURCE_INFRA_DEPENDENCY_STARTER",
                    GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                            "path", b64("billing-service/pom.xml"),
                            "fact", Map.of("nodeKind", "maven_dependency", "name", starter,
                                    "attributes", Map.of("starter", starter))))));
        }
        // report-service has data-jpa + jdbc-mysql starter WITHOUT any declared mysql Compose container (must NOT conjure mysql!)
        for (String starter : List.of("data-jpa", "jdbc-mysql")) {
            putNode(s, root, id("st-rep-" + starter), "SOURCE_INFRA_DEPENDENCY_STARTER",
                    GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                            "path", b64("report-service/pom.xml"),
                            "fact", Map.of("nodeKind", "maven_dependency", "name", starter,
                                    "attributes", Map.of("starter", starter))))));
        }
        // Compose rabbitmq, declared postgres, docker-socket observability probe, non-observability watchtower, and Kafka with dockerSocket
        putNode(s, root, id("dc-rabbit"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "rabbitmq", "span", Map.of("start", 1, "end", 2), "attributes", Map.of())))));
        putNode(s, root, id("dc-pg"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "postgres", "span", Map.of("start", 2, "end", 3), "attributes", Map.of())))));
        putNode(s, root, id("dc-mon"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "container-probe", "span", Map.of("start", 3, "end", 4),
                        "attributes", Map.of("dockerSocket", "true"))))));
        putNode(s, root, id("dc-watchtower"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "watchtower", "span", Map.of("start", 4, "end", 5),
                        "attributes", Map.of("dockerSocket", "true"))))));
        putNode(s, root, id("dc-watchtower-img"), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_image", "name", "containrrr/watchtower:latest", "owner", "4:5",
                        "attributes", Map.of("image", "containrrr/watchtower:latest"))))));
        putNode(s, root, id("dc-kafka-sock"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "kafka", "span", Map.of("start", 5, "end", 6),
                        "attributes", Map.of("dockerSocket", "true"))))));
        // Apache reverse proxy route (matching Worker B's exact apache-httpd schema)
        putNode(s, root, id("apache-decl"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "httpd-doc", "path", b64("proxy/httpd.conf"),
                "fact", Map.of("nodeKind", "apache_proxy", "name", "apache", "span", Map.of("start", 1, "end", 10),
                        "attributes", Map.of("role", "reverse-proxy", "proxy", "apache", "spec", "apache-httpd"))))));
        putNode(s, root, id("apache-route"), "SOURCE_GATEWAY_ROUTE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "httpd-doc", "path", b64("proxy/httpd.conf"),
                "fact", Map.of("nodeKind", "apache_proxy_pass", "name", "billing-service",
                        "attributes", Map.of("targetService", "billing-service", "proxy", "apache", "spec", "apache-httpd"))))));
        // Embedded localhost datasource with dbVendor="h2" & Grafana localhost:9090 datasourceType="prometheus"
        putNode(s, root, id("h2-ds"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("admin-server/src/main/resources/application.yml"),
                "fact", Map.of("name", "localhost",
                        "attributes", Map.of("role", "datasource", "target", "localhost", "dbVendor", "h2"))))));
        putNode(s, root, id("graf-local"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("monitoring/grafana/datasources.yml"),
                "fact", Map.of("name", "localhost",
                        "attributes", Map.of("role", "grafana-datasource", "target", "localhost", "port", "9090", "datasourceType", "prometheus"))))));
        // Explicit external https:// API calls: GET (extHost -> service) and POST (service -> extHost)
        putNode(s, root, id("ext-http-get"), "SOURCE_HTTP_CALL_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("billing-service/src/main/java/RatesClient.java"),
                "fact", Map.of("nodeKind", "method_call", "name", "\"https://api.rates.org/v1/latest/{param}\"",
                        "attributes", Map.of("method", "GET"))))));
        putNode(s, root, id("ext-http-post"), "SOURCE_HTTP_CALL_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("report-service/src/main/java/WebhookClient.java"),
                "fact", Map.of("nodeKind", "method_call", "name", "\"https://hooks.partner.org/events\"",
                        "attributes", Map.of("method", "POST"))))));
    }

    @Test void dfdExportWiresStartersDockerSocketMonitorsApacheProxyAndExternalHttpsApi() throws Exception {
        var strictStore = newMemoryStore();
        populateStarterMonitorProxyAndExternalHttpsFixture(strictStore);
        DeclarativeTopologyProjection.project(strictStore, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);
        var strictPairs = declarativePairs(strictStore);
        assertTrue(strictPairs.contains("apache->billing-service"));
        assertTrue(strictPairs.contains("admin-server->h2"));
        assertTrue(strictPairs.contains("grafana->prometheus"));
        assertFalse(strictPairs.contains("billing-service->container-probe"));
        assertFalse(strictPairs.contains("api.rates.org->billing-service"));
        assertTrue(strictStore.records("nodes", GraphSnapshotCodec::node).values().stream().noneMatch(n -> "EXTERNAL_ACTOR".equals(n.kind())));

        var dfdStore = newMemoryStore();
        populateStarterMonitorProxyAndExternalHttpsFixture(dfdStore);
        DeclarativeTopologyProjection.project(dfdStore, root, ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT, () -> false);
        var dfdPairs = declarativePairs(dfdStore);

        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "billing-service", "eureka-server", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "config-server", "billing-service", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "billing-service", "zipkin-server", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "billing-service", "rabbitmq", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "admin-server", "billing-service", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "postgres", "billing-service", "DERIVED_DFD_POLICY"));
        assertFalse(dfdPairs.contains("mysql->report-service"), "undeclared mysql starter must NOT conjure synthetic mysql infra");
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "h2", "admin-server", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "prometheus", "grafana", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "billing-service", "smtp", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "billing-service", "logstash", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "auth-server", "billing-service", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "billing-service", "container-probe", "DERIVED_DFD_POLICY"));
        assertFalse(dfdPairs.contains("container-probe->billing-service"));
        assertFalse(dfdPairs.contains("billing-service->watchtower"), "non-observability container mounting dockerSocket must NOT receive telemetry edges");
        assertFalse(dfdPairs.contains("billing-service->kafka"), "broker with dockerSocket must NOT act as container monitor");
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "api.rates.org", "billing-service", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "report-service", "hooks.partner.org", "DERIVED_DFD_POLICY"));
        var evs = dfdStore.records("evidence", GraphSnapshotCodec::evidence);
        var extNodes = dfdStore.records("nodes", GraphSnapshotCodec::node).values().stream()
                .filter(n -> "EXTERNAL_ACTOR".equals(n.kind()) && n.evidence().stream()
                        .map(d -> evs.get(d.value()))
                        .anyMatch(e -> e != null && "architecture.external-actor".equals(e.family())
                                && e.canonicalJson().contains("\"category\":\"external-http-service\"")))
                .toList();
        assertEquals(2, extNodes.size(), "distinct external HTTPS hosts must preserve per-host identity");
        for (var extNode : extNodes) {
            assertTrue(extNode.evidence().stream().map(d -> evs.get(d.value()))
                    .allMatch(e -> e != null && "architecture.external-actor".equals(e.family())),
                    "EXTERNAL_ACTOR must carry only architecture.external-actor evidence so polyglot.source-syntax does not clobber name");
        }
        assertTrue(extNodes.stream().flatMap(n -> n.evidence().stream()).map(d -> evs.get(d.value()).canonicalJson())
                .anyMatch(j -> j.contains("\"name\":\"api.rates.org\"") && j.contains("\"host\":\"api.rates.org\"")));
        assertTrue(extNodes.stream().flatMap(n -> n.evidence().stream()).map(d -> evs.get(d.value()).canonicalJson())
                .anyMatch(j -> j.contains("\"name\":\"hooks.partner.org\"") && j.contains("\"host\":\"hooks.partner.org\"")));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "user", "apache", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(dfdStore, "apache", "user", "DERIVED_DFD_POLICY"));
    }

    @Test void eventFlowProjectionStrictVsDfdExportBrokerOrientationAndPeerSuppression() throws Exception {
        java.util.function.Consumer<GraphProjectionStore> seed = s -> {
            putNode(s, root, id("kafka-infra"), "INFRA_COMPONENT",
                    GraphEvidence.capture("architecture.infra-component", Map.of("name", "kafka", "image", "kafka")));
            putNode(s, root, id("prod-node"), "SOURCE_EVENT_PRODUCER_CANDIDATE",
                    GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                            "path", b64("order-service/src/main/java/Pub.java"),
                            "fact", Map.of("name", "\"orders.placed\"", "recovered", false,
                                    "attributes", Map.of("protocol", "KAFKA"))))));
            putNode(s, root, id("cons-node"), "SOURCE_EVENT_CONSUMER_CANDIDATE",
                    GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                            "path", b64("shipping-service/src/main/java/Sub.java"),
                            "fact", Map.of("name", "\"orders.placed\"", "recovered", false,
                                    "attributes", Map.of("protocol", "KAFKA"))))));
        };

        var strictStore = newMemoryStore();
        seed.accept(strictStore);
        DeclarativeTopologyProjection.project(strictStore, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);
        EventFlowProjection.project(strictStore, root, () -> false);
        var strictEdges = strictStore.records("edges", GraphSnapshotCodec::edge).values();
        var strictConsume = strictEdges.stream().filter(e -> "CONSUMES_FROM".equals(e.kind())).toList();
        assertEquals(1, strictConsume.size());
        assertEquals(id("cons-node"), strictConsume.getFirst().source());
        assertEquals(List.of(id("kafka-infra")), strictConsume.getFirst().target().identities());
        assertTrue(strictEdges.stream().anyMatch(e -> "PUBLISHES_EVENT".equals(e.kind())));

        var dfdStore = newMemoryStore();
        seed.accept(dfdStore);
        DeclarativeTopologyProjection.project(dfdStore, root, ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT, () -> false);
        EventFlowProjection.project(dfdStore, root, () -> false);
        var dfdEdges = dfdStore.records("edges", GraphSnapshotCodec::edge).values();
        var dfdConsume = dfdEdges.stream().filter(e -> "CONSUMES_FROM".equals(e.kind())).toList();
        assertEquals(1, dfdConsume.size());
        assertEquals(id("kafka-infra"), dfdConsume.getFirst().source(), "DFD_EXPORT must orient consumer flow as broker -> consumer");
        assertEquals(List.of(id("cons-node")), dfdConsume.getFirst().target().identities());
        assertTrue(dfdStore.records("evidence", GraphSnapshotCodec::evidence).values().stream()
                .anyMatch(e -> "polyglot.event-broker-link".equals(e.family()) && e.canonicalJson().contains("DERIVED_DFD_POLICY")));
        assertTrue(dfdEdges.stream().noneMatch(e -> "PUBLISHES_EVENT".equals(e.kind())),
                "DFD_EXPORT must suppress direct peer-to-peer PUBLISHES_EVENT shortcuts when a concrete broker mediates the topic");
        assertTrue(dfdEdges.stream().anyMatch(e -> "PUBLISHES_TO".equals(e.kind())));
        assertTrue(dfdEdges.stream().anyMatch(e -> "EVENT_FLOW".equals(e.kind())));
    }

    @Test void batch11MultiContextIdentityComposeSuffixFallbackImageTailGuardAndUnhyphenatedToken() throws Exception {
        var s = newMemoryStore();
        // Item 1: BUILD_COMPONENT under non-root context `client`
        putNode(s, client, id("ctx-comp"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "alpha-mod", "directory", "alpha-mod")));
        putNode(s, root, id("ctx-appname"), "SOURCE_CONFIG_SERVICE_IDENTITY",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("alpha-mod/src/main/resources/application.yml"),
                        "fact", Map.of("nodeKind", "spring_application_name", "name", "alpha-api",
                                "attributes", Map.of("serviceName", "alpha-api", "spec", "spring-config"))))));

        // Item 2: Leaf BUILD_COMPONENT demo-config-server (no spring.application.name) + Compose config-server
        putNode(s, client, id("cfg-comp"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "demo-config-server", "directory", "demo-config-server")));
        putNode(s, root, id("dc-cfg"), "SOURCE_DECLARATION",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "document", "dc", "path", b64("docker-compose.yml"),
                        "fact", Map.of("nodeKind", "compose_service", "name", "config-server",
                                "span", Map.of("start", 1, "end", 5), "attributes", Map.of())))));

        // Item 3: Two infra containers sharing the same image tail (`consul:1.12.2`) must NOT collapse
        putNode(s, root, id("dc-c1"), "SOURCE_DECLARATION",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "document", "dc", "path", b64("docker-compose.yml"),
                        "fact", Map.of("nodeKind", "compose_service", "name", "consul",
                                "span", Map.of("start", 10, "end", 15), "attributes", Map.of())))));
        putNode(s, root, id("dc-c1-img"), "SOURCE_INFRA_BASE_IMAGE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "document", "dc", "path", b64("docker-compose.yml"),
                        "fact", Map.of("nodeKind", "compose_image", "name", "consul:1.12.2", "owner", "10:15",
                                "attributes", Map.of("image", "consul:1.12.2"))))));
        putNode(s, root, id("dc-c3"), "SOURCE_DECLARATION",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "document", "dc", "path", b64("docker-compose.yml"),
                        "fact", Map.of("nodeKind", "compose_service", "name", "consul3",
                                "span", Map.of("start", 20, "end", 25), "attributes", Map.of())))));
        putNode(s, root, id("dc-c3-img"), "SOURCE_INFRA_BASE_IMAGE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "document", "dc", "path", b64("docker-compose.yml"),
                        "fact", Map.of("nodeKind", "compose_image", "name", "consul:1.12.2", "owner", "20:25",
                                "attributes", Map.of("image", "consul:1.12.2"))))));

        // Item 4: Unhyphenated `authserver` matches leaf BUILD_COMPONENT `auth-server`
        putNode(s, root, id("auth-comp"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "auth-server", "directory", "auth-server")));
        putNode(s, root, id("dc-auth"), "SOURCE_DECLARATION",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "document", "dc", "path", b64("docker-compose.yml"),
                        "fact", Map.of("nodeKind", "compose_service", "name", "authserver",
                                "span", Map.of("start", 30, "end", 35), "attributes", Map.of())))));

        DeclarativeTopologyProjection.project(s, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);

        var nodes = s.records("nodes", GraphSnapshotCodec::node);
        var evs = s.records("evidence", GraphSnapshotCodec::evidence);
        // Item 1: identity stored under client|ctx-comp, ZERO ghost node under root|ctx-comp
        assertNull(nodes.get(root + "|" + id("ctx-comp")), "must NOT write ghost BUILD_COMPONENT under root context");
        var alphaNode = nodes.get(client + "|" + id("ctx-comp"));
        assertTrue(alphaNode.evidence().stream().map(d -> evs.get(d.value()))
                .anyMatch(e -> e != null && "architecture.service-identity".equals(e.family())
                        && e.canonicalJson().contains("alpha-api")));

        // Item 2: Compose suffix fallback attaches architecture.service-identity to demo-config-server under client context
        assertNull(nodes.get(root + "|" + id("cfg-comp")), "must NOT write ghost BUILD_COMPONENT under root context");
        var cfgNode = nodes.get(client + "|" + id("cfg-comp"));
        assertTrue(cfgNode.evidence().stream().map(d -> evs.get(d.value()))
                .anyMatch(e -> e != null && "architecture.service-identity".equals(e.family())
                        && e.canonicalJson().contains("\"serviceName\":\"config-server\"")
                        && e.canonicalJson().contains("\"spec\":\"docker-compose\"")));

        // Item 3 & 4: consul & consul3 are BOTH preserved; config-server & authserver did NOT spawn duplicate INFRA_COMPONENTs
        var infraNames = nodes.values().stream()
                .filter(n -> "INFRA_COMPONENT".equals(n.kind()))
                .flatMap(n -> n.evidence().stream().map(d -> evs.get(d.value())))
                .filter(e -> e != null && "architecture.infra-component".equals(e.family()))
                .map(e -> GraphJson.string(GraphJson.object(GraphJson.read(e.canonicalJson(), GraphJson.Limits.defaults())).get("name")))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(infraNames.contains("consul"));
        assertTrue(infraNames.contains("consul3"), "multi-node consul3 must NOT collapse into consul by imageTail");
        assertFalse(infraNames.contains("config-server"), "suffix-matched Compose service must NOT spawn duplicate infra");
        assertFalse(infraNames.contains("authserver"), "unhyphenated authserver must unify onto auth-server");
    }

    @Test void batch11ComposeBuildDirFilebeatProxyReuseExistingInfraAndRoleBeforePortLoopback() throws Exception {
        var s = newMemoryStore();
        putNode(s, root, id("cfg"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "config-server", "directory", "config-server")));
        putNode(s, root, id("port-clash"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "other-service", "directory", "other-service")));
        putNode(s, root, id("bill"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "billing-service", "directory", "billing-service")));
        putNode(s, root, id("r-cfg"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("config-server/src/App.java"), "fact", Map.of("name", "CONFIG_SERVER", "attributes", Map.of("role", "CONFIG_SERVER"))))));

        // Item 6: other-service happens to expose port 8888, while billing-service has role="config-client" pointing to localhost:8888
        putNode(s, root, id("p-other"), "SOURCE_INFRA_PORT", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("other-service/src/main/resources/application.yml"),
                "fact", Map.of("nodeKind", "spring_server_port", "name", "8888", "attributes", Map.of("port", "8888", "spec", "spring-config"))))));
        putNode(s, root, id("c-cfg-loop"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("billing-service/src/main/resources/bootstrap.yml"),
                "fact", Map.of("name", "localhost", "attributes", Map.of("role", "config-client", "target", "localhost", "port", "8888"))))));

        // Item 5: Compose `proxy` with buildDir="proxy", proxy/Dockerfile FROM nginx:latest,
        // proxy/default.conf (nginx_proxy + nginx_proxy_pass), and proxy/filebeat.yml (role="logstash")
        putNode(s, root, id("ng-decl"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "ng-doc", "path", b64("proxy/default.conf"),
                "fact", Map.of("nodeKind", "nginx_proxy", "name", "nginx", "span", Map.of("start", 1, "end", 5),
                        "attributes", Map.of("role", "reverse-proxy", "proxy", "nginx", "spec", "nginx"))))));
        putNode(s, root, id("dc-proxy"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "proxy", "span", Map.of("start", 1, "end", 4),
                        "attributes", Map.of("buildDir", "proxy"))))));
        putNode(s, root, id("df-proxy"), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "df-doc", "path", b64("proxy/Dockerfile"),
                "fact", Map.of("nodeKind", "dockerfile_from", "name", "nginx:latest", "owner", "",
                        "attributes", Map.of("image", "nginx:latest"))))));
        putNode(s, root, id("ng-route"), "SOURCE_GATEWAY_ROUTE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "ng-doc", "path", b64("proxy/default.conf"),
                "fact", Map.of("nodeKind", "nginx_proxy_pass", "name", "billing-service",
                        "attributes", Map.of("targetService", "billing-service", "proxy", "nginx", "spec", "nginx"))))));
        putNode(s, root, id("fb-ref"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("proxy/filebeat.yml"),
                "fact", Map.of("nodeKind", "filebeat_output", "name", "logstash",
                        "attributes", Map.of("role", "logstash", "target", "logstash", "spec", "filebeat"))))));

        // Item 5: Pre-existing Compose infra with suffixed/image names must be reused (no duplicate prometheus/grafana/zipkin)
        putNode(s, root, id("dc-prom"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "prometheus-server", "span", Map.of("start", 10, "end", 12), "attributes", Map.of())))));
        putNode(s, root, id("dc-prom-img"), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_image", "name", "prom/prometheus", "owner", "10:12", "attributes", Map.of("image", "prom/prometheus"))))));
        putNode(s, root, id("dc-graf"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "grafana-server", "span", Map.of("start", 14, "end", 16), "attributes", Map.of())))));
        putNode(s, root, id("dc-graf-img"), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_image", "name", "grafana/grafana", "owner", "14:16", "attributes", Map.of("image", "grafana/grafana"))))));
        putNode(s, root, id("dc-zip"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "tracing-server", "span", Map.of("start", 18, "end", 20), "attributes", Map.of())))));
        putNode(s, root, id("dc-zip-img"), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_image", "name", "openzipkin/zipkin", "owner", "18:20", "attributes", Map.of("image", "openzipkin/zipkin"))))));
        putNode(s, root, id("scr"), "SOURCE_INFRA_SCRAPE_TARGET", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("monitoring/prometheus.yml"), "fact", Map.of("name", "billing-service", "attributes", Map.of("target", "billing-service"))))));
        putNode(s, root, id("g-ds"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("monitoring/grafana/ds.yml"), "fact", Map.of("name", "prometheus", "attributes", Map.of("role", "grafana-datasource", "target", "prometheus"))))));
        putNode(s, root, id("z-ref"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("billing-service/src/main/resources/application.yml"),
                "fact", Map.of("name", "localhost", "attributes", Map.of("role", "zipkin", "target", "localhost", "port", "9411"))))));

        DeclarativeTopologyProjection.project(s, root, ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT, () -> false);

        var pairs = declarativePairs(s);
        var evs = s.records("evidence", GraphSnapshotCodec::evidence);
        var infraNames = s.records("nodes", GraphSnapshotCodec::node).values().stream()
                .filter(n -> "INFRA_COMPONENT".equals(n.kind()))
                .flatMap(n -> n.evidence().stream().map(d -> evs.get(d.value())))
                .filter(e -> e != null && "architecture.infra-component".equals(e.family()))
                .map(e -> GraphJson.string(GraphJson.object(GraphJson.read(e.canonicalJson(), GraphJson.Limits.defaults())).get("name")))
                .collect(java.util.stream.Collectors.toSet());

        // Item 6: config-client resolved to config-server (not other-service on port 8888)
        assertTrue(pairs.contains("config-server->billing-service"));
        assertFalse(pairs.contains("billing-service->other-service"));
        assertFalse(pairs.contains("other-service->billing-service"));

        // Item 5: proxy reused across Dockerfile/nginx/filebeat without spawning duplicate nginx
        assertTrue(pairs.contains("proxy->billing-service"));
        assertTrue(pairs.contains("proxy->logstash"));
        assertTrue(pairs.contains("user->proxy"));
        assertTrue(pairs.contains("proxy->user"));
        assertFalse(infraNames.contains("nginx"), "nginx_proxy under proxy/ must reuse Compose proxy");

        // Item 5: existing prometheus-server, grafana-server, tracing-server reused without duplicates
        assertTrue(pairs.contains("billing-service->prometheus-server"));
        assertTrue(pairs.contains("prometheus-server->grafana-server"));
        assertTrue(pairs.contains("billing-service->tracing-server"));
        assertFalse(infraNames.contains("prometheus"));
        assertFalse(infraNames.contains("grafana"));
        assertFalse(infraNames.contains("zipkin"));
    }

    @Test void batch11MultiProfileDatastorePrecedenceSharedConfigEurekaGuardAndDirectJavaEureka() throws Exception {
        var s = newMemoryStore();
        putNode(s, root, id("cfg"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "config-server", "directory", "config-server")));
        putNode(s, root, id("euk"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "eureka-server", "directory", "eureka-server")));
        putNode(s, root, id("ord"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "orders-service", "directory", "orders-service")));
        putNode(s, root, id("nodisc"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "worker-service", "directory", "worker-service")));
        putNode(s, root, id("javaonly"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "direct-service", "directory", "direct-service")));

        putNode(s, root, id("r-cfg"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("config-server/src/C.java"), "fact", Map.of("name", "CONFIG_SERVER", "attributes", Map.of("role", "CONFIG_SERVER"))))));
        putNode(s, root, id("r-euk"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("eureka-server/src/E.java"), "fact", Map.of("name", "EUREKA_SERVER", "attributes", Map.of("role", "EUREKA_SERVER"))))));
        // Item 10: direct-service has ONLY @EnableEurekaClient in Java (no YAML eureka ref)
        putNode(s, root, id("r-dir-euk"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("direct-service/src/App.java"), "fact", Map.of("name", "EUREKA_CLIENT", "attributes", Map.of("role", "EUREKA_CLIENT"))))));

        // Item 9: both orders-service & worker-service are config clients; only orders-service has eureka-client starter
        putNode(s, root, id("st-ord-cfg"), "SOURCE_INFRA_DEPENDENCY_STARTER", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("orders-service/pom.xml"), "fact", Map.of("name", "config-client", "attributes", Map.of("starter", "config-client"))))));
        putNode(s, root, id("st-ord-euk"), "SOURCE_INFRA_DEPENDENCY_STARTER", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("orders-service/pom.xml"), "fact", Map.of("name", "eureka-client", "attributes", Map.of("starter", "eureka-client"))))));
        putNode(s, root, id("st-wrk-cfg"), "SOURCE_INFRA_DEPENDENCY_STARTER", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("worker-service/pom.xml"), "fact", Map.of("name", "config-client", "attributes", Map.of("starter", "config-client"))))));
        putNode(s, root, id("shared-euk"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("config-server/src/main/resources/shared/application.yml"),
                "fact", Map.of("name", "eureka-server", "attributes", Map.of("role", "eureka-client", "target", "eureka-server", "configRepoFile", "application"))))));

        // Item 7: Compose orders-db (image: mongo:4.2.21) + explicit networked mongodb ref AND fallback localhost mongodb & h2 refs
        putNode(s, root, id("dc-odb"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "orders-db", "span", Map.of("start", 1, "end", 4), "attributes", Map.of())))));
        putNode(s, root, id("dc-odb-img"), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_image", "name", "mongo:4.2.21", "owner", "1:4", "attributes", Map.of("image", "mongo:4.2.21"))))));
        // Put fallback refs BEFORE explicit ref in node order to prove two-pass precedence!
        putNode(s, root, id("a-fallback-mongo"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("orders-service/src/main/resources/application.yml"),
                "fact", Map.of("name", "localhost", "attributes", Map.of("role", "mongodb", "target", "localhost"))))));
        putNode(s, root, id("a-fallback-h2"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("orders-service/src/main/resources/application.yml"),
                "fact", Map.of("name", "h2", "attributes", Map.of("role", "datasource", "target", "h2", "dbVendor", "h2"))))));
        putNode(s, root, id("z-explicit-mongo"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("orders-service/src/main/resources/application-docker.yml"),
                "fact", Map.of("name", "orders-db", "attributes", Map.of("role", "mongodb", "target", "orders-db"))))));

        DeclarativeTopologyProjection.project(s, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);
        var strictPairs = declarativePairs(s);
        // Item 7: explicit orders-db wins; fallback localhost mongodb & h2 suppressed
        assertTrue(strictPairs.contains("orders-service->orders-db"));
        assertFalse(strictPairs.contains("orders-service->mongodb"));
        assertFalse(strictPairs.contains("orders-service->h2"));
        // Item 9: shared application.yml eureka-client wired to orders-service, NOT worker-service
        assertTrue(strictPairs.contains("orders-service->eureka-server"));
        assertFalse(strictPairs.contains("worker-service->eureka-server"));
        // Item 10: direct Java @EnableEurekaClient -> unique @EnableEurekaServer with trust="SOURCE_SYNTAX" in STRICT
        assertTrue(hasDeclarativeEdgeWithTrust(s, "direct-service", "eureka-server", "SOURCE_SYNTAX"));

        // Verify Item 7 DFD orientation (orders-db -> orders-service via image/db token)
        var dfdStore = newMemoryStore();
        s.records("nodes", GraphSnapshotCodec::node).forEach((k, v) -> {
            if (!"INFRA_COMPONENT".equals(v.kind())) dfdStore.records("nodes", GraphSnapshotCodec::node).put(k, v);
        });
        // Re-run cleanly on fresh store with DFD_EXPORT to verify orders-db -> orders-service
        var dfdFresh = newMemoryStore();
        putNode(dfdFresh, root, id("ord"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "orders-service", "directory", "orders-service")));
        putNode(dfdFresh, root, id("dc-odb"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "orders-db", "span", Map.of("start", 1, "end", 4), "attributes", Map.of())))));
        putNode(dfdFresh, root, id("dc-odb-img"), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_image", "name", "mongo:4.2.21", "owner", "1:4", "attributes", Map.of("image", "mongo:4.2.21"))))));
        putNode(dfdFresh, root, id("z-explicit-mongo"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("orders-service/src/main/resources/application-docker.yml"),
                "fact", Map.of("name", "orders-db", "attributes", Map.of("role", "mongodb", "target", "orders-db"))))));
        DeclarativeTopologyProjection.project(dfdFresh, root, ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT, () -> false);
        assertTrue(hasDeclarativeEdgeWithTrust(dfdFresh, "orders-db", "orders-service", "DERIVED_DFD_POLICY"));

        // Verify Item 10 skips direct Java Eureka edge when Consul is present
        var consulStore = newMemoryStore();
        putNode(consulStore, root, id("euk"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "eureka-server", "directory", "eureka-server")));
        putNode(consulStore, root, id("svc"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "svc", "directory", "svc")));
        putNode(consulStore, root, id("r-euk"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("eureka-server/src/E.java"), "fact", Map.of("name", "EUREKA_SERVER", "attributes", Map.of("role", "EUREKA_SERVER"))))));
        putNode(consulStore, root, id("r-svc"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("svc/src/S.java"), "fact", Map.of("name", "DISCOVERY_CLIENT", "attributes", Map.of("role", "DISCOVERY_CLIENT"))))));
        putNode(consulStore, root, id("dc-consul"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "consul", "span", Map.of("start", 1, "end", 2), "attributes", Map.of())))));
        DeclarativeTopologyProjection.project(consulStore, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);
        assertFalse(declarativePairs(consulStore).contains("svc->eureka-server"),
                "direct @EnableDiscoveryClient -> EurekaServer must skip when Consul is present");
    }

    @Test void batch11DfdExportOrientationsStarterGuardsZuulExplicitRoutesAndIngressPrecedence() throws Exception {
        var s = newMemoryStore();
        putNode(s, root, id("web"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "web-application", "directory", "web-application", "tool", "NPM")));
        putNode(s, root, id("gw"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "api-gateway", "directory", "api-gateway")));
        putNode(s, root, id("auth"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "auth-server", "directory", "auth-server")));
        putNode(s, root, id("cfg"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "config-server", "directory", "config-server")));
        putNode(s, root, id("zip"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "zipkin", "directory", "zipkin")));
        putNode(s, root, id("mon"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "monitor", "directory", "monitor")));
        putNode(s, root, id("prod"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "producer-svc", "directory", "producer-svc")));
        putNode(s, root, id("cons"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "consumer-svc", "directory", "consumer-svc")));
        putNode(s, root, id("unrouted"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "unrouted-svc", "directory", "unrouted-svc")));

        // Roles
        putNode(s, root, id("r-gw"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("api-gateway/src/Gw.java"), "fact", Map.of("name", "ZUUL_PROXY", "attributes", Map.of("role", "ZUUL_PROXY"))))));
        putNode(s, root, id("r-gw-o"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("api-gateway/src/Gw.java"), "fact", Map.of("name", "OAUTH2_RESOURCE", "attributes", Map.of("role", "OAUTH2_RESOURCE"))))));
        putNode(s, root, id("r-auth"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("auth-server/src/A.java"), "fact", Map.of("name", "OAUTH2_AUTH_SERVER", "attributes", Map.of("role", "OAUTH2_AUTH_SERVER"))))));
        putNode(s, root, id("r-cfg"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("config-server/src/C.java"), "fact", Map.of("name", "CONFIG_SERVER", "attributes", Map.of("role", "CONFIG_SERVER"))))));
        putNode(s, root, id("r-zip"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("zipkin/src/Z.java"), "fact", Map.of("name", "ZIPKIN_STREAM_SERVER", "attributes", Map.of("role", "ZIPKIN_STREAM_SERVER"))))));
        putNode(s, root, id("r-prod-cb"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("producer-svc/src/P.java"), "fact", Map.of("name", "CIRCUIT_BREAKER", "attributes", Map.of("role", "CIRCUIT_BREAKER"))))));
        putNode(s, root, id("r-unr-euk"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("unrouted-svc/src/U.java"), "fact", Map.of("name", "EUREKA_CLIENT", "attributes", Map.of("role", "EUREKA_CLIENT"))))));

        // Explicit zuul.routes.* on api-gateway -> producer-svc (must suppress blanket Zuul auto-proxy to unrouted-svc!)
        putNode(s, root, id("zuul-rt"), "SOURCE_GATEWAY_ROUTE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("api-gateway/src/main/resources/application.yml"),
                "fact", Map.of("nodeKind", "zuul_route", "name", "producer-svc", "attributes", Map.of("targetService", "producer-svc", "spec", "netflix-zuul"))))));
        // Gateway OAuth2 config ref to auth-server must stay api-gateway -> auth-server
        putNode(s, root, id("gw-auth"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("api-gateway/src/main/resources/application.yml"),
                "fact", Map.of("name", "auth-server", "attributes", Map.of("role", "oauth2-auth", "target", "auth-server"))))));

        // Starters: monitor has turbine-stream; producer-svc has bus-amqp + zipkin; consumer-svc has bare bus-amqp (no CIRCUIT_BREAKER)
        putNode(s, root, id("st-mon-ts"), "SOURCE_INFRA_DEPENDENCY_STARTER", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("monitor/pom.xml"), "fact", Map.of("name", "turbine-stream", "attributes", Map.of("starter", "turbine-stream"))))));
        putNode(s, root, id("st-prod-bus"), "SOURCE_INFRA_DEPENDENCY_STARTER", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("producer-svc/pom.xml"), "fact", Map.of("name", "bus-amqp", "attributes", Map.of("starter", "bus-amqp"))))));
        putNode(s, root, id("st-prod-zip"), "SOURCE_INFRA_DEPENDENCY_STARTER", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("producer-svc/pom.xml"), "fact", Map.of("name", "zipkin", "attributes", Map.of("starter", "zipkin"))))));
        putNode(s, root, id("st-cons-bus"), "SOURCE_INFRA_DEPENDENCY_STARTER", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("consumer-svc/pom.xml"), "fact", Map.of("name", "bus-amqp", "attributes", Map.of("starter", "bus-amqp"))))));

        // Turbine/monitor non-code ref to logstash & control-plane config-server must NOT reverse
        putNode(s, root, id("mon-log"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("monitor/src/main/resources/application.yml"),
                "fact", Map.of("name", "logstash", "attributes", Map.of("role", "logstash", "target", "logstash"))))));
        putNode(s, root, id("mon-cfg"), "SOURCE_INFRA_DEPENDENCY", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("monitor/docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_depends_on", "name", "config-server", "attributes", Map.of("target", "config-server"))))));

        // Compose rabbitmq, kafka, consul, consul3 + dependencies
        putNode(s, root, id("dc-r"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_service", "name", "rabbitmq", "span", Map.of("start", 1, "end", 2), "attributes", Map.of())))));
        putNode(s, root, id("dc-k"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_service", "name", "kafka", "span", Map.of("start", 3, "end", 4), "attributes", Map.of())))));
        putNode(s, root, id("dc-c1"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_service", "name", "consul", "span", Map.of("start", 5, "end", 6), "attributes", Map.of())))));
        putNode(s, root, id("dc-c3"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_service", "name", "consul3", "span", Map.of("start", 7, "end", 8), "attributes", Map.of())))));
        putNode(s, root, id("dc-c3-c1"), "SOURCE_INFRA_DEPENDENCY", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_depends_on", "name", "consul", "owner", "7:8", "attributes", Map.of("target", "consul"))))));
        putNode(s, root, id("dc-prod"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_service", "name", "producer-svc", "span", Map.of("start", 9, "end", 10), "attributes", Map.of())))));
        putNode(s, root, id("dc-cons"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_service", "name", "consumer-svc", "span", Map.of("start", 11, "end", 12), "attributes", Map.of())))));
        putNode(s, root, id("dc-prod-k"), "SOURCE_INFRA_DEPENDENCY", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_depends_on", "name", "kafka", "owner", "9:10", "attributes", Map.of("target", "kafka"))))));
        putNode(s, root, id("dc-cons-k"), "SOURCE_INFRA_DEPENDENCY", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_depends_on", "name", "kafka", "owner", "11:12", "attributes", Map.of("target", "kafka"))))));
        putNode(s, root, id("dc-prod-c"), "SOURCE_INFRA_DEPENDENCY", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("document", "dc", "path", b64("docker-compose.yml"), "fact", Map.of("nodeKind", "compose_depends_on", "name", "consul", "owner", "9:10", "attributes", Map.of("target", "consul"))))));

        // Messaging facts: producer-svc produces to Kafka, consumer-svc ONLY consumes from Kafka
        putNode(s, root, id("ev-p"), "SOURCE_EVENT_PRODUCER_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("producer-svc/src/P.java"), "fact", Map.of("name", "\"orders\"", "recovered", false, "attributes", Map.of("protocol", "KAFKA"))))));
        putNode(s, root, id("ev-c"), "SOURCE_EVENT_CONSUMER_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("consumer-svc/src/C.java"), "fact", Map.of("name", "\"orders\"", "recovered", false, "attributes", Map.of("protocol", "KAFKA"))))));

        DeclarativeTopologyProjection.project(s, root, ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT, () -> false);
        var pairs = declarativePairs(s);

        // Item 12: Consul cluster vs client orientation
        assertTrue(hasDeclarativeEdgeWithTrust(s, "consul", "producer-svc", "DERIVED_DFD_POLICY"));
        assertTrue(pairs.contains("consul3->consul"), "consul cluster peer edge must NOT reverse");
        // Item 12: Pure Kafka consumer reverses to kafka -> consumer-svc, producer stays producer-svc -> kafka
        assertTrue(hasDeclarativeEdgeWithTrust(s, "kafka", "consumer-svc", "DERIVED_DFD_POLICY"));
        assertTrue(pairs.contains("producer-svc->kafka"));
        // Item 12: Turbine -> logstash stays monitor -> logstash (NOT reversed!)
        assertTrue(pairs.contains("monitor->logstash"));
        assertFalse(pairs.contains("logstash->monitor"));
        // Item 12: Gateway OAuth2 does NOT reverse to auth-server -> api-gateway
        assertTrue(pairs.contains("api-gateway->auth-server"));
        assertFalse(pairs.contains("auth-server->api-gateway"));
        // Item 13: ZIPKIN_STREAM_SERVER suppresses direct HTTP starter edge; bare bus-amqp suppressed; CIRCUIT_BREAKER+bus-amqp & turbine-stream wired
        assertFalse(pairs.contains("producer-svc->zipkin"), "ZIPKIN_STREAM_SERVER must suppress direct HTTP zipkin starter wiring");
        assertTrue(pairs.contains("producer-svc->rabbitmq"), "CIRCUIT_BREAKER + bus-amqp with TURBINE_STREAM wires to rabbitmq");
        assertFalse(pairs.contains("consumer-svc->rabbitmq"), "bare bus-amqp without CIRCUIT_BREAKER must NOT wire to rabbitmq");
        assertTrue(hasDeclarativeEdgeWithTrust(s, "rabbitmq", "monitor", "DERIVED_DFD_POLICY"), "turbine-stream starter maps to TURBINE_STREAM and receives rabbitmq -> monitor");
        // Item 14: explicit zuul_route skips blanket Zuul auto-proxy to unrouted-svc
        assertTrue(pairs.contains("api-gateway->producer-svc"));
        assertFalse(pairs.contains("api-gateway->unrouted-svc"));
        // Item 16: outer SPA web-application takes precedence over internal api-gateway for user actor
        assertTrue(hasDeclarativeEdgeWithTrust(s, "user", "web-application", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(s, "web-application", "user", "DERIVED_DFD_POLICY"));
        assertFalse(pairs.contains("user->api-gateway"), "outer SPA web-application must suppress redundant user -> api-gateway");

        // Item 13 (HTTP Turbine): separate fixture with HTTP @EnableTurbine (no explicit turbine.appConfig) + @EnableCircuitBreaker
        var httpTurb = newMemoryStore();
        putNode(httpTurb, root, id("tb"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "turbine", "directory", "turbine")));
        putNode(httpTurb, root, id("svc"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component", Map.of("id", "orders-service", "directory", "orders-service")));
        putNode(httpTurb, root, id("rtb"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("turbine/src/T.java"), "fact", Map.of("name", "TURBINE", "attributes", Map.of("role", "TURBINE"))))));
        putNode(httpTurb, root, id("rcb"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("orders-service/src/O.java"), "fact", Map.of("name", "CIRCUIT_BREAKER", "attributes", Map.of("role", "CIRCUIT_BREAKER"))))));
        DeclarativeTopologyProjection.project(httpTurb, root, ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT, () -> false);
        assertTrue(hasDeclarativeEdgeWithTrust(httpTurb, "orders-service", "turbine", "DERIVED_DFD_POLICY"));
    }

    @Test void batch11EventFlowSymbolicTopicEmitsBrokerEdgesAndSuppressesPeerShortcut() throws Exception {
        java.util.function.Consumer<GraphProjectionStore> seed = s -> {
            putNode(s, root, id("kafka-infra"), "INFRA_COMPONENT",
                    GraphEvidence.capture("architecture.infra-component", Map.of("name", "kafka", "image", "kafka")));
            putNode(s, root, id("prod-sym"), "SOURCE_EVENT_PRODUCER_CANDIDATE",
                    GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                            "path", b64("order-service/src/main/java/Pub.java"),
                            "fact", Map.of("name", "TOPIC_NAME", "recovered", false,
                                    "attributes", Map.of("protocol", "KAFKA", "symbolicTopic", "true"))))));
            putNode(s, root, id("cons-sym"), "SOURCE_EVENT_CONSUMER_CANDIDATE",
                    GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                            "path", b64("shipping-service/src/main/java/Sub.java"),
                            "fact", Map.of("name", "TOPIC_NAME", "recovered", false,
                                    "attributes", Map.of("protocol", "KAFKA", "symbolicTopic", "true"))))));
        };

        var strictStore = newMemoryStore();
        seed.accept(strictStore);
        DeclarativeTopologyProjection.project(strictStore, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);
        EventFlowProjection.project(strictStore, root, () -> false);
        var strictEdges = strictStore.records("edges", GraphSnapshotCodec::edge).values();
        assertTrue(strictEdges.stream().anyMatch(e -> "PUBLISHES_TO".equals(e.kind()) && e.source().equals(id("prod-sym"))));
        assertTrue(strictEdges.stream().anyMatch(e -> "CONSUMES_FROM".equals(e.kind()) && e.source().equals(id("cons-sym"))
                && e.target().identities().equals(List.of(id("kafka-infra")))));
        assertTrue(strictEdges.stream().noneMatch(e -> "EVENT_FLOW".equals(e.kind()) || "PUBLISHES_EVENT".equals(e.kind())),
                "symbolicTopic=true must NOT emit speculative peer-to-peer topic matches");

        var dfdStore = newMemoryStore();
        seed.accept(dfdStore);
        DeclarativeTopologyProjection.project(dfdStore, root, ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT, () -> false);
        EventFlowProjection.project(dfdStore, root, () -> false);
        var dfdEdges = dfdStore.records("edges", GraphSnapshotCodec::edge).values();
        assertTrue(dfdEdges.stream().anyMatch(e -> "PUBLISHES_TO".equals(e.kind()) && e.source().equals(id("prod-sym"))));
        assertTrue(dfdEdges.stream().anyMatch(e -> "CONSUMES_FROM".equals(e.kind()) && e.source().equals(id("kafka-infra"))
                && e.target().identities().equals(List.of(id("cons-sym")))));
        assertTrue(dfdEdges.stream().noneMatch(e -> "EVENT_FLOW".equals(e.kind()) || "PUBLISHES_EVENT".equals(e.kind())));
    }

    @Test void batch11DfdExportAndStrictEmitFullEdgesWithoutDroppingDeclarativeConnections() throws Exception {
        // Mirrors multi-service Spring Cloud Config + Eureka + GitHub + HTTP topology under both STRICT and DFD_EXPORT
        java.util.function.Consumer<GraphProjectionStore> seed = s -> {
            for (String svc : List.of("config-service", "discovery-service", "account-service", "customer-service",
                    "basket-service", "item-service", "price-service", "store-service")) {
                putNode(s, root, id("bc-" + svc), "BUILD_COMPONENT",
                        GraphEvidence.capture("polyglot.build-component", Map.of("id", svc, "directory", svc)));
            }
            putNode(s, root, id("r-cfg"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("config-service/src/App.java"), "fact", Map.of("name", "CONFIG_SERVER", "attributes", Map.of("role", "CONFIG_SERVER"))))));
            putNode(s, root, id("r-disc"), "SOURCE_SPRING_CLOUD_ROLE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("discovery-service/src/App.java"), "fact", Map.of("name", "EUREKA_SERVER", "attributes", Map.of("role", "EUREKA_SERVER"))))));
            putNode(s, root, id("cfg-git"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("config-service/src/main/resources/application.yml"), "fact", Map.of("name", "github-repository", "attributes", Map.of("role", "config-git-repo", "target", "github-repository"))))));
            putNode(s, root, id("cfg-euk"), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("config-service/src/main/resources/application.yml"), "fact", Map.of("name", "discovery-service", "attributes", Map.of("role", "eureka-client", "target", "discovery-service"))))));
            for (String svc : List.of("account-service", "customer-service", "basket-service", "item-service", "price-service", "store-service")) {
                putNode(s, root, id("c-cfg-" + svc), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64(svc + "/src/main/resources/bootstrap.yml"), "fact", Map.of("name", "config-service", "attributes", Map.of("role", "config-client", "target", "config-service"))))));
                putNode(s, root, id("c-euk-" + svc), "SOURCE_INFRA_CONFIG_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64(svc + "/src/main/resources/application.yml"), "fact", Map.of("name", "discovery-service", "attributes", Map.of("role", "eureka-client", "target", "discovery-service"))))));
            }
            putNode(s, root, id("http-item"), "SOURCE_HTTP_CALL_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("basket-service/src/Client.java"), "fact", Map.of("nodeKind", "method_call", "name", "\"http://item-service/items\"", "attributes", Map.of("method", "GET", "targetService", "item-service"))))));
            putNode(s, root, id("http-price"), "SOURCE_HTTP_CALL_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of("path", b64("basket-service/src/Client.java"), "fact", Map.of("nodeKind", "method_call", "name", "\"http://price-service/prices\"", "attributes", Map.of("method", "GET", "targetService", "price-service"))))));
        };

        var strictStore = newMemoryStore();
        seed.accept(strictStore);
        DeclarativeTopologyProjection.project(strictStore, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);
        assertEquals(16, declarativePairs(strictStore).size());

        var dfdStore = newMemoryStore();
        seed.accept(dfdStore);
        DeclarativeTopologyProjection.project(dfdStore, root, ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT, () -> false);
        var dfdPairs = declarativePairs(dfdStore);
        assertEquals(16, dfdPairs.size());
        assertTrue(dfdPairs.contains("github-repository->config-service"));
        assertTrue(dfdPairs.contains("config-service->account-service"));
        assertTrue(dfdPairs.contains("account-service->discovery-service"));
        assertTrue(dfdPairs.contains("basket-service->item-service"));
        assertTrue(dfdPairs.contains("basket-service->price-service"));
    }

    @Test void generalDatastoreSmtpAndTracingInferenceUsesStructuralImagePortAndTokenSignals() throws Exception {
        var s = newMemoryStore();
        putNode(s, root, id("bc-svc"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "checkout-api", "directory", "checkout-api")));
        // 1. Container-only cache inferred solely from image (valkey/valkey:8) with opaque service name
        putNode(s, root, id("k8s-kv"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-doc", "path", b64("deploy/k8s.yaml"),
                "fact", Map.of("nodeKind", "k8s_deployment", "name", "session-kv",
                        "span", Map.of("start", 1, "end", 5), "attributes", Map.of("kind", "Deployment"))))));
        putNode(s, root, id("k8s-kv-img"), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-doc", "path", b64("deploy/k8s.yaml"),
                "fact", Map.of("nodeKind", "k8s_image", "name", "valkey/valkey:8-alpine", "owner", "1:5",
                        "attributes", Map.of("image", "valkey/valkey:8-alpine"))))));
        // 2. Container-only datastore inferred solely from well-known Redis port 6379
        putNode(s, root, id("k8s-portdb"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-doc", "path", b64("deploy/k8s.yaml"),
                "fact", Map.of("nodeKind", "k8s_deployment", "name", "ephemeral-store",
                        "span", Map.of("start", 10, "end", 15), "attributes", Map.of("kind", "Deployment"))))));
        putNode(s, root, id("k8s-portdb-p"), "SOURCE_INFRA_PORT", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-doc", "path", b64("deploy/k8s.yaml"),
                "fact", Map.of("nodeKind", "k8s_port", "name", "6379", "owner", "10:15",
                        "attributes", Map.of("port", "6379", "externalPort", "false", "spec", "kubernetes"))))));
        // 3. Declared SMTP relay container (mailpit) + declared tracing container exposing Zipkin port 9411
        putNode(s, root, id("dc-mail"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc-doc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "relay-inbox",
                        "span", Map.of("start", 1, "end", 4), "attributes", Map.of())))));
        putNode(s, root, id("dc-mail-img"), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc-doc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_image", "name", "axllent/mailpit:latest", "owner", "1:4",
                        "attributes", Map.of("image", "axllent/mailpit:latest"))))));
        putNode(s, root, id("dc-trace"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc-doc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "span-collector",
                        "span", Map.of("start", 10, "end", 14), "attributes", Map.of())))));
        putNode(s, root, id("dc-trace-port"), "SOURCE_INFRA_PORT", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc-doc", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_port", "name", "9411", "owner", "10:14",
                        "attributes", Map.of("port", "9411", "externalPort", "true"))))));
        // checkout-api env refs to session-kv & ephemeral-store + Spring starters mail & zipkin
        putNode(s, root, id("k8s-svc"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-doc", "path", b64("deploy/k8s.yaml"),
                "fact", Map.of("nodeKind", "k8s_deployment", "name", "checkout-api",
                        "span", Map.of("start", 20, "end", 25), "attributes", Map.of("kind", "Deployment"))))));
        putNode(s, root, id("k8s-env-1"), "SOURCE_INFRA_ENV_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-doc", "path", b64("deploy/k8s.yaml"),
                "fact", Map.of("nodeKind", "k8s_env", "name", "session-kv", "owner", "20:25",
                        "attributes", Map.of("target", "session-kv", "envKey", "CACHE_ADDR"))))));
        putNode(s, root, id("k8s-env-2"), "SOURCE_INFRA_ENV_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-doc", "path", b64("deploy/k8s.yaml"),
                "fact", Map.of("nodeKind", "k8s_env", "name", "ephemeral-store", "owner", "20:25",
                        "attributes", Map.of("target", "ephemeral-store", "envKey", "STORE_ADDR"))))));
        putNode(s, root, id("st-mail"), "SOURCE_INFRA_DEPENDENCY_STARTER", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("checkout-api/pom.xml"),
                "fact", Map.of("nodeKind", "maven_dependency", "name", "mail", "attributes", Map.of("starter", "mail"))))));
        putNode(s, root, id("st-zip"), "SOURCE_INFRA_DEPENDENCY_STARTER", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "path", b64("checkout-api/pom.xml"),
                "fact", Map.of("nodeKind", "maven_dependency", "name", "zipkin", "attributes", Map.of("starter", "zipkin"))))));

        DeclarativeTopologyProjection.project(s, root, ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT, () -> false);
        var pairs = declarativePairs(s);
        var evs = s.records("evidence", GraphSnapshotCodec::evidence);

        // Both image-inferred (valkey) and port-inferred (6379) datastores reverse to datastore -> service under DFD_EXPORT
        assertTrue(hasDeclarativeEdgeWithTrust(s, "session-kv", "checkout-api", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(s, "ephemeral-store", "checkout-api", "DERIVED_DFD_POLICY"));
        // Declared mailpit container reused (not synthetic "smtp"), and port-9411 collector matched for zipkin starter
        assertTrue(hasDeclarativeEdgeWithTrust(s, "checkout-api", "relay-inbox", "DERIVED_DFD_POLICY"));
        assertTrue(hasDeclarativeEdgeWithTrust(s, "checkout-api", "span-collector", "DERIVED_DFD_POLICY"));
        // Every INFRA_COMPONENT node carries ONLY architecture.infra-component evidence (no path#name clobbering)
        for (var n : s.records("nodes", GraphSnapshotCodec::node).values()) {
            if (!"INFRA_COMPONENT".equals(n.kind())) continue;
            assertTrue(n.evidence().stream().map(d -> evs.get(d.value()))
                    .allMatch(e -> e != null && "architecture.infra-component".equals(e.family())),
                    "INFRA_COMPONENT must carry only architecture.infra-component evidence: " + n);
        }
    }

    @Test void multiManifestKubernetesDeduplicationPreventsImagePoisoningAndSelectorDuplicates() throws Exception {
        var s = newMemoryStore();
        // Polyglot services across Java, Python, Go, C#
        for (String svc : List.of("src/catalog", "src/mailer", "src/storefront", "src/basket")) {
            putNode(s, root, id("bc-" + svc), "BUILD_COMPONENT",
                    GraphEvidence.capture("polyglot.build-component", Map.of("id", "MOD:" + svc, "directory", svc)));
        }
        // Multi-document K8s file where catalog image appears first and k8s_service nodes appear BEFORE k8s_deployment nodes
        putNode(s, root, id("d-cat"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-all", "path", b64("release/manifests.yaml"),
                "fact", Map.of("nodeKind", "k8s_deployment", "name", "catalog",
                        "span", Map.of("start", 1, "end", 5), "attributes", Map.of("kind", "Deployment"))))));
        putNode(s, root, id("d-cat-img"), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-all", "path", b64("release/manifests.yaml"),
                "fact", Map.of("nodeKind", "k8s_image", "name", "registry.example/demo/catalog:v1", "owner", "1:5",
                        "attributes", Map.of("image", "registry.example/demo/catalog:v1"))))));
        // k8s_service for cache-redis & mailer appear BEFORE their deployments to verify priority sort + zero directory poisoning
        putNode(s, root, id("s-cache"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-all", "path", b64("release/manifests.yaml"),
                "fact", Map.of("nodeKind", "k8s_service", "name", "cache-redis",
                        "span", Map.of("start", 10, "end", 14), "attributes", Map.of("kind", "Service", "selectorTarget", "cache-redis"))))));
        putNode(s, root, id("s-mailer"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-all", "path", b64("release/manifests.yaml"),
                "fact", Map.of("nodeKind", "k8s_service", "name", "mailer",
                        "span", Map.of("start", 15, "end", 19), "attributes", Map.of("kind", "Service", "selectorTarget", "mailer"))))));
        // LoadBalancer Service storefront-public selecting app: storefront
        putNode(s, root, id("s-public"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-all", "path", b64("release/manifests.yaml"),
                "fact", Map.of("nodeKind", "k8s_service", "name", "storefront-public",
                        "span", Map.of("start", 20, "end", 24),
                        "attributes", Map.of("kind", "Service", "serviceType", "LoadBalancer", "selectorTarget", "storefront"))))));
        putNode(s, root, id("s-public-port"), "SOURCE_INFRA_PORT", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-all", "path", b64("release/manifests.yaml"),
                "fact", Map.of("nodeKind", "k8s_port", "name", "80", "owner", "20:24",
                        "attributes", Map.of("port", "80", "externalPort", "true", "spec", "kubernetes"))))));
        // Deployments for cache-redis and basket (with env ref to cache-redis:6379)
        putNode(s, root, id("d-cache"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-all", "path", b64("release/manifests.yaml"),
                "fact", Map.of("nodeKind", "k8s_deployment", "name", "cache-redis",
                        "span", Map.of("start", 30, "end", 35), "attributes", Map.of("kind", "Deployment"))))));
        putNode(s, root, id("d-cache-img"), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-all", "path", b64("release/manifests.yaml"),
                "fact", Map.of("nodeKind", "k8s_image", "name", "redis:alpine", "owner", "30:35",
                        "attributes", Map.of("image", "redis:alpine"))))));
        putNode(s, root, id("d-basket"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-all", "path", b64("release/manifests.yaml"),
                "fact", Map.of("nodeKind", "k8s_deployment", "name", "basket",
                        "span", Map.of("start", 40, "end", 45), "attributes", Map.of("kind", "Deployment"))))));
        putNode(s, root, id("d-basket-env"), "SOURCE_INFRA_ENV_REF", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-all", "path", b64("release/manifests.yaml"),
                "fact", Map.of("nodeKind", "k8s_env", "name", "cache-redis", "owner", "40:45",
                        "attributes", Map.of("target", "cache-redis", "envKey", "REDIS_ADDR"))))));

        DeclarativeTopologyProjection.project(s, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);
        var evs = s.records("evidence", GraphSnapshotCodec::evidence);
        var infraNames = s.records("nodes", GraphSnapshotCodec::node).values().stream()
                .filter(n -> "INFRA_COMPONENT".equals(n.kind()))
                .flatMap(n -> n.evidence().stream().map(d -> evs.get(d.value())))
                .filter(e -> e != null && "architecture.infra-component".equals(e.family()))
                .map(e -> GraphJson.string(GraphJson.object(GraphJson.read(e.canonicalJson(), GraphJson.Limits.defaults())).get("name")))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("cache-redis"), infraNames,
                "Only container-only cache-redis must be promoted as INFRA_COMPONENT; selector alias storefront-public and code services must unify: " + infraNames);
        assertTrue(declarativePairs(s).contains("basket->cache-redis"),
                "basket must connect to cache-redis without image-tail hijacking onto catalog");
    }

    @Test void singleServiceLayoutTestModuleExclusionAndReverseDnsUnificationWorkGenerically() throws Exception {
        var s = newMemoryStore();
        // Service directory with layout child src/ and tests/, plus standalone e2e-tests, IDE descriptor, and reverse-DNS module
        putNode(s, root, id("bc-basket"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "DOTNET:src/basket", "directory", "src/basket")));
        putNode(s, root, id("bc-basket-src"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "DOTNET:src/basket/src", "directory", "src/basket/src")));
        putNode(s, root, id("bc-basket-tests"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "DOTNET:src/basket/tests", "directory", "src/basket/tests")));
        putNode(s, root, id("bc-e2e"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "MAVEN:e2e-tests", "directory", "e2e-tests")));
        putNode(s, root, id("bc-ide"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "IDE:legacy-ide", "directory", "legacy-ide")));
        putNode(s, root, id("bc-auth"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "MAVEN:services/org.example.shop.auth", "directory", "services/org.example.shop.auth")));

        // K8s & Compose declarations for basket, auth, and shop-auth must unify with BUILD_COMPONENTs
        putNode(s, root, id("k8s-basket"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-1", "path", b64("k8s/basket.yaml"),
                "fact", Map.of("nodeKind", "k8s_deployment", "name", "basket",
                        "span", Map.of("start", 1, "end", 5), "attributes", Map.of("kind", "Deployment"))))));
        putNode(s, root, id("dc-auth"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "dc-1", "path", b64("docker-compose.yml"),
                "fact", Map.of("nodeKind", "compose_service", "name", "auth",
                        "span", Map.of("start", 1, "end", 5), "attributes", Map.of())))));
        putNode(s, root, id("k8s-shop-auth"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "document", "k8s-2", "path", b64("k8s/auth.yaml"),
                "fact", Map.of("nodeKind", "k8s_deployment", "name", "shop-auth",
                        "span", Map.of("start", 1, "end", 5), "attributes", Map.of("kind", "Deployment"))))));

        DeclarativeTopologyProjection.project(s, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);
        var evs = s.records("evidence", GraphSnapshotCodec::evidence);
        var infraNames = s.records("nodes", GraphSnapshotCodec::node).values().stream()
                .filter(n -> "INFRA_COMPONENT".equals(n.kind()))
                .flatMap(n -> n.evidence().stream().map(d -> evs.get(d.value())))
                .filter(e -> e != null && "architecture.infra-component".equals(e.family()))
                .map(e -> GraphJson.string(GraphJson.object(GraphJson.read(e.canonicalJson(), GraphJson.Limits.defaults())).get("name")))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(infraNames.isEmpty(),
                "basket, auth, and shop-auth must unify with BUILD_COMPONENTs without spawning duplicate INFRA_COMPONENTs: " + infraNames);
    }
}
