package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.EvidenceAcquisitionLedger;
import com.evolution.analysis.frontend.BinaryInput;
import com.evolution.analysis.frontend.PlatformInput;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import com.evolution.analysis.input.PlatformSymbolResult;
import com.evolution.analysis.readiness.ReadinessPlan;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * The passive Workbench consumes these verified inputs before any parser or
 * graph work.
 */
final class WorkbenchReuseInputs {
    private WorkbenchReuseInputs() {
    }

    static Optional<ContentDigest> key(RepositoryAcquisitionResult acquisition, ReadinessPlan readiness,
            List<PlatformSymbolResult> platforms, List<ContentDigest> mavenModels,
            Optional<ContentDigest> gradleModel, MavenScan.Inputs dependencies,
            UniversalArchitecturePipeline.Policy policy, Optional<LocalAnalysisRunner.PolicyInput> rules,
            BooleanSupplier cancelled) {
        if (platforms.stream().anyMatch(p -> p.status() != PlatformSymbolResult.Status.COMPLETE)
                || acquisition.problems().stream()
                        .anyMatch(p -> p.reason() != RepositoryAcquisitionResult.Reason.MISSING_ROOT_POM)
                || System.getProperty("tree-sitter-lib") != null
                || dependencies.resolutions().values().stream().anyMatch(r -> r.correspondence().isPresent()
                        || r.binaries().stream().anyMatch(b -> b.nativeOrigin().isPresent())))
            return Optional.empty();
        try {
            var implementation = implementation(cancelled);
            var classpaths = ordered(policy.resolutions().entrySet().stream().map(e -> {
                var resolution = e.getValue();
                // Both binary positions and reactor substitutions affect binding. Do not sort
                // the slots.
                return List.of(e.getKey(), resolution.platform().entry(),
                        resolution.binaries().stream().map(b -> List.of(b.entry(), b.reactorModule())).toList(),
                        resolution.reactorSlots(),
                        resolution.exactClasspathEvidence(), resolution.complete());
            }).toList());
            var externalPoms = ordered(policy.externalPoms().entrySet().stream()
                    .map(e -> List.of(e.getKey(), e.getValue().digest())).toList());
            var springScopes = ordered(policy.springScopes().entrySet().stream()
                    .map(e -> List.of(e.getKey(), e.getValue())).toList());
            return Optional.of(CanonicalJson.sha256(List.of("workbench-passive-analysis-inputs:v1",
                    acquisition.identity(), readiness.identity(),
                    platforms.stream().map(PlatformSymbolResult::identity).toList(),
                    mavenModels, gradleModel, classpaths, externalPoms,
                    dependencies.ledgers().stream().map(EvidenceAcquisitionLedger::identity).toList(),
                    policy.buildPolicy(), policy.decodePolicy(), policy.frontendProvider(), policy.mavenPolicy(),
                    policy.maxTypes(), policy.maxParameters(), policy.truthEvaluationMode(), policy.truthLimits(),
                    policy.analysisOptions(), policy.springSatMode(),
                    springScopes, policy.configurationSpace().map(s -> s.identity()),
                    policy.baselineConfiguration().map(a -> a.identity()),
                    rules.map(r -> List.of(ContentDigest.sha256(r.bytes()), r.scoreScope())), implementation,
                    Runtime.version().toString(), System.getProperty("java.vendor", ""),
                    System.getProperty("os.name", ""),
                    System.getProperty("os.arch", ""), Charset.defaultCharset().name(),
                    Locale.getDefault().toLanguageTag(),
                    Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors())));
        } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
            return Optional.empty(); // Unknown execution inputs never turn into a cache hit.
        }
    }

    private static List<ContentDigest> ordered(Collection<?> rows) {
        return rows.stream().map(CanonicalJson::sha256).sorted().toList();
    }

    // Pin the running application and its ordered declared classpath, not the
    // target classpath.
    // A deployment must replace/restart the process to change application code.
    private static ContentDigest implementation;

    private static synchronized ContentDigest implementation(BooleanSupplier cancelled) throws IOException {
        if (implementation != null)
            return implementation;
        if (!System.getProperty("jdk.module.path", "").isEmpty())
            throw new IOException("UNDECLARED_WORKBENCH_MODULES");
        var entries = new ArrayList<Object>();
        for (String element : System.getProperty("java.class.path", "").split(Pattern.quote(File.pathSeparator), -1)) {
            if (element.isEmpty())
                throw new IOException("UNPINNED_WORKBENCH_CLASSPATH");
            var path = Path.of(element);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                var rows = new TreeMap<String, ContentDigest>();
                try (var paths = Files.walk(path)) {
                    for (var iterator = paths.iterator(); iterator.hasNext();) {
                        var file = iterator.next();
                        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))
                            continue;
                        rows.put(path.relativize(file).toString().replace('\\', '/'), hashFile(file, cancelled));
                    }
                } catch (java.io.UncheckedIOException unavailable) {
                    throw unavailable.getCause();
                }
                entries.add(List.of("directory", CanonicalJson.sha256(rows)));
            } else {
                var hash = hashFile(path, cancelled);
                try (var jar = new JarFile(path.toFile(), false)) {
                    var manifest = jar.getManifest();
                    if (manifest != null && manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH) != null)
                        throw new IOException("UNDECLARED_WORKBENCH_CLASSPATH");
                }
                entries.add(List.of("jar", hash));
            }
        }
        implementation = CanonicalJson.sha256(List.of("workbench-implementation:v1", entries));
        return implementation;
    }

    private static ContentDigest hashFile(Path path, BooleanSupplier cancelled) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("UNPINNED_WORKBENCH_INPUT");
        var digest = sha256();
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (cancelled.getAsBoolean())
                    throw new CancellationException();
                digest.update(buffer, 0, count);
            }
        }
        return new ContentDigest("sha256:" + HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Hash again before indexing a miss, since solver artifacts are still backed by
     * mutable paths. Verifies each unique artifact path at most once, parallelizing across
     * cores when many distinct JARs/JMODs are present.
     */
    static boolean stillCurrent(List<PlatformSymbolResult> platforms, MavenScan.Inputs dependencies,
            BooleanSupplier cancelled) {
        return stillCurrent(platforms, dependencies, cancelled, null);
    }

    static boolean stillCurrent(List<PlatformSymbolResult> platforms, MavenScan.Inputs dependencies,
            BooleanSupplier cancelled, Map<Path, ContentDigest> preverifiedMemo) {
        var expectedByPath = new LinkedHashMap<Path, ContentDigest>();
        for (var result : platforms) {
            for (var artifact : result.platform().orElseThrow().artifacts()) {
                Path p = artifact.path().toAbsolutePath().normalize();
                ContentDigest prior = expectedByPath.putIfAbsent(p, artifact.contentDigest());
                if (prior != null && !prior.equals(artifact.contentDigest())) return false;
            }
        }
        for (var resolution : dependencies.resolutions().values()) {
            for (BinaryInput binary : resolution.binaries()) {
                Path p = binary.path().toAbsolutePath().normalize();
                ContentDigest prior = expectedByPath.putIfAbsent(p, binary.entry().contentDigest());
                if (prior != null && !prior.equals(binary.entry().contentDigest())) return false;
            }
        }
        var toHash = new ArrayList<Map.Entry<Path, ContentDigest>>(expectedByPath.size());
        for (var entry : expectedByPath.entrySet()) {
            if (preverifiedMemo != null) {
                ContentDigest memo = preverifiedMemo.get(entry.getKey());
                if (memo != null) {
                    if (!entry.getValue().equals(memo)) return false;
                    continue;
                }
            }
            toHash.add(entry);
        }
        if (toHash.isEmpty()) return true;
        int workers = com.evolution.analysis.concurrent.BoundedTasks.automaticWorkers();
        if (toHash.size() < 8 || workers <= 1) {
            try {
                for (var entry : toHash) {
                    ContentDigest actual = hashFile(entry.getKey(), cancelled);
                    if (preverifiedMemo != null) preverifiedMemo.put(entry.getKey(), actual);
                    if (!entry.getValue().equals(actual)) return false;
                }
                return true;
            } catch (IOException | SecurityException unavailable) {
                return false;
            }
        }
        try (var tasks = new com.evolution.analysis.concurrent.BoundedTasks(
                workers, workers * 4, "evo-reuse-verify", cancelled)) {
            var futures = new ArrayList<com.evolution.analysis.concurrent.BoundedTasks.Task<Boolean>>(toHash.size());
            for (var entry : toHash) {
                futures.add(tasks.submit(() -> {
                    try {
                        ContentDigest actual = hashFile(entry.getKey(), cancelled);
                        if (preverifiedMemo != null) preverifiedMemo.put(entry.getKey(), actual);
                        return entry.getValue().equals(actual);
                    } catch (IOException | SecurityException unavailable) {
                        return false;
                    }
                }));
            }
            for (var f : futures) {
                if (!Boolean.TRUE.equals(f.await())) return false;
            }
            return true;
        } catch (CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    // Partial outcomes are eligible only for named, input-derived limits of this
    // passive lane.
    // New/unknown codes, unavailable dependencies and provider/resource failures
    // stay retryable.
    private static final Map<String, Set<String>> STABLE_GAPS = Map.ofEntries(
            Map.entry("repository.cas-compatibility", Set.of("MISSING_ROOT_POM")),
            Map.entry("build.maven-model", Set.of("GENERATED_SOURCES_NOT_ACQUIRED")),
            Map.entry("build.readiness-planner", Set.of("CONTEXT_DISCOVERY_OPEN", "NATIVE_MODEL_REQUIRED",
                    "BUILD_TOOLCHAIN_REQUIRED",
                    "EXACT_DEPENDENCY_CLOSURE_REQUIRED", "ANALYZED_PLATFORM_REQUIRED", "GENERATOR_CLOSURE_REQUIRED",
                    "NATIVE_BUILD_VALIDATION_REQUIRED", "NATIVE_SEMANTIC_VALIDATION_REQUIRED", "SOURCE_PLAN_REQUIRED",
                    "OVERLAPPING_SOURCE_OWNERS", "FILE_CLASSIFICATION_REQUIRED", "RAW_PATH_PROVIDER_REQUIRED",
                    "JPMS_MANIFEST_REQUIRED", "JSP_GENERATION_REQUIRED", "JAVAFX_PLATFORM_REQUIRED",
                    "WOVEN_BYTECODE_REQUIRED", "ANDROID_SDK_REQUIRED")),
            Map.entry("frontend.javaparser", Set.of("CATEGORY_PARTIAL", "CATEGORY_UNSUPPORTED")),
            Map.entry("spring.component-scan-ingestion", Set.of("ANNOTATION_EVIDENCE_MISSING",
                    "FRAMEWORK_JARS_UNRESOLVED_SOURCE_SCOPED", "VERSION_FAMILY_COMPATIBLE_UNPINNED")),
            Map.entry("spring.condition-lowering", Set.of("FRAMEWORK_JARS_UNRESOLVED_SOURCE_SCOPED",
                    "VERSION_FAMILY_COMPATIBLE_UNPINNED")),
            Map.entry("spring.repository-configuration-space", Set.of("EXTERNAL_DEPLOYMENT_OVERRIDES_UNOBSERVED",
                    "CONFIGURATION_SPACE_DIMENSION_BOUNDED")),
            Map.entry("spring.source-to-plan", Set.of("SOURCE_SCOPED_CONTAINER_CLOSURE")),
            Map.entry("scan.polyglot",
                    Set.of("NATIVE_CONTEXT_REQUIRED", "PROTOBUF_GENERATOR_REQUIRED", "SYNTAX_ERROR")),
            Map.entry("architecture.assessment", Set.of("POLICY_EVIDENCE_INCOMPLETE", "REQUIRED_METRIC_INCOMPLETE",
                    "RULE_EVIDENCE_INCOMPLETE", "SCORE_MODEL_NOT_APPROVED")),
            Map.entry("architecture.metrics", Set.of("UPSTREAM_CAPABILITY_GAP", "UPSTREAM_SNAPSHOT_GAP")),
            Map.entry("architecture.query", Set.of("RULE_DENOMINATOR_INCOMPLETE", "UPSTREAM_CAPABILITY_GAP")));

    static boolean stableGaps(KnowledgeGraph graph, Collection<CapabilityGapRecord> gaps,
            StaticPolyglotScan.Result syntax) {
        if (syntax.failures().keySet().stream().anyMatch(
                reason -> !reason.endsWith(":SYNTAX_ERROR") && !reason.endsWith(":SYNTAX_GRAMMAR_UNAVAILABLE")))
            return false;
        var accounted = new HashSet<String>();
        for (var gap : gaps) {
            if (!STABLE_GAPS.getOrDefault(gap.detectingProvider().id(), Set.of()).contains(gap.reasonCode()))
                return false;
            accounted.add(gap.gapIdentity().value());
        }
        return graph.nodes().stream().filter(node -> node.kind().equals("CAPABILITY_GAP"))
                .allMatch(node -> accounted.contains(node.identity()));
    }

    static boolean stableGaps(com.evolution.analysis.filesystem.PartitionedGraphStore.Publication publication,
            Collection<CapabilityGapRecord> gaps, StaticPolyglotScan.Result syntax) {
        if (syntax.failures().keySet().stream().anyMatch(
                reason -> !reason.endsWith(":SYNTAX_ERROR") && !reason.endsWith(":SYNTAX_GRAMMAR_UNAVAILABLE")))
            return false;
        var accounted = new HashSet<String>();
        var allowedReasons = new HashSet<String>();
        for (var gap : gaps) {
            if (!STABLE_GAPS.getOrDefault(gap.detectingProvider().id(), Set.of()).contains(gap.reasonCode()))
                return false;
            accounted.add(gap.gapIdentity().value());
            allowedReasons.add(gap.reasonCode());
        }
        for (String pubReason : publication.gapReasons().keySet()) {
            if (!allowedReasons.contains(pubReason)
                    && STABLE_GAPS.values().stream().noneMatch(set -> set.contains(pubReason))) {
                return false;
            }
        }
        return publication.gapNodes() <= accounted.size();
    }
}
