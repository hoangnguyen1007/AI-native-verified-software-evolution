package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.Diagnostic;
import java.util.*;

/** Explicit incompatible observations; provider order never silently selects semantic truth. */
public record ProviderConflictRecord(
        String schemaVersion,
        Identity conflictIdentity,
        EvidenceContext context,
        ProviderObservationReference left,
        ProviderObservationReference right,
        List<String> conflictDimensions,
        AdjudicationStatus adjudicationStatus,
        Optional<ProviderObservationReference.Identity> acceptedObservation,
        List<AffectedOutput> affectedOutputs,
        List<Diagnostic> diagnostics,
        List<String> limitations) implements Comparable<ProviderConflictRecord> {
    public static final String SCHEMA = "provider-conflict-record-v1";
    public enum AdjudicationStatus { UNRESOLVED, LEFT_ACCEPTED, RIGHT_ACCEPTED, BOTH_QUALIFIED, DISMISSED }

    public ProviderConflictRecord {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported provider-conflict schema");
        ContractChecks.notNull(conflictIdentity, "conflict identity");
        ContractChecks.notNull(context, "conflict context");
        ContractChecks.notNull(left, "left observation");
        ContractChecks.notNull(right, "right observation");
        if (left.identity().equals(right.identity())) throw new IllegalArgumentException("A conflict needs distinct observations");
        conflictDimensions = ContractChecks.sortedDistinct(
                conflictDimensions.stream().map(value -> ContractChecks.namespacedId(value, "conflict dimension")).toList(),
                Comparator.naturalOrder(), "conflict dimensions");
        if (conflictDimensions.isEmpty()) throw new IllegalArgumentException("A conflict needs at least one dimension");
        ContractChecks.notNull(adjudicationStatus, "conflict adjudication status");
        acceptedObservation = ContractChecks.notNull(acceptedObservation, "accepted observation");
        boolean acceptanceRequired = adjudicationStatus == AdjudicationStatus.LEFT_ACCEPTED
                || adjudicationStatus == AdjudicationStatus.RIGHT_ACCEPTED;
        if (acceptanceRequired != acceptedObservation.isPresent()) {
            throw new IllegalArgumentException("Accepted adjudication must identify the accepted observation");
        }
        acceptedObservation.ifPresent(value -> {
            if (!value.equals(left.identity()) && !value.equals(right.identity())) {
                throw new IllegalArgumentException("Accepted observation is not part of the conflict");
            }
            if (adjudicationStatus == AdjudicationStatus.LEFT_ACCEPTED && !value.equals(left.identity())
                    || adjudicationStatus == AdjudicationStatus.RIGHT_ACCEPTED && !value.equals(right.identity())) {
                throw new IllegalArgumentException("Adjudication side and accepted observation disagree");
            }
        });
        affectedOutputs = ContractChecks.sortedDistinct(affectedOutputs, Comparator.naturalOrder(), "conflict affected outputs");
        if (affectedOutputs.isEmpty()) throw new IllegalArgumentException("A conflict must identify affected outputs");
        diagnostics = ContractChecks.sortedDistinct(diagnostics, Comparator.naturalOrder(), "conflict diagnostics");
        limitations = ContractChecks.sortedStrings(limitations, "conflict limitations");
        Identity expected = derive(context, left, right, conflictDimensions);
        if (!conflictIdentity.equals(expected)) throw new IllegalArgumentException("Conflict identity does not match observations");
    }

    public static ProviderConflictRecord create(
            EvidenceContext context,
            ProviderObservationReference left,
            ProviderObservationReference right,
            List<String> conflictDimensions,
            AdjudicationStatus adjudicationStatus,
            Optional<ProviderObservationReference.Identity> acceptedObservation,
            List<AffectedOutput> affectedOutputs,
            List<Diagnostic> diagnostics,
            List<String> limitations) {
        return new ProviderConflictRecord(SCHEMA, derive(context, left, right,
                conflictDimensions.stream().sorted().distinct().toList()), context, left, right,
                conflictDimensions, adjudicationStatus, acceptedObservation, affectedOutputs, diagnostics, limitations);
    }

    private static Identity derive(EvidenceContext context, ProviderObservationReference left,
            ProviderObservationReference right, List<String> dimensions) {
        List<ProviderObservationReference.Identity> pair = List.of(left.identity(), right.identity()).stream().sorted().toList();
        return new Identity(EvidenceIdentity.derive("conflict", Map.of(
                "schema", SCHEMA, "context", context, "observations", pair,
                "dimensions", dimensions.stream().sorted().distinct().toList())));
    }

    @Override public int compareTo(ProviderConflictRecord other) { return conflictIdentity.compareTo(other.conflictIdentity); }

    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = EvidenceIdentity.require(value, "conflict"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }
}
