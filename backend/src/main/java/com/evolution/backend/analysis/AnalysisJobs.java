package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.graph.*;
import com.evolution.backend.query.PublishedReports;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Bounded local job orchestration with progressive 3-stage preview telemetry, live SSE events,
 * and durable m8.4-v5 receipts. Operational IDs/times never enter canonical analysis identities.
 */
public final class AnalysisJobs implements AutoCloseable {
    public static final String VERSION = "architecture.jobs:m8.1-v1";
    private static final System.Logger LOG = System.getLogger(AnalysisJobs.class.getName());

    public enum State {
        QUEUED, RUNNING, CANCELLING, SUCCEEDED, PARTIAL, EMPTY, FAILED, CANCELLED, TIMED_OUT
    }

    public enum Stage {
        QUEUED, ACQUISITION, PLATFORM, ANALYSIS, GRAPH, POLICY, PUBLICATION, REUSED, FINISHED
    }

    public enum Completion {
        COMPLETE, PARTIAL, EMPTY, FAILED
    }

    public enum Stop {
        USER_CANCELLED, DEADLINE_EXCEEDED, SERVICE_CLOSED, PROVIDER_INTERRUPTED
    }

    public enum ReuseDecision {
        INPUTS_UNVERIFIED, NO_MATCH, RESULT_UNAVAILABLE, BYPASSED, REUSED
    }

    public enum TruthTier {
        NONE, PRELIMINARY_STRUCTURE, PARTIAL_CONTEXTS, DEEP_SEMANTIC_COMPLETE
    }

    public record Limits(int workers, int queued, int history, long timeoutMillis, long shutdownMillis) {
        public Limits {
            if (workers < 1 || workers > 4 || queued < 1 || queued > 32 || history < workers + queued || history > 256
                    || timeoutMillis < 0 || shutdownMillis < 1 || shutdownMillis > 5_000)
                throw new IllegalArgumentException("Invalid job limits");
        }

        public static Limits defaults() {
            return new Limits(1, 4, 64, 0, 1_000);
        }
    }

    /**
     * Public setup metadata contains identities and explicit policies, never host
     * paths or source/configuration values.
     */
    public record Setup(String id, RepositoryIdentity repository, int release, boolean includeTests,
            Optional<ContentDigest> policy, Optional<String> configurationSpace,
            Optional<ContentDigest> artifactPolicy) {
        public Setup(String id, RepositoryIdentity repository, int release, boolean includeTests,
                Optional<ContentDigest> policy, Optional<String> configurationSpace) {
            this(id, repository, release, includeTests, policy, configurationSpace, Optional.empty());
        }

        public Setup {
            identifier(id);
            Objects.requireNonNull(repository);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(configurationSpace);
            Objects.requireNonNull(artifactPolicy);
            if (release < 8 || release > 26)
                throw new IllegalArgumentException("Invalid target release");
            configurationSpace.ifPresent(v -> {
                if (v.length() > 256 || v.isBlank())
                    throw new IllegalArgumentException("Invalid space");
            });
        }
    }

    public record IncrementalMetrics(
            Optional<String> baseSnapshotId,
            int addedFiles,
            int modifiedFiles,
            int deletedFiles,
            int reusedFiles,
            int abiEarlyCutoffCount,
            int impactedDependentsCount,
            int reusedModules,
            long timeSavedEstimateMs) {
        public IncrementalMetrics {
            Objects.requireNonNull(baseSnapshotId, "baseSnapshotId");
            if (addedFiles < 0 || modifiedFiles < 0 || deletedFiles < 0 || reusedFiles < 0
                    || abiEarlyCutoffCount < 0 || impactedDependentsCount < 0 || reusedModules < 0
                    || timeSavedEstimateMs < 0L) {
                throw new IllegalArgumentException("Negative incremental metric");
            }
        }
    }

    public record PreviewPublication(
            ContentDigest graphId,
            TruthTier truthTier,
            int completedContexts,
            int totalContexts,
            List<String> pendingModules,
            Instant publishedAt) {
        public PreviewPublication {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(truthTier, "truthTier");
            pendingModules = List.copyOf(pendingModules == null ? List.of() : pendingModules);
            Objects.requireNonNull(publishedAt, "publishedAt");
        }
    }

