package com.evolution.analysis.maven;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.*;
import com.evolution.analysis.classpath.ExactClasspathResult.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.dependency.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stage-4 regression tests for Maven snapshot cache reuse, passive version ranges, S5 non-HoL prefetch, and cache isolation. */
class MavenGapAndPrefetchTest {
    private static final URI REPO = URI.create("https://repo.example.test/maven2/");
    private static final RepositoryIdentity REPO_ID =
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/maven-hardening.git");

    @TempDir
    Path temp;

    @Test
    void cachedSnapshotResolvesLocallyWhileUncachedSnapshotStaysUnsupportedWithoutRemoteFetch() throws Exception {
        Path cache = Files.createDirectory(temp.resolve("cache"));
        ArtifactCoordinate cachedSnap = new ArtifactCoordinate(
                new MavenCoordinate("demo", "snap-cached", "1.0-SNAPSHOT"), "jar", "");
        ArtifactCoordinate missingSnap = new ArtifactCoordinate(
                new MavenCoordinate("demo", "snap-missing", "1.0-SNAPSHOT"), "jar", "");

        byte[] jarBytes = minimalJar("demo/Snap.class");
        Path localPath = cache.resolve(cachedSnap.repositoryPath().replace('/', java.io.File.separatorChar));
        Files.createDirectories(localPath.getParent());
        Files.write(localPath, jarBytes);

        AtomicInteger remoteCalls = new AtomicInteger();
        var provider = new MavenDependencyArtifactProvider(cache, (uri, max, ct, rt) -> {
            remoteCalls.incrementAndGet();
            return new MavenDependencyArtifactProvider.RemoteResponse(200, jarBytes);
        }, ms -> {});
        var policy = new DependencyAcquisitionPolicy(16, 1_000_000, 10_000_000, 4, List.of(REPO), 1_000, 2_000, 1, 2);

        var result = provider.acquire(DependencyAcquisitionRequest.of(List.of(cachedSnap, missingSnap), policy));
        assertEquals(0, remoteCalls.get(), "Snapshots must never trigger remote HTTP downloads");
        assertEquals(DependencyAcquisitionResult.Status.CACHED, result.outcomes().get(0).status());
        assertEquals(ContentDigest.sha256(jarBytes), result.outcomes().get(0).artifact().orElseThrow().contentDigest());
        assertEquals(DependencyAcquisitionResult.Status.FAILED, result.outcomes().get(1).status());
        assertEquals(Optional.of(DependencyAcquisitionResult.FailureReason.SNAPSHOT_UNSUPPORTED),
                result.outcomes().get(1).failureReason());
    }

