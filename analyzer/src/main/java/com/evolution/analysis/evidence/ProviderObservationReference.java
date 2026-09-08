package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.Map;

/** Content-addressed pointer back to an immutable provider output and its exact observation payload. */
public record ProviderObservationReference(
        Identity identity,
        VersionedIdentifier provider,
        String observationKind,
        ContentDigest sourceResultIdentity,
        ContentDigest payloadDigest) implements Comparable<ProviderObservationReference> {

    public ProviderObservationReference {
        ContractChecks.notNull(identity, "provider observation identity");
        ContractChecks.notNull(provider, "observation provider");
        observationKind = ContractChecks.namespacedId(observationKind, "observation kind");
        ContractChecks.notNull(sourceResultIdentity, "source result identity");
        ContractChecks.notNull(payloadDigest, "observation payload digest");
        Identity expected = derive(provider, observationKind, sourceResultIdentity, payloadDigest);
        if (!identity.equals(expected)) throw new IllegalArgumentException("Observation identity does not match stable inputs");
    }

    public static ProviderObservationReference create(
            VersionedIdentifier provider, String observationKind,
            ContentDigest sourceResultIdentity, ContentDigest payloadDigest) {
        return new ProviderObservationReference(derive(provider, observationKind, sourceResultIdentity, payloadDigest),
                provider, observationKind, sourceResultIdentity, payloadDigest);
    }

    private static Identity derive(VersionedIdentifier provider, String kind, ContentDigest result, ContentDigest payload) {
        return new Identity(EvidenceIdentity.derive("observation", Map.of(
                "provider", provider, "kind", kind, "sourceResult", result, "payload", payload)));
    }

    @Override public int compareTo(ProviderObservationReference other) {
        return identity.compareTo(other.identity);
    }

    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = EvidenceIdentity.require(value, "observation"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }
}
