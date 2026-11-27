package com.evolution.analysis.query.projection;

import com.evolution.analysis.assessment.v2.LeidenCommunityDetector;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Deterministic Levelized Dependency Structure Matrix (DSM) builder.
 *
 * <p>Aggregates directed project dependencies at {@link Granularity#MODULE},
 * {@link Granularity#PACKAGE}, or {@link Granularity#TYPE} granularity, orders items into
 * topological condensation levels via iterative O(V + E) {@link TarjanSccAnalyzer} with cyclic
 * SCC blocks placed contiguously along the diagonal, and emits a sparse {@code CanonicalJson}-safe
 * matrix view without mutating {@link KnowledgeGraph}.
 *
 * <p>Matrix convention: cell {@code (r, c)} indicates row {@code r} (dependent) depends on
 * column {@code c} (dependency). Acyclic cross-SCC dependencies appear strictly in the lower
 * triangle ({@code r > c}); cyclic back-edges appear in the upper triangle ({@code r < c}) or
 * diagonal ({@code r == c}) inside contiguous SCC blocks.
 */
public final class DsmMatrixBuilder {
    public static final int DEFAULT_MAX_ITEMS = 80;
    public static final int MIN_MAX_ITEMS = 1;
    public static final int MAX_MAX_ITEMS = 250;

    public enum Granularity {
        MODULE,
        PACKAGE,
        TYPE;

        public static Granularity parse(String raw) {
            if (raw == null || raw.isBlank()) return PACKAGE;
            return switch (raw.strip().toUpperCase(Locale.ROOT)) {
                case "MODULE" -> MODULE;
                case "PACKAGE" -> PACKAGE;
                case "TYPE" -> TYPE;
                default -> throw new IllegalArgumentException("Unsupported DSM granularity: " + raw);
            };
        }
    }

    public record DsmRequest(
            String context,
            Granularity granularity,
            Optional<String> communityId,
            int maxItems,
            Set<String> violatedEdgeKeys
    ) {
        public DsmRequest {
            Objects.requireNonNull(context, "context");
            if (context.isBlank()) throw new IllegalArgumentException("Explicit context required");
            granularity = granularity == null ? Granularity.PACKAGE : granularity;
            communityId = communityId == null
                    ? Optional.empty()
                    : communityId.map(String::strip).filter(s -> !s.isEmpty());
            maxItems = maxItems <= 0
                    ? DEFAULT_MAX_ITEMS
                    : Math.max(MIN_MAX_ITEMS, Math.min(MAX_MAX_ITEMS, maxItems));
            violatedEdgeKeys = violatedEdgeKeys == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new TreeSet<>(violatedEdgeKeys));
        }

        public static DsmRequest defaults(String context, Granularity granularity) {
            return new DsmRequest(context, granularity, Optional.empty(), DEFAULT_MAX_ITEMS, Set.of());
        }
    }

    public record DsmItem(
            int index,
            String id,
            String label,
            String kind,
            Optional<String> communityId,
            int inDegree,
            int outDegree,
            int sccIndex,
            TrustTier trustTier
    ) {
        public DsmItem {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(communityId, "communityId");
            Objects.requireNonNull(trustTier, "trustTier");
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("index", index);
            m.put("id", id);
            m.put("label", label);
            m.put("kind", kind);
            communityId.ifPresent(c -> m.put("communityId", c));
            m.put("inDegree", inDegree);
            m.put("outDegree", outDegree);
            m.put("sccIndex", sccIndex);
            m.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(m);
        }
    }

    public record DsmSccBlock(
            int index,
            int start,
            int end,
            int size,
            boolean cyclic,
            int level,
            List<String> members
    ) {
        public DsmSccBlock {
            members = List.copyOf(Objects.requireNonNull(members, "members"));
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("index", index);
            m.put("start", start);
            m.put("end", end);
            m.put("size", size);
            m.put("cyclic", cyclic);
            m.put("level", level);
            m.put("members", members);
            return Collections.unmodifiableMap(m);
        }
    }

    public record DsmCell(
            int r,
            int c,
            int weight,
            boolean isCycle,
            boolean isViolation,
            TrustTier trustTier
    ) {
        public DsmCell {
            Objects.requireNonNull(trustTier, "trustTier");
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("r", r);
            m.put("c", c);
            m.put("weight", weight);
            m.put("isCycle", isCycle);
            m.put("isViolation", isViolation);
            m.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(m);
        }
    }

    public record DsmMatrix(
            ContentDigest graphDigest,
            String context,
            Granularity granularity,
            Optional<String> communityId,
            BigDecimal levelizationScore,
            int totalItems,
            boolean truncated,
            List<DsmItem> order,
            List<DsmSccBlock> sccBlocks,
            List<DsmCell> cells
    ) {
        public DsmMatrix {
            Objects.requireNonNull(graphDigest, "graphDigest");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(granularity, "granularity");
            Objects.requireNonNull(communityId, "communityId");
            Objects.requireNonNull(levelizationScore, "levelizationScore");
            order = List.copyOf(Objects.requireNonNull(order, "order"));
            sccBlocks = List.copyOf(Objects.requireNonNull(sccBlocks, "sccBlocks"));
            cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
        }

        /** CanonicalJson-safe view map (all floating-point quantities encoded as {@link BigDecimal}). */
        public Map<String, Object> toView() {
            var view = new TreeMap<String, Object>();
            view.put("graphDigest", graphDigest.value());
            view.put("context", context);
            view.put("granularity", granularity.name());
            communityId.ifPresent(c -> view.put("communityId", c));
            view.put("levelizationScore", levelizationScore);
            view.put("totalItems", totalItems);
            view.put("truncated", truncated);
            view.put("order", order.stream().map(DsmItem::toView).toList());
            view.put("sccBlocks", sccBlocks.stream().map(DsmSccBlock::toView).toList());
            view.put("cells", cells.stream().map(DsmCell::toView).toList());
            return Collections.unmodifiableMap(view);
        }
    }

    private DsmMatrixBuilder() {}

    /** Builds a levelized DSM for the requested context and granularity. */
    public static DsmMatrix build(KnowledgeGraph graph, DsmRequest request) {
        return build(graph, request, null);
    }

    /** Builds a levelized DSM with cooperative cancellation checkpoints. */
    public static DsmMatrix build(KnowledgeGraph graph, DsmRequest request, Runnable checkpoint) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(request, "request");
        Runnable tick = checkpoint == null ? () -> {} : checkpoint;
        tick.run();

        var extracted = ContextGraphExtractor.extract(graph, request.context(), tick);
        var projectEntities = extracted.projectEntities();
        var projectEdges = extracted.projectEdges();

        if (projectEntities.isEmpty()) {
            return new DsmMatrix(
                    graph.identity(),
                    request.context(),
                    request.granularity(),
                    request.communityId(),
                    BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP),
                    0,
                    false,
                    List.of(),
                    List.of(),
                    List.of());
        }

        // Compute deterministic Leiden community assignments so items carry communityId
        // and optional communityId filtering matches MACRO/MESO community IDs 100%.
        Map<String, String> communityByEntity = computeCommunityAssignments(
                request.context(), projectEntities, projectEdges, tick);

        Optional<String> normalizedCommunityFilter = request.communityId()
                .map(DsmMatrixBuilder::normalizeCommunityId);

        List<ContextGraphExtractor.ExtractedEntity> scopedEntities;
        if (normalizedCommunityFilter.isPresent()) {
            String targetComm = normalizedCommunityFilter.get();
            scopedEntities = projectEntities.stream()
                    .filter(e -> targetComm.equals(communityByEntity.get(e.id())))
                    .toList();
        } else {
            scopedEntities = projectEntities;
        }

        if (scopedEntities.isEmpty()) {
            return new DsmMatrix(
                    graph.identity(),
                    request.context(),
                    request.granularity(),
                    normalizedCommunityFilter,
                    BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP),
                    0,
                    false,
                    List.of(),
                    List.of(),
                    List.of());
        }

        var scopedEntityIds = new HashSet<String>(scopedEntities.size() * 2);
        for (var e : scopedEntities) {
            scopedEntityIds.add(e.id());
        }

        // Aggregate items at chosen granularity
        record ItemAcc(
                String id,
                String label,
                String kind,
                Map<String, Integer> communityCounts,
                int memberCount,
                TrustTier trustTier
        ) {}
        var itemMap = new TreeMap<String, ItemAcc>();
        var entityToItemId = new HashMap<String, String>(scopedEntities.size() * 2);

        for (var entity : scopedEntities) {
            String itemId;
            String itemLabel;
            String itemKind;
            switch (request.granularity()) {
                case MODULE -> {
                    String modKey = !entity.moduleId().isEmpty()
                            ? entity.moduleId()
                            : "module:" + entity.moduleName();
                    itemId = modKey;
                    itemLabel = entity.moduleName();
                    itemKind = "MODULE";
                }
                case PACKAGE -> {
                    String pkgLabel = entity.packageName().isEmpty() ? "(default)" : entity.packageName();
                    String pkgKey = !entity.packageId().isEmpty()
                            ? entity.packageId()
                            : "package:" + pkgLabel;
                    itemId = pkgKey;
                    itemLabel = pkgLabel;
                    itemKind = "PACKAGE";
                }
                case TYPE -> {
                    itemId = entity.id();
                    itemLabel = entity.shortLabel();
                    itemKind = entity.kind();
                }
                default -> throw new IllegalStateException("Unexpected granularity: " + request.granularity());
            }
            entityToItemId.put(entity.id(), itemId);
            String cid = communityByEntity.get(entity.id());
            var prev = itemMap.get(itemId);
            if (prev == null) {
                var commCounts = new TreeMap<String, Integer>();
                if (cid != null) commCounts.put(cid, 1);
                itemMap.put(itemId, new ItemAcc(itemId, itemLabel, itemKind, commCounts, 1, entity.trustTier()));
            } else {
                if (cid != null) prev.communityCounts().merge(cid, 1, Integer::sum);
                itemMap.put(itemId, new ItemAcc(
                        prev.id(),
                        prev.label(),
                        prev.kind(),
                        prev.communityCounts(),
                        prev.memberCount() + 1,
                        ContextGraphExtractor.combineTrustTier(prev.trustTier(), entity.trustTier())));
            }
        }

        // Aggregate directed dependencies across items
        record AggEdge(String source, String target, int weight, boolean violation, TrustTier trustTier) {}
        var aggEdges = new TreeMap<String, AggEdge>();
        var rawDegree = new HashMap<String, Integer>(itemMap.size() * 2);

        for (var edge : projectEdges) {
            if (!scopedEntityIds.contains(edge.source()) || !scopedEntityIds.contains(edge.target())) {
                continue;
            }
            String srcItem = entityToItemId.get(edge.source());
            String tgtItem = entityToItemId.get(edge.target());
            if (srcItem == null || tgtItem == null) continue;
            // Suppress intra-module / intra-package self-aggregation; preserve explicit TYPE self-loops.
            if (srcItem.equals(tgtItem) && request.granularity() != Granularity.TYPE) {
                continue;
            }
            boolean violated = ContextGraphExtractor.isEdgeViolated(edge, request.violatedEdgeKeys())
                    || request.violatedEdgeKeys().contains(TarjanSccAnalyzer.edgeKey(srcItem, tgtItem))
                    || request.violatedEdgeKeys().contains(srcItem + "|" + tgtItem);
            String key = srcItem + "|" + tgtItem;
            var prev = aggEdges.get(key);
            if (prev == null) {
                aggEdges.put(key, new AggEdge(srcItem, tgtItem, edge.supportCount(), violated, edge.trustTier()));
                if (!srcItem.equals(tgtItem)) {
                    rawDegree.merge(srcItem, 1, Integer::sum);
                    rawDegree.merge(tgtItem, 1, Integer::sum);
                }
            } else {
                aggEdges.put(key, new AggEdge(
                        srcItem,
                        tgtItem,
                        prev.weight() + edge.supportCount(),
                        prev.violation() || violated,
                        ContextGraphExtractor.combineTrustTier(prev.trustTier(), edge.trustTier())));
            }
        }

        // Honest maxItems capping (retain highest-coupling items deterministically)
        int totalItems = itemMap.size();
        boolean truncated = totalItems > request.maxItems();
        List<ItemAcc> keptItems;
        if (truncated) {
            keptItems = itemMap.values().stream()
                    .sorted(Comparator
                            .comparingInt((ItemAcc it) -> rawDegree.getOrDefault(it.id(), 0)).reversed()
                            .thenComparing(Comparator.comparingInt(ItemAcc::memberCount).reversed())
                            .thenComparing(ItemAcc::id))
                    .limit(request.maxItems())
                    .toList();
        } else {
            keptItems = List.copyOf(itemMap.values());
        }

        var keptIds = new LinkedHashSet<String>(keptItems.size() * 2);
        var keptById = new HashMap<String, ItemAcc>(keptItems.size() * 2);
        for (var it : keptItems) {
            keptIds.add(it.id());
            keptById.put(it.id(), it);
        }

        var keptEdges = new ArrayList<AggEdge>();
        var tarjanEdges = new ArrayList<TarjanSccAnalyzer.DirectedEdge>();
        for (var edge : aggEdges.values()) {
            if (keptIds.contains(edge.source()) && keptIds.contains(edge.target())) {
                keptEdges.add(edge);
                tarjanEdges.add(new TarjanSccAnalyzer.DirectedEdge(edge.source(), edge.target(), edge.weight()));
            }
        }

        // Iterative O(V + E) Tarjan SCC + topological condensation levelization
        var sccResult = TarjanSccAnalyzer.analyze(keptIds, tarjanEdges, tick);
        var topoOrder = sccResult.topologicalOrder();
        var indexById = new HashMap<String, Integer>(topoOrder.size() * 2);
        for (int i = 0; i < topoOrder.size(); i++) {
            indexById.put(topoOrder.get(i), i);
        }

        // Build sparse DSM cells & exact in/out degrees in the emitted matrix
        int[] inDeg = new int[topoOrder.size()];
        int[] outDeg = new int[topoOrder.size()];
        var cells = new ArrayList<DsmCell>(keptEdges.size());
        for (var edge : keptEdges) {
            int r = indexById.get(edge.source());
            int c = indexById.get(edge.target());
            outDeg[r]++;
            inDeg[c]++;
            boolean isCycle = sccResult.cyclicEdgeKeys()
                    .contains(TarjanSccAnalyzer.edgeKey(edge.source(), edge.target()));
            cells.add(new DsmCell(r, c, edge.weight(), isCycle, edge.violation(), edge.trustTier()));
        }
        cells.sort(Comparator.comparingInt(DsmCell::r).thenComparingInt(DsmCell::c));

        // Build contiguous diagonal SCC blocks
        var sccBlocks = new ArrayList<DsmSccBlock>(sccResult.blocks().size());
        int cursor = 0;
        for (var block : sccResult.blocks()) {
            int size = block.members().size();
            int start = cursor;
            int end = cursor + size - 1;
            sccBlocks.add(new DsmSccBlock(
                    block.index(),
                    start,
                    end,
                    size,
                    block.cyclic(),
                    block.level(),
                    block.members()));
            cursor += size;
        }

        // Build ordered DSM items
        var order = new ArrayList<DsmItem>(topoOrder.size());
        for (int i = 0; i < topoOrder.size(); i++) {
            String id = topoOrder.get(i);
            ItemAcc acc = keptById.get(id);
            Optional<String> dominantComm = dominantCommunity(acc.communityCounts());
            order.add(new DsmItem(
                    i,
                    acc.id(),
                    acc.label(),
                    acc.kind(),
                    dominantComm,
                    inDeg[i],
                    outDeg[i],
                    sccResult.nodeToBlockIndex().get(id),
                    acc.trustTier()));
        }

        return new DsmMatrix(
                graph.identity(),
                request.context(),
                request.granularity(),
                normalizedCommunityFilter,
                sccResult.levelizationScoreDecimal(),
                totalItems,
                truncated,
                order,
                sccBlocks,
                cells);
    }

    private static Map<String, String> computeCommunityAssignments(
            String context,
            List<ContextGraphExtractor.ExtractedEntity> projectEntities,
            List<ContextGraphExtractor.ExtractedEdge> projectEdges,
            Runnable checkpoint) {
        var views = new ArrayList<ArchitectureQuery.EntityView>(projectEntities.size());
        for (var e : projectEntities) {
            views.add(e.toEntityView(context));
        }
        String commonPrefix = GraphProjectionService.computeCommonPackagePrefix(views, ArchitectureQuery.Scope.TYPE);
        var leidenNodes = new ArrayList<LeidenCommunityDetector.NodeInput>(projectEntities.size());
        for (int i = 0; i < projectEntities.size(); i++) {
            var ev = views.get(i);
            leidenNodes.add(new LeidenCommunityDetector.NodeInput(
                    ev.identity(),
                    GraphProjectionService.shortLabel(ev, ArchitectureQuery.Scope.TYPE),
                    GraphProjectionService.groupName(ev, ArchitectureQuery.Scope.TYPE, commonPrefix),
                    ev.kind()));
        }
        var leidenEdges = new ArrayList<LeidenCommunityDetector.EdgeInput>(projectEdges.size());
        for (var e : projectEdges) {
            leidenEdges.add(new LeidenCommunityDetector.EdgeInput(
                    e.source(), e.target(), Math.max(1.0, e.supportCount()), e.primaryKind()));
        }
        var result = LeidenCommunityDetector.detect(leidenNodes, leidenEdges, checkpoint);
        return result.communityByNode();
    }

    private static String normalizeCommunityId(String raw) {
        String s = raw.strip();
        if (s.startsWith(GraphProjectionService.COMMUNITY_ID_PREFIX)) {
            return s.substring(GraphProjectionService.COMMUNITY_ID_PREFIX.length());
        }
        return s;
    }

    private static Optional<String> dominantCommunity(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) return Optional.empty();
        String best = null;
        int bestCount = -1;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > bestCount
                    || (entry.getValue() == bestCount && (best == null || entry.getKey().compareTo(best) < 0))) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return Optional.ofNullable(best);
    }
}
