package com.evolution.backend.analysis;

import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.evolution.EvolutionSnapshot;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.analysis.filesystem.PartitionedGraphStore;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.backend.query.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.io.IOException;

/**
 * Loopback composition root with operator-enabled passive local/public-Git
 * connections.
 */
public final class WorkbenchApiMain {
    static final int MAX_CAS_READ_BYTES = 16 * 1024 * 1024;

    static Optional<byte[]> readBoundedCasContent(
            Path contentRoot,
            java.util.concurrent.atomic.AtomicReference<com.evolution.analysis.filesystem.StreamingContentStore> contentStoreRef,
            ContentDigest digest,
            OptionalLong expectedSize) {
        if (expectedSize.isEmpty() || expectedSize.getAsLong() < 0
                || expectedSize.getAsLong() > MAX_CAS_READ_BYTES
                || !Files.isDirectory(contentRoot, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            var cas = contentStoreRef.get();
            if (cas == null) {
                var created = new com.evolution.analysis.filesystem.StreamingContentStore(contentRoot);
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
    }

    public record LaunchOptions(
            String source,
            String coordinate,
            String jdk,
            int release,
            String mavenCache,
            String graphDirectory,
            String bindHost,
            int port,
            Optional<String> policyPath,
            boolean onlineMode) {
    }

    public static LaunchOptions parseLaunchOptions(
            String[] args,
            java.util.function.Function<String, String> env,
            java.util.function.Function<String, String> props) {
        String hostFlag = null;
        String portFlag = null;
        String policyFlag = null;
        String stateDirFlag = null;
        String jdkFlag = null;
        String releaseFlag = null;
        String mavenCacheFlag = null;
        boolean onlineFlag = false;
        var positional = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--online".equals(arg)) {
                onlineFlag = true;
            } else if ("--host".equals(arg)) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for --host");
                hostFlag = args[++i];
            } else if (arg.startsWith("--host=")) {
                hostFlag = arg.substring("--host=".length());
            } else if ("--port".equals(arg)) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for --port");
                portFlag = args[++i];
            } else if (arg.startsWith("--port=")) {
                portFlag = arg.substring("--port=".length());
            } else if ("--policy".equals(arg)) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for --policy");
                policyFlag = args[++i];
            } else if (arg.startsWith("--policy=")) {
                policyFlag = arg.substring("--policy=".length());
            } else if ("--state-dir".equals(arg)) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for --state-dir");
                stateDirFlag = args[++i];
            } else if (arg.startsWith("--state-dir=")) {
                stateDirFlag = arg.substring("--state-dir=".length());
            } else if ("--jdk".equals(arg)) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for --jdk");
                jdkFlag = args[++i];
            } else if (arg.startsWith("--jdk=")) {
                jdkFlag = arg.substring("--jdk=".length());
            } else if ("--release".equals(arg)) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for --release");
                releaseFlag = args[++i];
            } else if (arg.startsWith("--release=")) {
                releaseFlag = arg.substring("--release=".length());
            } else if ("--maven-cache".equals(arg)) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for --maven-cache");
                mavenCacheFlag = args[++i];
            } else if (arg.startsWith("--maven-cache=")) {
                mavenCacheFlag = arg.substring("--maven-cache=".length());
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("Unknown option: " + arg);
            } else {
                positional.add(arg);
            }
        }

        Optional<String> policyPath = Optional.empty();
        if (policyFlag != null && !policyFlag.isBlank()) {
            policyPath = Optional.of(policyFlag.strip());
        } else if (!onlineFlag && positional.size() == 8) {
            policyPath = Optional.of(positional.get(7));
        } else {
            String envPolicy = firstNonBlank(props.apply("evo.workbench.policy"), env.apply("EVO_POLICY_FILE"));
            if (envPolicy != null) {
                policyPath = Optional.of(envPolicy);
            }
        }

        String envOnlineRaw = firstNonBlank(props.apply("evo.workbench.onlineMode"), env.apply("EVO_ONLINE_MODE"));
        boolean envOnline = envOnlineRaw != null && ("true".equalsIgnoreCase(envOnlineRaw) || "1".equals(envOnlineRaw));
        boolean onlineMode = onlineFlag || envOnline;

        if (onlineMode) {
            if (!positional.isEmpty()) {
                throw new IllegalArgumentException("Online mode does not accept positional local source arguments");
            }
            String stateDir = firstNonBlank(
                    stateDirFlag,
                    props.apply("evo.workbench.stateDir"),
                    env.apply("EVO_STATE_DIR"));
            if (stateDir == null) {
                stateDir = ".evo-online";
            }
            String mavenCache = firstNonBlank(
                    mavenCacheFlag,
                    props.apply("evo.workbench.mavenCache"),
                    env.apply("EVO_M2_REPOSITORY"));
            if (mavenCache == null) {
                mavenCache = Path.of(stateDir, "m2-cache").toString();
            }
            String jdk = firstNonBlank(
                    jdkFlag,
                    props.apply("evo.workbench.jdk"),
                    env.apply("EVO_JDK"),
                    env.apply("JAVA_HOME"),
                    props.apply("java.home"));
            if (jdk == null) {
                throw new IllegalArgumentException("JDK path required");
            }
            String relSource = firstNonBlank(
                    releaseFlag,
                    props.apply("evo.workbench.release"),
                    env.apply("EVO_JAVA_RELEASE"));
            int release = relSource == null ? 21 : Integer.parseInt(relSource);

            String resolvedHost = firstNonBlank(
                    hostFlag,
                    props.apply("evo.http.bindHost"),
                    env.apply("EVO_BIND_HOST"));
            if (resolvedHost == null) {
                resolvedHost = "0.0.0.0";
            }
            String portSource = firstNonBlank(
                    portFlag,
                    props.apply("evo.http.port"),
                    env.apply("EVO_PORT"),
                    env.apply("PORT"));
            int resolvedPort = portSource == null ? 8080 : Integer.parseInt(portSource);
            if (resolvedPort < 0 || resolvedPort > 65535) {
                throw new IllegalArgumentException("Invalid port: " + resolvedPort);
            }

            return new LaunchOptions(
                    null,
                    null,
                    jdk,
                    release,
                    mavenCache,
                    stateDir,
                    resolvedHost,
                    resolvedPort,
                    policyPath,
                    true);
        }

        if (positional.size() < 6 || positional.size() > 8) {
            throw new IllegalArgumentException("Expected 6..8 positional arguments or --online");
        }
        int release = Integer.parseInt(positional.get(3));
        String resolvedHost = (hostFlag != null && !hostFlag.isBlank())
                ? hostFlag.strip()
                : ArchitectureHttpServer.bindHostFromEnvironment(env, props);

        String portSource = null;
        if (portFlag != null && !portFlag.isBlank()) {
            if (positional.size() >= 7) {
                throw new IllegalArgumentException("Specify port via --port or positional argument, not both");
            }
            portSource = portFlag.strip();
        } else if (positional.size() >= 7) {
            portSource = positional.get(6).strip();
        } else {
            portSource = firstNonBlank(
                    props.apply("evo.http.port"),
                    env.apply("EVO_PORT"),
                    env.apply("PORT"));
        }
        int resolvedPort = portSource == null ? 8080 : Integer.parseInt(portSource);
        if (resolvedPort < 0 || resolvedPort > 65535) {
            throw new IllegalArgumentException("Invalid port: " + resolvedPort);
        }

        return new LaunchOptions(
                positional.get(0),
                positional.get(1),
                positional.get(2),
                release,
                positional.get(4),
                positional.get(5),
                resolvedHost,
                resolvedPort,
                policyPath,
                false);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    public static void main(String[] args) {
        LaunchOptions launch;
        try {
            launch = parseLaunchOptions(args, System::getenv, System::getProperty);
        } catch (IllegalArgumentException invalid) {
            System.err.println(
                    "Usage: WorkbenchApiMain --online [--host <bind-host>] [--port <port>] [--state-dir <dir>] [--jdk <dir>] [--release <n>] [--maven-cache <dir>] [--policy <file>]\n"
                            + "   or: WorkbenchApiMain [--host <bind-host>] [--port <port>] <source> <repository-coordinate> <jdk> <release> <maven-cache> <existing-graph-directory> [port] [policy.yaml]");
            System.exit(2);
            return;
        }
        try {
            if (launch.onlineMode()) {
                Files.createDirectories(Path.of(launch.graphDirectory()));
                Files.createDirectories(Path.of(launch.mavenCache()));
            }
            var jdk = LocalAnalysisRunner.safe(Path.of(launch.jdk()));
            var cache = LocalAnalysisRunner.safe(Path.of(launch.mavenCache()));
            var directory = LocalAnalysisRunner.safe(Path.of(launch.graphDirectory()));
            if (!Files.isDirectory(jdk, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("JDK directory unavailable");
            Path source = null;
            List<String> exclusions = List.of();
            if (!launch.onlineMode()) {
                source = LocalAnalysisRunner.safe(Path.of(launch.source()));
                if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException("Inputs unavailable");
                // Prevent output files from changing the next captured snapshot, even when the
                // store lives inside the source tree.
                if (directory.equals(source))
                    throw new IllegalArgumentException("Store must have its own directory");
                exclusions = directory.startsWith(source)
                        ? List.of(source.relativize(directory).toString().replace('\\', '/'))
                        : List.<String>of();
            }
            Optional<LocalAnalysisRunner.PolicyInput> policy = Optional.empty();
            if (launch.policyPath().isPresent()) {
                var path = LocalAnalysisRunner.safe(Path.of(launch.policyPath().orElseThrow()));
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException("Policy unavailable");
                try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    policy = Optional.of(new LocalAnalysisRunner.PolicyInput(stream.readNBytes(262_145), Scope.TYPE));
                }
            }
            var artifactPolicy = artifactPolicy(System.getProperty("evo.workbench.artifactRepositories", ""));
            var contentRoot = directory.resolve("acquisition/content");
            var contentStoreRef = new java.util.concurrent.atomic.AtomicReference<com.evolution.analysis.filesystem.StreamingContentStore>();
            RepairSource.registerContentFallback((digest, expectedSize) ->
                    readBoundedCasContent(contentRoot, contentStoreRef, digest, expectedSize));
            var acquisitionDir = directory.resolve("acquisition");
            var sharedCaches = LocalAnalysisRunner.sharedCaches(acquisitionDir);
            Optional<LocalAnalysisRunner> localRunner = launch.onlineMode()
                    ? Optional.empty()
                    : Optional.of(new LocalAnalysisRunner(new LocalAnalysisRunner.Input(source,
                            RepositoryIdentity.fromCanonicalCoordinate(launch.coordinate()),
                            jdk, launch.release(), cache, Optional.empty(), true, false,
                            exclusions, policy, LocalAnalysisRunner.SpringInput.autoMultiConfig()),
                            acquisitionDir, artifactPolicy,
                            LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(),
                            Optional.of(directory),
                            sharedCaches));
            // The streamed reader is bounded by the actual file and its token limits.
            // Current free space is checked separately for new writes.
            long bytes = Long.MAX_VALUE - 1;
            var graphLimits = new GraphJson.StreamLimits(bytes, 128, bytes, Integer.MAX_VALUE - 8);
            var legacy = new FileGraphStore(directory, bytes, graphLimits);
            var pages = new PartitionedGraphStore(directory.resolve("graph-pages"));
            final class WorkbenchGraphs implements GraphStore, ArchitectureQueryApi.GraphStorageLocation, AutoCloseable {
                // Keep 2 hot graphs (enabling fast before/after snapshot & PR comparisons) and 32 manifests.
                private record FailedLoad(long size, long modifiedMillis, GraphStoreException.Reason reason) {
                }

                private final Map<ContentDigest, KnowledgeGraph> cache = Collections
                        .synchronizedMap(new LinkedHashMap<>(4, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(Map.Entry<ContentDigest, KnowledgeGraph> eldest) {
                                return size() > 2;
                            }
                        });
                private final Map<ContentDigest, GraphManifest> manifestCache = Collections
                        .synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(Map.Entry<ContentDigest, GraphManifest> eldest) {
                                return size() > 32;
                            }
                        });
                private final ExecutorService loader = Executors.newFixedThreadPool(2, task -> {
                    var thread = new Thread(task, "workbench-graph-loader");
                    thread.setDaemon(true);
                    return thread;
                });
                private final ConcurrentHashMap<ContentDigest, CompletableFuture<Optional<KnowledgeGraph>>> inFlight = new ConcurrentHashMap<>();
                private final Map<ContentDigest, FailedLoad> failedLoads = Collections
                        .synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(Map.Entry<ContentDigest, FailedLoad> eldest) {
                                return size() > 16;
                            }
                        });

                Optional<KnowledgeGraph> cached(ContentDigest id) {
                    return Optional.ofNullable(cache.get(id));
                }

                private Path snapshotFile(ContentDigest id) {
                    return directory.resolve(id.value().substring(7) + ".graph.json");
                }

                @Override
                public Path jsonPath(ContentDigest id) {
                    return snapshotFile(id);
                }

                private FailedLoad fileSignature(ContentDigest id, GraphStoreException.Reason reason) {
                    try {
                        Path file = snapshotFile(id);
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                            return null;
                        return new FailedLoad(Files.size(file),
                                Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis(), reason);
                    } catch (IOException ignored) {
                        return null;
                    }
                }

                private void checkFailed(ContentDigest id) {
                    FailedLoad prev = failedLoads.get(id);
                    if (prev == null)
                        return;
                    FailedLoad current = fileSignature(id, prev.reason());
                    if (current != null && current.size() == prev.size()
                            && current.modifiedMillis() == prev.modifiedMillis()) {
                        throw new GraphStoreException(prev.reason());
                    }
                    failedLoads.remove(id, prev);
                }

                private CompletableFuture<Optional<KnowledgeGraph>> startLoad(ContentDigest id) {
                    return inFlight.computeIfAbsent(id, key -> CompletableFuture.supplyAsync(() -> {
                        try {
                            var existing = cache.get(key);
                            if (existing != null)
                                return Optional.of(existing);
                            var loaded = legacy.load(key);
                            if (loaded.isPresent()) {
                                cache.put(key, loaded.orElseThrow());
                                failedLoads.remove(key);
                            }
                            return loaded;
                        } catch (GraphStoreException failure) {
                            FailedLoad sig = fileSignature(key, failure.reason());
                            if (sig != null)
                                failedLoads.put(key, sig);
                            throw failure;
                        } finally {
                            inFlight.remove(key);
                        }
                    }, loader));
                }

                void warm(ContentDigest id) {
                    if (id == null || cache.containsKey(id))
                        return;
                    try {
                        checkFailed(id);
                        startLoad(id);
                    } catch (GraphStoreException | RejectedExecutionException ignored) {
                        /* Warmup is best-effort. */ }
                }

                public void save(KnowledgeGraph graph, BooleanSupplier cancelled) {
                    try {
                        long budget = Math.max(1, Files.getFileStore(directory).getUsableSpace());
                        if (Files.isRegularFile(snapshotFile(graph.identity()),
                                LinkOption.NOFOLLOW_LINKS))
                            legacy.save(graph, cancelled);
                        else
                            new FileGraphStore(directory, budget, new GraphJson.StreamLimits(budget, 128, budget,
                                    (int) Math.min(budget, Integer.MAX_VALUE - 8))).save(graph, cancelled);
                    } catch (IOException unavailable) {
                        throw new GraphStoreException(GraphStoreException.Reason.IO_FAILURE);
                    }
                    failedLoads.remove(graph.identity());
                    cache.put(graph.identity(), graph);
                    manifestCache.put(graph.identity(), graph.manifest());
                }

                public Optional<KnowledgeGraph> load(ContentDigest id) {
                    var g = cache.get(id);
                    if (g != null)
                        return Optional.of(g);
                    checkFailed(id);
                    var future = startLoad(id);
                    while (true) {
                        if (Thread.currentThread().isInterrupted())
                            throw new GraphStoreException(GraphStoreException.Reason.CANCELLED);
                        try {
                            return future.get(250, TimeUnit.MILLISECONDS);
                        } catch (TimeoutException waiting) {
                            // Keep waiting while still allowing HTTP deadline interrupts to release
                            // immediately.
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new GraphStoreException(GraphStoreException.Reason.CANCELLED);
                        } catch (ExecutionException failure) {
                            if (failure.getCause() instanceof GraphStoreException gse)
                                throw gse;
                            if (failure.getCause() instanceof RuntimeException re)
                                throw re;
                            throw new GraphStoreException(GraphStoreException.Reason.IO_FAILURE);
                        }
                    }
                }

                public boolean verifyPublished(ContentDigest id, BooleanSupplier cancelled) {
                    return legacy.verifyPublished(id, cancelled);
                }

                @Override
                public Optional<GraphManifest> manifest(ContentDigest id) {
                    if (!Files.isRegularFile(snapshotFile(id), LinkOption.NOFOLLOW_LINKS))
                        return Optional.empty();
                    var cachedM = manifestCache.get(id);
                    if (cachedM != null)
                        return Optional.of(cachedM);
                    var g = cache.get(id);
                    if (g != null) {
                        manifestCache.put(id, g.manifest());
                        return Optional.of(g.manifest());
                    }
                    try {
                        var pagedManifest = pages.manifest(id);
                        if (pagedManifest.isPresent()) {
                            manifestCache.put(id, pagedManifest.orElseThrow());
                            return pagedManifest;
                        }
                    } catch (IOException | RuntimeException ignored) {
                        /* Fall back to binary cache header or canonical JSON snapshot. */
                    }
                    var cachedManifest = legacy.readManifest(id);
                    if (cachedManifest.isPresent()) {
                        manifestCache.put(id, cachedManifest.orElseThrow());
                        return cachedManifest;
                    }
                    var loaded = load(id).map(KnowledgeGraph::manifest);
                    loaded.ifPresent(m -> manifestCache.put(id, m));
                    return loaded;
                }

                @Override
                public void close() {
                    loader.shutdownNow();
                }
            }
            try (var store = new WorkbenchGraphs();
                    var reportDisk = new PersistentReports(directory.resolve("reports"));
                    var disk = new AnalysisJobDiskStore(directory.resolve("jobs"))) {
                var reports = new PublishedReports(reportDisk);
                var previewStore = new WorkbenchPreviewStore();
                var initialSetups = localRunner.map(r -> List.of(r.registration("local"))).orElseGet(List::of);
                try (var jobs = new AnalysisJobs(initialSetups, store, reports,
                        AnalysisJobs.Limits.defaults(), disk, previewStore)) {
                    var history = jobs.history(1, Optional.empty()).items();
                    if (!history.isEmpty()) {
                        history.getFirst().result().flatMap(AnalysisJobs.Result::graph).ifPresent(store::warm);
                    }
                    var oauthConfig = com.evolution.backend.auth.GitHubOAuthService.Config.fromEnvironment();
                    var authDir = directory.resolve("auth");
                    var oauthConfigStore = new com.evolution.backend.auth.GitHubOAuthConfigStore(authDir, System::getenv);
                    var accountStore = new com.evolution.backend.auth.UserAccountStore(authDir.resolve("accounts.db"));
                    var githubTokens = new com.evolution.backend.github.GitHubTokenBroker();
                    githubTokens.setMultiUserMode(oauthConfig.multiUserMode());
                    var gitHostPolicy = new GitHostPolicy(Set.of(), Map.of(), java.net.InetAddress::getAllByName,
                            githubTokens.readOnlyGitCredentials());
                    var sessionStore = new com.evolution.backend.auth.UserSessionStore(
                            Optional.of(directory.resolve("auth-sessions.enc")),
                            Optional.of(oauthConfigStore.masterKeyBytes()));
                    var connections = new RepositoryConnections(jobs, jdk, launch.release(), cache, directory,
                            policy, "git", gitHostPolicy, artifactPolicy, launch.onlineMode());
                    if (!launch.onlineMode()) {
                        var repositoryId = RepositoryIdentity.fromCanonicalCoordinate(launch.coordinate());
                        connections.describeLocal("local", source.getFileName().toString(), repositoryId, source, policy);
                    }
                    try (githubTokens;
                            sessionStore;
                            accountStore;
                            var searchIndex = com.evolution.backend.query.search.CapturedSearchIndex.openReadWrite(
                                    directory.resolve("search").resolve("fts5.db"));
                            var chatStore = new com.evolution.backend.query.chat.ChatConversationStore(
                                    directory.resolve("chat").resolve("conversations.db"));
                            var repairJobStore = new com.evolution.backend.query.repair.RepairJobStore(
                                    directory.resolve("repairs").resolve("jobs.db"));
                            var repairJobs = new com.evolution.backend.query.repair.RepairVerificationJobs(2, repairJobStore);
                            var pageIndex = new LazyGraphPages(directory, pages, store::cached);
                            var evolution = new EvolutionJobs(directory.resolve("evolution"), pages, reports,
                                    pageIndex::evolution)) {
                        var repairStore = new com.evolution.backend.query.RepairProposalStore(
                                directory.resolve("repairs"));
                        var safePatchApplier = new com.evolution.backend.query.repair.SafePatchApplier(
                                directory.resolve("repair-applies"));
                        var repairReanalysis = new CandidateRepairReanalysis(jdk, launch.release(), cache,
                                directory, store, reports, connections);
                        var api = createWorkbenchQueryApi(store, reports, jobs, connections, pageIndex::reader,
                                previewStore);
                        var githubRest = new com.evolution.backend.github.GitHubRestClient(githubTokens, gitHostPolicy);
                        var githubWebhooks = com.evolution.backend.github.GitHubWebhookHandler.fromEnvironment();
                        var oauthService = new com.evolution.backend.auth.GitHubOAuthService(
                                oauthConfig, sessionStore,
                                com.evolution.backend.github.GitHubRestClient.createPinnedTransport(gitHostPolicy),
                                oauthConfigStore, accountStore)
                                .withWebhookSecretListener(githubWebhooks::updateSecret);
                        var authApi = new com.evolution.backend.auth.AuthHttpApi(
                                oauthService, githubRest, api.limits(), githubWebhooks::isConfigured);
                        var githubDiffs = new com.evolution.backend.github.GitHubPrDiffService(
                                githubRest, jobs, connections, store, reports, directory.resolve("github-diffs"));
                        var githubPublisher = new com.evolution.backend.github.GitHubRepairPublisher(
                                githubRest, githubTokens, repairStore, connections, gitHostPolicy,
                                directory.resolve("github-publish"), "git");
                        var githubPrReview = new com.evolution.backend.github.GitHubPrReviewService(
                                githubRest, githubDiffs, connections, store, reports, repairStore);
                        var githubIssues = new com.evolution.backend.github.GitHubIssueService(
                                githubRest, store, reports);
                        var githubApi = new com.evolution.backend.github.GitHubHttpApi(
                                githubTokens, githubRest, connections, githubDiffs, githubPublisher,
                                repairJobs, api.limits())
                                .withEnterpriseServices(authApi, githubPrReview, githubIssues, githubWebhooks, store, reports);
                        var initialRunner = CapturedRepairRunner.fromSystemProperties()
                                .map(CapturedRepairRunner::new)
                                .orElse(null);
                        var sandboxSetup = new com.evolution.backend.query.repair.SandboxSetupService(
                                directory.resolve("repairs").resolve("sandbox-work"), repairJobStore, initialRunner,
                                directory, authApi.isMultiUserMode());
                        var evolvedApi = ArchitectureQueryApi.withChatStore(
                                ArchitectureQueryApi.withSearchIndex(
                                        ArchitectureQueryApi.withSandboxSetup(
                                                ArchitectureQueryApi.withStoreDirectory(
                                                        ArchitectureQueryApi.withAuth(
                                                                ArchitectureQueryApi.withAcquisitionContentDir(
                                                                        ArchitectureQueryApi.withSafePatchApplier(
                                                                                ArchitectureQueryApi.withRepairVerificationJobs(
                                                                                        ArchitectureQueryApi.withGitHub(
                                                                                                ArchitectureQueryApi.withEvolution(api, evolution,
                                                                                                        new EvolutionQueries(evolution.results(), pages)),
                                                                                                githubApi),
                                                                                        repairJobs),
                                                                                safePatchApplier),
                                                                        acquisitionDir.resolve("content")),
                                                                authApi),
                                                        directory),
                                                sandboxSetup),
                                        searchIndex),
                                chatStore);
                        var capturedApi = ArchitectureQueryApi.withConfiguredCapturedRepairCapture(
                                ArchitectureQueryApi.withConfiguredDemoRepairCapture(
                                        ArchitectureQueryApi.withRepairReanalysis(
                                                ArchitectureQueryApi.withRepairStore(evolvedApi, repairStore),
                                                repairReanalysis)));
                        var configuredApi = ArchitectureQueryApi.withConfiguredLlmRegistry(
                                ArchitectureQueryApi.withConfiguredLiveDiagnosis(
                                        ArchitectureQueryApi.withConfiguredRepairModel(
                                                ArchitectureQueryApi.withConfiguredDemoDiagnosis(capturedApi))));
                        try (var server = new ArchitectureHttpServer(configuredApi,
                                launch.bindHost(), launch.port())) {
                            var stopped = new CountDownLatch(1);
                            var shutdown = new Thread(() -> {
                                server.close();
                                repairJobs.close();
                                repairJobStore.close();
                                chatStore.close();
                                searchIndex.close();
                                store.close();
                                try {
                                    evolution.close();
                                } catch (IOException unavailable) {
                                    System.err.println(
                                            "Comparison worker did not close cleanly. Pending jobs can be reopened.");
                                }
                                pageIndex.close();
                                jobs.close();
                                stopped.countDown();
                            }, "architecture-workbench-shutdown");
                            Runtime.getRuntime().addShutdownHook(shutdown);
                            try {
                                server.start();
                                if (launch.onlineMode()) {
                                    System.out.println("Architecture workbench ONLINE web app: http://"
                                            + launch.bindHost() + ":" + launch.port()
                                            + " (API: /api/v1, health: /api/v1/health)");
                                } else {
                                    System.out.println("Architecture workbench API: " + server.uri() + "/api/v1");
                                }
                                stopped.await();
                            } finally {
                                try {
                                    Runtime.getRuntime().removeShutdownHook(shutdown);
                                } catch (IllegalStateException stopping) {
                                    /* Shutdown hook owns cleanup. */ }
                            }
                        }
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            System.err.println(
                    "Unable to start workbench API; check the explicit inputs, storage limits and port.");
            System.exit(1);
        }
    }

    /**
     * The canonical snapshot is authoritative; the raw page tree is a rebuildable
     * query index.
     */
    private static final class LazyGraphPages implements AutoCloseable {
        private final Path snapshots;
        private final PartitionedGraphStore pages;
        private final java.util.function.Function<ContentDigest, Optional<KnowledgeGraph>> cached;
        private final ExecutorService worker = Executors
                .newSingleThreadExecutor(task -> new Thread(task, "workbench-graph-index"));
        private ContentDigest preparing;
        private final Map<ContentDigest, IOException> failures = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ContentDigest, IOException> eldest) {
                return size() > 16;
            }
        };

        LazyGraphPages(Path snapshots, PartitionedGraphStore pages,
                java.util.function.Function<ContentDigest, Optional<KnowledgeGraph>> cached) {
            this.snapshots = snapshots;
            this.pages = pages;
            this.cached = cached;
        }

        private synchronized void ensure(ContentDigest id, BooleanSupplier cancelled) throws IOException {
            if (cancelled.getAsBoolean())
                throw new CancellationException();
            if (pages.hasPreparedIndex(id))
                return;
            var failure = failures.remove(id);
            if (failure != null)
                throw failure;
            if (preparing != null)
                throw new PartitionedGraphReader.IndexPending(!preparing.equals(id));
            var source = snapshots.resolve(id.value().substring(7) + ".graph.json");
            boolean existingIndex = pages.hasIndex(id);
            if (!existingIndex && !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("GRAPH_NOT_FOUND");
            if (worker.isShutdown())
                throw new CancellationException();
            preparing = id;
            worker.execute(() -> prepare(id, source, existingIndex));
            throw new PartitionedGraphReader.IndexPending(false);
        }

        private void prepare(ContentDigest id, Path source, boolean existingIndex) {
            IOException failure = null;
            BooleanSupplier stopped = Thread.currentThread()::isInterrupted;
            try {
                if (existingIndex) {
                    pages.prepareIndex(id, stopped);
                } else {
                    var graph = cached.apply(id);
                    if (graph.isPresent())
                        pages.save(graph.orElseThrow(), stopped);
                    else
                        pages.importSnapshot(source, id, stopped);
                }
            } catch (IOException unavailable) {
                failure = unavailable;
            } catch (RuntimeException unavailable) {
                failure = new IOException("GRAPH_INDEX_PREPARATION_FAILED", unavailable);
            } finally {
                synchronized (this) {
                    if (failure != null)
                        failures.put(id, failure);
                    preparing = null;
                    notifyAll();
                }
            }
        }

        EvolutionSnapshot evolution(ContentDigest graph, BooleanSupplier cancelled,
                java.util.function.Consumer<String> progress) throws IOException {
            boolean waited = false;
            synchronized (this) {
                var source = snapshots.resolve(graph.value().substring(7) + ".graph.json");
                while (!pages.hasIndex(graph) && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    EvolutionSnapshot.check(cancelled);
                    try {
                        ensure(graph, cancelled);
                    } catch (PartitionedGraphReader.IndexPending pending) {
                        if (!waited)
                            progress.accept("INDEX_PREPARING");
                        waited = true;
                        try {
                            wait(100);
                        } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                            throw new CancellationException();
                        }
                    } catch (IOException unavailable) {
                        throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.UNAVAILABLE, unavailable);
                    }
                }
            }
            if (waited)
                progress.accept("COMPARING");
            return pages.openEvolution(graph, cancelled);
        }

        @Override
        public void close() {
            synchronized (this) {
                worker.shutdownNow();
            }
            try {
                worker.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        PartitionedGraphReader reader(BooleanSupplier cancelled) {
            var delegate = pages.reader(cancelled);
            return new PartitionedGraphReader() {
                public Page<Partition> partitions(ContentDigest graph, int size, Optional<String> cursor)
                        throws IOException {
                    ensure(graph, cancelled);
                    return delegate.partitions(graph, size, cursor);
                }

                public Page<KnowledgeGraph.Node> nodes(Partition partition, int size, Optional<String> cursor)
                        throws IOException {
                    ensure(partition.graph(), cancelled);
                    return delegate.nodes(partition, size, cursor);
                }

                public Page<KnowledgeGraph.Edge> adjacency(Partition partition, String node, boolean incoming, int size,
                        Optional<String> cursor) throws IOException {
                    ensure(partition.graph(), cancelled);
                    return delegate.adjacency(partition, node, incoming, size, cursor);
                }
            };
        }
    }

    static ArchitectureQueryApi createWorkbenchQueryApi(
            GraphStore store,
            PublishedReports reports,
            AnalysisJobs jobs,
            RepositoryConnections connections,
            java.util.function.Function<BooleanSupplier, PartitionedGraphReader> partitionReaders,
            WorkbenchPreviewStore previewStore) {
        var base = new ArchitectureQueryApi(store, reports,
                new ApiLimits(65_536, 16_777_216, 32, 100_000, 8, 300_000), jobs, connections);
        var withPartitions = partitionReaders != null
                ? ArchitectureQueryApi.withPartitionedGraphs(base, partitionReaders)
                : base;
        return ArchitectureQueryApi.withPreviewStore(withPartitions, previewStore);
    }

    static final URI GOOGLE_MAVEN = URI.create("https://dl.google.com/dl/android/maven2/");
    static final URI GRADLE_PLUGIN_PORTAL = URI.create("https://plugins.gradle.org/m2/");
    static final URI JITPACK = URI.create("https://jitpack.io/");

    static Optional<DependencyAcquisitionPolicy> artifactPolicy(String configured) {
        String trimmed = configured == null ? "" : configured.strip();
        if (Boolean.getBoolean("evo.workbench.offline")
                || trimmed.equalsIgnoreCase("offline")
                || trimmed.equalsIgnoreCase("none")) {
            return Optional.empty();
        }
        var repositories = trimmed.isEmpty()
                ? List.of(DependencyAcquisitionPolicy.MAVEN_CENTRAL, GOOGLE_MAVEN, GRADLE_PLUGIN_PORTAL, JITPACK)
                : Arrays.stream(trimmed.split("\\s+")).map(URI::create).toList();
        return Optional.of(
                new DependencyAcquisitionPolicy(4096, 64_000_000, MavenScan.DEFAULT_DEPENDENCY_TOTAL_BYTES, 64,
                        repositories, 5000, 15000, 1, 16));
    }

    private WorkbenchApiMain() {
    }
}
