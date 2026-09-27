package com.evolution.analysis.buildmodel;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.util.*;
import java.util.regex.*;

/** Safe literal subset of Gradle's default libs.versions.toml; never evaluates settings or plugins. */
final class GradleVersionCatalog {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"\\\\]*)\"|'([^'\\\\]*)'");
    record Catalog(Map<String, Optional<MavenCoordinate>> libraries,
                   Map<String, List<String>> bundles, BuildModelResult.PomEvidence evidence,
                   List<IngestionEvidence.Issue> issues) {
        Catalog {
            libraries = Map.copyOf(libraries); bundles = Map.copyOf(bundles);
            issues = List.copyOf(issues);
        }
    }
    static Catalog parse(String path, String text, ContentDigest digest, int maxLines) {
        Map<String,String> versions = new HashMap<>(), rawLibraries = new LinkedHashMap<>(), rawBundles = new LinkedHashMap<>();
        Set<String> seenVersions = new HashSet<>();
        Set<String> invalidLibraries = new HashSet<>(), invalidBundles = new HashSet<>();
        var issues = new ArrayList<IngestionEvidence.Issue>();
        String section = ""; String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index >= maxLines) { issue(issues, path, index + 1, digest); break; }
            String line = stripComment(lines[index]).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1); continue;
            }
            int equals = line.indexOf('=');
            if (equals < 1) { issue(issues, path, index + 1, digest); continue; }
            String key = line.substring(0, equals).trim(), value = line.substring(equals + 1).trim();
            if (!KEY.matcher(key).matches()) { issue(issues, path, index + 1, digest); continue; }
            switch (section) {
                case "versions" -> {
                    Optional<String> literal = quoted(value);
                    if (!seenVersions.add(key) || literal.isEmpty()) {
                        versions.remove(key);
                        issue(issues, path, index + 1, digest);
                    } else versions.put(key, literal.orElseThrow());
                }
                case "libraries" -> {
                    if (rawLibraries.putIfAbsent(key, value) != null) {
                        invalidLibraries.add(key); issue(issues, path, index + 1, digest);
                    }
                }
                case "bundles" -> {
                    if (rawBundles.putIfAbsent(key, value) != null) {
                        invalidBundles.add(key); issue(issues, path, index + 1, digest);
                    }
                }
                case "plugins" -> { /* Plugin aliases are inventoried by their raw catalog digest, never applied. */ }
                default -> issue(issues, path, index + 1, digest);
            }
        }
        Map<String,Optional<MavenCoordinate>> libraries = new TreeMap<>();
        rawLibraries.forEach((alias, value) -> {
            Optional<MavenCoordinate> coordinate = invalidLibraries.contains(alias)
                    ? Optional.empty() : coordinate(value, versions);
            String key = accessor(alias);
            if (libraries.containsKey(key)) {
                libraries.put(key, Optional.empty());
                issue(issues, path, 0, digest);
            } else libraries.put(key, coordinate);
            if (coordinate.isEmpty()) issue(issues, path, 0, digest);
        });
        Map<String,List<String>> bundles = new TreeMap<>();
        rawBundles.forEach((alias, value) -> {
            Optional<List<String>> members = invalidBundles.contains(alias)
                    ? Optional.empty() : array(value);
            String key = accessor(alias);
            if (bundles.containsKey(key)) {
                bundles.put(key, List.of());
                issue(issues, path, 0, digest);
            } else if (members.isPresent()) bundles.put(key, members.orElseThrow().stream()
                    .map(GradleVersionCatalog::accessor).toList());
            else issue(issues, path, 0, digest);
        });
        return new Catalog(libraries, bundles, new BuildModelResult.PomEvidence(path, digest),
                issues.stream().distinct().sorted(Comparator.comparing(IngestionEvidence.Issue::identity)).toList());
    }
    private static Optional<MavenCoordinate> coordinate(String raw, Map<String,String> versions) {
        try {
            if (quoted(raw).isPresent()) return gav(quoted(raw).orElseThrow());
            if (!raw.startsWith("{") || !raw.endsWith("}")) return Optional.empty();
            Map<String,String> fields = new HashMap<>();
            for (String item : raw.substring(1, raw.length() - 1).split(",", -1)) {
                String[] pair = item.split("=", 2);
                if (pair.length != 2 || !Set.of("module", "group", "name", "version", "version.ref")
                        .contains(pair[0].trim())) return Optional.empty();
                var value = quoted(pair[1].trim());
                if (value.isEmpty() || fields.putIfAbsent(pair[0].trim(), value.orElseThrow()) != null)
                    return Optional.empty();
            }
            String module = fields.get("module");
            if (module == null) {
                if (fields.get("group") == null || fields.get("name") == null) return Optional.empty();
                module = fields.get("group") + ":" + fields.get("name");
            } else if (fields.containsKey("group") || fields.containsKey("name")) return Optional.empty();
            String version = fields.containsKey("version.ref") ? versions.get(fields.get("version.ref"))
                    : fields.get("version");
            if (fields.containsKey("version.ref") && fields.containsKey("version")) return Optional.empty();
            return version == null ? Optional.empty() : gav(module + ":" + version);
        } catch (IllegalArgumentException failure) { return Optional.empty(); }
    }
    private static Optional<MavenCoordinate> gav(String notation) {
        String[] values = notation.split(":", -1);
        String version = values.length == 3 ? values[2].toUpperCase(Locale.ROOT) : "";
        if (values.length != 3 || values[2].contains("+") || values[2].startsWith("latest.")
                || values[2].contains("$") || values[2].startsWith("[") || values[2].startsWith("(")
                || values[2].contains("]") || values[2].contains(")")
                || version.endsWith("-SNAPSHOT") || version.equals("LATEST") || version.equals("RELEASE"))
            return Optional.empty();
        try { return Optional.of(new MavenCoordinate(values[0], values[1], values[2])); }
        catch (IllegalArgumentException failure) { return Optional.empty(); }
    }
    private static Optional<List<String>> array(String raw) {
        if (!raw.startsWith("[") || !raw.endsWith("]")) return Optional.empty();
        String content = raw.substring(1, raw.length() - 1).trim();
        if (content.isEmpty()) return Optional.of(List.of());
        var aliases = new ArrayList<String>();
        for (String item : content.split(",", -1)) {
            var literal = quoted(item.trim());
            if (literal.isEmpty() || !KEY.matcher(literal.orElseThrow()).matches()) return Optional.empty();
            aliases.add(literal.orElseThrow());
        }
        return Optional.of(List.copyOf(aliases));
    }
    private static Optional<String> quoted(String value) {
        Matcher matcher = QUOTED.matcher(value);
        return matcher.matches() ? Optional.ofNullable(matcher.group(1) != null ? matcher.group(1) : matcher.group(2))
                : Optional.empty();
    }
    private static String accessor(String alias) { return alias.replace('-', '.').replace('_', '.'); }
    private static String stripComment(String line) {
        boolean single = false, doubled = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !doubled) single = !single;
            else if (c == '"' && !single) doubled = !doubled;
            else if (c == '#' && !single && !doubled) return line.substring(0, i);
        }
        return line;
    }
    private static void issue(List<IngestionEvidence.Issue> issues, String path, int line, ContentDigest digest) {
        issues.add(new IngestionEvidence.Issue(IngestionEvidence.Reason.DYNAMIC_BUILD_LOGIC,
                path + "#" + line, List.of(digest)));
    }
    private GradleVersionCatalog() {}
}
