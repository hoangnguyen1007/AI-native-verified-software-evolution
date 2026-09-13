package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.CanonicalIdentifier;

/** One finite modeled space. Intentionally a different type and hash domain from M1 ConfigurationIdentity. */
public record ConfigurationSpaceIdentity(String value) implements CanonicalIdentifier, Comparable<ConfigurationSpaceIdentity> {
    public ConfigurationSpaceIdentity { value = ConditionIdentitySupport.require(value, "spring-configuration-space"); }
    @Override public int compareTo(ConfigurationSpaceIdentity other) { return value.compareTo(other.value); }
}
