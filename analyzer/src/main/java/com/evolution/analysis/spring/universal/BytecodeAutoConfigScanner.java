package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.frontend.BinaryInput;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.ConditionEvidence;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Passive {@code --build=off} ASM bytecode extractor for Spring Boot starter JARs
 * and Go/No-Go COI/interaction spike analyzer.
 *
 * <p>Never invokes {@code Class.forName}, never creates a target {@code ClassLoader},
 * and never executes target builds or plugins on the host.
 */
public final class BytecodeAutoConfigScanner {

    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier(
            "spring.bytecode-autoconfig", "m8.4-s1-asm-v1");

    private static final String ENABLE_AUTO_CONFIG_KEY =
            "org.springframework.boot.autoconfigure.EnableAutoConfiguration";
    private static final String SPRING_FACTORIES_PATH = "META-INF/spring.factories";
    private static final String IMPORTS_PATH =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final String ANN_AUTO_CONFIGURATION =
            "org.springframework.boot.autoconfigure.AutoConfiguration";
    private static final String ANN_CONFIGURATION =
            "org.springframework.context.annotation.Configuration";
    private static final String ANN_BEAN =
            "org.springframework.context.annotation.Bean";
    private static final String ANN_IMPORT =
            "org.springframework.context.annotation.Import";
    private static final String ANN_PROFILE =
            "org.springframework.context.annotation.Profile";
    private static final String ANN_CONDITIONAL =
            "org.springframework.context.annotation.Conditional";
    private static final String ANN_PRIMARY =
            "org.springframework.context.annotation.Primary";
    private static final String ANN_FALLBACK =
            "org.springframework.context.annotation.Fallback";
    private static final String ANN_LAZY =
            "org.springframework.context.annotation.Lazy";
    private static final String ANN_ENABLE_CONFIG_PROPS =
            "org.springframework.boot.context.properties.EnableConfigurationProperties";
    private static final String ANN_AUTO_CONFIGURE_BEFORE =
            "org.springframework.boot.autoconfigure.AutoConfigureBefore";
    private static final String ANN_AUTO_CONFIGURE_AFTER =
            "org.springframework.boot.autoconfigure.AutoConfigureAfter";
    private static final String ANN_AUTO_CONFIGURE_ORDER =
            "org.springframework.boot.autoconfigure.AutoConfigureOrder";
    private static final String ANN_ORDER =
            "org.springframework.core.annotation.Order";
    private static final String SPRING_BOOT_CONDITION_PREFIX =
            "org.springframework.boot.autoconfigure.condition.";

    public record Limits(
            int maxJars,
            int maxMetadataBytes,
            int maxMetadataEntries,
            int maxClassesPerJar,
            int maxTotalClasses,
            int maxEntryBytes,
            int maxImportDepth) {
        public Limits {
            if (maxJars < 1 || maxMetadataBytes < 1 || maxMetadataEntries < 1
                    || maxClassesPerJar < 1 || maxTotalClasses < 1 || maxEntryBytes < 1
                    || maxImportDepth < 0 || maxImportDepth > 8) {
                throw new IllegalArgumentException("Invalid bytecode auto-config scan limits");
            }
        }

        public static Limits defaults() {
            return new Limits(512, 2_097_152, 8_192, 8_192, 24_576, 4_194_304, 3);
        }
    }

    public record ConditionAnnotation(
            String annotationFqn,
            Map<String, List<String>> attributes,
            boolean opaque) {
        public ConditionAnnotation {
            annotationFqn = Objects.requireNonNull(annotationFqn);
            var clean = new TreeMap<String, List<String>>();
            if (attributes != null) {
                attributes.forEach((k, v) -> clean.put(k, v == null ? List.of() : List.copyOf(v)));
            }
            attributes = Collections.unmodifiableMap(clean);
        }
    }

    public record OrderingAnnotation(
            String annotationFqn,
            List<String> targetClassFqns,
            List<String> targetClassNames,
            Optional<Integer> orderValue) {
        public OrderingAnnotation {
            annotationFqn = Objects.requireNonNull(annotationFqn);
            targetClassFqns = targetClassFqns == null ? List.of() : List.copyOf(targetClassFqns);
            targetClassNames = targetClassNames == null ? List.of() : List.copyOf(targetClassNames);
            orderValue = orderValue == null ? Optional.empty() : orderValue;
        }
    }

    public record StarterBean(
            String declaringClassFqn,
            String methodName,
            String methodDescriptor,
            String returnTypeFqn,
            List<String> beanNames,
            List<String> parameterTypeFqns,
            boolean primary,
            boolean fallback,
            boolean lazy,
            List<ConditionAnnotation> conditions,
            ConditionEvidence.Artifact evidence) {
        public StarterBean {
            declaringClassFqn = Objects.requireNonNull(declaringClassFqn);
            methodName = Objects.requireNonNull(methodName);
            methodDescriptor = Objects.requireNonNull(methodDescriptor);
            returnTypeFqn = Objects.requireNonNull(returnTypeFqn);
            beanNames = List.copyOf(beanNames);
            parameterTypeFqns = List.copyOf(parameterTypeFqns);
            conditions = List.copyOf(conditions);
            evidence = Objects.requireNonNull(evidence);
        }
    }

    public record StarterConfiguration(
            String classFqn,
            String jarLogicalName,
            ContentDigest jarDigest,
            String metadataSource,
            boolean autoConfiguration,
            boolean configuration,
            boolean proxyBeanMethods,
            List<String> importedClasses,
            List<String> enabledConfigProperties,
            List<String> nestedConfigurationClasses,
            List<ConditionAnnotation> conditions,
            List<OrderingAnnotation> orderings,
            List<StarterBean> beans,
            ConditionEvidence.Artifact evidence) {
        public StarterConfiguration {
            classFqn = Objects.requireNonNull(classFqn);
            jarLogicalName = Objects.requireNonNull(jarLogicalName);
            jarDigest = Objects.requireNonNull(jarDigest);
            metadataSource = Objects.requireNonNull(metadataSource);
            importedClasses = List.copyOf(importedClasses);
            enabledConfigProperties = List.copyOf(enabledConfigProperties);
            nestedConfigurationClasses = List.copyOf(nestedConfigurationClasses);
            conditions = List.copyOf(conditions);
            orderings = List.copyOf(orderings);
            beans = List.copyOf(beans);
            evidence = Objects.requireNonNull(evidence);
        }
    }

    public record ScanProblem(
            String jarLogicalName,
            ContentDigest jarDigest,
            String entryPath,
            String reason,
            String detail) {
        public ScanProblem {
            jarLogicalName = Objects.requireNonNull(jarLogicalName);
            jarDigest = Objects.requireNonNull(jarDigest);
            entryPath = Objects.requireNonNull(entryPath);
            reason = Objects.requireNonNull(reason);
            detail = Objects.requireNonNull(detail);
        }
    }

    public record ScanResult(
            ContentDigest identity,
            int scannedJars,
            int metadataFilesRead,
            int seedCandidates,
            List<StarterConfiguration> configurations,
            List<ScanProblem> problems,
            Map<String, Integer> conditionHistogram,
            Set<String> indexedClassFqns,
            long elapsedMs) {
        public ScanResult {
            identity = Objects.requireNonNull(identity);
            configurations = List.copyOf(configurations);
            problems = List.copyOf(problems);
            conditionHistogram = Collections.unmodifiableMap(new TreeMap<>(conditionHistogram));
            indexedClassFqns = Collections.unmodifiableSet(new TreeSet<>(indexedClassFqns));
        }

        public int totalBeans() {
            int count = 0;
            for (var cfg : configurations) {
                count += cfg.beans().size();
            }
            return count;
        }

        public int totalConditionMarkers() {
            int count = 0;
            for (var v : conditionHistogram.values()) {
                count += v;
            }
            return count;
        }

        public int totalOrderingAnnotations() {
            int count = 0;
            for (var cfg : configurations) {
                count += cfg.orderings().size();
            }
            return count;
        }

        public int metaExpandedConditionCount() {
            int count = 0;
            for (var cfg : configurations) {
                for (var c : cfg.conditions()) {
                    if (c.attributes().containsKey("_composedFrom")) {
                        count++;
                    }
                }
                for (var b : cfg.beans()) {
                    for (var c : b.conditions()) {
                        if (c.attributes().containsKey("_composedFrom")) {
                            count++;
                        }
                    }
                }
            }
            return count;
        }
    }

