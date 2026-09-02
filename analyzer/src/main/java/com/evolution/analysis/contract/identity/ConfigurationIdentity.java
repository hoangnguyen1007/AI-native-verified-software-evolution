package com.evolution.analysis.contract.identity;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.List;
import java.util.Map;

public record ConfigurationIdentity(String value)
        implements CanonicalIdentifier, Comparable<ConfigurationIdentity> {

    public ConfigurationIdentity {
        value = IdentitySupport.require(value, "configuration");
    }

    public static ConfigurationIdentity from(
            VersionedIdentifier schema, Map<String, String> values) {
        ContractChecks.notNull(schema, "configuration schema");
        ContractChecks.notNull(values, "configuration values");
        return new ConfigurationIdentity(IdentitySupport.derive(
                "configuration", List.of(CanonicalJson.write(schema), CanonicalJson.write(values))));
    }

    @Override
    public int compareTo(ConfigurationIdentity other) {
        return value.compareTo(other.value);
    }
}
