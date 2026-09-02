package com.evolution.analysis.contract.assessment;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.ConfigurationIdentity;
import com.evolution.analysis.contract.semantic.Uncertainty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Versioned evidence-quality result, separate from repository architecture health. */
public record AnalysisConfidence(
        VersionedIdentifier model,
        AssessmentStatus status,
        Optional<BigDecimal> value,
        List<String> evidenceInputs,
        AnalysisIdentity analysisIdentity,
        ConfigurationIdentity configurationIdentity,
        List<Uncertainty> uncertainties,
        List<String> limitations,
        Instant computedAt) {

    public AnalysisConfidence {
        ContractChecks.notNull(model, "analysis confidence model");
        ContractChecks.notNull(status, "analysis confidence status");
        value = ArchitectureHealthAssessment.normalizeOptionalScore(
                value, "analysis confidence value", BigDecimal.ONE);
        evidenceInputs = ContractChecks.sortedStrings(evidenceInputs, "confidence evidence inputs");
        ContractChecks.notNull(analysisIdentity, "confidence analysis identity");
        ContractChecks.notNull(configurationIdentity, "confidence configuration identity");
        uncertainties = ContractChecks.sortedDistinct(
                uncertainties, Comparator.naturalOrder(), "confidence uncertainties");
        limitations = ContractChecks.sortedStrings(limitations, "confidence limitations");
        ContractChecks.notNull(computedAt, "confidence computation time");
        AssessmentStateRules.validate(
                status, value, uncertainties, limitations, "analysis confidence");
    }
}
