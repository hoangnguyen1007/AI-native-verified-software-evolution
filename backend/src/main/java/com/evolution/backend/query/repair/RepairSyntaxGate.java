package com.evolution.backend.query.repair;

import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.treesitter.TreeSitterSourceFrontend;
import com.evolution.backend.query.JavaRepairSyntax;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Pluggable syntax verification gate covering Java ({@code jdk.javac.parse}), Tree-sitter
 * polyglot languages (TypeScript, JavaScript, Python, Go, Kotlin, C#, PHP, Ruby, Rust, C/C++,
 * Scala, Dart, Swift, Protobuf), JSON, YAML, and Java {@code .properties}.
 * Syntax checking is a pre-patch gate, never a substitute for isolated build/test verification.
 */
public final class RepairSyntaxGate {

    public record Diagnostic(String code, String message, long line, long column) {}

    public record CheckResult(
            JavaRepairSyntax.Status status,
            String engine,
            String language,
            List<Diagnostic> diagnostics,
            Optional<JavaRepairSyntax.Check> javaCheck) {
        public CheckResult {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            javaCheck = javaCheck == null ? Optional.empty() : javaCheck;
        }

        public JavaRepairSyntax.Check toSyntaxCheck(JavaRepairSyntax.Profile fallbackProfile) {
            if (javaCheck.isPresent()) return javaCheck.orElseThrow();
            var errs = diagnostics.stream()
                    .map(d -> new JavaRepairSyntax.Error(d.code(), d.line(), d.column()))
                    .toList();
            return new JavaRepairSyntax.Check(status, engine, Runtime.version().toString(), fallbackProfile, errs);
        }
    }

    private static final Map<String, String> EXT_TO_TS_LANG = Map.ofEntries(
            Map.entry(".ts", "typescript"),
            Map.entry(".mts", "typescript"),
            Map.entry(".cts", "typescript"),
            Map.entry(".tsx", "tsx"),
            Map.entry(".js", "javascript"),
            Map.entry(".mjs", "javascript"),
            Map.entry(".cjs", "javascript"),
            Map.entry(".jsx", "javascript"),
            Map.entry(".py", "python"),
            Map.entry(".go", "go"),
            Map.entry(".kt", "kotlin"),
            Map.entry(".kts", "kotlin"),
            Map.entry(".cs", "csharp"),
            Map.entry(".php", "php"),
            Map.entry(".rb", "ruby"),
            Map.entry(".rs", "rust"),
            Map.entry(".c", "c"),
            Map.entry(".h", "c"),
            Map.entry(".cpp", "cpp"),
            Map.entry(".cc", "cpp"),
            Map.entry(".cxx", "cpp"),
            Map.entry(".hpp", "cpp"),
            Map.entry(".scala", "scala"),
            Map.entry(".dart", "dart"),
            Map.entry(".swift", "swift"),
            Map.entry(".proto", "protobuf"));

    private static final Set<String> CONFIG_EXTS = Set.of(".json", ".yaml", ".yml", ".properties");

    private RepairSyntaxGate() {}

    public static boolean supportsPath(String path) {
        if (path == null || path.isBlank()) return false;
        String low = path.toLowerCase(Locale.ROOT);
        if (low.endsWith(".java")) return true;
        String ext = extension(low);
        return EXT_TO_TS_LANG.containsKey(ext) || CONFIG_EXTS.contains(ext);
    }

    public static String languageForPath(String path) {
        if (path == null) return "unknown";
        String low = path.toLowerCase(Locale.ROOT);
        if (low.endsWith(".java")) return "java";
        String ext = extension(low);
        if (EXT_TO_TS_LANG.containsKey(ext)) return EXT_TO_TS_LANG.get(ext);
        return switch (ext) {
            case ".json" -> "json";
            case ".yaml", ".yml" -> "yaml";
            case ".properties" -> "properties";
            default -> "unknown";
        };
    }

    public static CheckResult check(String path, String text, Optional<JavaRepairSyntax.Profile> javaProfile) {
        if (path == null || text == null) {
            return new CheckResult(JavaRepairSyntax.Status.REJECTED, "syntax.input", "unknown",
                    List.of(new Diagnostic("syntax.null", "Path or text is null", -1, -1)), Optional.empty());
        }
        String low = path.toLowerCase(Locale.ROOT);
        if (low.endsWith(".java")) {
            var profile = javaProfile.orElseGet(JavaRepairSyntax::defaultProfile);
            var detailed = JavaRepairSyntax.parseDetailed(text, profile);
            var diags = detailed.details().stream()
                    .map(d -> new Diagnostic(d.code(), d.message(), d.line(), d.column()))
                    .toList();
            return new CheckResult(detailed.check().status(), detailed.check().engine(), "java",
                    diags, Optional.of(detailed.check()));
        }
        String ext = extension(low);
        if (".json".equals(ext)) return checkJson(text);
        if (".properties".equals(ext)) return checkProperties(text);
        if (".yaml".equals(ext) || ".yml".equals(ext)) return checkYaml(text);
        String tsLang = EXT_TO_TS_LANG.get(ext);
        if (tsLang != null) return checkTreeSitter(tsLang, text);
        return new CheckResult(JavaRepairSyntax.Status.UNAVAILABLE, "syntax.unsupported", "unknown",
                List.of(new Diagnostic("syntax.unsupported-language", "Unsupported file extension: " + ext, -1, -1)),
                Optional.empty());
    }

