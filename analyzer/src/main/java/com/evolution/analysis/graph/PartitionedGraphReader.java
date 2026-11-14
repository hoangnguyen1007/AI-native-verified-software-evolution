package com.evolution.analysis.graph;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.IOException;
import java.util.*;

/** Additive storage migration port. Implementations must page directly from segments/indexes;
 * materializing a v1 KnowledgeGraph is an explicitly bounded compatibility operation, never a default method.
 * Cross-context relation semantics require their separately versioned envelope before publication. */
public interface PartitionedGraphReader {
    String SCHEMA="graph.partition-reference:m8.4-v2";
    record Partition(String schema,ContentDigest graph,String context,ContentHandle segment,long nodes,long edges){
        public Partition{if(!SCHEMA.equals(schema)||nodes<0||edges<0)throw new IllegalArgumentException("Invalid graph partition");Objects.requireNonNull(graph);Objects.requireNonNull(context);Objects.requireNonNull(segment);}
    }
    record Page<T>(List<T> items,Optional<String> continuation){public Page{items=List.copyOf(items);Objects.requireNonNull(continuation);}}
    /** The caller can retry once the requested preparation or the shared worker is ready. */
    final class IndexPending extends IOException {
        private final boolean workerBusy;
        public IndexPending(boolean workerBusy) { this.workerBusy = workerBusy; }
        public boolean workerBusy() { return workerBusy; }
    }
    Page<Partition> partitions(ContentDigest graph,int size,Optional<String> cursor)throws IOException;
    Page<KnowledgeGraph.Node> nodes(Partition partition,int size,Optional<String> cursor)throws IOException;
    Page<KnowledgeGraph.Edge> adjacency(Partition partition,String node,boolean incoming,int size,Optional<String> cursor)throws IOException;
}
