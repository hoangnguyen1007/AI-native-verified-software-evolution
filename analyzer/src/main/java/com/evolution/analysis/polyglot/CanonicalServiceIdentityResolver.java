package com.evolution.analysis.polyglot;

import java.util.*;

/**
 * Generic, evidence-backed cross-layer service identity reconciliation and component classification
 * across build descriptors ({@code BUILD}), Spring configuration ({@code SPRING_CONFIG}),
 * container definitions ({@code DOCKERFILE}), Docker Compose ({@code COMPOSE}), and Kubernetes
 * ({@code K8S}).
 *
 * <p>Reconciles multi-layer declarations sharing build context directory, Dockerfile path,
 * normalized artifact suffix, image tail, or exposed port + route prefix into one canonical
 * logical service identity while preserving sorted {@code evidenceLayers} provenance. Classifies
 * test-only modules and non-deployable shared library modules solely from open-standard build and
 * structural signals.
 */
public final class CanonicalServiceIdentityResolver {

    public enum EvidenceLayer {
        BUILD,
        COMPOSE,
        K8S,
        SPRING_CONFIG
    }

    public enum ComponentRole {
        AGGREGATOR,
        DEPLOYABLE_BUILD_COMPONENT,
        SERVICE_COMPONENT,
        SHARED_LIBRARY_MODULE,
        TEST_ONLY_MODULE
    }

    public record BuildModuleDescriptor(
            String nodeId,
            String rawDir,
            String normDir,
            String compId,
            String canonicalName,
            String fallbackName,
            String tool,
            Set<String> languages,
            String packaging,
            boolean executablePackaging,
            boolean descriptorTestProject,
            boolean hasDockerfile,
            String dockerfilePath,
            Set<String> exposedPorts,
            Set<String> routePrefixes) {
    }

    public record DeclarationSignal(
            String normName,
            String buildContextDir,
            String dockerfilePath,
            String containerName,
            String image,
            String imageTail,
            Set<String> exposedPorts,
            Set<String> routePrefixes,
            EvidenceLayer layer,
            boolean infraImageOrName) {
    }

    public record ClassificationSignals(
            boolean isAggregator,
            boolean isTestOnlyDir,
            boolean descriptorTestProject,
            String tool,
            String compId,
            Set<String> languages,
            String packaging,
            boolean executablePackaging,
            boolean hasDockerfile,
            boolean hasComposeWorkload,
            boolean hasK8sWorkload,
            boolean hasSpringRole,
            boolean hasSpringAppName,
            boolean hasServerPort,
            boolean hasEndpointOrRoute,
            boolean hasServiceCallOrRpc,
            boolean hasEventEndpoint,
            int totalLeafBuildCount,
            boolean repoHasDeployableServices,
            boolean repoHasContainerServices) {
    }

    private static final Set<String> TEST_MODULE_DIR_NAMES = Set.of(
            "test", "tests", "spec", "specs", "unit-tests", "unit-test",
            "integration-tests", "integration-test", "e2e-tests", "e2e-test",
            "functional-tests", "acceptance-tests");

    private static final Set<String> JVM_BUILD_TOOLS = Set.of(
            "MAVEN", "GRADLE", "SBT", "ANT");

    private static final Set<String> IDL_SCHEMA_LANGUAGES = Set.of(
            "protobuf", "graphql", "openapi", "thrift", "avro");

    private static final List<String> GENERIC_ROLE_SUFFIXES = List.of(
            "-microservice", "-service", "-server", "-ms", "-app", "-dashboard", "-registry");

    private static final List<String> COLLAPSED_ROLE_SUFFIXES = List.of(
            "microservice", "service", "server", "dashboard", "registry", "app");

    private static final String AMBIGUOUS = "__AMBIGUOUS__";

