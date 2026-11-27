package com.evolution.backend.analysis;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisJobEventBrokerTest {

    @Test
    void replaysMissedEventsByLastEventIdAndSignalsResyncWhenBehindRing() throws Exception {
        var broker = new AnalysisJobEventBroker(4, 32);
        broker.publish("job-1", "stage_started", Map.of("step", 1));
        broker.publish("job-1", "module_completed", Map.of("step", 2));
        broker.publish("job-1", "preview_ready", Map.of("step", 3));
        broker.publish("job-1", "stage_started", Map.of("step", 4));

        // Reconnect from seq 2 -> should replay seq 3 and 4 without resync
        try (var sub = broker.subscribe("job-1", 2L, Optional.empty())) {
            assertFalse(sub.resyncRequired());
            var e3 = sub.poll(100, TimeUnit.MILLISECONDS);
            var e4 = sub.poll(100, TimeUnit.MILLISECONDS);
            assertNotNull(e3);
            assertNotNull(e4);
            assertEquals(3L, e3.seq());
            assertEquals("preview_ready", e3.event());
            assertEquals(4L, e4.seq());
            assertEquals("stage_started", e4.event());
        }

        // Push 3 more events so seq 1..3 fall out of the 4-element ring (ring retains 4..7)
        broker.publish("job-1", "stage_started", Map.of("step", 5));
        broker.publish("job-1", "stage_started", Map.of("step", 6));
        broker.publish("job-1", "job_completed", Map.of("step", 7));

        // Reconnect from seq 1 -> behind ring -> resyncRequired + resync_required + snapshot + job_completed
        try (var sub = broker.subscribe("job-1", 1L, Optional.of(Map.of("state", "SUCCEEDED")))) {
            assertTrue(sub.resyncRequired());
            var r = sub.poll(100, TimeUnit.MILLISECONDS);
            var snap = sub.poll(100, TimeUnit.MILLISECONDS);
            var term = sub.poll(100, TimeUnit.MILLISECONDS);
            assertNotNull(r);
            assertEquals("resync_required", r.event());
            assertNotNull(snap);
            assertEquals("snapshot", snap.event());
            assertNotNull(term);
            assertEquals("job_completed", term.event());
            assertTrue(sub.isTerminalDelivered());
        }
    }

    @Test
    void coalescesHighFrequencyProgressWithoutBlockingOrDroppingMilestones() throws Exception {
        var broker = new AnalysisJobEventBroker(256, 16);
        try (var sub = broker.subscribe("job-2", 0L, Optional.of(Map.of("state", "RUNNING")))) {
            assertEquals(1, broker.subscriberCount("job-2"));

            // Blast 500 rapid progress updates + key milestones while subscriber isn't draining
            broker.publish("job-2", "stage_started", Map.of("stage", "ANALYSIS"));
            for (int i = 1; i <= 500; i++) {
                broker.publish("job-2", "progress_update", Map.of("done", i, "total", 500));
            }
            broker.publish("job-2", "preview_ready", Map.of("truthTier", "PRELIMINARY_STRUCTURE"));
            broker.publish("job-2", "job_completed", Map.of("state", "SUCCEEDED"));

            var events = new ArrayList<AnalysisJobEventBroker.JobStreamEvent>();
            AnalysisJobEventBroker.JobStreamEvent ev;
            while ((ev = sub.poll(50, TimeUnit.MILLISECONDS)) != null) {
                events.add(ev);
                if (ev.isTerminal()) break;
            }
            // All milestones preserved in exact order
            var names = events.stream().map(AnalysisJobEventBroker.JobStreamEvent::event).toList();
            assertTrue(names.contains("snapshot"));
            assertTrue(names.contains("stage_started"));
            assertTrue(names.contains("preview_ready"));
            assertTrue(names.contains("job_completed"));
            // 500 progress_update events coalesced to at most 1 (or 0 if terminal closed loop first)
            long progressCount = names.stream().filter("progress_update"::equals).count();
            assertTrue(progressCount <= 1, "Expected coalesced progress updates, got " + progressCount);
        }
        assertEquals(0, broker.subscriberCount("job-2"));
    }

    @Test
    void closingOneSubscriberDoesNotAffectOtherSubscribers() throws Exception {
        var broker = new AnalysisJobEventBroker();
        var sub1 = broker.subscribe("job-3", 0L, Optional.empty());
        var sub2 = broker.subscribe("job-3", 0L, Optional.empty());
        assertEquals(2, broker.subscriberCount("job-3"));

        sub1.close();
        assertEquals(1, broker.subscriberCount("job-3"));

        broker.publish("job-3", "preview_ready", Map.of("ok", true));
        var received = sub2.poll(200, TimeUnit.MILLISECONDS);
        assertNotNull(received);
        assertEquals("preview_ready", received.event());
        sub2.close();
        assertEquals(0, broker.subscriberCount("job-3"));
    }

    @Test
    void concurrentPublishersDeliverStrictlyMonotonicSequenceOrderWithoutTerminalOvertake() throws Exception {
        var broker = new AnalysisJobEventBroker(1024, 512);
        var pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 20; round++) {
                String jobId = "concurrent-job-" + round;
                try (var sub = broker.subscribe(jobId, 0L, Optional.empty())) {
                    var startGate = new CountDownLatch(1);
                    var doneGate = new CountDownLatch(4);
                    for (int t = 0; t < 3; t++) {
                        int worker = t;
                        pool.submit(() -> {
                            try {
                                startGate.await();
                                for (int i = 0; i < 40; i++) {
                                    broker.publish(jobId, "module_completed", Map.of("worker", worker, "i", i));
                                }
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            } finally {
                                doneGate.countDown();
                            }
                        });
                    }
                    pool.submit(() -> {
                        try {
                            startGate.await();
                            for (int i = 0; i < 10; i++) {
                                broker.publish(jobId, "preview_ready", Map.of("rev", i));
                            }
                            broker.publish(jobId, "incremental_summary", Map.of("reusedFiles", 12));
                            broker.publish(jobId, "job_completed", Map.of("state", "SUCCEEDED"));
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneGate.countDown();
                        }
                    });
                    startGate.countDown();
                    assertTrue(doneGate.await(10, TimeUnit.SECONDS));

                    long prevSeq = 0L;
                    int seenMilestones = 0;
                    boolean sawTerminal = false;
                    AnalysisJobEventBroker.JobStreamEvent ev;
                    while ((ev = sub.poll(200, TimeUnit.MILLISECONDS)) != null) {
                        assertTrue(ev.seq() > prevSeq,
                                "Out-of-order SSE delivery in round " + round + ": prev=" + prevSeq + ", curr=" + ev.seq());
                        prevSeq = ev.seq();
                        seenMilestones++;
                        if (ev.isTerminal()) {
                            sawTerminal = true;
                            break;
                        }
                    }
                    assertTrue(sawTerminal, "Expected terminal event in round " + round);
                    var ring = broker.recentEvents(jobId);
                    long terminalSeq = ring.stream()
                            .filter(AnalysisJobEventBroker.JobStreamEvent::isTerminal)
                            .findFirst()
                            .orElseThrow()
                            .seq();
                    assertEquals(terminalSeq, prevSeq,
                            "Terminal event must be delivered at its exact monotonic seq without skipping earlier milestones");
                    assertEquals(terminalSeq, seenMilestones,
                            "Every earlier milestone seq 1..terminalSeq must be delivered before closing");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reconnectAtTerminalEventClosesImmediatelyAndPrunesTerminalChannelsEvenWhenOldestIsActive() throws Exception {
        var broker = new AnalysisJobEventBroker(16, 16);
        broker.publish("term-job", "stage_started", Map.of("step", 1));
        broker.publish("term-job", "job_completed", Map.of("state", "SUCCEEDED"));
        long lastSeq = broker.recentEvents("term-job").getLast().seq();

        // P0-5: reconnecting with Last-Event-ID == terminalSeq must immediately mark terminal delivered (no 2h hang!)
        try (var sub = broker.subscribe("term-job", lastSeq, Optional.of(Map.of("state", "SUCCEEDED")))) {
            assertTrue(sub.isTerminalDelivered(), "Reconnect at terminal seq must finish without hanging");
            assertNull(sub.poll(20, TimeUnit.MILLISECONDS));
        }

        // P0-5: channel pruning must evict terminal idle channels even when oldest entry is active
        try (var activeSub = broker.subscribe("active-oldest", 0L, Optional.empty())) {
            for (int i = 0; i < 140; i++) {
                String id = "done-" + i;
                broker.publish(id, "job_completed", Map.of("i", i));
            }
            assertEquals(1, broker.subscriberCount("active-oldest"));
            assertTrue(broker.recentEvents("done-139").size() == 1);
            // Oldest terminal channels should have been pruned once count > 128
            assertTrue(broker.recentEvents("done-0").isEmpty(),
                    "Expected oldest terminal idle channel to be pruned despite active oldest channel");
        }
    }
}
