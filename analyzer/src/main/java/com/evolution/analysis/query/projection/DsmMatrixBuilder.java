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
    /** Deterministic zero-LLM SemRef misplaced-file candidate flag constant. */
    public static final String MISPLACED_FILE_CANDIDATE_FLAG = "MISPLACED_FILE_CANDIDATE";
    /** Fixed global margin thresholds (I5: zero per-repo tuning) preventing utility-edge false positives. */
    public static final double MISPLACED_MIN_ABSOLUTE_MARGIN = 0.05;
    public static final double MISPLACED_MIN_RELATIVE_RATIO = 1.25;

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

    /** Directed dependency between two files/entities for SemRef Eq. 1-3 computation. */
    public record DirectedDependency(String source, String target) {
        public DirectedDependency {
            source = Objects.requireNonNull(source, "source");
            target = Objects.requireNonNull(target, "target");
        }
    }

    /** Zero-LLM deterministic misplaced-file candidate flagged via SemRef Eq. 1 {@code Dep(f, m)}. */
    public record MisplacedFileCandidate(
            String entityId,
            String label,
            String flag,
            String currentModule,
            String suggestedModule,
            BigDecimal ownModuleDep,
            BigDecimal suggestedModuleDep,
            BigDecimal depMargin,
            String rationale) {
        public MisplacedFileCandidate {
            entityId = Objects.requireNonNull(entityId, "entityId");
            label = label == null ? entityId : label;
            flag = MISPLACED_FILE_CANDIDATE_FLAG;
            currentModule = Objects.requireNonNull(currentModule, "currentModule");
            suggestedModule = Objects.requireNonNull(suggestedModule, "suggestedModule");
            ownModuleDep = Objects.requireNonNull(ownModuleDep, "ownModuleDep");
            suggestedModuleDep = Objects.requireNonNull(suggestedModuleDep, "suggestedModuleDep");
            depMargin = Objects.requireNonNull(depMargin, "depMargin");
            rationale = rationale == null ? "" : rationale;
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("entityId", entityId);
            m.put("label", label);
            m.put("flag", flag);
            m.put("currentModule", currentModule);
            m.put("suggestedModule", suggestedModule);
            m.put("ownModuleDep", ownModuleDep);
            m.put("suggestedModuleDep", suggestedModuleDep);
            m.put("depMargin", depMargin);
            m.put("rationale", rationale);
            return Collections.unmodifiableMap(m);
        }
    }

    /** SemRef Eq. 3 directed inter-module coupling strength {@code Dep_m(m_src, m_dst)}. */
    public record InterModuleCoupling(
            String sourceModule,
            String targetModule,
            BigDecimal depStrength) {
        public InterModuleCoupling {
            sourceModule = Objects.requireNonNull(sourceModule, "sourceModule");
            targetModule = Objects.requireNonNull(targetModule, "targetModule");
            depStrength = Objects.requireNonNull(depStrength, "depStrength");
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("sourceModule", sourceModule);
            m.put("targetModule", targetModule);
            m.put("depStrength", depStrength);
            return Collections.unmodifiableMap(m);
        }
    }

    /** Deterministic zero-LLM SemRef architectural quality bundle (Eqs. 1-3 + misplaced-file flags). */
    public record SemRefMetrics(
            Map<String, BigDecimal> moduleCohesion,
            BigDecimal meanCohesion,
            List<InterModuleCoupling> interModuleCoupling,
            List<MisplacedFileCandidate> misplacedCandidates) {
        public static final SemRefMetrics EMPTY = new SemRefMetrics(
                Map.of(),
                BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                List.of(),
                List.of());

        public SemRefMetrics {
            moduleCohesion = Collections.unmodifiableMap(new TreeMap<>(moduleCohesion == null ? Map.of() : moduleCohesion));
            meanCohesion = meanCohesion == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) : meanCohesion;
            interModuleCoupling = List.copyOf(interModuleCoupling == null ? List.of() : interModuleCoupling);
            misplacedCandidates = List.copyOf(misplacedCandidates == null ? List.of() : misplacedCandidates);
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("moduleCohesion", moduleCohesion);
            m.put("meanCohesion", meanCohesion);
            m.put("interModuleCoupling", interModuleCoupling.stream().map(InterModuleCoupling::toView).toList());
            m.put("misplacedCandidates", misplacedCandidates.stream().map(MisplacedFileCandidate::toView).toList());
            m.put("misplacedCandidateCount", misplacedCandidates.size());
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
            List<DsmCell> cells,
            SemRefMetrics semRefMetrics
    ) {
        public DsmMatrix(
                ContentDigest graphDigest,
                String context,
                Granularity granularity,
                Optional<String> communityId,
                BigDecimal levelizationScore,
                int totalItems,
                boolean truncated,
                List<DsmItem> order,
                List<DsmSccBlock> sccBlocks,
                List<DsmCell> cells) {
            this(graphDigest, context, granularity, communityId, levelizationScore, totalItems, truncated,
                    order, sccBlocks, cells, SemRefMetrics.EMPTY);
        }

        public DsmMatrix {
            Objects.requireNonNull(graphDigest, "graphDigest");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(granularity, "granularity");
            Objects.requireNonNull(communityId, "communityId");
            Objects.requireNonNull(levelizationScore, "levelizationScore");
            order = List.copyOf(Objects.requireNonNull(order, "order"));
            sccBlocks = List.copyOf(Objects.requireNonNull(sccBlocks, "sccBlocks"));
            cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
            semRefMetrics = semRefMetrics == null ? SemRefMetrics.EMPTY : semRefMetrics;
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
            view.put("semRefMetrics", semRefMetrics.toView());
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

        // Compute deterministic multi-gamma Leiden community assignments so items carry communityId
        // and optional communityId filtering matches MACRO/MESO/MICRO community IDs 100%.
        Optional<String> normalizedCommunityFilter = request.communityId()
                .map(DsmMatrixBuilder::normalizeCommunityId);
        Map<String, String> communityByEntity = computeCommunityAssignments(
                request.context(), projectEntities, projectEdges, normalizedCommunityFilter, tick);

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

        // Compute deterministic zero-LLM SemRef Eqs. 1-3 metrics & MISPLACED_FILE_CANDIDATE flags
        SemRefMetrics semRefMetrics = computeGraphSemRefMetrics(
                scopedEntities, scopedEntityIds, projectEdges, request.granularity(), communityByEntity);

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
                cells,
                semRefMetrics);
    }

    /**
     * Computes SemRef Eq. 1 utility-downweighted normalized dependency strength:
     * {@code Dep(f_src, m) = (1 / |m|) * sum_{f in m} (delta(f_src, f) / max(1, inDeg(f)))}.
     */
    public static double computeDependencyStrength(
            String sourceFile,
            Collection<String> targetModuleFiles,
            Map<String, Set<String>> outgoingTargets,
            Map<String, Integer> inDegrees) {
        if (sourceFile == null || targetModuleFiles == null || targetModuleFiles.isEmpty()) {
            return 0.0;
        }
        Set<String> targets = outgoingTargets.getOrDefault(sourceFile, Set.of());
        if (targets.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (String f : targetModuleFiles) {
            if (!sourceFile.equals(f) && targets.contains(f)) {
                int inDeg = Math.max(1, inDegrees.getOrDefault(f, 1));
                sum += 1.0 / inDeg;
            }
        }
        return sum / targetModuleFiles.size();
    }

    /**
     * Computes SemRef Eqs. 4-5 Relative Distance Progress {@code RDP(A, A') = (M(A') - M(A)) / (1 - M(A))}.
     */
    public static double computeRdpDouble(double baselineMetric, double refinedMetric) {
        double base = Double.isFinite(baselineMetric) ? baselineMetric : 0.0;
        double ref = Double.isFinite(refinedMetric) ? refinedMetric : 0.0;
        double remainingGap = 1.0 - base;
        if (remainingGap <= 1e-12) {
            return ref >= base - 1e-12 ? 0.0 : (ref - base);
        }
        double rdp = (ref - base) / remainingGap;
        return Double.isFinite(rdp) ? rdp : 0.0;
    }

    /** Computes SemRef Eqs. 4-5 {@code RDP(A, A')} rounded deterministically to 4 decimal places. */
    public static BigDecimal computeRdp(double baselineMetric, double refinedMetric) {
        return BigDecimal.valueOf(computeRdpDouble(baselineMetric, refinedMetric))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Pure deterministic zero-LLM SemRef Eqs. 1-3 computation and {@code MISPLACED_FILE_CANDIDATE} flagging.
     */
    public static SemRefMetrics computeSemRefMetrics(
            Map<String, String> moduleByFile,
            Collection<DirectedDependency> dependencies) {
        return computeSemRefMetrics(moduleByFile, Map.of(), dependencies);
    }

    /**
     * Pure deterministic zero-LLM SemRef Eqs. 1-3 computation with optional human-readable labels per file/entity.
     */
    public static SemRefMetrics computeSemRefMetrics(
            Map<String, String> moduleByFile,
            Map<String, String> labelByFile,
            Collection<DirectedDependency> dependencies) {
        if (moduleByFile == null || moduleByFile.isEmpty()) {
            return SemRefMetrics.EMPTY;
        }
        var sortedFileToModule = new TreeMap<String, String>();
        var filesByModule = new TreeMap<String, List<String>>();
        for (var entry : moduleByFile.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) continue;
            sortedFileToModule.put(entry.getKey(), entry.getValue());
            filesByModule.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        if (sortedFileToModule.isEmpty()) {
            return SemRefMetrics.EMPTY;
        }
        for (var members : filesByModule.values()) {
            Collections.sort(members);
        }

        // Build canonical directed adjacency and in-degrees inDeg(f) = |{x != f : x -> f}|
        var outgoing = new HashMap<String, Set<String>>(sortedFileToModule.size() * 2);
        var incoming = new HashMap<String, Set<String>>(sortedFileToModule.size() * 2);
        if (dependencies != null) {
            for (var dep : dependencies) {
                if (dep == null) continue;
                String s = dep.source();
                String t = dep.target();
                if (s.equals(t)) continue;
                if (!sortedFileToModule.containsKey(s) || !sortedFileToModule.containsKey(t)) continue;
                outgoing.computeIfAbsent(s, k -> new TreeSet<>()).add(t);
                incoming.computeIfAbsent(t, k -> new TreeSet<>()).add(s);
            }
        }
        var inDegrees = new HashMap<String, Integer>(sortedFileToModule.size() * 2);
        for (String f : sortedFileToModule.keySet()) {
            var inc = incoming.get(f);
            inDegrees.put(f, inc == null ? 0 : inc.size());
        }

        List<String> moduleIds = List.copyOf(filesByModule.keySet());
        // Precompute Dep(f, m) for every file f and module m where f has at least one edge into m
        var depByFileModule = new HashMap<String, Map<String, Double>>(sortedFileToModule.size() * 2);
        for (var entry : sortedFileToModule.entrySet()) {
            String f = entry.getKey();
            var perMod = new TreeMap<String, Double>();
            for (String m : moduleIds) {
                double d = computeDependencyStrength(f, filesByModule.get(m), outgoing, inDegrees);
                if (d > 0.0) {
                    perMod.put(m, d);
                }
            }
            depByFileModule.put(f, perMod);
        }

        // SemRef Eq. 2: Cohesion(m_src) = sum_{f in m_src} Dep(f, m_src) / sum_{m} sum_{f in m_src} Dep(f, m)
        var moduleCohesion = new TreeMap<String, BigDecimal>();
        double cohesionSum = 0.0;
        for (String mSrc : moduleIds) {
            double internalDep = 0.0;
            double totalDep = 0.0;
            for (String f : filesByModule.get(mSrc)) {
                var perMod = depByFileModule.getOrDefault(f, Map.of());
                internalDep += perMod.getOrDefault(mSrc, 0.0);
                for (double v : perMod.values()) {
                    totalDep += v;
                }
            }
            double coh = totalDep > 0.0 ? Math.min(1.0, internalDep / totalDep) : 0.0;
            moduleCohesion.put(mSrc, BigDecimal.valueOf(coh).setScale(4, RoundingMode.HALF_UP));
            cohesionSum += coh;
        }
        double meanCoh = moduleIds.isEmpty() ? 0.0 : (cohesionSum / moduleIds.size());

        // SemRef Eq. 3: Inter-module coupling Dep_m(m_src, m_dst) = sum_{f in m_src} Dep(f, m_dst)
        var interCoupling = new ArrayList<InterModuleCoupling>();
        for (String mSrc : moduleIds) {
            for (String mDst : moduleIds) {
                if (mSrc.equals(mDst)) continue;
                double strength = 0.0;
                for (String f : filesByModule.get(mSrc)) {
                    strength += depByFileModule.getOrDefault(f, Map.of()).getOrDefault(mDst, 0.0);
                }
                if (strength > 0.0) {
                    interCoupling.add(new InterModuleCoupling(
                            mSrc, mDst, BigDecimal.valueOf(strength).setScale(4, RoundingMode.HALF_UP)));
                }
            }
        }
        interCoupling.sort(Comparator.comparing(InterModuleCoupling::depStrength).reversed()
                .thenComparing(InterModuleCoupling::sourceModule)
                .thenComparing(InterModuleCoupling::targetModule));

        // Zero-LLM Misplaced-File Candidate Flagging: Dep(f, m_other) > Dep(f, m_own) with clear margin
        var misplaced = new ArrayList<MisplacedFileCandidate>();
        Map<String, String> labels = labelByFile == null ? Map.of() : labelByFile;
        for (var entry : sortedFileToModule.entrySet()) {
            String f = entry.getKey();
            String ownMod = entry.getValue();
            var perMod = depByFileModule.getOrDefault(f, Map.of());
            double ownDep = perMod.getOrDefault(ownMod, 0.0);

            String bestOtherMod = null;
            double bestOtherDep = 0.0;
            for (var depEntry : perMod.entrySet()) {
                String candMod = depEntry.getKey();
                if (candMod.equals(ownMod)) continue;
                double val = depEntry.getValue();
                if (val > bestOtherDep + 1e-12
                        || (StrictMath.abs(val - bestOtherDep) <= 1e-12
                                && (bestOtherMod == null || candMod.compareTo(bestOtherMod) < 0))) {
                    bestOtherDep = val;
                    bestOtherMod = candMod;
                }
            }

            if (bestOtherMod != null) {
                double margin = bestOtherDep - ownDep;
                boolean clearMargin = margin >= MISPLACED_MIN_ABSOLUTE_MARGIN
                        && (ownDep <= 1e-12 || bestOtherDep >= ownDep * MISPLACED_MIN_RELATIVE_RATIO);
                if (clearMargin) {
                    String lbl = labels.getOrDefault(f, f);
                    String rationale = String.format(Locale.ROOT,
                            "SemRef Eq.1 utility-downweighted Dep(%s, %s)=%.4f exceeds own module %s Dep=%.4f (margin=+%.4f).",
                            lbl, bestOtherMod, bestOtherDep, ownMod, ownDep, margin);
                    misplaced.add(new MisplacedFileCandidate(
                            f,
                            lbl,
                            MISPLACED_FILE_CANDIDATE_FLAG,
                            ownMod,
                            bestOtherMod,
                            BigDecimal.valueOf(ownDep).setScale(4, RoundingMode.HALF_UP),
                            BigDecimal.valueOf(bestOtherDep).setScale(4, RoundingMode.HALF_UP),
                            BigDecimal.valueOf(margin).setScale(4, RoundingMode.HALF_UP),
                            rationale));
                }
            }
        }
        misplaced.sort(Comparator.comparing(MisplacedFileCandidate::depMargin).reversed()
                .thenComparing(MisplacedFileCandidate::entityId));

        return new SemRefMetrics(
                moduleCohesion,
                BigDecimal.valueOf(meanCoh).setScale(4, RoundingMode.HALF_UP),
                interCoupling,
                misplaced);
    }

    private static SemRefMetrics computeGraphSemRefMetrics(
            List<ContextGraphExtractor.ExtractedEntity> scopedEntities,
            Set<String> scopedEntityIds,
            List<ContextGraphExtractor.ExtractedEdge> projectEdges,
            Granularity granularity,
            Map<String, String> communityByEntity) {
        var moduleByEntity = new TreeMap<String, String>();
        var labelByEntity = new HashMap<String, String>(scopedEntities.size() * 2);
        var distinctPackages = new HashSet<String>();
        for (var e : scopedEntities) {
            if (!e.packageName().isEmpty()) distinctPackages.add(e.packageName());
        }
        for (var e : scopedEntities) {
            String mod;
            if (granularity == Granularity.MODULE) {
                mod = !e.moduleName().isEmpty() ? e.moduleName() : (!e.moduleId().isEmpty() ? e.moduleId() : "(default)");
            } else if (distinctPackages.size() >= 2) {
                mod = e.packageName().isEmpty() ? "(default)" : e.packageName();
            } else {
                String cid = communityByEntity.get(e.id());
                mod = cid != null ? cid : (!e.packageName().isEmpty() ? e.packageName() : e.moduleName());
            }
            moduleByEntity.put(e.id(), mod);
            labelByEntity.put(e.id(), e.shortLabel());
        }
        var deps = new ArrayList<DirectedDependency>();
        for (var edge : projectEdges) {
            if (scopedEntityIds.contains(edge.source()) && scopedEntityIds.contains(edge.target())
                    && !edge.source().equals(edge.target())) {
                deps.add(new DirectedDependency(edge.source(), edge.target()));
            }
        }
        return computeSemRefMetrics(moduleByEntity, labelByEntity, deps);
    }

    private static Map<String, String> computeCommunityAssignments(
            String context,
            List<ContextGraphExtractor.ExtractedEntity> projectEntities,
            List<ContextGraphExtractor.ExtractedEdge> projectEdges,
            Optional<String> requestedCommunityFilter,
            Runnable checkpoint) {
        var sweep = GraphProjectionService.runMultiGammaLeidenForContext(
                context, projectEntities, projectEdges, checkpoint);
        if (requestedCommunityFilter != null && requestedCommunityFilter.isPresent()) {
            String target = requestedCommunityFilter.get();
            if (sweep.meso().communityByNode().containsValue(target)) {
                return sweep.meso().communityByNode();
            }
            if (sweep.macro().communityByNode().containsValue(target)) {
                return sweep.macro().communityByNode();
            }
            if (sweep.micro().communityByNode().containsValue(target)) {
                return sweep.micro().communityByNode();
            }
        }
        return sweep.meso().communityByNode();
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
