package com.evolution.analysis.spring;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.frontend.*;
import java.util.*;

/** Exact already-decoded inputs for the passive M4A.1 scanner; no I/O occurs during scanning. */
public record SpringMechanismScanRequest(
        FrontendResult frontendResult,
        SpringFrameworkEvidence frameworkEvidence,
        List<SourceInput> xmlInputs,
        List<SourceInput> metadataInputs) {
    public SpringMechanismScanRequest {
        ContractChecks.notNull(frontendResult, "Spring scan frontend result");
        ContractChecks.notNull(frameworkEvidence, "Spring scan framework evidence");
        xmlInputs = copyDistinct(xmlInputs, "Spring XML inputs");
        metadataInputs = copyDistinct(metadataInputs, "Spring metadata inputs");
        Set<Object> documents = new HashSet<>();
        java.util.stream.Stream.concat(xmlInputs.stream(), metadataInputs.stream()).forEach(input -> {
            if (!documents.add(input.document().identity())) {
                throw new IllegalArgumentException("A Spring resource document cannot be supplied twice");
            }
        });
    }

    private static List<SourceInput> copyDistinct(List<SourceInput> values, String name) {
        ContractChecks.notNull(values, name);
        ArrayList<SourceInput> copy = new ArrayList<>();
        for (SourceInput value : values) copy.add(ContractChecks.notNull(value, name + " element"));
        copy.sort(Comparator.comparing(value -> value.document().identity()));
        if (copy.stream().map(value -> value.document().identity()).distinct().count() != copy.size()) {
            throw new IllegalArgumentException(name + " contain duplicate documents");
        }
        return List.copyOf(copy);
    }
}
