package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.Diagnostic;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.*;

/** Immutable normalized explanation of evidence that is missing, unsupported, ambiguous or failed. */
public record CapabilityGapRecord(
        String schemaVersion,
        VersionedIdentifier catalog,
        CapabilityGapIdentity gapIdentity,
        EvidenceContext context,
        VersionedIdentifier detectingProvider,
        String mechanismCategory,
        String reasonCode,
        EvidenceSubject subject,
        List<SourceSpan> sourceSpans,
        List<ProviderObservationReference> observationReferences,
        List<EvidenceRequirement> evidenceRequirements,
        List<CandidateProvider> candidateProviders,
        List<AffectedOutput> affectedOutputs,
        List<AcquisitionAttemptRecord.Identity> acquisitionAttemptReferences,
        List<Diagnostic> diagnostics,
        List<String> limitations) implements Comparable<CapabilityGapRecord> {
    public static final String SCHEMA = "capability-gap-record-v1";

    public CapabilityGapRecord {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported capability-gap schema");
        ContractChecks.notNull(catalog, "capability-gap catalog");
        ContractChecks.notNull(gapIdentity, "gap identity");
        ContractChecks.notNull(context, "gap context");
        ContractChecks.notNull(detectingProvider, "detecting provider");
        mechanismCategory = ContractChecks.namespacedId(mechanismCategory, "mechanism category");
        reasonCode = ContractChecks.token(reasonCode, "gap reason code");
        ContractChecks.notNull(subject, "gap subject");
        sourceSpans = ContractChecks.sortedDistinct(sourceSpans, Comparator.naturalOrder(), "gap source spans");
        observationReferences = ContractChecks.sortedDistinct(
                observationReferences, Comparator.naturalOrder(), "gap observation references");
        if (observationReferences.isEmpty()) throw new IllegalArgumentException("A gap must cite its provider observation");
        if (observationReferences.stream().anyMatch(value -> !value.provider().equals(detectingProvider))) {
            throw new IllegalArgumentException("Gap observations must come from the detecting provider");
        }
        evidenceRequirements = ContractChecks.sortedDistinct(
                evidenceRequirements, Comparator.naturalOrder(), "gap evidence requirements");
        if (evidenceRequirements.isEmpty()) throw new IllegalArgumentException("A gap needs at least one evidence requirement");
        candidateProviders = ContractChecks.distinctInOrder(candidateProviders, "candidate providers");
        if (!candidateProviders.isEmpty()) {
            VersionedIdentifier policy = candidateProviders.getFirst().orderingPolicy();
            for (int index = 0; index < candidateProviders.size(); index++) {
                CandidateProvider candidate = candidateProviders.get(index);
                if (!candidate.orderingPolicy().equals(policy) || candidate.rank() != index) {
                    throw new IllegalArgumentException("Candidate providers require one documented policy and contiguous ranks");
                }
            }
        }
        affectedOutputs = ContractChecks.sortedDistinct(affectedOutputs, Comparator.naturalOrder(), "affected outputs");
        if (affectedOutputs.isEmpty()) throw new IllegalArgumentException("A gap must identify affected outputs");
        acquisitionAttemptReferences = ContractChecks.sortedDistinct(
                acquisitionAttemptReferences, Comparator.naturalOrder(), "gap acquisition attempts");
        diagnostics = ContractChecks.sortedDistinct(diagnostics, Comparator.naturalOrder(), "gap diagnostics");
        limitations = ContractChecks.sortedStrings(limitations, "gap limitations");
        CapabilityGapIdentity expected = derive(catalog, context, detectingProvider, mechanismCategory,
                reasonCode, subject, sourceSpans, evidenceRequirements);
        if (!gapIdentity.equals(expected)) throw new IllegalArgumentException("Gap identity does not match stable fields");
    }

    public static CapabilityGapRecord create(
            EvidenceContext context,
            VersionedIdentifier detectingProvider,
            String mechanismCategory,
            String reasonCode,
            EvidenceSubject subject,
            List<SourceSpan> sourceSpans,
            List<ProviderObservationReference> observationReferences,
            List<EvidenceRequirement> evidenceRequirements,
            List<CandidateProvider> candidateProviders,
            List<AffectedOutput> affectedOutputs,
            List<AcquisitionAttemptRecord.Identity> acquisitionAttemptReferences,
            List<Diagnostic> diagnostics,
            List<String> limitations) {
        List<SourceSpan> spans = sourceSpans.stream().sorted().distinct().toList();
        List<EvidenceRequirement> requirements = evidenceRequirements.stream().sorted().distinct().toList();
        CapabilityGapIdentity identity = derive(CapabilityGapCatalog.CATALOG, context, detectingProvider,
                mechanismCategory, reasonCode, subject, spans, requirements);
        return new CapabilityGapRecord(SCHEMA, CapabilityGapCatalog.CATALOG, identity, context,
                detectingProvider, mechanismCategory, reasonCode, subject, spans, observationReferences,
                requirements, candidateProviders, affectedOutputs, acquisitionAttemptReferences, diagnostics, limitations);
    }

    CapabilityGapRecord mergeHistory(CapabilityGapRecord other) {
        if (!gapIdentity.equals(other.gapIdentity()) || !stableView().equals(other.stableView())) {
            throw new IllegalArgumentException("Only records for the same stable gap may be merged");
        }
        return create(context, detectingProvider, mechanismCategory, reasonCode, subject, sourceSpans,
                union(observationReferences, other.observationReferences(), Comparator.naturalOrder()),
                evidenceRequirements,
                mergeCandidatePlans(candidateProviders, other.candidateProviders()),
                union(affectedOutputs, other.affectedOutputs(), Comparator.naturalOrder()),
                union(acquisitionAttemptReferences, other.acquisitionAttemptReferences(), Comparator.naturalOrder()),
                union(diagnostics, other.diagnostics(), Comparator.naturalOrder()),
                union(limitations, other.limitations(), Comparator.naturalOrder()));
    }

    private Object stableView() {
        return List.of(schemaVersion, catalog, context, detectingProvider, mechanismCategory, reasonCode,
                subject, sourceSpans, evidenceRequirements);
    }

    private static CapabilityGapIdentity derive(
            VersionedIdentifier catalog,
            EvidenceContext context,
            VersionedIdentifier provider,
            String mechanism,
            String reason,
            EvidenceSubject subject,
            List<SourceSpan> spans,
            List<EvidenceRequirement> requirements) {
        return new CapabilityGapIdentity(EvidenceIdentity.derive("gap", Map.of(
                "schema", SCHEMA,
                "catalog", catalog,
                "context", context,
                "detectingProvider", provider,
                "mechanismCategory", mechanism,
                "reasonCode", reason,
                "subject", subject,
                "sourceSpans", spans,
                "evidenceRequirements", requirements)));
    }

    private static <T> List<T> union(Collection<T> first, Collection<T> second, Comparator<? super T> comparator) {
        ArrayList<T> values = new ArrayList<>(first);
        values.addAll(second);
        return values.stream().sorted(comparator).distinct().toList();
    }

    private static List<CandidateProvider> mergeCandidatePlans(
            List<CandidateProvider> first, List<CandidateProvider> second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty() || first.equals(second)) return first;
        throw new IllegalArgumentException("Duplicate gap records contain incompatible candidate-provider policies");
    }

    @Override public int compareTo(CapabilityGapRecord other) { return gapIdentity.compareTo(other.gapIdentity); }
}
