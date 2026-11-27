package com.evolution.backend.analysis;

import com.evolution.analysis.graph.GraphJson;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java 21 static JS/TS workspace alias and SFC ({@code .vue}/{@code .svelte}) script extractor.
 *
 * <p>Reads {@code tsconfig.json}/{@code jsconfig.json} ({@code baseUrl}, {@code paths} with JSONC
 * comment/trailing-comma tolerance), {@code pnpm-workspace.yaml}, and {@code package.json}
 * ({@code workspaces}, {@code exports}, {@code imports}) directly from captured bytes without
 * executing Node, npm, pnpm, or tsc on the host.
 */
public final class JsWorkspaceAliasResolver {
    private static final GraphJson.Limits CONFIG_JSON = new GraphJson.Limits(1_048_576, 32, 16_384);
    private static final Pattern LANG_TS_ATTR = Pattern.compile(
            "(?i)\\blang\\s*=\\s*['\"](?:ts|tsx|typescript)['\"]");
    private static final Pattern PNPM_PKG_LINE = Pattern.compile("^\\s*-\\s*['\"]?([^'\"#]+?)['\"]?\\s*(?:#.*)?$");

    private static final int MAX_EXTENDS_DEPTH = 8;

    public record AliasRule(String configPath, String baseDir, String pattern, List<String> targets, String sourceKind) {
        public AliasRule {
            targets = List.copyOf(targets);
        }
    }

    public record WorkspacePackage(String name, String dir, String mainEntry) {}

