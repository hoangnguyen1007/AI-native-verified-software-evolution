package com.evolution.analysis.buildmodel;

import com.evolution.analysis.contract.common.ContractChecks;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Explicit model evaluation context. No host properties, settings, filesystem activation or network. */
public record BuildModelPolicy(List<String> activeProfiles, List<String> inactiveProfiles,
        Map<String, String> userProperties, int maxPomBytes, int maxPomCount, int maxModelReads) {
    public BuildModelPolicy {
        activeProfiles = ContractChecks.sortedStrings(activeProfiles, "active profiles");
        inactiveProfiles = ContractChecks.sortedStrings(inactiveProfiles, "inactive profiles");
        if (!Collections.disjoint(activeProfiles, inactiveProfiles)) {
            throw new IllegalArgumentException("A profile cannot be both active and inactive");
        }
        var properties = new TreeMap<String, String>();
        userProperties.forEach((key, value) -> {
            ContractChecks.text(key, "model property name");
            ContractChecks.notNull(value, "model property value");
            // Empty values are meaningful for Maven property activation. Preserve exact supplied text.
            com.evolution.analysis.contract.serialization.CanonicalJson.write(value);
            properties.put(key, value);
        });
        userProperties = Map.copyOf(properties);
        if (maxPomBytes < 1 || maxPomCount < 1 || maxModelReads < 1 || maxModelReads > 128) {
            throw new IllegalArgumentException("Model limits must be positive; model reads must not exceed 128");
        }
    }
}
