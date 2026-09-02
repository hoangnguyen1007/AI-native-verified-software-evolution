package com.evolution.analysis.contract.identity;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.contract.common.ContractChecks;
import java.util.List;

public record ModuleIdentity(String value) implements CanonicalIdentifier, Comparable<ModuleIdentity> {

    public ModuleIdentity {
        value = IdentitySupport.require(value, "module");
    }

    public static ModuleIdentity from(RepositoryIdentity repository, String repositoryRelativePath) {
        ContractChecks.notNull(repository, "repository");
        ContractChecks.repositoryRelativePath(repositoryRelativePath, "module path");
        return new ModuleIdentity(IdentitySupport.derive(
                "module", List.of(repository.value(), repositoryRelativePath)));
    }

    @Override
    public int compareTo(ModuleIdentity other) {
        return value.compareTo(other.value);
    }
}
