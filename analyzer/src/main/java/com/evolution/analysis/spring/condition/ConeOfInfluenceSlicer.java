package com.evolution.analysis.spring.condition;

import com.evolution.analysis.spring.universal.BasicSpelEvaluator;
import com.evolution.analysis.spring.universal.BytecodeAutoConfigScanner;
import java.util.*;

/**
 * Sound Cone-of-Influence (COI) slicer for Spring configuration spaces and starter bytecode graphs.
 *
 * <p>Decomposes a global condition model into independent coupled components
 * so each component obeys per-component dimension bounds ({@link #MAX_COMPONENT_VARS})
 * instead of a global repository ceiling, and computes sound backward
 * {@code Reach^{-1}(target)} slices over starter {@code @Bean} / {@code @Configuration} graphs
 * preserving exact truth-region semantics (over-approximation; never drops competing same-type beans,
 * {@code @Bean} parameter dependencies, enclosing/importing configuration guards, or directed
 * {@code @AutoConfigureBefore}/{@code @AutoConfigureAfter} upstream producers).
 */
public final class ConeOfInfluenceSlicer {

    public static final int MAX_COMPONENT_VARS = 64;

    private static final Set<String> PRIMITIVE_OR_OBJECT = Set.of(
            "void", "boolean", "byte", "short", "int", "long", "char", "float", "double", "java.lang.Object");

    private static final Set<String> ERASED_COLLECTION_OR_PROVIDER_TYPES = Set.of(
            "org.springframework.beans.factory.ObjectProvider",
            "org.springframework.beans.factory.ObjectFactory",
            "java.util.List",
            "java.util.Collection",
            "java.util.Set",
            "java.util.Map",
            "java.util.Optional",
            "java.util.stream.Stream");

    private static final Set<String> CONTAINER_INFRASTRUCTURE_TYPES = Set.of(
            "org.springframework.context.ApplicationContext",
            "org.springframework.context.ConfigurableApplicationContext",
            "org.springframework.beans.factory.BeanFactory",
            "org.springframework.beans.factory.ListableBeanFactory",
            "org.springframework.beans.factory.HierarchicalBeanFactory",
            "org.springframework.beans.factory.config.ConfigurableBeanFactory",
            "org.springframework.beans.factory.config.ConfigurableListableBeanFactory",
            "org.springframework.beans.factory.support.BeanDefinitionRegistry",
            "org.springframework.core.env.Environment",
            "org.springframework.core.env.ConfigurableEnvironment",
            "org.springframework.core.io.ResourceLoader",
            "org.springframework.core.io.support.ResourcePatternResolver",
            "org.springframework.context.ApplicationEventPublisher",
            "org.springframework.core.metrics.ApplicationStartup",
            "java.lang.ClassLoader");

    public record ConditionComponent(
            int index,
            List<FiniteDomain.Variable> variables,
            List<ConditionOccurrence> occurrences) {
        public ConditionComponent {
            variables = List.copyOf(variables);
            occurrences = List.copyOf(occurrences);
        }
    }

    public record ModelDecomposition(
            List<ConditionComponent> components,
            int totalVariables,
            int maxComponentVariables) {
        public ModelDecomposition {
            components = List.copyOf(components);
        }
    }

    public record DirectedSlice(
            String targetId,
            String targetKind,
            int sliceVars,
            int sliceConfigs,
            int sliceBeans,
            List<String> variableIds) {
        public DirectedSlice {
            variableIds = List.copyOf(variableIds);
        }
    }

    public record DetailedSlice(
            String targetId,
            String targetKind,
            Set<String> configFqns,
            Set<String> beanNodeIds,
            List<String> variableIds) {
        public DetailedSlice {
            configFqns = Collections.unmodifiableSet(new TreeSet<>(configFqns));
            beanNodeIds = Collections.unmodifiableSet(new TreeSet<>(beanNodeIds));
            variableIds = List.copyOf(variableIds);
        }

        public DirectedSlice toDirectedSlice() {
            return new DirectedSlice(
                    targetId,
                    targetKind,
                    variableIds.size(),
                    configFqns.size(),
                    beanNodeIds.size(),
                    variableIds);
        }
    }

    public record StarterSlicingResult(
            int totalTargets,
            int totalGlobalVars,
            int undirectedMaxComponentVars,
            int directedMaxSliceVars,
            double directedMedianSliceVars,
            int slicesExceedingBudget,
            List<DirectedSlice> topSlices,
            long elapsedMs) {
        public StarterSlicingResult {
            topSlices = List.copyOf(topSlices);
        }
    }

    private ConeOfInfluenceSlicer() {}

