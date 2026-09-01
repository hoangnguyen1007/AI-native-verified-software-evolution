package com.evolution.poc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ParserEvalApp {

    private ParserEvalApp() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: ParserEvalApp <source_root> <output_json> [configuration_id]");
            return;
        }

        Path sourceRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        String configurationId = args.length > 2 ? args[2] : "TEST_DEFAULT";

        Path groundTruth = optionalPath(args, 3);
        Path classpathFile = optionalPath(args, 4);
        List<Path> classpathEntries = classpathFile == null ? List.of() : Files.readAllLines(classpathFile).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        String targetRepositoryCommit = args.length > 5 && !"-".equals(args[5]) ? args[5] : null;
        ParserEvaluator.Options options = new ParserEvaluator.Options(sourceRoot, configurationId, classpathEntries,
                groundTruth, targetRepositoryCommit);
        ParserEvaluator.EvaluationResult result = new ParserEvaluator().evaluate(options);

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Writer writer = Files.newBufferedWriter(output)) {
            gson.toJson(result, writer);
        }
    }

    private static Path optionalPath(String[] args, int index) {
        if (args.length <= index || "-".equals(args[index])) {
            return null;
        }
        return Path.of(args[index]).toAbsolutePath().normalize();
    }
}
