package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.frontend.FrontendRequest;
import java.util.*;

/**
 * Passive, deterministic, SHA-256-verified parser for captured {@code lombok.config} files.
 *
 * <p>Never reads from the ambient host filesystem. Only configuration text supplied in
 * {@code manifest.configuration().values()} under {@code "java.lombok-config:" + path} whose
 * SHA-256 digest matches the snapshot's {@link SnapshotFile#contentDigest()} is parsed.
 * If an ancestor {@code lombok.config} exists in the snapshot without verified captured text,
 * or sets an unsupported behavioral key, the resolved {@link Effective} config retains that
 * gap explicitly so {@code lombok.configuration-required} is emitted truthfully.
 */
final class LombokConfig {
    static final String CONFIG_OPTION_PREFIX = "java.lombok-config:";

    private static final Set<String> BENIGN_KEYS = Set.of(
            "config.stopbubbling",
            "lombok.addlombokgeneratedannotation",
            "lombok.addnullannotations",
            "lombok.addjavaxgeneratedannotation",
            "lombok.addgeneratedannotation",
            "lombok.addsuppresswarnings",
            "lombok.anyconstructor.addconstructorproperties",
            "lombok.anyconstructor.suppressconstructorproperties",
            "lombok.copyableannotations",
            "lombok.equalsandhashcode.callsuper",
            "lombok.equalsandhashcode.donotusegetters",
            "lombok.tostring.callsuper",
            "lombok.tostring.includefieldnames",
            "lombok.tostring.donotusegetters",
            "lombok.nonnull.exceptiontype",
            "lombok.extern.findbugs.addsuppressfbwarnings",
            "lombok.checkerframework"
    );

    private static final Set<String> HONORED_BEHAVIORAL_KEYS = Set.of(
            "lombok.accessors.chain",
            "lombok.accessors.fluent",
            "lombok.accessors.prefix",
            "lombok.accessors.capitalize",
            "lombok.getter.noisprefix",
            "lombok.log.fieldname",
            "lombok.log.fieldisstatic",
            "lombok.fielddefaults.defaultprivate",
            "lombok.fielddefaults.defaultfinal"
    );

    record Effective(
            boolean opaqueConfigPresent,
            boolean accessorsChain,
            boolean accessorsFluent,
            List<String> accessorsPrefix,
            boolean getterNoIsPrefix,
            String logFieldName,
            boolean logFieldIsStatic,
            boolean fieldDefaultsPrivate,
            boolean fieldDefaultsFinal,
            Set<String> unsupportedKeys) {
        static final Effective DEFAULT = new Effective(
                false, false, false, List.of(), false, "log", true, false, false, Set.of());

        boolean requiresConfigurationGap(
                boolean hasAccessors, boolean hasBuilder, boolean hasLogger, boolean hasConstructor) {
            if (opaqueConfigPresent) return true;
            if (unsupportedKeys.isEmpty()) return false;
            for (String key : unsupportedKeys) {
                if (key.startsWith("lombok.log.")) {
                    if (hasLogger) return true;
                    continue;
                }
                if (key.startsWith("lombok.builder.") || key.startsWith("lombok.superbuilder.")
                        || key.startsWith("lombok.singular.")) {
                    if (hasBuilder) return true;
                    continue;
                }
                if (key.startsWith("lombok.accessors.") || key.startsWith("lombok.getter.")
                        || key.startsWith("lombok.setter.") || key.startsWith("lombok.with.")) {
                    if (hasAccessors) return true;
                    continue;
                }
                if (key.startsWith("lombok.noargsconstructor.") || key.startsWith("lombok.allargsconstructor.")
                        || key.startsWith("lombok.requiredargsconstructor.") || key.startsWith("lombok.anyconstructor.")) {
                    if (hasConstructor) return true;
                    continue;
                }
                // General or unknown key conservatively requires configuration gap.
                return true;
            }
            return false;
        }
    }

    private record ParsedFile(
            boolean stopBubbling,
            List<Directive> directives) {}

    private record Directive(String op, String key, String value) {}

    private final Map<String, ContentDigest> configDigestsByDir;
    private final Map<String, String> verifiedContentByDir;
    private final Map<String, ParsedFile> parsedCache = new HashMap<>();
    private final Map<String, Effective> effectiveByDir = new HashMap<>();

    private LombokConfig(
            Map<String, ContentDigest> configDigestsByDir,
            Map<String, String> verifiedContentByDir) {
        this.configDigestsByDir = configDigestsByDir;
        this.verifiedContentByDir = verifiedContentByDir;
    }

