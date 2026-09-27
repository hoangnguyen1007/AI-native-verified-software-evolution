package com.evolution.analysis.acquisition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Closed observed-file ledger with separate unvisited frontiers; no invented count beyond a frontier. */
public record RepositoryUnderstandingInventory(ContentDigest identity,
        RepositoryAcquisitionResult.Completion acquisitionCompletion,
        List<FileRow> observedFiles, List<String> observedDirectories,
        List<Frontier> frontiers, List<String> excludedPaths,
        List<List<String>> caseCollisions, JavaApplicability javaApplicability) {
    public static final String SCHEMA = "repository-understanding-inventory-v1";
    public static final VersionedIdentifier CLASSIFIER =
            new VersionedIdentifier("repository.inventory-classifier", "m4uv2.1-v1");
    public enum Kind { SOURCE, BUILD_SCRIPT, ARCHIVE, RESOURCE }
    public enum Language { JAVA, KOTLIN, SCALA, GROOVY, JAVASCRIPT, TYPESCRIPT, PYTHON,
        C_SHARP, C, C_PLUS_PLUS, OTHER }
    public enum Role { MAIN, TEST, INTEGRATION_TEST, GENERATED, VENDORED, BENCHMARK, UNCLASSIFIED }
    public enum Availability { BYTES, LFS_POINTER }
    public enum JavaApplicability { APPLICABLE, NOT_APPLICABLE, UNKNOWN }
    public enum FrontierScope { FILE_OR_ENTRY, UNVISITED_REGION }
    public record FileRow(String path, ContentDigest digest, int bytes, Kind kind, Language language,
                          Role role, Availability availability) {
        public FileRow {
            path = ContractChecks.repositoryRelativePath(path, "inventory file path");
            Objects.requireNonNull(digest); Objects.requireNonNull(kind); Objects.requireNonNull(language);
            Objects.requireNonNull(role); Objects.requireNonNull(availability);
            if (bytes < 0) throw new IllegalArgumentException("Negative file size");
        }
    }
    public record Frontier(String subject, RepositoryAcquisitionResult.Reason reason, FrontierScope scope) {
        public Frontier {
            subject = ContractChecks.text(subject, "inventory frontier");
            Objects.requireNonNull(reason); Objects.requireNonNull(scope);
        }
    }
    public RepositoryUnderstandingInventory {
        Objects.requireNonNull(identity); Objects.requireNonNull(acquisitionCompletion);
        observedFiles = List.copyOf(observedFiles); observedDirectories = List.copyOf(observedDirectories);
        frontiers = List.copyOf(frontiers); excludedPaths = List.copyOf(excludedPaths);
        caseCollisions = List.copyOf(caseCollisions); Objects.requireNonNull(javaApplicability);
        if (acquisitionCompletion == RepositoryAcquisitionResult.Completion.COMPLETE && !frontiers.isEmpty())
            throw new IllegalArgumentException("Complete inventory cannot have an open frontier");
        if (javaApplicability == JavaApplicability.NOT_APPLICABLE
                && (acquisitionCompletion != RepositoryAcquisitionResult.Completion.COMPLETE
                        || observedFiles.stream().anyMatch(f -> f.language() == Language.JAVA)))
            throw new IllegalArgumentException("Java N/A requires a complete inventory without Java files");
    }

    public OptionalInt unobservedFileCount() {
        return acquisitionCompletion == RepositoryAcquisitionResult.Completion.COMPLETE
                ? OptionalInt.of(0) : OptionalInt.empty();
    }

    public static RepositoryUnderstandingInventory from(RepositoryAcquisitionResult acquisition) {
        Objects.requireNonNull(acquisition);
        var files = acquisition.files().stream().map(file -> new FileRow(file.path(), file.contentDigest(),
                file.size(), kind(file.path()), language(file.path()), role(file.path()), availability(file)))
                .sorted(Comparator.comparing(FileRow::path)).toList();
        var frontiers = acquisition.problems().stream()
                .filter(p -> p.reason() != RepositoryAcquisitionResult.Reason.MISSING_ROOT_POM)
                .map(p -> new Frontier(p.subject(), p.reason(), scope(p.reason())))
                .distinct().sorted(Comparator.comparing(Frontier::subject).thenComparing(Frontier::reason)).toList();
        var exclusions = acquisition.attempts().stream()
                .filter(a -> a.outcome() == RepositoryAcquisitionResult.Outcome.EXCLUDED)
                .map(RepositoryAcquisitionResult.Attempt::subject).distinct().sorted().toList();
        var folded = new TreeMap<String, List<String>>();
        for (var file : files) folded.computeIfAbsent(file.path().toLowerCase(Locale.ROOT),
                ignored -> new ArrayList<>()).add(file.path());
        var collisions = folded.values().stream().filter(paths -> paths.size() > 1)
                .map(paths -> paths.stream().sorted().toList()).toList();
        JavaApplicability java = files.stream().anyMatch(f -> f.language() == Language.JAVA)
                ? JavaApplicability.APPLICABLE
                : acquisition.completion() == RepositoryAcquisitionResult.Completion.COMPLETE
                ? JavaApplicability.NOT_APPLICABLE : JavaApplicability.UNKNOWN;
        var identity = IngestionEvidence.digest(List.of(SCHEMA, CLASSIFIER, acquisition.identity(), files,
                acquisition.directories(), frontiers, exclusions, collisions, java));
        return new RepositoryUnderstandingInventory(identity, acquisition.completion(), files,
                acquisition.directories(), frontiers, exclusions, collisions, java);
    }

    private static FrontierScope scope(RepositoryAcquisitionResult.Reason reason) {
        return switch (reason) {
            case ROOT_NOT_FOUND, ROOT_NOT_DIRECTORY, ROOT_SYMBOLIC_LINK, ROOT_READ_FAILED,
                    DIRECTORY_READ_FAILED, DIRECTORY_CHANGED_DURING_READ, ENTRY_COUNT_LIMIT,
                    FILE_COUNT_LIMIT, DIRECTORY_COUNT_LIMIT, TOTAL_BYTE_LIMIT, DEPTH_LIMIT,
                    UNVISITED_REGION ->
                FrontierScope.UNVISITED_REGION;
            default -> FrontierScope.FILE_OR_ENTRY;
        };
    }
    private static Kind kind(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts")
                || name.equals("settings.gradle") || name.equals("settings.gradle.kts")
                || name.equals("build.xml") || name.endsWith(".bzl")) return Kind.BUILD_SCRIPT;
        if (name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear")
                || name.endsWith(".zip") || name.endsWith(".jmod")) return Kind.ARCHIVE;
        return language(path) == Language.OTHER ? Kind.RESOURCE : Kind.SOURCE;
    }
    private static Language language(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gradle.kts") || lower.endsWith("settings.gradle.kts")) return Language.OTHER;
        if (lower.endsWith(".java")) return Language.JAVA;
        if (lower.endsWith(".kt")) return Language.KOTLIN;
        if (lower.endsWith(".scala")) return Language.SCALA;
        if (lower.endsWith(".groovy")) return Language.GROOVY;
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) return Language.JAVASCRIPT;
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return Language.TYPESCRIPT;
        if (lower.endsWith(".py")) return Language.PYTHON;
        if (lower.endsWith(".cs")) return Language.C_SHARP;
        if (lower.endsWith(".c")) return Language.C;
        if (lower.endsWith(".cc") || lower.endsWith(".cpp") || lower.endsWith(".cxx")) return Language.C_PLUS_PLUS;
        return Language.OTHER;
    }
    private static Role role(String path) {
        String lower = "/" + path.toLowerCase(Locale.ROOT) + "/";
        if (lower.contains("/generated/") || lower.contains("/generated-sources/")) return Role.GENERATED;
        if (lower.contains("/vendor/") || lower.contains("/vendored/")
                || lower.contains("/third_party/")) return Role.VENDORED;
        if (lower.contains("/benchmark/") || lower.contains("/benchmarks/")) return Role.BENCHMARK;
        if (lower.contains("/src/integrationtest/") || lower.contains("/src/integration-test/")) return Role.INTEGRATION_TEST;
        if (lower.contains("/src/test/") || lower.contains("/test/")) return Role.TEST;
        if (lower.contains("/src/main/")) return Role.MAIN;
        return Role.UNCLASSIFIED;
    }
    private static Availability availability(AcquiredFile file) {
        if (file.size() > 1024) return Availability.BYTES;
        String text = new String(file.bytes(), StandardCharsets.US_ASCII);
        return text.startsWith("version https://git-lfs.github.com/spec/v1\n")
                ? Availability.LFS_POINTER : Availability.BYTES;
    }
}
