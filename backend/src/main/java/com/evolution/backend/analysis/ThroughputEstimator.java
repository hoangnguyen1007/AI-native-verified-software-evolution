package com.evolution.backend.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Thread-safe EWMA throughput ({@code files/sec}) and ETA estimator with monotonic stage-weighted
 * overall progress calculation.
 *
 * <p>Uses {@link BigDecimal} for all fractional metrics so values serialize cleanly through
 * {@code CanonicalJson} (which rejects binary floating point).
 */
public final class ThroughputEstimator {
    private static final double EWMA_ALPHA = 0.25;
    private static final int MIN_SAMPLES_FOR_ETA = 3;
    private static final long MIN_ELAPSED_NANOS_FOR_ETA = TimeUnit.MILLISECONDS.toNanos(1_500L);
    private static final long MAX_ETA_SECONDS = 86_400L;

    public static final String ETA_ESTIMATING = "ESTIMATING";
    public static final String ETA_AVAILABLE = "AVAILABLE";
    public static final String ETA_COMPLETE = "COMPLETE";
    public static final String ETA_INDETERMINATE = "INDETERMINATE";

    private record StageRange(double start, double end) {
        double width() {
            return Math.max(0.0, end - start);
        }
    }

    private static final EnumMap<AnalysisJobs.Stage, StageRange> STAGE_WEIGHTS = new EnumMap<>(AnalysisJobs.Stage.class);
    static {
        STAGE_WEIGHTS.put(AnalysisJobs.Stage.QUEUED, new StageRange(0.00, 0.02));
        STAGE_WEIGHTS.put(AnalysisJobs.Stage.ACQUISITION, new StageRange(0.02, 0.14));
        STAGE_WEIGHTS.put(AnalysisJobs.Stage.PLATFORM, new StageRange(0.14, 0.22));
        STAGE_WEIGHTS.put(AnalysisJobs.Stage.ANALYSIS, new StageRange(0.22, 0.80));
        STAGE_WEIGHTS.put(AnalysisJobs.Stage.GRAPH, new StageRange(0.80, 0.90));
        STAGE_WEIGHTS.put(AnalysisJobs.Stage.POLICY, new StageRange(0.90, 0.96));
        STAGE_WEIGHTS.put(AnalysisJobs.Stage.PUBLICATION, new StageRange(0.96, 1.00));
        STAGE_WEIGHTS.put(AnalysisJobs.Stage.REUSED, new StageRange(1.00, 1.00));
        STAGE_WEIGHTS.put(AnalysisJobs.Stage.FINISHED, new StageRange(1.00, 1.00));
    }

    public record Snapshot(
            BigDecimal overallRatio,
            AnalysisJobs.Stage activeStage,
            int stageDone,
            int stageTotal,
            Optional<BigDecimal> filesPerSecond,
            Optional<Long> etaSeconds,
            String etaStatus,
            long elapsedMillis,
            int sampleCount) {
        public Snapshot {
            Objects.requireNonNull(overallRatio, "overallRatio");
            Objects.requireNonNull(activeStage, "activeStage");
            Objects.requireNonNull(filesPerSecond, "filesPerSecond");
            Objects.requireNonNull(etaSeconds, "etaSeconds");
            Objects.requireNonNull(etaStatus, "etaStatus");
        }
    }

    private final LongSupplier nanoClock;
    private final long startedNanos;

    private AnalysisJobs.Stage activeStage = AnalysisJobs.Stage.QUEUED;
    private long stageStartNanos;
    private long lastSampleNanos;
    private int stageDone;
    private int stageTotal;
    private int lastSampleDone;
    private int stageSamples;
    private double ewmaFilesPerSec = Double.NaN;
    private double maxOverallRatio = 0.0;
    private boolean completed;
    private long completedNanos;

    public ThroughputEstimator() {
        this(System::nanoTime);
    }

    public ThroughputEstimator(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.startedNanos = nanoClock.getAsLong();
        this.stageStartNanos = startedNanos;
        this.lastSampleNanos = startedNanos;
    }

    public synchronized Snapshot onStageEnter(AnalysisJobs.Stage stage) {
        Objects.requireNonNull(stage, "stage");
        long now = nanoClock.getAsLong();
        if (stage == AnalysisJobs.Stage.FINISHED || stage == AnalysisJobs.Stage.REUSED) {
            return finishAt(stage, now);
        }
        if (stage != activeStage) {
            activeStage = stage;
            stageStartNanos = now;
            lastSampleNanos = now;
            stageDone = 0;
            stageTotal = 0;
            lastSampleDone = 0;
            stageSamples = 0;
            ewmaFilesPerSec = Double.NaN;
            var range = STAGE_WEIGHTS.getOrDefault(stage, new StageRange(0.0, 0.0));
            maxOverallRatio = Math.max(maxOverallRatio, range.start());
        }
        return buildSnapshot(now);
    }

