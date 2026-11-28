package com.evolution.backend.query.advisory;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.backend.analysis.JsWorkspaceAliasResolver;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.RepoMapBuilder;

import java.util.*;

/**
 * Cached, strictly read-only orchestrator for the quarantined {@code advisory.doc-link:v1} overlay.
 *
 * <p>Reads only immutable CAS bytes via {@link CapturedFileIndex} and canonical nodes from
 * {@link KnowledgeGraph}. NEVER modifies {@link KnowledgeGraph}, policy evaluation, Architecture
 * Score v2, or repair verification outcomes.
 */
public final class AdvisoryOverlayService {
    private static final int MAX_CACHED_GRAPHS = 16;
    private static final GraphJson.Limits SMALL_JSON = new GraphJson.Limits(131_072, 32, 8_192);

    private record CacheKey(ContentDigest graphId, long fallbackGeneration) {}

    private static final Map<CacheKey, AdvisoryOverlay.Snapshot> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHED_GRAPHS + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, AdvisoryOverlay.Snapshot> eldest) {
                    return size() > MAX_CACHED_GRAPHS;
                }
            });

    private AdvisoryOverlayService() {}

    public static AdvisoryOverlay.Snapshot forGraph(KnowledgeGraph graph) {
        Objects.requireNonNull(graph, "graph");
        var key = new CacheKey(graph.identity(), com.evolution.backend.query.RepairSource.contentFallbackGeneration());
        var cached = CACHE.get(key);
        if (cached != null) return cached;
        var built = build(graph);
        CACHE.put(key, built);
        return built;
    }

    /**
     * Read-only quarantined entry point for SemRef Stages 2-4 hierarchical cluster refinement.
     * Strictly returns {@code TrustTier.ADVISORY_UNVERIFIED} outputs without mutating
     * {@link KnowledgeGraph}, canonical Leiden partitions, Policy findings, or Score v2.
     */
    public static SemanticClusterRefinementService.RefinementResult refineClustersAdvisory(
            KnowledgeGraph graph,
            String context,
            SemanticClusterRefinementService.RefinementAdvisor advisor,
            SemanticClusterRefinementService.Config config) {
        return SemanticClusterRefinementService.refineGraph(graph, context, advisor, config);
    }

    /** Read-only quarantined SemRef refinement using the deterministic offline fallback advisor. */
    public static SemanticClusterRefinementService.RefinementResult refineClustersAdvisory(
            KnowledgeGraph graph,
            String context) {
        return SemanticClusterRefinementService.refineGraph(
                graph,
                context,
                SemanticClusterRefinementService.deterministicAdvisor(),
                SemanticClusterRefinementService.Config.defaults());
    }

    /** Filters an overlay snapshot by optional entity identity/name, kind, or source path substring. */
    public static AdvisoryOverlay.Snapshot filter(
            AdvisoryOverlay.Snapshot full,
            String entityQuery,
            String kindFilter,
            String pathFilter) {
        String eq = entityQuery == null ? "" : entityQuery.strip();
        String eqLow = eq.toLowerCase(Locale.ROOT);
        String kf = kindFilter == null ? "" : kindFilter.strip().toUpperCase(Locale.ROOT);
        String pf = pathFilter == null ? "" : CapturedFileIndex.normalizePath(pathFilter).toLowerCase(Locale.ROOT);

        if (eq.isEmpty() && kf.isEmpty() && pf.isEmpty()) {
            return full;
        }

        var matchedNodeIds = new HashSet<String>();
        var filteredLinks = new ArrayList<AdvisoryOverlay.AdvisoryLink>();
        for (var link : full.links()) {
            if (!kf.isEmpty() && !link.kind().toUpperCase(Locale.ROOT).contains(kf)
                    && !link.targetKind().toUpperCase(Locale.ROOT).contains(kf)) {
                continue;
            }
            if (!pf.isEmpty() && !link.sourcePath().toLowerCase(Locale.ROOT).contains(pf)
                    && !link.targetLabel().toLowerCase(Locale.ROOT).contains(pf)) {
                continue;
            }
            if (!eq.isEmpty()) {
                boolean hit = link.targetId().equals(eq)
                        || link.sourceId().equals(eq)
                        || link.targetLabel().toLowerCase(Locale.ROOT).contains(eqLow)
                        || link.sourceLabel().toLowerCase(Locale.ROOT).contains(eqLow)
                        || link.sourcePath().toLowerCase(Locale.ROOT).contains(eqLow);
                if (!hit) continue;
            }
            filteredLinks.add(link);
            matchedNodeIds.add(link.sourceId());
            matchedNodeIds.add(link.targetId());
        }

        var filteredNodes = new ArrayList<AdvisoryOverlay.AdvisoryNode>();
        for (var node : full.nodes()) {
            if (!kf.isEmpty() && !node.kind().toUpperCase(Locale.ROOT).contains(kf)) continue;
            if (!pf.isEmpty() && !node.sourcePath().toLowerCase(Locale.ROOT).contains(pf)) continue;
            if (!eq.isEmpty()) {
                boolean hit = matchedNodeIds.contains(node.id())
                        || node.id().equals(eq)
                        || node.label().toLowerCase(Locale.ROOT).contains(eqLow)
                        || node.sourcePath().toLowerCase(Locale.ROOT).contains(eqLow);
                if (!hit) continue;
            }
            filteredNodes.add(node);
        }

        return summarize(full.graphIdentity(), filteredNodes, filteredLinks, full.redactedSecretsCount());
    }

    private static AdvisoryOverlay.Snapshot build(KnowledgeGraph graph) {
        var fileIndex = CapturedFileIndex.forGraph(graph);
        var allPaths = new LinkedHashSet<String>();
        for (var e : fileIndex.entries()) allPaths.add(e.path());

        // Index canonical graph symbols & routes + discover JPA entities from KnowledgeGraph declarations/annotations first
        var byFqn = new LinkedHashMap<String, MarkdownAdrExtractor.GraphSymbolRef>();
        var bySimpleName = new LinkedHashMap<String, List<MarkdownAdrExtractor.GraphSymbolRef>>();
        var byRoute = new LinkedHashMap<String, MarkdownAdrExtractor.GraphSymbolRef>();
        var entitiesByPath = new LinkedHashMap<String, List<MarkdownAdrExtractor.GraphSymbolRef>>();
        var pathByEntityId = new HashMap<String, String>();
        var javaDeclPaths = new HashSet<String>();
        var jpaCandidatePaths = new HashSet<String>();
        var docIdToPath = indexDocPaths(graph);
        boolean hasJavaAnnotationEvidence = false;

        for (var ctx : graph.contexts()) {
            for (var node : graph.nodes(ctx.identity())) {
                if ("ANNOTATION_USE".equals(node.kind())) {
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null || !"java.annotation-use".equals(ev.family())) continue;
                        hasJavaAnnotationEvidence = true;
                        String rawJson = ev.canonicalJson();
                        if (mentionsJpaPersistenceAnnotation(rawJson)) {
                            var obj = readSmall(ev);
                            if (obj.get("span") instanceof Map<?, ?> sp && sp.get("document") instanceof String docId) {
                                String p = docIdToPath.get(docId);
                                if (p != null && !p.isBlank()) jpaCandidatePaths.add(p);
                            }
                            if (obj.get("owner") instanceof String ownerId) {
                                String ownerPath = pathByEntityId.get(ownerId);
                                if (ownerPath != null && !ownerPath.isBlank()) jpaCandidatePaths.add(ownerPath);
                            }
                        }
                    }
                    continue;
                }
                if (!Set.of("TYPE", "CLASS", "INTERFACE", "RECORD", "ENUM", "ENDPOINT",
                        "BEAN_DEFINITION_CANDIDATE", "BEAN_PRODUCER",
                        "POLYGLOT_TYPE", "POLYGLOT_CLASS", "POLYGLOT_FUNCTION", "SERVICE_ENDPOINT")
                        .contains(node.kind())) {
                    continue;
                }
                SymbolMeta meta = extractSymbolMeta(graph, node, docIdToPath);
                if (meta == null) continue;
                var ref = new MarkdownAdrExtractor.GraphSymbolRef(
                        node.identity(), meta.label(), node.kind(), meta.path(), meta.route());
                if (meta.label() != null && !meta.label().isBlank()) {
                    byFqn.putIfAbsent(meta.label(), ref);
                    String simple = simpleNameOf(meta.label());
                    if (!simple.isBlank()) {
                        bySimpleName.computeIfAbsent(simple, k -> new ArrayList<>()).add(ref);
                    }
                }
                if (meta.route() != null && !meta.route().isBlank()) {
                    byRoute.putIfAbsent(meta.route(), ref);
                }
                if (meta.path() != null && !meta.path().isBlank()) {
                    entitiesByPath.computeIfAbsent(meta.path(), k -> new ArrayList<>()).add(ref);
                    pathByEntityId.putIfAbsent(node.identity(), meta.path());
                    if (meta.javaDecl()) javaDeclPaths.add(meta.path());
                }
            }
        }
        // Second pass for any JPA annotation whose owner was indexed after the ANNOTATION_USE node
        if (hasJavaAnnotationEvidence) {
            for (var ctx : graph.contexts()) {
                for (var node : graph.nodes(ctx.identity())) {
                    if (!"ANNOTATION_USE".equals(node.kind())) continue;
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null || !"java.annotation-use".equals(ev.family())) continue;
                        if (!mentionsJpaPersistenceAnnotation(ev.canonicalJson())) continue;
                        var obj = readSmall(ev);
                        if (obj.get("owner") instanceof String ownerId) {
                            String ownerPath = pathByEntityId.get(ownerId);
                            if (ownerPath != null && !ownerPath.isBlank()) jpaCandidatePaths.add(ownerPath);
                        }
                    }
                }
            }
        }

        // Collect JPA @Entity declarations deterministically ONLY when SQL files exist in the snapshot,
        // and skip CAS reads for non-JPA Java files when the graph already carries JavaParser annotation-use evidence.
        boolean hasSqlFiles = false;
        for (var entry : fileIndex.entries()) {
            if (entry.readable() && ("sql".equals(entry.language())
                    || entry.path().toLowerCase(Locale.ROOT).endsWith(".sql"))) {
                hasSqlFiles = true;
                break;
            }
        }
        var jpaEntities = new ArrayList<SqlDdlExtractor.JpaEntityInfo>();
        if (hasSqlFiles) {
            for (var entry : fileIndex.entries()) {
                if (!entry.readable()) continue;
                boolean isJava = "java".equals(entry.language());
                boolean isKotlin = "kotlin".equals(entry.language());
                if (!isJava && !isKotlin) continue;
                if (isJava && hasJavaAnnotationEvidence && javaDeclPaths.contains(entry.path())
                        && !jpaCandidatePaths.contains(entry.path())) {
                    continue;
                }
                var textOpt = fileIndex.readText(graph, entry);
                if (textOpt.isEmpty()) continue;
                String text = textOpt.orElseThrow();
                if (!text.contains("@Entity") && !text.contains("@Table") && !text.contains("@SecondaryTable")
                        && !text.contains("@JoinTable") && !text.contains("@jakarta.persistence.Entity")
                        && !text.contains("@javax.persistence.Entity")) {
                    continue;
                }
                var syms = entitiesByPath.getOrDefault(entry.path(), List.of());
                jpaEntities.addAll(SqlDdlExtractor.parseJpaEntitiesFromSource(syms, entry.path(), text));
            }
        }

        var nodes = new ArrayList<AdvisoryOverlay.AdvisoryNode>();
        var links = new ArrayList<AdvisoryOverlay.AdvisoryLink>();
        int redactedSecrets = 0;

        // Collect JS/TS workspace config files for JsWorkspaceAliasResolver
        var jsConfigs = new LinkedHashMap<String, String>();
        var jsConfigDigests = new LinkedHashMap<String, String>();

        for (var entry : fileIndex.entries()) {
            if (!entry.readable()) continue;
            String path = entry.path();
            String lower = path.toLowerCase(Locale.ROOT);
            String fileName = lower.substring(lower.lastIndexOf('/') + 1);
            String sha = entry.digest().map(ContentDigest::value).orElse("");

            // 1. Markdown / ADR
            if ("markdown".equals(entry.language()) || lower.endsWith(".md") || lower.endsWith(".mdx")) {
                var textOpt = fileIndex.readText(graph, entry);
                if (textOpt.isPresent()) {
                    var out = MarkdownAdrExtractor.extract(path, sha, textOpt.orElseThrow(),
                            byFqn, bySimpleName, byRoute, allPaths);
                    nodes.addAll(out.nodes());
                    links.addAll(out.links());
                    redactedSecrets += out.redactedSecretsCount();
                }
                continue;
            }

            // 2. SQL DDL <-> JPA
            if ("sql".equals(entry.language()) || lower.endsWith(".sql")) {
                var textOpt = fileIndex.readText(graph, entry);
                if (textOpt.isPresent()) {
                    var out = SqlDdlExtractor.extract(path, sha, textOpt.orElseThrow(), jpaEntities);
                    nodes.addAll(out.nodes());
                    links.addAll(out.links());
                }
                continue;
            }

            // 3. Terraform / HCL
            if ("terraform".equals(entry.language()) || lower.endsWith(".tf")) {
                var textOpt = fileIndex.readText(graph, entry);
                if (textOpt.isPresent()) {
                    var out = TerraformHclExtractor.extract(path, sha, textOpt.orElseThrow());
                    nodes.addAll(out.nodes());
                    links.addAll(out.links());
                    redactedSecrets += out.redactedSecretsCount();
                }
                continue;
            }

            // 4. MCP Config (.mcp.json / mcp.json)
            if (McpConfigExtractor.isMcpConfigPath(path)) {
                var textOpt = fileIndex.readText(graph, entry);
                if (textOpt.isPresent()) {
                    var out = McpConfigExtractor.extract(path, sha, textOpt.orElseThrow());
                    nodes.addAll(out.nodes());
                    links.addAll(out.links());
                    redactedSecrets += out.redactedSecretsCount();
                }
                continue;
            }

            // 5. JS/TS Workspace Alias configs (tsconfig, jsconfig, pnpm-workspace, package.json)
            if (((fileName.startsWith("tsconfig") || fileName.startsWith("jsconfig")) && fileName.endsWith(".json"))
                    || fileName.equals("pnpm-workspace.yaml") || fileName.equals("pnpm-workspace.yml")
                    || fileName.equals("package.json")) {
                var textOpt = fileIndex.readText(graph, entry);
                if (textOpt.isPresent()) {
                    jsConfigs.put(path, textOpt.orElseThrow());
                    jsConfigDigests.put(path, sha);
                }
            }
        }

        // Resolve cross-file synthetic sql:table:<name> and tf:<address> references to extracted advisory node IDs
        var sqlTableIdByBare = new LinkedHashMap<String, String>();
        var tfNodeIdByAddress = new LinkedHashMap<String, String>();
        for (var n : nodes) {
            if ("SQL_TABLE".equals(n.kind())) {
                Object tblName = n.metadata().get("tableName");
                if (tblName instanceof String t && !t.isBlank()) {
                    sqlTableIdByBare.putIfAbsent(t.toLowerCase(Locale.ROOT), n.id());
                }
            } else if (n.kind().startsWith("TF_")) {
                if (n.label() != null && !n.label().isBlank()) {
                    tfNodeIdByAddress.putIfAbsent(n.label(), n.id());
                }
            }
        }
        if (!sqlTableIdByBare.isEmpty() || !tfNodeIdByAddress.isEmpty()) {
            for (int li = 0; li < links.size(); li++) {
                var l = links.get(li);
                String nextSrc = resolveCrossFileTarget(l.sourceId(), sqlTableIdByBare, tfNodeIdByAddress);
                String nextDst = resolveCrossFileTarget(l.targetId(), sqlTableIdByBare, tfNodeIdByAddress);
                if (!nextSrc.equals(l.sourceId()) || !nextDst.equals(l.targetId())) {
                    links.set(li, new AdvisoryOverlay.AdvisoryLink(
                            l.id(), l.kind(), nextSrc, l.sourceLabel(), nextDst, l.targetLabel(), l.targetKind(),
                            l.sourcePath(), l.startLine(), l.endLine(), l.contentSha256(), l.matchBasis()));
                }
            }
        }

        // Materialize JS/TS workspace alias advisory nodes
        if (!jsConfigs.isEmpty()) {
            var ws = JsWorkspaceAliasResolver.build(jsConfigs);
            for (var rule : ws.aliases()) {
                String sha = jsConfigDigests.getOrDefault(rule.configPath(), "");
                String aid = MarkdownAdrExtractor.advisoryId("JS_WORKSPACE_ALIAS",
                        rule.configPath() + ":" + rule.pattern(), 1);
                nodes.add(new AdvisoryOverlay.AdvisoryNode(
                        aid, "JS_WORKSPACE_ALIAS", rule.pattern() + " -> " + String.join(", ", rule.targets()),
                        rule.configPath(), 1, 1, sha,
                        Map.of("pattern", rule.pattern(), "targets", rule.targets(), "sourceKind", rule.sourceKind())));
            }
            for (var exp : ws.packageExports().entrySet()) {
                String aid = MarkdownAdrExtractor.advisoryId("JS_PACKAGE_EXPORT", exp.getKey(), 1);
                nodes.add(new AdvisoryOverlay.AdvisoryNode(
                        aid, "JS_PACKAGE_EXPORT", exp.getKey() + " -> " + exp.getValue(),
                        exp.getValue(), 1, 1, "",
                        Map.of("specifier", exp.getKey(), "target", exp.getValue())));
            }
        }

        // Deduplicate nodes and links deterministically by id
        var uniqueNodes = new LinkedHashMap<String, AdvisoryOverlay.AdvisoryNode>();
        for (var n : nodes) uniqueNodes.putIfAbsent(n.id(), n);
        var uniqueLinks = new LinkedHashMap<String, AdvisoryOverlay.AdvisoryLink>();
        for (var l : links) uniqueLinks.putIfAbsent(l.id(), l);

        var sortedNodes = uniqueNodes.values().stream()
                .sorted(Comparator.comparing(AdvisoryOverlay.AdvisoryNode::kind)
                        .thenComparing(AdvisoryOverlay.AdvisoryNode::sourcePath)
                        .thenComparingInt(AdvisoryOverlay.AdvisoryNode::startLine)
                        .thenComparing(AdvisoryOverlay.AdvisoryNode::id))
                .toList();
        var sortedLinks = uniqueLinks.values().stream()
                .sorted(Comparator.comparing(AdvisoryOverlay.AdvisoryLink::kind)
                        .thenComparing(AdvisoryOverlay.AdvisoryLink::sourcePath)
                        .thenComparingInt(AdvisoryOverlay.AdvisoryLink::startLine)
                        .thenComparing(AdvisoryOverlay.AdvisoryLink::id))
                .toList();

        return summarize(graph.identity().value(), sortedNodes, sortedLinks, redactedSecrets);
    }

    private static String resolveCrossFileTarget(
            String id, Map<String, String> sqlTableIdByBare, Map<String, String> tfNodeIdByAddress) {
        if (id == null) return "";
        if (id.startsWith("sql:table:")) {
            String key = id.substring("sql:table:".length()).toLowerCase(Locale.ROOT);
            return sqlTableIdByBare.getOrDefault(key, id);
        }
        if (id.startsWith("tf:")) {
            String addr = id.substring("tf:".length());
            return tfNodeIdByAddress.getOrDefault(addr, id);
        }
        return id;
    }

    private static AdvisoryOverlay.Snapshot summarize(
            String graphIdentity,
            List<AdvisoryOverlay.AdvisoryNode> nodes,
            List<AdvisoryOverlay.AdvisoryLink> links,
            int redactedSecretsCount) {
        var nodeCounts = new TreeMap<String, Integer>();
        for (var n : nodes) nodeCounts.merge(n.kind(), 1, Integer::sum);
        var linkCounts = new TreeMap<String, Integer>();
        for (var l : links) linkCounts.merge(l.kind(), 1, Integer::sum);
        return new AdvisoryOverlay.Snapshot(
                AdvisoryOverlay.SCHEMA, AdvisoryOverlay.DERIVATION, AdvisoryOverlay.TRUST,
                graphIdentity, nodes, links, nodeCounts, linkCounts, redactedSecretsCount);
    }

    private record SymbolMeta(String label, String path, String route, boolean javaDecl) {}

    private static boolean mentionsJpaPersistenceAnnotation(String canonicalJson) {
        if (canonicalJson == null || canonicalJson.isEmpty()) return false;
        return canonicalJson.contains("@Entity") || canonicalJson.contains("Entity\"")
                || canonicalJson.contains("@Table") || canonicalJson.contains("Table\"")
                || canonicalJson.contains("@SecondaryTable") || canonicalJson.contains("SecondaryTable")
                || canonicalJson.contains("@JoinTable") || canonicalJson.contains("JoinTable")
                || canonicalJson.contains("jakarta.persistence.") || canonicalJson.contains("javax.persistence.");
    }

    private static SymbolMeta extractSymbolMeta(
            KnowledgeGraph graph, KnowledgeGraph.Node node, Map<String, String> docIdToPath) {
        String label = null, path = null, route = null;
        boolean javaDecl = false;
        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            var obj = readSmall(ev);
            switch (ev.family()) {
                case "java.declaration" -> {
                    javaDecl = true;
                    if (obj.get("entity") instanceof Map<?, ?> ent) {
                        if (ent.get("canonicalName") instanceof String cn && !cn.isBlank()) {
                            label = RepoMapBuilder.readableCanonicalName(cn);
                        } else if (ent.get("qualifiedName") instanceof String q && !q.isBlank()) {
                            label = RepoMapBuilder.readableCanonicalName(q);
                        } else if (ent.get("name") instanceof String nm && !nm.isBlank()) {
                            label = RepoMapBuilder.readableCanonicalName(nm);
                        }
                        if (ent.get("declaration") instanceof Map<?, ?> sp && sp.get("document") instanceof String docId) {
                            path = docIdToPath.get(docId);
                        }
                    }
                    if (path == null && obj.get("declaration") instanceof Map<?, ?> sp && sp.get("document") instanceof String docId) {
                        path = docIdToPath.get(docId);
                    }
                }
                case "spring.endpoint-declaration" -> {
                    if (obj.get("route") instanceof Map<?, ?> r && r.get("path") instanceof String rp) {
                        route = rp;
                        if (label == null) label = rp;
                    }
                    if (obj.get("span") instanceof Map<?, ?> sp && sp.get("document") instanceof String docId) {
                        path = docIdToPath.get(docId);
                    }
                }
                case "spring.candidate" -> {
                    if (obj.get("declaredNameKey") instanceof Map<?, ?> dk && dk.get("primary") instanceof String p && label == null) {
                        label = p;
                    }
                }
                case "polyglot.source-syntax" -> {
                    Map<?, ?> fields = obj.get("fields") instanceof Map<?, ?> f ? f : obj;
                    if (fields.get("name") instanceof String n && !n.isBlank() && label == null) label = n;
                    if (fields.get("path") instanceof String p && !p.isBlank() && path == null) {
                        path = CapturedFileIndex.normalizePath(p);
                    }
                    if (fields.get("route") instanceof String r && !r.isBlank() && route == null) route = r;
                }
                default -> {}
            }
        }
        return (label == null && route == null) ? null : new SymbolMeta(label, path, route, javaDecl);
    }

    private static Map<String, String> indexDocPaths(KnowledgeGraph graph) {
        var map = new HashMap<String, String>();
        for (var node : graph.nodes(graph.snapshot().value())) {
            if (!"SOURCE_DOCUMENT".equals(node.kind())) continue;
            for (var ref : node.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null) continue;
                var obj = readSmall(ev);
                if ("source.document".equals(ev.family()) && obj.get("path") instanceof String p) {
                    map.putIfAbsent(node.identity(), CapturedFileIndex.normalizePath(p));
                    break;
                }
                if ("source.bytes".equals(ev.family()) && obj.get("document") instanceof Map<?, ?> d
                        && d.get("path") instanceof String p) {
                    map.putIfAbsent(node.identity(), CapturedFileIndex.normalizePath(p));
                    break;
                }
            }
        }
        return map;
    }

    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 && dot < fqn.length() - 1 ? fqn.substring(dot + 1) : fqn;
    }

    private static String simpleStem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Map<String, Object> readSmall(GraphEvidence ev) {
        if (ev.canonicalJson().length() > 131_072) return Map.of();
        try {
            return GraphJson.object(GraphJson.read(ev.canonicalJson(), SMALL_JSON));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }
}
