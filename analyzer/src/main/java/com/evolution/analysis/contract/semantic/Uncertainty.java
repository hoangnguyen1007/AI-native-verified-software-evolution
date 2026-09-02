package com.evolution.analysis.contract.semantic;

import com.evolution.analysis.contract.common.ContractChecks;
import java.util.List;

/** Explicit limitation or evidence gap; it is never replaced by a guessed target. */
public record Uncertainty(String code, String message, List<String> missingInputs)
        implements Comparable<Uncertainty> {

    public Uncertainty {
        code = ContractChecks.namespacedId(code, "uncertainty code");
        message = ContractChecks.text(message, "uncertainty message");
        missingInputs = ContractChecks.sortedStrings(missingInputs, "uncertainty missing inputs");
        if (missingInputs.isEmpty()) {
            throw new IllegalArgumentException("uncertainty must name at least one missing input");
        }
    }

    @Override
    public int compareTo(Uncertainty other) {
        int comparison = code.compareTo(other.code);
        if (comparison != 0) return comparison;
        comparison = message.compareTo(other.message);
        if (comparison != 0) return comparison;
        return missingInputs.toString().compareTo(other.missingInputs.toString());
    }
}
