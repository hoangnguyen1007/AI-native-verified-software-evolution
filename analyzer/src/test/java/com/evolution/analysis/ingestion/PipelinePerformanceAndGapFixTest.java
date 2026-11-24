package com.evolution.analysis.ingestion;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.CanonicalGraphBuilder;
import com.evolution.analysis.polyglot.*;
import com.evolution.analysis.spring.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PipelinePerformanceAndGapFixTest {
    private static final VersionedIdentifier PROVIDER = new VersionedIdentifier("frontend.test", "1");
    private static final Derivation DIRECT = new Derivation(DerivationKind.DIRECT, PROVIDER, List.of());

    @Test
    void capabilityGapNormalizerSkipsResolvedObservationHashingAndIndexesAttempts() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/gap-perf.git");
        var module = ModuleDescriptor.create(repo, "app", "app");
        var doc = SourceDocument.create(repo, module, "app/src/main/java/p/A.java", ContentDigest.sha256Utf8("class A {}"), SourceClassification.MAIN);
        var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false, List.of(SnapshotFile.from(doc)), List.of(doc));
        var comp = new ManifestComponent(PROVIDER, ContentDigest.sha256Utf8("c"));
        var manifest = AnalysisManifest.create(PROVIDER, snapshot, List.of(module), List.of(),
                AnalysisConfiguration.create(PROVIDER, Map.of()), comp, comp, comp);
        var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module.identity()), EntityKind.METHOD,
                "java:v1:[\"method\",[\"type\",[\"p\"],\"A\"],\"f\",[]]", Optional.of(new SourceSpan(doc.identity(), 1, 1, 1, 5)));
        var occurrences = new ArrayList<RelationshipOccurrence>();
        var observations = new ArrayList<ObservationRecord>();
        for (int i = 0; i < 50; i++) {
            var span = new SourceSpan(doc.identity(), i + 1, 1, i + 1, 5);
            var occ = RelationshipOccurrence.create(
                    SemanticRelationship.create(entity.identity(), new RelationshipKind("java.calls"), new RelationshipTarget.Resolved(entity.identity())),
                    span, 0, SemanticStatus.RESOLVED, DIRECT, List.of(), List.of());
            occurrences.add(occ);
            observations.add(new ObservationRecord(doc.identity(), new RelationshipKind("java.calls"), Optional.of(span),
                    SemanticStatus.RESOLVED, ObservationRecord.EvidenceState.VERIFIED, ObservationRecord.EvidenceState.VERIFIED,
                    Optional.of(occ.identity()), "f()", List.of()));
        }
        var unresolvedDiag = new Diagnostic(DiagnosticSeverity.WARNING, "java.unresolved", "Unresolved", Optional.empty(), Map.of());
        observations.add(new ObservationRecord(doc.identity(), new RelationshipKind("java.calls"),
                Optional.of(new SourceSpan(doc.identity(), 100, 1, 100, 5)),
                SemanticStatus.UNRESOLVED, ObservationRecord.EvidenceState.MISSING, ObservationRecord.EvidenceState.VERIFIED,
                Optional.empty(), "missing()", List.of(unresolvedDiag)));
        var coverage = FrontendRequest.CATEGORIES.stream()
                .map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED,
                        c.equals("calls") ? 51 : 0, c.equals("calls") ? 50 : 0, c.equals("calls") ? 1 : 0))
                .toList();
        var frontend = new FrontendResult(manifest.identity(), PROVIDER, FrontendResult.State.PARTIAL,
                List.of(new DeclarationRecord(entity, "f", SemanticStatus.RESOLVED, DIRECT, List.of(), List.of())),
                occurrences, observations, List.of(new SourceOutcome(doc.identity(), SourceOutcome.State.PROCESSED, List.of())),
                coverage, List.of(unresolvedDiag));

        var ledger = CapabilityGapNormalizer.normalize(EvidenceContext.forAnalysis(manifest),
                EvidenceNormalizationInput.builder().frontendResults(List.of(frontend)).build());
        assertEquals(1, ledger.gaps().stream().filter(g -> g.reasonCode().equals("SEMANTIC_UNRESOLVED")).count());
        assertEquals(2, ledger.gaps().size(), "Expected only FRONTEND_PARTIAL and single SEMANTIC_UNRESOLVED gap");

        // Verify attempt pre-indexing still links matching attempts accurately.
        var pomEv = new BuildModelResult.PomEvidence("workspace:pom.xml", ContentDigest.sha256Utf8("pom"));
        var buildProblem = new BuildModelResult.Problem(BuildModelResult.Reason.MISSING_PARENT_POM,
                "parent:g:a:1", BuildModelResult.Requirement.BUILD_MODEL, List.of(pomEv));
        var buildAttempt = new BuildModelResult.Attempt("pom.xml", BuildModelResult.AttemptKind.PARENT,
                "parent:g:a:1", BuildModelResult.Outcome.UNAVAILABLE, Optional.empty());
        var buildResult = new BuildModelResult(BuildModelResult.SCHEMA, ContentDigest.sha256Utf8("req"),
                new VersionedIdentifier("build.test", "1"), List.of(), List.of(buildProblem), List.of(buildAttempt), List.of());
        var buildLedger = CapabilityGapNormalizer.normalize(EvidenceContext.forSnapshot(snapshot.identity()),
                EvidenceNormalizationInput.builder().buildModels(List.of(buildResult)).build());
        assertEquals(List.of(buildLedger.attempts().getFirst().attemptIdentity()),
                buildLedger.gaps().getFirst().acquisitionAttemptReferences());
    }

    @Test
    void universalIngestionPipelineDecrementsAdmittedAsEachFutureFinishes() {
        var inputs = IngestionFixtures.inputs(Map.of(
                "m1/build.gradle", "plugins { java }\n",
                "m1/src/main/java/m1/A.java", "package m1; public class A {}",
                "m2/build.gradle", "plugins { java }\n",
                "m2/src/main/java/m2/B.java", "package m2; public class B {}",
                "m3/build.gradle", "plugins { java }\n",
                "m3/src/main/java/m3/C.java", "package m3; public class C {}"));
        var policy = new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21),
                Optional.of("UTF-8"), 100, 100_000, 10_000, 16);
        var build = new UniversalBuildIngestion().ingest(inputs, policy, Optional.empty(), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16));
        var platform = PlatformInput.create(21, "21", "test", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("jb"), Path.of("jb.jmod"), PlatformInput.Format.JMOD)));
        var resolutions = new HashMap<UniversalSourceIngestion.SourceSet, UniversalSourceIngestion.Resolution>();
        for (var m : build.modules()) {
            resolutions.put(new UniversalSourceIngestion.SourceSet(m.descriptor().identity(), SourcePlanModel.Kind.MAIN),
                    new UniversalSourceIngestion.Resolution(platform, List.of(), ContentDigest.sha256Utf8("cp:" + m.descriptor().path())));
        }
        var comp = new ManifestComponent(PROVIDER, ContentDigest.sha256Utf8("c"));
        var assembled = new UniversalSourceIngestion().assemble(inputs, build, resolutions, comp, comp, comp);

        // Unit 0 blocks until Unit 2 starts; Unit 1 completes immediately.
        // With budget = 2 * unitCost + 1, Unit 2 can ONLY be admitted while Unit 0 is still running
        // if admitted was decremented when Unit 1 completed (not waiting for active.isEmpty()).
        var thirdStarted = new CountDownLatch(1);
        var startedCount = new AtomicInteger();
        var unit2AdmittedWhileUnit0Running = new java.util.concurrent.atomic.AtomicBoolean(false);
        ConcurrentSemanticFrontend concurrentFrontend = new ConcurrentSemanticFrontend() {
            @Override
            public long estimatedWorkingBytes(FrontendRequest request) {
                return 1_000L;
            }

            @Override
            public FrontendResult analyze(FrontendRequest request) {
                int order = startedCount.getAndIncrement();
                if (order == 0) {
                    try {
                        unit2AdmittedWhileUnit0Running.set(thirdStarted.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else if (order == 2) {
                    thirdStarted.countDown();
                }
                var coverage = FrontendRequest.CATEGORIES.stream()
                        .map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED, 0, 0, 0))
                        .toList();
                var outcomes = request.sources().stream()
                        .map(s -> new SourceOutcome(s.document().identity(), SourceOutcome.State.PROCESSED, List.of()))
                        .toList();
                return new FrontendResult(request.manifest().identity(), PROVIDER, FrontendResult.State.COMPLETED,
                        List.of(), List.of(), List.of(), outcomes, coverage, List.of());
            }
        };

        var result = new UniversalIngestionPipeline().run(assembled, concurrentFrontend, PROVIDER, 100, 100, 2, 2_500L);
        assertTrue(unit2AdmittedWhileUnit0Running.get(),
                "Third unit must be admitted after second finishes while first is still active");
        assertEquals(3, result.units().stream().filter(u -> u.status() == UniversalIngestionPipeline.Status.ANALYZED).count());
    }

    @Test
    void universalSourceIngestionWithholdsCompleteReactorSubstitutionWhenSiblingHasPluginOrGeneratedGaps() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/reactor-plugin-gaps.git");
        var modLib = ModuleDescriptor.create(repo, "lib", "lib");
        var modApp = ModuleDescriptor.create(repo, "app", "app");
        byte[] libBytes = "package lib; public class Shared {}".getBytes(StandardCharsets.UTF_8);
        byte[] appBytes = "package app; public class Main { lib.Shared s; }".getBytes(StandardCharsets.UTF_8);
        var libDoc = SourceDocument.create(repo, modLib, "lib/src/main/java/lib/Shared.java", ContentDigest.sha256(libBytes), SourceClassification.MAIN);
        var appDoc = SourceDocument.create(repo, modApp, "app/src/main/java/app/Main.java", ContentDigest.sha256(appBytes), SourceClassification.MAIN);
        var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false,
                List.of(SnapshotFile.from(libDoc), SnapshotFile.from(appDoc)), List.of(libDoc, appDoc));
        var inputs = new RepositoryInputs(snapshot, Map.of(
                libDoc.path(), new SourceInput(libDoc, libBytes),
                appDoc.path(), new SourceInput(appDoc, appBytes)));

        var buildLib = buildModule(modLib, List.of(SourcePlanModel.Gap.PLUGIN_EFFECTS_NOT_EVALUATED, SourcePlanModel.Gap.GENERATED_SOURCES_NOT_ACQUIRED));
        var buildApp = buildModule(modApp, List.of());
        var build = new UniversalBuildModel(ContentDigest.sha256Utf8("b"), inputs.identity(),
                List.of(buildLib, buildApp), List.of(), List.of(), List.of());

        var platform = PlatformInput.create(21, "21", "test", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("jb"), Path.of("jb.jmod"), PlatformInput.Format.JMOD)));
        var libSet = new UniversalSourceIngestion.SourceSet(modLib.identity(), SourcePlanModel.Kind.MAIN);
        var appSet = new UniversalSourceIngestion.SourceSet(modApp.identity(), SourcePlanModel.Kind.MAIN);
        var appCompleteRes = new UniversalSourceIngestion.Resolution(platform, List.of(), ContentDigest.sha256Utf8("cp"),
                Optional.empty(), true, List.of(new UniversalSourceIngestion.ReactorSlot(libSet, 0)));
        var appRecoveryRes = new UniversalSourceIngestion.Resolution(platform, List.of(), ContentDigest.sha256Utf8("cp-rec"),
                Optional.empty(), false, List.of(new UniversalSourceIngestion.ReactorSlot(libSet, 0)));
        var libRes = new UniversalSourceIngestion.Resolution(platform, List.of(), ContentDigest.sha256Utf8("cp-lib"));
        var comp = new ManifestComponent(PROVIDER, ContentDigest.sha256Utf8("c"));

        // Strict/complete resolution must withhold unverified sibling substitution when sibling has plugin/generated gaps.
        var strictResult = new UniversalSourceIngestion().assemble(inputs, build, Map.of(libSet, libRes, appSet, appCompleteRes), comp, comp, comp);
        var strictAppReq = strictResult.outcomes().stream().filter(o -> o.sourceSet().equals(appSet)).findFirst().orElseThrow().request().orElseThrow();
        assertTrue(strictAppReq.sourceRecovery(), "Incomplete generated/plugin sibling must keep attribution withheld");
        assertTrue(strictAppReq.reactorSources().isEmpty(), "Strict resolution must not substitute unclosed sibling");

        // Incomplete/recovery resolution preserves handwritten sibling source under explicit SOURCE_RECOVERY.
        var recoveryResult = new UniversalSourceIngestion().assemble(inputs, build, Map.of(libSet, libRes, appSet, appRecoveryRes), comp, comp, comp);
        var recoveryAppReq = recoveryResult.outcomes().stream().filter(o -> o.sourceSet().equals(appSet)).findFirst().orElseThrow().request().orElseThrow();
        assertTrue(recoveryAppReq.sourceRecovery());
        assertEquals(1, recoveryAppReq.reactorSources().size());
        assertEquals(modLib.identity(), recoveryAppReq.reactorSources().getFirst().module());
    }

    @Test
    void universalArchitecturePipelineSkipsFalseMavenClasspathGapsForReactorPomOptionalAndInactiveTest() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/maven-false-gaps.git");
        var modLib = ModuleDescriptor.create(repo, "lib", "lib");
        var modApp = ModuleDescriptor.create(repo, "app", "app");
        byte[] libBytes = "package lib; public class Shared {}".getBytes(StandardCharsets.UTF_8);
        byte[] appBytes = "package app; public class Main {}".getBytes(StandardCharsets.UTF_8);
        var libDoc = SourceDocument.create(repo, modLib, "lib/src/main/java/lib/Shared.java", ContentDigest.sha256(libBytes), SourceClassification.MAIN);
        var appDoc = SourceDocument.create(repo, modApp, "app/src/main/java/app/Main.java", ContentDigest.sha256(appBytes), SourceClassification.MAIN);
        var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false,
                List.of(SnapshotFile.from(libDoc), SnapshotFile.from(appDoc)), List.of(libDoc, appDoc));
        var inputs = new RepositoryInputs(snapshot, Map.of(
                libDoc.path(), new SourceInput(libDoc, libBytes),
                appDoc.path(), new SourceInput(appDoc, appBytes)));

        var libBuildMod = buildModule(modLib, List.of(), Optional.of(new MavenCoordinate("demo", "lib", "1.0.0")));
        var appBuildMod = buildModule(modApp, List.of(), Optional.of(new MavenCoordinate("demo", "app", "1.0.0")));
        var pomEv = new BuildModelResult.PomEvidence("workspace:app/pom.xml", ContentDigest.sha256Utf8("app-pom"));
        var deps = List.of(
                new BuildModelResult.Dependency("demo", "lib", "1.0.0", "jar", "", "compile", false, List.of()),
                new BuildModelResult.Dependency("org.springframework.boot", "spring-boot-dependencies", "3.4.0", "pom", "", "import", false, List.of()),
                new BuildModelResult.Dependency("org.projectlombok", "lombok", "1.18.36", "jar", "", "provided", true, List.of()),
                new BuildModelResult.Dependency("org.junit.jupiter", "junit-jupiter", "5.11.0", "jar", "", "test", false, List.of()),
                new BuildModelResult.Dependency("com.missing", "real-missing-lib", "1.0.0", "jar", "", "compile", false, List.of()));
        var libPom = new BuildModelResult.EffectivePom(new MavenCoordinate("demo", "lib", "1.0.0"), "jar",
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(pomEv), libBuildMod.sourcePlan());
        var appPom = new BuildModelResult.EffectivePom(new MavenCoordinate("demo", "app", "1.0.0"), "jar",
                List.of(), deps, List.of(), Map.of(), List.of(), List.of(pomEv), appBuildMod.sourcePlan());
        var mavenResult = new BuildModelResult(BuildModelResult.SCHEMA, ContentDigest.sha256Utf8("mreq"),
                new VersionedIdentifier("build.maven", "1"),
                List.of(
                        new BuildModelResult.ModuleModel(modLib, "lib/pom.xml", Optional.empty(), Optional.of(libPom)),
                        new BuildModelResult.ModuleModel(modApp, "app/pom.xml", Optional.empty(), Optional.of(appPom))),
                List.of(), List.of(), List.of());
        var precomputedBuild = new UniversalBuildModel(ContentDigest.sha256Utf8("ub"), inputs.identity(),
                List.of(appBuildMod, libBuildMod), List.of(mavenResult), List.of(), List.of());

        SemanticFrontend stubFrontend = request -> {
            var coverage = FrontendRequest.CATEGORIES.stream()
                    .map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED, 0, 0, 0))
                    .toList();
            var outcomes = request.sources().stream()
                    .map(s -> new SourceOutcome(s.document().identity(), SourceOutcome.State.PROCESSED, List.of()))
                    .toList();
            return new FrontendResult(request.manifest().identity(), PROVIDER, FrontendResult.State.COMPLETED,
                    List.of(), List.of(), List.of(), outcomes, coverage, List.of());
        };
        var policy = UniversalArchitecturePipeline.Policy.builder(stubFrontend, PROVIDER)
                .defaultPlatformRelease(21)
                .precomputedBuild(precomputedBuild)
                .truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE)
                .build();

        var report = new UniversalArchitecturePipeline().run(inputs, policy);
        var classpathSubjects = report.issues().stream()
                .filter(i -> i.reason() == IngestionEvidence.Reason.CLASSPATH_RESOLUTION_REQUIRED)
                .map(IngestionEvidence.Issue::subject)
                .toList();
        assertTrue(classpathSubjects.stream().anyMatch(s -> s.contains("com.missing:real-missing-lib:1.0.0")),
                "Genuine missing compile JAR must still emit CLASSPATH_RESOLUTION_REQUIRED: " + classpathSubjects);
        assertTrue(classpathSubjects.stream().noneMatch(s -> s.contains("demo:lib:1.0.0")),
                "Reactor sibling module must not emit false CLASSPATH_RESOLUTION_REQUIRED: " + classpathSubjects);
        assertTrue(classpathSubjects.stream().noneMatch(s -> s.contains("spring-boot-dependencies")),
                "type=pom dependency must not emit false CLASSPATH_RESOLUTION_REQUIRED: " + classpathSubjects);
        assertTrue(classpathSubjects.stream().noneMatch(s -> s.contains("lombok")),
                "optional=true dependency must not emit false CLASSPATH_RESOLUTION_REQUIRED: " + classpathSubjects);
        assertTrue(classpathSubjects.stream().noneMatch(s -> s.contains("junit-jupiter")),
                "Inactive test-scoped dependency must not emit false CLASSPATH_RESOLUTION_REQUIRED: " + classpathSubjects);
    }

    @Test
    void polyglotInventoryVerifiesAndReadsPrefixInSinglePassAndDetectsCorruption() throws Exception {
        var backing = new HashMap<ContentDigest, byte[]>();
        var openCalls = new AtomicInteger();
        var verifyCalls = new AtomicInteger();
        var rangeCalls = new AtomicInteger();
        RepositoryContentStore spyStore = new RepositoryContentStore() {
            @Override public ContentHandle put(InputStream input, BooleanSupplier cancelled) throws IOException {
                byte[] bytes = input.readAllBytes();
                var h = new ContentHandle(ContentDigest.sha256(bytes), bytes.length);
                backing.put(h.digest(), bytes);
                return h;
            }
            @Override public InputStream open(ContentHandle handle) throws IOException {
                openCalls.incrementAndGet();
                byte[] bytes = backing.get(handle.digest());
                if (bytes == null) throw new IOException("missing");
                return new ByteArrayInputStream(bytes);
            }
            @Override public InputStream range(ContentHandle handle, long offset, long length) throws IOException {
                rangeCalls.incrementAndGet();
                byte[] bytes = backing.get(handle.digest());
                if (bytes == null) throw new IOException("missing");
                return new ByteArrayInputStream(Arrays.copyOfRange(bytes, Math.toIntExact(offset), Math.toIntExact(offset + length)));
            }
            @Override public boolean verify(ContentHandle handle) {
                verifyCalls.incrementAndGet();
                byte[] bytes = backing.get(handle.digest());
                return bytes != null && bytes.length == handle.size() && ContentDigest.sha256(bytes).equals(handle.digest());
            }
        };

        byte[] pyBytes = ("#!/usr/bin/env python3\n" + "# pad\n".repeat(2000) + "def run(): return 1\n").getBytes(StandardCharsets.UTF_8);
        var pyHandle = spyStore.put(new ByteArrayInputStream(pyBytes), () -> false);
        var corruptHandle = new ContentHandle(ContentDigest.sha256Utf8("expected-content"), 12);
        backing.put(corruptHandle.digest(), "tampered-val".getBytes(StandardCharsets.UTF_8));

        var entries = new ArrayList<>(List.of(
                new InventoryEntry(LogicalPath.utf8("scripts/run"), InventoryEntry.Kind.FILE, "100755",
                        InventoryEntry.Population.SOURCE, InventoryEntry.Disposition.ACQUIRED, Optional.of(pyHandle),
                        Optional.empty(), Optional.empty(), List.of(), Optional.empty()),
                new InventoryEntry(LogicalPath.utf8("scripts/corrupt.py"), InventoryEntry.Kind.FILE, "100644",
                        InventoryEntry.Population.SOURCE, InventoryEntry.Disposition.ACQUIRED, Optional.of(corruptHandle),
                        Optional.empty(), Optional.empty(), List.of(), Optional.empty())));
        entries.sort(Comparator.comparing(InventoryEntry::path));
        var manifest = new InventoryManifest(InventoryManifest.SCHEMA,
                RepositoryIdentity.fromCanonicalCoordinate("urn:test:single-pass"),
                ContentDigest.sha256Utf8("sel"), ContentDigest.sha256Utf8("inv"), 2, 2, 0, 0, Optional.empty(), Optional.empty());
        InventoryReader reader = new InventoryReader() {
            @Override public Optional<InventoryManifest> manifest(String revision) { return Optional.of(manifest); }
            @Override public Page page(String revision, int size, Optional<String> cursor) {
                return cursor.isEmpty() ? new Page(entries, Optional.empty()) : new Page(List.of(), Optional.empty());
            }
        };

        var tables = new SemanticTables(spyStore, 8192);
        openCalls.set(0);
        verifyCalls.set(0);
        rangeCalls.set(0);
        var report = new PolyglotInventory().scan("rev", reader, spyStore, tables, () -> false);

        assertEquals(2, openCalls.get(), "Each verified source file must be opened exactly once");
        assertEquals(0, verifyCalls.get(), "Single-pass scan must not perform a separate verify() pass");
        assertEquals(0, rangeCalls.get(), "Single-pass scan must not perform a separate range() pass for verified files");

        var rows = new ArrayList<SemanticTables.Row>();
        try (var r = tables.open(report.entries(), () -> false)) {
            SemanticTables.Row row;
            while ((row = r.next()) != null) rows.add(row);
        }
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> r.fields().get("languageCandidates").equals(List.of("python"))
                && "CLASSIFICATION_HINT_ONLY".equals(r.fields().get("reason"))));
        assertTrue(rows.stream().anyMatch(r -> "CLASSIFICATION_INPUT_UNAVAILABLE".equals(r.fields().get("reason"))),
                "Tampered digest must be rejected during single-pass streaming");
    }

    @Test
    void frontendCanonicalDigestMatchesCanonicalJsonAndPreservesSpringAndGraphDigests() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/digest-cache.git");
        var module = ModuleDescriptor.create(repo, "app", "app");
        var doc = SourceDocument.create(repo, module, "app/A.java", ContentDigest.sha256Utf8("class A {}"), SourceClassification.MAIN);
        var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false, List.of(SnapshotFile.from(doc)), List.of(doc));
        var comp = new ManifestComponent(PROVIDER, ContentDigest.sha256Utf8("c"));
        var manifest = AnalysisManifest.create(PROVIDER, snapshot, List.of(module), List.of(),
                AnalysisConfiguration.create(PROVIDER, Map.of()), comp, comp, comp);
        var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module.identity()), EntityKind.TYPE,
                JavaSymbolName.topLevelType("p", "A").canonicalName(), Optional.of(new SourceSpan(doc.identity(), 1, 1, 1, 10)));
        var coverage = FrontendRequest.CATEGORIES.stream()
                .map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED, 0, 0, 0))
                .toList();
        var frontend = new FrontendResult(manifest.identity(), PROVIDER, FrontendResult.State.COMPLETED,
                List.of(new DeclarationRecord(entity, "class A {}", SemanticStatus.RESOLVED, DIRECT, List.of(), List.of())),
                List.of(), List.of(), List.of(new SourceOutcome(doc.identity(), SourceOutcome.State.PROCESSED, List.of())),
                coverage, List.of());

        ContentDigest expected = ContentDigest.sha256Utf8(CanonicalJson.write(frontend));
        assertEquals(expected, frontend.canonicalDigest());
        assertSame(frontend.canonicalDigest(), frontend.canonicalDigest(), "canonicalDigest() must be memoized");

        var springInv = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                frontend, new SpringFrameworkEvidence(false, Optional.empty(), List.of()), List.of(), List.of()));
        assertEquals(expected, springInv.frontendResultIdentity());

        var g1 = new CanonicalGraphBuilder().build(manifest, frontend);
        var g2 = new CanonicalGraphBuilder().build(manifest, frontend);
        assertEquals(g1.identity(), g2.identity());
    }

    private static UniversalBuildModel.Module buildModule(ModuleDescriptor m, List<SourcePlanModel.Gap> mainGaps) {
        return buildModule(m, mainGaps, Optional.empty());
    }

    private static UniversalBuildModel.Module buildModule(ModuleDescriptor m, List<SourcePlanModel.Gap> mainGaps,
            Optional<MavenCoordinate> coord) {
        var java21 = new SourcePlanModel.Setting("java.release", Optional.of("21"), Optional.of("21"),
                SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
        var utf8 = new SourcePlanModel.Setting("encoding", Optional.of("UTF-8"), Optional.of("UTF-8"),
                SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
        var mainRoot = new SourcePlanModel.Setting("sourceRoot", Optional.of(m.path() + "/src/main/java"),
                Optional.of(m.path() + "/src/main/java"), SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
        var testRoot = new SourcePlanModel.Setting("testRoot", Optional.of(m.path() + "/src/test/java"),
                Optional.of(m.path() + "/src/test/java"), SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
        var outDir = new SourcePlanModel.Setting("output", Optional.of(m.path() + "/target/classes"),
                Optional.of(m.path() + "/target/classes"), SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
        var testOut = new SourcePlanModel.Setting("testOutput", Optional.of(m.path() + "/target/test-classes"),
                Optional.of(m.path() + "/target/test-classes"), SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
        var mainSet = new SourcePlanModel.SourceSetPlan(m.identity(), SourcePlanModel.Kind.MAIN,
                List.of(mainRoot), List.of(), outDir, Map.of(), java21, java21, java21, utf8, List.of(), mainGaps);
        var testSet = new SourcePlanModel.SourceSetPlan(m.identity(), SourcePlanModel.Kind.TEST,
                List.of(testRoot), List.of(), testOut, Map.of(), java21, java21, java21, utf8, List.of(), List.of());
        return new UniversalBuildModel.Module(m, UniversalBuildModel.Tool.MAVEN, Optional.of(m.path() + "/pom.xml"),
                coord, new SourcePlanModel(List.of(mainSet, testSet), List.of()), List.of(), List.of());
    }
}