    public synchronized Snapshot recordProgress(AnalysisJobs.Stage stage, int done, int total) {
        Objects.requireNonNull(stage, "stage");
        long now = nanoClock.getAsLong();
        if (completed) {
            return buildSnapshot(now);
        }
        if (stage != activeStage) {
            onStageEnter(stage);
        }
        int safeTotal = Math.max(0, total);
        int safeDone = Math.max(0, safeTotal > 0 ? Math.min(done, safeTotal) : done);
        int effectiveDone = Math.max(stageDone, safeDone);

        if (effectiveDone > lastSampleDone) {
            long deltaNanos = Math.max(0L, now - lastSampleNanos);
            if (deltaNanos > 0L) {
                double instantRate = (effectiveDone - lastSampleDone) / (deltaNanos / 1_000_000_000.0);
                if (Double.isFinite(instantRate) && instantRate > 0.0) {
                    if (stageSamples == 0 || !Double.isFinite(ewmaFilesPerSec)) {
                        ewmaFilesPerSec = instantRate;
                    } else {
                        ewmaFilesPerSec = EWMA_ALPHA * instantRate + (1.0 - EWMA_ALPHA) * ewmaFilesPerSec;
                    }
                    stageSamples++;
                    lastSampleDone = effectiveDone;
                    lastSampleNanos = now;
                }
            }
        }
        stageDone = effectiveDone;
        stageTotal = Math.max(stageTotal, safeTotal);

        var range = STAGE_WEIGHTS.getOrDefault(stage, new StageRange(0.0, 1.0));
        double stageFraction = stageTotal > 0 ? Math.min(1.0, (double) stageDone / (double) stageTotal) : 0.0;
        double candidateOverall = range.start() + range.width() * stageFraction;
        // Never report 1.0 until terminal completion
        double boundedOverall = Math.min(0.995, candidateOverall);
        maxOverallRatio = Math.max(maxOverallRatio, boundedOverall);
        return buildSnapshot(now);
    }

    public synchronized Snapshot markCompleted() {
        return finishAt(AnalysisJobs.Stage.FINISHED, nanoClock.getAsLong());
    }

    public synchronized Snapshot snapshot() {
        return buildSnapshot(nanoClock.getAsLong());
    }

    private Snapshot finishAt(AnalysisJobs.Stage stage, long now) {
        activeStage = stage;
        completed = true;
        completedNanos = now;
        maxOverallRatio = 1.0;
        if (stageTotal > 0) {
            stageDone = stageTotal;
        }
        return buildSnapshot(now);
    }

    private Snapshot buildSnapshot(long now) {
        long refNanos = completed ? completedNanos : now;
        long elapsedMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(refNanos - startedNanos));
        BigDecimal ratio = scale4(completed ? 1.0 : Math.min(0.995, Math.max(0.0, maxOverallRatio)));

        if (completed) {
            Optional<BigDecimal> finalRate = Double.isFinite(ewmaFilesPerSec) && ewmaFilesPerSec > 0.0
                    ? Optional.of(scale2(ewmaFilesPerSec))
                    : Optional.empty();
            return new Snapshot(ratio, activeStage, stageDone, stageTotal, finalRate, Optional.of(0L),
                    ETA_COMPLETE, elapsedMs, stageSamples);
        }

        Optional<BigDecimal> rate = (stageSamples >= 1 && Double.isFinite(ewmaFilesPerSec) && ewmaFilesPerSec > 0.0)
                ? Optional.of(scale2(ewmaFilesPerSec))
                : Optional.empty();

        long stageElapsedNanos = Math.max(0L, now - stageStartNanos);
        boolean warm = stageSamples >= MIN_SAMPLES_FOR_ETA
                && stageElapsedNanos >= MIN_ELAPSED_NANOS_FOR_ETA
                && Double.isFinite(ewmaFilesPerSec)
                && ewmaFilesPerSec > 0.001;

        if (!warm) {
            return new Snapshot(ratio, activeStage, stageDone, stageTotal, rate, Optional.empty(),
                    ETA_ESTIMATING, elapsedMs, stageSamples);
        }
        if (stageTotal <= 0) {
            return new Snapshot(ratio, activeStage, stageDone, stageTotal, rate, Optional.empty(),
                    ETA_INDETERMINATE, elapsedMs, stageSamples);
        }

        int remainingUnits = Math.max(0, stageTotal - stageDone);
        double stageRemainingSeconds = remainingUnits / ewmaFilesPerSec;
        var range = STAGE_WEIGHTS.getOrDefault(activeStage, new StageRange(0.0, 1.0));
        double tailWeight = Math.max(0.0, 1.0 - range.end());
        double tailSeconds = 0.0;
        if (range.width() > 0.0 && tailWeight > 0.0) {
            double estimatedFullStageSeconds = stageTotal / ewmaFilesPerSec;
            // Conservative bounded tail estimate scaled by remaining pipeline weight
            tailSeconds = estimatedFullStageSeconds * (tailWeight / range.width()) * 0.35;
        }
        long etaSec = Math.min(MAX_ETA_SECONDS, Math.max(0L, Math.round(stageRemainingSeconds + tailSeconds)));
        return new Snapshot(ratio, activeStage, stageDone, stageTotal, rate, Optional.of(etaSec),
                ETA_AVAILABLE, elapsedMs, stageSamples);
    }

    private static BigDecimal scale4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
