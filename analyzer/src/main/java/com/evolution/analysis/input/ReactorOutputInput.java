package com.evolution.analysis.input;

import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.frontend.BinaryInput;
import java.util.Objects;

/** Explicit immutable-output binding for one reactor module/source-set requirement. */
public record ReactorOutputInput(
        ModuleIdentity module,
        SourcePlanModel.Kind sourceSet,
        String outputDirectory,
        BinaryInput binary) {
    public ReactorOutputInput {
        Objects.requireNonNull(module); Objects.requireNonNull(sourceSet);
        outputDirectory = ContractChecks.modulePath(outputDirectory);
        Objects.requireNonNull(binary);
        if (!binary.reactorModule().equals(java.util.Optional.of(module))) {
            throw new IllegalArgumentException("Reactor binary must carry the bound module identity");
        }
    }
}
