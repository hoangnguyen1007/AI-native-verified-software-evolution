package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.backend.analysis.AnalysisJobs;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisJobsTest {
    @Test void semanticWorkerMemoryExhaustionRetainsFailureAndReleasesTheSlot()throws Exception{
        var graph=new QueryFixture().graph();var store=new InMemoryGraphStore();
        try(var jobs=new AnalysisJobs(List.of(registration("oversize",control->{throw new OutOfMemoryError("trusted worker control");}),registration("independent",control->output(graph))),store,new PublishedReports(),limits())){
            var failed=finished(jobs,jobs.submit("oversize","oversize").job().id());assertEquals(AnalysisJobs.State.FAILED,failed.state());assertEquals("SEMANTIC_WORKER_REQUIRED",failed.error().orElseThrow());assertTrue(failed.result().isEmpty());
            assertEquals(AnalysisJobs.State.SUCCEEDED,finished(jobs,jobs.submit("independent","next").job().id()).state());
        }
    }
    static AnalysisJobs.Registration registration(String id, AnalysisJobs.Runner runner) {
        return new AnalysisJobs.Registration(new AnalysisJobs.Setup(id,RepositoryIdentity.fromCanonicalCoordinate("urn:test:jobs"),21,false,Optional.empty(),Optional.empty()),runner);
    }
    static AnalysisJobs.Outcome output(KnowledgeGraph graph) {
        return new AnalysisJobs.Outcome(AnalysisJobs.Completion.COMPLETE,Optional.of(graph),List.of(),List.of(),Optional.empty(),Optional.empty());
    }
    static AnalysisJobs.View finished(AnalysisJobs jobs,String id) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) { var view = jobs.get(UUID.fromString(id)); if (AnalysisJobs.terminal(view.state())) return view; Thread.sleep(2); }
        throw new AssertionError("Job did not finish");
    }
    static AnalysisJobs.Limits limits() { return new AnalysisJobs.Limits(1,1,8,5_000,100); }
    static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(3,TimeUnit.SECONDS)); }
    @Test void successPublishesTheExactGraphAndKeepsOperationalMetadataOutsideItsIdentity() throws Exception {
        var graph = new QueryFixture().node("a").graph(); var store = new InMemoryGraphStore(); var reports = new PublishedReports();
        try (var jobs = new AnalysisJobs(List.of(registration("local",control -> { control.stage(AnalysisJobs.Stage.ANALYSIS); return output(graph); })),store,reports,limits())) {
            var submission = jobs.submit("local","retry-1"); var view = finished(jobs,submission.job().id());
            assertEquals(AnalysisJobs.State.SUCCEEDED,view.state()); assertEquals(graph.identity(),view.result().orElseThrow().graph().orElseThrow());
            assertSame(graph,store.load(graph.identity()).orElseThrow()); assertTrue(view.startedAt().isPresent()); assertTrue(view.finishedAt().isPresent());
            assertEquals(List.of(AnalysisJobs.Stage.QUEUED,AnalysisJobs.Stage.ANALYSIS,AnalysisJobs.Stage.PUBLICATION,AnalysisJobs.Stage.FINISHED),view.events().stream().map(AnalysisJobs.Event::stage).toList());
            var repeated = jobs.submit("local","retry-1"); assertFalse(repeated.created()); assertEquals(view.id(),repeated.job().id()); assertEquals(1,jobs.history(20,Optional.empty()).total());
            var second = finished(jobs,jobs.submit("local","retry-2").job().id()); assertEquals(view.result(),second.result()); assertNotEquals(view.id(),second.id());
            assertDoesNotThrow(() -> CanonicalJson.write(view));
        }
    }
    @Test void queuedCancellationReleasesAdmissionWithoutInvokingItsRunnerAndRetriesAreIdempotent() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger(); var graph = new QueryFixture().graph();
        try (var jobs = new AnalysisJobs(List.of(registration("local",control -> { calls.incrementAndGet(); entered.countDown(); await(release); return output(graph); })),new InMemoryGraphStore(),new PublishedReports(),limits())) {
            var active = jobs.submit("local","a").job(); await(entered); var queued = jobs.submit("local","b").job();
            assertEquals("JOB_QUEUE_FULL",assertThrows(AnalysisJobs.Rejected.class,() -> jobs.submit("local","c")).code());
            assertEquals(AnalysisJobs.State.CANCELLED,jobs.cancel(UUID.fromString(queued.id())).state());
            assertEquals(queued.id(),jobs.submit("local","b").job().id());
            var next = jobs.submit("local","c").job(); release.countDown();
            assertEquals(AnalysisJobs.State.SUCCEEDED,finished(jobs,active.id()).state()); assertEquals(AnalysisJobs.State.SUCCEEDED,finished(jobs,next.id()).state()); assertEquals(2,calls.get());
            assertEquals(3,jobs.history(20,Optional.empty()).total());
        } finally { release.countDown(); }
    }
    @Test void cancellingUncooperativeWorkKeepsItsSlotAndDiscardsItsLateOutput() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var graph = new QueryFixture().node("a").graph(); var store = new InMemoryGraphStore();
        try (var jobs = new AnalysisJobs(List.of(registration("local",control -> {
            entered.countDown(); while (release.getCount() != 0) { try { release.await(); } catch (InterruptedException ignored) {} } return output(graph);
        })),store,new PublishedReports(),limits())) {
            var running = jobs.submit("local","a").job(); await(entered);
            assertEquals(AnalysisJobs.State.CANCELLING,jobs.cancel(UUID.fromString(running.id())).state());
            var queued = jobs.submit("local","b").job();
            assertEquals("JOB_QUEUE_FULL",assertThrows(AnalysisJobs.Rejected.class,() -> jobs.submit("local","c")).code());
            jobs.cancel(UUID.fromString(queued.id())); release.countDown();
            var cancelled = finished(jobs,running.id()); assertEquals(AnalysisJobs.State.CANCELLED,cancelled.state()); assertTrue(cancelled.result().isEmpty()); assertFalse(store.available(graph.identity()));
            assertEquals(AnalysisJobs.Stop.USER_CANCELLED,cancelled.stop().orElseThrow());
        } finally { release.countDown(); }
    }
    @Test void deadlineCancelsCooperativeWorkAndDoesNotMasqueradeAsASemanticResult() throws Exception {
        var graph = new QueryFixture().graph(); var store = new InMemoryGraphStore();
        try (var jobs = new AnalysisJobs(List.of(registration("local",control -> { new CountDownLatch(1).await(); return output(graph); })),store,new PublishedReports(),new AnalysisJobs.Limits(1,1,4,30,100))) {
            var view = finished(jobs,jobs.submit("local","timeout").job().id());
            assertEquals(AnalysisJobs.State.TIMED_OUT,view.state()); assertEquals(AnalysisJobs.Stop.DEADLINE_EXCEEDED,view.stop().orElseThrow());
            assertTrue(view.result().isEmpty()); assertFalse(store.available(graph.identity()));
        }
    }
    @Test void failuresAreSanitizedAndFollowingJobsStillRun() throws Exception {
        var graph = new QueryFixture().graph(); var calls = new AtomicInteger();
        try (var jobs = new AnalysisJobs(List.of(registration("local",control -> { if (calls.incrementAndGet() == 1) throw new IOExceptionWithSecret(); return output(graph); })),new InMemoryGraphStore(),new PublishedReports(),limits())) {
            var failed = finished(jobs,jobs.submit("local","a").job().id()); assertEquals(AnalysisJobs.State.FAILED,failed.state());
            assertEquals("ANALYSIS_FAILED",failed.error().orElseThrow()); assertFalse(CanonicalJson.write(failed).contains("secret"));
            assertEquals(AnalysisJobs.State.SUCCEEDED,finished(jobs,jobs.submit("local","b").job().id()).state());
        }
    }
    private static final class IOExceptionWithSecret extends Exception { IOExceptionWithSecret() { super("C:/private/secret-token"); } }
    @Test void historyFreezesMembershipAndRejectsChangedOrForeignCursors() throws Exception {
        var graph = new QueryFixture().graph();
        try (var jobs = new AnalysisJobs(List.of(registration("local",control -> output(graph))),new InMemoryGraphStore(),new PublishedReports(),limits());
             var other = new AnalysisJobs(List.of(),new InMemoryGraphStore(),new PublishedReports(),limits())) {
            for (int i = 0; i < 3; i++) finished(jobs,jobs.submit("local","r"+i).job().id());
            var first = jobs.history(1,Optional.empty()); assertEquals(3,first.total());
            finished(jobs,jobs.submit("local","later").job().id());
            var second = jobs.history(1,first.continuation()); var third = jobs.history(1,second.continuation());
            assertEquals(3,third.total()); assertTrue(third.continuation().isEmpty());
            assertEquals(3,Set.of(first.items().getFirst().id(),second.items().getFirst().id(),third.items().getFirst().id()).size());
            assertThrows(IllegalArgumentException.class,() -> jobs.history(2,first.continuation()));
            assertThrows(IllegalArgumentException.class,() -> other.history(1,first.continuation()));
            assertThrows(IllegalArgumentException.class,() -> jobs.history(1,Optional.of(first.continuation().orElseThrow()+"x")));
        }
    }
    @Test void historyCapacityAndConflictingRetriesAreExplicitAndUnknownSetupsNeverRun() throws Exception {
        var graph = new QueryFixture().graph(); var invoked = new AtomicInteger();
        var runner = (AnalysisJobs.Runner)control -> { invoked.incrementAndGet(); return output(graph); };
        try (var jobs = new AnalysisJobs(List.of(registration("a",runner),registration("b",runner)),new InMemoryGraphStore(),new PublishedReports(),new AnalysisJobs.Limits(1,1,2,5_000,100))) {
            finished(jobs,jobs.submit("a","same").job().id());
            assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AnalysisJobs.Rejected.class,() -> jobs.submit("b","same")).code());
            assertEquals("SETUP_NOT_FOUND",assertThrows(AnalysisJobs.Rejected.class,() -> jobs.submit("missing","x")).code());
            finished(jobs,jobs.submit("b","second").job().id());
            assertEquals("JOB_HISTORY_FULL",assertThrows(AnalysisJobs.Rejected.class,() -> jobs.submit("a","third")).code());
            assertEquals(2,invoked.get()); assertEquals(2,jobs.history(50,Optional.empty()).total());
        }
    }
    @Test void shutdownIsIdempotentCancelsAllOutstandingJobsAndRejectsAdmission() throws Exception {
        var entered = new CountDownLatch(1);
        var jobs = new AnalysisJobs(List.of(registration("local",control -> { entered.countDown(); new CountDownLatch(1).await(); return output(new QueryFixture().graph()); })),new InMemoryGraphStore(),new PublishedReports(),limits());
        var active = jobs.submit("local","a").job(); await(entered); var queued = jobs.submit("local","b").job();
        jobs.close(); jobs.close();
        assertEquals(AnalysisJobs.State.CANCELLED,finished(jobs,active.id()).state()); assertEquals(AnalysisJobs.State.CANCELLED,jobs.get(UUID.fromString(queued.id())).state());
        assertEquals("JOBS_CLOSED",assertThrows(AnalysisJobs.Rejected.class,() -> jobs.submit("local","c")).code());
    }
    @Test void canonicalGapsDowngradeCompleteOutputsAndBothGapPopulationsRemainVisible() throws Exception {
        var f = new QueryFixture(); var gaps = IngestionEvidence.gaps(f.snapshot,new VersionedIdentifier("fixture.provider","1"),ContentDigest.sha256Utf8("input"),
                List.of(new IngestionEvidence.Issue(IngestionEvidence.Reason.INPUT_UNAVAILABLE,"source",List.of(ContentDigest.sha256Utf8("source")))));
        var gap = gaps.getFirst(); var evidence = GraphEvidence.capture("evidence.gap",gap); f.evidence.add(evidence);
        f.nodes.add(new KnowledgeGraph.Node(gap.gapIdentity().value(),f.context,"CAPABILITY_GAP",List.of(evidence.identity())));
        var graph = f.graph();
        try (var jobs = new AnalysisJobs(List.of(registration("local",control -> new AnalysisJobs.Outcome(AnalysisJobs.Completion.COMPLETE,Optional.of(graph),List.of(),gaps,Optional.empty(),Optional.empty()))),new InMemoryGraphStore(),new PublishedReports(),limits())) {
            var view = finished(jobs,jobs.submit("local","a").job().id()); assertEquals(AnalysisJobs.State.PARTIAL,view.state());
            assertEquals(1,view.result().orElseThrow().gapCount()); assertEquals(1,view.result().orElseThrow().graphGapCount()); assertEquals(1,view.result().orElseThrow().jobGapCount());
            assertEquals(gaps,jobs.gaps(UUID.fromString(view.id()),50,Optional.empty()).items());
        }
    }
    @Test void storageFailureCannotPublishPolicyReportsOrASuccessReceipt() throws Exception {
        var f = PublishedReportsTest.fixture(); var reports = new PublishedReports();
        GraphStore unavailable = new GraphStore() {
            public void save(KnowledgeGraph graph,java.util.function.BooleanSupplier cancelled) { throw new GraphStoreException(GraphStoreException.Reason.IO_FAILURE); }
            public Optional<KnowledgeGraph> load(ContentDigest id) { return Optional.empty(); }
        };
        try (var jobs = new AnalysisJobs(List.of(registration("local",control -> new AnalysisJobs.Outcome(AnalysisJobs.Completion.COMPLETE,Optional.of(f.graph()),List.of(f.report()),List.of(),Optional.empty(),Optional.empty()))),unavailable,reports,limits())) {
            var view = finished(jobs,jobs.submit("local","a").job().id()); assertEquals(AnalysisJobs.State.FAILED,view.state());
            assertEquals("RESULT_STORAGE_FAILED",view.error().orElseThrow()); assertTrue(view.result().isEmpty()); assertTrue(reports.forGraph(f.graph().identity()).isEmpty());
        }
    }
    @Test void providerLinkageFailuresAreClosedAsFailedJobsWithoutExceptionText() throws Exception {
        try (var jobs = new AnalysisJobs(List.of(registration("local",control -> { throw new NoClassDefFoundError("private-secret-class"); })),new InMemoryGraphStore(),new PublishedReports(),limits())) {
            var view = finished(jobs,jobs.submit("local","a").job().id()); assertEquals(AnalysisJobs.State.FAILED,view.state());
            assertEquals("ANALYSIS_FAILED",view.error().orElseThrow()); assertFalse(CanonicalJson.write(view).contains("secret"));
        }
    }
    @Test void cancellationDuringStoragePreventsReportsAndLateSuccessfulReceipts() throws Exception {
        var f = PublishedReportsTest.fixture(); var reports = new PublishedReports(); var entered = new CountDownLatch(1); var memory = new InMemoryGraphStore();
        GraphStore delayed = new GraphStore() {
            public void save(KnowledgeGraph graph,java.util.function.BooleanSupplier cancelled) {
                entered.countDown();
                try { new CountDownLatch(1).await(); } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                memory.save(graph,cancelled);
            }
            public Optional<KnowledgeGraph> load(ContentDigest id) { return memory.load(id); }
        };
        try (var jobs = new AnalysisJobs(List.of(registration("local",control -> new AnalysisJobs.Outcome(AnalysisJobs.Completion.COMPLETE,Optional.of(f.graph()),List.of(f.report()),List.of(),Optional.empty(),Optional.empty()))),delayed,reports,limits())) {
            var job = jobs.submit("local","a").job(); await(entered); jobs.cancel(UUID.fromString(job.id()));
            var view = finished(jobs,job.id()); assertEquals(AnalysisJobs.State.CANCELLED,view.state()); assertTrue(view.result().isEmpty());
            assertFalse(memory.available(f.graph().identity())); assertTrue(reports.forGraph(f.graph().identity()).isEmpty());
        }
    }
    @Test void providerInterruptionDoesNotInventAServiceShutdown() throws Exception {
        try (var jobs = new AnalysisJobs(List.of(registration("local",control -> { throw new InterruptedException(); })),new InMemoryGraphStore(),new PublishedReports(),limits())) {
            var view = finished(jobs,jobs.submit("local","a").job().id()); assertEquals(AnalysisJobs.State.CANCELLED,view.state());
            assertEquals(AnalysisJobs.Stop.PROVIDER_INTERRUPTED,view.stop().orElseThrow());
        }
    }
    @Test void setupLimitsAndImmutableOutputProvenanceAreValidatedBeforeWork() {
        var f = PublishedReportsTest.fixture(); var other = new QueryFixture().node("foreign").graph();
        assertThrows(IllegalArgumentException.class,() -> new AnalysisJobs.Outcome(AnalysisJobs.Completion.COMPLETE,Optional.of(other),List.of(f.report()),List.of(),Optional.empty(),Optional.empty()));
        assertThrows(IllegalArgumentException.class,() -> new AnalysisJobs.Limits(0,1,3,100,100));
        assertThrows(IllegalArgumentException.class,() -> new AnalysisJobs.Limits(1,1,1,100,100));
        assertThrows(IllegalArgumentException.class,() -> new AnalysisJobs(List.of(registration("same",control -> output(other)),registration("same",control -> output(other))),new InMemoryGraphStore(),new PublishedReports(),limits()));
    }
}
