import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/** Independent raw compiler observations. No candidate adapter or canonical formatter. */
public final class OraclePilot {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("source root and explicit source files required");
        if (Runtime.version().feature() != 21) throw new IllegalArgumentException("JDK 21 required");
        Path root = Path.of(args[0]).toRealPath();
        List<Path> inputs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            Path input = root.resolve(args[i]).toRealPath();
            if (!input.startsWith(root)) throw new IllegalArgumentException("source outside supplied root");
            inputs.add(input);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK compiler unavailable");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            // An empty class/source/module path avoids ambient current-directory/classloader inputs.
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of());
            files.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            files.setLocationFromPaths(StandardLocation.MODULE_PATH, List.of());
            List<String> options = List.of("--release", "21", "-proc:none", "-encoding", "UTF-8");
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics, options, null,
                    files.getJavaFileObjectsFromPaths(inputs));
            task.setProcessors(List.of());
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            task.analyze(); // Deliberately no generate(): no target bytecode or lifecycle execution.
            Trees trees = Trees.instance(task);
            Elements elements = task.getElements();
            Types types = task.getTypes();
            boolean hasErrors = diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
            List<Map<String, Object>> calls = new ArrayList<>();
            List<Map<String, Object>> declarations = new ArrayList<>();
            for (CompilationUnitTree unit : units) {
                String source = unit.getSourceFile().getCharContent(false).toString();
                String file = relative(root, unit.getSourceFile());
                new TreePathScanner<Void, Void>() {
                    @Override public Void visitMethod(MethodTree node, Void unused) {
                        Element element = trees.getElement(getCurrentPath());
                        if (element instanceof ExecutableElement method) {
                            Map<String, Object> row = binding(method, trees, elements, types, files, root, inputs);
                            row.put("file", file);
                            row.put("startUtf16", trees.getSourcePositions().getStartPosition(unit, node));
                            row.put("endUtf16", trees.getSourcePositions().getEndPosition(unit, node));
                            declarations.add(row);
                        }
                        return super.visitMethod(node, unused);
                    }

                    @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        long start = trees.getSourcePositions().getStartPosition(unit, node);
                        long end = trees.getSourcePositions().getEndPosition(unit, node);
                        boolean hasSpan = start >= 0 && end > start && end <= source.length();
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("file", file);
                        row.put("startUtf16", start);
                        row.put("endUtf16", end);
                        row.put("hasSourceSpan", hasSpan);
                        row.put("sourceText", hasSpan ? source.substring((int) start, (int) end) : null);
                        row.put("printedTree", node.toString());
                        row.put("originalStart", hasSpan ? originalPosition(source, (int) start) : null);
                        row.put("originalEnd", hasSpan ? originalPosition(source, (int) end) : null);
                        // Raw compiler columns are retained separately; tab expansion may differ.
                        row.put("compilerStart", start >= 0 ? List.of(unit.getLineMap().getLineNumber(start), unit.getLineMap().getColumnNumber(start)) : null);
                        row.put("compilerEnd", end >= 0 ? List.of(unit.getLineMap().getLineNumber(end), unit.getLineMap().getColumnNumber(end)) : null);
                        row.put("caller", caller(getCurrentPath(), trees));
                        Element element = trees.getElement(getCurrentPath());
                        row.put("rawElementKind", element == null ? null : element.getKind().name());
                        row.put("rawElementText", element == null ? null : element.toString());
                        boolean executable = element instanceof ExecutableElement;
                        row.put("oracleAllowed", !hasErrors && executable && hasSpan);
                        row.put("oracleBlockedReason", hasErrors ? "COMPILATION_HAS_ERROR" : !executable ? "NO_EXECUTABLE_ELEMENT" : !hasSpan ? "MISSING_SOURCE_SPAN" : null);
                        if (element instanceof ExecutableElement method) {
                            Map<String, Object> target = binding(method, trees, elements, types, files, root, inputs);
                            var expressionType = trees.getTypeMirror(getCurrentPath());
                            target.put("expressionType", expressionType == null ? null : expressionType.toString());
                            row.put("target", target);
                        } else row.put("target", null);
                        calls.add(row);
                        return super.visitMethodInvocation(node, unused);
                    }
                }.scan(unit, null);
            }
            List<Map<String, Object>> rawDiagnostics = new ArrayList<>();
            for (var d : diagnostics.getDiagnostics()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", d.getKind().name());
                row.put("code", d.getCode());
                row.put("message", d.getMessage(Locale.ROOT));
                row.put("file", d.getSource() == null ? null : relative(root, d.getSource()));
                row.put("startUtf16", d.getStartPosition());
                row.put("endUtf16", d.getEndPosition());
                row.put("positionUtf16", d.getPosition());
                row.put("line", d.getLineNumber());
                row.put("column", d.getColumnNumber());
                rawDiagnostics.add(row);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", "m2-javac-oracle-raw-v1");
            result.put("javaRuntimeVersion", System.getProperty("java.runtime.version"));
            result.put("javaVendor", System.getProperty("java.vendor"));
            result.put("options", options);
            result.put("classpath", List.of());
            result.put("sourcepath", List.of());
            result.put("modulepath", List.of());
            result.put("hasErrors", hasErrors);
            result.put("diagnostics", rawDiagnostics);
            result.put("declarations", declarations);
            result.put("calls", calls);
            System.out.println(json(result));
        }
    }

    private static Map<String, Object> binding(ExecutableElement method, Trees trees, Elements elements,
            Types types, StandardJavaFileManager files, Path root, List<Path> inputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        TypeElement owner = (TypeElement) method.getEnclosingElement();
        result.put("owner", owner.getQualifiedName().toString());
        result.put("binaryOwner", elements.getBinaryName(owner).toString());
        result.put("name", method.getSimpleName().toString());
        result.put("parameterTypes", method.getParameters().stream().map(p -> p.asType().toString()).toList());
        result.put("erasedParameterTypes", method.getParameters().stream().map(p -> types.erasure(p.asType()).toString()).toList());
        result.put("returnType", method.getReturnType().toString());
        result.put("varargs", method.isVarArgs());
        result.put("rawExecutableType", method.asType().toString());
        var module = elements.getModuleOf(owner);
        result.put("module", module == null ? null : module.getQualifiedName().toString());
        TreePath declaration = trees.getPath(method);
        result.put("origin", "UNKNOWN");
        result.put("originEvidence", null);
        if (declaration != null) {
            var unit = declaration.getCompilationUnit();
            Path declaredFile = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
            if (inputs.contains(declaredFile)) {
                result.put("origin", "PROJECT");
                result.put("originEvidence", Map.of("kind", "INVENTORIED_SOURCE_DECLARATION", "file", relative(root, unit.getSourceFile()),
                        "declarationStartUtf16", trees.getSourcePositions().getStartPosition(unit, declaration.getLeaf()),
                        "declarationEndUtf16", trees.getSourcePositions().getEndPosition(unit, declaration.getLeaf())));
            }
        } else if (module != null && !module.isUnnamed()) {
            // Evidence is membership in the selected compiler platform location, not a package prefix.
            try {
                var location = files.getLocationForModule(StandardLocation.SYSTEM_MODULES, module.getQualifiedName().toString());
                JavaFileObject platform = location == null ? null : files.getJavaFileForInput(location,
                        elements.getBinaryName(owner).toString(), JavaFileObject.Kind.CLASS);
                if (platform != null) {
                    result.put("origin", "JDK");
                    result.put("originEvidence", Map.of("kind", "COMPILER_PLATFORM_DECLARATION", "module", module.getQualifiedName().toString(),
                            "location", location.getName(), "uri", platform.toUri().toString()));
                }
            } catch (java.io.IOException | IllegalArgumentException exception) {
                result.put("originLookupFailure", exception.getClass().getSimpleName());
            }
        }
        return result;
    }

    private static String caller(TreePath path, Trees trees) {
        for (TreePath parent = path.getParentPath(); parent != null; parent = parent.getParentPath()) {
            if (parent.getLeaf() instanceof MethodTree) {
                Element method = trees.getElement(parent);
                return method == null ? null : method.getEnclosingElement() + "." + method;
            }
        }
        return null;
    }

    private static List<Integer> originalPosition(String source, int offset) {
        int line = 1, column = 1;
        for (int i = 0; i < offset; i++) {
            char c = source.charAt(i);
            if (c == '\r') {
                if (i + 1 < offset && source.charAt(i + 1) == '\n') i++;
                line++;
                column = 1;
            } else if (c == '\n') { line++; column = 1; }
            else column++;
        }
        return List.of(line, column);
    }

    private static String relative(Path root, JavaFileObject file) {
        return root.relativize(Path.of(file.toUri()).toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof String text) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> { if (c < 32 || c > 126) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Map<?, ?> map) {
            return "{" + map.entrySet().stream().sorted(java.util.Comparator.comparing(e -> e.getKey().toString()))
                    .map(e -> json(e.getKey().toString()) + ":" + json(e.getValue()))
                    .collect(java.util.stream.Collectors.joining(",")) + "}";
        }
        if (value instanceof List<?> list) return "[" + list.stream().map(OraclePilot::json).collect(java.util.stream.Collectors.joining(",")) + "]";
        throw new IllegalArgumentException("unsupported JSON value");
    }
}
