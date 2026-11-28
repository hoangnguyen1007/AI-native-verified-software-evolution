package com.evolution.analysis.policy;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.Classification;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.analysis.query.ArchitectureQueryService.AnnotationMetadata;
import com.evolution.analysis.query.ArchitectureQueryService.ClassMetadata;
import com.evolution.analysis.query.ArchitectureQueryService.MemberMetadata;
import com.evolution.analysis.spring.condition.ConfigDataIngestion;
import com.evolution.analysis.spring.condition.FiniteDomain;
import com.evolution.analysis.spring.truth.ConfigurationReasoner;
import com.evolution.analysis.spring.truth.SatConfigurationReasoner;
import com.evolution.analysis.spring.truth.SymbolicConfiguration;
import com.evolution.analysis.spring.truth.SymbolicTruthRegions;
import com.evolution.analysis.spring.universal.BytecodeAutoConfigScanner;
import java.util.*;

/**
 * WP2.2 MeCheck/RSL-style cross-artifact metadata consistency engine backed by exact classpath lookup
 * ({@link ClasspathTypeLookup} / {@code AsmBytecodeIndex}), SAT {@code SymbolicTruthRegions},
 * {@link ConfigDataIngestion} layers, and {@link ArchitectureQueryService}'s memoized lazy query cache.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@code I1}: Strictly passive read-only inspection of captured graph evidence and bytecode stubs.</li>
 *   <li>{@code I2}: Bounded, terminating, 100% deterministic evaluation and SHA-256 content-addressed output.</li>
 *   <li>{@code I3}/{@code I4}: Missing offline JARs emit {@link Classification#UNKNOWN} +
 *       {@code CapabilityGapRecord("LIBRARY_JAR_ABSENT")} instead of false positives; zero package-prefix regexes.</li>
 *   <li>{@code I5}: Strictly generic Spring Boot 2/3/4 and Spring XML semantics.</li>
 * </ul>
 */
public final class MetadataConsistencyRulePack {
    public static final String PACK_ID = "metadata-consistency-pack";
    public static final VersionedIdentifier VERSION =
            new VersionedIdentifier("policy.metadata-consistency", "wp2.2-v1");
    public static final VersionedIdentifier GAP_CATALOG =
            new VersionedIdentifier("evidence.metadata-consistency-gaps", "wp2.2-v1");

    // Built-in rule identifiers
    public static final String RULE_XML_BEAN_CLASS_AND_PROPS = "spring-xml-bean-class-and-props";
    public static final String RULE_BEAN_LIFECYCLE_METHOD = "spring-bean-lifecycle-method-exists";
    public static final String RULE_BOOT_PROPERTY_KEY = "boot-property-key-exists";
    public static final String RULE_BOOT_CONFIG_PROPERTIES = "boot-config-properties-binding";
    public static final String RULE_GETBEAN_CONDITIONAL_SAFETY = "spring-getbean-conditional-safety";
    public static final String RULE_FEIGN_CONTROLLER_PARITY = "feign-controller-contract-parity";

    public static final Set<String> ALL_RULE_IDS = Collections.unmodifiableSet(new TreeSet<>(Set.of(
            RULE_XML_BEAN_CLASS_AND_PROPS,
            RULE_BEAN_LIFECYCLE_METHOD,
            RULE_BOOT_PROPERTY_KEY,
            RULE_BOOT_CONFIG_PROPERTIES,
            RULE_GETBEAN_CONDITIONAL_SAFETY,
            RULE_FEIGN_CONTROLLER_PARITY
    )));

    // Evidence family & edge constants (aligned with WP2.1 without compile-time coupling)
    public static final String FAMILY_XML_BEAN = "spring.xml-bean";
    public static final String FAMILY_BEAN_LOOKUP = "spring.bean-lookup";
    public static final String FAMILY_CONFIG_DATA = "spring.config-data";
    public static final String FAMILY_SOURCE_CONFIG = "source.configuration";
    public static final String FAMILY_SYMBOLIC_REGION = "spring.symbolic-region";
    public static final String FAMILY_EXPLICIT_REGION = "spring.explicit-region";
    public static final String FAMILY_ENDPOINT_DECLARATION = "spring.endpoint-declaration";
    public static final String EDGE_RESOLVES_BEAN = "RESOLVES_BEAN";
    public static final String EDGE_CALLS_SERVICE = "CALLS_SERVICE";

    // Typed capability gap reason codes
    public static final String GAP_LIBRARY_JAR_ABSENT = "LIBRARY_JAR_ABSENT";
    public static final String GAP_DYNAMIC_BEAN_LOOKUP = "DYNAMIC_BEAN_LOOKUP";
    public static final String GAP_BEAN_TRUTH_UNKNOWN = "BEAN_TRUTH_UNKNOWN";
    public static final String GAP_CONFIG_EVIDENCE_INCOMPLETE = "CONFIG_EVIDENCE_INCOMPLETE";
    public static final String GAP_IMPORT_RESOURCE_UNRESOLVED = "IMPORT_RESOURCE_UNRESOLVED";
    public static final String GAP_FEIGN_TARGET_UNRESOLVED = "FEIGN_TARGET_UNRESOLVED";
    public static final String GAP_EVALUATION_LIMIT = "METADATA_EVALUATION_LIMIT";

    private static final Set<String> INCOMPLETE_CLASSPATH_GAP_REASONS = Set.of(
            GAP_LIBRARY_JAR_ABSENT,
            "DEPENDENCY_UNAVAILABLE",
            "CLASSPATH_INCOMPLETE",
            "OFFLINE_DEPENDENCY_MISSING",
            "MISSING_DEPENDENCY_JAR",
            "ARTIFACT_RESOLUTION_UNAVAILABLE"
    );

    private static final Set<String> HTTP_MAPPING_SHORTCUTS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

