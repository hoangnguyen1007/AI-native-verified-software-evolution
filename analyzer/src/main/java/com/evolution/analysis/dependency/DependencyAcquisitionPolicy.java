package com.evolution.analysis.dependency;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** Explicit authorization and finite limits for exact release dependency acquisition. */
public record DependencyAcquisitionPolicy(
        int maxCoordinates,
        long maxArtifactBytes,
        long maxTotalBytes,
        int maxPasses,
        List<URI> remoteRepositories,
        int connectTimeoutMillis,
        int readTimeoutMillis,
        int maxRetries) {
    public static final URI MAVEN_CENTRAL = URI.create("https://repo.maven.apache.org/maven2/");
    private static final int MAX_COORDINATES = 100_000;
    private static final int MAX_REPOSITORIES = 16;
    private static final int MAX_PASSES = 128;
    private static final int MAX_RETRIES = 2;

    public DependencyAcquisitionPolicy {
        if (maxCoordinates < 1 || maxCoordinates > MAX_COORDINATES
                || maxArtifactBytes < 1 || maxArtifactBytes > Integer.MAX_VALUE - 8L
                || maxTotalBytes < 1
                || maxPasses < 1 || maxPasses > MAX_PASSES
                || connectTimeoutMillis < 1 || connectTimeoutMillis > 120_000
                || readTimeoutMillis < 1 || readTimeoutMillis > 300_000
                || maxRetries < 0 || maxRetries > MAX_RETRIES) {
            throw new IllegalArgumentException("Dependency acquisition limits must be positive and finite");
        }
        remoteRepositories = List.copyOf(remoteRepositories);
        if (remoteRepositories.size() > MAX_REPOSITORIES) {
            throw new IllegalArgumentException("At most 16 explicitly authorized repositories are supported");
        }
        remoteRepositories.forEach(DependencyAcquisitionPolicy::validateRepository);
    }

    public ContentDigest identity() {
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "maxCoordinates", maxCoordinates,
                "maxArtifactBytes", maxArtifactBytes,
                "maxTotalBytes", maxTotalBytes,
                "maxPasses", maxPasses,
                "remoteRepositories", remoteRepositories.stream().map(URI::toASCIIString).toList(),
                "connectTimeoutMillis", connectTimeoutMillis,
                "readTimeoutMillis", readTimeoutMillis,
                "maxRetries", maxRetries)));
    }

    public DependencyAcquisitionPolicy withLimits(int coordinates, long totalBytes) {
        return new DependencyAcquisitionPolicy(coordinates, maxArtifactBytes, totalBytes, maxPasses,
                remoteRepositories, connectTimeoutMillis, readTimeoutMillis, maxRetries);
    }

    private static void validateRepository(URI repository) {
        if (repository == null || !repository.isAbsolute()
                || !"https".equalsIgnoreCase(repository.getScheme())
                || repository.getHost() == null || repository.getHost().isBlank()
                || repository.getUserInfo() != null
                || repository.getQuery() != null || repository.getFragment() != null
                || !repository.getPath().endsWith("/")) {
            throw new IllegalArgumentException(
                    "Remote artifact repositories must be credential-free HTTPS base URIs ending in '/'");
        }
    }
}
