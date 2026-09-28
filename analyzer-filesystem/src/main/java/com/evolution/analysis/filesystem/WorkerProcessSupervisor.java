package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Supervises fixed, analyzer-owned work in separate JVMs; no target command is accepted. */
public final class WorkerProcessSupervisor {
    public static final String SCHEMA = "trusted-worker-supervision-v1";
    private static final long MAX_BATCH_INPUT_BYTES = 256L * 1024 * 1024;
    public enum Status {
        COMPLETED, INPUT_MISMATCH, CRASHED, TIMED_OUT, PROTOCOL_ERROR, START_FAILED,
        CANCELLED, CHECKPOINT_CORRUPT, CHECKPOINT_STALE, CHECKPOINT_UNAVAILABLE
    }
    public enum Termination { COMPLETE, PARTIAL, CANCELLED }

    public record Policy(Duration timeout, int maxHeapMiB, int maxInputBytes,
                         int maxOutputBytes, int maxTasks) {
        public Policy {
            Objects.requireNonNull(timeout);
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0
                    || maxHeapMiB < 16 || maxHeapMiB > 1024 || maxInputBytes < 1
                    || maxInputBytes > 64 * 1024 * 1024 || maxOutputBytes < 96
                    || maxOutputBytes > 16 * 1024 * 1024 || maxTasks < 1 || maxTasks > 1_000)
                throw new IllegalArgumentException("Invalid trusted-worker limits");
        }
        public ContentDigest identity() {
            return IngestionEvidence.digest(List.of(SCHEMA, timeout.toNanos(), maxHeapMiB,
                    maxInputBytes, maxOutputBytes, maxTasks));
        }
    }

    public record Task(String id, byte[] capturedInput, ContentDigest expectedInputDigest) {
        public Task {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,100}"))
                throw new IllegalArgumentException("Invalid worker task ID");
            capturedInput = Objects.requireNonNull(capturedInput).clone();
            Objects.requireNonNull(expectedInputDigest);
        }
        @Override public byte[] capturedInput() { return capturedInput.clone(); }
        public ContentDigest identity(Policy policy) {
            return IngestionEvidence.digest(List.of(SCHEMA, id, expectedInputDigest, policy.identity()));
        }
    }

    public record Unit(Task task, Status status, Optional<ContentDigest> outputDigest, boolean restored) {
        public Unit {
            Objects.requireNonNull(task); Objects.requireNonNull(status); Objects.requireNonNull(outputDigest);
            if ((status == Status.COMPLETED) != outputDigest.isPresent())
                throw new IllegalArgumentException("Only completed units expose output evidence");
            if (restored && status != Status.COMPLETED)
                throw new IllegalArgumentException("Only completed evidence can be restored");
        }
    }
    public record Result(ContentDigest requestIdentity, List<Unit> units, Termination termination) {
        public Result {
            Objects.requireNonNull(requestIdentity); units = List.copyOf(units);
            Objects.requireNonNull(termination);
            if ((termination == Termination.COMPLETE) != units.stream().allMatch(u -> u.status() == Status.COMPLETED))
                throw new IllegalArgumentException("Complete result must contain only completed units");
        }
        /** Replay location and wall-clock timing are excluded from the semantic result identity. */
        public ContentDigest identity() {
            return IngestionEvidence.digest(List.of(SCHEMA, requestIdentity,
                    units.stream().map(u -> List.of(u.task().id(), u.status(), u.outputDigest())).toList(),
                    termination));
        }
        /** Every non-completed unit remains an explicit, scoped evidence obligation. */
        public List<CapabilityGapRecord> gaps(EvidenceContext context) {
            return gaps(context, new VersionedIdentifier("repository.trusted-worker", "m4uv2.1-v1"));
        }
        public List<CapabilityGapRecord> gaps(EvidenceContext context, VersionedIdentifier provider) {
            return gaps(context, provider, EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION);
        }
        public List<CapabilityGapRecord> gaps(EvidenceContext context, VersionedIdentifier provider,
                EvidenceRequirement.AuthorizationClass authorizationClass) {
            Objects.requireNonNull(context);
            Objects.requireNonNull(provider);
            Objects.requireNonNull(authorizationClass);
            VersionedIdentifier catalog = new VersionedIdentifier("evidence.worker-supervision", "m4uv2.1-v1");
            return units.stream().filter(u -> u.status() != Status.COMPLETED).map(unit -> {
                var observation = ProviderObservationReference.create(provider, "worker.unit",
                        unit.task().expectedInputDigest(),
                        IngestionEvidence.digest(List.of(requestIdentity, unit.task().id(), unit.status())));
                var subject = EvidenceSubject.observation(observation.identity());
                var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                        "worker." + unit.task().id(), List.of(subject),
                        authorizationClass,
                        List.of("Replay the captured input with the recorded trusted worker and policy."));
                return CapabilityGapRecord.create(catalog, context, provider, "repository.trusted-worker",
                        unit.status().name(), subject, List.of(), List.of(observation), List.of(requirement),
                        List.of(), List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,
                                "worker." + unit.task().id())), List.of(), List.of(),
                        List.of("A failed worker unit does not establish an analyzer result."));
            }).sorted().toList();
        }
    }

    @FunctionalInterface
    interface Launcher { Process start(Task task, Policy policy) throws IOException; }
    private final Launcher launcher;
    private final WorkerStageCheckpointStore checkpoints = new WorkerStageCheckpointStore();

    public WorkerProcessSupervisor() { this((task, policy) -> startTrustedWorker(policy)); }
    WorkerProcessSupervisor(Launcher launcher) { this.launcher = Objects.requireNonNull(launcher); }

    public Result run(List<Task> tasks, Policy policy, Path checkpointDirectory, BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(tasks); Objects.requireNonNull(policy);
        Objects.requireNonNull(checkpointDirectory); Objects.requireNonNull(cancellationRequested);
        if (tasks.size() > policy.maxTasks()) throw new IllegalArgumentException("Worker task budget exceeded");
        long totalBytes = 0;
        for (Task task : tasks) {
            totalBytes += task.capturedInput.length;
            if (totalBytes > MAX_BATCH_INPUT_BYTES)
                throw new IllegalArgumentException("Worker batch byte budget exceeded");
        }
        var ordered = tasks.stream().sorted(Comparator.comparing(Task::id)).toList();
        if (ordered.stream().map(Task::id).distinct().count() != ordered.size())
            throw new IllegalArgumentException("Duplicate worker task ID");
        ContentDigest requestIdentity = IngestionEvidence.digest(List.of(SCHEMA, policy.identity(),
                ordered.stream().map(t -> t.identity(policy)).toList()));
        var units = new ArrayList<Unit>();
        boolean cancelled = false;
        for (Task task : ordered) {
            if (cancelled || cancellationRequested.getAsBoolean()) {
                cancelled = true;
                units.add(new Unit(task, Status.CANCELLED, Optional.empty(), false));
                continue;
            }
            if (task.capturedInput.length > policy.maxInputBytes()
                    || !ContentDigest.sha256(task.capturedInput).equals(task.expectedInputDigest())) {
                units.add(new Unit(task, Status.INPUT_MISMATCH, Optional.empty(), false));
                continue;
            }
            var checkpoint = checkpoints.load(checkpointDirectory, task.identity(policy));
            if (checkpoint.status() == WorkerStageCheckpointStore.Status.RESTORED) {
                // The trusted operation is SHA-256 over captured input. A sealed but inconsistent output is rejected.
                if (checkpoint.output().orElseThrow().equals(task.expectedInputDigest()))
                    units.add(new Unit(task, Status.COMPLETED, checkpoint.output(), true));
                else units.add(new Unit(task, Status.CHECKPOINT_CORRUPT, Optional.empty(), false));
                continue;
            }
            if (checkpoint.status() != WorkerStageCheckpointStore.Status.MISSING) {
                Status failure = switch (checkpoint.status()) {
                    case CORRUPT -> Status.CHECKPOINT_CORRUPT;
                    case STALE -> Status.CHECKPOINT_STALE;
                    default -> Status.CHECKPOINT_UNAVAILABLE;
                };
                units.add(new Unit(task, failure, Optional.empty(), false));
                continue;
            }
            Unit executed = execute(task, policy, cancellationRequested);
            if (executed.status() == Status.CANCELLED) cancelled = true;
            if (executed.status() == Status.COMPLETED
                    && !checkpoints.save(checkpointDirectory, task.identity(policy), executed.outputDigest().orElseThrow()))
                executed = new Unit(task, Status.CHECKPOINT_UNAVAILABLE, Optional.empty(), false);
            units.add(executed);
        }
        Termination termination = cancelled ? Termination.CANCELLED
                : units.stream().allMatch(u -> u.status() == Status.COMPLETED) ? Termination.COMPLETE : Termination.PARTIAL;
        return new Result(requestIdentity, units, termination);
    }

    private Unit execute(Task task, Policy policy, BooleanSupplier cancellationRequested) {
        Process process;
        try { process = launcher.start(task, policy); }
        catch (IOException | SecurityException | IllegalStateException failure) {
            return new Unit(task, Status.START_FAILED, Optional.empty(), false);
        }
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> writer = io.submit(() -> {
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()))) {
                    out.writeUTF(TrustedWorkerMain.MAGIC);
                    out.writeInt(task.capturedInput.length);
                    out.write(task.capturedInput);
                } catch (IOException ignored) { /* nonzero exit and protocol validation classify this */ }
            });
            Future<byte[]> reader = io.submit(() -> {
                try (InputStream in = process.getInputStream()) {
                    return in.readNBytes(policy.maxOutputBytes() + 1);
                }
            });
            long deadline = System.nanoTime() + policy.timeout().toNanos();
            while (process.isAlive()) {
                if (cancellationRequested.getAsBoolean()) {
                    kill(process);
                    return new Unit(task, Status.CANCELLED, Optional.empty(), false);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    kill(process);
                    return new Unit(task, Status.TIMED_OUT, Optional.empty(), false);
                }
                process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
            }
            if (process.exitValue() != 0) return new Unit(task, Status.CRASHED, Optional.empty(), false);
            long remaining = Math.max(1, deadline - System.nanoTime());
            byte[] response = reader.get(remaining, TimeUnit.NANOSECONDS);
            writer.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (response.length > policy.maxOutputBytes())
                return new Unit(task, Status.PROTOCOL_ERROR, Optional.empty(), false);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(response))) {
                if (!TrustedWorkerMain.MAGIC.equals(input.readUTF()))
                    return new Unit(task, Status.PROTOCOL_ERROR, Optional.empty(), false);
                ContentDigest output = new ContentDigest(input.readUTF());
                if (input.available() != 0 || !output.equals(task.expectedInputDigest()))
                    return new Unit(task, Status.PROTOCOL_ERROR, Optional.empty(), false);
                return new Unit(task, Status.COMPLETED, Optional.of(output), false);
            } catch (IOException | IllegalArgumentException invalid) {
                return new Unit(task, Status.PROTOCOL_ERROR, Optional.empty(), false);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); kill(process);
            return new Unit(task, Status.CANCELLED, Optional.empty(), false);
        } catch (TimeoutException timedOutIpc) {
            kill(process);
            return new Unit(task, Status.TIMED_OUT, Optional.empty(), false);
        } catch (ExecutionException failedIpc) {
            kill(process);
            return new Unit(task, Status.PROTOCOL_ERROR, Optional.empty(), false);
        } finally {
            if (process.isAlive()) kill(process);
        }
    }

    static Process startTrustedWorker(Policy policy) throws IOException {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // Never inherit the host classpath: it may contain jars from the repository being analyzed.
        String classpath = Arrays.stream(new Class<?>[] {TrustedWorkerMain.class, ContentDigest.class})
                .map(type -> {
                    try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                    catch (Exception invalid) { throw new IllegalStateException("Trusted worker code location unavailable"); }
                }).distinct().reduce((left, right) -> left + java.io.File.pathSeparator + right).orElseThrow();
        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-Xmx" + policy.maxHeapMiB() + "m", "-cp", classpath,
                TrustedWorkerMain.class.getName(), Integer.toString(policy.maxInputBytes()))
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        return builder.start();
    }

    private static void kill(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
        try { process.getOutputStream().close(); } catch (IOException ignored) { /* bounded teardown */ }
        try { process.getInputStream().close(); } catch (IOException ignored) { /* bounded teardown */ }
        try { process.waitFor(100, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
