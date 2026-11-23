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
                   Map<String, List<String>> bundles,Map<String,Optional<Plugin>> plugins, BuildModelResult.PomEvidence evidence,
                   List<IngestionEvidence.Issue> issues) {
        Catalog {
            libraries = Map.copyOf(libraries); bundles = Map.copyOf(bundles);plugins=Map.copyOf(plugins);
            issues = List.copyOf(issues);
        }
    }
    record Plugin(String id,String version) {}
    static Catalog parse(String path, String text, ContentDigest digest, int maxLines) {
        Map<String,String> versions = new HashMap<>(), rawLibraries = new LinkedHashMap<>(), rawBundles = new LinkedHashMap<>(),rawPlugins=new LinkedHashMap<>();
        Set<String> seenVersions = new HashSet<>();
        Set<String> invalidLibraries = new HashSet<>(), invalidBundles = new HashSet<>(),invalidPlugins=new HashSet<>();
        var issues = new ArrayList<IngestionEvidence.Issue>();
        String section = ""; String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index >= maxLines) { issue(issues, path, index + 1, digest); break; }
            int startLine = index + 1;
            String line = stripComment(lines[index]).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim(); continue;
            }
            int equals = line.indexOf('=');
            if (equals < 1) { issue(issues, path, startLine, digest); continue; }
            String key = line.substring(0, equals).trim();
            StringBuilder valueBuilder = new StringBuilder(line.substring(equals + 1).trim());
            int[] depth = delimiterDepth(valueBuilder.toString(), 0, 0);
            boolean incomplete = depth[0] < 0 || depth[1] < 0;
            while (!incomplete && (depth[0] > 0 || depth[1] > 0)) {
                if (++index >= lines.length || index >= maxLines) { incomplete = true; break; }
                String cont = stripComment(lines[index]).trim();
                if (!cont.isEmpty()) {
                    valueBuilder.append(' ').append(cont);
                    depth = delimiterDepth(cont, depth[0], depth[1]);
                    if (depth[0] < 0 || depth[1] < 0) { incomplete = true; break; }
                }
            }
            if (incomplete) { issue(issues, path, startLine, digest); continue; }
            String value = valueBuilder.toString().trim();
            if (!KEY.matcher(key).matches()) { issue(issues, path, startLine, digest); continue; }
            switch (section) {
                case "versions" -> {
                    Optional<String> literal = richVersion(value);
                    if (!seenVersions.add(key) || literal.isEmpty()) {
                        versions.remove(key);
                        issue(issues, path, startLine, digest);
                    } else versions.put(key, literal.orElseThrow());
                }
                case "libraries" -> {
                    if (rawLibraries.putIfAbsent(key, value) != null) {
                        invalidLibraries.add(key); issue(issues, path, startLine, digest);
                    }
                }
                case "bundles" -> {
                    if (rawBundles.putIfAbsent(key, value) != null) {
                        invalidBundles.add(key); issue(issues, path, startLine, digest);
                    }
                }
                case "plugins" -> {if(rawPlugins.putIfAbsent(key,value)!=null){invalidPlugins.add(key);issue(issues,path,startLine,digest);}}
                default -> issue(issues, path, startLine, digest);
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
        Map<String,Optional<Plugin>> plugins=new TreeMap<>();
        rawPlugins.forEach((alias,raw)->{
            Optional<Plugin> plugin=Optional.empty();
            if(!invalidPlugins.contains(alias)) {
                var literal=quoted(raw);
                if(literal.isPresent()) {String[] parts=literal.orElseThrow().split(":",-1);if(parts.length==2&&gav("plugin:marker:"+parts[1]).isPresent())plugin=Optional.of(new Plugin(parts[0],parts[1]));}
                else if(raw.startsWith("{")&&raw.endsWith("}")) {
                    var fields=new HashMap<String,String>();boolean valid=true;
                    for(String part:splitTopLevel(raw.substring(1,raw.length()-1))){String[] pair=part.split("=",2);String k=pair.length==2?pair[0].trim():"";Optional<String> v=pair.length==2?(k.equals("version")?resolveInlineVersion(pair[1].trim(),versions):quoted(pair[1].trim())):Optional.empty();if(!Set.of("id","version","version.ref").contains(k)||v.isEmpty()||fields.putIfAbsent(k,v.orElseThrow())!=null){valid=false;break;}}
                    String version=fields.containsKey("version.ref")?versions.get(fields.get("version.ref")):fields.get("version");
                    if(valid&&!(fields.containsKey("version")&&fields.containsKey("version.ref"))&&fields.get("id")!=null&&version!=null&&gav("plugin:marker:"+version).isPresent())plugin=Optional.of(new Plugin(fields.get("id"),version));
                }
            }
            String accessor=accessor(alias);if(plugins.containsKey(accessor))plugin=Optional.empty();plugins.put(accessor,plugin);
            if(plugin.isEmpty())issue(issues,path,0,digest);
        });
        return new Catalog(libraries, bundles,plugins, new BuildModelResult.PomEvidence(path, digest),
                issues.stream().distinct().sorted(Comparator.comparing(IngestionEvidence.Issue::identity)).toList());
    }
    private static Optional<MavenCoordinate> coordinate(String raw, Map<String,String> versions) {
        try {
            if (quoted(raw).isPresent()) return gav(quoted(raw).orElseThrow());
            if (!raw.startsWith("{") || !raw.endsWith("}")) return Optional.empty();
            Map<String,String> fields = new HashMap<>();
            for (String item : splitTopLevel(raw.substring(1, raw.length() - 1))) {
                String[] pair = item.split("=", 2);
                String k = pair.length == 2 ? pair[0].trim() : "";
                if (pair.length != 2 || !Set.of("module", "group", "name", "version", "version.ref").contains(k))
                    return Optional.empty();
                var value = k.equals("version") ? resolveInlineVersion(pair[1].trim(), versions) : quoted(pair[1].trim());
                if (value.isEmpty() || fields.putIfAbsent(k, value.orElseThrow()) != null)
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
        for (String item : splitTopLevel(content)) {
            var literal = quoted(item.trim());
            if (literal.isEmpty() || !KEY.matcher(literal.orElseThrow()).matches()) return Optional.empty();
            aliases.add(literal.orElseThrow());
        }
        return Optional.of(List.copyOf(aliases));
    }
    private static Optional<String> resolveInlineVersion(String raw, Map<String,String> versions) {
        if (raw.startsWith("{") && raw.endsWith("}")) {
            var fields = new HashMap<String,String>();
            for (String item : splitTopLevel(raw.substring(1, raw.length() - 1))) {
                String[] pair = item.split("=", 2);
                if (pair.length != 2) return Optional.empty();
                var v = quoted(pair[1].trim());
                if (v.isEmpty() || fields.putIfAbsent(pair[0].trim(), v.orElseThrow()) != null) return Optional.empty();
            }
            if (fields.size() == 1 && fields.containsKey("ref")) {
                return Optional.ofNullable(versions.get(fields.get("ref")));
            }
        }
        return richVersion(raw);
    }
    private static Optional<String> richVersion(String raw) {
        Optional<String> literal = quoted(raw);
        if (literal.isPresent()) return literal;
        if (!raw.startsWith("{") || !raw.endsWith("}")) return Optional.empty();
        var fields = new HashMap<String,String>();
        for (String item : splitTopLevel(raw.substring(1, raw.length() - 1))) {
            String[] pair = item.split("=", 2);
            if (pair.length != 2) return Optional.empty();
            String k = pair[0].trim();
            if (!Set.of("require", "strictly", "prefer").contains(k)) return Optional.empty();
            var v = quoted(pair[1].trim());
            if (v.isEmpty() || fields.putIfAbsent(k, v.orElseThrow()) != null) return Optional.empty();
        }
        return fields.size() == 1 ? Optional.of(fields.values().iterator().next()) : Optional.empty();
    }
    private static List<String> splitTopLevel(String body) {
        var items = new ArrayList<String>();
        boolean single = false, doubled = false;
        int brackets = 0, braces = 0, start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'' && !doubled) single = !single;
            else if (c == '"' && !single && (i == 0 || body.charAt(i - 1) != '\\')) doubled = !doubled;
            else if (!single && !doubled) {
                if (c == '[') brackets++;
                else if (c == ']') brackets--;
                else if (c == '{') braces++;
                else if (c == '}') braces--;
                else if (c == ',' && brackets == 0 && braces == 0) {
                    items.add(body.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        String tail = body.substring(start).trim();
        if (!tail.isEmpty() || items.isEmpty()) items.add(tail);
        return items;
    }
    private static int[] delimiterDepth(String text, int brackets, int braces) {
        boolean single = false, doubled = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !doubled) single = !single;
            else if (c == '"' && !single && (i == 0 || text.charAt(i - 1) != '\\')) doubled = !doubled;
            else if (!single && !doubled) {
                if (c == '[') brackets++;
                else if (c == ']') brackets--;
                else if (c == '{') braces++;
                else if (c == '}') braces--;
            }
        }
        return new int[]{brackets, braces};
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
