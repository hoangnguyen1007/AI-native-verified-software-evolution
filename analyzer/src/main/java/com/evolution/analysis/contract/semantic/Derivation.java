package com.evolution.analysis.contract.semantic;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import java.util.List;

/** Versioned derivation method and canonical input identities. */
public record Derivation(
        DerivationKind kind, VersionedIdentifier method, List<String> inputIdentities) {

    public Derivation {
        ContractChecks.notNull(kind, "derivation kind");
        ContractChecks.notNull(method, "derivation method");
        inputIdentities = ContractChecks.sortedStrings(inputIdentities, "derivation inputs");
        if (kind != DerivationKind.DIRECT && inputIdentities.isEmpty()) {
            throw new IllegalArgumentException("derived and inferred facts require input identities");
        }
    }
}
