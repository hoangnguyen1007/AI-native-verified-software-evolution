package com.evolution.analysis.contract.semantic;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.*;

/** Language-rule evidence; supporting syntax is never an implicit member's declaration span. */
public record DerivedRelationshipRecord(SemanticRelationship relationship, SemanticStatus status,
        Derivation derivation, List<SourceSpan> supportingSpans, List<Uncertainty> uncertainties,
        List<Diagnostic> diagnostics) implements Comparable<DerivedRelationshipRecord> {
    public DerivedRelationshipRecord {
        Objects.requireNonNull(relationship); Objects.requireNonNull(status); Objects.requireNonNull(derivation);
        if (derivation.kind()==DerivationKind.DIRECT) throw new IllegalArgumentException("derived evidence requires a language rule");
        supportingSpans=ContractChecks.sortedDistinct(supportingSpans,Comparator.naturalOrder(),"supporting spans");
        uncertainties=ContractChecks.sortedDistinct(uncertainties,Comparator.naturalOrder(),"uncertainties");
        diagnostics=ContractChecks.sortedDistinct(diagnostics,Comparator.naturalOrder(),"diagnostics");
        RelationshipOccurrence.validateState(relationship.target(),status,uncertainties,diagnostics);
    }
    private String key() { return CanonicalJson.write(List.of(relationship.identity(),derivation.method(),derivation.inputIdentities())); }
    @Override public int compareTo(DerivedRelationshipRecord other) { return key().compareTo(other.key()); }
}
