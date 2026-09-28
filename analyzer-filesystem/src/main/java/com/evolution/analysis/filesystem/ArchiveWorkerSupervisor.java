package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Bounded, fixed-operation archive inspection in a trusted child JVM. Target paths never enter the worker. */
public final class ArchiveWorkerSupervisor {
    static final String MAGIC = "trusted-archive-scan-v1";
    public enum Status {
        COMPLETED, INPUT_LIMIT, INPUT_MISMATCH, CRASHED, TIMED_OUT, PROTOCOL_ERROR, START_FAILED, CANCELLED,
        CHECKPOINT_CORRUPT, CHECKPOINT_STALE, CHECKPOINT_UNAVAILABLE, CORRUPT_ARCHIVE,
        ARCHIVE_ENTRY_LIMIT, ARCHIVE_EXPANSION_LIMIT, UNSAFE_ARCHIVE_ENTRY,
        DUPLICATE_ARCHIVE_ENTRY, NESTED_ARCHIVE_UNSELECTED, RESOURCE_LIMIT, SCRATCH_UNAVAILABLE
    }
    public record Task(byte[] archive, ContentDigest expectedDigest, int targetRelease,
                       boolean collectEntries, int maxEntries, long maxExpandedBytes) {
        public Task {
            archive = Objects.requireNonNull(archive).clone(); Objects.requireNonNull(expectedDigest);
            if (targetRelease < 1 || maxEntries < 1 || maxExpandedBytes < 1)
                throw new IllegalArgumentException("Invalid archive scan limits");
        }
        @Override public byte[] archive() { return archive.clone(); }
        ContentDigest identity(WorkerProcessSupervisor.Policy policy) {
            return IngestionEvidence.digest(List.of(MAGIC, expectedDigest, targetRelease,
                    collectEntries, maxEntries, maxExpandedBytes, policy.identity()));
        }
    }
    public record Resource(String name, byte[] bytes) {
        public Resource { Objects.requireNonNull(name); bytes = Objects.requireNonNull(bytes).clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record ClassEntry(String logicalName, String physicalName, int release, ContentDigest digest) {
        public ClassEntry {
            Objects.requireNonNull(logicalName); Objects.requireNonNull(physicalName);
            Objects.requireNonNull(digest);
        }
    }
    public record Scan(ContentDigest taskIdentity, Status status, int entries, long expandedBytes,
                       List<Resource> resources, List<ClassEntry> classes, boolean restored) {
        public Scan {
            Objects.requireNonNull(taskIdentity); Objects.requireNonNull(status);
            resources = List.copyOf(resources); classes = List.copyOf(classes);
            if (status != Status.COMPLETED && (entries != 0 || expandedBytes != 0
                    || !resources.isEmpty() || !classes.isEmpty() || restored))
                throw new IllegalArgumentException("Failed scans cannot expose selected entries");
        }
        /** The output/status participates in identity; checkpoint location and replay telemetry do not. */
        public ContentDigest identity() {
            return IngestionEvidence.digest(List.of(MAGIC, taskIdentity, status, entries, expandedBytes,
                    resources.stream().map(r -> List.of(r.name(), ContentDigest.sha256(r.bytes()))).toList(),
                    classes));
        }
    }
    @FunctionalInterface interface Launcher {
        Process start(Task task, WorkerProcessSupervisor.Policy policy, Path scratchDirectory) throws IOException;
    }
    private final Launcher launcher;
    public ArchiveWorkerSupervisor() { this(ArchiveWorkerSupervisor::startWorker); }
    ArchiveWorkerSupervisor(Launcher launcher) { this.launcher = Objects.requireNonNull(launcher); }

    public Scan scan(Task task, WorkerProcessSupervisor.Policy policy, Path checkpointDirectory,
                     BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(task); Objects.requireNonNull(policy);
        Objects.requireNonNull(checkpointDirectory); Objects.requireNonNull(cancellationRequested);
        ContentDigest identity = task.identity(policy);
        if (cancellationRequested.getAsBoolean()) return failed(identity, Status.CANCELLED);
        if (task.archive.length > policy.maxInputBytes())
            return failed(identity, Status.INPUT_LIMIT);
        if (!ContentDigest.sha256(task.archive).equals(task.expectedDigest()))
            return failed(identity, Status.INPUT_MISMATCH);
        var checkpoint = load(checkpointDirectory, identity, policy.maxOutputBytes());
        if (checkpoint.status() == CheckpointStatus.RESTORED) {
            Scan restored = decode(checkpoint.bytes().orElseThrow(), task, policy, true);
            return restored.status() == Status.PROTOCOL_ERROR
                    ? failed(identity, Status.CHECKPOINT_CORRUPT) : restored;
        }
        if (checkpoint.status() != CheckpointStatus.MISSING)
            return failed(identity, switch (checkpoint.status()) {
                case CORRUPT -> Status.CHECKPOINT_CORRUPT;
                case STALE -> Status.CHECKPOINT_STALE;
                default -> Status.CHECKPOINT_UNAVAILABLE;
            });
        Process process;
        try { process = launcher.start(task, policy, checkpointDirectory); }
        catch (IOException | SecurityException | IllegalStateException failure) {
            return failed(identity, Status.START_FAILED);
        }
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> writer = io.submit(() -> {
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()))) {
                    out.writeUTF(MAGIC); out.writeInt(task.targetRelease());
                    out.writeBoolean(task.collectEntries()); out.writeInt(task.maxEntries());
                    out.writeLong(task.maxExpandedBytes()); out.writeInt(task.archive.length);
                    out.write(task.archive);
                } catch (IOException ignored) { /* exit and protocol outcome classify this */ }
            });
            Future<byte[]> reader = io.submit(() -> {
                try (InputStream in = process.getInputStream()) {
                    return in.readNBytes(policy.maxOutputBytes() + 1);
                }
            });
            long deadline = System.nanoTime() + policy.timeout().toNanos();
            while (process.isAlive()) {
                if (cancellationRequested.getAsBoolean()) { kill(process); return failed(identity, Status.CANCELLED); }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { kill(process); return failed(identity, Status.TIMED_OUT); }
                process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
            }
            if (process.exitValue() != 0) return failed(identity, Status.CRASHED);
            byte[] response = reader.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            writer.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (response.length > policy.maxOutputBytes()) return failed(identity, Status.RESOURCE_LIMIT);
            Scan decoded = decode(response, task, policy, false);
            if (decoded.status() != Status.COMPLETED) return decoded;
            if (!save(checkpointDirectory, identity, response))
                return failed(identity, Status.CHECKPOINT_UNAVAILABLE);
            return decoded;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); kill(process); return failed(identity, Status.CANCELLED);
        } catch (TimeoutException timeout) {
            kill(process); return failed(identity, Status.TIMED_OUT);
        } catch (ExecutionException failure) {
            kill(process); return failed(identity, Status.PROTOCOL_ERROR);
        } finally { if (process.isAlive()) kill(process); }
    }

    private static Scan decode(byte[] response, Task task, WorkerProcessSupervisor.Policy policy, boolean restored) {
        ContentDigest identity = task.identity(policy);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(response))) {
            if (!MAGIC.equals(in.readUTF()) || !task.expectedDigest().value().equals(in.readUTF()))
                return failed(identity, Status.PROTOCOL_ERROR);
            Status status = Status.valueOf(in.readUTF());
            if (status != Status.COMPLETED) {
                if (status == Status.INPUT_LIMIT || status == Status.INPUT_MISMATCH
                        || status == Status.CHECKPOINT_CORRUPT
                        || status == Status.CHECKPOINT_STALE || status == Status.CHECKPOINT_UNAVAILABLE
                        || status == Status.START_FAILED || status == Status.CANCELLED
                        || status == Status.TIMED_OUT || status == Status.CRASHED
                        || status == Status.PROTOCOL_ERROR || in.available() != 0)
                    return failed(identity, Status.PROTOCOL_ERROR);
                return failed(identity, status);
            }
            int entries = in.readInt(); long expanded = in.readLong();
            if (entries < 1 || entries > task.maxEntries() || expanded < 0 || expanded > task.maxExpandedBytes())
                return failed(identity, Status.PROTOCOL_ERROR);
            int resourceCount = in.readInt();
            if (resourceCount < 0 || resourceCount > entries) return failed(identity, Status.PROTOCOL_ERROR);
            var resources = new ArrayList<Resource>();
            for (int i = 0; i < resourceCount; i++) {
                String name = in.readUTF(); int length = in.readInt();
                if (length < 0 || length > 1_048_576 || length > in.available())
                    return failed(identity, Status.PROTOCOL_ERROR);
                resources.add(new Resource(name, in.readNBytes(length)));
            }
            int classCount = in.readInt();
            if (classCount < 0 || classCount > entries) return failed(identity, Status.PROTOCOL_ERROR);
            var classes = new ArrayList<ClassEntry>();
            for (int i = 0; i < classCount; i++)
                classes.add(new ClassEntry(in.readUTF(), in.readUTF(), in.readInt(), new ContentDigest(in.readUTF())));
            if (in.available() != 0 || !task.collectEntries() && (!resources.isEmpty() || !classes.isEmpty()))
                return failed(identity, Status.PROTOCOL_ERROR);
            return new Scan(identity, status, entries, expanded, resources, classes, restored);
        } catch (IOException | RuntimeException invalid) {
            return failed(identity, Status.PROTOCOL_ERROR);
        }
    }

    private static Scan failed(ContentDigest identity, Status status) {
        return new Scan(identity, status, 0, 0, List.of(), List.of(), false);
    }

    private static Process startWorker(Task task, WorkerProcessSupervisor.Policy policy,
                                       Path scratchDirectory) throws IOException {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Arrays.stream(new Class<?>[] {ArchiveWorkerMain.class, ContentDigest.class})
                .map(type -> {
                    try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                    catch (Exception invalid) { throw new IllegalStateException("Trusted worker code location unavailable"); }
                }).distinct().reduce((a, b) -> a + File.pathSeparator + b).orElseThrow();
        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-Xmx" + policy.maxHeapMiB() + "m",
                "-Djava.io.tmpdir=" + scratchDirectory.toAbsolutePath().normalize(),
                "-cp", classpath, ArchiveWorkerMain.class.getName(),
                Integer.toString(policy.maxInputBytes()), Integer.toString(policy.maxOutputBytes()))
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        return builder.start();
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.waitFor(100, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private enum CheckpointStatus { MISSING, RESTORED, CORRUPT, STALE, UNAVAILABLE }
    private record Checkpoint(CheckpointStatus status, Optional<byte[]> bytes) { }
    private static Checkpoint load(Path directory, ContentDigest identity, int maxBytes) {
        Path file;
        try { file = checkpointFile(directory, identity); }
        catch (IOException | RuntimeException invalid) { return new Checkpoint(CheckpointStatus.UNAVAILABLE, Optional.empty()); }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            return new Checkpoint(CheckpointStatus.MISSING, Optional.empty());
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            return new Checkpoint(CheckpointStatus.CORRUPT, Optional.empty());
        try {
            if (Files.size(file) > maxBytes + 256L) return new Checkpoint(CheckpointStatus.CORRUPT, Optional.empty());
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < 32) return new Checkpoint(CheckpointStatus.CORRUPT, Optional.empty());
            byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
            byte[] seal = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
            if (!MessageDigest.isEqual(seal, digestBytes(payload)))
                return new Checkpoint(CheckpointStatus.CORRUPT, Optional.empty());
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (!MAGIC.equals(in.readUTF())) return new Checkpoint(CheckpointStatus.CORRUPT, Optional.empty());
                ContentDigest stored = new ContentDigest(in.readUTF());
                int size = in.readInt();
                if (size < 0 || size > maxBytes || size != in.available())
                    return new Checkpoint(CheckpointStatus.CORRUPT, Optional.empty());
                return stored.equals(identity)
                        ? new Checkpoint(CheckpointStatus.RESTORED, Optional.of(in.readNBytes(size)))
                        : new Checkpoint(CheckpointStatus.STALE, Optional.empty());
            }
        } catch (IOException | RuntimeException invalid) {
            return new Checkpoint(CheckpointStatus.CORRUPT, Optional.empty());
        }
    }
    private static boolean save(Path directory, ContentDigest identity, byte[] response) {
        Path target;
        try { target = checkpointFile(directory, identity); }
        catch (IOException | RuntimeException unavailable) { return false; }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false;
        Path staged = null;
        try {
            var buffer = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(buffer)) {
                data.writeUTF(MAGIC); data.writeUTF(identity.value());
                data.writeInt(response.length); data.write(response);
            }
            byte[] payload = buffer.toByteArray();
            staged = Files.createTempFile(directory, "archive-stage-", ".tmp");
            try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                write(channel, ByteBuffer.wrap(payload)); write(channel, ByteBuffer.wrap(digestBytes(payload)));
                channel.force(true);
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException failed) { return false; }
        finally { if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) { } }
    }
    private static void write(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }
    private static byte[] digestBytes(byte[] payload) {
        return HexFormat.of().parseHex(ContentDigest.sha256(payload).value().substring(7));
    }
    private static Path checkpointFile(Path selected, ContentDigest identity) throws IOException {
        Path directory = selected.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.equals(directory.toRealPath())) throw new IOException("Invalid checkpoint directory");
        return directory.resolve(identity.value().substring(7) + ".archive");
    }
}
