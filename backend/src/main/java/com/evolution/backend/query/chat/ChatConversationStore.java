package com.evolution.backend.query.chat;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.query.llm.LlmTransport;
import com.evolution.backend.query.repair.SafePatchApplier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Single-owner SQLite persistence store for tenant-scoped architecture chat threads and turns
 * ({@code <store>/chat/conversations.db}).
 *
 * <p>Core invariants enforced:
 * <ul>
 *   <li><b>Single JDBC Owner:</b> Only this class opens connections to {@code conversations.db};
 *       no {@code java.sql.*} type leaks across its boundary.</li>
 *   <li><b>Operational PRAGMAs:</b> {@code journal_mode=WAL}, {@code busy_timeout=5000},
 *       {@code synchronous=NORMAL}, {@code foreign_keys=ON}, {@code temp_store=MEMORY}.</li>
 *   <li><b>Tenant Isolation:</b> Every lookup, append, list, and delete enforces {@code owner_user_id}.
 *       Cross-tenant access throws {@link AccessDeniedException} (HTTP 403); missing IDs throw
 *       {@link NotFoundException} (HTTP 404).</li>
 *   <li><b>Hard Secret Rule (I2):</b> User/assistant content, title, evidence, tool traces,
 *       citations, and metadata are recursively redacted via {@link LlmTransport} before SQLite
 *       insertion ({@code 0} raw secrets on disk). Combined per-turn JSON columns are strictly
 *       bounded to {@code <= 512 KiB}.</li>
 * </ul>
 */
