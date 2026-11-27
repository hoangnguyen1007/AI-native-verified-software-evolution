package com.evolution.backend.analysis;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThroughputEstimatorTest {

    @Test
    void warmUpGuardrailReturnsEstimatingBeforeThreeSamplesAnd1500ms() {
        var clock = new AtomicLong(1_000_000_000L);
        var estimator = new ThroughputEstimator(clock::get);

        estimator.onStageEnter(AnalysisJobs.Stage.ANALYSIS);

        // Sample 1 at +400ms
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(400));
        var s1 = estimator.recordProgress(AnalysisJobs.Stage.ANALYSIS, 20, 200);
        assertEquals(ThroughputEstimator.ETA_ESTIMATING, s1.etaStatus());
        assertTrue(s1.etaSeconds().isEmpty());
        assertTrue(s1.filesPerSecond().isPresent());

        // Sample 2 at +800ms
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(400));
        var s2 = estimator.recordProgress(AnalysisJobs.Stage.ANALYSIS, 40, 200);
        assertEquals(ThroughputEstimator.ETA_ESTIMATING, s2.etaStatus());
        assertTrue(s2.etaSeconds().isEmpty());

        // Sample 3 at +1200ms (still < 1500ms elapsed!)
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(400));
        var s3 = estimator.recordProgress(AnalysisJobs.Stage.ANALYSIS, 60, 200);
        assertEquals(ThroughputEstimator.ETA_ESTIMATING, s3.etaStatus());
        assertTrue(s3.etaSeconds().isEmpty());

        // Sample 4 at +1600ms (>= 3 samples and >= 1500ms -> AVAILABLE!)
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(400));
        var s4 = estimator.recordProgress(AnalysisJobs.Stage.ANALYSIS, 80, 200);
        assertEquals(ThroughputEstimator.ETA_AVAILABLE, s4.etaStatus());
        assertTrue(s4.etaSeconds().isPresent());
        assertTrue(s4.etaSeconds().orElseThrow() > 0L);
        // Constant rate of 20 files / 0.4s = 50.00 files/sec
        assertEquals(new BigDecimal("50.00"), s4.filesPerSecond().orElseThrow());
    }

    @Test
    void ewmaSmoothsSpikesAndOverallRatioIsMonotonicAndCanonicalJsonSafe() {
        var clock = new AtomicLong(10_000_000_000L);
        var estimator = new ThroughputEstimator(clock::get);

        var ratios = new java.util.ArrayList<BigDecimal>();
        ratios.add(estimator.onStageEnter(AnalysisJobs.Stage.ACQUISITION).overallRatio());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        ratios.add(estimator.recordProgress(AnalysisJobs.Stage.ACQUISITION, 50, 100).overallRatio());
        ratios.add(estimator.onStageEnter(AnalysisJobs.Stage.PLATFORM).overallRatio());
        ratios.add(estimator.onStageEnter(AnalysisJobs.Stage.ANALYSIS).overallRatio());

        // 3 steady samples at 10 files/sec, then 1 spike at 90 files/sec -> EWMA = 0.25*90 + 0.75*10 = 30.00
        for (int i = 1; i <= 3; i++) {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            ratios.add(estimator.recordProgress(AnalysisJobs.Stage.ANALYSIS, i * 10, 200).overallRatio());
        }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        var spiked = estimator.recordProgress(AnalysisJobs.Stage.ANALYSIS, 120, 200);
        ratios.add(spiked.overallRatio());
        assertEquals(new BigDecimal("30.00"), spiked.filesPerSecond().orElseThrow());

        // Moving to later stages continues monotonically, never reaching 1.0000 before completion
        ratios.add(estimator.onStageEnter(AnalysisJobs.Stage.GRAPH).overallRatio());
        ratios.add(estimator.onStageEnter(AnalysisJobs.Stage.POLICY).overallRatio());
        var pub = estimator.recordProgress(AnalysisJobs.Stage.PUBLICATION, 1, 1);
        ratios.add(pub.overallRatio());
        assertTrue(pub.overallRatio().compareTo(BigDecimal.ONE) < 0);

        var done = estimator.markCompleted();
        ratios.add(done.overallRatio());
        assertEquals(new BigDecimal("1.0000"), done.overallRatio());
        assertEquals(ThroughputEstimator.ETA_COMPLETE, done.etaStatus());
        assertEquals(0L, done.etaSeconds().orElseThrow());

        for (int i = 1; i < ratios.size(); i++) {
            assertTrue(ratios.get(i).compareTo(ratios.get(i - 1)) >= 0,
                    "Ratio regressed at index " + i + ": " + ratios.get(i - 1) + " -> " + ratios.get(i));
        }

        // Must serialize cleanly through CanonicalJson (no binary floating point!)
        assertDoesNotThrow(() -> CanonicalJson.write(spiked));
        assertDoesNotThrow(() -> CanonicalJson.write(done));
    }
}
