package com.evolution.analysis.filesystem;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceBundleImportTest {
    @TempDir Path temporary;

    @Test void verifiedSelectedArchivesReachExactGradleSourceRequest() throws Exception {
        var inputs = inputs("class Client { Dependency dep; }");
        var build = build(inputs);
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(), SourcePlanModel.Kind.MAIN);
        var platformBytes = zip("java/lang/Object.class", new byte[] {1, 2, 3});
        byte[] resourceBytes = "com.example.AutoConfig\n".getBytes(StandardCharsets.UTF_8);
        var dependencyBytes = zipWithResource(resourceBytes);
        Path root = Files.createDirectory(temporary.resolve("selected"));
        Path platformFile = Files.write(root.resolve("platform.jar"), platformBytes);
        Path dependencyFile = Files.write(root.resolve("dependency.jar"), dependencyBytes);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "platform.jar", ContentDigest.sha256(platformBytes), platformFile, PlatformInput.Format.JAR)));
        var binary = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "g:dependency:1@jar", ContentDigest.sha256(dependencyBytes)), dependencyFile);
        var importer = new FilesystemResolutionBundleImporter();
        var manifest = manifest(inputs, build, set, List.of(binary.entry()));
        var imported = importer.importBundle(root, inputs, build, set, manifest, platform, List.of(binary), policy());
        assertEquals(FilesystemResolutionBundleImporter.Status.EXACT, imported.status(), imported.problems().toString());
        assertEquals(1, imported.resources().size());
        var resource = imported.resources().getFirst();
        assertEquals("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                resource.entryName());
        assertEquals(ContentDigest.sha256(dependencyBytes), resource.artifactDigest());
        assertEquals(ContentDigest.sha256(resourceBytes), resource.entryDigest());
        assertArrayEquals(resourceBytes, resource.bytes());
        var component = new ManifestComponent(new VersionedIdentifier("test.components", "1"),
                ContentDigest.sha256Utf8("components"));
        var assembled = new UniversalSourceIngestion().assemble(inputs, build,
                Map.of(set, imported.resolution().orElseThrow()), component, component, component);
        assertTrue(assembled.outcomes().stream().filter(o -> o.sourceSet().equals(set))
                .anyMatch(o -> o.request().isPresent()), assembled.issues().toString());
        assertEquals(imported.identity(), importer.importBundle(root, inputs, build, set,
                manifest, platform, List.of(binary), policy()).identity());
        Path checkpoints = Files.createDirectory(temporary.resolve("archive-checkpoints"));
        var workerLimits = new WorkerProcessSupervisor.Policy(Duration.ofSeconds(3), 64, 4096, 4096, 4);
        var isolated = importer.importBundleSupervised(root, inputs, build, set, manifest, platform,
                List.of(binary), policy(), workerLimits, checkpoints, () -> false);
        assertEquals(FilesystemResolutionBundleImporter.Status.EXACT, isolated.status(), isolated.problems().toString());
        assertEquals(imported.identity(), isolated.identity());
        assertEquals(imported.selectedClasses(), isolated.selectedClasses());
        assertEquals(imported.receipts(), isolated.receipts());
        Path unavailableCheckpoints = Files.createDirectory(temporary.resolve("unavailable-checkpoints"));
        var failed = importer.importBundleSupervised(root, inputs, build, set, manifest, platform,
                List.of(binary), policy(), workerLimits, unavailableCheckpoints,
                new ArchiveWorkerSupervisor((task, limits, scratch) -> {
                    throw new java.io.IOException("injected worker failure");
                }), () -> false);
        assertEquals(FilesystemResolutionBundleImporter.Status.PARTIAL, failed.status());
        assertTrue(failed.resolution().isEmpty());
        assertTrue(failed.problems().stream().anyMatch(p -> p.reason()
                == FilesystemResolutionBundleImporter.Reason.WORKER_START_FAILED));
        assertTrue(failed.selectedClasses().isEmpty());
    }

    @Test void capturedResolvedGraphBindsDirectSelectionAndTransitiveClasspath() throws Exception {
        var inputs = inputs("class Client { Dependency dep; }"); var build = build(inputs);
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(), SourcePlanModel.Kind.MAIN);
        Path root = Files.createDirectory(temporary.resolve("resolved"));
        byte[] platformBytes = zip("java/lang/Object.class", new byte[] {1});
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "platform.jar", ContentDigest.sha256(platformBytes),
                Files.write(root.resolve("platform.jar"), platformBytes), PlatformInput.Format.JAR)));
        byte[] directBytes = zip("Dependency.class", new byte[] {2});
        byte[] transitiveBytes = zip("Transitive.class", new byte[] {3});
        var direct = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "g:dependency:1@jar", ContentDigest.sha256(directBytes)),
                Files.write(root.resolve("direct.jar"), directBytes));
        var transitive = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "g:transitive:2@jar", ContentDigest.sha256(transitiveBytes)),
                Files.write(root.resolve("transitive.jar"), transitiveBytes));
        var binaries = List.of(direct, transitive);
        var manifest = manifest(inputs, build, set, binaries.stream().map(BinaryInput::entry).toList());
        var nodes = List.of(
                new FilesystemResolutionBundleImporter.ResolvedNode(direct.entry(), "g:dependency:1",
                        "runtimeElements", List.of("g:dependency:1")),
                new FilesystemResolutionBundleImporter.ResolvedNode(transitive.entry(), "g:transitive:2",
                        "runtimeElements", List.of()));
        var producer = new VersionedIdentifier("test.trusted-capture", "1");
        var graph = FilesystemResolutionBundleImporter.ResolvedGraph.create(inputs.identity(),
                build.identity(), set, "compileClasspath", nodes,
                List.of(new FilesystemResolutionBundleImporter.ResolvedEdge(
                        direct.entry().logicalName(), transitive.entry().logicalName())),
                producer, FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE);
        var importer = new FilesystemResolutionBundleImporter();
        var exact = importer.importBundle(root, inputs, build, set, manifest, platform, binaries, policy(), graph);
        assertEquals(FilesystemResolutionBundleImporter.Status.EXACT, exact.status(), exact.problems().toString());
        assertEquals(exact.identity(), exact.resolution().orElseThrow().exactClasspathEvidence());
        assertEquals(exact.identity(), importer.importBundle(root, inputs, build, set,
                manifest, platform, binaries, policy(), graph).identity());
        var staleSelection = FilesystemResolutionBundleImporter.ResolvedGraph.create(inputs.identity(),
                build.identity(), set, "compileClasspath", List.of(
                        new FilesystemResolutionBundleImporter.ResolvedNode(direct.entry(), "g:dependency:9",
                                "runtimeElements", List.of("g:dependency:1")), nodes.get(1)),
                graph.edges(), producer, FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE);
        var rejected = importer.importBundle(root, inputs, build, set,
                manifest, platform, binaries, policy(), staleSelection);
        assertEquals(FilesystemResolutionBundleImporter.Status.PARTIAL, rejected.status());
        assertTrue(rejected.resolution().isEmpty());
        assertTrue(rejected.problems().stream().anyMatch(p -> p.reason()
                == FilesystemResolutionBundleImporter.Reason.RESOLVED_SELECTION_MISMATCH));
        var disconnected = FilesystemResolutionBundleImporter.ResolvedGraph.create(inputs.identity(),
                build.identity(), set, "compileClasspath", nodes, List.of(), producer,
                FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE);
        assertTrue(importer.importBundle(root, inputs, build, set, manifest, platform,
                binaries, policy(), disconnected).problems().stream().anyMatch(p -> p.reason()
                        == FilesystemResolutionBundleImporter.Reason.UNREACHABLE_RESOLVED_ARTIFACT));
        var forgedTransitive = FilesystemResolutionBundleImporter.ResolvedGraph.create(inputs.identity(),
                build.identity(), set, "compileClasspath", List.of(nodes.getFirst(),
                        new FilesystemResolutionBundleImporter.ResolvedNode(transitive.entry(), "g:other:2",
                                "runtimeElements", List.of())), graph.edges(), producer,
                FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE);
        assertTrue(importer.importBundle(root, inputs, build, set, manifest, platform,
                binaries, policy(), forgedTransitive).problems().stream().anyMatch(p -> p.reason()
                        == FilesystemResolutionBundleImporter.Reason.SELECTED_ARTIFACT_MISMATCH));
        var staleContext = FilesystemResolutionBundleImporter.ResolvedGraph.create(
                ContentDigest.sha256Utf8("older snapshot"), build.identity(), set,
                "compileClasspath", nodes, graph.edges(), producer,
                FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE);
        assertTrue(importer.importBundle(root, inputs, build, set, manifest, platform,
                binaries, policy(), staleContext).problems().stream().anyMatch(p -> p.reason()
                        == FilesystemResolutionBundleImporter.Reason.RESOLVED_CONTEXT_MISMATCH));
    }

    @Test void dynamicSelectorNeedsCapturedSelectionInsteadOfPickingNewest() throws Exception {
        var inputs = inputs("class Client { Dependency dep; }", "g:dependency:1.+");
        var build = build(inputs);
        assertTrue(build.modules().getFirst().dependencies().stream()
                .anyMatch(d -> d.notation().equals("g:dependency:1.+") && d.coordinate().isEmpty()));
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(),
                SourcePlanModel.Kind.MAIN);
        Path root = Files.createDirectory(temporary.resolve("dynamic"));
        byte[] platformBytes = zip("java/lang/Object.class", new byte[] {1});
        byte[] selectedBytes = zip("Dependency.class", new byte[] {2});
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "platform.jar", ContentDigest.sha256(platformBytes),
                Files.write(root.resolve("platform.jar"), platformBytes), PlatformInput.Format.JAR)));
        var selected = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "g:dependency:1.7@jar", ContentDigest.sha256(selectedBytes)),
                Files.write(root.resolve("selected.jar"), selectedBytes));
        var manifest = manifest(inputs, build, set, List.of(selected.entry()));
        var graph = FilesystemResolutionBundleImporter.ResolvedGraph.create(inputs.identity(),
                build.identity(), set, "compileClasspath", List.of(
                        new FilesystemResolutionBundleImporter.ResolvedNode(selected.entry(),
                                "g:dependency:1.7", "runtimeElements", List.of("g:dependency:1.+"))),
                List.of(), new VersionedIdentifier("test.trusted-capture", "1"),
                FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE);
        var importer = new FilesystemResolutionBundleImporter();
        assertEquals(FilesystemResolutionBundleImporter.Status.EXACT,
                importer.importBundle(root, inputs, build, set, manifest, platform,
                        List.of(selected), policy(), graph).status());
        var noBinding = FilesystemResolutionBundleImporter.ResolvedGraph.create(inputs.identity(),
                build.identity(), set, "compileClasspath", List.of(
                        new FilesystemResolutionBundleImporter.ResolvedNode(selected.entry(),
                                "g:dependency:1.7", "runtimeElements", List.of())),
                List.of(), graph.producer(), graph.trust());
        assertTrue(importer.importBundle(root, inputs, build, set, manifest, platform,
                List.of(selected), policy(), noBinding).problems().stream().anyMatch(p -> p.reason()
                        == FilesystemResolutionBundleImporter.Reason.UNBOUND_DECLARED_DEPENDENCY));
    }

    @Test void matchedDigestDoesNotMakeCorruptArchiveUsable() throws Exception {
        var inputs = inputs("class Client {}"); var build = build(inputs);
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(), SourcePlanModel.Kind.MAIN);
        Path root = Files.createDirectory(temporary.resolve("selected"));
        byte[] corrupt = "not an archive".getBytes(StandardCharsets.UTF_8);
        Path platformFile = Files.write(root.resolve("platform.jar"), corrupt);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "platform.jar", ContentDigest.sha256(corrupt), platformFile, PlatformInput.Format.JAR)));
        var result = new FilesystemResolutionBundleImporter().importBundle(root, inputs, build, set,
                manifest(inputs, build, set, List.of()), platform, List.of(), policy());
        assertEquals(FilesystemResolutionBundleImporter.Status.PARTIAL, result.status());
        assertTrue(result.resolution().isEmpty());
        assertTrue(result.problems().stream().anyMatch(p -> p.reason() == FilesystemResolutionBundleImporter.Reason.CORRUPT_ARCHIVE));
    }

    @Test void duplicateClassesAcrossSelectedJarsRetainOrderedCandidates() throws Exception {
        var inputs = inputs("class Client {}"); var build = build(inputs);
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(), SourcePlanModel.Kind.MAIN);
        Path root = Files.createDirectory(temporary.resolve("selected"));
        byte[] platformBytes = zip("java/lang/Object.class", new byte[] {1});
        Path platformFile = Files.write(root.resolve("platform.jar"), platformBytes);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "platform.jar", ContentDigest.sha256(platformBytes), platformFile, PlatformInput.Format.JAR)));
        byte[] firstBytes = zip("Dependency.class", new byte[] {2});
        byte[] secondBytes = zip("Dependency.class", new byte[] {3});
        var first = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "g:dependency:1@jar", ContentDigest.sha256(firstBytes)),
                Files.write(root.resolve("first.jar"), firstBytes));
        var second = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "g:shadow:1@jar", ContentDigest.sha256(secondBytes)),
                Files.write(root.resolve("second.jar"), secondBytes));
        var binaries = List.of(first, second);
        var result = new FilesystemResolutionBundleImporter().importBundle(root, inputs, build, set,
                manifest(inputs, build, set, binaries.stream().map(BinaryInput::entry).toList()),
                platform, binaries, policy());
        assertEquals(FilesystemResolutionBundleImporter.Status.EXACT, result.status(), result.problems().toString());
        assertEquals(1, result.classCollisions().size());
        assertEquals(List.of("g:dependency:1@jar", "g:shadow:1@jar"),
                result.classCollisions().getFirst().orderedCandidates().stream()
                        .map(FilesystemResolutionBundleImporter.SelectedClassEntry::artifactLogicalId).toList());
    }

    @Test void outsideSelectionAndStaleSourceContextAreRejected() throws Exception {
        var inputs = inputs("class Client {}"); var build = build(inputs);
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(), SourcePlanModel.Kind.MAIN);
        byte[] bytes = zip("java/lang/Object.class", new byte[] {1});
        Path root = Files.createDirectory(temporary.resolve("selected"));
        Path outside = Files.write(temporary.resolve("outside.jar"), bytes);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "outside.jar", ContentDigest.sha256(bytes), outside, PlatformInput.Format.JAR)));
        var importer = new FilesystemResolutionBundleImporter();
        var manifest = manifest(inputs, build, set, List.of());
        var result = importer.importBundle(root, inputs, build, set, manifest, platform, List.of(), policy());
        assertEquals(FilesystemResolutionBundleImporter.Status.PARTIAL, result.status());
        assertTrue(result.problems().stream().anyMatch(p -> p.reason() == FilesystemResolutionBundleImporter.Reason.OUTSIDE_SELECTION));
        assertThrows(IllegalArgumentException.class, () -> importer.importBundle(root,
                inputs("class Client { int changed; }"), build, set, manifest, platform, List.of(), policy()));
    }

    @Test void producerClaimWithoutPolicyTrustCannotCreateExactResolution() throws Exception {
        var inputs = inputs("class Client {}"); var build = build(inputs);
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(), SourcePlanModel.Kind.MAIN);
        byte[] bytes = zip("java/lang/Object.class", new byte[] {1});
        Path root = Files.createDirectory(temporary.resolve("selected"));
        Path platformFile = Files.write(root.resolve("platform.jar"), bytes);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "platform.jar", ContentDigest.sha256(bytes), platformFile, PlatformInput.Format.JAR)));
        var manifest = manifest(inputs, build, set, List.of());
        var result = new FilesystemResolutionBundleImporter().importBundle(root, inputs, build, set,
                manifest, platform, List.of(), new FilesystemResolutionBundleImporter.Policy(
                        8, 10000, 20000, 100, 20000, Set.of()));
        assertTrue(result.resolution().isEmpty());
        assertTrue(result.problems().stream().anyMatch(p -> p.reason() == FilesystemResolutionBundleImporter.Reason.UNTRUSTED_CAPTURE));
    }

    @Test void archiveExpansionAndReleaseSpecificEntriesRemainExplicit() throws Exception {
        var inputs = inputs("class Client {}"); var build = build(inputs);
        var set = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(), SourcePlanModel.Kind.MAIN);
        Path root = Files.createDirectory(temporary.resolve("selected"));
        byte[] ordinary = zip("java/lang/Object.class", new byte[] {1, 2, 3});
        Path ordinaryFile = Files.write(root.resolve("ordinary.jar"), ordinary);
        var ordinaryPlatform = PlatformInput.create(21, "fixture", "authored", List.of(new PlatformInput.Artifact(
                "ordinary.jar", ContentDigest.sha256(ordinary), ordinaryFile, PlatformInput.Format.JAR)));
        var importer = new FilesystemResolutionBundleImporter();
        var limited = importer.importBundle(root, inputs, build, set,
                manifest(inputs, build, set, List.of()), ordinaryPlatform, List.of(),
                new FilesystemResolutionBundleImporter.Policy(8, 10000, 20000, 100, 1,
                        Set.of(new VersionedIdentifier("test.trusted-capture", "1"))));
        assertTrue(limited.problems().stream().anyMatch(p -> p.reason()
                == FilesystemResolutionBundleImporter.Reason.ARCHIVE_EXPANSION_LIMIT));
        byte[] releaseSpecific = multiRelease();
        Path releaseFile = Files.write(root.resolve("release.jar"), releaseSpecific);
        var binary = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "g:dependency:1@jar", ContentDigest.sha256(releaseSpecific)), releaseFile);
        var result = importer.importBundle(root, inputs, build, set,
                manifest(inputs, build, set, List.of(binary.entry())), ordinaryPlatform,
                List.of(binary), policy());
        assertEquals(FilesystemResolutionBundleImporter.Status.EXACT, result.status(), result.problems().toString());
        assertEquals(1, result.selectedClasses().size());
        var selected = result.selectedClasses().getFirst();
        assertEquals("Dependency.class", selected.logicalClassEntry());
        assertEquals("META-INF/versions/21/Dependency.class", selected.physicalEntry());
        assertEquals(21, selected.release());
        assertEquals(ContentDigest.sha256(new byte[] {5}), selected.entryDigest());
        byte[] alternateManifest = multiRelease("meta-inf/manifest.mf");
        Path alternateFile = Files.write(root.resolve("alternate.jar"), alternateManifest);
        var alternate = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "g:dependency:1@jar", ContentDigest.sha256(alternateManifest)), alternateFile);
        var alternateResult = importer.importBundle(root, inputs, build, set,
                manifest(inputs, build, set, List.of(alternate.entry())), ordinaryPlatform,
                List.of(alternate), policy());
        assertEquals(FilesystemResolutionBundleImporter.Status.EXACT, alternateResult.status());
        assertEquals(21, alternateResult.selectedClasses().getFirst().release());
    }

    private static FilesystemResolutionBundleImporter.Policy policy() {
        return new FilesystemResolutionBundleImporter.Policy(8, 10000, 20000, 100, 20000,
                Set.of(new VersionedIdentifier("test.trusted-capture", "1")));
    }
    private static FilesystemResolutionBundleImporter.Manifest manifest(RepositoryInputs inputs,
            UniversalBuildModel build, UniversalSourceIngestion.SourceSet set, List<ClasspathEntry> entries) {
        return FilesystemResolutionBundleImporter.Manifest.create(inputs.identity(), build.identity(), set,
                entries, new VersionedIdentifier("test.trusted-capture", "1"),
                FilesystemResolutionBundleImporter.Trust.TRUSTED_CAPTURE);
    }
    private static UniversalBuildModel build(RepositoryInputs inputs) {
        return new UniversalBuildIngestion().ingest(inputs,
                new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21), Optional.of("UTF-8"),
                        100, 10000, 1000, 16), Optional.empty(), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16));
    }
    private static RepositoryInputs inputs(String source) {
        return inputs(source, "g:dependency:1");
    }
    private static RepositoryInputs inputs(String source, String dependencyNotation) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/import.git");
        var module = ModuleDescriptor.create(repo, ".", "fixture");
        Map<String, SourceInput> files = new TreeMap<>();
        Map.of("build.gradle", "plugins { java }\ndependencies { implementation '" + dependencyNotation + "'\n"
                + "testImplementation 'g:test:1'\nannotationProcessor 'g:processor:1'\nruntimeOnly 'g:runtime:1' }\n",
                "src/main/java/Client.java", source).forEach((path, value) -> {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            files.put(path, new SourceInput(SourceDocument.create(repo, module, path,
                    ContentDigest.sha256(bytes), SourceClassification.MAIN), bytes));
        });
        var docs = files.values().stream().map(SourceInput::document).toList();
        return new RepositoryInputs(RepositorySnapshot.create(repo, Optional.empty(), false,
                docs.stream().map(SnapshotFile::from).toList(), docs), files);
    }
    private static byte[] zip(String name, byte[] content) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry(name)); archive.write(content); archive.closeEntry();
        }
        return output.toByteArray();
    }
    private static byte[] zipWithResource(byte[] resource) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry("Dependency.class"));
            archive.write(new byte[] {4, 5, 6}); archive.closeEntry();
            archive.putNextEntry(new ZipEntry(
                    "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
            archive.write(resource); archive.closeEntry();
        }
        return output.toByteArray();
    }
    private static byte[] multiRelease() throws Exception { return multiRelease("META-INF/MANIFEST.MF"); }
    private static byte[] multiRelease(String manifestName) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var archive = new ZipOutputStream(output)) {
            for (var entry : List.of(
                    Map.entry(manifestName, "Manifest-Version: 1.0\nMulti-Release: true\n\n".getBytes(StandardCharsets.UTF_8)),
                    Map.entry("Dependency.class", new byte[] {4}),
                    Map.entry("META-INF/versions/21/Dependency.class", new byte[] {5}),
                    Map.entry("META-INF/versions/22/Dependency.class", new byte[] {6}))) {
                archive.putNextEntry(new ZipEntry(entry.getKey()));
                archive.write(entry.getValue()); archive.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
