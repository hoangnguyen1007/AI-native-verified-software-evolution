package com.evolution.analysis.filesystem;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Disposable content-addressed payload segments. The SQLite B-tree contains only
 * keys and byte locators; large JSON never participates in key rebalancing. */
final class GraphProjectionPayloads implements AutoCloseable {
    private static final int MIN_COMPRESS_BYTES = 512;
    private static final int STREAM_COMPRESS_THRESHOLD = 4 * 1024 * 1024;
    private static final int STREAM_COMPRESS_CHUNK = 64 * 1024;
    private static final int WRITE_BUFFER_BYTES = 256 * 1024;
    private static final int CACHE_MAX_ENTRIES = 2048;
    private static final int CACHE_MAX_CHARS = 16 * 1024 * 1024;
    private static final int LOCATOR_CACHE_CAP = 262_144;
    private static final int MAX_OPEN_READERS = 16;
    private static final int INSERT_BATCH_SIZE = 1024;

    private static int defaultLocatorCacheCap() {
        long maxMem = Runtime.getRuntime().maxMemory();
        if (maxMem >= 2L * 1024 * 1024 * 1024) return 1_048_576;
        if (maxMem >= 1024L * 1024 * 1024) return 524_288;
        return LOCATOR_CACHE_CAP;
    }

    private record Locator(int segment, long start, int length, int rawLength, boolean compressed) {}

    private final PreparedStatement find, insert;
    private final Path directory;
    private final int rowBytes;
    private final long segmentBytes;
    private final int cacheMaxEntries;
    private final int cacheMaxChars;
    private final int locatorCacheCap;
    private final boolean batchWrites;
    private final BooleanSupplier cancelled;
    private final List<Path> segments = new ArrayList<>();
    private final List<Long> segmentLengths = new ArrayList<>();
    private final Map<Integer, FileChannel> readers = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, String> cache = new LinkedHashMap<>(256, .75f, true);
    private final Map<String, Locator> locators = new HashMap<>(4096);
    private final Map<String, Locator> pendingLocators = new HashMap<>(INSERT_BATCH_SIZE);
    private final Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
    private final Inflater inflater = new Inflater(true);
    private byte[] compressBuf = new byte[8192];
    private final byte[] writeBuf = new byte[WRITE_BUFFER_BYTES];
    private int writeBufPos;
    private int insertBatchCount;
    private int cachedCharacters;
    private FileChannel writer;
    private long position, allowance;
    private final FileStore fileStore;
    private boolean closed;

    GraphProjectionPayloads(Connection database, int rowBytes, BooleanSupplier cancelled) throws SQLException, IOException {
        this(database, rowBytes, cancelled, 64L * 1024 * 1024, defaultLocatorCacheCap());
    }

    GraphProjectionPayloads(Connection database, int rowBytes, BooleanSupplier cancelled, long segmentBytes) throws SQLException, IOException {
        this(database, rowBytes, cancelled, segmentBytes, defaultLocatorCacheCap());
    }

    GraphProjectionPayloads(Connection database, int rowBytes, BooleanSupplier cancelled, long segmentBytes, int locatorCacheCap) throws SQLException, IOException {
        this.rowBytes = rowBytes; this.cancelled = cancelled; this.segmentBytes = segmentBytes;
        this.locatorCacheCap = Math.max(1, locatorCacheCap);
        boolean smallTestSegment = segmentBytes < 1_048_576L;
        this.cacheMaxEntries = smallTestSegment ? 64 : CACHE_MAX_ENTRIES;
        this.cacheMaxChars = smallTestSegment ? 524_288 : CACHE_MAX_CHARS;
        this.batchWrites = !database.getAutoCommit() && !smallTestSegment;
        try (var sql = database.createStatement(); var rows = sql.executeQuery("PRAGMA database_list")) {
            Path found = null; while (rows.next()) if (rows.getString(2).equals("main")) found = Path.of(rows.getString(3));
            if (found == null || found.toString().isEmpty()) throw new IOException("GRAPH_PROJECTION_DISK_INDEX_REQUIRED");
            directory = found.toAbsolutePath().getParent();
        }
        fileStore = Files.getFileStore(directory);
        try (var sql = database.createStatement()) {
            sql.execute("CREATE TABLE payloads(digest TEXT PRIMARY KEY,segment INTEGER NOT NULL,start INTEGER NOT NULL,length INTEGER NOT NULL,raw_length INTEGER NOT NULL,compressed INTEGER NOT NULL) WITHOUT ROWID");
        }
        find = database.prepareStatement("SELECT segment,start,length,raw_length,compressed FROM payloads WHERE digest=?");
        insert = database.prepareStatement("INSERT INTO payloads VALUES(?,?,?,?,?,?)");
    }

