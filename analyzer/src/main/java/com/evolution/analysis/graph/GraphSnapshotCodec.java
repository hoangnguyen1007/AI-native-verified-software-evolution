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
    public KnowledgeGraph decode(String json,GraphJson.Limits limits) {
        var envelope=object(read(json,limits));fields(envelope,"format","identity","graph");
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
        if(!encode(graph).equals(json))throw new IllegalArgumentException("Noncanonical or duplicated graph snapshot data");
        return graph;
    }
    private static ContentDigest digest(Object value) {return new ContentDigest(string(value));}
    private static List<ContentDigest> digests(Object value) {return array(value).stream().map(GraphSnapshotCodec::digest).toList();}
    private static Optional<String> optional(Object value) {return Optional.ofNullable(value).map(GraphJson::string);}
}
