package com.evolution.analysis.query.trace;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer;

import java.util.*;

/**
 * Canonical full-stack vertical trace query engine over an immutable {@link KnowledgeGraph}.
 *
 * <p>Resolves a seed (node identity, qualified/simple name, endpoint route path, or source path)
 * and traverses bidirectionally across architectural layers
 * ({@code FRONTEND -> API_ROUTE -> SPRING_CONTROLLER -> SPRING_DI -> SERVICE -> JPA_ENTITY},
 * with {@code SQL_TABLE} and {@code TERRAFORM_RESOURCE} enum slots reserved for downstream
 * quarantined advisory stitching in {@code backend}).
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Zero dependency on {@code backend} or {@code AdvisoryOverlay}.</li>
 *   <li>Zero mutation of {@link KnowledgeGraph} or its canonical SHA-256 identity.</li>
 *   <li>Honest per-hop {@link TrustTier}, Spring {@code MUST|MAY|NEVER|UNKNOWN} classification
 *       plus {@code witnessId}, source provenance ({@code sourcePath}, {@code startLine..endLine},
 *       {@code contentSha256}), and attached {@code CAPABILITY_GAP} records ({@code I3}).</li>
 *   <li>Strict deterministic ordering by {@code (layer.ordinal() ASC, index ASC, nodeId ASC)}.</li>
 * </ul>
 */
public final class VerticalTraceEngine {

    public static final int DEFAULT_MAX_DEPTH = 8;
    public static final int MIN_MAX_DEPTH = 1;
    public static final int MAX_MAX_DEPTH = 16;
    public static final int DEFAULT_MAX_HOPS = 60;
    public static final int MIN_MAX_HOPS = 1;
    public static final int MAX_MAX_HOPS = 200;

    /** Ordered architectural swimlanes from user-facing frontend down to infrastructure. */
    public enum VerticalLayer {
        FRONTEND,
        API_ROUTE,
        SPRING_CONTROLLER,
        SPRING_DI,
        SERVICE,
        JPA_ENTITY,
        SQL_TABLE,
        TERRAFORM_RESOURCE
    }

