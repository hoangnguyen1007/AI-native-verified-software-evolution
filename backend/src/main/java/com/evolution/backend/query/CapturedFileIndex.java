package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.backend.query.llm.LlmTransport;
import com.evolution.backend.query.search.CapturedSearchIndex;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Per-graph cached index of all captured repository files across both {@code source.bytes}
 * and {@code polyglot.inventory-entry} evidence, with CAS fallback verification.
 */
public final class CapturedFileIndex {
    static final int MAX_LINES_PER_READ = 250;
    private static final int MAX_CACHED_GRAPHS = 16;
    private static final long MAX_SEARCH_FILE_BYTES = CapturedSearchIndex.MAX_INDEXABLE_FILE_BYTES;
    private static final int MAX_MATCH_LINE_CHARS = 300;
    private static final int MAX_CODE_SEARCH_SNIPPET_CHARS = 600;
    private static final GraphJson.Limits EVIDENCE_JSON = new GraphJson.Limits(4 * 1024 * 1024, 32, 32_768);
    private static final Pattern ATX_HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE_OPEN = Pattern.compile("^\\s{0,3}(`{3,}|~{3,})");

    private static final Map<ContentDigest, CapturedFileIndex> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHED_GRAPHS + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ContentDigest, CapturedFileIndex> eldest) {
                    return size() > MAX_CACHED_GRAPHS;
                }
            });

    public record Entry(String path, String language, long size, Optional<ContentDigest> digest,
                 ContentDigest evidenceId, String sourceKind, boolean readable) {
        Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("path", path);
            map.put("language", language);
            map.put("size", size);
            map.put("sourceKind", sourceKind);
            map.put("digest", digest.map(ContentDigest::value).orElse(null));
            map.put("readable", readable);
            return Collections.unmodifiableMap(map);
        }
    }

    record ListResult(int totalFiles, int matchedFiles, int offset, int limit, boolean truncated,
                      Map<String, Integer> languages, List<Entry> files) {}

    public record ReadResult(boolean found, boolean ambiguous, String path, String language,
                      Optional<ContentDigest> digest, Optional<ContentDigest> evidenceId,
                      int startLine, int endLine, int totalLines, boolean truncated,
                      String content, String numberedSource, List<String> candidates,
                      int redactedSecretsCount) {
        ReadResult(boolean found, boolean ambiguous, String path, String language,
                   Optional<ContentDigest> digest, Optional<ContentDigest> evidenceId,
                   int startLine, int endLine, int totalLines, boolean truncated,
                   String content, String numberedSource, List<String> candidates) {
            this(found, ambiguous, path, language, digest, evidenceId, startLine, endLine, totalLines,
                    truncated, content, numberedSource, candidates, 0);
        }
    }

    public record MarkdownHeading(int level, String title, String anchor, int startLine, int endLine) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("level", level);
            map.put("title", title);
            map.put("anchor", anchor);
            map.put("startLine", startLine);
            map.put("endLine", endLine);
            return Collections.unmodifiableMap(map);
        }
    }

    public record MarkdownHeadingsResult(boolean found, boolean ambiguous, String path,
                                         Optional<ContentDigest> digest, Optional<ContentDigest> evidenceId,
                                         List<MarkdownHeading> headings, List<String> candidates) {}

    public record MarkdownSectionResult(boolean found, boolean ambiguous, String path,
                                        Optional<ContentDigest> digest, Optional<ContentDigest> evidenceId,
                                        String heading, int level, int startLine, int endLine, int totalLines,
                                        boolean truncated, String content, String numberedSource,
                                        int redactedSecretsCount, List<String> candidates) {}

    record SearchMatch(String path, String language, int line, String text,
                       Optional<ContentDigest> digest, ContentDigest evidenceId) {
        Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("path", path);
            map.put("language", language);
            map.put("line", line);
            map.put("text", text);
            return Collections.unmodifiableMap(map);
        }
    }

    record SearchResult(String query, boolean regex, int scannedFiles, int matchedFiles, boolean truncated,
                        List<SearchMatch> matches, List<ArchitectureChatModel.SourceUse> readSources,
                        String engine, int omittedFiles, int redactedSecretCount) {
        SearchResult(String query, boolean regex, int scannedFiles, int matchedFiles, boolean truncated,
                     List<SearchMatch> matches, List<ArchitectureChatModel.SourceUse> readSources) {
            this(query, regex, scannedFiles, matchedFiles, truncated, matches, readSources,
                    CapturedSearchIndex.ENGINE_STREAMING_FALLBACK, 0, 0);
        }
    }

    private final Map<String, Entry> byPath;
    private final List<Entry> sortedEntries;
    private final Map<String, Integer> languageCounts;
    private final Map<ContentDigest, ContentDigest> contentEvidenceByDigest;

    private CapturedFileIndex(Map<String, Entry> byPath, Map<ContentDigest, ContentDigest> contentEvidenceByDigest) {
        this.byPath = Collections.unmodifiableMap(byPath);
        this.sortedEntries = List.copyOf(byPath.values());
        this.contentEvidenceByDigest = Collections.unmodifiableMap(contentEvidenceByDigest);
        var langs = new TreeMap<String, Integer>();
        for (var e : sortedEntries) {
            langs.merge(e.language(), 1, Integer::sum);
        }
        this.languageCounts = Collections.unmodifiableMap(langs);
    }

    public static CapturedFileIndex forGraph(KnowledgeGraph graph) {
        var cached = CACHE.get(graph.identity());
        if (cached != null) return cached;
        var built = build(graph);
        CACHE.put(graph.identity(), built);
        return built;
    }

    private static CapturedFileIndex build(KnowledgeGraph graph) {
        var byPath = new TreeMap<String, Entry>();
        var contentByDigest = new HashMap<ContentDigest, ContentDigest>();

        for (var ev : graph.evidence()) {
            if ("source.content".equals(ev.family())) {
                try {
                    var row = readJson(ev);
                    if (row.get("contentDigest") instanceof String d) {
                        contentByDigest.putIfAbsent(new ContentDigest(d), ev.identity());
                    }
                } catch (RuntimeException ignored) {}
            }
        }

        // Pass 1: source.bytes (highest fidelity for captured Java/source files)
        for (var ev : graph.evidence()) {
            if (!"source.bytes".equals(ev.family())) continue;
            try {
                var row = readJson(ev);
                if (!(row.get("document") instanceof Map<?, ?> docObj)) continue;
                if (!(docObj.get("path") instanceof String rawPath) || rawPath.isBlank()) continue;
                String path = normalizePath(rawPath);
                Optional<ContentDigest> digest = docObj.get("contentDigest") instanceof String d
                        ? Optional.of(new ContentDigest(d))
                        : (docObj.get("digest") instanceof String d2
                                ? Optional.of(new ContentDigest(d2))
                                : (row.get("digest") instanceof String d3
                                        ? Optional.of(new ContentDigest(d3)) : Optional.empty()));
                long size = estimateSize(row);
                String language = inferLanguage(path, List.of());
                boolean readable = !isKnownBinary(path) && size <= MAX_SEARCH_FILE_BYTES;
                byPath.put(path, new Entry(path, language, size, digest, ev.identity(), "source.bytes", readable));
            } catch (RuntimeException ignored) {}
        }

        // Pass 2: polyglot.inventory-entry (all polyglot source, config, proto, Dockerfile, YAML, README, tests)
        for (var ev : graph.evidence()) {
            if (!"polyglot.inventory-entry".equals(ev.family())) continue;
            try {
                var row = readJson(ev);
                if (!(row.get("fields") instanceof Map<?, ?> fieldsObj)) continue;
                if (!"FILE".equals(fieldsObj.get("kind")) || !"ACQUIRED".equals(fieldsObj.get("disposition"))) continue;
                if (!(fieldsObj.get("rawPath") instanceof String rawB64)) continue;
                var decodedPath = new LogicalPath(rawB64).utf8();
                if (decodedPath.isEmpty()) continue;
                String path = normalizePath(decodedPath.orElseThrow());
                if (path.isEmpty() || path.equals(".git") || path.startsWith(".git/")) continue;

                Optional<ContentDigest> digest = Optional.empty();
                long size = -1L;
                if (fieldsObj.get("content") instanceof Map<?, ?> contentObj) {
                    if (contentObj.get("digest") instanceof String d) digest = Optional.of(new ContentDigest(d));
                    if (contentObj.get("size") instanceof Number n) size = n.longValue();
                }
                var candidates = new ArrayList<String>();
                if (fieldsObj.get("languageCandidates") instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof String s && !s.isBlank()) candidates.add(s);
                    }
                }
                String language = inferLanguage(path, candidates);
                Entry existing = byPath.get(path);
                if (existing == null) {
                    boolean readable = digest.isPresent() && size <= RepairSource.MAX_FILE_BYTES && !isKnownBinary(path);
                    byPath.put(path, new Entry(path, language, Math.max(0L, size), digest, ev.identity(),
                            "polyglot.inventory-entry", readable));
                } else if (existing.size() <= 0 && size > 0) {
                    var resolvedDigest = digest;
                    byPath.put(path, new Entry(existing.path(), existing.language(), size,
                            existing.digest().or(() -> resolvedDigest), existing.evidenceId(), existing.sourceKind(), existing.readable()));
                }
            } catch (RuntimeException ignored) {}
        }

        // Pass 3: source.document fallback for graphs that have documents without bytes/polyglot entries
        for (var ev : graph.evidence()) {
            if (!"source.document".equals(ev.family())) continue;
            try {
                var row = readJson(ev);
                if (!(row.get("path") instanceof String rawPath) || rawPath.isBlank()) continue;
                String path = normalizePath(rawPath);
                if (byPath.containsKey(path)) continue;
                Optional<ContentDigest> digest = row.get("contentDigest") instanceof String d
                        ? Optional.of(new ContentDigest(d)) : Optional.empty();
                byPath.put(path, new Entry(path, inferLanguage(path, List.of()), 0L, digest, ev.identity(),
                        "source.document", digest.isPresent()));
            } catch (RuntimeException ignored) {}
        }

        return new CapturedFileIndex(byPath, contentByDigest);
    }

    public List<Entry> entries() {
        return sortedEntries;
    }

    public Optional<Entry> findExact(String path) {
        return Optional.ofNullable(byPath.get(normalizePath(path)));
    }

    ListResult list(String query, String language, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(500, limit));
        String langFilter = language == null ? "" : language.strip().toLowerCase(Locale.ROOT);
        String q = query == null ? "" : query.strip();
        Pattern glob = (!q.isEmpty() && (q.indexOf('*') >= 0 || q.indexOf('?') >= 0)) ? compileGlob(q) : null;
        String qLower = q.toLowerCase(Locale.ROOT);

        var matched = new ArrayList<Entry>();
        var matchedLangs = new TreeMap<String, Integer>();
        for (var entry : sortedEntries) {
            if (!langFilter.isEmpty() && !entry.language().equalsIgnoreCase(langFilter)) continue;
            if (!q.isEmpty()) {
                boolean hit = glob != null
                        ? glob.matcher(entry.path()).matches()
                        : entry.path().toLowerCase(Locale.ROOT).contains(qLower);
                if (!hit) continue;
            }
            matched.add(entry);
            matchedLangs.merge(entry.language(), 1, Integer::sum);
        }
        int start = Math.min(safeOffset, matched.size());
        int end = Math.min(matched.size(), start + safeLimit);
        var page = matched.subList(start, end);
        boolean truncated = end < matched.size();
        return new ListResult(sortedEntries.size(), matched.size(), safeOffset, safeLimit, truncated,
                q.isEmpty() && langFilter.isEmpty() ? languageCounts : Collections.unmodifiableMap(matchedLangs),
                List.copyOf(page));
    }

    public ReadResult read(KnowledgeGraph graph, String rawPath, int requestedStartLine, int requestedEndLine) {
        String target = normalizePath(rawPath);
        if (target.isEmpty()) {
            return new ReadResult(false, false, "", "unknown", Optional.empty(), Optional.empty(),
                    0, 0, 0, false, "", "", List.of());
        }
        Entry exact = byPath.get(target);
        Entry matched = exact;
        if (matched == null) {
            var suffixMatches = new ArrayList<Entry>();
            String suffix = "/" + target;
            for (var entry : sortedEntries) {
                if (entry.path().equalsIgnoreCase(target) || entry.path().endsWith(suffix)) {
                    suffixMatches.add(entry);
                }
            }
            if (suffixMatches.size() > 1) {
                return new ReadResult(false, true, target, inferLanguage(target, List.of()), Optional.empty(),
                        Optional.empty(), 0, 0, 0, false, "", "",
                        suffixMatches.stream().map(Entry::path).limit(15).toList());
            }
            if (suffixMatches.size() == 1) {
                matched = suffixMatches.getFirst();
            }
        }
        if (matched == null) {
            return new ReadResult(false, false, target, inferLanguage(target, List.of()), Optional.empty(),
                    Optional.empty(), 0, 0, 0, false, "", "", List.of());
        }
        var textOpt = readText(graph, matched);
        if (textOpt.isEmpty()) {
            return new ReadResult(false, false, matched.path(), matched.language(), matched.digest(),
                    Optional.of(matched.evidenceId()), 0, 0, 0, false, "", "", List.of());
        }
        String text = textOpt.orElseThrow();
        var doc = LlmTransport.redactDocumentForPath(matched.path(), text);
        int totalLines = doc.lineCount();
        int start = Math.max(1, Math.min(requestedStartLine, Math.max(1, totalLines)));
        int reqEnd = requestedEndLine < start ? start + MAX_LINES_PER_READ - 1 : requestedEndLine;
        int end = Math.min(totalLines, Math.min(reqEnd, start + MAX_LINES_PER_READ - 1));
        var slice = buildRedactedSlice(doc, start, end);
        boolean truncated = end < totalLines || start > 1;
        return new ReadResult(true, false, matched.path(), matched.language(), matched.digest(),
                Optional.of(matched.evidenceId()), start, end, totalLines, truncated,
                slice.content(), slice.numberedSource(), List.of(), slice.redactedCount());
    }

    public MarkdownHeadingsResult listMarkdownHeadings(KnowledgeGraph graph, String rawPath) {
        var resolved = resolveEntry(rawPath);
        if (resolved.ambiguous()) {
            return new MarkdownHeadingsResult(false, true, resolved.target(), Optional.empty(), Optional.empty(),
                    List.of(), resolved.candidates());
        }
        if (resolved.entry() == null) {
            return new MarkdownHeadingsResult(false, false, resolved.target(), Optional.empty(), Optional.empty(),
                    List.of(), List.of());
        }
        Entry matched = resolved.entry();
        var textOpt = readText(graph, matched);
        if (textOpt.isEmpty()) {
            return new MarkdownHeadingsResult(false, false, matched.path(), matched.digest(),
                    Optional.of(matched.evidenceId()), List.of(), List.of());
        }
        var doc = LlmTransport.redactDocumentForPath(matched.path(), textOpt.orElseThrow());
        var headings = parseMarkdownHeadings(doc.lines().toArray(String[]::new));
        return new MarkdownHeadingsResult(true, false, matched.path(), matched.digest(),
                Optional.of(matched.evidenceId()), headings, List.of());
    }

    public MarkdownSectionResult readMarkdownSection(KnowledgeGraph graph, String rawPath, String headingQuery) {
        var resolved = resolveEntry(rawPath);
        if (resolved.ambiguous()) {
            return new MarkdownSectionResult(false, true, resolved.target(), Optional.empty(), Optional.empty(),
                    "", 0, 0, 0, 0, false, "", "", 0, resolved.candidates());
        }
        if (resolved.entry() == null) {
            return new MarkdownSectionResult(false, false, resolved.target(), Optional.empty(), Optional.empty(),
                    "", 0, 0, 0, 0, false, "", "", 0, List.of());
        }
        Entry matched = resolved.entry();
        var textOpt = readText(graph, matched);
        if (textOpt.isEmpty()) {
            return new MarkdownSectionResult(false, false, matched.path(), matched.digest(),
                    Optional.of(matched.evidenceId()), "", 0, 0, 0, 0, false, "", "", 0, List.of());
        }
        String rawText = textOpt.orElseThrow();
        var doc = LlmTransport.redactDocumentForPath(matched.path(), rawText);
        var headings = parseMarkdownHeadings(doc.lines().toArray(String[]::new));
        String q = headingQuery == null ? "" : headingQuery.strip();
        if (q.isEmpty()) {
            int end = Math.min(doc.lineCount(), MAX_LINES_PER_READ);
            var slice = buildRedactedSlice(doc, 1, Math.max(1, end));
            return new MarkdownSectionResult(true, false, matched.path(), matched.digest(),
                    Optional.of(matched.evidenceId()), "", 0, 1, Math.max(1, end), doc.lineCount(),
                    end < doc.lineCount(), slice.content(), slice.numberedSource(), slice.redactedCount(), List.of());
        }
        String qNorm = q.replaceFirst("^#+\\s*", "").strip();
        String qSlug = slugifyHeading(qNorm);
        List<MarkdownHeading> exact = headings.stream()
                .filter(h -> h.title().equalsIgnoreCase(qNorm) || h.anchor().equalsIgnoreCase(qSlug))
                .toList();
        List<MarkdownHeading> hits = !exact.isEmpty() ? exact
                : headings.stream()
                        .filter(h -> h.title().toLowerCase(Locale.ROOT).contains(qNorm.toLowerCase(Locale.ROOT))
                                || (!qSlug.isEmpty() && h.anchor().contains(qSlug)))
                        .toList();
        if (hits.isEmpty()) {
            return new MarkdownSectionResult(false, false, matched.path(), matched.digest(),
                    Optional.of(matched.evidenceId()), qNorm, 0, 0, 0, doc.lineCount(), false, "", "", 0,
                    headings.stream().map(MarkdownHeading::title).limit(20).toList());
        }
        if (hits.size() > 1) {
            return new MarkdownSectionResult(false, true, matched.path(), matched.digest(),
                    Optional.of(matched.evidenceId()), qNorm, 0, 0, 0, doc.lineCount(), false, "", "", 0,
                    hits.stream().map(h -> "L" + h.startLine() + ": " + "#".repeat(h.level()) + " " + h.title())
                            .limit(20).toList());
        }
        MarkdownHeading targetHeading = hits.getFirst();
        int start = targetHeading.startLine();
        int end = Math.min(targetHeading.endLine(), start + MAX_LINES_PER_READ - 1);
        var slice = buildRedactedSlice(doc, start, end);
        boolean truncated = end < targetHeading.endLine();
        return new MarkdownSectionResult(true, false, matched.path(), matched.digest(),
                Optional.of(matched.evidenceId()), targetHeading.title(), targetHeading.level(),
                start, end, doc.lineCount(), truncated, slice.content(), slice.numberedSource(),
                slice.redactedCount(), List.of());
    }

    private record ResolvedEntry(String target, Entry entry, boolean ambiguous, List<String> candidates) {}

    private ResolvedEntry resolveEntry(String rawPath) {
        String target = normalizePath(rawPath);
        if (target.isEmpty()) {
            return new ResolvedEntry("", null, false, List.of());
        }
        Entry exact = byPath.get(target);
        if (exact != null) {
            return new ResolvedEntry(target, exact, false, List.of());
        }
        var suffixMatches = new ArrayList<Entry>();
        String suffix = "/" + target;
        for (var entry : sortedEntries) {
            if (entry.path().equalsIgnoreCase(target) || entry.path().endsWith(suffix)) {
                suffixMatches.add(entry);
            }
        }
        if (suffixMatches.size() > 1) {
            return new ResolvedEntry(target, null, true,
                    suffixMatches.stream().map(Entry::path).limit(15).toList());
        }
        if (suffixMatches.size() == 1) {
            return new ResolvedEntry(target, suffixMatches.getFirst(), false, List.of());
        }
        return new ResolvedEntry(target, null, false, List.of());
    }

    public static List<MarkdownHeading> parseMarkdownHeadings(String[] lines) {
        record RawHeading(int level, String title, String anchor, int startLine) {}
        var raw = new ArrayList<RawHeading>();
        var anchorCounts = new HashMap<String, Integer>();
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLen = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fm = FENCE_OPEN.matcher(line);
            if (fm.find()) {
                String token = fm.group(1);
                if (!inFence) {
                    inFence = true;
                    fenceChar = token.charAt(0);
                    fenceLen = token.length();
                } else {
                    final char expectedFence = fenceChar;
                    if (token.charAt(0) == expectedFence && token.length() >= fenceLen
                            && line.strip().chars().allMatch(c -> c == expectedFence)) {
                        inFence = false;
                    }
                }
                continue;
            }
            if (inFence) continue;
            Matcher hm = ATX_HEADING.matcher(line);
            if (hm.matches()) {
                int level = hm.group(1).length();
                String title = LlmTransport.redactSecrets(hm.group(2).strip()).text();
                String baseSlug = slugifyHeading(title);
                int seen = anchorCounts.merge(baseSlug, 1, Integer::sum);
                String anchor = seen == 1 ? baseSlug : baseSlug + "-" + (seen - 1);
                raw.add(new RawHeading(level, title, anchor, i + 1));
            }
        }
        var result = new ArrayList<MarkdownHeading>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            var cur = raw.get(i);
            int endLine = lines.length;
            for (int j = i + 1; j < raw.size(); j++) {
                if (raw.get(j).level() <= cur.level()) {
                    endLine = raw.get(j).startLine() - 1;
                    break;
                }
            }
            result.add(new MarkdownHeading(cur.level(), cur.title(), cur.anchor(),
                    cur.startLine(), Math.max(cur.startLine(), endLine)));
        }
        return List.copyOf(result);
    }

    public static String slugifyHeading(String title) {
        if (title == null) return "";
        String lower = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s\\-_]", "")
                .trim()
                .replaceAll("\\s+", "-");
        return lower;
    }

    private record RedactedSlice(String content, String numberedSource, int redactedCount) {}

    private static RedactedSlice buildRedactedSlice(LlmTransport.RedactedDocument doc, int start, int end) {
        var lines = doc.lines();
        int safeStart = Math.max(1, Math.min(start, Math.max(1, lines.size())));
        int safeEnd = Math.max(safeStart, Math.min(end, lines.size()));
        var content = new StringBuilder();
        var numbered = new StringBuilder();
        for (int lineNum = safeStart; lineNum <= safeEnd; lineNum++) {
            String line = lines.get(lineNum - 1);
            if (lineNum > safeStart) content.append('\n');
            content.append(line);
            numbered.append(lineNum).append(": ").append(line).append('\n');
        }
        int redactedCount = doc.redactedCountInLineRange(safeStart, safeEnd);
        return new RedactedSlice(content.toString(), numbered.toString(), redactedCount);
    }

    public Optional<String> readText(KnowledgeGraph graph, Entry entry) {
        var bytesOpt = readBytes(graph, entry);
        if (bytesOpt.isEmpty()) return Optional.empty();
        byte[] bytes = bytesOpt.orElseThrow();
        if (containsNul(bytes)) return Optional.empty();
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (decoded.startsWith("\uFEFF")) decoded = decoded.substring(1);
            return Optional.of(decoded);
        } catch (CharacterCodingException binary) {
            return Optional.empty();
        }
    }

    public Optional<byte[]> readBytes(KnowledgeGraph graph, Entry entry) {
        if ("source.bytes".equals(entry.sourceKind())) {
            var ev = graph.evidence(entry.evidenceId());
            if (ev.isEmpty()) return Optional.empty();
            try {
                var row = readJson(ev.orElseThrow());
                return RepairSource.resolveSourceBytes(graph, row, entry.digest());
            } catch (RuntimeException invalid) {
                return Optional.empty();
            }
        }
        if (entry.digest().isEmpty()) return Optional.empty();
        var digest = entry.digest().orElseThrow();
        OptionalLong expectedSize = (entry.size() > 0 || (entry.size() == 0 && "polyglot.inventory-entry".equals(entry.sourceKind())))
                ? OptionalLong.of(entry.size()) : OptionalLong.empty();
        var fromStore = RepairSource.resolveContentFromStore(digest, expectedSize);
        if (fromStore.isPresent()) return fromStore;
        var contentEvId = contentEvidenceByDigest.get(digest);
        if (contentEvId != null) {
            var ev = graph.evidence(contentEvId);
            if (ev.isPresent()) {
                try {
                    var row = readJson(ev.orElseThrow());
                    return RepairSource.resolveSourceBytes(graph, row, Optional.of(digest));
                } catch (RuntimeException ignored) {}
            }
        }
        return Optional.empty();
    }

    SearchResult search(KnowledgeGraph graph, String query, boolean regex, String pathFilter, String languageFilter,
                        int maxMatches, Supplier<StopReason> stop) {
        return search(graph, query, regex, pathFilter, languageFilter, maxMatches, stop, null);
    }

    SearchResult search(KnowledgeGraph graph, String query, boolean regex, String pathFilter, String languageFilter,
                        int maxMatches, Supplier<StopReason> stop, CapturedSearchIndex searchIndex) {
        int cap = Math.max(1, Math.min(100, maxMatches));
        if (!regex && searchIndex != null) {
            List<String> langs = (languageFilter == null || languageFilter.isBlank())
                    ? List.of() : List.of(languageFilter.strip().toLowerCase(Locale.ROOT));
            var ftsOpt = searchIndex.codeSearch(graph, query, langs, pathFilter, true, cap, 0, stop);
            if (ftsOpt.isPresent()) {
                var fts = ftsOpt.orElseThrow();
                var matches = new ArrayList<SearchMatch>(fts.items().size());
                var matchedPaths = new LinkedHashSet<String>();
                var readSources = new LinkedHashMap<String, ArchitectureChatModel.SourceUse>();
                for (var hit : fts.items()) {
                    var entry = byPath.get(hit.path());
                    Optional<ContentDigest> digest = entry != null ? entry.digest()
                            : (hit.contentSha256() != null && hit.contentSha256().startsWith("sha256:")
                                    ? Optional.of(new ContentDigest(hit.contentSha256())) : Optional.empty());
                    ContentDigest evidenceId = entry != null ? entry.evidenceId() : graph.identity();
                    String snippet = hit.snippet().length() > MAX_MATCH_LINE_CHARS
                            ? hit.snippet().substring(0, MAX_MATCH_LINE_CHARS) + "…" : hit.snippet();
                    matches.add(new SearchMatch(hit.path(), hit.language(), hit.startLine(), snippet, digest, evidenceId));
                    matchedPaths.add(hit.path());
                    if (digest.isPresent()) {
                        readSources.putIfAbsent(hit.path(),
                                new ArchitectureChatModel.SourceUse(hit.path(), digest.orElseThrow(), false));
                    }
                }
                boolean searchTruncated = fts.truncated() || fts.totalHits() > matches.size();
                return new SearchResult(query, false, sortedEntries.size() - fts.omittedFiles(),
                        matchedPaths.size(), searchTruncated, List.copyOf(matches), List.copyOf(readSources.values()),
                        fts.engine(), fts.omittedFiles(), fts.redactedSecretCount());
            }
        }

        Pattern pattern;
        try {
            pattern = regex
                    ? Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    : Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException invalid) {
            throw new IllegalArgumentException("Invalid search regex: " + invalid.getDescription());
        }
        String langFilter = languageFilter == null ? "" : languageFilter.strip().toLowerCase(Locale.ROOT);
        String pf = pathFilter == null ? "" : pathFilter.strip();
        Pattern pathGlob = (!pf.isEmpty() && (pf.indexOf('*') >= 0 || pf.indexOf('?') >= 0)) ? compileGlob(pf) : null;
        String pfLower = pf.toLowerCase(Locale.ROOT);

        var matches = new ArrayList<SearchMatch>();
        var matchedPaths = new LinkedHashSet<String>();
        var readSources = new LinkedHashMap<String, ArchitectureChatModel.SourceUse>();
        int scanned = 0;
        int omitted = 0;
        int redactedSecrets = 0;
        boolean truncated = false;

        for (var entry : sortedEntries) {
            if (stop != null && stop.get() != StopReason.NONE) {
                truncated = true;
                break;
            }
            if (!langFilter.isEmpty() && !entry.language().equalsIgnoreCase(langFilter)) continue;
            if (!pf.isEmpty()) {
                boolean pathHit = pathGlob != null
                        ? pathGlob.matcher(entry.path()).matches()
                        : entry.path().toLowerCase(Locale.ROOT).contains(pfLower);
                if (!pathHit) continue;
            }
            if (!entry.readable() || entry.size() > MAX_SEARCH_FILE_BYTES || isKnownBinary(entry.path())) {
                omitted++;
                continue;
            }
            var textOpt = readText(graph, entry);
            if (textOpt.isEmpty()) {
                omitted++;
                continue;
            }
            scanned++;
            String text = textOpt.orElseThrow();
            // Redact multi-line blocks, config secrets, and high-entropy tokens BEFORE line splitting while keeping exact line numbers
            var doc = LlmTransport.redactDocumentForPath(entry.path(), text);
            redactedSecrets += doc.totalRedactedCount();
            List<String> lines = doc.lines();
            boolean fileMatched = false;
            for (int i = 0; i < lines.size(); i++) {
                String safeLine = lines.get(i);
                if (pattern.matcher(safeLine).find()) {
                    fileMatched = true;
                    String snippet = safeLine.length() > MAX_MATCH_LINE_CHARS
                            ? safeLine.substring(0, MAX_MATCH_LINE_CHARS) + "…" : safeLine;
                    matches.add(new SearchMatch(entry.path(), entry.language(), i + 1, snippet,
                            entry.digest(), entry.evidenceId()));
                    matchedPaths.add(entry.path());
                    if (matches.size() >= cap) {
                        truncated = true;
                        break;
                    }
                }
            }
            if (fileMatched && entry.digest().isPresent()) {
                readSources.putIfAbsent(entry.path(),
                        new ArchitectureChatModel.SourceUse(entry.path(), entry.digest().orElseThrow(), false));
            }
            if (matches.size() >= cap) break;
        }
        return new SearchResult(query, regex, scanned, matchedPaths.size(), truncated,
                List.copyOf(matches), List.copyOf(readSources.values()),
                CapturedSearchIndex.ENGINE_STREAMING_FALLBACK, omitted, redactedSecrets);
    }

    /**
     * Streaming CAS fallback for {@code POST /api/v1/graphs/{graph}/queries/code-search} when no disk
     * FTS5 index is attached or when an offline read-only index does not yet contain this snapshot.
     * Pre-redacts every file via {@link LlmTransport#redactDocumentForPath(String, String)} and tracks
     * binary/oversized/unreadable files honestly in {@code omittedFiles}.
     */
    public CapturedSearchIndex.CodeSearchResult codeSearchFallback(
            KnowledgeGraph graph,
            String rawQuery,
            List<String> languages,
            boolean includeAdvisory,
            int limit,
            int offset,
            Supplier<StopReason> stop) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        int safeOffset = Math.max(0, offset);
        List<String> terms = CapturedSearchIndex.extractSafeTerms(rawQuery);
        if (terms.isEmpty()) {
            return new CapturedSearchIndex.CodeSearchResult(
                    CapturedSearchIndex.ENGINE_STREAMING_FALLBACK, 0, false, 0, 0, safeOffset, safeLimit, List.of());
        }
        var langSet = new HashSet<String>();
        if (languages != null) {
            for (String l : languages) {
                if (l != null && !l.isBlank()) langSet.add(l.strip().toLowerCase(Locale.ROOT));
            }
        }

        int omittedFiles = 0;
        int totalRedacted = 0;
        boolean stoppedEarly = false;
        var allHits = new ArrayList<CapturedSearchIndex.CodeSearchHit>();
        for (var entry : sortedEntries) {
            if (stop != null && stop.get() != StopReason.NONE) {
                stoppedEarly = true;
                break;
            }
            if (!langSet.isEmpty() && !langSet.contains(entry.language().toLowerCase(Locale.ROOT))) continue;
            boolean advisory = CapturedSearchIndex.isAdvisoryPath(entry.path(), entry.language());
            if (!includeAdvisory && advisory) continue;
            if (!entry.readable() || entry.size() > MAX_SEARCH_FILE_BYTES || isKnownBinary(entry.path())) {
                omittedFiles++;
                continue;
            }
            var rawBytesOpt = readBytes(graph, entry);
            if (rawBytesOpt.isEmpty()) {
                omittedFiles++;
                continue;
            }
            byte[] rawBytes = rawBytesOpt.orElseThrow();
            if (rawBytes.length > MAX_SEARCH_FILE_BYTES || containsNul(rawBytes)) {
                omittedFiles++;
                continue;
            }
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(rawBytes)).toString();
                if (text.startsWith("\uFEFF")) text = text.substring(1);
            } catch (CharacterCodingException binary) {
                omittedFiles++;
                continue;
            }
            String sha = entry.digest().map(ContentDigest::value)
                    .orElseGet(() -> ContentDigest.sha256(rawBytes).value());
            var doc = LlmTransport.redactDocumentForPath(entry.path(), text);
            int docRedacted = doc.totalRedactedCount();
            totalRedacted += docRedacted;

            String pathExpanded = CapturedSearchIndex.expandSplitTokens(entry.path());
            List<String> lines = doc.lines();
            int bestLineIdx = -1;
            int bestLineHits = 0;
            int totalMatchedTerms = 0;
            var matchedTermsInDoc = new HashSet<String>();
            for (String t : terms) {
                if (pathExpanded.contains(t)) matchedTermsInDoc.add(t);
            }
            for (int li = 0; li < lines.size(); li++) {
                String lineExpanded = CapturedSearchIndex.expandSplitTokens(lines.get(li));
                int hitsOnLine = 0;
                for (String t : terms) {
                    if (lineExpanded.contains(t)) {
                        hitsOnLine++;
                        matchedTermsInDoc.add(t);
                    }
                }
                if (hitsOnLine > bestLineHits) {
                    bestLineHits = hitsOnLine;
                    bestLineIdx = li;
                }
            }
            totalMatchedTerms = matchedTermsInDoc.size();
            if (totalMatchedTerms == 0) continue;

            int anchorIdx = bestLineIdx >= 0 ? bestLineIdx : 0;
            int winStart = Math.max(0, anchorIdx - 1);
            int winEnd = Math.min(lines.size() - 1, anchorIdx + 2);
            var sb = new StringBuilder();
            for (int li = winStart; li <= winEnd; li++) {
                if (li > winStart) sb.append('\n');
                sb.append(lines.get(li));
                if (sb.length() >= MAX_CODE_SEARCH_SNIPPET_CHARS) break;
            }
            String snippet = sb.length() > MAX_CODE_SEARCH_SNIPPET_CHARS
                    ? sb.substring(0, MAX_CODE_SEARCH_SNIPPET_CHARS) + "…" : sb.toString();
            double rawScore = (totalMatchedTerms * 2.0) / Math.max(1, terms.size()) + (bestLineHits * 0.5)
                    + (pathExpanded.contains(terms.getFirst()) ? 1.5 : 0.0);
            BigDecimal score = BigDecimal.valueOf(rawScore).setScale(6, RoundingMode.HALF_UP);
            TrustTier tier = advisory ? TrustTier.ADVISORY_UNVERIFIED : TrustTier.SOURCE_SYNTAX;
            allHits.add(new CapturedSearchIndex.CodeSearchHit(
                    entry.path(), winStart + 1, winEnd + 1, score, snippet, sha,
                    entry.language(), tier, advisory, docRedacted));
        }
        allHits.sort(Comparator.comparing(CapturedSearchIndex.CodeSearchHit::score).reversed()
                .thenComparing(CapturedSearchIndex.CodeSearchHit::path)
                .thenComparingInt(CapturedSearchIndex.CodeSearchHit::startLine));
        boolean hitCeiling = stoppedEarly || allHits.size() > CapturedSearchIndex.MAX_MATCH_ROWS;
        int totalHits = Math.min(allHits.size(), CapturedSearchIndex.MAX_MATCH_ROWS);
        int start = Math.min(safeOffset, totalHits);
        int end = Math.min(totalHits, start + safeLimit);
        return new CapturedSearchIndex.CodeSearchResult(
                CapturedSearchIndex.ENGINE_STREAMING_FALLBACK, totalHits, hitCeiling,
                omittedFiles, totalRedacted, safeOffset, safeLimit,
                List.copyOf(allHits.subList(start, end)));
    }

    public static String normalizePath(String raw) {
        if (raw == null) return "";
        String norm = raw.strip().replace('\\', '/');
        while (norm.startsWith("./")) norm = norm.substring(2);
        while (norm.startsWith("/")) norm = norm.substring(1);
        return norm;
    }

    public static String inferLanguage(String path, List<String> candidates) {
        for (String c : candidates) {
            String low = c.strip().toLowerCase(Locale.ROOT);
            if (!low.isEmpty() && !low.equals("unknown") && !low.equals("text") && !low.equals("plain")) {
                return low;
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        if (name.equals("dockerfile") || name.startsWith("dockerfile.")) return "dockerfile";
        if (name.equals("makefile") || name.equals("gnumakefile")) return "makefile";
        if (name.equals("jenkinsfile")) return "groovy";
        if (name.equals("pom.xml")) return "xml";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "text";
        String ext = name.substring(dot + 1);
        return switch (ext) {
            case "java" -> "java";
            case "kt", "kts" -> "kotlin";
            case "py", "pyi" -> "python";
            case "ts", "mts", "cts" -> "typescript";
            case "tsx" -> "tsx";
            case "js", "mjs", "cjs" -> "javascript";
            case "jsx" -> "jsx";
            case "go" -> "go";
            case "rs" -> "rust";
            case "cs" -> "csharp";
            case "php" -> "php";
            case "rb" -> "ruby";
            case "scala" -> "scala";
            case "groovy", "gradle" -> "groovy";
            case "proto" -> "protobuf";
            case "yml", "yaml" -> "yaml";
            case "json", "jsonc" -> "json";
            case "xml", "xsd", "xsl" -> "xml";
            case "toml" -> "toml";
            case "properties", "ini", "cfg", "conf", "env" -> "properties";
            case "sql" -> "sql";
            case "tf", "tfvars", "hcl" -> "terraform";
            case "vue" -> "vue";
            case "svelte" -> "svelte";
            case "graphql", "gql" -> "graphql";
            case "md", "mdx", "rst", "adoc" -> "markdown";
            case "html", "htm" -> "html";
            case "css", "scss", "less" -> "css";
            case "sh", "bash", "zsh" -> "shell";
            case "ps1" -> "powershell";
            case "c", "h" -> "c";
            case "cpp", "cc", "cxx", "hpp", "hh" -> "cpp";
            case "swift" -> "swift";
            case "dart" -> "dart";
            default -> ext;
        };
    }

    public static boolean isKnownBinary(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = lower.substring(dot + 1);
        return Set.of("png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "pdf", "zip", "jar", "war", "ear",
                "tar", "gz", "tgz", "bz2", "xz", "7z", "class", "o", "so", "dll", "exe", "dylib", "wasm",
                "woff", "woff2", "ttf", "otf", "eot", "mp3", "mp4", "wav", "avi", "mov", "sqlite", "db").contains(ext);
    }

    private static boolean containsNul(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    private static long estimateSize(Map<String, Object> sourceBytesRow) {
        if (sourceBytesRow.get("size") instanceof Number n) return n.longValue();
        if (sourceBytesRow.get("bytesBase64") instanceof String b64) return (b64.length() * 3L) / 4L;
        return 0L;
    }

    private static Pattern compileGlob(String glob) {
        var regex = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i += 2;
                    if (i < glob.length() && glob.charAt(i) == '/') i++;
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) regex.append('\\');
                regex.append(c);
                i++;
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static Map<String, Object> readJson(GraphEvidence ev) {
        return GraphJson.object(GraphJson.read(ev.canonicalJson(), EVIDENCE_JSON));
    }
}
