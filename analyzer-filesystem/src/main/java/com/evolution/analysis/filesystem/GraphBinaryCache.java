package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Disposable, SHA-256-authenticated binary acceleration cache for canonical KnowledgeGraphs.
 * <p>
 * The canonical {@code .graph.json} file remains the single source of truth. Every cached read
 * verifies the authoritative JSON file's existence, size, modified timestamp, boundary SHA-256,
 * header SHA-256, and trailing full-file SHA-256 digest before returning any graph. Any corruption
 * or mismatch deletes the stale cache entry and falls back cleanly to canonical JSON decoding.
 */
public final class GraphBinaryCache {
    public static final String CACHE_DIR_NAME = "graph-cache";
    private static final byte[] MAGIC = "EVOGBIN1".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;
    private static final int BOUNDARY_BYTES = 64 * 1024;
    private static final long DEFAULT_MIN_JSON_BYTES = 4L * 1024 * 1024;
    private static final int IO_BUFFER_BYTES = 64 * 1024;

    private final Path cacheDirectory;
    private final long minJsonBytes;

    public GraphBinaryCache(Path storeDirectory) {
        this(storeDirectory, Long.getLong("evo.graph.binaryCacheMinBytes", DEFAULT_MIN_JSON_BYTES));
    }

    public GraphBinaryCache(Path storeDirectory, long minJsonBytes) {
        this.cacheDirectory = Objects.requireNonNull(storeDirectory).toAbsolutePath().normalize().resolve(CACHE_DIR_NAME);
        this.minJsonBytes = Math.max(0L, minJsonBytes);
    }

    public Path cachePath(ContentDigest graphId) {
        Objects.requireNonNull(graphId);
        return cacheDirectory.resolve(graphId.value().substring(7) + ".graph.bin");
    }

    public boolean eligible(Path jsonPath) {
        try {
            return Files.isRegularFile(jsonPath, LinkOption.NOFOLLOW_LINKS) && Files.size(jsonPath) >= minJsonBytes;
        } catch (IOException unavailable) {
            return false;
        }
    }

