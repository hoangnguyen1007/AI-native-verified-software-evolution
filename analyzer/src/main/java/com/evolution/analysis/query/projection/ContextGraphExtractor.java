package com.evolution.analysis.query.projection;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery;

import java.util.*;

/**
 * Fast, read-only O(V + E) extractor over one context of an immutable {@link KnowledgeGraph}.
 *
 * <p>Shared by {@link GraphProjectionService#projectMultiScale} and {@link DsmMatrixBuilder}
 * to avoid duplicating node/package/module/edge/TrustTier extraction and to scale cleanly to
 * 5,000–50,000+ node graphs without mutating {@link KnowledgeGraph} or hitting query step caps.
 */
final class ContextGraphExtractor {

    private static final Set<String> META_NODE_KINDS = Set.of(
            "CAPABILITY_GAP",
            "HISTORICAL_CAPABILITY_GAP",
            "ACQUISITION_ATTEMPT",
            "PROVIDER_CONFLICT",
            "TRUTH_REGION",
            "REGION_FORMULA",
            "CONFIGURATION_WORLD",
            "CONFIGURATION_WITNESS",
            "WITNESS_REPLAY",
            "CONDITIONAL_FACT",
            "CONFIGURATION_CONDITION",
            "CONDITION_EXPRESSION",
            "SOURCE_ROOT"
    );

    private static final Set<String> STRUCTURAL_EDGE_KINDS = Set.of(
            "graph.contains",
            "graph.declares",
            "spring.declares-injection",
            "graph.evidence",
            "graph.in-region"
    );

    private static final Set<String> SPRING_PRIMARY_KINDS = Set.of(
            "BEAN_PRODUCER",
            "BEAN_DEFINITION_CANDIDATE",
            "ENDPOINT",
            "TYPE"
    );

    record ExtractedEntity(
            String id,
            String kind,
            String canonicalName,
            String qualifiedName,
            String spelling,
            String shortLabel,
            String packageId,
            String packageName,
            String moduleId,
            String moduleName,
            String origin,
            Optional<String> sourceSet,
            TrustTier trustTier
    ) {
        boolean isProject() {
            return origin == null || origin.isBlank() || "PROJECT".equals(origin);
        }

        ArchitectureQuery.EntityView toEntityView(String context) {
            return new ArchitectureQuery.EntityView(
                    id,
                    context,
                    kind,
                    canonicalName.isEmpty() ? Optional.empty() : Optional.of(canonicalName),
                    qualifiedName.isEmpty() ? Optional.empty() : Optional.of(qualifiedName),
                    spelling.isEmpty() ? Optional.empty() : Optional.of(spelling),
                    moduleId.isEmpty() ? Optional.empty() : Optional.of(moduleId),
                    origin.isEmpty() ? Optional.empty() : Optional.of(origin),
                    sourceSet,
                    List.of()
            );
        }
    }

    record ExtractedEdge(
            String source,
            String target,
            String primaryKind,
            String classification,
            int supportCount,
            TrustTier trustTier,
            Set<String> rawEdgeIds
    ) {}

    record ExtractedContext(
            String context,
            String contextFamily,
            Map<String, ExtractedEntity> allEntities,
            List<ExtractedEntity> projectEntities,
            List<ExtractedEntity> externalEntities,
            List<ExtractedEdge> allEdges,
            List<ExtractedEdge> projectEdges
    ) {}

    private ContextGraphExtractor() {}

    static ExtractedContext extract(KnowledgeGraph graph, String context, Runnable checkpoint) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(context, "context");
        Runnable tick = checkpoint == null ? () -> {} : checkpoint;
        tick.run();

        KnowledgeGraph.Context ctx = graph.contexts().stream()
                .filter(c -> c.identity().equals(context))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Foreign context: " + context));

        String contextFamily = graph.evidence(ctx.evidence())
                .map(GraphEvidence::family)
                .orElse("unknown");
        TrustTier contextDefaultTier = defaultTierForContext(contextFamily);

        var evidenceCache = new HashMap<ContentDigest, Map<String, Object>>();
        var nodesById = new HashMap<String, KnowledgeGraph.Node>();
        var contextNodes = graph.nodes(context);
        for (int i = 0; i < contextNodes.size(); i++) {
            if ((i & 4095) == 0) tick.run();
            var n = contextNodes.get(i);
            nodesById.put(n.identity(), n);
        }

