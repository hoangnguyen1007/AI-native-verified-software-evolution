package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.*;
import com.evolution.backend.query.PublishedReports;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisResultReuseTest {
    @TempDir Path state;
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate("urn:test:result-reuse");
    private static final ContentDigest INPUT = ContentDigest.sha256Utf8("one verified input");

    @Test void conflictingFullRecomputationDisablesTheKeyWithoutOverwritingHistory() throws Exception {
        var calls = new AtomicInteger();
        var graphs = new InMemoryGraphStore();
        var firstGraph = graph("first");
        var conflictingGraph = graph("second");
        var setup = new AnalysisJobs.Setup("local", REPOSITORY, 21, false, Optional.empty(), Optional.empty());
        var registration = new AnalysisJobs.Registration(setup, control -> {
            control.beginReuseCheck();
            var hit = control.reuse(INPUT);
            if (hit.isPresent()) return hit.orElseThrow();
            control.stage(AnalysisJobs.Stage.ANALYSIS);
            return new AnalysisJobs.Outcome(AnalysisJobs.Completion.COMPLETE,
                    Optional.of(calls.incrementAndGet() == 1 ? firstGraph : conflictingGraph),
                    List.of(), List.of(), Optional.empty(), Optional.empty());
        });
        try (var disk = new AnalysisJobDiskStore(state);
             var jobs = new AnalysisJobs(List.of(registration), graphs, new PublishedReports(), AnalysisJobs.Limits.defaults(), disk)) {
            var first = complete(jobs, jobs.submit("local", "first"));
            assertEquals(AnalysisJobs.State.SUCCEEDED, first.state());
            assertEquals(first.id(), complete(jobs, jobs.submit("local", "repeat")).reuse().orElseThrow().sourceJob());
            var forced = complete(jobs, jobs.submit("local", "forced", true));
            assertTrue(forced.force());
            assertEquals(conflictingGraph.identity(), forced.result().orElseThrow().graph().orElseThrow());
            assertEquals("IDEMPOTENCY_CONFLICT",
                    assertThrows(AnalysisJobs.Rejected.class, () -> jobs.submit("local", "forced")).code());
            var next = complete(jobs, jobs.submit("local", "next"));
            assertTrue(next.reuse().isEmpty());
            assertEquals(3, calls.get());
            assertEquals(first.result(), jobs.get(UUID.fromString(first.id())).result());
            assertTrue(graphs.available(firstGraph.identity()));
            assertTrue(graphs.available(conflictingGraph.identity()));
        }
    }

    private static KnowledgeGraph graph(String content) {
        var snapshot = SnapshotIdentity.from(REPOSITORY, ContentDigest.sha256Utf8(content));
        return new KnowledgeGraph(snapshot, List.of(), List.of(), List.of(), List.of());
    }

    private static AnalysisJobs.View complete(AnalysisJobs jobs, AnalysisJobs.Submission submitted) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var id = UUID.fromString(submitted.job().id());
        do {
            var view = jobs.get(id);
            if (AnalysisJobs.terminal(view.state())) return view;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Job did not finish");
    }
}
