package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.Entity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.backend.auth.AuthHttpApi;
import com.evolution.backend.auth.GitHubOAuthConfigStore;
import com.evolution.backend.auth.GitHubOAuthService;
import com.evolution.backend.auth.UserAccountStore;
import com.evolution.backend.auth.UserSessionStore;
import com.evolution.backend.query.llm.LlmTransport;
import com.evolution.backend.query.repair.RepairContextPlanner;
import com.evolution.backend.query.search.CapturedSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Moonshot2SearchAndRrfTest {

    @Test
    void shannonEntropyPreRedactionBlocksSecretsOnDiskAndHasZeroFalsePositivesOnHashesUuidsUrlsAndCode(@TempDir Path tempDir) throws Exception {
        assertEquals("llm-redact:v2", LlmTransport.REDACTION_VERSION);

        String highEntropyQuoted = "K9xR7mP2vQ4nW8zB3tY6jL1cF5hD0gS9";
        String highEntropyAssigned = "qW8eR4tY2uI6oP0aS3dF7gH9jK1lZ5xC";
        String highEntropyProse = "mN4pQ8rS2tU6vW0xY3zA7bC9dE1fG5hJ";
        String highEntropyJsonEscaped = "zX7cV3bN9mK5jH1gF8dS4aP2oI6uY0tR";
        String basicAuthB64 = "YWRtaW46U3VwZXJTZWNyZXRQYXNzOTk=";
        String ghSecret = "ghp_1234567890abcdef1234567890abcdef";
        String sha256Digest = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String hex40 = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";
        String hex64 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String uuidVal = "550e8400-e29b-41d4-a716-446655440000";
        String safeUrl = "https://api.example.com/v1/organizations/acme-corp/repositories/billing-engine";
        String javaFqn = "com.evolution.backend.query.ArchitectureQueryApi";
        String twoSegmentFqn = "OrderService.reconcileInvoiceBatchV2";
        String camelId = "OrderReconciliationServiceFactoryImpl";
        String camelV2 = "ArchitectureScoreModelV2Impl";

        assertTrue(LlmTransport.shannonEntropy(highEntropyQuoted) >= 4.2);
        assertTrue(LlmTransport.isHighEntropySecretToken(highEntropyQuoted));
        assertTrue(LlmTransport.isHighEntropySecretToken(highEntropyAssigned));
        assertTrue(LlmTransport.isHighEntropySecretToken(highEntropyProse));
        assertTrue(LlmTransport.isHighEntropySecretToken(highEntropyJsonEscaped));
        assertFalse(LlmTransport.isHighEntropySecretToken(sha256Digest));
        assertFalse(LlmTransport.isHighEntropySecretToken(hex40));
        assertFalse(LlmTransport.isHighEntropySecretToken(hex64));
        assertFalse(LlmTransport.isHighEntropySecretToken(uuidVal));
        assertFalse(LlmTransport.isHighEntropySecretToken(safeUrl));
        assertFalse(LlmTransport.isHighEntropySecretToken(javaFqn));
        assertFalse(LlmTransport.isHighEntropySecretToken(twoSegmentFqn));
        assertFalse(LlmTransport.isHighEntropySecretToken(camelId));
        assertFalse(LlmTransport.isHighEntropySecretToken(camelV2));

        String javaSource = String.join("\n",
                "package com.acme.security;",
                "import com.evolution.backend.query.ArchitectureQueryApi;",
                "// Production key " + highEntropyProse + " must never reach fts5.db",
                "// Safe prose: basic misunderstanding and Basic AuthenticationProvider",
                "public class PaymentGatewayConfig {",
                "    static final String DIGEST = \"" + sha256Digest + "\";",
                "    static final String HEX40 = \"" + hex40 + "\";",
                "    static final String HEX64 = \"" + hex64 + "\";",
                "    static final String CORRELATION_ID = \"" + uuidVal + "\";",
                "    static final String ENDPOINT = \"" + safeUrl + "\";",
                "    static final String FQN = \"" + javaFqn + "\";",
                "    static final String CALL = \"" + twoSegmentFqn + "\";",
                "    static final String BEAN = \"" + camelId + "\";",
                "    static final String MODEL = \"" + camelV2 + "\";",
                "    static final String CUSTOM_SIGNING_VAL = \"" + highEntropyQuoted + "\";",
                "    static final String ESCAPED_JSON = \"{\\\"token\\\":\\\"" + highEntropyJsonEscaped + "\\\"}\";",
                "    static final String BASIC_HEADER = \"Authorization: Basic " + basicAuthB64 + "\";",
                "    static final String GH_TOKEN = \"" + ghSecret + "\";",
                "}");
        String envSource = "APP_MODE=prod\nCUSTOM_HMAC_MATERIAL=" + highEntropyAssigned + "\nSAFE_URL=" + safeUrl + "\n";
        String mdProseSource = "# Auth Notes\nNever paste bare secrets like " + highEntropyProse + " or Basic " + basicAuthB64 + " in docs.\n";

        var redactedJava = LlmTransport.redactDocumentForPath("src/main/java/com/acme/security/PaymentGatewayConfig.java", javaSource);
        assertFalse(redactedJava.text().contains(highEntropyQuoted), "Quoted high-entropy token must be redacted");
        assertFalse(redactedJava.text().contains(highEntropyProse), "Comment prose high-entropy token must be redacted");
        assertFalse(redactedJava.text().contains(highEntropyJsonEscaped), "JSON-escaped high-entropy token must be redacted");
        assertFalse(redactedJava.text().contains(basicAuthB64), "Authorization: Basic token must be redacted");
        assertFalse(redactedJava.text().contains(ghSecret), "Known GitHub token must be redacted");
        assertTrue(redactedJava.totalRedactedCount() >= 5);
        // Zero false positives on hashes, UUIDs, URLs, FQNs (2+ segments), imports, camelCase, and normal 'basic' prose
        assertTrue(redactedJava.text().contains(sha256Digest));
        assertTrue(redactedJava.text().contains(hex40));
        assertTrue(redactedJava.text().contains(hex64));
        assertTrue(redactedJava.text().contains(uuidVal));
        assertTrue(redactedJava.text().contains(safeUrl));
        assertTrue(redactedJava.text().contains(javaFqn));
        assertTrue(redactedJava.text().contains(twoSegmentFqn));
        assertTrue(redactedJava.text().contains(camelId));
        assertTrue(redactedJava.text().contains(camelV2));
        assertTrue(redactedJava.text().contains("basic misunderstanding"));
        assertTrue(redactedJava.text().contains("Basic AuthenticationProvider"));

        var graph = graphWithCapturedFiles(Map.of(
                "src/main/java/com/acme/security/PaymentGatewayConfig.java", javaSource,
                ".env", envSource,
                "docs/auth-notes.md", mdProseSource));
        Path ftsDb = tempDir.resolve("search").resolve("fts5.db");
        try (var index = CapturedSearchIndex.openReadWrite(ftsDb)) {
            var res = index.codeSearch(graph, "PaymentGatewayConfig", List.of(), true, 10, 0, null).orElseThrow();
            assertEquals(CapturedSearchIndex.ENGINE_SQLITE_FTS5, res.engine());
            assertTrue(res.redactedSecretCount() >= 8, "Expected >=8 redacted secrets across Java + .env + Markdown");
            assertFalse(res.truncated(), "Normal non-ceiling query must have truncated=false");
            assertFalse(res.items().isEmpty());
            assertTrue(res.items().stream().noneMatch(h -> h.snippet().contains(highEntropyQuoted)));
            assertTrue(res.items().stream().noneMatch(h -> h.snippet().contains(highEntropyAssigned)));
            assertTrue(res.items().stream().noneMatch(h -> h.snippet().contains(highEntropyProse)));
            assertTrue(res.items().stream().noneMatch(h -> h.snippet().contains(highEntropyJsonEscaped)));
            assertTrue(res.items().stream().noneMatch(h -> h.snippet().contains(basicAuthB64)));
            assertTrue(res.items().stream().noneMatch(h -> h.snippet().contains(ghSecret)));
        }

        // Byte-level verification: ZERO raw secrets persisted anywhere in fts5.db / -wal / -shm!
        for (String name : List.of("fts5.db", "fts5.db-wal", "fts5.db-shm")) {
            Path p = tempDir.resolve("search").resolve(name);
            if (!Files.exists(p)) continue;
            String rawDisk = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            assertFalse(rawDisk.contains(highEntropyQuoted), "Raw quoted secret leaked in " + name);
            assertFalse(rawDisk.contains(highEntropyAssigned), "Raw assigned secret leaked in " + name);
            assertFalse(rawDisk.contains(highEntropyProse), "Raw prose secret leaked in " + name);
            assertFalse(rawDisk.contains(highEntropyJsonEscaped), "Raw JSON-escaped secret leaked in " + name);
            assertFalse(rawDisk.contains(basicAuthB64), "Raw Basic auth secret leaked in " + name);
            assertFalse(rawDisk.contains(ghSecret), "Raw GitHub secret leaked in " + name);
        }

        // Raw CAS bytes for CapturedRepair patch anchors remain 100% unmutated
        var fileIndex = CapturedFileIndex.forGraph(graph);
        assertEquals(javaSource, fileIndex.readText(graph,
                fileIndex.findExact("src/main/java/com/acme/security/PaymentGatewayConfig.java").orElseThrow()).orElseThrow());
        assertEquals(envSource, fileIndex.readText(graph, fileIndex.findExact(".env").orElseThrow()).orElseThrow());
    }

    @Test
    void fts5Bm25SupportsCamelSnakeCaseAdvisoryFilteringAndHostileQuerySyntaxSafety(@TempDir Path tempDir) throws Exception {
        var graph = buildRichHybridGraph();
        Path ftsDb = tempDir.resolve("search").resolve("fts5.db");

        try (var index = CapturedSearchIndex.openReadWrite(ftsDb)) {
            // 1. snake_case query finds camelCase Java class & Python snake_case worker
            var res = index.codeSearch(graph, "invoice_reconciliation", List.of(), true, 10, 0, null).orElseThrow();
            assertEquals(CapturedSearchIndex.ENGINE_SQLITE_FTS5, res.engine());
            assertTrue(res.totalHits() >= 2, "Expected hits across Java and Python: " + res.items());
            assertTrue(res.items().stream().anyMatch(h -> h.path().endsWith("InvoiceReconciliationEngine.java")
                    && h.trustTier() == TrustTier.COMPILER && !h.isAdvisory()
                    && h.contentSha256().startsWith("sha256:") && h.startLine() >= 1 && h.endLine() >= h.startLine()));

            // Verify pagination does NOT falsely set truncated=true when below MAX_MATCH_ROWS ceiling
            var page1 = index.codeSearch(graph, "invoice_reconciliation", List.of(), true, 1, 0, null).orElseThrow();
            assertTrue(page1.totalHits() >= 2);
            assertEquals(1, page1.items().size());
            assertFalse(page1.truncated(), "Normal multi-page pagination below MAX_MATCH_ROWS must not set truncated=true");

            // 2. Language filtering
            var pyOnly = index.codeSearch(graph, "InvoiceReconciliation", List.of("python"), true, 10, 0, null).orElseThrow();
            assertFalse(pyOnly.items().isEmpty());
            assertTrue(pyOnly.items().stream().allMatch(h -> "python".equals(h.language())));

            // 3. Advisory filtering (SQL / Markdown / Terraform carry ADVISORY_UNVERIFIED)
            var withAdv = index.codeSearch(graph, "ledger_settlement_table", List.of(), true, 10, 0, null).orElseThrow();
            assertTrue(withAdv.items().stream().anyMatch(h -> h.isAdvisory() && h.trustTier() == TrustTier.ADVISORY_UNVERIFIED));
            var noAdv = index.codeSearch(graph, "ledger_settlement_table", List.of(), false, 10, 0, null).orElseThrow();
            assertTrue(noAdv.items().stream().noneMatch(CapturedSearchIndex.CodeSearchHit::isAdvisory));

            // 4. Hostile FTS5 queries NEVER throw SQLite syntax errors
            List<String> hostileQueries = List.of(
                    "\"unclosed quote",
                    "*** --- ::: () ///",
                    "NOT OR AND NEAR/3 (foo:bar* -baz/qux)",
                    "path:InvoiceReconciliationEngine NEAR \"unmatched",
                    "SELECT * FROM code_chunks; DROP TABLE code_chunks;--",
                    "((invoice* OR NOT -reconciliation)) / \\ ^ ~ ! ? @ # $ % &");
            for (String hostile : hostileQueries) {
                var safeRes = assertDoesNotThrow(
                        () -> index.codeSearch(graph, hostile, List.of(), true, 10, 0, null).orElseThrow(),
                        "Hostile FTS5 query must never throw: " + hostile);
                assertEquals(CapturedSearchIndex.ENGINE_SQLITE_FTS5, safeRes.engine());
            }
        }
    }

    @Test
    void indexesMoreThan500FilesWithHonestOmittedFilesAccountingAndNoRepoMapCliff(@TempDir Path tempDir) throws Exception {
        var textFiles = new LinkedHashMap<String, String>();
        for (int i = 0; i < 550; i++) {
            String name = String.format("src/main/java/com/acme/gen/ModuleFile%03d.java", i);
            String marker = (i == 545) ? "UltraRareSettlementMarker" : ("StandardRoutine" + i);
            textFiles.put(name, "package com.acme.gen;\npublic class ModuleFile" + String.format("%03d", i)
                    + " {\n    void execute() { String tag = \"" + marker + "\"; }\n}\n");
        }
        var binaryFiles = new LinkedHashMap<String, byte[]>();
        binaryFiles.put("assets/logo.png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x00, 0x01});
        binaryFiles.put("bin/raw.dat", new byte[]{0x41, 0x42, 0x00, 0x43, 0x44});
        byte[] oversized = new byte[(int) CapturedSearchIndex.MAX_INDEXABLE_FILE_BYTES + 128];
        Arrays.fill(oversized, (byte) 'a');
        binaryFiles.put("data/huge.txt", oversized);

        var graph = graphWithCapturedFilesAndBinaries(textFiles, binaryFiles);
        Path ftsDb = tempDir.resolve("search").resolve("fts5.db");

        try (var index = CapturedSearchIndex.openReadWrite(ftsDb)) {
            var stats = index.ensureIndexed(graph, null).orElseThrow();
            assertEquals(550, stats.fileCount(), "All 550 readable files must be indexed (zero 500-file ceiling)");
            assertEquals(3, stats.omittedFileCount(), "Binary/NUL/oversized files must be counted honestly in omittedFileCount");

            var hitRes = index.codeSearch(graph, "UltraRareSettlementMarker", List.of(), true, 10, 0, null).orElseThrow();
            assertEquals(3, hitRes.omittedFiles());
            assertEquals(1, hitRes.totalHits());
            assertEquals("src/main/java/com/acme/gen/ModuleFile545.java", hitRes.items().getFirst().path());

            // Streaming fallback also has no 500-file ceiling and reports omittedFiles honestly
            var fallback = CapturedFileIndex.forGraph(graph)
                    .codeSearchFallback(graph, "UltraRareSettlementMarker", List.of(), true, 10, 0, null);
            assertEquals(CapturedSearchIndex.ENGINE_STREAMING_FALLBACK, fallback.engine());
            assertEquals(3, fallback.omittedFiles());
            assertEquals(1, fallback.totalHits());
            assertEquals("src/main/java/com/acme/gen/ModuleFile545.java", fallback.items().getFirst().path());

            // RepoMapBuilder also has no 500-file cliff and ranks File545 at #1 via Hybrid RRF
            var repoMap = RepoMapBuilder.build(graph, Optional.empty(), List.of("UltraRareSettlementMarker"),
                    10, 4000, () -> StopReason.NONE, index);
            assertTrue(repoMap.totalFiles() >= 550, "RepoMap must include all 550 readable files, got " + repoMap.totalFiles());
            assertEquals(RepoMapBuilder.RRF_ALGORITHM, repoMap.algorithm());
            assertEquals("src/main/java/com/acme/gen/ModuleFile545.java", repoMap.files().getFirst().path());
            assertEquals(1, repoMap.files().getFirst().bm25Rank());
        }
    }

    @Test
    void hybridRrfMatchesFormulaDeterministicallyAndSurvivesConcurrentRoMcpAndLazyRebuildWithZeroGraphDigestDrift(
            @TempDir Path tempDir) throws Exception {
        var graph = buildRichHybridGraph();
        ContentDigest originalGraphDigest = graph.identity();
        String ctx = graph.contexts().stream().filter(c -> c.analysis().isPresent()).findFirst().orElseThrow().identity();

        Path graphsDir = tempDir.resolve("graphs");
        Files.createDirectories(graphsDir);
        var store = new FileGraphStore(graphsDir);
        store.save(graph);

        Path ftsDb = tempDir.resolve("search").resolve("fts5.db");
        String firstSearchJson;
        String firstRepoMapJson;

        try (var rwIndex = CapturedSearchIndex.openReadWrite(ftsDb)) {
            // Unseeded RepoMap stays backward-compatible personalized-pagerank-v1
            var unseeded = RepoMapBuilder.build(graph, Optional.of(ctx), List.of(), 10, 4000, () -> StopReason.NONE, rwIndex);
            assertEquals("personalized-pagerank-v1", unseeded.algorithm());

            // Seeded on body-only keyword "quantumLedgerChecksum" + symbol "InvoiceReconciliationEngine"
            var rrfMap = RepoMapBuilder.build(graph, Optional.of(ctx),
                    List.of("quantumLedgerChecksum", "InvoiceReconciliationEngine"),
                    10, 4000, () -> StopReason.NONE, rwIndex);
            assertEquals(RepoMapBuilder.RRF_ALGORITHM, rrfMap.algorithm());
            assertFalse(rrfMap.files().isEmpty());

            // Verify exact RRF formula on every ranked item: 1.0/(60+r_PPR) + 1.2/(60+r_BM25) + 0.6/(60+r_Tri)
            for (var fr : rrfMap.files()) {
                assertNotNull(fr.pprRank());
                assertNotNull(fr.rrfScore());
                double expected = 1.0 / (60.0 + fr.pprRank())
                        + (fr.bm25Rank() != null ? 1.2 / (60.0 + fr.bm25Rank()) : 0.0)
                        + (fr.trigramRank() != null ? 0.6 / (60.0 + fr.trigramRank()) : 0.0);
                assertEquals(BigDecimal.valueOf(expected).setScale(6, RoundingMode.HALF_UP), fr.rrfScore(),
                        "RRF formula mismatch for " + fr.path());
                assertEquals(fr.rrfScore(), fr.score());
            }
            // Verify strict monotonic tie-break ordering (rrfScore DESC, path ASC)
            for (int i = 1; i < rrfMap.files().size(); i++) {
                var prev = rrfMap.files().get(i - 1);
                var cur = rrfMap.files().get(i);
                int cmp = prev.rrfScore().compareTo(cur.rrfScore());
                assertTrue(cmp > 0 || (cmp == 0 && prev.path().compareTo(cur.path()) < 0),
                        "RRF tie-break must be (rrfScore DESC, path ASC)");
            }
            // Body-only BM25 hit ("quantumLedgerChecksum" lives inside LedgerAuditService.java) gets surfaced!
            assertTrue(rrfMap.files().stream().anyMatch(f -> f.path().endsWith("LedgerAuditService.java") && f.bm25Rank() != null));

            // RepairContextPlanner focus seeding leverages RRF + FTS5
            var seededFocus = RepairContextPlanner.seedFocusFiles(graph, ctx,
                    List.of("quantumLedgerChecksum"), rwIndex, 3, () -> StopReason.NONE);
            assertTrue(seededFocus.stream().anyMatch(p -> p.endsWith("LedgerAuditService.java")),
                    "RepairContextPlanner focus seeding should discover BM25 body match: " + seededFocus);
            var repairPlan = RepairContextPlanner.plan(graph, ctx, graph.snapshot().value(),
                    List.of(), List.of("quantumLedgerChecksum"), "quantumLedgerChecksum",
                    () -> StopReason.NONE, rwIndex);
            assertTrue(repairPlan.files().stream().anyMatch(pf -> pf.path().endsWith("LedgerAuditService.java")));

            var adHocOutcome = new CapturedRepair(null, rwIndex).proposeAdHoc(
                    new CapturedRepair.AdHocRequest(ctx, "Inspect quantumLedgerChecksum",
                            "Verify quantumLedgerChecksum handling", List.of(), List.of(),
                            CapturedRepair.Strategy.AI, Optional.empty(), 1),
                    graph, null, () -> StopReason.NONE);
            assertEquals("WITHHELD", adHocOutcome.status());
            assertTrue(adHocOutcome.reason().isPresent());
            assertEquals(CapturedRepair.Reason.MODEL_NOT_CONFIGURED, adHocOutcome.reason().orElseThrow());
            assertTrue(adHocOutcome.sources().stream()
                            .anyMatch(f -> f.path().endsWith("LedgerAuditService.java")),
                    "CapturedRepair(model, searchIndex) must thread searchIndex into focus seeding: "
                            + adHocOutcome.sources());

            var searchRes = rwIndex.codeSearch(graph, "quantumLedgerChecksum", List.of(), true, 10, 0, null).orElseThrow();
            firstSearchJson = CanonicalJson.write(searchRes);
            firstRepoMapJson = CanonicalJson.write(rrfMap);

            // Concurrent ?mode=ro read while RW index is STILL OPEN (zero SQLITE_BUSY!)
            try (var roIndex = CapturedSearchIndex.openReadOnly(ftsDb);
                 var directMcp = WorkbenchMcpMain.openDirectStore(tempDir, graph.identity().value(), ctx)) {
                assertTrue(roIndex.isReadOnly());
                var roSearch = roIndex.codeSearch(graph, "quantumLedgerChecksum", List.of(), true, 10, 0, null).orElseThrow();
                assertEquals(firstSearchJson, CanonicalJson.write(roSearch),
                        "Concurrent RO search must match RW search identically");

                // Verify RO SQLite enforces PRAGMA query_only=ON (write attempt fails cleanly)
                try (var roRawConn = DriverManager.getConnection("jdbc:sqlite:" + ftsDb.toUri() + "?mode=ro");
                     var st = roRawConn.createStatement()) {
                    st.execute("PRAGMA query_only=ON");
                    assertThrows(Exception.class, () -> st.executeUpdate("DELETE FROM schema_meta"));
                }

                // Concurrent WorkbenchMcpMain --store executes search_captured_source & repo_map cleanly via RO FTS5
                var mcpResponses = runMcp(directMcp, List.of(
                        msg(1, "initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "m2-test", "version", "1"))),
                        msg(2, "tools/call", Map.of("name", "search_captured_source",
                                "arguments", Map.of("query", "quantumLedgerChecksum"))),
                        msg(3, "tools/call", Map.of("name", "repo_map",
                                "arguments", Map.of("seeds", List.of("quantumLedgerChecksum", "InvoiceReconciliationEngine"))))));
                var searchTool = GraphJson.object(mcpResponses.get(1).get("result"));
                assertEquals(false, searchTool.get("isError"), "MCP search_captured_source failed: " + searchTool);
                String searchToolText = GraphJson.string(GraphJson.object(
                        GraphJson.array(searchTool.get("content")).getFirst()).get("text"));
                assertTrue(searchToolText.contains("SQLITE_FTS5_BM25"), searchToolText);
                assertTrue(searchToolText.contains("LedgerAuditService.java"), searchToolText);

                var mapTool = GraphJson.object(mcpResponses.get(2).get("result"));
                assertEquals(false, mapTool.get("isError"), "MCP repo_map failed: " + mapTool);
                String mapToolText = GraphJson.string(GraphJson.object(
                        GraphJson.array(mapTool.get("content")).getFirst()).get("text"));
                assertTrue(mapToolText.contains("ppr-bm25-trigram-rrf-v2"), mapToolText);
            }
        }

        // Delete fts5.db (+ WAL/SHM) and verify clean lazy rebuild invariance + ZERO canonical graph digest mutation
        Files.deleteIfExists(ftsDb);
        Files.deleteIfExists(tempDir.resolve("search").resolve("fts5.db-wal"));
        Files.deleteIfExists(tempDir.resolve("search").resolve("fts5.db-shm"));
        assertFalse(Files.exists(ftsDb));

        try (var rebuilt = CapturedSearchIndex.openReadWrite(ftsDb)) {
            var rebuiltSearch = rebuilt.codeSearch(graph, "quantumLedgerChecksum", List.of(), true, 10, 0, null).orElseThrow();
            var rebuiltMap = RepoMapBuilder.build(graph, Optional.of(ctx),
                    List.of("quantumLedgerChecksum", "InvoiceReconciliationEngine"),
                    10, 4000, () -> StopReason.NONE, rebuilt);
            assertEquals(firstSearchJson, CanonicalJson.write(rebuiltSearch),
                    "Lazy rebuild after deleting fts5.db must yield identical search results");
            assertEquals(firstRepoMapJson, CanonicalJson.write(rebuiltMap),
                    "Lazy rebuild after deleting fts5.db must yield identical RRF RepoMap");
        }
        assertEquals(originalGraphDigest, graph.identity(),
                "FTS5 indexing and rebuild must NEVER mutate canonical KnowledgeGraph digest");
    }

    @Test
    void httpCodeSearchRouteEnforcesMultiUser401AndPatScopesAndMatchesOpenApi(@TempDir Path tempDir) throws Exception {
        var graph = buildRichHybridGraph();
        Path graphsDir = tempDir.resolve("graphs");
        Files.createDirectories(graphsDir);
        var store = new FileGraphStore(graphsDir);
        store.save(graph);

        // 1. Streaming fallback works cleanly when no disk CapturedSearchIndex is attached
        var fallbackApi = new ArchitectureQueryApi(store);
        var fbResp = fallbackApi.handle("POST", "/api/v1/graphs/" + graph.identity().value() + "/queries/code-search",
                "application/json",
                CanonicalJson.write(Map.of("query", "invoice_reconciliation", "includeAdvisory", false, "limit", 10))
                        .getBytes(StandardCharsets.UTF_8),
                () -> false);
        assertEquals(200, fbResp.status());
        var fbData = GraphJson.object(GraphJson.object(GraphJson.read(
                new String(fbResp.body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults())).get("data"));
        assertEquals(CapturedSearchIndex.ENGINE_STREAMING_FALLBACK, fbData.get("engine"));
        assertTrue(((Number) fbData.get("totalHits")).intValue() >= 1);

        // 2. Live multi-user HTTP server with FTS5 index: enforces 401 + api:read & mcp:read PAT scopes
        Path authDir = tempDir.resolve("auth");
        Path ftsDb = tempDir.resolve("search").resolve("fts5.db");
        try (var accountStore = new UserAccountStore(authDir.resolve("accounts.db"));
             var sessions = new UserSessionStore();
             var searchIndex = CapturedSearchIndex.openReadWrite(ftsDb)) {
            var oauthStore = new GitHubOAuthConfigStore(authDir, k -> null);
            var oauthCfg = new GitHubOAuthService.Config(
                    true, "", "", "github.com", GitHubOAuthService.DEFAULT_SCOPES,
                    false, Optional.empty(), Optional.empty(), Optional.empty());
            var oauthSvc = new GitHubOAuthService(oauthCfg, sessions,
                    (uri, method, headers, body, cancelled) -> { throw new UnsupportedOperationException("offline"); },
                    oauthStore, accountStore);
            var authApi = new AuthHttpApi(oauthSvc, null, ApiLimits.defaults());
            var api = ArchitectureQueryApi.withSearchIndex(
                    ArchitectureQueryApi.withAuth(new ArchitectureQueryApi(store), authApi),
                    searchIndex);

            try (var server = new ArchitectureHttpServer(api, 0)) {
                server.start();
                String root = server.uri().toString();
                var http = HttpClient.newHttpClient();
                String codeSearchUrl = root + "/api/v1/graphs/" + graph.identity().value() + "/queries/code-search";
                String reqJson = CanonicalJson.write(Map.of(
                        "query", "invoice_reconciliation",
                        "languages", List.of("java"),
                        "includeAdvisory", false,
                        "limit", 10,
                        "offset", 0));

                // Unauthenticated request -> 401 AUTH_REQUIRED
                var unauth = http.send(HttpRequest.newBuilder(URI.create(codeSearchUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(reqJson)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(401, unauth.statusCode(), unauth.body());

                // Register user and mint mcp:read + api:read PATs
                var reg = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/register"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"searcher@example.test\",\"password\":\"StrongPass!2026\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(201, reg.statusCode(), reg.body());
                String cookie = reg.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                var regData = GraphJson.object(GraphJson.object(GraphJson.read(reg.body(), GraphJson.Limits.defaults())).get("data"));
                String csrf = GraphJson.string(regData.get("csrfToken"));

                String mcpPat = mintPat(http, root, cookie, csrf, "mcp-search", List.of("mcp:read"));
                String apiReadPat = mintPat(http, root, cookie, csrf, "api-search", List.of("api:read"));

                // mcp:read PAT -> 200 SQLITE_FTS5_BM25
                var mcpResp = http.send(HttpRequest.newBuilder(URI.create(codeSearchUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + mcpPat)
                        .POST(HttpRequest.BodyPublishers.ofString(reqJson)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, mcpResp.statusCode(), mcpResp.body());
                var mcpData = GraphJson.object(GraphJson.object(GraphJson.read(mcpResp.body(), GraphJson.Limits.defaults())).get("data"));
                assertEquals(CapturedSearchIndex.ENGINE_SQLITE_FTS5, mcpData.get("engine"));
                assertTrue(((Number) mcpData.get("totalHits")).intValue() >= 1);
                var items = GraphJson.array(mcpData.get("items"));
                assertFalse(items.isEmpty());
                var firstItem = GraphJson.object(items.getFirst());
                assertEquals("java", firstItem.get("language"));
                assertEquals("COMPILER", firstItem.get("trustTier"));
                assertEquals(Boolean.FALSE, firstItem.get("isAdvisory"));
                assertTrue(GraphJson.string(firstItem.get("contentSha256")).startsWith("sha256:"));

                // api:read PAT -> 200 SQLITE_FTS5_BM25
                var apiResp = http.send(HttpRequest.newBuilder(URI.create(codeSearchUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiReadPat)
                        .POST(HttpRequest.BodyPublishers.ofString(reqJson)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, apiResp.statusCode(), apiResp.body());
            }
        }

        // 3. OpenAPI parity verification for code-search and updated repo-map
        try (var input = getClass().getResourceAsStream("/api/openapi.yaml")) {
            assertNotNull(input);
            @SuppressWarnings("unchecked")
            var spec = (Map<String, Object>) new Yaml().load(input);
            @SuppressWarnings("unchecked")
            var paths = (Map<String, Object>) spec.get("paths");
            assertTrue(paths.containsKey("/api/v1/graphs/{graph}/queries/code-search"),
                    "OpenAPI must register /api/v1/graphs/{graph}/queries/code-search");
            @SuppressWarnings("unchecked")
            var repoMapPath = (Map<String, Object>) paths.get("/api/v1/graphs/{graph}/repo-map");
            @SuppressWarnings("unchecked")
            var repoMapPost = (Map<String, Object>) repoMapPath.get("post");
            String repoDesc = String.valueOf(repoMapPost.get("description"));
            assertTrue(repoDesc.contains("ppr-bm25-trigram-rrf-v2") && repoDesc.contains("rrfScore"), repoDesc);
        }
    }

    private static String mintPat(HttpClient http, String root, String cookie, String csrf,
                                  String label, List<String> scopes) throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/tokens"))
                .header("Content-Type", "application/json")
                .header("Cookie", cookie)
                .header("X-Evo-CSRF", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(
                        CanonicalJson.write(Map.of("label", label, "scopes", scopes))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp.statusCode(), resp.body());
        var data = GraphJson.object(GraphJson.object(GraphJson.read(resp.body(), GraphJson.Limits.defaults())).get("data"));
        return GraphJson.string(data.get("token"));
    }

    private static KnowledgeGraph buildRichHybridGraph() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/m2-hybrid.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("m2-hybrid-snap"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("m2-hybrid-mod");
        String ctx = analysis.value();
        var mod = ModuleIdentity.from(repo, ".");

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", mod, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", mod, "path", "."));
        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv, modEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        nodes.add(new KnowledgeGraph.Node(mod.value(), ctx, "MODULE", List.of(modEv.identity())));

        var files = new LinkedHashMap<String, String>();
        files.put("src/main/java/com/acme/billing/InvoiceReconciliationEngine.java", """
                package com.acme.billing;
                public class InvoiceReconciliationEngine {
                    private final LedgerAuditService audit;
                    public InvoiceReconciliationEngine(LedgerAuditService audit) { this.audit = audit; }
                    public void reconcileInvoices() { audit.verifyChecksum(); }
                }
                """);
        files.put("src/main/java/com/acme/billing/LedgerAuditService.java", """
                package com.acme.billing;
                public class LedgerAuditService {
                    public String verifyChecksum() {
                        return "quantumLedgerChecksum-verified";
                    }
                }
                """);
        files.put("src/main/java/com/acme/billing/OrderController.java", """
                package com.acme.billing;
                public class OrderController {
                    private final InvoiceReconciliationEngine engine;
                    public OrderController(InvoiceReconciliationEngine engine) { this.engine = engine; }
                    public void handle() { engine.reconcileInvoices(); }
                }
                """);
        files.put("workers/invoice_reconciliation_worker.py", """
                def run_invoice_reconciliation(batch_id):
                    return {"status": "ok", "batch": batch_id}
                """);
        files.put("db/migrations/V1__ledger.sql", """
                CREATE TABLE ledger_settlement_table (
                    id BIGINT PRIMARY KEY,
                    amount NUMERIC NOT NULL
                );
                """);
        files.put("docs/ADR-005-ledger.md", """
                # ADR-005 Ledger Settlement
                ## Audit Checksum
                Uses `ledger_settlement_table` and `quantumLedgerChecksum`.
                """);

        var docIds = new HashMap<String, String>();
        for (var e : files.entrySet()) {
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            var digest = ContentDigest.sha256(bytes);
            var ev = GraphEvidence.capture("source.bytes", Map.of(
                    "document", Map.of("path", e.getKey(), "contentDigest", digest.value()),
                    "size", bytes.length,
                    "bytesBase64", Base64.getEncoder().encodeToString(bytes)));
            evidence.add(ev);
            String docId = KnowledgeGraph.derivedIdentity("source-doc", List.of(e.getKey()));
            docIds.put(e.getKey(), docId);
            nodes.add(new KnowledgeGraph.Node(docId, snap.value(), "SOURCE_DOCUMENT", List.of(ev.identity())));
        }

        var typeIds = new LinkedHashMap<String, String>();
        for (String cls : List.of("OrderController", "InvoiceReconciliationEngine", "LedgerAuditService")) {
            String path = "src/main/java/com/acme/billing/" + cls + ".java";
            var ent = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                    JavaSymbolName.topLevelType("com.acme.billing", cls).canonicalName(), Optional.empty());
            var decl = GraphEvidence.capture("java.declaration", Map.of(
                    "entity", Map.of(
                            "identity", ent.identity().value(),
                            "origin", "PROJECT",
                            "kind", "TYPE",
                            "canonicalName", ent.canonicalName(),
                            "declaration", Map.of("document", docIds.get(path))),
                    "spelling", cls,
                    "status", "RESOLVED"));
            evidence.add(decl);
            nodes.add(new KnowledgeGraph.Node(ent.identity().value(), ctx, "TYPE", List.of(decl.identity())));
            typeIds.put(cls, ent.identity().value());
        }

        for (var pair : List.of(
                List.of("OrderController", "InvoiceReconciliationEngine"),
                List.of("InvoiceReconciliationEngine", "LedgerAuditService"))) {
            String src = typeIds.get(pair.get(0)), dst = typeIds.get(pair.get(1));
            var refEv = GraphEvidence.capture("java.reference", Map.of("from", src, "to", dst));
            evidence.add(refEv);
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("dep", pair), ctx, "graph.depends-on", src,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                    List.of(refEv.identity()), Optional.empty()));
        }

        return new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(ctx, snap, Optional.of(analysis), manEv.identity())),
                nodes, edges, evidence);
    }

    private static KnowledgeGraph graphWithCapturedFiles(Map<String, String> files) {
        return graphWithCapturedFilesAndBinaries(files, Map.of());
    }

    private static KnowledgeGraph graphWithCapturedFilesAndBinaries(Map<String, String> textFiles,
                                                                    Map<String, byte[]> binaryFiles) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/m2-files.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("m2-snap-" + textFiles.size() + "-" + binaryFiles.size()));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("m2-files-mod");
        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of()));
        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();

        for (var e : textFiles.entrySet()) {
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            var digest = ContentDigest.sha256(bytes);
            var ev = GraphEvidence.capture("source.bytes", Map.of(
                    "document", Map.of("path", e.getKey(), "contentDigest", digest.value()),
                    "size", bytes.length,
                    "bytesBase64", Base64.getEncoder().encodeToString(bytes)));
            evidence.add(ev);
            nodes.add(new KnowledgeGraph.Node(KnowledgeGraph.derivedIdentity("source-doc", List.of(e.getKey())),
                    snap.value(), "SOURCE_DOCUMENT", List.of(ev.identity())));
        }
        for (var e : binaryFiles.entrySet()) {
            byte[] bytes = e.getValue();
            var digest = ContentDigest.sha256(bytes);
            var ev = GraphEvidence.capture("source.bytes", Map.of(
                    "document", Map.of("path", e.getKey(), "contentDigest", digest.value()),
                    "size", bytes.length,
                    "bytesBase64", Base64.getEncoder().encodeToString(bytes)));
            evidence.add(ev);
            nodes.add(new KnowledgeGraph.Node(KnowledgeGraph.derivedIdentity("source-doc", List.of(e.getKey())),
                    snap.value(), "SOURCE_DOCUMENT", List.of(ev.identity())));
        }
        return new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(analysis.value(), snap, Optional.of(analysis), manEv.identity())),
                nodes, List.of(), evidence);
    }

    private static Map<String, Object> msg(int id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private static List<Map<String, Object>> runMcp(WorkbenchMcpMain mcp, List<Map<String, Object>> calls) throws Exception {
        var input = new StringReader(String.join("\n", calls.stream().map(CanonicalJson::write).toList()) + "\n");
        var output = new StringWriter();
        mcp.run(input, new PrintWriter(output, true));
        return output.toString().lines()
                .filter(l -> !l.isBlank())
                .map(l -> GraphJson.object(GraphJson.read(l, new GraphJson.Limits(16_777_216, 64, 250_000))))
                .toList();
    }
}
