package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;

/** Advisory only, with explicit ordering policy. Presence never grants trust, permission or execution authority. */
public record CandidateProvider(
        VersionedIdentifier provider,
        VersionedIdentifier orderingPolicy,
        int rank,
        String rationale) {
    public CandidateProvider {
        ContractChecks.notNull(provider, "candidate provider");
        ContractChecks.notNull(orderingPolicy, "candidate ordering policy");
        if (rank < 0) throw new IllegalArgumentException("Candidate-provider rank must not be negative");
        rationale = ContractChecks.text(rationale, "candidate-provider rationale");
    }
}
