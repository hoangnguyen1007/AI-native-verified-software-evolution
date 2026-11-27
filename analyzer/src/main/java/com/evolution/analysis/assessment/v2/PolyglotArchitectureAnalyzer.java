package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * Polyglot single-context and cross-service architecture analyzer for Architecture Scoring v2.
 *
 * <p>Cross-service metrics explicitly preserve {@code STATIC_CANDIDATE_TOPOLOGY (runtimeObserved: false)}
 * provenance and NEVER penalize external third-party URLs ({@code HTTP_URI_NO_CANDIDATE}).
 */
public final class PolyglotArchitectureAnalyzer {
    private static final GraphJson.Limits READ_LIMITS = new GraphJson.Limits(2_000_000, 64, 50_000);
    private static final int SYNC_FANOUT_BUDGET = 3;
    private static final int SYNC_FANOUT_SPAN = 5;
    private static final BigDecimal ASYNC_DISCOUNT = new BigDecimal("0.85");
    private static final BigDecimal ASYNC_SHARE_THRESHOLD = new BigDecimal("0.30");

    public record CrossServiceMetrics(
            boolean applicable,
            int serviceCount,
            int syncEdgeCount,
            int asyncEdgeCount,
            Optional<BigDecimal> syncRelativeCyclicity,
            int cyclicServiceSccCount,
            List<ArchitectureAssessmentV2.EvidenceWitness> cycleWitnesses,
            Optional<BigDecimal> syncCouplingExcess,
            List<ArchitectureAssessmentV2.EvidenceWitness> couplingWitnesses,
            Optional<BigDecimal> contractHygieneRisk,
            int ambiguousRouteCount,
            int matchedHttpCallCount,
            boolean formalSchemasPresent,
            List<ArchitectureAssessmentV2.EvidenceWitness> contractWitnesses) {

        public static CrossServiceMetrics notApplicable() {
            return new CrossServiceMetrics(false, 0, 0, 0, Optional.empty(), 0, List.of(),
                    Optional.empty(), List.of(), Optional.empty(), 0, 0, false, List.of());
        }
    }

    /**
     * Extracts a deterministic file/module structural graph from a {@code polyglot.context}
     * (Tree-sitter syntax nodes and local import/call dependencies) for {@link StructuralTopologyAnalyzer}.
     */
    public static StructuralTopologyAnalyzer.StructuralGraph extractPolyglotStructuralGraph(
            KnowledgeGraph graph, String polyglotContextId, Runnable checkpoint) {
        checkpoint.run();
        var fileNodes = new TreeMap<String, String>(); // path -> nodeId
        var nodeIdToPath = new HashMap<String, String>();
        var nodeEvidence = new HashMap<String, List<ContentDigest>>();
        var memberCounts = new HashMap<String, Integer>();
        var importEdgesRaw = new ArrayList<String[]>(); // [sourcePath, rawImportTarget, edgeId]

        for (var node : graph.nodes(polyglotContextId)) {
            for (var ref : node.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null || !"polyglot.source-syntax".equals(ev.family())) continue;
                try {
                    var root = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                    var fields = root.get("fields") instanceof Map<?, ?> f ? GraphJson.object(f) : root;
                    String path = decodePath(fields.get("path"));
                    if (path.isEmpty()) continue;
                    fileNodes.putIfAbsent(path, KnowledgeGraph.derivedIdentity("polyglot-file", List.of(polyglotContextId, path)));
                    String fileId = fileNodes.get(path);
                    nodeIdToPath.put(node.identity(), path);
                    nodeEvidence.computeIfAbsent(fileId, k -> new ArrayList<>()).add(ref);
                    if (fields.get("fact") instanceof Map<?, ?> rawFact) {
                        var fact = GraphJson.object(rawFact);
                        String category = fact.get("category") instanceof String s ? s : "";
                        String name = fact.get("name") instanceof String s ? s : "";
                        if (Set.of("FUNCTION", "METHOD", "CLASS", "TYPE", "DECLARATION").contains(category.toUpperCase(Locale.ROOT))) {
                            memberCounts.merge(fileId, 1, Integer::sum);
                        } else if ("IMPORT".equalsIgnoreCase(category) && !name.isBlank()) {
                            importEdgesRaw.add(new String[]{path, cleanQuote(name), node.identity()});
                        }
                    }
                } catch (RuntimeException ignored) {}
            }
        }
        checkpoint.run();

