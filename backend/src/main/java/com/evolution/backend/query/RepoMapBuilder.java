package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Supplier;

/**
 * Deterministic Aider-style Personalized PageRank (PPR) repository map computed strictly
 * over the canonical KnowledgeGraph dependency, call, injection, and bridge edges.
 */
final class RepoMapBuilder {
    static final String ALGORITHM = "personalized-pagerank-v1";
    static final double DAMPING = 0.85;
    static final int ITERATIONS = 20;
    private static final Set<String> CONTAINMENT_EDGES = Set.of(
            "graph.contains", "graph.declares", "graph.owns-source", "graph.inventory-under-component",
            "spring.declares-injection");
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
            "spring.selected-binding", "spring.injection-candidate",
            "CALLS_SERVICE", "INVOKES_RPC", "PUBLISHES_EVENT",
            "polyglot.service-call.candidate", "polyglot.grpc-service-call.candidate",
            "polyglot.event-flow.candidate", "polyglot.grpc-contract.candidate");
    private static final Set<String> CALL_INHERIT_EDGES = Set.of(
            "java.calls", "java.extends", "java.implements");

    record Declaration(String identity, String name, String kind) {}

    record FileRank(int rank, String path, BigDecimal score, String language,
                    int inDegree, int outDegree, List<Declaration> declarations,
                    List<String> endpoints, int gapCount) {
        FileRank {
            declarations = List.copyOf(declarations);
            endpoints = List.copyOf(endpoints);
        }
    }

    record Result(ContentDigest graph, String context, String algorithm, BigDecimal damping, int iterations,
                  int totalNodes, int totalEdges, int totalFiles, int seedCount, int estimatedTokens,
                  boolean truncated, List<FileRank> files, String renderedMap) {
        Result {
            files = List.copyOf(files);
        }
    }

    private RepoMapBuilder() {}

    static Result build(KnowledgeGraph graph, Optional<String> preferredContext, List<String> rawSeeds,
                        int requestedMaxFiles, int requestedMaxTokens, Supplier<StopReason> stop) {
        int maxFiles = Math.max(1, Math.min(100, requestedMaxFiles));
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

        // 2. Map symbol nodes -> real captured file paths, declarations, endpoints, gaps
        var nodeToFile = new HashMap<String, String>();
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
            }
        }

        // Seed remaining readable captured files so non-symbol files (configs/scripts/README) remain discoverable
        for (var entry : fileIndex.entries()) {
            if (allFiles.size() >= 500) break;
            if (entry.readable() && !isOpaqueIdentity(entry.path())) {
                allFiles.add(entry.path());
            }
        }

        // 3. Build weighted file-level graph from canonical edges
        List<String> fileList = List.copyOf(allFiles);
        int n = fileList.size();
        var fileIndexMap = new HashMap<String, Integer>(n * 2);
        for (int i = 0; i < n; i++) fileIndexMap.put(fileList.get(i), i);

        var outEdges = new ArrayList<Map<Integer, Double>>(n);
        for (int i = 0; i < n; i++) outEdges.add(new TreeMap<>());
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
                String srcFile = nodeToFile.get(edge.source());
                if (srcFile == null) continue;
                Integer u = fileIndexMap.get(srcFile);
                if (u == null) continue;
                double w = edgeWeight(edge.kind());
                for (String targetId : edge.target().identities()) {
                    String dstFile = nodeToFile.get(targetId);
                    if (dstFile == null || dstFile.equals(srcFile)) continue;
                    Integer v = fileIndexMap.get(dstFile);
                    if (v == null) continue;
                    outEdges.get(u).merge(v, w, Double::sum);
                    long pairKey = (((long) u) << 32) | (v & 0xffffffffL);
                    if (seenPairs.add(pairKey)) {
                        outDegree[u]++;
                        inDegree[v]++;
                    }
                }
            }
        }

        // 4. Compute deterministic Personalized PageRank (PPR)
        double[] personalization = new double[n];
        int matchedSeedFiles = 0;
        if (n > 0) {
            boolean[] isSeeded = new boolean[n];
            if (!seeds.isEmpty()) {
                for (int i = 0; i < n; i++) {
                    String path = fileList.get(i);
                    var decls = declsByFile.getOrDefault(path, new LinkedHashMap<>());
                    var eps = endpointsByFile.getOrDefault(path, new TreeSet<>());
                    if (matchesAnySeed(path, decls.values(), eps, seeds)) {
                        isSeeded[i] = true;
                        matchedSeedFiles++;
                    }
                }
            }
            if (matchedSeedFiles > 0) {
                double seedShare = 0.85 / matchedSeedFiles;
                double baseShare = 0.15 / n;
                for (int i = 0; i < n; i++) {
                    personalization[i] = baseShare + (isSeeded[i] ? seedShare : 0.0);
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

            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            final double[] finalRank = rank;
            Arrays.sort(order, (a, b) -> {
                int cmp = Double.compare(finalRank[b], finalRank[a]);
                if (cmp != 0) return cmp;
                cmp = Integer.compare(inDegree[b], inDegree[a]);
                if (cmp != 0) return cmp;
                return fileList.get(a).compareTo(fileList.get(b));
            });

            var rankedFiles = new ArrayList<FileRank>();
            var mapOut = new StringBuilder();
            int usedTokens = 0;
            boolean truncated = false;
            for (int pos = 0; pos < n; pos++) {
                if (rankedFiles.size() >= maxFiles) {
                    truncated = true;
                    break;
                }
                int idx = order[pos];
                String path = fileList.get(idx);
                BigDecimal score = BigDecimal.valueOf(finalRank[idx]).setScale(6, RoundingMode.HALF_UP);
                String lang = langByPath.getOrDefault(path, CapturedFileIndex.inferLanguage(path, List.of()));
                var decls = declsByFile.getOrDefault(path, new LinkedHashMap<>()).values().stream()
                        .sorted(Comparator.comparing(Declaration::kind).thenComparing(Declaration::name))
                        .limit(10).toList();
                var eps = endpointsByFile.getOrDefault(path, new TreeSet<>()).stream().limit(6).toList();
                int gaps = gapsByFile.getOrDefault(path, 0);

                String block = renderFileBlock(pos + 1, path, score, lang, inDegree[idx], outDegree[idx], decls, eps, gaps);
                int blockTokens = Math.max(1, (block.length() + 3) / 4);
                if (!rankedFiles.isEmpty() && usedTokens + blockTokens > maxTokens) {
                    truncated = true;
                    break;
                }
                usedTokens += blockTokens;
                mapOut.append(block);
                rankedFiles.add(new FileRank(pos + 1, path, score, lang, inDegree[idx], outDegree[idx], decls, eps, gaps));
            }

            return new Result(graph.identity(), chosenContext, ALGORITHM,
                    BigDecimal.valueOf(DAMPING).setScale(2, RoundingMode.HALF_UP), ITERATIONS,
                    totalNodes, totalEdges, n, matchedSeedFiles, usedTokens, truncated,
                    rankedFiles, mapOut.toString());
        }

        return new Result(graph.identity(), chosenContext, ALGORITHM,
                BigDecimal.valueOf(DAMPING).setScale(2, RoundingMode.HALF_UP), ITERATIONS,
                totalNodes, totalEdges, 0, 0, 0, false, List.of(), "");
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

    private record NodeInfo(String name, String path, String language, String origin, String endpoint, boolean isDeclaration) {}

    private static NodeInfo inspectNode(KnowledgeGraph graph, KnowledgeGraph.Node node, Map<String, String> docIdToPath) {
        String name = null, path = null, language = null, origin = null, endpoint = null;
        boolean isDecl = false;
        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            switch (ev.family()) {
                case "java.declaration" -> {
                    var payload = readSmall(ev);
                    if (payload.get("entity") instanceof Map<?, ?> ent) {
                        if (ent.get("canonicalName") instanceof String c) name = readableCanonicalName(c);
                        if (ent.get("origin") instanceof String o) origin = o;
                        if (ent.get("declaration") instanceof Map<?, ?> span && span.get("document") instanceof String docId) {
                            path = docIdToPath.get(docId);
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
                    if (payload.get("span") instanceof Map<?, ?> span && span.get("document") instanceof String docId) {
                        path = docIdToPath.get(docId);
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
                    if (payload.get("span") instanceof Map<?, ?> span && span.get("document") instanceof String docId) {
                        path = docIdToPath.get(docId);
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
                    if (fields.get("name") instanceof String n && !n.isBlank()) name = n;
                    else if (fields.get("symbol") instanceof String s && !s.isBlank()) name = s;
                    if (fields.get("route") instanceof String r && !r.isBlank()) {
                        String method = fields.get("httpMethod") instanceof String m ? m : "";
                        endpoint = (method + " " + r).trim();
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
        return new NodeInfo(name, path, language, origin, endpoint, isDecl);
    }

    static String readableCanonicalName(String canonical) {
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

    private static boolean matchesAnySeed(String path, Collection<Declaration> decls, Set<String> endpoints, List<String> seeds) {
        String pLow = path.toLowerCase(Locale.ROOT);
        for (String rawSeed : seeds) {
            String s = rawSeed.toLowerCase(Locale.ROOT);
            if (pLow.contains(s)) return true;
            for (var d : decls) {
                if (d.identity().equalsIgnoreCase(rawSeed) || d.name().toLowerCase(Locale.ROOT).contains(s)) return true;
            }
            for (var ep : endpoints) {
                if (ep.toLowerCase(Locale.ROOT).contains(s)) return true;
            }
        }
        return false;
    }

    private static String renderFileBlock(int rank, String path, BigDecimal score, String lang,
                                          int inDeg, int outDeg, List<Declaration> decls, List<String> eps, int gaps) {
        var sb = new StringBuilder();
        sb.append(rank).append(". ").append(path)
                .append(" [score=").append(score)
                .append(", in=").append(inDeg)
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
