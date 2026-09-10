package com.evolution.analysis.maven;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.buildmodel.MavenCoordinate;
import com.evolution.analysis.classpath.ArtifactCoordinate;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.dependency.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenDependencyArtifactProviderTest {
    private static final URI REPOSITORY = URI.create("https://repo.example.test/maven2/");
    private static final ArtifactCoordinate JAR =
            new ArtifactCoordinate(new MavenCoordinate("example", "library", "1.0"), "jar", "");

    @TempDir
    Path temporary;

    @Test
    void returnsVerifiedCachedJarWithoutContactingTheNetwork() throws Exception {
        Path cache = cache();
        byte[] bytes = jar("example/Library.class", new byte[] {1, 2, 3});
        write(cache, JAR, bytes);
        AtomicInteger networkCalls = new AtomicInteger();
        var provider = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) -> {
            networkCalls.incrementAndGet();
            return new MavenDependencyArtifactProvider.RemoteResponse(500, new byte[0]);
        });
        DependencyAcquisitionRequest request = request(JAR, Optional.of(ContentDigest.sha256(bytes)), policy(2));

        DependencyAcquisitionResult result = provider.acquire(request);

        assertFalse(result.hasFailures());
        assertEquals(DependencyAcquisitionResult.Status.CACHED, result.outcomes().getFirst().status());
        assertEquals(ContentDigest.sha256(bytes), result.outcomes().getFirst().artifact().orElseThrow().contentDigest());
        assertEquals(0, networkCalls.get());
        assertEquals(DependencyAcquisitionResult.AttemptOutcome.SUCCEEDED, result.attempts().getFirst().outcome());
    }

    @Test
    void acquiresValidJarByHttpsAndPromotesItAtomically() throws Exception {
        Path cache = cache();
        byte[] bytes = jar("example/Library.class", new byte[] {4, 5, 6});
        var requested = new java.util.ArrayList<URI>();
        var provider = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) -> {
            requested.add(uri);
            return new MavenDependencyArtifactProvider.RemoteResponse(200, bytes);
        });

        DependencyAcquisitionResult result = provider.acquire(request(JAR, Optional.empty(), policy(0)));

        assertEquals(DependencyAcquisitionResult.Status.ACQUIRED, result.outcomes().getFirst().status());
        assertEquals(List.of(REPOSITORY.resolve(JAR.repositoryPath())), requested);
        assertArrayEquals(bytes, Files.readAllBytes(path(cache, JAR)));
        try (var files = Files.list(path(cache, JAR).getParent())) {
            assertTrue(files.noneMatch(value -> value.getFileName().toString().contains(".m3.8-")));
        }

        AtomicInteger secondNetworkCalls = new AtomicInteger();
        DependencyAcquisitionResult cached = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) -> {
            secondNetworkCalls.incrementAndGet();
            throw new IOException("must not be called");
        }).acquire(request(JAR, Optional.empty(), policy(0)));
        assertEquals(DependencyAcquisitionResult.Status.CACHED, cached.outcomes().getFirst().status());
        assertEquals(0, secondNetworkCalls.get());
    }

    @Test
    void acquiresDescriptorButReportsPomPackagingAsACompleteBinarySkip() throws Exception {
        Path cache = cache();
        ArtifactCoordinate pom = JAR.asPom();
        byte[] bytes = "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>"
                .concat("<artifactId>library</artifactId><version>1.0</version><packaging>pom</packaging></project>")
                .getBytes(StandardCharsets.UTF_8);
        var provider = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) ->
                new MavenDependencyArtifactProvider.RemoteResponse(200, bytes));

        DependencyAcquisitionResult result = provider.acquire(request(pom, Optional.empty(), policy(0)));

        assertEquals(DependencyAcquisitionResult.Status.SKIPPED_POM, result.outcomes().getFirst().status());
        assertEquals(DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                result.outcomes().getFirst().artifact().orElseThrow().origin());
        assertArrayEquals(bytes, Files.readAllBytes(path(cache, pom)));
    }

    @Test
    void corruptOrDigestMismatchedDownloadsNeverReachTheCache() throws Exception {
        Path cache = cache();
        byte[] corrupt = new byte[] {'P', 'K', 3, 4, 1, 2, 3};
        DependencyAcquisitionResult corrupted = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) ->
                new MavenDependencyArtifactProvider.RemoteResponse(200, corrupt))
                .acquire(request(JAR, Optional.empty(), policy(0)));
        assertEquals(DependencyAcquisitionResult.FailureReason.CORRUPTED_DOWNLOAD,
                corrupted.outcomes().getFirst().failureReason().orElseThrow());
        assertFalse(Files.exists(path(cache, JAR)));

        byte[] valid = jar("example/Library.class", new byte[] {7});
        DependencyAcquisitionResult mismatch = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) ->
                new MavenDependencyArtifactProvider.RemoteResponse(200, valid))
                .acquire(request(JAR, Optional.of(ContentDigest.sha256Utf8("different")), policy(0)));
        assertEquals(DependencyAcquisitionResult.FailureReason.DIGEST_MISMATCH,
                mismatch.outcomes().getFirst().failureReason().orElseThrow());
        assertFalse(Files.exists(path(cache, JAR)));
    }

    @Test
    void timeoutAndServerFailuresUseOnlyTheConfiguredRetryBudget() throws Exception {
        Path cache = cache();
        AtomicInteger timeouts = new AtomicInteger();
        DependencyAcquisitionResult timeout = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) -> {
            timeouts.incrementAndGet();
            throw new HttpTimeoutException("bounded timeout");
        }).acquire(request(JAR, Optional.empty(), policy(2)));

        assertEquals(3, timeouts.get());
        assertEquals(3, timeout.attempts().stream()
                .filter(value -> value.origin() == DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY).count());
        assertEquals(DependencyAcquisitionResult.FailureReason.FETCH_TIMEOUT,
                timeout.outcomes().getFirst().failureReason().orElseThrow());

        AtomicInteger servers = new AtomicInteger();
        DependencyAcquisitionResult server = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) -> {
            servers.incrementAndGet();
            return new MavenDependencyArtifactProvider.RemoteResponse(503, new byte[0]);
        }).acquire(request(JAR, Optional.empty(), policy(1)));
        assertEquals(2, servers.get());
        assertEquals(DependencyAcquisitionResult.FailureReason.NETWORK_FAILURE,
                server.outcomes().getFirst().failureReason().orElseThrow());
    }

    @Test
    void notFoundAndSizeLimitsRemainDistinctTypedFailures() throws Exception {
        Path cache = cache();
        DependencyAcquisitionResult missing = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) ->
                new MavenDependencyArtifactProvider.RemoteResponse(404, new byte[0]))
                .acquire(request(JAR, Optional.empty(), policy(2)));
        assertEquals(1, missing.attempts().stream()
                .filter(value -> value.origin() == DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY).count());
        assertEquals(DependencyAcquisitionResult.FailureReason.ARTIFACT_NOT_FOUND,
                missing.outcomes().getFirst().failureReason().orElseThrow());

        DependencyAcquisitionResult oversized = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) -> {
            throw new MavenDependencyArtifactProvider.RemoteLimitExceeded();
        }).acquire(request(JAR, Optional.empty(), policy(0)));
        assertEquals(DependencyAcquisitionResult.FailureReason.SIZE_EXCEEDED,
                oversized.outcomes().getFirst().failureReason().orElseThrow());
    }

    @Test
    void invalidExistingCacheEntryIsNeverOverwrittenByRemoteContent() throws Exception {
        Path cache = cache();
        byte[] corrupt = "not-a-jar".getBytes(StandardCharsets.UTF_8);
        write(cache, JAR, corrupt);
        AtomicInteger networkCalls = new AtomicInteger();
        var provider = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) -> {
            networkCalls.incrementAndGet();
            return new MavenDependencyArtifactProvider.RemoteResponse(200,
                    jar("example/Library.class", new byte[] {9}));
        });

        DependencyAcquisitionResult result = provider.acquire(request(JAR, Optional.empty(), policy(0)));

        assertEquals(DependencyAcquisitionResult.FailureReason.CORRUPTED_CACHE,
                result.outcomes().getFirst().failureReason().orElseThrow());
        assertArrayEquals(corrupt, Files.readAllBytes(path(cache, JAR)));
        assertEquals(0, networkCalls.get());
    }

    @Test
    void missingCacheRootAndMalformedPomAreExplicitAndSideEffectFree() throws Exception {
        Path missingRoot = temporary.resolve("missing");
        DependencyAcquisitionResult rootFailure = provider(missingRoot, (uri, maxBytes, connectTimeout, readTimeout) ->
                new MavenDependencyArtifactProvider.RemoteResponse(200, new byte[0]))
                .acquire(request(JAR, Optional.empty(), policy(0)));
        assertEquals(DependencyAcquisitionResult.FailureReason.CACHE_ROOT_NOT_FOUND,
                rootFailure.outcomes().getFirst().failureReason().orElseThrow());
        assertFalse(Files.exists(missingRoot));

        Path cache = cache();
        ArtifactCoordinate pom = JAR.asPom();
        DependencyAcquisitionResult malformed = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) ->
                new MavenDependencyArtifactProvider.RemoteResponse(200, "<project>".getBytes(StandardCharsets.UTF_8)))
                .acquire(request(pom, Optional.empty(), policy(0)));
        assertEquals(DependencyAcquisitionResult.FailureReason.INVALID_POM,
                malformed.outcomes().getFirst().failureReason().orElseThrow());
        assertFalse(Files.exists(path(cache, pom)));
    }

    @Test
    void totalByteBudgetIsSharedAcrossTheWholeOrderedRequest() throws Exception {
        Path cache = cache();
        ArtifactCoordinate second = new ArtifactCoordinate(
                new MavenCoordinate("example", "second", "1.0"), "jar", "");
        byte[] bytes = jar("example/Library.class", new byte[] {1, 2, 3});
        AtomicInteger networkCalls = new AtomicInteger();
        var provider = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) -> {
            networkCalls.incrementAndGet();
            return new MavenDependencyArtifactProvider.RemoteResponse(200, bytes);
        });
        DependencyAcquisitionPolicy bounded = new DependencyAcquisitionPolicy(
                2, 1_000_000, bytes.length, 8, List.of(REPOSITORY), 1_000, 2_000, 0);
        DependencyAcquisitionRequest request = DependencyAcquisitionRequest.of(List.of(JAR, second), bounded);

        DependencyAcquisitionResult result = provider.acquire(request);

        assertEquals(DependencyAcquisitionResult.Status.ACQUIRED, result.outcomes().get(0).status());
        assertEquals(DependencyAcquisitionResult.Status.FAILED, result.outcomes().get(1).status());
        assertEquals(DependencyAcquisitionResult.FailureReason.SIZE_EXCEEDED,
                result.outcomes().get(1).failureReason().orElseThrow());
        assertEquals(bytes.length, result.bytesConsumed());
        assertEquals(1, networkCalls.get());
        assertFalse(Files.exists(path(cache, second)));
    }

    @Test
    void unavailableRepositoryFallsThroughToTheNextAuthorizedRepository() throws Exception {
        Path cache = cache();
        URI mirror = URI.create("https://mirror.example.test/releases/");
        byte[] bytes = jar("example/Library.class", new byte[] {4, 5, 6});
        var requested = new java.util.ArrayList<URI>();
        var provider = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) -> {
            requested.add(uri);
            return new MavenDependencyArtifactProvider.RemoteResponse(
                    uri.getHost().equals(REPOSITORY.getHost()) ? 404 : 200,
                    uri.getHost().equals(REPOSITORY.getHost()) ? new byte[0] : bytes);
        });
        DependencyAcquisitionPolicy fallback = new DependencyAcquisitionPolicy(
                10, 1_000_000, 10_000_000, 8, List.of(REPOSITORY, mirror), 1_000, 2_000, 2);

        DependencyAcquisitionResult result = provider.acquire(
                DependencyAcquisitionRequest.of(List.of(JAR), fallback));

        assertEquals(DependencyAcquisitionResult.Status.ACQUIRED, result.outcomes().getFirst().status());
        assertEquals(List.of(REPOSITORY.resolve(JAR.repositoryPath()), mirror.resolve(JAR.repositoryPath())), requested);
        assertEquals(List.of(
                        DependencyAcquisitionResult.AttemptOutcome.UNAVAILABLE,
                        DependencyAcquisitionResult.AttemptOutcome.SUCCEEDED),
                result.attempts().stream()
                        .filter(value -> value.origin() == DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY)
                        .map(DependencyAcquisitionResult.Attempt::outcome).toList());
    }

    @Test
    void snapshotsAuthenticationAndRedirectsRemainTypedAndAreNeverRetried() throws Exception {
        Path cache = cache();
        ArtifactCoordinate snapshot = new ArtifactCoordinate(
                new MavenCoordinate("example", "snapshot", "1.0-SNAPSHOT"), "jar", "");
        AtomicInteger snapshotCalls = new AtomicInteger();
        DependencyAcquisitionResult rejectedSnapshot = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) -> {
            snapshotCalls.incrementAndGet();
            return new MavenDependencyArtifactProvider.RemoteResponse(200, new byte[0]);
        }).acquire(DependencyAcquisitionRequest.of(List.of(snapshot), policy(2)));
        assertEquals(DependencyAcquisitionResult.FailureReason.SNAPSHOT_UNSUPPORTED,
                rejectedSnapshot.outcomes().getFirst().failureReason().orElseThrow());
        assertEquals(0, snapshotCalls.get());

        for (var denial : List.of(
                Map.entry(401, DependencyAcquisitionResult.FailureReason.CREDENTIALS_REQUIRED),
                Map.entry(302, DependencyAcquisitionResult.FailureReason.REMOTE_RESPONSE_DENIED))) {
            AtomicInteger calls = new AtomicInteger();
            DependencyAcquisitionResult result = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) -> {
                calls.incrementAndGet();
                return new MavenDependencyArtifactProvider.RemoteResponse(denial.getKey(), new byte[0]);
            }).acquire(request(JAR, Optional.empty(), policy(2)));
            assertEquals(denial.getValue(), result.outcomes().getFirst().failureReason().orElseThrow());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void cachePromotionFailureNeverMasqueradesAsASuccessfulRemoteAttempt() throws Exception {
        Path cache = cache();
        byte[] bytes = jar("example/Library.class", new byte[] {7, 8, 9});
        var provider = provider(cache, (uri, maxBytes, connectTimeout, readTimeout) -> {
            Path blockingParent = path(cache, JAR).getParent();
            Files.createDirectories(blockingParent.getParent());
            Files.writeString(blockingParent, "not-a-directory", StandardCharsets.UTF_8);
            return new MavenDependencyArtifactProvider.RemoteResponse(200, bytes);
        });

        DependencyAcquisitionResult result = provider.acquire(request(JAR, Optional.empty(), policy(0)));

        assertEquals(DependencyAcquisitionResult.FailureReason.NON_REGULAR_ARTIFACT,
                result.outcomes().getFirst().failureReason().orElseThrow());
        DependencyAcquisitionResult.Attempt remote = result.attempts().stream()
                .filter(value -> value.origin() == DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY)
                .findFirst().orElseThrow();
        assertEquals(DependencyAcquisitionResult.AttemptOutcome.DENIED, remote.outcome());
        assertTrue(remote.contentDigest().isEmpty());
        assertFalse(Files.exists(path(cache, JAR)));
    }

    private Path cache() throws IOException {
        Path cache = temporary.resolve("cache-" + java.util.UUID.randomUUID());
        return Files.createDirectory(cache).toRealPath();
    }

    private static MavenDependencyArtifactProvider provider(
            Path cache, MavenDependencyArtifactProvider.RemoteArtifactTransport transport) {
        return new MavenDependencyArtifactProvider(cache, transport, millis -> {});
    }

    private static DependencyAcquisitionRequest request(
            ArtifactCoordinate coordinate, Optional<ContentDigest> expected, DependencyAcquisitionPolicy policy) {
        return new DependencyAcquisitionRequest(List.of(
                new DependencyAcquisitionRequest.Requirement(coordinate, expected)), policy);
    }

    private static DependencyAcquisitionPolicy policy(int retries) {
        return new DependencyAcquisitionPolicy(100, 1_000_000, 10_000_000, 8,
                List.of(REPOSITORY), 1_000, 2_000, retries);
    }

    private static Path path(Path cache, ArtifactCoordinate coordinate) {
        return cache.resolve(coordinate.repositoryPath().replace('/', java.io.File.separatorChar));
    }

    private static void write(Path cache, ArtifactCoordinate coordinate, byte[] bytes) throws IOException {
        Path path = path(cache, coordinate);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private static byte[] jar(String entryName, byte[] bytes) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry(entryName));
            jar.write(bytes);
            jar.closeEntry();
        }
        return output.toByteArray();
    }
}