    public record ModuleProgress(
            String module,
            String status,
            int filesDone,
            int filesTotal,
            long elapsedMillis,
            Optional<String> contextId,
            Optional<String> reason) {
        public ModuleProgress {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(contextId, "contextId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record LiveProgress(
            String jobId,
            String setup,
            State state,
            Stage stage,
            long revision,
            TruthTier truthTier,
            Optional<ContentDigest> previewGraphId,
            Optional<PreviewPublication> preview,
            BigDecimal overallRatio,
            int stageDone,
            int stageTotal,
            Optional<BigDecimal> filesPerSecond,
            Optional<Long> etaSeconds,
            String etaStatus,
            long elapsedMillis,
            String message,
            List<ModuleProgress> modules,
            Map<String, Long> stageMillis,
            Map<String, Long> cacheMetrics,
            Optional<IncrementalMetrics> incremental) {
        public LiveProgress {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(setup, "setup");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(truthTier, "truthTier");
            Objects.requireNonNull(previewGraphId, "previewGraphId");
            Objects.requireNonNull(preview, "preview");
            Objects.requireNonNull(overallRatio, "overallRatio");
            Objects.requireNonNull(filesPerSecond, "filesPerSecond");
            Objects.requireNonNull(etaSeconds, "etaSeconds");
            Objects.requireNonNull(etaStatus, "etaStatus");
            message = message == null ? "" : message;
            modules = List.copyOf(modules == null ? List.of() : modules);
            stageMillis = Collections.unmodifiableMap(new LinkedHashMap<>(stageMillis == null ? Map.of() : stageMillis));
            cacheMetrics = Collections.unmodifiableMap(new TreeMap<>(cacheMetrics == null ? Map.of() : cacheMetrics));
            Objects.requireNonNull(incremental, "incremental");
        }
    }

    public sealed interface RunResult permits Outcome, Reused {
    }

    record Reused(AnalysisJobDiskStore.Envelope publication) implements RunResult {
    }

    @FunctionalInterface
    public interface Runner {
        RunResult run(Control control) throws Exception;
    }

    public record Registration(Setup setup, Runner runner) {
        public Registration {
            Objects.requireNonNull(setup);
            Objects.requireNonNull(runner);
        }
    }

    /**
     * Acquisition failures precede snapshot identity. Preserve their original typed
     * provider records without inventing a snapshot.
     */
    public record InputProblem(VersionedIdentifier provider, ContentDigest acquisition,
            RepositoryAcquisitionResult.Problem problem) {
        public InputProblem {
            Objects.requireNonNull(provider);
            Objects.requireNonNull(acquisition);
            Objects.requireNonNull(problem);
        }
    }

    public record Outcome(Completion completion, Optional<KnowledgeGraph> graph, List<PublishedReports.Report> reports,
            List<CapabilityGapRecord> gaps, Optional<ContentDigest> acquisition, Optional<ContentDigest> sourceReport,
            List<InputProblem> inputProblems, Optional<ContentDigest> publishedGraph,
            int publishedGraphGapCount) implements RunResult {
        public Outcome(Completion completion, Optional<KnowledgeGraph> graph, List<PublishedReports.Report> reports,
                List<CapabilityGapRecord> gaps, Optional<ContentDigest> acquisition,
                Optional<ContentDigest> sourceReport) {
            this(completion, graph, reports, gaps, acquisition, sourceReport, List.of(), Optional.empty(), 0);
        }

        public Outcome(Completion completion, Optional<KnowledgeGraph> graph, List<PublishedReports.Report> reports,
                List<CapabilityGapRecord> gaps, Optional<ContentDigest> acquisition,
                Optional<ContentDigest> sourceReport,
                List<InputProblem> inputProblems) {
            this(completion, graph, reports, gaps, acquisition, sourceReport, inputProblems, Optional.empty(), 0);
        }

        public Outcome(Completion completion, Optional<KnowledgeGraph> graph, List<PublishedReports.Report> reports,
                List<CapabilityGapRecord> gaps, Optional<ContentDigest> acquisition,
                Optional<ContentDigest> sourceReport,
                List<InputProblem> inputProblems, Optional<ContentDigest> publishedGraph) {
            this(completion, graph, reports, gaps, acquisition, sourceReport, inputProblems, publishedGraph, 0);
        }

        public Outcome {
            Objects.requireNonNull(completion);
            Objects.requireNonNull(graph);
            Objects.requireNonNull(acquisition);
            Objects.requireNonNull(sourceReport);
            Objects.requireNonNull(publishedGraph);
            if (graph.isPresent() && publishedGraph.isPresent()) {
                throw new IllegalArgumentException("Specify either in-memory graph or publishedGraph");
            }
            reports = List.copyOf(reports);
            gaps = gaps.stream().distinct().sorted().toList();
            inputProblems = inputProblems.stream().distinct().sorted(Comparator.comparing(CanonicalJson::write))
                    .toList();
            for (var problem : inputProblems)
                if (acquisition.isEmpty() || !problem.acquisition().equals(acquisition.orElseThrow()))
                    throw new IllegalArgumentException("Foreign acquisition problem");
            boolean hasGraph = graph.isPresent() || publishedGraph.isPresent();
            if (reports.size() > 32 || !hasGraph
                    && (!reports.isEmpty() || completion == Completion.COMPLETE || completion == Completion.PARTIAL))
                throw new IllegalArgumentException("Invalid analysis output");
            if (graph.isPresent()) {
                var g = graph.orElseThrow();
                for (var report : reports) {
                    var partition = g.contexts().stream()
                            .filter(c -> c.identity().equals(report.evaluation().context())).findFirst();
                    if (!report.evaluation().graph().equals(g.identity()) || partition.isEmpty())
                        throw new IllegalArgumentException("Foreign report output");
                    var context = partition.orElseThrow();
                    if (report.metrics().isPresent()
                            && (!report.metrics().orElseThrow().context().snapshot().equals(context.snapshot())
                                    || !report.metrics().orElseThrow().context().analysis().equals(context.analysis())))
                        throw new IllegalArgumentException("Foreign metric provenance");
                    if (report.evaluation().findings().stream()
                            .anyMatch(f -> !f.bundle().snapshot().equals(context.snapshot())
                                    || !f.bundle().analysis().equals(context.analysis())))
                        throw new IllegalArgumentException("Foreign finding provenance");
                }
                for (var gap : gaps)
                    if (!gap.context().snapshotIdentity().equals(g.snapshot())
                            && !isCapturedBaseGap(g, gap))
                        throw new IllegalArgumentException("Foreign gap output");
            }
        }
    }

    private static boolean isCapturedBaseGap(KnowledgeGraph graph, CapabilityGapRecord gap) {
        String base = gap.context().snapshotIdentity().value();
        boolean bound = graph.evidence().stream().filter(e -> e.family().equals("java.evidence.view")).anyMatch(e -> {
            var view = GraphJson.object(GraphJson.read(e.canonicalJson(), GraphJson.Limits.defaults()));
            return graph.snapshot().value().equals(view.get("derivedSnapshot"))
                    && base.equals(GraphJson.object(view.get("scope")).get("baseSnapshot"));
        });
        if (!bound)
            return false;
        boolean captured = graph.evidence().stream().filter(e -> e.family().equals("java.evidence.base-snapshot"))
                .anyMatch(e -> base.equals(GraphJson
                        .object(GraphJson.read(e.canonicalJson(), GraphJson.Limits.defaults())).get("identity")));
        if (!captured)
            return false;
        String raw = CanonicalJson.write(gap);
        return graph.nodes().stream()
                .filter(n -> n.identity().equals(gap.gapIdentity().value()) && n.kind().equals("CAPABILITY_GAP"))
                .flatMap(n -> n.evidence().stream()).map(graph::evidence).flatMap(Optional::stream)
                .anyMatch(e -> e.family().equals("evidence.gap") && e.canonicalJson().equals(raw));
    }

    public record Result(Completion completion, Optional<ContentDigest> graph, Optional<SnapshotIdentity> snapshot,
            List<AnalysisIdentity> analyses, List<String> contexts, List<ContentDigest> reports,
            int gapCount, int graphGapCount, int jobGapCount, int inputProblemCount,
            Optional<ContentDigest> acquisition, Optional<ContentDigest> sourceReport) {
    }

    public record Event(Stage stage, Instant at) {
    }

    public record Reuse(String sourceJob, Instant sourceFinishedAt, Instant verifiedAt) {
        public Reuse {
            if (!UUID.fromString(sourceJob).toString().equals(sourceJob))
                throw new IllegalArgumentException("Invalid source job");
            Objects.requireNonNull(sourceFinishedAt);
            Objects.requireNonNull(verifiedAt);
        }
    }

    public record View(String id, String setup, State state, Stage stage, long revision, Instant submittedAt,
            Optional<Instant> startedAt, Optional<Instant> finishedAt, long elapsedMillis,
            Optional<Stop> stop, Optional<String> error, List<Event> events, Optional<Result> result,
            boolean force, Optional<Reuse> reuse, Optional<ReuseDecision> reuseDecision,
            Optional<AnalysisMode> mode) {
        public View {
            Objects.requireNonNull(reuse);
            Objects.requireNonNull(reuseDecision);
            Objects.requireNonNull(mode);
            if (reuse.isPresent() && (force || stage != Stage.FINISHED || result.isEmpty()
                    || reuse.orElseThrow().sourceJob().equals(id)
                    || (state != State.SUCCEEDED && state != State.PARTIAL)
                    || !reuseDecision.equals(Optional.of(ReuseDecision.REUSED))))
                throw new IllegalArgumentException("Invalid result reuse receipt");
        }

        public View(String id, String setup, State state, Stage stage, long revision, Instant submittedAt,
                Optional<Instant> startedAt, Optional<Instant> finishedAt, long elapsedMillis,
                Optional<Stop> stop, Optional<String> error, List<Event> events, Optional<Result> result) {
            this(id, setup, state, stage, revision, submittedAt, startedAt, finishedAt, elapsedMillis, stop, error,
                    events, result, false, Optional.empty(), Optional.empty(), Optional.empty());
        }

        public View(String id, String setup, State state, Stage stage, long revision, Instant submittedAt,
                Optional<Instant> startedAt, Optional<Instant> finishedAt, long elapsedMillis,
                Optional<Stop> stop, Optional<String> error, List<Event> events, Optional<Result> result,
                boolean force, Optional<Reuse> reuse, Optional<ReuseDecision> reuseDecision) {
            this(id, setup, state, stage, revision, submittedAt, startedAt, finishedAt, elapsedMillis, stop, error,
                    events, result, force, reuse, reuseDecision, Optional.empty());
        }
    }

    public record Page<T>(List<T> items, int total, Optional<String> continuation) {
    }

    public record Submission(View job, boolean created) {
    }

    public record StageTiming(Stage stage, long durationMillis, Instant startedAt, Optional<Instant> endedAt) {
    }

    public record Telemetry(String jobId, String setup, Optional<AnalysisMode> mode, State state, Stage currentStage,
            boolean reused, Optional<ReuseDecision> reuseDecision, long totalElapsedMillis,
            Map<String, Long> stageMillis, List<StageTiming> stages, Map<String, Long> cacheMetrics,
            Optional<IncrementalMetrics> incremental) {
        public Telemetry(String jobId, String setup, Optional<AnalysisMode> mode, State state, Stage currentStage,
                boolean reused, Optional<ReuseDecision> reuseDecision, long totalElapsedMillis,
                Map<String, Long> stageMillis, List<StageTiming> stages) {
            this(jobId, setup, mode, state, currentStage, reused, reuseDecision, totalElapsedMillis, stageMillis,
                    stages, Map.of(), Optional.empty());
        }

        public Telemetry(String jobId, String setup, Optional<AnalysisMode> mode, State state, Stage currentStage,
                boolean reused, Optional<ReuseDecision> reuseDecision, long totalElapsedMillis,
                Map<String, Long> stageMillis, List<StageTiming> stages, Map<String, Long> cacheMetrics) {
            this(jobId, setup, mode, state, currentStage, reused, reuseDecision, totalElapsedMillis, stageMillis,
                    stages, cacheMetrics, Optional.empty());
        }

        public Telemetry {
            stageMillis = Collections.unmodifiableMap(new LinkedHashMap<>(stageMillis));
            stages = List.copyOf(stages);
            cacheMetrics = Collections.unmodifiableMap(new TreeMap<>(cacheMetrics));
            Objects.requireNonNull(incremental, "incremental");
        }
    }

    public static final class Rejected extends RuntimeException {
        private final String code;

        public Rejected(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public final class Control implements BooleanSupplier {
        private final Job job;

        private Control(Job job) {
            this.job = job;
        }

        @Override
        public boolean getAsBoolean() {
            return job.stop != null || Thread.currentThread().isInterrupted();
        }

        public void checkpoint() {
            if (getAsBoolean())
                throw new CancellationException();
        }

        public String id() {
            return job.id.toString();
        }

        public AnalysisMode mode() {
            return job.mode;
        }

        public Optional<String> requestedRef() {
            return job.requestedRef;
        }

        boolean beginReuseCheck() {
            if (disk == null)
                return false;
            job.reuseDecision = job.force ? ReuseDecision.BYPASSED : ReuseDecision.INPUTS_UNVERIFIED;
            return true;
        }

        Optional<Reused> reuse(ContentDigest verifiedInputs) {
            checkpoint();
            if (disk == null)
                return Optional.empty();
            if (job.inputKey != null || job.stage.ordinal() >= Stage.ANALYSIS.ordinal())
                throw new IllegalStateException("Reuse requires a single lookup before analysis");
            if (job.requestedRef.isPresent()) {
                job.inputKey = CanonicalJson.sha256(List.of("workbench-result-lookup:v1", job.registration.setup(),
                        verifiedInputs, job.mode.name(), "ref:" + job.requestedRef.orElseThrow()));
            } else {
                job.inputKey = job.mode == AnalysisMode.FULL
                        ? CanonicalJson
                                .sha256(List.of("workbench-result-lookup:v1", job.registration.setup(), verifiedInputs))
                        : CanonicalJson.sha256(List.of("workbench-result-lookup:v1", job.registration.setup(),
                                verifiedInputs, job.mode.name()));
            }
            if (job.force)
                return Optional.empty();
            job.reuseDecision = ReuseDecision.NO_MATCH;
            try {
                var candidate = disk.reusable(job.inputKey, job.registration.setup(), this);
                if (candidate.isEmpty())
                    return Optional.empty();
                var result = candidate.orElseThrow().view().result().orElseThrow();
                var graph = result.graph().orElseThrow();
                job.reuseDecision = ReuseDecision.RESULT_UNAVAILABLE;
                if (!graphs.verifyPublished(graph, this))
                    return Optional.empty();
                for (var report : result.reports()) {
                    checkpoint();
                    if (!reports.verifyPublished(report, graph, this))
                        return Optional.empty();
                }
                checkpoint();
                return candidate.map(Reused::new);
            } catch (IOException | IllegalArgumentException | IllegalStateException | GraphStoreException unavailable) {
                checkpoint();
                job.reuseDecision = ReuseDecision.RESULT_UNAVAILABLE;
                return Optional.empty(); // An unavailable acceleration index is a miss.
            }
        }

        void withholdReuse() {
            job.inputKey = null;
        }

        public void recordCacheMetrics(Map<String, Long> metrics) {
            if (metrics == null || metrics.isEmpty())
                return;
            synchronized (AnalysisJobs.this) {
                metrics.forEach((k, v) -> {
                    if (k != null && !k.isBlank() && v != null && v >= 0L) {
                        job.cacheMetrics.merge(k, v, Long::sum);
                    }
                });
            }
        }

        public void reportProgress(int done, int total, String message) {
            Map<String, Object> payload;
            synchronized (AnalysisJobs.this) {
                checkpoint();
                var snap = job.estimator.recordProgress(job.stage, done, total);
                job.progressMessage = sanitizeProgressMessage(message);
                payload = progressEventPayload(job, snap);
            }
            eventBroker.publish(job.id.toString(), "progress_update", payload);
        }

        public void registerModules(List<String> modules) {
            if (modules == null || modules.isEmpty())
                return;
            synchronized (AnalysisJobs.this) {
                for (String module : modules) {
                    if (module != null && !module.isBlank()) {
                        job.modules.putIfAbsent(module,
                                new ModuleProgress(module, "PENDING", 0, 0, 0L, Optional.empty(), Optional.empty()));
                    }
                }
            }
        }

        public void reportModuleUpdate(String module, String status, int filesDone, int filesTotal,
                long elapsedMillis, Optional<String> contextId, Optional<String> reason) {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(contextId, "contextId");
            Objects.requireNonNull(reason, "reason");
            String eventType;
            Map<String, Object> payload;
            synchronized (AnalysisJobs.this) {
                checkpoint();
                var update = new ModuleProgress(module, status, Math.max(0, filesDone), Math.max(0, filesTotal),
                        Math.max(0L, elapsedMillis), contextId, reason);
                job.modules.put(module, update);
                boolean doneStatus = "COMPLETED".equals(status) || "REUSED".equals(status)
                        || "FAILED".equals(status) || "SKIPPED".equals(status);
                eventType = doneStatus ? "module_completed" : "module_progress";
                payload = new LinkedHashMap<>();
                payload.put("jobId", job.id.toString());
                payload.put("module", update.module());
                payload.put("status", update.status());
                payload.put("filesDone", update.filesDone());
                payload.put("filesTotal", update.filesTotal());
                payload.put("elapsedMillis", update.elapsedMillis());
                payload.put("contextId", update.contextId().orElse(null));
                payload.put("reason", update.reason().orElse(null));
            }
            eventBroker.publish(job.id.toString(), eventType, payload);
        }

        public PreviewPublication publishPreview(KnowledgeGraph graph, TruthTier truthTier, int completedContexts,
                int totalContexts, List<String> pendingModules) {
            Objects.requireNonNull(graph, "graph");
            Objects.requireNonNull(truthTier, "truthTier");
            checkpoint();
            PreviewPublication publication;
            Map<String, Object> payload;
            synchronized (AnalysisJobs.this) {
                checkpoint();
                List<String> safePending = pendingModules == null ? List.of() : List.copyOf(pendingModules);
                Instant publishedAt;
                if (previewStore != null) {
                    var meta = previewStore.putPreview(job.id.toString(), graph, truthTier,
                            completedContexts, totalContexts, safePending);
                    publishedAt = meta.publishedAt();
                } else {
                    publishedAt = Instant.now();
                }
                publication = new PreviewPublication(graph.identity(), truthTier,
                        Math.max(0, completedContexts), Math.max(0, totalContexts), safePending, publishedAt);
                job.preview = publication;
                job.truthTier = truthTier;
                job.revision++;
                payload = new LinkedHashMap<>();
                payload.put("jobId", job.id.toString());
                payload.put("graphId", publication.graphId().value());
                payload.put("truthTier", publication.truthTier().name());
                payload.put("completedContexts", publication.completedContexts());
                payload.put("totalContexts", publication.totalContexts());
                payload.put("pendingModules", publication.pendingModules());
                payload.put("publishedAt", publication.publishedAt());
                payload.put("authoritative", false);
                payload.put("revision", job.revision);
            }
            eventBroker.publish(job.id.toString(), "preview_ready", payload);
            return publication;
        }

        public void recordIncrementalMetrics(IncrementalMetrics metrics) {
            Objects.requireNonNull(metrics, "metrics");
            Map<String, Object> payload;
            synchronized (AnalysisJobs.this) {
                job.incrementalMetrics = metrics;
                payload = new LinkedHashMap<>();
                payload.put("jobId", job.id.toString());
                payload.put("baseSnapshotId", metrics.baseSnapshotId().orElse(null));
                payload.put("addedFiles", metrics.addedFiles());
                payload.put("modifiedFiles", metrics.modifiedFiles());
                payload.put("deletedFiles", metrics.deletedFiles());
                payload.put("reusedFiles", metrics.reusedFiles());
                payload.put("abiEarlyCutoffCount", metrics.abiEarlyCutoffCount());
                payload.put("impactedDependentsCount", metrics.impactedDependentsCount());
                payload.put("reusedModules", metrics.reusedModules());
                payload.put("timeSavedEstimateMs", metrics.timeSavedEstimateMs());
            }
            eventBroker.publish(job.id.toString(), "incremental_summary", payload);
        }

        public void stage(Stage stage) {
            Map<String, Object> payload;
            synchronized (AnalysisJobs.this) {
                checkpoint();
                if (stage == Stage.FINISHED || stage == Stage.REUSED || stage.ordinal() <= job.stage.ordinal())
                    throw new IllegalArgumentException("Invalid progress transition");
                long nowNanos = System.nanoTime();
                job.closeActiveStage(nowNanos);
                job.stage = stage;
                job.stageStartNanos.put(stage, nowNanos);
                job.events.add(new Event(stage, Instant.now()));
                job.revision++;
                var snap = job.estimator.onStageEnter(stage);
                if (stage == Stage.PUBLICATION) {
                    persist(job);
                } else {
                    try {
                        persist(job);
                    } catch (Rejected unavailable) {
                        LOG.log(System.Logger.Level.WARNING,
                                "Non-terminal stage checkpoint skipped for {0} at {1}",
                                job.id, stage);
                    }
                }
                payload = new LinkedHashMap<>();
                payload.put("jobId", job.id.toString());
                payload.put("stage", stage.name());
                payload.put("revision", job.revision);
                payload.put("overallRatio", snap.overallRatio());
                payload.put("elapsedMillis", snap.elapsedMillis());
            }
            eventBroker.publish(job.id.toString(), "stage_started", payload);
        }
    }

    private static final class Job {
        final UUID id;
        final Registration registration;
        final int sequence;
        final Instant submitted;
        final List<Event> events = new ArrayList<>();
        String retry;
        final long submittedNanos = System.nanoTime();
        long finishedNanos;
        final EnumMap<Stage, Long> stageStartNanos = new EnumMap<>(Stage.class);
        final EnumMap<Stage, Long> stageElapsedNanos = new EnumMap<>(Stage.class);
        final Map<String, Long> cacheMetrics = new TreeMap<>();
        final ThroughputEstimator estimator = new ThroughputEstimator();
        final Map<String, ModuleProgress> modules = new LinkedHashMap<>();
        State state = State.QUEUED;
        Stage stage = Stage.QUEUED;
        long revision = 1;
        volatile Stop stop;
        Instant started, finished;
        Thread thread;
        Runnable task;
        ScheduledFuture<?> deadline;
        Result result;
        String error;
        List<CapabilityGapRecord> gaps = List.of();
        List<InputProblem> inputProblems = List.of();
        AnalysisJobDiskStore.PreparedLedgers preparedLedgers;
        boolean force;
        AnalysisMode mode = AnalysisMode.FULL;
        Optional<String> requestedRef = Optional.empty();
        ContentDigest inputKey;
        Reuse reuse;
        volatile ReuseDecision reuseDecision;
        AnalysisJobDiskStore.Envelope reusedPublication;
        TruthTier truthTier = TruthTier.NONE;
        PreviewPublication preview;
        IncrementalMetrics incrementalMetrics;
        String progressMessage = "";
        String ownerUserId = com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID;
        com.evolution.backend.auth.RequestContext.ActiveRequest requestContext;

        Job(Registration registration, int sequence) {
            this(registration, sequence, null);
        }

        Job(Registration registration, int sequence, AnalysisJobDiskStore.Saved previous) {
            this.registration = registration;
            this.sequence = sequence;
            this.id = previous == null ? UUID.randomUUID() : UUID.fromString(previous.view().id());
            this.submitted = previous == null ? Instant.now() : previous.view().submittedAt();
            if (previous != null) {
                this.revision = previous.view().revision() + 1;
                this.force = previous.view().force();
                this.mode = previous.view().mode().orElse(AnalysisMode.FULL);
            }
            stageStartNanos.put(stage, submittedNanos);
            events.add(new Event(stage, Instant.now()));
        }

        void closeActiveStage(long nowNanos) {
            Long startedAt = stageStartNanos.remove(stage);
            if (startedAt != null) {
                stageElapsedNanos.merge(stage, Math.max(0L, nowNanos - startedAt), Long::sum);
            }
        }
    }

    private final SortedMap<String, Registration> setups = new TreeMap<>();
    private final LinkedHashMap<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, String> jobOwners = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Telemetry> completedTelemetry = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Telemetry> eldest) {
            return size() > 128;
        }
    };
    private final LinkedHashMap<UUID, LiveProgress> completedProgress = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, LiveProgress> eldest) {
            return size() > 128;
        }
    };
    private final Map<String, Job> retries = new HashMap<>();
    private final UUID session = UUID.randomUUID();
    private final GraphStore graphs;
    private final PublishedReports reports;
    private final Limits limits;
    private final AnalysisJobDiskStore disk;
    private final WorkbenchPreviewStore previewStore;
    private final AnalysisJobEventBroker eventBroker = new AnalysisJobEventBroker();
    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor deadlines;
    private boolean closed;

