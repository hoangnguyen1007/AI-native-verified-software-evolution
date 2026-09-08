package com.evolution.analysis.maven;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** Finite policy for passive exact-coordinate POM acquisition. */
public record MavenPomResolutionPolicy(
        int maxPomCount,
        long maxPomBytes,
        long maxTotalBytes,
        int maxPasses,
        List<URI> remoteRepositories,
        int connectTimeoutMillis,
        int requestTimeoutMillis) {
    private static final int MAX_POMS = 100_000;

    public MavenPomResolutionPolicy(int maxPomCount, long maxPomBytes, long maxTotalBytes, int maxPasses) {
        this(maxPomCount, maxPomBytes, maxTotalBytes, maxPasses, List.of(), 5_000, 15_000);
    }

    public MavenPomResolutionPolicy {
        if (maxPomCount < 1 || maxPomCount > MAX_POMS
                || maxPomBytes < 1 || maxPomBytes > Integer.MAX_VALUE - 8L
                || maxTotalBytes < 1
                || maxPasses < 1 || maxPasses > 128
                || connectTimeoutMillis < 1 || connectTimeoutMillis > 120_000
                || requestTimeoutMillis < 1 || requestTimeoutMillis > 300_000) {
            throw new IllegalArgumentException("POM resolution limits must be positive and finite");
        }
        remoteRepositories = List.copyOf(remoteRepositories);
        if (remoteRepositories.size() > 16) {
            throw new IllegalArgumentException("At most 16 explicit remote repositories are supported");
        }
        for (URI repository : remoteRepositories) validateRepository(repository);
    }

    public ContentDigest identity() {
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "maxPomCount", maxPomCount,
                "maxPomBytes", maxPomBytes,
                "maxTotalBytes", maxTotalBytes,
                "maxPasses", maxPasses,
                "remoteRepositories", remoteRepositories.stream().map(URI::toASCIIString).toList(),
                "connectTimeoutMillis", connectTimeoutMillis,
                "requestTimeoutMillis", requestTimeoutMillis)));
    }

    private static void validateRepository(URI repository) {
        if (repository == null || !repository.isAbsolute() || !"https".equalsIgnoreCase(repository.getScheme())
                || repository.getHost() == null || repository.getHost().isBlank()
                || repository.getUserInfo() != null || repository.getQuery() != null || repository.getFragment() != null
                || !repository.getPath().endsWith("/")) {
            throw new IllegalArgumentException(
                    "Remote Maven repositories must be explicit credential-free HTTPS base URIs ending in '/'");
        }
    }
}
