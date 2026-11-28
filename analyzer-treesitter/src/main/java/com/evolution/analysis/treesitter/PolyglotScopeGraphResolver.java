package com.evolution.analysis.treesitter;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.AffectedOutput;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.evidence.EvidenceRequirement;
import com.evolution.analysis.evidence.EvidenceSubject;
import com.evolution.analysis.evidence.ProviderObservationReference;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.SourceCoordinates;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treesitter.TSNode;

/**
 * Passive ({@code --build=off}, I1) CodexGraph two-phase cross-file scope-graph and import-table
 * resolver for Python, TypeScript/JavaScript, Go, and Rust.
 *
 * <p>Phase 1 collects a deterministic, lexicographically ordered {@link ExportTable} from single-pass
 * Tree-sitter AST traversals alongside passive manifest metadata ({@code go.mod},
 * {@code tsconfig.json}/{@code package.json}, {@code pyproject.toml}/{@code __init__.py},
 * {@code Cargo.toml}).
 *
 * <p>Phase 2 executes a deterministic, cycle-safe lexicographic DFS over module imports and barrel
 * re-exports ({@code index.ts}, {@code __init__.py}, Rust {@code pub use}) to emit cross-file
 * {@code IMPORTS}, {@code INHERITS} (including multi-inheritance and trait implementations), and
 * unique {@code CALLS_SYMBOL} edges tagged with {@link #PROVENANCE_POLYGLOT_IMPORT_SCOPED} and
 * {@link TrustTier#SOURCE_SYNTAX} (strictly distinct from {@link TrustTier#COMPILER}/SCIP, I4).
 * Ambiguous duck-typed calls with multiple candidates or unresolved dynamic targets never guess and
 * instead emit explicit {@link CapabilityGapRecord} entries with reason code
 * {@value #AMBIGUOUS_CALL_REASON_CODE} (I3/I4).
 */
public final class PolyglotScopeGraphResolver {

    public static final String PROVENANCE_POLYGLOT_IMPORT_SCOPED = "POLYGLOT_IMPORT_SCOPED";
    public static final TrustTier BASE_TRUST_TIER = TrustTier.SOURCE_SYNTAX;
    public static final String AMBIGUOUS_CALL_REASON_CODE = "AMBIGUOUS_POLYGLOT_CALL";
    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("source.polyglot-scope-graph", "wp1.5-v1");
    public static final VersionedIdentifier GAP_CATALOG =
            new VersionedIdentifier("evidence.polyglot-scope-graph", "wp1.5-v1");
    private static final RepositoryIdentity DEFAULT_REPO =
            RepositoryIdentity.fromCanonicalCoordinate("urn:evo:polyglot-scope-graph");

    private static final GraphJson.Limits CONFIG_JSON_LIMITS = new GraphJson.Limits(1_048_576, 32, 16_384);
    private static final int MAX_EXTENDS_DEPTH = 8;
    private static final int MAX_REEXPORT_DEPTH = 16;

    private static final Pattern GO_MODULE_LINE =
            Pattern.compile("^\\s*module\\s+(?:\"([^\"]+)\"|([^\\s/]+(?:/[^\\s/]+)*))\\s*(?://.*)?$");
    private static final Pattern GO_REPLACE_LINE =
            Pattern.compile("^\\s*(?:replace\\s+)?([^\\s]+)\\s+(?:v[^\\s]+\\s+)?=>\\s+(\\.\\.?/[^\\s]+)\\s*(?://.*)?$");
    private static final Pattern TOML_SECTION = Pattern.compile("^\\s*\\[\\s*([^\\]]+?)\\s*\\]\\s*(?:#.*)?$");
    private static final Pattern TOML_KV_STRING =
            Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*[\"']([^\"']+)[\"']\\s*(?:#.*)?$");
    private static final Pattern TOML_PATH_DEP =
            Pattern.compile("^\\s*([A-Za-z0-9_-]+)\\s*=\\s*\\{[^}]*\\bpath\\s*=\\s*[\"']([^\"']+)[\"'][^}]*\\}");
    private static final Pattern PNPM_PKG_LINE =
            Pattern.compile("^\\s*-\\s*['\"]?([^'\"#]+?)['\"]?\\s*(?:#.*)?$");
    private static final Pattern STRING_TOKEN = Pattern.compile("[\"']([^\"']+)[\"']");

    private static final Set<String> SUPPORTED_LANGUAGES =
            Set.of("python", "typescript", "tsx", "javascript", "go", "rust");

    private static final Set<String> BUILTIN_CALLS = Set.copyOf(List.of(
            // Python builtins
            "print", "len", "str", "int", "float", "bool", "list", "dict", "set", "tuple", "range",
            "enumerate", "zip", "map", "filter", "sorted", "reversed", "sum", "min", "max", "abs",
            "isinstance", "issubclass", "hasattr", "setattr", "delattr", "type", "object", "super",
            "open", "id", "repr", "hash", "iter", "next", "any", "all", "round", "format", "input",
            "ValueError", "TypeError", "RuntimeError", "KeyError", "Exception", "NotImplementedError",
            // JS/TS builtins
            "console", "parseInt", "parseFloat", "isNaN", "isFinite", "Number", "String", "Boolean",
            "Object", "Array", "Map", "Set", "WeakMap", "WeakSet", "Promise", "Error",
            "Date", "RegExp", "JSON", "Math", "Symbol", "BigInt", "setTimeout", "clearTimeout",
            "setInterval", "clearInterval", "require", "fetch", "Buffer", "URL",
            // Go builtins
            "make", "new", "cap", "append", "copy", "delete", "close", "panic", "recover",
            "complex", "real", "imag", "clear", "error", "string", "byte", "rune",
            "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64",
            "float32", "float64", "fmt", "errors", "context", "strings", "bytes", "io", "os",
            // Rust builtins/prelude
            "Some", "None", "Ok", "Err", "Box", "Vec", "Option", "Result", "Arc", "Rc",
            "RefCell", "Cell", "Mutex", "RwLock", "HashMap", "HashSet", "BTreeMap", "BTreeSet",
            "println", "eprintln", "eprint", "vec", "assert", "assert_eq",
            "assert_ne", "debug_assert", "todo", "unimplemented", "unreachable", "drop", "clone",
            "into", "from", "unwrap", "expect", "ok", "err", "as_ref", "as_mut", "into_iter",
            "collect", "push", "pop", "is_empty", "to_string", "to_owned"));

    private static final Set<String> DYNAMIC_CALL_BUILTINS =
            Set.of("eval", "exec", "__import__", "Function");

    private static final Set<String> DYNAMIC_LOOKUP_BUILTINS =
            Set.of("getattr");

    private final TreeSitterSourceFrontend frontend;

    public PolyglotScopeGraphResolver() {
        this(new TreeSitterSourceFrontend());
    }

    public PolyglotScopeGraphResolver(TreeSitterSourceFrontend frontend) {
        this.frontend = Objects.requireNonNull(frontend, "frontend must not be null");
    }

    // -------------------------------------------------------------------------
    // Public Domain Model (100% immutable, deterministic, Comparable)
    // -------------------------------------------------------------------------

    public enum SymbolKind {
        CLASS,
        INTERFACE,
        STRUCT,
        TRAIT,
        ENUM,
        FUNCTION,
        METHOD,
        TYPE_ALIAS,
        CONSTANT,
        MODULE
    }

    public enum EdgeKind {
        IMPORTS,
        INHERITS,
        CALLS_SYMBOL
    }

    public record SourceFile(String path, String language, byte[] bytes) {
        public SourceFile {
            path = normalizePath(Objects.requireNonNull(path, "path"));
            language = Objects.requireNonNull(language, "language").toLowerCase(Locale.ROOT);
            bytes = Objects.requireNonNull(bytes, "bytes");
        }
    }

    public record SymbolDef(
            String symbolId,
            String modulePath,
            String language,
            String name,
            String qualifiedName,
            String enclosingType,
            SymbolKind kind,
            boolean exported,
            SourceCoordinates.Range span) implements Comparable<SymbolDef> {

        public SymbolDef {
            symbolId = Objects.requireNonNull(symbolId, "symbolId");
            modulePath = normalizePath(Objects.requireNonNull(modulePath, "modulePath"));
            language = Objects.requireNonNull(language, "language");
            name = Objects.requireNonNull(name, "name");
            qualifiedName = Objects.requireNonNull(qualifiedName, "qualifiedName");
            enclosingType = enclosingType == null ? "" : enclosingType;
            kind = Objects.requireNonNull(kind, "kind");
            span = Objects.requireNonNull(span, "span");
        }

        @Override
        public int compareTo(SymbolDef other) {
            int cmp = modulePath.compareTo(other.modulePath);
            if (cmp != 0) return cmp;
            cmp = qualifiedName.compareTo(other.qualifiedName);
            if (cmp != 0) return cmp;
            cmp = kind.compareTo(other.kind);
            if (cmp != 0) return cmp;
            return Integer.compare(span.start(), other.span.start());
        }

        public Map<String, Object> toMap() {
            var map = new TreeMap<String, Object>();
            map.put("enclosingType", enclosingType);
            map.put("exported", exported);
            map.put("kind", kind.name());
            map.put("language", language);
            map.put("modulePath", modulePath);
            map.put("name", name);
            map.put("qualifiedName", qualifiedName);
            map.put("span", rangeToMap(span));
            map.put("symbolId", symbolId);
            return map;
        }
    }

    public record ExportTable(
            Map<String, Map<String, SymbolDef>> byModule,
            Map<String, List<SymbolDef>> allSymbolsByModule) {

        public ExportTable {
            var sortedExports = new TreeMap<String, Map<String, SymbolDef>>();
            for (var entry : byModule.entrySet()) {
                sortedExports.put(entry.getKey(), Collections.unmodifiableMap(new TreeMap<>(entry.getValue())));
            }
            byModule = Collections.unmodifiableMap(sortedExports);

            var sortedAll = new TreeMap<String, List<SymbolDef>>();
            for (var entry : allSymbolsByModule.entrySet()) {
                var list = new ArrayList<>(entry.getValue());
                Collections.sort(list);
                sortedAll.put(entry.getKey(), List.copyOf(list));
            }
            allSymbolsByModule = Collections.unmodifiableMap(sortedAll);
        }

        public Optional<SymbolDef> lookup(String modulePath, String symbolName) {
            var mod = byModule.get(normalizePath(modulePath));
            return mod == null ? Optional.empty() : Optional.ofNullable(mod.get(symbolName));
        }

        public Map<String, Object> toMap() {
            var out = new TreeMap<String, Object>();
            for (var entry : byModule.entrySet()) {
                var syms = new TreeMap<String, Object>();
                for (var symEntry : entry.getValue().entrySet()) {
                    syms.put(symEntry.getKey(), symEntry.getValue().toMap());
                }
                out.put(entry.getKey(), syms);
            }
            return out;
        }
    }

    public record ResolvedEdge(
            EdgeKind kind,
            String language,
            String sourcePath,
            String sourceSymbol,
            SourceCoordinates.Range sourceSpan,
            String targetPath,
            String targetSymbol,
            SourceCoordinates.Range targetSpan,
            TrustTier trustTier,
            String provenance,
            String resolutionDetail) implements Comparable<ResolvedEdge> {

        public ResolvedEdge {
            kind = Objects.requireNonNull(kind, "kind");
            language = Objects.requireNonNull(language, "language");
            sourcePath = normalizePath(Objects.requireNonNull(sourcePath, "sourcePath"));
            sourceSymbol = sourceSymbol == null ? "<module>" : sourceSymbol;
            sourceSpan = Objects.requireNonNull(sourceSpan, "sourceSpan");
            targetPath = normalizePath(Objects.requireNonNull(targetPath, "targetPath"));
            targetSymbol = targetSymbol == null ? "<module>" : targetSymbol;
            targetSpan = Objects.requireNonNull(targetSpan, "targetSpan");
            trustTier = Objects.requireNonNull(trustTier, "trustTier");
            provenance = Objects.requireNonNull(provenance, "provenance");
            resolutionDetail = resolutionDetail == null ? "" : resolutionDetail;
        }

        @Override
        public int compareTo(ResolvedEdge other) {
            int cmp = sourcePath.compareTo(other.sourcePath);
            if (cmp != 0) return cmp;
            cmp = kind.compareTo(other.kind);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(sourceSpan.start(), other.sourceSpan.start());
            if (cmp != 0) return cmp;
            cmp = sourceSymbol.compareTo(other.sourceSymbol);
            if (cmp != 0) return cmp;
            cmp = targetPath.compareTo(other.targetPath);
            if (cmp != 0) return cmp;
            cmp = targetSymbol.compareTo(other.targetSymbol);
            if (cmp != 0) return cmp;
            return resolutionDetail.compareTo(other.resolutionDetail);
        }

        public Map<String, Object> toMap() {
            var map = new TreeMap<String, Object>();
            map.put("kind", kind.name());
            map.put("language", language);
            map.put("provenance", provenance);
            map.put("resolutionDetail", resolutionDetail);
            map.put("sourcePath", sourcePath);
            map.put("sourceSpan", rangeToMap(sourceSpan));
            map.put("sourceSymbol", sourceSymbol);
            map.put("targetPath", targetPath);
            map.put("targetSpan", rangeToMap(targetSpan));
            map.put("targetSymbol", targetSymbol);
            map.put("trustTier", trustTier.name());
            return map;
        }
    }

    // Manifest descriptors

    public record GoModuleManifest(
            String manifestPath, String rootDir, String modulePath, Map<String, String> localReplaces) {
        public GoModuleManifest {
            localReplaces = Collections.unmodifiableMap(new TreeMap<>(localReplaces));
        }
    }

    public record TsAliasRule(
            String configPath, String baseDir, String pattern, List<String> targets, String sourceKind) {
        public TsAliasRule {
            targets = List.copyOf(targets);
        }
    }

    public record JsWorkspacePackage(String name, String dir, String mainEntry) {
    }

    public record PythonProjectManifest(
            String manifestPath, String rootDir, String projectName, List<String> sourceRoots) {
        public PythonProjectManifest {
            sourceRoots = List.copyOf(sourceRoots);
        }
    }

    public record CargoCrateManifest(
            String manifestPath,
            String rootDir,
            String packageName,
            String crateName,
            String libPath,
            List<String> workspaceMembers,
            Map<String, String> localPathDeps) {
        public CargoCrateManifest {
            workspaceMembers = List.copyOf(workspaceMembers);
            localPathDeps = Collections.unmodifiableMap(new TreeMap<>(localPathDeps));
        }
    }

    public record ManifestIndex(
            List<GoModuleManifest> goModules,
            List<TsAliasRule> tsAliases,
            Map<String, String> jsPackageExports,
            Map<String, String> jsPackageImports,
            Map<String, JsWorkspacePackage> jsWorkspacePackages,
            List<PythonProjectManifest> pythonProjects,
            Set<String> pythonInitPackages,
            List<CargoCrateManifest> cargoCrates) {

        public ManifestIndex {
            goModules = List.copyOf(goModules);
            tsAliases = List.copyOf(tsAliases);
            jsPackageExports = Collections.unmodifiableMap(new TreeMap<>(jsPackageExports));
            jsPackageImports = Collections.unmodifiableMap(new TreeMap<>(jsPackageImports));
            jsWorkspacePackages = Collections.unmodifiableMap(new TreeMap<>(jsWorkspacePackages));
            pythonProjects = List.copyOf(pythonProjects);
            pythonInitPackages = Collections.unmodifiableSet(new TreeSet<>(pythonInitPackages));
            cargoCrates = List.copyOf(cargoCrates);
        }

        public static final ManifestIndex EMPTY = new ManifestIndex(
                List.of(), List.of(), Map.of(), Map.of(), Map.of(), List.of(), Set.of(), List.of());
    }

    public record ScopeGraph(
            ManifestIndex manifests,
            ExportTable exportTable,
            List<ResolvedEdge> edges,
            List<CapabilityGapRecord> capabilityGaps,
            List<String> dfsTraversalOrder,
            ContentDigest canonicalDigest) {

        public ScopeGraph {
            manifests = Objects.requireNonNull(manifests, "manifests");
            exportTable = Objects.requireNonNull(exportTable, "exportTable");
            var sortedEdges = new ArrayList<>(edges);
            Collections.sort(sortedEdges);
            edges = List.copyOf(sortedEdges);
            var sortedGaps = new ArrayList<>(capabilityGaps);
            Collections.sort(sortedGaps);
            capabilityGaps = List.copyOf(sortedGaps);
            dfsTraversalOrder = List.copyOf(dfsTraversalOrder);
            canonicalDigest = Objects.requireNonNull(canonicalDigest, "canonicalDigest");
        }

        public List<ResolvedEdge> edgesOfKind(EdgeKind kind) {
            return edges.stream().filter(e -> e.kind() == kind).toList();
        }

        public List<ResolvedEdge> edgesFrom(String sourcePath) {
            String norm = normalizePath(sourcePath);
            return edges.stream().filter(e -> e.sourcePath().equals(norm)).toList();
        }

        public boolean hasEdge(EdgeKind kind, String sourcePath, String targetPath, String targetSymbol) {
            String normSrc = normalizePath(sourcePath);
            String normTgt = normalizePath(targetPath);
            return edges.stream().anyMatch(e -> e.kind() == kind
                    && e.sourcePath().equals(normSrc)
                    && e.targetPath().equals(normTgt)
                    && e.targetSymbol().equals(targetSymbol));
        }

        public Map<String, Object> toMap() {
            var map = new TreeMap<String, Object>();
            map.put("baseTrustTier", BASE_TRUST_TIER.name());
            map.put("capabilityGaps", capabilityGaps.stream().map(g -> {
                var gm = new TreeMap<String, Object>();
                gm.put("gapIdentity", g.gapIdentity().value());
                gm.put("limitations", g.limitations());
                gm.put("mechanismCategory", g.mechanismCategory());
                gm.put("reasonCode", g.reasonCode());
                gm.put("subject", g.subject().kind().name() + ":" + g.subject().identity());
                return gm;
            }).toList());
            map.put("dfsTraversalOrder", dfsTraversalOrder);
            map.put("edges", edges.stream().map(ResolvedEdge::toMap).toList());
            map.put("exportTable", exportTable.toMap());
            map.put("provenance", PROVENANCE_POLYGLOT_IMPORT_SCOPED);
            return map;
        }
    }

    // -------------------------------------------------------------------------
    // Public Entry Points
    // -------------------------------------------------------------------------

    public ScopeGraph resolve(Map<String, byte[]> filesByPath, BooleanSupplier cancelled) throws IOException {
        return resolve(filesByPath, null, null, cancelled);
    }

    public ScopeGraph resolve(
            Map<String, byte[]> filesByPath,
            EvidenceContext context,
            BooleanSupplier cancelled) throws IOException {
        return resolve(filesByPath, null, context, cancelled);
    }

    /**
     * Runs passive manifest discovery, Phase 1 single-pass Tree-sitter extraction (optionally also
     * forwarding standard intra-file {@link TreeSitterSourceFrontend.Fact}s to {@code factSink}),
     * and Phase 2 deterministic lexicographic cross-file DFS resolution.
     */
    public ScopeGraph resolve(
            Map<String, byte[]> filesByPath,
            BiConsumer<String, TreeSitterSourceFrontend.Fact> factSink,
            EvidenceContext context,
            BooleanSupplier cancelled) throws IOException {
        BooleanSupplier cancel = cancelled == null ? () -> false : cancelled;
        checkCancelled(cancel);

        var sortedFiles = new TreeMap<String, byte[]>();
        if (filesByPath != null) {
            for (var entry : filesByPath.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    sortedFiles.put(normalizePath(entry.getKey()), entry.getValue());
                }
            }
        }

        // Compute deterministic snapshot context if caller did not supply one.
        EvidenceContext effectiveContext = context != null ? context : deriveDefaultContext(sortedFiles);

        // Passive manifest parsing (100% --build=off, I1)
        var rawManifests = new TreeMap<String, String>();
        var pythonInitPackages = new TreeSet<String>();
        var sourceFiles = new ArrayList<SourceFile>();

        for (var entry : sortedFiles.entrySet()) {
            checkCancelled(cancel);
            String path = entry.getKey();
            byte[] bytes = entry.getValue();
            if (isManifestFile(path)) {
                rawManifests.put(path, new String(bytes, StandardCharsets.UTF_8));
            }
            if (path.equals("__init__.py") || path.endsWith("/__init__.py")) {
                pythonInitPackages.add(parentDir(path));
            }
            String lang = detectLanguage(path);
            if (lang != null) {
                sourceFiles.add(new SourceFile(path, lang, bytes));
            }
        }