    private static CheckResult checkJson(String text) {
        try {
            GraphJson.read(text, new GraphJson.Limits(2_000_000, 64, 200_000));
            return new CheckResult(JavaRepairSyntax.Status.PASSED, "syntax.json", "json", List.of(), Optional.empty());
        } catch (IllegalArgumentException invalid) {
            return new CheckResult(JavaRepairSyntax.Status.REJECTED, "syntax.json", "json",
                    List.of(new Diagnostic("json.syntax-error",
                            invalid.getMessage() == null ? "Invalid JSON syntax" : invalid.getMessage(), -1, -1)),
                    Optional.empty());
        }
    }

    private static CheckResult checkProperties(String text) {
        try {
            var props = new Properties();
            props.load(new StringReader(text));
            return new CheckResult(JavaRepairSyntax.Status.PASSED, "syntax.properties", "properties", List.of(), Optional.empty());
        } catch (IOException | IllegalArgumentException invalid) {
            return new CheckResult(JavaRepairSyntax.Status.REJECTED, "syntax.properties", "properties",
                    List.of(new Diagnostic("properties.syntax-error",
                            invalid.getMessage() == null ? "Invalid properties syntax" : invalid.getMessage(), -1, -1)),
                    Optional.empty());
        }
    }

    private static CheckResult checkYaml(String text) {
        var diags = new ArrayList<Diagnostic>();
        String[] lines = text.split("\\r?\\n", -1);
        int braceDepth = 0;
        int bracketDepth = 0;
        boolean inBlockScalar = false;
        int blockIndent = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNo = i + 1;
            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                if (line.charAt(indent) == '\t' && !inBlockScalar) {
                    diags.add(new Diagnostic("yaml.tab-indent", "Tabs are not allowed for YAML indentation", lineNo, indent + 1));
                    break;
                }
                indent++;
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (inBlockScalar) {
                if (indent > blockIndent) continue;
                inBlockScalar = false;
                blockIndent = -1;
            }
            if (trimmed.endsWith("|") || trimmed.endsWith(">") || trimmed.endsWith("|-") || trimmed.endsWith(">-")) {
                inBlockScalar = true;
                blockIndent = indent;
                continue;
            }
            // Check balanced quotes and flow brackets on non-comment part of line
            boolean inSingle = false, inDouble = false, esc = false;
            for (int c = 0; c < trimmed.length(); c++) {
                char ch = trimmed.charAt(c);
                if (esc) { esc = false; continue; }
                if (inDouble) {
                    if (ch == '\\') esc = true;
                    else if (ch == '"') inDouble = false;
                } else if (inSingle) {
                    if (ch == '\'') inSingle = false;
                } else {
                    if (ch == '#') break;
                    if (ch == '"') inDouble = true;
                    else if (ch == '\'') inSingle = true;
                    else if (ch == '{') braceDepth++;
                    else if (ch == '}') braceDepth--;
                    else if (ch == '[') bracketDepth++;
                    else if (ch == ']') bracketDepth--;
                    if (braceDepth < 0 || bracketDepth < 0) {
                        diags.add(new Diagnostic("yaml.unbalanced-delimiter", "Unexpected closing delimiter '" + ch + "'", lineNo, c + 1));
                        break;
                    }
                }
            }
            if (inSingle || inDouble) {
                diags.add(new Diagnostic("yaml.unclosed-quote", "Unclosed string literal", lineNo, 1));
            }
            if (diags.size() >= 10) break;
        }
        if (braceDepth != 0 || bracketDepth != 0) {
            diags.add(new Diagnostic("yaml.unbalanced-flow", "Unclosed flow mapping or sequence", lines.length, 1));
        }
        var status = diags.isEmpty() ? JavaRepairSyntax.Status.PASSED : JavaRepairSyntax.Status.REJECTED;
        return new CheckResult(status, "syntax.yaml", "yaml", diags, Optional.empty());
    }

    private static CheckResult checkTreeSitter(String tsLang, String text) {
        var frontend = new TreeSitterSourceFrontend();
        var recoveredDiags = new ArrayList<Diagnostic>();
        try {
            var summary = frontend.parse(tsLang, text.getBytes(StandardCharsets.UTF_8), fact -> {
                if (fact.recovered() && recoveredDiags.size() < 12) {
                    recoveredDiags.add(new Diagnostic(
                            "treesitter.syntax-error",
                            "Syntax error near " + fact.nodeKind() + (fact.name().isBlank() ? "" : (" '" + fact.name() + "'")),
                            fact.span().startLine(),
                            fact.span().startColumn()));
                }
            }, () -> false);
            if (summary.errors() > 0) {
                if (recoveredDiags.isEmpty()) {
                    recoveredDiags.add(new Diagnostic("treesitter.syntax-error",
                            "Tree-sitter reported " + summary.errors() + " syntax error(s) in " + tsLang, 1, 1));
                }
                return new CheckResult(JavaRepairSyntax.Status.REJECTED, summary.toolIdentity(), tsLang, recoveredDiags, Optional.empty());
            }
            return new CheckResult(JavaRepairSyntax.Status.PASSED, summary.toolIdentity(), tsLang, List.of(), Optional.empty());
        } catch (IOException | RuntimeException unavailable) {
            return new CheckResult(JavaRepairSyntax.Status.UNAVAILABLE, "treesitter." + tsLang, tsLang,
                    List.of(new Diagnostic("treesitter.unavailable",
                            unavailable.getMessage() == null ? "Tree-sitter grammar unavailable" : unavailable.getMessage(), -1, -1)),
                    Optional.empty());
        }
    }

    private static String extension(String lowerPath) {
        int slash = lowerPath.lastIndexOf('/');
        int dot = lowerPath.lastIndexOf('.');
        return dot > slash ? lowerPath.substring(dot) : "";
    }
}
