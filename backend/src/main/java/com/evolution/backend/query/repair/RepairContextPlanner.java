package com.evolution.backend.query.repair;

import com.evolution.analysis.assessment.v2.LeidenCommunityDetector;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.javaparser.slice.JavaMethodSlicer;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.analysis.query.trace.MethodSliceResult;
import com.evolution.backend.query.ArchitectureInsightsAdvisor;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.RepoMapBuilder;
import com.evolution.backend.query.advisory.AdvisoryOverlay;
import com.evolution.backend.query.advisory.AdvisoryOverlayService;
import com.evolution.backend.query.llm.LlmTransport;
import com.evolution.backend.query.search.CapturedSearchIndex;

import java.util.*;
import java.util.function.Supplier;

/**
 * Bounded multi-file repair context planner. Expands beyond raw finding citations to include
 * dependency-path endpoints, top 1-hop incoming callers (for safe interface extraction / signature
 * updates), relevant Spring config files, sibling tests, quarantined {@link AdvisoryOverlayService}
 * SQL DDL / ADR links, and Leiden community signals. Every planned file is explicitly classified
 * ({@link FileRole}) for transparent disclosure.
 */
public final class RepairContextPlanner {
    public static final int MAX_EXPANDED_FILES = 20;
    public static final int MAX_STATIC_HINT_TOKENS = 30;
    public static final String SPRING_TRUTH_HINT_PREFIX = "# SpringTruth: ";
    public static final String SLICE_HINT_PREFIX = "# SliceHint: ";

    public enum FileRole { CITED, CONTEXT_EXPANDED, SIBLING_TEST, LINKED_DOC }

    public enum ContextRenderMode { FULL_BODY, SIGNATURE_ONLY }

    public record PlannedFile(String path, FileRole role, String reason) {}

    public record LinkedDocSection(String path, String heading, int startLine, int endLine, String digest, String excerpt,
                                   String kind) {
        public LinkedDocSection(String path, String heading, int startLine, int endLine, String digest, String excerpt) {
            this(path, heading, startLine, endLine, digest, excerpt, "MARKDOWN_DOC");
        }
    }

    public record AdvisoryContextBadge(String kind, String label, String sourcePath, int startLine, int endLine,
                                       String matchBasis, String trust) {}

    public record LeidenContextBadge(String communityId, String communityLabel, String dominantPackage,
                                     int memberCount, boolean misplaced, int bridgeCount) {}

    /**
     * SALT {@code CalleeCtx(n)} representation: out-of-edit-scope callees with resolved SKG signatures
     * render ONLY compact {@code Signature(n)} + 1-line Javadoc summary ({@link ContextRenderMode#SIGNATURE_ONLY}),
     * while directly edited/failing or unresolved {@code UNKNOWN} entities render {@link ContextRenderMode#FULL_BODY}.
     */
    public record CalleeContext(
            String entityId,
            String symbolName,
            String path,
            ContextRenderMode renderMode,
            String signature,
            String javadocSummary,
            String renderedContent,
            boolean resolvedInSkg,
            boolean unknownOrUnresolved,
            int estimatedTokens,
            int fullBodyTokens) {
        public CalleeContext {
            entityId = entityId == null ? "" : entityId;
            symbolName = symbolName == null ? "" : symbolName;
            path = path == null ? "" : path;
            renderMode = renderMode == null ? ContextRenderMode.SIGNATURE_ONLY : renderMode;
            signature = signature == null ? "" : signature;
            javadocSummary = javadocSummary == null ? "" : javadocSummary;
            renderedContent = renderedContent == null ? "" : renderedContent;
        }
    }

