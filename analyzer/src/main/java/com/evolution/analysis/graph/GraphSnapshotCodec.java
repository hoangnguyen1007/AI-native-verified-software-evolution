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
    /** Trusted immutable graph input has already passed graph/evidence invariants. */
    public void encodeTo(KnowledgeGraph graph,java.io.Writer writer,GraphJson.Limits limits)throws java.io.IOException {
        var envelope=Map.of("format",FORMAT,"identity",graph.identity(),"graph",graph.canonicalForm());
        if(!CanonicalJson.fitsStructure(envelope,limits.maxDepth(),limits.maxValues()))throw new GraphJson.LimitExceededException("Graph JSON structural limit");
        CanonicalJson.writeTo(envelope,writer);
    }
    public KnowledgeGraph decode(String json,GraphJson.Limits limits) {
        if(json.length()>limits.maxCharacters())throw new GraphJson.LimitExceededException("Graph JSON character limit");
        try{return decode(new java.io.StringReader(json),limits);}catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
    }
    public KnowledgeGraph decode(java.io.Reader reader,GraphJson.Limits limits)throws java.io.IOException {
        try{
            var digest=java.security.MessageDigest.getInstance("SHA-256");Map<String,Object> envelope;
            try(var writer=new java.io.OutputStreamWriter(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest),java.nio.charset.StandardCharsets.UTF_8)){
                var input=new java.io.FilterReader(reader){@Override public int read(char[] buffer,int offset,int length)throws java.io.IOException{int count=super.read(buffer,offset,length);if(count>0)writer.write(buffer,offset,count);return count;}};
                envelope=object(GraphJson.read(input,limits));
            }
            return decodeEnvelope(envelope,new ContentDigest("sha256:"+java.util.HexFormat.of().formatHex(digest.digest())));
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private KnowledgeGraph decodeEnvelope(Map<String,Object> envelope,ContentDigest rawDigest){
        fields(envelope,"format","identity","graph");
        if(!FORMAT.equals(string(envelope.get("format"))))throw new IllegalArgumentException("Unsupported graph snapshot version");
        var body=object(envelope.get("graph"));fields(body,"schema","snapshot","contexts","nodes","edges","evidence");
        if(!KnowledgeGraph.SCHEMA.equals(string(body.get("schema"))))throw new IllegalArgumentException("Unsupported graph schema");
        var evidence=array(body.get("evidence")).stream().map(value->{
            var row=object(value);fields(row,"identity","family","canonicalJson");
            return new GraphEvidence(digest(row.get("identity")),string(row.get("family")),string(row.get("canonicalJson")));
        }).toList();
        var contexts=array(body.get("contexts")).stream().map(value->{
            var row=object(value);fields(row,"identity","snapshot","analysis","evidence");
            return new KnowledgeGraph.Context(string(row.get("identity")),new SnapshotIdentity(string(row.get("snapshot"))),
                    optional(row.get("analysis")).map(AnalysisIdentity::new),digest(row.get("evidence")));
        }).toList();
        var nodes=array(body.get("nodes")).stream().map(value->{
            var row=object(value);fields(row,"identity","context","kind","evidence");
            return new KnowledgeGraph.Node(string(row.get("identity")),string(row.get("context")),string(row.get("kind")),digests(row.get("evidence")));
        }).toList();
        var edges=array(body.get("edges")).stream().map(value->{
            var row=object(value);fields(row,"identity","context","kind","source","target","evidence","region");
            var target=object(row.get("target"));fields(target,"state","identities","unresolvedReference");
            return new KnowledgeGraph.Edge(string(row.get("identity")),string(row.get("context")),string(row.get("kind")),string(row.get("source")),
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.valueOf(string(target.get("state"))),
                            array(target.get("identities")).stream().map(GraphJson::string).toList(),optional(target.get("unresolvedReference"))),
                    digests(row.get("evidence")),optional(row.get("region")));
        }).toList();
        var graph=new KnowledgeGraph(new SnapshotIdentity(string(body.get("snapshot"))),contexts,nodes,edges,evidence);
        if(!graph.identity().equals(digest(envelope.get("identity"))))throw new IllegalArgumentException("Graph snapshot digest mismatch");
        if(!CanonicalJson.sha256(Map.of("format",FORMAT,"identity",graph.identity(),"graph",graph.canonicalForm())).equals(rawDigest))throw new IllegalArgumentException("Noncanonical or duplicated graph snapshot data");
        return graph;
    }
    private static ContentDigest digest(Object value) {return new ContentDigest(string(value));}
    private static List<ContentDigest> digests(Object value) {return array(value).stream().map(GraphSnapshotCodec::digest).toList();}
    private static Optional<String> optional(Object value) {return Optional.ofNullable(value).map(GraphJson::string);}
}
