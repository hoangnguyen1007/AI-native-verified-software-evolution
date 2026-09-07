package com.evolution.analysis.classpath;

/** Explicit finite limits for passive dependency graph and local artifact acquisition. */
public record ClasspathResolutionPolicy(
        int maxCoordinates,
        int maxFiles,
        long maxPomBytes,
        long maxArtifactBytes,
        long maxTotalBytes,
        int maxDepth) {
    private static final int MAX_COORDINATES = 100_000;
    private static final int MAX_FILES = 200_000;

    public ClasspathResolutionPolicy {
        if (maxCoordinates < 1 || maxCoordinates > MAX_COORDINATES
                || maxFiles < 1 || maxFiles > MAX_FILES
                || maxPomBytes < 1 || maxPomBytes > Integer.MAX_VALUE - 8L
                || maxArtifactBytes < 1 || maxArtifactBytes > Integer.MAX_VALUE - 8L
                || maxTotalBytes < 1
                || maxDepth < 1 || maxDepth > 128) {
            throw new IllegalArgumentException(
                    "Classpath limits must be positive and finite; depth must not exceed 128");
        }
    }
}
