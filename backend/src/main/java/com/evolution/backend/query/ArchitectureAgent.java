package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.*;
import com.evolution.analysis.query.ArchitectureQueryService;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Autonomous multi-step read-only GraphRAG investigation agent pinned to one immutable snapshot and graph.
 * Executes bounded tool calls against ArchitectureQueryService and PublishedReports, recording every
 * observation as a citable evidence item (E#) with deterministic trace provenance.
 */
final class ArchitectureAgent {
    private static final int MAX_ROUNDS = 6;
    private static final int MAX_TOOL_JSON_BYTES = 24_000;
    private static final Set<String> SERVICE_BRIDGE_KINDS = Set.of(
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
                    // Provider may not support OpenAI `tools`; fall back gracefully to single-turn chat.
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
                // Model already answered directly without further tools.
                String content = assistantMsg.get("content") instanceof String s ? s : "";
                if (!content.isBlank()) {
                    var currentContext = new ArchitectureChat.Context(initialContext.pin(), List.copyOf(evidence), List.copyOf(limitations), sources);
                    var completion = model.decodeDirectResponse(currentContext, initPacket, rawResponse, initLimits,
                            input.reviewer(), true, deltaConsumer);
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
                var outcome = executeTool(toolName, args, input, initialContext.pin(), queries, reports, jobs, evidence, stop);
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
                input.reviewer(), true, stop, deltaConsumer);
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
                    var report = ArchitectureInsightsAdvisor.analyze(queries, primaryReq, Optional.empty(), topN);
                    Map<String, Object> data;
                    if (report.summary().analyzedEntities() == 0 && pin.context().equals(pin.snapshot())) {
                        data = aggregateInsightsAcrossSnapshot(queries, pin, scope, topN);
                    } else {
                        data = Map.of(
                                "summary", report.summary(),
                                "tangles", report.tangles().stream().limit(5).toList(),
                                "hotspots", report.hotspots().stream().limit(topN).toList(),
                                "coupling", report.couplingMatrix().cells().stream().limit(15).toList());
                    }
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.STRUCTURE,
                            "Architecture insights (" + scope + ")", pin.context(), data);
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "insights", data);
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
                        var spring = queries.spring(req, Set.of("BEAN_PRODUCER", "INJECTION_POINT", "ENDPOINT"), new Page(30, Optional.empty()));
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
                case "inspect_connections" -> {
                    var req = new Request(pin.graph(), pin.snapshot(), Scope.ENTITY, Projection.BOUNDED_UNION,
                            Direction.OUTGOING, SERVICE_BRIDGE_KINDS, Population.all(), false, Optional.empty(), Limits.defaults());
                    var edges = queries.relationships(req, new Page(30, Optional.empty()));
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.DEPENDENCIES,
                            "Cross-language service & event connections", pin.snapshot(), Map.of("connections", edges.items()));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "count", edges.items().size(), "connections", edges.items());
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
                case "read_captured_source" -> {
                    if (!input.includeSource()) {
                        yield Map.of("withheld", true, "reason", "Source disclosure is disabled by the user for this turn. Rely on structural metadata.");
                    }
                    String targetPath = stringArg(args, "path", "").replace('\\', '/').replaceFirst("^\\./+", "");
                    if (targetPath.isBlank()) {
                        yield Map.of("error", "path is required");
                    }
                    int startLine = Math.max(1, intArg(args, "startLine", 1));
                    int endLine = Math.max(startLine, Math.min(startLine + 120, intArg(args, "endLine", startLine + 80)));
                    var graph = queries.graph();
                    String matchedPath = null;
                    Map<String, Object> matchedRow = null;
                    ContentDigest matchedDigest = null;
                    ContentDigest matchedEvidenceId = null;
                    var suffixMatches = new ArrayList<String>();
                    for (var ev : graph.evidence()) {
                        if (!"source.bytes".equals(ev.family())) continue;
                        var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                        if (!(obj.get("document") instanceof Map<?, ?> doc)) continue;
                        if (!(doc.get("path") instanceof String docPath)) continue;
                        if (targetPath.equals(docPath)) {
                            matchedPath = docPath;
                            matchedRow = obj;
                            matchedDigest = doc.get("contentDigest") instanceof String d ? new ContentDigest(d) : null;
                            matchedEvidenceId = ev.identity();
                            break;
                        }
                        if (docPath.endsWith("/" + targetPath)) {
                            suffixMatches.add(docPath);
                            if (matchedRow == null) {
                                matchedPath = docPath;
                                matchedRow = obj;
                                matchedDigest = doc.get("contentDigest") instanceof String d ? new ContentDigest(d) : null;
                                matchedEvidenceId = ev.identity();
                            }
                        }
                    }
                    if (suffixMatches.size() > 1 && !targetPath.equals(matchedPath)) {
                        yield Map.of("found", false, "ambiguous", true, "path", targetPath,
                                "candidates", suffixMatches.stream().limit(10).toList());
                    }
                    String foundContent = null;
                    if (matchedRow != null) {
                        var rawBytes = RepairSource.resolveSourceBytes(graph, matchedRow, Optional.ofNullable(matchedDigest));
                        if (rawBytes.isPresent()) {
                            foundContent = new String(rawBytes.orElseThrow(), StandardCharsets.UTF_8);
                        }
                    }
                    if (foundContent == null) {
                        yield Map.of("found", false, "path", targetPath);
                    }
                    String[] allLines = foundContent.split("\\R", -1);
                    int s = Math.min(startLine, allLines.length);
                    int e = Math.min(endLine, allLines.length);
                    var numbered = new StringBuilder();
                    for (int i = s; i <= e; i++) {
                        numbered.append(i).append(": ").append(allLines[i - 1]).append('\n');
                    }
                    String finalPath = matchedPath != null ? matchedPath : targetPath;
                    String eid = ArchitectureChat.addEvidence(evidence, ArchitectureChat.Kind.SOURCE,
                            finalPath + " · L" + s + "-" + e, matchedEvidenceId.value(),
                            Map.of("path", finalPath, "startLine", s, "endLine", e, "excerpt", numbered.toString()));
                    createdIds.add(eid);
                    yield Map.of("evidenceId", eid, "found", true, "path", finalPath, "startLine", s, "endLine", e,
                            "totalLines", allLines.length, "source", numbered.toString());
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
                                                                     ArchitectureChat.Pin pin,
                                                                     Scope scope,
                                                                     int topN) {
        var contexts = searchContexts(queries.graph(), pin).stream()
                .filter(ctx -> !ctx.equals(pin.snapshot()))
                .limit(12)
                .toList();
        int totalEntities = 0, totalInternal = 0, totalExternal = 0, totalTangles = 0;
        int totalTangledEntities = 0, maxBlastRadius = 0, totalSdp = 0, totalFindings = 0;
        java.math.BigDecimal weightedInstability = java.math.BigDecimal.ZERO;
        var allTangles = new ArrayList<ArchitectureInsightsAdvisor.Tangle>();
        var allHotspots = new ArrayList<ArchitectureInsightsAdvisor.Hotspot>();
        var allCoupling = new ArrayList<ArchitectureInsightsAdvisor.CouplingCell>();
        for (String ctx : contexts) {
            var req = new Request(pin.graph(), ctx, scope, Projection.BOUNDED_UNION,
                    Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
            var r = ArchitectureInsightsAdvisor.analyze(queries, req, Optional.empty(), topN);
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
            weightedInstability = weightedInstability.add(
                    s.averageInstability().multiply(java.math.BigDecimal.valueOf(s.analyzedEntities())));
            allTangles.addAll(r.tangles());
            allHotspots.addAll(r.hotspots());
            allCoupling.addAll(r.couplingMatrix().cells());
        }
        var avgInstability = totalEntities == 0 ? java.math.BigDecimal.ZERO
                : weightedInstability.divide(java.math.BigDecimal.valueOf(totalEntities), 4, java.math.RoundingMode.HALF_UP);
        var tangledRatio = totalEntities == 0 ? java.math.BigDecimal.ZERO
                : java.math.BigDecimal.valueOf(totalTangledEntities)
                        .divide(java.math.BigDecimal.valueOf(totalEntities), 4, java.math.RoundingMode.HALF_UP);
        var mergedSummary = new ArchitectureInsightsAdvisor.SummaryMetrics(
                totalEntities, totalInternal, totalExternal, totalTangles, totalTangledEntities,
                tangledRatio, avgInstability, maxBlastRadius, totalSdp, totalFindings);
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
            case "trace_path" -> Boolean.TRUE.equals(payload.get("found")) ? "Found dependency path" : "No direct path found";
            case "analyze_impact" -> "Computed transitive blast radius";
            case "get_architecture_insights" -> "Computed Tarjan cycles, hotspots & coupling";
            case "list_findings" -> "Listed " + payload.getOrDefault("count", 0) + " policy findings";
            case "inspect_spring_wiring" -> "Inspected " + payload.getOrDefault("components", 0) + " Spring items";
            case "inspect_connections" -> "Found " + payload.getOrDefault("count", 0) + " cross-language bridges";
            case "inspect_gaps" -> "Inspected " + payload.getOrDefault("count", 0) + " capability gaps";
            case "read_captured_source" -> Boolean.TRUE.equals(payload.get("withheld"))
                    ? "Source withheld (metadata-only mode)" : "Read captured source lines";
            default -> "Completed " + name;
        };
    }

    private static String boundedJson(Map<String, Object> payload) {
        try {
            return new String(BoundedJson.encode(payload, MAX_TOOL_JSON_BYTES), StandardCharsets.UTF_8);
        } catch (BoundedJson.TooLarge large) {
            return CanonicalJson.write(Map.of("truncated", true, "summary", "Tool output exceeded 24KB; narrow scope or query."));
        }
    }

    private static String stringArg(Map<String, Object> args, String key, String fallback) {
        return args.get(key) instanceof String s ? s.strip() : fallback;
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        return args.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    private static Scope parseScope(String raw) {
        try { return Scope.valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { return Scope.TYPE; }
    }

    private static List<Map<String, Object>> toolSchemas(boolean includeSource) {
        var list = new ArrayList<Map<String, Object>>();
        list.add(fn("search_symbols", "Search classes, packages, modules, or Spring components in the captured graph.",
                Map.of("query", Map.of("type", "string"),
                        "scope", Map.of("type", "string", "enum", List.of("TYPE", "PACKAGE", "MODULE", "SPRING_COMPONENT", "ENTITY")),
                        "origin", Map.of("type", "string", "enum", List.of("PROJECT", "DEPENDENCY", "JDK"))),
                List.of("query")));
        list.add(fn("inspect_symbol", "Inspect one symbol's incoming dependents and outgoing dependencies.",
                Map.of("identity", Map.of("type", "string")), List.of("identity")));
        list.add(fn("trace_path", "Find the shortest dependency path between two symbol identities.",
                Map.of("source", Map.of("type", "string"), "target", Map.of("type", "string"),
                        "scope", Map.of("type", "string", "enum", List.of("TYPE", "PACKAGE", "MODULE"))),
                List.of("source", "target")));
        list.add(fn("analyze_impact", "Compute transitive incoming change blast radius for a symbol.",
                Map.of("identity", Map.of("type", "string"),
                        "scope", Map.of("type", "string", "enum", List.of("TYPE", "PACKAGE", "MODULE"))),
                List.of("identity")));
        list.add(fn("get_architecture_insights", "Run Tarjan SCC cycle detection, minimal break cuts, Martin instability, and hotspots.",
                Map.of("scope", Map.of("type", "string", "enum", List.of("TYPE", "PACKAGE", "MODULE", "SPRING_COMPONENT")),
                        "topN", Map.of("type", "integer")),
                List.of()));
        list.add(fn("list_findings", "List published architecture policy violations and MUST/MAY/UNKNOWN classifications.",
                Map.of(), List.of()));
        list.add(fn("inspect_spring_wiring", "Inspect Spring components, endpoints, injection sites, and configuration spaces.",
                Map.of(), List.of()));
        list.add(fn("inspect_connections", "Inspect cross-language HTTP, gRPC, and async event bridge candidates.",
                Map.of(), List.of()));
        list.add(fn("inspect_gaps", "Inspect capability gaps and obligations blocking complete semantic resolution.",
                Map.of("reasonFilter", Map.of("type", "string")), List.of()));
        if (includeSource) {
            list.add(fn("read_captured_source", "Read line-numbered captured source lines from a verified snapshot file.",
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
