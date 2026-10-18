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
import java.io.IOException;

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
                    || timeoutMillis < 0 || shutdownMillis < 1 || shutdownMillis > 5_000)
                throw new IllegalArgumentException("Invalid job limits");
        }
        public static Limits defaults() { return new Limits(1, 4, 64, 0, 1_000); }
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
        public String id() { return job.id.toString(); }
        public void stage(Stage stage) {
            synchronized (AnalysisJobs.this) {
                checkpoint();
                if (stage == Stage.FINISHED || stage.ordinal() <= job.stage.ordinal()) throw new IllegalArgumentException("Invalid progress transition");
                job.stage = stage; job.events.add(new Event(stage, Instant.now())); job.revision++;
                persist(job);
            }
        }
    }
    private static final class Job {
        final UUID id; final Registration registration; final int sequence;
        final Instant submitted; final List<Event> events = new ArrayList<>();
        String retry;
        final long submittedNanos = System.nanoTime(); long finishedNanos;
        State state = State.QUEUED; Stage stage = Stage.QUEUED; long revision = 1;
        volatile Stop stop; Instant started, finished; Thread thread; Runnable task;
        ScheduledFuture<?> deadline; Result result; String error;
        List<CapabilityGapRecord> gaps = List.of();
        List<InputProblem> inputProblems = List.of();
        Job(Registration registration, int sequence) { this(registration,sequence,null); }
        Job(Registration registration,int sequence,AnalysisJobDiskStore.Saved previous){
            this.registration=registration;this.sequence=sequence;this.id=previous==null?UUID.randomUUID():UUID.fromString(previous.view().id());this.submitted=previous==null?Instant.now():previous.view().submittedAt();
            if(previous!=null)this.revision=previous.view().revision()+1;events.add(new Event(stage,Instant.now()));
        }
    }
    private final SortedMap<String, Registration> setups = new TreeMap<>();
    private final LinkedHashMap<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<String, Job> retries = new HashMap<>();
    private final UUID session = UUID.randomUUID();
    private final GraphStore graphs; private final PublishedReports reports; private final Limits limits;
    private final AnalysisJobDiskStore disk;
    private final ThreadPoolExecutor executor; private final ScheduledThreadPoolExecutor deadlines;
    private boolean closed;
    public AnalysisJobs(List<Registration> registrations, GraphStore graphs, PublishedReports reports, Limits limits) {
        this(registrations,graphs,reports,limits,null);
    }
    public AnalysisJobs(List<Registration> registrations,GraphStore graphs,PublishedReports reports,Limits limits,AnalysisJobDiskStore disk){
        this.graphs = Objects.requireNonNull(graphs); this.reports = Objects.requireNonNull(reports); this.limits = Objects.requireNonNull(limits);
        this.disk=disk;
        if (registrations.size() > 32) throw new IllegalArgumentException("Setup capacity exceeded");
        for (var registration : registrations) if (setups.putIfAbsent(registration.setup().id(), registration) != null)
            throw new IllegalArgumentException("Duplicate setup");
        executor = new ThreadPoolExecutor(limits.workers(), limits.workers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(limits.queued()), runnable -> daemon(runnable, "architecture-analysis"), new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, runnable -> daemon(runnable, "architecture-analysis-deadlines"));
        deadlines.setRemoveOnCancelPolicy(true);
        if(disk!=null){try{for(var registration:setups.values())disk.recover(registration.setup());}catch(IOException failure){executor.shutdownNow();deadlines.shutdownNow();throw new Rejected("JOB_STORAGE_UNAVAILABLE");}dispatchPending();}
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
        if(disk!=null){try{disk.recover(registration.setup());}catch(IOException failure){throw new Rejected("JOB_STORAGE_UNAVAILABLE");}dispatchPending();}
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
        AnalysisJobDiskStore.Saved previous=null;
        if(disk!=null)try{
            previous=disk.retry(idempotencyKey).orElse(null);
            if(previous!=null){if(!previous.view().setup().equals(setup)||!disk.intent(UUID.fromString(previous.view().id())).equals(registration.setup()))throw new Rejected("IDEMPOTENCY_CONFLICT");if(terminal(previous.view().state()))return new Submission(previous.view(),false);}
        }catch(IOException failure){throw new Rejected("JOB_STORAGE_UNAVAILABLE");}
        if (disk==null&&jobs.size() >= limits.history()) throw new Rejected("JOB_HISTORY_FULL");
        var job = new Job(registration, jobs.size() + 1,previous);job.retry=idempotencyKey; job.task = () -> execute(job);
        persist(job);
        // The monitor prevents the worker from observing a job before admission is committed.
        try { executor.execute(job.task); jobs.put(job.id, job); retries.put(idempotencyKey, job); }
        catch (RejectedExecutionException full) { if(disk==null)throw new Rejected("JOB_QUEUE_FULL"); }
        return new Submission(view(job), true);
    }
    public synchronized View get(UUID id) { var active=jobs.get(id);return active!=null?view(active):saved(id).view(); }
    /** Explicit v2 continuation; ordinary retry keys keep their original idempotent meaning. */
    public synchronized View resume(UUID id){
        if(closed)throw new Rejected("JOBS_CLOSED");var active=jobs.get(id);AnalysisJobDiskStore.Saved previous;
        if(active!=null){if(!terminal(active.state))return view(active);previous=new AnalysisJobDiskStore.Saved("analysis-job-storage:m8.4-v2",active.retry,view(active),active.gaps,active.inputProblems);}
        else previous=saved(id);
        var registration=setups.get(previous.view().setup());if(registration==null)throw new Rejected("SETUP_NOT_FOUND");
        if(disk!=null)try{if(!disk.intent(id).equals(registration.setup()))throw new Rejected("SETUP_CONFIGURATION_CHANGED");}catch(IOException failure){throw new Rejected("JOB_STORAGE_UNAVAILABLE");}
        if(previous.view().state()==State.SUCCEEDED||previous.view().state()==State.PARTIAL||previous.view().state()==State.EMPTY)throw new Rejected("JOB_ALREADY_PUBLISHED");
        var job=new Job(registration,jobs.size()+1,previous);job.retry=previous.retry();job.task=()->execute(job);persist(job);
        try{executor.execute(job.task);}catch(RejectedExecutionException full){if(disk==null)throw new Rejected("JOB_QUEUE_FULL");return view(job);}
        jobs.put(id,job);retries.put(job.retry,job);return view(job);
    }
    public synchronized View cancel(UUID id) { var job=jobs.get(id);if(job==null){var previous=saved(id);var v=previous.view();if(terminal(v.state()))return v;
        var next=new View(v.id(),v.setup(),State.CANCELLED,Stage.FINISHED,v.revision()+1,v.submittedAt(),v.startedAt(),Optional.of(Instant.now()),v.elapsedMillis(),Optional.of(Stop.USER_CANCELLED),Optional.empty(),v.events(),v.result());
        try{disk.save(previous.retry(),disk.intent(id),next,previous.gaps(),previous.inputProblems());}catch(IOException failure){throw new Rejected("JOB_STORAGE_UNAVAILABLE");}return next;}
        stop(job, Stop.USER_CANCELLED); return view(job); }
    private AnalysisJobDiskStore.Saved saved(UUID id){if(disk==null)throw new Rejected("JOB_NOT_FOUND");try{return disk.get(id).orElseThrow(()->new Rejected("JOB_NOT_FOUND"));}catch(IOException failure){throw new Rejected("JOB_STORAGE_UNAVAILABLE");}}
    private AnalysisJobDiskStore.Saved savedLedgers(UUID id){if(disk==null)throw new Rejected("JOB_NOT_FOUND");try{return disk.get(id,true).orElseThrow(()->new Rejected("JOB_NOT_FOUND"));}catch(IOException failure){throw new Rejected("JOB_STORAGE_UNAVAILABLE");}}
    private void persist(Job job){if(disk!=null){boolean interrupted=Thread.interrupted();try{disk.save(job.retry,job.registration.setup(),view(job),job.gaps,job.inputProblems);}catch(IOException failure){throw new Rejected("JOB_STORAGE_UNAVAILABLE");}finally{if(interrupted)Thread.currentThread().interrupt();}}}
    private synchronized void dispatchPending(){
        if(disk==null||closed)return;
        try{while(jobs.size()<limits.workers()+limits.queued()){
            AnalysisJobDiskStore.Saved candidate=null;
            for(var setup:setups.keySet())for(var saved:disk.queued(setup))if(!jobs.containsKey(UUID.fromString(saved.view().id()))){if(candidate==null||saved.view().submittedAt().isBefore(candidate.view().submittedAt()))candidate=saved;break;}
            if(candidate==null)return;var selected=candidate;var registration=setups.get(selected.view().setup());var intended=disk.intent(UUID.fromString(selected.view().id()));
            if(!intended.equals(registration.setup())){var v=selected.view();var rejected=new View(v.id(),v.setup(),State.FAILED,Stage.FINISHED,v.revision()+1,v.submittedAt(),v.startedAt(),Optional.of(Instant.now()),v.elapsedMillis(),Optional.empty(),Optional.of("SETUP_CONFIGURATION_CHANGED"),v.events(),v.result());disk.save(selected.retry(),intended,rejected,selected.gaps(),selected.inputProblems());continue;}
            var job=new Job(registration,jobs.size()+1,selected);job.retry=selected.retry();job.task=()->execute(job);
            try{executor.execute(job.task);}catch(RejectedExecutionException full){return;}jobs.put(job.id,job);retries.put(job.retry,job);
        }}catch(IOException unavailable){throw new Rejected("JOB_STORAGE_UNAVAILABLE");}
    }
    private Job find(UUID id) { var job = jobs.get(Objects.requireNonNull(id)); if (job == null) throw new Rejected("JOB_NOT_FOUND"); return job; }
    private void stop(Job job, Stop reason) {
        if (terminal(job.state) || job.stop != null) return;
        job.stop = reason; job.revision++;
        if (job.state == State.QUEUED) { executor.remove(job.task); finish(job, State.CANCELLED); }
        else { job.state = State.CANCELLING; if (job.thread != null) job.thread.interrupt(); }
        persist(job);
    }
    private void execute(Job job) {
        var control = new Control(job);
        try {
        synchronized (this) {
            if (terminal(job.state)) return;
            job.thread = Thread.currentThread(); job.state = State.RUNNING; job.started = Instant.now(); job.revision++;
            persist(job);
            if(limits.timeoutMillis()>0)job.deadline = deadlines.schedule(() -> { synchronized (AnalysisJobs.this) { stop(job, Stop.DEADLINE_EXCEEDED); } }, limits.timeoutMillis(), TimeUnit.MILLISECONDS);
        }
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
                if (completion == Completion.FAILED) job.error = "ANALYSIS_FAILED";
                finish(job, switch (completion) { case COMPLETE -> State.SUCCEEDED; case PARTIAL -> State.PARTIAL; case EMPTY -> State.EMPTY; case FAILED -> State.FAILED; });
            }
        } catch (Exception | LinkageError | AssertionError | OutOfMemoryError failure) {
            synchronized (this) {
                if (job.stop == null && (failure instanceof InterruptedException || failure instanceof CancellationException)) job.stop = Stop.PROVIDER_INTERRUPTED;
                if (job.stop != null) finish(job, job.stop == Stop.DEADLINE_EXCEEDED ? State.TIMED_OUT : State.CANCELLED);
                else { job.error = failure instanceof GraphStoreException ? "RESULT_STORAGE_FAILED" : failure instanceof OutOfMemoryError||failure instanceof IOException&&"COMPATIBILITY_WORKER_REQUIRED".equals(failure.getMessage())?"SEMANTIC_WORKER_REQUIRED":job.stage == Stage.PUBLICATION ? "RESULT_PUBLICATION_FAILED" : "ANALYSIS_FAILED"; finish(job, State.FAILED); }
            }
        } finally {
            synchronized (this) { if (job.deadline != null) job.deadline.cancel(false); job.thread = null; }
            Thread.interrupted(); // Never transfer this job's interruption to the next queued job.
        }
    }
    private void finish(Job job, State state) {
        job.state = state; job.stage = Stage.FINISHED; job.finished = Instant.now(); job.finishedNanos = System.nanoTime(); job.events.add(new Event(Stage.FINISHED, job.finished)); job.revision++;
        try{persist(job);}catch(Rejected unavailable){job.state=State.FAILED;job.error="JOB_STORAGE_UNAVAILABLE";job.result=null;return;}
        if(disk!=null){jobs.remove(job.id);retries.remove(job.retry);try{dispatchPending();}catch(Rejected unavailable){/* Accepted queued receipts remain durable until storage recovers. */}}
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
        if(disk!=null){
            checkSize(size);long after=0,high=0;
            if(cursor.isPresent()){
                String encoded=cursor.orElseThrow();if(encoded.length()>512)throw new IllegalArgumentException("Invalid durable history cursor");
                String[] parts=new String(Base64.getUrlDecoder().decode(encoded),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);
                if(parts.length!=5||!parts[0].equals(disk.namespace())||!parts[1].equals(Integer.toString(size)))throw new IllegalArgumentException("Foreign durable history cursor");
                high=Long.parseLong(parts[2]);after=Long.parseLong(parts[3]);if(high<1||after<1||after>high||!durableCursor(size,high,after).equals(encoded))throw new IllegalArgumentException("Invalid durable history cursor");
            }
            try{var page=disk.history(after,size,high);if(page.total()>Integer.MAX_VALUE)throw new Rejected("V2_HISTORY_REQUIRED");return new Page<>(page.items().stream().map(saved->{var active=jobs.get(UUID.fromString(saved.view().id()));return active==null?saved.view():view(active);}).toList(),(int)page.total(),page.continuation().map(next->durableCursor(size,page.highWaterSequence(),next)));}
            catch(IOException failure){throw new Rejected("JOB_STORAGE_UNAVAILABLE");}
        }
        checkSize(size); int high = jobs.size(), offset = 0;
        if (cursor.isPresent()) { var decoded = cursor(cursor.orElseThrow(), "history", size); high = decoded[0]; offset = decoded[1]; }
        final int bound = high;
        var members = jobs.values().stream().filter(j -> j.sequence <= bound).toList();
        if (high > jobs.size() || offset >= members.size() && offset != 0) throw new IllegalArgumentException("Invalid history cursor");
        var items = members.stream().skip(offset).limit(size).map(this::view).toList(); int next = offset + items.size();
        return new Page<>(items, high, next < high ? Optional.of(cursor("history", size, high, next)) : Optional.empty());
    }
    public synchronized Page<CapabilityGapRecord> gaps(UUID id, int size, Optional<String> cursor) {
        if(disk!=null&&!jobs.containsKey(id))return savedPage(savedLedgers(id).gaps(),id.toString(),size,cursor);
        checkSize(size); var job = find(id);
        if (!terminal(job.state)) throw new Rejected("JOB_NOT_FINISHED");
        int offset = 0;
        if (cursor.isPresent()) { var decoded = cursor(cursor.orElseThrow(), id.toString(), size); if (decoded[0] != job.gaps.size()) throw new IllegalArgumentException("Invalid gap cursor"); offset = decoded[1]; }
        if (offset >= job.gaps.size() && offset != 0) throw new IllegalArgumentException("Invalid gap cursor");
        var items = job.gaps.stream().skip(offset).limit(size).toList(); int next = offset + items.size();
        return new Page<>(items, job.gaps.size(), next < job.gaps.size() ? Optional.of(cursor(id.toString(), size, job.gaps.size(), next)) : Optional.empty());
    }
    public synchronized Page<InputProblem> inputProblems(UUID id, int size, Optional<String> cursor) {
        if(disk!=null&&!jobs.containsKey(id))return savedPage(savedLedgers(id).inputProblems(),id+"/input",size,cursor);
        checkSize(size); var job = find(id);
        if (!terminal(job.state)) throw new Rejected("JOB_NOT_FINISHED");
        String operation = id + "/input"; int offset = 0;
        if (cursor.isPresent()) { var decoded = cursor(cursor.orElseThrow(),operation,size); if (decoded[0] != job.inputProblems.size()) throw new IllegalArgumentException("Invalid input cursor"); offset = decoded[1]; }
        if (offset >= job.inputProblems.size() && offset != 0) throw new IllegalArgumentException("Invalid input cursor");
        var items = job.inputProblems.stream().skip(offset).limit(size).toList(); int next = offset + items.size();
        return new Page<>(items,job.inputProblems.size(),next < job.inputProblems.size() ? Optional.of(cursor(operation,size,job.inputProblems.size(),next)) : Optional.empty());
    }
    private void checkSize(int size) { if (size < 1 || size > 50) throw new IllegalArgumentException("Invalid page size"); }
    private String durableCursor(int size,long high,long after){String prefix=disk.namespace()+"|"+size+"|"+high+"|"+after;return Base64.getUrlEncoder().withoutPadding().encodeToString((prefix+"|"+ContentDigest.sha256Utf8(prefix).value().substring(7)).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private <T>Page<T> savedPage(List<T> items,String operation,int size,Optional<String> cursor){
        checkSize(size);int offset=0;if(cursor.isPresent()){var decoded=cursor(cursor.orElseThrow(),operation,size);if(decoded[0]!=items.size())throw new IllegalArgumentException("Invalid cursor");offset=decoded[1];}
        if(offset>=items.size()&&offset!=0)throw new IllegalArgumentException("Invalid cursor");var selected=items.stream().skip(offset).limit(size).toList();int next=offset+selected.size();return new Page<>(selected,items.size(),next<items.size()?Optional.of(cursor(operation,size,items.size(),next)):Optional.empty());
    }
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
        synchronized (this) { if (closed) return; closed = true; for (var job : List.copyOf(jobs.values())) stop(job, Stop.SERVICE_CLOSED); }
        executor.shutdownNow(); deadlines.shutdownNow();
        try { executor.awaitTermination(limits.shutdownMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
