package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast lexical structural scanner for Stage 1 progressive skeleton previews.
 *
 * <p>Extracts packages, imports, top-level and nested type declarations with 1-based line spans,
 * architectural annotation hints, and internal explicit-import dependencies without invoking
 * JavaParser AST parsing or SymbolSolver resolution. All outputs are explicitly preliminary and
 * deterministic (sorted by repository path and symbol name).
 */
public final class FastJavaSkeletonScanner {
    private static final int PARALLEL_THRESHOLD = 16;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;

    private static final ExecutorService SCAN_POOL = Executors.newFixedThreadPool(
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())),
            runnable -> {
                var thread = new Thread(runnable, "evo-fast-skeleton-scanner");
                thread.setDaemon(true);
                return thread;
            });

    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*;?");

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?m)^\\s*import\\s+(static\\s+)?([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*(?:\\.\\*)?)\\s*;?");

    private static final Pattern ANNOTATION_PATTERN = Pattern.compile(
            "@([A-Za-z_$][A-Za-z0-9_$.]*)");

    private static final Pattern TYPE_DECL_PATTERN = Pattern.compile(
            "\\b(@interface|class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");

    private static final Pattern KOTLIN_TYPE_DECL_PATTERN = Pattern.compile(
            "\\b(class|interface|enum|object)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");

    private static final Set<String> ARCHITECTURAL_ANNOTATIONS = Set.of(
            "SpringBootApplication",
            "RestController",
            "Controller",
            "Service",
            "Repository",
            "Configuration",
            "Component",
            "Entity",
            "Table",
            "FeignClient",
            "RequestMapping",
            "GetMapping",
            "PostMapping",
            "PutMapping",
            "DeleteMapping",
            "PatchMapping",
            "Bean",
            "Aspect",
            "ControllerAdvice",
            "RestControllerAdvice",
            "ConfigurationProperties",
            "EnableAutoConfiguration",
            "Scheduled",
            "EventListener",
            "KafkaListener",
            "RabbitListener",
            "GrpcService",
            "GraphQlController",
            "WebFilter",
            "WebServlet",
            "Path",
            "ApplicationScoped",
            "Singleton",
            "Named",
            "Inject");

    public record SourceFile(String path, String modulePath, ContentDigest contentDigest, String sourceText) {
        public SourceFile {
            path = normalizePath(Objects.requireNonNull(path, "path"));
            modulePath = normalizeModulePath(modulePath == null ? "." : modulePath);
            Objects.requireNonNull(contentDigest, "contentDigest");
            sourceText = Objects.requireNonNull(sourceText, "sourceText");
        }

        public static SourceFile fromBytes(String path, String modulePath, ContentDigest contentDigest, byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            return new SourceFile(path, modulePath, contentDigest, new String(bytes, StandardCharsets.UTF_8));
        }
    }

    /** Lazy byte-backed source descriptor so Stage 1 scans never hold all raw file texts in RAM at once. */
    public record LazySource(String path, String modulePath, ContentDigest contentDigest,
            java.util.function.Supplier<byte[]> bytesSupplier) {
        public LazySource {
            path = normalizePath(Objects.requireNonNull(path, "path"));
            modulePath = normalizeModulePath(modulePath == null ? "." : modulePath);
            Objects.requireNonNull(contentDigest, "contentDigest");
            Objects.requireNonNull(bytesSupplier, "bytesSupplier");
        }
    }

    public record TypeDeclaration(
            String simpleName,
            String qualifiedName,
            String kind,
            boolean nested,
            int startLine,
            int endLine,
            List<String> annotations) {
        public TypeDeclaration {
            Objects.requireNonNull(simpleName, "simpleName");
            Objects.requireNonNull(qualifiedName, "qualifiedName");
            Objects.requireNonNull(kind, "kind");
            if (startLine < 1 || endLine < startLine) {
                throw new IllegalArgumentException("Invalid type line span: " + startLine + ".." + endLine);
            }
            annotations = List.copyOf(annotations);
        }
    }

    public record ScannedFile(
            String path,
            String modulePath,
            ContentDigest contentDigest,
            String language,
            String packageName,
            List<String> explicitImports,
            List<String> wildcardImports,
            List<TypeDeclaration> types,
            List<String> architecturalRoles) {
        public ScannedFile {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(modulePath, "modulePath");
            Objects.requireNonNull(contentDigest, "contentDigest");
            Objects.requireNonNull(language, "language");
            packageName = packageName == null ? "" : packageName;
            explicitImports = List.copyOf(explicitImports);
            wildcardImports = List.copyOf(wildcardImports);
            types = List.copyOf(types);
            architecturalRoles = List.copyOf(architecturalRoles);
        }
    }

    public record PreliminaryDependency(
            String sourceType,
            String targetType,
            String sourceModule,
            String targetModule,
            String sourcePackage,
            String targetPackage,
            String sourcePath,
            String importKind) {
        public PreliminaryDependency {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(sourceModule, "sourceModule");
            Objects.requireNonNull(targetModule, "targetModule");
            sourcePackage = sourcePackage == null ? "" : sourcePackage;
            targetPackage = targetPackage == null ? "" : targetPackage;
            Objects.requireNonNull(sourcePath, "sourcePath");
            Objects.requireNonNull(importKind, "importKind");
        }
    }

    public record ScanResult(
            List<ScannedFile> files,
            List<PreliminaryDependency> dependencies,
            Map<String, Integer> packageFileCounts,
            Map<String, Integer> roleCounts,
            long elapsedMillis,
            boolean truncated) {
        public ScanResult {
            files = List.copyOf(files);
            dependencies = List.copyOf(dependencies);
            packageFileCounts = Collections.unmodifiableMap(new TreeMap<>(packageFileCounts));
            roleCounts = Collections.unmodifiableMap(new TreeMap<>(roleCounts));
        }

        public int totalTypes() {
            int total = 0;
            for (var file : files) {
                total += file.types().size();
            }
            return total;
        }
    }

    public ScanResult scan(Collection<SourceFile> sources, BooleanSupplier cancelled) {
        Objects.requireNonNull(sources, "sources");
        BooleanSupplier cancelCheck = cancelled == null ? () -> false : cancelled;
        long startNanos = System.nanoTime();
        checkCancelled(cancelCheck);

        var eligible = new ArrayList<SourceFile>(sources.size());
        for (var source : sources) {
            if (source != null && isSupportedSourcePath(source.path())) {
                eligible.add(source);
            }
        }
        eligible.sort(Comparator.comparing(SourceFile::path));

        List<ScannedFile> scannedFiles;
        if (eligible.size() <= PARALLEL_THRESHOLD) {
            scannedFiles = new ArrayList<>(eligible.size());
            for (var source : eligible) {
                checkCancelled(cancelCheck);
                scannedFiles.add(scanOne(source));
            }
        } else {
            scannedFiles = scanParallel(eligible, cancelCheck);
        }

        scannedFiles.sort(Comparator.comparing(ScannedFile::path));
        return finalizeScanResult(scannedFiles, cancelCheck, startNanos);
    }

    /**
     * Streams and scans lazy sources in bounded 128-file chunks so large repositories release raw
     * source Strings immediately after producing compact {@link ScannedFile} skeletons.
     */
    public ScanResult scanLazy(Collection<LazySource> sources, BooleanSupplier cancelled) {
        Objects.requireNonNull(sources, "sources");
        BooleanSupplier cancelCheck = cancelled == null ? () -> false : cancelled;
        long startNanos = System.nanoTime();
        checkCancelled(cancelCheck);

        var eligible = new ArrayList<LazySource>(sources.size());
        for (var source : sources) {
            if (source != null && isSupportedSourcePath(source.path())) {
                eligible.add(source);
            }
        }
        eligible.sort(Comparator.comparing(LazySource::path));

        var scannedFiles = new ArrayList<ScannedFile>(eligible.size());
        int chunkLimit = 128;
        for (int start = 0; start < eligible.size(); start += chunkLimit) {
            checkCancelled(cancelCheck);
            int end = Math.min(eligible.size(), start + chunkLimit);
            var chunk = new ArrayList<SourceFile>(end - start);
            for (int i = start; i < end; i++) {
                checkCancelled(cancelCheck);
                var lazy = eligible.get(i);
                byte[] bytes = lazy.bytesSupplier().get();
                if (bytes != null && bytes.length <= MAX_FILE_BYTES) {
                    chunk.add(SourceFile.fromBytes(lazy.path(), lazy.modulePath(), lazy.contentDigest(), bytes));
                }
            }
            if (chunk.size() <= PARALLEL_THRESHOLD) {
                for (var sf : chunk) {
                    checkCancelled(cancelCheck);
                    scannedFiles.add(scanOne(sf));
                }
            } else {
                scannedFiles.addAll(scanParallel(chunk, cancelCheck));
            }
        }
        scannedFiles.sort(Comparator.comparing(ScannedFile::path));
        return finalizeScanResult(scannedFiles, cancelCheck, startNanos);
    }

    private static ScanResult finalizeScanResult(List<ScannedFile> scannedFiles, BooleanSupplier cancelCheck,
            long startNanos) {
        var dependencies = resolveInternalDependencies(scannedFiles, cancelCheck);
        var packageCounts = new TreeMap<String, Integer>();
        var roleCounts = new TreeMap<String, Integer>();
        for (var file : scannedFiles) {
            packageCounts.merge(file.packageName(), 1, Integer::sum);
            for (var role : file.architecturalRoles()) {
                roleCounts.merge(role, 1, Integer::sum);
            }
        }
        long elapsedMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        return new ScanResult(scannedFiles, dependencies, packageCounts, roleCounts, elapsedMs, false);
    }

    /**
     * Convenience scanner over a captured checkout directory when callers hold repository-relative paths.
     */
    public ScanResult scanDirectory(Path root, Collection<String> relativePaths, Map<String, String> pathToModule,
            BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(relativePaths, "relativePaths");
        BooleanSupplier cancelCheck = cancelled == null ? () -> false : cancelled;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        var sources = new ArrayList<SourceFile>();
        var sortedPaths = relativePaths.stream()
                .filter(Objects::nonNull)
                .map(FastJavaSkeletonScanner::normalizePath)
                .filter(FastJavaSkeletonScanner::isSupportedSourcePath)
                .distinct()
                .sorted()
                .toList();
        for (var rel : sortedPaths) {
            checkCancelled(cancelCheck);
            Path file = normalizedRoot.resolve(rel).normalize();
            if (!file.startsWith(normalizedRoot) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                continue;
            }
            byte[] bytes = Files.readAllBytes(file);
            String modulePath = pathToModule != null && pathToModule.containsKey(rel)
                    ? pathToModule.get(rel)
                    : inferModulePath(rel);
            sources.add(SourceFile.fromBytes(rel, modulePath, ContentDigest.sha256(bytes), bytes));
        }
        return scan(sources, cancelCheck);
    }

    private static List<ScannedFile> scanParallel(List<SourceFile> sources, BooleanSupplier cancelled) {
        int chunkSize = Math.max(8, (sources.size() + 7) / 8);
        var tasks = new ArrayList<Callable<List<ScannedFile>>>();
        for (int start = 0; start < sources.size(); start += chunkSize) {
            int from = start;
            int to = Math.min(sources.size(), start + chunkSize);
            tasks.add(() -> {
                var chunk = new ArrayList<ScannedFile>(to - from);
                for (int i = from; i < to; i++) {
                    checkCancelled(cancelled);
                    chunk.add(scanOne(sources.get(i)));
                }
                return chunk;
            });
        }
        var futures = new ArrayList<Future<List<ScannedFile>>>(tasks.size());
        try {
            for (var task : tasks) {
                futures.add(SCAN_POOL.submit(task));
            }
            var result = new ArrayList<ScannedFile>(sources.size());
            for (var future : futures) {
                checkCancelled(cancelled);
                result.addAll(future.get());
            }
            return result;
        } catch (InterruptedException interrupted) {
            futures.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            throw new CancellationException("Skeleton scan interrupted");
        } catch (ExecutionException failed) {
            futures.forEach(f -> f.cancel(true));
            if (failed.getCause() instanceof CancellationException cancel) {
                throw cancel;
            }
            if (failed.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Skeleton scan worker failed", failed.getCause());
        } catch (CancellationException cancel) {
            futures.forEach(f -> f.cancel(true));
            throw cancel;
        }
    }

    static ScannedFile scanOne(SourceFile source) {
        boolean kotlin = source.path().toLowerCase(Locale.ROOT).endsWith(".kt");
        String language = kotlin ? "KOTLIN" : "JAVA";
        String stripped = stripCommentsAndLiterals(source.sourceText(), kotlin);
        int[] lineOffsets = computeLineOffsets(stripped);

        String packageName = "";
        Matcher pkgMatcher = PACKAGE_PATTERN.matcher(stripped);
        if (pkgMatcher.find()) {
            packageName = pkgMatcher.group(1).trim();
        }

        var explicitImports = new TreeSet<String>();
        var wildcardImports = new TreeSet<String>();
        Matcher importMatcher = IMPORT_PATTERN.matcher(stripped);
        while (importMatcher.find()) {
            boolean isStatic = importMatcher.group(1) != null;
            String target = importMatcher.group(2).trim();
            if (target.endsWith(".*")) {
                String prefix = target.substring(0, target.length() - 2);
                if (isStatic) {
                    // import static pkg.Type.* -> type dependency on pkg.Type
                    explicitImports.add(prefix);
                } else {
                    wildcardImports.add(prefix);
                }
            } else if (isStatic) {
                int lastDot = target.lastIndexOf('.');
                if (lastDot > 0) {
                    explicitImports.add(target.substring(0, lastDot));
                }
            } else {
                explicitImports.add(target);
            }
        }

        var types = extractTypes(stripped, lineOffsets, packageName, kotlin);
        var roles = new TreeSet<String>();
        for (var type : types) {
            for (var ann : type.annotations()) {
                if (ARCHITECTURAL_ANNOTATIONS.contains(ann)) {
                    roles.add(ann);
                }
            }
        }
        return new ScannedFile(
                source.path(),
                source.modulePath(),
                source.contentDigest(),
                language,
                packageName,
                List.copyOf(explicitImports),
                List.copyOf(wildcardImports),
                types,
                List.copyOf(roles));
    }

    private static List<TypeDeclaration> extractTypes(String stripped, int[] lineOffsets, String packageName,
            boolean kotlin) {
        var found = new ArrayList<TypeDeclaration>();
        // Track enclosing type scopes as (qualifiedName, braceDepthOfBody)
        Deque<TypeScope> activeScopes = new ArrayDeque<>();

        // Precompute type declaration matches sorted by start offset.
        var matches = new ArrayList<TypeMatch>();
        Matcher m = (kotlin ? KOTLIN_TYPE_DECL_PATTERN : TYPE_DECL_PATTERN).matcher(stripped);
        while (m.find()) {
            int kwStart = m.start(1);
            // Ignore ".class" literals (e.g. Foo.class) or variable/identifier prefix
            if (isPrecededByDot(stripped, kwStart)) {
                continue;
            }
            String keyword = m.group(1);
            String name = m.group(2);
            // Validate declaration header: after name (and optional generics/extends/implements/permits/record args)
            // there must be '{' (or for Kotlin, optional body or newline/EOF before ';').
            int bodyOpen = findDeclarationBodyOpen(stripped, m.end(2));
            if (bodyOpen < 0 && (!kotlin || !isBodylessKotlinType(stripped, m.end(2)))) {
                continue;
            }
            String kind = switch (keyword) {
                case "@interface" -> "ANNOTATION";
                case "interface" -> "INTERFACE";
                case "enum" -> "ENUM";
                case "record" -> "RECORD";
                case "object" -> "OBJECT";
                default -> "CLASS";
            };
            matches.add(new TypeMatch(kwStart, m.end(2), bodyOpen, kind, name));
        }

        if (matches.isEmpty()) {
            return List.of();
        }

        // Single-pass O(N) brace match table avoids O(types * fileLength) rescanning on large files.
        int[] matchingClose = computeMatchingCloseBraces(stripped);

        int matchIdx = 0;
        int braceDepth = 0;
        int n = stripped.length();
        for (int i = 0; i < n; i++) {
            while (matchIdx < matches.size() && matches.get(matchIdx).kwStart() == i) {
                var tm = matches.get(matchIdx++);
                // Determine enclosing type (top of scope stack)
                boolean nested = !activeScopes.isEmpty();
                String qualifiedName;
                if (nested) {
                    qualifiedName = activeScopes.peek().qualifiedName + "." + tm.name();
                } else {
                    qualifiedName = packageName.isEmpty() ? tm.name() : packageName + "." + tm.name();
                }
                int startLine = lineOfOffset(lineOffsets, tm.kwStart());
                List<String> annotations = extractPrecedingAnnotations(stripped, tm.kwStart());
                if (tm.bodyOpen() >= 0) {
                    int endOffset = matchingClose[tm.bodyOpen()];
                    int endLine = lineOfOffset(lineOffsets, endOffset >= 0 ? endOffset : tm.bodyOpen());
                    found.add(new TypeDeclaration(tm.name(), qualifiedName, tm.kind(), nested, startLine,
                            Math.max(startLine, endLine), annotations));
                    // Push scope that will become active once braceDepth enters bodyOpen
                    activeScopes.push(new TypeScope(qualifiedName, braceDepth + 1, tm.bodyOpen(),
                            endOffset >= 0 ? endOffset : n));
                } else {
                    int endLine = lineOfOffset(lineOffsets, tm.nameEnd());
                    found.add(new TypeDeclaration(tm.name(), qualifiedName, tm.kind(), nested, startLine,
                            Math.max(startLine, endLine), annotations));
                }
            }
            char c = stripped.charAt(i);
            if (c == '{') {
                braceDepth++;
            } else if (c == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
                while (!activeScopes.isEmpty() && i >= activeScopes.peek().closeOffset) {
                    activeScopes.pop();
                }
            }
        }

        found.sort(Comparator.comparingInt(TypeDeclaration::startLine)
                .thenComparing(TypeDeclaration::qualifiedName));
        return found;
    }

    private static int[] computeMatchingCloseBraces(String text) {
        int n = text.length();
        int[] match = new int[n];
        java.util.Arrays.fill(match, -1);
        int[] stack = new int[Math.min(64, Math.max(16, n / 32))];
        int top = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '{') {
                if (top == stack.length) {
                    stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                }
                stack[top++] = i;
            } else if (c == '}' && top > 0) {
                match[stack[--top]] = i;
            }
        }
        return match;
    }

    private record TypeMatch(int kwStart, int nameEnd, int bodyOpen, String kind, String name) {
    }

    private record TypeScope(String qualifiedName, int bodyDepth, int openOffset, int closeOffset) {
    }

    private static boolean isPrecededByDot(String text, int offset) {
        int i = offset - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        return i >= 0 && text.charAt(i) == '.';
    }

    private static int findDeclarationBodyOpen(String text, int from) {
        int parenDepth = 0;
        int angleDepth = 0;
        int limit = Math.min(text.length(), from + 2048);
        for (int i = from; i < limit; i++) {
            char c = text.charAt(i);
            if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                if (parenDepth > 0) parenDepth--;
            } else if (c == '<') {
                angleDepth++;
            } else if (c == '>') {
                if (angleDepth > 0) angleDepth--;
            } else if (c == '{' && parenDepth == 0 && angleDepth == 0) {
                return i;
            } else if ((c == ';' || c == '=') && parenDepth == 0 && angleDepth == 0) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isBodylessKotlinType(String text, int from) {
        int i = from;
        int parenDepth = 0;
        int limit = Math.min(text.length(), from + 256);
        while (i < limit) {
            char c = text.charAt(i);
            if (c == '(') parenDepth++;
            else if (c == ')' && parenDepth > 0) parenDepth--;
            else if (c == '\n' && parenDepth == 0) return true;
            else if (c == ';' || c == '=') return false;
            i++;
        }
        return i == text.length();
    }

    private static int findMatchingCloseBrace(String text, int openBraceOffset) {
        int depth = 0;
        for (int i = openBraceOffset; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> extractPrecedingAnnotations(String stripped, int keywordOffset) {
        // Walk backwards from keywordOffset across modifiers/annotations until hitting ';', '{', '}', or start.
        int start = keywordOffset - 1;
        int parenDepth = 0;
        int braceDepth = 0;
        int limit = Math.max(0, keywordOffset - 1500);
        while (start >= limit) {
            char c = stripped.charAt(start);
            if (c == ')') {
                parenDepth++;
            } else if (c == '(') {
                if (parenDepth > 0) parenDepth--;
                else break;
            } else if (c == '}') {
                if (parenDepth > 0) braceDepth++;
                else break;
            } else if (c == '{') {
                if (braceDepth > 0) braceDepth--;
                else break;
            } else if (c == ';' && parenDepth == 0 && braceDepth == 0) {
                break;
            }
            start--;
        }
        String header = stripped.substring(Math.max(0, start + 1), keywordOffset);
        var result = new LinkedHashSet<String>();
        Matcher m = ANNOTATION_PATTERN.matcher(header);
        while (m.find()) {
            String raw = m.group(1);
            if ("interface".equals(raw)) {
                continue;
            }
            int lastDot = raw.lastIndexOf('.');
            String simple = lastDot >= 0 ? raw.substring(lastDot + 1) : raw;
            if (!simple.isEmpty()) {
                result.add(simple);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Replaces comments, string literals, text blocks, and char literals with spaces while preserving
     * exact newline positions so 1-based line numbers remain untouched.
     */
    static String stripCommentsAndLiterals(String input) {
        return stripCommentsAndLiterals(input, false);
    }

    static String stripCommentsAndLiterals(String input, boolean kotlin) {
        int n = input.length();
        var out = new char[n];
        int i = 0;
        while (i < n) {
            char c = input.charAt(i);
            char next = i + 1 < n ? input.charAt(i + 1) : 0;
            // Line comment //
            if (c == '/' && next == '/') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && input.charAt(i) != '\n') {
                    out[i++] = ' ';
                }
                continue;
            }
            // Block comment /* ... */ (supports nested comments in Kotlin)
            if (c == '/' && next == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                int depth = 1;
                while (i < n && depth > 0) {
                    char bc = input.charAt(i);
                    if (kotlin && bc == '/' && i + 1 < n && input.charAt(i + 1) == '*') {
                        out[i++] = ' ';
                        out[i++] = ' ';
                        depth++;
                        continue;
                    }
                    if (bc == '*' && i + 1 < n && input.charAt(i + 1) == '/') {
                        out[i++] = ' ';
                        out[i++] = ' ';
                        depth--;
                        continue;
                    }
                    out[i++] = bc == '\n' ? '\n' : ' ';
                }
                continue;
            }
            // Text block / Kotlin raw string """ ... """
            if (c == '"' && i + 2 < n && input.charAt(i + 1) == '"' && input.charAt(i + 2) == '"') {
                out[i++] = ' ';
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n) {
                    char tc = input.charAt(i);
                    if (!kotlin && tc == '\\' && i + 1 < n) {
                        out[i++] = ' ';
                        char esc = input.charAt(i);
                        out[i++] = esc == '\n' ? '\n' : ' ';
                        continue;
                    }
                    if (tc == '"' && i + 2 < n && input.charAt(i + 1) == '"' && input.charAt(i + 2) == '"') {
                        // In Java text blocks & Kotlin raw strings, trailing extra quotes (e.g. """")
                        // belong to the literal body until the final three quotes close it.
                        while (i + 3 < n && input.charAt(i + 3) == '"') {
                            out[i++] = ' ';
                        }
                        out[i++] = ' ';
                        out[i++] = ' ';
                        out[i++] = ' ';
                        break;
                    }
                    out[i++] = tc == '\n' ? '\n' : ' ';
                }
                continue;
            }
            // String literal "..."
            if (c == '"') {
                out[i++] = ' ';
                while (i < n) {
                    char sc = input.charAt(i);
                    if (sc == '\\' && i + 1 < n) {
                        out[i++] = ' ';
                        char esc = input.charAt(i);
                        out[i++] = esc == '\n' ? '\n' : ' ';
                        continue;
                    }
                    if (sc == '"') {
                        out[i++] = ' ';
                        break;
                    }
                    if (sc == '\n') {
                        out[i++] = '\n';
                        break;
                    }
                    out[i++] = ' ';
                }
                continue;
            }
            // Char literal '...'
            if (c == '\'') {
                out[i++] = ' ';
                while (i < n) {
                    char cc = input.charAt(i);
                    if (cc == '\\' && i + 1 < n) {
                        out[i++] = ' ';
                        char esc = input.charAt(i);
                        out[i++] = esc == '\n' ? '\n' : ' ';
                        continue;
                    }
                    if (cc == '\'') {
                        out[i++] = ' ';
                        break;
                    }
                    if (cc == '\n') {
                        out[i++] = '\n';
                        break;
                    }
                    out[i++] = ' ';
                }
                continue;
            }
            out[i++] = c;
        }
        return new String(out);
    }

    private static List<PreliminaryDependency> resolveInternalDependencies(List<ScannedFile> files,
            BooleanSupplier cancelled) {
        var knownTypes = new HashMap<String, ScannedFile>(Math.max(16, files.size() * 2));
        for (var file : files) {
            for (var type : file.types()) {
                knownTypes.putIfAbsent(type.qualifiedName(), file);
            }
        }
        var seen = new LinkedHashMap<String, PreliminaryDependency>();
        for (var file : files) {
            checkCancelled(cancelled);
            if (file.types().isEmpty() || file.explicitImports().isEmpty()) {
                continue;
            }
            var primaryType = file.types().stream()
                    .filter(t -> !t.nested())
                    .findFirst()
                    .orElse(file.types().getFirst());
            for (var imported : file.explicitImports()) {
                var targetFile = knownTypes.get(imported);
                if (targetFile == null) {
                    continue;
                }
                if (primaryType.qualifiedName().equals(imported)) {
                    continue;
                }
                String key = primaryType.qualifiedName() + "->" + imported + "|" + file.path();
                seen.putIfAbsent(key, new PreliminaryDependency(
                        primaryType.qualifiedName(),
                        imported,
                        file.modulePath(),
                        targetFile.modulePath(),
                        file.packageName(),
                        targetFile.packageName(),
                        file.path(),
                        "EXPLICIT_IMPORT"));
            }
        }
        var sorted = new ArrayList<>(seen.values());
        sorted.sort(Comparator.comparing(PreliminaryDependency::sourceType)
                .thenComparing(PreliminaryDependency::targetType)
                .thenComparing(PreliminaryDependency::sourcePath));
        return sorted;
    }

    private static int[] computeLineOffsets(String text) {
        int lineCount = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lineCount++;
        }
        int[] offsets = new int[lineCount];
        offsets[0] = 0;
        int idx = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                offsets[idx++] = i + 1;
            }
        }
        return offsets;
    }

    private static int lineOfOffset(int[] lineOffsets, int offset) {
        int lo = 0;
        int hi = lineOffsets.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineOffsets[mid] <= offset) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return Math.max(1, lo);
    }

    static boolean isSupportedSourcePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt");
    }

    private static String normalizePath(String path) {
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    private static String normalizeModulePath(String modulePath) {
        String m = modulePath.replace('\\', '/').trim();
        while (m.startsWith("./")) {
            m = m.substring(2);
        }
        while (m.startsWith("/")) {
            m = m.substring(1);
        }
        while (m.endsWith("/") && m.length() > 1) {
            m = m.substring(0, m.length() - 1);
        }
        return m.isEmpty() ? "." : m;
    }

    private static String inferModulePath(String relPath) {
        int srcIdx = relPath.indexOf("/src/");
        if (srcIdx > 0) {
            return relPath.substring(0, srcIdx);
        }
        return ".";
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Skeleton scan cancelled");
        }
    }
}
