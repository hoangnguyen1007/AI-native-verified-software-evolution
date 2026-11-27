package com.evolution.backend.query;

import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.GraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.backend.analysis.AnalysisJobDiskStore;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.query.mcp.McpToolDispatcher;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Read-only MCP stdio server supporting both:
 * <ul>
 *   <li><b>Loopback Workbench HTTP proxy mode</b> ({@code --port <N>} with optional {@code --auth-token-env}
 *       for multi-user deployments)</li>
 *   <li><b>Direct Local Store mode</b> ({@code --store <dir>}, 100% offline, zero daemon, auto-binding
 *       the latest published graph &amp; largest populated analysis context, loading persisted jobs &amp; reports)</li>
 * </ul>
 * Delegates all 21 read-only architecture tools and 6 {@code evo://} MCP Resources
 * ({@code evo://score-v2}, {@code evo://report}, {@code evo://gaps}, {@code evo://hotspots},
 * {@code evo://cycles}, {@code evo://spring-summary}) to {@link McpToolDispatcher}.
 */
public final class WorkbenchMcpMain implements AutoCloseable {
    private static final int MAX_MESSAGE = 65_536;
    private static final int MAX_RESPONSE = McpToolDispatcher.MAX_RESPONSE_BYTES;
    private static final int MAX_CAS_READ_BYTES = 16 * 1024 * 1024;
    private static final GraphJson.Limits JSON = new GraphJson.Limits(MAX_MESSAGE, 32, 10_000);
    private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

    private final URI origin;
    private final ArchitectureQueryApi directApi;
    private final List<AutoCloseable> ownedResources;
    private final String authBearerToken;
    private final String authCookie;
    private final String csrfToken;
    private final String configuredDefaultGraph;
    private final AtomicReference<String> resolvedDefaultGraph;
    private final AtomicReference<String> resolvedDefaultContext;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final McpToolDispatcher.ApiBackend backend;

    public WorkbenchMcpMain(int port) {
        this(port, null, null, null, null, null);
    }

    public WorkbenchMcpMain(int port, String authBearerToken, String authCookie, String csrfToken,
                            String defaultGraph, String defaultContext) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid Workbench port");
        this.origin = URI.create("http://127.0.0.1:" + port);
        this.directApi = null;
        this.ownedResources = List.of();
        this.authBearerToken = normalizeSecret(authBearerToken);
        this.authCookie = normalizeSecret(authCookie);
        this.csrfToken = normalizeSecret(csrfToken);
        this.configuredDefaultGraph = normalizeOpt(defaultGraph);
        this.resolvedDefaultGraph = new AtomicReference<>(this.configuredDefaultGraph);
        this.resolvedDefaultContext = new AtomicReference<>(normalizeOpt(defaultContext));
        this.backend = this::dispatchHttpProxy;
    }

    /** Direct offline constructor backed by an in-process {@link ArchitectureQueryApi} (zero daemon). */
    public WorkbenchMcpMain(ArchitectureQueryApi directApi, String defaultGraph, String defaultContext) {
        this(directApi, defaultGraph, defaultContext, List.of());
    }

    private WorkbenchMcpMain(ArchitectureQueryApi directApi, String defaultGraph, String defaultContext,
                             List<AutoCloseable> ownedResources) {
        this.origin = null;
        this.directApi = Objects.requireNonNull(directApi, "directApi");
        this.ownedResources = List.copyOf(ownedResources == null ? List.of() : ownedResources);
        this.authBearerToken = null;
        this.authCookie = null;
        this.csrfToken = null;
        this.configuredDefaultGraph = normalizeOpt(defaultGraph);
        this.resolvedDefaultGraph = new AtomicReference<>(this.configuredDefaultGraph);
        this.resolvedDefaultContext = new AtomicReference<>(normalizeOpt(defaultContext));
        this.backend = McpToolDispatcher.directBackend(this.directApi, null, "http://127.0.0.1", () -> false);
    }

    @Override
    public void close() {
        for (int i = ownedResources.size() - 1; i >= 0; i--) {
            try {
                ownedResources.get(i).close();
            } catch (Exception ignored) {}
        }
    }

    /** Opens a Direct Local Store MCP server reading graphs, persisted jobs, and saved reports directly from {@code storeDir}. */
    public static WorkbenchMcpMain openDirectStore(Path storeDir, String preferredGraph, String preferredContext) throws IOException {
        Path root = Objects.requireNonNull(storeDir, "storeDir").toAbsolutePath().normalize();
        Path subGraphs = root.resolve("graphs");
        Path graphsDir = (Files.isDirectory(subGraphs) && (McpToolDispatcher.discoverLatestGraphDigest(subGraphs).isPresent()
                || McpToolDispatcher.discoverLatestGraphDigest(root).isEmpty())) ? subGraphs : root;
        Path storeRoot = graphsDir.equals(subGraphs) ? root : (root.getParent() != null
                && (Files.isDirectory(root.getParent().resolve("jobs")) || Files.isDirectory(root.getParent().resolve("reports")))
                ? root.getParent() : root);
        Path contentRoot = storeRoot.resolve("acquisition/content");
        if (!Files.isDirectory(contentRoot, LinkOption.NOFOLLOW_LINKS) && root.getParent() != null) {
            Path siblingContent = root.getParent().resolve("acquisition/content");
            if (Files.isDirectory(siblingContent, LinkOption.NOFOLLOW_LINKS)) {
                contentRoot = siblingContent;
            }
        }
        final Path resolvedContentRoot = contentRoot;
        var contentStoreRef = new java.util.concurrent.atomic.AtomicReference<com.evolution.analysis.filesystem.StreamingContentStore>();
        RepairSource.registerContentFallback((digest, expectedSize) -> {
            if (expectedSize.isEmpty() || expectedSize.getAsLong() < 0
                    || expectedSize.getAsLong() > MAX_CAS_READ_BYTES
                    || !Files.isDirectory(resolvedContentRoot, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            try {
                var cas = contentStoreRef.get();
                if (cas == null) {
                    var created = new com.evolution.analysis.filesystem.StreamingContentStore(resolvedContentRoot);
                    if (!contentStoreRef.compareAndSet(null, created)) {
                        cas = contentStoreRef.get();
                    } else {
                        cas = created;
                    }
                }
                try (var in = cas.open(new com.evolution.analysis.acquisition.streaming.ContentHandle(
                        digest, expectedSize.getAsLong()))) {
                    byte[] data = in.readNBytes(MAX_CAS_READ_BYTES + 1);
                    if (data.length > MAX_CAS_READ_BYTES) {
                        return Optional.empty();
                    }
                    return Optional.of(data);
                }
            } catch (IOException | IllegalArgumentException unavailable) {
                return Optional.empty();
            }
        });
        long bytes = Long.MAX_VALUE - 1;
        var streamLimits = new GraphJson.StreamLimits(bytes, 128, bytes, Integer.MAX_VALUE - 8);
        GraphStore store = new FileGraphStore(graphsDir, bytes, streamLimits);
        var owned = new ArrayList<AutoCloseable>();

        // Load persisted reports read-only (non-exclusive) if index exists so offline MCP works concurrently with WorkbenchApiMain
        PublishedReports reports;
        Path reportsDir = storeRoot.resolve("reports");
        if (Files.isRegularFile(reportsDir.resolve("metadata/index.sqlite"), LinkOption.NOFOLLOW_LINKS)) {
            var reportDisk = new PersistentReports(reportsDir, true);
            owned.add(reportDisk);
            reports = new PublishedReports(reportDisk);
        } else {
            reports = new PublishedReports();
        }

        // Load persisted analysis jobs read-only (non-exclusive) if index exists so jobs_list & job_coverage work concurrently
        ArchitectureQueryApi baseApi;
        Path jobsDir = storeRoot.resolve("jobs");
        if (Files.isRegularFile(jobsDir.resolve("metadata/index.sqlite"), LinkOption.NOFOLLOW_LINKS)) {
            var jobDisk = new AnalysisJobDiskStore(jobsDir, true);
            owned.add(jobDisk);
            var analysisJobs = new AnalysisJobs(List.of(), store, reports, AnalysisJobs.Limits.defaults(), jobDisk);
            owned.add(analysisJobs);
            baseApi = new ArchitectureQueryApi(store, reports, ApiLimits.defaults(), analysisJobs);
        } else {
            baseApi = new ArchitectureQueryApi(store, reports, ApiLimits.defaults());
        }

        Path repairsDir = storeRoot.resolve("repairs");
        if (Files.isDirectory(repairsDir, LinkOption.NOFOLLOW_LINKS)) {
            baseApi = ArchitectureQueryApi.withRepairStore(baseApi, new RepairProposalStore(repairsDir));
        }
        var api = ArchitectureQueryApi.withStoreDirectory(
                ArchitectureQueryApi.withAcquisitionContentDir(baseApi, resolvedContentRoot),
                storeRoot);
        var roSearchOpt = com.evolution.backend.query.search.CapturedSearchIndex
                .openReadOnlyIfPresent(storeRoot.resolve("search").resolve("fts5.db"));
        if (roSearchOpt.isPresent()) {
            var roSearch = roSearchOpt.orElseThrow();
            owned.add(roSearch);
            api = ArchitectureQueryApi.withSearchIndex(api, roSearch);
        }

        String defaultGraph = normalizeOpt(preferredGraph);
        String defaultContext = normalizeOpt(preferredContext);
        if (defaultGraph == null) {
            defaultGraph = McpToolDispatcher.discoverLatestGraphDigest(graphsDir).orElse(null);
        }
        if (defaultGraph != null && defaultContext == null) {
            try {
                Optional<KnowledgeGraph> loaded = store.load(new ContentDigest(defaultGraph));
                if (loaded.isPresent()) {
                    var g = loaded.orElseThrow();
                    defaultContext = selectLargestAnalysisContext(g);
                }
            } catch (RuntimeException ignored) {}
        }
        return new WorkbenchMcpMain(api, defaultGraph, defaultContext, owned);
    }

    /** Selects the largest populated analysis context deterministically instead of arbitrary .findFirst(). */
    static String selectLargestAnalysisContext(KnowledgeGraph g) {
        return g.contexts().stream()
                .filter(c -> c.analysis().isPresent())
                .max(Comparator.<KnowledgeGraph.Context>comparingInt(c -> g.nodes(c.identity()).size())
                        .thenComparing(Comparator.comparing(KnowledgeGraph.Context::identity).reversed()))
                .map(KnowledgeGraph.Context::identity)
                .orElse(g.snapshot().value());
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].matches("\\d+")) {
            runLoop(new WorkbenchMcpMain(Integer.parseInt(args[0])));
            return;
        }
        Integer port = null;
        Path storeDir = null;
        String authTokenEnv = null;
        String authCookieEnv = null;
        String csrfEnv = null;
        String defaultGraph = null;
        String defaultContext = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(requireArg(args, ++i, "--port"));
                case "--store" -> storeDir = Path.of(requireArg(args, ++i, "--store"));
                case "--auth-token-env" -> authTokenEnv = requireArg(args, ++i, "--auth-token-env");
                case "--auth-cookie-env" -> authCookieEnv = requireArg(args, ++i, "--auth-cookie-env");
                case "--csrf-token-env" -> csrfEnv = requireArg(args, ++i, "--csrf-token-env");
                case "--graph" -> defaultGraph = requireArg(args, ++i, "--graph");
                case "--context" -> defaultContext = requireArg(args, ++i, "--context");
                default -> throw new IllegalArgumentException(
                        "Usage: WorkbenchMcpMain <port> | [--port <N> [--auth-token-env <ENV>]] | [--store <dir>] [--graph <sha256:...>] [--context <id>]");
            }
        }

        try (WorkbenchMcpMain server = storeDir != null
                ? openDirectStore(storeDir, defaultGraph, defaultContext)
                : (port != null
                        ? new WorkbenchMcpMain(port,
                                authTokenEnv == null ? null : System.getenv(authTokenEnv),
                                authCookieEnv == null ? null : System.getenv(authCookieEnv),
                                csrfEnv == null ? null : System.getenv(csrfEnv),
                                defaultGraph, defaultContext)
                        : null)) {
            if (server == null) {
                throw new IllegalArgumentException("Specify either --port <N> or --store <dir>");
            }
            runLoop(server);
        }
    }

    private static void runLoop(WorkbenchMcpMain server) throws IOException {
        try (var input = new InputStreamReader(System.in, StandardCharsets.UTF_8);
             var output = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)) {
            server.run(input, output);
        }
    }

    public void run(Reader input, PrintWriter output) throws IOException {
        var line = new StringBuilder();
        int character;
        while ((character = input.read()) != -1) {
            if (character == '\n') {
                String response = handle(line.toString());
                if (response != null) output.println(response);
                line.setLength(0);
            } else {
                if (line.length() >= MAX_MESSAGE) throw new IOException("MCP message exceeds limit");
                line.append((char) character);
            }
        }
    }

    public String handleMessage(String input) {
        return handle(input);
    }

    public List<Map<String, Object>> listTools() {
        return McpToolDispatcher.listToolsView(configuredDefaultGraph != null);
    }

    public Map<String, Object> callTool(Map<String, Object> params) {
        return McpToolDispatcher.callTool(params, backend, resolvedDefaultGraph, resolvedDefaultContext);
    }

    public Map<String, Object> readResource(Map<String, Object> params) {
        return McpToolDispatcher.readResource(params, backend, resolvedDefaultGraph, resolvedDefaultContext);
    }

    String handle(String input) {
        Object id = null;
        try {
            var request = GraphJson.object(GraphJson.read(input, JSON));
            id = request.get("id");
            var dispatch = McpToolDispatcher.handleJsonRpc(
                    request,
                    backend,
                    resolvedDefaultGraph,
                    resolvedDefaultContext,
                    true,
                    initialized,
                    configuredDefaultGraph != null);
            if (dispatch.responseBody() == null) {
                return null;
            }
            return CanonicalJson.write(dispatch.responseBody());
        } catch (IllegalArgumentException invalid) {
            return CanonicalJson.write(McpToolDispatcher.errorMap(id, -32602, "Invalid request or tool arguments"));
        }
    }

    private McpToolDispatcher.BackendResponse dispatchHttpProxy(
            String method, String pathWithQuery, Map<String, Object> payload)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(origin.resolve(pathWithQuery))
                .timeout(Duration.ofSeconds(120))
                .header("Accept", "application/json");
        if (authBearerToken != null) {
            builder.header("Authorization", "Bearer " + authBearerToken);
        } else if (authCookie != null) {
            String cookieHeader = authCookie.contains("=") ? authCookie : ("evo_session=" + authCookie);
            builder.header("Cookie", cookieHeader);
            if (csrfToken != null && !"GET".equals(method)) {
                builder.header("X-Evo-CSRF", csrfToken);
            }
        }
        if (payload == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(payload), StandardCharsets.UTF_8));
        }
        var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var body = response.body()) {
            bytes = body.readNBytes(MAX_RESPONSE + 1);
        }
        if (bytes.length > MAX_RESPONSE) {
            return new McpToolDispatcher.BackendResponse(422, "Workbench response exceeds MCP limit.");
        }
        String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return new McpToolDispatcher.BackendResponse(response.statusCode(), content);
    }

    private static String requireArg(String[] args, int idx, String flag) {
        if (idx >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
        return args[idx];
    }

    private static String normalizeSecret(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.strip();
        if (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) throw new IllegalArgumentException("Invalid auth token");
        return s;
    }

    private static String normalizeOpt(String raw) {
        return (raw == null || raw.isBlank()) ? null : raw.strip();
    }
}
