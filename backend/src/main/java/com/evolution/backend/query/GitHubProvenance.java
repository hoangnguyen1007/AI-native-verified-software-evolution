package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.backend.github.GitHubCoordinates;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Passive, zero-host-execution GitHub repository provenance, validated permalink builder,
 * and official GitHub CODEOWNERS parser operating strictly on captured {@link KnowledgeGraph} snapshots.
 */
public final class GitHubProvenance {
    private static final GraphJson.Limits SNAPSHOT_JSON = new GraphJson.Limits(4 * 1024 * 1024, 32, 65_536);
    private static final Pattern FULL_SHA = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final List<String> CODEOWNERS_PRECEDENCE = List.of(
            ".github/CODEOWNERS",
            "CODEOWNERS",
            "docs/CODEOWNERS");
    private static final int MAX_CODEOWNERS_BYTES = 512 * 1024;
    private static final int MAX_CODEOWNERS_RULES = 2_000;

    public record RepoCoords(String host, String owner, String repo, String commitSha) {
        public RepoCoords {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(commitSha, "commitSha");
            if (!GitHubCoordinates.isAllowedHost(host)) {
                throw new IllegalArgumentException("Disallowed GitHub host: " + host);
            }
            if (!FULL_SHA.matcher(commitSha).matches()) {
                throw new IllegalArgumentException("Full 40-hex commit SHA required: " + commitSha);
            }
        }

        public String fullName() {
            return owner + "/" + repo;
        }

        public String repoHtmlUrl() {
            return "https://" + host + "/" + owner + "/" + repo;
        }
    }