    private final Map<String, String> nodeIdByDir = new HashMap<>();
    private final Map<String, String> nodeIdByDockerfileDir = new HashMap<>();
    private final Map<String, String> nodeIdByExactName = new HashMap<>();
    private final Map<String, String> uniqueNodeIdByDirBase = new HashMap<>();
    private final Map<String, String> uniqueNodeIdByBaseToken = new HashMap<>();
    private final Map<String, String> uniqueNodeIdBySuffix = new HashMap<>();
    private final Map<String, String> uniqueNodeIdByPortRoute = new HashMap<>();
    private final Map<String, String> uniqueNodeIdByPort = new HashMap<>();
    private final Map<String, String> nodeIdByImageTail = new HashMap<>();
    private final Map<String, EnumSet<EvidenceLayer>> layersByNodeId = new TreeMap<>();
    private final Set<String> dockerfileNodeIds = new HashSet<>();
    private final Set<String> codeBuildNodeIds = new HashSet<>();
    private final Map<String, String> canonicalNameByNodeId = new HashMap<>();
    private String sharedDeclPrefix = "";

    public CanonicalServiceIdentityResolver() {
    }

    /** Registers a leaf code/build component into all O(1) deterministic reconciliation indexes. */
    public void registerBuildComponent(BuildModuleDescriptor module) {
        if (module == null || module.nodeId() == null || module.nodeId().isEmpty()) {
            return;
        }
        String nodeId = module.nodeId();
        codeBuildNodeIds.add(nodeId);
        canonicalNameByNodeId.put(nodeId, module.canonicalName());
        recordLayer(nodeId, EvidenceLayer.BUILD);
        if (module.hasDockerfile()) {
            dockerfileNodeIds.add(nodeId);
        }

        if (!module.normDir().isEmpty()) {
            nodeIdByDir.putIfAbsent(module.normDir(), nodeId);
        }
        if (module.dockerfilePath() != null && !module.dockerfilePath().isEmpty()) {
            String dfDir = parentDirOf(module.dockerfilePath());
            if (!dfDir.isEmpty()) {
                nodeIdByDockerfileDir.putIfAbsent(dfDir, nodeId);
            }
        }
        if (module.hasDockerfile() && !module.normDir().isEmpty()) {
            nodeIdByDockerfileDir.putIfAbsent(module.normDir(), nodeId);
        }

        indexNameAliases(nodeId, module.canonicalName(), module.fallbackName(), module.normDir());

        for (String port : module.exposedPorts()) {
            if (port != null && !port.isEmpty()) {
                putUnique(uniqueNodeIdByPort, port, nodeId);
                for (String route : module.routePrefixes()) {
                    String normRoute = normalizeRoutePrefix(route);
                    if (!normRoute.isEmpty()) {
                        putUnique(uniqueNodeIdByPortRoute, port + "|" + normRoute, nodeId);
                    }
                }
            }
        }
    }

    /** Updates indexes when a build component acquires a canonical Spring/Compose service identity. */
    public void updateCanonicalName(String nodeId, String canonicalName, String dirPrefix, EvidenceLayer layer) {
        if (nodeId == null || nodeId.isEmpty()) {
            return;
        }
        if (layer != null) {
            recordLayer(nodeId, layer);
        }
        if (canonicalName != null && !canonicalName.isEmpty()) {
            canonicalNameByNodeId.put(nodeId, canonicalName);
            indexNameAliases(nodeId, canonicalName, "", dirPrefix);
        }
    }

