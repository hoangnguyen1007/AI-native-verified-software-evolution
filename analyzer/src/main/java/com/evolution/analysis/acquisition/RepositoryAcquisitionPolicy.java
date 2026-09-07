package com.evolution.analysis.acquisition;

import com.evolution.analysis.contract.common.ContractChecks;
import java.util.Comparator;
import java.util.List;

/** Explicit finite filesystem traversal and byte limits. No ambient exclusions are inferred. */
public record RepositoryAcquisitionPolicy(
        int maxFiles,
        int maxDirectories,
        long maxFileBytes,
        long maxTotalBytes,
        int maxDepth,
        List<String> excludedPaths) {
    private static final int MAX_DISCOVERED_ENTRIES = 1_000_000;

    public RepositoryAcquisitionPolicy {
        if (maxFiles < 1 || maxDirectories < 1 || maxFileBytes < 1 || maxTotalBytes < 1
                || maxDepth < 1 || maxDepth > 256 || maxFileBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException(
                    "Acquisition limits must be positive; depth must not exceed 256 and one file must fit in an immutable byte array");
        }
        if ((long) maxFiles + maxDirectories > MAX_DISCOVERED_ENTRIES) {
            throw new IllegalArgumentException("Combined file and directory limits must not exceed 1000000 entries");
        }
        excludedPaths = ContractChecks.sortedDistinct(
                excludedPaths.stream()
                        .map(path -> ContractChecks.repositoryRelativePath(path, "excluded path"))
                        .toList(),
                Comparator.naturalOrder(),
                "excluded paths");
    }

    public boolean excludes(String logicalPath) {
        ContractChecks.repositoryRelativePath(logicalPath, "logical path");
        return excludedPaths.stream().anyMatch(
                excluded -> logicalPath.equals(excluded) || logicalPath.startsWith(excluded + "/"));
    }

    public long maxDiscoveredEntries() {
        return (long) maxFiles + maxDirectories;
    }
}
