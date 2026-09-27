package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveRepositoryJourneyTest {
    @TempDir Path temporary;
    private static final VersionedIdentifier PRODUCER = new VersionedIdentifier("test.capture", "1");

    @Test void supervisedClasspathReceiptRecoversAfterCrashAndTimeoutWithoutLosingStructure() throws Exception {
        var journey = new AdaptiveRepositoryJourney();
        var intake = journey.intake(repo(), request(100), policy());
        var inputs = intake.inputs().orElseThrow(); var build = intake.build().orElseThrow();
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(),
                SourcePlanModel.Kind.MAIN);
        Path root = Files.createDirectory(temporary.resolve("supervised-artifacts"));
        byte[] platformBytes = zip("java/lang/Object.class");
        byte[] dependencyBytes = zip("Dependency.class");
        Path platformFile = Files.write(root.resolve("platform.jar"), platformBytes);
        Path dependencyFile = Files.write(root.resolve("dependency.jar"), dependencyBytes);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "platform.jar", ContentDigest.sha256(platformBytes), platformFile, PlatformInput.Format.JAR)));
        var binary = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "g:dependency:1@jar", ContentDigest.sha256(dependencyBytes)), dependencyFile);
        var manifest = FilesystemResolutionBundleImporter.Manifest.create(inputs.identity(), build.identity(), set,
                List.of(binary.entry()), PRODUCER, FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE);
        var selection = new AdaptiveRepositoryJourney.Selection(root, set, manifest, platform, List.of(binary),
                new FilesystemResolutionBundleImporter.Policy(8, 10000, 20000, 100, 20000, Set.of(PRODUCER)));
        var permitted = new AdaptiveEvidenceCoordinator.Policy(
                Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ,
                        EvidenceRequirement.AuthorizationClass.LOCAL_WRITE), 8, 10000);
        var workerPolicy = new WorkerProcessSupervisor.Policy(Duration.ofMillis(800), 64, 10000, 256, 1);
        Path checkpoints = Files.createDirectory(temporary.resolve("worker-checkpoints"));

        var crashed = journey.resolveSupervised(intake, selection, permitted, components(), workerPolicy,
                checkpoints, new WorkerProcessSupervisor((task, limits) -> faultWorker("exit")), () -> false);
        assertEquals(AdaptiveEvidenceCoordinator.Termination.OUTSTANDING, crashed.coordination().termination());
        assertEquals(FilesystemResolutionBundleImporter.Status.EXACT, crashed.imported().orElseThrow().status());
        assertTrue(crashed.sources().outcomes().stream().allMatch(o -> o.request().isEmpty()));
        assertTrue(crashed.ledger().gaps().stream().anyMatch(g -> g.reasonCode().equals("CRASHED")));
        assertTrue(crashed.ledger().gaps().stream().filter(g -> g.reasonCode().equals("CRASHED"))
                .allMatch(g -> g.evidenceRequirements().getFirst().authorizationClass()
                        == EvidenceRequirement.AuthorizationClass.LOCAL_WRITE));
        assertTrue(crashed.sources().sources().stream().anyMatch(s -> s.path().endsWith("Client.java")));

        var timedOut = journey.resolveSupervised(intake, selection, permitted, components(), workerPolicy,
                checkpoints, new WorkerProcessSupervisor((task, limits) -> faultWorker("hang")), () -> false);
        assertEquals(AdaptiveEvidenceCoordinator.Termination.OUTSTANDING, timedOut.coordination().termination());
        assertTrue(timedOut.ledger().gaps().stream().anyMatch(g -> g.reasonCode().equals("TIMED_OUT")));

        var cancellationRequested = new AtomicBoolean();
        var cancelled = journey.resolveSupervised(intake, selection, permitted, components(), workerPolicy,
                checkpoints, new WorkerProcessSupervisor((task, limits) -> {
                    Process worker = faultWorker("hang");
                    cancellationRequested.set(true);
                    return worker;
                }), cancellationRequested::get);
        assertEquals(AdaptiveEvidenceCoordinator.Termination.CANCELLED, cancelled.coordination().termination());
        assertTrue(cancelled.ledger().gaps().stream().anyMatch(g -> g.reasonCode().equals("CANCELLED")));
        assertTrue(cancelled.sources().outcomes().stream().allMatch(o -> o.request().isEmpty()));

        var complete = journey.resolveSupervised(intake, selection, permitted, components(), workerPolicy,
                checkpoints, new WorkerProcessSupervisor(), () -> false);
        assertEquals(AdaptiveEvidenceCoordinator.Termination.COMPLETE, complete.coordination().termination());
        assertEquals(800_000_000L, complete.coordination().ledger().attempts().getFirst()
                .resourceLimits().get("workerTimeoutNanos"));
        assertTrue(complete.sources().outcomes().stream().anyMatch(o -> o.request().isPresent()));
        var replay = journey.resolveSupervised(intake, selection, permitted, components(), workerPolicy,
                checkpoints, new WorkerProcessSupervisor((task, limits) -> {
                    throw new AssertionError("committed worker stage must replay");
                }), () -> false);
        assertEquals(complete.identity(), replay.identity());
        var changedPolicy = new WorkerProcessSupervisor.Policy(Duration.ofSeconds(2), 64, 10000, 256, 1);
        var invalidated = journey.resolveSupervised(intake, selection, permitted, components(), changedPolicy,
                checkpoints, new WorkerProcessSupervisor((task, limits) -> {
                    throw new IOException("expected new task after policy change");
                }), () -> false);
        assertEquals(AdaptiveEvidenceCoordinator.Termination.OUTSTANDING, invalidated.coordination().termination());
        assertTrue(invalidated.ledger().gaps().stream().anyMatch(g -> g.reasonCode().equals("START_FAILED")));
    }

    @Test void deniedSupervisedStageDoesNotLaunchAWorkerOrReadArtifacts() throws Exception {
        var journey = new AdaptiveRepositoryJourney();
        var intake = journey.intake(repo(), request(100), policy());
        var inputs = intake.inputs().orElseThrow(); var build = intake.build().orElseThrow();
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(),
                SourcePlanModel.Kind.MAIN);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "unread.jar", ContentDigest.sha256Utf8("unread"), temporary.resolve("missing.jar"), PlatformInput.Format.JAR)));
        var selection = new AdaptiveRepositoryJourney.Selection(temporary, set,
                FilesystemResolutionBundleImporter.Manifest.create(inputs.identity(), build.identity(), set,
                        List.of(), PRODUCER, FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE),
                platform, List.of(), new FilesystemResolutionBundleImporter.Policy(8, 10000, 20000, 100,
                        20000, Set.of(PRODUCER)));
        Path checkpoints = Files.createDirectory(temporary.resolve("denied-checkpoints"));
        var report = journey.resolveSupervised(intake, selection,
                new AdaptiveEvidenceCoordinator.Policy(Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ),
                        8, 10000), components(), new WorkerProcessSupervisor.Policy(Duration.ofSeconds(1),
                        64, 10000, 256, 1), checkpoints, new WorkerProcessSupervisor((task, limits) -> {
                    throw new AssertionError("denied stage must not launch");
                }), () -> false);
        assertEquals(AdaptiveEvidenceCoordinator.OpenReason.DENIED,
                report.coordination().open().values().iterator().next());
        assertTrue(report.imported().isEmpty());

        var writeOnly = journey.resolveSupervised(intake, selection,
                new AdaptiveEvidenceCoordinator.Policy(Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_WRITE),
                        8, 10000), components(), new WorkerProcessSupervisor.Policy(Duration.ofSeconds(1),
                        64, 10000, 256, 1), checkpoints, new WorkerProcessSupervisor((task, limits) -> {
                    throw new AssertionError("read-denied stage must not launch");
                }), () -> false);
        assertEquals(AdaptiveEvidenceCoordinator.OpenReason.DENIED,
                writeOnly.coordination().open().values().iterator().next());
        assertTrue(writeOnly.imported().isEmpty());
    }

    private static Process faultWorker(String mode) throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                FaultWorkerMain.class.getName(), mode).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
    public static final class FaultWorkerMain {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("exit")) System.exit(17);
            Thread.sleep(60_000);
        }
    }

    @Test void capturedGradleClasspathClosesRequirementAndAssemblesExactInput() throws Exception {
        Path repository = repo();
        var journey = new AdaptiveRepositoryJourney();
        var intake = journey.intake(repository, request(100), policy());
        assertEquals(3, intake.inventory().observedFiles().size());
        var inputs = intake.inputs().orElseThrow(); var build = intake.build().orElseThrow();
        var module = build.modules().getFirst();
        var set = new UniversalSourceIngestion.SourceSet(module.descriptor().identity(), SourcePlanModel.Kind.MAIN);
        byte[] platformBytes = zip("java/lang/Object.class");
        byte[] dependencyBytes = zip("Dependency.class");
        Path root = Files.createDirectory(temporary.resolve("artifacts"));
        Path platformFile = Files.write(root.resolve("platform.jar"), platformBytes);
        Path dependencyFile = Files.write(root.resolve("dependency.jar"), dependencyBytes);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "platform.jar", ContentDigest.sha256(platformBytes), platformFile, PlatformInput.Format.JAR)));
        var binary = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "g:dependency:1@jar", ContentDigest.sha256(dependencyBytes)), dependencyFile);
        var manifest = FilesystemResolutionBundleImporter.Manifest.create(inputs.identity(), build.identity(), set,
                List.of(binary.entry()), PRODUCER, FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE);
        var selected = new FilesystemResolutionBundleImporter.ResolvedNode(binary.entry(),
                "g:dependency:1", "runtimeElements", List.of("g:dependency:1"));
        var graph = FilesystemResolutionBundleImporter.ResolvedGraph.create(inputs.identity(), build.identity(),
                set, "compileClasspath", List.of(selected), List.of(), PRODUCER,
                FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE);
        var selection = new AdaptiveRepositoryJourney.Selection(root, set, manifest, platform,
                List.of(binary), new FilesystemResolutionBundleImporter.Policy(8, 10000, 20000, 100,
                        20000, Set.of(PRODUCER)), Optional.of(graph));
        var report = journey.resolve(intake, selection,
                new AdaptiveEvidenceCoordinator.Policy(Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ),
                        8, 10000), components());
        assertEquals(AdaptiveEvidenceCoordinator.Termination.COMPLETE,
                report.coordination().termination());
        assertEquals(AcquisitionAttemptRecord.TrustDecision.TRUSTED_INPUT,
                report.coordination().ledger().attempts().getFirst().trustDecision());
        assertTrue(report.sources().outcomes().stream().filter(o -> o.sourceSet().equals(set))
                .anyMatch(o -> o.request().isPresent()), report.sources().issues().toString());
        assertTrue(report.ledger().resolutions().size() >= 1);
        assertEquals(report.identity(), journey.resolve(intake, selection,
                new AdaptiveEvidenceCoordinator.Policy(Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ),
                        8, 10000), components()).identity());
        var wrong = FilesystemResolutionBundleImporter.ResolvedGraph.create(inputs.identity(), build.identity(),
                set, "compileClasspath", List.of(new FilesystemResolutionBundleImporter.ResolvedNode(
                        binary.entry(), "g:dependency:9", "runtimeElements", List.of("g:dependency:1"))),
                List.of(), PRODUCER, FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE);
        var rejected = journey.resolve(intake, new AdaptiveRepositoryJourney.Selection(root, set, manifest,
                platform, List.of(binary), selection.policy(), Optional.of(wrong)),
                new AdaptiveEvidenceCoordinator.Policy(Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ),
                        8, 10000), components());
        assertEquals(FilesystemResolutionBundleImporter.Status.PARTIAL, rejected.imported().orElseThrow().status());
        assertTrue(rejected.ledger().gaps().stream().anyMatch(g -> g.reasonCode()
                .equals("RESOLVED_SELECTION_MISMATCH")));
        assertTrue(rejected.sources().outcomes().stream().allMatch(o -> o.request().isEmpty()));
    }

    @Test void deniedArtifactReadKeepsObservedStructureWithoutOpeningFiles() throws Exception {
        Path repository = repo(); var journey = new AdaptiveRepositoryJourney();
        var intake = journey.intake(repository, request(100), policy());
        var inputs = intake.inputs().orElseThrow(); var build = intake.build().orElseThrow();
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(),
                SourcePlanModel.Kind.MAIN);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "unread.jar", ContentDigest.sha256Utf8("unread"), temporary.resolve("missing.jar"), PlatformInput.Format.JAR)));
        var selection = new AdaptiveRepositoryJourney.Selection(temporary, set,
                FilesystemResolutionBundleImporter.Manifest.create(inputs.identity(), build.identity(), set,
                        List.of(), PRODUCER, FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE),
                platform, List.of(), new FilesystemResolutionBundleImporter.Policy(8, 10000, 20000, 100,
                        20000, Set.of(PRODUCER)));
        var report = journey.resolve(intake, selection,
                new AdaptiveEvidenceCoordinator.Policy(Set.of(EvidenceRequirement.AuthorizationClass.PASSIVE),
                        8, 10000), components());
        assertTrue(report.imported().isEmpty());
        assertEquals(AdaptiveEvidenceCoordinator.OpenReason.DENIED,
                report.coordination().open().values().iterator().next());
        assertTrue(report.sources().sources().stream().anyMatch(s -> s.path().endsWith("Client.java")));
        assertTrue(report.sources().outcomes().stream().allMatch(o -> o.request().isEmpty()));
    }

    @Test void cancelledResolutionDoesNotOpenArtifactsAndRetainsIntakeStructure() throws Exception {
        var journey = new AdaptiveRepositoryJourney();
        var intake = journey.intake(repo(), request(100), policy());
        var inputs = intake.inputs().orElseThrow(); var build = intake.build().orElseThrow();
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(),
                SourcePlanModel.Kind.MAIN);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "unread.jar", ContentDigest.sha256Utf8("unread"), temporary.resolve("missing.jar"),
                PlatformInput.Format.JAR)));
        var selection = new AdaptiveRepositoryJourney.Selection(temporary, set,
                FilesystemResolutionBundleImporter.Manifest.create(inputs.identity(), build.identity(), set,
                        List.of(), PRODUCER, FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE),
                platform, List.of(), new FilesystemResolutionBundleImporter.Policy(8, 10000, 20000, 100,
                        20000, Set.of(PRODUCER)));
        var report = journey.resolve(intake, selection,
                new AdaptiveEvidenceCoordinator.Policy(Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ),
                        8, 10000), components(), () -> true);
        assertEquals(AdaptiveEvidenceCoordinator.Termination.CANCELLED,
                report.coordination().termination());
        assertEquals(AdaptiveEvidenceCoordinator.OpenReason.CANCELLED,
                report.coordination().open().values().iterator().next());
        assertTrue(report.imported().isEmpty());
        assertTrue(report.sources().sources().stream().anyMatch(s -> s.path().endsWith("Client.java")));
        assertTrue(report.sources().outcomes().stream().allMatch(o -> o.request().isEmpty()));
    }

    @Test void corruptSelectedArchiveAppearsAsTypedRootCauseGap() throws Exception {
        var journey = new AdaptiveRepositoryJourney();
        var intake = journey.intake(repo(), request(100), policy());
        var inputs = intake.inputs().orElseThrow(); var build = intake.build().orElseThrow();
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(),
                SourcePlanModel.Kind.MAIN);
        Path root = Files.createDirectory(temporary.resolve("corrupt-artifacts"));
        byte[] corrupt = "not a jar".getBytes(StandardCharsets.UTF_8);
        Path platformFile = Files.write(root.resolve("platform.jar"), corrupt);
        byte[] dependencyBytes = zip("Dependency.class");
        Path dependencyFile = Files.write(root.resolve("dependency.jar"), dependencyBytes);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "platform.jar", ContentDigest.sha256(corrupt), platformFile, PlatformInput.Format.JAR)));
        var binary = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "g:dependency:1@jar", ContentDigest.sha256(dependencyBytes)), dependencyFile);
        var selection = new AdaptiveRepositoryJourney.Selection(root, set,
                FilesystemResolutionBundleImporter.Manifest.create(inputs.identity(), build.identity(), set,
                        List.of(binary.entry()), PRODUCER, FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE),
                platform, List.of(binary), new FilesystemResolutionBundleImporter.Policy(
                        8, 10000, 20000, 100, 20000, Set.of(PRODUCER)));
        var report = journey.resolve(intake, selection,
                new AdaptiveEvidenceCoordinator.Policy(Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ),
                        8, 10000), components());
        assertEquals(FilesystemResolutionBundleImporter.Status.PARTIAL, report.imported().orElseThrow().status());
        assertTrue(report.ledger().gaps().stream().anyMatch(g -> g.reasonCode().equals("CORRUPT_ARCHIVE")));
        assertTrue(report.outstandingGapCount() > 0);
        assertTrue(report.sources().outcomes().stream().allMatch(o -> o.request().isEmpty()));
    }

    @Test void quotaLimitedIntakeKeepsFrontierWithoutInventingBuildContext() throws Exception {
        Path repository = repo();
        var intake = new AdaptiveRepositoryJourney().intake(repository, request(1), policy());
        assertTrue(intake.inputs().isEmpty());
        assertTrue(intake.build().isEmpty());
        assertTrue(intake.inventory().unobservedFileCount().isEmpty());
        assertFalse(intake.inventory().frontiers().isEmpty());
    }

    @Test void capturedSourcePlanForCustomBuildReachesExactInputOnlyWithVerifiedClasspath() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("custom"));
        Files.writeString(repository.resolve("BUILD"), "java_library(name = 'app')", StandardCharsets.UTF_8);
        Files.createDirectories(repository.resolve("app/src"));
        Files.writeString(repository.resolve("app/src/Client.java"), "class Client {}", StandardCharsets.UTF_8);
        var journey = new AdaptiveRepositoryJourney();
        var intake = journey.intake(repository, request(100), policy());
        var inputs = intake.inputs().orElseThrow();
        var capture = "selected custom build model".getBytes(StandardCharsets.UTF_8);
        var captured = CapturedSourcePlanImporter.Bundle.create(inputs.identity(), "app", PRODUCER,
                "custom-build", capture, ContentDigest.sha256(capture),
                List.of("app/src"), List.of("app/test"), 21, 21, 21, "UTF-8");
        var importedBuild = new CapturedSourcePlanImporter().importBundle(inputs, captured,
                new CapturedSourcePlanImporter.Policy(Set.of(PRODUCER), 8, 1000)).build().orElseThrow();
        var set = new UniversalSourceIngestion.SourceSet(importedBuild.modules().getFirst().descriptor().identity(),
                SourcePlanModel.Kind.MAIN);
        byte[] platformBytes = zip("java/lang/Object.class");
        Path root = Files.createDirectory(temporary.resolve("custom-artifacts"));
        Path platformFile = Files.write(root.resolve("platform.jar"), platformBytes);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "platform.jar", ContentDigest.sha256(platformBytes), platformFile, PlatformInput.Format.JAR)));
        var manifest = FilesystemResolutionBundleImporter.Manifest.create(inputs.identity(),
                importedBuild.identity(), set, List.of(), PRODUCER,
                FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE);
        var selection = new AdaptiveRepositoryJourney.Selection(root, set, manifest, platform, List.of(),
                new FilesystemResolutionBundleImporter.Policy(8, 10000, 20000, 100, 20000, Set.of(PRODUCER)));
        var report = journey.resolve(intake, importedBuild, selection,
                new AdaptiveEvidenceCoordinator.Policy(Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ),
                        8, 10000), components());
        assertTrue(report.sources().outcomes().stream().filter(o -> o.sourceSet().equals(set))
                .anyMatch(o -> o.request().isPresent()), report.sources().issues().toString());
        assertThrows(IllegalArgumentException.class, () -> journey.resolve(intake,
                intake.build().orElseThrow(), selection,
                new AdaptiveEvidenceCoordinator.Policy(Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ),
                        8, 10000), components()));
    }

    private Path repo() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("repository"));
        Files.writeString(root.resolve("build.gradle"),
                "plugins { java }\ndependencies { implementation 'g:dependency:1' }\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/Client.java"), "class Client { Dependency dep; }", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("frontend"));
        Files.writeString(root.resolve("frontend/index.js"), "export const ready = true;", StandardCharsets.UTF_8);
        return root;
    }
    private static RepositoryAcquisitionRequest request(int maxFiles) {
        return new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate(
                "https://example.test/journey.git"), Optional.empty(), false, "pom.xml",
                new RepositoryAcquisitionPolicy(maxFiles, 100, 10000, 100000, 16, List.of()));
    }
    private static AdaptiveRepositoryJourney.Policy policy() {
        return new AdaptiveRepositoryJourney.Policy(new RepositoryInputDecoder.Policy("UTF-8", 100, 10000),
                new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21), Optional.of("UTF-8"),
                        100, 10000, 1000, 16),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16));
    }
    private static ManifestComponent component() {
        return new ManifestComponent(new VersionedIdentifier("test.components", "1"),
                ContentDigest.sha256Utf8("components"));
    }
    private static AdaptiveRepositoryJourney.Components components() {
        return new AdaptiveRepositoryJourney.Components(component(), component(), component());
    }
    private static byte[] zip(String name) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry(name)); archive.write(new byte[] {1, 2, 3}); archive.closeEntry();
        }
        return output.toByteArray();
    }
}