    public AnalysisJobs(List<Registration> registrations, GraphStore graphs, PublishedReports reports, Limits limits) {
        this(registrations, graphs, reports, limits, null, null);
    }

    public AnalysisJobs(List<Registration> registrations, GraphStore graphs, PublishedReports reports, Limits limits,
            AnalysisJobDiskStore disk) {
        this(registrations, graphs, reports, limits, disk, null);
    }

    public AnalysisJobs(List<Registration> registrations, GraphStore graphs, PublishedReports reports, Limits limits,
            AnalysisJobDiskStore disk, WorkbenchPreviewStore previewStore) {
        this.graphs = Objects.requireNonNull(graphs);
        this.reports = Objects.requireNonNull(reports);
        this.limits = Objects.requireNonNull(limits);
        this.disk = disk;
        this.previewStore = previewStore;
        if (registrations.size() > 32)
            throw new IllegalArgumentException("Setup capacity exceeded");
        for (var registration : registrations)
            if (setups.putIfAbsent(registration.setup().id(), registration) != null)
                throw new IllegalArgumentException("Duplicate setup");
        executor = new ThreadPoolExecutor(limits.workers(), limits.workers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(limits.queued()), runnable -> daemon(runnable, "architecture-analysis"),
                new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, runnable -> daemon(runnable, "architecture-analysis-deadlines"));
        deadlines.setRemoveOnCancelPolicy(true);
        if (disk != null) {
            try {
                for (var registration : setups.values())
                    disk.recover(registration.setup());
            } catch (IOException failure) {
                executor.shutdownNow();
                deadlines.shutdownNow();
                throw new Rejected("JOB_STORAGE_UNAVAILABLE");
            }
            dispatchPending();
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        var thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    public Limits limits() {
        return limits;
    }

    public boolean resultReuseEnabled() {
        return disk != null;
    }

    public boolean uses(GraphStore graphs, PublishedReports reports) {
        return this.graphs == graphs && this.reports == reports;
    }

    public WorkbenchPreviewStore previewStore() {
        return previewStore;
    }

    public AnalysisJobEventBroker eventBroker() {
        return eventBroker;
    }

    private boolean isJobVisibleToCaller(UUID id) {
        String owner = jobOwners.getOrDefault(id, com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID);
        String caller = com.evolution.backend.auth.RequestContext.currentUserId();
        if (owner.equals(caller)) {
            return true;
        }
        return com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID.equals(owner)
                && !com.evolution.backend.auth.RequestContext.isMultiUserMode();
    }

    private void requireJobAccess(UUID id) {
        if (!isJobVisibleToCaller(id)) {
            throw new Rejected("ACCESS_DENIED");
        }
    }

    public synchronized LiveProgress progress(UUID id) {
        Objects.requireNonNull(id);
        requireJobAccess(id);
        var active = jobs.get(id);
        if (active != null)
            return buildLiveProgress(active);
        var recent = completedProgress.get(id);
        if (recent != null)
            return recent;
        return buildHistoricalProgress(saved(id));
    }

    private static String scopedRetryKey(String ownerUserId, String idempotencyKey) {
        String owner = (ownerUserId == null || ownerUserId.isBlank())
                ? com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID
                : ownerUserId;
        return owner + "\0" + idempotencyKey;
    }

    private static String diskRetryKey(String ownerUserId, String idempotencyKey) {
        if (ownerUserId == null || ownerUserId.isBlank()
                || com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID.equals(ownerUserId)) {
            return idempotencyKey;
        }
        if (idempotencyKey != null && idempotencyKey.startsWith("u-") && idempotencyKey.length() == 34
                && idempotencyKey.substring(2).matches("[0-9a-f]{32}")) {
            return idempotencyKey;
        }
        return "u-" + ContentDigest.sha256Utf8(ownerUserId + "\0" + idempotencyKey).value().substring(7, 39);
    }

    public synchronized Optional<PreviewPublication> preview(UUID id) {
        Objects.requireNonNull(id);
        requireJobAccess(id);
        var active = jobs.get(id);
        if (active != null)
            return Optional.ofNullable(active.preview);
        var recent = completedProgress.get(id);
        if (recent != null)
            return recent.preview();
        return buildHistoricalProgress(saved(id)).preview();
    }

    public synchronized TruthTier truthTier(UUID id) {
        return progress(id).truthTier();
    }

    public synchronized Optional<IncrementalMetrics> incrementalMetrics(UUID id) {
        Objects.requireNonNull(id);
        var active = jobs.get(id);
        if (active != null)
            return Optional.ofNullable(active.incrementalMetrics);
        var recent = completedTelemetry.get(id);
        if (recent != null)
            return recent.incremental();
        return saved(id).telemetry().flatMap(AnalysisJobDiskStore.PersistedTelemetry::incremental);
    }

    public AnalysisJobEventBroker.Subscription subscribe(UUID id, long lastEventId) {
        Objects.requireNonNull(id);
        LiveProgress snapshot;
        AnalysisJobDiskStore.Saved historical = null;
        boolean hasLiveChannel;
        synchronized (this) {
            requireJobAccess(id);
            var active = jobs.get(id);
            if (active != null) {
                snapshot = buildLiveProgress(active);
                hasLiveChannel = true;
            } else {
                var recent = completedProgress.get(id);
                if (recent != null) {
                    snapshot = recent;
                    hasLiveChannel = eventBroker.hasChannel(id.toString());
                } else {
                    historical = saved(id);
                    snapshot = buildHistoricalProgress(historical);
                    hasLiveChannel = eventBroker.hasChannel(id.toString());
                }
            }
        }
        if (hasLiveChannel) {
            return eventBroker.subscribe(id.toString(), lastEventId, Optional.of(snapshot));
        }
        var view = historical != null ? historical.view() : get(id);
        var terminalPayload = terminalEventPayload(view.id(), view.state(), snapshot.truthTier(), view.result(),
                view.error(), view.stop(), view.elapsedMillis(), snapshot.incremental());
        return eventBroker.historicalTerminalSubscription(id.toString(), snapshot,
                terminalEventName(view.state()), terminalPayload);
    }

    public synchronized List<Setup> setups() {
        return setups.values().stream().map(Registration::setup).toList();
    }

    /**
     * Only an explicitly enabled local connection adapter may add immutable
     * selections.
     */
    public synchronized void register(Registration registration) {
        if (closed)
            throw new Rejected("JOBS_CLOSED");
        if (setups.size() >= 32)
            throw new Rejected("SETUP_CAPACITY");
        if (setups.putIfAbsent(registration.setup().id(), registration) != null)
            throw new IllegalArgumentException("Duplicate setup");
        if (disk != null) {
            try {
                disk.recover(registration.setup());
            } catch (IOException failure) {
                throw new Rejected("JOB_STORAGE_UNAVAILABLE");
            }
            dispatchPending();
        }
    }

    public synchronized void unregister(String setupId) {
        if (closed)
            throw new Rejected("JOBS_CLOSED");
        boolean hasRunning = jobs.values().stream()
                .anyMatch(j -> j.registration.setup().id().equals(setupId) && !terminal(j.state));
        if (hasRunning)
            throw new Rejected("JOB_RUNNING");
        if (disk != null) {
            try {
                if (!disk.queued(setupId).isEmpty())
                    throw new Rejected("JOB_RUNNING");
            } catch (IOException failure) {
                throw new Rejected("JOB_STORAGE_UNAVAILABLE");
            }
        }
        setups.remove(setupId);
        retries.entrySet().removeIf(e -> e.getValue().registration.setup().id().equals(setupId));
    }

    public Submission submit(String setup, String idempotencyKey) {
        return submit(setup, idempotencyKey, false, AnalysisMode.FULL, Optional.empty());
    }

    public Submission submit(String setup, String idempotencyKey, boolean force) {
        return submit(setup, idempotencyKey, force, AnalysisMode.FULL, Optional.empty());
    }

    public Submission submit(String setup, String idempotencyKey, boolean force, AnalysisMode mode) {
        return submit(setup, idempotencyKey, force, mode, Optional.empty());
    }

    public synchronized Submission submit(String setup, String idempotencyKey, boolean force, AnalysisMode mode,
            Optional<String> ref) {
        Objects.requireNonNull(mode);
        var normalizedRef = normalizeRef(ref);
        identifier(setup);
        identifier(idempotencyKey);
        if (closed)
            throw new Rejected("JOBS_CLOSED");
        String callerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
        String retryKey = scopedRetryKey(callerUserId, idempotencyKey);
        var existing = retries.get(retryKey);
        if (existing != null) {
            if (!existing.registration.setup().id().equals(setup) || existing.force != force || existing.mode != mode
                    || !existing.requestedRef.equals(normalizedRef))
                throw new Rejected("IDEMPOTENCY_CONFLICT");
            return new Submission(view(existing), false);
        }
        var registration = setups.get(setup);
        if (registration == null)
            throw new Rejected("SETUP_NOT_FOUND");
        String persistedRetryKey = diskRetryKey(callerUserId, idempotencyKey);
        AnalysisJobDiskStore.Saved previous = null;
        if (disk != null)
            try {
                previous = disk.retry(persistedRetryKey).orElse(null);
                if (previous != null && isJobVisibleToCaller(UUID.fromString(previous.view().id()))) {
                    if (!previous.view().setup().equals(setup) || previous.view().force() != force
                            || previous.view().mode().orElse(AnalysisMode.FULL) != mode
                            || !disk.intent(UUID.fromString(previous.view().id())).equals(registration.setup()))
                        throw new Rejected("IDEMPOTENCY_CONFLICT");
                    if (terminal(previous.view().state())
                            || previous.view().state() == State.QUEUED
                            || previous.view().state() == State.RUNNING
                            || previous.view().state() == State.CANCELLING)
                        return new Submission(previous.view(), false);
                } else {
                    previous = null;
                }
            } catch (IOException failure) {
                throw new Rejected("JOB_STORAGE_UNAVAILABLE");
            }
        if (disk == null && jobs.size() >= limits.history())
            throw new Rejected("JOB_HISTORY_FULL");
        var job = new Job(registration, jobs.size() + 1, previous);
        job.force = force;
        job.mode = mode;
        job.requestedRef = normalizedRef;
        job.retry = idempotencyKey;
        job.ownerUserId = callerUserId;
        job.requestContext = com.evolution.backend.auth.RequestContext.current().orElse(null);
        jobOwners.put(job.id, job.ownerUserId);
        job.task = () -> execute(job);
        persist(job);
        // The monitor prevents the worker from observing a job before admission is
        // committed.
        try {
            executor.execute(job.task);
            jobs.put(job.id, job);
            retries.put(retryKey, job);
        } catch (RejectedExecutionException full) {
            if (disk == null)
                throw new Rejected("JOB_QUEUE_FULL");
        }
        return new Submission(view(job), true);
    }

    public synchronized Telemetry telemetry(UUID id) {
        Objects.requireNonNull(id);
        requireJobAccess(id);
        var active = jobs.get(id);
        if (active != null)
            return buildLiveTelemetry(active);
        var recent = completedTelemetry.get(id);
        if (recent != null)
            return recent;
        return buildHistoricalTelemetry(saved(id));
    }

    private Telemetry buildLiveTelemetry(Job job) {
        long nowNanos = job.finished == null ? System.nanoTime() : job.finishedNanos;
        long totalMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(nowNanos - job.submittedNanos));
        var stageMs = computeStageMillis(job, nowNanos);
        var stages = new ArrayList<StageTiming>();
        var evs = job.events;
        for (int i = 0; i < evs.size(); i++) {
            var ev = evs.get(i);
            if (ev.stage() == Stage.FINISHED)
                continue;
            Optional<Instant> ended = (i + 1 < evs.size()) ? Optional.of(evs.get(i + 1).at())
                    : Optional.ofNullable(job.finished);
            long dur = stageMs.getOrDefault(ev.stage().name(),
                    ended.map(end -> Math.max(0L, end.toEpochMilli() - ev.at().toEpochMilli())).orElse(0L));
            stages.add(new StageTiming(ev.stage(), dur, ev.at(), ended));
        }
        return new Telemetry(job.id.toString(), job.registration.setup().id(), Optional.of(job.mode), job.state,
                job.stage, job.reuse != null, Optional.ofNullable(job.reuseDecision), totalMs, stageMs, stages,
                job.cacheMetrics, Optional.ofNullable(job.incrementalMetrics));
    }

    private static LinkedHashMap<String, Long> computeStageMillis(Job job, long nowNanos) {
        var stageMs = new LinkedHashMap<String, Long>();
        for (Stage s : Stage.values()) {
            if (s == Stage.FINISHED)
                continue;
            long nanos = job.stageElapsedNanos.getOrDefault(s, 0L);
            Long inFlight = job.stageStartNanos.get(s);
            if (inFlight != null && job.finished == null) {
                nanos += Math.max(0L, nowNanos - inFlight);
            }
            if (nanos > 0L || job.events.stream().anyMatch(e -> e.stage() == s)) {
                stageMs.put(s.name(), Math.max(0L, TimeUnit.NANOSECONDS.toMillis(nanos)));
            }
        }
        return stageMs;
    }

    private LiveProgress buildLiveProgress(Job job) {
        long nowNanos = job.finished == null ? System.nanoTime() : job.finishedNanos;
        var snap = job.estimator.snapshot();
        long elapsedMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(nowNanos - job.submittedNanos));
        return new LiveProgress(
                job.id.toString(),
                job.registration.setup().id(),
                job.state,
                job.stage,
                job.revision,
                job.truthTier,
                Optional.ofNullable(job.preview).map(PreviewPublication::graphId),
                Optional.ofNullable(job.preview),
                snap.overallRatio(),
                snap.stageDone(),
                snap.stageTotal(),
                snap.filesPerSecond(),
                snap.etaSeconds(),
                snap.etaStatus(),
                elapsedMs,
                job.progressMessage,
                List.copyOf(job.modules.values()),
                computeStageMillis(job, nowNanos),
                job.cacheMetrics,
                Optional.ofNullable(job.incrementalMetrics));
    }

