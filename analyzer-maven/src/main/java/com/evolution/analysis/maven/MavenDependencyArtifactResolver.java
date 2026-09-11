package com.evolution.analysis.maven;

import com.evolution.analysis.classpath.ClasspathResolutionRequest;
import com.evolution.analysis.classpath.ExactClasspathResult;
import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.dependency.*;
import com.evolution.analysis.frontend.BinaryInput;
import java.nio.file.Path;
import java.util.*;

/** Finite exact-coordinate POM/JAR fixpoint over the passive local classpath provider. */
public final class MavenDependencyArtifactResolver {
    public static final VersionedIdentifier VERSION =
            new VersionedIdentifier("dependency.maven-fixpoint", "3.9.16-m3.8");

    private final Path selectedCacheRoot;
    private final DependencyArtifactProvider artifactProvider;

    public MavenDependencyArtifactResolver(Path selectedCacheRoot) {
        this(selectedCacheRoot, new MavenDependencyArtifactProvider(selectedCacheRoot));
    }

    MavenDependencyArtifactResolver(Path selectedCacheRoot, DependencyArtifactProvider artifactProvider) {
        this.selectedCacheRoot = Objects.requireNonNull(selectedCacheRoot).toAbsolutePath().normalize();
        this.artifactProvider = Objects.requireNonNull(artifactProvider);
    }

    public MavenDependencyArtifactResolution resolve(
            ClasspathResolutionRequest request, DependencyAcquisitionPolicy policy) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(policy);
        MavenLocalClasspathProvider classpathProvider = new MavenLocalClasspathProvider(selectedCacheRoot);
        ExactClasspathResult classpath = classpathProvider.resolve(request);
        int classpathPasses = 1;
        int acquisitionPasses = 0;
        int requestedCoordinates = 0;
        long consumedBytes = 0;
        Set<com.evolution.analysis.classpath.ArtifactCoordinate> attempted = new HashSet<>();
        List<DependencyAcquisitionResult> acquisitions = new ArrayList<>();
        List<MavenDependencyArtifactResolution.Problem> problems = new ArrayList<>();

        while (acquisitionPasses < policy.maxPasses()) {
            List<com.evolution.analysis.classpath.ArtifactCoordinate> missing = missing(classpath).stream()
                    .filter(value -> !attempted.contains(value)).toList();
            if (missing.isEmpty()) break;
            int remainingCoordinates = policy.maxCoordinates() - requestedCoordinates;
            if (remainingCoordinates < 1) {
                problems.add(new MavenDependencyArtifactResolution.Problem(
                        MavenDependencyArtifactResolution.ProblemReason.COORDINATE_LIMIT, "dependency-closure"));
                break;
            }
            if (missing.size() > remainingCoordinates) {
                problems.add(new MavenDependencyArtifactResolution.Problem(
                        MavenDependencyArtifactResolution.ProblemReason.COORDINATE_LIMIT, "dependency-closure"));
                missing = missing.subList(0, remainingCoordinates);
            }
            long remainingBytes = policy.maxTotalBytes() - consumedBytes;
            if (remainingBytes < 1) {
                problems.add(new MavenDependencyArtifactResolution.Problem(
                        MavenDependencyArtifactResolution.ProblemReason.TOTAL_BYTE_LIMIT, "dependency-closure"));
                break;
            }

            DependencyAcquisitionPolicy roundPolicy = policy.withLimits(remainingCoordinates, remainingBytes);
            DependencyAcquisitionRequest acquisitionRequest = DependencyAcquisitionRequest.of(missing, roundPolicy);
            DependencyAcquisitionResult acquisition = artifactProvider.acquire(acquisitionRequest);
            acquisitions.add(acquisition);
            attempted.addAll(missing);
            requestedCoordinates += missing.size();
            consumedBytes += acquisition.bytesConsumed();
            acquisition.outcomes().stream()
                    .filter(value -> value.status() == DependencyAcquisitionResult.Status.FAILED)
                    .forEach(value -> problems.add(new MavenDependencyArtifactResolution.Problem(
                            MavenDependencyArtifactResolution.ProblemReason.ACQUISITION_FAILED,
                            value.requirement().coordinate().notation() + ":"
                                    + value.failureReason().orElseThrow().name())));

            boolean progressed = acquisition.outcomes().stream()
                    .anyMatch(value -> value.status() != DependencyAcquisitionResult.Status.FAILED);
            if (!progressed) break;
            classpath = classpathProvider.resolve(request);
            classpathPasses++;
            acquisitionPasses++;
        }
        if (acquisitionPasses == policy.maxPasses() && !missing(classpath).isEmpty()) {
            problems.add(new MavenDependencyArtifactResolution.Problem(
                    MavenDependencyArtifactResolution.ProblemReason.PASS_LIMIT, "dependency-closure"));
        }
        return MavenDependencyArtifactResolution.create(request.identity(), policy, VERSION,
                classpath, acquisitions, problems, classpathPasses);
    }

    /** Runtime cache locators for the final digest-bound entries; paths never enter stable identity. */
    public List<BinaryInput> binaryInputs(MavenDependencyArtifactResolution resolution) {
        Objects.requireNonNull(resolution);
        LinkedHashMap<ClasspathEntry, BinaryInput> inputs = new LinkedHashMap<>();
        for (ExactClasspathResult.Manifest manifest : resolution.classpath().manifests()) {
            for (ExactClasspathResult.Entry entry : manifest.entries()) {
                Path path = selectedCacheRoot.resolve(
                        entry.repositoryPath().replace('/', java.io.File.separatorChar)).normalize();
                inputs.putIfAbsent(entry.classpathEntry(), new BinaryInput(entry.classpathEntry(), path));
            }
        }
        return List.copyOf(inputs.values());
    }

    private static List<com.evolution.analysis.classpath.ArtifactCoordinate> missing(ExactClasspathResult result) {
        return result.attempts().stream()
                .filter(value -> value.origin() == ExactClasspathResult.ArtifactOrigin.LOCAL_CACHE)
                .filter(value -> value.outcome() == ExactClasspathResult.AttemptOutcome.UNAVAILABLE)
                .filter(value -> value.kind() == ExactClasspathResult.ArtifactKind.POM
                        || value.kind() == ExactClasspathResult.ArtifactKind.JAR)
                .map(ExactClasspathResult.Attempt::coordinate)
                .distinct()
                .sorted()
                .toList();
    }
}
