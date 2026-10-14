package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.ContentDigest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryGraphStoreTest {
    @Test void immutableSnapshotsRoundTripIdempotentlyAndCancellationDoesNotPublish() {
        var f=CanonicalGraphContractTest.occurrences();var graph=new CanonicalGraphBuilder().build(f.manifest(),f.frontend());
        var store=new InMemoryGraphStore();assertFalse(store.available(graph.identity()));
        var failure=assertThrows(GraphStoreException.class,()->store.save(graph,()->true));
        assertEquals(GraphStoreException.Reason.CANCELLED,failure.reason());assertFalse(store.available(graph.identity()));
        store.save(graph);store.save(graph);assertEquals(graph.identity(),store.load(graph.identity()).orElseThrow().identity());
        assertTrue(store.load(ContentDigest.sha256Utf8("missing")).isEmpty());
        assertThrows(UnsupportedOperationException.class,()->graph.nodes().clear());
        assertThrows(UnsupportedOperationException.class,()->graph.edges().getFirst().evidence().clear());
    }
}
