package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.*;
import java.io.IOException;
import java.util.*;
import static com.evolution.analysis.evolution.EvolutionRows.*;

/** Bounded working view of one selected partition. The reader has authenticated the full publication. */
final class ReportPartition {
    private final EvolutionInput side;
    final KnowledgeGraph graph;
    final ArchitectureQueryService queries;
    final SpringRegionInput spring;
    private Boolean dependenciesClosed;
    private final Map<ArchitectureQuery.Scope,Map<String,String>> endpointCache=new EnumMap<>(ArchitectureQuery.Scope.class);
    private long evidenceCharacters;

    ReportPartition(EvolutionInput side, Map<String,Object> context) throws IOException {
        this.side = side;
        var id = text(context,"context");
        var limits = ArchitectureQuery.Limits.defaults();
        if (number(context.get("nodes")) > limits.maxVertices() || number(context.get("edges")) > limits.maxEdges())
            throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.RESOURCE_LIMIT);
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        var contexts = new ArrayList<KnowledgeGraph.Context>();
        var evidence = new TreeMap<ContentDigest,GraphEvidence>();
        var analysis = side.analysis(id);
        var contextEvidence = new ContentDigest(text(context,"evidence"));
        add(evidence, contextEvidence);
        contexts.add(new KnowledgeGraph.Context(id,side.header.snapshot(),analysis,contextEvidence));
        scan(cursor -> side.snapshot.nodes(id,side.run.size,cursor),number(context.get("nodes")),side.run.size,side.run.cancelled,node -> {
            if (!id.equals(node.context())) throw EvolutionInput.corrupt();
            nodes.add(node);
            for (var ref : node.evidence()) add(evidence,ref);
        });
        scan(cursor -> side.snapshot.edges(id,side.run.size,cursor),number(context.get("edges")),side.run.size,side.run.cancelled,edge -> {
            if (!id.equals(edge.context())) throw EvolutionInput.corrupt();
            edges.add(edge);
            for (var ref : edge.evidence()) add(evidence,ref);
        });
        var ancestors = new TreeSet<String>();
        ancestors.add(side.header.snapshot().value());
        analysis.ifPresent(a -> ancestors.add(a.value()));
        ancestors.remove(id);
        for (var ancestor : ancestors) {
            var data = side.run.index.get("context-id",side.name+"|"+ancestor);
            if (data.isEmpty()) continue;
            var ref = new ContentDigest(text(data.orElseThrow(),"evidence"));
            add(evidence,ref);
            contexts.add(new KnowledgeGraph.Context(ancestor,side.header.snapshot(),side.analysis(ancestor),ref));
            scan(cursor -> side.snapshot.nodes(ancestor,side.run.size,cursor),number(data.orElseThrow().get("nodes")),
                    side.run.size,side.run.cancelled,node -> {
                if (Set.of("CAPABILITY_GAP","PROVIDER_CONFLICT").contains(node.kind())) {
                    if (nodes.size() >= limits.maxVertices()) throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.RESOURCE_LIMIT);
                    nodes.add(node);
                    for (var proof : node.evidence()) add(evidence,proof);
                }
            });
        }
        if ("spring.context".equals(context.get("family"))) {
            spring = new SpringRegionInput(side,context,SpringEvolution.LIMITS);
            if (spring.encoding.equals("symbolic")) add(evidence,new ContentDigest(text(spring.result,"evidence")));
        } else spring = null;
        graph = new KnowledgeGraph(side.header.snapshot(),contexts,nodes,edges,List.copyOf(evidence.values()));
        queries = ArchitectureQueryService.replayPartition(graph,side.graph,side.run.cancelled);
    }

    boolean sameClosedDomain(ReportPartition other) {
        if(spring==null || other.spring==null) return spring==other.spring;
        var a=spring;var b=other.spring;
        if(!a.complete || !b.complete || !a.domain.alignable(b.domain) || !a.domain.domains.equals(b.domain.domains))return false;
        if(a.encoding.equals("explicit") && b.encoding.equals("explicit"))
            return a.worlds.keySet().equals(b.worlds.keySet()) && a.worlds.entrySet().stream().allMatch(e ->
                    e.getValue().feasibility()!=com.evolution.analysis.spring.condition.LogicalValue.UNKNOWN &&
                            e.getValue().feasibility()==b.worlds.get(e.getKey()).feasibility());
        return a.side.graph.equals(b.side.graph) && a.context.equals(b.context);
    }

    private void add(Map<ContentDigest,GraphEvidence> evidence, ContentDigest ref) throws IOException {
        if (evidence.containsKey(ref)) return;
        var payload=side.evidence(ref);
        evidenceCharacters+=payload.canonicalJson().length();
        if (evidenceCharacters>32_000_000) throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.RESOURCE_LIMIT);
        evidence.put(ref,payload);
    }

    boolean dependenciesClosed(Map<String,Object> context) throws IOException {
        if (dependenciesClosed!=null) return dependenciesClosed;
        var coverage=side.coverage(context,Set.of("graph.depends-on"));
        if (!coverage.closed()) return dependenciesClosed=false;
        var id=text(context,"context");
        var declarations=new HashMap<String,Map<String,Object>>();
        var owners=new HashMap<String,String>();
        for(var node:graph.nodes(id)) for(var ref:node.evidence()) {
            EvolutionSnapshot.check(side.run.cancelled);
            var proof=graph.evidence(ref).orElseThrow();
            if (!proof.family().equals("java.declaration") || !side.frontendMember(id,"declarations",ref)) continue;
            var raw=EvolutionInput.payload(proof);
            var entity=obj(raw.get("entity"));
            if (!node.identity().equals(entity.get("identity"))) continue;
            if (!"RESOLVED".equals(raw.get("status"))) return dependenciesClosed=false;
            var key=ownerKey(entity,text(entity,"canonicalName"));
            if (declarations.putIfAbsent(node.identity(),entity)!=null || owners.putIfAbsent(key,node.identity())!=null)
                return dependenciesClosed=false;
        }
        var rawMembers=new HashSet<String>();
        var expected=new TreeMap<String,Set<ContentDigest>>();
        var projected=new TreeMap<String,Set<ContentDigest>>();
        for(var edge:graph.edges(id)) {
            EvolutionSnapshot.check(side.run.cancelled);
            if (edge.kind().equals("graph.depends-on")) {
                if (edge.region().isPresent() || edge.target().state()!=KnowledgeGraph.TargetState.RESOLVED
                        || !edge.identity().equals(dependency(edge.source(),edge.target().identities().getFirst()))
                        || projected.putIfAbsent(edge.identity(),Set.copyOf(edge.evidence()))!=null) return dependenciesClosed=false;
                continue;
            }
            if (!edge.kind().startsWith("java.")) continue;
            for(var ref:edge.evidence()) {
                var proof=graph.evidence(ref).orElseThrow();
                var family=switch(proof.family()) {
                    case "java.occurrence" -> "occurrences";
                    case "java.derived-relationship" -> "derivedRelationships";
                    default -> null;
                };
                if (family==null || !side.frontendMember(id,family,ref)) continue;
                var raw=EvolutionInput.payload(proof);var relation=obj(raw.get("relationship"));
                if (!edge.source().equals(relation.get("source")) || !edge.identity().equals(relation.get("identity"))
                        || !edge.kind().equals(EvolutionInput.category(relation.get("kind")))) return dependenciesClosed=false;
                rawMembers.add(ref.value());
                if (Set.of("java.declares","java.has-parameter").contains(edge.kind())) continue;
                if (!"RESOLVED".equals(raw.get("status")) || edge.target().state()!=KnowledgeGraph.TargetState.RESOLVED
                        || edge.region().isPresent() || !edge.target().identities().getFirst().equals(obj(relation.get("target")).get("target")))
                    return dependenciesClosed=false;
                var source=typeOwner(declarations,owners,edge.source());
                var target=typeOwner(declarations,owners,edge.target().identities().getFirst());
                if (source==null || target==null) return dependenciesClosed=false;
                if (!source.equals(target)) expected.computeIfAbsent(dependency(source,target),ignored->new TreeSet<>()).add(ref);
            }
        }
        var frontend=side.run.index.get("frontends",side.name+"|"+id).orElse(Map.of());
        for(var family:List.of("occurrences","derivedRelationships")) {
            var prefix=side.name+"|"+text(frontend,"evidence")+"|"+family+"|";
            var rows=new Rows(side.run.index,"frontend-members",prefix);
            for(var item=rows.next();item!=null;item=rows.next())
                if(!rawMembers.contains(item.key().substring(prefix.length()))) return dependenciesClosed=false;
        }
        return dependenciesClosed=expected.equals(projected);
    }

    private static String dependency(String source,String target) {
        return KnowledgeGraph.derivedIdentity("graph-relation",List.of("projection:m5.1-v1","graph.depends-on",source,target));
    }

    private static String ownerKey(Map<String,Object> entity,String name) {return text(entity,"stableScope")+"|"+name;}

    private static String typeOwner(Map<String,Map<String,Object>> declarations,Map<String,String> owners,String id) {
        var current=declarations.get(id);
        for(int depth=0;current!=null&&depth<64;depth++) {
            if ("TYPE".equals(current.get("kind"))) return text(current,"identity");
            var name=text(current,"canonicalName");
            if(!name.startsWith("java:v1:")) return null;
            var parts=list(GraphJson.read(name.substring(8),GraphJson.Limits.defaults()));
            if(parts.size()<2 || !(parts.get(1) instanceof List<?>)) return null;
            var owner=owners.get(ownerKey(current,"java:v1:"+com.evolution.analysis.contract.serialization.CanonicalJson.write(parts.get(1))));
            current=declarations.get(owner);
        }
        return null;
    }

    Map<String,String> endpoints(ArchitectureQuery.Scope scope) throws IOException {
        if (spring != null) return spring.policyEndpoints;
        var cached=endpointCache.get(scope);
        if(cached!=null)return cached;
        var keys = new HashMap<String,String>();
        var verifiedTypes = new HashSet<String>();
        for (var node : graph.nodes()) {
            if (!node.kind().equals("TYPE")) continue;
            for (var ref : node.evidence()) {
                var evidence = graph.evidence(ref).orElseThrow();
                if (!evidence.family().equals("java.declaration") || !side.frontendMember(node.context(),"declarations",ref)) continue;
                var raw = EvolutionInput.payload(evidence);
                var entity = obj(raw.get("entity"));
                var origin = com.evolution.analysis.contract.semantic.EntityOrigin.valueOf(text(entity,"origin"));
                if (!"RESOLVED".equals(raw.get("status")) || origin == com.evolution.analysis.contract.semantic.EntityOrigin.SYNTHETIC) continue;
                var identity = com.evolution.analysis.contract.identity.EntityIdentity.from(origin,
                        new com.evolution.analysis.contract.identity.EntityScope(text(entity,"stableScope")),
                        com.evolution.analysis.contract.semantic.EntityKind.TYPE,text(entity,"canonicalName"));
                if (!node.identity().equals(identity.value()) || !node.identity().equals(entity.get("identity"))) throw EvolutionInput.corrupt();
                if (entity.get("declaration") instanceof Map<?,?> span) {
                    var sources = SpringOperands.sourceSupport(side,text(obj(span),"document"),"source.document",null);
                    if (sources.size() != 1 || text(EvolutionInput.payload(sources.getFirst()),"classification").startsWith("GENERATED")) continue;
                } else if (origin == com.evolution.analysis.contract.semantic.EntityOrigin.PROJECT) continue;
                verifiedTypes.add(node.identity());
            }
        }
        if (scope == ArchitectureQuery.Scope.TYPE) {
            verifiedTypes.forEach(id -> keys.put(id,id));
            endpointCache.put(scope,Map.copyOf(keys));return endpointCache.get(scope);
        }
        var ownedPackages = new HashSet<String>();
        for (var edge : graph.edges()) if (edge.kind().equals("graph.contains")
                && edge.target().state() == KnowledgeGraph.TargetState.RESOLVED
                && verifiedTypes.contains(edge.target().identities().getFirst())) {
            for (var ref : edge.evidence()) {
                var declaration = graph.evidence(ref).orElseThrow();
                if (!declaration.family().equals("java.declaration")) continue;
                var entity = obj(EvolutionInput.payload(declaration).get("entity"));
                if (!edge.target().identities().getFirst().equals(entity.get("identity"))) continue;
                var name = text(entity,"canonicalName");
                if (!name.startsWith("java:v1:")) continue;
                var parts = list(GraphJson.read(name.substring("java:v1:".length()),GraphJson.Limits.defaults()));
                while (parts.size() > 1 && !"type".equals(parts.getFirst()) && parts.get(1) instanceof List<?>) parts = list(parts.get(1));
                if (parts.size() != 3 || !"type".equals(parts.getFirst())) continue;
                var id = com.evolution.analysis.contract.identity.EntityIdentity.from(
                        com.evolution.analysis.contract.semantic.EntityOrigin.valueOf(text(entity,"origin")),
                        new com.evolution.analysis.contract.identity.EntityScope(text(entity,"stableScope")),
                        com.evolution.analysis.contract.semantic.EntityKind.PACKAGE,
                        com.evolution.analysis.frontend.JavaSymbolName.packageName(
                                String.join(".",list(parts.get(1)).stream().map(GraphJson::string).toList())).canonicalName());
                if (edge.source().equals(id.value())) ownedPackages.add(id.value());
            }
        }
        if (scope == ArchitectureQuery.Scope.PACKAGE) {
            ownedPackages.forEach(id -> keys.put(id,id));
        } else if (scope == ArchitectureQuery.Scope.MODULE) {
            for (var edge : graph.edges()) if (edge.kind().equals("graph.contains")
                    && edge.target().state() == KnowledgeGraph.TargetState.RESOLVED
                    && ownedPackages.contains(edge.target().identities().getFirst())) {
                for (var ref : edge.evidence()) {
                    var proof = graph.evidence(ref).orElseThrow();
                    if (!proof.family().equals("java.declaration")) continue;
                    var entity = obj(EvolutionInput.payload(proof).get("entity"));
                    if ("PROJECT".equals(entity.get("origin")) && edge.source().equals(entity.get("stableScope")))
                        keys.put(edge.source(),edge.source());
                }
            }
        }
        endpointCache.put(scope,Map.copyOf(keys));return endpointCache.get(scope);
    }
}
