package com.evolution.benchmark.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrontendCallResolutionTest {

    @Test
    void bothFrontendsResolveJdkOverloadTargetsWithCompleteSpans() throws URISyntaxException {
        Path sourceRoot = Path.of(getClass().getResource("/fixtures/project").toURI());
        FrontendRequest request = new FrontendRequest(sourceRoot, List.of(), "CONFIG_A");

        for (SemanticFrontend frontend : List.of(new JavaParserFrontend(), new OpenRewriteFrontend())) {
            List<Observation> observations = frontend.analyze(request).observations();
            Observation get = observations.stream().filter(o -> o.targetIdentity().contains("java.util.List.get"))
                    .findFirst().orElseThrow();
            assertTrue(get.state() == ObservationState.RESOLVED, frontend.id());
            assertTrue(get.span().startLine() > 0 && get.span().endColumn() > get.span().startColumn(), frontend.id());
        }
    }

    @Test
    void javaParser328ResolvesAll7CallsOnJava21SealedArchitectureFixture() throws Exception {
        Path fixtures = Path.of("fixtures").toAbsolutePath().normalize();
        if (!Files.isDirectory(fixtures)) {
            fixtures = Path.of("benchmarks/semantic-frontend-evaluation/fixtures").toAbsolutePath().normalize();
        }
        // Compile into target/ rather than JUnit @TempDir because JavaParser JarTypeSolver holds JarFile handles on Windows.
        Path work = fixtures.getParent().resolve("target/test-controlled-classpath");
        Files.createDirectories(work);
        Path external = FixtureClasspath.compileJar(fixtures.resolve("external-api"), work, "external-api");
        Path optional = FixtureClasspath.compileJar(fixtures.resolve("optional-api"), work, "optional-api");
        FrontendResult result = new JavaParserFrontend().analyze(new FrontendRequest(
                fixtures.resolve("project"), List.of(external, optional), "CONFIG_B_EXACT_COMPILE_CLASSPATH"));
        assertTrue(result.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + result.diagnostics());
        assertEquals(7, result.observations().size());
        assertTrue(result.observations().stream().allMatch(o -> o.state() == ObservationState.RESOLVED),
                () -> "Unresolved calls: " + result.observations());
    }
}