    /**
     * Partitions condition occurrences into variable-and-selector coupled components.
     * Any two occurrences sharing an exogenous profile/property/web-mode variable, a bean-state
     * selector (type/name/annotation), an opaque correlation key, or Spring's {@code default}
     * profile semantics (which depends on the absence of all explicit profiles) belong to the same component.
     */
    public static ModelDecomposition decomposeConditionModel(List<ConditionOccurrence> occurrences) {
        Objects.requireNonNull(occurrences, "occurrences");
        if (occurrences.isEmpty()) {
            return new ModelDecomposition(List.of(), 0, 0);
        }

        var occToVars = new ArrayList<Set<FiniteDomain.Variable>>(occurrences.size());
        var allVars = new TreeSet<FiniteDomain.Variable>();
        boolean hasDefaultProfile = false;

        for (int i = 0; i < occurrences.size(); i++) {
            var vars = extractVariables(occurrences.get(i).expression());
            occToVars.add(vars);
            allVars.addAll(vars);
            for (var v : vars) {
                if (v.kind() == FiniteDomain.Kind.PROFILE && "default".equals(v.name())) {
                    hasDefaultProfile = true;
                }
            }
        }

        var keyToOccurrences = new TreeMap<String, List<Integer>>();
        var occToKeys = new ArrayList<Set<String>>(occurrences.size());
        for (int i = 0; i < occurrences.size(); i++) {
            var keys = new TreeSet<String>();
            for (var v : occToVars.get(i)) {
                keys.add("var:" + v.kind() + ":" + v.name());
                if (hasDefaultProfile && v.kind() == FiniteDomain.Kind.PROFILE) {
                    keys.add("coupling:default-profile");
                }
            }
            keys.addAll(extractCouplingKeys(occurrences.get(i).expression()));
            occToKeys.add(keys);
            for (String k : keys) {
                keyToOccurrences.computeIfAbsent(k, ignored -> new ArrayList<>()).add(i);
            }
        }

        var visitedOcc = new boolean[occurrences.size()];
        var components = new ArrayList<ConditionComponent>();

        for (int start = 0; start < occurrences.size(); start++) {
            if (visitedOcc[start]) {
                continue;
            }
            visitedOcc[start] = true;
            var queue = new ArrayDeque<Integer>();
            queue.add(start);
            var compOccIndices = new TreeSet<Integer>();
            var compVars = new TreeSet<FiniteDomain.Variable>();

            while (!queue.isEmpty()) {
                int cur = queue.poll();
                compOccIndices.add(cur);
                compVars.addAll(occToVars.get(cur));
                for (String k : occToKeys.get(cur)) {
                    for (int neighbor : keyToOccurrences.getOrDefault(k, List.of())) {
                        if (!visitedOcc[neighbor]) {
                            visitedOcc[neighbor] = true;
                            queue.add(neighbor);
                        }
                    }
                }
            }

            var compOccs = new ArrayList<ConditionOccurrence>(compOccIndices.size());
            for (int idx : compOccIndices) {
                compOccs.add(occurrences.get(idx));
            }
            components.add(new ConditionComponent(
                    components.size(),
                    List.copyOf(compVars),
                    List.copyOf(compOccs)));
        }

        components.sort(Comparator.comparing(c -> c.variables().isEmpty()
                ? ""
                : c.variables().getFirst().kind() + ":" + c.variables().getFirst().name()));
        int maxVars = 0;
        var reindexed = new ArrayList<ConditionComponent>(components.size());
        for (int i = 0; i < components.size(); i++) {
            var c = components.get(i);
            maxVars = Math.max(maxVars, c.variables().size());
            reindexed.add(new ConditionComponent(i, c.variables(), c.occurrences()));
        }
        return new ModelDecomposition(reindexed, allVars.size(), maxVars);
    }

    /**
     * Builds per-component bounded {@link ConfigurationSpace} slices with budget {@code maxComponentVars}
     * (default {@link #MAX_COMPONENT_VARS} = 64), avoiding artificial global truncation when a repository's
     * total variables exceed {@link RepositoryConfigurationSpaceBuilder#MAX_DIMENSIONS}.
     */
    public static List<RepositoryConfigurationSpaceBuilder.Selected> buildComponentSpaces(
            SpringBuildContext build,
            ConfigDataIngestion.Result captured,
            List<ConditionOccurrence> occurrences,
            int maxComponentVars) {
        Objects.requireNonNull(build, "build");
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(occurrences, "occurrences");
        int budget = Math.max(1, maxComponentVars);
        var decomposition = decomposeConditionModel(occurrences);
        if (decomposition.components().isEmpty()) {
            return List.of(RepositoryConfigurationSpaceBuilder.build(build, captured, List.of(), budget));
        }
        var spaces = new ArrayList<RepositoryConfigurationSpaceBuilder.Selected>(decomposition.components().size());
        for (var comp : decomposition.components()) {
            spaces.add(RepositoryConfigurationSpaceBuilder.build(build, captured, comp.occurrences(), budget));
        }
        return List.copyOf(spaces);
    }

