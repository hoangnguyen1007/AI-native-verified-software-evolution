package com.evolution.backend.query.advisory;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.backend.query.CapturedFileIndex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Passively reads static runtime service-dependency exports (Ripple {@code post.csv} {@code src,dst}
 * tables or OpenTelemetry / Jaeger Service Map JSON) and compares them against a canonical static
 * {@link KnowledgeGraph} to emit quarantined static-vs-runtime architectural drift findings.
 *
 * <p><b>Strict Quarantine &amp; Safety Invariants (I1–I5):</b>
 * <ul>
 *   <li><b>I1 (Passive Read-Only):</b> Reads only immutable static CSV/JSON bytes; never executes
 *       target code, probes networks, or invokes external processes.</li>
 *   <li><b>I2/I4 (Quarantine, {@code canonicalLeakage = 0}):</b> Every emitted finding is strictly
 *       tagged with {@code trust = "RUNTIME_OBSERVED"} and {@code quarantined = true}. NEVER mutates
 *       {@link KnowledgeGraph}, Leiden communities, policy evaluations, or Architecture Score v2.</li>
 *   <li><b>I2 (Determinism):</b> All static/runtime edges, findings, and metrics are canonically
 *       normalized and deterministically sorted.</li>
 *   <li><b>I3 (Closed Denominator):</b> Malformed rows/entries are reported in {@code warnings}
 *       rather than silently discarded.</li>
 *   <li><b>I5 (Generalization):</b> Purely generic service-name normalization and set-difference
 *       algebra with zero repository- or benchmark-specific names.</li>
 * </ul>
 */
public final class RuntimeDriftOverlayExtractor {
    public static final String SCHEMA = "advisory.runtime-drift:v1";
    /** Quarantined runtime-observed epistemic tier (never enters canonical KnowledgeGraph). */
    public static final String TRUST_TIER = "RUNTIME_OBSERVED";
    public static final String DERIVATION = "STATIC_VS_RUNTIME_DIFF";

    private static final long MAX_RUNTIME_FILE_BYTES = 32L * 1024L * 1024L;
    private static final GraphJson.Limits JSON_LIMITS = new GraphJson.Limits(16 * 1024 * 1024, 32, 65_536);
    private static final GraphJson.Limits EVIDENCE_LIMITS = new GraphJson.Limits(131_072, 32, 8_192);

    private static final Set<String> SOURCE_HEADERS = Set.of(
            "src", "source", "caller", "client", "from", "parent", "service_from", "src_service");
    private static final Set<String> TARGET_HEADERS = Set.of(
            "dst", "dest", "destination", "target", "callee", "server", "to", "child", "service_to", "dst_service");
    private static final Set<String> WEIGHT_HEADERS = Set.of(
            "weight", "calls", "call_count", "callcount", "count", "requests", "request_count", "rps", "value");

    public enum InputFormat {
        RIPPLE_CSV,
        OTEL_SERVICE_MAP_JSON
    }

    public enum DriftClassification {
        /** {@code E_runtime \ E_static}: runtime call absent from static architecture graph. */
        SHADOW_RUNTIME_DEPENDENCY,
        /** {@code E_static \ E_runtime}: static architecture edge not exercised in observed runtime trace. */
        UNEXERCISED_STATIC_PATH,
        /** {@code E_runtime ∩ E_static}: edge corroborated by both static architecture and runtime trace. */
        CONCORDANT_RUNTIME_EDGE
    }

    public record EdgePair(String source, String target) implements Comparable<EdgePair> {
        public EdgePair {
            source = Objects.requireNonNull(source, "source");
            target = Objects.requireNonNull(target, "target");
        }

        @Override
        public int compareTo(EdgePair other) {
            int c = this.source.compareTo(other.source);
            return c != 0 ? c : this.target.compareTo(other.target);
        }

        public String label() {
            return source + "->" + target;
        }
    }

    public record RuntimeEdge(
            String source,
            String target,
            OptionalDouble weight,
            int sourceLine,
            String rawSource,
            String rawTarget) {
        public RuntimeEdge {
            source = Objects.requireNonNull(source, "source");
            target = Objects.requireNonNull(target, "target");
            weight = weight == null ? OptionalDouble.empty() : weight;
            sourceLine = Math.max(1, sourceLine);
            rawSource = rawSource == null ? source : rawSource;
            rawTarget = rawTarget == null ? target : rawTarget;
        }
    }

