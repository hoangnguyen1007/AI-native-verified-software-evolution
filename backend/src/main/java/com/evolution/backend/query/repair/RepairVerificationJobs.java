package com.evolution.backend.query.repair;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.backend.query.CapturedRepairVerification;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bounded async job orchestrator for isolated repair verification and closed-loop repair workflows
 * with live SSE progress streaming across stages:
 * {@code STAGING -> PASSIVE_REANALYSIS -> SANDBOX_BUILD -> SANDBOX_TEST -> DIFF_TESTGEN -> CLOSED_LOOP_REPAIR -> IMPACT_SCORING -> REPORT_READY},
 * optionally backed by durable SQLite persistence ({@link RepairJobStore}) with crash recovery.
 */
public final class RepairVerificationJobs implements AutoCloseable {
    private static final int MAX_RETAINED_JOBS = 64;

    public enum Stage {
        QUEUED,
        STAGING,
        PASSIVE_REANALYSIS,
        SANDBOX_BUILD,
        SANDBOX_TEST,
        DIFF_TESTGEN,
        CLOSED_LOOP_REPAIR,
        IMPACT_SCORING,
        REPORT_READY,
        FAILED,
        CANCELLED,
        INTERRUPTED_ON_RESTART
    }

    public static boolean isTerminalStage(Stage stage) {
        return stage == Stage.REPORT_READY
                || stage == Stage.FAILED
                || stage == Stage.CANCELLED
                || stage == Stage.INTERRUPTED_ON_RESTART;
    }

    public record JobEvent(long seq, Instant timestamp, Stage stage, String message, Map<String, Object> detail) {
        public JobEvent {
            detail = detail == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
        }
    }

    public record JobView(
            UUID jobId,
            ContentDigest proposalId,
            Stage stage,
            boolean terminal,
            Instant createdAt,
            Instant updatedAt,
            List<JobEvent> events,
            Optional<CapturedRepairVerification.Result> verification,
            Optional<RepairVerificationReport.Report> report,
            Optional<String> error,
            String ownerUserId,
            List<RepairVerificationReport.RoundSummary> rounds,
            Optional<ContentDigest> finalProposalId,
            Optional<String> errorCode) {
        public JobView {
            events = List.copyOf(events);
            ownerUserId = normalizeOwner(ownerUserId);
            rounds = rounds == null ? List.of() : List.copyOf(rounds);
            finalProposalId = finalProposalId == null ? Optional.empty() : finalProposalId;
            errorCode = errorCode == null ? Optional.empty() : errorCode;
        }

        public JobView(
                UUID jobId,
                ContentDigest proposalId,
                Stage stage,
                boolean terminal,
                Instant createdAt,
                Instant updatedAt,
                List<JobEvent> events,
                Optional<CapturedRepairVerification.Result> verification,
                Optional<RepairVerificationReport.Report> report,
                Optional<String> error,
                String ownerUserId,
                List<RepairVerificationReport.RoundSummary> rounds,
                Optional<ContentDigest> finalProposalId) {
            this(jobId, proposalId, stage, terminal, createdAt, updatedAt, events, verification, report, error,
                    ownerUserId, rounds, finalProposalId,
                    stage == Stage.INTERRUPTED_ON_RESTART ? Optional.of("INTERRUPTED_ON_RESTART") : Optional.empty());
        }

        public JobView(
                UUID jobId,
                ContentDigest proposalId,
                Stage stage,
                boolean terminal,
                Instant createdAt,
                Instant updatedAt,
                List<JobEvent> events,
                Optional<CapturedRepairVerification.Result> verification,
                Optional<RepairVerificationReport.Report> report,
                Optional<String> error,
                String ownerUserId) {
            this(jobId, proposalId, stage, terminal, createdAt, updatedAt, events, verification, report, error,
                    ownerUserId, report.map(RepairVerificationReport.Report::rounds).orElse(List.of()),
                    report.map(r -> r.provenance().proposalIdentity()));
        }

        public JobView(
                UUID jobId,
                ContentDigest proposalId,
                Stage stage,
                boolean terminal,
                Instant createdAt,
                Instant updatedAt,
                List<JobEvent> events,
                Optional<CapturedRepairVerification.Result> verification,
                Optional<RepairVerificationReport.Report> report,
                Optional<String> error) {
            this(jobId, proposalId, stage, terminal, createdAt, updatedAt, events, verification, report, error,
                    SafePatchApplier.DEFAULT_OWNER);
        }

        /** Effective proposal identity verified by this job (self-healed final proposal when closed-loop advanced it). */
        public ContentDigest effectiveProposalId() {
            return report.map(r -> r.provenance().proposalIdentity())
                    .or(() -> finalProposalId)
                    .orElse(proposalId);
        }
    }

