package com.evolution.analysis.buildmodel;

import com.evolution.analysis.contract.analysis.ManifestComponent;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.frontend.PlatformInput;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.ingestion.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapturedSourcePlanImporterTest {
    private static final VersionedIdentifier PRODUCER = new VersionedIdentifier("test.captured-plan", "1");
    private static final byte[] CAPTURE = "complete selected build model".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final CapturedSourcePlanImporter.Policy POLICY =
            new CapturedSourcePlanImporter.Policy(Set.of(PRODUCER), 16, 1024);

    @Test void selectedSourcePlanKeepsOwnershipAndCanReachExactInputWithSeparateResolution() {
        var inputs = inputs("class Client {}");
        var imported = new CapturedSourcePlanImporter().importBundle(inputs, bundle(inputs), POLICY);
        assertEquals(CapturedSourcePlanImporter.Status.IMPORTED, imported.status());
        var build = imported.build().orElseThrow();
        assertEquals(UniversalBuildModel.Tool.IMPORTED_SOURCE_PLAN, build.modules().getFirst().tool());
        var module = build.modules().getFirst();
        assertEquals("app", module.descriptor().path());
        assertTrue(module.coordinate().isEmpty());
        var key = new UniversalSourceIngestion.SourceSet(module.descriptor().identity(), SourcePlanModel.Kind.MAIN);
        var platform = PlatformInput.create(21, "fixture-21", "authored", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("fixture"),
                        Path.of("unused.jmod"), PlatformInput.Format.JMOD)));
        var resolution = new UniversalSourceIngestion.Resolution(platform, List.of(),
                ContentDigest.sha256Utf8("separately-verified-exact-classpath"));
        var component = new ManifestComponent(new VersionedIdentifier("test.component", "1"),
                ContentDigest.sha256Utf8("component"));
        var sources = new UniversalSourceIngestion().assemble(inputs, build, Map.of(key, resolution),
                component, component, component);
        assertEquals(UniversalSourceIngestion.Status.OWNED, sources.sources().getFirst().status());
        assertTrue(sources.outcomes().stream().filter(o -> o.sourceSet().equals(key))
                .anyMatch(o -> o.request().isPresent()), sources.issues().toString());
    }

    @Test void staleOrUntrustedCaptureAndOverlappingRootsAreRejected() {
        var inputs = inputs("class Client {}");
        var importer = new CapturedSourcePlanImporter();
        assertEquals(CapturedSourcePlanImporter.Status.REJECTED,
                importer.importBundle(inputs("class Client { int x; }"), bundle(inputs), POLICY).status());
        var wrongBytes = CapturedSourcePlanImporter.Bundle.create(inputs.identity(), "app", PRODUCER,
                "capture", CAPTURE, ContentDigest.sha256Utf8("wrong"),
                List.of("app/src"), List.of("app/test"), 21, 21, 21, "UTF-8");
        assertEquals(CapturedSourcePlanImporter.Status.REJECTED,
                importer.importBundle(inputs, wrongBytes, POLICY).status());
        var overlap = CapturedSourcePlanImporter.Bundle.create(inputs.identity(), "app", PRODUCER,
                "capture", CAPTURE, ContentDigest.sha256(CAPTURE),
                List.of("app"), List.of("app/test"), 21, 21, 21, "UTF-8");
        assertEquals(CapturedSourcePlanImporter.Status.REJECTED,
                importer.importBundle(inputs, overlap, POLICY).status());
        assertEquals(CapturedSourcePlanImporter.Status.REJECTED,
                importer.importBundle(inputs, bundle(inputs),
                        new CapturedSourcePlanImporter.Policy(Set.of(), 16, 1024)).status());
    }

    @Test void namedCustomTestSetDoesNotMergeWithMainOrStandardTest() {
        var inputs = IngestionFixtures.inputs(Map.of("BUILD", "custom build",
                "app/src/Client.java", "class Client {}",
                "app/integration/Spec.java", "class Spec {}"));
        var custom = new CapturedSourcePlanImporter.CustomSet("integrationTest",
                SourceClassification.TEST, List.of("app/integration"));
        var bundle = CapturedSourcePlanImporter.Bundle.create(inputs.identity(), "app", PRODUCER,
                "capture", CAPTURE, ContentDigest.sha256(CAPTURE),
                List.of("app/src"), List.of("app/test"), List.of(custom), 21, 21, 21, "UTF-8");
        var build = new CapturedSourcePlanImporter().importBundle(inputs, bundle, POLICY).build().orElseThrow();
        assertEquals(3, build.modules().getFirst().sourcePlan().sourceSets().size());
        var module = build.modules().getFirst();
        var key = new UniversalSourceIngestion.SourceSet(module.descriptor().identity(),
                SourcePlanModel.Kind.CUSTOM, "integrationTest");
        var platform = PlatformInput.create(21, "fixture-21", "authored", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("fixture"),
                        Path.of("unused.jmod"), PlatformInput.Format.JMOD)));
        var component = new ManifestComponent(new VersionedIdentifier("test.component", "1"),
                ContentDigest.sha256Utf8("component"));
        var sources = new UniversalSourceIngestion().assemble(inputs, build,
                Map.of(key, new UniversalSourceIngestion.Resolution(platform, List.of(),
                        ContentDigest.sha256Utf8("separate-integration-classpath"))),
                component, component, component);
        assertEquals(2, sources.sources().size());
        assertTrue(sources.outcomes().stream().filter(o -> o.sourceSet().equals(key))
                .anyMatch(o -> o.request().isPresent()), sources.issues().toString());
        assertTrue(sources.outcomes().stream().filter(o -> o.sourceSet().kind() == SourcePlanModel.Kind.MAIN)
                .allMatch(o -> o.request().isEmpty()));
    }

    private static RepositoryInputs inputs(String source) {
        return IngestionFixtures.inputs(Map.of("BUILD", "java_library(name = 'app')",
                "app/src/Client.java", source));
    }
    private static CapturedSourcePlanImporter.Bundle bundle(RepositoryInputs inputs) {
        return CapturedSourcePlanImporter.Bundle.create(inputs.identity(), "app", PRODUCER,
                "capture", CAPTURE, ContentDigest.sha256(CAPTURE),
                List.of("app/src"), List.of("app/test"), 21, 21, 21, "UTF-8");
    }
}
