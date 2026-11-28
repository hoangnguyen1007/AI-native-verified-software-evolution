package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.dependency.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.EvidenceAcquisitionLedger;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.frontend.BinaryInput;
import com.evolution.analysis.frontend.PlatformInput;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.input.PlatformSymbolRequest;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import com.evolution.analysis.maven.*;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.analysis.readiness.ReadinessPlanner;
import com.evolution.backend.query.PublishedReports;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD regression contract for cold-vs-warm1 whole-repository canonical digest determinism:
 * operational cache misses, mirror fallbacks, retries, and obsolete intermediate-pass speculative
 * fetches must never alter canonical KnowledgeGraph identity or capability-gap denominators when
 * the final resolved artifacts and unresolved obligations are identical.
 */
class ColdWarmDeterminismTest {
    private static final URI REPO_A = URI.create("https://mirror-a.example.test/maven2/");
    private static final URI REPO_B = URI.create("https://mirror-b.example.test/maven2/");

    @TempDir
    Path temp;

    @Test
    void coldRemoteFetchWithRetriesMatchesWarmCacheHitBitIdentically() throws Exception {
        Path source = Files.createDirectory(temp.resolve("maven-cold-warm-src"));
        Files.writeString(source.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>app</artifactId><version>1.0</version>
                <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies>
                  <dependency><groupId>external</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                </dependencies></project>
                """);
        Files.createDirectories(source.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("src/main/java/demo/App.java"),
                "package demo; public class App { int go() { return external.Lib.value(); } }\n");

        byte[] libJar = compileJar("external/Lib.java",
                "package external; public class Lib { public static int value() { return 7; } }");
        byte[] libPom = """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>external</groupId><artifactId>lib</artifactId><version>1.0</version></project>
                """.getBytes(StandardCharsets.UTF_8);

        var acquisition = acquireSource(source, "urn:test:cold-warm-maven-retry");
        var platform = acquireJdk21();
        var policy = new DependencyAcquisitionPolicy(128, 10_000_000L, 100_000_000L, 16,
                List.of(REPO_A, REPO_B), 5_000, 15_000, 1, 4);

        Path cache = Files.createDirectory(temp.resolve("maven-m2"));
        var models = MavenScan.models(acquisition, cache, List.of());

        var libPomCoord = new ArtifactCoordinate(new MavenCoordinate("external", "lib", "1.0"), "pom", "");
        var libJarCoord = new ArtifactCoordinate(new MavenCoordinate("external", "lib", "1.0"), "jar", "");
        Map<ArtifactCoordinate, byte[]> remoteRepo = Map.of(libPomCoord, libPom, libJarCoord, libJar);

        // Cold run: empty .m2 cache; mirror-a returns 404 / transient 503 retry, mirror-b succeeds and populates .m2.
        var coldInputs = MavenScan.inputs(acquisition, models, cache, Optional.of(platform), policy,
                Map.of(21, platform), null,
                (req, depPolicy) -> simulateMultiPassMaven(cache, req, depPolicy, remoteRepo, true));

        // Warm1 run: .m2 cache is now populated from Cold; zero remote acquisitions needed!
        var warmInputs = MavenScan.inputs(acquisition, models, cache, Optional.of(platform), policy,
                Map.of(21, platform), null,
                (req, depPolicy) -> simulateMultiPassMaven(cache, req, depPolicy, remoteRepo, false));

        // Operational telemetry in summary MUST still reflect differing cold vs warm acquisition activity.
        assertNotEquals(coldInputs.summary(), warmInputs.summary(),
                "Operational summary should still distinguish cold remote acquisition from warm cache hit");
        @SuppressWarnings("unchecked")
        var coldRoot = (Map<String, Object>) ((List<?>) coldInputs.summary().get("roots")).getFirst();
        @SuppressWarnings("unchecked")
        var warmRoot = (Map<String, Object>) ((List<?>) warmInputs.summary().get("roots")).getFirst();
        assertTrue(((Number) coldRoot.get("acquisitionAttempts")).intValue() >= 8,
                "Cold operational summary must retain retry/mirror attempt count: " + coldRoot);
        assertEquals(0, ((Number) warmRoot.get("acquisitionAttempts")).intValue(),
                "Warm operational summary must record zero remote acquisition attempts: " + warmRoot);

        // Canonical ledgers (and their identities hashed by WorkbenchReuseInputs) and gaps MUST be bit-identical.
        assertEquals(coldInputs.resolutions(), warmInputs.resolutions());
        assertEquals(coldInputs.gaps(), warmInputs.gaps());
        assertEquals(coldInputs.ledgers(), warmInputs.ledgers(),
                "Canonical ledgers must not embed operational cold cache-miss/retry telemetry");
        assertEquals(
                coldInputs.ledgers().stream().map(EvidenceAcquisitionLedger::identity).toList(),
                warmInputs.ledgers().stream().map(EvidenceAcquisitionLedger::identity).toList());

        // Build full KnowledgeGraph (both in-memory AND disk-spilled PartitionedGraphStore) and verify bit-identical SHA-256.
        var coldGraph = buildGraph(acquisition, platform, coldInputs);
        var warmGraph = buildGraph(acquisition, platform, warmInputs);
        assertEquals(coldGraph.identity(), warmGraph.identity(),
                "Whole-repository canonical KnowledgeGraph SHA-256 must match bit-for-bit across cold remote fetch and warm cache hit");
        assertEquals(countKind(coldGraph, "ACQUISITION_ATTEMPT"), countKind(warmGraph, "ACQUISITION_ATTEMPT"));
        assertEquals(countKind(coldGraph, "CAPABILITY_GAP"), countKind(warmGraph, "CAPABILITY_GAP"));
        assertEquals(countKind(coldGraph, "ACQUISITION_ISSUE"), countKind(warmGraph, "ACQUISITION_ISSUE"));

        // Verify disk-spilled PartitionedGraphStore produces the exact same canonical graph SHA-256.
        Path coldSnapDir = Files.createDirectory(temp.resolve("snaps-cold"));
        Path warmSnapDir = Files.createDirectory(temp.resolve("snaps-warm"));
        var coldPub = buildPartitioned(acquisition, platform, coldInputs, temp.resolve("pages-cold"), coldSnapDir);
        var warmPub = buildPartitioned(acquisition, platform, warmInputs, temp.resolve("pages-warm"), warmSnapDir);
        assertEquals(coldGraph.identity(), coldPub.graph());
        assertEquals(coldPub.graph(), warmPub.graph());

        // Verify semantic call resolution actually used the acquired library JAR.
        var detailed = runPipeline(acquisition, platform, coldInputs);
        var calls = detailed.graphInput().orElseThrow().ingestion().orElseThrow().units().stream()
                .flatMap(u -> u.frontend().stream()).flatMap(f -> f.observations().stream())
                .filter(o -> o.category().value().equals("java.calls") && o.reference().contains("Lib.value"))
                .toList();
        assertEquals(1, calls.size());
        assertEquals(SemanticStatus.RESOLVED, calls.getFirst().attribution());
    }

    @Test
    void multiPassRelocatedArtifactAndPermanentFailureStayBitIdenticalAcrossColdAndWarm() throws Exception {
        Path source = Files.createDirectory(temp.resolve("maven-reloc-src"));
        Files.writeString(source.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>reloc-app</artifactId><version>1.0</version>
                <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies>
                  <dependency><groupId>old</groupId><artifactId>legacy</artifactId><version>1.0</version></dependency>
                  <dependency><groupId>absent</groupId><artifactId>ghost</artifactId><version>9.9</version></dependency>
                </dependencies></project>
                """);
        Files.createDirectories(source.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("src/main/java/demo/UseTarget.java"),
                "package demo; public class UseTarget { int n() { return modern.Target.id(); } }\n");

        // Relocated POM: old:legacy:1.0 has ONLY a .pom (no .jar on remote!) relocating to modern:target:2.0.
        byte[] relocPom = """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>old</groupId><artifactId>legacy</artifactId><version>1.0</version>
                <distributionManagement><relocation>
                  <groupId>modern</groupId><artifactId>target</artifactId><version>2.0</version>
                </relocation></distributionManagement></project>
                """.getBytes(StandardCharsets.UTF_8);
        byte[] targetPom = """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>modern</groupId><artifactId>target</artifactId><version>2.0</version></project>
                """.getBytes(StandardCharsets.UTF_8);
        byte[] targetJar = compileJar("modern/Target.java",
                "package modern; public class Target { public static int id() { return 42; } }");

        var acquisition = acquireSource(source, "urn:test:cold-warm-reloc-and-missing");
        var platform = acquireJdk21();
        var policy = new DependencyAcquisitionPolicy(128, 10_000_000L, 100_000_000L, 16,
                List.of(REPO_A, REPO_B), 5_000, 15_000, 1, 4);

        Path cache = Files.createDirectory(temp.resolve("maven-reloc-m2"));
        var models = MavenScan.models(acquisition, cache, List.of());

        var relocPomCoord = new ArtifactCoordinate(new MavenCoordinate("old", "legacy", "1.0"), "pom", "");
        var targetPomCoord = new ArtifactCoordinate(new MavenCoordinate("modern", "target", "2.0"), "pom", "");
        var targetJarCoord = new ArtifactCoordinate(new MavenCoordinate("modern", "target", "2.0"), "jar", "");
        // Note: old:legacy:1.0@jar and absent:ghost:9.9@{pom,jar} are ABSENT from remoteRepo (return 404).
        Map<ArtifactCoordinate, byte[]> remoteRepo = Map.of(
                relocPomCoord, relocPom,
                targetPomCoord, targetPom,
                targetJarCoord, targetJar);

        // Cold run: Pass 1 speculatively asks for old:legacy:1.0@jar (404!) alongside old:legacy:1.0@pom (200) and
        // absent:ghost:9.9@{pom,jar} (404!). Pass 2 sees relocation (withholds old:legacy:1.0) and downloads modern:target:2.0.
        var coldInputs = MavenScan.inputs(acquisition, models, cache, Optional.of(platform), policy,
                Map.of(21, platform), null,
                (req, depPolicy) -> simulateMultiPassMaven(cache, req, depPolicy, remoteRepo, true));

        // Warm1 run: old:legacy:1.0.pom and modern:target:2.0.{pom,jar} are ALREADY cached in .m2!
        // old:legacy:1.0@jar is NEVER requested; ONLY absent:ghost:9.9@{pom,jar} fails in a smaller batch!
        var warmInputs = MavenScan.inputs(acquisition, models, cache, Optional.of(platform), policy,
                Map.of(21, platform), null,
                (req, depPolicy) -> simulateMultiPassMaven(cache, req, depPolicy, remoteRepo, false));

        // 1. Relocated coordinate's obsolete Pass-1 404 must NOT leak a phantom gap on Cold (fixes 1585 -> 1584).
        assertTrue(coldInputs.gaps().stream().noneMatch(g -> g.subject().identity().equals("old:legacy:1.0@jar")),
                "Obsolete intermediate-pass failure for withheld relocated JAR must not become a capability gap: "
                        + coldInputs.gaps());

        // 2. Permanently unresolved dependency MUST retain its typed ARTIFACT_NOT_FOUND gaps on BOTH Cold and Warm1 (I3).
        assertTrue(coldInputs.gaps().stream().anyMatch(g ->
                g.reasonCode().equals("ARTIFACT_NOT_FOUND") && g.subject().identity().equals("absent:ghost:9.9@jar")));
        assertTrue(coldInputs.gaps().stream().anyMatch(g ->
                g.reasonCode().equals("ARTIFACT_NOT_FOUND") && g.subject().identity().equals("absent:ghost:9.9@pom")));
        assertTrue(coldInputs.gaps().stream().anyMatch(g ->
                g.reasonCode().equals("MISSING_ARTIFACT") && g.subject().identity().contains("ghost-9.9.jar")));

        // 3. Exact gap count, exact CapabilityGapRecord payloads, exact ledgers, and exact KnowledgeGraph SHA-256 MUST match!
        assertEquals(coldInputs.gaps().size(), warmInputs.gaps().size(),
                "Cold and Warm1 must produce the exact same closed capability-gap count");
        assertEquals(coldInputs.gaps(), warmInputs.gaps(),
                "Permanently unresolved gap records must be batch-independent and bit-identical across Cold and Warm1");
        assertEquals(coldInputs.ledgers(), warmInputs.ledgers());

        var coldGraph = buildGraph(acquisition, platform, coldInputs);
        var warmGraph = buildGraph(acquisition, platform, warmInputs);
        assertEquals(coldGraph.identity(), warmGraph.identity(),
                "Whole-repository KnowledgeGraph SHA-256 must match bit-for-bit across Cold and Warm1 even with permanent failures and relocations");
        assertEquals(countKind(coldGraph, "CAPABILITY_GAP"), countKind(warmGraph, "CAPABILITY_GAP"));
        assertEquals(countKind(coldGraph, "ACQUISITION_ISSUE"), countKind(warmGraph, "ACQUISITION_ISSUE"));
        assertTrue(countKind(coldGraph, "CAPABILITY_GAP") >= 3,
                "All genuinely unresolved capability gaps must be preserved in the canonical graph");

        // 4. ArchitectureQueryService.gaps() still exposes every CAPABILITY_GAP cleanly on the published graph.
        var queries = new ArchitectureQueryService(coldGraph);
        var page = queries.gaps(ArchitectureQuery.Request.dependencies(coldGraph.identity(), coldGraph.snapshot().value()),
                ArchitectureQuery.GapView.CURRENT, new ArchitectureQuery.Page(50, Optional.empty()));
        assertEquals(ArchitectureQuery.Operation.COMPLETED, page.operation());
        assertFalse(page.items().isEmpty());
    }

