package com.evolution.analysis.contract.metrics;

import com.evolution.analysis.contract.common.ContractChecks;
import java.math.BigDecimal;

/** Explicit numerator and denominator evidence for ratio metrics. */
public record RatioEvidence(BigDecimal numerator, BigDecimal denominator) {

    public RatioEvidence {
        ContractChecks.notNull(numerator, "ratio numerator");
        ContractChecks.notNull(denominator, "ratio denominator");
        numerator = normalize(numerator);
        denominator = normalize(denominator);
        if (denominator.signum() < 0) {
            throw new IllegalArgumentException("ratio denominator must not be negative");
        }
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }
}