    static String digestHex(byte[] raw) {
        MessageDigest md = StreamingContentStore.borrowSha256();
        try {
            return HexFormat.of().formatHex(md.digest(raw));
        } finally {
            StreamingContentStore.releaseSha256(md);
        }
    }

    void flush() throws SQLException, IOException {
        flushWriteBuffer();
        flushInsertBatch();
    }

    private void flushInsertBatch() throws SQLException {
        if (insertBatchCount > 0) {
            insert.executeBatch();
            insertBatchCount = 0;
            pendingLocators.clear();
        }
    }

    private void flushWriteBuffer() throws IOException {
        if (writeBufPos == 0 || writer == null) return;
        var buffer = ByteBuffer.wrap(writeBuf, 0, writeBufPos);
        long writeStart = position - writeBufPos;
        while (buffer.hasRemaining()) {
            check();
            writeStart += writer.write(buffer, writeStart);
        }
        writeBufPos = 0;
    }

    String put(String value) throws SQLException, IOException {
        check();
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        return putUtf8(value, raw, digestHex(raw));
    }

    String putUtf8(String value, byte[] raw, String digest) throws SQLException, IOException {
        check();
        if (raw.length > rowBytes) throw new IOException("GRAPH_PARTITION_ROW_WORKER_REQUIRED");
        if (cache.containsKey(digest) || locators.containsKey(digest) || pendingLocators.containsKey(digest)) return digest;
        if (locators.size() >= locatorCacheCap) {
            find.setString(1, digest);
            try (var rows = find.executeQuery()) {
                if (rows.next()) { remember(digest, value); return digest; }
            }
        }
        boolean compressed = false;
        byte[] bytes = raw;
        int bytesLen = raw.length;
        long start;
        int segIndex;
        if (raw.length > STREAM_COMPRESS_THRESHOLD) {
            // Stream-deflate very large payloads directly to the segment with a bounded 64 KiB buffer
            // so peak heap never doubles to hold a second multi-megabyte compressed copy.
            if (writer == null || position > 0 && position + raw.length > segmentBytes) {
                openFreshSegment();
            }
            ensureDiskAllowance(raw.length);
            flushWriteBuffer();
            start = position;
            segIndex = segments.size() - 1;
            int streamed = streamDeflateToSegment(raw, start);
            if (streamed >= 0 && streamed < raw.length) {
                compressed = true;
                bytesLen = streamed;
                position = start + streamed;
            } else {
                position = start;
                writer.truncate(start);
                var buffer = ByteBuffer.wrap(raw);
                while (buffer.hasRemaining()) {
                    check();
                    position += writer.write(buffer, position);
                }
                compressed = false;
                bytesLen = raw.length;
            }
            segmentLengths.set(segIndex, position);
            allowance -= bytesLen;
        } else {
            if (raw.length >= MIN_COMPRESS_BYTES) {
                deflater.reset();
                deflater.setInput(raw);
                deflater.finish();
                int needed = Math.min(raw.length, Math.max(compressBuf.length, raw.length));
                if (compressBuf.length < needed && needed <= 1_048_576) {
                    compressBuf = new byte[needed];
                }
                int total = 0;
                boolean fits = true;
                while (!deflater.finished()) {
                    if (total >= raw.length) {
                        fits = false;
                        break;
                    }
                    int space = Math.min(compressBuf.length, raw.length) - total;
                    if (space <= 0) {
                        if (compressBuf.length < raw.length) {
                            compressBuf = Arrays.copyOf(compressBuf, Math.min(raw.length, compressBuf.length * 2));
                            space = Math.min(compressBuf.length, raw.length) - total;
                        } else {
                            fits = false;
                            break;
                        }
                    }
                    int n = deflater.deflate(compressBuf, total, space);
                    if (n == 0 && deflater.needsInput()) break;
                    total += n;
                }
                if (fits && total < raw.length) {
                    compressed = true;
                    bytes = compressBuf;
                    bytesLen = total;
                }
                if (compressBuf.length > 1_048_576) {
                    compressBuf = new byte[8192];
                }
            }
            if (writer == null || position > 0 && position + bytesLen > segmentBytes) {
                openFreshSegment();
            }
            ensureDiskAllowance(bytesLen);
            start = position;
            appendToSegment(bytes, bytesLen);
            segIndex = segments.size() - 1;
            segmentLengths.set(segIndex, position);
            allowance -= bytesLen;
        }
        insert.setString(1, digest); insert.setInt(2, segIndex); insert.setLong(3, start);
        insert.setInt(4, bytesLen); insert.setInt(5, raw.length); insert.setBoolean(6, compressed);
        Locator loc = new Locator(segIndex, start, bytesLen, raw.length, compressed);
        if (batchWrites) {
            insert.addBatch();
            pendingLocators.put(digest, loc);
            if (++insertBatchCount >= INSERT_BATCH_SIZE) {
                flushInsertBatch();
            }
        } else {
            flushWriteBuffer();
            insert.executeUpdate();
        }
        if (locators.size() < locatorCacheCap) {
            locators.put(digest, loc);
        }
        remember(digest, value);
        return digest;
    }