        // Build lookup index by normalized path stem, package directories, and alias roots
        var pathByStem = new HashMap<String, String>();
        var filesByDir = new HashMap<String, List<String>>();
        var commonSourceRoots = new LinkedHashSet<String>();
        for (String path : fileNodes.keySet()) {
            String norm = path.replace('\\', '/');
            pathByStem.put(norm, path);
            int slash = norm.lastIndexOf('/');
            if (slash > 0) {
                String dir = norm.substring(0, slash);
                filesByDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(path);
                if (norm.startsWith("src/") || norm.contains("/src/")) {
                    int idx = norm.indexOf("src/");
                    commonSourceRoots.add(norm.substring(0, idx + 4));
                } else if (norm.startsWith("app/") || norm.contains("/app/")) {
                    int idx = norm.indexOf("app/");
                    commonSourceRoots.add(norm.substring(0, idx + 4));
                } else if (norm.startsWith("lib/") || norm.contains("/lib/")) {
                    int idx = norm.indexOf("lib/");
                    commonSourceRoots.add(norm.substring(0, idx + 4));
                }
            }
            int dot = norm.lastIndexOf('.');
            if (dot > slash) {
                pathByStem.putIfAbsent(norm.substring(0, dot), path);
            }
            if (norm.endsWith("/index.ts") || norm.endsWith("/index.tsx")
                    || norm.endsWith("/index.js") || norm.endsWith("/index.jsx")
                    || norm.endsWith("/index.mjs") || norm.endsWith("/index.cjs")
                    || norm.endsWith("/__init__.py") || norm.endsWith("/mod.rs")) {
                pathByStem.putIfAbsent(norm.substring(0, slash), path);
            }
        }
        // Index Go package directories (unique directory suffix -> deterministic first non-test .go file)
        var goDirBySuffix = new HashMap<String, String>();
        var ambiguousGoSuffixes = new HashSet<String>();
        for (var entry : filesByDir.entrySet()) {
            String dir = entry.getKey();
            String goRep = entry.getValue().stream()
                    .filter(p -> p.endsWith(".go") && !p.endsWith("_test.go"))
                    .findFirst().orElse(null);
            if (goRep == null) continue;
            pathByStem.putIfAbsent(dir, goRep);
            // Index trailing path suffixes (at least 1 segment after module prefix) for Go module imports
            String[] segs = dir.split("/");
            for (int start = 0; start < segs.length; start++) {
                String suffix = String.join("/", Arrays.copyOfRange(segs, start, segs.length));
                if (ambiguousGoSuffixes.contains(suffix)) continue;
                String prev = goDirBySuffix.putIfAbsent(suffix, goRep);
                if (prev != null && !prev.equals(goRep)) {
                    goDirBySuffix.remove(suffix);
                    ambiguousGoSuffixes.add(suffix);
                }
            }
        }
        var rootPrefixes = List.copyOf(commonSourceRoots);

        var nodes = new ArrayList<StructuralTopologyAnalyzer.NodeInfo>(fileNodes.size());
        for (var entry : fileNodes.entrySet()) {
            String path = entry.getKey();
            String fileId = entry.getValue();
            int slash = path.lastIndexOf('/');
            String dir = slash > 0 ? path.substring(0, slash) : "(root)";
            String fileName = slash >= 0 ? path.substring(slash + 1) : path;
            String pkgName = dir.replace('/', '.');
            String topMod = path.contains("/") ? path.substring(0, path.indexOf('/')) : "(root)";
            var evList = nodeEvidence.getOrDefault(fileId, List.of());
            nodes.add(new StructuralTopologyAnalyzer.NodeInfo(
                    fileId, fileName, "dir:" + dir, pkgName, "mod:" + topMod, topMod,
                    "CLASS", false, memberCounts.getOrDefault(fileId, 0), Set.of(),
                    evList.size() > 3 ? evList.subList(0, 3) : evList));
        }

        var edges = new ArrayList<StructuralTopologyAnalyzer.EdgeInfo>();
        var seen = new HashSet<String>();
        for (String[] raw : importEdgesRaw) {
            String fromPath = raw[0];
            String targetSpec = raw[1];
            String resolved = resolveLocalImport(fromPath, targetSpec, pathByStem, goDirBySuffix, rootPrefixes);
            if (resolved == null || resolved.equals(fromPath)) continue;
            String srcId = fileNodes.get(fromPath);
            String dstId = fileNodes.get(resolved);
            if (srcId != null && dstId != null && seen.add(srcId + "->" + dstId)) {
                edges.add(new StructuralTopologyAnalyzer.EdgeInfo(
                        KnowledgeGraph.derivedIdentity("polyglot-import", List.of(srcId, dstId)),
                        srcId, dstId, List.of()));
            }
        }

