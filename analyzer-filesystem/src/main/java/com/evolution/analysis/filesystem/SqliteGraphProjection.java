package com.evolution.analysis.filesystem;

import com.evolution.analysis.graph.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.function.*;

/** External-memory canonical projection, private to one build. SQLite is scratch, never authority. */
final class SqliteGraphProjection implements GraphProjectionStore, AutoCloseable {
    private static final int INLINE_MAX_UTF8_BYTES = 512;
    private static final String INLINE_PREFIX = "raw:";
    private static final String REF_PREFIX = "ref:";

    private final Connection database;
    private final int rowBytes;
    private final BooleanSupplier cancelled;
    private final GraphProjectionPayloads payloads;
    // This request-private connection is single-owner; it closes these hot
    // statements along with the scratch database. Page cursors close ResultSets per batch.
    private final PreparedStatement lookup, exists, upsert, count, gapUpsert, gapDelete, pageFirst, pageAfter, kindPageFirst, kindPageAfter;

    SqliteGraphProjection(Connection database, int rowBytes, BooleanSupplier cancelled) throws SQLException, IOException {
        this.database = database; this.rowBytes = rowBytes; this.cancelled = cancelled;
        payloads = new GraphProjectionPayloads(database, rowBytes, cancelled);
        try (var sql = database.createStatement()) {
            boolean hasKind = false;
            try (var info = sql.executeQuery("PRAGMA table_info(rows)")) { while (info.next()) if ("kind".equals(info.getString(2))) hasKind = true; }
            if (!hasKind) sql.execute("ALTER TABLE rows ADD COLUMN kind TEXT");
            sql.execute("CREATE INDEX IF NOT EXISTS rows_space_kind_k ON rows(space,kind,k,payload) WHERE kind IS NOT NULL");
            sql.execute("CREATE TABLE gap_nodes(identity TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(identity,k)) WITHOUT ROWID");
        }
        lookup = database.prepareStatement("SELECT payload FROM rows WHERE space=? AND k=?");
        exists = database.prepareStatement("SELECT 1 FROM rows WHERE space=? AND k=?");
        upsert = database.prepareStatement("INSERT INTO rows(space,k,payload,kind) VALUES(?,?,?,?) ON CONFLICT(space,k) DO UPDATE SET payload=excluded.payload,kind=excluded.kind");
        count = database.prepareStatement("SELECT COUNT(*) FROM rows WHERE space=?");
        gapUpsert = database.prepareStatement("INSERT INTO gap_nodes VALUES(?,?,?) ON CONFLICT(identity,k) DO UPDATE SET payload=excluded.payload");
        gapDelete = database.prepareStatement("DELETE FROM gap_nodes WHERE identity=? AND k=?");
        pageFirst = database.prepareStatement("SELECT k,payload FROM rows WHERE space=? ORDER BY k LIMIT 128");
        pageAfter = database.prepareStatement("SELECT k,payload FROM rows WHERE space=? AND k>? ORDER BY k LIMIT 128");
        kindPageFirst = database.prepareStatement("SELECT k,payload FROM rows INDEXED BY rows_space_kind_k WHERE space=? AND kind=? AND kind IS NOT NULL ORDER BY k LIMIT 128");
        kindPageAfter = database.prepareStatement("SELECT k,payload FROM rows INDEXED BY rows_space_kind_k WHERE space=? AND kind=? AND kind IS NOT NULL AND k>? ORDER BY k LIMIT 128");
    }

    private void checkState() {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
    }

    private static String classifyKind(Object value) {
        return switch (value) {
            case KnowledgeGraph.Node n -> n.kind();
            case KnowledgeGraph.Edge e -> e.kind();
            case GraphEvidence ev -> ev.family();
            case null, default -> null;
        };
    }

