package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import java.util.*;

/** Storage metadata, not a computed architecture metric or completeness claim. */
public record GraphManifest(String schema,ContentDigest contentIdentity,ContentDigest semanticIdentity,
        SnapshotIdentity snapshot,List<KnowledgeGraph.Context> contexts,int nodes,int edges,int evidencePayloads) {
    public GraphManifest {
        if(!KnowledgeGraph.SCHEMA.equals(schema))throw new IllegalArgumentException("Unsupported graph manifest schema");
        Objects.requireNonNull(contentIdentity);Objects.requireNonNull(semanticIdentity);Objects.requireNonNull(snapshot);
        contexts=List.copyOf(contexts);if(nodes<0||edges<0||evidencePayloads<0)throw new IllegalArgumentException("Invalid graph manifest counts");
    }
}
