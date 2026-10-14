package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.ContentDigest;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Storage port for complete immutable graph snapshots. No storage query owns semantics. */
public interface GraphStore {
    void save(KnowledgeGraph graph, BooleanSupplier cancelled);
    default void save(KnowledgeGraph graph) {save(graph,()->false);}
    Optional<KnowledgeGraph> load(ContentDigest identity);
    default Optional<GraphManifest> manifest(ContentDigest identity) {return load(identity).map(KnowledgeGraph::manifest);}
    default boolean available(ContentDigest identity) {return load(identity).isPresent();}
}
