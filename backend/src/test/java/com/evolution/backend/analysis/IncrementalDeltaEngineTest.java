package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.filesystem.FilesystemJdkPlatformProvider;
import com.evolution.analysis.filesystem.FilesystemRepositoryAcquirer;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.input.PlatformSymbolRequest;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import com.evolution.analysis.maven.MavenBuildModelProvider;
import com.evolution.analysis.maven.MavenBuildModelResolution;
import com.evolution.analysis.maven.MavenBuildModelResolver;
import java.net.URI;
import com.evolution.analysis.readiness.ReadinessPlan;
import com.evolution.analysis.readiness.ReadinessPlanner;
import com.evolution.analysis.spring.truth.TruthRegionEvaluation;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalDeltaEngineTest {
    @TempDir Path temp;

    @Test
    void shardedPolyglotV3CachesParsedFilesAndNegativeProbesAndMatchesCleanDigest() throws Exception {
        Path source = Files.createDirectory(temp.resolve("poly-src"));
        Files.writeString(source.resolve("client.ts"), "export function ping() { return fetch('/api/v1/items'); }\n");
        Files.writeString(source.resolve("worker.py"), "import requests\ndef run():\n    return requests.get('/api/v1/items')\n");
        Files.writeString(source.resolve("Plain.java"), "public class Plain { public int add(int a, int b) { return a + b; } }\n");
        Files.writeString(source.resolve("GrpcSrv.java"),
                "class GrpcSrv extends shop.CartServiceGrpc.CartServiceImplBase { @Override public void getCart(Req r, StreamObserver<Reply> o) {} }\n");
        Files.writeString(source.resolve("config.json"), "{\"name\":\"demo\",\"version\":\"1.0.0\"}\n");
        Files.writeString(source.resolve("spec.json"),
                "{\"openapi\":\"3.0.3\",\"paths\":{\"/api/v1/items\":{\"get\":{\"operationId\":\"listItems\"}}}}\n");

        Path cacheRoot = Files.createDirectory(temp.resolve("poly-cache"));
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:poly-v3-delta");
        var req = new StreamingAcquisitionRequest(repo, Optional.empty(), true, List.of());

        try (var intake = new RepositoryIntake(temp.resolve("poly-intake"))) {
            assertEquals(JobJournal.State.COMPLETE, intake.localComplete(source, "r1", req, () -> false).state());
            var readiness1 = intake.readiness("r1", ReadinessPlanner.Policy.comprehensive(), () -> false);
            var snap1 = RepositorySnapshot.create(repo, Optional.empty(), true, List.of(), List.of());
            var scan = new PolyglotScan();
            var res1 = scan.run("r1", intake, readiness1, EvidenceContext.forSnapshot(snap1.identity()),
                    Optional.empty(), temp.resolve("work-1"), () -> false, digest -> null, false, p -> false, Optional.of(cacheRoot));
            assertTrue(res1.syntax().cacheMisses() >= 4L);

            // Verify sharded v3 .pg3 files exist on disk (no monolithic polyglot-syntax.bin.gz).
            assertTrue(Files.isDirectory(cacheRoot.resolve("polyglot-v3")));
            try (var shards = Files.walk(cacheRoot.resolve("polyglot-v3"))) {
                assertTrue(shards.filter(p -> p.getFileName().toString().endsWith(".pg3")).count() >= 5L);
            }

            // Edit 1 file (worker.py); all other files (.ts, Plain.java sentinel, GrpcSrv.java, config.json sentinel, spec.json) must hit v3 cache!
            Files.writeString(source.resolve("worker.py"), "import requests\ndef run():\n    return requests.post('/api/v1/items')\n");
            assertEquals(JobJournal.State.COMPLETE, intake.localComplete(source, "r2", req, () -> false).state());
            var readiness2 = intake.readiness("r2", ReadinessPlanner.Policy.comprehensive(), () -> false);
            var snap2 = RepositorySnapshot.create(repo, Optional.empty(), true, List.of(), List.of());

            var incRes2 = scan.run("r2", intake, readiness2, EvidenceContext.forSnapshot(snap2.identity()),
                    Optional.empty(), temp.resolve("work-2-inc"), () -> false, digest -> null, false, p -> false, Optional.of(cacheRoot));
            var cleanRes2 = scan.run("r2", intake, readiness2, EvidenceContext.forSnapshot(snap2.identity()),
                    Optional.empty(), temp.resolve("work-2-clean"), () -> false, digest -> null, false, p -> false, Optional.empty());

            assertTrue(incRes2.syntax().cacheHits() >= 5L, "Expected cache hits for unchanged TS + Java gRPC + OpenAPI + negative probes, got " + incRes2.syntax().cacheHits());
            assertEquals(cleanRes2.syntax().records().identity(), incRes2.syntax().records().identity());
            assertEquals(cleanRes2.syntax().categories(), incRes2.syntax().categories());
            assertEquals(cleanRes2.syntax().failures(), incRes2.syntax().failures());
        }
    }

    @Test
    void repositoryIntakeReadinessIncrementalFastRebindMatchesCleanPlanBitForBit() throws Exception {
        Path source = Files.createDirectory(temp.resolve("intake-src"));
        Files.createDirectories(source.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>d</groupId><artifactId>demo</artifactId><version>1.0</version></project>");
        Path appJava = source.resolve("src/main/java/demo/App.java");
        Files.writeString(appJava, "package demo; public class App { int x() { return 1; } }");
        FileTime past = FileTime.fromMillis(System.currentTimeMillis() - 10_000L);
        Files.setLastModifiedTime(source.resolve("pom.xml"), past);
        Files.setLastModifiedTime(appJava, past);

        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:intake-readiness-inc");
        var req = new StreamingAcquisitionRequest(repo, Optional.empty(), true, List.of());
        var policy = ReadinessPlanner.Policy.comprehensive();

        ReadinessPlan incPlan2;
        try (var intake = new RepositoryIntake(temp.resolve("intake-dir"))) {
            assertEquals(JobJournal.State.COMPLETE, intake.localComplete(source, "rev1", req, () -> false).state());
            intake.readiness("rev1", policy, () -> false);

            // Modify App.java body only -> L1 incremental + L2 readiness fast rebind!
            Files.writeString(appJava, "package demo; public class App { int x() { return 77; } }");
            var inc = intake.localCompleteIncremental(source, "rev2", Optional.empty(), req, false, () -> false);
            assertTrue(inc.incrementalUsed());
            incPlan2 = intake.readinessIncremental("rev2", inc.delta(), policy, () -> false, false);
        }

        // Compare against completely clean cold intake + readiness
        ReadinessPlan cleanPlan2;
        try (var cleanIntake = new RepositoryIntake(temp.resolve("clean-intake-dir"))) {
            assertEquals(JobJournal.State.COMPLETE, cleanIntake.localComplete(source, "clean2", req, () -> false).state());
            cleanPlan2 = cleanIntake.readiness("clean2", policy, () -> false);
        }
        assertEquals(cleanPlan2.identity(), incPlan2.identity());
    }

    @Test
    void buildDeltaCacheReusesMavenModelsAndGradleResolutionAcrossJavaEditsWithExactParity() throws Exception {
        Path m2 = Files.createDirectory(temp.resolve("m2"));
        Path deltaRoot = Files.createDirectory(temp.resolve("delta-cache"));
        var deltaCache = new BuildDeltaCache(deltaRoot);

        // 1. Maven exact-snapshot reuse
        Path mvnRepo = Files.createDirectory(temp.resolve("mvn-repo"));
        Files.createDirectories(mvnRepo.resolve("src/main/java/m"));
        Files.writeString(mvnRepo.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>m</artifactId><version>1.0</version></project>");
        Files.writeString(mvnRepo.resolve("src/main/java/m/M.java"), "package m; class M {}");
        var mvnReq = new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:mvn-delta"),
                Optional.empty(), true, "pom.xml", new RepositoryAcquisitionPolicy(100, 100, 1_000_000, 10_000_000, 16, List.of()));
        var mvnAcq = new FilesystemRepositoryAcquirer().acquire(mvnRepo, mvnReq);
        var m1 = MavenScan.models(mvnAcq, m2, List.of(), deltaCache);
        var m2Models = MavenScan.models(mvnAcq, m2, List.of(), deltaCache);
        assertEquals(m1, m2Models);
        assertTrue(deltaCache.stats().reusedBuildRoots() >= 1);
        var jdk21 = new FilesystemJdkPlatformProvider()
                .acquire(Path.of(System.getProperty("java.home")), new PlatformSymbolRequest(21, 128, 128_000_000, 512_000_000))
                .platform();
        assertTrue(jdk21.isPresent());
        var mvnDepPolicy = new DependencyAcquisitionPolicy(64, 10_000_000, 50_000_000, 8, List.of(), 1000, 1000, 1, 2);
        var mi1 = MavenScan.inputs(mvnAcq, m1, m2, jdk21, mvnDepPolicy, Map.of(21, jdk21.orElseThrow()), deltaCache);
        var mi2 = MavenScan.inputs(mvnAcq, m1, m2, jdk21, mvnDepPolicy, Map.of(21, jdk21.orElseThrow()), deltaCache);
        var miClean = MavenScan.inputs(mvnAcq, m1, m2, jdk21, mvnDepPolicy, Map.of(21, jdk21.orElseThrow()), null);
        assertEquals(miClean.resolutions(), mi1.resolutions());
        assertEquals(miClean.resolutions(), mi2.resolutions());
        assertEquals(miClean.gaps(), mi2.gaps());

        // 2. Gradle cross-snapshot D3-unchanged reuse across a .java edit!
        Path grRepo = Files.createDirectory(temp.resolve("gradle-repo"));
        Files.createDirectories(grRepo.resolve("src/main/java/g"));
        Files.writeString(grRepo.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
        Files.writeString(grRepo.resolve("build.gradle"), "plugins { id 'java' }\n");
        Files.writeString(grRepo.resolve("src/main/java/g/G.java"), "package g; class G { int a = 1; }");
        var grReq = new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:gradle-delta"),
                Optional.empty(), true, "pom.xml", new RepositoryAcquisitionPolicy(100, 100, 1_000_000, 10_000_000, 16, List.of()));
        var depPolicy = new DependencyAcquisitionPolicy(64, 10_000_000, 50_000_000, 8, List.of(), 1000, 1000, 1, 2);
        var decodePolicy = new RepositoryInputDecoder.Policy("UTF-8", 1000, 1_000_000);
        var buildPolicy = new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21), Optional.of("UTF-8"), 2048, 1_000_000, 200_000, 64);

        var grAcq1 = new FilesystemRepositoryAcquirer().acquire(grRepo, grReq);
        var grModel1 = GradleScan.model(grAcq1, buildPolicy, decodePolicy, Map.of());
        GradleScan.augment(new MavenScan.Inputs(Map.of(), Map.of(), List.of(), Map.of()),
                grAcq1, grModel1, m2, Optional.empty(), depPolicy, Map.of(), Optional.empty(), deltaCache);

        // Edit Java file only (D3 build.gradle/settings.gradle unchanged!)
        Files.writeString(grRepo.resolve("src/main/java/g/G.java"), "package g; class G { int a = 2; }");
        var grAcq2 = new FilesystemRepositoryAcquirer().acquire(grRepo, grReq);
        var grModel2 = GradleScan.model(grAcq2, buildPolicy, decodePolicy, Map.of());
        var incAug2 = GradleScan.augment(new MavenScan.Inputs(Map.of(), Map.of(), List.of(), Map.of()),
                grAcq2, grModel2, m2, Optional.empty(), depPolicy, Map.of(), Optional.empty(), deltaCache);
        var cleanAug2 = GradleScan.augment(new MavenScan.Inputs(Map.of(), Map.of(), List.of(), Map.of()),
                grAcq2, grModel2, m2, Optional.empty(), depPolicy, Map.of(), Optional.empty(), null);

        assertTrue(deltaCache.stats().gradleResolverReused() >= 1);
        assertEquals(cleanAug2.resolutions(), incAug2.resolutions());
        assertEquals(cleanAug2.gaps(), incAug2.gaps());
        assertEquals(CanonicalJson.write(cleanAug2.summary()), CanonicalJson.write(incAug2.summary()));
    }

    @Test
    void incrementalSpringCacheAndApplicationYmlDeltaKeepZeroJavaReparseAndExactGraphParity() throws Exception {
        Path springRepo = Files.createDirectory(temp.resolve("spring-repo"));
        Files.createDirectories(springRepo.resolve("src/main/java/demo"));
        Files.createDirectories(springRepo.resolve("src/main/resources"));
        Files.writeString(springRepo.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>d</groupId><artifactId>sp</artifactId><version>1.0</version>"
                        + "<properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        Files.writeString(springRepo.resolve("src/main/java/demo/App.java"),
                "package demo;\n@org.springframework.stereotype.Service\npublic class App { public int ok() { return 1; } }\n");
        Files.writeString(springRepo.resolve("src/main/resources/application.yml"), "app:\n  mode: alpha\n");

        var req = new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:spring-inc"),
                Optional.empty(), true, "pom.xml", new RepositoryAcquisitionPolicy(100, 100, 1_000_000, 10_000_000, 16, List.of()));
        var springCache = new IncrementalSpringCache();
        var javaCache = new com.evolution.analysis.filesystem.IncrementalJavaFrontendCache(temp.resolve("java-cache"));
        var rawFrontend = new JavaParserFrontend();
        var javaInvocations = new java.util.concurrent.atomic.AtomicInteger();
        com.evolution.analysis.frontend.SemanticFrontend cachedFrontend = fr -> {
            var hit = javaCache.get(fr, JavaParserFrontend.PROVIDER);
            if (hit.isPresent()) return hit.orElseThrow();
            javaInvocations.incrementAndGet();
            var computed = rawFrontend.analyze(fr);
            javaCache.put(fr, computed);
            return computed;
        };

        var buildPolicy = new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21), Optional.of("UTF-8"), 2048, 1_000_000, 200_000, 64);
        var decodePolicy = new RepositoryInputDecoder.Policy("UTF-8", 100, 1_000_000);
        var platform = new FilesystemJdkPlatformProvider()
                .acquire(Path.of(System.getProperty("java.home")), new PlatformSymbolRequest(21, 128, 128_000_000, 512_000_000))
                .platform();
        var policy = new UniversalArchitecturePipeline.Policy(
                buildPolicy, decodePolicy, cachedFrontend, JavaParserFrontend.PROVIDER,
                Map.of(), platform, Optional.of(new MavenBuildModelProvider()), Map.of(), MavenScan.POLICY,
                1000, 100, UniversalArchitecturePipeline.TruthEvaluationMode.AUTO,
                Map.of(), Optional.empty(), Optional.empty(), TruthRegionEvaluation.Limits.conservative());
        var pipeline = new UniversalArchitecturePipeline(AnalysisWorkspace.MEMORY, 1, springCache);

        var acq1 = new FilesystemRepositoryAcquirer().acquire(springRepo, req);
        var run1 = pipeline.runWithEvidence(acq1, policy);
        assertEquals(1, javaInvocations.get());
        // Exact repeat over same snapshot hits both Java cache AND Spring SAT cache!
        var run1Repeat = pipeline.runWithEvidence(acq1, policy);
        assertEquals(1, javaInvocations.get());
        assertEquals(
                new com.evolution.analysis.graph.CanonicalGraphBuilder().build(run1.graphInput().orElseThrow()).identity(),
                new com.evolution.analysis.graph.CanonicalGraphBuilder().build(run1Repeat.graphInput().orElseThrow()).identity());

        // Case C: edit application.yml ONLY -> 0 Java re-parses, exact match with clean cold run!
        Files.writeString(springRepo.resolve("src/main/resources/application.yml"), "app:\n  mode: beta\n");
        var acq2 = new FilesystemRepositoryAcquirer().acquire(springRepo, req);
        var incRun2 = pipeline.runWithEvidence(acq2, policy);
        assertEquals(1, javaInvocations.get(), "application.yml edit must NOT re-parse unchanged Java module!");

        var cleanPolicy = new UniversalArchitecturePipeline.Policy(
                buildPolicy, decodePolicy, rawFrontend, JavaParserFrontend.PROVIDER,
                Map.of(), platform, Optional.of(new MavenBuildModelProvider()), Map.of(), MavenScan.POLICY,
                1000, 100, UniversalArchitecturePipeline.TruthEvaluationMode.AUTO,
                Map.of(), Optional.empty(), Optional.empty(), TruthRegionEvaluation.Limits.conservative());
        var cleanRun2 = new UniversalArchitecturePipeline().runWithEvidence(acq2, cleanPolicy);
        assertEquals(
                new com.evolution.analysis.graph.CanonicalGraphBuilder().build(cleanRun2.graphInput().orElseThrow()).identity(),
                new com.evolution.analysis.graph.CanonicalGraphBuilder().build(incRun2.graphInput().orElseThrow()).identity());
    }

    @Test
    void repeatFullAnalysisAcrossClosedFileAnalysisWorkspacesReusesSpringCacheWithoutSpoolClosedCrash() throws Exception {
        Path springRepo = Files.createDirectory(temp.resolve("spring-spool-repo"));
        Files.createDirectories(springRepo.resolve("src/main/java/demo"));
        Files.createDirectories(springRepo.resolve("src/main/java/org/springframework/stereotype"));
        Files.createDirectories(springRepo.resolve("src/main/java/org/springframework/boot/autoconfigure"));
        Files.createDirectories(springRepo.resolve("src/main/resources"));
        Files.writeString(springRepo.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>d</groupId><artifactId>sp-spool</artifactId><version>1.0</version>"
                        + "<properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        Files.writeString(springRepo.resolve("src/main/java/org/springframework/stereotype/Component.java"),
                "package org.springframework.stereotype;\npublic @interface Component { String value() default \"\"; }\n");
        Files.writeString(springRepo.resolve("src/main/java/org/springframework/stereotype/Service.java"),
                "package org.springframework.stereotype;\n@Component\npublic @interface Service { String value() default \"\"; }\n");
        Files.writeString(springRepo.resolve("src/main/java/org/springframework/boot/autoconfigure/SpringBootApplication.java"),
                "package org.springframework.boot.autoconfigure;\npublic @interface SpringBootApplication {}\n");
        Files.writeString(springRepo.resolve("src/main/java/demo/App.java"),
                "package demo;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}\n"
                        + "@org.springframework.stereotype.Service\nclass Store { public int ok() { return 1; } }\n");
        Files.writeString(springRepo.resolve("src/main/resources/application.yml"), "app:\n  mode: alpha\n");

        var req = new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:spring-spool"),
                Optional.empty(), true, "pom.xml", new RepositoryAcquisitionPolicy(100, 100, 1_000_000, 10_000_000, 16, List.of()));
        var springCache = new IncrementalSpringCache();
        var rawFrontend = new JavaParserFrontend();
        var buildPolicy = new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21), Optional.of("UTF-8"), 2048, 1_000_000, 200_000, 64);
        var decodePolicy = new RepositoryInputDecoder.Policy("UTF-8", 100, 1_000_000);
        var platform = new FilesystemJdkPlatformProvider()
                .acquire(Path.of(System.getProperty("java.home")), new PlatformSymbolRequest(21, 128, 128_000_000, 512_000_000))
                .platform();
        var policy = new UniversalArchitecturePipeline.Policy(
                buildPolicy, decodePolicy, rawFrontend, JavaParserFrontend.PROVIDER,
                Map.of(), platform, Optional.of(new MavenBuildModelProvider()), Map.of(), MavenScan.POLICY,
                1000, 100, UniversalArchitecturePipeline.TruthEvaluationMode.AUTO,
                Map.of(), Optional.empty(), Optional.empty(), TruthRegionEvaluation.Limits.conservative())
                .withSpringSatMode(UniversalArchitecturePipeline.SpringSatMode.AUTO_MULTI_CONFIG_SAT);

        var acq = new FilesystemRepositoryAcquirer().acquire(springRepo, req);

        com.evolution.analysis.contract.common.ContentDigest run1GraphDigest;
        int run1SpringSlices;
        try (var ws1 = new com.evolution.analysis.filesystem.FileAnalysisWorkspace(temp.resolve("ws-run1"))) {
            var pipe1 = new UniversalArchitecturePipeline(ws1, 1, springCache);
            var run1 = pipe1.runWithEvidence(acq, policy);
            assertTrue(run1.report().issues().stream()
                    .noneMatch(i -> i.reason() == IngestionEvidence.Reason.SEMANTIC_PROVIDER_FAILED),
                    "Run 1 must not have SEMANTIC_PROVIDER_FAILED: " + run1.report().issues());
            var input1 = run1.graphInput().orElseThrow();
            run1SpringSlices = input1.spring().size();
            assertTrue(run1SpringSlices >= 1, "Run 1 must produce at least one Spring slice");
            run1GraphDigest = new com.evolution.analysis.graph.CanonicalGraphBuilder().build(input1).identity();
        }
        // ws1 is now CLOSED! Before P1-4 fix, Run 2 hit springCache.getExact(...) and threw ANALYSIS_SPOOL_CLOSED,
        // silently dropping spring-context slices and drifting graph identity.
        com.evolution.analysis.contract.common.ContentDigest run2GraphDigest;
        int run2SpringSlices;
        try (var ws2 = new com.evolution.analysis.filesystem.FileAnalysisWorkspace(temp.resolve("ws-run2"))) {
            var pipe2 = new UniversalArchitecturePipeline(ws2, 1, springCache);
            var run2 = pipe2.runWithEvidence(acq, policy);
            assertTrue(run2.report().issues().stream()
                    .noneMatch(i -> i.reason() == IngestionEvidence.Reason.SEMANTIC_PROVIDER_FAILED),
                    "Run 2 must not fail with ANALYSIS_SPOOL_CLOSED / SEMANTIC_PROVIDER_FAILED: " + run2.report().issues());
            var input2 = run2.graphInput().orElseThrow();
            run2SpringSlices = input2.spring().size();
            run2GraphDigest = new com.evolution.analysis.graph.CanonicalGraphBuilder().build(input2).identity();
        }

        assertTrue(springCache.stats().reusedContexts() >= 1,
                "Run 2 must reuse IncrementalSpringCache entry across closed FileAnalysisWorkspace");
        assertEquals(run1SpringSlices, run2SpringSlices, "Run 2 must retain identical Spring slice count");
        assertEquals(run1GraphDigest, run2GraphDigest, "Run 2 must produce bit-for-bit identical canonical graph digest");
    }

    @Test
    void incrementalSummaryContractSerializesDeterministically() {
        var cold = IncrementalSummary.cold("NO_PRIOR_SNAPSHOT");
        assertEquals(IncrementalSummary.MODE_COLD, cold.mode());
        assertEquals(26, cold.toMap().size());
        assertFalse(CanonicalJson.write(cold.toMap()).isEmpty());

        var inc = new IncrementalSummary(
                IncrementalSummary.MODE_INCREMENTAL, "",
                Optional.of("snap:1"), Optional.of("sha256:aaa"), Optional.of("job-1"),
                1, 2, 1, 100, 100,
                3, 1,
                2, 4, 3, 97,
                4, 1,
                4, 1,
                80, 2,
                4, 1,
                12_500L, true);
        String json = CanonicalJson.write(inc.toMap());
        assertTrue(json.contains("\"mode\":\"INCREMENTAL\""));
        assertTrue(json.contains("\"abiEarlyCutoffCount\":2"));
        assertTrue(json.contains("\"timeSavedEstimated\":true"));
    }

    @Test
    void buildDeltaCacheReusesColdPromotedRemotePomsAndDeterministicMissingProblemsAndInvalidatesOnChange() throws Exception {
        Path m2 = Files.createDirectory(temp.resolve("m2-remote-reuse"));
        Path deltaRoot = Files.createDirectory(temp.resolve("delta-remote-reuse"));
        var deltaCache = new BuildDeltaCache(deltaRoot);

        Path mvnRepo = Files.createDirectory(temp.resolve("mvn-remote-repo"));
        Files.createDirectories(mvnRepo.resolve("src/main/java/m"));
        Files.writeString(mvnRepo.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>rem</artifactId><version>1.0</version></project>");
        Files.writeString(mvnRepo.resolve("src/main/java/m/R.java"), "package m; class R {}");

        var req = new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:mvn-remote-delta"),
                Optional.empty(), true, "pom.xml", new RepositoryAcquisitionPolicy(100, 100, 1_000_000, 10_000_000, 16, List.of()));
        var acq = new FilesystemRepositoryAcquirer().acquire(mvnRepo, req);
        var baseModels = MavenScan.models(acq, m2, List.of(), null);
        assertEquals(1, baseModels.size());
        var base = baseModels.getFirst();

        // Simulate a Cold-downloaded REMOTE_REPOSITORY parent POM promoted into local m2 cache,
        // plus a deterministic MISSING_POM and REMOTE_SNAPSHOT_UNSUPPORTED problem.
        var parentCoord = new MavenCoordinate("org.example", "cold-parent", "1.0.0");
        var parentArt = new com.evolution.analysis.classpath.ArtifactCoordinate(parentCoord, "pom", "");
        byte[] parentBytes = "<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId><artifactId>cold-parent</artifactId><version>1.0.0</version><packaging>pom</packaging></project>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path promotedPom = m2.resolve(parentArt.repositoryPath().replace('/', java.io.File.separatorChar));
        Files.createDirectories(promotedPom.getParent());
        Files.write(promotedPom, parentBytes);

        var missingRel = "org/example/absent-bom/2.0.0/absent-bom-2.0.0.pom";
        var repos = List.of(URI.create("https://repo.example.test/maven2/"));
        var policy = MavenScan.pomPolicy(repos);
        var coldResolution = MavenBuildModelResolution.create(
                base.initialRequestIdentity(),
                policy,
                MavenBuildModelResolver.VERSION,
                base.request(),
                base.model(),
                List.of(new MavenBuildModelResolution.AcquiredPom(
                        parentCoord,
                        MavenBuildModelResolution.Origin.REMOTE_REPOSITORY,
                        "https://repo.example.test/maven2/" + parentArt.repositoryPath(),
                        ContentDigest.sha256(parentBytes),
                        parentBytes.length)),
                List.of(),
                List.of(
                        new MavenBuildModelResolution.Problem(MavenBuildModelResolution.ProblemReason.MISSING_POM, missingRel),
                        new MavenBuildModelResolution.Problem(
                                MavenBuildModelResolution.ProblemReason.REMOTE_SNAPSHOT_UNSUPPORTED,
                                "org.example:snap-parent:1.0-SNAPSHOT")),
                2);

        deltaCache.forRun().putMavenModels(acq, m2, repos, List.of(coldResolution));

        // 1. Warm lookup (fresh per-run view, matching LocalAnalysisRunner) must REUSE the Cold-promoted
        //    REMOTE_REPOSITORY POM + deterministic missing problems!
        var hit = deltaCache.forRun().getMavenModels(acq, m2, repos);
        assertTrue(hit.isPresent(), "BuildDeltaCache must reuse Cold-promoted REMOTE_REPOSITORY POMs and deterministic missing-POM problems");
        assertEquals(coldResolution.identity(), hit.orElseThrow().getFirst().identity());
        assertEquals(1, deltaCache.stats().reusedBuildRoots());

        // 2. Mutating the promoted POM on disk must immediately invalidate the cache entry on the next run!
        Files.writeString(promotedPom, "<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId><artifactId>cold-parent</artifactId><version>1.0.0</version></project>");
        assertTrue(deltaCache.forRun().getMavenModels(acq, m2, repos).isEmpty(),
                "Mutating a promoted remote POM on disk must invalidate BuildDeltaCache immediately");

        // 3. Re-put with fresh bytes, then materialize the previously-missing POM on disk -> must invalidate!
        Files.write(promotedPom, parentBytes);
        deltaCache.forRun().putMavenModels(acq, m2, repos, List.of(coldResolution));
        assertTrue(deltaCache.forRun().getMavenModels(acq, m2, repos).isPresent());
        Path nowPresentMissing = m2.resolve(missingRel.replace('/', java.io.File.separatorChar));
        Files.createDirectories(nowPresentMissing.getParent());
        Files.writeString(nowPresentMissing, "<project/>");
        assertTrue(deltaCache.forRun().getMavenModels(acq, m2, repos).isEmpty(),
                "Appearing previously-missing POM in m2 cache must invalidate BuildDeltaCache immediately");
    }
}