        // Also include explicit graph edges inside the polyglot context between file/symbol nodes
        for (var edge : graph.edges(polyglotContextId)) {
            if (!edge.target().state().equals(KnowledgeGraph.TargetState.RESOLVED)) continue;
            String fromPath = nodeIdToPath.get(edge.source());
            String toPath = nodeIdToPath.get(edge.target().identities().getFirst());
            if (fromPath == null || toPath == null || fromPath.equals(toPath)) continue;
            String srcId = fileNodes.get(fromPath);
            String dstId = fileNodes.get(toPath);
            if (srcId != null && dstId != null && seen.add(srcId + "->" + dstId)) {
                edges.add(new StructuralTopologyAnalyzer.EdgeInfo(edge.identity(), srcId, dstId, edge.evidence()));
            }
        }
        return new StructuralTopologyAnalyzer.StructuralGraph(nodes, edges);
    }

    /** Analyzes repository-wide cross-service HTTP/RPC/Event topology at the snapshot root. */
    public static CrossServiceMetrics analyzeCrossService(KnowledgeGraph graph, int maxWitnesses, Runnable checkpoint) {
        checkpoint.run();
        int limit = Math.max(1, Math.min(25, maxWitnesses));
        String root = graph.snapshot().value();

        // Map endpoint-reference nodes back to their owning service/context
        var nodeToService = new HashMap<String, String>();
        var serviceLabels = new TreeMap<String, String>();
        for (var node : graph.nodes(root)) {
            for (var ref : node.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null) continue;
                String fam = ev.family();
                if (Set.of("architecture.service-endpoint-reference", "polyglot.http-call-reference",
                        "polyglot.http-endpoint-reference", "polyglot.event-producer-reference",
                        "polyglot.event-consumer-reference").contains(fam)) {
                    try {
                        var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                        String ctx = obj.get("sourceContext") instanceof String s ? s
                                : obj.get("context") instanceof String s2 ? s2 : null;
                        if (ctx != null && !ctx.isBlank()) {
                            nodeToService.put(node.identity(), ctx);
                            serviceLabels.putIfAbsent(ctx, shortServiceLabel(graph, ctx));
                        }
                    } catch (RuntimeException ignored) {}
                }
            }
        }

        var syncPairs = new TreeMap<String, Set<String>>();
        var asyncPairs = new TreeMap<String, Set<String>>();
        var allServices = new TreeSet<String>();
        int syncEdges = 0;
        int asyncEdges = 0;

        for (var edge : graph.edges(root)) {
            boolean isSync = "CALLS_SERVICE".equals(edge.kind()) || "INVOKES_RPC".equals(edge.kind());
            boolean isAsync = "PUBLISHES_EVENT".equals(edge.kind());
            if (!isSync && !isAsync) continue;
            String fromSvc = nodeToService.getOrDefault(edge.source(), edge.source());
            for (String target : edge.target().identities()) {
                String toSvc = nodeToService.getOrDefault(target, target);
                if (fromSvc.equals(toSvc)) continue;
                allServices.add(fromSvc);
                allServices.add(toSvc);
                serviceLabels.putIfAbsent(fromSvc, shortServiceLabel(graph, fromSvc));
                serviceLabels.putIfAbsent(toSvc, shortServiceLabel(graph, toSvc));
                if (isSync) {
                    if (syncPairs.computeIfAbsent(fromSvc, k -> new TreeSet<>()).add(toSvc)) syncEdges++;
                } else {
                    if (asyncPairs.computeIfAbsent(fromSvc, k -> new TreeSet<>()).add(toSvc)) asyncEdges++;
                }
            }
        }

        // Contract & Route Hygiene (from polyglot.http-uri-match and declarative schema presence)
        int matchedCalls = 0;
        int ambiguousRoutes = 0;
        var contractWitnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        boolean hasFormalSchemas = false;

        for (var ev : graph.evidence()) {
            if ("polyglot.http-uri-match".equals(ev.family())) {
                try {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                    if (obj.get("candidates") instanceof List<?> cands && !cands.isEmpty()) {
                        matchedCalls++;
                        if (cands.size() > 1) {
                            ambiguousRoutes++;
                            if (contractWitnesses.size() < limit) {
                                String path = obj.get("path") instanceof String s ? s : "(uri)";
                                contractWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                                        "AMBIGUOUS_SERVICE_ROUTE", ev.identity().value(), path,
                                        "HTTP call matches " + cands.size() + " distinct candidate endpoints [STATIC_CANDIDATE]",
                                        List.of(ev.identity())));
                            }
                        }
                    }
                } catch (RuntimeException ignored) {}
            } else if ("polyglot.source-syntax".equals(ev.family())) {
                String json = ev.canonicalJson();
                if (json.contains("\"openapi\"") || json.contains("\"protobuf\"") || json.contains("\"graphql\"")) {
                    hasFormalSchemas = true;
                }
            }
        }

        int svcCount = allServices.size();
        if (svcCount < 2 || (syncEdges + asyncEdges == 0 && matchedCalls == 0)) {
            return CrossServiceMetrics.notApplicable();
        }
        checkpoint.run();

        var svcList = List.copyOf(allServices);
        var svcIdx = new HashMap<String, Integer>(svcCount * 2);
        for (int i = 0; i < svcCount; i++) svcIdx.put(svcList.get(i), i);

        var adj = new ArrayList<int[]>(svcCount);
        int[] syncOut = new int[svcCount];
        for (int i = 0; i < svcCount; i++) {
            var targets = syncPairs.getOrDefault(svcList.get(i), Set.of());
            int[] arr = new int[targets.size()];
            int k = 0;
            for (String t : targets) arr[k++] = svcIdx.get(t);
            adj.add(arr);
            syncOut[i] = arr.length;
        }

        // 1. Cross-service synchronous cyclicity
        Optional<BigDecimal> syncRc = Optional.empty();
        int cyclicSccs = 0;
        var cycleWitnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        if (syncEdges > 0) {
            var scc = StructuralTopologyAnalyzer.tarjanScc(svcCount, adj);
            long sq = 0;
            for (int[] comp : scc.components()) {
                if (comp.length > 1) {
                    cyclicSccs++;
                    sq += (long) comp.length * (long) comp.length;
                    if (cycleWitnesses.size() < limit) {
                        var names = new ArrayList<String>();
                        for (int idx : comp) names.add(serviceLabels.getOrDefault(svcList.get(idx), svcList.get(idx)));
                        cycleWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                                "CROSS_SERVICE_SYNC_CYCLE", svcList.get(comp[0]),
                                String.join(" <-> ", names),
                                "Synchronous cross-service cycle of " + comp.length + " services [STATIC_CANDIDATE]",
                                List.of()));
                    }
                }
            }
            syncRc = Optional.of(sq == 0 ? BigDecimal.ZERO
                    : CalibrationCurve.divide(CalibrationCurve.sqrt(BigDecimal.valueOf(sq)), BigDecimal.valueOf(Math.max(svcCount, 5))));
        }

        // 2. Synchronous fan-out coupling excess (with event-driven choreography discount)
        Optional<BigDecimal> syncCouplingExcess = Optional.empty();
        var couplingWitnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        if (syncEdges + asyncEdges > 0) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < svcCount; i++) {
                if (syncOut[i] > SYNC_FANOUT_BUDGET) {
                    BigDecimal ex = CalibrationCurve.divide(syncOut[i] - SYNC_FANOUT_BUDGET, SYNC_FANOUT_SPAN).min(BigDecimal.ONE);
                    sum = sum.add(ex);
                    if (couplingWitnesses.size() < limit) {
                        couplingWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                                "HIGH_SYNC_SERVICE_FANOUT", svcList.get(i),
                                serviceLabels.getOrDefault(svcList.get(i), svcList.get(i)),
                                "Calls " + syncOut[i] + " distinct services synchronously (budget <= " + SYNC_FANOUT_BUDGET + ")",
                                List.of()));
                    }
                }
            }
            BigDecimal avg = CalibrationCurve.divide(sum, BigDecimal.valueOf(svcCount));
            BigDecimal asyncShare = CalibrationCurve.divide(asyncEdges, syncEdges + asyncEdges);
            if (asyncShare.compareTo(ASYNC_SHARE_THRESHOLD) >= 0) {
                avg = CalibrationCurve.round(avg.multiply(ASYNC_DISCOUNT), CalibrationCurve.SCALE);
            }
            syncCouplingExcess = Optional.of(avg);
        }

        // 3. Contract & Route Hygiene
        Optional<BigDecimal> contractRisk = Optional.empty();
        if (matchedCalls > 0) {
            BigDecimal ambRatio = CalibrationCurve.divide(ambiguousRoutes, matchedCalls);
            BigDecimal schemaRisk = hasFormalSchemas ? BigDecimal.ZERO : new BigDecimal("0.08");
            contractRisk = Optional.of(ambRatio.add(schemaRisk).min(BigDecimal.ONE));
            if (!hasFormalSchemas && contractWitnesses.size() < limit) {
                contractWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                        "MISSING_FORMAL_API_CONTRACT", root, "Cross-service HTTP/RPC boundaries",
                        "No OpenAPI, Protobuf, or GraphQL contract schema declarations detected across " + svcCount + " interacting services",
                        List.of()));
            }
        }

        return new CrossServiceMetrics(
                true, svcCount, syncEdges, asyncEdges,
                syncRc, cyclicSccs, cycleWitnesses,
                syncCouplingExcess, couplingWitnesses,
                contractRisk, ambiguousRoutes, matchedCalls, hasFormalSchemas, contractWitnesses);
    }

    private static String shortServiceLabel(KnowledgeGraph graph, String contextId) {
        for (var ctx : graph.contexts()) {
            if (!ctx.identity().equals(contextId)) continue;
            var ev = graph.evidence(ctx.evidence()).orElse(null);
            if (ev == null) break;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                if (obj.get("request") instanceof Map<?, ?> req) {
                    var r = GraphJson.object(req);
                    String mod = r.get("module") instanceof String s ? s : "";
                    String lang = r.get("language") instanceof String s ? s : "";
                    if (!mod.isEmpty() && !lang.isEmpty()) return mod + " (" + lang + ")";
                    if (!mod.isEmpty()) return mod;
                    if (!lang.isEmpty()) return lang;
                }
                if (obj.get("configuration") instanceof Map<?, ?> cfg
                        && GraphJson.object(cfg).get("values") instanceof Map<?, ?> vals
                        && vals.get("java.module") instanceof String m) {
                    return m;
                }
            } catch (RuntimeException ignored) {}
        }
        return contextId.length() > 24 ? contextId.substring(0, 24) + "..." : contextId;
    }

    private static String decodePath(Object rawPath) {
        if (!(rawPath instanceof String s) || s.isBlank()) return "";
        if (s.contains("/") || s.contains(".")) return s.replace('\\', '/');
        try {
            byte[] decoded = Base64.getDecoder().decode(s);
            return new String(decoded, java.nio.charset.StandardCharsets.UTF_8).replace('\\', '/');
        } catch (IllegalArgumentException notBase64) {
            return s.replace('\\', '/');
        }
    }

    private static String cleanQuote(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'"))
                || (s.startsWith("`") && s.endsWith("`")))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static String resolveLocalImport(
            String fromPath,
            String spec,
            Map<String, String> pathByStem,
            Map<String, String> goDirBySuffix,
            List<String> rootPrefixes) {
        if (spec == null || spec.isBlank()) return null;
        String trimmed = spec.trim();
        // Normalize raw Python "from ..models import X" or JS "import ... from '...'" statements if unparsed
        if (trimmed.startsWith("from ")) {
            int imp = trimmed.indexOf(" import ");
            if (imp > 5) {
                trimmed = trimmed.substring(5, imp).trim();
            }
        } else if (trimmed.startsWith("import ")) {
            int fromIdx = trimmed.lastIndexOf(" from ");
            if (fromIdx > 0) {
                trimmed = cleanQuote(trimmed.substring(fromIdx + 6));
            } else {
                String rest = trimmed.substring(7).trim();
                int asIdx = rest.indexOf(" as ");
                if (asIdx > 0) rest = rest.substring(0, asIdx).trim();
                int comma = rest.indexOf(',');
                if (comma > 0) rest = rest.substring(0, comma).trim();
                trimmed = cleanQuote(rest);
            }
        }
        if (trimmed.isEmpty()) return null;

        int slash = fromPath.lastIndexOf('/');
        String baseDir = slash >= 0 ? fromPath.substring(0, slash) : "";

        // 1. Python dot-relative imports (e.g., ".utils", "..models.user", "...core")
        if (fromPath.endsWith(".py") && trimmed.startsWith(".") && !trimmed.startsWith("./") && !trimmed.startsWith("../")) {
            int dots = 0;
            while (dots < trimmed.length() && trimmed.charAt(dots) == '.') dots++;
            String remainder = trimmed.substring(dots).replace('.', '/');
            var dirStack = new ArrayDeque<String>();
            if (!baseDir.isEmpty()) {
                for (String seg : baseDir.split("/")) if (!seg.isEmpty()) dirStack.addLast(seg);
            }
            for (int up = 1; up < dots && !dirStack.isEmpty(); up++) {
                dirStack.removeLast();
            }
            String parentPrefix = String.join("/", dirStack);
            String joined = remainder.isEmpty() ? parentPrefix
                    : (parentPrefix.isEmpty() ? remainder : parentPrefix + "/" + remainder);
            String found = pathByStem.get(joined);
            if (found != null) return found;
            // Walk up trailing symbol segment (e.g., from ..models.user import User)
            int lastSl = joined.lastIndexOf('/');
            return lastSl > 0 ? pathByStem.get(joined.substring(0, lastSl)) : null;
        }

        // 2. Unix relative imports (./foo, ../bar/baz)
        if (trimmed.startsWith("./") || trimmed.startsWith("../") || trimmed.equals(".") || trimmed.equals("..")) {
            String joined = baseDir.isEmpty() ? trimmed : baseDir + "/" + trimmed;
            String normalized = normalizeUnixPath(joined);
            String found = lookupWithJsExtensionStrip(normalized, pathByStem);
            if (found != null) return found;
            return null;
        }

        // 3. TS/JS path aliases (@/foo, ~/foo) and src/app/lib root-relative imports
        if (trimmed.startsWith("@/") || trimmed.startsWith("~/")) {
            String sub = trimmed.substring(2);
            String directAlias = lookupWithJsExtensionStrip(sub, pathByStem);
            if (directAlias != null) return directAlias;
            for (String rootPrefix : rootPrefixes) {
                String candidate = lookupWithJsExtensionStrip(rootPrefix + sub, pathByStem);
                if (candidate != null) return candidate;
            }
        }

        // 4. Direct or dotted module path (Python, JS/TS baseUrl, Rust, etc.)
        String direct = lookupWithJsExtensionStrip(trimmed, pathByStem);
        if (direct != null) return direct;
        String slashSpec = trimmed.replace('.', '/');
        String bySlash = pathByStem.get(slashSpec);
        if (bySlash != null) return bySlash;
        for (String rootPrefix : rootPrefixes) {
            String inRoot = lookupWithJsExtensionStrip(rootPrefix + trimmed, pathByStem);
            if (inRoot != null) return inRoot;
            String inRootSlash = pathByStem.get(rootPrefix + slashSpec);
            if (inRootSlash != null) return inRootSlash;
        }

        // 5. Go module-prefixed local imports (e.g. "github.com/org/repo/internal/auth" -> "internal/auth")
        if (fromPath.endsWith(".go") && trimmed.contains("/")) {
            String[] segs = trimmed.split("/");
            for (int start = 1; start < segs.length; start++) {
                String suffix = String.join("/", Arrays.copyOfRange(segs, start, segs.length));
                String matched = goDirBySuffix.get(suffix);
                if (matched != null) return matched;
            }
        }
        return null;
    }

    private static String lookupWithJsExtensionStrip(String candidate, Map<String, String> pathByStem) {
        String direct = pathByStem.get(candidate);
        if (direct != null) return direct;
        // ESM TypeScript imports often write "./foo.js" to refer to "./foo.ts" / "./foo.tsx"
        if (candidate.endsWith(".js") || candidate.endsWith(".mjs") || candidate.endsWith(".cjs")) {
            int dot = candidate.lastIndexOf('.');
            return pathByStem.get(candidate.substring(0, dot));
        }
        return null;
    }

    private static String normalizeUnixPath(String path) {
        var stack = new ArrayDeque<String>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                if (!stack.isEmpty()) stack.removeLast();
            } else {
                stack.addLast(seg);
            }
        }
        return String.join("/", stack);
    }

    private PolyglotArchitectureAnalyzer() {}
}