    public record WorkspaceModel(
            List<AliasRule> aliases,
            List<String> workspaceGlobs,
            Map<String, String> packageExports,
            Map<String, String> packageImports,
            Map<String, WorkspacePackage> workspacePackages) {
        public static final WorkspaceModel EMPTY = new WorkspaceModel(List.of(), List.of(), Map.of(), Map.of(), Map.of());

        public WorkspaceModel(
                List<AliasRule> aliases,
                List<String> workspaceGlobs,
                Map<String, String> packageExports,
                Map<String, String> packageImports) {
            this(aliases, workspaceGlobs, packageExports, packageImports, Map.of());
        }

        public WorkspaceModel {
            aliases = List.copyOf(aliases);
            workspaceGlobs = List.copyOf(workspaceGlobs);
            packageExports = Collections.unmodifiableMap(new TreeMap<>(packageExports));
            packageImports = Collections.unmodifiableMap(new TreeMap<>(packageImports));
            workspacePackages = Collections.unmodifiableMap(new TreeMap<>(workspacePackages == null ? Map.of() : workspacePackages));
        }

        /**
         * Deterministically resolves an import specifier (e.g. {@code @/services/api}, {@code #utils/date},
         * wildcard {@code package.json} {@code exports}, or {@code pnpm-workspace.yaml}/{@code workspaces}
         * packages) relative to a captured file set. Returns empty if no alias matches.
         */
        public Optional<String> resolveImport(String fromFilePath, String specifier, Set<String> knownPaths) {
            if (specifier == null || specifier.isBlank()) return Optional.empty();
            String spec = specifier.strip();
            // 1. Subpath #imports from package.json
            if (spec.startsWith("#")) {
                for (var entry : packageImports.entrySet()) {
                    String resolved = matchPatternTarget(spec, entry.getKey(), entry.getValue(), "");
                    if (resolved != null) {
                        Optional<String> hit = probeCandidate(resolved, knownPaths);
                        if (hit.isPresent()) return hit;
                        if (knownPaths == null || knownPaths.isEmpty()) return Optional.of(resolved);
                    }
                }
            }
            // 2. tsconfig/jsconfig paths (most specific directory first; includes inherited extends rules)
            String normFrom = normalizePath(fromFilePath);
            var sorted = new ArrayList<>(aliases);
            sorted.sort(Comparator.comparingInt((AliasRule r) -> r.baseDir().length()).reversed()
                    .thenComparing(Comparator.comparingInt((AliasRule r) -> r.pattern().length()).reversed())
                    .thenComparing(AliasRule::pattern));
            for (var rule : sorted) {
                if (!rule.baseDir().isEmpty() && !normFrom.startsWith(rule.baseDir() + "/") && !normFrom.equals(rule.baseDir())) {
                    continue;
                }
                for (String target : rule.targets()) {
                    String resolved = matchPatternTarget(spec, rule.pattern(), target, rule.baseDir());
                    if (resolved != null) {
                        Optional<String> hit = probeCandidate(resolved, knownPaths);
                        if (hit.isPresent()) return hit;
                        if (knownPaths == null || knownPaths.isEmpty()) {
                            return Optional.of(resolved);
                        }
                    }
                }
            }
            // 3. Package exports exact lookup first, then wildcard exports (e.g. @acme/ui/* -> packages/ui/src/*.ts)
            String exp = packageExports.get(spec);
            if (exp != null) {
                Optional<String> hit = probeCandidate(exp, knownPaths);
                if (hit.isPresent()) return hit;
                if (knownPaths == null || knownPaths.isEmpty()) return Optional.of(exp);
            }
            var wildcardExports = new ArrayList<Map.Entry<String, String>>();
            for (var entry : packageExports.entrySet()) {
                if (entry.getKey().contains("*")) wildcardExports.add(entry);
            }
            wildcardExports.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed()
                    .thenComparing(Map.Entry::getKey));
            for (var entry : wildcardExports) {
                String resolved = matchPatternTarget(spec, entry.getKey(), entry.getValue(), "");
                if (resolved != null) {
                    Optional<String> hit = probeCandidate(resolved, knownPaths);
                    if (hit.isPresent()) return hit;
                    if (knownPaths == null || knownPaths.isEmpty()) return Optional.of(resolved);
                }
            }
            // 4. pnpm-workspace.yaml / package.json workspaces package resolution
            if (!workspacePackages.isEmpty()) {
                var pkg = workspacePackages.get(spec);
                if (pkg != null) {
                    for (String cand : workspaceMainCandidates(pkg)) {
                        Optional<String> hit = probeCandidate(cand, knownPaths);
                        if (hit.isPresent()) return hit;
                    }
                    if (knownPaths == null || knownPaths.isEmpty()) {
                        return Optional.of(pkg.mainEntry().isEmpty() ? joinPaths(pkg.dir(), "src/index") : pkg.mainEntry());
                    }
                }
                // Subpath import into a workspace package (e.g. @acme/ui/Button)
                WorkspacePackage bestMatch = null;
                for (var entry : workspacePackages.entrySet()) {
                    String name = entry.getKey();
                    if (spec.startsWith(name + "/")) {
                        if (bestMatch == null || name.length() > bestMatch.name().length()) {
                            bestMatch = entry.getValue();
                        }
                    }
                }
                if (bestMatch != null) {
                    String sub = spec.substring(bestMatch.name().length() + 1);
                    for (String prefix : List.of(bestMatch.dir(), joinPaths(bestMatch.dir(), "src"))) {
                        if (prefix == null) continue;
                        String cand = joinPaths(prefix, sub);
                        if (cand != null) {
                            Optional<String> hit = probeCandidate(cand, knownPaths);
                            if (hit.isPresent()) return hit;
                        }
                    }
                    if (knownPaths == null || knownPaths.isEmpty()) {
                        String fallback = joinPaths(bestMatch.dir(), sub);
                        if (fallback != null) return Optional.of(fallback);
                    }
                }
            }
            return Optional.empty();
        }