    /** Reads only the header manifest in O(1) after verifying JSON binding and header SHA-256. */
    public Optional<GraphManifest> readManifest(ContentDigest graphId, Path jsonPath) {
        Objects.requireNonNull(graphId);
        Objects.requireNonNull(jsonPath);
        Path binPath = cachePath(graphId);
        if (!Files.isRegularFile(jsonPath, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(binPath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try (var in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(binPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), IO_BUFFER_BYTES))) {
            Header header = readHeader(in, jsonPath, graphId);
            if (header == null) {
                deleteQuietly(binPath);
                return Optional.empty();
            }
            return Optional.of(new GraphManifest(
                    KnowledgeGraph.SCHEMA,
                    header.graphIdentity(),
                    header.semanticIdentity(),
                    header.snapshot(),
                    header.contexts(),
                    header.nodeCount(),
                    header.edgeCount(),
                    header.evidenceCount()));
        } catch (IOException | RuntimeException corrupt) {
            deleteQuietly(binPath);
            return Optional.empty();
        }
    }

    /** Reads and fully checksum-verifies a cached binary graph bound to {@code jsonPath}. */
    public Optional<KnowledgeGraph> read(ContentDigest graphId, Path jsonPath) {
        Objects.requireNonNull(graphId);
        Objects.requireNonNull(jsonPath);
        Path binPath = cachePath(graphId);
        if (!Files.isRegularFile(jsonPath, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(binPath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            long totalBytes = Files.size(binPath);
            if (totalBytes < MAGIC.length + 32) {
                deleteQuietly(binPath);
                return Optional.empty();
            }
            MessageDigest fileDigest = MessageDigest.getInstance("SHA-256");
            try (var rawIn = new BufferedInputStream(
                    Files.newInputStream(binPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), IO_BUFFER_BYTES);
                 var digestIn = new DigestInputStream(rawIn, fileDigest);
                 var in = new DataInputStream(digestIn)) {
                Header header = readHeader(in, jsonPath, graphId);
                if (header == null) {
                    deleteQuietly(binPath);
                    return Optional.empty();
                }

                int stringCount = in.readInt();
                if (stringCount < 0 || stringCount > 50_000_000) throw new IOException("Invalid string count");
                String[] strings = new String[stringCount];
                for (int i = 0; i < stringCount; i++) {
                    strings[i] = readUtf(in);
                }

                int digestCount = in.readInt();
                if (digestCount < 0 || digestCount > 50_000_000) throw new IOException("Invalid digest count");
                ContentDigest[] digests = new ContentDigest[digestCount];
                byte[] rawHash = new byte[32];
                for (int i = 0; i < digestCount; i++) {
                    in.readFully(rawHash);
                    digests[i] = new ContentDigest("sha256:" + HexFormat.of().formatHex(rawHash));
                }

                int evidenceCount = header.evidenceCount();
                List<GraphEvidence> evidence = new ArrayList<>(evidenceCount);
                for (int i = 0; i < evidenceCount; i++) {
                    ContentDigest evId = digests[in.readInt()];
                    String family = strings[in.readInt()];
                    String canonicalJson = readUtf(in);
                    evidence.add(new GraphEvidence(evId, family, canonicalJson));
                }

                int nodeCount = header.nodeCount();
                List<KnowledgeGraph.Node> nodes = new ArrayList<>(nodeCount);
                for (int i = 0; i < nodeCount; i++) {
                    String nodeId = strings[in.readInt()];
                    String context = strings[in.readInt()];
                    String kind = strings[in.readInt()];
                    List<ContentDigest> evRefs = readDigestRefs(in, digests);
                    nodes.add(new KnowledgeGraph.Node(nodeId, context, kind, evRefs));
                }

                int edgeCount = header.edgeCount();
                List<KnowledgeGraph.Edge> edges = new ArrayList<>(edgeCount);
                KnowledgeGraph.TargetState[] targetStates = KnowledgeGraph.TargetState.values();
                for (int i = 0; i < edgeCount; i++) {
                    String edgeId = strings[in.readInt()];
                    String context = strings[in.readInt()];
                    String kind = strings[in.readInt()];
                    String source = strings[in.readInt()];
                    int stateOrd = in.readUnsignedByte();
                    if (stateOrd >= targetStates.length) throw new IOException("Invalid target state");
                    KnowledgeGraph.TargetState state = targetStates[stateOrd];
                    int tCount = in.readInt();
                    if (tCount < 0 || tCount > 1_000_000) throw new IOException("Invalid target count");
                    List<String> targets = new ArrayList<>(tCount);
                    for (int t = 0; t < tCount; t++) {
                        targets.add(strings[in.readInt()]);
                    }
                    int unresolvedIdx = in.readInt();
                    Optional<String> unresolvedRef = unresolvedIdx < 0 ? Optional.empty() : Optional.of(strings[unresolvedIdx]);
                    List<ContentDigest> evRefs = readDigestRefs(in, digests);
                    int regionIdx = in.readInt();
                    Optional<String> region = regionIdx < 0 ? Optional.empty() : Optional.of(strings[regionIdx]);
                    edges.add(new KnowledgeGraph.Edge(edgeId, context, kind, source,
                            new KnowledgeGraph.Target(state, targets, unresolvedRef), evRefs, region));
                }

                digestIn.on(false);
                byte[] expectedTrailer = new byte[32];
                in.readFully(expectedTrailer);
                if (in.read() != -1) {
                    deleteQuietly(binPath);
                    return Optional.empty();
                }
                byte[] actualFileDigest = fileDigest.digest();
                if (!MessageDigest.isEqual(expectedTrailer, actualFileDigest)) {
                    deleteQuietly(binPath);
                    return Optional.empty();
                }

                KnowledgeGraph restored = KnowledgeGraph.fromAuthenticatedCache(
                        header.snapshot(), header.contexts(), nodes, edges, evidence, header.graphIdentity());
                if (!restored.identity().equals(graphId)) {
                    deleteQuietly(binPath);
                    return Optional.empty();
                }
                return Optional.of(restored);
            }
        } catch (IOException | RuntimeException | NoSuchAlgorithmException corrupt) {
            deleteQuietly(binPath);
            return Optional.empty();
        }
    }

    /** Atomically writes the binary cache bound to {@code jsonPath} if {@code jsonPath} meets the size threshold. */
    public void write(KnowledgeGraph graph, Path jsonPath) {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(jsonPath);
        if (!eligible(jsonPath)) return;
        Path target = cachePath(graph.identity());
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path pending = null;
        try {
            Files.createDirectories(cacheDirectory);
            JsonSignature sig = signature(jsonPath);
            pending = Files.createTempFile(cacheDirectory, "graph-bin-", ".tmp");
            MessageDigest fileDigest = MessageDigest.getInstance("SHA-256");
            try (var fileOut = new BufferedOutputStream(
                    Files.newOutputStream(pending, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING), IO_BUFFER_BYTES);
                 var digestOut = new DigestOutputStream(fileOut, fileDigest);
                 var out = new DataOutputStream(digestOut)) {
                writeHeader(out, graph, sig);

                var stringPool = new LinkedHashMap<String, Integer>();
                var strings = new ArrayList<String>();
                var digestPool = new LinkedHashMap<ContentDigest, Integer>();
                var digests = new ArrayList<ContentDigest>();

                // Intern evidence
                var evRows = graph.evidence();
                for (var ev : evRows) {
                    internDigest(digestPool, digests, ev.identity());
                    internString(stringPool, strings, ev.family());
                }
                // Intern nodes
                var nodeRows = graph.nodes();
                for (var n : nodeRows) {
                    internString(stringPool, strings, n.identity());
                    internString(stringPool, strings, n.context());
                    internString(stringPool, strings, n.kind());
                    for (var ref : n.evidence()) internDigest(digestPool, digests, ref);
                }
                // Intern edges
                var edgeRows = graph.edges();
                for (var e : edgeRows) {
                    internString(stringPool, strings, e.identity());
                    internString(stringPool, strings, e.context());
                    internString(stringPool, strings, e.kind());
                    internString(stringPool, strings, e.source());
                    for (var tid : e.target().identities()) internString(stringPool, strings, tid);
                    e.target().unresolvedReference().ifPresent(r -> internString(stringPool, strings, r));
                    for (var ref : e.evidence()) internDigest(digestPool, digests, ref);
                    e.region().ifPresent(r -> internString(stringPool, strings, r));
                }

                out.writeInt(strings.size());
                for (String s : strings) writeUtf(out, s);

                out.writeInt(digests.size());
                for (ContentDigest d : digests) {
                    out.write(HexFormat.of().parseHex(d.value().substring(7)));
                }

                for (var ev : evRows) {
                    out.writeInt(digestPool.get(ev.identity()));
                    out.writeInt(stringPool.get(ev.family()));
                    writeUtf(out, ev.canonicalJson());
                }

                for (var n : nodeRows) {
                    out.writeInt(stringPool.get(n.identity()));
                    out.writeInt(stringPool.get(n.context()));
                    out.writeInt(stringPool.get(n.kind()));
                    writeDigestRefs(out, n.evidence(), digestPool);
                }

                for (var e : edgeRows) {
                    out.writeInt(stringPool.get(e.identity()));
                    out.writeInt(stringPool.get(e.context()));
                    out.writeInt(stringPool.get(e.kind()));
                    out.writeInt(stringPool.get(e.source()));
                    out.writeByte(e.target().state().ordinal());
                    out.writeInt(e.target().identities().size());
                    for (var tid : e.target().identities()) out.writeInt(stringPool.get(tid));
                    out.writeInt(e.target().unresolvedReference().map(stringPool::get).orElse(-1));
                    writeDigestRefs(out, e.evidence(), digestPool);
                    out.writeInt(e.region().map(stringPool::get).orElse(-1));
                }

                out.flush();
                digestOut.on(false);
                fileOut.write(fileDigest.digest());
                fileOut.flush();
            }
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException | NoSuchAlgorithmException ignored) {
            // Acceleration cache write failures must never abort canonical snapshot persistence.
        } finally {
            if (pending != null) deleteQuietly(pending);
        }
    }

    private record JsonSignature(long size, long modifiedMillis, byte[] boundarySha256) {}

    private record Header(
            ContentDigest graphIdentity,
            ContentDigest semanticIdentity,
            SnapshotIdentity snapshot,
            List<KnowledgeGraph.Context> contexts,
            int nodeCount,
            int edgeCount,
            int evidenceCount) {}

    private static void writeHeader(DataOutputStream out, KnowledgeGraph graph, JsonSignature sig)
            throws IOException, NoSuchAlgorithmException {
        var headerBytes = new ByteArrayOutputStream(512);
        var hout = new DataOutputStream(headerBytes);
        hout.write(MAGIC);
        hout.writeInt(FORMAT_VERSION);
        hout.writeLong(sig.size());
        hout.writeLong(sig.modifiedMillis());
        hout.write(sig.boundarySha256());
        writeUtf(hout, graph.identity().value());
        writeUtf(hout, graph.manifest().semanticIdentity().value());
        writeUtf(hout, graph.snapshot().value());
        hout.writeInt(graph.nodes().size());
        hout.writeInt(graph.edges().size());
        hout.writeInt(graph.evidence().size());
        var contexts = graph.contexts();
        hout.writeInt(contexts.size());
        for (var ctx : contexts) {
            writeUtf(hout, ctx.identity());
            writeUtf(hout, ctx.snapshot().value());
            writeUtf(hout, ctx.analysis().map(AnalysisIdentity::value).orElse(""));
            writeUtf(hout, ctx.evidence().value());
        }
        hout.flush();
        byte[] raw = headerBytes.toByteArray();
        byte[] headerHash = MessageDigest.getInstance("SHA-256").digest(raw);
        out.write(raw);
        out.write(headerHash);
    }

    private static Header readHeader(DataInputStream in, Path jsonPath, ContentDigest expectedGraphId)
            throws IOException {
        try {
            var capture = new ByteArrayOutputStream(512);
            var hOut = new DataOutputStream(capture);
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(MAGIC, magic)) return null;
            hOut.write(magic);

            int version = in.readInt();
            if (version != FORMAT_VERSION) return null;
            hOut.writeInt(version);

            long jsonSize = in.readLong();
            long jsonMtime = in.readLong();
            byte[] jsonBoundary = new byte[32];
            in.readFully(jsonBoundary);
            hOut.writeLong(jsonSize);
            hOut.writeLong(jsonMtime);
            hOut.write(jsonBoundary);

            JsonSignature liveSig = signature(jsonPath);
            if (liveSig.size() != jsonSize || liveSig.modifiedMillis() != jsonMtime
                    || !MessageDigest.isEqual(liveSig.boundarySha256(), jsonBoundary)) {
                return null;
            }

            String gIdStr = readUtf(in);
            String semIdStr = readUtf(in);
            String snapIdStr = readUtf(in);
            int nodeCount = in.readInt();
            int edgeCount = in.readInt();
            int evidenceCount = in.readInt();
            int ctxCount = in.readInt();
            if (ctxCount < 0 || ctxCount > 10_000 || nodeCount < 0 || edgeCount < 0 || evidenceCount < 0) {
                return null;
            }
            writeUtf(hOut, gIdStr);
            writeUtf(hOut, semIdStr);
            writeUtf(hOut, snapIdStr);
            hOut.writeInt(nodeCount);
            hOut.writeInt(edgeCount);
            hOut.writeInt(evidenceCount);
            hOut.writeInt(ctxCount);

            var contexts = new ArrayList<KnowledgeGraph.Context>(ctxCount);
            for (int i = 0; i < ctxCount; i++) {
                String cId = readUtf(in);
                String cSnap = readUtf(in);
                String cAnalysis = readUtf(in);
                String cEv = readUtf(in);
                writeUtf(hOut, cId);
                writeUtf(hOut, cSnap);
                writeUtf(hOut, cAnalysis);
                writeUtf(hOut, cEv);
                contexts.add(new KnowledgeGraph.Context(
                        cId,
                        new SnapshotIdentity(cSnap),
                        cAnalysis.isEmpty() ? Optional.empty() : Optional.of(new AnalysisIdentity(cAnalysis)),
                        new ContentDigest(cEv)));
            }
            hOut.flush();
            byte[] expectedHeaderHash = MessageDigest.getInstance("SHA-256").digest(capture.toByteArray());
            byte[] actualHeaderHash = new byte[32];
            in.readFully(actualHeaderHash);
            if (!MessageDigest.isEqual(expectedHeaderHash, actualHeaderHash)) {
                return null;
            }
            ContentDigest graphId = new ContentDigest(gIdStr);
            if (!graphId.equals(expectedGraphId)) {
                return null;
            }
            return new Header(graphId, new ContentDigest(semIdStr), new SnapshotIdentity(snapIdStr),
                    contexts, nodeCount, edgeCount, evidenceCount);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JsonSignature signature(Path jsonPath) throws IOException {
        try {
            long size = Files.size(jsonPath);
            long mtime = Files.getLastModifiedTime(jsonPath, LinkOption.NOFOLLOW_LINKS).toMillis();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileChannel channel = FileChannel.open(jsonPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (size <= 2L * BOUNDARY_BYTES) {
                    ByteBuffer buf = ByteBuffer.allocate(IO_BUFFER_BYTES);
                    while (channel.read(buf) != -1) {
                        buf.flip();
                        md.update(buf);
                        buf.clear();
                    }
                } else {
                    readSpanIntoDigest(channel, 0L, BOUNDARY_BYTES, md);
                    if (size > 3L * BOUNDARY_BYTES) {
                        readSpanIntoDigest(channel, size / 4L, BOUNDARY_BYTES, md);
                        readSpanIntoDigest(channel, size / 2L, BOUNDARY_BYTES, md);
                        readSpanIntoDigest(channel, (size / 4L) * 3L, BOUNDARY_BYTES, md);
                    }
                    readSpanIntoDigest(channel, size - BOUNDARY_BYTES, BOUNDARY_BYTES, md);
                }
            }
            return new JsonSignature(size, mtime, md.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void readSpanIntoDigest(FileChannel channel, long offset, int length, MessageDigest md)
            throws IOException {
        channel.position(offset);
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            if (channel.read(buf) == -1) break;
        }
        buf.flip();
        md.update(buf);
    }

    private static int internString(Map<String, Integer> pool, List<String> list, String value) {
        Integer existing = pool.get(value);
        if (existing != null) return existing;
        int idx = list.size();
        list.add(value);
        pool.put(value, idx);
        return idx;
    }

    private static int internDigest(Map<ContentDigest, Integer> pool, List<ContentDigest> list, ContentDigest value) {
        Integer existing = pool.get(value);
        if (existing != null) return existing;
        int idx = list.size();
        list.add(value);
        pool.put(value, idx);
        return idx;
    }

    private static void writeDigestRefs(DataOutputStream out, List<ContentDigest> refs, Map<ContentDigest, Integer> pool)
            throws IOException {
        out.writeInt(refs.size());
        for (var ref : refs) out.writeInt(pool.get(ref));
    }

    private static List<ContentDigest> readDigestRefs(DataInputStream in, ContentDigest[] digests) throws IOException {
        int count = in.readInt();
        if (count <= 0 || count > 100_000) throw new IOException("Invalid evidence reference count");
        if (count == 1) return List.of(digests[in.readInt()]);
        var list = new ArrayList<ContentDigest>(count);
        for (int i = 0; i < count; i++) list.add(digests[in.readInt()]);
        return list;
    }

    private static void writeUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 128 * 1024 * 1024) throw new IOException("Invalid UTF length: " + len);
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            /* Best-effort cleanup of disposable cache entry. */
        }
    }
}
