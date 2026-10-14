package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.ContentDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Immutable snapshots shared safely across calls; save is idempotent and cancellation precedes publish. */
public final class InMemoryGraphStore implements GraphStore {
    private final Map<ContentDigest,KnowledgeGraph> snapshots=new HashMap<>();
    @Override public synchronized void save(KnowledgeGraph graph,BooleanSupplier cancelled) {
        Objects.requireNonNull(graph);Objects.requireNonNull(cancelled);
        if(cancelled.getAsBoolean())throw new GraphStoreException(GraphStoreException.Reason.CANCELLED);
        var old=snapshots.get(graph.identity());
        if(old!=null&&!new GraphSnapshotCodec().encode(old).equals(new GraphSnapshotCodec().encode(graph)))
            throw new GraphStoreException(GraphStoreException.Reason.CORRUPT_SNAPSHOT);
        snapshots.putIfAbsent(graph.identity(),graph);
    }
    @Override public synchronized Optional<KnowledgeGraph> load(ContentDigest identity) {return Optional.ofNullable(snapshots.get(Objects.requireNonNull(identity)));}
}
