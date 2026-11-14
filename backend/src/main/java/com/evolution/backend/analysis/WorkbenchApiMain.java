package com.evolution.backend.analysis;

import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.filesystem.PartitionedGraphStore;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.backend.query.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.io.IOException;

/** Loopback composition root with operator-enabled passive local/public-Git connections. */
public final class WorkbenchApiMain {
    public static void main(String[] args) {
        if (args.length < 6 || args.length > 8) {
            System.err.println("Usage: WorkbenchApiMain <source> <repository-coordinate> <jdk> <release> <maven-cache> <existing-graph-directory> [port] [policy.yaml]");
            System.exit(2);
        }
        try {
            var source = LocalAnalysisRunner.safe(Path.of(args[0])); var jdk = LocalAnalysisRunner.safe(Path.of(args[2]));
            var cache = LocalAnalysisRunner.safe(Path.of(args[4])); var directory = LocalAnalysisRunner.safe(Path.of(args[5]));
            if (!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(jdk,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Inputs unavailable");
            // Prevent output files from changing the next captured snapshot, even when the store lives inside the source tree.
            if (directory.equals(source)) throw new IllegalArgumentException("Store must have its own directory");
            var exclusions = directory.startsWith(source) ? List.of(source.relativize(directory).toString().replace('\\','/')) : List.<String>of();
            Optional<LocalAnalysisRunner.PolicyInput> policy = Optional.empty();
            if (args.length == 8) {
                var path = LocalAnalysisRunner.safe(Path.of(args[7]));
                if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Policy unavailable");
                try (var stream = Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) { policy = Optional.of(new LocalAnalysisRunner.PolicyInput(stream.readNBytes(262_145),Scope.TYPE)); }
            }
            var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(source,RepositoryIdentity.fromCanonicalCoordinate(args[1]),jdk,Integer.parseInt(args[3]),cache,
                    Optional.empty(),true,false,exclusions,policy,LocalAnalysisRunner.SpringInput.absent()),directory.resolve("acquisition"));
            // The streamed reader is bounded by the actual file and its token limits.
            // Current free space is checked separately for new writes.
            long bytes=Long.MAX_VALUE-1;
            var legacy = new FileGraphStore(directory,bytes,new GraphJson.StreamLimits(bytes,128,bytes));
            var pages = new PartitionedGraphStore(directory.resolve("graph-pages"));
            final class WorkbenchGraphs implements GraphStore {
                // Large graphs already carry both adjacency indexes. Keep only the most recently used
                // graph; a cold query may read the durable snapshot again.
                private final Map<ContentDigest, KnowledgeGraph> cache = Collections.synchronizedMap(new LinkedHashMap<>(2, 0.75f, true){
                    @Override protected boolean removeEldestEntry(Map.Entry<ContentDigest, KnowledgeGraph> eldest) { return size() > 1; }
                });
                private final ReentrantLock loadLock = new ReentrantLock(true);
                Optional<KnowledgeGraph> cached(ContentDigest id) { return Optional.ofNullable(cache.get(id)); }
                public void save(KnowledgeGraph graph, BooleanSupplier cancelled){
                    try {
                        long budget=Math.max(1,Files.getFileStore(directory).getUsableSpace());
                        if(Files.isRegularFile(directory.resolve(graph.identity().value().substring(7)+".graph.json"),
                                LinkOption.NOFOLLOW_LINKS))legacy.save(graph,cancelled);
                        else new FileGraphStore(directory,budget,new GraphJson.StreamLimits(budget,128,budget)).save(graph,cancelled);
                    } catch(IOException unavailable){throw new GraphStoreException(GraphStoreException.Reason.IO_FAILURE);}
                    cache.put(graph.identity(), graph);
                }
                public Optional<KnowledgeGraph> load(ContentDigest id){
                    var g = cache.get(id);
                    if (g != null) return Optional.of(g);
                    loadLock.lock();
                    try {
                        var present = cache.get(id);
                        if (present != null) return Optional.of(present);
                        var loaded = legacy.load(id);
                        loaded.ifPresent(graph -> cache.put(id, graph));
                        return loaded;
                    } finally { loadLock.unlock(); }
                }
                public boolean verifyPublished(ContentDigest id, BooleanSupplier cancelled) {
                    return legacy.verifyPublished(id, cancelled);
                }
                @Override public Optional<GraphManifest> manifest(ContentDigest id) {
                    var g = cache.get(id);
                    if (g != null) return Optional.of(g.manifest());
                    return load(id).map(KnowledgeGraph::manifest);
                }
            }
            var store = new WorkbenchGraphs();
            try (var reportDisk=new PersistentReports(directory.resolve("reports"));var disk = new AnalysisJobDiskStore(directory.resolve("jobs"))) {
            var reports=new PublishedReports(reportDisk);
            try(var jobs = new AnalysisJobs(List.of(runner.registration("local")),store,reports,AnalysisJobs.Limits.defaults(),disk)) {
                var connections=new RepositoryConnections(jobs,jdk,Integer.parseInt(args[3]),cache,directory,policy);
                connections.describe("local",source.getFileName().toString(),"LOCAL",RepositoryIdentity.fromCanonicalCoordinate(args[1]));
                try (var pageIndex = new LazyGraphPages(directory, pages, store::cached)) {
                    var api=ArchitectureQueryApi.withPartitionedGraphs(
                            new ArchitectureQueryApi(store,reports,ApiLimits.defaults(),jobs,connections),pageIndex::reader);
                    try (var server = new ArchitectureHttpServer(api,args.length >= 7 ? Integer.parseInt(args[6]) : 8080)) {
                        var stopped = new CountDownLatch(1);
                        var shutdown = new Thread(() -> {
                            server.close();
                            pageIndex.close();
                            jobs.close();
                            stopped.countDown();
                        },"architecture-workbench-shutdown");
                        Runtime.getRuntime().addShutdownHook(shutdown);
                        try { server.start(); System.out.println("Architecture workbench API: " + server.uri() + "/api/v1"); stopped.await(); }
                        finally { try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException stopping) { /* Shutdown hook owns cleanup. */ } }
                    }
                }
            }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception failure) { System.err.println("Unable to start local workbench API; check the explicit inputs, storage limits and port."); System.exit(1); }
    }

    /** The canonical snapshot is authoritative; the raw page tree is a rebuildable query index. */
    private static final class LazyGraphPages implements AutoCloseable {
        private final Path snapshots;
        private final PartitionedGraphStore pages;
        private final java.util.function.Function<ContentDigest, Optional<KnowledgeGraph>> cached;
        private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "workbench-graph-index"));
        private ContentDigest preparing;
        private final Map<ContentDigest, IOException> failures = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<ContentDigest, IOException> eldest) { return size() > 16; }
        };

        LazyGraphPages(Path snapshots, PartitionedGraphStore pages,
                java.util.function.Function<ContentDigest, Optional<KnowledgeGraph>> cached) {
            this.snapshots = snapshots;
            this.pages = pages;
            this.cached = cached;
        }

        private synchronized void ensure(ContentDigest id, BooleanSupplier cancelled) throws IOException {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            if (pages.hasPreparedIndex(id)) return;
            var failure = failures.remove(id);
            if (failure != null) throw failure;
            if (preparing != null) throw new PartitionedGraphReader.IndexPending(!preparing.equals(id));
            var source = snapshots.resolve(id.value().substring(7)+".graph.json");
            boolean existingIndex = pages.hasIndex(id);
            if (!existingIndex && !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("GRAPH_NOT_FOUND");
            if (worker.isShutdown()) throw new CancellationException();
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
                    if (graph.isPresent()) pages.save(graph.orElseThrow(), stopped);
                    else pages.importSnapshot(source, id, stopped);
                }
            } catch (IOException unavailable) {
                failure = unavailable;
            } catch (RuntimeException unavailable) {
                failure = new IOException("GRAPH_INDEX_PREPARATION_FAILED", unavailable);
            } finally {
                synchronized (this) {
                    if (failure != null) failures.put(id, failure);
                    preparing = null;
                }
            }
        }

        @Override public void close() {
            synchronized (this) { worker.shutdownNow(); }
            try {
                worker.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        PartitionedGraphReader reader(BooleanSupplier cancelled) {
            var delegate = pages.reader(cancelled);
            return new PartitionedGraphReader() {
                public Page<Partition> partitions(ContentDigest graph, int size, Optional<String> cursor) throws IOException {
                    ensure(graph, cancelled);
                    return delegate.partitions(graph, size, cursor);
                }
                public Page<KnowledgeGraph.Node> nodes(Partition partition, int size, Optional<String> cursor) throws IOException {
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
    private WorkbenchApiMain() {}
}