    private void openFreshSegment() throws IOException {
        if (writer != null) {
            flushWriteBuffer();
            writer.close();
            writer = null;
        }
        Path file = Files.createTempFile(directory, "graph-payload-", ".pack");
        segments.add(file);
        segmentLengths.add(0L);
        writer = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        position = 0;
    }

    private void ensureDiskAllowance(long requiredBytes) throws IOException {
        if (allowance < requiredBytes) {
            long free = fileStore.getUsableSpace();
            long reserve = 64L * 1024 * 1024;
            if (free < requiredBytes + reserve) throw new IOException("GRAPH_PROJECTION_DISK_PRESSURE");
            allowance = Math.max(requiredBytes, Math.min(8L * 1024 * 1024, free - reserve));
        }
    }

    private int streamDeflateToSegment(byte[] raw, long start) throws IOException {
        deflater.reset();
        deflater.setInput(raw);
        deflater.finish();
        byte[] chunk = new byte[STREAM_COMPRESS_CHUNK];
        int total = 0;
        long writePos = start;
        while (!deflater.finished()) {
            check();
            int n = deflater.deflate(chunk, 0, chunk.length);
            if (n == 0 && deflater.needsInput()) break;
            if (total + n >= raw.length) {
                return -1;
            }
            var buf = ByteBuffer.wrap(chunk, 0, n);
            while (buf.hasRemaining()) {
                check();
                writePos += writer.write(buf, writePos);
            }
            total += n;
        }
        return total;
    }

    private void appendToSegment(byte[] bytes, int len) throws IOException {
        if (len >= writeBuf.length) {
            flushWriteBuffer();
            var buffer = ByteBuffer.wrap(bytes, 0, len);
            while (buffer.hasRemaining()) {
                check();
                position += writer.write(buffer, position);
            }
            return;
        }
        if (writeBufPos + len > writeBuf.length) {
            flushWriteBuffer();
        }
        System.arraycopy(bytes, 0, writeBuf, writeBufPos, len);
        writeBufPos += len;
        position += len;
    }

