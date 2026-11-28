package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.evolution.EvolutionSnapshot;
import com.evolution.analysis.concurrent.BoundedTasks;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Immutable, authenticated CAS page trees. SQLite is an external-sort scratch
 * index only;
 * queries follow content hashes from a verified root, never trust mutable SQL
 * rows.
 * Cold verification streams the original v1 graph digest without constructing
 * KnowledgeGraph.
 */
public final class PartitionedGraphStore implements PartitionedGraphReader {
    private static final String FORMAT = "graph.partition-store:m8.4-v1", LEAF = "graph.page-leaf:m8.4-v1",
            BRANCH = "graph.page-branch:m8.4-v1";
    static final int DEFAULT_ROW_BYTES = 512 * 1024 * 1024;
    private static final int MAX_ROW_BYTES = 1_073_741_824;
    private static final int LEAF_ENVELOPE_ALLOWANCE = 65_536;
    private static final int FANOUT = 128, PAGE_ROWS = 256, PAGE_CHARACTERS = 1_048_576;
    private static final int LOCAL_PAGE_CACHE_MAX_ENTRIES = 128;
    private static final long LOCAL_PAGE_CACHE_MAX_BYTES = 64L * 1024 * 1024;
    private static final ThreadLocal<Boolean> DEFER_LEAF_FORCE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<BoundedPageCache> LOCAL_PAGE_CACHE = new ThreadLocal<>();
    static final java.util.concurrent.atomic.AtomicLong LOCAL_PAGE_CACHE_HITS = new java.util.concurrent.atomic.AtomicLong();

    @FunctionalInterface
    private interface PageCacheScope extends AutoCloseable {
        @Override void close();
    }

    private static final class BoundedPageCache {
        private final LinkedHashMap<ContentHandle, Object> entries = new LinkedHashMap<>(64, 0.75f, true);
        private long totalBytes;

        Object get(ContentHandle handle) {
            return entries.get(handle);
        }

        void put(ContentHandle handle, Object parsed) {
            long size = handle.size();
            if (size <= 0L || size > 1_048_576L || size > LOCAL_PAGE_CACHE_MAX_BYTES) return;
            if (entries.containsKey(handle)) {
                entries.put(handle, parsed);
                return;
            }
            entries.put(handle, parsed);
            totalBytes += size;
            var it = entries.entrySet().iterator();
            while ((entries.size() > LOCAL_PAGE_CACHE_MAX_ENTRIES || totalBytes > LOCAL_PAGE_CACHE_MAX_BYTES) && it.hasNext()) {
                totalBytes -= it.next().getKey().size();
                it.remove();
            }
        }
    }

    private static PageCacheScope openLocalPageCache() {
        if (LOCAL_PAGE_CACHE.get() != null) {
            return () -> {};
        }
        LOCAL_PAGE_CACHE.set(new BoundedPageCache());
        return LOCAL_PAGE_CACHE::remove;
    }