public final class ChatConversationStore implements AutoCloseable {
    public static final String SCHEMA_VERSION = "1";
    public static final String DEFAULT_TITLE = "New conversation";
    public static final int MAX_TURN_JSON_BYTES = 512 * 1024;
    public static final int MAX_TITLE_CHARS = 160;
    public static final int MAX_CONTENT_CHARS = 64_000;
    public static final int DEFAULT_LIST_LIMIT = 50;
    public static final int MAX_LIST_LIMIT = 200;
    public static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("^conv_[a-f0-9]{16,64}$");
    private static final Set<String> VALID_MODES = Set.of("EXPLORE", "DESIGN", "REPAIR");
    private static final GraphJson.Limits TURN_JSON_LIMITS = new GraphJson.Limits(MAX_TURN_JSON_BYTES + 4096, 32, 65_536);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static final class AccessDeniedException extends SecurityException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    public static final class NotFoundException extends NoSuchElementException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public record ConversationSummary(
            String conversationId,
            String ownerUserId,
            String graphDigest,
            String snapshotDigest,
            String contextId,
            String mode,
            String title,
            Optional<String> findingId,
            boolean archived,
            long createdAtMs,
            long updatedAtMs,
            int turnCount) {
        public ConversationSummary {
            conversationId = Objects.requireNonNull(conversationId, "conversationId");
            ownerUserId = normalizeOwner(ownerUserId);
            graphDigest = Objects.requireNonNull(graphDigest, "graphDigest");
            snapshotDigest = Objects.requireNonNull(snapshotDigest, "snapshotDigest");
            contextId = Objects.requireNonNull(contextId, "contextId");
            mode = Objects.requireNonNull(mode, "mode");
            title = title == null ? DEFAULT_TITLE : title;
            findingId = findingId == null ? Optional.empty() : findingId.map(String::strip).filter(s -> !s.isEmpty());
            turnCount = Math.max(0, turnCount);
        }

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("conversationId", conversationId);
            map.put("graphDigest", graphDigest);
            map.put("snapshotDigest", snapshotDigest);
            map.put("contextId", contextId);
            map.put("mode", mode);
            map.put("title", title);
            map.put("findingId", findingId.orElse(null));
            map.put("archived", archived);
            map.put("createdAtMs", createdAtMs);
            map.put("updatedAtMs", updatedAtMs);
            map.put("turnCount", turnCount);
            return Collections.unmodifiableMap(map);
        }
    }

    public record ConversationTurn(
            String turnId,
            String conversationId,
            int seq,
            String role,
            String content,
            List<Object> evidence,
            List<Object> toolTrace,
            List<Object> citations,
            Map<String, Object> promptCacheMetrics,
            Optional<String> inputDigest,
            Optional<String> modelId,
            long createdAtMs) {
        public ConversationTurn {
            turnId = Objects.requireNonNull(turnId, "turnId");
            conversationId = Objects.requireNonNull(conversationId, "conversationId");
            role = Objects.requireNonNull(role, "role");
            content = content == null ? "" : content;
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
            citations = citations == null ? List.of() : List.copyOf(citations);
            promptCacheMetrics = promptCacheMetrics == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(promptCacheMetrics));
            inputDigest = inputDigest == null ? Optional.empty() : inputDigest.map(String::strip).filter(s -> !s.isEmpty());
            modelId = modelId == null ? Optional.empty() : modelId.map(String::strip).filter(s -> !s.isEmpty());
        }

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("turnId", turnId);
            map.put("conversationId", conversationId);
            map.put("seq", seq);
            map.put("role", role);
            map.put("content", content);
            map.put("evidence", evidence);
            map.put("toolTrace", toolTrace);
            map.put("citations", citations);
            map.put("promptCacheMetrics", promptCacheMetrics);
            map.put("inputDigest", inputDigest.orElse(null));
            map.put("modelId", modelId.orElse(null));
            map.put("createdAtMs", createdAtMs);
            return Collections.unmodifiableMap(map);
        }
    }

    public record ConversationDetail(
            ConversationSummary conversation,
            List<ConversationTurn> turns) {
        public ConversationDetail {
            conversation = Objects.requireNonNull(conversation, "conversation");
            turns = turns == null ? List.of() : List.copyOf(turns);
        }

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("conversation", conversation.toView());
            map.put("turns", turns.stream().map(ConversationTurn::toView).toList());
            return Collections.unmodifiableMap(map);
        }
    }

    public record AppendedTurnPair(
            String conversationId,
            String userTurnId,
            String assistantTurnId,
            int userSeq,
            int assistantSeq,
            String title,
            long updatedAtMs) {}

    private final Path dbFile;
    private final Connection connection;
    private final Object lock = new Object();
    private volatile boolean closed = false;

    public ChatConversationStore(Path dbFile) {
        this.dbFile = Objects.requireNonNull(dbFile, "dbFile").toAbsolutePath().normalize();
        try {
            if (this.dbFile.getParent() != null) {
                Files.createDirectories(this.dbFile.getParent());
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbFile);
            initializeSchema();
        } catch (IOException | SQLException err) {
            throw new IllegalStateException("Failed to initialize chat conversation store at " + this.dbFile, err);
        }
    }

    public Path dbFile() {
        return dbFile;
    }

    public static boolean isValidConversationId(String id) {
        return id != null && CONVERSATION_ID_PATTERN.matcher(id).matches();
    }

    public static String normalizeOwner(String ownerUserId) {
        return (ownerUserId == null || ownerUserId.isBlank())
                ? SafePatchApplier.DEFAULT_OWNER
                : ownerUserId.strip();
    }

    private void initializeSchema() throws SQLException {
        synchronized (lock) {
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA temp_store=MEMORY");
            }
            boolean txnStarted = false;
            try (Statement st = connection.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
                txnStarted = true;
                st.execute("""
                        CREATE TABLE IF NOT EXISTS schema_meta (
                            key   TEXT PRIMARY KEY,
                            value TEXT NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS conversations (
                            conversation_id TEXT PRIMARY KEY,
                            owner_user_id   TEXT NOT NULL,
                            graph_digest    TEXT NOT NULL,
                            snapshot_digest TEXT NOT NULL,
                            context_id      TEXT NOT NULL,
                            mode            TEXT NOT NULL,
                            title           TEXT NOT NULL,
                            finding_id      TEXT,
                            archived        INTEGER NOT NULL DEFAULT 0,
                            created_at_ms   INTEGER NOT NULL,
                            updated_at_ms   INTEGER NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE INDEX IF NOT EXISTS idx_conv_owner_graph
                            ON conversations(owner_user_id, graph_digest, archived, updated_at_ms DESC)
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS conversation_turns (
                            turn_id                   TEXT PRIMARY KEY,
                            conversation_id           TEXT NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
                            seq                       INTEGER NOT NULL,
                            role                      TEXT NOT NULL,
                            content                   TEXT NOT NULL,
                            evidence_json             TEXT NOT NULL DEFAULT '[]',
                            tool_trace_json           TEXT NOT NULL DEFAULT '[]',
                            citations_json            TEXT NOT NULL DEFAULT '[]',
                            prompt_cache_metrics_json TEXT NOT NULL DEFAULT '{}',
                            input_digest              TEXT,
                            model_id                  TEXT,
                            created_at_ms             INTEGER NOT NULL,
                            UNIQUE(conversation_id, seq)
                        )
                        """);
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO schema_meta(key, value) VALUES('schema_version', ?) "
                                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                    ps.setString(1, SCHEMA_VERSION);
                    ps.executeUpdate();
                }
                st.execute("COMMIT");
                txnStarted = false;
            } catch (SQLException err) {
                if (txnStarted) {
                    rollbackQuietly();
                }
                throw err;
            }
        }
    }

    public ConversationSummary createConversation(
            String ownerUserId,
            String graphDigest,
            String snapshotDigest,
            String contextId,
            String mode,
            String title,
            Optional<String> findingId) {
        ensureOpen();
        String owner = normalizeOwner(ownerUserId);
        String cleanGraph = requireNonBlank(graphDigest, "graphDigest", 256);
        String cleanSnapshot = requireNonBlank(snapshotDigest, "snapshotDigest", 512);
        String cleanContext = requireNonBlank(contextId, "contextId", 8192);
        String cleanMode = normalizeMode(mode);
        String cleanTitle = sanitizeTitle(title, DEFAULT_TITLE);
        String cleanFinding = findingId == null ? null
                : findingId.map(String::strip).filter(s -> !s.isEmpty()).map(ChatConversationStore::redactText).orElse(null);
        if (cleanFinding != null && cleanFinding.length() > 512) {
            throw new IllegalArgumentException("findingId exceeds 512 characters");
        }
        String convId = generateId("conv_");
        long now = System.currentTimeMillis();

        synchronized (lock) {
            ensureOpen();
            boolean txnStarted = false;
            try (Statement st = connection.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
                txnStarted = true;
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO conversations (
                            conversation_id, owner_user_id, graph_digest, snapshot_digest,
                            context_id, mode, title, finding_id, archived, created_at_ms, updated_at_ms
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                        """)) {
                    ps.setString(1, convId);
                    ps.setString(2, owner);
                    ps.setString(3, cleanGraph);
                    ps.setString(4, cleanSnapshot);
                    ps.setString(5, cleanContext);
                    ps.setString(6, cleanMode);
                    ps.setString(7, cleanTitle);
                    if (cleanFinding == null) {
                        ps.setNull(8, Types.VARCHAR);
                    } else {
                        ps.setString(8, cleanFinding);
                    }
                    ps.setLong(9, now);
                    ps.setLong(10, now);
                    ps.executeUpdate();
                }
                st.execute("COMMIT");
                txnStarted = false;
                return new ConversationSummary(
                        convId, owner, cleanGraph, cleanSnapshot, cleanContext, cleanMode,
                        cleanTitle, Optional.ofNullable(cleanFinding), false, now, now, 0);
            } catch (SQLException err) {
                if (txnStarted) rollbackQuietly();
                throw new IllegalStateException("Failed to create conversation", err);
            }
        }
    }

    public List<ConversationSummary> listConversations(String ownerUserId, String graphDigest, int limit) {
        ensureOpen();
        String owner = normalizeOwner(ownerUserId);
        String cleanGraph = requireNonBlank(graphDigest, "graphDigest", 256);
        int cappedLimit = Math.max(1, Math.min(MAX_LIST_LIMIT, limit <= 0 ? DEFAULT_LIST_LIMIT : limit));

        synchronized (lock) {
            ensureOpen();
            var rows = new ArrayList<ConversationSummary>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT c.conversation_id, c.owner_user_id, c.graph_digest, c.snapshot_digest,
                           c.context_id, c.mode, c.title, c.finding_id, c.archived,
                           c.created_at_ms, c.updated_at_ms,
                           (SELECT COUNT(*) FROM conversation_turns t WHERE t.conversation_id = c.conversation_id) AS turn_count
                    FROM conversations c
                    WHERE c.owner_user_id = ? AND c.graph_digest = ? AND c.archived = 0
                    ORDER BY c.updated_at_ms DESC, c.conversation_id ASC
                    LIMIT ?
                    """)) {
                ps.setString(1, owner);
                ps.setString(2, cleanGraph);
                ps.setInt(3, cappedLimit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(readSummary(rs));
                    }
                }
                return List.copyOf(rows);
            } catch (SQLException err) {
                throw new IllegalStateException("Failed to list conversations", err);
            }
        }
    }

    /**
     * Verifies conversation existence, tenant ownership, and optional graph pinning BEFORE invoking
     * an LLM provider. Throws {@link NotFoundException} (404), {@link AccessDeniedException} (403),
     * or {@link IllegalArgumentException} (400) when invalid or mismatched.
     */
    public ConversationSummary verifyAccess(String ownerUserId, String conversationId, String expectedGraphDigest) {
        ensureOpen();
        if (!isValidConversationId(conversationId)) {
            throw new IllegalArgumentException("Invalid conversationId");
        }
        String owner = normalizeOwner(ownerUserId);
        synchronized (lock) {
            ensureOpen();
            ConversationSummary summary = loadSummaryOrThrow(owner, conversationId);
            if (expectedGraphDigest != null && !expectedGraphDigest.isBlank()
                    && !summary.graphDigest().equals(expectedGraphDigest.strip())) {
                throw new IllegalArgumentException("CONVERSATION_GRAPH_MISMATCH");
            }
            return summary;
        }
    }

    public ConversationDetail getConversation(String ownerUserId, String conversationId) {
        ensureOpen();
        if (!isValidConversationId(conversationId)) {
            throw new IllegalArgumentException("Invalid conversationId");
        }
        String owner = normalizeOwner(ownerUserId);
        synchronized (lock) {
            ensureOpen();
            ConversationSummary summary = loadSummaryOrThrow(owner, conversationId);
            var turns = new ArrayList<ConversationTurn>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT turn_id, conversation_id, seq, role, content,
                           evidence_json, tool_trace_json, citations_json, prompt_cache_metrics_json,
                           input_digest, model_id, created_at_ms
                    FROM conversation_turns
                    WHERE conversation_id = ?
                    ORDER BY seq ASC
                    """)) {
                ps.setString(1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        turns.add(readTurn(rs));
                    }
                }
            } catch (SQLException err) {
                throw new IllegalStateException("Failed to load conversation turns", err);
            }
            return new ConversationDetail(summary, List.copyOf(turns));
        }
    }

    public AppendedTurnPair appendTurnPair(
            String ownerUserId,
            String conversationId,
            String expectedGraphDigest,
            String userContent,
            String assistantContent,
            List<?> evidence,
            List<?> toolTrace,
            List<?> citations,
            Map<String, ?> promptCacheMetrics,
            Optional<String> inputDigest,
            Optional<String> modelId,
            Optional<String> proposedTitle) {
        ensureOpen();
        if (!isValidConversationId(conversationId)) {
            throw new IllegalArgumentException("Invalid conversationId");
        }
        String owner = normalizeOwner(ownerUserId);
        String safeUserContent = truncateChars(redactText(userContent == null ? "" : userContent), MAX_CONTENT_CHARS);
        String safeAssistantContent = truncateChars(redactText(assistantContent == null ? "" : assistantContent), MAX_CONTENT_CHARS);

        String safeInputDigest = inputDigest == null ? null
                : inputDigest.map(String::strip).filter(s -> !s.isEmpty()).map(ChatConversationStore::redactText).orElse(null);
        String safeModelId = modelId == null ? null
                : modelId.map(String::strip).filter(s -> !s.isEmpty()).map(ChatConversationStore::redactText).orElse(null);

        // Pre-redact and enforce <= 512 KiB combined JSON payload budget per turn.
        BoundedTurnJson bounded = encodeBoundedTurnJson(evidence, toolTrace, citations, promptCacheMetrics);

        String userTurnId = generateId("turn_");
        String assistantTurnId = generateId("turn_");
        long now = System.currentTimeMillis();

        synchronized (lock) {
            ensureOpen();
            boolean txnStarted = false;
            try (Statement st = connection.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
                txnStarted = true;

                String existingOwner;
                String existingGraph;
                String existingTitle;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT owner_user_id, graph_digest, title FROM conversations WHERE conversation_id = ?")) {
                    ps.setString(1, conversationId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            st.execute("ROLLBACK");
                            txnStarted = false;
                            throw new NotFoundException("Conversation not found");
                        }
                        existingOwner = rs.getString(1);
                        existingGraph = rs.getString(2);
                        existingTitle = rs.getString(3);
                    }
                }
                if (!owner.equals(existingOwner)) {
                    st.execute("ROLLBACK");
                    txnStarted = false;
                    throw new AccessDeniedException("Conversation belongs to another user");
                }
                if (expectedGraphDigest != null && !expectedGraphDigest.isBlank()
                        && !existingGraph.equals(expectedGraphDigest.strip())) {
                    st.execute("ROLLBACK");
                    txnStarted = false;
                    throw new IllegalArgumentException("CONVERSATION_GRAPH_MISMATCH");
                }

                int maxSeq = 0;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT COALESCE(MAX(seq), 0) FROM conversation_turns WHERE conversation_id = ?")) {
                    ps.setString(1, conversationId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            maxSeq = rs.getInt(1);
                        }
                    }
                }
                int userSeq = maxSeq + 1;
                int assistantSeq = maxSeq + 2;

                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO conversation_turns (
                            turn_id, conversation_id, seq, role, content,
                            evidence_json, tool_trace_json, citations_json, prompt_cache_metrics_json,
                            input_digest, model_id, created_at_ms
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    // USER turn
                    ps.setString(1, userTurnId);
                    ps.setString(2, conversationId);
                    ps.setInt(3, userSeq);
                    ps.setString(4, "USER");
                    ps.setString(5, safeUserContent);
                    ps.setString(6, "[]");
                    ps.setString(7, "[]");
                    ps.setString(8, "[]");
                    ps.setString(9, "{}");
                    if (safeInputDigest == null) ps.setNull(10, Types.VARCHAR); else ps.setString(10, safeInputDigest);
                    ps.setNull(11, Types.VARCHAR);
                    ps.setLong(12, now);
                    ps.executeUpdate();

                    // ASSISTANT turn
                    ps.setString(1, assistantTurnId);
                    ps.setString(2, conversationId);
                    ps.setInt(3, assistantSeq);
                    ps.setString(4, "ASSISTANT");
                    ps.setString(5, safeAssistantContent);
                    ps.setString(6, bounded.evidenceJson());
                    ps.setString(7, bounded.toolTraceJson());
                    ps.setString(8, bounded.citationsJson());
                    ps.setString(9, bounded.promptCacheMetricsJson());
                    if (safeInputDigest == null) ps.setNull(10, Types.VARCHAR); else ps.setString(10, safeInputDigest);
                    if (safeModelId == null) ps.setNull(11, Types.VARCHAR); else ps.setString(11, safeModelId);
                    ps.setLong(12, now);
                    ps.executeUpdate();
                }

                String updatedTitle = existingTitle;
                if (existingTitle == null || existingTitle.isBlank() || DEFAULT_TITLE.equals(existingTitle)) {
                    String candidate = (proposedTitle != null && proposedTitle.isPresent() && !proposedTitle.orElseThrow().isBlank())
                            ? proposedTitle.orElseThrow()
                            : deriveTitleFromPrompt(safeUserContent);
                    updatedTitle = sanitizeTitle(candidate, DEFAULT_TITLE);
                }

                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE conversations SET title = ?, updated_at_ms = ? WHERE conversation_id = ? AND owner_user_id = ?")) {
                    ps.setString(1, updatedTitle);
                    ps.setLong(2, now);
                    ps.setString(3, conversationId);
                    ps.setString(4, owner);
                    ps.executeUpdate();
                }

                st.execute("COMMIT");
                txnStarted = false;
                return new AppendedTurnPair(conversationId, userTurnId, assistantTurnId, userSeq, assistantSeq, updatedTitle, now);
            } catch (SQLException err) {
                if (txnStarted) rollbackQuietly();
                throw new IllegalStateException("Failed to append conversation turn pair", err);
            }
        }
    }

    public boolean deleteConversation(String ownerUserId, String conversationId) {
        ensureOpen();
        if (!isValidConversationId(conversationId)) {
            throw new IllegalArgumentException("Invalid conversationId");
        }
        String owner = normalizeOwner(ownerUserId);

        synchronized (lock) {
            ensureOpen();
            boolean txnStarted = false;
            try (Statement st = connection.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
                txnStarted = true;
                String existingOwner;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT owner_user_id FROM conversations WHERE conversation_id = ?")) {
                    ps.setString(1, conversationId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            st.execute("ROLLBACK");
                            txnStarted = false;
                            throw new NotFoundException("Conversation not found");
                        }
                        existingOwner = rs.getString(1);
                    }
                }
                if (!owner.equals(existingOwner)) {
                    st.execute("ROLLBACK");
                    txnStarted = false;
                    throw new AccessDeniedException("Conversation belongs to another user");
                }
                int deleted;
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM conversations WHERE conversation_id = ? AND owner_user_id = ?")) {
                    ps.setString(1, conversationId);
                    ps.setString(2, owner);
                    deleted = ps.executeUpdate();
                }
                st.execute("COMMIT");
                txnStarted = false;
                return deleted > 0;
            } catch (SQLException err) {
                if (txnStarted) rollbackQuietly();
                throw new IllegalStateException("Failed to delete conversation", err);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Best-effort close on shutdown.
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("ChatConversationStore is closed");
        }
    }

    private void rollbackQuietly() {
        try (Statement rb = connection.createStatement()) {
            rb.execute("ROLLBACK");
        } catch (SQLException ignored) {}
    }

    private ConversationSummary loadSummaryOrThrow(String owner, String conversationId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT c.conversation_id, c.owner_user_id, c.graph_digest, c.snapshot_digest,
                       c.context_id, c.mode, c.title, c.finding_id, c.archived,
                       c.created_at_ms, c.updated_at_ms,
                       (SELECT COUNT(*) FROM conversation_turns t WHERE t.conversation_id = c.conversation_id) AS turn_count
                FROM conversations c
                WHERE c.conversation_id = ?
                """)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundException("Conversation not found");
                }
                ConversationSummary summary = readSummary(rs);
                if (!owner.equals(summary.ownerUserId())) {
                    throw new AccessDeniedException("Conversation belongs to another user");
                }
                return summary;
            }
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to load conversation", err);
        }
    }

    private static ConversationSummary readSummary(ResultSet rs) throws SQLException {
        return new ConversationSummary(
                rs.getString("conversation_id"),
                rs.getString("owner_user_id"),
                rs.getString("graph_digest"),
                rs.getString("snapshot_digest"),
                rs.getString("context_id"),
                rs.getString("mode"),
                rs.getString("title"),
                Optional.ofNullable(rs.getString("finding_id")),
                rs.getInt("archived") != 0,
                rs.getLong("created_at_ms"),
                rs.getLong("updated_at_ms"),
                rs.getInt("turn_count"));
    }

    private static ConversationTurn readTurn(ResultSet rs) throws SQLException {
        return new ConversationTurn(
                rs.getString("turn_id"),
                rs.getString("conversation_id"),
                rs.getInt("seq"),
                rs.getString("role"),
                rs.getString("content"),
                parseJsonList(rs.getString("evidence_json")),
                parseJsonList(rs.getString("tool_trace_json")),
                parseJsonList(rs.getString("citations_json")),
                parseJsonMap(rs.getString("prompt_cache_metrics_json")),
                Optional.ofNullable(rs.getString("input_digest")),
                Optional.ofNullable(rs.getString("model_id")),
                rs.getLong("created_at_ms"));
    }

    private static List<Object> parseJsonList(String raw) {
        if (raw == null || raw.isBlank() || "[]".equals(raw.strip())) return List.of();
        try {
            return GraphJson.array(GraphJson.read(raw, TURN_JSON_LIMITS));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static Map<String, Object> parseJsonMap(String raw) {
        if (raw == null || raw.isBlank() || "{}".equals(raw.strip())) return Map.of();
        try {
            return GraphJson.object(GraphJson.read(raw, TURN_JSON_LIMITS));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private record BoundedTurnJson(
            String evidenceJson,
            String toolTraceJson,
            String citationsJson,
            String promptCacheMetricsJson) {}

    private static BoundedTurnJson encodeBoundedTurnJson(
            List<?> evidence,
            List<?> toolTrace,
            List<?> citations,
            Map<String, ?> promptCacheMetrics) {
        String metricsJson = encodeRedactedObjectJson(promptCacheMetrics, 16 * 1024);
        String citationsJson = encodeRedactedListJson(citations, 64 * 1024);
        String toolTraceJson = encodeRedactedListJson(toolTrace, 128 * 1024);
        String evidenceJson = encodeRedactedListJson(evidence, 300 * 1024);

        int total = utf8Length(metricsJson) + utf8Length(citationsJson)
                + utf8Length(toolTraceJson) + utf8Length(evidenceJson);
        if (total > MAX_TURN_JSON_BYTES) {
            int budgetForEvidence = Math.max(256,
                    MAX_TURN_JSON_BYTES - utf8Length(metricsJson) - utf8Length(citationsJson) - utf8Length(toolTraceJson));
            evidenceJson = encodeRedactedListJson(evidence, budgetForEvidence);
            total = utf8Length(metricsJson) + utf8Length(citationsJson)
                    + utf8Length(toolTraceJson) + utf8Length(evidenceJson);
        }
        if (total > MAX_TURN_JSON_BYTES) {
            int budgetForTrace = Math.max(256,
                    MAX_TURN_JSON_BYTES - utf8Length(metricsJson) - utf8Length(citationsJson) - utf8Length(evidenceJson));
            toolTraceJson = encodeRedactedListJson(toolTrace, budgetForTrace);
            total = utf8Length(metricsJson) + utf8Length(citationsJson)
                    + utf8Length(toolTraceJson) + utf8Length(evidenceJson);
        }
        if (total > MAX_TURN_JSON_BYTES) {
            int budgetForCitations = Math.max(256,
                    MAX_TURN_JSON_BYTES - utf8Length(metricsJson) - utf8Length(toolTraceJson) - utf8Length(evidenceJson));
            citationsJson = encodeRedactedListJson(citations, budgetForCitations);
            total = utf8Length(metricsJson) + utf8Length(citationsJson)
                    + utf8Length(toolTraceJson) + utf8Length(evidenceJson);
        }
        if (total > MAX_TURN_JSON_BYTES) {
            evidenceJson = "[{\"truncated\":true,\"reason\":\"TURN_JSON_BOUND_EXCEEDED\"}]";
            toolTraceJson = "[]";
            citationsJson = "[]";
            metricsJson = "{}";
        }
        return new BoundedTurnJson(evidenceJson, toolTraceJson, citationsJson, metricsJson);
    }

    private static String encodeRedactedListJson(List<?> items, int maxBytes) {
        if (items == null || items.isEmpty()) return "[]";
        var redacted = new ArrayList<Object>(items.size());
        for (Object item : items) {
            redacted.add(redactValue(item, 0));
        }
        String encoded = encodeAndRedactJson(redacted);
        if (utf8Length(encoded) <= maxBytes) {
            return encoded;
        }
        // Progressively trim list items while keeping valid JSON and honest truncation marker.
        var kept = new ArrayList<Object>();
        int markerBytes = 64;
        for (Object item : redacted) {
            kept.add(item);
            String candidate = encodeAndRedactJson(kept);
            if (utf8Length(candidate) + markerBytes > maxBytes) {
                kept.removeLast();
                break;
            }
        }
        kept.add(Map.of("truncated", true, "omittedItems", Math.max(1, redacted.size() - kept.size())));
        String bounded = encodeAndRedactJson(kept);
        return utf8Length(bounded) <= maxBytes
                ? bounded
                : "[{\"truncated\":true,\"omittedItems\":" + redacted.size() + "}]";
    }

    private static String encodeRedactedObjectJson(Map<String, ?> map, int maxBytes) {
        if (map == null || map.isEmpty()) return "{}";
        Object redacted = redactValue(map, 0);
        String encoded = encodeAndRedactJson(redacted);
        return utf8Length(encoded) <= maxBytes ? encoded : "{}";
    }

    private static String encodeAndRedactJson(Object value) {
        String json = CanonicalJson.write(value);
        // Defense-in-depth pass over serialized JSON string.
        return redactText(json);
    }

    private static Object redactValue(Object value, int depth) {
        if (value == null) return null;
        if (depth > 14) return "[TRUNCATED]";
        if (value instanceof String s) {
            String cleaned = redactText(s);
            return cleaned.length() > 16_384 ? cleaned.substring(0, 16_384) + "... [truncated]" : cleaned;
        }
        if (value instanceof CanonicalJson.CanonicalValue cv) {
            return redactValue(cv.canonicalValue(), depth + 1);
        }
        if (value instanceof Optional<?> opt) {
            return opt.isEmpty() ? null : redactValue(opt.orElseThrow(), depth + 1);
        }
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>(map.size());
            for (var e : map.entrySet()) {
                String k = e.getKey() == null ? "" : redactText(String.valueOf(e.getKey()));
                copy.put(k, redactValue(e.getValue(), depth + 1));
            }
            return copy;
        }
        if (value instanceof Collection<?> col) {
            var copy = new ArrayList<Object>(col.size());
            for (Object item : col) {
                copy.add(redactValue(item, depth + 1));
            }
            return copy;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> en) {
            return en.name();
        }
        if (value.getClass().isRecord()) {
            // Convert Java records (e.g. Evidence, AgentStep, SourceSpan) to redacted maps so nested strings are always scrubbed.
            var copy = new LinkedHashMap<String, Object>();
            for (var rc : value.getClass().getRecordComponents()) {
                try {
                    var accessor = rc.getAccessor();
                    accessor.setAccessible(true);
                    copy.put(rc.getName(), redactValue(accessor.invoke(value), depth + 1));
                } catch (ReflectiveOperationException ignored) {
                    // Skip inaccessible component cleanly.
                }
            }
            return copy;
        }
        return redactText(String.valueOf(value));
    }

    static String redactText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String first = LlmTransport.redactSecrets(raw).text();
        return LlmTransport.redactForPath("", first, false).text();
    }

    private static String sanitizeTitle(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String cleaned = redactText(raw)
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (cleaned.isEmpty()) return fallback;
        return cleaned.length() > MAX_TITLE_CHARS
                ? cleaned.substring(0, MAX_TITLE_CHARS - 1).strip() + "…"
                : cleaned;
    }

    private static String deriveTitleFromPrompt(String safeUserContent) {
        if (safeUserContent == null || safeUserContent.isBlank()) return DEFAULT_TITLE;
        String firstLine = safeUserContent.split("\\R", 2)[0].strip();
        if (firstLine.length() > 80) {
            return firstLine.substring(0, 79).strip() + "…";
        }
        return firstLine.isEmpty() ? DEFAULT_TITLE : firstLine;
    }

    private static String truncateChars(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars - 16) + "\n... [truncated]";
    }

    private static int utf8Length(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String requireNonBlank(String value, String name, int maxLen) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLen) {
            throw new IllegalArgumentException(name + " exceeds max length");
        }
        return trimmed;
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) return "EXPLORE";
        String upper = mode.strip().toUpperCase(Locale.ROOT);
        if (!VALID_MODES.contains(upper)) {
            throw new IllegalArgumentException("Invalid conversation mode: " + mode);
        }
        return upper;
    }

    private static String generateId(String prefix) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return prefix + new String(out);
    }
}
