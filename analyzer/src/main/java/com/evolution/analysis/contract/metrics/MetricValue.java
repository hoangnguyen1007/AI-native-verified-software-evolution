package com.evolution.analysis.contract.metrics;

import com.evolution.analysis.contract.common.ContractChecks;
import java.math.BigDecimal;

/** Exact decimal metric value and explicit unit. */
public record MetricValue(BigDecimal amount, String unit) {

    public MetricValue {
        ContractChecks.notNull(amount, "metric amount");
        amount = normalize(amount);
        unit = ContractChecks.token(unit, "metric unit");
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }
}
