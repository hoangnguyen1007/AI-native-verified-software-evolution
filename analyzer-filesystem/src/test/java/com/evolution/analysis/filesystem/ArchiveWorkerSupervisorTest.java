package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveWorkerSupervisorTest {
    @TempDir Path checkpoints;

    @Test void selectedEntriesAreProducedOutsideParentAndReplayFromSealedCheckpoint() throws Exception {
        byte[] archive = zip(List.of("Thing.class", "META-INF/versions/21/Thing.class",
                "META-INF/MANIFEST.MF", "META-INF/spring.factories"),
                List.of(new byte[] {1}, new byte[] {2},
                        "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes(),
                        "sample.Factory=sample.Thing\n".getBytes()));
        var policy = new WorkerProcessSupervisor.Policy(Duration.ofSeconds(3), 64, 4096, 4096, 1);
        var task = new ArchiveWorkerSupervisor.Task(archive, ContentDigest.sha256(archive), 21,
                true, 10, 4096);
        var first = new ArchiveWorkerSupervisor().scan(task, policy, checkpoints, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.COMPLETED, first.status());
        assertEquals(4, first.entries());
        assertEquals(21, first.classes().getFirst().release());
        assertEquals(ContentDigest.sha256(new byte[] {2}), first.classes().getFirst().digest());
        assertEquals(1, first.resources().size());

        var launches = new AtomicInteger();
        var replay = new ArchiveWorkerSupervisor((ignored, limits, scratch) -> {
            launches.incrementAndGet();
            throw new IOException("must replay");
        }).scan(task, policy, checkpoints, () -> false);
        assertEquals(0, launches.get());
        assertEquals(first.identity(), replay.identity());
        assertTrue(replay.restored());
    }

    @Test void malformedArchiveAndInputMismatchNeverYieldSelectedClasses() throws Exception {
        var policy = new WorkerProcessSupervisor.Policy(Duration.ofSeconds(3), 64, 4096, 4096, 1);
        byte[] corrupt = {1, 2, 3};
        var bad = new ArchiveWorkerSupervisor().scan(new ArchiveWorkerSupervisor.Task(corrupt,
                ContentDigest.sha256(corrupt), 21, true, 10, 4096), policy, checkpoints, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.CORRUPT_ARCHIVE, bad.status());
        assertTrue(bad.classes().isEmpty());
        var mismatch = new ArchiveWorkerSupervisor().scan(new ArchiveWorkerSupervisor.Task(corrupt,
                ContentDigest.sha256(new byte[] {9}), 21, true, 10, 4096), policy,
                checkpoints, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.INPUT_MISMATCH, mismatch.status());
        assertTrue(mismatch.resources().isEmpty());
        byte[] valid = zip(List.of("Thing.class"), List.of(new byte[] {1}));
        var small = new WorkerProcessSupervisor.Policy(Duration.ofSeconds(3), 64, 32, 4096, 1);
        var oversized = new ArchiveWorkerSupervisor().scan(new ArchiveWorkerSupervisor.Task(valid,
                ContentDigest.sha256(valid), 21, true, 10, 4096), small, checkpoints, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.INPUT_LIMIT, oversized.status());
    }

    @Test void expansionLimitAndUnsafePathsRemainExplicitWithoutSelectedEvidence() throws Exception {
        var policy = new WorkerProcessSupervisor.Policy(Duration.ofSeconds(3), 64, 4096, 4096, 1);
        byte[] compressed = zip(List.of("Huge.class"), List.of(new byte[1024]));
        var limited = new ArchiveWorkerSupervisor().scan(new ArchiveWorkerSupervisor.Task(compressed,
                ContentDigest.sha256(compressed), 21, true, 10, 10), policy, checkpoints, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.ARCHIVE_EXPANSION_LIMIT, limited.status());
        assertTrue(limited.classes().isEmpty());
        byte[] escape = zip(List.of("../Thing.class"), List.of(new byte[] {1}));
        var rejected = new ArchiveWorkerSupervisor().scan(new ArchiveWorkerSupervisor.Task(escape,
                ContentDigest.sha256(escape), 21, true, 10, 1024), policy, checkpoints, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.UNSAFE_ARCHIVE_ENTRY, rejected.status());
    }

    @Test void corruptCheckpointAndWorkerStartFailureCannotRestoreACompleteScan() throws Exception {
        byte[] archive = zip(List.of("Thing.class"), List.of(new byte[] {1}));
        var task = new ArchiveWorkerSupervisor.Task(archive, ContentDigest.sha256(archive), 21,
                true, 10, 1024);
        var policy = new WorkerProcessSupervisor.Policy(Duration.ofSeconds(3), 64, 4096, 4096, 1);
        var complete = new ArchiveWorkerSupervisor().scan(task, policy, checkpoints, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.COMPLETED, complete.status());
        Path checkpoint;
        try (var files = Files.list(checkpoints)) {
            checkpoint = files.filter(p -> p.toString().endsWith(".archive")).findFirst().orElseThrow();
        }
        var otherRelease = new ArchiveWorkerSupervisor.Task(archive, ContentDigest.sha256(archive), 17,
                true, 10, 1024);
        Path wrongTarget = checkpoints.resolve(otherRelease.identity(policy).value().substring(7) + ".archive");
        Files.copy(checkpoint, wrongTarget);
        var stale = new ArchiveWorkerSupervisor().scan(otherRelease, policy, checkpoints, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.CHECKPOINT_STALE, stale.status());
        assertTrue(stale.classes().isEmpty());
        Files.write(checkpoint, new byte[] {1, 2, 3});
        var corrupt = new ArchiveWorkerSupervisor().scan(task, policy, checkpoints, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.CHECKPOINT_CORRUPT, corrupt.status());
        assertTrue(corrupt.classes().isEmpty());
        assertNotEquals(complete.identity(), corrupt.identity());
        Path empty = Files.createDirectory(checkpoints.resolve("empty"));
        var failed = new ArchiveWorkerSupervisor((ignored, limits, scratch) -> {
            throw new IOException("injected start failure");
        }).scan(task, policy, empty, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.START_FAILED, failed.status());
        assertTrue(failed.resources().isEmpty());
    }

    @Test void crashedAndTimedOutArchiveWorkersNeverPublishEntriesAndCanRetry() throws Exception {
        byte[] archive = zip(List.of("Thing.class"), List.of(new byte[] {1}));
        var task = new ArchiveWorkerSupervisor.Task(archive, ContentDigest.sha256(archive), 21,
                true, 10, 1024);
        var policy = new WorkerProcessSupervisor.Policy(Duration.ofMillis(700), 64, 4096, 4096, 1);
        var crashed = new ArchiveWorkerSupervisor((ignored, limits, scratch) -> fault("exit"))
                .scan(task, policy, checkpoints, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.CRASHED, crashed.status());
        assertTrue(crashed.classes().isEmpty());
        var timedOut = new ArchiveWorkerSupervisor((ignored, limits, scratch) -> fault("hang"))
                .scan(task, policy, checkpoints, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.TIMED_OUT, timedOut.status());
        assertTrue(timedOut.resources().isEmpty());
        var retried = new ArchiveWorkerSupervisor().scan(task, policy, checkpoints, () -> false);
        assertEquals(ArchiveWorkerSupervisor.Status.COMPLETED, retried.status());
        assertNotEquals(crashed.identity(), retried.identity());
    }

    private static Process fault(String mode) throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                WorkerProcessSupervisorTest.WorkerFaultMain.class.getName(), mode)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static byte[] zip(List<String> names, List<byte[]> bytes) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (int i = 0; i < names.size(); i++) {
                zip.putNextEntry(new ZipEntry(names.get(i)));
                zip.write(bytes.get(i));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
