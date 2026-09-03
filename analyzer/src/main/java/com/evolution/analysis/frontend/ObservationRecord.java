package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.*;

/** All attempted observations, including facts withheld for missing identity, origin or coordinates. */
public record ObservationRecord(SourceDocumentIdentity document, RelationshipKind category,
        Optional<SourceSpan> span, SemanticStatus attribution, EvidenceState origin, EvidenceState provenance,
        Optional<OccurrenceIdentity> mappedOccurrence, String reference, List<Diagnostic> diagnostics)
        implements Comparable<ObservationRecord> {
    public enum EvidenceState { VERIFIED, MISSING, NOT_APPLICABLE }
    public ObservationRecord {
        Objects.requireNonNull(document); Objects.requireNonNull(category); Objects.requireNonNull(span);
        Objects.requireNonNull(attribution); Objects.requireNonNull(origin); Objects.requireNonNull(provenance);
        Objects.requireNonNull(mappedOccurrence); Objects.requireNonNull(reference);
        diagnostics = ContractChecks.sortedDistinct(diagnostics, Comparator.naturalOrder(), "observation diagnostics");
        if (span.isPresent() && !span.get().document().equals(document)) throw new IllegalArgumentException("observation document mismatch");
        if (provenance == EvidenceState.VERIFIED && span.isEmpty()) throw new IllegalArgumentException("verified provenance needs a span");
        if (mappedOccurrence.isPresent() && provenance != EvidenceState.VERIFIED) throw new IllegalArgumentException("mapped occurrence needs provenance");
        if (mappedOccurrence.isEmpty() && diagnostics.isEmpty()) throw new IllegalArgumentException("unmapped observation needs a diagnostic");
    }
    @Override public int compareTo(ObservationRecord other) { return CanonicalJson.write(this).compareTo(CanonicalJson.write(other)); }
}