        // 1. Collect structural containment and Spring ownership from context edges
        var parentOf = new HashMap<String, String>(Math.max(16, contextNodes.size()));
        var springOwnerOf = new HashMap<String, String>();
        var contextEdges = graph.edges(context);
        for (int i = 0; i < contextEdges.size(); i++) {
            if ((i & 8191) == 0) tick.run();
            var edge = contextEdges.get(i);
            if (edge.target().state() != KnowledgeGraph.TargetState.RESOLVED
                    || edge.target().identities().isEmpty()) {
                continue;
            }
            String target = edge.target().identities().getFirst();
            if ("graph.contains".equals(edge.kind()) || "graph.declares".equals(edge.kind())) {
                parentOf.putIfAbsent(target, edge.source());
            } else if ("spring.declares-injection".equals(edge.kind())) {
                springOwnerOf.putIfAbsent(target, edge.source());
            }
        }

        // 2. Pre-extract module names & package names from MODULE / PACKAGE nodes
        var moduleNames = new HashMap<String, String>();
        var packageNames = new HashMap<String, String>();
        var documentInfo = new HashMap<String, DocumentInfo>();

        for (int i = 0; i < contextNodes.size(); i++) {
            if ((i & 4095) == 0) tick.run();
            var node = contextNodes.get(i);
            String kind = node.kind();
            if ("MODULE".equals(kind)) {
                String modName = extractModuleLabel(graph, node, evidenceCache);
                moduleNames.put(node.identity(), modName);
            } else if ("PACKAGE".equals(kind)) {
                String pkgName = extractPackageLabel(graph, node, evidenceCache);
                packageNames.put(node.identity(), pkgName);
            } else if ("SOURCE_DOCUMENT".equals(kind)) {
                documentInfo.put(node.identity(), extractDocumentInfo(graph, node, evidenceCache));
            }
        }

        // Context-level fallback module / sourceSet from analysis.manifest or spring.context
        String contextDefaultModuleId = "";
        String contextDefaultModuleName = "";
        Optional<String> contextDefaultSourceSet = Optional.empty();
        var ctxEv = graph.evidence(ctx.evidence()).orElse(null);
        if (ctxEv != null) {
            var payload = parseEvidence(ctxEv, evidenceCache);
            if (payload.get("build") instanceof Map<?, ?> buildObj) {
                var build = GraphJson.object(buildObj);
                if (build.get("moduleSourceSet") instanceof Map<?, ?> mssObj) {
                    var mss = GraphJson.object(mssObj);
                    if (mss.get("module") instanceof String m && !m.isBlank()) {
                        contextDefaultModuleId = m;
                    }
                    if (mss.get("sourceSet") instanceof String ss && !ss.isBlank()) {
                        contextDefaultSourceSet = Optional.of(ss);
                    }
                }
            }
            if ("analysis.manifest".equals(contextFamily)) {
                if (payload.get("configuration") instanceof Map<?, ?> cfgObj) {
                    var cfg = GraphJson.object(cfgObj);
                    if (cfg.get("values") instanceof Map<?, ?> valsObj) {
                        var vals = GraphJson.object(valsObj);
                        if (vals.get("java.module") instanceof String m && !m.isBlank()) {
                            contextDefaultModuleId = m;
                        }
                        if (vals.get("java.source-set") instanceof String ss && !ss.isBlank()) {
                            contextDefaultSourceSet = Optional.of(ss);
                        }
                    }
                }
                if (payload.get("modules") instanceof List<?> modules) {
                    for (Object item : modules) {
                        if (!(item instanceof Map<?, ?> rowObj)) continue;
                        var row = GraphJson.object(rowObj);
                        String id = optString(row, "identity");
                        String path = optString(row, "path");
                        if (!id.isEmpty() && !path.isEmpty()) {
                            moduleNames.putIfAbsent(id, path);
                            if (id.equals(contextDefaultModuleId)) {
                                contextDefaultModuleName = path;
                            }
                        }
                    }
                }
            }
        }
        if (contextDefaultModuleName.isEmpty() && !contextDefaultModuleId.isEmpty()) {
            contextDefaultModuleName = moduleNames.getOrDefault(contextDefaultModuleId, shortHashLabel(contextDefaultModuleId));
        }

        // 3. Determine primary entity set for this context
        var primaryNodes = selectPrimaryNodes(contextNodes, contextFamily);
        var allEntities = new LinkedHashMap<String, ExtractedEntity>(Math.max(16, primaryNodes.size() * 2));
        var projectEntities = new ArrayList<ExtractedEntity>(primaryNodes.size());
        var externalEntities = new ArrayList<ExtractedEntity>();

