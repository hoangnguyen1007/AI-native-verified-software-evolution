package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Disposable, disk-backed joins for one root census. Domain rules remain in
 * SemanticRootCensus. A caller-owned new path prevents reusing stale marks. */
public final class SqliteSemanticCensusIndex implements SemanticRootCensus.Index {
    private final Connection connection;
    private final PreparedStatement insertStmt, getStmt, markStmt;
    private final int rowBytes;
    private boolean closed;
    private static final int PAGE_ROWS = 512, PAGE_CHARS = 1048576;
    private final Set<PagedRows> readers = new HashSet<>();
    private record BufferedRow(String key, String encoded) {}

    /** Own only a newly created scratch database; never remove an existing index. */
    public static SemanticRootCensus.Index temporary(Path directory, int rowBytes) throws IOException {
        Path parent = StreamingContentStore.directory(directory);
        Path file = parent.resolve("semantic-" + UUID.randomUUID() + ".sqlite");
        var delegate = new SqliteSemanticCensusIndex(file, rowBytes);
        return new SemanticRootCensus.Index() {
            boolean closed;
            public void put(String space, String key, SemanticTables.Row row) throws IOException { delegate.put(space, key, row); }
            public Optional<SemanticTables.Row> get(String space, String key) throws IOException { return delegate.get(space, key); }
            public void mark(String space, String key) throws IOException { delegate.mark(space, key); }
            public SemanticRootCensus.Rows rows(String space, boolean unmarkedOnly) throws IOException { return delegate.rows(space, unmarkedOnly); }
            public SemanticRootCensus.Rows range(String space, String lower, String upper) throws IOException { return delegate.range(space, lower, upper); }
            public void close() throws IOException { if (closed) return; closed = true; try { delegate.close(); } finally { Files.deleteIfExists(file); } }
        };
    }

