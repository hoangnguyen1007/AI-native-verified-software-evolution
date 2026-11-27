package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.backend.query.chat.ChatConversationStore;

import java.util.*;
import java.util.regex.Pattern;

import static com.evolution.analysis.query.ArchitectureQuery.*;

/**
 * Bounded architecture context for each turn. Repository content never gains
 * instruction authority.
 */
final class ArchitectureChat {
    static final String VERSION = "architecture.chat:v1";
    private static final Page SAMPLE = new Page(50, Optional.empty());
    private static final FindingDiagnosis REPLAY = FindingDiagnosis.localReplay();
    private static final Pattern DIRECT_OFF_TOPIC = Pattern.compile(
            "(?:tell me a joke|what(?: is|'s) the weather(?: today| tomorrow)?|give me a (?:cake|pasta) recipe|"
                    + "kể (?:cho )?(?:tôi|mình) (?:nghe )?một câu chuyện cười|dự báo thời tiết (?:hôm nay|ngày mai)|"
                    + "thời tiết (?:hôm nay|ngày mai) thế nào)[.!?]*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VIETNAMESE = Pattern.compile("[ăđơưẠ-ỹ]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    enum Mode {
        EXPLORE, DESIGN, REPAIR
    }

    enum Kind {
        SUMMARY, STRUCTURE, SYMBOLS, DEPENDENCIES, FINDING, SOURCE
    }

    record Pin(ContentDigest graph, String snapshot, String context, Optional<FindingRef> finding) {
    }

    record Message(String role, String content) {
    }

    record FindingRef(ContentDigest report, ContentDigest identity, ContentDigest bundle) {
    }

    record AgentStep(int step, String tool, Map<String, Object> arguments, String summary, List<String> evidenceIds,
            long durationMs) {
    }

    record Input(String context, Mode mode, List<Message> messages, Optional<String> focus,
            Optional<FindingRef> finding, boolean allowRemote, boolean evidenceOnly, boolean includeSource,
            Optional<String> job,
            boolean reviewer, boolean agentic, Optional<String> conversationId) {
        Input(String context, Mode mode, List<Message> messages, Optional<String> focus,
                Optional<FindingRef> finding, boolean allowRemote, boolean evidenceOnly, boolean includeSource,
                Optional<String> job, boolean reviewer, boolean agentic) {
            this(context, mode, messages, focus, finding, allowRemote, evidenceOnly, includeSource, job, reviewer,
                    agentic, Optional.empty());
        }

        Input {
            conversationId = conversationId == null ? Optional.empty() : conversationId;
        }

        String question() {
            return messages.getLast().content();
        }

        static Input read(Object value) {
            var row = QueryJson.object(value, "context", "mode", "messages", "focus", "finding", "allowRemote",
                    "evidenceOnly", "includeSource", "job", "reviewer", "agentic", "conversationId");
            var messages = GraphJson.array(row.get("messages"));
            if (messages.isEmpty() || messages.size() > 15 || messages.size() % 2 == 0)
                throw new IllegalArgumentException("Expected at most seven previous turns and one question");
            var history = new ArrayList<Message>();
            for (Object item : messages) {
                var turn = QueryJson.object(item, "role", "content");
                var role = history.size() % 2 == 0 ? "user" : "assistant";
                var content = GraphJson.string(turn.get("content")).strip();
                if (!role.equals(turn.get("role")) || content.isEmpty() || content.length() > (role.equals("user") ? 2400 : 32_000))
                    throw new IllegalArgumentException("Invalid chat turn");
                history.add(new Message(role, content));
            }
            var focus = QueryJson.optionalText(row, "focus");
            if (focus.filter(s -> s.length() > 256).isPresent())
                throw new IllegalArgumentException("Focus exceeds the limit");
            var conversationId = QueryJson.optionalText(row, "conversationId");
            if (conversationId.isPresent() && !ChatConversationStore.isValidConversationId(conversationId.orElseThrow())) {
                throw new IllegalArgumentException("Invalid conversationId format");
            }
            var finding = Optional.ofNullable(row.get("finding")).map(ref -> {
                var pins = QueryJson.object(ref, "report", "identity", "bundle");
                return new FindingRef(digest(pins, "report"), digest(pins, "identity"), digest(pins, "bundle"));
            });
            return new Input(QueryJson.text(row, "context"), Mode.valueOf(QueryJson.text(row, "mode")),
                    List.copyOf(history), focus, finding, flag(row, "allowRemote"), flag(row, "evidenceOnly"),
                    flag(row, "includeSource"), QueryJson.optionalText(row, "job"), flag(row, "reviewer"),
                    flag(row, "agentic"), conversationId);
        }

        private static boolean flag(Map<String, Object> row, String field) {
            if (!(row.getOrDefault(field, false) instanceof Boolean value))
                throw new IllegalArgumentException("Boolean required");
            return value;
        }

        private static ContentDigest digest(Map<String, Object> row, String field) {
            return new ContentDigest(QueryJson.text(row, field));
        }
    }

    /** Gracefully compacts oldest conversation turns when total history exceeds {@code maxChars}. */
    static List<Message> compactHistory(List<Message> messages, int maxChars) {
        if (messages == null || messages.size() <= 1) return messages == null ? List.of() : messages;
        int total = 0;
        for (var m : messages) total += m.content().length();
        if (total <= maxChars) return messages;
        var out = new ArrayList<Message>(messages);
        for (int i = 0; i < out.size() - 1 && total > maxChars; i++) {
            var m = out.get(i);
            int cap = m.role().equals("assistant") ? 600 : 400;
            if (m.content().length() > cap) {
                String compacted = m.content().substring(0, cap) + "\n... [earlier turn compacted]";
                total -= (m.content().length() - compacted.length());
                out.set(i, new Message(m.role(), compacted));
            }
        }
        return List.copyOf(out);
    }

    record Evidence(String id, Kind kind, String label, String identity, Map<String, ?> data) {
    }

    public record ResolvedCitation(
            String id,
            String kind,
            String label,
            List<String> nodeIds,
            Optional<String> path,
            OptionalInt startLine,
            OptionalInt endLine,
            String trustTier) implements CanonicalJson.CanonicalValue {
        public ResolvedCitation {
            id = Objects.requireNonNull(id, "id");
            kind = Objects.requireNonNull(kind, "kind");
            label = label == null ? "" : label;
            nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
            path = path == null ? Optional.empty() : path.map(String::strip).filter(s -> !s.isEmpty());
            startLine = startLine == null ? OptionalInt.empty() : startLine;
            endLine = endLine == null ? OptionalInt.empty() : endLine;
            trustTier = (trustTier == null || trustTier.isBlank()) ? TrustTier.COMPILER.name() : trustTier;
        }

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("id", id);
            map.put("kind", kind);
            map.put("label", label);
            map.put("nodeIds", nodeIds);
            map.put("path", path.orElse(null));
            map.put("startLine", startLine.isPresent() ? startLine.getAsInt() : null);
            map.put("endLine", endLine.isPresent() ? endLine.getAsInt() : null);
            map.put("trustTier", trustTier);
            return Collections.unmodifiableMap(map);
        }

        @Override
        public Object canonicalValue() {
            return toView();
        }
    }