    /**
     * Computes sound directed backward {@code COI(target) = Reach^{-1}(target)} slices across passive starter
     * configurations and {@code @Bean} methods.
     */
    public static StarterSlicingResult sliceStarterGraph(
            BytecodeAutoConfigScanner.ScanResult scan,
            BytecodeAutoConfigScanner.UserSpringSummary user,
            int maxSliceVars) {
        return sliceStarterGraph(scan, user, maxSliceVars, Map.of());
    }

    /**
     * Computes sound directed backward {@code COI(target) = Reach^{-1}(target)} slices across passive starter
     * configurations and {@code @Bean} methods with optional explicit supertype/interface assignability edges.
     *
     * <p>Soundness invariants upheld:
     * <ul>
     *   <li>Competing same-type and supertype/interface {@code @Bean} producers are retained (only exact self-bean
     *       edges are excluded), preventing under-approximation on {@code @ConditionalOnMissingBean} /
     *       {@code @ConditionalOnBean} / {@code @ConditionalOnSingleCandidate}.</li>
     *   <li>All {@code @Bean} method parameter types (and {@code @EnableConfigurationProperties} bindings) are
     *       transitively followed to every producer.</li>
     *   <li>Enclosing, nested, and importing {@code @Configuration} guards are followed upward.</li>
     *   <li>Directed backward {@code @AutoConfigureAfter} / {@code @AutoConfigureBefore} predecessors (strictly
     *       upstream, never downstream siblings) are included whenever a configuration or bean carries bean-state
     *       conditions or parameter dependencies, conservatively covering subtype producers even when full
     *       classpath supertype hierarchies are absent.</li>
     * </ul>
     */
    public static StarterSlicingResult sliceStarterGraph(
            BytecodeAutoConfigScanner.ScanResult scan,
            BytecodeAutoConfigScanner.UserSpringSummary user,
            int maxSliceVars,
            Map<String, Set<String>> supertypesByType) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(supertypesByType, "supertypesByType");
        long tStart = System.nanoTime();
        int budget = Math.max(1, maxSliceVars);

        var baselineUndirected = BytecodeAutoConfigScanner.analyzeInteractions(scan, user);
        var detailed = computeDetailedSlices(scan, user, supertypesByType);

        var slices = new ArrayList<DirectedSlice>(detailed.size());
        var sliceSizes = new ArrayList<Integer>(detailed.size());
        int exceeding = 0;
        for (var d : detailed) {
            var directed = d.toDirectedSlice();
            slices.add(directed);
            int vCount = directed.sliceVars();
            sliceSizes.add(vCount);
            if (vCount > budget) {
                exceeding++;
            }
        }

