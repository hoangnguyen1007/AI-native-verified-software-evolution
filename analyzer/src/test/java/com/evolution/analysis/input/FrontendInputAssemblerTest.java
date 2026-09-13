package com.evolution.analysis.input;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.acquisition.CandidateSourceOwnership;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.*;
import com.evolution.analysis.classpath.ExactClasspathResult.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.evidence.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class FrontendInputAssemblerTest {
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate(
            "https://example.test/assembly.git");
    private static final ModuleDescriptor APP = ModuleDescriptor.create(REPOSITORY, "app", "app");
    private static final ModuleDescriptor LIB = ModuleDescriptor.create(REPOSITORY, "lib", "lib");
    private static final BuildModelResult.PomEvidence POM = new BuildModelResult.PomEvidence(
            "workspace:app/pom.xml", ContentDigest.sha256Utf8("pom"));
    private static final String SOURCE_PATH = "app/src/main/java/App.java";

    @Test
    void assemblesExactInterleavedReactorAndDependencyOrderWithBoundProvenance() {
        Fixture fixture = fixture();
        FrontendAssemblyResult result = FrontendInputAssembler.assemble(
                fixture.ownership, fixture.build, fixture.classpathRequest, fixture.classpaths, fixture.decoding,
                fixture.platformResult, List.of(fixture.dependency), List.of(fixture.reactor), policy());

        assertFalse(result.hasGaps());
        FrontendRequest request = result.outcomes().getFirst().request().orElseThrow();
        assertEquals(List.of(fixture.platform.entry(), fixture.reactor.binary().entry(), fixture.dependency.entry()),
                request.manifest().classpath());
        assertEquals(List.of(fixture.reactor.binary(), fixture.dependency), request.dependencies());
        assertEquals("17", request.manifest().configuration().values().get("java.release"));
        assertEquals(fixture.platform.vendor(),
                request.manifest().configuration().values().get("java.platform.vendor"));
        assertEquals(fixture.decoding.identity().value(),
                request.manifest().configuration().values().get("java.source-decoding"));
        var spring = com.evolution.analysis.spring.condition.SpringBuildContext.from(result, APP.identity(), SourcePlanModel.Kind.MAIN);
        assertEquals(request.manifest().snapshot().identity(), spring.snapshotIdentity());
        assertEquals(List.of(Map.of("kind", "BINARY", "entry", fixture.reactor.binary().entry()),
                        Map.of("kind", "BINARY", "entry", fixture.dependency.entry())),
                spring.canonicalForm().get("orderedResolutionInputs"));
    }

    @Test
    void missingMismatchedOrExtraInputsAreWithheldAndNeverFormASuperset() {
        Fixture fixture = fixture();
        FrontendAssemblyResult missing = FrontendInputAssembler.assemble(
                fixture.ownership, fixture.build, fixture.classpathRequest, fixture.classpaths, fixture.decoding,
                fixture.platformResult, List.of(fixture.dependency), List.of(), policy());
        assertTrue(missing.outcomes().getFirst().request().isEmpty());
        assertTrue(missing.outcomes().getFirst().problems().stream()
                .anyMatch(problem -> problem.reason() == FrontendAssemblyResult.Reason.MISSING_REACTOR_OUTPUT));

        ClasspathEntry extraEntry = new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "extra:unused:1",
                ContentDigest.sha256Utf8("extra"));
        BinaryInput extra = new BinaryInput(extraEntry, Path.of("unused.jar"));
        FrontendAssemblyResult superset = FrontendInputAssembler.assemble(
                fixture.ownership, fixture.build, fixture.classpathRequest, fixture.classpaths, fixture.decoding,
                fixture.platformResult, List.of(fixture.dependency, extra), List.of(fixture.reactor), policy());
        assertTrue(superset.outcomes().getFirst().request().isEmpty());
        assertTrue(superset.outcomes().getFirst().problems().stream()
                .anyMatch(problem -> problem.reason() == FrontendAssemblyResult.Reason.UNREFERENCED_BINARY_INPUT));

        PlatformInput wrongPlatform = PlatformInput.create(11, "11-fixture", "Fixture Vendor",
                fixture.platform.artifacts());
        PlatformSymbolResult wrongResult = PlatformSymbolResult.create(
                new PlatformSymbolRequest(11, 1, 1000, 1000), new VersionedIdentifier("platform.test", "1"),
                Optional.of(wrongPlatform), List.of(), List.of());
        FrontendAssemblyResult mismatch = FrontendInputAssembler.assemble(
                fixture.ownership, fixture.build, fixture.classpathRequest, fixture.classpaths, fixture.decoding,
                wrongResult, List.of(fixture.dependency), List.of(fixture.reactor), policy());
        assertTrue(mismatch.outcomes().getFirst().problems().stream()
                .anyMatch(problem -> problem.reason() == FrontendAssemblyResult.Reason.PLATFORM_RELEASE_MISMATCH));
    }

    @Test
    void classpathProfileQualifierDoesNotEraseAnOtherwiseExactInactiveBaseline() {
        Fixture fixture = fixture();
        Manifest original = fixture.classpaths.manifests().getFirst();
        Problem qualifier = new Problem(Reason.UNSUPPORTED_PROFILE_ACTIVATION, "ext:parent:1",
                Requirement.ANALYSIS_CONFIGURATION, List.of());
        Manifest qualified = Manifest.create(original.module(), original.sourceSet(), original.entries(),
                original.reactorEntries(), original.decisions(),
                java.util.stream.Stream.concat(original.problems().stream(), java.util.stream.Stream.of(qualifier))
                        .toList());
        ExactClasspathResult classpaths = ExactClasspathResult.create(fixture.classpathRequest.identity(),
                new VersionedIdentifier("classpath.test", "1"), List.of(qualified), List.of(), List.of(), List.of());

        FrontendAssemblyResult result = FrontendInputAssembler.assemble(
                fixture.ownership, fixture.build, fixture.classpathRequest, classpaths, fixture.decoding,
                fixture.platformResult, List.of(fixture.dependency), List.of(fixture.reactor), policy());

        assertTrue(result.outcomes().getFirst().request().isPresent());
    }

    @Test
    void nonFatalDescriptorWarningsAndTruncatedCyclesRemainVisibleWithoutWithholdingExactBinaries() {
        Fixture fixture = fixture();
        Manifest original = fixture.classpaths.manifests().getFirst();
        List<Problem> qualifiers = List.of(
                new Problem(Reason.POM_MODEL_WARNING, "demo:external:1",
                        Requirement.ARTIFACT_POM, List.of()),
                new Problem(Reason.DEPENDENCY_CYCLE, "demo:external:1@jar",
                        Requirement.ARTIFACT_POM, List.of()));
        Manifest qualified = Manifest.create(original.module(), original.sourceSet(), original.entries(),
                original.reactorEntries(), original.decisions(), qualifiers);
        ExactClasspathResult classpaths = ExactClasspathResult.create(fixture.classpathRequest.identity(),
                new VersionedIdentifier("classpath.test", "1"), List.of(qualified), List.of(), List.of(), List.of());

        FrontendAssemblyResult result = FrontendInputAssembler.assemble(
                fixture.ownership, fixture.build, fixture.classpathRequest, classpaths, fixture.decoding,
                fixture.platformResult, List.of(fixture.dependency), List.of(fixture.reactor), policy());

        assertTrue(result.outcomes().getFirst().request().isPresent(),
                () -> result.outcomes().getFirst().problems().toString());
    }

    @Test
    void withheldAssemblyNormalizesEveryProblemAndRetainsSourceSetScope() {
        Fixture fixture = fixture();
        FrontendAssemblyResult result = FrontendInputAssembler.assemble(
                fixture.ownership, fixture.build, fixture.classpathRequest, fixture.classpaths, fixture.decoding,
                fixture.platformResult, List.of(fixture.dependency), List.of(), policy());
        EvidenceContext context = new EvidenceContext(fixture.decoding.snapshot().identity(), Optional.empty());

        EvidenceAcquisitionLedger ledger = CapabilityGapNormalizer.normalize(context,
                EvidenceNormalizationInput.builder().frontendAssemblies(List.of(result)).build());

        assertEquals(result.outcomes().getFirst().problems().size(), ledger.gaps().size());
        CapabilityGapRecord reactorGap = ledger.gaps().stream()
                .filter(gap -> gap.reasonCode().equals("MISSING_REACTOR_OUTPUT")).findFirst().orElseThrow();
        assertEquals(EvidenceSubject.Kind.SOURCE_SET, reactorGap.subject().kind());
        assertEquals(EvidenceRequirement.Kind.REACTOR_OUTPUT, reactorGap.evidenceRequirements().getFirst().kind());
        assertTrue(reactorGap.candidateProviders().isEmpty());
    }

    @Test
    void completeDecodedSiblingSourcesReplaceOnlyTheMissingReactorBinaryPosition() {
        Fixture fixture = fixture(true);

        FrontendAssemblyResult result = FrontendInputAssembler.assemble(
                fixture.ownership, fixture.build, fixture.classpathRequest, fixture.classpaths, fixture.decoding,
                fixture.platformResult, List.of(fixture.dependency), List.of(), policy());

        assertFalse(result.hasGaps(), () -> result.outcomes().getFirst().problems().toString());
        FrontendRequest request = result.outcomes().getFirst().request().orElseThrow();
        assertEquals(List.of(fixture.platform.entry(), fixture.dependency.entry()), request.manifest().classpath());
        assertEquals(1, request.reactorSources().size());
        ReactorSourceInput sibling = request.reactorSources().getFirst();
        assertEquals(LIB.identity(), sibling.module());
        assertEquals(SourceClassification.MAIN, sibling.sourceSet());
        assertEquals(0, sibling.order());
        assertEquals(List.of("lib/src/main/java/demo/Common.java"),
                sibling.sources().stream().map(value -> value.document().path()).toList());
        assertEquals("reactor-source-input-v1",
                request.manifest().configuration().values().get("java.reactor-source-resolution"));
        var spring = com.evolution.analysis.spring.condition.SpringBuildContext.from(result, APP.identity(), SourcePlanModel.Kind.MAIN);
        assertEquals(List.of(Map.of("kind", "REACTOR_SOURCE", "identity", sibling.identity()),
                        Map.of("kind", "BINARY", "entry", fixture.dependency.entry())),
                spring.canonicalForm().get("orderedResolutionInputs"));

        Fixture generated = fixture(true, true);
        FrontendAssemblyResult withheld = FrontendInputAssembler.assemble(
                generated.ownership, generated.build, generated.classpathRequest, generated.classpaths,
                generated.decoding, generated.platformResult, List.of(generated.dependency), List.of(), policy());
        assertTrue(withheld.outcomes().getFirst().problems().stream()
                .anyMatch(problem -> problem.reason() == FrontendAssemblyResult.Reason.MISSING_REACTOR_OUTPUT),
                "handwritten sources must not conceal an unacquired generated-source surface");
        assertThrows(IllegalArgumentException.class, () -> com.evolution.analysis.spring.condition.SpringBuildContext.from(
                withheld, APP.identity(), SourcePlanModel.Kind.MAIN));
    }

    private static Fixture fixture() {
        return fixture(false);
    }

    private static Fixture fixture(boolean includeLibSource) {
        return fixture(includeLibSource, false);
    }

    private static Fixture fixture(boolean includeLibSource, boolean generatedGap) {
        SourcePlanModel appPlan = sourcePlan(APP, "app/src/main/java", "app/target/classes");
        SourcePlanModel libPlan = sourcePlan(LIB, "lib/src/main/java", "lib/target/classes",
                generatedGap ? List.of(SourcePlanModel.Gap.GENERATED_SOURCES_NOT_ACQUIRED) : List.of());
        var appPom = new BuildModelResult.EffectivePom(new MavenCoordinate("demo", "app", "1"), "jar",
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(POM), appPlan);
        var libPom = new BuildModelResult.EffectivePom(new MavenCoordinate("demo", "lib", "1"), "jar",
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(POM), libPlan);
        byte[] bytes = "class App {}\r\n".getBytes(StandardCharsets.UTF_8);
        SourceDocument document = SourceDocument.create(REPOSITORY, APP, SOURCE_PATH,
                ContentDigest.sha256(bytes), SourceClassification.MAIN);
        byte[] libBytes = "package demo; public class Common {}\n".getBytes(StandardCharsets.UTF_8);
        SourceDocument libDocument = SourceDocument.create(REPOSITORY, LIB,
                "lib/src/main/java/demo/Common.java", ContentDigest.sha256(libBytes), SourceClassification.MAIN);
        PomInput appPomInput = new PomInput("app-pom".getBytes(StandardCharsets.UTF_8));
        PomInput libPomInput = new PomInput("lib-pom".getBytes(StandardCharsets.UTF_8));
        List<SourceDocument> documents = includeLibSource ? List.of(document, libDocument) : List.of(document);
        List<SnapshotFile> files = new ArrayList<>(documents.stream().map(SnapshotFile::from).toList());
        files.add(new SnapshotFile("app/pom.xml", appPomInput.digest()));
        files.add(new SnapshotFile("lib/pom.xml", libPomInput.digest()));
        RepositorySnapshot snapshot = RepositorySnapshot.create(
                REPOSITORY, Optional.empty(), false, files, documents);
        BuildModelRequest buildRequest = new BuildModelRequest(snapshot, "app/pom.xml",
                Map.of("app/pom.xml", appPomInput, "lib/pom.xml", libPomInput), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 10_000, 20, 10));
        BuildModelResult build = new BuildModelResult(BuildModelResult.SCHEMA, buildRequest.identity(),
                new VersionedIdentifier("build.test", "1"), List.of(
                        new BuildModelResult.ModuleModel(APP, "app/pom.xml", Optional.empty(), Optional.of(appPom)),
                        new BuildModelResult.ModuleModel(LIB, "lib/pom.xml", Optional.empty(), Optional.of(libPom))),
                List.of(), List.of(), List.of());
        var claim = new CandidateSourceOwnership.Claim(APP.identity(), SourcePlanModel.Kind.MAIN,
                "app/src/main/java", List.of(POM));
        var libClaim = new CandidateSourceOwnership.Claim(LIB.identity(), SourcePlanModel.Kind.MAIN,
                "lib/src/main/java", List.of(POM));
        List<CandidateSourceOwnership.Candidate> candidates = new ArrayList<>();
        candidates.add(new CandidateSourceOwnership.Candidate(SOURCE_PATH, document.contentDigest(),
                CandidateSourceOwnership.Status.OWNED, List.of(claim)));
        if (includeLibSource) candidates.add(new CandidateSourceOwnership.Candidate(
                libDocument.path(), libDocument.contentDigest(), CandidateSourceOwnership.Status.OWNED,
                List.of(libClaim)));
        CandidateSourceOwnership ownership = new CandidateSourceOwnership(CandidateSourceOwnership.SCHEMA,
                snapshot.identity(), build.identity(), CandidateSourceOwnership.PROVIDER,
                candidates, List.of(), List.of());
        SourceInput source = new SourceInput(document, bytes, new SourceInput.Decoding("source-decoding-v1", "UTF-8",
                SourceInput.EncodingOrigin.BUILD_DECLARATION,
                List.of(new SourceInput.Evidence(POM.logicalId(), POM.digest())), SourceInput.Bom.NONE,
                SourceInput.LineEndings.from("")));
        var outcome = new SourceDecodingResult.Outcome(SOURCE_PATH, document.contentDigest(),
                SourceDecodingResult.Status.DECODED, Optional.of(source), List.of());
        List<SourceDecodingResult.Outcome> outcomes = new ArrayList<>(List.of(outcome));
        if (includeLibSource) {
            SourceInput libSource = new SourceInput(libDocument, libBytes, new SourceInput.Decoding(
                    "source-decoding-v1", "UTF-8", SourceInput.EncodingOrigin.BUILD_DECLARATION,
                    List.of(new SourceInput.Evidence(POM.logicalId(), POM.digest())), SourceInput.Bom.NONE,
                    SourceInput.LineEndings.from("")));
            outcomes.add(new SourceDecodingResult.Outcome(libDocument.path(), libDocument.contentDigest(),
                    SourceDecodingResult.Status.DECODED, Optional.of(libSource), List.of()));
        }
        SourceDecodingResult decoding = SourceDecodingResult.create(ContentDigest.sha256Utf8("acquisition"),
                ownership.identity(), build.identity(), SourceDecodingPolicy.withholdWhenAbsent(), snapshot, outcomes);

        ArtifactCoordinate external = new ArtifactCoordinate(new MavenCoordinate("demo", "external", "1"), "jar", "");
        ContentDigest dependencyDigest = ContentDigest.sha256Utf8("dependency");
        Entry dependencyEntry = new Entry(external, DependencyScope.COMPILE,
                external.classpathEntry(dependencyDigest), external.repositoryPath(), 1, true, 1,
                List.of(new Evidence("cache:" + external.repositoryPath(), dependencyDigest)));
        ReactorEntry reactorEntry = new ReactorEntry(new MavenCoordinate("demo", "lib", "1"), LIB.identity(),
                SourcePlanModel.Kind.MAIN, DependencyScope.COMPILE, Optional.of("lib/target/classes"),
                0, true, 1, List.of(POM).stream().map(value -> new Evidence(value.logicalId(), value.digest())).toList());
        Problem reactorProblem = new Problem(Reason.REACTOR_OUTPUT_NOT_ACQUIRED, "demo:lib:1",
                Requirement.REACTOR_OUTPUT, List.of());
        Manifest manifest = Manifest.create(APP.identity(), SourcePlanModel.Kind.MAIN,
                List.of(dependencyEntry), List.of(reactorEntry), List.of(), List.of(reactorProblem));
        ClasspathResolutionRequest classpathRequest = new ClasspathResolutionRequest(buildRequest, build,
                new ClasspathResolutionPolicy(10, 10, 10_000, 10_000, 20_000, 10));
        ExactClasspathResult classpaths = ExactClasspathResult.create(classpathRequest.identity(),
                new VersionedIdentifier("classpath.test", "1"), List.of(manifest), List.of(), List.of(), List.of());

        ContentDigest platformBytes = ContentDigest.sha256Utf8("platform");
        PlatformInput platform = PlatformInput.create(17, "17-fixture", "Fixture Vendor", List.of(
                new PlatformInput.Artifact("jmods/java.base.jmod", platformBytes, Path.of("java.base.jmod"),
                        PlatformInput.Format.JMOD)));
        PlatformSymbolResult platformResult = PlatformSymbolResult.create(
                new PlatformSymbolRequest(17, 1, 1000, 1000), new VersionedIdentifier("platform.test", "1"),
                Optional.of(platform), List.of(), List.of());

        BinaryInput dependency = new BinaryInput(external.classpathEntry(dependencyDigest), Path.of("external.jar"));
        ClasspathEntry reactorClasspath = new ClasspathEntry(ClasspathEntryKind.MODULE_OUTPUT,
                "reactor:demo:lib:1:main", ContentDigest.sha256Utf8("reactor"));
        ReactorOutputInput reactor = new ReactorOutputInput(LIB.identity(), SourcePlanModel.Kind.MAIN,
                "lib/target/classes", new BinaryInput(reactorClasspath, Path.of("lib.jar"), Optional.of(LIB.identity())));
        return new Fixture(build, ownership, decoding, classpathRequest, classpaths,
                platform, platformResult, dependency, reactor);
    }

    private static SourcePlanModel sourcePlan(ModuleDescriptor module, String root, String output) {
        return sourcePlan(module, root, output, List.of());
    }

    private static SourcePlanModel sourcePlan(
            ModuleDescriptor module, String root, String output, List<SourcePlanModel.Gap> mainGaps) {
        var rootSetting = declared("root", root);
        var outputSetting = declared("output", output);
        var release = declared("release", "17");
        var encoding = declared("encoding", "UTF-8");
        var absent = absent();
        return new SourcePlanModel(List.of(
                new SourcePlanModel.SourceSetPlan(module.identity(), SourcePlanModel.Kind.MAIN, List.of(rootSetting),
                        List.of(), outputSetting, Map.of("enablePreview", declared("preview", "false")),
                        release, release, release, encoding, List.of(), mainGaps),
                new SourcePlanModel.SourceSetPlan(module.identity(), SourcePlanModel.Kind.TEST, List.of(rootSetting),
                        List.of(), outputSetting, Map.of(), release, release, release, encoding, List.of(), List.of())),
                List.of());
    }

    private static SourcePlanModel.Setting declared(String selector, String value) {
        return new SourcePlanModel.Setting(selector, Optional.of(value), Optional.of(value),
                SourcePlanModel.Status.DECLARED, SourcePlanModel.Origin.EFFECTIVE_MODEL, List.of(POM));
    }

    private static SourcePlanModel.Setting absent() {
        return new SourcePlanModel.Setting("absent", Optional.empty(), Optional.empty(),
                SourcePlanModel.Status.UNSPECIFIED, SourcePlanModel.Origin.ABSENT, List.of());
    }

    private static FrontendAssemblyPolicy policy() {
        var component = new ManifestComponent(new VersionedIdentifier("fixture.component", "1"),
                ContentDigest.sha256Utf8("component"));
        return new FrontendAssemblyPolicy(new VersionedIdentifier("analysis.manifest", "2"),
                new VersionedIdentifier("analysis.configuration", "2"), component, component, component);
    }

    private record Fixture(
            BuildModelResult build,
            CandidateSourceOwnership ownership,
            SourceDecodingResult decoding,
            ClasspathResolutionRequest classpathRequest,
            ExactClasspathResult classpaths,
            PlatformInput platform,
            PlatformSymbolResult platformResult,
            BinaryInput dependency,
            ReactorOutputInput reactor) {}
}
