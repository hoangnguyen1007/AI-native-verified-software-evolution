package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.acquisition.streaming.JobJournal;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evolution.EvolutionSnapshot;
import com.evolution.analysis.evolution.SnapshotComparison;
import com.evolution.analysis.evolution.SnapshotComparison.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.analysis.CanonicalRecordReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Durable operational receipts around offline, immutable comparison inputs. */
public final class EvolutionJobs implements AutoCloseable {
    @FunctionalInterface public interface Snapshots {
        EvolutionSnapshot open(ContentDigest graph, BooleanSupplier cancelled, Consumer<String> progress) throws IOException;
    }
    public record View(ContentDigest job, ContentDigest request, String state, long revision, String reason,
                       Optional<ContentDigest> result, boolean resumed) {}
    public record Submission(View job, boolean created) {}
    private record Payload(String schema, ContentDigest request, ContentHandle input,
                           Optional<ContentHandle> checkpoint, Optional<ContentHandle> work, boolean resumed) {}
    private static final String RECEIPT = "architecture.evolution.receipt:v2";
    private final PartitionedGraphStore graphs;
    private final Snapshots snapshots;
    private final ReportReader reports;
    private final BridgeReader bridges;
    private final HistoryReader history;
    private final Path scratch;
    private final EvolutionResultStore results;
    private final StreamingContentStore content;
    private final SqliteInventoryStore journal;
    private final ThreadPoolExecutor worker;
    private final Map<ContentDigest,AtomicBoolean> running = new HashMap<>();
    private volatile boolean closed;

    public EvolutionJobs(Path directory, PartitionedGraphStore graphs, PublishedReports reports) throws IOException {
        this(directory, graphs, (graph,stop,preparing)->graphs.openEvolution(graph,stop), reports.evolutionReader(), localBridgeReader(directory),
                new EvolutionHistoryProofs(directory).reader());
    }

    public EvolutionJobs(Path directory, PartitionedGraphStore graphs, PublishedReports reports,
                         Snapshots snapshots) throws IOException {
        this(directory, graphs, snapshots, reports.evolutionReader(), localBridgeReader(directory),
                new EvolutionHistoryProofs(directory).reader());
    }

    /** Readers are operator composition. Requests can refer to their IDs only. */
    public EvolutionJobs(Path directory, PartitionedGraphStore graphs, ReportReader reports, BridgeReader bridges,
                         HistoryReader history) throws IOException {
        this(directory,graphs,(graph,stop,preparing)->graphs.openEvolution(graph,stop),reports,bridges,history);
    }

