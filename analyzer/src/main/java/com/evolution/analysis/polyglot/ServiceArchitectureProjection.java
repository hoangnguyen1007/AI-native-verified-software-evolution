package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.graph.*;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Architecture edges between evidenced caller/handler declarations. Candidate
 * provenance survives this projection; an edge is not an observed runtime call. */
public final class ServiceArchitectureProjection {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("architecture.service-edges","m8.4-v1");
    private record Endpoint(String context,String identity,String role) {
        Map<String,String> fields(){return Map.of("context",context,"identity",identity,"role",role);}
    }
    private ServiceArchitectureProjection() {}
    public static void project(GraphProjectionStore store,String root,BooleanSupplier cancelled)throws IOException {
        check(cancelled);
        var nodes=store.records("nodes",GraphSnapshotCodec::node);var edges=store.records("edges",GraphSnapshotCodec::edge);var evidence=store.records("evidence",GraphSnapshotCodec::evidence);
        var owners=store.records("service-declaration-index",GraphJson::object);
        var newNodes=store.records("service-endpoint-additions",GraphSnapshotCodec::node);var newEdges=store.records("service-edge-additions",GraphSnapshotCodec::edge);
        for(var edge:store.valuesByKind("edges",Set.of("graph.has-http-client-call","syntax.http-call-candidate","syntax.rpc-call-candidate","syntax.rpc-handler-declaration"),GraphSnapshotCodec::edge,KnowledgeGraph.Edge::kind)) {
            check(cancelled);
            if(Set.of("graph.has-http-client-call","syntax.http-call-candidate","syntax.rpc-call-candidate").contains(edge.kind()))for(String target:edge.target().identities())
                owners.put(edge.context()+"|caller|"+target,Map.of("context",edge.context(),"identity",edge.source()));
            if(edge.kind().equals("syntax.rpc-handler-declaration"))for(String target:edge.target().identities())owners.put(edge.context()+"|handler|"+edge.source(),Map.of("context",edge.context(),"identity",target));
        }
        for(var candidate:store.valuesByKind("edges",Set.of("polyglot.service-call.candidate","polyglot.grpc-service-call.candidate"),GraphSnapshotCodec::edge,KnowledgeGraph.Edge::kind)) {
            check(cancelled);String kind=switch(candidate.kind()){case "polyglot.service-call.candidate"->"CALLS_SERVICE";case "polyglot.grpc-service-call.candidate"->"INVOKES_RPC";default->"";};if(kind.isEmpty())continue;
            var source=endpoint(nodes.get(candidate.context()+"|"+candidate.source()),true,nodes,evidence,owners);var destinations=new TreeMap<String,Endpoint>();
            for(String target:candidate.target().identities()) {
                var destination=endpoint(nodes.get(candidate.context()+"|"+target),false,nodes,evidence,owners);destinations.put(destination.context()+"|"+destination.identity(),destination);
            }
            if(destinations.isEmpty())continue;
            String from=rooted(source,root,nodes,newNodes,evidence);var targets=new TreeSet<String>();for(var destination:destinations.values())targets.add(rooted(destination,root,nodes,newNodes,evidence));
            var proof=GraphEvidence.capture("architecture.service-call",Map.ofEntries(Map.entry("provider",PROVIDER),Map.entry("kind",kind),Map.entry("source",source.fields()),Map.entry("destinations",destinations.values().stream().map(Endpoint::fields).toList()),Map.entry("candidateEdge",candidate.identity()),
                    Map.entry("support",candidate.evidence()),Map.entry("certainty","CANDIDATE"),Map.entry("routingEstablished",false),Map.entry("runtimeObserved",false)));
            evidence.putIfAbsent(proof.identity().value(),proof);var support=new TreeSet<>(candidate.evidence());support.add(proof.identity());
            var target=new KnowledgeGraph.Target(targets.size()==1?KnowledgeGraph.TargetState.RESOLVED:KnowledgeGraph.TargetState.CANDIDATES,List.copyOf(targets),Optional.empty());
            String id=KnowledgeGraph.derivedIdentity("architecture-service-edge",List.of(PROVIDER,root,kind,from,target,proof.identity()));
            newEdges.put(root+"|"+id,new KnowledgeGraph.Edge(id,root,kind,from,target,List.copyOf(support),Optional.empty()));
        }
        nodes.putAll(newNodes);edges.putAll(newEdges);
    }
    private static Endpoint endpoint(KnowledgeGraph.Node node,boolean caller,Map<String,KnowledgeGraph.Node> nodes,Map<String,GraphEvidence> evidence,Map<String,Map<String,Object>> owners) {
        if(node==null)throw new IllegalArgumentException("SERVICE_EDGE_ENDPOINT_MISSING");
        String context=node.context(),identity=node.identity();
        for(var id:node.evidence()) {
            var proof=evidence.get(id.value());if(proof==null||!Set.of("java.http-client-call","polyglot.http-call-reference","polyglot.http-endpoint-reference","polyglot.grpc-source-reference").contains(proof.family()))continue;
            var raw=GraphJson.object(GraphJson.read(proof.canonicalJson(),GraphJson.Limits.defaults()));
            if(proof.family().equals("java.http-client-call")&&caller) {
                var call=GraphJson.object(raw.get("call"));String scope=raw.get("analysis") instanceof String value?value:context;
                if(call.get("caller") instanceof String owner&&nodes.containsKey(scope+"|"+owner))return new Endpoint(scope,owner,"CALLER_DECLARATION");
            }
            if(proof.family().equals("polyglot.http-call-reference")){context=GraphJson.string(raw.get("context"));identity=GraphJson.string(raw.get("call"));}
            if(proof.family().equals("polyglot.http-endpoint-reference")){context=GraphJson.string(raw.get("context"));identity=GraphJson.string(raw.get("endpoint"));}
            if(proof.family().equals("polyglot.grpc-source-reference")){context=GraphJson.string(raw.get("context"));identity=GraphJson.string(raw.get("source"));}
        }
        var original=nodes.get(context+"|"+identity);
        if(original!=null&&!original.equals(node))return endpoint(original,caller,nodes,evidence,owners);
        if(!caller)for(var id:node.evidence()) {
            var proof=evidence.get(id.value());if(proof!=null&&proof.family().equals("spring.endpoint-declaration")) {
                var raw=GraphJson.object(GraphJson.read(proof.canonicalJson(),GraphJson.Limits.defaults()));var route=GraphJson.object(raw.get("route"));
                if(route.get("handler") instanceof String handler&&nodes.containsKey(context+"|"+handler))return new Endpoint(context,handler,"HANDLER_DECLARATION");
            }
        }
        var declaration=owners.get(context+"|"+(caller?"caller":"handler")+"|"+identity);
        if(declaration!=null)return new Endpoint(GraphJson.string(declaration.get("context")),GraphJson.string(declaration.get("identity")),caller?"CALLER_DECLARATION":"HANDLER_DECLARATION");
        return new Endpoint(context,identity,caller?"CALL_SITE":"ENDPOINT_SITE");
    }
    private static String rooted(Endpoint endpoint,String root,Map<String,KnowledgeGraph.Node> nodes,Map<String,KnowledgeGraph.Node> additions,Map<String,GraphEvidence> evidence) {
        if(endpoint.context().equals(root))return endpoint.identity();
        var original=nodes.get(endpoint.context()+"|"+endpoint.identity());if(original==null)throw new IllegalArgumentException("SERVICE_DECLARATION_MISSING");
        var proof=GraphEvidence.capture("architecture.service-endpoint-reference",Map.of("sourceContext",endpoint.context(),"sourceIdentity",endpoint.identity(),"sourceKind",original.kind(),"role",endpoint.role(),"sourceEvidence",original.evidence()));
        evidence.putIfAbsent(proof.identity().value(),proof);String id=KnowledgeGraph.derivedIdentity("service-endpoint-reference",List.of(endpoint.context(),endpoint.identity()));
        var support=new TreeSet<>(original.evidence());var previous=additions.get(root+"|"+id);if(previous==null)previous=nodes.get(root+"|"+id);if(previous!=null)support.addAll(previous.evidence());
        support.add(proof.identity());additions.put(root+"|"+id,new KnowledgeGraph.Node(id,root,"SERVICE_ENDPOINT_REFERENCE",List.copyOf(support)));return id;
    }
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SERVICE_EDGE_PROJECTION_CANCELLED");}
}
