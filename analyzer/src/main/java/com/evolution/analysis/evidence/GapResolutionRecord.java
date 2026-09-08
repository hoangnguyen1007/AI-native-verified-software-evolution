package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.*;
import java.util.*;

/** Additive evidence that narrows or satisfies a gap; the original gap remains immutable. */
public record GapResolutionRecord(
        ContentDigest identity,
        String schemaVersion,
        CapabilityGapIdentity gapIdentity,
        State state,
        List<ProviderObservationReference> evidenceReferences,
        List<AcquisitionAttemptRecord.Identity> attemptReferences,
        List<String> limitations) implements Comparable<GapResolutionRecord> {
    public static final String SCHEMA = "gap-resolution-record-v1";
    public enum State { NARROWED, SATISFIED }

    public GapResolutionRecord {
        ContractChecks.notNull(identity, "gap resolution identity");
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported gap-resolution schema");
        ContractChecks.notNull(gapIdentity, "resolved gap identity");
        ContractChecks.notNull(state, "gap resolution state");
        evidenceReferences = ContractChecks.sortedDistinct(
                evidenceReferences, Comparator.naturalOrder(), "resolution evidence references");
        attemptReferences = ContractChecks.sortedDistinct(
                attemptReferences, Comparator.naturalOrder(), "resolution attempt references");
        if (evidenceReferences.isEmpty() || attemptReferences.isEmpty()) {
            throw new IllegalArgumentException("A gap resolution requires evidence and acquisition provenance");
        }
        limitations = ContractChecks.sortedStrings(limitations, "resolution limitations");
        ContentDigest expected = derive(gapIdentity, state, evidenceReferences, attemptReferences, limitations);
        if (!identity.equals(expected)) throw new IllegalArgumentException("Gap resolution identity does not match inputs");
    }

    public static GapResolutionRecord create(CapabilityGapIdentity gapIdentity, State state,
            List<ProviderObservationReference> evidenceReferences,
            List<AcquisitionAttemptRecord.Identity> attemptReferences, List<String> limitations) {
        List<ProviderObservationReference> evidence = evidenceReferences.stream().sorted().distinct().toList();
        List<AcquisitionAttemptRecord.Identity> attempts = attemptReferences.stream().sorted().distinct().toList();
        List<String> sortedLimitations = limitations.stream().sorted().distinct().toList();
        return new GapResolutionRecord(derive(gapIdentity, state, evidence, attempts, sortedLimitations),
                SCHEMA, gapIdentity, state, evidence, attempts, sortedLimitations);
    }

    private static ContentDigest derive(CapabilityGapIdentity gap, State state,
            List<ProviderObservationReference> evidence,
            List<AcquisitionAttemptRecord.Identity> attempts, List<String> limitations) {
        return ContentDigest.sha256Utf8(com.evolution.analysis.contract.serialization.CanonicalJson.write(Map.of(
                "schema", SCHEMA, "gap", gap, "state", state, "evidence", evidence,
                "attempts", attempts, "limitations", limitations)));
    }

    @Override public int compareTo(GapResolutionRecord other) { return identity.compareTo(other.identity); }
}