    private static final Map<String, String> SHORTCUT_TO_VERB = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );

    private static final Set<String> MAP_LIKE_TYPES = Set.of(
            "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "java.util.ConcurrentHashMap", "java.util.concurrent.ConcurrentHashMap",
            "java.util.Properties", "Map", "HashMap", "LinkedHashMap", "TreeMap", "Properties"
    );

    private static final Set<String> COLLECTION_LIKE_TYPES = Set.of(
            "java.util.List", "java.util.ArrayList", "java.util.Set", "java.util.HashSet",
            "java.util.LinkedHashSet", "java.util.Collection", "List", "Set", "Collection"
    );

    private static final Set<String> SCALAR_OR_JDK_LEAF_TYPES = Set.of(
            "boolean", "byte", "short", "int", "long", "float", "double", "char",
            "String", "java.lang.String", "Boolean", "java.lang.Boolean",
            "Integer", "java.lang.Integer", "Long", "java.lang.Long",
            "Double", "java.lang.Double", "Float", "java.lang.Float",
            "Short", "java.lang.Short", "Byte", "java.lang.Byte",
            "Character", "java.lang.Character", "Object", "java.lang.Object",
            "java.time.Duration", "Duration", "java.time.Period", "Period",
            "java.net.URI", "URI", "java.net.URL", "URL", "java.io.File", "File",
            "java.nio.file.Path", "Path", "java.nio.charset.Charset", "Charset",
            "java.math.BigDecimal", "BigDecimal", "java.math.BigInteger", "BigInteger",
            "java.util.Locale", "Locale", "java.util.UUID", "UUID", "java.lang.Class", "Class"
    );

    private static final int MAX_SUPERTYPE_DEPTH = 16;
    private static final int MAX_NESTED_CONFIG_DEPTH = 6;

    // ─── Exact Classpath Lookup SPI (ZERO package-prefix regexes!) ───

    public enum LookupStatus {
        FOUND,
        NOT_FOUND,
        LIBRARY_JAR_ABSENT
    }

    public record ConstructorSignature(int parameterCount, List<String> parameterTypes, boolean accessible) {
        public ConstructorSignature {
            if (parameterCount < 0) throw new IllegalArgumentException("Negative parameterCount");
            parameterTypes = List.copyOf(parameterTypes == null ? List.of() : parameterTypes);
        }

        public static ConstructorSignature of(int parameterCount, String... parameterTypes) {
            return new ConstructorSignature(parameterCount, List.of(parameterTypes), true);
        }
    }

    public record MethodSignature(
            String name,
            int parameterCount,
            List<String> parameterTypes,
            String returnType,
            boolean staticMethod,
            boolean accessible
    ) {
        public MethodSignature {
            name = Objects.requireNonNull(name, "name").strip();
            if (parameterCount < 0) throw new IllegalArgumentException("Negative parameterCount");
            parameterTypes = List.copyOf(parameterTypes == null ? List.of() : parameterTypes);
            returnType = returnType == null ? "void" : returnType.strip();
        }

        public static MethodSignature noArg(String name, String returnType) {
            return new MethodSignature(name, 0, List.of(), returnType, false, true);
        }

        public static MethodSignature setter(String name, String paramType) {
            return new MethodSignature(name, 1, List.of(paramType), "void", false, true);
        }
    }

    public record FieldSignature(String name, String type, boolean staticField, boolean finalField) {
        public FieldSignature {
            name = Objects.requireNonNull(name, "name").strip();
            type = type == null ? "java.lang.Object" : type.strip();
        }
    }

    public record AnnotationInfo(String name, Map<String, String> attributes, String rawSpelling) {
        public AnnotationInfo {
            name = name == null ? "" : name.strip();
            attributes = Collections.unmodifiableMap(new TreeMap<>(attributes == null ? Map.of() : attributes));
            rawSpelling = rawSpelling == null ? "" : rawSpelling;
        }
    }

    public record TypeDescriptor(
            String fqcn,
            boolean abstractType,
            boolean interfaceType,
            boolean recordType,
            TrustTier trustTier,
            Set<String> superTypes,
            List<ConstructorSignature> constructors,
            List<MethodSignature> methods,
            List<FieldSignature> fields,
            List<AnnotationInfo> annotations
    ) {
        public TypeDescriptor {
            fqcn = Objects.requireNonNull(fqcn, "fqcn").strip();
            trustTier = trustTier == null ? TrustTier.COMPILER : trustTier;
            superTypes = Collections.unmodifiableSet(new TreeSet<>(superTypes == null ? Set.of() : superTypes));
            constructors = List.copyOf(constructors == null ? List.of() : constructors);
            methods = List.copyOf(methods == null ? List.of() : methods);
            fields = List.copyOf(fields == null ? List.of() : fields);
            annotations = List.copyOf(annotations == null ? List.of() : annotations);
        }

        public static TypeDescriptor simpleClass(
                String fqcn,
                List<ConstructorSignature> constructors,
                List<MethodSignature> methods,
                List<FieldSignature> fields
        ) {
            return new TypeDescriptor(fqcn, false, false, false, TrustTier.COMPILER,
                    Set.of(), constructors, methods, fields, List.of());
        }
    }

    public record TypeLookupOutcome(
            LookupStatus status,
            Optional<TypeDescriptor> descriptor,
            Optional<String> missingArtifactHint
    ) {
        public TypeLookupOutcome {
            Objects.requireNonNull(status, "status");
            descriptor = descriptor == null ? Optional.empty() : descriptor;
            missingArtifactHint = missingArtifactHint == null ? Optional.empty() : missingArtifactHint;
            if (status == LookupStatus.FOUND && descriptor.isEmpty()) {
                throw new IllegalArgumentException("FOUND lookup requires a TypeDescriptor");
            }
        }

        public static TypeLookupOutcome found(TypeDescriptor descriptor) {
            return new TypeLookupOutcome(LookupStatus.FOUND, Optional.of(Objects.requireNonNull(descriptor)), Optional.empty());
        }

        public static TypeLookupOutcome notFound() {
            return new TypeLookupOutcome(LookupStatus.NOT_FOUND, Optional.empty(), Optional.empty());
        }

        public static TypeLookupOutcome libraryJarAbsent(String hint) {
            return new TypeLookupOutcome(LookupStatus.LIBRARY_JAR_ABSENT, Optional.empty(),
                    Optional.ofNullable(hint).filter(s -> !s.isBlank()));
        }
    }

    /**
     * Exact classpath type lookup functional interface backed by {@code AsmBytecodeIndex} or graph bytecode/source
     * declarations. Implementations must perform exact lookups without package-prefix regular expressions.
     */
    @FunctionalInterface
    public interface ClasspathTypeLookup {
        TypeLookupOutcome lookup(String fqcn);

        static ClasspathTypeLookup ofExactTypes(Map<String, TypeDescriptor> exactTypes, boolean classpathComplete) {
            var copy = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(exactTypes, "exactTypes")));
            return fqcn -> {
                if (fqcn == null || fqcn.isBlank()) {
                    return TypeLookupOutcome.notFound();
                }
                TypeDescriptor hit = copy.get(fqcn.strip());
                if (hit != null) {
                    return TypeLookupOutcome.found(hit);
                }
                return classpathComplete
                        ? TypeLookupOutcome.notFound()
                        : TypeLookupOutcome.libraryJarAbsent("Offline/incomplete classpath cannot prove absence of " + fqcn.strip());
            };
        }

        static ClasspathTypeLookup offlineIncomplete(Map<String, TypeDescriptor> availableTypes) {
            return ofExactTypes(availableTypes, false);
        }

        static ClasspathTypeLookup emptyComplete() {
            return ofExactTypes(Map.of(), true);
        }

        static ClasspathTypeLookup emptyOffline() {
            return ofExactTypes(Map.of(), false);
        }
    }

    // ─── Evaluation Options & Immutable Deterministic Result Contract ───

    public record EvaluationOptions(
            boolean classpathComplete,
            Optional<ConfigDataIngestion.Result> configData,
            int maxFindings,
            int maxDomainItems
    ) {
        public EvaluationOptions {
            configData = configData == null ? Optional.empty() : configData;
            if (maxFindings < 1 || maxFindings > 10_000) {
                throw new IllegalArgumentException("maxFindings must be in 1..10000");
            }
            if (maxDomainItems < 1 || maxDomainItems > 200_000) {
                throw new IllegalArgumentException("maxDomainItems must be in 1..200000");
            }
        }

        public static EvaluationOptions defaults() {
            return new EvaluationOptions(true, Optional.empty(), 500, 50_000);
        }

        public static EvaluationOptions offlineIncomplete() {
            return new EvaluationOptions(false, Optional.empty(), 500, 50_000);
        }

        public EvaluationOptions withConfigData(ConfigDataIngestion.Result data) {
            return new EvaluationOptions(classpathComplete, Optional.ofNullable(data), maxFindings, maxDomainItems);
        }

        public EvaluationOptions withClasspathComplete(boolean complete) {
            return new EvaluationOptions(complete, configData, maxFindings, maxDomainItems);
        }
    }

    public record Finding(
            String ruleId,
            String subCode,
            ArchitecturePolicy.Severity severity,
            Classification classification,
            TrustTier trustTier,
            String subject,
            String message,
            Optional<String> sourceFile,
            int line,
            Optional<String> truthRegionScope,
            Map<String, String> counterexampleWitness,
            Optional<String> gapReason
    ) implements Comparable<Finding> {
        public Finding {
            ruleId = Objects.requireNonNull(ruleId, "ruleId").strip();
            subCode = Objects.requireNonNull(subCode, "subCode").strip();
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(classification, "classification");
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
            subject = Objects.requireNonNull(subject, "subject").strip();
            message = Objects.requireNonNull(message, "message").strip();
            sourceFile = sourceFile == null ? Optional.empty() : sourceFile.filter(s -> !s.isBlank());
            truthRegionScope = truthRegionScope == null ? Optional.empty() : truthRegionScope.filter(s -> !s.isBlank());
            counterexampleWitness = Collections.unmodifiableMap(
                    new TreeMap<>(counterexampleWitness == null ? Map.of() : counterexampleWitness));
            gapReason = gapReason == null ? Optional.empty() : gapReason.filter(s -> !s.isBlank());
        }

        @Override
        public int compareTo(Finding other) {
            return Comparator.comparing(Finding::ruleId)
                    .thenComparing(Finding::subject)
                    .thenComparing(Finding::subCode)
                    .thenComparing(f -> f.sourceFile().orElse(""))
                    .thenComparingInt(Finding::line)
                    .thenComparing(Finding::classification)
                    .thenComparing(Finding::message)
                    .compare(this, other);
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION, toView())));
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("classification", classification.name());
            if (!counterexampleWitness.isEmpty()) m.put("counterexampleWitness", counterexampleWitness);
            gapReason.ifPresent(g -> m.put("gapReason", g));
            m.put("line", line);
            m.put("message", message);
            m.put("ruleId", ruleId);
            m.put("severity", severity.name());
            sourceFile.ifPresent(f -> m.put("sourceFile", f));
            m.put("subCode", subCode);
            m.put("subject", subject);
            truthRegionScope.ifPresent(s -> m.put("truthRegionScope", s));
            m.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(m);
        }
    }

    public record RuleOutcome(
            String ruleId,
            AssessmentStatus status,
            Classification classification,
            int checkedCount,
            List<Finding> findings,
            List<CapabilityGapRecord> gaps
    ) {
        public RuleOutcome {
            ruleId = Objects.requireNonNull(ruleId, "ruleId").strip();
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(classification, "classification");
            findings = Objects.requireNonNull(findings, "findings").stream().distinct().sorted().toList();
            gaps = Objects.requireNonNull(gaps, "gaps").stream().distinct().sorted().toList();
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
                    VERSION, ruleId, status, classification, checkedCount,
                    findings.stream().map(Finding::toView).toList(), gaps)));
        }
    }

    public record EvaluationReport(
            ContentDigest policyIdentity,
            ContentDigest graphDigest,
            String context,
            AssessmentStatus status,
            Classification classification,
            List<RuleOutcome> rules,
            List<Finding> findings,
            List<CapabilityGapRecord> gaps
    ) {
        public EvaluationReport {
            Objects.requireNonNull(policyIdentity, "policyIdentity");
            Objects.requireNonNull(graphDigest, "graphDigest");
            context = context == null ? "" : context;
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(classification, "classification");
            rules = Objects.requireNonNull(rules, "rules").stream()
                    .sorted(Comparator.comparing(RuleOutcome::ruleId))
                    .toList();
            findings = Objects.requireNonNull(findings, "findings").stream().distinct().sorted().toList();
            gaps = Objects.requireNonNull(gaps, "gaps").stream().distinct().sorted().toList();
        }

        public List<Finding> findingsForRule(String ruleId) {
            return findings.stream().filter(f -> f.ruleId().equals(ruleId)).toList();
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION, toView())));
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("classification", classification.name());
            m.put("context", context);
            m.put("findingCount", findings.size());
            m.put("findings", findings.stream().map(Finding::toView).toList());
            m.put("gapCount", gaps.size());
            m.put("gapReasons", gaps.stream().map(CapabilityGapRecord::reasonCode).distinct().sorted().toList());
            m.put("graphDigest", graphDigest.value());
            m.put("policyIdentity", policyIdentity.value());
            m.put("ruleCount", rules.size());
            m.put("status", status.name());
            return Collections.unmodifiableMap(m);
        }
    }

    // ─── Internal Structured Evidence Models ───

    private record XmlPropertyDef(String name, String ref, String value) {}
    private record XmlConstructorArgDef(int index, String name, String type, String ref, String value) {}
    private record XmlBeanDef(
            String beanId,
            String className,
            String scope,
            String initMethod,
            String destroyMethod,
            boolean abstractBean,
            String parent,
            String factoryBean,
            String factoryMethod,
            List<XmlPropertyDef> properties,
            List<XmlConstructorArgDef> constructorArgs,
            List<String> imports,
            String sourceFile,
            int line
    ) {}

    private record BeanLookupDef(
            String subjectId,
            String beanName,
            String beanType,
            boolean dynamic,
            String sourceFile,
            int line
    ) {}

    private record BeanTruthInfo(
            String beanId,
            String beanName,
            String beanType,
            Classification classification,
            Map<String, String> counterexampleWitness,
            Optional<String> truthRegionScope,
            TrustTier trustTier
    ) {}

    private record ConfigCatalog(
            Set<String> baseKeys,
            Map<String, Set<String>> profileKeys,
            Map<String, String> rawKeyToFirstValue,
            Map<String, Set<String>> rawKeysByProfile,
            Set<String> allProfiles,
            Set<String> allNormalizedKeys,
            boolean hasAnyConfigDocument
    ) {}

    private record FeignEndpointDef(
            String subjectId,
            String targetService,
            String httpMethod,
            String rawPath,
            String normalizedPath,
            boolean dynamicOrSpel,
            String sourceFile,
            int line
    ) {}

    private record ControllerEndpointDef(
            String subjectId,
            String serviceName,
            Set<String> httpMethods,
            boolean implicitHead,
            String rawPath,
            String normalizedPath,
            String sourceFile,
            int line
    ) {}

    private static final class EvalContext {
        private final ArchitectureQueryService service;
        private final String context;
        private final EvidenceContext evidenceContext;
        private final ClasspathTypeLookup delegateLookup;
        private final EvaluationOptions options;
        private final boolean effectiveClasspathComplete;
        private final String configDataKey;
        // Scoped to one evaluation run so distinct ClasspathTypeLookup delegates never collide in shared service cache
        private final Map<String, TypeLookupOutcome> localTypeCache = new HashMap<>();

        EvalContext(
                ArchitectureQueryService service,
                String context,
                EvidenceContext evidenceContext,
                ClasspathTypeLookup delegateLookup,
                EvaluationOptions options,
                boolean effectiveClasspathComplete
        ) {
            this.service = service;
            this.context = context;
            this.evidenceContext = evidenceContext;
            this.delegateLookup = delegateLookup;
            this.options = options;
            this.effectiveClasspathComplete = effectiveClasspathComplete;
            this.configDataKey = options.configData().map(r -> r.identity().value()).orElse("");
        }

        ArchitectureQueryService service() { return service; }
        String context() { return context; }
        EvaluationOptions options() { return options; }
        boolean effectiveClasspathComplete() { return effectiveClasspathComplete; }
        String configDataKey() { return configDataKey; }

        TypeLookupOutcome resolveExactType(String fqcn) {
            if (fqcn == null || fqcn.isBlank()) {
                return TypeLookupOutcome.notFound();
            }
            String clean = stripGenericsAndArrays(fqcn.strip());
            return localTypeCache.computeIfAbsent(clean, this::resolveExactTypeUncached);
        }

        private TypeLookupOutcome resolveExactTypeUncached(String cleanFqcn) {
            // 1. Check graph-indexed source/bytecode class metadata first (lazy per-class via MeCheck Fig. 6)
            Optional<ClassMetadata> graphClass = service.classMetadata(context, cleanFqcn);
            if (graphClass.isEmpty() && !context.isEmpty()) {
                graphClass = service.classMetadata("", cleanFqcn);
            }
            if (graphClass.isPresent()) {
                return TypeLookupOutcome.found(toTypeDescriptor(graphClass.get()));
            }
            // 2. Delegate to pluggable ClasspathTypeLookup (backed by AsmBytecodeIndex)
            if (delegateLookup != null) {
                TypeLookupOutcome outcome = delegateLookup.lookup(cleanFqcn);
                if (outcome.status() == LookupStatus.FOUND) {
                    return outcome;
                }
                if (outcome.status() == LookupStatus.LIBRARY_JAR_ABSENT) {
                    return outcome;
                }
            }
            // 3. If classpath is incomplete (offline / missing JAR gaps), emit LIBRARY_JAR_ABSENT instead of FP!
            if (!effectiveClasspathComplete) {
                return TypeLookupOutcome.libraryJarAbsent(
                        "Classpath incomplete (--offline or unresolved dependency JARs) for exact type " + cleanFqcn);
            }
            return TypeLookupOutcome.notFound();
        }

        CapabilityGapRecord createGap(String reasonCode, String subjectKey, String limitation) {
            var obs = ProviderObservationReference.create(
                    VERSION, "metadata.consistency", service.graphIdentity(),
                    ContentDigest.sha256Utf8(CanonicalJson.write(List.of(reasonCode, subjectKey))));
            var subject = EvidenceSubject.observation(obs.identity());
            var req = new EvidenceRequirement(
                    GAP_LIBRARY_JAR_ABSENT.equals(reasonCode)
                            ? EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT
                            : EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                    "metadata.consistency." + reasonCode.toLowerCase(Locale.ROOT),
                    List.of(subject),
                    EvidenceRequirement.AuthorizationClass.PASSIVE,
                    List.of(limitation));
            return CapabilityGapRecord.create(
                    GAP_CATALOG,
                    evidenceContext,
                    VERSION,
                    "policy.metadata-consistency",
                    reasonCode,
                    subject,
                    List.of(),
                    List.of(obs),
                    List.of(req),
                    List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.POLICY, "policy.metadata-consistency")),
                    List.of(),
                    List.of(),
                    List.of(limitation));
        }
    }

    // ─── Public Evaluation Entry Points ───

    /** Evaluates all 6 built-in rules of {@code metadata-consistency-pack}. */
    public EvaluationReport evaluatePack(
            ArchitectureQueryService service,
            String context,
            ClasspathTypeLookup classpathLookup,
            EvaluationOptions options
    ) {
        var policy = new PolicyCompiler.MetadataPolicy(
                "builtin." + PACK_ID,
                "1",
                List.of(new PolicyCompiler.MetadataPackActivation(PACK_ID, Set.of(), Set.of())),
                List.of());
        return evaluate(policy, service, context, classpathLookup, options);
    }

    /** Evaluates an arbitrary compiled {@link PolicyCompiler.MetadataPolicy} (built-in packs + custom RSL rules). */
    public EvaluationReport evaluate(
            PolicyCompiler.MetadataPolicy policy,
            ArchitectureQueryService service,
            String context,
            ClasspathTypeLookup classpathLookup,
            EvaluationOptions options
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(service, "service");
        EvaluationOptions opts = options == null ? EvaluationOptions.defaults() : options;
        String effectiveContext = context == null ? "" : context;
        EvidenceContext evCtx = resolveEvidenceContext(service.graph(), effectiveContext);
        boolean graphHasMissingJarGap = hasIncompleteClasspathGap(service, effectiveContext);
        boolean effectiveComplete = opts.classpathComplete() && !graphHasMissingJarGap;
        var ctx = new EvalContext(service, effectiveContext, evCtx, classpathLookup, opts, effectiveComplete);

        // Collect activated built-in rule IDs in canonical sorted order
        var activeBuiltInRules = new TreeSet<String>();
        for (var pack : policy.packs()) {
            if (PACK_ID.equals(pack.packId())) {
                Set<String> base = pack.includeRules().isEmpty() ? ALL_RULE_IDS : pack.includeRules();
                for (String r : base) {
                    if (!pack.excludeRules().contains(r)) {
                        activeBuiltInRules.add(r);
                    }
                }
            }
        }

        var outcomes = new ArrayList<RuleOutcome>();
        var allFindings = new ArrayList<Finding>();
        var allGaps = new TreeSet<CapabilityGapRecord>();
        int remainingBudget = opts.maxFindings();

        for (String ruleId : activeBuiltInRules) {
            RuleOutcome outcome = switch (ruleId) {
                case RULE_XML_BEAN_CLASS_AND_PROPS -> evaluateRule1XmlBeanClassAndProps(ctx, remainingBudget);
                case RULE_BEAN_LIFECYCLE_METHOD -> evaluateRule2LifecycleMethodExists(ctx, remainingBudget);
                case RULE_BOOT_PROPERTY_KEY -> evaluateRule3BootPropertyKeyExists(ctx, remainingBudget);
                case RULE_BOOT_CONFIG_PROPERTIES -> evaluateRule4ConfigPropertiesBinding(ctx, remainingBudget);
                case RULE_GETBEAN_CONDITIONAL_SAFETY -> evaluateRule5GetBeanConditionalSafety(ctx, remainingBudget);
                case RULE_FEIGN_CONTROLLER_PARITY -> evaluateRule6FeignControllerParity(ctx, remainingBudget);
                default -> new RuleOutcome(ruleId, AssessmentStatus.COMPLETE, Classification.NEVER, 0, List.of(), List.of());
            };
            outcomes.add(outcome);
            allFindings.addAll(outcome.findings());
            allGaps.addAll(outcome.gaps());
            remainingBudget = Math.max(0, remainingBudget - outcome.findings().size());
        }

        // Evaluate custom bounded declarative RSL rules
        for (var customRule : policy.rules()) {
            RuleOutcome outcome = evaluateCustomRslRule(ctx, customRule, remainingBudget);
            outcomes.add(outcome);
            allFindings.addAll(outcome.findings());
            allGaps.addAll(outcome.gaps());
            remainingBudget = Math.max(0, remainingBudget - outcome.findings().size());
        }

        Classification overall = aggregateClassification(outcomes, allGaps);
        AssessmentStatus status = allGaps.isEmpty() ? AssessmentStatus.COMPLETE : AssessmentStatus.PARTIAL;
        return new EvaluationReport(
                policy.identity(),
                service.graphIdentity(),
                effectiveContext,
                status,
                overall,
                outcomes,
                allFindings,
                List.copyOf(allGaps));
    }

    // ─── Rule 1: spring-xml-bean-class-and-props ───

    private RuleOutcome evaluateRule1XmlBeanClassAndProps(EvalContext ctx, int budget) {
        var xmlBeans = extractXmlBeans(ctx);
        var findings = new ArrayList<Finding>();
        var gaps = new TreeSet<CapabilityGapRecord>();
        int checked = 0;

        // 1A-1C: <bean class>, <constructor-arg>, <property> setters
        for (XmlBeanDef bean : xmlBeans) {
            if (findings.size() >= budget) {
                gaps.add(ctx.createGap(GAP_EVALUATION_LIMIT, RULE_XML_BEAN_CLASS_AND_PROPS,
                        "Reached maxFindings budget while evaluating " + RULE_XML_BEAN_CLASS_AND_PROPS));
                break;
            }
            if (bean.className().isBlank()) {
                continue; // Inherited parent or factory-bean definition without explicit class
            }
            checked++;
            String subj = bean.beanId().isBlank() ? bean.className() : bean.beanId() + ":" + bean.className();
            TypeLookupOutcome lookup = ctx.resolveExactType(bean.className());
            if (lookup.status() == LookupStatus.LIBRARY_JAR_ABSENT) {
                String hint = lookup.missingArtifactHint().orElse("Absent classpath JAR for " + bean.className());
                gaps.add(ctx.createGap(GAP_LIBRARY_JAR_ABSENT, bean.className(), hint));
                findings.add(new Finding(
                        RULE_XML_BEAN_CLASS_AND_PROPS, "LIBRARY_JAR_ABSENT",
                        ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.COMPILER,
                        subj, "Cannot verify XML bean class '" + bean.className() + "' because classpath JAR is absent: " + hint,
                        Optional.ofNullable(bean.sourceFile()), bean.line(),
                        Optional.empty(), Map.of(), Optional.of(GAP_LIBRARY_JAR_ABSENT)));
                continue;
            }
            if (lookup.status() == LookupStatus.NOT_FOUND) {
                findings.add(new Finding(
                        RULE_XML_BEAN_CLASS_AND_PROPS, "XML_BEAN_CLASS_NOT_FOUND",
                        ArchitecturePolicy.Severity.ERROR, Classification.MUST, TrustTier.COMPILER,
                        subj, "XML bean '" + bean.beanId() + "' references non-existent class '" + bean.className() + "'",
                        Optional.ofNullable(bean.sourceFile()), bean.line(),
                        Optional.empty(), Map.of(), Optional.empty()));
                continue;
            }

            TypeDescriptor td = lookup.descriptor().orElseThrow();
            // Instantiability check
            if ((td.abstractType() || td.interfaceType()) && !bean.abstractBean() && bean.factoryMethod().isBlank()) {
                findings.add(new Finding(
                        RULE_XML_BEAN_CLASS_AND_PROPS, "XML_BEAN_CLASS_NOT_INSTANTIABLE",
                        ArchitecturePolicy.Severity.ERROR, Classification.MUST, td.trustTier(),
                        subj, "XML bean '" + bean.beanId() + "' references abstract/interface class '" + bean.className() + "' without abstract=\"true\" or factory-method",
                        Optional.ofNullable(bean.sourceFile()), bean.line(),
                        Optional.empty(), Map.of(), Optional.empty()));
            }

            // Constructor argument matching
            if (!bean.constructorArgs().isEmpty() && bean.factoryMethod().isBlank()) {
                checked++;
                boolean matchedCtor = matchesConstructorArgs(td, bean.constructorArgs());
                if (!matchedCtor) {
                    findings.add(new Finding(
                            RULE_XML_BEAN_CLASS_AND_PROPS, "XML_CONSTRUCTOR_ARG_MISMATCH",
                            ArchitecturePolicy.Severity.ERROR, Classification.MUST, td.trustTier(),
                            subj + "#ctor(" + bean.constructorArgs().size() + ")",
                            "XML bean '" + bean.beanId() + "' declares " + bean.constructorArgs().size()
                                    + " <constructor-arg> entries but '" + bean.className() + "' has no matching constructor",
                            Optional.ofNullable(bean.sourceFile()), bean.line(),
                            Optional.empty(), Map.of(), Optional.empty()));
                }
            }

            // Property setter matching (exact hierarchy walk with Lombok @Setter/@Data support)
            for (XmlPropertyDef prop : bean.properties()) {
                if (prop.name().isBlank()) continue;
                checked++;
                String rootProp = prop.name().contains(".")
                        ? prop.name().substring(0, prop.name().indexOf('.'))
                        : prop.name();
                String expectedSetter = toSetterName(rootProp);
                HierarchySearchResult setterSearch = findSetterInHierarchy(ctx, td, rootProp, expectedSetter);
                if (setterSearch == HierarchySearchResult.LIBRARY_JAR_ABSENT) {
                    gaps.add(ctx.createGap(GAP_LIBRARY_JAR_ABSENT, bean.className() + "#" + expectedSetter,
                            "Supertype JAR absent while verifying setter '" + expectedSetter + "' on " + bean.className()));
                    findings.add(new Finding(
                            RULE_XML_BEAN_CLASS_AND_PROPS, "LIBRARY_JAR_ABSENT",
                            ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.COMPILER,
                            subj + "#" + prop.name(),
                            "Cannot verify setter '" + expectedSetter + "' for XML property '" + prop.name() + "' because supertype JAR is absent",
                            Optional.ofNullable(bean.sourceFile()), bean.line(),
                            Optional.empty(), Map.of(), Optional.of(GAP_LIBRARY_JAR_ABSENT)));
                } else if (setterSearch == HierarchySearchResult.NOT_FOUND) {
                    findings.add(new Finding(
                            RULE_XML_BEAN_CLASS_AND_PROPS, "XML_PROPERTY_SETTER_NOT_FOUND",
                            ArchitecturePolicy.Severity.ERROR, Classification.MUST, td.trustTier(),
                            subj + "#" + prop.name(),
                            "XML bean '" + bean.beanId() + "' sets property '" + prop.name() + "' but '" + bean.className()
                                    + "' has no setter '" + expectedSetter + "(...)'",
                            Optional.ofNullable(bean.sourceFile()), bean.line(),
                            Optional.empty(), Map.of(), Optional.empty()));
                }
            }
        }

        // 1D: @ImportResource & XML <import resource="...">
        var knownResources = collectRepositoryResourcePaths(ctx);
        var configCatalog = extractConfigCatalog(ctx);
        var importChecks = extractImportResources(ctx, xmlBeans);
        for (var imp : importChecks) {
            if (findings.size() >= budget) break;
            checked++;
            String resolvedPath = resolvePlaceholdersOrNull(imp.resourcePath(), configCatalog);
            if (resolvedPath == null) {
                gaps.add(ctx.createGap(GAP_IMPORT_RESOURCE_UNRESOLVED, imp.subject(),
                        "Unresolved placeholder in resource import '" + imp.resourcePath() + "'"));
                findings.add(new Finding(
                        RULE_XML_BEAN_CLASS_AND_PROPS, "IMPORT_RESOURCE_UNRESOLVED",
                        ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.SOURCE_SYNTAX,
                        imp.subject(), "Cannot resolve dynamic placeholder in imported resource '" + imp.resourcePath() + "'",
                        Optional.ofNullable(imp.sourceFile()), imp.line(),
                        Optional.empty(), Map.of(), Optional.of(GAP_IMPORT_RESOURCE_UNRESOLVED)));
                continue;
            }
            boolean isClasspath = resolvedPath.startsWith("classpath:") || resolvedPath.startsWith("classpath*:");
            String normalized = normalizeResourcePath(resolvedPath);
            if (!matchesCapturedResource(normalized, knownResources)) {
                if (isClasspath && !ctx.effectiveClasspathComplete()) {
                    gaps.add(ctx.createGap(GAP_LIBRARY_JAR_ABSENT, normalized,
                            "Classpath resource '" + resolvedPath + "' may reside in an absent offline JAR"));
                    findings.add(new Finding(
                            RULE_XML_BEAN_CLASS_AND_PROPS, "LIBRARY_JAR_ABSENT",
                            ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.SOURCE_SYNTAX,
                            imp.subject(), "Classpath import '" + resolvedPath + "' not found locally and dependency JARs are absent",
                            Optional.ofNullable(imp.sourceFile()), imp.line(),
                            Optional.empty(), Map.of(), Optional.of(GAP_LIBRARY_JAR_ABSENT)));
                } else {
                    findings.add(new Finding(
                            RULE_XML_BEAN_CLASS_AND_PROPS, "IMPORT_RESOURCE_NOT_FOUND",
                            ArchitecturePolicy.Severity.ERROR, Classification.MUST, TrustTier.SOURCE_SYNTAX,
                            imp.subject(), "Imported Spring XML resource '" + resolvedPath + "' does not exist in captured repository resources",
                            Optional.ofNullable(imp.sourceFile()), imp.line(),
                            Optional.empty(), Map.of(), Optional.empty()));
                }
            }
        }

        return buildOutcome(RULE_XML_BEAN_CLASS_AND_PROPS, checked, findings, gaps);
    }

    // ─── Rule 2: spring-bean-lifecycle-method-exists ───

    private RuleOutcome evaluateRule2LifecycleMethodExists(EvalContext ctx, int budget) {
        var findings = new ArrayList<Finding>();
        var gaps = new TreeSet<CapabilityGapRecord>();
        int checked = 0;

        var lifecycleRefs = extractLifecycleMethodRefs(ctx);
        for (var ref : lifecycleRefs) {
            if (findings.size() >= budget) {
                gaps.add(ctx.createGap(GAP_EVALUATION_LIMIT, RULE_BEAN_LIFECYCLE_METHOD,
                        "Reached maxFindings budget while evaluating " + RULE_BEAN_LIFECYCLE_METHOD));
                break;
            }
            if (isIgnoredLifecycleMethod(ref.methodName())) continue;
            checked++;

            TypeLookupOutcome lookup = ctx.resolveExactType(ref.targetClass());
            if (lookup.status() == LookupStatus.LIBRARY_JAR_ABSENT) {
                String hint = lookup.missingArtifactHint().orElse("Absent JAR for " + ref.targetClass());
                gaps.add(ctx.createGap(GAP_LIBRARY_JAR_ABSENT, ref.targetClass(), hint));
                findings.add(new Finding(
                        RULE_BEAN_LIFECYCLE_METHOD, "LIBRARY_JAR_ABSENT",
                        ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.COMPILER,
                        ref.subject(),
                        "Cannot verify " + ref.lifecycleKind() + "='" + ref.methodName() + "' on '" + ref.targetClass() + "' because classpath JAR is absent",
                        Optional.ofNullable(ref.sourceFile()), ref.line(),
                        Optional.empty(), Map.of(), Optional.of(GAP_LIBRARY_JAR_ABSENT)));
                continue;
            }
            if (lookup.status() == LookupStatus.NOT_FOUND) {
                findings.add(new Finding(
                        RULE_BEAN_LIFECYCLE_METHOD, "LIFECYCLE_TARGET_CLASS_NOT_FOUND",
                        ArchitecturePolicy.Severity.ERROR, Classification.MUST, TrustTier.COMPILER,
                        ref.subject(),
                        "Cannot resolve target bean class '" + ref.targetClass() + "' for " + ref.lifecycleKind() + "='" + ref.methodName() + "'",
                        Optional.ofNullable(ref.sourceFile()), ref.line(),
                        Optional.empty(), Map.of(), Optional.empty()));
                continue;
            }

            TypeDescriptor td = lookup.descriptor().orElseThrow();
            HierarchySearchResult foundZeroArg = findZeroArgMethodInHierarchy(ctx, td, ref.methodName());
            if (foundZeroArg == HierarchySearchResult.LIBRARY_JAR_ABSENT) {
                gaps.add(ctx.createGap(GAP_LIBRARY_JAR_ABSENT, ref.targetClass() + "#" + ref.methodName(),
                        "Supertype JAR absent while verifying 0-arg lifecycle method '" + ref.methodName() + "'"));
                findings.add(new Finding(
                        RULE_BEAN_LIFECYCLE_METHOD, "LIBRARY_JAR_ABSENT",
                        ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.COMPILER,
                        ref.subject(),
                        "Cannot verify 0-arg " + ref.lifecycleKind() + "='" + ref.methodName() + "' on '" + ref.targetClass() + "' because supertype JAR is absent",
                        Optional.ofNullable(ref.sourceFile()), ref.line(),
                        Optional.empty(), Map.of(), Optional.of(GAP_LIBRARY_JAR_ABSENT)));
            } else if (foundZeroArg == HierarchySearchResult.NOT_FOUND) {
                findings.add(new Finding(
                        RULE_BEAN_LIFECYCLE_METHOD, "LIFECYCLE_METHOD_NOT_FOUND",
                        ArchitecturePolicy.Severity.ERROR, Classification.MUST, td.trustTier(),
                        ref.subject(),
                        "Declared " + ref.lifecycleKind() + "='" + ref.methodName() + "' does not resolve to an existing 0-arg method on '" + ref.targetClass() + "'",
                        Optional.ofNullable(ref.sourceFile()), ref.line(),
                        Optional.empty(), Map.of(), Optional.empty()));
            }
        }
        return buildOutcome(RULE_BEAN_LIFECYCLE_METHOD, checked, findings, gaps);
    }

    // ─── Rule 3: boot-property-key-exists ───

    private RuleOutcome evaluateRule3BootPropertyKeyExists(EvalContext ctx, int budget) {
        var findings = new ArrayList<Finding>();
        var gaps = new TreeSet<CapabilityGapRecord>();
        var config = extractConfigCatalog(ctx);
        var propRefs = extractPropertyReferences(ctx);
        int checked = 0;

        for (var ref : propRefs) {
            if (findings.size() >= budget) {
                gaps.add(ctx.createGap(GAP_EVALUATION_LIMIT, RULE_BOOT_PROPERTY_KEY,
                        "Reached maxFindings budget while evaluating " + RULE_BOOT_PROPERTY_KEY));
                break;
            }
            if (ref.hasDefault() || ref.matchIfMissing()) {
                continue; // Explicitly defaulted or matchIfMissing=true -> clean!
            }
            checked++;
            if (ref.dynamicPlaceholder()) {
                gaps.add(ctx.createGap(GAP_CONFIG_EVIDENCE_INCOMPLETE, ref.subject(),
                        "Nested or dynamic property placeholder cannot be statically resolved: " + ref.rawKey()));
                findings.add(new Finding(
                        RULE_BOOT_PROPERTY_KEY, "DYNAMIC_PROPERTY_PLACEHOLDER",
                        ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.SOURCE_SYNTAX,
                        ref.subject(), "Dynamic or nested property key '" + ref.rawKey() + "' cannot be statically verified",
                        Optional.ofNullable(ref.sourceFile()), ref.line(),
                        Optional.empty(), Map.of(), Optional.of(GAP_CONFIG_EVIDENCE_INCOMPLETE)));
                continue;
            }
            if (!config.hasAnyConfigDocument()) {
                gaps.add(ctx.createGap(GAP_CONFIG_EVIDENCE_INCOMPLETE, ref.subject(),
                        "No ConfigDataIngestion configuration documents captured for property check '" + ref.rawKey() + "'"));
                findings.add(new Finding(
                        RULE_BOOT_PROPERTY_KEY, "CONFIG_EVIDENCE_INCOMPLETE",
                        ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.SOURCE_SYNTAX,
                        ref.subject(), "No configuration files captured to verify property key '" + ref.rawKey() + "'",
                        Optional.ofNullable(ref.sourceFile()), ref.line(),
                        Optional.empty(), Map.of(), Optional.of(GAP_CONFIG_EVIDENCE_INCOMPLETE)));
                continue;
            }

            String normKey = normalizeRelaxedKey(ref.rawKey());
            if (containsRelaxedKey(config.baseKeys(), normKey)) {
                continue; // Present in unconditional base/bootstrap/shared layer -> valid in all profiles!
            }

            // Evaluate per profile / truth-region scope
            var applicableProfiles = determineApplicableProfiles(ref.declaredProfiles(), config.allProfiles());
            var presentProfiles = new TreeSet<String>();
            var missingProfiles = new TreeSet<String>();

            for (String profile : applicableProfiles) {
                Set<String> profKeys = config.profileKeys().getOrDefault(profile, Set.of());
                if (containsRelaxedKey(profKeys, normKey)) {
                    presentProfiles.add(profile);
                } else {
                    missingProfiles.add(profile);
                }
            }

            if (missingProfiles.isEmpty() && !presentProfiles.isEmpty()) {
                continue; // Covered across all applicable profiles
            }

            boolean isConditionalOnProp = "CONDITIONAL_ON_PROPERTY".equals(ref.sourceKind());
            if (presentProfiles.isEmpty()) {
                String scopeLabel = ref.declaredProfiles().isEmpty()
                        ? "ALL_PROFILES"
                        : "@Profile(" + String.join(",", ref.declaredProfiles()) + ")";
                var witness = new TreeMap<String, String>();
                if (!missingProfiles.isEmpty()) {
                    witness.put("spring.profiles.active", missingProfiles.first());
                }
                findings.add(new Finding(
                        RULE_BOOT_PROPERTY_KEY,
                        isConditionalOnProp ? "CONDITIONAL_PROPERTY_KEY_MISSING" : "VALUE_PROPERTY_KEY_MISSING",
                        isConditionalOnProp ? ArchitecturePolicy.Severity.WARNING : ArchitecturePolicy.Severity.ERROR,
                        Classification.MUST,
                        TrustTier.SPRING_SAT,
                        ref.subject(),
                        (isConditionalOnProp ? "@ConditionalOnProperty" : "@Value(\"${" + ref.rawKey() + "}\")")
                                + " references key '" + ref.rawKey() + "' absent from all ConfigDataIngestion layers"
                                + " (scope: " + scopeLabel + ")",
                        Optional.ofNullable(ref.sourceFile()), ref.line(),
                        Optional.of(scopeLabel), witness, Optional.empty()));
            } else {
                String missingScope = "missing-under-profiles:[" + String.join(",", missingProfiles) + "]";
                var witness = new TreeMap<String, String>();
                witness.put("spring.profiles.active", missingProfiles.first());
                witness.put("missingKey", ref.rawKey());
                findings.add(new Finding(
                        RULE_BOOT_PROPERTY_KEY,
                        isConditionalOnProp ? "CONDITIONAL_PROPERTY_MISSING_IN_PROFILE" : "VALUE_PROPERTY_MISSING_IN_PROFILE",
                        ArchitecturePolicy.Severity.WARNING,
                        Classification.MAY,
                        TrustTier.SPRING_SAT,
                        ref.subject(),
                        (isConditionalOnProp ? "@ConditionalOnProperty" : "@Value(\"${" + ref.rawKey() + "}\")")
                                + " key '" + ref.rawKey() + "' is defined in " + presentProfiles
                                + " but missing under profile(s) " + missingProfiles,
                        Optional.ofNullable(ref.sourceFile()), ref.line(),
                        Optional.of(missingScope), witness, Optional.empty()));
            }
        }
        return buildOutcome(RULE_BOOT_PROPERTY_KEY, checked, findings, gaps);
    }

    // ─── Rule 4: boot-config-properties-binding ───

    private RuleOutcome evaluateRule4ConfigPropertiesBinding(EvalContext ctx, int budget) {
        var findings = new ArrayList<Finding>();
        var gaps = new TreeSet<CapabilityGapRecord>();
        var config = extractConfigCatalog(ctx);
        var cpBeans = extractConfigPropertiesBeans(ctx);
        int checked = 0;

        for (var cp : cpBeans) {
            if (findings.size() >= budget) {
                gaps.add(ctx.createGap(GAP_EVALUATION_LIMIT, RULE_BOOT_CONFIG_PROPERTIES,
                        "Reached maxFindings budget while evaluating " + RULE_BOOT_CONFIG_PROPERTIES));
                break;
            }
            if (cp.prefix().isBlank()) continue;
            TypeLookupOutcome lookup = ctx.resolveExactType(cp.className());
            if (lookup.status() == LookupStatus.LIBRARY_JAR_ABSENT) {
                gaps.add(ctx.createGap(GAP_LIBRARY_JAR_ABSENT, cp.className(),
                        "Absent JAR for @ConfigurationProperties class " + cp.className()));
                findings.add(new Finding(
                        RULE_BOOT_CONFIG_PROPERTIES, "LIBRARY_JAR_ABSENT",
                        ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.COMPILER,
                        cp.className(), "Cannot validate @ConfigurationProperties(prefix=\"" + cp.prefix() + "\") because class JAR is absent",
                        Optional.ofNullable(cp.sourceFile()), cp.line(),
                        Optional.empty(), Map.of(), Optional.of(GAP_LIBRARY_JAR_ABSENT)));
                continue;
            }
            if (lookup.status() == LookupStatus.NOT_FOUND) {
                continue;
            }
            TypeDescriptor td = lookup.descriptor().orElseThrow();
            String normPrefix = normalizeRelaxedKey(cp.prefix());

            // Gather all configured keys under prefix.* (including UPPER_SNAKE env style PREFIX_KEY)
            var matchedConfigKeys = new TreeSet<String>();
            for (String rawKey : config.rawKeyToFirstValue().keySet()) {
                Optional<String> remainder = stripRelaxedPrefix(rawKey, normPrefix);
                if (remainder.isPresent() && !remainder.get().isBlank()) {
                    matchedConfigKeys.add(rawKey);
                }
            }

            for (String rawKey : matchedConfigKeys) {
                if (findings.size() >= budget) break;
                checked++;
                String remainder = stripRelaxedPrefix(rawKey, normPrefix).orElse("");
                ConfigBindingCheckResult binding = checkConfigPropertyBinding(ctx, td, remainder, 0);
                switch (binding.status()) {
                    case BOUND -> {}
                    case LIBRARY_JAR_ABSENT -> {
                        gaps.add(ctx.createGap(GAP_LIBRARY_JAR_ABSENT, cp.className() + ":" + rawKey,
                                binding.detail()));
                        findings.add(new Finding(
                                RULE_BOOT_CONFIG_PROPERTIES, "LIBRARY_JAR_ABSENT",
                                ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.COMPILER,
                                cp.className() + "#" + rawKey,
                                "Cannot verify nested @ConfigurationProperties binding for '" + rawKey + "': " + binding.detail(),
                                Optional.ofNullable(cp.sourceFile()), cp.line(),
                                Optional.empty(), Map.of(), Optional.of(GAP_LIBRARY_JAR_ABSENT)));
                    }
                    case NO_SETTER -> findings.add(new Finding(
                            RULE_BOOT_CONFIG_PROPERTIES, "CONFIG_PROPERTIES_NO_SETTER",
                            ArchitecturePolicy.Severity.ERROR, Classification.MUST, td.trustTier(),
                            cp.className() + "#" + rawKey,
                            "Config key '" + rawKey + "' matches field on @ConfigurationProperties(prefix=\"" + cp.prefix()
                                    + "\") class '" + cp.className() + "' but no setter or @ConstructorBinding exists (" + binding.detail() + ")",
                            Optional.ofNullable(cp.sourceFile()), cp.line(),
                            Optional.empty(), Map.of(), Optional.empty()));
                    case UNBOUND -> findings.add(new Finding(
                            RULE_BOOT_CONFIG_PROPERTIES, "CONFIG_PROPERTIES_UNBOUND_KEY",
                            cp.ignoreUnknownFields() ? ArchitecturePolicy.Severity.WARNING : ArchitecturePolicy.Severity.ERROR,
                            Classification.MUST, td.trustTier(),
                            cp.className() + "#" + rawKey,
                            "Config key '" + rawKey + "' under @ConfigurationProperties(prefix=\"" + cp.prefix()
                                    + "\") does not bind to any relaxed-bound field or setter on '" + cp.className() + "'",
                            Optional.ofNullable(cp.sourceFile()), cp.line(),
                            Optional.empty(), Map.of(), Optional.empty()));
                }
            }
        }
        return buildOutcome(RULE_BOOT_CONFIG_PROPERTIES, checked, findings, gaps);
    }

    // ─── Rule 5: spring-getbean-conditional-safety ───

    private RuleOutcome evaluateRule5GetBeanConditionalSafety(EvalContext ctx, int budget) {
        var findings = new ArrayList<Finding>();
        var gaps = new TreeSet<CapabilityGapRecord>();
        var lookups = extractBeanLookups(ctx);
        var truthByBean = extractBeanTruthCatalog(ctx);
        int checked = 0;

        for (BeanLookupDef lookup : lookups) {
            if (findings.size() >= budget) {
                gaps.add(ctx.createGap(GAP_EVALUATION_LIMIT, RULE_GETBEAN_CONDITIONAL_SAFETY,
                        "Reached maxFindings budget while evaluating " + RULE_GETBEAN_CONDITIONAL_SAFETY));
                break;
            }
            checked++;
            if (lookup.dynamic() || (lookup.beanName().isBlank() && lookup.beanType().isBlank())) {
                gaps.add(ctx.createGap(GAP_DYNAMIC_BEAN_LOOKUP, lookup.subjectId(),
                        "Dynamic getBean(...) argument cannot be resolved statically"));
                findings.add(new Finding(
                        RULE_GETBEAN_CONDITIONAL_SAFETY, "DYNAMIC_BEAN_LOOKUP",
                        ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.SOURCE_SYNTAX,
                        lookup.subjectId(), "Dynamic getBean() lookup cannot be statically verified against SymbolicTruthRegions",
                        Optional.ofNullable(lookup.sourceFile()), lookup.line(),
                        Optional.empty(), Map.of(), Optional.of(GAP_DYNAMIC_BEAN_LOOKUP)));
                continue;
            }

            Optional<BeanTruthInfo> match = matchBeanTruth(lookup, truthByBean);
            if (match.isEmpty()) {
                // Check if target type is from an absent JAR
                if (!lookup.beanType().isBlank()
                        && ctx.resolveExactType(lookup.beanType()).status() == LookupStatus.LIBRARY_JAR_ABSENT) {
                    gaps.add(ctx.createGap(GAP_LIBRARY_JAR_ABSENT, lookup.beanType(),
                            "Target bean type '" + lookup.beanType() + "' belongs to an absent classpath JAR"));
                    findings.add(new Finding(
                            RULE_GETBEAN_CONDITIONAL_SAFETY, "LIBRARY_JAR_ABSENT",
                            ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.COMPILER,
                            lookup.subjectId(),
                            "Cannot verify getBean() target '" + firstNonBlank(lookup.beanName(), lookup.beanType())
                                    + "' because target class JAR is absent",
                            Optional.ofNullable(lookup.sourceFile()), lookup.line(),
                            Optional.empty(), Map.of(), Optional.of(GAP_LIBRARY_JAR_ABSENT)));
                } else {
                    gaps.add(ctx.createGap(GAP_BEAN_TRUTH_UNKNOWN, lookup.subjectId(),
                            "No SymbolicTruthRegion recorded for getBean target '" + firstNonBlank(lookup.beanName(), lookup.beanType()) + "'"));
                    findings.add(new Finding(
                            RULE_GETBEAN_CONDITIONAL_SAFETY, "BEAN_TRUTH_UNKNOWN",
                            ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.SPRING_SAT,
                            lookup.subjectId(),
                            "Truth region for getBean() target '" + firstNonBlank(lookup.beanName(), lookup.beanType())
                                    + "' is unresolved",
                            Optional.ofNullable(lookup.sourceFile()), lookup.line(),
                            Optional.empty(), Map.of(), Optional.of(GAP_BEAN_TRUTH_UNKNOWN)));
                }
                continue;
            }

            BeanTruthInfo truth = match.get();
            switch (truth.classification()) {
                case MUST -> {
                    // Unconditionally active across all valid configurations -> CLEAN!
                }
                case NEVER -> findings.add(new Finding(
                        RULE_GETBEAN_CONDITIONAL_SAFETY, "GETBEAN_TARGET_NEVER_ACTIVE",
                        ArchitecturePolicy.Severity.ERROR, Classification.MUST, truth.trustTier(),
                        lookup.subjectId(),
                        "getBean('" + firstNonBlank(lookup.beanName(), lookup.beanType())
                                + "') targets bean with SymbolicTruthRegion=NEVER (guaranteed NoSuchBeanDefinitionException)",
                        Optional.ofNullable(lookup.sourceFile()), lookup.line(),
                        truth.truthRegionScope().or(() -> Optional.of("NEVER")),
                        truth.counterexampleWitness(), Optional.empty()));
                case MAY -> findings.add(new Finding(
                        RULE_GETBEAN_CONDITIONAL_SAFETY, "GETBEAN_TARGET_CONDITIONAL_MAY",
                        ArchitecturePolicy.Severity.WARNING, Classification.MAY, truth.trustTier(),
                        lookup.subjectId(),
                        "getBean('" + firstNonBlank(lookup.beanName(), lookup.beanType())
                                + "') targets conditional bean (SymbolicTruthRegion=MAY); absent under counterexample configuration "
                                + truth.counterexampleWitness(),
                        Optional.ofNullable(lookup.sourceFile()), lookup.line(),
                        truth.truthRegionScope().or(() -> Optional.of("MAY")),
                        truth.counterexampleWitness(), Optional.empty()));
                case UNKNOWN -> {
                    gaps.add(ctx.createGap(GAP_BEAN_TRUTH_UNKNOWN, lookup.subjectId(),
                            "SymbolicTruthRegion for bean '" + firstNonBlank(lookup.beanName(), lookup.beanType()) + "' is UNKNOWN"));
                    findings.add(new Finding(
                            RULE_GETBEAN_CONDITIONAL_SAFETY, "BEAN_TRUTH_UNKNOWN",
                            ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, truth.trustTier(),
                            lookup.subjectId(),
                            "getBean('" + firstNonBlank(lookup.beanName(), lookup.beanType())
                                    + "') targets bean with SymbolicTruthRegion=UNKNOWN",
                            Optional.ofNullable(lookup.sourceFile()), lookup.line(),
                            truth.truthRegionScope().or(() -> Optional.of("UNKNOWN")),
                            Map.of(), Optional.of(GAP_BEAN_TRUTH_UNKNOWN)));
                }
            }
        }
        return buildOutcome(RULE_GETBEAN_CONDITIONAL_SAFETY, checked, findings, gaps);
    }

    // ─── Rule 6: feign-controller-contract-parity ───

    private RuleOutcome evaluateRule6FeignControllerParity(EvalContext ctx, int budget) {
        var findings = new ArrayList<Finding>();
        var gaps = new TreeSet<CapabilityGapRecord>();
        var feignEndpoints = extractFeignEndpoints(ctx);
        var controllerEndpoints = extractControllerEndpoints(ctx);
        var knownServices = collectKnownServices(ctx, controllerEndpoints);
        int checked = 0;

        for (FeignEndpointDef feign : feignEndpoints) {
            if (findings.size() >= budget) {
                gaps.add(ctx.createGap(GAP_EVALUATION_LIMIT, RULE_FEIGN_CONTROLLER_PARITY,
                        "Reached maxFindings budget while evaluating " + RULE_FEIGN_CONTROLLER_PARITY));
                break;
            }
            checked++;
            if (feign.dynamicOrSpel() || feign.normalizedPath().isBlank()) {
                gaps.add(ctx.createGap(GAP_FEIGN_TARGET_UNRESOLVED, feign.subjectId(),
                        "Dynamic or SpEL @FeignClient route cannot be statically matched"));
                findings.add(new Finding(
                        RULE_FEIGN_CONTROLLER_PARITY, "FEIGN_TARGET_UNRESOLVED",
                        ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.SOURCE_SYNTAX,
                        feign.subjectId(), "Dynamic/SpEL @FeignClient route cannot be statically verified",
                        Optional.ofNullable(feign.sourceFile()), feign.line(),
                        Optional.empty(), Map.of(), Optional.of(GAP_FEIGN_TARGET_UNRESOLVED)));
                continue;
            }

            // Determine candidate controllers in the target service (or all controllers if single-service / untagged)
            List<ControllerEndpointDef> targetControllers;
            if (!feign.targetService().isBlank()) {
                String targetSvcNorm = feign.targetService().toLowerCase(Locale.ROOT);
                boolean targetServiceCaptured = knownServices.contains(targetSvcNorm);
                targetControllers = controllerEndpoints.stream()
                        .filter(c -> c.serviceName().equalsIgnoreCase(feign.targetService()))
                        .toList();
                if (targetControllers.isEmpty() && !targetServiceCaptured) {
                    gaps.add(ctx.createGap(GAP_FEIGN_TARGET_UNRESOLVED, feign.subjectId(),
                            "Target service '" + feign.targetService() + "' is not captured in this KnowledgeGraph"));
                    findings.add(new Finding(
                            RULE_FEIGN_CONTROLLER_PARITY, "FEIGN_TARGET_UNRESOLVED",
                            ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.SOURCE_SYNTAX,
                            feign.subjectId(),
                            "@FeignClient target service '" + feign.targetService() + "' is absent from captured services",
                            Optional.ofNullable(feign.sourceFile()), feign.line(),
                            Optional.empty(), Map.of(), Optional.of(GAP_FEIGN_TARGET_UNRESOLVED)));
                    continue;
                }
            } else {
                if (controllerEndpoints.isEmpty()) {
                    gaps.add(ctx.createGap(GAP_FEIGN_TARGET_UNRESOLVED, feign.subjectId(),
                            "No controller endpoints captured to verify @FeignClient " + feign.httpMethod() + " " + feign.rawPath()));
                    findings.add(new Finding(
                            RULE_FEIGN_CONTROLLER_PARITY, "FEIGN_TARGET_UNRESOLVED",
                            ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.SOURCE_SYNTAX,
                            feign.subjectId(), "No controller endpoints captured for @FeignClient parity verification",
                            Optional.ofNullable(feign.sourceFile()), feign.line(),
                            Optional.empty(), Map.of(), Optional.of(GAP_FEIGN_TARGET_UNRESOLVED)));
                    continue;
                }
                targetControllers = controllerEndpoints;
            }

            var pathMatches = targetControllers.stream()
                    .filter(c -> routesMatch(feign.normalizedPath(), c.normalizedPath()))
                    .toList();
            if (pathMatches.isEmpty()) {
                findings.add(new Finding(
                        RULE_FEIGN_CONTROLLER_PARITY, "FEIGN_ENDPOINT_NOT_FOUND",
                        ArchitecturePolicy.Severity.ERROR, Classification.MUST, TrustTier.SOURCE_SYNTAX,
                        feign.subjectId(),
                        "@FeignClient " + feign.httpMethod() + " '" + feign.rawPath() + "' (normalized '"
                                + feign.normalizedPath() + "') has no matching controller endpoint in service '"
                                + firstNonBlank(feign.targetService(), "workspace") + "'",
                        Optional.ofNullable(feign.sourceFile()), feign.line(),
                        Optional.empty(), Map.of(), Optional.empty()));
                continue;
            }

            boolean verbMatched = pathMatches.stream().anyMatch(c -> verbMatches(feign.httpMethod(), c));
            if (!verbMatched) {
                var actualVerbs = new TreeSet<String>();
                pathMatches.forEach(c -> actualVerbs.addAll(c.httpMethods()));
                findings.add(new Finding(
                        RULE_FEIGN_CONTROLLER_PARITY, "FEIGN_HTTP_VERB_MISMATCH",
                        ArchitecturePolicy.Severity.ERROR, Classification.MUST, TrustTier.SOURCE_SYNTAX,
                        feign.subjectId(),
                        "@FeignClient declares " + feign.httpMethod() + " '" + feign.rawPath()
                                + "' but target controller endpoint only accepts " + actualVerbs,
                        Optional.ofNullable(feign.sourceFile()), feign.line(),
                        Optional.empty(), Map.of(), Optional.empty()));
            }
        }
        return buildOutcome(RULE_FEIGN_CONTROLLER_PARITY, checked, findings, gaps);
    }

    // ─── Bounded Declarative RSL-Style Rule Interpreter (forEach / where / exists / assert) ───

    private RuleOutcome evaluateCustomRslRule(EvalContext ctx, PolicyCompiler.MetadataRule rule, int budget) {
        var findings = new ArrayList<Finding>();
        var gaps = new TreeSet<CapabilityGapRecord>();
        var domainRows = loadDomainRows(ctx, rule.forEach().domain());
        int checked = 0;

        if (domainRows.size() > ctx.options().maxDomainItems()) {
            gaps.add(ctx.createGap(GAP_EVALUATION_LIMIT, rule.id(),
                    "Domain " + rule.forEach().domain() + " exceeded maxDomainItems=" + ctx.options().maxDomainItems()));
        }
        int limit = Math.min(domainRows.size(), ctx.options().maxDomainItems());
        var outerVar = rule.forEach().variable();

        // Preload existential domain once if rule uses EXISTS / NOT_EXISTS (O(1) memoized hit across iterations!)
        List<Map<String, String>> existsRows = rule.assertion().exists()
                .map(ex -> loadDomainRows(ctx, ex.domain()))
                .orElse(List.of());

        for (int i = 0; i < limit; i++) {
            if (findings.size() >= budget) {
                gaps.add(ctx.createGap(GAP_EVALUATION_LIMIT, rule.id(),
                        "Reached maxFindings budget while evaluating custom RSL rule " + rule.id()));
                break;
            }
            Map<String, String> row = domainRows.get(i);
            var env = new HashMap<String, Map<String, String>>(4);
            env.put(outerVar, row);

            if (!matchesAllPredicates(rule.where(), env)) {
                continue;
            }
            checked++;
            var assertClause = rule.assertion();
            String subject = row.getOrDefault("subject", row.getOrDefault("id", outerVar + "#" + i));
            String sourceFile = row.getOrDefault("sourceFile", "");
            int line = parseIntSafe(row.get("line"), 0);

            switch (assertClause.op()) {
                case EXISTS -> {
                    var ex = assertClause.exists().orElseThrow();
                    boolean found = evaluateExists(ex, existsRows, env, ctx.options().maxDomainItems());
                    if (!found) {
                        findings.add(new Finding(
                                rule.id(), "RSL_ASSERT_EXISTS_FAILED",
                                rule.severity(), Classification.MUST, TrustTier.SOURCE_SYNTAX,
                                subject, assertClause.message(),
                                sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                Optional.empty(), Map.of(), Optional.empty()));
                    }
                }
                case NOT_EXISTS -> {
                    var ex = assertClause.exists().orElseThrow();
                    boolean found = evaluateExists(ex, existsRows, env, ctx.options().maxDomainItems());
                    if (found) {
                        findings.add(new Finding(
                                rule.id(), "RSL_ASSERT_NOT_EXISTS_FAILED",
                                rule.severity(), Classification.MUST, TrustTier.SOURCE_SYNTAX,
                                subject, assertClause.message(),
                                sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                Optional.empty(), Map.of(), Optional.empty()));
                    }
                }
                case PREDICATE -> {
                    var pred = assertClause.predicate().orElseThrow();
                    if (!matchesPredicate(pred, env)) {
                        findings.add(new Finding(
                                rule.id(), "RSL_ASSERT_PREDICATE_FAILED",
                                rule.severity(), Classification.MUST, TrustTier.SOURCE_SYNTAX,
                                subject, assertClause.message(),
                                sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                Optional.empty(), Map.of(), Optional.empty()));
                    }
                }
                case CLASS_RESOLVABLE -> {
                    String fqcn = resolveFieldValue(assertClause.targetField().orElse(outerVar + ".className"), env);
                    if (fqcn != null && !fqcn.isBlank()) {
                        TypeLookupOutcome outcome = ctx.resolveExactType(fqcn);
                        if (outcome.status() == LookupStatus.LIBRARY_JAR_ABSENT) {
                            gaps.add(ctx.createGap(GAP_LIBRARY_JAR_ABSENT, fqcn, "Absent JAR for " + fqcn));
                            findings.add(new Finding(
                                    rule.id(), "LIBRARY_JAR_ABSENT",
                                    ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.COMPILER,
                                    subject, assertClause.message() + " (classpath JAR absent for " + fqcn + ")",
                                    sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                    Optional.empty(), Map.of(), Optional.of(GAP_LIBRARY_JAR_ABSENT)));
                        } else if (outcome.status() == LookupStatus.NOT_FOUND) {
                            findings.add(new Finding(
                                    rule.id(), "RSL_CLASS_NOT_FOUND",
                                    rule.severity(), Classification.MUST, TrustTier.COMPILER,
                                    subject, assertClause.message() + " (" + fqcn + ")",
                                    sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                    Optional.empty(), Map.of(), Optional.empty()));
                        }
                    }
                }
                case PROPERTY_EXISTS -> {
                    var config = extractConfigCatalog(ctx);
                    String key = resolveFieldValue(assertClause.targetField().orElse(outerVar + ".key"), env);
                    if (key != null && !key.isBlank()) {
                        if (!config.hasAnyConfigDocument()) {
                            gaps.add(ctx.createGap(GAP_CONFIG_EVIDENCE_INCOMPLETE, subject,
                                    "No configuration layers captured for RSL PROPERTY_EXISTS"));
                            findings.add(new Finding(
                                    rule.id(), "CONFIG_EVIDENCE_INCOMPLETE",
                                    ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.SOURCE_SYNTAX,
                                    subject, assertClause.message(),
                                    sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                    Optional.empty(), Map.of(), Optional.of(GAP_CONFIG_EVIDENCE_INCOMPLETE)));
                        } else if (!containsRelaxedKey(config.allNormalizedKeys(), normalizeRelaxedKey(key))) {
                            findings.add(new Finding(
                                    rule.id(), "RSL_PROPERTY_NOT_FOUND",
                                    rule.severity(), Classification.MUST, TrustTier.SPRING_SAT,
                                    subject, assertClause.message() + " (" + key + ")",
                                    sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                    Optional.empty(), Map.of(), Optional.empty()));
                        }
                    }
                }
                case METHOD_EXISTS -> {
                    String ownerClass = resolveFieldValue(outerVar + ".className", env);
                    String methodName = resolveFieldValue(assertClause.targetField().orElse(outerVar + ".methodName"), env);
                    if (ownerClass != null && !ownerClass.isBlank() && methodName != null && !methodName.isBlank()) {
                        TypeLookupOutcome outcome = ctx.resolveExactType(ownerClass);
                        if (outcome.status() == LookupStatus.LIBRARY_JAR_ABSENT) {
                            gaps.add(ctx.createGap(GAP_LIBRARY_JAR_ABSENT, ownerClass, "Absent JAR for " + ownerClass));
                            findings.add(new Finding(
                                    rule.id(), "LIBRARY_JAR_ABSENT",
                                    ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.COMPILER,
                                    subject, assertClause.message(),
                                    sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                    Optional.empty(), Map.of(), Optional.of(GAP_LIBRARY_JAR_ABSENT)));
                        } else if (outcome.status() == LookupStatus.NOT_FOUND
                                || findZeroArgMethodInHierarchy(ctx, outcome.descriptor().orElseThrow(), methodName) == HierarchySearchResult.NOT_FOUND) {
                            findings.add(new Finding(
                                    rule.id(), "RSL_METHOD_NOT_FOUND",
                                    rule.severity(), Classification.MUST, TrustTier.COMPILER,
                                    subject, assertClause.message() + " (" + ownerClass + "#" + methodName + ")",
                                    sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                    Optional.empty(), Map.of(), Optional.empty()));
                        }
                    }
                }
                case BEAN_TRUTH_SATISFIED -> {
                    var truthByBean = extractBeanTruthCatalog(ctx);
                    String bName = row.getOrDefault("beanName", "");
                    String bType = row.getOrDefault("beanType", "");
                    var matched = matchBeanTruth(new BeanLookupDef(subject, bName, bType, false, sourceFile, line), truthByBean);
                    if (matched.isEmpty() || matched.get().classification() == Classification.UNKNOWN) {
                        gaps.add(ctx.createGap(GAP_BEAN_TRUTH_UNKNOWN, subject, "Unknown bean truth region"));
                        findings.add(new Finding(
                                rule.id(), "BEAN_TRUTH_UNKNOWN",
                                ArchitecturePolicy.Severity.INFO, Classification.UNKNOWN, TrustTier.SPRING_SAT,
                                subject, assertClause.message(),
                                sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                Optional.empty(), Map.of(), Optional.of(GAP_BEAN_TRUTH_UNKNOWN)));
                    } else if (matched.get().classification() != Classification.MUST) {
                        Classification c = matched.get().classification() == Classification.NEVER
                                ? Classification.MUST : Classification.MAY;
                        findings.add(new Finding(
                                rule.id(), "RSL_BEAN_TRUTH_NOT_MUST",
                                rule.severity(), c, TrustTier.SPRING_SAT,
                                subject, assertClause.message(),
                                sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                matched.get().truthRegionScope(), matched.get().counterexampleWitness(), Optional.empty()));
                    }
                }
                case CONFIG_KEY_BOUND -> {
                    String prefix = row.getOrDefault("prefix", "");
                    String fqcn = row.getOrDefault("className", "");
                    String key = resolveFieldValue(assertClause.targetField().orElse(outerVar + ".key"), env);
                    if (!fqcn.isBlank() && key != null && !key.isBlank()) {
                        var outcome = ctx.resolveExactType(fqcn);
                        if (outcome.status() == LookupStatus.FOUND) {
                            String rem = prefix.isBlank() ? key : stripRelaxedPrefix(key, normalizeRelaxedKey(prefix)).orElse(key);
                            if (checkConfigPropertyBinding(ctx, outcome.descriptor().orElseThrow(), rem, 0).status() != BindingStatus.BOUND) {
                                findings.add(new Finding(
                                        rule.id(), "RSL_CONFIG_KEY_UNBOUND",
                                        rule.severity(), Classification.MUST, TrustTier.COMPILER,
                                        subject, assertClause.message() + " (" + key + ")",
                                        sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                        Optional.empty(), Map.of(), Optional.empty()));
                            }
                        }
                    }
                }
                case ENDPOINT_MATCHED -> {
                    var controllers = extractControllerEndpoints(ctx);
                    String normPath = row.getOrDefault("normalizedPath", normalizeRoutePath(row.getOrDefault("path", "")));
                    String verb = row.getOrDefault("httpMethod", "");
                    String targetSvc = row.getOrDefault("targetService", "");
                    boolean ok = controllers.stream().anyMatch(c ->
                            (targetSvc.isBlank() || c.serviceName().equalsIgnoreCase(targetSvc))
                                    && routesMatch(normPath, c.normalizedPath())
                                    && verbMatches(verb, c));
                    if (!ok) {
                        findings.add(new Finding(
                                rule.id(), "RSL_ENDPOINT_UNMATCHED",
                                rule.severity(), Classification.MUST, TrustTier.SOURCE_SYNTAX,
                                subject, assertClause.message(),
                                sourceFile.isBlank() ? Optional.empty() : Optional.of(sourceFile), line,
                                Optional.empty(), Map.of(), Optional.empty()));
                    }
                }
            }
        }
        return buildOutcome(rule.id(), checked, findings, gaps);
    }

    private boolean evaluateExists(
            PolicyCompiler.ExistsClause exists,
            List<Map<String, String>> candidateRows,
            Map<String, Map<String, String>> outerEnv,
            int maxItems
    ) {
        int limit = Math.min(candidateRows.size(), maxItems);
        var innerEnv = new HashMap<String, Map<String, String>>(outerEnv);
        for (int j = 0; j < limit; j++) {
            innerEnv.put(exists.variable(), candidateRows.get(j));
            if (matchesAllPredicates(exists.where(), innerEnv)) {
                return true; // Short-circuit existential quantifier (MeCheck RSL Fig. 4)
            }
        }
        return false;
    }

    private static boolean matchesAllPredicates(
            List<PolicyCompiler.MetadataPredicate> predicates,
            Map<String, Map<String, String>> env
    ) {
        for (var p : predicates) {
            if (!matchesPredicate(p, env)) return false;
        }
        return true;
    }

    private static boolean matchesPredicate(
            PolicyCompiler.MetadataPredicate p,
            Map<String, Map<String, String>> env
    ) {
        String lhs = resolveFieldValue(p.field(), env);
        String rhs = p.rhsField().isPresent()
                ? resolveFieldValue(p.rhsField().get(), env)
                : p.value().orElse("");
        return switch (p.op()) {
            case PRESENT -> lhs != null && !lhs.isBlank();
            case ABSENT -> lhs == null || lhs.isBlank();
            case IS_TRUE -> "true".equalsIgnoreCase(lhs);
            case IS_FALSE -> lhs == null || "false".equalsIgnoreCase(lhs) || lhs.isBlank();
            case EQUALS -> Objects.equals(lhs == null ? "" : lhs, rhs == null ? "" : rhs);
            case NOT_EQUALS -> !Objects.equals(lhs == null ? "" : lhs, rhs == null ? "" : rhs);
            case CONTAINS -> lhs != null && rhs != null && lhs.contains(rhs);
            case STARTS_WITH -> lhs != null && rhs != null && lhs.startsWith(rhs);
            case ENDS_WITH -> lhs != null && rhs != null && lhs.endsWith(rhs);
            case MATCHES_NORMALIZED -> lhs != null && rhs != null && relaxedEquivalent(lhs, rhs);
            case IN -> lhs != null && p.values().contains(lhs);
        };
    }

    private static String resolveFieldValue(String ref, Map<String, Map<String, String>> env) {
        if (ref == null || ref.isBlank()) return "";
        int dot = ref.indexOf('.');
        if (dot <= 0 || dot == ref.length() - 1) return "";
        String varName = ref.substring(0, dot);
        String fieldName = ref.substring(dot + 1);
        Map<String, String> row = env.get(varName);
        return row == null ? "" : row.getOrDefault(fieldName, "");
    }

    private List<Map<String, String>> loadDomainRows(EvalContext ctx, PolicyCompiler.MetadataDomain domain) {
        return ctx.service().memoizedQuery("wp2.2.domain-rows",
                List.of(ctx.context(), domain.name(), ctx.configDataKey()), () -> {
            var rows = new ArrayList<Map<String, String>>();
            switch (domain) {
                case XML_BEANS -> {
                    for (var b : extractXmlBeans(ctx)) {
                        var m = new TreeMap<String, String>();
                        m.put("id", b.beanId());
                        m.put("beanId", b.beanId());
                        m.put("subject", b.beanId() + ":" + b.className());
                        m.put("className", b.className());
                        m.put("scope", b.scope());
                        m.put("initMethod", b.initMethod());
                        m.put("destroyMethod", b.destroyMethod());
                        m.put("abstract", String.valueOf(b.abstractBean()));
                        m.put("sourceFile", b.sourceFile());
                        m.put("line", String.valueOf(b.line()));
                        rows.add(Collections.unmodifiableMap(m));
                    }
                }
                case XML_PROPERTIES -> {
                    for (var b : extractXmlBeans(ctx)) {
                        for (var p : b.properties()) {
                            var m = new TreeMap<String, String>();
                            m.put("subject", b.beanId() + "#" + p.name());
                            m.put("beanId", b.beanId());
                            m.put("className", b.className());
                            m.put("name", p.name());
                            m.put("setterName", p.name().isBlank() ? "" : toSetterName(p.name()));
                            m.put("ref", p.ref());
                            m.put("value", p.value());
                            m.put("sourceFile", b.sourceFile());
                            m.put("line", String.valueOf(b.line()));
                            rows.add(Collections.unmodifiableMap(m));
                        }
                    }
                }
                case XML_CONSTRUCTOR_ARGS -> {
                    for (var b : extractXmlBeans(ctx)) {
                        for (var a : b.constructorArgs()) {
                            var m = new TreeMap<String, String>();
                            m.put("subject", b.beanId() + "#arg" + a.index());
                            m.put("beanId", b.beanId());
                            m.put("className", b.className());
                            m.put("index", String.valueOf(a.index()));
                            m.put("name", a.name());
                            m.put("type", a.type());
                            m.put("ref", a.ref());
                            m.put("value", a.value());
                            m.put("sourceFile", b.sourceFile());
                            m.put("line", String.valueOf(b.line()));
                            rows.add(Collections.unmodifiableMap(m));
                        }
                    }
                }
                case XML_IMPORTS -> {
                    for (var imp : extractImportResources(ctx, extractXmlBeans(ctx))) {
                        var m = new TreeMap<String, String>();
                        m.put("subject", imp.subject());
                        m.put("resource", imp.resourcePath());
                        m.put("normalizedResource", normalizeResourcePath(imp.resourcePath()));
                        m.put("sourceFile", imp.sourceFile());
                        m.put("line", String.valueOf(imp.line()));
                        rows.add(Collections.unmodifiableMap(m));
                    }
                }
                case SPRING_BEANS -> {
                    for (var entry : extractBeanTruthCatalog(ctx).values()) {
                        var m = new TreeMap<String, String>();
                        m.put("subject", entry.beanId());
                        m.put("beanId", entry.beanId());
                        m.put("beanName", entry.beanName());
                        m.put("beanType", entry.beanType());
                        m.put("className", entry.beanType());
                        m.put("classification", entry.classification().name());
                        rows.add(Collections.unmodifiableMap(m));
                    }
                }
                case LIFECYCLE_METHODS -> {
                    for (var l : extractLifecycleMethodRefs(ctx)) {
                        var m = new TreeMap<String, String>();
                        m.put("subject", l.subject());
                        m.put("lifecycleKind", l.lifecycleKind());
                        m.put("methodName", l.methodName());
                        m.put("className", l.targetClass());
                        m.put("sourceFile", l.sourceFile());
                        m.put("line", String.valueOf(l.line()));
                        rows.add(Collections.unmodifiableMap(m));
                    }
                }
                case VALUE_PLACEHOLDERS, CONDITIONAL_PROPERTIES -> {
                    String want = domain == PolicyCompiler.MetadataDomain.VALUE_PLACEHOLDERS
                            ? "VALUE" : "CONDITIONAL_ON_PROPERTY";
                    for (var p : extractPropertyReferences(ctx)) {
                        if (!want.equals(p.sourceKind())) continue;
                        var m = new TreeMap<String, String>();
                        m.put("subject", p.subject());
                        m.put("key", p.rawKey());
                        m.put("normalizedKey", normalizeRelaxedKey(p.rawKey()));
                        m.put("hasDefault", String.valueOf(p.hasDefault()));
                        m.put("matchIfMissing", String.valueOf(p.matchIfMissing()));
                        m.put("profiles", String.join(",", p.declaredProfiles()));
                        m.put("sourceFile", p.sourceFile());
                        m.put("line", String.valueOf(p.line()));
                        rows.add(Collections.unmodifiableMap(m));
                    }
                }
                case CONFIG_PROPERTIES_BEANS -> {
                    for (var cp : extractConfigPropertiesBeans(ctx)) {
                        var m = new TreeMap<String, String>();
                        m.put("subject", cp.className());
                        m.put("className", cp.className());
                        m.put("prefix", cp.prefix());
                        m.put("normalizedPrefix", normalizeRelaxedKey(cp.prefix()));
                        m.put("sourceFile", cp.sourceFile());
                        m.put("line", String.valueOf(cp.line()));
                        rows.add(Collections.unmodifiableMap(m));
                    }
                }
                case BEAN_LOOKUPS -> {
                    for (var bl : extractBeanLookups(ctx)) {
                        var m = new TreeMap<String, String>();
                        m.put("subject", bl.subjectId());
                        m.put("beanName", bl.beanName());
                        m.put("beanType", bl.beanType());
                        m.put("dynamic", String.valueOf(bl.dynamic()));
                        m.put("sourceFile", bl.sourceFile());
                        m.put("line", String.valueOf(bl.line()));
                        rows.add(Collections.unmodifiableMap(m));
                    }
                }
                case FEIGN_ENDPOINTS -> {
                    for (var fe : extractFeignEndpoints(ctx)) {
                        var m = new TreeMap<String, String>();
                        m.put("subject", fe.subjectId());
                        m.put("targetService", fe.targetService());
                        m.put("httpMethod", fe.httpMethod());
                        m.put("path", fe.rawPath());
                        m.put("normalizedPath", fe.normalizedPath());
                        m.put("sourceFile", fe.sourceFile());
                        m.put("line", String.valueOf(fe.line()));
                        rows.add(Collections.unmodifiableMap(m));
                    }
                }
                case CONTROLLER_ENDPOINTS -> {
                    for (var ce : extractControllerEndpoints(ctx)) {
                        var verbs = ce.httpMethods().isEmpty() ? List.of("") : List.copyOf(ce.httpMethods());
                        for (String verb : verbs) {
                            var m = new TreeMap<String, String>();
                            m.put("subject", ce.subjectId());
                            m.put("serviceName", ce.serviceName());
                            m.put("httpMethod", verb);
                            m.put("path", ce.rawPath());
                            m.put("normalizedPath", ce.normalizedPath());
                            m.put("sourceFile", ce.sourceFile());
                            m.put("line", String.valueOf(ce.line()));
                            rows.add(Collections.unmodifiableMap(m));
                        }
                    }
                }
                case TYPES -> {
                    for (String fqcn : ctx.service().indexedClassNames(ctx.context())) {
                        ctx.service().classMetadata(ctx.context(), fqcn).ifPresent(cm -> {
                            var m = new TreeMap<String, String>();
                            m.put("subject", cm.fqcn());
                            m.put("className", cm.fqcn());
                            m.put("kind", cm.kind());
                            m.put("abstract", String.valueOf(cm.abstractType()));
                            rows.add(Collections.unmodifiableMap(m));
                        });
                    }
                }
                case METHODS -> {
                    for (String fqcn : ctx.service().indexedClassNames(ctx.context())) {
                        ctx.service().classMetadata(ctx.context(), fqcn).ifPresent(cm -> {
                            for (var mm : cm.methods()) {
                                var m = new TreeMap<String, String>();
                                m.put("subject", cm.fqcn() + "#" + mm.name());
                                m.put("ownerClass", cm.fqcn());
                                m.put("className", cm.fqcn());
                                m.put("name", mm.name());
                                m.put("paramCount", String.valueOf(mm.paramCount()));
                                m.put("returnType", mm.returnType().orElse("void"));
                                m.put("static", String.valueOf(mm.staticMember()));
                                rows.add(Collections.unmodifiableMap(m));
                            }
                        });
                    }
                }
                case FIELDS -> {
                    for (String fqcn : ctx.service().indexedClassNames(ctx.context())) {
                        ctx.service().classMetadata(ctx.context(), fqcn).ifPresent(cm -> {
                            for (var fm : cm.fields()) {
                                var m = new TreeMap<String, String>();
                                m.put("subject", cm.fqcn() + "#" + fm.name());
                                m.put("ownerClass", cm.fqcn());
                                m.put("className", cm.fqcn());
                                m.put("name", fm.name());
                                m.put("fieldType", fm.fieldType().orElse(""));
                                m.put("static", String.valueOf(fm.staticMember()));
                                rows.add(Collections.unmodifiableMap(m));
                            }
                        });
                    }
                }
                case ANNOTATIONS -> {
                    for (String fqcn : ctx.service().indexedClassNames(ctx.context())) {
                        ctx.service().classMetadata(ctx.context(), fqcn).ifPresent(cm -> {
                            for (var a : cm.annotations()) {
                                var m = new TreeMap<String, String>();
                                m.put("subject", cm.fqcn() + "@" + a.name());
                                m.put("ownerClass", cm.fqcn());
                                m.put("name", a.name());
                                m.put("spelling", a.rawSpelling());
                                m.put("sourceFile", a.sourceFile().orElse(""));
                                m.put("line", String.valueOf(a.line()));
                                rows.add(Collections.unmodifiableMap(m));
                            }
                        });
                    }
                }
                case CONFIG_KEYS -> {
                    var config = extractConfigCatalog(ctx);
                    for (var entry : config.rawKeyToFirstValue().entrySet()) {
                        var m = new TreeMap<String, String>();
                        m.put("subject", entry.getKey());
                        m.put("key", entry.getKey());
                        m.put("normalizedKey", normalizeRelaxedKey(entry.getKey()));
                        m.put("value", entry.getValue());
                        rows.add(Collections.unmodifiableMap(m));
                    }
                }
            }
            return List.copyOf(rows);
        });
    }

    // ─── Memoized Evidence Extractors (Single Graph Scan via ArchitectureQueryService) ───

    private List<XmlBeanDef> extractXmlBeans(EvalContext ctx) {
        return ctx.service().memoizedQuery("wp2.2.xml-beans", ctx.context(), () -> {
            var out = new ArrayList<XmlBeanDef>();
            for (var ev : ctx.service().evidenceByFamily(FAMILY_XML_BEAN)) {
                var m = ctx.service().parsedEvidence(ev);
                if (m.get("beans") instanceof List<?> beanList) {
                    String file = str(m, "sourceFile");
                    var topImports = stringList(m.get("imports"));
                    boolean attachedImports = false;
                    for (var item : beanList) {
                        if (item instanceof Map<?, ?> bm) {
                            out.add(parseXmlBeanMap(asStringMap(bm), file, attachedImports ? List.of() : topImports));
                            attachedImports = true;
                        }
                    }
                    if (!attachedImports && !topImports.isEmpty()) {
                        out.add(new XmlBeanDef("", "", "singleton", "", "", false, "", "", "",
                                List.of(), List.of(), topImports, file, parseIntSafe(m.get("line"), 0)));
                    }
                } else {
                    out.add(parseXmlBeanMap(m, str(m, "sourceFile"), stringList(m.get("imports"))));
                }
            }
            out.sort(Comparator.comparing(XmlBeanDef::sourceFile)
                    .thenComparingInt(XmlBeanDef::line)
                    .thenComparing(XmlBeanDef::beanId)
                    .thenComparing(XmlBeanDef::className));
            return List.copyOf(out);
        });
    }

    private static XmlBeanDef parseXmlBeanMap(Map<String, Object> m, String fallbackFile, List<String> extraImports) {
        String beanId = firstNonBlank(str(m, "beanId"), str(m, "id"), str(m, "name"));
        String className = str(m, "className");
        if (className.isBlank()) className = str(m, "class");
        String scope = firstNonBlank(str(m, "scope"), "singleton");
        String initMethod = firstNonBlank(str(m, "initMethod"), str(m, "init-method"));
        String destroyMethod = firstNonBlank(str(m, "destroyMethod"), str(m, "destroy-method"));
        boolean abstractBean = Boolean.TRUE.equals(m.get("abstract")) || "true".equalsIgnoreCase(str(m, "abstract"));
        String parent = str(m, "parent");
        String factoryBean = firstNonBlank(str(m, "factoryBean"), str(m, "factory-bean"));
        String factoryMethod = firstNonBlank(str(m, "factoryMethod"), str(m, "factory-method"));
        String sourceFile = firstNonBlank(str(m, "sourceFile"), fallbackFile);
        int line = parseIntSafe(m.get("line"), 0);

        var props = new ArrayList<XmlPropertyDef>();
        if (m.get("properties") instanceof List<?> propList) {
            for (var p : propList) {
                if (p instanceof Map<?, ?> pm) {
                    var sm = asStringMap(pm);
                    props.add(new XmlPropertyDef(str(sm, "name"), str(sm, "ref"), str(sm, "value")));
                } else if (p instanceof String ps && !ps.isBlank()) {
                    props.add(new XmlPropertyDef(ps, "", ""));
                }
            }
        }
        var ctorArgs = new ArrayList<XmlConstructorArgDef>();
        if (m.get("constructorArgs") instanceof List<?> argList) {
            int idx = 0;
            for (var a : argList) {
                if (a instanceof Map<?, ?> am) {
                    var sm = asStringMap(am);
                    int argIdx = parseIntSafe(sm.get("index"), idx);
                    ctorArgs.add(new XmlConstructorArgDef(argIdx, str(sm, "name"), str(sm, "type"),
                            str(sm, "ref"), str(sm, "value")));
                } else if (a instanceof String as) {
                    ctorArgs.add(new XmlConstructorArgDef(idx, "", "", "", as));
                }
                idx++;
            }
        }
        var imports = new TreeSet<String>(extraImports);
        imports.addAll(stringList(m.get("imports")));
        return new XmlBeanDef(beanId, className, scope, initMethod, destroyMethod, abstractBean,
                parent, factoryBean, factoryMethod, List.copyOf(props), List.copyOf(ctorArgs),
                List.copyOf(imports), sourceFile, line);
    }

    private record ImportResourceRef(String subject, String resourcePath, String sourceFile, int line) {}

    private List<ImportResourceRef> extractImportResources(EvalContext ctx, List<XmlBeanDef> xmlBeans) {
        return ctx.service().memoizedQuery("wp2.2.import-resources", ctx.context(), () -> {
            var out = new ArrayList<ImportResourceRef>();
            var seen = new HashSet<String>();
            for (XmlBeanDef bean : xmlBeans) {
                for (String imp : bean.imports()) {
                    if (imp.isBlank()) continue;
                    String subj = firstNonBlank(bean.sourceFile(), bean.beanId(), "xml") + "->" + imp;
                    if (seen.add(subj)) {
                        out.add(new ImportResourceRef(subj, imp, bean.sourceFile(), bean.line()));
                    }
                }
            }
            for (String fqcn : ctx.service().indexedClassNames(ctx.context())) {
                ctx.service().classMetadata(ctx.context(), fqcn).ifPresent(cm -> {
                    for (AnnotationMetadata ann : cm.annotations()) {
                        if (isAnnotationNamed(ann.name(), "ImportResource")) {
                            var attrs = parseAnnotationAttributes(ann.rawSpelling());
                            var locations = extractStringAttrValues(attrs, "locations", "value");
                            for (String loc : locations) {
                                String subj = cm.fqcn() + "@ImportResource(" + loc + ")";
                                if (seen.add(subj)) {
                                    out.add(new ImportResourceRef(subj, loc, ann.sourceFile().orElse(""), ann.line()));
                                }
                            }
                        }
                    }
                });
            }
            out.sort(Comparator.comparing(ImportResourceRef::subject));
            return List.copyOf(out);
        });
    }

    private record LifecycleMethodRef(
            String subject,
            String lifecycleKind,
            String methodName,
            String targetClass,
            String sourceFile,
            int line
    ) {}

    private List<LifecycleMethodRef> extractLifecycleMethodRefs(EvalContext ctx) {
        return ctx.service().memoizedQuery("wp2.2.lifecycle-refs", ctx.context(), () -> {
            var out = new ArrayList<LifecycleMethodRef>();
            // 1. XML init-method / destroy-method
            for (XmlBeanDef bean : extractXmlBeans(ctx)) {
                if (bean.className().isBlank()) continue;
                String baseSubj = firstNonBlank(bean.beanId(), bean.className());
                if (!bean.initMethod().isBlank()) {
                    out.add(new LifecycleMethodRef(baseSubj + "#init-method=" + bean.initMethod(),
                            "init-method", bean.initMethod(), bean.className(), bean.sourceFile(), bean.line()));
                }
                if (!bean.destroyMethod().isBlank()) {
                    out.add(new LifecycleMethodRef(baseSubj + "#destroy-method=" + bean.destroyMethod(),
                            "destroy-method", bean.destroyMethod(), bean.className(), bean.sourceFile(), bean.line()));
                }
            }
            // 2. Java @Bean(initMethod=..., destroyMethod=...)
            for (String fqcn : ctx.service().indexedClassNames(ctx.context())) {
                ctx.service().classMetadata(ctx.context(), fqcn).ifPresent(cm -> {
                    for (MemberMetadata mm : cm.methods()) {
                        for (AnnotationMetadata ann : mm.annotations()) {
                            if (!isAnnotationNamed(ann.name(), "Bean")) continue;
                            var attrs = parseAnnotationAttributes(ann.rawSpelling());
                            String initM = firstNonBlank(attrs.get("initMethod"), "");
                            String destroyM = firstNonBlank(attrs.get("destroyMethod"), "");
                            String retType = mm.returnType().orElse("");
                            if (retType.isBlank()) continue;
                            String baseSubj = cm.fqcn() + "#" + mm.name();
                            if (!initM.isBlank()) {
                                out.add(new LifecycleMethodRef(baseSubj + "@Bean(initMethod=" + initM + ")",
                                        "@Bean.initMethod", initM, retType, ann.sourceFile().orElse(""), ann.line()));
                            }
                            if (!destroyM.isBlank()) {
                                out.add(new LifecycleMethodRef(baseSubj + "@Bean(destroyMethod=" + destroyM + ")",
                                        "@Bean.destroyMethod", destroyM, retType, ann.sourceFile().orElse(""), ann.line()));
                            }
                        }
                    }
                });
            }
            // 3. spring.producer / spring.candidate evidence carrying explicit lifecycle metadata
            for (String fam : List.of("spring.producer", "spring.candidate")) {
                for (var ev : ctx.service().evidenceByFamily(fam)) {
                    var m = ctx.service().parsedEvidence(ev);
                    String initM = str(m, "initMethod");
                    String destroyM = str(m, "destroyMethod");
                    if (initM.isBlank() && destroyM.isBlank()) continue;
                    String target = firstNonBlank(str(m, "returnType"), str(m, "beanClass"), str(m, "className"));
                    if (target.isBlank()) continue;
                    String id = firstNonBlank(str(m, "beanId"), str(m, "beanName"), target);
                    String file = str(m, "sourceFile");
                    int line = parseIntSafe(m.get("line"), 0);
                    if (!initM.isBlank()) {
                        out.add(new LifecycleMethodRef(id + "#initMethod=" + initM, "@Bean.initMethod",
                                initM, target, file, line));
                    }
                    if (!destroyM.isBlank()) {
                        out.add(new LifecycleMethodRef(id + "#destroyMethod=" + destroyM, "@Bean.destroyMethod",
                                destroyM, target, file, line));
                    }
                }
            }
            out.sort(Comparator.comparing(LifecycleMethodRef::subject));
            return List.copyOf(out);
        });
    }

    private ConfigCatalog extractConfigCatalog(EvalContext ctx) {
        return ctx.service().memoizedQuery("wp2.2.config-catalog",
                List.of(ctx.context(), ctx.options().configData().map(r -> r.identity().value()).orElse("")), () -> {
                    var baseKeys = new TreeSet<String>();
                    var profileKeys = new TreeMap<String, Set<String>>();
                    var rawKeyToVal = new TreeMap<String, String>();
                    var rawByProfile = new TreeMap<String, Set<String>>();
                    var allProfiles = new TreeSet<String>();
                    var allNorm = new TreeSet<String>();
                    boolean hasAnyDoc = false;

                    // 1. Direct ConfigDataIngestion.Result passed via EvaluationOptions
                    if (ctx.options().configData().isPresent()) {
                        var res = ctx.options().configData().get();
                        allProfiles.addAll(res.activeProfiles());
                        for (var doc : res.documents()) {
                            hasAnyDoc = true;
                            ingestConfigDocument(doc.path(), doc.profile().orElse(""), doc.properties(),
                                    baseKeys, profileKeys, rawKeyToVal, rawByProfile, allProfiles, allNorm);
                        }
                        if (res.documents().isEmpty() && !res.properties().isEmpty()) {
                            hasAnyDoc = true;
                            ingestConfigDocument("config-overrides", "", res.properties(),
                                    baseKeys, profileKeys, rawKeyToVal, rawByProfile, allProfiles, allNorm);
                        }
                    }

                    // 2. Graph evidence families "spring.config-data" and "source.configuration"
                    for (String fam : List.of(FAMILY_CONFIG_DATA, FAMILY_SOURCE_CONFIG)) {
                        for (var ev : ctx.service().evidenceByFamily(fam)) {
                            var m = ctx.service().parsedEvidence(ev);
                            if (m.get("activeProfiles") instanceof List<?> ap) {
                                for (var p : ap) if (p instanceof String ps && !ps.isBlank()) allProfiles.add(ps.strip());
                            }
                            if (m.get("documents") instanceof List<?> docs && !docs.isEmpty()) {
                                for (var dObj : docs) {
                                    if (dObj instanceof Map<?, ?> dm) {
                                        hasAnyDoc = true;
                                        var sm = asStringMap(dm);
                                        String path = str(sm, "path");
                                        String prof = str(sm, "profile");
                                        var props = stringMap(sm.get("properties"));
                                        ingestConfigDocument(path, prof, props, baseKeys, profileKeys,
                                                rawKeyToVal, rawByProfile, allProfiles, allNorm);
                                    }
                                }
                            } else if (m.get("properties") instanceof Map<?, ?> propsObj) {
                                hasAnyDoc = true;
                                String path = firstNonBlank(str(m, "path"), str(m, "sourceFile"), fam);
                                String prof = str(m, "profile");
                                ingestConfigDocument(path, prof, stringMap(propsObj), baseKeys, profileKeys,
                                        rawKeyToVal, rawByProfile, allProfiles, allNorm);
                            }
                        }
                    }

                    // 3. Collect profile names declared in spring.space domains
                    for (var ev : ctx.service().evidenceByFamily("spring.space")) {
                        var m = ctx.service().parsedEvidence(ev);
                        if (m.get("domains") instanceof List<?> domains) {
                            for (var d : domains) {
                                if (d instanceof Map<?, ?> dm && dm.get("variable") instanceof Map<?, ?> vm
                                        && "PROFILE".equals(vm.get("kind")) && vm.get("name") instanceof String pName
                                        && !pName.isBlank()) {
                                    allProfiles.add(pName.strip());
                                }
                            }
                        }
                    }

                    if (hasAnyDoc && allProfiles.isEmpty()) {
                        allProfiles.add("default");
                    }
                    var frozenProfKeys = new TreeMap<String, Set<String>>();
                    profileKeys.forEach((k, v) -> frozenProfKeys.put(k, Collections.unmodifiableSet(v)));
                    var frozenRawProf = new TreeMap<String, Set<String>>();
                    rawByProfile.forEach((k, v) -> frozenRawProf.put(k, Collections.unmodifiableSet(v)));

                    return new ConfigCatalog(
                            Collections.unmodifiableSet(baseKeys),
                            Collections.unmodifiableMap(frozenProfKeys),
                            Collections.unmodifiableMap(rawKeyToVal),
                            Collections.unmodifiableMap(frozenRawProf),
                            Collections.unmodifiableSet(allProfiles),
                            Collections.unmodifiableSet(allNorm),
                            hasAnyDoc);
                });
    }

    private static void ingestConfigDocument(
            String path,
            String explicitProfile,
            Map<String, String> properties,
            Set<String> baseKeys,
            Map<String, Set<String>> profileKeys,
            Map<String, String> rawKeyToVal,
            Map<String, Set<String>> rawByProfile,
            Set<String> allProfiles,
            Set<String> allNorm
    ) {
        var docProfiles = new TreeSet<String>();
        if (explicitProfile != null && !explicitProfile.isBlank()) {
            docProfiles.add(explicitProfile.strip());
        } else {
            extractProfileFromFilename(path).ifPresent(docProfiles::add);
        }
        String activateProf = properties.getOrDefault("spring.config.activate.on-profile",
                properties.getOrDefault("spring.profiles", ""));
        if (!activateProf.isBlank()) {
            for (String token : activateProf.split("[,|&\\s]+")) {
                String t = token.strip();
                if (!t.isBlank() && !t.startsWith("!")) {
                    docProfiles.add(t);
                }
            }
        }
        String activeList = properties.getOrDefault("spring.profiles.active", "");
        if (!activeList.isBlank()) {
            for (String token : activeList.split("[,\\s]+")) {
                if (!token.isBlank()) allProfiles.add(token.strip());
            }
        }

        allProfiles.addAll(docProfiles);
        for (var e : properties.entrySet()) {
            String rawKey = e.getKey();
            if (rawKey == null || rawKey.isBlank()) continue;
            rawKeyToVal.putIfAbsent(rawKey, e.getValue() == null ? "" : e.getValue());
            String norm = normalizeRelaxedKey(rawKey);
            allNorm.add(norm);
            if (docProfiles.isEmpty()) {
                baseKeys.add(norm);
                rawByProfile.computeIfAbsent("default", k -> new TreeSet<>()).add(rawKey);
            } else {
                for (String p : docProfiles) {
                    profileKeys.computeIfAbsent(p, k -> new TreeSet<>()).add(norm);
                    rawByProfile.computeIfAbsent(p, k -> new TreeSet<>()).add(rawKey);
                }
            }
        }
    }

    private record PropertyRef(
            String subject,
            String sourceKind,
            String rawKey,
            boolean hasDefault,
            boolean matchIfMissing,
            boolean dynamicPlaceholder,
            Set<String> declaredProfiles,
            String sourceFile,
            int line
    ) {}

    private List<PropertyRef> extractPropertyReferences(EvalContext ctx) {
        return ctx.service().memoizedQuery("wp2.2.property-refs", ctx.context(), () -> {
            var out = new ArrayList<PropertyRef>();
            for (String fqcn : ctx.service().indexedClassNames(ctx.context())) {
                ctx.service().classMetadata(ctx.context(), fqcn).ifPresent(cm -> {
                    Set<String> classProfiles = extractProfilesFromAnnotations(cm.annotations());
                    collectPropertyRefsFromAnnotations(cm.fqcn(), cm.annotations(), classProfiles, out);
                    for (MemberMetadata fm : cm.fields()) {
                        Set<String> profiles = unionSets(classProfiles, extractProfilesFromAnnotations(fm.annotations()));
                        collectPropertyRefsFromAnnotations(cm.fqcn() + "#" + fm.name(), fm.annotations(), profiles, out);
                    }
                    for (MemberMetadata mm : cm.methods()) {
                        Set<String> profiles = unionSets(classProfiles, extractProfilesFromAnnotations(mm.annotations()));
                        collectPropertyRefsFromAnnotations(cm.fqcn() + "#" + mm.name(), mm.annotations(), profiles, out);
                    }
                    for (MemberMetadata ctor : cm.constructors()) {
                        Set<String> profiles = unionSets(classProfiles, extractProfilesFromAnnotations(ctor.annotations()));
                        collectPropertyRefsFromAnnotations(cm.fqcn() + "#<init>", ctor.annotations(), profiles, out);
                    }
                });
            }
            out.sort(Comparator.comparing(PropertyRef::subject).thenComparing(PropertyRef::rawKey));
            return List.copyOf(out);
        });
    }

    private static void collectPropertyRefsFromAnnotations(
            String ownerSubject,
            List<AnnotationMetadata> annotations,
            Set<String> profiles,
            List<PropertyRef> out
    ) {
        for (AnnotationMetadata ann : annotations) {
            if (isAnnotationNamed(ann.name(), "Value")) {
                var attrs = parseAnnotationAttributes(ann.rawSpelling());
                String expr = firstNonBlank(attrs.get("value"), "");
                if (expr.isBlank() || expr.contains("#{")) continue; // SpEL expression
                for (var ph : parseValuePlaceholders(expr)) {
                    out.add(new PropertyRef(
                            ownerSubject + "@Value(" + ph.key() + ")",
                            "VALUE",
                            ph.key(),
                            ph.hasDefault(),
                            false,
                            ph.dynamic(),
                            profiles,
                            ann.sourceFile().orElse(""),
                            ann.line()));
                }
            } else if (isAnnotationNamed(ann.name(), "ConditionalOnProperty")) {
                var attrs = parseAnnotationAttributes(ann.rawSpelling());
                String prefix = firstNonBlank(attrs.get("prefix"), "");
                boolean matchIfMissing = "true".equalsIgnoreCase(attrs.get("matchIfMissing"));
                var names = extractStringAttrValues(attrs, "name", "value");
                for (String name : names) {
                    String full = prefix.isBlank() ? name : (prefix.endsWith(".") ? prefix + name : prefix + "." + name);
                    out.add(new PropertyRef(
                            ownerSubject + "@ConditionalOnProperty(" + full + ")",
                            "CONDITIONAL_ON_PROPERTY",
                            full,
                            false,
                            matchIfMissing,
                            full.contains("${"),
                            profiles,
                            ann.sourceFile().orElse(""),
                            ann.line()));
                }
            }
        }
    }

    private record ConfigPropertiesBeanDef(
            String className,
            String prefix,
            boolean ignoreUnknownFields,
            String sourceFile,
            int line
    ) {}

    private List<ConfigPropertiesBeanDef> extractConfigPropertiesBeans(EvalContext ctx) {
        return ctx.service().memoizedQuery("wp2.2.config-properties-beans", ctx.context(), () -> {
            var out = new ArrayList<ConfigPropertiesBeanDef>();
            for (String fqcn : ctx.service().indexedClassNames(ctx.context())) {
                ctx.service().classMetadata(ctx.context(), fqcn).ifPresent(cm -> {
                    for (AnnotationMetadata ann : cm.annotations()) {
                        if (!isAnnotationNamed(ann.name(), "ConfigurationProperties")) continue;
                        var attrs = parseAnnotationAttributes(ann.rawSpelling());
                        String prefix = firstNonBlank(attrs.get("prefix"), attrs.get("value"), "");
                        boolean ignoreUnknown = !"false".equalsIgnoreCase(attrs.get("ignoreUnknownFields"));
                        out.add(new ConfigPropertiesBeanDef(cm.fqcn(), prefix, ignoreUnknown,
                                ann.sourceFile().orElse(""), ann.line()));
                    }
                    // Also handle @Bean @ConfigurationProperties(prefix="...") methods
                    for (MemberMetadata mm : cm.methods()) {
                        for (AnnotationMetadata ann : mm.annotations()) {
                            if (!isAnnotationNamed(ann.name(), "ConfigurationProperties")) continue;
                            if (mm.returnType().isEmpty()) continue;
                            var attrs = parseAnnotationAttributes(ann.rawSpelling());
                            String prefix = firstNonBlank(attrs.get("prefix"), attrs.get("value"), "");
                            boolean ignoreUnknown = !"false".equalsIgnoreCase(attrs.get("ignoreUnknownFields"));
                            out.add(new ConfigPropertiesBeanDef(mm.returnType().get(), prefix, ignoreUnknown,
                                    ann.sourceFile().orElse(""), ann.line()));
                        }
                    }
                });
            }
            out.sort(Comparator.comparing(ConfigPropertiesBeanDef::className).thenComparing(ConfigPropertiesBeanDef::prefix));
            return List.copyOf(out);
        });
    }

    private List<BeanLookupDef> extractBeanLookups(EvalContext ctx) {
        return ctx.service().memoizedQuery("wp2.2.bean-lookups", ctx.context(), () -> {
            var out = new ArrayList<BeanLookupDef>();
            var seen = new HashSet<String>();
            // 1. Pinned evidence family "spring.bean-lookup"
            for (var ev : ctx.service().evidenceByFamily(FAMILY_BEAN_LOOKUP)) {
                var m = ctx.service().parsedEvidence(ev);
                String beanName = str(m, "beanName");
                String beanType = str(m, "beanType");
                boolean dynamic = Boolean.TRUE.equals(m.get("dynamic")) || "true".equalsIgnoreCase(str(m, "dynamic"));
                String sourceFile = str(m, "sourceFile");
                int line = parseIntSafe(m.get("line"), 0);
                String subj = firstNonBlank(str(m, "subject"),
                        (sourceFile.isBlank() ? "getBean" : sourceFile + ":" + line) + "#"
                                + firstNonBlank(beanName, beanType, dynamic ? "<dynamic>" : "unknown"));
                if (seen.add(subj + "|" + beanName + "|" + beanType + "|" + dynamic)) {
                    out.add(new BeanLookupDef(subj, beanName, beanType, dynamic, sourceFile, line));
                }
            }
            // 2. RESOLVES_BEAN graph edges (pinned contract with WP2.1)
            for (var edge : ctx.service().edgesByKind(ctx.context(), EDGE_RESOLVES_BEAN)) {
                String beanName = "";
                String beanType = "";
                boolean dynamic = edge.target().state() == KnowledgeGraph.TargetState.UNRESOLVED;
                String sourceFile = "";
                int line = 0;
                for (var ref : edge.evidence()) {
                    var ev = ctx.service().graph().evidence(ref).orElse(null);
                    if (ev == null) continue;
                    var m = ctx.service().parsedEvidence(ev);
                    if (beanName.isBlank()) beanName = str(m, "beanName");
                    if (beanType.isBlank()) beanType = str(m, "beanType");
                    if (Boolean.TRUE.equals(m.get("dynamic"))) dynamic = true;
                    if (sourceFile.isBlank()) sourceFile = str(m, "sourceFile");
                    if (line == 0) line = parseIntSafe(m.get("line"), 0);
                }
                if (beanName.isBlank() && edge.target().state() == KnowledgeGraph.TargetState.RESOLVED) {
                    beanName = edge.target().identities().getFirst();
                }
                String subj = edge.identity();
                if (seen.add(subj + "|" + beanName + "|" + beanType + "|" + dynamic)) {
                    out.add(new BeanLookupDef(subj, beanName, beanType, dynamic, sourceFile, line));
                }
            }
            out.sort(Comparator.comparing(BeanLookupDef::subjectId));
            return List.copyOf(out);
        });
    }

    private Map<String, BeanTruthInfo> extractBeanTruthCatalog(EvalContext ctx) {
        return ctx.service().memoizedQuery("wp2.2.bean-truth-catalog", ctx.context(), () -> {
            var out = new TreeMap<String, BeanTruthInfo>();

            // Build formula DAG & spring.space variable label map once for SAT counterexample extraction
            var formulaNodes = new HashMap<String, Map<String, Object>>();
            for (var ev : ctx.service().evidenceByFamily("spring.formula")) {
                var m = ctx.service().parsedEvidence(ev);
                String id = str(m, "identity");
                if (!id.isBlank()) formulaNodes.put(id, m);
            }
            var varDigestToReadable = buildReadableVariableMap(ctx);

            // Map candidate/bean node IDs -> beanName & beanType
            var beanIdToName = new HashMap<String, String>();
            var beanIdToType = new HashMap<String, String>();
            for (var ev : ctx.service().evidenceByFamily("spring.candidate")) {
                var m = ctx.service().parsedEvidence(ev);
                String id = firstNonBlank(str(m, "identity"), str(m, "definition"));
                String name = "";
                if (m.get("declaredNameKey") instanceof Map<?, ?> nk && nk.get("primary") instanceof String p) {
                    name = p;
                }
                if (name.isBlank()) name = firstNonBlank(str(m, "beanName"), str(m, "name"));
                String type = firstNonBlank(str(m, "beanType"), str(m, "className"));
                if (type.isBlank() && m.get("exposedTypes") instanceof List<?> et && !et.isEmpty() && et.getFirst() instanceof String s) {
                    type = s;
                }
                if (!id.isBlank()) {
                    if (!name.isBlank()) beanIdToName.put(id, name);
                    if (!type.isBlank()) beanIdToType.put(id, type);
                }
            }
            for (var node : ctx.service().nodesByKind(ctx.context(), "SPRING_BEAN")) {
                for (var ref : node.evidence()) {
                    var ev = ctx.service().graph().evidence(ref).orElse(null);
                    if (ev == null) continue;
                    var m = ctx.service().parsedEvidence(ev);
                    String name = firstNonBlank(str(m, "beanName"), str(m, "beanId"), str(m, "name"));
                    String type = firstNonBlank(str(m, "beanType"), str(m, "className"));
                    if (!name.isBlank()) beanIdToName.putIfAbsent(node.identity(), name);
                    if (!type.isBlank()) beanIdToType.putIfAbsent(node.identity(), type);
                }
            }

            // 1. spring.symbolic-region (supports both full canonical DAG format & compact direct format)
            for (var ev : ctx.service().evidenceByFamily(FAMILY_SYMBOLIC_REGION)) {
                var m = ctx.service().parsedEvidence(ev);
                if (m.get("region") instanceof Map<?, ?> regionObj) {
                    var r = asStringMap(regionObj);
                    var fact = r.get("fact") instanceof Map<?, ?> fm ? asStringMap(fm) : Map.<String, Object>of();
                    String defId = firstNonBlank(str(fact, "definition"), str(m, "beanId"), str(m, "beanName"));
                    String beanName = firstNonBlank(str(fact, "beanName"), str(m, "beanName"), beanIdToName.getOrDefault(defId, ""));
                    String beanType = firstNonBlank(str(fact, "beanType"), str(m, "beanType"), beanIdToType.getOrDefault(defId, ""));
                    Classification cls = parseClassification(str(r, "classification"));
                    var counterWitness = new TreeMap<String, String>();
                    // Extract explicit counterWitness if present
                    extractWitnessMap(m.get("counterWitness"), counterWitness);
                    extractWitnessMap(r.get("counterWitness"), counterWitness);
                    // Otherwise solve falseRegion via SatConfigurationReasoner!
                    if (counterWitness.isEmpty() && cls == Classification.MAY) {
                        String falseFormulaId = str(r, "falseRegion");
                        if (!falseFormulaId.isBlank() && formulaNodes.containsKey(falseFormulaId)) {
                            solveCounterWitness(falseFormulaId, formulaNodes, varDigestToReadable, counterWitness);
                        }
                    }
                    String key = firstNonBlank(defId, beanName, beanType);
                    if (!key.isBlank()) {
                        var info = new BeanTruthInfo(key, beanName, beanType, cls, counterWitness,
                                Optional.ofNullable(str(m, "scope")).filter(s -> !s.isBlank()), TrustTier.SPRING_SAT);
                        indexBeanTruth(out, info);
                    }
                } else {
                    String defId = firstNonBlank(str(m, "beanId"), str(m, "definition"), str(m, "beanName"), str(m, "beanType"));
                    String beanName = firstNonBlank(str(m, "beanName"), str(m, "beanId"), beanIdToName.getOrDefault(defId, ""));
                    String beanType = firstNonBlank(str(m, "beanType"), str(m, "className"), beanIdToType.getOrDefault(defId, ""));
                    Classification cls = parseClassification(str(m, "classification"));
                    var counterWitness = new TreeMap<String, String>();
                    extractWitnessMap(m.get("counterWitness"), counterWitness);
                    extractWitnessMap(m.get("counterexampleWitness"), counterWitness);
                    if (!defId.isBlank()) {
                        var info = new BeanTruthInfo(defId, beanName, beanType, cls, counterWitness,
                                Optional.ofNullable(str(m, "scope")).filter(s -> !s.isBlank()), TrustTier.SPRING_SAT);
                        indexBeanTruth(out, info);
                    }
                }
            }

            // 2. spring.explicit-region
            for (var ev : ctx.service().evidenceByFamily(FAMILY_EXPLICIT_REGION)) {
                var m = ctx.service().parsedEvidence(ev);
                if (m.get("region") instanceof Map<?, ?> regionObj) {
                    var r = asStringMap(regionObj);
                    var fact = r.get("fact") instanceof Map<?, ?> fm ? asStringMap(fm) : Map.<String, Object>of();
                    String defId = firstNonBlank(str(fact, "definition"), str(m, "beanId"));
                    String beanName = firstNonBlank(str(fact, "beanName"), beanIdToName.getOrDefault(defId, ""));
                    String beanType = firstNonBlank(str(fact, "beanType"), beanIdToType.getOrDefault(defId, ""));
                    Classification cls = parseClassification(str(r, "classification"));
                    var counterWitness = new TreeMap<String, String>();
                    var falseWorlds = stringList(r.get("falseWorlds"));
                    if (!falseWorlds.isEmpty()) {
                        counterWitness.put("absentInWorld", falseWorlds.getFirst());
                    }
                    if (!defId.isBlank()) {
                        var info = new BeanTruthInfo(defId, beanName, beanType, cls, counterWitness,
                                Optional.empty(), TrustTier.SPRING_SAT);
                        if (!out.containsKey(defId)) {
                            indexBeanTruth(out, info);
                        }
                    }
                }
            }

            // 3. Unconditional XML beans (no profile/condition) register as MUST if not already covered by a truth region
            for (XmlBeanDef xml : extractXmlBeans(ctx)) {
                if (xml.beanId().isBlank() && xml.className().isBlank()) continue;
                String id = firstNonBlank(xml.beanId(), xml.className());
                if (!out.containsKey(id) && !out.containsKey("name:" + xml.beanId())) {
                    indexBeanTruth(out, new BeanTruthInfo(id, xml.beanId(), xml.className(),
                            Classification.MUST, Map.of(), Optional.of("xml-unconditional"), TrustTier.SOURCE_SYNTAX));
                }
            }
            return Collections.unmodifiableMap(out);
        });
    }

    private static void indexBeanTruth(Map<String, BeanTruthInfo> out, BeanTruthInfo info) {
        out.put(info.beanId(), info);
        if (!info.beanName().isBlank()) out.put("name:" + info.beanName(), info);
        if (!info.beanType().isBlank()) out.put("type:" + info.beanType(), info);
    }

    private static Optional<BeanTruthInfo> matchBeanTruth(BeanLookupDef lookup, Map<String, BeanTruthInfo> catalog) {
        if (!lookup.beanName().isBlank()) {
            BeanTruthInfo hit = catalog.get("name:" + lookup.beanName());
            if (hit != null) return Optional.of(hit);
            hit = catalog.get(lookup.beanName());
            if (hit != null) return Optional.of(hit);
        }
        if (!lookup.beanType().isBlank()) {
            BeanTruthInfo hit = catalog.get("type:" + lookup.beanType());
            if (hit != null) return Optional.of(hit);
            hit = catalog.get(lookup.beanType());
            if (hit != null) return Optional.of(hit);
        }
        return Optional.empty();
    }

    private List<FeignEndpointDef> extractFeignEndpoints(EvalContext ctx) {
        return ctx.service().memoizedQuery("wp2.2.feign-endpoints", List.of(ctx.context(), ctx.configDataKey()), () -> {
            var out = new ArrayList<FeignEndpointDef>();
            var seen = new HashSet<String>();
            var config = extractConfigCatalog(ctx);

            // 1. polyglot.syntax-fact with clientKind == FEIGN_CLIENT (JavaServiceSyntaxExtractor)
            for (var ev : ctx.service().evidenceByFamily("polyglot.syntax-fact")) {
                var m = ctx.service().parsedEvidence(ev);
                var fields = m.get("fields") instanceof Map<?, ?> fm ? asStringMap(fm) : m;
                var attrs = fields.get("attributes") instanceof Map<?, ?> am ? asStringMap(am) : Map.<String, Object>of();
                if (!"FEIGN_CLIENT".equals(str(attrs, "clientKind"))) continue;
                String method = firstNonBlank(str(attrs, "method"), "GET").toUpperCase(Locale.ROOT);
                String targetSvc = str(attrs, "targetService");
                String written = unquote(str(fields, "written"));
                boolean isSpel = "true".equalsIgnoreCase(str(attrs, "spelExpression")) || written.contains("#{");
                String resolved = isSpel ? "" : resolvePlaceholdersOrNull(written, config);
                boolean dynamic = isSpel || resolved == null;
                String normPath = dynamic ? "" : normalizeRoutePath(resolved);
                String file = firstNonBlank(str(fields, "path"), str(fields, "rawPath"));
                int line = parseIntSafe(fields.get("startLine"), parseIntSafe(fields.get("line"), 0));
                String subj = firstNonBlank(targetSvc, "feign") + ":" + method + " " + firstNonBlank(resolved, written);
                if (seen.add(subj + "|" + file + "|" + line)) {
                    out.add(new FeignEndpointDef(subj, targetSvc, method, firstNonBlank(resolved, written),
                            normPath, dynamic, file, line));
                }
            }

            // 2. @FeignClient annotated Java interfaces in ClassMetadata
            for (String fqcn : ctx.service().indexedClassNames(ctx.context())) {
                ctx.service().classMetadata(ctx.context(), fqcn).ifPresent(cm -> {
                    Optional<AnnotationMetadata> feignAnn = cm.annotations().stream()
                            .filter(a -> isAnnotationNamed(a.name(), "FeignClient"))
                            .findFirst();
                    if (feignAnn.isEmpty()) return;
                    var fAttrs = parseAnnotationAttributes(feignAnn.get().rawSpelling());
                    String targetSvc = firstNonBlank(fAttrs.get("name"), fAttrs.get("value"), "");
                    String classPath = firstNonBlank(fAttrs.get("path"), "");
                    if (classPath.isBlank()) {
                        cm.annotations().stream()
                                .filter(a -> isAnnotationNamed(a.name(), "RequestMapping"))
                                .findFirst()
                                .ifPresent(rm -> {});
                        for (var a : cm.annotations()) {
                            if (isAnnotationNamed(a.name(), "RequestMapping")) {
                                var rmAttrs = parseAnnotationAttributes(a.rawSpelling());
                                classPath = firstNonBlank(rmAttrs.get("path"), rmAttrs.get("value"), "");
                            }
                        }
                    }
                    String urlAttr = firstNonBlank(fAttrs.get("url"), "");
                    boolean classDynamic = targetSvc.contains("#{") || classPath.contains("#{") || urlAttr.contains("#{");
                    String resolvedSvc = classDynamic ? "" : resolvePlaceholdersOrNull(targetSvc, config);
                    if (resolvedSvc == null) {
                        classDynamic = true;
                        resolvedSvc = targetSvc;
                    }
                    for (MemberMetadata mm : cm.methods()) {
                        for (AnnotationMetadata mAnn : mm.annotations()) {
                            String verb = extractMappingVerb(mAnn);
                            if (verb == null) continue;
                            var mAttrs = parseAnnotationAttributes(mAnn.rawSpelling());
                            var paths = extractStringAttrValues(mAttrs, "path", "value");
                            if (paths.isEmpty()) paths = List.of("");
                            for (String mp : paths) {
                                String combined = combineRoutePaths(classPath, mp);
                                boolean dynamic = classDynamic || combined.contains("#{");
                                String resolvedPath = dynamic ? null : resolvePlaceholdersOrNull(combined, config);
                                if (resolvedPath == null) dynamic = true;
                                String norm = dynamic ? "" : normalizeRoutePath(resolvedPath);
                                String subj = cm.fqcn() + "#" + mm.name() + "[" + verb + " " + firstNonBlank(resolvedPath, combined) + "]";
                                if (seen.add(subj)) {
                                    out.add(new FeignEndpointDef(subj, resolvedSvc, verb,
                                            firstNonBlank(resolvedPath, combined), norm, dynamic,
                                            mAnn.sourceFile().orElse(feignAnn.get().sourceFile().orElse("")),
                                            mAnn.line() > 0 ? mAnn.line() : feignAnn.get().line()));
                                }
                            }
                        }
                    }
                });
            }

            // 3. Explicit spring.feign-endpoint evidence or CALLS_SERVICE edges carrying Feign route metadata
            for (var ev : ctx.service().evidenceByFamily("spring.feign-endpoint")) {
                var m = ctx.service().parsedEvidence(ev);
                String targetSvc = firstNonBlank(str(m, "targetService"), str(m, "service"));
                String verb = firstNonBlank(str(m, "httpMethod"), str(m, "method"), "GET").toUpperCase(Locale.ROOT);
                String rawPath = str(m, "path");
                boolean dynamic = rawPath.contains("#{") || Boolean.TRUE.equals(m.get("dynamic"));
                String resolved = dynamic ? null : resolvePlaceholdersOrNull(rawPath, config);
                if (resolved == null) dynamic = true;
                String norm = dynamic ? "" : normalizeRoutePath(resolved);
                String file = str(m, "sourceFile");
                int line = parseIntSafe(m.get("line"), 0);
                String subj = firstNonBlank(str(m, "subject"), targetSvc + ":" + verb + " " + rawPath);
                if (seen.add(subj)) {
                    out.add(new FeignEndpointDef(subj, targetSvc, verb, firstNonBlank(resolved, rawPath), norm, dynamic, file, line));
                }
            }

            out.sort(Comparator.comparing(FeignEndpointDef::subjectId));
            return List.copyOf(out);
        });
    }

    private List<ControllerEndpointDef> extractControllerEndpoints(EvalContext ctx) {
        return ctx.service().memoizedQuery("wp2.2.controller-endpoints", List.of(ctx.context(), ctx.configDataKey()), () -> {
            var out = new ArrayList<ControllerEndpointDef>();
            var seen = new HashSet<String>();
            var config = extractConfigCatalog(ctx);
            String defaultAppName = config.rawKeyToFirstValue().getOrDefault("spring.application.name", "");

            // 1. Canonical spring.endpoint-declaration evidence
            for (var ev : ctx.service().evidenceByFamily(FAMILY_ENDPOINT_DECLARATION)) {
                var m = ctx.service().parsedEvidence(ev);
                var route = m.get("route") instanceof Map<?, ?> rm ? asStringMap(rm) : m;
                String rawPath = str(route, "path");
                var methods = new TreeSet<String>();
                for (String verb : stringList(route.get("methods"))) {
                    if (!verb.isBlank()) methods.add(verb.toUpperCase(Locale.ROOT));
                }
                boolean implicitHead = Boolean.TRUE.equals(route.get("implicitHead")) || methods.contains("GET");
                String svc = firstNonBlank(str(m, "serviceName"), str(m, "service"), str(route, "service"),
                        str(m, "module"), defaultAppName);
                String handler = firstNonBlank(str(route, "handler"), str(route, "controller"), rawPath);
                String norm = normalizeRoutePath(rawPath);
                String key = svc + "|" + methods + "|" + norm;
                if (seen.add(key)) {
                    out.add(new ControllerEndpointDef(handler, svc, Collections.unmodifiableSet(methods),
                            implicitHead, rawPath, norm, str(m, "sourceFile"), parseIntSafe(m.get("line"), 0)));
                }
            }

            // 2. @RestController / @Controller classes in ClassMetadata
            for (String fqcn : ctx.service().indexedClassNames(ctx.context())) {
                ctx.service().classMetadata(ctx.context(), fqcn).ifPresent(cm -> {
                    boolean isController = cm.annotations().stream().anyMatch(
                            a -> isAnnotationNamed(a.name(), "RestController") || isAnnotationNamed(a.name(), "Controller"));
                    if (!isController) return;
                    String classPath = "";
                    var classMethods = new TreeSet<String>();
                    for (var a : cm.annotations()) {
                        if (isAnnotationNamed(a.name(), "RequestMapping")) {
                            var rmAttrs = parseAnnotationAttributes(a.rawSpelling());
                            classPath = firstNonBlank(rmAttrs.get("path"), rmAttrs.get("value"), "");
                            classMethods.addAll(extractMappingMethods(a));
                        }
                    }
                    String svc = defaultAppName;
                    for (MemberMetadata mm : cm.methods()) {
                        for (AnnotationMetadata mAnn : mm.annotations()) {
                            String verb = extractMappingVerb(mAnn);
                            if (verb == null && !isAnnotationNamed(mAnn.name(), "RequestMapping")) continue;
                            var mAttrs = parseAnnotationAttributes(mAnn.rawSpelling());
                            var methods = new TreeSet<>(classMethods);
                            if (verb != null && !verb.isBlank()) methods.add(verb);
                            methods.addAll(extractMappingMethods(mAnn));
                            var paths = extractStringAttrValues(mAttrs, "path", "value");
                            if (paths.isEmpty()) paths = List.of("");
                            for (String mp : paths) {
                                String combined = combineRoutePaths(classPath, mp);
                                String resolved = resolvePlaceholdersOrNull(combined, config);
                                String raw = resolved != null ? resolved : combined;
                                String norm = normalizeRoutePath(raw);
                                String key = svc + "|" + methods + "|" + norm;
                                if (seen.add(key)) {
                                    out.add(new ControllerEndpointDef(cm.fqcn() + "#" + mm.name(), svc,
                                            Collections.unmodifiableSet(methods), methods.contains("GET"),
                                            raw, norm, mAnn.sourceFile().orElse(""), mAnn.line()));
                                }
                            }
                        }
                    }
                });
            }

            // 3. polyglot.syntax-fact ROUTE_CANDIDATE
            for (var ev : ctx.service().evidenceByFamily("polyglot.syntax-fact")) {
                var m = ctx.service().parsedEvidence(ev);
                var fields = m.get("fields") instanceof Map<?, ?> fm ? asStringMap(fm) : m;
                if (!"ROUTE_CANDIDATE".equals(str(fields, "category"))) continue;
                var attrs = fields.get("attributes") instanceof Map<?, ?> am ? asStringMap(am) : Map.<String, Object>of();
                String rawPath = unquote(firstNonBlank(str(fields, "rawName"), str(fields, "written")));
                String verb = str(attrs, "method").toUpperCase(Locale.ROOT);
                var methods = verb.isBlank() || "UNKNOWN".equals(verb) ? Set.<String>of() : Set.of(verb);
                String svc = firstNonBlank(str(attrs, "service"), defaultAppName);
                String norm = normalizeRoutePath(rawPath);
                String key = svc + "|" + methods + "|" + norm;
                if (!norm.isBlank() && seen.add(key)) {
                    out.add(new ControllerEndpointDef(svc + ":" + norm, svc, methods, methods.contains("GET"),
                            rawPath, norm, str(fields, "path"), parseIntSafe(fields.get("startLine"), 0)));
                }
            }

            out.sort(Comparator.comparing(ControllerEndpointDef::serviceName)
                    .thenComparing(ControllerEndpointDef::normalizedPath)
                    .thenComparing(ControllerEndpointDef::subjectId));
            return List.copyOf(out);
        });
    }

    private Set<String> collectKnownServices(EvalContext ctx, List<ControllerEndpointDef> controllers) {
        var services = new TreeSet<String>();
        for (var c : controllers) {
            if (!c.serviceName().isBlank()) services.add(c.serviceName().toLowerCase(Locale.ROOT));
        }
        for (var ev : ctx.service().evidenceByFamily("architecture.service-identity")) {
            var m = ctx.service().parsedEvidence(ev);
            String svc = firstNonBlank(str(m, "serviceName"), str(m, "name"));
            if (!svc.isBlank()) services.add(svc.toLowerCase(Locale.ROOT));
        }
        var config = extractConfigCatalog(ctx);
        String appName = config.rawKeyToFirstValue().getOrDefault("spring.application.name", "");
        if (!appName.isBlank()) services.add(appName.toLowerCase(Locale.ROOT));
        return services;
    }

    // ─── Type Hierarchy, Setter, Lifecycle & Binding Helpers ───

    private enum HierarchySearchResult { FOUND, NOT_FOUND, LIBRARY_JAR_ABSENT }
    private enum BindingStatus { BOUND, UNBOUND, NO_SETTER, LIBRARY_JAR_ABSENT }
    private record ConfigBindingCheckResult(BindingStatus status, String detail) {}

    private static boolean matchesConstructorArgs(TypeDescriptor td, List<XmlConstructorArgDef> args) {
        if (td.constructors().isEmpty()) {
            // No explicit constructors declared -> Java default 0-arg constructor only
            return args.isEmpty();
        }
        for (ConstructorSignature ctor : td.constructors()) {
            if (ctor.parameterCount() != args.size()) continue;
            boolean typesCompatible = true;
            for (int i = 0; i < args.size() && i < ctor.parameterTypes().size(); i++) {
                String declaredArgType = args.get(i).type();
                if (!declaredArgType.isBlank()) {
                    String paramType = ctor.parameterTypes().get(i);
                    if (!typeNamesCompatible(declaredArgType, paramType)) {
                        typesCompatible = false;
                        break;
                    }
                }
            }
            if (typesCompatible) return true;
        }
        return false;
    }

    private HierarchySearchResult findSetterInHierarchy(
            EvalContext ctx, TypeDescriptor root, String propName, String setterName) {
        var queue = new ArrayDeque<TypeDescriptor>();
        var visited = new HashSet<String>();
        queue.add(root);
        visited.add(root.fqcn());
        boolean sawMissingSuper = false;
        int depth = 0;

        while (!queue.isEmpty() && depth++ < MAX_SUPERTYPE_DEPTH) {
            TypeDescriptor current = queue.poll();
            for (MethodSignature m : current.methods()) {
                if (!m.staticMethod() && m.name().equals(setterName) && m.parameterCount() == 1) {
                    return HierarchySearchResult.FOUND;
                }
            }
            // Support Lombok @Setter / @Data generating setters on declared fields
            if (hasLombokSetter(current)) {
                for (FieldSignature f : current.fields()) {
                    if (!f.staticField() && !f.finalField() && f.name().equals(propName)) {
                        return HierarchySearchResult.FOUND;
                    }
                }
            }
            for (String superType : current.superTypes()) {
                if ("java.lang.Object".equals(superType) || !visited.add(superType)) continue;
                TypeLookupOutcome sup = ctx.resolveExactType(superType);
                if (sup.status() == LookupStatus.FOUND) {
                    queue.add(sup.descriptor().orElseThrow());
                } else if (sup.status() == LookupStatus.LIBRARY_JAR_ABSENT) {
                    sawMissingSuper = true;
                }
            }
        }
        return sawMissingSuper ? HierarchySearchResult.LIBRARY_JAR_ABSENT : HierarchySearchResult.NOT_FOUND;
    }

    private HierarchySearchResult findZeroArgMethodInHierarchy(
            EvalContext ctx, TypeDescriptor root, String methodName) {
        var queue = new ArrayDeque<TypeDescriptor>();
        var visited = new HashSet<String>();
        queue.add(root);
        visited.add(root.fqcn());
        boolean sawMissingSuper = false;
        int depth = 0;

        while (!queue.isEmpty() && depth++ < MAX_SUPERTYPE_DEPTH) {
            TypeDescriptor current = queue.poll();
            for (MethodSignature m : current.methods()) {
                if (m.name().equals(methodName) && m.parameterCount() == 0) {
                    return HierarchySearchResult.FOUND;
                }
            }
            for (String superType : current.superTypes()) {
                if ("java.lang.Object".equals(superType) || !visited.add(superType)) continue;
                TypeLookupOutcome sup = ctx.resolveExactType(superType);
                if (sup.status() == LookupStatus.FOUND) {
                    queue.add(sup.descriptor().orElseThrow());
                } else if (sup.status() == LookupStatus.LIBRARY_JAR_ABSENT) {
                    sawMissingSuper = true;
                }
            }
        }
        return sawMissingSuper ? HierarchySearchResult.LIBRARY_JAR_ABSENT : HierarchySearchResult.NOT_FOUND;
    }

    private ConfigBindingCheckResult checkConfigPropertyBinding(
            EvalContext ctx, TypeDescriptor td, String remainderPath, int depth) {
        if (depth > MAX_NESTED_CONFIG_DEPTH) {
            return new ConfigBindingCheckResult(BindingStatus.BOUND, "max-depth");
        }
        // Strip list/map bracket notation like servers[0].host -> servers, host
        String[] segments = splitConfigSegments(remainderPath);
        if (segments.length == 0) {
            return new ConfigBindingCheckResult(BindingStatus.BOUND, "empty-remainder");
        }
        String head = stripBracketIndex(segments[0]);
        String tail = segments.length > 1
                ? String.join(".", Arrays.copyOfRange(segments, 1, segments.length))
                : "";

        boolean constructorBound = td.recordType() || hasAnnotation(td, "ConstructorBinding");
        boolean lombokSetter = hasLombokSetter(td);

        // 1. Try matching a setter method (setXxx)
        for (MethodSignature m : td.methods()) {
            if (!m.staticMethod() && m.parameterCount() == 1 && m.name().startsWith("set") && m.name().length() > 3) {
                String propFromSetter = Character.toLowerCase(m.name().charAt(3)) + m.name().substring(4);
                if (relaxedSegmentMatch(propFromSetter, head)) {
                    if (tail.isEmpty()) {
                        return new ConfigBindingCheckResult(BindingStatus.BOUND, "setter:" + m.name());
                    }
                    String nestedType = m.parameterTypes().isEmpty() ? "" : m.parameterTypes().getFirst();
                    return checkNestedConfigType(ctx, nestedType, tail, depth + 1);
                }
            }
        }

        // 2. Try matching a declared field (check mutability / setter / constructor binding / nested Map/Collection)
        for (FieldSignature f : td.fields()) {
            if (f.staticField()) continue;
            if (relaxedSegmentMatch(f.name(), head)) {
                String cleanFieldType = stripGenericsAndArrays(f.type());
                boolean isMapOrCollection = MAP_LIKE_TYPES.contains(cleanFieldType)
                        || COLLECTION_LIKE_TYPES.contains(cleanFieldType);
                boolean hasGetter = hasGetterFor(td, f.name());
                if (tail.isEmpty()) {
                    if (constructorBound || lombokSetter || (isMapOrCollection && hasGetter)) {
                        return new ConfigBindingCheckResult(BindingStatus.BOUND, "field:" + f.name());
                    }
                    return new ConfigBindingCheckResult(BindingStatus.NO_SETTER,
                            "field '" + f.name() + "' lacks setter '" + toSetterName(f.name()) + "'");
                }
                if (MAP_LIKE_TYPES.contains(cleanFieldType)) {
                    return new ConfigBindingCheckResult(BindingStatus.BOUND, "map:" + f.name());
                }
                if (COLLECTION_LIKE_TYPES.contains(cleanFieldType)) {
                    String elemType = extractFirstGenericArg(f.type());
                    if (elemType.isBlank() || SCALAR_OR_JDK_LEAF_TYPES.contains(elemType)) {
                        return new ConfigBindingCheckResult(BindingStatus.BOUND, "collection:" + f.name());
                    }
                    return checkNestedConfigType(ctx, elemType, tail, depth + 1);
                }
                if (constructorBound || lombokSetter || hasGetter) {
                    return checkNestedConfigType(ctx, f.type(), tail, depth + 1);
                }
                return new ConfigBindingCheckResult(BindingStatus.NO_SETTER,
                        "nested field '" + f.name() + "' lacks getter/setter");
            }
        }

        // 3. Check superTypes
        for (String superType : td.superTypes()) {
            if ("java.lang.Object".equals(superType)) continue;
            TypeLookupOutcome sup = ctx.resolveExactType(superType);
            if (sup.status() == LookupStatus.FOUND) {
                var sub = checkConfigPropertyBinding(ctx, sup.descriptor().orElseThrow(), remainderPath, depth + 1);
                if (sub.status() != BindingStatus.UNBOUND) return sub;
            } else if (sup.status() == LookupStatus.LIBRARY_JAR_ABSENT) {
                return new ConfigBindingCheckResult(BindingStatus.LIBRARY_JAR_ABSENT,
                        "Supertype JAR absent: " + superType);
            }
        }

        // 4. Support UPPER_SNAKE multi-word remainder without dots (e.g. "MAX_POOL_SIZE" or "retry_max_attempts")
        if (segments.length == 1 && (head.contains("_") || head.contains("-"))) {
            String collapsed = normalizeRelaxedKey(head);
            for (FieldSignature f : td.fields()) {
                if (f.staticField()) continue;
                String fNorm = normalizeRelaxedKey(f.name());
                if (collapsed.startsWith(fNorm + "-")) {
                    String rest = collapsed.substring(fNorm.length() + 1);
                    String cleanFieldType = stripGenericsAndArrays(f.type());
                    if (MAP_LIKE_TYPES.contains(cleanFieldType)) {
                        return new ConfigBindingCheckResult(BindingStatus.BOUND, "map:" + f.name());
                    }
                    return checkNestedConfigType(ctx, f.type(), rest, depth + 1);
                }
            }
        }

        return new ConfigBindingCheckResult(BindingStatus.UNBOUND, "no matching property for '" + remainderPath + "'");
    }

    private ConfigBindingCheckResult checkNestedConfigType(
            EvalContext ctx, String rawType, String tail, int depth) {
        String clean = stripGenericsAndArrays(rawType);
        if (clean.isBlank() || MAP_LIKE_TYPES.contains(clean)) {
            return new ConfigBindingCheckResult(BindingStatus.BOUND, "map-or-dynamic");
        }
        if (SCALAR_OR_JDK_LEAF_TYPES.contains(clean)) {
            return new ConfigBindingCheckResult(BindingStatus.UNBOUND,
                    "scalar type '" + clean + "' has no nested property '" + tail + "'");
        }
        TypeLookupOutcome nested = ctx.resolveExactType(clean);
        if (nested.status() == LookupStatus.LIBRARY_JAR_ABSENT) {
            return new ConfigBindingCheckResult(BindingStatus.LIBRARY_JAR_ABSENT,
                    "Nested property type JAR absent: " + clean);
        }
        if (nested.status() == LookupStatus.NOT_FOUND) {
            return new ConfigBindingCheckResult(BindingStatus.UNBOUND, "unknown nested type " + clean);
        }
        return checkConfigPropertyBinding(ctx, nested.descriptor().orElseThrow(), tail, depth);
    }

    // ─── Relaxed Binding & Route Normalization Utilities (Public for Reuse/Testing) ───

    /**
     * Normalizes a Spring Boot configuration key to canonical lowercase kebab-case using
     * {@link BytecodeAutoConfigScanner#normalizePropertyKey(String)}.
     */
    public static String normalizeRelaxedKey(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return BytecodeAutoConfigScanner.normalizePropertyKey(raw.strip());
    }

    /** Returns true when two property keys match under Spring Boot relaxed binding (kebab/camel/UPPER_SNAKE). */
    public static boolean relaxedEquivalent(String a, String b) {
        if (a == null || b == null) return false;
        String na = normalizeRelaxedKey(a);
        String nb = normalizeRelaxedKey(b);
        if (na.equals(nb)) return true;
        return canonicalAlphaNumSegments(na).equals(canonicalAlphaNumSegments(nb))
                || collapseDelimiters(na).equals(collapseDelimiters(nb));
    }

    /** Normalizes an HTTP route path: strips scheme/host, normalizes slashes, and canonicalizes {@code {var}} templates. */
    public static String normalizeRoutePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "";
        String p = rawPath.strip();
        int schemeIdx = p.indexOf("://");
        if (schemeIdx >= 0) {
            int pathStart = p.indexOf('/', schemeIdx + 3);
            p = pathStart >= 0 ? p.substring(pathStart) : "/";
        }
        int queryIdx = p.indexOf('?');
        if (queryIdx >= 0) p = p.substring(0, queryIdx);
        int fragIdx = p.indexOf('#');
        if (fragIdx >= 0) p = p.substring(0, fragIdx);
        // Canonicalize Spring MVC path variables {id}, {orderId:[0-9]+} -> {*}
        var sb = new StringBuilder(p.length());
        int braceDepth = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '{') {
                if (braceDepth == 0) sb.append("{*}");
                braceDepth++;
            } else if (c == '}') {
                if (braceDepth > 0) braceDepth--;
            } else if (braceDepth == 0) {
                sb.append(c);
            }
        }
        String collapsed = sb.toString().replaceAll("/+", "/");
        if (!collapsed.startsWith("/")) collapsed = "/" + collapsed;
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
    }

    // ─── Internal Helpers ───

    private static boolean containsRelaxedKey(Set<String> normalizedSet, String normTarget) {
        if (normalizedSet.contains(normTarget)) return true;
        String segForm = canonicalAlphaNumSegments(normTarget);
        String flatForm = collapseDelimiters(normTarget);
        for (String candidate : normalizedSet) {
            if (canonicalAlphaNumSegments(candidate).equals(segForm)
                    || collapseDelimiters(candidate).equals(flatForm)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> stripRelaxedPrefix(String rawKey, String normPrefix) {
        String normKey = normalizeRelaxedKey(rawKey);
        if (normKey.startsWith(normPrefix + ".")) {
            return Optional.of(normKey.substring(normPrefix.length() + 1));
        }
        // Env-var UPPER_SNAKE style: APP_DATASOURCE_MAX_POOL_SIZE -> normKey "app-datasource-max-pool-size"
        String flatPrefix = normPrefix.replace('.', '-');
        if (normKey.startsWith(flatPrefix + "-") && !normKey.contains(".")) {
            return Optional.of(normKey.substring(flatPrefix.length() + 1));
        }
        return Optional.empty();
    }

    private static String canonicalAlphaNumSegments(String key) {
        var sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '-' || c == '_') continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String collapseDelimiters(String key) {
        var sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '-' || c == '_' || c == '.') continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static boolean relaxedSegmentMatch(String javaProp, String configSegment) {
        if (javaProp.equalsIgnoreCase(configSegment)) return true;
        return canonicalAlphaNumSegments(normalizeRelaxedKey(javaProp))
                .equals(canonicalAlphaNumSegments(normalizeRelaxedKey(configSegment)));
    }

    private static String[] splitConfigSegments(String remainder) {
        if (remainder == null || remainder.isBlank()) return new String[0];
        return remainder.split("\\.");
    }

    private static String stripBracketIndex(String seg) {
        int idx = seg.indexOf('[');
        return idx >= 0 ? seg.substring(0, idx) : seg;
    }

    private static String toSetterName(String prop) {
        return "set" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
    }

    private static boolean hasGetterFor(TypeDescriptor td, String fieldName) {
        String suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String get = "get" + suffix;
        String is = "is" + suffix;
        for (MethodSignature m : td.methods()) {
            if (!m.staticMethod() && m.parameterCount() == 0 && (m.name().equals(get) || m.name().equals(is))) {
                return true;
            }
        }
        return hasAnnotation(td, "Getter") || hasAnnotation(td, "Data") || hasAnnotation(td, "Value");
    }

    private static boolean hasLombokSetter(TypeDescriptor td) {
        return hasAnnotation(td, "Setter") || hasAnnotation(td, "Data");
    }

    private static boolean hasAnnotation(TypeDescriptor td, String simpleName) {
        for (AnnotationInfo a : td.annotations()) {
            if (isAnnotationNamed(a.name(), simpleName)) return true;
        }
        return false;
    }

    private static boolean isAnnotationNamed(String name, String simpleName) {
        if (name == null || name.isBlank()) return false;
        String clean = name.startsWith("@") ? name.substring(1) : name;
        return clean.equals(simpleName) || clean.endsWith("." + simpleName);
    }

    private static boolean isIgnoredLifecycleMethod(String name) {
        if (name == null || name.isBlank()) return true;
        String s = name.strip();
        return "(inferred)".equals(s) || "AbstractBeanDefinition.INFER_METHOD".equals(s);
    }

    private static TypeDescriptor toTypeDescriptor(ClassMetadata cm) {
        var ctors = cm.constructors().stream()
                .map(c -> new ConstructorSignature(c.paramCount(), c.paramTypes(), true))
                .toList();
        var methods = cm.methods().stream()
                .map(m -> new MethodSignature(m.name(), m.paramCount(), m.paramTypes(),
                        m.returnType().orElse("void"), m.staticMember(), true))
                .toList();
        var fields = cm.fields().stream()
                .map(f -> new FieldSignature(f.name(), f.fieldType().orElse("java.lang.Object"),
                        f.staticMember(), false))
                .toList();
        var anns = cm.annotations().stream()
                .map(a -> new AnnotationInfo(a.name(), parseAnnotationAttributes(a.rawSpelling()), a.rawSpelling()))
                .toList();
        return new TypeDescriptor(
                cm.fqcn(),
                cm.abstractType(),
                "INTERFACE".equalsIgnoreCase(cm.kind()),
                "RECORD".equalsIgnoreCase(cm.kind()),
                TrustTier.COMPILER,
                cm.superTypes(),
                ctors,
                methods,
                fields,
                anns);
    }

    private static boolean typeNamesCompatible(String expected, String actual) {
        String e = stripGenericsAndArrays(expected.strip());
        String a = stripGenericsAndArrays(actual.strip());
        if (e.equals(a)) return true;
        String eSimple = e.contains(".") ? e.substring(e.lastIndexOf('.') + 1) : e;
        String aSimple = a.contains(".") ? a.substring(a.lastIndexOf('.') + 1) : a;
        return eSimple.equals(aSimple);
    }

    private static String stripGenericsAndArrays(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        int lt = s.indexOf('<');
        if (lt >= 0) s = s.substring(0, lt).strip();
        while (s.endsWith("[]")) s = s.substring(0, s.length() - 2).strip();
        return s;
    }

    private static String extractFirstGenericArg(String raw) {
        if (raw == null) return "";
        int lt = raw.indexOf('<');
        int gt = raw.lastIndexOf('>');
        if (lt < 0 || gt <= lt) return "";
        String inside = raw.substring(lt + 1, gt).strip();
        int comma = inside.indexOf(',');
        return stripGenericsAndArrays(comma >= 0 ? inside.substring(0, comma) : inside);
    }

    private Set<String> collectRepositoryResourcePaths(EvalContext ctx) {
        return ctx.service().memoizedQuery("wp2.2.repo-resources", ctx.context(), () -> {
            var paths = new TreeSet<String>();
            for (String fam : List.of("source.document", "source.bytes", "polyglot.inventory-entry")) {
                for (var ev : ctx.service().evidenceByFamily(fam)) {
                    var m = ctx.service().parsedEvidence(ev);
                    String p = str(m, "path");
                    if (p.isBlank() && m.get("document") instanceof Map<?, ?> dm) {
                        p = str(asStringMap(dm), "path");
                    }
                    if (!p.isBlank()) paths.add(p.replace('\\', '/'));
                }
            }
            for (var bean : extractXmlBeans(ctx)) {
                if (!bean.sourceFile().isBlank()) paths.add(bean.sourceFile().replace('\\', '/'));
            }
            return Collections.unmodifiableSet(paths);
        });
    }

    private static String normalizeResourcePath(String raw) {
        String s = raw.strip().replace('\\', '/');
        if (s.startsWith("classpath*:")) s = s.substring("classpath*:".length());
        else if (s.startsWith("classpath:")) s = s.substring("classpath:".length());
        else if (s.startsWith("file:")) s = s.substring("file:".length());
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    private static boolean matchesCapturedResource(String normalizedResource, Set<String> capturedPaths) {
        if (capturedPaths.contains(normalizedResource)) return true;
        String suffix = "/" + normalizedResource;
        for (String p : capturedPaths) {
            if (p.equals(normalizedResource) || p.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String resolvePlaceholdersOrNull(String value, ConfigCatalog config) {
        if (value == null) return "";
        String cur = value;
        for (int depth = 0; cur.contains("${") && depth < 16; depth++) {
            int start = cur.indexOf("${");
            int end = cur.indexOf('}', start + 2);
            if (end < 0) return null;
            String inside = cur.substring(start + 2, end);
            if (inside.contains("${")) return null;
            int colon = inside.indexOf(':');
            String key = colon >= 0 ? inside.substring(0, colon) : inside;
            String defVal = colon >= 0 ? inside.substring(colon + 1) : null;
            String replacement = lookupConfigValue(config, key);
            if (replacement == null) replacement = defVal;
            if (replacement == null) return null;
            cur = cur.substring(0, start) + replacement + cur.substring(end + 1);
        }
        return cur.contains("${") ? null : cur;
    }

    private static String lookupConfigValue(ConfigCatalog config, String rawKey) {
        String direct = config.rawKeyToFirstValue().get(rawKey);
        if (direct != null) return direct;
        String norm = normalizeRelaxedKey(rawKey);
        for (var e : config.rawKeyToFirstValue().entrySet()) {
            if (relaxedEquivalent(e.getKey(), norm)) return e.getValue();
        }
        return null;
    }

    private record ParsedPlaceholder(String key, boolean hasDefault, boolean dynamic) {}

    private static List<ParsedPlaceholder> parseValuePlaceholders(String expr) {
        var out = new ArrayList<ParsedPlaceholder>();
        int i = 0;
        while (i < expr.length()) {
            int start = expr.indexOf("${", i);
            if (start < 0) break;
            int depth = 1;
            int j = start + 2;
            boolean nested = false;
            while (j < expr.length() && depth > 0) {
                if (expr.charAt(j) == '$' && j + 1 < expr.length() && expr.charAt(j + 1) == '{') {
                    depth++;
                    nested = true;
                    j += 2;
                } else if (expr.charAt(j) == '}') {
                    depth--;
                    j++;
                } else {
                    j++;
                }
            }
            if (depth != 0) {
                out.add(new ParsedPlaceholder(expr.substring(start + 2), false, true));
                break;
            }
            String body = expr.substring(start + 2, j - 1);
            int colon = body.indexOf(':');
            String key = (colon >= 0 ? body.substring(0, colon) : body).strip();
            out.add(new ParsedPlaceholder(key, colon >= 0, nested || key.isBlank()));
            i = j;
        }
        return out;
    }

    private static Optional<String> extractProfileFromFilename(String path) {
        if (path == null || path.isBlank()) return Optional.empty();
        String p = path.replace('\\', '/');
        String name = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return Optional.empty();
        if (name.startsWith("application-") && dot > 12) return Optional.of(name.substring(12, dot));
        if (name.startsWith("bootstrap-") && dot > 10) return Optional.of(name.substring(10, dot));
        return Optional.empty();
    }

    private static Set<String> extractProfilesFromAnnotations(List<AnnotationMetadata> anns) {
        var profiles = new TreeSet<String>();
        for (AnnotationMetadata ann : anns) {
            if (!isAnnotationNamed(ann.name(), "Profile")) continue;
            var attrs = parseAnnotationAttributes(ann.rawSpelling());
            for (String p : extractStringAttrValues(attrs, "value")) {
                if (!p.isBlank() && !p.startsWith("!")) profiles.add(p.strip());
            }
        }
        return profiles;
    }

    private static Set<String> determineApplicableProfiles(Set<String> declaredProfiles, Set<String> allKnownProfiles) {
        if (!declaredProfiles.isEmpty()) {
            return declaredProfiles;
        }
        return allKnownProfiles.isEmpty() ? Set.of("default") : allKnownProfiles;
    }

    private static boolean routesMatch(String feignNorm, String controllerNorm) {
        if (feignNorm.equals(controllerNorm)) return true;
        String[] fSegs = feignNorm.split("/");
        String[] cSegs = controllerNorm.split("/");
        if (fSegs.length != cSegs.length) return false;
        for (int i = 0; i < fSegs.length; i++) {
            String f = fSegs[i], c = cSegs[i];
            if (f.equals(c) || "{*}".equals(f) || "{*}".equals(c) || "*".equals(c)) continue;
            return false;
        }
        return true;
    }

    private static boolean verbMatches(String feignVerb, ControllerEndpointDef controller) {
        if (feignVerb == null || feignVerb.isBlank()) return true;
        if (controller.httpMethods().isEmpty()) return true; // @RequestMapping with no method accepts all verbs
        String fv = feignVerb.toUpperCase(Locale.ROOT);
        if (controller.httpMethods().contains(fv)) return true;
        return "HEAD".equals(fv) && controller.implicitHead();
    }

    private static String combineRoutePaths(String parent, String child) {
        String p = parent == null ? "" : parent.strip();
        String c = child == null ? "" : child.strip();
        if (p.isEmpty()) return c;
        if (c.isEmpty()) return p;
        if (p.endsWith("/") && c.startsWith("/")) return p + c.substring(1);
        if (!p.endsWith("/") && !c.startsWith("/")) return p + "/" + c;
        return p + c;
    }

    private static String extractMappingVerb(AnnotationMetadata ann) {
        for (String sc : HTTP_MAPPING_SHORTCUTS) {
            if (isAnnotationNamed(ann.name(), sc)) {
                return SHORTCUT_TO_VERB.get(sc);
            }
        }
        if (isAnnotationNamed(ann.name(), "RequestMapping")) {
            var methods = extractMappingMethods(ann);
            return methods.isEmpty() ? "GET" : methods.iterator().next();
        }
        return null;
    }

    private static Set<String> extractMappingMethods(AnnotationMetadata ann) {
        var out = new TreeSet<String>();
        for (String sc : HTTP_MAPPING_SHORTCUTS) {
            if (isAnnotationNamed(ann.name(), sc)) {
                out.add(SHORTCUT_TO_VERB.get(sc));
                return out;
            }
        }
        var attrs = parseAnnotationAttributes(ann.rawSpelling());
        String raw = attrs.get("method");
        if (raw != null && !raw.isBlank()) {
            for (String token : raw.split("[,\\s{}\\[\\]]+")) {
                String t = token.strip();
                if (t.isBlank()) continue;
                int dot = t.lastIndexOf('.');
                String verb = (dot >= 0 ? t.substring(dot + 1) : t).toUpperCase(Locale.ROOT);
                if (Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE").contains(verb)) {
                    out.add(verb);
                }
            }
        }
        return out;
    }

    /** Lightweight deterministic annotation attribute parser for raw Java annotation spellings. */
    static Map<String, String> parseAnnotationAttributes(String rawSpelling) {
        var out = new TreeMap<String, String>();
        if (rawSpelling == null || rawSpelling.isBlank()) return out;
        int open = rawSpelling.indexOf('(');
        int close = rawSpelling.lastIndexOf(')');
        if (open < 0 || close <= open) return out;
        String body = rawSpelling.substring(open + 1, close).strip();
        if (body.isEmpty()) return out;

        var parts = splitTopLevelComma(body);
        for (String part : parts) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) continue;
            int eq = findTopLevelEquals(trimmed);
            if (eq < 0) {
                out.put("value", unquote(trimmed));
            } else {
                String k = trimmed.substring(0, eq).strip();
                String v = trimmed.substring(eq + 1).strip();
                out.put(k, unquote(v));
            }
        }
        return out;
    }

    private static List<String> extractStringAttrValues(Map<String, String> attrs, String... keys) {
        for (String k : keys) {
            String raw = attrs.get(k);
            if (raw == null || raw.isBlank()) continue;
            String trimmed = raw.strip();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                String inner = trimmed.substring(1, trimmed.length() - 1).strip();
                var list = new ArrayList<String>();
                for (String item : splitTopLevelComma(inner)) {
                    String u = unquote(item.strip());
                    if (!u.isBlank()) list.add(u);
                }
                return list;
            }
            return List.of(unquote(trimmed));
        }
        return List.of();
    }

    private static List<String> splitTopLevelComma(String s) {
        var parts = new ArrayList<String>();
        var cur = new StringBuilder();
        boolean inQuote = false;
        int brace = 0, paren = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
                cur.append(c);
            } else if (!inQuote) {
                if (c == '{') { brace++; cur.append(c); }
                else if (c == '}') { brace = Math.max(0, brace - 1); cur.append(c); }
                else if (c == '(') { paren++; cur.append(c); }
                else if (c == ')') { paren = Math.max(0, paren - 1); cur.append(c); }
                else if (c == ',' && brace == 0 && paren == 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) parts.add(cur.toString());
        return parts;
    }

    private static int findTopLevelEquals(String s) {
        boolean inQuote = false;
        int brace = 0, paren = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inQuote = !inQuote;
            else if (!inQuote) {
                if (c == '{') brace++;
                else if (c == '}') brace = Math.max(0, brace - 1);
                else if (c == '(') paren++;
                else if (c == ')') paren = Math.max(0, paren - 1);
                else if (c == '=' && brace == 0 && paren == 0) return i;
            }
        }
        return -1;
    }

    private static String unquote(String s) {
        if (s == null) return "";
        String t = s.strip();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static Map<String, String> buildReadableVariableMap(EvalContext ctx) {
        var map = new HashMap<String, String>();
        for (var ev : ctx.service().evidenceByFamily("spring.space")) {
            var space = ctx.service().parsedEvidence(ev);
            if (!(space.get("domains") instanceof List<?> domains)) continue;
            for (var row : domains) {
                if (!(row instanceof Map<?, ?> domain)
                        || !(domain.get("variable") instanceof Map<?, ?> variable)
                        || !(variable.get("kind") instanceof String kindStr)
                        || !(variable.get("name") instanceof String name)
                        || !(domain.get("values") instanceof List<?> values)) continue;
                FiniteDomain.Kind kind;
                try { kind = FiniteDomain.Kind.valueOf(kindStr); } catch (IllegalArgumentException ignored) { continue; }
                var domainVar = new FiniteDomain.Variable(kind, name);
                for (var raw : values) {
                    if (!(raw instanceof Map<?, ?> val)) continue;
                    FiniteDomain.Value fdVal = null;
                    String readable = null;
                    if ("BOOLEAN".equals(val.get("kind")) && val.get("bool") instanceof Boolean flag) {
                        fdVal = FiniteDomain.Value.bool(flag);
                        readable = kind.name().toLowerCase(Locale.ROOT) + ":" + name + "=" + flag;
                    } else if ("EXACT".equals(val.get("kind")) && val.get("exact") instanceof String txt) {
                        fdVal = FiniteDomain.Value.exact(txt);
                        readable = kind.name().toLowerCase(Locale.ROOT) + ":" + name + "=" + txt;
                    } else if ("MISSING".equals(val.get("kind"))) {
                        fdVal = FiniteDomain.Value.missing();
                        readable = kind.name().toLowerCase(Locale.ROOT) + ":" + name + "=<missing>";
                    } else if ("WEB_MODE".equals(val.get("kind")) && val.get("webMode") instanceof String wm) {
                        try {
                            fdVal = FiniteDomain.Value.web(FiniteDomain.WebMode.valueOf(wm));
                            readable = kind.name().toLowerCase(Locale.ROOT) + ":" + name + "=" + wm;
                        } catch (IllegalArgumentException ignored) {}
                    }
                    if (fdVal != null && readable != null) {
                        String key = SymbolicTruthRegions.baselineChoiceVariable(domainVar, fdVal);
                        map.put(key, readable);
                    }
                }
            }
        }
        return map;
    }

    private static void solveCounterWitness(
            String formulaRootId,
            Map<String, Map<String, Object>> formulaNodes,
            Map<String, String> readableVars,
            Map<String, String> outWitness
    ) {
        try {
            var built = new HashMap<String, SymbolicConfiguration.Formula>();
            var formula = reconstructFormula(formulaRootId, formulaNodes, built, new HashSet<>(), 0);
            if (formula == null) return;
            var answer = SatConfigurationReasoner.INSTANCE.solve(new SymbolicConfiguration.Query(
                    formula, new SymbolicConfiguration.Limits(50_000, 200_000, 100_000, 128)));
            if (answer.status() == ConfigurationReasoner.Satisfiability.SATISFIABLE) {
                for (var entry : new TreeMap<>(answer.witness()).entrySet()) {
                    String readable = readableVars.get(entry.getKey());
                    if (readable != null) {
                        if (Boolean.TRUE.equals(entry.getValue())) {
                            int eq = readable.indexOf('=');
                            if (eq > 0) {
                                outWitness.put(readable.substring(0, eq), readable.substring(eq + 1));
                            } else {
                                outWitness.put(readable, "true");
                            }
                        }
                    } else {
                        outWitness.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Malformed or budgeted formula stays safely empty without crashing evaluation
        }
    }

    private static SymbolicConfiguration.Formula reconstructFormula(
            String id,
            Map<String, Map<String, Object>> nodes,
            Map<String, SymbolicConfiguration.Formula> memo,
            Set<String> visiting,
            int depth
    ) {
        if (depth > 128) return null;
        if (memo.containsKey(id)) return memo.get(id);
        var node = nodes.get(id);
        if (node == null || !visiting.add(id)) return null;
        var opStr = str(node, "operator");
        SymbolicConfiguration.Operator op;
        try { op = SymbolicConfiguration.Operator.valueOf(opStr); } catch (IllegalArgumentException e) { return null; }
        var childrenIds = stringList(node.get("children"));
        var children = new ArrayList<SymbolicConfiguration.Formula>(childrenIds.size());
        for (String cid : childrenIds) {
            var cf = reconstructFormula(cid, nodes, memo, visiting, depth + 1);
            if (cf == null) return null;
            children.add(cf);
        }
        SymbolicConfiguration.Formula f = switch (op) {
            case TRUE -> SymbolicConfiguration.TRUE;
            case FALSE -> SymbolicConfiguration.FALSE;
            case VARIABLE -> SymbolicConfiguration.variable(str(node, "variable"));
            case NOT -> children.size() == 1 ? SymbolicConfiguration.not(children.getFirst()) : null;
            case AND -> SymbolicConfiguration.and(children);
            case OR -> SymbolicConfiguration.or(children);
        };
        visiting.remove(id);
        if (f != null) memo.put(id, f);
        return f;
    }

    private static void extractWitnessMap(Object raw, Map<String, String> out) {
        if (raw instanceof Map<?, ?> wm) {
            for (var e : wm.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
    }

    private static boolean hasIncompleteClasspathGap(ArchitectureQueryService service, String context) {
        for (var node : service.nodesByKind(context, "CAPABILITY_GAP")) {
            for (var ref : node.evidence()) {
                var ev = service.graph().evidence(ref).orElse(null);
                if (ev == null) continue;
                var m = service.parsedEvidence(ev);
                if (INCOMPLETE_CLASSPATH_GAP_REASONS.contains(str(m, "reasonCode"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static EvidenceContext resolveEvidenceContext(KnowledgeGraph graph, String contextId) {
        if (contextId != null && !contextId.isBlank()) {
            var match = graph.contexts().stream().filter(c -> c.identity().equals(contextId)).findFirst();
            if (match.isPresent()) {
                return new EvidenceContext(graph.snapshot(), match.get().analysis());
            }
        }
        return EvidenceContext.forSnapshot(graph.snapshot());
    }

    private static RuleOutcome buildOutcome(
            String ruleId, int checked, List<Finding> findings, Set<CapabilityGapRecord> gaps) {
        boolean anyMust = findings.stream().anyMatch(f -> f.classification() == Classification.MUST);
        boolean anyMay = findings.stream().anyMatch(f -> f.classification() == Classification.MAY);
        boolean anyUnknown = findings.stream().anyMatch(f -> f.classification() == Classification.UNKNOWN) || !gaps.isEmpty();
        Classification cls = anyMust ? Classification.MUST
                : anyMay ? Classification.MAY
                : anyUnknown ? Classification.UNKNOWN
                : Classification.NEVER;
        AssessmentStatus status = gaps.isEmpty() ? AssessmentStatus.COMPLETE : AssessmentStatus.PARTIAL;
        return new RuleOutcome(ruleId, status, cls, checked, findings, List.copyOf(gaps));
    }

    private static Classification aggregateClassification(List<RuleOutcome> outcomes, Set<CapabilityGapRecord> gaps) {
        if (outcomes.stream().anyMatch(r -> r.classification() == Classification.MUST)) return Classification.MUST;
        if (outcomes.stream().anyMatch(r -> r.classification() == Classification.MAY)) return Classification.MAY;
        if (!gaps.isEmpty() || outcomes.stream().anyMatch(r -> r.classification() == Classification.UNKNOWN)) {
            return Classification.UNKNOWN;
        }
        return Classification.NEVER;
    }

    private static Classification parseClassification(String s) {
        if (s == null || s.isBlank()) return Classification.UNKNOWN;
        try { return Classification.valueOf(s.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return Classification.UNKNOWN; }
    }

    private static Set<String> unionSets(Set<String> a, Set<String> b) {
        var s = new TreeSet<>(a);
        s.addAll(b);
        return s;
    }

    private static Map<String, Object> asStringMap(Map<?, ?> raw) {
        var out = new LinkedHashMap<String, Object>();
        raw.forEach((k, v) -> { if (k != null) out.put(String.valueOf(k), v); });
        return out;
    }

    private static Map<String, String> stringMap(Object raw) {
        var out = new TreeMap<String, String>();
        if (raw instanceof Map<?, ?> rm) {
            rm.forEach((k, v) -> {
                if (k != null) out.put(String.valueOf(k), v == null ? "" : String.valueOf(v));
            });
        }
        return out;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        var out = new ArrayList<String>(list.size());
        for (var item : list) {
            if (item != null) out.add(String.valueOf(item));
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s ? s.strip() : (v == null ? "" : String.valueOf(v).strip());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.strip();
        }
        return "";
    }

    private static int parseIntSafe(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.strip()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
