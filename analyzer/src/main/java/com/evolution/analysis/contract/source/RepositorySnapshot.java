package com.evolution.analysis.contract.source;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Content-addressed repository snapshot plus non-identifying revision provenance. */
public record RepositorySnapshot(
        SnapshotIdentity identity,
        RepositoryIdentity repository,
        Optional<String> revision,
        boolean dirty,
        List<SnapshotFile> files,
        List<SourceDocument> documents,
        ContentDigest contentDigest) {

    public RepositorySnapshot {
        ContractChecks.notNull(identity, "snapshot identity");
        ContractChecks.notNull(repository, "repository");
        revision = ContractChecks.notNull(revision, "revision")
                .map(value -> ContractChecks.text(value, "revision"));
        files = ContractChecks.sortedDistinct(
                files, Comparator.naturalOrder(), "snapshot files");
        documents = ContractChecks.sortedDistinct(
                documents, Comparator.naturalOrder(), "snapshot documents");
        ContractChecks.notNull(contentDigest, "snapshot content digest");
        validateDocumentRepositories(repository, documents);
        validateSourceInventory(files, documents);
        ContentDigest expectedContent = digestFiles(files);
        if (!contentDigest.equals(expectedContent)) {
            throw new IllegalArgumentException("snapshot content digest does not match file inventory");
        }
        SnapshotIdentity expectedIdentity = SnapshotIdentity.from(repository, contentDigest);
        if (!identity.equals(expectedIdentity)) {
            throw new IllegalArgumentException("snapshot identity does not match content inputs");
        }
    }

    public static RepositorySnapshot create(
            RepositoryIdentity repository,
            Optional<String> revision,
            boolean dirty,
            List<SnapshotFile> files,
            List<SourceDocument> documents) {
        List<SnapshotFile> sortedFiles = ContractChecks.sortedDistinct(
                files, Comparator.naturalOrder(), "snapshot files");
        List<SourceDocument> sorted = ContractChecks.sortedDistinct(
                documents, Comparator.naturalOrder(), "snapshot documents");
        validateDocumentRepositories(repository, sorted);
        validateSourceInventory(sortedFiles, sorted);
        ContentDigest contentDigest = digestFiles(sortedFiles);
        return new RepositorySnapshot(
                SnapshotIdentity.from(repository, contentDigest),
                repository,
                revision,
                dirty,
                sortedFiles,
                sorted,
                contentDigest);
    }

    private static void validateDocumentRepositories(
            RepositoryIdentity repository, List<SourceDocument> documents) {
        ContractChecks.notNull(repository, "repository");
        if (documents.stream().anyMatch(document -> !document.repository().equals(repository))) {
            throw new IllegalArgumentException("all snapshot documents must belong to the repository");
        }
    }

    private static void validateSourceInventory(
            List<SnapshotFile> files, List<SourceDocument> documents) {
        Map<String, ContentDigest> inventory = files.stream()
                .collect(java.util.stream.Collectors.toMap(
                        SnapshotFile::path, SnapshotFile::contentDigest));
        for (SourceDocument document : documents) {
            if (!document.contentDigest().equals(inventory.get(document.path()))) {
                throw new IllegalArgumentException(
                        "every source document must match a file in the snapshot inventory");
            }
        }
    }

    private static ContentDigest digestFiles(List<SnapshotFile> files) {
        return ContentDigest.sha256Utf8(CanonicalJson.write(files));
    }
}
