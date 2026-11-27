package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.contract.source.SourceDocument;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Builds ephemeral in-memory {@link KnowledgeGraph} snapshots for Stage 1
 * ({@code PRELIMINARY_STRUCTURE}) and Stage 2 ({@code PARTIAL_CONTEXTS}) progressive publication.
 *
 * <p>Truthfulness invariant: every constructed graph embeds an explicit
 * {@value #PUBLICATION_STAGE_FAMILY} evidence payload with {@code authoritative=false}, marks
 * import-derived dependencies as {@code SYNTAX_IMPORT_PREVIEW} with {@code classification=UNKNOWN},
 * and NEVER persists to disk, {@code GraphProjectionStore}, or the durable reuse index.
 */
public final class PreliminaryGraphBuilder {
    public static final String PUBLICATION_STAGE_FAMILY = "analysis.publication-stage";
    public static final String PUBLICATION_STAGE_SCHEMA = "analysis.publication-stage:v1";
    public static final String CONTEXT_FAMILY = "preliminary.context";
    public static final String IMPORT_DEPENDENCY_FAMILY = "preliminary.import-dependency";

    public record ModuleInfo(String path, String displayName, String buildSystem, int sourceFileCount) {
        public ModuleInfo {
            path = normalizeModulePath(path == null ? "." : path);
            displayName = (displayName == null || displayName.isBlank()) ? path : displayName.trim();
            buildSystem = (buildSystem == null || buildSystem.isBlank()) ? "UNKNOWN" : buildSystem.trim();
            if (sourceFileCount < 0) {
                throw new IllegalArgumentException("Negative source file count");
            }
        }
    }

    public record Input(
            RepositoryIdentity repository,
            SnapshotIdentity snapshot,
            AnalysisJobs.TruthTier truthTier,
            List<ModuleInfo> modules,
            FastJavaSkeletonScanner.ScanResult skeleton,
            List<String> completedModules,
            List<String> pendingModules,
            Map<String, Object> polyglotSummary,
            List<KnowledgeGraph> completedModuleGraphs) {
        public Input {
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(truthTier, "truthTier");
            if (truthTier != AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE
                    && truthTier != AnalysisJobs.TruthTier.PARTIAL_CONTEXTS) {
                throw new IllegalArgumentException(
                        "PreliminaryGraphBuilder only builds PRELIMINARY_STRUCTURE or PARTIAL_CONTEXTS graphs, got: "
                                + truthTier);
            }
            modules = List.copyOf(modules == null ? List.of() : modules);
            Objects.requireNonNull(skeleton, "skeleton");
            completedModules = List.copyOf(completedModules == null ? List.of() : completedModules);
            pendingModules = List.copyOf(pendingModules == null ? List.of() : pendingModules);
            polyglotSummary = Collections.unmodifiableMap(new TreeMap<>(polyglotSummary == null ? Map.of() : polyglotSummary));
            completedModuleGraphs = List.copyOf(completedModuleGraphs == null ? List.of() : completedModuleGraphs);
        }

        public static Input stage1(
                RepositoryIdentity repository,
                SnapshotIdentity snapshot,
                List<ModuleInfo> modules,
                FastJavaSkeletonScanner.ScanResult skeleton,
                List<String> pendingModules) {
            return new Input(repository, snapshot, AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE,
                    modules, skeleton, List.of(), pendingModules, Map.of(), List.of());
        }

        public static Input stage2(
                RepositoryIdentity repository,
                SnapshotIdentity snapshot,
                List<ModuleInfo> modules,
                FastJavaSkeletonScanner.ScanResult skeleton,
                List<String> completedModules,
                List<String> pendingModules,
                List<KnowledgeGraph> completedModuleGraphs) {
            return new Input(repository, snapshot, AnalysisJobs.TruthTier.PARTIAL_CONTEXTS,
                    modules, skeleton, completedModules, pendingModules, Map.of(), completedModuleGraphs);
        }
    }

    public KnowledgeGraph build(Input input) {
        return build(input, () -> false);
    }

    public KnowledgeGraph build(Input input, BooleanSupplier cancelled) {
        Objects.requireNonNull(input, "input");
        BooleanSupplier cancelCheck = cancelled == null ? () -> false : cancelled;
        checkCancelled(cancelCheck);

        String contextId = input.snapshot().value();
        var evidenceById = new LinkedHashMap<ContentDigest, GraphEvidence>();
        var contexts = new LinkedHashMap<String, KnowledgeGraph.Context>();
        var nodesByKey = new LinkedHashMap<String, NodeBuilder>();
        var edgesByKey = new LinkedHashMap<String, KnowledgeGraph.Edge>();

        var completedSorted = new TreeSet<>(input.completedModules());
        var pendingSorted = new TreeSet<>(input.pendingModules());
        int totalContexts = Math.max(input.modules().size(), completedSorted.size() + pendingSorted.size());

        // 1. Canonical publication-stage truthfulness evidence (authoritative = false)
        var stageMap = new TreeMap<String, Object>();
        stageMap.put("schema", PUBLICATION_STAGE_SCHEMA);
        stageMap.put("truthTier", input.truthTier().name());
        stageMap.put("authoritative", false);
        stageMap.put("completedContexts", completedSorted.size());
        stageMap.put("totalContexts", totalContexts);
        stageMap.put("completedModules", List.copyOf(completedSorted));
        stageMap.put("pendingModules", List.copyOf(pendingSorted));
        stageMap.put("semanticResolution",
                input.truthTier() == AnalysisJobs.TruthTier.PARTIAL_CONTEXTS ? "PARTIAL" : "PENDING");
        stageMap.put("springSat", "PENDING");
        stageMap.put("policyEvaluation", "PENDING");
        stageMap.put("scoreV2", "PENDING");
        stageMap.put("scannedFileCount", input.skeleton().files().size());
        stageMap.put("scannedTypeCount", input.skeleton().totalTypes());
        if (!input.polyglotSummary().isEmpty()) {
            stageMap.put("polyglotSummary", input.polyglotSummary());
        }
        GraphEvidence stageEvidence = recordEvidence(evidenceById,
                GraphEvidence.capture(PUBLICATION_STAGE_FAMILY, stageMap));

        // 2. Preliminary context (uses preliminary.context family so ArchitectureQueryService allows
        // MODULE/PACKAGE/TYPE structural queries while publication-stage declares authoritative=false)
        GraphEvidence contextEvidence = recordEvidence(evidenceById,
                GraphEvidence.capture(CONTEXT_FAMILY, Map.of(
                        "schema", "preliminary.context:v1",
                        "authoritative", false,
                        "truthTier", input.truthTier().name(),
                        "repository", input.repository().value(),
                        "snapshot", input.snapshot().value())));

        contexts.put(contextId, new KnowledgeGraph.Context(
                contextId, input.snapshot(), Optional.empty(), contextEvidence.identity()));

        String repoNodeId = input.repository().value();
        addNode(nodesByKey, contextId, repoNodeId, "REPOSITORY", contextEvidence.identity());
        addNode(nodesByKey, contextId, repoNodeId, "REPOSITORY", stageEvidence.identity());

        // 3. Collect and register all modules (explicit build modules + any inferred from skeleton files)
        var moduleDescriptors = new LinkedHashMap<String, ModuleDescriptor>();
        var moduleInfosByPath = new TreeMap<String, ModuleInfo>();
        for (var info : input.modules()) {
            moduleInfosByPath.put(info.path(), info);
        }
        for (var file : input.skeleton().files()) {
            String mPath = normalizeModulePath(file.modulePath());
            moduleInfosByPath.putIfAbsent(mPath, new ModuleInfo(mPath, mPath, "INFERRED", 0));
        }
        if (moduleInfosByPath.isEmpty()) {
            moduleInfosByPath.put(".", new ModuleInfo(".", ".", "UNKNOWN", 0));
        }

        for (var info : moduleInfosByPath.values()) {
            checkCancelled(cancelCheck);
            var descriptor = ModuleDescriptor.create(input.repository(), info.path(), info.displayName());
            moduleDescriptors.put(info.path(), descriptor);
            var modEv = recordEvidence(evidenceById, GraphEvidence.capture("module.descriptor", descriptor));
            String modNodeId = descriptor.identity().value();
            addNode(nodesByKey, contextId, modNodeId, "MODULE", modEv.identity());
            addEdge(edgesByKey, contextId, "graph.contains", repoNodeId, modNodeId, modEv.identity());
        }

        // 4. Project SOURCE_DOCUMENT, PACKAGE, and TYPE nodes from FastJavaSkeletonScanner
        var typeIdentityByQualified = new HashMap<String, String>(Math.max(16, input.skeleton().totalTypes() * 2));
        var typeModuleByQualified = new HashMap<String, String>(Math.max(16, input.skeleton().totalTypes() * 2));
        var packageNodeByKey = new HashMap<String, String>();

        for (var file : input.skeleton().files()) {
            checkCancelled(cancelCheck);
            String mPath = normalizeModulePath(file.modulePath());
            var module = moduleDescriptors.get(mPath);
            if (module == null) {
                module = moduleDescriptors.values().iterator().next();
            }
            var doc = SourceDocument.create(
                    input.repository(),
                    module,
                    file.path(),
                    file.contentDigest(),
                    classifyPath(file.path()));
            var docEv = recordEvidence(evidenceById, GraphEvidence.capture("source.document", doc));
            String docNodeId = doc.identity().value();
            addNode(nodesByKey, contextId, docNodeId, "SOURCE_DOCUMENT", docEv.identity());
            addEdge(edgesByKey, contextId, "graph.contains", module.identity().value(), docNodeId, docEv.identity());

            String pkgNodeId = null;
            var moduleScope = EntityScope.project(module.identity());
            if (!file.packageName().isEmpty()) {
                String pkgCanonical = safePackageCanonicalName(file.packageName());
                String pkgKey = module.identity().value() + "|" + file.packageName();
                pkgNodeId = packageNodeByKey.get(pkgKey);
                if (pkgNodeId == null) {
                    var pkgIdentity = EntityIdentity.from(EntityOrigin.PROJECT, moduleScope,
                            EntityKind.PACKAGE, pkgCanonical);
                    pkgNodeId = pkgIdentity.value();
                    packageNodeByKey.put(pkgKey, pkgNodeId);
                    var pkgEv = recordEvidence(evidenceById, GraphEvidence.capture("java.declaration", Map.of(
                            "schema", "preliminary.java-declaration:v1",
                            "authoritative", false,
                            "truthTier", input.truthTier().name(),
                            "derivationKind", "SYNTAX_IMPORT_PREVIEW",
                            "spelling", file.packageName(),
                            "entity", Map.of(
                                    "identity", pkgNodeId,
                                    "origin", "PROJECT",
                                    "stableScope", module.identity().value(),
                                    "kind", "PACKAGE",
                                    "canonicalName", pkgCanonical))));
                    addNode(nodesByKey, contextId, pkgNodeId, "PACKAGE", pkgEv.identity());
                    addEdge(edgesByKey, contextId, "graph.contains", module.identity().value(), pkgNodeId, pkgEv.identity());
                }
            }

            for (var type : file.types()) {
                String canonicalName = safeTypeCanonicalName(file.packageName(), type.qualifiedName(), type.simpleName());
                var typeIdentity = EntityIdentity.from(EntityOrigin.PROJECT, moduleScope,
                        EntityKind.TYPE, canonicalName);
                String typeNodeId = typeIdentity.value();
                int startLine = Math.max(1, type.startLine());
                int endLine = Math.max(startLine, type.endLine());
                int endCol = endLine == startLine ? 2 : 1;

                var declPayload = new TreeMap<String, Object>();
                declPayload.put("schema", "preliminary.java-declaration:v1");
                declPayload.put("authoritative", false);
                declPayload.put("truthTier", input.truthTier().name());
                declPayload.put("derivationKind", "SYNTAX_IMPORT_PREVIEW");
                declPayload.put("language", file.language());
                declPayload.put("typeKind", type.kind());
                declPayload.put("nested", type.nested());
                declPayload.put("spelling", type.qualifiedName());
                declPayload.put("annotations", type.annotations());
                declPayload.put("derivation", Map.of(
                        "kind", "INFERRED",
                        "derivationKind", "SYNTAX_IMPORT_PREVIEW",
                        "method", Map.of("id", "preliminary.fast-skeleton-scanner", "version", "v1"),
                        "inputs", List.of(doc.contentDigest().value())));
                declPayload.put("entity", Map.of(
                        "identity", typeNodeId,
                        "origin", "PROJECT",
                        "stableScope", module.identity().value(),
                        "kind", "TYPE",
                        "canonicalName", canonicalName,
                        "declaration", Map.of(
                                "document", doc.identity().value(),
                                "startLine", startLine,
                                "startColumn", 1,
                                "endLine", endLine,
                                "endColumn", endCol)));

                var typeEv = recordEvidence(evidenceById, GraphEvidence.capture("java.declaration", declPayload));
                addNode(nodesByKey, contextId, typeNodeId, "TYPE", typeEv.identity());
                if (pkgNodeId != null) {
                    addEdge(edgesByKey, contextId, "graph.contains", pkgNodeId, typeNodeId, typeEv.identity());
                } else {
                    addEdge(edgesByKey, contextId, "graph.contains", module.identity().value(), typeNodeId, typeEv.identity());
                }
                typeIdentityByQualified.putIfAbsent(type.qualifiedName(), typeNodeId);
                typeModuleByQualified.putIfAbsent(type.qualifiedName(), module.identity().value());
            }
        }

        // 5. Project preliminary TYPE->TYPE and MODULE->MODULE import dependency edges
        var moduleDepPairs = new HashSet<String>();
        for (var dep : input.skeleton().dependencies()) {
            checkCancelled(cancelCheck);
            String sourceTypeId = typeIdentityByQualified.get(dep.sourceType());
            String targetTypeId = typeIdentityByQualified.get(dep.targetType());
            if (sourceTypeId == null || targetTypeId == null || sourceTypeId.equals(targetTypeId)) {
                continue;
            }
            var depEv = recordEvidence(evidenceById, GraphEvidence.capture(IMPORT_DEPENDENCY_FAMILY, Map.of(
                    "schema", "preliminary.import-dependency:v1",
                    "authoritative", false,
                    "derivation", "SYNTAX_IMPORT_PREVIEW",
                    "classification", "UNKNOWN",
                    "importKind", dep.importKind(),
                    "sourceType", dep.sourceType(),
                    "targetType", dep.targetType(),
                    "sourcePath", dep.sourcePath())));
            addEdge(edgesByKey, contextId, "graph.depends-on", sourceTypeId, targetTypeId, depEv.identity());

            String srcModId = typeModuleByQualified.get(dep.sourceType());
            String dstModId = typeModuleByQualified.get(dep.targetType());
            if (srcModId != null && dstModId != null && !srcModId.equals(dstModId)
                    && moduleDepPairs.add(srcModId + "->" + dstModId)) {
                addEdge(edgesByKey, contextId, "graph.depends-on", srcModId, dstModId, depEv.identity());
            }
        }

        // 6. Stage 2 (PARTIAL_CONTEXTS): merge completed module semantic contexts verbatim
        if (!input.completedModuleGraphs().isEmpty()) {
            mergeCompletedModuleContexts(input.snapshot(), input.completedModuleGraphs(),
                    contexts, nodesByKey, edgesByKey, evidenceById, cancelCheck);
        }

        var finalNodes = new ArrayList<KnowledgeGraph.Node>(nodesByKey.size());
        for (var nb : nodesByKey.values()) {
            finalNodes.add(nb.build());
        }
        var finalEdges = new ArrayList<>(edgesByKey.values());
        var finalEvidence = new ArrayList<>(evidenceById.values());
        var finalContexts = new ArrayList<>(contexts.values());

        return new KnowledgeGraph(input.snapshot(), finalContexts, finalNodes, finalEdges, finalEvidence);
    }

    private static void mergeCompletedModuleContexts(
            SnapshotIdentity expectedSnapshot,
            List<KnowledgeGraph> completedGraphs,
            Map<String, KnowledgeGraph.Context> contexts,
            Map<String, NodeBuilder> nodesByKey,
            Map<String, KnowledgeGraph.Edge> edgesByKey,
            Map<ContentDigest, GraphEvidence> evidenceById,
            BooleanSupplier cancelled) {
        for (var moduleGraph : completedGraphs) {
            if (moduleGraph == null || !moduleGraph.snapshot().equals(expectedSnapshot)) {
                continue;
            }
            for (var ctx : moduleGraph.contexts()) {
                checkCancelled(cancelled);
                // Keep our skeleton repository context as the root overview; import semantic analysis contexts.
                if (ctx.analysis().isEmpty() || contexts.containsKey(ctx.identity())) {
                    continue;
                }
                var ctxEv = moduleGraph.evidence(ctx.evidence());
                if (ctxEv.isEmpty()) {
                    continue;
                }
                recordEvidence(evidenceById, ctxEv.orElseThrow());
                contexts.put(ctx.identity(), ctx);

                for (var node : moduleGraph.nodes(ctx.identity())) {
                    checkCancelled(cancelled);
                    for (var evDigest : node.evidence()) {
                        moduleGraph.evidence(evDigest).ifPresent(ev -> recordEvidence(evidenceById, ev));
                        addNode(nodesByKey, node.context(), node.identity(), node.kind(), evDigest);
                    }
                }
                for (var edge : moduleGraph.edges(ctx.identity())) {
                    checkCancelled(cancelled);
                    for (var evDigest : edge.evidence()) {
                        moduleGraph.evidence(evDigest).ifPresent(ev -> recordEvidence(evidenceById, ev));
                    }
                    edgesByKey.putIfAbsent(edge.context() + "|" + edge.identity(), edge);
                }
            }
        }
    }

    private static GraphEvidence recordEvidence(Map<ContentDigest, GraphEvidence> store, GraphEvidence evidence) {
        store.putIfAbsent(evidence.identity(), evidence);
        return evidence;
    }

    private static void addNode(Map<String, NodeBuilder> nodes, String context, String id, String kind,
            ContentDigest evidence) {
        String key = context + "|" + id;
        nodes.computeIfAbsent(key, k -> new NodeBuilder(id, context, kind)).addEvidence(evidence);
    }

    private static void addEdge(Map<String, KnowledgeGraph.Edge> edges, String context, String kind,
            String source, String target, ContentDigest evidence) {
        String edgeId = KnowledgeGraph.derivedIdentity("preliminary-edge",
                List.of(context, kind, source, target));
        String key = context + "|" + edgeId;
        if (!edges.containsKey(key)) {
            edges.put(key, new KnowledgeGraph.Edge(
                    edgeId,
                    context,
                    kind,
                    source,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(target), Optional.empty()),
                    List.of(evidence),
                    Optional.empty()));
        }
    }

    private static final class NodeBuilder {
        final String identity;
        final String context;
        final String kind;
        final TreeSet<ContentDigest> evidence = new TreeSet<>(Comparator.comparing(ContentDigest::value));

        NodeBuilder(String identity, String context, String kind) {
            this.identity = identity;
            this.context = context;
            this.kind = kind;
        }

        void addEvidence(ContentDigest digest) {
            evidence.add(digest);
        }

        KnowledgeGraph.Node build() {
            return new KnowledgeGraph.Node(identity, context, kind, List.copyOf(evidence));
        }
    }

    private static String safePackageCanonicalName(String pkg) {
        try {
            return JavaSymbolName.packageName(pkg).canonicalName();
        } catch (IllegalArgumentException fallback) {
            return "preliminary:v1:package:" + pkg;
        }
    }

    private static String safeTypeCanonicalName(String pkg, String qualifiedName, String simpleName) {
        try {
            if (pkg.isEmpty()) {
                return buildTypeSymbol(pkg, qualifiedName.split("\\.")).canonicalName();
            }
            if (qualifiedName.startsWith(pkg + ".")) {
                String remainder = qualifiedName.substring(pkg.length() + 1);
                return buildTypeSymbol(pkg, remainder.split("\\.")).canonicalName();
            }
            return JavaSymbolName.topLevelType(pkg, simpleName).canonicalName();
        } catch (IllegalArgumentException fallback) {
            return "preliminary:v1:type:" + qualifiedName;
        }
    }

    private static JavaSymbolName buildTypeSymbol(String pkg, String[] typeParts) {
        JavaSymbolName current = JavaSymbolName.topLevelType(pkg, typeParts[0]);
        for (int i = 1; i < typeParts.length; i++) {
            current = JavaSymbolName.memberType(current, typeParts[i]);
        }
        return current;
    }

    private static SourceClassification classifyPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("/src/test/") || lower.startsWith("src/test/")) {
            return SourceClassification.TEST;
        }
        if (lower.contains("/generated-sources/") || lower.contains("/generated/")) {
            return SourceClassification.GENERATED_MAIN;
        }
        if (lower.contains("/generated-test-sources/")) {
            return SourceClassification.GENERATED_TEST;
        }
        return SourceClassification.MAIN;
    }

    private static String normalizeModulePath(String raw) {
        String m = raw.replace('\\', '/').trim();
        while (m.startsWith("./")) m = m.substring(2);
        while (m.startsWith("/")) m = m.substring(1);
        while (m.endsWith("/") && m.length() > 1) m = m.substring(0, m.length() - 1);
        return m.isEmpty() ? "." : m;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Preliminary graph build cancelled");
        }
    }
}