    /** Registers an infra/non-code component so subsequent layers (e.g. K8s + Compose) unify into one node. */
    public void registerInfraComponent(String nodeId, String canonicalName, String buildDir,
            String imageTail, Set<String> exposedPorts, EvidenceLayer layer) {
        if (nodeId == null || nodeId.isEmpty()) {
            return;
        }
        canonicalNameByNodeId.putIfAbsent(nodeId, canonicalName);
        if (layer != null) {
            recordLayer(nodeId, layer);
        }
        if (canonicalName != null && !canonicalName.isEmpty()) {
            nodeIdByExactName.putIfAbsent(canonicalName, nodeId);
            putUnique(uniqueNodeIdBySuffix, canonicalName, nodeId);
        }
        if (buildDir != null && !buildDir.isEmpty()) {
            nodeIdByDir.putIfAbsent(buildDir, nodeId);
        }
        if (imageTail != null && !imageTail.isEmpty()) {
            nodeIdByImageTail.putIfAbsent(imageTail, nodeId);
        }
        if (exposedPorts != null) {
            for (String p : exposedPorts) {
                if (p != null && !p.isEmpty()) {
                    putUnique(uniqueNodeIdByPort, p, nodeId);
                }
            }
        }
    }

    /** Computes a data-driven shared hyphen prefix (e.g. {@code "app-"}) across sibling declaration names. */
    public void observeDeclarationNames(Collection<String> normalizedNames) {
        this.sharedDeclPrefix = detectSharedPrefix(normalizedNames);
    }

    public String sharedDeclPrefix() {
        return sharedDeclPrefix;
    }

    /** Records an evidenced layer onto a reconciled logical service/infra node. */
    public void recordLayer(String nodeId, EvidenceLayer layer) {
        if (nodeId == null || nodeId.isEmpty() || layer == null) {
            return;
        }
        layersByNodeId.computeIfAbsent(nodeId, k -> EnumSet.noneOf(EvidenceLayer.class)).add(layer);
    }

    /** Returns sorted deterministic evidence layer names (e.g. {@code ["BUILD", "COMPOSE", "K8S"]}). */
    public List<String> evidenceLayers(String nodeId) {
        var set = layersByNodeId.get(nodeId);
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<String>(set.size());
        for (EvidenceLayer layer : set) {
            out.add(layer.name());
        }
        Collections.sort(out);
        return List.copyOf(out);
    }

    public boolean hasLayer(String nodeId, EvidenceLayer layer) {
        var set = layersByNodeId.get(nodeId);
        return set != null && set.contains(layer);
    }

    public boolean hasDockerfile(String nodeId) {
        return nodeId != null && dockerfileNodeIds.contains(nodeId);
    }

