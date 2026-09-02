package com.evolution.analysis.contract.metrics;

import com.evolution.analysis.contract.assessment.AssessmentStateRules;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.ConfigurationIdentity;
import com.evolution.analysis.contract.semantic.Uncertainty;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Versioned, scoped, provenance-complete result envelope for a metric value. */
public record MetricEnvelope(
        VersionedIdentifier metric,
        String displayName,
        String description,
        MetricScope scope,
        AssessmentStatus status,
        Optional<MetricValue> value,
        Optional<RatioEvidence> ratio,
        List<String> inputs,
        AnalysisIdentity analysisIdentity,
        ConfigurationIdentity configurationIdentity,
        List<Uncertainty> uncertainties,
        List<String> limitations,
        Instant computedAt) {

    public MetricEnvelope {
        ContractChecks.notNull(metric, "metric identifier");
        displayName = ContractChecks.text(displayName, "metric display name");
        description = ContractChecks.text(description, "metric description");
        ContractChecks.notNull(scope, "metric scope");
        ContractChecks.notNull(status, "metric status");
        value = ContractChecks.notNull(value, "metric value");
        ratio = ContractChecks.notNull(ratio, "metric ratio");
        inputs = ContractChecks.sortedStrings(inputs, "metric inputs");
        ContractChecks.notNull(analysisIdentity, "metric analysis identity");
        ContractChecks.notNull(configurationIdentity, "metric configuration identity");
        uncertainties = ContractChecks.sortedDistinct(
                uncertainties, Comparator.naturalOrder(), "metric uncertainties");
        limitations = ContractChecks.sortedStrings(limitations, "metric limitations");
        ContractChecks.notNull(computedAt, "metric computation time");
        AssessmentStateRules.validate(status, value, uncertainties, limitations, "metric");
        if (ratio.isPresent()
                && ratio.orElseThrow().denominator().signum() == 0
                && status != AssessmentStatus.NOT_APPLICABLE) {
            throw new IllegalArgumentException(
                    "a zero ratio denominator requires NOT_APPLICABLE status");
        }
    }
}
