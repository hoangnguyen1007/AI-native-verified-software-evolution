package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.metrics.ArchitectureMetricsService;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.analysis.query.SymbolEgoGraphExtractor;
import com.evolution.backend.analysis.StaticPolyglotScan;
import com.evolution.backend.query.search.CapturedSearchIndex;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end WP3.1 verification covering:
 * <ul>
 *   <li>2-pass {@code --mode=syntax-fast} structural indexing ({@link StaticPolyglotScan#buildSyntaxFastGraph})</li>
 *   <li>Decoupled 100% FTS5 coverage ({@link CapturedSearchIndex#indexCapturedSources} &amp; {@code ensureIndexedDecoupled})</li>
 *   <li>FQN-safe {@code InterfaceConsumerExpand} + {@code [SKG TOPOLOGY]} header in {@link RepoMapBuilder}</li>
 *   <li>2-phase {@link SymbolEgoGraphExtractor} wiring (Localization + Patch Generation) in {@link CapturedRepair#proposeAdHoc}</li>
 * </ul>
 */
class SyntaxFastAndEgoGraphIntegrationTest {

    @Test
    void syntaxFastGraphDrivesInterfaceConsumerExpandDecoupledFts5AndTwoPhaseCapturedRepair(@TempDir Path tempDir)
            throws Exception {
        var files = new LinkedHashMap<String, byte[]>();
        files.put("src/main/java/com/acme/billing/PaymentProcessor.java", """
                package com.acme.billing;
                public interface PaymentProcessor {
                    boolean charge(String orderId);
                }
                """.getBytes(StandardCharsets.UTF_8));
        files.put("src/main/java/com/acme/billing/StripePaymentProcessorImpl.java", """
                package com.acme.billing;
                import org.springframework.stereotype.Service;
                @Service
                public class StripePaymentProcessorImpl implements PaymentProcessor {
                    @Override
                    public boolean charge(String orderId) {
                        return orderId != null && !orderId.isBlank();
                    }
                }
                """.getBytes(StandardCharsets.UTF_8));
        files.put("src/main/java/com/acme/web/CheckoutController.java", """
                package com.acme.web;
                import com.acme.billing.PaymentProcessor;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class CheckoutController {
                    private final PaymentProcessor processor;
                    public CheckoutController(PaymentProcessor processor) {
                        this.processor = processor;
                    }
                    public boolean submitOrder(String orderId) {
                        return processor.charge(orderId);
                    }
                }
                """.getBytes(StandardCharsets.UTF_8));
        // Duplicate simple name in another package to verify FQN-safe disambiguation (never overwrites!)
        files.put("src/main/java/com/acme/legacy/PaymentProcessor.java", """
                package com.acme.legacy;
                public interface PaymentProcessor {
                    void legacyCharge();
                }
                """.getBytes(StandardCharsets.UTF_8));
        files.put("src/main/java/com/acme/legacy/LegacyController.java", """
                package com.acme.legacy;
                public class LegacyController {
                    private final PaymentProcessor legacyProc;
                    public LegacyController(PaymentProcessor legacyProc) {
                        this.legacyProc = legacyProc;
                    }
                }
                """.getBytes(StandardCharsets.UTF_8));
        // Polyglot Python helper alongside Java
        files.put("workers/reconcile_worker.py", """
                class BaseWorker:
                    pass

                class BillingWorker(BaseWorker):
                    def run_batch(self):
                        return self.notify()

                    def notify(self):
                        return True
                """.getBytes(StandardCharsets.UTF_8));

        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/wp31-e2e.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("wp31-e2e-snap"));

        // 1. Build --mode=syntax-fast KnowledgeGraph (2-pass parallel, TrustTier.SOURCE_SYNTAX)
        var scanner = new StaticPolyglotScan();
        KnowledgeGraph graph = scanner.buildSyntaxFastGraph(repo, snap, files, () -> false);
        assertEquals(graph.identity(), scanner.buildSyntaxFastGraph(repo, snap, files, () -> false).identity(),
                "syntax-fast graph build must be bit-identical deterministic (I2)");

        // Verify FTS5 indexes 100.0% of captured files cleanly
        Path ftsDb = tempDir.resolve("search").resolve("fts5.db");
        try (var searchIndex = CapturedSearchIndex.openReadWrite(ftsDb)) {
            var stats = searchIndex.ensureIndexedDecoupled(graph, files, () -> StopReason.NONE).orElseThrow();
            assertEquals(files.size(), stats.fileCount(), "100% of captured files must be indexed in FTS5");
            assertEquals(0, stats.omittedFileCount());

            // 2. RepoMapBuilder FQN-safe InterfaceConsumerExpand:
            // Seeding ONLY on concrete StripePaymentProcessorImpl MUST recover upstream CheckoutController
            // via com.acme.billing.PaymentProcessor and MUST NOT pull com.acme.legacy.LegacyController!
            var repoMap = RepoMapBuilder.build(
                    graph,
                    Optional.of(snap.value()),
                    List.of("StripePaymentProcessorImpl"),
                    3,
                    4_000,
                    () -> StopReason.NONE,
                    searchIndex);

            assertFalse(repoMap.topologyExpansions().isEmpty(),
                    "Expected InterfaceConsumerExpand to recover upstream consumer via interface");
            assertTrue(repoMap.topologyExpansions().stream().anyMatch(e ->
                            e.concreteFqn().equals("com.acme.billing.StripePaymentProcessorImpl")
                                    && e.interfaceFqn().equals("com.acme.billing.PaymentProcessor")
                                    && e.consumerFqn().equals("com.acme.web.CheckoutController")
                                    && "SOURCE_SYNTAX".equals(e.trustTier())),
                    "Expected exact FQN chain StripePaymentProcessorImpl -> PaymentProcessor <- CheckoutController, got: "
                            + repoMap.topologyExpansions());
            assertTrue(repoMap.topologyExpansions().stream().noneMatch(e ->
                            e.consumerFqn().equals("com.acme.legacy.LegacyController")),
                    "FQN-safe expansion must NEVER cross-wire duplicate simple names across packages");
            assertTrue(repoMap.files().stream().anyMatch(f -> f.path().equals("src/main/java/com/acme/web/CheckoutController.java")),
                    "Recovered upstream controller must be promoted into prioritized RepoMap files: " + repoMap.files());
            assertTrue(repoMap.renderedMap().contains(RepoMapBuilder.SKG_TOPOLOGY_HEADER),
                    "Rendered map must include explicit [SKG TOPOLOGY] header");
            assertTrue(repoMap.renderedMap().contains("src/main/java/com/acme/web/CheckoutController.java:"),
                    "[SKG TOPOLOGY] header must carry file:line provenance");

            // 3. CapturedRepair.proposeAdHoc wires SymbolEgoGraphExtractor into BOTH Localization and Patch Generation
            var capturedPromptJson = new AtomicReference<String>("");
            var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                mock.createContext("/v1/chat/completions", ex -> {
                    try (ex) {
                        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        var reqObj = GraphJson.object(GraphJson.read(body, GraphJson.Limits.defaults()));
                        var msgs = GraphJson.array(reqObj.get("messages"));
                        for (Object m : msgs) {
                            var mo = GraphJson.object(m);
                            if ("user".equals(mo.get("role"))) {
                                capturedPromptJson.set(GraphJson.string(mo.get("content")));
                            }
                        }
                        String content = "FILE: src/main/java/com/acme/billing/StripePaymentProcessorImpl.java\n"
                                + "<<<<<<< SEARCH\n"
                                + "        return orderId != null && !orderId.isBlank();\n"
                                + "=======\n"
                                + "        return orderId != null && orderId.trim().length() > 0;\n"
                                + ">>>>>>> REPLACE\n";
                        byte[] out = CanonicalJson.write(Map.of(
                                "model", "ego-repair-test",
                                "choices", List.of(Map.of(
                                        "finish_reason", "stop",
                                        "message", Map.of("role", "assistant", "content", content))))).getBytes(StandardCharsets.UTF_8);
                        ex.getResponseHeaders().add("Content-Type", "application/json");
                        ex.sendResponseHeaders(200, out.length);
                        ex.getResponseBody().write(out);
                    }
                });
                mock.start();

                var model = new RepairChatModel(URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1"),
                        "ego-repair-test", null, false);
                var repair = new CapturedRepair(model, searchIndex);
                var queries = new ArchitectureMetricsService(graph).queries();

                // Pass EMPTY targetPaths + targetSymbols=["StripePaymentProcessorImpl"] so Phase 1 Localization MUST run!
                var outcome = repair.proposeAdHoc(
                        new CapturedRepair.AdHocRequest(
                                snap.value(),
                                "Harden StripePaymentProcessorImpl validation",
                                "Ensure StripePaymentProcessorImpl trims blank order IDs",
                                List.of(),
                                List.of("StripePaymentProcessorImpl"),
                                CapturedRepair.Strategy.AI,
                                Optional.empty(),
                                1),
                        graph, queries, () -> StopReason.NONE);

                assertEquals("PROPOSED", outcome.status(), () -> "Outcome=" + outcome);
                assertTrue(outcome.sources().stream()
                                .anyMatch(s -> s.path().equals("src/main/java/com/acme/billing/StripePaymentProcessorImpl.java")),
                        "Phase 1 Localization via SymbolEgoGraphExtractor must localize StripePaymentProcessorImpl.java");

                // Phase 2 Patch Generation prompt MUST contain symbolEgoGraph with k=1 FLATTENED_1HOP <= 2500 tokens!
                String prompt = capturedPromptJson.get();
                assertFalse(prompt.isBlank(), "Expected LLM prompt JSON");
                var promptObj = GraphJson.object(GraphJson.read(prompt, GraphJson.Limits.defaults()));
                assertTrue(promptObj.containsKey("symbolEgoGraph"),
                        "Phase 2 Patch Generation prompt must inject symbolEgoGraph: keys=" + promptObj.keySet());
                var egoList = GraphJson.array(promptObj.get("symbolEgoGraph"));
                assertFalse(egoList.isEmpty());
                var ego0 = GraphJson.object(egoList.getFirst());
                assertEquals(SymbolEgoGraphExtractor.ExtractionMode.FLATTENED_1HOP.name(), ego0.get("mode"));
                assertEquals(1, ((Number) ego0.get("effectiveHops")).intValue());
                assertTrue(((Number) ego0.get("estimatedTokens")).intValue() <= SymbolEgoGraphExtractor.HARD_MAX_TOKENS);
                assertTrue(GraphJson.string(ego0.get("renderedContext")).contains("com.acme.billing.StripePaymentProcessorImpl"));
                assertTrue(CapturedRepair.authentic(outcome.proposal().orElseThrow()));
            } finally {
                mock.stop(0);
            }
        }
    }

    @Test
    void decoupledFts5Maintains100PercentCoverageWhenSemanticStageFailsOrFilesHaveLegacyEncoding(@TempDir Path tempDir)
            throws Exception {
        // Construct a synthetic secret via String.join (zero raw secret literals)
        String syntheticSecret = String.join("_", "ghp", "abcdef1234567890abcdef1234567890abcd");
        var files = new LinkedHashMap<String, byte[]>();
        files.put("src/main/java/com/acme/CleanService.java",
                ("package com.acme;\npublic class CleanService { String key = \"" + syntheticSecret + "\"; }\n")
                        .getBytes(StandardCharsets.UTF_8));
        // File with ISO-8859-1 copyright / accented comment (historically dropped by strict UTF-8 decoder!)
        files.put("src/main/java/com/acme/LegacyEncodingService.java",
                "package com.acme;\n// Copyright \u00A9 Jos\u00E9\npublic class LegacyEncodingService { void uniqueLatinMarker() {} }\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
        files.put("scripts/helper.py",
                "def sync_catalog_delta():\n    return 42\n".getBytes(StandardCharsets.UTF_8));

        Path ftsDb = tempDir.resolve("search").resolve("decoupled-fts5.db");
        try (var index = CapturedSearchIndex.openReadWrite(ftsDb)) {
            // Direct decoupled indexing requires ZERO semantic graph stage
            var stats = index.indexCapturedSources("syntax-fast:key1", "snap:1", files, () -> StopReason.NONE)
                    .orElseThrow();
            assertEquals(3, stats.fileCount(), "100.0% of text source files must be indexed including legacy-encoded Java");
            assertEquals(0, stats.omittedFileCount());
            assertTrue(stats.redactedSecretCount() >= 1, "Pre-redaction must still run in decoupled mode");

            var latinHit = index.codeSearchByIndexKey("syntax-fast:key1", "uniqueLatinMarker",
                    List.of(), "", true, 10, 0, () -> StopReason.NONE).orElseThrow();
            assertEquals(1, latinHit.totalHits());
            assertEquals("src/main/java/com/acme/LegacyEncodingService.java", latinHit.items().getFirst().path());

            var cleanHit = index.codeSearchByIndexKey("syntax-fast:key1", "CleanService",
                    List.of(), "", true, 10, 0, () -> StopReason.NONE).orElseThrow();
            assertEquals(1, cleanHit.totalHits());
            assertFalse(cleanHit.items().getFirst().snippet().contains(syntheticSecret),
                    "Decoupled FTS5 must NEVER leak unredacted secrets");
        }
        assertEquals(5_000, RepoMapBuilder.MAX_FILE_SEED_CAP);
        assertEquals(5_000, CapturedSearchIndex.MAX_RANK_FILES_CAP);
    }
}