    @Test
    void gradleColdRemoteFetchMatchesWarmLocalCacheHitBitIdentically() throws Exception {
        Path source = Files.createDirectory(temp.resolve("gradle-cold-warm-src"));
        Files.writeString(source.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
        Files.writeString(source.resolve("build.gradle"), """
                plugins { id 'java' }
                dependencies {
                    implementation 'external:glib:1.0'
                    implementation 'absent:missing:2.0'
                }
                """);
        Files.createDirectories(source.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("src/main/java/demo/Main.java"),
                "package demo; public class Main { int x() { return external.GLib.n(); } }\n");

        byte[] jarBytes = compileJar("external/GLib.java",
                "package external; public class GLib { public static int n() { return 9; } }");
        byte[] pomBytes = """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>external</groupId><artifactId>glib</artifactId><version>1.0</version></project>
                """.getBytes(StandardCharsets.UTF_8);

        Path cache = Files.createDirectory(temp.resolve("gradle-m2"));
        var libCoord = new ArtifactCoordinate(new MavenCoordinate("external", "glib", "1.0"), "jar", "");
        var libPomCoord = new ArtifactCoordinate(new MavenCoordinate("external", "glib", "1.0"), "pom", "");
        var missingCoord = new ArtifactCoordinate(new MavenCoordinate("absent", "missing", "2.0"), "jar", "");
        writeCacheFile(cache, libCoord, jarBytes);
        writeCacheFile(cache, libPomCoord, pomBytes);
        var jarDigest = ContentDigest.sha256(jarBytes);
        var pomDigest = ContentDigest.sha256(pomBytes);

        var acquisition = acquireSource(source, "urn:test:cold-warm-gradle");
        var platform = acquireJdk21();
        var buildPolicy = new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21),
                Optional.of("UTF-8"), 2048, 1_000_000, 200_000, 64);
        var decoding = new RepositoryInputDecoder.Policy("UTF-8", 100, 1_000_000);
        var model = GradleScan.model(acquisition, buildPolicy, decoding, Map.of());
        assertTrue(model.isPresent());

        var depPolicy = new DependencyAcquisitionPolicy(128, 10_000_000L, 100_000_000L, 16,
                List.of(REPO_A, REPO_B), 5_000, 15_000, 1, 4);
        var base = new MavenScan.Inputs(Map.of(), Map.of(), List.of(), Map.of("mode", "NOT_APPLICABLE"));

        var mod = model.orElseThrow().modules().getFirst();
        var set = mod.sourcePlan().sourceSets().stream().filter(s -> s.kind() == SourcePlanModel.Kind.MAIN).findFirst().orElseThrow();
        var sourceSet = new UniversalSourceIngestion.SourceSet(set.module(), set.kind(), set.name());
        var binary = new BinaryInput(libCoord.classpathEntry(jarDigest), cache.resolve(libCoord.repositoryPath()));
        var ctx = new GradleDependencyArtifactResolver.Context(sourceSet, List.of(binary), List.of(),
                List.of(new GradleDependencyArtifactResolver.Problem("GRADLE_EFFECTIVE_CONTEXT_REQUIRED", ".#main"),
                        new GradleDependencyArtifactResolver.Problem("GRADLE_BINARY_UNAVAILABLE", missingCoord.notation())),
                List.of(), ContentDigest.sha256Utf8("gradle-ctx-proof"));

