package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.backend.query.repair.RepairContextPlanner;
import com.evolution.backend.query.search.CapturedSearchIndex;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Supplier;

/**
 * Deterministic Aider-style Personalized PageRank (PPR) &amp; Hybrid PPR + BM25 + Trigram RRF
 * repository map computed strictly over canonical KnowledgeGraph edges and pre-redacted FTS5 BM25 hits.
 */
public final class RepoMapBuilder {
    static final String ALGORITHM = "personalized-pagerank-v1";
    public static final String RRF_ALGORITHM = "ppr-bm25-trigram-rrf-v2";
    public static final int MAX_FILE_SEED_CAP = 5_000;
    public static final String SKG_TOPOLOGY_HEADER = "[SKG TOPOLOGY]";
    static final double DAMPING = 0.85;
    static final int ITERATIONS = 20;
    static final double RRF_K = 60.0;
    static final double RRF_WEIGHT_PPR = 1.0;
    static final double RRF_WEIGHT_BM25 = 1.2;
    static final double RRF_WEIGHT_TRIGRAM = 0.6;
    private static final Set<String> CONTAINMENT_EDGES = Set.of(
            "graph.contains", "graph.declares", "java.declares", "graph.owns-source",
            "graph.inventory-under-component", "spring.declares-injection");
    private static final Set<String> SYMBOL_NODE_KINDS = Set.of(
            "TYPE", "CLASS", "INTERFACE", "RECORD", "ENUM",
            "METHOD", "FIELD", "CONSTRUCTOR",
            "BEAN_DEFINITION_CANDIDATE", "BEAN_PRODUCER", "ENDPOINT", "INJECTION_POINT",
            "POLYGLOT_SYMBOL", "POLYGLOT_TYPE", "POLYGLOT_FUNCTION", "POLYGLOT_CLASS",
            "SERVICE_ENDPOINT", "FUNCTION");
    private static final Set<String> DECLARATION_KINDS = Set.of(
            "TYPE", "CLASS", "INTERFACE", "RECORD", "ENUM",
            "BEAN_DEFINITION_CANDIDATE", "BEAN_PRODUCER", "ENDPOINT",
            "POLYGLOT_SYMBOL", "POLYGLOT_TYPE", "POLYGLOT_FUNCTION", "POLYGLOT_CLASS",
            "SERVICE_ENDPOINT", "FUNCTION");
    private static final Set<String> HIGH_WEIGHT_EDGES = Set.of(
            "spring.selected-binding", "spring.injection-candidate", "spring.injects",
            "INJECTS", "syntax.injects",
            "CALLS_SERVICE", "INVOKES_RPC", "PUBLISHES_EVENT",
            "polyglot.service-call.candidate", "polyglot.grpc-service-call.candidate",
            "polyglot.event-flow.candidate", "polyglot.grpc-contract.candidate");
    private static final Set<String> CALL_INHERIT_EDGES = Set.of(
            "java.calls", "java.extends", "java.implements",
            "CALLS", "EXTENDS", "IMPLEMENTS",
            "syntax.calls", "syntax.extends", "syntax.implements");
    private static final Set<String> IMPLEMENTS_EDGE_KINDS = Set.of(
            "java.implements", "IMPLEMENTS", "syntax.implements");
    private static final Set<String> EXTENDS_EDGE_KINDS = Set.of(
            "java.extends", "EXTENDS", "syntax.extends", "syntax.inherits-declaration", "INHERITS");
    private static final Set<String> CONSUMER_INJECT_EDGE_KINDS = Set.of(
            "spring.selected-binding", "spring.injection-candidate", "spring.injects",
            "INJECTS", "syntax.injects", "java.field-type", "java.parameter-type");
    private static final Set<String> CONSUMER_CALL_EDGE_KINDS = Set.of(
            "java.calls", "CALLS", "CALLS_SYMBOL", "syntax.calls", "syntax.calls-declaration");

    public record Declaration(String identity, String name, String kind) {}

    /**
     * FQN-safe {@code InterfaceConsumerExpand(G, v)} topology recovery record (Chinthareddy-DKB Alg. 1):
     * records concrete type {@code c --IMPLEMENTS|EXTENDS--> I <--INJECTS|CALLS-- consumer} with
     * verified FQN + {@code file:line} provenance (I2/I4).
     */
    public record TopologyExpansion(
            String concreteId,
            String concreteFqn,
            String concretePath,
            int concreteLine,
            String hierarchyKind,
            String interfaceId,
            String interfaceFqn,
            String interfacePath,
            int interfaceLine,
            String consumerId,
            String consumerFqn,
            String consumerPath,
            int consumerLine,
            String consumerRelation,
            String trustTier) implements Comparable<TopologyExpansion> {
        public TopologyExpansion {
            concreteId = Objects.requireNonNull(concreteId, "concreteId");
            concreteFqn = Objects.requireNonNull(concreteFqn, "concreteFqn");
            concretePath = concretePath == null ? "" : concretePath;
            hierarchyKind = Objects.requireNonNull(hierarchyKind, "hierarchyKind");
            interfaceId = Objects.requireNonNull(interfaceId, "interfaceId");
            interfaceFqn = Objects.requireNonNull(interfaceFqn, "interfaceFqn");
            interfacePath = interfacePath == null ? "" : interfacePath;
            consumerId = Objects.requireNonNull(consumerId, "consumerId");
            consumerFqn = Objects.requireNonNull(consumerFqn, "consumerFqn");
            consumerPath = consumerPath == null ? "" : consumerPath;
            consumerRelation = Objects.requireNonNull(consumerRelation, "consumerRelation");
            trustTier = trustTier == null || trustTier.isBlank() ? "SOURCE_SYNTAX" : trustTier;
        }

        @Override
        public int compareTo(TopologyExpansion other) {
            int cmp = this.concreteFqn.compareTo(other.concreteFqn);
            if (cmp != 0) return cmp;
            cmp = this.interfaceFqn.compareTo(other.interfaceFqn);
            if (cmp != 0) return cmp;
            cmp = this.consumerFqn.compareTo(other.consumerFqn);
            if (cmp != 0) return cmp;
            cmp = this.consumerRelation.compareTo(other.consumerRelation);
            if (cmp != 0) return cmp;
            return this.consumerId.compareTo(other.consumerId);
        }

        public String formatLine() {
            return "- InterfaceConsumerExpand: " + concreteFqn + formatLoc(concretePath, concreteLine)
                    + " --" + hierarchyKind + "--> " + interfaceFqn + formatLoc(interfacePath, interfaceLine)
                    + " <--" + consumerRelation + "-- " + consumerFqn + formatLoc(consumerPath, consumerLine)
                    + " [trust=" + trustTier + "]";
        }

        private static String formatLoc(String path, int line) {
            if (path == null || path.isBlank()) return "";
            return line > 0 ? " (" + path + ":" + line + ")" : " (" + path + ")";
        }
    }

    public record FileRank(int rank, String path, BigDecimal score, String language,
                    int inDegree, int outDegree, List<Declaration> declarations,
                    List<String> endpoints, int gapCount,
                    Integer pprRank, Integer bm25Rank, Integer trigramRank, BigDecimal rrfScore) {
        public FileRank(int rank, String path, BigDecimal score, String language,
                        int inDegree, int outDegree, List<Declaration> declarations,
                        List<String> endpoints, int gapCount) {
            this(rank, path, score, language, inDegree, outDegree, declarations, endpoints, gapCount,
                    rank, null, null, null);
        }

        public FileRank {
            declarations = List.copyOf(declarations);
            endpoints = List.copyOf(endpoints);
        }
    }