        slices.sort(Comparator.comparingInt(DirectedSlice::sliceVars).reversed()
                .thenComparing(DirectedSlice::targetId));
        sliceSizes.sort(Comparator.naturalOrder());
        int maxDirected = sliceSizes.isEmpty() ? 0 : sliceSizes.getLast();
        double medianDirected = 0.0;
        if (!sliceSizes.isEmpty()) {
            int n = sliceSizes.size();
            medianDirected = (n % 2 == 1)
                    ? sliceSizes.get(n / 2)
                    : (sliceSizes.get((n / 2) - 1) + sliceSizes.get(n / 2)) / 2.0;
        }
        long elapsedMs = Math.max(0L, (System.nanoTime() - tStart) / 1_000_000L);
        return new StarterSlicingResult(
                slices.size(),
                baselineUndirected.g3CoiMetrics().totalStarterVars(),
                baselineUndirected.g3CoiMetrics().maxComponentVars(),
                maxDirected,
                medianDirected,
                exceeding,
                slices.subList(0, Math.min(10, slices.size())),
                elapsedMs);
    }

    /**
     * Computes exact detailed backward COI slices (including sliced configuration FQNs, bean node IDs,
     * and variable IDs) for every selected target bean in the starter graph.
     */
    public static List<DetailedSlice> computeDetailedSlices(
            BytecodeAutoConfigScanner.ScanResult scan,
            BytecodeAutoConfigScanner.UserSpringSummary user,
            Map<String, Set<String>> supertypesByType) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(supertypesByType, "supertypesByType");

        var knownClasses = new HashSet<String>(
                scan.indexedClassFqns().size() + user.extraClasspathFqns().size() + user.userBeanTypeFqns().size() + 16);
        knownClasses.addAll(scan.indexedClassFqns());
        knownClasses.addAll(user.extraClasspathFqns());
        knownClasses.addAll(user.userBeanTypeFqns());

        var configByFqn = new HashMap<String, BytecodeAutoConfigScanner.StarterConfiguration>(scan.configurations().size() * 2);
        for (var cfg : scan.configurations()) {
            configByFqn.putIfAbsent(cfg.classFqn(), cfg);
        }

        // Parent/child config hierarchy (@Import + nested inner @Configuration + enclosing '$' fallback)
        var importedByConfig = new HashMap<String, Set<String>>();
        var childConfigsByParent = new HashMap<String, Set<String>>();
        // Strictly directed backward ordering: laterCfg -> Set<earlierCfg>
        var orderedBefore = new HashMap<String, Set<String>>();
        var enabledPropTypes = new HashSet<String>();
        var producersByType = new HashMap<String, Set<String>>();
        var producersByName = new HashMap<String, Set<String>>();
        var beanDeclaringClass = new HashMap<String, String>();
        var allBeans = new ArrayList<BytecodeAutoConfigScanner.StarterBean>();

        for (var cfg : scan.configurations()) {
            String cfgFqn = cfg.classFqn();
            for (String imp : cfg.importedClasses()) {
                if (configByFqn.containsKey(imp)) {
                    importedByConfig.computeIfAbsent(imp, ignored -> new LinkedHashSet<>()).add(cfgFqn);
                    childConfigsByParent.computeIfAbsent(cfgFqn, ignored -> new LinkedHashSet<>()).add(imp);
                }
            }
            for (String nested : cfg.nestedConfigurationClasses()) {
                if (configByFqn.containsKey(nested)) {
                    importedByConfig.computeIfAbsent(nested, ignored -> new LinkedHashSet<>()).add(cfgFqn);
                    childConfigsByParent.computeIfAbsent(cfgFqn, ignored -> new LinkedHashSet<>()).add(nested);
                }
            }
            int lastDollar = cfgFqn.lastIndexOf('$');
            if (lastDollar > 0) {
                String outer = cfgFqn.substring(0, lastDollar);
                if (configByFqn.containsKey(outer)) {
                    importedByConfig.computeIfAbsent(cfgFqn, ignored -> new LinkedHashSet<>()).add(outer);
                    childConfigsByParent.computeIfAbsent(outer, ignored -> new LinkedHashSet<>()).add(cfgFqn);
                }
            }

            // Record strictly directed @AutoConfigureAfter / @AutoConfigureBefore edges
            for (var ord : cfg.orderings()) {
                String simpleOrd = simpleName(ord.annotationFqn());
                if ("AutoConfigureAfter".equals(simpleOrd)) {
                    for (String target : ord.targetClassFqns()) {
                        if (configByFqn.containsKey(target) && !target.equals(cfgFqn)) {
                            orderedBefore.computeIfAbsent(cfgFqn, ignored -> new LinkedHashSet<>()).add(target);
                        }
                    }
                    for (String target : ord.targetClassNames()) {
                        if (configByFqn.containsKey(target) && !target.equals(cfgFqn)) {
                            orderedBefore.computeIfAbsent(cfgFqn, ignored -> new LinkedHashSet<>()).add(target);
                        }
                    }
                } else if ("AutoConfigureBefore".equals(simpleOrd)) {
                    for (String target : ord.targetClassFqns()) {
                        if (configByFqn.containsKey(target) && !target.equals(cfgFqn)) {
                            orderedBefore.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(cfgFqn);
                        }
                    }
                    for (String target : ord.targetClassNames()) {
                        if (configByFqn.containsKey(target) && !target.equals(cfgFqn)) {
                            orderedBefore.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(cfgFqn);
                        }
                    }
                }
            }

            // Register @Configuration class itself and @EnableConfigurationProperties classes as type producers
            producersByType.computeIfAbsent(cfgFqn, ignored -> new LinkedHashSet<>()).add("cfg:" + cfgFqn);
            for (String propClass : cfg.enabledConfigProperties()) {
                enabledPropTypes.add(propClass);
                producersByType.computeIfAbsent(propClass, ignored -> new LinkedHashSet<>()).add("cfg:" + cfgFqn);
            }

            // Class-level @ConditionalOnMissingBean explicit targets act as polymorphic contract signals
            var cfgMissingBeanTypes = extractExplicitMissingBeanTypes(cfg.conditions());

            for (var bean : cfg.beans()) {
                allBeans.add(bean);
                String beanId = beanNodeId(bean);
                beanDeclaringClass.put(beanId, cfgFqn);
                if (!PRIMITIVE_OR_OBJECT.contains(bean.returnTypeFqn())) {
                    indexTypeAndSupertypes(bean.returnTypeFqn(), beanId, supertypesByType, producersByType);
                }
                // Method-level and class-level @ConditionalOnMissingBean explicit targets signal polymorphic supertype
                for (String missingTarget : extractExplicitMissingBeanTypes(bean.conditions())) {
                    indexTypeAndSupertypes(missingTarget, beanId, supertypesByType, producersByType);
                }
                for (String missingTarget : cfgMissingBeanTypes) {
                    indexTypeAndSupertypes(missingTarget, beanId, supertypesByType, producersByType);
                }
                for (String bName : bean.beanNames()) {
                    producersByName.computeIfAbsent(bName, ignored -> new LinkedHashSet<>()).add(beanId);
                }
            }
        }

        // Precompute directed edges per config node
        var cfgVars = new HashMap<String, Set<String>>(scan.configurations().size() * 2);
        var cfgDeps = new HashMap<String, Set<String>>(scan.configurations().size() * 2);
        for (var cfg : scan.configurations()) {
            String cfgFqn = cfg.classFqn();
            var vars = new TreeSet<String>();
            var deps = new LinkedHashSet<String>();
            boolean hasBeanStateCond = false;
            for (var cond : cfg.conditions()) {
                collectDirectedCondition(
                        cfgFqn, null, null, cond, knownClasses,
                        producersByType, producersByName, beanDeclaringClass, vars, deps);
                if (isBeanStateCondition(cond)) {
                    hasBeanStateCond = true;
                }
            }
            for (String parentCfg : importedByConfig.getOrDefault(cfgFqn, Set.of())) {
                if (configByFqn.containsKey(parentCfg)) {
                    deps.add("cfg:" + parentCfg);
                }
            }
            if (hasBeanStateCond) {
                addDirectedOrderingPredecessors(
                        cfgFqn, null, orderedBefore, importedByConfig, childConfigsByParent, configByFqn, deps);
            }
            String cNode = "cfg:" + cfgFqn;
            cfgVars.put(cNode, vars);
            cfgDeps.put(cNode, deps);
        }

        // Precompute directed edges per @Bean node (including ALL @Bean parameter dependencies!)
        var beanById = new HashMap<String, BytecodeAutoConfigScanner.StarterBean>(allBeans.size() * 2);
        var beanVars = new HashMap<String, Set<String>>(allBeans.size() * 2);
        var beanDeps = new HashMap<String, Set<String>>(allBeans.size() * 2);
        for (var bean : allBeans) {
            String bId = beanNodeId(bean);
            beanById.put(bId, bean);
            var vars = new TreeSet<String>();
            var deps = new LinkedHashSet<String>();
            deps.add("cfg:" + bean.declaringClassFqn());

            boolean hasBeanStateOrParamDep = false;
            for (var cond : bean.conditions()) {
                collectDirectedCondition(
                        bean.declaringClassFqn(), bId, bean.returnTypeFqn(), cond, knownClasses,
                        producersByType, producersByName, beanDeclaringClass, vars, deps);
                if (isBeanStateCondition(cond)) {
                    hasBeanStateOrParamDep = true;
                }
            }

            // Sound @Bean method parameter dependency closure
            for (String rawParam : bean.parameterTypeFqns()) {
                String baseType = stripArraySuffix(rawParam);
                if (PRIMITIVE_OR_OBJECT.contains(baseType) || CONTAINER_INFRASTRUCTURE_TYPES.contains(baseType)) {
                    continue;
                }
                if (ERASED_COLLECTION_OR_PROVIDER_TYPES.contains(baseType)) {
                    // Erased ObjectProvider<T>/List<T>/Optional<T>: record explicit marker (no silent omission)
                    // and conservatively depend on upstream ordered configs.
                    vars.add("erased-param:" + bId + ":" + baseType);
                    hasBeanStateOrParamDep = true;
                    continue;
                }
                for (String pId : producersByType.getOrDefault(baseType, Set.of())) {
                    if (!pId.equals(bId)) {
                        deps.add(pId);
                    }
                }
                if (!enabledPropTypes.contains(baseType)) {
                    vars.add("bean:" + baseType);
                    hasBeanStateOrParamDep = true;
                }
            }

            if (hasBeanStateOrParamDep) {
                addDirectedOrderingPredecessors(
                        bean.declaringClassFqn(), bId, orderedBefore, importedByConfig, childConfigsByParent, configByFqn, deps);
            }

            beanVars.put(bId, vars);
            beanDeps.put(bId, deps);
        }

        // Select targets: user-injected types matched against starter beans when available, else all starter beans
        var targetBeans = new ArrayList<BytecodeAutoConfigScanner.StarterBean>();
        var seenTargetIds = new LinkedHashSet<String>();
        if (!user.userInjectedTypeFqns().isEmpty()) {
            for (String inj : user.userInjectedTypeFqns()) {
                for (String pId : producersByType.getOrDefault(inj, Set.of())) {
                    var b = beanById.get(pId);
                    if (b != null && seenTargetIds.add(pId)) {
                        targetBeans.add(b);
                    }
                }
            }
        }
        if (targetBeans.isEmpty()) {
            targetBeans.addAll(allBeans);
        }

        var detailedSlices = new ArrayList<DetailedSlice>(targetBeans.size());
        for (var target : targetBeans) {
            String startId = beanNodeId(target);
            var visited = new HashSet<String>();
            var bfs = new ArrayDeque<String>();
            var collectedVars = new TreeSet<String>();
            var visitedConfigs = new TreeSet<String>();
            var visitedBeans = new TreeSet<String>();
            bfs.add(startId);
            visited.add(startId);

            while (!bfs.isEmpty()) {
                String cur = bfs.poll();
                if (cur.startsWith("bean:")) {
                    visitedBeans.add(cur);
                    collectedVars.addAll(beanVars.getOrDefault(cur, Set.of()));
                    for (String nxt : beanDeps.getOrDefault(cur, Set.of())) {
                        if (visited.add(nxt)) {
                            bfs.add(nxt);
                        }
                    }
                } else if (cur.startsWith("cfg:")) {
                    visitedConfigs.add(cur.substring(4));
                    collectedVars.addAll(cfgVars.getOrDefault(cur, Set.of()));
                    for (String nxt : cfgDeps.getOrDefault(cur, Set.of())) {
                        if (visited.add(nxt)) {
                            bfs.add(nxt);
                        }
                    }
                }
            }

            detailedSlices.add(new DetailedSlice(
                    startId,
                    target.returnTypeFqn(),
                    visitedConfigs,
                    visitedBeans,
                    List.copyOf(collectedVars)));
        }

        return List.copyOf(detailedSlices);
    }

    public static String beanNodeId(BytecodeAutoConfigScanner.StarterBean bean) {
        return "bean:" + bean.declaringClassFqn() + "#" + bean.methodName() + bean.methodDescriptor();
    }

    private static void indexTypeAndSupertypes(
            String typeFqn,
            String producerId,
            Map<String, Set<String>> supertypesByType,
            Map<String, Set<String>> producersByType) {
        if (typeFqn == null || typeFqn.isBlank() || PRIMITIVE_OR_OBJECT.contains(typeFqn)) {
            return;
        }
        var queue = new ArrayDeque<String>();
        var seen = new HashSet<String>();
        queue.add(typeFqn);
        seen.add(typeFqn);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (PRIMITIVE_OR_OBJECT.contains(cur)) {
                continue;
            }
            producersByType.computeIfAbsent(cur, ignored -> new LinkedHashSet<>()).add(producerId);
            for (String sup : supertypesByType.getOrDefault(cur, Set.of())) {
                if (sup != null && !sup.isBlank() && !PRIMITIVE_OR_OBJECT.contains(sup) && seen.add(sup)) {
                    queue.add(sup);
                }
            }
        }
    }

    private static Set<String> extractExplicitMissingBeanTypes(
            List<BytecodeAutoConfigScanner.ConditionAnnotation> conditions) {
        var targets = new LinkedHashSet<String>();
        for (var cond : conditions) {
            String simple = simpleName(cond.annotationFqn());
            if ("ConditionalOnMissingBean".equals(simple)) {
                targets.addAll(cond.attributes().getOrDefault("value", List.of()));
                targets.addAll(cond.attributes().getOrDefault("type", List.of()));
            }
        }
        return targets;
    }

    private static void addDirectedOrderingPredecessors(
            String cfgFqn,
            String selfBeanId,
            Map<String, Set<String>> orderedBefore,
            Map<String, Set<String>> importedByConfig,
            Map<String, Set<String>> childConfigsByParent,
            Map<String, BytecodeAutoConfigScanner.StarterConfiguration> configByFqn,
            Set<String> depsOut) {
        // Collect this config and its enclosing/importing ancestor configs (since @AutoConfiguration(after=...) lives on the outer config)
        var ancestorQueue = new ArrayDeque<String>();
        var ancestors = new LinkedHashSet<String>();
        ancestorQueue.add(cfgFqn);
        ancestors.add(cfgFqn);
        while (!ancestorQueue.isEmpty()) {
            String cur = ancestorQueue.poll();
            for (String parent : importedByConfig.getOrDefault(cur, Set.of())) {
                if (ancestors.add(parent)) {
                    ancestorQueue.add(parent);
                }
            }
        }

        // Collect all strictly upstream configurations (orderedBefore) and their nested/imported sub-configurations + beans
        var predQueue = new ArrayDeque<String>();
        var visitedPred = new HashSet<String>();
        for (String anc : ancestors) {
            for (String prev : orderedBefore.getOrDefault(anc, Set.of())) {
                if (!ancestors.contains(prev) && visitedPred.add(prev)) {
                    predQueue.add(prev);
                }
            }
        }
        while (!predQueue.isEmpty()) {
            String prevCfg = predQueue.poll();
            var cfgObj = configByFqn.get(prevCfg);
            if (cfgObj == null) {
                continue;
            }
            depsOut.add("cfg:" + prevCfg);
            for (var b : cfgObj.beans()) {
                String pBeanId = beanNodeId(b);
                if (!pBeanId.equals(selfBeanId)) {
                    depsOut.add(pBeanId);
                }
            }
            for (String child : childConfigsByParent.getOrDefault(prevCfg, Set.of())) {
                if (!ancestors.contains(child) && visitedPred.add(child)) {
                    predQueue.add(child);
                }
            }
            // Transitive upstream ordering closure (strictly backward: never follows downstream siblings!)
            for (String upstream : orderedBefore.getOrDefault(prevCfg, Set.of())) {
                if (!ancestors.contains(upstream) && visitedPred.add(upstream)) {
                    predQueue.add(upstream);
                }
            }
        }
    }

    private static boolean isBeanStateCondition(BytecodeAutoConfigScanner.ConditionAnnotation cond) {
        String simple = simpleName(cond.annotationFqn());
        return "ConditionalOnBean".equals(simple)
                || "ConditionalOnMissingBean".equals(simple)
                || "ConditionalOnSingleCandidate".equals(simple);
    }

    private static void collectDirectedCondition(
            String classFqn,
            String selfBeanId,
            String beanReturnType,
            BytecodeAutoConfigScanner.ConditionAnnotation cond,
            Set<String> knownClasses,
            Map<String, Set<String>> producersByType,
            Map<String, Set<String>> producersByName,
            Map<String, String> beanDeclaringClass,
            Set<String> varsOut,
            Set<String> depsOut) {
        String simple = simpleName(cond.annotationFqn());
        if (simple.equals("ConditionalOnProperty") || simple.equals("ConditionalOnBooleanProperty")) {
            addPropertyConditionVars(
                    cond.attributes().getOrDefault("prefix", List.of()),
                    cond.attributes().getOrDefault("name", List.of()),
                    cond.attributes().getOrDefault("value", List.of()),
                    varsOut);
            return;
        }
        if (simple.equals("ConditionalOnBooleanProperties")) {
            addPropertyConditionVars(
                    cond.attributes().getOrDefault("value.prefix", List.of()),
                    cond.attributes().getOrDefault("value.name", List.of()),
                    cond.attributes().getOrDefault("value.value", List.of()),
                    varsOut);
            return;
        }
        if (simple.equals("ConditionalOnThreading")) {
            varsOut.add("prop:spring.threads.virtual.enabled");
            return;
        }
        if (simple.equals("ConditionalOnWebApplication") || simple.equals("ConditionalOnNotWebApplication")) {
            varsOut.add("web:mode");
            return;
        }
        if (simple.equals("ConditionalOnExpression")) {
            for (String expr : cond.attributes().getOrDefault("value", List.of())) {
                BasicSpelEvaluator.propertyKeys(expr).ifPresent(keys -> {
                    for (String k : keys) {
                        if (k != null && !k.isBlank()) {
                            varsOut.add("prop:" + BytecodeAutoConfigScanner.normalizePropertyKey(k.trim()));
                        }
                    }
                });
            }
            varsOut.add("opaque:" + classFqn + "#ConditionalOnExpression");
            return;
        }
        if (simple.equals("Profile")) {
            for (String rawExpr : cond.attributes().getOrDefault("value", List.of())) {
                if (rawExpr == null) {
                    continue;
                }
                for (String token : rawExpr.split("[!&|()\\s]+")) {
                    String clean = token.trim();
                    if (!clean.isEmpty()) {
                        varsOut.add("profile:" + clean);
                    }
                }
            }
            return;
        }
        if (simple.equals("ConditionalOnClass") || simple.equals("ConditionalOnMissingClass")) {
            var targets = new ArrayList<String>();
            targets.addAll(cond.attributes().getOrDefault("value", List.of()));
            targets.addAll(cond.attributes().getOrDefault("name", List.of()));
            for (String t : targets) {
                // Align variable universe 1:1 with BytecodeAutoConfigScanner.registerConditionInCoi:
                // non-JDK classes resolve against closed classpath when knownClasses is non-empty;
                // standard JDK types and open-classpath queries emit class:* variables.
                if (knownClasses.isEmpty() || isStandardJdkType(t)) {
                    varsOut.add("class:" + t);
                }
            }
            return;
        }
        if (simple.equals("ConditionalOnBean")
                || simple.equals("ConditionalOnMissingBean")
                || simple.equals("ConditionalOnSingleCandidate")) {
            var typeTargets = new LinkedHashSet<String>();
            typeTargets.addAll(cond.attributes().getOrDefault("value", List.of()));
            typeTargets.addAll(cond.attributes().getOrDefault("type", List.of()));
            var nameTargets = cond.attributes().getOrDefault("name", List.of());
            var annotationTargets = cond.attributes().getOrDefault("annotation", List.of());
            var containerTargets = cond.attributes().getOrDefault("parameterizedContainer", List.of());
            if (typeTargets.isEmpty()
                    && nameTargets.isEmpty()
                    && annotationTargets.isEmpty()
                    && beanReturnType != null
                    && !PRIMITIVE_OR_OBJECT.contains(beanReturnType)) {
                typeTargets.add(beanReturnType);
            }
            for (String t : typeTargets) {
                varsOut.add("bean:" + t);
                // Soundness fix: exclude ONLY exact self bean (or same class for class-level conditions),
                // NEVER drop competing same-type or subtype producers!
                for (String pId : producersByType.getOrDefault(t, Set.of())) {
                    if (isAllowedProducerTarget(pId, selfBeanId, classFqn, beanDeclaringClass)) {
                        depsOut.add(pId);
                    }
                }
            }
            for (String n : nameTargets) {
                varsOut.add("bean:" + n);
                for (String pId : producersByName.getOrDefault(n, Set.of())) {
                    if (isAllowedProducerTarget(pId, selfBeanId, classFqn, beanDeclaringClass)) {
                        depsOut.add(pId);
                    }
                }
            }
            for (String ann : annotationTargets) {
                varsOut.add("bean-ann:" + ann);
            }
            for (String c : containerTargets) {
                varsOut.add("bean:" + c);
                for (String pId : producersByType.getOrDefault(c, Set.of())) {
                    if (isAllowedProducerTarget(pId, selfBeanId, classFqn, beanDeclaringClass)) {
                        depsOut.add(pId);
                    }
                }
            }
            return;
        }
        if (cond.opaque() || simple.startsWith("Conditional")) {
            varsOut.add("opaque:" + classFqn + "#" + simple);
        }
    }

    private static boolean isAllowedProducerTarget(
            String producerId,
            String selfBeanId,
            String declaringClassFqn,
            Map<String, String> beanDeclaringClass) {
        if (selfBeanId != null) {
            return !producerId.equals(selfBeanId);
        }
        // Class-level @ConditionalOn[Missing]Bean runs before this configuration's own @Bean methods are registered
        if (producerId.equals("cfg:" + declaringClassFqn)) {
            return false;
        }
        return !declaringClassFqn.equals(beanDeclaringClass.get(producerId));
    }

    private static void addPropertyConditionVars(
            List<String> prefixes,
            List<String> nameAttrs,
            List<String> valueAttrs,
            Set<String> varsOut) {
        String prefix = "";
        if (!prefixes.isEmpty()) {
            prefix = prefixes.getFirst().trim();
            while (prefix.endsWith(".")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
        }
        var names = new ArrayList<String>(nameAttrs.size() + valueAttrs.size());
        names.addAll(nameAttrs);
        names.addAll(valueAttrs);
        for (String n : names) {
            if (n != null && !n.isBlank()) {
                String full = prefix.isEmpty() ? n.trim() : prefix + "." + n.trim();
                varsOut.add("prop:" + BytecodeAutoConfigScanner.normalizePropertyKey(full));
            }
        }
    }

    private static String stripArraySuffix(String typeFqn) {
        String t = typeFqn == null ? "" : typeFqn.trim();
        while (t.endsWith("[]")) {
            t = t.substring(0, t.length() - 2);
        }
        return t;
    }

    private static boolean isStandardJdkType(String fqn) {
        return fqn.startsWith("java.") || fqn.startsWith("javax.sql.") || fqn.startsWith("javax.naming.");
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static Set<FiniteDomain.Variable> extractVariables(ConditionExpression root) {
        var vars = new TreeSet<FiniteDomain.Variable>();
        var queue = new ArrayDeque<ConditionExpression>();
        var visited = new HashSet<ConditionExpression.Identity>();
        queue.add(root);
        while (!queue.isEmpty()) {
            var cur = queue.removeFirst();
            if (!visited.add(cur.identity())) {
                continue;
            }
            if (cur.operand() instanceof ConditionExpression.Profile profile) {
                if (!profile.name().isBlank()) {
                    vars.add(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, profile.name()));
                }
            } else if (cur.operand() instanceof ConditionExpression.Property property) {
                for (String key : property.keys()) {
                    if (!key.isBlank()) {
                        vars.add(new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, key));
                    }
                }
            } else if (cur.operand() instanceof ConditionExpression.Web) {
                vars.add(new FiniteDomain.Variable(FiniteDomain.Kind.WEB_MODE, "web-mode"));
            } else if (cur.operand() instanceof ConditionExpression.BasicSpel spel) {
                BasicSpelEvaluator.propertyKeys(spel.expression()).ifPresent(keys -> {
                    for (String k : keys) {
                        if (!k.isBlank()) {
                            vars.add(new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, k));
                        }
                    }
                });
            }
            queue.addAll(cur.children());
        }
        return vars;
    }

    private static Set<String> extractCouplingKeys(ConditionExpression root) {
        var keys = new TreeSet<String>();
        var queue = new ArrayDeque<ConditionExpression>();
        var visited = new HashSet<ConditionExpression.Identity>();
        queue.add(root);
        while (!queue.isEmpty()) {
            var cur = queue.removeFirst();
            if (!visited.add(cur.identity())) {
                continue;
            }
            if (cur.operand() instanceof ConditionExpression.Bean bean) {
                for (String t : bean.types()) {
                    keys.add("bean-type:" + t);
                }
                for (String n : bean.names()) {
                    keys.add("bean-name:" + n);
                }
                for (String a : bean.annotations()) {
                    keys.add("bean-ann:" + a);
                }
            } else if (cur.operand() instanceof ConditionExpression.Opaque opaque) {
                keys.add("opaque:" + opaque.correlationKey().value());
            }
            queue.addAll(cur.children());
        }
        return keys;
    }
}
