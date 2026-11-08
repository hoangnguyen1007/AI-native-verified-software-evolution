package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.*;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Source URI joins expose possible relationships, preserving endpoint contexts and
 * unresolved routing/activation. They cannot establish a deployed service call. */
public final class HttpServiceCallProjection {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("polyglot.http-uri-candidates","m8.4-spring-segments-v4");
    private record Endpoint(KnowledgeGraph.Node node,String path,Set<String> methods,ContentDigest proof,boolean recovered,boolean spring) {}
    private HttpServiceCallProjection() {}

    public static void project(Map<String,KnowledgeGraph.Node> nodes,Map<String,KnowledgeGraph.Edge> edges,
                               Map<String,GraphEvidence> evidence,String root,BooleanSupplier cancelled)throws IOException {
        project(nodes,edges,evidence,new TreeMap<>(),root,cancelled);
    }
    public static void project(Map<String,KnowledgeGraph.Node> nodes,Map<String,KnowledgeGraph.Edge> edges,
                               Map<String,GraphEvidence> evidence,Map<String,KnowledgeGraph.Node> additions,String root,BooleanSupplier cancelled)throws IOException {
        var providers=new TreeMap<String,List<Endpoint>>();
        // Only URI endpoint descriptors are retained, never the complete syntax population.
        for(var node:nodes.values()) {
            check(cancelled);if(!Set.of("ENDPOINT","ROUTE_CANDIDATE").contains(node.kind()))continue;
            var endpoint=descriptor(node,evidence);if(endpoint.isEmpty())continue;
            var value=endpoint.orElseThrow();providers.computeIfAbsent(bucket(value.path()),key->new ArrayList<>()).add(value);
        }
        // Delay added nodes until iteration finishes (in-memory maps also remain valid).
        for(var node:nodes.values()) {
            check(cancelled);if(!node.kind().equals("SOURCE_HTTP_CALL_CANDIDATE"))continue;
            var source=descriptor(node,evidence);if(source.isEmpty())continue;var caller=source.orElseThrow();
            String from=node.identity();
            if(!node.context().equals(root)) {
                from=KnowledgeGraph.derivedIdentity("http-call-reference",List.of(node.context(),node.identity()));
                var reference=GraphEvidence.capture("polyglot.http-call-reference",Map.of("context",node.context(),"call",node.identity(),"callEvidence",caller.proof()));
                evidence.putIfAbsent(reference.identity().value(),reference);
                additions.put(root+"|"+from,new KnowledgeGraph.Node(from,root,"HTTP_CALL_REFERENCE",List.of(reference.identity(),caller.proof())));
            }
            var candidates=new TreeMap<String,Endpoint>();
            long unsupportedPatterns=0;
            var path=HttpUriPattern.candidatePath(caller.path());
            if(path.isPresent()&&!caller.recovered()) {
                var group=new ArrayList<Endpoint>(providers.getOrDefault(bucket(path.orElseThrow()),List.of()));
                if(!bucket(path.orElseThrow()).equals("*"))group.addAll(providers.getOrDefault("*",List.of()));
                else for(var entry:providers.entrySet())if(!entry.getKey().equals("*"))group.addAll(entry.getValue());
                for(var target:group) {
                    check(cancelled);if(target.recovered()||target.node().identity().equals(node.identity()))continue;
                    if(!caller.methods().isEmpty()&&!target.methods().isEmpty()&&Collections.disjoint(caller.methods(),target.methods()))continue;
                    var match=target.spring()?HttpUriPattern.springCandidateMatch(caller.path(),target.path()):HttpUriPattern.candidateMatch(caller.path(),target.path());
                    if(match==HttpUriPattern.Match.MATCH)candidates.put(target.node().context()+"|"+target.node().identity(),target);
                    else if(match==HttpUriPattern.Match.UNSUPPORTED)unsupportedPatterns++;
                }
            }
            var targets=new ArrayList<String>();var supports=new TreeSet<ContentDigest>();supports.add(caller.proof());
            for(var target:candidates.values()) {
                String id=target.node().identity();
                if(!target.node().context().equals(root)) {
                    id=KnowledgeGraph.derivedIdentity("http-endpoint-reference",List.of(target.node().context(),id));
                    var reference=GraphEvidence.capture("polyglot.http-endpoint-reference",Map.of("context",target.node().context(),"endpoint",target.node().identity(),"declarationEvidence",target.proof()));
                    evidence.putIfAbsent(reference.identity().value(),reference);
                    additions.put(root+"|"+id,new KnowledgeGraph.Node(id,root,"ENDPOINT_REFERENCE",List.of(reference.identity(),target.proof())));
                }
                targets.add(id);supports.add(target.proof());
            }
            String reason=path.isEmpty()||caller.recovered()?"HTTP_URI_UNSUPPORTED":unsupportedPatterns>0?"HTTP_ROUTE_PATTERN_UNSUPPORTED":targets.isEmpty()?"HTTP_URI_NO_CANDIDATE":"HTTP_ROUTING_EVIDENCE_MISSING";
            var proof=GraphEvidence.capture("polyglot.http-uri-match",Map.ofEntries(Map.entry("provider",PROVIDER),Map.entry("source",caller.node().identity()),Map.entry("sourceContext",caller.node().context()),
                    Map.entry("path",caller.path()),Map.entry("methods",new TreeSet<>(caller.methods())),Map.entry("candidates",List.copyOf(candidates.keySet())),Map.entry("sourceEvidence",List.copyOf(supports)),
                    Map.entry("certainty","CANDIDATE"),Map.entry("runtimeObserved",false),Map.entry("routingEstablished",false),Map.entry("unsupportedProviderPatterns",unsupportedPatterns),Map.entry("outcome",reason)));
            evidence.putIfAbsent(proof.identity().value(),proof);supports.add(proof.identity());
            if(!targets.isEmpty()) {
                var target=new KnowledgeGraph.Target(targets.size()==1?KnowledgeGraph.TargetState.RESOLVED:KnowledgeGraph.TargetState.CANDIDATES,targets,Optional.empty());
                String id=KnowledgeGraph.derivedIdentity("http-service-call-candidate",List.of(PROVIDER,root,node.identity(),target,proof.identity()));
                edges.put(root+"|"+id,new KnowledgeGraph.Edge(id,root,"polyglot.service-call.candidate",from,target,List.copyOf(supports),Optional.empty()));
            }
            var subject=new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION,proof.identity().value());
            var requirement=new EvidenceRequirement(EvidenceRequirement.Kind.CONFIGURATION,"polyglot.http-routing",List.of(subject),EvidenceRequirement.AuthorizationClass.PASSIVE,
                    List.of("Acquire exact host/base URL, context path, gateway rewrite, route predicates and activation before establishing a service-call binding."));
            var gap=CapabilityGapRecord.create(EvidenceContext.forSnapshot(new SnapshotIdentity(root)),PROVIDER,"polyglot.service-call",reason,subject,List.of(),
                    List.of(ProviderObservationReference.create(PROVIDER,"polyglot.http-uri-match",caller.proof(),proof.identity())),List.of(requirement),List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"polyglot.service-call")),List.of(),List.of(),List.of("URI matches enumerate candidates; absence is not proof that a service is unreachable."));
            var gapProof=GraphEvidence.capture("evidence.gap",gap);evidence.putIfAbsent(gapProof.identity().value(),gapProof);
            additions.put(root+"|"+gap.gapIdentity().value(),new KnowledgeGraph.Node(gap.gapIdentity().value(),root,"CAPABILITY_GAP",List.of(gapProof.identity())));
        }
        nodes.putAll(additions);
    }
    private static Optional<Endpoint> descriptor(KnowledgeGraph.Node node,Map<String,GraphEvidence> evidence) {
        for(var support:node.evidence()) {
            var proof=evidence.get(support.value());if(proof==null)continue;
            if(proof.family().equals("java.http-client-call")) {
                var call=GraphJson.object(GraphJson.object(GraphJson.read(proof.canonicalJson(),GraphJson.Limits.defaults())).get("call"));
                var methods=new TreeSet<String>();GraphJson.array(call.get("methods")).forEach(m->methods.add(GraphJson.string(m)));
                return Optional.of(new Endpoint(node,GraphJson.string(call.get("uri")),methods,support,false,false));
            }
            if(node.kind().equals("ENDPOINT")&&proof.family().equals("spring.endpoint-declaration")) {
                var route=GraphJson.object(GraphJson.object(GraphJson.read(proof.canonicalJson(),GraphJson.Limits.defaults())).get("route"));
                var methods=new TreeSet<String>();GraphJson.array(route.get("methods")).forEach(m->methods.add(GraphJson.string(m)));
                if(Boolean.TRUE.equals(route.get("implicitHead")))methods.add("HEAD");
                return Optional.of(new Endpoint(node,GraphJson.string(route.get("path")),methods,support,false,true));
            }
            if(proof.family().equals("polyglot.source-syntax")) {
                var fields=GraphJson.object(GraphJson.object(GraphJson.read(proof.canonicalJson(),GraphJson.Limits.defaults())).get("fields"));
                var fact=GraphJson.object(fields.get("fact"));String raw=GraphJson.string(fact.get("name"));
                if(raw.length()<2||raw.indexOf('\\')>=0||raw.charAt(raw.length()-1)!=raw.charAt(0)||raw.charAt(0)!='\''&&raw.charAt(0)!='"')continue;
                String method="UNKNOWN";if(fact.get("attributes") instanceof Map<?,?> attrs&&attrs.get("method") instanceof String text)method=text;
                return Optional.of(new Endpoint(node,raw.substring(1,raw.length()-1),method.equals("UNKNOWN")?Set.of():Set.of(method),support,Boolean.TRUE.equals(fact.get("recovered")),false));
            }
        }
        return Optional.empty();
    }
    private static String bucket(String path){String[] parts=path.split("/",-1);String first=parts.length>1?parts[1]:"";return first.startsWith("{")||first.startsWith(":")||first.contains("*")?"*":first;}
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("HTTP_URI_MATCH_CANCELLED");}
}
