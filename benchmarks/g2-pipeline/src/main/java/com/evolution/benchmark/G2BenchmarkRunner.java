package com.evolution.benchmark;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.dependency.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.input.*;
import com.evolution.analysis.javaparser.*;
import com.evolution.analysis.maven.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Reproducible end-to-end G2 checkpoint runner. This is a benchmark, not a Gate oracle. */
public final class G2BenchmarkRunner {
    private G2BenchmarkRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) args = environmentArguments();
        if (args.length < 7) {
            System.err.println("Usage: G2BenchmarkRunner <repositoryRoot> <outputDirectory> "
                    + "<repositoryCoordinate> <revision-or-dash> <mavenCache> <jdkHome> "
                    + "<fallbackPlatformRelease> [remoteMavenRepository ...]");
            System.exit(2);
        }
        Path repositoryRoot = Paths.get(args[0]).toAbsolutePath().normalize();
        Path outputDirectory = Paths.get(args[1]).toAbsolutePath().normalize();
        RepositoryIdentity repository = RepositoryIdentity.fromCanonicalCoordinate(args[2]);
        Optional<String> revision = args[3].equals("-") ? Optional.empty() : Optional.of(args[3]);
        Path cacheBase = Paths.get(args[4]).toAbsolutePath().normalize();
        Path jdkHome = Paths.get(args[5]).toAbsolutePath().normalize();
        int fallbackPlatformRelease = Integer.parseInt(args[6]);
        List<URI> remoteRepositories = Arrays.stream(args).skip(7).map(URI::create).toList();
        Files.createDirectories(outputDirectory);
        Path firstCache = emptyCache(cacheBase.resolve("run-1"));
        Path secondCache = emptyCache(cacheBase.resolve("run-2"));

        Map<String, Object> environment = new TreeMap<>();
        environment.put("java.version", System.getProperty("java.version"));
        environment.put("java.vendor", System.getProperty("java.vendor"));
        environment.put("os.name", System.getProperty("os.name"));
        environment.put("repository", args[2]);
        environment.put("revision", revision.orElse("UNSPECIFIED"));
        Files.writeString(outputDirectory.resolve("environment.json"), CanonicalJson.write(environment),
                StandardCharsets.UTF_8);

        String first = runPipeline(repositoryRoot, outputDirectory.resolve("pipeline-results-run-1.json"),
                outputDirectory, repository, revision, firstCache, jdkHome, fallbackPlatformRelease, remoteRepositories);
        String second = runPipeline(repositoryRoot, outputDirectory.resolve("pipeline-results-run-2.json"),
                null, repository, revision, secondCache, jdkHome, fallbackPlatformRelease, remoteRepositories);
        Files.writeString(outputDirectory.resolve("canonical-digests.json"), CanonicalJson.write(
                Map.of("run-1", first, "run-2", second)), StandardCharsets.UTF_8);
        if (!first.equals(second)) throw new IllegalStateException("Pipeline runs are not deterministic");
        System.out.println("G2 pipeline completed deterministically: " + first);
    }

    private static Path emptyCache(Path path) throws Exception {
        Files.createDirectories(path);
        Path real = path.toRealPath();
        try (var children = Files.list(real)) {
            if (children.findAny().isPresent()) {
                throw new IllegalArgumentException("Each deterministic benchmark run requires an empty isolated cache");
            }
        }
        return real;
    }

    private static String[] environmentArguments() {
        String repositories = System.getenv().getOrDefault("G2_BENCHMARK_REMOTE_POM_REPOSITORIES", "");
        List<String> values = new ArrayList<>(List.of(
                requiredEnvironment("G2_BENCHMARK_REPOSITORY_ROOT"),
                requiredEnvironment("G2_BENCHMARK_OUTPUT_DIRECTORY"),
                requiredEnvironment("G2_BENCHMARK_REPOSITORY_COORDINATE"),
                requiredEnvironment("G2_BENCHMARK_REVISION"),
                requiredEnvironment("G2_BENCHMARK_MAVEN_CACHE"),
                requiredEnvironment("G2_BENCHMARK_JDK_HOME"),
                requiredEnvironment("G2_BENCHMARK_FALLBACK_PLATFORM_RELEASE")));
        Arrays.stream(repositories.split("\\|", -1))
                .filter(value -> !value.isBlank())
                .forEach(values::add);
        return values.toArray(String[]::new);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing benchmark environment variable: " + name);
        }
        return value;
    }

    private static String runPipeline(
            Path repositoryRoot,
            Path resultsFile,
            Path metricsDirectory,
            RepositoryIdentity repository,
            Optional<String> revision,
            Path cacheRoot,
            Path jdkHome,
            int fallbackPlatformRelease,
            List<URI> remoteRepositories) throws Exception {
        RepositoryAcquisitionRequest acquisitionRequest = new RepositoryAcquisitionRequest(
                repository, revision, false, "pom.xml",
                new RepositoryAcquisitionPolicy(
                        20_000, 5_000, 10_000_000L, 100_000_000L, 32,
                        List.of(".git", ".idea", ".mvn/wrapper/maven-wrapper.jar", "target")));
        RepositoryAcquisitionResult repositoryResult =
                new FilesystemRepositoryAcquirer().acquire(repositoryRoot, acquisitionRequest);
        var snapshot = repositoryResult.snapshot().orElseThrow(
                () -> new IllegalStateException("Repository acquisition was incomplete"));

        Map<String, PomInput> workspacePoms = new TreeMap<>();
        repositoryResult.files().stream()
                .filter(file -> file.path().equals("pom.xml") || file.path().endsWith("/pom.xml"))
                .forEach(file -> workspacePoms.put(file.path(), new PomInput(file.bytes())));
        BuildModelRequest initialBuildRequest = new BuildModelRequest(snapshot, "pom.xml", workspacePoms, Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 2_000_000, 2_000, 128));
        MavenPomResolutionPolicy pomPolicy = new MavenPomResolutionPolicy(
                2_000, 2_000_000, 50_000_000, 64, remoteRepositories, 5_000, 20_000);
        MavenBuildModelResolution buildResolution = new MavenBuildModelResolver(cacheRoot)
                .resolve(initialBuildRequest, pomPolicy);
        BuildModelRequest buildRequest = buildResolution.request();
        BuildModelResult buildResult = buildResolution.model();

        CandidateSourceOwnership ownership = CandidateSourceOwnershipResolver.resolve(
                repositoryResult, buildRequest, buildResult);
        ClasspathResolutionPolicy classpathPolicy =
                new ClasspathResolutionPolicy(20_000, 40_000, 2_000_000L, 100_000_000L, 1_000_000_000L, 64);
        ClasspathResolutionRequest classpathRequest =
                new ClasspathResolutionRequest(buildRequest, buildResult, classpathPolicy);
        DependencyAcquisitionPolicy dependencyPolicy = new DependencyAcquisitionPolicy(
                20_000, 100_000_000L, 1_000_000_000L, 64,
                remoteRepositories, 5_000, 20_000, 2);
        MavenDependencyArtifactResolver dependencyResolver = new MavenDependencyArtifactResolver(cacheRoot);
        MavenDependencyArtifactResolution dependencyResolution =
                dependencyResolver.resolve(classpathRequest, dependencyPolicy);
        ExactClasspathResult classpathResult = dependencyResolution.classpath();
        SourceDecodingResult decodingResult = SourceDecoder.decode(
                repositoryResult, ownership, buildRequest, buildResult, SourceDecodingPolicy.withholdWhenAbsent());

        int platformRelease = platformRelease(buildResult, fallbackPlatformRelease);
        PlatformSymbolResult platformResult = new FilesystemJdkPlatformProvider().acquire(
                jdkHome, new PlatformSymbolRequest(platformRelease, 256, 100_000_000L, 1_000_000_000L));

        List<BinaryInput> dependencyInputs = dependencyResolver.binaryInputs(dependencyResolution);
        var component = new ManifestComponent(
                new VersionedIdentifier("benchmark.g2", "m3.8"), ContentDigest.sha256Utf8("benchmark.g2:m3.8"));
        FrontendAssemblyPolicy assemblyPolicy = new FrontendAssemblyPolicy(
                new VersionedIdentifier("analysis.manifest", "2"),
                new VersionedIdentifier("analysis.configuration", "2"), component, component, component);
        FrontendAssemblyResult assemblyResult = FrontendInputAssembler.assemble(
                ownership, buildResult, classpathRequest, classpathResult, decodingResult, platformResult,
                dependencyInputs, List.of(), assemblyPolicy);

        JavaParserFrontend frontend = new JavaParserFrontend();
        List<FrontendResult> frontendResults = assemblyResult.outcomes().stream()
                .flatMap(outcome -> outcome.request().stream())
                .map(frontend::analyze)
                .toList();
        EvidenceContext context = new EvidenceContext(snapshot.identity(), Optional.empty());
        EvidenceNormalizationInput normalizationInput = EvidenceNormalizationInput.builder()
                .repositoryAcquisitions(List.of(repositoryResult))
                .buildModels(List.of(buildResult))
                .sourceOwnerships(List.of(ownership))
                .classpaths(List.of(classpathResult))
                .dependencyAcquisitions(dependencyResolution.acquisitions())
                .sourceDecodings(List.of(decodingResult))
                .platformResults(List.of(platformResult))
                .frontendAssemblies(List.of(assemblyResult))
                .build();
        EvidenceAcquisitionLedger ledger = CapabilityGapNormalizer.normalize(context, normalizationInput);
        List<EvidenceAcquisitionLedger> frontendLedgers = frontendResults.stream().map(result ->
                CapabilityGapNormalizer.normalize(
                        new EvidenceContext(snapshot.identity(), Optional.of(result.analysis())),
                        EvidenceNormalizationInput.builder().frontendResults(List.of(result)).build()))
                .toList();

        Map<String, Object> resolutionView = new TreeMap<>();
        resolutionView.put("identity", buildResolution.identity());
        resolutionView.put("provider", buildResolution.provider());
        resolutionView.put("passes", buildResolution.passes());
        resolutionView.put("acquiredPoms", buildResolution.acquiredPoms());
        resolutionView.put("attempts", buildResolution.attempts());
        resolutionView.put("problems", buildResolution.problems());
        Map<String, Object> dependencyView = new TreeMap<>();
        dependencyView.put("identity", dependencyResolution.identity());
        dependencyView.put("provider", dependencyResolution.provider());
        dependencyView.put("classpathPasses", dependencyResolution.classpathPasses());
        dependencyView.put("acquisitions", dependencyResolution.acquisitions());
        dependencyView.put("problems", dependencyResolution.problems());
        Map<String, Object> output = new TreeMap<>();
        output.put("identities", Map.of(
                "repositoryAcquisition", repositoryResult.identity().value(),
                "buildResolution", buildResolution.identity().value(),
                "buildModel", buildResult.identity().value(),
                "dependencyResolution", dependencyResolution.identity().value(),
                "classpath", classpathResult.identity().value(),
                "platform", platformResult.identity().value(),
                "ownership", ownership.identity().value(),
                "decoding", decodingResult.identity().value(),
                "assembly", assemblyResult.identity().value()));
        output.put("buildResolution", resolutionView);
        output.put("dependencyResolution", dependencyView);
        output.put("buildModel", buildResult);
        output.put("classpath", classpathResult);
        output.put("platform", platformView(platformResult));
        output.put("ownership", ownership);
        output.put("decoding", decodingView(decodingResult));
        output.put("assembly", assemblyView(assemblyResult));
        output.put("frontendResults", frontendResults);
        output.put("ledger", ledger);
        output.put("frontendLedgers", frontendLedgers);
        String json = CanonicalJson.write(output);
        Files.writeString(resultsFile, json, StandardCharsets.UTF_8);
        if (metricsDirectory != null) {
            writeMetrics(metricsDirectory, repositoryResult, buildResolution, dependencyResolution,
                    ownership, classpathResult,
                    decodingResult, assemblyResult, frontendResults,
                    java.util.stream.Stream.concat(java.util.stream.Stream.of(ledger), frontendLedgers.stream()).toList());
        }
        return ContentDigest.sha256Utf8(json).value();
    }

    private static Map<String, Object> platformView(PlatformSymbolResult result) {
        Map<String, Object> view = new TreeMap<>();
        view.put("identity", result.identity());
        view.put("schemaVersion", result.schemaVersion());
        view.put("requestIdentity", result.requestIdentity());
        view.put("provider", result.provider());
        view.put("status", result.status());
        view.put("platform", result.platform().map(platform -> Map.of(
                "entry", platform.entry(),
                "release", platform.release(),
                "version", platform.version(),
                "vendor", platform.vendor(),
                "artifacts", platform.artifacts().stream().map(artifact -> Map.of(
                        "logicalName", artifact.logicalName(),
                        "contentDigest", artifact.contentDigest(),
                        "format", artifact.format())).toList())));
        view.put("problems", result.problems());
        view.put("attempts", result.attempts());
        view.put("limitations", result.limitations());
        return view;
    }

    private static Map<String, Object> decodingView(SourceDecodingResult result) {
        Map<String, Object> view = new TreeMap<>();
        view.put("identity", result.identity());
        view.put("schemaVersion", result.schemaVersion());
        view.put("acquisitionIdentity", result.acquisitionIdentity());
        view.put("ownershipIdentity", result.ownershipIdentity());
        view.put("buildModelIdentity", result.buildModelIdentity());
        view.put("policy", result.policy());
        view.put("provider", result.provider());
        view.put("snapshot", result.snapshot().identity());
        view.put("outcomes", result.outcomes().stream().map(outcome -> Map.of(
                "path", outcome.path(),
                "rawDigest", outcome.rawDigest(),
                "status", outcome.status(),
                "document", outcome.input().map(SourceInput::document),
                "decoding", outcome.input().map(SourceInput::decoding),
                "problems", outcome.problems())).toList());
        view.put("limitations", result.limitations());
        return view;
    }

    private static Map<String, Object> assemblyView(FrontendAssemblyResult result) {
        Map<String, Object> view = new TreeMap<>();
        view.put("identity", result.identity());
        view.put("schemaVersion", result.schemaVersion());
        view.put("inputIdentity", result.inputIdentity());
        view.put("provider", result.provider());
        view.put("outcomes", result.outcomes().stream().map(outcome -> Map.of(
                "module", outcome.module(),
                "sourceSet", outcome.sourceSet(),
                "status", outcome.status(),
                "analysis", outcome.request().map(request -> request.manifest().identity()),
                "problems", outcome.problems())).toList());
        view.put("limitations", result.limitations());
        return view;
    }

    private static int platformRelease(BuildModelResult build, int fallback) {
        Set<Integer> releases = new TreeSet<>();
        build.modules().stream().flatMap(module -> module.effectivePom().stream())
                .flatMap(pom -> pom.sourcePlan().sourceSets().stream())
                .flatMap(plan -> plan.platformRelease().value().stream())
                .map(Integer::parseInt).forEach(releases::add);
        if (releases.size() > 1) {
            throw new IllegalStateException("This checkpoint runner requires one platform release per repository run: " + releases);
        }
        return releases.stream().findFirst().orElse(fallback);
    }

    private static void writeMetrics(
            Path directory,
            RepositoryAcquisitionResult repository,
            MavenBuildModelResolution build,
            MavenDependencyArtifactResolution dependencyResolution,
            CandidateSourceOwnership ownership,
            ExactClasspathResult classpath,
            SourceDecodingResult decoding,
            FrontendAssemblyResult assembly,
            List<FrontendResult> frontendResults,
            List<EvidenceAcquisitionLedger> ledgers) throws Exception {
        Map<String, Map<String, Long>> categories = categoryMetrics(frontendResults);
        Files.writeString(directory.resolve("category-metrics.json"), CanonicalJson.write(categories), StandardCharsets.UTF_8);
        writeCategoryTsv(directory.resolve("category-metrics.tsv"), categories);

        Map<String, Long> reasons = new TreeMap<>();
        for (CapabilityGapRecord gap : ledgers.stream().flatMap(value -> value.gaps().stream()).toList()) {
            reasons.merge(gap.mechanismCategory() + ":" + gap.reasonCode(), 1L, Long::sum);
        }
        Files.writeString(directory.resolve("reason-metrics.json"), CanonicalJson.write(reasons), StandardCharsets.UTF_8);
        writeReasonTsv(directory.resolve("reason-metrics.tsv"), reasons);

        Map<String, Long> summary = new TreeMap<>();
        summary.put("repository.files", (long) repository.files().size());
        summary.put("repository.javaFiles", repository.files().stream().filter(file -> file.path().endsWith(".java")).count());
        summary.put("build.modules", (long) build.model().modules().size());
        summary.put("build.effectiveModules", build.model().modules().stream().filter(module -> module.effectivePom().isPresent()).count());
        summary.put("build.problems", (long) build.model().problems().size());
        summary.put("build.externalPomsAcquired", (long) build.acquiredPoms().size());
        List<DependencyAcquisitionResult.Outcome> dependencyOutcomes = dependencyResolution.acquisitions().stream()
                .flatMap(value -> value.outcomes().stream()).toList();
        summary.put("dependency.acquisitionRounds", (long) dependencyResolution.acquisitions().size());
        summary.put("dependency.classpathPasses", (long) dependencyResolution.classpathPasses());
        summary.put("dependency.requested", (long) dependencyOutcomes.size());
        for (DependencyAcquisitionResult.Status status : DependencyAcquisitionResult.Status.values()) {
            summary.put("dependency." + status.name().toLowerCase(Locale.ROOT),
                    dependencyOutcomes.stream().filter(value -> value.status() == status).count());
        }
        summary.put("dependency.bytesConsumed", dependencyResolution.acquisitions().stream()
                .mapToLong(DependencyAcquisitionResult::bytesConsumed).sum());
        summary.put("ownership.owned", ownership.candidates().stream().filter(value -> value.status() == CandidateSourceOwnership.Status.OWNED).count());
        summary.put("ownership.unowned", ownership.candidates().stream().filter(value -> value.status() == CandidateSourceOwnership.Status.UNOWNED).count());
        summary.put("classpath.artifacts", (long) classpath.artifacts().size());
        summary.put("classpath.problems", classpath.manifests().stream().mapToLong(value -> value.problems().size()).sum());
        summary.put("decoding.decoded", decoding.outcomes().stream().filter(value -> value.status() == SourceDecodingResult.Status.DECODED).count());
        summary.put("assembly.assembled", assembly.outcomes().stream().filter(value -> value.status() == FrontendAssemblyResult.Status.ASSEMBLED).count());
        summary.put("assembly.withheld", assembly.outcomes().stream().filter(value -> value.status() == FrontendAssemblyResult.Status.WITHHELD).count());
        summary.put("frontend.runs", (long) frontendResults.size());
        summary.put("frontend.observations", frontendResults.stream().mapToLong(value -> value.observations().size()).sum());
        summary.put("capabilityGaps", ledgers.stream().mapToLong(value -> value.gaps().size()).sum());
        Files.writeString(directory.resolve("pipeline-summary.json"), CanonicalJson.write(summary), StandardCharsets.UTF_8);
        writeSummaryTsv(directory.resolve("pipeline-summary.tsv"), summary);

        String reason = frontendResults.isEmpty()
                ? "No frontend input was assembled; build/acquisition gaps must be resolved before semantic adjudication"
                : "Independent semantic correctness labels and adjudication are not yet recorded";
        Map<String, Object> assessment = Map.of(
                "checkpoint", "G2", "date", "2026-09-09", "status", "WITHHELD", "reason", reason);
        Files.writeString(directory.resolve("gate-assessment.json"), CanonicalJson.write(assessment), StandardCharsets.UTF_8);
    }

    static Map<String, Map<String, Long>> categoryMetrics(List<FrontendResult> results) {
        Map<String, Map<String, Long>> categories = new TreeMap<>();
        for (String registered : FrontendRequest.CATEGORIES) {
            Map<String, Long> counts = new TreeMap<>();
            counts.put("ATTEMPTED", 0L); counts.put("EMITTED", 0L); counts.put("UNMAPPED", 0L);
            for (SemanticStatus status : SemanticStatus.values()) counts.put(status.name(), 0L);
            categories.put("java." + registered, counts);
        }
        for (FrontendResult result : results) {
            result.coverage().forEach(coverage -> {
                Map<String, Long> counts = categories.get(coverage.category().value());
                counts.merge("ATTEMPTED", coverage.attempted(), Long::sum);
                counts.merge("EMITTED", coverage.emitted(), Long::sum);
                counts.merge("UNMAPPED", coverage.unmappable(), Long::sum);
            });
            result.observations().forEach(observation -> categories.get(observation.category().value())
                    .merge(observation.attribution().name(), 1L, Long::sum));
        }
        return categories.entrySet().stream().collect(
                TreeMap::new, (map, entry) -> map.put(entry.getKey(), Map.copyOf(entry.getValue())), TreeMap::putAll);
    }

    private static void writeCategoryTsv(Path path, Map<String, Map<String, Long>> categories) throws Exception {
        List<String> columns = new ArrayList<>();
        columns.add("CATEGORY"); columns.add("ATTEMPTED"); columns.add("EMITTED"); columns.add("UNMAPPED");
        Arrays.stream(SemanticStatus.values()).map(Enum::name).forEach(columns::add);
        StringBuilder text = new StringBuilder(String.join("\t", columns)).append('\n');
        categories.forEach((category, counts) -> {
            text.append(category);
            columns.stream().skip(1).forEach(column -> text.append('\t').append(counts.getOrDefault(column, 0L)));
            text.append('\n');
        });
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static void writeReasonTsv(Path path, Map<String, Long> reasons) throws Exception {
        StringBuilder text = new StringBuilder("REASON\tCOUNT\n");
        reasons.forEach((reason, count) -> text.append(reason).append('\t').append(count).append('\n'));
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static void writeSummaryTsv(Path path, Map<String, Long> summary) throws Exception {
        StringBuilder text = new StringBuilder("METRIC\tVALUE\n");
        summary.forEach((metric, value) -> text.append(metric).append('\t').append(value).append('\n'));
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
