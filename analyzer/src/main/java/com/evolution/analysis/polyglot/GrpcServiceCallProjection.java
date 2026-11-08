package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.*;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Source-backed RPC contract/handler candidates. No generated binding or deployed
 * address is inferred from a coincident method name. */
public final class GrpcServiceCallProjection {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("polyglot.grpc-source-candidates","m8.4-v1");
    private record Row(KnowledgeGraph.Node node,ContentDigest proof,Map<String,Object> fact,Map<String,Object> attributes) {}
    private record Method(Row row,String service,String name) {}
    private GrpcServiceCallProjection() {}
    public static void project(GraphProjectionStore store,String root,BooleanSupplier cancelled)throws IOException {
        var nodes=store.records("nodes",GraphSnapshotCodec::node);var edges=store.records("edges",GraphSnapshotCodec::edge);var evidence=store.records("evidence",GraphSnapshotCodec::evidence);
        var additions=store.records("grpc-projection-additions",GraphSnapshotCodec::node);
        var methods=new TreeMap<String,List<Method>>();var handlers=new TreeMap<String,List<Row>>();
        // Retain only API descriptors; executable/source populations stream below.
        for(var node:nodes.values()) {
            check(cancelled);if(!Set.of("PROTOBUF_RPC_METHOD","SOURCE_RPC_METHOD").contains(node.kind()))continue;
            var row=source(node,evidence);if(row.isEmpty()||recovered(row.orElseThrow()))continue;var value=row.orElseThrow();
            String qualified=GraphJson.string(value.attributes().get("qualifiedName"));int dot=qualified.lastIndexOf('.');
            if(dot<0)continue;String service=qualified.substring(0,dot),name=qualified.substring(dot+1);
            var method=new Method(value,service,name);
            for(String style:List.of("EXACT","LOWER_CAMEL","CSHARP_ASYNC"))for(String spelling:spellings(name,style))methods.computeIfAbsent(simple(service)+"|"+style+"|"+spelling,ignored->new ArrayList<>()).add(method);
        }
        for(var node:nodes.values()) {
            check(cancelled);if(!node.kind().equals("SOURCE_RPC_HANDLER_CANDIDATE"))continue;
            var descriptor=source(node,evidence);if(descriptor.isEmpty()||recovered(descriptor.orElseThrow()))continue;var handler=descriptor.orElseThrow();
            for(var method:matching(handler,methods))handlers.computeIfAbsent(key(method.row()),ignored->new ArrayList<>()).add(handler);
        }
        for(var node:nodes.values()) {
            check(cancelled);if(!Set.of("SOURCE_RPC_CALL_CANDIDATE","SOURCE_RPC_HANDLER_CANDIDATE").contains(node.kind()))continue;
            var descriptor=source(node,evidence);if(descriptor.isEmpty())continue;var caller=descriptor.orElseThrow();boolean implementation=node.kind().equals("SOURCE_RPC_HANDLER_CANDIDATE");
            var contracts=recovered(caller)?List.<Method>of():matching(caller,methods);var targets=new TreeMap<String,Row>();var schemas=new TreeMap<String,Row>();
            for(var method:contracts) {
                schemas.put(key(method.row()),method.row());
                if(!implementation)for(var handler:handlers.getOrDefault(key(method.row()),List.of()))if(!handler.node().equals(node))targets.put(key(handler),handler);
            }
            var supports=new TreeSet<ContentDigest>();supports.add(caller.proof());schemas.values().forEach(r->supports.add(r.proof()));targets.values().forEach(r->supports.add(r.proof()));
            String reason=recovered(caller)?"GRPC_SOURCE_RECOVERED":schemas.isEmpty()?"GRPC_SCHEMA_NO_CANDIDATE":!implementation&&targets.isEmpty()?"GRPC_HANDLER_NO_CANDIDATE":"GRPC_ROUTING_EVIDENCE_MISSING";
            var proof=GraphEvidence.capture("polyglot.grpc-source-match",Map.ofEntries(Map.entry("provider",PROVIDER),Map.entry("source",key(caller)),Map.entry("attributes",caller.attributes()),Map.entry("schemaCandidates",List.copyOf(schemas.keySet())),Map.entry("handlerCandidates",List.copyOf(targets.keySet())),
                    Map.entry("support",supports),Map.entry("certainty","CANDIDATE"),Map.entry("generatedBindingEstablished",false),Map.entry("namespaceEstablished",false),Map.entry("routingEstablished",false),Map.entry("runtimeObserved",false),Map.entry("outcome",reason)));
            evidence.putIfAbsent(proof.identity().value(),proof);supports.add(proof.identity());
            String from=reference(caller,root,additions,evidence);
            edge(edges,root,implementation?"polyglot.grpc-implements.candidate":"polyglot.grpc-contract.candidate",from,schemas.values(),supports,additions,evidence);
            if(!implementation)edge(edges,root,"polyglot.grpc-service-call.candidate",from,targets.values(),supports,additions,evidence);
            var subject=new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION,proof.identity().value());
            var requirement=new EvidenceRequirement(EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT,"polyglot.grpc-binding-routing",List.of(subject),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,
                    List.of("Acquire exact descriptor imports, generated stub/compiler binding and address/server-registration/activation context. Simple generated-API spellings enumerate candidates across every matching namespace."));
            var gap=CapabilityGapRecord.create(EvidenceContext.forSnapshot(new SnapshotIdentity(root)),PROVIDER,"polyglot.grpc-service-call",reason,subject,List.of(),List.of(ProviderObservationReference.create(PROVIDER,"polyglot.grpc-source-match",caller.proof(),proof.identity())),List.of(requirement),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"polyglot.grpc-service-call")),List.of(),List.of(),List.of("Contract and handler candidates retain their exact source context/evidence; routing remains unestablished."));
            var gapProof=GraphEvidence.capture("evidence.gap",gap);evidence.putIfAbsent(gapProof.identity().value(),gapProof);additions.put(root+"|"+gap.gapIdentity().value(),new KnowledgeGraph.Node(gap.gapIdentity().value(),root,"CAPABILITY_GAP",List.of(gapProof.identity())));
        }
        nodes.putAll(additions);
    }
    private static void edge(Map<String,KnowledgeGraph.Edge> edges,String root,String kind,String from,Collection<Row> targets,Set<ContentDigest> supports,Map<String,KnowledgeGraph.Node> additions,Map<String,GraphEvidence> evidence) {
        if(targets.isEmpty())return;var ids=new TreeSet<String>();targets.forEach(row->ids.add(reference(row,root,additions,evidence)));
        var target=new KnowledgeGraph.Target(ids.size()==1?KnowledgeGraph.TargetState.RESOLVED:KnowledgeGraph.TargetState.CANDIDATES,List.copyOf(ids),Optional.empty());
        String id=KnowledgeGraph.derivedIdentity("grpc-source-link",List.of(PROVIDER,kind,root,from,target,supports));edges.put(root+"|"+id,new KnowledgeGraph.Edge(id,root,kind,from,target,List.copyOf(supports),Optional.empty()));
    }
    private static String reference(Row row,String root,Map<String,KnowledgeGraph.Node> additions,Map<String,GraphEvidence> evidence) {
        if(row.node().context().equals(root))return row.node().identity();
        String id=KnowledgeGraph.derivedIdentity("grpc-source-reference",List.of(row.node().context(),row.node().identity()));var proof=GraphEvidence.capture("polyglot.grpc-source-reference",Map.of("context",row.node().context(),"source",row.node().identity(),"sourceEvidence",row.proof()));
        evidence.putIfAbsent(proof.identity().value(),proof);additions.put(root+"|"+id,new KnowledgeGraph.Node(id,root,"RPC_SOURCE_REFERENCE",List.of(proof.identity(),row.proof())));return id;
    }
    private static List<Method> matching(Row row,Map<String,List<Method>> methods) {
        String service=GraphJson.string(row.attributes().get("service")),name=GraphJson.string(row.attributes().get("method"));String style=GraphJson.string(row.attributes().get("methodStyle"));
        var matches=methods.getOrDefault(simple(service)+"|"+style+"|"+name,List.of());
        if("QUALIFIED".equals(row.attributes().get("serviceQualification")))return matches.stream().filter(m->m.service().equals(service)).toList();return matches;
    }
    private static Optional<Row> source(KnowledgeGraph.Node node,Map<String,GraphEvidence> evidence) {
        for(var id:node.evidence()) {var proof=evidence.get(id.value());if(proof==null||!proof.family().equals("polyglot.source-syntax"))continue;
            var fields=GraphJson.object(GraphJson.object(GraphJson.read(proof.canonicalJson(),GraphJson.Limits.defaults())).get("fields"));var fact=GraphJson.object(fields.get("fact"));return Optional.of(new Row(node,id,fact,GraphJson.object(fact.get("attributes"))));}
        return Optional.empty();
    }
    private static Set<String> spellings(String method,String style){return style.equals("CSHARP_ASYNC")?Set.of(method,method+"Async"):style.equals("LOWER_CAMEL")?new TreeSet<>(List.of(method,Character.toLowerCase(method.charAt(0))+method.substring(1))):Set.of(method);}
    private static boolean recovered(Row row){return Boolean.TRUE.equals(row.fact().get("recovered"));}
    private static String simple(String service){return service.substring(service.lastIndexOf('.')+1);}
    private static String key(Row row){return row.node().context()+"|"+row.node().identity();}
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("GRPC_SOURCE_MATCH_CANCELLED");}
}