    @Override
    public <T> Iterable<T> valuesByKind(String space, Set<String> kinds, Function<Object, T> decode, Function<T, String> kindExtractor) {
        Objects.requireNonNull(space); Objects.requireNonNull(kinds); Objects.requireNonNull(decode); Objects.requireNonNull(kindExtractor);
        if (kinds.isEmpty()) return List.of();
        var sortedKinds = List.copyOf(new TreeSet<>(kinds));
        return () -> new Iterator<>() {
            final class KindCursor {
                final String kind;
                final ArrayDeque<Map.Entry<String, T>> buffer = new ArrayDeque<>();
                String after;
                boolean exhausted;
                KindCursor(String kind) { this.kind = kind; }
                void refill() {
                    if (exhausted || !buffer.isEmpty()) return;
                    long characters = 0; int fetched = 0; boolean sizeCap = false;
                    try {
                        var query = after == null ? kindPageFirst : kindPageAfter;
                        query.setString(1, space); query.setString(2, kind);
                        if (after != null) query.setString(3, after);
                        try (var rows = query.executeQuery()) {
                            while (rows.next()) {
                                checkState(); fetched++;
                                String k = rows.getString(1), encoded = decoded(rows.getString(2));
                                T val = decode.apply(GraphJson.read(encoded, new GraphJson.Limits(rowBytes, 128, Integer.MAX_VALUE)));
                                buffer.addLast(Map.entry(k, val));
                                after = k; characters += encoded.length();
                                if (characters >= 1_048_576) { sizeCap = true; break; }
                            }
                        }
                    } catch (SQLException failure) { throw storage(failure); }
                    if (fetched == 0 || !sizeCap && fetched < 128) exhausted = true;
                }
            }
            final PriorityQueue<KindCursor> queue = new PriorityQueue<>(Comparator.comparing(c -> c.buffer.peekFirst().getKey()));
            {
                checkState();
                for (String kind : sortedKinds) {
                    var cursor = new KindCursor(kind); cursor.refill();
                    if (!cursor.buffer.isEmpty()) queue.add(cursor);
                }
            }
            public boolean hasNext() { checkState(); return !queue.isEmpty(); }
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                var cursor = queue.poll();
                var entry = cursor.buffer.removeFirst();
                if (cursor.buffer.isEmpty() && !cursor.exhausted) cursor.refill();
                if (!cursor.buffer.isEmpty()) queue.add(cursor);
                return entry.getValue();
            }
        };
    }

    public <T> Map<String, T> records(String space, Function<Object, T> decode) {
        return new AbstractMap<>() {
            private void check() { checkState(); }
            private T read(String reference) {
                return decode.apply(GraphJson.read(decoded(reference), new GraphJson.Limits(rowBytes, 128, Integer.MAX_VALUE)));
            }
            private String lookupReference(String key) {
                check();
                try {
                    lookup.setString(1, space);
                    lookup.setString(2, key);
                    try (var rows = lookup.executeQuery()) {
                        return rows.next() ? rows.getString(1) : null;
                    }
                } catch (SQLException failure) { throw storage(failure); }
            }
            public T get(Object key) {
                String ref = lookupReference((String) key);
                return ref != null ? read(ref) : null;
            }
            public boolean containsKey(Object key) {
                check();
                try {
                    exists.setString(1, space);
                    exists.setString(2, (String) key);
                    try (var rows = exists.executeQuery()) { return rows.next(); }
                } catch (SQLException failure) { throw storage(failure); }
            }
            public T put(String key, T value) {
                String existingRef = lookupReference(key);
                String canonical = CanonicalJson.write(value);
                if (existingRef == null) {
                    writeCanonical(key, value, canonical, null);
                    return null;
                }
                if (samePayload(existingRef, canonical)) {
                    return value;
                }
                T old = read(existingRef);
                if (!Objects.equals(old, value)) writeCanonical(key, value, canonical, old);
                return old;
            }
            public T putIfAbsent(String key, T value) {
                String existingRef = lookupReference(key);
                if (existingRef == null) {
                    writeCanonical(key, value, CanonicalJson.write(value), null);
                    return null;
                }
                String canonical = CanonicalJson.write(value);
                if (samePayload(existingRef, canonical)) {
                    return value;
                }
                return read(existingRef);
            }
            private void writeCanonical(String key, T value, String canonical, T previous) {
                check();
                try {
                    String reference = encoded(canonical);
                    upsert.setString(1, space); upsert.setString(2, key); upsert.setString(3, reference);
                    String rowKind = classifyKind(value);
                    if (rowKind == null) upsert.setNull(4, Types.VARCHAR); else upsert.setString(4, rowKind);
                    upsert.executeUpdate();
                    if (space.equals("nodes")) {
                        if (previous instanceof KnowledgeGraph.Node old && old.kind().equals("CAPABILITY_GAP")) {
                            gapDelete.setString(1, old.identity()); gapDelete.setString(2, key); gapDelete.executeUpdate();
                        }
                        if (value instanceof KnowledgeGraph.Node node && node.kind().equals("CAPABILITY_GAP")) {
                            gapUpsert.setString(1, node.identity()); gapUpsert.setString(2, key); gapUpsert.setString(3, reference); gapUpsert.executeUpdate();
                        }
                    }
                } catch (SQLException failure) { throw storage(failure); }
            }
            public Set<Map.Entry<String, T>> entrySet() {
                return new AbstractSet<>() {
                    public int size() {
                        try {
                            count.setString(1, space);
                            try (var rows = count.executeQuery()) { return rows.next() ? Math.toIntExact(rows.getLong(1)) : 0; }
                        } catch (SQLException failure) { throw storage(failure); }
                    }
                    public Iterator<Map.Entry<String, T>> iterator() {
                        return new Iterator<>() {
                            String after; Iterator<Map.Entry<String, T>> page = Collections.emptyIterator(); boolean end;
                            public boolean hasNext() {
                                check(); if (page.hasNext()) return true; if (end) return false;
                                var batch = new ArrayList<Map.Entry<String, T>>(); long characters = 0; boolean sizeCap = false;
                                try {
                                    var query = after == null ? pageFirst : pageAfter;
                                    query.setString(1, space); if (after != null) query.setString(2, after);
                                    try (var rows = query.executeQuery()) {
                                        while (rows.next()) {
                                            String encoded = decoded(rows.getString(2));
                                            batch.add(Map.entry(rows.getString(1), decode.apply(GraphJson.read(encoded, new GraphJson.Limits(rowBytes, 128, Integer.MAX_VALUE)))));
                                            characters += encoded.length();
                                            if (characters >= 1_048_576) { sizeCap = true; break; }
                                        }
                                    }
                                } catch (SQLException failure) { throw storage(failure); }
                                if (batch.isEmpty()) { end = true; return false; }
                                if (!sizeCap && batch.size() < 128) end = true;
                                after = batch.getLast().getKey(); page = batch.iterator(); return true;
                            }
                            public Map.Entry<String, T> next() { if (!hasNext()) throw new NoSuchElementException(); return page.next(); }
                        };
                    }
                };
            }
        };
    }

    private boolean samePayload(String existingReference, String canonical) {
        if (existingReference.startsWith(INLINE_PREFIX)) {
            return existingReference.regionMatches(INLINE_PREFIX.length(), canonical, 0, canonical.length())
                    && existingReference.length() == INLINE_PREFIX.length() + canonical.length();
        }
        String digestRef = existingReference.startsWith(REF_PREFIX) ? existingReference.substring(REF_PREFIX.length()) : existingReference;
        byte[] raw = canonical.getBytes(StandardCharsets.UTF_8);
        return raw.length <= rowBytes && digestRef.equals(GraphProjectionPayloads.digestHex(raw));
    }

    record GapCounts(Map<String, Long> reasons, long nodes) {}

    /** Count unique gap identities after every contribution. Only the narrow gap
     * index is replayed; repeated context references are not new obligations. */
    GapCounts gapCounts() throws SQLException {
        var reasons = new TreeMap<String, Long>(); long nodes = 0; String previous = null;
        var evidence = records("evidence", GraphSnapshotCodec::evidence);
        try (var query = database.prepareStatement("SELECT identity,payload FROM gap_nodes ORDER BY identity,k"); var rows = query.executeQuery()) {
            while (rows.next()) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                nodes++;
                String identity = rows.getString(1); if (identity.equals(previous)) continue; previous = identity;
                var node = GraphSnapshotCodec.node(GraphJson.read(decoded(rows.getString(2)), new GraphJson.Limits(rowBytes, 128, Integer.MAX_VALUE)));
                String reason = "UNCLASSIFIED_CAPABILITY_GAP";
                for (var id : node.evidence()) {
                    var proof = evidence.get(id.value());
                    if (proof != null && proof.family().equals("evidence.gap")) {
                        var raw = GraphJson.object(GraphJson.read(proof.canonicalJson(), new GraphJson.Limits(rowBytes, 128, Integer.MAX_VALUE)));
                        if (raw.get("reasonCode") instanceof String code) reason = code;
                        break;
                    }
                }
                reasons.merge(reason, 1L, Long::sum);
            }
        }
        return new GapCounts(Map.copyOf(reasons), nodes);
    }

    String encoded(String value) {
        try {
            // Fast ASCII/short bound: UTF-8 uses <= 3 bytes per UTF-16 code unit (<= 4 across supplementary pairs, <= 3 per char).
            int maxPossibleUtf8 = value.length() * 3;
            if (maxPossibleUtf8 <= INLINE_MAX_UTF8_BYTES && maxPossibleUtf8 <= rowBytes) {
                return INLINE_PREFIX + value;
            }
            byte[] raw = value.getBytes(StandardCharsets.UTF_8);
            if (raw.length > rowBytes) throw new IOException("GRAPH_PARTITION_ROW_WORKER_REQUIRED");
            if (raw.length <= INLINE_MAX_UTF8_BYTES) {
                return INLINE_PREFIX + value;
            }
            return REF_PREFIX + payloads.putUtf8(value, raw, GraphProjectionPayloads.digestHex(raw));
        } catch (SQLException failure) { throw storage(failure); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    String decoded(String reference) {
        try {
            if (reference.startsWith(INLINE_PREFIX)) {
                return reference.substring(INLINE_PREFIX.length());
            }
            if (reference.startsWith(REF_PREFIX)) {
                return payloads.get(reference.substring(REF_PREFIX.length()));
            }
            return payloads.get(reference);
        } catch (SQLException failure) { throw storage(failure); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    public void close() throws IOException {
        IOException failed = null;
        for (var statement : List.of(lookup, exists, upsert, count, gapUpsert, gapDelete, pageFirst, pageAfter, kindPageFirst, kindPageAfter)) {
            try { statement.close(); }
            catch (SQLException failure) {
                if (failed == null) failed = new IOException("GRAPH_PROJECTION_STATEMENT_CLOSE", failure);
                else failed.addSuppressed(failure);
            }
        }
        try { payloads.close(); }
        catch (IOException failure) { if (failed == null) failed = failure; else failed.addSuppressed(failure); }
        if (failed != null) throw failed;
    }

    private static UncheckedIOException storage(SQLException failure) {
        return new UncheckedIOException(new IOException("GRAPH_PROJECTION_STORAGE_UNAVAILABLE", failure));
    }
}
