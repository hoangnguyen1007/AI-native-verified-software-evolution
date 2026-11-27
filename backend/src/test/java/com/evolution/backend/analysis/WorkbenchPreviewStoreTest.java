package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkbenchPreviewStoreTest {

    private static KnowledgeGraph sampleGraph(String seed) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/" + seed + ".git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8(seed));
        var ev = GraphEvidence.capture("preliminary.context", Map.of("seed", seed, "authoritative", false));
        return new KnowledgeGraph(
                snap,
                List.of(new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), ev.identity())),
                List.of(new KnowledgeGraph.Node(repo.value(), snap.value(), "REPOSITORY", List.of(ev.identity()))),
                List.of(),
                List.of(ev));
    }

    @Test
    void enforcesNonAuthoritativeVerifyPublishedFalseAndRejectsDirectSave() {
        var store = new WorkbenchPreviewStore();
        var g = sampleGraph("job1");

        assertThrows(UnsupportedOperationException.class, () -> store.save(g));
        assertThrows(IllegalArgumentException.class, () -> store.putPreview(
                "job-1", g, AnalysisJobs.TruthTier.DEEP_SEMANTIC_COMPLETE, 1, 1, List.of()));

        var meta = store.putPreview("job-1", g, AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE, 0, 3, List.of("a", "b", "c"));
        assertEquals(g.identity(), meta.graphId());
        assertTrue(store.available(g.identity()));
        assertSame(g, store.load(g.identity()).orElseThrow());
        assertEquals(WorkbenchPreviewStore.LookupState.ACTIVE, store.lookupState(g.identity()));
        // Hard invariant: preview graphs NEVER satisfy durable canonical verifyPublished!
        assertFalse(store.verifyPublished(g.identity(), () -> false));
    }

    @Test
    void gracePeriodPreservesInFlightReadsThenTransitionsToSuperseded() {
        var now = new AtomicLong(100_000L);
        var store = new WorkbenchPreviewStore(2, 15_000L, now::get);
        var stage1 = sampleGraph("stage1");
        var stage2 = sampleGraph("stage2");
        var finalAuthoritativeId = ContentDigest.sha256Utf8("final-authoritative-graph");

        store.putPreview("job-1", stage1, AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE, 0, 2, List.of("core", "api"));
        // Stage 2 replaces Stage 1 for same job -> Stage 1 immediately becomes SUPERSEDED -> stage2
        store.putPreview("job-1", stage2, AnalysisJobs.TruthTier.PARTIAL_CONTEXTS, 1, 2, List.of("api"));
        assertEquals(WorkbenchPreviewStore.LookupState.SUPERSEDED, store.lookupState(stage1.identity()));
        assertEquals(Optional.of(stage2.identity()), store.supersededBy(stage1.identity()));
        assertTrue(store.load(stage1.identity()).isEmpty());
        assertTrue(store.load(stage2.identity()).isPresent());

        // Job finishes -> Stage 2 stays readable during 15s grace period
        store.markJobFinished("job-1", Optional.of(finalAuthoritativeId));
        now.addAndGet(10_000L);
        assertEquals(WorkbenchPreviewStore.LookupState.ACTIVE, store.lookupState(stage2.identity()));
        assertTrue(store.load(stage2.identity()).isPresent());
        assertEquals(Optional.of(finalAuthoritativeId), store.supersededBy(stage2.identity()));

        // Grace period elapses -> Stage 2 evicted and reports SUPERSEDED with authoritative replacement
        now.addAndGet(5_000L);
        assertEquals(WorkbenchPreviewStore.LookupState.SUPERSEDED, store.lookupState(stage2.identity()));
        assertTrue(store.load(stage2.identity()).isEmpty());
        assertEquals(Optional.of(finalAuthoritativeId), store.supersededBy(stage2.identity()));
        assertEquals(0, store.activePreviewCount());
    }

    @Test
    void capacityBoundEvictsOldestJobPreview() {
        var store = new WorkbenchPreviewStore(2, 15_000L);
        var g1 = sampleGraph("j1");
        var g2 = sampleGraph("j2");
        var g3 = sampleGraph("j3");

        store.putPreview("j1", g1, AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE, 0, 1, List.of("m1"));
        store.putPreview("j2", g2, AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE, 0, 1, List.of("m2"));
        assertEquals(2, store.activePreviewCount());

        store.putPreview("j3", g3, AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE, 0, 1, List.of("m3"));
        assertEquals(2, store.activePreviewCount());
        assertEquals(WorkbenchPreviewStore.LookupState.SUPERSEDED, store.lookupState(g1.identity()));
        assertEquals(WorkbenchPreviewStore.LookupState.ACTIVE, store.lookupState(g2.identity()));
        assertEquals(WorkbenchPreviewStore.LookupState.ACTIVE, store.lookupState(g3.identity()));
    }

    @Test
    void resolvesMultiHopSupersededChainTransitivelyToActivePreviewAndFinalGraph() {
        var now = new AtomicLong(200_000L);
        var store = new WorkbenchPreviewStore(2, 15_000L, now::get);
        var p1 = sampleGraph("p1");
        var p2a = sampleGraph("p2a");
        var p2b = sampleGraph("p2b");
        var finalAuthoritativeId = ContentDigest.sha256Utf8("final-multihop-graph");

        // Stage 1 -> Stage 2a -> Stage 2b
        store.putPreview("job-mh", p1, AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE, 0, 3, List.of("a", "b", "c"));
        store.putPreview("job-mh", p2a, AnalysisJobs.TruthTier.PARTIAL_CONTEXTS, 1, 3, List.of("b", "c"));
        store.putPreview("job-mh", p2b, AnalysisJobs.TruthTier.PARTIAL_CONTEXTS, 2, 3, List.of("c"));

        // While Stage 2b is active, P1 and P2a both resolve directly to active P2b
        assertEquals(Optional.of(p2b.identity()), store.supersededBy(p1.identity()));
        assertEquals(Optional.of(p2b.identity()), store.supersededBy(p2a.identity()));
        assertEquals(Optional.empty(), store.supersededBy(p2b.identity()));

        // Job finishes -> during grace period, P1, P2a, and P2b all resolve transitively to FINAL
        store.markJobFinished("job-mh", Optional.of(finalAuthoritativeId));
        assertEquals(Optional.of(finalAuthoritativeId), store.supersededBy(p1.identity()));
        assertEquals(Optional.of(finalAuthoritativeId), store.supersededBy(p2a.identity()));
        assertEquals(Optional.of(finalAuthoritativeId), store.supersededBy(p2b.identity()));

        // Grace period expires -> all tombstoned intermediates still resolve directly to FINAL
        now.addAndGet(15_000L);
        assertEquals(WorkbenchPreviewStore.LookupState.SUPERSEDED, store.lookupState(p1.identity()));
        assertEquals(WorkbenchPreviewStore.LookupState.SUPERSEDED, store.lookupState(p2a.identity()));
        assertEquals(WorkbenchPreviewStore.LookupState.SUPERSEDED, store.lookupState(p2b.identity()));
        assertEquals(Optional.of(finalAuthoritativeId), store.supersededBy(p1.identity()));
        assertEquals(Optional.of(finalAuthoritativeId), store.supersededBy(p2a.identity()));
        assertEquals(Optional.of(finalAuthoritativeId), store.supersededBy(p2b.identity()));
    }

    @Test
    void sharedPreviewGraphDigestRemainsActiveWhileAnotherJobStillOwnsIt() {
        var now = new AtomicLong(300_000L);
        var store = new WorkbenchPreviewStore(4, 5_000L, now::get);
        var shared = sampleGraph("shared-same-snapshot");

        store.putPreview("job-A", shared, AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE, 0, 2, List.of("a", "b"));
        store.putPreview("job-B", shared, AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE, 0, 2, List.of("a", "b"));

        // Job A finishes and its grace period expires -> Job B still owns the shared graph digest!
        store.markJobFinished("job-A", Optional.of(ContentDigest.sha256Utf8("final-A")));
        now.addAndGet(10_000L);

        assertEquals(WorkbenchPreviewStore.LookupState.ACTIVE, store.lookupState(shared.identity()),
                "Shared preview graph must stay ACTIVE while job-B is still running");
        assertTrue(store.load(shared.identity()).isPresent());

        // Now Job B finishes and expires -> graph transitions cleanly to SUPERSEDED
        var finalB = ContentDigest.sha256Utf8("final-B");
        store.markJobFinished("job-B", Optional.of(finalB));
        now.addAndGet(10_000L);
        assertEquals(WorkbenchPreviewStore.LookupState.SUPERSEDED, store.lookupState(shared.identity()));
        assertEquals(Optional.of(finalB), store.supersededBy(shared.identity()));
    }
}
