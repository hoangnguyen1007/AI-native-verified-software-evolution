package com.evolution.analysis.contract.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;

/** Exact logical classpath entry. Acquisition and path resolution are deliberately out of scope. */
public record ClasspathEntry(
        ClasspathEntryKind kind, String logicalName, ContentDigest contentDigest)
        implements Comparable<ClasspathEntry> {

    public ClasspathEntry {
        ContractChecks.notNull(kind, "classpath entry kind");
        logicalName = ContractChecks.text(logicalName, "classpath logical name");
        ContractChecks.notNull(contentDigest, "classpath content digest");
    }

    @Override
    public int compareTo(ClasspathEntry other) {
        int comparison = kind.compareTo(other.kind);
        if (comparison != 0) return comparison;
        comparison = logicalName.compareTo(other.logicalName);
        return comparison != 0 ? comparison : contentDigest.compareTo(other.contentDigest);
    }
}
