package com.evolution.backend.query.search;

import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.RepoMapBuilder;
import com.evolution.backend.query.llm.LlmTransport;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Single-owner SQLite FTS5 BM25 pre-redacted code search index ({@code <store>/search/fts5.db}).
 *
 * <p>Core invariants enforced:
 * <ul>
 *   <li><b>I1 (Zero Host Exec):</b> Pure offline JVM SQLite read/write; never executes target code.</li>
 *   <li><b>I2 (Mandatory Pre-Redaction):</b> Raw CAS bytes are NEVER written to {@code code_chunks}
 *       or {@code code_chunks_fts}. Every chunk is sliced strictly from
 *       {@link LlmTransport#redactDocumentForPath(String, String)} (including Shannon-entropy guard),
 *       preserving exact 1-based {@code start_line..end_line} and original CAS {@code content_sha256}.</li>
 *   <li><b>I3 (Honest Accounting):</b> Binary, oversized ({@code >2 MiB}), or unreadable files are
 *       never silently dropped; they are tracked in {@code omitted_file_count}. Every document carries
 *       an explicit {@link TrustTier} and {@code is_advisory} flag.</li>
 *   <li><b>I4 (Rebuildable &amp; Deterministic):</b> Keyed by {@code (graph_digest, redaction_version,
 *       tokenizer_version)}. Deleting {@code fts5.db} triggers clean lazy rebuild with identical
 *       canonical results and zero mutation to {@link KnowledgeGraph#identity()}.</li>
 *   <li><b>I5 (Zero Repo Ceilings):</b> Streams file-by-file with no 500-file or 12 MiB total ceiling.</li>
 * </ul>
 */
public final class CapturedSearchIndex implements AutoCloseable {
    public static final String SCHEMA_VERSION = "1";
    public static final String TOKENIZER_VERSION = "camel-snake-unicode61:v1";
    public static final String ENGINE_SQLITE_FTS5 = "SQLITE_FTS5_BM25";
    public static final String ENGINE_STREAMING_FALLBACK = "STREAMING_FALLBACK";
    public static final long MAX_INDEXABLE_FILE_BYTES = 2L * 1024 * 1024;
    public static final int MAX_MATCH_ROWS = 1000;
    public static final int MAX_RANK_FILES_CAP = 5_000;
    static final int CHUNK_LINES = 80;
    private static final int MAX_SNIPPET_CHARS = 600;
    private static final int MAX_QUERY_TERMS = 16;
    private static final Set<String> ADVISORY_LANGUAGES = Set.of("markdown", "sql", "terraform");
    private static final GraphJson.Limits SMALL_EVIDENCE = new GraphJson.Limits(131_072, 32, 8_192);

    public record CodeSearchHit(
            String path,
            int startLine,
            int endLine,
            BigDecimal score,
            String snippet,
            String contentSha256,
            String language,
            TrustTier trustTier,
            boolean isAdvisory,
            int redactedCount) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("path", path);
            map.put("startLine", startLine);
            map.put("endLine", endLine);
            map.put("score", score);
            map.put("snippet", snippet);
            map.put("contentSha256", contentSha256);
            map.put("language", language);
            map.put("trustTier", trustTier.name());
            map.put("isAdvisory", isAdvisory);
            map.put("redactedCount", redactedCount);
            return Collections.unmodifiableMap(map);
        }
    }

    public record CodeSearchResult(
            String engine,
            int totalHits,
            boolean truncated,
            int omittedFiles,
            int redactedSecretCount,
            int offset,
            int limit,
            List<CodeSearchHit> items) {
        public CodeSearchResult {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record FileBm25Hit(String path, int bm25Rank, BigDecimal bm25Score) {}

    public record SnapshotStats(
            String graphDigest,
            String snapshotDigest,
            String redactionVersion,
            String tokenizerVersion,
            int fileCount,
            int chunkCount,
            int omittedFileCount,
            int redactedSecretCount,
            String status) {}

    private final Path dbFile;
    private final boolean readOnly;
    private final Object dbLock = new Object();
    private Connection connection;
    private volatile boolean closed;

    private CapturedSearchIndex(Path dbFile, boolean readOnly) {
        this.dbFile = Objects.requireNonNull(dbFile, "dbFile").toAbsolutePath().normalize();
        this.readOnly = readOnly;
        openConnectionInternal();
    }

    /** Opens or creates the read-write single-owner FTS5 acceleration database at {@code dbFile}. */
    public static CapturedSearchIndex openReadWrite(Path dbFile) {
        return new CapturedSearchIndex(dbFile, false);
    }

    /**
     * Opens an existing FTS5 database strictly read-only ({@code ?mode=ro} + {@code PRAGMA query_only=ON})
     * for concurrent offline MCP access alongside a live {@code WorkbenchApiMain}.
     */
    public static CapturedSearchIndex openReadOnly(Path dbFile) {
        Path norm = Objects.requireNonNull(dbFile, "dbFile").toAbsolutePath().normalize();
        if (!Files.isRegularFile(norm, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Search index does not exist: " + norm);
        }
        return new CapturedSearchIndex(norm, true);
    }

    /** Opens read-only if {@code dbFile} already exists; returns empty otherwise (never creates files). */
    public static Optional<CapturedSearchIndex> openReadOnlyIfPresent(Path dbFile) {
        if (dbFile == null) return Optional.empty();
        Path norm = dbFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(norm, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new CapturedSearchIndex(norm, true));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    public Path dbFile() {
        return dbFile;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    private void openConnectionInternal() {
        synchronized (dbLock) {
            try {
                if (readOnly) {
                    this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbFile.toUri() + "?mode=ro");
                    try (Statement st = connection.createStatement()) {
                        st.execute("PRAGMA query_only=ON");
                        st.execute("PRAGMA busy_timeout=5000");
                    }
                } else {
                    if (this.dbFile.getParent() != null) {
                        Files.createDirectories(this.dbFile.getParent());
                    }
                    this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbFile);
                    initializeSchema();
                }
            } catch (IOException | SQLException err) {
                closeQuietly();
                throw new IllegalStateException("Failed to initialize search index at " + this.dbFile, err);
            }
        }
    }

    private void ensureWritableOpen() throws SQLException, IOException {
        if (closed) {
            throw new IllegalStateException("CapturedSearchIndex is closed");
        }
        if (!readOnly && (!Files.isRegularFile(dbFile, LinkOption.NOFOLLOW_LINKS) || connection == null || connection.isClosed())) {
            closeQuietly();
            if (dbFile.getParent() != null) {
                Files.createDirectories(dbFile.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            initializeSchema();
        }
    }

    private void initializeSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA temp_store=MEMORY");
        }
        try (Statement st = connection.createStatement()) {
            st.execute("BEGIN IMMEDIATE");
            try {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS schema_meta (
                            key   TEXT PRIMARY KEY,
                            value TEXT NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS indexed_snapshots (
                            graph_digest          TEXT PRIMARY KEY,
                            snapshot_digest       TEXT NOT NULL,
                            redaction_version     TEXT NOT NULL,
                            tokenizer_version     TEXT NOT NULL,
                            file_count            INTEGER NOT NULL,
                            chunk_count           INTEGER NOT NULL,
                            omitted_file_count    INTEGER NOT NULL DEFAULT 0,
                            redacted_secret_count INTEGER NOT NULL DEFAULT 0,
                            status                TEXT NOT NULL,
                            built_at_ms           INTEGER NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS code_documents (
                            doc_id          INTEGER PRIMARY KEY,
                            graph_digest    TEXT NOT NULL REFERENCES indexed_snapshots(graph_digest) ON DELETE CASCADE,
                            path            TEXT NOT NULL,
                            content_sha256  TEXT NOT NULL,
                            language        TEXT NOT NULL,
                            trust_tier      TEXT NOT NULL,
                            is_advisory     INTEGER NOT NULL DEFAULT 0,
                            line_count      INTEGER NOT NULL,
                            byte_size       INTEGER NOT NULL,
                            redacted_count  INTEGER NOT NULL DEFAULT 0,
                            UNIQUE(graph_digest, path)
                        )
                        """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_codedocs_graph_lang ON code_documents(graph_digest, language)");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS code_chunks (
                            chunk_id      INTEGER PRIMARY KEY,
                            doc_id        INTEGER NOT NULL REFERENCES code_documents(doc_id) ON DELETE CASCADE,
                            graph_digest  TEXT NOT NULL,
                            path          TEXT NOT NULL,
                            start_line    INTEGER NOT NULL,
                            end_line      INTEGER NOT NULL,
                            symbol_names  TEXT NOT NULL DEFAULT '',
                            redacted_text TEXT NOT NULL,
                            split_tokens  TEXT NOT NULL
                        )
                        """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_chunks_graph_doc ON code_chunks(graph_digest, doc_id, start_line)");
                st.execute("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS code_chunks_fts USING fts5(
                            path,
                            symbol_names,
                            split_tokens,
                            content='code_chunks',
                            content_rowid='chunk_id',
                            tokenize='unicode61 remove_diacritics 0'
                        )
                        """);
                st.execute("""
                        CREATE TRIGGER IF NOT EXISTS code_chunks_ai AFTER INSERT ON code_chunks BEGIN
                            INSERT INTO code_chunks_fts(rowid, path, symbol_names, split_tokens)
                            VALUES (new.chunk_id, new.path, new.symbol_names, new.split_tokens);
                        END
                        """);
                st.execute("""
                        CREATE TRIGGER IF NOT EXISTS code_chunks_ad AFTER DELETE ON code_chunks BEGIN
                            INSERT INTO code_chunks_fts(code_chunks_fts, rowid, path, symbol_names, split_tokens)
                            VALUES ('delete', old.chunk_id, old.path, old.symbol_names, old.split_tokens);
                        END
                        """);
                st.execute("""
                        CREATE TRIGGER IF NOT EXISTS code_chunks_au AFTER UPDATE ON code_chunks BEGIN
                            INSERT INTO code_chunks_fts(code_chunks_fts, rowid, path, symbol_names, split_tokens)
                            VALUES ('delete', old.chunk_id, old.path, old.symbol_names, old.split_tokens);
                            INSERT INTO code_chunks_fts(rowid, path, symbol_names, split_tokens)
                            VALUES (new.chunk_id, new.path, new.symbol_names, new.split_tokens);
                        END
                        """);
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO schema_meta(key, value) VALUES('schema_version', ?)")) {
                    ps.setString(1, SCHEMA_VERSION);
                    ps.executeUpdate();
                }
                st.execute("COMMIT");
            } catch (SQLException err) {
                try { st.execute("ROLLBACK"); } catch (SQLException ignored) {}
                throw err;
            }
        }
    }

    /**
     * Checks whether {@code graph} is already indexed for the current {@code (graph_digest, redaction_version,
     * tokenizer_version)}; if not and opened RW, lazily builds the pre-redacted FTS5 index streaming file-by-file.
     * In RO mode, never writes and returns empty if the snapshot is not indexed at the current versions.
     */
    public Optional<SnapshotStats> ensureIndexed(KnowledgeGraph graph, Supplier<StopReason> stop) {
        Objects.requireNonNull(graph, "graph");
        String graphDigest = graph.identity().value();
        synchronized (dbLock) {
            if (closed) return Optional.empty();
            try {
                if (!readOnly) {
                    ensureWritableOpen();
                }
                var existing = readReadySnapshot(graphDigest);
                if (existing.isPresent()) {
                    return existing;
                }
                if (readOnly) {
                    return Optional.empty();
                }
                return buildSnapshotIndex(graph, stop);
            } catch (IOException | SQLException err) {
                return Optional.empty();
            }
        }
    }

    private Optional<SnapshotStats> readReadySnapshot(String graphDigest) throws SQLException {
        String sql = """
                SELECT graph_digest, snapshot_digest, redaction_version, tokenizer_version,
                       file_count, chunk_count, omitted_file_count, redacted_secret_count, status
                FROM indexed_snapshots
                WHERE graph_digest = ?
                  AND redaction_version = ?
                  AND tokenizer_version = ?
                  AND status = 'READY'
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, graphDigest);
            ps.setString(2, LlmTransport.REDACTION_VERSION);
            ps.setString(3, TOKENIZER_VERSION);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new SnapshotStats(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getInt(5),
                        rs.getInt(6),
                        rs.getInt(7),
                        rs.getInt(8),
                        rs.getString(9)));
            }
        }
    }

    /**
     * Indexes captured source files directly into pre-redacted SQLite FTS5, completely decoupled
     * from any semantic analyzer or KnowledgeGraph build stage (eliminating DKB's dropped-chunk
     * defect when semantic analyzers partially fail, I3).
     */
    public Optional<SnapshotStats> indexCapturedSources(
            String indexKey,
            String snapshotDigest,
            Map<String, byte[]> filesByPath,
            Supplier<StopReason> stop) {
        Objects.requireNonNull(indexKey, "indexKey");
        Objects.requireNonNull(filesByPath, "filesByPath");
        String snap = (snapshotDigest == null || snapshotDigest.isBlank()) ? indexKey : snapshotDigest;
        synchronized (dbLock) {
            if (closed || readOnly) return Optional.empty();
            try {
                ensureWritableOpen();
                var existing = readReadySnapshot(indexKey);
                if (existing.isPresent()) {
                    return existing;
                }
                return buildDirectSourceIndex(indexKey, snap, filesByPath, GraphFileMetadata.EMPTY, stop);
            } catch (IOException | SQLException err) {
                return Optional.empty();
            }
        }
    }

    /**
     * Decoupled indexing entry point that merges captured graph files with an optional raw-file fallback map
     * and isolates semantic-metadata extraction exceptions so 100.0% of readable captured source files
     * remain searchable even if a semantic stage partially fails (I3).
     */
    public Optional<SnapshotStats> ensureIndexedDecoupled(
            KnowledgeGraph graph,
            Map<String, byte[]> fallbackFilesByPath,
            Supplier<StopReason> stop) {
        Objects.requireNonNull(graph, "graph");
        String graphDigest = graph.identity().value();
        synchronized (dbLock) {
            if (closed) return Optional.empty();
            try {
                if (!readOnly) {
                    ensureWritableOpen();
                }
                var existing = readReadySnapshot(graphDigest);
                if (existing.isPresent()) {
                    return existing;
                }
                if (readOnly) {
                    return Optional.empty();
                }
                return buildSnapshotIndex(graph, fallbackFilesByPath == null ? Map.of() : fallbackFilesByPath, stop);
            } catch (IOException | SQLException err) {
                return Optional.empty();
            }
        }
    }

    /**
     * Executes a pre-redacted SQLite FTS5 BM25 search against a directly indexed source key
     * (created via {@link #indexCapturedSources}) without requiring a semantic {@link KnowledgeGraph}.
     */
    public Optional<CodeSearchResult> codeSearchByIndexKey(
            String indexKey,
            String rawQuery,
            List<String> languages,
            String pathFilter,
            boolean includeAdvisory,
            int limit,
            int offset,
            Supplier<StopReason> stop) {
        if (indexKey == null || indexKey.isBlank()) return Optional.empty();
        int safeLimit = Math.max(1, Math.min(100, limit));
        int safeOffset = Math.max(0, offset);
        synchronized (dbLock) {
            if (closed) return Optional.empty();
            try {
                var statsOpt = readReadySnapshot(indexKey);
                if (statsOpt.isEmpty()) return Optional.empty();
                var stats = statsOpt.orElseThrow();
                return runCodeSearchWithStats(stats, rawQuery, languages, pathFilter, includeAdvisory, safeLimit, safeOffset);
            } catch (SQLException err) {
                return Optional.empty();
            }
        }
    }

    private Optional<SnapshotStats> buildSnapshotIndex(KnowledgeGraph graph, Supplier<StopReason> stop) throws SQLException {
        return buildSnapshotIndex(graph, Map.of(), stop);
    }

    private Optional<SnapshotStats> buildSnapshotIndex(
            KnowledgeGraph graph,
            Map<String, byte[]> fallbackFilesByPath,
            Supplier<StopReason> stop) throws SQLException {
        String graphDigest = graph.identity().value();
        String snapshotDigest = graph.snapshot().value();
        CapturedFileIndex fileIndex = null;
        try {
            fileIndex = CapturedFileIndex.forGraph(graph);
        } catch (RuntimeException ignored) {
            // Decouple from graph-layer failure; fallbackFilesByPath can still populate 100% FTS5 coverage.
        }
        GraphFileMetadata meta;
        try {
            meta = fileIndex != null ? extractGraphFileMetadata(graph, fileIndex) : GraphFileMetadata.EMPTY;
        } catch (RuntimeException semanticFailure) {
            // Decouple FTS5 from semantic-stage exceptions (I3): never drop captured source files!
            meta = GraphFileMetadata.EMPTY;
        }

        // Collect all captured entries plus any fallback raw source files not present in the partial graph
        var indexedPaths = new HashSet<String>();
        var extraFiles = new TreeMap<String, byte[]>();
        if (fallbackFilesByPath != null) {
            for (var e : fallbackFilesByPath.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    extraFiles.put(CapturedFileIndex.normalizePath(e.getKey()), e.getValue());
                }
            }
        }

        int indexedFiles = 0;
        int indexedChunks = 0;
        int omittedFiles = 0;
        int totalRedactedSecrets = 0;

        try (Statement tx = connection.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                // Explicit child-first delete guarantees FTS5 AFTER DELETE triggers fire cleanly.
                try (PreparedStatement delChunks = connection.prepareStatement("DELETE FROM code_chunks WHERE graph_digest = ?");
                     PreparedStatement delDocs = connection.prepareStatement("DELETE FROM code_documents WHERE graph_digest = ?");
                     PreparedStatement delSnap = connection.prepareStatement("DELETE FROM indexed_snapshots WHERE graph_digest = ?")) {
                    delChunks.setString(1, graphDigest);
                    delChunks.executeUpdate();
                    delDocs.setString(1, graphDigest);
                    delDocs.executeUpdate();
                    delSnap.setString(1, graphDigest);
                    delSnap.executeUpdate();
                }

                long nowMs = System.currentTimeMillis();
                try (PreparedStatement insSnap = connection.prepareStatement("""
                        INSERT INTO indexed_snapshots(
                            graph_digest, snapshot_digest, redaction_version, tokenizer_version,
                            file_count, chunk_count, omitted_file_count, redacted_secret_count, status, built_at_ms
                        ) VALUES (?, ?, ?, ?, 0, 0, 0, 0, 'BUILDING', ?)
                        """)) {
                    insSnap.setString(1, graphDigest);
                    insSnap.setString(2, snapshotDigest);
                    insSnap.setString(3, LlmTransport.REDACTION_VERSION);
                    insSnap.setString(4, TOKENIZER_VERSION);
                    insSnap.setLong(5, nowMs);
                    insSnap.executeUpdate();
                }

                try (PreparedStatement insDoc = connection.prepareStatement("""
                        INSERT INTO code_documents(
                            graph_digest, path, content_sha256, language, trust_tier,
                            is_advisory, line_count, byte_size, redacted_count
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement insChunk = connection.prepareStatement("""
                        INSERT INTO code_chunks(
                            doc_id, graph_digest, path, start_line, end_line,
                            symbol_names, redacted_text, split_tokens
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    int checked = 0;
                    if (fileIndex != null) {
                        for (var entry : fileIndex.entries()) {
                            if ((++checked & 63) == 0 && stop != null && stop.get() != StopReason.NONE) {
                                tx.execute("ROLLBACK");
                                return Optional.empty();
                            }
                            indexedPaths.add(entry.path());
                            if (!entry.readable()
                                    || entry.size() > MAX_INDEXABLE_FILE_BYTES
                                    || CapturedFileIndex.isKnownBinary(entry.path())) {
                                omittedFiles++;
                                continue;
                            }
                            byte[] rawBytes;
                            try {
                                var rawBytesOpt = fileIndex.readBytes(graph, entry);
                                if (rawBytesOpt.isEmpty()) {
                                    byte[] fb = extraFiles.get(entry.path());
                                    if (fb == null) {
                                        omittedFiles++;
                                        continue;
                                    }
                                    rawBytes = fb;
                                } else {
                                    rawBytes = rawBytesOpt.orElseThrow();
                                }
                            } catch (RuntimeException readFailure) {
                                byte[] fb = extraFiles.get(entry.path());
                                if (fb == null) {
                                    omittedFiles++;
                                    continue;
                                }
                                rawBytes = fb;
                            }
                            int[] counts = indexOneCapturedDocument(
                                    graphDigest, entry.path(), entry.language(), entry.digest(), rawBytes,
                                    meta, insDoc, insChunk);
                            if (counts == null) {
                                omittedFiles++;
                            } else {
                                indexedFiles++;
                                indexedChunks += counts[0];
                                totalRedactedSecrets += counts[1];
                            }
                        }
                    }

                    // Index any remaining fallback files omitted from a partially-failed semantic graph
                    for (var fbEntry : extraFiles.entrySet()) {
                        if ((++checked & 63) == 0 && stop != null && stop.get() != StopReason.NONE) {
                            tx.execute("ROLLBACK");
                            return Optional.empty();
                        }
                        String path = fbEntry.getKey();
                        if (indexedPaths.contains(path)) continue;
                        byte[] rawBytes = fbEntry.getValue();
                        if (rawBytes.length > MAX_INDEXABLE_FILE_BYTES || CapturedFileIndex.isKnownBinary(path)) {
                            omittedFiles++;
                            continue;
                        }
                        String lang = CapturedFileIndex.inferLanguage(path, List.of());
                        int[] counts = indexOneCapturedDocument(
                                graphDigest, path, lang, Optional.empty(), rawBytes,
                                meta, insDoc, insChunk);
                        if (counts == null) {
                            omittedFiles++;
                        } else {
                            indexedFiles++;
                            indexedChunks += counts[0];
                            totalRedactedSecrets += counts[1];
                        }
                    }
                }

                try (PreparedStatement updSnap = connection.prepareStatement("""
                        UPDATE indexed_snapshots
                        SET file_count = ?, chunk_count = ?, omitted_file_count = ?,
                            redacted_secret_count = ?, status = 'READY', built_at_ms = ?
                        WHERE graph_digest = ?
                        """)) {
                    updSnap.setInt(1, indexedFiles);
                    updSnap.setInt(2, indexedChunks);
                    updSnap.setInt(3, omittedFiles);
                    updSnap.setInt(4, totalRedactedSecrets);
                    updSnap.setLong(5, System.currentTimeMillis());
                    updSnap.setString(6, graphDigest);
                    updSnap.executeUpdate();
                }

                tx.execute("COMMIT");
                return Optional.of(new SnapshotStats(
                        graphDigest, snapshotDigest, LlmTransport.REDACTION_VERSION, TOKENIZER_VERSION,
                        indexedFiles, indexedChunks, omittedFiles, totalRedactedSecrets, "READY"));
            } catch (SQLException | RuntimeException err) {
                try { tx.execute("ROLLBACK"); } catch (SQLException ignored) {}
                throw err;
            }
        }
    }

    private Optional<SnapshotStats> buildDirectSourceIndex(
            String indexKey,
            String snapshotDigest,
            Map<String, byte[]> filesByPath,
            GraphFileMetadata meta,
            Supplier<StopReason> stop) throws SQLException {
        var sorted = new TreeMap<String, byte[]>();
        for (var e : filesByPath.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                sorted.put(CapturedFileIndex.normalizePath(e.getKey()), e.getValue());
            }
        }
        int indexedFiles = 0;
        int indexedChunks = 0;
        int omittedFiles = 0;
        int totalRedactedSecrets = 0;

        try (Statement tx = connection.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                try (PreparedStatement delChunks = connection.prepareStatement("DELETE FROM code_chunks WHERE graph_digest = ?");
                     PreparedStatement delDocs = connection.prepareStatement("DELETE FROM code_documents WHERE graph_digest = ?");
                     PreparedStatement delSnap = connection.prepareStatement("DELETE FROM indexed_snapshots WHERE graph_digest = ?")) {
                    delChunks.setString(1, indexKey);
                    delChunks.executeUpdate();
                    delDocs.setString(1, indexKey);
                    delDocs.executeUpdate();
                    delSnap.setString(1, indexKey);
                    delSnap.executeUpdate();
                }
                try (PreparedStatement insSnap = connection.prepareStatement("""
                        INSERT INTO indexed_snapshots(
                            graph_digest, snapshot_digest, redaction_version, tokenizer_version,
                            file_count, chunk_count, omitted_file_count, redacted_secret_count, status, built_at_ms
                        ) VALUES (?, ?, ?, ?, 0, 0, 0, 0, 'BUILDING', ?)
                        """)) {
                    insSnap.setString(1, indexKey);
                    insSnap.setString(2, snapshotDigest);
                    insSnap.setString(3, LlmTransport.REDACTION_VERSION);
                    insSnap.setString(4, TOKENIZER_VERSION);
                    insSnap.setLong(5, System.currentTimeMillis());
                    insSnap.executeUpdate();
                }
                try (PreparedStatement insDoc = connection.prepareStatement("""
                        INSERT INTO code_documents(
                            graph_digest, path, content_sha256, language, trust_tier,
                            is_advisory, line_count, byte_size, redacted_count
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement insChunk = connection.prepareStatement("""
                        INSERT INTO code_chunks(
                            doc_id, graph_digest, path, start_line, end_line,
                            symbol_names, redacted_text, split_tokens
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    int checked = 0;
                    for (var entry : sorted.entrySet()) {
                        if ((++checked & 63) == 0 && stop != null && stop.get() != StopReason.NONE) {
                            tx.execute("ROLLBACK");
                            return Optional.empty();
                        }
                        String path = entry.getKey();
                        byte[] rawBytes = entry.getValue();
                        if (rawBytes.length > MAX_INDEXABLE_FILE_BYTES || CapturedFileIndex.isKnownBinary(path)) {
                            omittedFiles++;
                            continue;
                        }
                        String lang = CapturedFileIndex.inferLanguage(path, List.of());
                        int[] counts = indexOneCapturedDocument(
                                indexKey, path, lang, Optional.empty(), rawBytes, meta, insDoc, insChunk);
                        if (counts == null) {
                            omittedFiles++;
                        } else {
                            indexedFiles++;
                            indexedChunks += counts[0];
                            totalRedactedSecrets += counts[1];
                        }
                    }
                }
                try (PreparedStatement updSnap = connection.prepareStatement("""
                        UPDATE indexed_snapshots
                        SET file_count = ?, chunk_count = ?, omitted_file_count = ?,
                            redacted_secret_count = ?, status = 'READY', built_at_ms = ?
                        WHERE graph_digest = ?
                        """)) {
                    updSnap.setInt(1, indexedFiles);
                    updSnap.setInt(2, indexedChunks);
                    updSnap.setInt(3, omittedFiles);
                    updSnap.setInt(4, totalRedactedSecrets);
                    updSnap.setLong(5, System.currentTimeMillis());
                    updSnap.setString(6, indexKey);
                    updSnap.executeUpdate();
                }
                tx.execute("COMMIT");
                return Optional.of(new SnapshotStats(
                        indexKey, snapshotDigest, LlmTransport.REDACTION_VERSION, TOKENIZER_VERSION,
                        indexedFiles, indexedChunks, omittedFiles, totalRedactedSecrets, "READY"));
            } catch (SQLException | RuntimeException err) {
                try { tx.execute("ROLLBACK"); } catch (SQLException ignored) {}
                throw err;
            }
        }
    }

    /**
     * Indexes one pre-redacted document into {@code code_documents} + {@code code_chunks}.
     * Returns {@code [chunksInserted, redactedCount]} or {@code null} if binary/oversized/unreadable.
     */
    private static int[] indexOneCapturedDocument(
            String graphDigest,
            String path,
            String language,
            Optional<ContentDigest> knownDigest,
            byte[] rawBytes,
            GraphFileMetadata meta,
            PreparedStatement insDoc,
            PreparedStatement insChunk) throws SQLException {
        if (rawBytes == null || rawBytes.length > MAX_INDEXABLE_FILE_BYTES || containsNul(rawBytes)) {
            return null;
        }
        String rawText = decodeCapturedText(rawBytes);
        if (rawText == null) {
            return null;
        }
        // Original CAS SHA-256 preserved strictly for I2 provenance
        String contentSha256 = knownDigest
                .map(ContentDigest::value)
                .orElseGet(() -> ContentDigest.sha256(rawBytes).value());

        // HARD SECURITY RULE (I2): redact full document FIRST; rawText is NEVER persisted.
        LlmTransport.RedactedDocument redactedDoc = LlmTransport.redactDocumentForPath(path, rawText);
        int docRedactedCount = redactedDoc.totalRedactedCount();

        TrustTier tier = classifyTrustTier(path, language, meta);
        boolean advisory = tier == TrustTier.ADVISORY_UNVERIFIED;
        List<String> lines = redactedDoc.lines();
        int lineCount = lines.size();

        insDoc.setString(1, graphDigest);
        insDoc.setString(2, path);
        insDoc.setString(3, contentSha256);
        insDoc.setString(4, language);
        insDoc.setString(5, tier.name());
        insDoc.setInt(6, advisory ? 1 : 0);
        insDoc.setInt(7, lineCount);
        insDoc.setInt(8, rawBytes.length);
        insDoc.setInt(9, docRedactedCount);
        insDoc.executeUpdate();

        long docId;
        try (ResultSet keys = insDoc.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("Missing generated doc_id");
            docId = keys.getLong(1);
        }

        String rawSymbols = "";
        try {
            rawSymbols = meta.symbolsByPath.getOrDefault(path, "");
        } catch (RuntimeException ignored) {}
        String safeSymbols = rawSymbols.isEmpty() ? ""
                : expandSplitTokens(LlmTransport.redactForPath(path, rawSymbols, false).text());

        int chunks = 0;
        // Slice strictly from redactedDoc.lines(), preserving exact 1-based start_line..end_line
        for (int startIdx = 0; startIdx < lineCount; startIdx += CHUNK_LINES) {
            int endIdx = Math.min(lineCount, startIdx + CHUNK_LINES);
            var chunkText = new StringBuilder();
            for (int li = startIdx; li < endIdx; li++) {
                if (li > startIdx) chunkText.append('\n');
                chunkText.append(lines.get(li));
            }
            String redactedChunk = chunkText.toString();
            String splitTokens = expandSplitTokens(redactedChunk);

            insChunk.setLong(1, docId);
            insChunk.setString(2, graphDigest);
            insChunk.setString(3, path);
            insChunk.setInt(4, startIdx + 1);
            insChunk.setInt(5, endIdx);
            insChunk.setString(6, safeSymbols);
            insChunk.setString(7, redactedChunk);
            insChunk.setString(8, splitTokens);
            insChunk.executeUpdate();
            chunks++;
        }
        return new int[]{chunks, docRedactedCount};
    }

    /**
     * Decodes non-binary source text resiliently so valid source files containing legacy Latin-1/Windows-1252
     * comments are never silently dropped from FTS5 coverage (eliminating DKB's dropped-chunk defect).
     */
    private static String decodeCapturedText(byte[] rawBytes) {
        if (rawBytes.length >= 2) {
            int b0 = rawBytes[0] & 0xFF;
            int b1 = rawBytes[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                String t = new String(rawBytes, 2, rawBytes.length - 2, StandardCharsets.UTF_16LE);
                return t.startsWith("\uFEFF") ? t.substring(1) : t;
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                String t = new String(rawBytes, 2, rawBytes.length - 2, StandardCharsets.UTF_16BE);
                return t.startsWith("\uFEFF") ? t.substring(1) : t;
            }
        }
        try {
            String rawText = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawBytes)).toString();
            return rawText.startsWith("\uFEFF") ? rawText.substring(1) : rawText;
        } catch (CharacterCodingException legacyText) {
            // Non-NUL text file with non-UTF-8 characters (e.g. ISO-8859-1 comments): decode leniently!
            String fallback = new String(rawBytes, StandardCharsets.UTF_8);
            return fallback.startsWith("\uFEFF") ? fallback.substring(1) : fallback;
        }
    }

    /**
     * Executes a pre-redacted SQLite FTS5 BM25 search against {@code graph}.
     * Returns empty only when opened read-only and the snapshot has not yet been indexed (so callers
     * can gracefully degrade to streaming CAS fallback without mutating the read-only database).
     */
    public Optional<CodeSearchResult> codeSearch(
            KnowledgeGraph graph,
            String rawQuery,
            List<String> languages,
            boolean includeAdvisory,
            int limit,
            int offset,
            Supplier<StopReason> stop) {
        return codeSearch(graph, rawQuery, languages, "", includeAdvisory, limit, offset, stop);
    }

    /**
     * Executes a pre-redacted SQLite FTS5 BM25 search with an optional path substring/glob filter.
     */
    public Optional<CodeSearchResult> codeSearch(
            KnowledgeGraph graph,
            String rawQuery,
            List<String> languages,
            String pathFilter,
            boolean includeAdvisory,
            int limit,
            int offset,
            Supplier<StopReason> stop) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        int safeOffset = Math.max(0, offset);
        var statsOpt = ensureIndexed(graph, stop);
        if (statsOpt.isEmpty()) {
            return Optional.empty();
        }
        var stats = statsOpt.orElseThrow();
        return runCodeSearchWithStats(stats, rawQuery, languages, pathFilter, includeAdvisory, safeLimit, safeOffset);
    }

    private Optional<CodeSearchResult> runCodeSearchWithStats(
            SnapshotStats stats,
            String rawQuery,
            List<String> languages,
            String pathFilter,
            boolean includeAdvisory,
            int safeLimit,
            int safeOffset) {
        List<String> terms = extractSafeTerms(rawQuery);
        if (terms.isEmpty()) {
            return Optional.of(new CodeSearchResult(
                    ENGINE_SQLITE_FTS5, 0, false, stats.omittedFileCount(), stats.redactedSecretCount(),
                    safeOffset, safeLimit, List.of()));
        }

        List<String> normLangs = languages == null ? List.of()
                : languages.stream()
                        .filter(Objects::nonNull)
                        .map(s -> s.strip().toLowerCase(Locale.ROOT))
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
        String normPathFilter = pathFilter == null ? "" : pathFilter.strip();

        synchronized (dbLock) {
            if (closed) return Optional.empty();
            try {
                // 1. Strict AND query (best precision for multi-word & camelCase/snake_case queries)
                String andExpr = buildFtsMatchExpression(terms, true, false);
                var result = executeFtsQuery(stats, andExpr, terms, normLangs, normPathFilter,
                        includeAdvisory, safeLimit, safeOffset);
                if (result.totalHits() > 0) {
                    return Optional.of(result);
                }
                // 2. Prefix AND query for partial tokens (e.g. "Reconcil" -> "reconcil"*)
                String prefixExpr = buildFtsMatchExpression(terms, true, true);
                if (!prefixExpr.equals(andExpr)) {
                    var prefixRes = executeFtsQuery(stats, prefixExpr, terms, normLangs, normPathFilter,
                            includeAdvisory, safeLimit, safeOffset);
                    if (prefixRes.totalHits() > 0) {
                        return Optional.of(prefixRes);
                    }
                }
                // 3. Relaxed OR query for multi-term natural language queries (BM25 naturally ranks multi-hit chunks highest)
                if (terms.size() > 1) {
                    String orExpr = buildFtsMatchExpression(terms, false, false);
                    var orRes = executeFtsQuery(stats, orExpr, terms, normLangs, normPathFilter,
                            includeAdvisory, safeLimit, safeOffset);
                    return Optional.of(orRes);
                }
                return Optional.of(result);
            } catch (SQLException err) {
                return Optional.empty();
            }
        }
    }

    private CodeSearchResult executeFtsQuery(
            SnapshotStats stats,
            String ftsMatchExpr,
            List<String> queryTerms,
            List<String> normLangs,
            String pathFilter,
            boolean includeAdvisory,
            int limit,
            int offset) throws SQLException {
        if (ftsMatchExpr.isEmpty()) {
            return new CodeSearchResult(ENGINE_SQLITE_FTS5, 0, false,
                    stats.omittedFileCount(), stats.redactedSecretCount(), offset, limit, List.of());
        }
        var where = new StringBuilder();
        where.append(" WHERE code_chunks_fts MATCH ? AND c.graph_digest = ?");
        if (!includeAdvisory) {
            where.append(" AND d.is_advisory = 0");
        }
        if (!normLangs.isEmpty()) {
            where.append(" AND d.language IN (");
            for (int i = 0; i < normLangs.size(); i++) {
                if (i > 0) where.append(',');
                where.append('?');
            }
            where.append(')');
        }
        boolean hasPathFilter = !pathFilter.isEmpty();
        java.util.regex.Pattern pathGlob = (hasPathFilter && (pathFilter.indexOf('*') >= 0 || pathFilter.indexOf('?') >= 0))
                ? compileGlob(pathFilter) : null;
        String pathSubLower = pathFilter.toLowerCase(Locale.ROOT);
        if (hasPathFilter && pathGlob == null) {
            where.append(" AND instr(lower(c.path), ?) > 0");
        }

        String baseFrom = """
                FROM code_chunks_fts
                JOIN code_chunks c ON c.chunk_id = code_chunks_fts.rowid
                JOIN code_documents d ON d.doc_id = c.doc_id
                """;

        if (pathGlob != null) {
            // Post-filter glob deterministically while preserving BM25 ordering; truncated strictly reflects engine ceiling
            String sql = """
                    SELECT c.path, c.start_line, c.end_line, c.redacted_text,
                           d.content_sha256, d.language, d.trust_tier, d.is_advisory, d.redacted_count,
                           bm25(code_chunks_fts, 2.5, 2.0, 1.0) AS rank_score
                    """ + baseFrom + where + " ORDER BY rank_score ASC, c.path ASC, c.start_line ASC LIMIT " + (MAX_MATCH_ROWS + 1);
            var matched = new ArrayList<CodeSearchHit>();
            int scannedRows = 0;
            boolean hitCeiling = false;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bindCommonParams(ps, ftsMatchExpr, stats.graphDigest(), normLangs, null);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        scannedRows++;
                        if (scannedRows > MAX_MATCH_ROWS) {
                            hitCeiling = true;
                            break;
                        }
                        String p = rs.getString(1);
                        if (!pathGlob.matcher(p).matches()) continue;
                        matched.add(rowToHit(rs, queryTerms));
                    }
                }
            }
            int total = matched.size();
            int start = Math.min(offset, total);
            int end = Math.min(total, start + limit);
            return new CodeSearchResult(ENGINE_SQLITE_FTS5, total, hitCeiling,
                    stats.omittedFileCount(), stats.redactedSecretCount(), offset, limit,
                    List.copyOf(matched.subList(start, end)));
        }

        int totalHits;
        String countSql = "SELECT COUNT(*) " + baseFrom + where;
        try (PreparedStatement ps = connection.prepareStatement(countSql)) {
            bindCommonParams(ps, ftsMatchExpr, stats.graphDigest(), normLangs, hasPathFilter ? pathSubLower : null);
            try (ResultSet rs = ps.executeQuery()) {
                totalHits = rs.next() ? rs.getInt(1) : 0;
            }
        }
        boolean hitCeiling = totalHits > MAX_MATCH_ROWS;
        int cappedTotal = Math.min(totalHits, MAX_MATCH_ROWS);
        if (cappedTotal == 0 || offset >= cappedTotal) {
            return new CodeSearchResult(ENGINE_SQLITE_FTS5, cappedTotal, hitCeiling,
                    stats.omittedFileCount(), stats.redactedSecretCount(), offset, limit, List.of());
        }
        int effectiveLimit = Math.min(limit, cappedTotal - offset);

        String selectSql = """
                SELECT c.path, c.start_line, c.end_line, c.redacted_text,
                       d.content_sha256, d.language, d.trust_tier, d.is_advisory, d.redacted_count,
                       bm25(code_chunks_fts, 2.5, 2.0, 1.0) AS rank_score
                """ + baseFrom + where + " ORDER BY rank_score ASC, c.path ASC, c.start_line ASC LIMIT ? OFFSET ?";
        var items = new ArrayList<CodeSearchHit>();
        try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
            int idx = bindCommonParams(ps, ftsMatchExpr, stats.graphDigest(), normLangs, hasPathFilter ? pathSubLower : null);
            ps.setInt(idx++, effectiveLimit);
            ps.setInt(idx, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(rowToHit(rs, queryTerms));
                }
            }
        }
        return new CodeSearchResult(ENGINE_SQLITE_FTS5, cappedTotal, hitCeiling,
                stats.omittedFileCount(), stats.redactedSecretCount(), offset, limit, items);
    }

    private static int bindCommonParams(PreparedStatement ps, String matchExpr, String graphDigest,
                                        List<String> normLangs, String pathSubLower) throws SQLException {
        int idx = 1;
        ps.setString(idx++, matchExpr);
        ps.setString(idx++, graphDigest);
        for (String lang : normLangs) {
            ps.setString(idx++, lang);
        }
        if (pathSubLower != null) {
            ps.setString(idx++, pathSubLower);
        }
        return idx;
    }

    private static CodeSearchHit rowToHit(ResultSet rs, List<String> queryTerms) throws SQLException {
        String path = rs.getString(1);
        int chunkStart = rs.getInt(2);
        int chunkEnd = rs.getInt(3);
        String redactedText = rs.getString(4);
        String contentSha256 = rs.getString(5);
        String language = rs.getString(6);
        TrustTier trustTier = parseTrustTier(rs.getString(7));
        boolean isAdvisory = rs.getInt(8) != 0;
        int redactedCount = rs.getInt(9);
        double rawBm25 = rs.getDouble(10);
        // SQLite FTS5 bm25() is negative (more negative = better); invert to positive deterministic BigDecimal.
        double positiveScore = Double.isFinite(rawBm25) ? Math.max(0.000001, -rawBm25) : 0.000001;
        BigDecimal score = BigDecimal.valueOf(positiveScore).setScale(6, RoundingMode.HALF_UP);

        var focused = buildSnippetWindow(redactedText, chunkStart, chunkEnd, queryTerms);
        return new CodeSearchHit(
                path, focused.startLine(), focused.endLine(), score, focused.snippet(),
                contentSha256, language, trustTier, isAdvisory, redactedCount);
    }

    /**
     * Computes deterministic file-level BM25 rankings for Hybrid RRF ({@link RepoMapBuilder}).
     * Tie-breaks strictly by {@code (best_bm25 ASC, path ASC)}.
     */
    public List<FileBm25Hit> rankFiles(KnowledgeGraph graph, List<String> seeds, int maxFiles, Supplier<StopReason> stop) {
        if (seeds == null || seeds.isEmpty() || maxFiles <= 0) return List.of();
        var statsOpt = ensureIndexed(graph, stop);
        if (statsOpt.isEmpty()) return List.of();
        var stats = statsOpt.orElseThrow();

        String combined = String.join(" ", seeds);
        List<String> terms = extractSafeTerms(combined);
        if (terms.isEmpty()) return List.of();

        return rankFilesInternal(stats.graphDigest(), terms, Math.max(1, Math.min(MAX_RANK_FILES_CAP, maxFiles)));
    }

    private List<FileBm25Hit> rankFilesInternal(String graphDigest, List<String> terms, int cap) {
        synchronized (dbLock) {
            if (closed) return List.of();
            try {
                // For multi-seed RRF, try AND first when <= 4 terms, then OR fallback so partial matches still contribute
                List<FileBm25Hit> hits = List.of();
                if (terms.size() <= 4) {
                    hits = runRankFilesQuery(graphDigest, buildFtsMatchExpression(terms, true, false), cap);
                }
                if (hits.isEmpty()) {
                    hits = runRankFilesQuery(graphDigest, buildFtsMatchExpression(terms, false, false), cap);
                }
                if (hits.isEmpty()) {
                    hits = runRankFilesQuery(graphDigest, buildFtsMatchExpression(terms, false, true), cap);
                }
                return hits;
            } catch (SQLException err) {
                return List.of();
            }
        }
    }

    private List<FileBm25Hit> runRankFilesQuery(String graphDigest, String ftsExpr, int cap) throws SQLException {
        if (ftsExpr.isEmpty()) return List.of();
        // SQLite FTS5 forbids bm25() inside aggregate GROUP BY queries; stream ranked chunks and deduplicate per path.
        String sql = """
                SELECT c.path, bm25(code_chunks_fts, 2.5, 2.0, 1.0) AS rank_score
                FROM code_chunks_fts
                JOIN code_chunks c ON c.chunk_id = code_chunks_fts.rowid
                WHERE code_chunks_fts MATCH ?
                  AND c.graph_digest = ?
                ORDER BY rank_score ASC, c.path ASC, c.start_line ASC
                LIMIT ?
                """;
        var out = new ArrayList<FileBm25Hit>();
        var seenPaths = new HashSet<String>();
        int chunkLimit = Math.min(25_000, Math.max(cap * 8, 200));
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ftsExpr);
            ps.setString(2, graphDigest);
            ps.setInt(3, chunkLimit);
            try (ResultSet rs = ps.executeQuery()) {
                int rank = 0;
                while (rs.next() && out.size() < cap) {
                    String path = rs.getString(1);
                    if (!seenPaths.add(path)) continue;
                    rank++;
                    double raw = rs.getDouble(2);
                    double pos = Double.isFinite(raw) ? Math.max(0.000001, -raw) : 0.000001;
                    out.add(new FileBm25Hit(path, rank, BigDecimal.valueOf(pos).setScale(6, RoundingMode.HALF_UP)));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Expands camelCase, PascalCase, acronym, and snake_case identifiers in pre-redacted text so
     * SQLite FTS5 {@code unicode61} matches both full identifiers and split sub-tokens.
     */
    public static String expandSplitTokens(String redactedText) {
        if (redactedText == null || redactedText.isEmpty()) return "";
        var out = new StringBuilder(redactedText.length() + (redactedText.length() / 4));
        int len = redactedText.length();
        int i = 0;
        while (i < len) {
            char c = redactedText.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                i++;
                continue;
            }
            int start = i;
            while (i < len && (Character.isLetterOrDigit(redactedText.charAt(i)) || redactedText.charAt(i) == '_')) {
                i++;
            }
            String word = redactedText.substring(start, i);
            if (!out.isEmpty()) out.append(' ');
            out.append(word.toLowerCase(Locale.ROOT));

            // Split on underscores and camelCase/digit boundaries
            boolean hasUnderscore = word.indexOf('_') >= 0;
            String[] segments = hasUnderscore ? word.split("_+") : new String[]{word};
            for (String seg : segments) {
                if (seg.isEmpty()) continue;
                String segLow = seg.toLowerCase(Locale.ROOT);
                if (hasUnderscore && !segLow.equals(word.toLowerCase(Locale.ROOT))) {
                    out.append(' ').append(segLow);
                }
                appendCamelSplit(seg, segLow, out);
            }
        }
        return out.toString();
    }

    private static void appendCamelSplit(String seg, String segLow, StringBuilder out) {
        int segLen = seg.length();
        if (segLen < 2) return;
        int start = 0;
        boolean emitted = false;
        for (int i = 1; i < segLen; i++) {
            char prev = seg.charAt(i - 1);
            char cur = seg.charAt(i);
            char next = (i + 1 < segLen) ? seg.charAt(i + 1) : 0;
            boolean boundary = (Character.isLowerCase(prev) && Character.isUpperCase(cur))
                    || (Character.isDigit(prev) != Character.isDigit(cur))
                    || (Character.isUpperCase(prev) && Character.isUpperCase(cur) && next != 0 && Character.isLowerCase(next));
            if (boundary) {
                if (i > start) {
                    String part = seg.substring(start, i).toLowerCase(Locale.ROOT);
                    if (!part.equals(segLow)) {
                        out.append(' ').append(part);
                        emitted = true;
                    }
                }
                start = i;
            }
        }
        if (emitted && start < segLen) {
            String tail = seg.substring(start).toLowerCase(Locale.ROOT);
            if (!tail.equals(segLow)) {
                out.append(' ').append(tail);
            }
        }
    }

    /**
     * Extracts strictly safe alphanumeric terms (plus camelCase/snake_case splits) from arbitrary
     * user queries so FTS5 syntax metacharacters ({@code " * - : OR NOT NEAR ( ) /}) can NEVER
     * trigger SQLite FTS5 syntax errors.
     */
    public static List<String> extractSafeTerms(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return List.of();
        var terms = new LinkedHashSet<String>();
        int len = rawQuery.length();
        int i = 0;
        while (i < len && terms.size() < MAX_QUERY_TERMS) {
            char c = rawQuery.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                i++;
                continue;
            }
            int start = i;
            while (i < len && Character.isLetterOrDigit(rawQuery.charAt(i))) {
                i++;
            }
            String token = rawQuery.substring(start, i);
            List<String> subparts = splitCamelOnly(token);
            if (subparts.size() > 1) {
                for (String sp : subparts) {
                    if (terms.size() >= MAX_QUERY_TERMS) break;
                    terms.add(sp);
                }
            } else {
                terms.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(terms);
    }

    private static List<String> splitCamelOnly(String part) {
        var out = new ArrayList<String>();
        int start = 0;
        for (int i = 1; i < part.length(); i++) {
            char prev = part.charAt(i - 1);
            char cur = part.charAt(i);
            char next = (i + 1 < part.length()) ? part.charAt(i + 1) : 0;
            boolean boundary = (Character.isLowerCase(prev) && Character.isUpperCase(cur))
                    || (Character.isDigit(prev) != Character.isDigit(cur))
                    || (Character.isUpperCase(prev) && Character.isUpperCase(cur) && next != 0 && Character.isLowerCase(next));
            if (boundary) {
                if (i > start) out.add(part.substring(start, i).toLowerCase(Locale.ROOT));
                start = i;
            }
        }
        if (start < part.length()) {
            out.add(part.substring(start).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * Builds a syntactically guaranteed-safe FTS5 {@code MATCH} query by double-quoting every
     * sanitized alphanumeric token (neutralizing reserved operators {@code OR}, {@code NOT}, {@code AND},
     * {@code NEAR}, column prefixes, quotes, parentheses, hyphens, slashes, and wildcards).
     */
    public static String buildFtsMatchExpression(List<String> terms, boolean useAnd, boolean prefixWildcard) {
        if (terms == null || terms.isEmpty()) return "";
        var sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            String t = terms.get(i);
            if (t == null || t.isEmpty()) continue;
            if (!sb.isEmpty()) {
                sb.append(useAnd ? " " : " OR ");
            }
            sb.append('"').append(t.replace("\"", "\"\"")).append('"');
            if (prefixWildcard && t.length() >= 3) {
                sb.append('*');
            }
        }
        return sb.toString();
    }

    private record SnippetWindow(int startLine, int endLine, String snippet) {}

    private static SnippetWindow buildSnippetWindow(String redactedChunk, int chunkStartLine, int chunkEndLine, List<String> terms) {
        String[] lines = redactedChunk.split("\\R", -1);
        if (lines.length == 0) {
            return new SnippetWindow(chunkStartLine, chunkEndLine, "");
        }
        int bestIdx = 0;
        int bestHits = -1;
        for (int i = 0; i < lines.length; i++) {
            String low = lines[i].toLowerCase(Locale.ROOT);
            int hits = 0;
            for (String t : terms) {
                if (low.contains(t)) hits++;
            }
            if (hits > bestHits) {
                bestHits = hits;
                bestIdx = i;
                if (hits == terms.size()) break;
            }
        }
        int winStart = Math.max(0, bestIdx - 1);
        int winEnd = Math.min(lines.length - 1, bestIdx + 2);
        var sb = new StringBuilder();
        for (int i = winStart; i <= winEnd; i++) {
            if (i > winStart) sb.append('\n');
            sb.append(lines[i]);
            if (sb.length() >= MAX_SNIPPET_CHARS) break;
        }
        String snippet = sb.length() > MAX_SNIPPET_CHARS
                ? sb.substring(0, MAX_SNIPPET_CHARS) + "…"
                : sb.toString();
        return new SnippetWindow(chunkStartLine + winStart, chunkStartLine + winEnd, snippet);
    }

    private record GraphFileMetadata(
            Set<String> compilerPaths,
            Set<String> springPaths,
            boolean hasSpringContext,
            Map<String, String> symbolsByPath) {
        static final GraphFileMetadata EMPTY = new GraphFileMetadata(Set.of(), Set.of(), false, Map.of());
    }

    private static GraphFileMetadata extractGraphFileMetadata(KnowledgeGraph graph, CapturedFileIndex fileIndex) {
        var docIdToPath = new HashMap<String, String>();
        var compilerPaths = new HashSet<String>();
        var springPaths = new HashSet<String>();
        var symbolSets = new HashMap<String, LinkedHashSet<String>>();
        boolean hasSpringContext = graph.contexts().stream().anyMatch(c ->
                graph.evidence(c.evidence()).map(e -> "spring.context".equals(e.family())).orElse(false));

        for (var ctx : graph.contexts()) {
            for (var node : graph.nodes(ctx.identity())) {
                try {
                    if ("SOURCE_DOCUMENT".equals(node.kind())) {
                        for (var ref : node.evidence()) {
                            var ev = graph.evidence(ref).orElse(null);
                            if (ev == null) continue;
                            if ("source.document".equals(ev.family())) {
                                var obj = readSmall(ev);
                                if (obj.get("path") instanceof String p && !p.isBlank()) {
                                    docIdToPath.put(node.identity(), CapturedFileIndex.normalizePath(p));
                                }
                            } else if ("source.bytes".equals(ev.family())) {
                                var obj = readSmall(ev);
                                if (obj.get("document") instanceof Map<?, ?> d && d.get("path") instanceof String p && !p.isBlank()) {
                                    docIdToPath.put(node.identity(), CapturedFileIndex.normalizePath(p));
                                }
                            }
                        }
                    } else if ("INVENTORY_ENTRY".equals(node.kind())) {
                        for (var ref : node.evidence()) {
                            var ev = graph.evidence(ref).orElse(null);
                            if (ev == null || !"polyglot.inventory-entry".equals(ev.family())) continue;
                            var obj = readSmall(ev);
                            if (obj.get("fields") instanceof Map<?, ?> fields && fields.get("rawPath") instanceof String rawB64) {
                                new LogicalPath(rawB64).utf8()
                                        .ifPresent(p -> docIdToPath.put(node.identity(), CapturedFileIndex.normalizePath(p)));
                            }
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Isolate semantic-stage evidence errors per node (I3)
                }
            }
        }

        for (var ctx : graph.contexts()) {
            for (var node : graph.nodes(ctx.identity())) {
                for (var ref : node.evidence()) {
                    try {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null) continue;
                        switch (ev.family()) {
                            case "java.declaration" -> {
                                var payload = readSmall(ev);
                                if (payload.get("entity") instanceof Map<?, ?> ent) {
                                    String path = null;
                                    if (ent.get("declaration") instanceof Map<?, ?> span && span.get("document") instanceof String docId) {
                                        path = docIdToPath.get(docId);
                                    }
                                    if (path != null) {
                                        compilerPaths.add(path);
                                        if (ent.get("canonicalName") instanceof String cn) {
                                            String readable = RepoMapBuilder.readableCanonicalName(cn);
                                            if (readable != null && !readable.isBlank()) {
                                                symbolSets.computeIfAbsent(path, k -> new LinkedHashSet<>()).add(readable);
                                            }
                                        }
                                        if (payload.get("spelling") instanceof String sp && !sp.isBlank()) {
                                            symbolSets.computeIfAbsent(path, k -> new LinkedHashSet<>()).add(sp);
                                        }
                                    }
                                }
                            }
                            case "spring.candidate", "spring.endpoint-declaration" -> {
                                var payload = readSmall(ev);
                                String path = null;
                                if (payload.get("span") instanceof Map<?, ?> span && span.get("document") instanceof String docId) {
                                    path = docIdToPath.get(docId);
                                }
                                if (path != null) {
                                    springPaths.add(path);
                                    if (payload.get("declaredNameKey") instanceof Map<?, ?> key && key.get("primary") instanceof String p) {
                                        symbolSets.computeIfAbsent(path, k -> new LinkedHashSet<>()).add(p);
                                    }
                                }
                            }
                            case "polyglot.source-syntax" -> {
                                var payload = readSmall(ev);
                                Map<?, ?> fields = payload.get("fields") instanceof Map<?, ?> f ? f : payload;
                                String path = null;
                                if (fields.get("path") instanceof String p && !p.isBlank()) {
                                    path = CapturedFileIndex.normalizePath(p);
                                } else if (fields.get("rawPath") instanceof String rawB64) {
                                    var utf8 = new LogicalPath(rawB64).utf8();
                                    if (utf8.isPresent()) path = CapturedFileIndex.normalizePath(utf8.orElseThrow());
                                }
                                if (path != null) {
                                    if (fields.get("fqn") instanceof String q && !q.isBlank()) {
                                        symbolSets.computeIfAbsent(path, k -> new LinkedHashSet<>()).add(q);
                                    }
                                    if (fields.get("name") instanceof String n && !n.isBlank()) {
                                        symbolSets.computeIfAbsent(path, k -> new LinkedHashSet<>()).add(n);
                                    } else if (fields.get("symbol") instanceof String s && !s.isBlank()) {
                                        symbolSets.computeIfAbsent(path, k -> new LinkedHashSet<>()).add(s);
                                    }
                                }
                            }
                            default -> {}
                        }
                    } catch (RuntimeException ignored) {
                        // Isolate semantic-stage exceptions per evidence ref (I3)
                    }
                }
            }
        }

        var joined = new HashMap<String, String>(symbolSets.size() * 2);
        for (var e : symbolSets.entrySet()) {
            joined.put(e.getKey(), String.join(" ", e.getValue()));
        }
        return new GraphFileMetadata(compilerPaths, springPaths, hasSpringContext, joined);
    }

    /** Public helper shared with streaming fallback for consistent {@link TrustTier} classification. */
    public static boolean isAdvisoryPath(String path, String language) {
        if (language != null && ADVISORY_LANGUAGES.contains(language.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (path == null || path.isEmpty()) return false;
        String low = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = low.substring(low.lastIndexOf('/') + 1);
        return name.equals(".mcp.json") || name.equals("mcp.json") || name.endsWith(".mcp.json")
                || name.endsWith(".md") || name.endsWith(".mdx") || name.endsWith(".rst") || name.endsWith(".adoc")
                || name.endsWith(".sql") || name.endsWith(".tf") || name.endsWith(".tfvars") || name.endsWith(".hcl");
    }

    private static TrustTier classifyTrustTier(String path, String language, GraphFileMetadata meta) {
        if (isAdvisoryPath(path, language)) {
            return TrustTier.ADVISORY_UNVERIFIED;
        }
        if (meta.compilerPaths.contains(path)) {
            return TrustTier.COMPILER;
        }
        if (meta.springPaths.contains(path)) {
            return TrustTier.SPRING_SAT;
        }
        if (meta.hasSpringContext) {
            String low = path.toLowerCase(Locale.ROOT);
            if (low.endsWith("application.yml") || low.endsWith("application.yaml")
                    || low.endsWith("application.properties")
                    || low.matches(".*application-[a-z0-9_\\-]+\\.(?:yml|yaml|properties)$")) {
                return TrustTier.SPRING_SAT;
            }
        }
        return TrustTier.SOURCE_SYNTAX;
    }

    private static TrustTier parseTrustTier(String raw) {
        if (raw == null || raw.isBlank()) return TrustTier.SOURCE_SYNTAX;
        try {
            return TrustTier.valueOf(raw);
        } catch (IllegalArgumentException invalid) {
            return TrustTier.SOURCE_SYNTAX;
        }
    }

    private static boolean containsNul(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    private static java.util.regex.Pattern compileGlob(String glob) {
        var regex = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i += 2;
                    if (i < glob.length() && glob.charAt(i) == '/') i++;
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) regex.append('\\');
                regex.append(c);
                i++;
            }
        }
        regex.append('$');
        return java.util.regex.Pattern.compile(regex.toString(), java.util.regex.Pattern.CASE_INSENSITIVE);
    }

    private static Map<String, Object> readSmall(GraphEvidence ev) {
        if (ev.canonicalJson().length() > 131_072) return Map.of();
        try {
            return GraphJson.object(GraphJson.read(ev.canonicalJson(), SMALL_EVIDENCE));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private void closeQuietly() {
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
            connection = null;
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (dbLock) {
            closeQuietly();
        }
    }
}