    private static LiveProgress buildHistoricalProgress(AnalysisJobDiskStore.Saved saved) {
        var view = saved.view();
        var telemetry = buildHistoricalTelemetry(saved);
        boolean isTerm = terminal(view.state());
        TruthTier tier = (view.state() == State.SUCCEEDED || view.state() == State.PARTIAL)
                && view.result().flatMap(Result::graph).isPresent()
                        ? TruthTier.DEEP_SEMANTIC_COMPLETE
                        : TruthTier.NONE;
        BigDecimal ratio = isTerm
                ? BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        String etaStatus = isTerm ? ThroughputEstimator.ETA_COMPLETE : ThroughputEstimator.ETA_ESTIMATING;
        Optional<Long> etaSec = isTerm ? Optional.of(0L) : Optional.empty();
        return new LiveProgress(
                view.id(),
                view.setup(),
                view.state(),
                view.stage(),
                view.revision(),
                tier,
                Optional.empty(),
                Optional.empty(),
                ratio,
                0,
                0,
                Optional.empty(),
                etaSec,
                etaStatus,
                view.elapsedMillis(),
                "",
                List.of(),
                telemetry.stageMillis(),
                telemetry.cacheMetrics(),
                telemetry.incremental());
    }

    private static Telemetry buildHistoricalTelemetry(AnalysisJobDiskStore.Saved saved) {
        var view = saved.view();
        var persisted = saved.telemetry();
        var stageMs = new LinkedHashMap<String, Long>();
        persisted.ifPresent(p -> stageMs.putAll(p.stageMillis()));
        var stages = new ArrayList<StageTiming>();
        var evs = view.events();
        for (int i = 0; i < evs.size(); i++) {
            var ev = evs.get(i);
            if (ev.stage() == Stage.FINISHED)
                continue;
            Optional<Instant> ended = (i + 1 < evs.size()) ? Optional.of(evs.get(i + 1).at()) : view.finishedAt();
            long fallbackDur = ended.map(end -> Math.max(0L, end.toEpochMilli() - ev.at().toEpochMilli())).orElse(0L);
            long dur = stageMs.containsKey(ev.stage().name()) ? stageMs.get(ev.stage().name()) : fallbackDur;
            stageMs.putIfAbsent(ev.stage().name(), fallbackDur);
            stages.add(new StageTiming(ev.stage(), dur, ev.at(), ended));
        }
        Map<String, Long> cacheMetrics = persisted.map(AnalysisJobDiskStore.PersistedTelemetry::cacheMetrics)
                .orElse(Map.of());
        Optional<IncrementalMetrics> incremental = persisted
                .flatMap(AnalysisJobDiskStore.PersistedTelemetry::incremental);
        return new Telemetry(view.id(), view.setup(), view.mode(), view.state(), view.stage(),
                view.reuse().isPresent(), view.reuseDecision(), view.elapsedMillis(), stageMs, stages,
                cacheMetrics, incremental);
    }

