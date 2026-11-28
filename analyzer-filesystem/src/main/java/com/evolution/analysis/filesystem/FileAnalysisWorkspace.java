package com.evolution.analysis.filesystem;

import com.evolution.analysis.ingestion.AnalysisWorkspace;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.math.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Private, disposable analysis spool. Compressed rows are streamed to disk, with disk indexes and
 * SHA-256 row checks. Only classes encountered in this process may be reconstructed;
 * input bytes cannot name or load a class. This is scratch state, never an evidence format. */
public final class FileAnalysisWorkspace implements AnalysisWorkspace, AutoCloseable {
    private static final int ORDERED_PAGE_SIZE = 256;
    private static final int MIN_COMPRESS_BYTES = 256;
    private static final int WRITE_BUFFER_BYTES = 256 * 1024;
    private static final int SQLITE_BATCH_SIZE = 2_048;
    private static final int SQLITE_COMMIT_BATCH = 8_192;
    private static final int DISTINCT_PAGE_SIZE = 1_024;
    private static final int MAX_CACHED_KEYS_PER_SPACE = 262_144;
    private static final byte FLAG_RAW = 0;
    private static final byte FLAG_DEFLATE = 1;
    private static final ThreadLocal<Deflater> ROW_DEFLATER = ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED, true));
    private static final ThreadLocal<Inflater> ROW_INFLATER = ThreadLocal.withInitial(() -> new Inflater(true));
    private int pendingUncommittedRows;
    private PreparedStatement activeBatchStmt;
    private int activeBatchCount;
    private final Map<Integer, SpaceKeys> spaceKeys = new HashMap<>();

    private record ByteKey(byte[] bytes, int hash) {
        ByteKey(byte[] bytes) { this(bytes, Arrays.hashCode(bytes)); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object obj) {
            return obj instanceof ByteKey other && this.hash == other.hash && Arrays.equals(this.bytes, other.bytes);
        }
    }

    private static final class SpaceKeys {
        final HashSet<ByteKey> keys = new HashSet<>();
        boolean overflowed;
    }

    @Override public boolean concurrentTables() { return true; }
    private final Path directory;
    private final Connection db;
    private final RandomAccessFile data;
    private final byte[] fileWriteBuf = new byte[WRITE_BUFFER_BYTES];
    private int fileWriteBufPos;
    private final Map<Integer, Integer> sizes = new HashMap<>();
    private final Map<String, PreparedStatement> queries = new HashMap<>();
    private final List<Class<?>> classes = new ArrayList<>();
    private final ConcurrentHashMap<Class<?>, Integer> classIds = new ConcurrentHashMap<>();
    private record RecordCodec(Method[] accessors, MethodHandle[] handles, Constructor<?> constructor) {}
    private static final ClassValue<RecordCodec> RECORD_CODECS = new ClassValue<>() {
        @Override
        protected RecordCodec computeValue(Class<?> type) {
            if (!type.isRecord()) throw new IllegalArgumentException("ANALYSIS_SPOOL_RECORD_TYPE");
            var lookup = MethodHandles.lookup();
            var fields = type.getRecordComponents();
            var accessors = new Method[fields.length];
            var handles = new MethodHandle[fields.length];
            var parameters = new Class<?>[fields.length];
            for (int i = 0; i < fields.length; i++) {
                accessors[i] = fields[i].getAccessor();
                accessors[i].trySetAccessible();
                parameters[i] = fields[i].getType();
                try {
                    handles[i] = lookup.unreflect(accessors[i])
                            .asType(MethodType.methodType(Object.class, Object.class));
                } catch (IllegalAccessException ignored) {
                    handles[i] = null;
                }
            }
            try {
                var ctor = type.getDeclaredConstructor(parameters);
                ctor.trySetAccessible();
                return new RecordCodec(accessors, handles, ctor);
            } catch (NoSuchMethodException failed) {
                throw new IllegalArgumentException("ANALYSIS_SPOOL_RECORD_CONSTRUCTOR", failed);
            }
        }
    };
    private long writePosition;
    private int sequence;
    private boolean closed;

    public FileAnalysisWorkspace(Path parent) throws IOException {
        Files.createDirectories(parent);
        directory = Files.createTempDirectory(parent, "analysis-");
        data = new RandomAccessFile(directory.resolve("values.bin").toFile(), "rw");
        try {
            db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("index.sqlite"));
            try (var s = db.createStatement()) {
                s.execute("PRAGMA journal_mode=OFF"); s.execute("PRAGMA synchronous=OFF"); s.execute("PRAGMA cache_size=-32768");
                s.execute("PRAGMA temp_store=MEMORY");
                s.execute("CREATE TABLE rows(space INTEGER, ordinal INTEGER, key BLOB, start INTEGER, length INTEGER, digest BLOB, PRIMARY KEY(space,ordinal))");
                s.execute("CREATE UNIQUE INDEX row_keys ON rows(space,key)");
            }
            db.setAutoCommit(false);
            org.sqlite.ProgressHandler.setHandler(db, 1000, new org.sqlite.ProgressHandler() { protected int progress() { return Thread.currentThread().isInterrupted() ? 1 : 0; } });
        } catch (SQLException failed) { throw new IOException("ANALYSIS_SPOOL_OPEN_FAILED", failed); }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("ANALYSIS_SPOOL_CLOSED");
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    }

    private synchronized int space() { requireOpen(); int next = sequence++; sizes.put(next, 0); return next; }

    private PreparedStatement query(String sql) throws SQLException {
        var cached = queries.get(sql);
        if (cached == null) { cached = db.prepareStatement(sql); queries.put(sql, cached); }
        cached.clearParameters();
        return cached;
    }

    @Override public <T> List<T> list() { return new Rows<>(space()); }

    @Override public <T> List<T> freeze(List<T> rows) {
        if (rows instanceof FileAnalysisWorkspace.Rows<?> raw) {
            @SuppressWarnings("unchecked") var owned = (Rows<T>) raw;
            if (owned.owner() != this) throw new IllegalArgumentException("FOREIGN_ANALYSIS_SPOOL");
            synchronized (this) {
                requireOpen();
                try {
                    flushFileWriteBufferLocked();
                    flushPendingBatchLocked();
                } catch (IOException | SQLException failed) { throw failure(failed); }
            }
            owned.sealed = true; int size = owned.size;
            return new ReplayRows<>(owned.space, size);
        }
        return ReplayList.copy(rows);
    }

    @Override public Set<CapabilityGapRecord> gaps(Collection<CapabilityGapRecord> initial) {
        int space = space();
        var set = new AbstractSet<CapabilityGapRecord>() {
            public int size() { return count(space); }
            public boolean add(CapabilityGapRecord row) { return insertKey(space, row.gapIdentity().value(), row); }
            public Iterator<CapabilityGapRecord> iterator() { return FileAnalysisWorkspace.this.<CapabilityGapRecord>ordered(space).iterator(); }
        };
        set.addAll(initial);
        return set;
    }

    @Override public <T> List<T> distinct(Collection<T> rows, Function<T, String> key) {
        int space = space(), source = -1;
        if (rows instanceof FileAnalysisWorkspace.Rows<?> owned && owned.owner() == this) source = owned.space;
        if (rows instanceof FileAnalysisWorkspace.ReplayRows<?> owned && owned.owner() == this) source = owned.space;
        if (source >= 0) {
            record RawRow(byte[] framed, Location location) {}
            record KeyedAlias(byte[] encodedKey, Location location) {}
            int afterOrdinal = -1;
            while (true) {
                var page = new ArrayList<RawRow>(DISTINCT_PAGE_SIZE);
                int lastOrdinal = afterOrdinal;
                synchronized (this) {
                    requireOpen();
                    try {
                        flushFileWriteBufferLocked();
                        flushPendingBatchLocked();
                        var q = query("SELECT ordinal,start,length,digest FROM rows WHERE space=? AND ordinal>? ORDER BY ordinal LIMIT " + DISTINCT_PAGE_SIZE);
                        q.setInt(1, source);
                        q.setInt(2, afterOrdinal);
                        var channel = data.getChannel();
                        try (var r = q.executeQuery()) {
                            while (r.next()) {
                                int ord = r.getInt(1);
                                long start = r.getLong(2), length = r.getLong(3);
                                byte[] expected = r.getBytes(4);
                                if (start < 0 || length < 1 || start > writePosition - length || length > Integer.MAX_VALUE - 8)
                                    throw new IOException("ANALYSIS_SPOOL_RANGE");
                                byte[] framed = new byte[(int) length];
                                var buf = ByteBuffer.wrap(framed);
                                long pos = start;
                                while (buf.hasRemaining()) {
                                    int n = channel.read(buf, pos);
                                    if (n < 0) throw new EOFException();
                                    pos += n;
                                }
                                page.add(new RawRow(framed, new Location(start, length, expected)));
                                lastOrdinal = ord;
                            }
                        }
                    } catch (IOException | SQLException failed) { throw failure(failed); }
                }
                if (page.isEmpty()) break;
                afterOrdinal = lastOrdinal;
                var keyed = new ArrayList<KeyedAlias>(page.size());
                for (var raw : page) {
                    T val = this.<T>decodeFramed(raw.framed(), raw.location());
                    keyed.add(new KeyedAlias(orderedKey(key.apply(val)), raw.location()));
                }
                synchronized (this) {
                    requireOpen();
                    for (var entry : keyed) {
                        insertAliasLocked(space, entry.encodedKey(), entry.location());
                    }
                }
                if (page.size() < DISTINCT_PAGE_SIZE) break;
            }
        } else {
            for (var row : rows) insertKey(space, key.apply(row), row);
        }
        int output = space();
        synchronized (this) {
            requireOpen();
            try {
                flushPendingBatchLocked();
                var q = query("INSERT INTO rows SELECT ?,ROW_NUMBER() OVER (ORDER BY key)-1,NULL,start,length,digest FROM rows WHERE space=?");
                q.setInt(1, output); q.setInt(2, space); q.executeUpdate(); sizes.put(output, count(space));
                spaceKeys.remove(space);
            } catch (SQLException failed) { throw failure(failed); }
        }
        return new ReplayRows<>(output, count(output));
    }

    private record Location(long start, long length, byte[] digest) {}
    private record Stored<T>(T value, Location location) {}
    private record SerializedRow(byte[] bytes, int length, byte[] digest) {}

    private void flushPendingBatchLocked() throws SQLException {
        if (activeBatchCount > 0 && activeBatchStmt != null) {
            activeBatchStmt.executeBatch();
            activeBatchCount = 0;
            activeBatchStmt = null;
        }
    }

    private void enqueueBatchRowLocked(PreparedStatement q) throws SQLException {
        if (activeBatchStmt != null && activeBatchStmt != q && activeBatchCount > 0) {
            flushPendingBatchLocked();
        }
        q.addBatch();
        activeBatchStmt = q;
        activeBatchCount++;
        pendingUncommittedRows++;
        if (activeBatchCount >= SQLITE_BATCH_SIZE) {
            flushPendingBatchLocked();
        }
        if (pendingUncommittedRows >= SQLITE_COMMIT_BATCH) {
            flushPendingBatchLocked();
            db.commit();
            pendingUncommittedRows = 0;
        }
    }

    private boolean keyExistsLocked(int space, byte[] encodedKey, ByteKey bk) throws SQLException {
        var sk = spaceKeys.get(space);
        if (sk != null && sk.keys.contains(bk)) return true;
        if (sk == null || !sk.overflowed) return false;
        flushPendingBatchLocked();
        var q = query("SELECT 1 FROM rows WHERE space=? AND key=?");
        q.setInt(1, space); q.setBytes(2, encodedKey);
        try (var r = q.executeQuery()) { return r.next(); }
    }

    private void recordKeyLocked(int space, ByteKey bk) {
        var sk = spaceKeys.computeIfAbsent(space, k -> new SpaceKeys());
        if (sk.keys.size() < MAX_CACHED_KEYS_PER_SPACE) {
            sk.keys.add(bk);
        } else {
            sk.overflowed = true;
        }
    }

    private void insertAliasLocked(int space, byte[] encodedKey, Location location) {
        try {
            var bk = new ByteKey(encodedKey);
            if (keyExistsLocked(space, encodedKey, bk)) return;
            var sk = spaceKeys.computeIfAbsent(space, k -> new SpaceKeys());
            if (!sk.overflowed) {
                recordKeyLocked(space, bk);
                int idx = count(space);
                var q = query("INSERT OR IGNORE INTO rows VALUES(?,?,?,?,?,?)");
                q.setInt(1, space); q.setInt(2, idx); q.setBytes(3, encodedKey);
                q.setLong(4, location.start()); q.setLong(5, location.length()); q.setBytes(6, location.digest());
                enqueueBatchRowLocked(q);
                sizes.put(space, idx + 1);
            } else {
                flushPendingBatchLocked();
                var q = query("INSERT OR IGNORE INTO rows VALUES(?,?,?,?,?,?)");
                q.setInt(1, space); q.setInt(2, count(space)); q.setBytes(3, encodedKey);
                q.setLong(4, location.start()); q.setLong(5, location.length()); q.setBytes(6, location.digest());
                if (q.executeUpdate() > 0) {
                    sizes.put(space, count(space) + 1);
                    if (++pendingUncommittedRows >= SQLITE_COMMIT_BATCH) {
                        db.commit();
                        pendingUncommittedRows = 0;
                    }
                }
            }
        } catch (SQLException failed) { throw failure(failed); }
    }

    private synchronized int count(int space) { requireOpen(); return sizes.get(space); }

    private boolean insertKey(int space, String key, Object value) {
        byte[] encodedKey = orderedKey(key);
        var bk = new ByteKey(encodedKey);
        synchronized (this) {
            requireOpen();
            try {
                if (keyExistsLocked(space, encodedKey, bk)) return false;
            } catch (SQLException failed) { throw failure(failed); }
        }
        // Serialize, compress, and hash outside the global monitor so concurrent threads overlap.
        SerializedRow serialized = serializeRow(value);
        synchronized (this) {
            requireOpen();
            try {
                if (keyExistsLocked(space, encodedKey, bk)) return false;
                recordKeyLocked(space, bk);
                appendAndIndexLocked(space, count(space), encodedKey, serialized);
                return true;
            } catch (SQLException | IOException failed) { throw failure(failed); }
        }
    }

    /**
     * CESU-8 byte encoding preserves exact Java UTF-16 code-unit order ({@link String#compareTo})
     * under SQLite unsigned BLOB {@code memcmp} while staying 1 byte/char for ASCII keys (4x smaller
     * than UTF-16 hex).
     */
    static byte[] orderedKey(String key) {
        int len = key.length();
        boolean allAscii = true;
        for (int i = 0; i < len; i++) {
            if (key.charAt(i) >= 0x80) { allAscii = false; break; }
        }
        if (allAscii) {
            byte[] out = new byte[len];
            for (int i = 0; i < len; i++) out[i] = (byte) key.charAt(i);
            return out;
        }
        var out = new ByteArrayOutputStream(len * 2);
        for (int i = 0; i < len; i++) {
            int c = key.charAt(i);
            if (c < 0x80) {
                out.write(c);
            } else if (c < 0x800) {
                out.write(0xC0 | (c >>> 6));
                out.write(0x80 | (c & 0x3F));
            } else {
                out.write(0xE0 | (c >>> 12));
                out.write(0x80 | ((c >>> 6) & 0x3F));
                out.write(0x80 | (c & 0x3F));
            }
        }
        return out.toByteArray();
    }

    private <T> Iterable<T> ordered(int space) {
        return () -> new Iterator<>() {
            final int[] ordinals = new int[ORDERED_PAGE_SIZE];
            int buffered, cursor;
            byte[] after;
            boolean done;

            private void refill() {
                if (cursor < buffered || done) return;
                cursor = 0; buffered = 0;
                synchronized (FileAnalysisWorkspace.this) {
                    requireOpen();
                    try {
                        flushPendingBatchLocked();
                        var q = query(after == null
                                ? "SELECT ordinal,key FROM rows WHERE space=? ORDER BY key LIMIT " + ORDERED_PAGE_SIZE
                                : "SELECT ordinal,key FROM rows WHERE space=? AND key>? ORDER BY key LIMIT " + ORDERED_PAGE_SIZE);
                        q.setInt(1, space);
                        if (after != null) q.setBytes(2, after);
                        try (var r = q.executeQuery()) {
                            while (r.next()) {
                                ordinals[buffered++] = r.getInt(1);
                                after = r.getBytes(2);
                            }
                        }
                        if (buffered < ORDERED_PAGE_SIZE) done = true;
                    } catch (SQLException failed) { throw failure(failed); }
                }
            }

            public boolean hasNext() {
                if (cursor < buffered) return true;
                if (done) return false;
                refill();
                return cursor < buffered;
            }

            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                return read(space, ordinals[cursor++]);
            }
        };
    }

    private final class Rows<T> extends AbstractList<T> {
        final int space; int size; boolean sealed;
        Rows(int space) { this.space = space; }
        FileAnalysisWorkspace owner() { return FileAnalysisWorkspace.this; }
        public int size() { requireOpen(); return size; }
        public T get(int index) { Objects.checkIndex(index, size); return read(space, index); }
        public boolean add(T value) { if (sealed) throw new UnsupportedOperationException(); write(space, size, null, value); size++; return true; }
        public T set(int index, T value) { if (sealed) throw new UnsupportedOperationException(); Objects.checkIndex(index, size); T previous = get(index); write(space, index, null, value); return previous; }
    }

    private final class ReplayRows<T> extends ReplayList<T> {
        final int space, size; ReplayRows(int space, int size) { this.space = space; this.size = size; }
        FileAnalysisWorkspace owner() { return FileAnalysisWorkspace.this; }
        public int size() { requireOpen(); return size; }
        public T get(int index) { Objects.checkIndex(index, size); return read(space, index); }
    }

    private SerializedRow serializeRow(Object value) {
        requireOpen();
        MessageDigest digest = StreamingContentStore.borrowSha256();
        try {
            var rawOut = new ByteArrayOutputStream(512);
            try (var dataOut = new DataOutputStream(rawOut)) {
                encode(dataOut, value);
            }
            byte[] raw = rawOut.toByteArray();
            byte[] framed;
            if (raw.length >= MIN_COMPRESS_BYTES) {
                var deflater = ROW_DEFLATER.get();
                deflater.reset();
                deflater.setInput(raw);
                deflater.finish();
                byte[] comp = new byte[raw.length];
                int total = 0;
                boolean smaller = true;
                while (!deflater.finished()) {
                    if (total >= raw.length - 5) { smaller = false; break; }
                    int n = deflater.deflate(comp, total, (raw.length - 5) - total);
                    if (n == 0 && deflater.needsInput()) break;
                    total += n;
                }
                if (smaller && total + 5 < raw.length) {
                    framed = new byte[5 + total];
                    framed[0] = FLAG_DEFLATE;
                    framed[1] = (byte) (raw.length >>> 24);
                    framed[2] = (byte) (raw.length >>> 16);
                    framed[3] = (byte) (raw.length >>> 8);
                    framed[4] = (byte) raw.length;
                    System.arraycopy(comp, 0, framed, 5, total);
                } else {
                    framed = new byte[1 + raw.length];
                    framed[0] = FLAG_RAW;
                    System.arraycopy(raw, 0, framed, 1, raw.length);
                }
            } else {
                framed = new byte[1 + raw.length];
                framed[0] = FLAG_RAW;
                System.arraycopy(raw, 0, framed, 1, raw.length);
            }
            byte[] hash = digest.digest(framed);
            return new SerializedRow(framed, framed.length, hash);
        } catch (IOException failed) {
            throw failure(failed);
        } finally {
            StreamingContentStore.releaseSha256(digest);
        }
    }

    private void flushFileWriteBufferLocked() throws IOException {
        if (fileWriteBufPos == 0) return;
        var channel = data.getChannel();
        var buf = ByteBuffer.wrap(fileWriteBuf, 0, fileWriteBufPos);
        long pos = writePosition - fileWriteBufPos;
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos);
        }
        fileWriteBufPos = 0;
    }

    private void appendAndIndexLocked(int space, int index, byte[] key, SerializedRow row) throws IOException, SQLException {
        long start = writePosition;
        int len = row.length();
        if (len >= fileWriteBuf.length) {
            flushFileWriteBufferLocked();
            var channel = data.getChannel();
            var buf = ByteBuffer.wrap(row.bytes(), 0, len);
            long pos = start;
            while (buf.hasRemaining()) pos += channel.write(buf, pos);
            writePosition = pos;
        } else {
            if (fileWriteBufPos + len > fileWriteBuf.length) {
                flushFileWriteBufferLocked();
            }
            System.arraycopy(row.bytes(), 0, fileWriteBuf, fileWriteBufPos, len);
            fileWriteBufPos += len;
            writePosition += len;
        }
        var q = query("INSERT OR REPLACE INTO rows VALUES(?,?,?,?,?,?)");
        q.setInt(1, space); q.setInt(2, index);
        if (key == null) q.setNull(3, Types.BLOB); else q.setBytes(3, key);
        q.setLong(4, start); q.setLong(5, len); q.setBytes(6, row.digest());
        enqueueBatchRowLocked(q);
        sizes.put(space, Math.max(sizes.get(space), index + 1));
    }

    private void write(int space, int index, byte[] key, Object value) {
        SerializedRow serialized = serializeRow(value);
        synchronized (this) {
            requireOpen();
            try {
                appendAndIndexLocked(space, index, key, serialized);
            } catch (IOException | SQLException failed) { throw failure(failed); }
        }
    }

    private <T> T read(int space, int index) { return this.<T>readStored(space, index).value(); }

    private <T> Stored<T> readStored(int space, int index) {
        byte[] framed;
        Location location;
        synchronized (this) {
            requireOpen();
            try {
                flushFileWriteBufferLocked();
                flushPendingBatchLocked();
                var q = query("SELECT start,length,digest FROM rows WHERE space=? AND ordinal=?");
                q.setInt(1, space); q.setInt(2, index);
                try (var r = q.executeQuery()) {
                    if (!r.next()) throw new IllegalStateException("ANALYSIS_SPOOL_ROW_MISSING");
                    long start = r.getLong(1), length = r.getLong(2); byte[] expected = r.getBytes(3);
                    if (start < 0 || length < 1 || start > writePosition - length || length > Integer.MAX_VALUE - 8)
                        throw new IOException("ANALYSIS_SPOOL_RANGE");
                    framed = new byte[(int) length];
                    var buf = ByteBuffer.wrap(framed);
                    long pos = start;
                    var channel = data.getChannel();
                    while (buf.hasRemaining()) {
                        int n = channel.read(buf, pos);
                        if (n < 0) throw new EOFException();
                        pos += n;
                    }
                    location = new Location(start, length, expected);
                }
            } catch (IOException | SQLException failed) { throw failure(failed); }
        }
        return new Stored<>(this.<T>decodeFramed(framed, location), location);
    }

    @SuppressWarnings("unchecked")
    private <T> T decodeFramed(byte[] framed, Location location) {
        // Verify SHA-256, decompress, and decode outside the global monitor.
        MessageDigest digest = StreamingContentStore.borrowSha256();
        byte[] actual;
        try { actual = digest.digest(framed); }
        finally { StreamingContentStore.releaseSha256(digest); }
        try {
            if (!MessageDigest.isEqual(actual, location.digest())) throw new IOException("ANALYSIS_SPOOL_HASH_MISMATCH");
            byte[] raw;
            byte flag = framed[0];
            if (flag == FLAG_RAW) {
                raw = framed;
                try (var in = new DataInputStream(new ByteArrayInputStream(raw, 1, raw.length - 1))) {
                    Object value = decode(in);
                    if (in.read() != -1) throw new IOException("ANALYSIS_SPOOL_TRAILING_BYTES");
                    return (T) value;
                }
            } else if (flag == FLAG_DEFLATE) {
                if (framed.length < 5) throw new IOException("ANALYSIS_SPOOL_CORRUPT");
                int rawLen = ((framed[1] & 0xFF) << 24) | ((framed[2] & 0xFF) << 16)
                        | ((framed[3] & 0xFF) << 8) | (framed[4] & 0xFF);
                if (rawLen < 0 || rawLen > 256_000_000) throw new IOException("ANALYSIS_SPOOL_ROW_CAPACITY");
                raw = new byte[rawLen];
                var inflater = ROW_INFLATER.get();
                inflater.reset();
                try {
                    inflater.setInput(framed, 5, framed.length - 5);
                    int total = 0;
                    while (total < rawLen) {
                        int n = inflater.inflate(raw, total, rawLen - total);
                        if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
                        total += n;
                    }
                    if (total != rawLen || !inflater.finished()) throw new IOException("ANALYSIS_SPOOL_CORRUPT");
                } catch (DataFormatException corrupt) {
                    throw new IOException("ANALYSIS_SPOOL_CORRUPT", corrupt);
                }
                try (var in = new DataInputStream(new ByteArrayInputStream(raw))) {
                    Object value = decode(in);
                    if (in.read() != -1) throw new IOException("ANALYSIS_SPOOL_TRAILING_BYTES");
                    return (T) value;
                }
            } else {
                throw new IOException("ANALYSIS_SPOOL_FLAG");
            }
        } catch (IOException failed) { throw failure(failed); }
    }

    private int type(Class<?> type) throws IOException {
        if (!type.getName().startsWith("com.evolution.analysis.") || !(type.isRecord() || type.isEnum()))
            throw new IOException("ANALYSIS_SPOOL_NON_NEUTRAL_TYPE:" + type.getName());
        Integer id = classIds.get(type);
        if (id != null) return id;
        synchronized (classes) {
            id = classIds.get(type);
            if (id != null) return id;
            int next = classes.size();
            classes.add(type);
            classIds.put(type, next);
            return next;
        }
    }

    private static RecordCodec codec(Class<?> type) throws IOException {
        try {
            return RECORD_CODECS.get(type);
        } catch (IllegalArgumentException invalid) {
            throw new IOException(invalid.getMessage(), invalid);
        }
    }

    private void encode(DataOutputStream out, Object value) throws IOException {
        if (value == null) { out.writeByte(0); return; }
        if (value instanceof String s) { writeStringTagged(out, s); return; }
        if (value instanceof Boolean b) { out.writeByte(b ? 2 : 3); return; }
        if (value instanceof Integer i) { out.writeByte(4); out.writeInt(i); return; }
        if (value instanceof Long l) { out.writeByte(5); out.writeLong(l); return; }
        if (value instanceof Optional<?> o) { out.writeByte(6); out.writeBoolean(o.isPresent()); if (o.isPresent()) encode(out, o.orElseThrow()); return; }
        if (value instanceof List<?> l) { out.writeByte(7); out.writeInt(l.size()); for (int i = 0, n = l.size(); i < n; i++) encode(out, l.get(i)); return; }
        if (value instanceof Set<?> s) { out.writeByte(8); out.writeInt(s.size()); for (var item : s) encode(out, item); return; }
        if (value instanceof Map<?, ?> m) { out.writeByte(9); out.writeInt(m.size()); for (var e : m.entrySet()) { encode(out, e.getKey()); encode(out, e.getValue()); } return; }
        if (value instanceof Enum<?> e) { out.writeByte(10); out.writeInt(type(e.getDeclaringClass())); out.writeInt(e.ordinal()); return; }
        if (value instanceof BigDecimal n) { out.writeByte(12); writeUtf8(out, n.toString()); return; }
        if (value instanceof BigInteger n) { out.writeByte(13); writeUtf8(out, n.toString()); return; }
        if (value instanceof java.time.Instant i) { out.writeByte(14); out.writeLong(i.getEpochSecond()); out.writeInt(i.getNano()); return; }
        if (value instanceof java.net.URI u) { out.writeByte(15); writeUtf8(out, u.toString()); return; }
        out.writeByte(11); out.writeInt(type(value.getClass()));
        try {
            var c = codec(value.getClass());
            var handles = c.handles();
            var accessors = c.accessors();
            for (int i = 0; i < accessors.length; i++) {
                Object field = handles[i] != null ? handles[i].invokeExact(value) : accessors[i].invoke(value);
                encode(out, field);
            }
        } catch (Throwable failed) {
            throw new IOException("ANALYSIS_SPOOL_RECORD_WRITE", failed);
        }
    }

    private Object decode(DataInputStream in) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> readUtf8(in);
            case 16 -> readUtf16(in);
            case 2 -> true;
            case 3 -> false;
            case 4 -> in.readInt();
            case 5 -> in.readLong();
            case 6 -> in.readBoolean() ? Optional.of(decode(in)) : Optional.empty();
            case 7 -> { int n = length(in); var list = new ArrayList<>(Math.min(n, 4096)); for (int i = 0; i < n; i++) list.add(decode(in)); yield List.copyOf(list); }
            case 8 -> { int n = length(in); var set = new LinkedHashSet<>(); for (int i = 0; i < n; i++) set.add(decode(in)); yield Collections.unmodifiableSet(set); }
            case 9 -> { int n = length(in); var map = new LinkedHashMap<>(); for (int i = 0; i < n; i++) map.put(decode(in), decode(in)); yield Collections.unmodifiableMap(map); }
            case 10 -> { Class<?> cls = registered(in); int ordinal = in.readInt(); Object[] values = cls.getEnumConstants(); if (values == null || ordinal < 0 || ordinal >= values.length) throw new IOException("ANALYSIS_SPOOL_ENUM"); yield values[ordinal]; }
            case 11 -> {
                Class<?> cls = registered(in); var metadata = codec(cls); Object[] args = new Object[metadata.accessors().length];
                for (int i = 0; i < args.length; i++) args[i] = decode(in);
                try { yield metadata.constructor().newInstance(args); }
                catch (ReflectiveOperationException failed) { throw new IOException("ANALYSIS_SPOOL_RECORD_READ:" + cls.getSimpleName(), failed); }
            }
            case 12 -> new BigDecimal(readUtf8(in));
            case 13 -> new BigInteger(readUtf8(in));
            case 14 -> java.time.Instant.ofEpochSecond(in.readLong(), in.readInt());
            case 15 -> java.net.URI.create(readUtf8(in));
            default -> throw new IOException("ANALYSIS_SPOOL_TAG");
        };
    }

    private Class<?> registered(DataInputStream in) throws IOException {
        int id = in.readInt();
        synchronized (classes) {
            if (id < 0 || id >= classes.size()) throw new IOException("ANALYSIS_SPOOL_TYPE_ID");
            return classes.get(id);
        }
    }
    private static int length(DataInputStream in) throws IOException { int size = in.readInt(); if (size < 0 || size > 256_000_000) throw new IOException("ANALYSIS_SPOOL_ROW_CAPACITY"); return size; }

    /** Uses compact UTF-8 for valid Unicode strings and exact UTF-16 only when unpaired surrogates appear. */
    private static void writeStringTagged(DataOutputStream out, String value) throws IOException {
        if (hasUnpairedSurrogate(value)) {
            out.writeByte(16);
            out.writeInt(value.length());
            for (int i = 0, len = value.length(); i < len; i++) out.writeChar(value.charAt(i));
        } else {
            out.writeByte(1);
            writeUtf8(out, value);
        }
    }

    private static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= len || !Character.isLowSurrogate(s.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    private static void writeUtf8(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readUtf8(DataInputStream in) throws IOException {
        int n = length(in);
        if (n == 0) return "";
        byte[] bytes = new byte[n];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readUtf16(DataInputStream in) throws IOException {
        int n = length(in);
        char[] text = new char[n];
        for (int i = 0; i < n; i++) text[i] = in.readChar();
        return new String(text);
    }

    private static IllegalStateException failure(Exception failed) {
        if (Thread.currentThread().isInterrupted()) { var cancelled = new java.util.concurrent.CancellationException("ANALYSIS_SPOOL_CANCELLED"); cancelled.initCause(failed); throw cancelled; }
        return new IllegalStateException("ANALYSIS_SPOOL_FAILED", failed);
    }
    private static IOException retain(IOException first, IOException next) { if (first == null) return next; if (first != next) first.addSuppressed(next); return first; }

    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true; IOException failure = null;
        try { flushFileWriteBufferLocked(); } catch (IOException failed) { failure = retain(failure, failed); }
        try { flushPendingBatchLocked(); } catch (SQLException failed) { failure = retain(failure, new IOException("ANALYSIS_SPOOL_CLOSE_BATCH", failed)); }
        activeBatchStmt = null; activeBatchCount = 0; spaceKeys.clear();
        for (var stmt : queries.values()) { try { stmt.close(); } catch (SQLException ignored) {} }
        queries.clear();
        try { db.close(); } catch (SQLException failed) { failure = new IOException("ANALYSIS_SPOOL_CLOSE", failed); }
        try { data.close(); } catch (IOException failed) { failure = retain(failure, failed); }
        // Only this constructor's private flat directory; never follow links or remove caller files.
        try (var paths = Files.list(directory)) { for (var path : paths.toList()) Files.delete(path); } catch (IOException failed) { failure = retain(failure, failed); }
        try { Files.delete(directory); } catch (IOException failed) { failure = retain(failure, failed); }
        if (failure != null) throw failure;
    }
}