        private static List<String> workspaceMainCandidates(WorkspacePackage pkg) {
            var out = new ArrayList<String>();
            if (!pkg.mainEntry().isEmpty()) out.add(pkg.mainEntry());
            String srcIdx = joinPaths(pkg.dir(), "src/index");
            if (srcIdx != null) out.add(srcIdx);
            String rootIdx = joinPaths(pkg.dir(), "index");
            if (rootIdx != null) out.add(rootIdx);
            return out;
        }
    }

    public record ExtractedScript(String code, String language, int startLine, int endLine) {}

    private JsWorkspaceAliasResolver() {}

    /**
     * Parses captured config files ({@code tsconfig*.json}, {@code jsconfig*.json},
     * {@code pnpm-workspace.yaml}, {@code package.json}) into a deterministic workspace alias model.
     */
    public static WorkspaceModel build(Map<String, String> capturedConfigsByPath) {
        if (capturedConfigsByPath == null || capturedConfigsByPath.isEmpty()) {
            return WorkspaceModel.EMPTY;
        }
        var aliases = new ArrayList<AliasRule>();
        var workspaceGlobs = new LinkedHashSet<String>();
        var packageExports = new TreeMap<String, String>();
        var packageImports = new TreeMap<String, String>();
        var parsedTsConfigs = new LinkedHashMap<String, Map<String, Object>>();
        var discoveredPackages = new ArrayList<WorkspacePackage>();

        var sortedPaths = new ArrayList<>(capturedConfigsByPath.keySet());
        Collections.sort(sortedPaths);

        // Pass 1: parse workspace globs, package.json metadata, and raw tsconfig/jsconfig JSON objects
        for (String rawPath : sortedPaths) {
            String path = normalizePath(rawPath);
            String content = capturedConfigsByPath.get(rawPath);
            if (content == null || content.isBlank()) continue;
            String fileName = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            String dir = parentDir(path);

            if ((fileName.contains("tsconfig") || fileName.contains("jsconfig")) && fileName.endsWith(".json")) {
                try {
                    parsedTsConfigs.put(path, GraphJson.object(GraphJson.read(stripJsonc(content), CONFIG_JSON)));
                } catch (RuntimeException ignored) {
                    // Malformed config stays non-fatal in passive static mode.
                }
            } else if (fileName.equals("pnpm-workspace.yaml") || fileName.equals("pnpm-workspace.yml")) {
                parsePnpmWorkspace(content, workspaceGlobs);
            } else if (fileName.equals("package.json")) {
                parsePackageJson(dir, content, workspaceGlobs, packageExports, packageImports, discoveredPackages);
            }
        }

        // Pass 2: resolve tsconfig/jsconfig with bounded "extends" inheritance chains (max depth 8, cycle-safe)
        for (var entry : parsedTsConfigs.entrySet()) {
            String path = entry.getKey();
            String dir = parentDir(path);
            resolveTsConfigWithExtends(path, dir, parsedTsConfigs, aliases);
        }

        // Pass 3: register workspace packages matching parsed pnpm-workspace.yaml / package.json workspaces globs
        var workspacePackages = new TreeMap<String, WorkspacePackage>();
        if (!workspaceGlobs.isEmpty()) {
            for (var pkg : discoveredPackages) {
                if (!pkg.dir().isEmpty() && matchesAnyWorkspaceGlob(pkg.dir(), workspaceGlobs)) {
                    workspacePackages.putIfAbsent(pkg.name(), pkg);
                }
            }
        }

        return new WorkspaceModel(aliases, List.copyOf(workspaceGlobs), packageExports, packageImports, workspacePackages);
    }

    /**
     * Extracts all {@code <script>} blocks from a {@code .vue} or {@code .svelte} Single-File Component
     * with exact 1-based line offsets and inferred language ({@code typescript} vs {@code javascript}).
     * Skips HTML comments ({@code <!-- ... -->}) and parses opening tag attributes quote-aware so
     * Vue 3 {@code generic="T extends Record<string, any>"} attributes never truncate the opening tag.
     */
    public static List<ExtractedScript> extractSfcScripts(String sfcText) {
        if (sfcText == null || sfcText.isEmpty()) return List.of();
        var scripts = new ArrayList<ExtractedScript>();
        int len = sfcText.length();
        int i = 0;
        while (i < len) {
            int lt = sfcText.indexOf('<', i);
            if (lt < 0) break;
            // Skip HTML comments <!-- ... -->
            if (sfcText.startsWith("<!--", lt)) {
                int commentEnd = sfcText.indexOf("-->", lt + 4);
                if (commentEnd < 0) break;
                i = commentEnd + 3;
                continue;
            }
            if (!sfcText.regionMatches(true, lt, "<script", 0, 7)) {
                i = lt + 1;
                continue;
            }
            int afterName = lt + 7;
            if (afterName < len) {
                char nextCh = sfcText.charAt(afterName);
                if (nextCh != '>' && nextCh != '/' && !Character.isWhitespace(nextCh)) {
                    i = afterName;
                    continue;
                }
            }
            // Scan opening <script ...> tag quote-aware
            int pos = afterName;
            char quote = 0;
            while (pos < len) {
                char c = sfcText.charAt(pos);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '>') {
                    break;
                }
                pos++;
            }
            if (pos >= len) break;
            String attrs = sfcText.substring(afterName, pos);
            int bodyStart = pos + 1;
            // Skip self-closing <script ... />
            if (attrs.stripTrailing().endsWith("/")) {
                i = bodyStart;
                continue;
            }
            // Find matching </script> (case-insensitive)
            int closeStart = findClosingScriptTag(sfcText, bodyStart);
            if (closeStart < 0) break;
            int closeGt = sfcText.indexOf('>', closeStart + 8);
            int nextPos = closeGt < 0 ? len : closeGt + 1;

            String body = sfcText.substring(bodyStart, closeStart);
            if (!body.isBlank()) {
                String lang = LANG_TS_ATTR.matcher(attrs).find() ? "typescript" : "javascript";
                int startLine = countNewlinesBefore(sfcText, bodyStart) + 1;
                int endLine = countNewlinesBefore(sfcText, closeStart) + 1;
                scripts.add(new ExtractedScript(body, lang, startLine, Math.max(startLine, endLine)));
            }
            i = nextPos;
        }
        return List.copyOf(scripts);
    }

    /**
     * Locates the closing {@code </script>} tag while skipping JS string literals ({@code '...'}, {@code "..."},
     * template literals {@code `...`}) and comments ({@code //}, {@code /* ... * /}) so strings containing
     * {@code "</script>"} never prematurely truncate the SFC script block.
     */
    private static int findClosingScriptTag(String text, int from) {
        int len = text.length();
        int i = from;
        char quote = 0; // ', ", or `
        boolean inLineComment = false;
        boolean inBlockComment = false;
        // Stack of brace depths for nested template literal ${ ... } interpolations
        var templateBraceStack = new ArrayDeque<Integer>();
        while (i < len) {
            char c = text.charAt(i);
            if (inLineComment) {
                if (c == '\n' || c == '\r') inLineComment = false;
                i++;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < len && text.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                if (c == '\\' && i + 1 < len) {
                    i += 2;
                    continue;
                }
                if (quote == '`' && c == '$' && i + 1 < len && text.charAt(i + 1) == '{') {
                    // Enter JS expression inside template literal; resume template literal when matching '}' closes
                    templateBraceStack.push(0);
                    quote = 0;
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    quote = 0;
                } else if (quote != '`' && (c == '\n' || c == '\r')) {
                    // Unterminated single/double-quoted string cannot span newlines in JS
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == '/' && i + 1 < len) {
                char next = text.charAt(i + 1);
                if (next == '/') {
                    inLineComment = true;
                    i += 2;
                    continue;
                }
                if (next == '*') {
                    inBlockComment = true;
                    i += 2;
                    continue;
                }
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                i++;
                continue;
            }
            if (!templateBraceStack.isEmpty()) {
                if (c == '{') {
                    templateBraceStack.push(templateBraceStack.pop() + 1);
                    i++;
                    continue;
                }
                if (c == '}') {
                    int depth = templateBraceStack.pop();
                    if (depth == 0) {
                        // Exiting ${...} expression back into enclosing template literal
                        quote = '`';
                    } else {
                        templateBraceStack.push(depth - 1);
                    }
                    i++;
                    continue;
                }
            }
            if (c == '<' && i + 8 < len && text.regionMatches(true, i, "</script", 0, 8)) {
                int p = i + 8;
                while (p < len && Character.isWhitespace(text.charAt(p))) p++;
                if (p < len && text.charAt(p) == '>') {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /** Strips JSONC line/block comments and trailing commas so standard {@link GraphJson} parses cleanly. */
    public static String stripJsonc(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        var noComments = new StringBuilder(raw.length());
        boolean inString = false;
        boolean escape = false;
        int i = 0;
        int len = raw.length();
        while (i < len) {
            char c = raw.charAt(i);
            if (inString) {
                noComments.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                noComments.append(c);
                i++;
            } else if (c == '/' && i + 1 < len && raw.charAt(i + 1) == '/') {
                i += 2;
                while (i < len && raw.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < len && raw.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(raw.charAt(i) == '*' && raw.charAt(i + 1) == '/')) {
                    if (raw.charAt(i) == '\n') noComments.append('\n');
                    i++;
                }
                i = Math.min(len, i + 2);
            } else {
                noComments.append(c);
                i++;
            }
        }
        // Remove trailing commas before } or ] outside strings
        String s = noComments.toString();
        var cleaned = new StringBuilder(s.length());
        inString = false;
        escape = false;
        for (int idx = 0; idx < s.length(); idx++) {
            char c = s.charAt(idx);
            if (inString) {
                cleaned.append(c);
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                cleaned.append(c);
            } else if (c == ',') {
                int j = idx + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length() && (s.charAt(j) == '}' || s.charAt(j) == ']')) {
                    continue; // Skip trailing comma
                }
                cleaned.append(c);
            } else {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    private static void resolveTsConfigWithExtends(
            String configPath,
            String configDir,
            Map<String, Map<String, Object>> parsedConfigs,
            List<AliasRule> out) {
        var mergedPaths = new LinkedHashMap<String, List<String>>();
        String[] inheritedBaseUrl = new String[1]; // [0] = effectiveBase from nearest config defining baseUrl
        collectTsConfigChain(configPath, parsedConfigs, new HashSet<>(), 0, mergedPaths, inheritedBaseUrl);

        var sortedKeys = new ArrayList<>(mergedPaths.keySet());
        Collections.sort(sortedKeys);
        for (String key : sortedKeys) {
            List<String> targets = mergedPaths.get(key);
            if (targets != null && !targets.isEmpty()) {
                out.add(new AliasRule(configPath, configDir, key, targets, "tsconfig.paths"));
            }
        }
        if (inheritedBaseUrl[0] != null && !inheritedBaseUrl[0].isEmpty()
                && !inheritedBaseUrl[0].equals(normalizePath(configDir))) {
            String baseWildcard = joinPaths(inheritedBaseUrl[0], "*");
            if (baseWildcard != null && !baseWildcard.isEmpty()) {
                out.add(new AliasRule(configPath, configDir, "*", List.of(baseWildcard), "tsconfig.baseUrl"));
            }
        }
    }

    private static void collectTsConfigChain(
            String currentPath,
            Map<String, Map<String, Object>> parsedConfigs,
            Set<String> visited,
            int depth,
            Map<String, List<String>> mergedPaths,
            String[] effectiveBaseHolder) {
        if (depth > MAX_EXTENDS_DEPTH || !visited.add(currentPath)) return;
        var root = parsedConfigs.get(currentPath);
        if (root == null) return;
        String dir = parentDir(currentPath);

        // Apply parent extends first so child overrides parent keys
        Object extObj = root.get("extends");
        var extRefs = new ArrayList<String>();
        if (extObj instanceof String s && !s.isBlank()) {
            extRefs.add(s.strip());
        } else if (extObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) extRefs.add(s.strip());
            }
        }
        for (String extRef : extRefs) {
            String resolvedParent = resolveExtendsPath(dir, extRef, parsedConfigs);
            if (resolvedParent != null) {
                collectTsConfigChain(resolvedParent, parsedConfigs, visited, depth + 1, mergedPaths, effectiveBaseHolder);
            }
        }

        if (!(root.get("compilerOptions") instanceof Map<?, ?> opts)) return;
        String rawBaseUrl = opts.get("baseUrl") instanceof String b ? b.strip() : "";
        String localBase = rawBaseUrl.isEmpty() ? normalizePath(dir) : joinPaths(dir, rawBaseUrl);
        if (!rawBaseUrl.isEmpty() && localBase != null) {
            effectiveBaseHolder[0] = localBase;
        }
        if (localBase == null) return;

        if (opts.get("paths") instanceof Map<?, ?> pathsMap) {
            var sortedKeys = new ArrayList<String>();
            for (Object k : pathsMap.keySet()) {
                if (k instanceof String s && !s.isBlank()) sortedKeys.add(s);
            }
            Collections.sort(sortedKeys);
            for (String key : sortedKeys) {
                if (!(pathsMap.get(key) instanceof List<?> arr)) continue;
                var targets = new LinkedHashSet<String>();
                for (Object item : arr) {
                    if (item instanceof String t && !t.isBlank()) {
                        String joined = joinPaths(localBase, t);
                        if (joined != null && !joined.isEmpty()) {
                            targets.add(joined);
                        }
                        if (rawBaseUrl.isEmpty() && effectiveBaseHolder[0] != null
                                && !effectiveBaseHolder[0].equals(localBase)) {
                            String inheritedJoined = joinPaths(effectiveBaseHolder[0], t);
                            if (inheritedJoined != null && !inheritedJoined.isEmpty()) {
                                targets.add(inheritedJoined);
                            }
                        }
                    }
                }
                if (!targets.isEmpty()) {
                    mergedPaths.put(key, List.copyOf(targets));
                }
            }
        }
    }

    private static String resolveExtendsPath(String fromDir, String rawExtends, Map<String, Map<String, Object>> parsedConfigs) {
        if (rawExtends == null || rawExtends.isBlank()) return null;
        if (!rawExtends.startsWith(".") && !rawExtends.startsWith("/")) {
            // Bare package extends (e.g. "@tsconfig/node20/tsconfig.json") are outside repo unless present verbatim
            return parsedConfigs.containsKey(rawExtends) ? rawExtends : null;
        }
        String joined = joinPaths(fromDir, rawExtends);
        if (joined == null || joined.isEmpty()) return null;
        if (parsedConfigs.containsKey(joined)) return joined;
        if (!joined.endsWith(".json") && parsedConfigs.containsKey(joined + ".json")) return joined + ".json";
        if (parsedConfigs.containsKey(joined + "/tsconfig.json")) return joined + "/tsconfig.json";
        return null;
    }

    private static boolean matchesAnyWorkspaceGlob(String dir, Set<String> globs) {
        String normDir = normalizePath(dir);
        boolean matchedPositive = false;
        for (String rawGlob : globs) {
            if (rawGlob == null) continue;
            String trimmed = rawGlob.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("!")) continue;
            String g = normalizePath(trimmed);
            while (g.endsWith("/")) g = g.substring(0, g.length() - 1);
            if (!g.isEmpty() && globMatchesPath(g, normDir)) {
                matchedPositive = true;
                break;
            }
        }
        if (!matchedPositive) return false;
        for (String rawGlob : globs) {
            if (rawGlob == null) continue;
            String trimmed = rawGlob.strip();
            if (!trimmed.startsWith("!")) continue;
            String g = normalizePath(trimmed.substring(1));
            while (g.endsWith("/")) g = g.substring(0, g.length() - 1);
            if (!g.isEmpty() && globMatchesPath(g, normDir)) {
                return false;
            }
        }
        return true;
    }

    private static boolean globMatchesPath(String glob, String path) {
        var regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]+");
                }
            } else if ("\\.^$|+?()[]{}".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        regex.append('$');
        return path.matches(regex.toString());
    }

    private static void parsePnpmWorkspace(String content, Set<String> outGlobs) {
        boolean inPackages = false;
        for (String line : content.split("\\R", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("packages:")) {
                inPackages = true;
                continue;
            }
            if (!line.startsWith(" ") && !line.startsWith("\t") && !trimmed.startsWith("-")) {
                inPackages = false;
                continue;
            }
            if (inPackages) {
                Matcher m = PNPM_PKG_LINE.matcher(line);
                if (m.matches()) {
                    String glob = m.group(1).strip();
                    if (!glob.isEmpty()) {
                        outGlobs.add(glob);
                    }
                }
            }
        }
    }

    private static void parsePackageJson(
            String dir, String content, Set<String> workspaces,
            Map<String, String> exportsMap, Map<String, String> importsMap,
            List<WorkspacePackage> discoveredPackages) {
        try {
            var root = GraphJson.object(GraphJson.read(content, CONFIG_JSON));
            String pkgName = root.get("name") instanceof String n ? n.strip() : "";

            // workspaces: ["packages/*"] or { packages: [...] }
            Object wsObj = root.get("workspaces");
            if (wsObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s && !s.isBlank()) workspaces.add(s.strip());
                }
            } else if (wsObj instanceof Map<?, ?> wsMap && wsMap.get("packages") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s && !s.isBlank()) workspaces.add(s.strip());
                }
            }

            if (!pkgName.isEmpty()) {
                String mainEntry = "";
                for (String key : List.of("module", "main", "types")) {
                    if (root.get(key) instanceof String s && !s.isBlank()) {
                        String joined = joinPaths(dir, s);
                        if (joined != null && !joined.isEmpty()) {
                            mainEntry = joined;
                            break;
                        }
                    }
                }
                discoveredPackages.add(new WorkspacePackage(pkgName, dir, mainEntry));
            }

            // exports (both exact, conditional top-level, and wildcard "./*" subpath patterns)
            if (!pkgName.isEmpty()) {
                Object expObj = root.get("exports");
                if (expObj instanceof String s && !s.isBlank()) {
                    String joined = joinPaths(dir, s);
                    if (joined != null && !joined.isEmpty()) exportsMap.put(pkgName, joined);
                } else if (expObj instanceof Map<?, ?> map) {
                    boolean hasSubpathKey = false;
                    for (Object k : map.keySet()) {
                        if (k instanceof String sk && (sk.equals(".") || sk.startsWith("./"))) {
                            hasSubpathKey = true;
                            break;
                        }
                    }
                    if (!hasSubpathKey) {
                        // Top-level conditional export map (e.g. {"import": "./dist/index.mjs", "types": "..."})
                        String target = extractExportTarget(map);
                        if (target != null) {
                            String joined = joinPaths(dir, target);
                            if (joined != null && !joined.isEmpty()) exportsMap.put(pkgName, joined);
                        }
                    } else {
                        for (var e : map.entrySet()) {
                            if (!(e.getKey() instanceof String subKey)) continue;
                            if (!subKey.equals(".") && !subKey.startsWith("./")) continue;
                            String target = extractExportTarget(e.getValue());
                            if (target == null) continue;
                            String fullSpec = subKey.equals(".") ? pkgName : (pkgName + "/" + subKey.substring(2));
                            String joined = joinPaths(dir, target);
                            if (joined != null && !joined.isEmpty()) exportsMap.put(fullSpec, joined);
                        }
                    }
                }
            }

            // #imports
            if (root.get("imports") instanceof Map<?, ?> impMap) {
                for (var e : impMap.entrySet()) {
                    if (e.getKey() instanceof String key && key.startsWith("#")) {
                        String target = extractExportTarget(e.getValue());
                        if (target != null) {
                            String joined = joinPaths(dir, target);
                            if (joined != null && !joined.isEmpty()) importsMap.put(key, joined);
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {}
    }

    private static String extractExportTarget(Object val) {
        if (val instanceof String s && !s.isBlank()) return s;
        if (val instanceof Map<?, ?> m) {
            for (String pref : List.of("import", "types", "default", "require")) {
                String sub = extractExportTarget(m.get(pref));
                if (sub != null) return sub;
            }
        }
        return null;
    }

    private static String matchPatternTarget(String spec, String pattern, String target, String baseDir) {
        int star = pattern.indexOf('*');
        if (star < 0) {
            return spec.equals(pattern) ? normalizeWithinRoot(target) : null;
        }
        String prefix = pattern.substring(0, star);
        String suffix = pattern.substring(star + 1);
        if (!spec.startsWith(prefix) || !spec.endsWith(suffix) || spec.length() < prefix.length() + suffix.length()) {
            return null;
        }
        String wildcard = spec.substring(prefix.length(), spec.length() - suffix.length());
        String replaced = target.contains("*") ? target.replace("*", wildcard) : joinPaths(target, wildcard);
        return replaced == null ? null : normalizeWithinRoot(replaced);
    }

    private static Optional<String> probeCandidate(String basePath, Set<String> knownPaths) {
        if (knownPaths == null || knownPaths.isEmpty()) return Optional.empty();
        if (knownPaths.contains(basePath)) return Optional.of(basePath);
        for (String ext : List.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".vue", ".svelte",
                "/index.ts", "/index.tsx", "/index.js", "/index.jsx")) {
            String cand = basePath + ext;
            if (knownPaths.contains(cand)) return Optional.of(cand);
        }
        return Optional.empty();
    }

    private static int countNewlinesBefore(String text, int offset) {
        int lines = 0;
        int lim = Math.min(offset, text.length());
        for (int i = 0; i < lim; i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    private static String parentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "";
    }

    /**
     * Joins repo-relative base {@code a} with relative target {@code b} BEFORE normalizing {@code ..} segments
     * so monorepo relative aliases like {@code ../shared/*} resolve cleanly while any traversal escaping above
     * the repository root (or using absolute OS paths) is rejected ({@code null}).
     */
    private static String joinPaths(String a, String b) {
        String na = normalizePath(a);
        String rb = b == null ? "" : b.strip().replace('\\', '/');
        if (rb.isEmpty() || rb.equals(".") || rb.equals("./")) return na;
        if (rb.startsWith("/") || (rb.length() >= 2 && Character.isLetter(rb.charAt(0)) && rb.charAt(1) == ':')) {
            return null;
        }
        String combined = na.isEmpty() ? rb : (na + "/" + rb);
        return normalizeWithinRoot(combined);
    }

    /** Normalizes a repo-relative path; returns {@code null} if {@code ..} escapes above repository root. */
    private static String normalizeWithinRoot(String raw) {
        if (raw == null) return "";
        String norm = raw.strip().replace('\\', '/');
        if (norm.startsWith("/") || (norm.length() >= 2 && Character.isLetter(norm.charAt(0)) && norm.charAt(1) == ':')) {
            return null;
        }
        String[] segs = norm.split("/");
        var stack = new ArrayDeque<String>();
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (stack.isEmpty()) {
                    return null; // Block escape above repository root
                }
                stack.removeLast();
            } else {
                stack.addLast(s);
            }
        }
        return String.join("/", stack);
    }

    private static String normalizePath(String raw) {
        if (raw == null) return "";
        String norm = raw.strip().replace('\\', '/');
        while (norm.startsWith("./")) norm = norm.substring(2);
        while (norm.startsWith("/")) norm = norm.substring(1);
        String[] segs = norm.split("/");
        var stack = new ArrayDeque<String>();
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..") && !stack.isEmpty()) stack.removeLast();
            else if (!s.equals("..")) stack.addLast(s);
        }
        return String.join("/", stack);
    }
}
