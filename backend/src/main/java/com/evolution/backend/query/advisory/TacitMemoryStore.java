package com.evolution.backend.query.advisory;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.llm.LlmTransport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Cryptographically pinned, strictly quarantined graph-grounded tacit memory store
 * ({@value #SCHEMA}, {@value #TRUST_TIER}) implementing PRAXIS (ICSE '27) Eqs. 6–9
 * with SHA-256 span-pinned staleness decay and anti-memory-poisoning guards.
 *
 * <p><b>Non-Negotiable Invariants (I1–I6):</b>
 * <ul>
 *   <li><b>I1 (Passive Safety):</b> {@link SourceKind#PASSIVE_GIT_ADR} extracts only from passive
 *       history/ADR artifacts with {@code --build=off}; sandbox-verified distillation is strictly
 *       isolated via {@link SourceKind#SANDBOX_DIFF_VERIFIED}.</li>
 *   <li><b>I2/I4 (Absolute Quarantine, {@code canonicalLeakage = 0}):</b> Stored in a dedicated
 *       SQLite database outside {@code PartitionedGraphStore} and {@link KnowledgeGraph#identity()}.
 *       NEVER mutates {@link KnowledgeGraph}, Leiden communities, Policy evaluations, or Score v2.
 *       All graph propagation, Noisy-OR merging, and output lists are deterministically sorted.</li>
 *   <li><b>Secret Quarantine ({@code llm-redact:v2}):</b> Every {@code trigger}, {@code content},
 *       and {@link EvidenceSpan#snippet()} passes {@link LlmTransport#redactDocumentForPath(String, String)}
 *       and {@link LlmTransport#neutralizeInjectionSentinels(String)} BEFORE writing to SQLite.</li>
 *   <li><b>Anti-Memory-Poisoning Guard:</b> Unverified free-form LLM hypotheses are rejected outright;
 *       only {@link SourceKind#PASSIVE_GIT_ADR} (strictly capped at initial confidence
 *       {@code c_0 <= 0.65}, below injection threshold {@code theta = 0.70}) and
 *       {@link SourceKind#SANDBOX_DIFF_VERIFIED} are accepted.</li>
 *   <li><b>SHA-256 Span-Pinned Staleness Decay:</b> Every evidence span pins
 *       {@code path + startLine:endLine + fileSha256}. When {@link #reconcileWithCurrentFileHashes(Map)}
 *       detects that any anchored file's SHA-256 changed, confidence decays
 *       {@code c <- 0.5 * c} and {@code staleEvidence = true} until re-verified.</li>
 *   <li><b>Online Confidence Evolution ({@code beta = 0.8}):</b> Locked exclusively to verified
 *       sandbox outcomes ({@code RESOLVED -> 1 - 0.8*(1 - c)}, {@code PERSISTED/fail -> 0.8 * c}).</li>
 * </ul>
 */
public final class TacitMemoryStore implements AutoCloseable {
    public static final String SCHEMA = "advisory.tacit-memory:v1";
    public static final String SCHEMA_VERSION = "1";
    /** Quarantined tacit-memory epistemic trust label (maps canonically to {@link TrustTier#ADVISORY_UNVERIFIED}). */
    public static final String TRUST_TIER = "ADVISORY_TACIT";
    public static final TrustTier CANONICAL_TRUST_TIER = TrustTier.ADVISORY_UNVERIFIED;
    public static final String HEADER_BANNER = "[ADVISORY_TACIT — UNVERIFIED]";

    /** Maximum allowed initial confidence for passive Git/ADR memories (below {@link #INJECTION_THRESHOLD}). */
    public static final double PASSIVE_MAX_INITIAL_CONFIDENCE = 0.65;
    /** PRAXIS Eqs. 8–9 proactive injection threshold ({@code theta = 0.70}). */
    public static final double INJECTION_THRESHOLD = 0.70;
    /** SHA-256 span-pinned staleness decay factor when an anchor file hash changes. */
    public static final double STALENESS_DECAY_FACTOR = 0.50;
    /** PRAXIS online confidence evolution factor ({@code beta = 0.80}). */
    public static final double EVOLUTION_BETA = 0.80;
    /** Maximum bidirectional SKG propagation hop radius (PRAXIS Sec. III.E). */
    public static final int MAX_PROPAGATION_HOPS = 4;

    private static final double EPSILON = 1e-12;
    private static final GraphJson.Limits JSON_LIMITS = new GraphJson.Limits(2 * 1024 * 1024, 32, 65_536);

    /**
     * Canonical SKG edge kinds eligible for bidirectional {@code <= 4}-hop tacit memory propagation:
     * PRAXIS canonical edge kinds ({@code CALLS}, {@code INJECTS}, {@code IMPLEMENTS},
     * {@code INVOKES_RPC}, {@code EVENT_FLOW}, {@code ADVISES_METHOD}) and their canonical
     * {@link KnowledgeGraph} equivalents. Structural containment edges (e.g. {@code graph.contains})
     * are deliberately excluded.
     */
    public static final Set<String> PROPAGATION_EDGE_KINDS = Collections.unmodifiableSet(new TreeSet<>(List.of(
            "ADVISES_METHOD",
            "CALLS",
            "CALLS_METHOD",
            "CALLS_SERVICE",
            "CONSUMES_FROM",
            "EVENT_FLOW",
            "IMPLEMENTS",
            "INJECTS",
            "INVOKES_RPC",
            "PROXIED_BY_ASPECT",
            "PUBLISHES_EVENT",
            "graph.calls",
            "java.calls",
            "java.implements",
            "spring.declares-injection",
            "spring.injects",
            "spring.produces-candidate",
            "spring.source-type-candidate"
    )));

    /**
     * Permitted ingestion sources. Unverified free-form LLM hypotheses are intentionally absent
     * and rejected outright to prevent SWE-Exp-style memory poisoning.
     */
    public enum SourceKind {
        /** Passively distilled from Git commits, code review history, or ADRs ({@code --build=off}, {@code c_0 <= 0.65}). */
        PASSIVE_GIT_ADR,
        /** Distilled from isolated sandbox execution diff and verified test/preservation proof. */
        SANDBOX_DIFF_VERIFIED;

        /**
         * Strictly parses a raw source kind label, rejecting any unverified LLM hypothesis or unknown source.
         *
         * @throws IllegalArgumentException if {@code raw} is not {@code PASSIVE_GIT_ADR} or {@code SANDBOX_DIFF_VERIFIED}
         */
        public static SourceKind parseAllowed(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                        "UNVERIFIED_HYPOTHESIS_REJECTED: sourceKind is required and must be PASSIVE_GIT_ADR or SANDBOX_DIFF_VERIFIED");
            }
            String normalized = raw.strip().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "PASSIVE_GIT_ADR" -> PASSIVE_GIT_ADR;
                case "SANDBOX_DIFF_VERIFIED" -> SANDBOX_DIFF_VERIFIED;
                default -> throw new IllegalArgumentException(
                        "UNVERIFIED_HYPOTHESIS_REJECTED: only PASSIVE_GIT_ADR and SANDBOX_DIFF_VERIFIED are accepted, rejected=" + normalized);
            };
        }
    }

    /**
     * Cryptographically pinned evidence span binding a tacit memory unit to an exact file path,
     * 1-based line interval, and file content SHA-256 digest.
     */
    public record EvidenceSpan(
            String path,
            int startLine,
            int endLine,
            String fileSha256,
            String snippet) implements Comparable<EvidenceSpan> {
        public EvidenceSpan(String path, int startLine, int endLine, String fileSha256) {
            this(path, startLine, endLine, fileSha256, "");
        }

        public EvidenceSpan {
            path = CapturedFileIndex.normalizePath(Objects.requireNonNull(path, "path"));
            if (path.isBlank()) {
                throw new IllegalArgumentException("EvidenceSpan path must not be blank");
            }
            startLine = Math.max(1, startLine);
            endLine = Math.max(startLine, endLine);
            fileSha256 = normalizeStoredDigest(Objects.requireNonNull(fileSha256, "fileSha256"));
            if (fileSha256.isBlank()) {
                throw new IllegalArgumentException("EvidenceSpan fileSha256 must not be blank");
            }
            snippet = snippet == null ? "" : snippet;
        }

        /** Human- and machine-readable pin string: {@code path:startLine-endLine@fileSha256}. */
        public String pin() {
            return path + ":" + startLine + "-" + endLine + "@" + fileSha256;
        }

        public Map<String, Object> toView() {
            var m = new LinkedHashMap<String, Object>();
            m.put("path", path);
            m.put("startLine", startLine);
            m.put("endLine", endLine);
            m.put("fileSha256", fileSha256);
            m.put("pin", pin());
            if (!snippet.isEmpty()) {
                m.put("snippet", snippet);
            }
            return Collections.unmodifiableMap(m);
        }

        @Override
        public int compareTo(EvidenceSpan other) {
            int c = this.path.compareTo(other.path);
            if (c != 0) return c;
            c = Integer.compare(this.startLine, other.startLine);
            if (c != 0) return c;
            c = Integer.compare(this.endLine, other.endLine);
            if (c != 0) return c;
            c = this.fileSha256.compareTo(other.fileSha256);
            if (c != 0) return c;
            return this.snippet.compareTo(other.snippet);
        }
    }

    /**
     * Canonical persisted 4-tuple procedural tacit memory record (PRAXIS Eq. 6) extended with
     * cryptographic SHA-256 evidence pins, provenance source kind, verification counters, and
     * staleness flag.
     */
    public record TacitMemoryUnit(
            String unitId,
            String skgEntityId,
            String trigger,
            String content,
            List<EvidenceSpan> evidenceFileSha256Spans,
            double confidence,
            SourceKind sourceKind,
            int verifiedPassCount,
            int verifiedFailCount,
            String lastVerifiedCommit,
            boolean staleEvidence) {
        public TacitMemoryUnit {
            unitId = unitId == null ? "" : unitId.strip();
            skgEntityId = Objects.requireNonNull(skgEntityId, "skgEntityId").strip();
            if (skgEntityId.isBlank()) {
                throw new IllegalArgumentException("skgEntityId must not be blank");
            }
            trigger = Objects.requireNonNull(trigger, "trigger").strip();
            if (trigger.isBlank()) {
                throw new IllegalArgumentException("trigger must not be blank");
            }
            content = Objects.requireNonNull(content, "content").strip();
            if (content.isBlank()) {
                throw new IllegalArgumentException("content must not be blank");
            }
            if (sourceKind == null) {
                throw new IllegalArgumentException(
                        "UNVERIFIED_HYPOTHESIS_REJECTED: sourceKind must be PASSIVE_GIT_ADR or SANDBOX_DIFF_VERIFIED");
            }
            confidence = requireValidConfidence(confidence);
            if (verifiedPassCount < 0 || verifiedFailCount < 0) {
                throw new IllegalArgumentException("verification counts must be >= 0");
            }
            var sortedSpans = new ArrayList<EvidenceSpan>(evidenceFileSha256Spans == null ? List.of() : evidenceFileSha256Spans);
            Collections.sort(sortedSpans);
            evidenceFileSha256Spans = List.copyOf(sortedSpans);
            lastVerifiedCommit = lastVerifiedCommit == null ? "" : lastVerifiedCommit.strip();
        }

        public String trustTier() {
            return TRUST_TIER;
        }

        public boolean quarantined() {
            return true;
        }

        public Map<String, Object> toView() {
            var m = new LinkedHashMap<String, Object>();
            m.put("unitId", unitId);
            m.put("skgEntityId", skgEntityId);
            m.put("trigger", trigger);
            m.put("content", content);
            m.put("evidenceFileSha256Spans", evidenceFileSha256Spans.stream().map(EvidenceSpan::toView).toList());
            m.put("confidence", canonicalDecimal(confidence));
            m.put("sourceKind", sourceKind.name());
            m.put("verifiedPassCount", verifiedPassCount);
            m.put("verifiedFailCount", verifiedFailCount);
            m.put("lastVerifiedCommit", lastVerifiedCommit);
            m.put("staleEvidence", staleEvidence);
            m.put("trustTier", TRUST_TIER);
            m.put("canonicalTrustTier", CANONICAL_TRUST_TIER.name());
            m.put("quarantined", true);
            return Collections.unmodifiableMap(m);
        }
    }

    /** Corroborated tacit memory unit merged across distinct {@code unitId}s via Noisy-OR (PRAXIS Eq. 7). */
    public record MergedTacitUnit(
            String mergeKey,
            String trigger,
            String content,
            double confidence,
            int corroboratingCount,
            List<String> sourceUnitIds,
            List<String> anchorEntityIds,
            int minHopDistance,
            List<String> traversedEdgeKinds,
            List<EvidenceSpan> evidenceFileSha256Spans,
            List<SourceKind> sourceKinds,
            boolean anyStaleEvidence,
            String trustTier,
            boolean quarantined) {
        public MergedTacitUnit {
            mergeKey = Objects.requireNonNull(mergeKey, "mergeKey");
            trigger = Objects.requireNonNull(trigger, "trigger");
            content = Objects.requireNonNull(content, "content");
            confidence = clampConfidence(confidence);
            sourceUnitIds = List.copyOf(sourceUnitIds == null ? List.of() : sourceUnitIds);
            anchorEntityIds = List.copyOf(anchorEntityIds == null ? List.of() : anchorEntityIds);
            traversedEdgeKinds = List.copyOf(traversedEdgeKinds == null ? List.of() : traversedEdgeKinds);
            evidenceFileSha256Spans = List.copyOf(evidenceFileSha256Spans == null ? List.of() : evidenceFileSha256Spans);
            sourceKinds = List.copyOf(sourceKinds == null ? List.of() : sourceKinds);
            trustTier = TRUST_TIER;
            quarantined = true;
        }

        /** Formats a compact single-block annotation for MCP tool responses (PRAXIS Eq. 9). */
        public String toCompactAnnotation() {
            String pins = evidenceFileSha256Spans.stream()
                    .map(EvidenceSpan::pin)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("none");
            return String.format(Locale.ROOT,
                    "[conf=%.4f, hops=%d, sources=%d%s] %s => %s (evidence: %s)",
                    confidence,
                    minHopDistance,
                    corroboratingCount,
                    anyStaleEvidence ? ", STALE_EVIDENCE" : "",
                    trigger,
                    content,
                    pins);
        }

        public Map<String, Object> toView() {
            var m = new LinkedHashMap<String, Object>();
            m.put("mergeKey", mergeKey);
            m.put("trigger", trigger);
            m.put("content", content);
            m.put("confidence", canonicalDecimal(confidence));
            m.put("corroboratingCount", corroboratingCount);
            m.put("sourceUnitIds", sourceUnitIds);
            m.put("anchorEntityIds", anchorEntityIds);
            m.put("minHopDistance", minHopDistance);
            m.put("traversedEdgeKinds", traversedEdgeKinds);
            m.put("evidenceFileSha256Spans", evidenceFileSha256Spans.stream().map(EvidenceSpan::toView).toList());
            m.put("sourceKinds", sourceKinds.stream().map(Enum::name).toList());
            m.put("anyStaleEvidence", anyStaleEvidence);
            m.put("trustTier", trustTier);
            m.put("canonicalTrustTier", CANONICAL_TRUST_TIER.name());
            m.put("quarantined", quarantined);
            return Collections.unmodifiableMap(m);
        }
    }

    /** PRAXIS Eq. 8 Task-Initialization proactive injection bundle. */
    public record TaskInitResult(
            String schema,
            String trustTier,
            boolean quarantined,
            String header,
            String targetEntityId,
            double threshold,
            int maxHops,
            int visitedEntityCount,
            List<MergedTacitUnit> units,
            String formattedPromptBlock) {
        public TaskInitResult {
            schema = SCHEMA;
            trustTier = TRUST_TIER;
            quarantined = true;
            header = HEADER_BANNER;
            targetEntityId = Objects.requireNonNull(targetEntityId, "targetEntityId");
            units = List.copyOf(units == null ? List.of() : units);
            formattedPromptBlock = formattedPromptBlock == null ? "" : formattedPromptBlock;
        }

        public Map<String, Object> toView() {
            var m = new LinkedHashMap<String, Object>();
            m.put("schema", schema);
            m.put("trustTier", trustTier);
            m.put("canonicalTrustTier", CANONICAL_TRUST_TIER.name());
            m.put("quarantined", quarantined);
            m.put("header", header);
            m.put("targetEntityId", targetEntityId);
            m.put("threshold", canonicalDecimal(threshold));
            m.put("maxHops", maxHops);
            m.put("visitedEntityCount", visitedEntityCount);
            m.put("unitCount", units.size());
            m.put("units", units.stream().map(MergedTacitUnit::toView).toList());
            m.put("formattedPromptBlock", formattedPromptBlock);
            return Collections.unmodifiableMap(m);
        }
    }

    /** PRAXIS Eq. 9 Interaction-Triggered proactive injection bundle for MCP tools. */
    public record InteractionTriggeredResult(
            String schema,
            String trustTier,
            boolean quarantined,
            String header,
            List<String> accessedEntityIds,
            double threshold,
            int maxHops,
            List<MergedTacitUnit> annotations,
            String compactSummary) {
        public InteractionTriggeredResult {
            schema = SCHEMA;
            trustTier = TRUST_TIER;
            quarantined = true;
            header = HEADER_BANNER;
            accessedEntityIds = List.copyOf(accessedEntityIds == null ? List.of() : accessedEntityIds);
            annotations = List.copyOf(annotations == null ? List.of() : annotations);
            compactSummary = compactSummary == null ? "" : compactSummary;
        }

        public Map<String, Object> toView() {
            var m = new LinkedHashMap<String, Object>();
            m.put("schema", schema);
            m.put("trustTier", trustTier);
            m.put("canonicalTrustTier", CANONICAL_TRUST_TIER.name());
            m.put("quarantined", quarantined);
            m.put("header", header);
            m.put("accessedEntityIds", accessedEntityIds);
            m.put("threshold", canonicalDecimal(threshold));
            m.put("maxHops", maxHops);
            m.put("annotationCount", annotations.size());
            m.put("annotations", annotations.stream().map(MergedTacitUnit::toView).toList());
            m.put("compactSummary", compactSummary);
            return Collections.unmodifiableMap(m);
        }
    }

    /** Deterministic report returned by {@link #reconcileWithCurrentFileHashes(Map)}. */
    public record ReconciliationReport(
            int unitsScanned,
            int unitsDecayed,
            List<String> decayedUnitIds) {
        public ReconciliationReport {
            decayedUnitIds = List.copyOf(decayedUnitIds == null ? List.of() : decayedUnitIds);
        }
    }

    /** Read-only quarantined overlay snapshot exposed via {@link AdvisoryOverlayService}. */
    public record OverlaySnapshot(
            String schema,
            String trustTier,
            TrustTier canonicalTrustTier,
            boolean quarantined,
            String graphIdentity,
            int totalStoreUnits,
            int graphAnchoredUnits,
            int activeAboveThresholdCount,
            int staleUnitCount,
            List<TacitMemoryUnit> units,
            Optional<TaskInitResult> taskInit) {
        public OverlaySnapshot {
            schema = SCHEMA;
            trustTier = TRUST_TIER;
            canonicalTrustTier = CANONICAL_TRUST_TIER;
            quarantined = true;
            graphIdentity = graphIdentity == null ? "" : graphIdentity;
            units = List.copyOf(units == null ? List.of() : units);
            taskInit = taskInit == null ? Optional.empty() : taskInit;
        }

        public Map<String, Object> toView() {
            var m = new LinkedHashMap<String, Object>();
            m.put("schema", schema);
            m.put("trustTier", trustTier);
            m.put("canonicalTrustTier", canonicalTrustTier.name());
            m.put("quarantined", quarantined);
            m.put("notice", "Tacit memory units are strictly quarantined in an isolated SQLite store and NEVER mutate KnowledgeGraph digests, Leiden partitions, Policy verdicts, or Architecture Score v2.");
            m.put("graphIdentity", graphIdentity);
            m.put("totalStoreUnits", totalStoreUnits);
            m.put("graphAnchoredUnits", graphAnchoredUnits);
            m.put("activeAboveThresholdCount", activeAboveThresholdCount);
            m.put("staleUnitCount", staleUnitCount);
            m.put("units", units.stream().map(TacitMemoryUnit::toView).toList());
            taskInit.ifPresent(ti -> m.put("taskInit", ti.toView()));
            return Collections.unmodifiableMap(m);
        }
    }

    private final Path dbFile;
    private final Connection connection;
    private final Object lock = new Object();
    private boolean closed;

    /** Opens or creates a dedicated quarantined SQLite tacit-memory database at {@code dbFile}. */
    public TacitMemoryStore(Path dbFile) {
        this.dbFile = Objects.requireNonNull(dbFile, "dbFile").toAbsolutePath().normalize();
        try {
            if (this.dbFile.getParent() != null) {
                Files.createDirectories(this.dbFile.getParent());
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbFile);
            initializeSchema(true);
        } catch (IOException | SQLException err) {
            throw new IllegalStateException("Failed to initialize TacitMemoryStore at " + this.dbFile, err);
        }
    }

    private TacitMemoryStore(Connection inMemoryConn) {
        this.dbFile = null;
        this.connection = inMemoryConn;
        try {
            initializeSchema(false);
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to initialize in-memory TacitMemoryStore", err);
        }
    }

    /** Creates an isolated in-memory SQLite tacit-memory store (useful for ephemeral sessions/tests). */
    public static TacitMemoryStore inMemory() {
        try {
            return new TacitMemoryStore(DriverManager.getConnection("jdbc:sqlite::memory:"));
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to create in-memory TacitMemoryStore", err);
        }
    }

    public Optional<Path> dbFile() {
        return Optional.ofNullable(dbFile);
    }

    private void initializeSchema(boolean fileBacked) throws SQLException {
        try (Statement st = connection.createStatement()) {
            if (fileBacked) {
                st.execute("PRAGMA journal_mode=WAL");
            }
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
                    CREATE TABLE IF NOT EXISTS tacit_memory_units (
                        unit_id                 TEXT PRIMARY KEY,
                        skg_entity_id           TEXT NOT NULL,
                        trigger_text            TEXT NOT NULL,
                        content_text            TEXT NOT NULL,
                        evidence_spans_json     TEXT NOT NULL,
                        confidence              REAL NOT NULL,
                        source_kind             TEXT NOT NULL CHECK (source_kind IN ('PASSIVE_GIT_ADR', 'SANDBOX_DIFF_VERIFIED')),
                        verified_pass_count     INTEGER NOT NULL DEFAULT 0,
                        verified_fail_count     INTEGER NOT NULL DEFAULT 0,
                        last_verified_commit    TEXT NOT NULL DEFAULT '',
                        stale_evidence          INTEGER NOT NULL DEFAULT 0,
                        decayed_hashes_json     TEXT NOT NULL DEFAULT '{}'
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_tacit_entity ON tacit_memory_units(skg_entity_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tacit_conf ON tacit_memory_units(confidence DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tacit_stale ON tacit_memory_units(stale_evidence)");
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO schema_meta(key, value) VALUES (?, ?)")) {
                ps.setString(1, "schema");
                ps.setString(2, SCHEMA);
                ps.executeUpdate();
                ps.setString(1, "schema_version");
                ps.setString(2, SCHEMA_VERSION);
                ps.executeUpdate();
                ps.setString(1, "trust_tier");
                ps.setString(2, TRUST_TIER);
                ps.executeUpdate();
                ps.setString(1, "redaction_version");
                ps.setString(2, LlmTransport.REDACTION_VERSION);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Ingests a tacit memory unit after enforcing anti-poisoning source validation, passive confidence
     * capping ({@code c_0 <= 0.65}), and mandatory {@code llm-redact:v2} secret redaction on every
     * {@code trigger}, {@code content}, and {@code evidence} snippet BEFORE writing to SQLite.
     */
    public TacitMemoryUnit ingest(
            String skgEntityId,
            String trigger,
            String content,
            List<EvidenceSpan> evidenceFileSha256Spans,
            double initialConfidence,
            SourceKind sourceKind,
            String lastVerifiedCommit) {
        if (sourceKind == null) {
            throw new IllegalArgumentException(
                    "UNVERIFIED_HYPOTHESIS_REJECTED: sourceKind must be PASSIVE_GIT_ADR or SANDBOX_DIFF_VERIFIED");
        }
        double validated = requireValidConfidence(initialConfidence);
        double effectiveConfidence = sourceKind == SourceKind.PASSIVE_GIT_ADR
                ? Math.min(PASSIVE_MAX_INITIAL_CONFIDENCE, validated)
                : validated;
        var candidate = new TacitMemoryUnit(
                "",
                skgEntityId,
                trigger,
                content,
                evidenceFileSha256Spans,
                effectiveConfidence,
                sourceKind,
                sourceKind == SourceKind.SANDBOX_DIFF_VERIFIED ? 1 : 0,
                0,
                lastVerifiedCommit,
                false);
        return put(candidate);
    }

    /**
     * String-labeled ingestion entrypoint that explicitly rejects unverified free-form LLM hypotheses
     * (anything other than {@code PASSIVE_GIT_ADR} or {@code SANDBOX_DIFF_VERIFIED}).
     */
    public TacitMemoryUnit ingestWithSourceLabel(
            String skgEntityId,
            String trigger,
            String content,
            List<EvidenceSpan> evidenceFileSha256Spans,
            double initialConfidence,
            String rawSourceKind,
            String lastVerifiedCommit) {
        SourceKind parsed = SourceKind.parseAllowed(rawSourceKind);
        return ingest(skgEntityId, trigger, content, evidenceFileSha256Spans, initialConfidence, parsed, lastVerifiedCommit);
    }

    /**
     * Sanitizes, enforces anti-poisoning caps, assigns a deterministic {@code unitId} if absent,
     * and persists a {@link TacitMemoryUnit} into the isolated SQLite database.
     */
    public TacitMemoryUnit put(TacitMemoryUnit rawUnit) {
        Objects.requireNonNull(rawUnit, "unit");
        TacitMemoryUnit sanitized = sanitizeForStorage(rawUnit);
        synchronized (lock) {
            ensureOpen();
            String sql = """
                    INSERT INTO tacit_memory_units (
                        unit_id, skg_entity_id, trigger_text, content_text, evidence_spans_json,
                        confidence, source_kind, verified_pass_count, verified_fail_count,
                        last_verified_commit, stale_evidence, decayed_hashes_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}')
                    ON CONFLICT(unit_id) DO UPDATE SET
                        skg_entity_id = excluded.skg_entity_id,
                        trigger_text = excluded.trigger_text,
                        content_text = excluded.content_text,
                        evidence_spans_json = excluded.evidence_spans_json,
                        confidence = excluded.confidence,
                        source_kind = excluded.source_kind,
                        verified_pass_count = excluded.verified_pass_count,
                        verified_fail_count = excluded.verified_fail_count,
                        last_verified_commit = excluded.last_verified_commit,
                        stale_evidence = excluded.stale_evidence,
                        decayed_hashes_json = '{}'
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, sanitized.unitId());
                ps.setString(2, sanitized.skgEntityId());
                ps.setString(3, sanitized.trigger());
                ps.setString(4, sanitized.content());
                ps.setString(5, encodeSpansJson(sanitized.evidenceFileSha256Spans()));
                ps.setDouble(6, sanitized.confidence());
                ps.setString(7, sanitized.sourceKind().name());
                ps.setInt(8, sanitized.verifiedPassCount());
                ps.setInt(9, sanitized.verifiedFailCount());
                ps.setString(10, sanitized.lastVerifiedCommit());
                ps.setInt(11, sanitized.staleEvidence() ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException err) {
                throw new IllegalStateException("Failed to persist TacitMemoryUnit " + sanitized.unitId(), err);
            }
            return sanitized;
        }
    }

    /**
     * Batch transactional insert of sanitized units (strictly applying {@code llm-redact:v2} and
     * anti-poisoning guards to every item before persistence).
     */
    public List<TacitMemoryUnit> putAll(Collection<TacitMemoryUnit> units) {
        Objects.requireNonNull(units, "units");
        if (units.isEmpty()) return List.of();
        var sanitizedList = new ArrayList<TacitMemoryUnit>(units.size());
        for (var u : units) {
            sanitizedList.add(sanitizeForStorage(Objects.requireNonNull(u, "unit")));
        }
        synchronized (lock) {
            ensureOpen();
            boolean prevAutoCommit;
            try {
                prevAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                String sql = """
                        INSERT INTO tacit_memory_units (
                            unit_id, skg_entity_id, trigger_text, content_text, evidence_spans_json,
                            confidence, source_kind, verified_pass_count, verified_fail_count,
                            last_verified_commit, stale_evidence, decayed_hashes_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}')
                        ON CONFLICT(unit_id) DO UPDATE SET
                            skg_entity_id = excluded.skg_entity_id,
                            trigger_text = excluded.trigger_text,
                            content_text = excluded.content_text,
                            evidence_spans_json = excluded.evidence_spans_json,
                            confidence = excluded.confidence,
                            source_kind = excluded.source_kind,
                            verified_pass_count = excluded.verified_pass_count,
                            verified_fail_count = excluded.verified_fail_count,
                            last_verified_commit = excluded.last_verified_commit,
                            stale_evidence = excluded.stale_evidence,
                            decayed_hashes_json = '{}'
                        """;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    for (var s : sanitizedList) {
                        ps.setString(1, s.unitId());
                        ps.setString(2, s.skgEntityId());
                        ps.setString(3, s.trigger());
                        ps.setString(4, s.content());
                        ps.setString(5, encodeSpansJson(s.evidenceFileSha256Spans()));
                        ps.setDouble(6, s.confidence());
                        ps.setString(7, s.sourceKind().name());
                        ps.setInt(8, s.verifiedPassCount());
                        ps.setInt(9, s.verifiedFailCount());
                        ps.setString(10, s.lastVerifiedCommit());
                        ps.setInt(11, s.staleEvidence() ? 1 : 0);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                connection.commit();
                connection.setAutoCommit(prevAutoCommit);
                return List.copyOf(sanitizedList);
            } catch (SQLException err) {
                try {
                    connection.rollback();
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {}
                throw new IllegalStateException("Failed to batch persist TacitMemoryUnits", err);
            }
        }
    }

    public Optional<TacitMemoryUnit> get(String unitId) {
        if (unitId == null || unitId.isBlank()) return Optional.empty();
        synchronized (lock) {
            ensureOpen();
            String sql = """
                    SELECT unit_id, skg_entity_id, trigger_text, content_text, evidence_spans_json,
                           confidence, source_kind, verified_pass_count, verified_fail_count,
                           last_verified_commit, stale_evidence
                    FROM tacit_memory_units WHERE unit_id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, unitId.strip());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(readUnitRow(rs));
                }
            } catch (SQLException err) {
                throw new IllegalStateException("Failed to read TacitMemoryUnit " + unitId, err);
            }
        }
    }

    public List<TacitMemoryUnit> listForEntity(String skgEntityId) {
        if (skgEntityId == null || skgEntityId.isBlank()) return List.of();
        synchronized (lock) {
            ensureOpen();
            String sql = """
                    SELECT unit_id, skg_entity_id, trigger_text, content_text, evidence_spans_json,
                           confidence, source_kind, verified_pass_count, verified_fail_count,
                           last_verified_commit, stale_evidence
                    FROM tacit_memory_units
                    WHERE skg_entity_id = ?
                    ORDER BY unit_id ASC
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, skgEntityId.strip());
                return readUnitList(ps);
            } catch (SQLException err) {
                throw new IllegalStateException("Failed to list tacit units for entity " + skgEntityId, err);
            }
        }
    }

    public List<TacitMemoryUnit> listForEntities(Collection<String> skgEntityIds) {
        if (skgEntityIds == null || skgEntityIds.isEmpty()) return List.of();
        var sortedIds = new ArrayList<String>(new TreeSet<>(skgEntityIds));
        synchronized (lock) {
            ensureOpen();
            var result = new ArrayList<TacitMemoryUnit>();
            // Chunk IN-clauses safely below SQLite's 999 bind parameter limit.
            int chunkSize = 400;
            for (int start = 0; start < sortedIds.size(); start += chunkSize) {
                int end = Math.min(sortedIds.size(), start + chunkSize);
                var sub = sortedIds.subList(start, end);
                String placeholders = String.join(",", Collections.nCopies(sub.size(), "?"));
                String sql = """
                        SELECT unit_id, skg_entity_id, trigger_text, content_text, evidence_spans_json,
                               confidence, source_kind, verified_pass_count, verified_fail_count,
                               last_verified_commit, stale_evidence
                        FROM tacit_memory_units
                        WHERE skg_entity_id IN (""" + placeholders + ") ORDER BY unit_id ASC";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    for (int i = 0; i < sub.size(); i++) {
                        ps.setString(i + 1, sub.get(i));
                    }
                    result.addAll(readUnitList(ps));
                } catch (SQLException err) {
                    throw new IllegalStateException("Failed to query tacit units by entity set", err);
                }
            }
            result.sort(Comparator.comparing(TacitMemoryUnit::unitId));
            return List.copyOf(result);
        }
    }

    public List<TacitMemoryUnit> listAll() {
        synchronized (lock) {
            ensureOpen();
            String sql = """
                    SELECT unit_id, skg_entity_id, trigger_text, content_text, evidence_spans_json,
                           confidence, source_kind, verified_pass_count, verified_fail_count,
                           last_verified_commit, stale_evidence
                    FROM tacit_memory_units
                    ORDER BY unit_id ASC
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                return readUnitList(ps);
            } catch (SQLException err) {
                throw new IllegalStateException("Failed to list all tacit memory units", err);
            }
        }
    }

    public int count() {
        synchronized (lock) {
            ensureOpen();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tacit_memory_units")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException err) {
                throw new IllegalStateException("Failed to count tacit memory units", err);
            }
        }
    }

    /**
     * SHA-256 span-pinned staleness decay: for every unit whose anchored evidence span's
     * {@code fileSha256} differs from {@code currentSha256ByPath}, decays
     * {@code confidence <- 0.5 * confidence} and marks {@code staleEvidence = true} until
     * re-verified. Idempotent across repeated calls with the exact same unchanged file hashes,
     * while decaying again if the file subsequently mutates to yet another distinct SHA-256.
     */
    public ReconciliationReport reconcileWithCurrentFileHashes(Map<String, String> currentSha256ByPath) {
        Objects.requireNonNull(currentSha256ByPath, "currentSha256ByPath");
        var normalizedCurrent = new HashMap<String, String>(currentSha256ByPath.size() * 2 + 1);
        for (var entry : currentSha256ByPath.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            String normPath = CapturedFileIndex.normalizePath(entry.getKey());
            String normSha = entry.getValue() == null ? "" : normalizeComparableDigest(entry.getValue());
            normalizedCurrent.put(normPath, normSha);
        }

        synchronized (lock) {
            ensureOpen();
            var decayedIds = new ArrayList<String>();
            int scanned = 0;
            String selectSql = """
                    SELECT unit_id, evidence_spans_json, confidence, stale_evidence, decayed_hashes_json
                    FROM tacit_memory_units
                    ORDER BY unit_id ASC
                    """;
            String updateSql = """
                    UPDATE tacit_memory_units
                    SET confidence = ?, stale_evidence = 1, decayed_hashes_json = ?
                    WHERE unit_id = ?
                    """;
            try {
                boolean prevAuto = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (PreparedStatement sel = connection.prepareStatement(selectSql);
                     ResultSet rs = sel.executeQuery();
                     PreparedStatement upd = connection.prepareStatement(updateSql)) {
                    while (rs.next()) {
                        scanned++;
                        String unitId = rs.getString(1);
                        List<EvidenceSpan> spans = decodeSpansJson(rs.getString(2));
                        double confidence = rs.getDouble(3);
                        boolean alreadyStale = rs.getInt(4) != 0;
                        Map<String, String> priorDecayed = decodeStringMap(rs.getString(5));

                        var currentMismatches = new TreeMap<String, String>();
                        for (var span : spans) {
                            if (!normalizedCurrent.containsKey(span.path())) continue;
                            String observed = normalizedCurrent.get(span.path());
                            String pinned = normalizeComparableDigest(span.fileSha256());
                            if (!pinned.equals(observed)) {
                                currentMismatches.put(span.path(), observed);
                            }
                        }
                        if (currentMismatches.isEmpty()) continue;

                        boolean hasNewDrift = !alreadyStale || !currentMismatches.equals(priorDecayed);
                        if (!hasNewDrift) continue;

                        double decayedConfidence = clampConfidence(confidence * STALENESS_DECAY_FACTOR);
                        upd.setDouble(1, decayedConfidence);
                        upd.setString(2, CanonicalJson.write(currentMismatches));
                        upd.setString(3, unitId);
                        upd.addBatch();
                        decayedIds.add(unitId);
                    }
                    if (!decayedIds.isEmpty()) {
                        upd.executeBatch();
                    }
                }
                connection.commit();
                connection.setAutoCommit(prevAuto);
                return new ReconciliationReport(scanned, decayedIds.size(), decayedIds);
            } catch (SQLException err) {
                try {
                    connection.rollback();
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {}
                throw new IllegalStateException("Failed to reconcile tacit memory file hashes", err);
            }
        }
    }

    /** Convenience helper extracting current captured file SHA-256 hashes directly from a {@link KnowledgeGraph}. */
    public ReconciliationReport reconcileWithGraph(KnowledgeGraph graph) {
        Objects.requireNonNull(graph, "graph");
        var fileIndex = CapturedFileIndex.forGraph(graph);
        var byPath = new LinkedHashMap<String, String>();
        for (var entry : fileIndex.entries()) {
            entry.digest().ifPresent(d -> byPath.put(entry.path(), d.value()));
        }
        return reconcileWithCurrentFileHashes(byPath);
    }

    /**
     * Online confidence evolution locked exclusively to verified sandbox outcomes (PRAXIS {@code beta = 0.8}):
     * <ul>
     *   <li>{@code resolved == true} ({@code RESOLVED}):
     *       {@code c' = 1.0 - 0.8 * (1.0 - c)}, {@code verifiedPassCount++}, {@code staleEvidence = false}</li>
     *   <li>{@code resolved == false} ({@code PERSISTED} / fail):
     *       {@code c' = 0.8 * c}, {@code verifiedFailCount++}</li>
     * </ul>
     */
    public Optional<TacitMemoryUnit> recordVerificationOutcome(String unitId, boolean resolved, String commitSha) {
        if (unitId == null || unitId.isBlank()) return Optional.empty();
        String safeCommit = sanitizeCommitSha(commitSha);
        synchronized (lock) {
            ensureOpen();
            var existingOpt = get(unitId);
            if (existingOpt.isEmpty()) return Optional.empty();
            var existing = existingOpt.orElseThrow();
            double updatedConfidence = evolveConfidence(existing.confidence(), resolved);
            int nextPass = existing.verifiedPassCount() + (resolved ? 1 : 0);
            int nextFail = existing.verifiedFailCount() + (resolved ? 0 : 1);
            boolean nextStale = resolved ? false : existing.staleEvidence();
            String nextCommit = safeCommit.isEmpty() ? existing.lastVerifiedCommit() : safeCommit;

            String sql = resolved
                    ? """
                      UPDATE tacit_memory_units
                      SET confidence = ?, verified_pass_count = ?, verified_fail_count = ?,
                          last_verified_commit = ?, stale_evidence = 0, decayed_hashes_json = '{}'
                      WHERE unit_id = ?
                      """
                    : """
                      UPDATE tacit_memory_units
                      SET confidence = ?, verified_pass_count = ?, verified_fail_count = ?,
                          last_verified_commit = ?, stale_evidence = ?
                      WHERE unit_id = ?
                      """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setDouble(1, updatedConfidence);
                ps.setInt(2, nextPass);
                ps.setInt(3, nextFail);
                ps.setString(4, nextCommit);
                if (resolved) {
                    ps.setString(5, existing.unitId());
                } else {
                    ps.setInt(5, nextStale ? 1 : 0);
                    ps.setString(6, existing.unitId());
                }
                ps.executeUpdate();
            } catch (SQLException err) {
                throw new IllegalStateException("Failed to evolve confidence for " + unitId, err);
            }
            return Optional.of(new TacitMemoryUnit(
                    existing.unitId(),
                    existing.skgEntityId(),
                    existing.trigger(),
                    existing.content(),
                    existing.evidenceFileSha256Spans(),
                    updatedConfidence,
                    existing.sourceKind(),
                    nextPass,
                    nextFail,
                    nextCommit,
                    nextStale));
        }
    }

    /** Batch deterministic confidence evolution across a set of consulted {@code unitId}s. */
    public List<TacitMemoryUnit> recordVerificationOutcomes(
            Collection<String> unitIds, boolean resolved, String commitSha) {
        if (unitIds == null || unitIds.isEmpty()) return List.of();
        var sorted = new TreeSet<String>();
        for (String id : unitIds) {
            if (id != null && !id.isBlank()) sorted.add(id.strip());
        }
        var updated = new ArrayList<TacitMemoryUnit>(sorted.size());
        synchronized (lock) {
            for (String id : sorted) {
                recordVerificationOutcome(id, resolved, commitSha).ifPresent(updated::add);
            }
        }
        return List.copyOf(updated);
    }

    /** Pure PRAXIS {@code beta = 0.8} confidence evolution equation. */
    public static double evolveConfidence(double currentConfidence, boolean resolved) {
        double c = clampConfidence(currentConfidence);
        return resolved
                ? clampConfidence(1.0 - EVOLUTION_BETA * (1.0 - c))
                : clampConfidence(EVOLUTION_BETA * c);
    }

    /**
     * Pure PRAXIS Eq. 7 Noisy-OR corroboration merger:
     * {@code confidence(merged) = 1.0 - prod_{k in S}(1.0 - confidence(k))}.
     */
    public static double noisyOrConfidence(Collection<Double> confidences) {
        if (confidences == null || confidences.isEmpty()) return 0.0;
        double complement = 1.0;
        for (Double c : confidences) {
            if (c == null || !Double.isFinite(c)) continue;
            complement *= (1.0 - clampConfidence(c));
        }
        return clampConfidence(1.0 - complement);
    }

    /** Returns true iff {@code edgeKind} is one of the canonical bidirectional SKG propagation edge kinds. */
    public static boolean isPropagationEdgeKind(String edgeKind) {
        return edgeKind != null && PROPAGATION_EDGE_KINDS.contains(edgeKind);
    }

    /**
     * PRAXIS Eq. 8 Task-Initialization proactive retrieval with default {@code maxHops = 4}
     * and {@code theta = 0.70}. Ready for {@code RepairContextPlanner.plan(...)}.
     */
    public TaskInitResult retrieveTaskInit(KnowledgeGraph graph, String targetEntityId) {
        return retrieveTaskInit(graph, targetEntityId, MAX_PROPAGATION_HOPS, INJECTION_THRESHOLD);
    }

    /**
     * PRAXIS Eq. 8 Task-Initialization proactive retrieval along canonical bidirectional SKG edges
     * ({@code maxHops <= 4}), merging corroborating distinct units by Noisy-OR (Eq. 7) and
     * filtering strictly by {@code confidence >= threshold}.
     */
    public TaskInitResult retrieveTaskInit(
            KnowledgeGraph graph, String targetEntityId, int maxHops, double threshold) {
        Objects.requireNonNull(graph, "graph");
        String target = Objects.requireNonNull(targetEntityId, "targetEntityId").strip();
        if (target.isBlank()) {
            throw new IllegalArgumentException("targetEntityId must not be blank");
        }
        int boundedHops = Math.max(0, Math.min(MAX_PROPAGATION_HOPS, maxHops));
        double boundedThreshold = clampConfidence(threshold);

        PropagationNeighborhood neighborhood = traverseBidirectional(graph, List.of(target), boundedHops);
        List<MergedTacitUnit> merged = queryAndMerge(neighborhood, boundedThreshold);
        String promptBlock = formatTaskInitPromptBlock(target, merged);
        return new TaskInitResult(
                SCHEMA,
                TRUST_TIER,
                true,
                HEADER_BANNER,
                target,
                boundedThreshold,
                boundedHops,
                neighborhood.minDistanceByEntity().size(),
                merged,
                promptBlock);
    }

    /**
     * PRAXIS Eq. 9 Interaction-Triggered proactive retrieval with default {@code maxHops = 4}
     * and {@code theta = 0.70}. Ready for {@code McpToolDispatcher}.
     */
    public InteractionTriggeredResult retrieveInteractionTriggered(
            KnowledgeGraph graph, Collection<String> accessedEntityIds) {
        return retrieveInteractionTriggered(graph, accessedEntityIds, MAX_PROPAGATION_HOPS, INJECTION_THRESHOLD);
    }

    /**
     * PRAXIS Eq. 9 Interaction-Triggered proactive retrieval returning compact advisory tacit
     * annotations ({@code confidence >= threshold}) across all {@code accessedEntityIds} and their
     * {@code <= 4}-hop SKG neighborhood.
     */
    public InteractionTriggeredResult retrieveInteractionTriggered(
            KnowledgeGraph graph, Collection<String> accessedEntityIds, int maxHops, double threshold) {
        Objects.requireNonNull(graph, "graph");
        var seeds = new TreeSet<String>();
        if (accessedEntityIds != null) {
            for (String id : accessedEntityIds) {
                if (id != null && !id.isBlank()) seeds.add(id.strip());
            }
        }
        int boundedHops = Math.max(0, Math.min(MAX_PROPAGATION_HOPS, maxHops));
        double boundedThreshold = clampConfidence(threshold);
        if (seeds.isEmpty()) {
            return new InteractionTriggeredResult(
                    SCHEMA, TRUST_TIER, true, HEADER_BANNER, List.of(), boundedThreshold, boundedHops, List.of(), "");
        }
        PropagationNeighborhood neighborhood = traverseBidirectional(graph, seeds, boundedHops);
        List<MergedTacitUnit> merged = queryAndMerge(neighborhood, boundedThreshold);
        String compact = formatCompactInteractionSummary(merged);
        return new InteractionTriggeredResult(
                SCHEMA,
                TRUST_TIER,
                true,
                HEADER_BANNER,
                List.copyOf(seeds),
                boundedThreshold,
                boundedHops,
                merged,
                compact);
    }

    /**
     * Builds a strictly read-only quarantined {@link OverlaySnapshot} scoped to canonical entities
     * present in {@code graph}. NEVER mutates {@code graph} or this store.
     */
    public OverlaySnapshot buildReadOnlyOverlay(KnowledgeGraph graph, String optionalTargetEntityId) {
        Objects.requireNonNull(graph, "graph");
        var graphEntityIds = new TreeSet<String>();
        for (var node : graph.nodes()) {
            graphEntityIds.add(node.identity());
        }
        int totalStoreUnits = count();
        List<TacitMemoryUnit> anchoredUnits = listForEntities(graphEntityIds);
        int activeAbove = 0;
        int staleCount = 0;
        for (var u : anchoredUnits) {
            if (u.confidence() >= INJECTION_THRESHOLD - EPSILON) activeAbove++;
            if (u.staleEvidence()) staleCount++;
        }
        Optional<TaskInitResult> taskInit = (optionalTargetEntityId != null && !optionalTargetEntityId.isBlank())
                ? Optional.of(retrieveTaskInit(graph, optionalTargetEntityId.strip()))
                : Optional.empty();
        return new OverlaySnapshot(
                SCHEMA,
                TRUST_TIER,
                CANONICAL_TRUST_TIER,
                true,
                graph.identity().value(),
                totalStoreUnits,
                anchoredUnits.size(),
                activeAbove,
                staleCount,
                anchoredUnits,
                taskInit);
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            try {
                connection.close();
            } catch (SQLException ignored) {}
        }
    }

    // ---- Internal propagation, Noisy-OR merging, formatting, and sanitization helpers ----

    private record NeighborStep(String targetEntityId, String edgeKind) implements Comparable<NeighborStep> {
        @Override
        public int compareTo(NeighborStep other) {
            int c = this.targetEntityId.compareTo(other.targetEntityId);
            return c != 0 ? c : this.edgeKind.compareTo(other.edgeKind);
        }
    }

    private record PropagationNeighborhood(
            Map<String, Integer> minDistanceByEntity,
            Map<String, Set<String>> edgeKindsByEntity) {}

    private static PropagationNeighborhood traverseBidirectional(
            KnowledgeGraph graph, Collection<String> seedEntities, int maxHops) {
        var adjacency = new HashMap<String, TreeSet<NeighborStep>>();
        for (var edge : graph.edges()) {
            if (!isPropagationEdgeKind(edge.kind())) continue;
            String src = edge.source();
            for (String dst : edge.target().identities()) {
                if (src.equals(dst)) continue;
                adjacency.computeIfAbsent(src, k -> new TreeSet<>()).add(new NeighborStep(dst, edge.kind()));
                adjacency.computeIfAbsent(dst, k -> new TreeSet<>()).add(new NeighborStep(src, edge.kind()));
            }
        }

        var minDist = new LinkedHashMap<String, Integer>();
        var edgeKinds = new HashMap<String, TreeSet<String>>();
        var currentFrontier = new TreeSet<String>();
        for (String seed : seedEntities) {
            minDist.put(seed, 0);
            edgeKinds.put(seed, new TreeSet<>());
            currentFrontier.add(seed);
        }

        for (int depth = 0; depth < maxHops && !currentFrontier.isEmpty(); depth++) {
            var nextFrontier = new TreeSet<String>();
            int nextDepth = depth + 1;
            for (String node : currentFrontier) {
                var steps = adjacency.get(node);
                if (steps == null) continue;
                var inheritedKinds = edgeKinds.getOrDefault(node, new TreeSet<>());
                for (NeighborStep step : steps) {
                    Integer existingDist = minDist.get(step.targetEntityId());
                    if (existingDist == null) {
                        minDist.put(step.targetEntityId(), nextDepth);
                        var pathKinds = new TreeSet<String>(inheritedKinds);
                        pathKinds.add(step.edgeKind());
                        edgeKinds.put(step.targetEntityId(), pathKinds);
                        nextFrontier.add(step.targetEntityId());
                    } else if (existingDist == nextDepth) {
                        // Deterministically accumulate shortest-path edge kinds without re-queuing.
                        var pathKinds = edgeKinds.computeIfAbsent(step.targetEntityId(), k -> new TreeSet<>());
                        pathKinds.addAll(inheritedKinds);
                        pathKinds.add(step.edgeKind());
                    }
                }
            }
            currentFrontier = nextFrontier;
        }

        var frozenEdgeKinds = new LinkedHashMap<String, Set<String>>(edgeKinds.size());
        for (var e : edgeKinds.entrySet()) {
            frozenEdgeKinds.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        return new PropagationNeighborhood(Collections.unmodifiableMap(minDist), Collections.unmodifiableMap(frozenEdgeKinds));
    }

    private List<MergedTacitUnit> queryAndMerge(PropagationNeighborhood neighborhood, double threshold) {
        List<TacitMemoryUnit> rawUnits = listForEntities(neighborhood.minDistanceByEntity().keySet());
        if (rawUnits.isEmpty()) return List.of();

        // Deduplicate by unitId FIRST so diamond paths in the SKG NEVER self-corroborate a single unit!
        var uniqueByUnitId = new LinkedHashMap<String, TacitMemoryUnit>();
        for (var u : rawUnits) {
            uniqueByUnitId.putIfAbsent(u.unitId(), u);
        }

        // Group corroborating distinct units by canonical normalized (trigger, content) mergeKey.
        var groups = new LinkedHashMap<String, List<TacitMemoryUnit>>();
        for (var u : uniqueByUnitId.values()) {
            String key = mergeKeyOf(u.trigger(), u.content());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(u);
        }

        var merged = new ArrayList<MergedTacitUnit>(groups.size());
        for (var entry : groups.entrySet()) {
            String mergeKey = entry.getKey();
            List<TacitMemoryUnit> group = entry.getValue();
            group.sort(Comparator.comparing(TacitMemoryUnit::unitId));

            var confidences = new ArrayList<Double>(group.size());
            var unitIds = new ArrayList<String>(group.size());
            var anchors = new TreeSet<String>();
            var edgeKinds = new TreeSet<String>();
            var spans = new TreeSet<EvidenceSpan>();
            var sourceKinds = new TreeSet<SourceKind>();
            int minHop = Integer.MAX_VALUE;
            boolean anyStale = false;

            for (var u : group) {
                confidences.add(u.confidence());
                unitIds.add(u.unitId());
                anchors.add(u.skgEntityId());
                spans.addAll(u.evidenceFileSha256Spans());
                sourceKinds.add(u.sourceKind());
                anyStale |= u.staleEvidence();
                int hop = neighborhood.minDistanceByEntity().getOrDefault(u.skgEntityId(), 0);
                if (hop < minHop) minHop = hop;
                edgeKinds.addAll(neighborhood.edgeKindsByEntity().getOrDefault(u.skgEntityId(), Set.of()));
            }
            if (minHop == Integer.MAX_VALUE) minHop = 0;

            double noisyOr = noisyOrConfidence(confidences);
            if (noisyOr + EPSILON < threshold) {
                continue;
            }
            TacitMemoryUnit exemplar = group.getFirst();
            merged.add(new MergedTacitUnit(
                    mergeKey,
                    exemplar.trigger(),
                    exemplar.content(),
                    noisyOr,
                    group.size(),
                    unitIds,
                    new ArrayList<>(anchors),
                    minHop,
                    new ArrayList<>(edgeKinds),
                    new ArrayList<>(spans),
                    new ArrayList<>(sourceKinds),
                    anyStale,
                    TRUST_TIER,
                    true));
        }

        merged.sort(Comparator
                .comparingDouble(MergedTacitUnit::confidence).reversed()
                .thenComparingInt(MergedTacitUnit::minHopDistance)
                .thenComparing(MergedTacitUnit::mergeKey));
        return List.copyOf(merged);
    }

    private static String formatTaskInitPromptBlock(String targetEntityId, List<MergedTacitUnit> units) {
        if (units.isEmpty()) return "";
        var sb = new StringBuilder();
        sb.append(HEADER_BANNER).append('\n');
        sb.append("Target SKG Entity: ").append(targetEntityId).append('\n');
        sb.append("Quarantine Notice: Advisory tacit memory only (never overrides canonical compiler/SAT/policy verdicts).\n");
        for (int i = 0; i < units.size(); i++) {
            var u = units.get(i);
            sb.append(i + 1).append(". [confidence=").append(String.format(Locale.ROOT, "%.4f", u.confidence()))
                    .append(", hops=").append(u.minHopDistance())
                    .append(", corroborations=").append(u.corroboratingCount());
            if (u.anyStaleEvidence()) {
                sb.append(", STALE_EVIDENCE");
            }
            sb.append("]\n");
            sb.append("   Trigger: ").append(u.trigger()).append('\n');
            sb.append("   Guidance: ").append(u.content()).append('\n');
            sb.append("   Evidence SHA-256 Spans: ");
            if (u.evidenceFileSha256Spans().isEmpty()) {
                sb.append("(none)");
            } else {
                sb.append(String.join("; ", u.evidenceFileSha256Spans().stream().map(EvidenceSpan::pin).toList()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String formatCompactInteractionSummary(List<MergedTacitUnit> annotations) {
        if (annotations.isEmpty()) return "";
        var sb = new StringBuilder();
        sb.append(HEADER_BANNER).append('\n');
        for (var ann : annotations) {
            sb.append("- ").append(ann.toCompactAnnotation()).append('\n');
        }
        return sb.toString();
    }

    private static TacitMemoryUnit sanitizeForStorage(TacitMemoryUnit raw) {
        // 1. Redact every evidence span snippet using its exact path via llm-redact:v2 BEFORE writing to SQLite.
        var redactedSpans = new ArrayList<EvidenceSpan>(raw.evidenceFileSha256Spans().size());
        String preferredAnchorPath = null;
        for (var span : raw.evidenceFileSha256Spans()) {
            if (preferredAnchorPath == null
                    || (!looksLikeConfigPath(preferredAnchorPath) && looksLikeConfigPath(span.path()))) {
                preferredAnchorPath = span.path();
            }
            String cleanSnippet = redactAndNeutralize(span.path(), span.snippet());
            redactedSpans.add(new EvidenceSpan(
                    span.path(), span.startLine(), span.endLine(), span.fileSha256(), cleanSnippet));
        }
        Collections.sort(redactedSpans);

        String textPath = preferredAnchorPath != null ? preferredAnchorPath : "tacit-memory.md";
        // 2. Redact trigger & content via llm-redact:v2 + prompt-injection sentinel neutralization.
        String cleanTrigger = redactAndNeutralize(textPath, raw.trigger()).strip();
        String cleanContent = redactAndNeutralize(textPath, raw.content()).strip();
        if (cleanTrigger.isEmpty()) cleanTrigger = LlmTransport.REDACTED_PLACEHOLDER;
        if (cleanContent.isEmpty()) cleanContent = LlmTransport.REDACTED_PLACEHOLDER;

        String cleanCommit = sanitizeCommitSha(raw.lastVerifiedCommit());

        // 3. Enforce anti-poisoning initial passive cap (c_0 <= 0.65) when never sandbox-verified.
        double safeConfidence = (raw.sourceKind() == SourceKind.PASSIVE_GIT_ADR && raw.verifiedPassCount() == 0)
                ? Math.min(PASSIVE_MAX_INITIAL_CONFIDENCE, raw.confidence())
                : raw.confidence();

        String unitId = raw.unitId().isBlank()
                ? deterministicUnitId(raw.skgEntityId(), cleanTrigger, cleanContent, raw.sourceKind(), redactedSpans)
                : raw.unitId();

        return new TacitMemoryUnit(
                unitId,
                raw.skgEntityId(),
                cleanTrigger,
                cleanContent,
                redactedSpans,
                safeConfidence,
                raw.sourceKind(),
                raw.verifiedPassCount(),
                raw.verifiedFailCount(),
                cleanCommit,
                raw.staleEvidence());
    }

    private static String redactAndNeutralize(String path, String rawText) {
        if (rawText == null || rawText.isEmpty()) return "";
        String redacted = LlmTransport.redactDocumentForPath(path, rawText).text();
        return LlmTransport.neutralizeInjectionSentinels(redacted);
    }

    private static boolean looksLikeConfigPath(String path) {
        if (path == null || path.isBlank()) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        return name.startsWith(".env")
                || lower.endsWith(".env")
                || lower.endsWith(".properties")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".toml")
                || lower.endsWith(".ini")
                || lower.endsWith(".conf")
                || lower.endsWith(".cfg");
    }

    private static String sanitizeCommitSha(String rawCommit) {
        if (rawCommit == null || rawCommit.isBlank()) return "";
        String cleaned = LlmTransport.redactDocumentForPath("commit.txt", rawCommit.strip()).text().strip();
        return LlmTransport.neutralizeInjectionSentinels(cleaned);
    }

    private static String deterministicUnitId(
            String skgEntityId,
            String trigger,
            String content,
            SourceKind sourceKind,
            List<EvidenceSpan> spans) {
        var spanPins = spans.stream().map(EvidenceSpan::pin).toList();
        ContentDigest digest = ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
                SCHEMA, skgEntityId, trigger, content, sourceKind.name(), spanPins)));
        return "tacit:" + digest.value();
    }

    private static String mergeKeyOf(String trigger, String content) {
        String normTrigger = normalizeMergeText(trigger);
        String normContent = normalizeMergeText(content);
        return ContentDigest.sha256Utf8(CanonicalJson.write(List.of("tacit-merge:v1", normTrigger, normContent))).value();
    }

    private static String normalizeMergeText(String text) {
        if (text == null) return "";
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String normalizeStoredDigest(String raw) {
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        return s.startsWith("sha256:") ? s : "sha256:" + s;
    }

    private static String normalizeComparableDigest(String raw) {
        String s = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return s.startsWith("sha256:") ? s.substring("sha256:".length()) : s;
    }

    private static double requireValidConfidence(double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("Confidence must be finite and within [0.0, 1.0], got " + value);
        }
        return value;
    }

    private static double clampConfidence(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static java.math.BigDecimal canonicalDecimal(double value) {
        return java.math.BigDecimal.valueOf(clampConfidence(value))
                .setScale(6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private static String encodeSpansJson(List<EvidenceSpan> spans) {
        var rows = new ArrayList<Map<String, Object>>(spans.size());
        for (var s : spans) {
            var m = new LinkedHashMap<String, Object>();
            m.put("path", s.path());
            m.put("startLine", s.startLine());
            m.put("endLine", s.endLine());
            m.put("fileSha256", s.fileSha256());
            m.put("snippet", s.snippet());
            rows.add(m);
        }
        return CanonicalJson.write(rows);
    }

    private static List<EvidenceSpan> decodeSpansJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        var arr = GraphJson.array(GraphJson.read(json, JSON_LIMITS));
        var spans = new ArrayList<EvidenceSpan>(arr.size());
        for (Object item : arr) {
            var obj = GraphJson.object(item);
            String path = GraphJson.string(obj.get("path"));
            int startLine = obj.get("startLine") instanceof Number n ? n.intValue() : 1;
            int endLine = obj.get("endLine") instanceof Number n ? n.intValue() : startLine;
            String sha = GraphJson.string(obj.get("fileSha256"));
            String snippet = obj.get("snippet") instanceof String s ? s : "";
            spans.add(new EvidenceSpan(path, startLine, endLine, sha, snippet));
        }
        Collections.sort(spans);
        return List.copyOf(spans);
    }

    private static Map<String, String> decodeStringMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            var obj = GraphJson.object(GraphJson.read(json, JSON_LIMITS));
            var map = new TreeMap<String, String>();
            for (var e : obj.entrySet()) {
                map.put(e.getKey(), Objects.toString(e.getValue(), ""));
            }
            return map;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static List<TacitMemoryUnit> readUnitList(PreparedStatement ps) throws SQLException {
        var list = new ArrayList<TacitMemoryUnit>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(readUnitRow(rs));
            }
        }
        return List.copyOf(list);
    }

    private static TacitMemoryUnit readUnitRow(ResultSet rs) throws SQLException {
        return new TacitMemoryUnit(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                decodeSpansJson(rs.getString(5)),
                rs.getDouble(6),
                SourceKind.parseAllowed(rs.getString(7)),
                rs.getInt(8),
                rs.getInt(9),
                rs.getString(10),
                rs.getInt(11) != 0);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("TacitMemoryStore is already closed");
        }
    }
}
