package com.evolution.analysis.contract.assessment;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.ConfigurationIdentity;
import com.evolution.analysis.contract.semantic.Uncertainty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Architecture health output only. Analyzer evidence quality is deliberately represented by
 * {@link AnalysisConfidence}, not by this type.
 */
public record ArchitectureHealthAssessment(
        VersionedIdentifier model,
        AssessmentStatus status,
        Optional<BigDecimal> score,
        Map<String, BigDecimal> dimensionScores,
        List<String> inputMetricIdentities,
        AnalysisIdentity analysisIdentity,
        ConfigurationIdentity configurationIdentity,
        List<Uncertainty> uncertainties,
        List<String> limitations,
        Instant computedAt) {

    public ArchitectureHealthAssessment {
        ContractChecks.notNull(model, "architecture health model");
        ContractChecks.notNull(status, "architecture health status");
        score = normalizeOptionalScore(score, "architecture health score", BigDecimal.valueOf(100));
        dimensionScores = normalizeDimensionScores(dimensionScores);
        inputMetricIdentities =
                ContractChecks.sortedStrings(inputMetricIdentities, "assessment metric inputs");
        ContractChecks.notNull(analysisIdentity, "assessment analysis identity");
        ContractChecks.notNull(configurationIdentity, "assessment configuration identity");
        uncertainties = ContractChecks.sortedDistinct(
                uncertainties, Comparator.naturalOrder(), "assessment uncertainties");
        limitations = ContractChecks.sortedStrings(limitations, "assessment limitations");
        ContractChecks.notNull(computedAt, "assessment computation time");
        AssessmentStateRules.validate(
                status, score, uncertainties, limitations, "architecture health assessment");
        if (score.isEmpty() && !dimensionScores.isEmpty()) {
            throw new IllegalArgumentException(
                    "withheld or not-applicable health cannot expose dimension scores");
        }
    }

    static Optional<BigDecimal> normalizeOptionalScore(
            Optional<BigDecimal> value, String name, BigDecimal maximum) {
        Optional<BigDecimal> checked = ContractChecks.notNull(value, name)
                .map(ArchitectureHealthAssessment::normalize);
        checked.ifPresent(score -> {
            if (score.signum() < 0 || score.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(name + " must be between 0 and " + maximum);
            }
        });
        return checked;
    }

    private static Map<String, BigDecimal> normalizeDimensionScores(
            Map<String, BigDecimal> values) {
        ContractChecks.notNull(values, "dimension scores");
        TreeMap<String, BigDecimal> sorted = new TreeMap<>();
        values.forEach((key, value) -> {
            String checkedKey = ContractChecks.namespacedId(key, "dimension id");
            BigDecimal checkedValue = normalize(ContractChecks.notNull(value, "dimension score"));
            if (checkedValue.signum() < 0 || checkedValue.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("dimension score must be between 0 and 100");
            }
            sorted.put(checkedKey, checkedValue);
        });
        return Map.copyOf(new LinkedHashMap<>(sorted));
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }
}
