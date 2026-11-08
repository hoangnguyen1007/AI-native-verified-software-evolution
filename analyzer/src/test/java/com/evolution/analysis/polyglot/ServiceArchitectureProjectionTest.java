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
}
