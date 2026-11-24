package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.*;
import com.evolution.analysis.query.ArchitectureQueryService;

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
    static final int MAX_ROUNDS = 10;
    static final int MAX_TOOL_JSON_BYTES = 48_000;
    static final Set<String> SERVICE_BRIDGE_KINDS = Set.of(
            "CALLS_SERVICE", "INVOKES_RPC", "PUBLISHES_EVENT",
            "polyglot.service-call.candidate", "polyglot.grpc-service-call.candidate",
            "polyglot.event-flow.candidate", "polyglot.grpc-contract.candidate");

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

    static ArchitectureChat.Result run(
            ArchitectureChatModel model,
            ArchitectureChat.Input input,
            ArchitectureChat.Context initialContext,
            ArchitectureQueryService queries,
            PublishedReports reports,
            Optional<AnalysisJobApi> jobs,
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
        var initPacket = model.buildPacket(input, initialContext, graph, initLimits, stop);
        var offeredFiles = initPacket.files().stream().map(ModelSources.Selection::file).toList();
        var userPrompt = model.buildUserPrompt(input, initialContext, initPacket.files(), stop);

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", model.instructions(input.reviewer(), true)));
        messages.add(Map.of("role", "user", "content", userPrompt));

        var tools = toolSchemas(input.includeSource());
        Consumer<String> deltaConsumer = listener == null ? null : progress::onDelta;

        progress.onStage("Investigating architecture graph…");
        int stepCounter = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (stop.get() != StopReason.NONE) {
                throw new ArchitectureChatModel.Rejected(ArchitectureChatModel.Failure.CHAT_RESOURCE_LIMIT);
            }
            Map<String, Object> rawResponse;
            try {
                rawResponse = model.sendToolRound(messages, tools, stop);
            } catch (ArchitectureChatModel.Rejected rejected) {
                if (round == 0 && rejected.reason == ArchitectureChatModel.Failure.CHAT_PROVIDER_UNAVAILABLE) {
                    progress.onStage("Falling back to single-turn analysis…");
                    var fallback = model.complete(input, initialContext, graph, stop, deltaConsumer);
                    return ArchitectureChat.Result.advice(initialContext, fallback);
                }
                throw rejected;
            }
            if (!(rawResponse.get("choices") instanceof List<?> choices) || choices.size() != 1) {
                throw new ArchitectureChatModel.Rejected(ArchitectureChatModel.Failure.CHAT_INVALID_MODEL_OUTPUT);
            }
            var choice = GraphJson.object(choices.getFirst());
            var assistantMsg = GraphJson.object(choice.get("message"));
            Object rawCalls = assistantMsg.get("tool_calls");
            if (!(rawCalls instanceof List<?> toolCalls) || toolCalls.isEmpty()) {
                String content = assistantMsg.get("content") instanceof String s ? s : "";
                if (!content.isBlank()) {
                    var currentContext = new ArchitectureChat.Context(initialContext.pin(), List.copyOf(evidence), List.copyOf(limitations), sources);
                    var completion = model.decodeDirectResponse(currentContext, initPacket, rawResponse, initLimits,
                            input.reviewer(), true, List.copyOf(toolSources.values()), deltaConsumer);
                    return ArchitectureChat.Result.adviceWithTrace(currentContext, completion, trace);
                }
                break;
            }

            messages.add(sanitizeAssistantToolMessage(assistantMsg, toolCalls));
            for (Object rawCall : toolCalls) {
                if (stop.get() != StopReason.NONE) {
                    throw new ArchitectureChatModel.Rejected(ArchitectureChatModel.Failure.CHAT_RESOURCE_LIMIT);
                }
                var callObj = GraphJson.object(rawCall);
                String callId = callObj.get("id") instanceof String id && !id.isBlank() ? id : ("call_" + (++stepCounter));
                var fn = GraphJson.object(callObj.get("function"));
                String toolName = fn.get("name") instanceof String n ? n : "";
                Map<String, Object> args = parseArgs(fn.get("arguments"));
                int stepNo = ++stepCounter;
                progress.onToolStart(stepNo, toolName, args);
                long startNs = System.nanoTime();
                var outcome = executeTool(toolName, args, input, initialContext.pin(), queries, reports, jobs,
                        evidence, toolSources, stop);
                long durationMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                var step = new ArchitectureChat.AgentStep(stepNo, toolName, args, outcome.summary(), outcome.evidenceIds(), durationMs);
                trace.add(step);
                progress.onToolComplete(step);
                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", callId,
                        "content", outcome.toolOutputJson()));
            }
        }

        progress.onStage("Synthesizing evidence-backed response…");
        var finalContext = new ArchitectureChat.Context(initialContext.pin(), List.copyOf(evidence), List.copyOf(limitations), sources);
        var completion = model.completeMessages(finalContext, messages, offeredFiles, initLimits,
                input.reviewer(), true, List.copyOf(toolSources.values()), stop, deltaConsumer);
        return ArchitectureChat.Result.adviceWithTrace(finalContext, completion, trace);
    }

    private static Map<String, Object> sanitizeAssistantToolMessage(Map<String, Object> assistantMsg, List<?> toolCalls) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("role", "assistant");
        if (assistantMsg.get("content") instanceof String s) msg.put("content", s);
        else msg.put("content", "");
        msg.put("tool_calls", toolCalls);
        return msg;
    }

    private static Map<String, Object> parseArgs(Object raw) {
        if (raw == null) return Map.of();
        if (raw instanceof Map<?, ?>) return GraphJson.object(raw);
        if (raw instanceof String s) {
            if (s.isBlank()) return Map.of();
            try { return GraphJson.object(GraphJson.read(s, GraphJson.Limits.defaults())); }
            catch (IllegalArgumentException invalid) { return Map.of(); }
        }
        return Map.of();
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
                    String id = stringArg(args, "identity", "");
                    if (id.isBlank()) yield Map.of("error", "identity is required");
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
                    String id = stringArg(args, "identity", "");
                    if (id.isBlank()) yield Map.of("error", "identity is required");
                    yield executeInspectSymbolDetails(id, pin, queries, evidence, createdIds);
                }
                case "trace_path" -> {
                    String src = stringArg(args, "source", "");
                    String dst = stringArg(args, "target", "");
                    if (src.isBlank() || dst.isBlank()) yield Map.of("error", "source and target are required");
                    Scope scope = parseScope(stringArg(args, "scope", "TYPE"));
                    String effectiveCtx = resolvePathContext(queries.graph(), queries, pin, src, dst);
                    var req = new Request(pin.graph(), effectiveCtx, scope, Projection.BOUNDED_UNION,
                            Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
                    var res = queries.shortestPath(req, src, dst);
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.DEPENDENCIES,
                            "Shortest path trace", res.query().value(), Map.of("context", effectiveCtx, "paths", res.items(), "status", res.status()));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "context", effectiveCtx, "found", !res.items().isEmpty(), "paths", res.items());
                }
                case "analyze_impact" -> {
                    String id = stringArg(args, "identity", "");
                    if (id.isBlank()) yield Map.of("error", "identity is required");
                    Scope scope = parseScope(stringArg(args, "scope", "TYPE"));
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
                    Map<String, Object> data;
                    if (report.summary().analyzedEntities() == 0 && pin.context().equals(pin.snapshot())) {
                        data = aggregateInsightsAcrossSnapshot(queries, reports, pin, scope, topN);
                    } else {
                        var map = new LinkedHashMap<String, Object>();
                        map.put("summary", report.summary());
                        map.put("tangles", report.tangles().stream().limit(5).toList());
                        map.put("hotspots", report.hotspots().stream().limit(topN).toList());
                        map.put("coupling", report.couplingMatrix().cells().stream().limit(15).toList());
                        map.put("sdpViolations", report.sdpViolations().stream().limit(10).toList());
                        map.put("packageMetrics", report.packageMetrics().stream().limit(15).toList());
                        data = Collections.unmodifiableMap(map);
                    }
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                            "Architecture insights (" + scope + ")", pin.context(), data);
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "insights", data);
                }
                case "get_repo_map" -> {
                    var seeds = stringListArg(args, "seeds");
                    int maxFiles = Math.max(1, Math.min(50, intArg(args, "maxFiles", 20)));
                    int maxTokens = Math.max(500, Math.min(12_000, intArg(args, "maxTokens", 4000)));
                    var mapResult = RepoMapBuilder.build(queries.graph(), Optional.of(pin.context()), seeds, maxFiles, maxTokens, stop);
                    var summaryData = new LinkedHashMap<String, Object>();
                    summaryData.put("algorithm", mapResult.algorithm());
                    summaryData.put("context", mapResult.context());
                    summaryData.put("totalFiles", mapResult.totalFiles());
                    summaryData.put("seedCount", mapResult.seedCount());
                    summaryData.put("estimatedTokens", mapResult.estimatedTokens());
                    summaryData.put("truncated", mapResult.truncated());
                    summaryData.put("files", mapResult.files());
                    summaryData.put("renderedMap", mapResult.renderedMap());
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                            "Repository PPR map (" + mapResult.files().size() + " files)", pin.context(), summaryData);
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "fileCount", mapResult.files().size(), "totalFiles", mapResult.totalFiles(),
                            "seedCount", mapResult.seedCount(), "renderedMap", mapResult.renderedMap(), "files", mapResult.files());
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
                            findingsOut.add(Map.of(
                                    "report", rid.value(),
                                    "context", eval.context(),
                                    "identity", f.identity().value(),
                                    "rule", f.rule().id(),
                                    "kind", f.kind(),
                                    "classification", f.classification(),
                                    "path", f.bundle().path()));
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
                    yield executeInspectSpringConditions(filter, pin, queries, evidence, createdIds);
                }
                case "inspect_connections" -> {
                    yield executeInspectConnections(pin, queries, evidence, createdIds);
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
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                            "Capability gaps" + (filter.isEmpty() ? "" : " (" + filter + ")"), pin.context(),
                            Map.of("items", graphGaps, "queryGaps", queryGaps));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "count", graphGaps.size() + queryGaps.size(), "items", graphGaps, "queryGaps", queryGaps);
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
                            "languages", listResult.languages(), "files", fileViews);
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
                            .search(queries.graph(), query, regex, pathFilter, language, maxMatches, stop);
                    for (var su : searchRes.readSources()) {
                        String key = su.path() + "|" + su.digest().value();
                        toolSources.putIfAbsent(key, su);
                    }
                    var matchViews = searchRes.matches().stream().map(CapturedFileIndex.SearchMatch::toView).toList();
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.SOURCE,
                            "Source grep: " + query, pin.snapshot(),
                            Map.of("query", query, "regex", regex, "scannedFiles", searchRes.scannedFiles(),
                                    "matchedFiles", searchRes.matchedFiles(), "truncated", searchRes.truncated(),
                                    "matches", matchViews));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "count", matchViews.size(), "scannedFiles", searchRes.scannedFiles(),
                            "matchedFiles", searchRes.matchedFiles(), "truncated", searchRes.truncated(),
                            "matches", matchViews);
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
                        yield Map.of("found", false, "ambiguous", true, "path", targetPath, "candidates", readRes.candidates());
                    }
                    if (!readRes.found()) {
                        yield Map.of("found", false, "path", targetPath);
                    }
                    if (readRes.digest().isPresent()) {
                        boolean completeFile = readRes.startLine() == 1 && readRes.endLine() == readRes.totalLines();
                        var su = new ArchitectureChatModel.SourceUse(readRes.path(), readRes.digest().orElseThrow(), completeFile);
                        String key = su.path() + "|" + su.digest().value();
                        toolSources.merge(key, su, (a, b) -> new ArchitectureChatModel.SourceUse(a.path(), a.digest(), a.completeFile() || b.completeFile()));
                    }
                    String evRef = readRes.evidenceId().map(ContentDigest::value).orElse(readRes.path());
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.SOURCE,
                            readRes.path() + " · L" + readRes.startLine() + "-" + readRes.endLine(), evRef,
                            Map.of("path", readRes.path(), "language", readRes.language(),
                                    "startLine", readRes.startLine(), "endLine", readRes.endLine(),
                                    "totalLines", readRes.totalLines(), "excerpt", readRes.numberedSource()));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "found", true, "path", readRes.path(), "language", readRes.language(),
                            "startLine", readRes.startLine(), "endLine", readRes.endLine(),
                            "totalLines", readRes.totalLines(), "truncated", readRes.truncated(),
                            "source", readRes.numberedSource());
                }
                default -> Map.of("error", "Unknown read-only tool: " + name);
            };
            String json = boundedJson(payload);
            String summary = summarizeTool(name, payload);
            return new ToolOutcome(summary, List.copyOf(createdIds), json);
        } catch (RuntimeException failure) {
            return new ToolOutcome("Tool unavailable (" + name + ")", List.of(),
                    CanonicalJson.write(Map.of("error", "Tool query failed or reached bounds.")));
        }
    }

    private static Map<String, Object> executeInspectSymbolDetails(
            String id,
            ArchitectureChat.Pin pin,
            ArchitectureQueryService queries,
            List<ArchitectureChat.Evidence> evidence,
            List<String> createdIds) {
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
                ? CapturedRepair.entityLabel(graph, effectiveCtx, pin.snapshot(), id)
                : entityRes.items().getFirst().qualifiedName().or(entityRes.items().getFirst()::canonicalName)
                        .or(entityRes.items().getFirst()::spelling).orElse(id);

        // Extract path, span, annotations, Spring conditions, and associated gaps directly from node evidence
        String path = null;
        Map<String, Object> span = null;
        var annotations = new LinkedHashSet<String>();
        var springDetails = new ArrayList<Map<String, Object>>();
        String docId = null;

        KnowledgeGraph.Node targetNode = null;
        for (var n : graph.nodes(effectiveCtx)) {
            if (n.identity().equals(id)) { targetNode = n; break; }
        }
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
            path = resolveDocumentPath(graph, docId);
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
        if (path != null) detail.put("path", path);
        if (span != null) detail.put("span", span);
        if (!annotations.isEmpty()) detail.put("annotations", List.copyOf(annotations));
        if (!springDetails.isEmpty()) detail.put("spring", springDetails);
        detail.put("metrics", metrics);
        detail.put("incomingCount", fanIn);
        detail.put("outgoingCount", fanOut);
        detail.put("incoming", inList);
        detail.put("outgoing", outList);
        detail.put("gaps", relatedGaps);

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
            List<String> createdIds) {
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
                    String label = CapturedRepair.entityLabel(graph, ctx, pin.snapshot(), node.identity());
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
            List<String> createdIds) {
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
            row.put("sourceLabel", CapturedRepair.entityLabel(graph, pin.snapshot(), pin.context(), rel.source()));
            var targetViews = new ArrayList<Map<String, Object>>();
            for (String tid : rel.targets()) {
                targetViews.add(Map.of(
                        "identity", tid,
                        "label", CapturedRepair.entityLabel(graph, pin.snapshot(), pin.context(), tid)));
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
        var allTangles = new ArrayList<ArchitectureInsightsAdvisor.Tangle>();
        var allHotspots = new ArrayList<ArchitectureInsightsAdvisor.Hotspot>();
        var allCoupling = new ArrayList<ArchitectureInsightsAdvisor.CouplingCell>();
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
            allTangles.addAll(r.tangles());
            allHotspots.addAll(r.hotspots());
            allCoupling.addAll(r.couplingMatrix().cells());
        }
        var avgInstability = totalEntities == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : weightedInstability.divide(BigDecimal.valueOf(totalEntities), 4, RoundingMode.HALF_UP);
        var avgAbstractness = totalEntities == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : weightedAbstractness.divide(BigDecimal.valueOf(totalEntities), 4, RoundingMode.HALF_UP);
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
        return Map.of(
                "aggregatedContexts", contexts.size(),
                "summary", mergedSummary,
                "tangles", sortedTangles,
                "hotspots", sortedHotspots,
                "coupling", sortedCoupling);
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
            case "get_repo_map" -> "Ranked " + payload.getOrDefault("fileCount", 0) + " files via Personalized PageRank";
            case "list_findings" -> "Listed " + payload.getOrDefault("count", 0) + " policy findings";
            case "inspect_spring_wiring" -> "Inspected " + payload.getOrDefault("components", 0) + " Spring items";
            case "inspect_spring_conditions" -> "Inspected " + payload.getOrDefault("conditionCount", 0)
                    + " conditions & " + payload.getOrDefault("realizationCount", 0) + " realizations";
            case "inspect_connections" -> "Found " + payload.getOrDefault("count", 0) + " cross-language bridges";
            case "inspect_gaps" -> "Inspected " + payload.getOrDefault("count", 0) + " capability gaps";
            case "list_captured_files" -> "Listed " + payload.getOrDefault("matchedFiles", 0) + " captured files";
            case "search_captured_source" -> Boolean.TRUE.equals(payload.get("withheld"))
                    ? "Source search withheld (metadata-only mode)"
                    : "Found " + payload.getOrDefault("count", 0) + " source matches";
            case "read_captured_source" -> Boolean.TRUE.equals(payload.get("withheld"))
                    ? "Source withheld (metadata-only mode)" : "Read captured source lines";
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
        list.add(fn("list_captured_files", "List captured repository files across all languages with optional path glob/substring and language filter.",
                Map.of("query", Map.of("type", "string"), "language", Map.of("type", "string"),
                        "limit", Map.of("type", "integer"), "offset", Map.of("type", "integer")),
                List.of()));
        if (includeSource) {
            list.add(fn("search_captured_source", "Bounded regex or substring grep across all captured text files returning file:line snippets.",
                    Map.of("query", Map.of("type", "string"), "regex", Map.of("type", "boolean"),
                            "pathFilter", Map.of("type", "string"), "language", Map.of("type", "string"),
                            "maxMatches", Map.of("type", "integer")),
                    List.of("query")));
            list.add(fn("read_captured_source", "Read up to 250 line-numbered captured source lines from any verified snapshot file across Java and polyglot languages.",
                    Map.of("path", Map.of("type", "string"), "startLine", Map.of("type", "integer"), "endLine", Map.of("type", "integer")),
                    List.of("path")));
        }
        return List.copyOf(list);
    }

    private static Map<String, Object> fn(String name, String description, Map<String, Object> properties, List<String> required) {
        return Map.of("type", "function", "function", Map.of(
                "name", name,
                "description", description,
                "parameters", Map.of("type", "object", "properties", properties, "required", required)));
    }
}
