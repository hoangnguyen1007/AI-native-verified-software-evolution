package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import static com.evolution.analysis.graph.GraphJson.*;

/** Versioned, complete snapshot codec. Restores typed graph values and reruns graph invariants. */
public final class GraphSnapshotCodec {
    public static final String FORMAT="graph-snapshot:m5.1-v1";
    public String encode(KnowledgeGraph graph) {
        return CanonicalJson.write(Map.of("format",FORMAT,"identity",graph.identity(),"graph",graph.canonicalForm()));
    }
    /** Producer-side streaming for already validated graphs; no whole-snapshot string or byte array. */
    public void encodeTo(KnowledgeGraph graph,java.io.Writer writer)throws java.io.IOException {
        CanonicalJson.writeTo(Map.of("format",FORMAT,"identity",graph.identity(),"graph",graph.canonicalForm()),writer);
    }
    /** Trusted immutable graph input has already passed graph/evidence invariants. */
    public void encodeTo(KnowledgeGraph graph,java.io.Writer writer,GraphJson.Limits limits)throws java.io.IOException {
        encodeTo(graph,writer,GraphJson.StreamLimits.from(limits));
    }
    public void encodeTo(KnowledgeGraph graph,java.io.Writer writer,GraphJson.StreamLimits limits)throws java.io.IOException {
        var envelope=Map.of("format",FORMAT,"identity",graph.identity(),"graph",graph.canonicalForm());
        if(!CanonicalJson.fitsStructure(envelope,limits.maxDepth(),limits.maxValues()))throw new GraphJson.LimitExceededException("Graph JSON structural limit");
        CanonicalJson.writeTo(envelope,writer);
    }
    public KnowledgeGraph decode(String json,GraphJson.Limits limits) {
        if(json.length()>limits.maxCharacters())throw new GraphJson.LimitExceededException("Graph JSON character limit");
        try{return decode(new java.io.StringReader(json),limits);}catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
    }
    public KnowledgeGraph decode(java.io.Reader reader,GraphJson.Limits limits)throws java.io.IOException {
        return decode(reader,GraphJson.StreamLimits.from(limits));
    }
    /** Decode individual typed rows, releasing their temporary JSON maps before reading the next row. */
    public KnowledgeGraph decode(java.io.Reader reader,GraphJson.StreamLimits limits)throws java.io.IOException {
        try{
            var digest=java.security.MessageDigest.getInstance("SHA-256");var decoded=new StreamDecoder();
            try(var writer=new java.io.OutputStreamWriter(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest),java.nio.charset.StandardCharsets.UTF_8)){
                var input=new java.io.FilterReader(reader){@Override public int read(char[] buffer,int offset,int length)throws java.io.IOException{int count=super.read(buffer,offset,length);if(count>0)writer.write(buffer,offset,count);return count;}};
                GraphJson.streamObject(input,limits,decoded::envelope);
            }
            return decoded.finish(new ContentDigest("sha256:"+java.util.HexFormat.of().formatHex(digest.digest())));
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static final class StreamDecoder {
        final Map<String,Object> header=new TreeMap<>(),body=new TreeMap<>();final Set<String> members=new HashSet<>();
        final List<GraphEvidence> evidence=new ArrayList<>();final List<KnowledgeGraph.Context> contexts=new ArrayList<>();
        final List<KnowledgeGraph.Node> nodes=new ArrayList<>();final List<KnowledgeGraph.Edge> edges=new ArrayList<>();
        void envelope(String name,GraphJson.MemberReader member)throws java.io.IOException {
            switch(name){case "graph"->member.object(this::graph);case "format","identity"->header.put(name,member.read());default->throw new IllegalArgumentException("Unknown graph envelope member");}
        }
        void graph(String name,GraphJson.MemberReader member)throws java.io.IOException {
            members.add(name);
            switch(name){
                case "schema","snapshot"->body.put(name,member.read());
                case "evidence"->member.array(value->{var row=object(value);fields(row,"identity","family","canonicalJson");evidence.add(new GraphEvidence(digest(row.get("identity")),string(row.get("family")),string(row.get("canonicalJson"))));});
                case "contexts"->member.array(value->{var row=object(value);fields(row,"identity","snapshot","analysis","evidence");contexts.add(new KnowledgeGraph.Context(string(row.get("identity")),new SnapshotIdentity(string(row.get("snapshot"))),optional(row.get("analysis")).map(AnalysisIdentity::new),digest(row.get("evidence"))));});
                case "nodes"->member.array(value->{var row=object(value);fields(row,"identity","context","kind","evidence");nodes.add(new KnowledgeGraph.Node(string(row.get("identity")),string(row.get("context")),string(row.get("kind")),digests(row.get("evidence"))));});
                case "edges"->member.array(value->{var row=object(value);fields(row,"identity","context","kind","source","target","evidence","region");var target=object(row.get("target"));fields(target,"state","identities","unresolvedReference");edges.add(new KnowledgeGraph.Edge(string(row.get("identity")),string(row.get("context")),string(row.get("kind")),string(row.get("source")),new KnowledgeGraph.Target(KnowledgeGraph.TargetState.valueOf(string(target.get("state"))),array(target.get("identities")).stream().map(GraphJson::string).toList(),optional(target.get("unresolvedReference"))),digests(row.get("evidence")),optional(row.get("region"))));});
                default->throw new IllegalArgumentException("Unknown graph member");
            }
        }
        KnowledgeGraph finish(ContentDigest rawDigest){
            fields(header,"format","identity");
            if(!FORMAT.equals(string(header.get("format")))||!KnowledgeGraph.SCHEMA.equals(string(body.get("schema")))
                    ||!members.equals(Set.of("schema","snapshot","contexts","nodes","edges","evidence")))throw new IllegalArgumentException("Incomplete or unsupported graph snapshot");
            var graph=new KnowledgeGraph(new SnapshotIdentity(string(body.get("snapshot"))),contexts,nodes,edges,evidence);
            if(!graph.identity().equals(digest(header.get("identity"))))throw new IllegalArgumentException("Graph snapshot digest mismatch");
            if(!CanonicalJson.sha256(Map.of("format",FORMAT,"identity",graph.identity(),"graph",graph.canonicalForm())).equals(rawDigest))throw new IllegalArgumentException("Noncanonical graph snapshot data");
            return graph;
        }
    }
    private static ContentDigest digest(Object value) {return new ContentDigest(string(value));}
    /** Shared typed row validation for paged storage adapters. */
    public static KnowledgeGraph.Context context(Object value){var row=object(value);fields(row,"identity","snapshot","analysis","evidence");return new KnowledgeGraph.Context(string(row.get("identity")),new SnapshotIdentity(string(row.get("snapshot"))),optional(row.get("analysis")).map(AnalysisIdentity::new),digest(row.get("evidence")));}
    public static KnowledgeGraph.Node node(Object value){var row=object(value);fields(row,"identity","context","kind","evidence");return new KnowledgeGraph.Node(string(row.get("identity")),string(row.get("context")),string(row.get("kind")),digests(row.get("evidence")));}
    public static KnowledgeGraph.Edge edge(Object value){var row=object(value);fields(row,"identity","context","kind","source","target","evidence","region");var target=object(row.get("target"));fields(target,"state","identities","unresolvedReference");return new KnowledgeGraph.Edge(string(row.get("identity")),string(row.get("context")),string(row.get("kind")),string(row.get("source")),new KnowledgeGraph.Target(KnowledgeGraph.TargetState.valueOf(string(target.get("state"))),array(target.get("identities")).stream().map(GraphJson::string).toList(),optional(target.get("unresolvedReference"))),digests(row.get("evidence")),optional(row.get("region")));}
    public static GraphEvidence evidence(Object value){var row=object(value);fields(row,"identity","family","canonicalJson");return new GraphEvidence(digest(row.get("identity")),string(row.get("family")),string(row.get("canonicalJson")));}
    private static List<ContentDigest> digests(Object value) {return array(value).stream().map(GraphSnapshotCodec::digest).toList();}
    private static Optional<String> optional(Object value) {return Optional.ofNullable(value).map(GraphJson::string);}
}
