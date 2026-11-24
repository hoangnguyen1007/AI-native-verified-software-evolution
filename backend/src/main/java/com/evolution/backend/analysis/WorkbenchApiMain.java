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
    public static void main(String[] args) {
        if (args.length < 6 || args.length > 8) {
            System.err.println(
                    "Usage: WorkbenchApiMain <source> <repository-coordinate> <jdk> <release> <maven-cache> <existing-graph-directory> [port] [policy.yaml]");
            System.exit(2);
        }
        try {
            var source = LocalAnalysisRunner.safe(Path.of(args[0]));
            var jdk = LocalAnalysisRunner.safe(Path.of(args[2]));
            var cache = LocalAnalysisRunner.safe(Path.of(args[4]));
            var directory = LocalAnalysisRunner.safe(Path.of(args[5]));
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(jdk, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Inputs unavailable");
            // Prevent output files from changing the next captured snapshot, even when the
            // store lives inside the source tree.
            if (directory.equals(source))
                throw new IllegalArgumentException("Store must have its own directory");
            var exclusions = directory.startsWith(source)
                    ? List.of(source.relativize(directory).toString().replace('\\', '/'))
                    : List.<String>of();
            Optional<LocalAnalysisRunner.PolicyInput> policy = Optional.empty();
            if (args.length == 8) {
                var path = LocalAnalysisRunner.safe(Path.of(args[7]));
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException("Policy unavailable");
                try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    policy = Optional.of(new LocalAnalysisRunner.PolicyInput(stream.readNBytes(262_145), Scope.TYPE));
                }
            }
            var artifactPolicy = artifactPolicy(System.getProperty("evo.workbench.artifactRepositories", ""));
            var contentRoot = directory.resolve("acquisition/content");
            var contentStoreRef = new java.util.concurrent.atomic.AtomicReference<com.evolution.analysis.filesystem.StreamingContentStore>();
            RepairSource.registerContentFallback((digest, expectedSize) -> {
                if (expectedSize.isEmpty() || expectedSize.getAsLong() < 0
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
                    try (var in = cas.open(new com.evolution.analysis.acquisition.streaming.ContentHandle(digest,
                            expectedSize.getAsLong()))) {
                        return Optional.of(in.readAllBytes());
                    }
                } catch (IOException | IllegalArgumentException unavailable) {
                    return Optional.empty();
                }
            });
            var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(source,
                    RepositoryIdentity.fromCanonicalCoordinate(args[1]), jdk, Integer.parseInt(args[3]), cache,
                    Optional.empty(), true, false, exclusions, policy, LocalAnalysisRunner.SpringInput.autoMultiConfig()),
                    directory.resolve("acquisition"), artifactPolicy,
                    LocalAnalysisRunner.NativeExecutionConfig.fromEnvironment(),
                    Optional.of(directory));
            // The streamed reader is bounded by the actual file and its token limits.
            // Current free space is checked separately for new writes.
            long bytes = Long.MAX_VALUE - 1;
            var graphLimits = new GraphJson.StreamLimits(bytes, 128, bytes, Integer.MAX_VALUE - 8);
            var legacy = new FileGraphStore(directory, bytes, graphLimits);
            var pages = new PartitionedGraphStore(directory.resolve("graph-pages"));
            final class WorkbenchGraphs implements GraphStore, AutoCloseable {
                // Large graphs already carry both adjacency indexes. Keep only the most
                // recently used
                // graph; a cold query may read the durable snapshot again.
                private record FailedLoad(long size, long modifiedMillis, GraphStoreException.Reason reason) {
                }

                private final Map<ContentDigest, KnowledgeGraph> cache = Collections
                        .synchronizedMap(new LinkedHashMap<>(2, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(Map.Entry<ContentDigest, KnowledgeGraph> eldest) {
                                return size() > 1;
                            }
                        });
                private final ExecutorService loader = Executors.newSingleThreadExecutor(task -> {
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
                    var g = cache.get(id);
                    if (g != null)
                        return Optional.of(g.manifest());
                    try {
                        var pagedManifest = pages.manifest(id);
                        if (pagedManifest.isPresent())
                            return pagedManifest;
                    } catch (IOException | RuntimeException ignored) {
                        /* Fall back to binary cache header or canonical JSON snapshot. */
                    }
                    var cachedManifest = legacy.readManifest(id);
                    if (cachedManifest.isPresent())
                        return cachedManifest;
                    return load(id).map(KnowledgeGraph::manifest);
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
                try (var jobs = new AnalysisJobs(List.of(runner.registration("local")), store, reports,
                        AnalysisJobs.Limits.defaults(), disk)) {
                    var history = jobs.history(1, Optional.empty()).items();
                    if (!history.isEmpty()) {
                        history.getFirst().result().flatMap(AnalysisJobs.Result::graph).ifPresent(store::warm);
                    }
                    var repositoryId = RepositoryIdentity.fromCanonicalCoordinate(args[1]);
                    var connections = new RepositoryConnections(jobs, jdk, Integer.parseInt(args[3]), cache, directory,
                            policy, "git", new GitHostPolicy(), artifactPolicy);
                    connections.describeLocal("local", source.getFileName().toString(), repositoryId, source, policy);
                    try (var pageIndex = new LazyGraphPages(directory, pages, store::cached);
                            var evolution = new EvolutionJobs(directory.resolve("evolution"), pages, reports,
                                    pageIndex::evolution)) {
                        var repairStore = new com.evolution.backend.query.RepairProposalStore(
                                directory.resolve("repairs"));
                        var repairReanalysis = new CandidateRepairReanalysis(jdk, Integer.parseInt(args[3]), cache,
                                directory, store, reports, connections);
                        var api = ArchitectureQueryApi.withPartitionedGraphs(
                                new ArchitectureQueryApi(store, reports,
                                        new ApiLimits(65_536, 16_777_216, 32, 100_000, 8, 300_000), jobs, connections),
                                pageIndex::reader);
                        var evolvedApi = ArchitectureQueryApi.withEvolution(api, evolution,
                                new EvolutionQueries(evolution.results(), pages));
                        var capturedApi = ArchitectureQueryApi.withConfiguredCapturedRepairRunner(
                                ArchitectureQueryApi.withConfiguredCapturedRepairCapture(
                                        ArchitectureQueryApi.withConfiguredDemoRepairCapture(
                                                ArchitectureQueryApi.withRepairReanalysis(
                                                        ArchitectureQueryApi.withRepairStore(evolvedApi, repairStore),
                                                        repairReanalysis))));
                        var configuredApi = ArchitectureQueryApi.withConfiguredLiveDiagnosis(
                                ArchitectureQueryApi.withConfiguredRepairModel(
                                        ArchitectureQueryApi.withConfiguredDemoDiagnosis(capturedApi)));
                        try (var server = new ArchitectureHttpServer(
                                ArchitectureQueryApi.withConfiguredArchitectureChat(configuredApi),
                                args.length >= 7 ? Integer.parseInt(args[6]) : 8080)) {
                            var stopped = new CountDownLatch(1);
                            var shutdown = new Thread(() -> {
                                server.close();
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
                                System.out.println("Architecture workbench API: " + server.uri() + "/api/v1");
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
                    "Unable to start local workbench API; check the explicit inputs, storage limits and port.");
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
                        repositories, 5000, 15000, 1, 8));
    }

    private WorkbenchApiMain() {
    }
}