    public record UserSpringSummary(
            Set<String> userBeanTypeFqns,
            Set<String> userBeanNames,
            Set<String> userInjectedTypeFqns,
            Map<String, String> configuredProperties,
            Set<String> activeProfiles,
            Set<String> extraClasspathFqns) {
        public UserSpringSummary {
            userBeanTypeFqns = Collections.unmodifiableSet(new TreeSet<>(userBeanTypeFqns));
            userBeanNames = Collections.unmodifiableSet(new TreeSet<>(userBeanNames));
            userInjectedTypeFqns = Collections.unmodifiableSet(new TreeSet<>(userInjectedTypeFqns));
            configuredProperties = Collections.unmodifiableMap(new TreeMap<>(configuredProperties));
            activeProfiles = Collections.unmodifiableSet(new TreeSet<>(activeProfiles));
            extraClasspathFqns = Collections.unmodifiableSet(new TreeSet<>(extraClasspathFqns));
        }

        public static UserSpringSummary empty() {
            return new UserSpringSummary(Set.of(), Set.of(), Set.of(), Map.of(), Set.of(), Set.of());
        }
    }

    public record InteractionFinding(
            String kind,
            String starterClassFqn,
            String starterBeanOrKey,
            String userEvidence,
            String detail) {}

    public record CoiComponentMetrics(
            int totalStarterVars,
            int resolvedClassConditions,
            int activePropertyVars,
            int beanConditionVars,
            int classConditionVars,
            int orderingEdges,
            int componentCount,
            int maxComponentVars,
            double medianComponentVars,
            List<Integer> topComponentSizes,
            long elapsedMs) {
        public CoiComponentMetrics {
            topComponentSizes = List.copyOf(topComponentSizes);
        }
    }

    public record SpikeAnalysis(
            ContentDigest identity,
            int scannedJars,
            int metadataFilesRead,
            int seedCandidates,
            int totalConfigurations,
            int totalBeans,
            int totalConditionMarkers,
            int totalOrderingAnnotations,
            int g1UserStarterInteractions,
            int g2ConditionalFindings,
            CoiComponentMetrics g3CoiMetrics,
            Map<String, Integer> interactionBreakdown,
            Map<String, Integer> conditionHistogram,
            List<InteractionFinding> sampleFindings,
            List<ScanProblem> problems,
            Map<String, Boolean> gateChecks,
            String goNoGoRecommendation) {
        public SpikeAnalysis {
            identity = Objects.requireNonNull(identity);
            interactionBreakdown = Collections.unmodifiableMap(new TreeMap<>(interactionBreakdown));
            conditionHistogram = Collections.unmodifiableMap(new TreeMap<>(conditionHistogram));
            sampleFindings = List.copyOf(sampleFindings);
            problems = List.copyOf(problems);
            gateChecks = Collections.unmodifiableMap(new TreeMap<>(gateChecks));
        }

        public Map<String, Object> toMap() {
            var root = new TreeMap<String, Object>();
            root.put("identity", identity.value());
            root.put("provider", PROVIDER.id() + "@" + PROVIDER.version());
            root.put("scannedJars", scannedJars);
            root.put("metadataFilesRead", metadataFilesRead);
            root.put("seedCandidates", seedCandidates);
            root.put("totalConfigurations", totalConfigurations);
            root.put("totalBeans", totalBeans);
            root.put("totalConditionMarkers", totalConditionMarkers);
            root.put("totalOrderingAnnotations", totalOrderingAnnotations);
            root.put("g1UserStarterInteractions", g1UserStarterInteractions);
            root.put("g2ConditionalFindings", g2ConditionalFindings);
            var coi = new TreeMap<String, Object>();
            coi.put("totalStarterVars", g3CoiMetrics.totalStarterVars());
            coi.put("resolvedClassConditions", g3CoiMetrics.resolvedClassConditions());
            coi.put("activePropertyVars", g3CoiMetrics.activePropertyVars());
            coi.put("beanConditionVars", g3CoiMetrics.beanConditionVars());
            coi.put("classConditionVars", g3CoiMetrics.classConditionVars());
            coi.put("orderingEdges", g3CoiMetrics.orderingEdges());
            coi.put("componentCount", g3CoiMetrics.componentCount());
            coi.put("maxComponentVars", g3CoiMetrics.maxComponentVars());
            coi.put("medianComponentVars", java.math.BigDecimal.valueOf(g3CoiMetrics.medianComponentVars()));
            coi.put("topComponentSizes", g3CoiMetrics.topComponentSizes());
            coi.put("elapsedMs", g3CoiMetrics.elapsedMs());
            root.put("g3CoiMetrics", coi);
            root.put("interactionBreakdown", interactionBreakdown);
            root.put("conditionHistogram", conditionHistogram);
            var findingRows = new ArrayList<Map<String, Object>>(sampleFindings.size());
            for (var f : sampleFindings) {
                var row = new TreeMap<String, Object>();
                row.put("kind", f.kind());
                row.put("starterClassFqn", f.starterClassFqn());
                row.put("starterBeanOrKey", f.starterBeanOrKey());
                row.put("userEvidence", f.userEvidence());
                row.put("detail", f.detail());
                findingRows.add(row);
            }
            root.put("sampleFindings", findingRows);
            var problemRows = new ArrayList<Map<String, Object>>(problems.size());
            for (var p : problems) {
                var row = new TreeMap<String, Object>();
                row.put("jarLogicalName", p.jarLogicalName());
                row.put("jarDigest", p.jarDigest().value());
                row.put("entryPath", p.entryPath());
                row.put("reason", p.reason());
                row.put("detail", p.detail());
                problemRows.add(row);
            }
            root.put("problems", problemRows);
            root.put("gateChecks", gateChecks);
            root.put("goNoGoRecommendation", goNoGoRecommendation);
            return Collections.unmodifiableMap(root);
        }
    }

    private record JarClassRef(
            BinaryInput binary,
            ContentDigest jarDigest,
            String entryPath) {}

    private record SeedTask(
            String classFqn,
            String metadataSource,
            int depth,
            boolean requiredTarget) {}

