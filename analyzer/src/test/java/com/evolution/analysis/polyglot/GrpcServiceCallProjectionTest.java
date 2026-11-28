package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.graph.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GrpcServiceCallProjectionTest {
    private final Map<String,Map<String,?>> tables=new HashMap<>();
    private final GraphProjectionStore store=new GraphProjectionStore(){@SuppressWarnings("unchecked") public <T> Map<String,T> records(String name,java.util.function.Function<Object,T> decode){return (Map<String,T>)tables.computeIfAbsent(name,ignored->new TreeMap<>());}};
    private final String root=SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:grpc"),ContentDigest.sha256Utf8("source")).value();
    @Test void ambiguousNamespacesAndContextsRemainCandidateTargetsAndReplayIsStable()throws Exception {
        schema("one","shop.v1.Cart.Get");schema("two","shop.v2.Cart.Get");
        syntax("handler",ContentDigest.sha256Utf8("server-context").value(),"SOURCE_RPC_HANDLER_CANDIDATE","Cart","Get","EXACT",false);
        syntax("caller",root,"SOURCE_RPC_CALL_CANDIDATE","Cart","GetAsync","CSHARP_ASYNC",false);
        GrpcServiceCallProjection.project(store,root,()->false);
        var edges=store.records("edges",GraphSnapshotCodec::edge);var contracts=edges.values().stream().filter(e->e.kind().equals("polyglot.grpc-contract.candidate")).toList();
        assertEquals(1,contracts.size());assertEquals(KnowledgeGraph.TargetState.CANDIDATES,contracts.getFirst().target().state());assertEquals(2,contracts.getFirst().target().identities().size());
        assertEquals(1,edges.values().stream().filter(e->e.kind().equals("polyglot.grpc-service-call.candidate")).count());
        assertTrue(store.records("nodes",GraphSnapshotCodec::node).values().stream().anyMatch(n->n.kind().equals("RPC_SOURCE_REFERENCE")));
        var expected=new TreeMap<>(edges);GrpcServiceCallProjection.project(store,root,()->false);assertEquals(expected,edges);
    }
    @Test void recoveredUnknownServicesAndWrongMethodsCannotInventServiceEdges()throws Exception {
        schema("schema","shop.Cart.Get");syntax("recovered",root,"SOURCE_RPC_CALL_CANDIDATE","Cart","Get","EXACT",true);
        syntax("wrong-method",root,"SOURCE_RPC_CALL_CANDIDATE","Cart","Other","EXACT",false);
        syntax("wrong-service",root,"SOURCE_RPC_CALL_CANDIDATE","Other","Get","EXACT",false);
        GrpcServiceCallProjection.project(store,root,()->false);assertTrue(store.records("edges",GraphSnapshotCodec::edge).isEmpty());
        assertEquals(3,store.records("nodes",GraphSnapshotCodec::node).values().stream().filter(n->n.kind().equals("CAPABILITY_GAP")).count());
        assertThrows(java.io.IOException.class,()->GrpcServiceCallProjection.project(store,root,()->true));
    }
    @Test void excludesTestCallersTestHandlersAndSameServiceComponentSelfLoops()throws Exception {
        schema("s1","demo.CatalogService.GetProduct");
        addWithPath("prod-handler",root,"SOURCE_RPC_HANDLER_CANDIDATE","src/catalogservice/handler.go",Map.of("service","CatalogService","method","GetProduct","methodStyle","EXACT","serviceQualification","SIMPLE_NAME"),false);
        addWithPath("mock-handler",root,"SOURCE_RPC_HANDLER_CANDIDATE","src/catalogservice/mock_server_test.go",Map.of("service","CatalogService","method","GetProduct","methodStyle","EXACT","serviceQualification","SIMPLE_NAME"),false);
        addWithPath("same-svc-call",root,"SOURCE_RPC_CALL_CANDIDATE","src/catalogservice/helper.go",Map.of("service","CatalogService","method","GetProduct","methodStyle","EXACT","serviceQualification","SIMPLE_NAME"),false);
        addWithPath("test-caller",root,"SOURCE_RPC_CALL_CANDIDATE","src/frontend/client_test.go",Map.of("service","CatalogService","method","GetProduct","methodStyle","EXACT","serviceQualification","SIMPLE_NAME"),false);
        addWithPath("cross-caller",root,"SOURCE_RPC_CALL_CANDIDATE","src/frontend/rpc.go",Map.of("service","CatalogService","method","GetProduct","methodStyle","EXACT","serviceQualification","SIMPLE_NAME"),false);
        GrpcServiceCallProjection.project(store,root,()->false);
        var serviceCalls=store.records("edges",GraphSnapshotCodec::edge).values().stream().filter(e->e.kind().equals("polyglot.grpc-service-call.candidate")).toList();
        assertEquals(1,serviceCalls.size(),"Only cross-service non-test caller to non-test handler produces an RPC call edge");
        assertEquals(ContentDigest.sha256Utf8("cross-caller").value(),serviceCalls.getFirst().source());
        assertEquals(List.of(ContentDigest.sha256Utf8("prod-handler").value()),serviceCalls.getFirst().target().identities());
    }
    private void schema(String id,String name){add(id,root,"PROTOBUF_RPC_METHOD",Map.of("qualifiedName",name),false);}
    private void syntax(String id,String context,String kind,String service,String method,String style,boolean recovered){add(id,context,kind,Map.of("service",service,"method",method,"methodStyle",style,"serviceQualification","SIMPLE_NAME"),recovered);}
    private void add(String id,String context,String kind,Map<String,String> attributes,boolean recovered) {
        var proof=GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of("fact",Map.of("attributes",attributes,"recovered",recovered))));
        store.records("evidence",GraphSnapshotCodec::evidence).put(proof.identity().value(),proof);String identity=ContentDigest.sha256Utf8(id).value();
        store.records("nodes",GraphSnapshotCodec::node).put(context+"|"+identity,new KnowledgeGraph.Node(identity,context,kind,List.of(proof.identity())));
    }
    private void addWithPath(String id,String context,String kind,String path,Map<String,String> attributes,boolean recovered) {
        String encoded=Base64.getEncoder().encodeToString(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var proof=GraphEvidence.capture("polyglot.source-syntax",Map.of("fields",Map.of("path",encoded,"fact",Map.of("attributes",attributes,"recovered",recovered))));
        store.records("evidence",GraphSnapshotCodec::evidence).put(proof.identity().value(),proof);String identity=ContentDigest.sha256Utf8(id).value();
        store.records("nodes",GraphSnapshotCodec::node).put(context+"|"+identity,new KnowledgeGraph.Node(identity,context,kind,List.of(proof.identity())));
    }
}