    private static final int MAX_CITATION_NODE_IDS = 64;
    private static final Set<String> VALID_TRUST_TIERS = Set.of(
            TrustTier.COMPILER.name(),
            TrustTier.SPRING_SAT.name(),
            TrustTier.SOURCE_SYNTAX.name(),
            TrustTier.ADVISORY_UNVERIFIED.name());

    static List<ResolvedCitation> resolveCitations(List<Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        var resolved = new ArrayList<ResolvedCitation>(evidence.size());
        for (var ev : evidence) {
            if (ev == null) continue;
            resolved.add(resolveCitation(ev));
        }
        return List.copyOf(resolved);
    }

    private static ResolvedCitation resolveCitation(Evidence ev) {
        Map<String, ?> data = ev.data() == null ? Map.of() : ev.data();
        var nodeIds = new LinkedHashSet<String>();
        if ((ev.kind() == Kind.DEPENDENCIES || ev.kind() == Kind.SYMBOLS) && looksLikeGraphNodeId(ev.identity())) {
            addNodeId(nodeIds, ev.identity());
        }
        extractNodeIdsFromMap(data, nodeIds, 0);

        String path = extractPath(data);
        OptionalInt startLine = extractLine(data, "startLine");
        OptionalInt endLine = extractLine(data, "endLine");
        if (startLine.isEmpty() || endLine.isEmpty()) {
            Object span = data.get("span");
            if (span instanceof SourceSpan sp) {
                if (startLine.isEmpty() && sp.startLine() > 0) startLine = OptionalInt.of(sp.startLine());
                if (endLine.isEmpty() && sp.endLine() > 0) endLine = OptionalInt.of(sp.endLine());
            } else if (span instanceof Map<?, ?> sm) {
                if (startLine.isEmpty()) startLine = extractLine(sm, "startLine");
                if (endLine.isEmpty()) endLine = extractLine(sm, "endLine");
            }
        }
        if (path == null) {
            var nestedLoc = extractNestedLocation(data);
            if (nestedLoc != null) {
                path = nestedLoc.path();
                if (startLine.isEmpty()) startLine = nestedLoc.startLine();
                if (endLine.isEmpty()) endLine = nestedLoc.endLine();
            }
        }
        if (startLine.isPresent() && endLine.isEmpty()) {
            endLine = startLine;
        }
        String trustTier = determineTrustTier(ev, data, path);
        return new ResolvedCitation(
                ev.id(),
                ev.kind().name(),
                ev.label(),
                List.copyOf(nodeIds),
                Optional.ofNullable(path),
                startLine,
                endLine,
                trustTier);
    }

    private record LocationRef(String path, OptionalInt startLine, OptionalInt endLine) {}