    @FunctionalInterface
    public interface Task {
        ExecutionOutcome execute(Progress progress, java.util.function.BooleanSupplier cancelled) throws Exception;
    }

    public record ExecutionOutcome(
            CapturedRepairVerification.Result verification,
            RepairVerificationReport.Report report) {}

    public interface Progress {
        void stage(Stage stage, String message, Map<String, Object> detail);
        default void stage(Stage stage, String message) { stage(stage, message, Map.of()); }
        default void recordRound(RepairVerificationReport.RoundSummary round) {}
    }

    private static final class JobRecord {
        final UUID id;
        final String ownerUserId;
        final ContentDigest proposalId;
        final Instant createdAt;
        final RepairJobStore store;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicLong seq = new AtomicLong(0);
        final List<JobEvent> events = new ArrayList<>();
        final List<RepairVerificationReport.RoundSummary> rounds = new ArrayList<>();
        final CopyOnWriteArrayList<Consumer<JobEvent>> listeners = new CopyOnWriteArrayList<>();
        volatile Stage stage = Stage.QUEUED;
        volatile Instant updatedAt;
        volatile ContentDigest finalProposalId;
        volatile CapturedRepairVerification.Result verification;
        volatile RepairVerificationReport.Report report;
        volatile String error;
        volatile String errorCode;
        volatile Future<?> future;

        JobRecord(UUID id, String ownerUserId, ContentDigest proposalId, RepairJobStore store) {
            this.id = id;
            this.ownerUserId = normalizeOwner(ownerUserId);
            this.proposalId = proposalId;
            this.finalProposalId = proposalId;
            this.createdAt = Instant.now();
            this.updatedAt = this.createdAt;
            this.store = store;
        }

        synchronized JobEvent emit(Stage nextStage, String message, Map<String, Object> detail) {
            if (isTerminalStage(this.stage)) {
                return null;
            }
            this.stage = nextStage;
            this.updatedAt = Instant.now();
            String safeMessage = message == null ? "" : com.evolution.backend.query.llm.LlmTransport.redactSecrets(message).text();
            Map<String, Object> safeDetail = redactDetailMap(detail);
            var ev = new JobEvent(seq.incrementAndGet(), this.updatedAt, nextStage, safeMessage, safeDetail);
            if (events.size() < 256) events.add(ev);
            if (store != null) {
                try { store.appendEvent(id, ev); } catch (RuntimeException ignored) {}
            }
            for (var listener : listeners) {
                try { listener.accept(ev); } catch (RuntimeException ignored) {}
            }
            return ev;
        }

        synchronized boolean finishCompleted(
                CapturedRepairVerification.Result nextVerification,
                RepairVerificationReport.Report nextReport,
                String summaryMessage,
                Map<String, Object> doneDetail) {
            if (isTerminalStage(this.stage) || cancelled.get()) {
                return false;
            }
            this.verification = nextVerification;
            this.report = nextReport;
            if (nextReport != null) {
                this.finalProposalId = nextReport.provenance().proposalIdentity();
                if (store != null) {
                    try { store.completeJob(id, nextReport); } catch (RuntimeException ignored) {}
                }
            }
            emit(Stage.REPORT_READY, summaryMessage, doneDetail);
            return true;
        }

        synchronized boolean finishTerminalFailure(Stage terminalStage, String errorCode, String message, Map<String, Object> detail) {
            if (isTerminalStage(this.stage)) {
                return false;
            }
            String safeMsg = message == null ? "" : com.evolution.backend.query.llm.LlmTransport.redactSecrets(message).text();
            if (terminalStage == Stage.FAILED || terminalStage == Stage.INTERRUPTED_ON_RESTART) {
                this.error = safeMsg;
            }
            this.errorCode = (errorCode == null || errorCode.isBlank()) ? terminalStage.name() : errorCode.strip();
            if (store != null) {
                try { store.failJob(id, terminalStage, errorCode, safeMsg); } catch (RuntimeException ignored) {}
            }
            emit(terminalStage, safeMsg, detail);
            return true;
        }

