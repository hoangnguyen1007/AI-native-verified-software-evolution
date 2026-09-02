package com.evolution.analysis.contract.metrics;

import com.evolution.analysis.contract.common.ContractChecks;
import java.util.regex.Pattern;

/** Scope type and canonical identity of the measured subject. */
public record MetricScope(MetricScopeType type, String identity) {

    public MetricScope {
        ContractChecks.notNull(type, "metric scope type");
        identity = ContractChecks.text(identity, "metric scope identity");
        String identityKind = switch (type) {
            case ANALYSIS -> "analysis";
            case REPOSITORY -> "repository";
            case MODULE -> "module";
            case PACKAGE, TYPE, ENTITY -> "entity";
            case RELATIONSHIP -> "relationship";
        };
        if (!Pattern.matches(identityKind + ":sha256:[0-9a-f]{64}", identity)) {
            throw new IllegalArgumentException(
                    "metric scope identity must be a stable " + identityKind + " identity");
        }
    }
}
