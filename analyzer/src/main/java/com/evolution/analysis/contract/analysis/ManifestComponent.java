package com.evolution.analysis.contract.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;

/** Version and exact content digest for an analysis-defining component. */
public record ManifestComponent(VersionedIdentifier component, ContentDigest contentDigest)
        implements Comparable<ManifestComponent> {

    public ManifestComponent {
        ContractChecks.notNull(component, "manifest component");
        ContractChecks.notNull(contentDigest, "manifest component digest");
    }

    @Override
    public int compareTo(ManifestComponent other) {
        int comparison = component.compareTo(other.component);
        return comparison != 0 ? comparison : contentDigest.compareTo(other.contentDigest);
    }
}
