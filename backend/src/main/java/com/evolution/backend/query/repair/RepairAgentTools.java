package com.evolution.backend.query.repair;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.*;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.CapturedRepair;
import com.evolution.backend.query.advisory.AdvisoryOverlayService;
import com.evolution.backend.query.llm.LlmTransport;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Strictly read-only, bounded tool-calling toolkit for Repair LLM patch authoring and
 * closed-loop error diagnosis.
 *
 * <p>Exposes ONLY 5 allowlisted read-only tools:
 * <ol>
 *   <li>{@code read_captured_source}</li>
 *   <li>{@code inspect_symbol_details}</li>
 *   <li>{@code analyze_impact}</li>
 *   <li>{@code inspect_advisory_links}</li>
 *   <li>{@code read_markdown_section}</li>
 * </ol>
 *
 * <p>Security invariants: ZERO mutation tools, ZERO host filesystem/build execution, mandatory secret
 * redaction + prompt-injection sentinel neutralization + {@code <untrusted_source path="..." sha256="...">}
 * framing on every returned payload.
 */
public final class RepairAgentTools {
    public static final int MAX_TOOL_STEPS_PER_ROUND = 6;
    public static final int MAX_TOTAL_TOOL_CALLS_PER_ROUND = 12;
    public static final int MAX_TOOL_RESULT_BYTES = 24 * 1024;
    public static final Set<String> ALLOWED_TOOLS = Set.of(
            "read_captured_source",
            "inspect_symbol_details",
            "analyze_impact",
            "inspect_advisory_links",
            "read_markdown_section");

    public record ToolTraceEntry(
            int step,
            String tool,
            Map<String, Object> arguments,
            String summary,
            long durationMs) {
        public ToolTraceEntry {
            arguments = arguments == null ? Map.of() : redactArgumentsMap(arguments);
            summary = summary == null ? "" : LlmTransport.redactSecrets(summary).text();
        }
    }

