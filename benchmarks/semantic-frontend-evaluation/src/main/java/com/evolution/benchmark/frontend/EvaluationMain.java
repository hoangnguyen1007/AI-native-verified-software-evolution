package com.evolution.benchmark.frontend;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reproducible controlled A/B/C run for the checked-in microfixture. */
public final class EvaluationMain {
    private EvaluationMain() { }

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of(".").toAbsolutePath() : Path.of(args[0]).toAbsolutePath();
        Path source;
        List<Path> fullClasspath;
        List<Path> partialClasspath;
        String outputName;
        if (args.length == 3) {
            source = Path.of(args[1]).toAbsolutePath();
            fullClasspath = java.util.Arrays.stream(Files.readString(Path.of(args[2])).trim()
                            .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                    .filter(line -> !line.isBlank()).map(Path::of).toList();
            partialClasspath = fullClasspath.stream().filter(path -> !path.getFileName().toString().startsWith("spring-web-")).toList();
            outputName = "petclinic-preliminary-call-results.json";
        } else {
            Path fixtures = root.resolve("fixtures");
            Path work = root.resolve("target/controlled-classpath");
            Files.createDirectories(work);
            Path external = FixtureClasspath.compileJar(fixtures.resolve("external-api"), work, "external-api");
            Path optional = FixtureClasspath.compileJar(fixtures.resolve("optional-api"), work, "optional-api");
            source = fixtures.resolve("project");
            fullClasspath = List.of(external, optional);
            partialClasspath = List.of(external);
            outputName = "controlled-microfixture-results.json";
        }
        List<SemanticFrontend> frontends = List.of(new JavaParserFrontend(), new OpenRewriteFrontend());
        Map<String, List<Map<String, Object>>> results = new LinkedHashMap<>();
        results.put("CONFIG_A_SOURCE_AND_JDK", run(frontends, source, List.of(), "CONFIG_A_SOURCE_AND_JDK"));
        results.put("CONFIG_B_EXACT_COMPILE_CLASSPATH", run(frontends, source, fullClasspath, "CONFIG_B_EXACT_COMPILE_CLASSPATH"));
        results.put("CONFIG_C_CONTROLLED_PARTIAL_CLASSPATH", run(frontends, source, partialClasspath, "CONFIG_C_CONTROLLED_PARTIAL_CLASSPATH"));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", "0.1");
        output.put("generatedAtUtc", Instant.now().toString());
        output.put("javaVersion", System.getProperty("java.version"));
        output.put("javaVendor", System.getProperty("java.vendor"));
        output.put("results", results);
        Path out = root.resolve("results/" + outputName);
        Files.createDirectories(out.getParent());
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(output));
        System.out.println(out.toAbsolutePath());
    }

    private static List<Map<String, Object>> run(
            List<SemanticFrontend> frontends, Path source, List<Path> classpath, String configuration) {
        return frontends.stream().map(frontend -> encode(frontend.analyze(new FrontendRequest(source, classpath, configuration)))).toList();
    }

    private static Map<String, Object> encode(FrontendResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("frontendId", result.frontendId());
        output.put("elapsedMillis", result.elapsedMillis());
        output.put("diagnostics", result.diagnostics());
        output.put("observations", result.observations().stream().map(observation -> Map.of(
                "category", observation.category().name(),
                "source", observation.sourceIdentity(),
                "target", observation.targetIdentity(),
                "origin", observation.targetOrigin().name(),
                "sourceFile", observation.sourceFile().toString().replace('\\', '/'),
                "span", List.of(observation.span().startLine(), observation.span().startColumn(),
                        observation.span().endLine(), observation.span().endColumn()),
                "state", observation.state().name(),
                "diagnostic", observation.diagnostic())).toList());
        return output;
    }
}
