package com.evolution.analysis.maven;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlatformReactorModelTest {
    @Test
    void modelsThePlatformsActualPomBundleWithoutRunningItsEnforcerOrPlugins() throws Exception {
        // This smoke input is an explicit POM-only fixture, not a full repository acquisition checkpoint.
        Path root = Path.of("..").toAbsolutePath().normalize();
        var poms = new TreeMap<String, PomInput>();
        for (String path : List.of("pom.xml", "analyzer/pom.xml", "analyzer-maven/pom.xml", "analyzer-filesystem/pom.xml",
                "analyzer-javaparser/pom.xml", "backend/pom.xml")) {
            poms.put(path, new PomInput(Files.readAllBytes(root.resolve(path))));
        }
        var snapshot = RepositorySnapshot.create(
                RepositoryIdentity.fromCanonicalCoordinate("https://example.test/platform-pom-smoke.git"), Optional.empty(), false,
                poms.entrySet().stream().map(e -> new SnapshotFile(e.getKey(), e.getValue().digest())).toList(), List.of());
        var request = new BuildModelRequest(snapshot, "pom.xml", poms, Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 100, 32));
        var result = new MavenBuildModelProvider().build(request);
        assertTrue(result.problems().isEmpty(), () -> result.problems().toString());
        assertTrue(result.hasGaps()); // Candidate plans explicitly withhold generated-file acquisition/plugin effects.
        assertEquals(6, result.modules().size());
        for (var module : result.modules()) {
            var effective = module.effectivePom().orElseThrow();
            assertEquals("21", effective.properties().get("maven.compiler.release"));
            assertEquals("UTF-8", effective.properties().get("project.build.sourceEncoding"));
            for (var sourceSet : effective.sourcePlan().sourceSets()) {
                assertEquals(module.module().identity(), sourceSet.module());
                assertEquals(Optional.of("21"), sourceSet.syntaxLevel().value());
                assertEquals(Optional.of("21"), sourceSet.platformRelease().value());
                assertEquals(Optional.of("UTF-8"), sourceSet.encoding().value());
                if (!module.module().path().equals(".")) {
                    String suffix = sourceSet.kind() == SourcePlanModel.Kind.MAIN ? "/src/main/java" : "/src/test/java";
                    assertEquals(Optional.of(module.module().path() + suffix), sourceSet.sourceRoots().getFirst().value());
                }
            }
            effective.dependencies().stream().filter(d -> d.artifactId().equals("junit-jupiter"))
                    .forEach(d -> assertEquals("5.11.0", d.version()));
        }
    }
}
