package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.graph.PartitionedGraphReader.Page;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Read-only, authenticated rows from a single immutable graph. No whole-graph fallback. */
public interface EvolutionSnapshot {
    enum Failure { MISSING, CORRUPT, UNSUPPORTED, RESOURCE_LIMIT, UNAVAILABLE }

    final class ReadFailure extends IOException {
        private final Failure reason;
        public ReadFailure(Failure reason) { this(reason,null); }
        public ReadFailure(Failure reason, Throwable cause) { super(reason.name(),cause);this.reason=Objects.requireNonNull(reason); }
        public Failure reason() { return reason; }
    }

    record Header(String schema, ContentDigest graph, SnapshotIdentity snapshot, long contexts, long evidence) {
        public Header {
            Objects.requireNonNull(schema); Objects.requireNonNull(graph); Objects.requireNonNull(snapshot);
            if(contexts<0 || evidence<0)throw new IllegalArgumentException("Invalid snapshot census");
        }
    }

    record Partition(KnowledgeGraph.Context context, long nodes, long edges) {
        public Partition {
            Objects.requireNonNull(context);
            if(nodes<0 || edges<0)throw new IllegalArgumentException("Invalid partition census");
        }
    }

    @FunctionalInterface interface Reader {
        EvolutionSnapshot open(ContentDigest graph, BooleanSupplier cancelled) throws IOException;
    }

    Header header();
    Page<Partition> contexts(int size, Optional<String> cursor) throws IOException;
    Page<KnowledgeGraph.Node> nodes(String context, int size, Optional<String> cursor) throws IOException;
    Page<KnowledgeGraph.Edge> edges(String context, int size, Optional<String> cursor) throws IOException;
    Page<GraphEvidence> evidence(int size, Optional<String> cursor) throws IOException;
    Optional<GraphEvidence> evidence(ContentDigest id) throws IOException;

    /** Parity adapter for graphs already constructed and validated by the caller. */
    static Reader memory(KnowledgeGraph... graphs) {
        var index=new HashMap<ContentDigest,KnowledgeGraph>();
        for(var graph:graphs)index.put(graph.identity(),graph);
        return (identity,cancelled)->{
            var graph=index.get(identity);
            if(graph==null)throw new ReadFailure(Failure.MISSING);
            return new EvolutionSnapshot() {
                public Header header() {
                    return new Header(KnowledgeGraph.SCHEMA,identity,graph.snapshot(),graph.contexts().size(),graph.evidence().size());
                }
                public Page<Partition> contexts(int size,Optional<String> cursor) {
                    var page=page(graph.contexts(),size,cursor,cancelled);
                    return new Page<>(page.items().stream().map(c->new Partition(c,graph.nodes(c.identity()).size(),
                            graph.edges(c.identity()).size())).toList(),page.continuation());
                }
                public Page<KnowledgeGraph.Node> nodes(String context,int size,Optional<String> cursor) {
                    return page(graph.nodes(context),size,cursor,cancelled);
                }
                public Page<KnowledgeGraph.Edge> edges(String context,int size,Optional<String> cursor) {
                    return page(graph.edges(context),size,cursor,cancelled);
                }
                public Page<GraphEvidence> evidence(int size,Optional<String> cursor) {
                    return page(graph.evidence(),size,cursor,cancelled);
                }
                public Optional<GraphEvidence> evidence(ContentDigest id) { check(cancelled); return graph.evidence(id); }
            };
        };
    }

    private static <T> Page<T> page(List<T> rows,int size,Optional<String> cursor,BooleanSupplier cancelled) {
        check(cancelled);
        int from=cursor.map(Integer::parseInt).orElse(0);
        int until=from+Math.min(size,rows.size()-from);
        return new Page<>(rows.subList(from,until),until<rows.size()?Optional.of(Integer.toString(until)):Optional.empty());
    }

    static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
    }
}