        // Cold Gradle result: 2 rounds (POM remote fetch + binary remote fetch with retries & batch-coupled missing dep).
        var coldPomReq = DependencyAcquisitionRequest.of(List.of(libPomCoord), depPolicy.withLimits(1, 100_000_000L));
        var coldPomRes = DependencyAcquisitionResult.create(coldPomReq, MavenDependencyArtifactProvider.VERSION,
                List.of(new DependencyAcquisitionResult.Outcome(coldPomReq.requirements().getFirst(),
                        DependencyAcquisitionResult.Status.SKIPPED_POM,
                        Optional.of(new DependencyAcquisitionResult.Artifact(libPomCoord,
                                DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY, libPomCoord.repositoryPath(),
                                pomDigest, pomBytes.length)),
                        Optional.empty())),
                List.of(
                        new DependencyAcquisitionResult.Attempt(0, libPomCoord, DependencyAcquisitionResult.Origin.LOCAL_CACHE,
                                libPomCoord.repositoryPath(), DependencyAcquisitionResult.AttemptOutcome.UNAVAILABLE, Optional.empty()),
                        new DependencyAcquisitionResult.Attempt(1, libPomCoord, DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                                REPO_A.resolve(libPomCoord.repositoryPath()).toASCIIString(),
                                DependencyAcquisitionResult.AttemptOutcome.SUCCEEDED, Optional.of(pomDigest))),
                pomBytes.length);

        var coldBinReq = DependencyAcquisitionRequest.of(List.of(libCoord, missingCoord),
                depPolicy.withLimits(2, 100_000_000L - pomBytes.length));
        var coldBinRes = DependencyAcquisitionResult.create(coldBinReq, MavenDependencyArtifactProvider.VERSION,
                List.of(
                        new DependencyAcquisitionResult.Outcome(coldBinReq.requirements().get(0),
                                DependencyAcquisitionResult.Status.ACQUIRED,
                                Optional.of(new DependencyAcquisitionResult.Artifact(libCoord,
                                        DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY, libCoord.repositoryPath(),
                                        jarDigest, jarBytes.length)),
                                Optional.empty()),
                        new DependencyAcquisitionResult.Outcome(coldBinReq.requirements().get(1),
                                DependencyAcquisitionResult.Status.FAILED, Optional.empty(),
                                Optional.of(DependencyAcquisitionResult.FailureReason.ARTIFACT_NOT_FOUND))),
                List.of(
                        new DependencyAcquisitionResult.Attempt(0, libCoord, DependencyAcquisitionResult.Origin.LOCAL_CACHE,
                                libCoord.repositoryPath(), DependencyAcquisitionResult.AttemptOutcome.UNAVAILABLE, Optional.empty()),
                        new DependencyAcquisitionResult.Attempt(1, libCoord, DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                                REPO_A.resolve(libCoord.repositoryPath()).toASCIIString(),
                                DependencyAcquisitionResult.AttemptOutcome.FAILED, Optional.empty()),
                        new DependencyAcquisitionResult.Attempt(2, libCoord, DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                                REPO_B.resolve(libCoord.repositoryPath()).toASCIIString(),
                                DependencyAcquisitionResult.AttemptOutcome.SUCCEEDED, Optional.of(jarDigest)),
                        new DependencyAcquisitionResult.Attempt(3, missingCoord, DependencyAcquisitionResult.Origin.LOCAL_CACHE,
                                missingCoord.repositoryPath(), DependencyAcquisitionResult.AttemptOutcome.UNAVAILABLE, Optional.empty()),
                        new DependencyAcquisitionResult.Attempt(4, missingCoord, DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                                REPO_A.resolve(missingCoord.repositoryPath()).toASCIIString(),
                                DependencyAcquisitionResult.AttemptOutcome.UNAVAILABLE, Optional.empty())),
                jarBytes.length);

        var coldGradleRes = new GradleDependencyArtifactResolver.Result(
                List.of(ctx), List.of(coldPomRes, coldBinRes),
                List.of(new GradleDependencyArtifactResolver.Problem("ARTIFACT_NOT_FOUND", missingCoord.notation())));

        // Warm1 Gradle result: POM already on disk (0 POM acquisitions!), binary hits LOCAL_CACHE, only missingCoord fails!
        var warmBinReq = DependencyAcquisitionRequest.of(List.of(libCoord, missingCoord), depPolicy.withLimits(2, 100_000_000L));
        var warmBinRes = DependencyAcquisitionResult.create(warmBinReq, MavenDependencyArtifactProvider.VERSION,
                List.of(
                        new DependencyAcquisitionResult.Outcome(warmBinReq.requirements().get(0),
                                DependencyAcquisitionResult.Status.CACHED,
                                Optional.of(new DependencyAcquisitionResult.Artifact(libCoord,
                                        DependencyAcquisitionResult.Origin.LOCAL_CACHE, libCoord.repositoryPath(),
                                        jarDigest, jarBytes.length)),
                                Optional.empty()),
                        new DependencyAcquisitionResult.Outcome(warmBinReq.requirements().get(1),
                                DependencyAcquisitionResult.Status.FAILED, Optional.empty(),
                                Optional.of(DependencyAcquisitionResult.FailureReason.ARTIFACT_NOT_FOUND))),
                List.of(
                        new DependencyAcquisitionResult.Attempt(0, libCoord, DependencyAcquisitionResult.Origin.LOCAL_CACHE,
                                libCoord.repositoryPath(), DependencyAcquisitionResult.AttemptOutcome.SUCCEEDED, Optional.of(jarDigest)),
                        new DependencyAcquisitionResult.Attempt(1, missingCoord, DependencyAcquisitionResult.Origin.LOCAL_CACHE,
                                missingCoord.repositoryPath(), DependencyAcquisitionResult.AttemptOutcome.UNAVAILABLE, Optional.empty()),
                        new DependencyAcquisitionResult.Attempt(2, missingCoord, DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                                REPO_A.resolve(missingCoord.repositoryPath()).toASCIIString(),
                                DependencyAcquisitionResult.AttemptOutcome.UNAVAILABLE, Optional.empty())),
                jarBytes.length);

        var warmGradleRes = new GradleDependencyArtifactResolver.Result(
                List.of(ctx), List.of(warmBinRes),
                List.of(new GradleDependencyArtifactResolver.Problem("ARTIFACT_NOT_FOUND", missingCoord.notation())));

        var coldInputs = GradleScan.augment(base, acquisition, model, cache, Optional.of(platform), depPolicy,
                Map.of(21, platform), Optional.empty(), null, (b, p) -> coldGradleRes);
        var warmInputs = GradleScan.augment(base, acquisition, model, cache, Optional.of(platform), depPolicy,
                Map.of(21, platform), Optional.empty(), null, (b, p) -> warmGradleRes);

        assertFalse(coldInputs.ledgers().isEmpty());
        @SuppressWarnings("unchecked")
        var coldGradleSum = (Map<String, Object>) coldInputs.summary().get("gradle");
        @SuppressWarnings("unchecked")
        var warmGradleSum = (Map<String, Object>) warmInputs.summary().get("gradle");
        assertEquals(7, ((Number) coldGradleSum.get("acquisitionAttempts")).intValue());
        assertEquals(3, ((Number) warmGradleSum.get("acquisitionAttempts")).intValue());
        assertTrue(coldInputs.gaps().stream().anyMatch(g ->
                g.reasonCode().equals("ARTIFACT_NOT_FOUND") && g.subject().identity().equals(missingCoord.notation())));
        assertEquals(coldInputs.gaps(), warmInputs.gaps());
        assertEquals(coldInputs.ledgers(), warmInputs.ledgers());