    /**
     * Resolves a declarative workload (Compose / K8s / Proxy) against registered logical services
     * using O(1) indexed signals: {@code buildContextDir | dockerfilePath | exactName |
     * normalizedArtifactSuffix | imageTail | exposedPort + routePrefix}.
     */
    public Optional<String> resolveDeclaration(DeclarationSignal signal) {
        if (signal == null) {
            return Optional.empty();
        }
        // 1. Build context directory (exact, enclosing parent, or unique basename)
        if (signal.buildContextDir() != null && !signal.buildContextDir().isEmpty()) {
            String byDir = resolveNearestDir(signal.buildContextDir(), nodeIdByDir);
            if (byDir != null) {
                return Optional.of(byDir);
            }
            if (!signal.buildContextDir().contains("/")) {
                String byBase = getUnique(uniqueNodeIdByDirBase, normalizeName(signal.buildContextDir()));
                if (byBase != null) {
                    return Optional.of(byBase);
                }
            }
        }
        // 2. Dockerfile path / parent directory
        if (signal.dockerfilePath() != null && !signal.dockerfilePath().isEmpty()) {
            String dfDir = parentDirOf(signal.dockerfilePath());
            String byDf = resolveNearestDir(dfDir.isEmpty() ? signal.dockerfilePath() : dfDir, nodeIdByDockerfileDir);
            if (byDf != null) {
                return Optional.of(byDf);
            }
        }
        // 3. Exact normalized name or container name
        String norm = signal.normName() == null ? "" : signal.normName();
        if (!norm.isEmpty()) {
            String exact = nodeIdByExactName.get(norm);
            if (exact != null) {
                return Optional.of(exact);
            }
        }
        String container = signal.containerName() == null ? "" : signal.containerName();
        if (!container.isEmpty()) {
            String byContainer = nodeIdByExactName.get(container);
            if (byContainer != null) {
                return Optional.of(byContainer);
            }
        }
        // 4. Cross-layer shared image tail (reconciles e.g. Compose "db" + K8s "app-db" sharing org/app-db,
        // while NEVER collapsing same-layer multi-node clusters like consul + consul3 sharing image "consul").
        String imageTail = signal.imageTail() == null ? "" : signal.imageTail();
        if (!imageTail.isEmpty()) {
            String byImgNode = nodeIdByImageTail.get(imageTail);
            if (byImgNode != null && signal.layer() != null && !hasLayer(byImgNode, signal.layer())) {
                String existingName = canonicalNameByNodeId.getOrDefault(byImgNode, "");
                String strippedNorm = stripSharedPrefix(norm, sharedDeclPrefix);
                if (existingName.equals(norm) || existingName.equals(strippedNorm)
                        || norm.endsWith("-" + existingName) || existingName.endsWith("-" + norm)) {
                    return Optional.of(byImgNode);
                }
            }
            if (!signal.infraImageOrName()) {
                String byCodeName = nodeIdByExactName.get(imageTail);
                if (byCodeName != null && codeBuildNodeIds.contains(byCodeName)) {
                    return Optional.of(byCodeName);
                }
            }
        }
        // 5. Normalized artifact suffix (works for reverse-DNS modules & shared-prefix K8s names!)
        if (!norm.isEmpty()) {
            // Direct suffix match against registered code modules (even if name contains broker/db token
            // when the multi-segment artifact suffix uniquely names an explicit code module).
            String byExactCodeSuffix = getUnique(uniqueNodeIdBySuffix, norm);
            if (byExactCodeSuffix != null && codeBuildNodeIds.contains(byExactCodeSuffix)
                    && (!signal.infraImageOrName() || norm.contains("-"))) {
                return Optional.of(byExactCodeSuffix);
            }
            String stripped = stripSharedPrefix(norm, sharedDeclPrefix);
            if (!stripped.equals(norm) && stripped.length() >= 2) {
                String exactStripped = nodeIdByExactName.get(stripped);
                if (exactStripped != null) {
                    return Optional.of(exactStripped);
                }
                String suffixStripped = getUnique(uniqueNodeIdBySuffix, stripped);
                if (suffixStripped != null) {
                    return Optional.of(suffixStripped);
                }
            }
            if (!signal.infraImageOrName()) {
                String byToken = getUnique(uniqueNodeIdByBaseToken, canonicalBaseToken(norm));
                if (byToken != null) {
                    return Optional.of(byToken);
                }
                if (!stripped.equals(norm) && stripped.length() >= 2) {
                    String byStrippedToken = getUnique(uniqueNodeIdByBaseToken, canonicalBaseToken(stripped));
                    if (byStrippedToken != null) {
                        return Optional.of(byStrippedToken);
                    }
                }
            }
        }
        if (!imageTail.isEmpty()) {
            String strippedImg = stripSharedPrefix(imageTail, sharedDeclPrefix);
            if (!strippedImg.equals(imageTail) && strippedImg.length() >= 2) {
                String byStrippedImg = nodeIdByExactName.get(strippedImg);
                if (byStrippedImg != null) {
                    return Optional.of(byStrippedImg);
                }
                String byImgSuffix = getUnique(uniqueNodeIdBySuffix, strippedImg);
                if (byImgSuffix != null && codeBuildNodeIds.contains(byImgSuffix)) {
                    return Optional.of(byImgSuffix);
                }
            }
        }
        // 6. Exposed port + route prefix reconciliation
        if (signal.exposedPorts() != null && signal.routePrefixes() != null
                && !signal.exposedPorts().isEmpty() && !signal.routePrefixes().isEmpty()) {
            String matched = null;
            for (String port : signal.exposedPorts()) {
                for (String route : signal.routePrefixes()) {
                    String key = port + "|" + normalizeRoutePrefix(route);
                    String candidate = getUnique(uniqueNodeIdByPortRoute, key);
                    if (candidate != null) {
                        if (matched != null && !matched.equals(candidate)) {
                            matched = null;
                            break;
                        }
                        matched = candidate;
                    }
                }
            }
            if (matched != null) {
                return Optional.of(matched);
            }
        }
        return Optional.empty();
    }

