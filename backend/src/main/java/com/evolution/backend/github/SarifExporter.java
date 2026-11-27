package com.evolution.backend.github;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.GitHubProvenance;
import com.evolution.backend.query.PublishedReports;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;

/**
 * Deterministic SARIF 2.1.0 exporter and GitHub Code Scanning ({@code POST /code-scanning/sarifs}) uploader.
 */
public final class SarifExporter {
    public static final String SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    public static final String SARIF_VERSION = "2.1.0";
    public static final String TOOL_NAME = "Evo Architecture Intelligence";
    public static final String TOOL_VERSION = "0.1.0";

    public record ExportedSarif(
            String json,
            ContentDigest sarifDigest,
            int ruleCount,
            int resultCount) {
    }

    public record UploadOutcome(
            String sarifId,
            String url,
            int ruleCount,
            int resultCount,
            ContentDigest sarifDigest) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("sarifId", sarifId);
            map.put("url", url);
            map.put("ruleCount", ruleCount);
            map.put("resultCount", resultCount);
            map.put("sarifDigest", sarifDigest.value());
            return map;
        }
    }

    private SarifExporter() {
    }

    public static ExportedSarif export(KnowledgeGraph graph, PublishedReports.Report report) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(report, "report");
        return export(graph, report.evaluation(), report.identity());
    }

    public static ExportedSarif export(KnowledgeGraph graph, PolicyEvaluation.Result eval, ContentDigest reportDigest) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(eval, "eval");
        Objects.requireNonNull(reportDigest, "reportDigest");

        var codeowners = GitHubProvenance.CodeownersIndex.fromGraph(graph);
        var evidenceById = new HashMap<ContentDigest, GraphEvidence>();
        for (var ev : graph.evidence()) {
            evidenceById.put(ev.identity(), ev);
        }

        var ruleDescriptors = new LinkedHashMap<String, Map<String, Object>>();
        var results = new ArrayList<Map<String, Object>>();
        var sortedFindings = new ArrayList<PolicyEvaluation.Finding>(eval.findings());
        sortedFindings.sort(Comparator.comparing(f -> f.identity().value()));

        for (var finding : sortedFindings) {
            var header = finding.rule();
            String ruleId = header.id() + "@" + header.version();
            ruleDescriptors.computeIfAbsent(ruleId, id -> {
                var r = new LinkedHashMap<String, Object>();
                r.put("id", id);
                r.put("name", header.id());
                r.put("shortDescription", Map.of("text", "Architecture policy rule " + header.id() + " (" + finding.kind().name() + ")"));
                r.put("defaultConfiguration", Map.of(
                        "level", finding.classification() == ArchitectureQuery.Classification.MUST ? "error" : "warning"));
                r.put("properties", Map.of(
                        "tags", List.of("architecture", "evolution", finding.classification().name().toLowerCase(Locale.ROOT)),
                        "precision", finding.classification() == ArchitectureQuery.Classification.MUST ? "high" : "medium"));
                return r;
            });

            var bundle = finding.bundle();
            String src = bundle.path().isEmpty() ? "" : bundle.path().getFirst();
            String dst = bundle.path().size() > 1 ? bundle.path().getLast() : src;
            String relKind = bundle.relationships().isEmpty() || bundle.relationships().getFirst().kinds().isEmpty()
                    ? header.relationships().iterator().next()
                    : bundle.relationships().getFirst().kinds().getFirst();

            var loc = locateFinding(graph, evidenceById, finding, src);
            var owners = loc.path().isEmpty() ? List.<String>of() : codeowners.ownersOf(loc.path());

            var res = new LinkedHashMap<String, Object>();
            res.put("ruleId", ruleId);
            res.put("level", finding.classification() == ArchitectureQuery.Classification.MUST ? "error" : "warning");
            res.put("message", Map.of("text",
                    "Architecture violation (" + finding.classification().name() + "): "
                            + src + " --[" + relKind + "]--> " + dst));
            if (!loc.path().isEmpty()) {
                var region = new LinkedHashMap<String, Object>();
                region.put("startLine", loc.startLine());
                region.put("endLine", loc.endLine());
                if (loc.startColumn() > 0) {
                    region.put("startColumn", loc.startColumn());
                }
                res.put("locations", List.of(Map.of(
                        "physicalLocation", Map.of(
                                "artifactLocation", Map.of("uri", loc.path()),
                                "region", region))));
            }
            res.put("partialFingerprints", Map.of("evoFindingDigest", finding.identity().value()));
            var props = new LinkedHashMap<String, Object>();
            props.put("classification", finding.classification().name());
            props.put("assessmentStatus", bundle.status().name());
            props.put("source", src);
            props.put("target", dst);
            props.put("relationship", relKind);
            props.put("owners", owners);
            props.put("graphDigest", graph.identity().value());
            props.put("reportDigest", reportDigest.value());
            res.put("properties", props);
            results.add(res);
        }

        var driver = new LinkedHashMap<String, Object>();
        driver.put("name", TOOL_NAME);
        driver.put("version", TOOL_VERSION);
        driver.put("informationUri", "https://github.com/evo-platform/architecture-intelligence");
        driver.put("rules", List.copyOf(ruleDescriptors.values()));

        var run = new LinkedHashMap<String, Object>();
        run.put("tool", Map.of("driver", driver));
        run.put("results", results);
        run.put("properties", Map.of(
                "graphDigest", graph.identity().value(),
                "reportDigest", reportDigest.value(),
                "snapshotId", graph.snapshot().value()));

        var sarif = new LinkedHashMap<String, Object>();
        sarif.put("$schema", SARIF_SCHEMA);
        sarif.put("version", SARIF_VERSION);
        sarif.put("runs", List.of(run));

        String json = CanonicalJson.write(sarif);
        return new ExportedSarif(json, ContentDigest.sha256Utf8(json), ruleDescriptors.size(), results.size());
    }

    public static String compressGzipBase64(String sarifJson) {
        Objects.requireNonNull(sarifJson, "sarifJson");
        try {
            var baos = new ByteArrayOutputStream();
            try (var gz = new GZIPOutputStream(baos)) {
                gz.write(sarifJson.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("GZIP compression failed", impossible);
        }
    }

    public static UploadOutcome uploadToGitHub(
            GitHubRestClient restClient,
            GitHubCoordinates.Coordinates coords,
            KnowledgeGraph graph,
            PublishedReports.Report report,
            String commitSha,
            String ref,
            boolean confirmedByUser,
            BooleanSupplier cancelled) {
        if (!confirmedByUser) {
            throw new IllegalArgumentException("EXPLICIT_USER_CONFIRMATION_REQUIRED");
        }
        var exported = export(graph, report);
        String compressed = compressGzipBase64(exported.json());
        var receipt = restClient.uploadSarif(coords, commitSha, ref, compressed, TOOL_NAME, cancelled);
        return new UploadOutcome(
                receipt.id(), receipt.url(), exported.ruleCount(), exported.resultCount(), exported.sarifDigest());
    }

    private record SpanLoc(String path, int startLine, int endLine, int startColumn) {}

    private static SpanLoc locateFinding(
            KnowledgeGraph graph,
            Map<ContentDigest, GraphEvidence> evidenceById,
            PolicyEvaluation.Finding finding,
            String sourceNodeId) {
        for (var se : finding.bundle().sources()) {
            var fromEv = parseSpan(evidenceById.get(se.evidence()));
            if (fromEv != null && !fromEv.path().isEmpty()) {
                var span = se.span();
                return new SpanLoc(fromEv.path(),
                        Math.max(1, span.startLine()),
                        Math.max(span.startLine(), span.endLine()),
                        Math.max(0, span.startColumn()));
            }
        }
        for (var ref : finding.bundle().evidence()) {
            var loc = parseSpan(evidenceById.get(ref.identity()));
            if (loc != null) return loc;
        }
        if (sourceNodeId != null && !sourceNodeId.isBlank()) {
            for (var node : graph.nodes()) {
                if (node.identity().equals(sourceNodeId)) {
                    for (var evId : node.evidence()) {
                        var loc = parseSpan(evidenceById.get(evId));
                        if (loc != null) return loc;
                    }
                }
            }
        }
        return new SpanLoc("", 1, 1, 0);
    }

    private static SpanLoc parseSpan(GraphEvidence ev) {
        if (ev == null) return null;
        try {
            var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(),
                    new GraphJson.Limits(1024 * 1024, 24, 50_000)));
            Object spanObj = obj.get("span");
            if (spanObj instanceof Map<?, ?> m) {
                var span = GraphJson.object(m);
                String path = span.get("path") instanceof String s ? CapturedFileIndex.normalizePath(s) : "";
                int start = span.get("startLine") instanceof Number n ? Math.max(1, n.intValue()) : 1;
                int end = span.get("endLine") instanceof Number n ? Math.max(start, n.intValue()) : start;
                int col = span.get("startColumn") instanceof Number n ? Math.max(0, n.intValue()) : 0;
                if (!path.isEmpty()) return new SpanLoc(path, start, end, col);
            }
            if (obj.get("path") instanceof String p && !p.isBlank()) {
                int start = obj.get("startLine") instanceof Number n ? Math.max(1, n.intValue()) : 1;
                int end = obj.get("endLine") instanceof Number n ? Math.max(start, n.intValue()) : start;
                return new SpanLoc(CapturedFileIndex.normalizePath(p), start, end, 0);
            }
        } catch (RuntimeException ignored) {}
        return null;
    }
}