        synchronized void addRound(RepairVerificationReport.RoundSummary summary) {
            if (summary == null) return;
            rounds.add(summary);
            if (summary.proposalId() != null) {
                this.finalProposalId = summary.proposalId();
            }
            this.updatedAt = Instant.now();
            if (store != null) {
                try { store.updateRounds(id, this.finalProposalId, rounds); } catch (RuntimeException ignored) {}
            }
        }

        private static Map<String, Object> redactDetailMap(Map<String, Object> detail) {
            if (detail == null || detail.isEmpty()) {
                return Map.of();
            }
            var out = new LinkedHashMap<String, Object>(detail.size());
            for (var e : detail.entrySet()) {
                String safeKey = e.getKey() == null ? "" : com.evolution.backend.query.llm.LlmTransport.redactSecrets(e.getKey()).text();
                out.put(safeKey, redactDetailValue(e.getValue(), 0));
            }
            return out;
        }

        private static Object redactDetailValue(Object value, int depth) {
            if (value == null) {
                return null;
            }
            if (depth > 8) {
                return "[TRUNCATED]";
            }
            if (value instanceof String s) {
                return s.isEmpty() ? s : com.evolution.backend.query.llm.LlmTransport.redactSecrets(s).text();
            }
            if (value instanceof Map<?, ?> map) {
                var out = new LinkedHashMap<Object, Object>(map.size());
                for (var e : map.entrySet()) {
                    Object k = e.getKey() instanceof String ks
                            ? com.evolution.backend.query.llm.LlmTransport.redactSecrets(ks).text()
                            : e.getKey();
                    out.put(k, redactDetailValue(e.getValue(), depth + 1));
                }
                return Collections.unmodifiableMap(out);
            }
            if (value instanceof List<?> list) {
                var out = new ArrayList<Object>(list.size());
                for (Object item : list) {
                    out.add(redactDetailValue(item, depth + 1));
                }
                return Collections.unmodifiableList(out);
            }
            return value;
        }

        synchronized JobView snapshot() {
            boolean terminal = isTerminalStage(stage);
            List<RepairVerificationReport.RoundSummary> effRounds = !rounds.isEmpty()
                    ? List.copyOf(rounds)
                    : (report != null ? report.rounds() : List.of());
            ContentDigest effProp = report != null ? report.provenance().proposalIdentity() : finalProposalId;
            return new JobView(id, proposalId, stage, terminal, createdAt, updatedAt, List.copyOf(events),
                    Optional.ofNullable(verification), Optional.ofNullable(report), Optional.ofNullable(error),
                    ownerUserId, effRounds, Optional.ofNullable(effProp), Optional.ofNullable(errorCode));
        }
    }