    /** O(1) unique lookup by canonical base token (replacing O(N) scans over leaf build services). */
    public Optional<String> findUniqueByBaseToken(String raw) {
        String tok = canonicalBaseToken(raw);
        if (tok.length() < 2) {
            return Optional.empty();
        }
        return Optional.ofNullable(getUnique(uniqueNodeIdByBaseToken, tok));
    }

    /** O(1) unique lookup by normalized hyphen/dot suffix. */
    public Optional<String> findUniqueBySuffix(String norm) {
        if (norm == null || norm.length() < 2) {
            return Optional.empty();
        }
        return Optional.ofNullable(getUnique(uniqueNodeIdBySuffix, norm));
    }

    /** O(1) unique lookup by module directory basename. */
    public Optional<String> findUniqueByDirBasename(String basename) {
        if (basename == null || basename.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(getUnique(uniqueNodeIdByDirBase, normalizeName(basename)));
    }

    /**
     * Classifies a build component into {@link ComponentRole} strictly from open-standard build,
     * container, and structural evidence (zero hardcoded benchmark/directory names).
     */
    public static ComponentRole classifyBuildComponent(ClassificationSignals s) {
        if (s.isAggregator()) {
            return ComponentRole.AGGREGATOR;
        }
        if (s.isTestOnlyDir() || s.descriptorTestProject()) {
            return ComponentRole.TEST_ONLY_MODULE;
        }
        boolean hasRuntimeSignal = s.hasComposeWorkload()
                || s.hasK8sWorkload()
                || s.hasSpringRole()
                || s.hasSpringAppName()
                || s.hasServerPort()
                || s.hasEndpointOrRoute()
                || s.hasServiceCallOrRpc()
                || s.hasEventEndpoint();

        // Elevate container-backed services (Dockerfile + K8s workload) without Maven/Gradle to SERVICE_COMPONENT.
        if (s.hasDockerfile() && s.hasK8sWorkload() && !isJvmBuildTool(s.tool())) {
            return ComponentRole.SERVICE_COMPONENT;
        }

        // Classify shared libraries / schema-only modules in multi-module repositories.
        if (s.totalLeafBuildCount() >= 2) {
            String pkg = s.packaging() == null ? "" : s.packaging().toLowerCase(Locale.ROOT);
            // Non-deployed POM utility/base-image module (even if a base Dockerfile declares EXPOSE).
            if ("pom".equals(pkg) && !s.hasComposeWorkload() && !s.hasK8sWorkload()
                    && !s.hasSpringRole() && !s.hasSpringAppName()
                    && !s.hasEndpointOrRoute() && !s.hasServiceCallOrRpc() && !s.hasEventEndpoint()
                    && s.repoHasDeployableServices()) {
                return ComponentRole.SHARED_LIBRARY_MODULE;
            }
            if (!hasRuntimeSignal) {
                boolean isPlain = "PLAIN".equalsIgnoreCase(s.tool())
                        || (s.compId() != null && s.compId().toUpperCase(Locale.ROOT).startsWith("PLAIN:"));

                // Case A: Non-deployable library JAR / .NET class library in a multi-module service repo.
                boolean libraryPackaging = ("jar".equals(pkg) && !s.executablePackaging() && !s.hasDockerfile())
                        || ("library".equals(pkg) && !s.executablePackaging() && !s.hasDockerfile());
                if (libraryPackaging && s.repoHasDeployableServices()) {
                    return ComponentRole.SHARED_LIBRARY_MODULE;
                }

                // Case B: IDL / schema-only plain directory (e.g. standalone .proto / .graphql definitions).
                if (isPlain && !s.hasDockerfile() && !s.languages().isEmpty()
                        && s.languages().stream().allMatch(l -> IDL_SCHEMA_LANGUAGES.contains(l.toLowerCase(Locale.ROOT)))) {
                    return ComponentRole.SHARED_LIBRARY_MODULE;
                }

                // Case C: Non-service plain script/helper directory in a repo with containerized/K8s services.
                if (isPlain && !s.hasDockerfile() && s.repoHasContainerServices()) {
                    return ComponentRole.SHARED_LIBRARY_MODULE;
                }
            }
        }

        return ComponentRole.DEPLOYABLE_BUILD_COMPONENT;
    }

    public static boolean isTestOnlyModuleDir(String normDir) {
        if (normDir == null || normDir.isEmpty()) {
            return false;
        }
        String base = dirBasename(normDir).toLowerCase(Locale.ROOT);
        return TEST_MODULE_DIR_NAMES.contains(base)
                || base.endsWith("-tests") || base.endsWith("-test")
                || base.endsWith(".tests") || base.endsWith(".test")
                || base.endsWith("_tests") || base.endsWith("_test");
    }

    public static boolean isJvmBuildTool(String tool) {
        return tool != null && JVM_BUILD_TOOLS.contains(tool.toUpperCase(Locale.ROOT));
    }

    /** Data-driven detection of a common hyphenated project prefix shared by >= 2 sibling names. */
    public static String detectSharedPrefix(Collection<String> normalizedNames) {
        if (normalizedNames == null || normalizedNames.size() < 2) {
            return "";
        }
        var unique = new TreeSet<String>();
        for (String n : normalizedNames) {
            if (n != null && n.contains("-")) {
                unique.add(n);
            }
        }
        if (unique.size() < 2) {
            return "";
        }
        var counts = new TreeMap<String, Integer>();
        for (String name : unique) {
            int dash = name.indexOf('-');
            if (dash >= 2 && dash + 2 <= name.length()) {
                String prefix = name.substring(0, dash + 1);
                counts.merge(prefix, 1, Integer::sum);
            }
        }
        String best = "";
        int bestCount = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() >= 2 && (e.getValue() > bestCount
                    || (e.getValue() == bestCount && e.getKey().length() > best.length()))) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    public static String stripSharedPrefix(String norm, String sharedPrefix) {
        if (norm == null || sharedPrefix == null || sharedPrefix.isEmpty()) {
            return norm == null ? "" : norm;
        }
        if (norm.startsWith(sharedPrefix) && norm.length() > sharedPrefix.length() + 1) {
            return norm.substring(sharedPrefix.length());
        }
        return norm;
    }

    /** O(depth) longest-prefix directory match without scanning all build directories. */
    public static <V> V resolveNearestDir(String path, Map<String, V> mapByDir) {
        if (path == null || path.isEmpty() || mapByDir.isEmpty()) {
            return null;
        }
        String clean = path.replace('\\', '/').trim();
        while (clean.startsWith("./")) {
            clean = clean.substring(2);
        }
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isEmpty() || ".".equals(clean)) {
            return mapByDir.get("");
        }
        V exact = mapByDir.get(clean);
        if (exact != null) {
            return exact;
        }
        int slash = clean.lastIndexOf('/');
        while (slash > 0) {
            String prefix = clean.substring(0, slash);
            V found = mapByDir.get(prefix);
            if (found != null) {
                return found;
            }
            slash = clean.lastIndexOf('/', slash - 1);
        }
        return null;
    }