    public static ScanResult scan(
            List<BinaryInput> dependencies,
            FrameworkGeneration.MetadataPolicy policy,
            Limits limits) {
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(limits, "limits");
        long tStart = System.nanoTime();

        var uniqueDeps = new LinkedHashMap<String, BinaryInput>();
        for (var dep : dependencies) {
            if (dep == null) {
                continue;
            }
            String key = dep.entry().contentDigest().value() + "|" + dep.path();
            uniqueDeps.putIfAbsent(key, dep);
        }
        var sortedDeps = uniqueDeps.values().stream()
                .sorted(Comparator.comparing((BinaryInput b) -> b.entry().contentDigest().value())
                        .thenComparing(b -> b.entry().logicalName())
                        .thenComparing(b -> b.path().toString()))
                .toList();

        var problems = new ArrayList<ScanProblem>();
        var indexedClasses = new HashMap<String, JarClassRef>(4096);
        var indexedFqns = new TreeSet<String>();
        var seedSources = new TreeMap<String, Set<String>>();
        int scannedJars = 0;
        int metadataFilesRead = 0;
        long totalMetadataBytes = 0L;
        int totalMetadataEntries = 0;

        boolean readFactories = policy == FrameworkGeneration.MetadataPolicy.FACTORIES
                || policy == FrameworkGeneration.MetadataPolicy.BOTH
                || policy == FrameworkGeneration.MetadataPolicy.UNKNOWN;
        boolean readImports = policy == FrameworkGeneration.MetadataPolicy.IMPORTS
                || policy == FrameworkGeneration.MetadataPolicy.BOTH
                || policy == FrameworkGeneration.MetadataPolicy.UNKNOWN;

        for (var dep : sortedDeps) {
            if (scannedJars >= limits.maxJars()) {
                problems.add(new ScanProblem(
                        dep.entry().logicalName(), dep.entry().contentDigest(),
                        dep.entry().logicalName(), "RESOURCE_LIMIT",
                        "Exceeded maxJars=" + limits.maxJars()));
                break;
            }
            if (!Files.isRegularFile(dep.path(), LinkOption.NOFOLLOW_LINKS)) {
                problems.add(new ScanProblem(
                        dep.entry().logicalName(), dep.entry().contentDigest(),
                        dep.entry().logicalName(), "JAR_UNAVAILABLE",
                        "JAR path is missing or not a regular file: " + dep.path().getFileName()));
                continue;
            }
            scannedJars++;
            try (var zip = new ZipFile(dep.path().toFile(), StandardCharsets.UTF_8)) {
                int jarClassCount = 0;
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    if (!name.endsWith(".class")
                            || name.equals("module-info.class")
                            || name.endsWith("/package-info.class")
                            || name.equals("package-info.class")
                            || name.startsWith("META-INF/versions/")) {
                        continue;
                    }
                    jarClassCount++;
                    if (jarClassCount > limits.maxClassesPerJar()) {
                        problems.add(new ScanProblem(
                                dep.entry().logicalName(), dep.entry().contentDigest(),
                                name, "RESOURCE_LIMIT",
                                "Exceeded maxClassesPerJar=" + limits.maxClassesPerJar()));
                        break;
                    }
                    String fqn = name.substring(0, name.length() - 6).replace('/', '.');
                    indexedFqns.add(fqn);
                    indexedClasses.putIfAbsent(fqn, new JarClassRef(dep, dep.entry().contentDigest(), name));
                }

                if (policy == FrameworkGeneration.MetadataPolicy.NONE) {
                    continue;
                }
                if (readFactories) {
                    var entry = zip.getEntry(SPRING_FACTORIES_PATH);
                    if (entry != null && !entry.isDirectory()) {
                        metadataFilesRead++;
                        byte[] raw = readEntryBounded(zip, entry, limits.maxMetadataBytes() + 1);
                        totalMetadataBytes += raw.length;
                        if (raw.length > limits.maxMetadataBytes() || totalMetadataBytes > limits.maxMetadataBytes()) {
                            problems.add(new ScanProblem(
                                    dep.entry().logicalName(), dep.entry().contentDigest(),
                                    SPRING_FACTORIES_PATH, "RESOURCE_LIMIT",
                                    "Metadata byte budget exceeded: maxMetadataBytes=" + limits.maxMetadataBytes()));
                        } else {
                            try {
                                List<String> candidates = parseSpringFactories(raw);
                                totalMetadataEntries += candidates.size();
                                if (totalMetadataEntries > limits.maxMetadataEntries()) {
                                    problems.add(new ScanProblem(
                                            dep.entry().logicalName(), dep.entry().contentDigest(),
                                            SPRING_FACTORIES_PATH, "RESOURCE_LIMIT",
                                            "Exceeded maxMetadataEntries=" + limits.maxMetadataEntries()));
                                } else {
                                    for (String c : candidates) {
                                        seedSources.computeIfAbsent(c, ignored -> new TreeSet<>())
                                                .add(SPRING_FACTORIES_PATH);
                                    }
                                }
                            } catch (IOException | IllegalArgumentException invalid) {
                                problems.add(new ScanProblem(
                                        dep.entry().logicalName(), dep.entry().contentDigest(),
                                        SPRING_FACTORIES_PATH, "METADATA_INVALID",
                                        safeMsg(invalid)));
                            }
                        }
                    }
                }
                if (readImports) {
                    var entry = zip.getEntry(IMPORTS_PATH);
                    if (entry != null && !entry.isDirectory()) {
                        metadataFilesRead++;
                        byte[] raw = readEntryBounded(zip, entry, limits.maxMetadataBytes() + 1);
                        totalMetadataBytes += raw.length;
                        if (raw.length > limits.maxMetadataBytes() || totalMetadataBytes > limits.maxMetadataBytes()) {
                            problems.add(new ScanProblem(
                                    dep.entry().logicalName(), dep.entry().contentDigest(),
                                    IMPORTS_PATH, "RESOURCE_LIMIT",
                                    "Metadata byte budget exceeded: maxMetadataBytes=" + limits.maxMetadataBytes()));
                        } else {
                            try {
                                List<String> candidates = parseAutoConfigImports(raw);
                                totalMetadataEntries += candidates.size();
                                if (totalMetadataEntries > limits.maxMetadataEntries()) {
                                    problems.add(new ScanProblem(
                                            dep.entry().logicalName(), dep.entry().contentDigest(),
                                            IMPORTS_PATH, "RESOURCE_LIMIT",
                                            "Exceeded maxMetadataEntries=" + limits.maxMetadataEntries()));
                                } else {
                                    for (String c : candidates) {
                                        seedSources.computeIfAbsent(c, ignored -> new TreeSet<>())
                                                .add(IMPORTS_PATH);
                                    }
                                }
                            } catch (IOException | IllegalArgumentException invalid) {
                                problems.add(new ScanProblem(
                                        dep.entry().logicalName(), dep.entry().contentDigest(),
                                        IMPORTS_PATH, "METADATA_INVALID",
                                        safeMsg(invalid)));
                            }
                        }
                    }
                }
            } catch (ZipException invalidZip) {
                problems.add(new ScanProblem(
                        dep.entry().logicalName(), dep.entry().contentDigest(),
                        dep.entry().logicalName(), "JAR_INVALID", safeMsg(invalidZip)));
            } catch (IOException ioFailed) {
                problems.add(new ScanProblem(
                        dep.entry().logicalName(), dep.entry().contentDigest(),
                        dep.entry().logicalName(), "JAR_INVALID", safeMsg(ioFailed)));
            }
        }

        // Group bytecode parsing by JAR path so each ZipFile opens only once during Pass 2
        var queue = new ArrayDeque<SeedTask>();
        var seenFqns = new HashSet<String>(Math.max(16, seedSources.size() * 2));
        for (var e : seedSources.entrySet()) {
            String src = String.join(",", e.getValue());
            queue.add(new SeedTask(e.getKey(), src, 0, true));
            seenFqns.add(e.getKey());
        }

        // Open ZipFiles lazily cache per path during BFS, closing safely in finally
        var openZips = new HashMap<String, ZipFile>();
        var metaCache = new HashMap<String, Optional<ParsedAnnotationType>>();
        var configurations = new ArrayList<StarterConfiguration>();
        var histogram = new TreeMap<String, Integer>();
        int inspectedClasses = 0;