    private EvolutionJobs(Path directory, PartitionedGraphStore graphs, Snapshots snapshots,
                          ReportReader reports, BridgeReader bridges, HistoryReader history) throws IOException {
        this.graphs = Objects.requireNonNull(graphs);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.reports = Objects.requireNonNull(reports);
        this.bridges = Objects.requireNonNull(bridges);
        this.history = Objects.requireNonNull(history);
        scratch = directory.resolve("scratch");
        results = new EvolutionResultStore(directory);
        content = new StreamingContentStore(directory.resolve("cas"));
        journal = new SqliteInventoryStore(directory.resolve("jobs"), content);
        worker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8), task -> {
            var thread = new Thread(task, "architecture-evolution");
            thread.setDaemon(true);
            return thread;
        });
    }

    public EvolutionResultStore results() { return results; }

    private static BridgeReader localBridgeReader(Path directory) {
        var storage = directory.toAbsolutePath().normalize().resolve("bridge-inputs");
        return (ref,snapshot,observe,stop) -> {
            var policyPath = storage.resolve("read-policy.json");
            if (!java.nio.file.Files.isRegularFile(policyPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                return ReportStatus.UNAVAILABLE;
            try {
                var policy = new BridgeReadPolicy(policyPath);
                var content = new StreamingContentStore(storage.resolve("cas"));
                return new BridgePublicationStore(storage.resolve("bridges"),content,
                        new com.evolution.analysis.polyglot.SemanticTables(content,65_536),policy::policy,policy::revoked)
                        .readEvolution(ref,snapshot,observe,stop);
            } catch (IOException | UncheckedIOException unreadable) {
                EvolutionSnapshot.check(stop);
                return ReportStatus.UNAVAILABLE;
            }
        };
    }

    public Submission submit(EvolutionRequest request, Optional<String> key) throws IOException {
        View prior;
        synchronized (this) {
            ensureOpen();
            var identity = request.identity();
            var job = key.map(EvolutionJobs::jobKey).orElseGet(() -> jobKey(UUID.randomUUID().toString()));
            var saved = journal.get(job.value());
            if (saved.isEmpty()) {
                var input = put(request, () -> false);
                var payload = new Payload(RECEIPT, identity, input, Optional.empty(), Optional.empty(), false);
                var record = journal.create(job.value(), identity, Optional.of(put(payload, () -> false)));
                schedule(job);
                return new Submission(view(job, record, payload), true);
            }
            var record = saved.orElseThrow();
            var payload = payload(record);
            if (!identity.equals(payload.request())) throw new Conflict();
            if (!terminal(record.state())) schedule(job);
            prior = view(job, record, payload);
        }
        // An explicit key retries the original job. An unkeyed submission realizes the inputs anew.
        if (prior.result().isPresent()) {
            var result = results.open(prior.result().orElseThrow(), () -> false);
            results.requireUnambiguous(result);
            results.verify(result, () -> false);
        }
        return new Submission(prior, false);
    }

    public synchronized Optional<View> get(ContentDigest job) throws IOException {
        var record = journal.get(job.value());
        if (record.isEmpty()) return Optional.empty();
        var saved = record.orElseThrow();
        var payload = payload(saved);
        if (saved.state() == JobJournal.State.COMPLETE)
            results.open(payload.checkpoint().orElseThrow().digest(), () -> false);
        return Optional.of(view(job, saved, payload));
    }

    public synchronized Optional<View> resume(ContentDigest job) throws IOException {
        ensureOpen();
        var current = get(job);
        if (current.isPresent() && Set.of("QUEUED", "RUNNING", "WAITING_FOR_RESOURCE").contains(current.orElseThrow().state()))
            schedule(job);
        return current;
    }

    public synchronized Optional<View> cancel(ContentDigest job) throws IOException {
        ensureOpen();
        var record = journal.get(job.value());
        if (record.isEmpty()) return Optional.empty();
        var saved = record.orElseThrow();
        var stop = running.get(job);
        if (stop != null) stop.set(true);
        if (!terminal(saved.state()))
            saved = journal.transition(job.value(), saved.revision(), JobJournal.State.CANCELLED, "CANCELLED", saved.payload());
        return Optional.of(view(job, saved, payload(saved)));
    }

    private void schedule(ContentDigest job) {
        if (running.containsKey(job)) return;
        var stop = new AtomicBoolean();
        running.put(job, stop);
        try { worker.execute(() -> execute(job, stop)); }
        catch (RejectedExecutionException busy) {
            running.remove(job);
            throw busy;
        }
    }

    private void execute(ContentDigest job, AtomicBoolean stop) {
        BooleanSupplier cancelled = () -> stop.get() || closed || Thread.currentThread().isInterrupted();
        try {
            Payload payload;
            synchronized (this) {
                var record = journal.get(job.value()).orElseThrow();
                if (terminal(record.state()) || closed) return;
                payload = payload(record);
                boolean resumed = payload.resumed() || record.state() != JobJournal.State.PENDING;
                payload = new Payload(RECEIPT, payload.request(), payload.input(), payload.checkpoint(), payload.work(), resumed);
                change(record, JobJournal.State.RUNNING, resumed ? "RESUMED" : "COMPARING", payload);
            }
            var request = request(payload);
            if (payload.checkpoint().isEmpty() && payload.work().isEmpty()) {
                var reusable = reusable(request, cancelled);
                if (reusable.isPresent()) {
                    var reused = new Payload(RECEIPT,payload.request(),payload.input(),reusable,Optional.empty(),payload.resumed());
                    synchronized (this) {
                        EvolutionSnapshot.check(cancelled);
                        change(journal.get(job.value()).orElseThrow(),JobJournal.State.COMPLETE,"VERIFIED_RESULT_REUSED",reused);
                    }
                    return;
                }
            }
            // A resumed staged result is an attempt checkpoint, not a cache hit. Retry comparison if
            // a pinned dependency's realization changed while the worker was offline.
            if (payload.checkpoint().isPresent() && !checkpointInputsStillReadable(request,
                    payload.checkpoint().orElseThrow(), cancelled))
                payload = new Payload(RECEIPT, payload.request(), payload.input(), Optional.empty(), Optional.empty(), true);
            if (payload.checkpoint().isEmpty()) {
                EvolutionSnapshot.Reader reader = (graph,stopInput) ->
                        snapshots.open(graph,stopInput,stage->noteStage(job,stage));
                try (var session = compare(job,payload,request,reader,cancelled)) {
                    var checkpoint = results.prepare(session.result(),Map.of("mode",request.comparison().mode(),
                            "selection",request.comparison().selection()), cancelled);
                    payload = new Payload(RECEIPT, payload.request(), payload.input(), Optional.of(checkpoint), Optional.empty(), payload.resumed());
                }
                synchronized (this) {
                    EvolutionSnapshot.check(cancelled);
                    change(journal.get(job.value()).orElseThrow(), JobJournal.State.RUNNING, "VERIFYING_PUBLICATION", payload);
                }
            }
            var verified = results.verifyPrepared(payload.checkpoint().orElseThrow(), cancelled);
            synchronized (this) {
                EvolutionSnapshot.check(cancelled);
                results.commit(verified, cancelled);
                change(journal.get(job.value()).orElseThrow(), JobJournal.State.COMPLETE, "", payload);
            }
        } catch (CancellationException stopped) {
            // Explicit cancellation already committed a receipt. Shutdown leaves a resumable checkpoint/attempt.
        } catch (IOException failure) {
            boolean resources = resourceFailure(failure);
            var code = "EVOLUTION_RESULT_CONFLICT".equals(failure.getMessage()) ? "RESULT_CONFLICT"
                    : resources ? "STORAGE_RESOURCE_LIMIT" : "COMPARISON_STORAGE_UNAVAILABLE";
            fail(job, resources ? JobJournal.State.WAITING_FOR_RESOURCES : JobJournal.State.FAILED, code);
        } catch (RuntimeException failure) {
            fail(job, JobJournal.State.FAILED, "COMPARISON_FAILED");
        } finally {
            synchronized (this) { running.remove(job); }
        }
    }

    private FileSnapshotComparison.Session compare(ContentDigest job, Payload payload, EvolutionRequest request,
                                                   EvolutionSnapshot.Reader reader, BooleanSupplier cancelled) throws IOException {
        var work = payload.work();
        if (work.isPresent()) {
            try {
                for (var graph : Set.copyOf(List.of(request.comparison().left(),request.comparison().right())))
                    if (!graphs.verifyPublished(graph,cancelled)) { work=Optional.empty(); break; }
            } catch (IOException unavailable) { work=Optional.empty(); }
        }
        if (work.isPresent()) noteStage(job,"RESUMING_SCOPE");
        FileSnapshotComparison.CheckpointSink sink = saved -> {
            synchronized (this) {
                EvolutionSnapshot.check(cancelled);
                var record=journal.get(job.value()).orElseThrow();
                change(record,JobJournal.State.RUNNING,"SCOPE_CHECKPOINT",
                        new Payload(RECEIPT,payload.request(),payload.input(),Optional.empty(),Optional.of(saved),payload.resumed()));
            }
        };
        try {
            return FileSnapshotComparison.withCheckpoint(reader,scratch,request.comparison(),request.reports(),reports,
                    request.bridges(),bridges,request.history(),history,content,work,sink,20,cancelled);
        } catch (IOException invalid) {
            if (work.isEmpty() || !Objects.toString(invalid.getMessage(),"").startsWith("EVOLUTION_CHECKPOINT_")) throw invalid;
            noteStage(job,"COMPARING");
            return FileSnapshotComparison.withCheckpoint(reader,scratch,request.comparison(),request.reports(),reports,
                    request.bridges(),bridges,request.history(),history,content,Optional.empty(),sink,20,cancelled);
        }
    }

    private static boolean graphOnly(EvolutionRequest request) {
        return request.reports().isEmpty() && request.bridges().isEmpty() && request.history().isEmpty();
    }

    private synchronized void noteStage(ContentDigest job, String reason) {
        if (closed) return;
        try {
            var record = journal.get(job.value()).orElseThrow();
            if (!terminal(record.state()) && !reason.equals(record.reason()))
                journal.transition(job.value(),record.revision(),record.state(),reason,record.payload());
        } catch (IOException unavailable) {
            throw new UncheckedIOException(unavailable);
        }
    }

    private boolean checkpointInputsStillReadable(EvolutionRequest request, ContentHandle handle,
                                                   BooleanSupplier cancelled) throws IOException {
        // Freshly realized graph and sidecar inputs may diagnose a deeper missing leaf than a root probe.
        // Limit checkpoint reuse to verified graph-only inputs with no dependent report/bridge/history readers.
        if (!graphOnly(request)) return false;
        return results.sameAvailableGraphs(handle, request.comparison().left(), request.comparison().right(),
                graph -> graphs.verifyPublished(graph, cancelled), cancelled);
    }

    private EvolutionRequest request(Payload payload) throws IOException {
        var request = CanonicalRecordReader.read(read(payload.input(), 1_048_576), EvolutionRequest.class);
        if (!request.identity().equals(payload.request())) throw new IOException("EVOLUTION_REQUEST_CHANGED");
        return request;
    }

    private Optional<ContentHandle> reusable(EvolutionRequest request, BooleanSupplier cancelled) throws IOException {
        if (!graphOnly(request)) return Optional.empty();
        var inputs = new ArrayList<Map<String,Object>>();
        try {
            for (var side : Side.values()) {
                var id = side == Side.LEFT ? request.comparison().left() : request.comparison().right();
                if (!graphs.hasIndex(id)) return Optional.empty();
                var graph = graphs.openEvolution(id, cancelled);
                var provenance = graphs.evolutionRevision(graph,cancelled).orElse(Map.of());
                if (!request.comparison().repository().value().equals(provenance.get("repository"))) return Optional.empty();
                inputs.add(SnapshotComparison.graphInput(side,id,graph.header().snapshot(),
                        "AVAILABLE",provenance.get("revision"),provenance.get("dirty")));
            }
        } catch (IOException | IllegalArgumentException unavailable) {
            return Optional.empty();
        }
        var candidate = results.findReusable(request.comparison().identity(),inputs,cancelled);
        if (candidate.isEmpty()) return candidate;
        try {
            for (var graph : Set.copyOf(List.of(request.comparison().left(),request.comparison().right())))
                if (!graphs.verifyPublished(graph,cancelled)) return Optional.empty();
        } catch (IOException unavailable) { return Optional.empty(); }
        return candidate;
    }

    private static boolean resourceFailure(IOException failure) {
        for (Throwable at = failure; at != null; at = at.getCause()) {
            var message = Objects.toString(at.getMessage(), "").toLowerCase(Locale.ROOT);
            if (message.contains("waiting_for_disk") || message.contains("resource_limit") ||
                    message.contains("sqlite_full") || message.contains("no space left") ||
                    message.contains("not enough space on the disk")) return true;
        }
        return false;
    }

    private synchronized void fail(ContentDigest job, JobJournal.State state, String reason) {
        if (closed) return;
        try {
            var record = journal.get(job.value()).orElseThrow();
            if (!terminal(record.state())) journal.transition(job.value(), record.revision(), state, reason, record.payload());
        } catch (IOException ignored) {
            // An unavailable journal leaves an unfinished durable attempt that can be retried.
        }
    }

    private void change(JobJournal.Record record, JobJournal.State state, String reason, Payload payload) throws IOException {
        journal.transition(record.id(), record.revision(), state, reason, Optional.of(put(payload, () -> false)));
    }

    private Payload payload(JobJournal.Record record) throws IOException {
        try {
            var raw = new TreeMap<>(GraphJson.object(read(record.payload().orElseThrow(),4096)));
            if ("architecture.evolution.receipt:v1".equals(raw.get("schema")) && !raw.containsKey("work")) {
                raw.put("schema",RECEIPT);
                raw.put("work",null);
            }
            var value = CanonicalRecordReader.read(raw,Payload.class);
            if (!RECEIPT.equals(value.schema())) throw new IOException("EVOLUTION_RECEIPT_CORRUPT");
            return value;
        } catch (RuntimeException invalid) { throw new IOException("EVOLUTION_RECEIPT_CORRUPT"); }
    }

    private Object read(ContentHandle handle, int limit) throws IOException {
        if (handle.size() > limit) throw new IOException("EVOLUTION_RECEIPT_LIMIT");
        try (var input = content.open(handle)) {
            var bytes = input.readNBytes(limit + 1);
            if (bytes.length != handle.size() || !ContentDigest.sha256(bytes).equals(handle.digest()))
                throw new IOException("EVOLUTION_RECEIPT_CORRUPT");
            return GraphJson.read(new String(bytes, StandardCharsets.UTF_8), new GraphJson.Limits(limit, 64, limit));
        }
    }

    private ContentHandle put(Object payload, BooleanSupplier cancelled) throws IOException {
        return content.put(new ByteArrayInputStream(CanonicalJson.write(payload).getBytes(StandardCharsets.UTF_8)), cancelled);
    }

    private static View view(ContentDigest job, JobJournal.Record record, Payload payload) {
        String state = switch (record.state()) {
            case PENDING -> "QUEUED";
            case COMPLETE -> "SUCCEEDED";
            case WAITING_FOR_RESOURCES, WAITING_FOR_INPUT -> "WAITING_FOR_RESOURCE";
            default -> record.state().name();
        };
        return new View(job, payload.request(), state, record.revision(), record.reason(),
                record.state() == JobJournal.State.COMPLETE ? payload.checkpoint().map(ContentHandle::digest) : Optional.empty(),
                payload.resumed());
    }

    private static ContentDigest jobKey(String key) {
        if (!key.matches("[A-Za-z0-9_-]{1,96}")) throw new IllegalArgumentException("Invalid idempotency key");
        return CanonicalJson.sha256(List.of("architecture.evolution.receipt:v1", key));
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("EVOLUTION_JOBS_UNAVAILABLE");
    }

    private static boolean terminal(JobJournal.State state) {
        return state == JobJournal.State.CANCELLED || state == JobJournal.State.COMPLETE || state == JobJournal.State.FAILED;
    }

    public static final class Conflict extends IOException {}

    @Override public void close() throws IOException {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        worker.shutdownNow();
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            while (!worker.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("EVOLUTION_WORKER_STOP_PENDING");
                try { worker.awaitTermination(remaining,TimeUnit.NANOSECONDS); }
                catch (InterruptedException stop) { interrupted = true; }
            }
        } finally {
            try { journal.close(); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
}
