package com.evolution.backend.query.repair;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.analysis.CanonicalRecordReader;
import com.evolution.backend.query.llm.LlmTransport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Single-owner SQLite persistence store for repair verification jobs, live SSE event logs,
 * closed-loop repair round telemetry, behavioral differential witnesses, and in-app sandbox
 * runner profiles ({@code <store>/repairs/jobs.db}).
 *
 * <p>Operational & security invariants:
 * <ul>
 *   <li>WAL mode, {@code busy_timeout=5000}, {@code synchronous=NORMAL}, {@code foreign_keys=ON}.</li>
 *   <li>Versioned {@code schema_meta} ({@code schema_version = '1'}).</li>
 *   <li>Strict per-tenant {@code owner_user_id} isolation ({@code 403} cross-tenant denial).</li>
 *   <li>Every persisted message, error, report, witness excerpt, and event payload is strictly
 *       secret-redacted before SQLite insertion (zero raw secrets on disk).</li>
 *   <li>Crash recovery on startup atomically transitions previously in-flight jobs to
 *       {@code INTERRUPTED_ON_RESTART} while preserving all prior events and history.</li>
 * </ul>
 */
public final class RepairJobStore implements AutoCloseable {
    public static final String SCHEMA_VERSION = "1";
    public static final int MAX_REPORT_JSON_BYTES = 2 * 1024 * 1024;
    private static final GraphJson.Limits REPORT_LIMITS = new GraphJson.Limits(MAX_REPORT_JSON_BYTES + 4096, 48, 250_000);

    public record PersistedSandboxProfile(
            String profileId,
            String isolationMode,
            String buildTool,
            String imageDigest,
            Map<String, Object> config,
            Optional<Long> verifiedAtMs,
            long updatedAtMs) {
        public PersistedSandboxProfile {
            config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
            verifiedAtMs = verifiedAtMs == null ? Optional.empty() : verifiedAtMs;
        }
    }

