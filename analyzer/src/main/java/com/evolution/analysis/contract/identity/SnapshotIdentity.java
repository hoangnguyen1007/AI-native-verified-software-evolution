package com.evolution.analysis.contract.identity;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import java.util.List;

public record SnapshotIdentity(String value)
        implements CanonicalIdentifier, Comparable<SnapshotIdentity> {

    public SnapshotIdentity {
        value = IdentitySupport.require(value, "snapshot");
    }

    public static SnapshotIdentity from(
            RepositoryIdentity repository, ContentDigest snapshotContentDigest) {
        ContractChecks.notNull(repository, "repository");
        ContractChecks.notNull(snapshotContentDigest, "snapshot content digest");
        return new SnapshotIdentity(IdentitySupport.derive(
                "snapshot", List.of(repository.value(), snapshotContentDigest.value())));
    }

    @Override
    public int compareTo(SnapshotIdentity other) {
        return value.compareTo(other.value);
    }
}
