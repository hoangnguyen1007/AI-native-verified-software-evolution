package com.evolution.analysis.contract.assessment;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.semantic.Uncertainty;
import java.util.List;
import java.util.Optional;

/** Shared invariant for honest value presence across metric and assessment envelopes. */
public final class AssessmentStateRules {

    private AssessmentStateRules() {}

    public static void validate(
            AssessmentStatus status,
            Optional<?> value,
            List<Uncertainty> uncertainties,
            List<String> limitations,
            String subject) {
        ContractChecks.notNull(status, subject + " status");
        ContractChecks.notNull(value, subject + " value");
        ContractChecks.notNull(uncertainties, subject + " uncertainties");
        ContractChecks.notNull(limitations, subject + " limitations");
        boolean hasReason = !uncertainties.isEmpty() || !limitations.isEmpty();
        switch (status) {
            case COMPLETE -> {
                if (value.isEmpty()) {
                    throw new IllegalArgumentException(subject + " COMPLETE status requires a value");
                }
                if (!uncertainties.isEmpty()) {
                    throw new IllegalArgumentException(
                            subject + " COMPLETE status cannot carry uncertainty");
                }
            }
            case PARTIAL -> {
                if (value.isEmpty()) {
                    throw new IllegalArgumentException(subject + " PARTIAL status requires a value");
                }
                if (!hasReason) {
                    throw new IllegalArgumentException(
                            subject + " PARTIAL status requires uncertainty or a limitation");
                }
            }
            case WITHHELD, NOT_APPLICABLE -> {
                if (value.isPresent()) {
                    throw new IllegalArgumentException(
                            subject + " " + status + " status must not expose a value");
                }
                if (!hasReason) {
                    throw new IllegalArgumentException(
                            subject + " " + status + " status requires an explicit reason");
                }
            }
        }
    }
}
