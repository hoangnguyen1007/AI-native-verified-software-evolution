package com.evolution.analysis.spring.condition;

import com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability;
import com.evolution.analysis.spring.truth.IncrementalCdclReasoner;
import com.evolution.analysis.spring.truth.SymbolicConfiguration;
import com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula;
import com.evolution.analysis.spring.truth.SymbolicConfiguration.Limits;
import com.evolution.analysis.spring.truth.SymbolicConfiguration.Query;
import com.evolution.analysis.spring.universal.BytecodeAutoConfigScanner;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;

/**
 * Production 3-valued static SAT lowering and truth-region reasoner over passive
 * {@link BytecodeAutoConfigScanner} starter auto-configuration bytecode models.
 *
 * <p>Upheld platform invariants:
 * <ul>
 *   <li><b>100% Passive ({@code --build=off}):</b> never invokes {@code Class.forName}, never loads
 *       target classes via a {@code ClassLoader}, and never executes target lifecycles. Classpath
 *       class availability is indexed purely from ZIP/JAR entries, directory {@code .class} files,
 *       and standard JDK {@link ModuleFinder#ofSystem()} module metadata.</li>
 *   <li><b>Sound 3-Valued Fail-Closed Reasoning:</b> unmodeled custom {@code @Conditional(...)}
 *       predicates, {@code @ConditionalOnResource}, annotation-filtered bean conditions, and
 *       ambiguous multi-candidate {@code @ConditionalOnSingleCandidate} sites are represented as
 *       {@link Truth#UNKNOWN}, ensuring zero False-MUST / False-NEVER verdicts.</li>
 *   <li><b>Exact Finite One-Hot Property Domains:</b> each property key is encoded over its
 *       mutually exclusive finite partition {@code {MISSING, distinct havingValues..., OTHER}}
 *       honoring {@code matchIfMissing} and Spring Boot's empty-{@code havingValue} semantics so
 *       mutually exclusive auto-configurations (e.g. {@code redis} vs {@code caffeine}) cannot be
 *       simultaneously satisfied.</li>
 * </ul>
 */
public final class StarterAutoConfigSatReasoner {

    public static final String MISSING_VALUE_TOKEN = "<MISSING>";
    public static final String OTHER_VALUE_TOKEN = "__other__";
    private static final String SCHEDULED_ANNOTATION_PROCESSOR_BEAN =
            "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
    private static final String ANN_ENABLE_SCHEDULING =
            "org.springframework.scheduling.annotation.EnableScheduling";

    private static final Set<String> INFRA_OR_WRAPPER_PARAMS = Set.of(
            "void", "boolean", "byte", "short", "int", "long", "char", "float", "double", "java.lang.Object",
            "org.springframework.beans.factory.ObjectProvider",
            "org.springframework.beans.factory.ObjectFactory",
            "java.util.List",
            "java.util.Collection",
            "java.util.Set",
            "java.util.Map",
            "java.util.Optional",
            "java.util.stream.Stream",
            "org.springframework.beans.factory.BeanFactory",
            "org.springframework.beans.factory.ListableBeanFactory",
            "org.springframework.beans.factory.HierarchicalBeanFactory",
            "org.springframework.beans.factory.config.ConfigurableBeanFactory",
            "org.springframework.beans.factory.config.ConfigurableListableBeanFactory",
            "org.springframework.beans.factory.support.BeanDefinitionRegistry",
            "org.springframework.context.ApplicationContext",
            "org.springframework.context.ConfigurableApplicationContext",
            "org.springframework.core.env.Environment",
            "org.springframework.core.env.ConfigurableEnvironment",
            "org.springframework.core.io.ResourceLoader",
            "org.springframework.core.io.support.ResourcePatternResolver",
            "org.springframework.context.ApplicationEventPublisher",
            "org.springframework.core.metrics.ApplicationStartup",
            "java.lang.ClassLoader");

    public record Dimension(boolean isProfile, String key, List<Optional<String>> values) {
        public Dimension {
            key = Objects.requireNonNull(key, "key");
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }

        public static Dimension profile(String name) {
            return new Dimension(true, name, List.of(Optional.of("false"), Optional.of("true")));
        }

        public static Dimension property(String name, List<Optional<String>> values) {
            return new Dimension(false, name, values);
        }
    }

    public record UserBeanDescriptor(
            String beanName,
            String returnTypeFqn,
            boolean primary,
            boolean fallback) {
        public UserBeanDescriptor {
            beanName = Objects.requireNonNull(beanName, "beanName");
            returnTypeFqn = Objects.requireNonNull(returnTypeFqn, "returnTypeFqn");
        }
    }

    public record UserBeanInventory(
            List<UserBeanDescriptor> beans,
            Set<String> syntheticBeanNames) {
        public UserBeanInventory {
            beans = List.copyOf(beans);
            syntheticBeanNames = Collections.unmodifiableSet(new TreeSet<>(syntheticBeanNames));
        }

        public static UserBeanInventory empty() {
            return new UserBeanInventory(List.of(), Set.of());
        }
    }

    public record Truth(Formula yes, Formula no) {
        public static final Truth T = new Truth(SymbolicConfiguration.TRUE, SymbolicConfiguration.FALSE);
        public static final Truth F = new Truth(SymbolicConfiguration.FALSE, SymbolicConfiguration.TRUE);
        public static final Truth U = new Truth(SymbolicConfiguration.FALSE, SymbolicConfiguration.FALSE);

        public Truth {
            Objects.requireNonNull(yes, "yes");
            Objects.requireNonNull(no, "no");
        }

        public Formula unknown() {
            return not(or(yes, no));
        }

        public Truth andWith(Truth other) {
            return new Truth(and(yes, other.yes), or(no, other.no));
        }

        public Truth orWith(Truth other) {
            return new Truth(or(yes, other.yes), and(no, other.no));
        }

        public Truth negate() {
            return new Truth(no, yes);
        }
    }

    public record CohortEvaluationResult(
            Map<String, String> beanClassifications,
            int scopedConfigCount,
            int scopedBeanCount) {
        public CohortEvaluationResult {
            beanClassifications = Collections.unmodifiableMap(new LinkedHashMap<>(beanClassifications));
        }
    }

    public record EncodedStarterSat(
            Formula formula,
            Map<String, Formula> beanActiveVars,
            Map<String, List<String>> propertyDomains,
            int exogenousVarCount,
            int configAndBeanVarCount,
            int constraintCount) {
        public EncodedStarterSat {
            Objects.requireNonNull(formula, "formula");
            beanActiveVars = Collections.unmodifiableMap(new LinkedHashMap<>(beanActiveVars));
            propertyDomains = Collections.unmodifiableMap(new TreeMap<>(propertyDomains));
        }
    }

    private record CandidateTruth(Truth truth, boolean primary, boolean fallback) {}

    private StarterAutoConfigSatReasoner() {}

