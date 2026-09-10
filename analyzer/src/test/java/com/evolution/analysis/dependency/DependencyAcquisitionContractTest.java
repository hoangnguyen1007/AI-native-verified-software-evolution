package com.evolution.analysis.dependency;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.buildmodel.MavenCoordinate;
import com.evolution.analysis.classpath.ArtifactCoordinate;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DependencyAcquisitionContractTest {
    private static final ArtifactCoordinate JAR =
            new ArtifactCoordinate(new MavenCoordinate("example", "library", "1.0"), "jar", "");
    private static final ArtifactCoordinate POM = JAR.asPom();
    private static final URI CENTRAL = URI.create("https://repo.maven.apache.org/maven2/");

    @Test
    void requestBindsOrderedExactRequirementsAndPolicyWithoutAHostCachePath() {
        DependencyAcquisitionPolicy policy = policy(List.of(CENTRAL));
        ContentDigest expected = ContentDigest.sha256Utf8("expected-library");
        DependencyAcquisitionRequest first = new DependencyAcquisitionRequest(List.of(
                new DependencyAcquisitionRequest.Requirement(POM, Optional.empty()),
                new DependencyAcquisitionRequest.Requirement(JAR, Optional.of(expected))), policy);
        DependencyAcquisitionRequest same = new DependencyAcquisitionRequest(first.requirements(), policy);
        DependencyAcquisitionRequest reversed = new DependencyAcquisitionRequest(List.of(
                new DependencyAcquisitionRequest.Requirement(JAR, Optional.of(expected)),
                new DependencyAcquisitionRequest.Requirement(POM, Optional.empty())), policy);

        assertEquals(DependencyAcquisitionRequest.SCHEMA, first.schemaVersion());
        assertEquals(first.identity(), same.identity());
        assertNotEquals(first.identity(), reversed.identity());
        assertThrows(IllegalArgumentException.class,
                () -> new DependencyAcquisitionRequest(List.of(first.requirements().getFirst(),
                        first.requirements().getFirst()), policy));
    }

    @Test
    void policyAllowsOnlyBoundedCredentialFreeHttpsReleaseRepositories() {
        assertThrows(IllegalArgumentException.class, () -> policy(List.of(URI.create("http://repo.example.test/"))));
        assertThrows(IllegalArgumentException.class,
                () -> policy(List.of(URI.create("https://user:secret@repo.example.test/"))));
        assertThrows(IllegalArgumentException.class,
                () -> policy(List.of(URI.create("https://repo.example.test/no-trailing-slash"))));
        assertThrows(IllegalArgumentException.class, () -> new DependencyAcquisitionPolicy(
                10, 1_000, 10_000, 2, List.of(CENTRAL), 1_000, 2_000, 3));
        assertNotEquals(policy(List.of(CENTRAL)).identity(),
                policy(List.of(URI.create("https://repo.example.test/maven2/"))).identity());
    }

    @Test
    void resultRequiresOneTypedOutcomePerRequirementAndBindsArtifactEvidence() {
        DependencyAcquisitionRequest request = DependencyAcquisitionRequest.of(List.of(POM, JAR), policy(List.of(CENTRAL)));
        ContentDigest pomDigest = ContentDigest.sha256Utf8("pom");
        ContentDigest jarDigest = ContentDigest.sha256Utf8("jar");
        var pomArtifact = new DependencyAcquisitionResult.Artifact(POM,
                DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY, POM.repositoryPath(), pomDigest, 3);
        var jarArtifact = new DependencyAcquisitionResult.Artifact(JAR,
                DependencyAcquisitionResult.Origin.LOCAL_CACHE, JAR.repositoryPath(), jarDigest, 3);
        var outcomes = List.of(
                new DependencyAcquisitionResult.Outcome(request.requirements().get(0),
                        DependencyAcquisitionResult.Status.SKIPPED_POM, Optional.of(pomArtifact), Optional.empty()),
                new DependencyAcquisitionResult.Outcome(request.requirements().get(1),
                        DependencyAcquisitionResult.Status.CACHED, Optional.of(jarArtifact), Optional.empty()));
        var attempts = List.of(
                new DependencyAcquisitionResult.Attempt(0, POM,
                        DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                        "https://repo.maven.apache.org/maven2/" + POM.repositoryPath(),
                        DependencyAcquisitionResult.AttemptOutcome.SUCCEEDED, Optional.of(pomDigest)),
                new DependencyAcquisitionResult.Attempt(1, JAR, DependencyAcquisitionResult.Origin.LOCAL_CACHE,
                        JAR.repositoryPath(), DependencyAcquisitionResult.AttemptOutcome.SUCCEEDED,
                        Optional.of(jarDigest)));

        DependencyAcquisitionResult result = DependencyAcquisitionResult.create(request,
                new VersionedIdentifier("dependency.artifact-cache", "m3.8"), outcomes, attempts);

        assertFalse(result.hasFailures());
        assertEquals(outcomes, result.outcomes());
        assertTrue(CanonicalJson.write(result).contains("\"remoteRepositories\":[\"https://repo.maven.apache.org/maven2/\"]"));
        assertThrows(IllegalArgumentException.class, () -> DependencyAcquisitionResult.create(request,
                new VersionedIdentifier("dependency.artifact-cache", "m3.8"), List.of(outcomes.getFirst()), attempts));
        assertThrows(IllegalArgumentException.class, () -> new DependencyAcquisitionResult.Outcome(
                request.requirements().get(1), DependencyAcquisitionResult.Status.FAILED,
                Optional.of(jarArtifact), Optional.of(DependencyAcquisitionResult.FailureReason.ARTIFACT_NOT_FOUND)));
        assertThrows(IllegalArgumentException.class, () -> new DependencyAcquisitionResult.Outcome(
                request.requirements().get(0), DependencyAcquisitionResult.Status.ACQUIRED,
                Optional.of(pomArtifact), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new DependencyAcquisitionResult.Attempt(
                0, JAR, DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                "http://repo.example.test/" + JAR.repositoryPath(),
                DependencyAcquisitionResult.AttemptOutcome.UNAVAILABLE, Optional.empty()));
    }

    private static DependencyAcquisitionPolicy policy(List<URI> repositories) {
        return new DependencyAcquisitionPolicy(100, 100_000_000, 1_000_000_000, 16,
                repositories, 5_000, 20_000, 2);
    }
}
