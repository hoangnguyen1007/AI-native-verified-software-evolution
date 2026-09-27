package com.evolution.analysis.ingestion;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.ManifestComponent;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.frontend.PlatformInput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedEvidenceImportTest {
    private static final VersionedIdentifier GENERATOR = new VersionedIdentifier("test.generator", "1");

    @Test void validLineageImportsGeneratedJavaIntoNewCapturedContext() {
        var inputs = inputs("class Client { Generated value = new Generated(1); }");
        var build = build(inputs);
        var bundle = bundle(inputs, build, ContentDigest.sha256Utf8(
                "class Client { Generated value = new Generated(1); }"));
        var result = new GeneratedSourceBundleImporter().importBundle(inputs, build, bundle, policy(true));
        assertEquals(GeneratedSourceBundleImporter.Status.IMPORTED, result.status(), result.problems().toString());
        var next = result.inputs().orElseThrow();
        assertNotEquals(inputs.snapshot().identity(), next.snapshot().identity());
        assertEquals(SourceClassification.GENERATED_MAIN,
                next.files().get("src/main/java/Generated.java").document().classification());
        assertEquals(result.identity(), new GeneratedSourceBundleImporter().importBundle(inputs, build, bundle,
                policy(true)).identity());
        assertEquals(next.identity(), result.inputs().orElseThrow().identity());
        var rebuilt = build(next);
        var set = sourceSet(rebuilt);
        var platform = PlatformInput.create(21, "fixture", "authored", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("platform"),
                        Path.of("unused.jmod"), PlatformInput.Format.JMOD)));
        var component = new ManifestComponent(new VersionedIdentifier("test.components", "1"),
                ContentDigest.sha256Utf8("components"));
        var assembled = new UniversalSourceIngestion().assemble(next, rebuilt,
                Map.of(set, new UniversalSourceIngestion.Resolution(platform, List.of(),
                        ContentDigest.sha256Utf8("exact-classpath"))), component, component, component);
        var request = assembled.outcomes().stream().filter(o -> o.sourceSet().equals(set))
                .findFirst().orElseThrow().request().orElseThrow();
        assertEquals(SourceClassification.GENERATED_MAIN, request.sources().stream()
                .filter(s -> s.document().path().endsWith("Generated.java"))
                .findFirst().orElseThrow().document().classification());
    }

    @Test void staleSourceOrWrongOutputHashCannotPromoteGeneratedDeclaration() {
        var inputs = inputs("class Client { Generated value = new Generated(1); }"); var build = build(inputs);
        var stale = bundle(inputs, build, ContentDigest.sha256Utf8("older Client"));
        var rejected = new GeneratedSourceBundleImporter().importBundle(inputs, build, stale, policy(true));
        assertEquals(GeneratedSourceBundleImporter.Status.REJECTED, rejected.status());
        assertTrue(rejected.inputs().isEmpty());
        assertTrue(rejected.problems().stream().anyMatch(p -> p.reason() == GeneratedSourceBundleImporter.Reason.STALE_INPUT));
        var bytes = "class Generated { Generated(int x) {} }".getBytes(StandardCharsets.UTF_8);
        var bad = GeneratedSourceBundleImporter.Bundle.create(inputs.identity(), build.identity(), GENERATOR,
                ContentDigest.sha256Utf8("recipe"),
                List.of(new GeneratedSourceBundleImporter.InputReference("src/main/java/Client.java",
                        inputs.files().get("src/main/java/Client.java").document().contentDigest())),
                List.of(new GeneratedSourceBundleImporter.Output("src/main/java/Generated.java", bytes,
                        ContentDigest.sha256Utf8("wrong"), sourceSet(build))));
        var wrong = new GeneratedSourceBundleImporter().importBundle(inputs, build, bad, policy(true));
        assertTrue(wrong.problems().stream().anyMatch(p -> p.reason() == GeneratedSourceBundleImporter.Reason.DIGEST_MISMATCH));
    }

    @Test void producerIdentityAloneCannotAuthorizeImport() {
        var inputs = inputs("class Client {}"); var build = build(inputs);
        var bundle = bundle(inputs, build, inputs.files().get("src/main/java/Client.java").document().contentDigest());
        var result = new GeneratedSourceBundleImporter().importBundle(inputs, build, bundle, policy(false));
        assertTrue(result.inputs().isEmpty());
        assertTrue(result.problems().stream().anyMatch(p -> p.reason() == GeneratedSourceBundleImporter.Reason.PRODUCER_NOT_ALLOWED));
    }

    private static RepositoryInputs inputs(String source) {
        return IngestionFixtures.inputs(Map.of("build.gradle", "plugins { java }\n",
                "src/main/java/Client.java", source));
    }
    private static UniversalBuildModel build(RepositoryInputs inputs) {
        return new UniversalBuildIngestion().ingest(inputs,
                new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21), Optional.of("UTF-8"),
                        100, 10000, 1000, 16), Optional.empty(), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16));
    }
    private static UniversalSourceIngestion.SourceSet sourceSet(UniversalBuildModel build) {
        return new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(),
                SourcePlanModel.Kind.MAIN);
    }
    private static GeneratedSourceBundleImporter.Bundle bundle(RepositoryInputs inputs,
            UniversalBuildModel build, ContentDigest sourceDigest) {
        byte[] bytes = "class Generated { Generated(int x) {} }".getBytes(StandardCharsets.UTF_8);
        return GeneratedSourceBundleImporter.Bundle.create(inputs.identity(), build.identity(), GENERATOR,
                ContentDigest.sha256Utf8("recipe"),
                List.of(new GeneratedSourceBundleImporter.InputReference("src/main/java/Client.java", sourceDigest)),
                List.of(new GeneratedSourceBundleImporter.Output("src/main/java/Generated.java", bytes,
                        ContentDigest.sha256(bytes), sourceSet(build))));
    }
    private static GeneratedSourceBundleImporter.Policy policy(boolean allow) {
        return new GeneratedSourceBundleImporter.Policy(allow ? Set.of(GENERATOR) : Set.of(), 8, 10000);
    }
}
