package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import java.util.function.Function;

/** Immutable graph with context-qualified indexes and explicit unresolved targets. */
public final class KnowledgeGraph {
    public static final String SCHEMA="knowledge-graph:m5.1-v1";
    public static com.evolution.analysis.contract.analysis.ManifestComponent schemaComponent() {
        String definition=CanonicalJson.write(Map.of("schema",SCHEMA,"format",GraphSnapshotCodec.FORMAT,
                "fields",List.of("snapshot","contexts","nodes","edges","evidence"),
                "nodeFields",List.of("identity","context","kind","evidence"),
                "edgeFields",List.of("identity","context","kind","source","target","evidence","region"),
                "targetStates",List.of(TargetState.values()),"projectionPolicy","projection:m5.1-v1",
                "evidencePolicy","graph.evidence:m5.1-v1"));
        return new com.evolution.analysis.contract.analysis.ManifestComponent(new VersionedIdentifier("graph.canonical","m5.1-v1"),ContentDigest.sha256Utf8(definition));
    }
    public enum TargetState { RESOLVED, CANDIDATES, UNRESOLVED }
    public record Target(TargetState state,List<String> identities,Optional<String> unresolvedReference) {
        public Target {
            Objects.requireNonNull(state); identities=sorted(identities,Function.identity());Objects.requireNonNull(unresolvedReference);
            identities.forEach(KnowledgeGraph::requireIdentity);
            if(state==TargetState.RESOLVED && identities.size()!=1 || state==TargetState.CANDIDATES && identities.size()<2
                    || state==TargetState.UNRESOLVED && (!identities.isEmpty()||unresolvedReference.isEmpty())
                    || state!=TargetState.UNRESOLVED && unresolvedReference.isPresent())
                throw new IllegalArgumentException("Invalid graph target state");
        }
    }
    public record Context(String identity, SnapshotIdentity snapshot, Optional<AnalysisIdentity> analysis, ContentDigest evidence) {
        public Context { requireIdentity(identity);Objects.requireNonNull(snapshot);Objects.requireNonNull(analysis);Objects.requireNonNull(evidence); }
    }
    public record Node(String identity,String context,String kind,List<ContentDigest> evidence) {
        public Node {requireIdentity(identity);requireIdentity(context);kind=ContractChecks.token(kind,"node kind");evidence=sorted(evidence,ContentDigest::value);if(evidence.isEmpty())throw new IllegalArgumentException("Node needs evidence");}
    }
    public record Edge(String identity,String context,String kind,String source,Target target,
                       List<ContentDigest> evidence,Optional<String> region) {
        public Edge {requireIdentity(identity);requireIdentity(context);kind=ContractChecks.token(kind,"edge kind");requireIdentity(source);Objects.requireNonNull(target);
            evidence=sorted(evidence,ContentDigest::value);if(evidence.isEmpty())throw new IllegalArgumentException("Edge needs evidence");Objects.requireNonNull(region);region.ifPresent(KnowledgeGraph::requireIdentity);}
    }
    private final SnapshotIdentity snapshot;
    private final List<Context> contexts;
    private final List<Node> nodes;
    private final List<Edge> edges;
    private final List<GraphEvidence> evidence;
    private final ContentDigest identity;
    private final Map<String,List<Edge>> outgoing,incoming;
    private final Map<ContentDigest,GraphEvidence> evidenceIndex;
    public KnowledgeGraph(SnapshotIdentity snapshot,List<Context> contexts,List<Node> nodes,List<Edge> edges,List<GraphEvidence> evidence) {
        this.snapshot=Objects.requireNonNull(snapshot);this.contexts=sorted(contexts,Context::identity);
        this.nodes=sorted(nodes,n->key(n.context(),n.identity()));this.edges=sorted(edges,e->key(e.context(),e.identity()));
        this.evidence=sorted(evidence,e->e.identity().value());
        var indexedEvidence=new HashMap<ContentDigest,GraphEvidence>();this.evidence.forEach(e->indexedEvidence.put(e.identity(),e));
        this.evidenceIndex=Map.copyOf(indexedEvidence);var payloads=evidenceIndex.keySet();
        var contextIds=new HashSet<String>();this.contexts.forEach(c->{
            if(!c.snapshot().equals(snapshot))throw new IllegalArgumentException("Foreign graph snapshot");
            contextIds.add(c.identity());checkEvidence(payloads,List.of(c.evidence()));
        });
        var nodeIds=new HashSet<String>();this.nodes.forEach(n->{
            if(!contextIds.contains(n.context()))throw new IllegalArgumentException("Foreign graph node context");
            nodeIds.add(key(n.context(),n.identity()));checkEvidence(payloads,n.evidence());
        });
        var out=new TreeMap<String,List<Edge>>();var in=new TreeMap<String,List<Edge>>();
        this.edges.forEach(e->{
            if(!contextIds.contains(e.context()) || !nodeIds.contains(key(e.context(),e.source())))throw new IllegalArgumentException("Dangling graph source/context");
            for(String target:e.target().identities())if(!nodeIds.contains(key(e.context(),target)))throw new IllegalArgumentException("Dangling graph target");
            e.region().ifPresent(r->{if(!nodeIds.contains(key(e.context(),r)))throw new IllegalArgumentException("Dangling graph region");});
            checkEvidence(payloads,e.evidence());out.computeIfAbsent(key(e.context(),e.source()),k->new ArrayList<>()).add(e);
            e.target().identities().forEach(t->in.computeIfAbsent(key(e.context(),t),k->new ArrayList<>()).add(e));
        });
        this.outgoing=freeze(out);this.incoming=freeze(in);this.identity=CanonicalJson.sha256(canonicalForm());
    }
    public SnapshotIdentity snapshot() {return snapshot;}
    public List<Context> contexts() {return contexts;}
    public List<Node> nodes() {return nodes;}
    public List<Edge> edges() {return edges;}
    public List<GraphEvidence> evidence() {return evidence;}
    public ContentDigest identity() {return identity;}
    public GraphManifest manifest() {return new GraphManifest(SCHEMA,identity,semanticIdentity(),snapshot,contexts,nodes.size(),edges.size(),evidence.size());}
    /** Content identity above preserves every byte; this identity is for semantic replay across telemetry changes. */
    public ContentDigest semanticIdentity() {
        var semantic=new HashMap<ContentDigest,ContentDigest>();evidence.forEach(e->semantic.put(e.identity(),e.semanticIdentity()));
        var contextValues=contexts.stream().map(c->List.of(c.identity(),c.snapshot(),c.analysis(),semantic.get(c.evidence()))).toList();
        var nodeValues=nodes.stream().map(n->List.of(n.identity(),n.context(),n.kind(),n.evidence().stream().map(semantic::get).distinct().sorted().toList())).toList();
        var edgeValues=edges.stream().map(e->List.of(e.identity(),e.context(),e.kind(),e.source(),e.target(),e.evidence().stream().map(semantic::get).distinct().sorted().toList(),e.region())).toList();
        return CanonicalJson.sha256(List.of(SCHEMA,"semantic-replay:v1",snapshot,contextValues,nodeValues,edgeValues,semantic.values().stream().distinct().sorted().toList()));
    }
    public List<String> entityIdentities() {return nodes.stream().filter(n->n.identity().startsWith("entity:")).map(Node::identity).distinct().toList();}
    public List<Edge> outgoing(String context,String node) {return outgoing.getOrDefault(key(context,node),List.of());}
    public List<Edge> incoming(String context,String node) {return incoming.getOrDefault(key(context,node),List.of());}
    public Optional<GraphEvidence> evidence(ContentDigest digest) {return Optional.ofNullable(evidenceIndex.get(Objects.requireNonNull(digest)));}
    public Object canonicalForm() {return Map.of("schema",SCHEMA,"snapshot",snapshot,"contexts",contexts,"nodes",nodes,"edges",edges,"evidence",evidence);}
    public static String derivedIdentity(String kind,Object inputs) {
        ContractChecks.token(kind,"graph identity kind");return kind+":"+ContentDigest.sha256Utf8(CanonicalJson.write(List.of(SCHEMA,kind,inputs))).value();
    }
    static String key(String context,String identity) {return context+"|"+identity;}
    static void requireIdentity(String identity) {
        if(identity==null || !identity.matches("(?:[a-z][a-z0-9.-]*:)?sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid graph canonical identity");
    }
    private static void checkEvidence(Set<ContentDigest> available,List<ContentDigest> references) {
        if(!available.containsAll(references))throw new IllegalArgumentException("Missing graph evidence payload");
    }
    private static Map<String,List<Edge>> freeze(Map<String,List<Edge>> map) {
        var result=new TreeMap<String,List<Edge>>();map.forEach((k,v)->result.put(k,List.copyOf(v)));return Collections.unmodifiableMap(result);
    }
    static <T> List<T> sorted(Collection<T> values,Function<T,String> key) {
        Objects.requireNonNull(values);var sorted=new TreeMap<String,T>();
        for(T value:values) {Objects.requireNonNull(value);String id=key.apply(value);var previous=sorted.putIfAbsent(id,value);
            if(previous!=null&&!previous.equals(value))throw new IllegalArgumentException("Conflicting graph identity");}
        return List.copyOf(sorted.values());
    }
}
