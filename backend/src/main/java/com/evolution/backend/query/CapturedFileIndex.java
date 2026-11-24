package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Per-graph cached index of all captured repository files across both {@code source.bytes}
 * and {@code polyglot.inventory-entry} evidence, with CAS fallback verification.
 */
final class CapturedFileIndex {
    static final int MAX_LINES_PER_READ = 250;
    private static final int MAX_CACHED_GRAPHS = 16;
    private static final int MAX_SEARCH_FILES = 500;
    private static final long MAX_SEARCH_BYTES = 12L * 1024 * 1024;
    private static final int MAX_MATCH_LINE_CHARS = 300;
    private static final GraphJson.Limits EVIDENCE_JSON = new GraphJson.Limits(2 * 1024 * 1024, 32, 32_768);

    private static final Map<ContentDigest, CapturedFileIndex> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHED_GRAPHS + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ContentDigest, CapturedFileIndex> eldest) {
                    return size() > MAX_CACHED_GRAPHS;
                }
            });

    record Entry(String path, String language, long size, Optional<ContentDigest> digest,
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

    record ReadResult(boolean found, boolean ambiguous, String path, String language,
                      Optional<ContentDigest> digest, Optional<ContentDigest> evidenceId,
                      int startLine, int endLine, int totalLines, boolean truncated,
                      String content, String numberedSource, List<String> candidates) {}

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
                        List<SearchMatch> matches, List<ArchitectureChatModel.SourceUse> readSources) {}

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

    static CapturedFileIndex forGraph(KnowledgeGraph graph) {
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
                        ? Optional.of(new ContentDigest(d)) : Optional.empty();
                long size = estimateSize(row);
                String language = inferLanguage(path, List.of());
                byPath.put(path, new Entry(path, language, size, digest, ev.identity(), "source.bytes", true));
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

    List<Entry> entries() {
        return sortedEntries;
    }

    Optional<Entry> findExact(String path) {
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

    ReadResult read(KnowledgeGraph graph, String rawPath, int requestedStartLine, int requestedEndLine) {
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
        String[] allLines = text.split("\\R", -1);
        int totalLines = allLines.length;
        int start = Math.max(1, Math.min(requestedStartLine, Math.max(1, totalLines)));
        int reqEnd = requestedEndLine < start ? start + MAX_LINES_PER_READ - 1 : requestedEndLine;
        int end = Math.min(totalLines, Math.min(reqEnd, start + MAX_LINES_PER_READ - 1));
        var rawSlice = new StringBuilder();
        var numbered = new StringBuilder();
        for (int i = start; i <= end; i++) {
            String line = allLines[i - 1];
            if (i > start) rawSlice.append('\n');
            rawSlice.append(line);
            numbered.append(i).append(": ").append(line).append('\n');
        }
        boolean truncated = end < totalLines || start > 1;
        return new ReadResult(true, false, matched.path(), matched.language(), matched.digest(),
                Optional.of(matched.evidenceId()), start, end, totalLines, truncated,
                rawSlice.toString(), numbered.toString(), List.of());
    }

    Optional<String> readText(KnowledgeGraph graph, Entry entry) {
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

    Optional<byte[]> readBytes(KnowledgeGraph graph, Entry entry) {
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
        OptionalLong expectedSize = entry.size() > 0 ? OptionalLong.of(entry.size()) : OptionalLong.empty();
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
        int cap = Math.max(1, Math.min(100, maxMatches));
        Pattern pattern;
        try {
            pattern = regex
                    ? Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    : Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException invalid) {
            throw new IllegalArgumentException("Invalid search regex: " + invalid.getDescription());
        }
        var filtered = list(pathFilter, languageFilter, 0, MAX_SEARCH_FILES).files();
        var matches = new ArrayList<SearchMatch>();
        var matchedPaths = new LinkedHashSet<String>();
        var readSources = new LinkedHashMap<String, ArchitectureChatModel.SourceUse>();
        int scanned = 0;
        long bytesScanned = 0L;
        boolean truncated = false;

        for (var entry : filtered) {
            if (stop != null && stop.get() != StopReason.NONE) {
                truncated = true;
                break;
            }
            if (!entry.readable() || isKnownBinary(entry.path())) continue;
            if (scanned >= MAX_SEARCH_FILES || bytesScanned >= MAX_SEARCH_BYTES) {
                truncated = true;
                break;
            }
            var textOpt = readText(graph, entry);
            if (textOpt.isEmpty()) continue;
            scanned++;
            String text = textOpt.orElseThrow();
            bytesScanned += text.length();
            String[] lines = text.split("\\R", -1);
            boolean fileMatched = false;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (pattern.matcher(line).find()) {
                    fileMatched = true;
                    String snippet = line.length() > MAX_MATCH_LINE_CHARS
                            ? line.substring(0, MAX_MATCH_LINE_CHARS) + "…" : line;
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
                List.copyOf(matches), List.copyOf(readSources.values()));
    }

    static String normalizePath(String raw) {
        if (raw == null) return "";
        String norm = raw.strip().replace('\\', '/');
        while (norm.startsWith("./")) norm = norm.substring(2);
        while (norm.startsWith("/")) norm = norm.substring(1);
        return norm;
    }

    static String inferLanguage(String path, List<String> candidates) {
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

    private static boolean isKnownBinary(String path) {
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
