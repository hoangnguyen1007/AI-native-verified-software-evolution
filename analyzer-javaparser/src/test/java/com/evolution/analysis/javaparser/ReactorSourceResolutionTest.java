package com.evolution.analysis.javaparser;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorSourceResolutionTest {
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate(
            "https://example.test/reactor-source.git");
    private static final ModuleDescriptor APP = ModuleDescriptor.create(REPOSITORY, "app", "app");
    private static final ModuleDescriptor COMMON = ModuleDescriptor.create(REPOSITORY, "common-lib", "common-lib");
    @TempDir Path temporary;

    @Test
    void siblingSourcesResolveAtTheirClasspathPositionWithoutEnteringTheCallerDenominator() throws Exception {
        SourceInput app = source(APP, "app/src/main/java/app/App.java", """
                package app;
                class App {
                    common.Common value = new common.Common();
                    String run() { return common.Common.message(); }
                }
                """);
        SourceInput common = source(COMMON, "common-lib/src/main/java/common/Common.java", """
                package common;
                public class Common {
                    public static String message() { return "ok"; }
                }
                """);
        FrontendRequest request = request(app, List.of(common), List.of(), 0);

        FrontendResult result = new JavaParserFrontend().analyze(request);

        RelationshipOccurrence call = result.occurrences().stream()
                .filter(value -> value.relationship().kind().value().equals("java.calls"))
                .findFirst().orElseThrow();
        assertEquals(SemanticStatus.RESOLVED, call.status());
        EntityIdentity target = ((RelationshipTarget.Resolved) call.relationship().target()).target();
        Entity method = result.declarations().stream().map(DeclarationRecord::entity)
                .filter(value -> value.identity().equals(target)).findFirst().orElseThrow();
        assertEquals(EntityOrigin.PROJECT, method.origin());
        assertEquals(EntityScope.project(COMMON.identity()), method.stableScope());
        assertTrue(method.declaration().isPresent(), "source-level reactor targets retain exact source evidence");

        CategoryCoverage declares = result.coverage().stream()
                .filter(value -> value.category().value().equals("java.declares"))
                .findFirst().orElseThrow();
        assertEquals(3, declares.attempted(), "resolution-only sibling declarations are not analyzed twice");
        assertEquals(FrontendResult.State.COMPLETED, result.state(), () -> result.diagnostics().toString());
    }

    @Test
    void malformedSiblingSourceReturnsPartialResultWithClosedDocumentCoverage() throws Exception {
        SourceInput app = source(APP, "app/src/main/java/app/App.java", "package app; class App {}");
        SourceInput broken = source(COMMON, "common-lib/src/main/java/common/Broken.java",
                "package common; public class Broken {");
        FrontendRequest request = request(app, List.of(broken), List.of(), 0);

        FrontendResult result = assertDoesNotThrow(() -> new JavaParserFrontend().analyze(request));

        assertEquals(FrontendResult.State.PARTIAL, result.state());
        assertTrue(result.diagnostics().stream()
                .anyMatch(value -> value.code().equals("java.reactor-source-parse-error")));
        assertEquals(Set.of(app.document().identity(), broken.document().identity()),
                result.sources().stream().map(SourceOutcome::document).collect(java.util.stream.Collectors.toSet()));
        SourceOutcome outcome = result.sources().stream()
                .filter(value -> value.document().equals(broken.document().identity()))
                .findFirst().orElseThrow();
        assertEquals(SourceOutcome.State.ERROR, outcome.state());
    }

    @Test
    void duplicateTypeWithinOneSiblingInputIsRejectedDeterministically() throws Exception {
        SourceInput app = source(APP, "app/src/main/java/app/App.java", "package app; class App {}");
        SourceInput first = source(COMMON, "common-lib/src/main/java/common/First.java",
                "package common; public class Duplicate {}");
        SourceInput second = source(COMMON, "common-lib/src/main/java/common/Second.java",
                "package common; public class Duplicate {}");
        FrontendRequest request = request(app, List.of(first, second), List.of(), 0);

        FrontendInputException failure = assertThrows(
                FrontendInputException.class, () -> new JavaParserFrontend().analyze(request));

        assertEquals("frontend.duplicate-reactor-source-type", failure.diagnostic().code());
    }

    @Test
    void siblingInheritanceAndRecordComponentsRetainProjectSemantics() throws Exception {
        SourceInput app = source(APP, "app/src/main/java/app/App.java", """
                package app;
                class App extends common.Base implements common.Marker {
                    int coordinate(common.Point point) { return point.x(); }
                }
                """);
        List<SourceInput> common = List.of(
                source(COMMON, "common-lib/src/main/java/common/Base.java",
                        "package common; public class Base {}"),
                source(COMMON, "common-lib/src/main/java/common/Marker.java",
                        "package common; public interface Marker {}"),
                source(COMMON, "common-lib/src/main/java/common/Point.java",
                        "package common; public record Point(int x) {}"));

        FrontendResult result = new JavaParserFrontend().analyze(request(app, common, List.of(), 0));

        for (String relationship : List.of("java.extends", "java.implements", "java.calls")) {
            assertTrue(result.occurrences().stream().anyMatch(value ->
                    value.relationship().kind().value().equals(relationship)
                            && value.status() == SemanticStatus.RESOLVED), relationship);
        }
        RelationshipOccurrence call = result.occurrences().stream()
                .filter(value -> value.relationship().kind().value().equals("java.calls"))
                .findFirst().orElseThrow();
        EntityIdentity target = ((RelationshipTarget.Resolved) call.relationship().target()).target();
        DeclarationRecord accessor = result.declarations().stream()
                .filter(value -> value.entity().identity().equals(target)).findFirst().orElseThrow();
        assertEquals(EntityScope.project(COMMON.identity()), accessor.entity().stableScope());
        assertEquals(EntityKind.METHOD, accessor.entity().kind());
        assertEquals("java.record-component-accessor", accessor.derivation().method().id());
        assertTrue(result.declarations().stream().anyMatch(value ->
                value.entity().kind() == EntityKind.RECORD_COMPONENT
                        && value.entity().stableScope().equals(EntityScope.project(COMMON.identity()))));
        assertFalse(result.diagnostics().stream().anyMatch(value -> value.code().equals("java.implicit-callable")));
    }

    @Test
    void siblingSourceShadowsLaterBinaryAtItsExactClasspathPosition() throws Exception {
        SourceInput app = source(APP, "app/src/main/java/app/App.java", """
                package app;
                class App { String run() { return common.Common.message(); } }
                """);
        SourceInput common = source(COMMON, "common-lib/src/main/java/common/Common.java", """
                package common;
                public class Common { public static String message() { return "source"; } }
                """);
        BinaryInput binary = jar("common-binary", """
                package common;
                public class Common { public static String message() { return "binary"; } }
                """);

        FrontendResult result = new JavaParserFrontend().analyze(
                request(app, List.of(common), List.of(binary), 0));

        RelationshipOccurrence call = result.occurrences().stream()
                .filter(value -> value.relationship().kind().value().equals("java.calls"))
                .findFirst().orElseThrow();
        EntityIdentity target = ((RelationshipTarget.Resolved) call.relationship().target()).target();
        Entity selected = result.declarations().stream().map(DeclarationRecord::entity)
                .filter(value -> value.identity().equals(target)).findFirst().orElseThrow();
        assertEquals(EntityOrigin.PROJECT, selected.origin());
        assertEquals(EntityScope.project(COMMON.identity()), selected.stableScope());
        assertTrue(result.diagnostics().stream()
                .anyMatch(value -> value.code().equals("java.duplicate-binary-type")));
    }

    private static SourceInput source(ModuleDescriptor module, String path, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new SourceInput(SourceDocument.create(
                REPOSITORY, module, path, ContentDigest.sha256(bytes), SourceClassification.MAIN), bytes);
    }

    private BinaryInput jar(String name, String code) throws Exception {
        Path root = temporary.resolve(name);
        Path packageRoot = root.resolve("common");
        Files.createDirectories(packageRoot);
        Path source = packageRoot.resolve("Common.java");
        Files.writeString(source, code, StandardCharsets.UTF_8);
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-proc:none", "-encoding", "UTF-8",
                "-d", root.toString(), source.toString());
        assertEquals(0, exit);
        Path jar = temporary.resolve(name + ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar));
                var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                var entry = new JarEntry(root.relativize(file).toString().replace('\\', '/'));
                entry.setTime(0L);
                output.putNextEntry(entry);
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return new BinaryInput(new ClasspathEntry(
                ClasspathEntryKind.DEPENDENCY, "fixture:" + name + ":1",
                ContentDigest.sha256(Files.readAllBytes(jar))), jar);
    }

    private static FrontendRequest request(SourceInput app, List<SourceInput> common,
            List<BinaryInput> dependencies, int reactorOrder) throws Exception {
        Path modules = Path.of(System.getProperty("java.home")).resolve("lib/modules");
        PlatformInput platform = PlatformInput.create(Runtime.version().feature(), Runtime.version().toString(),
                System.getProperty("java.vendor"), List.of(new PlatformInput.Artifact(
                        "lib/modules", ContentDigest.sha256(Files.readAllBytes(modules)), modules,
                        PlatformInput.Format.RUNTIME_MODULES)));
        ReactorSourceInput reactor = ReactorSourceInput.create(
                COMMON.identity(), SourceClassification.MAIN, reactorOrder, common);
        FrontendPlan plan = new FrontendPlan(Optional.of(21), Optional.of(21), false,
                ContentDigest.sha256Utf8("reactor-classpath"), ContentDigest.sha256Utf8("reactor-decoding"));
        List<SourceDocument> documents = new ArrayList<>();
        documents.add(app.document());
        common.stream().map(SourceInput::document).forEach(documents::add);
        RepositorySnapshot snapshot = RepositorySnapshot.create(REPOSITORY, Optional.empty(), false,
                documents.stream().map(SnapshotFile::from).toList(), documents);
        ManifestComponent component = new ManifestComponent(
                new VersionedIdentifier("test.fixture", "1"), ContentDigest.sha256Utf8("reactor-fixture"));
        AnalysisManifest manifest = AnalysisManifest.create(
                new VersionedIdentifier("analysis.manifest", "2"), snapshot, List.of(APP, COMMON),
                java.util.stream.Stream.concat(java.util.stream.Stream.of(platform.entry()),
                        dependencies.stream().map(BinaryInput::entry)).toList(),
                AnalysisConfiguration.create(new VersionedIdentifier("analysis.configuration", "2"),
                        FrontendRequest.options(plan, APP.identity(), SourceClassification.MAIN,
                                List.of(app.document()), platform, List.of(reactor))),
                component, component, component);
        return new FrontendRequest(manifest, APP.identity(), SourceClassification.MAIN, plan,
                List.of(app), platform, dependencies, List.of(reactor));
    }
}