        for (int i = 0; i < primaryNodes.size(); i++) {
            if ((i & 2047) == 0) tick.run();
            var node = primaryNodes.get(i);
            var extracted = extractEntity(
                    graph,
                    node,
                    nodesById,
                    parentOf,
                    moduleNames,
                    packageNames,
                    documentInfo,
                    contextDefaultModuleId,
                    contextDefaultModuleName,
                    contextDefaultSourceSet,
                    contextDefaultTier,
                    evidenceCache);
            allEntities.put(extracted.id(), extracted);
            if (extracted.isProject()) {
                projectEntities.add(extracted);
            } else {
                externalEntities.add(extracted);
            }
        }

        // 4. Extract & aggregate directed dependency edges between primary entities
        record MutableEdge(
                String source,
                String target,
                TreeSet<String> kinds,
                TreeSet<String> rawIds,
                int supportCount,
                TrustTier trustTier,
                boolean anyConditional,
                boolean anyUnresolved
        ) {}
        var edgeAcc = new TreeMap<String, MutableEdge>();

        for (int i = 0; i < contextEdges.size(); i++) {
            if ((i & 4095) == 0) tick.run();
            var edge = contextEdges.get(i);
            if (STRUCTURAL_EDGE_KINDS.contains(edge.kind())) {
                continue;
            }
            if (edge.target().identities().isEmpty()) {
                continue;
            }
            String resolvedSource = liftToPrimary(edge.source(), allEntities, parentOf, springOwnerOf);
            if (resolvedSource == null) continue;
            boolean sourceLifted = !resolvedSource.equals(edge.source());

            TrustTier edgeTier = classifyEdgeTrustTier(graph, edge, contextDefaultTier, evidenceCache);
            boolean conditional = edge.region().isPresent();
            boolean unresolved = edge.target().state() != KnowledgeGraph.TargetState.RESOLVED;

            for (String rawTarget : edge.target().identities()) {
                String resolvedTarget = liftToPrimary(rawTarget, allEntities, parentOf, springOwnerOf);
                if (resolvedTarget == null) continue;
                boolean targetLifted = !resolvedTarget.equals(rawTarget);
                // Ignore intra-type member-to-member calls lifted onto the same owning TYPE;
                // preserve explicit direct self-loops on primary entities.
                if (resolvedSource.equals(resolvedTarget) && (sourceLifted || targetLifted)) {
                    continue;
                }
                String key = resolvedSource + "|" + resolvedTarget;
                MutableEdge prev = edgeAcc.get(key);
                if (prev == null) {
                    var kinds = new TreeSet<String>();
                    kinds.add(edge.kind());
                    var rawIds = new TreeSet<String>();
                    rawIds.add(edge.identity());
                    edgeAcc.put(key, new MutableEdge(
                            resolvedSource,
                            resolvedTarget,
                            kinds,
                            rawIds,
                            1,
                            edgeTier,
                            conditional,
                            unresolved));
                } else {
                    prev.kinds().add(edge.kind());
                    prev.rawIds().add(edge.identity());
                    edgeAcc.put(key, new MutableEdge(
                            resolvedSource,
                            resolvedTarget,
                            prev.kinds(),
                            prev.rawIds(),
                            prev.supportCount() + 1,
                            combineTrustTier(prev.trustTier(), edgeTier),
                            prev.anyConditional() || conditional,
                            prev.anyUnresolved() || unresolved));
                }
            }
        }

        var allEdges = new ArrayList<ExtractedEdge>(edgeAcc.size());
        var projectEdges = new ArrayList<ExtractedEdge>(edgeAcc.size());
        for (var acc : edgeAcc.values()) {
            String primaryKind = acc.kinds().contains("graph.depends-on")
                    ? "graph.depends-on"
                    : acc.kinds().first();
            String classification = acc.anyUnresolved()
                    ? "UNKNOWN"
                    : (acc.anyConditional() ? "MAY" : "MUST");
            var srcEntity = allEntities.get(acc.source());
            var tgtEntity = allEntities.get(acc.target());
            TrustTier tier = acc.trustTier() != null
                    ? acc.trustTier()
                    : combineTrustTier(srcEntity.trustTier(), tgtEntity.trustTier());
            var extracted = new ExtractedEdge(
                    acc.source(),
                    acc.target(),
                    primaryKind,
                    classification,
                    acc.supportCount(),
                    tier,
                    Collections.unmodifiableSet(acc.rawIds()));
            allEdges.add(extracted);
            if (srcEntity.isProject() && tgtEntity.isProject()) {
                projectEdges.add(extracted);
            }
        }

