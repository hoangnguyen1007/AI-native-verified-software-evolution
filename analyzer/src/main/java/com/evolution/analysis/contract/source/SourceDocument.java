package com.evolution.analysis.contract.source;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;

/** Immutable source inventory entry. Source content is represented by its SHA-256 digest. */
public record SourceDocument(
        SourceDocumentIdentity identity,
        RepositoryIdentity repository,
        ModuleIdentity module,
        String path,
        ContentDigest contentDigest,
        SourceClassification classification)
        implements Comparable<SourceDocument> {

    public SourceDocument {
        ContractChecks.notNull(identity, "source identity");
        ContractChecks.notNull(repository, "repository");
        ContractChecks.notNull(module, "module");
        path = ContractChecks.repositoryRelativePath(path, "source path");
        ContractChecks.notNull(contentDigest, "source content digest");
        ContractChecks.notNull(classification, "source classification");
        SourceDocumentIdentity expected = SourceDocumentIdentity.from(repository, path);
        if (!identity.equals(expected)) {
            throw new IllegalArgumentException("source identity does not match repository and path");
        }
    }

    public static SourceDocument create(
            RepositoryIdentity repository,
            ModuleDescriptor module,
            String path,
            ContentDigest contentDigest,
            SourceClassification classification) {
        ContractChecks.notNull(module, "module");
        if (!module.repository().equals(repository)) {
            throw new IllegalArgumentException("source module belongs to a different repository");
        }
        return new SourceDocument(
                SourceDocumentIdentity.from(repository, path),
                repository,
                module.identity(),
                path,
                contentDigest,
                classification);
    }

    @Override
    public int compareTo(SourceDocument other) {
        return identity.compareTo(other.identity);
    }
}
