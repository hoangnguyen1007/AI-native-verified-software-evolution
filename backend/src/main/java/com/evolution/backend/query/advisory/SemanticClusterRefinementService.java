package com.evolution.backend.query.advisory;

import com.evolution.analysis.assessment.v2.LeidenCommunityDetector;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.analysis.query.projection.DsmMatrixBuilder;
import com.evolution.analysis.query.projection.GraphProjectionService;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.llm.LlmTransport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Strictly quarantined SemRef (Stages 2-4) hierarchical semantic cluster refinement service.
 *
 * <p>Implements:
 * <ul>
 *   <li><b>Stage 2 (Unanchoring + Bounded 2-Step Consistency Check):</b> Simultaneously unanchors
 *       all zero-LLM {@code MISPLACED_FILE_CANDIDATE} files before reassignment so the advisor
 *       evaluates top-k coupled candidate modules without anchoring bias, verifying each proposed
 *       move via an independent 2-step confirmation bounded by {@code maxRetries = 3}.</li>
 *   <li><b>Stage 3 (Exclusive Leaf/Branch Hierarchy):</b> Constructs an acyclic rooted tree where
 *       every {@code LEAF} node contains exclusively files ({@code childClusterIds.isEmpty()}) and
 *       every {@code BRANCH} node contains exclusively child clusters ({@code fileIds.isEmpty()}).</li>
 *   <li><b>Stage 4 (Top-Down Branch Refinement + Bottom-Up Lazy Summaries):</b> Lazily summarizes
 *       only visited clusters strictly bottom-up (leaf snippets -&gt; branch child summaries) and
 *       iteratively evaluates top-3 inter-module coupled branch moves while preserving the
 *       exclusive leaf/branch invariant.</li>
 * </ul>
 *
 * <p><b>Quarantine Invariants (I2/I4):</b> Every output carries {@link TrustTier#ADVISORY_UNVERIFIED},
 * every code snippet passes {@link LlmTransport#redactDocumentForPath(String, String)}
 * ({@value LlmTransport#REDACTION_VERSION}) and prompt-injection sentinel neutralization before
 * leaving the boundary, and canonical {@link KnowledgeGraph#identity()}, canonical Leiden
 * partitions, Policy findings, and Architecture Score v2 remain 100% untouched
 * ({@code canonicalLeakage = 0}).
 */
public final class SemanticClusterRefinementService {
    public static final String SCHEMA = "advisory.semref-hierarchy:v1";
    public static final String DERIVATION = "INFERRED";
    public static final TrustTier TRUST_TIER = TrustTier.ADVISORY_UNVERIFIED;
    public static final String REDACTION_VERSION = LlmTransport.REDACTION_VERSION;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final int MAX_ALLOWED_RETRIES = 3;
    public static final int DEFAULT_TOP_K_CANDIDATES = 3;
    public static final int MAX_SNIPPET_LINES = 80;
    public static final int MAX_SNIPPET_CHARS = 2_400;
    private static final int MAX_LEAF_GROUP_SIZE = 3;
    private static final GraphJson.Limits SMALL_JSON = new GraphJson.Limits(131_072, 32, 8_192);

    private SemanticClusterRefinementService() {}

    public enum NodeKind {
        LEAF,
        BRANCH
    }

    public record Config(
            int maxRetries,
            int topKCandidates,
            int maxTopDownIterations,
            int maxSnippetLines,
            int maxSnippetChars) {
        public static Config defaults() {
            return new Config(DEFAULT_MAX_RETRIES, DEFAULT_TOP_K_CANDIDATES, 2, MAX_SNIPPET_LINES, MAX_SNIPPET_CHARS);
        }

        public Config {
            maxRetries = Math.max(1, Math.min(MAX_ALLOWED_RETRIES, maxRetries));
            topKCandidates = Math.max(1, Math.min(10, topKCandidates));
            maxTopDownIterations = Math.max(0, Math.min(5, maxTopDownIterations));
            maxSnippetLines = Math.max(5, Math.min(250, maxSnippetLines));
            maxSnippetChars = Math.max(128, Math.min(8_192, maxSnippetChars));
        }
    }

    /** Immutable input file/entity descriptor (never mutates canonical graph). */
    public record FileNode(
            String entityId,
            String label,
            String initialModule,
            String sourcePath) {
        public FileNode {
            entityId = Objects.requireNonNull(entityId, "entityId");
            label = (label == null || label.isBlank()) ? entityId : label;
            initialModule = (initialModule == null || initialModule.isBlank()) ? "(default)" : initialModule.strip();
            sourcePath = sourcePath == null ? "" : CapturedFileIndex.normalizePath(sourcePath);
        }
    }

    public record CandidateModuleOption(
            String moduleId,
            BigDecimal dependencyStrength,
            String lazySummary) {}

    /** Stage 2 Step-1 prompt delivered to the pluggable advisor (all snippets pre-redacted via llm-redact:v2). */
    public record FileReassignmentPrompt(
            String fileId,
            String label,
            String sourcePath,
            String redactedSnippet,
            boolean unanchored,
            List<CandidateModuleOption> candidateModules,
            List<String> priorRejectedModules,
            int attempt) {
        public FileReassignmentPrompt {
            candidateModules = List.copyOf(candidateModules == null ? List.of() : candidateModules);
            priorRejectedModules = List.copyOf(priorRejectedModules == null ? List.of() : priorRejectedModules);
        }
    }

    /** Stage 2 Step-2 independent consistency-verification prompt. */
    public record ConsistencyCheckPrompt(
            String fileId,
            String label,
            String sourcePath,
            String redactedSnippet,
            String proposedModuleId,
            BigDecimal proposedModuleDep,
            String proposedModuleSummary,
            List<CandidateModuleOption> alternativeModules,
            int attempt) {
        public ConsistencyCheckPrompt {
            proposedModuleDep = proposedModuleDep == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) : proposedModuleDep;
            alternativeModules = List.copyOf(alternativeModules == null ? List.of() : alternativeModules);
        }
    }

    /** Bottom-up lazy summarization prompt: leaf uses redacted file snippets; branch uses child cluster summaries only. */
    public record ClusterSummaryPrompt(
            String clusterId,
            NodeKind kind,
            List<String> childLabels,
            List<String> redactedInputs) {
        public ClusterSummaryPrompt {
            childLabels = List.copyOf(childLabels == null ? List.of() : childLabels);
            redactedInputs = List.copyOf(redactedInputs == null ? List.of() : redactedInputs);
        }
    }

    /** Stage 4 top-down branch refinement prompt with top-3 coupled destination branches. */
    public record BranchReassignmentPrompt(
            String leafClusterId,
            String leafSummary,
            String currentBranchId,
            String currentBranchSummary,
            List<CandidateModuleOption> candidateBranches,
            int iteration) {
        public BranchReassignmentPrompt {
            candidateBranches = List.copyOf(candidateBranches == null ? List.of() : candidateBranches);
        }
    }

    /** Pluggable LLM or deterministic advisor interface for SemRef Stages 2-4. */
    public interface RefinementAdvisor {
        Optional<String> proposeFileReassignment(FileReassignmentPrompt prompt);

        boolean confirmFileReassignment(ConsistencyCheckPrompt prompt);

        String summarizeCluster(ClusterSummaryPrompt prompt);

        Optional<String> proposeBranchReassignment(BranchReassignmentPrompt prompt);
    }

    public record ReassignmentDecision(
            String fileId,
            String label,
            String originalModule,
            String assignedModule,
            boolean unanchoredBeforeReassignment,
            boolean consistencyConfirmed,
            int attemptsUsed,
            String status,
            TrustTier trustTier) {
        public ReassignmentDecision {
            trustTier = TRUST_TIER;
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("fileId", fileId);
            m.put("label", label);
            m.put("originalModule", originalModule);
            m.put("assignedModule", assignedModule);
            m.put("unanchoredBeforeReassignment", unanchoredBeforeReassignment);
            m.put("consistencyConfirmed", consistencyConfirmed);
            m.put("attemptsUsed", attemptsUsed);
            m.put("status", status);
            m.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(m);
        }
    }

    public record BranchMoveDecision(
            String leafClusterId,
            String fromBranchId,
            String toBranchId,
            int iteration,
            TrustTier trustTier) {
        public BranchMoveDecision {
            trustTier = TRUST_TIER;
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("leafClusterId", leafClusterId);
            m.put("fromBranchId", fromBranchId);
            m.put("toBranchId", toBranchId);
            m.put("iteration", iteration);
            m.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(m);
        }
    }

    /**
     * Strict exclusive leaf/branch node:
     * {@code LEAF} holds ONLY {@code fileIds} (empty {@code childClusterIds});
     * {@code BRANCH} holds ONLY {@code childClusterIds} (empty {@code fileIds}).
     */
    public record HierarchyNode(
            String id,
            NodeKind kind,
            String label,
            Optional<String> parentId,
            List<String> fileIds,
            List<String> childClusterIds,
            Optional<String> lazySummary,
            TrustTier trustTier) {
        public HierarchyNode {
            id = Objects.requireNonNull(id, "id");
            kind = Objects.requireNonNull(kind, "kind");
            label = label == null ? id : label;
            parentId = parentId == null ? Optional.empty() : parentId;
            fileIds = List.copyOf(fileIds == null ? List.of() : fileIds);
            childClusterIds = List.copyOf(childClusterIds == null ? List.of() : childClusterIds);
            lazySummary = lazySummary == null ? Optional.empty() : lazySummary;
            trustTier = TRUST_TIER;
            if (kind == NodeKind.LEAF && !childClusterIds.isEmpty()) {
                throw new IllegalArgumentException("Exclusive leaf/branch invariant violated: LEAF node " + id + " cannot have child clusters");
            }
            if (kind == NodeKind.BRANCH && !fileIds.isEmpty()) {
                throw new IllegalArgumentException("Exclusive leaf/branch invariant violated: BRANCH node " + id + " cannot directly contain files");
            }
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("id", id);
            m.put("kind", kind.name());
            m.put("label", label);
            parentId.ifPresent(p -> m.put("parentId", p));
            m.put("fileIds", fileIds);
            m.put("childClusterIds", childClusterIds);
            lazySummary.ifPresent(s -> m.put("lazySummary", s));
            m.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(m);
        }
    }

    public record HierarchyTree(
            String rootId,
            Map<String, HierarchyNode> nodesById) {
        public HierarchyTree {
            rootId = Objects.requireNonNull(rootId, "rootId");
            nodesById = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(nodesById, "nodesById")));
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("rootId", rootId);
            var nodesList = new ArrayList<Map<String, Object>>(nodesById.size());
            for (var node : nodesById.values()) {
                nodesList.add(node.toView());
            }
            m.put("nodes", nodesList);
            return Collections.unmodifiableMap(m);
        }
    }

    public record RefinementResult(
            String schema,
            String derivation,
            TrustTier trustTier,
            String redactionVersion,
            boolean quarantined,
            String graphIdentity,
            List<String> unanchoredFileIds,
            List<ReassignmentDecision> fileReassignments,
            List<BranchMoveDecision> branchMoves,
            HierarchyTree hierarchy,
            Map<String, String> refinedModuleByFile,
            DsmMatrixBuilder.SemRefMetrics baselineMetrics,
            DsmMatrixBuilder.SemRefMetrics refinedMetrics,
            BigDecimal cohesionRdp,
            int redactedSecretsCount,
            int lazySummaryEvaluations,
            int lazySummaryCacheHits) {
        public RefinementResult {
            schema = SCHEMA;
            derivation = DERIVATION;
            trustTier = TRUST_TIER;
            redactionVersion = REDACTION_VERSION;
            quarantined = true;
            graphIdentity = graphIdentity == null ? "" : graphIdentity;
            unanchoredFileIds = List.copyOf(unanchoredFileIds == null ? List.of() : unanchoredFileIds);
            fileReassignments = List.copyOf(fileReassignments == null ? List.of() : fileReassignments);
            branchMoves = List.copyOf(branchMoves == null ? List.of() : branchMoves);
            Objects.requireNonNull(hierarchy, "hierarchy");
            refinedModuleByFile = Collections.unmodifiableMap(new TreeMap<>(refinedModuleByFile == null ? Map.of() : refinedModuleByFile));
            baselineMetrics = baselineMetrics == null ? DsmMatrixBuilder.SemRefMetrics.EMPTY : baselineMetrics;
            refinedMetrics = refinedMetrics == null ? DsmMatrixBuilder.SemRefMetrics.EMPTY : refinedMetrics;
            cohesionRdp = cohesionRdp == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) : cohesionRdp;
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("schema", schema);
            m.put("derivation", derivation);
            m.put("trustTier", trustTier.name());
            m.put("redactionVersion", redactionVersion);
            m.put("quarantined", quarantined);
            m.put("notice", "SemRef hierarchical cluster refinement is strictly quarantined (ADVISORY_UNVERIFIED) and never modifies canonical KnowledgeGraph digests, Leiden partitions, Policy findings, or Architecture Score v2.");
            m.put("graphIdentity", graphIdentity);
            m.put("unanchoredFileIds", unanchoredFileIds);
            m.put("fileReassignments", fileReassignments.stream().map(ReassignmentDecision::toView).toList());
            m.put("branchMoves", branchMoves.stream().map(BranchMoveDecision::toView).toList());
            m.put("hierarchy", hierarchy.toView());
            m.put("refinedModuleByFile", refinedModuleByFile);
            m.put("baselineMetrics", baselineMetrics.toView());
            m.put("refinedMetrics", refinedMetrics.toView());
            m.put("cohesionRdp", cohesionRdp);
            m.put("redactedSecretsCount", redactedSecretsCount);
            m.put("lazySummaryEvaluations", lazySummaryEvaluations);
            m.put("lazySummaryCacheHits", lazySummaryCacheHits);
            return Collections.unmodifiableMap(m);
        }
    }

    /** Returns a zero-LLM deterministic fallback advisor suitable for offline runs and verification. */
    public static RefinementAdvisor deterministicAdvisor() {
        return DeterministicFallbackAdvisor.INSTANCE;
    }

    /**
     * Strictly read-only SemRef refinement over an immutable {@link KnowledgeGraph} context.
     * Starts from the canonical Leiden multi-gamma MESO partition without mutating the graph,
     * pre-redacts every captured file via {@link LlmTransport#redactDocumentForPath(String, String)},
     * and returns a quarantined {@link TrustTier#ADVISORY_UNVERIFIED} hierarchy.
     */
    public static RefinementResult refineGraph(
            KnowledgeGraph graph,
            String context,
            RefinementAdvisor advisor,
            Config config) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(context, "context");
        RefinementAdvisor effectiveAdvisor = advisor == null ? deterministicAdvisor() : advisor;
        Config cfg = config == null ? Config.defaults() : config;

        var queries = new ArchitectureQueryService(graph);
        var req = new ArchitectureQuery.Request(
                graph.identity(),
                context,
                ArchitectureQuery.Scope.TYPE,
                ArchitectureQuery.Projection.BOUNDED_UNION,
                ArchitectureQuery.Direction.OUTGOING,
                Set.of("graph.depends-on"),
                ArchitectureQuery.Population.all(),
                false,
                Optional.empty(),
                ArchitectureQuery.Limits.defaults());
        var projRes = queries.metricProjection(req);
        if (projRes.items().isEmpty()) {
            return refine(graph.identity().value(), List.of(), List.of(), Map.of(), effectiveAdvisor, cfg);
        }
        var entityViews = projRes.items().getFirst().entities();
        var edgeViews = projRes.items().getFirst().edges();

        // Filter to PROJECT entities cleanly (matching GraphProjectionService)
        var projectViews = entityViews.stream()
                .filter(e -> e.origin().isEmpty() || "PROJECT".equals(e.origin().orElse("")))
                .toList();
        var projectIds = new HashSet<String>(projectViews.size() * 2);
        for (var ev : projectViews) projectIds.add(ev.identity());

        // Map entity -> source path read-only from canonical graph evidence + CapturedFileIndex
        var docIdToPath = indexDocumentPaths(graph);
        var entityToPath = indexEntitySourcePaths(graph, context, docIdToPath);
        var fileIndex = CapturedFileIndex.forGraph(graph);

        String commonPrefix = GraphProjectionService.computeCommonPackagePrefix(projectViews, ArchitectureQuery.Scope.TYPE);
        var leidenNodes = new ArrayList<LeidenCommunityDetector.NodeInput>(projectViews.size());
        for (var ev : projectViews) {
            leidenNodes.add(new LeidenCommunityDetector.NodeInput(
                    ev.identity(),
                    GraphProjectionService.shortLabel(ev, ArchitectureQuery.Scope.TYPE),
                    GraphProjectionService.groupName(ev, ArchitectureQuery.Scope.TYPE, commonPrefix),
                    ev.kind()));
        }
        var leidenEdges = new ArrayList<LeidenCommunityDetector.EdgeInput>();
        var deps = new ArrayList<DsmMatrixBuilder.DirectedDependency>();
        for (var edge : edgeViews) {
            if (!projectIds.contains(edge.source()) || !projectIds.contains(edge.target())) continue;
            if (edge.source().equals(edge.target())) continue;
            String kind = edge.kinds().isEmpty() ? "graph.depends-on" : edge.kinds().getFirst();
            leidenEdges.add(new LeidenCommunityDetector.EdgeInput(
                    edge.source(), edge.target(), Math.max(1.0, edge.supportCount()), kind));
            deps.add(new DsmMatrixBuilder.DirectedDependency(edge.source(), edge.target()));
        }

        // Canonical Leiden partition stays strictly read-only; if declared packages span >=2 modules,
        // initialize from declared packages so misplaced-file drift can be refined cleanly, else Leiden MESO.
        var distinctPackages = new HashSet<String>();
        for (var n : leidenNodes) {
            if (!n.declaredPackage().isEmpty()) distinctPackages.add(n.declaredPackage());
        }
        Map<String, String> initialModules;
        if (distinctPackages.size() >= 2) {
            initialModules = new HashMap<>();
            for (var n : leidenNodes) initialModules.put(n.identity(), n.declaredPackage());
        } else {
            var sweep = LeidenCommunityDetector.sweepMultiGamma(leidenNodes, leidenEdges, null);
            initialModules = sweep.meso().communityByNode();
        }

        var files = new ArrayList<FileNode>(projectViews.size());
        var rawContentByPath = new LinkedHashMap<String, String>();
        for (var ev : projectViews) {
            String path = entityToPath.getOrDefault(ev.identity(), "");
            if (!path.isEmpty() && !rawContentByPath.containsKey(path)) {
                fileIndex.findExact(path)
                        .flatMap(entry -> fileIndex.readText(graph, entry))
                        .ifPresent(text -> rawContentByPath.put(path, text));
            }
            String label = GraphProjectionService.shortLabel(ev, ArchitectureQuery.Scope.TYPE);
            String mod = initialModules.getOrDefault(ev.identity(), "(default)");
            files.add(new FileNode(ev.identity(), label, mod, path));
        }

        return refine(graph.identity().value(), files, deps, rawContentByPath, effectiveAdvisor, cfg);
    }

    /**
     * Executes quarantined SemRef Stages 2-4 over an explicit file/module partition and dependency list.
     * Every snippet in {@code rawContentByPath} is pre-redacted via
     * {@link LlmTransport#redactDocumentForPath(String, String)} before reaching {@code advisor}.
     */
    public static RefinementResult refine(
            String graphIdentity,
            List<FileNode> rawFiles,
            List<DsmMatrixBuilder.DirectedDependency> rawDependencies,
            Map<String, String> rawContentByPath,
            RefinementAdvisor advisor,
            Config config) {
        RefinementAdvisor effectiveAdvisor = advisor == null ? deterministicAdvisor() : advisor;
        Config cfg = config == null ? Config.defaults() : config;

        // 1. Canonical sort of files and pre-redaction of every source document via llm-redact:v2
        var fileById = new TreeMap<String, FileNode>();
        if (rawFiles != null) {
            for (var fn : rawFiles) {
                if (fn != null) fileById.putIfAbsent(fn.entityId(), fn);
            }
        }
        if (fileById.isEmpty()) {
            var emptyRoot = new HierarchyNode("branch:root", NodeKind.BRANCH, "Root",
                    Optional.empty(), List.of(), List.of(), Optional.empty(), TRUST_TIER);
            return new RefinementResult(
                    SCHEMA, DERIVATION, TRUST_TIER, REDACTION_VERSION, true,
                    graphIdentity, List.of(), List.of(), List.of(),
                    new HierarchyTree("branch:root", Map.of("branch:root", emptyRoot)),
                    Map.of(), DsmMatrixBuilder.SemRefMetrics.EMPTY, DsmMatrixBuilder.SemRefMetrics.EMPTY,
                    BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP), 0, 0, 0);
        }

        var redactedSnippetsByPath = new HashMap<String, String>();
        int redactedSecretsCount = 0;
        if (rawContentByPath != null && !rawContentByPath.isEmpty()) {
            var sortedPaths = new TreeMap<String, String>();
            for (var e : rawContentByPath.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    sortedPaths.put(CapturedFileIndex.normalizePath(e.getKey()), e.getValue());
                }
            }
            for (var entry : sortedPaths.entrySet()) {
                String normPath = entry.getKey();
                LlmTransport.RedactedDocument doc = LlmTransport.redactDocumentForPath(normPath, entry.getValue());
                redactedSecretsCount += doc.totalRedactedCount();
                String sliced = buildBoundedSafeSnippet(doc, cfg.maxSnippetLines(), cfg.maxSnippetChars());
                redactedSnippetsByPath.put(normPath, sliced);
            }
        }

        var originalModuleByFile = new TreeMap<String, String>();
        var labelByFile = new HashMap<String, String>(fileById.size() * 2);
        var workingModules = new TreeMap<String, TreeSet<String>>();
        for (var fn : fileById.values()) {
            originalModuleByFile.put(fn.entityId(), fn.initialModule());
            labelByFile.put(fn.entityId(), fn.label());
            workingModules.computeIfAbsent(fn.initialModule(), k -> new TreeSet<>()).add(fn.entityId());
        }

        var cleanDeps = new ArrayList<DsmMatrixBuilder.DirectedDependency>();
        var outgoing = new HashMap<String, Set<String>>(fileById.size() * 2);
        var incoming = new HashMap<String, Set<String>>(fileById.size() * 2);
        if (rawDependencies != null) {
            var seenDep = new TreeSet<String>();
            for (var dep : rawDependencies) {
                if (dep == null || dep.source().equals(dep.target())) continue;
                if (!fileById.containsKey(dep.source()) || !fileById.containsKey(dep.target())) continue;
                if (seenDep.add(dep.source() + "->" + dep.target())) {
                    cleanDeps.add(dep);
                    outgoing.computeIfAbsent(dep.source(), k -> new TreeSet<>()).add(dep.target());
                    incoming.computeIfAbsent(dep.target(), k -> new TreeSet<>()).add(dep.source());
                }
            }
        }
        var inDegrees = new HashMap<String, Integer>(fileById.size() * 2);
        for (String fid : fileById.keySet()) {
            var inc = incoming.get(fid);
            inDegrees.put(fid, inc == null ? 0 : inc.size());
        }

        // Baseline Stage-1 zero-LLM SemRef metrics (Eq. 1-3 + MISPLACED_FILE_CANDIDATE flags)
        DsmMatrixBuilder.SemRefMetrics baselineMetrics = DsmMatrixBuilder.computeSemRefMetrics(
                originalModuleByFile, labelByFile, cleanDeps);

        // ─── Stage 2: Simultaneously UNANCHOR all MISPLACED_FILE_CANDIDATE files before reassignment ───
        var unanchoredFileIds = new ArrayList<String>(baselineMetrics.misplacedCandidates().size());
        var candidateByFile = new LinkedHashMap<String, DsmMatrixBuilder.MisplacedFileCandidate>();
        for (var cand : baselineMetrics.misplacedCandidates()) {
            String fid = cand.entityId();
            if (fileById.containsKey(fid)) {
                unanchoredFileIds.add(fid);
                candidateByFile.put(fid, cand);
                var modSet = workingModules.get(cand.currentModule());
                if (modSet != null) {
                    modSet.remove(fid);
                }
            }
        }
        Collections.sort(unanchoredFileIds);

        var lazySummaryCache = new HashMap<String, String>();
        int[] summaryStats = new int[2]; // [0] = evaluations, [1] = cacheHits

        var fileDecisions = new ArrayList<ReassignmentDecision>(unanchoredFileIds.size());
        var pendingPlacements = new TreeMap<String, String>();

        for (String fid : unanchoredFileIds) {
            FileNode fn = fileById.get(fid);
            String origMod = originalModuleByFile.get(fid);
            String safeSnippet = resolveSafeSnippetForFile(fn, redactedSnippetsByPath);

            // Rank candidate modules by SemRef Eq. 1 Dep(fid, m) on the unanchored module sets
            record ScoredMod(String modId, double dep) {}
            var scored = new ArrayList<ScoredMod>();
            for (var modEntry : workingModules.entrySet()) {
                String modId = modEntry.getKey();
                double dep = DsmMatrixBuilder.computeDependencyStrength(fid, modEntry.getValue(), outgoing, inDegrees);
                scored.add(new ScoredMod(modId, dep));
            }
            scored.sort(Comparator.comparingDouble(ScoredMod::dep).reversed().thenComparing(ScoredMod::modId));

            var topModIds = new LinkedHashSet<String>();
            var stage1Cand = candidateByFile.get(fid);
            if (stage1Cand != null && workingModules.containsKey(stage1Cand.suggestedModule())) {
                topModIds.add(stage1Cand.suggestedModule());
            }
            for (var sm : scored) {
                if (topModIds.size() >= cfg.topKCandidates()) break;
                if (sm.dep() > 0.0 || !workingModules.getOrDefault(sm.modId(), new TreeSet<>()).isEmpty()) {
                    topModIds.add(sm.modId());
                }
            }
            topModIds.add(origMod);

            var depByMod = new HashMap<String, Double>();
            for (var sm : scored) depByMod.put(sm.modId(), sm.dep());

            var candidateOptions = new ArrayList<CandidateModuleOption>(topModIds.size());
            for (String mid : topModIds) {
                String summary = getOrComputeLeafSummary(
                        mid, workingModules.getOrDefault(mid, new TreeSet<>()),
                        fileById, redactedSnippetsByPath, effectiveAdvisor, lazySummaryCache, summaryStats);
                candidateOptions.add(new CandidateModuleOption(
                        mid,
                        BigDecimal.valueOf(depByMod.getOrDefault(mid, 0.0)).setScale(4, RoundingMode.HALF_UP),
                        summary));
            }
            candidateOptions.sort(Comparator.comparing(CandidateModuleOption::dependencyStrength).reversed()
                    .thenComparing(CandidateModuleOption::moduleId));

            // Bounded 2-step self-consistency loop (maxRetries = 3)
            var rejected = new ArrayList<String>();
            String acceptedMod = null;
            int attemptsUsed = 0;
            for (int attempt = 1; attempt <= cfg.maxRetries(); attempt++) {
                attemptsUsed = attempt;
                var step1Prompt = new FileReassignmentPrompt(
                        fid, fn.label(), fn.sourcePath(), safeSnippet, true,
                        candidateOptions, rejected, attempt);
                Optional<String> proposedOpt = effectiveAdvisor.proposeFileReassignment(step1Prompt);
                if (proposedOpt == null || proposedOpt.isEmpty()) {
                    continue;
                }
                String proposed = proposedOpt.get().strip();
                if (!topModIds.contains(proposed)) {
                    rejected.add(proposed);
                    continue;
                }
                String proposedSummary = getOrComputeLeafSummary(
                        proposed, workingModules.getOrDefault(proposed, new TreeSet<>()),
                        fileById, redactedSnippetsByPath, effectiveAdvisor, lazySummaryCache, summaryStats);
                BigDecimal proposedDep = BigDecimal.valueOf(depByMod.getOrDefault(proposed, 0.0))
                        .setScale(4, RoundingMode.HALF_UP);
                var alts = candidateOptions.stream().filter(o -> !o.moduleId().equals(proposed)).toList();
                var step2Prompt = new ConsistencyCheckPrompt(
                        fid, fn.label(), fn.sourcePath(), safeSnippet,
                        proposed, proposedDep, proposedSummary, alts, attempt);
                boolean confirmed = effectiveAdvisor.confirmFileReassignment(step2Prompt);
                if (confirmed) {
                    acceptedMod = proposed;
                    break;
                }
                rejected.add(proposed);
            }

            if (acceptedMod != null) {
                pendingPlacements.put(fid, acceptedMod);
                fileDecisions.add(new ReassignmentDecision(
                        fid, fn.label(), origMod, acceptedMod, true, true, attemptsUsed,
                        acceptedMod.equals(origMod) ? "CONFIRMED_RETAIN_ORIGINAL" : "CONFIRMED_REASSIGNED",
                        TRUST_TIER));
            } else {
                pendingPlacements.put(fid, origMod);
                fileDecisions.add(new ReassignmentDecision(
                        fid, fn.label(), origMod, origMod, true, false, attemptsUsed,
                        "FALLBACK_ORIGINAL_AFTER_MAX_RETRIES",
                        TRUST_TIER));
            }
        }

        // Apply Stage-2 placements and invalidate lazy cache ONLY for mutated leaf modules
        var mutatedLeafModules = new HashSet<String>();
        for (var entry : pendingPlacements.entrySet()) {
            String fid = entry.getKey();
            String targetMod = entry.getValue();
            workingModules.computeIfAbsent(targetMod, k -> new TreeSet<>()).add(fid);
            mutatedLeafModules.add(targetMod);
            mutatedLeafModules.add(originalModuleByFile.get(fid));
        }
        workingModules.entrySet().removeIf(e -> e.getValue().isEmpty());
        for (String mutated : mutatedLeafModules) {
            lazySummaryCache.remove("leaf:" + mutated);
        }

        var refinedModuleByFile = new TreeMap<String, String>();
        for (var entry : workingModules.entrySet()) {
            for (String fid : entry.getValue()) {
                refinedModuleByFile.put(fid, entry.getKey());
            }
        }

        // ─── Stage 3: Build strict Exclusive Leaf/Branch Hierarchical Tree ───
        DsmMatrixBuilder.SemRefMetrics stage2Metrics = DsmMatrixBuilder.computeSemRefMetrics(
                refinedModuleByFile, labelByFile, cleanDeps);
        MutableHierarchy mutableTree = buildInitialExclusiveHierarchy(workingModules, stage2Metrics);

        // ─── Stage 4: Top-Down Iterative Branch Refinement with Bottom-Up Lazy Summaries ───
        var branchMoves = runTopDownBranchRefinement(
                mutableTree, workingModules, stage2Metrics, fileById, redactedSnippetsByPath,
                effectiveAdvisor, lazySummaryCache, summaryStats, cfg);

        // Materialize immutable HierarchyTree with lazily cached summaries & validate exclusive leaf/branch invariant
        HierarchyTree finalTree = materializeTree(mutableTree, lazySummaryCache);
        validateExclusiveLeafBranchTree(finalTree, fileById.keySet());

        // Compute post-refinement SemRef metrics and Eqs. 4-5 RDP
        BigDecimal cohesionRdp = DsmMatrixBuilder.computeRdp(
                baselineMetrics.meanCohesion().doubleValue(),
                stage2Metrics.meanCohesion().doubleValue());

        return new RefinementResult(
                SCHEMA,
                DERIVATION,
                TRUST_TIER,
                REDACTION_VERSION,
                true,
                graphIdentity,
                unanchoredFileIds,
                fileDecisions,
                branchMoves,
                finalTree,
                refinedModuleByFile,
                baselineMetrics,
                stage2Metrics,
                cohesionRdp,
                redactedSecretsCount,
                summaryStats[0],
                summaryStats[1]);
    }

    /**
     * Strictly validates the SemRef exclusive leaf/branch tree invariant:
     * <ul>
     *   <li>Root exists and is a {@link NodeKind#BRANCH}.</li>
     *   <li>Every {@link NodeKind#LEAF} has non-empty {@code fileIds} and empty {@code childClusterIds}.</li>
     *   <li>Every {@link NodeKind#BRANCH} has empty {@code fileIds} and non-empty {@code childClusterIds}.</li>
     *   <li>Graph is a single connected rooted acyclic tree (each non-root node visited once).</li>
     *   <li>Every expected file appears in exactly one {@link NodeKind#LEAF} (zero orphans, zero duplicates).</li>
     *   <li>Every node carries {@link TrustTier#ADVISORY_UNVERIFIED}.</li>
     * </ul>
     */
    public static void validateExclusiveLeafBranchTree(HierarchyTree tree, Set<String> expectedFiles) {
        Objects.requireNonNull(tree, "tree");
        HierarchyNode root = tree.nodesById().get(tree.rootId());
        if (root == null) {
            throw new IllegalStateException("Missing root node: " + tree.rootId());
        }
        if (expectedFiles == null || expectedFiles.isEmpty()) {
            return;
        }
        if (root.kind() != NodeKind.BRANCH) {
            throw new IllegalStateException("Root node must be BRANCH: " + root.id());
        }
        var visitedNodes = new HashSet<String>();
        var seenFiles = new HashSet<String>();
        var stack = new ArrayDeque<String>();
        stack.push(root.id());
        visitedNodes.add(root.id());

        while (!stack.isEmpty()) {
            String curId = stack.pop();
            HierarchyNode node = tree.nodesById().get(curId);
            if (node == null) {
                throw new IllegalStateException("Dangling cluster reference: " + curId);
            }
            if (node.trustTier() != TRUST_TIER) {
                throw new IllegalStateException("Quarantine violation on node " + curId + ": " + node.trustTier());
            }
            if (node.kind() == NodeKind.LEAF) {
                if (node.fileIds().isEmpty() || !node.childClusterIds().isEmpty()) {
                    throw new IllegalStateException("Exclusive LEAF invariant violated on " + curId);
                }
                for (String fid : node.fileIds()) {
                    if (!seenFiles.add(fid)) {
                        throw new IllegalStateException("Duplicate file assignment in hierarchy: " + fid);
                    }
                }
            } else {
                if (!node.fileIds().isEmpty() || node.childClusterIds().isEmpty()) {
                    throw new IllegalStateException("Exclusive BRANCH invariant violated on " + curId);
                }
                for (String childId : node.childClusterIds()) {
                    if (!visitedNodes.add(childId)) {
                        throw new IllegalStateException("Cycle or shared parent detected in hierarchy at " + childId);
                    }
                    stack.push(childId);
                }
            }
        }
        if (visitedNodes.size() != tree.nodesById().size()) {
            throw new IllegalStateException("Unreachable orphan cluster nodes detected in hierarchy");
        }
        if (!seenFiles.equals(expectedFiles)) {
            throw new IllegalStateException("Hierarchy leaf files do not exactly partition expected files");
        }
    }

    // ─── Internal Hierarchy Construction & Stage 4 Top-Down Refinement ───

    private static final class MutableCluster {
        final String id;
        final NodeKind kind;
        final String label;
        String parentId;
        final TreeSet<String> fileIds = new TreeSet<>();
        final TreeSet<String> childClusterIds = new TreeSet<>();

        MutableCluster(String id, NodeKind kind, String label, String parentId) {
            this.id = id;
            this.kind = kind;
            this.label = label;
            this.parentId = parentId;
        }
    }

    private record MutableHierarchy(String rootId, Map<String, MutableCluster> clusters) {}

    private static MutableHierarchy buildInitialExclusiveHierarchy(
            Map<String, TreeSet<String>> leafModules,
            DsmMatrixBuilder.SemRefMetrics metrics) {
        var clusters = new TreeMap<String, MutableCluster>();
        String rootId = "branch:root";
        var root = new MutableCluster(rootId, NodeKind.BRANCH, "Architecture Root", null);
        clusters.put(rootId, root);

        List<String> modIds = new ArrayList<>(leafModules.keySet());
        Collections.sort(modIds);

        // Create all LEAF clusters first (each strictly holds only files)
        for (String mid : modIds) {
            String leafId = "leaf:" + mid;
            var leaf = new MutableCluster(leafId, NodeKind.LEAF, mid, null);
            leaf.fileIds.addAll(leafModules.get(mid));
            clusters.put(leafId, leaf);
        }

        // For <= 2 leaf modules, attach leaves directly under ROOT branch (clean exclusive leaf/branch tree)
        if (modIds.size() <= 2) {
            for (String mid : modIds) {
                String leafId = "leaf:" + mid;
                clusters.get(leafId).parentId = rootId;
                root.childClusterIds.add(leafId);
            }
            return new MutableHierarchy(rootId, clusters);
        }

        // Build symmetric inter-module coupling map from SemRef Eq. 3 Dep_m(a, b)
        var pairCoupling = new HashMap<String, Double>();
        for (var c : metrics.interModuleCoupling()) {
            String a = c.sourceModule().compareTo(c.targetModule()) <= 0 ? c.sourceModule() : c.targetModule();
            String b = c.sourceModule().compareTo(c.targetModule()) <= 0 ? c.targetModule() : c.sourceModule();
            pairCoupling.merge(a + "|" + b, c.depStrength().doubleValue(), Double::sum);
        }

        // Deterministically group coupled leaf modules into intermediate BRANCH clusters (max size 3)
        var assignedBranchByMod = new HashMap<String, String>();
        var branchMembers = new TreeMap<String, List<String>>();

        // Sort module pairs by coupling descending, tie-break deterministically
        record ModPair(String a, String b, double weight) {}
        var pairs = new ArrayList<ModPair>();
        for (var e : pairCoupling.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            if (parts.length == 2 && e.getValue() > 0.0) {
                pairs.add(new ModPair(parts[0], parts[1], e.getValue()));
            }
        }
        pairs.sort(Comparator.comparingDouble(ModPair::weight).reversed()
                .thenComparing(ModPair::a)
                .thenComparing(ModPair::b));

        int branchSeq = 1;
        for (var p : pairs) {
            String ba = assignedBranchByMod.get(p.a());
            String bb = assignedBranchByMod.get(p.b());
            if (ba == null && bb == null) {
                String bid = "branch:subsystem-" + (branchSeq++);
                var members = new ArrayList<String>(List.of(p.a(), p.b()));
                branchMembers.put(bid, members);
                assignedBranchByMod.put(p.a(), bid);
                assignedBranchByMod.put(p.b(), bid);
            } else if (ba != null && bb == null && branchMembers.get(ba).size() < MAX_LEAF_GROUP_SIZE) {
                branchMembers.get(ba).add(p.b());
                assignedBranchByMod.put(p.b(), ba);
            } else if (bb != null && ba == null && branchMembers.get(bb).size() < MAX_LEAF_GROUP_SIZE) {
                branchMembers.get(bb).add(p.a());
                assignedBranchByMod.put(p.a(), bb);
            }
        }

        // Any remaining ungrouped leaf module gets its own subsystem branch if intermediate branches exist,
        // or attaches directly to root (both preserve the strict exclusive leaf/branch tree invariant!).
        for (String mid : modIds) {
            if (!assignedBranchByMod.containsKey(mid)) {
                String bid = "branch:subsystem-" + (branchSeq++);
                branchMembers.put(bid, new ArrayList<>(List.of(mid)));
                assignedBranchByMod.put(mid, bid);
            }
        }

        // Materialize intermediate BRANCH nodes under ROOT
        for (var entry : branchMembers.entrySet()) {
            String bid = entry.getKey();
            var members = entry.getValue();
            Collections.sort(members);
            String label = "Subsystem (" + String.join(", ", members) + ")";
            var branch = new MutableCluster(bid, NodeKind.BRANCH, label, rootId);
            for (String mid : members) {
                String leafId = "leaf:" + mid;
                branch.childClusterIds.add(leafId);
                clusters.get(leafId).parentId = bid;
            }
            clusters.put(bid, branch);
            root.childClusterIds.add(bid);
        }

        return new MutableHierarchy(rootId, clusters);
    }

    private static List<BranchMoveDecision> runTopDownBranchRefinement(
            MutableHierarchy tree,
            Map<String, TreeSet<String>> leafModules,
            DsmMatrixBuilder.SemRefMetrics metrics,
            Map<String, FileNode> fileById,
            Map<String, String> redactedSnippetsByPath,
            RefinementAdvisor advisor,
            Map<String, String> lazySummaryCache,
            int[] summaryStats,
            Config cfg) {
        if (cfg.maxTopDownIterations() <= 0) return List.of();
        var root = tree.clusters().get(tree.rootId());
        var intermediateBranches = root.childClusterIds.stream()
                .map(tree.clusters()::get)
                .filter(c -> c != null && c.kind == NodeKind.BRANCH)
                .toList();
        if (intermediateBranches.size() < 2) return List.of();

        // Index directed + reverse Eq. 3 inter-module coupling
        var coupling = new HashMap<String, Double>();
        for (var c : metrics.interModuleCoupling()) {
            coupling.merge(c.sourceModule() + "->" + c.targetModule(), c.depStrength().doubleValue(), Double::sum);
        }

        var moves = new ArrayList<BranchMoveDecision>();
        for (int iter = 1; iter <= cfg.maxTopDownIterations(); iter++) {
            boolean anyMoved = false;
            var activeBranches = new ArrayList<String>();
            for (String cid : root.childClusterIds) {
                var c = tree.clusters().get(cid);
                if (c != null && c.kind == NodeKind.BRANCH && !c.childClusterIds.isEmpty()) {
                    activeBranches.add(cid);
                }
            }
            if (activeBranches.size() < 2) break;

            for (String branchId : List.copyOf(activeBranches)) {
                var currentBranch = tree.clusters().get(branchId);
                if (currentBranch == null || currentBranch.childClusterIds.isEmpty()) continue;

                for (String leafId : List.copyOf(currentBranch.childClusterIds)) {
                    var leaf = tree.clusters().get(leafId);
                    if (leaf == null || leaf.kind != NodeKind.LEAF) continue;
                    String modId = leaf.label;

                    // Compute coupling from this leaf to every sibling branch (SemRef Eq. 3)
                    record BranchScore(String bid, double dep) {}
                    var candScores = new ArrayList<BranchScore>();
                    for (String sibId : activeBranches) {
                        if (sibId.equals(branchId)) continue;
                        var sib = tree.clusters().get(sibId);
                        if (sib == null || sib.childClusterIds.isEmpty()) continue;
                        double sum = 0.0;
                        for (String sibLeafId : sib.childClusterIds) {
                            var sibLeaf = tree.clusters().get(sibLeafId);
                            if (sibLeaf != null) {
                                sum += coupling.getOrDefault(modId + "->" + sibLeaf.label, 0.0);
                                sum += coupling.getOrDefault(sibLeaf.label + "->" + modId, 0.0);
                            }
                        }
                        if (sum > 0.0) {
                            candScores.add(new BranchScore(sibId, sum));
                        }
                    }
                    if (candScores.isEmpty()) continue;
                    candScores.sort(Comparator.comparingDouble(BranchScore::dep).reversed()
                            .thenComparing(BranchScore::bid));

                    // Top-3 coupled sibling branches + bottom-up lazy summaries ONLY for visited clusters
                    var topOptions = new ArrayList<CandidateModuleOption>();
                    var validTargetIds = new HashSet<String>();
                    for (int k = 0; k < Math.min(cfg.topKCandidates(), candScores.size()); k++) {
                        var bs = candScores.get(k);
                        validTargetIds.add(bs.bid());
                        String sibSummary = getOrComputeBranchSummary(
                                bs.bid(), tree, leafModules, fileById, redactedSnippetsByPath,
                                advisor, lazySummaryCache, summaryStats);
                        topOptions.add(new CandidateModuleOption(
                                bs.bid(),
                                BigDecimal.valueOf(bs.dep()).setScale(4, RoundingMode.HALF_UP),
                                sibSummary));
                    }

                    String leafSummary = getOrComputeLeafSummary(
                            modId, leafModules.getOrDefault(modId, new TreeSet<>()),
                            fileById, redactedSnippetsByPath, advisor, lazySummaryCache, summaryStats);
                    String curBranchSummary = getOrComputeBranchSummary(
                            branchId, tree, leafModules, fileById, redactedSnippetsByPath,
                            advisor, lazySummaryCache, summaryStats);

                    var prompt = new BranchReassignmentPrompt(
                            leafId, leafSummary, branchId, curBranchSummary, topOptions, iter);
                    Optional<String> targetOpt = advisor.proposeBranchReassignment(prompt);
                    if (targetOpt == null || targetOpt.isEmpty()) continue;
                    String targetBranchId = targetOpt.get().strip();
                    if (!validTargetIds.contains(targetBranchId) || targetBranchId.equals(branchId)) continue;

                    var targetBranch = tree.clusters().get(targetBranchId);
                    if (targetBranch == null || targetBranch.kind != NodeKind.BRANCH) continue;

                    // Apply move and cleanly prune emptied intermediate branch to preserve exclusive leaf/branch invariant
                    currentBranch.childClusterIds.remove(leafId);
                    targetBranch.childClusterIds.add(leafId);
                    leaf.parentId = targetBranchId;

                    lazySummaryCache.remove(branchId);
                    lazySummaryCache.remove(targetBranchId);
                    lazySummaryCache.remove(tree.rootId());

                    if (currentBranch.childClusterIds.isEmpty()) {
                        root.childClusterIds.remove(branchId);
                        tree.clusters().remove(branchId);
                    }
                    moves.add(new BranchMoveDecision(leafId, branchId, targetBranchId, iter, TRUST_TIER));
                    anyMoved = true;
                }
            }
            if (!anyMoved) break;
        }
        return moves;
    }

    /** Bottom-up lazy summary for a LEAF cluster: summarizes strictly from pre-redacted file snippets. */
    private static String getOrComputeLeafSummary(
            String moduleId,
            Set<String> memberFileIds,
            Map<String, FileNode> fileById,
            Map<String, String> redactedSnippetsByPath,
            RefinementAdvisor advisor,
            Map<String, String> summaryCache,
            int[] summaryStats) {
        String key = "leaf:" + moduleId;
        String cached = summaryCache.get(key);
        if (cached != null) {
            summaryStats[1]++;
            return cached;
        }
        var childLabels = new ArrayList<String>();
        var redactedInputs = new ArrayList<String>();
        for (String fid : memberFileIds) {
            FileNode fn = fileById.get(fid);
            if (fn == null) continue;
            childLabels.add(fn.label());
            redactedInputs.add(resolveSafeSnippetForFile(fn, redactedSnippetsByPath));
        }
        String rawSummary = advisor.summarizeCluster(
                new ClusterSummaryPrompt(key, NodeKind.LEAF, childLabels, redactedInputs));
        String safeSummary = sanitizeAdvisorOutput(rawSummary, "Leaf module " + moduleId);
        summaryCache.put(key, safeSummary);
        summaryStats[0]++;
        return safeSummary;
    }

    /** Bottom-up lazy summary for a BRANCH cluster: summarizes strictly from direct children's summaries. */
    private static String getOrComputeBranchSummary(
            String branchId,
            MutableHierarchy tree,
            Map<String, TreeSet<String>> leafModules,
            Map<String, FileNode> fileById,
            Map<String, String> redactedSnippetsByPath,
            RefinementAdvisor advisor,
            Map<String, String> summaryCache,
            int[] summaryStats) {
        String cached = summaryCache.get(branchId);
        if (cached != null) {
            summaryStats[1]++;
            return cached;
        }
        var branch = tree.clusters().get(branchId);
        if (branch == null) return "";
        var childLabels = new ArrayList<String>();
        var childSummaries = new ArrayList<String>();
        for (String childId : branch.childClusterIds) {
            var child = tree.clusters().get(childId);
            if (child == null) continue;
            childLabels.add(child.label);
            if (child.kind == NodeKind.LEAF) {
                childSummaries.add(getOrComputeLeafSummary(
                        child.label, leafModules.getOrDefault(child.label, new TreeSet<>()),
                        fileById, redactedSnippetsByPath, advisor, summaryCache, summaryStats));
            } else {
                childSummaries.add(getOrComputeBranchSummary(
                        childId, tree, leafModules, fileById, redactedSnippetsByPath,
                        advisor, summaryCache, summaryStats));
            }
        }
        String rawSummary = advisor.summarizeCluster(
                new ClusterSummaryPrompt(branchId, NodeKind.BRANCH, childLabels, childSummaries));
        String safeSummary = sanitizeAdvisorOutput(rawSummary, "Branch " + branch.label);
        summaryCache.put(branchId, safeSummary);
        summaryStats[0]++;
        return safeSummary;
    }

    private static HierarchyTree materializeTree(MutableHierarchy mutable, Map<String, String> lazySummaryCache) {
        var nodes = new TreeMap<String, HierarchyNode>();
        for (var c : mutable.clusters().values()) {
            Optional<String> summary = Optional.ofNullable(lazySummaryCache.get(c.id));
            nodes.put(c.id, new HierarchyNode(
                    c.id,
                    c.kind,
                    c.label,
                    Optional.ofNullable(c.parentId),
                    List.copyOf(c.fileIds),
                    List.copyOf(c.childClusterIds),
                    summary,
                    TRUST_TIER));
        }
        return new HierarchyTree(mutable.rootId(), nodes);
    }

    private static String resolveSafeSnippetForFile(FileNode fn, Map<String, String> redactedSnippetsByPath) {
        if (!fn.sourcePath().isEmpty()) {
            String preRedacted = redactedSnippetsByPath.get(fn.sourcePath());
            if (preRedacted != null) {
                return preRedacted;
            }
        }
        // Even synthetic metadata fallback passes through redactDocumentForPath + sentinel neutralization
        String synthetic = "// symbol: " + fn.label() + " (" + fn.sourcePath() + ")";
        var doc = LlmTransport.redactDocumentForPath(fn.sourcePath(), synthetic);
        return LlmTransport.neutralizeInjectionSentinels(doc.text());
    }

    private static String buildBoundedSafeSnippet(LlmTransport.RedactedDocument doc, int maxLines, int maxChars) {
        var sb = new StringBuilder();
        var lines = doc.lines();
        int limit = Math.min(lines.size(), maxLines);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
            if (sb.length() >= maxChars) {
                sb.setLength(maxChars);
                break;
            }
        }
        return LlmTransport.neutralizeInjectionSentinels(sb.toString());
    }

    private static String sanitizeAdvisorOutput(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String redacted = LlmTransport.redactSecrets(raw).text();
        String safe = LlmTransport.neutralizeInjectionSentinels(redacted).strip();
        if (safe.isEmpty()) return fallback;
        return safe.length() > 600 ? safe.substring(0, 600) : safe;
    }

    // ─── Read-Only Graph Path Indexing Helpers ───

    private static Map<String, String> indexDocumentPaths(KnowledgeGraph graph) {
        var map = new HashMap<String, String>();
        for (var ctx : graph.contexts()) {
            for (var node : graph.nodes(ctx.identity())) {
                if (!"SOURCE_DOCUMENT".equals(node.kind())) continue;
                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null) continue;
                    var obj = readSmall(ev);
                    if ("source.document".equals(ev.family()) && obj.get("path") instanceof String p && !p.isBlank()) {
                        map.putIfAbsent(node.identity(), CapturedFileIndex.normalizePath(p));
                        break;
                    }
                    if ("source.bytes".equals(ev.family()) && obj.get("document") instanceof Map<?, ?> d
                            && d.get("path") instanceof String p && !p.isBlank()) {
                        map.putIfAbsent(node.identity(), CapturedFileIndex.normalizePath(p));
                        break;
                    }
                }
            }
        }
        return map;
    }

    private static Map<String, String> indexEntitySourcePaths(
            KnowledgeGraph graph, String context, Map<String, String> docIdToPath) {
        var map = new HashMap<String, String>();
        for (var node : graph.nodes(context)) {
            for (var ref : node.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null) continue;
                if ("java.declaration".equals(ev.family())) {
                    var obj = readSmall(ev);
                    String docId = null;
                    if (obj.get("entity") instanceof Map<?, ?> ent
                            && ent.get("declaration") instanceof Map<?, ?> sp
                            && sp.get("document") instanceof String d) {
                        docId = d;
                    } else if (obj.get("declaration") instanceof Map<?, ?> sp
                            && sp.get("document") instanceof String d) {
                        docId = d;
                    }
                    if (docId != null && docIdToPath.containsKey(docId)) {
                        map.putIfAbsent(node.identity(), docIdToPath.get(docId));
                        break;
                    }
                } else if ("polyglot.source-syntax".equals(ev.family())) {
                    var obj = readSmall(ev);
                    Map<?, ?> fields = obj.get("fields") instanceof Map<?, ?> f ? f : obj;
                    if (fields.get("path") instanceof String p && !p.isBlank()) {
                        map.putIfAbsent(node.identity(), CapturedFileIndex.normalizePath(p));
                        break;
                    }
                }
            }
        }
        return map;
    }

    private static Map<String, Object> readSmall(GraphEvidence ev) {
        if (ev.canonicalJson().length() > 131_072) return Map.of();
        try {
            return GraphJson.object(GraphJson.read(ev.canonicalJson(), SMALL_JSON));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    /** Zero-LLM deterministic fallback advisor implementing SemRef Stage 2-4 cleanly offline. */
    private enum DeterministicFallbackAdvisor implements RefinementAdvisor {
        INSTANCE;

        @Override
        public Optional<String> proposeFileReassignment(FileReassignmentPrompt prompt) {
            if (prompt.candidateModules().isEmpty()) return Optional.empty();
            for (var opt : prompt.candidateModules()) {
                if (!prompt.priorRejectedModules().contains(opt.moduleId())
                        && opt.dependencyStrength().doubleValue() > 0.0) {
                    return Optional.of(opt.moduleId());
                }
            }
            return Optional.empty();
        }

        @Override
        public boolean confirmFileReassignment(ConsistencyCheckPrompt prompt) {
            if (prompt.proposedModuleId() == null || prompt.proposedModuleId().isBlank()) {
                return false;
            }
            double proposedDep = prompt.proposedModuleDep().doubleValue();
            double bestAlt = 0.0;
            for (var alt : prompt.alternativeModules()) {
                if (alt.dependencyStrength().doubleValue() > bestAlt) {
                    bestAlt = alt.dependencyStrength().doubleValue();
                }
            }
            // Confirm iff proposed module has positive dependency strength and is not strictly dominated by an alternative
            return proposedDep > 0.0 && proposedDep + 1e-9 >= bestAlt;
        }

        @Override
        public String summarizeCluster(ClusterSummaryPrompt prompt) {
            String members = String.join(", ", prompt.childLabels().stream().limit(6).toList());
            return prompt.kind().name() + " " + prompt.clusterId()
                    + " (" + prompt.childLabels().size() + " items: " + members + ")";
        }

        @Override
        public Optional<String> proposeBranchReassignment(BranchReassignmentPrompt prompt) {
            if (prompt.candidateBranches().isEmpty()) return Optional.empty();
            var top = prompt.candidateBranches().getFirst();
            // Move only on strong inter-module coupling to prevent churn
            return top.dependencyStrength().doubleValue() >= 0.50
                    ? Optional.of(top.moduleId())
                    : Optional.empty();
        }
    }
}