        ManifestIndex manifests = parseManifests(rawManifests, pythonInitPackages, sortedFiles.keySet());
        return resolveParsed(sourceFiles, manifests, factSink, effectiveContext, cancel);
    }

    /**
     * Explicitly parses standard manifests ({@code go.mod}, {@code tsconfig.json}/{@code package.json},
     * {@code pyproject.toml}, {@code Cargo.toml}) from in-memory text without host execution.
     */
    public static ManifestIndex parseManifests(
            Map<String, String> manifestsByPath,
            Set<String> pythonInitDirs,
            Set<String> knownPaths) {
        var sorted = new TreeMap<String, String>();
        if (manifestsByPath != null) {
            for (var e : manifestsByPath.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    sorted.put(normalizePath(e.getKey()), e.getValue());
                }
            }
        }

        var goModules = new ArrayList<GoModuleManifest>();
        var tsAliases = new ArrayList<TsAliasRule>();
        var jsWorkspaceGlobs = new LinkedHashSet<String>();
        var jsPackageExports = new TreeMap<String, String>();
        var jsPackageImports = new TreeMap<String, String>();
        var parsedTsConfigs = new LinkedHashMap<String, Map<String, Object>>();
        var discoveredJsPackages = new ArrayList<JsWorkspacePackage>();
        var pythonProjects = new ArrayList<PythonProjectManifest>();
        var cargoCrates = new ArrayList<CargoCrateManifest>();

        for (var entry : sorted.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();
            if (content == null || content.isBlank()) continue;
            String fileName = baseName(path);
            String lowerName = fileName.toLowerCase(Locale.ROOT);
            String dir = parentDir(path);

            if (lowerName.equals("go.mod")) {
                parseGoMod(path, dir, content).ifPresent(goModules::add);
            } else if ((lowerName.contains("tsconfig") || lowerName.contains("jsconfig"))
                    && lowerName.endsWith(".json")) {
                try {
                    parsedTsConfigs.put(path, GraphJson.object(GraphJson.read(stripJsonc(content), CONFIG_JSON_LIMITS)));
                } catch (RuntimeException ignored) {
                    // Passive malformed JSON stays non-fatal.
                }
            } else if (lowerName.equals("pnpm-workspace.yaml") || lowerName.equals("pnpm-workspace.yml")) {
                parsePnpmWorkspace(content, jsWorkspaceGlobs);
            } else if (lowerName.equals("package.json")) {
                parsePackageJson(dir, content, jsWorkspaceGlobs, jsPackageExports, jsPackageImports, discoveredJsPackages);
            } else if (lowerName.equals("pyproject.toml")) {
                pythonProjects.add(parsePyprojectToml(path, dir, content, knownPaths));
            } else if (lowerName.equals("cargo.toml")) {
                cargoCrates.add(parseCargoToml(path, dir, content));
            }
        }

        for (String cfgPath : parsedTsConfigs.keySet()) {
            resolveTsConfigWithExtends(cfgPath, parentDir(cfgPath), parsedTsConfigs, tsAliases);
        }

        var jsWorkspacePackages = new TreeMap<String, JsWorkspacePackage>();
        if (!jsWorkspaceGlobs.isEmpty()) {
            for (var pkg : discoveredJsPackages) {
                if (!pkg.dir().isEmpty() && matchesAnyGlob(pkg.dir(), jsWorkspaceGlobs)) {
                    jsWorkspacePackages.putIfAbsent(pkg.name(), pkg);
                }
            }
        }

        var initDirs = pythonInitDirs == null ? new TreeSet<String>() : new TreeSet<>(pythonInitDirs);
        if (knownPaths != null) {
            for (String p : knownPaths) {
                String norm = normalizePath(p);
                if (norm.equals("__init__.py") || norm.endsWith("/__init__.py")) {
                    initDirs.add(parentDir(norm));
                }
            }
        }

        return new ManifestIndex(
                goModules,
                tsAliases,
                jsPackageExports,
                jsPackageImports,
                jsWorkspacePackages,
                pythonProjects,
                initDirs,
                cargoCrates);
    }

    // -------------------------------------------------------------------------
    // Internal Phase 1 + Phase 2 Pipeline
    // -------------------------------------------------------------------------

    private record ImportedBinding(
            String importedName,
            String localAlias,
            boolean namespaceImport,
            boolean wildcardImport,
            boolean defaultImport) {
    }

    private record ImportDirective(
            String rawSpecifier,
            List<ImportedBinding> bindings,
            boolean reExport,
            SourceCoordinates.Range span) {
    }

    private record HeritageRef(
            String childName,
            String childQualifiedName,
            String baseExpression,
            String heritageKind,
            SourceCoordinates.Range span) {
    }

    private record CallSite(
            String callerSymbol,
            String enclosingType,
            String expression,
            String receiver,
            String methodName,
            boolean dynamicTarget,
            boolean constructorCall,
            SourceCoordinates.Range span) {
    }

    private record LocalVarBinding(String scopeKey, String varName, String typeExpression) {
    }

    private static final class ParsedFile {
        final String path;
        final String language;
        final String langGroup;
        final ContentDigest contentDigest;
        String goPackageName = "";
        final List<SymbolDef> declarations = new ArrayList<>();
        final Map<String, SymbolDef> localExports = new TreeMap<>();
        final Map<String, Map<String, SymbolDef>> methodsByType = new TreeMap<>();
        final Map<String, Set<String>> interfaceMethods = new TreeMap<>();
        final List<ImportDirective> imports = new ArrayList<>();
        final List<HeritageRef> heritageRefs = new ArrayList<>();
        final List<CallSite> callSites = new ArrayList<>();
        final Map<String, Map<String, Set<String>>> varTypesByScope = new TreeMap<>();
        final Set<String> pythonDunderAll = new TreeSet<>();
        boolean hasPythonDunderAll = false;
        final List<String> rustExternalMods = new ArrayList<>();
        final Set<String> exportedLocalNames = new TreeSet<>();
        final Map<String, String> localExportAliases = new TreeMap<>();

        ParsedFile(String path, String language, ContentDigest contentDigest) {
            this.path = path;
            this.language = language;
            this.langGroup = languageGroup(language);
            this.contentDigest = contentDigest;
        }

        void addVarType(String scopeKey, String varName, String typeExpr) {
            if (varName == null || varName.isBlank() || typeExpr == null || typeExpr.isBlank()) return;
            varTypesByScope
                    .computeIfAbsent(scopeKey == null ? "" : scopeKey, k -> new TreeMap<>())
                    .computeIfAbsent(varName, k -> new TreeSet<>())
                    .add(typeExpr);
        }

        Set<String> lookupVarTypes(String scopeKey, String varName) {
            var scoped = varTypesByScope.get(scopeKey == null ? "" : scopeKey);
            if (scoped != null && scoped.containsKey(varName)) {
                return scoped.get(varName);
            }
            var top = varTypesByScope.get("");
            if (top != null && top.containsKey(varName)) {
                return top.get(varName);
            }
            return Set.of();
        }
    }

    private ScopeGraph resolveParsed(
            List<SourceFile> sourceFiles,
            ManifestIndex manifests,
            BiConsumer<String, TreeSitterSourceFrontend.Fact> factSink,
            EvidenceContext context,
            BooleanSupplier cancelled) throws IOException {
        // Phase 1: Lexicographically sorted single-pass Tree-sitter extraction -> ExportTable
        var sortedSources = new ArrayList<>(sourceFiles);
        sortedSources.sort(Comparator.comparing(SourceFile::path));

        var parsedByPath = new TreeMap<String, ParsedFile>();
        for (SourceFile sf : sortedSources) {
            checkCancelled(cancelled);
            if (!SUPPORTED_LANGUAGES.contains(sf.language())) continue;
            ParsedFile pf = parseSingleFile(sf, factSink, cancelled);
            parsedByPath.put(pf.path, pf);
        }

        // Build language-specific workspace indices from Phase 1 results
        Set<String> allFilePaths = Collections.unmodifiableSet(new TreeSet<>(parsedByPath.keySet()));
        Map<String, List<ParsedFile>> goPackagesByDir = indexGoPackages(parsedByPath);
        RustModuleIndex rustModules = buildRustModuleIndex(parsedByPath, manifests);

        // Build raw per-module exports map and all-symbols map
        var rawExportsByModule = new TreeMap<String, Map<String, SymbolDef>>();
        var allSymbolsByModule = new TreeMap<String, List<SymbolDef>>();
        var methodsByLangAndName = new TreeMap<String, TreeSet<SymbolDef>>();

        for (ParsedFile pf : parsedByPath.values()) {
            finalizeLocalExports(pf);
            rawExportsByModule.put(pf.path, new TreeMap<>(pf.localExports));
            var decls = new ArrayList<>(pf.declarations);
            Collections.sort(decls);
            allSymbolsByModule.put(pf.path, decls);
            for (SymbolDef sym : decls) {
                if (sym.kind() == SymbolKind.METHOD) {
                    methodsByLangAndName
                            .computeIfAbsent(pf.langGroup + "|" + sym.name(), k -> new TreeSet<>())
                            .add(sym);
                }
            }
        }

        // Phase 2a: Deterministic lexicographic DFS to expand barrel / re-export tables
        var resolvedExportsByModule = new TreeMap<String, Map<String, SymbolDef>>();
        var exportVisiting = new HashSet<String>();
        for (String path : parsedByPath.keySet()) {
            checkCancelled(cancelled);
            resolveModuleExportsDfs(
                    path, parsedByPath, manifests, allFilePaths, goPackagesByDir, rustModules,
                    rawExportsByModule, resolvedExportsByModule, exportVisiting, 0);
        }

        ExportTable exportTable = new ExportTable(resolvedExportsByModule, allSymbolsByModule);

        // Build direct import adjacency for deterministic post-order DFS over the module graph
        var moduleDeps = new TreeMap<String, TreeSet<String>>();
        for (ParsedFile pf : parsedByPath.values()) {
            var deps = new TreeSet<String>();
            for (ImportDirective imp : pf.imports) {
                for (String targetPath : resolveImportTargetFiles(pf, imp, parsedByPath, manifests,
                        allFilePaths, goPackagesByDir, rustModules)) {
                    if (!targetPath.equals(pf.path)) {
                        deps.add(targetPath);
                    }
                }
            }
            if (pf.language.equals("go")) {
                for (ParsedFile sibling : goPackagesByDir.getOrDefault(parentDir(pf.path), List.of())) {
                    if (!sibling.path.equals(pf.path) && sibling.goPackageName.equals(pf.goPackageName)) {
                        deps.add(sibling.path);
                    }
                }
            }
            moduleDeps.put(pf.path, deps);
        }

        // Phase 2b: Deterministic lexicographic DFS traversal resolving IMPORTS, INHERITS, CALLS_SYMBOL & gaps
        var edges = new TreeSet<ResolvedEdge>();
        var gaps = new TreeSet<CapabilityGapRecord>();
        var dfsOrder = new ArrayList<String>();
        var visited = new HashSet<String>();
        var visiting = new HashSet<String>();
        // Inheritance graph: childSymbolId -> sorted set of parent SymbolDefs
        var inheritanceGraph = new TreeMap<String, TreeSet<SymbolDef>>();
        // Resolved per-file import scopes cached as DFS completes dependencies
        var resolvedImportScopes = new TreeMap<String, FileImportScope>();

        for (String startPath : parsedByPath.keySet()) {
            checkCancelled(cancelled);
            if (!visited.contains(startPath)) {
                dfsResolveModule(
                        startPath, parsedByPath, manifests, allFilePaths, goPackagesByDir, rustModules,
                        resolvedExportsByModule, moduleDeps, methodsByLangAndName, inheritanceGraph,
                        resolvedImportScopes, edges, gaps, dfsOrder, visited, visiting, context, cancelled);
            }
        }

        // Second pass for cyclic mutual imports: if a cycle prevented heritage or calls from seeing
        // bindings settled later in DFS, resolve any remaining edges idempotently.
        if (parsedByPath.size() > 1) {
            for (String path : dfsOrder) {
                checkCancelled(cancelled);
                ParsedFile pf = parsedByPath.get(path);
                FileImportScope importScope = resolvedImportScopes.get(path);
                if (pf != null && importScope != null) {
                    resolveHeritage(
                            pf, importScope, parsedByPath, goPackagesByDir, resolvedExportsByModule,
                            inheritanceGraph, edges);
                }
            }
            for (String path : dfsOrder) {
                checkCancelled(cancelled);
                ParsedFile pf = parsedByPath.get(path);
                FileImportScope importScope = resolvedImportScopes.get(path);
                if (pf != null && importScope != null) {
                    resolveCallsAndGaps(
                            pf, importScope, parsedByPath, goPackagesByDir, resolvedExportsByModule,
                            methodsByLangAndName, inheritanceGraph, edges, gaps, context);
                }
            }
        }

        var tempGraph = new ScopeGraph(
                manifests,
                exportTable,
                List.copyOf(edges),
                List.copyOf(gaps),
                dfsOrder,
                ContentDigest.sha256Utf8("pending"));
        ContentDigest digest = CanonicalJson.sha256(tempGraph.toMap());
        return new ScopeGraph(
                manifests,
                exportTable,
                tempGraph.edges(),
                tempGraph.capabilityGaps(),
                tempGraph.dfsTraversalOrder(),
                digest);
    }

    private void dfsResolveModule(
            String path,
            Map<String, ParsedFile> parsedByPath,
            ManifestIndex manifests,
            Set<String> allFilePaths,
            Map<String, List<ParsedFile>> goPackagesByDir,
            RustModuleIndex rustModules,
            Map<String, Map<String, SymbolDef>> resolvedExportsByModule,
            Map<String, TreeSet<String>> moduleDeps,
            Map<String, TreeSet<SymbolDef>> methodsByLangAndName,
            Map<String, TreeSet<SymbolDef>> inheritanceGraph,
            Map<String, FileImportScope> resolvedImportScopes,
            Set<ResolvedEdge> edges,
            Set<CapabilityGapRecord> gaps,
            List<String> dfsOrder,
            Set<String> visited,
            Set<String> visiting,
            EvidenceContext context,
            BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        if (visited.contains(path) || !visiting.add(path)) {
            return;
        }
        // Visit dependencies first in deterministic lexicographic order (post-order DFS)
        for (String dep : moduleDeps.getOrDefault(path, new TreeSet<>())) {
            if (!visited.contains(dep) && !visiting.contains(dep)) {
                dfsResolveModule(
                        dep, parsedByPath, manifests, allFilePaths, goPackagesByDir, rustModules,
                        resolvedExportsByModule, moduleDeps, methodsByLangAndName, inheritanceGraph,
                        resolvedImportScopes, edges, gaps, dfsOrder, visited, visiting, context, cancelled);
            }
        }
        visiting.remove(path);
        visited.add(path);
        dfsOrder.add(path);

        ParsedFile pf = parsedByPath.get(path);
        if (pf == null) return;

        // 1. Resolve imports and emit IMPORTS edges
        FileImportScope importScope = resolveFileImports(
                pf, parsedByPath, manifests, allFilePaths, goPackagesByDir, rustModules,
                resolvedExportsByModule, edges);
        resolvedImportScopes.put(path, importScope);

        // 2. Resolve heritage / trait impls / Go embeddings & interface satisfaction -> INHERITS edges
        resolveHeritage(
                pf, importScope, parsedByPath, goPackagesByDir, resolvedExportsByModule,
                inheritanceGraph, edges);

        // 3. Resolve cross-file CALLS_SYMBOL edges & emit AMBIGUOUS_POLYGLOT_CALL gaps
        resolveCallsAndGaps(
                pf, importScope, parsedByPath, goPackagesByDir, resolvedExportsByModule,
                methodsByLangAndName, inheritanceGraph, edges, gaps, context);
    }

    // -------------------------------------------------------------------------
    // Phase 1: Single-Pass Tree-sitter File Extraction
    // -------------------------------------------------------------------------

    private ParsedFile parseSingleFile(
            SourceFile sf,
            BiConsumer<String, TreeSitterSourceFrontend.Fact> factSink,
            BooleanSupplier cancelled) throws IOException {
        ContentDigest contentDigest = ContentDigest.sha256(sf.bytes());
        ParsedFile pf = new ParsedFile(sf.path(), sf.language(), contentDigest);
        TreeSitterSourceFrontend.Sink sink = factSink == null ? null : fact -> factSink.accept(sf.path(), fact);
        frontend.parseWithTreeHook(sf.language(), sf.bytes(), sink,
                (root, utf8, positions) -> extractFileSyntax(pf, root, utf8, positions, cancelled),
                cancelled, false);
        return pf;
    }

    private static void extractFileSyntax(
            ParsedFile pf,
            TSNode root,
            byte[] utf8,
            SourceCoordinates positions,
            BooleanSupplier cancelled) throws IOException {
        switch (pf.language) {
            case "python" -> extractPython(pf, root, utf8, positions, cancelled);
            case "typescript", "tsx", "javascript" -> extractTsJs(pf, root, utf8, positions, cancelled);
            case "go" -> extractGo(pf, root, utf8, positions, cancelled);
            case "rust" -> extractRust(pf, root, utf8, positions, cancelled);
            default -> {
            }
        }
    }

    // --- Python extraction ---

    private static void extractPython(
            ParsedFile pf, TSNode root, byte[] utf8, SourceCoordinates pos, BooleanSupplier cancelled)
            throws IOException {
        boolean isInit = pf.path.equals("__init__.py") || pf.path.endsWith("/__init__.py");
        walkPython(pf, root, utf8, pos, "", "", isInit, cancelled);
    }

    private static void walkPython(
            ParsedFile pf,
            TSNode node,
            byte[] utf8,
            SourceCoordinates pos,
            String enclosingType,
            String enclosingCallable,
            boolean isInit,
            BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        String kind = node.getType();
        if (kind.contains("comment") || kind.equals("string")) {
            return;
        }

        if (kind.equals("import_statement")) {
            var span = spanOf(node, pos);
            var bindings = new ArrayList<ImportedBinding>();
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                if (c.getType().equals("dotted_name")) {
                    String mod = text(c, utf8).strip();
                    String alias = firstSegment(mod, '.');
                    if (!mod.isEmpty()) {
                        pf.imports.add(new ImportDirective(mod,
                                List.of(new ImportedBinding("*", alias, true, false, false)), isInit, span));
                    }
                } else if (c.getType().equals("aliased_import")) {
                    String mod = text(field(c, "name"), utf8).strip();
                    String alias = text(field(c, "alias"), utf8).strip();
                    if (!mod.isEmpty() && !alias.isEmpty()) {
                        pf.imports.add(new ImportDirective(mod,
                                List.of(new ImportedBinding("*", alias, true, false, false)), isInit, span));
                    }
                }
            }
            return;
        }

        if (kind.equals("import_from_statement")) {
            var span = spanOf(node, pos);
            TSNode modNode = field(node, "module_name");
            String rawSpec = modNode != null ? text(modNode, utf8).strip() : "";
            var bindings = new ArrayList<ImportedBinding>();
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                if (modNode != null && c.getStartByte() == modNode.getStartByte() && c.getEndByte() == modNode.getEndByte()) {
                    continue;
                }
                String ck = c.getType();
                if (ck.equals("wildcard_import")) {
                    bindings.add(new ImportedBinding("*", "*", false, true, false));
                } else if (ck.equals("dotted_name")) {
                    String name = text(c, utf8).strip();
                    if (!name.isEmpty()) {
                        bindings.add(new ImportedBinding(name, name, false, false, false));
                    }
                } else if (ck.equals("aliased_import")) {
                    String name = text(field(c, "name"), utf8).strip();
                    String alias = text(field(c, "alias"), utf8).strip();
                    if (!name.isEmpty() && !alias.isEmpty()) {
                        bindings.add(new ImportedBinding(name, alias, false, false, false));
                    }
                }
            }
            if (!rawSpec.isEmpty() && !bindings.isEmpty()) {
                pf.imports.add(new ImportDirective(rawSpec, bindings, isInit, span));
            }
            return;
        }

        if (kind.equals("class_definition")) {
            String className = text(field(node, "name"), utf8).strip();
            var span = spanOf(node, pos);
            if (!className.isEmpty()) {
                String qname = enclosingType.isEmpty() ? className : enclosingType + "." + className;
                boolean exported = !className.startsWith("_");
                pf.declarations.add(new SymbolDef(
                        symbolId(pf.path, qname), pf.path, pf.language, className, qname,
                        enclosingType, SymbolKind.CLASS, exported, span));
                TSNode supers = field(node, "superclasses");
                if (supers != null) {
                    for (int i = 0; i < supers.getNamedChildCount(); i++) {
                        TSNode baseNode = supers.getNamedChild(i);
                        if (baseNode.getType().equals("keyword_argument")) continue;
                        String baseExpr = stripGenericArgs(text(baseNode, utf8).strip());
                        if (!baseExpr.isEmpty() && !baseExpr.equals("object") && !baseExpr.equals("ABC")) {
                            pf.heritageRefs.add(new HeritageRef(
                                    className, qname, baseExpr, "extends", spanOf(baseNode, pos)));
                        }
                    }
                }
                TSNode body = field(node, "body");
                if (body != null) {
                    for (int i = 0; i < body.getNamedChildCount(); i++) {
                        walkPython(pf, body.getNamedChild(i), utf8, pos, qname, qname, isInit, cancelled);
                    }
                }
                return;
            }
        }

        if (kind.equals("function_definition")) {
            String fnName = text(field(node, "name"), utf8).strip();
            var span = spanOf(node, pos);
            if (!fnName.isEmpty()) {
                boolean isMethod = !enclosingType.isEmpty();
                String qname = isMethod ? enclosingType + "." + fnName : fnName;
                boolean exported = isMethod ? !fnName.startsWith("__") || fnName.equals("__init__") : !fnName.startsWith("_");
                SymbolDef sym = new SymbolDef(
                        symbolId(pf.path, qname), pf.path, pf.language, fnName, qname,
                        enclosingType, isMethod ? SymbolKind.METHOD : SymbolKind.FUNCTION, exported, span);
                pf.declarations.add(sym);
                if (isMethod) {
                    pf.methodsByType.computeIfAbsent(enclosingType, k -> new TreeMap<>()).putIfAbsent(fnName, sym);
                }
                // Extract typed parameters for local bindings
                TSNode params = field(node, "parameters");
                if (params != null) {
                    for (int i = 0; i < params.getNamedChildCount(); i++) {
                        TSNode p = params.getNamedChild(i);
                        if (p.getType().equals("typed_parameter") || p.getType().equals("typed_default_parameter")) {
                            String pName = "";
                            TSNode pNameNode = field(p, "name");
                            if (pNameNode != null) {
                                pName = text(pNameNode, utf8).strip();
                            } else if (p.getNamedChildCount() > 0 && p.getNamedChild(0).getType().equals("identifier")) {
                                pName = text(p.getNamedChild(0), utf8).strip();
                            }
                            TSNode pType = field(p, "type");
                            if (!pName.isEmpty() && pType != null) {
                                String typeExpr = unwrapPythonType(text(pType, utf8).strip());
                                if (!typeExpr.isEmpty()) {
                                    pf.addVarType(qname, pName, typeExpr);
                                }
                            }
                        }
                    }
                }
                TSNode body = field(node, "body");
                if (body != null) {
                    for (int i = 0; i < body.getNamedChildCount(); i++) {
                        walkPython(pf, body.getNamedChild(i), utf8, pos, enclosingType, qname, isInit, cancelled);
                    }
                }
                return;
            }
        }

        if (kind.equals("assignment")) {
            TSNode left = field(node, "left");
            TSNode typeNode = field(node, "type");
            TSNode right = field(node, "right");
            String lhs = left != null ? text(left, utf8).strip() : "";
            if (lhs.equals("__all__") && right != null && enclosingCallable.isEmpty()) {
                pf.hasPythonDunderAll = true;
                Matcher m = STRING_TOKEN.matcher(text(right, utf8));
                while (m.find()) {
                    pf.pythonDunderAll.add(m.group(1));
                }
            } else if (isSimpleIdentifier(lhs)) {
                if (typeNode != null) {
                    String typeExpr = unwrapPythonType(text(typeNode, utf8).strip());
                    if (!typeExpr.isEmpty()) {
                        pf.addVarType(enclosingCallable, lhs, typeExpr);
                    }
                } else if (right != null && right.getType().equals("call")) {
                    TSNode fn = field(right, "function");
                    if (fn != null) {
                        String callee = text(fn, utf8).strip();
                        String tail = tailIdent(callee);
                        if (!tail.isEmpty() && Character.isUpperCase(tail.charAt(0))) {
                            pf.addVarType(enclosingCallable, lhs, callee);
                        }
                    }
                }
            }
        }

        if (kind.equals("call")) {
            TSNode fn = field(node, "function");
            if (fn != null) {
                var span = spanOf(node, pos);
                String fnKind = fn.getType();
                String expr = text(fn, utf8).strip();
                String innerCallName = fnKind.equals("call") ? text(field(fn, "function"), utf8).strip() : "";
                boolean dynamic = fnKind.equals("subscript")
                        || DYNAMIC_CALL_BUILTINS.contains(expr)
                        || DYNAMIC_LOOKUP_BUILTINS.contains(innerCallName)
                        || DYNAMIC_CALL_BUILTINS.contains(innerCallName);
                String recv = "";
                String method = "";
                if (fnKind.equals("attribute")) {
                    TSNode obj = field(fn, "object");
                    TSNode attr = field(fn, "attribute");
                    recv = obj != null ? text(obj, utf8).strip() : "";
                    method = attr != null ? text(attr, utf8).strip() : "";
                } else if (fnKind.equals("identifier")) {
                    method = expr;
                } else if (dynamic) {
                    method = expr;
                }
                if (!method.isEmpty() && !DYNAMIC_LOOKUP_BUILTINS.contains(expr)) {
                    pf.callSites.add(new CallSite(
                            enclosingCallable.isEmpty() ? "<module>" : enclosingCallable,
                            enclosingType, expr, recv, method, dynamic, false, span));
                }
            }
        }

        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkPython(pf, node.getNamedChild(i), utf8, pos, enclosingType, enclosingCallable, isInit, cancelled);
        }
    }

    // --- TypeScript / JavaScript extraction ---

    private static void extractTsJs(
            ParsedFile pf, TSNode root, byte[] utf8, SourceCoordinates pos, BooleanSupplier cancelled)
            throws IOException {
        walkTsJs(pf, root, utf8, pos, "", "", false, cancelled);
    }

    private static void walkTsJs(
            ParsedFile pf,
            TSNode node,
            byte[] utf8,
            SourceCoordinates pos,
            String enclosingType,
            String enclosingCallable,
            boolean insideExport,
            BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        String kind = node.getType();
        if (kind.contains("comment") || kind.equals("string") || kind.equals("template_string")) {
            return;
        }

        if (kind.equals("import_statement")) {
            TSNode sourceNode = field(node, "source");
            String spec = unquote(text(sourceNode, utf8));
            if (!spec.isEmpty()) {
                var bindings = new ArrayList<ImportedBinding>();
                collectTsImportClause(node, utf8, bindings);
                if (bindings.isEmpty()) {
                    // Side-effect import: import './polyfill';
                    bindings.add(new ImportedBinding("*", "", true, false, false));
                }
                pf.imports.add(new ImportDirective(spec, bindings, false, spanOf(node, pos)));
            }
            return;
        }

        if (kind.equals("export_statement")) {
            TSNode sourceNode = field(node, "source");
            String spec = sourceNode != null ? unquote(text(sourceNode, utf8)) : "";
            String stmtText = text(node, utf8);
            boolean isDefault = stmtText.startsWith("export default") || hasUnnamedChild(node, "default");
            if (!spec.isEmpty()) {
                var bindings = new ArrayList<ImportedBinding>();
                collectTsExportClause(node, utf8, bindings);
                if (bindings.isEmpty() && hasUnnamedChild(node, "*")) {
                    bindings.add(new ImportedBinding("*", "*", false, true, false));
                }
                if (!bindings.isEmpty()) {
                    pf.imports.add(new ImportDirective(spec, bindings, true, spanOf(node, pos)));
                }
                return;
            }
            // Local export { A, B as C }
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                if (c.getType().equals("export_clause")) {
                    for (int j = 0; j < c.getNamedChildCount(); j++) {
                        TSNode esp = c.getNamedChild(j);
                        if (esp.getType().equals("export_specifier")) {
                            String orig = text(field(esp, "name"), utf8).strip();
                            String alias = text(field(esp, "alias"), utf8).strip();
                            if (!orig.isEmpty()) {
                                pf.exportedLocalNames.add(orig);
                                if (!alias.isEmpty() && !alias.equals(orig)) {
                                    pf.localExportAliases.put(alias, orig);
                                }
                            }
                        }
                    }
                    return;
                }
            }
            // Export wrapping a declaration
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode c = node.getNamedChild(i);
                if (isDefault && c.getType().equals("identifier")) {
                    String name = text(c, utf8).strip();
                    pf.exportedLocalNames.add(name);
                    pf.localExportAliases.put("default", name);
                } else {
                    walkTsJs(pf, c, utf8, pos, enclosingType, enclosingCallable, true, cancelled);
                    if (isDefault) {
                        TSNode declName = field(c, "name");
                        if (declName != null) {
                            pf.localExportAliases.put("default", text(declName, utf8).strip());
                        }
                    }
                }
            }
            return;
        }

        if (kind.equals("class_declaration") || kind.equals("abstract_class_declaration")) {
            String className = text(field(node, "name"), utf8).strip();
            var span = spanOf(node, pos);
            if (!className.isEmpty()) {
                String qname = enclosingType.isEmpty() ? className : enclosingType + "." + className;
                pf.declarations.add(new SymbolDef(
                        symbolId(pf.path, qname), pf.path, pf.language, className, qname,
                        enclosingType, SymbolKind.CLASS, insideExport, span));
                // Extract extends_clause and implements_clause from class_heritage
                for (int i = 0; i < node.getNamedChildCount(); i++) {
                    TSNode c = node.getNamedChild(i);
                    if (c.getType().equals("class_heritage")) {
                        extractTsHeritage(pf, c, utf8, pos, className, qname);
                    }
                }
                TSNode body = field(node, "body");
                if (body != null) {
                    for (int i = 0; i < body.getNamedChildCount(); i++) {
                        walkTsJs(pf, body.getNamedChild(i), utf8, pos, qname, qname, false, cancelled);
                    }
                }
                return;
            }
        }

        if (kind.equals("interface_declaration")) {
            String ifaceName = text(field(node, "name"), utf8).strip();
            var span = spanOf(node, pos);
            if (!ifaceName.isEmpty()) {
                pf.declarations.add(new SymbolDef(
                        symbolId(pf.path, ifaceName), pf.path, pf.language, ifaceName, ifaceName,
                        "", SymbolKind.INTERFACE, insideExport, span));
                for (int i = 0; i < node.getNamedChildCount(); i++) {
                    TSNode c = node.getNamedChild(i);
                    if (c.getType().equals("extends_type_clause")) {
                        for (int j = 0; j < c.getNamedChildCount(); j++) {
                            TSNode base = c.getNamedChild(j);
                            String baseExpr = stripGenericArgs(text(base, utf8).strip());
                            if (!baseExpr.isEmpty()) {
                                pf.heritageRefs.add(new HeritageRef(
                                        ifaceName, ifaceName, baseExpr, "extends", spanOf(base, pos)));
                            }
                        }
                    } else if (c.getType().equals("interface_body") || c.getType().equals("object_type")) {
                        for (int j = 0; j < c.getNamedChildCount(); j++) {
                            TSNode member = c.getNamedChild(j);
                            if (member.getType().equals("method_signature")) {
                                String mName = text(field(member, "name"), utf8).strip();
                                if (!mName.isEmpty()) {
                                    String qn = ifaceName + "." + mName;
                                    SymbolDef mSym = new SymbolDef(
                                            symbolId(pf.path, qn), pf.path, pf.language, mName, qn,
                                            ifaceName, SymbolKind.METHOD, insideExport, spanOf(member, pos));
                                    pf.declarations.add(mSym);
                                    pf.methodsByType.computeIfAbsent(ifaceName, k -> new TreeMap<>()).putIfAbsent(mName, mSym);
                                }
                            }
                        }
                    }
                }
            }
            return;
        }

        if (kind.equals("enum_declaration") || kind.equals("type_alias_declaration")) {
            String symName = text(field(node, "name"), utf8).strip();
            if (!symName.isEmpty()) {
                pf.declarations.add(new SymbolDef(
                        symbolId(pf.path, symName), pf.path, pf.language, symName, symName,
                        "", kind.equals("enum_declaration") ? SymbolKind.ENUM : SymbolKind.TYPE_ALIAS,
                        insideExport, spanOf(node, pos)));
            }
            return;
        }

        if (kind.equals("function_declaration") || kind.equals("generator_function_declaration")) {
            String fnName = text(field(node, "name"), utf8).strip();
            if (!fnName.isEmpty()) {
                pf.declarations.add(new SymbolDef(
                        symbolId(pf.path, fnName), pf.path, pf.language, fnName, fnName,
                        "", SymbolKind.FUNCTION, insideExport, spanOf(node, pos)));
                collectTsParams(pf, field(node, "parameters"), utf8, fnName);
                TSNode body = field(node, "body");
                if (body != null) {
                    walkTsJs(pf, body, utf8, pos, "", fnName, false, cancelled);
                }
                return;
            }
        }

        if (kind.equals("method_definition")) {
            String methodName = text(field(node, "name"), utf8).strip();
            if (!methodName.isEmpty() && !enclosingType.isEmpty()) {
                String qname = enclosingType + "." + methodName;
                SymbolDef sym = new SymbolDef(
                        symbolId(pf.path, qname), pf.path, pf.language, methodName, qname,
                        enclosingType, SymbolKind.METHOD, true, spanOf(node, pos));
                pf.declarations.add(sym);
                pf.methodsByType.computeIfAbsent(enclosingType, k -> new TreeMap<>()).putIfAbsent(methodName, sym);
                collectTsParams(pf, field(node, "parameters"), utf8, qname);
                TSNode body = field(node, "body");
                if (body != null) {
                    walkTsJs(pf, body, utf8, pos, enclosingType, qname, false, cancelled);
                }
                return;
            }
        }

        if (kind.equals("variable_declarator")) {
            TSNode nameNode = field(node, "name");
            TSNode typeAnn = field(node, "type");
            TSNode valNode = field(node, "value");
            String varName = nameNode != null && nameNode.getType().equals("identifier")
                    ? text(nameNode, utf8).strip() : "";
            if (!varName.isEmpty()) {
                if (valNode != null && (valNode.getType().equals("arrow_function")
                        || valNode.getType().equals("function_expression")
                        || valNode.getType().equals("function"))) {
                    if (enclosingCallable.isEmpty() && enclosingType.isEmpty()) {
                        pf.declarations.add(new SymbolDef(
                                symbolId(pf.path, varName), pf.path, pf.language, varName, varName,
                                "", SymbolKind.FUNCTION, insideExport, spanOf(node, pos)));
                    }
                    String fnScope = enclosingCallable.isEmpty() ? varName : enclosingCallable;
                    collectTsParams(pf, field(valNode, "parameters"), utf8, fnScope);
                    TSNode body = field(valNode, "body");
                    if (body != null) {
                        walkTsJs(pf, body, utf8, pos, enclosingType, fnScope, false, cancelled);
                    }
                    return;
                } else if (enclosingCallable.isEmpty() && enclosingType.isEmpty() && insideExport) {
                    pf.declarations.add(new SymbolDef(
                            symbolId(pf.path, varName), pf.path, pf.language, varName, varName,
                            "", SymbolKind.CONSTANT, true, spanOf(node, pos)));
                }
                if (typeAnn != null) {
                    String typeExpr = unwrapTsTypeAnnotation(text(typeAnn, utf8));
                    if (!typeExpr.isEmpty()) {
                        pf.addVarType(enclosingCallable, varName, typeExpr);
                    }
                } else if (valNode != null && valNode.getType().equals("new_expression")) {
                    TSNode ctor = field(valNode, "constructor");
                    if (ctor != null) {
                        String ctorExpr = stripGenericArgs(text(ctor, utf8).strip());
                        if (!ctorExpr.isEmpty()) {
                            pf.addVarType(enclosingCallable, varName, ctorExpr);
                        }
                    }
                }
            }
        }

        if (kind.equals("call_expression") || kind.equals("new_expression")) {
            boolean isNew = kind.equals("new_expression");
            TSNode fn = field(node, isNew ? "constructor" : "function");
            if (fn != null) {
                var span = spanOf(node, pos);
                String fnKind = fn.getType();
                String expr = stripGenericArgs(text(fn, utf8).strip());
                boolean dynamic = fnKind.equals("subscript_expression") || DYNAMIC_CALL_BUILTINS.contains(expr);
                String recv = "";
                String method = "";
                if (fnKind.equals("member_expression")) {
                    TSNode obj = field(fn, "object");
                    TSNode prop = field(fn, "property");
                    recv = obj != null ? text(obj, utf8).strip() : "";
                    method = prop != null ? text(prop, utf8).strip() : "";
                } else if (fnKind.equals("identifier") || fnKind.equals("type_identifier")) {
                    method = expr;
                } else if (dynamic) {
                    method = expr;
                }
                if (!method.isEmpty()) {
                    pf.callSites.add(new CallSite(
                            enclosingCallable.isEmpty() ? "<module>" : enclosingCallable,
                            enclosingType, expr, recv, method, dynamic, isNew, span));
                }
            }
        }

        boolean passExport = insideExport && (kind.equals("lexical_declaration") || kind.equals("variable_declaration"));
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkTsJs(pf, node.getNamedChild(i), utf8, pos, enclosingType, enclosingCallable, passExport, cancelled);
        }
    }

    private static void collectTsImportClause(TSNode importStmt, byte[] utf8, List<ImportedBinding> out) {
        for (int i = 0; i < importStmt.getNamedChildCount(); i++) {
            TSNode c = importStmt.getNamedChild(i);
            if (!c.getType().equals("import_clause")) continue;
            for (int j = 0; j < c.getNamedChildCount(); j++) {
                TSNode part = c.getNamedChild(j);
                String pk = part.getType();
                if (pk.equals("identifier")) {
                    String alias = text(part, utf8).strip();
                    if (!alias.isEmpty()) {
                        out.add(new ImportedBinding("default", alias, false, false, true));
                    }
                } else if (pk.equals("namespace_import")) {
                    for (int k = 0; k < part.getNamedChildCount(); k++) {
                        if (part.getNamedChild(k).getType().equals("identifier")) {
                            String alias = text(part.getNamedChild(k), utf8).strip();
                            if (!alias.isEmpty()) {
                                out.add(new ImportedBinding("*", alias, true, false, false));
                            }
                        }
                    }
                } else if (pk.equals("named_imports")) {
                    for (int k = 0; k < part.getNamedChildCount(); k++) {
                        TSNode sp = part.getNamedChild(k);
                        if (sp.getType().equals("import_specifier")) {
                            String orig = text(field(sp, "name"), utf8).strip();
                            String alias = text(field(sp, "alias"), utf8).strip();
                            if (!orig.isEmpty()) {
                                out.add(new ImportedBinding(orig, alias.isEmpty() ? orig : alias, false, false, false));
                            }
                        }
                    }
                }
            }
        }
    }

    private static void collectTsExportClause(TSNode exportStmt, byte[] utf8, List<ImportedBinding> out) {
        for (int i = 0; i < exportStmt.getNamedChildCount(); i++) {
            TSNode c = exportStmt.getNamedChild(i);
            String ck = c.getType();
            if (ck.equals("export_clause")) {
                for (int j = 0; j < c.getNamedChildCount(); j++) {
                    TSNode sp = c.getNamedChild(j);
                    if (sp.getType().equals("export_specifier")) {
                        String orig = text(field(sp, "name"), utf8).strip();
                        String alias = text(field(sp, "alias"), utf8).strip();
                        if (!orig.isEmpty()) {
                            out.add(new ImportedBinding(orig, alias.isEmpty() ? orig : alias, false, false, false));
                        }
                    }
                }
            } else if (ck.equals("namespace_export")) {
                for (int j = 0; j < c.getNamedChildCount(); j++) {
                    if (c.getNamedChild(j).getType().equals("identifier")) {
                        String alias = text(c.getNamedChild(j), utf8).strip();
                        if (!alias.isEmpty()) {
                            out.add(new ImportedBinding("*", alias, true, false, false));
                        }
                    }
                }
            }
        }
    }

    private static void extractTsHeritage(
            ParsedFile pf, TSNode heritage, byte[] utf8, SourceCoordinates pos, String className, String qname) {
        for (int i = 0; i < heritage.getNamedChildCount(); i++) {
            TSNode clause = heritage.getNamedChild(i);
            String ck = clause.getType();
            if (ck.equals("extends_clause") || ck.equals("implements_clause")) {
                String hKind = ck.equals("extends_clause") ? "extends" : "implements";
                for (int j = 0; j < clause.getNamedChildCount(); j++) {
                    TSNode base = clause.getNamedChild(j);
                    if (base.getType().equals("type_arguments")) continue;
                    String baseExpr = stripGenericArgs(text(base, utf8).strip());
                    if (!baseExpr.isEmpty()) {
                        pf.heritageRefs.add(new HeritageRef(className, qname, baseExpr, hKind, spanOf(base, pos)));
                    }
                }
            }
        }
    }

    private static void collectTsParams(ParsedFile pf, TSNode params, byte[] utf8, String scopeKey) {
        if (params == null) return;
        for (int i = 0; i < params.getNamedChildCount(); i++) {
            TSNode p = params.getNamedChild(i);
            TSNode pat = field(p, "pattern");
            TSNode type = field(p, "type");
            if (pat != null && pat.getType().equals("identifier") && type != null) {
                String pName = text(pat, utf8).strip();
                String tExpr = unwrapTsTypeAnnotation(text(type, utf8));
                if (!pName.isEmpty() && !tExpr.isEmpty()) {
                    pf.addVarType(scopeKey, pName, tExpr);
                }
            }
        }
    }

    // --- Go extraction ---

    private static void extractGo(
            ParsedFile pf, TSNode root, byte[] utf8, SourceCoordinates pos, BooleanSupplier cancelled)
            throws IOException {
        walkGo(pf, root, utf8, pos, "", "", cancelled);
    }

    private static void walkGo(
            ParsedFile pf,
            TSNode node,
            byte[] utf8,
            SourceCoordinates pos,
            String enclosingType,
            String enclosingCallable,
            BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        String kind = node.getType();
        if (kind.contains("comment") || kind.equals("interpreted_string_literal") || kind.equals("raw_string_literal")) {
            return;
        }

        if (kind.equals("package_clause")) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                if (node.getNamedChild(i).getType().equals("package_identifier")) {
                    pf.goPackageName = text(node.getNamedChild(i), utf8).strip();
                    break;
                }
            }
            return;
        }

        if (kind.equals("import_spec")) {
            TSNode pathNode = field(node, "path");
            String importPath = unquote(text(pathNode, utf8));
            if (!importPath.isEmpty()) {
                TSNode aliasNode = field(node, "name");
                String alias = aliasNode != null ? text(aliasNode, utf8).strip() : "";
                if (alias.equals("_")) return;
                boolean wildcard = alias.equals(".");
                String defaultAlias = lastSegment(importPath, '/');
                String effectiveAlias = alias.isEmpty() ? defaultAlias : (wildcard ? "*" : alias);
                pf.imports.add(new ImportDirective(
                        importPath,
                        List.of(new ImportedBinding("*", effectiveAlias, !wildcard, wildcard, false)),
                        false,
                        spanOf(node, pos)));
            }
            return;
        }

        if (kind.equals("type_spec") || kind.equals("type_alias")) {
            String typeName = text(field(node, "name"), utf8).strip();
            TSNode typeNode = field(node, "type");
            if (!typeName.isEmpty() && typeNode != null) {
                String tk = typeNode.getType();
                SymbolKind sk = tk.equals("struct_type") ? SymbolKind.STRUCT
                        : tk.equals("interface_type") ? SymbolKind.INTERFACE : SymbolKind.TYPE_ALIAS;
                boolean exported = Character.isUpperCase(typeName.charAt(0));
                pf.declarations.add(new SymbolDef(
                        symbolId(pf.path, typeName), pf.path, pf.language, typeName, typeName,
                        "", sk, exported, spanOf(node, pos)));
                if (tk.equals("struct_type")) {
                    extractGoStructEmbeddings(pf, typeNode, utf8, pos, typeName);
                } else if (tk.equals("interface_type")) {
                    extractGoInterfaceMembers(pf, typeNode, utf8, pos, typeName);
                }
            }
            return;
        }

        if (kind.equals("function_declaration")) {
            String fnName = text(field(node, "name"), utf8).strip();
            if (!fnName.isEmpty()) {
                boolean exported = Character.isUpperCase(fnName.charAt(0));
                pf.declarations.add(new SymbolDef(
                        symbolId(pf.path, fnName), pf.path, pf.language, fnName, fnName,
                        "", SymbolKind.FUNCTION, exported, spanOf(node, pos)));
                collectGoParams(pf, field(node, "parameters"), utf8, fnName);
                TSNode body = field(node, "body");
                if (body != null) {
                    walkGo(pf, body, utf8, pos, "", fnName, cancelled);
                }
                return;
            }
        }

        if (kind.equals("method_declaration")) {
            String methodName = text(field(node, "name"), utf8).strip();
            String recvType = extractGoReceiverType(field(node, "receiver"), utf8);
            String recvVar = extractGoReceiverVar(field(node, "receiver"), utf8);
            if (!methodName.isEmpty()) {
                String qname = recvType.isEmpty() ? methodName : recvType + "." + methodName;
                boolean exported = Character.isUpperCase(methodName.charAt(0));
                SymbolDef sym = new SymbolDef(
                        symbolId(pf.path, qname), pf.path, pf.language, methodName, qname,
                        recvType, SymbolKind.METHOD, exported, spanOf(node, pos));
                pf.declarations.add(sym);
                if (!recvType.isEmpty()) {
                    pf.methodsByType.computeIfAbsent(recvType, k -> new TreeMap<>()).putIfAbsent(methodName, sym);
                    if (!recvVar.isEmpty()) {
                        pf.addVarType(qname, recvVar, recvType);
                    }
                }
                collectGoParams(pf, field(node, "parameters"), utf8, qname);
                TSNode body = field(node, "body");
                if (body != null) {
                    walkGo(pf, body, utf8, pos, recvType, qname, cancelled);
                }
                return;
            }
        }

        if (kind.equals("var_spec")) {
            TSNode nameNode = field(node, "name");
            TSNode typeNode = field(node, "type");
            TSNode valNode = field(node, "value");
            String varName = nameNode != null ? text(nameNode, utf8).strip() : "";
            String typeExpr = typeNode != null ? cleanGoType(text(typeNode, utf8)) : "";
            if (varName.equals("_") && !typeExpr.isEmpty() && valNode != null) {
                // Compile-time interface assertion: var _ pkg.Iface = (*Impl)(nil) or Impl{}
                String implType = extractGoAssertionImplType(text(valNode, utf8));
                if (!implType.isEmpty()) {
                    pf.heritageRefs.add(new HeritageRef(
                            implType, implType, typeExpr, "interface-assertion", spanOf(node, pos)));
                }
            } else if (isSimpleIdentifier(varName) && !typeExpr.isEmpty()) {
                pf.addVarType(enclosingCallable, varName, typeExpr);
            }
        }

        if (kind.equals("short_var_declaration")) {
            TSNode left = field(node, "left");
            TSNode right = field(node, "right");
            if (left != null && right != null && left.getNamedChildCount() >= 1 && right.getNamedChildCount() >= 1) {
                String lhs = text(left.getNamedChild(0), utf8).strip();
                TSNode rhs = right.getNamedChild(0);
                if (isSimpleIdentifier(lhs)) {
                    String inferred = inferGoRhsType(rhs, utf8);
                    if (!inferred.isEmpty()) {
                        pf.addVarType(enclosingCallable, lhs, inferred);
                    }
                }
            }
        }

        if (kind.equals("call_expression")) {
            TSNode fn = field(node, "function");
            if (fn != null) {
                var span = spanOf(node, pos);
                String fnKind = fn.getType();
                String expr = stripGenericArgs(text(fn, utf8).strip());
                boolean dynamic = fnKind.equals("index_expression");
                String recv = "";
                String method = "";
                if (fnKind.equals("selector_expression")) {
                    TSNode op = field(fn, "operand");
                    TSNode fld = field(fn, "field");
                    recv = op != null ? text(op, utf8).strip() : "";
                    method = fld != null ? text(fld, utf8).strip() : "";
                } else if (fnKind.equals("identifier")) {
                    method = expr;
                } else if (dynamic) {
                    method = expr;
                }
                if (!method.isEmpty()) {
                    pf.callSites.add(new CallSite(
                            enclosingCallable.isEmpty() ? "<module>" : enclosingCallable,
                            enclosingType, expr, recv, method, dynamic, false, span));
                }
            }
        }

        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkGo(pf, node.getNamedChild(i), utf8, pos, enclosingType, enclosingCallable, cancelled);
        }
    }

    private static void extractGoStructEmbeddings(
            ParsedFile pf, TSNode structNode, byte[] utf8, SourceCoordinates pos, String structName) {
        for (int i = 0; i < structNode.getNamedChildCount(); i++) {
            TSNode list = structNode.getNamedChild(i);
            if (!list.getType().equals("field_declaration_list")) continue;
            for (int j = 0; j < list.getNamedChildCount(); j++) {
                TSNode fld = list.getNamedChild(j);
                if (!fld.getType().equals("field_declaration")) continue;
                if (field(fld, "name") == null) {
                    TSNode typeNode = field(fld, "type");
                    if (typeNode != null) {
                        String emb = cleanGoType(text(typeNode, utf8));
                        if (!emb.isEmpty()) {
                            pf.heritageRefs.add(new HeritageRef(
                                    structName, structName, emb, "struct-embed", spanOf(fld, pos)));
                        }
                    }
                }
            }
        }
    }

    private static void extractGoInterfaceMembers(
            ParsedFile pf, TSNode ifaceNode, byte[] utf8, SourceCoordinates pos, String ifaceName) {
        var methods = pf.interfaceMethods.computeIfAbsent(ifaceName, k -> new TreeSet<>());
        for (int i = 0; i < ifaceNode.getNamedChildCount(); i++) {
            TSNode c = ifaceNode.getNamedChild(i);
            String ck = c.getType();
            if (ck.equals("method_elem") || ck.equals("method_spec")) {
                String mName = text(field(c, "name"), utf8).strip();
                if (!mName.isEmpty()) {
                    methods.add(mName);
                    String qn = ifaceName + "." + mName;
                    boolean exported = Character.isUpperCase(mName.charAt(0));
                    SymbolDef mSym = new SymbolDef(
                            symbolId(pf.path, qn), pf.path, pf.language, mName, qn,
                            ifaceName, SymbolKind.METHOD, exported, spanOf(c, pos));
                    pf.declarations.add(mSym);
                    pf.methodsByType.computeIfAbsent(ifaceName, k -> new TreeMap<>()).putIfAbsent(mName, mSym);
                }
            } else if (ck.equals("type_elem") || ck.equals("type_identifier") || ck.equals("qualified_type")) {
                String emb = cleanGoType(text(c, utf8));
                if (!emb.isEmpty() && !emb.contains("|")) {
                    pf.heritageRefs.add(new HeritageRef(
                            ifaceName, ifaceName, emb, "interface-embed", spanOf(c, pos)));
                }
            }
        }
    }

    private static void collectGoParams(ParsedFile pf, TSNode params, byte[] utf8, String scopeKey) {
        if (params == null) return;
        for (int i = 0; i < params.getNamedChildCount(); i++) {
            TSNode p = params.getNamedChild(i);
            if (!p.getType().equals("parameter_declaration")) continue;
            TSNode typeNode = field(p, "type");
            String typeExpr = typeNode != null ? cleanGoType(text(typeNode, utf8)) : "";
            if (typeExpr.isEmpty()) continue;
            for (int j = 0; j < p.getNamedChildCount(); j++) {
                TSNode c = p.getNamedChild(j);
                if (c.getType().equals("identifier")) {
                    pf.addVarType(scopeKey, text(c, utf8).strip(), typeExpr);
                }
            }
        }
    }

    private static String extractGoReceiverType(TSNode receiver, byte[] utf8) {
        if (receiver == null) return "";
        for (int i = 0; i < receiver.getNamedChildCount(); i++) {
            TSNode p = receiver.getNamedChild(i);
            if (p.getType().equals("parameter_declaration")) {
                TSNode t = field(p, "type");
                if (t != null) return cleanGoType(text(t, utf8));
            }
        }
        return "";
    }

    private static String extractGoReceiverVar(TSNode receiver, byte[] utf8) {
        if (receiver == null) return "";
        for (int i = 0; i < receiver.getNamedChildCount(); i++) {
            TSNode p = receiver.getNamedChild(i);
            if (p.getType().equals("parameter_declaration")) {
                TSNode n = field(p, "name");
                if (n != null) return text(n, utf8).strip();
            }
        }
        return "";
    }

    private static String cleanGoType(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        while (s.startsWith("*") || s.startsWith("[]")) {
            s = s.startsWith("*") ? s.substring(1).strip() : s.substring(2).strip();
        }
        int bracket = s.indexOf('[');
        if (bracket > 0) s = s.substring(0, bracket);
        return s.strip();
    }

    private static String extractGoAssertionImplType(String rhsText) {
        if (rhsText == null) return "";
        String s = rhsText.strip();
        Matcher ptrNil = Pattern.compile("\\(\\s*\\*?([A-Za-z_][A-Za-z0-9_.]*)\\s*\\)\\s*\\(\\s*nil\\s*\\)").matcher(s);
        if (ptrNil.find()) return ptrNil.group(1);
        Matcher lit = Pattern.compile("&?([A-Za-z_][A-Za-z0-9_.]*)\\s*\\{").matcher(s);
        if (lit.find()) return lit.group(1);
        return "";
    }

    private static String inferGoRhsType(TSNode rhs, byte[] utf8) {
        if (rhs == null) return "";
        String rk = rhs.getType();
        if (rk.equals("unary_expression") && rhs.getNamedChildCount() >= 1) {
            return inferGoRhsType(rhs.getNamedChild(0), utf8);
        }
        if (rk.equals("composite_literal")) {
            TSNode t = field(rhs, "type");
            return t != null ? cleanGoType(text(t, utf8)) : "";
        }
        if (rk.equals("call_expression")) {
            TSNode fn = field(rhs, "function");
            String callee = fn != null ? text(fn, utf8).strip() : "";
            if (callee.equals("new")) {
                TSNode args = field(rhs, "arguments");
                if (args != null && args.getNamedChildCount() >= 1) {
                    return cleanGoType(text(args.getNamedChild(0), utf8));
                }
            }
            // Go constructor idiom: pkg.NewFoo(...) or NewFoo(...)
            String tail = tailIdent(callee);
            if (tail.startsWith("New") && tail.length() > 3 && Character.isUpperCase(tail.charAt(3))) {
                String prefix = callee.contains(".") ? callee.substring(0, callee.lastIndexOf('.') + 1) : "";
                return prefix + tail.substring(3);
            }
        }
        return "";
    }

    // --- Rust extraction ---

    private static void extractRust(
            ParsedFile pf, TSNode root, byte[] utf8, SourceCoordinates pos, BooleanSupplier cancelled)
            throws IOException {
        walkRust(pf, root, utf8, pos, "", "", false, cancelled);
    }

    private static void walkRust(
            ParsedFile pf,
            TSNode node,
            byte[] utf8,
            SourceCoordinates pos,
            String enclosingType,
            String enclosingCallable,
            boolean insideTraitImpl,
            BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        String kind = node.getType();
        if (kind.contains("comment") || kind.equals("string_literal") || kind.equals("raw_string_literal")) {
            return;
        }

        if (kind.equals("mod_item")) {
            String modName = text(field(node, "name"), utf8).strip();
            TSNode body = field(node, "body");
            if (!modName.isEmpty() && body == null) {
                pf.rustExternalMods.add(modName);
            }
            if (body != null) {
                walkRust(pf, body, utf8, pos, enclosingType, enclosingCallable, insideTraitImpl, cancelled);
            }
            return;
        }

        if (kind.equals("use_declaration")) {
            boolean isPub = hasChildOfType(node, "visibility_modifier");
            TSNode arg = field(node, "argument");
            if (arg != null) {
                var span = spanOf(node, pos);
                var entries = new ArrayList<RustUseEntry>();
                expandRustUseTree(arg, "", utf8, entries);
                for (RustUseEntry e : entries) {
                    String fullPath = e.fullPath();
                    if (fullPath.isEmpty()) continue;
                    if (e.wildcard()) {
                        pf.imports.add(new ImportDirective(
                                fullPath,
                                List.of(new ImportedBinding("*", "*", false, true, false)),
                                isPub,
                                span));
                    } else {
                        int sep = fullPath.lastIndexOf("::");
                        String modPart = sep > 0 ? fullPath.substring(0, sep) : fullPath;
                        String symPart = sep > 0 ? fullPath.substring(sep + 2) : fullPath;
                        String alias = e.alias().isEmpty() ? symPart : e.alias();
                        pf.imports.add(new ImportDirective(
                                modPart,
                                List.of(new ImportedBinding(symPart, alias, false, false, false)),
                                isPub,
                                span));
                    }
                }
            }
            return;
        }

        if (kind.equals("struct_item") || kind.equals("enum_item") || kind.equals("trait_item")
                || kind.equals("type_item") || kind.equals("const_item")) {
            String name = text(field(node, "name"), utf8).strip();
            if (!name.isEmpty()) {
                boolean exported = hasChildOfType(node, "visibility_modifier");
                SymbolKind sk = switch (kind) {
                    case "struct_item" -> SymbolKind.STRUCT;
                    case "enum_item" -> SymbolKind.ENUM;
                    case "trait_item" -> SymbolKind.TRAIT;
                    case "type_item" -> SymbolKind.TYPE_ALIAS;
                    default -> SymbolKind.CONSTANT;
                };
                pf.declarations.add(new SymbolDef(
                        symbolId(pf.path, name), pf.path, pf.language, name, name,
                        "", sk, exported, spanOf(node, pos)));
                if (kind.equals("trait_item")) {
                    for (int i = 0; i < node.getNamedChildCount(); i++) {
                        TSNode c = node.getNamedChild(i);
                        if (c.getType().equals("trait_bounds")) {
                            for (int j = 0; j < c.getNamedChildCount(); j++) {
                                TSNode b = c.getNamedChild(j);
                                if (b.getType().equals("lifetime")) continue;
                                String baseExpr = stripGenericArgs(text(b, utf8).strip());
                                if (!baseExpr.isEmpty()) {
                                    pf.heritageRefs.add(new HeritageRef(
                                            name, name, baseExpr, "trait-supertrait", spanOf(b, pos)));
                                }
                            }
                        } else if (c.getType().equals("declaration_list")) {
                            for (int j = 0; j < c.getNamedChildCount(); j++) {
                                TSNode member = c.getNamedChild(j);
                                if (member.getType().equals("function_signature_item")
                                        || member.getType().equals("function_item")) {
                                    String mName = text(field(member, "name"), utf8).strip();
                                    if (!mName.isEmpty()) {
                                        String qn = name + "." + mName;
                                        SymbolDef mSym = new SymbolDef(
                                                symbolId(pf.path, qn), pf.path, pf.language, mName, qn,
                                                name, SymbolKind.METHOD, exported, spanOf(member, pos));
                                        pf.declarations.add(mSym);
                                        pf.methodsByType.computeIfAbsent(name, k -> new TreeMap<>()).putIfAbsent(mName, mSym);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return;
        }

        if (kind.equals("impl_item")) {
            TSNode traitNode = field(node, "trait");
            TSNode typeNode = field(node, "type");
            String targetType = typeNode != null ? cleanRustType(text(typeNode, utf8)) : "";
            String traitExpr = traitNode != null ? cleanRustType(text(traitNode, utf8)) : "";
            if (!targetType.isEmpty() && !traitExpr.isEmpty()) {
                pf.heritageRefs.add(new HeritageRef(
                        targetType, targetType, traitExpr, "trait-impl", spanOf(node, pos)));
            }
            TSNode body = field(node, "body");
            if (body != null) {
                for (int i = 0; i < body.getNamedChildCount(); i++) {
                    walkRust(pf, body.getNamedChild(i), utf8, pos, targetType, targetType, !traitExpr.isEmpty(), cancelled);
                }
            }
            return;
        }

        if (kind.equals("function_item")) {
            String fnName = text(field(node, "name"), utf8).strip();
            if (!fnName.isEmpty()) {
                boolean isMethod = !enclosingType.isEmpty();
                String qname = isMethod ? enclosingType + "." + fnName : fnName;
                boolean exported = insideTraitImpl || hasChildOfType(node, "visibility_modifier");
                SymbolDef sym = new SymbolDef(
                        symbolId(pf.path, qname), pf.path, pf.language, fnName, qname,
                        enclosingType, isMethod ? SymbolKind.METHOD : SymbolKind.FUNCTION, exported, spanOf(node, pos));
                pf.declarations.add(sym);
                if (isMethod) {
                    pf.methodsByType.computeIfAbsent(enclosingType, k -> new TreeMap<>()).putIfAbsent(fnName, sym);
                }
                collectRustParams(pf, field(node, "parameters"), utf8, qname);
                TSNode body = field(node, "body");
                if (body != null) {
                    walkRust(pf, body, utf8, pos, enclosingType, qname, insideTraitImpl, cancelled);
                }
                return;
            }
        }

        if (kind.equals("let_declaration")) {
            TSNode pat = field(node, "pattern");
            TSNode typeNode = field(node, "type");
            TSNode valNode = field(node, "value");
            String varName = extractRustVarName(pat, utf8);
            if (!varName.isEmpty()) {
                if (typeNode != null) {
                    String t = cleanRustType(text(typeNode, utf8));
                    if (!t.isEmpty()) pf.addVarType(enclosingCallable, varName, t);
                } else if (valNode != null) {
                    String inferred = inferRustRhsType(valNode, utf8);
                    if (!inferred.isEmpty()) pf.addVarType(enclosingCallable, varName, inferred);
                }
            }
        }

        if (kind.equals("call_expression")) {
            TSNode fn = field(node, "function");
            if (fn != null) {
                var span = spanOf(node, pos);
                String fnKind = fn.getType();
                String expr = stripRustTurbofish(text(fn, utf8).strip());
                boolean dynamic = fnKind.equals("index_expression");
                String recv = "";
                String method = "";
                if (fnKind.equals("field_expression")) {
                    TSNode val = field(fn, "value");
                    TSNode fld = field(fn, "field");
                    recv = val != null ? text(val, utf8).strip() : "";
                    method = fld != null ? text(fld, utf8).strip() : "";
                } else if (fnKind.equals("scoped_identifier")) {
                    int sep = expr.lastIndexOf("::");
                    if (sep > 0) {
                        recv = expr.substring(0, sep);
                        method = expr.substring(sep + 2);
                    } else {
                        method = expr;
                    }
                } else if (fnKind.equals("generic_function")) {
                    TSNode inner = field(fn, "function");
                    if (inner != null) {
                        String innerText = text(inner, utf8).strip();
                        int sep = innerText.lastIndexOf("::");
                        if (sep > 0) {
                            recv = innerText.substring(0, sep);
                            method = innerText.substring(sep + 2);
                        } else {
                            method = innerText;
                        }
                    }
                } else if (fnKind.equals("identifier")) {
                    method = expr;
                } else if (dynamic) {
                    method = expr;
                }
                if (!method.isEmpty()) {
                    pf.callSites.add(new CallSite(
                            enclosingCallable.isEmpty() ? "<module>" : enclosingCallable,
                            enclosingType, expr, recv, method, dynamic, false, span));
                }
            }
        }

        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walkRust(pf, node.getNamedChild(i), utf8, pos, enclosingType, enclosingCallable, insideTraitImpl, cancelled);
        }
    }

    private record RustUseEntry(String fullPath, String alias, boolean wildcard) {
    }

    private static void expandRustUseTree(TSNode node, String prefix, byte[] utf8, List<RustUseEntry> out) {
        String kind = node.getType();
        if (kind.equals("identifier") || kind.equals("crate") || kind.equals("self") || kind.equals("super")) {
            String part = text(node, utf8).strip();
            if (part.equals("self") && !prefix.isEmpty()) {
                out.add(new RustUseEntry(prefix, "", false));
            } else {
                String full = prefix.isEmpty() ? part : prefix + "::" + part;
                out.add(new RustUseEntry(full, "", false));
            }
        } else if (kind.equals("scoped_identifier")) {
            String part = text(node, utf8).strip();
            String full = prefix.isEmpty() ? part : prefix + "::" + part;
            out.add(new RustUseEntry(full, "", false));
        } else if (kind.equals("use_as_clause")) {
            TSNode pathNode = field(node, "path");
            TSNode aliasNode = field(node, "alias");
            String p = pathNode != null ? text(pathNode, utf8).strip() : "";
            String alias = aliasNode != null ? text(aliasNode, utf8).strip() : "";
            if (!p.isEmpty() && !alias.equals("_")) {
                String full = p.equals("self") && !prefix.isEmpty()
                        ? prefix
                        : (prefix.isEmpty() ? p : prefix + "::" + p);
                out.add(new RustUseEntry(full, alias, false));
            }
        } else if (kind.equals("use_wildcard")) {
            String base = text(node, utf8).strip();
            if (base.endsWith("::*")) base = base.substring(0, base.length() - 3);
            else if (base.equals("*")) base = "";
            String full = base.isEmpty() ? prefix : (prefix.isEmpty() ? base : prefix + "::" + base);
            if (!full.isEmpty()) {
                out.add(new RustUseEntry(full, "*", true));
            }
        } else if (kind.equals("scoped_use_list")) {
            TSNode pathNode = field(node, "path");
            TSNode listNode = field(node, "list");
            String seg = pathNode != null ? text(pathNode, utf8).strip() : "";
            String nextPrefix = seg.isEmpty() ? prefix : (prefix.isEmpty() ? seg : prefix + "::" + seg);
            if (listNode != null) {
                for (int i = 0; i < listNode.getNamedChildCount(); i++) {
                    expandRustUseTree(listNode.getNamedChild(i), nextPrefix, utf8, out);
                }
            }
        } else if (kind.equals("use_list")) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                expandRustUseTree(node.getNamedChild(i), prefix, utf8, out);
            }
        }
    }

    private static void collectRustParams(ParsedFile pf, TSNode params, byte[] utf8, String scopeKey) {
        if (params == null) return;
        for (int i = 0; i < params.getNamedChildCount(); i++) {
            TSNode p = params.getNamedChild(i);
            if (!p.getType().equals("parameter")) continue;
            String pName = extractRustVarName(field(p, "pattern"), utf8);
            TSNode tNode = field(p, "type");
            if (!pName.isEmpty() && tNode != null) {
                String t = cleanRustType(text(tNode, utf8));
                if (!t.isEmpty()) pf.addVarType(scopeKey, pName, t);
            }
        }
    }

    private static String extractRustVarName(TSNode pat, byte[] utf8) {
        if (pat == null) return "";
        String pk = pat.getType();
        if (pk.equals("identifier")) return text(pat, utf8).strip();
        if (pk.equals("mut_pattern") && pat.getNamedChildCount() > 0) {
            return extractRustVarName(pat.getNamedChild(0), utf8);
        }
        return "";
    }

    private static String cleanRustType(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        s = s.replaceFirst("^&\\s*(?:'[A-Za-z_][A-Za-z0-9_]*\\s+)?(?:mut\\s+)?", "");
        s = s.replaceFirst("^dyn\\s+", "").replaceFirst("^impl\\s+", "");
        s = stripGenericArgs(s);
        return s.strip();
    }

    private static String inferRustRhsType(TSNode val, byte[] utf8) {
        String vk = val.getType();
        if (vk.equals("struct_expression")) {
            TSNode n = field(val, "name");
            return n != null ? cleanRustType(text(n, utf8)) : "";
        }
        if (vk.equals("call_expression")) {
            TSNode fn = field(val, "function");
            if (fn != null && fn.getType().equals("scoped_identifier")) {
                String expr = stripRustTurbofish(text(fn, utf8).strip());
                int sep = expr.lastIndexOf("::");
                if (sep > 0) {
                    String lhs = expr.substring(0, sep);
                    String tail = tailIdent(lhs);
                    if (!tail.isEmpty() && Character.isUpperCase(tail.charAt(0))) {
                        return lhs;
                    }
                }
            }
        }
        return "";
    }

    private static void finalizeLocalExports(ParsedFile pf) {
        for (SymbolDef sym : pf.declarations) {
            if (sym.kind() == SymbolKind.METHOD) continue;
            boolean exported = sym.exported() || pf.exportedLocalNames.contains(sym.name());
            if (pf.language.equals("python") && pf.hasPythonDunderAll) {
                exported = pf.pythonDunderAll.contains(sym.name());
            }
            if (exported) {
                pf.localExports.putIfAbsent(sym.name(), sym);
            }
        }
        for (var aliasEntry : pf.localExportAliases.entrySet()) {
            String exportedAlias = aliasEntry.getKey();
            String localName = aliasEntry.getValue();
            pf.declarations.stream()
                    .filter(s -> s.kind() != SymbolKind.METHOD && s.name().equals(localName))
                    .findFirst()
                    .ifPresent(sym -> pf.localExports.put(exportedAlias, sym));
        }
    }

    // -------------------------------------------------------------------------
    // Phase 2a: Barrel / Re-export Closure DFS
    // -------------------------------------------------------------------------

    private static Map<String, SymbolDef> resolveModuleExportsDfs(
            String path,
            Map<String, ParsedFile> parsedByPath,
            ManifestIndex manifests,
            Set<String> allFilePaths,
            Map<String, List<ParsedFile>> goPackagesByDir,
            RustModuleIndex rustModules,
            Map<String, Map<String, SymbolDef>> rawExportsByModule,
            Map<String, Map<String, SymbolDef>> resolvedExportsByModule,
            Set<String> visiting,
            int depth) {
        Map<String, SymbolDef> cached = resolvedExportsByModule.get(path);
        if (cached != null) return cached;
        var merged = new TreeMap<>(rawExportsByModule.getOrDefault(path, Map.of()));
        if (depth > MAX_REEXPORT_DEPTH || !visiting.add(path)) {
            return merged;
        }
        ParsedFile pf = parsedByPath.get(path);
        if (pf != null) {
            for (ImportDirective imp : pf.imports) {
                if (!imp.reExport()) continue;
                List<String> targetPaths = resolveImportTargetFiles(
                        pf, imp, parsedByPath, manifests, allFilePaths, goPackagesByDir, rustModules);
                for (ImportedBinding b : imp.bindings()) {
                    if (b.wildcardImport()) {
                        for (String tp : targetPaths) {
                            if (tp.equals(path)) continue;
                            Map<String, SymbolDef> targetExps = resolveModuleExportsDfs(
                                    tp, parsedByPath, manifests, allFilePaths, goPackagesByDir, rustModules,
                                    rawExportsByModule, resolvedExportsByModule, visiting, depth + 1);
                            for (var te : targetExps.entrySet()) {
                                if (!te.getKey().equals("default")) {
                                    merged.putIfAbsent(te.getKey(), te.getValue());
                                }
                            }
                        }
                    } else if (!b.namespaceImport()) {
                        // Named re-export (e.g. export { A as B } from './sub', Python __init__.py, Rust pub use)
                        for (String tp : targetPaths) {
                            if (tp.equals(path)) continue;
                            Map<String, SymbolDef> targetExps = resolveModuleExportsDfs(
                                    tp, parsedByPath, manifests, allFilePaths, goPackagesByDir, rustModules,
                                    rawExportsByModule, resolvedExportsByModule, visiting, depth + 1);
                            SymbolDef found = targetExps.get(b.importedName());
                            if (found == null) {
                                // Also allow non-strict lookup in Python/Rust submodule when explicitly re-exported
                                ParsedFile tpf = parsedByPath.get(tp);
                                if (tpf != null) {
                                    found = findDeclaredTopLevel(tpf, b.importedName());
                                }
                            }
                            if (found != null) {
                                if (!pf.language.equals("python") || !pf.hasPythonDunderAll
                                        || pf.pythonDunderAll.contains(b.localAlias())) {
                                    merged.put(b.localAlias(), found);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
        visiting.remove(path);
        Map<String, SymbolDef> immutable = Collections.unmodifiableMap(merged);
        resolvedExportsByModule.put(path, immutable);
        return immutable;
    }

    // -------------------------------------------------------------------------
    // Phase 2b: Per-Module Import, Heritage & Call Resolution
    // -------------------------------------------------------------------------

    private static final class FileImportScope {
        // localAlias -> sorted candidate SymbolDefs (size==1 means unique import-scoped binding)
        final Map<String, TreeSet<SymbolDef>> importedSymbols = new TreeMap<>();
        // localNamespaceAlias -> sorted target file paths (e.g. Go package files, TS * as ns, Py import mod)
        final Map<String, TreeSet<String>> importedNamespaces = new TreeMap<>();
        // wildcard imported target file paths
        final TreeSet<String> wildcardModules = new TreeSet<>();
        // localAlias -> whether it was explicitly imported from external/stdlib (not in repo)
        final Set<String> externalImports = new TreeSet<>();
    }

    private static FileImportScope resolveFileImports(
            ParsedFile pf,
            Map<String, ParsedFile> parsedByPath,
            ManifestIndex manifests,
            Set<String> allFilePaths,
            Map<String, List<ParsedFile>> goPackagesByDir,
            RustModuleIndex rustModules,
            Map<String, Map<String, SymbolDef>> resolvedExportsByModule,
            Set<ResolvedEdge> edges) {
        var scope = new FileImportScope();

        for (ImportDirective imp : pf.imports) {
            List<String> targetPaths = resolveImportTargetFiles(
                    pf, imp, parsedByPath, manifests, allFilePaths, goPackagesByDir, rustModules);

            if (targetPaths.isEmpty()) {
                String fallbackKind = classifyImportResolutionKind(pf, imp, manifests);
                for (ImportedBinding b : imp.bindings()) {
                    if (!b.wildcardImport() && !b.namespaceImport() && !b.importedName().isEmpty()) {
                        Optional<String> subMod = resolveSubmoduleImport(
                                pf, imp.rawSpecifier(), b.importedName(), manifests, allFilePaths, rustModules);
                        if (subMod.isPresent() && !subMod.orElseThrow().equals(pf.path)) {
                            String subPath = subMod.orElseThrow();
                            scope.importedNamespaces.computeIfAbsent(b.localAlias(), k -> new TreeSet<>()).add(subPath);
                            emitImportEdge(pf, imp.span(), subPath, "<module>", moduleSpan(parsedByPath.get(subPath)),
                                    fallbackKind + ":submodule", edges);
                            continue;
                        }
                    }
                    if (!b.localAlias().isEmpty() && !b.localAlias().equals("*")) {
                        scope.externalImports.add(b.localAlias());
                    }
                }
                continue;
            }

            String resolutionKind = classifyImportResolutionKind(pf, imp, manifests);

            for (ImportedBinding b : imp.bindings()) {
                if (b.wildcardImport()) {
                    for (String tp : targetPaths) {
                        if (tp.equals(pf.path)) continue;
                        scope.wildcardModules.add(tp);
                        emitImportEdge(pf, imp.span(), tp, "<module>", moduleSpan(parsedByPath.get(tp)),
                                resolutionKind + ":wildcard", edges);
                    }
                } else if (b.namespaceImport()) {
                    String effectiveAlias = b.localAlias();
                    if (pf.language.equals("go") && !targetPaths.isEmpty()) {
                        // Prefer the actual declared Go package name if alias wasn't explicitly overridden
                        String defaultSeg = lastSegment(imp.rawSpecifier(), '/');
                        if (effectiveAlias.equals(defaultSeg)) {
                            ParsedFile firstTarget = parsedByPath.get(targetPaths.getFirst());
                            if (firstTarget != null && !firstTarget.goPackageName.isEmpty()) {
                                effectiveAlias = firstTarget.goPackageName;
                            }
                        }
                    }
                    for (String tp : targetPaths) {
                        if (tp.equals(pf.path)) continue;
                        if (!effectiveAlias.isEmpty()) {
                            scope.importedNamespaces.computeIfAbsent(effectiveAlias, k -> new TreeSet<>()).add(tp);
                        }
                        emitImportEdge(pf, imp.span(), tp, "<module>", moduleSpan(parsedByPath.get(tp)),
                                resolutionKind + ":namespace", edges);
                    }
                } else {
                    // Named or default symbol import
                    boolean boundSymbol = false;
                    for (String tp : targetPaths) {
                        if (tp.equals(pf.path)) continue;
                        Map<String, SymbolDef> targetExports = resolvedExportsByModule.getOrDefault(tp, Map.of());
                        SymbolDef targetSym = targetExports.get(b.importedName());
                        if (targetSym == null) {
                            ParsedFile tpf = parsedByPath.get(tp);
                            if (tpf != null) {
                                targetSym = findDeclaredTopLevel(tpf, b.importedName());
                            }
                        }
                        if (targetSym != null) {
                            scope.importedSymbols.computeIfAbsent(b.localAlias(), k -> new TreeSet<>()).add(targetSym);
                            String detail = targetSym.modulePath().equals(tp)
                                    ? resolutionKind
                                    : resolutionKind + "->reexport(" + tp + ")";
                            emitImportEdge(pf, imp.span(), targetSym.modulePath(), targetSym.qualifiedName(),
                                    targetSym.span(), detail, edges);
                            boundSymbol = true;
                        }
                    }
                    // Python `from . import submodule` or Rust `use crate::submodule;` where binding is a submodule
                    if (!boundSymbol) {
                        Optional<String> subMod = resolveSubmoduleImport(
                                pf, imp.rawSpecifier(), b.importedName(), manifests, allFilePaths, rustModules);
                        if (subMod.isPresent() && !subMod.orElseThrow().equals(pf.path)) {
                            String subPath = subMod.orElseThrow();
                            scope.importedNamespaces.computeIfAbsent(b.localAlias(), k -> new TreeSet<>()).add(subPath);
                            emitImportEdge(pf, imp.span(), subPath, "<module>", moduleSpan(parsedByPath.get(subPath)),
                                    resolutionKind + ":submodule", edges);
                        }
                    }
                }
            }
        }
        return scope;
    }

    private static void emitImportEdge(
            ParsedFile pf,
            SourceCoordinates.Range sourceSpan,
            String targetPath,
            String targetSymbol,
            SourceCoordinates.Range targetSpan,
            String detail,
            Set<ResolvedEdge> edges) {
        if (pf.path.equals(targetPath)) return;
        edges.add(new ResolvedEdge(
                EdgeKind.IMPORTS,
                pf.language,
                pf.path,
                "<module>",
                sourceSpan,
                targetPath,
                targetSymbol,
                targetSpan,
                BASE_TRUST_TIER,
                PROVENANCE_POLYGLOT_IMPORT_SCOPED,
                detail));
    }

    private static void resolveHeritage(
            ParsedFile pf,
            FileImportScope importScope,
            Map<String, ParsedFile> parsedByPath,
            Map<String, List<ParsedFile>> goPackagesByDir,
            Map<String, Map<String, SymbolDef>> resolvedExportsByModule,
            Map<String, TreeSet<SymbolDef>> inheritanceGraph,
            Set<ResolvedEdge> edges) {
        for (HeritageRef hr : pf.heritageRefs) {
            SymbolDef childDef = findDeclaredTopLevel(pf, hr.childName());
            String childQname = childDef != null ? childDef.qualifiedName() : hr.childQualifiedName();
            String childId = symbolId(pf.path, childQname);
            List<SymbolDef> targets = resolveTypeReference(
                    pf, hr.baseExpression(), importScope, parsedByPath, goPackagesByDir, resolvedExportsByModule);
            if (targets.size() == 1) {
                SymbolDef parent = targets.getFirst();
                inheritanceGraph.computeIfAbsent(childId, k -> new TreeSet<>()).add(parent);
                if (!parent.modulePath().equals(pf.path)) {
                    edges.add(new ResolvedEdge(
                            EdgeKind.INHERITS,
                            pf.language,
                            pf.path,
                            childQname,
                            hr.span(),
                            parent.modulePath(),
                            parent.qualifiedName(),
                            parent.span(),
                            BASE_TRUST_TIER,
                            PROVENANCE_POLYGLOT_IMPORT_SCOPED,
                            hr.heritageKind()));
                }
            }
        }

        // Go import-scoped structural interface satisfaction:
        // If a Go file imports a package declaring interface I (with >= 1 method), and a struct S
        // in this file declares every method name required by I, record S INHERITS I.
        if (pf.language.equals("go")) {
            var candidateIfaces = new TreeSet<SymbolDef>();
            for (var nsPaths : importScope.importedNamespaces.values()) {
                for (String tp : nsPaths) {
                    ParsedFile tpf = parsedByPath.get(tp);
                    if (tpf == null) continue;
                    for (SymbolDef sym : tpf.declarations) {
                        if (sym.kind() == SymbolKind.INTERFACE && sym.exported()) {
                            candidateIfaces.add(sym);
                        }
                    }
                }
            }
            for (SymbolDef structSym : pf.declarations) {
                if (structSym.kind() != SymbolKind.STRUCT) continue;
                Set<String> structMethods = collectGoStructMethodNames(pf, structSym.name(), goPackagesByDir);
                if (structMethods.isEmpty()) continue;
                for (SymbolDef iface : candidateIfaces) {
                    ParsedFile ifaceFile = parsedByPath.get(iface.modulePath());
                    if (ifaceFile == null) continue;
                    Set<String> reqMethods = ifaceFile.interfaceMethods.getOrDefault(iface.name(), Set.of());
                    if (!reqMethods.isEmpty() && structMethods.containsAll(reqMethods)) {
                        inheritanceGraph.computeIfAbsent(structSym.symbolId(), k -> new TreeSet<>()).add(iface);
                        if (!iface.modulePath().equals(pf.path)) {
                            edges.add(new ResolvedEdge(
                                    EdgeKind.INHERITS,
                                    pf.language,
                                    pf.path,
                                    structSym.qualifiedName(),
                                    structSym.span(),
                                    iface.modulePath(),
                                    iface.qualifiedName(),
                                    iface.span(),
                                    BASE_TRUST_TIER,
                                    PROVENANCE_POLYGLOT_IMPORT_SCOPED,
                                    "go-structural-interface-impl"));
                        }
                    }
                }
            }
        }
    }

    private static Set<String> collectGoStructMethodNames(
            ParsedFile pf, String structName, Map<String, List<ParsedFile>> goPackagesByDir) {
        var names = new TreeSet<String>();
        for (ParsedFile sib : goPackagesByDir.getOrDefault(parentDir(pf.path), List.of())) {
            if (!sib.goPackageName.equals(pf.goPackageName)) continue;
            var m = sib.methodsByType.get(structName);
            if (m != null) names.addAll(m.keySet());
        }
        return names;
    }

    private static void resolveCallsAndGaps(
            ParsedFile pf,
            FileImportScope importScope,
            Map<String, ParsedFile> parsedByPath,
            Map<String, List<ParsedFile>> goPackagesByDir,
            Map<String, Map<String, SymbolDef>> resolvedExportsByModule,
            Map<String, TreeSet<SymbolDef>> methodsByLangAndName,
            Map<String, TreeSet<SymbolDef>> inheritanceGraph,
            Set<ResolvedEdge> edges,
            Set<CapabilityGapRecord> gaps,
            EvidenceContext context) {
        for (CallSite cs : pf.callSites) {
            // 1. Dynamic target -> NEVER guess, always emit AMBIGUOUS_POLYGLOT_CALL gap
            if (cs.dynamicTarget()) {
                gaps.add(buildAmbiguousCallGap(pf, cs, List.of(), "unresolved-dynamic-target", context));
                continue;
            }

            String method = cs.methodName();
            String recv = cs.receiver();

            // Skip standard library / language built-ins when unqualified
            if (recv.isEmpty() && BUILTIN_CALLS.contains(method)) {
                continue;
            }
            if (!recv.isEmpty() && (BUILTIN_CALLS.contains(firstSegment(recv, '.'))
                    || BUILTIN_CALLS.contains(firstSegment(recv, ':')))) {
                continue;
            }

            // 2. Unqualified call: foo(...) or new Foo(...)
            if (recv.isEmpty()) {
                // Local symbol shadows imports
                if (findDeclaredTopLevel(pf, method) != null) {
                    continue;
                }
                // Check explicit imports
                var imported = importScope.importedSymbols.get(method);
                if (imported != null && !imported.isEmpty()) {
                    if (imported.size() == 1) {
                        SymbolDef target = imported.getFirst();
                        if (!target.modulePath().equals(pf.path)) {
                            edges.add(new ResolvedEdge(
                                    EdgeKind.CALLS_SYMBOL, pf.language, pf.path, cs.callerSymbol(), cs.span(),
                                    target.modulePath(), target.qualifiedName(), target.span(),
                                    BASE_TRUST_TIER, PROVENANCE_POLYGLOT_IMPORT_SCOPED,
                                    cs.constructorCall() ? "imported-constructor-call" : "direct-import-call"));
                        }
                    } else {
                        gaps.add(buildAmbiguousCallGap(pf, cs, List.copyOf(imported),
                                "multiple-imported-candidates", context));
                    }
                    continue;
                }
                // Check Go same-package sibling files
                if (pf.language.equals("go")) {
                    var sibs = findGoSamePackageSymbols(pf, method, goPackagesByDir);
                    if (sibs.size() == 1 && !sibs.getFirst().modulePath().equals(pf.path)) {
                        SymbolDef target = sibs.getFirst();
                        edges.add(new ResolvedEdge(
                                EdgeKind.CALLS_SYMBOL, pf.language, pf.path, cs.callerSymbol(), cs.span(),
                                target.modulePath(), target.qualifiedName(), target.span(),
                                BASE_TRUST_TIER, PROVENANCE_POLYGLOT_IMPORT_SCOPED, "go-same-package-call"));
                        continue;
                    }
                }
                // Check wildcard imports
                if (!importScope.wildcardModules.isEmpty()) {
                    var wildMatches = new TreeSet<SymbolDef>();
                    for (String wm : importScope.wildcardModules) {
                        SymbolDef s = resolvedExportsByModule.getOrDefault(wm, Map.of()).get(method);
                        if (s != null) wildMatches.add(s);
                    }
                    if (wildMatches.size() == 1) {
                        SymbolDef target = wildMatches.getFirst();
                        if (!target.modulePath().equals(pf.path)) {
                            edges.add(new ResolvedEdge(
                                    EdgeKind.CALLS_SYMBOL, pf.language, pf.path, cs.callerSymbol(), cs.span(),
                                    target.modulePath(), target.qualifiedName(), target.span(),
                                    BASE_TRUST_TIER, PROVENANCE_POLYGLOT_IMPORT_SCOPED, "wildcard-import-call"));
                        }
                    } else if (wildMatches.size() > 1) {
                        gaps.add(buildAmbiguousCallGap(pf, cs, List.copyOf(wildMatches),
                                "ambiguous-wildcard-import-call", context));
                    }
                }
                continue;
            }

            // 3. Qualified / receiver call: recv.method(...) or Type::method(...)
            String normalizedRecv = recv.replace("()", "");
            // 3a. super / this / self receiver -> walk own class + inheritance chain
            if (normalizedRecv.equals("super") || normalizedRecv.equals("this") || normalizedRecv.equals("self")) {
                if (!cs.enclosingType().isEmpty()) {
                    boolean skipSelf = normalizedRecv.equals("super");
                    if (!skipSelf && pf.methodsByType.getOrDefault(cs.enclosingType(), Map.of()).containsKey(method)) {
                        continue; // Intra-file self/this call
                    }
                    var inherited = resolveInheritedMethods(
                            symbolId(pf.path, cs.enclosingType()), method, parsedByPath, inheritanceGraph);
                    if (inherited.size() == 1) {
                        SymbolDef target = inherited.getFirst();
                        if (!target.modulePath().equals(pf.path)) {
                            edges.add(new ResolvedEdge(
                                    EdgeKind.CALLS_SYMBOL, pf.language, pf.path, cs.callerSymbol(), cs.span(),
                                    target.modulePath(), target.qualifiedName(), target.span(),
                                    BASE_TRUST_TIER, PROVENANCE_POLYGLOT_IMPORT_SCOPED,
                                    skipSelf ? "super-inherited-call" : "inherited-method-call"));
                        }
                    } else if (inherited.size() > 1) {
                        gaps.add(buildAmbiguousCallGap(pf, cs, List.copyOf(inherited),
                                "ambiguous-inherited-method-call", context));
                    }
                }
                continue;
            }

            // 3b. External stdlib/3rd-party namespace import -> ignore cleanly
            String recvRoot = firstSegment(firstSegment(recv, '.'), ':');
            if (importScope.externalImports.contains(recvRoot)) {
                continue;
            }

            // 3c. Imported namespace / module / Go package call (e.g. pkg.Add(), api.fetch(), mod::run())
            TreeSet<String> nsTargets = importScope.importedNamespaces.get(recv);
            if (nsTargets != null && !nsTargets.isEmpty()) {
                var matches = new TreeSet<SymbolDef>();
                for (String tp : nsTargets) {
                    SymbolDef found = resolvedExportsByModule.getOrDefault(tp, Map.of()).get(method);
                    if (found == null) {
                        ParsedFile tpf = parsedByPath.get(tp);
                        if (tpf != null) {
                            found = findDeclaredTopLevel(tpf, method);
                        }
                    }
                    if (found != null) matches.add(found);
                }
                if (matches.size() == 1) {
                    SymbolDef target = matches.getFirst();
                    if (!target.modulePath().equals(pf.path)) {
                        edges.add(new ResolvedEdge(
                                EdgeKind.CALLS_SYMBOL, pf.language, pf.path, cs.callerSymbol(), cs.span(),
                                target.modulePath(), target.qualifiedName(), target.span(),
                                BASE_TRUST_TIER, PROVENANCE_POLYGLOT_IMPORT_SCOPED, "qualified-module-call"));
                    }
                } else if (matches.size() > 1) {
                    gaps.add(buildAmbiguousCallGap(pf, cs, List.copyOf(matches),
                            "ambiguous-module-call", context));
                }
                continue;
            }

            // 3d. Static / associated method call on an imported or local type (e.g. Service::new(), pkg.Service.run())
            List<SymbolDef> staticTypeMatches = resolveTypeReference(
                    pf, recv, importScope, parsedByPath, goPackagesByDir, resolvedExportsByModule);
            if (!staticTypeMatches.isEmpty()) {
                var methodMatches = new TreeSet<SymbolDef>();
                for (SymbolDef typeSym : staticTypeMatches) {
                    methodMatches.addAll(lookupMethodOnTypeOrParents(typeSym, method, parsedByPath, inheritanceGraph));
                }
                if (methodMatches.size() == 1) {
                    SymbolDef target = methodMatches.getFirst();
                    if (!target.modulePath().equals(pf.path)) {
                        edges.add(new ResolvedEdge(
                                EdgeKind.CALLS_SYMBOL, pf.language, pf.path, cs.callerSymbol(), cs.span(),
                                target.modulePath(), target.qualifiedName(), target.span(),
                                BASE_TRUST_TIER, PROVENANCE_POLYGLOT_IMPORT_SCOPED, "static-type-method-call"));
                    }
                } else if (methodMatches.size() > 1) {
                    gaps.add(buildAmbiguousCallGap(pf, cs, List.copyOf(methodMatches),
                            "ambiguous-static-method-call", context));
                }
                continue;
            }

            // 3e. Typed or locally constructed receiver variable (e.g. svc = UserService(); svc.load())
            String scopeKey = cs.callerSymbol().equals("<module>") ? "" : cs.callerSymbol();
            Set<String> boundTypes = pf.lookupVarTypes(scopeKey, recv);
            if (!boundTypes.isEmpty()) {
                var methodMatches = new TreeSet<SymbolDef>();
                for (String typeExpr : boundTypes) {
                    for (SymbolDef typeSym : resolveTypeReference(
                            pf, typeExpr, importScope, parsedByPath, goPackagesByDir, resolvedExportsByModule)) {
                        methodMatches.addAll(lookupMethodOnTypeOrParents(typeSym, method, parsedByPath, inheritanceGraph));
                    }
                }
                if (methodMatches.size() == 1) {
                    SymbolDef target = methodMatches.getFirst();
                    if (!target.modulePath().equals(pf.path)) {
                        edges.add(new ResolvedEdge(
                                EdgeKind.CALLS_SYMBOL, pf.language, pf.path, cs.callerSymbol(), cs.span(),
                                target.modulePath(), target.qualifiedName(), target.span(),
                                BASE_TRUST_TIER, PROVENANCE_POLYGLOT_IMPORT_SCOPED, "typed-receiver-method-call"));
                    }
                } else if (methodMatches.size() > 1) {
                    gaps.add(buildAmbiguousCallGap(pf, cs, List.copyOf(methodMatches),
                            "ambiguous-typed-receiver-call", context));
                }
                continue;
            }

            // 3f. Untyped / duck-typed receiver call: NEVER guess when >1 candidate methods exist in repo!
            if (isSimpleIdentifier(recv)) {
                TreeSet<SymbolDef> repoMethods = methodsByLangAndName.getOrDefault(
                        pf.langGroup + "|" + method, new TreeSet<>());
                if (repoMethods.size() > 1) {
                    gaps.add(buildAmbiguousCallGap(pf, cs, List.copyOf(repoMethods),
                            "duck-typed-multiple-candidates", context));
                }
            }
        }
    }

    private static List<SymbolDef> resolveTypeReference(
            ParsedFile pf,
            String typeExpr,
            FileImportScope importScope,
            Map<String, ParsedFile> parsedByPath,
            Map<String, List<ParsedFile>> goPackagesByDir,
            Map<String, Map<String, SymbolDef>> resolvedExportsByModule) {
        if (typeExpr == null || typeExpr.isBlank()) return List.of();
        String clean = stripGenericArgs(typeExpr.strip());
        // Qualified reference: pkg.Type or mod::Type
        int dot = clean.lastIndexOf('.');
        int colon = clean.lastIndexOf("::");
        if (dot > 0 || colon > 0) {
            boolean isColon = colon > dot;
            String qualifier = isColon ? clean.substring(0, colon) : clean.substring(0, dot);
            String symName = isColon ? clean.substring(colon + 2) : clean.substring(dot + 1);
            TreeSet<String> nsFiles = importScope.importedNamespaces.get(qualifier);
            if (nsFiles != null && !nsFiles.isEmpty()) {
                var found = new TreeSet<SymbolDef>();
                for (String tp : nsFiles) {
                    SymbolDef s = resolvedExportsByModule.getOrDefault(tp, Map.of()).get(symName);
                    if (s == null) {
                        ParsedFile tpf = parsedByPath.get(tp);
                        if (tpf != null) s = findDeclaredTopLevel(tpf, symName);
                    }
                    if (s != null) found.add(s);
                }
                return List.copyOf(found);
            }
            // Rust direct crate/super path in trait impl or call: crate::traits::Runner
            if (pf.language.equals("rust") && isColon) {
                var imported = importScope.importedSymbols.get(symName);
                if (imported != null && !imported.isEmpty()) {
                    return List.copyOf(imported);
                }
            }
            return List.of();
        }

        // Unqualified type name: check local file first, then imports, then Go same-package, then wildcard imports
        SymbolDef local = findDeclaredTopLevel(pf, clean);
        if (local != null) return List.of(local);

        var imported = importScope.importedSymbols.get(clean);
        if (imported != null && !imported.isEmpty()) return List.copyOf(imported);

        if (pf.language.equals("go")) {
            var sibs = findGoSamePackageSymbols(pf, clean, goPackagesByDir);
            if (!sibs.isEmpty()) return sibs;
        }

        if (!importScope.wildcardModules.isEmpty()) {
            var matches = new TreeSet<SymbolDef>();
            for (String wm : importScope.wildcardModules) {
                SymbolDef s = resolvedExportsByModule.getOrDefault(wm, Map.of()).get(clean);
                if (s != null) matches.add(s);
            }
            return List.copyOf(matches);
        }
        return List.of();
    }

    private static TreeSet<SymbolDef> lookupMethodOnTypeOrParents(
            SymbolDef typeSym,
            String methodName,
            Map<String, ParsedFile> parsedByPath,
            Map<String, TreeSet<SymbolDef>> inheritanceGraph) {
        var result = new TreeSet<SymbolDef>();
        ParsedFile typeFile = parsedByPath.get(typeSym.modulePath());
        if (typeFile != null) {
            SymbolDef direct = typeFile.methodsByType.getOrDefault(typeSym.qualifiedName(), Map.of()).get(methodName);
            if (direct == null) {
                direct = typeFile.methodsByType.getOrDefault(typeSym.name(), Map.of()).get(methodName);
            }
            if (direct != null) {
                result.add(direct);
                return result;
            }
        }
        return resolveInheritedMethods(typeSym.symbolId(), methodName, parsedByPath, inheritanceGraph);
    }

    private static TreeSet<SymbolDef> resolveInheritedMethods(
            String startTypeId,
            String methodName,
            Map<String, ParsedFile> parsedByPath,
            Map<String, TreeSet<SymbolDef>> inheritanceGraph) {
        var found = new TreeSet<SymbolDef>();
        var visitedTypes = new HashSet<String>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(startTypeId);
        visitedTypes.add(startTypeId);
        while (!queue.isEmpty()) {
            String curId = queue.poll();
            for (SymbolDef parent : inheritanceGraph.getOrDefault(curId, new TreeSet<>())) {
                if (!visitedTypes.add(parent.symbolId())) continue;
                ParsedFile parentFile = parsedByPath.get(parent.modulePath());
                if (parentFile != null) {
                    SymbolDef m = parentFile.methodsByType.getOrDefault(parent.qualifiedName(), Map.of()).get(methodName);
                    if (m == null) {
                        m = parentFile.methodsByType.getOrDefault(parent.name(), Map.of()).get(methodName);
                    }
                    if (m != null) {
                        found.add(m);
                        continue;
                    }
                }
                queue.add(parent.symbolId());
            }
        }
        return found;
    }

    private static List<SymbolDef> findGoSamePackageSymbols(
            ParsedFile pf, String symbolName, Map<String, List<ParsedFile>> goPackagesByDir) {
        var matches = new TreeSet<SymbolDef>();
        for (ParsedFile sib : goPackagesByDir.getOrDefault(parentDir(pf.path), List.of())) {
            if (!sib.goPackageName.equals(pf.goPackageName)) continue;
            SymbolDef s = findDeclaredTopLevel(sib, symbolName);
            if (s != null) matches.add(s);
        }
        return List.copyOf(matches);
    }

    private static SymbolDef findDeclaredTopLevel(ParsedFile pf, String name) {
        for (SymbolDef sym : pf.declarations) {
            if (sym.kind() != SymbolKind.METHOD && sym.name().equals(name)) {
                return sym;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Capability Gap Creation (I3 / I4)
    // -------------------------------------------------------------------------

    private static CapabilityGapRecord buildAmbiguousCallGap(
            ParsedFile pf,
            CallSite cs,
            List<SymbolDef> candidates,
            String detail,
            EvidenceContext context) {
        var payload = new TreeMap<String, Object>();
        payload.put("callerSymbol", cs.callerSymbol());
        payload.put("candidates", candidates.stream().map(SymbolDef::symbolId).sorted().toList());
        payload.put("detail", detail);
        payload.put("expression", cs.expression());
        payload.put("language", pf.language);
        payload.put("path", pf.path);
        payload.put("span", rangeToMap(cs.span()));

        ContentDigest payloadDigest = CanonicalJson.sha256(payload);
        ProviderObservationReference obsRef = ProviderObservationReference.create(
                PROVIDER, "polyglot.ambiguous-call", pf.contentDigest, payloadDigest);
        EvidenceSubject subject = EvidenceSubject.observation(obsRef.identity());
        EvidenceRequirement requirement = new EvidenceRequirement(
                EvidenceRequirement.Kind.ALTERNATE_FRONTEND,
                "polyglot.disambiguate-call-target",
                List.of(subject),
                EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,
                List.of("Establish compiler or SCIP type-resolved call target for ambiguous or dynamic polyglot call."));

        List<SourceSpan> spans = toSourceSpans(pf.path, cs.span());
        var limitations = new ArrayList<String>();
        limitations.add("call=" + cs.expression());
        limitations.add("detail=" + detail);
        limitations.add("file=" + pf.path);
        limitations.add("language=" + pf.language);
        if (!candidates.isEmpty()) {
            limitations.add("candidateCount=" + candidates.size());
            for (SymbolDef cand : candidates) {
                limitations.add("candidate=" + cand.symbolId());
            }
        }

        return CapabilityGapRecord.create(
                GAP_CATALOG,
                context,
                PROVIDER,
                "polyglot.scope-graph",
                AMBIGUOUS_CALL_REASON_CODE,
                subject,
                spans,
                List.of(obsRef),
                List.of(requirement),
                List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.RELATIONSHIP_CATEGORY, "polyglot.calls-symbol")),
                List.of(),
                List.of(),
                limitations);
    }

    private static List<SourceSpan> toSourceSpans(String repoPath, SourceCoordinates.Range range) {
        try {
            if (range == null || range.startLine() < 1 || range.startColumn() < 1
                    || range.endLine() < range.startLine()
                    || (range.endLine() == range.startLine() && range.endColumn() <= range.startColumn())) {
                return List.of();
            }
            SourceDocumentIdentity docId = SourceDocumentIdentity.from(DEFAULT_REPO, repoPath);
            return List.of(new SourceSpan(docId, range.startLine(), range.startColumn(),
                    range.endLine(), range.endColumn()));
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    private static EvidenceContext deriveDefaultContext(Map<String, byte[]> sortedFiles) {
        var entries = new ArrayList<List<String>>(sortedFiles.size());
        for (var e : sortedFiles.entrySet()) {
            entries.add(List.of(e.getKey(), ContentDigest.sha256(e.getValue()).value()));
        }
        ContentDigest snapshotDigest = CanonicalJson.sha256(entries);
        return EvidenceContext.forSnapshot(SnapshotIdentity.from(DEFAULT_REPO, snapshotDigest));
    }

    // -------------------------------------------------------------------------
    // Module Path Resolution per Language
    // -------------------------------------------------------------------------

    private static List<String> resolveImportTargetFiles(
            ParsedFile pf,
            ImportDirective imp,
            Map<String, ParsedFile> parsedByPath,
            ManifestIndex manifests,
            Set<String> allFilePaths,
            Map<String, List<ParsedFile>> goPackagesByDir,
            RustModuleIndex rustModules) {
        return switch (pf.language) {
            case "python" -> resolvePythonImport(pf, imp.rawSpecifier(), manifests, allFilePaths);
            case "typescript", "tsx", "javascript" -> resolveTsJsImport(pf, imp.rawSpecifier(), manifests, allFilePaths);
            case "go" -> resolveGoImport(pf, imp.rawSpecifier(), manifests, goPackagesByDir);
            case "rust" -> resolveRustImport(pf, imp.rawSpecifier(), rustModules);
            default -> List.of();
        };
    }

    private static String classifyImportResolutionKind(
            ParsedFile pf, ImportDirective imp, ManifestIndex manifests) {
        String spec = imp.rawSpecifier();
        return switch (pf.language) {
            case "python" -> spec.startsWith(".") ? "python-relative-import" : "python-package-import";
            case "typescript", "tsx", "javascript" -> (spec.startsWith("./") || spec.startsWith("../"))
                    ? "ts-relative-import"
                    : "ts-alias-or-workspace-import";
            case "go" -> "go-mod-package-import";
            case "rust" -> imp.reExport() ? "rust-pub-use" : "rust-use-import";
            default -> "import";
        };
    }

    // --- Python import resolution ---

    private static List<String> resolvePythonImport(
            ParsedFile pf, String rawSpec, ManifestIndex manifests, Set<String> allFilePaths) {
        if (rawSpec == null || rawSpec.isBlank()) return List.of();
        String spec = rawSpec.strip();

        if (spec.startsWith(".")) {
            int dots = 0;
            while (dots < spec.length() && spec.charAt(dots) == '.') dots++;
            String remainder = spec.substring(dots);
            String baseDir = parentDir(pf.path);
            for (int i = 1; i < dots; i++) {
                baseDir = parentDir(baseDir);
            }
            String relPath = remainder.isEmpty() ? baseDir : joinPaths(baseDir, remainder.replace('.', '/'));
            if (relPath == null) return List.of();
            return probePythonModule(relPath, allFilePaths);
        }

        String modRel = spec.replace('.', '/');
        var roots = new LinkedHashSet<String>();
        // Most specific pyproject.toml roots containing this file first
        var matchingProjects = new ArrayList<>(manifests.pythonProjects());
        matchingProjects.sort(Comparator.comparingInt((PythonProjectManifest p) -> p.rootDir().length()).reversed()
                .thenComparing(PythonProjectManifest::manifestPath));
        for (PythonProjectManifest proj : matchingProjects) {
            if (proj.rootDir().isEmpty() || pf.path.startsWith(proj.rootDir() + "/")) {
                roots.addAll(proj.sourceRoots());
                roots.add(proj.rootDir());
            }
        }
        for (PythonProjectManifest proj : matchingProjects) {
            roots.addAll(proj.sourceRoots());
            roots.add(proj.rootDir());
        }
        roots.add("");
        for (String root : roots) {
            String candidate = joinPaths(root, modRel);
            if (candidate == null) continue;
            List<String> hits = probePythonModule(candidate, allFilePaths);
            if (!hits.isEmpty()) return hits;
        }
        return List.of();
    }

    private static List<String> probePythonModule(String basePath, Set<String> allFilePaths) {
        if (basePath.isEmpty()) {
            return allFilePaths.contains("__init__.py") ? List.of("__init__.py") : List.of();
        }
        String pyFile = basePath + ".py";
        if (allFilePaths.contains(pyFile)) return List.of(pyFile);
        String pkgInit = basePath + "/__init__.py";
        if (allFilePaths.contains(pkgInit)) return List.of(pkgInit);
        return List.of();
    }

    private static Optional<String> resolveSubmoduleImport(
            ParsedFile pf,
            String rawSpec,
            String bindingName,
            ManifestIndex manifests,
            Set<String> allFilePaths,
            RustModuleIndex rustModules) {
        if (pf.language.equals("python")) {
            String combined = rawSpec.endsWith(".") ? rawSpec + bindingName : rawSpec + "." + bindingName;
            List<String> hits = resolvePythonImport(pf, combined, manifests, allFilePaths);
            return hits.isEmpty() ? Optional.empty() : Optional.of(hits.getFirst());
        }
        if (pf.language.equals("rust")) {
            String combined = rawSpec.isEmpty() ? bindingName : rawSpec + "::" + bindingName;
            List<String> hits = resolveRustImport(pf, combined, rustModules);
            return hits.isEmpty() ? Optional.empty() : Optional.of(hits.getFirst());
        }
        return Optional.empty();
    }

    // --- TypeScript / JavaScript import resolution ---

    private static List<String> resolveTsJsImport(
            ParsedFile pf, String rawSpec, ManifestIndex manifests, Set<String> allFilePaths) {
        if (rawSpec == null || rawSpec.isBlank()) return List.of();
        String spec = rawSpec.strip();

        // 1. Relative imports ./ or ../
        if (spec.startsWith("./") || spec.startsWith("../") || spec.equals(".") || spec.equals("..")) {
            String joined = joinPaths(parentDir(pf.path), spec);
            if (joined == null) return List.of();
            return probeTsJsFile(joined, allFilePaths).map(List::of).orElse(List.of());
        }

        // 2. package.json #imports
        if (spec.startsWith("#")) {
            for (var entry : manifests.jsPackageImports().entrySet()) {
                String resolved = matchPatternTarget(spec, entry.getKey(), entry.getValue());
                if (resolved != null) {
                    var hit = probeTsJsFile(resolved, allFilePaths);
                    if (hit.isPresent()) return List.of(hit.orElseThrow());
                }
            }
        }

        // 3. tsconfig.json / jsconfig.json paths & baseUrl (most specific config directory first)
        var sortedAliases = new ArrayList<>(manifests.tsAliases());
        sortedAliases.sort(Comparator.comparingInt((TsAliasRule r) -> r.baseDir().length()).reversed()
                .thenComparing(Comparator.comparingInt((TsAliasRule r) -> r.pattern().length()).reversed())
                .thenComparing(TsAliasRule::pattern));
        for (TsAliasRule rule : sortedAliases) {
            if (!rule.baseDir().isEmpty()
                    && !pf.path.startsWith(rule.baseDir() + "/")
                    && !pf.path.equals(rule.baseDir())) {
                continue;
            }
            for (String target : rule.targets()) {
                String resolved = matchPatternTarget(spec, rule.pattern(), target);
                if (resolved != null) {
                    var hit = probeTsJsFile(resolved, allFilePaths);
                    if (hit.isPresent()) return List.of(hit.orElseThrow());
                }
            }
        }

        // 4. package.json exports (exact then wildcard)
        String exactExp = manifests.jsPackageExports().get(spec);
        if (exactExp != null) {
            var hit = probeTsJsFile(exactExp, allFilePaths);
            if (hit.isPresent()) return List.of(hit.orElseThrow());
        }
        var wildExports = new ArrayList<Map.Entry<String, String>>();
        for (var e : manifests.jsPackageExports().entrySet()) {
            if (e.getKey().contains("*")) wildExports.add(e);
        }
        wildExports.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed()
                .thenComparing(Map.Entry::getKey));
        for (var e : wildExports) {
            String resolved = matchPatternTarget(spec, e.getKey(), e.getValue());
            if (resolved != null) {
                var hit = probeTsJsFile(resolved, allFilePaths);
                if (hit.isPresent()) return List.of(hit.orElseThrow());
            }
        }

        // 5. Workspace packages
        JsWorkspacePackage pkg = manifests.jsWorkspacePackages().get(spec);
        if (pkg != null) {
            for (String cand : List.of(pkg.mainEntry(), joinPaths(pkg.dir(), "src/index"), joinPaths(pkg.dir(), "index"))) {
                if (cand == null || cand.isEmpty()) continue;
                var hit = probeTsJsFile(cand, allFilePaths);
                if (hit.isPresent()) return List.of(hit.orElseThrow());
            }
        }
        JsWorkspacePackage bestPrefix = null;
        for (var e : manifests.jsWorkspacePackages().entrySet()) {
            if (spec.startsWith(e.getKey() + "/")
                    && (bestPrefix == null || e.getKey().length() > bestPrefix.name().length())) {
                bestPrefix = e.getValue();
            }
        }
        if (bestPrefix != null) {
            String sub = spec.substring(bestPrefix.name().length() + 1);
            for (String base : List.of(bestPrefix.dir(), joinPaths(bestPrefix.dir(), "src"))) {
                if (base == null) continue;
                String cand = joinPaths(base, sub);
                if (cand != null) {
                    var hit = probeTsJsFile(cand, allFilePaths);
                    if (hit.isPresent()) return List.of(hit.orElseThrow());
                }
            }
        }
        return List.of();
    }

    private static Optional<String> probeTsJsFile(String basePath, Set<String> allFilePaths) {
        if (basePath == null || basePath.isEmpty()) return Optional.empty();
        if (allFilePaths.contains(basePath)) return Optional.of(basePath);
        // Handle ESM TypeScript imports written with .js/.mjs extension pointing to .ts/.tsx
        String stem = stripJsTsExt(basePath);
        for (String ext : List.of(".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs",
                "/index.ts", "/index.tsx", "/index.mts", "/index.js", "/index.jsx")) {
            String cand = stem + ext;
            if (allFilePaths.contains(cand)) return Optional.of(cand);
        }
        if (!stem.equals(basePath)) {
            for (String ext : List.of("/index.ts", "/index.tsx", "/index.js")) {
                String cand = basePath + ext;
                if (allFilePaths.contains(cand)) return Optional.of(cand);
            }
        }
        return Optional.empty();
    }

    private static String stripJsTsExt(String path) {
        for (String ext : List.of(".tsx", ".ts", ".mts", ".cts", ".jsx", ".js", ".mjs", ".cjs")) {
            if (path.endsWith(ext) && path.length() > ext.length()) {
                return path.substring(0, path.length() - ext.length());
            }
        }
        return path;
    }

    // --- Go import resolution ---

    private static Map<String, List<ParsedFile>> indexGoPackages(Map<String, ParsedFile> parsedByPath) {
        var map = new TreeMap<String, List<ParsedFile>>();
        for (ParsedFile pf : parsedByPath.values()) {
            if (pf.language.equals("go")) {
                map.computeIfAbsent(parentDir(pf.path), k -> new ArrayList<>()).add(pf);
            }
        }
        return map;
    }

    private static List<String> resolveGoImport(
            ParsedFile pf,
            String importPath,
            ManifestIndex manifests,
            Map<String, List<ParsedFile>> goPackagesByDir) {
        if (importPath == null || importPath.isBlank()) return List.of();
        String spec = importPath.strip();

        // Check local replace directives first
        for (GoModuleManifest gm : manifests.goModules()) {
            for (var rep : gm.localReplaces().entrySet()) {
                if (spec.equals(rep.getKey()) || spec.startsWith(rep.getKey() + "/")) {
                    String rem = spec.equals(rep.getKey()) ? "" : spec.substring(rep.getKey().length() + 1);
                    String targetRoot = joinPaths(gm.rootDir(), rep.getValue());
                    if (targetRoot != null) {
                        String dir = rem.isEmpty() ? targetRoot : joinPaths(targetRoot, rem);
                        if (dir != null) {
                            List<String> files = selectGoPackageFiles(dir, goPackagesByDir);
                            if (!files.isEmpty()) return files;
                        }
                    }
                }
            }
        }

        // Match longest go.mod module path
        var sortedMods = new ArrayList<>(manifests.goModules());
        sortedMods.sort(Comparator.comparingInt((GoModuleManifest m) -> m.modulePath().length()).reversed()
                .thenComparing(GoModuleManifest::manifestPath));
        for (GoModuleManifest gm : sortedMods) {
            if (spec.equals(gm.modulePath()) || spec.startsWith(gm.modulePath() + "/")) {
                String rem = spec.equals(gm.modulePath()) ? "" : spec.substring(gm.modulePath().length() + 1);
                String dir = rem.isEmpty() ? gm.rootDir() : joinPaths(gm.rootDir(), rem);
                if (dir != null) {
                    List<String> files = selectGoPackageFiles(dir, goPackagesByDir);
                    if (!files.isEmpty()) return files;
                }
            }
        }

        // Fallback: direct repo-relative package path
        return selectGoPackageFiles(spec, goPackagesByDir);
    }

    private static List<String> selectGoPackageFiles(String dir, Map<String, List<ParsedFile>> goPackagesByDir) {
        List<ParsedFile> all = goPackagesByDir.get(normalizePath(dir));
        if (all == null || all.isEmpty()) return List.of();
        var nonTests = all.stream().filter(f -> !f.path.endsWith("_test.go")).map(f -> f.path).sorted().toList();
        return nonTests.isEmpty() ? all.stream().map(f -> f.path).sorted().toList() : nonTests;
    }

    // --- Rust module tree & import resolution ---

    private record RustModuleIndex(
            Map<String, String> modPathToFile,
            Map<String, String> fileToModPath,
            Map<String, String> fileToCrateName,
            Map<String, String> crateNameToRootFile) {
    }

    private static RustModuleIndex buildRustModuleIndex(
            Map<String, ParsedFile> parsedByPath, ManifestIndex manifests) {
        var modPathToFile = new TreeMap<String, String>();
        var fileToModPath = new TreeMap<String, String>();
        var fileToCrateName = new TreeMap<String, String>();
        var crateNameToRootFile = new TreeMap<String, String>();

        var rsPaths = new TreeSet<String>();
        for (ParsedFile pf : parsedByPath.values()) {
            if (pf.language.equals("rust")) rsPaths.add(pf.path);
        }
        if (rsPaths.isEmpty()) {
            return new RustModuleIndex(modPathToFile, fileToModPath, fileToCrateName, crateNameToRootFile);
        }

        // Determine crate roots from Cargo.toml manifests (or fallback repo root src/lib.rs, src/main.rs)
        var crateRoots = new ArrayList<Map.Entry<String, String>>();
        for (CargoCrateManifest crate : manifests.cargoCrates()) {
            if (crate.crateName().isEmpty()) continue;
            String rootFile = findRustCrateRootFile(crate, rsPaths);
            if (rootFile != null) {
                crateRoots.add(Map.entry(crate.crateName(), rootFile));
                crateNameToRootFile.putIfAbsent(crate.crateName(), rootFile);
            }
        }
        if (crateRoots.isEmpty()) {
            for (String cand : List.of("src/lib.rs", "src/main.rs", "lib.rs", "main.rs")) {
                if (rsPaths.contains(cand)) {
                    crateRoots.add(Map.entry("crate", cand));
                    crateNameToRootFile.putIfAbsent("crate", cand);
                    break;
                }
            }
        }

        // BFS/DFS from each crate root following `mod foo;` declarations
        for (var rootEntry : crateRoots) {
            String crateName = rootEntry.getKey();
            String rootFile = rootEntry.getValue();
            indexRustModuleDfs(crateName, crateName, rootFile, true, parsedByPath, rsPaths,
                    modPathToFile, fileToModPath, fileToCrateName, new HashSet<>());
        }

        // Also index any remaining .rs files relative to their crate root (e.g. main.rs alongside lib.rs)
        for (String rsFile : rsPaths) {
            if (fileToModPath.containsKey(rsFile)) continue;
            String crateName = findEnclosingRustCrate(rsFile, manifests);
            String base = baseName(rsFile);
            if (base.equals("main.rs") || base.equals("lib.rs")) {
                fileToModPath.put(rsFile, crateName);
                fileToCrateName.put(rsFile, crateName);
                modPathToFile.putIfAbsent(crateName, rsFile);
                indexRustModuleDfs(crateName, crateName, rsFile, true, parsedByPath, rsPaths,
                        modPathToFile, fileToModPath, fileToCrateName, new HashSet<>());
            } else {
                String stem = rsFile.substring(0, rsFile.length() - 3);
                String modPath = crateName + "::" + stem.replace('/', ':').replace(":", "::");
                fileToModPath.put(rsFile, modPath);
                fileToCrateName.put(rsFile, crateName);
                modPathToFile.putIfAbsent(modPath, rsFile);
            }
        }

        return new RustModuleIndex(modPathToFile, fileToModPath, fileToCrateName, crateNameToRootFile);
    }

    private static void indexRustModuleDfs(
            String crateName,
            String modPath,
            String filePath,
            boolean isDirMod,
            Map<String, ParsedFile> parsedByPath,
            Set<String> rsPaths,
            Map<String, String> modPathToFile,
            Map<String, String> fileToModPath,
            Map<String, String> fileToCrateName,
            Set<String> visited) {
        if (!visited.add(filePath)) return;
        modPathToFile.putIfAbsent(modPath, filePath);
        fileToModPath.putIfAbsent(filePath, modPath);
        fileToCrateName.putIfAbsent(filePath, crateName);

        ParsedFile pf = parsedByPath.get(filePath);
        if (pf == null) return;
        String baseDir;
        if (isDirMod) {
            baseDir = parentDir(filePath);
        } else {
            // Submodules of `foo.rs` live in `foo/`
            baseDir = filePath.endsWith(".rs") ? filePath.substring(0, filePath.length() - 3) : parentDir(filePath);
        }
        var sortedMods = new TreeSet<>(pf.rustExternalMods);
        for (String subMod : sortedMods) {
            String direct = joinPaths(baseDir, subMod + ".rs");
            String nested = joinPaths(baseDir, subMod + "/mod.rs");
            String nextModPath = modPath + "::" + subMod;
            if (direct != null && rsPaths.contains(direct)) {
                indexRustModuleDfs(crateName, nextModPath, direct, false, parsedByPath, rsPaths,
                        modPathToFile, fileToModPath, fileToCrateName, visited);
            } else if (nested != null && rsPaths.contains(nested)) {
                indexRustModuleDfs(crateName, nextModPath, nested, true, parsedByPath, rsPaths,
                        modPathToFile, fileToModPath, fileToCrateName, visited);
            }
        }
    }

    private static String findRustCrateRootFile(CargoCrateManifest crate, Set<String> rsPaths) {
        if (!crate.libPath().isEmpty()) {
            String explicit = joinPaths(crate.rootDir(), crate.libPath());
            if (explicit != null && rsPaths.contains(explicit)) return explicit;
        }
        for (String rel : List.of("src/lib.rs", "src/main.rs", "lib.rs", "main.rs")) {
            String cand = joinPaths(crate.rootDir(), rel);
            if (cand != null && rsPaths.contains(cand)) return cand;
        }
        return null;
    }

    private static String findEnclosingRustCrate(String rsFile, ManifestIndex manifests) {
        CargoCrateManifest best = null;
        for (CargoCrateManifest c : manifests.cargoCrates()) {
            if (c.crateName().isEmpty()) continue;
            if (c.rootDir().isEmpty() || rsFile.startsWith(c.rootDir() + "/")) {
                if (best == null || c.rootDir().length() > best.rootDir().length()) {
                    best = c;
                }
            }
        }
        return best != null ? best.crateName() : "crate";
    }

    private static List<String> resolveRustImport(
            ParsedFile pf, String rawSpec, RustModuleIndex rustModules) {
        if (rawSpec == null || rawSpec.isBlank()) return List.of();
        String spec = rawSpec.strip();
        if (spec.startsWith("::")) spec = spec.substring(2);
        String currentMod = rustModules.fileToModPath().getOrDefault(pf.path, "crate");
        String currentCrate = rustModules.fileToCrateName().getOrDefault(pf.path, "crate");

        String canonical;
        if (spec.equals("crate")) {
            canonical = currentCrate;
        } else if (spec.startsWith("crate::")) {
            canonical = currentCrate + "::" + spec.substring("crate::".length());
        } else if (spec.equals("self")) {
            canonical = currentMod;
        } else if (spec.startsWith("self::")) {
            canonical = currentMod + "::" + spec.substring("self::".length());
        } else if (spec.equals("super") || spec.startsWith("super::")) {
            String base = currentMod;
            String rem = spec;
            while (rem.equals("super") || rem.startsWith("super::")) {
                int last = base.lastIndexOf("::");
                if (last > 0) base = base.substring(0, last);
                rem = rem.equals("super") ? "" : rem.substring("super::".length());
            }
            canonical = rem.isEmpty() ? base : base + "::" + rem;
        } else {
            // Either external workspace crate name (my_lib::sub) or relative module inside current crate
            String first = firstSegment(spec, ':');
            if (rustModules.crateNameToRootFile().containsKey(first)) {
                canonical = spec;
            } else {
                canonical = currentMod + "::" + spec;
            }
        }

        String exact = rustModules.modPathToFile().get(canonical);
        if (exact != null) return List.of(exact);
        // If `use crate::sub::MyStruct` pointed to a module containing re-exports, try parent module too
        int lastSep = canonical.lastIndexOf("::");
        if (lastSep > 0) {
            String parentFile = rustModules.modPathToFile().get(canonical.substring(0, lastSep));
            if (parentFile != null) return List.of(parentFile);
        }
        return List.of();
    }

    // -------------------------------------------------------------------------
    // Passive Manifest Parsers (I1: 100% static, zero host execution)
    // -------------------------------------------------------------------------

    private static boolean isManifestFile(String normPath) {
        String lower = baseName(normPath).toLowerCase(Locale.ROOT);
        return lower.equals("go.mod")
                || lower.equals("package.json")
                || lower.equals("pnpm-workspace.yaml")
                || lower.equals("pnpm-workspace.yml")
                || lower.equals("pyproject.toml")
                || lower.equals("cargo.toml")
                || ((lower.contains("tsconfig") || lower.contains("jsconfig")) && lower.endsWith(".json"));
    }

    private static Optional<GoModuleManifest> parseGoMod(String path, String dir, String content) {
        String modulePath = "";
        var replaces = new TreeMap<String, String>();
        boolean inReplaceBlock = false;
        for (String line : content.split("\\R", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) continue;
            if (trimmed.startsWith("replace") && trimmed.contains("(")) {
                inReplaceBlock = true;
                continue;
            }
            if (inReplaceBlock && trimmed.startsWith(")")) {
                inReplaceBlock = false;
                continue;
            }
            if (modulePath.isEmpty()) {
                Matcher mm = GO_MODULE_LINE.matcher(line);
                if (mm.matches()) {
                    modulePath = mm.group(1) != null ? mm.group(1).strip() : mm.group(2).strip();
                    continue;
                }
            }
            if (inReplaceBlock || trimmed.startsWith("replace ")) {
                Matcher rm = GO_REPLACE_LINE.matcher(line);
                if (rm.matches()) {
                    replaces.put(rm.group(1).strip(), rm.group(2).strip());
                }
            }
        }
        return modulePath.isEmpty() ? Optional.empty() : Optional.of(new GoModuleManifest(path, dir, modulePath, replaces));
    }

    private static PythonProjectManifest parsePyprojectToml(
            String path, String dir, String content, Set<String> knownPaths) {
        String section = "";
        String projectName = "";
        var sourceRoots = new LinkedHashSet<String>();
        boolean readingWhereArray = false;
        var whereBuf = new StringBuilder();

        for (String line : content.split("\\R", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            Matcher sm = TOML_SECTION.matcher(line);
            if (sm.matches()) {
                section = sm.group(1).strip();
                readingWhereArray = false;
                continue;
            }
            if (readingWhereArray) {
                whereBuf.append(' ').append(trimmed);
                if (trimmed.contains("]")) {
                    extractTomlStrings(whereBuf.toString()).forEach(w -> addPythonRoot(dir, w, sourceRoots));
                    readingWhereArray = false;
                    whereBuf.setLength(0);
                }
                continue;
            }
            if (section.equals("project") || section.equals("tool.poetry")) {
                Matcher kv = TOML_KV_STRING.matcher(line);
                if (kv.matches() && kv.group(1).equals("name") && projectName.isEmpty()) {
                    projectName = kv.group(2).strip();
                }
            }
            if (trimmed.startsWith("where") && trimmed.contains("=")) {
                if (trimmed.contains("]")) {
                    extractTomlStrings(trimmed.substring(trimmed.indexOf('=') + 1))
                            .forEach(w -> addPythonRoot(dir, w, sourceRoots));
                } else {
                    readingWhereArray = true;
                    whereBuf.append(trimmed.substring(trimmed.indexOf('=') + 1));
                }
            } else if (section.equals("tool.setuptools.package-dir") || trimmed.startsWith("package-dir")) {
                Matcher fromSrc = Pattern.compile("[\"']\\s*[\"']\\s*=\\s*[\"']([^\"']+)[\"']").matcher(trimmed);
                if (fromSrc.find()) {
                    addPythonRoot(dir, fromSrc.group(1), sourceRoots);
                }
            } else if (trimmed.contains("from") && section.startsWith("tool.poetry")) {
                Matcher fromM = Pattern.compile("\\bfrom\\s*=\\s*[\"']([^\"']+)[\"']").matcher(trimmed);
                while (fromM.find()) {
                    addPythonRoot(dir, fromM.group(1), sourceRoots);
                }
            }
        }
        // Standard src-layout auto-detection when <dir>/src exists in captured paths
        String srcCandidate = joinPaths(dir, "src");
        if (srcCandidate != null && knownPaths != null) {
            String prefix = srcCandidate + "/";
            for (String p : knownPaths) {
                if (normalizePath(p).startsWith(prefix)) {
                    sourceRoots.add(srcCandidate);
                    break;
                }
            }
        }
        sourceRoots.add(dir);
        return new PythonProjectManifest(path, dir, projectName, List.copyOf(sourceRoots));
    }

    private static void addPythonRoot(String manifestDir, String relRoot, Set<String> out) {
        String joined = joinPaths(manifestDir, relRoot);
        if (joined != null) out.add(joined);
    }

    private static CargoCrateManifest parseCargoToml(String path, String dir, String content) {
        String section = "";
        String packageName = "";
        String libPath = "";
        var workspaceMembers = new LinkedHashSet<String>();
        var localPathDeps = new TreeMap<String, String>();
        boolean readingMembers = false;
        var membersBuf = new StringBuilder();

        for (String line : content.split("\\R", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            Matcher sm = TOML_SECTION.matcher(line);
            if (sm.matches()) {
                section = sm.group(1).strip();
                readingMembers = false;
                continue;
            }
            if (readingMembers) {
                membersBuf.append(' ').append(trimmed);
                if (trimmed.contains("]")) {
                    workspaceMembers.addAll(extractTomlStrings(membersBuf.toString()));
                    readingMembers = false;
                    membersBuf.setLength(0);
                }
                continue;
            }
            if (section.equals("package")) {
                Matcher kv = TOML_KV_STRING.matcher(line);
                if (kv.matches() && kv.group(1).equals("name") && packageName.isEmpty()) {
                    packageName = kv.group(2).strip();
                }
            } else if (section.equals("lib")) {
                Matcher kv = TOML_KV_STRING.matcher(line);
                if (kv.matches() && kv.group(1).equals("path") && libPath.isEmpty()) {
                    libPath = kv.group(2).strip();
                }
            } else if (section.equals("workspace") && trimmed.startsWith("members") && trimmed.contains("=")) {
                String rhs = trimmed.substring(trimmed.indexOf('=') + 1);
                if (rhs.contains("]")) {
                    workspaceMembers.addAll(extractTomlStrings(rhs));
                } else {
                    readingMembers = true;
                    membersBuf.append(rhs);
                }
            } else if (section.equals("dependencies") || section.equals("workspace.dependencies")) {
                Matcher dep = TOML_PATH_DEP.matcher(line);
                if (dep.find()) {
                    localPathDeps.put(dep.group(1).strip().replace('-', '_'), dep.group(2).strip());
                }
            }
        }
        String crateName = packageName.replace('-', '_');
        return new CargoCrateManifest(path, dir, packageName, crateName, libPath,
                List.copyOf(workspaceMembers), localPathDeps);
    }

    private static List<String> extractTomlStrings(String raw) {
        var out = new ArrayList<String>();
        Matcher m = STRING_TOKEN.matcher(raw);
        while (m.find()) {
            String v = m.group(1).strip();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    // --- TS/JS manifest helpers (tsconfig JSONC + package.json + pnpm-workspace) ---

    static String stripJsonc(String raw) {
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
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
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
                if (j < s.length() && (s.charAt(j) == '}' || s.charAt(j) == ']')) continue;
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
            List<TsAliasRule> out) {
        var mergedPaths = new LinkedHashMap<String, List<String>>();
        String[] inheritedBaseUrl = new String[1];
        collectTsConfigChain(configPath, parsedConfigs, new HashSet<>(), 0, mergedPaths, inheritedBaseUrl);

        var sortedKeys = new ArrayList<>(mergedPaths.keySet());
        Collections.sort(sortedKeys);
        for (String key : sortedKeys) {
            List<String> targets = mergedPaths.get(key);
            if (targets != null && !targets.isEmpty()) {
                out.add(new TsAliasRule(configPath, configDir, key, targets, "tsconfig.paths"));
            }
        }
        if (inheritedBaseUrl[0] != null && !inheritedBaseUrl[0].isEmpty()
                && !inheritedBaseUrl[0].equals(normalizePath(configDir))) {
            String baseWildcard = joinPaths(inheritedBaseUrl[0], "*");
            if (baseWildcard != null && !baseWildcard.isEmpty()) {
                out.add(new TsAliasRule(configPath, configDir, "*", List.of(baseWildcard), "tsconfig.baseUrl"));
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
            String parent = resolveExtendsPath(dir, extRef, parsedConfigs);
            if (parent != null) {
                collectTsConfigChain(parent, parsedConfigs, visited, depth + 1, mergedPaths, effectiveBaseHolder);
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
                        if (joined != null && !joined.isEmpty()) targets.add(joined);
                    }
                }
                if (!targets.isEmpty()) {
                    mergedPaths.put(key, List.copyOf(targets));
                }
            }
        }
    }

    private static String resolveExtendsPath(
            String fromDir, String rawExtends, Map<String, Map<String, Object>> parsedConfigs) {
        if (rawExtends == null || rawExtends.isBlank()) return null;
        if (!rawExtends.startsWith(".") && !rawExtends.startsWith("/")) {
            return parsedConfigs.containsKey(rawExtends) ? rawExtends : null;
        }
        String joined = joinPaths(fromDir, rawExtends);
        if (joined == null || joined.isEmpty()) return null;
        if (parsedConfigs.containsKey(joined)) return joined;
        if (!joined.endsWith(".json") && parsedConfigs.containsKey(joined + ".json")) return joined + ".json";
        if (parsedConfigs.containsKey(joined + "/tsconfig.json")) return joined + "/tsconfig.json";
        return null;
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
                    if (!glob.isEmpty()) outGlobs.add(glob);
                }
            }
        }
    }

    private static void parsePackageJson(
            String dir,
            String content,
            Set<String> workspaces,
            Map<String, String> exportsMap,
            Map<String, String> importsMap,
            List<JsWorkspacePackage> discoveredPackages) {
        try {
            var root = GraphJson.object(GraphJson.read(content, CONFIG_JSON_LIMITS));
            String pkgName = root.get("name") instanceof String n ? n.strip() : "";
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
                discoveredPackages.add(new JsWorkspacePackage(pkgName, dir, mainEntry));

                Object expObj = root.get("exports");
                if (expObj instanceof String s && !s.isBlank()) {
                    String joined = joinPaths(dir, s);
                    if (joined != null && !joined.isEmpty()) exportsMap.put(pkgName, joined);
                } else if (expObj instanceof Map<?, ?> map) {
                    boolean hasSubpath = false;
                    for (Object k : map.keySet()) {
                        if (k instanceof String sk && (sk.equals(".") || sk.startsWith("./"))) {
                            hasSubpath = true;
                            break;
                        }
                    }
                    if (!hasSubpath) {
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
        } catch (RuntimeException ignored) {
            // Malformed package.json stays non-fatal in passive mode.
        }
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

    // -------------------------------------------------------------------------
    // Utility Helpers
    // -------------------------------------------------------------------------

    public static String detectLanguage(String path) {
        if (path == null || path.isEmpty()) return null;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".tsx")) return "tsx";
        if (lower.endsWith(".ts") || lower.endsWith(".mts") || lower.endsWith(".cts")) return "typescript";
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) {
            return "javascript";
        }
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".rs")) return "rust";
        return null;
    }

    private static String languageGroup(String language) {
        return Set.of("typescript", "tsx", "javascript").contains(language) ? "js-ts" : language;
    }

    private static String symbolId(String modulePath, String qualifiedName) {
        return modulePath + "::" + qualifiedName;
    }

    private static SourceCoordinates.Range moduleSpan(ParsedFile pf) {
        if (pf != null && !pf.declarations.isEmpty()) {
            return pf.declarations.getFirst().span();
        }
        return new SourceCoordinates.Range(0, 1, 1, 1, 1, 2);
    }

    private static SourceCoordinates.Range spanOf(TSNode node, SourceCoordinates pos) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (end <= start) {
            return new SourceCoordinates.Range(0, 1, 1, 1, 1, 2);
        }
        return pos.range(start, end, SourceCoordinates.Encoding.UTF8);
    }

    private static Map<String, Object> rangeToMap(SourceCoordinates.Range span) {
        var map = new TreeMap<String, Object>();
        map.put("end", BigDecimal.valueOf(span.end()));
        map.put("endColumn", BigDecimal.valueOf(span.endColumn()));
        map.put("endLine", BigDecimal.valueOf(span.endLine()));
        map.put("start", BigDecimal.valueOf(span.start()));
        map.put("startColumn", BigDecimal.valueOf(span.startColumn()));
        map.put("startLine", BigDecimal.valueOf(span.startLine()));
        return map;
    }

    private static TSNode field(TSNode node, String name) {
        if (node == null || node.isNull()) return null;
        TSNode child = node.getChildByFieldName(name);
        return child == null || child.isNull() ? null : child;
    }

    private static boolean hasChildOfType(TSNode node, String type) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            if (node.getNamedChild(i).getType().equals(type)) return true;
        }
        return false;
    }

    private static boolean hasUnnamedChild(TSNode node, String token) {
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            if (!c.isNamed() && token.equals(c.getType())) return true;
        }
        return false;
    }

    private static String text(TSNode node, byte[] utf8) {
        if (node == null || node.isNull()) return "";
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start < 0 || end <= start || end > utf8.length) return "";
        return new String(utf8, start, end - start, StandardCharsets.UTF_8);
    }

    private static String unquote(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (s.length() >= 2) {
            char q = s.charAt(0);
            if ((q == '"' || q == '\'' || q == '`') && s.charAt(s.length() - 1) == q) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static String stripGenericArgs(String expr) {
        if (expr == null) return "";
        int lt = expr.indexOf('<');
        int br = expr.indexOf('[');
        int cut = -1;
        if (lt > 0) cut = lt;
        if (br > 0 && (cut < 0 || br < cut)) cut = br;
        return (cut > 0 ? expr.substring(0, cut) : expr).strip();
    }

    private static String stripRustTurbofish(String expr) {
        if (expr == null) return "";
        return expr.replaceAll("::<[^>]+>", "");
    }

    private static String unwrapPythonType(String raw) {
        String s = raw.strip();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1).strip();
        }
        if (s.startsWith("Optional[") && s.endsWith("]")) {
            s = s.substring("Optional[".length(), s.length() - 1).strip();
        }
        if (s.contains("|")) {
            // Only resolve non-None union if single concrete type remains
            var parts = new ArrayList<String>();
            for (String p : s.split("\\|")) {
                String t = p.strip();
                if (!t.isEmpty() && !t.equals("None")) parts.add(t);
            }
            if (parts.size() == 1) s = parts.getFirst();
            else return "";
        }
        return stripGenericArgs(s);
    }

    private static String unwrapTsTypeAnnotation(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (s.startsWith(":")) s = s.substring(1).strip();
        if (s.contains("|") || s.contains("&")) {
            var parts = new ArrayList<String>();
            for (String p : s.split("[|&]")) {
                String t = p.strip();
                if (!t.isEmpty() && !t.equals("null") && !t.equals("undefined")) parts.add(t);
            }
            if (parts.size() == 1) s = parts.getFirst();
            else return "";
        }
        return stripGenericArgs(s);
    }

    private static boolean isSimpleIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if (!((c0 >= 'A' && c0 <= 'Z') || (c0 >= 'a' && c0 <= 'z') || c0 == '_' || c0 == '$')) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '$')) {
                return false;
            }
        }
        return true;
    }

    private static String firstSegment(String s, char sep) {
        if (s == null) return "";
        int idx = s.indexOf(sep);
        return idx > 0 ? s.substring(0, idx) : s;
    }

    private static String lastSegment(String s, char sep) {
        if (s == null) return "";
        int idx = s.lastIndexOf(sep);
        return idx >= 0 ? s.substring(idx + 1) : s;
    }

    private static String tailIdent(String expr) {
        if (expr == null || expr.isEmpty()) return "";
        int sep = Math.max(expr.lastIndexOf('.'), expr.lastIndexOf(':'));
        return sep >= 0 ? expr.substring(sep + 1).strip() : expr.strip();
    }

    private static String baseName(String normPath) {
        int slash = normPath.lastIndexOf('/');
        return slash >= 0 ? normPath.substring(slash + 1) : normPath;
    }

    private static String parentDir(String normPath) {
        if (normPath == null || normPath.isEmpty()) return "";
        int slash = normPath.lastIndexOf('/');
        return slash > 0 ? normPath.substring(0, slash) : "";
    }

    static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "";
        String norm = path.strip().replace('\\', '/');
        while (norm.startsWith("./")) norm = norm.substring(2);
        while (norm.startsWith("/")) norm = norm.substring(1);
        while (norm.endsWith("/") && norm.length() > 1) norm = norm.substring(0, norm.length() - 1);
        if (norm.equals(".")) return "";
        return norm;
    }

    private static String joinPaths(String base, String rel) {
        String nb = normalizePath(base);
        String rr = rel == null ? "" : rel.strip().replace('\\', '/');
        if (rr.isEmpty() || rr.equals(".")) return nb;
        if (rr.startsWith("/") || (rr.length() >= 2 && rr.charAt(1) == ':')) return null;
        String combined = nb.isEmpty() ? rr : nb + "/" + rr;
        Deque<String> parts = new ArrayDeque<>();
        for (String seg : combined.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.removeLast();
            } else {
                parts.addLast(seg);
            }
        }
        return String.join("/", parts);
    }

    private static String matchPatternTarget(String spec, String pattern, String target) {
        int star = pattern.indexOf('*');
        if (star < 0) {
            return spec.equals(pattern) ? joinPaths("", target) : null;
        }
        String prefix = pattern.substring(0, star);
        String suffix = pattern.substring(star + 1);
        if (!spec.startsWith(prefix) || !spec.endsWith(suffix) || spec.length() < prefix.length() + suffix.length()) {
            return null;
        }
        String wildcard = spec.substring(prefix.length(), spec.length() - suffix.length());
        String replaced = target.contains("*") ? target.replace("*", wildcard) : joinPaths(target, wildcard);
        return replaced == null ? null : joinPaths("", replaced);
    }

    private static boolean matchesAnyGlob(String dir, Set<String> globs) {
        String normDir = normalizePath(dir);
        boolean matched = false;
        for (String raw : globs) {
            if (raw == null || raw.isBlank() || raw.strip().startsWith("!")) continue;
            String g = normalizePath(raw);
            if (!g.isEmpty() && globMatches(g, normDir)) {
                matched = true;
                break;
            }
        }
        if (!matched) return false;
        for (String raw : globs) {
            if (raw == null || !raw.strip().startsWith("!")) continue;
            String g = normalizePath(raw.strip().substring(1));
            if (!g.isEmpty() && globMatches(g, normDir)) return false;
        }
        return true;
    }

    private static boolean globMatches(String glob, String path) {
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

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException("SYNTAX_CANCELLED");
        }
    }
}