    /** Single evidence-backed hop along a full-stack vertical trace. */
    public record TraceHop(
            int index,
            VerticalLayer layer,
            String nodeId,
            String label,
            String kind,
            TrustTier trustTier,
            Optional<String> classification,
            Optional<String> witnessId,
            Optional<String> sourcePath,
            OptionalInt startLine,
            OptionalInt endLine,
            Optional<String> contentSha256,
            Optional<String> edgeKind,
            Optional<String> gapReason
    ) {
        public TraceHop {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(trustTier, "trustTier");
            classification = classification == null ? Optional.empty() : classification;
            witnessId = witnessId == null ? Optional.empty() : witnessId;
            sourcePath = sourcePath == null ? Optional.empty() : sourcePath;
            startLine = startLine == null ? OptionalInt.empty() : startLine;
            endLine = endLine == null ? OptionalInt.empty() : endLine;
            contentSha256 = contentSha256 == null ? Optional.empty() : contentSha256;
            edgeKind = edgeKind == null ? Optional.empty() : edgeKind;
            gapReason = gapReason == null ? Optional.empty() : gapReason;
        }

        /** CanonicalJson-safe map representation. */
        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("index", index);
            m.put("layer", layer.name());
            m.put("nodeId", nodeId);
            m.put("label", label);
            m.put("kind", kind);
            m.put("trustTier", trustTier.name());
            classification.ifPresent(c -> m.put("classification", c));
            witnessId.ifPresent(w -> m.put("witnessId", w));
            sourcePath.ifPresent(p -> m.put("sourcePath", p));
            if (startLine.isPresent()) {
                m.put("startLine", startLine.getAsInt());
            }
            if (endLine.isPresent()) {
                m.put("endLine", endLine.getAsInt());
            }
            contentSha256.ifPresent(h -> m.put("contentSha256", h));
            edgeKind.ifPresent(e -> m.put("edgeKind", e));
            gapReason.ifPresent(g -> m.put("gapReason", g));
            return Collections.unmodifiableMap(m);
        }
    }

    /** Query parameters for a canonical vertical trace. */
    public record TraceRequest(
            String context,
            String seed,
            Optional<String> seedKind,
            Optional<String> world,
            int maxDepth,
            int maxHops
    ) {
        public TraceRequest {
            context = context == null ? "" : context.strip();
            seed = Objects.requireNonNull(seed, "seed").strip();
            if (seed.isEmpty()) {
                throw new IllegalArgumentException("Trace seed must not be blank");
            }
            seedKind = seedKind == null
                    ? Optional.empty()
                    : seedKind.map(String::strip).filter(s -> !s.isEmpty());
            world = world == null
                    ? Optional.empty()
                    : world.map(String::strip).filter(s -> !s.isEmpty());
            maxDepth = maxDepth <= 0
                    ? DEFAULT_MAX_DEPTH
                    : Math.max(MIN_MAX_DEPTH, Math.min(MAX_MAX_DEPTH, maxDepth));
            maxHops = maxHops <= 0
                    ? DEFAULT_MAX_HOPS
                    : Math.max(MIN_MAX_HOPS, Math.min(MAX_MAX_HOPS, maxHops));
        }

        public static TraceRequest defaults(String context, String seed) {
            return new TraceRequest(context, seed, Optional.empty(), Optional.empty(), DEFAULT_MAX_DEPTH, DEFAULT_MAX_HOPS);
        }
    }

    /** Immutable, deterministic result of a canonical vertical trace query. */
    public record VerticalTraceResult(
            ContentDigest graphDigest,
            String context,
            String seed,
            int totalHops,
            int canonicalHopCount,
            int advisoryHopCount,
            boolean truncated,
            List<String> layers,
            List<TraceHop> hops,
            List<Map<String, Object>> gaps
    ) {
        public VerticalTraceResult {
            Objects.requireNonNull(graphDigest, "graphDigest");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(seed, "seed");
            layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
            hops = List.copyOf(Objects.requireNonNull(hops, "hops"));
            Objects.requireNonNull(gaps, "gaps");
            gaps = gaps.stream()
                    .map(g -> Collections.unmodifiableMap(new TreeMap<>(g)))
                    .toList();
        }

        /** CanonicalJson-safe map representation suitable for REST, MCP, and deterministic digests. */
        public Map<String, Object> toView() {
            var view = new TreeMap<String, Object>();
            view.put("graphDigest", graphDigest.value());
            view.put("context", context);
            view.put("seed", seed);
            view.put("totalHops", totalHops);
            view.put("canonicalHopCount", canonicalHopCount);
            view.put("advisoryHopCount", advisoryHopCount);
            view.put("truncated", truncated);
            view.put("layers", layers);
            view.put("hops", hops.stream().map(TraceHop::toView).toList());
            view.put("gaps", gaps);
            return Collections.unmodifiableMap(view);
        }
    }

    private static final Set<String> NON_TRAVERSABLE_NODE_KINDS = Set.of(
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
            "SOURCE_ROOT",
            "SOURCE_DOCUMENT",
            "PACKAGE",
            "MODULE",
            "REPOSITORY",
            "OCCURRENCE",
            "REGISTRATION_EVENT",
            "REGISTRATION_TRANSITION",
            "ANNOTATION_USE",
            "PARAMETER",
            "TYPE_PARAMETER"
    );

    private static final Set<String> IGNORED_EDGE_KINDS = Set.of(
            "graph.evidence",
            "graph.in-region",
            "spring.has-truth-region",
            "spring.has-witness",
            "spring.has-replay",
            "spring.region-true-world",
            "spring.region-false-world",
            "spring.region-unknown-world",
            "spring.true-formula",
            "spring.false-formula",
            "spring.unknown-formula",
            "spring.formula-input",
            "spring.has-condition",
            "spring.condition-expression",
            "spring.expression-input",
            "spring.precedes-registration",
            "spring.precedes-transition",
            "spring.invokes-event",
            "spring.definition-fact"
    );

    private static final Set<String> FRONTEND_EXTENSIONS = Set.of(
            ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".vue", ".svelte", ".html", ".htm"
    );

    private static final Set<String> FRONTEND_LANGUAGES = Set.of(
            "typescript", "tsx", "javascript", "jsx", "vue", "svelte", "html", "frontend"
    );

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "RestController", "Controller", "RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping", "GraphQLApi", "GrpcService", "Path"
    );

    private static final Set<String> JPA_ANNOTATIONS = Set.of(
            "Entity", "Table", "Embeddable", "MappedSuperclass", "Document", "Repository", "Dao"
    );

    private static final Set<String> SPRING_DI_KINDS = Set.of(
            "BEAN_DEFINITION_CANDIDATE", "INJECTION_POINT", "INJECTION_USE"
    );

    private static final Set<String> API_ROUTE_KINDS = Set.of(
            "ENDPOINT", "API_ROUTE", "HTTP_ENDPOINT", "ROUTE", "GRPC_HANDLER", "SOURCE_RPC_HANDLER_CANDIDATE"
    );

    private VerticalTraceEngine() {}

    /** Executes a deterministic, read-only canonical vertical trace over {@code graph}. */
    public static VerticalTraceResult trace(KnowledgeGraph graph, TraceRequest request) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(request, "request");

        if (graph.contexts().isEmpty()) {
            return emptyResult(graph, request.context(), request.seed());
        }

        String primaryContext = resolvePrimaryContext(graph, request.context());
        List<String> scopeContexts = collectScopeContexts(graph, primaryContext);
        var evidenceCache = new HashMap<ContentDigest, Map<String, Object>>();

        // 1. Build unified read-only index over scoped contexts
        TraceIndex index = buildIndex(graph, scopeContexts, request.world(), evidenceCache);

        // 2. Resolve seed node (prefer primaryContext, fallback across scoped contexts / snapshot)
        String seedNodeId = resolveSeed(index, primaryContext, request.seed(), request.seedKind());
        if (seedNodeId == null) {
            return emptyResult(graph, primaryContext, request.seed());
        }

        // 3. Bidirectional BFS up to maxDepth, bounded by maxHops
        record VisitState(
                String nodeId,
                int depth,
                int discoveryOrder,
                Optional<String> edgeKind,
                Optional<String> edgeClassification,
                Optional<String> edgeWitnessId,
                TrustTier edgeTrustTier
        ) {}

        var visited = new LinkedHashMap<String, VisitState>();
        var queue = new ArrayDeque<VisitState>();
        int nextOrder = 0;

        NodeMeta seedMeta = index.nodes.get(seedNodeId);
        VisitState seedState = new VisitState(
                seedNodeId,
                0,
                nextOrder++,
                Optional.empty(),
                seedMeta.classification(),
                seedMeta.witnessId(),
                seedMeta.trustTier());
        visited.put(seedNodeId, seedState);
        queue.add(seedState);

        boolean truncated = false;
        while (!queue.isEmpty()) {
            VisitState current = queue.poll();
            if (current.depth() >= request.maxDepth()) {
                continue;
            }
            List<DirectedLink> neighbors = index.neighborsOf(current.nodeId());
            for (DirectedLink link : neighbors) {
                String nextId = link.neighborId();
                if (!index.nodes.containsKey(nextId)) {
                    continue;
                }
                if (visited.containsKey(nextId)) {
                    // Preserve stronger Spring classification/witness metadata if discovered via Spring edge
                    VisitState existing = visited.get(nextId);
                    if (existing.edgeClassification().isEmpty() && link.classification().isPresent()) {
                        visited.put(nextId, new VisitState(
                                existing.nodeId(),
                                existing.depth(),
                                existing.discoveryOrder(),
                                existing.edgeKind().isPresent() ? existing.edgeKind() : Optional.of(link.edgeKind()),
                                link.classification(),
                                existing.edgeWitnessId().isPresent() ? existing.edgeWitnessId() : link.witnessId(),
                                combineTrust(existing.edgeTrustTier(), link.trustTier())));
                    }
                    continue;
                }
                if (visited.size() >= request.maxHops()) {
                    truncated = true;
                    continue;
                }
                NodeMeta nextMeta = index.nodes.get(nextId);
                Optional<String> effClass = link.classification().isPresent()
                        ? link.classification()
                        : nextMeta.classification();
                Optional<String> effWitness = link.witnessId().isPresent()
                        ? link.witnessId()
                        : nextMeta.witnessId();
                VisitState nextState = new VisitState(
                        nextId,
                        current.depth() + 1,
                        nextOrder++,
                        Optional.of(link.edgeKind()),
                        effClass,
                        effWitness,
                        link.trustTier());
                visited.put(nextId, nextState);
                queue.add(nextState);
            }
        }

        // 4. Collect relevant CapabilityGapRecord entries touching traced nodes (I3 honesty)
        Set<String> tracedNodeIds = visited.keySet();
        var gapReasonByNode = new HashMap<String, TreeSet<String>>();
        List<Map<String, Object>> collectedGaps = collectRelevantGaps(
                graph, scopeContexts, index, tracedNodeIds, gapReasonByNode, evidenceCache);

        // 5. Materialize hops and sort deterministically by (layer.ordinal() ASC, discoveryOrder ASC, nodeId ASC)
        record PendingHop(int discoveryOrder, TraceHop hop) {}
        var pending = new ArrayList<PendingHop>(visited.size());
        for (VisitState st : visited.values()) {
            NodeMeta meta = index.nodes.get(st.nodeId());
            TrustTier hopTier = resolveHopTrustTier(meta, st.edgeTrustTier(), st.edgeClassification());
            Optional<String> classification = st.edgeClassification().isPresent()
                    ? st.edgeClassification()
                    : meta.classification();
            Optional<String> witnessId = st.edgeWitnessId().isPresent()
                    ? st.edgeWitnessId()
                    : meta.witnessId();
            Optional<String> gapReason = Optional.ofNullable(gapReasonByNode.get(st.nodeId()))
                    .filter(s -> !s.isEmpty())
                    .map(s -> String.join(", ", s));

            TraceHop hop = new TraceHop(
                    st.discoveryOrder(),
                    meta.layer(),
                    meta.nodeId(),
                    meta.label(),
                    meta.kind(),
                    hopTier,
                    classification,
                    witnessId,
                    meta.sourcePath(),
                    meta.startLine(),
                    meta.endLine(),
                    meta.contentSha256(),
                    st.edgeKind(),
                    gapReason);
            pending.add(new PendingHop(st.discoveryOrder(), hop));
        }

        pending.sort(Comparator
                .comparingInt((PendingHop p) -> p.hop().layer().ordinal())
                .thenComparingInt(PendingHop::discoveryOrder)
                .thenComparing(p -> p.hop().nodeId()));

        var finalHops = new ArrayList<TraceHop>(pending.size());
        var layerSet = new TreeSet<VerticalLayer>(Comparator.comparingInt(Enum::ordinal));
        int canonicalCount = 0;
        int advisoryCount = 0;

        for (int i = 0; i < pending.size(); i++) {
            TraceHop raw = pending.get(i).hop();
            TraceHop indexed = new TraceHop(
                    i,
                    raw.layer(),
                    raw.nodeId(),
                    raw.label(),
                    raw.kind(),
                    raw.trustTier(),
                    raw.classification(),
                    raw.witnessId(),
                    raw.sourcePath(),
                    raw.startLine(),
                    raw.endLine(),
                    raw.contentSha256(),
                    raw.edgeKind(),
                    raw.gapReason());
            finalHops.add(indexed);
            layerSet.add(indexed.layer());
            if (indexed.trustTier() == TrustTier.ADVISORY_UNVERIFIED) {
                advisoryCount++;
            } else {
                canonicalCount++;
            }
        }

        List<String> presentLayers = layerSet.stream().map(Enum::name).toList();
        return new VerticalTraceResult(
                graph.identity(),
                primaryContext,
                request.seed(),
                finalHops.size(),
                canonicalCount,
                advisoryCount,
                truncated,
                presentLayers,
                finalHops,
                collectedGaps);
    }

    private static VerticalTraceResult emptyResult(KnowledgeGraph graph, String context, String seed) {
        return new VerticalTraceResult(
                graph.identity(),
                context,
                seed,
                0,
                0,
                0,
                false,
                List.of(),
                List.of(),
                List.of());
    }

    private static String resolvePrimaryContext(KnowledgeGraph graph, String requestedContext) {
        if (!requestedContext.isEmpty()) {
            for (var ctx : graph.contexts()) {
                if (ctx.identity().equals(requestedContext)) {
                    return requestedContext;
                }
            }
        }
        String snapshotId = graph.snapshot().value();
        for (var ctx : graph.contexts()) {
            if (ctx.identity().equals(snapshotId)) {
                return snapshotId;
            }
        }
        return graph.contexts().getFirst().identity();
    }

    private static List<String> collectScopeContexts(KnowledgeGraph graph, String primaryContext) {
        var ordered = new LinkedHashSet<String>();
        ordered.add(primaryContext);

        Optional<String> primaryAnalysis = graph.contexts().stream()
                .filter(c -> c.identity().equals(primaryContext))
                .findFirst()
                .flatMap(c -> c.analysis().map(a -> a.value()));

        // Include sibling Spring / analysis contexts sharing the same analysis identity, plus snapshot context
        for (var ctx : graph.contexts()) {
            String cid = ctx.identity();
            if (cid.equals(primaryContext)) continue;
            Optional<String> ctxAnalysis = ctx.analysis().map(a -> a.value());
            boolean linkedByAnalysis = (primaryAnalysis.isPresent() && primaryAnalysis.equals(ctxAnalysis))
                    || (ctxAnalysis.isPresent() && ctxAnalysis.get().equals(primaryContext))
                    || (primaryAnalysis.isPresent() && primaryAnalysis.get().equals(cid));
            if (linkedByAnalysis || cid.equals(graph.snapshot().value())) {
                ordered.add(cid);
            }
        }
        // Fallback: also include any remaining polyglot/spring contexts deterministically so cross-context
        // frontend -> API -> Spring DI -> JPA traces resolve seamlessly across contexts.
        for (var ctx : graph.contexts()) {
            ordered.add(ctx.identity());
        }
        return List.copyOf(ordered);
    }

    private record DocumentMeta(String documentId, String path, String contentSha256) {}

    private record RegionMeta(
            String regionId,
            String classification,
            Optional<String> witnessId,
            Optional<String> definitionId
    ) {}

    private record DirectedLink(
            String neighborId,
            String edgeKind,
            boolean outgoing,
            TrustTier trustTier,
            Optional<String> classification,
            Optional<String> witnessId
    ) {}

    private record NodeMeta(
            String nodeId,
            String ownerContext,
            String kind,
            VerticalLayer layer,
            String label,
            String canonicalName,
            String qualifiedName,
            String simpleName,
            String routePath,
            TrustTier trustTier,
            Optional<String> classification,
            Optional<String> witnessId,
            Optional<String> sourcePath,
            OptionalInt startLine,
            OptionalInt endLine,
            Optional<String> contentSha256,
            Set<String> documentIds
    ) {}

    private static final class TraceIndex {
        final Map<String, NodeMeta> nodes = new LinkedHashMap<>();
        final Map<String, List<DirectedLink>> adjacency = new HashMap<>();
        final Map<String, DocumentMeta> documents = new HashMap<>();
        final Map<String, RegionMeta> regions = new HashMap<>();
        final Map<String, Set<String>> contextsByNode = new HashMap<>();

        List<DirectedLink> neighborsOf(String nodeId) {
            return adjacency.getOrDefault(nodeId, List.of());
        }
    }

    private static TraceIndex buildIndex(
            KnowledgeGraph graph,
            List<String> scopeContexts,
            Optional<String> worldFilter,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {
        var index = new TraceIndex();

        // Context default trust tier lookup
        var contextDefaultTiers = new HashMap<String, TrustTier>();
        for (var ctx : graph.contexts()) {
            String family = graph.evidence(ctx.evidence()).map(GraphEvidence::family).orElse("unknown");
            contextDefaultTiers.put(ctx.identity(), defaultTierForContext(family));
        }

        // Pass 1: Collect SOURCE_DOCUMENT metadata, TRUTH_REGION + CONFIGURATION_WITNESS metadata,
        // and endpoint-producing owners + annotations across scoped contexts.
        var regionWitnesses = new HashMap<String, TreeSet<String>>();
        var factToRegion = new HashMap<String, String>();
        var definitionRegions = new HashMap<String, String>();
        var endpointOwners = new HashSet<String>();
        var nodeAnnotations = new HashMap<String, Set<String>>();
        var rawNodes = new LinkedHashMap<String, KnowledgeGraph.Node>();
        var rawNodeContext = new HashMap<String, String>();
        var rawNodeEvidence = new HashMap<String, LinkedHashSet<ContentDigest>>();

        for (String ctxId : scopeContexts) {
            for (var node : graph.nodes(ctxId)) {
                String k = node.kind();
                if ("SOURCE_DOCUMENT".equals(k)) {
                    extractDocumentMeta(graph, node, evidenceCache)
                            .ifPresent(doc -> index.documents.putIfAbsent(doc.documentId(), doc));
                } else if ("CONFIGURATION_WITNESS".equals(k)) {
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null) continue;
                        var payload = parseEvidence(ev, evidenceCache);
                        String regionId = optString(payload, "factRegionIdentity");
                        if (!regionId.isEmpty()) {
                            regionWitnesses.computeIfAbsent(regionId, r -> new TreeSet<>()).add(node.identity());
                        }
                    }
                }
            }
            for (var edge : graph.edges(ctxId)) {
                String ek = edge.kind();
                if ("spring.has-witness".equals(ek) && !edge.target().identities().isEmpty()) {
                    for (String wid : edge.target().identities()) {
                        regionWitnesses.computeIfAbsent(edge.source(), r -> new TreeSet<>()).add(wid);
                    }
                } else if ("spring.has-truth-region".equals(ek) && !edge.target().identities().isEmpty()) {
                    factToRegion.putIfAbsent(edge.source(), edge.target().identities().getFirst());
                } else if ("spring.definition-fact".equals(ek) && !edge.target().identities().isEmpty()) {
                    String reg = factToRegion.get(edge.source());
                    if (reg != null) {
                        definitionRegions.putIfAbsent(edge.target().identities().getFirst(), reg);
                    }
                }
            }
            // Second edge pass for spring.definition-fact in case edge order differed
            for (var edge : graph.edges(ctxId)) {
                if ("spring.definition-fact".equals(edge.kind()) && !edge.target().identities().isEmpty()) {
                    String reg = factToRegion.get(edge.source());
                    if (reg != null) {
                        definitionRegions.putIfAbsent(edge.target().identities().getFirst(), reg);
                    }
                }
            }
        }

        // Parse TRUTH_REGION nodes now that witnesses are collected
        for (String ctxId : scopeContexts) {
            for (var node : graph.nodes(ctxId)) {
                if (!"TRUTH_REGION".equals(node.kind())) continue;
                parseTruthRegion(graph, node, worldFilter, regionWitnesses, evidenceCache)
                        .ifPresent(rm -> index.regions.putIfAbsent(rm.regionId(), rm));
            }
        }

        // Collect all traversable nodes and identify endpoint owners + annotations
        var nodeKindById = new HashMap<String, String>();
        for (String ctxId : scopeContexts) {
            for (var node : graph.nodes(ctxId)) {
                nodeKindById.putIfAbsent(node.identity(), node.kind());
                if (NON_TRAVERSABLE_NODE_KINDS.contains(node.kind())) {
                    continue;
                }
                index.contextsByNode.computeIfAbsent(node.identity(), id -> new LinkedHashSet<>()).add(ctxId);
                rawNodeEvidence.computeIfAbsent(node.identity(), id -> new LinkedHashSet<>()).addAll(node.evidence());
                // Prefer non-generic kind if the same node ID appears across Java & Spring contexts
                if (!rawNodes.containsKey(node.identity()) || shouldPreferNodeKind(node.kind(), rawNodes.get(node.identity()).kind())) {
                    rawNodes.put(node.identity(), node);
                    rawNodeContext.put(node.identity(), ctxId);
                }
            }
        }

        // Identify controller/endpoint owners and annotation edges
        var parentOf = new HashMap<String, String>();
        for (String ctxId : scopeContexts) {
            for (var edge : graph.edges(ctxId)) {
                if (edge.target().identities().isEmpty()) continue;
                String targetId = edge.target().identities().getFirst();
                String targetKind = nodeKindById.getOrDefault(targetId, "");
                if ("graph.contains".equals(edge.kind()) || "graph.declares".equals(edge.kind())) {
                    parentOf.putIfAbsent(targetId, edge.source());
                    if (API_ROUTE_KINDS.contains(targetKind)) {
                        endpointOwners.add(edge.source());
                    }
                } else if ("java.annotated-by".equals(edge.kind()) || "java.uses-annotation".equals(edge.kind())) {
                    for (String tid : edge.target().identities()) {
                        var tNode = rawNodes.get(tid);
                        if (tNode != null) {
                            String annName = extractSimpleNameFromEvidence(graph, tNode.evidence(), evidenceCache);
                            if (!annName.isEmpty()) {
                                nodeAnnotations.computeIfAbsent(edge.source(), s -> new LinkedHashSet<>()).add(annName);
                            }
                        }
                    }
                }
            }
        }
        // Lift endpoint ownership from handler method -> owning controller class
        var liftedEndpointOwners = new HashSet<>(endpointOwners);
        for (String owner : endpointOwners) {
            String parent = parentOf.get(owner);
            if (parent != null && "TYPE".equals(nodeKindById.get(parent))) {
                liftedEndpointOwners.add(parent);
            }
        }
        endpointOwners.addAll(liftedEndpointOwners);

        // Pass 2: Extract full NodeMeta for every traversable node
        for (var entry : rawNodes.entrySet()) {
            String nodeId = entry.getKey();
            KnowledgeGraph.Node node = entry.getValue();
            String ownerCtx = rawNodeContext.get(nodeId);
            TrustTier defaultTier = contextDefaultTiers.getOrDefault(ownerCtx, TrustTier.COMPILER);
            Set<ContentDigest> allEv = rawNodeEvidence.getOrDefault(nodeId, new LinkedHashSet<>());

            NodeMeta meta = extractNodeMeta(
                    graph,
                    nodeId,
                    ownerCtx,
                    node.kind(),
                    allEv,
                    defaultTier,
                    index.documents,
                    index.regions,
                    definitionRegions,
                    endpointOwners,
                    nodeAnnotations.getOrDefault(nodeId, Set.of()),
                    evidenceCache);
            index.nodes.put(nodeId, meta);
        }

        // Pass 3: Build bidirectional adjacency across scoped contexts (deterministic order!)
        var linkSets = new HashMap<String, Map<String, DirectedLink>>();
        for (String ctxId : scopeContexts) {
            TrustTier defaultTier = contextDefaultTiers.getOrDefault(ctxId, TrustTier.COMPILER);
            for (var edge : graph.edges(ctxId)) {
                if (IGNORED_EDGE_KINDS.contains(edge.kind())) continue;
                if (edge.target().identities().isEmpty()) continue;
                String src = edge.source();
                if (!index.nodes.containsKey(src)) continue;

                Optional<RegionMeta> reg = edge.region().map(index.regions::get);
                Optional<String> edgeClass = reg.map(RegionMeta::classification);
                Optional<String> edgeWitness = reg.flatMap(RegionMeta::witnessId);
                // Also check direct evidence payload for classification / witnessId
                if (edgeClass.isEmpty() || edgeWitness.isEmpty()) {
                    var evMeta = extractEdgeSpringMeta(graph, edge, evidenceCache);
                    if (edgeClass.isEmpty()) edgeClass = evMeta.classification();
                    if (edgeWitness.isEmpty()) edgeWitness = evMeta.witnessId();
                }

                // If a specific world was requested and this conditional edge evaluates to NEVER in that world, skip it
                if (worldFilter.isPresent() && edgeClass.filter("NEVER"::equals).isPresent()) {
                    continue;
                }

                TrustTier edgeTier = classifyEdgeTrust(graph, edge, defaultTier, edgeClass.isPresent(), evidenceCache);
                for (String tgt : edge.target().identities()) {
                    if (tgt.equals(src) || !index.nodes.containsKey(tgt)) continue;
                    addLink(linkSets, src, new DirectedLink(tgt, edge.kind(), true, edgeTier, edgeClass, edgeWitness));
                    addLink(linkSets, tgt, new DirectedLink(src, edge.kind(), false, edgeTier, edgeClass, edgeWitness));
                }
            }
        }

        // Bridge frontend HTTP/route call candidates to matching API_ROUTE nodes when literal route path matches
        var routesByPath = new HashMap<String, List<String>>();
        for (var meta : index.nodes.values()) {
            if (meta.layer() == VerticalLayer.API_ROUTE && !meta.routePath().isEmpty()) {
                routesByPath.computeIfAbsent(normalizeRoutePath(meta.routePath()), p -> new ArrayList<>())
                        .add(meta.nodeId());
            }
        }
        for (var meta : index.nodes.values()) {
            if (meta.layer() == VerticalLayer.FRONTEND && !meta.routePath().isEmpty()) {
                var targets = routesByPath.getOrDefault(normalizeRoutePath(meta.routePath()), List.of());
                for (String routeNodeId : targets) {
                    if (!routeNodeId.equals(meta.nodeId())) {
                        addLink(linkSets, meta.nodeId(), new DirectedLink(
                                routeNodeId, "polyglot.route-bridge", true,
                                TrustTier.SOURCE_SYNTAX, Optional.empty(), Optional.empty()));
                        addLink(linkSets, routeNodeId, new DirectedLink(
                                meta.nodeId(), "polyglot.route-bridge", false,
                                TrustTier.SOURCE_SYNTAX, Optional.empty(), Optional.empty()));
                    }
                }
            }
        }

        for (var entry : linkSets.entrySet()) {
            var sortedLinks = new ArrayList<>(entry.getValue().values());
            sortedLinks.sort(Comparator
                    .comparing((DirectedLink l) -> index.nodes.get(l.neighborId()).layer().ordinal())
                    .thenComparing(DirectedLink::neighborId)
                    .thenComparing(DirectedLink::edgeKind));
            index.adjacency.put(entry.getKey(), List.copyOf(sortedLinks));
        }

        return index;
    }

    private static void addLink(
            Map<String, Map<String, DirectedLink>> linkSets,
            String from,
            DirectedLink link) {
        var map = linkSets.computeIfAbsent(from, k -> new LinkedHashMap<>());
        String key = link.neighborId() + "|" + link.edgeKind();
        DirectedLink prev = map.get(key);
        if (prev == null) {
            map.put(key, link);
        } else {
            Optional<String> cls = prev.classification().isPresent() ? prev.classification() : link.classification();
            Optional<String> wit = prev.witnessId().isPresent() ? prev.witnessId() : link.witnessId();
            map.put(key, new DirectedLink(
                    prev.neighborId(),
                    prev.edgeKind(),
                    prev.outgoing() || link.outgoing(),
                    combineTrust(prev.trustTier(), link.trustTier()),
                    cls,
                    wit));
        }
    }

    private static boolean shouldPreferNodeKind(String candidate, String existing) {
        if ("TYPE".equals(existing) && SPRING_DI_KINDS.contains(candidate)) return false;
        if (API_ROUTE_KINDS.contains(candidate) && !API_ROUTE_KINDS.contains(existing)) return true;
        if ("TYPE".equals(candidate) && "BEAN_PRODUCER".equals(existing)) return true;
        return false;
    }

    private static String resolveSeed(
            TraceIndex index,
            String primaryContext,
            String seed,
            Optional<String> seedKind) {
        String normalizedSeed = seed.strip();
        String normalizedPath = normalizedSeed.replace('\\', '/');
        String normalizedRoute = normalizeRoutePath(normalizedSeed);
        String kindFilter = seedKind.map(k -> k.toUpperCase(Locale.ROOT)).orElse("");

        record Candidate(int rank, int contextPenalty, int kindPenalty, int layerOrdinal, String nodeId) {}
        var candidates = new ArrayList<Candidate>();

        for (var meta : index.nodes.values()) {
            int rank = matchRank(meta, normalizedSeed, normalizedPath, normalizedRoute);
            if (rank < 0) continue;
            Set<String> nodeContexts = index.contextsByNode.getOrDefault(meta.nodeId(), Set.of());
            int ctxPenalty = nodeContexts.contains(primaryContext) ? 0 : 1;
            int kPenalty = 0;
            if (!kindFilter.isEmpty()) {
                boolean matchesKind = meta.kind().equalsIgnoreCase(kindFilter)
                        || meta.layer().name().equalsIgnoreCase(kindFilter);
                kPenalty = matchesKind ? 0 : 1;
            }
            candidates.add(new Candidate(rank, ctxPenalty, kPenalty, meta.layer().ordinal(), meta.nodeId()));
        }

        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator
                .comparingInt(Candidate::rank)
                .thenComparingInt(Candidate::kindPenalty)
                .thenComparingInt(Candidate::contextPenalty)
                .thenComparingInt(Candidate::layerOrdinal)
                .thenComparing(Candidate::nodeId));
        return candidates.getFirst().nodeId();
    }

    private static int matchRank(NodeMeta meta, String seed, String normalizedPath, String normalizedRoute) {
        if (meta.nodeId().equals(seed)) return 0;
        if (!meta.qualifiedName().isEmpty() && meta.qualifiedName().equals(seed)) return 1;
        if (!meta.canonicalName().isEmpty() && meta.canonicalName().equals(seed)) return 1;
        if (!meta.label().isEmpty() && meta.label().equals(seed)) return 2;
        if (!meta.routePath().isEmpty() && (meta.routePath().equalsIgnoreCase(seed)
                || normalizeRoutePath(meta.routePath()).equalsIgnoreCase(normalizedRoute))) {
            return 2;
        }
        if (meta.sourcePath().isPresent()) {
            String sp = meta.sourcePath().get().replace('\\', '/');
            if (sp.equals(normalizedPath) || sp.endsWith("/" + normalizedPath)) return 3;
        }
        if (!meta.simpleName().isEmpty() && meta.simpleName().equals(seed)) return 4;
        if (!meta.qualifiedName().isEmpty() && meta.qualifiedName().equalsIgnoreCase(seed)) return 5;
        if (!meta.simpleName().isEmpty() && meta.simpleName().equalsIgnoreCase(seed)) return 6;
        if (!meta.label().isEmpty() && meta.label().equalsIgnoreCase(seed)) return 6;
        if (!meta.routePath().isEmpty() && meta.routePath().toLowerCase(Locale.ROOT).contains(normalizedRoute.toLowerCase(Locale.ROOT))
                && normalizedRoute.length() >= 2) {
            return 7;
        }
        if (!meta.qualifiedName().isEmpty() && meta.qualifiedName().toLowerCase(Locale.ROOT).contains(seed.toLowerCase(Locale.ROOT))) {
            return 8;
        }
        if (meta.sourcePath().isPresent() && meta.sourcePath().get().toLowerCase(Locale.ROOT).contains(normalizedPath.toLowerCase(Locale.ROOT))) {
            return 9;
        }
        return -1;
    }

    private static NodeMeta extractNodeMeta(
            KnowledgeGraph graph,
            String nodeId,
            String ownerContext,
            String kind,
            Set<ContentDigest> evidenceRefs,
            TrustTier defaultTier,
            Map<String, DocumentMeta> documents,
            Map<String, RegionMeta> regions,
            Map<String, String> definitionRegions,
            Set<String> endpointOwners,
            Set<String> edgeAnnotations,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {

        String canonicalName = "";
        String qualifiedName = "";
        String simpleName = "";
        String routePath = "";
        String language = "";
        String sourcePath = "";
        String contentSha256 = "";
        int startLine = -1;
        int endLine = -1;
        TrustTier tier = null;
        String classification = "";
        String witnessId = "";
        var annotations = new LinkedHashSet<>(edgeAnnotations);
        var docIds = new LinkedHashSet<String>();
        var families = new LinkedHashSet<String>();

        for (ContentDigest ref : evidenceRefs) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            String family = ev.family();
            families.add(family);
            TrustTier fTier = classifyEvidenceFamily(family);
            if (fTier != null) {
                tier = combineTrust(tier, fTier);
            }
            var payload = parseEvidence(ev, evidenceCache);

            if ("java.declaration".equals(family)) {
                if (payload.get("entity") instanceof Map<?, ?> entObj) {
                    var ent = GraphJson.object(entObj);
                    String cn = optString(ent, "canonicalName");
                    if (!cn.isEmpty()) {
                        canonicalName = cn;
                        String decoded = decodeJavaQualifiedName(cn);
                        if (!decoded.isEmpty()) qualifiedName = decoded;
                    }
                    if (ent.get("declaration") instanceof Map<?, ?> declObj) {
                        var decl = GraphJson.object(declObj);
                        String docId = optString(decl, "document");
                        if (!docId.isEmpty()) {
                            docIds.add(docId);
                            var doc = documents.get(docId);
                            if (doc != null) {
                                if (sourcePath.isEmpty()) sourcePath = doc.path();
                                if (contentSha256.isEmpty()) contentSha256 = doc.contentSha256();
                            }
                        }
                        if (startLine < 0) startLine = optInt(decl, "startLine", -1);
                        if (endLine < 0) endLine = optInt(decl, "endLine", startLine);
                    }
                }
                collectAnnotations(payload.get("annotations"), annotations);
                collectAnnotations(payload.get("stereotypes"), annotations);
                String sp = optString(payload, "spelling");
                if (simpleName.isEmpty() && !sp.isEmpty() && sp.length() <= 128 && !sp.contains("\n")) {
                    simpleName = sp;
                }
            } else if ("spring.endpoint-declaration".equals(family)) {
                if (payload.get("route") instanceof Map<?, ?> routeObj) {
                    var route = GraphJson.object(routeObj);
                    String path = optString(route, "path");
                    String methods = "";
                    if (route.get("methods") instanceof List<?> ms) {
                        methods = String.join(",", ms.stream()
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .toList());
                    }
                    if (!path.isEmpty()) {
                        routePath = methods.isEmpty() ? path : methods + " " + path;
                        if (qualifiedName.isEmpty()) qualifiedName = routePath;
                        if (canonicalName.isEmpty()) canonicalName = routePath;
                    }
                    if (route.get("span") instanceof Map<?, ?> spanObj) {
                        var span = GraphJson.object(spanObj);
                        String docId = optString(span, "document");
                        if (!docId.isEmpty()) {
                            docIds.add(docId);
                            var doc = documents.get(docId);
                            if (doc != null) {
                                if (sourcePath.isEmpty()) sourcePath = doc.path();
                                if (contentSha256.isEmpty()) contentSha256 = doc.contentSha256();
                            }
                        }
                        if (startLine < 0) startLine = optInt(span, "startLine", -1);
                        if (endLine < 0) endLine = optInt(span, "endLine", startLine);
                    }
                }
            } else if ("spring.candidate".equals(family)) {
                if (payload.get("declaredNameKey") instanceof Map<?, ?> namesObj) {
                    String primary = optString(GraphJson.object(namesObj), "primary");
                    if (!primary.isEmpty()) {
                        if (qualifiedName.isEmpty()) qualifiedName = primary;
                        if (canonicalName.isEmpty()) canonicalName = primary;
                    }
                }
            } else if ("spring.injection-dependency".equals(family)) {
                if (payload.get("point") instanceof Map<?, ?> ptObj) {
                    var pt = GraphJson.object(ptObj);
                    String name = optString(pt, "memberName");
                    if (name.isEmpty()) name = optString(pt, "targetType");
                    if (name.isEmpty()) name = optString(pt, "name");
                    if (!name.isEmpty() && qualifiedName.isEmpty()) {
                        qualifiedName = name;
                    }
                }
            } else if (family.startsWith("polyglot.")) {
                var fields = payload.get("fields") instanceof Map<?, ?> f ? GraphJson.object(f) : payload;
                String p = decodePolyglotPath(fields.get("path"));
                if (p.isEmpty()) p = decodePolyglotPath(fields.get("rawPath"));
                if (!p.isEmpty() && sourcePath.isEmpty()) sourcePath = p;
                String lang = optString(fields, "language");
                if (!lang.isEmpty() && language.isEmpty()) language = lang;
                String digest = optString(fields, "contentDigest");
                if (digest.isEmpty()) digest = optString(fields, "contentSha256");
                if (!digest.isEmpty() && contentSha256.isEmpty()) contentSha256 = digest;
                String rp = optString(fields, "routePath");
                if (rp.isEmpty()) rp = optString(fields, "route");
                if (!rp.isEmpty() && routePath.isEmpty()) routePath = rp;

                if (fields.get("fact") instanceof Map<?, ?> factObj) {
                    var fact = GraphJson.object(factObj);
                    String rawName = optString(fact, "name");
                    if (fact.get("attributes") instanceof Map<?, ?> attrObj) {
                        var attrs = GraphJson.object(attrObj);
                        String qn = optString(attrs, "qualifiedName");
                        if (!qn.isEmpty()) rawName = qn;
                        String attrRoute = optString(attrs, "route");
                        if (attrRoute.isEmpty()) attrRoute = optString(attrs, "routePath");
                        if (!attrRoute.isEmpty() && routePath.isEmpty()) routePath = attrRoute;
                    }
                    if (!rawName.isEmpty() && qualifiedName.isEmpty()) {
                        qualifiedName = sourcePath.isEmpty() ? rawName : sourcePath + "#" + rawName;
                        simpleName = rawName;
                    }
                    if (fact.get("span") instanceof Map<?, ?> spanObj) {
                        var span = GraphJson.object(spanObj);
                        if (startLine < 0) startLine = optInt(span, "startLine", -1);
                        if (endLine < 0) endLine = optInt(span, "endLine", startLine);
                    }
                }
            }

            // Generic fallback extraction for structured evidence payloads
            if (sourcePath.isEmpty()) {
                String p = optString(payload, "sourcePath");
                if (p.isEmpty()) p = optString(payload, "path");
                if (!p.isEmpty()) sourcePath = p;
            }
            if (contentSha256.isEmpty()) {
                String h = optString(payload, "contentSha256");
                if (h.isEmpty()) h = optString(payload, "contentDigest");
                if (!h.isEmpty()) contentSha256 = h;
            }
            if (startLine < 0) {
                startLine = optInt(payload, "startLine", optInt(payload, "line", -1));
            }
            if (endLine < 0) {
                endLine = optInt(payload, "endLine", startLine);
            }
            if (payload.get("span") instanceof Map<?, ?> spanObj) {
                var span = GraphJson.object(spanObj);
                String docId = optString(span, "document");
                if (!docId.isEmpty()) {
                    docIds.add(docId);
                    var doc = documents.get(docId);
                    if (doc != null) {
                        if (sourcePath.isEmpty()) sourcePath = doc.path();
                        if (contentSha256.isEmpty()) contentSha256 = doc.contentSha256();
                    }
                }
                if (startLine < 0) startLine = optInt(span, "startLine", -1);
                if (endLine < 0) endLine = optInt(span, "endLine", startLine);
            }
            if (qualifiedName.isEmpty()) {
                String qn = optString(payload, "qualifiedName");
                if (qn.isEmpty()) qn = optString(payload, "label");
                if (qn.isEmpty()) qn = optString(payload, "name");
                if (!qn.isEmpty()) qualifiedName = qn;
            }
            if (routePath.isEmpty()) {
                String rp = optString(payload, "routePath");
                if (rp.isEmpty()) rp = optString(payload, "endpoint");
                if (!rp.isEmpty()) routePath = rp;
            }
            if (language.isEmpty()) {
                language = optString(payload, "language");
            }
            if (classification.isEmpty()) {
                classification = optString(payload, "classification");
            }
            if (witnessId.isEmpty()) {
                witnessId = optString(payload, "witnessId");
            }
            collectAnnotations(payload.get("annotations"), annotations);
            collectAnnotations(payload.get("stereotypes"), annotations);
        }

        // Attach DEFINITION_PRESENT truth region classification/witness if registered for this node
        String defRegionId = definitionRegions.get(nodeId);
        if (defRegionId != null) {
            RegionMeta rm = regions.get(defRegionId);
            if (rm != null) {
                if (classification.isEmpty()) classification = rm.classification();
                if (witnessId.isEmpty() && rm.witnessId().isPresent()) witnessId = rm.witnessId().get();
                tier = combineTrust(tier, TrustTier.SPRING_SAT);
            }
        }

        if (simpleName.isEmpty() && !qualifiedName.isEmpty()) {
            simpleName = extractSimpleName(qualifiedName);
        }
        String label = !qualifiedName.isEmpty()
                ? qualifiedName
                : (!canonicalName.isEmpty()
                ? canonicalName
                : (!routePath.isEmpty()
                ? routePath
                : (!sourcePath.isEmpty() ? sourcePath : shortHashLabel(nodeId))));

        if (tier == null) {
            tier = defaultTier;
        }
        if (SPRING_DI_KINDS.contains(kind) && tier == TrustTier.COMPILER) {
            tier = TrustTier.SPRING_SAT;
        }

        VerticalLayer layer = classifyLayer(
                kind, qualifiedName, simpleName, routePath, sourcePath, language,
                annotations, endpointOwners.contains(nodeId), families);

        return new NodeMeta(
                nodeId,
                ownerContext,
                kind,
                layer,
                label,
                canonicalName,
                qualifiedName,
                simpleName,
                routePath,
                tier,
                classification.isEmpty() ? Optional.empty() : Optional.of(classification),
                witnessId.isEmpty() ? Optional.empty() : Optional.of(witnessId),
                sourcePath.isEmpty() ? Optional.empty() : Optional.of(sourcePath),
                startLine >= 1 ? OptionalInt.of(startLine) : OptionalInt.empty(),
                endLine >= 1 ? OptionalInt.of(Math.max(startLine, endLine)) : OptionalInt.empty(),
                contentSha256.isEmpty() ? Optional.empty() : Optional.of(contentSha256),
                Collections.unmodifiableSet(docIds));
    }

    private static VerticalLayer classifyLayer(
            String kind,
            String qualifiedName,
            String simpleName,
            String routePath,
            String sourcePath,
            String language,
            Set<String> annotations,
            boolean producesEndpoint,
            Set<String> families) {

        String upperKind = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
        String lowerPath = sourcePath == null ? "" : sourcePath.toLowerCase(Locale.ROOT).replace('\\', '/');
        String lowerLang = language == null ? "" : language.toLowerCase(Locale.ROOT);
        String lowerQual = qualifiedName == null ? "" : qualifiedName.toLowerCase(Locale.ROOT);

        // 1. SQL / Terraform (canonical or advisory nodes if present)
        if (upperKind.equals("SQL_TABLE") || upperKind.equals("DATABASE_TABLE")
                || families.stream().anyMatch(f -> f.startsWith("sql.") || f.startsWith("advisory.sql"))) {
            return VerticalLayer.SQL_TABLE;
        }
        if (upperKind.equals("TERRAFORM_RESOURCE") || upperKind.equals("IAC_RESOURCE")
                || families.stream().anyMatch(f -> f.startsWith("terraform.") || f.startsWith("advisory.terraform"))) {
            return VerticalLayer.TERRAFORM_RESOURCE;
        }

        // 2. API_ROUTE (explicit endpoint / route nodes)
        if (API_ROUTE_KINDS.contains(upperKind)
                || families.contains("spring.endpoint-declaration")) {
            return VerticalLayer.API_ROUTE;
        }

        // 3. FRONTEND (TS/JS/Vue/Svelte/HTML UI components & calls)
        if (upperKind.equals("FRONTEND_COMPONENT") || upperKind.equals("UI_COMPONENT")
                || FRONTEND_LANGUAGES.contains(lowerLang)
                || hasAnySuffix(lowerPath, FRONTEND_EXTENSIONS)) {
            return VerticalLayer.FRONTEND;
        }

        // 4. SPRING_DI (bean candidates, injection points, Spring wiring nodes)
        if (SPRING_DI_KINDS.contains(upperKind)) {
            return VerticalLayer.SPRING_DI;
        }
        if ("BEAN_PRODUCER".equals(upperKind)
                && !producesEndpoint
                && !hasAnyAnnotation(annotations, CONTROLLER_ANNOTATIONS)
                && !hasAnyAnnotation(annotations, JPA_ANNOTATIONS)
                && !simpleName.endsWith("Controller")
                && !simpleName.endsWith("Service")
                && !simpleName.endsWith("Repository")
                && !simpleName.endsWith("Entity")) {
            return VerticalLayer.SPRING_DI;
        }

        // 5. SPRING_CONTROLLER (@RestController / @Controller / endpoint producers)
        if (upperKind.equals("SPRING_CONTROLLER")
                || producesEndpoint
                || hasAnyAnnotation(annotations, CONTROLLER_ANNOTATIONS)
                || simpleName.endsWith("Controller")
                || simpleName.endsWith("RestResource")
                || lowerQual.contains(".controller.")
                || lowerQual.contains(".controllers.")
                || lowerQual.contains(".web.")) {
            return VerticalLayer.SPRING_CONTROLLER;
        }

        // 6. JPA_ENTITY (@Entity / @Table / @Repository / JPA entities & repositories)
        if (upperKind.equals("JPA_ENTITY")
                || upperKind.equals("REPOSITORY")
                || hasAnyAnnotation(annotations, JPA_ANNOTATIONS)
                || simpleName.endsWith("Entity")
                || simpleName.endsWith("Repository")
                || simpleName.endsWith("JpaRepo")
                || simpleName.endsWith("Dao")
                || lowerQual.contains(".entity.")
                || lowerQual.contains(".entities.")
                || lowerQual.contains(".repository.")
                || lowerQual.contains(".repositories.")
                || lowerQual.contains(".persistence.")) {
            return VerticalLayer.JPA_ENTITY;
        }

        // 7. SERVICE (Java/polyglot service & domain types)
        return VerticalLayer.SERVICE;
    }

    private static List<Map<String, Object>> collectRelevantGaps(
            KnowledgeGraph graph,
            List<String> scopeContexts,
            TraceIndex index,
            Set<String> tracedNodeIds,
            Map<String, TreeSet<String>> gapReasonByNode,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {

        var tracedPaths = new HashMap<String, Set<String>>();
        var tracedDocs = new HashMap<String, Set<String>>();
        var tracedNames = new HashMap<String, Set<String>>();

        for (String nodeId : tracedNodeIds) {
            NodeMeta meta = index.nodes.get(nodeId);
            if (meta == null) continue;
            meta.sourcePath().ifPresent(p -> tracedPaths.computeIfAbsent(p, k -> new TreeSet<>()).add(nodeId));
            for (String docId : meta.documentIds()) {
                tracedDocs.computeIfAbsent(docId, k -> new TreeSet<>()).add(nodeId);
            }
            if (!meta.qualifiedName().isEmpty()) {
                tracedNames.computeIfAbsent(meta.qualifiedName(), k -> new TreeSet<>()).add(nodeId);
            }
        }

        var gapsById = new TreeMap<String, Map<String, Object>>();
        for (String ctxId : scopeContexts) {
            // Check direct edges between gap nodes and traced nodes
            var gapEdgeTargets = new HashMap<String, Set<String>>();
            for (var edge : graph.edges(ctxId)) {
                if (tracedNodeIds.contains(edge.source())) {
                    for (String t : edge.target().identities()) {
                        gapEdgeTargets.computeIfAbsent(t, k -> new TreeSet<>()).add(edge.source());
                    }
                }
                for (String t : edge.target().identities()) {
                    if (tracedNodeIds.contains(t)) {
                        gapEdgeTargets.computeIfAbsent(edge.source(), k -> new TreeSet<>()).add(t);
                    }
                }
            }

            for (var node : graph.nodes(ctxId)) {
                if (!"CAPABILITY_GAP".equals(node.kind())) continue;
                if (gapsById.containsKey(node.identity())) continue;

                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null || !"evidence.gap".equals(ev.family())) continue;
                    var payload = parseEvidence(ev, evidenceCache);

                    String reasonCode = optString(payload, "reasonCode");
                    String mechanismCategory = optString(payload, "mechanismCategory");
                    var affectedNodes = new TreeSet<String>();
                    var gapPaths = new TreeSet<String>();

                    // Match via direct graph edge
                    affectedNodes.addAll(gapEdgeTargets.getOrDefault(node.identity(), Set.of()));

                    // Match via subject (identity or qualifiedName)
                    if (payload.get("subject") instanceof Map<?, ?> subjObj) {
                        var subj = GraphJson.object(subjObj);
                        for (String key : List.of("identity", "entity", "target", "nodeId", "value", "reference", "label")) {
                            String val = optString(subj, key);
                            if (val.isEmpty()) continue;
                            if (tracedNodeIds.contains(val)) {
                                affectedNodes.add(val);
                            }
                            if (tracedNames.containsKey(val)) {
                                affectedNodes.addAll(tracedNames.get(val));
                            }
                            if (tracedPaths.containsKey(val)) {
                                affectedNodes.addAll(tracedPaths.get(val));
                                gapPaths.add(val);
                            }
                        }
                    }

                    // Match via sourceSpans (document ID or resolved source path)
                    if (payload.get("sourceSpans") instanceof List<?> spans) {
                        for (Object sObj : spans) {
                            if (!(sObj instanceof Map<?, ?> sm)) continue;
                            var span = GraphJson.object(sm);
                            String docId = optString(span, "document");
                            if (!docId.isEmpty()) {
                                if (tracedDocs.containsKey(docId)) {
                                    affectedNodes.addAll(tracedDocs.get(docId));
                                }
                                var dm = index.documents.get(docId);
                                if (dm != null && !dm.path().isEmpty()) {
                                    gapPaths.add(dm.path());
                                    if (tracedPaths.containsKey(dm.path())) {
                                        affectedNodes.addAll(tracedPaths.get(dm.path()));
                                    }
                                }
                            }
                            String sp = optString(span, "path");
                            if (!sp.isEmpty()) {
                                gapPaths.add(sp);
                                if (tracedPaths.containsKey(sp)) {
                                    affectedNodes.addAll(tracedPaths.get(sp));
                                }
                            }
                        }
                    }

                    if (affectedNodes.isEmpty()) {
                        continue;
                    }

                    for (String nid : affectedNodes) {
                        if (!reasonCode.isEmpty()) {
                            gapReasonByNode.computeIfAbsent(nid, k -> new TreeSet<>()).add(reasonCode);
                        }
                    }

                    var gapView = new TreeMap<String, Object>();
                    gapView.put("gapId", node.identity());
                    gapView.put("mechanismCategory", mechanismCategory);
                    gapView.put("reasonCode", reasonCode);
                    gapView.put("affectedNodeIds", List.copyOf(affectedNodes));
                    if (!gapPaths.isEmpty()) {
                        gapView.put("sourcePaths", List.copyOf(gapPaths));
                    }
                    if (payload.get("limitations") instanceof List<?> lims) {
                        var limStrings = lims.stream()
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .toList();
                        if (!limStrings.isEmpty()) {
                            gapView.put("limitations", limStrings);
                        }
                    }
                    gapsById.put(node.identity(), Collections.unmodifiableMap(gapView));
                    break;
                }
            }
        }
        return List.copyOf(gapsById.values());
    }

    private static Optional<DocumentMeta> extractDocumentMeta(
            KnowledgeGraph graph,
            KnowledgeGraph.Node node,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {
        for (ContentDigest ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null || !"source.document".equals(ev.family())) continue;
            var payload = parseEvidence(ev, evidenceCache);
            String path = optString(payload, "path");
            String digest = optString(payload, "contentDigest");
            if (digest.isEmpty()) digest = optString(payload, "contentSha256");
            return Optional.of(new DocumentMeta(node.identity(), path, digest));
        }
        return Optional.empty();
    }

    private static Optional<RegionMeta> parseTruthRegion(
            KnowledgeGraph graph,
            KnowledgeGraph.Node node,
            Optional<String> worldFilter,
            Map<String, TreeSet<String>> regionWitnesses,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {
        for (ContentDigest ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            if (!"spring.explicit-region".equals(ev.family()) && !"spring.symbolic-region".equals(ev.family())) {
                continue;
            }
            var payload = parseEvidence(ev, evidenceCache);
            var regionObj = payload.get("region") instanceof Map<?, ?> rm ? GraphJson.object(rm) : payload;
            String classification = optString(regionObj, "classification");

            if (worldFilter.isPresent() && "spring.explicit-region".equals(ev.family())) {
                String w = worldFilter.get();
                if (containsString(regionObj.get("trueWorlds"), w)) {
                    classification = "MUST";
                } else if (containsString(regionObj.get("falseWorlds"), w)) {
                    classification = "NEVER";
                } else if (containsString(regionObj.get("unknownWorlds"), w)) {
                    classification = "UNKNOWN";
                }
            }
            if (classification.isEmpty()) {
                classification = "UNKNOWN";
            }

            Optional<String> witnessId = Optional.ofNullable(regionWitnesses.get(node.identity()))
                    .filter(s -> !s.isEmpty())
                    .map(TreeSet::first);
            if (witnessId.isEmpty()) {
                String inlineWitness = optString(regionObj, "witnessId");
                if (inlineWitness.isEmpty()) inlineWitness = optString(payload, "witnessId");
                if (!inlineWitness.isEmpty()) witnessId = Optional.of(inlineWitness);
            }

            Optional<String> definitionId = Optional.empty();
            if (regionObj.get("fact") instanceof Map<?, ?> fObj) {
                String def = optString(GraphJson.object(fObj), "definition");
                if (!def.isEmpty()) definitionId = Optional.of(def);
            }
            return Optional.of(new RegionMeta(node.identity(), classification, witnessId, definitionId));
        }
        return Optional.empty();
    }

    private record EdgeSpringMeta(Optional<String> classification, Optional<String> witnessId) {}

    private static EdgeSpringMeta extractEdgeSpringMeta(
            KnowledgeGraph graph,
            KnowledgeGraph.Edge edge,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {
        String cls = "";
        String wit = "";
        for (ContentDigest ref : edge.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            var payload = parseEvidence(ev, evidenceCache);
            if (cls.isEmpty()) {
                cls = optString(payload, "classification");
                if (cls.isEmpty() && payload.get("region") instanceof Map<?, ?> rm) {
                    cls = optString(GraphJson.object(rm), "classification");
                }
            }
            if (wit.isEmpty()) {
                wit = optString(payload, "witnessId");
                if (wit.isEmpty() && payload.get("region") instanceof Map<?, ?> rm) {
                    wit = optString(GraphJson.object(rm), "witnessId");
                }
            }
        }
        return new EdgeSpringMeta(
                cls.isEmpty() ? Optional.empty() : Optional.of(cls),
                wit.isEmpty() ? Optional.empty() : Optional.of(wit));
    }

    private static TrustTier resolveHopTrustTier(
            NodeMeta meta,
            TrustTier edgeTrustTier,
            Optional<String> edgeClassification) {
        if (meta.layer() == VerticalLayer.SPRING_DI) {
            return meta.trustTier() == TrustTier.ADVISORY_UNVERIFIED
                    ? TrustTier.ADVISORY_UNVERIFIED
                    : TrustTier.SPRING_SAT;
        }
        if (meta.trustTier() != null) {
            return meta.trustTier();
        }
        return edgeTrustTier != null ? edgeTrustTier : TrustTier.COMPILER;
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
        if (family.startsWith("advisory.")) return TrustTier.ADVISORY_UNVERIFIED;
        if (family.startsWith("spring.")) return TrustTier.SPRING_SAT;
        if (family.startsWith("scip.")) return TrustTier.COMPILER;
        if (family.startsWith("polyglot.native.")) return TrustTier.COMPILER;
        if (family.startsWith("polyglot.") || family.startsWith("syntax.")) return TrustTier.SOURCE_SYNTAX;
        if (family.startsWith("java.skeleton")) return TrustTier.SOURCE_SYNTAX;
        if (family.startsWith("java.") || family.startsWith("module.") || family.startsWith("build.")
                || "analysis.manifest".equals(family)) {
            return TrustTier.COMPILER;
        }
        return null;
    }

    private static TrustTier classifyEdgeTrust(
            KnowledgeGraph graph,
            KnowledgeGraph.Edge edge,
            TrustTier contextDefaultTier,
            boolean hasSpringClassification,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {
        if (edge.region().isPresent() || hasSpringClassification || edge.kind().startsWith("spring.")) {
            return TrustTier.SPRING_SAT;
        }
        if (edge.kind().startsWith("syntax.") || edge.kind().startsWith("polyglot.source")) {
            return TrustTier.SOURCE_SYNTAX;
        }
        if (edge.target().state() != KnowledgeGraph.TargetState.RESOLVED) {
            return TrustTier.SOURCE_SYNTAX;
        }
        TrustTier combined = null;
        for (ContentDigest ref : edge.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            TrustTier t = classifyEvidenceFamily(ev.family());
            if (t != null) {
                combined = combineTrust(combined, t);
            }
        }
        return combined != null ? combined : contextDefaultTier;
    }

    private static TrustTier combineTrust(TrustTier a, TrustTier b) {
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

    private static void collectAnnotations(Object raw, Set<String> target) {
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    target.add(normalizeAnnotationName(s));
                } else if (item instanceof Map<?, ?> m) {
                    String name = optString(GraphJson.object(m), "name");
                    if (name.isEmpty()) name = optString(GraphJson.object(m), "type");
                    if (!name.isEmpty()) target.add(normalizeAnnotationName(name));
                }
            }
        }
    }

    private static String normalizeAnnotationName(String raw) {
        String s = raw.strip();
        if (s.startsWith("@")) s = s.substring(1).strip();
        int paren = s.indexOf('(');
        if (paren >= 0) s = s.substring(0, paren).strip();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static boolean hasAnyAnnotation(Set<String> present, Set<String> expected) {
        for (String a : present) {
            if (expected.contains(a)) return true;
        }
        return false;
    }

    private static boolean hasAnySuffix(String path, Set<String> suffixes) {
        for (String suf : suffixes) {
            if (path.endsWith(suf)) return true;
        }
        return false;
    }

    private static boolean containsString(Object rawList, String needle) {
        if (!(rawList instanceof List<?> list)) return false;
        for (Object item : list) {
            if (needle.equals(item)) return true;
        }
        return false;
    }

    private static String extractSimpleNameFromEvidence(
            KnowledgeGraph graph,
            List<ContentDigest> refs,
            Map<ContentDigest, Map<String, Object>> evidenceCache) {
        for (var ref : refs) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            var payload = parseEvidence(ev, evidenceCache);
            if ("java.declaration".equals(ev.family()) && payload.get("entity") instanceof Map<?, ?> entObj) {
                String cn = optString(GraphJson.object(entObj), "canonicalName");
                if (!cn.isEmpty()) {
                    return extractSimpleName(decodeJavaQualifiedName(cn));
                }
            }
            String qn = optString(payload, "qualifiedName");
            if (!qn.isEmpty()) return extractSimpleName(qn);
        }
        return "";
    }

    private static String normalizeRoutePath(String raw) {
        String s = raw == null ? "" : raw.strip();
        int space = s.indexOf(' ');
        if (space > 0 && space + 1 < s.length()) {
            String verb = s.substring(0, space).toUpperCase(Locale.ROOT);
            if (verb.matches("(?:GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)(?:,(?:GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS))*")) {
                s = s.substring(space + 1).strip();
            }
        }
        return s;
    }

    private static String extractSimpleName(String qualified) {
        int hash = qualified.lastIndexOf('#');
        String base = hash >= 0 && hash + 1 < qualified.length() ? qualified.substring(hash + 1) : qualified;
        int dot = base.lastIndexOf('.');
        return dot >= 0 && dot + 1 < base.length() ? base.substring(dot + 1) : base;
    }

    private static String decodePolyglotPath(Object rawValue) {
        if (!(rawValue instanceof String text) || text.isBlank()) return "";
        try {
            var decoded = new com.evolution.analysis.acquisition.streaming.LogicalPath(text).utf8();
            if (decoded.isPresent()) return decoded.get();
        } catch (IllegalArgumentException ignored) {
            // Fall through to raw path
        }
        return text;
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
                    .map(VerticalTraceEngine::decodePercentEscapes)
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

    private static String optString(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s ? s : "";
    }

    private static int optInt(Map<String, Object> map, String key, int fallback) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        return fallback;
    }

    /**
     * Composes cached intra-method error propagation summaries backwards from outbound RPC/HTTP edges
     * ({@code CALLS_SERVICE}/{@code INVOKES_RPC}) to inbound endpoints ({@code ENDPOINT}/{@code GRPC_HANDLER})
     * using order-independent XOR path-permutation deduplication and bounded branching.
     */
    public static FailCloseResilienceAnalyzer.ResilienceReport traceFailClosePaths(
            KnowledgeGraph graph,
            Map<String, List<FailCloseResilienceAnalyzer.MethodErrorSummary>> summariesByMethod) {
        return FailCloseResilienceAnalyzer.analyze(
                graph,
                summariesByMethod,
                FailCloseResilienceAnalyzer.AnalysisConfig.defaults());
    }

    /** Same as {@link #traceFailClosePaths(KnowledgeGraph, Map)} with explicit branching/depth bounds. */
    public static FailCloseResilienceAnalyzer.ResilienceReport traceFailClosePaths(
            KnowledgeGraph graph,
            Map<String, List<FailCloseResilienceAnalyzer.MethodErrorSummary>> summariesByMethod,
            FailCloseResilienceAnalyzer.AnalysisConfig config) {
        return FailCloseResilienceAnalyzer.analyze(graph, summariesByMethod, config);
    }

    /**
     * Traces a specific seed via {@link #trace(KnowledgeGraph, TraceRequest)} and filters the
     * passive counterfactual {@link FailCloseResilienceAnalyzer.ResilienceReport} to call paths
     * intersecting the traced nodes (preserving per-path verdicts and {@code MIXED_PATHS}).
     */
    public static FailCloseResilienceAnalyzer.ResilienceReport traceFailClosePathsForSeed(
            KnowledgeGraph graph,
            TraceRequest request,
            Map<String, List<FailCloseResilienceAnalyzer.MethodErrorSummary>> summariesByMethod) {
        VerticalTraceResult traced = trace(graph, request);
        FailCloseResilienceAnalyzer.ResilienceReport full = traceFailClosePaths(graph, summariesByMethod);
        if (traced.hops().isEmpty() || full.pathVerdicts().isEmpty()) {
            return full;
        }
        var tracedIds = new HashSet<String>();
        for (TraceHop hop : traced.hops()) {
            tracedIds.add(hop.nodeId());
        }
        var filteredPaths = new TreeMap<FailCloseResilienceAnalyzer.PathKey, FailCloseResilienceAnalyzer.PathVerdict>();
        for (var entry : full.pathVerdicts().entrySet()) {
            var pv = entry.getValue();
            boolean touches = tracedIds.contains(pv.inboundId())
                    || tracedIds.contains(pv.outboundId())
                    || pv.flamegraph().stream().anyMatch(f -> tracedIds.contains(f.nodeId()));
            if (touches) {
                filteredPaths.put(entry.getKey(), pv);
            }
        }
        if (filteredPaths.isEmpty()) {
            return full;
        }
        var filteredPairs = new TreeMap<String, FailCloseResilienceAnalyzer.PairSummary>();
        for (var pv : filteredPaths.values()) {
            String pairKey = pv.inboundId() + "->" + pv.outboundId();
            var ps = full.pairSummaries().get(pairKey);
            if (ps != null) {
                filteredPairs.put(pairKey, ps);
            }
        }
        return new FailCloseResilienceAnalyzer.ResilienceReport(
                full.graphDigest(),
                full.trustTier(),
                filteredPaths,
                filteredPairs,
                full.gaps(),
                full.xorDedupCollapsedCount(),
                full.branchBoundedTruncated());
    }

    /**
     * Builds a deterministic call-path flamegraph view ({@code file:line} chain + per-path resilience
     * verdicts + pair summaries) suitable for REST and MCP {@code vertical_trace} consumers.
     */
    public static Map<String, Object> buildCallPathFlamegraph(
            VerticalTraceResult traceResult,
            FailCloseResilienceAnalyzer.ResilienceReport resilienceReport) {
        Objects.requireNonNull(traceResult, "traceResult");
        var frames = new ArrayList<Map<String, Object>>(traceResult.hops().size());
        var chainParts = new ArrayList<String>(traceResult.hops().size());
        for (TraceHop hop : traceResult.hops()) {
            String sp = hop.sourcePath().orElse("");
            int line = hop.startLine().orElse(0);
            int endLine = hop.endLine().orElse(line);
            var frame = new FailCloseResilienceAnalyzer.CallFrame(
                    hop.nodeId(), hop.label(), sp, line, endLine, hop.kind());
            var fv = new TreeMap<String, Object>(frame.toView());
            fv.put("layer", hop.layer().name());
            fv.put("trustTier", hop.trustTier().name());
            hop.edgeKind().ifPresent(ek -> fv.put("edgeKind", ek));
            hop.gapReason().ifPresent(gr -> fv.put("gapReason", gr));
            frames.add(Collections.unmodifiableMap(fv));
            chainParts.add(frame.fileLine());
        }

        int failClose = 0;
        int failOpen = 0;
        int unknown = 0;
        int mixedPairs = 0;
        var pathViews = new ArrayList<Map<String, Object>>();
        var pairViews = new ArrayList<Map<String, Object>>();
        var gapViews = new ArrayList<Map<String, Object>>();
        int xorCollapsed = 0;

        if (resilienceReport != null) {
            xorCollapsed = resilienceReport.xorDedupCollapsedCount();
            for (var pv : resilienceReport.pathVerdicts().values()) {
                pathViews.add(pv.toView());
                switch (pv.verdict()) {
                    case FAIL_CLOSE -> failClose++;
                    case FAIL_OPEN -> failOpen++;
                    case UNKNOWN -> unknown++;
                    case MIXED_PATHS -> {
                        failClose++;
                        failOpen++;
                    }
                }
            }
            for (var ps : resilienceReport.pairSummaries().values()) {
                pairViews.add(ps.toView());
                if (ps.aggregateVerdict() == FailCloseResilienceAnalyzer.ResilienceVerdict.MIXED_PATHS) {
                    mixedPairs++;
                }
            }
            for (var gap : resilienceReport.gaps()) {
                gapViews.add(FailCloseResilienceAnalyzer.gapToView(gap));
            }
        }

        String aggregateVerdict;
        if (failClose > 0 && failOpen > 0) {
            aggregateVerdict = FailCloseResilienceAnalyzer.ResilienceVerdict.MIXED_PATHS.name();
        } else if (unknown > 0) {
            aggregateVerdict = FailCloseResilienceAnalyzer.ResilienceVerdict.UNKNOWN.name();
        } else if (failClose > 0) {
            aggregateVerdict = FailCloseResilienceAnalyzer.ResilienceVerdict.FAIL_CLOSE.name();
        } else if (failOpen > 0) {
            aggregateVerdict = FailCloseResilienceAnalyzer.ResilienceVerdict.FAIL_OPEN.name();
        } else {
            aggregateVerdict = FailCloseResilienceAnalyzer.ResilienceVerdict.UNKNOWN.name();
        }

        var summary = new TreeMap<String, Object>();
        summary.put("failCloseCount", failClose);
        summary.put("failOpenCount", failOpen);
        summary.put("mixedPairCount", mixedPairs);
        summary.put("totalPaths", pathViews.size());
        summary.put("unknownCount", unknown);
        summary.put("xorDedupCollapsedCount", xorCollapsed);

        var out = new TreeMap<String, Object>();
        out.put("chain", String.join(" -> ", chainParts));
        out.put("frames", List.copyOf(frames));
        out.put("gaps", List.copyOf(gapViews));
        out.put("pairSummaries", List.copyOf(pairViews));
        out.put("paths", List.copyOf(pathViews));
        out.put("resilienceVerdict", aggregateVerdict);
        out.put("summary", Collections.unmodifiableMap(summary));
        out.put("trustTier", TrustTier.SOURCE_SYNTAX.name());
        return Collections.unmodifiableMap(out);
    }

    private static String shortHashLabel(String id) {
        if (id == null) return "unknown";
        int sha = id.lastIndexOf("sha256:");
        if (sha >= 0 && sha + 19 <= id.length()) {
            String prefix = sha > 0 ? id.substring(0, sha) : "";
            return prefix + id.substring(sha + 7, sha + 19);
        }
        return id;
    }
}
