package com.evolution.analysis.contract.analysis;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.semantic.Diagnostic;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Runtime provenance around the stable manifest; timestamps never enter analysis identity. */
public record AnalysisProvenance(
        AnalysisIdentity analysisIdentity,
        AnalysisManifest manifest,
        Instant startedAt,
        Instant completedAt,
        List<Diagnostic> diagnostics,
        List<String> limitations) {

    public AnalysisProvenance {
        ContractChecks.notNull(analysisIdentity, "analysis identity");
        ContractChecks.notNull(manifest, "analysis manifest");
        ContractChecks.notNull(startedAt, "analysis start time");
        ContractChecks.notNull(completedAt, "analysis completion time");
        diagnostics = ContractChecks.sortedDistinct(
                diagnostics, Comparator.naturalOrder(), "analysis diagnostics");
        limitations = ContractChecks.sortedStrings(limitations, "analysis limitations");
        if (!analysisIdentity.equals(manifest.identity())) {
            throw new IllegalArgumentException("provenance identity must equal manifest identity");
        }
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("analysis completion time precedes start time");
        }
    }

    public static AnalysisProvenance create(
            AnalysisManifest manifest,
            Instant startedAt,
            Instant completedAt,
            List<Diagnostic> diagnostics,
            List<String> limitations) {
        return new AnalysisProvenance(
                manifest.identity(), manifest, startedAt, completedAt, diagnostics, limitations);
    }
}