    public SqliteSemanticCensusIndex(Path file, int rowBytes) throws IOException {
        if (rowBytes < 1 || rowBytes > 64_000_000) throw new IllegalArgumentException("SEMANTIC_ROOT_INDEX_ROW_BUDGET");
        this.rowBytes = rowBytes;
        Path absolute = file.toAbsolutePath().normalize();
        StreamingContentStore.safe(absolute);
        StreamingContentStore.directory(absolute.getParent());
        Files.createFile(absolute);
        Connection opened = null;
        try {
            opened = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            connection = opened;
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=MEMORY");
                statement.execute("PRAGMA synchronous=OFF");
                statement.execute("PRAGMA cache_size=-8192");
                statement.execute("PRAGMA temp_store=MEMORY");
                statement.execute("CREATE TABLE census_rows(space TEXT NOT NULL,k TEXT NOT NULL,row TEXT NOT NULL,marked INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(space,k)) WITHOUT ROWID");
            }
            connection.setAutoCommit(false);
            insertStmt = connection.prepareStatement("INSERT INTO census_rows(space,k,row) VALUES(?,?,?)");
            getStmt = connection.prepareStatement("SELECT row FROM census_rows WHERE space=? AND k=?");
            markStmt = connection.prepareStatement("UPDATE census_rows SET marked=1 WHERE space=? AND k=?");
        } catch (SQLException failure) {
            if (opened != null) try { opened.close(); } catch (SQLException suppressed) { failure.addSuppressed(suppressed); }
            throw new IOException("SEMANTIC_ROOT_INDEX_OPEN_FAILED", failure);
        }
    }

    @Override public void put(String space, String key, SemanticTables.Row row) throws IOException {
        check();
        String encoded = CanonicalJson.write(row);
        if (encoded.length() > rowBytes / 3 && encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > rowBytes)
            throw new IOException("SEMANTIC_ROOT_INDEX_ROW_WORKER_REQUIRED");
        try {
            insertStmt.setString(1, space); insertStmt.setString(2, key); insertStmt.setString(3, encoded);
            insertStmt.executeUpdate();
            for (var reader : readers) if (Objects.equals(reader.space, space)) reader.buffer.clear();
        } catch (SQLException failure) { throw new IOException("SEMANTIC_ROOT_INDEX_INSERT_FAILED", failure); }
    }

    @Override public Optional<SemanticTables.Row> get(String space, String key) throws IOException {
        check();
        try {
            getStmt.setString(1, space); getStmt.setString(2, key);
            try (var result = getStmt.executeQuery()) {
                return result.next() ? Optional.of(decode(result.getString(1))) : Optional.empty();
            }
        } catch (SQLException failure) { throw new IOException("SEMANTIC_ROOT_INDEX_READ_FAILED", failure); }
    }

    @Override public void mark(String space, String key) throws IOException {
        check();
        try {
            markStmt.setString(1, space); markStmt.setString(2, key);
            if (markStmt.executeUpdate() != 1) throw new IOException("SEMANTIC_ROOT_INDEX_MISSING");
            for (var reader : readers) if (reader.unmarkedOnly && Objects.equals(reader.space, space)) reader.buffer.removeIf(row -> row.key().equals(key));
        } catch (SQLException failure) { throw new IOException("SEMANTIC_ROOT_INDEX_MARK_FAILED", failure); }
    }

    @Override public SemanticRootCensus.Rows rows(String space, boolean unmarkedOnly) throws IOException {
        check();
        var reader = new PagedRows(space, unmarkedOnly); readers.add(reader); return reader;
    }

    @Override public SemanticRootCensus.Rows range(String space, String lower, String upper) throws IOException {
        check();
        if (lower.compareTo(upper) >= 0) throw new IllegalArgumentException("SEMANTIC_INDEX_RANGE");
        var reader = new PagedRows(space, false, lower, upper); readers.add(reader); return reader;
    }

    private final class PagedRows implements SemanticRootCensus.Rows {
        final String space, lower, upper; final boolean unmarkedOnly; final ArrayDeque<BufferedRow> buffer = new ArrayDeque<>(); String previous; boolean end;
        PagedRows(String space, boolean unmarkedOnly) { this(space, unmarkedOnly, null, null); }
        PagedRows(String space, boolean unmarkedOnly, String lower, String upper) { this.space = space; this.unmarkedOnly = unmarkedOnly; this.lower = lower; this.upper = upper; }
        public SemanticTables.Row next() throws IOException {
            check(); if (end) return null; if (buffer.isEmpty()) refill();
            if (buffer.isEmpty()) { close(); return null; }
            var row = buffer.removeFirst(); previous = row.key(); return decode(row.encoded());
        }
        private void refill() throws IOException {
            String sql = "SELECT k,row FROM census_rows WHERE space=?" + (unmarkedOnly ? " AND marked=0" : "") + (lower == null ? "" : " AND k>=? AND k<?") + (previous == null ? "" : " AND k>?") + " ORDER BY k LIMIT " + PAGE_ROWS;
            try (var query = connection.prepareStatement(sql)) {
                int parameter = 1; query.setString(parameter++, space); if (lower != null) { query.setString(parameter++, lower); query.setString(parameter++, upper); } if (previous != null) query.setString(parameter, previous);
                try (var result = query.executeQuery()) {
                    long chars = 0;
                    while (result.next()) {
                        String encoded = result.getString(2);
                        if (!buffer.isEmpty() && chars + encoded.length() > PAGE_CHARS) break;
                        buffer.addLast(new BufferedRow(result.getString(1), encoded)); chars += encoded.length();
                    }
                }
            } catch (SQLException failure) { throw new IOException("SEMANTIC_ROOT_INDEX_SCAN_FAILED", failure); }
        }
        public void close() { end = true; buffer.clear(); readers.remove(this); }
    }

    private SemanticTables.Row decode(String encoded) throws IOException {
        try {
            var value = GraphJson.object(GraphJson.read(encoded, new GraphJson.Limits(rowBytes, 64, rowBytes)));
            if (!value.keySet().equals(Set.of("kind", "key", "scope", "fields"))) throw new IllegalArgumentException();
            var row = new SemanticTables.Row(SemanticTables.Kind.valueOf(GraphJson.string(value.get("kind"))), GraphJson.string(value.get("key")), new ContentDigest(GraphJson.string(value.get("scope"))), GraphJson.object(value.get("fields")));
            if (!CanonicalJson.write(row).equals(encoded)) throw new IllegalArgumentException();
            return row;
        } catch (IllegalArgumentException failure) { throw new IOException("SEMANTIC_ROOT_INDEX_ROW_INVALID", failure); }
    }

    private void check() throws IOException { if (closed) throw new IOException("SEMANTIC_ROOT_INDEX_CLOSED"); }

    @Override public void close() throws IOException {
        if (closed) return; closed = true;
        for (var reader : List.copyOf(readers)) reader.close();
        for (var stmt : List.of(insertStmt, getStmt, markStmt)) { try { stmt.close(); } catch (SQLException ignored) {} }
        try (connection) { connection.rollback(); }
        catch (SQLException failure) { throw new IOException("SEMANTIC_ROOT_INDEX_CLOSE_FAILED", failure); }
    }
}