    public synchronized View get(UUID id) {
        requireJobAccess(id);
        var active = jobs.get(id);
        return active != null ? view(active) : saved(id).view();
    }

    /**
     * Explicit v2 continuation; ordinary retry keys keep their original idempotent
     * meaning.
     */
    public synchronized View resume(UUID id) {
        if (closed)
            throw new Rejected("JOBS_CLOSED");
        requireJobAccess(id);
        var active = jobs.get(id);
        AnalysisJobDiskStore.Saved previous;
        if (active != null) {
            if (!terminal(active.state))
                return view(active);
            previous = new AnalysisJobDiskStore.Saved("analysis-job-storage:m8.4-v2", active.retry, view(active),
                    active.gaps, active.inputProblems);
        } else
            previous = saved(id);
        var registration = setups.get(previous.view().setup());
        if (registration == null)
            throw new Rejected("SETUP_NOT_FOUND");
        if (disk != null)
            try {
                if (!disk.intent(id).equals(registration.setup()))
                    throw new Rejected("SETUP_CONFIGURATION_CHANGED");
            } catch (IOException failure) {
                throw new Rejected("JOB_STORAGE_UNAVAILABLE");
            }
        if (previous.view().state() == State.SUCCEEDED || previous.view().state() == State.PARTIAL
                || previous.view().state() == State.EMPTY)
            throw new Rejected("JOB_ALREADY_PUBLISHED");
        var job = new Job(registration, jobs.size() + 1, previous);
        job.retry = previous.retry();
        job.ownerUserId = active != null
                ? active.ownerUserId
                : jobOwners.getOrDefault(id, com.evolution.backend.auth.RequestContext.currentUserId());
        job.requestContext = com.evolution.backend.auth.RequestContext.current().orElse(null);
        jobOwners.put(id, job.ownerUserId);
        job.task = () -> execute(job);
        persist(job);
        try {
            executor.execute(job.task);
        } catch (RejectedExecutionException full) {
            if (disk == null)
                throw new Rejected("JOB_QUEUE_FULL");
            return view(job);
        }
        jobs.put(id, job);
        retries.put(scopedRetryKey(job.ownerUserId, job.retry), job);
        return view(job);
    }