    @Test
    void passiveLocalVersionRangePicksHighestNonSnapshotLocalMatchAndKeepsUncachedRangeTyped() throws Exception {
        Path cache = Files.createDirectory(temp.resolve("range-cache"));
        writeCachedArtifact(cache, "ext", "lib", "1.2.0", minimalJar("ext/Lib12.class"));
        writeCachedArtifact(cache, "ext", "lib", "1.9.0", minimalJar("ext/Lib19.class"));
        writeCachedArtifact(cache, "ext", "lib", "1.9.1-SNAPSHOT", minimalJar("ext/LibSnap.class"));
        writeCachedArtifact(cache, "ext", "lib", "2.0.0", minimalJar("ext/Lib20.class"));

        String rootPom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>ext</groupId><artifactId>lib</artifactId><version>[1.0,2.0)</version></dependency>
                    <dependency><groupId>ext</groupId><artifactId>absent</artifactId><version>[1.0,2.0)</version></dependency>
                  </dependencies>
                </project>
                """;
        var pomInput = new PomInput(rootPom.getBytes(StandardCharsets.UTF_8));
        var snapshot = RepositorySnapshot.create(REPO_ID, Optional.empty(), false,
                List.of(new SnapshotFile("pom.xml", pomInput.digest())), List.of());
        var buildReq = new BuildModelRequest(snapshot, "pom.xml", Map.of("pom.xml", pomInput), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 100, 16));
        var buildResult = new MavenBuildModelProvider().build(buildReq);
        var cpPolicy = new ClasspathResolutionPolicy(64, 64, 1_000_000, 10_000_000, 50_000_000, 16);
        var cpResult = new MavenLocalClasspathProvider(cache, false)
                .resolve(new ClasspathResolutionRequest(buildReq, buildResult, cpPolicy));

        var main = cpResult.manifests().getFirst();
        assertEquals(List.of("ext:lib:1.9.0@jar"),
                main.entries().stream().map(e -> e.coordinate().notation()).toList(),
                "Passive range [1.0,2.0) must pick highest non-SNAPSHOT cached version 1.9.0");
        assertTrue(main.problems().stream().anyMatch(p ->
                p.reason() == Reason.NON_EXACT_VERSION && p.subject().equals("ext:absent")),
                "Uncached version range must still emit NON_EXACT_VERSION");
    }

    @Test
    void s5OutOfOrderCompletionAvoidsHolBlockingAndMatchesSequentialLedgerDeterministically() throws Exception {
        var coords = new ArrayList<ArtifactCoordinate>();
        var payloads = new HashMap<String, byte[]>();
        for (int i = 0; i < 6; i++) {
            var coord = new ArtifactCoordinate(new MavenCoordinate("demo", "mod" + i, "1.0"), "jar", "");
            coords.add(coord);
            payloads.put(coord.repositoryPath(), minimalJar("demo/Mod" + i + ".class"));
        }

        // Parallel run: mod0 blocks until mod1 and mod2 have already completed (proving no HoL blocking when maxConcurrent=2 -> slots refill!).
        Path parCache = Files.createDirectory(temp.resolve("par-cache"));
        CountDownLatch laterCompleted = new CountDownLatch(2);
        var parProvider = new MavenDependencyArtifactProvider(parCache, (uri, max, ct, rt) -> {
            String path = uri.getPath().substring("/maven2/".length());
            if (path.contains("mod0")) {
                assertTrue(laterCompleted.await(5, TimeUnit.SECONDS),
                        "mod1 and mod2 must complete while mod0 is slow (S5 non-HoL slot refill)");
            } else if (path.contains("mod1") || path.contains("mod2")) {
                laterCompleted.countDown();
            }
            return new MavenDependencyArtifactProvider.RemoteResponse(200, payloads.get(path));
        }, ms -> {});

        var parPolicy = new DependencyAcquisitionPolicy(16, 1_000_000, 10_000_000, 4, List.of(REPO), 1_000, 2_000, 0, 2);
        var parRes = parProvider.acquire(DependencyAcquisitionRequest.of(coords, parPolicy));

        Path seqCache = Files.createDirectory(temp.resolve("seq-cache"));
        var seqProvider = new MavenDependencyArtifactProvider(seqCache, (uri, max, ct, rt) -> {
            String path = uri.getPath().substring("/maven2/".length());
            return new MavenDependencyArtifactProvider.RemoteResponse(200, payloads.get(path));
        }, ms -> {});
        var seqPolicy = new DependencyAcquisitionPolicy(16, 1_000_000, 10_000_000, 4, List.of(REPO), 1_000, 2_000, 0, 1);
        var seqRes = seqProvider.acquire(DependencyAcquisitionRequest.of(coords, seqPolicy));

        assertEquals(seqRes.outcomes(), parRes.outcomes(), "Parallel S5 outcomes must match sequential order exactly");
        assertEquals(seqRes.attempts(), parRes.attempts(), "Parallel S5 attempt ordinals must match sequential order exactly");
    }

    @Test
    void concurrentCachePromotionWithIdenticalDigestReusesWinnerCleanly() throws Exception {
        Path cache = Files.createDirectory(temp.resolve("race-cache"));
        var coord = new ArtifactCoordinate(new MavenCoordinate("demo", "race", "1.0"), "jar", "");
        byte[] jarBytes = minimalJar("demo/Race.class");
        CyclicBarrier barrier = new CyclicBarrier(2);
        var policy = new DependencyAcquisitionPolicy(4, 1_000_000, 10_000_000, 4, List.of(REPO), 1_000, 2_000, 0, 1);

        Callable<DependencyAcquisitionResult> task = () -> {
            var provider = new MavenDependencyArtifactProvider(cache, (uri, max, ct, rt) -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
                return new MavenDependencyArtifactProvider.RemoteResponse(200, jarBytes);
            }, ms -> {});
            return provider.acquire(DependencyAcquisitionRequest.of(List.of(coord), policy));
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(task);
            var f2 = pool.submit(task);
            assertEquals(DependencyAcquisitionResult.Status.ACQUIRED, f1.get(10, TimeUnit.SECONDS).outcomes().getFirst().status());
            assertEquals(DependencyAcquisitionResult.Status.ACQUIRED, f2.get(10, TimeUnit.SECONDS).outcomes().getFirst().status());
        }
    }

    @Test
    void gradleLocalJarInputsIsolatesAndroidSdkCacheByCacheRoot() throws Exception {
        Path cacheA = Files.createDirectory(temp.resolve("sdk-cache-a"));
        Path cacheB = Files.createDirectory(temp.resolve("sdk-cache-b"));
        Path sdkRoot = Files.createDirectories(temp.resolve("android-sdk/platforms/android-34"));
        Files.write(sdkRoot.resolve("android.jar"), minimalJar("android/app/Activity.class"));

        var a = GradleLocalJarInputs.recoverAndroidPlatformJar(
                cacheA, sdkRoot.getParent().getParent(), OptionalInt.of(34), 10_000_000L, 50_000_000L);
        var b = GradleLocalJarInputs.recoverAndroidPlatformJar(
                cacheB, sdkRoot.getParent().getParent(), OptionalInt.of(34), 10_000_000L, 50_000_000L);
        assertTrue(a.isPresent());
        assertTrue(b.isPresent());
        assertTrue(a.orElseThrow().path().toAbsolutePath().normalize().startsWith(cacheA.toAbsolutePath().normalize()),
                "Cache A must materialize under cacheA root, got " + a.orElseThrow().path());
        assertTrue(b.orElseThrow().path().toAbsolutePath().normalize().startsWith(cacheB.toAbsolutePath().normalize()),
                "Cache B must materialize under cacheB root, got " + b.orElseThrow().path());
    }

    private static void writeCachedArtifact(Path cache, String group, String artifact, String version, byte[] jarBytes) throws Exception {
        Path dir = cache.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        String pom = "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>"
                + artifact + "</artifactId><version>" + version + "</version></project>";
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), pom, StandardCharsets.UTF_8);
        Files.write(dir.resolve(artifact + "-" + version + ".jar"), jarBytes);
    }

    private static byte[] minimalJar(String entryName) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(out)) {
            var entry = new JarEntry(entryName);
            entry.setTime(0L);
            jar.putNextEntry(entry);
            jar.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jar.closeEntry();
        }
        return out.toByteArray();
    }
}