    String get(String digest) throws SQLException, IOException {
        check();
        String existing = cache.get(digest); if (existing != null) return existing;
        Locator loc = locators.get(digest);
        if (loc == null) loc = pendingLocators.get(digest);
        if (loc == null) {
            find.setString(1, digest);
            try (var rows = find.executeQuery()) {
                if (!rows.next()) throw new IOException("GRAPH_PROJECTION_PAYLOAD_MISSING");
                loc = new Locator(rows.getInt(1), rows.getLong(2), rows.getInt(3), rows.getInt(4), rows.getBoolean(5));
            }
        }
        int segment = loc.segment(), length = loc.length(), rawLength = loc.rawLength();
        long start = loc.start();
        boolean compressed = loc.compressed();
        if (segment < 0 || segment >= segments.size() || start < 0 || length < 0 || length > rowBytes || rawLength < 0 || rawLength > rowBytes)
            throw new IOException("GRAPH_PROJECTION_PAYLOAD_LOCATOR_INVALID");
        FileChannel channel;
        if (segment == segments.size() - 1) {
            flushWriteBuffer();
            channel = writer;
        } else {
            channel = readers.get(segment);
            if (channel == null) {
                if (readers.size() >= MAX_OPEN_READERS) {
                    var first = readers.entrySet().iterator();
                    var old = first.next();
                    old.getValue().close();
                    first.remove();
                }
                channel = FileChannel.open(segments.get(segment), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                readers.put(segment, channel);
            }
        }
        long segSize = segmentLengths.get(segment);
        if (start > segSize - length) throw new IOException("GRAPH_PROJECTION_PAYLOAD_TRUNCATED");
        byte[] bytes = new byte[length];
        var buffer = ByteBuffer.wrap(bytes);
        long offset = start;
        while (buffer.hasRemaining()) {
            check();
            int count = channel.read(buffer, offset);
            if (count < 0) throw new EOFException("GRAPH_PROJECTION_PAYLOAD_TRUNCATED");
            offset += count;
        }
        byte[] raw;
        if (compressed) {
            raw = new byte[rawLength];
            inflater.reset();
            inflater.setInput(bytes);
            int total = 0;
            try {
                while (total < rawLength) {
                    int n = inflater.inflate(raw, total, rawLength - total);
                    if (n == 0) {
                        if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break;
                    }
                    total += n;
                }
            } catch (DataFormatException corrupt) {
                throw new IOException("GRAPH_PROJECTION_PAYLOAD_CORRUPT", corrupt);
            }
            if (total != rawLength || !inflater.finished()) {
                throw new IOException("GRAPH_PROJECTION_PAYLOAD_TRUNCATED");
            }
        } else {
            raw = bytes;
        }
        MessageDigest sha = StreamingContentStore.borrowSha256();
        byte[] actualHash;
        try {
            actualHash = sha.digest(raw);
        } finally {
            StreamingContentStore.releaseSha256(sha);
        }
        if (raw.length != rawLength || !MessageDigest.isEqual(actualHash, HexFormat.of().parseHex(digest)))
            throw new IOException("GRAPH_PROJECTION_PAYLOAD_HASH_MISMATCH");
        String value = new String(raw, StandardCharsets.UTF_8);
        remember(digest, value);
        return value;
    }

    private void remember(String digest, String value) {
        if (value.length() > 524_288) return;
        String old = cache.put(digest, value);
        if (old != null) cachedCharacters -= old.length();
        cachedCharacters += value.length();
        var rows = cache.entrySet().iterator();
        while ((cache.size() > cacheMaxEntries || cachedCharacters > cacheMaxChars) && rows.hasNext()) {
            cachedCharacters -= rows.next().getValue().length();
            rows.remove();
        }
    }

    private void check() {
        if (closed) throw new IllegalStateException("GRAPH_PROJECTION_CLOSED");
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
    }

    public void close() throws IOException {
        if (closed) return;
        closed = true;
        writeBufPos = 0; // Segments are disposable scratch deleted below; no need to flush tail bytes.
        deflater.end();
        inflater.end();
        IOException failure = null;
        var resources = new ArrayList<AutoCloseable>();
        resources.add(find); resources.add(insert);
        if (writer != null) resources.add(writer);
        resources.addAll(readers.values());
        for (var resource : resources) {
            try { resource.close(); }
            catch (Exception problem) {
                if (failure == null) failure = new IOException("GRAPH_PROJECTION_PAYLOAD_CLOSE", problem);
                else failure.addSuppressed(problem);
            }
        }
        for (var file : segments) {
            try { Files.deleteIfExists(file); }
            catch (IOException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        cache.clear();
        locators.clear();
        pendingLocators.clear();
        if (failure != null) throw failure;
    }
}
