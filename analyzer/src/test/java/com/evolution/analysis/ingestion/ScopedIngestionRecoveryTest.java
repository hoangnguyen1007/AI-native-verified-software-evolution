package com.evolution.analysis.ingestion;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.ManifestComponent;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.frontend.PlatformInput;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScopedIngestionRecoveryTest {
    @Test void rootBuildFailureCannotBeScopedAwayFromNestedProject() {
        var inputs = IngestionFixtures.inputs(Map.of(
                "build.gradle", "dependencies { implementation 'unfinished",
                "src/main/java/Root.java", "class Root {}",
                "child/build.gradle", "plugins { java }",
                "child/src/main/java/Child.java", "class Child {}"));
        var build = new UniversalBuildIngestion().ingest(inputs, policy(), Optional.empty(), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16));
        var child = build.modules().stream().filter(m -> m.descriptor().path().equals("child"))
                .findFirst().orElseThrow();
        var key = new UniversalSourceIngestion.SourceSet(child.descriptor().identity(), SourcePlanModel.Kind.MAIN);
        var result = new UniversalSourceIngestion().assemble(inputs, build, Map.of(key, resolution()),
                component(), component(), component());
        assertTrue(result.outcomes().stream().filter(o -> o.sourceSet().equals(key))
                .allMatch(o -> o.request().isEmpty()));
    }

    @Test void sharedSettingsKeepFailedProjectFootprintBroad() {
        var inputs = IngestionFixtures.inputs(Map.of(
                "settings.gradle", "include ':good', ':bad'\n",
                "good/build.gradle", "plugins { java }\n",
                "good/src/main/java/Good.java", "class Good {}",
                "bad/build.gradle", "dependencies { implementation 'unfinished",
                "bad/src/main/java/Bad.java", "class Bad {}"));
        var build = new UniversalBuildIngestion().ingest(inputs, policy(), Optional.empty(), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16));
        var good = build.modules().stream().filter(m -> m.descriptor().path().equals("good"))
                .findFirst().orElseThrow();
        var key = new UniversalSourceIngestion.SourceSet(good.descriptor().identity(), SourcePlanModel.Kind.MAIN);
        var result = new UniversalSourceIngestion().assemble(inputs, build, Map.of(key, resolution()),
                component(), component(), component());
        assertTrue(result.outcomes().stream().filter(o -> o.sourceSet().equals(key))
                .allMatch(o -> o.request().isEmpty()));
    }

    @Test void malformedIndependentBuildKeepsExactRequestForHealthyIsland() {
        var inputs = IngestionFixtures.inputs(Map.of(
                "good/build.gradle", "plugins { java }\n",
                "good/src/main/java/Good.java", "class Good {}",
                "bad/build.gradle", "dependencies { implementation 'unfinished",
                "bad/src/main/java/Bad.java", "class Bad {}",
                "web/index.js", "export const ready = true;"));
        var build = new UniversalBuildIngestion().ingest(inputs, policy(), Optional.empty(), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16));
        assertTrue(build.issues().stream().anyMatch(i -> i.subject().equals("bad/build.gradle")));
        var resolutions = new HashMap<UniversalSourceIngestion.SourceSet, UniversalSourceIngestion.Resolution>();
        for (var module : build.modules()) {
            var key = new UniversalSourceIngestion.SourceSet(module.descriptor().identity(), SourcePlanModel.Kind.MAIN);
            resolutions.put(key, resolution());
        }
        var component = component();
        var result = new UniversalSourceIngestion().assemble(inputs, build, resolutions,
                component, component, component);
        assertEquals(2, result.sources().size());
        assertTrue(result.outcomes().stream().filter(o -> o.sourceSet().module().equals(
                build.modules().stream().filter(m -> m.descriptor().path().equals("good"))
                        .findFirst().orElseThrow().descriptor().identity()))
                .anyMatch(o -> o.request().isPresent()), result.issues().toString());
        assertTrue(result.outcomes().stream().filter(o -> o.sourceSet().module().equals(
                build.modules().stream().filter(m -> m.descriptor().path().equals("bad"))
                        .findFirst().orElseThrow().descriptor().identity()))
                .allMatch(o -> o.request().isEmpty()));
    }

    @Test void unknownCustomRoleKeepsMainExactAndCustomStructureQualified() {
        var inputs = IngestionFixtures.inputs(Map.of(
                "build.gradle", "plugins { java }\nsourceSets { integrationTest { java { srcDir('src/it/java') } } }\n",
                "src/main/java/Main.java", "class Main {}",
                "src/it/java/Probe.java", "class Probe {}"));
        var build = new UniversalBuildIngestion().ingest(inputs, policy(), Optional.empty(), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16));
        var owner = build.modules().getFirst().descriptor().identity();
        var main = new UniversalSourceIngestion.SourceSet(owner, SourcePlanModel.Kind.MAIN);
        var custom = new UniversalSourceIngestion.SourceSet(owner, SourcePlanModel.Kind.CUSTOM,
                "integrationTest");
        var result = new UniversalSourceIngestion().assemble(inputs, build,
                Map.of(main, resolution(), custom, resolution()),
                component(), component(), component());
        assertTrue(result.outcomes().stream().filter(o -> o.sourceSet().equals(main))
                .anyMatch(o -> o.request().isPresent()), result.issues().toString());
        assertTrue(result.outcomes().stream().filter(o -> o.sourceSet().equals(custom))
                .allMatch(o -> o.request().isEmpty()));
        assertTrue(result.sources().stream().anyMatch(row -> row.path().endsWith("Probe.java")
                && row.claims().equals(List.of(custom))));
    }

    private static UniversalBuildIngestion.Policy policy() {
        return new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21),
                Optional.of("UTF-8"), 100, 10000, 1000, 16);
    }
    private static UniversalSourceIngestion.Resolution resolution() {
        var platform = PlatformInput.create(21, "fixture-21", "authored", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("fixture"),
                        Path.of("unused.jmod"), PlatformInput.Format.JMOD)));
        return new UniversalSourceIngestion.Resolution(platform, List.of(),
                ContentDigest.sha256Utf8("exact-empty-dependencies"));
    }
    private static ManifestComponent component() {
        return new ManifestComponent(new VersionedIdentifier("test.components", "1"),
                ContentDigest.sha256Utf8("components"));
    }
}
