package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.AffectedOutput;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.evidence.EvidenceRequirement;
import com.evolution.analysis.evidence.EvidenceSubject;
import com.evolution.analysis.evidence.ProviderObservationReference;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Passive counterfactual Fail-Close vs. Fail-Open RPC resilience analyzer.
 *
 * <p>Implements per-service backward error-propagation composition from outbound RPC/HTTP call sites
 * ({@code CALLS_SERVICE}/{@code INVOKES_RPC}, {@code f_k}) to inbound handlers
 * ({@code ENDPOINT}/{@code GRPC_HANDLER}, {@code f_0}), using order-independent XOR path-permutation
 * deduplication ({@code pathXor ^= hash64(methodId)}) and bounded branching ({@code maxBranchFactor = 64}).
 *
 * <p>Invariants preserved:
 * <ul>
 *   <li>{@code I1}: 100% passive ({@code --build=off}); zero host builds or runtime execution.</li>
 *   <li>{@code I2}: Deterministic {@link TreeMap} ordering keyed by {@code (inboundId, outboundId, pathDigest)}.</li>
 *   <li>{@code I3}: Emits typed {@link CapabilityGapRecord} ({@code FAIL_CLOSE_HANDLER_OPAQUE}) for opaque
 *       library/async handlers and never flattens mixed {@code (IB, OB)} pairs.</li>
 *   <li>{@code I4}: Tags syntax-derived summaries and verdicts with {@link TrustTier#SOURCE_SYNTAX}.</li>
 *   <li>{@code I5}: Strictly generic Java/Go/RPC graph rules with zero benchmark-specific conventions.</li>
 * </ul>
 */
public final class FailCloseResilienceAnalyzer {

    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("polyglot.fail-close-resilience", "wp1.2-v1");
    public static final VersionedIdentifier GAP_CATALOG =
            new VersionedIdentifier("evidence.fail-close-resilience", "wp1.2-v1");

    public static final String MECHANISM_CATEGORY = "polyglot.fail-close-resilience";
    public static final String GAP_REASON_HANDLER_OPAQUE = "FAIL_CLOSE_HANDLER_OPAQUE";
    public static final String GAP_REASON_UNKNOWN_ASYNC = "UNKNOWN_ASYNC_HANDLER";
    public static final String GAP_REASON_TIER_METADATA_MISSING = "FAIL_CLOSE_TIER_METADATA_MISSING";

    public static final int DEFAULT_MAX_BRANCH_FACTOR = 64;
    public static final int DEFAULT_MAX_DEPTH = 16;
    public static final int DEFAULT_MAX_PATHS_PER_OUTBOUND = 256;

    public static final Set<String> OUTBOUND_RPC_EDGE_KINDS = Set.of(
            "CALLS_SERVICE",
            "INVOKES_RPC",
            "polyglot.service-call.candidate",
            "polyglot.grpc-service-call.candidate",
            "java.http-client-call",
            "syntax.http-call-candidate",
            "syntax.rpc-call-candidate"
    );

    public static final Set<String> INBOUND_ENDPOINT_NODE_KINDS = Set.of(
            "ENDPOINT",
            "GRPC_HANDLER",
            "SOURCE_RPC_HANDLER_CANDIDATE",
            "API_ROUTE",
            "HTTP_ENDPOINT",
            "ROUTE"
    );

    private static final Set<String> INTRA_SERVICE_CALL_EDGE_KINDS = Set.of(
            "java.calls",
            "graph.calls",
            "polyglot.calls",
            "graph.contains",
            "graph.declares",
            "spring.selected-binding",
            "spring.produces-candidate",
            "graph.depends-on",
            "CALLS_METHOD"
    );

    private static final Set<String> TRANSPARENT_CONTAINER_KINDS = Set.of(
            "ENDPOINT",
            "GRPC_HANDLER",
            "SOURCE_RPC_HANDLER_CANDIDATE",
            "API_ROUTE",
            "HTTP_ENDPOINT",
            "ROUTE",
            "BEAN_DEFINITION_CANDIDATE",
            "BEAN_PRODUCER",
            "INJECTION_POINT",
            "INJECTION_USE",
            "SPRING_CONTROLLER",
            "SERVICE_ENDPOINT_REFERENCE",
            "RPC_SOURCE_REFERENCE"
    );

    /** Intra-method error propagation disposition for one callsite or callee boundary. */
    public enum PropagationDisposition {
        PROPAGATES_ERROR,
        SUPPRESSED_FAIL_OPEN,
        UNKNOWN_OPAQUE
    }

    /** Per-path or per-pair counterfactual RPC resilience verdict. */
    public enum ResilienceVerdict {
        FAIL_CLOSE,
        FAIL_OPEN,
        MIXED_PATHS,
        UNKNOWN
    }

    /** Exception/error transformation edge {@code T -> F} observed through catch or Go error wrapping. */
    public record ExceptionTransformation(
            String sourceException,
            String targetException,
            String mechanism
    ) implements Comparable<ExceptionTransformation> {
        public ExceptionTransformation {
            sourceException = Objects.requireNonNull(sourceException, "sourceException").strip();
            targetException = Objects.requireNonNull(targetException, "targetException").strip();
            mechanism = Objects.requireNonNull(mechanism, "mechanism").strip();
        }

        @Override
        public int compareTo(ExceptionTransformation other) {
            int cmp = sourceException.compareTo(other.sourceException);
            if (cmp != 0) return cmp;
            cmp = targetException.compareTo(other.targetException);
            return cmp != 0 ? cmp : mechanism.compareTo(other.mechanism);
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("mechanism", mechanism);
            m.put("sourceException", sourceException);
            m.put("targetException", targetException);
            return Collections.unmodifiableMap(m);
        }
    }

    /** Single {@code file:line} stack/call frame in a call-path flamegraph. */
    public record CallFrame(
            String nodeId,
            String symbol,
            String sourcePath,
            int startLine,
            int endLine,
            String kind
    ) {
        public CallFrame {
            nodeId = Objects.requireNonNull(nodeId, "nodeId").strip();
            symbol = Objects.requireNonNull(symbol, "symbol").strip();
            sourcePath = sourcePath == null ? "" : sourcePath.strip();
            startLine = Math.max(0, startLine);
            endLine = Math.max(startLine, endLine);
            kind = kind == null ? "" : kind.strip();
        }

        /** Human- and machine-readable {@code file:line} location token. */
        public String fileLine() {
            if (sourcePath.isEmpty()) {
                return startLine > 0 ? symbol + ":" + startLine : symbol;
            }
            return startLine > 0 ? sourcePath + ":" + startLine : sourcePath;
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("endLine", endLine);
            m.put("fileLine", fileLine());
            m.put("kind", kind);
            m.put("nodeId", nodeId);
            m.put("sourcePath", sourcePath);
            m.put("startLine", startLine);
            m.put("symbol", symbol);
            return Collections.unmodifiableMap(m);
        }
    }

    /** Cached parser-neutral intra-method error propagation summary for a {@code (method, callsite)} pair. */
    public record MethodErrorSummary(
            String language,
            String methodId,
            String callsiteId,
            String sourcePath,
            int line,
            int endLine,
            String targetCall,
            String callProtocol,
            Set<String> baseExceptions,
            Set<String> propagatedExceptions,
            List<ExceptionTransformation> transformations,
            PropagationDisposition disposition,
            boolean counterfactualOkPathExists,
            boolean lastFailurePrimarySuppressed,
            boolean conjunctionFailureGroup,
            boolean opaqueHandler,
            Optional<String> opaqueReason,
            Optional<String> fallbackMechanism,
            TrustTier trustTier
    ) {
        public MethodErrorSummary {
            language = Objects.requireNonNull(language, "language").strip().toLowerCase(Locale.ROOT);
            methodId = Objects.requireNonNull(methodId, "methodId").strip();
            callsiteId = Objects.requireNonNull(callsiteId, "callsiteId").strip();
            sourcePath = sourcePath == null ? "" : sourcePath.strip();
            line = Math.max(1, line);
            endLine = Math.max(line, endLine);
            targetCall = Objects.requireNonNull(targetCall, "targetCall").strip();
            callProtocol = callProtocol == null ? "METHOD_CALL" : callProtocol.strip();
            baseExceptions = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(baseExceptions, "baseExceptions")));
            propagatedExceptions = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(propagatedExceptions, "propagatedExceptions")));
            transformations = Objects.requireNonNull(transformations, "transformations").stream()
                    .distinct()
                    .sorted()
                    .toList();
            disposition = Objects.requireNonNull(disposition, "disposition");
            opaqueReason = opaqueReason == null
                    ? Optional.empty()
                    : opaqueReason.map(String::strip).filter(s -> !s.isEmpty());
            fallbackMechanism = fallbackMechanism == null
                    ? Optional.empty()
                    : fallbackMechanism.map(String::strip).filter(s -> !s.isEmpty());
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
        }

        /** Single-method local counterfactual resilience verdict (incorporating D1.3). */
        public ResilienceVerdict localVerdict() {
            if (opaqueHandler || disposition == PropagationDisposition.UNKNOWN_OPAQUE) {
                return ResilienceVerdict.UNKNOWN;
            }
            if (disposition == PropagationDisposition.SUPPRESSED_FAIL_OPEN) {
                return ResilienceVerdict.FAIL_OPEN;
            }
            return counterfactualOkPathExists ? ResilienceVerdict.FAIL_CLOSE : ResilienceVerdict.FAIL_OPEN;
        }

        public ContentDigest summaryDigest() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(toView()));
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("baseExceptions", List.copyOf(baseExceptions));
            m.put("callProtocol", callProtocol);
            m.put("callsiteId", callsiteId);
            m.put("conjunctionFailureGroup", conjunctionFailureGroup);
            m.put("counterfactualOkPathExists", counterfactualOkPathExists);
            m.put("disposition", disposition.name());
            m.put("endLine", endLine);
            fallbackMechanism.ifPresent(v -> m.put("fallbackMechanism", v));
            m.put("language", language);
            m.put("lastFailurePrimarySuppressed", lastFailurePrimarySuppressed);
            m.put("line", line);
            m.put("localVerdict", localVerdict().name());
            m.put("methodId", methodId);
            m.put("opaqueHandler", opaqueHandler);
            opaqueReason.ifPresent(v -> m.put("opaqueReason", v));
            m.put("propagatedExceptions", List.copyOf(propagatedExceptions));
            m.put("sourcePath", sourcePath);
            m.put("targetCall", targetCall);
            m.put("transformations", transformations.stream().map(ExceptionTransformation::toView).toList());
            m.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(m);
        }
    }

    /** Deterministic composite key {@code (inboundId, outboundId, pathDigest)} for {@link TreeMap} storage ({@code I2}). */
    public record PathKey(
            String inboundId,
            String outboundId,
            String pathDigest
    ) implements Comparable<PathKey> {
        public PathKey {
            inboundId = Objects.requireNonNull(inboundId, "inboundId").strip();
            outboundId = Objects.requireNonNull(outboundId, "outboundId").strip();
            pathDigest = Objects.requireNonNull(pathDigest, "pathDigest").strip();
        }

        @Override
        public int compareTo(PathKey other) {
            int cmp = inboundId.compareTo(other.inboundId);
            if (cmp != 0) return cmp;
            cmp = outboundId.compareTo(other.outboundId);
            return cmp != 0 ? cmp : pathDigest.compareTo(other.pathDigest);
        }
    }

    /** Per-path resilience verdict with full {@code file:line} call-path flamegraph and XOR dedup telemetry. */
    public record PathVerdict(
            PathKey key,
            String inboundId,
            String inboundLabel,
            String inboundService,
            String outboundId,
            String outboundLabel,
            String outboundService,
            ResilienceVerdict verdict,
            TrustTier trustTier,
            long pathXorFingerprint,
            int deduplicatedPermutations,
            boolean counterfactualD13Verified,
            Set<String> propagatedExceptions,
            List<ExceptionTransformation> transformations,
            List<CallFrame> flamegraph,
            Optional<String> gapReason
    ) {
        public PathVerdict {
            Objects.requireNonNull(key, "key");
            inboundId = Objects.requireNonNull(inboundId, "inboundId").strip();
            inboundLabel = Objects.requireNonNull(inboundLabel, "inboundLabel").strip();
            inboundService = inboundService == null ? "" : inboundService.strip();
            outboundId = Objects.requireNonNull(outboundId, "outboundId").strip();
            outboundLabel = Objects.requireNonNull(outboundLabel, "outboundLabel").strip();
            outboundService = outboundService == null ? "" : outboundService.strip();
            verdict = Objects.requireNonNull(verdict, "verdict");
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
            propagatedExceptions = Collections.unmodifiableSet(new TreeSet<>(
                    propagatedExceptions == null ? Set.of() : propagatedExceptions));
            transformations = (transformations == null ? List.<ExceptionTransformation>of() : transformations)
                    .stream().distinct().sorted().toList();
            flamegraph = List.copyOf(Objects.requireNonNull(flamegraph, "flamegraph"));
            gapReason = gapReason == null
                    ? Optional.empty()
                    : gapReason.map(String::strip).filter(s -> !s.isEmpty());
        }

        /** Compact {@code file:line -> file:line} call chain representation. */
        public String flamegraphChain() {
            if (flamegraph.isEmpty()) {
                return inboundLabel + " -> " + outboundLabel;
            }
            var parts = new ArrayList<String>(flamegraph.size());
            for (CallFrame frame : flamegraph) {
                parts.add(frame.fileLine());
            }
            return String.join(" -> ", parts);
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("counterfactualD13Verified", counterfactualD13Verified);
            m.put("deduplicatedPermutations", deduplicatedPermutations);
            m.put("flamegraph", flamegraph.stream().map(CallFrame::toView).toList());
            m.put("flamegraphChain", flamegraphChain());
            gapReason.ifPresent(g -> m.put("gapReason", g));
            m.put("inboundId", inboundId);
            m.put("inboundLabel", inboundLabel);
            m.put("inboundService", inboundService);
            m.put("outboundId", outboundId);
            m.put("outboundLabel", outboundLabel);
            m.put("outboundService", outboundService);
            m.put("pathDigest", key.pathDigest());
            m.put("pathXorHex", Long.toHexString(pathXorFingerprint));
            m.put("propagatedExceptions", List.copyOf(propagatedExceptions));
            m.put("transformations", transformations.stream().map(ExceptionTransformation::toView).toList());
            m.put("trustTier", trustTier.name());
            m.put("verdict", verdict.name());
            return Collections.unmodifiableMap(m);
        }
    }

    /** Per-{@code (IB, OB)} aggregate summary preserving {@link ResilienceVerdict#MIXED_PATHS} without flattening. */
    public record PairSummary(
            String inboundId,
            String outboundId,
            String inboundService,
            String outboundService,
            ResilienceVerdict aggregateVerdict,
            int totalPaths,
            int failClosePathCount,
            int failOpenPathCount,
            int unknownPathCount,
            List<String> pathDigests
    ) {
        public PairSummary {
            inboundId = Objects.requireNonNull(inboundId, "inboundId").strip();
            outboundId = Objects.requireNonNull(outboundId, "outboundId").strip();
            inboundService = inboundService == null ? "" : inboundService.strip();
            outboundService = outboundService == null ? "" : outboundService.strip();
            aggregateVerdict = Objects.requireNonNull(aggregateVerdict, "aggregateVerdict");
            pathDigests = Objects.requireNonNull(pathDigests, "pathDigests").stream().distinct().sorted().toList();
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("aggregateVerdict", aggregateVerdict.name());
            m.put("failClosePathCount", failClosePathCount);
            m.put("failOpenPathCount", failOpenPathCount);
            m.put("inboundId", inboundId);
            m.put("inboundService", inboundService);
            m.put("outboundId", outboundId);
            m.put("outboundService", outboundService);
            m.put("pathDigests", pathDigests);
            m.put("totalPaths", totalPaths);
            m.put("unknownPathCount", unknownPathCount);
            return Collections.unmodifiableMap(m);
        }
    }

    /** Bounded traversal limits protecting against combinatorial blowup on dense graphs. */
    public record AnalysisConfig(
            int maxBranchFactor,
            int maxDepth,
            int maxPathsPerOutbound
    ) {
        public AnalysisConfig {
            if (maxBranchFactor < 1 || maxBranchFactor > 1024) {
                throw new IllegalArgumentException("maxBranchFactor must be in 1..1024");
            }
            if (maxDepth < 1 || maxDepth > 64) {
                throw new IllegalArgumentException("maxDepth must be in 1..64");
            }
            if (maxPathsPerOutbound < 1 || maxPathsPerOutbound > 10_000) {
                throw new IllegalArgumentException("maxPathsPerOutbound must be in 1..10000");
            }
        }

        public static AnalysisConfig defaults() {
            return new AnalysisConfig(DEFAULT_MAX_BRANCH_FACTOR, DEFAULT_MAX_DEPTH, DEFAULT_MAX_PATHS_PER_OUTBOUND);
        }
    }

    /** Lightweight call graph node for direct composition or {@link KnowledgeGraph} projection. */
    public record CallNode(
            String nodeId,
            String label,
            String kind,
            String serviceName,
            String sourcePath,
            int startLine,
            int endLine,
            boolean inboundEndpoint
    ) {
        public CallNode {
            nodeId = Objects.requireNonNull(nodeId, "nodeId").strip();
            label = Objects.requireNonNull(label, "label").strip();
            kind = kind == null ? "" : kind.strip();
            serviceName = serviceName == null ? "" : serviceName.strip();
            sourcePath = sourcePath == null ? "" : sourcePath.strip();
            startLine = Math.max(0, startLine);
            endLine = Math.max(startLine, endLine);
        }

        public CallFrame toFrame() {
            return new CallFrame(nodeId, label, sourcePath, startLine, endLine, kind);
        }
    }

    /** Directed edge in a {@link CallGraph}. */
    public record CallEdge(
            String sourceId,
            String targetId,
            String edgeKind
    ) {
        public CallEdge {
            sourceId = Objects.requireNonNull(sourceId, "sourceId").strip();
            targetId = Objects.requireNonNull(targetId, "targetId").strip();
            edgeKind = Objects.requireNonNull(edgeKind, "edgeKind").strip();
        }
    }

    /** Immutable parser-neutral call graph snapshot for backward {@code IB -> OB} composition. */
    public record CallGraph(
            Map<String, CallNode> nodes,
            List<CallEdge> edges
    ) {
        public CallGraph {
            var sortedNodes = new TreeMap<String, CallNode>(Objects.requireNonNull(nodes, "nodes"));
            nodes = Collections.unmodifiableMap(sortedNodes);
            edges = Objects.requireNonNull(edges, "edges").stream()
                    .distinct()
                    .sorted(Comparator.comparing(CallEdge::sourceId)
                            .thenComparing(CallEdge::targetId)
                            .thenComparing(CallEdge::edgeKind))
                    .toList();
        }
    }

    /** Deterministic report containing per-path verdicts, per-pair summaries, and typed gaps. */
    public record ResilienceReport(
            ContentDigest graphDigest,
            TrustTier trustTier,
            Map<PathKey, PathVerdict> pathVerdicts,
            Map<String, PairSummary> pairSummaries,
            List<CapabilityGapRecord> gaps,
            int xorDedupCollapsedCount,
            boolean branchBoundedTruncated
    ) {
        public ResilienceReport {
            Objects.requireNonNull(graphDigest, "graphDigest");
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
            var sortedPaths = new TreeMap<PathKey, PathVerdict>(Objects.requireNonNull(pathVerdicts, "pathVerdicts"));
            pathVerdicts = Collections.unmodifiableMap(sortedPaths);
            var sortedPairs = new TreeMap<String, PairSummary>(Objects.requireNonNull(pairSummaries, "pairSummaries"));
            pairSummaries = Collections.unmodifiableMap(sortedPairs);
            gaps = Objects.requireNonNull(gaps, "gaps").stream().distinct().sorted().toList();
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(PROVIDER, toView())));
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("branchBoundedTruncated", branchBoundedTruncated);
            m.put("gapCount", gaps.size());
            m.put("gaps", gaps.stream().map(FailCloseResilienceAnalyzer::gapToView).toList());
            m.put("graphDigest", graphDigest.value());
            m.put("pairSummaries", pairSummaries.values().stream().map(PairSummary::toView).toList());
            m.put("pathVerdicts", pathVerdicts.values().stream().map(PathVerdict::toView).toList());
            m.put("provider", PROVIDER.toString());
            m.put("trustTier", trustTier.name());
            m.put("xorDedupCollapsedCount", xorDedupCollapsedCount);
            return Collections.unmodifiableMap(m);
        }
    }

    private FailCloseResilienceAnalyzer() {}

    /**
     * Deterministic 64-bit hash for a method/node identifier (first 8 bytes of SHA-256 UTF-8).
     * Guarantees cross-JVM determinism ({@code I2}) for order-independent XOR path deduplication.
     */
    public static long hash64(String methodId) {
        Objects.requireNonNull(methodId, "methodId");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(methodId.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, 8).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Order-independent XOR path-permutation fingerprint ({@code pathXor ^= hash64(methodId)}). */
    public static long computePathXor(Collection<String> methodIds) {
        long xor = 0L;
        if (methodIds == null) return xor;
        for (String id : methodIds) {
            if (id != null && !id.isEmpty()) {
                xor ^= hash64(id);
            }
        }
        return xor;
    }

    /** Analyzes a canonical {@link KnowledgeGraph} with default bounded configuration. */
    public static ResilienceReport analyze(
            KnowledgeGraph graph,
            Map<String, List<MethodErrorSummary>> summariesByMethod) {
        return analyze(graph, summariesByMethod, AnalysisConfig.defaults());
    }

    /**
     * Projects an immutable {@link KnowledgeGraph} into a {@link CallGraph} and composes
     * intra-method error summaries backwards from outbound RPC calls to inbound endpoints.
     */
    public static ResilienceReport analyze(
            KnowledgeGraph graph,
            Map<String, List<MethodErrorSummary>> summariesByMethod,
            AnalysisConfig config) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(config, "config");
        CallGraph callGraph = extractCallGraph(graph);
        EvidenceContext evidenceContext = EvidenceContext.forSnapshot(graph.snapshot());
        return analyzeCallGraph(
                graph.identity(),
                evidenceContext,
                callGraph,
                summariesByMethod == null ? Map.of() : summariesByMethod,
                config);
    }

    /**
     * Composes intra-method error summaries backwards over an explicit {@link CallGraph} from every
     * outbound RPC edge ({@code f_k -> OB}) to every reachable inbound handler ({@code f_0}).
     */
    public static ResilienceReport analyzeCallGraph(
            ContentDigest graphDigest,
            EvidenceContext evidenceContext,
            CallGraph callGraph,
            Map<String, List<MethodErrorSummary>> summariesByMethod,
            AnalysisConfig config) {
        Objects.requireNonNull(graphDigest, "graphDigest");
        Objects.requireNonNull(evidenceContext, "evidenceContext");
        Objects.requireNonNull(callGraph, "callGraph");
        Objects.requireNonNull(config, "config");

        SummaryLookup lookup = SummaryLookup.index(summariesByMethod == null ? Map.of() : summariesByMethod);

        // Build deterministic reverse adjacency for intra-service callers + endpoint ownership edges.
        var reverseCallers = new TreeMap<String, TreeSet<String>>();
        var outboundEdges = new ArrayList<CallEdge>();

        for (CallEdge edge : callGraph.edges()) {
            if (!callGraph.nodes().containsKey(edge.sourceId()) || !callGraph.nodes().containsKey(edge.targetId())) {
                continue;
            }
            if (OUTBOUND_RPC_EDGE_KINDS.contains(edge.edgeKind())) {
                outboundEdges.add(edge);
            } else if (INTRA_SERVICE_CALL_EDGE_KINDS.contains(edge.edgeKind())) {
                CallNode src = callGraph.nodes().get(edge.sourceId());
                CallNode tgt = callGraph.nodes().get(edge.targetId());
                if (("graph.contains".equals(edge.edgeKind()) || "graph.declares".equals(edge.edgeKind()))
                        && isInboundNode(tgt) && !isInboundNode(src)) {
                    // Evo Spring graphs represent Controller --graph.contains--> ENDPOINT.
                    // Expose ENDPOINT as a reverse-upstream inbound root for Controller.
                    reverseCallers.computeIfAbsent(src.nodeId(), k -> new TreeSet<>()).add(tgt.nodeId());
                } else if (!edge.sourceId().equals(edge.targetId())) {
                    reverseCallers.computeIfAbsent(edge.targetId(), k -> new TreeSet<>()).add(edge.sourceId());
                }
            }
        }

        outboundEdges.sort(Comparator.comparing(CallEdge::sourceId)
                .thenComparing(CallEdge::targetId)
                .thenComparing(CallEdge::edgeKind));

        var pathVerdicts = new TreeMap<PathKey, PathVerdict>();
        var finalDedupMap = new LinkedHashMap<FinalXorKey, PathVerdict>();
        var gaps = new TreeSet<CapabilityGapRecord>();
        int[] xorDedupCollapsed = new int[]{0};
        boolean[] branchTruncated = new boolean[]{false};

        for (CallEdge obEdge : outboundEdges) {
            CallNode fkNode = callGraph.nodes().get(obEdge.sourceId());
            CallNode obNode = callGraph.nodes().get(obEdge.targetId());
            if (fkNode == null || obNode == null) {
                continue;
            }

            Optional<MethodErrorSummary> outboundSummaryOpt = lookup.findForCall(fkNode, obNode);
            ErrorState initialState = initialStateFromSummary(outboundSummaryOpt, fkNode, obNode);
            if (initialState.opaque) {
                gaps.add(createOpaqueGap(
                        evidenceContext,
                        graphDigest,
                        fkNode.nodeId() + "->" + obNode.nodeId(),
                        initialState.opaqueReason.orElse(GAP_REASON_HANDLER_OPAQUE)));
            }

            // Bounded backward traversal from f_k to f_0 with XOR permutation dedup.
            var visitedStates = new HashSet<StepDedupKey>();
            var activePath = new ArrayDeque<String>();
            var activeSet = new HashSet<String>();
            activePath.addFirst(fkNode.nodeId());
            activeSet.add(fkNode.nodeId());
            long initialXor = hash64(fkNode.nodeId());
            int[] emittedForOutbound = new int[]{0};

            backwardCompose(
                    fkNode,
                    obNode,
                    initialState,
                    initialXor,
                    0,
                    activePath,
                    activeSet,
                    callGraph,
                    reverseCallers,
                    lookup,
                    config,
                    graphDigest,
                    evidenceContext,
                    visitedStates,
                    finalDedupMap,
                    gaps,
                    xorDedupCollapsed,
                    branchTruncated,
                    emittedForOutbound);
        }

        for (PathVerdict pv : finalDedupMap.values()) {
            pathVerdicts.put(pv.key(), pv);
        }

        // Compute PairSummary per (inboundId, outboundId) preserving MIXED_PATHS honestly.
        var byPair = new TreeMap<String, List<PathVerdict>>();
        for (PathVerdict pv : pathVerdicts.values()) {
            String pairKey = pv.inboundId() + "->" + pv.outboundId();
            byPair.computeIfAbsent(pairKey, k -> new ArrayList<>()).add(pv);
        }

        var pairSummaries = new TreeMap<String, PairSummary>();
        for (var entry : byPair.entrySet()) {
            List<PathVerdict> verdicts = entry.getValue();
            int fc = 0, fo = 0, unk = 0;
            var digests = new ArrayList<String>(verdicts.size());
            for (PathVerdict pv : verdicts) {
                digests.add(pv.key().pathDigest());
                switch (pv.verdict()) {
                    case FAIL_CLOSE -> fc++;
                    case FAIL_OPEN -> fo++;
                    case UNKNOWN -> unk++;
                    case MIXED_PATHS -> {
                        fc++;
                        fo++;
                    }
                }
            }
            ResilienceVerdict aggregate;
            if (fc > 0 && fo > 0) {
                aggregate = ResilienceVerdict.MIXED_PATHS;
            } else if (unk > 0) {
                aggregate = ResilienceVerdict.UNKNOWN;
            } else if (fc > 0) {
                aggregate = ResilienceVerdict.FAIL_CLOSE;
            } else {
                aggregate = ResilienceVerdict.FAIL_OPEN;
            }
            PathVerdict first = verdicts.getFirst();
            pairSummaries.put(entry.getKey(), new PairSummary(
                    first.inboundId(),
                    first.outboundId(),
                    first.inboundService(),
                    first.outboundService(),
                    aggregate,
                    verdicts.size(),
                    fc,
                    fo,
                    unk,
                    digests));
        }

        return new ResilienceReport(
                graphDigest,
                TrustTier.SOURCE_SYNTAX,
                pathVerdicts,
                pairSummaries,
                List.copyOf(gaps),
                xorDedupCollapsed[0],
                branchTruncated[0]);
    }

    private record StepDedupKey(String nodeId, long pathXor, String stateKey) {}

    private record FinalXorKey(String inboundId, String outboundId, long pathXor, ResilienceVerdict verdict) {}

    private record ErrorState(
            PropagationDisposition disposition,
            boolean counterfactualOk,
            Set<String> exceptions,
            List<ExceptionTransformation> transformations,
            boolean opaque,
            Optional<String> opaqueReason
    ) {
        String fingerprint() {
            return disposition.name() + "|" + counterfactualOk + "|" + opaque + "|" + String.join(",", exceptions);
        }

        ResilienceVerdict toVerdict() {
            if (opaque || disposition == PropagationDisposition.UNKNOWN_OPAQUE) {
                return ResilienceVerdict.UNKNOWN;
            }
            if (disposition == PropagationDisposition.SUPPRESSED_FAIL_OPEN) {
                return ResilienceVerdict.FAIL_OPEN;
            }
            return counterfactualOk ? ResilienceVerdict.FAIL_CLOSE : ResilienceVerdict.FAIL_OPEN;
        }
    }

    private static ErrorState initialStateFromSummary(
            Optional<MethodErrorSummary> summaryOpt,
            CallNode fkNode,
            CallNode obNode) {
        if (summaryOpt.isEmpty()) {
            return new ErrorState(
                    PropagationDisposition.UNKNOWN_OPAQUE,
                    true,
                    Set.of(),
                    List.of(),
                    true,
                    Optional.of("MISSING_METHOD_ERROR_SUMMARY:" + fkNode.label() + "->" + obNode.label()));
        }
        MethodErrorSummary s = summaryOpt.get();
        if (s.opaqueHandler() || s.disposition() == PropagationDisposition.UNKNOWN_OPAQUE) {
            return new ErrorState(
                    PropagationDisposition.UNKNOWN_OPAQUE,
                    s.counterfactualOkPathExists(),
                    s.propagatedExceptions(),
                    s.transformations(),
                    true,
                    s.opaqueReason().or(() -> Optional.of(GAP_REASON_HANDLER_OPAQUE)));
        }
        if (s.disposition() == PropagationDisposition.SUPPRESSED_FAIL_OPEN) {
            return new ErrorState(
                    PropagationDisposition.SUPPRESSED_FAIL_OPEN,
                    s.counterfactualOkPathExists(),
                    Set.of(),
                    s.transformations(),
                    false,
                    Optional.empty());
        }
        Set<String> active = s.propagatedExceptions().isEmpty()
                ? (s.baseExceptions().isEmpty() ? Set.of("Error") : s.baseExceptions())
                : s.propagatedExceptions();
        return new ErrorState(
                PropagationDisposition.PROPAGATES_ERROR,
                s.counterfactualOkPathExists(),
                active,
                s.transformations(),
                false,
                Optional.empty());
    }

    private static ErrorState composeBackwardStep(
            ErrorState downstream,
            CallNode caller,
            CallNode callee,
            SummaryLookup lookup) {
        if (downstream.disposition() == PropagationDisposition.SUPPRESSED_FAIL_OPEN) {
            return downstream;
        }

        Optional<MethodErrorSummary> callerSummaryOpt = lookup.findForCall(caller, callee);
        if (callerSummaryOpt.isEmpty()) {
            // Transparent routing/container node or pass-through method without catching summary.
            return downstream;
        }
        MethodErrorSummary callerSummary = callerSummaryOpt.get();

        if (callerSummary.disposition() == PropagationDisposition.SUPPRESSED_FAIL_OPEN
                && !callerSummary.opaqueHandler()) {
            if (catchesOrSuppressesAll(callerSummary, downstream.exceptions())) {
                var mergedTransforms = new ArrayList<ExceptionTransformation>(downstream.transformations());
                mergedTransforms.addAll(callerSummary.transformations());
                return new ErrorState(
                        PropagationDisposition.SUPPRESSED_FAIL_OPEN,
                        downstream.counterfactualOk() && callerSummary.counterfactualOkPathExists(),
                        Set.of(),
                        mergedTransforms,
                        false,
                        Optional.empty());
            }
        }

        if (downstream.opaque() || downstream.disposition() == PropagationDisposition.UNKNOWN_OPAQUE) {
            return downstream;
        }

        if (callerSummary.opaqueHandler() || callerSummary.disposition() == PropagationDisposition.UNKNOWN_OPAQUE) {
            var mergedTransforms = new ArrayList<ExceptionTransformation>(downstream.transformations());
            mergedTransforms.addAll(callerSummary.transformations());
            return new ErrorState(
                    PropagationDisposition.UNKNOWN_OPAQUE,
                    downstream.counterfactualOk() && callerSummary.counterfactualOkPathExists(),
                    downstream.exceptions(),
                    mergedTransforms,
                    true,
                    callerSummary.opaqueReason().or(() -> Optional.of(GAP_REASON_HANDLER_OPAQUE)));
        }

        // PROPAGATES_ERROR: apply T -> F exception transformations if caller wraps exceptions.
        var nextExceptions = new TreeSet<String>(downstream.exceptions());
        var mergedTransforms = new ArrayList<ExceptionTransformation>(downstream.transformations());
        if (!callerSummary.transformations().isEmpty()) {
            for (ExceptionTransformation tr : callerSummary.transformations()) {
                mergedTransforms.add(tr);
                boolean matched = false;
                for (String ex : List.copyOf(nextExceptions)) {
                    if (exceptionMatches(tr.sourceException(), ex)) {
                        nextExceptions.remove(ex);
                        nextExceptions.add(tr.targetException());
                        matched = true;
                    }
                }
                if (!matched && !tr.targetException().isEmpty()) {
                    nextExceptions.add(tr.targetException());
                }
            }
        }
        return new ErrorState(
                PropagationDisposition.PROPAGATES_ERROR,
                downstream.counterfactualOk() && callerSummary.counterfactualOkPathExists(),
                nextExceptions,
                mergedTransforms,
                false,
                Optional.empty());
    }

    private static boolean catchesOrSuppressesAll(MethodErrorSummary callerSummary, Set<String> activeExceptions) {
        if (callerSummary.fallbackMechanism().isPresent()) {
            String fb = callerSummary.fallbackMechanism().get();
            if (fb.startsWith("CIRCUIT_BREAKER_FALLBACK")
                    || fb.startsWith("RETRY_FALLBACK")
                    || fb.startsWith("ANNOTATION_FALLBACK")
                    || fb.startsWith("COMPLETABLE_FUTURE_")
                    || fb.startsWith("WEBCLIENT_ON_ERROR")
                    || fb.startsWith("GO_FALLBACK_BLOCK")
                    || fb.startsWith("GO_ERR_SUPPRESSED")
                    || fb.startsWith("GO_IGNORED_ERROR")
                    || fb.startsWith("GO_ERRGROUP_WAIT_SUPPRESSED")) {
                return true;
            }
        }
        if (activeExceptions.isEmpty() || callerSummary.baseExceptions().isEmpty()) {
            return true;
        }
        for (String active : activeExceptions) {
            boolean caught = false;
            for (String base : callerSummary.baseExceptions()) {
                if (exceptionMatches(base, active)) {
                    caught = true;
                    break;
                }
            }
            if (!caught) {
                return false;
            }
        }
        return true;
    }

    public static boolean exceptionMatches(String catchType, String thrownType) {
        if (catchType == null || thrownType == null) return false;
        String c = simpleTypeName(catchType);
        String t = simpleTypeName(thrownType);
        if (c.equals(t) || c.equals("*") || c.equals("Throwable") || c.equals("error")) {
            return true;
        }
        if (c.equals("Exception")) {
            return !t.equals("Error") && !t.equals("OutOfMemoryError") && !t.equals("StackOverflowError");
        }
        if (c.equals("RuntimeException")) {
            return t.endsWith("RuntimeException")
                    || t.equals("RestClientException")
                    || t.equals("HttpStatusCodeException")
                    || t.equals("ResourceAccessException")
                    || t.equals("WebClientException")
                    || t.equals("WebClientResponseException")
                    || t.equals("WebClientRequestException")
                    || t.equals("FeignException")
                    || t.equals("RetryableException")
                    || t.equals("StatusRuntimeException")
                    || t.equals("ResponseStatusException")
                    || t.equals("IllegalStateException")
                    || t.equals("IllegalArgumentException")
                    || t.equals("NullPointerException")
                    || t.equals("CompletionException");
        }
        if (c.equals("IOException")) {
            return t.equals("IOException") || t.endsWith("IOException")
                    || t.equals("HttpConnectTimeoutException") || t.equals("SocketTimeoutException")
                    || t.equals("ConnectException") || t.equals("RetryableException");
        }
        if (c.equals("RestClientException")) {
            return t.equals("HttpStatusCodeException") || t.equals("ResourceAccessException")
                    || t.equals("HttpClientErrorException") || t.equals("HttpServerErrorException");
        }
        if (c.equals("WebClientException")) {
            return t.equals("WebClientResponseException") || t.equals("WebClientRequestException");
        }
        if (c.equals("FeignException")) {
            return t.equals("RetryableException") || t.startsWith("FeignException");
        }
        return false;
    }

    private static String simpleTypeName(String raw) {
        String s = raw.strip();
        int dot = s.lastIndexOf('.');
        return dot >= 0 && dot + 1 < s.length() ? s.substring(dot + 1) : s;
    }

    private static void backwardCompose(
            CallNode current,
            CallNode obNode,
            ErrorState state,
            long pathXor,
            int depth,
            Deque<String> activePath,
            Set<String> activeSet,
            CallGraph callGraph,
            Map<String, TreeSet<String>> reverseCallers,
            SummaryLookup lookup,
            AnalysisConfig config,
            ContentDigest graphDigest,
            EvidenceContext evidenceContext,
            Set<StepDedupKey> visitedStates,
            Map<FinalXorKey, PathVerdict> finalDedupMap,
            Set<CapabilityGapRecord> gaps,
            int[] xorDedupCollapsed,
            boolean[] branchTruncated,
            int[] emittedForOutbound) {

        if (emittedForOutbound[0] >= config.maxPathsPerOutbound()) {
            branchTruncated[0] = true;
            return;
        }

        StepDedupKey stepKey = new StepDedupKey(current.nodeId(), pathXor, state.fingerprint());
        if (!visitedStates.add(stepKey)) {
            xorDedupCollapsed[0]++;
            return;
        }

        if (isInboundNode(current)) {
            emitPathVerdict(
                    current, obNode, state, pathXor, activePath, callGraph,
                    finalDedupMap, xorDedupCollapsed, emittedForOutbound);
            // If this inbound node itself has no further inbound wrapper, stop here.
            return;
        }

        if (depth >= config.maxDepth()) {
            branchTruncated[0] = true;
            return;
        }

        TreeSet<String> callers = reverseCallers.get(current.nodeId());
        if (callers == null || callers.isEmpty()) {
            return;
        }

        int explored = 0;
        for (String callerId : callers) {
            if (explored >= config.maxBranchFactor()) {
                branchTruncated[0] = true;
                break;
            }
            if (activeSet.contains(callerId)) {
                continue; // Avoid cycles along the active stack
            }
            CallNode callerNode = callGraph.nodes().get(callerId);
            if (callerNode == null) {
                continue;
            }
            explored++;

            ErrorState nextState = composeBackwardStep(state, callerNode, current, lookup);
            if (nextState.opaque() && !state.opaque()) {
                gaps.add(createOpaqueGap(
                        evidenceContext,
                        graphDigest,
                        callerNode.nodeId() + "->" + current.nodeId(),
                        nextState.opaqueReason().orElse(GAP_REASON_HANDLER_OPAQUE)));
            }

            activePath.addFirst(callerId);
            activeSet.add(callerId);
            backwardCompose(
                    callerNode,
                    obNode,
                    nextState,
                    pathXor ^ hash64(callerId),
                    depth + 1,
                    activePath,
                    activeSet,
                    callGraph,
                    reverseCallers,
                    lookup,
                    config,
                    graphDigest,
                    evidenceContext,
                    visitedStates,
                    finalDedupMap,
                    gaps,
                    xorDedupCollapsed,
                    branchTruncated,
                    emittedForOutbound);
            activeSet.remove(callerId);
            activePath.removeFirst();
        }
    }

    private static void emitPathVerdict(
            CallNode inbound,
            CallNode outbound,
            ErrorState state,
            long pathXor,
            Deque<String> activePath,
            CallGraph callGraph,
            Map<FinalXorKey, PathVerdict> finalDedupMap,
            int[] xorDedupCollapsed,
            int[] emittedForOutbound) {
        ResilienceVerdict verdict = state.toVerdict();
        FinalXorKey xorKey = new FinalXorKey(inbound.nodeId(), outbound.nodeId(), pathXor, verdict);
        PathVerdict existing = finalDedupMap.get(xorKey);
        if (existing != null) {
            xorDedupCollapsed[0]++;
            finalDedupMap.put(xorKey, new PathVerdict(
                    existing.key(),
                    existing.inboundId(),
                    existing.inboundLabel(),
                    existing.inboundService(),
                    existing.outboundId(),
                    existing.outboundLabel(),
                    existing.outboundService(),
                    existing.verdict(),
                    existing.trustTier(),
                    existing.pathXorFingerprint(),
                    existing.deduplicatedPermutations() + 1,
                    existing.counterfactualD13Verified(),
                    existing.propagatedExceptions(),
                    existing.transformations(),
                    existing.flamegraph(),
                    existing.gapReason()));
            return;
        }

        emittedForOutbound[0]++;
        var orderedIds = new ArrayList<String>(activePath.size() + 1);
        var frames = new ArrayList<CallFrame>(activePath.size() + 1);
        for (String id : activePath) {
            orderedIds.add(id);
            CallNode n = callGraph.nodes().get(id);
            if (n != null) {
                frames.add(n.toFrame());
            }
        }
        orderedIds.add(outbound.nodeId());
        frames.add(outbound.toFrame());

        String pathDigest = ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
                inbound.nodeId(),
                outbound.nodeId(),
                orderedIds,
                verdict.name(),
                Long.toHexString(pathXor)))).value();

        PathKey key = new PathKey(inbound.nodeId(), outbound.nodeId(), pathDigest);
        Optional<String> gapReason = state.opaque()
                ? Optional.of(GAP_REASON_HANDLER_OPAQUE)
                : Optional.empty();

        PathVerdict pv = new PathVerdict(
                key,
                inbound.nodeId(),
                inbound.label(),
                inbound.serviceName(),
                outbound.nodeId(),
                outbound.label(),
                outbound.serviceName(),
                verdict,
                TrustTier.SOURCE_SYNTAX,
                pathXor,
                0,
                state.counterfactualOk(),
                state.exceptions(),
                state.transformations(),
                frames,
                gapReason);
        finalDedupMap.put(xorKey, pv);
    }

    private static boolean isInboundNode(CallNode node) {
        return node.inboundEndpoint()
                || INBOUND_ENDPOINT_NODE_KINDS.contains(node.kind().toUpperCase(Locale.ROOT));
    }

    /** Extracts a read-only {@link CallGraph} projection from an immutable {@link KnowledgeGraph}. */
    public static CallGraph extractCallGraph(KnowledgeGraph graph) {
        Objects.requireNonNull(graph, "graph");
        var evidenceCache = new HashMap<ContentDigest, Map<String, Object>>();
        var documents = new HashMap<String, String>();

        for (var node : graph.nodes()) {
            if (!"SOURCE_DOCUMENT".equals(node.kind())) continue;
            for (var ref : node.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null || !"source.document".equals(ev.family())) continue;
                var payload = parseEvidence(ev, evidenceCache);
                String path = optString(payload, "path");
                if (!path.isEmpty()) {
                    documents.putIfAbsent(node.identity(), path);
                }
            }
        }

        var nodesById = new TreeMap<String, CallNode>();
        for (var node : graph.nodes()) {
            if (isNonCallGraphNode(node.kind())) continue;
            String label = "";
            String serviceName = "";
            String sourcePath = "";
            int startLine = 0;
            int endLine = 0;
            boolean inbound = INBOUND_ENDPOINT_NODE_KINDS.contains(node.kind().toUpperCase(Locale.ROOT));

            for (var ref : node.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null) continue;
                var payload = parseEvidence(ev, evidenceCache);
                String fam = ev.family();
                if ("spring.endpoint-declaration".equals(fam)
                        || "jaxrs.endpoint-declaration".equals(fam)
                        || "servlet.endpoint-declaration".equals(fam)) {
                    inbound = true;
                    if (payload.get("route") instanceof Map<?, ?> rObj) {
                        var route = GraphJson.object(rObj);
                        String rp = optString(route, "path");
                        if (!rp.isEmpty() && label.isEmpty()) label = rp;
                        if (route.get("span") instanceof Map<?, ?> spObj) {
                            var sp = GraphJson.object(spObj);
                            String docId = optString(sp, "document");
                            if (!docId.isEmpty() && sourcePath.isEmpty()) {
                                sourcePath = documents.getOrDefault(docId, "");
                            }
                            if (startLine == 0) startLine = optInt(sp, "startLine", 0);
                            if (endLine == 0) endLine = optInt(sp, "endLine", startLine);
                        }
                    }
                } else if ("java.declaration".equals(fam)) {
                    if (payload.get("entity") instanceof Map<?, ?> eObj) {
                        var ent = GraphJson.object(eObj);
                        String cn = optString(ent, "canonicalName");
                        if (!cn.isEmpty() && label.isEmpty()) label = cn;
                        String scope = optString(ent, "stableScope");
                        if (!scope.isEmpty() && serviceName.isEmpty()) serviceName = extractServiceName(scope);
                        if (ent.get("declaration") instanceof Map<?, ?> dObj) {
                            var decl = GraphJson.object(dObj);
                            String docId = optString(decl, "document");
                            if (!docId.isEmpty() && sourcePath.isEmpty()) {
                                sourcePath = documents.getOrDefault(docId, "");
                            }
                            if (startLine == 0) startLine = optInt(decl, "startLine", 0);
                            if (endLine == 0) endLine = optInt(decl, "endLine", startLine);
                        }
                    }
                    String sp = optString(payload, "spelling");
                    if (!sp.isEmpty() && label.isEmpty()) label = sp;
                } else if (fam.startsWith("polyglot.")) {
                    var fields = payload.get("fields") instanceof Map<?, ?> f ? GraphJson.object(f) : payload;
                    String p = decodePath(fields.get("path"));
                    if (!p.isEmpty() && sourcePath.isEmpty()) sourcePath = p;
                    if (fields.get("fact") instanceof Map<?, ?> factObj) {
                        var fact = GraphJson.object(factObj);
                        String fn = optString(fact, "name");
                        if (!fn.isEmpty() && label.isEmpty()) label = fn;
                        if (fact.get("span") instanceof Map<?, ?> spObj) {
                            var sp = GraphJson.object(spObj);
                            if (startLine == 0) startLine = optInt(sp, "startLine", 0);
                            if (endLine == 0) endLine = optInt(sp, "endLine", startLine);
                        }
                    }
                }
                if (label.isEmpty()) {
                    label = firstNonEmpty(optString(payload, "qualifiedName"), optString(payload, "label"),
                            optString(payload, "name"), optString(payload, "service"));
                }
                if (serviceName.isEmpty()) {
                    serviceName = firstNonEmpty(optString(payload, "service"), optString(payload, "serviceName"),
                            optString(payload, "module"));
                }
                if (sourcePath.isEmpty()) {
                    sourcePath = firstNonEmpty(optString(payload, "sourcePath"), optString(payload, "path"));
                }
                if (startLine == 0) {
                    startLine = optInt(payload, "startLine", optInt(payload, "line", 0));
                }
                if (endLine == 0) {
                    endLine = optInt(payload, "endLine", startLine);
                }
            }
            if (label.isEmpty()) {
                label = node.identity();
            }
            if (serviceName.isEmpty() && !sourcePath.isEmpty()) {
                serviceName = inferServiceFromPath(sourcePath);
            }
            CallNode candidate = new CallNode(
                    node.identity(), label, node.kind(), serviceName, sourcePath, startLine, endLine, inbound);
            CallNode existing = nodesById.get(node.identity());
            if (existing == null || (!existing.inboundEndpoint() && candidate.inboundEndpoint())
                    || (existing.sourcePath().isEmpty() && !candidate.sourcePath().isEmpty())) {
                nodesById.put(node.identity(), candidate);
            }
        }

        var edges = new ArrayList<CallEdge>();
        for (var edge : graph.edges()) {
            if (!OUTBOUND_RPC_EDGE_KINDS.contains(edge.kind()) && !INTRA_SERVICE_CALL_EDGE_KINDS.contains(edge.kind())) {
                continue;
            }
            for (String targetId : edge.target().identities()) {
                if (nodesById.containsKey(edge.source()) && nodesById.containsKey(targetId)) {
                    edges.add(new CallEdge(edge.source(), targetId, edge.kind()));
                }
            }
        }

        return new CallGraph(nodesById, edges);
    }

    /** Creates a typed {@link CapabilityGapRecord} for an opaque fail-close/async handler ({@code I3}). */
    public static CapabilityGapRecord createOpaqueGap(
            EvidenceContext context,
            ContentDigest graphDigest,
            String subjectId,
            String detail) {
        return createGap(
                context,
                graphDigest,
                GAP_REASON_HANDLER_OPAQUE,
                subjectId,
                detail,
                AffectedOutput.Kind.ANALYSIS_COVERAGE,
                MECHANISM_CATEGORY);
    }

    /** Creates a typed {@link CapabilityGapRecord} in the fail-close resilience gap catalog. */
    public static CapabilityGapRecord createGap(
            EvidenceContext context,
            ContentDigest graphDigest,
            String reasonCode,
            String subjectId,
            String detail,
            AffectedOutput.Kind affectedKind,
            String affectedIdentifier) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(graphDigest, "graphDigest");
        String safeReason = Objects.requireNonNull(reasonCode, "reasonCode").strip();
        String safeSubject = (subjectId == null || subjectId.isBlank()) ? "unknown-subject" : subjectId.strip();
        String safeDetail = (detail == null || detail.isBlank()) ? safeReason : detail.strip();

        var observation = ProviderObservationReference.create(
                PROVIDER,
                "polyglot.fail-close-observation",
                graphDigest,
                ContentDigest.sha256Utf8(CanonicalJson.write(List.of(safeReason, safeSubject, safeDetail))));
        var subject = EvidenceSubject.observation(observation.identity());
        var requirement = new EvidenceRequirement(
                EvidenceRequirement.Kind.ALTERNATE_FRONTEND,
                "polyglot.fail-close-handler-semantics",
                List.of(subject),
                EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of("Provide resolvable source or bytecode handler body for " + safeSubject + " (" + safeDetail + ")."));
        return CapabilityGapRecord.create(
                GAP_CATALOG,
                context,
                PROVIDER,
                MECHANISM_CATEGORY,
                safeReason,
                subject,
                List.of(),
                List.of(observation),
                List.of(requirement),
                List.of(),
                List.of(new AffectedOutput(affectedKind, affectedIdentifier)),
                List.of(),
                List.of(),
                List.of("Passive SOURCE_SYNTAX resilience analysis cannot verify opaque handler or missing metadata: " + safeDetail));
    }

    public static Map<String, Object> gapToView(CapabilityGapRecord gap) {
        var m = new TreeMap<String, Object>();
        m.put("gapIdentity", gap.gapIdentity().value());
        m.put("limitations", gap.limitations());
        m.put("mechanismCategory", gap.mechanismCategory());
        m.put("reasonCode", gap.reasonCode());
        m.put("subject", gap.subject().identity());
        return Collections.unmodifiableMap(m);
    }

    private static final class SummaryLookup {
        private final Map<String, List<MethodErrorSummary>> byKey;

        private SummaryLookup(Map<String, List<MethodErrorSummary>> byKey) {
            this.byKey = byKey;
        }

        static SummaryLookup index(Map<String, List<MethodErrorSummary>> raw) {
            var map = new HashMap<String, List<MethodErrorSummary>>();
            for (var entry : raw.entrySet()) {
                String key = entry.getKey();
                if (key == null || entry.getValue() == null) continue;
                for (MethodErrorSummary s : entry.getValue()) {
                    if (s == null) continue;
                    add(map, key, s);
                    add(map, s.methodId(), s);
                    add(map, s.callsiteId(), s);
                    if (!s.sourcePath().isEmpty()) {
                        add(map, s.sourcePath() + ":" + s.line(), s);
                    }
                }
            }
            return new SummaryLookup(map);
        }

        private static void add(Map<String, List<MethodErrorSummary>> map, String key, MethodErrorSummary s) {
            if (key == null || key.isBlank()) return;
            var list = map.computeIfAbsent(key, k -> new ArrayList<>());
            if (!list.contains(s)) {
                list.add(s);
            }
        }

        Optional<MethodErrorSummary> findForCall(CallNode caller, CallNode target) {
            var candidates = new ArrayList<MethodErrorSummary>();
            collect(candidates, caller.nodeId());
            collect(candidates, caller.label());
            if (!caller.sourcePath().isEmpty() && caller.startLine() > 0) {
                collect(candidates, caller.sourcePath() + ":" + caller.startLine());
            }
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            if (candidates.size() == 1) {
                return Optional.of(candidates.getFirst());
            }
            // Deterministic candidate order (I2) before prioritized specificity matching
            candidates.sort(Comparator.comparing(MethodErrorSummary::callsiteId)
                    .thenComparing(MethodErrorSummary::targetCall));
            String tgtLabel = target.label().toLowerCase(Locale.ROOT);
            String tgtSvc = target.serviceName().toLowerCase(Locale.ROOT);
            String tgtSimple = simpleSymbol(target.label()).toLowerCase(Locale.ROOT);

            // Pass 1: exact target call or service-name match (prevents e.g. primary.Fetch colliding with fallback.Fetch)
            for (MethodErrorSummary s : candidates) {
                String tc = s.targetCall().toLowerCase(Locale.ROOT);
                if (!tc.isEmpty() && (tc.equals(tgtLabel) || (!tgtSvc.isEmpty() && tc.equals(tgtSvc)))) {
                    return Optional.of(s);
                }
            }
            // Pass 2: qualified substring or service-name match
            for (MethodErrorSummary s : candidates) {
                String tc = s.targetCall().toLowerCase(Locale.ROOT);
                if (!tc.isEmpty() && (tc.contains(tgtLabel) || tgtLabel.contains(tc)
                        || (!tgtSvc.isEmpty() && tc.contains(tgtSvc)))) {
                    return Optional.of(s);
                }
            }
            // Pass 3: simple method/symbol match
            if (!tgtSimple.isEmpty()) {
                for (MethodErrorSummary s : candidates) {
                    String tc = s.targetCall().toLowerCase(Locale.ROOT);
                    if (!tc.isEmpty() && tc.contains(tgtSimple)) {
                        return Optional.of(s);
                    }
                }
            }
            // Pass 4: deterministic fallback sorted by callsiteId
            return Optional.of(candidates.getFirst());
        }

        private void collect(List<MethodErrorSummary> out, String key) {
            List<MethodErrorSummary> found = byKey.get(key);
            if (found != null) {
                for (MethodErrorSummary s : found) {
                    if (!out.contains(s)) out.add(s);
                }
            }
        }

        private static String simpleSymbol(String raw) {
            int hash = raw.lastIndexOf('#');
            String base = hash >= 0 && hash + 1 < raw.length() ? raw.substring(hash + 1) : raw;
            int paren = base.indexOf('(');
            if (paren > 0) base = base.substring(0, paren);
            int dot = base.lastIndexOf('.');
            return dot >= 0 && dot + 1 < base.length() ? base.substring(dot + 1) : base;
        }
    }

    private static boolean isNonCallGraphNode(String kind) {
        return Set.of(
                "CAPABILITY_GAP", "HISTORICAL_CAPABILITY_GAP", "ACQUISITION_ATTEMPT",
                "PROVIDER_CONFLICT", "TRUTH_REGION", "REGION_FORMULA", "CONFIGURATION_WORLD",
                "CONFIGURATION_WITNESS", "WITNESS_REPLAY", "CONDITIONAL_FACT",
                "CONFIGURATION_CONDITION", "CONDITION_EXPRESSION", "SOURCE_ROOT",
                "SOURCE_DOCUMENT", "PACKAGE", "REPOSITORY", "OCCURRENCE",
                "REGISTRATION_EVENT", "REGISTRATION_TRANSITION", "ANNOTATION_USE",
                "PARAMETER", "TYPE_PARAMETER"
        ).contains(kind);
    }

    private static Map<String, Object> parseEvidence(
            GraphEvidence ev,
            Map<ContentDigest, Map<String, Object>> cache) {
        var cached = cache.get(ev.identity());
        if (cached != null) return cached;
        try {
            var parsed = GraphJson.object(GraphJson.read(
                    ev.canonicalJson(), new GraphJson.Limits(4_000_000, 128, 500_000)));
            cache.put(ev.identity(), parsed);
            return parsed;
        } catch (IllegalArgumentException malformed) {
            return Map.of();
        }
    }

    private static String decodePath(Object rawValue) {
        if (!(rawValue instanceof String text) || text.isBlank()) return "";
        try {
            var decoded = new com.evolution.analysis.acquisition.streaming.LogicalPath(text).utf8();
            if (decoded.isPresent()) return decoded.get();
        } catch (IllegalArgumentException ignored) {
            // Fall through
        }
        return text;
    }

    private static String extractServiceName(String scope) {
        if (scope == null || scope.isBlank()) return "";
        int slash = scope.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < scope.length()) return scope.substring(slash + 1);
        int colon = scope.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < scope.length()) return scope.substring(colon + 1);
        return scope;
    }

    private static String inferServiceFromPath(String path) {
        String norm = path.replace('\\', '/');
        String[] parts = norm.split("/");
        if (parts.length >= 2 && Set.of("src", "services", "apps", "cmd", "packages", "modules")
                .contains(parts[0].toLowerCase(Locale.ROOT))) {
            return parts[1];
        }
        return parts.length >= 2 ? parts[0] : "";
    }

    private static String optString(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s ? s : "";
    }

    private static int optInt(Map<String, Object> map, String key, int fallback) {
        return map.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