    public synchronized View cancel(UUID id) {
        requireJobAccess(id);
        var job = jobs.get(id);
        if (job == null) {
            var previous = saved(id);
            var v = previous.view();
            if (terminal(v.state()))
                return v;
            var next = new View(v.id(), v.setup(), State.CANCELLED, Stage.FINISHED, v.revision() + 1, v.submittedAt(),
                    v.startedAt(), Optional.of(Instant.now()), v.elapsedMillis(), Optional.of(Stop.USER_CANCELLED),
                    Optional.empty(), v.events(), v.result(), v.force(), Optional.empty(), v.reuseDecision(), v.mode());
            try {
                disk.save(previous.retry(), disk.intent(id), next, previous.gaps(), previous.inputProblems());
            } catch (IOException failure) {
                throw new Rejected("JOB_STORAGE_UNAVAILABLE");
            }
            return next;
        }
        stop(job, Stop.USER_CANCELLED);
        return view(job);
    }

    private AnalysisJobDiskStore.Saved saved(UUID id) {
        if (disk == null)
            throw new Rejected("JOB_NOT_FOUND");
        try {
            return disk.get(id).orElseThrow(() -> new Rejected("JOB_NOT_FOUND"));
        } catch (IOException failure) {
            throw new Rejected("JOB_STORAGE_UNAVAILABLE");
        }
    }

    private void persist(Job job) {
        if (disk != null) {
            boolean interrupted = Thread.interrupted();
            try {
                var reusableKey = job.state == State.SUCCEEDED || job.state == State.PARTIAL
                        ? Optional.ofNullable(job.inputKey)
                        : Optional.<ContentDigest>empty();
                Optional<AnalysisJobDiskStore.PersistedTelemetry> persistedTelemetry = Optional.empty();
                if (terminal(job.state) && job.reusedPublication == null) {
                    long refNanos = job.finishedNanos > 0L ? job.finishedNanos : System.nanoTime();
                    var pt = new AnalysisJobDiskStore.PersistedTelemetry(
                            computeStageMillis(job, refNanos),
                            job.cacheMetrics,
                            Optional.ofNullable(job.incrementalMetrics));
                    if (!pt.isEmpty()) {
                        persistedTelemetry = Optional.of(pt);
                    }
                }
                String persistedRetry = diskRetryKey(job.ownerUserId, job.retry);
                disk.save(persistedRetry, job.registration.setup(), view(job), job.gaps, job.inputProblems,
                        job.reusedPublication, reusableKey, job.preparedLedgers, persistedTelemetry);
            } catch (IOException failure) {
                LOG.log(System.Logger.Level.WARNING, "Analysis job persistence failed for {0} ({1}: {2})",
                        job.id, failure.getClass().getSimpleName(), failure.getMessage());
                throw new Rejected("JOB_STORAGE_UNAVAILABLE");
            } finally {
                if (interrupted)
                    Thread.currentThread().interrupt();
            }
        }
    }

    private synchronized void dispatchPending() {
        if (disk == null || closed)
            return;
        try {
            while (jobs.size() < limits.workers() + limits.queued()) {
                AnalysisJobDiskStore.Saved candidate = null;
                for (var setup : setups.keySet())
                    for (var saved : disk.queued(setup))
                        if (!jobs.containsKey(UUID.fromString(saved.view().id()))) {
                            if (candidate == null
                                    || saved.view().submittedAt().isBefore(candidate.view().submittedAt()))
                                candidate = saved;
                            break;
                        }
                if (candidate == null)
                    return;
                var selected = candidate;
                var registration = setups.get(selected.view().setup());
                var intended = disk.intent(UUID.fromString(selected.view().id()));
                if (!intended.equals(registration.setup())) {
                    var v = selected.view();
                    var rejected = new View(v.id(), v.setup(), State.FAILED, Stage.FINISHED, v.revision() + 1,
                            v.submittedAt(), v.startedAt(), Optional.of(Instant.now()), v.elapsedMillis(),
                            Optional.empty(), Optional.of("SETUP_CONFIGURATION_CHANGED"), v.events(), v.result(),
                            v.force(), Optional.empty(), Optional.empty(), v.mode());
                    disk.save(selected.retry(), intended, rejected, selected.gaps(), selected.inputProblems());
                    continue;
                }
                var job = new Job(registration, jobs.size() + 1, selected);
                job.retry = selected.retry();
                job.ownerUserId = jobOwners.getOrDefault(job.id, com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID);
                job.task = () -> execute(job);
                try {
                    executor.execute(job.task);
                } catch (RejectedExecutionException full) {
                    return;
                }
                jobs.put(job.id, job);
                retries.put(scopedRetryKey(job.ownerUserId, job.retry), job);
            }
        } catch (IOException unavailable) {
            throw new Rejected("JOB_STORAGE_UNAVAILABLE");
        }
    }

    private Job find(UUID id) {
        var job = jobs.get(Objects.requireNonNull(id));
        if (job == null)
            throw new Rejected("JOB_NOT_FOUND");
        return job;
    }

    private void stop(Job job, Stop reason) {
        if (terminal(job.state) || job.stop != null)
            return;
        job.stop = reason;
        job.revision++;
        if (job.state == State.QUEUED) {
            executor.remove(job.task);
            finish(job, State.CANCELLED);
        } else {
            job.state = State.CANCELLING;
            if (job.thread != null)
                job.thread.interrupt();
            persist(job);
        }
    }