    public record CodeownersRule(int lineNumber, String pattern, List<String> owners) {
        public CodeownersRule {
            Objects.requireNonNull(pattern, "pattern");
            owners = List.copyOf(owners);
        }

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("line", lineNumber);
            map.put("pattern", pattern);
            map.put("owners", owners);
            return Collections.unmodifiableMap(map);
        }
    }

    public record OwnershipMatch(
            String path,
            List<String> owners,
            Optional<String> matchedPattern,
            Optional<Integer> ruleLine,
            Optional<String> sourceFile) {
        public OwnershipMatch {
            Objects.requireNonNull(path, "path");
            owners = List.copyOf(owners);
            Objects.requireNonNull(matchedPattern, "matchedPattern");
            Objects.requireNonNull(ruleLine, "ruleLine");
            Objects.requireNonNull(sourceFile, "sourceFile");
        }

        public boolean matched() {
            return matchedPattern.isPresent();
        }

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("path", path);
            map.put("owners", owners);
            matchedPattern.ifPresent(p -> map.put("matchedPattern", p));
            ruleLine.ifPresent(l -> map.put("ruleLine", l));
            sourceFile.ifPresent(s -> map.put("sourceFile", s));
            return Collections.unmodifiableMap(map);
        }
    }

    public record CodeownersIndex(Optional<String> sourceFile, List<CodeownersRule> rules) {
        public static final CodeownersIndex EMPTY = new CodeownersIndex(Optional.empty(), List.of());

        public CodeownersIndex {
            Objects.requireNonNull(sourceFile, "sourceFile");
            rules = List.copyOf(rules);
        }

        public boolean found() {
            return sourceFile.isPresent();
        }

        /**
         * Passively discovers and parses CODEOWNERS from captured snapshot evidence in official GitHub
         * precedence order ({@code .github/CODEOWNERS} -> {@code CODEOWNERS} -> {@code docs/CODEOWNERS}).
         */
        public static CodeownersIndex fromGraph(KnowledgeGraph graph) {
            if (graph == null) return EMPTY;
            var fileIndex = CapturedFileIndex.forGraph(graph);
            for (String candidatePath : CODEOWNERS_PRECEDENCE) {
                var entry = fileIndex.findExact(candidatePath);
                if (entry.isPresent()) {
                    var text = fileIndex.readText(graph, entry.orElseThrow());
                    if (text.isPresent()) {
                        return parse(candidatePath, text.orElseThrow());
                    }
                }
            }
            // Fallback: inspect repository.snapshot file digests + RepairSource content fallback (CAS)
            for (GraphEvidence ev : graph.evidence()) {
                if (!"repository.snapshot".equals(ev.family())) continue;
                try {
                    var row = GraphJson.object(GraphJson.read(ev.canonicalJson(), SNAPSHOT_JSON));
                    if (!(row.get("files") instanceof List<?> files)) continue;
                    var digestsByPath = new HashMap<String, ContentDigest>();
                    for (Object item : files) {
                        if (!(item instanceof Map<?, ?> m)) continue;
                        if (m.get("path") instanceof String p && m.get("contentDigest") instanceof String d) {
                            String norm = CapturedFileIndex.normalizePath(p);
                            if (CODEOWNERS_PRECEDENCE.contains(norm)) {
                                digestsByPath.putIfAbsent(norm, new ContentDigest(d));
                            }
                        }
                    }
                    for (String candidatePath : CODEOWNERS_PRECEDENCE) {
                        var digest = digestsByPath.get(candidatePath);
                        if (digest != null) {
                            var bytes = RepairSource.resolveContentFromStore(digest, OptionalLong.empty());
                            if (bytes.isPresent() && bytes.orElseThrow().length <= MAX_CODEOWNERS_BYTES) {
                                return parse(candidatePath, new String(bytes.orElseThrow(), StandardCharsets.UTF_8));
                            }
                        }
                    }
                } catch (RuntimeException ignored) {}
            }
            return EMPTY;
        }

        /**
         * Parses GitHub CODEOWNERS text following official GitHub syntax rules:
         * top-to-bottom last-matching-pattern wins, strictly case-sensitive, ignores unsupported
         * {@code !} negation, {@code [...]} character classes, and {@code \#} escapes.
         */
        public static CodeownersIndex parse(String sourceFile, String content) {
            if (content == null) return new CodeownersIndex(Optional.ofNullable(sourceFile), List.of());
            String bounded = content.length() > MAX_CODEOWNERS_BYTES
                    ? content.substring(0, MAX_CODEOWNERS_BYTES) : content;
            var rules = new ArrayList<CodeownersRule>();
            String[] lines = bounded.split("\\r?\\n", -1);
            for (int i = 0; i < lines.length && rules.size() < MAX_CODEOWNERS_RULES; i++) {
                String raw = lines[i];
                if (raw.startsWith("\uFEFF")) raw = raw.substring(1);
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                // Per GitHub CODEOWNERS spec: escaped #, negation !, and [...] character ranges are unsupported
                if (trimmed.startsWith("\\#") || trimmed.startsWith("!")) continue;
                var parsed = parseRuleLine(trimmed);
                if (parsed == null) continue;
                String pattern = parsed.pattern();
                if (pattern.indexOf('[') >= 0 || pattern.indexOf(']') >= 0) continue;
                rules.add(new CodeownersRule(i + 1, pattern, parsed.owners()));
            }
            return new CodeownersIndex(Optional.ofNullable(sourceFile), rules);
        }

        /** Evaluates all rules top-to-bottom; the last matching rule determines effective owners. */
        public OwnershipMatch match(String rawPath) {
            String path = normalizeRepoPath(rawPath);
            if (path.isEmpty() || rules.isEmpty()) {
                return new OwnershipMatch(path, List.of(), Optional.empty(), Optional.empty(), sourceFile);
            }
            CodeownersRule winner = null;
            for (var rule : rules) {
                if (matchesPattern(rule.pattern(), path)) {
                    winner = rule;
                }
            }
            if (winner == null) {
                return new OwnershipMatch(path, List.of(), Optional.empty(), Optional.empty(), sourceFile);
            }
            return new OwnershipMatch(path, winner.owners(), Optional.of(winner.pattern()),
                    Optional.of(winner.lineNumber()), sourceFile);
        }

        public List<String> ownersOf(String path) {
            return match(path).owners();
        }

        public List<String> pathsOwnedBy(String ownerHandle, Collection<String> repoPaths) {
            if (ownerHandle == null || ownerHandle.isBlank() || repoPaths == null || repoPaths.isEmpty()) {
                return List.of();
            }
            String needle = ownerHandle.strip();
            String withAt = needle.startsWith("@") || needle.contains("@") ? needle : ("@" + needle);
            var matched = new ArrayList<String>();
            for (String p : repoPaths) {
                var owners = ownersOf(p);
                for (String o : owners) {
                    if (o.equalsIgnoreCase(needle) || o.equalsIgnoreCase(withAt)) {
                        matched.add(normalizeRepoPath(p));
                        break;
                    }
                }
            }
            Collections.sort(matched);
            return List.copyOf(matched);
        }

        public Map<String, Object> toView(Optional<String> queryPath) {
            var map = new LinkedHashMap<String, Object>();
            map.put("found", found());
            map.put("sourcePath", sourceFile.orElse(null));
            map.put("ruleCount", rules.size());
            map.put("rules", rules.stream().limit(200).map(CodeownersRule::toView).toList());
            if (queryPath != null && queryPath.isPresent() && !queryPath.orElseThrow().isBlank()) {
                var m = match(queryPath.orElseThrow());
                map.put("matchedOwners", m.owners());
                map.put("match", m.toView());
            }
            return Collections.unmodifiableMap(map);
        }
    }

    private GitHubProvenance() {}

    /**
     * Extracts validated GitHub coordinates and full 40-hex commit SHA from snapshot/provenance evidence.
     * Checks {@code remoteUrl}, {@code repositoryUrl}, {@code canonicalCoordinate}, {@code htmlUrl},
     * and URL-formatted {@code repository} fields across {@code repository.snapshot}, {@code github.provenance},
     * and {@code repository.acquisition} evidence.
     */
    public static Optional<RepoCoords> extractFromGraph(KnowledgeGraph graph) {
        if (graph == null) return Optional.empty();
        String rev = extractRevision(graph).orElse(null);
        for (GraphEvidence ev : graph.evidence()) {
            String fam = ev.family();
            if (!"repository.snapshot".equals(fam) && !"github.provenance".equals(fam)
                    && !"repository.provenance".equals(fam) && !"repository.acquisition".equals(fam)) {
                continue;
            }
            try {
                var row = GraphJson.object(GraphJson.read(ev.canonicalJson(), SNAPSHOT_JSON));
                String localRev = firstSha(row, rev);
                if (localRev == null) continue;
                for (String key : List.of("remoteUrl", "repositoryUrl", "canonicalCoordinate", "htmlUrl", "repository")) {
                    if (row.get(key) instanceof String candidate && !candidate.isBlank()) {
                        var parsed = parseRepositoryCoordinate(candidate);
                        if (parsed.isPresent()) {
                            var coords = parsed.orElseThrow();
                            return Optional.of(new RepoCoords(coords.host(), coords.owner(), coords.repo(), localRev));
                        }
                    }
                }
            } catch (RuntimeException ignored) {}
        }
        return Optional.empty();
    }

    /** Extracts the captured full 40-hex Git commit SHA from {@code repository.snapshot} evidence, if present. */
    public static Optional<String> extractRevision(KnowledgeGraph graph) {
        if (graph == null) return Optional.empty();
        for (GraphEvidence ev : graph.evidence()) {
            if (!"repository.snapshot".equals(ev.family()) && !"github.provenance".equals(ev.family())) continue;
            try {
                var row = GraphJson.object(GraphJson.read(ev.canonicalJson(), SNAPSHOT_JSON));
                String sha = firstSha(row, null);
                if (sha != null) return Optional.of(sha);
            } catch (RuntimeException ignored) {}
        }
        return Optional.empty();
    }

    /**
     * Combines a known repository remote URL (e.g. from {@code RepositoryConnections.remoteUrl})
     * with the graph's captured 40-hex commit SHA to build validated {@link RepoCoords}.
     */
    public static Optional<RepoCoords> fromRemoteUrlAndGraph(String remoteUrl, KnowledgeGraph graph) {
        if (remoteUrl == null || remoteUrl.isBlank() || graph == null) return Optional.empty();
        var rev = extractRevision(graph);
        if (rev.isEmpty()) return Optional.empty();
        return parseRepositoryCoordinate(remoteUrl)
                .map(c -> new RepoCoords(c.host(), c.owner(), c.repo(), rev.orElseThrow()));
    }

    private static String firstSha(Map<String, Object> row, String fallback) {
        for (String key : List.of("revision", "commitSha", "headSha")) {
            if (row.get(key) instanceof String s) {
                String trimmed = s.strip();
                if (FULL_SHA.matcher(trimmed).matches()) return trimmed;
            }
        }
        return fallback;
    }

    /**
     * Parses a canonical {@code repo:...} coordinate or HTTPS/SSH GitHub URL into validated {@link GitHubCoordinates.Coordinates}.
     */
    public static Optional<GitHubCoordinates.Coordinates> parseRepositoryCoordinate(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String s = raw.strip();
        if (s.startsWith("repo:")) {
            s = s.substring("repo:".length());
        }
        if (s.startsWith("https://") || s.startsWith("git@")) {
            return GitHubCoordinates.tryParse(s);
        }
        // Handle canonical host/owner/repo format without scheme (e.g. "github.com/org/repo")
        int slash = s.indexOf('/');
        if (slash > 0 && s.substring(0, slash).contains(".")) {
            return GitHubCoordinates.tryParse("https://" + s);
        }
        return Optional.empty();
    }

    /**
     * Builds a strictly validated HTTPS GitHub blob permalink with per-segment URL encoding and optional line anchors.
     */
    public static Optional<String> blobPermalink(RepoCoords coords, String rawPath, Integer startLine, Integer endLine) {
        if (coords == null || rawPath == null || rawPath.isBlank()) return Optional.empty();
        String norm = normalizeRepoPath(rawPath);
        if (norm.isEmpty() || !RepairSource.isSafeRelativePath(norm)) return Optional.empty();
        var encoded = new StringBuilder();
        String[] segments = norm.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) encoded.append('/');
            encoded.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        var url = new StringBuilder("https://")
                .append(coords.host()).append('/')
                .append(coords.owner()).append('/')
                .append(coords.repo()).append("/blob/")
                .append(coords.commitSha()).append('/')
                .append(encoded);
        if (startLine != null && startLine > 0) {
            url.append("#L").append(startLine);
            if (endLine != null && endLine > startLine) {
                url.append("-L").append(endLine);
            }
        }
        return Optional.of(url.toString());
    }

    /** Convenience overload building a permalink directly from owner, repo, commit SHA, path, and optional line range. */
    public static Optional<String> blobPermalink(String host, String owner, String repo, String commitSha,
                                                 String path, Integer startLine, Integer endLine) {
        try {
            String effectiveHost = (host == null || host.isBlank()) ? GitHubCoordinates.DEFAULT_HOST : host.strip();
            var coords = new RepoCoords(effectiveHost, owner, repo, commitSha);
            return blobPermalink(coords, path, startLine, endLine);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private record ParsedRule(String pattern, List<String> owners) {}

    private static ParsedRule parseRuleLine(String trimmed) {
        // First token is pattern (supporting escaped spaces `\ `); inline `#` starts trailing comment
        var patBuf = new StringBuilder();
        int idx = 0;
        int len = trimmed.length();
        while (idx < len) {
            char c = trimmed.charAt(idx);
            if (c == '\\' && idx + 1 < len && trimmed.charAt(idx + 1) == ' ') {
                patBuf.append(' ');
                idx += 2;
                continue;
            }
            if (Character.isWhitespace(c)) break;
            if (c == '#') return null;
            patBuf.append(c);
            idx++;
        }
        String pattern = patBuf.toString();
        if (pattern.isEmpty()) return null;

        // Parse owner tokens until EOL or inline `#` comment
        var owners = new ArrayList<String>();
        while (idx < len) {
            while (idx < len && Character.isWhitespace(trimmed.charAt(idx))) idx++;
            if (idx >= len || trimmed.charAt(idx) == '#') break;
            int start = idx;
            while (idx < len && !Character.isWhitespace(trimmed.charAt(idx)) && trimmed.charAt(idx) != '#') idx++;
            String token = trimmed.substring(start, idx);
            if (isValidOwnerToken(token) && !owners.contains(token)) {
                owners.add(token);
            }
        }
        return new ParsedRule(pattern, owners);
    }

    private static boolean isValidOwnerToken(String token) {
        if (token.length() < 2 || token.length() > 128) return false;
        if (token.startsWith("@")) {
            // @username or @org/team-slug
            return token.matches("^@[A-Za-z0-9][A-Za-z0-9._-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)?$");
        }
        // email@domain
        int at = token.indexOf('@');
        return at > 0 && at < token.length() - 2 && token.indexOf('.', at) > at + 1 && !token.contains("..");
    }

    /** Official GitHub CODEOWNERS case-sensitive pattern matcher. */
    static boolean matchesPattern(String pattern, String normalizedPath) {
        if (pattern == null || pattern.isEmpty() || normalizedPath == null || normalizedPath.isEmpty()) {
            return false;
        }
        if ("*".equals(pattern)) return true;
        boolean dirOnly = pattern.endsWith("/");
        String p = dirOnly ? pattern.substring(0, pattern.length() - 1) : pattern;
        if (p.isEmpty()) return false;
        boolean anchored = p.startsWith("/") || p.indexOf('/') >= 0;
        if (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return false;

        boolean endsWithSingleStarOrQ = (p.endsWith("*") && !p.endsWith("**")) || p.endsWith("?");

        var regex = new StringBuilder();
        regex.append(anchored ? "^" : "^(?:.*/)?");
        int i = 0;
        while (i < p.length()) {
            if (p.startsWith("/**/", i)) {
                regex.append("(?:/|/.+/)");
                i += 4;
            } else if (i == 0 && p.startsWith("**/", i)) {
                regex.append("(?:.*/)?");
                i += 3;
            } else if (i + 3 == p.length() && p.startsWith("/**", i)) {
                regex.append("/.*");
                i += 3;
            } else if (p.startsWith("**", i)) {
                regex.append(".*");
                i += 2;
            } else {
                char c = p.charAt(i++);
                if (c == '*') {
                    regex.append("[^/]*");
                } else if (c == '?') {
                    regex.append("[^/]");
                } else {
                    if ("\\.^$|()[]{}+".indexOf(c) >= 0) regex.append('\\');
                    regex.append(c);
                }
            }
        }
        if (dirOnly) {
            regex.append("/.+$");
        } else if (endsWithSingleStarOrQ || p.endsWith("**")) {
            regex.append('$');
        } else {
            regex.append("(?:/.*)?$");
        }
        return Pattern.compile(regex.toString()).matcher(normalizedPath).matches();
    }

    private static String normalizeRepoPath(String raw) {
        if (raw == null) return "";
        String p = raw.strip().replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }
}
