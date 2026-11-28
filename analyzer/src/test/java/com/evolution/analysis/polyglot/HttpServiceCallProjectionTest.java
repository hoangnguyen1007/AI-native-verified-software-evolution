package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.graph.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HttpServiceCallProjectionTest {
    private final SnapshotIdentity snapshot=SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:http-joins"),ContentDigest.sha256Utf8("inputs"));
    private final String root=snapshot.value();
    private final Map<String,KnowledgeGraph.Node> nodes=new TreeMap<>();
    private final Map<String,KnowledgeGraph.Edge> edges=new TreeMap<>();
    private final Map<String,GraphEvidence> evidence=new TreeMap<>();
    @Test void literalClientTemplatesJoinCompatibleRouteSegmentsWithoutAssumingValues()throws Exception {
        syntax("client",root,"SOURCE_HTTP_CALL_CANDIDATE","http://customers/owners/{ownerId}/pets","GET",false);
        syntax("matching",root,"ROUTE_CANDIDATE","/owners/{id}/pets","GET",false);
        syntax("other",root,"ROUTE_CANDIDATE","/owners/{id}/visits","GET",false);
        HttpServiceCallProjection.project(nodes,edges,evidence,root,()->false);
        assertEquals(1,edges.size());assertEquals(1,edges.values().iterator().next().target().identities().size());
        assertTrue(evidence.values().stream().anyMatch(e->e.family().equals("polyglot.http-uri-match")&&e.canonicalJson().contains("\"routingEstablished\":false")));
    }
    @Test void boundJavaClientsKeepTheirAnalysisContextAndDynamicUrisRemainObligations()throws Exception {
        String context=ContentDigest.sha256Utf8("java-analysis").value();
        add("literal-java",context,"SOURCE_HTTP_CALL_CANDIDATE",GraphEvidence.capture("java.http-client-call",Map.of("call",Map.of("uri","/pets/42","methods",List.of("GET")))));
        add("dynamic-java",context,"SOURCE_HTTP_CALL_CANDIDATE",GraphEvidence.capture("java.http-client-call",Map.of("call",Map.of("uri","","methods",List.of("GET")))));
        syntax("server",root,"ROUTE_CANDIDATE","/pets/{id}","GET",false);
        HttpServiceCallProjection.project(nodes,edges,evidence,root,()->false);
        assertEquals(1,edges.size());var join=edges.values().iterator().next();
        assertEquals("HTTP_CALL_REFERENCE",nodes.get(root+"|"+join.source()).kind());
        assertEquals(2,nodes.values().stream().filter(n->n.kind().equals("HTTP_CALL_REFERENCE")).count());
        assertTrue(evidence.values().stream().anyMatch(e->e.family().equals("polyglot.http-uri-match")&&e.canonicalJson().contains(context)));
        assertTrue(evidence.values().stream().anyMatch(e->e.family().equals("evidence.gap")&&e.canonicalJson().contains("HTTP_URI_UNSUPPORTED")));
    }

    @Test void sourceClientsJoinAllMatchingRoutesAndKeepSpringContextProvenance()throws Exception {
        var client=syntax("client",root,"SOURCE_HTTP_CALL_CANDIDATE","/pets/42?full=true","GET",false);
        var other=syntax("go-server",root,"ROUTE_CANDIDATE","/pets/{id}","GET",false);
        var spring=GraphEvidence.capture("spring.endpoint-declaration",Map.of("route",Map.of("path","/pets/{petId}","methods",List.of("GET"),"implicitHead",true)));
        add("spring",ContentDigest.sha256Utf8("spring-analysis").value(),"ENDPOINT",spring);
        syntax("wrong-method",root,"ROUTE_CANDIDATE","/pets/:id","POST",false);
        syntax("wrong-prefix",root,"ROUTE_CANDIDATE","/api/pets/:id","GET",false);
        syntax("recovered",root,"ROUTE_CANDIDATE","/pets/:id","GET",true);
        HttpServiceCallProjection.project(nodes,edges,evidence,root,()->false);
        var joins=edges.values().stream().filter(e->e.kind().equals("polyglot.service-call.candidate")).toList();
        assertEquals(1,joins.size());var join=joins.getFirst();assertEquals(client,join.source());
        assertEquals(KnowledgeGraph.TargetState.CANDIDATES,join.target().state());assertEquals(2,join.target().identities().size());assertTrue(join.target().identities().contains(other));
        assertEquals(1,nodes.values().stream().filter(n->n.kind().equals("ENDPOINT_REFERENCE")).count());
        assertTrue(evidence.values().stream().anyMatch(e->e.family().equals("polyglot.http-uri-match")&&e.canonicalJson().contains("\"routingEstablished\":false")));
        assertTrue(evidence.values().stream().anyMatch(e->e.family().equals("evidence.gap")&&e.canonicalJson().contains("HTTP_ROUTING_EVIDENCE_MISSING")));
        var expected=new TreeMap<>(edges);HttpServiceCallProjection.project(nodes,edges,evidence,root,()->false);assertEquals(expected,edges);
    }

    @Test void unmatchedAndUnsafeRequestsRemainExplicitAndCancellationStopsProjection()throws Exception {
        syntax("no-provider",root,"SOURCE_HTTP_CALL_CANDIDATE","/api/pets","GET",false);
        syntax("dynamic",root,"SOURCE_HTTP_CALL_CANDIDATE","/pets/{id:[0-9]+}","GET",false);
        syntax("server",root,"ROUTE_CANDIDATE","/pets","GET",false);
        HttpServiceCallProjection.project(nodes,edges,evidence,root,()->false);assertTrue(edges.isEmpty());
        assertEquals(2,nodes.values().stream().filter(n->n.kind().equals("CAPABILITY_GAP")).count());
        assertTrue(evidence.values().stream().anyMatch(e->e.canonicalJson().contains("HTTP_URI_NO_CANDIDATE")));
        assertTrue(evidence.values().stream().anyMatch(e->e.canonicalJson().contains("HTTP_URI_UNSUPPORTED")));
        assertThrows(java.io.IOException.class,()->HttpServiceCallProjection.project(nodes,edges,evidence,root,()->true));
    }
    private String syntax(String name,String context,String kind,String path,String method,boolean recovered) {
        return add(name,context,kind,GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of("fact",Map.of("name","'"+path+"'","attributes",Map.of("method",method),"recovered",recovered)))));
    }
    @Test void springWholeSegmentWildcardsMatchWithoutPoisoningDisjointRoutes()throws Exception {
        assertEquals("m8.4-spring-segments-v4",HttpServiceCallProjection.PROVIDER.version());
        syntax("client",root,"SOURCE_HTTP_CALL_CANDIDATE","/owners/17/pets/3","GET",false);
        syntax("short-client",root,"SOURCE_HTTP_CALL_CANDIDATE","/owners","GET",false);
        add("spring",root,"ENDPOINT",GraphEvidence.capture("spring.endpoint-declaration",Map.of("route",Map.of("path","/owners/*/pets/{petId}","methods",List.of("GET")))));
        add("spring-short",root,"ENDPOINT",GraphEvidence.capture("spring.endpoint-declaration",Map.of("route",Map.of("path","/owners","methods",List.of("GET")))));
        HttpServiceCallProjection.project(nodes,edges,evidence,root,()->false);
        assertEquals(2,edges.values().stream().filter(e->e.kind().equals("polyglot.service-call.candidate")).count());
        assertFalse(evidence.values().stream().anyMatch(e->e.family().equals("evidence.gap")&&e.canonicalJson().contains("HTTP_ROUTE_PATTERN_UNSUPPORTED")));
        assertTrue(evidence.values().stream().filter(e->e.family().equals("polyglot.http-uri-match")).allMatch(e->e.canonicalJson().contains("HTTP_ROUTING_EVIDENCE_MISSING")));
    }
    @Test void gatewayStripPrefixAndLbSchemeJoinTargetServiceEndpoints()throws Exception {
        // Gateway call /api/vet/vets matches gateway route (StripPrefix=2 -> /vets) targeting vets-service
        syntax("client",root,"SOURCE_HTTP_CALL_CANDIDATE","/api/vet/vets","GET",false);
        syntax("lb-client",root,"SOURCE_HTTP_CALL_CANDIDATE","lb://vets-service/vets","GET",false);
        var gwProof=GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of("fact",Map.of("name","vets-service","recovered",false,
                "attributes",Map.of("uri","lb://vets-service","targetService","vets-service","pathPrefix","/api/vet/**","stripPrefix","2")))));
        add("gw-route",root,"SOURCE_GATEWAY_ROUTE",gwProof);
        var idProof=GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of("path","vets-service/src/App.java",
                "fact",Map.of("name","vets-service","recovered",false,"attributes",Map.of("serviceName","vets-service")))));
        add("id-node",root,"SOURCE_CONFIG_SERVICE_IDENTITY",idProof);
        var epProof=GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of("path","vets-service/src/VetsController.java",
                "fact",Map.of("name","'/vets'","recovered",false,"attributes",Map.of("method","GET")))));
        add("vets-ep",root,"ENDPOINT",epProof);
        var wrongEpProof=GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of("path","visits-service/src/VisitsController.java",
                "fact",Map.of("name","'/vets'","recovered",false,"attributes",Map.of("method","GET")))));
        var wrongIdProof=GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of("path","visits-service/src/App.java",
                "fact",Map.of("name","visits-service","recovered",false,"attributes",Map.of("serviceName","visits-service")))));
        add("wrong-id",root,"SOURCE_CONFIG_SERVICE_IDENTITY",wrongIdProof);
        add("wrong-ep",root,"ENDPOINT",wrongEpProof);
        HttpServiceCallProjection.project(nodes,edges,evidence,root,()->false);
        var joins=edges.values().stream().filter(e->e.kind().equals("polyglot.service-call.candidate")).toList();
        assertEquals(2,joins.size());
        for(var j:joins) {
            assertEquals(KnowledgeGraph.TargetState.RESOLVED,j.target().state(),"Host affinity must select vets-service only, not visits-service");
            assertTrue(j.target().identities().getFirst().equals(ContentDigest.sha256Utf8("vets-ep").value()));
        }
    }
    @Test void jaxRsAndServletEndpointsJoinHttpClientsCleanly() throws Exception {
        syntax("client-jaxrs", root, "SOURCE_HTTP_CALL_CANDIDATE", "/rest/products/42", "GET", false);
        syntax("client-servlet", root, "SOURCE_HTTP_CALL_CANDIDATE", "/cart/checkout", "POST", false);
        add("jaxrs-ep", root, "ENDPOINT", GraphEvidence.capture("jaxrs.endpoint-declaration",
                Map.of("route", Map.of("path", "/rest/products/{id}", "methods", List.of("GET"), "implicitHead", true, "module", "inventory"))));
        add("servlet-ep", root, "ENDPOINT", GraphEvidence.capture("servlet.endpoint-declaration",
                Map.of("route", Map.of("path", "/cart/*", "methods", List.of("GET", "POST"), "module", "webui"))));
        HttpServiceCallProjection.project(nodes, edges, evidence, root, () -> false);
        var joins = edges.values().stream().filter(e -> e.kind().equals("polyglot.service-call.candidate")).toList();
        assertEquals(2, joins.size());
        assertTrue(joins.stream().anyMatch(j -> j.target().identities().equals(List.of(ContentDigest.sha256Utf8("jaxrs-ep").value()))));
        assertTrue(joins.stream().anyMatch(j -> j.target().identities().equals(List.of(ContentDigest.sha256Utf8("servlet-ep").value()))));
    }

    @Test void longestPrefixBuildComponentResolutionMatchesNestedModulesCleanly() throws Exception {
        add("agg", root, "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "platform-core", "directory", "platform/core")));
        add("inv-mod", root, "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "inventory-api", "directory", "platform/core/inventory-api")));
        add("bill-mod", root, "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "billing-api", "directory", "platform/core/billing-api")));
        add("inv-id", root, "SOURCE_CONFIG_SERVICE_IDENTITY", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "platform/core/inventory-api/src/main/resources/application.yml",
                        "fact", Map.of("name", "inventory-api", "recovered", false,
                                "attributes", Map.of("serviceName", "inventory-api"))))));
        add("bill-id", root, "SOURCE_CONFIG_SERVICE_IDENTITY", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "platform/core/billing-api/src/main/resources/application.yml",
                        "fact", Map.of("name", "billing-api", "recovered", false,
                                "attributes", Map.of("serviceName", "billing-api"))))));
        String invEp = add("inv-ep", root, "ENDPOINT", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "platform/core/inventory-api/src/main/java/ItemsController.java",
                        "fact", Map.of("name", "'/items/{id}'", "recovered", false,
                                "attributes", Map.of("method", "GET"))))));
        add("bill-ep", root, "ENDPOINT", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "platform/core/billing-api/src/main/java/ItemsController.java",
                        "fact", Map.of("name", "'/items/{id}'", "recovered", false,
                                "attributes", Map.of("method", "GET"))))));
        syntax("caller", root, "SOURCE_HTTP_CALL_CANDIDATE", "http://inventory-api/items/7", "GET", false);

        HttpServiceCallProjection.project(nodes, edges, evidence, root, () -> false);
        var joins = edges.values().stream().filter(e -> e.kind().equals("polyglot.service-call.candidate")).toList();
        assertEquals(1, joins.size());
        assertEquals(KnowledgeGraph.TargetState.RESOLVED, joins.getFirst().target().state());
        assertEquals(List.of(invEp), joins.getFirst().target().identities());
    }

    @Test void resolvesValuePropertyKeyAndLocalhostPortToTargetService() throws Exception {
        add("order-mod", root, "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "order-app", "directory", "order-app")));
        add("pricing-mod", root, "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "pricing-app", "directory", "pricing-app")));
        add("other-mod", root, "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "other-app", "directory", "other-app")));
        add("pricing-port", root, "SOURCE_INFRA_PORT", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "pricing-app/src/main/resources/application.yml",
                        "fact", Map.of("nodeKind", "spring_server_port", "name", "8092", "recovered", false,
                                "attributes", Map.of("port", "8092", "spec", "spring-config"))))));
        add("order-prop", root, "SOURCE_CONFIG_PROPERTY", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "order-app/src/main/resources/application.yml",
                        "fact", Map.of("nodeKind", "config_property", "name", "clients.pricing.url", "recovered", false,
                                "attributes", Map.of("key", "clients.pricing.url",
                                        "value", "http://localhost:8092/quotes",
                                        "target", "localhost", "port", "8092"))))));
        String pricingEp = add("pricing-ep", root, "ENDPOINT", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "pricing-app/src/main/java/QuoteController.java",
                        "fact", Map.of("name", "'/quotes/{id}'", "recovered", false,
                                "attributes", Map.of("method", "GET"))))));
        add("other-ep", root, "ENDPOINT", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "other-app/src/main/java/QuoteController.java",
                        "fact", Map.of("name", "'/quotes/{id}'", "recovered", false,
                                "attributes", Map.of("method", "GET"))))));
        add("prop-call", root, "SOURCE_HTTP_CALL_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "order-app/src/main/java/PricingClient.java",
                        "fact", Map.of("name", "\"/{id}\"", "recovered", false,
                                "attributes", Map.of("method", "GET", "propertyKey", "clients.pricing.url"))))));
        add("localhost-call", root, "SOURCE_HTTP_CALL_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "order-app/src/main/java/DirectClient.java",
                        "fact", Map.of("name", "\"http://localhost:8092/quotes/1\"", "recovered", false,
                                "attributes", Map.of("method", "GET"))))));

        HttpServiceCallProjection.project(nodes, edges, evidence, root, () -> false);
        var joins = edges.values().stream().filter(e -> e.kind().equals("polyglot.service-call.candidate")).toList();
        assertEquals(2, joins.size());
        for (var j : joins) {
            assertEquals(KnowledgeGraph.TargetState.RESOLVED, j.target().state());
            assertEquals(List.of(pricingEp), j.target().identities());
        }
    }

    @Test void ignoresTestSourceFactsInHttpCallProjection() throws Exception {
        add("prod-ep", root, "ENDPOINT", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "order-app/src/main/java/OrderController.java",
                        "fact", Map.of("name", "'/orders/{id}'", "recovered", false,
                                "attributes", Map.of("method", "GET"))))));
        add("tagged-test-call", root, "SOURCE_HTTP_CALL_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "order-app/src/main/java/TaggedTest.java",
                        "fact", Map.of("name", "'/orders/1'", "recovered", false,
                                "attributes", Map.of("method", "GET", "testSource", "true"))))));
        add("path-test-call", root, "SOURCE_HTTP_CALL_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "order-app/src/test/java/OrderContractTest.java",
                        "fact", Map.of("name", "'/orders/2'", "recovered", false,
                                "attributes", Map.of("method", "GET"))))));

        HttpServiceCallProjection.project(nodes, edges, evidence, root, () -> false);
        assertTrue(edges.isEmpty());
        assertEquals(0, nodes.values().stream().filter(n -> n.kind().equals("CAPABILITY_GAP")).count());
    }

    @Test void multiProfileFeignPlaceholderResolvesDistinctProfilesWithMayTruthRegionMetadata() throws Exception {
        add("caller-mod", root, "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "checkout-app", "directory", "checkout-app")));
        add("dev-mod", root, "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "orders-dev", "directory", "orders-dev")));
        add("prod-mod", root, "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "orders-prod", "directory", "orders-prod")));

        add("dev-prop", root, "SOURCE_CONFIG_PROPERTY", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "checkout-app/src/main/resources/application-dev.yml",
                        "fact", Map.of("nodeKind", "spring_http_property", "name", "clients.orders.url", "recovered", false,
                                "attributes", Map.of("key", "clients.orders.url",
                                        "value", "http://orders-dev:8080", "target", "orders-dev"))))));
        add("prod-prop", root, "SOURCE_CONFIG_PROPERTY", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "checkout-app/src/main/resources/application-prod.yml",
                        "fact", Map.of("nodeKind", "spring_http_property", "name", "clients.orders.url", "recovered", false,
                                "attributes", Map.of("key", "clients.orders.url",
                                        "value", "http://orders-prod:8080", "target", "orders-prod"))))));

        String devEp = add("dev-ep", root, "ENDPOINT", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "orders-dev/src/main/java/OrderController.java",
                        "fact", Map.of("name", "'/orders/{id}'", "recovered", false,
                                "attributes", Map.of("method", "GET"))))));
        String prodEp = add("prod-ep", root, "ENDPOINT", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "orders-prod/src/main/java/OrderController.java",
                        "fact", Map.of("name", "'/orders/{id}'", "recovered", false,
                                "attributes", Map.of("method", "GET"))))));

        add("feign-call", root, "SOURCE_HTTP_CALL_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "checkout-app/src/main/java/OrdersClient.java",
                        "fact", Map.of("name", "\"http://orders-default/orders/{id}\"", "recovered", false,
                                "attributes", Map.of("method", "GET", "clientKind", "FEIGN_CLIENT",
                                        "propertyKey", "clients.orders.url"))))));

        HttpServiceCallProjection.project(nodes, edges, evidence, root, () -> false);

        var joins = edges.values().stream().filter(e -> e.kind().equals("polyglot.service-call.candidate")).toList();
        assertEquals(2, joins.size(), "Multi-profile Feign client must produce separate profile-scoped candidate edges");
        assertTrue(joins.stream().anyMatch(j -> j.target().identities().equals(List.of(devEp))));
        assertTrue(joins.stream().anyMatch(j -> j.target().identities().equals(List.of(prodEp))));

        var matchEvidence = evidence.values().stream()
                .filter(e -> e.family().equals("polyglot.http-uri-match")).toList();
        assertTrue(matchEvidence.stream().anyMatch(e -> e.canonicalJson().contains("\"profile\":\"dev\"")
                && e.canonicalJson().contains("\"truthRegion\":\"MAY\"")
                && e.canonicalJson().contains("\"multiProfile\":true")));
        assertTrue(matchEvidence.stream().anyMatch(e -> e.canonicalJson().contains("\"profile\":\"prod\"")
                && e.canonicalJson().contains("\"truthRegion\":\"MAY\"")
                && e.canonicalJson().contains("\"multiProfile\":true")));
    }

    @Test void spelDynamicUriOnFeignAndGatewayRouteEmitsSpelDynamicUriCapabilityGap() throws Exception {
        add("target-ep", root, "ENDPOINT", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "orders-app/src/main/java/OrderController.java",
                        "fact", Map.of("name", "'/orders'", "recovered", false,
                                "attributes", Map.of("method", "GET"))))));
        add("spel-feign", root, "SOURCE_HTTP_CALL_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "checkout-app/src/main/java/DynamicFeign.java",
                        "fact", Map.of("name", "\"\"", "recovered", false,
                                "attributes", Map.of("method", "GET", "clientKind", "FEIGN_CLIENT",
                                        "spelExpression", "true", "spelRaw", "#{@routeResolver.ordersUrl}"))))));
        add("spel-gw", root, "SOURCE_GATEWAY_ROUTE", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", "gateway-app/src/main/java/GatewayConfig.java",
                        "fact", Map.of("name", "dynamic-route", "recovered", false,
                                "attributes", Map.of("pathPrefix", "/dyn/**", "uri", "#{@gatewayTarget.uri}",
                                        "spelExpression", "true", "spelRaw", "#{@gatewayTarget.uri}"))))));

        HttpServiceCallProjection.project(nodes, edges, evidence, root, () -> false);

        assertTrue(edges.isEmpty(), "SpEL dynamic URIs must never emit speculative service-call edges");
        var spelGaps = evidence.values().stream()
                .filter(e -> e.family().equals("evidence.gap")
                        && e.canonicalJson().contains("\"reasonCode\":\"SPEL_DYNAMIC_URI\""))
                .toList();
        assertEquals(2, spelGaps.size(), "Expected SPEL_DYNAMIC_URI gaps for both Feign and Gateway SpEL URIs");
    }

    private String add(String name,String context,String kind,GraphEvidence proof){String id=ContentDigest.sha256Utf8(name).value();evidence.put(proof.identity().value(),proof);nodes.put(context+"|"+id,new KnowledgeGraph.Node(id,context,kind,List.of(proof.identity())));return id;}
}
