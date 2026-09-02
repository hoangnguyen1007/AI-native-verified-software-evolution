package com.evolution.analysis.contract.identity;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.contract.common.ContractChecks;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/** Stable repository namespace derived from a caller-supplied canonical coordinate. */
public record RepositoryIdentity(String value)
        implements CanonicalIdentifier, Comparable<RepositoryIdentity> {

    public RepositoryIdentity {
        value = IdentitySupport.require(value, "repository");
    }

    public static RepositoryIdentity fromCanonicalCoordinate(String canonicalCoordinate) {
        ContractChecks.text(canonicalCoordinate, "canonical repository coordinate");
        validateCanonicalCoordinate(canonicalCoordinate);
        return new RepositoryIdentity(
                IdentitySupport.derive("repository", List.of(canonicalCoordinate)));
    }

    private static void validateCanonicalCoordinate(String coordinate) {
        try {
            URI uri = new URI(coordinate);
            if (!uri.isAbsolute()
                    || uri.getScheme() == null
                    || !uri.getScheme().equals(uri.getScheme().toLowerCase(java.util.Locale.ROOT))
                    || uri.getHost() != null
                            && !uri.getHost().equals(uri.getHost().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "canonical repository coordinate must be an absolute URI with lowercase scheme and host");
            }
            if (!uri.normalize().toString().equals(coordinate)) {
                throw new IllegalArgumentException(
                        "canonical repository coordinate must already be URI-normalized");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("canonical repository coordinate must be a valid URI", exception);
        }
    }

    @Override
    public int compareTo(RepositoryIdentity other) {
        return value.compareTo(other.value);
    }
}