    public record DriftFinding(
            String id,
            DriftClassification classification,
            String source,
            String target,
            String sourceNodeId,
            String targetNodeId,
            OptionalDouble runtimeWeight,
            int runtimeLine,
            String sourcePath,
            String contentSha256,
            String trust,
            String derivation,
            boolean quarantined) {
        public DriftFinding {
            id = Objects.requireNonNull(id, "id");
            classification = Objects.requireNonNull(classification, "classification");
            source = Objects.requireNonNull(source, "source");
            target = Objects.requireNonNull(target, "target");
            sourceNodeId = sourceNodeId == null ? "" : sourceNodeId;
            targetNodeId = targetNodeId == null ? "" : targetNodeId;
            runtimeWeight = runtimeWeight == null ? OptionalDouble.empty() : runtimeWeight;
            runtimeLine = Math.max(0, runtimeLine);
            sourcePath = sourcePath == null ? "" : sourcePath;
            contentSha256 = contentSha256 == null ? "" : contentSha256;
            trust = TRUST_TIER;
            derivation = DERIVATION;
            quarantined = true;
        }

        public Map<String, Object> toView() {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("classification", classification.name());
            m.put("source", source);
            m.put("target", target);
            if (!sourceNodeId.isEmpty()) m.put("sourceNodeId", sourceNodeId);
            if (!targetNodeId.isEmpty()) m.put("targetNodeId", targetNodeId);
            if (runtimeWeight.isPresent()) m.put("runtimeWeight", runtimeWeight.getAsDouble());
            if (runtimeLine > 0) m.put("runtimeLine", runtimeLine);
            m.put("sourcePath", sourcePath);
            m.put("contentSha256", contentSha256);
            m.put("trust", trust);
            m.put("derivation", derivation);
            m.put("quarantined", quarantined);
            return Collections.unmodifiableMap(m);
        }
    }

    public record DriftMetrics(
            int staticEdgeCount,
            int runtimeEdgeCount,
            int concordantCount,
            int shadowRuntimeCount,
            int unexercisedStaticCount,
            double precision,
            double recall,
            double f1,
            double jaccard) {
        public Map<String, Object> toView() {
            var m = new LinkedHashMap<String, Object>();
            m.put("staticEdgeCount", staticEdgeCount);
            m.put("runtimeEdgeCount", runtimeEdgeCount);
            m.put("concordantCount", concordantCount);
            m.put("shadowRuntimeCount", shadowRuntimeCount);
            m.put("unexercisedStaticCount", unexercisedStaticCount);
            m.put("precision", precision);
            m.put("recall", recall);
            m.put("f1", f1);
            m.put("jaccard", jaccard);
            return Collections.unmodifiableMap(m);
        }
    }

    public record DriftOverlaySnapshot(
            String schema,
            String trust,
            String derivation,
            boolean quarantined,
            String graphIdentity,
            InputFormat inputFormat,
            String sourcePath,
            String contentSha256,
            List<DriftFinding> findings,
            List<DriftFinding> shadowDependencies,
            List<DriftFinding> unexercisedStaticPaths,
            List<DriftFinding> concordantEdges,
            DriftMetrics metrics,
            List<String> warnings) {
        public DriftOverlaySnapshot {
            schema = SCHEMA;
            trust = TRUST_TIER;
            derivation = DERIVATION;
            quarantined = true;
            graphIdentity = graphIdentity == null ? "" : graphIdentity;
            inputFormat = Objects.requireNonNull(inputFormat, "inputFormat");
            sourcePath = sourcePath == null ? "" : sourcePath;
            contentSha256 = contentSha256 == null ? "" : contentSha256;
            findings = List.copyOf(findings == null ? List.of() : findings);
            shadowDependencies = List.copyOf(shadowDependencies == null ? List.of() : shadowDependencies);
            unexercisedStaticPaths = List.copyOf(unexercisedStaticPaths == null ? List.of() : unexercisedStaticPaths);
            concordantEdges = List.copyOf(concordantEdges == null ? List.of() : concordantEdges);
            metrics = Objects.requireNonNull(metrics, "metrics");
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }

        public Map<String, Object> toView(int limit) {
            int maxItems = Math.max(1, Math.min(500, limit));
            var counts = new TreeMap<String, Integer>();
            counts.put(DriftClassification.CONCORDANT_RUNTIME_EDGE.name(), concordantEdges.size());
            counts.put(DriftClassification.SHADOW_RUNTIME_DEPENDENCY.name(), shadowDependencies.size());
            counts.put(DriftClassification.UNEXERCISED_STATIC_PATH.name(), unexercisedStaticPaths.size());
            var m = new LinkedHashMap<String, Object>();
            m.put("schema", schema);
            m.put("trust", trust);
            m.put("derivation", derivation);
            m.put("quarantined", quarantined);
            m.put("canonicalLeakage", 0);
            m.put("notice", "Runtime drift findings are strictly quarantined in RUNTIME_OBSERVED and never mutate canonical KnowledgeGraph digests, Leiden communities, policy verdicts, or Architecture Score v2.");
            m.put("graphIdentity", graphIdentity);
            m.put("inputFormat", inputFormat.name());
            m.put("sourcePath", sourcePath);
            m.put("contentSha256", contentSha256);
            m.put("metrics", metrics.toView());
            m.put("countsByClassification", Collections.unmodifiableMap(counts));
            m.put("totalFindings", findings.size());
            m.put("truncated", findings.size() > maxItems);
            m.put("findings", findings.stream().limit(maxItems).map(DriftFinding::toView).toList());
            if (!warnings.isEmpty()) {
                m.put("warnings", warnings);
            }
            return Collections.unmodifiableMap(m);
        }
    }

