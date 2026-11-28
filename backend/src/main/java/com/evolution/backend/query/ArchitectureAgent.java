package com.evolution.backend.query;

import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2;
import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2Service;
import com.evolution.analysis.assessment.v2.ArchitectureScoreModelV2;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.javaparser.slice.JavaMethodSlicer;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureQuery.*;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.analysis.query.trace.MethodSliceResult;
import com.evolution.analysis.query.trace.VerticalTraceEngine;
import com.evolution.backend.query.advisory.AdvisoryOverlayService;
import com.evolution.backend.query.llm.LlmTransport;
import com.evolution.backend.query.repair.RepairContextPlanner;
import com.evolution.backend.query.search.CapturedSearchIndex;
import com.evolution.backend.query.trace.FullStackVerticalTraceService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Autonomous multi-step read-only GraphRAG investigation agent pinned to one immutable snapshot and graph.
 * Executes bounded tool calls against ArchitectureQueryService, CapturedFileIndex, RepoMapBuilder, and
 * PublishedReports, recording every observation as a citable evidence item (E#) with deterministic provenance.
 */
final class ArchitectureAgent {
    static final int MAX_ROUNDS = 18;
    static final int MAX_TOOL_JSON_BYTES = 48_000;
    private static final int COMPACT_PROTECTED_RECENT_MESSAGES = 6;
    private static final List<String> COMPACT_ITEM_LIST_KEYS = List.of(
            "items", "matches", "nodes", "findings", "files", "callers", "edges", "regions");
    static final Set<String> SERVICE_BRIDGE_KINDS =
            com.evolution.backend.query.mcp.McpToolDispatcher.SERVICE_BRIDGE_KINDS;

    interface ProgressListener {
        void onStage(String message);
        void onToolStart(int step, String tool, Map<String, Object> arguments);
        void onToolComplete(ArchitectureChat.AgentStep step);
        void onDelta(String textChunk);

        static ProgressListener noop() {
            return new ProgressListener() {
                @Override public void onStage(String message) {}
                @Override public void onToolStart(int step, String tool, Map<String, Object> arguments) {}
                @Override public void onToolComplete(ArchitectureChat.AgentStep step) {}
                @Override public void onDelta(String textChunk) {}
            };
        }
    }

    private ArchitectureAgent() {}

    /**
     * Schedules unresolved multi-file architectural debt, SCC cycle, or polyglot gap targets
     * deterministically by verified-SKG {@code (Impact_SKG_verified DESC, Richness_concrete_evidence DESC, entityId ASC)}.
     */
    static CapturedRepair.ImpactRichnessSchedule scheduleArchitectureTargets(
            KnowledgeGraph graph,
            String context,
            Collection<String> unresolvedEntityIds) {
        return CapturedRepair.scheduleRepairTargets(graph, context, unresolvedEntityIds);
    }

    /**
     * Computes compact {@code <= 30}-token static evidence hints ({@code # SpringTruth:} and {@code # SliceHint:})
     * for the specified entity IDs in {@code graph}.
     */
    static List<RepairContextPlanner.StaticEvidenceHint> compactHintsForEntities(
            KnowledgeGraph graph,
            String primaryContext,
            String secondaryContext,
            Collection<String> entityIds,
            Supplier<StopReason> stop) {
        return RepairContextPlanner.buildStaticEvidenceHints(graph, primaryContext, secondaryContext, entityIds, stop);
    }

    static ArchitectureChat.Result run(
            ArchitectureChatModel model,
            ArchitectureChat.Input input,
            ArchitectureChat.Context initialContext,
            ArchitectureQueryService queries,
            PublishedReports reports,
            Optional<AnalysisJobApi> jobs,
            Supplier<StopReason> stop,
            ProgressListener listener) {
        return run(model, input, initialContext, queries, reports, jobs, Optional.empty(), stop, listener);
    }

    static ArchitectureChat.Result run(
            ArchitectureChatModel model,
            ArchitectureChat.Input input,
            ArchitectureChat.Context initialContext,
            ArchitectureQueryService queries,
            PublishedReports reports,
            Optional<AnalysisJobApi> jobs,
            Optional<CapturedSearchIndex> searchIndex,
            Supplier<StopReason> stop,
            ProgressListener listener) {
        var progress = listener == null ? ProgressListener.noop() : listener;
        var graph = queries.graph();
        var evidence = new ArrayList<>(initialContext.evidence());
        var limitations = new ArrayList<>(initialContext.limitations());
        var sources = new ArrayList<>(initialContext.sources());
        var trace = new ArrayList<ArchitectureChat.AgentStep>();
        var toolSources = new LinkedHashMap<String, ArchitectureChatModel.SourceUse>();

        var initLimits = new ArrayList<String>();
        try {
            var initPacket = model.buildPacket(input, initialContext, graph, initLimits, stop);
            var offeredFiles = initPacket.files().stream().map(ModelSources.Selection::file).toList();
            var userPrompt = model.buildUserPrompt(input, initialContext, initPacket.files(), stop);

            var messages = new ArrayList<Map<String, Object>>();
            messages.add(Map.of("role", "system", "content", model.instructions(input.reviewer(), true, !input.reviewer())));
            messages.add(Map.of("role", "user", "content", userPrompt));

            var tools = toolSchemas(input.includeSource());
            Consumer<String> deltaConsumer = listener == null ? null : progress::onDelta;

            progress.onStage("Investigating architecture graph…");
            int stepCounter = 0;
            var seenCalls = new HashMap<String, Integer>();
            var graphIndex = GraphLookupIndex.build(graph);
            int compactionBudget = Math.max(24_000, (model.settings().requestBytes() * 3) / 4);
            for (int round = 0; round < MAX_ROUNDS; round++) {
                if (stop.get() != StopReason.NONE) {
                    throw new ArchitectureChatModel.Rejected(ArchitectureChatModel.Failure.CHAT_RESOURCE_LIMIT);
                }
                compactToolMessages(messages, compactionBudget);
                ArchitectureChatModel.ToolRoundResult roundResult;
                try {
                    roundResult = model.sendToolRoundWithAdapter(messages, tools, stop);
                } catch (ArchitectureChatModel.Rejected rejected) {
                    if (round == 0 && rejected.reason == ArchitectureChatModel.Failure.CHAT_PROVIDER_UNAVAILABLE) {
                        progress.onStage("Falling back to single-turn analysis…");
                        var fallback = model.complete(input, initialContext, graph, stop, deltaConsumer);
                        return ArchitectureChat.Result.advice(initialContext, fallback);
                    }
                    throw rejected;
                }
                var adapterResp = roundResult.response();
                var toolCalls = adapterResp.toolCalls();
                if (toolCalls.isEmpty()) {
                    String content = adapterResp.content();
                    if (content != null && !content.isBlank()) {
                        var currentContext = new ArchitectureChat.Context(initialContext.pin(), List.copyOf(evidence), List.copyOf(limitations), sources);
                        var completion = model.decodeAdapterResponse(currentContext, initPacket, adapterResp, initLimits,
                                input.reviewer(), true, List.copyOf(toolSources.values()), deltaConsumer);
                        return ArchitectureChat.Result.adviceWithTrace(currentContext, completion, trace);
                    }
                    break;
                }

                messages.add(buildAssistantToolMessage(adapterResp.content(), toolCalls));
                for (var call : toolCalls) {
                    if (stop.get() != StopReason.NONE) {
                        throw new ArchitectureChatModel.Rejected(ArchitectureChatModel.Failure.CHAT_RESOURCE_LIMIT);
                    }
                    String callId = (call.id() == null || call.id().isBlank()) ? ("call_" + (++stepCounter)) : call.id();
                    String toolName = call.name() == null ? "" : call.name();
                    var parsedArgs = parseArgsWithDiagnostics(call.argumentsJson());
                    int stepNo = ++stepCounter;
                    progress.onToolStart(stepNo, toolName, parsedArgs.args());
                    long startNs = System.nanoTime();
                    ToolOutcome outcome;
                    if (parsedArgs.error() != null) {
                        outcome = new ToolOutcome("Invalid tool arguments (" + toolName + ")", List.of(),
                                CanonicalJson.write(Map.of("error", parsedArgs.error(), "hint", "Provide a valid JSON object matching the tool schema.")));
                    } else {
                        String dedupeKey = toolName + "|" + CanonicalJson.write(parsedArgs.args());
                        int repeats = seenCalls.merge(dedupeKey, 1, Integer::sum);
                        if (repeats > 2) {
                            outcome = new ToolOutcome("Duplicate tool call suppressed (" + toolName + ")", List.of(),
                                    CanonicalJson.write(Map.of("duplicate", true,
                                            "message", "You already called " + toolName + " with these exact arguments. Synthesize your answer from existing [E#] evidence or choose a different tool.")));
                        } else {
                            outcome = executeTool(toolName, parsedArgs.args(), input, initialContext.pin(), queries, reports, jobs,
                                    searchIndex == null ? Optional.empty() : searchIndex,
                                    evidence, toolSources, graphIndex, stop);
                        }
                    }
                    long durationMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                    var step = new ArchitectureChat.AgentStep(stepNo, toolName, parsedArgs.args(), outcome.summary(), outcome.evidenceIds(), durationMs);
                    trace.add(step);
                    progress.onToolComplete(step);
                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", callId,
                            "content", outcome.toolOutputJson()));
                }
            }

            progress.onStage("Synthesizing evidence-backed response…");
            compactToolMessages(messages, compactionBudget);
            var finalContext = new ArchitectureChat.Context(initialContext.pin(), List.copyOf(evidence), List.copyOf(limitations), sources);
            var completion = model.completeMessages(finalContext, messages, offeredFiles, initLimits,
                    input.reviewer(), true, List.copyOf(toolSources.values()), stop, deltaConsumer);
            return ArchitectureChat.Result.adviceWithTrace(finalContext, completion, trace);
        } finally {
            model.clearRunTelemetry();
        }
    }

    private static Map<String, Object> buildAssistantToolMessage(String content, List<com.evolution.backend.query.llm.LlmProviderAdapter.ToolCall> toolCalls) {
        var wireCalls = new ArrayList<Map<String, Object>>(toolCalls.size());
        for (var tc : toolCalls) {
            wireCalls.add(Map.of(
                    "id", tc.id(),
                    "type", "function",
                    "function", Map.of("name", tc.name(), "arguments", tc.argumentsJson() == null ? "{}" : tc.argumentsJson())));
        }
        var msg = new LinkedHashMap<String, Object>();
        msg.put("role", "assistant");
        msg.put("content", content == null ? "" : content);
        msg.put("tool_calls", List.copyOf(wireCalls));
        return msg;
    }

    static void compactToolMessages(List<Map<String, Object>> messages, int byteBudget) {
        int estimated = 0;
        for (var m : messages) {
            Object c = m.get("content");
            if (c instanceof String s) estimated += s.length();
        }
        if (estimated <= byteBudget) return;
        // Sliding window: keep system prompt, user question, and most recent 6 messages verbatim.
        int protectFrom = Math.max(2, messages.size() - COMPACT_PROTECTED_RECENT_MESSAGES);
        for (int i = 2; i < protectFrom && estimated > byteBudget; i++) {
            var m = messages.get(i);
            if (!"tool".equals(m.get("role"))) continue;
            if (m.get("content") instanceof String content && content.length() > 1200) {
                String compact = extractCompactToolSummary(content);
                estimated -= (content.length() - compact.length());
                var copy = new LinkedHashMap<>(m);
                copy.put("content", compact);
                messages.set(i, copy);
            }
        }
    }

    static String extractCompactToolSummary(String toolJson) {
        try {
            var obj = GraphJson.object(GraphJson.read(toolJson, GraphJson.Limits.defaults()));
            var kept = new LinkedHashMap<String, Object>();
            for (String key : List.of("evidenceId", "tool", "count", "symbol", "path", "startLine", "endLine", "classification")) {
                if (obj.containsKey(key)) kept.put(key, obj.get(key));
            }
            var digests = new ArrayList<Map<String, Object>>();
            for (String listKey : COMPACT_ITEM_LIST_KEYS) {
                if (digests.size() >= 8) break;
                Object rawList = obj.get(listKey);
                if (!(rawList instanceof List<?> items)) continue;
                for (Object item : items) {
                    if (digests.size() >= 8) break;
                    if (!(item instanceof Map<?, ?> m)) continue;
                    var entry = new LinkedHashMap<String, Object>();
                    for (String f : List.of("id", "name", "symbol", "kind", "path", "line", "startLine")) {
                        if (m.containsKey(f) && m.get(f) != null) entry.put(f, m.get(f));
                    }
                    if (!entry.isEmpty()) digests.add(Collections.unmodifiableMap(entry));
                }
            }
            if (!digests.isEmpty()) {
                kept.put("itemDigests", List.copyOf(digests));
            }
            kept.put("compacted", true);
            kept.put("note", "Earlier tool payload compacted by sliding window; evidenceId and key symbol/path anchors preserved.");
            return CanonicalJson.write(kept);
        } catch (RuntimeException ignored) {
            return CanonicalJson.write(Map.of("compacted", true, "excerpt", toolJson.substring(0, Math.min(400, toolJson.length()))));
        }
    }

    private record ParsedArgs(Map<String, Object> args, String error) {}

    private static ParsedArgs parseArgsWithDiagnostics(Object raw) {
        if (raw == null) return new ParsedArgs(Map.of(), null);
        if (raw instanceof Map<?, ?>) return new ParsedArgs(GraphJson.object(raw), null);
        if (raw instanceof String s) {
            if (s.isBlank()) return new ParsedArgs(Map.of(), null);
            try { return new ParsedArgs(GraphJson.object(GraphJson.read(s, GraphJson.Limits.defaults())), null); }
            catch (IllegalArgumentException invalid) {
                return new ParsedArgs(Map.of(), "Malformed tool arguments JSON: " + invalid.getMessage());
            }
        }
        return new ParsedArgs(Map.of(), "Tool arguments must be a JSON object");
    }

    /** Per-run O(1) lookup index avoiding repeated O(N) graph node/document scans across tool calls. */
    private static final class GraphLookupIndex {
        private final KnowledgeGraph graph;
        private final Map<String, Map<String, KnowledgeGraph.Node>> nodesByContext = new HashMap<>();
        private final Map<String, String> docIdToPath = new HashMap<>();
        private final Map<String, String> labelCache = new HashMap<>();
        private boolean docsIndexed = false;
        private boolean provenanceLoaded = false;
        private Optional<GitHubProvenance.RepoCoords> githubCoords = Optional.empty();
        private GitHubProvenance.CodeownersIndex codeowners = GitHubProvenance.CodeownersIndex.EMPTY;

        private GraphLookupIndex(KnowledgeGraph graph) {
            this.graph = graph;
        }

        private void ensureProvenanceLoaded() {
            if (!provenanceLoaded) {
                provenanceLoaded = true;
                this.githubCoords = GitHubProvenance.extractFromGraph(graph);
                this.codeowners = GitHubProvenance.CodeownersIndex.fromGraph(graph);
            }
        }

        Optional<GitHubProvenance.RepoCoords> githubCoords() {
            ensureProvenanceLoaded();
            return githubCoords;
        }

        GitHubProvenance.CodeownersIndex codeowners() {
            ensureProvenanceLoaded();
            return codeowners;
        }

        Optional<String> permalink(String path, Integer startLine, Integer endLine) {
            return githubCoords().flatMap(c -> GitHubProvenance.blobPermalink(c, path, startLine, endLine));
        }

        List<String> ownersOf(String path) {
            return codeowners().ownersOf(path);
        }

        static GraphLookupIndex build(KnowledgeGraph graph) {
            return new GraphLookupIndex(graph);
        }

        KnowledgeGraph.Node node(String context, String id) {
            var map = nodesByContext.computeIfAbsent(context, ctx -> {
                var byId = new HashMap<String, KnowledgeGraph.Node>();
                for (var n : graph.nodes(ctx)) byId.putIfAbsent(n.identity(), n);
                return byId;
            });
            return map.get(id);
        }

        String documentPath(String docId) {
            if (!docsIndexed) {
                docsIndexed = true;
                for (var n : graph.nodes(graph.snapshot().value())) {
                    if (!"SOURCE_DOCUMENT".equals(n.kind())) continue;
                    for (var ref : n.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null) continue;
                        var obj = readSmallEvidence(ev);
                        if ("source.document".equals(ev.family()) && obj.get("path") instanceof String p) {
                            docIdToPath.putIfAbsent(n.identity(), p);
                            break;
                        }
                        if ("source.bytes".equals(ev.family()) && obj.get("document") instanceof Map<?, ?> d && d.get("path") instanceof String p) {
                            docIdToPath.putIfAbsent(n.identity(), p);
                            break;
                        }
                    }
                }
            }
            return docIdToPath.get(docId);
        }

        String label(String primaryCtx, String secondaryCtx, String entityId) {
            String key = primaryCtx + "|" + secondaryCtx + "|" + entityId;
            return labelCache.computeIfAbsent(key, k -> {
                var n = node(primaryCtx, entityId);
                if (n == null && !primaryCtx.equals(secondaryCtx)) n = node(secondaryCtx, entityId);
                if (n != null) {
                    String resolved = CapturedRepair.nodeLabel(graph, n);
                    if (resolved != null) return resolved;
                }
                return entityId;
            });
        }
    }

    private record ToolOutcome(String summary, List<String> evidenceIds, String toolOutputJson) {}

    private static ToolOutcome executeTool(
            String name,
            Map<String, Object> args,
            ArchitectureChat.Input input,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            PublishedReports reports,
            Optional<AnalysisJobApi> jobs,
            List<ArchitectureChat.Evidence> evidence,
            Map<String, ArchitectureChatModel.SourceUse> toolSources,
            GraphLookupIndex index,
            Supplier<StopReason> stop) {
        return executeTool(name, args, input, pin, queries, reports, jobs, Optional.empty(),
                evidence, toolSources, index, stop);
    }

    private static ToolOutcome executeTool(
            String name,
            Map<String, Object> args,
            ArchitectureChat.Input input,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            PublishedReports reports,
            Optional<AnalysisJobApi> jobs,
            Optional<CapturedSearchIndex> searchIndex,
            List<ArchitectureChat.Evidence> evidence,
            Map<String, ArchitectureChatModel.SourceUse> toolSources,
            GraphLookupIndex index,
            Supplier<StopReason> stop) {
        try {
            var createdIds = new ArrayList<String>();
            Map<String, Object> payload = switch (name) {
                case "search_symbols" -> {
                    String query = stringArg(args, "query", "");
                    Scope scope = parseScope(stringArg(args, "scope", "TYPE"));
                    String origin = stringArg(args, "origin", "");
                    var pop = origin.isBlank() ? Population.all() : new Population(Set.of(), Set.of(), Set.of(origin), Set.of());
                    var contexts = searchContexts(queries.graph(), pin);
                    var items = new ArrayList<Map<String, Object>>();
                    var seenIds = new HashSet<String>();
                    long totalMatches = 0;
                    String lastQueryId = pin.context();
                    for (String ctx : contexts) {
                        if (items.size() >= 30) break;
                        var req = new Request(pin.graph(), ctx, scope, Projection.BOUNDED_UNION, Direction.OUTGOING,
                                Set.of("graph.depends-on"), pop, false, Optional.empty(), Limits.defaults());
                        var res = queries.entities(req, query, new Page(25, Optional.empty()));
                        lastQueryId = res.query().value();
                        totalMatches += res.exactTotal().orElse((long) res.items().size());
                        for (var e : res.items()) {
                            if (items.size() >= 30) break;
                            if (seenIds.add(e.identity())) {
                                items.add(Map.of(
                                        "identity", e.identity(),
                                        "context", ctx,
                                        "kind", e.kind(),
                                        "name", e.qualifiedName().or(e::canonicalName).or(e::spelling).orElse(e.identity()),
                                        "origin", e.origin()));
                            }
                        }
                    }
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.SYMBOLS,
                            "Symbol search: " + (query.isBlank() ? scope.name() : query), lastQueryId,
                            Map.of("matches", items, "total", Math.max(totalMatches, items.size())));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "count", items.size(), "matches", items);
                }
                case "inspect_symbol" -> {
                    String rawId = stringArg(args, "identity", "");
                    if (rawId.isBlank()) yield Map.of("error", "identity is required");
                    var resolved = resolveSymbolOrRefuse(rawId, Scope.TYPE, pin, queries, index);
                    if (resolved.ambiguous()) yield resolved.refusalPayload();
                    String id = resolved.identity();
                    String effectiveCtx = resolveSymbolContext(queries.graph(), queries, pin, id);
                    var rels = Set.of("graph.depends-on", "java.calls", "java.extends", "java.implements", "java.type-uses");
                    var outReq = new Request(pin.graph(), effectiveCtx, Scope.ENTITY, Projection.BOUNDED_UNION,
                            Direction.OUTGOING, rels, Population.all(), false, Optional.empty(), Limits.defaults());
                    var inReq = new Request(pin.graph(), effectiveCtx, Scope.ENTITY, Projection.BOUNDED_UNION,
                            Direction.INCOMING, rels, Population.all(), false, Optional.empty(), Limits.defaults());
                    var entity = queries.entity(outReq, id);
                    var out = queries.expand(outReq, id);
                    var in = queries.expand(inReq, id);
                    var outList = summarizeNeighborhood(out);
                    var inList = summarizeNeighborhood(in);
                    String symLabel = entity.items().isEmpty() ? id : entity.items().getFirst().qualifiedName().orElse(id);
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.DEPENDENCIES,
                            "Symbol inspection: " + symLabel, id,
                            Map.of("context", effectiveCtx, "entity", entity.items(), "outgoing", outList, "incoming", inList));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "context", effectiveCtx, "symbol", symLabel, "outgoingCount", outList.size(),
                            "incomingCount", inList.size(), "outgoing", outList, "incoming", inList);
                }
                case "inspect_symbol_details" -> {
                    String rawId = stringArg(args, "identity", "");
                    if (rawId.isBlank()) yield Map.of("error", "identity is required");
                    var resolved = resolveSymbolOrRefuse(rawId, Scope.TYPE, pin, queries, index);
                    if (resolved.ambiguous()) yield resolved.refusalPayload();
                    yield executeInspectSymbolDetails(resolved.identity(), pin, queries, evidence, createdIds, index);
                }
                case "trace_path" -> {
                    String rawSrc = stringArg(args, "source", "");
                    String rawDst = stringArg(args, "target", "");
                    if (rawSrc.isBlank() || rawDst.isBlank()) yield Map.of("error", "source and target are required");
                    Scope scope = parseScope(stringArg(args, "scope", "TYPE"));
                    var resSrc = resolveSymbolOrRefuse(rawSrc, scope, pin, queries, index);
                    if (resSrc.ambiguous()) yield resSrc.refusalPayload();
                    var resDst = resolveSymbolOrRefuse(rawDst, scope, pin, queries, index);
                    if (resDst.ambiguous()) yield resDst.refusalPayload();
                    String src = resSrc.identity();
                    String dst = resDst.identity();
                    String effectiveCtx = resolvePathContext(queries.graph(), queries, pin, src, dst);
                    yield executeTracePath(src, dst, scope, effectiveCtx, pin, queries, evidence, createdIds, index);
                }
                case "analyze_impact" -> {
                    String rawId = stringArg(args, "identity", "");
                    if (rawId.isBlank()) yield Map.of("error", "identity is required");
                    Scope scope = parseScope(stringArg(args, "scope", "TYPE"));
                    var resolved = resolveSymbolOrRefuse(rawId, scope, pin, queries, index);
                    if (resolved.ambiguous()) yield resolved.refusalPayload();
                    String id = resolved.identity();
                    String effectiveCtx = resolveSymbolContext(queries.graph(), queries, pin, id);
                    var req = new Request(pin.graph(), effectiveCtx, scope, Projection.BOUNDED_UNION,
                            Direction.INCOMING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
                    var res = queries.impact(req, id);
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.DEPENDENCIES,
                            "Change blast radius", id, Map.of("context", effectiveCtx, "impact", res.items(), "status", res.status()));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "context", effectiveCtx, "items", res.items(), "status", res.status());
                }
                case "get_architecture_insights" -> {
                    Scope scope = parseScope(stringArg(args, "scope", "TYPE"));
                    int topN = Math.max(1, Math.min(15, intArg(args, "topN", 8)));
                    var primaryReq = new Request(pin.graph(), pin.context(), scope, Projection.BOUNDED_UNION,
                            Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
                    var matchedReport = findPublishedReport(reports, pin.graph(), pin.context());
                    var report = ArchitectureInsightsAdvisor.analyze(queries, primaryReq, matchedReport, topN);
                    Map<String, Object> rawData;
                    if (report.summary().analyzedEntities() == 0 && pin.context().equals(pin.snapshot())) {
                        rawData = aggregateInsightsAcrossSnapshot(queries, reports, pin, scope, topN);
                    } else {
                        var map = new LinkedHashMap<String, Object>();
                        map.put("summary", report.summary());
                        map.put("tangles", report.tangles().stream().limit(5).toList());
                        map.put("hotspots", report.hotspots().stream().limit(topN).toList());
                        map.put("coupling", report.couplingMatrix().cells().stream().limit(15).toList());
                        map.put("sdpViolations", report.sdpViolations().stream().limit(10).toList());
                        map.put("packageMetrics", report.packageMetrics().stream().limit(15).toList());
                        map.put("communityModularity", report.communityModularity());
                        map.put("communities", report.communities().stream().limit(15).toList());
                        map.put("misplacedTypes", report.misplacedTypes().stream().limit(15).toList());
                        map.put("communityBridges", report.communityBridges().stream().limit(15).toList());
                        var debtTargets = new LinkedHashSet<String>();
                        for (var h : report.hotspots()) debtTargets.add(h.identity());
                        for (var t : report.tangles()) debtTargets.addAll(t.members());
                        if (!debtTargets.isEmpty()) {
                            var schedule = scheduleArchitectureTargets(queries.graph(), pin.context(), debtTargets);
                            map.put("scheduledTargets", schedule.rankedTargets().stream().limit(10).toList());
                        }
                        rawData = Collections.unmodifiableMap(map);
                    }
                    var safeData = neutralizeMapForLlm(rawData);
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                            "Architecture insights (" + scope + ")", pin.context(), safeData);
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "insights", safeData);
                }
                case "get_architecture_score" -> {
                    String requestedCtx = stringArg(args, "context", pin.context());
                    yield executeGetArchitectureScore(requestedCtx, pin, queries, reports, evidence, createdIds);
                }
                case "get_repo_map" -> {
                    var seeds = stringListArg(args, "seeds");
                    int maxFiles = Math.max(1, Math.min(50, intArg(args, "maxFiles", 20)));
                    int maxTokens = Math.max(500, Math.min(12_000, intArg(args, "maxTokens", 4000)));
                    boolean compactHints = Boolean.TRUE.equals(args.get("compactHints"));
                    var mapResult = compactHints
                            ? RepoMapBuilder.buildCompressed(queries.graph(), Optional.of(pin.context()), seeds,
                                    maxFiles, maxTokens, RepoMapBuilder.SaltCompressionOptions.defaults(), stop,
                                    searchIndex.orElse(null))
                            : RepoMapBuilder.build(queries.graph(), Optional.of(pin.context()), seeds,
                                    maxFiles, maxTokens, stop, searchIndex.orElse(null));
                    var summaryData = new LinkedHashMap<String, Object>();
                    summaryData.put("algorithm", mapResult.algorithm());
                    summaryData.put("context", mapResult.context());
                    summaryData.put("totalFiles", mapResult.totalFiles());
                    summaryData.put("seedCount", mapResult.seedCount());
                    summaryData.put("estimatedTokens", mapResult.estimatedTokens());
                    summaryData.put("truncated", mapResult.truncated());
                    summaryData.put("files", mapResult.files());
                    summaryData.put("renderedMap", mapResult.renderedMap());
                    var safeSummary = neutralizeMapForLlm(summaryData);
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                            "Repository map (" + mapResult.algorithm() + ", " + mapResult.files().size() + " files)", pin.context(), safeSummary);
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "algorithm", mapResult.algorithm(),
                            "fileCount", mapResult.files().size(), "totalFiles", mapResult.totalFiles(),
                            "seedCount", mapResult.seedCount(),
                            "renderedMap", safeSummary.getOrDefault("renderedMap", ""),
                            "files", safeSummary.getOrDefault("files", List.of()));
                }
                case "list_findings" -> {
                    var reportIds = reports.forGraph(pin.graph());
                    boolean wholeSnapshot = pin.context().equals(pin.snapshot());
                    var findingsOut = new ArrayList<Map<String, Object>>();
                    for (var rid : reportIds) {
                        var opt = reports.get(rid);
                        if (opt.isEmpty()) continue;
                        var eval = opt.orElseThrow().evaluation();
                        if (!wholeSnapshot && !eval.context().equals(pin.context())) continue;
                        for (var f : eval.findings()) {
                            if (findingsOut.size() >= 25) break;
                            var row = new LinkedHashMap<String, Object>();
                            row.put("report", rid.value());
                            row.put("context", eval.context());
                            row.put("identity", f.identity().value());
                            row.put("rule", f.rule().id());
                            row.put("kind", f.kind());
                            row.put("classification", f.classification());
                            row.put("path", f.bundle().path());
                            if (!f.bundle().sources().isEmpty()) {
                                var src0 = f.bundle().sources().getFirst();
                                String docPath = index.documentPath(src0.span().document().value());
                                if (docPath != null) {
                                    row.put("sourcePath", docPath);
                                    var owners = index.ownersOf(docPath);
                                    if (!owners.isEmpty()) row.put("owners", owners);
                                    index.permalink(docPath, src0.span().startLine(), src0.span().endLine())
                                            .ifPresent(url -> row.put("permalink", url));
                                }
                            }
                            findingsOut.add(Collections.unmodifiableMap(row));
                        }
                    }
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.FINDING,
                            "Published policy findings", pin.context(), Map.of("findings", findingsOut));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "count", findingsOut.size(), "findings", findingsOut);
                }
                case "inspect_spring_wiring" -> {
                    var contexts = searchContexts(queries.graph(), pin);
                    var components = new ArrayList<Object>();
                    var configs = new ArrayList<Object>();
                    for (String ctx : contexts) {
                        if (components.size() >= 30 && configs.size() >= 15) break;
                        var req = new Request(pin.graph(), ctx, Scope.SPRING_COMPONENT, Projection.BOUNDED_UNION,
                                Direction.OUTGOING, Set.of("spring.selected-binding", "spring.injection-candidate"),
                                Population.all(), false, Optional.empty(), Limits.defaults());
                        var spring = queries.spring(req, Set.of("BEAN_DEFINITION_CANDIDATE", "BEAN_PRODUCER", "INJECTION_POINT", "ENDPOINT"), new Page(30, Optional.empty()));
                        var cfg = queries.configurations(req, new Page(15, Optional.empty()));
                        for (var item : spring.items()) {
                            if (components.size() >= 30) break;
                            components.add(item);
                        }
                        for (var item : cfg.items()) {
                            if (configs.size() >= 15) break;
                            configs.add(item);
                        }
                    }
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                            "Spring components & configurations", pin.context(),
                            Map.of("components", components, "configurations", configs));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "components", components.size(), "configurations", configs.size(),
                            "items", components, "configs", configs);
                }
                case "inspect_spring_conditions" -> {
                    String filter = stringArg(args, "filter", "");
                    yield executeInspectSpringConditions(filter, pin, queries, evidence, createdIds, index);
                }
                case "inspect_connections" -> {
                    yield executeInspectConnections(pin, queries, evidence, createdIds, index);
                }
                case "inspect_gaps" -> {
                    String filter = stringArg(args, "reasonFilter", "").trim().toUpperCase(Locale.ROOT);
                    if (input.job().isPresent() && jobs.isPresent()) {
                        var jobGaps = jobs.orElseThrow().gaps(input.job().orElseThrow());
                        var filtered = jobGaps.items().stream()
                                .filter(g -> filter.isEmpty() || g.reasonCode().contains(filter))
                                .limit(20)
                                .map(g -> Map.of(
                                        "reason", g.reasonCode(),
                                        "category", g.mechanismCategory(),
                                        "provider", g.detectingProvider(),
                                        "requirements", g.evidenceRequirements().stream().map(r -> r.question()).limit(3).toList(),
                                        "spans", g.sourceSpans().stream().limit(2).toList()))
                                .toList();
                        String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                                "Capability gaps" + (filter.isEmpty() ? "" : " (" + filter + ")"), pin.context(),
                                Map.of("gaps", filtered, "totalInPage", jobGaps.items().size()));
                        createdIds.add(eid);
                        yield Map.of("evidenceId", eid, "count", filtered.size(), "gaps", filtered);
                    }
                    var req = new Request(pin.graph(), pin.context(), Scope.TYPE, Projection.BOUNDED_UNION,
                            Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
                    var gapsPage = queries.gaps(req, GapView.CURRENT, new Page(50, Optional.empty()));
                    var graphGaps = gapsPage.items().stream()
                            .limit(20)
                            .map(e -> Map.of(
                                    "identity", e.identity(),
                                    "kind", e.kind(),
                                    "name", e.qualifiedName().or(e::canonicalName).or(e::spelling).orElse(e.identity()),
                                    "evidence", e.evidence()))
                            .toList();
                    var queryGaps = gapsPage.gaps().stream()
                            .filter(g -> filter.isEmpty() || g.reasonCode().contains(filter))
                            .limit(20)
                            .map(g -> Map.of(
                                    "reason", g.reasonCode(),
                                    "category", g.mechanismCategory(),
                                    "provider", g.detectingProvider()))
                            .toList();
                    var gapTargetIds = gapsPage.items().stream().map(EntityView::identity).limit(30).toList();
                    var scheduledGaps = gapTargetIds.isEmpty()
                            ? List.<CapturedRepair.ScheduledTarget>of()
                            : scheduleArchitectureTargets(queries.graph(), pin.context(), gapTargetIds)
                                    .rankedTargets().stream().limit(15).toList();
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                            "Capability gaps" + (filter.isEmpty() ? "" : " (" + filter + ")"), pin.context(),
                            Map.of("items", graphGaps, "queryGaps", queryGaps, "scheduledTargets", scheduledGaps));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "count", graphGaps.size() + queryGaps.size(),
                            "items", graphGaps, "queryGaps", queryGaps, "scheduledTargets", scheduledGaps);
                }
                case "list_captured_files" -> {
                    String query = stringArg(args, "query", "");
                    String language = stringArg(args, "language", "");
                    int limit = Math.max(1, Math.min(200, intArg(args, "limit", 50)));
                    int offset = Math.max(0, intArg(args, "offset", 0));
                    var listResult = CapturedFileIndex.forGraph(queries.graph()).list(query, language, offset, limit);
                    var fileViews = listResult.files().stream().map(CapturedFileIndex.Entry::toView).toList();
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                            "Captured files" + (query.isBlank() ? "" : ": " + query), pin.snapshot(),
                            Map.of("totalFiles", listResult.totalFiles(), "matchedFiles", listResult.matchedFiles(),
                                    "languages", listResult.languages(), "files", fileViews));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "totalFiles", listResult.totalFiles(),
                            "matchedFiles", listResult.matchedFiles(), "truncated", listResult.truncated(),
                            "languages", listResult.languages(), "files", neutralizeStringsDeep(fileViews));
                }
                case "search_captured_source" -> {
                    if (!input.includeSource()) {
                        yield Map.of("withheld", true, "reason", "Source disclosure is disabled by the user for this turn. Rely on structural metadata.");
                    }
                    String query = stringArg(args, "query", "");
                    if (query.isBlank()) yield Map.of("error", "query is required");
                    boolean regex = Boolean.TRUE.equals(args.get("regex"));
                    String pathFilter = stringArg(args, "pathFilter", "");
                    String language = stringArg(args, "language", "");
                    int maxMatches = Math.max(1, Math.min(60, intArg(args, "maxMatches", 30)));
                    var searchRes = CapturedFileIndex.forGraph(queries.graph())
                            .search(queries.graph(), query, regex, pathFilter, language, maxMatches, stop,
                                    searchIndex.orElse(null));
                    for (var su : searchRes.readSources()) {
                        String key = su.path() + "|" + su.digest().value();
                        toolSources.putIfAbsent(key, su);
                    }
                    var matchViews = searchRes.matches().stream().map(CapturedFileIndex.SearchMatch::toView).toList();
                    var llmMatches = searchRes.matches().stream().map(m -> {
                        String sha = m.digest().map(ContentDigest::value).orElse("");
                        var row = new LinkedHashMap<String, Object>();
                        row.put("path", LlmTransport.neutralizeInjectionSentinels(m.path()));
                        row.put("language", m.language());
                        row.put("line", m.line());
                        row.put("text", LlmTransport.wrapPreRedactedUntrustedSource(m.path(), sha, m.text()));
                        return Collections.unmodifiableMap(row);
                    }).toList();
                    var evPayload = new LinkedHashMap<String, Object>();
                    evPayload.put("query", query);
                    evPayload.put("regex", regex);
                    evPayload.put("engine", searchRes.engine());
                    evPayload.put("scannedFiles", searchRes.scannedFiles());
                    evPayload.put("matchedFiles", searchRes.matchedFiles());
                    evPayload.put("omittedFiles", searchRes.omittedFiles());
                    evPayload.put("redactedSecretCount", searchRes.redactedSecretCount());
                    evPayload.put("truncated", searchRes.truncated());
                    evPayload.put("matches", matchViews);
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.SOURCE,
                            "Source search (" + searchRes.engine() + "): " + query, pin.snapshot(),
                            Collections.unmodifiableMap(evPayload));
                    createdIds.add(eid);
                    var toolPayload = new LinkedHashMap<String, Object>();
                    toolPayload.put("evidenceId", eid);
                    toolPayload.put("engine", searchRes.engine());
                    toolPayload.put("count", llmMatches.size());
                    toolPayload.put("scannedFiles", searchRes.scannedFiles());
                    toolPayload.put("matchedFiles", searchRes.matchedFiles());
                    toolPayload.put("omittedFiles", searchRes.omittedFiles());
                    toolPayload.put("redactedSecretCount", searchRes.redactedSecretCount());
                    toolPayload.put("truncated", searchRes.truncated());
                    toolPayload.put("matches", llmMatches);
                    yield Collections.unmodifiableMap(toolPayload);
                }
                case "read_captured_source" -> {
                    if (!input.includeSource()) {
                        yield Map.of("withheld", true, "reason", "Source disclosure is disabled by the user for this turn. Rely on structural metadata.");
                    }
                    String targetPath = stringArg(args, "path", "");
                    if (targetPath.isBlank()) {
                        yield Map.of("error", "path is required");
                    }
                    int startLine = Math.max(1, intArg(args, "startLine", 1));
                    int requestedEnd = args.containsKey("endLine") ? intArg(args, "endLine", startLine + 120)
                            : startLine + CapturedFileIndex.MAX_LINES_PER_READ - 1;
                    var readRes = CapturedFileIndex.forGraph(queries.graph())
                            .read(queries.graph(), targetPath, startLine, requestedEnd);
                    if (readRes.ambiguous()) {
                        var safeCands = readRes.candidates().stream().map(LlmTransport::neutralizeInjectionSentinels).toList();
                        yield Map.of("found", false, "ambiguous", true,
                                "path", LlmTransport.neutralizeInjectionSentinels(targetPath), "candidates", safeCands);
                    }
                    if (!readRes.found()) {
                        yield Map.of("found", false, "path", LlmTransport.neutralizeInjectionSentinels(targetPath));
                    }
                    if (readRes.digest().isPresent()) {
                        boolean completeFile = readRes.startLine() == 1 && readRes.endLine() == readRes.totalLines();
                        var su = new ArchitectureChatModel.SourceUse(readRes.path(), readRes.digest().orElseThrow(), completeFile);
                        String key = su.path() + "|" + su.digest().value();
                        toolSources.merge(key, su, (a, b) -> new ArchitectureChatModel.SourceUse(a.path(), a.digest(), a.completeFile() || b.completeFile()));
                    }
                    String evRef = readRes.evidenceId().map(ContentDigest::value).orElse(readRes.path());
                    var srcEv = new LinkedHashMap<String, Object>();
                    srcEv.put("path", readRes.path());
                    srcEv.put("language", readRes.language());
                    srcEv.put("startLine", readRes.startLine());
                    srcEv.put("endLine", readRes.endLine());
                    srcEv.put("totalLines", readRes.totalLines());
                    srcEv.put("excerpt", readRes.numberedSource());
                    var readOwners = index.ownersOf(readRes.path());
                    if (!readOwners.isEmpty()) srcEv.put("owners", readOwners);
                    var readPermalink = index.permalink(readRes.path(), readRes.startLine(), readRes.endLine());
                    readPermalink.ifPresent(url -> srcEv.put("permalink", url));
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.SOURCE,
                            readRes.path() + " · L" + readRes.startLine() + "-" + readRes.endLine(), evRef,
                            Collections.unmodifiableMap(srcEv));
                    createdIds.add(eid);
                    var outMap = new LinkedHashMap<String, Object>();
                    outMap.put("evidenceId", eid);
                    outMap.put("found", true);
                    outMap.put("path", LlmTransport.neutralizeInjectionSentinels(readRes.path()));
                    outMap.put("language", readRes.language());
                    outMap.put("startLine", readRes.startLine());
                    outMap.put("endLine", readRes.endLine());
                    outMap.put("totalLines", readRes.totalLines());
                    outMap.put("truncated", readRes.truncated());
                    String shaStr = readRes.digest().map(ContentDigest::value).orElse("");
                    outMap.put("source", LlmTransport.wrapPreRedactedUntrustedSource(readRes.path(), shaStr, readRes.numberedSource()));
                    if (readRes.redactedSecretsCount() > 0) {
                        outMap.put("redactedSecretsCount", readRes.redactedSecretsCount());
                    }
                    if (!readOwners.isEmpty()) {
                        outMap.put("owners", readOwners.stream().map(LlmTransport::neutralizeInjectionSentinels).toList());
                    }
                    readPermalink.ifPresent(url -> outMap.put("permalink", url));
                    yield Collections.unmodifiableMap(outMap);
                }
                case "read_markdown_section" -> executeReadMarkdownSection(args, input, pin, queries, evidence, createdIds, toolSources, index);
                case "architecture_brief" -> executeArchitectureBrief(pin, queries, reports, evidence, createdIds);
                case "inspect_advisory_links" -> executeInspectAdvisoryLinks(args, pin, queries, evidence, createdIds);
                case "inspect_codeowners" -> executeInspectCodeowners(args, pin, queries, evidence, createdIds, index);
                case "vertical_trace" -> executeVerticalTrace(args, pin, queries, evidence, createdIds);
                case "trace_slice" -> executeTraceSlice(args, input, pin, queries, evidence, createdIds, toolSources);
                default -> Map.of("error", "Unknown read-only tool: " + name);
            };
            String json = boundedJson(payload);
            String summary = summarizeTool(name, payload);
            return new ToolOutcome(summary, List.copyOf(createdIds), json);
        } catch (RuntimeException failure) {
            String reason = com.evolution.backend.query.llm.LlmTransport.sanitizeText(failure.getMessage());
            String msg = reason.isBlank() ? "Tool query failed or reached bounds." : ("Tool query failed: " + reason);
            return new ToolOutcome("Tool unavailable (" + name + ")", List.of(),
                    CanonicalJson.write(Map.of("error", msg, "tool", name)));
        }
    }

    private static Map<String, Object> executeInspectCodeowners(
            Map<String, Object> args,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            List<ArchitectureChat.Evidence> evidence,
            List<String> createdIds,
            GraphLookupIndex index) {
        var co = index.codeowners();
        String singlePath = stringArg(args, "path", "");
        var batchPaths = stringListArg(args, "paths");
        String ownerQuery = stringArg(args, "owner", "");

        var out = new LinkedHashMap<String, Object>();
        out.put("found", co.found());
        co.sourceFile().ifPresent(sf -> {
            out.put("sourcePath", sf);
            index.permalink(sf, null, null).ifPresent(url -> out.put("sourcePermalink", url));
        });
        out.put("ruleCount", co.rules().size());
        index.githubCoords().ifPresent(c -> out.put("repository", c.fullName()));

        if (!singlePath.isBlank()) {
            var match = co.match(singlePath);
            var mView = new LinkedHashMap<>(match.toView());
            index.permalink(singlePath, null, null).ifPresent(url -> mView.put("permalink", url));
            out.put("match", Collections.unmodifiableMap(mView));
            out.put("owners", match.owners());
        }
        if (!batchPaths.isEmpty()) {
            var matches = new ArrayList<Map<String, Object>>();
            for (String p : batchPaths.stream().limit(25).toList()) {
                var m = co.match(p);
                var mView = new LinkedHashMap<>(m.toView());
                index.permalink(p, null, null).ifPresent(url -> mView.put("permalink", url));
                matches.add(Collections.unmodifiableMap(mView));
            }
            out.put("matches", matches);
        }
        if (!ownerQuery.isBlank()) {
            var allCapturedPaths = CapturedFileIndex.forGraph(queries.graph()).entries().stream()
                    .map(CapturedFileIndex.Entry::path).toList();
            var owned = co.pathsOwnedBy(ownerQuery, allCapturedPaths);
            out.put("ownerQuery", ownerQuery);
            out.put("ownedPathCount", owned.size());
            out.put("ownedPaths", owned.stream().limit(50).toList());
        }
        if (singlePath.isBlank() && batchPaths.isEmpty() && ownerQuery.isBlank()) {
            out.put("rules", co.rules().stream().limit(60).map(GitHubProvenance.CodeownersRule::toView).toList());
        }

        String label = !singlePath.isBlank() ? ("CODEOWNERS lookup: " + LlmTransport.neutralizeInjectionSentinels(singlePath))
                : (!ownerQuery.isBlank() ? ("CODEOWNERS files for " + LlmTransport.neutralizeInjectionSentinels(ownerQuery))
                        : "Repository CODEOWNERS rules");
        var safeOut = new LinkedHashMap<String, Object>(neutralizeMapForLlm(out));
        String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                label, co.sourceFile().orElse(pin.snapshot()), Collections.unmodifiableMap(new LinkedHashMap<>(safeOut)));
        createdIds.add(eid);
        safeOut.put("evidenceId", eid);
        return Collections.unmodifiableMap(safeOut);
    }

    private static Map<String, Object> executeInspectSymbolDetails(
            String id,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            List<ArchitectureChat.Evidence> evidence,
            List<String> createdIds,
            GraphLookupIndex index) {
        var graph = queries.graph();
        String effectiveCtx = resolveSymbolContext(graph, queries, pin, id);
        var rels = Set.of("graph.depends-on", "java.calls", "java.extends", "java.implements", "java.type-uses",
                "spring.selected-binding", "spring.injection-candidate");
        var outReq = new Request(pin.graph(), effectiveCtx, Scope.ENTITY, Projection.BOUNDED_UNION,
                Direction.OUTGOING, rels, Population.all(), false, Optional.empty(), Limits.defaults());
        var inReq = new Request(pin.graph(), effectiveCtx, Scope.ENTITY, Projection.BOUNDED_UNION,
                Direction.INCOMING, rels, Population.all(), false, Optional.empty(), Limits.defaults());
        var entityRes = queries.entity(outReq, id);
        var outList = summarizeNeighborhood(queries.expand(outReq, id));
        var inList = summarizeNeighborhood(queries.expand(inReq, id));

        String symLabel = entityRes.items().isEmpty()
                ? index.label(effectiveCtx, pin.snapshot(), id)
                : entityRes.items().getFirst().qualifiedName().or(entityRes.items().getFirst()::canonicalName)
                        .or(entityRes.items().getFirst()::spelling).orElse(id);

        // Extract path, span, annotations, Spring conditions, and associated gaps directly from node evidence
        String path = null;
        Map<String, Object> span = null;
        var annotations = new LinkedHashSet<String>();
        var springDetails = new ArrayList<Map<String, Object>>();
        String docId = null;

        KnowledgeGraph.Node targetNode = index.node(effectiveCtx, id);
        if (targetNode != null) {
            for (var ref : targetNode.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null) continue;
                var obj = readSmallEvidence(ev);
                switch (ev.family()) {
                    case "java.declaration" -> {
                        if (obj.get("entity") instanceof Map<?, ?> ent && ent.get("declaration") instanceof Map<?, ?> sp) {
                            span = copySpan(sp);
                            if (sp.get("document") instanceof String d) docId = d;
                        }
                    }
                    case "java.annotation-use" -> {
                        if (obj.get("annotation") instanceof String a) annotations.add(a);
                        else if (obj.get("type") instanceof String t) annotations.add(t);
                    }
                    case "spring.candidate", "spring.producer", "spring.condition", "spring.endpoint-declaration" -> {
                        var row = new LinkedHashMap<String, Object>();
                        row.put("family", ev.family());
                        if (obj.get("declaredNameKey") instanceof Map<?, ?> dk && dk.get("primary") instanceof String p)
                            row.put("beanName", p);
                        if (obj.get("kind") instanceof String k) row.put("kind", k);
                        if (obj.get("route") instanceof Map<?, ?> r) row.put("route", r);
                        if (obj.get("span") instanceof Map<?, ?> sp) {
                            if (span == null) span = copySpan(sp);
                            if (docId == null && sp.get("document") instanceof String d) docId = d;
                        }
                        springDetails.add(row);
                    }
                    case "polyglot.source-syntax" -> {
                        Map<?, ?> fields = obj.get("fields") instanceof Map<?, ?> f ? f : obj;
                        if (fields.get("path") instanceof String p) path = p;
                        if (fields.get("span") instanceof Map<?, ?> sp && span == null) span = copySpan(sp);
                    }
                    default -> {}
                }
            }
            // Follow annotation edges from targetNode
            for (var edge : graph.edges(effectiveCtx)) {
                if (!edge.source().equals(id)) continue;
                if ("graph.has-annotation-use".equals(edge.kind()) || "spring.has-condition".equals(edge.kind())) {
                    for (var ref : edge.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null) continue;
                        var obj = readSmallEvidence(ev);
                        if (obj.get("annotation") instanceof String a) annotations.add(a);
                        else if (obj.get("spelling") instanceof String s && s.length() <= 120) annotations.add(s);
                        else if (obj.get("kind") instanceof String k) springDetails.add(Map.of("edgeKind", edge.kind(), "kind", k));
                    }
                }
            }
        }
        if (path == null && docId != null) {
            path = index.documentPath(docId);
        }

        // Gather associated capability gaps
        var relatedGaps = new ArrayList<Map<String, Object>>();
        for (String ctx : Set.of(effectiveCtx, pin.snapshot())) {
            for (var n : graph.nodes(ctx)) {
                if (relatedGaps.size() >= 10) break;
                if (!"CAPABILITY_GAP".equals(n.kind()) && !"PROVIDER_CONFLICT".equals(n.kind())) continue;
                for (var ref : n.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null || !"evidence.gap".equals(ev.family())) continue;
                    String raw = ev.canonicalJson();
                    if (raw.contains(id) || (docId != null && raw.contains(docId))) {
                        var obj = readSmallEvidence(ev);
                        relatedGaps.add(Map.of(
                                "identity", n.identity(),
                                "reasonCode", obj.getOrDefault("reasonCode", "UNKNOWN"),
                                "category", obj.getOrDefault("mechanismCategory", "UNKNOWN")));
                    }
                }
            }
        }

        int fanIn = inList.size();
        int fanOut = outList.size();
        BigDecimal instability = (fanIn + fanOut == 0) ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(fanOut).divide(BigDecimal.valueOf(fanIn + fanOut), 4, RoundingMode.HALF_UP);
        var metrics = Map.of("fanIn", fanIn, "fanOut", fanOut, "instability", instability);

        var detail = new LinkedHashMap<String, Object>();
        detail.put("identity", id);
        detail.put("context", effectiveCtx);
        detail.put("symbol", symLabel);
        if (!entityRes.items().isEmpty()) {
            var e = entityRes.items().getFirst();
            detail.put("kind", e.kind());
            e.module().ifPresent(m -> detail.put("module", m));
            e.origin().ifPresent(o -> detail.put("origin", o));
            e.sourceSet().ifPresent(s -> detail.put("sourceSet", s));
        } else if (targetNode != null) {
            detail.put("kind", targetNode.kind());
        }
        if (path != null) {
            detail.put("path", path);
            var symOwners = index.ownersOf(path);
            if (!symOwners.isEmpty()) detail.put("owners", symOwners);
            Integer startL = span != null && span.get("startLine") instanceof Number n ? n.intValue() : null;
            Integer endL = span != null && span.get("endLine") instanceof Number n ? n.intValue() : null;
            index.permalink(path, startL, endL).ifPresent(url -> detail.put("permalink", url));
        }
        if (span != null) detail.put("span", span);
        if (!annotations.isEmpty()) detail.put("annotations", List.copyOf(annotations));
        if (!springDetails.isEmpty()) detail.put("spring", springDetails);
        detail.put("metrics", metrics);
        detail.put("incomingCount", fanIn);
        detail.put("outgoingCount", fanOut);
        detail.put("incoming", inList);
        detail.put("outgoing", outList);
        detail.put("gaps", relatedGaps);
        var staticHints = compactHintsForEntities(graph, effectiveCtx, pin.snapshot(), List.of(id), () -> StopReason.NONE);
        if (!staticHints.isEmpty()) {
            detail.put("staticEvidenceHints", staticHints.getFirst().lines());
        }

        String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.DEPENDENCIES,
                "Detailed symbol view: " + symLabel, id, detail);
        createdIds.add(eid);
        detail.put("evidenceId", eid);
        return detail;
    }

    private static Map<String, Object> executeInspectSpringConditions(
            String filter,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            List<ArchitectureChat.Evidence> evidence,
            List<String> createdIds,
            GraphLookupIndex index) {
        var graph = queries.graph();
        String filterLow = filter.toLowerCase(Locale.ROOT);
        var contexts = searchContexts(graph, pin);
        var conditions = new ArrayList<Map<String, Object>>();
        var realizations = new ArrayList<Map<String, Object>>();
        var dimensions = new ArrayList<Map<String, Object>>();
        var conditionalBeans = new ArrayList<Map<String, Object>>();

        // Collect configuration-space dimensions from spring.space (profiles visible, property values redacted)
        for (var ev : graph.evidence()) {
            if (!"spring.space".equals(ev.family()) || dimensions.size() >= 32) continue;
            var space = readSmallEvidence(ev);
            if (space.get("domains") instanceof List<?> domains) {
                for (Object d : domains) {
                    if (dimensions.size() >= 32) break;
                    if (!(d instanceof Map<?, ?> dom) || !(dom.get("variable") instanceof Map<?, ?> varObj)) continue;
                    String varKind = Objects.toString(varObj.get("kind"), "UNKNOWN");
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("kind", varKind);
                    if ("PROFILE".equals(varKind) && varObj.get("name") instanceof String pName) {
                        if (!filterLow.isEmpty() && !pName.toLowerCase(Locale.ROOT).contains(filterLow)) continue;
                        entry.put("name", pName);
                    } else {
                        entry.put("variableDigest", ContentDigest.sha256Utf8(CanonicalJson.write(varObj)).value());
                    }
                    if (dom.get("values") instanceof List<?> vals) entry.put("valueCount", vals.size());
                    dimensions.add(entry);
                }
            }
        }

        for (String ctx : contexts) {
            var req = new Request(pin.graph(), ctx, Scope.SPRING_COMPONENT, Projection.BOUNDED_UNION,
                    Direction.OUTGOING, Set.of("spring.selected-binding", "spring.injection-candidate"),
                    Population.all(), false, Optional.empty(), Limits.defaults());
            // Realizations
            if (realizations.size() < 20) {
                var rPage = queries.realizations(req, new Page(20, Optional.empty()));
                for (var rv : rPage.items()) {
                    if (realizations.size() >= 20) break;
                    if (!filterLow.isEmpty() && !rv.profiles().keySet().stream().anyMatch(p -> p.toLowerCase(Locale.ROOT).contains(filterLow))) {
                        continue;
                    }
                    realizations.add(Map.of("context", ctx, "identity", rv.identity(), "kind", rv.kind(), "profiles", rv.profiles()));
                }
            }
            // Condition & truth region nodes
            for (var node : graph.nodes(ctx)) {
                if (conditions.size() >= 30 && conditionalBeans.size() >= 30) break;
                if (Set.of("CONFIGURATION_CONDITION", "CONDITION_EXPRESSION", "TRUTH_REGION", "CONDITIONAL_FACT").contains(node.kind())
                        && conditions.size() < 30) {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("context", ctx);
                    row.put("identity", node.identity());
                    row.put("kind", node.kind());
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null) continue;
                        var obj = readSmallEvidence(ev);
                        row.put("family", ev.family());
                        if (obj.get("region") instanceof Map<?, ?> reg) {
                            if (reg.get("classification") != null) row.put("classification", reg.get("classification"));
                            if (reg.get("trueWorlds") instanceof List<?> tw) row.put("trueWorldCount", tw.size());
                            if (reg.get("falseWorlds") instanceof List<?> fw) row.put("falseWorldCount", fw.size());
                            if (reg.get("unknownWorlds") instanceof List<?> uw) row.put("unknownWorldCount", uw.size());
                        }
                        if (obj.get("classification") != null) row.put("classification", obj.get("classification"));
                        if (obj.get("conditionKind") != null) row.put("conditionKind", obj.get("conditionKind"));
                        if (obj.get("expression") instanceof String expr && expr.length() <= 160) row.put("expression", expr);
                        break;
                    }
                    if (filterLow.isEmpty() || row.toString().toLowerCase(Locale.ROOT).contains(filterLow)) {
                        conditions.add(row);
                    }
                } else if (Set.of("BEAN_DEFINITION_CANDIDATE", "BEAN_PRODUCER").contains(node.kind())
                        && conditionalBeans.size() < 30) {
                    String label = index.label(ctx, pin.snapshot(), node.identity());
                    if (!filterLow.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(filterLow)) continue;
                    var beanRow = new LinkedHashMap<String, Object>();
                    beanRow.put("context", ctx);
                    beanRow.put("identity", node.identity());
                    beanRow.put("kind", node.kind());
                    beanRow.put("name", label);
                    for (var edge : graph.edges(ctx)) {
                        if (edge.source().equals(node.identity()) && edge.region().isPresent()) {
                            beanRow.put("truthRegion", edge.region().orElseThrow());
                            break;
                        }
                    }
                    conditionalBeans.add(beanRow);
                }
            }
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("dimensions", dimensions);
        data.put("realizations", realizations);
        data.put("conditions", conditions);
        data.put("beans", conditionalBeans);
        String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                "Spring conditions & configuration space" + (filter.isEmpty() ? "" : " (" + filter + ")"),
                pin.context(), data);
        createdIds.add(eid);
        data.put("evidenceId", eid);
        data.put("conditionCount", conditions.size());
        data.put("realizationCount", realizations.size());
        return data;
    }

    private static Map<String, Object> executeInspectConnections(
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            List<ArchitectureChat.Evidence> evidence,
            List<String> createdIds,
            GraphLookupIndex index) {
        var graph = queries.graph();
        var req = new Request(pin.graph(), pin.snapshot(), Scope.ENTITY, Projection.BOUNDED_UNION,
                Direction.OUTGOING, SERVICE_BRIDGE_KINDS, Population.all(), false, Optional.empty(), Limits.defaults());
        var edges = queries.relationships(req, new Page(30, Optional.empty()));
        var enriched = new ArrayList<Map<String, Object>>();
        for (var rel : edges.items()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("identity", rel.identity());
            row.put("kind", rel.kind());
            row.put("targetState", rel.targetState());
            row.put("source", rel.source());
            row.put("sourceLabel", index.label(pin.snapshot(), pin.context(), rel.source()));
            var targetViews = new ArrayList<Map<String, Object>>();
            for (String tid : rel.targets()) {
                targetViews.add(Map.of(
                        "identity", tid,
                        "label", index.label(pin.snapshot(), pin.context(), tid)));
            }
            row.put("targets", targetViews);
            for (var ref : rel.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null) continue;
                var obj = readSmallEvidence(ev);
                Map<?, ?> fields = obj.get("fields") instanceof Map<?, ?> f ? f : obj;
                putIfPresent(row, "path", fields.get("path"));
                putIfPresent(row, "protocol", fields.get("protocol"));
                putIfPresent(row, "httpMethod", fields.get("httpMethod"));
                if (!row.containsKey("httpMethod")) putIfPresent(row, "httpMethod", fields.get("method"));
                putIfPresent(row, "url", fields.get("url"));
                if (!row.containsKey("url")) putIfPresent(row, "url", fields.get("route"));
                putIfPresent(row, "topic", fields.get("topic"));
                if (!row.containsKey("topic")) putIfPresent(row, "topic", fields.get("channel"));
                putIfPresent(row, "service", fields.get("service"));
                if (fields.get("route") instanceof Map<?, ?> routeMap) {
                    putIfPresent(row, "url", routeMap.get("path"));
                    putIfPresent(row, "httpMethod", routeMap.get("methods"));
                }
            }
            enriched.add(row);
        }
        String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.DEPENDENCIES,
                "Cross-language service & event connections", pin.snapshot(), Map.of("connections", enriched));
        createdIds.add(eid);
        return Map.of("evidenceId", eid, "count", enriched.size(), "connections", enriched);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        target.putIfAbsent(key, value);
    }

    private static Map<String, Object> executeGetArchitectureScore(
            String requestedContext,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            PublishedReports reports,
            List<ArchitectureChat.Evidence> evidence,
            List<String> createdIds) {
        var graph = queries.graph();
        String targetCtx = pin.context();
        if (requestedContext != null && !requestedContext.isBlank()) {
            boolean known = requestedContext.equals(graph.snapshot().value())
                    || graph.contexts().stream().anyMatch(c -> c.identity().equals(requestedContext));
            if (known) targetCtx = requestedContext;
        }

        var service = new ArchitectureAssessmentV2Service();
        ArchitectureAssessmentV2.Result result;
        if (targetCtx.equals(graph.snapshot().value())) {
            var customByContext = new LinkedHashMap<String, PolicyEvaluation.Result>();
            Optional<ContentDigest> firstCustomDigest = Optional.empty();
            for (var rid : reports.forGraph(pin.graph())) {
                var opt = reports.get(rid);
                if (opt.isEmpty()) continue;
                var eval = opt.orElseThrow().evaluation();
                if (isCustomPolicy(eval)) {
                    customByContext.putIfAbsent(eval.context(), eval);
                    if (firstCustomDigest.isEmpty()) firstCustomDigest = Optional.of(rid);
                }
            }
            result = service.assessRepository(queries, customByContext, firstCustomDigest, ArchitectureScoreModelV2.standard());
        } else {
            Optional<PolicyEvaluation.Result> customPolicy = Optional.empty();
            Optional<ContentDigest> customDigest = Optional.empty();
            for (var rid : reports.forGraph(pin.graph())) {
                var opt = reports.get(rid);
                if (opt.isEmpty()) continue;
                var eval = opt.orElseThrow().evaluation();
                if (eval.context().equals(targetCtx) && isCustomPolicy(eval)) {
                    customPolicy = Optional.of(eval);
                    customDigest = Optional.of(rid);
                    break;
                }
            }
            result = service.assess(queries, targetCtx, customPolicy, customDigest, ArchitectureScoreModelV2.standard());
        }

        var compact = compactScorePayload(result);
        String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                "Architecture Health Score v2 (" + result.scopeMode() + ")", targetCtx, compact);
        createdIds.add(eid);

        var response = new LinkedHashMap<String, Object>();
        response.put("evidenceId", eid);
        response.put("status", result.status().name());
        response.put("healthBand", result.healthBand());
        response.put("confidenceTier", result.confidence().tier().name());
        result.score().ifPresent(s -> response.put("score", s.setScale(2, RoundingMode.HALF_EVEN)));
        result.conservativeLowerBound().ifPresent(lb -> response.put("conservativeLowerBound", lb.setScale(2, RoundingMode.HALF_EVEN)));
        response.put("architectureScore", compact);
        return Collections.unmodifiableMap(response);
    }

    private static boolean isCustomPolicy(PolicyEvaluation.Result eval) {
        if (eval == null || eval.rules().isEmpty()) return false;
        return !(eval.rules().size() == 1
                && "workbench.no-dependency-cycles".equals(eval.rules().getFirst().rule().header().id()));
    }

    private static Map<String, Object> compactScorePayload(ArchitectureAssessmentV2.Result res) {
        var out = new LinkedHashMap<String, Object>();
        out.put("formula", res.formula().id() + ":" + res.formula().version());
        out.put("identity", res.identity().value());
        out.put("context", res.context());
        out.put("scopeMode", res.scopeMode());
        out.put("calibration", res.calibration());
        out.put("status", res.status().name());
        out.put("healthBand", res.healthBand());
        res.score().ifPresent(s -> out.put("score", s.setScale(2, RoundingMode.HALF_EVEN)));
        res.conservativeLowerBound().ifPresent(lb -> out.put("conservativeLowerBound", lb.setScale(2, RoundingMode.HALF_EVEN)));
        out.put("uncertaintyDelta", res.uncertaintyDelta().setScale(2, RoundingMode.HALF_EVEN));
        out.put("applicableWeight", res.applicableWeight().setScale(2, RoundingMode.HALF_EVEN));

        var c = res.confidence();
        var conf = new LinkedHashMap<String, Object>();
        conf.put("confidenceScore", c.confidenceScore().setScale(2, RoundingMode.HALF_EVEN));
        conf.put("tier", c.tier().name());
        conf.put("evidenceTier", c.evidenceTier());
        conf.put("parseCompleteness", c.parseCompleteness().setScale(4, RoundingMode.HALF_EVEN));
        conf.put("internalEdgeCompleteness", c.internalEdgeCompleteness().setScale(4, RoundingMode.HALF_EVEN));
        conf.put("overallSemanticResolution", c.overallSemanticResolution().setScale(4, RoundingMode.HALF_EVEN));
        conf.put("buildCompleteness", c.buildCompleteness().setScale(4, RoundingMode.HALF_EVEN));
        c.springSpaceDefiniteness().ifPresent(sd -> conf.put("springSpaceDefiniteness", sd.setScale(4, RoundingMode.HALF_EVEN)));
        conf.put("openGapCount", c.openGapCount());
        if (!c.gapBuckets().isEmpty()) conf.put("gapBuckets", c.gapBuckets());
        if (!c.limitations().isEmpty()) conf.put("limitations", c.limitations().stream().limit(6).toList());
        out.put("confidence", conf);

        var dims = new ArrayList<Map<String, Object>>();
        var offenders = new ArrayList<Map<String, Object>>();
        for (var d : res.dimensions()) {
            var dm = new LinkedHashMap<String, Object>();
            dm.put("id", d.id());
            dm.put("displayName", d.displayName());
            dm.put("iso25010Mapping", d.iso25010Mapping());
            dm.put("weight", d.weight().setScale(2, RoundingMode.HALF_EVEN));
            dm.put("status", d.status().name());
            d.score().ifPresent(s -> dm.put("score", s.setScale(2, RoundingMode.HALF_EVEN)));
            d.conservativeLowerBound().ifPresent(lb -> dm.put("conservativeLowerBound", lb.setScale(2, RoundingMode.HALF_EVEN)));
            dm.put("uncertaintyDelta", d.uncertaintyDelta().setScale(2, RoundingMode.HALF_EVEN));
            if (!d.reasons().isEmpty()) dm.put("reasons", d.reasons().stream().limit(5).toList());
            var subList = new ArrayList<Map<String, Object>>();
            for (var sm : d.subMetrics()) {
                var smMap = new LinkedHashMap<String, Object>();
                smMap.put("id", sm.id());
                smMap.put("displayName", sm.displayName());
                smMap.put("status", sm.status().name());
                sm.rawValue().ifPresent(rv -> smMap.put("rawValue", rv.setScale(4, RoundingMode.HALF_EVEN)));
                sm.score().ifPresent(sc -> smMap.put("score", sc.setScale(2, RoundingMode.HALF_EVEN)));
                smMap.put("explanation", sm.explanation());
                smMap.put("remediationHint", sm.remediationHint());
                if (!sm.witnesses().isEmpty()) {
                    smMap.put("witnesses", sm.witnesses().stream().limit(3)
                            .map(w -> Map.of("kind", w.kind(), "label", w.label(), "detail", w.detail()))
                            .toList());
                }
                subList.add(smMap);
                if (sm.score().isPresent() && sm.score().orElseThrow().compareTo(new BigDecimal("85")) < 0) {
                    var off = new LinkedHashMap<String, Object>();
                    off.put("dimensionId", d.id());
                    off.put("subMetricId", sm.id());
                    off.put("displayName", sm.displayName());
                    off.put("score", sm.score().orElseThrow().setScale(2, RoundingMode.HALF_EVEN));
                    sm.rawValue().ifPresent(rv -> off.put("rawValue", rv.setScale(4, RoundingMode.HALF_EVEN)));
                    off.put("explanation", sm.explanation());
                    off.put("remediationHint", sm.remediationHint());
                    if (!sm.witnesses().isEmpty()) {
                        off.put("witnesses", sm.witnesses().stream().limit(3)
                                .map(w -> Map.of("kind", w.kind(), "label", w.label(), "detail", w.detail()))
                                .toList());
                    }
                    offenders.add(off);
                }
            }
            dm.put("subMetrics", subList);
            dims.add(dm);
        }
        out.put("dimensions", dims);

        offenders.sort(Comparator.comparing(m -> (BigDecimal) m.get("score")));
        if (!offenders.isEmpty()) {
            out.put("topOffenders", offenders.stream().limit(8).toList());
        }

        res.inferredLayers().ifPresent(lm -> {
            var layerInfo = new LinkedHashMap<String, Object>();
            layerInfo.put("detectedStyle", lm.detectedStyle());
            layerInfo.put("layers", lm.layers().stream()
                    .map(ls -> Map.of("layer", ls.layer(), "packageCount", ls.packageCount(), "typeCount", ls.typeCount()))
                    .toList());
            out.put("inferredLayers", layerInfo);
        });

        if (!res.contextBreakdown().isEmpty()) {
            var contexts = res.contextBreakdown().stream()
                    .sorted(Comparator.comparing((ArchitectureAssessmentV2.ContextScoreSummary cs) -> cs.score().orElse(new BigDecimal("101")))
                            .thenComparing(Comparator.comparingInt(ArchitectureAssessmentV2.ContextScoreSummary::entityCount).reversed()))
                    .limit(15)
                    .map(cs -> {
                        var cm = new LinkedHashMap<String, Object>();
                        cm.put("contextId", cs.contextId());
                        cm.put("label", cs.label());
                        cm.put("kind", cs.kind());
                        cm.put("entityCount", cs.entityCount());
                        cm.put("status", cs.status().name());
                        cs.score().ifPresent(s -> cm.put("score", s.setScale(2, RoundingMode.HALF_EVEN)));
                        cs.conservativeLowerBound().ifPresent(lb -> cm.put("conservativeLowerBound", lb.setScale(2, RoundingMode.HALF_EVEN)));
                        cm.put("confidenceTier", cs.confidenceTier().name());
                        return cm;
                    }).toList();
            out.put("contextBreakdown", contexts);
        }

        res.policyReport().ifPresent(pr -> out.put("policyReport", pr.value()));
        if (!res.reasons().isEmpty()) out.put("reasons", res.reasons().stream().limit(8).toList());
        return Collections.unmodifiableMap(out);
    }

    private static Optional<PublishedReports.Report> findPublishedReport(PublishedReports reports, ContentDigest graphId, String context) {
        for (var rid : reports.forGraph(graphId)) {
            var opt = reports.get(rid);
            if (opt.isPresent() && opt.orElseThrow().evaluation().context().equals(context)) {
                return opt;
            }
        }
        return Optional.empty();
    }

    private static String resolveDocumentPath(KnowledgeGraph graph, String docId) {
        for (var node : graph.nodes(graph.snapshot().value())) {
            if (!node.identity().equals(docId)) continue;
            for (var ref : node.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null) continue;
                var obj = readSmallEvidence(ev);
                if ("source.document".equals(ev.family()) && obj.get("path") instanceof String p) return p;
                if ("source.bytes".equals(ev.family()) && obj.get("document") instanceof Map<?, ?> d && d.get("path") instanceof String p) return p;
            }
        }
        return null;
    }

    private static Map<String, Object> copySpan(Map<?, ?> sp) {
        var out = new LinkedHashMap<String, Object>();
        for (String k : List.of("startLine", "startColumn", "endLine", "endColumn")) {
            if (sp.get(k) != null) out.put(k, sp.get(k));
        }
        return out;
    }

    private static Map<String, Object> readSmallEvidence(GraphEvidence ev) {
        if (ev.canonicalJson().length() > 131_072) return Map.of();
        try {
            return GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(131_072, 32, 8_192)));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static List<String> searchContexts(KnowledgeGraph graph, ArchitectureChat.Pin pin) {
        var contexts = new ArrayList<String>();
        contexts.add(pin.context());
        if (pin.context().equals(pin.snapshot())) {
            graph.contexts().stream()
                    .filter(c -> c.snapshot().value().equals(pin.snapshot())
                            && c.analysis().isPresent()
                            && !c.identity().equals(pin.context()))
                    .map(KnowledgeGraph.Context::identity)
                    .forEach(contexts::add);
        }
        return contexts;
    }

    private static String resolveSymbolContext(KnowledgeGraph graph, ArchitectureQueryService queries,
                                               ArchitectureChat.Pin pin, String symbolId) {
        var contexts = searchContexts(graph, pin);
        if (contexts.size() == 1) return pin.context();
        for (String ctx : contexts) {
            var req = new Request(pin.graph(), ctx, Scope.ENTITY, Projection.BOUNDED_UNION,
                    Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
            if (!queries.entity(req, symbolId).items().isEmpty()) {
                return ctx;
            }
        }
        return pin.context();
    }

    private static String resolvePathContext(KnowledgeGraph graph, ArchitectureQueryService queries,
                                             ArchitectureChat.Pin pin, String src, String dst) {
        var contexts = searchContexts(graph, pin);
        if (contexts.size() == 1) return pin.context();
        String fallbackCtx = null;
        for (String ctx : contexts) {
            var req = new Request(pin.graph(), ctx, Scope.ENTITY, Projection.BOUNDED_UNION,
                    Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
            boolean hasSrc = !queries.entity(req, src).items().isEmpty();
            boolean hasDst = !queries.entity(req, dst).items().isEmpty();
            if (hasSrc && hasDst) return ctx;
            if (fallbackCtx == null && (hasSrc || hasDst)) fallbackCtx = ctx;
        }
        return fallbackCtx != null ? fallbackCtx : pin.context();
    }

    private static Map<String, Object> aggregateInsightsAcrossSnapshot(ArchitectureQueryService queries,
                                                                     PublishedReports reports,
                                                                     ArchitectureChat.Pin pin,
                                                                     Scope scope,
                                                                     int topN) {
        var contexts = searchContexts(queries.graph(), pin).stream()
                .filter(ctx -> !ctx.equals(pin.snapshot()))
                .limit(12)
                .toList();
        int totalEntities = 0, totalInternal = 0, totalExternal = 0, totalTangles = 0;
        int totalTangledEntities = 0, maxBlastRadius = 0, totalSdp = 0, totalFindings = 0, totalEdgeSdp = 0;
        BigDecimal weightedInstability = BigDecimal.ZERO;
        BigDecimal weightedAbstractness = BigDecimal.ZERO;
        BigDecimal weightedModularity = BigDecimal.ZERO;
        var allTangles = new ArrayList<ArchitectureInsightsAdvisor.Tangle>();
        var allHotspots = new ArrayList<ArchitectureInsightsAdvisor.Hotspot>();
        var allCoupling = new ArrayList<ArchitectureInsightsAdvisor.CouplingCell>();
        var allCommunities = new ArrayList<com.evolution.analysis.assessment.v2.LeidenCommunityDetector.EmergentCommunity>();
        var allMisplaced = new ArrayList<com.evolution.analysis.assessment.v2.LeidenCommunityDetector.MisplacedType>();
        var allBridges = new ArrayList<com.evolution.analysis.assessment.v2.LeidenCommunityDetector.CommunityBridge>();
        for (String ctx : contexts) {
            var req = new Request(pin.graph(), ctx, scope, Projection.BOUNDED_UNION,
                    Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
            var r = ArchitectureInsightsAdvisor.analyze(queries, req, findPublishedReport(reports, pin.graph(), ctx), topN);
            var s = r.summary();
            if (s.analyzedEntities() == 0) continue;
            totalEntities += s.analyzedEntities();
            totalInternal += s.internalDependencies();
            totalExternal += s.externalDependencies();
            totalTangles += s.tangleCount();
            totalTangledEntities += s.tangledEntityCount();
            maxBlastRadius = Math.max(maxBlastRadius, s.maxBlastRadius());
            totalSdp += s.sdpViolationCount();
            totalFindings += s.policyFindingCount();
            totalEdgeSdp += s.edgeSdpViolationCount();
            weightedInstability = weightedInstability.add(
                    s.averageInstability().multiply(BigDecimal.valueOf(s.analyzedEntities())));
            weightedAbstractness = weightedAbstractness.add(
                    s.abstractness().multiply(BigDecimal.valueOf(s.analyzedEntities())));
            weightedModularity = weightedModularity.add(
                    r.communityModularity().multiply(BigDecimal.valueOf(s.analyzedEntities())));
            allTangles.addAll(r.tangles());
            allHotspots.addAll(r.hotspots());
            allCoupling.addAll(r.couplingMatrix().cells());
            allCommunities.addAll(r.communities());
            allMisplaced.addAll(r.misplacedTypes());
            allBridges.addAll(r.communityBridges());
        }
        var avgInstability = totalEntities == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : weightedInstability.divide(BigDecimal.valueOf(totalEntities), 4, RoundingMode.HALF_UP);
        var avgAbstractness = totalEntities == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : weightedAbstractness.divide(BigDecimal.valueOf(totalEntities), 4, RoundingMode.HALF_UP);
        var avgModularity = totalEntities == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : weightedModularity.divide(BigDecimal.valueOf(totalEntities), 4, RoundingMode.HALF_UP);
        var distance = totalEntities == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : avgAbstractness.add(avgInstability).subtract(BigDecimal.ONE).abs().setScale(4, RoundingMode.HALF_UP);
        var tangledRatio = totalEntities == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(totalTangledEntities)
                        .divide(BigDecimal.valueOf(totalEntities), 4, RoundingMode.HALF_UP);
        var mergedSummary = new ArchitectureInsightsAdvisor.SummaryMetrics(
                totalEntities, totalInternal, totalExternal, totalTangles, totalTangledEntities,
                tangledRatio, avgInstability, maxBlastRadius, totalSdp, totalFindings,
                avgAbstractness, distance, totalEdgeSdp);
        var sortedTangles = allTangles.stream()
                .sorted(Comparator.comparingInt(ArchitectureInsightsAdvisor.Tangle::memberCount).reversed())
                .limit(5).toList();
        var sortedHotspots = allHotspots.stream()
                .sorted(Comparator.comparingInt(ArchitectureInsightsAdvisor.Hotspot::hotspotScore).reversed()
                        .thenComparing(Comparator.comparingInt(ArchitectureInsightsAdvisor.Hotspot::transitiveDependents).reversed()))
                .limit(topN).toList();
        var sortedCoupling = allCoupling.stream()
                .sorted(Comparator.comparingInt(ArchitectureInsightsAdvisor.CouplingCell::edgeCount).reversed())
                .limit(15).toList();
        var sortedCommunities = allCommunities.stream()
                .sorted(Comparator.comparingInt(com.evolution.analysis.assessment.v2.LeidenCommunityDetector.EmergentCommunity::memberCount).reversed()
                        .thenComparing(com.evolution.analysis.assessment.v2.LeidenCommunityDetector.EmergentCommunity::id))
                .limit(15).toList();
        var sortedMisplaced = allMisplaced.stream()
                .sorted(Comparator.comparingInt((com.evolution.analysis.assessment.v2.LeidenCommunityDetector.MisplacedType m) -> m.internalCommunityEdges() - m.declaredPackageEdges()).reversed()
                        .thenComparing(com.evolution.analysis.assessment.v2.LeidenCommunityDetector.MisplacedType::identity))
                .limit(15).toList();
        var sortedBridges = allBridges.stream()
                .sorted(Comparator.comparingInt(com.evolution.analysis.assessment.v2.LeidenCommunityDetector.CommunityBridge::edgeCount).reversed()
                        .thenComparing(com.evolution.analysis.assessment.v2.LeidenCommunityDetector.CommunityBridge::fromCommunityId))
                .limit(15).toList();
        var out = new LinkedHashMap<String, Object>();
        out.put("aggregatedContexts", contexts.size());
        out.put("summary", mergedSummary);
        out.put("tangles", sortedTangles);
        out.put("hotspots", sortedHotspots);
        out.put("coupling", sortedCoupling);
        out.put("communityModularity", avgModularity);
        out.put("communities", sortedCommunities);
        out.put("misplacedTypes", sortedMisplaced);
        out.put("communityBridges", sortedBridges);
        return Collections.unmodifiableMap(out);
    }

    private static List<Map<String, Object>> summarizeNeighborhood(Result<Neighborhood> deps) {
        var list = new ArrayList<Map<String, Object>>();
        for (var n : deps.items()) {
            var labels = new HashMap<String, String>();
            n.entities().forEach(e -> labels.put(e.identity(), e.qualifiedName().or(e::canonicalName).or(e::spelling).orElse(e.identity())));
            for (var edge : n.edges()) {
                if (list.size() >= 30) break;
                list.add(Map.of(
                        "source", labels.getOrDefault(edge.source(), edge.source()),
                        "target", labels.getOrDefault(edge.target(), edge.target()),
                        "kinds", edge.kinds(),
                        "classification", edge.classification()));
            }
        }
        return list;
    }

    private static String summarizeTool(String name, Map<String, Object> payload) {
        return switch (name) {
            case "search_symbols" -> "Found " + payload.getOrDefault("count", 0) + " matching symbols";
            case "inspect_symbol" -> "Inspected " + payload.getOrDefault("symbol", "symbol")
                    + " (" + payload.getOrDefault("incomingCount", 0) + " in / " + payload.getOrDefault("outgoingCount", 0) + " out)";
            case "inspect_symbol_details" -> "Deep-inspected " + payload.getOrDefault("symbol", "symbol")
                    + " (" + payload.getOrDefault("incomingCount", 0) + " in / " + payload.getOrDefault("outgoingCount", 0) + " out)";
            case "trace_path" -> Boolean.TRUE.equals(payload.get("found")) ? "Found dependency path" : "No direct path found";
            case "analyze_impact" -> "Computed transitive blast radius";
            case "get_architecture_insights" -> "Computed Tarjan cycles, SDP, hotspots & coupling";
            case "get_architecture_score" -> {
                Object score = payload.get("score");
                Object band = payload.getOrDefault("healthBand", payload.getOrDefault("status", "Withheld"));
                Object tier = payload.getOrDefault("confidenceTier", "UNKNOWN");
                yield score != null
                        ? "Architecture Health v2: " + score + "/100 (" + band + " · " + tier + " confidence)"
                        : "Architecture Health v2: " + band + " (" + tier + " confidence)";
            }
            case "get_repo_map" -> "Ranked " + payload.getOrDefault("fileCount", 0) + " files via Personalized PageRank";
            case "list_findings" -> "Listed " + payload.getOrDefault("count", 0) + " policy findings";
            case "inspect_spring_wiring" -> "Inspected " + payload.getOrDefault("components", 0) + " Spring items";
            case "inspect_spring_conditions" -> "Inspected " + payload.getOrDefault("conditionCount", 0)
                    + " conditions & " + payload.getOrDefault("realizationCount", 0) + " realizations";
            case "inspect_connections" -> "Found " + payload.getOrDefault("count", 0) + " cross-language bridges";
            case "inspect_gaps" -> "Inspected " + payload.getOrDefault("count", 0) + " capability gaps";
            case "inspect_codeowners" -> Boolean.TRUE.equals(payload.get("found"))
                    ? "Inspected CODEOWNERS (" + payload.getOrDefault("ruleCount", 0) + " rules)"
                    : "No CODEOWNERS file found in captured snapshot";
            case "list_captured_files" -> "Listed " + payload.getOrDefault("matchedFiles", 0) + " captured files";
            case "search_captured_source" -> Boolean.TRUE.equals(payload.get("withheld"))
                    ? "Source search withheld (metadata-only mode)"
                    : "Found " + payload.getOrDefault("count", 0) + " source matches";
            case "read_captured_source" -> Boolean.TRUE.equals(payload.get("withheld"))
                    ? "Source withheld (metadata-only mode)" : "Read captured source lines";
            case "read_markdown_section" -> Boolean.TRUE.equals(payload.get("withheld"))
                    ? "Markdown section withheld (metadata-only mode)"
                    : "Read Markdown section from " + payload.getOrDefault("path", "doc");
            case "architecture_brief" -> "Compiled 1-call architecture brief (Score v2, communities, hotspots & gaps)";
            case "inspect_advisory_links" -> "Inspected " + payload.getOrDefault("linkCount", 0)
                    + " quarantined advisory links (ADVISORY_UNVERIFIED)";
            case "vertical_trace" -> "Vertical trace: " + payload.getOrDefault("canonicalHopCount", 0)
                    + " canonical + " + payload.getOrDefault("advisoryHopCount", 0) + " advisory hops";
            case "trace_slice" -> Boolean.TRUE.equals(payload.get("withheld"))
                    ? "Code slice withheld (metadata-only mode)"
                    : "Sliced " + payload.getOrDefault("path", "source")
                            + " at L" + payload.getOrDefault("seedLine", 0);
            default -> "Completed " + name;
        };
    }

    /** Encodes tool output within MAX_TOOL_JSON_BYTES while guaranteeing valid JSON (never mid-JSON cut). */
    static String boundedJson(Map<String, Object> payload) {
        try {
            return new String(BoundedJson.encode(payload, MAX_TOOL_JSON_BYTES), StandardCharsets.UTF_8);
        } catch (BoundedJson.TooLarge large) {
            int[] listCaps = {20, 10, 5, 2, 1};
            int[] strCaps = {16_000, 8_000, 3_000, 1_000, 300};
            for (int pass = 0; pass < listCaps.length; pass++) {
                var trimmed = trimMap(payload, listCaps[pass], strCaps[pass], 0);
                trimmed.put("truncated", true);
                try {
                    return new String(BoundedJson.encode(trimmed, MAX_TOOL_JSON_BYTES), StandardCharsets.UTF_8);
                } catch (BoundedJson.TooLarge stillLarge) {
                    // Continue tightening until JSON fits cleanly.
                }
            }
            var fallback = new LinkedHashMap<String, Object>();
            if (payload.get("evidenceId") instanceof String eid) fallback.put("evidenceId", eid);
            fallback.put("truncated", true);
            fallback.put("summary", "Tool output exceeded 48KB; narrow scope, filter, or line range.");
            return CanonicalJson.write(fallback);
        }
    }

    private static Map<String, Object> trimMap(Map<?, ?> input, int maxList, int maxStr, int depth) {
        var out = new LinkedHashMap<String, Object>();
        if (depth > 6) {
            out.put("truncated", true);
            return out;
        }
        int entries = 0;
        for (var e : input.entrySet()) {
            if (++entries > 40) {
                out.put("truncated", true);
                break;
            }
            String key = Objects.toString(e.getKey(), "");
            out.put(key, trimValue(e.getValue(), maxList, maxStr, depth + 1));
        }
        return out;
    }

    private static Object trimValue(Object value, int maxList, int maxStr, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof String s) {
            if (s.length() <= maxStr) return s;
            return s.substring(0, maxStr) + "\n... [truncated]";
        }
        if (value instanceof List<?> list) {
            int limit = Math.min(list.size(), maxList);
            var copy = new ArrayList<Object>(limit);
            for (int i = 0; i < limit; i++) {
                copy.add(trimValue(list.get(i), Math.max(1, maxList / 2), maxStr, depth + 1));
            }
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            return trimMap(map, maxList, maxStr, depth + 1);
        }
        return value;
    }

    private static String stringArg(Map<String, Object> args, String key, String fallback) {
        return args.get(key) instanceof String s ? s.strip() : fallback;
    }

    private static List<String> stringListArg(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof List<?> list)) return List.of();
        var out = new ArrayList<String>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) out.add(s.strip());
        }
        return List.copyOf(out);
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        return args.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    private static Scope parseScope(String raw) {
        try { return Scope.valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { return Scope.TYPE; }
    }

    static List<Map<String, Object>> toolSchemas(boolean includeSource) {
        var list = new ArrayList<Map<String, Object>>();
        list.add(fn("search_symbols", "Search classes, packages, modules, or Spring components in the captured graph.",
                Map.of("query", Map.of("type", "string"),
                        "scope", Map.of("type", "string", "enum", List.of("TYPE", "PACKAGE", "MODULE", "SPRING_COMPONENT", "ENTITY")),
                        "origin", Map.of("type", "string", "enum", List.of("PROJECT", "DEPENDENCY", "JDK"))),
                List.of("query")));
        list.add(fn("inspect_symbol", "Inspect one symbol's incoming dependents and outgoing dependencies.",
                Map.of("identity", Map.of("type", "string")), List.of("identity")));
        list.add(fn("inspect_symbol_details", "Deep view of one symbol: kind, file path, span, Spring annotations/conditions, incoming/outgoing edges, gap records, and fan-in/out metrics.",
                Map.of("identity", Map.of("type", "string")), List.of("identity")));
        list.add(fn("trace_path", "Find the shortest dependency path between two symbol identities.",
                Map.of("source", Map.of("type", "string"), "target", Map.of("type", "string"),
                        "scope", Map.of("type", "string", "enum", List.of("TYPE", "PACKAGE", "MODULE"))),
                List.of("source", "target")));
        list.add(fn("analyze_impact", "Compute transitive incoming change blast radius for a symbol.",
                Map.of("identity", Map.of("type", "string"),
                        "scope", Map.of("type", "string", "enum", List.of("TYPE", "PACKAGE", "MODULE"))),
                List.of("identity")));
        list.add(fn("get_architecture_insights", "Run Tarjan SCC cycle detection, minimal break cuts, Martin instability/abstractness/SDP, and hotspots.",
                Map.of("scope", Map.of("type", "string", "enum", List.of("TYPE", "PACKAGE", "MODULE", "SPRING_COMPONENT")),
                        "topN", Map.of("type", "integer")),
                List.of()));
        list.add(fn("get_architecture_score", "Compute the 7-dimension Architecture Health Score v2 (cycles, coupling/propagation, modularity/stability, boundaries/layering, hubs/size, Spring/polyglot health, policy conformance) with independent Analysis Confidence and conservative lower bound.",
                Map.of("context", Map.of("type", "string", "description", "Optional context identity or snapshot identity for Whole Repository scoring; defaults to the pinned context.")),
                List.of()));
        list.add(fn("get_repo_map", "Compute a deterministic Personalized PageRank (PPR) repository map ranking the most structurally central files, declarations, endpoints, and gaps.",
                Map.of("seeds", Map.of("type", "array", "items", Map.of("type", "string")),
                        "maxFiles", Map.of("type", "integer"),
                        "maxTokens", Map.of("type", "integer")),
                List.of()));
        list.add(fn("list_findings", "List published architecture policy violations and MUST/MAY/UNKNOWN classifications.",
                Map.of(), List.of()));
        list.add(fn("inspect_spring_wiring", "Inspect Spring bean candidates, producers, endpoints, injection sites, and configuration spaces.",
                Map.of(), List.of()));
        list.add(fn("inspect_spring_conditions", "Inspect Spring @Profile, @Conditional* rules, truth regions, configuration dimensions, and active/inactive bean realizations.",
                Map.of("filter", Map.of("type", "string")), List.of()));
        list.add(fn("inspect_connections", "Inspect cross-language HTTP, gRPC, and async event bridge candidates with readable labels, paths, methods, URLs, and topics.",
                Map.of(), List.of()));
        list.add(fn("inspect_gaps", "Inspect capability gaps and obligations blocking complete semantic resolution.",
                Map.of("reasonFilter", Map.of("type", "string")), List.of()));
        list.add(fn("inspect_codeowners", "Inspect passive GitHub CODEOWNERS ownership rules from the captured snapshot: query owners for one or more paths, or list captured paths owned by a @user/@org/team handle.",
                Map.of("path", Map.of("type", "string", "description", "Optional repository-relative file path to match."),
                        "paths", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Optional list of up to 25 repository-relative file paths to resolve."),
                        "owner", Map.of("type", "string", "description", "Optional GitHub handle (e.g. @org/team or @user) to list owned captured files.")),
                List.of()));
        list.add(fn("list_captured_files", "List captured repository files across all languages with optional path glob/substring and language filter.",
                Map.of("query", Map.of("type", "string"), "language", Map.of("type", "string"),
                        "limit", Map.of("type", "integer"), "offset", Map.of("type", "integer")),
                List.of()));
        list.add(fn("architecture_brief", "One-call compressed executive overview: Architecture Health Score v2, confidence tier, top gaps, hotspots, tangles, Leiden emergent communities & misplaced types, and advisory link counts.",
                Map.of(), List.of()));
        list.add(fn("inspect_advisory_links", "Inspect quarantined deterministic non-code advisory links (Markdown/ADR mentions, SQL DDL <-> JPA entity mappings, Terraform resources, JS workspace aliases, MCP server configs). All results are ADVISORY_UNVERIFIED and excluded from canonical digests.",
                Map.of("entity", Map.of("type", "string", "description", "Optional entity identity, class name, or table name filter."),
                        "kind", Map.of("type", "string", "description", "Optional kind filter (e.g. SQL_TABLE_MAPS_JPA_ENTITY, DOC_MENTIONS_SYMBOL, TF_RESOURCE, MCP_SERVER)."),
                        "path", Map.of("type", "string", "description", "Optional source file path substring filter."),
                        "limit", Map.of("type", "integer")),
                List.of()));
        list.add(fn("vertical_trace", "Trace full-stack execution and data flow across Frontend, API Route, Spring Controller, Spring DI, Service, and JPA Entity layers, optionally stitching quarantined ADVISORY_UNVERIFIED SQL DDL and Terraform/HCL hops.",
                Map.of("seed", Map.of("type", "string", "description", "Symbol name, node identity, HTTP route path, source path, or SQL/Terraform advisory seed."),
                        "context", Map.of("type", "string", "description", "Optional context identity; defaults to the pinned context."),
                        "seedKind", Map.of("type", "string", "description", "Optional seed kind hint (e.g. ENDPOINT, TYPE, SQL_TABLE, TERRAFORM_RESOURCE)."),
                        "world", Map.of("type", "string", "description", "Optional Spring configuration world filter."),
                        "maxDepth", Map.of("type", "integer"),
                        "maxHops", Map.of("type", "integer"),
                        "includeAdvisory", Map.of("type", "boolean", "description", "Include quarantined ADVISORY_UNVERIFIED SQL and Terraform hops (default true).")),
                List.of("seed")));
        if (includeSource) {
            list.add(fn("search_captured_source", "Bounded regex or substring grep across all captured text files returning file:line snippets.",
                    Map.of("query", Map.of("type", "string"), "regex", Map.of("type", "boolean"),
                            "pathFilter", Map.of("type", "string"), "language", Map.of("type", "string"),
                            "maxMatches", Map.of("type", "integer")),
                    List.of("query")));
            list.add(fn("read_captured_source", "Read up to 250 line-numbered captured source lines from any verified snapshot file across Java and polyglot languages.",
                    Map.of("path", Map.of("type", "string"), "startLine", Map.of("type", "integer"), "endLine", Map.of("type", "integer")),
                    List.of("path")));
            list.add(fn("read_markdown_section", "List Markdown headings or read one exact heading section on-demand from a captured .md/.mdx file with startLine..endLine, SHA-256 provenance, and secret redaction.",
                    Map.of("path", Map.of("type", "string"),
                            "heading", Map.of("type", "string", "description", "Optional heading title or anchor slug. Omit to list all headings with line spans.")),
                    List.of("path")));
            list.add(fn("trace_slice", "Compute a lazy, pre-redacted intra-method CFG/DFG/CDG program slice for a captured Java file at a given 1-based line number (SOURCE_SYNTAX, zero secret leakage).",
                    Map.of("path", Map.of("type", "string", "description", "Repository-relative captured Java file path."),
                            "line", Map.of("type", "integer", "description", "1-based seed line number inside the target method."),
                            "methodSignature", Map.of("type", "string", "description", "Optional method signature or name disambiguator."),
                            "direction", Map.of("type", "string", "enum", List.of("BACKWARD", "FORWARD", "BOTH")),
                            "maxStatements", Map.of("type", "integer")),
                    List.of("path", "line")));
        }
        return List.copyOf(list);
    }

    private record SymbolResolution(boolean ambiguous, String identity, Map<String, Object> refusalPayload) {}

    private static SymbolResolution resolveSymbolOrRefuse(
            String rawQuery,
            Scope preferredScope,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            GraphLookupIndex index) {
        String q = rawQuery == null ? "" : rawQuery.strip();
        if (q.isEmpty()) return new SymbolResolution(false, "", Map.of());
        // Direct hit on any exact node identity in pinned or snapshot contexts
        for (String ctx : searchContexts(queries.graph(), pin)) {
            if (index.node(ctx, q) != null) {
                return new SymbolResolution(false, q, Map.of());
            }
        }
        // Only perform short-symbol ambiguity lookup when input does not already look like a full canonical identity
        if (q.contains("sha256:") || q.startsWith("java:v1:") || q.startsWith("spring:") || q.startsWith("polyglot:")) {
            return new SymbolResolution(false, q, Map.of());
        }
        Scope primaryScope = preferredScope == null ? Scope.TYPE : preferredScope;
        List<Scope> scopesToTry = primaryScope == Scope.ENTITY ? List.of(Scope.ENTITY) : List.of(primaryScope, Scope.ENTITY);
        var candidates = new LinkedHashMap<String, Map<String, Object>>();
        for (Scope tryScope : scopesToTry) {
            for (String ctx : searchContexts(queries.graph(), pin)) {
                if (candidates.size() >= 15) break;
                var req = new Request(pin.graph(), ctx, tryScope, Projection.BOUNDED_UNION, Direction.OUTGOING,
                        Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
                var res = queries.entities(req, q, new Page(15, Optional.empty()));
                for (var e : res.items()) {
                    String name = e.qualifiedName().or(e::canonicalName).or(e::spelling).orElse(e.identity());
                    String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
                    if (name.equalsIgnoreCase(q) || simple.equalsIgnoreCase(q) || e.identity().equals(q)) {
                        String path = "";
                        var node = index.node(ctx, e.identity());
                        if (node != null) {
                            for (var ref : node.evidence()) {
                                var ev = queries.graph().evidence(ref).orElse(null);
                                if (ev == null) continue;
                                var obj = readSmallEvidence(ev);
                                if ("java.declaration".equals(ev.family())
                                        && obj.get("entity") instanceof Map<?, ?> ent
                                        && ent.get("declaration") instanceof Map<?, ?> sp
                                        && sp.get("document") instanceof String docId) {
                                    path = Objects.toString(index.documentPath(docId), "");
                                    break;
                                }
                            }
                        }
                        String display = path.isEmpty() ? name : (path + "::" + simple);
                        candidates.putIfAbsent(e.identity(), Map.of(
                                "display", display,
                                "symbol", name,
                                "identity", e.identity(),
                                "kind", e.kind(),
                                "path", path));
                    }
                }
            }
            if (!candidates.isEmpty()) break;
        }
        if (candidates.size() == 1) {
            return new SymbolResolution(false, candidates.keySet().iterator().next(), Map.of());
        }
        if (candidates.size() > 1) {
            return new SymbolResolution(true, q, Map.of(
                    "ambiguous", true,
                    "query", q,
                    "message", "Multiple symbols match '" + q + "'. Re-invoke with one exact identity from candidates.",
                    "candidates", List.copyOf(candidates.values())));
        }
        return new SymbolResolution(false, q, Map.of());
    }

    private static Map<String, Object> executeTracePath(
            String src,
            String dst,
            Scope scope,
            String effectiveCtx,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            List<ArchitectureChat.Evidence> evidence,
            List<String> createdIds,
            GraphLookupIndex index) {
        var req = new Request(pin.graph(), effectiveCtx, scope, Projection.BOUNDED_UNION,
                Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
        var shortestRes = queries.shortestPath(req, src, dst);
        var candidates = new ArrayList<>(shortestRes.items());
        // Check bounded all-paths to collect equal-length ties without modifying frozen M5 BoundedGraphTraversal
        if (!candidates.isEmpty()) {
            int minEdges = candidates.stream().mapToInt(p -> p.edges().size()).min().orElse(0);
            var allPaths = queries.findPaths(req, src, dst);
            var seenKey = new HashSet<List<String>>();
            for (var p : candidates) seenKey.add(p.vertices());
            for (var p : allPaths.items()) {
                if (p.edges().size() == minEdges && seenKey.add(p.vertices())) {
                    candidates.add(p);
                }
            }
        }

        // Rank equal-length shortest paths: (1) more PROJECT nodes, (2) stronger edge certainty (MUST > MAY > UNKNOWN)
        var projectSet = new HashSet<String>();
        var checkedVertices = new HashSet<String>();
        var entityReq = new Request(pin.graph(), effectiveCtx, Scope.ENTITY, Projection.BOUNDED_UNION,
                Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
        for (var p : candidates) {
            for (String v : p.vertices()) {
                if (!checkedVertices.add(v)) continue;
                var e = queries.entity(entityReq, v);
                if (!e.items().isEmpty() && e.items().getFirst().origin().map("PROJECT"::equals).orElse(false)) {
                    projectSet.add(v);
                }
            }
        }
        candidates.sort(Comparator.comparingInt((com.evolution.analysis.query.ArchitectureQuery.Path p) -> p.edges().size())
                .thenComparing(Comparator.comparingInt((com.evolution.analysis.query.ArchitectureQuery.Path p) -> countProjectNodes(p, projectSet)).reversed())
                .thenComparing(Comparator.comparingInt(ArchitectureAgent::pathStrengthScore).reversed())
                .thenComparing(p -> String.join("->", p.vertices())));

        Map<String, Object> weakestLink = null;
        Map<String, Object> jointTruth = null;
        if (!candidates.isEmpty()) {
            var best = candidates.getFirst();
            EdgeView weakest = null;
            int weakestRank = Integer.MAX_VALUE;
            var regions = new LinkedHashSet<String>();
            boolean anyUnknown = false;
            boolean anyMay = false;
            for (var edge : best.edges()) {
                int r = classificationRank(edge.classification());
                if (r < weakestRank) {
                    weakestRank = r;
                    weakest = edge;
                }
                if (edge.classification() == Classification.UNKNOWN) anyUnknown = true;
                else if (edge.classification() == Classification.MAY) anyMay = true;
                regions.addAll(edge.regions());
            }
            if (weakest != null) {
                var wl = new LinkedHashMap<String, Object>();
                wl.put("edgeIdentity", weakest.identity());
                wl.put("source", weakest.source());
                wl.put("sourceLabel", index.label(effectiveCtx, pin.snapshot(), weakest.source()));
                wl.put("target", weakest.target());
                wl.put("targetLabel", index.label(effectiveCtx, pin.snapshot(), weakest.target()));
                wl.put("classification", weakest.classification().name());
                wl.put("kinds", weakest.kinds());
                if (!weakest.regions().isEmpty()) wl.put("regions", weakest.regions());
                weakestLink = Collections.unmodifiableMap(wl);
            }
            String jointClass = anyUnknown ? "UNKNOWN" : (anyMay ? "MAY" : "MUST");
            boolean multiRegion = regions.size() > 1;
            var jt = new LinkedHashMap<String, Object>();
            jt.put("jointClassification", jointClass);
            jt.put("regions", List.copyOf(regions));
            jt.put("distinctRegionCount", regions.size());
            jt.put("jointSatisfiabilityChecked", !multiRegion);
            if (multiRegion) {
                jt.put("note", "Path crosses multiple distinct conditional regions; pairwise/joint SAT satisfiability across regions is not assumed.");
            }
            jt.put("internalProjectNodes", countProjectNodes(best, projectSet));
            jt.put("totalNodes", best.vertices().size());
            jointTruth = Collections.unmodifiableMap(jt);
        }

        var evData = new LinkedHashMap<String, Object>();
        evData.put("context", effectiveCtx);
        evData.put("paths", candidates);
        evData.put("status", shortestRes.status());
        if (weakestLink != null) evData.put("weakestLink", weakestLink);
        if (jointTruth != null) evData.put("jointTruthRegion", jointTruth);
        String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.DEPENDENCIES,
                "Shortest path trace", shortestRes.query().value(), Collections.unmodifiableMap(evData));
        createdIds.add(eid);

        var out = new LinkedHashMap<String, Object>();
        out.put("evidenceId", eid);
        out.put("context", effectiveCtx);
        out.put("found", !candidates.isEmpty());
        out.put("paths", candidates);
        if (weakestLink != null) out.put("weakestLink", weakestLink);
        if (jointTruth != null) out.put("jointTruthRegion", jointTruth);
        return Collections.unmodifiableMap(out);
    }

    private static int countProjectNodes(com.evolution.analysis.query.ArchitectureQuery.Path path, Set<String> projectSet) {
        int c = 0;
        for (String v : path.vertices()) if (projectSet.contains(v)) c++;
        return c;
    }

    private static int pathStrengthScore(com.evolution.analysis.query.ArchitectureQuery.Path path) {
        if (path.edges().isEmpty()) return 100;
        int minEdge = 100;
        int sum = 0;
        for (var e : path.edges()) {
            int r = classificationRank(e.classification());
            minEdge = Math.min(minEdge, r);
            sum += r;
        }
        return minEdge * 100 + sum;
    }

    private static int classificationRank(Classification c) {
        if (c == null) return 1;
        return switch (c) {
            case MUST -> 3;
            case MAY -> 2;
            case UNKNOWN -> 1;
            default -> 0;
        };
    }

    private static Map<String, Object> executeReadMarkdownSection(
            Map<String, Object> args,
            ArchitectureChat.Input input,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            List<ArchitectureChat.Evidence> evidence,
            List<String> createdIds,
            Map<String, ArchitectureChatModel.SourceUse> toolSources,
            GraphLookupIndex index) {
        if (!input.includeSource()) {
            return Map.of("withheld", true, "reason", "Source disclosure is disabled by the user for this turn.");
        }
        String path = stringArg(args, "path", "");
        if (path.isBlank()) return Map.of("error", "path is required");
        String heading = stringArg(args, "heading", "");
        var fileIndex = CapturedFileIndex.forGraph(queries.graph());

        if (heading.isBlank()) {
            var res = fileIndex.listMarkdownHeadings(queries.graph(), path);
            if (res.ambiguous()) {
                var safeCands = res.candidates().stream().map(LlmTransport::neutralizeInjectionSentinels).toList();
                return Map.of("found", false, "ambiguous", true,
                        "path", LlmTransport.neutralizeInjectionSentinels(path), "candidates", safeCands);
            }
            if (!res.found()) {
                return Map.of("found", false, "path", LlmTransport.neutralizeInjectionSentinels(path));
            }
            var hViews = res.headings().stream().map(CapturedFileIndex.MarkdownHeading::toView).toList();
            var safeHeadings = res.headings().stream().map(h -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("level", h.level());
                map.put("title", LlmTransport.neutralizeInjectionSentinels(h.title()));
                map.put("anchor", LlmTransport.neutralizeInjectionSentinels(h.anchor()));
                map.put("startLine", h.startLine());
                map.put("endLine", h.endLine());
                return Collections.unmodifiableMap(map);
            }).toList();
            String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.SOURCE,
                    res.path() + " · Markdown headings", res.evidenceId().map(ContentDigest::value).orElse(res.path()),
                    Map.of("path", res.path(), "headings", hViews));
            createdIds.add(eid);
            return Map.of("evidenceId", eid, "found", true,
                    "path", LlmTransport.neutralizeInjectionSentinels(res.path()),
                    "digest", res.digest().map(ContentDigest::value).orElse(""),
                    "headings", safeHeadings);
        }

        var sec = fileIndex.readMarkdownSection(queries.graph(), path, heading);
        if (sec.ambiguous()) {
            var safeCands = sec.candidates().stream().map(LlmTransport::neutralizeInjectionSentinels).toList();
            return Map.of("found", false, "ambiguous", true,
                    "path", LlmTransport.neutralizeInjectionSentinels(sec.path()),
                    "heading", LlmTransport.neutralizeInjectionSentinels(heading),
                    "candidates", safeCands);
        }
        if (!sec.found()) {
            var safeAvail = sec.candidates().stream().map(LlmTransport::neutralizeInjectionSentinels).toList();
            return Map.of("found", false,
                    "path", LlmTransport.neutralizeInjectionSentinels(sec.path()),
                    "heading", LlmTransport.neutralizeInjectionSentinels(heading),
                    "availableHeadings", safeAvail);
        }
        if (sec.digest().isPresent()) {
            var su = new ArchitectureChatModel.SourceUse(sec.path(), sec.digest().orElseThrow(), false);
            toolSources.putIfAbsent(su.path() + "|" + su.digest().value(), su);
        }
        String sha = sec.digest().map(ContentDigest::value).orElse("");
        var evObj = new LinkedHashMap<String, Object>();
        evObj.put("path", sec.path());
        evObj.put("heading", sec.heading());
        evObj.put("startLine", sec.startLine());
        evObj.put("endLine", sec.endLine());
        evObj.put("digest", sha);
        evObj.put("excerpt", sec.numberedSource());
        String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.SOURCE,
                sec.path() + " § " + sec.heading() + " (L" + sec.startLine() + "-" + sec.endLine() + ")",
                sec.evidenceId().map(ContentDigest::value).orElse(sec.path()),
                Collections.unmodifiableMap(evObj));
        createdIds.add(eid);

        var out = new LinkedHashMap<String, Object>();
        out.put("evidenceId", eid);
        out.put("found", true);
        out.put("path", LlmTransport.neutralizeInjectionSentinels(sec.path()));
        out.put("heading", LlmTransport.neutralizeInjectionSentinels(sec.heading()));
        out.put("level", sec.level());
        out.put("startLine", sec.startLine());
        out.put("endLine", sec.endLine());
        out.put("digest", sha);
        out.put("truncated", sec.truncated());
        out.put("source", LlmTransport.wrapPreRedactedUntrustedSource(sec.path(), sha, sec.numberedSource()));
        if (sec.redactedSecretsCount() > 0) out.put("redactedSecretsCount", sec.redactedSecretsCount());
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Object> executeArchitectureBrief(
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            PublishedReports reports,
            List<ArchitectureChat.Evidence> evidence,
            List<String> createdIds) {
        var scoreOut = executeGetArchitectureScore(pin.context(), pin, queries, reports, new ArrayList<>(), new ArrayList<>());
        var req = new Request(pin.graph(), pin.context(), Scope.TYPE, Projection.BOUNDED_UNION,
                Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
        var insights = ArchitectureInsightsAdvisor.analyze(queries, req, findPublishedReport(reports, pin.graph(), pin.context()), 5);
        var advisory = AdvisoryOverlayService.forGraph(queries.graph());

        var brief = new LinkedHashMap<String, Object>();
        brief.put("context", pin.context());
        brief.put("scoreV2", Map.of(
                "status", scoreOut.getOrDefault("status", "UNKNOWN"),
                "healthBand", scoreOut.getOrDefault("healthBand", "UNKNOWN"),
                "confidenceTier", scoreOut.getOrDefault("confidenceTier", "UNKNOWN"),
                "score", scoreOut.getOrDefault("score", "WITHHELD")));
        if (insights.summary().analyzedEntities() == 0 && pin.context().equals(pin.snapshot())) {
            var agg = aggregateInsightsAcrossSnapshot(queries, reports, pin, Scope.TYPE, 5);
            brief.put("summary", agg.get("summary"));
            brief.put("topHotspots", agg.getOrDefault("hotspots", List.of()));
            brief.put("topTangles", agg.getOrDefault("tangles", List.of()));
            brief.put("communityModularity", agg.getOrDefault("communityModularity", BigDecimal.ZERO));
            brief.put("topCommunities", agg.getOrDefault("communities", List.of()));
            brief.put("topMisplacedTypes", agg.getOrDefault("misplacedTypes", List.of()));
        } else {
            brief.put("summary", insights.summary());
            brief.put("topHotspots", insights.hotspots().stream().limit(5).toList());
            brief.put("topTangles", insights.tangles().stream().limit(3).toList());
            brief.put("communityModularity", insights.communityModularity());
            brief.put("topCommunities", insights.communities().stream().limit(8).toList());
            brief.put("topMisplacedTypes", insights.misplacedTypes().stream().limit(5).toList());
        }
        brief.put("advisorySummary", Map.of(
                "schema", advisory.schema(),
                "trust", advisory.trust(),
                "nodeCountsByKind", advisory.nodeCountsByKind(),
                "linkCountsByKind", advisory.linkCountsByKind()));

        var safeBrief = new LinkedHashMap<String, Object>(neutralizeMapForLlm(brief));
        String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                "Architecture Brief", pin.context(), Collections.unmodifiableMap(new LinkedHashMap<>(safeBrief)));
        createdIds.add(eid);
        safeBrief.put("evidenceId", eid);
        return Collections.unmodifiableMap(safeBrief);
    }

    private static Map<String, Object> executeInspectAdvisoryLinks(
            Map<String, Object> args,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            List<ArchitectureChat.Evidence> evidence,
            List<String> createdIds) {
        String entity = stringArg(args, "entity", "");
        String kind = stringArg(args, "kind", "");
        String path = stringArg(args, "path", "");
        int limit = Math.max(1, Math.min(100, intArg(args, "limit", 40)));

        var full = AdvisoryOverlayService.forGraph(queries.graph());
        var filtered = AdvisoryOverlayService.filter(full, entity, kind, path);
        var rawView = filtered.toView(limit, limit);
        var safeView = new LinkedHashMap<String, Object>(neutralizeMapForLlm(rawView));
        String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                "Advisory links (ADVISORY_UNVERIFIED)", pin.snapshot(), Collections.unmodifiableMap(new LinkedHashMap<>(safeView)));
        createdIds.add(eid);
        safeView.put("evidenceId", eid);
        safeView.put("linkCount", filtered.links().size());
        return Collections.unmodifiableMap(safeView);
    }

    private static Map<String, Object> executeVerticalTrace(
            Map<String, Object> args,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            List<ArchitectureChat.Evidence> evidence,
            List<String> createdIds) {
        String seed = stringArg(args, "seed", "");
        if (seed.isBlank()) {
            return Map.of("error", "seed is required");
        }
        String ctx = stringArg(args, "context", pin.context());
        Optional<String> seedKind = Optional.of(stringArg(args, "seedKind", "")).filter(s -> !s.isBlank());
        Optional<String> world = Optional.of(stringArg(args, "world", "")).filter(s -> !s.isBlank());
        int maxDepth = intArg(args, "maxDepth", VerticalTraceEngine.DEFAULT_MAX_DEPTH);
        int maxHops = intArg(args, "maxHops", VerticalTraceEngine.DEFAULT_MAX_HOPS);
        boolean includeAdvisory = !(args.get("includeAdvisory") instanceof Boolean b) || b;

        var req = new VerticalTraceEngine.TraceRequest(ctx, seed, seedKind, world, maxDepth, maxHops);
        var result = FullStackVerticalTraceService.trace(queries.graph(), req, includeAdvisory);
        var safeView = new LinkedHashMap<String, Object>(neutralizeMapForLlm(result.toView()));
        String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                "Vertical trace: " + LlmTransport.neutralizeInjectionSentinels(seed),
                pin.snapshot(),
                Collections.unmodifiableMap(new LinkedHashMap<>(safeView)));
        createdIds.add(eid);
        safeView.put("evidenceId", eid);
        return Collections.unmodifiableMap(safeView);
    }

    private static Map<String, Object> executeTraceSlice(
            Map<String, Object> args,
            ArchitectureChat.Input input,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            List<ArchitectureChat.Evidence> evidence,
            List<String> createdIds,
            Map<String, ArchitectureChatModel.SourceUse> toolSources) {
        if (!input.includeSource()) {
            return Map.of("withheld", true,
                    "reason", "Source disclosure is disabled by the user for this turn. Rely on structural metadata.");
        }
        String path = stringArg(args, "path", "");
        if (path.isBlank()) {
            return Map.of("error", "path is required");
        }
        int line = intArg(args, "line", 0);
        if (line < 1) {
            return Map.of("error", "line must be >= 1");
        }
        Optional<String> methodSig = Optional.of(stringArg(args, "methodSignature", "")).filter(s -> !s.isBlank());
        String dirRaw = stringArg(args, "direction", "BACKWARD");
        MethodSliceResult.SliceDirection direction = MethodSliceResult.SliceDirection.parse(dirRaw);
        int maxStatements = intArg(args, "maxStatements", JavaMethodSlicer.DEFAULT_MAX_STATEMENTS);

        FullStackVerticalTraceService.SliceOutcome outcome;
        try {
            outcome = FullStackVerticalTraceService.sliceCapturedJavaWithMetadata(
                    queries.graph(), path, methodSig, line, direction, maxStatements);
        } catch (FullStackVerticalTraceService.CapturedSourceNotFoundException missing) {
            return Map.of("found", false,
                    "path", LlmTransport.neutralizeInjectionSentinels(path),
                    "error", "Captured source file not found in snapshot.");
        }
        var result = outcome.result();
        if (outcome.contentDigest().isPresent()) {
            var su = new ArchitectureChatModel.SourceUse(result.path(), outcome.contentDigest().orElseThrow(), false);
            String key = su.path() + "|" + su.digest().value();
            toolSources.putIfAbsent(key, su);
        }
        var safeView = new LinkedHashMap<String, Object>(neutralizeMapForLlm(result.toView()));
        String evRef = outcome.contentDigest().map(ContentDigest::value).orElse(result.path());
        String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.SOURCE,
                "Code slice: " + result.path() + " · L" + line,
                evRef,
                Collections.unmodifiableMap(new LinkedHashMap<>(safeView)));
        createdIds.add(eid);
        safeView.put("evidenceId", eid);
        return Collections.unmodifiableMap(safeView);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> neutralizeMapForLlm(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        try {
            Object decoded = GraphJson.read(CanonicalJson.write(raw), new GraphJson.Limits(4_000_000, 64, 400_000));
            return (Map<String, Object>) neutralizeStringsDeep(decoded);
        } catch (RuntimeException fallback) {
            return (Map<String, Object>) neutralizeStringsDeep(raw);
        }
    }

    private static Object neutralizeStringsDeep(Object value) {
        if (value instanceof String s) {
            return LlmTransport.neutralizeInjectionSentinels(s);
        }
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<Object, Object>(map.size());
            for (var e : map.entrySet()) {
                Object k = e.getKey() instanceof String ks ? LlmTransport.neutralizeInjectionSentinels(ks) : e.getKey();
                out.put(k, neutralizeStringsDeep(e.getValue()));
            }
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<Object>(list.size());
            for (Object item : list) {
                out.add(neutralizeStringsDeep(item));
            }
            return Collections.unmodifiableList(out);
        }
        return value;
    }

    private static Map<String, Object> fn(String name, String description, Map<String, Object> properties, List<String> required) {
        return Map.of("type", "function", "function", Map.of(
                "name", name,
                "description", description,
                "parameters", Map.of("type", "object", "properties", properties, "required", required)));
    }
}
