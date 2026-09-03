package com.evolution.analysis.contract.semantic;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.OccurrenceIdentity;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.Comparator;
import java.util.List;

/** One source-evidenced occurrence of a semantic relationship. */
public record RelationshipOccurrence(
        OccurrenceIdentity identity,
        SemanticRelationship relationship,
        SourceSpan span,
        int ordinal,
        SemanticStatus status,
        Derivation derivation,
        List<Uncertainty> uncertainties,
        List<Diagnostic> diagnostics)
        implements Comparable<RelationshipOccurrence> {

    public RelationshipOccurrence {
        ContractChecks.notNull(identity, "occurrence identity");
        ContractChecks.notNull(relationship, "relationship");
        ContractChecks.notNull(span, "source span");
        if (ordinal < 0) {
            throw new IllegalArgumentException("occurrence ordinal must be non-negative");
        }
        ContractChecks.notNull(status, "semantic status");
        ContractChecks.notNull(derivation, "derivation");
        uncertainties = ContractChecks.sortedDistinct(
                uncertainties, Comparator.naturalOrder(), "uncertainties");
        diagnostics = ContractChecks.sortedDistinct(
                diagnostics, Comparator.naturalOrder(), "diagnostics");
        OccurrenceIdentity expected = OccurrenceIdentity.from(relationship.identity(), span, ordinal);
        if (!identity.equals(expected)) {
            throw new IllegalArgumentException("occurrence identity does not match its canonical inputs");
        }
        validateState(relationship.target(), status, uncertainties, diagnostics);
    }

    public static RelationshipOccurrence create(
            SemanticRelationship relationship,
            SourceSpan span,
            int ordinal,
            SemanticStatus status,
            Derivation derivation,
            List<Uncertainty> uncertainties,
            List<Diagnostic> diagnostics) {
        return new RelationshipOccurrence(
                OccurrenceIdentity.from(relationship.identity(), span, ordinal),
                relationship,
                span,
                ordinal,
                status,
                derivation,
                uncertainties,
                diagnostics);
    }

    static void validateState(
            RelationshipTarget target,
            SemanticStatus status,
            List<Uncertainty> uncertainties,
            List<Diagnostic> diagnostics) {
        boolean targetMatches = switch (status) {
            case RESOLVED -> target instanceof RelationshipTarget.Resolved;
            case AMBIGUOUS -> target instanceof RelationshipTarget.Candidates;
            case UNRESOLVED, UNSUPPORTED, ERROR -> target instanceof RelationshipTarget.Unresolved;
            case PARTIAL, CONDITIONAL -> true;
        };
        if (!targetMatches) {
            throw new IllegalArgumentException("semantic status does not match target representation");
        }
        if (status == SemanticStatus.RESOLVED && !uncertainties.isEmpty()) {
            throw new IllegalArgumentException("resolved occurrences cannot carry unresolved uncertainty");
        }
        if (status != SemanticStatus.RESOLVED && uncertainties.isEmpty()) {
            throw new IllegalArgumentException("non-resolved occurrences require explicit uncertainty");
        }
        if (status == SemanticStatus.ERROR
                && diagnostics.stream().noneMatch(
                        diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)) {
            throw new IllegalArgumentException("error occurrences require an error diagnostic");
        }
    }

    @Override
    public int compareTo(RelationshipOccurrence other) {
        return identity.compareTo(other.identity);
    }
}
