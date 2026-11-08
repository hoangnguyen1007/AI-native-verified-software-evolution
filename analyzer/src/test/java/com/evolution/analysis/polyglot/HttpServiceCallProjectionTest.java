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
    private String add(String name,String context,String kind,GraphEvidence proof){String id=ContentDigest.sha256Utf8(name).value();evidence.put(proof.identity().value(),proof);nodes.put(context+"|"+id,new KnowledgeGraph.Node(id,context,kind,List.of(proof.identity())));return id;}
}