    public record Result(ContentDigest graph, String context, String algorithm, BigDecimal damping, int iterations,
                  int totalNodes, int totalEdges, int totalFiles, int seedCount, int estimatedTokens,
                  boolean truncated, List<FileRank> files, String renderedMap,
                  List<TopologyExpansion> topologyExpansions) {
        public Result(ContentDigest graph, String context, String algorithm, BigDecimal damping, int iterations,
                      int totalNodes, int totalEdges, int totalFiles, int seedCount, int estimatedTokens,
                      boolean truncated, List<FileRank> files, String renderedMap) {
            this(graph, context, algorithm, damping, iterations, totalNodes, totalEdges, totalFiles,
                    seedCount, estimatedTokens, truncated, files, renderedMap, List.of());
        }

        public Result {
            files = List.copyOf(files);
            topologyExpansions = List.copyOf(topologyExpansions == null ? List.of() : topologyExpansions);
        }
    }

    /**
     * SALT compression options for {@link #buildCompressed}: emits compact {@code Signature(n)} + 1-line
     * Javadoc summaries for resolved out-of-edit-scope callees and {@code <= 30}-token {@code # SpringTruth:}
     * / {@code # SliceHint:} static evidence hints per entity.
     */
    public record SaltCompressionOptions(
            Set<String> directEditPaths,
            Set<String> unresolvedEntityIds,
            boolean signatureOnlyForCallees,
            boolean includeStaticHints) {
        public SaltCompressionOptions {
            directEditPaths = directEditPaths == null ? Set.of() : Set.copyOf(directEditPaths);
            unresolvedEntityIds = unresolvedEntityIds == null ? Set.of() : Set.copyOf(unresolvedEntityIds);
        }

        public static SaltCompressionOptions defaults() {
            return new SaltCompressionOptions(Set.of(), Set.of(), true, true);
        }
    }

    private RepoMapBuilder() {}

    public static Result build(KnowledgeGraph graph, Optional<String> preferredContext, List<String> rawSeeds,
                        int requestedMaxFiles, int requestedMaxTokens, Supplier<StopReason> stop) {
        return build(graph, preferredContext, rawSeeds, requestedMaxFiles, requestedMaxTokens, stop, null);
    }

    /**
     * Builds a SALT-compressed repository map combining deterministic PPR / Hybrid RRF file ranking with
     * {@code CalleeCtx(n)} {@code Signature(n)} + 1-line Javadoc rendering and {@code <= 30}-token
     * {@code # SpringTruth:} / {@code # SliceHint:} static evidence hints (pre-redacted via {@code llm-redact:v2}).
     */
    public static Result buildCompressed(
            KnowledgeGraph graph,
            Optional<String> preferredContext,
            List<String> rawSeeds,
            int requestedMaxFiles,
            int requestedMaxTokens,
            SaltCompressionOptions options,
            Supplier<StopReason> stop,
            CapturedSearchIndex searchIndex) {
        Result base = build(graph, preferredContext, rawSeeds, requestedMaxFiles, requestedMaxTokens, stop, searchIndex);
        if (base.files().isEmpty()) return base;
        SaltCompressionOptions opts = options == null ? SaltCompressionOptions.defaults() : options;
        var candidatePaths = base.files().stream().map(FileRank::path).toList();
        var compressed = RepairContextPlanner.compressContext(
                graph,
                base.context(),
                graph.snapshot().value(),
                opts.directEditPaths(),
                opts.unresolvedEntityIds(),
                candidatePaths,
                stop);

        var ctxByPath = new HashMap<String, RepairContextPlanner.CalleeContext>();
        for (var c : compressed.contexts()) ctxByPath.put(c.path(), c);
        var hintsByEntity = new HashMap<String, RepairContextPlanner.StaticEvidenceHint>();
        for (var h : compressed.hints()) hintsByEntity.put(h.entityId(), h);

        int maxTokens = Math.max(500, Math.min(24_000, requestedMaxTokens));
        var rendered = new StringBuilder();
        var keptFiles = new ArrayList<FileRank>(base.files().size());
        int usedTokens = 0;
        boolean truncated = base.truncated();
        if (!base.topologyExpansions().isEmpty()) {
            String topoBlock = renderTopologyHeader(base.topologyExpansions());
            rendered.append(topoBlock);
            usedTokens += RepairContextPlanner.estimateTokens(topoBlock);
        }

        for (var fr : base.files()) {
            var block = new StringBuilder();
            block.append(fr.rank()).append(". ").append(fr.path())
                    .append(" [score=").append(fr.score())
                    .append(", in=").append(fr.inDegree())
                    .append(", out=").append(fr.outDegree())
                    .append(", ").append(fr.language());
            if (fr.gapCount() > 0) block.append(", gaps=").append(fr.gapCount());
            block.append("]\n");

            var cc = ctxByPath.get(fr.path());
            if (cc != null) {
                if (opts.includeStaticHints()) {
                    var hint = hintsByEntity.get(cc.entityId());
                    if (hint != null) {
                        for (String line : hint.lines()) {
                            block.append("   ").append(line).append('\n');
                        }
                    }
                }
                if (opts.signatureOnlyForCallees() && cc.renderMode() == RepairContextPlanner.ContextRenderMode.SIGNATURE_ONLY) {
                    if (!cc.javadocSummary().isBlank()) {
                        block.append("   // ").append(cc.javadocSummary()).append('\n');
                    }
                    if (!cc.signature().isBlank()) {
                        for (String sigLine : cc.signature().split("\\R")) {
                            block.append("   ").append(sigLine).append('\n');
                        }
                    }
                } else {
                    for (var d : fr.declarations()) {
                        block.append("   - ").append(d.kind()).append(' ').append(d.name()).append('\n');
                    }
                }
            } else {
                for (var d : fr.declarations()) {
                    block.append("   - ").append(d.kind()).append(' ').append(d.name()).append('\n');
                }
            }
            int blockTokens = RepairContextPlanner.estimateTokens(block.toString());
            if (!keptFiles.isEmpty() && usedTokens + blockTokens > maxTokens) {
                truncated = true;
                break;
            }
            usedTokens += blockTokens;
            rendered.append(block);
            keptFiles.add(fr);
        }
        return new Result(
                base.graph(), base.context(), base.algorithm() + "+salt-sig-v1",
                base.damping(), base.iterations(), base.totalNodes(), base.totalEdges(),
                base.totalFiles(), base.seedCount(), usedTokens, truncated, keptFiles, rendered.toString(),
                base.topologyExpansions());
    }

    public static String renderCompactHintsBlock(List<RepairContextPlanner.StaticEvidenceHint> hints) {
        if (hints == null || hints.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var h : hints) {
            for (String line : h.lines()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    public static Result build(KnowledgeGraph graph, Optional<String> preferredContext, List<String> rawSeeds,
                        int requestedMaxFiles, int requestedMaxTokens, Supplier<StopReason> stop,
                        CapturedSearchIndex searchIndex) {
        int maxFiles = Math.max(1, Math.min(MAX_FILE_SEED_CAP, requestedMaxFiles));
        int maxTokens = Math.max(500, Math.min(24_000, requestedMaxTokens));
        var seeds = rawSeeds == null ? List.<String>of()
                : rawSeeds.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();

        var fileIndex = CapturedFileIndex.forGraph(graph);
        String snapshotCtx = graph.snapshot().value();
        String chosenContext = preferredContext.filter(c -> !c.isBlank()).orElse(snapshotCtx);
        boolean wholeSnapshot = chosenContext.equals(snapshotCtx);

        var targetContexts = new LinkedHashSet<String>();
        if (wholeSnapshot) {
            for (var c : graph.contexts()) targetContexts.add(c.identity());
        } else {
            targetContexts.add(chosenContext);
            targetContexts.add(snapshotCtx);
        }

        // 1. Map document identities -> repository paths
        var docIdToPath = new HashMap<String, String>();
        var langByPath = new HashMap<String, String>();
        for (var entry : fileIndex.entries()) {
            langByPath.put(entry.path(), entry.language());
        }
        for (String ctx : targetContexts) {
            if (stopped(stop)) break;
            for (var node : graph.nodes(ctx)) {
                if ("SOURCE_DOCUMENT".equals(node.kind())) {
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null) continue;
                        if ("source.document".equals(ev.family())) {
                            var obj = readSmall(ev);
                            if (obj.get("path") instanceof String p && !p.isBlank()) {
                                String norm = CapturedFileIndex.normalizePath(p);
                                docIdToPath.put(node.identity(), norm);
                                langByPath.putIfAbsent(norm, CapturedFileIndex.inferLanguage(norm, List.of()));
                            }
                        } else if ("source.bytes".equals(ev.family())) {
                            var obj = readSmall(ev);
                            if (obj.get("document") instanceof Map<?, ?> d && d.get("path") instanceof String p && !p.isBlank()) {
                                String norm = CapturedFileIndex.normalizePath(p);
                                docIdToPath.put(node.identity(), norm);
                                langByPath.putIfAbsent(norm, CapturedFileIndex.inferLanguage(norm, List.of()));
                            }
                        }
                    }
                } else if ("INVENTORY_ENTRY".equals(node.kind())) {
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null || !"polyglot.inventory-entry".equals(ev.family())) continue;
                        var obj = readSmall(ev);
                        if (obj.get("fields") instanceof Map<?, ?> fields && fields.get("rawPath") instanceof String rawB64) {
                            new LogicalPath(rawB64).utf8().ifPresent(p -> {
                                String norm = CapturedFileIndex.normalizePath(p);
                                docIdToPath.put(node.identity(), norm);
                                langByPath.putIfAbsent(norm, CapturedFileIndex.inferLanguage(norm, List.of()));
                            });
                        }
                    }
                }
            }
        }