    private static LocationRef extractNestedLocation(Map<?, ?> data) {
        for (String key : List.of("links", "hubs", "matches", "items")) {
            Object val = data.get(key);
            if (val instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        String p = extractPath(m);
                        if (p != null) {
                            OptionalInt s = extractLine(m, "startLine");
                            OptionalInt e = extractLine(m, "endLine");
                            return new LocationRef(p, s, e.isPresent() ? e : s);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String extractPath(Map<?, ?> data) {
        for (String key : List.of("path", "sourcePath", "file", "filePath")) {
            Object v = data.get(key);
            if (v instanceof String s) {
                String trimmed = s.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("sha256:")) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    private static OptionalInt extractLine(Map<?, ?> data, String key) {
        Object v = data.get(key);
        if (v instanceof Integer i && i > 0) return OptionalInt.of(i);
        if (v instanceof Long l && l > 0 && l <= Integer.MAX_VALUE) return OptionalInt.of(l.intValue());
        if (v instanceof java.math.BigDecimal bd) {
            try {
                int i = bd.intValueExact();
                return i > 0 ? OptionalInt.of(i) : OptionalInt.empty();
            } catch (ArithmeticException ignored) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    private static String determineTrustTier(Evidence ev, Map<String, ?> data, String path) {
        for (String key : List.of("trustTier", "trust")) {
            if (data.get(key) instanceof String s && VALID_TRUST_TIERS.contains(s.strip())) {
                return s.strip();
            }
        }
        String labelLower = ev.label() == null ? "" : ev.label().toLowerCase(Locale.ROOT);
        if (labelLower.contains("advisory")
                || (data.get("schema") instanceof String schema && schema.startsWith("advisory."))) {
            return TrustTier.ADVISORY_UNVERIFIED.name();
        }
        if (labelLower.contains("spring")
                || (ev.identity() != null && ev.identity().startsWith("spring:"))
                || data.containsKey("components")
                || data.containsKey("configurations")
                || data.containsKey("truthRegion")
                || data.containsKey("springConditions")) {
            return TrustTier.SPRING_SAT.name();
        }
        if (path != null) {
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".sql") || lower.endsWith(".tf") || lower.endsWith(".tfvars")
                    || lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".mcp.json")) {
                return TrustTier.ADVISORY_UNVERIFIED.name();
            }
            if (lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".js") || lower.endsWith(".jsx")
                    || lower.endsWith(".mjs") || lower.endsWith(".cjs") || lower.endsWith(".py")
                    || lower.endsWith(".go") || lower.endsWith(".rs") || lower.endsWith(".cs")
                    || lower.endsWith(".php") || lower.endsWith(".rb") || lower.endsWith(".vue")
                    || lower.endsWith(".svelte") || lower.endsWith(".kt") || lower.endsWith(".kts")) {
                return TrustTier.SOURCE_SYNTAX.name();
            }
            if (lower.endsWith(".java") || data.containsKey("reference")) {
                return TrustTier.COMPILER.name();
            }
            if (ev.kind() == Kind.SOURCE) {
                return TrustTier.SOURCE_SYNTAX.name();
            }
        }
        if (ev.identity() != null && (ev.identity().startsWith("polyglot:") || ev.identity().startsWith("file:"))) {
            return TrustTier.SOURCE_SYNTAX.name();
        }
        return TrustTier.COMPILER.name();
    }

    private static void extractNodeIdsFromMap(Map<?, ?> map, Set<String> out, int depth) {
        if (map == null || out.size() >= MAX_CITATION_NODE_IDS || depth > 4) return;
        boolean isEdgeRow = map.containsKey("source") && map.containsKey("target");
        List<String> directKeys = isEdgeRow
                ? List.of("source", "target")
                : List.of("nodeId", "identity", "source", "target", "sourceId", "targetId");
        for (String key : directKeys) {
            Object v = map.get(key);
            if (v instanceof String s && looksLikeGraphNodeId(s)) {
                addNodeId(out, s);
            }
        }
        if (map.get("nodeIds") instanceof List<?> ids) {
            for (Object id : ids) {
                if (id instanceof String s && looksLikeGraphNodeId(s)) addNodeId(out, s);
            }
        }
        if (map.get("path") instanceof List<?> pathNodes) {
            for (Object item : pathNodes) {
                if (item instanceof String s && looksLikeGraphNodeId(s)) addNodeId(out, s);
            }
        }
        for (String nestedMapKey : List.of("symbol", "entity")) {
            Object v = map.get(nestedMapKey);
            if (v instanceof Map<?, ?> m) {
                extractNodeIdsFromMap(m, out, depth + 1);
            } else if (v instanceof EntityView ev) {
                addNodeId(out, ev.identity());
            } else if (v instanceof List<?> list) {
                extractNodeIdsFromList(list, out, depth + 1);
            }
        }
        for (String listKey : List.of("matches", "modules", "edges", "outgoingEdges", "incomingEdges",
                "outgoing", "incoming", "connections", "components", "impact", "nodes", "steps",
                "tangles", "hotspots")) {
            Object v = map.get(listKey);
            if (v instanceof List<?> list) {
                extractNodeIdsFromList(list, out, depth + 1);
            }
        }
    }

    private static void extractNodeIdsFromList(List<?> list, Set<String> out, int depth) {
        if (list == null || out.size() >= MAX_CITATION_NODE_IDS || depth > 4) return;
        for (Object item : list) {
            if (out.size() >= MAX_CITATION_NODE_IDS) break;
            if (item instanceof EntityView ev) {
                addNodeId(out, ev.identity());
            } else if (item instanceof EdgeView edge) {
                addNodeId(out, edge.source());
                addNodeId(out, edge.target());
            } else if (item instanceof ArchitectureQuery.Path path) {
                for (String v : path.vertices()) addNodeId(out, v);
            } else if (item instanceof Neighborhood nb) {
                for (var e : nb.entities()) addNodeId(out, e.identity());
            } else if (item instanceof Map<?, ?> m) {
                extractNodeIdsFromMap(m, out, depth + 1);
            } else if (item instanceof String s && looksLikeGraphNodeId(s)) {
                addNodeId(out, s);
            }
        }
    }

    private static void addNodeId(Set<String> out, String id) {
        if (id != null && !id.isBlank() && out.size() < MAX_CITATION_NODE_IDS) {
            out.add(id.strip());
        }
    }

    private static boolean looksLikeGraphNodeId(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > 512) return false;
        String s = candidate.strip();
        if (s.startsWith("sha256:") || s.startsWith("snapshot:") || s.startsWith("analysis:")
                || s.startsWith("context:") || s.startsWith("http://") || s.startsWith("https://")) {
            return false;
        }
        // Reject bare UUIDs (job IDs)
        if (s.length() == 36 && s.charAt(8) == '-' && s.charAt(13) == '-' && s.charAt(18) == '-' && s.charAt(23) == '-') {
            return false;
        }
        return s.startsWith("java:") || s.startsWith("spring:") || s.startsWith("polyglot:")
                || s.startsWith("type:") || s.startsWith("module:") || s.startsWith("package:")
                || s.startsWith("entity:") || s.startsWith("node:") || s.startsWith("community:")
                || s.startsWith("fixture:") || s.contains("://") == false && s.contains(":");
    }

    record Context(Pin pin, List<Evidence> evidence, List<String> limitations,
            List<FindingDiagnosis.Citation> sources) {
        Map<String, Object> metadata() {
            return Map.of("pin", pin, "evidence", evidence, "limitations", limitations);
        }

        ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION, metadata())));
        }

        Map<String, Object> snapshotBrief() {
            var brief = new LinkedHashMap<String, Object>();
            brief.put("version", VERSION);
            brief.put("graph", pin.graph().value());
            brief.put("snapshot", pin.snapshot());
            brief.put("context", pin.context());
            pin.finding().ifPresent(ref -> brief.put("finding", Map.of(
                    "report", ref.report().value(),
                    "identity", ref.identity().value(),
                    "bundle", ref.bundle().value())));
            return Collections.unmodifiableMap(brief);
        }