    private record StaticEdgeInfo(String sourceNodeId, String targetNodeId) {}

    private record ParsedRuntimeInput(Map<EdgePair, RuntimeEdge> edges, List<String> warnings) {}

    private RuntimeDriftOverlayExtractor() {}

    /**
     * Passively reads a static runtime dependency file (Ripple {@code post.csv} or OpenTelemetry
     * Service Map JSON) from disk and computes quarantined static-vs-runtime drift against
     * {@code staticGraph}.
     */
    public static DriftOverlaySnapshot extract(KnowledgeGraph staticGraph, Path runtimeFile) throws IOException {
        Objects.requireNonNull(staticGraph, "staticGraph");
        Objects.requireNonNull(runtimeFile, "runtimeFile");
        if (!Files.isRegularFile(runtimeFile)) {
            throw new IOException("Runtime dependency file does not exist or is not a regular file: " + runtimeFile);
        }
        long size = Files.size(runtimeFile);
        if (size > MAX_RUNTIME_FILE_BYTES) {
            throw new IOException("Runtime dependency file exceeds passive read limit (" + MAX_RUNTIME_FILE_BYTES + " bytes): " + size);
        }
        byte[] bytes = Files.readAllBytes(runtimeFile);
        String content = new String(bytes, StandardCharsets.UTF_8);
        String sha256 = ContentDigest.sha256(bytes).value();
        String normPath = CapturedFileIndex.normalizePath(runtimeFile.getFileName().toString());
        InputFormat format = detectFormat(normPath, content);
        return format == InputFormat.OTEL_SERVICE_MAP_JSON
                ? extractFromJsonInternal(staticGraph, content, normPath, sha256)
                : extractFromCsvInternal(staticGraph, content, normPath, sha256);
    }

    /** Extracts quarantined runtime drift from Ripple {@code post.csv} ({@code src,dst[,weight]}) content. */
    public static DriftOverlaySnapshot extractFromCsv(KnowledgeGraph staticGraph, String csvContent) {
        return extractFromCsv(staticGraph, csvContent, "post.csv");
    }

    /** Extracts quarantined runtime drift from Ripple {@code post.csv} ({@code src,dst[,weight]}) content. */
    public static DriftOverlaySnapshot extractFromCsv(KnowledgeGraph staticGraph, String csvContent, String sourcePath) {
        Objects.requireNonNull(staticGraph, "staticGraph");
        String safe = csvContent == null ? "" : csvContent;
        String sha256 = ContentDigest.sha256Utf8(safe).value();
        String normPath = (sourcePath == null || sourcePath.isBlank()) ? "post.csv" : CapturedFileIndex.normalizePath(sourcePath);
        return extractFromCsvInternal(staticGraph, safe, normPath, sha256);
    }

    /** Extracts quarantined runtime drift from OpenTelemetry / Jaeger Service Map JSON content. */
    public static DriftOverlaySnapshot extractFromJson(KnowledgeGraph staticGraph, String jsonContent) {
        return extractFromJson(staticGraph, jsonContent, "otel-service-map.json");
    }

    /** Extracts quarantined runtime drift from OpenTelemetry / Jaeger Service Map JSON content. */
    public static DriftOverlaySnapshot extractFromJson(KnowledgeGraph staticGraph, String jsonContent, String sourcePath) {
        Objects.requireNonNull(staticGraph, "staticGraph");
        String safe = jsonContent == null ? "" : jsonContent;
        String sha256 = ContentDigest.sha256Utf8(safe).value();
        String normPath = (sourcePath == null || sourcePath.isBlank())
                ? "otel-service-map.json" : CapturedFileIndex.normalizePath(sourcePath);
        return extractFromJsonInternal(staticGraph, safe, normPath, sha256);
    }