        // Retain only external entities that actually touch at least one project entity
        var connectedExternalIds = new HashSet<String>();
        for (var e : allEdges) {
            boolean srcProj = allEntities.get(e.source()).isProject();
            boolean tgtProj = allEntities.get(e.target()).isProject();
            if (srcProj && !tgtProj) connectedExternalIds.add(e.target());
            if (!srcProj && tgtProj) connectedExternalIds.add(e.source());
        }
        var activeExternals = externalEntities.stream()
                .filter(e -> connectedExternalIds.contains(e.id()))
                .toList();

        return new ExtractedContext(
                context,
                contextFamily,
                Collections.unmodifiableMap(allEntities),
                List.copyOf(projectEntities),
                activeExternals,
                List.copyOf(allEdges),
                List.copyOf(projectEdges));
    }

    /** Conservative trust-tier combination lattice: never promotes a weaker fact to higher certainty. */
    static TrustTier combineTrustTier(TrustTier a, TrustTier b) {
        if (a == null) return b == null ? TrustTier.COMPILER : b;
        if (b == null) return a;
        if (a == TrustTier.ADVISORY_UNVERIFIED || b == TrustTier.ADVISORY_UNVERIFIED) {
            return TrustTier.ADVISORY_UNVERIFIED;
        }
        if (a == TrustTier.SOURCE_SYNTAX || b == TrustTier.SOURCE_SYNTAX) {
            return TrustTier.SOURCE_SYNTAX;
        }
        if (a == TrustTier.SPRING_SAT || b == TrustTier.SPRING_SAT) {
            return TrustTier.SPRING_SAT;
        }
        return TrustTier.COMPILER;
    }

    /** Checks whether a directed edge matches any entry in {@code violatedEdgeKeys}. */
    static boolean isEdgeViolated(ExtractedEdge edge, Set<String> violatedEdgeKeys) {
        if (violatedEdgeKeys == null || violatedEdgeKeys.isEmpty()) return false;
        if (violatedEdgeKeys.contains(TarjanSccAnalyzer.edgeKey(edge.source(), edge.target()))
                || violatedEdgeKeys.contains(edge.source() + "|" + edge.target())) {
            return true;
        }
        for (String rawId : edge.rawEdgeIds()) {
            if (violatedEdgeKeys.contains(rawId)) {
                return true;
            }
        }
        return false;
    }

    private static List<KnowledgeGraph.Node> selectPrimaryNodes(
            List<KnowledgeGraph.Node> contextNodes,
            String contextFamily) {
        if ("spring.context".equals(contextFamily)) {
            var springNodes = new ArrayList<KnowledgeGraph.Node>();
            for (var n : contextNodes) {
                if (SPRING_PRIMARY_KINDS.contains(n.kind())) {
                    springNodes.add(n);
                }
            }
            if (!springNodes.isEmpty()) {
                return springNodes;
            }
        }
        var typeNodes = new ArrayList<KnowledgeGraph.Node>();
        for (var n : contextNodes) {
            if ("TYPE".equals(n.kind())) {
                typeNodes.add(n);
            }
        }
        if (!typeNodes.isEmpty()) {
            return typeNodes;
        }
        // Polyglot or non-Java context fallback: architectural symbols first, then documents/modules
        var nonContainer = new ArrayList<KnowledgeGraph.Node>();
        var documentsOrModules = new ArrayList<KnowledgeGraph.Node>();
        for (var n : contextNodes) {
            String k = n.kind();
            if (META_NODE_KINDS.contains(k)) continue;
            if ("SOURCE_DOCUMENT".equals(k) || "PACKAGE".equals(k) || "MODULE".equals(k)) {
                documentsOrModules.add(n);
            } else {
                nonContainer.add(n);
            }
        }
        return !nonContainer.isEmpty() ? nonContainer : documentsOrModules;
    }

    private static String liftToPrimary(
            String nodeId,
            Map<String, ExtractedEntity> primaries,
            Map<String, String> parentOf,
            Map<String, String> springOwnerOf) {
        if (primaries.containsKey(nodeId)) {
            return nodeId;
        }
        String springOwner = springOwnerOf.get(nodeId);
        if (springOwner != null && primaries.containsKey(springOwner)) {
            return springOwner;
        }
        String cursor = parentOf.get(nodeId);
        int hops = 0;
        while (cursor != null && hops++ < 16) {
            if (primaries.containsKey(cursor)) {
                return cursor;
            }
            cursor = parentOf.get(cursor);
        }
        return null;
    }

    private record DocumentInfo(String path, String module, String sourceSet) {}

    private static ExtractedEntity extractEntity(
            KnowledgeGraph graph,
            KnowledgeGraph.Node node,
            Map<String, KnowledgeGraph.Node> nodesById,
            Map<String, String> parentOf,
            Map<String, String> moduleNames,
            Map<String, String> packageNames,
            Map<String, DocumentInfo> documentInfo,
            String contextDefaultModuleId,
            String contextDefaultModuleName,
            Optional<String> contextDefaultSourceSet,
            TrustTier contextDefaultTier,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {
        String canonicalName = "";
        String qualifiedName = "";
        String spelling = "";
        String origin = "";
        String moduleId = "";
        String moduleName = "";
        String packageId = "";
        String packageName = "";
        Optional<String> sourceSet = Optional.empty();
        TrustTier tier = null;

        for (ContentDigest ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            String family = ev.family();
            TrustTier evTier = classifyEvidenceFamily(family);
            if (evTier != null) {
                tier = combineTrustTier(tier, evTier);
            }
            if ("java.declaration".equals(family)) {
                var payload = parseEvidence(ev, evidenceCache);
                if (payload.get("entity") instanceof Map<?, ?> entObj) {
                    var ent = GraphJson.object(entObj);
                    String cn = optString(ent, "canonicalName");
                    if (!cn.isEmpty()) {
                        canonicalName = cn;
                        qualifiedName = decodeJavaQualifiedName(cn);
                    }
                    String sp = optString(payload, "spelling");
                    if (!sp.isEmpty() && sp.length() <= 256 && !sp.contains("\n")) {
                        spelling = sp;
                    }
                    String orig = optString(ent, "origin");
                    if (!orig.isEmpty()) {
                        origin = orig;
                    }
                    String stableScope = optString(ent, "stableScope");
                    if (stableScope.startsWith("module:")) {
                        moduleId = stableScope;
                    }
                    if (ent.get("declaration") instanceof Map<?, ?> declObj) {
                        var decl = GraphJson.object(declObj);
                        String docId = optString(decl, "document");
                        DocumentInfo di = documentInfo.get(docId);
                        if (di != null) {
                            if (sourceSet.isEmpty() && !di.sourceSet().isEmpty()) {
                                sourceSet = Optional.of(di.sourceSet());
                            }
                            if (moduleId.isEmpty() && !di.module().isEmpty()) {
                                moduleId = di.module();
                            }
                        }
                    }
                }
            } else if ("spring.candidate".equals(family)) {
                var payload = parseEvidence(ev, evidenceCache);
                if (payload.get("declaredNameKey") instanceof Map<?, ?> namesObj) {
                    var names = GraphJson.object(namesObj);
                    String primary = optString(names, "primary");
                    if (!primary.isEmpty()) {
                        canonicalName = primary;
                        qualifiedName = primary;
                        spelling = primary;
                    }
                }
                if (origin.isEmpty()) origin = "PROJECT";
            } else if ("spring.endpoint-declaration".equals(family)) {
                var payload = parseEvidence(ev, evidenceCache);
                if (payload.get("route") instanceof Map<?, ?> routeObj) {
                    var route = GraphJson.object(routeObj);
                    String path = optString(route, "path");
                    String methods = "";
                    if (route.get("methods") instanceof List<?> ms) {
                        methods = String.join(",", ms.stream().filter(String.class::isInstance)
                                .map(String.class::cast).toList());
                    }
                    String lbl = methods.isEmpty() ? path : methods + " " + path;
                    if (!lbl.isBlank()) {
                        canonicalName = lbl;
                        qualifiedName = lbl;
                        spelling = lbl;
                    }
                }
                String m = optString(payload, "module");
                if (!m.isEmpty()) moduleId = m;
                String ss = optString(payload, "sourceSet");
                if (!ss.isEmpty()) sourceSet = Optional.of(ss);
                if (origin.isEmpty()) origin = "PROJECT";
            } else if ("polyglot.source-syntax".equals(family) || "polyglot.inventory-entry".equals(family)) {
                var payload = parseEvidence(ev, evidenceCache);
                var fields = payload.get("fields") instanceof Map<?, ?> f ? GraphJson.object(f) : payload;
                String path = decodePolyglotPath(fields.get("path"));
                if (path.isEmpty()) path = decodePolyglotPath(fields.get("rawPath"));
                String lang = optString(fields, "language");
                if (fields.get("fact") instanceof Map<?, ?> factObj) {
                    var fact = GraphJson.object(factObj);
                    String rawName = optString(fact, "name");
                    if (fact.get("attributes") instanceof Map<?, ?> aObj) {
                        String qn = optString(GraphJson.object(aObj), "qualifiedName");
                        if (!qn.isEmpty()) rawName = qn;
                    }
                    if (!rawName.isEmpty()) {
                        qualifiedName = path.isEmpty() ? rawName : path + "#" + rawName;
                        canonicalName = qualifiedName;
                        spelling = rawName;
                    }
                }
                if (qualifiedName.isEmpty() && !path.isEmpty()) {
                    qualifiedName = path;
                    canonicalName = path;
                    spelling = path;
                }
                if (!path.isEmpty()) {
                    int slash = path.lastIndexOf('/');
                    if (slash > 0) {
                        packageName = path.substring(0, slash);
                    }
                    int firstSlash = path.indexOf('/');
                    if (firstSlash > 0 && moduleName.isEmpty()) {
                        moduleName = path.substring(0, firstSlash);
                    }
                }
                if (!lang.isEmpty() && sourceSet.isEmpty()) {
                    sourceSet = Optional.of(lang);
                }
                if (origin.isEmpty()) origin = "PROJECT";
            } else if ("source.document".equals(family)) {
                var di = documentInfo.get(node.identity());
                if (di != null) {
                    if (qualifiedName.isEmpty() && !di.path().isEmpty()) {
                        qualifiedName = di.path();
                        canonicalName = di.path();
                        spelling = di.path();
                    }
                    if (moduleId.isEmpty() && !di.module().isEmpty()) {
                        moduleId = di.module();
                    }
                    if (sourceSet.isEmpty() && !di.sourceSet().isEmpty()) {
                        sourceSet = Optional.of(di.sourceSet());
                    }
                }
                if (origin.isEmpty()) origin = "PROJECT";
            }
        }

        // Walk structural ancestors to locate owning PACKAGE and MODULE nodes
        String ancestor = parentOf.get(node.identity());
        int hops = 0;
        while (ancestor != null && hops++ < 16) {
            var ancNode = nodesById.get(ancestor);
            if (ancNode != null) {
                if ("PACKAGE".equals(ancNode.kind()) && packageId.isEmpty()) {
                    packageId = ancNode.identity();
                    String pn = packageNames.getOrDefault(packageId, "");
                    if (!pn.isEmpty()) packageName = pn;
                } else if ("MODULE".equals(ancNode.kind()) && moduleId.isEmpty()) {
                    moduleId = ancNode.identity();
                }
            }
            ancestor = parentOf.get(ancestor);
        }

        if (moduleId.isEmpty() && !contextDefaultModuleId.isEmpty()) {
            moduleId = contextDefaultModuleId;
        }
        if (moduleName.isEmpty() && !moduleId.isEmpty()) {
            moduleName = moduleNames.getOrDefault(moduleId,
                    moduleId.equals(contextDefaultModuleId) && !contextDefaultModuleName.isEmpty()
                            ? contextDefaultModuleName
                            : shortHashLabel(moduleId));
        }
        if (moduleName.isEmpty()) {
            moduleName = "(default)";
        }
        if (packageName.isEmpty() && !qualifiedName.isEmpty()) {
            packageName = extractPackageFromName(qualifiedName);
        }
        if (sourceSet.isEmpty()) {
            sourceSet = contextDefaultSourceSet;
        }
        if (origin.isEmpty()) {
            origin = "PROJECT";
        }
        if (tier == null) {
            tier = contextDefaultTier;
        }

        String displayBase = !qualifiedName.isEmpty()
                ? qualifiedName
                : (!canonicalName.isEmpty() ? canonicalName : (!spelling.isEmpty() ? spelling : shortHashLabel(node.identity())));
        String shortLabel = computeShortLabel(displayBase, node.kind());

        return new ExtractedEntity(
                node.identity(),
                node.kind(),
                canonicalName,
                qualifiedName,
                spelling,
                shortLabel,
                packageId,
                packageName,
                moduleId,
                moduleName,
                origin,
                sourceSet,
                tier);
    }

    private static TrustTier defaultTierForContext(String contextFamily) {
        if ("spring.context".equals(contextFamily)) return TrustTier.SPRING_SAT;
        if ("polyglot.context".equals(contextFamily) || "repository.inventory-snapshot".equals(contextFamily)) {
            return TrustTier.SOURCE_SYNTAX;
        }
        return TrustTier.COMPILER;
    }

    private static TrustTier classifyEvidenceFamily(String family) {
        if (family == null || family.isEmpty()) return null;
        if (family.startsWith("spring.")) return TrustTier.SPRING_SAT;
        if (family.startsWith("scip.")) return TrustTier.COMPILER;
        if (family.startsWith("polyglot.")) return TrustTier.SOURCE_SYNTAX;
        if (family.startsWith("java.skeleton")) return TrustTier.SOURCE_SYNTAX;
        if (family.startsWith("java.") || family.startsWith("module.") || family.startsWith("build.")
                || "analysis.manifest".equals(family)) {
            return TrustTier.COMPILER;
        }
        return null;
    }

    private static TrustTier classifyEdgeTrustTier(
            KnowledgeGraph graph,
            KnowledgeGraph.Edge edge,
            TrustTier contextDefaultTier,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {
        if (edge.region().isPresent() || edge.kind().startsWith("spring.")) {
            return TrustTier.SPRING_SAT;
        }
        if (edge.target().state() != KnowledgeGraph.TargetState.RESOLVED) {
            return TrustTier.SOURCE_SYNTAX;
        }
        TrustTier combined = null;
        for (ContentDigest ref : edge.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            String family = ev.family();
            if ("java.occurrence".equals(family)) {
                var payload = parseEvidence(ev, evidenceCache);
                String status = optString(payload, "status");
                TrustTier t = (status.isEmpty() || "RESOLVED".equals(status))
                        ? TrustTier.COMPILER
                        : TrustTier.SOURCE_SYNTAX;
                combined = combineTrustTier(combined, t);
            } else {
                TrustTier t = classifyEvidenceFamily(family);
                if (t != null) {
                    combined = combineTrustTier(combined, t);
                }
            }
        }
        return combined != null ? combined : contextDefaultTier;
    }

    private static String extractModuleLabel(
            KnowledgeGraph graph,
            KnowledgeGraph.Node node,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {
        for (ContentDigest ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            if ("module.descriptor".equals(ev.family()) || "build.module".equals(ev.family())) {
                var payload = parseEvidence(ev, evidenceCache);
                var descriptor = "build.module".equals(ev.family()) && payload.get("descriptor") instanceof Map<?, ?> d
                        ? GraphJson.object(d)
                        : payload;
                String path = optString(descriptor, "path");
                if (!path.isEmpty()) return path;
                String name = optString(descriptor, "name");
                if (!name.isEmpty()) return name;
            }
        }
        return shortHashLabel(node.identity());
    }

    private static String extractPackageLabel(
            KnowledgeGraph graph,
            KnowledgeGraph.Node node,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {
        for (ContentDigest ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            if ("java.declaration".equals(ev.family())) {
                var payload = parseEvidence(ev, evidenceCache);
                if (payload.get("entity") instanceof Map<?, ?> entObj) {
                    String cn = optString(GraphJson.object(entObj), "canonicalName");
                    if (cn.startsWith("java:v1:")) {
                        String decoded = decodeJavaPackageName(cn);
                        if (!decoded.isEmpty()) return decoded;
                    }
                }
            }
        }
        return "";
    }

    private static DocumentInfo extractDocumentInfo(
            KnowledgeGraph graph,
            KnowledgeGraph.Node node,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {
        for (ContentDigest ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            if ("source.document".equals(ev.family())) {
                var payload = parseEvidence(ev, evidenceCache);
                return new DocumentInfo(
                        optString(payload, "path"),
                        optString(payload, "module"),
                        optString(payload, "classification"));
            }
        }
        return new DocumentInfo("", "", "");
    }

    private static Map<String, Object> parseEvidence(
            GraphEvidence ev,
            Map<ContentDigest, Map<String, Object>> cache) {
        var cached = cache.get(ev.identity());
        if (cached != null) return cached;
        try {
            var parsed = GraphJson.object(
                    GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(8_000_000, 128, 1_000_000)));
            if (cache.size() < 65_536) {
                cache.put(ev.identity(), parsed);
            }
            return parsed;
        } catch (IllegalArgumentException malformed) {
            return Map.of();
        }
    }

    private static String optString(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s ? s : "";
    }

    private static String decodePolyglotPath(Object rawValue) {
        if (!(rawValue instanceof String text) || text.isBlank()) return "";
        try {
            var decoded = new com.evolution.analysis.acquisition.streaming.LogicalPath(text).utf8();
            if (decoded.isPresent()) return decoded.get();
        } catch (IllegalArgumentException ignored) {
            // Fall through to raw string path
        }
        return text;
    }

    private static String decodeJavaPackageName(String canonical) {
        try {
            var tuple = GraphJson.array(GraphJson.read(canonical.substring(8), new GraphJson.Limits(100_000, 64, 10_000)));
            for (int depth = 0; depth < 32; depth++) {
                if (tuple.size() < 2) return "";
                String kind = GraphJson.string(tuple.getFirst());
                if ("type".equals(kind) || "package".equals(kind)) {
                    var parts = GraphJson.array(tuple.get(1));
                    return String.join(".", parts.stream()
                            .map(GraphJson::string)
                            .map(ContextGraphExtractor::decodePercentEscapes)
                            .toList());
                }
                tuple = GraphJson.array(tuple.get(1));
            }
        } catch (IllegalArgumentException ignored) {
        }
        return "";
    }

    private static String decodeJavaQualifiedName(String canonical) {
        if (!canonical.startsWith("java:v1:")) return canonical;
        try {
            var tuple = GraphJson.array(GraphJson.read(canonical.substring(8), new GraphJson.Limits(100_000, 64, 10_000)));
            return decodeTupleLabel(tuple, 0);
        } catch (IllegalArgumentException ignored) {
            return canonical;
        }
    }

    private static String decodeTupleLabel(List<Object> tuple, int depth) {
        if (depth > 32 || tuple.size() < 2) return "";
        String kind = GraphJson.string(tuple.getFirst());
        if ("package".equals(kind) || "type".equals(kind)) {
            String pkg = String.join(".", GraphJson.array(tuple.get(1)).stream()
                    .map(GraphJson::string)
                    .map(ContextGraphExtractor::decodePercentEscapes)
                    .toList());
            if ("package".equals(kind)) return pkg;
            String simple = tuple.size() >= 3 ? decodePercentEscapes(GraphJson.string(tuple.get(2))) : "";
            return pkg.isEmpty() ? simple : pkg + "." + simple;
        }
        String owner = decodeTupleLabel(GraphJson.array(tuple.get(1)), depth + 1);
        if (tuple.size() >= 3 && ("member-type".equals(kind) || "method".equals(kind)
                || "field".equals(kind) || "record-component".equals(kind))) {
            return owner + "." + decodePercentEscapes(GraphJson.string(tuple.get(2)));
        }
        return owner;
    }

    private static String decodePercentEscapes(String name) {
        if (name.indexOf('%') < 0) return name;
        var out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) == '%' && i + 6 < name.length()) {
                out.appendCodePoint(Integer.parseInt(name.substring(i + 1, i + 7), 16));
                i += 6;
            } else {
                out.append(name.charAt(i));
            }
        }
        return out.toString();
    }

    private static String extractPackageFromName(String qualified) {
        int hash = qualified.indexOf('#');
        String base = hash > 0 ? qualified.substring(0, hash) : qualified;
        int slash = base.lastIndexOf('/');
        if (slash > 0) {
            return base.substring(0, slash);
        }
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : "";
    }

    private static String computeShortLabel(String raw, String kind) {
        if (raw == null || raw.isBlank()) return "unknown";
        if ("TYPE".equals(kind) || "SPRING_COMPONENT".equals(kind)) {
            int dot = raw.lastIndexOf('.');
            if (dot > 0 && dot + 1 < raw.length() && !raw.startsWith("sha256:")) {
                int prevDot = raw.lastIndexOf('.', dot - 1);
                return prevDot >= 0 ? raw.substring(prevDot + 1) : raw;
            }
        }
        return raw;
    }

    static String shortHashLabel(String id) {
        if (id == null) return "unknown";
        int sha = id.lastIndexOf("sha256:");
        if (sha >= 0 && sha + 19 <= id.length()) {
            String prefix = sha > 0 ? id.substring(0, sha) : "";
            return prefix + id.substring(sha + 7, sha + 19);
        }
        return id;
    }
}