    /**
     * Compact {@code <= 30}-token static evidence hint per entity ({@code # SpringTruth:} and {@code # SliceHint:}),
     * strictly pre-redacted via {@code llm-redact:v2} and sentinel-neutralized.
     */
    public record StaticEvidenceHint(
            String entityId,
            String symbolName,
            String springTruthHint,
            String sliceHint,
            List<String> lines,
            int estimatedTokens) {
        public StaticEvidenceHint {
            entityId = entityId == null ? "" : entityId;
            symbolName = symbolName == null ? "" : symbolName;
            springTruthHint = springTruthHint == null ? "" : springTruthHint;
            sliceHint = sliceHint == null ? "" : sliceHint;
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    public record SignatureExtraction(String signature, String javadocSummary, String rendered) {}

    public record CompressedContext(
            List<CalleeContext> contexts,
            List<StaticEvidenceHint> hints,
            int fullBodyBaselineTokens,
            int compressedTokens,
            double tokenReductionPercent) {
        public static final CompressedContext EMPTY = new CompressedContext(List.of(), List.of(), 0, 0, 0.0);

        public CompressedContext {
            contexts = List.copyOf(contexts == null ? List.of() : contexts);
            hints = List.copyOf(hints == null ? List.of() : hints);
        }
    }

    public static final String ADVISORY_TACIT_PREFIX = "[ADVISORY_TACIT — UNVERIFIED]";

    public record Plan(
            List<PlannedFile> files,
            List<String> allowedCreateDirectories,
            List<String> callerSymbols,
            List<LinkedDocSection> linkedDocs,
            List<AdvisoryContextBadge> advisoryBadges,
            List<LeidenContextBadge> leidenBadges,
            CompressedContext compressedContext,
            PreEditConfigGate.GateResult preEditConfigGate,
            List<String> advisoryTacitNotes) {
        public Plan(List<PlannedFile> files, List<String> allowedCreateDirectories, List<String> callerSymbols) {
            this(files, allowedCreateDirectories, callerSymbols, List.of(), List.of(), List.of(),
                    CompressedContext.EMPTY, PreEditConfigGate.GateResult.ALLOW, List.of());
        }

        public Plan(List<PlannedFile> files, List<String> allowedCreateDirectories, List<String> callerSymbols,
                    List<LinkedDocSection> linkedDocs) {
            this(files, allowedCreateDirectories, callerSymbols, linkedDocs, List.of(), List.of(),
                    CompressedContext.EMPTY, PreEditConfigGate.GateResult.ALLOW, List.of());
        }

        public Plan(List<PlannedFile> files, List<String> allowedCreateDirectories, List<String> callerSymbols,
                    List<LinkedDocSection> linkedDocs, List<AdvisoryContextBadge> advisoryBadges,
                    List<LeidenContextBadge> leidenBadges) {
            this(files, allowedCreateDirectories, callerSymbols, linkedDocs, advisoryBadges, leidenBadges,
                    CompressedContext.EMPTY, PreEditConfigGate.GateResult.ALLOW, List.of());
        }

        public Plan(List<PlannedFile> files, List<String> allowedCreateDirectories, List<String> callerSymbols,
                    List<LinkedDocSection> linkedDocs, List<AdvisoryContextBadge> advisoryBadges,
                    List<LeidenContextBadge> leidenBadges, CompressedContext compressedContext) {
            this(files, allowedCreateDirectories, callerSymbols, linkedDocs, advisoryBadges, leidenBadges,
                    compressedContext, PreEditConfigGate.GateResult.ALLOW, List.of());
        }

        public Plan {
            files = List.copyOf(files);
            allowedCreateDirectories = List.copyOf(allowedCreateDirectories);
            callerSymbols = List.copyOf(callerSymbols);
            linkedDocs = List.copyOf(linkedDocs == null ? List.of() : linkedDocs);
            advisoryBadges = List.copyOf(advisoryBadges == null ? List.of() : advisoryBadges);
            leidenBadges = List.copyOf(leidenBadges == null ? List.of() : leidenBadges);
            compressedContext = compressedContext == null ? CompressedContext.EMPTY : compressedContext;
            preEditConfigGate = preEditConfigGate == null ? PreEditConfigGate.GateResult.ALLOW : preEditConfigGate;
            advisoryTacitNotes = List.copyOf(advisoryTacitNotes == null ? List.of() : advisoryTacitNotes);
        }

        public boolean isConfigOnlyResolution() {
            return preEditConfigGate.decision() == PreEditConfigGate.GateDecision.CONFIG_ONLY_RESOLUTION;
        }
    }

    private RepairContextPlanner() {}

    public static Plan plan(
            KnowledgeGraph graph,
            String primaryContext,
            String secondaryContext,
            List<String> citedPaths,
            List<String> bundlePath,
            String targetEntityId,
            Supplier<StopReason> stop) {
        return plan(graph, primaryContext, secondaryContext, citedPaths, bundlePath, targetEntityId, stop, null, null, List.of());
    }

    /**
     * Convenience overload accepting optional {@code advisoryTacitNotes} (quarantined Eq. 8 tacit memory notes)
     * so downstream integrators can wire {@code TacitMemoryStore} without changing existing call sites.
     */
    public static Plan plan(
            KnowledgeGraph graph,
            String primaryContext,
            String secondaryContext,
            List<String> citedPaths,
            List<String> bundlePath,
            String targetEntityId,
            Supplier<StopReason> stop,
            CapturedSearchIndex searchIndex,
            List<String> advisoryTacitNotes) {
        return plan(graph, primaryContext, secondaryContext, citedPaths, bundlePath, targetEntityId, stop,
                searchIndex, null, advisoryTacitNotes);
    }

    /**
     * Formats quarantined {@code advisoryTacitNotes} into an explicit {@value #ADVISORY_TACIT_PREFIX} block.
     */
    public static String formatAdvisoryTacitBlock(List<String> advisoryTacitNotes) {
        if (advisoryTacitNotes == null || advisoryTacitNotes.isEmpty()) return "";
        var sb = new StringBuilder(ADVISORY_TACIT_PREFIX).append('\n');
        for (String note : advisoryTacitNotes) {
            if (note == null || note.isBlank()) continue;
            sb.append("- ").append(note).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Hybrid PPR + BM25 + Trigram RRF focus file seeder for blank-focus ad-hoc repairs or symbol-guided edits.
     * Excludes unsupported extensions, speculative config/env files, and secret-bearing files.
     */
    public static List<String> seedFocusFiles(
            KnowledgeGraph graph,
            String context,
            List<String> querySeeds,
            CapturedSearchIndex searchIndex,
            int maxFiles,
            Supplier<StopReason> stop) {
        if (graph == null || querySeeds == null || querySeeds.isEmpty() || maxFiles <= 0) return List.of();
        int cap = Math.max(1, Math.min(10, maxFiles));
        var map = RepoMapBuilder.build(graph, Optional.ofNullable(context), querySeeds,
                Math.max(10, cap * 3), 3_000, stop, searchIndex);
        var fileIndex = CapturedFileIndex.forGraph(graph);
        var out = new ArrayList<String>(cap);
        for (var fr : map.files()) {
            if (out.size() >= cap) break;
            String p = fr.path();
            if (!RepairSyntaxGate.supportsPath(p) || LlmTransport.isConfigOrEnvPath(p)) continue;
            var entryOpt = fileIndex.findExact(p);
            if (entryOpt.isEmpty() || !entryOpt.orElseThrow().readable()) continue;
            var textOpt = fileIndex.readText(graph, entryOpt.orElseThrow());
            if (textOpt.isEmpty() || LlmTransport.containsSecretForPath(p, textOpt.orElseThrow())) continue;
            out.add(p);
        }
        return List.copyOf(out);
    }

    public static Plan plan(
            KnowledgeGraph graph,
            String primaryContext,
            String secondaryContext,
            List<String> citedPaths,
            List<String> bundlePath,
            String targetEntityId,
            Supplier<StopReason> stop,
            CapturedSearchIndex searchIndex) {
        return plan(graph, primaryContext, secondaryContext, citedPaths, bundlePath, targetEntityId,
                stop, searchIndex, null, List.of());
    }

    public static Plan plan(
            KnowledgeGraph graph,
            String primaryContext,
            String secondaryContext,
            List<String> citedPaths,
            List<String> bundlePath,
            String targetEntityId,
            Supplier<StopReason> stop,
            CapturedSearchIndex searchIndex,
            String taskPrompt,
            List<String> advisoryTacitNotes) {
        var sanitizedTacitNotes = sanitizeTacitNotes(advisoryTacitNotes);
        var configGate = (taskPrompt != null && !taskPrompt.isBlank())
                ? PreEditConfigGate.evaluate(graph, primaryContext, taskPrompt, citedPaths, targetEntityId, stop)
                : PreEditConfigGate.GateResult.ALLOW;

        var planned = new LinkedHashMap<String, PlannedFile>();
        var dirs = new LinkedHashSet<String>();
        var callerSymbols = new LinkedHashSet<String>();

        if (citedPaths != null) {
            for (String p : citedPaths) {
                if (p != null && !p.isBlank()) {
                    String norm = CapturedFileIndex.normalizePath(p);
                    planned.putIfAbsent(norm, new PlannedFile(norm, FileRole.CITED, "Cited by finding diagnosis"));
                    addParentDir(dirs, norm);
                }
            }
        }
        if (configGate.decision() == PreEditConfigGate.GateDecision.CONFIG_ONLY_RESOLUTION) {
            for (var match : configGate.matches()) {
                if (!match.filePath().isBlank()) {
                    planned.putIfAbsent(match.filePath(), new PlannedFile(
                            match.filePath(),
                            FileRole.LINKED_DOC,
                            "PreEditConfigGate CONFIG_ONLY_RESOLUTION: " + match.configKey() + " @ " + match.fileLine()));
                }
            }
        }
        if (stop != null && stop.get() != StopReason.NONE) {
            return new Plan(List.copyOf(planned.values()), List.copyOf(dirs), List.of(),
                    List.of(), List.of(), List.of(), CompressedContext.EMPTY, configGate, sanitizedTacitNotes);
        }

        // Build docId -> path map from snapshot
        var docPaths = indexDocumentPaths(graph);
        var contexts = new LinkedHashSet<String>();
        if (primaryContext != null && !primaryContext.isBlank()) contexts.add(primaryContext);
        if (secondaryContext != null && !secondaryContext.isBlank()) contexts.add(secondaryContext);

        // Index nodeId -> declaring file path & label
        var nodePath = new HashMap<String, String>();
        var nodeLabel = new HashMap<String, String>();
        for (String ctx : contexts) {
            if (stop != null && stop.get() != StopReason.NONE) break;
            for (var node : graph.nodes(ctx)) {
                inspectDeclaration(graph, node, docPaths, nodePath, nodeLabel);
            }
        }

        int expanded = 0;
        // 1. Expand bundlePath endpoints not already cited
        if (bundlePath != null) {
            for (String entityId : bundlePath) {
                if (expanded >= MAX_EXPANDED_FILES) break;
                String p = nodePath.get(entityId);
                if (p != null && !planned.containsKey(p) && RepairSyntaxGate.supportsPath(p)) {
                    planned.put(p, new PlannedFile(p, FileRole.CONTEXT_EXPANDED,
                            "Declares finding path entity " + nodeLabel.getOrDefault(entityId, entityId)));
                    addParentDir(dirs, p);
                    expanded++;
                }
            }
        }

        // 2. Expand top 1-hop incoming callers of targetEntityId (critical for DIP / interface extraction)
        if (targetEntityId != null && !targetEntityId.isBlank() && expanded < MAX_EXPANDED_FILES) {
            int callerAdded = 0;
            for (String ctx : contexts) {
                if (callerAdded >= 10 || expanded >= MAX_EXPANDED_FILES) break;
                for (var edge : graph.edges(ctx)) {
                    if (callerAdded >= 10 || expanded >= MAX_EXPANDED_FILES) break;
                    if ("graph.contains".equals(edge.kind()) || "graph.declares".equals(edge.kind())
                            || "graph.owns-source".equals(edge.kind())) continue;
                    if (edge.target().identities().contains(targetEntityId) && !edge.source().equals(targetEntityId)) {
                        callerSymbols.add(nodeLabel.getOrDefault(edge.source(), edge.source()));
                        String p = nodePath.get(edge.source());
                        if (p != null && !planned.containsKey(p) && RepairSyntaxGate.supportsPath(p)) {
                            planned.put(p, new PlannedFile(p, FileRole.CONTEXT_EXPANDED,
                                    "1-hop incoming caller of target (" + edge.kind() + ")"));
                            addParentDir(dirs, p);
                            expanded++;
                            callerAdded++;
                        }
                    }
                }
            }
        }

        // 2b. Hybrid PPR + BM25 + Trigram RRF seeding when CapturedSearchIndex is available
        if (searchIndex != null && expanded < MAX_EXPANDED_FILES && (stop == null || stop.get() == StopReason.NONE)) {
            var rrfSeeds = new LinkedHashSet<String>();
            if (targetEntityId != null && !targetEntityId.isBlank()) {
                rrfSeeds.add(nodeLabel.getOrDefault(targetEntityId, targetEntityId));
            }
            if (bundlePath != null) {
                for (String entityId : bundlePath) {
                    if (entityId != null && !entityId.isBlank()) {
                        rrfSeeds.add(nodeLabel.getOrDefault(entityId, entityId));
                    }
                }
            }
            for (String p : planned.keySet()) {
                rrfSeeds.add(p);
            }
            if (!rrfSeeds.isEmpty()) {
                int rrfCap = planned.isEmpty() ? Math.min(6, MAX_EXPANDED_FILES - expanded)
                        : Math.min(4, MAX_EXPANDED_FILES - expanded);
                var seeded = seedFocusFiles(graph, primaryContext, List.copyOf(rrfSeeds), searchIndex, rrfCap + planned.size(), stop);
                int rrfAdded = 0;
                for (String p : seeded) {
                    if (rrfAdded >= rrfCap || expanded >= MAX_EXPANDED_FILES) break;
                    if (!planned.containsKey(p)) {
                        planned.put(p, new PlannedFile(p, FileRole.CONTEXT_EXPANDED,
                                "Hybrid PPR+BM25+Trigram RRF related focus file"));
                        addParentDir(dirs, p);
                        expanded++;
                        rrfAdded++;
                    }
                }
            }
        }

        // 3. Add Spring configuration file if Spring context exists and budget remains
        var fileIndex = CapturedFileIndex.forGraph(graph);
        if (expanded < MAX_EXPANDED_FILES) {
            boolean hasSpring = graph.contexts().stream().anyMatch(c ->
                    graph.evidence(c.evidence()).map(e -> "spring.context".equals(e.family())).orElse(false));
            if (hasSpring) {
                for (var entry : fileIndex.entries()) {
                    if (expanded >= MAX_EXPANDED_FILES) break;
                    String p = entry.path();
                    String low = p.toLowerCase(Locale.ROOT);
                    if ((low.endsWith("application.yml") || low.endsWith("application.yaml") || low.endsWith("application.properties"))
                            && !planned.containsKey(p) && entry.readable()) {
                        planned.put(p, new PlannedFile(p, FileRole.CONTEXT_EXPANDED, "Spring configuration context"));
                        expanded++;
                    }
                }
            }
        }

        // 4. Add sibling test files (up to 6 within budget) so repair models preserve existing test contracts
        var stemCandidates = collectStems(planned.keySet());
        int testsAdded = 0;
        if (!stemCandidates.isEmpty() && expanded < MAX_EXPANDED_FILES) {
            for (var entry : fileIndex.entries()) {
                if (testsAdded >= 6 || expanded >= MAX_EXPANDED_FILES) break;
                if (!entry.readable() || planned.containsKey(entry.path())) continue;
                String p = entry.path();
                if (isMatchingSiblingTest(p, stemCandidates)) {
                    planned.put(p, new PlannedFile(p, FileRole.SIBLING_TEST, "Sibling test file for cited source"));
                    testsAdded++;
                    expanded++;
                }
            }
        }

        // 5. Discover quarantined AdvisoryOverlay links (SQL DDL <-> JPA @Entity + ADR/Markdown sections)
        var advisoryBadges = new ArrayList<AdvisoryContextBadge>();
        var linkedDocs = discoverAdvisoryLinkedContext(graph, fileIndex, planned.keySet(), nodePath, nodeLabel,
                stemCandidates, advisoryBadges, stop, searchIndex);
        for (var doc : linkedDocs) {
            planned.putIfAbsent(doc.path(), new PlannedFile(doc.path(), FileRole.LINKED_DOC,
                    ("SQL_DDL".equals(doc.kind()) ? "Linked SQL DDL table: " : "Linked documentation section: ")
                            + (doc.heading().isBlank() ? doc.path() : doc.heading())));
        }

        // 6. Compute Leiden community context badges for planned entities (opportunistic, bounded)
        var leidenBadges = collectLeidenContextBadges(graph, primaryContext, planned.keySet(), nodePath, docPaths, stop);

        // 7. Compute SALT CalleeCtx(n) Signature(n) compression & <=30-token compact static evidence hints
        var directEditPaths = new LinkedHashSet<String>();
        for (var pf : planned.values()) {
            if (pf.role() == FileRole.CITED) directEditPaths.add(pf.path());
        }
        var directEntities = new LinkedHashSet<String>();
        if (targetEntityId != null && !targetEntityId.isBlank()) directEntities.add(targetEntityId);
        var candidateSourcePaths = new ArrayList<String>();
        for (var pf : planned.values()) {
            if (pf.role() != FileRole.LINKED_DOC) candidateSourcePaths.add(pf.path());
        }
        var compressed = compressContext(
                graph, primaryContext, secondaryContext, directEditPaths, directEntities, candidateSourcePaths, stop);

        return new Plan(List.copyOf(planned.values()), List.copyOf(dirs), List.copyOf(callerSymbols),
                linkedDocs, advisoryBadges, leidenBadges, compressed, configGate, sanitizedTacitNotes);
    }

    private static List<String> sanitizeTacitNotes(List<String> rawNotes) {
        if (rawNotes == null || rawNotes.isEmpty()) return List.of();
        var cleaned = new ArrayList<String>(rawNotes.size());
        for (String note : rawNotes) {
            if (note == null || note.isBlank()) continue;
            String safe = LlmTransport.neutralizeInjectionSentinels(
                    LlmTransport.redactSecrets(note.replace('\r', ' ').replace('\n', ' ')).text()).strip();
            if (safe.isEmpty()) continue;
            if (!safe.startsWith(ADVISORY_TACIT_PREFIX)) {
                safe = ADVISORY_TACIT_PREFIX + " " + safe;
            }
            cleaned.add(safe);
        }
        return List.copyOf(cleaned);
    }

    // -------------------------------------------------------------------------
    // SALT Signature(n) Compression & <= 30-Token Compact Static Evidence Hints
    // -------------------------------------------------------------------------

    /**
     * Conservative deterministic token estimator (max of 4-char BPE heuristic and word-run count).
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        int charEst = (text.length() + 3) / 4;
        int wordEst = 0;
        boolean inTok = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean wordChar = Character.isLetterOrDigit(c) || c == '_' || c == '.';
            if (wordChar) {
                if (!inTok) {
                    wordEst++;
                    inTok = true;
                }
            } else {
                inTok = false;
            }
        }
        return Math.max(1, Math.max(charEst, wordEst));
    }

    /**
     * Bounds a single static evidence hint line strictly to {@code maxTokens} (default 30) while
     * preserving {@code llm-redact:v2} redaction and sentinel neutralization.
     */
    public static String boundHintLineToMaxTokens(String rawLine, int maxTokens) {
        if (rawLine == null || rawLine.isBlank()) return "";
        int cap = Math.max(8, maxTokens);
        String sanitized = LlmTransport.neutralizeInjectionSentinels(
                LlmTransport.redactSecrets(rawLine.replace('\r', ' ').replace('\n', ' ')).text()).strip();
        if (estimateTokens(sanitized) <= cap) return sanitized;
        int maxChars = Math.max(16, cap * 4 - 3);
        String truncated = sanitized.length() > maxChars ? sanitized.substring(0, maxChars) : sanitized;
        while (truncated.length() > 8 && estimateTokens(truncated + "...") > cap) {
            truncated = truncated.substring(0, truncated.length() - 4);
        }
        return truncated.stripTrailing() + "...";
    }

    /**
     * Formats a {@code <= 30}-token {@code # SpringTruth:} compact static evidence hint, e.g.:
     * {@code # SpringTruth: bean=X, status=MAY(75.0%), witness={profile=prod,cache=true}, peers=[Y]}.
     */
    public static String formatSpringTruthHint(
            String beanName,
            String status,
            Double probabilityPercent,
            Map<String, String> witness,
            List<String> peers) {
        String safeBean = shortSymbol(beanName);
        String safeStatus = (status == null || status.isBlank()) ? "UNKNOWN" : status.strip().toUpperCase(Locale.ROOT);
        var sb = new StringBuilder(SPRING_TRUTH_HINT_PREFIX);
        sb.append("bean=").append(safeBean.isEmpty() ? "unknown" : safeBean);
        sb.append(", status=").append(safeStatus);
        if (probabilityPercent != null && Double.isFinite(probabilityPercent)) {
            sb.append('(').append(String.format(Locale.ROOT, "%.1f", probabilityPercent)).append("%)");
        }
        if (witness != null && !witness.isEmpty()) {
            var sortedWitness = new TreeMap<String, String>();
            for (var e : witness.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    String safeVal = LlmTransport.redactSecrets(e.getValue()).text();
                    sortedWitness.put(e.getKey().strip(), safeVal.strip());
                }
            }
            if (!sortedWitness.isEmpty()) {
                sb.append(", witness={");
                int count = 0;
                for (var e : sortedWitness.entrySet()) {
                    if (count >= 3) break;
                    if (count++ > 0) sb.append(',');
                    sb.append(e.getKey()).append('=').append(e.getValue());
                }
                sb.append('}');
            }
        }
        if (peers != null && !peers.isEmpty()) {
            var sortedPeers = peers.stream()
                    .filter(Objects::nonNull)
                    .map(RepairContextPlanner::shortSymbol)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .sorted()
                    .limit(2)
                    .toList();
            if (!sortedPeers.isEmpty()) {
                sb.append(", peers=[").append(String.join(",", sortedPeers)).append(']');
            }
        }
        return boundHintLineToMaxTokens(sb.toString(), MAX_STATIC_HINT_TOKENS);
    }

    /**
     * Formats a {@code <= 30}-token {@code # SliceHint:} compact static evidence hint, e.g.:
     * {@code # SliceHint: callers=[OrderCtrl#create], callees=[PaySvc#charge]}.
     */
    public static String formatSliceHint(List<String> callers, List<String> callees) {
        var cIn = callers == null ? List.<String>of() : callers.stream()
                .filter(Objects::nonNull)
                .map(RepairContextPlanner::shortSymbol)
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted()
                .limit(2)
                .toList();
        var cOut = callees == null ? List.<String>of() : callees.stream()
                .filter(Objects::nonNull)
                .map(RepairContextPlanner::shortSymbol)
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted()
                .limit(2)
                .toList();
        if (cIn.isEmpty() && cOut.isEmpty()) return "";
        String raw = SLICE_HINT_PREFIX
                + "callers=[" + String.join(",", cIn) + "], "
                + "callees=[" + String.join(",", cOut) + "]";
        return boundHintLineToMaxTokens(raw, MAX_STATIC_HINT_TOKENS);
    }

    /**
     * Extracts compact {@code Signature(n)} + 1-line Javadoc/docstring summary for an out-of-edit-scope
     * callee source file, strictly pre-redacted via {@code llm-redact:v2} before any slicing.
     */
    public static SignatureExtraction extractCompactSignatureAndJavadoc(String path, String language, String rawSource) {
        if (rawSource == null || rawSource.isBlank()) {
            return new SignatureExtraction("", "", "");
        }
        String safePath = path == null ? "source.txt" : path;
        String redacted = LlmTransport.redactDocumentForPath(safePath, rawSource).text();
        redacted = LlmTransport.neutralizeInjectionSentinels(redacted);
        String lang = (language == null || language.isBlank())
                ? CapturedFileIndex.inferLanguage(safePath, List.of())
                : language.toLowerCase(Locale.ROOT);

        String javadoc = extractFirstDocSummary(lang, redacted);
        String sig = switch (lang) {
            case "python" -> extractPythonSignatures(redacted);
            case "typescript", "tsx", "javascript" -> extractBraceLanguageSignatures(redacted, true);
            case "java", "kotlin", "csharp", "go", "rust" -> extractBraceLanguageSignatures(redacted, false);
            default -> extractGenericHeaderSignatures(redacted);
        };
        if (sig.isBlank()) {
            sig = extractGenericHeaderSignatures(redacted);
        }
        var rendered = new StringBuilder();
        if (!javadoc.isBlank()) {
            rendered.append("// ").append(javadoc).append('\n');
        }
        rendered.append(sig);
        return new SignatureExtraction(sig, javadoc, rendered.toString().strip());
    }

    /**
     * Builds SALT {@code CalleeCtx(n)} compressed contexts and {@code <= 30}-token static evidence hints
     * across candidate files/entities in {@code graph}. Directly edited/failing entities and unresolved
     * {@code UNKNOWN} entities retain {@link ContextRenderMode#FULL_BODY}; resolved out-of-edit-scope
     * callees emit ONLY compact {@code Signature(n)} + 1-line Javadoc summary ({@link ContextRenderMode#SIGNATURE_ONLY}).
     */
    public static CompressedContext compressContext(
            KnowledgeGraph graph,
            String primaryContext,
            String secondaryContext,
            Set<String> directEditPaths,
            Set<String> directEditOrFailingEntityIds,
            Collection<String> candidatePaths,
            Supplier<StopReason> stop) {
        if (graph == null || candidatePaths == null || candidatePaths.isEmpty()) {
            return CompressedContext.EMPTY;
        }
        var directPathsNorm = new HashSet<String>();
        if (directEditPaths != null) {
            for (String p : directEditPaths) {
                if (p != null && !p.isBlank()) directPathsNorm.add(CapturedFileIndex.normalizePath(p));
            }
        }
        var directEntities = directEditOrFailingEntityIds == null ? Set.<String>of() : directEditOrFailingEntityIds;

        var contexts = new LinkedHashSet<String>();
        if (primaryContext != null && !primaryContext.isBlank()) contexts.add(primaryContext);
        if (secondaryContext != null && !secondaryContext.isBlank()) contexts.add(secondaryContext);
        contexts.add(graph.snapshot().value());
        for (var c : graph.contexts()) {
            var ev = graph.evidence(c.evidence()).orElse(null);
            if (ev != null && "spring.context".equals(ev.family())) {
                contexts.add(c.identity());
            }
        }

        var docPaths = indexDocumentPaths(graph);
        var nodePath = new HashMap<String, String>();
        var nodeLabel = new HashMap<String, String>();
        var entitiesByPath = new TreeMap<String, List<String>>();
        var unknownEntities = new HashSet<String>();
        var unknownPaths = new HashSet<String>();
        var springInfoByEntity = new HashMap<String, SpringTruthInfo>();

        for (String ctx : contexts) {
            if (stop != null && stop.get() != StopReason.NONE) break;
            for (var node : graph.nodes(ctx)) {
                if ("CAPABILITY_GAP".equals(node.kind()) || "PROVIDER_CONFLICT".equals(node.kind())) {
                    unknownEntities.add(node.identity());
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null) continue;
                        var obj = readSmall(ev);
                        if (obj.get("sourceSpans") instanceof List<?> spans) {
                            for (Object sp : spans) {
                                if (sp instanceof Map<?, ?> m && m.get("document") instanceof String docId) {
                                    String p = docPaths.get(docId);
                                    if (p != null) unknownPaths.add(p);
                                }
                            }
                        }
                        if (obj.get("subject") instanceof Map<?, ?> sub && sub.get("identity") instanceof String sid) {
                            unknownEntities.add(sid);
                        }
                    }
                    continue;
                }
                inspectDeclaration(graph, node, docPaths, nodePath, nodeLabel);
                String p = nodePath.get(node.identity());
                if (p != null) {
                    entitiesByPath.computeIfAbsent(p, k -> new ArrayList<>()).add(node.identity());
                }
                extractSpringTruthInfo(graph, node, nodeLabel, springInfoByEntity);
            }
        }

        // Index incoming callers and outgoing callees per entity for # SliceHint:
        var callersByEntity = new HashMap<String, TreeSet<String>>();
        var calleesByEntity = new HashMap<String, TreeSet<String>>();
        for (String ctx : contexts) {
            if (stop != null && stop.get() != StopReason.NONE) break;
            for (var edge : graph.edges(ctx)) {
                if ("graph.contains".equals(edge.kind()) || "graph.declares".equals(edge.kind())
                        || "graph.owns-source".equals(edge.kind())
                        || "graph.inventory-under-component".equals(edge.kind())) {
                    continue;
                }
                if (edge.target().state() != KnowledgeGraph.TargetState.RESOLVED) {
                    unknownEntities.add(edge.source());
                    continue;
                }
                String srcLabel = shortSymbol(nodeLabel.getOrDefault(edge.source(), edge.source()));
                for (String dstId : edge.target().identities()) {
                    if (dstId.equals(edge.source())) continue;
                    String dstLabel = shortSymbol(nodeLabel.getOrDefault(dstId, dstId));
                    callersByEntity.computeIfAbsent(dstId, k -> new TreeSet<>()).add(srcLabel);
                    calleesByEntity.computeIfAbsent(edge.source(), k -> new TreeSet<>()).add(dstLabel);
                }
            }
        }

        var fileIndex = CapturedFileIndex.forGraph(graph);
        var sortedPaths = candidatePaths.stream()
                .filter(Objects::nonNull)
                .map(CapturedFileIndex::normalizePath)
                .filter(p -> !p.isBlank())
                .distinct()
                .sorted()
                .toList();

        var calleeContexts = new ArrayList<CalleeContext>();
        var hints = new ArrayList<StaticEvidenceHint>();
        int fullBaselineTotal = 0;
        int compressedTotal = 0;

        for (String path : sortedPaths) {
            if (stop != null && stop.get() != StopReason.NONE) break;
            var entryOpt = fileIndex.findExact(path);
            if (entryOpt.isEmpty() || !entryOpt.orElseThrow().readable()) continue;
            var entry = entryOpt.orElseThrow();
            var rawOpt = fileIndex.readText(graph, entry);
            if (rawOpt.isEmpty()) continue;
            String rawText = rawOpt.orElseThrow();
            String redactedFull = LlmTransport.redactDocumentForPath(path, rawText).text();
            int fullTokens = estimateTokens(redactedFull);

            List<String> declaredIds = entitiesByPath.getOrDefault(path, List.of());
            String primaryEntityId = declaredIds.isEmpty() ? ("file:" + path) : declaredIds.getFirst();
            String primaryLabel = declaredIds.isEmpty()
                    ? fileStem(path)
                    : nodeLabel.getOrDefault(primaryEntityId, fileStem(path));
            boolean resolvedInSkg = !declaredIds.isEmpty();
            boolean isDirect = directPathsNorm.contains(path)
                    || declaredIds.stream().anyMatch(directEntities::contains);
            boolean isUnknown = unknownPaths.contains(path)
                    || declaredIds.stream().anyMatch(unknownEntities::contains)
                    || !resolvedInSkg;

            // CalleeCtx(n): FULL_BODY only for directly edited/failing OR unresolved UNKNOWN entities;
            // SIGNATURE_ONLY (Signature(n) + 1-line Javadoc) for resolved out-of-edit-scope callees!
            ContextRenderMode mode = (isDirect || isUnknown)
                    ? ContextRenderMode.FULL_BODY
                    : ContextRenderMode.SIGNATURE_ONLY;

            var extraction = extractCompactSignatureAndJavadoc(path, entry.language(), rawText);
            String rendered = mode == ContextRenderMode.FULL_BODY ? redactedFull : extraction.rendered();
            int estTokens = estimateTokens(rendered);
            fullBaselineTotal += fullTokens;
            compressedTotal += estTokens;

            calleeContexts.add(new CalleeContext(
                    primaryEntityId,
                    shortSymbol(primaryLabel),
                    path,
                    mode,
                    extraction.signature(),
                    extraction.javadocSummary(),
                    rendered,
                    resolvedInSkg,
                    isUnknown,
                    estTokens,
                    fullTokens));

            // Build <=30-token static evidence hints for this file's primary entity
            var callers = new TreeSet<String>();
            var callees = new TreeSet<String>();
            SpringTruthInfo springInfo = null;
            for (String eid : declaredIds) {
                callers.addAll(callersByEntity.getOrDefault(eid, new TreeSet<>()));
                callees.addAll(calleesByEntity.getOrDefault(eid, new TreeSet<>()));
                if (springInfo == null && springInfoByEntity.containsKey(eid)) {
                    springInfo = springInfoByEntity.get(eid);
                }
            }
            var hintLines = new ArrayList<String>();
            String springLine = "";
            if (springInfo != null) {
                springLine = formatSpringTruthHint(
                        springInfo.beanName, springInfo.status, springInfo.probabilityPercent,
                        springInfo.witness, springInfo.peers);
                if (!springLine.isEmpty()) hintLines.add(springLine);
            }
            String sliceLine = formatSliceHint(List.copyOf(callers), List.copyOf(callees));
            if (!sliceLine.isEmpty()) hintLines.add(sliceLine);

            if (!hintLines.isEmpty()) {
                int hintTok = hintLines.stream().mapToInt(RepairContextPlanner::estimateTokens).sum();
                hints.add(new StaticEvidenceHint(
                        primaryEntityId, shortSymbol(primaryLabel), springLine, sliceLine, hintLines, hintTok));
                // Include hint tokens in compressed total when replacing verbose config/caller dumps
                if (mode == ContextRenderMode.SIGNATURE_ONLY) {
                    compressedTotal += hintTok;
                }
            }
        }

        double reduction = fullBaselineTotal > 0
                ? Math.max(0.0, (100.0 * (fullBaselineTotal - compressedTotal)) / fullBaselineTotal)
                : 0.0;
        return new CompressedContext(calleeContexts, hints, fullBaselineTotal, compressedTotal, reduction);
    }

    /**
     * Builds compact {@code <= 30}-token static evidence hints ({@code # SpringTruth:} and {@code # SliceHint:})
     * for specific SKG entity identities.
     */
    public static List<StaticEvidenceHint> buildStaticEvidenceHints(
            KnowledgeGraph graph,
            String primaryContext,
            String secondaryContext,
            Collection<String> entityIds,
            Supplier<StopReason> stop) {
        if (graph == null || entityIds == null || entityIds.isEmpty()) return List.of();
        var targetIds = new LinkedHashSet<String>();
        for (String id : entityIds) {
            if (id != null && !id.isBlank()) targetIds.add(id);
        }
        if (targetIds.isEmpty()) return List.of();

        var contexts = new LinkedHashSet<String>();
        if (primaryContext != null && !primaryContext.isBlank()) contexts.add(primaryContext);
        if (secondaryContext != null && !secondaryContext.isBlank()) contexts.add(secondaryContext);
        contexts.add(graph.snapshot().value());
        for (var c : graph.contexts()) {
            var ev = graph.evidence(c.evidence()).orElse(null);
            if (ev != null && "spring.context".equals(ev.family())) contexts.add(c.identity());
        }

        var docPaths = indexDocumentPaths(graph);
        var nodePath = new HashMap<String, String>();
        var nodeLabel = new HashMap<String, String>();
        var springInfoByEntity = new HashMap<String, SpringTruthInfo>();
        for (String ctx : contexts) {
            if (stop != null && stop.get() != StopReason.NONE) break;
            for (var node : graph.nodes(ctx)) {
                inspectDeclaration(graph, node, docPaths, nodePath, nodeLabel);
                extractSpringTruthInfo(graph, node, nodeLabel, springInfoByEntity);
            }
        }
        var callersByEntity = new HashMap<String, TreeSet<String>>();
        var calleesByEntity = new HashMap<String, TreeSet<String>>();
        for (String ctx : contexts) {
            if (stop != null && stop.get() != StopReason.NONE) break;
            for (var edge : graph.edges(ctx)) {
                if ("graph.contains".equals(edge.kind()) || "graph.declares".equals(edge.kind())
                        || "graph.owns-source".equals(edge.kind())) continue;
                if (edge.target().state() != KnowledgeGraph.TargetState.RESOLVED) continue;
                String srcLabel = shortSymbol(nodeLabel.getOrDefault(edge.source(), edge.source()));
                for (String dstId : edge.target().identities()) {
                    if (dstId.equals(edge.source())) continue;
                    String dstLabel = shortSymbol(nodeLabel.getOrDefault(dstId, dstId));
                    if (targetIds.contains(dstId)) {
                        callersByEntity.computeIfAbsent(dstId, k -> new TreeSet<>()).add(srcLabel);
                    }
                    if (targetIds.contains(edge.source())) {
                        calleesByEntity.computeIfAbsent(edge.source(), k -> new TreeSet<>()).add(dstLabel);
                    }
                }
            }
        }

        var out = new ArrayList<StaticEvidenceHint>();
        var sortedIds = new ArrayList<>(targetIds);
        Collections.sort(sortedIds);
        for (String id : sortedIds) {
            String lbl = shortSymbol(nodeLabel.getOrDefault(id, id));
            var lines = new ArrayList<String>();
            String springLine = "";
            var sInfo = springInfoByEntity.get(id);
            if (sInfo != null) {
                springLine = formatSpringTruthHint(
                        sInfo.beanName, sInfo.status, sInfo.probabilityPercent, sInfo.witness, sInfo.peers);
                if (!springLine.isEmpty()) lines.add(springLine);
            }
            String sliceLine = formatSliceHint(
                    List.copyOf(callersByEntity.getOrDefault(id, new TreeSet<>())),
                    List.copyOf(calleesByEntity.getOrDefault(id, new TreeSet<>())));
            if (!sliceLine.isEmpty()) lines.add(sliceLine);
            if (!lines.isEmpty()) {
                int toks = lines.stream().mapToInt(RepairContextPlanner::estimateTokens).sum();
                out.add(new StaticEvidenceHint(id, lbl, springLine, sliceLine, lines, toks));
            }
        }
        return List.copyOf(out);
    }

    private record SpringTruthInfo(
            String beanName, String status, Double probabilityPercent,
            Map<String, String> witness, List<String> peers) {}

    private static void extractSpringTruthInfo(
            KnowledgeGraph graph,
            KnowledgeGraph.Node node,
            Map<String, String> nodeLabel,
            Map<String, SpringTruthInfo> out) {
        String beanName = null;
        String status = null;
        Double prob = null;
        var witness = new TreeMap<String, String>();
        var peers = new ArrayList<String>();
        boolean foundSpring = false;

        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            if (!Set.of("spring.candidate", "spring.condition", "spring.explicit-region", "spring.symbolic-region")
                    .contains(ev.family())) {
                continue;
            }
            foundSpring = true;
            var obj = readSmall(ev);
            Map<?, ?> regMap = obj.get("region") instanceof Map<?, ?> rm ? rm : null;
            if (obj.get("declaredNameKey") instanceof Map<?, ?> dk && dk.get("primary") instanceof String p && !p.isBlank()) {
                beanName = p;
            } else if (obj.get("beanName") instanceof String bn && !bn.isBlank()) {
                beanName = bn;
            } else if (regMap != null && regMap.get("fact") instanceof Map<?, ?> fm
                    && fm.get("beanName") instanceof String fbn && !fbn.isBlank()) {
                beanName = fbn;
            }
            if (obj.get("classification") instanceof String cls && !cls.isBlank()) {
                status = cls;
            } else if (regMap != null && regMap.get("classification") instanceof String cls && !cls.isBlank()) {
                status = cls;
            }
            Double extractedProb = extractExposurePercent(obj);
            if (extractedProb == null && regMap != null) {
                extractedProb = extractExposurePercent(regMap);
            }
            if (extractedProb != null) {
                prob = extractedProb;
            }
            if (obj.get("witness") instanceof Map<?, ?> wMap) {
                for (var we : wMap.entrySet()) {
                    if (we.getKey() != null && we.getValue() != null) {
                        witness.put(we.getKey().toString(), we.getValue().toString());
                    }
                }
            }
            if (obj.get("peers") instanceof List<?> pList) {
                for (Object po : pList) {
                    if (po != null && !po.toString().isBlank()) peers.add(po.toString());
                }
            }
        }
        if (foundSpring) {
            String effectiveBean = beanName != null ? beanName : nodeLabel.getOrDefault(node.identity(), node.identity());
            String effectiveStatus = status != null ? status : "MAY";
            out.putIfAbsent(node.identity(), new SpringTruthInfo(effectiveBean, effectiveStatus, prob, witness, peers));
        }
    }