        // 1b. Build containment child -> parent index so methods/fields/beans inherit owning file paths
        var parentByChild = new HashMap<String, String>();
        for (String ctx : targetContexts) {
            if (stopped(stop)) break;
            for (var edge : graph.edges(ctx)) {
                if (!CONTAINMENT_EDGES.contains(edge.kind())) continue;
                for (String targetId : edge.target().identities()) {
                    parentByChild.putIfAbsent(targetId, edge.source());
                }
            }
        }

        // 2. Map symbol nodes -> real captured file paths, declarations, endpoints, gaps, and FQN provenance
        var nodeToFile = new HashMap<String, String>();
        var symbolProvById = new HashMap<String, SymbolProv>();
        var fileToSymbolIds = new HashMap<String, TreeSet<String>>();
        var pendingNodes = new ArrayList<PendingNode>();
        var declsByFile = new TreeMap<String, LinkedHashMap<String, Declaration>>();
        var endpointsByFile = new TreeMap<String, TreeSet<String>>();
        var gapsByFile = new HashMap<String, Integer>();
        var allFiles = new TreeSet<String>();
        boolean hasCapturedFiles = !docIdToPath.isEmpty() || !fileIndex.entries().isEmpty();
        int totalNodes = 0;

        for (String ctx : targetContexts) {
            if (stopped(stop)) break;
            boolean primaryForSymbols = wholeSnapshot || ctx.equals(chosenContext);
            for (var node : graph.nodes(ctx)) {
                totalNodes++;
                if ((totalNodes & 1023) == 0 && stopped(stop)) break;
                if ("CAPABILITY_GAP".equals(node.kind()) || "PROVIDER_CONFLICT".equals(node.kind())) {
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null || !"evidence.gap".equals(ev.family())) continue;
                        var obj = readSmall(ev);
                        if (obj.get("sourceSpans") instanceof List<?> spans) {
                            for (Object sp : spans) {
                                if (sp instanceof Map<?, ?> m && m.get("document") instanceof String docId) {
                                    String p = docIdToPath.get(docId);
                                    if (p != null && !isOpaqueIdentity(p)) {
                                        gapsByFile.merge(p, 1, Integer::sum);
                                        allFiles.add(p);
                                    }
                                }
                            }
                        }
                    }
                    continue;
                }
                if (!primaryForSymbols) continue;
                String kind = node.kind();
                if (!SYMBOL_NODE_KINDS.contains(kind)) {
                    continue;
                }
                NodeInfo info = inspectNode(graph, node, docIdToPath);
                if (info.origin != null && !info.origin.isEmpty() && !"PROJECT".equals(info.origin)) {
                    continue;
                }
                String fileKey = (info.path != null && !info.path.isBlank() && !isOpaqueIdentity(info.path))
                        ? info.path
                        : null;
                if (fileKey == null) {
                    pendingNodes.add(new PendingNode(node, info));
                    continue;
                }
                registerSymbolOnFile(node, info, fileKey, nodeToFile, allFiles, langByPath, endpointsByFile, declsByFile);
                recordSymbolProvenance(node, info, fileKey, symbolProvById, fileToSymbolIds);
            }
        }

        // Resolve deferred members/beans to enclosing file via parent containment chain (never opaque sha256 IDs)
        for (var pending : pendingNodes) {
            String resolved = resolveAncestorFile(pending.node().identity(), parentByChild, docIdToPath, nodeToFile);
            if (resolved == null && !hasCapturedFiles
                    && DECLARATION_KINDS.contains(pending.node().kind())
                    && pending.info().name() != null && !isOpaqueIdentity(pending.info().name())) {
                resolved = pending.info().name();
            }
            if (resolved != null && !isOpaqueIdentity(resolved)) {
                registerSymbolOnFile(pending.node(), pending.info(), resolved,
                        nodeToFile, allFiles, langByPath, endpointsByFile, declsByFile);
                recordSymbolProvenance(pending.node(), pending.info(), resolved, symbolProvById, fileToSymbolIds);
            }
        }

        // Seed readable captured files (lifted cap: MAX_FILE_SEED_CAP = 5,000; full coverage preserved for I6)
        for (var entry : fileIndex.entries()) {
            if (entry.readable() && !isOpaqueIdentity(entry.path())) {
                allFiles.add(entry.path());
            }
        }

        // 3. Build weighted file-level graph + FQN-safe type hierarchy & consumer index for InterfaceConsumerExpand
        List<String> fileList = List.copyOf(allFiles);
        int n = fileList.size();
        var fileIndexMap = new HashMap<String, Integer>(n * 2);
        for (int i = 0; i < n; i++) fileIndexMap.put(fileList.get(i), i);

        var outEdges = new ArrayList<Map<Integer, Double>>(n);
        for (int i = 0; i < n; i++) outEdges.add(new TreeMap<>());
        var edgeDetails = new ArrayList<Map<String, EdgeLabelCount>>(n);
        for (int i = 0; i < n; i++) edgeDetails.add(new LinkedHashMap<>());
        var hierarchyByConcrete = new HashMap<String, List<HierarchyLink>>();
        var consumersByInterface = new HashMap<String, List<ConsumerLink>>();
        var dependsOnToCandidates = new HashMap<String, List<ConsumerLink>>();
        var knownInterfaces = new HashSet<String>();
        for (var sp : symbolProvById.values()) {
            if ("INTERFACE".equals(sp.kind())) knownInterfaces.add(sp.id());
        }
        int[] inDegree = new int[n];
        int[] outDegree = new int[n];
        int totalEdges = 0;
        var seenPairs = new HashSet<Long>();

        for (String ctx : targetContexts) {
            if (stopped(stop)) break;
            if (!wholeSnapshot && !ctx.equals(chosenContext)) continue;
            for (var edge : graph.edges(ctx)) {
                totalEdges++;
                if ((totalEdges & 2047) == 0 && stopped(stop)) break;
                if (CONTAINMENT_EDGES.contains(edge.kind())) continue;
                String edgeKind = edge.kind();
                String edgeTrust = inferEdgeTrustTier(graph, edge);
                // Record FQN-safe hierarchy and consumer relations (lifting member calls to enclosing type)
                if (IMPLEMENTS_EDGE_KINDS.contains(edgeKind) || EXTENDS_EDGE_KINDS.contains(edgeKind)) {
                    String hKind = IMPLEMENTS_EDGE_KINDS.contains(edgeKind) ? "IMPLEMENTS" : "EXTENDS";
                    String concreteId = liftToDeclaringType(edge.source(), parentByChild, symbolProvById);
                    for (String targetId : edge.target().identities()) {
                        String ifaceId = liftToDeclaringType(targetId, parentByChild, symbolProvById);
                        if (!concreteId.equals(ifaceId)) {
                            hierarchyByConcrete.computeIfAbsent(concreteId, k -> new ArrayList<>())
                                    .add(new HierarchyLink(ifaceId, hKind, edgeTrust));
                            knownInterfaces.add(ifaceId);
                        }
                    }
                } else if (CONSUMER_INJECT_EDGE_KINDS.contains(edgeKind) || CONSUMER_CALL_EDGE_KINDS.contains(edgeKind)) {
                    String rel = CONSUMER_INJECT_EDGE_KINDS.contains(edgeKind) ? "INJECTS" : "CALLS";
                    String consumerId = liftToDeclaringType(edge.source(), parentByChild, symbolProvById);
                    for (String targetId : edge.target().identities()) {
                        String targetTypeId = liftToDeclaringType(targetId, parentByChild, symbolProvById);
                        if (!consumerId.equals(targetTypeId)) {
                            consumersByInterface.computeIfAbsent(targetTypeId, k -> new ArrayList<>())
                                    .add(new ConsumerLink(consumerId, rel, edgeTrust));
                        }
                    }
                } else if ("graph.depends-on".equals(edgeKind)) {
                    String consumerId = liftToDeclaringType(edge.source(), parentByChild, symbolProvById);
                    for (String targetId : edge.target().identities()) {
                        String targetTypeId = liftToDeclaringType(targetId, parentByChild, symbolProvById);
                        if (!consumerId.equals(targetTypeId)) {
                            dependsOnToCandidates.computeIfAbsent(targetTypeId, k -> new ArrayList<>())
                                    .add(new ConsumerLink(consumerId, "INJECTS", edgeTrust));
                        }
                    }
                }
                String srcFile = nodeToFile.get(edge.source());
                if (srcFile == null) continue;
                Integer u = fileIndexMap.get(srcFile);
                if (u == null) continue;
                double w = edgeWeight(edgeKind);
                String edgeQual = edgeQualifier(graph, edge);
                boolean notableKind = HIGH_WEIGHT_EDGES.contains(edgeKind) || CALL_INHERIT_EDGES.contains(edgeKind);
                for (String targetId : edge.target().identities()) {
                    String dstFile = nodeToFile.get(targetId);
                    if (dstFile == null || dstFile.equals(srcFile)) continue;
                    Integer v = fileIndexMap.get(dstFile);
                    if (v == null) continue;
                    outEdges.get(u).merge(v, w, Double::sum);
                    if (notableKind) {
                        String detailKey = edgeKind + "|" + edgeQual + "|" + dstFile;
                        edgeDetails.get(u).merge(detailKey, new EdgeLabelCount(edgeKind, edgeQual, dstFile, 1),
                                (a, b) -> new EdgeLabelCount(a.kind(), a.qualifier(), a.targetFile(), a.count() + b.count()));
                    }
                    long pairKey = (((long) u) << 32) | (v & 0xffffffffL);
                    if (seenPairs.add(pairKey)) {
                        outDegree[u]++;
                        inDegree[v]++;
                    }
                }
            }
        }
        // Admit graph.depends-on predecessors ONLY for verified supertype/interface targets (never unrelated edges)
        for (String ifaceId : knownInterfaces) {
            var deps = dependsOnToCandidates.get(ifaceId);
            if (deps != null && !deps.isEmpty()) {
                consumersByInterface.computeIfAbsent(ifaceId, k -> new ArrayList<>()).addAll(deps);
            }
        }

        // 4. Compute deterministic Personalized PageRank (PPR) + optional BM25 + Trigram RRF
        double[] personalization = new double[n];
        int matchedSeedFiles = 0;
        if (n > 0) {
            double[] trigramScores = new double[n];
            var trigramRanks = new HashMap<Integer, Integer>();
            var bm25Ranks = new HashMap<Integer, Integer>();
            if (!seeds.isEmpty()) {
                trigramScores = scoreFilesBySeeds(fileList, declsByFile, endpointsByFile, seeds);
                var triIndices = new ArrayList<Integer>();
                for (int i = 0; i < n; i++) {
                    if (trigramScores[i] > 0.0) {
                        triIndices.add(i);
                    }
                }
                final double[] triRef = trigramScores;
                triIndices.sort((a, b) -> {
                    int cmp = Double.compare(triRef[b], triRef[a]);
                    if (cmp != 0) return cmp;
                    return fileList.get(a).compareTo(fileList.get(b));
                });
                for (int pos = 0; pos < triIndices.size(); pos++) {
                    trigramRanks.put(triIndices.get(pos), pos + 1);
                }

                if (searchIndex != null && !stopped(stop)) {
                    int bm25Cap = Math.min(MAX_FILE_SEED_CAP, Math.max(300, maxFiles * 10));
                    for (var hit : searchIndex.rankFiles(graph, seeds, bm25Cap, stop)) {
                        Integer idx = fileIndexMap.get(hit.path());
                        if (idx != null) {
                            bm25Ranks.putIfAbsent(idx, hit.bm25Rank());
                        }
                    }
                }

                // Combine trigram and BM25 relevance into PPR personalization mass
                double[] combinedSeedWeight = new double[n];
                double totalSeedWeight = 0.0;
                for (int i = 0; i < n; i++) {
                    double w = trigramScores[i];
                    Integer br = bm25Ranks.get(i);
                    if (br != null) {
                        w += 25.0 / (10.0 + br);
                    }
                    if (w > 0.0) {
                        combinedSeedWeight[i] = w;
                        totalSeedWeight += w;
                        matchedSeedFiles++;
                    }
                }
                if (matchedSeedFiles > 0 && totalSeedWeight > 0.0) {
                    double baseShare = 0.10 / n;
                    for (int i = 0; i < n; i++) {
                        personalization[i] = baseShare + (0.90 * (combinedSeedWeight[i] / totalSeedWeight));
                    }
                } else {
                    Arrays.fill(personalization, 1.0 / n);
                }
            } else {
                Arrays.fill(personalization, 1.0 / n);
            }

            double[] rank = Arrays.copyOf(personalization, n);
            double[] outWeightSum = new double[n];
            for (int u = 0; u < n; u++) {
                double sum = 0.0;
                for (double w : outEdges.get(u).values()) sum += w;
                outWeightSum[u] = sum;
            }
            for (int iter = 0; iter < ITERATIONS; iter++) {
                if (stopped(stop)) break;
                double[] next = new double[n];
                double dangling = 0.0;
                for (int u = 0; u < n; u++) {
                    if (outWeightSum[u] == 0.0) dangling += rank[u];
                }
                for (int v = 0; v < n; v++) {
                    next[v] = (1.0 - DAMPING) * personalization[v] + DAMPING * dangling * personalization[v];
                }
                for (int u = 0; u < n; u++) {
                    double totalOut = outWeightSum[u];
                    if (totalOut == 0.0) continue;
                    double contribBase = DAMPING * rank[u] / totalOut;
                    for (var e : outEdges.get(u).entrySet()) {
                        next[e.getKey()] += contribBase * e.getValue();
                    }
                }
                rank = next;
            }

            Integer[] pprOrder = new Integer[n];
            for (int i = 0; i < n; i++) pprOrder[i] = i;
            final double[] finalRank = rank;
            Arrays.sort(pprOrder, (a, b) -> {
                int cmp = Double.compare(finalRank[b], finalRank[a]);
                if (cmp != 0) return cmp;
                cmp = Integer.compare(inDegree[b], inDegree[a]);
                if (cmp != 0) return cmp;
                return fileList.get(a).compareTo(fileList.get(b));
            });
            int[] pprRankByIdx = new int[n];
            for (int pos = 0; pos < n; pos++) {
                pprRankByIdx[pprOrder[pos]] = pos + 1;
            }

            boolean useRrf = !seeds.isEmpty() && (!bm25Ranks.isEmpty() || !trigramRanks.isEmpty());
            String effectiveAlgorithm = useRrf ? RRF_ALGORITHM : ALGORITHM;
            double[] rrfValues = useRrf ? new double[n] : null;
            Integer[] order;
            if (useRrf) {
                for (int i = 0; i < n; i++) {
                    double pprTerm = RRF_WEIGHT_PPR / (RRF_K + pprRankByIdx[i]);
                    Integer br = bm25Ranks.get(i);
                    Integer tr = trigramRanks.get(i);
                    double bm25Term = br != null ? (RRF_WEIGHT_BM25 / (RRF_K + br)) : 0.0;
                    double triTerm = tr != null ? (RRF_WEIGHT_TRIGRAM / (RRF_K + tr)) : 0.0;
                    rrfValues[i] = pprTerm + bm25Term + triTerm;
                }
                order = new Integer[n];
                for (int i = 0; i < n; i++) order[i] = i;
                final double[] rrfRef = rrfValues;
                // Deterministic RRF tie-break strictly by (rrfScore DESC, path ASC)
                Arrays.sort(order, (a, b) -> {
                    int cmp = Double.compare(rrfRef[b], rrfRef[a]);
                    if (cmp != 0) return cmp;
                    return fileList.get(a).compareTo(fileList.get(b));
                });
            } else {
                order = pprOrder;
            }

            int baseTopK = Math.min(n, maxFiles);
            var topKPaths = new LinkedHashSet<String>();
            for (int pos = 0; pos < baseTopK; pos++) {
                topKPaths.add(fileList.get(order[pos]));
            }

            // 4b. FQN-Safe InterfaceConsumerExpand(G, v) (Chinthareddy-DKB Alg. 1):
            // For every concrete class c in top-K with (c --IMPLEMENTS|EXTENDS--> I),
            // automatically expand Pred_{INJECTS|CALLS}(I) into prioritized context!
            List<TopologyExpansion> topologyExpansions = computeInterfaceConsumerExpansions(
                    topKPaths, fileToSymbolIds, symbolProvById, hierarchyByConcrete, consumersByInterface);

            var selectedIndices = new ArrayList<Integer>(baseTopK);
            var selectedSet = new LinkedHashSet<Integer>();
            if (topologyExpansions.isEmpty()) {
                for (int pos = 0; pos < baseTopK; pos++) {
                    selectedIndices.add(order[pos]);
                    selectedSet.add(order[pos]);
                }
            } else {
                var expandedConsumerIndices = new ArrayList<Integer>();
                for (var exp : topologyExpansions) {
                    Integer cIdx = fileIndexMap.get(exp.consumerPath());
                    if (cIdx != null && !topKPaths.contains(exp.consumerPath())
                            && !expandedConsumerIndices.contains(cIdx)) {
                        expandedConsumerIndices.add(cIdx);
                    }
                }
                int reserve = Math.min(expandedConsumerIndices.size(), Math.max(0, maxFiles - 1));
                int baseKeep = Math.min(baseTopK, Math.max(1, maxFiles - reserve));
                for (int pos = 0; pos < baseKeep; pos++) {
                    if (selectedSet.add(order[pos])) selectedIndices.add(order[pos]);
                }
                for (int eIdx : expandedConsumerIndices) {
                    if (selectedIndices.size() >= maxFiles) break;
                    if (selectedSet.add(eIdx)) selectedIndices.add(eIdx);
                }
                for (int pos = baseKeep; pos < baseTopK && selectedIndices.size() < maxFiles; pos++) {
                    if (selectedSet.add(order[pos])) selectedIndices.add(order[pos]);
                }
            }

            var topFileSet = new HashSet<String>();
            for (int idx : selectedIndices) {
                topFileSet.add(fileList.get(idx));
            }

            var rankedFiles = new ArrayList<FileRank>();
            var mapOut = new StringBuilder();
            int usedTokens = 0;
            boolean truncated = n > selectedIndices.size();
            if (!topologyExpansions.isEmpty()) {
                String topoHeader = renderTopologyHeader(topologyExpansions);
                mapOut.append(topoHeader);
                usedTokens += Math.max(1, (topoHeader.length() + 3) / 4);
            }
            for (int pos = 0; pos < selectedIndices.size(); pos++) {
                int idx = selectedIndices.get(pos);
                String path = fileList.get(idx);
                BigDecimal rrfScore = useRrf
                        ? BigDecimal.valueOf(rrfValues[idx]).setScale(6, RoundingMode.HALF_UP) : null;
                BigDecimal score = useRrf
                        ? rrfScore
                        : BigDecimal.valueOf(finalRank[idx]).setScale(6, RoundingMode.HALF_UP);
                Integer pprRankVal = pprRankByIdx[idx];
                Integer bm25RankVal = bm25Ranks.get(idx);
                Integer triRankVal = trigramRanks.get(idx);
                String lang = langByPath.getOrDefault(path, CapturedFileIndex.inferLanguage(path, List.of()));
                var decls = declsByFile.getOrDefault(path, new LinkedHashMap<>()).values().stream()
                        .sorted(Comparator.comparing(Declaration::kind).thenComparing(Declaration::name))
                        .limit(10).toList();
                var eps = endpointsByFile.getOrDefault(path, new TreeSet<>()).stream().limit(6).toList();
                int gaps = gapsByFile.getOrDefault(path, 0);
                var archEdges = edgeDetails.get(idx).values().stream()
                        .filter(e -> topFileSet.contains(e.targetFile()))
                        .sorted(Comparator.comparingInt(EdgeLabelCount::count).reversed()
                                .thenComparing(EdgeLabelCount::kind)
                                .thenComparing(EdgeLabelCount::targetFile))
                        .limit(5)
                        .toList();

                String block = renderFileBlock(pos + 1, path, score, lang, inDegree[idx], outDegree[idx],
                        decls, eps, gaps, archEdges, useRrf, pprRankVal, bm25RankVal, triRankVal);
                int blockTokens = Math.max(1, (block.length() + 3) / 4);
                if (!rankedFiles.isEmpty() && usedTokens + blockTokens > maxTokens) {
                    truncated = true;
                    break;
                }
                usedTokens += blockTokens;
                mapOut.append(block);
                rankedFiles.add(new FileRank(pos + 1, path, score, lang, inDegree[idx], outDegree[idx],
                        decls, eps, gaps, pprRankVal, bm25RankVal, triRankVal, rrfScore));
            }

            return new Result(graph.identity(), chosenContext, effectiveAlgorithm,
                    BigDecimal.valueOf(DAMPING).setScale(2, RoundingMode.HALF_UP), ITERATIONS,
                    totalNodes, totalEdges, n, matchedSeedFiles, usedTokens, truncated,
                    rankedFiles, mapOut.toString(), topologyExpansions);
        }

        return new Result(graph.identity(), chosenContext, ALGORITHM,
                BigDecimal.valueOf(DAMPING).setScale(2, RoundingMode.HALF_UP), ITERATIONS,
                totalNodes, totalEdges, 0, 0, 0, false, List.of(), "", List.of());
    }

    private record PendingNode(KnowledgeGraph.Node node, NodeInfo info) {}

    private static void registerSymbolOnFile(
            KnowledgeGraph.Node node,
            NodeInfo info,
            String fileKey,
            Map<String, String> nodeToFile,
            Set<String> allFiles,
            Map<String, String> langByPath,
            Map<String, TreeSet<String>> endpointsByFile,
            Map<String, LinkedHashMap<String, Declaration>> declsByFile) {
        nodeToFile.put(node.identity(), fileKey);
        allFiles.add(fileKey);
        if (info.language() != null) langByPath.putIfAbsent(fileKey, info.language());
        if (info.endpoint() != null && !info.endpoint().isBlank()) {
            endpointsByFile.computeIfAbsent(fileKey, k -> new TreeSet<>()).add(info.endpoint());
        }
        if (DECLARATION_KINDS.contains(node.kind())) {
            String displayName = (info.name() != null && !info.name().isBlank() && !isOpaqueIdentity(info.name()))
                    ? info.name() : fileKey;
            declsByFile.computeIfAbsent(fileKey, k -> new LinkedHashMap<>())
                    .putIfAbsent(node.identity(), new Declaration(node.identity(), displayName, node.kind()));
        }
    }

    private static String resolveAncestorFile(String nodeId, Map<String, String> parentByChild,
                                              Map<String, String> docIdToPath, Map<String, String> nodeToFile) {
        String cursor = parentByChild.get(nodeId);
        var visited = new HashSet<String>();
        visited.add(nodeId);
        for (int depth = 0; depth < 8 && cursor != null && visited.add(cursor); depth++) {
            String f = nodeToFile.get(cursor);
            if (f != null) return f;
            String doc = docIdToPath.get(cursor);
            if (doc != null) return doc;
            cursor = parentByChild.get(cursor);
        }
        return null;
    }

    private static boolean isOpaqueIdentity(String value) {
        if (value == null || value.isBlank()) return true;
        return value.contains("sha256:") || value.startsWith("type-use:") || value.startsWith("java:v1:");
    }

    private record NodeInfo(String name, String path, String language, String origin, String endpoint,
                            boolean isDeclaration, int startLine) {}

    private record SymbolProv(String id, String fqn, String path, int line, String kind) {}

    private record HierarchyLink(String interfaceId, String kind, String trustTier) {}

    private record ConsumerLink(String consumerId, String relation, String trustTier) {}

    private static void recordSymbolProvenance(
            KnowledgeGraph.Node node,
            NodeInfo info,
            String fileKey,
            Map<String, SymbolProv> symbolProvById,
            Map<String, TreeSet<String>> fileToSymbolIds) {
        String fqn = (info.name() != null && !info.name().isBlank() && !isOpaqueIdentity(info.name()))
                ? info.name() : node.identity();
        int line = info.startLine() > 0 ? info.startLine() : 1;
        symbolProvById.putIfAbsent(node.identity(), new SymbolProv(node.identity(), fqn, fileKey, line, node.kind()));
        if (DECLARATION_KINDS.contains(node.kind())) {
            fileToSymbolIds.computeIfAbsent(fileKey, k -> new TreeSet<>()).add(node.identity());
        }
    }

    private static String liftToDeclaringType(
            String nodeId, Map<String, String> parentByChild, Map<String, SymbolProv> symbolProvById) {
        SymbolProv direct = symbolProvById.get(nodeId);
        if (direct != null && DECLARATION_KINDS.contains(direct.kind())) {
            return nodeId;
        }
        String cursor = parentByChild.get(nodeId);
        var visited = new HashSet<String>();
        visited.add(nodeId);
        for (int depth = 0; depth < 6 && cursor != null && visited.add(cursor); depth++) {
            SymbolProv parent = symbolProvById.get(cursor);
            if (parent != null && DECLARATION_KINDS.contains(parent.kind())) {
                return cursor;
            }
            cursor = parentByChild.get(cursor);
        }
        return nodeId;
    }

    private static List<TopologyExpansion> computeInterfaceConsumerExpansions(
            Set<String> topKPaths,
            Map<String, TreeSet<String>> fileToSymbolIds,
            Map<String, SymbolProv> symbolProvById,
            Map<String, List<HierarchyLink>> hierarchyByConcrete,
            Map<String, List<ConsumerLink>> consumersByInterface) {
        if (topKPaths.isEmpty() || hierarchyByConcrete.isEmpty() || consumersByInterface.isEmpty()) {
            return List.of();
        }
        // Index all implementors per interface so a sibling implementor isn't confused with an external consumer
        var implementorsByIface = new HashMap<String, Set<String>>();
        for (var entry : hierarchyByConcrete.entrySet()) {
            for (var link : entry.getValue()) {
                implementorsByIface.computeIfAbsent(link.interfaceId(), k -> new HashSet<>()).add(entry.getKey());
            }
        }
        var expansions = new TreeSet<TopologyExpansion>();
        for (String topPath : topKPaths) {
            for (String concreteId : fileToSymbolIds.getOrDefault(topPath, new TreeSet<>())) {
                SymbolProv concrete = symbolProvById.get(concreteId);
                if (concrete == null) continue;
                var visitedIfaces = new HashSet<String>();
                visitedIfaces.add(concreteId);
                var frontier = new ArrayDeque<HierarchyLink>();
                for (var link : hierarchyByConcrete.getOrDefault(concreteId, List.of())) {
                    if (visitedIfaces.add(link.interfaceId())) {
                        frontier.add(link);
                    }
                }
                int maxSuperHops = 2;
                int hop = 0;
                while (!frontier.isEmpty() && hop < maxSuperHops) {
                    int levelSize = frontier.size();
                    for (int s = 0; s < levelSize; s++) {
                        HierarchyLink hLink = frontier.poll();
                        if (hLink == null) continue;
                        String ifaceId = hLink.interfaceId();
                        SymbolProv iface = symbolProvById.get(ifaceId);
                        if (iface == null) continue;
                        var impls = implementorsByIface.getOrDefault(ifaceId, Set.of());
                        for (var cLink : consumersByInterface.getOrDefault(ifaceId, List.of())) {
                            String consumerId = cLink.consumerId();
                            if (consumerId.equals(concreteId) || consumerId.equals(ifaceId)
                                    || impls.contains(consumerId)) {
                                continue;
                            }
                            SymbolProv consumer = symbolProvById.get(consumerId);
                            if (consumer == null || consumer.path().isEmpty()) continue;
                            String trust = combineTrustTier(hLink.trustTier(), cLink.trustTier());
                            expansions.add(new TopologyExpansion(
                                    concrete.id(), concrete.fqn(), concrete.path(), concrete.line(),
                                    hLink.kind(),
                                    iface.id(), iface.fqn(), iface.path(), iface.line(),
                                    consumer.id(), consumer.fqn(), consumer.path(), consumer.line(),
                                    cLink.relation(), trust));
                        }
                        // Walk super-interfaces (up to 2 hops)
                        for (var superLink : hierarchyByConcrete.getOrDefault(ifaceId, List.of())) {
                            if (visitedIfaces.add(superLink.interfaceId())) {
                                frontier.add(superLink);
                            }
                        }
                    }
                    hop++;
                }
            }
        }
        return List.copyOf(expansions);
    }

    private static String renderTopologyHeader(List<TopologyExpansion> expansions) {
        var sb = new StringBuilder();
        sb.append(SKG_TOPOLOGY_HEADER).append('\n');
        int limit = Math.min(20, expansions.size());
        for (int i = 0; i < limit; i++) {
            sb.append(expansions.get(i).formatLine()).append('\n');
        }
        if (expansions.size() > limit) {
            sb.append("- ... (").append(expansions.size() - limit).append(" more interface consumer links)\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String inferEdgeTrustTier(KnowledgeGraph graph, KnowledgeGraph.Edge edge) {
        for (var ref : edge.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            var obj = readSmall(ev);
            if (obj.get("trustTier") instanceof String tt && !tt.isBlank()) return tt;
            if (obj.get("trust") instanceof String tt && !tt.isBlank()) return tt;
            if (ev.family().startsWith("java.")) return "COMPILER";
            if (ev.family().startsWith("spring.")) return "SPRING_SAT";
            if (ev.family().startsWith("polyglot.") || ev.family().startsWith("syntax.")) return "SOURCE_SYNTAX";
        }
        if (edge.kind().startsWith("spring.")) return "SPRING_SAT";
        if (edge.kind().startsWith("java.")) return "COMPILER";
        return "SOURCE_SYNTAX";
    }

    private static String combineTrustTier(String a, String b) {
        if ("SOURCE_SYNTAX".equals(a) || "SOURCE_SYNTAX".equals(b)) return "SOURCE_SYNTAX";
        if ("SPRING_SAT".equals(a) || "SPRING_SAT".equals(b)) return "SPRING_SAT";
        return "COMPILER";
    }

    private static int intField(Object raw, int fallback) {
        if (raw instanceof Number num) return num.intValue();
        if (raw instanceof String s) {
            try { return Integer.parseInt(s.strip()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static NodeInfo inspectNode(KnowledgeGraph graph, KnowledgeGraph.Node node, Map<String, String> docIdToPath) {
        String name = null, path = null, language = null, origin = null, endpoint = null;
        boolean isDecl = false;
        int startLine = 0;
        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            switch (ev.family()) {
                case "java.declaration" -> {
                    var payload = readSmall(ev);
                    if (payload.get("entity") instanceof Map<?, ?> ent) {
                        if (ent.get("qualifiedName") instanceof String q && !q.isBlank()) name = q;
                        else if (ent.get("canonicalName") instanceof String c) name = readableCanonicalName(c);
                        if (ent.get("origin") instanceof String o) origin = o;
                        if (ent.get("declaration") instanceof Map<?, ?> span) {
                            if (span.get("document") instanceof String docId) path = docIdToPath.get(docId);
                            startLine = intField(span.get("startLine"), startLine);
                        }
                        language = "java";
                        isDecl = true;
                    }
                }
                case "spring.candidate" -> {
                    var payload = readSmall(ev);
                    if (payload.get("declaredNameKey") instanceof Map<?, ?> key && key.get("primary") instanceof String p) {
                        name = p;
                    }
                    if (payload.get("span") instanceof Map<?, ?> span) {
                        if (span.get("document") instanceof String docId) path = docIdToPath.get(docId);
                        startLine = intField(span.get("startLine"), startLine);
                    }
                    language = "java";
                    isDecl = true;
                }
                case "spring.endpoint-declaration" -> {
                    var payload = readSmall(ev);
                    if (payload.get("route") instanceof Map<?, ?> route) {
                        var methods = route.get("methods") instanceof List<?> list
                                ? String.join(",", list.stream().map(Object::toString).toList()) : "ANY";
                        String routePath = route.get("path") instanceof String rp ? rp : "";
                        endpoint = (methods + " " + routePath).trim();
                        if (name == null) name = endpoint;
                    }
                    if (payload.get("span") instanceof Map<?, ?> span) {
                        if (span.get("document") instanceof String docId) path = docIdToPath.get(docId);
                        startLine = intField(span.get("startLine"), startLine);
                    }
                    origin = "PROJECT";
                    language = "java";
                    isDecl = true;
                }
                case "polyglot.source-syntax" -> {
                    var payload = readSmall(ev);
                    Map<?, ?> fields = payload.get("fields") instanceof Map<?, ?> f ? f : payload;
                    if (fields.get("path") instanceof String p && !p.isBlank()) {
                        path = CapturedFileIndex.normalizePath(p);
                    } else if (fields.get("rawPath") instanceof String rawB64) {
                        var utf8 = new LogicalPath(rawB64).utf8();
                        if (utf8.isPresent()) path = CapturedFileIndex.normalizePath(utf8.orElseThrow());
                    }
                    if (fields.get("language") instanceof String l && !l.isBlank()) language = l.toLowerCase(Locale.ROOT);
                    if (fields.get("fqn") instanceof String q && !q.isBlank()) name = q;
                    else if (fields.get("qualifiedName") instanceof String q && !q.isBlank()) name = q;
                    else if (fields.get("name") instanceof String n && !n.isBlank()) name = n;
                    else if (fields.get("symbol") instanceof String s && !s.isBlank()) name = s;
                    if (fields.get("route") instanceof String r && !r.isBlank()) {
                        String method = fields.get("httpMethod") instanceof String m ? m : "";
                        endpoint = (method + " " + r).trim();
                    }
                    startLine = intField(fields.get("startLine"), startLine);
                    if (fields.get("fact") instanceof Map<?, ?> fact && fact.get("span") instanceof Map<?, ?> sp) {
                        startLine = intField(sp.get("startLine"), startLine);
                    }
                    origin = "PROJECT";
                    isDecl = true;
                }
                default -> {}
            }
        }
        if (language == null && path != null) {
            language = CapturedFileIndex.inferLanguage(path, List.of());
        }
        return new NodeInfo(name, path, language, origin, endpoint, isDecl, startLine);
    }

    public static String readableCanonicalName(String canonical) {
        if (canonical == null || !canonical.startsWith("java:v1:")) return canonical;
        try {
            var tuple = GraphJson.array(GraphJson.read(canonical.substring(8), new GraphJson.Limits(65_536, 32, 4_096)));
            return decodeJavaTuple(tuple, 0);
        } catch (RuntimeException invalid) {
            return canonical;
        }
    }

    private static String decodeJavaTuple(List<Object> tuple, int depth) {
        if (depth > 32 || tuple.size() < 2) return "";
        String kind = GraphJson.string(tuple.getFirst());
        if ("package".equals(kind) || "type".equals(kind)) {
            String pkg = String.join(".", GraphJson.array(tuple.get(1)).stream().map(Object::toString).toList());
            if ("package".equals(kind)) return pkg;
            String simple = tuple.size() >= 3 ? tuple.get(2).toString() : "";
            return pkg.isEmpty() ? simple : pkg + "." + simple;
        }
        String owner = decodeJavaTuple(GraphJson.array(tuple.get(1)), depth + 1);
        if (Set.of("member-type", "method", "field", "record-component").contains(kind) && tuple.size() >= 3) {
            return owner.isEmpty() ? tuple.get(2).toString() : owner + "." + tuple.get(2);
        }
        return owner;
    }

    private static double edgeWeight(String kind) {
        if (HIGH_WEIGHT_EDGES.contains(kind)) return 1.5;
        if (CALL_INHERIT_EDGES.contains(kind)) return 1.2;
        return 1.0;
    }

    private record EdgeLabelCount(String kind, String qualifier, String targetFile, int count) {}

    private static String edgeQualifier(KnowledgeGraph graph, KnowledgeGraph.Edge edge) {
        if (edge.region().isPresent()) {
            String regionId = edge.region().orElseThrow();
            for (var ref : edge.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null) continue;
                var obj = readSmall(ev);
                if (obj.get("classification") instanceof String cls && !cls.isBlank()) {
                    return cls;
                }
            }
            return regionId.contains("MUST") ? "MUST" : "CONDITIONAL";
        }
        return "";
    }

    /**
     * Splits paths and identifiers on separators and camelCase boundaries (e.g. {@code OrderServiceImpl}
     * -> {@code order}, {@code service}, {@code impl}, {@code orderserviceimpl}).
     */
    static List<String> tokenizeIdentifier(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        var tokens = new LinkedHashSet<String>();
        String[] coarse = raw.split("[^A-Za-z0-9]+");
        for (String part : coarse) {
            if (part.isEmpty()) continue;
            String lower = part.toLowerCase(Locale.ROOT);
            tokens.add(lower);
            // Split camelCase / acronym boundaries: HTTPServer -> http, server; orderService -> order, service
            int start = 0;
            for (int i = 1; i < part.length(); i++) {
                char prev = part.charAt(i - 1);
                char cur = part.charAt(i);
                char next = (i + 1 < part.length()) ? part.charAt(i + 1) : 0;
                boolean boundary = (Character.isLowerCase(prev) && Character.isUpperCase(cur))
                        || (Character.isDigit(prev) != Character.isDigit(cur))
                        || (Character.isUpperCase(prev) && Character.isUpperCase(cur) && next != 0 && Character.isLowerCase(next));
                if (boundary) {
                    if (i > start) tokens.add(part.substring(start, i).toLowerCase(Locale.ROOT));
                    start = i;
                }
            }
            if (start > 0 && start < part.length()) {
                tokens.add(part.substring(start).toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(tokens);
    }

    static Set<String> trigramsOf(String token) {
        if (token == null || token.isEmpty()) return Set.of();
        if (token.length() < 3) return Set.of("=" + token);
        var out = new LinkedHashSet<String>();
        for (int i = 0; i <= token.length() - 3; i++) {
            out.add(token.substring(i, i + 3));
        }
        return out;
    }

    /**
     * Computes deterministic graph-wide IDF-weighted token + character trigram relevance scores with
     * squared term-coverage scaling across all repository files.
     */
    static double[] scoreFilesBySeeds(
            List<String> fileList,
            Map<String, LinkedHashMap<String, Declaration>> declsByFile,
            Map<String, TreeSet<String>> endpointsByFile,
            List<String> seeds) {
        int n = fileList.size();
        double[] scores = new double[n];
        if (n == 0 || seeds == null || seeds.isEmpty()) return scores;

        var fileTokens = new ArrayList<Set<String>>(n);
        var fileTrigrams = new ArrayList<Set<String>>(n);
        var tokenDf = new HashMap<String, Integer>();
        var trigramDf = new HashMap<String, Integer>();

        for (int i = 0; i < n; i++) {
            String path = fileList.get(i);
            var toks = new HashSet<String>(tokenizeIdentifier(path));
            var decls = declsByFile.getOrDefault(path, new LinkedHashMap<>());
            for (var d : decls.values()) {
                toks.addAll(tokenizeIdentifier(d.name()));
            }
            var eps = endpointsByFile.getOrDefault(path, new TreeSet<>());
            for (var ep : eps) {
                toks.addAll(tokenizeIdentifier(ep));
            }
            var tris = new HashSet<String>();
            for (String t : toks) {
                tokenDf.merge(t, 1, Integer::sum);
                tris.addAll(trigramsOf(t));
            }
            for (String g : tris) {
                trigramDf.merge(g, 1, Integer::sum);
            }
            fileTokens.add(toks);
            fileTrigrams.add(tris);
        }

        // Precompute query term sets per seed
        record QuerySeed(String raw, String lower, Set<String> tokens, Set<String> trigrams) {}
        var querySeeds = new ArrayList<QuerySeed>(seeds.size());
        for (String raw : seeds) {
            if (raw == null || raw.isBlank()) continue;
            String s = raw.strip();
            var qToks = new LinkedHashSet<String>(tokenizeIdentifier(s));
            var qTris = new LinkedHashSet<String>();
            for (String t : qToks) qTris.addAll(trigramsOf(t));
            querySeeds.add(new QuerySeed(s, s.toLowerCase(Locale.ROOT), qToks, qTris));
        }

        for (int i = 0; i < n; i++) {
            String path = fileList.get(i);
            String pLow = path.toLowerCase(Locale.ROOT);
            var decls = declsByFile.getOrDefault(path, new LinkedHashMap<>()).values();
            var eps = endpointsByFile.getOrDefault(path, new TreeSet<>());
            var fToks = fileTokens.get(i);
            var fTris = fileTrigrams.get(i);

            double fileTotal = 0.0;
            for (var qs : querySeeds) {
                double exactBonus = 0.0;
                if (pLow.equals(qs.lower()) || pLow.endsWith("/" + qs.lower())) {
                    exactBonus = 20.0;
                } else if (pLow.contains(qs.lower())) {
                    exactBonus = 12.0;
                }
                for (var d : decls) {
                    if (d.identity().equalsIgnoreCase(qs.raw())) {
                        exactBonus = Math.max(exactBonus, 24.0);
                    } else if (d.name().equalsIgnoreCase(qs.raw())) {
                        exactBonus = Math.max(exactBonus, 20.0);
                    } else if (d.name().toLowerCase(Locale.ROOT).contains(qs.lower())) {
                        exactBonus = Math.max(exactBonus, 14.0);
                    }
                }
                for (var ep : eps) {
                    if (ep.toLowerCase(Locale.ROOT).contains(qs.lower())) {
                        exactBonus = Math.max(exactBonus, 12.0);
                    }
                }

                // Token IDF with squared term-coverage scaling
                double tokenScore = 0.0;
                int matchedTok = 0;
                if (!qs.tokens().isEmpty()) {
                    for (String qt : qs.tokens()) {
                        if (fToks.contains(qt)) {
                            matchedTok++;
                            int df = tokenDf.getOrDefault(qt, 1);
                            tokenScore += StrictMath.log((n + 1.0) / (df + 1.0)) + 1.0;
                        }
                    }
                    double tokCov = (double) matchedTok / qs.tokens().size();
                    tokenScore *= (tokCov * tokCov);
                }

                // Character trigram IDF with squared coverage scaling (handles typos/partial identifiers)
                double triScore = 0.0;
                int matchedTri = 0;
                if (!qs.trigrams().isEmpty()) {
                    for (String qg : qs.trigrams()) {
                        if (fTris.contains(qg)) {
                            matchedTri++;
                            int df = trigramDf.getOrDefault(qg, 1);
                            triScore += StrictMath.log((n + 1.0) / (df + 1.0)) + 1.0;
                        }
                    }
                    double triCov = (double) matchedTri / qs.trigrams().size();
                    if (triCov >= 0.45 || matchedTok > 0 || exactBonus > 0.0) {
                        triScore = (triScore / Math.max(1, qs.trigrams().size())) * (triCov * triCov) * 2.0;
                    } else {
                        triScore = 0.0;
                    }
                }

                fileTotal += exactBonus + tokenScore + triScore;
            }
            scores[i] = fileTotal;
        }
        return scores;
    }

    private static String renderFileBlock(int rank, String path, BigDecimal score, String lang,
                                          int inDeg, int outDeg, List<Declaration> decls, List<String> eps,
                                          int gaps, List<EdgeLabelCount> archEdges,
                                          boolean useRrf, Integer pprRank, Integer bm25Rank, Integer triRank) {
        var sb = new StringBuilder();
        sb.append(rank).append(". ").append(path)
                .append(useRrf ? " [rrf=" : " [score=").append(score);
        if (useRrf) {
            if (pprRank != null) sb.append(", ppr=#").append(pprRank);
            if (bm25Rank != null) sb.append(", bm25=#").append(bm25Rank);
            if (triRank != null) sb.append(", tri=#").append(triRank);
        }
        sb.append(", in=").append(inDeg)
                .append(", out=").append(outDeg)
                .append(", ").append(lang);
        if (gaps > 0) sb.append(", gaps=").append(gaps);
        sb.append("]\n");
        for (var d : decls) {
            sb.append("   - ").append(d.kind()).append(' ').append(d.name()).append('\n');
        }
        for (var ep : eps) {
            sb.append("   - ENDPOINT ").append(ep).append('\n');
        }
        for (var ae : archEdges) {
            sb.append("   -> ").append(ae.kind());
            if (!ae.qualifier().isEmpty()) sb.append(" [").append(ae.qualifier()).append(']');
            sb.append(' ').append(ae.targetFile());
            if (ae.count() > 1) sb.append(" (x").append(ae.count()).append(')');
            sb.append('\n');
        }
        return sb.toString();
    }

    private static boolean stopped(Supplier<StopReason> stop) {
        return stop != null && stop.get() != StopReason.NONE;
    }

    private static Map<String, Object> readSmall(GraphEvidence ev) {
        if (ev.canonicalJson().length() > 131_072) return Map.of();
        try {
            return GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(131_072, 32, 8_192)));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }
}