    private void execute(Job job) {
        var control = new Control(job);
        try {
            synchronized (this) {
                if (terminal(job.state))
                    return;
                job.thread = Thread.currentThread();
                job.state = State.RUNNING;
                job.started = Instant.now();
                job.revision++;
                persist(job);
                if (limits.timeoutMillis() > 0)
                    job.deadline = deadlines.schedule(() -> {
                        synchronized (AnalysisJobs.this) {
                            stop(job, Stop.DEADLINE_EXCEEDED);
                        }
                    }, limits.timeoutMillis(), TimeUnit.MILLISECONDS);
            }
            final Object[] holder = new Object[1];
            final Exception[] checkedErr = new Exception[1];
            com.evolution.backend.auth.RequestContext.runWithVoid(job.requestContext, () -> {
                try {
                    holder[0] = job.registration.runner().run(control);
                } catch (Exception ex) {
                    checkedErr[0] = ex;
                }
            });
            if (checkedErr[0] != null) {
                throw checkedErr[0];
            }
            var execution = holder[0];
            control.checkpoint();
            if (execution instanceof Reused reused) {
                synchronized (this) {
                    control.checkpoint();
                    var publication = reused.publication();
                    var source = publication.view();
                    var now = Instant.now();
                    long reuseNanos = System.nanoTime();
                    job.closeActiveStage(reuseNanos);
                    job.stageElapsedNanos.put(Stage.REUSED, 0L);
                    job.reuse = new Reuse(source.id(), source.finishedAt().orElseThrow(), now);
                    job.reuseDecision = ReuseDecision.REUSED;
                    job.cacheMetrics.merge("jobResultReuseHits", 1L, Long::sum);
                    job.reusedPublication = publication;
                    job.result = source.result().orElseThrow();
                    job.events.add(new Event(Stage.REUSED, now));
                    finish(job, source.state());
                }
                return;
            }
            var outcome = (Outcome) execution;
            control.stage(Stage.PUBLICATION);
            // Storage observes the stop token. Cancellation can still win until the
            // publication transaction below.
            outcome.graph().ifPresent(graph -> graphs.save(graph, control));
            var ledgers = disk == null ? null : disk.prepareLedgers(outcome.gaps(), outcome.inputProblems(), control);
            var graph = outcome.graph();
            Optional<GraphManifest> publishedManifest = Optional.empty();
            if (graph.isEmpty() && outcome.publishedGraph().isPresent()) {
                var pubId = outcome.publishedGraph().orElseThrow();
                publishedManifest = graphs.manifest(pubId);
                if (publishedManifest.isEmpty()) {
                    throw new GraphStoreException(GraphStoreException.Reason.CORRUPT_SNAPSHOT);
                }
            }
            var finalPublishedManifest = publishedManifest;
            var graphGaps = new HashSet<String>();
            if (graph.isPresent())
                for (var node : graph.orElseThrow().nodes()) {
                    control.checkpoint();
                    if (node.kind().equals("CAPABILITY_GAP"))
                        graphGaps.add(node.identity());
                }
            var allGaps = new HashSet<>(graphGaps);
            outcome.gaps().forEach(g -> {
                control.checkpoint();
                allGaps.add(g.gapIdentity().value());
            });
            int effectiveGraphGapCount = graph.isPresent() ? graphGaps.size() : outcome.publishedGraphGapCount();
            int effectiveTotalGapCount = graph.isPresent() ? allGaps.size()
                    : Math.max(allGaps.size(), effectiveGraphGapCount);
            Optional<ContentDigest> resultGraph = graph.map(KnowledgeGraph::identity)
                    .or(() -> outcome.publishedGraph());
            Optional<SnapshotIdentity> resultSnapshot = graph.map(KnowledgeGraph::snapshot)
                    .or(() -> finalPublishedManifest.map(GraphManifest::snapshot));
            List<KnowledgeGraph.Context> resultContexts = graph.map(KnowledgeGraph::contexts)
                    .or(() -> finalPublishedManifest.map(GraphManifest::contexts)).orElse(List.of());
            synchronized (this) {
                control.checkpoint();
                var ids = reports.publishAll(outcome.reports());
                var completion = outcome.completion();
                if (completion == Completion.COMPLETE && effectiveTotalGapCount > 0)
                    completion = Completion.PARTIAL;
                job.preparedLedgers = ledgers;
                job.gaps = disk == null ? outcome.gaps() : List.of();
                job.inputProblems = disk == null ? outcome.inputProblems() : List.of();
                job.result = new Result(completion, resultGraph, resultSnapshot,
                        resultContexts.stream().flatMap(c -> c.analysis().stream()).distinct().sorted().toList(),
                        resultContexts.stream().map(KnowledgeGraph.Context::identity).sorted().toList(), ids,
                        effectiveTotalGapCount, effectiveGraphGapCount, outcome.gaps().size(),
                        outcome.inputProblems().size(), outcome.acquisition(), outcome.sourceReport());
                if (completion == Completion.FAILED)
                    job.error = "ANALYSIS_FAILED";
                finish(job, switch (completion) {
                    case COMPLETE -> State.SUCCEEDED;
                    case PARTIAL -> State.PARTIAL;
                    case EMPTY -> State.EMPTY;
                    case FAILED -> State.FAILED;
                });
            }
        } catch (Exception | LinkageError | AssertionError | OutOfMemoryError failure) {
            LOG.log(System.Logger.Level.WARNING, "Analysis execution failed for {0} at stage {1} ({2}: {3})",
                    job.id, job.stage, failure.getClass().getSimpleName(), failure.getMessage());
            synchronized (this) {
                if (job.stop == null
                        && (failure instanceof InterruptedException || failure instanceof CancellationException))
                    job.stop = Stop.PROVIDER_INTERRUPTED;
                if (job.stop != null)
                    finish(job, job.stop == Stop.DEADLINE_EXCEEDED ? State.TIMED_OUT : State.CANCELLED);
                else {
                    job.error = failure instanceof GraphStoreException ? "RESULT_STORAGE_FAILED"
                            : failure instanceof OutOfMemoryError || failure instanceof IOException
                                    && "COMPATIBILITY_WORKER_REQUIRED".equals(failure.getMessage())
                                            ? "SEMANTIC_WORKER_REQUIRED"
                                            : job.stage == Stage.PUBLICATION ? "RESULT_PUBLICATION_FAILED"
                                                    : "ANALYSIS_FAILED";
                    finish(job, State.FAILED);
                }
            }
        } finally {
            synchronized (this) {
                if (job.deadline != null)
                    job.deadline.cancel(false);
                job.thread = null;
            }
            Thread.interrupted(); // Never transfer this job's interruption to the next queued job.
        }
    }

    private void finish(Job job, State state) {
        long endNanos = System.nanoTime();
        job.closeActiveStage(endNanos);
        job.estimator.markCompleted();
        job.state = state;
        job.stage = Stage.FINISHED;
        job.finished = Instant.now();
        job.finishedNanos = endNanos;
        job.events.add(new Event(Stage.FINISHED, job.finished));
        job.revision++;
        if ((state == State.SUCCEEDED || state == State.PARTIAL)
                && job.result != null && job.result.graph().isPresent()) {
            job.truthTier = TruthTier.DEEP_SEMANTIC_COMPLETE;
        }
        if (previewStore != null) {
            previewStore.markJobFinished(job.id.toString(),
                    job.result == null ? Optional.empty() : job.result.graph());
        }
        try {
            persist(job);
        } catch (Rejected unavailable) {
            LOG.log(System.Logger.Level.WARNING, "Terminal job persistence failed for {0}; marking FAILED", job.id);
            job.state = State.FAILED;
            if (job.error == null)
                job.error = "JOB_STORAGE_UNAVAILABLE";
            job.result = null;
            job.truthTier = TruthTier.NONE;
            if (job.reuse != null)
                job.reuseDecision = ReuseDecision.RESULT_UNAVAILABLE;
            job.reuse = null;
            completedTelemetry.put(job.id, buildLiveTelemetry(job));
            completedProgress.put(job.id, buildLiveProgress(job));
            publishTerminalEvent(job);
            return;
        }
        completedTelemetry.put(job.id, buildLiveTelemetry(job));
        completedProgress.put(job.id, buildLiveProgress(job));
        publishTerminalEvent(job);
        if (disk != null) {
            jobs.remove(job.id);
            retries.remove(scopedRetryKey(job.ownerUserId, job.retry));
            try {
                dispatchPending();
            } catch (Rejected unavailable) {
                /* Accepted queued receipts remain durable until storage recovers. */ }
        }
    }

