package com.evolution.analysis.javaparser;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.ManifestComponent;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.contract.semantic.RelationshipTarget;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedConstructorIntegrationTest {
    @Test void importedGeneratedConstructorResolvesClientWithoutRunningProcessor() {
        var original = inputs(); var build = build(original);
        byte[] generated = "class Generated { Generated(int value) {} }".getBytes(StandardCharsets.UTF_8);
        var producer = new VersionedIdentifier("test.authored-generator", "1");
        var platform = TestInputs.request("class Platform {}").platform();
        var component = new ManifestComponent(new VersionedIdentifier("test.components", "1"),
                ContentDigest.sha256Utf8("components"));
        var beforeSet = sourceSet(build);
        var beforeAssembly = new UniversalSourceIngestion().assemble(original, build,
                Map.of(beforeSet, new UniversalSourceIngestion.Resolution(platform, List.of(),
                        ContentDigest.sha256Utf8("captured-empty-classpath"))),
                component, component, component);
        var beforeRequest = beforeAssembly.outcomes().stream().filter(o -> o.sourceSet().equals(beforeSet))
                .findFirst().orElseThrow().request().orElseThrow();
        var before = new JavaParserFrontend().analyze(beforeRequest);
        assertTrue(before.occurrences().stream().filter(o -> o.relationship().kind().value()
                .equals("java.constructor-calls")).noneMatch(o -> o.status() == SemanticStatus.RESOLVED));
        var bundle = GeneratedSourceBundleImporter.Bundle.create(original.identity(), build.identity(),
                producer, ContentDigest.sha256Utf8("authored-recipe"),
                List.of(new GeneratedSourceBundleImporter.InputReference("src/main/java/Client.java",
                        original.files().get("src/main/java/Client.java").document().contentDigest())),
                List.of(new GeneratedSourceBundleImporter.Output("src/main/java/Generated.java", generated,
                        ContentDigest.sha256(generated), sourceSet(build))));
        var imported = new GeneratedSourceBundleImporter().importBundle(original, build, bundle,
                new GeneratedSourceBundleImporter.Policy(Set.of(producer), 4, 1000));
        var next = imported.inputs().orElseThrow();
        var rebuilt = build(next);
        var key = sourceSet(rebuilt);
        var assembled = new UniversalSourceIngestion().assemble(next, rebuilt,
                Map.of(key, new UniversalSourceIngestion.Resolution(platform, List.of(),
                        ContentDigest.sha256Utf8("captured-empty-classpath"))),
                component, component, component);
        var request = assembled.outcomes().stream().filter(o -> o.sourceSet().equals(key))
                .findFirst().orElseThrow().request().orElseThrow();
        assertEquals(SourceClassification.GENERATED_MAIN, request.sources().stream()
                .filter(s -> s.document().path().endsWith("Generated.java"))
                .findFirst().orElseThrow().document().classification());
        var analyzed = new JavaParserFrontend().analyze(request);
        assertTrue(analyzed.observations().stream().anyMatch(o -> o.category().value().equals("java.constructor-calls")
                && o.attribution() == SemanticStatus.RESOLVED), analyzed.diagnostics().toString());
        var resolved = analyzed.occurrences().stream().filter(o -> o.relationship().kind().value()
                .equals("java.constructor-calls") && o.status() == SemanticStatus.RESOLVED)
                .findFirst().orElseThrow();
        var target = ((RelationshipTarget.Resolved) resolved.relationship().target()).target();
        var generatedDocument = request.sources().stream().filter(s -> s.document().path().endsWith("Generated.java"))
                .findFirst().orElseThrow().document().identity();
        assertTrue(analyzed.declarations().stream().anyMatch(d -> d.entity().identity().equals(target)
                && d.entity().declaration().stream().anyMatch(span -> span.document().equals(generatedDocument))));
    }

    private static UniversalSourceIngestion.SourceSet sourceSet(UniversalBuildModel build) {
        return new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(),
                SourcePlanModel.Kind.MAIN);
    }
    private static UniversalBuildModel build(RepositoryInputs input) {
        return new UniversalBuildIngestion().ingest(input,
                new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21), Optional.of("UTF-8"),
                        100, 10000, 1000, 16), Optional.empty(), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16));
    }
    private static RepositoryInputs inputs() {
        var repo = TestInputs.REPO; var module = TestInputs.MODULE;
        Map<String, SourceInput> files = new TreeMap<>();
        Map.of("build.gradle", "plugins { java }\n",
                "src/main/java/Client.java", "class Client { Generated value = new Generated(1); }")
                .forEach((path, value) -> {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    files.put(path, new SourceInput(SourceDocument.create(repo, module, path,
                            ContentDigest.sha256(bytes), SourceClassification.MAIN), bytes));
                });
        var docs = files.values().stream().map(SourceInput::document).toList();
        return new RepositoryInputs(RepositorySnapshot.create(repo, Optional.empty(), false,
                docs.stream().map(SnapshotFile::from).toList(), docs), files);
    }
}