        var coldGraph = buildGraph(acquisition, platform, coldInputs);
        var warmGraph = buildGraph(acquisition, platform, warmInputs);
        assertEquals(coldGraph.identity(), warmGraph.identity(),
                "Gradle cold remote fetch vs warm cache hit must yield bit-identical KnowledgeGraph SHA-256");
    }

    @Test
    void polyglotCacheLookupAccountingIsSymmetricAcrossAllTaskModesAndEditCycles() throws Exception {
        Path source = Files.createDirectory(temp.resolve("poly-symmetry-src"));
        String sharedOpenApiJson = "{\"openapi\":\"3.0.3\",\"paths\":{\"/api/v1/items\":{\"get\":{\"operationId\":\"listItems\"}}}}\n";
        writeText(source.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>poly-sym</artifactId><version>1.0</version>
                <dependencies>
                  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId><version>3.4.0</version></dependency>
                </dependencies></project>
                """);
        Path controllerJava = source.resolve("src/main/java/demo/ItemController.java");
        writeText(controllerJava, """
                package demo;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class ItemController {
                    @GetMapping("/api/v1/items")
                    public String list() { return "ok"; }
                }
                """);
        writeText(source.resolve("src/main/java/demo/PlainHelper.java"),
                "package demo; public class PlainHelper { public int sum(int a, int b) { return a + b; } }\n");
        writeText(source.resolve("src/main/java/demo/OrderGrpcService.java"),
                "package demo; class OrderGrpcService extends order.OrderServiceGrpc.OrderServiceImplBase { public void getOrder(Req r, StreamObserver<Reply> o) {} }\n");
        writeText(source.resolve("src/main/resources/application.properties"),
                "spring.application.name=catalog-service\nserver.port=8081\n");
        writeText(source.resolve("src/main/resources/logback-spring.xml"),
                "<configuration><appender name=\"OUT\" class=\"ch.qos.logback.core.ConsoleAppender\"/></configuration>\n");
        writeText(source.resolve("deploy/helm-deploy.yaml"),
                "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: {{ .Release.Name }}-app\n");
        writeText(source.resolve("config/meta.json"),
                "{\"name\":\"plain-metadata\",\"version\":\"1.0.0\"}\n");
        // Identical JSON body in both .json (OPENAPI_JSON_PROBE) and openapi.yml (DECLARATIVE_INFRA) verifies zero cross-mode key collision.
        writeText(source.resolve("api/spec.json"), sharedOpenApiJson);
        writeText(source.resolve("api/openapi.yml"), sharedOpenApiJson);
        writeText(source.resolve("proxy/nginx.conf"),
                "http { server { listen 80; location /api/ { proxy_pass http://catalog-service:8081; } } }\n");
        writeText(source.resolve("Dockerfile"),
                "FROM eclipse-temurin:21-jre-alpine\nEXPOSE 8081\n");
        writeText(source.resolve("web/client.ts"),
                "export function fetchItems() { return fetch('/api/v1/items'); }\n");
        writeText(source.resolve("web/App.vue"),
                "<template><div/></template>\n<script lang=\"ts\">\nexport function loadView() { return fetch('/api/v1/items'); }\n</script>\n");

        Path cacheRoot = Files.createDirectory(temp.resolve("poly-symmetry-cache"));
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:polyglot-lookup-symmetry");
        var req = new StreamingAcquisitionRequest(repo, Optional.empty(), true, List.of());
        var scan = new PolyglotScan();

        try (var intake = new RepositoryIntake(temp.resolve("poly-symmetry-intake"))) {
            // 1. Cold scan (empty polyglot-v3 cache)
            assertEquals(JobJournal.State.COMPLETE, intake.localComplete(source, "cold1", req, () -> false).state());
            var rCold = intake.readiness("cold1", ReadinessPlanner.Policy.comprehensive(), () -> false);
            var sCold = RepositorySnapshot.create(repo, Optional.empty(), true, List.of(), List.of());
            var coldRes = scan.run("cold1", intake, rCold, EvidenceContext.forSnapshot(sCold.identity()),
                    Optional.empty(), temp.resolve("poly-w-cold"), () -> false, d -> null, false, p -> false, Optional.of(cacheRoot));
            long coldTotal = coldRes.syntax().cacheMisses();
            assertEquals(0L, coldRes.syntax().cacheHits(), "Cold scan must start with 0 cache hits");
            assertTrue(coldTotal >= 14L, "Expected all task modes to participate in single-slot accounting, got " + coldTotal);
            assertEquals(1L, coldRes.syntax().failures().getOrDefault("config:UNRENDERED_HELM_TEMPLATE", 0L),
                    "Unrendered Helm template must remain typed as config:UNRENDERED_HELM_TEMPLATE: " + coldRes.syntax().failures());

            // 2. Warm NO_CHANGE scan -> 100% cache hit, 0 misses, bit-identical records & failures (including UNRENDERED_HELM_TEMPLATE!)
            assertEquals(JobJournal.State.COMPLETE, intake.localComplete(source, "warm-nc", req, () -> false).state());
            var rWarmNc = intake.readiness("warm-nc", ReadinessPlanner.Policy.comprehensive(), () -> false);
            var warmNcRes = scan.run("warm-nc", intake, rWarmNc, EvidenceContext.forSnapshot(sCold.identity()),
                    Optional.empty(), temp.resolve("poly-w-nc"), () -> false, d -> null, false, p -> false, Optional.of(cacheRoot));
            assertEquals(coldTotal, warmNcRes.syntax().cacheHits(),
                    "Warm NO_CHANGE must hit 100% of Cold lookup slots (including sentinels and Helm templates)");
            assertEquals(0L, warmNcRes.syntax().cacheMisses(), "Warm NO_CHANGE must have 0 polyglot misses");
            assertEquals(coldRes.syntax().records().identity(), warmNcRes.syntax().records().identity());
            assertEquals(coldRes.syntax().categories(), warmNcRes.syntax().categories());
            assertEquals(coldRes.syntax().failures(), warmNcRes.syntax().failures());

            // 3. Warm 1-File Edit on ItemController.java -> exact lookup conservation (hits + misses == coldTotal, misses == 2 for JAVA_GRPC + JAVA_SERVICE_SYNTAX)
            writeText(controllerJava, """
                    package demo;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class ItemController {
                        @GetMapping("/api/v1/items")
                        public String list() { return "ok-edited"; }
                        public int probeEditMethod() { return 42; }
                    }
                    """);
            assertEquals(JobJournal.State.COMPLETE, intake.localComplete(source, "warm-edit", req, () -> false).state());
            var rEdit = intake.readiness("warm-edit", ReadinessPlanner.Policy.comprehensive(), () -> false);
            var sEdit = RepositorySnapshot.create(repo, Optional.empty(), true, List.of(), List.of());
            var warmEditRes = scan.run("warm-edit", intake, rEdit, EvidenceContext.forSnapshot(sEdit.identity()),
                    Optional.empty(), temp.resolve("poly-w-edit"), () -> false, d -> null, false, p -> false, Optional.of(cacheRoot));
            var cleanEditRes = scan.run("warm-edit", intake, rEdit, EvidenceContext.forSnapshot(sEdit.identity()),
                    Optional.empty(), temp.resolve("poly-w-clean"), () -> false, d -> null, false, p -> false, Optional.empty());

            assertEquals(coldTotal, warmEditRes.syntax().cacheHits() + warmEditRes.syntax().cacheMisses(),
                    "Total polyglot lookups (hits + misses) on Warm 1-File Edit must exactly equal Cold lookups");
            assertEquals(2L, warmEditRes.syntax().cacheMisses(),
                    "Editing 1 main Java file must miss exactly 2 tasks (JAVA_GRPC + JAVA_SERVICE_SYNTAX)");
            assertEquals(coldTotal - 2L, warmEditRes.syntax().cacheHits());
            assertEquals(cleanEditRes.syntax().records().identity(), warmEditRes.syntax().records().identity());
            assertEquals(cleanEditRes.syntax().categories(), warmEditRes.syntax().categories());
            assertEquals(cleanEditRes.syntax().failures(), warmEditRes.syntax().failures());
        }
    }

    @Test
    void springBootWarmOneFileEditMatchesColdEditedBitIdenticallyAcrossAllEditTypes() throws Exception {
        Path m2 = Files.createDirectory(temp.resolve("spring-m2"));
        seedSpringBootLocalCache(m2);

        Path repoDir = Files.createDirectory(temp.resolve("spring-app"));
        writeText(repoDir.resolve("pom.xml"), springBootPom("demo-spring-app", List.of()));
        writeText(repoDir.resolve("src/main/java/demo/DemoApplication.java"), """
                package demo;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class DemoApplication {
                    public static void main(String[] args) {}
                }
                """);
        writeText(repoDir.resolve("src/main/java/demo/CatalogService.java"), """
                package demo;
                import org.springframework.stereotype.Service;
                @Service
                public class CatalogService {
                    public String label() { return "catalog"; }
                }
                """);
        writeText(repoDir.resolve("src/main/java/demo/AuditConfig.java"), """
                package demo;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                public class AuditConfig {
                    @Bean
                    @ConditionalOnProperty(name = "feature.audit.enabled", havingValue = "true")
                    public String auditTrail() { return "enabled"; }
                }
                """);
        Path controllerFile = repoDir.resolve("src/main/java/demo/CatalogController.java");
        writeText(controllerFile, """
                package demo;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.GetMapping;
                @Controller
                public class CatalogController {
                    private final CatalogService service;
                    public CatalogController(CatalogService service) {
                        this.service = service;
                    }
                    @GetMapping("/catalog")
                    public String showCatalog() {
                        return service.label();
                    }
                }
                """);
        Path appProps = repoDir.resolve("src/main/resources/application.properties");
        writeText(appProps, "spring.application.name=demo-spring-app\nfeature.audit.enabled=false\n");
        writeText(repoDir.resolve("src/main/resources/application-prod.properties"),
                "feature.audit.enabled=true\n");
        writeText(repoDir.resolve("web/ui.ts"),
                "export function fetchCatalog() { return fetch('/catalog'); }\n");

        FileTime past = FileTime.fromMillis(System.currentTimeMillis() - 10_000L);
        setAllMtimes(repoDir, past);

        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:test:spring-warm-1file-determinism");
        var input = new LocalAnalysisRunner.Input(repoDir, repoId, Path.of(System.getProperty("java.home")), 21, m2,
                Optional.empty(), true, false, List.of(), Optional.empty(), LocalAnalysisRunner.SpringInput.autoMultiConfig());
        Path warmIntake = Files.createDirectory(temp.resolve("spring-warm-intake"));
        var warmRunner = new LocalAnalysisRunner(input, warmIntake);
        var warmStore = new InMemoryGraphStore();

        try (var jobs = new AnalysisJobs(List.of(warmRunner.registration("spring-warm")), warmStore,
                new PublishedReports(), new AnalysisJobs.Limits(1, 2, 16, 120_000, 1000))) {

            // 0. Cold A populates all incremental caches (L1 intake, L2 buildDelta, L3 ModuleIncremental + JavaSets + SpringCache + Polyglot-v3, L4 GraphProjector)
            var coldA = runJob(jobs, "spring-warm", "cold-a", false, AnalysisMode.FULL, warmStore);
            long coldPolyTotal = coldA.cacheMetrics().getOrDefault("polyglotCacheMisses", 0L);
            assertEquals(0L, coldA.cacheMetrics().getOrDefault("polyglotCacheHits", 0L));
            assertTrue(coldPolyTotal >= 8L, "Expected polyglot tasks on Cold A, got " + coldA.cacheMetrics());
            assertTrue(hasSpringContext(coldA.graph()), "Spring autoMultiConfig must produce a spring-context");

            // 1. Warm NO_CHANGE (force=true exercises L2/L3/L4 caches without job-level short-circuit)
            var warmNc = runJob(jobs, "spring-warm", "warm-nc", true, AnalysisMode.FULL, warmStore);
            assertGraphsAndContextsBitIdentical("Warm NO_CHANGE vs Cold A", coldA.graph(), warmNc.graph());
            assertEquals(coldPolyTotal, warmNc.cacheMetrics().getOrDefault("polyglotCacheHits", 0L));
            assertEquals(0L, warmNc.cacheMetrics().getOrDefault("polyglotCacheMisses", 0L));
            assertTrue(warmNc.cacheMetrics().getOrDefault("javaCacheHits", 0L) >= 1L, () -> "warmNc=" + warmNc.cacheMetrics());
            assertTrue(warmNc.cacheMetrics().getOrDefault("springCacheReused", 0L) >= 1L, () -> "warmNc=" + warmNc.cacheMetrics());
            assertTrue(warmNc.cacheMetrics().getOrDefault("buildDeltaReused", 0L) >= 1L, () -> "warmNc=" + warmNc.cacheMetrics());

            // 2. Edit 1: Comment + method-body-only edit on CatalogController (shifts lines, preserves ABI -> ABI Early Cutoff!)
            writeText(controllerFile, """
                    package demo;
                    // shifted comment line 1
                    // shifted comment line 2
                    import org.springframework.stereotype.Controller;
                    import org.springframework.web.bind.annotation.GetMapping;
                    @Controller
                    public class CatalogController {
                        private final CatalogService service;
                        public CatalogController(CatalogService service) {
                            this.service = service;
                        }
                        @GetMapping("/catalog")
                        public String showCatalog() {
                            String out = service.label();
                            return out + "-v2";
                        }
                    }
                    """);
            var warmBody = runJob(jobs, "spring-warm", "warm-edit-body", false, AnalysisMode.FULL, warmStore);
            var coldBody = runCleanCold(input, temp.resolve("spring-cold-body"), AnalysisMode.FULL);
            assertGraphsAndContextsBitIdentical("Warm Body/Comment Edit vs Cold-Edited", coldBody, warmBody.graph());
            assertTrue(warmBody.incremental().orElseThrow().abiEarlyCutoffCount() >= 1,
                    "Body/comment edit must trigger genuine ABI early cutoff: " + warmBody.incremental());
            assertTrue(warmBody.incremental().orElseThrow().reusedFiles() >= 6,
                    "Body/comment edit must reuse unchanged repository files: " + warmBody.incremental());
            assertPolyglotSymmetry("Edit 1 (body/comment)", coldPolyTotal, 2L, warmBody.cacheMetrics());

            // 3. Edit 2: Public method + new @GetMapping added on CatalogController (mutates ABI + Spring MVC surface)
            writeText(controllerFile, """
                    package demo;
                    import org.springframework.stereotype.Controller;
                    import org.springframework.web.bind.annotation.GetMapping;
                    @Controller
                    public class CatalogController {
                        private final CatalogService service;
                        public CatalogController(CatalogService service) {
                            this.service = service;
                        }
                        @GetMapping("/catalog")
                        public String showCatalog() {
                            return service.label();
                        }
                        @GetMapping("/catalog/probe")
                        public int probeEndpoint() {
                            return 42;
                        }
                    }
                    """);
            var warmPub = runJob(jobs, "spring-warm", "warm-edit-public", false, AnalysisMode.FULL, warmStore);
            var coldPub = runCleanCold(input, temp.resolve("spring-cold-public"), AnalysisMode.FULL);
            assertGraphsAndContextsBitIdentical("Warm Public+@GetMapping Edit vs Cold-Edited", coldPub, warmPub.graph());
            assertPolyglotSymmetry("Edit 2 (public+@GetMapping)", coldPolyTotal, 2L, warmPub.cacheMetrics());

            // 4. Edit 2 under FAST_ARCHITECTURE mode must also match Cold-Edited bit-identically
            var warmFast = runJob(jobs, "spring-warm", "warm-edit-fast", false, AnalysisMode.FAST_ARCHITECTURE, warmStore);
            var coldFast = runCleanCold(input, temp.resolve("spring-cold-fast"), AnalysisMode.FAST_ARCHITECTURE);
            assertGraphsAndContextsBitIdentical("Warm FAST_ARCHITECTURE Edit vs Cold-Edited", coldFast, warmFast.graph());

            // 5. Edit 3: D2 Spring config edit (application.properties toggles feature.audit.enabled=true)
            String springCtxBeforeConfigEdit = springContextId(warmPub.graph());
            writeText(appProps, "spring.application.name=demo-spring-app\nfeature.audit.enabled=true\n");
            var warmCfg = runJob(jobs, "spring-warm", "warm-edit-config", false, AnalysisMode.FULL, warmStore);
            var coldCfg = runCleanCold(input, temp.resolve("spring-cold-config"), AnalysisMode.FULL);
            assertGraphsAndContextsBitIdentical("Warm application.properties Edit vs Cold-Edited", coldCfg, warmCfg.graph());
            assertNotEquals(springCtxBeforeConfigEdit, springContextId(warmCfg.graph()),
                    "Changing application.properties must update spring-context rather than reusing stale Spring cache");
            assertTrue(warmCfg.cacheMetrics().getOrDefault("javaCacheHits", 0L) >= 1L,
                    "D2 application.properties edit must reuse unchanged Java module cache: " + warmCfg.cacheMetrics());
            assertPolyglotSymmetry("Edit 3 (application.properties)", coldPolyTotal, 1L, warmCfg.cacheMetrics());
        }
    }

    @Test
    void multiModuleSpringWarmOneFileEditReusesUnchangedModuleCachesAndMatchesColdEdited() throws Exception {
        Path m2 = Files.createDirectory(temp.resolve("mm-spring-m2"));
        seedSpringBootLocalCache(m2);

        Path repoDir = Files.createDirectory(temp.resolve("mm-spring-app"));
        writeText(repoDir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>mm-root</artifactId><version>1.0</version><packaging>pom</packaging>
                <modules><module>core-mod</module><module>web-mod</module></modules></project>
                """);
        writeText(repoDir.resolve("core-mod/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <parent><groupId>demo</groupId><artifactId>mm-root</artifactId><version>1.0</version></parent>
                <artifactId>core-mod</artifactId>
                <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                </project>
                """);
        writeText(repoDir.resolve("core-mod/src/main/java/demo/core/DomainModel.java"),
                "package demo.core; public class DomainModel { public String code() { return \"M1\"; } }\n");
        writeText(repoDir.resolve("web-mod/pom.xml"),
                springBootPom("web-mod", List.of("<dependency><groupId>demo</groupId><artifactId>core-mod</artifactId><version>1.0</version></dependency>")));
        writeText(repoDir.resolve("web-mod/src/main/java/demo/web/WebApplication.java"), """
                package demo.web;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class WebApplication {
                    public static void main(String[] args) {}
                }
                """);
        Path webCtrl = repoDir.resolve("web-mod/src/main/java/demo/web/WebController.java");
        writeText(webCtrl, """
                package demo.web;
                import demo.core.DomainModel;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.GetMapping;
                @Controller
                public class WebController {
                    @GetMapping("/model")
                    public String model() { return new DomainModel().code(); }
                }
                """);
        writeText(repoDir.resolve("web-mod/src/main/resources/application.properties"),
                "spring.application.name=web-mod\n");

        FileTime past = FileTime.fromMillis(System.currentTimeMillis() - 10_000L);
        setAllMtimes(repoDir, past);

        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:test:mm-spring-warm-edit");
        var input = new LocalAnalysisRunner.Input(repoDir, repoId, Path.of(System.getProperty("java.home")), 21, m2,
                Optional.empty(), true, false, List.of(), Optional.empty(), LocalAnalysisRunner.SpringInput.autoMultiConfig());
        Path warmIntake = Files.createDirectory(temp.resolve("mm-warm-intake"));
        var warmRunner = new LocalAnalysisRunner(input, warmIntake);
        var warmStore = new InMemoryGraphStore();

        try (var jobs = new AnalysisJobs(List.of(warmRunner.registration("mm-warm")), warmStore,
                new PublishedReports(), new AnalysisJobs.Limits(1, 2, 8, 120_000, 1000))) {
            var coldA = runJob(jobs, "mm-warm", "mm-cold-a", false, AnalysisMode.FULL, warmStore);
            long coldPolyTotal = coldA.cacheMetrics().getOrDefault("polyglotCacheMisses", 0L);
            assertTrue(hasSpringContext(coldA.graph()));

            // Edit 1 file in web-mod only -> core-mod must hit both Java module cache AND GraphProjector .gt2 template!
            writeText(webCtrl, """
                    package demo.web;
                    import demo.core.DomainModel;
                    import org.springframework.stereotype.Controller;
                    import org.springframework.web.bind.annotation.GetMapping;
                    @Controller
                    public class WebController {
                        @GetMapping("/model")
                        public String model() { return new DomainModel().code() + "-edited"; }
                    }
                    """);
            var warmEdit = runJob(jobs, "mm-warm", "mm-warm-edit", false, AnalysisMode.FULL, warmStore);
            var coldEdited = runCleanCold(input, temp.resolve("mm-cold-edited"), AnalysisMode.FULL);

            assertGraphsAndContextsBitIdentical("Multi-Module Warm 1-File Edit vs Cold-Edited", coldEdited, warmEdit.graph());
            assertTrue(warmEdit.cacheMetrics().getOrDefault("javaCacheHits", 0L) >= 1L,
                    "Unchanged core-mod must hit Java source-set cache: " + warmEdit.cacheMetrics());
            assertTrue(warmEdit.cacheMetrics().getOrDefault("graphProjectorReused", 0L) >= 1L,
                    "Unchanged core-mod must reuse IncrementalGraphProjector template: " + warmEdit.cacheMetrics());
            assertPolyglotSymmetry("Multi-Module 1-File Edit", coldPolyTotal, 2L, warmEdit.cacheMetrics());
        }
    }

    @Test
    void fastArchitectureWarmOneFileEditMatchesColdEditedWithoutFieldNamePollution() throws Exception {
        Path m2 = Files.createDirectory(temp.resolve("fast-arch-m2"));
        seedSpringBootLocalCache(m2);

        Path repoDir = Files.createDirectory(temp.resolve("fast-arch-app"));
        writeText(repoDir.resolve("pom.xml"), springBootPom("fast-arch-app", List.of()));
        writeText(repoDir.resolve("src/main/java/demo/FastApplication.java"), """
                package demo;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class FastApplication {
                    public static void main(String[] args) {}
                }
                """);
        // Clean domain model with annotated fields named `displayName` and `tagCode` (added to SourceAbiFingerprinter.declaredSimpleNames).
        writeText(repoDir.resolve("src/main/java/demo/DomainEntity.java"), """
                package demo;
                import org.springframework.stereotype.Component;
                @Component
                public class DomainEntity {
                    @Deprecated
                    private String displayName;
                    @Deprecated
                    private String tagCode;
                    public String getDisplayName() { return displayName; }
                    public String getTagCode() { return tagCode; }
                }
                """);
        // Same-package validator calling local String methods on variables matching clean field names (`displayName`, `tagCode`).
        writeText(repoDir.resolve("src/main/java/demo/DomainValidator.java"), """
                package demo;
                public class DomainValidator {
                    public boolean isValid(DomainEntity entity) {
                        String displayName = entity.getDisplayName();
                        String tagCode = entity.getTagCode();
                        if (displayName == null || displayName.length() > 30) {
                            return false;
                        }
                        return tagCode != null && !tagCode.strip().isEmpty();
                    }
                }
                """);
        Path controllerFile = repoDir.resolve("src/main/java/demo/DomainController.java");
        writeText(controllerFile, """
                package demo;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.GetMapping;
                @Controller
                public class DomainController {
                    private final DomainEntity entity;
                    public DomainController(DomainEntity entity) {
                        this.entity = entity;
                    }
                    @GetMapping("/domain")
                    public String inspect() {
                        String displayName = entity.getDisplayName();
                        if (displayName != null) {
                            displayName = displayName.strip();
                        }
                        return displayName;
                    }
                }
                """);
        writeText(repoDir.resolve("src/main/resources/application.properties"),
                "spring.application.name=fast-arch-app\n");

        FileTime past = FileTime.fromMillis(System.currentTimeMillis() - 10_000L);
        setAllMtimes(repoDir, past);

        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:test:fast-arch-warm-1file-determinism");
        var input = new LocalAnalysisRunner.Input(repoDir, repoId, Path.of(System.getProperty("java.home")), 21, m2,
                Optional.empty(), true, false, List.of(), Optional.empty(), LocalAnalysisRunner.SpringInput.autoMultiConfig());
        Path warmIntake = Files.createDirectory(temp.resolve("fast-arch-warm-intake"));
        var warmRunner = new LocalAnalysisRunner(input, warmIntake);
        var warmStore = new InMemoryGraphStore();

        try (var jobs = new AnalysisJobs(List.of(warmRunner.registration("fast-warm")), warmStore,
                new PublishedReports(), new AnalysisJobs.Limits(1, 2, 8, 120_000, 1000))) {
            // Cold A under FAST_ARCHITECTURE populates ModuleIncrementalCache with DomainEntity's declaredSimpleNames.
            var coldA = runJob(jobs, "fast-warm", "fast-cold-a", false, AnalysisMode.FAST_ARCHITECTURE, warmStore);
            long coldPolyTotal = coldA.cacheMetrics().getOrDefault("polyglotCacheMisses", 0L);
            assertTrue(hasSpringContext(coldA.graph()), "FAST_ARCHITECTURE must produce a spring-context");

            // 1. Public method edit on DomainController (triggers incremental re-extraction of dirty units in FAST_ARCHITECTURE).
            writeText(controllerFile, """
                    package demo;
                    import org.springframework.stereotype.Controller;
                    import org.springframework.web.bind.annotation.GetMapping;
                    @Controller
                    public class DomainController {
                        private final DomainEntity entity;
                        public DomainController(DomainEntity entity) {
                            this.entity = entity;
                        }
                        @GetMapping("/domain")
                        public String inspect() {
                            String displayName = entity.getDisplayName();
                            if (displayName != null) {
                                displayName = displayName.strip();
                            }
                            return displayName;
                        }
                        public int probeFastMethod() {
                            return 42;
                        }
                    }
                    """);
            var warmPub = runJob(jobs, "fast-warm", "fast-warm-pub", false, AnalysisMode.FAST_ARCHITECTURE, warmStore);
            var coldPub = runCleanCold(input, temp.resolve("fast-cold-pub"), AnalysisMode.FAST_ARCHITECTURE);
            assertGraphsAndContextsBitIdentical("FAST_ARCHITECTURE Warm Public Edit vs Cold-Edited", coldPub, warmPub.graph());
            assertPolyglotSymmetry("FAST_ARCHITECTURE Public Edit", coldPolyTotal, 2L, warmPub.cacheMetrics());

            // 2. Body-only/comment edit on DomainController under FAST_ARCHITECTURE (ABI Early Cutoff + zero field-name pollution!).
            writeText(controllerFile, """
                    package demo;
                    // shifted comment under FAST_ARCHITECTURE
                    import org.springframework.stereotype.Controller;
                    import org.springframework.web.bind.annotation.GetMapping;
                    @Controller
                    public class DomainController {
                        private final DomainEntity entity;
                        public DomainController(DomainEntity entity) {
                            this.entity = entity;
                        }
                        @GetMapping("/domain")
                        public String inspect() {
                            String displayName = entity.getDisplayName();
                            if (displayName != null) {
                                displayName = displayName.strip();
                            }
                            return displayName == null ? "" : displayName;
                        }
                        public int probeFastMethod() {
                            return 42;
                        }
                    }
                    """);
            var warmBody = runJob(jobs, "fast-warm", "fast-warm-body", false, AnalysisMode.FAST_ARCHITECTURE, warmStore);
            var coldBody = runCleanCold(input, temp.resolve("fast-cold-body"), AnalysisMode.FAST_ARCHITECTURE);
            assertGraphsAndContextsBitIdentical("FAST_ARCHITECTURE Warm Body Edit vs Cold-Edited", coldBody, warmBody.graph());
            assertTrue(warmBody.incremental().orElseThrow().abiEarlyCutoffCount() >= 1,
                    "FAST_ARCHITECTURE body edit must still trigger ABI early cutoff: " + warmBody.incremental());
            assertPolyglotSymmetry("FAST_ARCHITECTURE Body Edit", coldPolyTotal, 2L, warmBody.cacheMetrics());
        }
    }

    private record JobRun(
            KnowledgeGraph graph,
            Map<String, Long> cacheMetrics,
            Optional<AnalysisJobs.IncrementalMetrics> incremental) {}

    private static JobRun runJob(
            AnalysisJobs jobs, String setupId, String key, boolean force, AnalysisMode mode, GraphStore store)
            throws Exception {
        var sub = jobs.submit(setupId, key, force, mode);
        UUID uuid = UUID.fromString(sub.job().id());
        long deadline = System.nanoTime() + 90_000_000_000L;
        AnalysisJobs.View view = null;
        while (System.nanoTime() < deadline) {
            view = jobs.get(uuid);
            if (AnalysisJobs.terminal(view.state())) break;
            Thread.sleep(25);
        }
        assertNotNull(view);
        assertTrue(AnalysisJobs.terminal(view.state()), "Job did not finish: " + view.state());
        var graphId = view.result().orElseThrow().graph().orElseThrow();
        var telemetry = jobs.telemetry(uuid);
        return new JobRun(store.load(graphId).orElseThrow(), telemetry.cacheMetrics(), telemetry.incremental());
    }

    private static KnowledgeGraph runCleanCold(LocalAnalysisRunner.Input input, Path coldIntake, AnalysisMode mode)
            throws Exception {
        Files.createDirectories(coldIntake);
        var coldRunner = new LocalAnalysisRunner(input, coldIntake);
        var coldStore = new InMemoryGraphStore();
        System.setProperty("evo.incremental.disabled", "true");
        try (var jobs = new AnalysisJobs(List.of(coldRunner.registration("cold-clean")), coldStore,
                new PublishedReports(), new AnalysisJobs.Limits(1, 2, 4, 120_000, 1000))) {
            return runJob(jobs, "cold-clean", "clean-" + UUID.randomUUID(), true, mode, coldStore).graph();
        } finally {
            System.clearProperty("evo.incremental.disabled");
        }
    }

    private static void assertGraphsAndContextsBitIdentical(String label, KnowledgeGraph expectedCold, KnowledgeGraph actualWarm) {
        assertEquals(expectedCold.identity(), actualWarm.identity(),
                label + ": whole-graph SHA-256 must match bit-for-bit (expectedContexts="
                        + summarizeContexts(expectedCold) + ", actualContexts=" + summarizeContexts(actualWarm) + ")");
        assertEquals(expectedCold.contexts(), actualWarm.contexts(), label + ": contexts must match");
        assertEquals(expectedCold.nodes().size(), actualWarm.nodes().size(), label + ": total nodes must match");
        assertEquals(expectedCold.edges().size(), actualWarm.edges().size(), label + ": total edges must match");
        for (var ctx : expectedCold.contexts()) {
            assertEquals(expectedCold.nodes(ctx.identity()).size(), actualWarm.nodes(ctx.identity()).size(),
                    label + ": node count mismatch in context " + ctx.identity());
            assertEquals(expectedCold.edges(ctx.identity()).size(), actualWarm.edges(ctx.identity()).size(),
                    label + ": edge count mismatch in context " + ctx.identity());
        }
    }

    private static void assertPolyglotSymmetry(String label, long coldTotal, long expectedMisses, Map<String, Long> metrics) {
        long hits = metrics.getOrDefault("polyglotCacheHits", 0L);
        long misses = metrics.getOrDefault("polyglotCacheMisses", 0L);
        assertEquals(coldTotal, hits + misses,
                label + ": polyglot (hits + misses) must equal Cold total lookups; metrics=" + metrics);
        assertEquals(expectedMisses, misses,
                label + ": unexpected polyglot miss count; metrics=" + metrics);
    }

    private static boolean hasSpringContext(KnowledgeGraph graph) {
        return graph.contexts().stream().anyMatch(c ->
                c.identity().startsWith("spring-context:") || c.identity().startsWith("spring-semantics-context:"));
    }

    private static String springContextId(KnowledgeGraph graph) {
        return graph.contexts().stream()
                .map(KnowledgeGraph.Context::identity)
                .filter(id -> id.startsWith("spring-context:") || id.startsWith("spring-semantics-context:"))
                .findFirst().orElseThrow();
    }

    private static String summarizeContexts(KnowledgeGraph graph) {
        var sb = new StringBuilder("[");
        for (var c : graph.contexts()) {
            sb.append(c.identity()).append("(n=").append(graph.nodes(c.identity()).size())
                    .append(",e=").append(graph.edges(c.identity()).size()).append("), ");
        }
        return sb.append("]").toString();
    }

    private static String springBootPom(String artifactId, List<String> extraDependencies) {
        String extras = String.join("\n", extraDependencies);
        return """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>%s</artifactId><version>1.0</version>
                <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies>
                  <dependency><groupId>org.springframework</groupId><artifactId>spring-core</artifactId><version>6.2.0</version></dependency>
                  <dependency><groupId>org.springframework</groupId><artifactId>spring-beans</artifactId><version>6.2.0</version></dependency>
                  <dependency><groupId>org.springframework</groupId><artifactId>spring-context</artifactId><version>6.2.0</version></dependency>
                  <dependency><groupId>org.springframework</groupId><artifactId>spring-web</artifactId><version>6.2.0</version></dependency>
                  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot</artifactId><version>3.4.0</version></dependency>
                  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-autoconfigure</artifactId><version>3.4.0</version></dependency>
                  %s
                </dependencies></project>
                """.formatted(artifactId, extras);
    }

    private static void seedSpringBootLocalCache(Path m2Cache) throws Exception {
        copyClasspathJar(m2Cache, "org.springframework", "spring-core", "6.2.0",
                org.springframework.core.SpringVersion.class);
        copyClasspathJar(m2Cache, "org.springframework", "spring-beans", "6.2.0",
                org.springframework.beans.factory.BeanFactory.class);
        copyClasspathJar(m2Cache, "org.springframework", "spring-context", "6.2.0",
                org.springframework.context.ApplicationContext.class);
        copyClasspathJar(m2Cache, "org.springframework", "spring-web", "6.2.0",
                org.springframework.web.bind.annotation.GetMapping.class);
        copyClasspathJar(m2Cache, "org.springframework.boot", "spring-boot", "3.4.0",
                org.springframework.boot.SpringApplication.class);
        copyClasspathJar(m2Cache, "org.springframework.boot", "spring-boot-autoconfigure", "3.4.0",
                org.springframework.boot.autoconfigure.SpringBootApplication.class);
    }

    private static void copyClasspathJar(Path m2Cache, String groupId, String artifactId, String version, Class<?> anchor)
            throws Exception {
        Path sourceJar = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        byte[] jarBytes = Files.readAllBytes(sourceJar);
        var coord = new MavenCoordinate(groupId, artifactId, version);
        writeCacheFile(m2Cache, new ArtifactCoordinate(coord, "jar", ""), jarBytes);
        String pom = """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></project>
                """.formatted(groupId, artifactId, version);
        writeCacheFile(m2Cache, new ArtifactCoordinate(coord, "pom", ""), pom.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeText(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void setAllMtimes(Path root, FileTime time) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Files.setLastModifiedTime(p, time);
            }
        }
    }

    /**
     * Simulates MavenDependencyArtifactResolver's real multi-pass fixpoint using the real
     * MavenLocalClasspathProvider over a real temp .m2 directory, populating cache on remote hits.
     */
    private static MavenDependencyArtifactResolution simulateMultiPassMaven(
            Path cache,
            ClasspathResolutionRequest request,
            DependencyAcquisitionPolicy policy,
            Map<ArtifactCoordinate, byte[]> remoteRepo,
            boolean injectColdRetries) {
        var classpathProvider = new MavenLocalClasspathProvider(cache, true);
        ExactClasspathResult classpath = classpathProvider.resolve(request);
        int passes = 1;
        int requested = 0;
        long consumed = 0;
        var attempted = new HashSet<ArtifactCoordinate>();
        var acquisitions = new ArrayList<DependencyAcquisitionResult>();
        var problems = new ArrayList<MavenDependencyArtifactResolution.Problem>();

        while (passes <= policy.maxPasses()) {
            List<ArtifactCoordinate> missing = classpath.attempts().stream()
                    .filter(a -> a.origin() == ExactClasspathResult.ArtifactOrigin.LOCAL_CACHE
                            && a.outcome() == ExactClasspathResult.AttemptOutcome.UNAVAILABLE
                            && (a.kind() == ExactClasspathResult.ArtifactKind.POM
                                    || a.kind() == ExactClasspathResult.ArtifactKind.JAR))
                    .map(ExactClasspathResult.Attempt::coordinate)
                    .distinct().sorted()
                    .filter(c -> !attempted.contains(c))
                    .toList();
            if (missing.isEmpty()) break;

            var roundPolicy = policy.withLimits(policy.maxCoordinates() - requested, policy.maxTotalBytes() - consumed);
            var acqReq = DependencyAcquisitionRequest.of(missing, roundPolicy);
            var outcomes = new ArrayList<DependencyAcquisitionResult.Outcome>();
            var attempts = new ArrayList<DependencyAcquisitionResult.Attempt>();
            long roundBytes = 0;
            int ordinal = 0;
            boolean progressed = false;

            for (var reqItem : acqReq.requirements()) {
                var coord = reqItem.coordinate();
                attempts.add(new DependencyAcquisitionResult.Attempt(ordinal++, coord,
                        DependencyAcquisitionResult.Origin.LOCAL_CACHE, coord.repositoryPath(),
                        DependencyAcquisitionResult.AttemptOutcome.UNAVAILABLE, Optional.empty()));
                byte[] bytes = remoteRepo.get(coord);
                if (bytes != null) {
                    if (injectColdRetries) {
                        // Simulate mirror-a 404 + mirror-b transient 503 retry before 200 OK.
                        attempts.add(new DependencyAcquisitionResult.Attempt(ordinal++, coord,
                                DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                                REPO_A.resolve(coord.repositoryPath()).toASCIIString(),
                                DependencyAcquisitionResult.AttemptOutcome.UNAVAILABLE, Optional.empty()));
                        attempts.add(new DependencyAcquisitionResult.Attempt(ordinal++, coord,
                                DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                                REPO_B.resolve(coord.repositoryPath()).toASCIIString(),
                                DependencyAcquisitionResult.AttemptOutcome.FAILED, Optional.empty()));
                    }
                    try {
                        writeCacheFile(cache, coord, bytes);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    var digest = ContentDigest.sha256(bytes);
                    attempts.add(new DependencyAcquisitionResult.Attempt(ordinal++, coord,
                            DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                            REPO_B.resolve(coord.repositoryPath()).toASCIIString(),
                            DependencyAcquisitionResult.AttemptOutcome.SUCCEEDED, Optional.of(digest)));
                    outcomes.add(new DependencyAcquisitionResult.Outcome(reqItem,
                            coord.extension().equals("pom")
                                    ? DependencyAcquisitionResult.Status.SKIPPED_POM
                                    : DependencyAcquisitionResult.Status.ACQUIRED,
                            Optional.of(new DependencyAcquisitionResult.Artifact(coord,
                                    DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                                    coord.repositoryPath(), digest, bytes.length)),
                            Optional.empty()));
                    roundBytes += bytes.length;
                    progressed = true;
                } else {
                    attempts.add(new DependencyAcquisitionResult.Attempt(ordinal++, coord,
                            DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                            REPO_A.resolve(coord.repositoryPath()).toASCIIString(),
                            DependencyAcquisitionResult.AttemptOutcome.UNAVAILABLE, Optional.empty()));
                    attempts.add(new DependencyAcquisitionResult.Attempt(ordinal++, coord,
                            DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY,
                            REPO_B.resolve(coord.repositoryPath()).toASCIIString(),
                            DependencyAcquisitionResult.AttemptOutcome.UNAVAILABLE, Optional.empty()));
                    outcomes.add(new DependencyAcquisitionResult.Outcome(reqItem,
                            DependencyAcquisitionResult.Status.FAILED, Optional.empty(),
                            Optional.of(DependencyAcquisitionResult.FailureReason.ARTIFACT_NOT_FOUND)));
                    problems.add(new MavenDependencyArtifactResolution.Problem(
                            MavenDependencyArtifactResolution.ProblemReason.ACQUISITION_FAILED,
                            coord.notation() + ":ARTIFACT_NOT_FOUND"));
                }
            }
            var acqRes = DependencyAcquisitionResult.create(acqReq, MavenDependencyArtifactProvider.VERSION,
                    outcomes, attempts, roundBytes);
            acquisitions.add(acqRes);
            attempted.addAll(missing);
            requested += missing.size();
            consumed += roundBytes;
            if (!progressed) break;
            classpath = classpathProvider.resolve(request);
            passes++;
        }

        List<MavenDependencyArtifactResolution.Problem> sortedProblems = problems.stream().distinct()
                .sorted(Comparator.comparing(CanonicalJson::write)).toList();
        ContentDigest id = ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", MavenDependencyArtifactResolution.SCHEMA,
                "request", request.identity(),
                "policy", policy.identity(),
                "provider", MavenDependencyArtifactResolver.VERSION,
                "classpath", classpath.identity(),
                "acquisitions", acquisitions.stream().map(DependencyAcquisitionResult::identity).toList(),
                "problems", sortedProblems,
                "classpathPasses", passes)));
        return new MavenDependencyArtifactResolution(id, MavenDependencyArtifactResolution.SCHEMA,
                request.identity(), policy.identity(), MavenDependencyArtifactResolver.VERSION,
                classpath, acquisitions, sortedProblems, passes);
    }

    private static UniversalArchitecturePipeline.DetailedResult runPipeline(
            RepositoryAcquisitionResult acquisition, PlatformInput platform, MavenScan.Inputs inputs) {
        var policy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .defaultPlatform(platform)
                .resolutions(inputs.resolutions())
                .mavenProvider(new MavenBuildModelProvider())
                .externalPoms(inputs.externalPoms())
                .mavenPolicy(MavenScan.POLICY)
                .truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE)
                .build();
        return new UniversalArchitecturePipeline().runWithEvidence(acquisition, policy);
    }

    private static KnowledgeGraph buildGraph(
            RepositoryAcquisitionResult acquisition, PlatformInput platform, MavenScan.Inputs inputs) {
        var detailed = runPipeline(acquisition, platform, inputs);
        var captured = detailed.graphInput().orElseThrow();
        var gaps = new TreeSet<CapabilityGapRecord>(detailed.report().gaps());
        gaps.addAll(inputs.gaps());
        gaps.addAll(captured.gaps());
        var canonicalInput = new CanonicalGraphInput(
                captured.repository(), captured.build(), captured.ingestion(), captured.spring(),
                captured.issues(), List.copyOf(gaps), captured.acquisition(), captured.javaEvidence());
        return new CanonicalGraphBuilder().build(canonicalInput, inputs.ledgers());
    }

    private static PartitionedGraphStore.Publication buildPartitioned(
            RepositoryAcquisitionResult acquisition, PlatformInput platform, MavenScan.Inputs inputs,
            Path pagesDir, Path snapDir) throws IOException {
        var detailed = runPipeline(acquisition, platform, inputs);
        var captured = detailed.graphInput().orElseThrow();
        var gaps = new TreeSet<CapabilityGapRecord>(detailed.report().gaps());
        gaps.addAll(inputs.gaps());
        gaps.addAll(captured.gaps());
        var canonicalInput = new CanonicalGraphInput(
                captured.repository(), captured.build(), captured.ingestion(), captured.spring(),
                captured.issues(), List.copyOf(gaps), captured.acquisition(), captured.javaEvidence());
        return new PartitionedGraphStore(pagesDir).build(canonicalInput, snapDir, () -> false, inputs.ledgers(), s -> {});
    }

    private static long countKind(KnowledgeGraph graph, String kind) {
        return graph.nodes().stream().filter(n -> n.kind().equals(kind)).count();
    }

    private static RepositoryAcquisitionResult acquireSource(Path source, String repoUrn) {
        return new FilesystemRepositoryAcquirer().acquire(source, new RepositoryAcquisitionRequest(
                RepositoryIdentity.fromCanonicalCoordinate(repoUrn), Optional.empty(), false, "pom.xml",
                new RepositoryAcquisitionPolicy(100, 100, 1_000_000, 10_000_000, 32, List.of())));
    }

    private static PlatformInput acquireJdk21() {
        return new FilesystemJdkPlatformProvider()
                .acquire(Path.of(System.getProperty("java.home")),
                        new PlatformSymbolRequest(21, 128, 128_000_000, 512_000_000))
                .platform().orElseThrow();
    }

    private byte[] compileJar(String relJavaPath, String sourceCode) throws IOException {
        Path work = Files.createTempDirectory(temp, "compile-");
        Path src = work.resolve("src").resolve(relJavaPath);
        Path classes = Files.createDirectory(work.resolve("classes"));
        Files.createDirectories(src.getParent());
        Files.writeString(src, sourceCode);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-proc:none", "-d", classes.toString(), src.toString()));
        var out = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(out); var walk = Files.walk(classes)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                jar.write(Files.readAllBytes(p));
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static void writeCacheFile(Path cache, ArtifactCoordinate coord, byte[] bytes) throws IOException {
        Path target = cache.resolve(coord.repositoryPath().replace('/', java.io.File.separatorChar));
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }
}