    private static Double extractExposurePercent(Map<?, ?> map) {
        if (map == null) return null;
        Double pct = parseFiniteDouble(map.get("probabilityPercent"));
        if (pct != null) return pct;
        Double sat = parseFiniteDouble(map.get("satisfactionRatio"));
        if (sat != null) return sat <= 1.0 ? sat * 100.0 : sat;
        // WP2.3 SymbolicTruthRegions.FactRegion/Row exact d-DNNF mayExposureRatio in [0, 1] -> [0.0, 100.0]
        Double mayRatio = parseFiniteDouble(map.get("mayExposureRatio"));
        if (mayRatio != null) return (mayRatio >= 0.0 && mayRatio <= 1.0) ? mayRatio * 100.0 : mayRatio;
        return null;
    }

    private static Double parseFiniteDouble(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                double d = Double.parseDouble(s.strip());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String extractFirstDocSummary(String lang, String text) {
        if ("python".equals(lang)) {
            int idx = text.indexOf("\"\"\"");
            String delim = "\"\"\"";
            int alt = text.indexOf("'''");
            if (idx < 0 || (alt >= 0 && alt < idx)) {
                idx = alt;
                delim = "'''";
            }
            if (idx >= 0) {
                int end = text.indexOf(delim, idx + 3);
                if (end > idx + 3) {
                    return cleanOneLineSummary(text.substring(idx + 3, end));
                }
            }
            return "";
        }
        int start = text.indexOf("/**");
        if (start >= 0) {
            int end = text.indexOf("*/", start + 3);
            if (end > start + 3) {
                String raw = text.substring(start + 3, end).replaceAll("(?m)^\\s*\\*+\\s?", " ");
                return cleanOneLineSummary(raw);
            }
        }
        return "";
    }

    private static String cleanOneLineSummary(String raw) {
        if (raw == null) return "";
        var lines = raw.split("\\R");
        var sb = new StringBuilder();
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("@")) {
                if (sb.length() > 0) break;
                continue;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
            int dot = sb.indexOf(". ");
            if (dot > 0) {
                sb.setLength(dot + 1);
                break;
            }
            if (sb.length() >= 96) break;
        }
        String collapsed = sb.toString().replaceAll("\\s+", " ").strip();
        return collapsed.length() > 100 ? collapsed.substring(0, 97) + "..." : collapsed;
    }

