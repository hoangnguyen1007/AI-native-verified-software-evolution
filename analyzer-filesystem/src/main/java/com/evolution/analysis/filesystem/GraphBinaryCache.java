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
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Disposable, SHA-256-authenticated binary acceleration cache for canonical KnowledgeGraphs.
 * <p>
 * The canonical {@code .graph.json} file remains the single source of truth. Every cached read
 * verifies the authoritative JSON file's existence, size, modified timestamp, boundary SHA-256,
 * header SHA-256, and trailing full-file SHA-256 digest before returning any graph. Any corruption
 * or mismatch deletes the stale cache entry and falls back cleanly to canonical JSON decoding.
 * <p>
 * Format v2 (enabled by default via {@code -Devo.perf.graphBinaryV2=true}) stores the body in
 * 64 KB raw-DEFLATE ({@link Deflater#BEST_SPEED}) length-prefixed chunks for 4x-7x smaller cache
 * files and faster disk I/O while remaining 100% backward-compatible with v1 cache files on read.
 */
public final class GraphBinaryCache {
    public static final String CACHE_DIR_NAME = "graph-cache";
    private static final byte[] MAGIC = "EVOGBIN1".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] MAGIC_EVOGBIN2 = "EVOGBIN2".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION_V1 = 1;
    private static final int FORMAT_VERSION_V2 = 2;
    public static final int EVOGBIN2_VERSION = 2;
    public static final String CROSS_CONTEXT_PARTITION_KEY = "__cross_context__";
    private static final byte LEAF_TYPE_CONTEXT = 1;
    private static final byte LEAF_TYPE_CROSS_CONTEXT = 2;
    private static final int CHUNK_RAW_BYTES = 64 * 1024;
    private static final int MAX_CHUNK_RAW_BYTES = 1_048_576;
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

    static boolean useV2Format() {
        return Boolean.parseBoolean(System.getProperty("evo.perf.graphBinaryV2", "true"));
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
                 var outerIn = new DataInputStream(digestIn)) {
                Header header = readHeader(outerIn, jsonPath, graphId);
                if (header == null) {
                    deleteQuietly(binPath);
                    return Optional.empty();
                }

                DecodedBody decoded;
                if (header.version() == FORMAT_VERSION_V2) {
                    var chunkIn = new ChunkedInflateInputStream(outerIn);
                    try {
                        decoded = readBody(new DataInputStream(chunkIn), header);
                        if (chunkIn.read() != -1) {
                            deleteQuietly(binPath);
                            return Optional.empty();
                        }
                    } finally {
                        chunkIn.releaseInflater();
                    }
                } else {
                    decoded = readBody(outerIn, header);
                }

                digestIn.on(false);
                byte[] expectedTrailer = new byte[32];
                outerIn.readFully(expectedTrailer);
                if (outerIn.read() != -1) {
                    deleteQuietly(binPath);
                    return Optional.empty();
                }
                byte[] actualFileDigest = fileDigest.digest();
                if (!MessageDigest.isEqual(expectedTrailer, actualFileDigest)) {
                    deleteQuietly(binPath);
                    return Optional.empty();
                }

                // Full-file SHA-256 + header SHA-256 + .graph.json signature are now authenticated.
                KnowledgeGraph restored = KnowledgeGraph.fromAuthenticatedCache(
                        header.snapshot(), header.contexts(), decoded.nodes(), decoded.edges(),
                        decoded.evidence(), header.graphIdentity(), header.semanticIdentity());
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

    private record DecodedBody(
            List<GraphEvidence> evidence,
            List<KnowledgeGraph.Node> nodes,
            List<KnowledgeGraph.Edge> edges) {}

    private static DecodedBody readBody(DataInputStream in, Header header) throws IOException {
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
        HexFormat hex = HexFormat.of();
        for (int i = 0; i < digestCount; i++) {
            in.readFully(rawHash);
            digests[i] = new ContentDigest("sha256:" + hex.formatHex(rawHash));
        }

        int evidenceCount = header.evidenceCount();
        List<GraphEvidence> evidence = new ArrayList<>(evidenceCount);
        for (int i = 0; i < evidenceCount; i++) {
            ContentDigest evId = digests[in.readInt()];
            String family = strings[in.readInt()];
            String canonicalJson = readUtf(in);
            // Safe: read() never returns this list until the full-file SHA-256 trailer matches.
            evidence.add(GraphEvidence.fromAuthenticatedCache(evId, family, canonicalJson));
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
            List<String> targets = tCount == 0 ? List.of() : new ArrayList<>(tCount);
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
        return new DecodedBody(evidence, nodes, edges);
    }

    /** Atomically writes the binary cache bound to {@code jsonPath} if {@code jsonPath} meets the size threshold. */
    public void write(KnowledgeGraph graph, Path jsonPath) {
        write(graph, jsonPath, useV2Format() ? FORMAT_VERSION_V2 : FORMAT_VERSION_V1);
    }

    /** Package-private overload allowing tests to exercise v1 backward compatibility explicitly. */
    void write(KnowledgeGraph graph, Path jsonPath, int formatVersion) {
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
                 var outerOut = new DataOutputStream(digestOut)) {
                writeHeader(outerOut, graph, sig, formatVersion);

                if (formatVersion == FORMAT_VERSION_V2) {
                    var chunkOut = new ChunkedDeflateOutputStream(outerOut);
                    try {
                        var bodyOut = new DataOutputStream(chunkOut);
                        writeBody(bodyOut, graph);
                        bodyOut.flush();
                        chunkOut.finish();
                    } finally {
                        chunkOut.releaseDeflater();
                    }
                } else {
                    writeBody(outerOut, graph);
                }

                outerOut.flush();
                digestOut.on(false);
                fileOut.write(fileDigest.digest());
                fileOut.flush();
            }
            StreamingContentStore.moveAtomicWithRetry(pending, target);
        } catch (IOException | RuntimeException | NoSuchAlgorithmException ignored) {
            // Acceleration cache write failures must never abort canonical snapshot persistence.
        } finally {
            if (pending != null) deleteQuietly(pending);
        }
    }

    private static void writeBody(DataOutputStream out, KnowledgeGraph graph) throws IOException {
        var evRows = graph.evidence();
        var nodeRows = graph.nodes();
        var edgeRows = graph.edges();

        int estStrings = Math.max(64, nodeRows.size() + edgeRows.size() + 64);
        int estDigests = Math.max(64, evRows.size() + 16);
        var stringPool = new HashMap<String, Integer>(hashCapacity(estStrings));
        var strings = new ArrayList<String>(estStrings);
        var digestPool = new HashMap<ContentDigest, Integer>(hashCapacity(estDigests));
        var digests = new ArrayList<ContentDigest>(estDigests);

        for (int i = 0, len = evRows.size(); i < len; i++) {
            var ev = evRows.get(i);
            internDigest(digestPool, digests, ev.identity());
            internString(stringPool, strings, ev.family());
        }
        for (int i = 0, len = nodeRows.size(); i < len; i++) {
            var n = nodeRows.get(i);
            internString(stringPool, strings, n.identity());
            internString(stringPool, strings, n.context());
            internString(stringPool, strings, n.kind());
            var refs = n.evidence();
            for (int r = 0, rlen = refs.size(); r < rlen; r++) internDigest(digestPool, digests, refs.get(r));
        }
        for (int i = 0, len = edgeRows.size(); i < len; i++) {
            var e = edgeRows.get(i);
            internString(stringPool, strings, e.identity());
            internString(stringPool, strings, e.context());
            internString(stringPool, strings, e.kind());
            internString(stringPool, strings, e.source());
            var tids = e.target().identities();
            for (int t = 0, tlen = tids.size(); t < tlen; t++) internString(stringPool, strings, tids.get(t));
            if (e.target().unresolvedReference().isPresent()) {
                internString(stringPool, strings, e.target().unresolvedReference().orElseThrow());
            }
            var refs = e.evidence();
            for (int r = 0, rlen = refs.size(); r < rlen; r++) internDigest(digestPool, digests, refs.get(r));
            if (e.region().isPresent()) internString(stringPool, strings, e.region().orElseThrow());
        }

        out.writeInt(strings.size());
        for (int i = 0, len = strings.size(); i < len; i++) writeUtf(out, strings.get(i));

        out.writeInt(digests.size());
        HexFormat hex = HexFormat.of();
        for (int i = 0, len = digests.size(); i < len; i++) {
            out.write(hex.parseHex(digests.get(i).value(), 7, 71));
        }

        for (int i = 0, len = evRows.size(); i < len; i++) {
            var ev = evRows.get(i);
            out.writeInt(digestPool.get(ev.identity()));
            out.writeInt(stringPool.get(ev.family()));
            writeUtf(out, ev.canonicalJson());
        }

        for (int i = 0, len = nodeRows.size(); i < len; i++) {
            var n = nodeRows.get(i);
            out.writeInt(stringPool.get(n.identity()));
            out.writeInt(stringPool.get(n.context()));
            out.writeInt(stringPool.get(n.kind()));
            writeDigestRefs(out, n.evidence(), digestPool);
        }

        for (int i = 0, len = edgeRows.size(); i < len; i++) {
            var e = edgeRows.get(i);
            out.writeInt(stringPool.get(e.identity()));
            out.writeInt(stringPool.get(e.context()));
            out.writeInt(stringPool.get(e.kind()));
            out.writeInt(stringPool.get(e.source()));
            out.writeByte(e.target().state().ordinal());
            var tids = e.target().identities();
            out.writeInt(tids.size());
            for (int t = 0, tlen = tids.size(); t < tlen; t++) out.writeInt(stringPool.get(tids.get(t)));
            out.writeInt(e.target().unresolvedReference().isPresent()
                    ? stringPool.get(e.target().unresolvedReference().orElseThrow()) : -1);
            writeDigestRefs(out, e.evidence(), digestPool);
            out.writeInt(e.region().isPresent() ? stringPool.get(e.region().orElseThrow()) : -1);
        }
    }

    private static int hashCapacity(int elements) {
        return Math.max(16, (int) Math.min(Integer.MAX_VALUE / 2, (elements / 0.75f) + 1));
    }

    private static final int PARALLEL_CHUNK_WORKERS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    private static final java.util.concurrent.ExecutorService CHUNK_POOL = PARALLEL_CHUNK_WORKERS > 1
            ? java.util.concurrent.Executors.newFixedThreadPool(PARALLEL_CHUNK_WORKERS, r -> {
                Thread t = new Thread(r, "evo-graph-cache-codec");
                t.setDaemon(true);
                return t;
            })
            : null;

    private static byte[] deflateChunkOnce(byte[] src, int rawLen) {
        var deflater = new Deflater(Deflater.BEST_SPEED, true);
        try {
            deflater.setInput(src, 0, rawLen);
            deflater.finish();
            byte[] comp = new byte[rawLen + 1024];
            int total = 0;
            while (!deflater.finished()) {
                if (total == comp.length) {
                    comp = Arrays.copyOf(comp, comp.length + 16_384);
                }
                int n = deflater.deflate(comp, total, comp.length - total);
                if (n == 0 && deflater.needsInput()) break;
                total += n;
            }
            return Arrays.copyOf(comp, total);
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflateChunkOnce(byte[] compressed, int compLen, int rawLen) throws IOException {
        var inflater = new Inflater(true);
        try {
            byte[] raw = new byte[rawLen];
            inflater.setInput(compressed, 0, compLen);
            int total = 0;
            while (total < rawLen) {
                int n = inflater.inflate(raw, total, rawLen - total);
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
                total += n;
            }
            if (total != rawLen || !inflater.finished()) {
                throw new IOException("Truncated or trailing compressed graph cache chunk");
            }
            return raw;
        } catch (DataFormatException corrupt) {
            throw new IOException("Corrupt compressed graph cache chunk", corrupt);
        } finally {
            inflater.end();
        }
    }

    /** Length-prefixed 64 KB raw-DEFLATE chunk writer with parallel ordered chunk compression (S10). */
    private static final class ChunkedDeflateOutputStream extends OutputStream {
        private record PendingChunk(int rawLen, java.util.concurrent.Future<byte[]> compressed) {}
        private final DataOutputStream out;
        private final Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        private final ArrayDeque<PendingChunk> inFlight = new ArrayDeque<>(PARALLEL_CHUNK_WORKERS * 2);
        private final int maxInFlight = CHUNK_POOL == null ? 0 : PARALLEL_CHUNK_WORKERS * 2;
        private byte[] raw = new byte[CHUNK_RAW_BYTES];
        private byte[] compressed = new byte[CHUNK_RAW_BYTES + 1024];
        private int pos;
        private boolean finished;

        ChunkedDeflateOutputStream(DataOutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            if (pos == raw.length) flushChunk();
            raw[pos++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            int remaining = len;
            int offset = off;
            while (remaining > 0) {
                if (pos == raw.length) flushChunk();
                int take = Math.min(remaining, raw.length - pos);
                System.arraycopy(b, offset, raw, pos, take);
                pos += take;
                offset += take;
                remaining -= take;
            }
        }

        private void flushChunk() throws IOException {
            if (pos == 0) return;
            if (CHUNK_POOL != null) {
                final byte[] chunkBytes = raw;
                final int rawLen = pos;
                raw = new byte[CHUNK_RAW_BYTES];
                pos = 0;
                inFlight.addLast(new PendingChunk(rawLen, CHUNK_POOL.submit(() -> deflateChunkOnce(chunkBytes, rawLen))));
                if (inFlight.size() >= maxInFlight) {
                    drainOldest();
                }
                return;
            }
            deflater.reset();
            deflater.setInput(raw, 0, pos);
            deflater.finish();
            int total = 0;
            while (!deflater.finished()) {
                if (total == compressed.length) {
                    compressed = Arrays.copyOf(compressed, compressed.length + 16_384);
                }
                int n = deflater.deflate(compressed, total, compressed.length - total);
                if (n == 0 && deflater.needsInput()) break;
                total += n;
            }
            out.writeInt(pos);
            out.writeInt(total);
            out.write(compressed, 0, total);
            pos = 0;
        }

        private void drainOldest() throws IOException {
            PendingChunk head = inFlight.removeFirst();
            try {
                byte[] comp = head.compressed().get();
                out.writeInt(head.rawLen());
                out.writeInt(comp.length);
                out.write(comp);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Graph cache compression interrupted", interrupted);
            } catch (java.util.concurrent.ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof IOException io) throw io;
                throw new IOException("Graph cache compression failed", cause);
            }
        }

        void finish() throws IOException {
            if (finished) return;
            finished = true;
            flushChunk();
            while (!inFlight.isEmpty()) {
                drainOldest();
            }
            out.writeInt(0); // Exact end-of-body sentinel before trailing SHA-256.
        }

        void releaseDeflater() {
            for (var pending : inFlight) pending.compressed().cancel(true);
            inFlight.clear();
            deflater.end();
        }
    }

    /** Length-prefixed raw-DEFLATE chunk reader with parallel ordered prefetch decompression (S10). */
    private static final class ChunkedInflateInputStream extends InputStream {
        private final DataInputStream in;
        private final Inflater inflater = new Inflater(true);
        private final ArrayDeque<java.util.concurrent.Future<byte[]>> prefetched = new ArrayDeque<>(PARALLEL_CHUNK_WORKERS * 2);
        private final int maxPrefetch = CHUNK_POOL == null ? 0 : PARALLEL_CHUNK_WORKERS * 2;
        private byte[] compressed = new byte[CHUNK_RAW_BYTES + 1024];
        private byte[] raw = new byte[CHUNK_RAW_BYTES];
        private int pos;
        private int limit;
        private boolean streamEnded;
        private boolean eof;

        ChunkedInflateInputStream(DataInputStream in) {
            this.in = in;
        }

        private void fillPrefetch() throws IOException {
            while (!streamEnded && prefetched.size() < maxPrefetch) {
                int rawLen = in.readInt();
                if (rawLen == 0) {
                    streamEnded = true;
                    break;
                }
                if (rawLen < 0 || rawLen > MAX_CHUNK_RAW_BYTES) {
                    throw new IOException("Invalid compressed chunk raw length: " + rawLen);
                }
                int compLen = in.readInt();
                if (compLen <= 0 || compLen > rawLen + 65_536) {
                    throw new IOException("Invalid compressed chunk length: " + compLen);
                }
                byte[] compBytes = new byte[compLen];
                in.readFully(compBytes, 0, compLen);
                prefetched.addLast(CHUNK_POOL.submit(() -> inflateChunkOnce(compBytes, compLen, rawLen)));
            }
        }

        private boolean refill() throws IOException {
            if (eof) return false;
            if (CHUNK_POOL != null) {
                fillPrefetch();
                if (prefetched.isEmpty()) {
                    eof = true;
                    return false;
                }
                try {
                    raw = prefetched.removeFirst().get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Graph cache decompression interrupted", interrupted);
                } catch (java.util.concurrent.ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof IOException io) throw io;
                    throw new IOException("Graph cache decompression failed", cause);
                }
                pos = 0;
                limit = raw.length;
                return true;
            }
            int rawLen = in.readInt();
            if (rawLen == 0) {
                eof = true;
                return false;
            }
            if (rawLen < 0 || rawLen > MAX_CHUNK_RAW_BYTES) {
                throw new IOException("Invalid compressed chunk raw length: " + rawLen);
            }
            int compLen = in.readInt();
            if (compLen <= 0 || compLen > rawLen + 65_536) {
                throw new IOException("Invalid compressed chunk length: " + compLen);
            }
            if (compressed.length < compLen) compressed = new byte[compLen];
            in.readFully(compressed, 0, compLen);
            if (raw.length < rawLen) raw = new byte[rawLen];
            inflater.reset();
            inflater.setInput(compressed, 0, compLen);
            int total = 0;
            try {
                while (total < rawLen) {
                    int n = inflater.inflate(raw, total, rawLen - total);
                    if (n == 0) {
                        if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break;
                    }
                    total += n;
                }
            } catch (DataFormatException corrupt) {
                throw new IOException("Corrupt compressed graph cache chunk", corrupt);
            }
            if (total != rawLen || !inflater.finished()) {
                throw new IOException("Truncated or trailing compressed graph cache chunk");
            }
            pos = 0;
            limit = rawLen;
            return true;
        }

        @Override
        public int read() throws IOException {
            if (pos >= limit && !refill()) return -1;
            return raw[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) return 0;
            if (pos >= limit && !refill()) return -1;
            int copy = Math.min(len, limit - pos);
            System.arraycopy(raw, pos, b, off, copy);
            pos += copy;
            return copy;
        }

        void releaseInflater() {
            for (var f : prefetched) f.cancel(true);
            prefetched.clear();
            inflater.end();
        }
    }

    private record JsonSignature(long size, long modifiedMillis, byte[] boundarySha256) {}

    private record Header(
            int version,
            ContentDigest graphIdentity,
            ContentDigest semanticIdentity,
            SnapshotIdentity snapshot,
            List<KnowledgeGraph.Context> contexts,
            int nodeCount,
            int edgeCount,
            int evidenceCount) {}

    private static void writeHeader(DataOutputStream out, KnowledgeGraph graph, JsonSignature sig, int version)
            throws IOException, NoSuchAlgorithmException {
        var headerBytes = new ByteArrayOutputStream(512);
        var hout = new DataOutputStream(headerBytes);
        hout.write(MAGIC);
        hout.writeInt(version);
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
            if (version != FORMAT_VERSION_V1 && version != FORMAT_VERSION_V2) return null;
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
            return new Header(version, graphId, new ContentDigest(semIdStr), new SnapshotIdentity(snapIdStr),
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
        int size = refs.size();
        out.writeInt(size);
        for (int i = 0; i < size; i++) out.writeInt(pool.get(refs.get(i)));
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

    // =========================================================================
    // EVOGBIN2 — Canonical Merkle-Subtree Delta Binary Format (WP3.5)
    // =========================================================================

    /** Per-gap-node closed-denominator observation count preserved across Merkle leaves (I2/I3). */
    public record GapOccurrence(String gapNodeId, String reasonCode, long occurrenceCount)
            implements Comparable<GapOccurrence> {
        public GapOccurrence {
            Objects.requireNonNull(gapNodeId, "gapNodeId");
            reasonCode = (reasonCode == null || reasonCode.isBlank()) ? "UNCLASSIFIED_CAPABILITY_GAP" : reasonCode;
            if (occurrenceCount < 1L) {
                throw new IllegalArgumentException("Capability gap occurrenceCount must be positive");
            }
        }

        @Override
        public int compareTo(GapOccurrence other) {
            int cmp = gapNodeId.compareTo(other.gapNodeId);
            if (cmp != 0) return cmp;
            cmp = reasonCode.compareTo(other.reasonCode);
            return cmp != 0 ? cmp : Long.compare(occurrenceCount, other.occurrenceCount);
        }
    }

    /** Deterministic closed-denominator capability summary over all unique gap nodes and exact occurrenceCount sums. */
    public record CapabilitySummary(
            long uniqueGapNodes,
            long totalOccurrenceCount,
            Map<String, Long> occurrenceCountByReason,
            Map<String, Long> uniqueGapCountByReason,
            ContentDigest digest) {
        public CapabilitySummary {
            occurrenceCountByReason = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(occurrenceCountByReason)));
            uniqueGapCountByReason = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(uniqueGapCountByReason)));
            Objects.requireNonNull(digest, "digest");
        }
    }

    /** Self-contained canonical data for one context partition leaf (lossless bijection with graph partition). */
    public record ContextPartitionData(
            KnowledgeGraph.Context context,
            List<KnowledgeGraph.Node> nodes,
            List<KnowledgeGraph.Edge> intraContextEdges,
            List<GraphEvidence> evidence) {
        public ContextPartitionData {
            Objects.requireNonNull(context, "context");
            nodes = nodes.stream()
                    .sorted(Comparator.comparing(KnowledgeGraph.Node::identity))
                    .distinct()
                    .toList();
            intraContextEdges = intraContextEdges.stream()
                    .sorted(Comparator.comparing(KnowledgeGraph.Edge::identity))
                    .distinct()
                    .toList();
            evidence = evidence.stream()
                    .sorted(Comparator.comparing(e -> e.identity().value()))
                    .distinct()
                    .toList();
        }
    }

    /** Compressed deterministic EVOGBIN2 Merkle leaf blob and its canonical metadata. */
    public record MerkleLeaf(
            String partitionKey,
            ContentDigest leafHash,
            int uncompressedBytes,
            int compressedBytes,
            byte[] compressedPayload,
            int nodeCount,
            int edgeCount,
            int evidenceCount,
            List<GapOccurrence> gapOccurrences) {
        public MerkleLeaf {
            Objects.requireNonNull(partitionKey, "partitionKey");
            Objects.requireNonNull(leafHash, "leafHash");
            compressedPayload = Objects.requireNonNull(compressedPayload, "compressedPayload").clone();
            if (compressedBytes != compressedPayload.length) {
                throw new IllegalArgumentException("Compressed leaf length mismatch");
            }
            gapOccurrences = gapOccurrences.stream().sorted().distinct().toList();
        }

        @Override
        public byte[] compressedPayload() {
            return compressedPayload.clone();
        }

        public ContentDigest compressedBlobDigest() {
            return ContentDigest.sha256(compressedPayload);
        }
    }

    /** Decomposed cross-context edges and any unreferenced graph evidence. */
    public record CrossContextPartitionData(
            List<KnowledgeGraph.Edge> crossContextEdges,
            List<GraphEvidence> evidence) {
        public CrossContextPartitionData {
            crossContextEdges = crossContextEdges.stream()
                    .sorted(Comparator.comparing(KnowledgeGraph.Edge::context)
                            .thenComparing(KnowledgeGraph.Edge::identity))
                    .distinct()
                    .toList();
            evidence = evidence.stream()
                    .sorted(Comparator.comparing(e -> e.identity().value()))
                    .distinct()
                    .toList();
        }
    }

    /** Full canonical EVOGBIN2 Merkle decomposition of a KnowledgeGraph. */
    public record MerkleDecomposition(
            SnapshotIdentity snapshot,
            ContentDigest merkleGraphIdentity,
            Map<String, MerkleLeaf> contextLeaves,
            MerkleLeaf crossContextLeaf,
            CapabilitySummary capabilitySummary) {
        public MerkleDecomposition {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(merkleGraphIdentity, "merkleGraphIdentity");
            contextLeaves = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(contextLeaves)));
            Objects.requireNonNull(crossContextLeaf, "crossContextLeaf");
            Objects.requireNonNull(capabilitySummary, "capabilitySummary");
        }

        public Map<String, ContentDigest> contextPartitionHashes() {
            var hashes = new LinkedHashMap<String, ContentDigest>(contextLeaves.size() * 2);
            for (var entry : contextLeaves.entrySet()) {
                hashes.put(entry.getKey(), entry.getValue().leafHash());
            }
            return Collections.unmodifiableMap(hashes);
        }
    }

    /** Verified decoded result of an EVOGBIN2 payload or Merkle snapshot. */
    public record DecodedEvogbin2(
            KnowledgeGraph graph,
            ContentDigest merkleGraphIdentity,
            Map<String, ContentDigest> contextPartitionHashes,
            ContentDigest crossContextEdgesHash,
            CapabilitySummary capabilitySummary) {
        public DecodedEvogbin2 {
            Objects.requireNonNull(graph, "graph");
            Objects.requireNonNull(merkleGraphIdentity, "merkleGraphIdentity");
            contextPartitionHashes = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(contextPartitionHashes)));
            Objects.requireNonNull(crossContextEdgesHash, "crossContextEdgesHash");
            Objects.requireNonNull(capabilitySummary, "capabilitySummary");
        }
    }

    /** Pure local edge classifier separating cross-boundary edges from intra-context partition leaves. */
    public static boolean isCrossContextEdge(KnowledgeGraph.Edge edge) {
        String kind = Objects.requireNonNull(edge).kind();
        return kind.startsWith("cross.")
                || kind.startsWith("cross-context.")
                || kind.startsWith("service.")
                || kind.startsWith("polyglot.")
                || kind.startsWith("http.")
                || kind.startsWith("grpc.")
                || kind.startsWith("messaging.")
                || kind.startsWith("module.depends")
                || kind.startsWith("architecture.cross")
                || kind.contains("cross-module")
                || kind.contains("cross_context");
    }

    /** Extracts exact capability-gap observation count (default 1; preserves coalesced {@code occurrenceCount=N}). */
    public static long extractGapOccurrenceCount(GraphEvidence ev) {
        if (ev == null || !"evidence.gap".equals(ev.family())) {
            return 1L;
        }
        String json = ev.canonicalJson();
        if (!json.contains("occurrenceCount")) {
            return 1L;
        }
        try {
            var obj = GraphJson.object(GraphJson.read(json, new GraphJson.Limits(
                    Math.max(GraphJson.Limits.defaults().maxCharacters(), json.length() + 16), 128, Integer.MAX_VALUE - 8)));
            Object direct = obj.get("occurrenceCount");
            if (direct instanceof Number n) {
                long v = n.longValue();
                if (v > 0L) return v;
            } else if (direct instanceof String s) {
                try {
                    long v = Long.parseLong(s.strip());
                    if (v > 0L) return v;
                } catch (NumberFormatException ignored) {
                }
            }
            Object rawLimitations = obj.get("limitations");
            if (rawLimitations instanceof List<?> limitations) {
                for (Object item : limitations) {
                    if (item instanceof String text && text.startsWith("occurrenceCount=")) {
                        try {
                            long v = Long.parseLong(text.substring("occurrenceCount=".length()).strip());
                            if (v > 0L) return v;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to default observation count of 1.
        }
        return 1L;
    }

    /** Extracts the canonical {@code reasonCode} from an {@code evidence.gap} payload. */
    public static String extractGapReasonCode(GraphEvidence ev) {
        if (ev == null || !"evidence.gap".equals(ev.family())) {
            return "UNCLASSIFIED_CAPABILITY_GAP";
        }
        String json = ev.canonicalJson();
        try {
            var obj = GraphJson.object(GraphJson.read(json, new GraphJson.Limits(
                    Math.max(GraphJson.Limits.defaults().maxCharacters(), json.length() + 16), 128, Integer.MAX_VALUE - 8)));
            Object code = obj.get("reasonCode");
            if (code instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (RuntimeException ignored) {
            // Fall through to default unclassified gap reason.
        }
        return "UNCLASSIFIED_CAPABILITY_GAP";
    }

    /** Extracts per-node gap occurrences for all {@code CAPABILITY_GAP} nodes in a partition. */
    public static List<GapOccurrence> extractGapOccurrences(
            List<KnowledgeGraph.Node> nodes, Map<ContentDigest, GraphEvidence> evidenceById) {
        if (nodes.isEmpty()) return List.of();
        var byNodeId = new TreeMap<String, GapOccurrence>();
        for (var node : nodes) {
            if (!"CAPABILITY_GAP".equals(node.kind())) continue;
            String reason = "UNCLASSIFIED_CAPABILITY_GAP";
            long occ = 1L;
            for (var evId : node.evidence()) {
                GraphEvidence ev = evidenceById.get(evId);
                if (ev != null && "evidence.gap".equals(ev.family())) {
                    reason = extractGapReasonCode(ev);
                    occ = extractGapOccurrenceCount(ev);
                    break;
                }
            }
            byNodeId.merge(node.identity(), new GapOccurrence(node.identity(), reason, occ),
                    (a, b) -> a.occurrenceCount() >= b.occurrenceCount() ? a : b);
        }
        return List.copyOf(byNodeId.values());
    }

    /** Computes deterministic global CapabilitySummary over unique gap nodes preserving exact occurrenceCount sums. */
    public static CapabilitySummary computeCapabilitySummary(Collection<GapOccurrence> occurrences) {
        var uniqueByGapId = new TreeMap<String, GapOccurrence>();
        for (var occ : occurrences) {
            uniqueByGapId.merge(occ.gapNodeId(), occ,
                    (a, b) -> a.occurrenceCount() >= b.occurrenceCount() ? a : b);
        }
        long uniqueNodes = 0L;
        long totalOccurrences = 0L;
        var occByReason = new TreeMap<String, Long>();
        var uniqueByReason = new TreeMap<String, Long>();
        for (var occ : uniqueByGapId.values()) {
            uniqueNodes++;
            totalOccurrences = Math.addExact(totalOccurrences, occ.occurrenceCount());
            occByReason.merge(occ.reasonCode(), occ.occurrenceCount(), Math::addExact);
            uniqueByReason.merge(occ.reasonCode(), 1L, Math::addExact);
        }
        try {
            var baos = new ByteArrayOutputStream(128);
            baos.write("EVOGBIN2-CAPABILITY-SUMMARY:v2\0".getBytes(StandardCharsets.US_ASCII));
            writeVarLong(baos, uniqueNodes);
            writeVarLong(baos, totalOccurrences);
            writeVarInt(baos, occByReason.size());
            for (var entry : occByReason.entrySet()) {
                writeVarUtf(baos, entry.getKey());
                writeVarLong(baos, entry.getValue());
                writeVarLong(baos, uniqueByReason.getOrDefault(entry.getKey(), 0L));
            }
            ContentDigest digest = ContentDigest.sha256(baos.toByteArray());
            return new CapabilitySummary(uniqueNodes, totalOccurrences, occByReason, uniqueByReason, digest);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Computes the deterministic Merkle Root SHA-256 ({@code merkleGraphIdentity}) over
     * {@code (sortedContextPartitionHashes, crossContextEdgesHash, capabilitySummaryHash)}.
     */
    public static ContentDigest computeMerkleRoot(
            Map<String, ContentDigest> contextPartitionHashes,
            ContentDigest crossContextEdgesHash,
            ContentDigest capabilitySummaryHash) {
        Objects.requireNonNull(contextPartitionHashes, "contextPartitionHashes");
        Objects.requireNonNull(crossContextEdgesHash, "crossContextEdgesHash");
        Objects.requireNonNull(capabilitySummaryHash, "capabilitySummaryHash");
        var sorted = new TreeMap<>(contextPartitionHashes);
        try {
            var baos = new ByteArrayOutputStream(128 + sorted.size() * 64);
            baos.write("EVOGBIN2-MERKLE-ROOT:v2\0".getBytes(StandardCharsets.US_ASCII));
            writeVarInt(baos, sorted.size());
            HexFormat hex = HexFormat.of();
            for (var entry : sorted.entrySet()) {
                writeVarUtf(baos, entry.getKey());
                baos.write(hex.parseHex(entry.getValue().value(), 7, 71));
            }
            baos.write(hex.parseHex(crossContextEdgesHash.value(), 7, 71));
            baos.write(hex.parseHex(capabilitySummaryHash.value(), 7, 71));
            return ContentDigest.sha256(baos.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Decomposes a canonical KnowledgeGraph into deterministic EVOGBIN2 Merkle partition leaves. */
    public static MerkleDecomposition decomposeMerkle(KnowledgeGraph graph) {
        Objects.requireNonNull(graph, "graph");
        var evidenceById = new HashMap<ContentDigest, GraphEvidence>(hashCapacity(graph.evidence().size()));
        for (var ev : graph.evidence()) {
            evidenceById.put(ev.identity(), ev);
        }
        var nodesByCtx = new HashMap<String, List<KnowledgeGraph.Node>>(hashCapacity(graph.contexts().size()));
        for (var node : graph.nodes()) {
            nodesByCtx.computeIfAbsent(node.context(), ignored -> new ArrayList<>()).add(node);
        }
        var intraEdgesByCtx = new HashMap<String, List<KnowledgeGraph.Edge>>(hashCapacity(graph.contexts().size()));
        var crossEdges = new ArrayList<KnowledgeGraph.Edge>();
        for (var edge : graph.edges()) {
            if (isCrossContextEdge(edge)) {
                crossEdges.add(edge);
            } else {
                intraEdgesByCtx.computeIfAbsent(edge.context(), ignored -> new ArrayList<>()).add(edge);
            }
        }
        var referencedEvidence = new HashSet<ContentDigest>(hashCapacity(graph.evidence().size()));
        var contextLeaves = new TreeMap<String, MerkleLeaf>();
        var allGaps = new ArrayList<GapOccurrence>();

        for (var ctx : graph.contexts()) {
            var ctxNodes = nodesByCtx.getOrDefault(ctx.identity(), List.of());
            var ctxEdges = intraEdgesByCtx.getOrDefault(ctx.identity(), List.of());
            var localEvIds = new TreeSet<ContentDigest>();
            localEvIds.add(ctx.evidence());
            for (var n : ctxNodes) localEvIds.addAll(n.evidence());
            for (var e : ctxEdges) localEvIds.addAll(e.evidence());
            referencedEvidence.addAll(localEvIds);
            var localEvidence = new ArrayList<GraphEvidence>(localEvIds.size());
            for (var evId : localEvIds) {
                GraphEvidence ev = evidenceById.get(evId);
                if (ev == null) {
                    throw new IllegalArgumentException("Missing graph evidence: " + evId.value());
                }
                localEvidence.add(ev);
            }
            MerkleLeaf leaf = encodeContextLeaf(new ContextPartitionData(ctx, ctxNodes, ctxEdges, localEvidence));
            contextLeaves.put(ctx.identity(), leaf);
            allGaps.addAll(leaf.gapOccurrences());
        }

        var crossEvIds = new TreeSet<ContentDigest>();
        for (var e : crossEdges) {
            crossEvIds.addAll(e.evidence());
            referencedEvidence.addAll(e.evidence());
        }
        // Preserve any unreferenced evidence records so decode(encode(G)) == G is a strict 1-to-1 bijection.
        for (var ev : graph.evidence()) {
            if (!referencedEvidence.contains(ev.identity())) {
                crossEvIds.add(ev.identity());
            }
        }
        var crossEvidence = new ArrayList<GraphEvidence>(crossEvIds.size());
        for (var evId : crossEvIds) {
            GraphEvidence ev = evidenceById.get(evId);
            if (ev != null) crossEvidence.add(ev);
        }
        MerkleLeaf crossLeaf = encodeCrossContextLeaf(new CrossContextPartitionData(crossEdges, crossEvidence));
        CapabilitySummary summary = computeCapabilitySummary(allGaps);
        var partHashes = new TreeMap<String, ContentDigest>();
        for (var entry : contextLeaves.entrySet()) {
            partHashes.put(entry.getKey(), entry.getValue().leafHash());
        }
        ContentDigest merkleRoot = computeMerkleRoot(partHashes, crossLeaf.leafHash(), summary.digest());
        return new MerkleDecomposition(graph.snapshot(), merkleRoot, contextLeaves, crossLeaf, summary);
    }

    /** Encodes one context partition into a deterministic compressed EVOGBIN2 Merkle leaf (varint + sorted string table + DEFLATE). */
    public static MerkleLeaf encodeContextLeaf(ContextPartitionData data) {
        Objects.requireNonNull(data, "data");
        var ctx = data.context();
        var nodes = data.nodes();
        var edges = data.intraContextEdges();
        var evRows = data.evidence();

        var stringSet = new TreeSet<String>();
        var digestSet = new TreeSet<ContentDigest>();
        stringSet.add(ctx.identity());
        ctx.analysis().ifPresent(a -> stringSet.add(a.value()));
        digestSet.add(ctx.evidence());

        var evById = new HashMap<ContentDigest, GraphEvidence>(hashCapacity(evRows.size()));
        for (var ev : evRows) {
            evById.put(ev.identity(), ev);
            digestSet.add(ev.identity());
            stringSet.add(ev.family());
            stringSet.add(ev.canonicalJson());
        }
        for (var n : nodes) {
            stringSet.add(n.identity());
            stringSet.add(n.kind());
            digestSet.addAll(n.evidence());
        }
        for (var e : edges) {
            stringSet.add(e.identity());
            stringSet.add(e.kind());
            stringSet.add(e.source());
            stringSet.addAll(e.target().identities());
            e.target().unresolvedReference().ifPresent(stringSet::add);
            digestSet.addAll(e.evidence());
            e.region().ifPresent(stringSet::add);
        }

        var sortedStrings = new ArrayList<String>(stringSet);
        var stringIdx = new HashMap<String, Integer>(hashCapacity(sortedStrings.size()));
        for (int i = 0; i < sortedStrings.size(); i++) stringIdx.put(sortedStrings.get(i), i);

        var sortedDigests = new ArrayList<ContentDigest>(digestSet);
        var digestIdx = new HashMap<ContentDigest, Integer>(hashCapacity(sortedDigests.size()));
        for (int i = 0; i < sortedDigests.size(); i++) digestIdx.put(sortedDigests.get(i), i);

        try {
            var rawOut = new ByteArrayOutputStream(Math.max(256, evRows.size() * 96 + nodes.size() * 24 + edges.size() * 32));
            rawOut.write(LEAF_TYPE_CONTEXT);
            writeSortedTables(rawOut, sortedStrings, sortedDigests);

            // Context metadata (snapshot lives in EVOGBIN2 root header so unchanged contexts deduplicate across commits).
            writeVarInt(rawOut, stringIdx.get(ctx.identity()));
            if (ctx.analysis().isPresent()) {
                rawOut.write(1);
                writeVarInt(rawOut, stringIdx.get(ctx.analysis().orElseThrow().value()));
            } else {
                rawOut.write(0);
            }
            writeVarInt(rawOut, digestIdx.get(ctx.evidence()));

            // Evidence rows
            writeVarInt(rawOut, evRows.size());
            for (var ev : evRows) {
                writeVarInt(rawOut, digestIdx.get(ev.identity()));
                writeVarInt(rawOut, stringIdx.get(ev.family()));
                writeVarInt(rawOut, stringIdx.get(ev.canonicalJson()));
            }

            // Node rows
            writeVarInt(rawOut, nodes.size());
            for (var n : nodes) {
                writeVarInt(rawOut, stringIdx.get(n.identity()));
                writeVarInt(rawOut, stringIdx.get(n.kind()));
                writeVarInt(rawOut, n.evidence().size());
                for (var ref : n.evidence()) writeVarInt(rawOut, digestIdx.get(ref));
            }

            // Intra-context edge rows
            writeVarInt(rawOut, edges.size());
            for (var e : edges) {
                writeEncodedEdge(rawOut, e, false, stringIdx, digestIdx);
            }

            byte[] uncompressed = rawOut.toByteArray();
            ContentDigest leafHash = ContentDigest.sha256(uncompressed);
            byte[] compressed = deflateDeterministic(uncompressed);
            List<GapOccurrence> gaps = extractGapOccurrences(nodes, evById);
            return new MerkleLeaf(ctx.identity(), leafHash, uncompressed.length, compressed.length, compressed,
                    nodes.size(), edges.size(), evRows.size(), gaps);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Decodes and SHA-256-authenticates a context partition leaf. */
    public static ContextPartitionData decodeContextLeaf(MerkleLeaf leaf, SnapshotIdentity snapshot) throws IOException {
        Objects.requireNonNull(leaf, "leaf");
        Objects.requireNonNull(snapshot, "snapshot");
        byte[] uncompressed = inflateDeterministic(leaf.compressedPayload(), leaf.uncompressedBytes());
        if (!ContentDigest.sha256(uncompressed).equals(leaf.leafHash())) {
            throw new IOException("EVOGBIN2_LEAF_HASH_MISMATCH:" + leaf.partitionKey());
        }
        var in = new ByteArrayInputStream(uncompressed);
        int type = in.read();
        if (type != LEAF_TYPE_CONTEXT) {
            throw new IOException("EVOGBIN2_INVALID_CONTEXT_LEAF_TYPE:" + type);
        }
        String[] strings = readStringTable(in);
        ContentDigest[] digests = readDigestTable(in);

        String ctxId = strings[readCheckedIndex(in, strings.length)];
        int hasAnalysis = in.read();
        if (hasAnalysis != 0 && hasAnalysis != 1) throw new IOException("EVOGBIN2_INVALID_ANALYSIS_FLAG");
        Optional<AnalysisIdentity> analysis = hasAnalysis == 1
                ? Optional.of(new AnalysisIdentity(strings[readCheckedIndex(in, strings.length)]))
                : Optional.empty();
        ContentDigest ctxEvidence = digests[readCheckedIndex(in, digests.length)];
        var ctx = new KnowledgeGraph.Context(ctxId, snapshot, analysis, ctxEvidence);

        int evCount = readVarInt(in);
        var evidence = new ArrayList<GraphEvidence>(evCount);
        for (int i = 0; i < evCount; i++) {
            ContentDigest evId = digests[readCheckedIndex(in, digests.length)];
            String family = strings[readCheckedIndex(in, strings.length)];
            String json = strings[readCheckedIndex(in, strings.length)];
            evidence.add(GraphEvidence.fromAuthenticatedCache(evId, family, json));
        }

        int nodeCount = readVarInt(in);
        var nodes = new ArrayList<KnowledgeGraph.Node>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            String nodeId = strings[readCheckedIndex(in, strings.length)];
            String kind = strings[readCheckedIndex(in, strings.length)];
            int refCount = readVarInt(in);
            if (refCount <= 0 || refCount > 1_000_000) throw new IOException("EVOGBIN2_INVALID_NODE_EV_COUNT");
            var refs = new ArrayList<ContentDigest>(refCount);
            for (int r = 0; r < refCount; r++) refs.add(digests[readCheckedIndex(in, digests.length)]);
            nodes.add(new KnowledgeGraph.Node(nodeId, ctxId, kind, refs));
        }

        int edgeCount = readVarInt(in);
        var edges = new ArrayList<KnowledgeGraph.Edge>(edgeCount);
        for (int i = 0; i < edgeCount; i++) {
            edges.add(readEncodedEdge(in, ctxId, strings, digests));
        }
        if (in.read() != -1) {
            throw new IOException("EVOGBIN2_TRAILING_LEAF_BYTES");
        }
        return new ContextPartitionData(ctx, nodes, edges, evidence);
    }

    /** Encodes cross-context edges and any unreferenced graph evidence into a deterministic compressed EVOGBIN2 leaf. */
    public static MerkleLeaf encodeCrossContextLeaf(CrossContextPartitionData data) {
        Objects.requireNonNull(data, "data");
        var edges = data.crossContextEdges();
        var evRows = data.evidence();
        var stringSet = new TreeSet<String>();
        var digestSet = new TreeSet<ContentDigest>();
        for (var ev : evRows) {
            digestSet.add(ev.identity());
            stringSet.add(ev.family());
            stringSet.add(ev.canonicalJson());
        }
        for (var e : edges) {
            stringSet.add(e.context());
            stringSet.add(e.identity());
            stringSet.add(e.kind());
            stringSet.add(e.source());
            stringSet.addAll(e.target().identities());
            e.target().unresolvedReference().ifPresent(stringSet::add);
            digestSet.addAll(e.evidence());
            e.region().ifPresent(stringSet::add);
        }

        var sortedStrings = new ArrayList<String>(stringSet);
        var stringIdx = new HashMap<String, Integer>(hashCapacity(sortedStrings.size()));
        for (int i = 0; i < sortedStrings.size(); i++) stringIdx.put(sortedStrings.get(i), i);

        var sortedDigests = new ArrayList<ContentDigest>(digestSet);
        var digestIdx = new HashMap<ContentDigest, Integer>(hashCapacity(sortedDigests.size()));
        for (int i = 0; i < sortedDigests.size(); i++) digestIdx.put(sortedDigests.get(i), i);

        try {
            var rawOut = new ByteArrayOutputStream(Math.max(64, edges.size() * 32 + evRows.size() * 64));
            rawOut.write(LEAF_TYPE_CROSS_CONTEXT);
            writeSortedTables(rawOut, sortedStrings, sortedDigests);
            writeVarInt(rawOut, evRows.size());
            for (var ev : evRows) {
                writeVarInt(rawOut, digestIdx.get(ev.identity()));
                writeVarInt(rawOut, stringIdx.get(ev.family()));
                writeVarInt(rawOut, stringIdx.get(ev.canonicalJson()));
            }
            writeVarInt(rawOut, edges.size());
            for (var e : edges) {
                writeEncodedEdge(rawOut, e, true, stringIdx, digestIdx);
            }
            byte[] uncompressed = rawOut.toByteArray();
            ContentDigest leafHash = ContentDigest.sha256(uncompressed);
            byte[] compressed = deflateDeterministic(uncompressed);
            return new MerkleLeaf(CROSS_CONTEXT_PARTITION_KEY, leafHash, uncompressed.length, compressed.length,
                    compressed, 0, edges.size(), evRows.size(), List.of());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Decodes and SHA-256-authenticates the cross-context edges & orphan-evidence leaf. */
    public static CrossContextPartitionData decodeCrossContextLeaf(MerkleLeaf leaf) throws IOException {
        Objects.requireNonNull(leaf, "leaf");
        byte[] uncompressed = inflateDeterministic(leaf.compressedPayload(), leaf.uncompressedBytes());
        if (!ContentDigest.sha256(uncompressed).equals(leaf.leafHash())) {
            throw new IOException("EVOGBIN2_CROSS_LEAF_HASH_MISMATCH");
        }
        var in = new ByteArrayInputStream(uncompressed);
        int type = in.read();
        if (type != LEAF_TYPE_CROSS_CONTEXT) {
            throw new IOException("EVOGBIN2_INVALID_CROSS_LEAF_TYPE:" + type);
        }
        String[] strings = readStringTable(in);
        ContentDigest[] digests = readDigestTable(in);
        int evCount = readVarInt(in);
        var evidence = new ArrayList<GraphEvidence>(evCount);
        for (int i = 0; i < evCount; i++) {
            ContentDigest evId = digests[readCheckedIndex(in, digests.length)];
            String family = strings[readCheckedIndex(in, strings.length)];
            String json = strings[readCheckedIndex(in, strings.length)];
            evidence.add(GraphEvidence.fromAuthenticatedCache(evId, family, json));
        }
        int edgeCount = readVarInt(in);
        var edges = new ArrayList<KnowledgeGraph.Edge>(edgeCount);
        for (int i = 0; i < edgeCount; i++) {
            edges.add(readEncodedEdge(in, null, strings, digests));
        }
        if (in.read() != -1) {
            throw new IOException("EVOGBIN2_TRAILING_CROSS_LEAF_BYTES");
        }
        return new CrossContextPartitionData(edges, evidence);
    }

    /** Encodes a KnowledgeGraph into a self-contained deterministic EVOGBIN2 binary container. */
    public static byte[] encodeEvogbin2(KnowledgeGraph graph) {
        return encodeEvogbin2(decomposeMerkle(graph));
    }

    /** Serializes a canonical MerkleDecomposition into the exact EVOGBIN2 binary container bytes. */
    public static byte[] encodeEvogbin2(MerkleDecomposition decomp) {
        Objects.requireNonNull(decomp, "decomp");
        try {
            var baos = new ByteArrayOutputStream(4096);
            baos.write(MAGIC_EVOGBIN2);
            writeVarInt(baos, EVOGBIN2_VERSION);
            writeVarUtf(baos, decomp.snapshot().value());
            HexFormat hex = HexFormat.of();
            baos.write(hex.parseHex(decomp.merkleGraphIdentity().value(), 7, 71));
            writeCapabilitySummary(baos, decomp.capabilitySummary(), hex);

            writeVarInt(baos, decomp.contextLeaves().size());
            for (var entry : decomp.contextLeaves().entrySet()) {
                MerkleLeaf leaf = entry.getValue();
                writeVarUtf(baos, entry.getKey());
                baos.write(hex.parseHex(leaf.leafHash().value(), 7, 71));
                writeVarInt(baos, leaf.uncompressedBytes());
                writeVarInt(baos, leaf.compressedBytes());
                baos.write(leaf.compressedPayload());
            }

            MerkleLeaf cross = decomp.crossContextLeaf();
            baos.write(hex.parseHex(cross.leafHash().value(), 7, 71));
            writeVarInt(baos, cross.uncompressedBytes());
            writeVarInt(baos, cross.compressedBytes());
            baos.write(cross.compressedPayload());

            byte[] payload = baos.toByteArray();
            byte[] trailer = MessageDigest.getInstance("SHA-256").digest(payload);
            byte[] full = Arrays.copyOf(payload, payload.length + 32);
            System.arraycopy(trailer, 0, full, payload.length, 32);
            return full;
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Decodes, checksum-verifies, and Merkle-authenticates an EVOGBIN2 binary container back to KnowledgeGraph. */
    public static DecodedEvogbin2 decodeEvogbin2(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < MAGIC_EVOGBIN2.length + 32) {
            throw new IOException("EVOGBIN2_TRUNCATED");
        }
        try {
            byte[] expectedTrailer = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
            byte[] actualTrailer = MessageDigest.getInstance("SHA-256").digest(
                    Arrays.copyOf(bytes, bytes.length - 32));
            if (!MessageDigest.isEqual(expectedTrailer, actualTrailer)) {
                throw new IOException("EVOGBIN2_CHECKSUM_MISMATCH");
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        var in = new ByteArrayInputStream(bytes, 0, bytes.length - 32);
        byte[] magic = in.readNBytes(MAGIC_EVOGBIN2.length);
        if (!Arrays.equals(MAGIC_EVOGBIN2, magic)) {
            throw new IOException("EVOGBIN2_INVALID_MAGIC");
        }
        int version = readVarInt(in);
        if (version != EVOGBIN2_VERSION) {
            throw new IOException("EVOGBIN2_UNSUPPORTED_VERSION:" + version);
        }
        var snapshot = new SnapshotIdentity(readVarUtf(in));
        HexFormat hex = HexFormat.of();
        ContentDigest declaredMerkleRoot = readRawDigest(in, hex);
        CapabilitySummary declaredSummary = readCapabilitySummary(in, hex);

        int ctxCount = readVarInt(in);
        if (ctxCount < 0 || ctxCount > 100_000) throw new IOException("EVOGBIN2_INVALID_CONTEXT_COUNT");
        var contexts = new ArrayList<KnowledgeGraph.Context>(ctxCount);
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        var evidenceById = new TreeMap<ContentDigest, GraphEvidence>();
        var contextHashes = new TreeMap<String, ContentDigest>();
        var allGaps = new ArrayList<GapOccurrence>();

        String prevCtx = null;
        for (int i = 0; i < ctxCount; i++) {
            String ctxId = readVarUtf(in);
            if (prevCtx != null && prevCtx.compareTo(ctxId) >= 0) {
                throw new IOException("EVOGBIN2_NONCANONICAL_CONTEXT_ORDER");
            }
            prevCtx = ctxId;
            ContentDigest leafHash = readRawDigest(in, hex);
            int uncompressedLen = readVarInt(in);
            int compressedLen = readVarInt(in);
            byte[] comp = readExactBytes(in, compressedLen);
            var leaf = new MerkleLeaf(ctxId, leafHash, uncompressedLen, compressedLen, comp, 0, 0, 0, List.of());
            ContextPartitionData part = decodeContextLeaf(leaf, snapshot);
            if (!part.context().identity().equals(ctxId)) {
                throw new IOException("EVOGBIN2_CONTEXT_ID_MISMATCH");
            }
            contexts.add(part.context());
            nodes.addAll(part.nodes());
            edges.addAll(part.intraContextEdges());
            var localEvById = new HashMap<ContentDigest, GraphEvidence>(hashCapacity(part.evidence().size()));
            for (var ev : part.evidence()) {
                localEvById.put(ev.identity(), ev);
                evidenceById.putIfAbsent(ev.identity(), ev);
            }
            allGaps.addAll(extractGapOccurrences(part.nodes(), localEvById));
            contextHashes.put(ctxId, leafHash);
        }

        ContentDigest crossHash = readRawDigest(in, hex);
        int crossUncomp = readVarInt(in);
        int crossCompLen = readVarInt(in);
        byte[] crossComp = readExactBytes(in, crossCompLen);
        var crossLeaf = new MerkleLeaf(CROSS_CONTEXT_PARTITION_KEY, crossHash, crossUncomp, crossCompLen, crossComp,
                0, 0, 0, List.of());
        CrossContextPartitionData cross = decodeCrossContextLeaf(crossLeaf);
        edges.addAll(cross.crossContextEdges());
        for (var ev : cross.evidence()) {
            evidenceById.putIfAbsent(ev.identity(), ev);
        }
        if (in.read() != -1) {
            throw new IOException("EVOGBIN2_TRAILING_CONTAINER_BYTES");
        }

        CapabilitySummary recomputedSummary = computeCapabilitySummary(allGaps);
        if (!recomputedSummary.equals(declaredSummary)) {
            throw new IOException("EVOGBIN2_CAPABILITY_SUMMARY_MISMATCH");
        }
        ContentDigest recomputedRoot = computeMerkleRoot(contextHashes, crossHash, recomputedSummary.digest());
        if (!recomputedRoot.equals(declaredMerkleRoot)) {
            throw new IOException("EVOGBIN2_MERKLE_ROOT_MISMATCH");
        }

        var restored = new KnowledgeGraph(snapshot, contexts, nodes, edges, List.copyOf(evidenceById.values()));
        return new DecodedEvogbin2(restored, recomputedRoot, contextHashes, crossHash, recomputedSummary);
    }

    /** Lazily materializes the exact canonical {@code .graph.json} document from EVOGBIN2 bytes for audit. */
    public static KnowledgeGraph materializeCanonicalJson(byte[] evogbin2Bytes, Writer out) throws IOException {
        Objects.requireNonNull(out, "out");
        DecodedEvogbin2 decoded = decodeEvogbin2(evogbin2Bytes);
        new GraphSnapshotCodec().encodeTo(decoded.graph(), out);
        out.flush();
        return decoded.graph();
    }

    /** Atomically writes a self-contained EVOGBIN2 binary file to {@code targetPath}. */
    public static ContentDigest writeEvogbin2(KnowledgeGraph graph, Path targetPath) throws IOException {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(targetPath, "targetPath");
        MerkleDecomposition decomp = decomposeMerkle(graph);
        byte[] encoded = encodeEvogbin2(decomp);
        Path normalized = targetPath.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        Path pending = Files.createTempFile(normalized.getParent(), "evogbin2-", ".tmp");
        try {
            Files.write(pending, encoded, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            StreamingContentStore.moveAtomicWithRetry(pending, normalized);
        } finally {
            deleteQuietly(pending);
        }
        return decomp.merkleGraphIdentity();
    }

    /** Reads and authenticates a self-contained EVOGBIN2 file from {@code sourcePath}. */
    public static DecodedEvogbin2 readEvogbin2(Path sourcePath) throws IOException {
        Objects.requireNonNull(sourcePath, "sourcePath");
        return decodeEvogbin2(Files.readAllBytes(sourcePath));
    }

    private static void writeSortedTables(OutputStream out, List<String> strings, List<ContentDigest> digests)
            throws IOException {
        writeVarInt(out, strings.size());
        for (String s : strings) {
            writeVarUtf(out, s);
        }
        writeVarInt(out, digests.size());
        HexFormat hex = HexFormat.of();
        for (ContentDigest d : digests) {
            out.write(hex.parseHex(d.value(), 7, 71));
        }
    }

    private static String[] readStringTable(InputStream in) throws IOException {
        int count = readVarInt(in);
        if (count < 0 || count > 50_000_000) throw new IOException("EVOGBIN2_INVALID_STRING_COUNT:" + count);
        String[] table = new String[count];
        String prev = null;
        for (int i = 0; i < count; i++) {
            String value = readVarUtf(in);
            if (prev != null && prev.compareTo(value) >= 0) {
                throw new IOException("EVOGBIN2_UNSORTED_STRING_TABLE");
            }
            prev = value;
            table[i] = value;
        }
        return table;
    }

    private static ContentDigest[] readDigestTable(InputStream in) throws IOException {
        int count = readVarInt(in);
        if (count < 0 || count > 50_000_000) throw new IOException("EVOGBIN2_INVALID_DIGEST_COUNT:" + count);
        ContentDigest[] table = new ContentDigest[count];
        HexFormat hex = HexFormat.of();
        String prev = null;
        for (int i = 0; i < count; i++) {
            ContentDigest d = readRawDigest(in, hex);
            if (prev != null && prev.compareTo(d.value()) >= 0) {
                throw new IOException("EVOGBIN2_UNSORTED_DIGEST_TABLE");
            }
            prev = d.value();
            table[i] = d;
        }
        return table;
    }

    private static void writeEncodedEdge(OutputStream out, KnowledgeGraph.Edge e, boolean includeContext,
            Map<String, Integer> stringIdx, Map<ContentDigest, Integer> digestIdx) throws IOException {
        if (includeContext) {
            writeVarInt(out, stringIdx.get(e.context()));
        }
        writeVarInt(out, stringIdx.get(e.identity()));
        writeVarInt(out, stringIdx.get(e.kind()));
        writeVarInt(out, stringIdx.get(e.source()));
        out.write(e.target().state().ordinal());
        var tids = e.target().identities();
        writeVarInt(out, tids.size());
        for (String tid : tids) writeVarInt(out, stringIdx.get(tid));
        writeVarInt(out, e.target().unresolvedReference().isPresent()
                ? stringIdx.get(e.target().unresolvedReference().orElseThrow()) + 1 : 0);
        writeVarInt(out, e.evidence().size());
        for (var ref : e.evidence()) writeVarInt(out, digestIdx.get(ref));
        writeVarInt(out, e.region().isPresent() ? stringIdx.get(e.region().orElseThrow()) + 1 : 0);
    }

    private static KnowledgeGraph.Edge readEncodedEdge(InputStream in, String fixedContext,
            String[] strings, ContentDigest[] digests) throws IOException {
        String context = fixedContext != null ? fixedContext : strings[readCheckedIndex(in, strings.length)];
        String edgeId = strings[readCheckedIndex(in, strings.length)];
        String kind = strings[readCheckedIndex(in, strings.length)];
        String source = strings[readCheckedIndex(in, strings.length)];
        int stateOrd = in.read();
        KnowledgeGraph.TargetState[] states = KnowledgeGraph.TargetState.values();
        if (stateOrd < 0 || stateOrd >= states.length) throw new IOException("EVOGBIN2_INVALID_TARGET_STATE");
        int tCount = readVarInt(in);
        if (tCount < 0 || tCount > 1_000_000) throw new IOException("EVOGBIN2_INVALID_TARGET_COUNT");
        List<String> targets = tCount == 0 ? List.of() : new ArrayList<>(tCount);
        for (int t = 0; t < tCount; t++) targets.add(strings[readCheckedIndex(in, strings.length)]);
        int unresolvedPlusOne = readVarInt(in);
        Optional<String> unresolvedRef = unresolvedPlusOne == 0
                ? Optional.empty() : Optional.of(strings[checkIndex(unresolvedPlusOne - 1, strings.length)]);
        int evCount = readVarInt(in);
        if (evCount <= 0 || evCount > 1_000_000) throw new IOException("EVOGBIN2_INVALID_EDGE_EV_COUNT");
        var evRefs = new ArrayList<ContentDigest>(evCount);
        for (int r = 0; r < evCount; r++) evRefs.add(digests[readCheckedIndex(in, digests.length)]);
        int regionPlusOne = readVarInt(in);
        Optional<String> region = regionPlusOne == 0
                ? Optional.empty() : Optional.of(strings[checkIndex(regionPlusOne - 1, strings.length)]);
        return new KnowledgeGraph.Edge(edgeId, context, kind, source,
                new KnowledgeGraph.Target(states[stateOrd], targets, unresolvedRef), evRefs, region);
    }

    static void writeCapabilitySummary(OutputStream out, CapabilitySummary summary, HexFormat hex) throws IOException {
        writeVarLong(out, summary.uniqueGapNodes());
        writeVarLong(out, summary.totalOccurrenceCount());
        writeVarInt(out, summary.occurrenceCountByReason().size());
        for (var entry : summary.occurrenceCountByReason().entrySet()) {
            writeVarUtf(out, entry.getKey());
            writeVarLong(out, entry.getValue());
            writeVarLong(out, summary.uniqueGapCountByReason().getOrDefault(entry.getKey(), 0L));
        }
        out.write(hex.parseHex(summary.digest().value(), 7, 71));
    }

    static CapabilitySummary readCapabilitySummary(InputStream in, HexFormat hex) throws IOException {
        long uniqueNodes = readVarLong(in);
        long totalOcc = readVarLong(in);
        int reasonCount = readVarInt(in);
        if (reasonCount < 0 || reasonCount > 100_000) throw new IOException("EVOGBIN2_INVALID_REASON_COUNT");
        var occByReason = new TreeMap<String, Long>();
        var uniqueByReason = new TreeMap<String, Long>();
        for (int i = 0; i < reasonCount; i++) {
            String reason = readVarUtf(in);
            long occ = readVarLong(in);
            long unique = readVarLong(in);
            occByReason.put(reason, occ);
            uniqueByReason.put(reason, unique);
        }
        ContentDigest digest = readRawDigest(in, hex);
        return new CapabilitySummary(uniqueNodes, totalOcc, occByReason, uniqueByReason, digest);
    }

    static byte[] deflateDeterministic(byte[] raw) {
        var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(raw);
            deflater.finish();
            byte[] buf = new byte[Math.max(64, raw.length / 2 + 64)];
            int total = 0;
            while (!deflater.finished()) {
                if (total == buf.length) {
                    buf = Arrays.copyOf(buf, buf.length + Math.max(4096, buf.length / 2));
                }
                int n = deflater.deflate(buf, total, buf.length - total);
                if (n == 0 && deflater.needsInput()) break;
                total += n;
            }
            return Arrays.copyOf(buf, total);
        } finally {
            deflater.end();
        }
    }

    static byte[] inflateDeterministic(byte[] compressed, int expectedUncompressedLen) throws IOException {
        if (expectedUncompressedLen < 0 || expectedUncompressedLen > 256 * 1024 * 1024) {
            throw new IOException("EVOGBIN2_INVALID_UNCOMPRESSED_LEN:" + expectedUncompressedLen);
        }
        var inflater = new Inflater(true);
        try {
            byte[] out = new byte[expectedUncompressedLen];
            inflater.setInput(compressed);
            int total = 0;
            while (total < expectedUncompressedLen) {
                int n = inflater.inflate(out, total, expectedUncompressedLen - total);
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
                total += n;
            }
            if (total != expectedUncompressedLen || !inflater.finished()) {
                throw new IOException("EVOGBIN2_CORRUPT_DEFLATE_STREAM");
            }
            return out;
        } catch (DataFormatException corrupt) {
            throw new IOException("EVOGBIN2_CORRUPT_DEFLATE_STREAM", corrupt);
        } finally {
            inflater.end();
        }
    }

    /** Unsigned LEB128 varint writer for non-negative 32-bit integers. */
    public static void writeVarInt(OutputStream out, int value) throws IOException {
        if (value < 0) throw new IllegalArgumentException("Negative varint: " + value);
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    /** Unsigned LEB128 varint reader for non-negative 32-bit integers. */
    public static int readVarInt(InputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (shift < 35) {
            int b = in.read();
            if (b == -1) throw new EOFException("EVOGBIN2_EOF_VARINT");
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (result < 0) throw new IOException("EVOGBIN2_VARINT_OVERFLOW");
                return result;
            }
            shift += 7;
        }
        throw new IOException("EVOGBIN2_VARINT_OVERFLOW");
    }

    /** Unsigned LEB128 varlong writer for non-negative 64-bit integers. */
    public static void writeVarLong(OutputStream out, long value) throws IOException {
        if (value < 0L) throw new IllegalArgumentException("Negative varlong: " + value);
        long v = value;
        while ((v & ~0x7FL) != 0L) {
            out.write((int) ((v & 0x7FL) | 0x80L));
            v >>>= 7;
        }
        out.write((int) v);
    }

    /** Unsigned LEB128 varlong reader for non-negative 64-bit integers. */
    public static long readVarLong(InputStream in) throws IOException {
        long result = 0L;
        int shift = 0;
        while (shift < 70) {
            int b = in.read();
            if (b == -1) throw new EOFException("EVOGBIN2_EOF_VARLONG");
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (result < 0L) throw new IOException("EVOGBIN2_VARLONG_OVERFLOW");
                return result;
            }
            shift += 7;
        }
        throw new IOException("EVOGBIN2_VARLONG_OVERFLOW");
    }

    static void writeVarUtf(OutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, utf8.length);
        out.write(utf8);
    }

    static String readVarUtf(InputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > 128 * 1024 * 1024) throw new IOException("EVOGBIN2_INVALID_UTF_LEN:" + len);
        if (len == 0) return "";
        return new String(readExactBytes(in, len), StandardCharsets.UTF_8);
    }

    static ContentDigest readRawDigest(InputStream in, HexFormat hex) throws IOException {
        byte[] raw = readExactBytes(in, 32);
        return new ContentDigest("sha256:" + hex.formatHex(raw));
    }

    static byte[] readExactBytes(InputStream in, int length) throws IOException {
        if (length < 0 || length > 256 * 1024 * 1024) throw new IOException("EVOGBIN2_INVALID_BYTE_LEN:" + length);
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("EVOGBIN2_UNEXPECTED_EOF");
        return bytes;
    }

    private static int readCheckedIndex(InputStream in, int limit) throws IOException {
        return checkIndex(readVarInt(in), limit);
    }

    private static int checkIndex(int idx, int limit) throws IOException {
        if (idx < 0 || idx >= limit) throw new IOException("EVOGBIN2_INDEX_OUT_OF_BOUNDS:" + idx + "/" + limit);
        return idx;
    }
}
