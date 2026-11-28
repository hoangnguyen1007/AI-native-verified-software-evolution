package com.evolution.backend.query.repair;

import com.evolution.analysis.assessment.v2.LeidenCommunityDetector;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.analysis.query.ArchitectureQueryService;
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

    public enum FileRole { CITED, CONTEXT_EXPANDED, SIBLING_TEST, LINKED_DOC }

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

    public record Plan(
            List<PlannedFile> files,
            List<String> allowedCreateDirectories,
            List<String> callerSymbols,
            List<LinkedDocSection> linkedDocs,
            List<AdvisoryContextBadge> advisoryBadges,
            List<LeidenContextBadge> leidenBadges) {
        public Plan(List<PlannedFile> files, List<String> allowedCreateDirectories, List<String> callerSymbols) {
            this(files, allowedCreateDirectories, callerSymbols, List.of(), List.of(), List.of());
        }

        public Plan(List<PlannedFile> files, List<String> allowedCreateDirectories, List<String> callerSymbols,
                    List<LinkedDocSection> linkedDocs) {
            this(files, allowedCreateDirectories, callerSymbols, linkedDocs, List.of(), List.of());
        }

        public Plan {
            files = List.copyOf(files);
            allowedCreateDirectories = List.copyOf(allowedCreateDirectories);
            callerSymbols = List.copyOf(callerSymbols);
            linkedDocs = List.copyOf(linkedDocs == null ? List.of() : linkedDocs);
            advisoryBadges = List.copyOf(advisoryBadges == null ? List.of() : advisoryBadges);
            leidenBadges = List.copyOf(leidenBadges == null ? List.of() : leidenBadges);
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
        return plan(graph, primaryContext, secondaryContext, citedPaths, bundlePath, targetEntityId, stop, null);
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
        var planned = new LinkedHashMap<String, PlannedFile>();
        var dirs = new LinkedHashSet<String>();
        var callerSymbols = new LinkedHashSet<String>();

        for (String p : citedPaths) {
            if (p != null && !p.isBlank()) {
                String norm = CapturedFileIndex.normalizePath(p);
                planned.putIfAbsent(norm, new PlannedFile(norm, FileRole.CITED, "Cited by finding diagnosis"));
                addParentDir(dirs, norm);
            }
        }
        if (stop != null && stop.get() != StopReason.NONE) {
            return new Plan(List.copyOf(planned.values()), List.copyOf(dirs), List.of());
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

        return new Plan(List.copyOf(planned.values()), List.copyOf(dirs), List.copyOf(callerSymbols),
                linkedDocs, advisoryBadges, leidenBadges);
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
}
