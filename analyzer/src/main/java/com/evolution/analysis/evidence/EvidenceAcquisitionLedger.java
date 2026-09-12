package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Deterministic normalized M2/M3 gap, attempt, conflict and resolution envelope. */
public record EvidenceAcquisitionLedger(
        ContentDigest identity,
        String schemaVersion,
        VersionedIdentifier normalizer,
        EvidenceContext context,
        List<CapabilityGapRecord> gaps,
        List<AcquisitionAttemptRecord> attempts,
        List<ProviderConflictRecord> conflicts,
        List<GapResolutionRecord> resolutions) {
    public static final String SCHEMA = "evidence-acquisition-ledger-v1";
    public static final VersionedIdentifier NORMALIZER =
            new VersionedIdentifier("evidence.gap-normalizer", "m3.8");
    public static final VersionedIdentifier M4A1_NORMALIZER =
            new VersionedIdentifier("evidence.gap-normalizer", "m4a.1");

    public EvidenceAcquisitionLedger {
        ContractChecks.notNull(identity, "evidence ledger identity");
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported evidence ledger schema");
        ContractChecks.notNull(normalizer, "evidence normalizer");
        ContractChecks.notNull(context, "evidence ledger context");
        gaps = ContractChecks.sortedDistinct(gaps, Comparator.naturalOrder(), "capability gaps");
        attempts = ContractChecks.sortedDistinct(attempts, Comparator.naturalOrder(), "acquisition attempts");
        conflicts = ContractChecks.sortedDistinct(conflicts, Comparator.naturalOrder(), "provider conflicts");
        resolutions = ContractChecks.sortedDistinct(resolutions, Comparator.naturalOrder(), "gap resolutions");
        if (gaps.stream().anyMatch(value -> !value.context().equals(context))
                || attempts.stream().anyMatch(value -> !value.context().equals(context))
                || conflicts.stream().anyMatch(value -> !value.context().equals(context))) {
            throw new IllegalArgumentException("All evidence records must share the ledger context");
        }
        Set<AcquisitionAttemptRecord.Identity> attemptIds = attempts.stream()
                .map(AcquisitionAttemptRecord::attemptIdentity).collect(Collectors.toSet());
        if (gaps.stream().flatMap(value -> value.acquisitionAttemptReferences().stream())
                .anyMatch(value -> !attemptIds.contains(value))) {
            throw new IllegalArgumentException("Gap references an attempt outside the ledger");
        }
        Map<AcquisitionAttemptRecord.Identity, AcquisitionAttemptRecord> attemptsById = attempts.stream()
                .collect(Collectors.toMap(AcquisitionAttemptRecord::attemptIdentity, Function.identity()));
        for (CapabilityGapRecord gap : gaps) {
            for (AcquisitionAttemptRecord.Identity attemptIdentity : gap.acquisitionAttemptReferences()) {
                AcquisitionAttemptRecord attempt = attemptsById.get(attemptIdentity);
                boolean matchingRequirement = gap.evidenceRequirements().stream()
                        .anyMatch(requirement -> requirement.kind() == attempt.requestedRequirement().kind());
                if (!attempt.subject().equals(gap.subject()) || !matchingRequirement) {
                    throw new IllegalArgumentException("Gap references an unrelated acquisition attempt");
                }
            }
        }
        Set<CapabilityGapIdentity> gapIds = gaps.stream().map(CapabilityGapRecord::gapIdentity).collect(Collectors.toSet());
        for (GapResolutionRecord resolution : resolutions) {
            if (!gapIds.contains(resolution.gapIdentity())) throw new IllegalArgumentException("Resolution references an unknown gap");
            Set<VersionedIdentifier> resolutionProviders = new HashSet<>();
            boolean hasSuccessfulAttempt = false;
            for (AcquisitionAttemptRecord.Identity attempt : resolution.attemptReferences()) {
                AcquisitionAttemptRecord record = attemptsById.get(attempt);
                if (record == null || record.outcome() != AcquisitionAttemptRecord.Outcome.SUCCEEDED
                        && record.outcome() != AcquisitionAttemptRecord.Outcome.PARTIAL) {
                    throw new IllegalArgumentException("Only successful or partial attempts can narrow a gap");
                }
                resolutionProviders.add(record.provider());
                hasSuccessfulAttempt |= record.outcome() == AcquisitionAttemptRecord.Outcome.SUCCEEDED;
            }
            if (resolution.state() == GapResolutionRecord.State.SATISFIED && !hasSuccessfulAttempt) {
                throw new IllegalArgumentException("A satisfied gap requires a successful acquisition attempt");
            }
            if (resolution.evidenceReferences().stream()
                    .anyMatch(reference -> !resolutionProviders.contains(reference.provider()))) {
                throw new IllegalArgumentException("Resolution evidence must come from a referenced attempt provider");
            }
        }
        ContentDigest expected = derive(normalizer, context, gaps, attempts, conflicts, resolutions);
        if (!identity.equals(expected)) throw new IllegalArgumentException("Evidence ledger identity does not match contents");
    }

    public static EvidenceAcquisitionLedger create(EvidenceContext context,
            List<CapabilityGapRecord> gaps, List<AcquisitionAttemptRecord> attempts,
            List<ProviderConflictRecord> conflicts, List<GapResolutionRecord> resolutions) {
        return create(NORMALIZER, context, gaps, attempts, conflicts, resolutions);
    }

    public static EvidenceAcquisitionLedger create(VersionedIdentifier normalizer, EvidenceContext context,
            List<CapabilityGapRecord> gaps, List<AcquisitionAttemptRecord> attempts,
            List<ProviderConflictRecord> conflicts, List<GapResolutionRecord> resolutions) {
        if (!NORMALIZER.equals(normalizer) && !M4A1_NORMALIZER.equals(normalizer)) {
            throw new IllegalArgumentException("Unsupported evidence normalizer version");
        }
        TreeMap<CapabilityGapIdentity, CapabilityGapRecord> merged = new TreeMap<>();
        for (CapabilityGapRecord gap : gaps) {
            merged.merge(gap.gapIdentity(), gap, CapabilityGapRecord::mergeHistory);
        }
        List<CapabilityGapRecord> normalizedGaps = List.copyOf(merged.values());
        List<AcquisitionAttemptRecord> normalizedAttempts = mergeAttempts(attempts);
        List<ProviderConflictRecord> normalizedConflicts = conflicts.stream().sorted().distinct().toList();
        List<GapResolutionRecord> normalizedResolutions = resolutions.stream().sorted().distinct().toList();
        return new EvidenceAcquisitionLedger(derive(normalizer, context, normalizedGaps, normalizedAttempts,
                normalizedConflicts, normalizedResolutions), SCHEMA, normalizer, context, normalizedGaps,
                normalizedAttempts, normalizedConflicts, normalizedResolutions);
    }

    private static List<AcquisitionAttemptRecord> mergeAttempts(List<AcquisitionAttemptRecord> values) {
        TreeMap<AcquisitionAttemptRecord.Identity, AcquisitionAttemptRecord> unique = new TreeMap<>();
        for (AcquisitionAttemptRecord value : values) {
            AcquisitionAttemptRecord existing = unique.putIfAbsent(value.attemptIdentity(), value);
            if (existing != null && !existing.equals(value)) {
                throw new IllegalArgumentException("Duplicate attempt identity has different provenance");
            }
        }
        return List.copyOf(unique.values());
    }

    private static ContentDigest derive(VersionedIdentifier normalizer, EvidenceContext context,
            List<CapabilityGapRecord> gaps, List<AcquisitionAttemptRecord> attempts,
            List<ProviderConflictRecord> conflicts, List<GapResolutionRecord> resolutions) {
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", SCHEMA, "normalizer", normalizer, "context", context,
                "gaps", gaps, "attempts", attempts, "conflicts", conflicts, "resolutions", resolutions)));
    }
}
