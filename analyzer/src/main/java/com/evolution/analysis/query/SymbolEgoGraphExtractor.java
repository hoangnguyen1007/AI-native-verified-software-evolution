package com.evolution.analysis.query;

import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Deterministic {@code k=1} flattened Symbol Ego-Graph extractor (RepoGraph ICLR '25) with
 * 2-tier stdlib/external noise filtering and mandatory {@code k >= 2} signature-compression
 * guardrail (RepoGraph Table 4).
 *
 * <p>Core invariants enforced:
 * <ul>
 *   <li><b>I2 (Determinism):</b> Every symbol match, neighbor ordering, edge list, rendered text,
 *       and {@link ContentDigest} is strictly deterministic.</li>
 *   <li><b>I4 (Trust Preservation):</b> Every node and edge retains its explicit {@link TrustTier};
 *       Spring/framework wiring edges ({@code INJECTS}, {@code ADVISES_METHOD}, etc.) are preserved
 *       even when filtering generic stdlib noise.</li>
 *   <li><b>I5 (Zero Benchmark Leakage):</b> Tier-1 filtering uses strictly generic language standard
 *       library prefixes only.</li>
 *   <li><b>RepoGraph Table 4 Guardrail:</b> Default {@code k=1} uses {@link ExtractionMode#FLATTENED_1HOP}
 *       capped at {@value #HARD_MAX_TOKENS} tokens. Any request with {@code k >= 2} NEVER emits raw
 *       2-hop flattened bodies and automatically switches to
 *       {@link ExtractionMode#SIGNATURE_COMPRESSED_2HOP} ({@code Signature(n)} summaries, capped
 *       at {@value #HARD_MAX_TOKENS} tokens).</li>
 * </ul>
 */
public final class SymbolEgoGraphExtractor {
    public static final String VERSION = "symbol-ego-graph:v1";
    public static final int DEFAULT_HOPS = 1;
    public static final int DEFAULT_MAX_TOKENS = 2_500;
    public static final int HARD_MAX_TOKENS = 2_500;
    public static final String TABLE4_GUARDRAIL_NOTE =
            "RepoGraph Table 4 guardrail: k>=2 automatically switched to SIGNATURE_COMPRESSED_2HOP "
                    + "(<=2500 tokens); raw 2-hop body flattening disabled.";

    private static final GraphJson.Limits SMALL_EVIDENCE = new GraphJson.Limits(131_072, 32, 8_192);
    private static final GraphJson.Limits SOURCE_BYTES_LIMITS = new GraphJson.Limits(4 * 1024 * 1024, 32, 32_768);
    private static final int MAX_CENTER_SNIPPET_LINES = 24;
    private static final int MAX_NEIGHBOR_SNIPPET_LINES = 10;

    /** Generic language standard-library prefixes excluded by Tier-1 filter (I5: zero benchmark names). */
    private static final List<String> STDLIB_PREFIXES = List.of(
            // Java / JVM standard libraries
            "java.lang.", "java.util.", "java.io.", "java.nio.", "java.net.", "java.math.",
            "java.time.", "java.text.", "java.sql.", "java.security.", "java.beans.",
            "java.applet.", "java.awt.", "javax.lang.", "jakarta.lang.",
            "sun.", "com.sun.", "jdk.", "kotlin.", "scala.",
            // Generic logging facades
            "org.slf4j.", "org.apache.logging.", "java.util.logging.",
            // Python standard library / builtins
            "builtins.", "typing.", "collections.", "itertools.", "functools.",
            "os.", "sys.", "re.", "json.", "math.", "datetime.", "pathlib.",
            "logging.", "unittest.", "abc.", "dataclasses.", "enum.",
            // JS/TS / Node / Rust / C# / Go stdlib
            "node:", "std::", "core::", "alloc::", "System.",
            "fmt.", "strings.", "errors.", "context.", "bytes.");

    private static final Set<String> BARE_STDLIB_SYMBOLS = Set.of(
            "Object", "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
            "Void", "Number", "Throwable", "Exception", "RuntimeException", "Error",
            "List", "Map", "Set", "Collection", "Iterable", "Optional", "Stream", "Comparable", "Serializable",
            "print", "len", "str", "int", "float", "bool", "list", "dict", "set", "tuple", "range",
            "enumerate", "zip", "map", "filter", "sorted", "sum", "min", "max", "abs",
            "isinstance", "issubclass", "hasattr", "setattr", "getattr", "type", "object", "super",
            "ValueError", "TypeError", "KeyError", "NotImplementedError");

    /** Generic framework wiring prefixes preserved through Tier-2 external filtering. */
    private static final List<String> FRAMEWORK_WIRING_PREFIXES = List.of(
            "org.springframework.",
            "jakarta.inject.",
            "javax.inject.",
            "jakarta.annotation.",
            "javax.annotation.",
            "jakarta.transaction.",
            "javax.transaction.",
            "jakarta.persistence.",
            "javax.persistence.",
            "org.aspectj.");

    private static final Set<String> SYMBOL_NODE_KINDS = Set.of(
            "TYPE", "CLASS", "INTERFACE", "RECORD", "ENUM", "ANNOTATION",
            "METHOD", "FIELD", "CONSTRUCTOR",
            "BEAN_DEFINITION_CANDIDATE", "BEAN_PRODUCER", "ENDPOINT", "INJECTION_POINT",
            "ASPECT", "ADVICE",
            "POLYGLOT_SYMBOL", "POLYGLOT_TYPE", "POLYGLOT_FUNCTION", "POLYGLOT_CLASS",
            "SOURCE_DECLARATION", "SERVICE_ENDPOINT", "FUNCTION");

    private static final Set<String> CONTAINMENT_EDGE_KINDS = Set.of(
            "graph.contains", "graph.declares", "java.declares", "CONTAINS", "syntax.declaration");

    private static final Set<String> CALL_EDGE_KINDS = Set.of(
            "java.calls", "CALLS", "CALLS_SYMBOL", "syntax.calls", "syntax.calls-declaration",
            "CALLS_SERVICE", "INVOKES_RPC");

    private static final Set<String> INJECT_EDGE_KINDS = Set.of(
            "spring.selected-binding", "spring.injection-candidate", "spring.injects",
            "spring.declares-injection", "INJECTS", "syntax.injects");

    private static final Set<String> IMPLEMENTS_EDGE_KINDS = Set.of(
            "java.implements", "IMPLEMENTS", "syntax.implements");

    private static final Set<String> EXTENDS_EDGE_KINDS = Set.of(
            "java.extends", "EXTENDS", "syntax.extends", "syntax.inherits-declaration", "INHERITS");

    private static final Set<String> ADVISES_EDGE_KINDS = Set.of(
            "ADVISES_METHOD", "spring.advises-method", "PROXIED_BY_ASPECT", "spring.proxied-by-aspect");

    private static final Set<String> REF_EDGE_KINDS = Set.of(
            "java.references", "java.field-type", "java.parameter-type", "java.return-type",
            "graph.depends-on", "syntax.imports-declaration", "DEF", "REF");

    private static final Set<String> FRAMEWORK_PRESERVED_EDGE_KINDS = Set.of(
            "INJECTS", "ADVISES_METHOD");

    public enum ExtractionMode {
        FLATTENED_1HOP,
        SIGNATURE_COMPRESSED_2HOP
    }

    public record Request(
            Optional<String> context,
            Optional<String> symbolId,
            Optional<String> searchTerm,
            int hops,
            int maxTokens,
            boolean includeStdlib) {

        public Request {
            context = context == null ? Optional.empty() : context.filter(s -> !s.isBlank()).map(String::strip);
            symbolId = symbolId == null ? Optional.empty() : symbolId.filter(s -> !s.isBlank()).map(String::strip);
            searchTerm = searchTerm == null ? Optional.empty() : searchTerm.filter(s -> !s.isBlank()).map(String::strip);
            hops = hops <= 0 ? DEFAULT_HOPS : hops;
            maxTokens = maxTokens <= 0 ? DEFAULT_MAX_TOKENS : Math.max(100, Math.min(HARD_MAX_TOKENS, maxTokens));
        }

        public static Request bySymbolId(String symbolId) {
            return new Request(Optional.empty(), Optional.ofNullable(symbolId), Optional.empty(),
                    DEFAULT_HOPS, DEFAULT_MAX_TOKENS, false);
        }

        public static Request bySearchTerm(String searchTerm) {
            return new Request(Optional.empty(), Optional.empty(), Optional.ofNullable(searchTerm),
                    DEFAULT_HOPS, DEFAULT_MAX_TOKENS, false);
        }

        public static Request of(
                Optional<String> context,
                Optional<String> symbolId,
                Optional<String> searchTerm,
                int hops,
                int maxTokens) {
            return new Request(context, symbolId, searchTerm, hops, maxTokens, false);
        }
    }

    public record EgoNode(
            String id,
            String fqn,
            String kind,
            String path,
            int startLine,
            int endLine,
            int hopDistance,
            String role,
            TrustTier trustTier,
            String signature,
            String snippet,
            boolean compressedSignatureOnly) implements Comparable<EgoNode> {

        public EgoNode {
            id = Objects.requireNonNull(id, "id");
            fqn = fqn == null || fqn.isBlank() ? id : fqn;
            kind = kind == null ? "SYMBOL" : kind;
            path = path == null ? "" : path;
            startLine = Math.max(0, startLine);
            endLine = Math.max(startLine, endLine);
            hopDistance = Math.max(0, hopDistance);
            role = role == null ? "REF" : role;
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
            signature = signature == null ? "" : signature;
            snippet = compressedSignatureOnly ? "" : (snippet == null ? "" : snippet);
        }

        @Override
        public int compareTo(EgoNode other) {
            int cmp = Integer.compare(this.hopDistance, other.hopDistance);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(rolePriority(this.role), rolePriority(other.role));
            if (cmp != 0) return cmp;
            cmp = this.fqn.compareTo(other.fqn);
            if (cmp != 0) return cmp;
            return this.id.compareTo(other.id);
        }

        public Map<String, Object> toMap() {
            var map = new TreeMap<String, Object>();
            map.put("compressedSignatureOnly", compressedSignatureOnly);
            map.put("endLine", endLine);
            map.put("fqn", fqn);
            map.put("hopDistance", hopDistance);
            map.put("id", id);
            map.put("kind", kind);
            map.put("path", path);
            map.put("role", role);
            map.put("signature", signature);
            map.put("snippet", snippet);
            map.put("startLine", startLine);
            map.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(map);
        }
    }

    public record EgoEdge(
            String kind,
            String normalizedKind,
            String sourceId,
            String sourceFqn,
            String targetId,
            String targetFqn,
            int hopDistance,
            TrustTier trustTier) implements Comparable<EgoEdge> {

        public EgoEdge {
            kind = Objects.requireNonNull(kind, "kind");
            normalizedKind = Objects.requireNonNull(normalizedKind, "normalizedKind");
            sourceId = Objects.requireNonNull(sourceId, "sourceId");
            sourceFqn = sourceFqn == null || sourceFqn.isBlank() ? sourceId : sourceFqn;
            targetId = Objects.requireNonNull(targetId, "targetId");
            targetFqn = targetFqn == null || targetFqn.isBlank() ? targetId : targetFqn;
            hopDistance = Math.max(1, hopDistance);
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
        }

        @Override
        public int compareTo(EgoEdge other) {
            int cmp = Integer.compare(this.hopDistance, other.hopDistance);
            if (cmp != 0) return cmp;
            cmp = this.normalizedKind.compareTo(other.normalizedKind);
            if (cmp != 0) return cmp;
            cmp = this.sourceFqn.compareTo(other.sourceFqn);
            if (cmp != 0) return cmp;
            cmp = this.targetFqn.compareTo(other.targetFqn);
            if (cmp != 0) return cmp;
            cmp = this.kind.compareTo(other.kind);
            if (cmp != 0) return cmp;
            cmp = this.sourceId.compareTo(other.sourceId);
            if (cmp != 0) return cmp;
            return this.targetId.compareTo(other.targetId);
        }

        public Map<String, Object> toMap() {
            var map = new TreeMap<String, Object>();
            map.put("hopDistance", hopDistance);
            map.put("kind", kind);
            map.put("normalizedKind", normalizedKind);
            map.put("sourceFqn", sourceFqn);
            map.put("sourceId", sourceId);
            map.put("targetFqn", targetFqn);
            map.put("targetId", targetId);
            map.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(map);
        }
    }

    public record Result(
            String version,
            ContentDigest graphIdentity,
            String context,
            boolean found,
            ExtractionMode mode,
            int requestedHops,
            int effectiveHops,
            boolean guardrailApplied,
            String guardrailNote,
            Optional<EgoNode> center,
            List<EgoNode> nodes,
            List<EgoEdge> edges,
            int filteredNoiseNodes,
            int estimatedTokens,
            int tokenBudget,
            boolean truncated,
            String renderedText,
            ContentDigest canonicalDigest) {

        public Result {
            version = Objects.requireNonNull(version, "version");
            graphIdentity = Objects.requireNonNull(graphIdentity, "graphIdentity");
            context = context == null ? "" : context;
            mode = Objects.requireNonNull(mode, "mode");
            guardrailNote = guardrailNote == null ? "" : guardrailNote;
            center = center == null ? Optional.empty() : center;
            nodes = List.copyOf(nodes == null ? List.of() : nodes);
            edges = List.copyOf(edges == null ? List.of() : edges);
            renderedText = renderedText == null ? "" : renderedText;
            canonicalDigest = Objects.requireNonNull(canonicalDigest, "canonicalDigest");
        }

        public Map<String, Object> toMap() {
            var map = new TreeMap<String, Object>();
            map.put("canonicalDigest", canonicalDigest.value());
            map.put("center", center.map(EgoNode::toMap).orElse(null));
            map.put("context", context);
            map.put("edges", edges.stream().map(EgoEdge::toMap).toList());
            map.put("effectiveHops", effectiveHops);
            map.put("estimatedTokens", estimatedTokens);
            map.put("filteredNoiseNodes", filteredNoiseNodes);
            map.put("found", found);
            map.put("graphIdentity", graphIdentity.value());
            map.put("guardrailApplied", guardrailApplied);
            map.put("guardrailNote", guardrailNote);
            map.put("mode", mode.name());
            map.put("nodes", nodes.stream().map(EgoNode::toMap).toList());
            map.put("renderedText", renderedText);
            map.put("requestedHops", requestedHops);
            map.put("tokenBudget", tokenBudget);
            map.put("truncated", truncated);
            map.put("version", version);
            return Collections.unmodifiableMap(map);
        }
    }

    private record RawNodeMeta(
            String id,
            String fqn,
            String kind,
            String path,
            int startLine,
            int endLine,
            String origin,
            TrustTier trustTier,
            String declaredSignature) {}

    private record RawEdge(
            String kind,
            String normalizedKind,
            String sourceId,
            String targetId,
            TrustTier trustTier) {}

    public SymbolEgoGraphExtractor() {}

    public Result extract(KnowledgeGraph graph, Request req) {
        return extract(graph, req, () -> StopReason.NONE);
    }

    public Result extract(KnowledgeGraph graph, Request req, Supplier<StopReason> stop) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(req, "req");
        Supplier<StopReason> guard = stop == null ? () -> StopReason.NONE : stop;

        // Mandatory RepoGraph Table 4 guardrail: k >= 2 ALWAYS switches to SIGNATURE_COMPRESSED_2HOP
        boolean guardrailApplied = req.hops() >= 2;
        ExtractionMode mode = guardrailApplied
                ? ExtractionMode.SIGNATURE_COMPRESSED_2HOP
                : ExtractionMode.FLATTENED_1HOP;
        int effectiveHops = guardrailApplied ? 2 : 1;
        String guardrailNote = guardrailApplied ? TABLE4_GUARDRAIL_NOTE : "";
        int tokenBudget = Math.min(HARD_MAX_TOKENS, req.maxTokens());

        String snapshotCtx = graph.snapshot().value();
        String chosenContext = req.context().orElse(snapshotCtx);
        var targetContexts = new LinkedHashSet<String>();
        if (req.context().isEmpty() || chosenContext.equals(snapshotCtx)) {
            for (var c : graph.contexts()) {
                targetContexts.add(c.identity());
            }
        } else {
            targetContexts.add(chosenContext);
            targetContexts.add(snapshotCtx);
        }

        // 1. Index document paths & parent containment
        var docIdToPath = indexDocumentPaths(graph, targetContexts, guard);
        var parentByChild = new HashMap<String, String>();
        var rawEdges = new ArrayList<RawEdge>();
        for (String ctx : targetContexts) {
            if (stopped(guard)) break;
            for (var edge : graph.edges(ctx)) {
                String normKind = normalizeEdgeKind(edge.kind());
                if ("CONTAINS".equals(normKind)) {
                    for (String t : edge.target().identities()) {
                        parentByChild.putIfAbsent(t, edge.source());
                    }
                }
                if (normKind != null) {
                    TrustTier edgeTier = inferEdgeTrustTier(graph, edge);
                    for (String targetId : edge.target().identities()) {
                        if (!targetId.equals(edge.source())) {
                            rawEdges.add(new RawEdge(edge.kind(), normKind, edge.source(), targetId, edgeTier));
                        }
                    }
                }
            }
        }

        // 2. Index symbol nodes across target contexts
        var nodeMetaById = new LinkedHashMap<String, RawNodeMeta>();
        for (String ctx : targetContexts) {
            if (stopped(guard)) break;
            for (var node : graph.nodes(ctx)) {
                if (!SYMBOL_NODE_KINDS.contains(node.kind())) continue;
                RawNodeMeta meta = inspectNode(graph, node, docIdToPath);
                RawNodeMeta existing = nodeMetaById.get(node.identity());
                if (existing == null || scoreRichness(meta) > scoreRichness(existing)) {
                    nodeMetaById.put(node.identity(), meta);
                }
            }
        }
        // Backfill missing paths via containment parent chain
        for (var entry : new ArrayList<>(nodeMetaById.entrySet())) {
            RawNodeMeta m = entry.getValue();
            if (m.path().isEmpty()) {
                String resolvedPath = resolveAncestorPath(m.id(), parentByChild, docIdToPath, nodeMetaById);
                if (!resolvedPath.isEmpty()) {
                    nodeMetaById.put(m.id(), new RawNodeMeta(
                            m.id(), m.fqn(), m.kind(), resolvedPath, m.startLine(), m.endLine(),
                            m.origin(), m.trustTier(), m.declaredSignature()));
                }
            }
        }

        // 3. Locate center node deterministically by symbolId or searchTerm
        Optional<RawNodeMeta> centerOpt = locateCenter(nodeMetaById, req);
        if (centerOpt.isEmpty()) {
            String rendered = "[SYMBOL EGO-GRAPH] mode=" + mode.name()
                    + " hops=" + effectiveHops
                    + " found=false query=" + req.symbolId().or(() -> req.searchTerm()).orElse("") + "\n";
            int tok = estimateTokens(rendered);
            ContentDigest digest = CanonicalJson.sha256(List.of(
                    VERSION, graph.identity().value(), chosenContext, mode.name(),
                    req.hops(), effectiveHops, false, rendered));
            return new Result(VERSION, graph.identity(), chosenContext, false, mode,
                    req.hops(), effectiveHops, guardrailApplied, guardrailNote,
                    Optional.empty(), List.of(), List.of(), 0, tok, tokenBudget, false, rendered, digest);
        }

        RawNodeMeta centerMeta = centerOpt.orElseThrow();

        // 4. Build bidirectional adjacency index
        var outgoing = new HashMap<String, List<RawEdge>>();
        var incoming = new HashMap<String, List<RawEdge>>();
        for (var e : rawEdges) {
            if (!nodeMetaById.containsKey(e.sourceId()) || !nodeMetaById.containsKey(e.targetId())) continue;
            outgoing.computeIfAbsent(e.sourceId(), k -> new ArrayList<>()).add(e);
            incoming.computeIfAbsent(e.targetId(), k -> new ArrayList<>()).add(e);
        }

        // 5. BFS up to effectiveHops with 2-tier stdlib/external noise filter
        var keptHopById = new HashMap<String, Integer>();
        var keptRoleById = new HashMap<String, String>();
        var keptEdges = new TreeSet<EgoEdge>();
        var filteredIds = new HashSet<String>();

        keptHopById.put(centerMeta.id(), 0);
        keptRoleById.put(centerMeta.id(), "CENTER");

        var queue = new ArrayDeque<String>();
        queue.add(centerMeta.id());

        while (!queue.isEmpty() && !stopped(guard)) {
            String curId = queue.poll();
            int curHop = keptHopById.get(curId);
            if (curHop >= effectiveHops) continue;
            int nextHop = curHop + 1;

            // Outgoing edges: curId -> targetId
            var outList = new ArrayList<>(outgoing.getOrDefault(curId, List.of()));
            outList.sort(Comparator.comparing(RawEdge::normalizedKind)
                    .thenComparing(RawEdge::targetId)
                    .thenComparing(RawEdge::kind));
            for (var e : outList) {
                RawNodeMeta nb = nodeMetaById.get(e.targetId());
                if (nb == null) continue;
                if (!req.includeStdlib() && isFilteredNoise(nb, e.normalizedKind(), e.kind())) {
                    filteredIds.add(nb.id());
                    continue;
                }
                RawNodeMeta src = nodeMetaById.get(e.sourceId());
                keptEdges.add(new EgoEdge(e.kind(), e.normalizedKind(),
                        e.sourceId(), src.fqn(), e.targetId(), nb.fqn(), nextHop, e.trustTier()));
                if (!keptHopById.containsKey(nb.id())) {
                    keptHopById.put(nb.id(), nextHop);
                    keptRoleById.put(nb.id(), outgoingRole(e.normalizedKind()));
                    if (nextHop < effectiveHops) queue.add(nb.id());
                } else {
                    String existingRole = keptRoleById.get(nb.id());
                    String candRole = outgoingRole(e.normalizedKind());
                    if (rolePriority(candRole) < rolePriority(existingRole)) {
                        keptRoleById.put(nb.id(), candRole);
                    }
                }
            }

            // Incoming edges: sourceId -> curId
            var inList = new ArrayList<>(incoming.getOrDefault(curId, List.of()));
            inList.sort(Comparator.comparing(RawEdge::normalizedKind)
                    .thenComparing(RawEdge::sourceId)
                    .thenComparing(RawEdge::kind));
            for (var e : inList) {
                RawNodeMeta nb = nodeMetaById.get(e.sourceId());
                if (nb == null) continue;
                if (!req.includeStdlib() && isFilteredNoise(nb, e.normalizedKind(), e.kind())) {
                    filteredIds.add(nb.id());
                    continue;
                }
                RawNodeMeta dst = nodeMetaById.get(e.targetId());
                keptEdges.add(new EgoEdge(e.kind(), e.normalizedKind(),
                        e.sourceId(), nb.fqn(), e.targetId(), dst.fqn(), nextHop, e.trustTier()));
                if (!keptHopById.containsKey(nb.id())) {
                    keptHopById.put(nb.id(), nextHop);
                    keptRoleById.put(nb.id(), incomingRole(e.normalizedKind()));
                    if (nextHop < effectiveHops) queue.add(nb.id());
                } else {
                    String existingRole = keptRoleById.get(nb.id());
                    String candRole = incomingRole(e.normalizedKind());
                    if (rolePriority(candRole) < rolePriority(existingRole)) {
                        keptRoleById.put(nb.id(), candRole);
                    }
                }
            }
        }

        // Remove any node that was later admitted via a non-filtered wiring edge from filtered count
        filteredIds.removeAll(keptHopById.keySet());

        // 6. Lazily load captured source lines ONLY for files referenced by kept nodes
        var neededPaths = new HashSet<String>();
        for (String id : keptHopById.keySet()) {
            RawNodeMeta m = nodeMetaById.get(id);
            if (m != null && !m.path().isEmpty()) neededPaths.add(m.path());
        }
        Map<String, List<String>> linesByPath = loadSourceLines(graph, neededPaths);

        // 7. Build deterministic EgoNode list
        boolean signatureOnlyMode = mode == ExtractionMode.SIGNATURE_COMPRESSED_2HOP;
        var allNodes = new ArrayList<EgoNode>(keptHopById.size());
        for (var entry : keptHopById.entrySet()) {
            RawNodeMeta m = nodeMetaById.get(entry.getKey());
            int hop = entry.getValue();
            String role = keptRoleById.getOrDefault(m.id(), "REF");
            List<String> fileLines = linesByPath.getOrDefault(m.path(), List.of());
            String signature = extractSignature(m, fileLines);
            String snippet = "";
            if (!signatureOnlyMode) {
                int maxLines = hop == 0 ? MAX_CENTER_SNIPPET_LINES : MAX_NEIGHBOR_SNIPPET_LINES;
                snippet = extractFlattenedSnippet(m, fileLines, maxLines);
            }
            allNodes.add(new EgoNode(
                    m.id(), m.fqn(), m.kind(), m.path(), m.startLine(), m.endLine(),
                    hop, role, m.trustTier(), signature, snippet, signatureOnlyMode));
        }
        Collections.sort(allNodes);

        // 8. Render deterministically within hard <= tokenBudget (<= 2,500 tokens)
        var rendered = new StringBuilder();
        var includedNodes = new ArrayList<EgoNode>();
        var includedNodeIds = new HashSet<String>();
        boolean truncated = false;

        rendered.append("[SYMBOL EGO-GRAPH] mode=").append(mode.name())
                .append(" hops=").append(effectiveHops)
                .append(" requestedHops=").append(req.hops())
                .append(" center=").append(centerMeta.fqn())
                .append(" budget=").append(tokenBudget).append("tok");
        if (filteredIds.size() > 0) {
            rendered.append(" filteredNoise=").append(filteredIds.size());
        }
        rendered.append('\n');
        if (guardrailApplied) {
            rendered.append("[GUARDRAIL] ").append(guardrailNote).append('\n');
        }

        int usedTokens = estimateTokens(rendered.toString());

        // Emit center + neighbors in deterministic priority order, strictly bounded by tokenBudget
        for (var node : allNodes) {
            String block = formatNodeBlock(node, signatureOnlyMode);
            int blockTokens = estimateTokens(block);
            if (!includedNodes.isEmpty() && usedTokens + blockTokens > tokenBudget) {
                // In 1-hop mode, try signature-only fallback for tail neighbors before stopping
                if (!signatureOnlyMode && !node.snippet().isEmpty()) {
                    EgoNode compact = new EgoNode(
                            node.id(), node.fqn(), node.kind(), node.path(), node.startLine(), node.endLine(),
                            node.hopDistance(), node.role(), node.trustTier(), node.signature(), "", true);
                    String compactBlock = formatNodeBlock(compact, true);
                    int compactTokens = estimateTokens(compactBlock);
                    if (usedTokens + compactTokens <= tokenBudget) {
                        rendered.append(compactBlock);
                        usedTokens += compactTokens;
                        includedNodes.add(compact);
                        includedNodeIds.add(compact.id());
                        truncated = true;
                        continue;
                    }
                }
                truncated = true;
                break;
            }
            if (includedNodes.isEmpty() && usedTokens + blockTokens > tokenBudget) {
                int availChars = Math.max(64, (tokenBudget - usedTokens) * 4 - 8);
                if (block.length() > availChars) {
                    block = block.substring(0, availChars) + "\n";
                    truncated = true;
                }
                blockTokens = estimateTokens(block);
            }
            rendered.append(block);
            usedTokens += blockTokens;
            includedNodes.add(node);
            includedNodeIds.add(node.id());
        }

        // Emit edges connecting included nodes while staying strictly within tokenBudget
        var includedEdges = new ArrayList<EgoEdge>();
        var edgeHeader = "[EGO EDGES]\n";
        boolean wroteEdgeHeader = false;
        for (var edge : keptEdges) {
            if (!includedNodeIds.contains(edge.sourceId()) || !includedNodeIds.contains(edge.targetId())) {
                continue;
            }
            String line = "  " + edge.sourceFqn() + " --" + edge.normalizedKind() + "--> "
                    + edge.targetFqn() + " [hop=" + edge.hopDistance() + ", trust=" + edge.trustTier().name() + "]\n";
            int addTok = estimateTokens((wroteEdgeHeader ? "" : edgeHeader) + line);
            if (usedTokens + addTok > tokenBudget) {
                truncated = true;
                break;
            }
            if (!wroteEdgeHeader) {
                rendered.append(edgeHeader);
                wroteEdgeHeader = true;
            }
            rendered.append(line);
            usedTokens += addTok;
            includedEdges.add(edge);
        }

        String finalText = rendered.toString();
        int finalTokens = estimateTokens(finalText);
        if (finalTokens > tokenBudget) {
            int maxChars = Math.max(32, tokenBudget * 4 - 4);
            if (finalText.length() > maxChars) {
                finalText = finalText.substring(0, maxChars) + "...\n";
            }
            finalTokens = Math.min(tokenBudget, estimateTokens(finalText));
            truncated = true;
        }

        Optional<EgoNode> finalCenter = includedNodes.stream()
                .filter(n -> n.id().equals(centerMeta.id()))
                .findFirst();

        var digestPayload = new TreeMap<String, Object>();
        digestPayload.put("context", chosenContext);
        digestPayload.put("edges", includedEdges.stream().map(EgoEdge::toMap).toList());
        digestPayload.put("effectiveHops", effectiveHops);
        digestPayload.put("filteredNoiseNodes", filteredIds.size());
        digestPayload.put("graphIdentity", graph.identity().value());
        digestPayload.put("guardrailApplied", guardrailApplied);
        digestPayload.put("mode", mode.name());
        digestPayload.put("nodes", includedNodes.stream().map(EgoNode::toMap).toList());
        digestPayload.put("tokenBudget", tokenBudget);
        digestPayload.put("truncated", truncated);
        digestPayload.put("version", VERSION);
        ContentDigest canonicalDigest = CanonicalJson.sha256(digestPayload);

        return new Result(
                VERSION,
                graph.identity(),
                chosenContext,
                true,
                mode,
                req.hops(),
                effectiveHops,
                guardrailApplied,
                guardrailNote,
                finalCenter,
                includedNodes,
                includedEdges,
                filteredIds.size(),
                finalTokens,
                tokenBudget,
                truncated,
                finalText,
                canonicalDigest);
    }

    /** Tier-1 (language stdlib prefixes) + Tier-2 (external non-wiring nodes) filter. */
    public static boolean isStdlibFqn(String fqn) {
        if (fqn == null || fqn.isBlank()) return false;
        String clean = fqn.strip();
        if (BARE_STDLIB_SYMBOLS.contains(clean)) return true;
        for (String prefix : STDLIB_PREFIXES) {
            if (clean.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Returns true if an external node/edge represents Spring/framework wiring that MUST be preserved. */
    public static boolean isFrameworkWiringPreserved(String fqn, String normalizedEdgeKind, String rawEdgeKind) {
        if (FRAMEWORK_PRESERVED_EDGE_KINDS.contains(normalizedEdgeKind)) {
            return true;
        }
        if (rawEdgeKind != null && (rawEdgeKind.startsWith("spring.") || "ADVISES_METHOD".equals(rawEdgeKind)
                || "PROXIED_BY_ASPECT".equals(rawEdgeKind))) {
            return true;
        }
        if (fqn != null) {
            for (String prefix : FRAMEWORK_WIRING_PREFIXES) {
                if (fqn.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    private static boolean isFilteredNoise(RawNodeMeta node, String normalizedEdgeKind, String rawEdgeKind) {
        // Tier 1: Language stdlib/builtins always excluded unless explicitly preserved wiring
        if (isStdlibFqn(node.fqn())) {
            return true;
        }
        // Tier 2: External / library non-editable nodes excluded UNLESS Spring/framework wiring
        boolean external = node.origin() != null
                && !node.origin().isEmpty()
                && !"PROJECT".equalsIgnoreCase(node.origin());
        if (external) {
            return !isFrameworkWiringPreserved(node.fqn(), normalizedEdgeKind, rawEdgeKind);
        }
        return false;
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (text.length() + 3) / 4);
    }

    private static String formatNodeBlock(EgoNode node, boolean signatureOnlyMode) {
        var sb = new StringBuilder();
        sb.append("- [").append(node.role()).append(" hop=").append(node.hopDistance())
                .append(" ").append(node.kind()).append("] ")
                .append(node.fqn());
        if (!node.path().isEmpty()) {
            sb.append(" (").append(node.path());
            if (node.startLine() > 0) {
                sb.append(':').append(node.startLine());
                if (node.endLine() > node.startLine()) {
                    sb.append('-').append(node.endLine());
                }
            }
            sb.append(')');
        }
        sb.append(" [trust=").append(node.trustTier().name());
        if (signatureOnlyMode || node.compressedSignatureOnly()) {
            sb.append(", sigOnly=true");
        }
        sb.append("]\n");

        if (!node.signature().isBlank()) {
            sb.append("  sig: ").append(node.signature()).append('\n');
        }
        if (!signatureOnlyMode && !node.compressedSignatureOnly() && !node.snippet().isBlank()) {
            for (String line : node.snippet().split("\\R")) {
                sb.append("  | ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static Optional<RawNodeMeta> locateCenter(Map<String, RawNodeMeta> nodesById, Request req) {
        if (req.symbolId().isPresent()) {
            String sid = req.symbolId().orElseThrow();
            RawNodeMeta direct = nodesById.get(sid);
            if (direct != null) return Optional.of(direct);
            Optional<RawNodeMeta> byFqn = matchBestNode(nodesById, sid);
            if (byFqn.isPresent()) return byFqn;
        }
        if (req.searchTerm().isPresent()) {
            return matchBestNode(nodesById, req.searchTerm().orElseThrow());
        }
        return Optional.empty();
    }

    private static Optional<RawNodeMeta> matchBestNode(Map<String, RawNodeMeta> nodesById, String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        String q = query.strip();
        String qLow = q.toLowerCase(Locale.ROOT);
        RawNodeMeta best = null;
        int bestRank = Integer.MAX_VALUE;
        for (var m : nodesById.values()) {
            String fqn = m.fqn();
            String fqnLow = fqn.toLowerCase(Locale.ROOT);
            String simple = simpleName(fqn);
            String simpleLow = simple.toLowerCase(Locale.ROOT);
            int rank;
            if (m.id().equals(q)) rank = 0;
            else if (fqn.equals(q)) rank = 1;
            else if (fqnLow.equals(qLow)) rank = 2;
            else if (simple.equals(q)) rank = 3;
            else if (simpleLow.equals(qLow)) rank = 4;
            else if (fqnLow.endsWith("." + qLow) || fqnLow.endsWith("#" + qLow)) rank = 5;
            else if (fqnLow.contains(qLow)) rank = 6;
            else continue;

            // Prefer project-origin types over external/member symbols on equal match tier
            int typeBonus = isTypeKind(m.kind()) ? 0 : 1;
            int originBonus = (m.origin().isEmpty() || "PROJECT".equalsIgnoreCase(m.origin())) ? 0 : 2;
            int compositeRank = rank * 10 + originBonus * 2 + typeBonus;

            if (best == null || compositeRank < bestRank
                    || (compositeRank == bestRank && compareMeta(m, best) < 0)) {
                best = m;
                bestRank = compositeRank;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int compareMeta(RawNodeMeta a, RawNodeMeta b) {
        int cmp = a.fqn().compareTo(b.fqn());
        return cmp != 0 ? cmp : a.id().compareTo(b.id());
    }

    private static boolean isTypeKind(String kind) {
        return Set.of("TYPE", "CLASS", "INTERFACE", "RECORD", "ENUM", "POLYGLOT_TYPE", "POLYGLOT_CLASS")
                .contains(kind);
    }

    private static String simpleName(String fqn) {
        if (fqn == null || fqn.isEmpty()) return "";
        int hash = fqn.lastIndexOf('#');
        int dot = fqn.lastIndexOf('.');
        int sep = Math.max(hash, dot);
        return sep >= 0 && sep + 1 < fqn.length() ? fqn.substring(sep + 1) : fqn;
    }

    private static String normalizeEdgeKind(String kind) {
        if (kind == null || kind.isBlank()) return null;
        if (CONTAINMENT_EDGE_KINDS.contains(kind)) return "CONTAINS";
        if (CALL_EDGE_KINDS.contains(kind)) return "CALLS";
        if (INJECT_EDGE_KINDS.contains(kind)) return "INJECTS";
        if (IMPLEMENTS_EDGE_KINDS.contains(kind)) return "IMPLEMENTS";
        if (EXTENDS_EDGE_KINDS.contains(kind)) return "EXTENDS";
        if (ADVISES_EDGE_KINDS.contains(kind)) return "ADVISES_METHOD";
        if (REF_EDGE_KINDS.contains(kind)) return "REF";
        return null;
    }

    private static String outgoingRole(String normalizedKind) {
        return switch (normalizedKind) {
            case "INJECTS" -> "INJECTED";
            case "IMPLEMENTS", "EXTENDS" -> "INTERFACE";
            case "ADVISES_METHOD" -> "ADVISED";
            case "CALLS" -> "CALLEE";
            case "CONTAINS" -> "CONTAINED";
            default -> "REF";
        };
    }

    private static String incomingRole(String normalizedKind) {
        return switch (normalizedKind) {
            case "INJECTS" -> "INJECTOR";
            case "IMPLEMENTS", "EXTENDS" -> "IMPLEMENTOR";
            case "ADVISES_METHOD" -> "ASPECT";
            case "CALLS" -> "CALLER";
            case "CONTAINS" -> "DEF";
            default -> "REF";
        };
    }

    private static int rolePriority(String role) {
        return switch (role) {
            case "CENTER" -> 0;
            case "INJECTOR" -> 1;
            case "INJECTED" -> 2;
            case "INTERFACE" -> 3;
            case "IMPLEMENTOR" -> 4;
            case "ASPECT" -> 5;
            case "ADVISED" -> 6;
            case "CALLER" -> 7;
            case "CALLEE" -> 8;
            case "DEF" -> 9;
            case "CONTAINED" -> 10;
            default -> 11;
        };
    }

    private static Map<String, String> indexDocumentPaths(
            KnowledgeGraph graph, Set<String> contexts, Supplier<StopReason> stop) {
        var docIdToPath = new HashMap<String, String>();
        for (String ctx : contexts) {
            if (stopped(stop)) break;
            for (var node : graph.nodes(ctx)) {
                if ("SOURCE_DOCUMENT".equals(node.kind())) {
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null) continue;
                        if ("source.document".equals(ev.family())) {
                            var obj = readSmall(ev);
                            if (obj.get("path") instanceof String p && !p.isBlank()) {
                                docIdToPath.put(node.identity(), normalizePath(p));
                            }
                        } else if ("source.bytes".equals(ev.family())) {
                            var obj = readSmall(ev);
                            if (obj.get("document") instanceof Map<?, ?> d
                                    && d.get("path") instanceof String p && !p.isBlank()) {
                                docIdToPath.put(node.identity(), normalizePath(p));
                            }
                        }
                    }
                } else if ("INVENTORY_ENTRY".equals(node.kind())) {
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null || !"polyglot.inventory-entry".equals(ev.family())) continue;
                        var obj = readSmall(ev);
                        if (obj.get("fields") instanceof Map<?, ?> fields
                                && fields.get("rawPath") instanceof String rawB64) {
                            new LogicalPath(rawB64).utf8()
                                    .ifPresent(p -> docIdToPath.put(node.identity(), normalizePath(p)));
                        }
                    }
                }
            }
        }
        return docIdToPath;
    }

    private static RawNodeMeta inspectNode(
            KnowledgeGraph graph, KnowledgeGraph.Node node, Map<String, String> docIdToPath) {
        String fqn = "";
        String path = "";
        int startLine = 0;
        int endLine = 0;
        String origin = "";
        TrustTier trustTier = TrustTier.SOURCE_SYNTAX;
        String declaredSig = "";

        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            var payload = readSmall(ev);
            switch (ev.family()) {
                case "java.declaration" -> {
                    trustTier = TrustTier.COMPILER;
                    if (payload.get("entity") instanceof Map<?, ?> ent) {
                        if (ent.get("qualifiedName") instanceof String q && !q.isBlank()) {
                            fqn = q;
                        } else if (ent.get("canonicalName") instanceof String c && !c.isBlank()) {
                            fqn = readableCanonicalName(c);
                        }
                        if (ent.get("origin") instanceof String o) origin = o;
                        if (ent.get("declaration") instanceof Map<?, ?> sp) {
                            if (sp.get("document") instanceof String docId) {
                                path = docIdToPath.getOrDefault(docId, path);
                            }
                            startLine = intVal(sp.get("startLine"), startLine);
                            endLine = intVal(sp.get("endLine"), endLine);
                        }
                        if (ent.get("signature") instanceof String s && !s.isBlank()) {
                            declaredSig = s;
                        }
                    }
                    if (payload.get("signature") instanceof String s && !s.isBlank()) {
                        declaredSig = s;
                    }
                }
                case "spring.candidate", "spring.endpoint-declaration", "spring.aop-advice" -> {
                    if (trustTier != TrustTier.COMPILER) trustTier = TrustTier.SPRING_SAT;
                    if (payload.get("declaredNameKey") instanceof Map<?, ?> k
                            && k.get("primary") instanceof String p && fqn.isEmpty()) {
                        fqn = p;
                    }
                    if (payload.get("fqn") instanceof String q && !q.isBlank()) {
                        fqn = q;
                    }
                    if (payload.get("span") instanceof Map<?, ?> sp) {
                        if (sp.get("document") instanceof String docId) {
                            path = docIdToPath.getOrDefault(docId, path);
                        }
                        startLine = intVal(sp.get("startLine"), startLine);
                        endLine = intVal(sp.get("endLine"), endLine);
                    }
                    if (origin.isEmpty()) origin = "PROJECT";
                }
                case "polyglot.source-syntax" -> {
                    Map<?, ?> fields = payload.get("fields") instanceof Map<?, ?> f ? f : payload;
                    if (fields.get("trustTier") instanceof String tt) {
                        trustTier = parseTrustTier(tt, trustTier);
                    }
                    if (fields.get("path") instanceof String p && !p.isBlank()) {
                        path = normalizePath(p);
                    } else if (fields.get("rawPath") instanceof String rawB64) {
                        var utf8 = new LogicalPath(rawB64).utf8();
                        if (utf8.isPresent()) path = normalizePath(utf8.orElseThrow());
                    }
                    if (fields.get("fqn") instanceof String q && !q.isBlank()) {
                        fqn = q;
                    } else if (fields.get("qualifiedName") instanceof String q && !q.isBlank()) {
                        fqn = q;
                    } else if (fields.get("name") instanceof String n && !n.isBlank() && fqn.isEmpty()) {
                        fqn = n;
                    } else if (fields.get("symbol") instanceof String s && !s.isBlank() && fqn.isEmpty()) {
                        fqn = s;
                    }
                    if (fields.get("signature") instanceof String s && !s.isBlank()) {
                        declaredSig = s;
                    }
                    startLine = intVal(fields.get("startLine"), startLine);
                    endLine = intVal(fields.get("endLine"), endLine);
                    if (fields.get("fact") instanceof Map<?, ?> fact) {
                        if (fact.get("attributes") instanceof Map<?, ?> attrs) {
                            if (attrs.get("fqn") instanceof String q && !q.isBlank()) fqn = q;
                            else if (attrs.get("qualifiedName") instanceof String q && !q.isBlank()) fqn = q;
                        }
                        if (fqn.isEmpty() && fact.get("name") instanceof String n && !n.isBlank()) {
                            fqn = n;
                        }
                        if (fact.get("span") instanceof Map<?, ?> sp) {
                            startLine = intVal(sp.get("startLine"), startLine);
                            endLine = intVal(sp.get("endLine"), endLine);
                        }
                    }
                    if (origin.isEmpty()) origin = "PROJECT";
                }
                default -> {
                    if (payload.get("trustTier") instanceof String tt) {
                        trustTier = parseTrustTier(tt, trustTier);
                    }
                    if (payload.get("fqn") instanceof String q && !q.isBlank() && fqn.isEmpty()) {
                        fqn = q;
                    }
                }
            }
        }
        if (fqn.isEmpty()) fqn = node.identity();
        if (endLine < startLine) endLine = startLine;
        return new RawNodeMeta(node.identity(), fqn, node.kind(), path, startLine, endLine, origin, trustTier, declaredSig);
    }

    private static TrustTier inferEdgeTrustTier(KnowledgeGraph graph, KnowledgeGraph.Edge edge) {
        for (var ref : edge.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            var obj = readSmall(ev);
            if (obj.get("trustTier") instanceof String tt) {
                return parseTrustTier(tt, TrustTier.SOURCE_SYNTAX);
            }
            if (obj.get("trust") instanceof String tt) {
                return parseTrustTier(tt, TrustTier.SOURCE_SYNTAX);
            }
            String fam = ev.family();
            if (fam.startsWith("java.")) return TrustTier.COMPILER;
            if (fam.startsWith("spring.")) return TrustTier.SPRING_SAT;
            if (fam.startsWith("polyglot.") || fam.startsWith("syntax.")) return TrustTier.SOURCE_SYNTAX;
        }
        String k = edge.kind();
        if (k.startsWith("spring.") || "ADVISES_METHOD".equals(k)) return TrustTier.SPRING_SAT;
        if (k.startsWith("java.")) return TrustTier.COMPILER;
        return TrustTier.SOURCE_SYNTAX;
    }

    private static int scoreRichness(RawNodeMeta m) {
        int score = 0;
        if (!m.fqn().equals(m.id())) score += 4;
        if (!m.path().isEmpty()) score += 3;
        if (m.startLine() > 0) score += 2;
        if (m.trustTier() == TrustTier.COMPILER) score += 2;
        if (!m.declaredSignature().isEmpty()) score += 1;
        return score;
    }

    private static String resolveAncestorPath(
            String nodeId,
            Map<String, String> parentByChild,
            Map<String, String> docIdToPath,
            Map<String, RawNodeMeta> nodeMetaById) {
        String cursor = parentByChild.get(nodeId);
        var visited = new HashSet<String>();
        visited.add(nodeId);
        for (int depth = 0; depth < 8 && cursor != null && visited.add(cursor); depth++) {
            RawNodeMeta parentMeta = nodeMetaById.get(cursor);
            if (parentMeta != null && !parentMeta.path().isEmpty()) return parentMeta.path();
            String doc = docIdToPath.get(cursor);
            if (doc != null && !doc.isEmpty()) return doc;
            cursor = parentByChild.get(cursor);
        }
        return "";
    }

    private static Map<String, List<String>> loadSourceLines(KnowledgeGraph graph, Set<String> neededPaths) {
        if (neededPaths.isEmpty()) return Map.of();
        var result = new HashMap<String, List<String>>();
        for (var ev : graph.evidence()) {
            if (result.size() >= neededPaths.size()) break;
            if (!"source.bytes".equals(ev.family())) continue;
            if (ev.canonicalJson().length() > 4 * 1024 * 1024) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), SOURCE_BYTES_LIMITS));
                if (!(obj.get("document") instanceof Map<?, ?> doc)) continue;
                if (!(doc.get("path") instanceof String rawPath)) continue;
                String norm = normalizePath(rawPath);
                if (!neededPaths.contains(norm) || result.containsKey(norm)) continue;
                if (obj.get("bytesBase64") instanceof String b64 && !b64.isEmpty()) {
                    byte[] raw = Base64.getDecoder().decode(b64);
                    String text = new String(raw, StandardCharsets.UTF_8);
                    result.put(norm, List.of(text.split("\\R", -1)));
                }
            } catch (RuntimeException ignored) {}
        }
        return result;
    }

    private static String extractSignature(RawNodeMeta meta, List<String> lines) {
        if (!meta.declaredSignature().isBlank()) {
            return collapseSpaces(meta.declaredSignature(), 180);
        }
        if (!lines.isEmpty() && meta.startLine() >= 1 && meta.startLine() <= lines.size()) {
            int idx = meta.startLine() - 1;
            String docSummary = findPrecedingDocSummary(lines, idx);
            var sig = new StringBuilder();
            for (int i = idx; i < Math.min(lines.size(), idx + 4); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty()) continue;
                if (line.startsWith("@") && sig.isEmpty()) {
                    sig.append(line).append(' ');
                    continue;
                }
                int brace = line.indexOf('{');
                if (brace >= 0) {
                    sig.append(line, 0, brace).append(';');
                    break;
                }
                sig.append(line);
                if (line.endsWith(";") || line.endsWith(":")) break;
                sig.append(' ');
            }
            String cleanSig = collapseSpaces(sig.toString(), 180);
            if (!cleanSig.isEmpty()) {
                return docSummary.isEmpty() ? cleanSig : cleanSig + " // " + docSummary;
            }
        }
        return meta.kind().toLowerCase(Locale.ROOT) + " " + meta.fqn();
    }

    private static String findPrecedingDocSummary(List<String> lines, int declLineIdx) {
        for (int i = declLineIdx - 1; i >= Math.max(0, declLineIdx - 8); i--) {
            String t = lines.get(i).strip();
            if (t.isEmpty() || t.startsWith("@")) continue;
            if (t.endsWith("*/")) {
                var sb = new StringBuilder();
                for (int j = i; j >= Math.max(0, i - 6); j--) {
                    String l = lines.get(j).strip()
                            .replaceFirst("^/\\*+\\s*", "")
                            .replaceFirst("\\*+/$", "")
                            .replaceFirst("^\\*+\\s*", "")
                            .strip();
                    if (!l.isEmpty() && !l.startsWith("@")) {
                        sb.insert(0, l + " ");
                    }
                    if (lines.get(j).contains("/*")) break;
                }
                return collapseSpaces(sb.toString(), 80);
            }
            break;
        }
        return "";
    }

    private static String extractFlattenedSnippet(RawNodeMeta meta, List<String> lines, int maxLines) {
        if (lines.isEmpty()) return "";
        int startIdx;
        int endIdx;
        if (meta.startLine() >= 1 && meta.startLine() <= lines.size()) {
            startIdx = meta.startLine() - 1;
            int rawEnd = meta.endLine() >= meta.startLine() ? meta.endLine() - 1 : startIdx + maxLines - 1;
            endIdx = Math.min(lines.size() - 1, Math.min(rawEnd, startIdx + maxLines - 1));
        } else {
            // Find line mentioning simple name
            String simple = simpleName(meta.fqn());
            startIdx = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(simple)) {
                    startIdx = i;
                    break;
                }
            }
            if (startIdx < 0) startIdx = 0;
            endIdx = Math.min(lines.size() - 1, startIdx + maxLines - 1);
        }
        var sb = new StringBuilder();
        for (int i = startIdx; i <= endIdx; i++) {
            if (i > startIdx) sb.append('\n');
            String line = lines.get(i);
            if (line.length() > 180) line = line.substring(0, 177) + "...";
            sb.append(i + 1).append(": ").append(line);
        }
        return sb.toString();
    }

    private static String collapseSpaces(String raw, int maxChars) {
        if (raw == null) return "";
        String clean = raw.replaceAll("\\s+", " ").strip();
        return clean.length() > maxChars ? clean.substring(0, maxChars - 3) + "..." : clean;
    }

    private static TrustTier parseTrustTier(String raw, TrustTier fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return TrustTier.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static int intVal(Object raw, int fallback) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static String normalizePath(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\\', '/').strip();
        while (s.startsWith("./")) s = s.substring(2);
        return s;
    }

    private static String readableCanonicalName(String canonical) {
        if (canonical == null || !canonical.startsWith("java:v1:")) return canonical == null ? "" : canonical;
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

    private static boolean stopped(Supplier<StopReason> stop) {
        return stop != null && stop.get() != StopReason.NONE;
    }

    private static Map<String, Object> readSmall(GraphEvidence ev) {
        if (ev.canonicalJson().length() > 131_072) return Map.of();
        try {
            return GraphJson.object(GraphJson.read(ev.canonicalJson(), SMALL_EVIDENCE));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }
}