    private static String extractPythonSignatures(String text) {
        var out = new ArrayList<String>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].strip();
            if (trimmed.startsWith("class ") || trimmed.startsWith("def ") || trimmed.startsWith("async def ")) {
                int indent = lines[i].indexOf(trimmed.charAt(0));
                String prefix = indent > 0 ? "  " : "";
                out.add(prefix + trimmed);
                if (out.size() >= 24) break;
            }
        }
        return String.join("\n", out);
    }

    private static String extractBraceLanguageSignatures(String text, boolean isTsJs) {
        var sigLines = new ArrayList<String>();
        int depth = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        boolean escaped = false;
        var currentHeader = new StringBuilder();
        int len = text.length();

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            char next = (i + 1 < len) ? text.charAt(i + 1) : 0;

            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inSingle || inDouble || inBacktick) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if ((inSingle && c == '\'') || (inDouble && c == '"') || (inBacktick && c == '`')) {
                    inSingle = false;
                    inDouble = false;
                    inBacktick = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '\'') { inSingle = true; continue; }
            if (c == '"') { inDouble = true; continue; }
            if (isTsJs && c == '`') { inBacktick = true; continue; }

            if (c == '{') {
                String hdr = normalizeHeader(currentHeader.toString());
                currentHeader.setLength(0);
                if (depth == 0) {
                    if (!hdr.isEmpty()) {
                        if (isTopLevelTypeHeader(hdr, isTsJs)) {
                            sigLines.add(hdr + " {");
                        } else if (isCallableSignature(hdr, isTsJs)) {
                            sigLines.add(hdr + ";");
                        }
                    }
                } else if (depth == 1) {
                    if (!hdr.isEmpty() && isCallableSignature(hdr, isTsJs)) {
                        sigLines.add("  " + hdr + ";");
                    }
                }
                depth++;
                continue;
            }
            if (c == '}') {
                currentHeader.setLength(0);
                if (depth > 0) depth--;
                continue;
            }
            if (c == ';') {
                String stmt = normalizeHeader(currentHeader.toString());
                currentHeader.setLength(0);
                if (depth == 0 && (stmt.startsWith("package ") || stmt.startsWith("export type ") || stmt.startsWith("type "))) {
                    sigLines.add(stmt + ";");
                } else if (depth == 1 && isAbstractOrInterfaceMethod(stmt, isTsJs)) {
                    sigLines.add("  " + stmt + ";");
                }
                continue;
            }
            if (depth <= 1 && currentHeader.length() < 400) {
                currentHeader.append(c);
            }
        }
        if (sigLines.size() > 28) {
            return String.join("\n", sigLines.subList(0, 28));
        }
        return String.join("\n", sigLines);
    }

    private static String normalizeHeader(String raw) {
        if (raw == null) return "";
        // Strip leading Java/TS annotations unless short single marker, collapse whitespace
        String collapsed = raw.replaceAll("\\s+", " ").strip();
        return collapsed.length() > 160 ? collapsed.substring(0, 157) + "..." : collapsed;
    }

    private static boolean isTopLevelTypeHeader(String hdr, boolean isTsJs) {
        return hdr.contains("class ") || hdr.contains("interface ") || hdr.contains("enum ")
                || hdr.contains("record ") || (isTsJs && (hdr.contains("namespace ") || hdr.startsWith("export type ")));
    }

    private static boolean isCallableSignature(String hdr, boolean isTsJs) {
        if (!hdr.contains("(") || !hdr.contains(")")) return false;
        if (hdr.startsWith("if ") || hdr.startsWith("if(") || hdr.startsWith("for ") || hdr.startsWith("for(")
                || hdr.startsWith("while ") || hdr.startsWith("while(") || hdr.startsWith("switch ")
                || hdr.startsWith("catch ") || hdr.startsWith("synchronized ")) {
            return false;
        }
        return !isTsJs || hdr.contains("function ") || hdr.contains("export ") || hdr.contains("=>")
                || hdr.contains(":") || hdr.indexOf('(') > 0;
    }

    private static boolean isAbstractOrInterfaceMethod(String stmt, boolean isTsJs) {
        return stmt.contains("(") && stmt.contains(")") && !stmt.contains("=");
    }

    private static String extractGenericHeaderSignatures(String text) {
        var out = new ArrayList<String>();
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("#")) continue;
            out.add(t.length() > 120 ? t.substring(0, 117) + "..." : t);
            if (out.size() >= 6) break;
        }
        return String.join("\n", out);
    }

    private static String shortSymbol(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (s.startsWith("java:v1:")) s = com.evolution.backend.query.RepoMapBuilder.readableCanonicalName(s);
        int colon = s.lastIndexOf("::");
        if (colon >= 0 && colon < s.length() - 2) s = s.substring(colon + 2);
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) {
            int prevDot = s.lastIndexOf('.', dot - 1);
            // Preserve Class.method when method-level; otherwise simple class name
            String tail = s.substring(dot + 1);
            if (prevDot >= 0 && !tail.isEmpty() && Character.isLowerCase(tail.charAt(0))) {
                s = s.substring(prevDot + 1).replace('.', '#');
            } else {
                s = tail;
            }
        }
        return s;
    }

    private static String fileStem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Set<String> collectStems(Collection<String> paths) {
        var stems = new LinkedHashSet<String>();
        for (String p : paths) {
            String name = p.substring(p.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            if (stem.length() >= 3) {
                stems.add(stem);
            }
        }
        return stems;
    }

    static boolean isMatchingSiblingTest(String path, Set<String> stems) {
        String low = path.toLowerCase(Locale.ROOT);
        boolean inTestDir = low.contains("/test/") || low.startsWith("test/") || low.contains("/tests/")
                || low.contains("/__tests__/") || low.endsWith("test.java") || low.endsWith("tests.java")
                || low.endsWith(".test.ts") || low.endsWith(".spec.ts") || low.endsWith(".test.js");
        if (!inTestDir) return false;
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        String fileStem = (dot > 0 ? file.substring(0, dot) : file).toLowerCase(Locale.ROOT);
        for (String stem : stems) {
            String s = stem.toLowerCase(Locale.ROOT);
            if (fileStem.equals(s + "test") || fileStem.equals(s + "tests") || fileStem.equals(s + "it")
                    || fileStem.equals(s + "spec") || fileStem.equals("test_" + s)
                    || fileStem.equals(s + ".test") || fileStem.equals(s + ".spec")) {
                return true;
            }
        }
        return false;
    }

    private static List<LinkedDocSection> discoverAdvisoryLinkedContext(
            KnowledgeGraph graph,
            CapturedFileIndex fileIndex,
            Set<String> plannedPaths,
            Map<String, String> nodePath,
            Map<String, String> nodeLabel,
            Set<String> stems,
            List<AdvisoryContextBadge> outBadges,
            Supplier<StopReason> stop,
            CapturedSearchIndex searchIndex) {
        if (plannedPaths.isEmpty() && stems.isEmpty()) return List.of();
        var docs = new ArrayList<LinkedDocSection>();
        var seenDocKeys = new HashSet<String>();
        int maxSqlDocs = 2;
        int maxMdDocs = 2;
        int sqlCount = 0;
        int mdCount = 0;
        int maxCharsPerExcerpt = 1_200;

        // Gather entity IDs and labels declared in planned editable files
        var plannedEntityIds = new HashSet<String>();
        var plannedLabelsLower = new HashSet<String>();
        var plannedPathsLower = new HashSet<String>();
        for (String p : plannedPaths) {
            plannedPathsLower.add(p.toLowerCase(Locale.ROOT));
        }
        for (var e : nodePath.entrySet()) {
            if (plannedPaths.contains(e.getValue())) {
                plannedEntityIds.add(e.getKey());
                String lbl = nodeLabel.get(e.getKey());
                if (lbl != null && !lbl.isBlank()) {
                    plannedLabelsLower.add(lbl.toLowerCase(Locale.ROOT));
                    int dot = lbl.lastIndexOf('.');
                    if (dot >= 0 && dot < lbl.length() - 1) {
                        plannedLabelsLower.add(lbl.substring(dot + 1).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        for (String stem : stems) {
            plannedLabelsLower.add(stem.toLowerCase(Locale.ROOT));
        }

        AdvisoryOverlay.Snapshot advisory = null;
        try {
            if (stop == null || stop.get() == StopReason.NONE) {
                advisory = AdvisoryOverlayService.forGraph(graph);
            }
        } catch (RuntimeException ignored) {
            advisory = null;
        }

        if (advisory != null && !advisory.links().isEmpty()) {
            // 5a. Linked Flyway / SQL DDL tables for edited JPA @Entity classes
            for (var link : advisory.links()) {
                if (sqlCount >= maxSqlDocs || (stop != null && stop.get() != StopReason.NONE)) break;
                if (!"SQL_TABLE_MAPS_JPA_ENTITY".equals(link.kind())) continue;
                if (!matchesPlannedEntity(link, plannedEntityIds, plannedLabelsLower, plannedPathsLower)) continue;
                String sqlPath = link.sourcePath();
                String key = "SQL|" + sqlPath + "|" + link.sourceLabel();
                if (!seenDocKeys.add(key)) continue;
                int startLine = Math.max(1, link.startLine());
                int endLine = Math.max(startLine, Math.min(startLine + 39, link.endLine()));
                var read = fileIndex.read(graph, sqlPath, startLine, endLine);
                if (!read.found()) continue;
                String excerpt = read.content().length() > maxCharsPerExcerpt
                        ? read.content().substring(0, maxCharsPerExcerpt) + "\n..."
                        : read.content();
                docs.add(new LinkedDocSection(
                        read.path(),
                        "SQL TABLE " + link.sourceLabel() + " (" + link.matchBasis() + ")",
                        read.startLine(),
                        read.endLine(),
                        read.digest().map(d -> d.value()).orElse(link.contentSha256()),
                        excerpt,
                        "SQL_DDL"));
                outBadges.add(new AdvisoryContextBadge(
                        link.kind(), link.sourceLabel() + " ↔ " + link.targetLabel(),
                        read.path(), read.startLine(), read.endLine(), link.matchBasis(), AdvisoryOverlay.TRUST));
                sqlCount++;
            }

            // 5b. Linked ADR / Markdown documentation sections from AdvisoryOverlayService
            for (var link : advisory.links()) {
                if (mdCount >= maxMdDocs || (stop != null && stop.get() != StopReason.NONE)) break;
                if (!("DOC_MENTIONS_SYMBOL".equals(link.kind())
                        || "DOC_LINKS_FILE".equals(link.kind())
                        || "DOC_MENTIONS_FILE".equals(link.kind())
                        || "DOC_WIKILINK".equals(link.kind()))) {
                    continue;
                }
                if (!matchesPlannedEntity(link, plannedEntityIds, plannedLabelsLower, plannedPathsLower)) continue;
                String docPath = link.sourcePath();
                if (!docPath.toLowerCase(Locale.ROOT).endsWith(".md")
                        && !docPath.toLowerCase(Locale.ROOT).endsWith(".mdx")) {
                    continue;
                }
                var headingsRes = fileIndex.listMarkdownHeadings(graph, docPath);
                String targetHeadingQuery = "";
                for (var h : headingsRes.headings()) {
                    if (h.startLine() <= link.startLine() && link.startLine() <= h.endLine()) {
                        targetHeadingQuery = (h.anchor() != null && !h.anchor().isBlank()) ? h.anchor() : h.title();
                    }
                }
                var sec = fileIndex.readMarkdownSection(graph, docPath, targetHeadingQuery);
                if (!sec.found()) continue;
                String key = "MD|" + sec.path() + "|" + sec.startLine();
                if (!seenDocKeys.add(key)) continue;
                String excerpt = sec.content().length() > maxCharsPerExcerpt
                        ? sec.content().substring(0, maxCharsPerExcerpt) + "\n..."
                        : sec.content();
                docs.add(new LinkedDocSection(
                        sec.path(),
                        sec.heading(),
                        sec.startLine(),
                        sec.endLine(),
                        sec.digest().map(d -> d.value()).orElse(link.contentSha256()),
                        excerpt,
                        "MARKDOWN_DOC"));
                outBadges.add(new AdvisoryContextBadge(
                        link.kind(),
                        (sec.heading().isBlank() ? sec.path() : sec.heading()) + " → " + link.targetLabel(),
                        sec.path(), sec.startLine(), sec.endLine(), link.matchBasis(), AdvisoryOverlay.TRUST));
                mdCount++;
            }
        }

        // Fast FTS5 BM25 Markdown discovery fallback when search index is available
        if (mdCount == 0 && searchIndex != null && !stems.isEmpty() && (stop == null || stop.get() == StopReason.NONE)) {
            var mdHits = searchIndex.codeSearch(graph, String.join(" ", stems), List.of("markdown"), true, 4, 0, stop);
            if (mdHits.isPresent()) {
                for (var hit : mdHits.orElseThrow().items()) {
                    if (mdCount >= maxMdDocs) break;
                    var headingsRes = fileIndex.listMarkdownHeadings(graph, hit.path());
                    String targetHeadingQuery = "";
                    for (var h : headingsRes.headings()) {
                        if (h.startLine() <= hit.startLine() && hit.startLine() <= h.endLine()) {
                            targetHeadingQuery = (h.anchor() != null && !h.anchor().isBlank()) ? h.anchor() : h.title();
                        }
                    }
                    var sec = fileIndex.readMarkdownSection(graph, hit.path(), targetHeadingQuery);
                    if (!sec.found()) continue;
                    String key = "MD|" + sec.path() + "|" + sec.startLine();
                    if (!seenDocKeys.add(key)) continue;
                    String excerpt = sec.content().length() > maxCharsPerExcerpt
                            ? sec.content().substring(0, maxCharsPerExcerpt) + "\n..."
                            : sec.content();
                    docs.add(new LinkedDocSection(
                            sec.path(), sec.heading(), sec.startLine(), sec.endLine(),
                            sec.digest().map(d -> d.value()).orElse(hit.contentSha256()), excerpt, "MARKDOWN_DOC"));
                    outBadges.add(new AdvisoryContextBadge(
                            "DOC_MENTIONS_SYMBOL",
                            (sec.heading().isBlank() ? sec.path() : sec.heading()) + " → BM25 match",
                            sec.path(), sec.startLine(), sec.endLine(), "fts5-bm25-doc-fallback", AdvisoryOverlay.TRUST));
                    mdCount++;
                }
            }
        }

        // Fallback for minimal graphs without canonical symbol declarations where an ADR explicitly cites a stem
        if (mdCount == 0 && !stems.isEmpty()) {
            for (var entry : fileIndex.entries()) {
                if (mdCount >= maxMdDocs || (stop != null && stop.get() != StopReason.NONE)) break;
                if (!entry.readable() || !"markdown".equalsIgnoreCase(entry.language())) continue;
                String low = entry.path().toLowerCase(Locale.ROOT);
                boolean prioritizedDoc = low.contains("adr") || low.contains("decision") || low.contains("architecture")
                        || low.endsWith("readme.md");
                if (!prioritizedDoc) continue;
                var textOpt = fileIndex.readText(graph, entry);
                if (textOpt.isEmpty()) continue;
                String raw = textOpt.orElseThrow();
                String matchedStem = null;
                for (String stem : stems) {
                    if (raw.contains(stem)) {
                        matchedStem = stem;
                        break;
                    }
                }
                if (matchedStem == null) continue;
                var headingsRes = fileIndex.listMarkdownHeadings(graph, entry.path());
                String[] lines = raw.split("\\R", -1);
                int matchLine = 1;
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].contains(matchedStem)) {
                        matchLine = i + 1;
                        break;
                    }
                }
                String targetHeadingQuery = "";
                for (var h : headingsRes.headings()) {
                    if (h.startLine() <= matchLine && matchLine <= h.endLine()) {
                        targetHeadingQuery = (h.anchor() != null && !h.anchor().isBlank()) ? h.anchor() : h.title();
                    }
                }
                var sec = fileIndex.readMarkdownSection(graph, entry.path(), targetHeadingQuery);
                if (!sec.found()) continue;
                String key = "MD|" + sec.path() + "|" + sec.startLine();
                if (!seenDocKeys.add(key)) continue;
                String excerpt = sec.content().length() > maxCharsPerExcerpt
                        ? sec.content().substring(0, maxCharsPerExcerpt) + "\n..."
                        : sec.content();
                docs.add(new LinkedDocSection(
                        sec.path(), sec.heading(), sec.startLine(), sec.endLine(),
                        sec.digest().map(d -> d.value()).orElse(""), excerpt, "MARKDOWN_DOC"));
                outBadges.add(new AdvisoryContextBadge(
                        "DOC_MENTIONS_SYMBOL",
                        (sec.heading().isBlank() ? sec.path() : sec.heading()) + " → " + matchedStem,
                        sec.path(), sec.startLine(), sec.endLine(), "section-stem-fallback", AdvisoryOverlay.TRUST));
                mdCount++;
            }
        }
        return List.copyOf(docs);
    }

    private static boolean matchesPlannedEntity(
            AdvisoryOverlay.AdvisoryLink link,
            Set<String> plannedEntityIds,
            Set<String> plannedLabelsLower,
            Set<String> plannedPathsLower) {
        if (link.targetId() != null && plannedEntityIds.contains(link.targetId())) return true;
        String tId = link.targetId() == null ? "" : link.targetId();
        if (tId.startsWith("file:")) {
            String fp = tId.substring("file:".length());
            int hash = fp.indexOf('#');
            if (hash >= 0) fp = fp.substring(0, hash);
            if (plannedPathsLower.contains(fp.toLowerCase(Locale.ROOT))) return true;
        }
        String tLbl = link.targetLabel() == null ? "" : link.targetLabel().toLowerCase(Locale.ROOT);
        if (!tLbl.isEmpty()) {
            if (plannedLabelsLower.contains(tLbl) || plannedPathsLower.contains(tLbl)) return true;
            int dot = tLbl.lastIndexOf('.');
            if (dot >= 0 && dot < tLbl.length() - 1 && plannedLabelsLower.contains(tLbl.substring(dot + 1))) {
                return true;
            }
            for (String p : plannedPathsLower) {
                if (p.endsWith("/" + tLbl) || p.equals(tLbl)) return true;
            }
        }
        return false;
    }

    private static List<LeidenContextBadge> collectLeidenContextBadges(
            KnowledgeGraph graph,
            String primaryContext,
            Set<String> plannedPaths,
            Map<String, String> nodePath,
            Map<String, String> docPaths,
            Supplier<StopReason> stop) {
        if (primaryContext == null || primaryContext.isBlank() || plannedPaths.isEmpty()) return List.of();
        if (stop != null && stop.get() != StopReason.NONE) return List.of();
        try {
            var queries = new ArchitectureQueryService(graph, () -> stop == null ? StopReason.NONE : stop.get(), Optional.empty());
            String ctx = primaryContext;
            Map<String, String> effectiveNodePath = nodePath;
            if (ctx.equals(graph.snapshot().value())) {
                ctx = graph.contexts().stream()
                        .filter(c -> c.analysis().isPresent())
                        .max(Comparator.comparingInt(c -> graph.nodes(c.identity()).size()))
                        .map(KnowledgeGraph.Context::identity)
                        .orElse(primaryContext);
                if (!ctx.equals(primaryContext)) {
                    var mergedPaths = new HashMap<String, String>(nodePath);
                    var tmpLabels = new HashMap<String, String>();
                    for (var node : graph.nodes(ctx)) {
                        inspectDeclaration(graph, node, docPaths, mergedPaths, tmpLabels);
                    }
                    effectiveNodePath = mergedPaths;
                }
            }
            var req = new ArchitectureQuery.Request(
                    graph.identity(), ctx, ArchitectureQuery.Scope.TYPE,
                    ArchitectureQuery.Projection.BOUNDED_UNION, ArchitectureQuery.Direction.OUTGOING,
                    Set.of("graph.depends-on"), ArchitectureQuery.Population.all(),
                    false, Optional.empty(), ArchitectureQuery.Limits.defaults());
            LeidenCommunityDetector.Result leiden = ArchitectureInsightsAdvisor.detectCommunities(queries, req);
            if (leiden.communities().isEmpty()) return List.of();

            var commById = new LinkedHashMap<String, LeidenCommunityDetector.EmergentCommunity>();
            for (var c : leiden.communities()) commById.put(c.id(), c);
            var misplacedIds = new HashSet<String>();
            for (var m : leiden.misplacedTypes()) misplacedIds.add(m.identity());

            var touchedCommIds = new LinkedHashMap<String, Boolean>();
            for (var entry : effectiveNodePath.entrySet()) {
                if (!plannedPaths.contains(entry.getValue())) continue;
                String cid = leiden.communityByNode().get(entry.getKey());
                if (cid != null) {
                    boolean isMisplaced = misplacedIds.contains(entry.getKey());
                    touchedCommIds.merge(cid, isMisplaced, (a, b) -> a || b);
                }
            }
            var out = new ArrayList<LeidenContextBadge>();
            for (var e : touchedCommIds.entrySet()) {
                var comm = commById.get(e.getKey());
                if (comm == null) continue;
                out.add(new LeidenContextBadge(
                        comm.id(), comm.label(), comm.dominantPackage(),
                        comm.memberCount(), e.getValue() || comm.misplacedCount() > 0, comm.bridgeEdgeCount()));
                if (out.size() >= 4) break;
            }
            return List.copyOf(out);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static void addParentDir(Set<String> dirs, String path) {
        int slash = path.lastIndexOf('/');
        if (slash > 0) {
            String dir = path.substring(0, slash);
            if (!dir.contains("src/test") && !dir.startsWith(".git")) {
                dirs.add(dir);
            }
        }
    }

    static Map<String, String> indexDocumentPaths(KnowledgeGraph graph) {
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

    static void inspectDeclaration(KnowledgeGraph graph, KnowledgeGraph.Node node,
                                           Map<String, String> docPaths,
                                           Map<String, String> nodePath,
                                           Map<String, String> nodeLabel) {
        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            if ("java.declaration".equals(ev.family())) {
                var obj = readSmall(ev);
                if (obj.get("entity") instanceof Map<?, ?> ent) {
                    if (ent.get("qualifiedName") instanceof String q && !q.isBlank()) {
                        nodeLabel.putIfAbsent(node.identity(), q);
                    } else if (ent.get("canonicalName") instanceof String cn && !cn.isBlank()) {
                        String readable = com.evolution.backend.query.RepoMapBuilder.readableCanonicalName(cn);
                        if (readable != null && !readable.isBlank()) {
                            nodeLabel.putIfAbsent(node.identity(), readable);
                        }
                    }
                    if (ent.get("declaration") instanceof Map<?, ?> sp && sp.get("document") instanceof String docId) {
                        String p = docPaths.get(docId);
                        if (p != null) nodePath.putIfAbsent(node.identity(), p);
                    }
                }
            } else if ("polyglot.source-syntax".equals(ev.family())) {
                var obj = readSmall(ev);
                Map<?, ?> fields = obj.get("fields") instanceof Map<?, ?> f ? f : obj;
                if (fields.get("name") instanceof String n && !n.isBlank()) nodeLabel.putIfAbsent(node.identity(), n);
                if (fields.get("path") instanceof String p && !p.isBlank()) {
                    nodePath.putIfAbsent(node.identity(), CapturedFileIndex.normalizePath(p));
                }
            }
        }
    }

    private static Map<String, Object> readSmall(GraphEvidence ev) {
        if (ev.canonicalJson().length() > 131_072) return Map.of();
        try {
            return GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(131_072, 32, 8_192)));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    // ─── WP4.1: CausalRepair Dual-Slicing Context & AST Skeleton Tree Assembly ───

    public static final int DEFAULT_DYNAMIC_SLICE_TIMEOUT_SECONDS = 300;
    public static final String GAP_DYNAMIC_SLICE_UNAVAILABLE = "DYNAMIC_SLICE_TIMEOUT_OR_UNAVAILABLE";
    private static final VersionedIdentifier DUAL_SLICE_PROVIDER =
            new VersionedIdentifier("repair.dual-slicer", "wp4.1-v1");
    private static final VersionedIdentifier DUAL_SLICE_GAP_CATALOG =
            new VersionedIdentifier("repair.dual-slice-gaps", "1");

    public enum DualSliceMode {
        ISOLATED_DYNAMIC_DDG,
        STATIC_SAFE_FALLBACK
    }

    /**
     * Execution trace collected strictly inside Tier-1 {@code DOCKER_HARDENED_CONTAINER} ({@code I1}).
     * When {@code timedOut} or {@code failureReason} is non-empty, {@link #buildDualSliceContext}
     * MUST emit an explicit {@link CapabilityGapRecord} ({@value #GAP_DYNAMIC_SLICE_UNAVAILABLE}, {@code I3})
     * and fall back deterministically to passive static slicing.
     */
    public record DynamicSliceTrace(
            boolean available,
            boolean timedOut,
            int timeoutSeconds,
            String failureReason,
            Map<String, Set<Integer>> executedLinesByPath) {
        public DynamicSliceTrace {
            timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_DYNAMIC_SLICE_TIMEOUT_SECONDS : timeoutSeconds;
            failureReason = failureReason == null ? "" : failureReason.strip();
            var norm = new TreeMap<String, Set<Integer>>();
            if (executedLinesByPath != null) {
                for (var e : executedLinesByPath.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) continue;
                    var sorted = new TreeSet<Integer>();
                    for (Integer l : e.getValue()) {
                        if (l != null && l >= 1) sorted.add(l);
                    }
                    if (!sorted.isEmpty()) {
                        norm.put(CapturedFileIndex.normalizePath(e.getKey()), Collections.unmodifiableSet(sorted));
                    }
                }
            }
            executedLinesByPath = Collections.unmodifiableMap(norm);
        }

        public static DynamicSliceTrace timedOut(int timeoutSeconds, String detail) {
            return new DynamicSliceTrace(false, true, timeoutSeconds,
                    detail == null || detail.isBlank() ? "Isolated dynamic slicing exceeded " + timeoutSeconds + "s cap" : detail,
                    Map.of());
        }

        public static DynamicSliceTrace unavailable(String reason) {
            return new DynamicSliceTrace(false, false, DEFAULT_DYNAMIC_SLICE_TIMEOUT_SECONDS,
                    reason == null || reason.isBlank() ? "Isolated dynamic trace unavailable" : reason,
                    Map.of());
        }

        public static DynamicSliceTrace captured(Map<String, Set<Integer>> linesByPath) {
            return new DynamicSliceTrace(true, false, DEFAULT_DYNAMIC_SLICE_TIMEOUT_SECONDS, "", linesByPath);
        }
    }

    /**
     * Minimal causal context combining Phase 1a Test-Side SDG slice, Phase 1b Dual-Mode source slice,
     * and Phase 1c AST Skeleton Trees (File Path Header + preserved Javadoc + {@code Line N:}),
     * combined with WP2.4 SALT {@code Signature(n)} compression for out-of-slice callees.
     */
    public record DualSliceContext(
            DualSliceMode mode,
            Optional<JavaMethodSlicer.TestSliceResult> testSlice,
            List<JavaMethodSlicer.AstSkeletonResult> sourceSkeletons,
            CompressedContext compressedCallees,
            List<CapabilityGapRecord> capabilityGaps,
            int originalLineCount,
            int retainedLineCount,
            double lineReductionPercent,
            String renderedPrompt,
            ContentDigest digest) {
        public DualSliceContext {
            mode = mode == null ? DualSliceMode.STATIC_SAFE_FALLBACK : mode;
            testSlice = testSlice == null ? Optional.empty() : testSlice;
            sourceSkeletons = List.copyOf(sourceSkeletons == null ? List.of() : sourceSkeletons);
            compressedCallees = compressedCallees == null ? CompressedContext.EMPTY : compressedCallees;
            capabilityGaps = List.copyOf(capabilityGaps == null ? List.of() : capabilityGaps);
            renderedPrompt = renderedPrompt == null ? "" : renderedPrompt;
            digest = digest == null ? ContentDigest.sha256Utf8(renderedPrompt) : digest;
        }
    }

    /**
     * Builds a CausalRepair Dual-Slicing context from the captured {@link KnowledgeGraph}.
     * Zero host execution ({@code I1}); deterministic ordering ({@code I2}); explicit gap on dynamic
     * trace timeout/unavailability ({@code I3}); secret-redacted output ({@code I4}).
     */
    public static DualSliceContext buildDualSliceContext(
            KnowledgeGraph graph,
            String context,
            String failingTestPath,
            int failingTestLine,
            Map<String, Set<Integer>> sourceSeedLinesByPath,
            Optional<DynamicSliceTrace> isolatedDynamicTrace,
            CompressedContext compressedCallees) {
        Objects.requireNonNull(graph, "graph");
        var fileIndex = CapturedFileIndex.forGraph(graph);
        var gaps = new ArrayList<CapabilityGapRecord>();

        // Build SKG-backed helper resolver for cross-file test helpers (strictly passive --build=off)
        JavaMethodSlicer.TestHelperResolver skgHelperResolver = (callerPath, receiverHint, methodName, arity) -> {
            if (methodName == null || methodName.isBlank()) return Optional.empty();
            String recvSimple = receiverHint == null ? "" : receiverHint.strip();
            if (recvSimple.contains(".")) recvSimple = recvSimple.substring(recvSimple.lastIndexOf('.') + 1);
            for (var entry : fileIndex.entries()) {
                String p = CapturedFileIndex.normalizePath(entry.path());
                if (!p.endsWith(".java") || p.equals(CapturedFileIndex.normalizePath(callerPath))) continue;
                if (!recvSimple.isEmpty()) {
                    String fname = p.substring(p.lastIndexOf('/') + 1);
                    if (!fname.equalsIgnoreCase(recvSimple + ".java")
                            && !p.toLowerCase(Locale.ROOT).contains(recvSimple.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                }
                var txtOpt = fileIndex.readText(graph, entry);
                if (txtOpt.isEmpty()) continue;
                String txt = txtOpt.orElseThrow();
                if (txt.contains(methodName + "(")) {
                    int line = findMethodDeclarationLine(txt, methodName);
                    if (line > 0) {
                        return Optional.of(new JavaMethodSlicer.ResolvedHelperSource(p, txt, methodName, line));
                    }
                }
            }
            return Optional.empty();
        };

        // Phase 1a: Test-side SDG static slice
        Optional<JavaMethodSlicer.TestSliceResult> testSliceOpt = Optional.empty();
        if (failingTestPath != null && !failingTestPath.isBlank() && failingTestLine >= 1) {
            String normTestPath = CapturedFileIndex.normalizePath(failingTestPath);
            var testEntryOpt = fileIndex.findExact(normTestPath);
            if (testEntryOpt.isPresent()) {
                var testTextOpt = fileIndex.readText(graph, testEntryOpt.orElseThrow());
                if (testTextOpt.isPresent()) {
                    testSliceOpt = Optional.of(JavaMethodSlicer.sliceFailingTest(
                            normTestPath, testTextOpt.orElseThrow(), failingTestLine, skgHelperResolver));
                }
            }
        }

        // Phase 1b: Source-side Dual Mode (Isolated Dynamic DDG vs Safe Static Fallback)
        DualSliceMode mode = DualSliceMode.STATIC_SAFE_FALLBACK;
        Map<String, Set<Integer>> dynamicLines = Map.of();
        if (isolatedDynamicTrace != null && isolatedDynamicTrace.isPresent()) {
            DynamicSliceTrace trace = isolatedDynamicTrace.orElseThrow();
            if (trace.available() && !trace.timedOut() && !trace.executedLinesByPath().isEmpty()) {
                mode = DualSliceMode.ISOLATED_DYNAMIC_DDG;
                dynamicLines = trace.executedLinesByPath();
            } else {
                String msg = trace.timedOut()
                        ? "Isolated dynamic DDG slicing timed out after " + trace.timeoutSeconds() + "s: "
                        + trace.failureReason() + ". Fell back deterministically to passive static slicing."
                        : "Isolated dynamic DDG trace unavailable (" + trace.failureReason()
                        + "). Fell back deterministically to passive static slicing.";
                var subject = new EvidenceSubject(EvidenceSubject.Kind.REPOSITORY, graph.snapshot().value());
                var obs = ProviderObservationReference.create(
                        DUAL_SLICE_PROVIDER, "repair.dynamic-slice",
                        ContentDigest.sha256Utf8(msg),
                        ContentDigest.sha256Utf8(GAP_DYNAMIC_SLICE_UNAVAILABLE + "|" + msg));
                var req = new EvidenceRequirement(
                        EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT, "repair.dynamic-slice-trace",
                        List.of(subject), EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,
                        List.of(msg));
                gaps.add(CapabilityGapRecord.create(
                        DUAL_SLICE_GAP_CATALOG,
                        new EvidenceContext(graph.snapshot(), Optional.empty()),
                        DUAL_SLICE_PROVIDER,
                        "repair.dynamic-slice",
                        GAP_DYNAMIC_SLICE_UNAVAILABLE,
                        subject,
                        List.of(),
                        List.of(obs),
                        List.of(req),
                        List.of(),
                        List.of(new AffectedOutput(AffectedOutput.Kind.POLICY, "repair.dual-slice")),
                        List.of(),
                        List.of(),
                        List.of(msg)));
            }
        }

        // Collect all candidate source paths deterministically
        var candidateSourceLines = new TreeMap<String, Set<Integer>>();
        if (mode == DualSliceMode.ISOLATED_DYNAMIC_DDG) {
            for (var e : dynamicLines.entrySet()) {
                candidateSourceLines.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).addAll(e.getValue());
            }
        }
        if (sourceSeedLinesByPath != null) {
            for (var e : sourceSeedLinesByPath.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) continue;
                String normPath = CapturedFileIndex.normalizePath(e.getKey());
                var entryOpt = fileIndex.findExact(normPath);
                if (entryOpt.isEmpty()) continue;
                var txtOpt = fileIndex.readText(graph, entryOpt.orElseThrow());
                if (txtOpt.isEmpty()) continue;
                String src = txtOpt.orElseThrow();
                var merged = candidateSourceLines.computeIfAbsent(normPath, k -> new TreeSet<>());
                if (e.getValue() != null) {
                    for (Integer seedLine : e.getValue()) {
                        if (seedLine == null || seedLine < 1) continue;
                        MethodSliceResult staticSlice = JavaMethodSlicer.slice(
                                normPath, src, seedLine, MethodSliceResult.SliceDirection.BACKWARD);
                        if (!staticSlice.sliceLines().isEmpty()) {
                            merged.addAll(staticSlice.sliceLines());
                        } else {
                            merged.add(seedLine);
                        }
                    }
                }
            }
        }

        // Phase 1c: Reconstruct AST Skeleton Trees with 3 Semantic Anchors
        var skeletons = new ArrayList<JavaMethodSlicer.AstSkeletonResult>();
        int origLines = 0;
        int keptLines = 0;
        for (var e : candidateSourceLines.entrySet()) {
            String normPath = e.getKey();
            var entryOpt = fileIndex.findExact(normPath);
            if (entryOpt.isEmpty()) continue;
            var txtOpt = fileIndex.readText(graph, entryOpt.orElseThrow());
            if (txtOpt.isEmpty()) continue;
            String src = txtOpt.orElseThrow();
            if (LlmTransport.containsSecretForPath(normPath, src)) continue;
            var skel = JavaMethodSlicer.buildAstSkeletonTree(normPath, src, e.getValue(), true);
            skeletons.add(skel);
            origLines += skel.originalLineCount();
            keptLines += skel.retainedLineCount();
        }
        if (testSliceOpt.isPresent()) {
            var ts = testSliceOpt.orElseThrow().skeleton();
            origLines += ts.originalLineCount();
            keptLines += ts.retainedLineCount();
        }

        // Assemble secret-redacted deterministic prompt
        var sb = new StringBuilder();
        sb.append("[DUAL-SLICE CAUSAL CONTEXT | mode=").append(mode.name()).append("]\n");
        if (testSliceOpt.isPresent()) {
            var ts = testSliceOpt.orElseThrow();
            sb.append("## Test-Side SDG Slice (C_test = S_intra U E)\n");
            sb.append(LlmTransport.redactSecrets(ts.skeleton().renderedSkeleton()).text()).append('\n');
            for (var ext : ts.externalDependencies()) {
                if ("SKG_EXTERNAL_HELPER".equals(ext.kind())) {
                    sb.append("// External Helper (depth=").append(ext.depth()).append(") ")
                            .append(ext.path()).append(':').append(ext.startLine()).append('\n')
                            .append(LlmTransport.redactSecrets(ext.code()).text()).append('\n');
                }
            }
        }
        if (!skeletons.isEmpty()) {
            sb.append("## Source-Side AST Skeleton Tree (C_source)\n");
            for (var skel : skeletons) {
                sb.append(LlmTransport.redactSecrets(skel.renderedSkeleton()).text()).append('\n');
            }
        }
        CompressedContext safeCallees = compressedCallees == null ? CompressedContext.EMPTY : compressedCallees;
        if (!safeCallees.contexts().isEmpty()) {
            sb.append("## Compressed Out-of-Slice Callees (SALT Signature(n))\n");
            for (var cc : safeCallees.contexts()) {
                sb.append(LlmTransport.redactSecrets(cc.renderedContent()).text()).append('\n');
            }
        }
        String rendered = sb.toString();
        double reductionPct = origLines > 0
                ? Math.round(10000.0 * (origLines - keptLines) / origLines) / 100.0
                : 0.0;
        return new DualSliceContext(
                mode,
                testSliceOpt,
                skeletons,
                safeCallees,
                gaps,
                origLines,
                keptLines,
                Math.max(0.0, reductionPct),
                rendered,
                ContentDigest.sha256Utf8(rendered));
    }

    private static int findMethodDeclarationLine(String source, String methodName) {
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int idx = line.indexOf(methodName + "(");
            if (idx > 0 && !line.stripLeading().startsWith("//") && !line.stripLeading().startsWith("*")) {
                String before = line.substring(0, idx).strip();
                if (!before.isEmpty() && !before.endsWith(".") && !before.endsWith("new")
                        && !before.endsWith("return") && !before.endsWith("=")) {
                    return i + 1;
                }
            }
        }
        return -1;
    }
}
