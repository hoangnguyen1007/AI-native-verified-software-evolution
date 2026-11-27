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
    private static final int FORMAT_VERSION_V1 = 1;
    private static final int FORMAT_VERSION_V2 = 2;
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
}