        try {
            while (!queue.isEmpty()) {
                var task = queue.poll();
                var ref = indexedClasses.get(task.classFqn());
                if (ref == null) {
                    if (task.requiredTarget()) {
                        problems.add(new ScanProblem(
                                "classpath",
                                ContentDigest.sha256Utf8("classpath-lookup"),
                                task.classFqn().replace('.', '/') + ".class",
                                "ENTRY_MISSING",
                                "Class referenced from " + task.metadataSource() + " not found in supplied JARs: "
                                        + task.classFqn()));
                    }
                    continue;
                }
                if (inspectedClasses >= limits.maxTotalClasses()) {
                    problems.add(new ScanProblem(
                            ref.binary().entry().logicalName(), ref.jarDigest(),
                            ref.entryPath(), "RESOURCE_LIMIT",
                            "Exceeded maxTotalClasses=" + limits.maxTotalClasses()));
                    break;
                }
                inspectedClasses++;

                ZipFile zip = openZips.get(ref.binary().path().toString());
                if (zip == null) {
                    try {
                        zip = new ZipFile(ref.binary().path().toFile(), StandardCharsets.UTF_8);
                        openZips.put(ref.binary().path().toString(), zip);
                    } catch (IOException ioFailed) {
                        problems.add(new ScanProblem(
                                ref.binary().entry().logicalName(), ref.jarDigest(),
                                ref.entryPath(), "JAR_INVALID", safeMsg(ioFailed)));
                        continue;
                    }
                }
                ZipEntry entry = zip.getEntry(ref.entryPath());
                if (entry == null) {
                    if (task.requiredTarget()) {
                        problems.add(new ScanProblem(
                                ref.binary().entry().logicalName(), ref.jarDigest(),
                                ref.entryPath(), "ENTRY_MISSING",
                                "Entry missing inside JAR: " + ref.entryPath()));
                    }
                    continue;
                }
                byte[] classBytes;
                try {
                    classBytes = readEntryBounded(zip, entry, limits.maxEntryBytes() + 1);
                } catch (IOException ioFailed) {
                    problems.add(new ScanProblem(
                            ref.binary().entry().logicalName(), ref.jarDigest(),
                            ref.entryPath(), "CLASS_UNREADABLE", safeMsg(ioFailed)));
                    continue;
                }
                if (classBytes.length > limits.maxEntryBytes()) {
                    problems.add(new ScanProblem(
                            ref.binary().entry().logicalName(), ref.jarDigest(),
                            ref.entryPath(), "RESOURCE_LIMIT",
                            "Class entry exceeds maxEntryBytes=" + limits.maxEntryBytes()));
                    continue;
                }

                ContentDigest entryDigest = ContentDigest.sha256(classBytes);
                var evidence = new ConditionEvidence.Artifact(ref.jarDigest(), entryDigest, ref.entryPath());
                ParsedClass parsed;
                try {
                    parsed = parseClassBytes(classBytes, task.classFqn(), evidence);
                } catch (RuntimeException invalidClass) {
                    problems.add(new ScanProblem(
                            ref.binary().entry().logicalName(), ref.jarDigest(),
                            ref.entryPath(), "CLASS_UNREADABLE", safeMsg(invalidClass)));
                    continue;
                }

                var classResolvedConditions = new ArrayList<>(parsed.conditions);
                for (var rawAnn : parsed.candidateMetaAnnotations) {
                    expandMetaAnnotations(rawAnn, indexedClasses, openZips, limits, metaCache, classResolvedConditions);
                }
                var expandedBeans = new ArrayList<StarterBean>(parsed.beans.size());
                for (var rawBean : parsed.beans) {
                    var mergedConds = new ArrayList<>(rawBean.conditions());
                    for (var rawAnn : rawBean.candidateMetaAnnotations()) {
                        expandMetaAnnotations(rawAnn, indexedClasses, openZips, limits, metaCache, mergedConds);
                    }
                    var sortedBeanConds = mergedConds.stream()
                            .distinct()
                            .sorted(Comparator.comparing(ConditionAnnotation::annotationFqn)
                                    .thenComparing(c -> c.attributes().toString()))
                            .toList();
                    expandedBeans.add(new StarterBean(
                            rawBean.declaringClassFqn(),
                            rawBean.methodName(),
                            rawBean.methodDescriptor(),
                            rawBean.returnTypeFqn(),
                            rawBean.beanNames(),
                            rawBean.parameterTypeFqns(),
                            rawBean.primary(),
                            rawBean.fallback(),
                            rawBean.lazy(),
                            sortedBeanConds,
                            rawBean.evidence()));
                }

                boolean relevant = task.depth() == 0
                        || parsed.autoConfiguration
                        || parsed.configuration
                        || !expandedBeans.isEmpty()
                        || !classResolvedConditions.isEmpty()
                        || !parsed.orderings.isEmpty()
                        || !parsed.importedClasses.isEmpty();
                if (!relevant) {
                    continue;
                }

                var sortedConditions = classResolvedConditions.stream()
                        .distinct()
                        .sorted(Comparator.comparing(ConditionAnnotation::annotationFqn)
                                .thenComparing(c -> c.attributes().toString()))
                        .toList();
                var sortedOrderings = parsed.orderings.stream()
                        .sorted(Comparator.comparing(OrderingAnnotation::annotationFqn)
                                .thenComparing(o -> o.targetClassFqns().toString()))
                        .toList();
                var sortedBeans = expandedBeans.stream()
                        .sorted(Comparator.comparing(StarterBean::methodName)
                                .thenComparing(StarterBean::methodDescriptor))
                        .toList();

                for (var c : sortedConditions) {
                    histogram.merge(simpleName(c.annotationFqn()), 1, Integer::sum);
                }
                for (var b : sortedBeans) {
                    for (var c : b.conditions()) {
                        histogram.merge(simpleName(c.annotationFqn()), 1, Integer::sum);
                    }
                }

                var imports = parsed.importedClasses.stream().distinct().sorted().toList();
                var enableProps = parsed.enabledConfigProperties.stream().distinct().sorted().toList();
                var innerConfigs = parsed.nestedClasses.stream().distinct().sorted().toList();

                configurations.add(new StarterConfiguration(
                        parsed.classFqn,
                        ref.binary().entry().logicalName(),
                        ref.jarDigest(),
                        task.metadataSource(),
                        parsed.autoConfiguration,
                        parsed.configuration,
                        parsed.proxyBeanMethods,
                        imports,
                        enableProps,
                        innerConfigs,
                        sortedConditions,
                        sortedOrderings,
                        sortedBeans,
                        evidence));

                if (task.depth() < limits.maxImportDepth()) {
                    for (String imp : imports) {
                        if (seenFqns.add(imp)) {
                            queue.add(new SeedTask(imp, "import:" + parsed.classFqn, task.depth() + 1, false));
                        }
                    }
                    for (String nested : innerConfigs) {
                        if (seenFqns.add(nested)) {
                            queue.add(new SeedTask(nested, "inner:" + parsed.classFqn, task.depth() + 1, false));
                        }
                    }
                }
            }
        } finally {
            for (var z : openZips.values()) {
                try {
                    z.close();
                } catch (IOException ignored) {
                }
            }
        }

        configurations.sort(Comparator.comparing((StarterConfiguration c) -> c.jarDigest().value())
                .thenComparing(StarterConfiguration::classFqn));
        problems.sort(Comparator.comparing((ScanProblem p) -> p.jarDigest().value())
                .thenComparing(ScanProblem::entryPath)
                .thenComparing(ScanProblem::reason)
                .thenComparing(ScanProblem::detail));

        long elapsedMs = Math.max(0L, (System.nanoTime() - tStart) / 1_000_000L);
        ContentDigest identity = IngestionEvidence.digest(Map.of(
                "provider", PROVIDER,
                "scannedJars", scannedJars,
                "metadataFilesRead", metadataFilesRead,
                "seedCandidates", seedSources.size(),
                "configurations", configurations,
                "problems", problems));