    static boolean isLocalPageCacheActive() {
        return LOCAL_PAGE_CACHE.get() != null;
    }
    private final Path directory;
    private final StreamingContentStore content;
    private final int rowBytes;
    private final int pageWorkers;
    private final java.util.concurrent.atomic.AtomicLong forcedBlobs = new java.util.concurrent.atomic.AtomicLong();
    private final Map<ContentDigest, Root> verified = Collections.synchronizedMap(new LinkedHashMap<>(16, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<ContentDigest, Root> entry) {
            return size() > 16;
        }
    });

    private record Tree(ContentHandle content, String first, String last, long rows) {
    }

    private record Entry(String key, Object row) {
    }

    private record EncodedEntry(String key, String payload) {
    }

    private record Root(ContentHandle document, ContentDigest graph, String snapshot, Map<String, Tree> trees) {
        Root {
            new com.evolution.analysis.contract.identity.SnapshotIdentity(snapshot);
        }
    }

    public record Publication(ContentDigest graph, com.evolution.analysis.contract.identity.SnapshotIdentity snapshot,
            List<KnowledgeGraph.Context> contexts, long nodes, long edges, long evidence, Map<String, Long> edgeKinds,
            Map<String, Long> gapReasons, long gapNodes) {
        public Publication {
            contexts = List.copyOf(contexts);
            edgeKinds = Map.copyOf(edgeKinds);
            gapReasons = Map.copyOf(gapReasons);
        }

        public Publication(ContentDigest graph, com.evolution.analysis.contract.identity.SnapshotIdentity snapshot,
                List<KnowledgeGraph.Context> contexts, long nodes, long edges, long evidence,
                Map<String, Long> edgeKinds) {
            this(graph, snapshot, contexts, nodes, edges, evidence, edgeKinds, Map.of(), 0);
        }

        /**
         * All stored gap identities; CURRENT/HISTORY qualification stays in domain
         * queries.
         */
        public long gaps() {
            return gapReasons.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(Entry entry) throws IOException;
    }

    /**
     * Own only these generated flat scratch files. Try-with-resources retains
     * the original failure and records cleanup failures as suppressed causes.
     */
    private static final class TemporaryFiles implements AutoCloseable {
        private final List<Path> paths = new ArrayList<>();

        TemporaryFiles(Path... created) {
            Collections.addAll(paths, created);
        }

        Path add(Path path) {
            paths.add(path);
            return path;
        }

        public void close() throws IOException {
            IOException failed = null;
            for (var path : paths)
                try {
                    Files.deleteIfExists(path);
                } catch (IOException problem) {
                    if (failed == null)
                        failed = problem;
                    else if (failed != problem)
                        failed.addSuppressed(problem);
                }
            if (failed != null)
                throw failed;
        }
    }

    private static IOException unwrap(UncheckedIOException failure) {
        var cause = failure.getCause();
        for (var suppressed : failure.getSuppressed())
            if (suppressed != cause)
                cause.addSuppressed(suppressed);
        return cause;
    }

    public PartitionedGraphStore(Path directory) throws IOException {
        this(directory, DEFAULT_ROW_BYTES);
    }

    public PartitionedGraphStore(Path directory, int rowBytes) throws IOException {
        this(directory, rowBytes, BoundedTasks.automaticWorkers());
    }

    public PartitionedGraphStore(Path directory, int rowBytes, int pageWorkers) throws IOException {
        this.directory = StreamingContentStore.directory(directory);
        if (rowBytes < 1024 || rowBytes > MAX_ROW_BYTES)
            throw new IllegalArgumentException("GRAPH_PAGE_WORKER_BUDGET");
        if (pageWorkers < 1 || pageWorkers > 8)
            throw new IllegalArgumentException("GRAPH_PAGE_WORKERS_REQUIRED");
        this.rowBytes = rowBytes;
        this.pageWorkers = pageWorkers;
        content = new StreamingContentStore(this.directory.resolve("content"), 0, channel -> {
            if (!DEFER_LEAF_FORCE.get()) {
                forcedBlobs.incrementAndGet();
                channel.force(true);
            }
        });
    }

    long forcedBlobWrites() {
        return forcedBlobs.get();
    }

    /** Presence is used only to decide whether to build a derived index. Reads still authenticate it. */
    public boolean hasIndex(ContentDigest graph) throws IOException {
        return Files.isRegularFile(pointer(graph), LinkOption.NOFOLLOW_LINKS);
    }

    public boolean hasPreparedIndex(ContentDigest graph) throws IOException {
        var root = verified.get(graph);
        if (root == null) return false;
        try {
            return root.document().equals(rootReference(graph));
        } catch (NoSuchFileException missing) {
            return false;
        } catch (IllegalArgumentException malformed) {
            throw new IOException("GRAPH_PARTITION_ROOT_INVALID", malformed);
        }
    }

    /** Authenticate the root and its canonical rows and indexes before serving a cold archive. */
    public void prepareIndex(ContentDigest graph, BooleanSupplier cancelled) throws IOException {
        root(graph, cancelled);
    }

    /** Authenticated evolution reader, independent of the lightweight discovery manifest. */
    public EvolutionSnapshot openEvolution(ContentDigest graph, BooleanSupplier cancelled) throws IOException {
        final Root pinned;
        try {
            if (!Files.exists(pointer(graph), LinkOption.NOFOLLOW_LINKS))
                throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.MISSING);
            pinned=root(graph,cancelled);
            if (!content.verify(pinned.document(),cancelled))
                throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.CORRUPT);
        } catch (EvolutionSnapshot.ReadFailure failure) { throw failure;
        } catch (IOException failure) { throw evolutionFailure(failure); }
        return new EvolutionSnapshot() {
            public Header header() {
                return new Header(KnowledgeGraph.SCHEMA,graph,
                        new com.evolution.analysis.contract.identity.SnapshotIdentity(pinned.snapshot()),
                        pinned.trees().get("contexts").rows(),pinned.trees().get("evidence").rows());
            }
            public Page<EvolutionSnapshot.Partition> contexts(int size,Optional<String> cursor)throws IOException {
                return rows("contexts","","\uffff",size,cursor,raw->{
                    var context=GraphSnapshotCodec.context(raw);
                    var found=new ArrayList<Entry>();
                    visit(pinned.trees().get("partitions"),context.identity(),context.identity()+"\0",null,found::add,cancelled,0);
                    if(found.size()!=1)throw new IOException("GRAPH_PARTITION_INDEX_SCOPE");
                    var partition=partition(found.getFirst().row());
                    return new EvolutionSnapshot.Partition(context,partition.nodes(),partition.edges());
                });
            }
            public Page<KnowledgeGraph.Node> nodes(String context,int size,Optional<String> cursor)throws IOException {
                String prefix=context+"|";
                return rows("nodes",prefix,upper(prefix),size,cursor,GraphSnapshotCodec::node);
            }
            public Page<KnowledgeGraph.Edge> edges(String context,int size,Optional<String> cursor)throws IOException {
                String prefix=context+"|";
                return rows("edges",prefix,upper(prefix),size,cursor,GraphSnapshotCodec::edge);
            }
            public Page<GraphEvidence> evidence(int size,Optional<String> cursor)throws IOException {
                return rows("evidence","","\uffff",size,cursor,GraphSnapshotCodec::evidence);
            }
            public Optional<GraphEvidence> evidence(ContentDigest id)throws IOException {
                return rows("evidence",id.value(),id.value()+"\0",1,Optional.empty(),GraphSnapshotCodec::evidence)
                        .items().stream().findFirst();
            }
            private <T> Page<T> rows(String tree,String from,String until,int size,Optional<String> cursor,
                                     EvolutionDecoder<T> decode)throws IOException {
                try (var ignored = openLocalPageCache()) {
                    var result=page(pinned.trees().get(tree),from,until,
                            query(graph,"evolution-"+tree,size,List.of(pinned.document(),from,until)),size,cursor,cancelled);
                    var values=new ArrayList<T>();
                    for(var entry:result.items())values.add(decode.decode(entry.row()));
                    return new Page<>(values,result.continuation());
                } catch(IOException failure){throw evolutionFailure(failure);}
            }
        };
    }

    /** Compact captured revision metadata for the exact repository context, after closure revalidation by the caller. */
    public Optional<Map<String,Object>> evolutionRevision(EvolutionSnapshot snapshot, BooleanSupplier cancelled) throws IOException {
        try (var ignored = openLocalPageCache()) {
            var graph = snapshot.header().graph();
            var id = snapshot.header().snapshot().value();
            var rows = new ArrayList<Entry>(1);
            visit(root(graph,cancelled).trees().get("contexts"),id,id+"\0",null,rows::add,cancelled,0);
            if (rows.size()!=1) return Optional.empty();
            var context = GraphSnapshotCodec.context(rows.getFirst().row());
            var evidence = snapshot.evidence(context.evidence());
            if (evidence.isEmpty() || !evidence.orElseThrow().family().equals("repository.snapshot")) return Optional.empty();
            var canonical = evidence.orElseThrow().canonicalJson();
            var provenance = new TreeMap<String,Object>();
            GraphJson.streamObject(new StringReader(canonical),
                    new GraphJson.StreamLimits(canonical.length(),128,Long.MAX_VALUE),(field,member) -> {
                        if (Set.of("revision","dirty","repository").contains(field)) provenance.put(field,member.read());
                        else member.skip();
                    });
            return Optional.of(provenance);
        }
    }

    @FunctionalInterface private interface EvolutionDecoder<T>{T decode(Object row)throws IOException;}
    private static EvolutionSnapshot.ReadFailure evolutionFailure(IOException cause) {
        String code=Objects.toString(cause.getMessage(),"");
        var kind=cause instanceof java.nio.file.AccessDeniedException||code.contains("STORAGE_UNAVAILABLE")||code.contains("AUDIT_UNAVAILABLE")
                ?EvolutionSnapshot.Failure.UNAVAILABLE:code.contains("LIMIT")||code.contains("BUDGET")||code.equals("GRAPH_PARTITION_PAGE_WORKER_REQUIRED")
                ?EvolutionSnapshot.Failure.RESOURCE_LIMIT:EvolutionSnapshot.Failure.CORRUPT;
        return new EvolutionSnapshot.ReadFailure(kind,cause);
    }

    PartitionedGraphStore(Path directory, int rowBytes, int pageWorkers, StreamingContentStore.Force force)
            throws IOException {
        if (rowBytes < 1024 || rowBytes > MAX_ROW_BYTES)
            throw new IllegalArgumentException("GRAPH_PAGE_WORKER_BUDGET");
        if (pageWorkers < 1 || pageWorkers > 8)
            throw new IllegalArgumentException("GRAPH_PAGE_WORKERS_REQUIRED");
        this.directory = StreamingContentStore.directory(directory);
        this.rowBytes = rowBytes;
        this.pageWorkers = pageWorkers;
        content = new StreamingContentStore(this.directory.resolve("content"), 0, force);
    }

    /**
     * Request-local cancellation; no mutable cancellation state is shared across
     * callers.
     */
    public PartitionedGraphReader reader(BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);
        return new PartitionedGraphReader() {
            public Page<Partition> partitions(ContentDigest graph, int size, Optional<String> cursor)
                    throws IOException {
                return partitionPage(graph, size, cursor, cancelled);
            }

            public Page<KnowledgeGraph.Node> nodes(Partition partition, int size, Optional<String> cursor)
                    throws IOException {
                return nodePage(partition, size, cursor, cancelled);
            }

            public Page<KnowledgeGraph.Edge> adjacency(Partition partition, String node, boolean incoming, int size,
                    Optional<String> cursor) throws IOException {
                return adjacencyPage(partition, node, incoming, size, cursor, cancelled);
            }
        };
    }

    public Page<Partition> partitions(ContentDigest graph, int size, Optional<String> cursor) throws IOException {
        return partitionPage(graph, size, cursor, () -> false);
    }

    /** Check that a verified root still has its authenticated pages and partition segments. */
    public boolean verifyPublished(ContentDigest graph, BooleanSupplier cancelled) throws IOException {
        if (!Files.isRegularFile(pointer(graph), LinkOption.NOFOLLOW_LINKS)) return false;
        var root = root(graph, cancelled);
        if (!content.verify(root.document(), cancelled)) return false;
        for (var entry : root.trees().entrySet()) {
            var tree = entry.getValue();
            if (tree.rows() == 0) {
                if (!content.verify(tree.content(), cancelled)) return false;
            } else {
                boolean partitions = entry.getKey().equals("partitions");
                visit(tree, "", "\uffff", null, row -> {
                    if (partitions && !content.verify(partition(row.row()).segment(), cancelled))
                        throw new IOException("GRAPH_PARTITION_SEGMENT_UNAVAILABLE");
                }, cancelled, 0);
            }
        }
        return true;
    }

    public Page<KnowledgeGraph.Node> nodes(Partition partition, int size, Optional<String> cursor) throws IOException {
        return nodePage(partition, size, cursor, () -> false);
    }

    public Page<KnowledgeGraph.Edge> adjacency(Partition partition, String node, boolean incoming, int size,
            Optional<String> cursor) throws IOException {
        return adjacencyPage(partition, node, incoming, size, cursor, () -> false);
    }

    public Optional<Partition> partition(ContentDigest graph, String context) throws IOException {
        try (var ignored = openLocalPageCache()) {
            var root = root(graph, () -> false);
            var found = new ArrayList<Entry>();
            visit(root.trees().get("partitions"), context, context + "\0", null, found::add, () -> false, 0);
            return found.isEmpty() ? Optional.empty() : Optional.of(partition(found.getFirst().row()));
        }
    }

    /** Read one authenticated node by context and identity without materializing the graph. */
    public Optional<KnowledgeGraph.Node> node(ContentDigest graph, String contextId, String nodeId) throws IOException {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(contextId);
        Objects.requireNonNull(nodeId);
        BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
        try (var ignored = openLocalPageCache()) {
            var root = root(graph, cancelled);
            String targetKey = key(contextId, nodeId);
            var found = new ArrayList<Entry>(1);
            visit(root.trees().get("nodes"), targetKey, targetKey + "\0", null, found::add, cancelled, 0);
            if (found.size() > 1)
                throw new IOException("GRAPH_PARTITION_DUPLICATE_NODE");
            return found.isEmpty() ? Optional.empty() : Optional.of(GraphSnapshotCodec.node(found.getFirst().row()));
        }
    }

    /** Reconstitutes one single context subgraph for bounded partition replay (e.g. policy evaluation). */
    public Optional<KnowledgeGraph> contextGraph(ContentDigest graph, String contextId, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(contextId);
        Objects.requireNonNull(cancelled);
        if (!Files.isRegularFile(pointer(graph), LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try (var ignored = openLocalPageCache()) {
            var root = root(graph, cancelled);
            var ctxRows = new ArrayList<KnowledgeGraph.Context>();
            visit(root.trees().get("contexts"), contextId, contextId + "\0", null,
                    entry -> ctxRows.add(GraphSnapshotCodec.context(entry.row())), cancelled, 0);
            if (ctxRows.size() != 1) return Optional.empty();
            var ctx = ctxRows.getFirst();
            String prefix = contextId + "|";
            String end = upper(prefix);
            var nodes = new ArrayList<KnowledgeGraph.Node>();
            var neededEvidence = new TreeSet<ContentDigest>();
            neededEvidence.add(ctx.evidence());
            visit(root.trees().get("nodes"), prefix, end, null, entry -> {
                var n = GraphSnapshotCodec.node(entry.row());
                nodes.add(n);
                neededEvidence.addAll(n.evidence());
            }, cancelled, 0);
            var edges = new ArrayList<KnowledgeGraph.Edge>();
            visit(root.trees().get("edges"), prefix, end, null, entry -> {
                var e = GraphSnapshotCodec.edge(entry.row());
                edges.add(e);
                neededEvidence.addAll(e.evidence());
            }, cancelled, 0);
            var evidenceList = new ArrayList<GraphEvidence>(neededEvidence.size());
            var evTree = root.trees().get("evidence");
            for (var evId : neededEvidence) {
                visit(evTree, evId.value(), evId.value() + "\0", null,
                        entry -> evidenceList.add(GraphSnapshotCodec.evidence(entry.row())), cancelled, 0);
            }
            return Optional.of(new KnowledgeGraph(ctx.snapshot(), List.of(ctx), nodes, edges, evidenceList));
        }
    }

    public Optional<GraphManifest> manifest(ContentDigest graph) throws IOException {
        var pointer = pointer(graph);
        if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try (var ignored = openLocalPageCache()) {
            Map<String, Object> reference;
            try (var input = Files.newInputStream(pointer, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(16385);
                if (bytes.length > 16384) throw new IOException("GRAPH_PARTITION_ROOT_INVALID");
                reference = object(parse(utf8(bytes)));
            }
            fields(reference, "format", "graph", "document");
            if (!FORMAT.equals(reference.get("format")) || !graph.value().equals(reference.get("graph")))
                throw new IOException("GRAPH_PARTITION_ROOT_SCOPE");
            var document = handle(reference.get("document"));
            var cached = verified.get(graph);
            Root root;
            if (cached != null && cached.document().equals(document)) {
                root = cached;
            } else {
                var value = object(read(document, () -> false));
                fields(value, "format", "graph", "snapshot", "trees");
                var trees = new TreeMap<String, Tree>();
                object(value.get("trees")).forEach((name, tree) -> trees.put(name, tree(tree)));
                root = new Root(document, graph, text(value.get("snapshot")), Map.copyOf(trees));
            }
            var contexts = new ArrayList<KnowledgeGraph.Context>();
            visit(root.trees().get("contexts"), "", "\uffff", null,
                    entry -> contexts.add(GraphSnapshotCodec.context(entry.row())), () -> false, 0);
            long nodes = root.trees().get("nodes").rows();
            long edges = root.trees().get("edges").rows();
            long evidence = root.trees().get("evidence").rows();
            ContentDigest semId = semanticIdentity(root, () -> false);
            return Optional.of(new GraphManifest(KnowledgeGraph.SCHEMA, graph, semId,
                    new com.evolution.analysis.contract.identity.SnapshotIdentity(root.snapshot()), contexts,
                    (int) Math.min(nodes, Integer.MAX_VALUE),
                    (int) Math.min(edges, Integer.MAX_VALUE),
                    (int) Math.min(evidence, Integer.MAX_VALUE)));
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<ContentDigest, ContentDigest> semanticIdentityCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Computes the exact {@link KnowledgeGraph#semanticIdentity()} digest by streaming authenticated
     * B-tree pages (matching {@link FileGraphStore} and {@link KnowledgeGraph} bit-for-bit).
     */
    private ContentDigest semanticIdentity(Root root, BooleanSupplier cancelled) throws IOException {
        ContentDigest cached = semanticIdentityCache.get(root.graph());
        if (cached != null) return cached;
        var overrides = new HashMap<ContentDigest, ContentDigest>();
        visit(root.trees().get("evidence"), "", "\uffff", null, entry -> {
            var ev = GraphSnapshotCodec.evidence(entry.row());
            ContentDigest sem = ev.semanticIdentity();
            if (!sem.equals(ev.identity())) {
                overrides.put(ev.identity(), sem);
            }
        }, cancelled, 0);
        Map<ContentDigest, ContentDigest> ovMap = overrides.isEmpty() ? null : overrides;
        var md = CanonicalJson.borrowSha256();
        try {
            try (var out = CanonicalJson.newUtf8DigestWriter(md)) {
                out.append("[\"");
                out.append(KnowledgeGraph.SCHEMA);
                out.append("\",\"semantic-replay:v1\",");
                com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(root.snapshot(), out);
                out.append(",[");
                boolean[] first = { true };
                visit(root.trees().get("contexts"), "", "\uffff", null, entry -> {
                    var c = GraphSnapshotCodec.context(entry.row());
                    if (!first[0]) out.append(',');
                    first[0] = false;
                    out.append('[');
                    com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(c.identity(), out);
                    out.append(',');
                    com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(c.snapshot().value(), out);
                    out.append(',');
                    if (c.analysis().isPresent()) com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(c.analysis().orElseThrow().value(), out);
                    else out.append("null");
                    out.append(',');
                    ContentDigest ev = ovMap == null ? c.evidence() : ovMap.getOrDefault(c.evidence(), c.evidence());
                    com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(ev.value(), out);
                    out.append(']');
                }, cancelled, 0);
                out.append("],[");
                first[0] = true;
                visit(root.trees().get("nodes"), "", "\uffff", null, entry -> {
                    var n = GraphSnapshotCodec.node(entry.row());
                    if (!first[0]) out.append(',');
                    first[0] = false;
                    out.append('[');
                    com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(n.identity(), out);
                    out.append(',');
                    com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(n.context(), out);
                    out.append(',');
                    com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(n.kind(), out);
                    out.append(',');
                    writeSemanticSupportList(n.evidence(), ovMap, out);
                    out.append(']');
                }, cancelled, 0);
                out.append("],[");
                first[0] = true;
                visit(root.trees().get("edges"), "", "\uffff", null, entry -> {
                    var e = GraphSnapshotCodec.edge(entry.row());
                    if (!first[0]) out.append(',');
                    first[0] = false;
                    out.append('[');
                    com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(e.identity(), out);
                    out.append(',');
                    com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(e.context(), out);
                    out.append(',');
                    com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(e.kind(), out);
                    out.append(',');
                    com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(e.source(), out);
                    out.append(',');
                    com.evolution.analysis.graph.GraphCanonicalWriter.writeTarget(e.target(), out);
                    out.append(',');
                    writeSemanticSupportList(e.evidence(), ovMap, out);
                    out.append(',');
                    if (e.region().isPresent()) com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(e.region().orElseThrow(), out);
                    else out.append("null");
                    out.append(']');
                }, cancelled, 0);
                out.append("],[");
                var uniqueEvidence = new TreeSet<ContentDigest>();
                visit(root.trees().get("evidence"), "", "\uffff", null, entry -> {
                    var ev = GraphSnapshotCodec.evidence(entry.row());
                    uniqueEvidence.add(ovMap == null ? ev.identity() : ovMap.getOrDefault(ev.identity(), ev.identity()));
                }, cancelled, 0);
                boolean firstEv = true;
                for (var id : uniqueEvidence) {
                    if (!firstEv) out.append(',');
                    firstEv = false;
                    com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(id.value(), out);
                }
                out.append("]]");
            }
            ContentDigest computed = new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest()));
            semanticIdentityCache.put(root.graph(), computed);
            return computed;
        } finally {
            CanonicalJson.releaseSha256(md);
        }
    }

    private static void writeSemanticSupportList(List<ContentDigest> rows, Map<ContentDigest, ContentDigest> overrides,
            Appendable out) throws IOException {
        out.append('[');
        if (overrides == null) {
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) out.append(',');
                com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(rows.get(i).value(), out);
            }
        } else {
            var unique = new TreeSet<ContentDigest>();
            for (var d : rows) unique.add(overrides.getOrDefault(d, d));
            boolean first = true;
            for (var d : unique) {
                if (!first) out.append(',');
                first = false;
                com.evolution.analysis.graph.GraphCanonicalWriter.appendJsonString(d.value(), out);
            }
        }
        out.append(']');
    }

    /**
     * Read one authenticated provider record without loading the legacy snapshot.
     */
    public Optional<GraphEvidence> evidence(ContentDigest graph, ContentDigest identity) throws IOException {
        BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
        try (var ignored = openLocalPageCache()) {
            var root = root(graph, cancelled);
            var found = new ArrayList<Entry>();
            visit(root.trees().get("evidence"), identity.value(), identity.value() + "\0", null, found::add, cancelled, 0);
            if (found.size() > 1)
                throw new IOException("GRAPH_PARTITION_DUPLICATE_EVIDENCE");
            return found.isEmpty() ? Optional.empty() : Optional.of(GraphSnapshotCodec.evidence(found.getFirst().row()));
        }
    }

    /**
     * Build directly into a bounded disk workspace. No repository-wide graph
     * objects or
     * adjacency maps are constructed. Both the original snapshot and authenticated
     * pages
     * retain the exact v1 digest and undergo the same reference audit as imported
     * snapshots.
     */
    public Publication build(CanonicalGraphInput input, Path snapshots, BooleanSupplier cancelled) throws IOException {
        return build(input, snapshots, cancelled, workspace -> {
        });
    }

    @FunctionalInterface
    public interface Contribution extends GraphProjectionStore.Contribution {
        void project(GraphProjectionStore workspace) throws IOException;
    }

    /**
     * Neutral inventory/native roots need not fabricate a legacy Java
     * RepositorySnapshot.
     */
    public Publication build(com.evolution.analysis.contract.identity.SnapshotIdentity snapshot, Path snapshots,
            BooleanSupplier cancelled, Contribution contribution) throws IOException {
        return assemble(snapshot, snapshots, cancelled, contribution);
    }

    public Publication build(CanonicalGraphInput input, Path snapshots, BooleanSupplier cancelled,
            Contribution contribution) throws IOException {
        return build(input, snapshots, cancelled, List.of(), contribution);
    }

    public Publication build(CanonicalGraphInput input, Path snapshots, BooleanSupplier cancelled,
            Collection<com.evolution.analysis.evidence.EvidenceAcquisitionLedger> ledgers, Contribution contribution)
            throws IOException {
        return build(input, snapshots, cancelled, ledgers, contribution, false);
    }

    public Publication build(CanonicalGraphInput input, Path snapshots, BooleanSupplier cancelled,
            Collection<com.evolution.analysis.evidence.EvidenceAcquisitionLedger> ledgers, Contribution contribution,
            boolean fastArchitecture)
            throws IOException {
        return build(input, snapshots, cancelled, ledgers, contribution, fastArchitecture, null);
    }

    public Publication build(CanonicalGraphInput input, Path snapshots, BooleanSupplier cancelled,
            Collection<com.evolution.analysis.evidence.EvidenceAcquisitionLedger> ledgers, Contribution contribution,
            boolean fastArchitecture, IncrementalGraphProjector projector)
            throws IOException {
        return build(input, snapshots, cancelled, ledgers, contribution, fastArchitecture, projector,
                (java.util.function.Function<String, ContentDigest>) null);
    }

    public Publication build(CanonicalGraphInput input, Path snapshots, BooleanSupplier cancelled,
            Collection<com.evolution.analysis.evidence.EvidenceAcquisitionLedger> ledgers, Contribution contribution,
            boolean fastArchitecture, IncrementalGraphProjector projector, ContentDigest polyglotProjectionDigest)
            throws IOException {
        return build(input, snapshots, cancelled, ledgers, contribution, fastArchitecture, projector,
                polyglotProjectionDigest == null ? null : ignored -> polyglotProjectionDigest);
    }

    public Publication build(CanonicalGraphInput input, Path snapshots, BooleanSupplier cancelled,
            Collection<com.evolution.analysis.evidence.EvidenceAcquisitionLedger> ledgers, Contribution contribution,
            boolean fastArchitecture, IncrementalGraphProjector projector,
            java.util.function.Function<String, ContentDigest> modulePolyglotDigest)
            throws IOException {
        return assemble(input.repository().snapshot().identity(), snapshots, cancelled, workspace -> {
            new CanonicalGraphBuilder().project(input, workspace, ledgers, fastArchitecture, projector,
                    modulePolyglotDigest);
            contribution.project(workspace);
        });
    }

    /**
     * Compatibility entry for an already validated graph; additive providers still
     * spill to disk.
     */
    public Publication build(KnowledgeGraph graph, Path snapshots, BooleanSupplier cancelled, Contribution contribution)
            throws IOException {
        return assemble(graph.snapshot(), snapshots, cancelled, workspace -> {
            var contexts = workspace.records("contexts", GraphSnapshotCodec::context);
            for (var row : graph.contexts())
                contexts.put(row.identity(), row);
            var nodes = workspace.records("nodes", GraphSnapshotCodec::node);
            for (var row : graph.nodes())
                nodes.put(key(row.context(), row.identity()), row);
            var edges = workspace.records("edges", GraphSnapshotCodec::edge);
            for (var row : graph.edges())
                edges.put(key(row.context(), row.identity()), row);
            var evidence = workspace.records("evidence", GraphSnapshotCodec::evidence);
            for (var row : graph.evidence())
                evidence.put(row.identity().value(), row);
            contribution.project(workspace);
        });
    }

    private Publication assemble(com.evolution.analysis.contract.identity.SnapshotIdentity snapshot, Path snapshots,
            BooleanSupplier cancelled, Contribution contribution) throws IOException {
        check(cancelled);
        snapshots = StreamingContentStore.directory(snapshots);
        StreamingContentStore.directory(directory);
        Path scratch = Files.createTempFile(directory, "graph-projection-", ".sqlite");
        try (var cleanup = new TemporaryFiles(scratch)) {
            Path pending = cleanup.add(Files.createTempFile(snapshots, "graph-snapshot-", ".part"));
            try (Connection database = DriverManager.getConnection("jdbc:sqlite:" + scratch)) {
                try (var sql = database.createStatement()) {
                    sql.execute("PRAGMA journal_mode=OFF");
                    sql.execute("PRAGMA synchronous=OFF");
                    sql.execute("PRAGMA temp_store=MEMORY");
                    sql.execute("PRAGMA cache_size=-65536");
                    sql.execute(
                            "CREATE TABLE rows(space TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,kind TEXT,PRIMARY KEY(space,k)) WITHOUT ROWID");
                }
                database.setAutoCommit(false);
                try (var workspace = new SqliteGraphProjection(database, rowBytes, cancelled)) {
                    contribution.project(workspace);
                    workspace.flush();
                    var gaps = workspace.gapCounts();
                    var contexts = new ArrayList<KnowledgeGraph.Context>(
                            workspace.records("contexts", GraphSnapshotCodec::context).values());
                    var kinds = new TreeMap<String, Long>();
                    boolean runPageAudit = Boolean.getBoolean("evo.graph.auditOnPublish");
                    int auditSeenCap = defaultAuditSeenCap();
                    var contextIds = runPageAudit ? null : new HashSet<String>();
                    var evidenceSeen = runPageAudit ? null : new HashSet<String>();
                    var nodeSeen = runPageAudit ? null : new HashSet<String>();
                    try (var existsStmt = runPageAudit ? null
                            : database.prepareStatement("SELECT 1 FROM rows WHERE space=? AND k=?")) {
                        if (!runPageAudit) {
                            auditContextsAndNodes(database, workspace, snapshot.value(), cancelled,
                                    existsStmt, contextIds, nodeSeen, evidenceSeen, auditSeenCap);
                        }
                        try (var edgeQuery = database.prepareStatement("SELECT payload FROM rows WHERE space='edges'");
                                var edgeRows = edgeQuery.executeQuery();
                                var insert = database.prepareStatement("INSERT INTO rows(space,k,payload) VALUES(?,?,?)")) {
                            int batched = 0;
                            while (edgeRows.next()) {
                                check(cancelled);
                                var edge = GraphSnapshotCodec.edge(parse(workspace.decoded(edgeRows.getString(1))));
                                if (!runPageAudit) {
                                    auditEdge(edge, workspace, existsStmt, contextIds, nodeSeen, evidenceSeen, auditSeenCap);
                                }
                                kinds.merge(edge.kind(), 1L, Long::sum);
                                String id = key(edge.context(), edge.identity());
                                addInsertBatch(workspace, insert, "outgoing", key(edge.context(), edge.source(), edge.identity()),
                                        id, cancelled);
                                if (++batched == 1024) { insert.executeBatch(); batched = 0; }
                                for (String target : edge.target().identities()) {
                                    addInsertBatch(workspace, insert, "incoming", key(edge.context(), target, edge.identity()), id,
                                            cancelled);
                                    if (++batched == 1024) { insert.executeBatch(); batched = 0; }
                                }
                            }
                            if (batched > 0) insert.executeBatch();
                        }
                    }
                    workspace.flush();
                    database.commit();
                    var digest = StreamingContentStore.sha256();
                    ContentDigest identity;
                    ContentDigest frameDigest;
                    var frame = StreamingContentStore.sha256();
                    var prebuiltTrees = new TreeMap<String, Tree>();
                    try (var workers = new BoundedTasks(pageWorkers, pageWorkers, "evo-graph-page", cancelled)) {
                        try (var channel = java.nio.channels.FileChannel.open(pending, StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS)) {
                            var destination = new DigestOutputStream(
                                    new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(channel), 65536),
                                    frame);
                            var writer = new BufferedWriter(new OutputStreamWriter(destination, StandardCharsets.UTF_8), 65536);
                            writer.write("{\"format\":");
                            CanonicalJson.writeTo(GraphSnapshotCodec.FORMAT, writer);
                            writer.write(",\"graph\":");
                            writer.flush();
                            var graphWriter = new BufferedWriter(new OutputStreamWriter(
                                    new DigestOutputStream(destination, digest), StandardCharsets.UTF_8), 65536);
                            writeGraph(database, workspace, snapshot.value(), graphWriter, cancelled, workers, prebuiltTrees);
                            graphWriter.flush();
                            identity = new ContentDigest("sha256:" + HexFormat.of().formatHex(digest.digest()));
                            writer.write(",\"identity\":");
                            CanonicalJson.writeTo(identity, writer);
                            writer.write("}");
                            writer.flush();
                            destination.flush();
                            channel.force(true);
                        }
                        // Audits endpoints, evidence, contexts and both adjacency indexes before
                        // publication.
                        frameDigest = new ContentDigest("sha256:" + HexFormat.of().formatHex(frame.digest()));
                        publish(database, workspace, identity, snapshot.value(), cancelled,
                                Optional.of(frameDigest), false, workers, prebuiltTrees, !runPageAudit);
                    }
                    Path target = snapshots.resolve(identity.value().substring(7) + ".graph.json");
                    StreamingContentStore.safe(target);
                    check(cancelled);
                    try {
                        StreamingContentStore.createLinkWithRetry(target, pending);
                    } catch (FileAlreadyExistsException existing) {
                        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                                || Files.size(target) != Files.size(pending) || !hash(target).equals(frameDigest))
                            throw new IOException("GRAPH_SNAPSHOT_CONFLICT");
                    }
                    return new Publication(identity, snapshot, contexts, total(database, "nodes"),
                            total(database, "edges"), total(database, "evidence"), kinds, gaps.reasons(), gaps.nodes());
                }
            }
        } catch (UncheckedIOException failure) {
            throw unwrap(failure);
        } catch (SQLException failure) {
            throw new IOException("GRAPH_PROJECTION_STORAGE_UNAVAILABLE", failure);
        }
    }

    private static ContentDigest hash(Path file) throws IOException {
        var digest = StreamingContentStore.sha256();
        try (var in = new DigestInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return new ContentDigest("sha256:" + HexFormat.of().formatHex(digest.digest()));
    }

    private static long total(Connection database, String space) throws SQLException {
        try (var query = database.prepareStatement("SELECT COUNT(*) FROM rows WHERE space=?")) {
            query.setString(1, space);
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    private void writeGraph(Connection database, SqliteGraphProjection workspace, String snapshot, Writer writer,
            BooleanSupplier cancelled, BoundedTasks workers, Map<String, Tree> builtTrees) throws SQLException, IOException {
        writer.write("{");
        boolean first = true;
        for (String space : List.of("contexts", "edges", "evidence", "nodes")) {
            if (!first)
                writer.write(",");
            first = false;
            CanonicalJson.writeTo(space, writer);
            writer.write(":[");
            boolean member = false;
            var pages = new TreePages(cancelled, workers);
            try (var query = database.prepareStatement("SELECT k,payload FROM rows WHERE space=? ORDER BY k")) {
                query.setString(1, space);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        check(cancelled);
                        String k = rows.getString(1);
                        String decoded = workspace.decoded(rows.getString(2));
                        if (member)
                            writer.write(",");
                        member = true;
                        writer.write(decoded);
                        pages.add(new EncodedEntry(k, decoded));
                    }
                }
            }
            builtTrees.put(space, pages.finish());
            writer.write("]");
        }
        writer.write(",\"schema\":");
        CanonicalJson.writeTo(KnowledgeGraph.SCHEMA, writer);
        writer.write(",\"snapshot\":");
        CanonicalJson.writeTo(snapshot, writer);
        writer.write("}");
    }

    public void save(KnowledgeGraph graph, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(graph);
        check(cancelled);
        var pointer = pointer(graph.identity());
        if (Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
            root(graph.identity(), cancelled);
            return;
        }
        // A KnowledgeGraph has already validated every reference, ordered its rows and
        // built both adjacency indexes. Only projections and imports need external sorting.
        try (var workers = new BoundedTasks(pageWorkers, pageWorkers, "evo-graph-page", cancelled)) {
            var trees = new TreeMap<String, Tree>();
            var sizes = new HashMap<String, long[]>();
            graph.contexts().forEach(context -> sizes.put(context.identity(), new long[2]));
            trees.put("contexts", build(graph.contexts().stream(), KnowledgeGraph.Context::identity,
                    Function.identity(), cancelled, workers));
            trees.put("nodes", build(graph.nodes().stream(), row -> {
                sizes.get(row.context())[0]++;
                return key(row.context(), row.identity());
            }, Function.identity(), cancelled, workers));
            trees.put("edges", build(graph.edges().stream(), row -> {
                sizes.get(row.context())[1]++;
                return key(row.context(), row.identity());
            }, Function.identity(), cancelled, workers));
            for (boolean incoming : List.of(false, true))
                trees.put(incoming ? "incoming" : "outgoing", build(graph.adjacency(incoming),
                        row -> key(row.edge().context(), row.node(), row.edge().identity()),
                        row -> key(row.edge().context(), row.edge().identity()), cancelled, workers));
            trees.put("evidence", build(graph.evidence().stream(), row -> row.identity().value(),
                    Function.identity(), cancelled, workers));
            var partitions = new TreePages(cancelled, workers);
            for (var context : graph.contexts()) {
                var counts = sizes.get(context.identity());
                var row = partition(graph.identity(), context.identity(), counts[0], counts[1], trees, cancelled);
                partitions.add(new EncodedEntry(context.identity(), CanonicalJson.write(row)));
            }
            trees.put("partitions", partitions.finish());
            publishRoot(rootDocument(graph.identity(), graph.snapshot().value(), trees, cancelled), cancelled);
        } catch (UncheckedIOException failure) {
            throw unwrap(failure);
        }
    }

    /**
     * Disk migration of the canonical v1 frame. Neither JSON document nor
     * KnowledgeGraph is
     * retained; the declared identity, exact canonical bytes and structural
     * references must agree.
     */
    public void importSnapshot(Path source, ContentDigest expected, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        StreamingContentStore.safe(source);
        long bytes = Files.size(source);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || bytes < 1)
            throw new IOException("GRAPH_PARTITION_INPUT_UNAVAILABLE");
        if (Files.exists(pointer(expected), LinkOption.NOFOLLOW_LINKS)) {
            root(expected, cancelled);
            return;
        }
        StreamingContentStore.directory(directory);
        Path scratch = Files.createTempFile(directory, "graph-import-", ".sqlite");
        try (var cleanup = new TemporaryFiles(scratch);
                Connection database = DriverManager.getConnection("jdbc:sqlite:" + scratch)) {
            try (var sql = database.createStatement()) {
                sql.execute("PRAGMA journal_mode=OFF");
                sql.execute("PRAGMA synchronous=OFF");
                sql.execute("PRAGMA temp_store=MEMORY");
                sql.execute("PRAGMA cache_size=-65536");
                sql.execute(
                        "CREATE TABLE rows(space TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,kind TEXT,PRIMARY KEY(space,k)) WITHOUT ROWID");
            }
            database.setAutoCommit(false);
            try (var workspace = new SqliteGraphProjection(database, rowBytes, cancelled)) {
                var header = new TreeMap<String, Object>();
                var body = new TreeMap<String, Object>();
                var members = new HashSet<String>();
                var digest = MessageDigest.getInstance("SHA-256");
                int[] importBatch = { 0 };
                try (var insert = database.prepareStatement("INSERT INTO rows(space,k,payload) VALUES(?,?,?)");
                        var input = new DigestInputStream(
                                new BufferedInputStream(Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS), 65536),
                                digest);
                        var reader = new InputStreamReader(input,
                                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                        .onUnmappableCharacter(CodingErrorAction.REPORT))) {
                    GraphJson.streamObject(reader,
                            new GraphJson.StreamLimits(bytes, 128, bytes, Math.toIntExact(Math.min(bytes, pageByteLimit()))),
                            (name, member) -> {
                                check(cancelled);
                                if (name.equals("format") || name.equals("identity"))
                                    header.put(name, member.read());
                                else if (name.equals("graph"))
                                    member.object((space, values) -> {
                                        members.add(space);
                                        if (space.equals("schema") || space.equals("snapshot")) {
                                            body.put(space, values.read());
                                            return;
                                        }
                                        if (!Set.of("contexts", "nodes", "edges", "evidence").contains(space))
                                            throw new IOException("GRAPH_PARTITION_INPUT_FIELDS");
                                        values.array(raw -> {
                                            check(cancelled);
                                            Object value = switch (space) {
                                                case "contexts" -> GraphSnapshotCodec.context(raw);
                                                case "nodes" -> GraphSnapshotCodec.node(raw);
                                                case "edges" -> GraphSnapshotCodec.edge(raw);
                                                default -> GraphSnapshotCodec.evidence(raw);
                                            };
                                            String key = switch (value) {
                                                case KnowledgeGraph.Context c -> c.identity();
                                                case KnowledgeGraph.Node n -> key(n.context(), n.identity());
                                                case KnowledgeGraph.Edge e -> key(e.context(), e.identity());
                                                case GraphEvidence e -> e.identity().value();
                                                default -> throw new AssertionError();
                                            };
                                            try {
                                                addInsertBatch(workspace, insert, space, key, value, cancelled);
                                                if (++importBatch[0] >= 1024) {
                                                    insert.executeBatch();
                                                    importBatch[0] = 0;
                                                }
                                                if (value instanceof KnowledgeGraph.Edge edge) {
                                                    addInsertBatch(workspace, insert, "outgoing",
                                                            key(edge.context(), edge.source(), edge.identity()), key,
                                                            cancelled);
                                                    if (++importBatch[0] >= 1024) {
                                                        insert.executeBatch();
                                                        importBatch[0] = 0;
                                                    }
                                                    for (String target : edge.target().identities()) {
                                                        addInsertBatch(workspace, insert, "incoming",
                                                                key(edge.context(), target, edge.identity()), key,
                                                                cancelled);
                                                        if (++importBatch[0] >= 1024) {
                                                            insert.executeBatch();
                                                            importBatch[0] = 0;
                                                        }
                                                    }
                                                }
                                            } catch (SQLException failure) {
                                                throw new IOException("GRAPH_PARTITION_DUPLICATE_OR_INVALID_ROW",
                                                        failure);
                                            }
                                        });
                                    });
                                else
                                    throw new IOException("GRAPH_PARTITION_INPUT_FIELDS");
                            });
                    if (importBatch[0] > 0) {
                        try {
                            insert.executeBatch();
                        } catch (SQLException failure) {
                            throw new IOException("GRAPH_PARTITION_DUPLICATE_OR_INVALID_ROW", failure);
                        }
                    }
                }
                if (!header.keySet().equals(Set.of("format", "identity"))
                        || !GraphSnapshotCodec.FORMAT.equals(header.get("format"))
                        || !expected.value().equals(header.get("identity"))
                        || !members.equals(Set.of("schema", "snapshot", "contexts", "nodes", "edges", "evidence"))
                        || !KnowledgeGraph.SCHEMA.equals(body.get("schema")))
                    throw new IOException("GRAPH_PARTITION_INPUT_SCOPE");
                workspace.flush();
                database.commit();
                publish(database, workspace, expected, text(body.get("snapshot")), cancelled,
                        Optional.of(new ContentDigest("sha256:" + HexFormat.of().formatHex(digest.digest()))), true);
            }
        } catch (UncheckedIOException failure) {
            throw unwrap(failure);
        } catch (SQLException failure) {
            throw new IOException("GRAPH_PARTITION_STORAGE_UNAVAILABLE", failure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public boolean hasPublishedRoot(ContentDigest graph) {
        try {
            Path p = pointer(Objects.requireNonNull(graph));
            return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ignored) {
            return false;
        }
    }

    private void publish(Connection database, SqliteGraphProjection workspace, ContentDigest graph, String snapshot,
            BooleanSupplier cancelled, Optional<ContentDigest> frame, boolean verifyFromPages) throws SQLException, IOException {
        try (var workers = new BoundedTasks(pageWorkers, pageWorkers, "evo-graph-page", cancelled)) {
            publish(database, workspace, graph, snapshot, cancelled, frame, verifyFromPages, workers, Map.of(), false);
        }
    }

    private void publish(Connection database, SqliteGraphProjection workspace, ContentDigest graph, String snapshot,
            BooleanSupplier cancelled, Optional<ContentDigest> frame, boolean verifyFromPages,
            BoundedTasks workers, Map<String, Tree> prebuiltTrees, boolean alreadyAudited) throws SQLException, IOException {
        boolean runPageAudit = verifyFromPages || Boolean.getBoolean("evo.graph.auditOnPublish");
        if (!runPageAudit && !alreadyAudited) {
            auditDatabaseReferences(database, workspace, snapshot, cancelled);
        }
        var trees = new TreeMap<String, Tree>(prebuiltTrees);
        for (String space : List.of("contexts", "nodes", "edges", "outgoing", "incoming", "evidence")) {
            if (!trees.containsKey(space)) {
                trees.put(space, build(database, workspace, space, cancelled, workers));
            }
        }
        try (var query = database.prepareStatement("SELECT payload FROM rows WHERE space='contexts' ORDER BY k");
                var contexts = query.executeQuery();
                var insert = database.prepareStatement("INSERT INTO rows(space,k,payload) VALUES(?,?,?)")) {
            while (contexts.next()) {
                var context = GraphSnapshotCodec.context(parse(workspace.decoded(contexts.getString(1))));
                long nodes = count(database, "nodes", context.identity()),
                        edges = count(database, "edges", context.identity());
                insert(workspace, insert, "partitions", context.identity(),
                        partition(graph, context.identity(), nodes, edges, trees, cancelled), cancelled);
            }
        }
        workspace.flush();
        database.commit();
        trees.put("partitions", build(database, workspace, "partitions", cancelled, workers));
        var root = rootDocument(graph, snapshot, trees, cancelled);
        if (runPageAudit && !graph.equals(canonicalDigest(root, cancelled, true, frame)))
            throw new IOException("GRAPH_PARTITION_DIGEST_MISMATCH");
        publishRoot(root, cancelled);
    }

    private static int defaultAuditSeenCap() {
        long maxMem = Runtime.getRuntime().maxMemory();
        if (maxMem >= 2L * 1024 * 1024 * 1024) return 1_048_576;
        if (maxMem >= 1024L * 1024 * 1024) return 524_288;
        return 262_144;
    }

    private void auditContextsAndNodes(Connection database, SqliteGraphProjection workspace, String snapshot,
            BooleanSupplier cancelled, PreparedStatement existsStmt, Set<String> contextIds,
            Set<String> nodeSeen, Set<String> evidenceSeen, int seenCap) throws SQLException, IOException {
        try (var q = database.prepareStatement("SELECT payload FROM rows WHERE space='contexts'");
                var rs = q.executeQuery()) {
            while (rs.next()) {
                check(cancelled);
                var ctx = GraphSnapshotCodec.context(parse(workspace.decoded(rs.getString(1))));
                if (!ctx.snapshot().value().equals(snapshot))
                    throw new IOException("GRAPH_PARTITION_FOREIGN_SNAPSHOT");
                contextIds.add(ctx.identity());
                if (!existsInRows(workspace, existsStmt, evidenceSeen, "evidence", ctx.evidence().value(), seenCap))
                    throw new IOException("GRAPH_PARTITION_DANGLING_REFERENCE");
            }
        }
        try (var q = database.prepareStatement("SELECT k,payload FROM rows WHERE space='nodes'");
                var rs = q.executeQuery()) {
            while (rs.next()) {
                check(cancelled);
                String nodeKey = rs.getString(1);
                var node = GraphSnapshotCodec.node(parse(workspace.decoded(rs.getString(2))));
                if (!contextIds.contains(node.context()))
                    throw new IOException("GRAPH_PARTITION_DANGLING_REFERENCE");
                for (var ev : node.evidence()) {
                    if (!existsInRows(workspace, existsStmt, evidenceSeen, "evidence", ev.value(), seenCap))
                        throw new IOException("GRAPH_PARTITION_DANGLING_REFERENCE");
                }
                if (nodeSeen.size() < seenCap) {
                    nodeSeen.add(nodeKey);
                }
            }
        }
    }

    private void auditEdge(KnowledgeGraph.Edge edge, SqliteGraphProjection workspace, PreparedStatement existsStmt,
            Set<String> contextIds, Set<String> nodeSeen, Set<String> evidenceSeen, int seenCap)
            throws SQLException, IOException {
        if (!contextIds.contains(edge.context()))
            throw new IOException("GRAPH_PARTITION_DANGLING_REFERENCE");
        if (!existsInRows(workspace, existsStmt, nodeSeen, "nodes", key(edge.context(), edge.source()), seenCap))
            throw new IOException("GRAPH_PARTITION_DANGLING_REFERENCE");
        for (String target : edge.target().identities()) {
            if (!existsInRows(workspace, existsStmt, nodeSeen, "nodes", key(edge.context(), target), seenCap))
                throw new IOException("GRAPH_PARTITION_DANGLING_REFERENCE");
        }
        if (edge.region().isPresent()
                && !existsInRows(workspace, existsStmt, nodeSeen, "nodes", key(edge.context(), edge.region().orElseThrow()), seenCap))
            throw new IOException("GRAPH_PARTITION_DANGLING_REFERENCE");
        for (var ev : edge.evidence()) {
            if (!existsInRows(workspace, existsStmt, evidenceSeen, "evidence", ev.value(), seenCap))
                throw new IOException("GRAPH_PARTITION_DANGLING_REFERENCE");
        }
    }

    private void auditDatabaseReferences(Connection database, SqliteGraphProjection workspace, String snapshot,
            BooleanSupplier cancelled) throws SQLException, IOException {
        int seenCap = defaultAuditSeenCap();
        var contextIds = new HashSet<String>();
        var evidenceSeen = new HashSet<String>();
        var nodeSeen = new HashSet<String>();
        try (var existsStmt = database.prepareStatement("SELECT 1 FROM rows WHERE space=? AND k=?")) {
            auditContextsAndNodes(database, workspace, snapshot, cancelled, existsStmt, contextIds, nodeSeen, evidenceSeen, seenCap);
            try (var q = database.prepareStatement("SELECT payload FROM rows WHERE space='edges'");
                    var rs = q.executeQuery()) {
                while (rs.next()) {
                    check(cancelled);
                    var edge = GraphSnapshotCodec.edge(parse(workspace.decoded(rs.getString(1))));
                    auditEdge(edge, workspace, existsStmt, contextIds, nodeSeen, evidenceSeen, seenCap);
                }
            }
        }
    }

    private static boolean existsInRows(SqliteGraphProjection workspace, PreparedStatement existsStmt,
            Set<String> cache, String space, String key, int seenCap) throws SQLException {
        if (workspace.hasKnownKey(space, key) || cache.contains(key))
            return true;
        existsStmt.setString(1, space);
        existsStmt.setString(2, key);
        try (var rs = existsStmt.executeQuery()) {
            if (!rs.next())
                return false;
        }
        if (cache.size() < seenCap)
            cache.add(key);
        return true;
    }

    private Partition partition(ContentDigest graph, String context, long nodes, long edges, Map<String, Tree> trees,
            BooleanSupplier cancelled) throws IOException {
        var segment = put(Map.of("schema", "graph.partition-segment:m8.4-v1", "graph", graph, "context", context,
                "nodes", nodes, "edges", edges, "indexes", Map.of("nodes", trees.get("nodes"),
                        "edges", trees.get("edges"), "incoming", trees.get("incoming"), "outgoing", trees.get("outgoing"))),
                cancelled);
        return new Partition(SCHEMA, graph, context, segment, nodes, edges);
    }

    private Root rootDocument(ContentDigest graph, String snapshot, Map<String, Tree> trees,
            BooleanSupplier cancelled) throws IOException {
        var document = put(Map.of("format", FORMAT, "graph", graph, "snapshot", snapshot, "trees", trees), cancelled);
        return new Root(document, graph, snapshot, Map.copyOf(trees));
    }

    private void publishRoot(Root root, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        StreamingContentStore.directory(directory);
        byte[] bytes = CanonicalJson.write(Map.of("format", FORMAT, "graph", root.graph(), "document", root.document()))
                .getBytes(StandardCharsets.UTF_8);
        Path pending = Files.createTempFile(directory, "graph-root-", ".part");
        try (var cleanup = new TemporaryFiles(pending)) {
            try (var channel = java.nio.channels.FileChannel.open(pending, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining())
                    channel.write(buffer);
                channel.force(true);
            }
            Path pointer = pointer(root.graph());
            check(cancelled);
            try {
                StreamingContentStore.createLinkWithRetry(pointer, pending);
            } catch (FileAlreadyExistsException raced) {
                root(root.graph(), cancelled);
                return;
            }
            verified.put(root.graph(), root);
        }
    }

    private long count(Connection database, String space, String context) throws SQLException {
        try (var query = database.prepareStatement("SELECT COUNT(*) FROM rows WHERE space=? AND k>=? AND k<?")) {
            String prefix = context + "|";
            query.setString(1, space);
            query.setString(2, prefix);
            query.setString(3, upper(prefix));
            try (var result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private void insert(SqliteGraphProjection workspace, PreparedStatement statement, String space, String key,
            Object value, BooleanSupplier cancelled) throws SQLException, IOException {
        check(cancelled);
        String encoded = encodeGraphValue(value);
        if (encoded.length() > rowBytes || encoded.length() > rowBytes / 3
                && encoded.getBytes(StandardCharsets.UTF_8).length > rowBytes)
            throw new IOException("GRAPH_PARTITION_ROW_WORKER_REQUIRED");
        statement.setString(1, space);
        statement.setString(2, key);
        statement.setString(3, workspace.encoded(encoded));
        statement.executeUpdate();
    }

    private void addInsertBatch(SqliteGraphProjection workspace, PreparedStatement statement, String space, String key,
            Object value, BooleanSupplier cancelled) throws SQLException, IOException {
        check(cancelled);
        String encoded = encodeGraphValue(value);
        if (encoded.length() > rowBytes || encoded.length() > rowBytes / 3
                && encoded.getBytes(StandardCharsets.UTF_8).length > rowBytes)
            throw new IOException("GRAPH_PARTITION_ROW_WORKER_REQUIRED");
        statement.setString(1, space);
        statement.setString(2, key);
        statement.setString(3, workspace.encoded(encoded));
        statement.addBatch();
    }

    private Tree build(Connection database, SqliteGraphProjection workspace, String space, BooleanSupplier cancelled,
            BoundedTasks workers) throws SQLException, IOException {
        var pages = new TreePages(cancelled, workers);
        try (var query = database.prepareStatement("SELECT k,payload FROM rows WHERE space=? ORDER BY k")) {
            query.setString(1, space);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    check(cancelled);
                    pages.add(new EncodedEntry(rows.getString(1), workspace.decoded(rows.getString(2))));
                }
            }
        }
        return pages.finish();
    }

    private <T> Tree build(Stream<T> rows, Function<T, String> key, Function<T, ?> value, BooleanSupplier cancelled,
            BoundedTasks workers) throws IOException {
        var pages = new TreePages(cancelled, workers);
        try (rows) {
            var input = rows.iterator();
            while (input.hasNext()) {
                check(cancelled);
                var row = input.next();
                pages.add(new EncodedEntry(key.apply(row), encodeGraphValue(value.apply(row))));
            }
        }
        return pages.finish();
    }

    private final class TreePages {
        private final TreeBuilder builder;
        private final BoundedTasks workers;
        private final BooleanSupplier cancelled;
        private final List<EncodedEntry> batch = new ArrayList<>(PAGE_ROWS);
        private final Deque<BoundedTasks.Task<Tree>> pending = new ArrayDeque<>();
        private long characters;

        TreePages(BooleanSupplier cancelled, BoundedTasks workers) {
            this.cancelled = cancelled;
            this.workers = workers;
            this.builder = new TreeBuilder(cancelled);
        }

        void add(EncodedEntry row) throws IOException {
            int length = row.payload().length();
            if (length > rowBytes || length > rowBytes / 3
                    && row.payload().getBytes(StandardCharsets.UTF_8).length > rowBytes)
                throw new IOException("GRAPH_PARTITION_ROW_WORKER_REQUIRED");
            long pageCap = Math.min(PAGE_CHARACTERS, rowBytes);
            long entryCost = rowBytes < PAGE_CHARACTERS ? (long) length + row.key().length() * 3L + 32L : length;
            if (!batch.isEmpty() && (batch.size() == PAGE_ROWS || characters + entryCost > pageCap))
                flush();
            batch.add(row);
            characters += entryCost;
        }

        private void flush() throws IOException {
            var captured = List.copyOf(batch);
            long pageCap = Math.min(PAGE_CHARACTERS, rowBytes);
            if (characters > pageCap) {
                // One admitted large row proceeds alone, without multiplying worker heap.
                drain();
                builder.add(leaf(captured, cancelled), 0);
            } else {
                if (pending.size() >= pageWorkers)
                    builder.add(pending.removeFirst().await(), 0);
                pending.addLast(workers.submit(() -> leaf(captured, cancelled)));
            }
            batch.clear();
            characters = 0;
        }

        private void drain() throws IOException {
            while (!pending.isEmpty())
                builder.add(pending.removeFirst().await(), 0);
        }

        Tree finish() throws IOException {
            if (!batch.isEmpty()) flush();
            drain();
            return builder.finish();
        }
    }

    private Tree leaf(List<EncodedEntry> entries, BooleanSupplier cancelled) throws IOException {
        DEFER_LEAF_FORCE.set(Boolean.TRUE);
        final ContentHandle handle;
        try {
            // Rows can only come from CanonicalJson or the hash-checked private projection.
            // Write the same canonical frame without parsing each row to recreate it.
            handle = content.write(output -> {
                var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), 65536);
                writer.write("{\"rows\":[");
                boolean first = true;
                for (var row : entries) {
                    check(cancelled);
                    if (!first) writer.write(',');
                    first = false;
                    writer.write("{\"key\":");
                    CanonicalJson.writeTo(row.key(), writer);
                    writer.write(",\"row\":");
                    writer.write(row.payload());
                    writer.write('}');
                }
                writer.write("],\"schema\":");
                CanonicalJson.writeTo(LEAF, writer);
                writer.write('}');
                writer.flush();
            }, cancelled);
        } finally {
            DEFER_LEAF_FORCE.remove();
        }
        return new Tree(handle, entries.isEmpty() ? "" : entries.getFirst().key(),
                entries.isEmpty() ? "" : entries.getLast().key(), entries.size());
    }

    private Tree branch(List<Tree> children, BooleanSupplier cancelled) throws IOException {
        long rows = 0;
        for (var child : children)
            rows = Math.addExact(rows, child.rows());
        return new Tree(put(Map.of("schema", BRANCH, "children", children), cancelled), children.getFirst().first(),
                children.getLast().last(), rows);
    }

    private final class TreeBuilder {
        final List<List<Tree>> levels = new ArrayList<>();
        final BooleanSupplier cancelled;

        TreeBuilder(BooleanSupplier cancelled) {
            this.cancelled = cancelled;
        }

        void add(Tree tree, int level) throws IOException {
            while (levels.size() <= level)
                levels.add(new ArrayList<>());
            var values = levels.get(level);
            values.add(tree);
            if (values.size() == FANOUT) {
                var parent = branch(values, cancelled);
                values.clear();
                add(parent, level + 1);
            }
        }

        Tree finish() throws IOException {
            Tree carry = null;
            for (int level = 0; level < levels.size(); level++) {
                var values = levels.get(level);
                if (carry != null) {
                    values.add(carry);
                    carry = null;
                }
                if (values.isEmpty())
                    continue;
                boolean higher = false;
                for (int i = level + 1; i < levels.size(); i++)
                    higher |= !levels.get(i).isEmpty();
                if (!higher)
                    return values.size() == 1 ? values.getFirst() : branch(values, cancelled);
                carry = branch(values, cancelled);
            }
            return carry == null ? leaf(List.of(), cancelled) : carry;
        }
    }

    private Root root(ContentDigest graph, BooleanSupplier cancelled) throws IOException {
        try {
            return loadRoot(graph, cancelled);
        } catch (IllegalArgumentException | ArithmeticException corrupt) {
            throw new IOException("GRAPH_PARTITION_CORRUPT", corrupt);
        }
    }

    private Root loadRoot(ContentDigest graph, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        var document = rootReference(graph);
        var cached = verified.get(graph);
        if (cached != null && cached.document().equals(document))
            return cached;
        var value = object(read(document, cancelled));
        fields(value, "format", "graph", "snapshot", "trees");
        if (!FORMAT.equals(value.get("format")) || !graph.value().equals(value.get("graph")))
            throw new IOException("GRAPH_PARTITION_ROOT_SCOPE");
        var trees = new TreeMap<String, Tree>();
        object(value.get("trees")).forEach((name, tree) -> trees.put(name, tree(tree)));
        if (!trees.keySet()
                .equals(Set.of("contexts", "nodes", "edges", "evidence", "outgoing", "incoming", "partitions")))
            throw new IOException("GRAPH_PARTITION_INDEX_MISSING");
        var root = new Root(document, graph, text(value.get("snapshot")), Map.copyOf(trees));
        if (!graph.equals(canonicalDigest(root, cancelled, true)))
            throw new IOException("GRAPH_PARTITION_DIGEST_MISMATCH");
        verified.put(graph, root);
        return root;
    }

    private ContentHandle rootReference(ContentDigest graph) throws IOException {
        Path pointer = pointer(graph);
        Map<String, Object> reference;
        try (var input = Files.newInputStream(pointer, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(16385);
            if (bytes.length > 16384)
                throw new IOException("GRAPH_PARTITION_ROOT_INVALID");
            reference = object(parse(utf8(bytes)));
        }
        fields(reference, "format", "graph", "document");
        if (!FORMAT.equals(reference.get("format")) || !graph.value().equals(reference.get("graph")))
            throw new IOException("GRAPH_PARTITION_ROOT_SCOPE");
        return handle(reference.get("document"));
    }

    private ContentDigest canonicalDigest(Root root, BooleanSupplier cancelled, boolean auditIndexes)
            throws IOException {
        return canonicalDigest(root, cancelled, auditIndexes, Optional.empty());
    }

    private ContentDigest canonicalDigest(Root root, BooleanSupplier cancelled, boolean auditIndexes,
            Optional<ContentDigest> expectedFrame) throws IOException {
        try (var audit = auditIndexes ? new Audit(root, cancelled) : null) {
            var digest = MessageDigest.getInstance("SHA-256");
            var frame = expectedFrame.isPresent() ? MessageDigest.getInstance("SHA-256") : null;
            if (frame != null)
                frame.update(("{\"format\":" + CanonicalJson.write(GraphSnapshotCodec.FORMAT) + ",\"graph\":")
                        .getBytes(StandardCharsets.UTF_8));
            var output = new OutputStream() {
                public void write(int value) {
                    digest.update((byte) value);
                    if (frame != null)
                        frame.update((byte) value);
                }

                public void write(byte[] values, int offset, int length) {
                    digest.update(values, offset, length);
                    if (frame != null)
                        frame.update(values, offset, length);
                }
            };
            try (var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), 65536)) {
                writer.write("{");
                boolean first = true;
                for (String name : List.of("contexts", "edges", "evidence", "nodes")) {
                    if (!first)
                        writer.write(",");
                    first = false;
                    CanonicalJson.writeTo(name, writer);
                    writer.write(":");
                    writer.write("[");
                    boolean[] member = { false };
                    visit(root.trees().get(name), "", "\uffff", null, entry -> {
                        check(cancelled);
                        Object row = switch (name) {
                            case "contexts" -> GraphSnapshotCodec.context(entry.row());
                            case "nodes" -> GraphSnapshotCodec.node(entry.row());
                            case "edges" -> GraphSnapshotCodec.edge(entry.row());
                            default -> GraphSnapshotCodec.evidence(entry.row());
                        };
                        String expectedKey = switch (name) {
                            case "contexts" -> ((KnowledgeGraph.Context) row).identity();
                            case "nodes" -> {
                                var n = (KnowledgeGraph.Node) row;
                                yield key(n.context(), n.identity());
                            }
                            case "edges" -> {
                                var e = (KnowledgeGraph.Edge) row;
                                yield key(e.context(), e.identity());
                            }
                            default -> ((GraphEvidence) row).identity().value();
                        };
                        if (!entry.key().equals(expectedKey))
                            throw new IOException("GRAPH_PARTITION_CANONICAL_KEY");
                        if (audit != null)
                            audit.accept(row);
                        if (member[0])
                            writer.write(",");
                        member[0] = true;
                        writeGraphValue(row, writer);
                    }, cancelled, 0);
                    writer.write("]");
                }
                writer.write(",\"schema\":");
                CanonicalJson.writeTo(KnowledgeGraph.SCHEMA, writer);
                writer.write(",\"snapshot\":");
                CanonicalJson.writeTo(root.snapshot(), writer);
                writer.write("}");
            }
            if (audit != null)
                audit.finish();
            if (frame != null) {
                frame.update(
                        (",\"identity\":" + CanonicalJson.write(root.graph()) + "}").getBytes(StandardCharsets.UTF_8));
                if (!expectedFrame.orElseThrow()
                        .equals(new ContentDigest("sha256:" + HexFormat.of().formatHex(frame.digest()))))
                    throw new IOException("GRAPH_PARTITION_NONCANONICAL_INPUT");
            }
            return new ContentDigest("sha256:" + HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Verify derived lookup completeness and canonical graph references on disk.
     * A valid v1 digest alone does not authenticate a separately supplied adjacency
     * index.
     */
    private final class Audit implements AutoCloseable {
        private static final int DEDUP_CAP = 524_288;
        final Root root;
        final BooleanSupplier cancelled;
        final Path file;
        final Connection database;
        final PreparedStatement insert;
        final Map<String, Set<String>> seenKeys = new HashMap<>();
        int batched;

        Audit(Root root, BooleanSupplier cancelled) throws IOException {
            this.root = root;
            this.cancelled = cancelled;
            StreamingContentStore.directory(directory);
            file = Files.createTempFile(directory, "graph-audit-", ".sqlite");
            Connection opened = null;
            PreparedStatement prepared = null;
            try {
                opened = DriverManager.getConnection("jdbc:sqlite:" + file);
                try (var sql = opened.createStatement()) {
                    sql.execute("PRAGMA journal_mode=OFF");
                    sql.execute("PRAGMA synchronous=OFF");
                    sql.execute("PRAGMA temp_store=MEMORY");
                    sql.execute("PRAGMA cache_size=-65536");
                    sql.execute(
                            "CREATE TABLE audit(space TEXT,k TEXT,payload TEXT,PRIMARY KEY(space,k)) WITHOUT ROWID");
                }
                opened.setAutoCommit(false);
                prepared = opened.prepareStatement("INSERT OR IGNORE INTO audit(space,k,payload) VALUES(?,?,?)");
            } catch (SQLException failure) {
                if (prepared != null)
                    try {
                        prepared.close();
                    } catch (SQLException close) {
                        failure.addSuppressed(close);
                    }
                if (opened != null)
                    try {
                        opened.close();
                    } catch (SQLException close) {
                        failure.addSuppressed(close);
                    }
                try {
                    Files.deleteIfExists(file);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw new IOException("GRAPH_PARTITION_AUDIT_UNAVAILABLE", failure);
            }
            database = opened;
            insert = prepared;
        }

        void add(String space, String key, String value) throws IOException {
            check(cancelled);
            if (value.isEmpty()) {
                var set = seenKeys.computeIfAbsent(space, ignored -> new HashSet<>(1024));
                if (set.contains(key)) return;
                if (set.size() < DEDUP_CAP) set.add(key);
            }
            try {
                insert.setString(1, space);
                insert.setString(2, key);
                insert.setString(3, value);
                insert.addBatch();
                if (++batched == 4096) {
                    insert.executeBatch();
                    batched = 0;
                }
            } catch (SQLException failure) {
                throw new IOException("GRAPH_PARTITION_AUDIT_WRITE", failure);
            }
        }

        private void flushBatch() throws SQLException {
            if (batched > 0) {
                insert.executeBatch();
                batched = 0;
            }
            seenKeys.clear();
        }

        void accept(Object value) throws IOException {
            if (value instanceof KnowledgeGraph.Context context) {
                if (!context.snapshot().value().equals(root.snapshot()))
                    throw new IOException("GRAPH_PARTITION_FOREIGN_SNAPSHOT");
                add("contexts", context.identity(), "");
                add("evidence-refs", context.evidence().value(), "");
            } else if (value instanceof KnowledgeGraph.Node node) {
                add("nodes", key(node.context(), node.identity()), "");
                add("context-refs", node.context(), "");
                for (var evidence : node.evidence())
                    add("evidence-refs", evidence.value(), "");
            } else if (value instanceof KnowledgeGraph.Edge edge) {
                String id = key(edge.context(), edge.identity());
                add("edges", id, "");
                add("context-refs", edge.context(), "");
                add("node-refs", key(edge.context(), edge.source()), "");
                add("expected-outgoing", key(edge.context(), edge.source(), edge.identity()), id);
                for (String target : edge.target().identities()) {
                    add("node-refs", key(edge.context(), target), "");
                    add("expected-incoming", key(edge.context(), target, edge.identity()), id);
                }
                if (edge.region().isPresent())
                    add("node-refs", key(edge.context(), edge.region().orElseThrow()), "");
                for (var evidence : edge.evidence())
                    add("evidence-refs", evidence.value(), "");
            } else if (value instanceof GraphEvidence evidence)
                add("evidence", evidence.identity().value(), "");
        }

        void finish() throws IOException {
            try {
                flushBatch();
                for (String[] pair : new String[][] { { "contexts", "context-refs" }, { "nodes", "node-refs" },
                        { "evidence", "evidence-refs" } })
                    try (var query = database.prepareStatement(
                            "SELECT 1 FROM audit r LEFT JOIN audit a ON a.space=? AND a.k=r.k WHERE r.space=? AND a.k IS NULL LIMIT 1")) {
                        query.setString(1, pair[0]);
                        query.setString(2, pair[1]);
                        try (var result = query.executeQuery()) {
                            if (result.next())
                                throw new IOException("GRAPH_PARTITION_DANGLING_REFERENCE");
                        }
                    }
                for (String space : List.of("incoming", "outgoing")) {
                    try (var query = database
                            .prepareStatement("SELECT k,payload FROM audit WHERE space=? ORDER BY k")) {
                        query.setString(1, "expected-" + space);
                        try (var expected = query.executeQuery()) {
                            visit(root.trees().get(space), "", "\uffff", null, entry -> {
                                try {
                                    if (!expected.next() || !expected.getString(1).equals(entry.key())
                                            || !expected.getString(2).equals(text(entry.row())))
                                        throw new IOException("GRAPH_PARTITION_ADJACENCY_MISMATCH");
                                } catch (SQLException failure) {
                                    throw new IOException("GRAPH_PARTITION_AUDIT_READ", failure);
                                }
                            }, cancelled, 0);
                            if (expected.next())
                                throw new IOException("GRAPH_PARTITION_ADJACENCY_OMITTED");
                        }
                    }
                }
                try (var query = database.prepareStatement("SELECT k FROM audit WHERE space='contexts' ORDER BY k");
                        var contexts = query.executeQuery()) {
                    visit(root.trees().get("partitions"), "", "\uffff", null, entry -> {
                        try {
                            if (!contexts.next() || !contexts.getString(1).equals(entry.key()))
                                throw new IOException("GRAPH_PARTITION_CONTEXT_CENSUS");
                            var partition = partition(entry.row());
                            if (!partition.context().equals(entry.key()) || !partition.graph().equals(root.graph()))
                                throw new IOException("GRAPH_PARTITION_CONTEXT_SCOPE");
                            for (String space : List.of("nodes", "edges"))
                                try (var count = database.prepareStatement(
                                        "SELECT COUNT(*) FROM audit WHERE space=? AND k>=? AND k<?")) {
                                    String prefix = partition.context() + "|";
                                    count.setString(1, space);
                                    count.setString(2, prefix);
                                    count.setString(3, upper(prefix));
                                    try (var result = count.executeQuery()) {
                                        long actual = result.next() ? result.getLong(1) : 0;
                                        if (actual != (space.equals("nodes") ? partition.nodes() : partition.edges()))
                                            throw new IOException("GRAPH_PARTITION_CONTEXT_COUNT");
                                    }
                                }
                            var segment = object(read(partition.segment(), cancelled));
                            if (!partition.context().equals(segment.get("context"))
                                    || !partition.graph().value().equals(segment.get("graph")))
                                throw new IOException("GRAPH_PARTITION_SEGMENT_SCOPE");
                            var indexes = object(segment.get("indexes"));
                            for (String space : List.of("nodes", "edges", "incoming", "outgoing"))
                                if (!root.trees().get(space).equals(tree(indexes.get(space))))
                                    throw new IOException("GRAPH_PARTITION_INDEX_SCOPE");
                        } catch (SQLException failure) {
                            throw new IOException("GRAPH_PARTITION_AUDIT_READ", failure);
                        }
                    }, cancelled, 0);
                    if (contexts.next())
                        throw new IOException("GRAPH_PARTITION_CONTEXT_OMITTED");
                }
            } catch (SQLException failure) {
                throw new IOException("GRAPH_PARTITION_AUDIT_READ", failure);
            }
        }

        public void close() throws IOException {
            SQLException failed = null;
            try {
                insert.close();
            } catch (SQLException failure) {
                failed = failure;
            }
            try {
                database.close();
            } catch (SQLException failure) {
                if (failed == null)
                    failed = failure;
                else
                    failed.addSuppressed(failure);
            }
            IOException problem = failed == null ? null : new IOException("GRAPH_PARTITION_AUDIT_CLOSE", failed);
            try {
                Files.deleteIfExists(file);
            } catch (IOException cleanup) {
                if (problem == null)
                    problem = cleanup;
                else
                    problem.addSuppressed(cleanup);
            }
            if (problem != null)
                throw problem;
        }
    }

    private Page<Partition> partitionPage(ContentDigest graph, int size, Optional<String> cursor,
            BooleanSupplier cancelled) throws IOException {
        try (var ignored = openLocalPageCache()) {
            var root = root(graph, cancelled);
            String query = query(graph, "partitions", size, root.document());
            var page = page(root.trees().get("partitions"), "", "\uffff", query, size, cursor, cancelled);
            var values = new ArrayList<Partition>();
            for (var entry : page.items())
                values.add(partition(entry.row()));
            return new Page<>(values, page.continuation());
        }
    }

    private Page<KnowledgeGraph.Node> nodePage(Partition partition, int size, Optional<String> cursor,
            BooleanSupplier cancelled) throws IOException {
        try (var ignored = openLocalPageCache()) {
            var root = partitionRoot(partition, cancelled);
            String prefix = partition.context() + "|";
            var page = page(root.trees().get("nodes"), prefix, upper(prefix),
                    query(partition.graph(), "nodes", size, partition), size, cursor, cancelled);
            var values = new ArrayList<KnowledgeGraph.Node>();
            for (var entry : page.items()) {
                var node = GraphSnapshotCodec.node(entry.row());
                if (!node.context().equals(partition.context()))
                    throw new IOException("GRAPH_PARTITION_NODE_SCOPE");
                values.add(node);
            }
            return new Page<>(values, page.continuation());
        }
    }

    private Page<KnowledgeGraph.Edge> adjacencyPage(Partition partition, String node, boolean incoming, int size,
            Optional<String> cursor, BooleanSupplier cancelled) throws IOException {
        if (node == null || !node.matches("(?:[a-z][a-z0-9.-]*:)?sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("GRAPH_PARTITION_NODE_ID");
        try (var ignored = openLocalPageCache()) {
            var root = partitionRoot(partition, cancelled);
            String space = incoming ? "incoming" : "outgoing", prefix = key(partition.context(), node) + "|";
            var page = page(root.trees().get(space), prefix, upper(prefix),
                    query(partition.graph(), space, size, List.of(partition, node)), size, cursor, cancelled);
            var values = new ArrayList<KnowledgeGraph.Edge>();
            for (var entry : page.items()) {
                String id = text(entry.row());
                var exact = new ArrayList<Entry>();
                visit(root.trees().get("edges"), id, id + "\0", null, exact::add, cancelled, 0);
                if (exact.size() != 1)
                    throw new IOException("GRAPH_PARTITION_EDGE_MISSING");
                var edge = GraphSnapshotCodec.edge(exact.getFirst().row());
                if (!edge.context().equals(partition.context())
                        || !(incoming ? edge.target().identities().contains(node) : edge.source().equals(node)))
                    throw new IOException("GRAPH_PARTITION_EDGE_SCOPE");
                values.add(edge);
            }
            return new Page<>(values, page.continuation());
        }
    }

    private Root partitionRoot(Partition partition, BooleanSupplier cancelled) throws IOException {
        var root = root(partition.graph(), cancelled);
        var found = new ArrayList<Entry>();
        visit(root.trees().get("partitions"), partition.context(), partition.context() + "\0", null, found::add,
                cancelled, 0);
        if (found.size() != 1 || !partition.equals(partition(found.getFirst().row())))
            throw new IOException("GRAPH_PARTITION_REFERENCE_MISMATCH");
        var segment = object(read(partition.segment(), cancelled));
        fields(segment, "schema", "graph", "context", "nodes", "edges", "indexes");
        if (!"graph.partition-segment:m8.4-v1".equals(segment.get("schema"))
                || !partition.graph().value().equals(segment.get("graph"))
                || !partition.context().equals(segment.get("context"))
                || number(segment.get("nodes")) != partition.nodes()
                || number(segment.get("edges")) != partition.edges())
            throw new IOException("GRAPH_PARTITION_SEGMENT_SCOPE");
        var indexes = object(segment.get("indexes"));
        for (String name : List.of("nodes", "edges", "incoming", "outgoing"))
            if (!root.trees().get(name).equals(tree(indexes.get(name))))
                throw new IOException("GRAPH_PARTITION_INDEX_SCOPE");
        return root;
    }

    private Page<Entry> page(Tree tree, String lower, String upper, String query, int size, Optional<String> cursor,
            BooleanSupplier cancelled) throws IOException {
        if (size < 1 || size > 512)
            throw new IllegalArgumentException("GRAPH_PARTITION_PAGE_SIZE");
        String after = null;
        if (cursor.isPresent()) {
            String token = cursor.orElseThrow();
            if (token.length() > 2048)
                throw new IllegalArgumentException("GRAPH_PARTITION_CURSOR");
            var value = object(parse(utf8(Base64.getUrlDecoder().decode(token))));
            if (!value.keySet().equals(Set.of("query", "after")) || !query.equals(value.get("query")))
                throw new IllegalArgumentException("GRAPH_PARTITION_CURSOR_SCOPE");
            after = text(value.get("after"));
            if (after.compareTo(lower) < 0 || after.compareTo(upper) >= 0)
                throw new IllegalArgumentException("GRAPH_PARTITION_CURSOR_RANGE");
        }
        var rows = new ArrayList<Entry>();
        String boundary = after;
        try {
            visit(tree, lower, upper, boundary, entry -> {
                rows.add(entry);
                if (rows.size() > size)
                    throw new PageFull();
            }, cancelled, 0);
        } catch (PageFull complete) {
        }
        boolean more = rows.size() > size;
        if (more)
            rows.removeLast();
        var continuation = more
                ? Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson
                        .write(Map.of("query", query, "after", rows.getLast().key())).getBytes(StandardCharsets.UTF_8)))
                : Optional.<String>empty();
        return new Page<>(rows, continuation);
    }

    private static final class PageFull extends IOException {
    }

    private void visit(Tree tree, String lower, String upper, String after, RowConsumer consumer,
            BooleanSupplier cancelled, int depth) throws IOException {
        check(cancelled);
        if (tree.rows() == 0 || tree.last().compareTo(lower) < 0 || tree.first().compareTo(upper) >= 0
                || after != null && tree.last().compareTo(after) <= 0)
            return;
        if (depth > 128)
            throw new IOException("GRAPH_PARTITION_TREE_DEPTH");
        var value = object(read(tree.content(), cancelled));
        if (LEAF.equals(value.get("schema"))) {
            fields(value, "schema", "rows");
            var rows = GraphJson.array(value.get("rows"));
            if (rows.size() != tree.rows())
                throw new IOException("GRAPH_PARTITION_LEAF_COUNT");
            String previous = null;
            for (Object raw : rows) {
                var row = object(raw);
                fields(row, "key", "row");
                String key = text(row.get("key"));
                if (previous != null && previous.compareTo(key) >= 0)
                    throw new IOException("GRAPH_PARTITION_KEY_ORDER");
                previous = key;
                if (key.compareTo(lower) >= 0 && key.compareTo(upper) < 0
                        && (after == null || key.compareTo(after) > 0))
                    consumer.accept(new Entry(key, row.get("row")));
            }
            if (!rows.isEmpty()
                    && (!tree.first().equals(object(rows.getFirst()).get("key")) || !tree.last().equals(previous)))
                throw new IOException("GRAPH_PARTITION_KEY_RANGE");
        } else if (BRANCH.equals(value.get("schema"))) {
            fields(value, "schema", "children");
            var children = GraphJson.array(value.get("children"));
            if (children.isEmpty() || children.size() > FANOUT)
                throw new IOException("GRAPH_PARTITION_BRANCH_SIZE");
            long count = 0;
            String previous = null;
            var parsed = new ArrayList<Tree>();
            for (Object child : children) {
                var next = tree(child);
                if (previous != null && previous.compareTo(next.first()) >= 0)
                    throw new IOException("GRAPH_PARTITION_CHILD_ORDER");
                count = Math.addExact(count, next.rows());
                previous = next.last();
                parsed.add(next);
            }
            if (count != tree.rows() || !tree.first().equals(parsed.getFirst().first())
                    || !tree.last().equals(parsed.getLast().last()))
                throw new IOException("GRAPH_PARTITION_BRANCH_RANGE");
            for (var child : parsed)
                visit(child, lower, upper, after, consumer, cancelled, depth + 1);
        } else
            throw new IOException("GRAPH_PARTITION_TREE_FORMAT");
    }

    private ContentHandle put(Object value, BooleanSupplier cancelled) throws IOException {
        return content.write(output -> {
            var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            CanonicalJson.writeTo(value, writer);
            writer.flush();
        }, cancelled);
    }

    /** Leaf framing wraps an admitted canonical row with schema/key metadata; allow that envelope on read. */
    private int pageByteLimit() {
        return (int) Math.min((long) Integer.MAX_VALUE - 8, (long) rowBytes + LEAF_ENVELOPE_ALLOWANCE);
    }

    private Object read(ContentHandle handle, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        int pageLimit = pageByteLimit();
        if (handle.size() > pageLimit)
            throw new IOException("GRAPH_PARTITION_PAGE_WORKER_REQUIRED");
        var localCache = LOCAL_PAGE_CACHE.get();
        if (localCache != null) {
            Object hit = localCache.get(handle);
            if (hit != null) {
                LOCAL_PAGE_CACHE_HITS.incrementAndGet();
                return hit;
            }
        }
        try (var input = content.open(handle)) {
            byte[] bytes = input.readNBytes(Math.addExact(pageLimit, 1));
            if (bytes.length != handle.size() || !ContentDigest.sha256(bytes).equals(handle.digest()))
                throw new IOException("GRAPH_PARTITION_CONTENT_CORRUPT");
            Object parsed = parse(utf8(bytes));
            if (localCache != null) {
                localCache.put(handle, parsed);
            }
            return parsed;
        }
    }

    private static String encodeGraphValue(Object value) {
        return GraphCanonicalWriter.encodeValue(value);
    }

    private static void writeGraphValue(Object value, Appendable out) throws IOException {
        GraphCanonicalWriter.writeValue(value, out);
    }

    private Object parse(String encoded) {
        int limit = pageByteLimit();
        return GraphJson.read(encoded, new GraphJson.Limits(limit, 128, limit));
    }

    private Path pointer(ContentDigest graph) throws IOException {
        var path = directory.resolve(graph.value().substring(7) + ".partitions.json");
        StreamingContentStore.safeWithin(directory, path);
        return path;
    }

    private static Map<String, Object> object(Object value) {
        return GraphJson.object(value);
    }

    private static String text(Object value) {
        return GraphJson.string(value);
    }

    private static long number(Object value) {
        return ((java.math.BigDecimal) value).longValueExact();
    }

    private static ContentHandle handle(Object value) {
        var row = object(value);
        if (!row.keySet().equals(Set.of("digest", "size")))
            throw new IllegalArgumentException("GRAPH_CONTENT_HANDLE");
        return new ContentHandle(new ContentDigest(text(row.get("digest"))), number(row.get("size")));
    }

    private static Tree tree(Object value) {
        var row = object(value);
        if (!row.keySet().equals(Set.of("content", "first", "last", "rows")))
            throw new IllegalArgumentException("GRAPH_TREE_FIELDS");
        var result = new Tree(handle(row.get("content")), text(row.get("first")), text(row.get("last")),
                number(row.get("rows")));
        if (result.rows() < 0 || result.rows() > 0 && result.first().compareTo(result.last()) > 0)
            throw new IllegalArgumentException("GRAPH_TREE_RANGE");
        return result;
    }

    private static Partition partition(Object value) {
        var row = object(value);
        if (!row.keySet().equals(Set.of("schema", "graph", "context", "segment", "nodes", "edges")))
            throw new IllegalArgumentException("GRAPH_PARTITION_FIELDS");
        return new Partition(text(row.get("schema")), new ContentDigest(text(row.get("graph"))),
                text(row.get("context")), handle(row.get("segment")), number(row.get("nodes")),
                number(row.get("edges")));
    }

    private static void fields(Map<String, Object> value, String... names) throws IOException {
        if (!value.keySet().equals(Set.of(names)))
            throw new IOException("GRAPH_PARTITION_RECORD_FIELDS");
    }

    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String key(String... values) {
        return String.join("|", values);
    }

    private static String upper(String prefix) {
        return prefix.substring(0, prefix.length() - 1) + "}";
    }

    private static String query(ContentDigest graph, String operation, int size, Object scope) {
        return CanonicalJson.sha256(List.of(FORMAT, graph, operation, size, scope)).value();
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
    }

    // =========================================================================
    // EVOGBIN2 — Incremental Merkle-Leaf Delta Persistence & Pass-2 Streaming Sink
    // =========================================================================

    private static final String MERKLE_V2_DIR_NAME = "merkle-v2";
    private static final byte[] MERKLE_MANIFEST_MAGIC = "EVOMRK2\0".getBytes(StandardCharsets.US_ASCII);
    private static final int MERKLE_MANIFEST_VERSION = 2;

    /** Pointer to one compressed EVOGBIN2 Merkle leaf stored in CAS with compact gap summary metadata. */
    private record StoredLeafRef(
            String partitionKey,
            ContentDigest leafHash,
            ContentHandle blobHandle,
            int uncompressedBytes,
            int nodeCount,
            int edgeCount,
            int evidenceCount,
            List<GraphBinaryCache.GapOccurrence> gapOccurrences) {
    }

    /** Authenticated on-disk Merkle manifest referencing CAS-deduplicated compressed EVOGBIN2 leaves. */
    private record MerkleManifest(
            SnapshotIdentity snapshot,
            ContentDigest merkleGraphIdentity,
            Optional<ContentDigest> canonicalGraphIdentity,
            GraphBinaryCache.CapabilitySummary capabilitySummary,
            Map<String, StoredLeafRef> contextLeaves,
            StoredLeafRef crossContextLeaf) {
    }

    /** Publication receipt for an EVOGBIN2 Merkle-subtree snapshot or incremental delta update. */
    public record MerklePublication(
            ContentDigest merkleGraphIdentity,
            Optional<ContentDigest> canonicalGraphIdentity,
            SnapshotIdentity snapshot,
            Map<String, ContentDigest> contextPartitionHashes,
            ContentDigest crossContextEdgesHash,
            GraphBinaryCache.CapabilitySummary capabilitySummary,
            int totalLeaves,
            int dirtyLeavesWritten,
            int reusedLeaves,
            long newBytesWritten,
            long totalCompressedBytes,
            long totalUncompressedBytes,
            Path manifestPath) {
        public MerklePublication {
            Objects.requireNonNull(merkleGraphIdentity, "merkleGraphIdentity");
            Objects.requireNonNull(canonicalGraphIdentity, "canonicalGraphIdentity");
            Objects.requireNonNull(snapshot, "snapshot");
            contextPartitionHashes = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(contextPartitionHashes)));
            Objects.requireNonNull(crossContextEdgesHash, "crossContextEdgesHash");
            Objects.requireNonNull(capabilitySummary, "capabilitySummary");
            Objects.requireNonNull(manifestPath, "manifestPath");
        }
    }

    /** Cold or content-deduplicated EVOGBIN2 Merkle persistence without building monolithic {@code .graph.json}. */
    public MerklePublication saveMerkleV2(KnowledgeGraph graph, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(graph, "graph");
        check(cancelled);
        GraphBinaryCache.MerkleDecomposition decomp = GraphBinaryCache.decomposeMerkle(graph);
        return persistMerkleDecomposition(decomp, Optional.of(graph.identity()), null, cancelled);
    }

    /**
     * Incremental EVOGBIN2 Merkle delta update from an already-constructed updated KnowledgeGraph:
     * unchanged context leaves and cross-context leaf are reused by content hash with zero CAS write.
     */
    public MerklePublication saveMerkleDeltaV2(
            ContentDigest baseMerkleIdentity,
            KnowledgeGraph updatedGraph,
            BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(updatedGraph, "updatedGraph");
        check(cancelled);
        MerkleManifest base = baseMerkleIdentity != null && hasMerkleManifest(baseMerkleIdentity)
                ? readMerkleManifest(baseMerkleIdentity, cancelled)
                : null;
        GraphBinaryCache.MerkleDecomposition decomp = GraphBinaryCache.decomposeMerkle(updatedGraph);
        return persistMerkleDecomposition(decomp, Optional.of(updatedGraph.identity()), base, cancelled);
    }

    /**
     * True partition-level Warm 1-File Edit delta persistence:
     * loads ONLY the tiny base manifest, reuses all untouched context Merkle leaves in O(1) without
     * touching their CAS blobs, encodes and writes ONLY the dirty partition leaves (typically {@code <= 2}),
     * recomputes exact capability-gap {@code occurrenceCount} sums (I2/I3), and produces bit-identical
     * EVOGBIN2 Merkle root & payload to a Cold full build (zero {@code .graph.json} rebuild/fsync).
     */
    public MerklePublication saveMerkleDeltaPartitionsV2(
            ContentDigest baseMerkleIdentity,
            SnapshotIdentity newSnapshot,
            Map<String, GraphBinaryCache.ContextPartitionData> updatedPartitions,
            Set<String> removedContexts,
            Optional<List<KnowledgeGraph.Edge>> updatedCrossContextEdges,
            Optional<List<GraphEvidence>> updatedOrphanEvidence,
            BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(baseMerkleIdentity, "baseMerkleIdentity");
        Objects.requireNonNull(newSnapshot, "newSnapshot");
        Objects.requireNonNull(updatedPartitions, "updatedPartitions");
        Objects.requireNonNull(removedContexts, "removedContexts");
        Objects.requireNonNull(updatedCrossContextEdges, "updatedCrossContextEdges");
        Objects.requireNonNull(updatedOrphanEvidence, "updatedOrphanEvidence");
        check(cancelled);

        MerkleManifest base = readMerkleManifest(baseMerkleIdentity, cancelled);
        var nextContextRefs = new TreeMap<String, StoredLeafRef>();
        int dirtyWritten = 0;
        int reused = 0;
        long newBytes = 0L;

        // 1. Copy untouched context leaf references in O(1) after fast CAS presence check.
        for (var entry : base.contextLeaves().entrySet()) {
            String ctxId = entry.getKey();
            if (removedContexts.contains(ctxId) || updatedPartitions.containsKey(ctxId)) {
                continue;
            }
            StoredLeafRef ref = entry.getValue();
            if (!content.hasObjectFast(ref.blobHandle())) {
                throw new IOException("EVOGBIN2_BASE_LEAF_MISSING:" + ctxId);
            }
            nextContextRefs.put(ctxId, ref);
            reused++;
        }

        // 2. Encode and persist ONLY dirty/added context partitions.
        for (var entry : new TreeMap<>(updatedPartitions).entrySet()) {
            check(cancelled);
            String ctxId = entry.getKey();
            GraphBinaryCache.MerkleLeaf encoded = GraphBinaryCache.encodeContextLeaf(entry.getValue());
            StoredLeafRef baseRef = base.contextLeaves().get(ctxId);
            if (baseRef != null && baseRef.leafHash().equals(encoded.leafHash())
                    && content.hasObjectFast(baseRef.blobHandle())) {
                nextContextRefs.put(ctxId, baseRef);
                reused++;
            } else {
                LeafWriteResult written = persistLeafBlob(encoded, cancelled);
                nextContextRefs.put(ctxId, written.ref());
                if (written.wroteNewBlob()) {
                    dirtyWritten++;
                    newBytes += written.ref().blobHandle().size();
                } else {
                    reused++;
                }
            }
        }

        // 3. Cross-context leaf: reuse base directly if unchanged, else re-encode & write only if changed.
        StoredLeafRef nextCrossRef;
        if (updatedCrossContextEdges.isEmpty() && updatedOrphanEvidence.isEmpty()) {
            StoredLeafRef baseCross = base.crossContextLeaf();
            if (!content.hasObjectFast(baseCross.blobHandle())) {
                throw new IOException("EVOGBIN2_BASE_CROSS_LEAF_MISSING");
            }
            nextCrossRef = baseCross;
            reused++;
        } else {
            List<KnowledgeGraph.Edge> edges = updatedCrossContextEdges.orElseGet(() -> {
                try {
                    return GraphBinaryCache.decodeCrossContextLeaf(loadLeaf(base.crossContextLeaf())).crossContextEdges();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            List<GraphEvidence> ev = updatedOrphanEvidence.orElseGet(() -> {
                try {
                    return GraphBinaryCache.decodeCrossContextLeaf(loadLeaf(base.crossContextLeaf())).evidence();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            GraphBinaryCache.MerkleLeaf encodedCross = GraphBinaryCache.encodeCrossContextLeaf(
                    new GraphBinaryCache.CrossContextPartitionData(edges, ev));
            if (base.crossContextLeaf().leafHash().equals(encodedCross.leafHash())
                    && content.hasObjectFast(base.crossContextLeaf().blobHandle())) {
                nextCrossRef = base.crossContextLeaf();
                reused++;
            } else {
                LeafWriteResult written = persistLeafBlob(encodedCross, cancelled);
                nextCrossRef = written.ref();
                if (written.wroteNewBlob()) {
                    dirtyWritten++;
                    newBytes += written.ref().blobHandle().size();
                } else {
                    reused++;
                }
            }
        }

        return finalizeMerkleManifest(newSnapshot, Optional.empty(), nextContextRefs, nextCrossRef,
                dirtyWritten, reused, newBytes, cancelled);
    }

    /** Returns true if an EVOGBIN2 Merkle manifest exists for {@code merkleGraphIdentity}. */
    public boolean hasMerkleManifest(ContentDigest merkleGraphIdentity) {
        try {
            return Files.isRegularFile(merkleManifestPath(Objects.requireNonNull(merkleGraphIdentity)),
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ignored) {
            return false;
        }
    }

    /** Loads, verifies, and decodes an authenticated EVOGBIN2 Merkle snapshot back into a KnowledgeGraph. */
    public GraphBinaryCache.DecodedEvogbin2 loadMerkleV2(
            ContentDigest merkleGraphIdentity, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(merkleGraphIdentity, "merkleGraphIdentity");
        check(cancelled);
        byte[] evogbin2Bytes = exportEvogbin2Bytes(merkleGraphIdentity, cancelled);
        return GraphBinaryCache.decodeEvogbin2(evogbin2Bytes);
    }

    /**
     * Assembles the exact self-contained deterministic EVOGBIN2 byte representation from the
     * deduplicated CAS Merkle leaves and manifest (bit-for-bit identical to Cold {@code encodeEvogbin2}).
     */
    public byte[] exportEvogbin2Bytes(ContentDigest merkleGraphIdentity, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(merkleGraphIdentity, "merkleGraphIdentity");
        check(cancelled);
        MerkleManifest manifest = readMerkleManifest(merkleGraphIdentity, cancelled);
        var contextLeaves = new TreeMap<String, GraphBinaryCache.MerkleLeaf>();
        for (var entry : manifest.contextLeaves().entrySet()) {
            check(cancelled);
            contextLeaves.put(entry.getKey(), loadLeaf(entry.getValue()));
        }
        GraphBinaryCache.MerkleLeaf crossLeaf = loadLeaf(manifest.crossContextLeaf());
        var decomp = new GraphBinaryCache.MerkleDecomposition(
                manifest.snapshot(),
                manifest.merkleGraphIdentity(),
                contextLeaves,
                crossLeaf,
                manifest.capabilitySummary());
        return GraphBinaryCache.encodeEvogbin2(decomp);
    }

    /**
     * Lazily materializes the exact canonical {@code .graph.json} file from EVOGBIN2 Merkle leaves
     * on demand for audit without storing monolithic JSON on every incremental edit.
     */
    public Path materializeCanonicalJson(
            ContentDigest merkleGraphIdentity, Path targetJsonPath, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(merkleGraphIdentity, "merkleGraphIdentity");
        Objects.requireNonNull(targetJsonPath, "targetJsonPath");
        check(cancelled);
        GraphBinaryCache.DecodedEvogbin2 decoded = loadMerkleV2(merkleGraphIdentity, cancelled);
        Path normalized = targetJsonPath.toAbsolutePath().normalize();
        StreamingContentStore.safe(normalized);
        Files.createDirectories(normalized.getParent());
        Path pending = Files.createTempFile(normalized.getParent(), "graph-materialize-", ".part");
        try {
            try (var channel = java.nio.channels.FileChannel.open(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var writer = new BufferedWriter(new OutputStreamWriter(
                         java.nio.channels.Channels.newOutputStream(channel), StandardCharsets.UTF_8), 65536)) {
                new GraphSnapshotCodec().encodeTo(decoded.graph(), writer);
                writer.flush();
                channel.force(true);
            }
            StreamingContentStore.moveAtomicWithRetry(pending, normalized);
            return normalized;
        } finally {
            StreamingContentStore.deleteIfExistsQuietly(pending);
        }
    }

    /** Returns total physical bytes on disk occupied by EVOGBIN2 manifests and deduplicated CAS leaf blobs. */
    public long totalMerkleStorageBytes() throws IOException {
        long total = 0L;
        for (Path sub : List.of(directory.resolve(MERKLE_V2_DIR_NAME), directory.resolve("content"))) {
            if (!Files.isDirectory(sub, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var walk = Files.walk(sub)) {
                var it = walk.iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        total += Files.size(p);
                    }
                }
            }
        }
        return total;
    }

    /**
     * Opens a streaming Merkle partition session for Pass-2 module-by-module flushing:
     * each partition is immediately compressed, written/deduplicated into CAS, and released from heap.
     */
    public MerkleStreamSession openMerkleStreamSession(
            SnapshotIdentity snapshot,
            Optional<ContentDigest> baseMerkleIdentity,
            BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(baseMerkleIdentity, "baseMerkleIdentity");
        Objects.requireNonNull(cancelled, "cancelled");
        check(cancelled);
        MerkleManifest base = baseMerkleIdentity.isPresent() && hasMerkleManifest(baseMerkleIdentity.orElseThrow())
                ? readMerkleManifest(baseMerkleIdentity.orElseThrow(), cancelled)
                : null;
        return new MerkleStreamSession(snapshot, base, cancelled);
    }

    /** Streaming Merkle leaf writer that flushes one context partition at a time and bounds heap. */
    public final class MerkleStreamSession {
        private final SnapshotIdentity snapshot;
        private final MerkleManifest base;
        private final BooleanSupplier cancelled;
        private final TreeMap<String, StoredLeafRef> contextRefs = new TreeMap<>();
        private int dirtyWritten;
        private int reused;
        private long newBytes;
        private boolean finished;

        private MerkleStreamSession(SnapshotIdentity snapshot, MerkleManifest base, BooleanSupplier cancelled) {
            this.snapshot = snapshot;
            this.base = base;
            this.cancelled = cancelled;
        }

        /** Encodes and immediately flushes one context partition leaf to CAS, releasing partition rows from heap. */
        public ContentDigest writeContextPartition(GraphBinaryCache.ContextPartitionData partition) throws IOException {
            if (finished) throw new IllegalStateException("MerkleStreamSession already finished");
            check(cancelled);
            GraphBinaryCache.MerkleLeaf encoded = GraphBinaryCache.encodeContextLeaf(partition);
            String ctxId = partition.context().identity();
            StoredLeafRef baseRef = base == null ? null : base.contextLeaves().get(ctxId);
            if (baseRef != null && baseRef.leafHash().equals(encoded.leafHash())
                    && content.hasObjectFast(baseRef.blobHandle())) {
                contextRefs.put(ctxId, baseRef);
                reused++;
                return baseRef.leafHash();
            }
            LeafWriteResult written = persistLeafBlob(encoded, cancelled);
            contextRefs.put(ctxId, written.ref());
            if (written.wroteNewBlob()) {
                dirtyWritten++;
                newBytes += written.ref().blobHandle().size();
            } else {
                reused++;
            }
            return written.ref().leafHash();
        }

        /** Reuses an unchanged context leaf directly from the base manifest without reading or re-encoding it. */
        public boolean reuseContextFromBase(String contextId) throws IOException {
            if (finished) throw new IllegalStateException("MerkleStreamSession already finished");
            check(cancelled);
            if (base == null) return false;
            StoredLeafRef baseRef = base.contextLeaves().get(contextId);
            if (baseRef == null || !content.hasObjectFast(baseRef.blobHandle())) return false;
            contextRefs.put(contextId, baseRef);
            reused++;
            return true;
        }

        /** Finishes the streaming session by writing/reusing the cross-context leaf and publishing the manifest. */
        public MerklePublication finish(
                List<KnowledgeGraph.Edge> crossContextEdges,
                List<GraphEvidence> orphanEvidence) throws IOException {
            if (finished) throw new IllegalStateException("MerkleStreamSession already finished");
            finished = true;
            check(cancelled);
            GraphBinaryCache.MerkleLeaf crossLeaf = GraphBinaryCache.encodeCrossContextLeaf(
                    new GraphBinaryCache.CrossContextPartitionData(crossContextEdges, orphanEvidence));
            StoredLeafRef crossRef;
            if (base != null && base.crossContextLeaf().leafHash().equals(crossLeaf.leafHash())
                    && content.hasObjectFast(base.crossContextLeaf().blobHandle())) {
                crossRef = base.crossContextLeaf();
                reused++;
            } else {
                LeafWriteResult written = persistLeafBlob(crossLeaf, cancelled);
                crossRef = written.ref();
                if (written.wroteNewBlob()) {
                    dirtyWritten++;
                    newBytes += written.ref().blobHandle().size();
                } else {
                    reused++;
                }
            }
            return finalizeMerkleManifest(snapshot, Optional.empty(), contextRefs, crossRef,
                    dirtyWritten, reused, newBytes, cancelled);
        }
    }

    /**
     * Opens a Pass-2 streaming sink implementing {@link UniversalArchitecturePipeline.StreamingPartitionSink}.
     * Each module's nodes/edges/evidence are projected and flushed immediately to a compressed EVOGBIN2
     * Merkle leaf before the module's FrontendResult is released from heap.
     */
    public AbiStreamingPartitionSink openAbiStreamingSink(
            SnapshotIdentity snapshot,
            Optional<ContentDigest> baseMerkleIdentity,
            boolean fastArchitecture,
            BooleanSupplier cancelled) throws IOException {
        return new AbiStreamingPartitionSink(
                openMerkleStreamSession(snapshot, baseMerkleIdentity, cancelled),
                fastArchitecture,
                cancelled);
    }

    /** Streaming Pass-2 sink that flushes each module directly to PartitionedGraphStore Merkle leaves. */
    public final class AbiStreamingPartitionSink implements UniversalArchitecturePipeline.StreamingPartitionSink {
        private final MerkleStreamSession session;
        private final boolean fastArchitecture;
        private final BooleanSupplier cancelled;
        private final List<KnowledgeGraph.Edge> crossContextEdges = new ArrayList<>();
        private final Map<ContentDigest, GraphEvidence> crossOrOrphanEvidence = new TreeMap<>();
        private final List<CapabilityGapRecord> accumulatedGaps = new ArrayList<>();
        private final Map<String, KnowledgeGraph.Node> mergedRootNodes = new TreeMap<>();
        private final Map<String, KnowledgeGraph.Edge> mergedRootEdges = new TreeMap<>();
        private final Map<ContentDigest, GraphEvidence> mergedRootEvidence = new TreeMap<>();
        private KnowledgeGraph.Context mergedRootContext;
        private MerklePublication publication;

        private AbiStreamingPartitionSink(
                MerkleStreamSession session, boolean fastArchitecture, BooleanSupplier cancelled) {
            this.session = session;
            this.fastArchitecture = fastArchitecture;
            this.cancelled = cancelled;
        }

        @Override
        public void flushModulePartition(
                UniversalSourceIngestion.Outcome outcome,
                UniversalIngestionPipeline.Unit unit,
                List<CanonicalGraphInput.SpringSlice> springSlices,
                List<CapabilityGapRecord> moduleGaps,
                ContentDigest abiIndexDigest) throws IOException {
            check(cancelled);
            if (moduleGaps != null && !moduleGaps.isEmpty()) {
                accumulatedGaps.addAll(moduleGaps);
            }
            if (outcome.request().isEmpty() || unit.frontend().isEmpty()) {
                return;
            }
            var manifest = outcome.request().orElseThrow().manifest();
            KnowledgeGraph moduleGraph = new CanonicalGraphBuilder().build(
                    manifest, unit.frontend().orElseThrow(),
                    springSlices == null ? List.of() : springSlices,
                    List.of(), fastArchitecture);
            flushSubgraphPartitions(moduleGraph);
        }

        @Override
        public void completeRepositorySlice(
                RepositoryInputs inputs,
                com.evolution.analysis.buildmodel.UniversalBuildModel buildModel,
                UniversalSourceIngestion.Result sourceIngestion,
                List<IngestionEvidence.Issue> issues,
                List<CapabilityGapRecord> gaps) throws IOException {
            check(cancelled);
            var combinedGaps = new TreeSet<CapabilityGapRecord>(accumulatedGaps);
            if (gaps != null) combinedGaps.addAll(gaps);
            var repoInput = new CanonicalGraphInput(
                    inputs,
                    Optional.ofNullable(buildModel),
                    Optional.empty(),
                    List.of(),
                    issues == null ? List.of() : issues,
                    List.copyOf(combinedGaps));
            KnowledgeGraph repoGraph = new CanonicalGraphBuilder().build(repoInput, List.of(), fastArchitecture);
            flushSubgraphPartitions(repoGraph);
            if (mergedRootContext != null) {
                session.writeContextPartition(new GraphBinaryCache.ContextPartitionData(
                        mergedRootContext,
                        List.copyOf(mergedRootNodes.values()),
                        List.copyOf(mergedRootEdges.values()),
                        List.copyOf(mergedRootEvidence.values())));
                mergedRootNodes.clear();
                mergedRootEdges.clear();
                mergedRootEvidence.clear();
            }
            this.publication = session.finish(crossContextEdges, List.copyOf(crossOrOrphanEvidence.values()));
        }

        private void flushSubgraphPartitions(KnowledgeGraph subgraph) throws IOException {
            var evById = new HashMap<ContentDigest, GraphEvidence>(Math.max(16, subgraph.evidence().size() * 2));
            for (var ev : subgraph.evidence()) evById.put(ev.identity(), ev);
            var nodesByCtx = new HashMap<String, List<KnowledgeGraph.Node>>();
            for (var n : subgraph.nodes()) {
                nodesByCtx.computeIfAbsent(n.context(), ignored -> new ArrayList<>()).add(n);
            }
            var intraEdgesByCtx = new HashMap<String, List<KnowledgeGraph.Edge>>();
            var referenced = new HashSet<ContentDigest>();
            for (var e : subgraph.edges()) {
                if (GraphBinaryCache.isCrossContextEdge(e)) {
                    crossContextEdges.add(e);
                    for (var evId : e.evidence()) {
                        referenced.add(evId);
                        GraphEvidence ev = evById.get(evId);
                        if (ev != null) crossOrOrphanEvidence.putIfAbsent(evId, ev);
                    }
                } else {
                    intraEdgesByCtx.computeIfAbsent(e.context(), ignored -> new ArrayList<>()).add(e);
                }
            }
            for (var ctx : subgraph.contexts()) {
                var ctxNodes = nodesByCtx.getOrDefault(ctx.identity(), List.of());
                var ctxEdges = intraEdgesByCtx.getOrDefault(ctx.identity(), List.of());
                var localEvIds = new TreeSet<ContentDigest>();
                localEvIds.add(ctx.evidence());
                for (var n : ctxNodes) localEvIds.addAll(n.evidence());
                for (var e : ctxEdges) localEvIds.addAll(e.evidence());
                referenced.addAll(localEvIds);
                var localEv = new ArrayList<GraphEvidence>(localEvIds.size());
                for (var evId : localEvIds) {
                    GraphEvidence ev = evById.get(evId);
                    if (ev != null) localEv.add(ev);
                }
                if (ctx.analysis().isEmpty()) {
                    mergedRootContext = ctx;
                    for (var n : ctxNodes) mergedRootNodes.put(n.identity(), n);
                    for (var e : ctxEdges) mergedRootEdges.put(e.identity(), e);
                    for (var ev : localEv) mergedRootEvidence.putIfAbsent(ev.identity(), ev);
                } else {
                    session.writeContextPartition(new GraphBinaryCache.ContextPartitionData(
                            ctx, ctxNodes, ctxEdges, localEv));
                }
            }
            for (var ev : subgraph.evidence()) {
                if (!referenced.contains(ev.identity())) {
                    crossOrOrphanEvidence.putIfAbsent(ev.identity(), ev);
                }
            }
        }

        public Optional<MerklePublication> publication() {
            return Optional.ofNullable(publication);
        }
    }

    private record LeafWriteResult(StoredLeafRef ref, boolean wroteNewBlob) {}

    private MerklePublication persistMerkleDecomposition(
            GraphBinaryCache.MerkleDecomposition decomp,
            Optional<ContentDigest> canonicalGraphIdentity,
            MerkleManifest base,
            BooleanSupplier cancelled) throws IOException {
        var contextRefs = new TreeMap<String, StoredLeafRef>();
        int dirtyWritten = 0;
        int reused = 0;
        long newBytes = 0L;

        for (var entry : decomp.contextLeaves().entrySet()) {
            check(cancelled);
            String ctxId = entry.getKey();
            GraphBinaryCache.MerkleLeaf leaf = entry.getValue();
            StoredLeafRef baseRef = base == null ? null : base.contextLeaves().get(ctxId);
            if (baseRef != null && baseRef.leafHash().equals(leaf.leafHash())
                    && content.hasObjectFast(baseRef.blobHandle())) {
                contextRefs.put(ctxId, baseRef);
                reused++;
                continue;
            }
            LeafWriteResult written = persistLeafBlob(leaf, cancelled);
            contextRefs.put(ctxId, written.ref());
            if (written.wroteNewBlob()) {
                dirtyWritten++;
                newBytes += written.ref().blobHandle().size();
            } else {
                reused++;
            }
        }

        GraphBinaryCache.MerkleLeaf crossLeaf = decomp.crossContextLeaf();
        StoredLeafRef crossRef;
        if (base != null && base.crossContextLeaf().leafHash().equals(crossLeaf.leafHash())
                && content.hasObjectFast(base.crossContextLeaf().blobHandle())) {
            crossRef = base.crossContextLeaf();
            reused++;
        } else {
            LeafWriteResult written = persistLeafBlob(crossLeaf, cancelled);
            crossRef = written.ref();
            if (written.wroteNewBlob()) {
                dirtyWritten++;
                newBytes += written.ref().blobHandle().size();
            } else {
                reused++;
            }
        }

        return finalizeMerkleManifest(decomp.snapshot(), canonicalGraphIdentity, contextRefs, crossRef,
                dirtyWritten, reused, newBytes, cancelled);
    }

    private LeafWriteResult persistLeafBlob(GraphBinaryCache.MerkleLeaf leaf, BooleanSupplier cancelled)
            throws IOException {
        byte[] payload = leaf.compressedPayload();
        ContentHandle candidate = new ContentHandle(ContentDigest.sha256(payload), payload.length);
        boolean existed = content.hasObjectFast(candidate);
        ContentHandle stored;
        if (existed) {
            stored = candidate;
        } else {
            stored = content.write(out -> out.write(payload), cancelled);
        }
        var ref = new StoredLeafRef(
                leaf.partitionKey(),
                leaf.leafHash(),
                stored,
                leaf.uncompressedBytes(),
                leaf.nodeCount(),
                leaf.edgeCount(),
                leaf.evidenceCount(),
                leaf.gapOccurrences());
        return new LeafWriteResult(ref, !existed);
    }

    private MerklePublication finalizeMerkleManifest(
            SnapshotIdentity snapshot,
            Optional<ContentDigest> canonicalGraphIdentity,
            TreeMap<String, StoredLeafRef> contextRefs,
            StoredLeafRef crossRef,
            int dirtyWritten,
            int reused,
            long newBytes,
            BooleanSupplier cancelled) throws IOException {
        var allGaps = new ArrayList<GraphBinaryCache.GapOccurrence>();
        var contextHashes = new TreeMap<String, ContentDigest>();
        long totalComp = crossRef.blobHandle().size();
        long totalUncomp = crossRef.uncompressedBytes();
        for (var entry : contextRefs.entrySet()) {
            StoredLeafRef ref = entry.getValue();
            contextHashes.put(entry.getKey(), ref.leafHash());
            allGaps.addAll(ref.gapOccurrences());
            totalComp += ref.blobHandle().size();
            totalUncomp += ref.uncompressedBytes();
        }
        GraphBinaryCache.CapabilitySummary summary = GraphBinaryCache.computeCapabilitySummary(allGaps);
        ContentDigest merkleRoot = GraphBinaryCache.computeMerkleRoot(
                contextHashes, crossRef.leafHash(), summary.digest());
        var manifest = new MerkleManifest(snapshot, merkleRoot, canonicalGraphIdentity, summary,
                Collections.unmodifiableMap(contextRefs), crossRef);
        Path manifestPath = writeMerkleManifest(manifest, cancelled);
        return new MerklePublication(
                merkleRoot,
                canonicalGraphIdentity,
                snapshot,
                contextHashes,
                crossRef.leafHash(),
                summary,
                contextRefs.size() + 1,
                dirtyWritten,
                reused,
                newBytes,
                totalComp,
                totalUncomp,
                manifestPath);
    }

    private GraphBinaryCache.MerkleLeaf loadLeaf(StoredLeafRef ref) throws IOException {
        byte[] compressed = content.readVerifiedBytes(ref.blobHandle());
        return new GraphBinaryCache.MerkleLeaf(
                ref.partitionKey(),
                ref.leafHash(),
                ref.uncompressedBytes(),
                compressed.length,
                compressed,
                ref.nodeCount(),
                ref.edgeCount(),
                ref.evidenceCount(),
                ref.gapOccurrences());
    }

    private Path merkleManifestPath(ContentDigest merkleGraphIdentity) throws IOException {
        Path dir = directory.resolve(MERKLE_V2_DIR_NAME);
        StreamingContentStore.safeWithin(directory, dir);
        Path file = dir.resolve(merkleGraphIdentity.value().substring(7) + ".evogbin2.manifest");
        StreamingContentStore.safeWithin(directory, file);
        return file;
    }

    private Path writeMerkleManifest(MerkleManifest manifest, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        Path dir = directory.resolve(MERKLE_V2_DIR_NAME);
        StreamingContentStore.ensureSubdirectory(directory, dir);
        Path target = merkleManifestPath(manifest.merkleGraphIdentity());
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return target;
        }
        byte[] encoded = encodeMerkleManifest(manifest);
        Path pending = Files.createTempFile(dir, "evogbin2-manifest-", ".part");
        try (var cleanup = new TemporaryFiles(pending)) {
            try (var channel = java.nio.channels.FileChannel.open(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buf = ByteBuffer.wrap(encoded);
                while (buf.hasRemaining()) channel.write(buf);
                channel.force(true);
            }
            check(cancelled);
            try {
                StreamingContentStore.createLinkWithRetry(target, pending);
            } catch (FileAlreadyExistsException raced) {
                return target;
            }
            return target;
        }
    }

    private static byte[] encodeMerkleManifest(MerkleManifest manifest) throws IOException {
        var baos = new ByteArrayOutputStream(512 + manifest.contextLeaves().size() * 96);
        HexFormat hex = HexFormat.of();
        baos.write(MERKLE_MANIFEST_MAGIC);
        GraphBinaryCache.writeVarInt(baos, MERKLE_MANIFEST_VERSION);
        GraphBinaryCache.writeVarUtf(baos, manifest.snapshot().value());
        baos.write(hex.parseHex(manifest.merkleGraphIdentity().value(), 7, 71));
        if (manifest.canonicalGraphIdentity().isPresent()) {
            baos.write(1);
            baos.write(hex.parseHex(manifest.canonicalGraphIdentity().orElseThrow().value(), 7, 71));
        } else {
            baos.write(0);
        }
        GraphBinaryCache.writeCapabilitySummary(baos, manifest.capabilitySummary(), hex);
        GraphBinaryCache.writeVarInt(baos, manifest.contextLeaves().size());
        for (var ref : manifest.contextLeaves().values()) {
            writeStoredLeafRef(baos, ref, hex);
        }
        writeStoredLeafRef(baos, manifest.crossContextLeaf(), hex);
        byte[] body = baos.toByteArray();
        byte[] trailer = StreamingContentStore.sha256().digest(body);
        byte[] full = Arrays.copyOf(body, body.length + 32);
        System.arraycopy(trailer, 0, full, body.length, 32);
        return full;
    }

    private MerkleManifest readMerkleManifest(ContentDigest merkleGraphIdentity, BooleanSupplier cancelled)
            throws IOException {
        check(cancelled);
        Path path = merkleManifestPath(merkleGraphIdentity);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("EVOGBIN2_MANIFEST_NOT_FOUND:" + merkleGraphIdentity.value());
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < MERKLE_MANIFEST_MAGIC.length + 32) {
            throw new IOException("EVOGBIN2_MANIFEST_CORRUPT");
        }
        byte[] expectedTrailer = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        byte[] actualTrailer = StreamingContentStore.sha256().digest(Arrays.copyOf(bytes, bytes.length - 32));
        if (!MessageDigest.isEqual(expectedTrailer, actualTrailer)) {
            throw new IOException("EVOGBIN2_MANIFEST_CHECKSUM_MISMATCH");
        }
        var in = new ByteArrayInputStream(bytes, 0, bytes.length - 32);
        byte[] magic = in.readNBytes(MERKLE_MANIFEST_MAGIC.length);
        if (!Arrays.equals(MERKLE_MANIFEST_MAGIC, magic)) {
            throw new IOException("EVOGBIN2_MANIFEST_INVALID_MAGIC");
        }
        int version = GraphBinaryCache.readVarInt(in);
        if (version != MERKLE_MANIFEST_VERSION) {
            throw new IOException("EVOGBIN2_MANIFEST_UNSUPPORTED_VERSION:" + version);
        }
        HexFormat hex = HexFormat.of();
        var snapshot = new SnapshotIdentity(GraphBinaryCache.readVarUtf(in));
        ContentDigest rootId = GraphBinaryCache.readRawDigest(in, hex);
        if (!rootId.equals(merkleGraphIdentity)) {
            throw new IOException("EVOGBIN2_MANIFEST_SCOPE_MISMATCH");
        }
        int hasCanonical = in.read();
        Optional<ContentDigest> canonicalId = hasCanonical == 1
                ? Optional.of(GraphBinaryCache.readRawDigest(in, hex))
                : Optional.empty();
        GraphBinaryCache.CapabilitySummary summary = GraphBinaryCache.readCapabilitySummary(in, hex);
        int ctxCount = GraphBinaryCache.readVarInt(in);
        var contextRefs = new TreeMap<String, StoredLeafRef>();
        for (int i = 0; i < ctxCount; i++) {
            StoredLeafRef ref = readStoredLeafRef(in, hex);
            contextRefs.put(ref.partitionKey(), ref);
        }
        StoredLeafRef crossRef = readStoredLeafRef(in, hex);
        if (in.read() != -1) {
            throw new IOException("EVOGBIN2_MANIFEST_TRAILING_BYTES");
        }
        return new MerkleManifest(snapshot, rootId, canonicalId, summary,
                Collections.unmodifiableMap(contextRefs), crossRef);
    }

    private static void writeStoredLeafRef(OutputStream out, StoredLeafRef ref, HexFormat hex) throws IOException {
        GraphBinaryCache.writeVarUtf(out, ref.partitionKey());
        out.write(hex.parseHex(ref.leafHash().value(), 7, 71));
        out.write(hex.parseHex(ref.blobHandle().digest().value(), 7, 71));
        GraphBinaryCache.writeVarLong(out, ref.blobHandle().size());
        GraphBinaryCache.writeVarInt(out, ref.uncompressedBytes());
        GraphBinaryCache.writeVarInt(out, ref.nodeCount());
        GraphBinaryCache.writeVarInt(out, ref.edgeCount());
        GraphBinaryCache.writeVarInt(out, ref.evidenceCount());
        GraphBinaryCache.writeVarInt(out, ref.gapOccurrences().size());
        for (var gap : ref.gapOccurrences()) {
            GraphBinaryCache.writeVarUtf(out, gap.gapNodeId());
            GraphBinaryCache.writeVarUtf(out, gap.reasonCode());
            GraphBinaryCache.writeVarLong(out, gap.occurrenceCount());
        }
    }

    private static StoredLeafRef readStoredLeafRef(InputStream in, HexFormat hex) throws IOException {
        String key = GraphBinaryCache.readVarUtf(in);
        ContentDigest leafHash = GraphBinaryCache.readRawDigest(in, hex);
        ContentDigest blobDigest = GraphBinaryCache.readRawDigest(in, hex);
        long blobSize = GraphBinaryCache.readVarLong(in);
        int uncompressedBytes = GraphBinaryCache.readVarInt(in);
        int nodeCount = GraphBinaryCache.readVarInt(in);
        int edgeCount = GraphBinaryCache.readVarInt(in);
        int evidenceCount = GraphBinaryCache.readVarInt(in);
        int gapCount = GraphBinaryCache.readVarInt(in);
        var gaps = gapCount == 0 ? List.<GraphBinaryCache.GapOccurrence>of() : new ArrayList<GraphBinaryCache.GapOccurrence>(gapCount);
        for (int i = 0; i < gapCount; i++) {
            String gapId = GraphBinaryCache.readVarUtf(in);
            String reason = GraphBinaryCache.readVarUtf(in);
            long occ = GraphBinaryCache.readVarLong(in);
            gaps.add(new GraphBinaryCache.GapOccurrence(gapId, reason, occ));
        }
        return new StoredLeafRef(key, leafHash, new ContentHandle(blobDigest, blobSize),
                uncompressedBytes, nodeCount, edgeCount, evidenceCount, gaps);
    }
}
