package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.EvidenceContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkerProcessSupervisorTest {
    @TempDir Path checkpoints;

    private static WorkerProcessSupervisor.Policy policy() {
        return new WorkerProcessSupervisor.Policy(Duration.ofSeconds(2), 64, 4096, 256, 10);
    }
    private static WorkerProcessSupervisor.Task task(String id) {
        byte[] bytes = ("source:" + id).getBytes(StandardCharsets.UTF_8);
        return new WorkerProcessSupervisor.Task(id, bytes, ContentDigest.sha256(bytes));
    }

    @Test void completedUnitsReplayWithoutLaunchingWorkersAndIdentityIsStable() {
        var supervisor = new WorkerProcessSupervisor();
        var first = supervisor.run(List.of(task("b"), task("a")), policy(), checkpoints, () -> false);
        assertEquals(WorkerProcessSupervisor.Termination.COMPLETE, first.termination());
        assertEquals(List.of("a", "b"), first.units().stream().map(u -> u.task().id()).toList());
        assertTrue(first.units().stream().allMatch(u -> u.status() == WorkerProcessSupervisor.Status.COMPLETED));
        assertEquals(task("a").expectedInputDigest(), first.units().getFirst().outputDigest().orElseThrow());

        var launches = new AtomicInteger();
        var restarted = new WorkerProcessSupervisor((task, policy) -> {
            launches.incrementAndGet();
            throw new AssertionError("checkpointed work must not launch");
        }).run(List.of(task("a"), task("b")), policy(), checkpoints, () -> false);
        assertEquals(0, launches.get());
        assertEquals(first.identity(), restarted.identity());
        assertTrue(restarted.units().stream().allMatch(WorkerProcessSupervisor.Unit::restored));
    }

    @Test void workerCrashAndTimeoutPreserveIndependentCompletedUnits() {
        var supervisor = new WorkerProcessSupervisor((task, policy) -> {
            if (task.id().equals("b")) return helper("exit");
            if (task.id().equals("c")) return helper("hang");
            return WorkerProcessSupervisor.startTrustedWorker(policy);
        });
        var limit = new WorkerProcessSupervisor.Policy(Duration.ofMillis(800), 64, 4096, 256, 10);
        var result = supervisor.run(List.of(task("c"), task("a"), task("b")), limit, checkpoints, () -> false);
        assertEquals(List.of(WorkerProcessSupervisor.Status.COMPLETED,
                WorkerProcessSupervisor.Status.CRASHED, WorkerProcessSupervisor.Status.TIMED_OUT),
                result.units().stream().map(WorkerProcessSupervisor.Unit::status).toList());
        assertEquals(WorkerProcessSupervisor.Termination.PARTIAL, result.termination());
        assertTrue(result.units().getFirst().outputDigest().isPresent());
        assertTrue(result.units().subList(1, 3).stream().allMatch(u -> u.outputDigest().isEmpty()));

        var recovered = new WorkerProcessSupervisor().run(List.of(task("a"), task("b"), task("c")), limit,
                checkpoints, () -> false);
        assertEquals(WorkerProcessSupervisor.Termination.COMPLETE, recovered.termination());
        assertTrue(recovered.units().getFirst().restored());
        Path uninterruptedDirectory = checkpoints.resolve("uninterrupted");
        assertDoesNotThrow(() -> Files.createDirectory(uninterruptedDirectory));
        var uninterrupted = new WorkerProcessSupervisor().run(List.of(task("a"), task("b"), task("c")), limit,
                uninterruptedDirectory, () -> false);
        assertEquals(uninterrupted.identity(), recovered.identity());
    }

    @Test void malformedResponseAndCorruptCheckpointCannotBecomeCompletedEvidence() throws Exception {
        var malformed = new WorkerProcessSupervisor((task, policy) -> helper("garbage"))
                .run(List.of(task("a")), policy(), checkpoints, () -> false);
        assertEquals(WorkerProcessSupervisor.Status.PROTOCOL_ERROR, malformed.units().getFirst().status());
        assertTrue(malformed.units().getFirst().outputDigest().isEmpty());

        var first = new WorkerProcessSupervisor().run(List.of(task("a")), policy(), checkpoints, () -> false);
        assertEquals(WorkerProcessSupervisor.Status.COMPLETED, first.units().getFirst().status());
        var checkpoint = Files.list(checkpoints).findFirst().orElseThrow();
        Files.write(checkpoint, new byte[] {1, 2, 3}, StandardOpenOption.TRUNCATE_EXISTING);
        var retry = new WorkerProcessSupervisor().run(List.of(task("a")), policy(), checkpoints, () -> false);
        assertEquals(WorkerProcessSupervisor.Status.CHECKPOINT_CORRUPT, retry.units().getFirst().status());
        assertEquals(WorkerProcessSupervisor.Termination.PARTIAL, retry.termination());
    }

    @Test void cancellationRetainsEarlierUnitAndLeavesLaterUnitOutstanding() {
        new WorkerProcessSupervisor().run(List.of(task("a")), policy(), checkpoints, () -> false);
        var calls = new AtomicInteger();
        var result = new WorkerProcessSupervisor().run(List.of(task("a"), task("b")), policy(),
                checkpoints, () -> calls.getAndIncrement() >= 1);
        assertEquals(List.of(WorkerProcessSupervisor.Status.COMPLETED, WorkerProcessSupervisor.Status.CANCELLED),
                result.units().stream().map(WorkerProcessSupervisor.Unit::status).toList());
        assertEquals(WorkerProcessSupervisor.Termination.CANCELLED, result.termination());
        assertTrue(result.units().getFirst().outputDigest().isPresent());
    }

    @Test void activeWorkerCancellationKillsItAndRecordsAnObligation() {
        var checks = new AtomicInteger();
        var result = new WorkerProcessSupervisor((task, policy) -> helper("hang"))
                .run(List.of(task("a"), task("b")), policy(), checkpoints,
                        () -> checks.incrementAndGet() >= 3);
        assertEquals(WorkerProcessSupervisor.Termination.CANCELLED, result.termination());
        assertEquals(List.of(WorkerProcessSupervisor.Status.CANCELLED, WorkerProcessSupervisor.Status.CANCELLED),
                result.units().stream().map(WorkerProcessSupervisor.Unit::status).toList());
        var context = EvidenceContext.forSnapshot(new SnapshotIdentity("snapshot:sha256:" + "a".repeat(64)));
        assertEquals(List.of("CANCELLED", "CANCELLED"),
                result.gaps(context).stream().map(g -> g.reasonCode()).toList());
        assertEquals(2, result.gaps(context).stream().map(g -> g.gapIdentity()).distinct().count());
    }

    @Test void staleCheckpointIsRejectedAndDoesNotLaunchWorker() throws Exception {
        new WorkerProcessSupervisor().run(List.of(task("a")), policy(), checkpoints, () -> false);
        Path old = checkpoints.resolve(task("a").identity(policy()).value().substring(7) + ".worker");
        Path wrong = checkpoints.resolve(task("b").identity(policy()).value().substring(7) + ".worker");
        Files.copy(old, wrong);
        var result = new WorkerProcessSupervisor((task, policy) -> {
            throw new AssertionError("stale checkpoint must not launch a worker");
        }).run(List.of(task("b")), policy(), checkpoints, () -> false);
        assertEquals(WorkerProcessSupervisor.Status.CHECKPOINT_STALE, result.units().getFirst().status());
        assertTrue(result.units().getFirst().outputDigest().isEmpty());
    }

    @Test void failedInputAndResourceFailuresNeverBecomeSuccessfulEvidence() {
        var wrong = new WorkerProcessSupervisor.Task("wrong", new byte[] {1}, ContentDigest.sha256(new byte[] {2}));
        var failed = new WorkerProcessSupervisor((task, policy) -> helper(task.id().equals("oom") ? "oom" : "stack"))
                .run(List.of(wrong, task("oom"), task("stack")), policy(), checkpoints, () -> false);
        assertEquals(List.of(WorkerProcessSupervisor.Status.CRASHED, WorkerProcessSupervisor.Status.CRASHED,
                WorkerProcessSupervisor.Status.INPUT_MISMATCH),
                failed.units().stream().map(WorkerProcessSupervisor.Unit::status).toList());
        assertTrue(failed.units().stream().allMatch(u -> u.outputDigest().isEmpty()));
    }

    private static Process helper(String mode) throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                WorkerFaultMain.class.getName(), mode)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    public static final class WorkerFaultMain {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "exit" -> System.exit(17);
                case "hang" -> Thread.sleep(60_000);
                case "garbage" -> System.out.write(new byte[] {1, 2, 3});
                case "oom" -> { var blocks = new ArrayList<byte[]>(); while (true) blocks.add(new byte[1024 * 1024]); }
                case "stack" -> overflow();
                default -> throw new IllegalArgumentException();
            }
        }
        private static void overflow() { overflow(); }
    }
}