        return new ScanResult(
                identity,
                scannedJars,
                metadataFilesRead,
                seedSources.size(),
                configurations,
                problems,
                histogram,
                indexedFqns,
                elapsedMs);
    }

    public static SpikeAnalysis analyzeInteractions(ScanResult scan, UserSpringSummary user) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(user, "user");
        long tCoiStart = System.nanoTime();

        var knownClasses = new HashSet<String>(scan.indexedClassFqns().size() + user.extraClasspathFqns().size() + 64);
        knownClasses.addAll(scan.indexedClassFqns());
        knownClasses.addAll(user.extraClasspathFqns());
        knownClasses.addAll(user.userBeanTypeFqns());

        var userBeanSimpleNames = new HashSet<String>();
        for (String fqn : user.userBeanTypeFqns()) {
            userBeanSimpleNames.add(simpleName(fqn));
        }
        var userInjectedSimpleNames = new HashSet<String>();
        for (String fqn : user.userInjectedTypeFqns()) {
            userInjectedSimpleNames.add(simpleName(fqn));
        }
        var normalizedProps = new TreeMap<String, String>();
        user.configuredProperties().forEach((k, v) -> normalizedProps.put(normalizePropertyKey(k), v == null ? "" : v));

        var findings = new ArrayList<InteractionFinding>();
        var breakdown = new TreeMap<String, Integer>();

        // Bipartite COI graph: node id -> neighbor node ids
        var graph = new HashMap<String, Set<String>>();
        var varNodes = new HashSet<String>();
        var resolvedClassNodes = new HashSet<String>();
        var propVarNodes = new HashSet<String>();
        var beanVarNodes = new HashSet<String>();
        var classVarNodes = new HashSet<String>();
        int orderingEdgeCount = 0;

        var configByFqn = new HashMap<String, StarterConfiguration>(scan.configurations().size() * 2);
        var starterBeansByReturnType = new HashMap<String, List<String>>();
        for (var cfg : scan.configurations()) {
            configByFqn.putIfAbsent(cfg.classFqn(), cfg);
            for (var bean : cfg.beans()) {
                starterBeansByReturnType
                        .computeIfAbsent(bean.returnTypeFqn(), ignored -> new ArrayList<>())
                        .add("cfg:" + cfg.classFqn());
            }
        }

        for (var cfg : scan.configurations()) {
            String cfgNode = "cfg:" + cfg.classFqn();
            graph.computeIfAbsent(cfgNode, ignored -> new LinkedHashSet<>());

            // Ordering edges inside COI graph
            for (var ord : cfg.orderings()) {
                for (String target : ord.targetClassFqns()) {
                    if (configByFqn.containsKey(target)) {
                        addUndirectedEdge(graph, cfgNode, "cfg:" + target);
                        orderingEdgeCount++;
                    }
                }
                for (String targetName : ord.targetClassNames()) {
                    if (configByFqn.containsKey(targetName)) {
                        addUndirectedEdge(graph, cfgNode, "cfg:" + targetName);
                        orderingEdgeCount++;
                    }
                }
            }
            for (String imp : cfg.importedClasses()) {
                if (configByFqn.containsKey(imp)) {
                    addUndirectedEdge(graph, cfgNode, "cfg:" + imp);
                    orderingEdgeCount++;
                }
            }

            // Evaluate class-level conditions
            for (var cond : cfg.conditions()) {
                registerConditionInCoi(
                        cfg.classFqn(), cfgNode, null, cond, knownClasses,
                        graph, varNodes, resolvedClassNodes, propVarNodes, beanVarNodes, classVarNodes,
                        starterBeansByReturnType);
                recordUserInteraction(
                        cfg.classFqn(), "<class>", null, cond,
                        user, userBeanSimpleNames, normalizedProps, findings);
            }

            // Evaluate @Bean methods & parameters
            for (var bean : cfg.beans()) {
                String beanLabel = bean.returnTypeFqn() + "#" + bean.methodName();
                if (!bean.returnTypeFqn().equals("void")
                        && !bean.returnTypeFqn().equals("java.lang.Object")
                        && (user.userInjectedTypeFqns().contains(bean.returnTypeFqn())
                                || userInjectedSimpleNames.contains(simpleName(bean.returnTypeFqn())))) {
                    findings.add(new InteractionFinding(
                            "STARTER_PROVIDES_INJECTION_FOR_USER",
                            cfg.classFqn(),
                            beanLabel,
                            bean.returnTypeFqn(),
                            "Starter @Bean supplies injectable type used by user code"));
                }
                for (var cond : bean.conditions()) {
                    registerConditionInCoi(
                            cfg.classFqn(), cfgNode, bean.returnTypeFqn(), cond, knownClasses,
                            graph, varNodes, resolvedClassNodes, propVarNodes, beanVarNodes, classVarNodes,
                            starterBeansByReturnType);
                    recordUserInteraction(
                            cfg.classFqn(), beanLabel, bean, cond,
                            user, userBeanSimpleNames, normalizedProps, findings);
                }
            }
        }

        // Deduplicate and sort findings deterministically
        var uniqueFindings = findings.stream()
                .distinct()
                .sorted(Comparator.comparing(InteractionFinding::kind)
                        .thenComparing(InteractionFinding::starterClassFqn)
                        .thenComparing(InteractionFinding::starterBeanOrKey)
                        .thenComparing(InteractionFinding::userEvidence))
                .toList();
        for (var f : uniqueFindings) {
            breakdown.merge(f.kind(), 1, Integer::sum);
        }

        int g1Count = uniqueFindings.size();
        int g2Count = 0;
        for (var f : uniqueFindings) {
            if (f.kind().equals("USER_OVERRIDES_STARTER_MISSING_BEAN")
                    || f.kind().equals("USER_PROPERTY_GATES_STARTER")
                    || f.kind().equals("PROFILE_GATED_STARTER")) {
                g2Count++;
            }
        }

        // Compute connected components on COI graph and count active variable nodes per component
        var visited = new HashSet<String>(graph.size() * 2);
        var componentSizes = new ArrayList<Integer>();
        for (String start : graph.keySet()) {
            if (!visited.add(start)) {
                continue;
            }
            var bfs = new ArrayDeque<String>();
            bfs.add(start);
            int compVarCount = 0;
            while (!bfs.isEmpty()) {
                String cur = bfs.poll();
                if (varNodes.contains(cur)) {
                    compVarCount++;
                }
                for (String nxt : graph.getOrDefault(cur, Set.of())) {
                    if (visited.add(nxt)) {
                        bfs.add(nxt);
                    }
                }
            }
            if (compVarCount > 0) {
                componentSizes.add(compVarCount);
            }
        }
        componentSizes.sort(Comparator.reverseOrder());
        int maxComp = componentSizes.isEmpty() ? 0 : componentSizes.getFirst();
        double medianComp = 0.0;
        if (!componentSizes.isEmpty()) {
            int n = componentSizes.size();
            if (n % 2 == 1) {
                medianComp = componentSizes.get(n / 2);
            } else {
                medianComp = (componentSizes.get((n / 2) - 1) + componentSizes.get(n / 2)) / 2.0;
            }
        }
        List<Integer> topSizes = componentSizes.subList(0, Math.min(10, componentSizes.size()));
        long coiElapsedMs = Math.max(0L, (System.nanoTime() - tCoiStart) / 1_000_000L);

        var coiMetrics = new CoiComponentMetrics(
                varNodes.size(),
                resolvedClassNodes.size(),
                propVarNodes.size(),
                beanVarNodes.size(),
                classVarNodes.size(),
                orderingEdgeCount,
                componentSizes.size(),
                maxComp,
                medianComp,
                topSizes,
                coiElapsedMs);

        var gates = new TreeMap<String, Boolean>();
        gates.put("g1InteractionThresholdMet", g1Count >= 10);
        gates.put("g2FindingsThresholdMet", g2Count >= 1);
        gates.put("g3CoiScalable", maxComp <= 64);

        String recommendation = Boolean.TRUE.equals(gates.get("g1InteractionThresholdMet"))
                && Boolean.TRUE.equals(gates.get("g2FindingsThresholdMet"))
                && Boolean.TRUE.equals(gates.get("g3CoiScalable"))
                        ? "GO_CANDIDATE"
                        : "BELOW_SINGLE_REPO_GATE";

        var sample = uniqueFindings.subList(0, Math.min(50, uniqueFindings.size()));
        ContentDigest spikeId = IngestionEvidence.digest(Map.of(
                "scanIdentity", scan.identity(),
                "user", user,
                "g1", g1Count,
                "g2", g2Count,
                "totalStarterVars", coiMetrics.totalStarterVars(),
                "componentCount", coiMetrics.componentCount(),
                "maxComponentVars", coiMetrics.maxComponentVars(),
                "sampleFindings", sample));

        return new SpikeAnalysis(
                spikeId,
                scan.scannedJars(),
                scan.metadataFilesRead(),
                scan.seedCandidates(),
                scan.configurations().size(),
                scan.totalBeans(),
                scan.totalConditionMarkers(),
                scan.totalOrderingAnnotations(),
                g1Count,
                g2Count,
                coiMetrics,
                breakdown,
                scan.conditionHistogram(),
                sample,
                scan.problems(),
                gates,
                recommendation);
    }

    private static void registerConditionInCoi(
            String classFqn,
            String cfgNode,
            String beanReturnType,
            ConditionAnnotation cond,
            Set<String> knownClasses,
            Map<String, Set<String>> graph,
            Set<String> varNodes,
            Set<String> resolvedClassNodes,
            Set<String> propVarNodes,
            Set<String> beanVarNodes,
            Set<String> classVarNodes,
            Map<String, List<String>> starterBeansByReturnType) {
        String simple = simpleName(cond.annotationFqn());
        if (simple.equals("ConditionalOnProperty")) {
            for (String key : extractPropertyKeys(cond)) {
                String varId = "prop:" + key;
                varNodes.add(varId);
                propVarNodes.add(varId);
                addUndirectedEdge(graph, cfgNode, varId);
            }
            return;
        }
        if (simple.equals("Profile")) {
            for (String profile : cond.attributes().getOrDefault("value", List.of())) {
                String norm = profile.startsWith("!") ? profile.substring(1).trim() : profile.trim();
                if (!norm.isEmpty()) {
                    String varId = "profile:" + norm;
                    varNodes.add(varId);
                    propVarNodes.add(varId);
                    addUndirectedEdge(graph, cfgNode, varId);
                }
            }
            return;
        }
        if (simple.equals("ConditionalOnClass") || simple.equals("ConditionalOnMissingClass")) {
            var targets = new ArrayList<String>();
            targets.addAll(cond.attributes().getOrDefault("value", List.of()));
            targets.addAll(cond.attributes().getOrDefault("name", List.of()));
            for (String target : targets) {
                if (!knownClasses.isEmpty() && !isStandardJdkType(target)) {
                    // Closed supplied JAR classpath resolves class presence statically without spawning SAT variables
                    resolvedClassNodes.add(target);
                } else {
                    String varId = "class:" + target;
                    varNodes.add(varId);
                    classVarNodes.add(varId);
                    addUndirectedEdge(graph, cfgNode, varId);
                }
            }
            return;
        }
        if (simple.equals("ConditionalOnBean")
                || simple.equals("ConditionalOnMissingBean")
                || simple.equals("ConditionalOnSingleCandidate")) {
            var targets = new ArrayList<String>();
            targets.addAll(cond.attributes().getOrDefault("value", List.of()));
            targets.addAll(cond.attributes().getOrDefault("type", List.of()));
            targets.addAll(cond.attributes().getOrDefault("name", List.of()));
            if (targets.isEmpty() && beanReturnType != null && !beanReturnType.equals("void")) {
                targets.add(beanReturnType);
            }
            for (String t : targets) {
                String varId = "bean:" + t;
                varNodes.add(varId);
                beanVarNodes.add(varId);
                addUndirectedEdge(graph, cfgNode, varId);
                for (String producerCfg : starterBeansByReturnType.getOrDefault(t, List.of())) {
                    addUndirectedEdge(graph, cfgNode, producerCfg);
                }
            }
            return;
        }
        if (cond.opaque() || simple.startsWith("Conditional")) {
            String varId = "opaque:" + classFqn + "#" + simple;
            varNodes.add(varId);
            addUndirectedEdge(graph, cfgNode, varId);
        }
    }

    private static void recordUserInteraction(
            String starterClassFqn,
            String targetLabel,
            StarterBean bean,
            ConditionAnnotation cond,
            UserSpringSummary user,
            Set<String> userBeanSimpleNames,
            Map<String, String> normalizedProps,
            List<InteractionFinding> findings) {
        String simple = simpleName(cond.annotationFqn());
        if (simple.equals("ConditionalOnMissingBean") || simple.equals("ConditionalOnBean")
                || simple.equals("ConditionalOnSingleCandidate")) {
            boolean missingBean = simple.equals("ConditionalOnMissingBean");
            var types = new LinkedHashSet<String>();
            types.addAll(cond.attributes().getOrDefault("value", List.of()));
            types.addAll(cond.attributes().getOrDefault("type", List.of()));
            if (types.isEmpty() && cond.attributes().getOrDefault("name", List.of()).isEmpty()
                    && bean != null && !bean.returnTypeFqn().equals("void")) {
                types.add(bean.returnTypeFqn());
            }
            for (String typeFqn : types) {
                String matched = null;
                if (user.userBeanTypeFqns().contains(typeFqn)) {
                    matched = typeFqn;
                } else if (userBeanSimpleNames.contains(simpleName(typeFqn))) {
                    matched = findMatchingFqn(user.userBeanTypeFqns(), simpleName(typeFqn));
                }
                if (matched != null) {
                    findings.add(new InteractionFinding(
                            missingBean ? "USER_OVERRIDES_STARTER_MISSING_BEAN" : "USER_BEAN_TRIGGERS_STARTER",
                            starterClassFqn,
                            targetLabel,
                            matched,
                            "User bean matches @" + simple + "(" + typeFqn + ")"));
                }
            }
            for (String beanName : cond.attributes().getOrDefault("name", List.of())) {
                if (user.userBeanNames().contains(beanName)) {
                    findings.add(new InteractionFinding(
                            missingBean ? "USER_OVERRIDES_STARTER_MISSING_BEAN" : "USER_BEAN_TRIGGERS_STARTER",
                            starterClassFqn,
                            targetLabel,
                            beanName,
                            "User bean name matches @" + simple + "(name=" + beanName + ")"));
                }
            }
        } else if (simple.equals("ConditionalOnProperty")) {
            List<String> keys = extractPropertyKeys(cond);
            List<String> having = cond.attributes().getOrDefault("havingValue", List.of());
            String expected = having.isEmpty() ? null : having.getFirst();
            for (String key : keys) {
                if (normalizedProps.containsKey(key)) {
                    String actual = normalizedProps.get(key);
                    boolean matches = expected == null
                            ? !"false".equalsIgnoreCase(actual.trim())
                            : expected.equalsIgnoreCase(actual.trim());
                    findings.add(new InteractionFinding(
                            "USER_PROPERTY_GATES_STARTER",
                            starterClassFqn,
                            key,
                            key + "=" + actual,
                            matches ? "Property matches @ConditionalOnProperty"
                                    : "Property disagrees with havingValue=" + expected));
                }
            }
        } else if (simple.equals("Profile") && !user.activeProfiles().isEmpty()) {
            for (String p : cond.attributes().getOrDefault("value", List.of())) {
                boolean negated = p.startsWith("!");
                String clean = negated ? p.substring(1).trim() : p.trim();
                if (user.activeProfiles().contains(clean)) {
                    findings.add(new InteractionFinding(
                            "PROFILE_GATED_STARTER",
                            starterClassFqn,
                            targetLabel,
                            "profile=" + clean,
                            negated ? "Active profile disables starter via !" + clean
                                    : "Active profile enables starter via " + clean));
                }
            }
        }
    }

    private static String findMatchingFqn(Set<String> fqns, String simple) {
        for (String candidate : fqns) {
            if (simpleName(candidate).equals(simple)) {
                return candidate;
            }
        }
        return simple;
    }

    private static List<String> extractPropertyKeys(ConditionAnnotation cond) {
        String prefix = "";
        var prefixes = cond.attributes().getOrDefault("prefix", List.of());
        if (!prefixes.isEmpty()) {
            prefix = prefixes.getFirst().trim();
            while (prefix.endsWith(".")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
        }
        var names = new ArrayList<String>();
        names.addAll(cond.attributes().getOrDefault("name", List.of()));
        names.addAll(cond.attributes().getOrDefault("value", List.of()));
        var keys = new LinkedHashSet<String>();
        for (String name : names) {
            String cleanName = name == null ? "" : name.trim();
            if (cleanName.isEmpty()) {
                continue;
            }
            String full = prefix.isEmpty() ? cleanName : prefix + "." + cleanName;
            keys.add(normalizePropertyKey(full));
        }
        return List.copyOf(keys);
    }

    public static String normalizePropertyKey(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '_') {
                sb.append('-');
            } else if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    char prev = raw.charAt(i - 1);
                    if (prev != '.' && prev != '-' && prev != '_' && !Character.isUpperCase(prev)) {
                        sb.append('-');
                    }
                }
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static void addUndirectedEdge(Map<String, Set<String>> graph, String a, String b) {
        if (a.equals(b)) {
            graph.computeIfAbsent(a, ignored -> new LinkedHashSet<>());
            return;
        }
        graph.computeIfAbsent(a, ignored -> new LinkedHashSet<>()).add(b);
        graph.computeIfAbsent(b, ignored -> new LinkedHashSet<>()).add(a);
    }

    private static boolean isStandardJdkType(String fqn) {
        return fqn.startsWith("java.") || fqn.startsWith("javax.sql.") || fqn.startsWith("javax.naming.");
    }

    private static List<String> parseSpringFactories(byte[] bytes) throws IOException {
        var props = new Properties();
        props.load(new ByteArrayInputStream(bytes));
        String value = props.getProperty(ENABLE_AUTO_CONFIG_KEY, "");
        if (value.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                validateClassName(trimmed);
                out.add(trimmed);
            }
        }
        return out;
    }

    private static List<String> parseAutoConfigImports(byte[] bytes) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        var out = new ArrayList<String>();
        for (String line : text.split("\\R")) {
            int hash = line.indexOf('#');
            String clean = (hash >= 0 ? line.substring(0, hash) : line).trim();
            if (!clean.isEmpty()) {
                validateClassName(clean);
                out.add(clean);
            }
        }
        return out;
    }

    private static void validateClassName(String fqn) {
        if (!fqn.matches(
                "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(?:\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*")) {
            throw new IllegalArgumentException("Invalid configuration class name: " + fqn);
        }
    }

    private static byte[] readEntryBounded(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readNBytes(limit);
        }
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        String s = dot >= 0 ? fqn.substring(dot + 1) : fqn;
        int dollar = s.lastIndexOf('$');
        return dollar >= 0 ? s.substring(dollar + 1) : s;
    }

    private static String safeMsg(Throwable t) {
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }

    private static final int MAX_META_ANNOTATION_DEPTH = 3;
    private static final String ANN_ALIAS_FOR = "org.springframework.core.annotation.AliasFor";
    private static final Set<String> PRIMITIVE_BOOT_CONDITIONS = Set.of(
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnBean",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnMissingBean",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnSingleCandidate",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnClass",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnMissingClass",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnProperty",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnBooleanProperty",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnBooleanProperties",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnExpression",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnWebApplication",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnNotWebApplication",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnResource",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnJava",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnCloudPlatform",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnWarDeployment",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnNotWarDeployment",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnJndi",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnThreading",
            SPRING_BOOT_CONDITION_PREFIX + "ConditionalOnCheckpointRestore");

    private record RawAnnUse(String annotationFqn, Map<String, List<String>> attributes) {}

    private record AliasTarget(String targetAnnotationFqn, String targetAttribute) {}

    private record ParsedAnnotationType(
            String annotationFqn,
            List<RawAnnUse> metaAnnotations,
            Map<String, List<String>> defaultValues,
            Map<String, List<AliasTarget>> aliases) {}

    private record ParsedBean(
            String declaringClassFqn,
            String methodName,
            String methodDescriptor,
            String returnTypeFqn,
            List<String> beanNames,
            List<String> parameterTypeFqns,
            boolean primary,
            boolean fallback,
            boolean lazy,
            List<ConditionAnnotation> conditions,
            List<RawAnnUse> candidateMetaAnnotations,
            ConditionEvidence.Artifact evidence) {}

    private static final class ParsedClass {
        String classFqn;
        boolean autoConfiguration;
        boolean configuration;
        boolean proxyBeanMethods = true;
        final List<String> importedClasses = new ArrayList<>();
        final List<String> enabledConfigProperties = new ArrayList<>();
        final List<String> nestedClasses = new ArrayList<>();
        final List<ConditionAnnotation> conditions = new ArrayList<>();
        final List<RawAnnUse> candidateMetaAnnotations = new ArrayList<>();
        final List<OrderingAnnotation> orderings = new ArrayList<>();
        final List<ParsedBean> beans = new ArrayList<>();
    }

    private static ParsedClass parseClassBytes(
            byte[] classBytes,
            String expectedFqn,
            ConditionEvidence.Artifact evidence) {
        var parsed = new ParsedClass();
        parsed.classFqn = expectedFqn;
        var reader = new ClassReader(classBytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            private String currentInternalName;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                this.currentInternalName = name;
                parsed.classFqn = name.replace('/', '.');
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                if (currentInternalName != null
                        && outerName != null
                        && outerName.equals(currentInternalName)
                        && innerName != null
                        && (access & Opcodes.ACC_SYNTHETIC) == 0) {
                    parsed.nestedClasses.add(name.replace('/', '.'));
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                String annFqn = Type.getType(descriptor).getClassName();
                return new CollectingAnnotationVisitor(attrs -> {
                    switch (annFqn) {
                        case ANN_AUTO_CONFIGURATION -> {
                            parsed.autoConfiguration = true;
                            var proxy = attrs.get("proxyBeanMethods");
                            if (proxy != null && !proxy.isEmpty()) {
                                parsed.proxyBeanMethods = Boolean.parseBoolean(proxy.getFirst());
                            }
                            var before = attrs.getOrDefault("before", List.of());
                            var beforeNames = attrs.getOrDefault("beforeName", List.of());
                            if (!before.isEmpty() || !beforeNames.isEmpty()) {
                                parsed.orderings.add(new OrderingAnnotation(
                                        ANN_AUTO_CONFIGURE_BEFORE, before, beforeNames, Optional.empty()));
                            }
                            var after = attrs.getOrDefault("after", List.of());
                            var afterNames = attrs.getOrDefault("afterName", List.of());
                            if (!after.isEmpty() || !afterNames.isEmpty()) {
                                parsed.orderings.add(new OrderingAnnotation(
                                        ANN_AUTO_CONFIGURE_AFTER, after, afterNames, Optional.empty()));
                            }
                        }
                        case ANN_CONFIGURATION -> {
                            parsed.configuration = true;
                            var proxy = attrs.get("proxyBeanMethods");
                            if (proxy != null && !proxy.isEmpty()) {
                                parsed.proxyBeanMethods = Boolean.parseBoolean(proxy.getFirst());
                            }
                        }
                        case ANN_IMPORT -> parsed.importedClasses.addAll(attrs.getOrDefault("value", List.of()));
                        case ANN_ENABLE_CONFIG_PROPS ->
                            parsed.enabledConfigProperties.addAll(attrs.getOrDefault("value", List.of()));
                        case ANN_AUTO_CONFIGURE_BEFORE, ANN_AUTO_CONFIGURE_AFTER ->
                            parsed.orderings.add(new OrderingAnnotation(
                                    annFqn,
                                    attrs.getOrDefault("value", List.of()),
                                    attrs.getOrDefault("name", List.of()),
                                    Optional.empty()));
                        case ANN_AUTO_CONFIGURE_ORDER, ANN_ORDER -> {
                            Optional<Integer> orderVal = Optional.empty();
                            var vals = attrs.get("value");
                            if (vals != null && !vals.isEmpty()) {
                                try {
                                    orderVal = Optional.of(Integer.parseInt(vals.getFirst()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                            parsed.orderings.add(new OrderingAnnotation(annFqn, List.of(), List.of(), orderVal));
                        }
                        default -> {
                            if (isPrimitiveConditionAnnotation(annFqn)) {
                                parsed.conditions.add(new ConditionAnnotation(annFqn, attrs, isOpaqueCondition(annFqn)));
                            } else if (isExpandableCandidateAnnotation(annFqn)) {
                                parsed.candidateMetaAnnotations.add(new RawAnnUse(annFqn, attrs));
                            }
                        }
                    }
                });
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if ((access & Opcodes.ACC_SYNTHETIC) != 0 || (access & Opcodes.ACC_BRIDGE) != 0) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    boolean isBean;
                    boolean primary;
                    boolean fallback;
                    boolean lazy;
                    final List<String> beanNames = new ArrayList<>();
                    final List<ConditionAnnotation> methodConditions = new ArrayList<>();
                    final List<RawAnnUse> candidateMethodMeta = new ArrayList<>();

                    @Override
                    public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                        String annFqn = Type.getType(annDesc).getClassName();
                        return new CollectingAnnotationVisitor(attrs -> {
                            switch (annFqn) {
                                case ANN_BEAN -> {
                                    isBean = true;
                                    beanNames.addAll(attrs.getOrDefault("name", List.of()));
                                    beanNames.addAll(attrs.getOrDefault("value", List.of()));
                                }
                                case ANN_PRIMARY -> primary = true;
                                case ANN_FALLBACK -> fallback = true;
                                case ANN_LAZY -> {
                                    var vals = attrs.get("value");
                                    lazy = vals == null || vals.isEmpty() || Boolean.parseBoolean(vals.getFirst());
                                }
                                default -> {
                                    if (isPrimitiveConditionAnnotation(annFqn)) {
                                        methodConditions.add(new ConditionAnnotation(
                                                annFqn, attrs, isOpaqueCondition(annFqn)));
                                    } else if (isExpandableCandidateAnnotation(annFqn)) {
                                        candidateMethodMeta.add(new RawAnnUse(annFqn, attrs));
                                    }
                                }
                            }
                        });
                    }

                    @Override
                    public void visitEnd() {
                        if (!isBean) {
                            return;
                        }
                        if (beanNames.isEmpty()) {
                            beanNames.add(name);
                        }
                        var argTypes = Arrays.stream(Type.getArgumentTypes(descriptor))
                                .map(Type::getClassName)
                                .toList();
                        String returnFqn = Type.getReturnType(descriptor).getClassName();
                        parsed.beans.add(new ParsedBean(
                                parsed.classFqn,
                                name,
                                descriptor,
                                returnFqn,
                                beanNames.stream().distinct().sorted().toList(),
                                argTypes,
                                primary,
                                fallback,
                                lazy,
                                methodConditions,
                                candidateMethodMeta,
                                evidence));
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return parsed;
    }

    private static void expandMetaAnnotations(
            RawAnnUse rootUse,
            Map<String, JarClassRef> indexedClasses,
            Map<String, ZipFile> openZips,
            Limits limits,
            Map<String, Optional<ParsedAnnotationType>> metaCache,
            List<ConditionAnnotation> out) {
        var expandedConcrete = new ArrayList<ConditionAnnotation>();
        var opaqueFallbacks = new ArrayList<ConditionAnnotation>();
        var visited = new LinkedHashSet<String>();
        visited.add(rootUse.annotationFqn());
        expandMetaRecursive(
                rootUse.annotationFqn(),
                rootUse,
                0,
                visited,
                indexedClasses,
                openZips,
                limits,
                metaCache,
                expandedConcrete,
                opaqueFallbacks);
        if (!expandedConcrete.isEmpty()) {
            out.addAll(expandedConcrete);
        } else if (!opaqueFallbacks.isEmpty()) {
            out.addAll(opaqueFallbacks);
        } else if (isConditionAnnotation(rootUse.annotationFqn())) {
            out.add(new ConditionAnnotation(
                    rootUse.annotationFqn(), rootUse.attributes(), isOpaqueCondition(rootUse.annotationFqn())));
        }
    }

    private static void expandMetaRecursive(
            String rootAnnotationFqn,
            RawAnnUse currentUse,
            int depth,
            Set<String> visitedPath,
            Map<String, JarClassRef> indexedClasses,
            Map<String, ZipFile> openZips,
            Limits limits,
            Map<String, Optional<ParsedAnnotationType>> metaCache,
            List<ConditionAnnotation> concreteOut,
            List<ConditionAnnotation> opaqueOut) {
        if (depth >= MAX_META_ANNOTATION_DEPTH) {
            return;
        }
        var parsedTypeOpt = loadAnnotationType(currentUse.annotationFqn(), indexedClasses, openZips, limits, metaCache);
        if (parsedTypeOpt.isEmpty()) {
            return;
        }
        var parsedType = parsedTypeOpt.get();
        for (var metaAnn : parsedType.metaAnnotations()) {
            Map<String, List<String>> resolvedAttrs = applyAliasForwarding(
                    parsedType, currentUse.attributes(), metaAnn.annotationFqn(), metaAnn.attributes());
            if (isPrimitiveConditionAnnotation(metaAnn.annotationFqn())) {
                var withProv = new TreeMap<>(resolvedAttrs);
                withProv.put("_composedFrom", List.of(rootAnnotationFqn));
                if (metaAnn.annotationFqn().equals(ANN_CONDITIONAL)) {
                    opaqueOut.add(new ConditionAnnotation(metaAnn.annotationFqn(), withProv, true));
                } else {
                    concreteOut.add(new ConditionAnnotation(metaAnn.annotationFqn(), withProv, false));
                }
                continue;
            }
            if (!isExpandableCandidateAnnotation(metaAnn.annotationFqn())) {
                continue;
            }
            if (visitedPath.add(metaAnn.annotationFqn())) {
                try {
                    expandMetaRecursive(
                            rootAnnotationFqn,
                            new RawAnnUse(metaAnn.annotationFqn(), resolvedAttrs),
                            depth + 1,
                            visitedPath,
                            indexedClasses,
                            openZips,
                            limits,
                            metaCache,
                            concreteOut,
                            opaqueOut);
                } finally {
                    visitedPath.remove(metaAnn.annotationFqn());
                }
            }
        }
    }

    private static Map<String, List<String>> applyAliasForwarding(
            ParsedAnnotationType ownerType,
            Map<String, List<String>> useSiteAttrs,
            String targetAnnFqn,
            Map<String, List<String>> baseMetaAttrs) {
        var merged = new TreeMap<String, List<String>>();
        if (baseMetaAttrs != null) {
            baseMetaAttrs.forEach((k, v) -> merged.put(k, v == null ? List.of() : List.copyOf(v)));
        }
        for (var entry : ownerType.aliases().entrySet()) {
            String srcAttr = entry.getKey();
            List<String> val = useSiteAttrs.get(srcAttr);
            if (val == null || val.isEmpty()) {
                val = ownerType.defaultValues().get(srcAttr);
            }
            if (val == null || val.isEmpty()) {
                continue;
            }
            for (var target : entry.getValue()) {
                if (target.targetAnnotationFqn().equals(targetAnnFqn)) {
                    merged.put(target.targetAttribute(), List.copyOf(val));
                }
            }
        }
        return merged;
    }

    private static Optional<ParsedAnnotationType> loadAnnotationType(
            String fqn,
            Map<String, JarClassRef> indexedClasses,
            Map<String, ZipFile> openZips,
            Limits limits,
            Map<String, Optional<ParsedAnnotationType>> cache) {
        var cached = cache.get(fqn);
        if (cached != null) {
            return cached;
        }
        var ref = indexedClasses.get(fqn);
        if (ref == null) {
            cache.put(fqn, Optional.empty());
            return Optional.empty();
        }
        try {
            ZipFile zip = openZips.get(ref.binary().path().toString());
            if (zip == null) {
                zip = new ZipFile(ref.binary().path().toFile(), StandardCharsets.UTF_8);
                openZips.put(ref.binary().path().toString(), zip);
            }
            ZipEntry entry = zip.getEntry(ref.entryPath());
            if (entry == null) {
                cache.put(fqn, Optional.empty());
                return Optional.empty();
            }
            byte[] bytes = readEntryBounded(zip, entry, limits.maxEntryBytes() + 1);
            if (bytes.length > limits.maxEntryBytes()) {
                cache.put(fqn, Optional.empty());
                return Optional.empty();
            }
            ParsedAnnotationType parsedType = parseAnnotationTypeBytes(bytes, fqn);
            Optional<ParsedAnnotationType> res = Optional.of(parsedType);
            cache.put(fqn, res);
            return res;
        } catch (IOException | RuntimeException ignored) {
            cache.put(fqn, Optional.empty());
            return Optional.empty();
        }
    }

    private static ParsedAnnotationType parseAnnotationTypeBytes(byte[] bytes, String expectedFqn) {
        var metaAnns = new ArrayList<RawAnnUse>();
        var defaults = new TreeMap<String, List<String>>();
        var aliases = new TreeMap<String, List<AliasTarget>>();
        var holder = new String[] { expectedFqn };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                holder[0] = name.replace('/', '.');
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                String annFqn = Type.getType(descriptor).getClassName();
                if (!isExpandableCandidateAnnotation(annFqn) && !isPrimitiveConditionAnnotation(annFqn)) {
                    return null;
                }
                return new CollectingAnnotationVisitor(attrs -> metaAnns.add(new RawAnnUse(annFqn, attrs)));
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if ((access & Opcodes.ACC_SYNTHETIC) != 0 || (access & Opcodes.ACC_STATIC) != 0) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotationDefault() {
                        return new CollectingAnnotationVisitor(attrs -> {
                            var vals = attrs.get("value");
                            if (vals != null && !vals.isEmpty()) {
                                defaults.put(name, List.copyOf(vals));
                            }
                        });
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                        String annFqn = Type.getType(annDesc).getClassName();
                        if (!ANN_ALIAS_FOR.equals(annFqn)) {
                            return null;
                        }
                        return new CollectingAnnotationVisitor(attrs -> {
                            var annTargets = attrs.get("annotation");
                            String targetAnn = (annTargets == null || annTargets.isEmpty()
                                    || "java.lang.annotation.Annotation".equals(annTargets.getFirst()))
                                            ? holder[0]
                                            : annTargets.getFirst();
                            String targetAttr = name;
                            var attrVals = attrs.get("attribute");
                            var valVals = attrs.get("value");
                            if (attrVals != null && !attrVals.isEmpty() && !attrVals.getFirst().isEmpty()) {
                                targetAttr = attrVals.getFirst();
                            } else if (valVals != null && !valVals.isEmpty() && !valVals.getFirst().isEmpty()) {
                                targetAttr = valVals.getFirst();
                            }
                            aliases.computeIfAbsent(name, ignored -> new ArrayList<>())
                                    .add(new AliasTarget(targetAnn, targetAttr));
                        });
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ParsedAnnotationType(holder[0], metaAnns, defaults, aliases);
    }

    private static boolean isPrimitiveConditionAnnotation(String fqn) {
        return fqn.equals(ANN_PROFILE)
                || fqn.equals(ANN_CONDITIONAL)
                || PRIMITIVE_BOOT_CONDITIONS.contains(fqn);
    }

    private static boolean isExpandableCandidateAnnotation(String fqn) {
        return !fqn.startsWith("java.")
                && !fqn.startsWith("jakarta.")
                && !fqn.startsWith("javax.")
                && !fqn.startsWith("kotlin.")
                && !fqn.startsWith("org.jspecify.")
                && !fqn.startsWith("org.springframework.lang.")
                && !fqn.equals(ANN_AUTO_CONFIGURATION)
                && !fqn.equals(ANN_CONFIGURATION)
                && !fqn.equals(ANN_BEAN)
                && !fqn.equals(ANN_IMPORT)
                && !fqn.equals(ANN_PRIMARY)
                && !fqn.equals(ANN_FALLBACK)
                && !fqn.equals(ANN_LAZY)
                && !fqn.equals(ANN_ORDER)
                && !fqn.equals(ANN_AUTO_CONFIGURE_BEFORE)
                && !fqn.equals(ANN_AUTO_CONFIGURE_AFTER)
                && !fqn.equals(ANN_AUTO_CONFIGURE_ORDER)
                && !fqn.equals(ANN_ENABLE_CONFIG_PROPS)
                && !fqn.equals(ANN_ALIAS_FOR);
    }

    private static boolean isConditionAnnotation(String fqn) {
        return fqn.equals(ANN_PROFILE)
                || fqn.equals(ANN_CONDITIONAL)
                || fqn.startsWith(SPRING_BOOT_CONDITION_PREFIX)
                || fqn.contains(".ConditionalOn")
                || fqn.endsWith(".Conditional");
    }

    private static boolean isOpaqueCondition(String fqn) {
        return !fqn.equals(ANN_PROFILE) && !fqn.startsWith(SPRING_BOOT_CONDITION_PREFIX);
    }

    private static final class CollectingAnnotationVisitor extends AnnotationVisitor {
        private final java.util.function.Consumer<Map<String, List<String>>> onEnd;
        private final Map<String, List<String>> attrs = new TreeMap<>();

        CollectingAnnotationVisitor(java.util.function.Consumer<Map<String, List<String>>> onEnd) {
            super(Opcodes.ASM9);
            this.onEnd = onEnd;
        }

        private void add(String key, String value) {
            if (value == null) {
                return;
            }
            String safeKey = (key == null || key.isEmpty()) ? "value" : key;
            attrs.computeIfAbsent(safeKey, ignored -> new ArrayList<>()).add(value);
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof Type t) {
                add(name, t.getClassName());
            } else if (value != null) {
                add(name, String.valueOf(value));
            }
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            add(name, Type.getType(descriptor).getClassName() + "." + value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            String safeKey = (name == null || name.isEmpty()) ? "value" : name;
            add(safeKey, "@" + Type.getType(descriptor).getClassName());
            return new CollectingAnnotationVisitor(nested -> nested.forEach((k, vals) -> {
                for (String v : vals) {
                    add(safeKey + "." + k, v);
                }
            }));
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            String safeKey = (name == null || name.isEmpty()) ? "value" : name;
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String ignored, Object value) {
                    CollectingAnnotationVisitor.this.visit(safeKey, value);
                }

                @Override
                public void visitEnum(String ignored, String descriptor, String value) {
                    CollectingAnnotationVisitor.this.visitEnum(safeKey, descriptor, value);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
                    return CollectingAnnotationVisitor.this.visitAnnotation(safeKey, descriptor);
                }
            };
        }

        @Override
        public void visitEnd() {
            onEnd.accept(attrs);
        }
    }

    private BytecodeAutoConfigScanner() {}
}
