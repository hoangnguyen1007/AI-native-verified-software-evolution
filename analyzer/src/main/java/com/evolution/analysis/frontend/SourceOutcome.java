package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.semantic.Diagnostic;
import com.evolution.analysis.contract.common.ContractChecks;
import java.util.*;

public record SourceOutcome(SourceDocumentIdentity document, State state, List<Diagnostic> diagnostics) implements Comparable<SourceOutcome> {
    public enum State { PROCESSED, PARTIAL, ERROR, REJECTED, NOT_PROCESSED, UNSUPPORTED }
    public SourceOutcome {
        Objects.requireNonNull(document); Objects.requireNonNull(state);
        diagnostics = ContractChecks.sortedDistinct(diagnostics, Comparator.naturalOrder(), "source diagnostics");
        if (state != State.PROCESSED && diagnostics.isEmpty()) throw new IllegalArgumentException("incomplete source needs a reason");
    }
    @Override public int compareTo(SourceOutcome other) { return document.compareTo(other.document); }
}