    /**
     * Passively indexes available class FQNs from JAR files, class directories, and optionally
     * the JDK system modules (via {@link ModuleFinder#ofSystem()}), without invoking
     * {@code Class.forName} or probing any {@code ClassLoader}.
     */
    public static Set<String> indexClasspathPassively(List<Path> classpathEntries, boolean includeJdkModules)
            throws IOException {
        Objects.requireNonNull(classpathEntries, "classpathEntries");
        var classes = new HashSet<String>(16_384);

        if (includeJdkModules) {
            for (ModuleReference ref : ModuleFinder.ofSystem().findAll()) {
                try (ModuleReader reader = ref.open()) {
                    try (var stream = reader.list()) {
                        stream.forEach(entry -> {
                            if (entry.endsWith(".class")
                                    && !entry.equals("module-info.class")
                                    && !entry.endsWith("/package-info.class")) {
                                classes.add(entry.substring(0, entry.length() - 6).replace('/', '.'));
                            }
                        });
                    }
                } catch (IOException ignored) {
                    // Best-effort module scan; missing module remains absent passively
                }
            }
        }

        for (Path entry : classpathEntries) {
            if (entry == null || !Files.exists(entry)) {
                continue;
            }
            if (Files.isDirectory(entry)) {
                try (var walk = Files.walk(entry)) {
                    walk.filter(Files::isRegularFile).forEach(file -> {
                        String rel = entry.relativize(file).toString().replace('\\', '/');
                        if (rel.endsWith(".class")
                                && !rel.equals("module-info.class")
                                && !rel.endsWith("/package-info.class")) {
                            classes.add(rel.substring(0, rel.length() - 6).replace('/', '.'));
                        }
                    });
                }
            } else if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")) {
                try (var zip = new ZipFile(entry.toFile(), StandardCharsets.UTF_8)) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry ze = entries.nextElement();
                        if (ze.isDirectory()) {
                            continue;
                        }
                        String name = ze.getName();
                        if (name.endsWith(".class")
                                && !name.equals("module-info.class")
                                && !name.endsWith("/package-info.class")
                                && !name.startsWith("META-INF/versions/")) {
                            classes.add(name.substring(0, name.length() - 6).replace('/', '.'));
                        }
                    }
                } catch (IOException ignored) {
                    // Corrupt/unreadable archive entry stays absent passively
                }
            }
        }
        return Collections.unmodifiableSet(classes);
    }

    /**
     * Passively extracts user {@code @Bean} method declarations and standard framework registrar
     * markers (such as {@code @EnableScheduling}) directly from compiled {@code .class} bytes via ASM.
     */
    public static UserBeanInventory extractUserBeansPassively(List<byte[]> userConfigClassBytes) {
        Objects.requireNonNull(userConfigClassBytes, "userConfigClassBytes");
        if (userConfigClassBytes.isEmpty()) {
            return UserBeanInventory.empty();
        }
        var beans = new ArrayList<UserBeanDescriptor>();
        var syntheticNames = new TreeSet<String>();

        for (byte[] bytes : userConfigClassBytes) {
            var reader = new ClassReader(bytes);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    String annFqn = Type.getType(descriptor).getClassName();
                    if (ANN_ENABLE_SCHEDULING.equals(annFqn)) {
                        // Spring's @EnableScheduling imports SchedulingConfiguration which registers this processor bean
                        syntheticNames.add(SCHEDULED_ANNOTATION_PROCESSOR_BEAN);
                    }
                    return null;
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
                        final List<String> names = new ArrayList<>();

                        @Override
                        public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                            String annFqn = Type.getType(annDesc).getClassName();
                            if ("org.springframework.context.annotation.Bean".equals(annFqn)) {
                                isBean = true;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public AnnotationVisitor visitArray(String attrName) {
                                        if ("name".equals(attrName) || "value".equals(attrName)) {
                                            return new AnnotationVisitor(Opcodes.ASM9) {
                                                @Override
                                                public void visit(String ignored, Object value) {
                                                    if (value instanceof String s && !s.isBlank()) {
                                                        names.add(s);
                                                    }
                                                }
                                            };
                                        }
                                        return null;
                                    }
                                };
                            }
                            if ("org.springframework.context.annotation.Primary".equals(annFqn)) {
                                primary = true;
                            } else if ("org.springframework.context.annotation.Fallback".equals(annFqn)) {
                                fallback = true;
                            }
                            return null;
                        }

                        @Override
                        public void visitEnd() {
                            if (!isBean) {
                                return;
                            }
                            String returnType = Type.getReturnType(descriptor).getClassName();
                            if (names.isEmpty()) {
                                names.add(name);
                            }
                            for (String bn : names) {
                                beans.add(new UserBeanDescriptor(bn, returnType, primary, fallback));
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return new UserBeanInventory(beans, syntheticNames);
    }

    /**
     * Evaluates a cohort of scanned Spring Boot {@code @AutoConfiguration} classes over a finite
     * configuration space, a passively indexed closed classpath, and passively extracted user beans,
     * returning 3-valued sound {@code MUST}/{@code MAY}/{@code NEVER}/{@code UNKNOWN} classifications
     * solved by {@link IncrementalCdclReasoner}.
     */
    public static CohortEvaluationResult evaluateAutoConfigCohort(
            BytecodeAutoConfigScanner.ScanResult scan,
            List<String> rootAutoConfigFqns,
            List<Dimension> dimensions,
            Set<String> passiveClasspathFqns,
            UserBeanInventory userBeans,
            Map<String, Set<String>> supertypesByType,
            List<String> beanNamesToClassify) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(rootAutoConfigFqns, "rootAutoConfigFqns");
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(passiveClasspathFqns, "passiveClasspathFqns");
        Objects.requireNonNull(userBeans, "userBeans");
        Objects.requireNonNull(supertypesByType, "supertypesByType");
        Objects.requireNonNull(beanNamesToClassify, "beanNamesToClassify");

        var configByFqn = new HashMap<String, BytecodeAutoConfigScanner.StarterConfiguration>(scan.configurations().size() * 2);
        for (var cfg : scan.configurations()) {
            configByFqn.put(cfg.classFqn(), cfg);
        }

        var rootSet = new LinkedHashSet<>(rootAutoConfigFqns);
        var scopeMap = new LinkedHashMap<String, BytecodeAutoConfigScanner.StarterConfiguration>();
        var importedBy = new HashMap<String, Set<String>>();
        var queue = new ArrayDeque<String>(rootSet);
        while (!queue.isEmpty()) {
            String fqn = queue.poll();
            var cfg = configByFqn.get(fqn);
            if (cfg == null || scopeMap.containsKey(fqn)) {
                continue;
            }
            scopeMap.put(fqn, cfg);
            for (String imp : cfg.importedClasses()) {
                if (configByFqn.containsKey(imp)) {
                    importedBy.computeIfAbsent(imp, ignored -> new LinkedHashSet<>()).add(fqn);
                    queue.add(imp);
                }
            }
            for (String nested : cfg.nestedConfigurationClasses()) {
                if (configByFqn.containsKey(nested)) {
                    importedBy.computeIfAbsent(nested, ignored -> new LinkedHashSet<>()).add(fqn);
                    queue.add(nested);
                }
            }
        }

        var orderedConfigs = topologicalOrderConfigs(scopeMap, importedBy);

        var domainAxioms = new ArrayList<Formula>();
        var propDimMap = new HashMap<String, Dimension>();
        var profileDims = new LinkedHashSet<String>();
        for (var d : dimensions) {
            if (d.isProfile()) {
                profileDims.add(d.key());
            } else {
                String normKey = BytecodeAutoConfigScanner.normalizePropertyKey(d.key());
                propDimMap.put(normKey, d);
                var choices = new ArrayList<Formula>(d.values().size());
                for (int i = 0; i < d.values().size(); i++) {
                    choices.add(variable("dom:" + normKey + ":" + i));
                }
                domainAxioms.add(or(choices));
                for (int a = 0; a < choices.size(); a++) {
                    for (int b = a + 1; b < choices.size(); b++) {
                        domainAxioms.add(or(not(choices.get(a)), not(choices.get(b))));
                    }
                }
            }
        }
        Formula domainFormula = domainAxioms.isEmpty() ? SymbolicConfiguration.TRUE : and(domainAxioms);

        var cfgTruth = new HashMap<String, Truth>();
        var priorBeans = new ArrayList<BytecodeAutoConfigScanner.StarterBean>();
        var beanTruth = new HashMap<String, Truth>();
        var beansByName = new HashMap<String, List<BytecodeAutoConfigScanner.StarterBean>>();

        for (var cfg : orderedConfigs) {
            Truth guard = Truth.T;
            if (!rootSet.contains(cfg.classFqn())) {
                var parents = importedBy.getOrDefault(cfg.classFqn(), Set.of());
                if (!parents.isEmpty()) {
                    Truth anyParent = Truth.F;
                    for (String p : parents) {
                        anyParent = anyParent.orWith(cfgTruth.getOrDefault(p, Truth.F));
                    }
                    guard = guard.andWith(anyParent);
                }
            }
            for (var cond : cfg.conditions()) {
                guard = guard.andWith(evalCondition3Valued(
                        cond, null, passiveClasspathFqns, propDimMap, profileDims,
                        userBeans, supertypesByType, priorBeans, beanTruth));
            }
            cfgTruth.put(cfg.classFqn(), guard);

            for (var bean : cfg.beans()) {
                Truth bTruth = guard;
                for (var cond : bean.conditions()) {
                    bTruth = bTruth.andWith(evalCondition3Valued(
                            cond, bean.returnTypeFqn(), passiveClasspathFqns, propDimMap, profileDims,
                            userBeans, supertypesByType, priorBeans, beanTruth));
                }
                String bId = ConeOfInfluenceSlicer.beanNodeId(bean);
                beanTruth.put(bId, bTruth);
                priorBeans.add(bean);
                for (String name : bean.beanNames()) {
                    beansByName.computeIfAbsent(name, ignored -> new ArrayList<>()).add(bean);
                }
            }
        }

        var classifications = new LinkedHashMap<String, String>();
        int totalBeans = priorBeans.size();
        for (String beanName : beanNamesToClassify) {
            var candidates = beansByName.getOrDefault(beanName, List.of());
            if (candidates.isEmpty()) {
                continue;
            }
            Truth combined = Truth.F;
            for (var cand : candidates) {
                combined = combined.orWith(beanTruth.getOrDefault(ConeOfInfluenceSlicer.beanNodeId(cand), Truth.U));
            }
            classifications.put(beanName, classify3Valued(domainFormula, combined));
        }
        return new CohortEvaluationResult(classifications, orderedConfigs.size(), totalBeans);
    }

    /**
     * Encodes a scanned Spring Boot starter graph (either full JAR or a COI-sliced subgraph) into a
     * genuine non-tautological Boolean SAT formula with exact finite one-hot property domains
     * (honoring {@code havingValue} mutual exclusion and {@code matchIfMissing}), classpath literals,
     * {@code @Profile} expressions, {@code @ConditionalOnBean}/{@code @ConditionalOnMissingBean}/
     * {@code @ConditionalOnSingleCandidate}, enclosing/importing config guards, topological
     * {@code @AutoConfigureAfter}/{@code @AutoConfigureBefore} registration order, and {@code @Bean}
     * parameter dependencies.
     *
     * <p>When {@code allowedConfigs} and {@code allowedBeans} are non-null (COI-sliced mode), global
     * topological ordering and property domains are preserved from {@code scan} so exogenous variables
     * and registration precedence remain 1:1 comparable against the full formula for formal
     * equisatisfiability / XOR-equivalence proofs.
     */
    public static EncodedStarterSat encodeStarterGraphSat(
            BytecodeAutoConfigScanner.ScanResult scan,
            Set<String> allowedConfigs,
            Set<String> allowedBeans,
            Map<String, Set<String>> supertypesByType,
            String internalVarPrefix) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(supertypesByType, "supertypesByType");
        String prefix = (internalVarPrefix == null || internalVarPrefix.isBlank()) ? "" : internalVarPrefix + ":";

        var fullMap = new LinkedHashMap<String, BytecodeAutoConfigScanner.StarterConfiguration>();
        var fullImportedBy = new HashMap<String, Set<String>>();
        for (var cfg : scan.configurations()) {
            fullMap.put(cfg.classFqn(), cfg);
        }
        for (var cfg : fullMap.values()) {
            for (String imp : cfg.importedClasses()) {
                if (fullMap.containsKey(imp)) {
                    fullImportedBy.computeIfAbsent(imp, ignored -> new LinkedHashSet<>()).add(cfg.classFqn());
                }
            }
            for (String nested : cfg.nestedConfigurationClasses()) {
                if (fullMap.containsKey(nested)) {
                    fullImportedBy.computeIfAbsent(nested, ignored -> new LinkedHashSet<>()).add(cfg.classFqn());
                }
            }
            int lastDollar = cfg.classFqn().lastIndexOf('$');
            if (lastDollar > 0) {
                String outer = cfg.classFqn().substring(0, lastDollar);
                if (fullMap.containsKey(outer)) {
                    fullImportedBy.computeIfAbsent(cfg.classFqn(), ignored -> new LinkedHashSet<>()).add(outer);
                }
            }
        }

        // Preserve global topological order across full & sliced encodings
        var globalOrderedConfigs = topologicalOrderConfigs(fullMap, fullImportedBy);
        // Discover global finite property domains across scan so full & slice share identical one-hot encodings
        var globalPropertyDomains = discoverPropertyDomains(globalOrderedConfigs);

        var scopedConfigs = new ArrayList<BytecodeAutoConfigScanner.StarterConfiguration>();
        var propOwners = new HashMap<String, Set<String>>();
        for (var cfg : globalOrderedConfigs) {
            if (allowedConfigs != null && !allowedConfigs.contains(cfg.classFqn())) {
                continue;
            }
            scopedConfigs.add(cfg);
            for (String ep : cfg.enabledConfigProperties()) {
                propOwners.computeIfAbsent(ep, ignored -> new LinkedHashSet<>()).add(cfg.classFqn());
            }
        }

        var clauses = new ArrayList<Formula>();
        var exoVars = new HashSet<String>();
        var usedPropKeys = new TreeSet<String>();
        var cfgVars = new LinkedHashMap<String, Formula>();
        var beanVars = new LinkedHashMap<String, Formula>();
        var allScopedBeans = new ArrayList<BytecodeAutoConfigScanner.StarterBean>();

        // Pre-allocate config & bean activation variables (allows instantiation-time parameter references cleanly)
        for (var cfg : scopedConfigs) {
            cfgVars.put(cfg.classFqn(), variable(prefix + "cfg:" + cfg.classFqn()));
            for (var bean : cfg.beans()) {
                String bId = ConeOfInfluenceSlicer.beanNodeId(bean);
                if (allowedBeans != null && !allowedBeans.contains(bId)) {
                    continue;
                }
                beanVars.put(bId, variable(prefix + bId));
                allScopedBeans.add(bean);
            }
        }

        // Pre-index ALL scoped beans for instantiation-time @Bean parameter satisfaction
        var allScopedBeansByType = new HashMap<String, List<Formula>>();
        var cfgMissingTypesByCfg = new HashMap<String, Set<String>>();
        for (var cfg : scopedConfigs) {
            var mTypes = new LinkedHashSet<String>();
            for (var c : cfg.conditions()) {
                if (c.annotationFqn().endsWith(".ConditionalOnMissingBean")) {
                    mTypes.addAll(c.attributes().getOrDefault("value", List.of()));
                    mTypes.addAll(c.attributes().getOrDefault("type", List.of()));
                }
            }
            cfgMissingTypesByCfg.put(cfg.classFqn(), mTypes);
        }
        for (var bean : allScopedBeans) {
            Formula bVar = beanVars.get(ConeOfInfluenceSlicer.beanNodeId(bean));
            indexBeanFormulaByType(
                    bean, bVar, cfgMissingTypesByCfg.getOrDefault(bean.declaringClassFqn(), Set.of()),
                    supertypesByType, allScopedBeansByType);
        }

        // Registration-time prior-bean indices (built incrementally in topological registration order!)
        var priorBeansByType = new HashMap<String, List<BeanFormulaInfo>>();
        var priorBeansByName = new HashMap<String, List<BeanFormulaInfo>>();

        for (var cfg : scopedConfigs) {
            var rhsTerms = new ArrayList<Formula>();
            var parents = fullImportedBy.getOrDefault(cfg.classFqn(), Set.of());
            if (!parents.isEmpty() && !cfg.metadataSource().contains("AutoConfiguration.imports")) {
                var pOr = new ArrayList<Formula>();
                for (String p : parents) {
                    if (cfgVars.containsKey(p)) {
                        pOr.add(cfgVars.get(p));
                    }
                }
                if (!pOr.isEmpty()) {
                    rhsTerms.add(or(pOr));
                }
            }
            for (var cond : cfg.conditions()) {
                Formula cf = encodeConditionSymbolic(
                        cond, cfg.classFqn(), null, globalPropertyDomains, usedPropKeys,
                        priorBeansByType, priorBeansByName, exoVars);
                if (cf != SymbolicConfiguration.TRUE) {
                    rhsTerms.add(cf);
                }
            }
            Formula cfgRhs = rhsTerms.isEmpty() ? SymbolicConfiguration.TRUE : and(rhsTerms);
            clauses.add(iff(cfgVars.get(cfg.classFqn()), cfgRhs));

            for (var bean : cfg.beans()) {
                String bId = ConeOfInfluenceSlicer.beanNodeId(bean);
                if (allowedBeans != null && !allowedBeans.contains(bId)) {
                    continue;
                }
                Formula bVar = beanVars.get(bId);
                var bTerms = new ArrayList<Formula>();
                bTerms.add(cfgVars.get(cfg.classFqn()));
                for (var cond : bean.conditions()) {
                    Formula cf = encodeConditionSymbolic(
                            cond, cfg.classFqn(), bean.returnTypeFqn(), globalPropertyDomains, usedPropKeys,
                            priorBeansByType, priorBeansByName, exoVars);
                    if (cf != SymbolicConfiguration.TRUE) {
                        bTerms.add(cf);
                    }
                }
                // Required non-container @Bean method parameter dependencies
                for (String rawParam : bean.parameterTypeFqns()) {
                    String base = stripArraySuffix(rawParam);
                    if (isInfraOrWrapperParam(base)) {
                        continue;
                    }
                    var providers = new ArrayList<Formula>();
                    for (String owner : propOwners.getOrDefault(base, Set.of())) {
                        if (cfgVars.containsKey(owner)) {
                            providers.add(cfgVars.get(owner));
                        }
                    }
                    for (Formula pf : allScopedBeansByType.getOrDefault(base, List.of())) {
                        if (!pf.equals(bVar)) {
                            providers.add(pf);
                        }
                    }
                    if (!propOwners.containsKey(base)) {
                        String ext = "extbean:" + base;
                        exoVars.add(ext);
                        providers.add(variable(ext));
                    }
                    if (!providers.isEmpty()) {
                        bTerms.add(or(providers));
                    }
                }
                clauses.add(iff(bVar, and(bTerms)));

                // Register bean in prior-bean indices for subsequent beans/configs
                var info = new BeanFormulaInfo(bVar, bean.primary(), bean.fallback());
                indexBeanInfoByType(
                        bean, info, cfgMissingTypesByCfg.getOrDefault(cfg.classFqn(), Set.of()),
                        supertypesByType, priorBeansByType);
                for (String bn : bean.beanNames()) {
                    priorBeansByName.computeIfAbsent(bn, ignored -> new ArrayList<>()).add(info);
                }
            }
        }

        // Emit exact finite one-hot property domain axioms for every referenced property key
        var usedDomains = new TreeMap<String, List<String>>();
        for (String key : usedPropKeys) {
            List<String> dom = globalPropertyDomains.get(key);
            if (dom == null || dom.isEmpty()) {
                continue;
            }
            usedDomains.put(key, dom);
            var choiceVars = new ArrayList<Formula>(dom.size());
            for (String v : dom) {
                String vn = propValueVarName(key, v);
                exoVars.add(vn);
                choiceVars.add(variable(vn));
            }
            // At-least-one value in finite partition
            clauses.add(or(choiceVars));
            // Pairwise mutual exclusion (at-most-one value)
            for (int a = 0; a < choiceVars.size(); a++) {
                for (int b = a + 1; b < choiceVars.size(); b++) {
                    clauses.add(or(not(choiceVars.get(a)), not(choiceVars.get(b))));
                }
            }
        }

        Formula root = clauses.isEmpty() ? SymbolicConfiguration.TRUE : and(clauses);
        return new EncodedStarterSat(
                root, beanVars, usedDomains, exoVars.size(), cfgVars.size() + beanVars.size(), clauses.size());
    }

    public static String propValueVarName(String normalizedKey, String domainValue) {
        return "propval:" + normalizedKey + "=" + domainValue;
    }

    private record BeanFormulaInfo(Formula activeVar, boolean primary, boolean fallback) {}

    private static Map<String, List<String>> discoverPropertyDomains(
            List<BytecodeAutoConfigScanner.StarterConfiguration> configs) {
        var valuesByKey = new TreeMap<String, LinkedHashSet<String>>();
        for (var cfg : configs) {
            for (var cond : cfg.conditions()) {
                collectPropertyDomainHints(cond, valuesByKey);
            }
            for (var bean : cfg.beans()) {
                for (var cond : bean.conditions()) {
                    collectPropertyDomainHints(cond, valuesByKey);
                }
            }
        }
        var domains = new TreeMap<String, List<String>>();
        valuesByKey.forEach((k, vals) -> {
            var ordered = new LinkedHashSet<String>();
            ordered.add(MISSING_VALUE_TOKEN);
            ordered.addAll(vals);
            ordered.add(OTHER_VALUE_TOKEN);
            domains.put(k, List.copyOf(ordered));
        });
        return domains;
    }

    private static void collectPropertyDomainHints(
            BytecodeAutoConfigScanner.ConditionAnnotation cond,
            Map<String, LinkedHashSet<String>> valuesByKey) {
        String simple = simpleName(cond.annotationFqn());
        if ("ConditionalOnProperty".equals(simple) || "ConditionalOnBooleanProperty".equals(simple)) {
            boolean isBool = "ConditionalOnBooleanProperty".equals(simple);
            String prefix = normalizePrefix(cond.attributes().getOrDefault("prefix", List.of()));
            var names = new ArrayList<String>();
            names.addAll(cond.attributes().getOrDefault("name", List.of()));
            names.addAll(cond.attributes().getOrDefault("value", List.of()));
            String hv = cond.attributes().getOrDefault("havingValue", isBool ? List.of("true") : List.of(""))
                    .stream().findFirst().orElse(isBool ? "true" : "").trim().toLowerCase(Locale.ROOT);
            for (String n : names) {
                if (n == null || n.isBlank()) continue;
                String key = BytecodeAutoConfigScanner.normalizePropertyKey(
                        prefix.isEmpty() ? n.trim() : prefix + "." + n.trim());
                var set = valuesByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
                if (hv.isEmpty()) {
                    set.add("true");
                    set.add("false");
                } else {
                    set.add(hv);
                    if ("true".equals(hv)) set.add("false");
                    else if ("false".equals(hv)) set.add("true");
                }
            }
        } else if ("ConditionalOnThreading".equals(simple)) {
            var set = valuesByKey.computeIfAbsent("spring.threads.virtual.enabled", ignored -> new LinkedHashSet<>());
            set.add("true");
            set.add("false");
        }
    }

    private static Formula encodeConditionSymbolic(
            BytecodeAutoConfigScanner.ConditionAnnotation cond,
            String cfgFqn,
            String beanReturnType,
            Map<String, List<String>> propertyDomains,
            Set<String> usedPropKeys,
            Map<String, List<BeanFormulaInfo>> priorBeansByType,
            Map<String, List<BeanFormulaInfo>> priorBeansByName,
            Set<String> exoVarsOut) {
        String simple = simpleName(cond.annotationFqn());

        if ("ConditionalOnProperty".equals(simple) || "ConditionalOnBooleanProperty".equals(simple)) {
            boolean isBool = "ConditionalOnBooleanProperty".equals(simple);
            String prefix = normalizePrefix(cond.attributes().getOrDefault("prefix", List.of()));
            var names = new ArrayList<String>();
            names.addAll(cond.attributes().getOrDefault("name", List.of()));
            names.addAll(cond.attributes().getOrDefault("value", List.of()));
            String hv = cond.attributes().getOrDefault("havingValue", isBool ? List.of("true") : List.of(""))
                    .stream().findFirst().orElse(isBool ? "true" : "").trim();
            boolean matchIfMissing = Boolean.parseBoolean(
                    cond.attributes().getOrDefault("matchIfMissing", List.of("false")).getFirst());

            var terms = new ArrayList<Formula>();
            for (String n : names) {
                if (n == null || n.isBlank()) continue;
                String key = BytecodeAutoConfigScanner.normalizePropertyKey(
                        prefix.isEmpty() ? n.trim() : prefix + "." + n.trim());
                usedPropKeys.add(key);
                terms.add(matchOneHotPropertyFormula(key, hv, matchIfMissing, propertyDomains, exoVarsOut));
            }
            return terms.isEmpty() ? SymbolicConfiguration.TRUE : and(terms);
        }

        if ("ConditionalOnThreading".equals(simple)) {
            String key = "spring.threads.virtual.enabled";
            usedPropKeys.add(key);
            Formula virtualMatches = matchOneHotPropertyFormula(key, "true", false, propertyDomains, exoVarsOut);
            String val = cond.attributes().getOrDefault("value", List.of()).stream().findFirst().orElse("");
            return val.endsWith(".VIRTUAL") ? virtualMatches : not(virtualMatches);
        }

        if ("ConditionalOnClass".equals(simple) || "ConditionalOnMissingClass".equals(simple)) {
            boolean wantPresent = "ConditionalOnClass".equals(simple);
            var targets = new ArrayList<String>();
            targets.addAll(cond.attributes().getOrDefault("value", List.of()));
            targets.addAll(cond.attributes().getOrDefault("name", List.of()));
            var terms = new ArrayList<Formula>();
            for (String t : targets) {
                String varName = "class:" + t;
                exoVarsOut.add(varName);
                terms.add(wantPresent ? variable(varName) : not(variable(varName)));
            }
            return terms.isEmpty() ? SymbolicConfiguration.TRUE : and(terms);
        }

        if ("Profile".equals(simple)) {
            var exprs = cond.attributes().getOrDefault("value", List.of());
            if (exprs.isEmpty()) return SymbolicConfiguration.TRUE;
            var parts = new ArrayList<Formula>();
            for (String expr : exprs) {
                for (String tok : expr.split("[!&|()\\s]+")) {
                    if (!tok.isBlank()) exoVarsOut.add("profile:" + tok.trim());
                }
                parts.add(parseProfileFormula(expr));
            }
            return parts.size() == 1 ? parts.getFirst() : or(parts);
        }

        if ("ConditionalOnBean".equals(simple)
                || "ConditionalOnMissingBean".equals(simple)
                || "ConditionalOnSingleCandidate".equals(simple)) {
            var types = new ArrayList<String>();
            types.addAll(cond.attributes().getOrDefault("value", List.of()));
            types.addAll(cond.attributes().getOrDefault("type", List.of()));
            var names = cond.attributes().getOrDefault("name", List.of());
            var anns = cond.attributes().getOrDefault("annotation", List.of());
            if (!anns.isEmpty()) {
                String opVar = "opaque:" + cfgFqn + "#" + simple + ":ann";
                exoVarsOut.add(opVar);
                return variable(opVar);
            }
            if (types.isEmpty() && names.isEmpty() && beanReturnType != null && !beanReturnType.equals("void")) {
                types.add(beanReturnType);
            }

            var candidateInfos = new ArrayList<BeanFormulaInfo>();
            for (String t : types) {
                String ext = "extbean:" + t;
                exoVarsOut.add(ext);
                candidateInfos.add(new BeanFormulaInfo(variable(ext), false, false));
                candidateInfos.addAll(priorBeansByType.getOrDefault(t, List.of()));
            }
            for (String n : names) {
                String ext = "extname:" + n;
                exoVarsOut.add(ext);
                candidateInfos.add(new BeanFormulaInfo(variable(ext), false, false));
                candidateInfos.addAll(priorBeansByName.getOrDefault(n, List.of()));
            }
            if (candidateInfos.isEmpty()) {
                return SymbolicConfiguration.TRUE;
            }
            if ("ConditionalOnSingleCandidate".equals(simple)) {
                return encodeSingleCandidateFormula(candidateInfos, cfgFqn, exoVarsOut);
            }
            var activeList = candidateInfos.stream().map(BeanFormulaInfo::activeVar).toList();
            Formula any = or(activeList);
            return "ConditionalOnMissingBean".equals(simple) ? not(any) : any;
        }

        if (cond.opaque() || simple.startsWith("Conditional")) {
            String opVar = "opaque:" + cfgFqn + "#" + simple;
            exoVarsOut.add(opVar);
            return variable(opVar);
        }
        return SymbolicConfiguration.TRUE;
    }

    private static Formula encodeSingleCandidateFormula(
            List<BeanFormulaInfo> candidates,
            String cfgFqn,
            Set<String> exoVarsOut) {
        if (candidates.size() == 1) {
            return candidates.getFirst().activeVar();
        }
        if (candidates.size() > 8) {
            // Bounded guard: at least one candidate active AND opaque uniqueness predicate
            String opVar = "opaque:" + cfgFqn + "#SingleCandidateUnique";
            exoVarsOut.add(opVar);
            return and(or(candidates.stream().map(BeanFormulaInfo::activeVar).toList()), variable(opVar));
        }
        // Exact single-candidate semantics: (exactly 1 active) OR (unique @Primary active)
        var options = new ArrayList<Formula>();
        for (int i = 0; i < candidates.size(); i++) {
            var exactOne = new ArrayList<Formula>(candidates.size());
            exactOne.add(candidates.get(i).activeVar());
            for (int j = 0; j < candidates.size(); j++) {
                if (j != i) {
                    exactOne.add(not(candidates.get(j).activeVar()));
                }
            }
            options.add(and(exactOne));
            if (candidates.get(i).primary()) {
                var uniquePrimary = new ArrayList<Formula>();
                uniquePrimary.add(candidates.get(i).activeVar());
                for (int j = 0; j < candidates.size(); j++) {
                    if (j != i && candidates.get(j).primary()) {
                        uniquePrimary.add(not(candidates.get(j).activeVar()));
                    }
                }
                options.add(and(uniquePrimary));
            }
        }
        return or(options);
    }

    private static Formula matchOneHotPropertyFormula(
            String normKey,
            String havingValue,
            boolean matchIfMissing,
            Map<String, List<String>> propertyDomains,
            Set<String> exoVarsOut) {
        List<String> dom = propertyDomains.getOrDefault(normKey, List.of(MISSING_VALUE_TOKEN, "true", "false", OTHER_VALUE_TOKEN));
        var matchedVars = new ArrayList<Formula>();
        for (String v : dom) {
            boolean matches;
            if (MISSING_VALUE_TOKEN.equals(v)) {
                matches = matchIfMissing;
            } else if (havingValue.isEmpty()) {
                matches = !"false".equalsIgnoreCase(v);
            } else {
                matches = havingValue.equalsIgnoreCase(v);
            }
            if (matches) {
                String vn = propValueVarName(normKey, v);
                exoVarsOut.add(vn);
                matchedVars.add(variable(vn));
            }
        }
        return matchedVars.isEmpty() ? SymbolicConfiguration.FALSE : or(matchedVars);
    }

    private static Truth evalCondition3Valued(
            BytecodeAutoConfigScanner.ConditionAnnotation cond,
            String beanReturnType,
            Set<String> passiveClasspathFqns,
            Map<String, Dimension> propDimMap,
            Set<String> profileDims,
            UserBeanInventory userBeans,
            Map<String, Set<String>> supertypesByType,
            List<BytecodeAutoConfigScanner.StarterBean> priorBeans,
            Map<String, Truth> beanTruth) {
        String simple = simpleName(cond.annotationFqn());

        if ("ConditionalOnClass".equals(simple) || "ConditionalOnMissingClass".equals(simple)) {
            boolean wantPresent = "ConditionalOnClass".equals(simple);
            var targets = new ArrayList<String>();
            targets.addAll(cond.attributes().getOrDefault("value", List.of()));
            targets.addAll(cond.attributes().getOrDefault("name", List.of()));
            for (String target : targets) {
                boolean present = passiveClasspathFqns.contains(target);
                if (wantPresent && !present) return Truth.F;
                if (!wantPresent && present) return Truth.F;
            }
            return Truth.T;
        }

        if ("ConditionalOnProperty".equals(simple) || "ConditionalOnBooleanProperty".equals(simple)) {
            boolean isBool = "ConditionalOnBooleanProperty".equals(simple);
            String prefix = normalizePrefix(cond.attributes().getOrDefault("prefix", List.of()));
            var names = new ArrayList<String>();
            names.addAll(cond.attributes().getOrDefault("name", List.of()));
            names.addAll(cond.attributes().getOrDefault("value", List.of()));
            String havingValue = cond.attributes().getOrDefault("havingValue", isBool ? List.of("true") : List.of(""))
                    .stream().findFirst().orElse(isBool ? "true" : "");
            boolean matchIfMissing = Boolean.parseBoolean(
                    cond.attributes().getOrDefault("matchIfMissing", List.of("false")).getFirst());

            Truth allNames = Truth.T;
            for (String rawName : names) {
                String fullKey = prefix.isEmpty() ? rawName.trim() : prefix + "." + rawName.trim();
                String normKey = BytecodeAutoConfigScanner.normalizePropertyKey(fullKey);
                allNames = allNames.andWith(evalPropertyDim3Valued(normKey, havingValue, matchIfMissing, propDimMap));
            }
            return allNames;
        }

        if ("ConditionalOnThreading".equals(simple)) {
            String threadingVal = cond.attributes().getOrDefault("value", List.of()).stream().findFirst().orElse("");
            Truth virtualOn = evalPropertyDim3Valued("spring.threads.virtual.enabled", "true", false, propDimMap);
            if (threadingVal.endsWith(".VIRTUAL")) return virtualOn;
            if (threadingVal.endsWith(".PLATFORM")) return virtualOn.negate();
            return Truth.U;
        }

        if ("Profile".equals(simple)) {
            var exprs = cond.attributes().getOrDefault("value", List.of());
            if (exprs.isEmpty()) return Truth.T;
            var parts = new ArrayList<Formula>();
            for (String expr : exprs) {
                parts.add(parseProfileFormulaBounded(expr, profileDims));
            }
            Formula f = parts.size() == 1 ? parts.getFirst() : or(parts);
            return new Truth(f, not(f));
        }

        if ("ConditionalOnBean".equals(simple)
                || "ConditionalOnMissingBean".equals(simple)
                || "ConditionalOnSingleCandidate".equals(simple)) {
            var typeTargets = new ArrayList<String>();
            typeTargets.addAll(cond.attributes().getOrDefault("value", List.of()));
            typeTargets.addAll(cond.attributes().getOrDefault("type", List.of()));
            var nameTargets = cond.attributes().getOrDefault("name", List.of());
            var annTargets = cond.attributes().getOrDefault("annotation", List.of());
            if (!annTargets.isEmpty()) {
                return Truth.U;
            }
            if (typeTargets.isEmpty() && nameTargets.isEmpty() && beanReturnType != null && !beanReturnType.equals("void")) {
                typeTargets.add(beanReturnType);
            }

            var candidates = collectMatchingCandidates(
                    typeTargets, nameTargets, userBeans, supertypesByType, priorBeans, beanTruth);

            if ("ConditionalOnSingleCandidate".equals(simple)) {
                return evalSingleCandidate3Valued(candidates);
            }
            Truth anyPresent = Truth.F;
            for (var cand : candidates) {
                anyPresent = anyPresent.orWith(cand.truth());
            }
            return "ConditionalOnMissingBean".equals(simple) ? anyPresent.negate() : anyPresent;
        }

        // Opaque custom @Conditional(...) / @ConditionalOnResource / @ConditionalOnWebApplication -> fail-closed UNKNOWN
        return Truth.U;
    }

    private static List<CandidateTruth> collectMatchingCandidates(
            List<String> typeTargets,
            List<String> nameTargets,
            UserBeanInventory userBeans,
            Map<String, Set<String>> supertypesByType,
            List<BytecodeAutoConfigScanner.StarterBean> priorBeans,
            Map<String, Truth> beanTruth) {
        var candidates = new ArrayList<CandidateTruth>();
        var seenUserBeans = new HashSet<String>();
        for (var ub : userBeans.beans()) {
            boolean matches = false;
            for (String t : typeTargets) {
                if (isTypeAssignable(ub.returnTypeFqn(), t, supertypesByType)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                for (String n : nameTargets) {
                    if (ub.beanName().equals(n)) {
                        matches = true;
                        break;
                    }
                }
            }
            if (matches && seenUserBeans.add("user:" + ub.beanName() + ":" + ub.returnTypeFqn())) {
                candidates.add(new CandidateTruth(Truth.T, ub.primary(), ub.fallback()));
            }
        }
        for (String n : nameTargets) {
            if (userBeans.syntheticBeanNames().contains(n) && seenUserBeans.add("synth:" + n)) {
                candidates.add(new CandidateTruth(Truth.T, false, false));
            }
        }
        var seenStarterIds = new HashSet<String>();
        for (var pb : priorBeans) {
            String pId = ConeOfInfluenceSlicer.beanNodeId(pb);
            boolean matches = false;
            for (String t : typeTargets) {
                if (starterBeanMatchesType(pb, t, supertypesByType)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                for (String n : nameTargets) {
                    if (pb.beanNames().contains(n)) {
                        matches = true;
                        break;
                    }
                }
            }
            if (matches && seenStarterIds.add(pId)) {
                candidates.add(new CandidateTruth(
                        beanTruth.getOrDefault(pId, Truth.U), pb.primary(), pb.fallback()));
            }
        }
        return candidates;
    }

    /**
     * Exact sound 3-valued {@code @ConditionalOnSingleCandidate} evaluation:
     * matches iff (exactly one candidate is active) OR (unique {@code @Primary} candidate is active).
     * Never conflates {@code @ConditionalOnSingleCandidate} with {@code @ConditionalOnBean};
     * returns FALSE when 2+ non-primary beans are simultaneously active, and fails closed to UNKNOWN
     * if candidate count exceeds the exact enumeration cap.
     */
    private static Truth evalSingleCandidate3Valued(List<CandidateTruth> candidates) {
        if (candidates.isEmpty()) {
            return Truth.F;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst().truth();
        }
        if (candidates.size() > 8) {
            // Sound fail-closed bound: definitely FALSE if all candidates are absent, otherwise UNKNOWN
            Truth anyPresent = Truth.F;
            for (var c : candidates) {
                anyPresent = anyPresent.orWith(c.truth());
            }
            return new Truth(SymbolicConfiguration.FALSE, anyPresent.no());
        }
        Truth result = Truth.F;
        for (int i = 0; i < candidates.size(); i++) {
            // Case A: candidate i is the ONLY active candidate
            Truth exactOne = candidates.get(i).truth();
            for (int j = 0; j < candidates.size(); j++) {
                if (j != i) {
                    exactOne = exactOne.andWith(candidates.get(j).truth().negate());
                }
            }
            result = result.orWith(exactOne);

            // Case B: candidate i is @Primary and NO other @Primary candidate is active
            if (candidates.get(i).primary()) {
                Truth uniquePrimary = candidates.get(i).truth();
                for (int j = 0; j < candidates.size(); j++) {
                    if (j != i && candidates.get(j).primary()) {
                        uniquePrimary = uniquePrimary.andWith(candidates.get(j).truth().negate());
                    }
                }
                result = result.orWith(uniquePrimary);
            }
        }
        return result;
    }

    private static Truth evalPropertyDim3Valued(
            String normKey,
            String havingValue,
            boolean matchIfMissing,
            Map<String, Dimension> propDimMap) {
        Dimension dim = propDimMap.get(normKey);
        if (dim == null) {
            return matchIfMissing ? Truth.T : Truth.F;
        }
        var matching = new ArrayList<Formula>();
        for (int i = 0; i < dim.values().size(); i++) {
            var valOpt = dim.values().get(i);
            boolean matched = valOpt.isEmpty()
                    ? matchIfMissing
                    : (havingValue.isEmpty()
                            ? !"false".equalsIgnoreCase(valOpt.orElseThrow())
                            : havingValue.equalsIgnoreCase(valOpt.orElseThrow()));
            if (matched) {
                matching.add(variable("dom:" + normKey + ":" + i));
            }
        }
        Formula yes = matching.isEmpty() ? SymbolicConfiguration.FALSE : or(matching);
        return new Truth(yes, not(yes));
    }

    private static String classify3Valued(Formula domainFormula, Truth truth) {
        var satYes = IncrementalCdclReasoner.INSTANCE.solve(new Query(and(domainFormula, truth.yes()), Limits.defaults()));
        var satNo = IncrementalCdclReasoner.INSTANCE.solve(new Query(and(domainFormula, truth.no()), Limits.defaults()));
        var satUnk = IncrementalCdclReasoner.INSTANCE.solve(new Query(and(domainFormula, truth.unknown()), Limits.defaults()));
        if (satYes.status() == Satisfiability.UNKNOWN
                || satNo.status() == Satisfiability.UNKNOWN
                || satUnk.status() == Satisfiability.UNKNOWN
                || satUnk.status() == Satisfiability.SATISFIABLE) {
            return "UNKNOWN";
        }
        boolean canYes = satYes.status() == Satisfiability.SATISFIABLE;
        boolean canNo = satNo.status() == Satisfiability.SATISFIABLE;
        if (canYes && !canNo) return "MUST";
        if (!canYes && canNo) return "NEVER";
        if (canYes && canNo) return "MAY";
        return "UNKNOWN";
    }

    private static boolean starterBeanMatchesType(
            BytecodeAutoConfigScanner.StarterBean bean,
            String targetType,
            Map<String, Set<String>> supertypesByType) {
        if (isTypeAssignable(bean.returnTypeFqn(), targetType, supertypesByType)) {
            return true;
        }
        for (var c : bean.conditions()) {
            if (c.annotationFqn().endsWith(".ConditionalOnMissingBean")) {
                for (String t : c.attributes().getOrDefault("value", List.of())) {
                    if (isTypeAssignable(t, targetType, supertypesByType)) return true;
                }
                for (String t : c.attributes().getOrDefault("type", List.of())) {
                    if (isTypeAssignable(t, targetType, supertypesByType)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isTypeAssignable(String subType, String superType, Map<String, Set<String>> supertypesByType) {
        if (subType.equals(superType)) {
            return true;
        }
        var queue = new ArrayDeque<String>();
        var visited = new HashSet<String>();
        queue.add(subType);
        visited.add(subType);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String sup : supertypesByType.getOrDefault(cur, Set.of())) {
                if (sup.equals(superType)) return true;
                if (visited.add(sup)) queue.add(sup);
            }
        }
        return false;
    }

    private static void indexBeanFormulaByType(
            BytecodeAutoConfigScanner.StarterBean bean,
            Formula formula,
            Set<String> enclosingMissingBeanTypes,
            Map<String, Set<String>> supertypesByType,
            Map<String, List<Formula>> out) {
        var types = new LinkedHashSet<String>();
        if (!bean.returnTypeFqn().equals("void") && !bean.returnTypeFqn().equals("java.lang.Object")) {
            collectTypeAndSupertypes(bean.returnTypeFqn(), supertypesByType, types);
        }
        for (var c : bean.conditions()) {
            if (c.annotationFqn().endsWith(".ConditionalOnMissingBean")) {
                for (String t : c.attributes().getOrDefault("value", List.of())) {
                    collectTypeAndSupertypes(t, supertypesByType, types);
                }
                for (String t : c.attributes().getOrDefault("type", List.of())) {
                    collectTypeAndSupertypes(t, supertypesByType, types);
                }
            }
        }
        for (String t : enclosingMissingBeanTypes) {
            collectTypeAndSupertypes(t, supertypesByType, types);
        }
        for (String t : types) {
            out.computeIfAbsent(t, ignored -> new ArrayList<>()).add(formula);
        }
    }

    private static void indexBeanInfoByType(
            BytecodeAutoConfigScanner.StarterBean bean,
            BeanFormulaInfo info,
            Set<String> enclosingMissingBeanTypes,
            Map<String, Set<String>> supertypesByType,
            Map<String, List<BeanFormulaInfo>> out) {
        var types = new LinkedHashSet<String>();
        if (!bean.returnTypeFqn().equals("void") && !bean.returnTypeFqn().equals("java.lang.Object")) {
            collectTypeAndSupertypes(bean.returnTypeFqn(), supertypesByType, types);
        }
        for (var c : bean.conditions()) {
            if (c.annotationFqn().endsWith(".ConditionalOnMissingBean")) {
                for (String t : c.attributes().getOrDefault("value", List.of())) {
                    collectTypeAndSupertypes(t, supertypesByType, types);
                }
                for (String t : c.attributes().getOrDefault("type", List.of())) {
                    collectTypeAndSupertypes(t, supertypesByType, types);
                }
            }
        }
        for (String t : enclosingMissingBeanTypes) {
            collectTypeAndSupertypes(t, supertypesByType, types);
        }
        for (String t : types) {
            out.computeIfAbsent(t, ignored -> new ArrayList<>()).add(info);
        }
    }

    private static void collectTypeAndSupertypes(
            String root,
            Map<String, Set<String>> supertypesByType,
            Set<String> out) {
        if (root == null || root.isBlank() || "void".equals(root) || "java.lang.Object".equals(root)) {
            return;
        }
        var queue = new ArrayDeque<String>();
        if (out.add(root)) {
            queue.add(root);
        }
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String sup : supertypesByType.getOrDefault(cur, Set.of())) {
                if (sup != null && !sup.isBlank() && !"java.lang.Object".equals(sup) && out.add(sup)) {
                    queue.add(sup);
                }
            }
        }
    }

    private static List<BytecodeAutoConfigScanner.StarterConfiguration> topologicalOrderConfigs(
            Map<String, BytecodeAutoConfigScanner.StarterConfiguration> scopeMap,
            Map<String, Set<String>> importedBy) {
        var deps = new LinkedHashMap<String, Set<String>>();
        for (String fqn : scopeMap.keySet()) {
            deps.put(fqn, new LinkedHashSet<>());
        }
        for (var cfg : scopeMap.values()) {
            String fqn = cfg.classFqn();
            for (String parent : importedBy.getOrDefault(fqn, Set.of())) {
                if (scopeMap.containsKey(parent) && !parent.equals(fqn)) {
                    deps.get(fqn).add(parent);
                }
            }
            for (var ord : cfg.orderings()) {
                String simple = simpleName(ord.annotationFqn());
                if ("AutoConfigureAfter".equals(simple)) {
                    for (String t : ord.targetClassFqns()) {
                        if (scopeMap.containsKey(t) && !t.equals(fqn)) deps.get(fqn).add(t);
                    }
                    for (String t : ord.targetClassNames()) {
                        if (scopeMap.containsKey(t) && !t.equals(fqn)) deps.get(fqn).add(t);
                    }
                } else if ("AutoConfigureBefore".equals(simple)) {
                    for (String t : ord.targetClassFqns()) {
                        if (scopeMap.containsKey(t) && !t.equals(fqn)) deps.get(t).add(fqn);
                    }
                    for (String t : ord.targetClassNames()) {
                        if (scopeMap.containsKey(t) && !t.equals(fqn)) deps.get(t).add(fqn);
                    }
                }
            }
        }
        var indegree = new LinkedHashMap<String, Integer>();
        var rev = new HashMap<String, List<String>>();
        for (var e : deps.entrySet()) {
            indegree.put(e.getKey(), e.getValue().size());
            for (String d : e.getValue()) {
                rev.computeIfAbsent(d, ignored -> new ArrayList<>()).add(e.getKey());
            }
        }
        var ready = new PriorityQueue<String>();
        for (var e : indegree.entrySet()) {
            if (e.getValue() == 0) ready.add(e.getKey());
        }
        var ordered = new ArrayList<BytecodeAutoConfigScanner.StarterConfiguration>(scopeMap.size());
        var placed = new HashSet<String>();
        while (!ready.isEmpty()) {
            String cur = ready.poll();
            if (!placed.add(cur)) continue;
            ordered.add(scopeMap.get(cur));
            for (String nxt : rev.getOrDefault(cur, List.of())) {
                int deg = indegree.get(nxt) - 1;
                indegree.put(nxt, deg);
                if (deg == 0) ready.add(nxt);
            }
        }
        for (var cfg : scopeMap.values()) {
            if (placed.add(cfg.classFqn())) {
                ordered.add(cfg);
            }
        }
        return ordered;
    }

    private static Formula parseProfileFormula(String expr) {
        String s = expr.strip();
        while (s.startsWith("(") && s.endsWith(")") && matchingOuterParens(s)) {
            s = s.substring(1, s.length() - 1).strip();
        }
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && c == '|') {
                return or(parseProfileFormula(s.substring(0, i)), parseProfileFormula(s.substring(i + 1)));
            }
        }
        depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && c == '&') {
                return and(parseProfileFormula(s.substring(0, i)), parseProfileFormula(s.substring(i + 1)));
            }
        }
        if (s.startsWith("!")) {
            return not(parseProfileFormula(s.substring(1)));
        }
        return variable("profile:" + s);
    }

    private static Formula parseProfileFormulaBounded(String expr, Set<String> profileDims) {
        String s = expr.strip();
        while (s.startsWith("(") && s.endsWith(")") && matchingOuterParens(s)) {
            s = s.substring(1, s.length() - 1).strip();
        }
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && c == '|') {
                return or(parseProfileFormulaBounded(s.substring(0, i), profileDims),
                        parseProfileFormulaBounded(s.substring(i + 1), profileDims));
            }
        }
        depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && c == '&') {
                return and(parseProfileFormulaBounded(s.substring(0, i), profileDims),
                        parseProfileFormulaBounded(s.substring(i + 1), profileDims));
            }
        }
        if (s.startsWith("!")) {
            return not(parseProfileFormulaBounded(s.substring(1), profileDims));
        }
        if ("default".equals(s)) {
            if (profileDims.isEmpty()) return SymbolicConfiguration.TRUE;
            var negs = new ArrayList<Formula>();
            for (String p : profileDims) negs.add(not(variable("profile:" + p)));
            return and(negs);
        }
        return profileDims.contains(s) ? variable("profile:" + s) : SymbolicConfiguration.FALSE;
    }

    private static boolean matchingOuterParens(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '(') depth++;
            else if (s.charAt(i) == ')') depth--;
            if (depth == 0 && i < s.length() - 1) return false;
        }
        return depth == 0;
    }

    private static String normalizePrefix(List<String> prefixes) {
        String prefix = prefixes.isEmpty() ? "" : prefixes.getFirst().trim();
        while (prefix.endsWith(".")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    private static String stripArraySuffix(String typeFqn) {
        String t = typeFqn == null ? "" : typeFqn.trim();
        while (t.endsWith("[]")) {
            t = t.substring(0, t.length() - 2);
        }
        return t;
    }

    private static boolean isInfraOrWrapperParam(String typeFqn) {
        return typeFqn.isBlank() || INFRA_OR_WRAPPER_PARAMS.contains(typeFqn);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static Formula iff(Formula a, Formula b) {
        return and(or(not(a), b), or(not(b), a));
    }
}