        ContentDigest pinDigest() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(snapshotBrief()));
        }

        String snapshotPinnedBrief() {
            return "Snapshot-pinned architecture brief (immutable for this graph/context): "
                    + CanonicalJson.write(snapshotBrief());
        }

        Context withReadiness(Map<String, Object> overview, String job) {
            var updated = new ArrayList<>(evidence);
            add(updated, Kind.STRUCTURE, "Repository readiness overview",
                    ((ContentDigest) overview.get("plan")).value(),
                    Map.of("job", job, "readiness", overview,
                            "interpretation", Map.of(
                                    "semanticCoverage",
                                    "NOT_EVALUATED is the readiness overview's own status, not the graph's semantic coverage verdict.",
                                    "pending",
                                    "Pending counts readiness requirements, not files, modules, roots, or acquisition progress.",
                                    "discoveredSelected",
                                    "Discovered and selected count build contexts, not modules or pending requirements.",
                                    "rootsModules",
                                    "Root and module totals include dependency and generated candidates. Preview lists are bounded samples, not complete inventories.",
                                    "planRevision",
                                    "The overview plan is read at chat time and may differ from the analysis plan that produced the graph.")));
            return new Context(pin, List.copyOf(updated), limitations, sources);
        }

        Context withJob(com.evolution.backend.analysis.AnalysisJobs.View job,
                com.evolution.backend.analysis.AnalysisJobs.Result result) {
            var updated = new ArrayList<>(evidence);
            add(updated, Kind.STRUCTURE, "Published analysis job", job.id(), Map.ofEntries(
                    Map.entry("job", job.id()), Map.entry("mode", job.mode().map(Enum::name).orElse("UNKNOWN")),
                    Map.entry("completion", result.completion()), Map.entry("contexts", result.contexts().size()),
                    Map.entry("reports", result.reports().size()), Map.entry("totalGaps", result.gapCount()),
                    Map.entry("graphGaps", result.graphGapCount()), Map.entry("jobGaps", result.jobGapCount()),
                    Map.entry("inputProblems", result.inputProblemCount())));
            return new Context(pin, List.copyOf(updated), limitations, sources);
        }

        Context withGaps(
                com.evolution.backend.analysis.AnalysisJobs.Page<com.evolution.analysis.evidence.CapabilityGapRecord> page,
                String job) {
            var updated = new ArrayList<>(evidence);
            var counts = new HashMap<String, Integer>();
            page.items().forEach(gap -> counts.merge(gap.reasonCode(), 1, Integer::sum));
            var reasons = counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey())).limit(12)
                    .map(entry -> Map.of("reason", entry.getKey(), "count", entry.getValue())).toList();
            var sample = page.items().stream().limit(8).map(gap -> Map.of(
                    "identity", gap.gapIdentity(), "context", gap.context(), "provider", gap.detectingProvider(),
                    "category", gap.mechanismCategory(), "reason", gap.reasonCode(), "spans",
                    gap.sourceSpans().stream().limit(3).toList(),
                    "diagnosticCodes",
                    gap.diagnostics().stream().map(diagnostic -> diagnostic.code()).distinct().limit(5).toList(),
                    "requirements", gap.evidenceRequirements().stream().map(requirement -> requirement.question())
                            .distinct().limit(5).toList()))
                    .toList();
            add(updated, Kind.STRUCTURE, "Analysis capability gaps", job,
                    Map.of("job", job, "total", page.total(), "counted", page.items().size(), "topReasons", reasons,
                            "truncated", sample.size() < page.total(), "gaps", sample));
            return new Context(pin, List.copyOf(updated), limitations, sources);
        }
    }

    record Result(String version, String status, Pin pin, ContentDigest evidenceIdentity, Optional<String> answer,
            Optional<String> conversationTitle,
            List<String> citations, List<Evidence> evidence, List<String> limitations,
            Optional<ArchitectureChatModel.Receipt> model, List<AgentStep> agentTrace,
            List<ResolvedCitation> resolvedCitations,
            Optional<String> conversationId,
            Optional<String> turnId) {
        Result(String version, String status, Pin pin, ContentDigest evidenceIdentity, Optional<String> answer,
                Optional<String> conversationTitle,
                List<String> citations, List<Evidence> evidence, List<String> limitations,
                Optional<ArchitectureChatModel.Receipt> model) {
            this(version, status, pin, evidenceIdentity, answer, conversationTitle, citations, evidence, limitations,
                    model, List.of(), resolveCitations(evidence), Optional.empty(), Optional.empty());
        }

        Result(String version, String status, Pin pin, ContentDigest evidenceIdentity, Optional<String> answer,
                Optional<String> conversationTitle,
                List<String> citations, List<Evidence> evidence, List<String> limitations,
                Optional<ArchitectureChatModel.Receipt> model, List<AgentStep> agentTrace) {
            this(version, status, pin, evidenceIdentity, answer, conversationTitle, citations, evidence, limitations,
                    model, agentTrace, resolveCitations(evidence), Optional.empty(), Optional.empty());
        }

        Result {
            citations = citations == null ? List.of() : List.copyOf(citations);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            agentTrace = agentTrace == null ? List.of() : List.copyOf(agentTrace);
            resolvedCitations = (resolvedCitations == null || (resolvedCitations.isEmpty() && !evidence.isEmpty()))
                    ? resolveCitations(evidence)
                    : List.copyOf(resolvedCitations);
            conversationId = conversationId == null ? Optional.empty() : conversationId;
            turnId = turnId == null ? Optional.empty() : turnId;
        }

        Result withPersistence(String convId, String assistantTurnId) {
            return new Result(version, status, pin, evidenceIdentity, answer, conversationTitle, citations, evidence,
                    limitations, model, agentTrace, resolvedCitations,
                    Optional.ofNullable(convId), Optional.ofNullable(assistantTurnId));
        }

        static Result local(Context context, boolean configured) {
            var limitations = new ArrayList<>(context.limitations());
            if (!configured)
                limitations.add("Configure a chat provider to get a natural-language answer.");
            return new Result(VERSION, "EVIDENCE_ONLY", context.pin(), context.identity(), Optional.empty(),
                    Optional.empty(),
                    List.of(), context.evidence(), List.copyOf(limitations), Optional.empty(), List.of());
        }

        static Result overview(Context context, ArchitectureChatOverview.Answer answer) {
            return new Result(VERSION, "EVIDENCE_ONLY", context.pin(), context.identity(), Optional.of(answer.text()),
                    Optional.of(answer.title()), answer.citations(), context.evidence(), context.limitations(), Optional.empty(),
                    List.of());
        }

        static Result advice(Context context, ArchitectureChatModel.Completion completion) {
            return adviceWithTrace(context, completion, List.of());
        }

        static Result adviceWithTrace(Context context, ArchitectureChatModel.Completion completion,
                List<AgentStep> trace) {
            var limitations = new ArrayList<>(context.limitations());
            limitations.addAll(completion.limitations());
            return new Result(VERSION, completion.status(), context.pin(), context.identity(),
                    Optional.of(completion.answer()), completion.conversationTitle(),
                    completion.citations(), context.evidence(), List.copyOf(limitations),
                    Optional.of(completion.receipt()), List.copyOf(trace));
        }

        static Result offTopic(Pin pin, String question, Optional<ArchitectureChatModel.Receipt> model) {
            var context = new Context(pin, List.of(), List.of(), List.of());
            String answer = VIETNAMESE.matcher(question).find()
                    ? "Tôi hỗ trợ các câu hỏi về kiến trúc phần mềm. Bạn có thể hỏi về dependency, một finding hoặc cách kiểm chứng một thay đổi."
                    : "I can help with software architecture. Ask about a dependency, a finding, or how to verify a proposed change.";
            return new Result(VERSION, "OUT_OF_SCOPE", pin, context.identity(), Optional.of(answer), Optional.empty(),
                    List.of(), List.of(), List.of(), model, List.of());
        }
    }

    static boolean directlyOutOfScope(Input input) {
        return DIRECT_OFF_TOPIC.matcher(input.question().replaceAll("\\s+", " ")).matches();
    }

    static Pin pin(Input input, KnowledgeGraph.Context partition, ContentDigest graph) {
        return new Pin(graph, partition.snapshot().value(), partition.identity(), input.finding());
    }

    static Context retrieve(Input input, KnowledgeGraph.Context partition, ArchitectureQueryService queries,
            Optional<PolicyEvaluation.Finding> finding) {
        var pin = pin(input, partition, queries.graph().identity());
        var evidence = new ArrayList<Evidence>();
        var limitations = new ArrayList<String>();
        detectPreliminaryTruthTier(queries.graph()).ifPresent(tier -> limitations.add(
                "Preliminary preview graph (" + tier
                        + "): deep semantic analysis is still in progress; syntax-import preview edges are non-authoritative."));
        var request = Request.dependencies(pin.graph(), pin.context());
        var summary = queries.summary(request);
        add(evidence, Kind.SUMMARY, "Captured architecture summary", summary.query().value(), withStatus(summary,
                Map.of("counts", summary.items(), "gapCount", summary.gaps().size())));
        if (summary.status() != AssessmentStatus.COMPLETE) {
            limitations
                    .add("Analysis status is " + summary.status() + ". Missing relationships can change conclusions.");
        }
        if (summary.items().stream().anyMatch(row -> row.nodeCounts().containsKey("MODULE"))) {
            var modules = queries.entities(new Request(pin.graph(), pin.context(), Scope.MODULE, request.projection(),
                    request.direction(), request.relationshipKinds(), request.population(), false, Optional.empty(),
                    request.limits()), "", SAMPLE);
            add(evidence, Kind.STRUCTURE, "Captured modules", modules.query().value(),
                    withStatus(modules,
                            Map.of("modules", modules.items().stream().map(ArchitectureChat::symbol).toList())));
        }
        if (input.focus().isPresent())
            retrieveFocus(input.focus().orElseThrow(), pin, queries, evidence, limitations);
        var sources = finding
                .map(row -> retrieveFinding(input.finding().orElseThrow(), row, queries, evidence, limitations))
                .orElse(List.of());
        if (summary.items().stream().anyMatch(row -> row.nodeCounts().containsKey("TYPE"))) {
            var types = queries.entities(request, "", SAMPLE);
            add(evidence, Kind.SYMBOLS, "Captured types", types.query().value(),
                    withStatus(types,
                            Map.of("matches", types.items().stream().map(ArchitectureChat::symbol).toList())));
            if (types.bounds().truncated() || types.continuation().isPresent())
                limitations.add(
                        "The captured type inventory is a sample; more types are available through graph queries.");
        }
        if (partition.identity().equals(partition.snapshot().value())) {
            var related = queries.graph().contexts().stream()
                    .filter(candidate -> candidate.snapshot().equals(partition.snapshot())
                            && candidate.analysis().isPresent() && !candidate.identity().equals(pin.context()))
                    .toList();
            for (var candidate : related.stream().limit(8).toList()) {
                var semantic = queries.summary(Request.dependencies(pin.graph(), candidate.identity()));
                var mods = queries.entities(new Request(pin.graph(), candidate.identity(), Scope.MODULE, request.projection(),
                        request.direction(), request.relationshipKinds(), request.population(), false, Optional.empty(), request.limits()), "", new Page(8, Optional.empty()));
                var ctxPayload = new LinkedHashMap<String, Object>();
                ctxPayload.put("context", candidate.identity());
                ctxPayload.put("counts", semantic.items());
                ctxPayload.put("gapCount", semantic.gaps().size());
                if (!mods.items().isEmpty()) {
                    ctxPayload.put("modules", mods.items().stream().map(ArchitectureChat::symbol).toList());
                }
                add(evidence, Kind.SUMMARY, "Captured analysis context", semantic.query().value(), withStatus(semantic,
                        Collections.unmodifiableMap(ctxPayload)));
            }
            if (related.size() > 8)
                limitations.add("Only eight analysis contexts from this snapshot are included.");
        }
        if (input.mode() == Mode.REPAIR && finding.isEmpty())
            limitations.add("Attach a published finding to use the captured-source repair workflow.");
        if (!ArchitectureChatOverview.matches(input.question())) {
            enrichQuestionMentions(input.question(), pin, queries, evidence);
            if (!partition.identity().equals(partition.snapshot().value())) {
                enrichInsightsSummary(pin, queries, evidence);
            } else {
                var analysisContexts = queries.graph().contexts().stream()
                        .filter(candidate -> candidate.snapshot().equals(partition.snapshot())
                                && candidate.analysis().isPresent() && !candidate.identity().equals(pin.context()))
                        .limit(8)
                        .map(KnowledgeGraph.Context::identity)
                        .toList();
                if (!analysisContexts.isEmpty()) {
                    enrichQuestionMentionsAcross(input.question(), pin, analysisContexts, queries, evidence);
                    enrichInsightsSummaryAcross(pin, analysisContexts, queries, evidence);
                }
            }
            enrichRepoMapHubs(input.question(), input.focus(), pin, queries, evidence);
            enrichAdvisoryLinks(input.question(), input.focus(), pin, queries, evidence);
        }
        return new Context(pin, List.copyOf(evidence), List.copyOf(limitations), sources);
    }

    private static void enrichRepoMapHubs(String question, Optional<String> focus, Pin pin,
                                          ArchitectureQueryService queries, List<Evidence> evidence) {
        try {
            var fileIndex = CapturedFileIndex.forGraph(queries.graph());
            if (fileIndex.entries().isEmpty()) return;
            var seedSet = new LinkedHashSet<String>();
            focus.filter(f -> !f.isBlank()).ifPresent(seedSet::add);
            seedSet.addAll(extractMentionCandidates(question));
            var seeds = List.copyOf(seedSet);
            var map = RepoMapBuilder.build(queries.graph(), Optional.of(pin.context()), seeds, 10, 1800, () -> StopReason.NONE);
            if (map.files().isEmpty()) return;
            var hubs = map.files().stream().map(f -> Map.<String, Object>of(
                    "rank", f.rank(),
                    "path", neutralize(f.path()),
                    "score", f.score(),
                    "language", f.language(),
                    "inDegree", f.inDegree(),
                    "outDegree", f.outDegree(),
                    "gapCount", f.gapCount())).toList();
            add(evidence, Kind.STRUCTURE, "Top PPR repository hubs", pin.context(),
                    Map.of("algorithm", map.algorithm(), "totalFiles", map.totalFiles(),
                            "seedCount", map.seedCount(), "hubs", hubs,
                            "renderedMap", neutralize(map.renderedMap())));
        } catch (RuntimeException ignored) {
            // Opportunistic PPR seeding never fails retrieval.
        }
    }

    private static void enrichAdvisoryLinks(String question, Optional<String> focus, Pin pin,
                                            ArchitectureQueryService queries, List<Evidence> evidence) {
        try {
            var advisory = com.evolution.backend.query.advisory.AdvisoryOverlayService.forGraph(queries.graph());
            if (advisory.links().isEmpty() && advisory.nodes().isEmpty()) return;
            var seeds = new LinkedHashSet<String>();
            focus.filter(f -> !f.isBlank()).ifPresent(f -> {
                seeds.add(f);
                int dot = f.lastIndexOf('.');
                if (dot >= 0 && dot < f.length() - 1) seeds.add(f.substring(dot + 1));
            });
            seeds.addAll(extractMentionCandidates(question));

            var matchedLinks = new LinkedHashMap<String, com.evolution.backend.query.advisory.AdvisoryOverlay.AdvisoryLink>();
            for (String seed : seeds) {
                if (matchedLinks.size() >= 10) break;
                var filtered = com.evolution.backend.query.advisory.AdvisoryOverlayService.filter(advisory, seed, "", "");
                for (var l : filtered.links()) {
                    if (matchedLinks.size() >= 10) break;
                    matchedLinks.putIfAbsent(l.id(), l);
                }
            }
            // If no explicit seed matched, include top high-signal cross-layer links (SQL<->JPA, ADR, IaC)
            if (matchedLinks.isEmpty()) {
                for (var l : advisory.links()) {
                    if (matchedLinks.size() >= 6) break;
                    if ("SQL_TABLE_MAPS_JPA_ENTITY".equals(l.kind())
                            || "DOC_MENTIONS_SYMBOL".equals(l.kind())
                            || "FOREIGN_KEY".equals(l.kind())
                            || l.kind().startsWith("TF_")) {
                        matchedLinks.putIfAbsent(l.id(), l);
                    }
                }
                for (var l : advisory.links()) {
                    if (matchedLinks.size() >= 6) break;
                    matchedLinks.putIfAbsent(l.id(), l);
                }
            }
            var linkViews = matchedLinks.values().stream().map(l -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("kind", l.kind());
                row.put("sourceLabel", com.evolution.backend.query.llm.LlmTransport.neutralizeInjectionSentinels(l.sourceLabel()));
                row.put("targetLabel", com.evolution.backend.query.llm.LlmTransport.neutralizeInjectionSentinels(l.targetLabel()));
                row.put("targetKind", l.targetKind());
                row.put("sourcePath", com.evolution.backend.query.llm.LlmTransport.neutralizeInjectionSentinels(l.sourcePath()));
                row.put("startLine", l.startLine());
                row.put("endLine", l.endLine());
                row.put("matchBasis", com.evolution.backend.query.llm.LlmTransport.neutralizeInjectionSentinels(l.matchBasis()));
                return Collections.unmodifiableMap(row);
            }).toList();
            var payload = new LinkedHashMap<String, Object>();
            payload.put("schema", advisory.schema());
            payload.put("derivation", advisory.derivation());
            payload.put("trust", advisory.trust());
            payload.put("totalNodes", advisory.nodes().size());
            payload.put("totalLinks", advisory.links().size());
            payload.put("nodeCountsByKind", advisory.nodeCountsByKind());
            payload.put("linkCountsByKind", advisory.linkCountsByKind());
            payload.put("links", linkViews);
            add(evidence, Kind.STRUCTURE, "Advisory non-code links (ADVISORY_UNVERIFIED)", pin.snapshot(),
                    Collections.unmodifiableMap(payload));
        } catch (RuntimeException ignored) {
            // Opportunistic advisory enrichment never fails retrieval.
        }
    }

    private static final Pattern BACKTICK_SYMBOL = Pattern.compile("`([A-Za-z0-9_./$-]{2,140})`");
    private static final Pattern CAMEL_SYMBOL = Pattern.compile("\\b([A-Z][a-z0-9]+[A-Z][A-Za-z0-9_]{1,80}|[a-z]+[A-Z][A-Za-z0-9_]{1,80})\\b");
    private static final Pattern SNAKE_SYMBOL = Pattern.compile("\\b([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\\b");
    private static final Pattern PATH_SYMBOL = Pattern.compile("\\b(?:[A-Za-z0-9_.-]+/)*([A-Za-z0-9_-]{2,80})\\.(?:java|kt|py|ts|tsx|js|jsx|go|rs|cs|php|rb|proto|ya?ml|json)\\b", Pattern.CASE_INSENSITIVE);

    private static void enrichQuestionMentions(String question, Pin pin, ArchitectureQueryService queries, List<Evidence> evidence) {
        enrichQuestionMentionsAcross(question, pin, List.of(pin.context()), queries, evidence);
    }

    static List<String> extractMentionCandidates(String question) {
        if (question == null || question.isBlank()) return List.of();
        var candidates = new LinkedHashSet<String>();
        var bt = BACKTICK_SYMBOL.matcher(question);
        while (bt.find() && candidates.size() < 6) {
            String raw = bt.group(1).strip();
            candidates.add(raw);
            String stem = pathStem(raw);
            if (!stem.isEmpty() && !stem.equals(raw) && candidates.size() < 6) candidates.add(stem);
        }
        var ps = PATH_SYMBOL.matcher(question);
        while (ps.find() && candidates.size() < 6) {
            candidates.add(ps.group(1));
        }
        var cc = CAMEL_SYMBOL.matcher(question);
        while (cc.find() && candidates.size() < 6) {
            candidates.add(cc.group(1));
        }
        var sn = SNAKE_SYMBOL.matcher(question);
        while (sn.find() && candidates.size() < 6) {
            candidates.add(sn.group(1));
        }
        return List.copyOf(candidates);
    }

    private static String pathStem(String value) {
        String norm = value.replace('\\', '/');
        int slash = norm.lastIndexOf('/');
        String name = slash >= 0 ? norm.substring(slash + 1) : norm;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void enrichQuestionMentionsAcross(String question, Pin pin, List<String> contexts,
                                                     ArchitectureQueryService queries, List<Evidence> evidence) {
        var candidates = extractMentionCandidates(question);
        if (candidates.isEmpty())
            return;
        int added = 0;
        var seenSymbols = new HashSet<String>();
        var baseLimits = new Limits(1, 100_000, 6, 100_000, 500_000, 1_000_000, 100_000, 200_000);
        var relKinds = Set.of("graph.depends-on", "java.calls", "java.extends", "java.implements", "java.type-uses");
        for (String term : candidates) {
            if (added >= 3) break;
            String termLower = term.toLowerCase(Locale.ROOT);
            for (String ctx : contexts) {
                var outReq = new Request(pin.graph(), ctx, Scope.ENTITY, Projection.BOUNDED_UNION, Direction.OUTGOING,
                        relKinds, Population.all(), false, Optional.empty(), baseLimits);
                var matches = queries.entities(outReq, term, new Page(10, Optional.empty()));
                EntityView symbol = pickBestMentionMatch(matches.items(), termLower);
                if (symbol == null && matches.items().isEmpty()) {
                    var sample = queries.entities(outReq, "", new Page(50, Optional.empty()));
                    symbol = pickBestMentionMatch(sample.items(), termLower);
                }
                if (symbol == null || !seenSymbols.add(symbol.identity())) continue;
                var outDeps = collectEdges(queries.expand(outReq, symbol.identity()));
                var inReq = new Request(pin.graph(), ctx, Scope.ENTITY, Projection.BOUNDED_UNION, Direction.INCOMING,
                        relKinds, Population.all(), false, Optional.empty(), baseLimits);
                var inDeps = collectEdges(queries.expand(inReq, symbol.identity()));
                add(evidence, Kind.DEPENDENCIES, "Ego-graph of mentioned symbol " + label(symbol), symbol.identity(),
                        Map.of("context", ctx, "symbol", symbol(symbol), "outgoingEdges", outDeps, "incomingEdges", inDeps));
                added++;
                break;
            }
        }
    }

    private static EntityView pickBestMentionMatch(List<EntityView> items, String termLower) {
        if (items.isEmpty()) return null;
        var exact = new ArrayList<EntityView>();
        var partial = new ArrayList<EntityView>();
        for (var e : items) {
            String lbl = label(e).toLowerCase(Locale.ROOT);
            String simple = lbl.contains(".") ? lbl.substring(lbl.lastIndexOf('.') + 1) : lbl;
            String stem = pathStem(lbl).toLowerCase(Locale.ROOT);
            if (e.identity().toLowerCase(Locale.ROOT).equals(termLower)
                    || lbl.equals(termLower) || simple.equals(termLower) || stem.equals(termLower)) {
                exact.add(e);
            } else if (lbl.contains(termLower) || simple.contains(termLower) || stem.contains(termLower)) {
                partial.add(e);
            }
        }
        if (exact.size() == 1) return exact.getFirst();
        if (exact.isEmpty() && partial.size() == 1) return partial.getFirst();
        return null;
    }

    private static void enrichInsightsSummaryAcross(Pin pin, List<String> contexts, ArchitectureQueryService queries, List<Evidence> evidence) {
        int bestCount = -1;
        ArchitectureInsightsAdvisor.InsightsReport bestReport = null;
        for (String ctx : contexts) {
            try {
                var req = new Request(pin.graph(), ctx, Scope.TYPE, Projection.BOUNDED_UNION, Direction.OUTGOING,
                        Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
                var report = ArchitectureInsightsAdvisor.analyze(queries, req, Optional.empty(), 5);
                if (report.summary().analyzedEntities() > bestCount && report.summary().analyzedEntities() >= 3) {
                    bestCount = report.summary().analyzedEntities();
                    bestReport = report;
                }
            } catch (RuntimeException ignored) {
                // Bounded insights enrichment is opportunistic; skip overloaded context.
            }
        }
        if (bestReport != null) {
            add(evidence, Kind.STRUCTURE, "Structural hotspots, cycles & Leiden communities", bestReport.context(),
                    buildInsightsEvidencePayload(bestReport, true));
        }
    }

    private static void enrichInsightsSummary(Pin pin, ArchitectureQueryService queries, List<Evidence> evidence) {
        try {
            var req = new Request(pin.graph(), pin.context(), Scope.TYPE, Projection.BOUNDED_UNION, Direction.OUTGOING,
                    Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
            var report = ArchitectureInsightsAdvisor.analyze(queries, req, Optional.empty(), 5);
            if (report.summary().analyzedEntities() < 3)
                return;
            add(evidence, Kind.STRUCTURE, "Structural hotspots, cycles & Leiden communities", pin.context(),
                    buildInsightsEvidencePayload(report, false));
        } catch (RuntimeException ignored) {
            // Bounded insights enrichment is opportunistic; never fail chat retrieval.
        }
    }

    private static String neutralize(String raw) {
        return com.evolution.backend.query.llm.LlmTransport.neutralizeInjectionSentinels(raw);
    }

    private static Map<String, Object> buildInsightsEvidencePayload(
            ArchitectureInsightsAdvisor.InsightsReport report, boolean includeContext) {
        var tangles = report.tangles().stream().limit(3).map(t -> Map.of(
                "id", neutralize(t.id()), "severity", t.severity(),
                "members", t.memberLabels().stream().limit(8).map(ArchitectureChat::neutralize).toList(),
                "breakCandidates", t.breakCandidates().stream().limit(2).map(c -> Map.of(
                        "source", neutralize(c.sourceLabel()), "target", neutralize(c.targetLabel()),
                        "strategy", neutralize(c.recommendedStrategy()),
                        "rationale", neutralize(c.rationale()))).toList())).toList();
        var hotspots = report.hotspots().stream().limit(5).map(h -> Map.of(
                "label", neutralize(h.label()), "archetype", neutralize(h.archetype()), "score", h.hotspotScore(),
                "transitiveDependents", h.transitiveDependents(), "instability", h.instability(), "inCycle",
                h.inCycle())).toList();
        var communities = report.communities().stream().limit(8).map(c -> Map.<String, Object>of(
                "id", neutralize(c.id()), "label", neutralize(c.label()),
                "dominantPackage", neutralize(c.dominantPackage()),
                "memberCount", c.memberCount(), "cohesion", c.cohesion(),
                "misplacedCount", c.misplacedCount(), "bridgeEdgeCount", c.bridgeEdgeCount())).toList();
        var misplacedTypes = report.misplacedTypes().stream().limit(8).map(m -> Map.<String, Object>of(
                "label", neutralize(m.label()), "declaredPackage", neutralize(m.declaredPackage()),
                "communityLabel", neutralize(m.communityLabel()),
                "internalCommunityEdges", m.internalCommunityEdges(),
                "declaredPackageEdges", m.declaredPackageEdges(),
                "rationale", neutralize(m.rationale()))).toList();
        var communityBridges = report.communityBridges().stream().limit(6).map(b -> Map.<String, Object>of(
                "from", neutralize(b.fromCommunityLabel()), "to", neutralize(b.toCommunityLabel()),
                "edgeCount", b.edgeCount(), "totalWeight", b.totalWeight(),
                "sampleEdges", b.sampleEdges().stream().limit(3).map(ArchitectureChat::neutralize).toList())).toList();
        var payload = new LinkedHashMap<String, Object>();
        if (includeContext) payload.put("context", neutralize(report.context()));
        payload.put("summary", report.summary());
        payload.put("tangles", tangles);
        payload.put("hotspots", hotspots);
        payload.put("communityModularity", report.communityModularity());
        payload.put("communities", communities);
        payload.put("misplacedTypes", misplacedTypes);
        payload.put("communityBridges", communityBridges);
        return Collections.unmodifiableMap(payload);
    }

    private static void retrieveFocus(String focus, Pin pin, ArchitectureQueryService queries,
            List<Evidence> evidence, List<String> limitations) {
        var request = new Request(pin.graph(), pin.context(), Scope.ENTITY, Projection.BOUNDED_UNION,
                Direction.OUTGOING,
                Set.of("graph.depends-on", "java.calls", "java.extends", "java.implements", "java.type-uses"),
                Population.all(), false, Optional.empty(),
                new Limits(1, 100_000, 6, 100_000, 500_000, 1_000_000, 100_000, 200_000));
        var matches = queries.entities(request, focus, SAMPLE);
        add(evidence, Kind.SYMBOLS, "Symbols matching " + neutralize(focus), matches.query().value(),
                withStatus(matches,
                        Map.of("matches", matches.items().stream().map(ArchitectureChat::symbol).toList())));
        if (matches.exactTotal().orElse(0L) != 1 || matches.items().size() != 1) {
            limitations.add("Use a qualified name or symbol identity to select a single match.");
            return;
        }
        var symbol = matches.items().getFirst();
        var deps = queries.expand(request, symbol.identity());
        var edges = collectEdges(deps);
        var inReq = new Request(pin.graph(), pin.context(), Scope.ENTITY, Projection.BOUNDED_UNION, Direction.INCOMING,
                request.relationshipKinds(), Population.all(), false, Optional.empty(), request.limits());
        var incomingEdges = collectEdges(queries.expand(inReq, symbol.identity()));
        var payload = new LinkedHashMap<String, Object>(withStatus(deps, Map.of("edges", edges)));
        if (!incomingEdges.isEmpty())
            payload.put("incomingEdges", incomingEdges);
        add(evidence, Kind.DEPENDENCIES, "Outgoing dependencies of " + label(symbol), deps.query().value(),
                Collections.unmodifiableMap(payload));
        if (deps.bounds().truncated() || deps.continuation().isPresent())
            limitations.add("Only a bounded sample of outgoing dependencies is included.");
    }

    private static List<Map<String, ?>> collectEdges(ArchitectureQuery.Result<Neighborhood> deps) {
        var edges = new ArrayList<Map<String, ?>>();
        for (var neighborhood : deps.items()) {
            var labels = new HashMap<String, String>();
            neighborhood.entities().forEach(entity -> labels.put(entity.identity(), label(entity)));
            for (var edge : neighborhood.edges()) {
                edges.add(Map.of("identity", edge.identity(), "source", edge.source(), "target", edge.target(),
                        "sourceLabel", labels.getOrDefault(edge.source(), edge.source()),
                        "kinds", edge.kinds(), "classification", edge.classification(),
                        "label", labels.getOrDefault(edge.target(), edge.target())));
            }
        }
        return edges;
    }

    private static List<FindingDiagnosis.Citation> retrieveFinding(FindingRef ref, PolicyEvaluation.Finding finding,
            ArchitectureQueryService queries,
            List<Evidence> evidence, List<String> limitations) {
        var bundle = finding.bundle();
        var diagnosis = REPLAY.diagnose(ref.report(), finding, queries);
        add(evidence, Kind.FINDING, finding.rule().id(), ref.identity().value(),
                Map.of("rule", finding.rule(), "kind", finding.kind(), "classification", finding.classification(),
                        "status", bundle.status(), "region", bundle.region().jointRegion(), "scope",
                        bundle.request().scope(),
                        "path", bundle.path().stream().limit(8).toList(), "pathLength", bundle.path().size(),
                        "gapCount", bundle.gaps().size(), "diagnosis", diagnosis.status()));
        if (diagnosis.status() != FindingDiagnosis.Status.CITATIONS_VALIDATED)
            limitations.add("Source citations were withheld: " + diagnosis.reason().orElseThrow().name() + ".");
        var ghCoords = GitHubProvenance.extractFromGraph(queries.graph());
        var codeowners = GitHubProvenance.CodeownersIndex.fromGraph(queries.graph());
        for (var citation : diagnosis.citations()) {
            var data = new LinkedHashMap<String, Object>();
            String safePath = neutralize(citation.path());
            data.put("path", safePath);
            data.put("span", citation.span());
            data.put("sourceDigest", citation.sourceDigest());
            data.put("context", citation.context());
            data.put("reference", citation.evidence());
            var owners = codeowners.ownersOf(citation.path());
            if (!owners.isEmpty()) {
                data.put("owners", owners.stream().map(ArchitectureChat::neutralize).toList());
            }
            ghCoords.flatMap(c -> GitHubProvenance.blobPermalink(
                    c, citation.path(), citation.span().startLine(), citation.span().endLine()))
                    .ifPresent(url -> data.put("permalink", url));
            add(evidence, Kind.SOURCE, safePath + " · line " + citation.span().startLine(),
                    citation.identity().value(), Collections.unmodifiableMap(data));
        }
        if (bundle.path().size() > 8)
            limitations.add("The finding path is sampled.");
        return diagnosis.citations();
    }

    private static Map<String, ?> symbol(EntityView entity) {
        return Map.of("identity", entity.identity(), "kind", entity.kind(), "name", label(entity), "context",
                entity.context());
    }

    private static String label(EntityView entity) {
        return neutralize(entity.qualifiedName().or(entity::canonicalName).or(entity::spelling).orElse(entity.identity()));
    }

    private static Map<String, ?> withStatus(ArchitectureQuery.Result<?> result, Map<String, ?> fields) {
        var data = new TreeMap<String, Object>(fields);
        data.put("status", result.status());
        data.put("operation", result.operation());
        data.put("truncated", result.bounds().truncated() || result.continuation().isPresent());
        data.put("stopReason", result.bounds().stopReason());
        result.exactTotal().ifPresent(total -> data.put("total", total));
        return Collections.unmodifiableMap(data);
    }

    static String addEvidence(List<Evidence> evidence, Kind kind, String label, String identity, Map<String, ?> data) {
        String id = "E" + (evidence.size() + 1);
        evidence.add(new Evidence(id, kind, label, identity, data));
        return id;
    }

    private static void add(List<Evidence> evidence, Kind kind, String label, String identity, Map<String, ?> data) {
        addEvidence(evidence, kind, label, identity, data);
    }

    private static Optional<String> detectPreliminaryTruthTier(KnowledgeGraph graph) {
        if (graph == null) return Optional.empty();
        for (var ev : graph.evidence()) {
            if ("analysis.publication-stage".equals(ev.family()) || "preliminary.context".equals(ev.family())) {
                try {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                    if (Boolean.FALSE.equals(obj.get("authoritative"))) {
                        String tier = obj.get("truthTier") instanceof String s && !s.isBlank()
                                ? s : "PRELIMINARY_STRUCTURE";
                        return Optional.of(tier);
                    }
                } catch (RuntimeException ignored) {
                    return Optional.of("PRELIMINARY_STRUCTURE");
                }
            }
        }
        return Optional.empty();
    }

    private ArchitectureChat() {
    }
}