    private void indexNameAliases(String nodeId, String canonicalName, String fallbackName, String normDir) {
        if (canonicalName != null && !canonicalName.isEmpty()) {
            nodeIdByExactName.putIfAbsent(canonicalName, nodeId);
            indexSuffixes(nodeId, canonicalName);
            String tok = canonicalBaseToken(canonicalName);
            if (tok.length() >= 2) {
                putUnique(uniqueNodeIdByBaseToken, tok, nodeId);
            }
        }
        if (fallbackName != null && !fallbackName.isEmpty()) {
            nodeIdByExactName.putIfAbsent(fallbackName, nodeId);
            indexSuffixes(nodeId, fallbackName);
            String tok = canonicalBaseToken(fallbackName);
            if (tok.length() >= 2) {
                putUnique(uniqueNodeIdByBaseToken, tok, nodeId);
            }
        }
        if (normDir != null && !normDir.isEmpty()) {
            String dirBase = normalizeName(dirBasename(normDir));
            if (!dirBase.isEmpty()) {
                putUnique(uniqueNodeIdByDirBase, dirBase, nodeId);
                indexSuffixes(nodeId, dirBase);
                String tok = canonicalBaseToken(dirBase);
                if (tok.length() >= 2) {
                    putUnique(uniqueNodeIdByBaseToken, tok, nodeId);
                }
            }
        }
    }

