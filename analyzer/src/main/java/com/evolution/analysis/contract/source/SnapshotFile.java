package com.evolution.analysis.contract.source;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;

/** One normalized file entry in the complete repository snapshot inventory. */
public record SnapshotFile(String path, ContentDigest contentDigest)
        implements Comparable<SnapshotFile> {

    public SnapshotFile {
        path = ContractChecks.repositoryRelativePath(path, "snapshot file path");
        ContractChecks.notNull(contentDigest, "snapshot file content digest");
    }

    public static SnapshotFile from(SourceDocument document) {
        ContractChecks.notNull(document, "source document");
        return new SnapshotFile(document.path(), document.contentDigest());
    }

    @Override
    public int compareTo(SnapshotFile other) {
        return path.compareTo(other.path);
    }
}
