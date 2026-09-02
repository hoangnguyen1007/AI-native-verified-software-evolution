package com.evolution.analysis.contract.common;

/** A namespaced semantic identifier paired with an explicit contract or formula version. */
public record VersionedIdentifier(String id, String version)
        implements Comparable<VersionedIdentifier> {

    public VersionedIdentifier {
        id = ContractChecks.namespacedId(id, "id");
        version = ContractChecks.token(version, "version");
    }

    @Override
    public int compareTo(VersionedIdentifier other) {
        int idComparison = id.compareTo(other.id);
        return idComparison != 0 ? idComparison : version.compareTo(other.version);
    }
}
