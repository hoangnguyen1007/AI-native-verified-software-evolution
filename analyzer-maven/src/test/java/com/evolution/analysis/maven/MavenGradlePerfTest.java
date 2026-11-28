package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.dependency.*;
import com.evolution.analysis.input.VerifiedArtifactSession;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MavenGradlePerfTest {
    @TempDir
    Path temp;

    @Test
    void mavenBuildModelResolverBatchPrefetchesMultipleMissingBomsConcurrently() throws Exception {
        Path cacheRoot = Files.createDirectory(temp.resolve("m2"));
        var bomXmls = new HashMap<String, byte[]>();
        var bomImports = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            String aid = "bom-" + i;
            bomXmls.put(aid + "-1.0.pom",
                    ("<project><modelVersion>4.0.0</modelVersion><groupId>ext</groupId><artifactId>" + aid
                            + "</artifactId><version>1.0</version><packaging>pom</packaging></project>")
                            .getBytes(StandardCharsets.UTF_8));
            bomImports.append("<dependency><groupId>ext</groupId><artifactId>").append(aid)
                    .append("</artifactId><version>1.0</version><type>pom</type><scope>import</scope></dependency>");
        }
        String appPom = "<project><modelVersion>4.0.0</modelVersion><groupId>app</groupId><artifactId>app</artifactId>"
                + "<version>1.0</version><dependencyManagement><dependencies>" + bomImports
                + "</dependencies></dependencyManagement></project>";

        var entered = new CountDownLatch(4);
        var provider = new MavenDependencyArtifactProvider(cacheRoot, (uri, max, connect, read) -> {
            entered.countDown();
            if (!entered.await(3, TimeUnit.SECONDS)) {
                throw new java.io.IOException("Expected concurrent batch prefetch of all 4 missing BOM POMs");
            }
            String file = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
            byte[] data = bomXmls.get(file);
            return data == null
                    ? new MavenDependencyArtifactProvider.RemoteResponse(404, new byte[0])
                    : new MavenDependencyArtifactProvider.RemoteResponse(200, data);
        }, millis -> {});

        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/batch-bom.git");
        byte[] appBytes = appPom.getBytes(StandardCharsets.UTF_8);
        var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false,
                List.of(new SnapshotFile("pom.xml", ContentDigest.sha256(appBytes))), List.of());
        var req = new BuildModelRequest(snapshot, "pom.xml", Map.of("pom.xml", new PomInput(appBytes)),
                Map.of(), new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 100, 32));
        var policy = new MavenPomResolutionPolicy(100, 100_000, 5_000_000, 16,
                List.of(URI.create("https://repo.example.test/maven2/")), 1000, 2000);

        var result = new MavenBuildModelResolver(cacheRoot,
                (uri, max, c, r) -> { throw new AssertionError("Should use batched artifactProvider"); },
                Optional.of(provider)).resolve(req, policy);

        assertTrue(result.problems().isEmpty(), () -> result.problems().toString());
        assertEquals(4, result.acquiredPoms().size());
        assertEquals(4, result.request().artifactPoms().size());
    }

    @Test
    void managedDependenciesO1IndexPreservesFirstMatchAndScalesFast() throws Exception {
        Path cacheRoot = Files.createDirectory(temp.resolve("m2-bom"));
        var parentManaged = new StringBuilder();
        parentManaged.append("<dependency><groupId>org.example</groupId><artifactId>target-lib</artifactId><version>9.9.9</version></dependency>");
        for (int i = 0; i < 1200; i++) {
            parentManaged.append("<dependency><groupId>org.bom</groupId><artifactId>entry-").append(i)
                    .append("</artifactId><version>1.0.").append(i).append("</version></dependency>");
        }
        String parentPom = "<project><modelVersion>4.0.0</modelVersion><groupId>app</groupId><artifactId>parent</artifactId>"
                + "<version>1.0</version><packaging>pom</packaging><modules><module>child</module></modules>"
                + "<dependencyManagement><dependencies>" + parentManaged + "</dependencies></dependencyManagement></project>";
        // Child overrides target-lib to 2.0.0 ahead of inherited parent 9.9.9.
        String childPom = "<project><modelVersion>4.0.0</modelVersion>"
                + "<parent><groupId>app</groupId><artifactId>parent</artifactId><version>1.0</version></parent>"
                + "<artifactId>child</artifactId>"
                + "<dependencyManagement><dependencies>"
                + "<dependency><groupId>org.example</groupId><artifactId>target-lib</artifactId><version>2.0.0</version></dependency>"
                + "</dependencies></dependencyManagement>"
                + "<dependencies><dependency><groupId>org.example</groupId><artifactId>mid</artifactId><version>1.0</version></dependency></dependencies></project>";

        writeLocal(cacheRoot, "org/example/mid/1.0/mid-1.0.pom",
                ("<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId><artifactId>mid</artifactId><version>1.0</version>"
                        + "<dependencies><dependency><groupId>org.example</groupId><artifactId>target-lib</artifactId><version>1.0.0</version></dependency></dependencies></project>")
                        .getBytes(StandardCharsets.UTF_8));
        writeLocal(cacheRoot, "org/example/mid/1.0/mid-1.0.jar", minimalJar("org/example/Mid.class"));
        writeLocal(cacheRoot, "org/example/target-lib/2.0.0/target-lib-2.0.0.pom",
                "<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId><artifactId>target-lib</artifactId><version>2.0.0</version></project>"
                        .getBytes(StandardCharsets.UTF_8));
        writeLocal(cacheRoot, "org/example/target-lib/2.0.0/target-lib-2.0.0.jar", minimalJar("org/example/Target.class"));

        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/bom-perf.git");
        byte[] parentBytes = parentPom.getBytes(StandardCharsets.UTF_8);
        byte[] childBytes = childPom.getBytes(StandardCharsets.UTF_8);
        var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false,
                List.of(
                        new SnapshotFile("pom.xml", ContentDigest.sha256(parentBytes)),
                        new SnapshotFile("child/pom.xml", ContentDigest.sha256(childBytes))),
                List.of());
        var buildReq = new BuildModelRequest(snapshot, "pom.xml",
                Map.of("pom.xml", new PomInput(parentBytes), "child/pom.xml", new PomInput(childBytes)),
                Map.of(), new BuildModelPolicy(List.of(), List.of(), Map.of(), 1_000_000, 100, 32));
        var buildModel = new MavenBuildModelProvider().build(buildReq);
        assertTrue(buildModel.problems().isEmpty(), () -> buildModel.problems().toString());
        var cpReq = new ClasspathResolutionRequest(buildReq, buildModel,
                new ClasspathResolutionPolicy(500, 500, 1_000_000, 5_000_000, 20_000_000, 16));

        long t0 = System.nanoTime();
        var result = new MavenLocalClasspathProvider(cacheRoot, false).resolve(cpReq);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        var childManifest = result.manifests().stream()
                .filter(m -> m.module().equals(com.evolution.analysis.contract.identity.ModuleIdentity.from(repo, "child")))
                .findFirst().orElseThrow();
        var entries = childManifest.entries();
        assertTrue(entries.stream().anyMatch(e -> e.coordinate().gav().notation().equals("org.example:target-lib:2.0.0")),
                () -> "entries=" + entries + ", problems=" + childManifest.problems());
        assertTrue(entries.stream().noneMatch(e -> e.coordinate().gav().notation().equals("org.example:target-lib:9.9.9")));
        assertTrue(elapsedMs < 5_000, "Indexed BOM lookup took unexpectedly long: " + elapsedMs + "ms");
        System.out.printf("[PERF-BENCH] MavenLocalClasspathProvider (1201 managed BOM rows): %dms%n", elapsedMs);
    }

    @Test
    void persistedValidationReceiptAndGradleLocalJarSessionReuseWorkSafely() throws Exception {
        Path cacheRoot = Files.createDirectory(temp.resolve("m2-valid"));
        byte[] jarBytes = minimalJar("demo/Lib.class");
        ContentDigest digest = ContentDigest.sha256(jarBytes);
        var coord = new ArtifactCoordinate(new MavenCoordinate("demo", "lib", "1.0"), "jar", "");
        writeLocal(cacheRoot, coord.repositoryPath(), jarBytes);

        var provider = new MavenDependencyArtifactProvider(cacheRoot,
                (uri, max, c, r) -> { throw new AssertionError("Should hit local cache"); }, millis -> {});
        var policy = new DependencyAcquisitionPolicy(16, 1_000_000, 5_000_000, 1,
                List.of(URI.create("https://repo.example.test/maven2/")), 1000, 2000, 0, 4);

        var r1 = provider.acquire(DependencyAcquisitionRequest.of(List.of(coord), policy));
        assertFalse(r1.hasFailures());
        assertTrue(Files.isRegularFile(cacheRoot.resolve(".evo-cache-index/validated-artifacts.v1")));

        // Reuse local JAR inside a VerifiedArtifactSession across multiple calls.
        try (var session = VerifiedArtifactSession.open(true)) {
            var first = GradleLocalJarInputs.recover(cacheRoot, "libs/lib.jar", jarBytes, digest, 1_000_000, 10_000_000);
            var second = GradleLocalJarInputs.recover(cacheRoot, "libs/lib.jar", jarBytes, digest, 1_000_000, 10_000_000);
            assertEquals(first, second);
            assertTrue(session.hits() >= 1);
        }

        // Corrupt the cached file on disk -> different SHA-256 -> MUST fail validation as CORRUPTED_CACHE.
        byte[] corrupt = "not-a-zip-archive".getBytes(StandardCharsets.UTF_8);
        writeLocal(cacheRoot, coord.repositoryPath(), corrupt);
        var r2 = provider.acquire(DependencyAcquisitionRequest.of(List.of(coord), policy));
        assertEquals(DependencyAcquisitionResult.FailureReason.CORRUPTED_CACHE,
                r2.outcomes().getFirst().failureReason().orElseThrow());
    }

    @Test
    void localArtifactCacheSessionReuseEliminatesRedundantReadsWithBitIdenticalResults() throws Exception {
        Path cacheRoot = Files.createDirectory(temp.resolve("m2-session"));
        byte[] pomBytes = "<project><modelVersion>4.0.0</modelVersion><groupId>acme</groupId><artifactId>shared</artifactId><version>1.2.3</version></project>"
                .getBytes(StandardCharsets.UTF_8);
        byte[] jarBytes = minimalJar("acme/Shared.class");
        var gav = new MavenCoordinate("acme", "shared", "1.2.3");
        var jarCoord = new ArtifactCoordinate(gav, "jar", "");
        writeLocal(cacheRoot, new ArtifactCoordinate(gav, "pom", "").repositoryPath(), pomBytes);
        writeLocal(cacheRoot, jarCoord.repositoryPath(), jarBytes);

        var cpPolicy = new ClasspathResolutionPolicy(500, 500, 1_000_000, 5_000_000, 20_000_000, 16);
        var baselineCache = new LocalArtifactCache(cacheRoot, Map.of(), cpPolicy, ignored -> {});
        var baselinePom = baselineCache.pom(gav);
        var baselineJar = baselineCache.jar(jarCoord);

        try (var session = VerifiedArtifactSession.open(true)) {
            var cache1 = new LocalArtifactCache(cacheRoot, Map.of(), cpPolicy, ignored -> {});
            var firstPom = cache1.pom(gav);
            var firstJar = cache1.jar(jarCoord);

            // Second distinct LocalArtifactCache (simulating another Maven root in a multi-root repo)
            var cache2 = new LocalArtifactCache(cacheRoot, Map.of(), cpPolicy, ignored -> {});
            var secondPom = cache2.pom(gav);
            var secondJar = cache2.jar(jarCoord);

            assertTrue(session.hits() >= 2, "Second root must hit VerifiedArtifactSession for both POM & JAR, hits=" + session.hits());
            assertEquals(baselinePom.record(), firstPom.record());
            assertEquals(baselinePom.record(), secondPom.record());
            assertArrayEquals(baselinePom.bytes(), secondPom.bytes());
            assertEquals(baselineJar.record(), firstJar.record());
            assertEquals(baselineJar.record(), secondJar.record());
            assertArrayEquals(baselineJar.bytes(), secondJar.bytes());
        }
    }

    private static void writeLocal(Path root, String relative, byte[] bytes) throws Exception {
        Path target = root.resolve(relative.replace('/', java.io.File.separatorChar));
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static byte[] minimalJar(String entryName) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry(entryName));
            jar.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jar.closeEntry();
        }
        return out.toByteArray();
    }
}
