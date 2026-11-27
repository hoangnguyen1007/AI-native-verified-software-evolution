package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.KnowledgeGraph;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Passive reanalysis port for captured repair proposals without target code execution. */
public final class RepairReanalysis {
    public static final String VERSION = "architecture.repair-reanalysis:captured-v1";

    public record Result(String version, String status, Optional<String> reason, ContentDigest proposal,
                         SnapshotIdentity candidateSnapshot, Optional<ContentDigest> candidateGraph,
                         Optional<ContentDigest> candidateReport, Optional<SnapshotIdentity> controlSnapshot,
                         Optional<ContentDigest> controlGraph, Optional<ContentDigest> controlReport,
                         List<ContentDigest> allCandidateReports) {}

    @FunctionalInterface
    public interface Port {
        Result reanalyze(CapturedRepair.Proposal proposal, KnowledgeGraph baseline,
                         PublishedReports.Report baselineReport, boolean includeControl,
                         BooleanSupplier cancelled);

        /** Passive reanalysis for ad-hoc proposals without a baseline policy report (computes candidate KnowledgeGraph for Score v2 delta). */
        default Result reanalyzeAdHoc(CapturedRepair.Proposal proposal, KnowledgeGraph baseline,
                                      BooleanSupplier cancelled) {
            return reanalyze(proposal, baseline, null, false, cancelled);
        }
    }

    private RepairReanalysis() {}
}
