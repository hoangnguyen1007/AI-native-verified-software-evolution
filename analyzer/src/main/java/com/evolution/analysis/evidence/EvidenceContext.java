package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import java.util.Optional;

/** Stable repository scope; build-stage gaps need no fabricated analysis identity. */
public record EvidenceContext(SnapshotIdentity snapshotIdentity, Optional<AnalysisIdentity> analysisIdentity)
        implements Comparable<EvidenceContext> {
    public EvidenceContext {
        ContractChecks.notNull(snapshotIdentity, "evidence snapshot identity");
        analysisIdentity = ContractChecks.notNull(analysisIdentity, "evidence analysis identity");
    }

    public static EvidenceContext forSnapshot(SnapshotIdentity snapshotIdentity) {
        return new EvidenceContext(snapshotIdentity, Optional.empty());
    }

    /** Uses the already-derived M1 manifest identity; capability-gap output is not an identity input. */
    public static EvidenceContext forAnalysis(AnalysisManifest manifest) {
        ContractChecks.notNull(manifest, "analysis manifest");
        return new EvidenceContext(manifest.snapshot().identity(), Optional.of(manifest.identity()));
    }

    @Override public int compareTo(EvidenceContext other) {
        int snapshot = snapshotIdentity.compareTo(other.snapshotIdentity);
        if (snapshot != 0) return snapshot;
        return analysisIdentity.map(AnalysisIdentity::value).orElse("")
                .compareTo(other.analysisIdentity.map(AnalysisIdentity::value).orElse(""));
    }
}
