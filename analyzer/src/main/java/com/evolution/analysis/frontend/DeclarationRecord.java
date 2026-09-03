package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.semantic.*;
import java.util.*;

/** Original spelling is source data and deliberately not NFC-normalized. */
public record DeclarationRecord(Entity entity, String spelling, SemanticStatus status, Derivation derivation,
        List<Uncertainty> uncertainties, List<Diagnostic> diagnostics) implements Comparable<DeclarationRecord> {
    public DeclarationRecord {
        Objects.requireNonNull(entity); Objects.requireNonNull(spelling); Objects.requireNonNull(status); Objects.requireNonNull(derivation);
        uncertainties = ContractChecks.sortedDistinct(uncertainties, Comparator.naturalOrder(), "declaration uncertainties");
        diagnostics = ContractChecks.sortedDistinct(diagnostics, Comparator.naturalOrder(), "declaration diagnostics");
        if ((status == SemanticStatus.RESOLVED) != uncertainties.isEmpty()) throw new IllegalArgumentException("declaration status and uncertainty disagree");
        if (status == SemanticStatus.ERROR && diagnostics.stream().noneMatch(d -> d.severity() == DiagnosticSeverity.ERROR)) throw new IllegalArgumentException("error diagnostic required");
    }
    @Override public int compareTo(DeclarationRecord other) { return entity.compareTo(other.entity); }
}
