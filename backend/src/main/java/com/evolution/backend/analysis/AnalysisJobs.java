package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.graph.*;
import com.evolution.backend.query.PublishedReports;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Bounded local job orchestration. Operational IDs/times never enter canonical analysis identities. */
public final class AnalysisJobs implements AutoCloseable {
    public static final String VERSION = "architecture.jobs:m8.1-v1";
    public enum State { QUEUED, RUNNING, CANCELLING, SUCCEEDED, PARTIAL, EMPTY, FAILED, CANCELLED, TIMED_OUT }
    public enum Stage { QUEUED, ACQUISITION, PLATFORM, ANALYSIS, GRAPH, POLICY, PUBLICATION, FINISHED }
    public enum Completion { COMPLETE, PARTIAL, EMPTY, FAILED }
    public enum Stop { USER_CANCELLED, DEADLINE_EXCEEDED, SERVICE_CLOSED, PROVIDER_INTERRUPTED }
    public record Limits(int workers, int queued, int history, long timeoutMillis, long shutdownMillis) {
        public Limits {
            if (workers < 1 || workers > 4 || queued < 1 || queued > 32 || history < workers + queued || history > 256
                    || timeoutMillis < 1 || timeoutMillis > 3_600_000 || shutdownMillis < 1 || shutdownMillis > 5_000)
                throw new IllegalArgumentException("Invalid job limits");
        }
        public static Limits defaults() { return new Limits(1, 4, 64, 900_000, 1_000); }
    }
    /** Public setup metadata contains identities and explicit policies, never host paths or source/configuration values. */
    public record Setup(String id, RepositoryIdentity repository, int release, boolean includeTests,
                        Optional<ContentDigest> policy, Optional<String> configurationSpace) {
        public Setup {
            identifier(id); Objects.requireNonNull(repository); Objects.requireNonNull(policy); Objects.requireNonNull(configurationSpace);
            if (release < 8 || release > 26) throw new IllegalArgumentException("Invalid target release");
            configurationSpace.ifPresent(v -> { if (v.length() > 256 || v.isBlank()) throw new IllegalArgumentException("Invalid space"); });
        }
    }
    @FunctionalInterface public interface Runner { Outcome run(Control control) throws Exception; }
    public record Registration(Setup setup, Runner runner) {
        public Registration { Objects.requireNonNull(setup); Objects.requireNonNull(runner); }
    }
    /** Acquisition failures precede snapshot identity. Preserve their original typed provider records without inventing a snapshot. */
    public record InputProblem(VersionedIdentifier provider, ContentDigest acquisition, RepositoryAcquisitionResult.Problem problem) {
        public InputProblem { Objects.requireNonNull(provider); Objects.requireNonNull(acquisition); Objects.requireNonNull(problem); }
    }
    public record Outcome(Completion completion, Optional<KnowledgeGraph> graph, List<PublishedReports.Report> reports,
                          List<CapabilityGapRecord> gaps, Optional<ContentDigest> acquisition, Optional<ContentDigest> sourceReport, List<InputProblem> inputProblems) {
        public Outcome(Completion completion, Optional<KnowledgeGraph> graph, List<PublishedReports.Report> reports,
                       List<CapabilityGapRecord> gaps, Optional<ContentDigest> acquisition, Optional<ContentDigest> sourceReport) {
            this(completion,graph,reports,gaps,acquisition,sourceReport,List.of());
        }
        public Outcome {
            Objects.requireNonNull(completion); Objects.requireNonNull(graph); Objects.requireNonNull(acquisition); Objects.requireNonNull(sourceReport);
            reports = List.copyOf(reports); gaps = gaps.stream().distinct().sorted().toList();
            inputProblems = inputProblems.stream().distinct().sorted(Comparator.comparing(CanonicalJson::write)).toList();
            for (var problem : inputProblems) if (acquisition.isEmpty() || !problem.acquisition().equals(acquisition.orElseThrow())) throw new IllegalArgumentException("Foreign acquisition problem");
            if (reports.size() > 32 || graph.isEmpty() && (!reports.isEmpty() || completion == Completion.COMPLETE || completion == Completion.PARTIAL))
                throw new IllegalArgumentException("Invalid analysis output");
            if (graph.isPresent()) {
                var g = graph.orElseThrow();
                for (var report : reports) {
                    var partition = g.contexts().stream().filter(c -> c.identity().equals(report.evaluation().context())).findFirst();
                    if (!report.evaluation().graph().equals(g.identity()) || partition.isEmpty())
                        throw new IllegalArgumentException("Foreign report output");
                    var context = partition.orElseThrow();
                    if (report.metrics().isPresent() && (!report.metrics().orElseThrow().context().snapshot().equals(context.snapshot())
                            || !report.metrics().orElseThrow().context().analysis().equals(context.analysis()))) throw new IllegalArgumentException("Foreign metric provenance");
                    if (report.evaluation().findings().stream().anyMatch(f -> !f.bundle().snapshot().equals(context.snapshot()) || !f.bundle().analysis().equals(context.analysis())))
                        throw new IllegalArgumentException("Foreign finding provenance");
                }
                for (var gap : gaps) if (!gap.context().snapshotIdentity().equals(g.snapshot()))
                    throw new IllegalArgumentException("Foreign gap output");
            }
        }
    }
    public record Result(Completion completion, Optional<ContentDigest> graph, Optional<SnapshotIdentity> snapshot,
                         List<AnalysisIdentity> analyses, List<String> contexts, List<ContentDigest> reports,
                         int gapCount, int graphGapCount, int jobGapCount, int inputProblemCount, Optional<ContentDigest> acquisition, Optional<ContentDigest> sourceReport) {}
    public record Event(Stage stage, Instant at) {}
    public record View(String id, String setup, State state, Stage stage, long revision, Instant submittedAt,
                       Optional<Instant> startedAt, Optional<Instant> finishedAt, long elapsedMillis,
                       Optional<Stop> stop, Optional<String> error, List<Event> events, Optional<Result> result) {}
    public record Page<T>(List<T> items, int total, Optional<String> continuation) {}
    public record Submission(View job, boolean created) {}
    public static final class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
    public final class Control implements BooleanSupplier {
        private final Job job;
        private Control(Job job) { this.job = job; }
        @Override public boolean getAsBoolean() { return job.stop != null || Thread.currentThread().isInterrupted(); }
        public void checkpoint() { if (getAsBoolean()) throw new CancellationException(); }
        public void stage(Stage stage) {
            synchronized (AnalysisJobs.this) {
                checkpoint();
                if (stage == Stage.FINISHED || stage.ordinal() <= job.stage.ordinal()) throw new IllegalArgumentException("Invalid progress transition");
                job.stage = stage; job.events.add(new Event(stage, Instant.now())); job.revision++;
            }
        }
    }
    private static final class Job {
        final UUID id = UUID.randomUUID(); final Registration registration; final int sequence;
        final Instant submitted = Instant.now(); final List<Event> events = new ArrayList<>();
        final long submittedNanos = System.nanoTime(); long finishedNanos;
        State state = State.QUEUED; Stage stage = Stage.QUEUED; long revision = 1;
        volatile Stop stop; Instant started, finished; Thread thread; Runnable task;
        ScheduledFuture<?> deadline; Result result; String error;
        List<CapabilityGapRecord> gaps = List.of();
        List<InputProblem> inputProblems = List.of();
        Job(Registration registration, int sequence) { this.registration = registration; this.sequence = sequence; events.add(new Event(stage, submitted)); }
    }
    private final SortedMap<String, Registration> setups = new TreeMap<>();
    private final LinkedHashMap<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<String, Job> retries = new HashMap<>();
    private final UUID session = UUID.randomUUID();
    private final GraphStore graphs; private final PublishedReports reports; private final Limits limits;
    private final ThreadPoolExecutor executor; private final ScheduledThreadPoolExecutor deadlines;
    private boolean closed;
    public AnalysisJobs(List<Registration> registrations, GraphStore graphs, PublishedReports reports, Limits limits) {
        this.graphs = Objects.requireNonNull(graphs); this.reports = Objects.requireNonNull(reports); this.limits = Objects.requireNonNull(limits);
        if (registrations.size() > 32) throw new IllegalArgumentException("Setup capacity exceeded");
        for (var registration : registrations) if (setups.putIfAbsent(registration.setup().id(), registration) != null)
            throw new IllegalArgumentException("Duplicate setup");
        executor = new ThreadPoolExecutor(limits.workers(), limits.workers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(limits.queued()), runnable -> daemon(runnable, "architecture-analysis"), new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, runnable -> daemon(runnable, "architecture-analysis-deadlines"));
        deadlines.setRemoveOnCancelPolicy(true);
    }
    private static Thread daemon(Runnable runnable, String name) { var thread = new Thread(runnable, name); thread.setDaemon(true); return thread; }
    public Limits limits() { return limits; }
    public boolean uses(GraphStore graphs, PublishedReports reports) { return this.graphs == graphs && this.reports == reports; }
    public synchronized List<Setup> setups() { return setups.values().stream().map(Registration::setup).toList(); }
    /** Only an explicitly enabled local connection adapter may add immutable selections. */
    public synchronized void register(Registration registration) {
        if (closed) throw new Rejected("JOBS_CLOSED");
        if (setups.size() >= 32) throw new Rejected("SETUP_CAPACITY");
        if (setups.putIfAbsent(registration.setup().id(),registration) != null) throw new IllegalArgumentException("Duplicate setup");
    }
    public synchronized Submission submit(String setup, String idempotencyKey) {
        identifier(setup); identifier(idempotencyKey);
        if (closed) throw new Rejected("JOBS_CLOSED");
        var existing = retries.get(idempotencyKey);
        if (existing != null) {
            if (!existing.registration.setup().id().equals(setup)) throw new Rejected("IDEMPOTENCY_CONFLICT");
            return new Submission(view(existing), false);
        }
        var registration = setups.get(setup);
        if (registration == null) throw new Rejected("SETUP_NOT_FOUND");
        if (jobs.size() >= limits.history()) throw new Rejected("JOB_HISTORY_FULL");
        var job = new Job(registration, jobs.size() + 1); job.task = () -> execute(job);
        // The monitor prevents the worker from observing a job before admission is committed.
        try { executor.execute(job.task); }
        catch (RejectedExecutionException full) { throw new Rejected("JOB_QUEUE_FULL"); }
        jobs.put(job.id, job); retries.put(idempotencyKey, job);
        return new Submission(view(job), true);
    }
    public synchronized View get(UUID id) { return view(find(id)); }
    public synchronized View cancel(UUID id) { var job = find(id); stop(job, Stop.USER_CANCELLED); return view(job); }
    private Job find(UUID id) { var job = jobs.get(Objects.requireNonNull(id)); if (job == null) throw new Rejected("JOB_NOT_FOUND"); return job; }
    private void stop(Job job, Stop reason) {
        if (terminal(job.state) || job.stop != null) return;
        job.stop = reason; job.revision++;
        if (job.state == State.QUEUED) { executor.remove(job.task); finish(job, State.CANCELLED); }
        else { job.state = State.CANCELLING; if (job.thread != null) job.thread.interrupt(); }
    }
    private void execute(Job job) {
        synchronized (this) {
            if (terminal(job.state)) return;
            job.thread = Thread.currentThread(); job.state = State.RUNNING; job.started = Instant.now(); job.revision++;
            job.deadline = deadlines.schedule(() -> { synchronized (AnalysisJobs.this) { stop(job, Stop.DEADLINE_EXCEEDED); } }, limits.timeoutMillis(), TimeUnit.MILLISECONDS);
        }
        var control = new Control(job);
        try {
            var outcome = job.registration.runner().run(control); control.checkpoint();
            control.stage(Stage.PUBLICATION);
            // Storage observes the stop token. Cancellation can still win until the publication transaction below.
            outcome.graph().ifPresent(graph -> graphs.save(graph, control));
            synchronized (this) {
                control.checkpoint();
                var ids = reports.publishAll(outcome.reports());
                var graph = outcome.graph();
                var completion = outcome.completion();
                var graphGaps = new TreeSet<String>(); graph.stream().flatMap(g -> g.nodes().stream()).filter(n -> n.kind().equals("CAPABILITY_GAP")).forEach(n -> graphGaps.add(n.identity()));
                var allGaps = new TreeSet<>(graphGaps); outcome.gaps().forEach(g -> allGaps.add(g.gapIdentity().value()));
                if (completion == Completion.COMPLETE && !allGaps.isEmpty()) completion = Completion.PARTIAL;
                job.gaps = outcome.gaps();
                job.inputProblems = outcome.inputProblems();
                job.result = new Result(completion, graph.map(KnowledgeGraph::identity), graph.map(KnowledgeGraph::snapshot),
                        graph.stream().flatMap(g -> g.contexts().stream()).flatMap(c -> c.analysis().stream()).distinct().sorted().toList(),
                        graph.stream().flatMap(g -> g.contexts().stream()).map(KnowledgeGraph.Context::identity).sorted().toList(), ids,
                        allGaps.size(), graphGaps.size(), job.gaps.size(), job.inputProblems.size(), outcome.acquisition(), outcome.sourceReport());
                finish(job, switch (completion) { case COMPLETE -> State.SUCCEEDED; case PARTIAL -> State.PARTIAL; case EMPTY -> State.EMPTY; case FAILED -> State.FAILED; });
                if (completion == Completion.FAILED) job.error = "ANALYSIS_FAILED";
            }
        } catch (Exception | LinkageError | AssertionError failure) {
            synchronized (this) {
                if (job.stop == null && (failure instanceof InterruptedException || failure instanceof CancellationException)) job.stop = Stop.PROVIDER_INTERRUPTED;
                if (job.stop != null) finish(job, job.stop == Stop.DEADLINE_EXCEEDED ? State.TIMED_OUT : State.CANCELLED);
                else { job.error = failure instanceof GraphStoreException ? "RESULT_STORAGE_FAILED" : job.stage == Stage.PUBLICATION ? "RESULT_PUBLICATION_FAILED" : "ANALYSIS_FAILED"; finish(job, State.FAILED); }
            }
        } finally {
            synchronized (this) { if (job.deadline != null) job.deadline.cancel(false); job.thread = null; }
            Thread.interrupted(); // Never transfer this job's interruption to the next queued job.
        }
    }
    private void finish(Job job, State state) {
        job.state = state; job.stage = Stage.FINISHED; job.finished = Instant.now(); job.finishedNanos = System.nanoTime(); job.events.add(new Event(Stage.FINISHED, job.finished)); job.revision++;
    }
    public static boolean terminal(State state) { return state != State.QUEUED && state != State.RUNNING && state != State.CANCELLING; }
    private View view(Job job) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis((job.finished == null ? System.nanoTime() : job.finishedNanos) - job.submittedNanos);
        return new View(job.id.toString(), job.registration.setup().id(), job.state, job.stage, job.revision, job.submitted,
                Optional.ofNullable(job.started), Optional.ofNullable(job.finished), Math.max(0, elapsed), Optional.ofNullable(job.stop),
                Optional.ofNullable(job.error), List.copyOf(job.events), Optional.ofNullable(job.result));
    }
    /** A high-water mark freezes membership. Live state/revisions remain explicitly operational. */
    public synchronized Page<View> history(int size, Optional<String> cursor) {
        checkSize(size); int high = jobs.size(), offset = 0;
        if (cursor.isPresent()) { var decoded = cursor(cursor.orElseThrow(), "history", size); high = decoded[0]; offset = decoded[1]; }
        final int bound = high;
        var members = jobs.values().stream().filter(j -> j.sequence <= bound).toList();
        if (high > jobs.size() || offset >= members.size() && offset != 0) throw new IllegalArgumentException("Invalid history cursor");
        var items = members.stream().skip(offset).limit(size).map(this::view).toList(); int next = offset + items.size();
        return new Page<>(items, high, next < high ? Optional.of(cursor("history", size, high, next)) : Optional.empty());
    }
    public synchronized Page<CapabilityGapRecord> gaps(UUID id, int size, Optional<String> cursor) {
        checkSize(size); var job = find(id);
        if (!terminal(job.state)) throw new Rejected("JOB_NOT_FINISHED");
        int offset = 0;
        if (cursor.isPresent()) { var decoded = cursor(cursor.orElseThrow(), id.toString(), size); if (decoded[0] != job.gaps.size()) throw new IllegalArgumentException("Invalid gap cursor"); offset = decoded[1]; }
        if (offset >= job.gaps.size() && offset != 0) throw new IllegalArgumentException("Invalid gap cursor");
        var items = job.gaps.stream().skip(offset).limit(size).toList(); int next = offset + items.size();
        return new Page<>(items, job.gaps.size(), next < job.gaps.size() ? Optional.of(cursor(id.toString(), size, job.gaps.size(), next)) : Optional.empty());
    }
    public synchronized Page<InputProblem> inputProblems(UUID id, int size, Optional<String> cursor) {
        checkSize(size); var job = find(id);
        if (!terminal(job.state)) throw new Rejected("JOB_NOT_FINISHED");
        String operation = id + "/input"; int offset = 0;
        if (cursor.isPresent()) { var decoded = cursor(cursor.orElseThrow(),operation,size); if (decoded[0] != job.inputProblems.size()) throw new IllegalArgumentException("Invalid input cursor"); offset = decoded[1]; }
        if (offset >= job.inputProblems.size() && offset != 0) throw new IllegalArgumentException("Invalid input cursor");
        var items = job.inputProblems.stream().skip(offset).limit(size).toList(); int next = offset + items.size();
        return new Page<>(items,job.inputProblems.size(),next < job.inputProblems.size() ? Optional.of(cursor(operation,size,job.inputProblems.size(),next)) : Optional.empty());
    }
    private void checkSize(int size) { if (size < 1 || size > 50) throw new IllegalArgumentException("Invalid page size"); }
    private String cursor(String operation, int size, int high, int offset) {
        String prefix = session + ":" + operation + ":" + size + ":" + high + ":" + offset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString((prefix + ":" + ContentDigest.sha256Utf8(prefix).value().substring(7)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private int[] cursor(String encoded, String operation, int size) {
        if (encoded.length() > 512) throw new IllegalArgumentException("Invalid cursor");
        String raw = new String(Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8); var parts = raw.split(":", -1);
        if (parts.length != 6 || !parts[0].equals(session.toString()) || !parts[1].equals(operation) || !parts[2].equals(Integer.toString(size))) throw new IllegalArgumentException("Foreign cursor");
        int high = Integer.parseInt(parts[3]), offset = Integer.parseInt(parts[4]);
        if (high < 0 || offset < 1 || !cursor(operation, size, high, offset).equals(encoded)) throw new IllegalArgumentException("Invalid cursor");
        return new int[]{high, offset};
    }
    public static void identifier(String value) { if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new IllegalArgumentException("Invalid identifier"); }
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; for (var job : jobs.values()) stop(job, Stop.SERVICE_CLOSED); }
        executor.shutdownNow(); deadlines.shutdownNow();
        try { executor.awaitTermination(limits.shutdownMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
