package com.evolution.benchmark;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Renders the mechanically generated TSV tables without an undeclared JSON runtime dependency. */
public final class G2ReportRenderer {
    private G2ReportRenderer() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            args = new String[] {
                    requiredEnvironment("G2_REPORT_METRICS_DIRECTORY"),
                    requiredEnvironment("G2_REPORT_OUTPUT_FILE"),
                    requiredEnvironment("G2_REPORT_REPOSITORY_LABEL")
            };
        }
        if (args.length < 3) {
            System.err.println("Usage: G2ReportRenderer <metricsDirectory> <outputMarkdownFile> <repositoryLabel>");
            System.exit(2);
        }
        render(Paths.get(args[0]).toAbsolutePath().normalize(),
                Paths.get(args[1]).toAbsolutePath().normalize(), args[2]);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing benchmark environment variable: " + name);
        }
        return value;
    }

    static void render(Path metricsDirectory, Path outputMarkdown, String repositoryLabel) throws Exception {
        List<List<String>> categories = readTsv(metricsDirectory.resolve("category-metrics.tsv"));
        List<List<String>> reasons = readTsv(metricsDirectory.resolve("reason-metrics.tsv"));
        List<List<String>> summary = readTsv(metricsDirectory.resolve("pipeline-summary.tsv"));
        if (categories.size() < 2 || summary.size() < 2) {
            throw new IllegalArgumentException("Benchmark tables are empty");
        }

        StringBuilder report = new StringBuilder("# Gate G2 Checkpoint: ")
                .append(repositoryLabel).append("\n\n");
        report.append("This report is mechanically rendered from the preserved pipeline output. "
                + "It is coverage evidence, not independent semantic correctness adjudication.\n\n");
        report.append("## Pipeline summary\n\n");
        report.append("| Metric | Value |\n| --- | ---: |\n");
        summary.stream().skip(1).forEach(row -> report.append("| ").append(row.get(0)).append(" | ")
                .append(row.get(1)).append(" |\n"));

        Map<String, String> summaryMetrics = metricValues(summary);
        if ("0".equals(summaryMetrics.get("frontend.runs"))) {
            report.append("\n> **Coverage boundary:** No source set reached the semantic frontend. ")
                    .append("The zero semantic-category counts below demonstrate a closed reporting denominator, ")
                    .append("not successful semantic analysis.\n");
        }

        report.append("\n## Closed semantic-category denominator\n\n");
        appendTable(report, categories);
        report.append("\n## Capability-gap reasons\n\n");
        if (reasons.size() == 1) report.append("No normalized capability gap was emitted.\n");
        else appendTable(report, reasons);

        report.append("\n## Gate decision\n\n");
        report.append("**WITHHELD.** Independent semantic correctness labels and adjudication are not yet recorded.\n");
        Files.createDirectories(outputMarkdown.getParent());
        Files.writeString(outputMarkdown, report, StandardCharsets.UTF_8);
    }

    private static Map<String, String> metricValues(List<List<String>> rows) {
        Map<String, String> metrics = new LinkedHashMap<>();
        rows.stream().skip(1).forEach(row -> metrics.put(row.get(0), row.get(1)));
        return Map.copyOf(metrics);
    }

    private static void appendTable(StringBuilder report, List<List<String>> rows) {
        report.append("| ").append(String.join(" | ", rows.getFirst())).append(" |\n");
        report.append("| ").append(String.join(" | ", Collections.nCopies(rows.getFirst().size(), "---")))
                .append(" |\n");
        rows.stream().skip(1).forEach(row -> report.append("| ").append(String.join(" | ", row)).append(" |\n"));
    }

    private static List<List<String>> readTsv(Path path) throws Exception {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Missing benchmark table: " + path.getFileName());
        List<List<String>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            List<String> cells = Arrays.asList(line.split("\\t", -1));
            if (cells.stream().anyMatch(cell -> cell.contains("|") || cell.contains("\n") || cell.contains("\r"))) {
                throw new IllegalArgumentException("Unsafe benchmark table cell");
            }
            rows.add(List.copyOf(cells));
        }
        if (!rows.isEmpty() && rows.stream().anyMatch(row -> row.size() != rows.getFirst().size())) {
            throw new IllegalArgumentException("Inconsistent benchmark table width");
        }
        return List.copyOf(rows);
    }
}