    public record PersistedJobRow(
            UUID jobId,
            String ownerUserId,
            ContentDigest proposalId,
            Optional<ContentDigest> finalProposalId,
            String baselineGraphDigest,
            Optional<String> candidateGraphDigest,
            Optional<String> baselineReportDigest,
            Optional<String> candidateReportDigest,
            RepairVerificationJobs.Stage stage,
            boolean terminal,
            Optional<String> verdict,
            Optional<String> trustLabel,
            Optional<String> behavioralVerdict,
            Optional<String> springPreservationStatus,
            List<RepairVerificationReport.RoundSummary> rounds,
            Optional<RepairVerificationReport.Report> report,
            Optional<String> errorCode,
            Optional<String> errorMessage,
            Instant createdAt,
            Instant updatedAt,
            List<RepairVerificationJobs.JobEvent> events) {
        public PersistedJobRow {
            rounds = rounds == null ? List.of() : List.copyOf(rounds);
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    private final Path dbFile;
    private final Connection connection;
    private final int recoveredOnStartup;

    public RepairJobStore(Path dbFile) {
        this.dbFile = Objects.requireNonNull(dbFile, "dbFile").toAbsolutePath().normalize();
        try {
            if (this.dbFile.getParent() != null) {
                Files.createDirectories(this.dbFile.getParent());
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbFile);
            initializeSchema();
            this.recoveredOnStartup = recoverInFlightJobs();
        } catch (IOException | SQLException err) {
            throw new IllegalStateException("Failed to initialize repair job store at " + this.dbFile, err);
        }
    }

    public Path dbFile() {
        return dbFile;
    }

    public int recoveredOnStartupCount() {
        return recoveredOnStartup;
    }

    private void initializeSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA temp_store=MEMORY");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS schema_meta (
                        key   TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS repair_jobs (
                        job_id                     TEXT PRIMARY KEY,
                        owner_user_id              TEXT NOT NULL,
                        proposal_digest            TEXT NOT NULL,
                        final_proposal_digest      TEXT,
                        baseline_graph_digest      TEXT NOT NULL DEFAULT '',
                        candidate_graph_digest     TEXT,
                        baseline_report_digest     TEXT,
                        candidate_report_digest    TEXT,
                        stage                      TEXT NOT NULL,
                        terminal                   INTEGER NOT NULL DEFAULT 0,
                        verdict                    TEXT,
                        trust_label                TEXT,
                        behavioral_verdict         TEXT,
                        spring_preservation_status TEXT,
                        rounds_json                TEXT NOT NULL DEFAULT '[]',
                        report_digest              TEXT,
                        report_json                TEXT,
                        error_code                 TEXT,
                        error_message              TEXT,
                        created_at_ms              INTEGER NOT NULL,
                        updated_at_ms              INTEGER NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_rjobs_owner ON repair_jobs(owner_user_id, created_at_ms DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_rjobs_proposal ON repair_jobs(proposal_digest)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS repair_job_events (
                        job_id        TEXT NOT NULL REFERENCES repair_jobs(job_id) ON DELETE CASCADE,
                        seq           INTEGER NOT NULL,
                        stage         TEXT NOT NULL,
                        message       TEXT NOT NULL,
                        detail_json   TEXT NOT NULL DEFAULT '{}',
                        created_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(job_id, seq)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS repair_behavioral_witnesses (
                        witness_id            TEXT NOT NULL,
                        job_id                TEXT NOT NULL REFERENCES repair_jobs(job_id) ON DELETE CASCADE,
                        language              TEXT NOT NULL,
                        test_path             TEXT NOT NULL,
                        test_sha256           TEXT NOT NULL,
                        base_exit             INTEGER NOT NULL,
                        cand_exit             INTEGER NOT NULL,
                        union_coverage_pct    REAL,
                        intent_classification TEXT NOT NULL,
                        output_sha256         TEXT NOT NULL,
                        output_excerpt        TEXT NOT NULL DEFAULT '',
                        created_at_ms         INTEGER NOT NULL,
                        PRIMARY KEY(job_id, witness_id)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_rwit_job ON repair_behavioral_witnesses(job_id)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS repair_sandbox_profiles (
                        profile_id     TEXT PRIMARY KEY,
                        isolation_mode TEXT NOT NULL,
                        build_tool     TEXT NOT NULL,
                        image_digest   TEXT NOT NULL,
                        config_json    TEXT NOT NULL,
                        verified_at_ms INTEGER,
                        updated_at_ms  INTEGER NOT NULL
                    )
                    """);
        }
        migrateBehavioralWitnessesPkIfNeeded();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO schema_meta(key, value) VALUES('schema_version', ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, SCHEMA_VERSION);
            ps.executeUpdate();
        }
    }

    /**
     * Atomically migrates legacy databases where {@code repair_behavioral_witnesses} had a single-column
     * {@code witness_id PRIMARY KEY} to the tenant-safe composite {@code PRIMARY KEY(job_id, witness_id)}
     * inside an explicit SQLite {@code BEGIN IMMEDIATE ... COMMIT} transaction.
     */
    private void migrateBehavioralWitnessesPkIfNeeded() throws SQLException {
        int pkCols = 0;
        try (PreparedStatement ps = connection.prepareStatement("PRAGMA table_info(repair_behavioral_witnesses)");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (rs.getInt("pk") > 0) pkCols++;
            }
        }
        if (pkCols != 1) {
            return;
        }
        boolean txnStarted = false;
        try (Statement st = connection.createStatement()) {
            st.execute("BEGIN IMMEDIATE");
            txnStarted = true;
            st.execute("""
                    CREATE TABLE repair_behavioral_witnesses_migrated (
                        witness_id            TEXT NOT NULL,
                        job_id                TEXT NOT NULL REFERENCES repair_jobs(job_id) ON DELETE CASCADE,
                        language              TEXT NOT NULL,
                        test_path             TEXT NOT NULL,
                        test_sha256           TEXT NOT NULL,
                        base_exit             INTEGER NOT NULL,
                        cand_exit             INTEGER NOT NULL,
                        union_coverage_pct    REAL,
                        intent_classification TEXT NOT NULL,
                        output_sha256         TEXT NOT NULL,
                        output_excerpt        TEXT NOT NULL DEFAULT '',
                        created_at_ms         INTEGER NOT NULL,
                        PRIMARY KEY(job_id, witness_id)
                    )
                    """);
            st.execute("""
                    INSERT INTO repair_behavioral_witnesses_migrated(
                        witness_id, job_id, language, test_path, test_sha256, base_exit, cand_exit,
                        union_coverage_pct, intent_classification, output_sha256, output_excerpt, created_at_ms)
                    SELECT witness_id, job_id, language, test_path, test_sha256, base_exit, cand_exit,
                           union_coverage_pct, intent_classification, output_sha256, output_excerpt, created_at_ms
                    FROM repair_behavioral_witnesses
                    """);
            st.execute("DROP TABLE repair_behavioral_witnesses");
            st.execute("ALTER TABLE repair_behavioral_witnesses_migrated RENAME TO repair_behavioral_witnesses");
            st.execute("CREATE INDEX IF NOT EXISTS idx_rwit_job ON repair_behavioral_witnesses(job_id)");
            st.execute("COMMIT");
            txnStarted = false;
        } catch (SQLException err) {
            if (txnStarted) {
                try (Statement rb = connection.createStatement()) {
                    rb.execute("ROLLBACK");
                } catch (SQLException ignored) {}
            }
            throw err;
        }
    }

    /**
     * Safe crash recovery on startup: atomically transitions all non-terminal jobs from a previous
     * server lifecycle to {@code INTERRUPTED_ON_RESTART} without deleting their event history.
     */
    private synchronized int recoverInFlightJobs() throws SQLException {
        long now = System.currentTimeMillis();
        var inFlight = new ArrayList<String>();
        boolean prevAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT job_id FROM repair_jobs WHERE terminal = 0");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    inFlight.add(rs.getString(1));
                }
            }
            if (!inFlight.isEmpty()) {
                try (PreparedStatement upd = connection.prepareStatement(
                        "UPDATE repair_jobs SET stage = 'INTERRUPTED_ON_RESTART', terminal = 1, "
                                + "error_code = 'INTERRUPTED_ON_RESTART', "
                                + "error_message = 'Server restarted while repair verification job was in flight.', "
                                + "updated_at_ms = ? WHERE terminal = 0")) {
                    upd.setLong(1, now);
                    upd.executeUpdate();
                }
                for (String jobId : inFlight) {
                    long nextSeq = 1L;
                    try (PreparedStatement seqPs = connection.prepareStatement(
                            "SELECT COALESCE(MAX(seq), 0) + 1 FROM repair_job_events WHERE job_id = ?")) {
                        seqPs.setString(1, jobId);
                        try (ResultSet srs = seqPs.executeQuery()) {
                            if (srs.next()) nextSeq = srs.getLong(1);
                        }
                    }
                    try (PreparedStatement evPs = connection.prepareStatement(
                            "INSERT OR IGNORE INTO repair_job_events(job_id, seq, stage, message, detail_json, created_at_ms) "
                                    + "VALUES(?, ?, 'INTERRUPTED_ON_RESTART', ?, ?, ?)")) {
                        evPs.setString(1, jobId);
                        evPs.setLong(2, nextSeq);
                        evPs.setString(3, "Server restarted while repair verification job was in flight.");
                        evPs.setString(4, CanonicalJson.write(Map.of("errorCode", "INTERRUPTED_ON_RESTART")));
                        evPs.setLong(5, now);
                        evPs.executeUpdate();
                    }
                }
            }
            connection.commit();
            return inFlight.size();
        } catch (SQLException err) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            throw err;
        } finally {
            connection.setAutoCommit(prevAutoCommit);
        }
    }

    public synchronized void createJob(
            UUID jobId,
            String ownerUserId,
            ContentDigest proposalId,
            String baselineGraphDigest,
            Optional<String> baselineReportDigest,
            Instant createdAt) {
        String safeOwner = normalizeOwner(ownerUserId);
        long ts = createdAt.toEpochMilli();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO repair_jobs(job_id, owner_user_id, proposal_digest, final_proposal_digest, "
                        + "baseline_graph_digest, baseline_report_digest, stage, terminal, rounds_json, "
                        + "created_at_ms, updated_at_ms) "
                        + "VALUES(?, ?, ?, ?, ?, ?, 'QUEUED', 0, '[]', ?, ?)")) {
            ps.setString(1, jobId.toString());
            ps.setString(2, safeOwner);
            ps.setString(3, proposalId.value());
            ps.setString(4, proposalId.value());
            ps.setString(5, baselineGraphDigest == null ? "" : baselineGraphDigest);
            if (baselineReportDigest != null && baselineReportDigest.isPresent()) {
                ps.setString(6, baselineReportDigest.orElseThrow());
            } else {
                ps.setNull(6, Types.VARCHAR);
            }
            ps.setLong(7, ts);
            ps.setLong(8, ts);
            ps.executeUpdate();
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to create repair job row", err);
        }
    }

    public synchronized void appendEvent(UUID jobId, RepairVerificationJobs.JobEvent event) {
        boolean terminal = isTerminalStage(event.stage());
        String safeMessage = redact(event.message());
        String safeDetailJson = CanonicalJson.write(redactJsonMap(event.detail(), 0));
        long ts = event.timestamp().toEpochMilli();
        try {
            boolean prevAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ev = connection.prepareStatement(
                        "INSERT OR REPLACE INTO repair_job_events(job_id, seq, stage, message, detail_json, created_at_ms) "
                                + "VALUES(?, ?, ?, ?, ?, ?)")) {
                    ev.setString(1, jobId.toString());
                    ev.setLong(2, event.seq());
                    ev.setString(3, event.stage().name());
                    ev.setString(4, safeMessage);
                    ev.setString(5, safeDetailJson);
                    ev.setLong(6, ts);
                    ev.executeUpdate();
                }
                try (PreparedStatement upd = connection.prepareStatement(
                        "UPDATE repair_jobs SET stage = CASE WHEN terminal = 1 THEN stage ELSE ? END, "
                                + "terminal = CASE WHEN terminal = 1 THEN 1 ELSE ? END, "
                                + "updated_at_ms = CASE WHEN terminal = 1 THEN updated_at_ms ELSE ? END "
                                + "WHERE job_id = ?")) {
                    upd.setString(1, event.stage().name());
                    upd.setInt(2, terminal ? 1 : 0);
                    upd.setLong(3, ts);
                    upd.setString(4, jobId.toString());
                    upd.executeUpdate();
                }
                connection.commit();
            } catch (SQLException err) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw err;
            } finally {
                connection.setAutoCommit(prevAutoCommit);
            }
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to append repair job event", err);
        }
    }

    public synchronized void updateRounds(UUID jobId, ContentDigest latestProposalId,
                                          List<RepairVerificationReport.RoundSummary> rounds) {
        String safeRounds = encodeBoundedRoundsJson(rounds == null ? List.of() : rounds);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE repair_jobs SET final_proposal_digest = ?, rounds_json = ?, updated_at_ms = ? WHERE job_id = ?")) {
            ps.setString(1, latestProposalId != null ? latestProposalId.value() : null);
            ps.setString(2, safeRounds);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, jobId.toString());
            ps.executeUpdate();
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to update repair job rounds", err);
        }
    }

    public synchronized void completeJob(UUID jobId, RepairVerificationReport.Report report) {
        Objects.requireNonNull(report);
        long now = System.currentTimeMillis();
        // Redact all string fields recursively on the Java structure BEFORE CanonicalJson serialization
        // so secret redaction can never break JSON escape sequences.
        var sanitizedReport = redactReport(report);
        String reportJson = encodeBoundedReportJson(sanitizedReport);
        String roundsJson = encodeBoundedRoundsJson(sanitizedReport.rounds());
        String behavioralVerdict = report.behavioralDiff().map(b -> b.verdict().name()).orElse(null);
        String springStatus = report.springPreservation().map(s -> s.status().name()).orElse(null);
        String candGraph = report.provenance().candidateGraph().map(ContentDigest::value).orElse(null);

        try {
            boolean prevAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE repair_jobs SET final_proposal_digest = ?, candidate_graph_digest = ?, "
                                + "stage = 'REPORT_READY', terminal = 1, verdict = ?, trust_label = ?, "
                                + "behavioral_verdict = ?, spring_preservation_status = ?, rounds_json = ?, "
                                + "report_digest = ?, report_json = ?, updated_at_ms = ? WHERE job_id = ? AND terminal = 0")) {
                    ps.setString(1, report.provenance().proposalIdentity().value());
                    ps.setString(2, candGraph);
                    ps.setString(3, report.verdict().name());
                    ps.setString(4, report.trustLabel().name());
                    ps.setString(5, behavioralVerdict);
                    ps.setString(6, springStatus);
                    ps.setString(7, roundsJson);
                    ps.setString(8, report.reportIdentity().value());
                    ps.setString(9, reportJson);
                    ps.setLong(10, now);
                    ps.setString(11, jobId.toString());
                    updated = ps.executeUpdate();
                }
                if (updated > 0 && report.behavioralDiff().isPresent()) {
                    for (var w : report.behavioralDiff().orElseThrow().witnesses()) {
                        try (PreparedStatement wps = connection.prepareStatement(
                                "INSERT OR REPLACE INTO repair_behavioral_witnesses("
                                        + "witness_id, job_id, language, test_path, test_sha256, base_exit, cand_exit, "
                                        + "union_coverage_pct, intent_classification, output_sha256, output_excerpt, created_at_ms) "
                                        + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                            wps.setString(1, w.witnessId());
                            wps.setString(2, jobId.toString());
                            wps.setString(3, w.language());
                            wps.setString(4, w.testPath());
                            wps.setString(5, w.testSha256().value());
                            wps.setInt(6, w.baseExit());
                            wps.setInt(7, w.candExit());
                            if (w.unionCoveragePct().isPresent()) {
                                wps.setDouble(8, w.unionCoveragePct().orElseThrow().doubleValue());
                            } else {
                                wps.setNull(8, Types.REAL);
                            }
                            wps.setString(9, w.intentClassification().name());
                            wps.setString(10, w.outputSha256().value());
                            wps.setString(11, redact(w.outputExcerpt()));
                            wps.setLong(12, now);
                            wps.executeUpdate();
                        }
                    }
                }
                connection.commit();
            } catch (SQLException err) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw err;
            } finally {
                connection.setAutoCommit(prevAutoCommit);
            }
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to persist completed repair verification report", err);
        }
    }

    public synchronized void failJob(UUID jobId, RepairVerificationJobs.Stage stage, String errorCode, String errorMessage) {
        String safeMsg = redact(errorMessage == null ? "" : errorMessage);
        String safeCode = (errorCode == null || errorCode.isBlank()) ? stage.name() : errorCode.strip();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE repair_jobs SET stage = ?, terminal = 1, error_code = ?, error_message = ?, updated_at_ms = ? "
                        + "WHERE job_id = ? AND terminal = 0")) {
            ps.setString(1, stage.name());
            ps.setString(2, safeCode);
            ps.setString(3, safeMsg);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, jobId.toString());
            ps.executeUpdate();
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to update failed repair job", err);
        }
    }

    public synchronized Optional<PersistedJobRow> findJob(String ownerUserId, UUID jobId) {
        String caller = normalizeOwner(ownerUserId);
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT job_id, owner_user_id, proposal_digest, final_proposal_digest, baseline_graph_digest, "
                        + "candidate_graph_digest, baseline_report_digest, candidate_report_digest, stage, terminal, "
                        + "verdict, trust_label, behavioral_verdict, spring_preservation_status, rounds_json, "
                        + "report_json, error_code, error_message, created_at_ms, updated_at_ms "
                        + "FROM repair_jobs WHERE job_id = ?")) {
            ps.setString(1, jobId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String rowOwner = normalizeOwner(rs.getString("owner_user_id"));
                if (!rowOwner.equals(caller)) {
                    throw new SafePatchApplier.AccessDeniedException("Repair verification job belongs to another user.");
                }
                return Optional.of(readJobRow(rs));
            }
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to read repair job", err);
        }
    }

    public synchronized List<PersistedJobRow> listJobs(String ownerUserId, int limit) {
        String caller = normalizeOwner(ownerUserId);
        int bounded = Math.max(1, Math.min(200, limit));
        var out = new ArrayList<PersistedJobRow>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT job_id, owner_user_id, proposal_digest, final_proposal_digest, baseline_graph_digest, "
                        + "candidate_graph_digest, baseline_report_digest, candidate_report_digest, stage, terminal, "
                        + "verdict, trust_label, behavioral_verdict, spring_preservation_status, rounds_json, "
                        + "report_json, error_code, error_message, created_at_ms, updated_at_ms "
                        + "FROM repair_jobs WHERE owner_user_id = ? ORDER BY created_at_ms DESC LIMIT ?")) {
            ps.setString(1, caller);
            ps.setInt(2, bounded);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readJobRow(rs));
                }
            }
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to list repair jobs", err);
        }
        return List.copyOf(out);
    }

    public synchronized List<BehavioralDiffService.BehavioralWitness> listWitnesses(String ownerUserId, UUID jobId) {
        if (findJob(ownerUserId, jobId).isEmpty()) return List.of();
        var out = new ArrayList<BehavioralDiffService.BehavioralWitness>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT witness_id, language, test_path, test_sha256, base_exit, cand_exit, "
                        + "union_coverage_pct, intent_classification, output_sha256, output_excerpt "
                        + "FROM repair_behavioral_witnesses WHERE job_id = ? ORDER BY created_at_ms ASC, witness_id ASC")) {
            ps.setString(1, jobId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double covVal = rs.getDouble("union_coverage_pct");
                    Optional<java.math.BigDecimal> cov = rs.wasNull()
                            ? Optional.empty()
                            : Optional.of(java.math.BigDecimal.valueOf(covVal));
                    out.add(new BehavioralDiffService.BehavioralWitness(
                            rs.getString("witness_id"),
                            rs.getString("language"),
                            rs.getString("test_path"),
                            new ContentDigest(rs.getString("test_sha256")),
                            rs.getInt("base_exit"),
                            rs.getInt("cand_exit"),
                            cov,
                            BehavioralDiffService.IntentClassification.valueOf(rs.getString("intent_classification")),
                            new ContentDigest(rs.getString("output_sha256")),
                            rs.getString("output_excerpt")));
                }
            }
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to list behavioral witnesses", err);
        }
        return List.copyOf(out);
    }

    public synchronized void saveSandboxProfile(PersistedSandboxProfile profile) {
        Objects.requireNonNull(profile);
        String safeConfig = CanonicalJson.write(redactJsonMap(profile.config(), 0));
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO repair_sandbox_profiles(profile_id, isolation_mode, build_tool, image_digest, "
                        + "config_json, verified_at_ms, updated_at_ms) VALUES(?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(profile_id) DO UPDATE SET isolation_mode = excluded.isolation_mode, "
                        + "build_tool = excluded.build_tool, image_digest = excluded.image_digest, "
                        + "config_json = excluded.config_json, verified_at_ms = excluded.verified_at_ms, "
                        + "updated_at_ms = excluded.updated_at_ms")) {
            ps.setString(1, profile.profileId());
            ps.setString(2, profile.isolationMode());
            ps.setString(3, profile.buildTool());
            ps.setString(4, profile.imageDigest());
            ps.setString(5, safeConfig);
            if (profile.verifiedAtMs().isPresent()) ps.setLong(6, profile.verifiedAtMs().orElseThrow());
            else ps.setNull(6, Types.INTEGER);
            ps.setLong(7, profile.updatedAtMs());
            ps.executeUpdate();
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to save sandbox profile", err);
        }
    }

    public synchronized Optional<PersistedSandboxProfile> loadSandboxProfile(String profileId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT profile_id, isolation_mode, build_tool, image_digest, config_json, verified_at_ms, updated_at_ms "
                        + "FROM repair_sandbox_profiles WHERE profile_id = ?")) {
            ps.setString(1, profileId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Map<String, Object> cfg = parseObjectQuietly(rs.getString("config_json"));
                long ver = rs.getLong("verified_at_ms");
                Optional<Long> verOpt = rs.wasNull() ? Optional.empty() : Optional.of(ver);
                return Optional.of(new PersistedSandboxProfile(
                        rs.getString("profile_id"),
                        rs.getString("isolation_mode"),
                        rs.getString("build_tool"),
                        rs.getString("image_digest"),
                        cfg,
                        verOpt,
                        rs.getLong("updated_at_ms")));
            }
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to load sandbox profile", err);
        }
    }

    public synchronized boolean deleteSandboxProfile(String profileId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM repair_sandbox_profiles WHERE profile_id = ?")) {
            ps.setString(1, profileId);
            return ps.executeUpdate() > 0;
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to delete sandbox profile", err);
        }
    }

    public synchronized String schemaVersion() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM schema_meta WHERE key = 'schema_version'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : "";
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to read schema version", err);
        }
    }

    private PersistedJobRow readJobRow(ResultSet rs) throws SQLException {
        UUID jobId = UUID.fromString(rs.getString("job_id"));
        String owner = normalizeOwner(rs.getString("owner_user_id"));
        ContentDigest proposalId = new ContentDigest(rs.getString("proposal_digest"));
        String finalPropRaw = rs.getString("final_proposal_digest");
        Optional<ContentDigest> finalProposal = (finalPropRaw == null || finalPropRaw.isBlank())
                ? Optional.empty() : Optional.of(new ContentDigest(finalPropRaw));
        RepairVerificationJobs.Stage stage = parseStage(rs.getString("stage"));
        boolean terminal = rs.getInt("terminal") != 0 || isTerminalStage(stage);
        Optional<RepairVerificationReport.Report> report = decodeReportQuietly(rs.getString("report_json"));
        List<RepairVerificationReport.RoundSummary> rounds = decodeRoundsQuietly(rs.getString("rounds_json"), report);
        List<RepairVerificationJobs.JobEvent> events = loadEvents(jobId);

        return new PersistedJobRow(
                jobId,
                owner,
                proposalId,
                finalProposal,
                Objects.toString(rs.getString("baseline_graph_digest"), ""),
                Optional.ofNullable(rs.getString("candidate_graph_digest")),
                Optional.ofNullable(rs.getString("baseline_report_digest")),
                Optional.ofNullable(rs.getString("candidate_report_digest")),
                stage,
                terminal,
                Optional.ofNullable(rs.getString("verdict")),
                Optional.ofNullable(rs.getString("trust_label")),
                Optional.ofNullable(rs.getString("behavioral_verdict")),
                Optional.ofNullable(rs.getString("spring_preservation_status")),
                rounds,
                report,
                Optional.ofNullable(rs.getString("error_code")),
                Optional.ofNullable(rs.getString("error_message")),
                Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                Instant.ofEpochMilli(rs.getLong("updated_at_ms")),
                events);
    }

    private List<RepairVerificationJobs.JobEvent> loadEvents(UUID jobId) throws SQLException {
        var events = new ArrayList<RepairVerificationJobs.JobEvent>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT seq, stage, message, detail_json, created_at_ms "
                        + "FROM repair_job_events WHERE job_id = ? ORDER BY seq ASC LIMIT 256")) {
            ps.setString(1, jobId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(new RepairVerificationJobs.JobEvent(
                            rs.getLong("seq"),
                            Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                            parseStage(rs.getString("stage")),
                            rs.getString("message"),
                            parseObjectQuietly(rs.getString("detail_json"))));
                }
            }
        }
        return List.copyOf(events);
    }

    private static Optional<RepairVerificationReport.Report> decodeReportQuietly(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            Object parsed = GraphJson.read(json, REPORT_LIMITS);
            return Optional.of(CanonicalRecordReader.read(parsed, RepairVerificationReport.Report.class));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static List<RepairVerificationReport.RoundSummary> decodeRoundsQuietly(
            String json, Optional<RepairVerificationReport.Report> report) {
        if (report.isPresent() && !report.orElseThrow().rounds().isEmpty()) {
            return report.orElseThrow().rounds();
        }
        if (json == null || json.isBlank() || "[]".equals(json.strip())) return List.of();
        try {
            var arr = GraphJson.array(GraphJson.read(json, new GraphJson.Limits(512 * 1024, 32, 32_768)));
            var out = new ArrayList<RepairVerificationReport.RoundSummary>(arr.size());
            for (Object item : arr) {
                out.add(CanonicalRecordReader.read(item, RepairVerificationReport.RoundSummary.class));
            }
            return List.copyOf(out);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static Map<String, Object> parseObjectQuietly(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return GraphJson.object(GraphJson.read(json, new GraphJson.Limits(65_536, 16, 4096)));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static RepairVerificationJobs.Stage parseStage(String raw) {
        if (raw == null || raw.isBlank()) return RepairVerificationJobs.Stage.FAILED;
        try {
            return RepairVerificationJobs.Stage.valueOf(raw.strip());
        } catch (IllegalArgumentException unknown) {
            return RepairVerificationJobs.Stage.FAILED;
        }
    }

    private static boolean isTerminalStage(RepairVerificationJobs.Stage stage) {
        return stage == RepairVerificationJobs.Stage.REPORT_READY
                || stage == RepairVerificationJobs.Stage.FAILED
                || stage == RepairVerificationJobs.Stage.CANCELLED
                || stage == RepairVerificationJobs.Stage.INTERRUPTED_ON_RESTART;
    }

    private static String normalizeOwner(String owner) {
        return (owner == null || owner.isBlank()) ? SafePatchApplier.DEFAULT_OWNER : owner.strip();
    }

    private static String redact(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return LlmTransport.redactSecrets(raw).text();
    }

    private static Map<String, Object> redactJsonMap(Map<?, ?> map, int depth) {
        if (map == null || map.isEmpty()) return Map.of();
        if (depth > 8) return Map.of("_truncated", true);
        var out = new LinkedHashMap<String, Object>(map.size());
        for (var e : map.entrySet()) {
            String k = e.getKey() == null ? "" : redact(String.valueOf(e.getKey()));
            out.put(k, redactJsonValue(e.getValue(), depth + 1));
        }
        return out;
    }

    private static Object redactJsonValue(Object value, int depth) {
        if (value == null) return null;
        if (depth > 8) return "[TRUNCATED]";
        if (value instanceof String s) return redact(s);
        if (value instanceof Map<?, ?> m) return redactJsonMap(m, depth);
        if (value instanceof List<?> list) {
            var out = new ArrayList<Object>(list.size());
            for (Object item : list) out.add(redactJsonValue(item, depth + 1));
            return out;
        }
        return value;
    }

    private static List<RepairVerificationReport.RoundSummary> redactRounds(
            List<RepairVerificationReport.RoundSummary> rounds) {
        if (rounds == null || rounds.isEmpty()) return List.of();
        return rounds.stream().map(r -> {
            var safeTrace = r.toolTrace().stream()
                    .map(t -> new RepairAgentTools.ToolTraceEntry(
                            t.step(),
                            redact(t.tool()),
                            redactJsonMap(t.arguments(), 0),
                            redact(t.summary()),
                            t.durationMs()))
                    .toList();
            return new RepairVerificationReport.RoundSummary(
                    r.round(), r.proposalId(), r.patchDigest(), r.syntaxPassed(),
                    r.compileExit(), r.testExit(), r.testsRun(), r.testsFailed(),
                    r.verdict(), r.behavioralVerdict(), r.springPreservationStatus(),
                    r.toolCallsCount(), safeTrace, redact(r.feedbackSummary()), r.durationMs());
        }).toList();
    }

    private static RepairVerificationReport.Report redactReport(RepairVerificationReport.Report r) {
        var sbx = r.sandbox();
        var safeFailures = sbx.failingTests().stream()
                .map(ft -> new com.evolution.backend.query.CapturedRepairExecution.TestFailure(
                        redact(ft.className()), redact(ft.methodName()), redact(ft.kind()), redact(ft.message())))
                .toList();
        var safeSandbox = new RepairVerificationReport.SandboxEvidence(
                sbx.executed(), sbx.isolationMode(), sbx.buildTool(), sbx.modules(),
                sbx.compileExit(), sbx.testExit(), sbx.testsRun(), sbx.testsFailed(), sbx.testsErrors(),
                sbx.classCount(), safeFailures, redact(sbx.logTail()),
                sbx.captureDigest(), sbx.imageDigest(), sbx.inputsDigest());
        var safeBehav = r.behavioralDiff().map(bd -> new BehavioralDiffService.BehavioralDiffReport(
                bd.verdict(), bd.executedChecksCount(),
                bd.baselineTestsRun(), bd.baselineTestsFailed(),
                bd.candidateTestsRun(), bd.candidateTestsFailed(),
                bd.proposedProbesGenerated(), bd.proposedProbesSyntaxPassed(), bd.proposedProbesExecuted(),
                bd.unionCoveragePct(),
                bd.witnesses().stream().map(w -> new BehavioralDiffService.BehavioralWitness(
                        w.witnessId(), w.language(), redact(w.testPath()), w.testSha256(),
                        w.baseExit(), w.candExit(), w.unionCoveragePct(),
                        w.intentClassification(), w.outputSha256(), redact(w.outputExcerpt()))).toList(),
                bd.lineageDigest(), redact(bd.summary())));
        var safeSpring = r.springPreservation().map(sp -> new SpringPreservationChecker.SpringPreservationReport(
                sp.status(), sp.baselineFactCount(), sp.candidateFactCount(), sp.preservedFactCount(),
                sp.baselineWorldCount(), sp.candidateWorldCount(), sp.preservedWorldCount(),
                sp.drifts().stream().map(d -> new SpringPreservationChecker.SpringDrift(
                        redact(d.factKey()), d.factKind(), redact(d.symbolLabel()),
                        d.beforeClassification(), d.afterClassification(), d.severity(), redact(d.detail()))).toList(),
                sp.preservationDigest(), redact(sp.summary())));
        var safeChecks = r.checks().stream()
                .map(c -> new com.evolution.backend.query.CapturedRepairVerification.Check(
                        c.id(), c.status(), redact(c.reason()), c.evidence()))
                .toList();
        var vd = r.violationDelta();
        var safeViolations = new RepairVerificationReport.ViolationDelta(
                redactFindings(vd.resolved()),
                redactFindings(vd.persisted()),
                redactFindings(vd.introduced()),
                vd.unknownBefore(),
                vd.unknownAfter());
        return new RepairVerificationReport.Report(
                r.version(), r.reportIdentity(), r.verdict(), r.trustLabel(),
                redact(r.executiveSummary()), r.scoreDelta(), r.structuralDelta(),
                safeViolations, r.capabilityGapDelta(), safeSandbox,
                safeChecks, r.provenance(),
                redact(r.patch()), redact(r.markdownReport()),
                safeBehav, safeSpring, redactRounds(r.rounds()));
    }

    private static List<RepairVerificationReport.FindingItem> redactFindings(
            List<RepairVerificationReport.FindingItem> items) {
        if (items == null || items.isEmpty()) return List.of();
        return items.stream().map(f -> new RepairVerificationReport.FindingItem(
                redact(f.ruleId()),
                redact(f.ruleVersion()),
                redact(f.kind()),
                redact(f.classification()),
                f.path().stream().map(RepairJobStore::redact).toList())).toList();
    }

    private static String encodeBoundedRoundsJson(List<RepairVerificationReport.RoundSummary> rounds) {
        final int maxRoundsBytes = 500 * 1024;
        var sanitized = redactRounds(rounds);
        String json = CanonicalJson.write(sanitized);
        if (json.getBytes(StandardCharsets.UTF_8).length <= maxRoundsBytes) return json;
        var strippedTrace = sanitized.stream()
                .map(r -> new RepairVerificationReport.RoundSummary(
                        r.round(), r.proposalId(), r.patchDigest(), r.syntaxPassed(),
                        r.compileExit(), r.testExit(), r.testsRun(), r.testsFailed(),
                        r.verdict(), r.behavioralVerdict(), r.springPreservationStatus(),
                        r.toolCallsCount(), List.of(), truncateStr(r.feedbackSummary(), 512), r.durationMs()))
                .toList();
        json = CanonicalJson.write(strippedTrace);
        if (json.getBytes(StandardCharsets.UTF_8).length <= maxRoundsBytes) return json;
        var minimal = strippedTrace.subList(0, Math.min(16, strippedTrace.size()));
        return CanonicalJson.write(minimal);
    }

    private static String encodeBoundedReportJson(RepairVerificationReport.Report report) {
        String json = CanonicalJson.write(report);
        if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_REPORT_JSON_BYTES) return json;

        // Pass 1: trim large free-text fields (patch, markdown, sandbox logTail)
        var pass1 = withBoundedFields(report,
                truncateStr(report.patch(), 131_072),
                truncateStr(report.markdownReport(), 262_144),
                capSandbox(report.sandbox(), 16_384, 50, 400),
                report.violationDelta(), report.checks(), report.provenance(),
                report.behavioralDiff(), report.springPreservation(), report.rounds());
        json = CanonicalJson.write(pass1);
        if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_REPORT_JSON_BYTES) return json;

        // Pass 2: strip tool traces, trim witnesses & drifts, shrink patch/markdown further
        var compactRounds = report.rounds().stream()
                .map(r -> new RepairVerificationReport.RoundSummary(
                        r.round(), r.proposalId(), r.patchDigest(), r.syntaxPassed(),
                        r.compileExit(), r.testExit(), r.testsRun(), r.testsFailed(),
                        r.verdict(), r.behavioralVerdict(), r.springPreservationStatus(),
                        r.toolCallsCount(), List.of(), truncateStr(r.feedbackSummary(), 512), r.durationMs()))
                .toList();
        var pass2 = withBoundedFields(report,
                truncateStr(report.patch(), 32_768),
                truncateStr(report.markdownReport(), 65_536),
                capSandbox(report.sandbox(), 4096, 30, 250),
                report.violationDelta(), report.checks(), report.provenance(),
                capBehavioral(report.behavioralDiff(), 20, 1024),
                capSpring(report.springPreservation(), 100),
                compactRounds);
        json = CanonicalJson.write(pass2);
        if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_REPORT_JSON_BYTES) return json;

        // Pass 3: cap violation arrays, checks, provenance edits, omit full patch/markdown body
        var vd = report.violationDelta();
        var cappedVd = new RepairVerificationReport.ViolationDelta(
                capList(vd.resolved(), 200), capList(vd.persisted(), 200), capList(vd.introduced(), 200),
                vd.unknownBefore(), vd.unknownAfter());
        var prov = report.provenance();
        var cappedProv = new RepairVerificationReport.Provenance(
                prov.proposalIdentity(), prov.patchDigest(), prov.baselineGraph(), prov.candidateGraph(),
                prov.planIdentity(), prov.strategy(), prov.recipeId(), capList(prov.edits(), 200));
        var pass3 = withBoundedFields(report,
                "[omitted: exceeded 2 MiB persisted report cap]",
                truncateStr(report.executiveSummary(), 4096),
                capSandbox(report.sandbox(), 1024, 15, 200),
                cappedVd, capList(report.checks(), 100), cappedProv,
                capBehavioral(report.behavioralDiff(), 10, 512),
                capSpring(report.springPreservation(), 50),
                capList(compactRounds, 10));
        json = CanonicalJson.write(pass3);
        if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_REPORT_JSON_BYTES) return json;

        // Pass 4 (hard guarantee <= 2 MiB): minimal lossless core verdict/trust/provenance envelope
        var emptyVd = new RepairVerificationReport.ViolationDelta(
                List.of(), List.of(), List.of(), vd.unknownBefore(), vd.unknownAfter());
        var minimalProv = new RepairVerificationReport.Provenance(
                prov.proposalIdentity(), prov.patchDigest(), prov.baselineGraph(), prov.candidateGraph(),
                prov.planIdentity(), prov.strategy(), prov.recipeId(), List.of());
        var minimalGaps = new RepairVerificationReport.CapabilityGapDelta(
                report.capabilityGapDelta().beforeCount(), report.capabilityGapDelta().afterCount(),
                report.capabilityGapDelta().delta(), Map.of(), Map.of());
        var sd = report.scoreDelta();
        var minimalScore = new RepairVerificationReport.ScoreDelta(
                sd.beforeScore(), sd.afterScore(), sd.delta(), sd.beforeLowerBound(), sd.afterLowerBound(),
                sd.beforeBand(), sd.afterBand(), sd.beforeConfidence(), sd.afterConfidence(),
                sd.beforeConfidenceTier(), sd.afterConfidenceTier(), List.of());
        var minimalChecks = capList(report.checks(), 20).stream()
                .map(c -> new com.evolution.backend.query.CapturedRepairVerification.Check(
                        truncateStr(c.id(), 128), c.status(), truncateStr(c.reason(), 256), c.evidence()))
                .toList();
        var pass4 = new RepairVerificationReport.Report(
                report.version(), report.reportIdentity(), report.verdict(), report.trustLabel(),
                truncateStr(report.executiveSummary(), 2048),
                minimalScore, Optional.empty(),
                emptyVd, minimalGaps,
                capSandbox(report.sandbox(), 0, 0, 0),
                minimalChecks, minimalProv,
                "", truncateStr(report.executiveSummary(), 2048),
                capBehavioral(report.behavioralDiff(), 0, 0),
                capSpring(report.springPreservation(), 0),
                List.of());
        json = CanonicalJson.write(pass4);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_JSON_BYTES) {
            throw new IllegalStateException("Persisted repair report exceeded 2 MiB budget even after compaction");
        }
        return json;
    }

    private static RepairVerificationReport.Report withBoundedFields(
            RepairVerificationReport.Report base,
            String patch,
            String markdown,
            RepairVerificationReport.SandboxEvidence sandbox,
            RepairVerificationReport.ViolationDelta vd,
            List<com.evolution.backend.query.CapturedRepairVerification.Check> checks,
            RepairVerificationReport.Provenance prov,
            Optional<BehavioralDiffService.BehavioralDiffReport> behav,
            Optional<SpringPreservationChecker.SpringPreservationReport> spring,
            List<RepairVerificationReport.RoundSummary> rounds) {
        return new RepairVerificationReport.Report(
                base.version(), base.reportIdentity(), base.verdict(), base.trustLabel(),
                base.executiveSummary(), base.scoreDelta(), base.structuralDelta(),
                vd, base.capabilityGapDelta(), sandbox, checks, prov, patch, markdown, behav, spring, rounds);
    }

    private static RepairVerificationReport.SandboxEvidence capSandbox(
            RepairVerificationReport.SandboxEvidence sbx, int maxLogChars, int maxFailures, int maxMsgChars) {
        var failures = maxFailures <= 0 ? List.<com.evolution.backend.query.CapturedRepairExecution.TestFailure>of()
                : sbx.failingTests().stream().limit(maxFailures)
                .map(ft -> new com.evolution.backend.query.CapturedRepairExecution.TestFailure(
                        ft.className(), ft.methodName(), ft.kind(), truncateStr(ft.message(), maxMsgChars)))
                .toList();
        return new RepairVerificationReport.SandboxEvidence(
                sbx.executed(), sbx.isolationMode(), sbx.buildTool(), sbx.modules(),
                sbx.compileExit(), sbx.testExit(), sbx.testsRun(), sbx.testsFailed(), sbx.testsErrors(),
                sbx.classCount(), failures, maxLogChars <= 0 ? "" : truncateStr(sbx.logTail(), maxLogChars),
                sbx.captureDigest(), sbx.imageDigest(), sbx.inputsDigest());
    }

    private static Optional<BehavioralDiffService.BehavioralDiffReport> capBehavioral(
            Optional<BehavioralDiffService.BehavioralDiffReport> opt, int maxWitnesses, int maxExcerptChars) {
        return opt.map(bd -> {
            var witnesses = maxWitnesses <= 0 ? List.<BehavioralDiffService.BehavioralWitness>of()
                    : bd.witnesses().stream().limit(maxWitnesses)
                    .map(w -> new BehavioralDiffService.BehavioralWitness(
                            w.witnessId(), w.language(), w.testPath(), w.testSha256(),
                            w.baseExit(), w.candExit(), w.unionCoveragePct(),
                            w.intentClassification(), w.outputSha256(),
                            maxExcerptChars <= 0 ? "" : truncateStr(w.outputExcerpt(), maxExcerptChars)))
                    .toList();
            return new BehavioralDiffService.BehavioralDiffReport(
                    bd.verdict(), bd.executedChecksCount(),
                    bd.baselineTestsRun(), bd.baselineTestsFailed(),
                    bd.candidateTestsRun(), bd.candidateTestsFailed(),
                    bd.proposedProbesGenerated(), bd.proposedProbesSyntaxPassed(), bd.proposedProbesExecuted(),
                    bd.unionCoveragePct(), witnesses, bd.lineageDigest(), truncateStr(bd.summary(), 1024));
        });
    }

    private static Optional<SpringPreservationChecker.SpringPreservationReport> capSpring(
            Optional<SpringPreservationChecker.SpringPreservationReport> opt, int maxDrifts) {
        return opt.map(sp -> new SpringPreservationChecker.SpringPreservationReport(
                sp.status(), sp.baselineFactCount(), sp.candidateFactCount(), sp.preservedFactCount(),
                sp.baselineWorldCount(), sp.candidateWorldCount(), sp.preservedWorldCount(),
                maxDrifts <= 0 ? List.of() : capList(sp.drifts(), maxDrifts),
                sp.preservationDigest(), truncateStr(sp.summary(), 1024)));
    }

    private static <T> List<T> capList(List<T> items, int limit) {
        if (items == null || items.isEmpty() || limit <= 0) return List.of();
        return items.size() <= limit ? items : items.subList(0, limit);
    }

    private static String truncateStr(String s, int maxChars) {
        if (s == null || s.isEmpty() || maxChars <= 0) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "\n... [truncated]";
    }

    @Override
    public synchronized void close() {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }
}
