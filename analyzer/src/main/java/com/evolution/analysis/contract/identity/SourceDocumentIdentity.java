package com.evolution.analysis.contract.identity;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.contract.common.ContractChecks;
import java.util.List;

public record SourceDocumentIdentity(String value)
        implements CanonicalIdentifier, Comparable<SourceDocumentIdentity> {

    public SourceDocumentIdentity {
        value = IdentitySupport.require(value, "source");
    }

    public static SourceDocumentIdentity from(
            RepositoryIdentity repository, String repositoryRelativePath) {
        ContractChecks.notNull(repository, "repository");
        ContractChecks.repositoryRelativePath(repositoryRelativePath, "source path");
        return new SourceDocumentIdentity(IdentitySupport.derive(
                "source", List.of(repository.value(), repositoryRelativePath)));
    }

    @Override
    public int compareTo(SourceDocumentIdentity other) {
        return value.compareTo(other.value);
    }
}