    private static Map<String, Object> redactArgumentsMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        var out = new LinkedHashMap<String, Object>(raw.size());
        for (var e : raw.entrySet()) {
            String safeKey = e.getKey() == null ? "" : LlmTransport.redactSecrets(e.getKey()).text();
            out.put(safeKey, redactArgValue(e.getValue(), 0));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Object redactArgValue(Object value, int depth) {
        if (value == null) return null;
        if (depth > 6) return "[TRUNCATED]";
        if (value instanceof String s) {
            return s.isEmpty() ? s : LlmTransport.redactSecrets(s).text();
        }
        if (value instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<Object, Object>(m.size());
            for (var e : m.entrySet()) {
                Object k = e.getKey() instanceof String ks ? LlmTransport.redactSecrets(ks).text() : e.getKey();
                out.put(k, redactArgValue(e.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<Object>(list.size());
            for (Object item : list) {
                out.add(redactArgValue(item, depth + 1));
            }
            return Collections.unmodifiableList(out);
        }
        return value;
    }

    public record ToolResult(String toolOutputJson, String summary) {}

    private RepairAgentTools() {}

    public static List<Map<String, Object>> toolSchemas() {
        return List.of(
                fn("read_captured_source",
                        "Read a bounded line range from an immutable captured repository source file (read-only, secret-redacted).",
                        Map.of(
                                "path", Map.of("type", "string", "description", "Repository-relative file path"),
                                "startLine", Map.of("type", "integer", "description", "1-based start line (default 1)"),
                                "endLine", Map.of("type", "integer", "description", "1-based end line (max 200 lines per call)")),
                        List.of("path")),
                fn("inspect_symbol_details",
                        "Inspect a symbol's kind, declaring file path, incoming callers, and outgoing callees in the canonical KnowledgeGraph (read-only).",
                        Map.of(
                                "symbol", Map.of("type", "string", "description", "Qualified symbol name, simple class name, or canonical entity ID"),
                                "context", Map.of("type", "string", "description", "Optional analysis context identity")),
                        List.of("symbol")),
                fn("analyze_impact",
                        "Compute bounded incoming or outgoing dependency blast radius for a symbol before editing its signature or dependencies (read-only).",
                        Map.of(
                                "symbol", Map.of("type", "string", "description", "Symbol name or canonical entity ID"),
                                "direction", Map.of("type", "string", "enum", List.of("INCOMING", "OUTGOING"), "description", "Default INCOMING"),
                                "maxDepth", Map.of("type", "integer", "description", "Depth 1..3 (default 2)")),
                        List.of("symbol")),
                fn("inspect_advisory_links",
                        "Inspect quarantined ADVISORY_UNVERIFIED non-code links (SQL DDL <-> JPA @Entity, ADR docs, Terraform) relevant to an entity or file (read-only).",
                        Map.of(
                                "entity", Map.of("type", "string", "description", "Optional symbol or table name filter"),
                                "path", Map.of("type", "string", "description", "Optional file path filter"),
                                "kind", Map.of("type", "string", "description", "Optional link kind filter")),
                        List.of()),
                fn("read_markdown_section",
                        "List headings or read one section of a captured Markdown/ADR document with exact line spans (read-only, secret-redacted).",
                        Map.of(
                                "path", Map.of("type", "string", "description", "Repository-relative Markdown file path"),
                                "heading", Map.of("type", "string", "description", "Optional heading title to read; omit to list headings")),
                        List.of("path")));
    }

    public static ToolResult execute(
            String toolName,
            String rawArgumentsJson,
            KnowledgeGraph graph,
            String defaultContext,
            ArchitectureQueryService queries) {
        if (toolName == null || !ALLOWED_TOOLS.contains(toolName)) {
            String denied = CanonicalJson.write(Map.of(
                    "error", "TOOL_NOT_ALLOWED",
                    "message", "Only read-only inspection tools are permitted during repair authoring: " + ALLOWED_TOOLS));
            return new ToolResult(denied, "Blocked disallowed tool: " + toolName);
        }
        Map<String, Object> args;
        try {
            args = (rawArgumentsJson == null || rawArgumentsJson.isBlank())
                    ? Map.of()
                    : GraphJson.object(GraphJson.read(rawArgumentsJson, GraphJson.Limits.defaults()));
        } catch (IllegalArgumentException invalid) {
            return new ToolResult(
                    CanonicalJson.write(Map.of("error", "INVALID_TOOL_ARGUMENTS", "message", "Malformed JSON arguments")),
                    "Invalid arguments for " + toolName);
        }
        if (graph == null) {
            return new ToolResult(
                    CanonicalJson.write(Map.of("error", "GRAPH_UNAVAILABLE")),
                    "Graph unavailable for " + toolName);
        }
        try {
            return switch (toolName) {
                case "read_captured_source" -> runReadCapturedSource(args, graph);
                case "inspect_symbol_details" -> runInspectSymbolDetails(args, graph, defaultContext);
                case "analyze_impact" -> runAnalyzeImpact(args, graph, defaultContext, queries);
                case "inspect_advisory_links" -> runInspectAdvisoryLinks(args, graph);
                case "read_markdown_section" -> runReadMarkdownSection(args, graph);
                default -> new ToolResult(CanonicalJson.write(Map.of("error", "TOOL_NOT_ALLOWED")), "Blocked tool");
            };
        } catch (RuntimeException ex) {
            String safeErr = LlmTransport.redactSecrets(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()).text();
            return new ToolResult(CanonicalJson.write(Map.of("error", "TOOL_FAILED", "message", safeErr)),
                    "Tool failed: " + toolName);
        }
    }

    private static ToolResult runReadCapturedSource(Map<String, Object> args, KnowledgeGraph graph) {
        String path = str(args, "path", "");
        int start = clampInt(args.get("startLine"), 1, 1, 10_000_000);
        int end = clampInt(args.get("endLine"), start + 159, start, start + 199);
        var read = CapturedFileIndex.forGraph(graph).read(graph, path, start, end);
        var out = new LinkedHashMap<String, Object>();
        out.put("found", read.found());
        out.put("readOnly", true);
        out.put("path", LlmTransport.neutralizeInjectionSentinels(read.path()));
        out.put("language", read.language());
        out.put("startLine", read.startLine());
        out.put("endLine", read.endLine());
        out.put("totalLines", read.totalLines());
        out.put("redactedSecretsCount", read.redactedSecretsCount());
        if (read.found()) {
            String sha = read.digest().map(d -> d.value()).orElse("unknown");
            String body = read.numberedSource();
            if (body.length() > 16_000) body = body.substring(0, 16_000) + "\n... [truncated]";
            out.put("content", LlmTransport.wrapPreRedactedUntrustedSource(read.path(), sha, body));
        } else if (!read.candidates().isEmpty()) {
            out.put("candidates", read.candidates().stream().limit(10)
                    .map(LlmTransport::neutralizeInjectionSentinels).toList());
        }
        String json = boundJson(out);
        String summary = read.found()
                ? "Read " + read.path() + " L" + read.startLine() + "-" + read.endLine()
                : "File not found: " + path;
        return new ToolResult(json, summary);
    }

    private static ToolResult runInspectSymbolDetails(Map<String, Object> args, KnowledgeGraph graph, String defaultCtx) {
        String symbol = str(args, "symbol", "");
        String ctx = str(args, "context", defaultCtx == null ? graph.snapshot().value() : defaultCtx);
        var match = findNode(graph, ctx, symbol);
        if (match == null) {
            return new ToolResult(
                    CanonicalJson.write(Map.of("found", false, "symbol", LlmTransport.neutralizeInjectionSentinels(symbol))),
                    "Symbol not found: " + symbol);
        }
        String matchedCtx = match.context();
        var callers = new ArrayList<Map<String, Object>>();
        var callees = new ArrayList<Map<String, Object>>();
        for (var edge : graph.edges(matchedCtx)) {
            if ("graph.contains".equals(edge.kind()) || "graph.declares".equals(edge.kind())
                    || "graph.owns-source".equals(edge.kind())) continue;
            if (edge.target().identities().contains(match.identity()) && !edge.source().equals(match.identity()) && callers.size() < 15) {
                callers.add(Map.of(
                        "identity", edge.source(),
                        "label", safeNodeLabel(graph, matchedCtx, edge.source()),
                        "kind", edge.kind()));
            } else if (edge.source().equals(match.identity()) && callees.size() < 15) {
                for (String t : edge.target().identities()) {
                    if (callees.size() >= 15) break;
                    if (!t.equals(match.identity())) {
                        callees.add(Map.of(
                                "identity", t,
                                "label", safeNodeLabel(graph, matchedCtx, t),
                                "kind", edge.kind()));
                    }
                }
            }
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("found", true);
        out.put("readOnly", true);
        out.put("identity", match.identity());
        out.put("kind", match.kind());
        out.put("context", matchedCtx);
        out.put("label", safeNodeLabel(graph, matchedCtx, match.identity()));
        out.put("incomingCallers", callers);
        out.put("outgoingCallees", callees);
        String json = boundJson(LlmTransport.neutralizeMapForLlm(out));
        return new ToolResult(json, "Inspected " + out.get("label") + " (" + callers.size() + " callers, " + callees.size() + " callees)");
    }

    private static ToolResult runAnalyzeImpact(
            Map<String, Object> args, KnowledgeGraph graph, String defaultCtx, ArchitectureQueryService queries) {
        String symbol = str(args, "symbol", "");
        String dirStr = str(args, "direction", "INCOMING").toUpperCase(Locale.ROOT);
        int maxDepth = clampInt(args.get("maxDepth"), 2, 1, 3);
        String ctx = defaultCtx == null ? graph.snapshot().value() : defaultCtx;
        var match = findNode(graph, ctx, symbol);
        if (match == null) {
            return new ToolResult(
                    CanonicalJson.write(Map.of("found", false, "symbol", LlmTransport.neutralizeInjectionSentinels(symbol))),
                    "Impact target not found: " + symbol);
        }
        Direction dir = "OUTGOING".equals(dirStr) ? Direction.OUTGOING : Direction.INCOMING;
        var affected = new ArrayList<Map<String, Object>>();
        if (queries != null) {
            try {
                var req = new Request(graph.identity(), match.context(), Scope.TYPE, Projection.BOUNDED_UNION,
                        dir, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(),
                        new Limits(maxDepth, 25_000, 30, 100_000, 500_000, 1_000_000, 100_000, 200_000));
                var page = dir == Direction.INCOMING
                        ? queries.impact(req, match.identity())
                        : queries.reachability(req, match.identity());
                for (var path : page.items()) {
                    if (path.vertices().isEmpty()) continue;
                    String targetId = path.vertices().getLast();
                    int dist = Math.max(1, path.vertices().size() - 1);
                    affected.add(Map.of(
                            "identity", targetId,
                            "label", safeNodeLabel(graph, match.context(), targetId),
                            "distance", dist,
                            "feasibility", path.feasibility().name()));
                }
            } catch (IllegalArgumentException ignored) {
                // Fall back to direct 1-hop graph edges when node is outside TYPE scope projection
            }
        }
        if (affected.isEmpty()) {
            // Direct 1-hop fallback when query service is omitted
            for (var edge : graph.edges(match.context())) {
                if (affected.size() >= 30) break;
                if ("graph.contains".equals(edge.kind()) || "graph.declares".equals(edge.kind())
                        || "graph.owns-source".equals(edge.kind())) continue;
                if (dir == Direction.INCOMING && edge.target().identities().contains(match.identity())
                        && !edge.source().equals(match.identity())) {
                    affected.add(Map.of("identity", edge.source(),
                            "label", safeNodeLabel(graph, match.context(), edge.source()),
                            "distance", 1, "classification", "MAY"));
                } else if (dir == Direction.OUTGOING && edge.source().equals(match.identity())) {
                    for (String t : edge.target().identities()) {
                        if (affected.size() >= 30) break;
                        affected.add(Map.of("identity", t,
                                "label", safeNodeLabel(graph, match.context(), t),
                                "distance", 1, "classification", "MAY"));
                    }
                }
            }
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("found", true);
        out.put("readOnly", true);
        out.put("seed", safeNodeLabel(graph, match.context(), match.identity()));
        out.put("direction", dir.name());
        out.put("maxDepth", maxDepth);
        out.put("affectedCount", affected.size());
        out.put("affected", affected);
        String json = boundJson(LlmTransport.neutralizeMapForLlm(out));
        return new ToolResult(json, "Impact(" + dir.name() + ") on " + out.get("seed") + ": " + affected.size() + " affected");
    }

    private static ToolResult runInspectAdvisoryLinks(Map<String, Object> args, KnowledgeGraph graph) {
        String entity = str(args, "entity", "");
        String path = str(args, "path", "");
        String kind = str(args, "kind", "");
        var full = AdvisoryOverlayService.forGraph(graph);
        var filtered = AdvisoryOverlayService.filter(full, entity, kind, path);
        var view = new LinkedHashMap<String, Object>(filtered.toView(20, 20));
        view.put("trust", "ADVISORY_UNVERIFIED");
        view.put("readOnly", true);
        String json = boundJson(LlmTransport.neutralizeMapForLlm(view));
        return new ToolResult(json, "Advisory links: " + filtered.links().size() + " link(s), " + filtered.nodes().size() + " node(s)");
    }

    private static ToolResult runReadMarkdownSection(Map<String, Object> args, KnowledgeGraph graph) {
        String path = str(args, "path", "");
        String heading = str(args, "heading", "");
        var idx = CapturedFileIndex.forGraph(graph);
        var out = new LinkedHashMap<String, Object>();
        out.put("readOnly", true);
        out.put("trust", "ADVISORY_UNVERIFIED");
        if (heading.isBlank()) {
            var res = idx.listMarkdownHeadings(graph, path);
            out.put("mode", "HEADINGS");
            out.put("found", res.found());
            out.put("path", LlmTransport.neutralizeInjectionSentinels(res.path()));
            out.put("headings", res.headings().stream().limit(40).map(h -> Map.of(
                    "title", LlmTransport.neutralizeInjectionSentinels(h.title()),
                    "level", h.level(),
                    "startLine", h.startLine(),
                    "endLine", h.endLine())).toList());
            return new ToolResult(boundJson(out), "Listed " + res.headings().size() + " heading(s) in " + res.path());
        }
        var sec = idx.readMarkdownSection(graph, path, heading);
        out.put("mode", "SECTION");
        out.put("found", sec.found());
        out.put("path", LlmTransport.neutralizeInjectionSentinels(sec.path()));
        out.put("heading", LlmTransport.neutralizeInjectionSentinels(sec.heading()));
        out.put("startLine", sec.startLine());
        out.put("endLine", sec.endLine());
        out.put("redactedSecretsCount", sec.redactedSecretsCount());
        if (sec.found()) {
            String sha = sec.digest().map(d -> d.value()).orElse("unknown");
            String body = sec.numberedSource();
            if (body.length() > 12_000) body = body.substring(0, 12_000) + "\n... [truncated]";
            out.put("content", LlmTransport.wrapPreRedactedUntrustedSource(sec.path() + "#" + sec.heading(), sha, body));
        }
        return new ToolResult(boundJson(out), sec.found()
                ? "Read markdown section '" + sec.heading() + "' in " + sec.path()
                : "Markdown section not found: " + heading);
    }

    private static KnowledgeGraph.Node findNode(KnowledgeGraph graph, String preferredCtx, String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String needle = symbol.strip();
        String needleLower = needle.toLowerCase(Locale.ROOT);
        var contexts = new LinkedHashSet<String>();
        if (preferredCtx != null && !preferredCtx.isBlank()) contexts.add(preferredCtx);
        graph.contexts().forEach(c -> contexts.add(c.identity()));

        KnowledgeGraph.Node partial = null;
        for (String ctx : contexts) {
            for (var n : graph.nodes(ctx)) {
                if (n.identity().equals(needle)) return n;
                String lbl = CapturedRepair.nodeLabel(graph, n);
                if (lbl == null) continue;
                if (lbl.equals(needle) || lbl.equalsIgnoreCase(needle) || lbl.endsWith("." + needle)) {
                    return n;
                }
                if (partial == null && lbl.toLowerCase(Locale.ROOT).contains(needleLower)) {
                    partial = n;
                }
            }
        }
        return partial;
    }

    private static String safeNodeLabel(KnowledgeGraph graph, String ctx, String id) {
        for (var n : graph.nodes(ctx)) {
            if (n.identity().equals(id)) {
                String lbl = CapturedRepair.nodeLabel(graph, n);
                if (lbl != null && !lbl.isBlank()) {
                    return LlmTransport.neutralizeInjectionSentinels(lbl);
                }
            }
        }
        return LlmTransport.neutralizeInjectionSentinels(id);
    }

    private static Map<String, Object> fn(String name, String desc, Map<String, Object> props, List<String> required) {
        var params = new LinkedHashMap<String, Object>();
        params.put("type", "object");
        params.put("properties", props);
        if (!required.isEmpty()) params.put("required", required);
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", desc,
                        "parameters", params));
    }

    private static String boundJson(Map<String, Object> payload) {
        String raw = CanonicalJson.write(payload);
        String redacted = LlmTransport.redactSecrets(raw).text();
        if (redacted.getBytes(StandardCharsets.UTF_8).length <= MAX_TOOL_RESULT_BYTES) {
            return redacted;
        }
        return CanonicalJson.write(Map.of(
                "truncated", true,
                "excerpt", redacted.substring(0, Math.min(MAX_TOOL_RESULT_BYTES - 256, redacted.length()))));
    }

    private static String str(Map<String, Object> map, String key, String fallback) {
        return map.get(key) instanceof String s && !s.isBlank() ? s.strip() : fallback;
    }

    private static int clampInt(Object raw, int fallback, int min, int max) {
        if (raw instanceof Number n) {
            int v = n.intValue();
            return Math.max(min, Math.min(max, v));
        }
        return fallback;
    }
}