    private void indexSuffixes(String nodeId, String rawName) {
        String dashName = normalizeName(rawName).replace('.', '-');
        if (dashName.isEmpty()) {
            return;
        }
        putUnique(uniqueNodeIdBySuffix, dashName, nodeId);
        int dash = dashName.indexOf('-');
        while (dash >= 0 && dash + 1 < dashName.length()) {
            String suffix = dashName.substring(dash + 1);
            if (suffix.length() >= 2) {
                putUnique(uniqueNodeIdBySuffix, suffix, nodeId);
            }
            dash = dashName.indexOf('-', dash + 1);
        }
    }

    private static void putUnique(Map<String, String> index, String key, String nodeId) {
        if (key == null || key.isEmpty()) {
            return;
        }
        String existing = index.get(key);
        if (existing == null) {
            index.put(key, nodeId);
        } else if (!existing.equals(nodeId)) {
            index.put(key, AMBIGUOUS);
        }
    }

    private static String getUnique(Map<String, String> index, String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String val = index.get(key);
        return (val == null || AMBIGUOUS.equals(val)) ? null : val;
    }

    public static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        var sb = new StringBuilder(s.length());
        boolean prevDash = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.') {
                sb.append(c);
                prevDash = false;
            } else if (!prevDash && sb.length() > 0) {
                sb.append('-');
                prevDash = true;
            }
        }
        while (!sb.isEmpty() && (sb.charAt(sb.length() - 1) == '-' || sb.charAt(sb.length() - 1) == '.')) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    public static String canonicalBaseToken(String raw) {
        String s = normalizeName(raw);
        for (String suf : GENERIC_ROLE_SUFFIXES) {
            if (s.endsWith(suf) && s.length() > suf.length() + 1) {
                s = s.substring(0, s.length() - suf.length());
                break;
            }
        }
        String collapsed = s.replace("-", "");
        for (String suf : COLLAPSED_ROLE_SUFFIXES) {
            if (collapsed.endsWith(suf) && collapsed.length() > suf.length() + 2) {
                collapsed = collapsed.substring(0, collapsed.length() - suf.length());
                break;
            }
        }
        return collapsed;
    }

    private static String normalizeRoutePrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith("*") || s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.startsWith("/") ? s : "/" + s;
    }

    private static String dirBasename(String normDir) {
        if (normDir == null || normDir.isEmpty()) {
            return "";
        }
        int slash = normDir.lastIndexOf('/');
        return slash >= 0 ? normDir.substring(slash + 1) : normDir;
    }

    private static String parentDirOf(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String clean = path.replace('\\', '/').trim();
        while (clean.startsWith("./")) {
            clean = clean.substring(2);
        }
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        int slash = clean.lastIndexOf('/');
        return slash > 0 ? clean.substring(0, slash) : "";
    }
}
