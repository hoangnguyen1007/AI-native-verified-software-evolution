package com.evolution.analysis.contract.source;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;

/** Parser-neutral module identity and repository-relative placement. */
public record ModuleDescriptor(
        ModuleIdentity identity,
        RepositoryIdentity repository,
        String path,
        String displayName)
        implements Comparable<ModuleDescriptor> {

    public ModuleDescriptor {
        ContractChecks.notNull(identity, "module identity");
        ContractChecks.notNull(repository, "repository");
        path = ContractChecks.repositoryRelativePath(path, "module path");
        displayName = ContractChecks.text(displayName, "module display name");
        ModuleIdentity expected = ModuleIdentity.from(repository, path);
        if (!identity.equals(expected)) {
            throw new IllegalArgumentException("module identity does not match repository and path");
        }
    }

    public static ModuleDescriptor create(
            RepositoryIdentity repository, String path, String displayName) {
        return new ModuleDescriptor(
                ModuleIdentity.from(repository, path), repository, path, displayName);
    }

    @Override
    public int compareTo(ModuleDescriptor other) {
        return identity.compareTo(other.identity);
    }
}
