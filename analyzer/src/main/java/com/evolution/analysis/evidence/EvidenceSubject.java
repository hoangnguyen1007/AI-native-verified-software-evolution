package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.ContractChecks;

/** Typed scope affected by an evidence gap; the value is a stable logical identity, never a host path. */
public record EvidenceSubject(Kind kind, String identity) implements Comparable<EvidenceSubject> {
    public enum Kind {
        REPOSITORY, MODULE, SOURCE_SET, DOCUMENT, ENTITY, CONFIGURATION, ARTIFACT,
        PLATFORM, REPOSITORY_PATH, BUILD_INPUT, CATEGORY, OBSERVATION, PROVIDER
    }

    public EvidenceSubject {
        ContractChecks.notNull(kind, "evidence subject kind");
        identity = ContractChecks.text(identity, "evidence subject identity");
    }

    public static EvidenceSubject observation(ProviderObservationReference.Identity identity) {
        ContractChecks.notNull(identity, "observation identity");
        return new EvidenceSubject(Kind.OBSERVATION, identity.value());
    }

    @Override public int compareTo(EvidenceSubject other) {
        int kindComparison = kind.compareTo(other.kind);
        return kindComparison != 0 ? kindComparison : identity.compareTo(other.identity);
    }
}
