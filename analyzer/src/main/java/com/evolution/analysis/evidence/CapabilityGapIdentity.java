package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.CanonicalIdentifier;

public record CapabilityGapIdentity(String value) implements CanonicalIdentifier, Comparable<CapabilityGapIdentity> {
    public CapabilityGapIdentity { value = EvidenceIdentity.require(value, "gap"); }
    @Override public int compareTo(CapabilityGapIdentity other) { return value.compareTo(other.value); }
}