    private static DriftOverlaySnapshot extractFromCsvInternal(
            KnowledgeGraph staticGraph, String csvContent, String sourcePath, String sha256) {
        var parsed = parseCsv(csvContent);
        return diff(staticGraph, parsed.edges(), parsed.warnings(), InputFormat.RIPPLE_CSV, sourcePath, sha256);
    }

    private static DriftOverlaySnapshot extractFromJsonInternal(
            KnowledgeGraph staticGraph, String jsonContent, String sourcePath, String sha256) {
        var parsed = parseOtelJson(jsonContent);
        return diff(staticGraph, parsed.edges(), parsed.warnings(), InputFormat.OTEL_SERVICE_MAP_JSON, sourcePath, sha256);
    }

    private static InputFormat detectFormat(String fileName, String content) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return InputFormat.OTEL_SERVICE_MAP_JSON;
        }
        if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
            return InputFormat.RIPPLE_CSV;
        }
        String trimmed = stripBom(content == null ? "" : content).stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return InputFormat.OTEL_SERVICE_MAP_JSON;
        }
        return InputFormat.RIPPLE_CSV;
    }

    private static DriftOverlaySnapshot diff(
            KnowledgeGraph staticGraph,
            Map<EdgePair, RuntimeEdge> runtimeEdges,
            List<String> warnings,
            InputFormat format,
            String sourcePath,
            String sha256) {
        Map<EdgePair, StaticEdgeInfo> staticEdges = extractStaticServiceEdges(staticGraph);
        Map<String, String> nodeIdByService = indexCanonicalServiceNodeIds(staticGraph);

        var shadow = new ArrayList<DriftFinding>();
        var unexercised = new ArrayList<DriftFinding>();
        var concordant = new ArrayList<DriftFinding>();

        // 1. E_runtime \ E_static (SHADOW_RUNTIME_DEPENDENCY) and E_runtime ∩ E_static (CONCORDANT_RUNTIME_EDGE)
        for (var entry : runtimeEdges.entrySet()) {
            EdgePair pair = entry.getKey();
            RuntimeEdge re = entry.getValue();
            StaticEdgeInfo staticInfo = staticEdges.get(pair);
            if (staticInfo != null) {
                concordant.add(buildFinding(
                        DriftClassification.CONCORDANT_RUNTIME_EDGE,
                        pair,
                        staticInfo.sourceNodeId(),
                        staticInfo.targetNodeId(),
                        re.weight(),
                        re.sourceLine(),
                        sourcePath,
                        sha256));
            } else {
                shadow.add(buildFinding(
                        DriftClassification.SHADOW_RUNTIME_DEPENDENCY,
                        pair,
                        nodeIdByService.getOrDefault(pair.source(), ""),
                        nodeIdByService.getOrDefault(pair.target(), ""),
                        re.weight(),
                        re.sourceLine(),
                        sourcePath,
                        sha256));
            }
        }

        // 2. E_static \ E_runtime (UNEXERCISED_STATIC_PATH)
        for (var entry : staticEdges.entrySet()) {
            EdgePair pair = entry.getKey();
            if (runtimeEdges.containsKey(pair)) continue;
            StaticEdgeInfo staticInfo = entry.getValue();
            unexercised.add(buildFinding(
                    DriftClassification.UNEXERCISED_STATIC_PATH,
                    pair,
                    staticInfo.sourceNodeId(),
                    staticInfo.targetNodeId(),
                    OptionalDouble.empty(),
                    0,
                    sourcePath,
                    sha256));
        }

        Comparator<DriftFinding> findingOrder = Comparator
                .comparingInt((DriftFinding f) -> f.classification().ordinal())
                .thenComparing(DriftFinding::source)
                .thenComparing(DriftFinding::target)
                .thenComparing(DriftFinding::id);
        shadow.sort(findingOrder);
        unexercised.sort(findingOrder);
        concordant.sort(findingOrder);

        var allFindings = new ArrayList<DriftFinding>(shadow.size() + unexercised.size() + concordant.size());
        allFindings.addAll(shadow);
        allFindings.addAll(unexercised);
        allFindings.addAll(concordant);
        allFindings.sort(findingOrder);

        int staticCount = staticEdges.size();
        int runtimeCount = runtimeEdges.size();
        int tp = concordant.size();
        int unionCount = staticCount + runtimeCount - tp;
        double precision = staticCount > 0 ? (double) tp / staticCount : (runtimeCount == 0 ? 1.0 : 0.0);
        double recall = runtimeCount > 0 ? (double) tp / runtimeCount : (staticCount == 0 ? 1.0 : 0.0);
        double f1 = (precision + recall) > 0.0 ? (2.0 * precision * recall) / (precision + recall) : 0.0;
        double jaccard = unionCount > 0 ? (double) tp / unionCount : 1.0;

        var metrics = new DriftMetrics(
                staticCount, runtimeCount, tp, shadow.size(), unexercised.size(),
                round4(precision), round4(recall), round4(f1), round4(jaccard));

        return new DriftOverlaySnapshot(
                SCHEMA, TRUST_TIER, DERIVATION, true,
                staticGraph.identity().value(),
                format, sourcePath, sha256,
                allFindings, shadow, unexercised, concordant,
                metrics, warnings);
    }

    private static DriftFinding buildFinding(
            DriftClassification classification,
            EdgePair pair,
            String sourceNodeId,
            String targetNodeId,
            OptionalDouble weight,
            int runtimeLine,
            String sourcePath,
            String sha256) {
        String id = driftFindingId(classification, pair.source(), pair.target());
        return new DriftFinding(
                id, classification, pair.source(), pair.target(),
                sourceNodeId, targetNodeId, weight, runtimeLine,
                sourcePath, sha256, TRUST_TIER, DERIVATION, true);
    }

    private static String driftFindingId(DriftClassification classification, String source, String target) {
        return "runtime-drift:" + ContentDigest.sha256Utf8(
                SCHEMA + "|" + classification.name() + "|" + source + "->" + target).value().substring(7, 23);
    }

    /**
     * Extracts canonical static service-to-service edges purely read-only from {@link KnowledgeGraph}
     * without mutating any graph state or digest.
     */
    private static Map<EdgePair, StaticEdgeInfo> extractStaticServiceEdges(KnowledgeGraph graph) {
        var nodeKindById = new HashMap<String, String>();
        var nodeNameById = new HashMap<String, String>();

        for (var node : graph.nodes()) {
            nodeKindById.put(node.identity(), node.kind());
            String name = extractNodeServiceName(graph, node);
            if (!name.isEmpty()) {
                nodeNameById.put(node.identity(), name);
            }
        }

        var edges = new TreeMap<EdgePair, StaticEdgeInfo>();
        for (var edge : graph.edges()) {
            if (!"CALLS_SERVICE".equals(edge.kind()) && !"INVOKES_RPC".equals(edge.kind())) {
                continue;
            }
            if ("EXTERNAL_ACTOR".equals(nodeKindById.get(edge.source()))) {
                continue;
            }

            // Prefer explicit declarative topology evidence when present
            String evSrc = "";
            String evDst = "";
            boolean skipDfdExternal = false;
            for (var ref : edge.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null) continue;
                if ("architecture.declarative-topology".equals(ev.family())) {
                    var obj = readEvidence(ev);
                    String mechanism = str(obj.get("mechanism"));
                    if ("DFD_EXTERNAL_ENTRY_POINT".equals(mechanism)
                            || "DFD_EXTERNAL_ENTRY_RESPONSE".equals(mechanism)
                            || "DFD_EXTERNAL_HTTPS_API".equals(mechanism)) {
                        skipDfdExternal = true;
                        break;
                    }
                    evSrc = normalizeServiceName(str(obj.get("source")));
                    evDst = normalizeServiceName(str(obj.get("target")));
                    break;
                }
            }
            if (skipDfdExternal) {
                continue;
            }

            if (!evSrc.isEmpty() && !evDst.isEmpty()) {
                String tgtId = edge.target().identities().isEmpty() ? "" : edge.target().identities().getFirst();
                if ("EXTERNAL_ACTOR".equals(nodeKindById.get(tgtId))) {
                    continue;
                }
                if (!evSrc.equals(evDst)) {
                    edges.putIfAbsent(new EdgePair(evSrc, evDst), new StaticEdgeInfo(edge.source(), tgtId));
                }
                continue;
            }

            // Fallback: resolve component-level CALLS_SERVICE / INVOKES_RPC endpoints via node names
            String srcName = nodeNameById.getOrDefault(edge.source(), "");
            if (srcName.isEmpty()) continue;
            for (String tgtId : edge.target().identities()) {
                if ("EXTERNAL_ACTOR".equals(nodeKindById.get(tgtId))) continue;
                String dstName = nodeNameById.getOrDefault(tgtId, "");
                if (!dstName.isEmpty() && !srcName.equals(dstName)) {
                    edges.putIfAbsent(new EdgePair(srcName, dstName), new StaticEdgeInfo(edge.source(), tgtId));
                }
            }
        }
        return edges;
    }

    private static Map<String, String> indexCanonicalServiceNodeIds(KnowledgeGraph graph) {
        var byName = new TreeMap<String, String>();
        for (var node : graph.nodes()) {
            if (!Set.of("BUILD_COMPONENT", "SERVICE_COMPONENT", "INFRA_COMPONENT").contains(node.kind())) {
                continue;
            }
            String name = extractNodeServiceName(graph, node);
            if (!name.isEmpty()) {
                byName.putIfAbsent(name, node.identity());
            }
        }
        return byName;
    }

    private static String extractNodeServiceName(KnowledgeGraph graph, KnowledgeGraph.Node node) {
        String fallback = "";
        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            switch (ev.family()) {
                case "architecture.service-identity" -> {
                    var obj = readEvidence(ev);
                    String svc = normalizeServiceName(str(obj.get("serviceName")));
                    if (!svc.isEmpty()) return svc;
                    String nm = normalizeServiceName(str(obj.get("name")));
                    if (!nm.isEmpty()) return nm;
                }
                case "architecture.infra-component", "architecture.external-actor" -> {
                    var obj = readEvidence(ev);
                    String nm = normalizeServiceName(str(obj.get("name")));
                    if (!nm.isEmpty()) return nm;
                }
                case "polyglot.build-component" -> {
                    var obj = readEvidence(ev);
                    String dir = str(obj.get("directory")).replace('\\', '/');
                    String id = str(obj.get("id"));
                    String base = basenameOrTail(dir, id);
                    if (!base.isEmpty() && fallback.isEmpty()) {
                        fallback = normalizeServiceName(base);
                    }
                }
                default -> {}
            }
        }
        return fallback;
    }

    private static ParsedRuntimeInput parseCsv(String rawCsv) {
        var edges = new TreeMap<EdgePair, RuntimeEdge>();
        var warnings = new ArrayList<String>();
        if (rawCsv == null || rawCsv.isBlank()) {
            return new ParsedRuntimeInput(edges, warnings);
        }
        String clean = stripBom(rawCsv);
        String[] lines = clean.split("\\R", -1);
        int srcCol = 0;
        int dstCol = 1;
        int weightCol = 2;
        boolean checkedHeader = false;

        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String rawLine = lines[i].trim();
            if (rawLine.isEmpty() || rawLine.startsWith("#")) {
                continue;
            }
            List<String> cols = parseCsvLine(rawLine);
            if (cols.size() < 2) {
                warnings.add("CSV line " + lineNo + ": expected at least 2 columns (src,dst)");
                continue;
            }
            if (!checkedHeader) {
                checkedHeader = true;
                int detectedSrc = -1;
                int detectedDst = -1;
                int detectedWeight = -1;
                for (int c = 0; c < cols.size(); c++) {
                    String hdr = cols.get(c).trim().toLowerCase(Locale.ROOT);
                    if (detectedSrc < 0 && SOURCE_HEADERS.contains(hdr)) detectedSrc = c;
                    else if (detectedDst < 0 && TARGET_HEADERS.contains(hdr)) detectedDst = c;
                    else if (detectedWeight < 0 && WEIGHT_HEADERS.contains(hdr)) detectedWeight = c;
                }
                if (detectedSrc >= 0 && detectedDst >= 0) {
                    srcCol = detectedSrc;
                    dstCol = detectedDst;
                    weightCol = detectedWeight;
                    continue;
                }
            }
            if (srcCol >= cols.size() || dstCol >= cols.size()) {
                warnings.add("CSV line " + lineNo + ": missing source or target column");
                continue;
            }
            String rawSrc = cols.get(srcCol).trim();
            String rawDst = cols.get(dstCol).trim();
            String normSrc = normalizeServiceName(rawSrc);
            String normDst = normalizeServiceName(rawDst);
            if (normSrc.isEmpty() || normDst.isEmpty()) {
                warnings.add("CSV line " + lineNo + ": blank source or target service");
                continue;
            }
            if (normSrc.equals(normDst)) {
                continue;
            }
            OptionalDouble weight = OptionalDouble.empty();
            if (weightCol >= 0 && weightCol < cols.size()) {
                String wText = cols.get(weightCol).trim();
                if (!wText.isEmpty()) {
                    try {
                        double parsed = Double.parseDouble(wText);
                        if (Double.isFinite(parsed) && parsed >= 0.0) {
                            weight = OptionalDouble.of(parsed);
                        } else {
                            warnings.add("CSV line " + lineNo + ": ignored non-finite/negative weight '" + wText + "'");
                        }
                    } catch (NumberFormatException invalid) {
                        warnings.add("CSV line " + lineNo + ": ignored non-numeric weight '" + wText + "'");
                    }
                }
            }
            mergeRuntimeEdge(edges, new EdgePair(normSrc, normDst), weight, lineNo, rawSrc, rawDst);
        }
        return new ParsedRuntimeInput(edges, warnings);
    }

    private static List<String> parseCsvLine(String line) {
        var cols = new ArrayList<String>();
        var cur = new StringBuilder();
        boolean inQuotes = false;
        char delimiter = (!line.contains(",") && line.contains("\t")) ? '\t' : ',';
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else {
                if (ch == '"' && cur.isEmpty()) {
                    inQuotes = true;
                } else if (ch == delimiter) {
                    cols.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
        }
        cols.add(cur.toString());
        return cols;
    }

    private static ParsedRuntimeInput parseOtelJson(String rawJson) {
        var edges = new TreeMap<EdgePair, RuntimeEdge>();
        var warnings = new ArrayList<String>();
        if (rawJson == null || rawJson.isBlank()) {
            return new ParsedRuntimeInput(edges, warnings);
        }
        Object parsed;
        try {
            parsed = GraphJson.read(stripBom(rawJson), JSON_LIMITS);
        } catch (RuntimeException invalid) {
            warnings.add("Invalid OpenTelemetry JSON: " + invalid.getMessage());
            return new ParsedRuntimeInput(edges, warnings);
        }

        List<?> rawEdgeList = locateEdgeArray(parsed);
        if (rawEdgeList == null) {
            warnings.add("No service-map edge array found (expected edges, dependencies, data, relations, or top-level array)");
            return new ParsedRuntimeInput(edges, warnings);
        }

        // Optional node-id -> service-name lookup when OTel/Tempo JSON indexes nodes by id
        Map<String, String> nodeNameById = extractJsonNodeMap(parsed);

        int idx = 0;
        for (Object item : rawEdgeList) {
            idx++;
            if (!(item instanceof Map<?, ?> rawMap)) {
                warnings.add("JSON edge #" + idx + ": expected JSON object");
                continue;
            }
            var obj = GraphJson.object(rawMap);
            String rawSrc = firstString(obj, "source", "src", "parent", "client", "caller", "from", "sourceService");
            String rawDst = firstString(obj, "target", "dst", "dest", "destination", "child", "server", "callee", "to", "targetService");
            if (nodeNameById.containsKey(rawSrc)) rawSrc = nodeNameById.get(rawSrc);
            if (nodeNameById.containsKey(rawDst)) rawDst = nodeNameById.get(rawDst);

            String normSrc = normalizeServiceName(rawSrc);
            String normDst = normalizeServiceName(rawDst);
            if (normSrc.isEmpty() || normDst.isEmpty()) {
                warnings.add("JSON edge #" + idx + ": missing source or target service field");
                continue;
            }
            if (normSrc.equals(normDst)) {
                continue;
            }
            OptionalDouble weight = firstNumeric(obj,
                    "callCount", "calls", "weight", "count", "requestCount", "requests", "value", "rps");
            mergeRuntimeEdge(edges, new EdgePair(normSrc, normDst), weight, idx, rawSrc, rawDst);
        }
        return new ParsedRuntimeInput(edges, warnings);
    }

    private static List<?> locateEdgeArray(Object parsed) {
        if (parsed instanceof List<?> list) {
            return list;
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return null;
        }
        var obj = GraphJson.object(map);
        for (String key : List.of("edges", "dependencies", "relations", "links", "data")) {
            if (obj.get(key) instanceof List<?> list) {
                return list;
            }
        }
        for (String wrapper : List.of("serviceMap", "service_map", "graph", "topology", "data")) {
            if (obj.get(wrapper) instanceof Map<?, ?> nested) {
                List<?> inner = locateEdgeArray(nested);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private static Map<String, String> extractJsonNodeMap(Object parsed) {
        if (!(parsed instanceof Map<?, ?> map)) {
            return Map.of();
        }
        var obj = GraphJson.object(map);
        Object nodesObj = obj.get("nodes");
        if (nodesObj == null && obj.get("serviceMap") instanceof Map<?, ?> nested) {
            nodesObj = GraphJson.object(nested).get("nodes");
        }
        if (nodesObj == null && obj.get("graph") instanceof Map<?, ?> nested) {
            nodesObj = GraphJson.object(nested).get("nodes");
        }
        if (!(nodesObj instanceof List<?> list)) {
            return Map.of();
        }
        var out = new HashMap<String, String>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawNode)) continue;
            var n = GraphJson.object(rawNode);
            String id = firstString(n, "id", "nodeId", "key");
            String name = firstString(n, "serviceName", "service.name", "name", "label", "title");
            if (!id.isEmpty() && !name.isEmpty()) {
                out.putIfAbsent(id, name);
            }
        }
        return out;
    }

    private static void mergeRuntimeEdge(
            Map<EdgePair, RuntimeEdge> edges,
            EdgePair pair,
            OptionalDouble weight,
            int lineNo,
            String rawSrc,
            String rawDst) {
        RuntimeEdge existing = edges.get(pair);
        if (existing == null) {
            edges.put(pair, new RuntimeEdge(pair.source(), pair.target(), weight, lineNo, rawSrc, rawDst));
            return;
        }
        OptionalDouble mergedWeight;
        if (existing.weight().isPresent() && weight.isPresent()) {
            mergedWeight = OptionalDouble.of(existing.weight().getAsDouble() + weight.getAsDouble());
        } else if (existing.weight().isPresent()) {
            mergedWeight = existing.weight();
        } else {
            mergedWeight = weight;
        }
        edges.put(pair, new RuntimeEdge(
                pair.source(), pair.target(), mergedWeight,
                Math.min(existing.sourceLine(), lineNo),
                existing.rawSource(), existing.rawTarget()));
    }

    /**
     * Generic service-name canonicalizer: trims quotes/scheme/port/K8s DNS suffix and normalizes
     * separators to lowercase hyphenated tokens (zero benchmark/repository-specific rules, I5).
     */
    public static String normalizeServiceName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        String lower = s.toLowerCase(Locale.ROOT);
        int schemeIdx = lower.indexOf("://");
        if (schemeIdx >= 0) {
            lower = lower.substring(schemeIdx + 3);
        }
        int slashIdx = lower.indexOf('/');
        if (slashIdx >= 0) {
            lower = lower.substring(0, slashIdx);
        }
        int colonIdx = lower.indexOf(':');
        if (colonIdx > 0) {
            String after = lower.substring(colonIdx + 1);
            if (after.matches("\\d+")) {
                lower = lower.substring(0, colonIdx);
            }
        }
        if (lower.endsWith(".svc.cluster.local")) {
            lower = lower.substring(0, lower.length() - ".svc.cluster.local".length());
            int dot = lower.indexOf('.');
            if (dot > 0) lower = lower.substring(0, dot);
        } else if (lower.endsWith(".svc")) {
            lower = lower.substring(0, lower.length() - ".svc".length());
            int dot = lower.indexOf('.');
            if (dot > 0) lower = lower.substring(0, dot);
        }
        var sb = new StringBuilder(lower.length());
        boolean prevDash = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.') {
                sb.append(c);
                prevDash = false;
            } else if (!prevDash && !sb.isEmpty()) {
                sb.append('-');
                prevDash = true;
            }
        }
        while (!sb.isEmpty() && (sb.charAt(sb.length() - 1) == '-' || sb.charAt(sb.length() - 1) == '.')) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static String firstString(Map<String, Object> obj, String... keys) {
        for (String key : keys) {
            if (obj.get(key) instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return "";
    }

    private static OptionalDouble firstNumeric(Map<String, Object> obj, String... keys) {
        for (String key : keys) {
            Object val = obj.get(key);
            if (val instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isFinite(d) && d >= 0.0) {
                    return OptionalDouble.of(d);
                }
            } else if (val instanceof String s && !s.isBlank()) {
                try {
                    double d = Double.parseDouble(s.trim());
                    if (Double.isFinite(d) && d >= 0.0) {
                        return OptionalDouble.of(d);
                    }
                } catch (NumberFormatException ignored) {
                    // continue
                }
            }
        }
        return OptionalDouble.empty();
    }

    private static Map<String, Object> readEvidence(GraphEvidence ev) {
        if (ev.canonicalJson().length() > 131_072) return Map.of();
        try {
            return GraphJson.object(GraphJson.read(ev.canonicalJson(), EVIDENCE_LIMITS));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static String basenameOrTail(String dir, String fallbackId) {
        String clean = dir == null ? "" : dir.replace('\\', '/').trim();
        while (clean.startsWith("./")) clean = clean.substring(2);
        while (clean.endsWith("/") && clean.length() > 1) clean = clean.substring(0, clean.length() - 1);
        if (!clean.isEmpty() && !".".equals(clean)) {
            int slash = clean.lastIndexOf('/');
            return slash >= 0 ? clean.substring(slash + 1) : clean;
        }
        if (fallbackId == null) return "";
        int colon = fallbackId.lastIndexOf(':');
        return colon >= 0 ? fallbackId.substring(colon + 1) : fallbackId;
    }

    private static String stripBom(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static String str(Object raw) {
        return raw instanceof String s ? s.trim() : "";
    }

    private static double round4(double value) {
        return Math.rint(value * 10_000.0) / 10_000.0;
    }
}