    private void publishTerminalEvent(Job job) {
        long elapsedMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(job.finishedNanos - job.submittedNanos));
        var payload = terminalEventPayload(job.id.toString(), job.state, job.truthTier,
                Optional.ofNullable(job.result), Optional.ofNullable(job.error),
                Optional.ofNullable(job.stop), elapsedMs, Optional.ofNullable(job.incrementalMetrics));
        eventBroker.publish(job.id.toString(), terminalEventName(job.state), payload);
    }

    private static String terminalEventName(State state) {
        return switch (state) {
            case SUCCEEDED, PARTIAL, EMPTY -> "job_completed";
            case CANCELLED -> "job_cancelled";
            case FAILED, TIMED_OUT -> "job_failed";
            default -> "job_failed";
        };
    }

    private static Map<String, Object> terminalEventPayload(
            String jobId,
            State state,
            TruthTier truthTier,
            Optional<Result> result,
            Optional<String> error,
            Optional<Stop> stop,
            long elapsedMillis,
            Optional<IncrementalMetrics> incremental) {
        var map = new LinkedHashMap<String, Object>();
        map.put("jobId", jobId);
        map.put("state", state.name());
        map.put("truthTier", truthTier.name());
        map.put("graphId", result.flatMap(Result::graph).map(ContentDigest::value).orElse(null));
        map.put("snapshotId", result.flatMap(Result::snapshot).map(SnapshotIdentity::value).orElse(null));
        map.put("result", result.orElse(null));
        map.put("error", error.orElse(null));
        map.put("stop", stop.map(Enum::name).orElse(null));
        map.put("elapsedMillis", elapsedMillis);
        map.put("incremental", incremental.orElse(null));
        return map;
    }

    private static Map<String, Object> progressEventPayload(Job job, ThroughputEstimator.Snapshot snap) {
        var map = new LinkedHashMap<String, Object>();
        map.put("jobId", job.id.toString());
        map.put("stage", job.stage.name());
        map.put("revision", job.revision);
        map.put("truthTier", job.truthTier.name());
        map.put("overallRatio", snap.overallRatio());
        map.put("stageDone", snap.stageDone());
        map.put("stageTotal", snap.stageTotal());
        map.put("filesPerSecond", snap.filesPerSecond().orElse(null));
        map.put("etaSeconds", snap.etaSeconds().orElse(null));
        map.put("etaStatus", snap.etaStatus());
        map.put("elapsedMillis", snap.elapsedMillis());
        map.put("message", job.progressMessage);
        return map;
    }

    private static String sanitizeProgressMessage(String message) {
        if (message == null || message.isBlank())
            return "";
        String cleaned = message.replace('\r', ' ').replace('\n', ' ').strip();
        return cleaned.length() > 240 ? cleaned.substring(0, 240) : cleaned;
    }

    public static Optional<String> normalizeRef(Optional<String> ref) {
        if (ref == null || ref.isEmpty())
            return Optional.empty();
        String trimmed = ref.orElseThrow().strip();
        if (trimmed.isEmpty())
            return Optional.empty();
        if (trimmed.length() > 256
                || trimmed.startsWith("-")
                || trimmed.contains("..")
                || trimmed.contains("@{")
                || trimmed.endsWith(".lock")
                || trimmed.endsWith("/")
                || !trimmed.matches("[A-Za-z0-9._/@+-]{1,256}")) {
            throw new IllegalArgumentException("Invalid Git ref");
        }
        return Optional.of(trimmed);
    }

    public static boolean terminal(State state) {
        return state != State.QUEUED && state != State.RUNNING && state != State.CANCELLING;
    }

    private View view(Job job) {
        long elapsed = TimeUnit.NANOSECONDS
                .toMillis((job.finished == null ? System.nanoTime() : job.finishedNanos) - job.submittedNanos);
        return new View(job.id.toString(), job.registration.setup().id(), job.state, job.stage, job.revision,
                job.submitted,
                Optional.ofNullable(job.started), Optional.ofNullable(job.finished), Math.max(0, elapsed),
                Optional.ofNullable(job.stop),
                Optional.ofNullable(job.error), List.copyOf(job.events), Optional.ofNullable(job.result),
                job.force, Optional.ofNullable(job.reuse), Optional.ofNullable(job.reuseDecision),
                Optional.of(job.mode));
    }

    /**
     * A high-water mark freezes membership. Live state/revisions remain explicitly
     * operational.
     */
    public synchronized Page<View> history(int size, Optional<String> cursor) {
        if (disk != null) {
            checkSize(size);
            long after = 0, high = 0;
            if (cursor.isPresent()) {
                String encoded = cursor.orElseThrow();
                if (encoded.length() > 512)
                    throw new IllegalArgumentException("Invalid durable history cursor");
                String[] parts = new String(Base64.getUrlDecoder().decode(encoded),
                        java.nio.charset.StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 5 || !parts[0].equals(disk.namespace()) || !parts[1].equals(Integer.toString(size)))
                    throw new IllegalArgumentException("Foreign durable history cursor");
                high = Long.parseLong(parts[2]);
                after = Long.parseLong(parts[3]);
                if (high < 1 || after < 1 || after > high || !durableCursor(size, high, after).equals(encoded))
                    throw new IllegalArgumentException("Invalid durable history cursor");
            }
            try {
                boolean unfilteredLocal = !com.evolution.backend.auth.RequestContext.isMultiUserMode()
                        && com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID
                                .equals(com.evolution.backend.auth.RequestContext.currentUserId())
                        && jobOwners.values().stream()
                                .allMatch(com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID::equals);
                if (unfilteredLocal) {
                    var page = disk.history(after, size, high);
                    if (page.total() > Integer.MAX_VALUE)
                        throw new Rejected("V2_HISTORY_REQUIRED");
                    var items = page.items().stream()
                            .map(saved -> {
                                var active = jobs.get(UUID.fromString(saved.view().id()));
                                return active == null ? saved.view() : view(active);
                            }).toList();
                    return new Page<>(items, (int) page.total(),
                            page.continuation().map(next -> durableCursor(size, page.highWaterSequence(), next)));
                }
                var probe = disk.history(0, 50, high);
                long effectiveHigh = high > 0 ? high : probe.highWaterSequence();
                var visible = new ArrayList<View>();
                var currentPage = probe;
                while (true) {
                    for (var saved : currentPage.items()) {
                        UUID jid = UUID.fromString(saved.view().id());
                        if (isJobVisibleToCaller(jid)) {
                            var active = jobs.get(jid);
                            visible.add(active == null ? saved.view() : view(active));
                        }
                    }
                    if (currentPage.continuation().isEmpty()) {
                        break;
                    }
                    currentPage = disk.history(currentPage.continuation().orElseThrow(), 50, effectiveHigh);
                }
                int visibleTotal = visible.size();
                int offset = cursor.isPresent() ? (int) after : 0;
                if (offset > visibleTotal || (offset == visibleTotal && offset != 0)) {
                    throw new IllegalArgumentException("Invalid durable history cursor");
                }
                var pageItems = visible.stream().skip(offset).limit(size).toList();
                int nextOffset = offset + pageItems.size();
                Optional<String> nextCursor = nextOffset < visibleTotal && effectiveHigh >= nextOffset
                        ? Optional.of(durableCursor(size, effectiveHigh, nextOffset))
                        : Optional.empty();
                return new Page<>(pageItems, visibleTotal, nextCursor);
            } catch (IOException failure) {
                throw new Rejected("JOB_STORAGE_UNAVAILABLE");
            }
        }
        checkSize(size);
        int high = jobs.size(), offset = 0;
        if (cursor.isPresent()) {
            var decoded = cursor(cursor.orElseThrow(), "history", size);
            high = decoded[0];
            offset = decoded[1];
        }
        final int bound = high;
        var members = jobs.values().stream()
                .filter(j -> j.sequence <= bound && isJobVisibleToCaller(j.id))
                .toList();
        if (high > jobs.size() || offset >= members.size() && offset != 0)
            throw new IllegalArgumentException("Invalid history cursor");
        var items = members.stream().skip(offset).limit(size).map(this::view).toList();
        int next = offset + items.size();
        int visibleTotal = members.size();
        return new Page<>(items, visibleTotal,
                next < visibleTotal ? Optional.of(cursor("history", size, high, next)) : Optional.empty());
    }

    public Page<CapabilityGapRecord> gaps(UUID id, int size, Optional<String> cursor) {
        synchronized (this) {
            requireJobAccess(id);
            var active = jobs.get(id);
            if (active != null || disk == null) {
                var job = find(id);
                if (!terminal(job.state))
                    throw new Rejected("JOB_NOT_FINISHED");
                return savedPage(job.gaps, id.toString(), size, cursor);
            }
        }
        checkSize(size);
        int[] bounds = cursor.map(token -> cursor(token, id.toString(), size)).orElse(null);
        int offset = bounds == null ? 0 : bounds[1];
        try {
            var page = disk.gaps(id, offset, size, bounds == null ? OptionalInt.empty() : OptionalInt.of(bounds[0]));
            int next = offset + page.items().size();
            return new Page<>(page.items(), page.total(),
                    next < page.total() ? Optional.of(cursor(id.toString(), size, page.total(), next))
                            : Optional.empty());
        } catch (IOException unavailable) {
            throw new Rejected("JOB_STORAGE_UNAVAILABLE");
        }
    }

    /**
     * Read a bounded prefix for the chat census. A durable job reads its ledger
     * only once.
     */
    public Page<CapabilityGapRecord> chatGaps(UUID id) {
        int limit = 2_000;
        synchronized (this) {
            var job = jobs.get(id);
            if (job != null || disk == null) {
                if (job == null)
                    job = find(id);
                if (!terminal(job.state))
                    throw new Rejected("JOB_NOT_FINISHED");
                return new Page<>(job.gaps.subList(0, Math.min(limit, job.gaps.size())), job.gaps.size(),
                        Optional.empty());
            }
        }
        try {
            var page = disk.gaps(id, 0, limit, OptionalInt.empty());
            return new Page<>(page.items(), page.total(), Optional.empty());
        } catch (IOException unavailable) {
            throw new Rejected("JOB_STORAGE_UNAVAILABLE");
        }
    }

    public Page<InputProblem> inputProblems(UUID id, int size, Optional<String> cursor) {
        synchronized (this) {
            requireJobAccess(id);
            var active = jobs.get(id);
            if (active != null || disk == null) {
                var job = find(id);
                if (!terminal(job.state))
                    throw new Rejected("JOB_NOT_FINISHED");
                return savedPage(job.inputProblems, id + "/input", size, cursor);
            }
        }
        try {
            return savedPage(disk.inputProblems(id), id + "/input", size, cursor);
        } catch (IOException unavailable) {
            throw new Rejected("JOB_STORAGE_UNAVAILABLE");
        }
    }

    private void checkSize(int size) {
        if (size < 1 || size > 50)
            throw new IllegalArgumentException("Invalid page size");
    }

    private String durableCursor(int size, long high, long after) {
        String prefix = disk.namespace() + "|" + size + "|" + high + "|" + after;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((prefix + "|" + ContentDigest.sha256Utf8(prefix).value().substring(7))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private <T> Page<T> savedPage(List<T> items, String operation, int size, Optional<String> cursor) {
        checkSize(size);
        int offset = 0;
        if (cursor.isPresent()) {
            var decoded = cursor(cursor.orElseThrow(), operation, size);
            if (decoded[0] != items.size())
                throw new IllegalArgumentException("Invalid cursor");
            offset = decoded[1];
        }
        if (offset >= items.size() && offset != 0)
            throw new IllegalArgumentException("Invalid cursor");
        var selected = items.stream().skip(offset).limit(size).toList();
        int next = offset + selected.size();
        return new Page<>(selected, items.size(),
                next < items.size() ? Optional.of(cursor(operation, size, items.size(), next)) : Optional.empty());
    }

    private String cursor(String operation, int size, int high, int offset) {
        String prefix = session + ":" + operation + ":" + size + ":" + high + ":" + offset;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((prefix + ":" + ContentDigest.sha256Utf8(prefix).value().substring(7))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private int[] cursor(String encoded, String operation, int size) {
        if (encoded.length() > 512)
            throw new IllegalArgumentException("Invalid cursor");
        String raw = new String(Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
        var parts = raw.split(":", -1);
        if (parts.length != 6 || !parts[0].equals(session.toString()) || !parts[1].equals(operation)
                || !parts[2].equals(Integer.toString(size)))
            throw new IllegalArgumentException("Foreign cursor");
        int high = Integer.parseInt(parts[3]), offset = Integer.parseInt(parts[4]);
        if (high < 0 || offset < 1 || !cursor(operation, size, high, offset).equals(encoded))
            throw new IllegalArgumentException("Invalid cursor");
        return new int[] { high, offset };
    }

    public static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("Invalid identifier");
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed)
                return;
            closed = true;
            for (var job : List.copyOf(jobs.values()))
                stop(job, Stop.SERVICE_CLOSED);
        }
        eventBroker.closeAll();
        executor.shutdownNow();
        deadlines.shutdownNow();
        try {
            executor.awaitTermination(limits.shutdownMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