    static LombokConfig from(FrontendRequest request) {
        var digests = new HashMap<String, ContentDigest>();
        for (SnapshotFile f : request.manifest().snapshot().files()) {
            String fp = f.path();
            if (fp.equals("lombok.config")) {
                digests.put("", f.contentDigest());
            } else if (fp.endsWith("/lombok.config")) {
                digests.put(fp.substring(0, fp.lastIndexOf('/') + 1), f.contentDigest());
            }
        }
        if (digests.isEmpty()) {
            return new LombokConfig(Map.of(), Map.of());
        }
        var verified = new HashMap<String, String>();
        var options = request.manifest().configuration().values();
        for (var entry : digests.entrySet()) {
            String dir = entry.getKey();
            String path = dir.isEmpty() ? "lombok.config" : dir + "lombok.config";
            String raw = options.get(CONFIG_OPTION_PREFIX + path);
            if (raw == null) continue;
            if (raw.startsWith("base64:")) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(raw.substring("base64:".length()));
                    if (ContentDigest.sha256(decoded).equals(entry.getValue())) {
                        verified.put(dir, new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (IllegalArgumentException ignored) {}
            } else if (ContentDigest.sha256Utf8(raw).equals(entry.getValue())) {
                verified.put(dir, raw);
            }
        }
        return new LombokConfig(digests, verified);
    }

    boolean isEmpty() {
        return configDigestsByDir.isEmpty();
    }

    Effective forDocumentPath(String documentPath) {
        if (configDigestsByDir.isEmpty()) {
            return Effective.DEFAULT;
        }
        int lastSlash = documentPath.lastIndexOf('/');
        String dir = lastSlash >= 0 ? documentPath.substring(0, lastSlash + 1) : "";
        return effectiveByDir.computeIfAbsent(dir, this::resolveForDirectory);
    }

    private Effective resolveForDirectory(String leafDir) {
        // Walk child -> root so config.stopBubbling = true stops inheritance accurately.
        var activeDirs = new ArrayList<String>();
        String cur = leafDir;
        while (true) {
            if (configDigestsByDir.containsKey(cur)) {
                String text = verifiedContentByDir.get(cur);
                if (text == null) {
                    return new Effective(true, false, false, List.of(), false, "log", true, false, false, Set.of());
                }
                ParsedFile parsed = parsedCache.computeIfAbsent(cur, k -> parse(text));
                activeDirs.add(cur);
                if (parsed.stopBubbling()) {
                    break;
                }
            }
            if (cur.isEmpty()) break;
            int prevSlash = cur.lastIndexOf('/', cur.length() - 2);
            cur = prevSlash >= 0 ? cur.substring(0, prevSlash + 1) : "";
        }
        if (activeDirs.isEmpty()) {
            return Effective.DEFAULT;
        }
        // Apply outermost -> innermost.
        Collections.reverse(activeDirs);
        boolean accessorsChain = false;
        boolean accessorsFluent = false;
        var accessorsPrefix = new ArrayList<String>();
        boolean getterNoIsPrefix = false;
        String logFieldName = "log";
        boolean logFieldIsStatic = true;
        boolean fieldDefaultsPrivate = false;
        boolean fieldDefaultsFinal = false;
        var unsupported = new TreeSet<String>();

        for (String dir : activeDirs) {
            ParsedFile parsed = parsedCache.get(dir);
            for (Directive d : parsed.directives()) {
                String k = d.key();
                if (BENIGN_KEYS.contains(k)) {
                    continue;
                }
                if (!HONORED_BEHAVIORAL_KEYS.contains(k)) {
                    unsupported.add(k);
                    continue;
                }
                switch (k) {
                    case "lombok.accessors.chain" -> {
                        if (d.op().equals("clear")) accessorsChain = false;
                        else {
                            Boolean b = parseBool(d.value());
                            if (b == null) unsupported.add(k);
                            else accessorsChain = b;
                        }
                    }
                    case "lombok.accessors.fluent" -> {
                        if (d.op().equals("clear")) accessorsFluent = false;
                        else {
                            Boolean b = parseBool(d.value());
                            if (b == null) unsupported.add(k);
                            else accessorsFluent = b;
                        }
                    }
                    case "lombok.accessors.prefix" -> {
                        if (d.op().equals("clear")) accessorsPrefix.clear();
                        else if (d.op().equals("+=")) {
                            if (!d.value().isEmpty()) accessorsPrefix.add(d.value());
                        } else if (d.op().equals("-=")) {
                            accessorsPrefix.remove(d.value());
                        } else if (d.op().equals("=")) {
                            accessorsPrefix.clear();
                            if (!d.value().isEmpty()) accessorsPrefix.add(d.value());
                        }
                    }
                    case "lombok.accessors.capitalize" -> {
                        if (d.op().equals("clear") || "basic".equalsIgnoreCase(d.value())) {
                            // Default Lombok capitalization.
                        } else {
                            unsupported.add(k);
                        }
                    }
                    case "lombok.getter.noisprefix" -> {
                        if (d.op().equals("clear")) getterNoIsPrefix = false;
                        else {
                            Boolean b = parseBool(d.value());
                            if (b == null) unsupported.add(k);
                            else getterNoIsPrefix = b;
                        }
                    }
                    case "lombok.log.fieldname" -> {
                        if (d.op().equals("clear")) logFieldName = "log";
                        else if (isValidJavaIdentifier(d.value())) logFieldName = d.value();
                        else unsupported.add(k);
                    }
                    case "lombok.log.fieldisstatic" -> {
                        if (d.op().equals("clear")) logFieldIsStatic = true;
                        else {
                            Boolean b = parseBool(d.value());
                            if (b == null) unsupported.add(k);
                            else logFieldIsStatic = b;
                        }
                    }
                    case "lombok.fielddefaults.defaultprivate" -> {
                        if (d.op().equals("clear")) fieldDefaultsPrivate = false;
                        else {
                            Boolean b = parseBool(d.value());
                            if (b == null) unsupported.add(k);
                            else fieldDefaultsPrivate = b;
                        }
                    }
                    case "lombok.fielddefaults.defaultfinal" -> {
                        if (d.op().equals("clear")) fieldDefaultsFinal = false;
                        else {
                            Boolean b = parseBool(d.value());
                            if (b == null) unsupported.add(k);
                            else fieldDefaultsFinal = b;
                        }
                    }
                    default -> unsupported.add(k);
                }
            }
        }
        return new Effective(
                false,
                accessorsChain,
                accessorsFluent,
                List.copyOf(accessorsPrefix),
                getterNoIsPrefix,
                logFieldName,
                logFieldIsStatic,
                fieldDefaultsPrivate,
                fieldDefaultsFinal,
                Set.copyOf(unsupported));
    }

    private static ParsedFile parse(String text) {
        boolean stopBubbling = false;
        var directives = new ArrayList<Directive>();
        int len = text.length();
        int pos = 0;
        while (pos < len) {
            int nl = text.indexOf('\n', pos);
            String rawLine = nl >= 0 ? text.substring(pos, nl) : text.substring(pos);
            pos = nl >= 0 ? nl + 1 : len;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.regionMatches(true, 0, "clear ", 0, 6)) {
                String key = line.substring(6).trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty()) {
                    if (key.equals("config.stopbubbling")) stopBubbling = false;
                    directives.add(new Directive("clear", key, ""));
                }
                continue;
            }
            int opIdx = -1;
            String op = null;
            int plusEq = line.indexOf("+=");
            int minusEq = line.indexOf("-=");
            int eq = line.indexOf('=');
            if (plusEq >= 0 && (minusEq < 0 || plusEq < minusEq) && (eq < 0 || plusEq < eq)) {
                opIdx = plusEq;
                op = "+=";
            } else if (minusEq >= 0 && (eq < 0 || minusEq < eq)) {
                opIdx = minusEq;
                op = "-=";
            } else if (eq >= 0) {
                opIdx = eq;
                op = "=";
            }
            if (op == null || opIdx <= 0) {
                directives.add(new Directive("invalid", "lombok.config.syntax-error", line));
                continue;
            }
            String key = line.substring(0, opIdx).trim().toLowerCase(Locale.ROOT);
            String val = line.substring(opIdx + op.length()).trim();
            if (key.equals("config.stopbubbling")) {
                Boolean b = parseBool(val);
                if (b != null) {
                    stopBubbling = b;
                    continue;
                }
            }
            directives.add(new Directive(op, key, val));
        }
        return new ParsedFile(stopBubbling, List.copyOf(directives));
    }

    private static Boolean parseBool(String value) {
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        return null;
    }

    private static boolean isValidJavaIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }
}
