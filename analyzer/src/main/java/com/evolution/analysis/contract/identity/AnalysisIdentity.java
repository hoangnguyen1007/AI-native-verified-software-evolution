package com.evolution.analysis.contract.identity;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import java.util.List;

public record AnalysisIdentity(String value)
        implements CanonicalIdentifier, Comparable<AnalysisIdentity> {

    public AnalysisIdentity {
        value = IdentitySupport.require(value, "analysis");
    }

    public static AnalysisIdentity fromCanonicalManifestInputs(String canonicalManifestInputs) {
        return new AnalysisIdentity(
                IdentitySupport.derive("analysis", List.of(canonicalManifestInputs)));
    }

    @Override
    public int compareTo(AnalysisIdentity other) {
        return value.compareTo(other.value);
    }
}
