package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.polyglot.*;
import com.evolution.analysis.treesitter.TreeSitterSourceFrontend;
import com.evolution.analysis.javaparser.JavaGrpcSourceFrontend;
import com.evolution.analysis.javaparser.JavaServiceSyntaxExtractor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Bundled grammar parsing of immutable bytes, one file at a time. No project
 * compiler, plugin, interpreter, model request or target library is loaded.
 */
public final class StaticPolyglotScan {
    private static final int MEMORY_ROW_CAP = 65_536;
    private static final int PARSE_WORKERS = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 8));
    private static final int PARSE_BATCH_LIMIT = 512;
    static final String DECLARATIVE_PROVIDER = "source.declarative-infra:m8.4-v4";
    private static final String DECLARATIVE_TOOL_IDENTITY = ContentDigest.sha256Utf8(DECLARATIVE_PROVIDER).value();
    private static final Pattern PYTHON_FROM_IMPORT = Pattern.compile("^from\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s+import\\b");
    private static final Pattern DOCKERFILE_FROM = Pattern.compile(
            "(?i)^FROM\\s+(?:--[^\\s]+\\s+)*([^\\s#]+)(?:\\s+AS\\s+([A-Za-z0-9_.-]+))?");
    private static final Pattern DOCKERFILE_EXPOSE = Pattern.compile("(?i)^EXPOSE\\s+(.+)");
    private static final Pattern YAML_KEY = Pattern.compile("^['\"]?([A-Za-z0-9_./{}-]+)['\"]?\\s*:(?:\\s+(.*))?$");
    private static final Pattern GRAPHQL_TYPE_HEADER = Pattern.compile(
            "^(?:extend\\s+)?(type|interface|input|enum|union|scalar)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern GRAPHQL_FIELD = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\([^)]*\\))?\\s*:\\s*([^\\s{#]+)");
    private static final Set<String> OPENAPI_METHODS = Set.of("get", "post", "put", "patch", "delete", "head",
            "options");
    private static final Set<String> GRAPHQL_ROOT_TYPES = Set.of("Query", "Mutation", "Subscription");
    private static final Set<String> BASELINE_LANGUAGES = LanguageProfile.baseline().stream()
            .map(LanguageProfile::language).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final ThreadPoolExecutor PARSE_POOL = createParsePool();

    private static ThreadPoolExecutor createParsePool() {
        var pool = new ThreadPoolExecutor(
                PARSE_WORKERS, PARSE_WORKERS, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    var thread = new Thread(runnable, "evo-polyglot-parse");
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    public record Result(SemanticTables.Table records, Map<String, Long> categories, Map<String, Long> failures,
            long documents, long parsed, long workerFileBytes, long cacheHits, long cacheMisses,
            JsWorkspaceAliasResolver.WorkspaceModel jsWorkspace,
            Map<String, ContentDigest> fileSyntaxDigests) {
        public Result(SemanticTables.Table records, Map<String, Long> categories, Map<String, Long> failures,
                long documents, long parsed, long workerFileBytes) {
            this(records, categories, failures, documents, parsed, workerFileBytes, 0L, 0L,
                    JsWorkspaceAliasResolver.WorkspaceModel.EMPTY, Map.of());
        }

        public Result(SemanticTables.Table records, Map<String, Long> categories, Map<String, Long> failures,
                long documents, long parsed, long workerFileBytes, long cacheHits, long cacheMisses) {
            this(records, categories, failures, documents, parsed, workerFileBytes, cacheHits, cacheMisses,
                    JsWorkspaceAliasResolver.WorkspaceModel.EMPTY, Map.of());
        }

        public Result(SemanticTables.Table records, Map<String, Long> categories, Map<String, Long> failures,
                long documents, long parsed, long workerFileBytes, long cacheHits, long cacheMisses,
                JsWorkspaceAliasResolver.WorkspaceModel jsWorkspace) {
            this(records, categories, failures, documents, parsed, workerFileBytes, cacheHits, cacheMisses,
                    jsWorkspace, Map.of());
        }

        public Result {
            categories = Map.copyOf(categories);
            failures = Map.copyOf(failures);
            jsWorkspace = jsWorkspace == null ? JsWorkspaceAliasResolver.WorkspaceModel.EMPTY : jsWorkspace;
            fileSyntaxDigests = fileSyntaxDigests == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(fileSyntaxDigests));
        }

        public Map<String, Object> summary() {
            var values = new TreeMap<String, Object>(Map.of("provider", TreeSitterSourceFrontend.provider(), "records",
                    records.identity(), "documents", documents, "parsedDocuments", parsed, "categories", categories,
                    "failures", failures, "trust", "SOURCE_SYNTAX", "boundCallTargets", false, "workerFileBytes",
                    workerFileBytes, "population", "LANGUAGE_DOCUMENT_ATTEMPTS"));
            values.put("javaRpcProvider", JavaGrpcSourceFrontend.PROVIDER);
            values.put("javaRpcAcquisition", "SELECTIVE_SOURCE_SYNTAX");
            return Map.copyOf(values);
        }
    }

    private enum TaskMode {
        TREE_SITTER,
        JAVA_GRPC,
        JAVA_SERVICE_SYNTAX,
        DECLARATIVE_INFRA,
        OPENAPI_JSON_PROBE,
        YAML_CONFIG_PROBE,
        PROXY_CONFIG_PROBE,
        LOGBACK_XML_PROBE,
        BUILD_DESCRIPTOR_PROBE,
        UNSUPPORTED_CONFIG_DSL,
        SFC_SCRIPT
    }

    private record ParseTask(String document, Object rawPathField, String path, String hinted, String language,
            String provider, ContentHandle source, TaskMode mode) {
    }

    private record ParseOutput(ParseTask task, TreeSitterSourceFrontend.Summary summary,
            List<TreeSitterSourceFrontend.Fact> facts, String failureReason, boolean cancelledError, boolean skipped) {
        static ParseOutput skip(ParseTask task) {
            return new ParseOutput(task, null, List.of(), null, false, true);
        }
    }

    public Result run(PolyglotInventory.Report inventory, SemanticTables tables, RepositoryContentStore content,
            Path scratch, BooleanSupplier cancelled) throws IOException {
        return run(inventory, tables, content, scratch, cancelled, digest -> null, false, Optional.empty(), null);
    }

    public Result run(PolyglotInventory.Report inventory, SemanticTables tables, RepositoryContentStore content,
            Path scratch, BooleanSupplier cancelled,
            Map<ContentDigest, byte[]> verifiedBytes, boolean fastArchitecture) throws IOException {
        return run(inventory, tables, content, scratch, cancelled, verifiedBytes::get, fastArchitecture,
                Optional.empty(), null);
    }

    Result run(PolyglotInventory.Report inventory, SemanticTables tables, RepositoryContentStore content, Path scratch,
            BooleanSupplier cancelled,
            Function<ContentDigest, byte[]> verifiedBytes, boolean fastArchitecture) throws IOException {
        return run(inventory, tables, content, scratch, cancelled, verifiedBytes, fastArchitecture, Optional.empty(),
                null);
    }

    Result run(PolyglotInventory.Report inventory, SemanticTables tables, RepositoryContentStore content, Path scratch,
            BooleanSupplier cancelled,
            Function<ContentDigest, byte[]> verifiedBytes, boolean fastArchitecture,
            Optional<Path> incrementalCacheRoot) throws IOException {
        return run(inventory, tables, content, scratch, cancelled, verifiedBytes, fastArchitecture,
                incrementalCacheRoot, null);
    }

    Result run(PolyglotInventory.Report inventory, SemanticTables tables, RepositoryContentStore content, Path scratch,
            BooleanSupplier cancelled,
            Function<ContentDigest, byte[]> verifiedBytes, boolean fastArchitecture,
            Optional<Path> incrementalCacheRoot, List<SemanticTables.Row> preloadedRows) throws IOException {
        var categories = new TreeMap<String, Long>();
        var failures = new TreeMap<String, Long>();
        long[] counts = { 0L, 0L }; // [documents, parsed]
        int fileBudget = (int) Math.min(64_000_000L, Math.max(1_000_000L, Runtime.getRuntime().maxMemory() / 16));
        long maxBatchBytes = Math.min(64_000_000L, Math.max(8_000_000L, Runtime.getRuntime().maxMemory() / 16));
        var supportedGrammars = TreeSitterSourceFrontend.languages();
        var treeSitter = new TreeSitterSourceFrontend();
        var javaGrpc = new JavaGrpcSourceFrontend();
        var javaServiceSyntax = new JavaServiceSyntaxExtractor();
        var memoryRows = new TreeMap<String, SemanticTables.Row>();
        var fileSyntaxDigests = new TreeMap<String, ContentDigest>();
        var spillRef = new SemanticRootCensus.Index[] { null };
        var toolIdentities = new ConcurrentHashMap<String, String>();
        IncrementalPolyglotCache polyglotCache = incrementalCacheRoot.map(IncrementalPolyglotCache::open).orElse(null);
        var batch = new ArrayList<ParseTask>(PARSE_BATCH_LIMIT);
        var jsWorkspaceConfigs = new TreeMap<String, String>();
        long batchBytes = 0L;
        SemanticTables.Reader inputReader = preloadedRows == null ? tables.open(inventory.entries(), cancelled) : null;
        Iterator<SemanticTables.Row> preloadedIt = preloadedRows != null ? preloadedRows.iterator() : null;
        try {
            SemanticTables.Row row;
            while ((row = (preloadedIt != null ? (preloadedIt.hasNext() ? preloadedIt.next() : null)
                    : inputReader.next())) != null) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new IOException("SYNTAX_CANCELLED");
                if (!"FILE".equals(row.fields().get("kind")) || !"ACQUIRED".equals(row.fields().get("disposition")))
                    continue;
                if (PolyglotInventory.INSTALLED_DEPENDENCY_DEFERRED.equals(row.fields().get("reason")))
                    continue;
                Object rawPathField = row.fields().get("rawPath");
                String path = new LogicalPath(GraphJson.string(rawPathField)).utf8().orElse("");
                var languages = GraphJson.array(row.fields().get("languageCandidates")).stream().map(GraphJson::string)
                        .toList();
                var source = PolyglotScan.handle(row.fields().get("content"));
                // Passively collect JS/TS workspace alias config files (tsconfig, jsconfig,
                // package.json, pnpm-workspace)
                if (isJsWorkspaceConfig(path) && jsWorkspaceConfigs.size() < 128 && source.size() > 0
                        && source.size() <= 512_000) {
                    try {
                        byte[] cfgBytes = source(content, source, fileBudget, verifiedBytes);
                        jsWorkspaceConfigs.putIfAbsent(path.replace('\\', '/'),
                                new String(cfgBytes, StandardCharsets.UTF_8));
                    } catch (IOException | RuntimeException ignored) {
                        // Passive config read stays non-fatal.
                    }
                }
                boolean matchedAnyLanguage = false;
                for (String hinted : languages) {
                    String language = hinted.equals("typescript") && path.endsWith(".tsx") ? "tsx" : hinted;
                    // Unknown/document languages retain the full inventory and native obligations.
                    if (!hinted.equals("protobuf") && !BASELINE_LANGUAGES.contains(hinted))
                        continue;
                    matchedAnyLanguage = true;
                    String document = KnowledgeGraph.derivedIdentity("syntax-document",
                            List.of(inventory.inventory(), row.key(), hinted));
                    String provider = hinted.equals("java") ? JavaGrpcSourceFrontend.PROVIDER
                            : TreeSitterSourceFrontend.provider();
                    TaskMode mode = hinted.equals("java") ? TaskMode.JAVA_GRPC : TaskMode.TREE_SITTER;
                    batch.add(new ParseTask(document, rawPathField, path, hinted, language, provider, source, mode));
                    batchBytes += Math.max(0L, source.size());
                    if (hinted.equals("java") && !DeclarativeTopologyParsers.isTestSourcePath(path)) {
                        String svcDoc = KnowledgeGraph.derivedIdentity("syntax-document",
                                List.of(inventory.inventory(), row.key(), "java-service-syntax"));
                        batch.add(new ParseTask(svcDoc, rawPathField, path, "java-service", "java",
                                JavaServiceSyntaxExtractor.PROVIDER, source, TaskMode.JAVA_SERVICE_SYNTAX));
                    }
                    if (batch.size() >= PARSE_BATCH_LIMIT || batchBytes >= maxBatchBytes) {
                        flushBatch(batch, inventory, content, fileBudget, verifiedBytes, fastArchitecture,
                                supportedGrammars, treeSitter, javaGrpc, javaServiceSyntax, polyglotCache,
                                toolIdentities,
                                memoryRows, fileSyntaxDigests, spillRef, scratch, categories, failures, counts, cancelled);
                        batch.clear();
                        batchBytes = 0L;
                    }
                }
                if (isBuildDescriptorCandidate(path, source, fileBudget)) {
                    String document = KnowledgeGraph.derivedIdentity("syntax-document",
                            List.of(inventory.inventory(), row.key(), "build-descriptor"));
                    batch.add(new ParseTask(document, rawPathField, path, "build-descriptor", "build-descriptor",
                            DECLARATIVE_PROVIDER, source, TaskMode.BUILD_DESCRIPTOR_PROBE));
                    batchBytes += Math.max(0L, source.size());
                    if (batch.size() >= PARSE_BATCH_LIMIT || batchBytes >= maxBatchBytes) {
                        flushBatch(batch, inventory, content, fileBudget, verifiedBytes, fastArchitecture,
                                supportedGrammars, treeSitter, javaGrpc, javaServiceSyntax, polyglotCache,
                                toolIdentities,
                                memoryRows, fileSyntaxDigests, spillRef, scratch, categories, failures, counts, cancelled);
                        batch.clear();
                        batchBytes = 0L;
                    }
                }
                if (!matchedAnyLanguage) {
                    Optional<String> infraLang = declarativeInfraLanguage(path);
                    if (infraLang.isPresent()) {
                        String infra = infraLang.orElseThrow();
                        String document = KnowledgeGraph.derivedIdentity("syntax-document",
                                List.of(inventory.inventory(), row.key(), infra));
                        batch.add(new ParseTask(document, rawPathField, path, infra, infra, DECLARATIVE_PROVIDER,
                                source, TaskMode.DECLARATIVE_INFRA));
                        batchBytes += Math.max(0L, source.size());
                    } else if (isSfcFile(path)) {
                        String sfcLang = path.toLowerCase(Locale.ROOT).endsWith(".vue") ? "vue" : "svelte";
                        String document = KnowledgeGraph.derivedIdentity("syntax-document",
                                List.of(inventory.inventory(), row.key(), sfcLang));
                        batch.add(new ParseTask(document, rawPathField, path, sfcLang, sfcLang,
                                TreeSitterSourceFrontend.provider(), source, TaskMode.SFC_SCRIPT));
                        batchBytes += Math.max(0L, source.size());
                    } else if (isOpenApiJsonCandidate(path, source, fileBudget)) {
                        String document = KnowledgeGraph.derivedIdentity("syntax-document",
                                List.of(inventory.inventory(), row.key(), "openapi"));
                        batch.add(
                                new ParseTask(document, rawPathField, path, "openapi", "openapi", DECLARATIVE_PROVIDER,
                                        source, TaskMode.OPENAPI_JSON_PROBE));
                        batchBytes += Math.max(0L, source.size());
                    } else if (isYamlOrPropertiesConfigCandidate(path, source, fileBudget)) {
                        String document = KnowledgeGraph.derivedIdentity("syntax-document",
                                List.of(inventory.inventory(), row.key(), "yaml-config"));
                        batch.add(new ParseTask(document, rawPathField, path, "config", "config", DECLARATIVE_PROVIDER,
                                source, TaskMode.YAML_CONFIG_PROBE));
                        batchBytes += Math.max(0L, source.size());
                    } else if (isLogbackXmlCandidate(path, source, fileBudget)) {
                        String document = KnowledgeGraph.derivedIdentity("syntax-document",
                                List.of(inventory.inventory(), row.key(), "logback"));
                        batch.add(new ParseTask(document, rawPathField, path, "config", "config", DECLARATIVE_PROVIDER,
                                source, TaskMode.LOGBACK_XML_PROBE));
                        batchBytes += Math.max(0L, source.size());
                    } else if (isProxyConfigCandidate(path, source, fileBudget)) {
                        String document = KnowledgeGraph.derivedIdentity("syntax-document",
                                List.of(inventory.inventory(), row.key(), "proxy-config"));
                        batch.add(new ParseTask(document, rawPathField, path, "config", "config", DECLARATIVE_PROVIDER,
                                source, TaskMode.PROXY_CONFIG_PROBE));
                        batchBytes += Math.max(0L, source.size());
                    } else if (isUnsupportedConfigDslCandidate(path)) {
                        String document = KnowledgeGraph.derivedIdentity("syntax-document",
                                List.of(inventory.inventory(), row.key(), "unsupported-config"));
                        batch.add(new ParseTask(document, rawPathField, path, "config", "config", DECLARATIVE_PROVIDER,
                                source, TaskMode.UNSUPPORTED_CONFIG_DSL));
                    }
                    if (batch.size() >= PARSE_BATCH_LIMIT || batchBytes >= maxBatchBytes) {
                        flushBatch(batch, inventory, content, fileBudget, verifiedBytes, fastArchitecture,
                                supportedGrammars, treeSitter, javaGrpc, javaServiceSyntax, polyglotCache,
                                toolIdentities,
                                memoryRows, fileSyntaxDigests, spillRef, scratch, categories, failures, counts, cancelled);
                        batch.clear();
                        batchBytes = 0L;
                    }
                }
            }
            if (!batch.isEmpty()) {
                flushBatch(batch, inventory, content, fileBudget, verifiedBytes, fastArchitecture,
                        supportedGrammars, treeSitter, javaGrpc, javaServiceSyntax, polyglotCache, toolIdentities,
                        memoryRows, fileSyntaxDigests, spillRef, scratch, categories, failures, counts, cancelled);
                batch.clear();
            }
            long hits = polyglotCache == null ? 0L : polyglotCache.hits();
            long misses = polyglotCache == null ? 0L : polyglotCache.misses();
            var jsWorkspace = jsWorkspaceConfigs.isEmpty()
                    ? JsWorkspaceAliasResolver.WorkspaceModel.EMPTY
                    : JsWorkspaceAliasResolver.build(jsWorkspaceConfigs);
            if (spillRef[0] == null) {
                return new Result(
                        tables.write(SemanticTables.Kind.OBSERVATION, memoryRows.values().iterator(), cancelled),
                        categories, failures, counts[0], counts[1], fileBudget, hits, misses, jsWorkspace,
                        fileSyntaxDigests);
            }
            try (var rows = spillRef[0].rows("syntax", false)) {
                var iterator = new Iterator<SemanticTables.Row>() {
                    SemanticTables.Row next;
                    boolean end;

                    public boolean hasNext() {
                        if (next != null)
                            return true;
                        if (end)
                            return false;
                        try {
                            next = rows.next();
                            end = next == null;
                            return !end;
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    }

                    public SemanticTables.Row next() {
                        if (!hasNext())
                            throw new NoSuchElementException();
                        var value = next;
                        next = null;
                        return value;
                    }
                };
                return new Result(tables.write(SemanticTables.Kind.OBSERVATION, iterator, cancelled), categories,
                        failures, counts[0], counts[1], fileBudget, hits, misses, jsWorkspace, fileSyntaxDigests);
            } catch (UncheckedIOException failure) {
                throw failure.getCause();
            }
        } finally {
            if (inputReader != null)
                try {
                    inputReader.close();
                } catch (IOException ignored) {
                }
            if (spillRef[0] != null)
                try {
                    spillRef[0].close();
                } catch (IOException ignored) {
                }
            if (polyglotCache != null)
                polyglotCache.flushQuietly();
        }
    }

    private static void flushBatch(
            List<ParseTask> batch,
            PolyglotInventory.Report inventory,
            RepositoryContentStore content,
            int fileBudget,
            Function<ContentDigest, byte[]> verifiedBytes,
            boolean fastArchitecture,
            Set<String> supportedGrammars,
            TreeSitterSourceFrontend treeSitter,
            JavaGrpcSourceFrontend javaGrpc,
            JavaServiceSyntaxExtractor javaServiceSyntax,
            IncrementalPolyglotCache polyglotCache,
            ConcurrentHashMap<String, String> toolIdentities,
            TreeMap<String, SemanticTables.Row> memoryRows,
            TreeMap<String, ContentDigest> fileSyntaxDigests,
            SemanticRootCensus.Index[] spillRef,
            Path scratch,
            TreeMap<String, Long> categories,
            TreeMap<String, Long> failures,
            long[] counts,
            BooleanSupplier cancelled) throws IOException {
        int n = batch.size();
        ParseOutput[] results = new ParseOutput[n];
        // Share CAS-verified bytes across tasks in the same batch (e.g. JAVA_GRPC + JAVA_SERVICE_SYNTAX
        // on the same .java file) to avoid duplicate disk reads and SHA-256 verification.
        var batchByteCache = new ConcurrentHashMap<ContentDigest, byte[]>();
        Function<ContentDigest, byte[]> sharedVerifiedBytes = digest -> {
            byte[] pre = verifiedBytes.apply(digest);
            if (pre != null) return pre;
            return batchByteCache.get(digest);
        };
        if (n == 1 || PARSE_WORKERS <= 1) {
            for (int i = 0; i < n; i++) {
                var out = parseOne(batch.get(i), content, fileBudget, sharedVerifiedBytes, batchByteCache, fastArchitecture,
                        supportedGrammars, treeSitter, javaGrpc, javaServiceSyntax, polyglotCache, toolIdentities,
                        cancelled);
                if (out.cancelledError())
                    throw new IOException("SYNTAX_CANCELLED");
                results[i] = out;
            }
        } else {
            // Dynamic work-stealing across PARSE_WORKERS with LPT (largest files first)
            // scheduling
            // eliminates straggler stalls on large polyglot files while outputs are still
            // sorted deterministically.
            var orderedBatch = new ArrayList<>(batch);
            orderedBatch.sort(Comparator.comparingLong((ParseTask t) -> t.source().size()).reversed()
                    .thenComparing(ParseTask::document));
            int workers = Math.min(PARSE_WORKERS, n);
            var nextIndex = new java.util.concurrent.atomic.AtomicInteger(0);
            var cancelFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
            List<Future<Void>> futures = new ArrayList<>(workers);
            boolean failed = false;
            try {
                for (int w = 0; w < workers; w++) {
                    futures.add(PARSE_POOL.submit(() -> {
                        int idx;
                        while (!cancelFlag.get() && (idx = nextIndex.getAndIncrement()) < n) {
                            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                                cancelFlag.set(true);
                                results[idx] = new ParseOutput(orderedBatch.get(idx), null, List.of(),
                                        "SYNTAX_CANCELLED", true, false);
                                return null;
                            }
                            ParseOutput out = parseOne(orderedBatch.get(idx), content, fileBudget, sharedVerifiedBytes,
                                    batchByteCache, fastArchitecture, supportedGrammars, treeSitter, javaGrpc,
                                    javaServiceSyntax, polyglotCache, toolIdentities, cancelled);
                            results[idx] = out;
                            if (out.cancelledError()) {
                                cancelFlag.set(true);
                                return null;
                            }
                        }
                        return null;
                    }));
                }
                for (var future : futures) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        failed = true;
                        cancelFlag.set(true);
                        throw new IOException("SYNTAX_CANCELLED");
                    }
                    future.get();
                }
                if (cancelFlag.get()) {
                    throw new IOException("SYNTAX_CANCELLED");
                }
            } catch (InterruptedException interrupted) {
                failed = true;
                cancelFlag.set(true);
                Thread.currentThread().interrupt();
                throw new IOException("SYNTAX_CANCELLED");
            } catch (ExecutionException exec) {
                failed = true;
                cancelFlag.set(true);
                if (exec.getCause() instanceof IOException io)
                    throw io;
                if (exec.getCause() instanceof RuntimeException re)
                    throw re;
                if (exec.getCause() instanceof Error err)
                    throw err;
                throw new IOException("SYNTAX_SOURCE_OR_PROVIDER_FAILED", exec.getCause());
            } finally {
                if (failed) {
                    for (var f : futures)
                        f.cancel(true);
                }
            }
        }
        var outputs = new ArrayList<ParseOutput>(n);
        for (int i = 0; i < n; i++) {
            ParseOutput out = results[i];
            if (out == null)
                continue;
            if (out.cancelledError())
                throw new IOException("SYNTAX_CANCELLED");
            if (!out.skipped())
                outputs.add(out);
        }
        // Merge rows in strictly deterministic sorted document/key order so canonical
        // digests remain identical.
        outputs.sort(Comparator.comparing(o -> o.task().document()));
        for (var out : outputs) {
            var task = out.task();
            counts[0]++;
            var metadata = new TreeMap<String, Object>();
            metadata.put("rowType", "DOCUMENT");
            metadata.put("document", task.document());
            metadata.put("path", task.rawPathField());
            metadata.put("language", task.hinted());
            metadata.put("content", task.source());
            metadata.put("workerFileBytes", fileBudget);
            metadata.put("provider", task.provider());
            if (out.failureReason() != null) {
                metadata.put("status", out.failureReason());
                failures.merge(task.hinted() + ":" + out.failureReason(), 1L, Long::sum);
            } else {
                for (var fact : out.facts()) {
                    var fields = new TreeMap<String, Object>();
                    fields.put("rowType", "FACT");
                    fields.put("document", task.document());
                    fields.put("content", task.source());
                    fields.put("language", task.hinted());
                    fields.put("path", task.rawPathField());
                    fields.put("semanticBinding", "NOT_ESTABLISHED");
                    fields.put("provider", task.provider());
                    fields.put("fact", fact.toMap());
                    String key = CanonicalJson.sha256(
                            fact.category().startsWith("RPC_") || usesRichFactIdentity(fact.category(), task.hinted())
                                    ? List.of(task.document(), fact.category(), fact.nodeKind(), fact.span(),
                                            fact.name(), fact.attributes())
                                    : List.of(task.document(), fact.category(), fact.nodeKind(), fact.span())).value();
                    var obsRow = new SemanticTables.Row(SemanticTables.Kind.OBSERVATION, key,
                            inventory.inventory(), fields);
                    putSyntaxRow(memoryRows, spillRef, scratch, key, obsRow);
                    categories.merge(task.hinted() + ":" + fact.category(), 1L, Long::sum);
                }
                counts[1]++;
                var summary = out.summary();
                metadata.put("status",
                        summary.errors() == 0
                                ? (task.hinted().equals("protobuf") ? "SCHEMA_DECLARATIONS_OBSERVED" : "PARSED")
                                : "RECOVERED_SYNTAX");
                metadata.put("summary", summary.toMap());
                if (summary.errors() > 0)
                    failures.merge(task.hinted() + ":SYNTAX_ERROR", 1L, Long::sum);
            }
            String docKey = "document:" + task.document();
            putSyntaxRow(memoryRows, spillRef, scratch, docKey, new SemanticTables.Row(
                    SemanticTables.Kind.OBSERVATION, docKey, inventory.inventory(), metadata));
            ContentDigest entryDigest = CanonicalJson.sha256(List.of(
                    task.path(),
                    task.provider(),
                    task.hinted(),
                    task.source().digest(),
                    metadata.get("status"),
                    out.failureReason() != null ? out.failureReason() : out.summary().toMap(),
                    out.facts().stream().map(TreeSitterSourceFrontend.Fact::toMap).toList()));
            fileSyntaxDigests.merge(task.path(), entryDigest,
                    (prev, cur) -> CanonicalJson.sha256(List.of(prev, cur)));
        }
    }

    private static ParseOutput parseOne(
            ParseTask task,
            RepositoryContentStore content,
            int fileBudget,
            Function<ContentDigest, byte[]> verifiedBytes,
            ConcurrentHashMap<ContentDigest, byte[]> batchByteCache,
            boolean fastArchitecture,
            Set<String> supportedGrammars,
            TreeSitterSourceFrontend treeSitter,
            JavaGrpcSourceFrontend javaGrpc,
            JavaServiceSyntaxExtractor javaServiceSyntax,
            IncrementalPolyglotCache polyglotCache,
            ConcurrentHashMap<String, String> toolIdentities,
            BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return new ParseOutput(task, null, List.of(), "SYNTAX_CANCELLED", true, false);
        }
        try {
            if (task.mode() == TaskMode.UNSUPPORTED_CONFIG_DSL) {
                return new ParseOutput(task, null, List.of(), "UNSUPPORTED_CONFIG_DSL", false, false);
            }

            if (task.mode() == TaskMode.JAVA_SERVICE_SYNTAX) {
                ContentDigest cacheKey = (polyglotCache != null && task.source().size() <= fileBudget)
                        ? IncrementalPolyglotCache.key(JavaServiceSyntaxExtractor.PROVIDER,
                                JavaServiceSyntaxExtractor.PROVIDER, "java-service",
                                task.source().digest(), task.source().size(), fastArchitecture)
                        : null;
                if (cacheKey != null) {
                    var cachedSlot = polyglotCache.lookup(cacheKey);
                    if (cachedSlot.isPresent()) {
                        var hit = cachedSlot.orElseThrow();
                        if (hit.isSentinel()) {
                            return ParseOutput.skip(task);
                        }
                        var entry = hit.entry();
                        return entry.facts().isEmpty()
                                ? ParseOutput.skip(task)
                                : new ParseOutput(task, entry.summary(), entry.facts(), null, false, false);
                    }
                }
                byte[] bytes = sourceCached(content, task.source(), fileBudget, verifiedBytes, batchByteCache);
                if (!JavaServiceSyntaxExtractor.candidate(bytes)) {
                    if (cacheKey != null) {
                        polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.NOT_CANDIDATE);
                    }
                    return ParseOutput.skip(task);
                }
                var recordedFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
                var parsedJava = javaServiceSyntax.parse(bytes,
                        fact -> recordedFacts.add(new TreeSitterSourceFrontend.Fact(fact.category(),
                                fact.nodeKind(), fact.name(), fact.written(), fact.span(), fact.owner(),
                                fact.recovered(), fact.attributes())),
                        cancelled);
                if (recordedFacts.isEmpty()) {
                    if (cacheKey != null) {
                        polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.NOT_CANDIDATE);
                    }
                    return ParseOutput.skip(task);
                }
                var summary = new TreeSitterSourceFrontend.Summary(parsedJava.nodes(), parsedJava.facts(),
                        parsedJava.errors(), parsedJava.grammar(), parsedJava.toolIdentity());
                var immutableFacts = List.copyOf(recordedFacts);
                if (cacheKey != null) {
                    polyglotCache.put(cacheKey, new IncrementalPolyglotCache.Entry(summary, immutableFacts));
                }
                return new ParseOutput(task, summary, immutableFacts, null, false, false);
            }

            if (task.mode() == TaskMode.YAML_CONFIG_PROBE) {
                String lowerPath = task.path().toLowerCase(Locale.ROOT);
                boolean isHelmPath = DeclarativeTopologyParsers.isHelmTemplatePath(task.path());
                boolean isTestPath = DeclarativeTopologyParsers.isTestSourcePath(task.path());
                String extTag = lowerPath.endsWith(".properties") ? "props" : "yaml";
                String probeSlot = "yaml-probe@" + extTag + "@" + task.path()
                        + "@" + (isHelmPath ? "h" : "n") + (isTestPath ? "t" : "m");
                ContentDigest cacheKey = (polyglotCache != null && task.source().size() <= fileBudget)
                        ? IncrementalPolyglotCache.key(DECLARATIVE_PROVIDER, DECLARATIVE_TOOL_IDENTITY,
                                probeSlot, task.source().digest(), task.source().size(), fastArchitecture)
                        : null;
                if (cacheKey != null) {
                    var cachedSlot = polyglotCache.lookup(cacheKey);
                    if (cachedSlot.isPresent()) {
                        var hit = cachedSlot.orElseThrow();
                        if (hit.isSentinel()) {
                            return hit.sentinel() == IncrementalPolyglotCache.ProbeStatus.UNRENDERED_HELM_TEMPLATE
                                    ? new ParseOutput(task, null, List.of(), "UNRENDERED_HELM_TEMPLATE", false, false)
                                    : ParseOutput.skip(task);
                        }
                        var entry = hit.entry();
                        return entry.facts().isEmpty()
                                ? ParseOutput.skip(task)
                                : new ParseOutput(task, entry.summary(), entry.facts(), null, false, false);
                    }
                }
                byte[] probe;
                try {
                    probe = source(content, task.source(), fileBudget, verifiedBytes);
                } catch (IOException ignored) {
                    return ParseOutput.skip(task);
                }
                var coords = new SourceCoordinates(probe, "UTF-8");
                String text = coords.text();
                String configKind;
                if (DeclarativeTopologyParsers.looksLikeKubernetesYaml(text)) {
                    if (isHelmPath) {
                        if (cacheKey != null) {
                            polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.UNRENDERED_HELM_TEMPLATE);
                        }
                        return new ParseOutput(task, null, List.of(), "UNRENDERED_HELM_TEMPLATE", false, false);
                    }
                    configKind = "k8s";
                } else if (DeclarativeTopologyParsers.looksLikePrometheusYaml(task.path(), text)) {
                    configKind = "prometheus";
                } else if (DeclarativeTopologyParsers.looksLikeGrafanaDatasources(task.path(), text)) {
                    configKind = "grafana";
                } else if (DeclarativeTopologyParsers.looksLikeFilebeatYaml(task.path(), text)) {
                    configKind = "filebeat";
                } else if (DeclarativeTopologyParsers.looksLikeSpringConfig(task.path(), text)) {
                    if (isTestPath) {
                        if (cacheKey != null) {
                            polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.NOT_CANDIDATE);
                        }
                        return ParseOutput.skip(task);
                    }
                    configKind = lowerPath.endsWith(".properties")
                            ? "spring-props"
                            : "spring-yaml";
                } else {
                    if (cacheKey != null) {
                        polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.NOT_CANDIDATE);
                    }
                    return ParseOutput.skip(task);
                }
                var recordedFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
                long[] stats = { 0L, 0L };
                switch (configKind) {
                    case "k8s" -> DeclarativeTopologyParsers.parseKubernetesManifest(task.path(), text, coords, recordedFacts, stats, cancelled);
                    case "prometheus" -> DeclarativeTopologyParsers.parsePrometheusConfig(text, coords, recordedFacts, stats, cancelled);
                    case "grafana" -> DeclarativeTopologyParsers.parseGrafanaDatasources(text, coords, recordedFacts, stats, cancelled);
                    case "filebeat" -> DeclarativeTopologyParsers.parseFilebeatConfig(text, coords, recordedFacts, stats, cancelled);
                    default -> DeclarativeTopologyParsers.parseSpringConfig(task.path(), text, coords, recordedFacts, stats, cancelled);
                }
                if (recordedFacts.isEmpty()) {
                    if ("k8s".equals(configKind) && text.contains("{{") && text.contains("}}")) {
                        if (cacheKey != null) {
                            polyglotCache.putProbe(cacheKey,
                                    IncrementalPolyglotCache.ProbeStatus.UNRENDERED_HELM_TEMPLATE);
                        }
                        return new ParseOutput(task, null, List.of(), "UNRENDERED_HELM_TEMPLATE", false, false);
                    }
                    if (cacheKey != null) {
                        polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.NOT_CANDIDATE);
                    }
                    return ParseOutput.skip(task);
                }
                var summary = new TreeSitterSourceFrontend.Summary(stats[0], recordedFacts.size(), stats[1],
                        DECLARATIVE_PROVIDER + ":" + configKind, DECLARATIVE_TOOL_IDENTITY);
                var immutableFacts = List.copyOf(recordedFacts);
                if (cacheKey != null) {
                    polyglotCache.put(cacheKey, new IncrementalPolyglotCache.Entry(summary, immutableFacts));
                }
                return new ParseOutput(task, summary, immutableFacts, null, false, false);
            }

            if (task.mode() == TaskMode.PROXY_CONFIG_PROBE) {
                boolean apache = isApacheConfigPath(task.path());
                boolean unsupportedDsl = !apache && isUnsupportedConfigDslCandidate(task.path());
                String kind = apache ? "apache-httpd" : "nginx";
                ContentDigest cacheKey = (polyglotCache != null && task.source().size() <= fileBudget)
                        ? IncrementalPolyglotCache.key(DECLARATIVE_PROVIDER, DECLARATIVE_TOOL_IDENTITY,
                                "proxy-probe-" + kind + (unsupportedDsl ? "-u" : ""),
                                task.source().digest(), task.source().size(), fastArchitecture)
                        : null;
                if (cacheKey != null) {
                    var cachedSlot = polyglotCache.lookup(cacheKey);
                    if (cachedSlot.isPresent()) {
                        var hit = cachedSlot.orElseThrow();
                        if (hit.isSentinel()) {
                            return ParseOutput.skip(task);
                        }
                        var entry = hit.entry();
                        return entry.facts().isEmpty()
                                ? ParseOutput.skip(task)
                                : new ParseOutput(task, entry.summary(), entry.facts(), null, false, false);
                    }
                }
                byte[] probe;
                try {
                    probe = source(content, task.source(), fileBudget, verifiedBytes);
                } catch (IOException ignored) {
                    return ParseOutput.skip(task);
                }
                var coords = new SourceCoordinates(probe, "UTF-8");
                String text = coords.text();
                if (!apache && !DeclarativeTopologyParsers.looksLikeNginxConfig(text)) {
                    if (unsupportedDsl) {
                        return new ParseOutput(task, null, List.of(), "UNSUPPORTED_CONFIG_DSL", false, false);
                    }
                    if (cacheKey != null) {
                        polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.NOT_CANDIDATE);
                    }
                    return ParseOutput.skip(task);
                }
                var recordedFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
                long[] stats = { 0L, 0L };
                if (apache) {
                    DeclarativeTopologyParsers.parseApacheHttpdConfig(text, coords, recordedFacts, stats, cancelled);
                } else {
                    DeclarativeTopologyParsers.parseNginxConfig(text, coords, recordedFacts, stats, cancelled);
                }
                if (recordedFacts.isEmpty()) {
                    if (cacheKey != null) {
                        polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.NOT_CANDIDATE);
                    }
                    return ParseOutput.skip(task);
                }
                var summary = new TreeSitterSourceFrontend.Summary(stats[0], recordedFacts.size(), stats[1],
                        DECLARATIVE_PROVIDER + ":" + kind, DECLARATIVE_TOOL_IDENTITY);
                var immutableFacts = List.copyOf(recordedFacts);
                if (cacheKey != null) {
                    polyglotCache.put(cacheKey, new IncrementalPolyglotCache.Entry(summary, immutableFacts));
                }
                return new ParseOutput(task, summary, immutableFacts, null, false, false);
            }

            if (task.mode() == TaskMode.LOGBACK_XML_PROBE) {
                ContentDigest cacheKey = (polyglotCache != null && task.source().size() <= fileBudget)
                        ? IncrementalPolyglotCache.key(DECLARATIVE_PROVIDER, DECLARATIVE_TOOL_IDENTITY,
                                "config-logback", task.source().digest(), task.source().size(), fastArchitecture)
                        : null;
                if (cacheKey != null) {
                    var cachedSlot = polyglotCache.lookup(cacheKey);
                    if (cachedSlot.isPresent()) {
                        var hit = cachedSlot.orElseThrow();
                        if (hit.isSentinel()) {
                            return ParseOutput.skip(task);
                        }
                        var entry = hit.entry();
                        return entry.facts().isEmpty()
                                ? ParseOutput.skip(task)
                                : new ParseOutput(task, entry.summary(), entry.facts(), null, false, false);
                    }
                }
                byte[] probe;
                try {
                    probe = source(content, task.source(), fileBudget, verifiedBytes);
                } catch (IOException ignored) {
                    return ParseOutput.skip(task);
                }
                var coords = new SourceCoordinates(probe, "UTF-8");
                var recordedFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
                long[] stats = { 0L, 0L };
                DeclarativeTopologyParsers.parseLogbackXml(coords.text(), coords, recordedFacts, stats, cancelled);
                if (recordedFacts.isEmpty()) {
                    if (cacheKey != null) {
                        polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.NOT_CANDIDATE);
                    }
                    return ParseOutput.skip(task);
                }
                var summary = new TreeSitterSourceFrontend.Summary(stats[0], recordedFacts.size(), stats[1],
                        DECLARATIVE_PROVIDER + ":logback", DECLARATIVE_TOOL_IDENTITY);
                var immutableFacts = List.copyOf(recordedFacts);
                if (cacheKey != null) {
                    polyglotCache.put(cacheKey, new IncrementalPolyglotCache.Entry(summary, immutableFacts));
                }
                return new ParseOutput(task, summary, immutableFacts, null, false, false);
            }

            if (task.mode() == TaskMode.BUILD_DESCRIPTOR_PROBE) {
                String lowerPath = task.path().toLowerCase(Locale.ROOT);
                String descKind = lowerPath.endsWith("pom.xml")
                        ? "maven"
                        : (lowerPath.endsWith("build.gradle") || lowerPath.endsWith("build.gradle.kts"))
                                ? "gradle"
                                : (lowerPath.endsWith(".csproj") || lowerPath.endsWith(".vbproj") || lowerPath.endsWith(".fsproj"))
                                        ? "dotnet"
                                        : "other";
                ContentDigest cacheKey = (polyglotCache != null && task.source().size() <= fileBudget)
                        ? IncrementalPolyglotCache.key(DECLARATIVE_PROVIDER, DECLARATIVE_TOOL_IDENTITY,
                                "build-" + descKind + "@" + task.path(), task.source().digest(), task.source().size(), fastArchitecture)
                        : null;
                if (cacheKey != null) {
                    var cachedSlot = polyglotCache.lookup(cacheKey);
                    if (cachedSlot.isPresent()) {
                        var hit = cachedSlot.orElseThrow();
                        if (hit.isSentinel()) {
                            return ParseOutput.skip(task);
                        }
                        var entry = hit.entry();
                        return entry.facts().isEmpty()
                                ? ParseOutput.skip(task)
                                : new ParseOutput(task, entry.summary(), entry.facts(), null, false, false);
                    }
                }
                byte[] probe;
                try {
                    probe = source(content, task.source(), fileBudget, verifiedBytes);
                } catch (IOException ignored) {
                    return ParseOutput.skip(task);
                }
                var coords = new SourceCoordinates(probe, "UTF-8");
                var recordedFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
                long[] stats = { 0L, 0L };
                DeclarativeTopologyParsers.parseBuildDescriptorStarters(
                        task.path(), coords.text(), coords, recordedFacts, stats, cancelled);
                DeclarativeTopologyParsers.parseBuildDescriptorMetadata(
                        task.path(), coords.text(), coords, recordedFacts, stats, cancelled);
                if (recordedFacts.isEmpty()) {
                    if (cacheKey != null) {
                        polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.NOT_CANDIDATE);
                    }
                    return ParseOutput.skip(task);
                }
                var summary = new TreeSitterSourceFrontend.Summary(stats[0], recordedFacts.size(), stats[1],
                        DECLARATIVE_PROVIDER + ":build-" + descKind, DECLARATIVE_TOOL_IDENTITY);
                var immutableFacts = List.copyOf(recordedFacts);
                if (cacheKey != null) {
                    polyglotCache.put(cacheKey, new IncrementalPolyglotCache.Entry(summary, immutableFacts));
                }
                return new ParseOutput(task, summary, immutableFacts, null, false, false);
            }

            if (task.mode() == TaskMode.JAVA_GRPC) {
                ContentDigest cacheKey = (polyglotCache != null && task.source().size() <= fileBudget)
                        ? IncrementalPolyglotCache.key(JavaGrpcSourceFrontend.PROVIDER, JavaGrpcSourceFrontend.PROVIDER,
                                "java", task.source().digest(), task.source().size(), fastArchitecture)
                        : null;
                if (cacheKey != null) {
                    var cachedSlot = polyglotCache.lookup(cacheKey);
                    if (cachedSlot.isPresent()) {
                        var hit = cachedSlot.orElseThrow();
                        if (hit.isSentinel()) {
                            return ParseOutput.skip(task);
                        }
                        var entry = hit.entry();
                        return new ParseOutput(task, entry.summary(), entry.facts(), null, false, false);
                    }
                }
                byte[] bytes = sourceCached(content, task.source(), fileBudget, verifiedBytes, batchByteCache);
                if (!JavaGrpcSourceFrontend.candidate(bytes)) {
                    if (cacheKey != null) {
                        polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.NOT_CANDIDATE);
                    }
                    return ParseOutput.skip(task);
                }
                var recordedFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
                var parsedJava = javaGrpc.parse(bytes,
                        fact -> recordedFacts.add(new TreeSitterSourceFrontend.Fact(fact.category(),
                                fact.nodeKind(), fact.name(), fact.written(), fact.span(), fact.owner(),
                                fact.recovered(), fact.attributes())),
                        cancelled);
                var summary = new TreeSitterSourceFrontend.Summary(parsedJava.nodes(), parsedJava.facts(),
                        parsedJava.errors(), parsedJava.grammar(), parsedJava.toolIdentity());
                var immutableFacts = List.copyOf(recordedFacts);
                if (cacheKey != null) {
                    polyglotCache.put(cacheKey, new IncrementalPolyglotCache.Entry(summary, immutableFacts));
                }
                return new ParseOutput(task, summary, immutableFacts, null, false, false);
            }

            if (task.mode() == TaskMode.OPENAPI_JSON_PROBE) {
                ContentDigest cacheKey = (polyglotCache != null && task.source().size() <= fileBudget)
                        ? IncrementalPolyglotCache.key(DECLARATIVE_PROVIDER, DECLARATIVE_TOOL_IDENTITY,
                                "openapi-json-probe@" + task.path(), task.source().digest(), task.source().size(), fastArchitecture)
                        : null;
                if (cacheKey != null) {
                    var cachedSlot = polyglotCache.lookup(cacheKey);
                    if (cachedSlot.isPresent()) {
                        var hit = cachedSlot.orElseThrow();
                        if (hit.isSentinel()) {
                            return ParseOutput.skip(task);
                        }
                        var entry = hit.entry();
                        return new ParseOutput(task, entry.summary(), entry.facts(), null, false, false);
                    }
                }
                byte[] probe;
                try {
                    probe = source(content, task.source(), fileBudget, verifiedBytes);
                } catch (IOException ignored) {
                    /*
                     * Non-OpenAPI or unreadable JSON stays in inventory without failing syntax
                     * scan.
                     */
                    return ParseOutput.skip(task);
                }
                if (!containsAscii(probe, "\"openapi\"") && !containsAscii(probe, "\"swagger\"")) {
                    if (cacheKey != null) {
                        polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.NOT_OPENAPI);
                    }
                    return ParseOutput.skip(task);
                }
                var recordedFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
                var summary = parseDeclarativeInfra("openapi", task.path(), probe, recordedFacts, cancelled);
                var immutableFacts = List.copyOf(recordedFacts);
                if (cacheKey != null) {
                    polyglotCache.put(cacheKey, new IncrementalPolyglotCache.Entry(summary, immutableFacts));
                }
                return new ParseOutput(task, summary, immutableFacts, null, false, false);
            }

            if (task.mode() == TaskMode.SFC_SCRIPT) {
                String jsToolId = toolIdentities.computeIfAbsent("javascript",
                        lang -> TreeSitterSourceFrontend.toolIdentity(lang).orElse(""));
                String tsToolId = toolIdentities.computeIfAbsent("typescript",
                        lang -> TreeSitterSourceFrontend.toolIdentity(lang).orElse(""));
                String tsxToolId = toolIdentities.computeIfAbsent("tsx",
                        lang -> TreeSitterSourceFrontend.toolIdentity(lang).orElse(""));
                String sfcToolId = "js=" + jsToolId + "|ts=" + tsToolId + "|tsx=" + tsxToolId;
                ContentDigest cacheKey = (polyglotCache != null && task.source().size() <= fileBudget)
                        ? IncrementalPolyglotCache.key(task.provider(), sfcToolId, "sfc-" + task.language(),
                                task.source().digest(), task.source().size(), fastArchitecture)
                        : null;
                if (cacheKey != null) {
                    var cachedSlot = polyglotCache.lookup(cacheKey);
                    if (cachedSlot.isPresent()) {
                        var hit = cachedSlot.orElseThrow();
                        if (hit.isSentinel()) {
                            return ParseOutput.skip(task);
                        }
                        var entry = hit.entry();
                        return new ParseOutput(task, entry.summary(), entry.facts(), null, false, false);
                    }
                }
                byte[] bytes = source(content, task.source(), fileBudget, verifiedBytes);
                String text = new String(bytes, StandardCharsets.UTF_8);
                var scripts = JsWorkspaceAliasResolver.extractSfcScripts(text);
                if (scripts.isEmpty()) {
                    if (cacheKey != null) {
                        polyglotCache.putProbe(cacheKey, IncrementalPolyglotCache.ProbeStatus.NOT_CANDIDATE);
                    }
                    return ParseOutput.skip(task);
                }
                var recordedFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
                long totalNodes = 0, totalFacts = 0, totalErrors = 0;
                String lastGrammar = task.language();
                String lastToolId = "";
                for (var script : scripts) {
                    String grammarLang = supportedGrammars.contains(script.language()) ? script.language()
                            : "javascript";
                    if (!supportedGrammars.contains(grammarLang))
                        continue;
                    // Preserve exact 1-based line numbers from the enclosing .vue/.svelte file and
                    // avoid
                    // span collisions across multiple <script> blocks in the same SFC.
                    String paddedCode = script.startLine() > 1
                            ? "\n".repeat(script.startLine() - 1) + script.code()
                            : script.code();
                    var sSum = treeSitter.parse(grammarLang, paddedCode.getBytes(StandardCharsets.UTF_8),
                            recordedFacts::add, cancelled, fastArchitecture);
                    totalNodes += sSum.nodes();
                    totalFacts += sSum.facts();
                    totalErrors += sSum.errors();
                    lastGrammar = sSum.grammar();
                    lastToolId = sSum.toolIdentity();
                }
                var summary = new TreeSitterSourceFrontend.Summary(totalNodes, totalFacts, totalErrors, lastGrammar,
                        lastToolId);
                var immutableFacts = List.copyOf(recordedFacts);
                if (cacheKey != null) {
                    polyglotCache.put(cacheKey, new IncrementalPolyglotCache.Entry(summary, immutableFacts));
                }
                return new ParseOutput(task, summary, immutableFacts, null, false, false);
            }

            if (task.mode() == TaskMode.TREE_SITTER && !supportedGrammars.contains(task.language())) {
                throw new IOException("SYNTAX_GRAMMAR_UNAVAILABLE");
            }
            String toolId = task.mode() == TaskMode.DECLARATIVE_INFRA
                    ? DECLARATIVE_TOOL_IDENTITY
                    : toolIdentities.computeIfAbsent(task.language(),
                            lang -> TreeSitterSourceFrontend.toolIdentity(lang).orElse(""));
            String cacheLanguage = task.mode() == TaskMode.DECLARATIVE_INFRA && "docker-compose".equals(task.language())
                    ? "docker-compose@" + parentDir(task.path())
                    : task.language();
            ContentDigest cacheKey = (polyglotCache != null && task.source().size() <= fileBudget)
                    ? IncrementalPolyglotCache.key(task.provider(), toolId, cacheLanguage,
                            task.source().digest(), task.source().size(), fastArchitecture)
                    : null;
            ContentDigest legacyKey = cacheKey == null ? null
                    : IncrementalPolyglotCache.legacyV2Key(task.provider(), toolId, cacheLanguage,
                            task.source().digest(), task.source().size(), fastArchitecture);
            if (cacheKey != null) {
                var cachedSlot = polyglotCache.lookup(cacheKey, legacyKey);
                if (cachedSlot.isPresent() && cachedSlot.orElseThrow().isEntry()) {
                    var entry = cachedSlot.orElseThrow().entry();
                    return new ParseOutput(task, entry.summary(), entry.facts(), null, false, false);
                }
            }
            byte[] bytes = source(content, task.source(), fileBudget, verifiedBytes);
            var recordedFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
            TreeSitterSourceFrontend.Summary summary;
            if (task.mode() == TaskMode.DECLARATIVE_INFRA) {
                summary = parseDeclarativeInfra(task.language(), task.path(), bytes, recordedFacts, cancelled);
            } else {
                summary = treeSitter.parse(task.language(), bytes, recordedFacts::add, cancelled, fastArchitecture);
            }
            var immutableFacts = List.copyOf(recordedFacts);
            if (cacheKey != null) {
                polyglotCache.put(cacheKey, new IncrementalPolyglotCache.Entry(summary, immutableFacts));
            }
            return new ParseOutput(task, summary, immutableFacts, null, false, false);
        } catch (IOException | IllegalArgumentException failure) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return new ParseOutput(task, null, List.of(), "SYNTAX_CANCELLED", true, false);
            }
            String reason = failure.getMessage();
            if (reason == null || !reason.matches("[A-Z][A-Z0-9_]{0,100}"))
                reason = "SYNTAX_SOURCE_OR_PROVIDER_FAILED";
            return new ParseOutput(task, null, List.of(), reason, false, false);
        }
    }

    private static Optional<String> declarativeInfraLanguage(String path) {
        String norm = path.replace('\\', '/');
        int slash = norm.lastIndexOf('/');
        String base = slash < 0 ? norm : norm.substring(slash + 1);
        String lower = base.toLowerCase(Locale.ROOT);
        if (base.equals("Dockerfile") || base.startsWith("Dockerfile.") || lower.endsWith(".dockerfile"))
            return Optional.of("dockerfile");
        if ((lower.startsWith("docker-compose") || lower.equals("compose.yml") || lower.equals("compose.yaml"))
                && (lower.endsWith(".yml") || lower.endsWith(".yaml")))
            return Optional.of("docker-compose");
        if ((lower.startsWith("openapi") || lower.startsWith("swagger"))
                && (lower.endsWith(".yml") || lower.endsWith(".yaml")))
            return Optional.of("openapi");
        if (lower.endsWith(".graphql") || lower.endsWith(".gql"))
            return Optional.of("graphql");
        return Optional.empty();
    }

    private static boolean isSfcFile(String path) {
        if (path == null)
            return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".vue") || lower.endsWith(".svelte");
    }

    private static boolean isOpenApiJsonCandidate(String path, ContentHandle source, int fileBudget) {
        String norm = path.replace('\\', '/');
        int slash = norm.lastIndexOf('/');
        String lower = (slash < 0 ? norm : norm.substring(slash + 1)).toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") && source.size() > 10 && source.size() <= Math.min(fileBudget, 4_000_000L);
    }

    private static boolean usesRichFactIdentity(String category, String language) {
        return "java-service".equals(language) || Set.of(
                "INFRA_SCRAPE_TARGET", "INFRA_CONFIG_REF", "GATEWAY_ROUTE",
                "SPRING_CLOUD_ROLE", "CONFIG_SERVICE_IDENTITY", "CONFIG_PROPERTY",
                "INFRA_DEPENDENCY_STARTER")
                .contains(category);
    }

    private static boolean isYamlOrPropertiesConfigCandidate(String path, ContentHandle source, int fileBudget) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties"))
                && source.size() > 0 && source.size() <= Math.min(fileBudget, 4_000_000L);
    }

    private static boolean isLogbackXmlCandidate(String path, ContentHandle source, int fileBudget) {
        if (path == null || DeclarativeTopologyParsers.isTestSourcePath(path)) return false;
        String norm = path.replace('\\', '/');
        int slash = norm.lastIndexOf('/');
        String base = (slash < 0 ? norm : norm.substring(slash + 1)).toLowerCase(Locale.ROOT);
        return base.startsWith("logback") && base.endsWith(".xml") && !base.contains("test")
                && source.size() > 0 && source.size() <= Math.min(fileBudget, 4_000_000L);
    }

    private static boolean isApacheConfigPath(String path) {
        if (path == null) return false;
        String norm = "/" + path.replace('\\', '/').toLowerCase(Locale.ROOT);
        int slash = norm.lastIndexOf('/');
        String base = norm.substring(slash + 1);
        if (!base.endsWith(".conf")) return false;
        return base.startsWith("httpd") || base.equals("apache.conf") || base.equals("apache2.conf")
                || norm.contains("/httpd/") || norm.contains("/apache/") || norm.contains("/apache2/");
    }

    private static boolean isProxyConfigCandidate(String path, ContentHandle source, int fileBudget) {
        if (path == null || DeclarativeTopologyParsers.isTestSourcePath(path)) return false;
        if (source.size() <= 0 || source.size() > Math.min(fileBudget, 4_000_000L)) return false;
        if (isApacheConfigPath(path)) return true;
        String norm = "/" + path.replace('\\', '/').toLowerCase(Locale.ROOT);
        int slash = norm.lastIndexOf('/');
        String base = norm.substring(slash + 1);
        if (!base.endsWith(".conf")) return false;
        return base.startsWith("nginx") || base.equals("default.conf") || norm.contains("/nginx/");
    }

    private static boolean isBuildDescriptorCandidate(String path, ContentHandle source, int fileBudget) {
        if (path == null || DeclarativeTopologyParsers.isTestSourcePath(path)) return false;
        if (source.size() <= 0 || source.size() > Math.min(fileBudget, 4_000_000L)) return false;
        String norm = path.replace('\\', '/');
        int slash = norm.lastIndexOf('/');
        String base = (slash < 0 ? norm : norm.substring(slash + 1)).toLowerCase(Locale.ROOT);
        return base.equals("pom.xml") || base.equals("build.gradle") || base.equals("build.gradle.kts")
                || base.endsWith(".csproj") || base.endsWith(".vbproj") || base.endsWith(".fsproj");
    }

    private static byte[] sourceCached(
            RepositoryContentStore content,
            ContentHandle handle,
            int fileBudget,
            Function<ContentDigest, byte[]> verifiedBytes,
            ConcurrentHashMap<ContentDigest, byte[]> batchByteCache) throws IOException {
        byte[] bytes = source(content, handle, fileBudget, verifiedBytes);
        if (bytes.length <= 512_000 && batchByteCache != null) {
            batchByteCache.putIfAbsent(handle.digest(), bytes);
        }
        return bytes;
    }

    private static String parentDir(String path) {
        if (path == null || path.isEmpty()) return "";
        String norm = path.replace('\\', '/');
        int slash = norm.lastIndexOf('/');
        return slash <= 0 ? "" : norm.substring(0, slash);
    }

    private static boolean isUnsupportedConfigDslCandidate(String path) {
        if (path == null) return false;
        String norm = "/" + path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!norm.endsWith(".conf")) return false;
        return norm.contains("/pipeline/") || norm.contains("/logging/") || norm.contains("/conf/")
                || norm.contains("/config/") || norm.contains("/etc/") || norm.contains("/ops/");
    }

    private static boolean containsAscii(byte[] bytes, String token) {
        byte[] needle = token.getBytes(StandardCharsets.US_ASCII);
        if (needle.length == 0 || bytes.length < needle.length)
            return false;
        outer: for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j])
                    continue outer;
            }
            return true;
        }
        return false;
    }

    private static TreeSitterSourceFrontend.Summary parseDeclarativeInfra(
            String language,
            String path,
            byte[] bytes,
            List<TreeSitterSourceFrontend.Fact> out,
            BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IOException("SYNTAX_CANCELLED");
        var coordinates = new SourceCoordinates(bytes, "UTF-8");
        String text = coordinates.text();
        long[] stats = { 0L, 0L }; // [nodes, errors]
        switch (language) {
            case "dockerfile" -> parseDockerfile(text, coordinates, out, stats, cancelled);
            case "docker-compose" -> parseDockerCompose(path, text, coordinates, out, stats, cancelled);
            case "openapi" -> parseOpenApi(text, coordinates, out, stats, cancelled);
            case "graphql" -> parseGraphQl(text, coordinates, out, stats, cancelled);
            default -> {
            }
        }
        return new TreeSitterSourceFrontend.Summary(stats[0], out.size(), stats[1],
                DECLARATIVE_PROVIDER + ":" + language, DECLARATIVE_TOOL_IDENTITY);
    }

    private static void parseDockerfile(String text, SourceCoordinates coords, List<TreeSitterSourceFrontend.Fact> out,
            long[] stats, BooleanSupplier cancelled) throws IOException {
        int len = text.length();
        int offset = 0;
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new IOException("SYNTAX_CANCELLED");
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String rawLine = text.substring(offset, contentEnd);
            String trimmed = rawLine.trim();
            stats[0]++;
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                int lead = rawLine.indexOf(trimmed);
                int start = offset + Math.max(0, lead);
                var fromMatcher = DOCKERFILE_FROM.matcher(trimmed);
                if (fromMatcher.find()) {
                    String image = fromMatcher.group(1);
                    var attrs = new TreeMap<String, String>();
                    attrs.put("image", image);
                    attrs.put("spec", "dockerfile");
                    if (fromMatcher.group(2) != null)
                        attrs.put("stage", fromMatcher.group(2));
                    var span = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                    out.add(new TreeSitterSourceFrontend.Fact("INFRA_BASE_IMAGE", "dockerfile_from", image,
                            truncate(trimmed, 1024), span, "", false, attrs));
                } else {
                    var exposeMatcher = DOCKERFILE_EXPOSE.matcher(trimmed);
                    if (exposeMatcher.find()) {
                        String rest = exposeMatcher.group(1);
                        int hash = rest.indexOf('#');
                        if (hash >= 0)
                            rest = rest.substring(0, hash);
                        int tokenSearch = exposeMatcher.start(1);
                        for (String rawTok : rest.trim().split("\\s+")) {
                            if (rawTok.isEmpty() || rawTok.length() > 64)
                                continue;
                            int tokPos = trimmed.indexOf(rawTok, tokenSearch);
                            int tokStart = tokPos >= 0 ? start + tokPos : start;
                            int tokEnd = Math.min(contentEnd, tokStart + rawTok.length());
                            tokenSearch = tokPos >= 0 ? tokPos + rawTok.length() : tokenSearch;
                            int slash = rawTok.indexOf('/');
                            String port = slash < 0 ? rawTok : rawTok.substring(0, slash);
                            String proto = slash < 0 ? "tcp" : rawTok.substring(slash + 1).toLowerCase(Locale.ROOT);
                            var span = coords.range(tokStart, Math.max(tokStart + 1, tokEnd),
                                    SourceCoordinates.Encoding.UTF16);
                            out.add(new TreeSitterSourceFrontend.Fact("INFRA_PORT", "dockerfile_expose", port,
                                    truncate(trimmed, 1024), span, "", false,
                                    Map.of("port", port, "protocol", proto, "spec", "dockerfile")));
                        }
                    }
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
    }

    private static final Set<String> COMPOSE_RESERVED_SECTIONS = Set.of(
            "networks", "volumes", "configs", "secrets");
    private static final Pattern COMPOSE_FLOW_CONTEXT = Pattern.compile(
            "\\bcontext\\s*:\\s*(['\"]?[^,}\\s'\"]+['\"]?)");

    private static final class ComposeServiceBuffer {
        final String name;
        final String written;
        final SourceCoordinates.Range span;
        final String slot;
        final List<TreeSitterSourceFrontend.Fact> children = new ArrayList<>();
        String buildDir = "";
        String containerName = "";
        boolean dockerSocket;
        boolean hasImage;
        boolean hasBuild;
        boolean hasPorts;
        boolean hasCommand;
        boolean hasServiceProps;

        ComposeServiceBuffer(String name, String written, SourceCoordinates.Range span) {
            this.name = name;
            this.written = written;
            this.span = span;
            this.slot = span.start() + ":" + span.end();
        }

        void flush(boolean v1Mode, List<TreeSitterSourceFrontend.Fact> out) {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean reserved = COMPOSE_RESERVED_SECTIONS.contains(lower) || lower.startsWith("x-");
            boolean hasCoreSignal = hasImage || hasBuild || hasPorts || hasCommand;
            if (reserved && !hasCoreSignal) {
                return;
            }
            if (v1Mode && !hasCoreSignal && !hasServiceProps) {
                return;
            }
            var attrs = new TreeMap<String, String>();
            attrs.put("role", "service");
            attrs.put("spec", "docker-compose");
            if (!buildDir.isEmpty()) attrs.put("buildDir", buildDir);
            if (!containerName.isEmpty()) attrs.put("containerName", containerName);
            if (dockerSocket) attrs.put("dockerSocket", "true");
            out.add(new TreeSitterSourceFrontend.Fact("DECLARATION", "compose_service", name,
                    written, span, "", false, attrs));
            out.addAll(children);
        }
    }

    private static boolean hasTopLevelServicesSection(String text) {
        int len = text.length();
        int offset = 0;
        while (offset < len) {
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String rawLine = text.substring(offset, contentEnd);
            String trimmed = stripYamlComment(rawLine).trim();
            if (!trimmed.isEmpty() && leadingSpaces(rawLine) == 0
                    && (trimmed.equals("services:") || trimmed.startsWith("services: "))) {
                return true;
            }
            offset = nl < 0 ? len : nl + 1;
        }
        return false;
    }

    private static String resolveComposeBuildDir(String composeParentDir, String rawBuild) {
        if (rawBuild == null || rawBuild.isBlank()) return "";
        String cleaned = DeclarativeTopologyParsers.resolvePlaceholderOrValue(unquoteYaml(rawBuild.trim()));
        if (cleaned.isEmpty()) return "";
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("git@")) {
            return "";
        }
        String normRel = cleaned.replace('\\', '/');
        while (normRel.endsWith("/") && normRel.length() > 1) {
            normRel = normRel.substring(0, normRel.length() - 1);
        }
        String resolved = normalizeRelativePath(composeParentDir, normRel);
        return resolved.isEmpty() ? "." : resolved;
    }

    static void parseDockerCompose(String path, String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        String composeParentDir = parentDir(path);
        boolean hasServicesKey = hasTopLevelServicesSection(text);
        boolean v1Mode = !hasServicesKey;
        int len = text.length();
        int offset = 0;
        boolean inServices = v1Mode;
        int serviceIndent = v1Mode ? 0 : -1;
        int propIndent = -1;
        ComposeServiceBuffer current = null;
        String currentSection = "";
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new IOException("SYNTAX_CANCELLED");
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String rawLine = text.substring(offset, contentEnd);
            String trimmed = stripYamlComment(rawLine).trim();
            stats[0]++;
            if (!trimmed.isEmpty()) {
                int indent = leadingSpaces(rawLine);
                int start = offset + indent;
                if (!v1Mode && indent == 0) {
                    if (current != null) {
                        current.flush(false, out);
                        current = null;
                    }
                    inServices = trimmed.equals("services:") || trimmed.startsWith("services: ");
                    serviceIndent = -1;
                    propIndent = -1;
                    currentSection = "";
                } else if (inServices) {
                    if (serviceIndent < 0)
                        serviceIndent = indent;
                    if (indent == serviceIndent && !trimmed.startsWith("-")) {
                        if (current != null) {
                            current.flush(v1Mode, out);
                            current = null;
                        }
                        var m = YAML_KEY.matcher(trimmed);
                        if (m.matches()) {
                            String serviceName = m.group(1);
                            String lowerSvc = serviceName.toLowerCase(Locale.ROOT);
                            if (v1Mode && (lowerSvc.equals("version") || lowerSvc.equals("services")
                                    || COMPOSE_RESERVED_SECTIONS.contains(lowerSvc) || lowerSvc.startsWith("x-"))) {
                                propIndent = -1;
                                currentSection = "";
                            } else {
                                var span = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                                current = new ComposeServiceBuffer(serviceName, truncate(trimmed, 1024), span);
                                propIndent = -1;
                                currentSection = "";
                            }
                        }
                    } else if (indent > serviceIndent && current != null) {
                        if (propIndent < 0 || indent < propIndent)
                            propIndent = indent;
                        if (indent == propIndent && !trimmed.startsWith("-")) {
                            var m = YAML_KEY.matcher(trimmed);
                            if (m.matches()) {
                                String key = m.group(1);
                                String rawVal = m.group(2) == null ? "" : m.group(2).trim();
                                String val = unquoteYaml(rawVal);
                                currentSection = key;
                                switch (key) {
                                    case "image" -> {
                                        current.hasImage = true;
                                        if (!val.isEmpty()) {
                                            var span = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                                            current.children.add(new TreeSitterSourceFrontend.Fact(
                                                    "INFRA_BASE_IMAGE", "compose_image", val,
                                                    truncate(trimmed, 1024), span, current.slot, false,
                                                    Map.of("image", val, "spec", "docker-compose")));
                                        }
                                    }
                                    case "build" -> {
                                        current.hasBuild = true;
                                        if (!rawVal.isEmpty()) {
                                            if (rawVal.startsWith("{")) {
                                                var cm = COMPOSE_FLOW_CONTEXT.matcher(rawVal);
                                                if (cm.find()) {
                                                    String bd = resolveComposeBuildDir(composeParentDir, cm.group(1));
                                                    if (!bd.isEmpty()) current.buildDir = bd;
                                                }
                                            } else {
                                                String bd = resolveComposeBuildDir(composeParentDir, val);
                                                if (!bd.isEmpty()) current.buildDir = bd;
                                            }
                                        }
                                    }
                                    case "container_name" -> {
                                        current.hasServiceProps = true;
                                        String cn = DeclarativeTopologyParsers.resolvePlaceholderOrValue(val);
                                        if (!cn.isEmpty()) current.containerName = cn;
                                    }
                                    case "command", "entrypoint" -> current.hasCommand = true;
                                    case "ports" -> {
                                        current.hasPorts = true;
                                        if (rawVal.startsWith("[") && rawVal.endsWith("]")) {
                                            for (String item : rawVal.substring(1, rawVal.length() - 1).split(",")) {
                                                String portSpec = unquoteYaml(item.trim());
                                                if (!portSpec.isEmpty() && portSpec.length() <= 128) {
                                                    var span = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                                                    current.children.add(new TreeSitterSourceFrontend.Fact(
                                                            "INFRA_PORT", "compose_port", portSpec,
                                                            truncate(trimmed, 1024), span, current.slot, false,
                                                            Map.of("port", portSpec, "spec", "docker-compose")));
                                                }
                                            }
                                        }
                                    }
                                    case "volumes" -> {
                                        current.hasServiceProps = true;
                                        if (rawVal.contains("/var/run/docker.sock")) {
                                            current.dockerSocket = true;
                                        }
                                    }
                                    case "depends_on" -> {
                                        current.hasServiceProps = true;
                                        if (rawVal.startsWith("[") && rawVal.endsWith("]")) {
                                            for (String item : rawVal.substring(1, rawVal.length() - 1).split(",")) {
                                                String dep = unquoteYaml(item.trim());
                                                if (!dep.isEmpty() && dep.length() <= 128) {
                                                    var span = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                                                    current.children.add(new TreeSitterSourceFrontend.Fact(
                                                            "INFRA_DEPENDENCY", "compose_depends_on", dep,
                                                            truncate(trimmed, 1024), span, current.slot, false,
                                                            Map.of("target", dep, "spec", "docker-compose")));
                                                }
                                            }
                                        }
                                    }
                                    case "links", "external_links" -> {
                                        current.hasServiceProps = true;
                                        if (rawVal.startsWith("[") && rawVal.endsWith("]")) {
                                            for (String item : rawVal.substring(1, rawVal.length() - 1).split(",")) {
                                                DeclarativeTopologyParsers.emitComposeLinkTarget(
                                                        item, trimmed, start, contentEnd, current.slot, coords,
                                                        current.children);
                                            }
                                        }
                                    }
                                    case "environment", "expose", "restart", "env_file" ->
                                            current.hasServiceProps = true;
                                    default -> {
                                    }
                                }
                            }
                        } else if (currentSection.equals("build") && indent > propIndent) {
                            var m = YAML_KEY.matcher(trimmed);
                            if (m.matches() && "context".equals(m.group(1)) && m.group(2) != null) {
                                String bd = resolveComposeBuildDir(composeParentDir, m.group(2).trim());
                                if (!bd.isEmpty()) current.buildDir = bd;
                            }
                        } else if (currentSection.equals("volumes")) {
                            if (trimmed.contains("/var/run/docker.sock")) {
                                current.dockerSocket = true;
                            }
                        } else if (currentSection.equals("ports") && trimmed.startsWith("-")) {
                            current.hasPorts = true;
                            String portSpec = unquoteYaml(trimmed.substring(1).trim());
                            if (!portSpec.isEmpty() && portSpec.length() <= 128) {
                                var span = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                                current.children.add(new TreeSitterSourceFrontend.Fact("INFRA_PORT", "compose_port", portSpec,
                                        truncate(trimmed, 1024), span, current.slot, false,
                                        Map.of("port", portSpec, "spec", "docker-compose")));
                            }
                        } else if (currentSection.equals("depends_on")) {
                            String dep = "";
                            if (trimmed.startsWith("-")) {
                                dep = unquoteYaml(trimmed.substring(1).trim());
                            } else if (indent == propIndent + 2 || indent > propIndent) {
                                var m = YAML_KEY.matcher(trimmed);
                                if (m.matches() && !Set.of("condition", "restart", "required").contains(m.group(1))) {
                                    dep = m.group(1);
                                }
                            }
                            if (!dep.isEmpty() && dep.length() <= 128) {
                                var span = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                                current.children.add(new TreeSitterSourceFrontend.Fact("INFRA_DEPENDENCY", "compose_depends_on", dep,
                                        truncate(trimmed, 1024), span, current.slot, false,
                                        Map.of("target", dep, "spec", "docker-compose")));
                            }
                        } else if (currentSection.equals("environment") || currentSection.equals("links")
                                || currentSection.equals("external_links")) {
                            DeclarativeTopologyParsers.parseDockerComposeEnvOrLinksLine(
                                    currentSection, trimmed, indent, propIndent, start, contentEnd,
                                    current.slot, coords, current.children);
                        }
                    }
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
        if (current != null) {
            current.flush(v1Mode, out);
        }
    }

    private static void parseOpenApi(String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        if (text.stripLeading().startsWith("{")) {
            try {
                Object parsed = GraphJson.read(text, new GraphJson.Limits(4_000_000, 64, 500_000));
                if (!(parsed instanceof Map<?, ?> rawRoot))
                    return;
                var root = GraphJson.object(rawRoot);
                stats[0]++;
                String version = root.get("openapi") instanceof String v ? v
                        : root.get("swagger") instanceof String s ? s : "";
                if (version.isEmpty())
                    return;
                String title = root.get("info") instanceof Map<?, ?> infoMap
                        && GraphJson.object(infoMap).get("title") instanceof String t && !t.isBlank()
                                ? t
                                : "openapi:" + version;
                var docSpan = coords.range(0, Math.min(text.length(), 1), SourceCoordinates.Encoding.UTF16);
                out.add(new TreeSitterSourceFrontend.Fact("DECLARATION", "openapi_document", title,
                        "openapi " + version, docSpan, "", false, Map.of("openapi", version, "spec", "openapi")));
                if (root.get("paths") instanceof Map<?, ?> rawPaths) {
                    var sortedPaths = new TreeMap<>(GraphJson.object(rawPaths));
                    for (var entry : sortedPaths.entrySet()) {
                        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                            throw new IOException("SYNTAX_CANCELLED");
                        String routePath = entry.getKey();
                        if (routePath.isEmpty() || routePath.length() > 2048)
                            continue;
                        stats[0]++;
                        int pos = text.indexOf("\"" + routePath + "\"");
                        int spanStart = pos >= 0 ? pos : 0;
                        int spanEnd = pos >= 0 ? Math.min(text.length(), pos + routePath.length() + 2)
                                : Math.min(text.length(), 1);
                        var span = coords.range(spanStart, Math.max(spanStart + 1, spanEnd),
                                SourceCoordinates.Encoding.UTF16);
                        var methods = new TreeSet<String>();
                        if (entry.getValue() instanceof Map<?, ?> pathItem) {
                            for (Object mk : pathItem.keySet()) {
                                String lower = String.valueOf(mk).toLowerCase(Locale.ROOT);
                                if (OPENAPI_METHODS.contains(lower))
                                    methods.add(lower);
                            }
                        }
                        if (methods.isEmpty()) {
                            out.add(new TreeSitterSourceFrontend.Fact("ROUTE_CANDIDATE", "openapi_path",
                                    quoteLiteral(routePath), routePath, span, "", false,
                                    Map.of("method", "UNKNOWN", "spec", "openapi")));
                        } else {
                            for (String method : methods) {
                                String upper = method.toUpperCase(Locale.ROOT);
                                out.add(new TreeSitterSourceFrontend.Fact("ROUTE_CANDIDATE", "openapi_" + method,
                                        quoteLiteral(routePath), upper + " " + routePath, span, "", false,
                                        Map.of("method", upper, "spec", "openapi")));
                            }
                        }
                    }
                }
            } catch (IllegalArgumentException malformed) {
                stats[1]++;
            }
            return;
        }
        int len = text.length();
        int offset = 0;
        boolean inPaths = false;
        int pathIndent = -1;
        String currentPath = null;
        SourceCoordinates.Range currentPathSpan = null;
        boolean emittedMethodForPath = false;
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new IOException("SYNTAX_CANCELLED");
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String rawLine = text.substring(offset, contentEnd);
            String trimmed = stripYamlComment(rawLine).trim();
            stats[0]++;
            if (!trimmed.isEmpty()) {
                int indent = leadingSpaces(rawLine);
                int start = offset + indent;
                if (indent == 0) {
                    if (currentPath != null && !emittedMethodForPath) {
                        out.add(new TreeSitterSourceFrontend.Fact("ROUTE_CANDIDATE", "openapi_path",
                                quoteLiteral(currentPath), currentPath, currentPathSpan, "", false,
                                Map.of("method", "UNKNOWN", "spec", "openapi")));
                    }
                    currentPath = null;
                    currentPathSpan = null;
                    emittedMethodForPath = false;
                    inPaths = trimmed.equals("paths:") || trimmed.startsWith("paths: ");
                    pathIndent = -1;
                    if (trimmed.startsWith("openapi:") || trimmed.startsWith("swagger:")) {
                        String version = unquoteYaml(trimmed.substring(trimmed.indexOf(':') + 1).trim());
                        var span = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                        out.add(new TreeSitterSourceFrontend.Fact("DECLARATION", "openapi_document",
                                "openapi:" + version, truncate(trimmed, 256), span, "", false,
                                Map.of("openapi", version, "spec", "openapi")));
                    }
                } else if (inPaths) {
                    if (pathIndent < 0)
                        pathIndent = indent;
                    if (indent == pathIndent) {
                        if (currentPath != null && !emittedMethodForPath) {
                            out.add(new TreeSitterSourceFrontend.Fact("ROUTE_CANDIDATE", "openapi_path",
                                    quoteLiteral(currentPath), currentPath, currentPathSpan, "", false,
                                    Map.of("method", "UNKNOWN", "spec", "openapi")));
                        }
                        int colon = trimmed.lastIndexOf(':');
                        if (colon > 0) {
                            String candidate = unquoteYaml(trimmed.substring(0, colon).trim());
                            if (candidate.startsWith("/") && candidate.length() <= 2048) {
                                currentPath = candidate;
                                currentPathSpan = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                                emittedMethodForPath = false;
                            } else {
                                currentPath = null;
                            }
                        }
                    } else if (indent > pathIndent && currentPath != null) {
                        int colon = trimmed.indexOf(':');
                        if (colon > 0) {
                            String key = unquoteYaml(trimmed.substring(0, colon).trim()).toLowerCase(Locale.ROOT);
                            if (OPENAPI_METHODS.contains(key)) {
                                var span = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                                String upper = key.toUpperCase(Locale.ROOT);
                                out.add(new TreeSitterSourceFrontend.Fact("ROUTE_CANDIDATE", "openapi_" + key,
                                        quoteLiteral(currentPath), upper + " " + currentPath, span, "", false,
                                        Map.of("method", upper, "spec", "openapi")));
                                emittedMethodForPath = true;
                            }
                        }
                    }
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
        if (currentPath != null && !emittedMethodForPath) {
            out.add(new TreeSitterSourceFrontend.Fact("ROUTE_CANDIDATE", "openapi_path",
                    quoteLiteral(currentPath), currentPath, currentPathSpan, "", false,
                    Map.of("method", "UNKNOWN", "spec", "openapi")));
        }
    }

    private static void parseGraphQl(String text, SourceCoordinates coords, List<TreeSitterSourceFrontend.Fact> out,
            long[] stats, BooleanSupplier cancelled) throws IOException {
        int len = text.length();
        int offset = 0;
        boolean inBlockString = false;
        int braceDepth = 0;
        String currentTypeName = "";
        String currentTypeSlot = "";
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new IOException("SYNTAX_CANCELLED");
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String rawLine = text.substring(offset, contentEnd);
            stats[0]++;
            int tripleIdx = 0;
            while ((tripleIdx = rawLine.indexOf("\"\"\"", tripleIdx)) >= 0) {
                inBlockString = !inBlockString;
                tripleIdx += 3;
            }
            if (!inBlockString && !rawLine.contains("\"\"\"")) {
                String clean = stripGraphQlComment(rawLine);
                String trimmed = clean.trim();
                if (!trimmed.isEmpty()) {
                    int lead = rawLine.indexOf(trimmed.charAt(0));
                    int start = offset + Math.max(0, lead);
                    if (braceDepth == 0) {
                        var header = GRAPHQL_TYPE_HEADER.matcher(trimmed);
                        if (header.find()) {
                            String kind = header.group(1);
                            String name = header.group(2);
                            var span = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                            out.add(new TreeSitterSourceFrontend.Fact("DECLARATION", "graphql_" + kind, name,
                                    truncate(trimmed, 1024), span, "", false,
                                    Map.of("graphqlKind", kind, "spec", "graphql")));
                            if (clean.indexOf('{') >= 0) {
                                currentTypeName = name;
                                currentTypeSlot = span.start() + ":" + span.end();
                            }
                        }
                    } else if (braceDepth == 1 && GRAPHQL_ROOT_TYPES.contains(currentTypeName)
                            && !trimmed.startsWith("}") && !trimmed.startsWith("@")) {
                        var field = GRAPHQL_FIELD.matcher(trimmed);
                        if (field.find()) {
                            String fieldName = field.group(1);
                            String retType = field.group(2);
                            var span = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                            out.add(new TreeSitterSourceFrontend.Fact("ROUTE_CANDIDATE", "graphql_operation",
                                    quoteLiteral("/graphql#" + currentTypeName + "." + fieldName),
                                    truncate(trimmed, 1024), span, currentTypeSlot, false,
                                    Map.of("operationType", currentTypeName.toUpperCase(Locale.ROOT),
                                            "field", fieldName, "returnType", retType, "spec", "graphql")));
                        }
                    }
                    for (int i = 0; i < clean.length(); i++) {
                        char c = clean.charAt(i);
                        if (c == '{')
                            braceDepth++;
                        else if (c == '}') {
                            braceDepth = Math.max(0, braceDepth - 1);
                            if (braceDepth == 0) {
                                currentTypeName = "";
                                currentTypeSlot = "";
                            }
                        }
                    }
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
    }

    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t'))
            i++;
        return i;
    }

    private static String stripYamlComment(String line) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble)
                inSingle = !inSingle;
            else if (c == '"' && !inSingle)
                inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble && (i == 0 || Character.isWhitespace(line.charAt(i - 1))))
                return line.substring(0, i);
        }
        return line;
    }

    private static String stripGraphQlComment(String line) {
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && inDouble) {
                i++;
                continue;
            }
            if (c == '"')
                inDouble = !inDouble;
            else if (c == '#' && !inDouble)
                return line.substring(0, i);
        }
        return line;
    }

    private static String unquoteYaml(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String quoteLiteral(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static void putSyntaxRow(TreeMap<String, SemanticTables.Row> memoryRows,
            SemanticRootCensus.Index[] spillRef, Path scratch, String key, SemanticTables.Row row) throws IOException {
        if (spillRef[0] != null) {
            spillRef[0].put("syntax", key, row);
            return;
        }
        memoryRows.put(key, row);
        if (memoryRows.size() >= MEMORY_ROW_CAP) {
            var index = SqliteSemanticCensusIndex.temporary(scratch, 65536);
            for (var entry : memoryRows.entrySet())
                index.put("syntax", entry.getKey(), entry.getValue());
            memoryRows.clear();
            spillRef[0] = index;
        }
    }

    private static byte[] source(RepositoryContentStore content, ContentHandle source, int fileBudget,
            Function<ContentDigest, byte[]> verifiedBytes) throws IOException {
        if (source.size() > fileBudget)
            throw new IOException("SYNTAX_FILE_WORKER_REQUIRED");
        byte[] cached = verifiedBytes.apply(source.digest());
        if (cached != null && cached.length == source.size())
            return cached;
        if (content instanceof StreamingContentStore scs) {
            try {
                return scs.readVerifiedBytes(source);
            } catch (IOException failure) {
                throw new IOException("SYNTAX_SOURCE_UNAVAILABLE", failure);
            }
        }
        try (var captured = content.open(source)) {
            byte[] bytes = captured.readNBytes(Math.toIntExact(source.size()) + 1);
            if (bytes.length != source.size())
                throw new IOException("SYNTAX_SOURCE_SIZE_MISMATCH");
            if (!ContentDigest.sha256(bytes).equals(source.digest()))
                throw new IOException("SYNTAX_SOURCE_UNAVAILABLE");
            return bytes;
        }
    }

    private record DeclEntry(String id, String document, String langGroup, String normPath, String dirPath,
            String fileStem, String symbolName, String proof) {
    }

    private record SiteEntry(String siteId, String document, String langGroup, String language, String normPath,
            String dirPath, String category, String name, String written, String proof) {
    }

    private record WrapperSpec(String declId, String document, String langGroup, String kind, int paramIndex,
            String methodOrRole, String basePathOrProtocol, int hop, List<String> chainProofs) {
    }

    private record CallSite(String siteId, String document, String langGroup, String language, String normPath,
            String rawPathField, Object contentHandle,
            String dirPath, String nodeKind, String spanSlot, Map<String, Object> spanMap, String ownerSlot,
            String name, String written,
            Map<Integer, String> litArgs, Map<Integer, Integer> forwardParams, ContentDigest proof) {
    }

    private static final Set<String> GENERIC_WRAPPER_NAMES = Set.of(
            "get", "post", "put", "patch", "delete", "head", "options",
            "send", "emit", "publish", "subscribe", "consume",
            "call", "run", "exec", "execute", "request", "fetch", "on", "off",
            "handle", "process", "apply", "invoke", "create", "update", "remove",
            "read", "write", "load", "save", "log", "info", "warn", "error", "debug");

    private static final Set<String> KEYWORDS = Set.of("import", "from", "as", "type", "default", "require", "package",
            "using", "export", "const", "let", "var", "async", "function", "class", "interface", "struct", "def",
            "func", "extends", "implements", "new", "return", "self", "this", "super");

    public void project(Result result, SemanticTables tables, GraphProjectionStore store, String context,
            BooleanSupplier cancelled) throws IOException {
        var nodes = store.records("nodes", GraphSnapshotCodec::node);
        var edges = store.records("edges", GraphSnapshotCodec::edge);
        var evidence = store.records("evidence", GraphSnapshotCodec::evidence);
        var protoSymbols = store.records("proto-source-symbols", GraphJson::object);
        var protoMethods = store.records("proto-source-methods", GraphJson::object);
        var byLangAndSymbol = new HashMap<String, List<DeclEntry>>();
        var byDocAndSymbol = new HashMap<String, List<DeclEntry>>();
        var byDirAndSymbol = new HashMap<String, List<DeclEntry>>();
        var byStemAndSymbol = new HashMap<String, List<DeclEntry>>();
        var docImports = new HashMap<String, Set<String>>();
        var sites = new ArrayList<SiteEntry>();
        var declByDocSlot = new HashMap<String, DeclEntry>();
        var rawWrappers = new ArrayList<Map.Entry<String, WrapperSpec>>();
        var wrapperCalls = new ArrayList<CallSite>();
        var directCandidateSpans = new HashSet<String>();
        try (var rows = tables.open(result.records(), cancelled)) {
            SemanticTables.Row row;
            while ((row = rows.next()) != null) {
                var fields = row.fields();
                String document = GraphJson.string(fields.get("document"));
                var artifact = GraphEvidence.capture("polyglot.source-syntax", row);
                evidence.putIfAbsent(artifact.identity().value(), artifact);
                if (fields.get("rowType").equals("DOCUMENT")) {
                    node(nodes, context, document, "SOURCE_DOCUMENT", artifact.identity());
                    continue;
                }
                var fact = GraphJson.object(fields.get("fact"));
                var span = GraphJson.object(fact.get("span"));
                String category = GraphJson.string(fact.get("category"));
                String language = GraphJson.string(fields.get("language"));
                String slot = span.get("start") + ":" + span.get("end");
                String id = category.equals("DECLARATION") ? declaration(document, slot)
                        : KnowledgeGraph.derivedIdentity("syntax-site",
                                (category.startsWith("RPC_") && !category.equals("RPC_METHOD"))
                                        || usesRichFactIdentity(category, language)
                                                ? List.of(document, category, fact.get("nodeKind"), span,
                                                        fact.get("name"), fact.get("attributes"))
                                                : List.of(document, category, fact.get("nodeKind"), span));
                node(nodes, context, id, switch (category) {
                    case "DECLARATION" -> language.equals("protobuf")
                            ? "PROTOBUF_" + GraphJson.string(fact.get("nodeKind")).toUpperCase(Locale.ROOT)
                            : "SOURCE_DECLARATION";
                    case "RPC_METHOD" -> "PROTOBUF_RPC_METHOD";
                    case "SCHEMA_FIELD" -> "PROTOBUF_FIELD";
                    case "CALL" -> "CALL_SITE";
                    case "ROUTE_CANDIDATE" -> "ROUTE_CANDIDATE";
                    default -> "SOURCE_" + category;
                }, artifact.identity());
                if (language.equals("protobuf") && !Boolean.TRUE.equals(fact.get("recovered"))) {
                    var attrs = GraphJson.object(fact.get("attributes"));
                    if (category.equals("DECLARATION") && fact.get("nodeKind").equals("message")) {
                        String qualifiedName = GraphJson.string(attrs.get("qualifiedName"));
                        addProtoSymbol(protoSymbols, document + "|" + qualifiedName, id);
                        if (!qualifiedName.isEmpty()) {
                            addProtoSymbol(protoSymbols, qualifiedName, id);
                        }
                    }
                    if (category.equals("RPC_METHOD"))
                        protoMethods.put(id, Map.of("document", document, "attributes", attrs, "proof",
                                artifact.identity().value()));
                } else if (!language.equals("protobuf") && !Boolean.TRUE.equals(fact.get("recovered"))) {
                    String rawField = fields.get("path") == null ? "" : GraphJson.string(fields.get("path"));
                    String rawPath = decodePath(rawField).replace('\\', '/');
                    int slash = rawPath.lastIndexOf('/');
                    String dir = slash < 0 ? "" : rawPath.substring(0, slash);
                    String base = slash < 0 ? rawPath : rawPath.substring(slash + 1);
                    int dot = base.lastIndexOf('.');
                    String stem = dot < 0 ? base : base.substring(0, dot);
                    if ((stem.equals("index") || stem.equals("__init__")) && !dir.isEmpty()) {
                        int pSlash = dir.lastIndexOf('/');
                        stem = pSlash < 0 ? dir : dir.substring(pSlash + 1);
                    }
                    String group = langGroup(language);
                    String name = fact.get("name") == null ? "" : GraphJson.string(fact.get("name"));
                    String written = fact.get("written") == null ? "" : GraphJson.string(fact.get("written"));
                    String ownerKey = GraphJson.string(fact.get("owner"));
                    var attrsMap = fact.get("attributes") instanceof Map<?, ?> m
                            ? GraphJson.object(m)
                            : Map.<String, Object>of();
                    if (Set.of("HTTP_CALL_CANDIDATE", "EVENT_PRODUCER_CANDIDATE", "EVENT_CONSUMER_CANDIDATE")
                            .contains(category)) {
                        directCandidateSpans.add(document + "|" + slot);
                    } else if (Set.of("HTTP_WRAPPER", "EVENT_WRAPPER").contains(category) && !ownerKey.isEmpty()) {
                        int paramIndex = -1;
                        if (attrsMap.get("paramIndex") instanceof String ps) {
                            try {
                                paramIndex = Integer.parseInt(ps);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if (paramIndex >= 0) {
                            String kind = category.equals("HTTP_WRAPPER") ? "HTTP" : "EVENT";
                            String methodOrRole = category.equals("HTTP_WRAPPER")
                                    ? (attrsMap.get("method") instanceof String s ? s : "UNKNOWN")
                                    : (attrsMap.get("role") instanceof String s ? s : "PRODUCER");
                            String baseOrProto = category.equals("HTTP_WRAPPER")
                                    ? (attrsMap.get("basePath") instanceof String s ? s : "")
                                    : (attrsMap.get("protocol") instanceof String s ? s : "EVENT_BUS");
                            rawWrappers.add(Map.entry(document + "|" + ownerKey,
                                    new WrapperSpec("", document, group, kind, paramIndex, methodOrRole, baseOrProto,
                                            0, List.of(artifact.identity().value()))));
                        }
                    }
                    if (category.equals("DECLARATION")) {
                        String sym = declSymbol(name, written);
                        if (!sym.isEmpty() && !KEYWORDS.contains(sym)) {
                            var entry = new DeclEntry(id, document, group, rawPath, dir, stem, sym,
                                    artifact.identity().value());
                            declByDocSlot.put(document + "|" + slot, entry);
                            addDecl(byLangAndSymbol, group + "|" + sym, entry);
                            addDecl(byDocAndSymbol, document + "|" + sym, entry);
                            addDecl(byDirAndSymbol, group + "|" + dir + "|" + sym, entry);
                            if (!stem.isEmpty())
                                addDecl(byStemAndSymbol, group + "|" + stem + "|" + sym, entry);
                        }
                    } else if (Set.of("IMPORT", "CALL", "HERITAGE").contains(category)) {
                        if (category.equals("IMPORT")) {
                            var imported = docImports.computeIfAbsent(document, k -> new TreeSet<>());
                            for (String tok : tokens(name + " " + written))
                                if (!KEYWORDS.contains(tok))
                                    imported.add(tok);
                        }
                        if (category.equals("CALL") && !attrsMap.isEmpty()) {
                            var litArgs = new TreeMap<Integer, String>();
                            var forwardParams = new TreeMap<Integer, Integer>();
                            for (int ai = 0; ai < 3; ai++) {
                                if (attrsMap.get("litArg" + ai) instanceof String lv)
                                    litArgs.put(ai, lv);
                                if (attrsMap.get("forwardParam" + ai) instanceof String fp) {
                                    try {
                                        forwardParams.put(ai, Integer.parseInt(fp));
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                            }
                            if (!litArgs.isEmpty() || !forwardParams.isEmpty()) {
                                Map<String, Object> copiedSpan = fact.get("span") instanceof Map<?, ?> sm
                                        ? Map.copyOf(GraphJson.object(sm))
                                        : Map.of();
                                wrapperCalls.add(new CallSite(id, document, group, language, rawPath,
                                        rawField, fields.get("content"), dir,
                                        GraphJson.string(fact.get("nodeKind")), slot, copiedSpan, ownerKey, name,
                                        written,
                                        Map.copyOf(litArgs), Map.copyOf(forwardParams), artifact.identity()));
                            }
                        }
                        sites.add(new SiteEntry(id, document, group, language, rawPath, dir, category, name, written,
                                artifact.identity().value()));
                    }
                }
                String owner = GraphJson.string(fact.get("owner"));
                String from = owner.isEmpty() ? document : declaration(document, owner);
                String edge = KnowledgeGraph.derivedIdentity("syntax-relation", List.of(document, from, id, category));
                edges.putIfAbsent(context + "|" + edge, new KnowledgeGraph.Edge(edge, context,
                        "syntax." + category.toLowerCase(Locale.ROOT).replace('_', '-'), from,
                        new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(id), Optional.empty()),
                        List.of(artifact.identity()), Optional.empty()));
                if (category.equals("RPC_HANDLER_CANDIDATE") && Set
                        .of("function_definition", "function_declaration", "method_declaration", "method_definition")
                        .contains(fact.get("nodeKind"))) {
                    String declaration = declaration(document, slot);
                    String binding = KnowledgeGraph.derivedIdentity("rpc-handler-source-declaration",
                            List.of(id, declaration));
                    edges.putIfAbsent(context + "|" + binding,
                            new KnowledgeGraph.Edge(binding, context, "syntax.rpc-handler-declaration", id,
                                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(declaration),
                                            Optional.empty()),
                                    List.of(artifact.identity()), Optional.empty()));
                }
            }
        }
        linkPolyglotSymbols(sites, byLangAndSymbol, byDocAndSymbol, byDirAndSymbol, byStemAndSymbol, docImports,
                result.jsWorkspace(), edges, evidence, context, cancelled);
        propagateCrossFileWrappers(rawWrappers, wrapperCalls, directCandidateSpans, declByDocSlot,
                byLangAndSymbol, byDocAndSymbol, byDirAndSymbol, byStemAndSymbol, docImports,
                nodes, edges, evidence, context, cancelled);
        for (var rpc : protoMethods.entrySet())
            for (String role : List.of("request", "response")) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new IOException("SYNTAX_CANCELLED");
                var attrs = GraphJson.object(rpc.getValue().get("attributes"));
                String type = GraphJson.string(attrs.get(role + "Type"));
                String qualified = GraphJson.string(attrs.get("qualifiedName"));
                int lastDot = qualified.lastIndexOf('.');
                String initialPrefix = lastDot < 0 ? "" : qualified.substring(0, lastDot);
                Map<String, Object> symbol = lookupProtoSymbol(protoSymbols,
                        GraphJson.string(rpc.getValue().get("document")) + "|", initialPrefix, type);
                if (symbol == null) {
                    // Cross-file Protobuf symbol fallback across all captured .proto files in the
                    // repository.
                    symbol = lookupProtoSymbol(protoSymbols, "", initialPrefix, type);
                }
                var targets = symbol == null ? List.<String>of()
                        : GraphJson.array(symbol.get("identities")).stream().map(GraphJson::string).toList();
                var proof = GraphEvidence.capture("protobuf.source-type-candidate",
                        Map.of("method", rpc.getKey(), "role", role, "writtenType", type, "targets", targets,
                                "sourceEvidence", rpc.getValue().get("proof"), "semanticBinding", "NOT_ESTABLISHED",
                                "generatedStubBinding", false));
                evidence.putIfAbsent(proof.identity().value(), proof);
                String id = KnowledgeGraph.derivedIdentity("protobuf-source-type-candidate",
                        List.of(rpc.getKey(), role, targets));
                var target = targets.isEmpty()
                        ? new KnowledgeGraph.Target(KnowledgeGraph.TargetState.UNRESOLVED, List.of(), Optional.of(type))
                        : new KnowledgeGraph.Target(targets.size() == 1 ? KnowledgeGraph.TargetState.RESOLVED
                                : KnowledgeGraph.TargetState.CANDIDATES, targets, Optional.empty());
                edges.put(context + "|" + id,
                        new KnowledgeGraph.Edge(id, context, "protobuf.rpc-" + role + ".candidate", rpc.getKey(),
                                target, List.of(proof.identity()), Optional.empty()));
                if (targets.isEmpty()) {
                    var provider = new VersionedIdentifier("polyglot.protobuf-source-types", "m8.4-v1");
                    var subject = new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION, proof.identity().value());
                    var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT,
                            "protobuf.native-descriptor-closure", List.of(subject),
                            EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,
                            List.of("Acquire hash-bound native descriptors/import context for this exact schema reference; generated-stub bindings remain a separate obligation."));
                    var gap = CapabilityGapRecord.create(
                            EvidenceContext.forSnapshot(
                                    new com.evolution.analysis.contract.identity.SnapshotIdentity(context)),
                            provider, "polyglot.protobuf-source", "PROTOBUF_SCHEMA_TYPE_UNRESOLVED", subject, List.of(),
                            List.of(ProviderObservationReference.create(provider, "protobuf.source-type-candidate",
                                    new ContentDigest(GraphJson.string(rpc.getValue().get("proof"))),
                                    proof.identity())),
                            List.of(requirement), List.of(),
                            List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,
                                    "polyglot.protobuf-source")),
                            List.of(), List.of(),
                            List.of("The source RPC/type spelling remains visible; no imported message or generated stub is invented."));
                    var gapEvidence = GraphEvidence.capture("evidence.gap", gap);
                    evidence.putIfAbsent(gapEvidence.identity().value(), gapEvidence);
                    node(nodes, context, gap.gapIdentity().value(), "CAPABILITY_GAP", gapEvidence.identity());
                }
            }
    }

    private static void propagateCrossFileWrappers(
            List<Map.Entry<String, WrapperSpec>> rawWrappers,
            List<CallSite> wrapperCalls,
            Set<String> directCandidateSpans,
            Map<String, DeclEntry> declByDocSlot,
            Map<String, List<DeclEntry>> byLangAndSymbol,
            Map<String, List<DeclEntry>> byDocAndSymbol,
            Map<String, List<DeclEntry>> byDirAndSymbol,
            Map<String, List<DeclEntry>> byStemAndSymbol,
            Map<String, Set<String>> docImports,
            Map<String, KnowledgeGraph.Node> nodes,
            Map<String, KnowledgeGraph.Edge> edges,
            Map<String, GraphEvidence> evidence,
            String context,
            BooleanSupplier cancelled) throws IOException {
        if (rawWrappers.isEmpty() || wrapperCalls.isEmpty())
            return;
        var wrapperByDecl = new TreeMap<String, List<WrapperSpec>>();
        for (var entry : rawWrappers) {
            var decl = declByDocSlot.get(entry.getKey());
            if (decl == null)
                continue;
            var spec = entry.getValue();
            addWrapperSpec(wrapperByDecl, decl.id(), new WrapperSpec(
                    decl.id(), decl.document(), decl.langGroup(), spec.kind(), spec.paramIndex(),
                    spec.methodOrRole(), spec.basePathOrProtocol(), 0,
                    List.of(decl.proof(), spec.chainProofs().getFirst())));
        }
        if (wrapperByDecl.isEmpty())
            return;

        // Bounded 4-hop fixpoint propagation across wrapper functions passing caller
        // parameters cleanly; each declaration retains multiple specs (e.g. HTTP +
        // Event).
        for (int hop = 1; hop < 4; hop++) {
            boolean changed = false;
            for (var site : wrapperCalls) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new IOException("SYNTAX_CANCELLED");
                if (site.ownerSlot().isEmpty() || site.forwardParams().isEmpty())
                    continue;
                var ownerDecl = declByDocSlot.get(site.document() + "|" + site.ownerSlot());
                if (ownerDecl == null)
                    continue;
                var targets = resolveWrapperCallees(site, byLangAndSymbol, byDocAndSymbol, byDirAndSymbol,
                        byStemAndSymbol, docImports);
                List<WrapperSpec> matchedSpecs = selectUnambiguousWrapperSpecs(targets, wrapperByDecl);
                if (matchedSpecs == null)
                    continue;
                for (var matchedWrapper : List.copyOf(matchedSpecs)) {
                    if (matchedWrapper.hop() >= hop)
                        continue;
                    Integer forwardedParam = site.forwardParams().get(matchedWrapper.paramIndex());
                    if (forwardedParam == null)
                        continue;
                    var proofs = new ArrayList<>(matchedWrapper.chainProofs());
                    proofs.add(site.proof().value());
                    proofs.add(ownerDecl.proof());
                    if (addWrapperSpec(wrapperByDecl, ownerDecl.id(), new WrapperSpec(
                            ownerDecl.id(), ownerDecl.document(), ownerDecl.langGroup(),
                            matchedWrapper.kind(), forwardedParam, matchedWrapper.methodOrRole(),
                            matchedWrapper.basePathOrProtocol(), matchedWrapper.hop() + 1, List.copyOf(proofs)))) {
                        changed = true;
                    }
                }
            }
            if (!changed)
                break;
        }

        // Materialize candidate nodes for calls passing static string literals into any
        // resolved wrapper.
        for (var site : wrapperCalls) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new IOException("SYNTAX_CANCELLED");
            if (directCandidateSpans.contains(site.document() + "|" + site.spanSlot()) || site.litArgs().isEmpty())
                continue;
            var targets = resolveWrapperCallees(site, byLangAndSymbol, byDocAndSymbol, byDirAndSymbol, byStemAndSymbol,
                    docImports);
            List<WrapperSpec> resolvedSpecs = selectUnambiguousWrapperSpecs(targets, wrapperByDecl);
            if (resolvedSpecs == null)
                continue;
            for (var resolved : resolvedSpecs) {
                String argValue = site.litArgs().get(resolved.paramIndex());
                if (argValue == null || argValue.indexOf('\\') >= 0)
                    continue;

                if (resolved.kind().equals("HTTP")) {
                    String combined = joinWrapperPath(resolved.basePathOrProtocol(), argValue);
                    if (combined.indexOf('/') < 0 || combined.length() > 4096)
                        continue;
                    String quoted = quoteLiteral(combined);
                    emitPropagatedCandidate(site, resolved, "HTTP_CALL_CANDIDATE", "SOURCE_HTTP_CALL_CANDIDATE",
                            "syntax.http-call-candidate", quoted, Map.of("method", resolved.methodOrRole(),
                                    "derivation", "CROSS_FILE_WRAPPER_PROPAGATION", "wrapperTarget", resolved.declId()),
                            nodes, edges, evidence, context);
                } else if (resolved.kind().equals("EVENT")) {
                    String topic = argValue.trim();
                    if (topic.isEmpty() || topic.length() > 256)
                        continue;
                    String quoted = quoteLiteral(topic);
                    boolean producer = "PRODUCER".equals(resolved.methodOrRole());
                    String cat = producer ? "EVENT_PRODUCER_CANDIDATE" : "EVENT_CONSUMER_CANDIDATE";
                    String nodeKind = producer ? "SOURCE_EVENT_PRODUCER_CANDIDATE" : "SOURCE_EVENT_CONSUMER_CANDIDATE";
                    String edgeKind = producer ? "syntax.event-producer-candidate" : "syntax.event-consumer-candidate";
                    emitPropagatedCandidate(site, resolved, cat, nodeKind, edgeKind, quoted,
                            Map.of("role", resolved.methodOrRole(), "protocol", resolved.basePathOrProtocol(),
                                    "derivation", "CROSS_FILE_WRAPPER_PROPAGATION", "wrapperTarget", resolved.declId()),
                            nodes, edges, evidence, context);
                }
            }
        }
    }

    private static boolean addWrapperSpec(Map<String, List<WrapperSpec>> wrapperByDecl, String declId,
            WrapperSpec spec) {
        var list = wrapperByDecl.computeIfAbsent(declId, k -> new ArrayList<>());
        if (list.size() >= 8)
            return false;
        for (var existing : list) {
            if (existing.kind().equals(spec.kind())
                    && existing.paramIndex() == spec.paramIndex()
                    && existing.methodOrRole().equals(spec.methodOrRole())
                    && existing.basePathOrProtocol().equals(spec.basePathOrProtocol())) {
                return false;
            }
        }
        list.add(spec);
        return true;
    }

    private static List<WrapperSpec> selectUnambiguousWrapperSpecs(
            List<DeclEntry> targets, Map<String, List<WrapperSpec>> wrapperByDecl) {
        String matchedDecl = null;
        List<WrapperSpec> matchedSpecs = null;
        for (var target : targets) {
            var candidates = wrapperByDecl.get(target.id());
            if (candidates != null && !candidates.isEmpty()) {
                if (matchedDecl != null && !matchedDecl.equals(target.id())) {
                    return null; // Ambiguous multi-wrapper call stays unpropagated.
                }
                matchedDecl = target.id();
                matchedSpecs = candidates;
            }
        }
        return matchedSpecs;
    }

    private static void addProtoSymbol(Map<String, Map<String, Object>> protoSymbols, String lookup, String id) {
        var ids = new TreeSet<String>();
        var old = protoSymbols.get(lookup);
        if (old != null)
            GraphJson.array(old.get("identities")).forEach(v -> ids.add(GraphJson.string(v)));
        ids.add(id);
        protoSymbols.put(lookup, Map.of("identities", List.copyOf(ids)));
    }

    private static Map<String, Object> lookupProtoSymbol(
            Map<String, Map<String, Object>> protoSymbols, String keyPrefix, String initialScope, String type) {
        String prefix = initialScope;
        while (true) {
            String name = type.startsWith(".") ? type.substring(1)
                    : prefix.isEmpty() ? type : prefix + "." + type;
            Map<String, Object> symbol = protoSymbols.get(keyPrefix + name);
            if (symbol != null || type.startsWith(".") || prefix.isEmpty())
                return symbol;
            int dot = prefix.lastIndexOf('.');
            prefix = dot < 0 ? "" : prefix.substring(0, dot);
        }
    }

    private static List<DeclEntry> resolveWrapperCallees(
            CallSite site,
            Map<String, List<DeclEntry>> byLangAndSymbol,
            Map<String, List<DeclEntry>> byDocAndSymbol,
            Map<String, List<DeclEntry>> byDirAndSymbol,
            Map<String, List<DeclEntry>> byStemAndSymbol,
            Map<String, Set<String>> docImports) {
        String expr = site.written().isEmpty() ? site.name() : site.written();
        int openParen = expr.indexOf('(');
        if (openParen >= 0)
            expr = expr.substring(0, openParen).trim();
        if (expr.startsWith("new "))
            expr = expr.substring(4).trim();
        int lastSep = Math.max(expr.lastIndexOf('.'), expr.lastIndexOf(':'));
        String qualifier = lastSep > 0 ? expr.substring(0, lastSep).trim() : "";
        int prevSep = Math.max(qualifier.lastIndexOf('.'), qualifier.lastIndexOf(':'));
        if (prevSep >= 0)
            qualifier = qualifier.substring(prevSep + 1).trim();
        String callee = lastSep >= 0 ? expr.substring(lastSep + 1).trim() : expr.trim();
        if (!callee.matches("[A-Za-z_][A-Za-z0-9_]*") || KEYWORDS.contains(callee))
            return List.of();

        boolean generic = GENERIC_WRAPPER_NAMES.contains(callee);
        var imported = docImports.getOrDefault(site.document(), Set.of());
        if (qualifier.isEmpty() || qualifier.equals("self") || qualifier.equals("this")) {
            var local = byDocAndSymbol.getOrDefault(site.document() + "|" + callee, List.of());
            if (!local.isEmpty())
                return local;
        }
        if (!qualifier.isEmpty()) {
            var stemMatched = byStemAndSymbol.getOrDefault(site.langGroup() + "|" + qualifier + "|" + callee,
                    List.of());
            if (!stemMatched.isEmpty())
                return stemMatched;
        }
        // Generic unqualified names (e.g. get/send/publish) must never jump across
        // files unless explicitly imported or receiver-qualified.
        boolean explicitImportMatch = imported.contains(callee)
                || (!qualifier.isEmpty() && imported.contains(qualifier));
        if (generic && !explicitImportMatch)
            return List.of();
        if (!generic && qualifier.isEmpty()) {
            var dirMatched = byDirAndSymbol.getOrDefault(site.langGroup() + "|" + site.dirPath() + "|" + callee,
                    List.of());
            if (!dirMatched.isEmpty())
                return dirMatched;
        }
        var repoCandidates = byLangAndSymbol.getOrDefault(site.langGroup() + "|" + callee, List.of());
        if (explicitImportMatch) {
            var stemFiltered = repoCandidates.stream()
                    .filter(d -> !d.fileStem().isEmpty() && imported.contains(d.fileStem())).toList();
            return stemFiltered.isEmpty() ? repoCandidates : stemFiltered;
        }
        return !generic && qualifier.isEmpty() && repoCandidates.size() == 1 ? repoCandidates : List.of();
    }

    private static String joinWrapperPath(String basePath, String path) {
        if (basePath == null || basePath.isEmpty())
            return path;
        if (path.startsWith("http://") || path.startsWith("https://"))
            return path;
        String b = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        String p = path.startsWith("/") ? path : "/" + path;
        return b + p;
    }

    private static void emitPropagatedCandidate(
            CallSite site,
            WrapperSpec resolved,
            String category,
            String nodeKind,
            String edgeKind,
            String quotedName,
            Map<String, String> attributes,
            Map<String, KnowledgeGraph.Node> nodes,
            Map<String, KnowledgeGraph.Edge> edges,
            Map<String, GraphEvidence> evidence,
            String context) {
        String id = KnowledgeGraph.derivedIdentity("wrapper-propagated-candidate",
                List.of(site.document(), site.spanSlot(), category, quotedName, resolved.declId()));
        var spanMap = new TreeMap<String, Object>();
        int colon = site.spanSlot().indexOf(':');
        int spanStart = 0, spanEnd = 0;
        if (colon > 0) {
            try {
                spanStart = Integer.parseInt(site.spanSlot().substring(0, colon));
                spanEnd = Integer.parseInt(site.spanSlot().substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        spanMap.put("start", site.spanMap().getOrDefault("start", java.math.BigDecimal.valueOf(spanStart)));
        spanMap.put("end", site.spanMap().getOrDefault("end", java.math.BigDecimal.valueOf(spanEnd)));
        spanMap.put("startLine", site.spanMap().getOrDefault("startLine", java.math.BigDecimal.ZERO));
        spanMap.put("startColumn", site.spanMap().getOrDefault("startColumn", java.math.BigDecimal.ZERO));
        spanMap.put("endLine", site.spanMap().getOrDefault("endLine", java.math.BigDecimal.ZERO));
        spanMap.put("endColumn", site.spanMap().getOrDefault("endColumn", java.math.BigDecimal.ZERO));
        var factMap = new TreeMap<String, Object>();
        factMap.put("category", category);
        factMap.put("nodeKind", site.nodeKind());
        factMap.put("name", quotedName);
        factMap.put("written", site.written());
        factMap.put("span", spanMap);
        factMap.put("owner", site.ownerSlot());
        factMap.put("recovered", false);
        factMap.put("attributes", attributes);
        var fieldsMap = new TreeMap<String, Object>();
        fieldsMap.put("rowType", "FACT");
        fieldsMap.put("document", site.document());
        fieldsMap.put("language", site.language());
        fieldsMap.put("path", site.rawPathField() == null ? "" : site.rawPathField());
        if (site.contentHandle() != null) {
            fieldsMap.put("content", site.contentHandle());
        }
        fieldsMap.put("provider", TreeSitterSourceFrontend.provider());
        fieldsMap.put("semanticBinding", "NOT_ESTABLISHED");
        fieldsMap.put("derivation", "CROSS_FILE_WRAPPER_PROPAGATION");
        fieldsMap.put("runtimeObserved", false);
        fieldsMap.put("wrapperTarget", resolved.declId());
        fieldsMap.put("wrapperHops", java.math.BigDecimal.valueOf(resolved.hop() + 1L));
        fieldsMap.put("wrapperProofs", resolved.chainProofs());
        fieldsMap.put("fact", factMap);
        var proof = GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", fieldsMap));
        evidence.putIfAbsent(proof.identity().value(), proof);
        node(nodes, context, id, nodeKind, proof.identity());
        String from = site.ownerSlot().isEmpty() ? site.document() : declaration(site.document(), site.ownerSlot());
        String relEdgeId = KnowledgeGraph.derivedIdentity("syntax-relation",
                List.of(site.document(), from, id, category));
        edges.putIfAbsent(context + "|" + relEdgeId, new KnowledgeGraph.Edge(relEdgeId, context, edgeKind, from,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(id), Optional.empty()),
                List.of(proof.identity()), Optional.empty()));
    }

    private static void linkPolyglotSymbols(List<SiteEntry> sites, Map<String, List<DeclEntry>> byLangAndSymbol,
            Map<String, List<DeclEntry>> byDocAndSymbol,
            Map<String, List<DeclEntry>> byDirAndSymbol, Map<String, List<DeclEntry>> byStemAndSymbol,
            Map<String, Set<String>> docImports,
            JsWorkspaceAliasResolver.WorkspaceModel jsWorkspace,
            Map<String, KnowledgeGraph.Edge> edges, Map<String, GraphEvidence> evidence, String context,
            BooleanSupplier cancelled) throws IOException {
        Set<String> knownPaths = new LinkedHashSet<>();
        for (var list : byLangAndSymbol.values()) {
            for (var d : list) {
                if (d.normPath() != null && !d.normPath().isEmpty()) {
                    knownPaths.add(d.normPath());
                }
            }
        }
        for (var site : sites) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new IOException("SYNTAX_CANCELLED");
            var matchedIds = new TreeSet<String>();
            if (site.category().equals("IMPORT")) {
                String modulePrefix = resolveImportModulePrefix(site, jsWorkspace, knownPaths);
                var siteTokens = tokens(site.name() + " " + site.written());
                for (String tok : siteTokens) {
                    if (KEYWORDS.contains(tok))
                        continue;
                    var candidates = byLangAndSymbol.getOrDefault(site.langGroup() + "|" + tok, List.of());
                    var external = candidates.stream().filter(d -> !d.document().equals(site.document())).toList();
                    if (external.size() == 1)
                        matchedIds.add(external.getFirst().id());
                    else if (external.size() > 1) {
                        var moduleMatched = modulePrefix.isEmpty() ? List.<DeclEntry>of()
                                : external.stream().filter(d -> matchesModulePrefix(d.normPath(), modulePrefix))
                                        .toList();
                        if (!moduleMatched.isEmpty()) {
                            for (var d : moduleMatched)
                                matchedIds.add(d.id());
                        } else {
                            var pathFiltered = external.stream()
                                    .filter(d -> !d.fileStem().isEmpty() && siteTokens.contains(d.fileStem())).toList();
                            for (var d : (pathFiltered.isEmpty() ? external : pathFiltered))
                                matchedIds.add(d.id());
                        }
                    }
                }
            } else if (site.category().equals("CALL")) {
                String expr = site.written().isEmpty() ? site.name() : site.written();
                int openParen = expr.indexOf('(');
                if (openParen >= 0)
                    expr = expr.substring(0, openParen).trim();
                if (expr.startsWith("new "))
                    expr = expr.substring(4).trim();
                int lastDot = Math.max(expr.lastIndexOf('.'), expr.lastIndexOf(':'));
                String qualifier = lastDot > 0 ? expr.substring(0, lastDot).trim() : "";
                int prevDot = Math.max(qualifier.lastIndexOf('.'), qualifier.lastIndexOf(':'));
                if (prevDot >= 0)
                    qualifier = qualifier.substring(prevDot + 1).trim();
                String callee = lastDot >= 0 ? expr.substring(lastDot + 1).trim() : expr.trim();
                if (callee.matches("[A-Za-z_][A-Za-z0-9_]*") && !KEYWORDS.contains(callee)) {
                    if (qualifier.isEmpty() || qualifier.equals("self") || qualifier.equals("this")) {
                        for (var d : byDocAndSymbol.getOrDefault(site.document() + "|" + callee, List.of()))
                            matchedIds.add(d.id());
                    }
                    if (matchedIds.isEmpty() && !qualifier.isEmpty()) {
                        for (var d : byStemAndSymbol.getOrDefault(site.langGroup() + "|" + qualifier + "|" + callee,
                                List.of()))
                            matchedIds.add(d.id());
                    }
                    if (matchedIds.isEmpty()) {
                        for (var d : byDirAndSymbol.getOrDefault(site.langGroup() + "|" + site.dirPath() + "|" + callee,
                                List.of()))
                            matchedIds.add(d.id());
                    }
                    if (matchedIds.isEmpty()) {
                        var imported = docImports.getOrDefault(site.document(), Set.of());
                        var repoCandidates = byLangAndSymbol.getOrDefault(site.langGroup() + "|" + callee, List.of());
                        if (imported.contains(callee) || (!qualifier.isEmpty() && imported.contains(qualifier))) {
                            var stemFiltered = repoCandidates.stream()
                                    .filter(d -> !d.fileStem().isEmpty() && imported.contains(d.fileStem())).toList();
                            for (var d : (stemFiltered.isEmpty() ? repoCandidates : stemFiltered))
                                matchedIds.add(d.id());
                        } else if (repoCandidates.size() == 1) {
                            matchedIds.add(repoCandidates.getFirst().id());
                        }
                    }
                }
            } else if (site.category().equals("HERITAGE")) {
                for (String tok : tokens(site.name() + " " + site.written())) {
                    if (KEYWORDS.contains(tok))
                        continue;
                    var local = byDocAndSymbol.getOrDefault(site.document() + "|" + tok, List.of());
                    if (!local.isEmpty()) {
                        for (var d : local)
                            matchedIds.add(d.id());
                        continue;
                    }
                    var dirLocal = byDirAndSymbol.getOrDefault(site.langGroup() + "|" + site.dirPath() + "|" + tok,
                            List.of());
                    if (!dirLocal.isEmpty()) {
                        for (var d : dirLocal)
                            matchedIds.add(d.id());
                        continue;
                    }
                    for (var d : byLangAndSymbol.getOrDefault(site.langGroup() + "|" + tok, List.of()))
                        matchedIds.add(d.id());
                }
            }
            if (!matchedIds.isEmpty()) {
                var targets = List.copyOf(matchedIds);
                String edgeKind = switch (site.category()) {
                    case "IMPORT" -> "syntax.imports-declaration";
                    case "CALL" -> "syntax.calls-declaration";
                    default -> "syntax.inherits-declaration";
                };
                var proof = GraphEvidence.capture("polyglot.static-symbol-link",
                        Map.of("site", site.siteId(), "category", site.category(), "language", site.language(),
                                "written", site.written(), "targets", targets, "sourceEvidence", site.proof(), "trust",
                                "STATIC_SYNTAX_DERIVED", "runtimeObserved", false));
                evidence.putIfAbsent(proof.identity().value(), proof);
                String edgeId = KnowledgeGraph.derivedIdentity("polyglot-static-symbol-link",
                        List.of(site.siteId(), edgeKind, targets));
                var targetState = targets.size() == 1 ? KnowledgeGraph.TargetState.RESOLVED
                        : KnowledgeGraph.TargetState.CANDIDATES;
                edges.putIfAbsent(context + "|" + edgeId,
                        new KnowledgeGraph.Edge(edgeId, context, edgeKind, site.siteId(),
                                new KnowledgeGraph.Target(targetState, targets, Optional.empty()),
                                List.of(proof.identity()), Optional.empty()));
            }
        }
    }

    private static boolean isJsWorkspaceConfig(String path) {
        if (path == null || path.isEmpty())
            return false;
        String norm = path.replace('\\', '/');
        int slash = norm.lastIndexOf('/');
        String base = (slash < 0 ? norm : norm.substring(slash + 1)).toLowerCase(Locale.ROOT);
        return ((base.startsWith("tsconfig") || base.startsWith("jsconfig")) && base.endsWith(".json"))
                || base.equals("package.json")
                || base.equals("pnpm-workspace.yaml")
                || base.equals("pnpm-workspace.yml");
    }

    private static String stripJsExtension(String path) {
        if (path == null)
            return "";
        for (String ext : List.of(".tsx", ".ts", ".jsx", ".js", ".mjs", ".cjs", ".vue", ".svelte")) {
            if (path.endsWith(ext) && path.length() > ext.length()) {
                return path.substring(0, path.length() - ext.length());
            }
        }
        return path;
    }

    private static String resolveImportModulePrefix(
            SiteEntry site,
            JsWorkspaceAliasResolver.WorkspaceModel jsWorkspace,
            Set<String> knownPaths) {
        String written = site.written();
        if (written == null || written.isEmpty())
            return "";
        int q1 = Math.max(written.indexOf('\''), written.indexOf('"'));
        if (q1 >= 0) {
            char quote = written.charAt(q1);
            int q2 = written.indexOf(quote, q1 + 1);
            if (q2 > q1 + 1) {
                String spec = written.substring(q1 + 1, q2).trim();
                if (spec.startsWith("./") || spec.startsWith("../")) {
                    return normalizeRelativePath(site.dirPath(), spec);
                }
                if (jsWorkspace != null
                        && (!jsWorkspace.aliases().isEmpty()
                                || !jsWorkspace.packageImports().isEmpty()
                                || !jsWorkspace.packageExports().isEmpty())) {
                    var resolved = jsWorkspace.resolveImport(site.normPath(), spec, knownPaths);
                    if (resolved.isPresent()) {
                        return stripJsExtension(resolved.orElseThrow());
                    }
                }
                if (spec.startsWith("@/") || spec.startsWith("~/")) {
                    return spec.substring(2);
                }
            }
        }
        if ("python".equals(site.langGroup())) {
            var m = PYTHON_FROM_IMPORT.matcher(written.trim());
            if (m.find()) {
                return m.group(1).replace('.', '/');
            }
        }
        return "";
    }

    private static String normalizeRelativePath(String baseDir, String relative) {
        var parts = new ArrayDeque<String>();
        if (baseDir != null && !baseDir.isEmpty()) {
            for (String p : baseDir.split("/"))
                if (!p.isEmpty() && !p.equals("."))
                    parts.addLast(p);
        }
        for (String seg : relative.split("/")) {
            if (seg.isEmpty() || seg.equals("."))
                continue;
            if (seg.equals("..")) {
                if (!parts.isEmpty())
                    parts.removeLast();
            } else
                parts.addLast(seg);
        }
        return String.join("/", parts);
    }

    private static boolean matchesModulePrefix(String normPath, String prefix) {
        if (normPath == null || normPath.isEmpty() || prefix.isEmpty())
            return false;
        String clean = normPath.replaceFirst("\\.[^./]+$", "");
        return clean.equals(prefix) || clean.endsWith("/" + prefix)
                || clean.equals(prefix + "/index") || clean.endsWith("/" + prefix + "/index")
                || clean.equals(prefix + "/__init__") || clean.endsWith("/" + prefix + "/__init__");
    }

    private static String decodePath(String raw) {
        if (raw == null || raw.isEmpty())
            return "";
        try {
            return new LogicalPath(raw).utf8().orElse(raw);
        } catch (IllegalArgumentException plain) {
            return raw;
        }
    }

    private static String langGroup(String language) {
        return Set.of("javascript", "typescript", "tsx", "vue", "svelte").contains(language) ? "js-ts" : language;
    }

    private static String declSymbol(String name, String written) {
        String candidate = name == null ? "" : name.trim();
        if (candidate.matches("[A-Za-z_][A-Za-z0-9_]*"))
            return candidate;
        for (String tok : tokens(written))
            if (!KEYWORDS.contains(tok))
                return tok;
        return "";
    }

    private static List<String> tokens(String text) {
        if (text == null || text.isEmpty())
            return List.of();
        var list = new ArrayList<String>();
        int start = -1;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9' && start >= 0) || c == '_') {
                if (start < 0)
                    start = i;
            } else if (start >= 0) {
                list.add(text.substring(start, i));
                start = -1;
            }
        }
        return list;
    }

    private static void addDecl(Map<String, List<DeclEntry>> index, String key, DeclEntry entry) {
        var list = index.computeIfAbsent(key, k -> new ArrayList<>());
        if (list.size() < 64)
            list.add(entry);
    }

    private static String declaration(String document, String slot) {
        return KnowledgeGraph.derivedIdentity("syntax-declaration", List.of(document, slot));
    }

    private static void node(Map<String, KnowledgeGraph.Node> nodes, String context, String id, String kind,
            ContentDigest support) {
        var old = nodes.get(context + "|" + id);
        if (old != null && !old.kind().equals(kind))
            throw new IllegalArgumentException("SYNTAX_NODE_CONFLICT");
        var supports = new TreeSet<ContentDigest>();
        if (old != null)
            supports.addAll(old.evidence());
        supports.add(support);
        nodes.put(context + "|" + id, new KnowledgeGraph.Node(id, context, kind, List.copyOf(supports)));
    }
}