    private final ExecutorService executor;
    private final RepairJobStore jobStore;
    private final Map<UUID, JobRecord> jobs = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, JobRecord> eldest) {
            return size() > MAX_RETAINED_JOBS && eldest.getValue().snapshot().terminal();
        }
    });

    public RepairVerificationJobs() {
        this(2, null);
    }

    public RepairVerificationJobs(int parallelism) {
        this(parallelism, null);
    }

    public RepairVerificationJobs(int parallelism, RepairJobStore jobStore) {
        this.jobStore = jobStore;
        this.executor = Executors.newFixedThreadPool(Math.max(1, Math.min(4, parallelism)), r -> {
            Thread t = new Thread(r, "evo-repair-verify-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public RepairJobStore store() {
        return jobStore;
    }

    public JobView submit(ContentDigest proposalId, Task task) {
        return submit(SafePatchApplier.DEFAULT_OWNER, proposalId, "", Optional.empty(), task);
    }

    public JobView submit(String ownerUserId, ContentDigest proposalId, Task task) {
        return submit(ownerUserId, proposalId, "", Optional.empty(), task);
    }

    public JobView submit(
            String ownerUserId,
            ContentDigest proposalId,
            String baselineGraphDigest,
            Optional<String> baselineReportDigest,
            Task task) {
        Objects.requireNonNull(proposalId);
        Objects.requireNonNull(task);
        String effectiveOwner = normalizeOwner(ownerUserId);
        UUID id = UUID.randomUUID();
        var rec = new JobRecord(id, effectiveOwner, proposalId, jobStore);
        if (jobStore != null) {
            jobStore.createJob(id, effectiveOwner, proposalId, baselineGraphDigest, baselineReportDigest, rec.createdAt);
        }
        rec.emit(Stage.QUEUED, "Queued isolated repair verification", Map.of("proposal", proposalId.value()));
        jobs.put(id, rec);

        Progress progress = new Progress() {
            @Override
            public void stage(Stage stage, String message, Map<String, Object> detail) {
                rec.emit(stage, message, detail);
            }

            @Override
            public void recordRound(RepairVerificationReport.RoundSummary round) {
                rec.addRound(round);
            }
        };

        rec.future = executor.submit(() -> {
            try {
                if (rec.cancelled.get()) {
                    rec.finishTerminalFailure(Stage.CANCELLED, "CANCELLED", "Verification cancelled before start", Map.of());
                    return;
                }
                var outcome = task.execute(progress, rec.cancelled::get);
                if (rec.cancelled.get()) {
                    rec.finishTerminalFailure(Stage.CANCELLED, "CANCELLED", "Verification cancelled", Map.of());
                    return;
                }
                var doneDetail = new LinkedHashMap<String, Object>();
                if (outcome.report() != null) {
                    doneDetail.put("verdict", outcome.report().verdict().name());
                    doneDetail.put("trustLabel", outcome.report().trustLabel().name());
                    doneDetail.put("reportIdentity", outcome.report().reportIdentity().value());
                    outcome.report().behavioralDiff().ifPresent(b -> doneDetail.put("behavioralVerdict", b.verdict().name()));
                    outcome.report().springPreservation().ifPresent(s -> doneDetail.put("springPreservationStatus", s.status().name()));
                    doneDetail.put("roundsCount", outcome.report().rounds().size());
                }
                String summary = outcome.report() != null ? outcome.report().executiveSummary() : "Verification complete";
                if (!rec.finishCompleted(outcome.verification(), outcome.report(), summary, doneDetail)
                        && rec.cancelled.get()) {
                    rec.finishTerminalFailure(Stage.CANCELLED, "CANCELLED", "Verification cancelled", Map.of());
                }
            } catch (Exception failed) {
                if (rec.cancelled.get()) {
                    rec.finishTerminalFailure(Stage.CANCELLED, "CANCELLED", "Verification cancelled", Map.of());
                } else {
                    String rawMsg = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
                    String msg = com.evolution.backend.query.llm.LlmTransport.redactSecrets(rawMsg).text();
                    rec.finishTerminalFailure(Stage.FAILED, "VERIFICATION_FAILED", msg, Map.of("error", msg));
                }
            }
        });
        return rec.snapshot();
    }

    public Optional<JobView> get(UUID jobId) {
        return get(SafePatchApplier.DEFAULT_OWNER, jobId);
    }

    public Optional<JobView> get(String ownerUserId, UUID jobId) {
        var rec = jobs.get(jobId);
        if (rec != null) {
            requireOwner(rec, ownerUserId);
            return Optional.of(rec.snapshot());
        }
        if (jobStore != null) {
            return jobStore.findJob(ownerUserId, jobId).map(RepairVerificationJobs::fromPersisted);
        }
        return Optional.empty();
    }

    public List<JobView> list() {
        return list(SafePatchApplier.DEFAULT_OWNER);
    }

    public List<JobView> list(String ownerUserId) {
        String effectiveOwner = normalizeOwner(ownerUserId);
        var merged = new LinkedHashMap<UUID, JobView>();
        synchronized (jobs) {
            for (var rec : jobs.values()) {
                if (rec.ownerUserId.equals(effectiveOwner)) {
                    merged.put(rec.id, rec.snapshot());
                }
            }
        }
        if (jobStore != null) {
            for (var row : jobStore.listJobs(effectiveOwner, 100)) {
                merged.putIfAbsent(row.jobId(), fromPersisted(row));
            }
        }
        var sorted = new ArrayList<>(merged.values());
        sorted.sort(Comparator.comparing(JobView::createdAt).reversed());
        return List.copyOf(sorted);
    }

    public Optional<String> reportMarkdown(UUID jobId) {
        return reportMarkdown(SafePatchApplier.DEFAULT_OWNER, jobId);
    }

    public Optional<String> reportMarkdown(String ownerUserId, UUID jobId) {
        return get(ownerUserId, jobId).flatMap(JobView::report).map(RepairVerificationReport.Report::markdownReport);
    }

    public boolean cancel(UUID jobId) {
        return cancel(SafePatchApplier.DEFAULT_OWNER, jobId);
    }

    public boolean cancel(String ownerUserId, UUID jobId) {
        var rec = jobs.get(jobId);
        if (rec == null) {
            if (jobStore != null) {
                var persisted = jobStore.findJob(ownerUserId, jobId);
                if (persisted.isPresent()) {
                    return true; // Already persisted & terminal from a prior lifecycle
                }
            }
            return false;
        }
        requireOwner(rec, ownerUserId);
        rec.cancelled.set(true);
        var f = rec.future;
        if (f != null) f.cancel(true);
        rec.finishTerminalFailure(Stage.CANCELLED, "CANCELLED", "Cancelled by operator", Map.of());
        return true;
    }

    /**
     * Replays missed events since {@code afterSeq} and registers a live listener until closed.
     * Compatible with HTTP SSE endpoints and persisted terminal jobs after restart.
     */
    public AutoCloseable subscribe(UUID jobId, long afterSeq, Consumer<JobEvent> listener) {
        return subscribe(SafePatchApplier.DEFAULT_OWNER, jobId, afterSeq, listener);
    }

    public AutoCloseable subscribe(String ownerUserId, UUID jobId, long afterSeq, Consumer<JobEvent> listener) {
        var rec = jobs.get(jobId);
        if (rec != null) {
            requireOwner(rec, ownerUserId);
            synchronized (rec) {
                for (var ev : rec.events) {
                    if (ev.seq() > afterSeq) listener.accept(ev);
                }
                rec.listeners.add(listener);
            }
            return () -> rec.listeners.remove(listener);
        }
        if (jobStore != null) {
            var persisted = jobStore.findJob(ownerUserId, jobId);
            if (persisted.isPresent()) {
                for (var ev : persisted.orElseThrow().events()) {
                    if (ev.seq() > afterSeq) listener.accept(ev);
                }
                return () -> {};
            }
        }
        throw new IllegalArgumentException("Unknown repair verification job: " + jobId);
    }

    private static JobView fromPersisted(RepairJobStore.PersistedJobRow row) {
        Optional<CapturedRepairVerification.Result> reconstructed = reconstructVerification(row);
        Optional<String> effectiveErrorCode = row.errorCode().filter(s -> !s.isBlank())
                .or(() -> row.stage() == Stage.INTERRUPTED_ON_RESTART ? Optional.of("INTERRUPTED_ON_RESTART") : Optional.empty());
        return new JobView(
                row.jobId(),
                row.proposalId(),
                row.stage(),
                row.terminal(),
                row.createdAt(),
                row.updatedAt(),
                row.events(),
                reconstructed,
                row.report(),
                row.errorMessage(),
                row.ownerUserId(),
                row.rounds(),
                row.finalProposalId(),
                effectiveErrorCode);
    }

    /**
     * Reconstructs a verification receipt from a persisted {@link RepairVerificationReport.Report}
     * (or persisted {@code REJECTED} verdict row) so post-restart publish/apply gates preserve
     * {@code VERIFIED} proofs and continue to hard-block {@code REJECTED} proposals.
     */
    public static Optional<CapturedRepairVerification.Result> reconstructVerificationFromReport(
            RepairVerificationReport.Report report) {
        if (report == null) return Optional.empty();
        var prov = report.provenance();
        var sbx = report.sandbox();
        var snap = new com.evolution.analysis.contract.identity.SnapshotIdentity(
                "snapshot:" + prov.baselineGraph().value());
        var tree = new CapturedRepairVerification.TreeSummary(snap, prov.edits().size());
        List<String> modules = sbx.modules().isEmpty() ? List.of(".") : sbx.modules();
        var plan = new CapturedRepairVerification.Plan(
                prov.proposalIdentity(),
                prov.patchDigest(),
                prov.planIdentity(),
                prov.planIdentity(),
                tree, tree, tree,
                prov.edits(),
                modules,
                List.of());
        Optional<CapturedRepairVerification.IsolatedProof> isolatedProof = Optional.empty();
        if (sbx.executed()
                && sbx.captureDigest().isPresent()
                && sbx.imageDigest().isPresent()
                && sbx.inputsDigest().isPresent()) {
            boolean skipped = report.checks().stream()
                    .anyMatch(c -> "ISOLATED_BUILD_PASSED_TESTS_SKIPPED".equals(c.reason()))
                    || report.behavioralDiff()
                            .map(bd -> bd.verdict() == BehavioralDiffService.BehavioralVerdict.UNKNOWN_UNTESTED
                                    && bd.summary().contains("skipTests=true"))
                            .orElse(false);
            var proofChecks = new ArrayList<String>();
            if (sbx.compileExit() == 0) proofChecks.add("candidate.compile");
            if (!skipped && sbx.compileExit() == 0 && sbx.testExit() == 0
                    && sbx.testsFailed() == 0 && sbx.testsErrors() == 0) {
                proofChecks.add("candidate.test");
            }
            isolatedProof = Optional.of(new CapturedRepairVerification.IsolatedProof(
                    sbx.captureDigest().orElseThrow(),
                    sbx.imageDigest().orElseThrow(),
                    sbx.inputsDigest().orElseThrow(),
                    sbx.buildTool(),
                    modules,
                    sbx.compileExit(),
                    sbx.testExit(),
                    sbx.testsRun(),
                    sbx.testsFailed(),
                    sbx.testsErrors(),
                    ContentDigest.sha256Utf8("persisted-census:" + sbx.captureDigest().orElseThrow().value()),
                    sbx.classCount(),
                    List.copyOf(proofChecks),
                    sbx.isolationMode(),
                    sbx.failingTests(),
                    sbx.logTail(),
                    Optional.empty(),
                    skipped));
        }
        return Optional.of(new CapturedRepairVerification.Result(
                CapturedRepairVerification.VERSION,
                report.verdict(),
                prov.proposalIdentity(),
                prov.planIdentity(),
                plan,
                report.checks(),
                Optional.empty(),
                Optional.empty(),
                isolatedProof,
                report.patch() == null ? "" : report.patch()));
    }

    private static Optional<CapturedRepairVerification.Result> reconstructVerification(RepairJobStore.PersistedJobRow row) {
        if (row.report().isPresent()) {
            return reconstructVerificationFromReport(row.report().orElseThrow());
        }
        if (row.verdict().isPresent() && "REJECTED".equalsIgnoreCase(row.verdict().orElseThrow().strip())) {
            ContentDigest propId = row.finalProposalId().orElse(row.proposalId());
            ContentDigest fallbackPatch = row.rounds().stream()
                    .filter(r -> propId.equals(r.proposalId()) && r.patchDigest() != null)
                    .map(RepairVerificationReport.RoundSummary::patchDigest)
                    .findFirst()
                    .orElse(propId);
            String snapHex = (row.baselineGraphDigest() != null && row.baselineGraphDigest().startsWith("sha256:")
                    && row.baselineGraphDigest().length() == 71)
                    ? row.baselineGraphDigest().substring(7)
                    : propId.value().substring(7);
            var tree = new CapturedRepairVerification.TreeSummary(
                    new com.evolution.analysis.contract.identity.SnapshotIdentity("snapshot:sha256:" + snapHex), 0);
            var plan = new CapturedRepairVerification.Plan(
                    propId, fallbackPatch, propId, propId, tree, tree, tree, List.of(), List.of("."), List.of());
            return Optional.of(new CapturedRepairVerification.Result(
                    CapturedRepairVerification.VERSION,
                    CapturedRepairVerification.Verdict.REJECTED,
                    propId,
                    propId,
                    plan,
                    List.of(new CapturedRepairVerification.Check(
                            "persisted-verdict", CapturedRepairVerification.Status.FAILED,
                            "PERSISTED_VERDICT_REJECTED", Optional.empty())),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    ""));
        }
        return Optional.empty();
    }

    private static String normalizeOwner(String ownerUserId) {
        return (ownerUserId == null || ownerUserId.isBlank()) ? SafePatchApplier.DEFAULT_OWNER : ownerUserId.strip();
    }

    private static void requireOwner(JobRecord rec, String ownerUserId) {
        if (!rec.ownerUserId.equals(normalizeOwner(ownerUserId))) {
            throw new SafePatchApplier.AccessDeniedException("Repair verification job belongs to another user.");
        }
    }

    public static String formatSseEvent(JobEvent ev) {
        String data = CanonicalJson.write(Map.of(
                "seq", ev.seq(),
                "timestamp", ev.timestamp().toString(),
                "stage", ev.stage().name(),
                "message", ev.message(),
                "detail", ev.detail()));
        return "id: " + ev.seq() + "\nevent: stage\ndata: " + data + "\n\n";
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
