package com.evolution.analysis.contract.analysis;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.ConfigurationIdentity;
import java.util.Map;

/** Versioned, immutable analysis options with a content-addressed identity. */
public record AnalysisConfiguration(
        ConfigurationIdentity identity,
        VersionedIdentifier schema,
        Map<String, String> values) {

    public AnalysisConfiguration {
        ContractChecks.notNull(identity, "configuration identity");
        ContractChecks.notNull(schema, "configuration schema");
        values = ContractChecks.sortedStringMap(values, "configuration values");
        ConfigurationIdentity expected = ConfigurationIdentity.from(schema, values);
        if (!identity.equals(expected)) {
            throw new IllegalArgumentException(
                    "configuration identity does not match schema and values");
        }
    }

    public static AnalysisConfiguration create(
            VersionedIdentifier schema, Map<String, String> values) {
        Map<String, String> sorted = ContractChecks.sortedStringMap(values, "configuration values");
        return new AnalysisConfiguration(ConfigurationIdentity.from(schema, sorted), schema, sorted);
    }
}
