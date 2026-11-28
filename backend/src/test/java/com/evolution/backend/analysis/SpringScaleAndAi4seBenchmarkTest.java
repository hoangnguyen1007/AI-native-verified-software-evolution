package com.evolution.backend.analysis;

import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2Service;
import com.evolution.analysis.assessment.v2.ArchitectureScoreModelV2;
import com.evolution.analysis.assessment.v2.LeidenCommunityDetector;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.semantic.Entity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.metrics.ArchitectureMetric;
import com.evolution.analysis.metrics.ArchitectureMetricsService;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.analysis.query.projection.GraphProjectionService;
import com.evolution.backend.query.PublishedReports;
import com.evolution.backend.query.RepairSource;
import com.evolution.backend.query.RepoMapBuilder;
import com.evolution.backend.query.advisory.AdvisoryOverlay;
import com.evolution.backend.query.advisory.AdvisoryOverlayService;
import com.evolution.backend.query.llm.LlmTransport;
import com.evolution.backend.query.repair.AffectedTestSelector;
import com.evolution.backend.query.search.CapturedSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E9 AI4SE Empirical Evaluation Suite (deterministic, zero-network, reproducible):
 * <ul>
 *   <li><b>[E9-SYNTHETIC-SKG-RTS]</b> Synthetic multi-architecture unit/ablation benchmark across 5 constructed
 *       multi-module software architectures (&gt;= 40 production modules, &gt;= 80 test classes) comparing
 *       {@link AffectedTestSelector} against exhaustive reverse-DFS ground truth.</li>
 *   <li><b>[E9-SYNTHETIC-QUARANTINE]</b> End-to-end Quarantine Invariance proof on real passive analysis + Leiden
 *       community detection before and after mutating {@code .sql}, {@code .tf}, and {@code .md} files.</li>
 *   <li><b>[E9-SYNTHETIC-REDACT-RRF]</b> Synthetic unit/ablation benchmark for Shannon-entropy secret guard
 *       ({@code llm-redact:v2}) across 15 credential patterns + Hybrid RRF ({@code ppr-bm25-trigram-rrf-v2}).</li>
 *   <li><b>[E9-REAL-REPO-*]</b> Distinct <b>real-repository</b> empirical evaluation running full passive
 *       {@link LocalAnalysisRunner} analysis over actual locally available Java/Spring repositories
 *       (e.g. {@code spring-petclinic}, {@code ewolff/microservice}, and this workspace's real {@code analyzer-maven}
 *       module) with honest closed-denominator RTS, Hybrid RRF (Recall@5/Recall@10/MRR@10), and full-corpus
 *       secret-redaction + canary disk-leak metrics.</li>
 * </ul>
 */
class SpringScaleAndAi4seBenchmarkTest {

    @TempDir
    Path temp;

    // =========================================================================
    // 1. [E9-SKG-RTS] Multi-Architecture Selective Regression Testing Benchmark
    // =========================================================================

    @Test
    void skgRtsBenchmarkAcrossFiveMultiModuleArchitecturesAchieves100PercentClosureSoundnessAndFallbackSafety() {
        var architectures = List.of(
                buildMicroserviceMeshArchitecture(),
                buildLayeredEnterpriseMonolithArchitecture(),
                buildSharedKernelMultiModuleArchitecture(),
                buildHighFanoutUtilityLibraryArchitecture(),
                buildSafetyFallbackArchitecture());

        int totalModules = 0;
        int totalTestClasses = 0;
        int totalCodeEdits = 0;
        int totalMissedTests = 0;
        double sumCodeReductionPct = 0.0;
        int safetyFallbackTriggers = 0;
        int safetyFallbackPassed = 0;

        for (var arch : architectures) {
            totalModules += arch.moduleCount();
            totalTestClasses += arch.testPaths().size();

            int archMissed = 0;
            double archRedSum = 0.0;
            double archSelectedSum = 0.0;

            for (var editPath : arch.codeEdits()) {
                var result = AffectedTestSelector.select(arch.graph(), arch.contextId(), List.of(editPath));
                Set<String> expectedTests = exhaustiveGroundTruthTests(arch, editPath);

                assertEquals(AffectedTestSelector.SelectionMode.SELECTED_TESTS, result.mode(),
                        () -> "Expected SELECTED_TESTS for code edit " + editPath + " in " + arch.name()
                                + ", got " + result.mode() + " (" + result.rationale() + ")");
                assertEquals(arch.testPaths().size(), result.totalTestClasses(),
                        () -> "Total test count mismatch in " + arch.name());

                var selectedSet = new TreeSet<String>();
                for (var st : result.selectedTests()) {
                    selectedSet.add(st.path());
                }
                for (String reqTest : expectedTests) {
                    if (!selectedSet.contains(reqTest)) {
                        archMissed++;
                        totalMissedTests++;
                    }
                }
                assertEquals(expectedTests, selectedSet,
                        () -> "Exact transitive test closure mismatch for " + arch.name() + " edit=" + editPath);

                archRedSum += result.reductionPercent();
                archSelectedSum += result.selectedTests().size();
                sumCodeReductionPct += result.reductionPercent();
                totalCodeEdits++;
            }

            int archFallbackTotal = arch.safetyEdits().size();
            int archFallbackOk = 0;
            for (var safetyEdit : arch.safetyEdits()) {
                safetyFallbackTriggers++;
                var result = AffectedTestSelector.select(arch.graph(), arch.contextId(), List.of(safetyEdit));
                assertEquals(AffectedTestSelector.SelectionMode.FULL_SUITE, result.mode(),
                        () -> "Safety fallback failed for " + safetyEdit + " in " + arch.name() + ": " + result);
                assertTrue(result.mavenTestFilter().isEmpty(),
                        () -> "FULL_SUITE fallback must not emit narrow -Dtest filter for " + safetyEdit);
                archFallbackOk++;
                safetyFallbackPassed++;
            }

            double avgSelected = arch.codeEdits().isEmpty() ? 0.0 : archSelectedSum / arch.codeEdits().size();
            double avgRed = arch.codeEdits().isEmpty() ? 0.0 : archRedSum / arch.codeEdits().size();
            String fbText = archFallbackTotal == 0
                    ? "N/A"
                    : String.format(Locale.ROOT, "%d/%d(100.0%%)", archFallbackOk, archFallbackTotal);
            System.out.printf(Locale.ROOT,
                    "[E9-SKG-RTS-ARCH] arch=%s modules=%d tests=%d codeEdits=%d avgSelected=%.2f avgReductionPct=%.2f%% missedTests=%d safetyFallback=%s%n",
                    arch.name(), arch.moduleCount(), arch.testPaths().size(), arch.codeEdits().size(),
                    avgSelected, avgRed, archMissed, fbText);
        }

        double overallReductionPct = sumCodeReductionPct / totalCodeEdits;
        double fallbackPct = 100.0 * safetyFallbackPassed / safetyFallbackTriggers;

        System.out.printf(Locale.ROOT,
                "[E9-SKG-RTS] corpus=SYNTHETIC_FIXTURE architectures=%d totalModules=%d totalTestClasses=%d codeEdits=%d overallReductionPct=%.2f%% missedDependentTests=%d closureSoundnessPct=100.0%% safetyFallbackTriggers=%d/%d (%.1f%%)%n",
                architectures.size(), totalModules, totalTestClasses, totalCodeEdits,
                overallReductionPct, totalMissedTests, safetyFallbackPassed, safetyFallbackTriggers, fallbackPct);

        assertEquals(5, architectures.size());
        assertTrue(totalModules >= 40, "Expected >= 40 production modules, got " + totalModules);
        assertTrue(totalTestClasses >= 80, "Expected >= 80 synthetic test classes, got " + totalTestClasses);
        assertEquals(0, totalMissedTests, "Zero missed dependent tests allowed against exhaustive DFS ground truth");
        assertEquals(safetyFallbackTriggers, safetyFallbackPassed, "100% automatic FULL_SUITE fallback required");
        assertTrue(overallReductionPct >= 65.0,
                () -> "Expected significant overall SKG-RTS reduction >= 65%, got " + overallReductionPct + "%");
    }

    // =========================================================================
    // 2. [E9-QUARANTINE] Quarantine Invariance Proof (Real Analysis + Leiden)
    // =========================================================================

    @Test
    void quarantineInvarianceHoldsBitIdenticallyForCanonicalGraphAndLeidenPartitionAcrossNonCodeMutations() throws Exception {
        Path repoBefore = Files.createDirectory(temp.resolve("quarantine-before"));
        Path repoAfter = Files.createDirectory(temp.resolve("quarantine-after"));

        // Write identical 12-class, 3-community Java/JPA codebase to BOTH directories
        writeMultiCommunityJavaJpaProject(repoBefore);
        writeMultiCommunityJavaJpaProject(repoAfter);

        // BEFORE: initial .sql, .tf, .md, .mcp.json
        writeAdvisoryFixtureFiles(repoBefore, false);
        // AFTER: heavily mutated .sql, .tf, .md, .mcp.json (extra tables, views, triggers, TF resources + secrets, ADR links)
        writeAdvisoryFixtureFiles(repoAfter, true);

        // 1. Canonical Code-Only Analysis (excluding non-code advisory dirs db/, infra/, docs/):
        //    proves full KnowledgeGraph.identity() SHA-256 is 100% bit-identical across disk mutations to .sql/.tf/.md!
        var canonBefore = runPassiveRepoAnalysis(repoBefore, "urn:e9:quarantine-canon", List.of("db", "infra", "docs", ".mcp.json"),
                Files.createDirectory(temp.resolve("intake-canon-before")));
        var canonAfter = runPassiveRepoAnalysis(repoAfter, "urn:e9:quarantine-canon", List.of("db", "infra", "docs", ".mcp.json"),
                Files.createDirectory(temp.resolve("intake-canon-after")));
        assertEquals(canonBefore.identity(), canonAfter.identity(),
                "Canonical KnowledgeGraph SHA-256 must be 100% bit-identical before and after mutating .sql/.tf/.md");

        // 2. Full Captured Analysis (including .sql/.tf/.md bytes via CAS content store for AdvisoryOverlayService):
        //    proves AdvisoryOverlayService updates cleanly with 0 leakage while canonical Java analysis subgraph,
        //    Leiden community partition, modularity Q, and Health Score v2 remain 100% BIT-IDENTICAL!
        Path sharedIntake = Files.createDirectory(temp.resolve("intake-quarantine-full"));
        var casRef = new java.util.concurrent.atomic.AtomicReference<com.evolution.analysis.filesystem.StreamingContentStore>();
        RepairSource.registerContentFallback((digest, expectedSize) ->
                WorkbenchApiMain.readBoundedCasContent(sharedIntake.resolve("content"), casRef, digest, expectedSize));
        try {
        var fullBefore = runPassiveRepoAnalysis(repoBefore, "urn:e9:quarantine-full", List.of(), sharedIntake);
        var fullAfter = runPassiveRepoAnalysis(repoAfter, "urn:e9:quarantine-full", List.of(), sharedIntake);

        ContentDigest fullBeforeIdPreOverlay = fullBefore.identity();
        ContentDigest fullAfterIdPreOverlay = fullAfter.identity();

        var advBefore = AdvisoryOverlayService.forGraph(fullBefore);
        var advAfter = AdvisoryOverlayService.forGraph(fullAfter);

        // Zero mutation to KnowledgeGraph identity on overlay invocation
        assertEquals(fullBeforeIdPreOverlay, fullBefore.identity(), "AdvisoryOverlayService must never mutate graph digest (before)");
        assertEquals(fullAfterIdPreOverlay, fullAfter.identity(), "AdvisoryOverlayService must never mutate graph digest (after)");

        // Verify advisory overlay actually detected & updated cleanly across .sql, .tf, .md mutations with strict quarantine tags
        assertEquals(AdvisoryOverlay.SCHEMA, advBefore.schema());
        assertEquals(AdvisoryOverlay.SCHEMA, advAfter.schema());
        assertTrue(advBefore.nodes().size() >= 3, () -> "Expected initial advisory nodes, got " + advBefore.nodes().size());
        assertTrue(advAfter.nodes().size() > advBefore.nodes().size(),
                () -> "Mutating .sql/.tf/.md must update advisory overlay: before=" + advBefore.nodes().size()
                        + " after=" + advAfter.nodes().size());
        assertTrue(advAfter.links().size() > advBefore.links().size(),
                () -> "Mutating .sql/.tf/.md must add advisory links: before=" + advBefore.links().size()
                        + " after=" + advAfter.links().size());
        assertTrue(advAfter.nodes().stream().allMatch(n ->
                AdvisoryOverlay.TRUST.equals(n.trust()) && AdvisoryOverlay.DERIVATION.equals(n.derivation())));
        assertTrue(advAfter.links().stream().allMatch(l ->
                AdvisoryOverlay.TRUST.equals(l.trust()) && AdvisoryOverlay.DERIVATION.equals(l.derivation())));
        assertTrue(advAfter.links().stream().anyMatch(l -> "SQL_TABLE_MAPS_JPA_ENTITY".equals(l.kind())));
        assertTrue(advAfter.links().stream().anyMatch(l -> "DOC_MENTIONS_SYMBOL".equals(l.kind())));
        assertTrue(advAfter.redactedSecretsCount() >= 1, "Terraform secret inside mutated .tf must be redacted");

        // Zero leakage of advisory node kinds/evidence into canonical KnowledgeGraph
        Set<String> advisoryKinds = Set.of(
                "SQL_TABLE", "SQL_VIEW", "SQL_TRIGGER", "SQL_ROUTINE",
                "TF_RESOURCE", "TF_MODULE", "TF_DATA", "TF_OUTPUT",
                "DOC_SECTION", "ADR_DOCUMENT", "MCP_SERVER", "MCP_ENV_VAR");
        long leakedNodesBefore = fullBefore.nodes().stream().filter(n -> advisoryKinds.contains(n.kind())).count();
        long leakedNodesAfter = fullAfter.nodes().stream().filter(n -> advisoryKinds.contains(n.kind())).count();
        long leakedEvAfter = fullAfter.evidence().stream().filter(e -> e.family().startsWith("advisory.")).count();
        assertEquals(0L, leakedNodesBefore, "Zero advisory nodes may leak into canonical graph (before)");
        assertEquals(0L, leakedNodesAfter, "Zero advisory nodes may leak into canonical graph (after)");
        assertEquals(0L, leakedEvAfter, "Zero advisory evidence may leak into canonical graph (after)");

        // Compare canonical Java analysis semantic subgraph (nodes/edges/declarations independent of snapshot wrapper ID),
        // Leiden community partition, & Health Score v2 (BEFORE vs AFTER)!
        String ctxBefore = primaryAnalysisContext(fullBefore);
        String ctxAfter = primaryAnalysisContext(fullAfter);

        ContentDigest semanticSubgraphBefore = canonicalAnalysisSubgraphDigest(fullBefore, ctxBefore);
        ContentDigest semanticSubgraphAfter = canonicalAnalysisSubgraphDigest(fullAfter, ctxAfter);
        assertEquals(semanticSubgraphBefore, semanticSubgraphAfter,
                "Canonical Java analysis subgraph SHA-256 must be 100% bit-identical after mutating .sql/.tf/.md");

        // Real Leiden community detection via BOTH GraphProjectionService(GroupBy.COMMUNITY) and LeidenCommunityDetector
        var leidenBefore = computeCanonicalLeiden(fullBefore, ctxBefore);
        var leidenAfter = computeCanonicalLeiden(fullAfter, ctxAfter);
        assertTrue(leidenBefore.communities().size() >= 2,
                () -> "Expected >= 2 emergent Leiden communities across 3 domain packages, got " + leidenBefore.communities().size());
        assertEquals(leidenBefore.communities().size(), leidenAfter.communities().size());
        assertEquals(leidenBefore.communityByNode(), leidenAfter.communityByNode(),
                "Canonical Leiden community partition must be 100% identical before & after non-code mutations");
        assertEquals(leidenBefore.modularity(), leidenAfter.modularity(),
                "Canonical Leiden modularity Q must be bit-identical");
        assertEquals(
                leidenBefore.communities().stream().map(LeidenCommunityDetector.EmergentCommunity::memberSetDigest).toList(),
                leidenAfter.communities().stream().map(LeidenCommunityDetector.EmergentCommunity::memberSetDigest).toList(),
                "Canonical Leiden SHA-256 memberSetDigests must be bit-identical");

        // Also verify GraphProjectionService(GroupBy.COMMUNITY) & ArchitectureAssessmentV2Service Score v2 parity
        var queriesBefore = new ArchitectureMetricsService(fullBefore, ArchitectureMetric.Limits.defaults(),
                () -> StopReason.NONE, Optional.empty(), List.of()).queries();
        var queriesAfter = new ArchitectureMetricsService(fullAfter, ArchitectureMetric.Limits.defaults(),
                () -> StopReason.NONE, Optional.empty(), List.of()).queries();
        var projReqBefore = new ArchitectureQuery.Request(
                fullBefore.identity(), ctxBefore, ArchitectureQuery.Scope.TYPE,
                ArchitectureQuery.Projection.BOUNDED_UNION, ArchitectureQuery.Direction.OUTGOING,
                Set.of("graph.depends-on"), ArchitectureQuery.Population.all(), false,
                Optional.empty(), ArchitectureQuery.Limits.defaults());
        var projReqAfter = new ArchitectureQuery.Request(
                fullAfter.identity(), ctxAfter, ArchitectureQuery.Scope.TYPE,
                ArchitectureQuery.Projection.BOUNDED_UNION, ArchitectureQuery.Direction.OUTGOING,
                Set.of("graph.depends-on"), ArchitectureQuery.Population.all(), false,
                Optional.empty(), ArchitectureQuery.Limits.defaults());
        var visBefore = new GraphProjectionService(queriesBefore).project(projReqBefore, Optional.empty(), GraphProjectionService.GroupBy.COMMUNITY);
        var visAfter = new GraphProjectionService(queriesAfter).project(projReqAfter, Optional.empty(), GraphProjectionService.GroupBy.COMMUNITY);
        var commIdsBefore = visBefore.nodes().stream().filter(n -> "COMMUNITY".equals(n.kind())).map(n -> n.id()).sorted().toList();
        var commIdsAfter = visAfter.nodes().stream().filter(n -> "COMMUNITY".equals(n.kind())).map(n -> n.id()).sorted().toList();
        assertEquals(commIdsBefore, commIdsAfter, "GraphProjectionService COMMUNITY super-nodes must be identical");

        var scorer = new ArchitectureAssessmentV2Service();
        var scoreBefore = scorer.assess(queriesBefore, ctxBefore, Optional.empty(), Optional.empty(), ArchitectureScoreModelV2.standard());
        var scoreAfter = scorer.assess(queriesAfter, ctxAfter, Optional.empty(), Optional.empty(), ArchitectureScoreModelV2.standard());
        assertEquals(scoreBefore.score(), scoreAfter.score(), "Architecture Score v2 must be bit-identical");

        System.out.printf(Locale.ROOT,
                "[E9-QUARANTINE] canonicalGraphSha256Match=true analysisSubgraphSha256Match=true leidenPartitionMatch=true communities=%d modularity=%s scoreV2=%s advisoryNodesBefore=%d advisoryNodesAfter=%d advisoryLinksBefore=%d advisoryLinksAfter=%d canonicalLeakage=0%n",
                leidenBefore.communities().size(), leidenBefore.modularity(), scoreBefore.score(),
                advBefore.nodes().size(), advAfter.nodes().size(),
                advBefore.links().size(), advAfter.links().size());
        } finally {
            RepairSource.registerContentFallback(null);
        }
    }

    // =========================================================================
    // 3. [E9-REDACT-RRF] Shannon-Entropy Secret Guard (llm-redact:v2) & Hybrid RRF Benchmark
    // =========================================================================

    @Test
    void shannonEntropySecretGuardAndHybridRrfBenchmarkVerifyZeroLeaksAndSuperiorRecall() throws Exception {
        // --- Part 3A: Shannon-Entropy Secret Guard (llm-redact:v2) across 15 realistic credential patterns ---
        assertEquals("llm-redact:v2", LlmTransport.REDACTION_VERSION);

        String awsKey = String.join("", "AKIA", "IOSFODNN7EXAMPLE");
        String awsSecret = String.join("/", "wJalrXUtnFEMI", "K7MDENG", "bPxRfiCYEXAMPLEKEY");
        String ghClassic = String.join("_", "ghp", "1234567890abcdef1234567890abcdef1234");
        String ghFineGrained = String.join("_", "github", "pat", "11AAAAAA0123456789abcdef", "XYZ9876543210fedcba9876543210");
        String pemHeader = String.join(" ", "-----BEGIN", "RSA", "PRIVATE", "KEY-----");
        String pemFooter = String.join(" ", "-----END", "RSA", "PRIVATE", "KEY-----");
        String stripeLive = String.join("_", "sk", "live", "51AbCdEfGhIjKlMnOpQrStUvWxYz0123456789");
        String openaiProj = String.join("-", "sk", "proj", "aB3cD4eF5gH6iJ7kL8mN9oP0qR1sT2uV3wX4");
        String slackBot = String.join("-", "xoxb", "123456789012", "987654321098", "AbCdEfGhIjKlMnOpQrStUvWx");
        String slackWebhook = String.join("/", "https:/", "hooks.slack.com", "services", "T00000000", "B00000000", "X9kP2mQ7rT4vW8zB3nL6jF1h");
        String googleKey = String.join("", "AIza", "SyDaGmWKa4JsXZ-HjGw7ISLn_3namBGewQe");

        record SecretCase(String name, String path, String content, String rawSecretSubstring) {}
        var secretCases = List.of(
                new SecretCase("aws-access-key", "src/main/java/com/acme/AwsCfg.java",
                        "String key = \"" + awsKey + "\";", awsKey),
                new SecretCase("aws-secret-key", ".env",
                        "AWS_SECRET_ACCESS_KEY=" + awsSecret, awsSecret),
                new SecretCase("github-pat-classic", "src/main/java/com/acme/GitCfg.java",
                        "String tok = \"" + ghClassic + "\";", ghClassic),
                new SecretCase("github-pat-fine-grained", "src/main/java/com/acme/GitFg.java",
                        "String fg = \"" + ghFineGrained + "\";", ghFineGrained),
                new SecretCase("jwt-bearer", "src/main/java/com/acme/AuthHdr.java",
                        "String h = \"Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIiwiYWRtaW4iOnRydWV9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c\";",
                        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"),
                new SecretCase("jdbc-embedded-password", "src/main/resources/application.yml",
                        "spring:\n  datasource:\n    url: jdbc:postgresql://dbadmin:Sup3rS3cretJdbcPwd!@db.internal:5432/orders\n",
                        "Sup3rS3cretJdbcPwd!"),
                new SecretCase("yaml-db-password", "src/main/resources/application.yml",
                        "spring:\n  datasource:\n    password: ProdVaultDbPassword#2026!\n",
                        "ProdVaultDbPassword#2026!"),
                new SecretCase("pem-rsa-private-key", "certs/service.pem.txt",
                        pemHeader + "\nMIIEpAIBAAKCAQEA7V2k9X8mP4qR6wL1nT3yB5zC8fG0hJ2k\n" + pemFooter + "\n",
                        "MIIEpAIBAAKCAQEA7V2k9X8mP4qR6wL1nT3yB5zC8fG0hJ2k"),
                new SecretCase("stripe-live-key", "src/main/java/com/acme/BillingCfg.java",
                        "String stripe = \"" + stripeLive + "\";", stripeLive),
                new SecretCase("openai-project-key", "src/main/java/com/acme/AiCfg.java",
                        "String openai = \"" + openaiProj + "\";", openaiProj),
                new SecretCase("slack-bot-token", "src/main/java/com/acme/NotifyCfg.java",
                        "String slack = \"" + slackBot + "\";", slackBot),
                new SecretCase("slack-webhook-secret", ".env",
                        "SLACK_WEBHOOK_SECRET=" + slackWebhook,
                        "X9kP2mQ7rT4vW8zB3nL6jF1h"),
                new SecretCase("high-entropy-base64-token", "src/main/java/com/acme/SigningCfg.java",
                        "static final String SIGNING_KEY = \"K9xR7mP2vQ4nW8zB3tY6jL1cF5hD0gS9==\";",
                        "K9xR7mP2vQ4nW8zB3tY6jL1cF5hD0gS9=="),
                new SecretCase("high-entropy-hex-env-secret", ".env",
                        "HMAC_SIGNING_SECRET=9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a3928",
                        "9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a3928"),
                new SecretCase("google-api-key", "src/main/java/com/acme/MapsCfg.java",
                        "String gKey = \"" + googleKey + "\";", googleKey));

        int redactedOk = 0;
        int leakedSecrets = 0;
        var capturedForDiskCheck = new LinkedHashMap<String, String>();
        for (int i = 0; i < secretCases.size(); i++) {
            var sc = secretCases.get(i);
            var doc = LlmTransport.redactDocumentForPath(sc.path(), sc.content());
            if (doc.text().contains(sc.rawSecretSubstring()) || doc.totalRedactedCount() < 1) {
                leakedSecrets++;
            } else {
                redactedOk++;
            }
            assertFalse(doc.text().contains(sc.rawSecretSubstring()),
                    () -> "Secret leaked for pattern " + sc.name() + ": " + doc.text());
            assertTrue(doc.totalRedactedCount() >= 1, () -> "Expected >=1 redaction count for " + sc.name());
            capturedForDiskCheck.put("secrets/case-" + i + "-" + sc.path(), sc.content());
        }

        // Benign identifiers & metadata MUST suffer 0 false positives!
        var benignTokens = List.of(
                "OrderReconciliationServiceFactoryImpl",
                "ArchitectureScoreModelV2Impl",
                "PaymentIdempotencyLedgerCoordinator",
                "com.evolution.backend.query.ArchitectureQueryApi",
                "OrderService.reconcileInvoiceBatchV2",
                "550e8400-e29b-41d4-a716-446655440000",
                "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
                "https://api.example.com/v1/organizations/acme/repositories/orders",
                "org.springframework.boot:spring-boot-autoconfigure:3.5.14",
                "maxTokens: 4096",
                "tokenExpirySeconds: 3600");
        String benignJava = "package com.acme.clean;\npublic class BenignCatalog {\n"
                + String.join("\n", benignTokens.stream().limit(10).map(t -> "    String x = \"" + t + "\";").toList())
                + "\n}\n";
        String benignYaml = "llm:\n  maxTokens: 4096\n  tokenExpirySeconds: 3600\n  passwordEncoder: bcrypt\n";
        var redBenignJava = LlmTransport.redactDocumentForPath("src/main/java/com/acme/clean/BenignCatalog.java", benignJava);
        var redBenignYaml = LlmTransport.redactDocumentForPath("src/main/resources/application.yml", benignYaml);

        int benignPreserved = 0;
        int falsePositives = 0;
        for (int i = 0; i < benignTokens.size(); i++) {
            String tok = benignTokens.get(i);
            String target = i < 10 ? redBenignJava.text() : redBenignYaml.text();
            if (target.contains(tok)) {
                benignPreserved++;
            } else {
                falsePositives++;
            }
            assertTrue(target.contains(tok), () -> "False-positive redaction on benign token: " + tok);
        }

        // Byte-level SQLite FTS5 disk check: 0 secret substrings persisted in fts5.db / -wal / -shm!
        var secretGraph = buildCapturedFilesOnlyGraph(capturedForDiskCheck);
        Path secretFtsDb = temp.resolve("redact-check").resolve("fts5.db");
        try (var idx = CapturedSearchIndex.openReadWrite(secretFtsDb)) {
            idx.ensureIndexed(secretGraph, () -> StopReason.NONE).orElseThrow();
        }
        int diskLeaks = 0;
        for (String name : List.of("fts5.db", "fts5.db-wal", "fts5.db-shm")) {
            Path p = temp.resolve("redact-check").resolve(name);
            if (!Files.exists(p)) continue;
            String rawDisk = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            for (var sc : secretCases) {
                if (rawDisk.contains(sc.rawSecretSubstring())) {
                    diskLeaks++;
                }
            }
        }
        assertEquals(0, diskLeaks, "Zero raw secrets may persist on disk in SQLite FTS5 index");
        assertEquals(0, leakedSecrets, "Zero leaked secrets permitted across all 15 credential patterns");
        assertEquals(0, falsePositives, "Zero false positives permitted on benign identifiers & metadata");

        double redactionRatePct = 100.0 * redactedOk / secretCases.size();
        System.out.printf(Locale.ROOT,
                "[E9-REDACT] corpus=SYNTHETIC_FIXTURE version=%s secretPatterns=%d redacted=%d leaked=%d redactionRatePct=%.1f%% benignPreserved=%d/%d falsePositives=%d diskLeaks=%d%n",
                LlmTransport.REDACTION_VERSION, secretCases.size(), redactedOk, leakedSecrets,
                redactionRatePct, benignPreserved, benignTokens.size(), falsePositives, diskLeaks);

        // --- Part 3B: Hybrid RRF (ppr-bm25-trigram-rrf-v2) Recall@5 & Recall@10 vs Single-Signal Baselines ---
        var rrfBench = buildMultiModuleRrfBenchmarkFixture();
        Path rrfFtsDb = temp.resolve("rrf-bench").resolve("fts5.db");

        double hybridR5Sum = 0.0, hybridR10Sum = 0.0;
        double bm25R5Sum = 0.0, bm25R10Sum = 0.0;
        double noBm25R5Sum = 0.0, noBm25R10Sum = 0.0;
        double unseededPprR5Sum = 0.0, unseededPprR10Sum = 0.0;

        try (var searchIndex = CapturedSearchIndex.openReadWrite(rrfFtsDb)) {
            searchIndex.ensureIndexed(rrfBench.graph(), () -> StopReason.NONE).orElseThrow();

            // Precompute unseeded PPR baseline once
            var unseededRes = RepoMapBuilder.build(
                    rrfBench.graph(), Optional.of(rrfBench.contextId()), List.of(),
                    10, 24_000, () -> StopReason.NONE, null);
            List<String> unseededPaths = unseededRes.files().stream().map(RepoMapBuilder.FileRank::path).toList();

            for (var q : rrfBench.queries()) {
                // 1. Hybrid RRF (PPR + BM25 + Trigram)
                var hybridRes = RepoMapBuilder.build(
                        rrfBench.graph(), Optional.of(rrfBench.contextId()), q.seeds(),
                        10, 24_000, () -> StopReason.NONE, searchIndex);
                assertEquals(RepoMapBuilder.RRF_ALGORITHM, hybridRes.algorithm());
                List<String> hybridPaths = hybridRes.files().stream().map(RepoMapBuilder.FileRank::path).toList();

                // 2. BM25-only lexical baseline
                List<String> bm25Paths = searchIndex.rankFiles(rrfBench.graph(), q.seeds(), 10, () -> StopReason.NONE)
                        .stream().map(CapturedSearchIndex.FileBm25Hit::path).toList();

                // 3. Graph+Trigram-only baseline (no BM25 body index)
                var noBm25Res = RepoMapBuilder.build(
                        rrfBench.graph(), Optional.of(rrfBench.contextId()), q.seeds(),
                        10, 24_000, () -> StopReason.NONE, null);
                List<String> noBm25Paths = noBm25Res.files().stream().map(RepoMapBuilder.FileRank::path).toList();

                hybridR5Sum += recallAtK(hybridPaths, q.relevantPaths(), 5);
                hybridR10Sum += recallAtK(hybridPaths, q.relevantPaths(), 10);
                bm25R5Sum += recallAtK(bm25Paths, q.relevantPaths(), 5);
                bm25R10Sum += recallAtK(bm25Paths, q.relevantPaths(), 10);
                noBm25R5Sum += recallAtK(noBm25Paths, q.relevantPaths(), 5);
                noBm25R10Sum += recallAtK(noBm25Paths, q.relevantPaths(), 10);
                unseededPprR5Sum += recallAtK(unseededPaths, q.relevantPaths(), 5);
                unseededPprR10Sum += recallAtK(unseededPaths, q.relevantPaths(), 10);
            }
        }

        int qCount = rrfBench.queries().size();
        double hybridR5 = 100.0 * hybridR5Sum / qCount;
        double hybridR10 = 100.0 * hybridR10Sum / qCount;
        double bm25R5 = 100.0 * bm25R5Sum / qCount;
        double bm25R10 = 100.0 * bm25R10Sum / qCount;
        double noBm25R5 = 100.0 * noBm25R5Sum / qCount;
        double noBm25R10 = 100.0 * noBm25R10Sum / qCount;
        double unseededR5 = 100.0 * unseededPprR5Sum / qCount;
        double unseededR10 = 100.0 * unseededPprR10Sum / qCount;

        System.out.printf(Locale.ROOT,
                "[E9-RRF] corpus=SYNTHETIC_FIXTURE algorithm=%s files=%d queries=%d hybridRecallAt5=%.1f%% hybridRecallAt10=%.1f%% bm25OnlyRecallAt5=%.1f%% bm25OnlyRecallAt10=%.1f%% noBm25RecallAt5=%.1f%% noBm25RecallAt10=%.1f%% unseededPprRecallAt5=%.1f%% unseededPprRecallAt10=%.1f%%%n",
                RepoMapBuilder.RRF_ALGORITHM, rrfBench.fileCount(), qCount,
                hybridR5, hybridR10, bm25R5, bm25R10, noBm25R5, noBm25R10, unseededR5, unseededR10);

        System.out.printf(Locale.ROOT,
                "[E9-REDACT-RRF] corpus=SYNTHETIC_FIXTURE redactionVersion=%s secretPatterns=%d redactionRatePct=%.1f%% leakedSecrets=%d falsePositives=%d rrfAlgorithm=%s hybridRecallAt5=%.1f%% hybridRecallAt10=%.1f%% deltaVsBm25At5=+%.1fpp deltaVsNoBm25At5=+%.1fpp%n",
                LlmTransport.REDACTION_VERSION, secretCases.size(), redactionRatePct, leakedSecrets, falsePositives,
                RepoMapBuilder.RRF_ALGORITHM, hybridR5, hybridR10, (hybridR5 - bm25R5), (hybridR5 - noBm25R5));

        assertTrue(hybridR5 > bm25R5,
                () -> "Hybrid RRF Recall@5 (" + hybridR5 + "%) must beat BM25-only (" + bm25R5 + "%)");
        assertTrue(hybridR5 > noBm25R5,
                () -> "Hybrid RRF Recall@5 (" + hybridR5 + "%) must beat Graph+Trigram without BM25 (" + noBm25R5 + "%)");
        assertTrue(hybridR10 >= bm25R10 && hybridR10 >= noBm25R10,
                () -> "Hybrid RRF Recall@10 (" + hybridR10 + "%) must dominate single-signal baselines (bm25="
                        + bm25R10 + "%, noBm25=" + noBm25R10 + "%)");
        assertTrue(hybridR10 >= 85.0, () -> "Expected Hybrid RRF Recall@10 >= 85%, got " + hybridR10 + "%");
    }

    // =========================================================================
    // 4. [E9-REAL-REPO-*] Real-Repository AI4SE Evaluation (RTS, RRF, Redaction)
    // =========================================================================

    private record RealRepoTarget(String label, Path root, List<String> exclusions) {}

    @Test
    void realRepositoryAi4seEvaluationAcrossActualSpringAndMavenCodebases() throws Exception {
        var targets = discoverRealRepositories();
        assertFalse(targets.isEmpty(), "At least one real local Maven/Spring repository must be discoverable");

        int reposEvaluated = 0;
        int totalProdEdits = 0;
        int totalSelectedEdits = 0;
        int totalFallbackEdits = 0;
        double sumSelectedReductionPct = 0.0;
        double sumEffectiveReductionPct = 0.0;
        int totalSourceRefExpected = 0;
        int totalSourceRefHit = 0;
        int totalBuildConfigSafetyTotal = 0;
        int totalBuildConfigSafetyPassed = 0;

        int totalRealRrfQueries = 0;
        double sumRealHybridR5 = 0.0, sumRealHybridR10 = 0.0, sumRealHybridMrr = 0.0;
        double sumRealBm25R5 = 0.0, sumRealBm25R10 = 0.0, sumRealBm25Mrr = 0.0;
        double sumRealNoBm25R5 = 0.0, sumRealNoBm25R10 = 0.0, sumRealNoBm25Mrr = 0.0;
        double sumRealUnseededR5 = 0.0, sumRealUnseededR10 = 0.0, sumRealUnseededMrr = 0.0;

        int totalRealFilesScanned = 0;
        int totalRealJavaFiles = 0;
        int totalRealJavaRedactions = 0;
        int totalRealConfigFiles = 0;
        int totalRealConfigRedactions = 0;
        int totalCanarySecrets = 0;
        int totalCanaryRedacted = 0;
        int totalCanaryDiskLeaks = 0;

        for (int rIdx = 0; rIdx < targets.size(); rIdx++) {
            var target = targets.get(rIdx);
            Path intake = Files.createDirectory(temp.resolve("real-repo-intake-" + rIdx));
            var casRef = new java.util.concurrent.atomic.AtomicReference<com.evolution.analysis.filesystem.StreamingContentStore>();
            RepairSource.registerContentFallback((digest, expectedSize) ->
                    WorkbenchApiMain.readBoundedCasContent(intake.resolve("content"), casRef, digest, expectedSize));
            try {
                var graph = runPassiveRepoAnalysis(
                        target.root(),
                        "urn:e9-real:" + target.label().replaceAll("[^A-Za-z0-9._-]", "-"),
                        target.exclusions(),
                        intake,
                        true);
                reposEvaluated++;

                // --- 4A. Real-Repo SKG-RTS (closed denominator over ALL prod .java files) ---
                var allRepoJava = collectRelativeFiles(target.root(), target.exclusions(), Set.of(".java"));
                var prodJava = allRepoJava.stream()
                        .filter(p -> (p.contains("/src/main/java/") || p.startsWith("src/main/java/"))
                                && !p.endsWith("/package-info.java") && !p.equals("package-info.java"))
                        .sorted()
                        .toList();
                var testDirJava = allRepoJava.stream()
                        .filter(p -> p.contains("/src/test/java/") || p.startsWith("src/test/java/"))
                        .sorted()
                        .toList();
                var runnableTestPaths = new TreeSet<String>();
                int nonTestHelpersInTestDir = 0;
                for (String tp : testDirJava) {
                    if (isSurefireRunnableJavaTest(tp)) {
                        runnableTestPaths.add(tp);
                    } else {
                        nonTestHelpersInTestDir++;
                    }
                }

                // Independent static source-reference ground truth directly from real .java files on disk
                Map<String, Set<String>> staticSourceRefDeps = buildStaticJavaSourceReferenceGraph(
                        target.root(), allRepoJava);

                int repoSelected = 0;
                int repoFallback = 0;
                double repoSelectedRedSum = 0.0;
                double repoEffectiveRedSum = 0.0;
                int repoSrcRefExpected = 0;
                int repoSrcRefHit = 0;

                for (String prodPath : prodJava) {
                    var res = AffectedTestSelector.select(graph, null, List.of(prodPath));
                    if (!runnableTestPaths.isEmpty()) {
                        assertEquals(runnableTestPaths.size(), res.totalTestClasses(),
                                () -> "Runnable test class count mismatch on real repo " + target.label()
                                        + " (helpers under src/test/java must be excluded)");
                    }
                    Set<String> expectedBySourceRef = computeReverseSourceRefTests(
                            staticSourceRefDeps, runnableTestPaths, prodPath);
                    repoSrcRefExpected += expectedBySourceRef.size();
                    totalSourceRefExpected += expectedBySourceRef.size();

                    if (res.mode() == AffectedTestSelector.SelectionMode.SELECTED_TESTS) {
                        repoSelected++;
                        totalSelectedEdits++;
                        repoSelectedRedSum += res.reductionPercent();
                        repoEffectiveRedSum += res.reductionPercent();
                        sumSelectedReductionPct += res.reductionPercent();
                        sumEffectiveReductionPct += res.reductionPercent();

                        var selectedPaths = new HashSet<String>();
                        for (var st : res.selectedTests()) {
                            selectedPaths.add(st.path());
                            assertTrue(runnableTestPaths.contains(st.path()),
                                    () -> "Selected non-runnable test helper " + st.path() + " in " + target.label());
                        }
                        for (String expTest : expectedBySourceRef) {
                            if (selectedPaths.contains(expTest)) {
                                repoSrcRefHit++;
                                totalSourceRefHit++;
                            }
                        }
                    } else {
                        // Fail-closed FULL_SUITE guarantees 100% recall with 0% reduction
                        repoFallback++;
                        totalFallbackEdits++;
                        repoSrcRefHit += expectedBySourceRef.size();
                        totalSourceRefHit += expectedBySourceRef.size();
                    }
                    totalProdEdits++;
                }

                // Real build/config files must ALWAYS fail closed to FULL_SUITE
                var realSafetyFiles = collectRealBuildAndSpringConfigFiles(target.root(), target.exclusions());
                int repoSafetyOk = 0;
                for (String sf : realSafetyFiles) {
                    var res = AffectedTestSelector.select(graph, null, List.of(sf));
                    assertEquals(AffectedTestSelector.SelectionMode.FULL_SUITE, res.mode(),
                            () -> "Real build/config edit failed to trigger FULL_SUITE: " + target.label() + " " + sf);
                    assertTrue(res.mavenTestFilter().isEmpty());
                    repoSafetyOk++;
                }
                totalBuildConfigSafetyTotal += realSafetyFiles.size();
                totalBuildConfigSafetyPassed += repoSafetyOk;

                double repoAvgSelRed = repoSelected == 0 ? 0.0 : repoSelectedRedSum / repoSelected;
                double repoEffRed = prodJava.isEmpty() ? 0.0 : repoEffectiveRedSum / prodJava.size();
                double repoSrcRecall = repoSrcRefExpected == 0 ? 100.0 : (100.0 * repoSrcRefHit / repoSrcRefExpected);

                System.out.printf(Locale.ROOT,
                        "[E9-REAL-RTS] repo=%s prodJava=%d runnableTests=%d testHelpersExcluded=%d selectedEdits=%d fullSuiteFallbacks=%d avgReductionOnSelectedPct=%.2f%% effectiveReductionAllEditsPct=%.2f%% sourceRefRecallPct=%.2f%% (%d/%d) safetyFallback=%d/%d%n",
                        target.label(), prodJava.size(), runnableTestPaths.size(), nonTestHelpersInTestDir,
                        repoSelected, repoFallback, repoAvgSelRed, repoEffRed,
                        repoSrcRecall, repoSrcRefHit, repoSrcRefExpected, repoSafetyOk, realSafetyFiles.size());

                // --- 4B. Real-Repo Hybrid RRF vs BM25-only vs Graph+Trigram vs Unseeded PPR ---
                var realQueries = buildSystematicRealRepoRrfQueries(target.root(), prodJava, staticSourceRefDeps, 12);
                Path ftsDb = temp.resolve("real-rrf-" + rIdx).resolve("fts5.db");
                try (var searchIndex = CapturedSearchIndex.openReadWrite(ftsDb)) {
                    searchIndex.ensureIndexed(graph, () -> StopReason.NONE).orElseThrow();
                    var unseeded = RepoMapBuilder.build(graph, Optional.empty(), List.of(),
                            10, 24_000, () -> StopReason.NONE, null);
                    List<String> unseededPaths = unseeded.files().stream().map(RepoMapBuilder.FileRank::path).toList();

                    double rHyb5 = 0.0, rHyb10 = 0.0, rHybMrr = 0.0;
                    double rBm5 = 0.0, rBm10 = 0.0, rBmMrr = 0.0;
                    double rNo5 = 0.0, rNo10 = 0.0, rNoMrr = 0.0;
                    double rUn5 = 0.0, rUn10 = 0.0, rUnMrr = 0.0;

                    for (var q : realQueries) {
                        var hybRes = RepoMapBuilder.build(graph, Optional.empty(), q.seeds(),
                                10, 24_000, () -> StopReason.NONE, searchIndex);
                        assertEquals(RepoMapBuilder.RRF_ALGORITHM, hybRes.algorithm());
                        List<String> hybPaths = hybRes.files().stream().map(RepoMapBuilder.FileRank::path).toList();

                        // Determinism check on first query of each real repo
                        if (q == realQueries.getFirst()) {
                            var hybRepeat = RepoMapBuilder.build(graph, Optional.empty(), q.seeds(),
                                    10, 24_000, () -> StopReason.NONE, searchIndex);
                            assertEquals(hybPaths,
                                    hybRepeat.files().stream().map(RepoMapBuilder.FileRank::path).toList(),
                                    "Real-repo Hybrid RRF ranking must be 100% deterministic");
                        }

                        List<String> bmPaths = searchIndex.rankFiles(graph, q.seeds(), 10, () -> StopReason.NONE)
                                .stream().map(CapturedSearchIndex.FileBm25Hit::path).toList();
                        var noBmRes = RepoMapBuilder.build(graph, Optional.empty(), q.seeds(),
                                10, 24_000, () -> StopReason.NONE, null);
                        List<String> noBmPaths = noBmRes.files().stream().map(RepoMapBuilder.FileRank::path).toList();

                        rHyb5 += recallAtK(hybPaths, q.relevantPaths(), 5);
                        rHyb10 += recallAtK(hybPaths, q.relevantPaths(), 10);
                        rHybMrr += mrrAtK(hybPaths, q.relevantPaths(), 10);

                        rBm5 += recallAtK(bmPaths, q.relevantPaths(), 5);
                        rBm10 += recallAtK(bmPaths, q.relevantPaths(), 10);
                        rBmMrr += mrrAtK(bmPaths, q.relevantPaths(), 10);

                        rNo5 += recallAtK(noBmPaths, q.relevantPaths(), 5);
                        rNo10 += recallAtK(noBmPaths, q.relevantPaths(), 10);
                        rNoMrr += mrrAtK(noBmPaths, q.relevantPaths(), 10);

                        rUn5 += recallAtK(unseededPaths, q.relevantPaths(), 5);
                        rUn10 += recallAtK(unseededPaths, q.relevantPaths(), 10);
                        rUnMrr += mrrAtK(unseededPaths, q.relevantPaths(), 10);
                    }

                    int qc = realQueries.size();
                    assertTrue(qc > 0, () -> "Expected systematic real-repo RRF queries for " + target.label());
                    sumRealHybridR5 += rHyb5; sumRealHybridR10 += rHyb10; sumRealHybridMrr += rHybMrr;
                    sumRealBm25R5 += rBm5; sumRealBm25R10 += rBm10; sumRealBm25Mrr += rBmMrr;
                    sumRealNoBm25R5 += rNo5; sumRealNoBm25R10 += rNo10; sumRealNoBm25Mrr += rNoMrr;
                    sumRealUnseededR5 += rUn5; sumRealUnseededR10 += rUn10; sumRealUnseededMrr += rUnMrr;
                    totalRealRrfQueries += qc;

                    System.out.printf(Locale.ROOT,
                            "[E9-REAL-RRF] repo=%s queries=%d hybridR5=%.1f%% hybridR10=%.1f%% hybridMRR=%.3f bm25R5=%.1f%% bm25R10=%.1f%% bm25MRR=%.3f noBm25R5=%.1f%% noBm25R10=%.1f%% noBm25MRR=%.3f unseededR5=%.1f%% unseededR10=%.1f%% unseededMRR=%.3f%n",
                            target.label(), qc,
                            100.0 * rHyb5 / qc, 100.0 * rHyb10 / qc, rHybMrr / qc,
                            100.0 * rBm5 / qc, 100.0 * rBm10 / qc, rBmMrr / qc,
                            100.0 * rNo5 / qc, 100.0 * rNo10 / qc, rNoMrr / qc,
                            100.0 * rUn5 / qc, 100.0 * rUn10 / qc, rUnMrr / qc);
                    final double fHyb5 = 100.0 * rHyb5 / qc;
                    final double fUn5 = 100.0 * rUn5 / qc;
                    final double fHybMrr = rHybMrr / qc;
                    final double fUnMrr = rUnMrr / qc;
                    assertTrue(fHyb5 >= fUn5 && fHybMrr > fUnMrr,
                            () -> "Hybrid RRF must beat Unseeded PPR on Recall@5 and MRR@10 on " + target.label()
                                    + " (hybridR5=" + fHyb5 + "%, unseededR5=" + fUn5
                                    + "%, hybridMRR=" + fHybMrr + ", unseededMRR=" + fUnMrr + ")");
                }

                // --- 4C. Real-Repo Secret Redaction (Full real text corpus + canary injection + FTS5 disk audit) ---
                var realTextFiles = collectRelativeFiles(target.root(), target.exclusions(),
                        Set.of(".java", ".properties", ".yml", ".yaml", ".xml", ".sql", ".html", ".md", ".sh", ".json"));
                int repoJavaFiles = 0, repoJavaRedactions = 0;
                int repoConfigFiles = 0, repoConfigRedactions = 0;
                var sampleRealTriggers = new ArrayList<String>();

                for (String rel : realTextFiles) {
                    Path abs = target.root().resolve(rel);
                    if (Files.size(abs) > 512_000L) continue;
                    String content = Files.readString(abs, StandardCharsets.UTF_8);
                    var doc = LlmTransport.redactDocumentForPath(rel, content);
                    boolean isJava = rel.endsWith(".java");
                    boolean isCfg = LlmTransport.isConfigOrEnvPath(rel);
                    if (isJava) {
                        repoJavaFiles++;
                        if (doc.totalRedactedCount() > 0) {
                            repoJavaRedactions++;
                            if (sampleRealTriggers.size() < 6) sampleRealTriggers.add(rel + "(" + doc.totalRedactedCount() + ")");
                        }
                    } else if (isCfg) {
                        repoConfigFiles++;
                        if (doc.totalRedactedCount() > 0) {
                            repoConfigRedactions++;
                            if (sampleRealTriggers.size() < 6) sampleRealTriggers.add(rel + "(" + doc.totalRedactedCount() + ")");
                        }
                    }
                }
                totalRealFilesScanned += realTextFiles.size();
                totalRealJavaFiles += repoJavaFiles;
                totalRealJavaRedactions += repoJavaRedactions;
                totalRealConfigFiles += repoConfigFiles;
                totalRealConfigRedactions += repoConfigRedactions;

                // Inject 10 realistic canary secrets into in-memory copies of REAL files from this repository
                String sampleJavaRel = prodJava.isEmpty() ? "src/main/java/Sample.java" : prodJava.getFirst();
                String sampleJavaText = prodJava.isEmpty() ? "class Sample {}\n"
                        : Files.readString(target.root().resolve(sampleJavaRel), StandardCharsets.UTF_8);
                String sampleCfgRel = realSafetyFiles.stream()
                        .filter(p -> p.endsWith(".yml") || p.endsWith(".yaml") || p.endsWith(".properties"))
                        .findFirst()
                        .orElse("src/main/resources/application.yml");
                String sampleCfgText = Files.isRegularFile(target.root().resolve(sampleCfgRel))
                        ? Files.readString(target.root().resolve(sampleCfgRel), StandardCharsets.UTF_8)
                        : "spring:\n  application:\n    name: demo\n";

                String canaryAwsKey = String.join("", "AKIA", "IOSFODNN7EXAMPLE");
                String canaryGhPat = String.join("_", "ghp", "1234567890abcdef1234567890abcdef1234");
                String canaryStripe = String.join("_", "sk", "live", "51AbCdEfGhIjKlMnOpQrStUvWxYz0123456789");
                String canaryOpenAi = String.join("-", "sk", "proj", "aB3cD4eF5gH6iJ7kL8mN9oP0qR1sT2uV3wX4");
                String canarySlack = String.join("-", "xoxb", "123456789012", "987654321098", "AbCdEfGhIjKlMnOpQrStUvWx");
                String canaryGoogle = String.join("", "AIza", "SyDaGmWKa4JsXZ-HjGw7ISLn_3namBGewQe");
                String canaryPemHeader = String.join(" ", "-----BEGIN", "RSA", "PRIVATE", "KEY-----");
                String canaryPemFooter = String.join(" ", "-----END", "RSA", "PRIVATE", "KEY-----");

                record Canary(String name, String path, String body, String secret) {}
                var canaries = List.of(
                        new Canary("aws-key", sampleJavaRel, sampleJavaText + "\n// " + canaryAwsKey + "\n", canaryAwsKey),
                        new Canary("github-pat", sampleJavaRel, sampleJavaText + "\n// " + canaryGhPat + "\n", canaryGhPat),
                        new Canary("stripe-live", sampleJavaRel, sampleJavaText + "\n// " + canaryStripe + "\n", canaryStripe),
                        new Canary("openai-proj", sampleJavaRel, sampleJavaText + "\n// " + canaryOpenAi + "\n", canaryOpenAi),
                        new Canary("slack-bot", sampleJavaRel, sampleJavaText + "\n// " + canarySlack + "\n", canarySlack),
                        new Canary("jwt-bearer", sampleJavaRel, sampleJavaText + "\n// Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIiwiYWRtaW4iOnRydWV9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c\n", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"),
                        new Canary("google-key", sampleJavaRel, sampleJavaText + "\n// " + canaryGoogle + "\n", canaryGoogle),
                        new Canary("jdbc-uri-pwd", sampleCfgRel, sampleCfgText + "\nspring.datasource.url=jdbc:postgresql://admin:RealCanaryPwd#2026!@db.internal:5432/app\n", "RealCanaryPwd#2026!"),
                        new Canary("cfg-password", sampleCfgRel, sampleCfgText + "\napp.vault.password=VaultCanarySecret!9876\n", "VaultCanarySecret!9876"),
                        new Canary("pem-private-key", "certs/canary.pem", canaryPemHeader + "\nMIIEpAIBAAKCAQEA7V2k9X8mP4qR6wL1nT3yB5zC8fG0hJ2k\n" + canaryPemFooter + "\n", "MIIEpAIBAAKCAQEA7V2k9X8mP4qR6wL1nT3yB5zC8fG0hJ2k"));

                int repoCanaryOk = 0;
                var canaryCaptured = new LinkedHashMap<String, String>();
                for (int ci = 0; ci < canaries.size(); ci++) {
                    var c = canaries.get(ci);
                    var doc = LlmTransport.redactDocumentForPath(c.path(), c.body());
                    assertFalse(doc.text().contains(c.secret()),
                            () -> "Canary secret leaked on real repo " + target.label() + " pattern=" + c.name());
                    assertTrue(doc.totalRedactedCount() >= 1);
                    repoCanaryOk++;
                    canaryCaptured.put("canary-" + ci + "/" + c.path(), c.body());
                }
                int repoDiskLeaks = 0;
                Path canaryDbDir = temp.resolve("real-canary-fts-" + rIdx);
                var canaryGraph = buildCapturedFilesOnlyGraph(canaryCaptured);
                try (var cIdx = CapturedSearchIndex.openReadWrite(canaryDbDir.resolve("fts5.db"))) {
                    cIdx.ensureIndexed(canaryGraph, () -> StopReason.NONE).orElseThrow();
                }
                for (String fn : List.of("fts5.db", "fts5.db-wal", "fts5.db-shm")) {
                    Path dbFile = canaryDbDir.resolve(fn);
                    if (!Files.exists(dbFile)) continue;
                    String rawDisk = new String(Files.readAllBytes(dbFile), StandardCharsets.ISO_8859_1);
                    for (var c : canaries) {
                        if (rawDisk.contains(c.secret())) repoDiskLeaks++;
                    }
                }
                assertEquals(0, repoDiskLeaks, () -> "Canary secret leaked to SQLite FTS5 disk on " + target.label());
                totalCanarySecrets += canaries.size();
                totalCanaryRedacted += repoCanaryOk;
                totalCanaryDiskLeaks += repoDiskLeaks;

                System.out.printf(Locale.ROOT,
                        "[E9-REAL-REDACT] repo=%s realFilesScanned=%d realJava=%d realJavaRedacted=%d realCfg=%d realCfgRedacted=%d canaryRedacted=%d/%d canaryDiskLeaks=%d sampleTriggers=%s%n",
                        target.label(), realTextFiles.size(), repoJavaFiles, repoJavaRedactions,
                        repoConfigFiles, repoConfigRedactions, repoCanaryOk, canaries.size(),
                        repoDiskLeaks, sampleRealTriggers);
            } finally {
                RepairSource.registerContentFallback(null);
            }
        }

        double aggSelectedRed = totalSelectedEdits == 0 ? 0.0 : sumSelectedReductionPct / totalSelectedEdits;
        double aggEffectiveRed = totalProdEdits == 0 ? 0.0 : sumEffectiveReductionPct / totalProdEdits;
        double aggSrcRecall = totalSourceRefExpected == 0 ? 100.0 : (100.0 * totalSourceRefHit / totalSourceRefExpected);

        System.out.printf(Locale.ROOT,
                "[E9-REAL-REPO-SUMMARY] repos=%d prodEdits=%d selectedEdits=%d fullSuiteFallbacks=%d avgReductionSelectedPct=%.2f%% effectiveReductionAllPct=%.2f%% sourceRefRecallPct=%.2f%% (%d/%d) safetyFallback=%d/%d rrfQueries=%d hybridR5=%.1f%% hybridR10=%.1f%% hybridMRR=%.3f bm25R5=%.1f%% bm25R10=%.1f%% noBm25R5=%.1f%% noBm25R10=%.1f%% unseededR5=%.1f%% unseededR10=%.1f%% realFilesScanned=%d realJavaRedacted=%d/%d realCfgRedacted=%d/%d canaryRedacted=%d/%d canaryDiskLeaks=%d%n",
                reposEvaluated, totalProdEdits, totalSelectedEdits, totalFallbackEdits,
                aggSelectedRed, aggEffectiveRed, aggSrcRecall, totalSourceRefHit, totalSourceRefExpected,
                totalBuildConfigSafetyPassed, totalBuildConfigSafetyTotal,
                totalRealRrfQueries,
                100.0 * sumRealHybridR5 / totalRealRrfQueries, 100.0 * sumRealHybridR10 / totalRealRrfQueries, sumRealHybridMrr / totalRealRrfQueries,
                100.0 * sumRealBm25R5 / totalRealRrfQueries, 100.0 * sumRealBm25R10 / totalRealRrfQueries,
                100.0 * sumRealNoBm25R5 / totalRealRrfQueries, 100.0 * sumRealNoBm25R10 / totalRealRrfQueries,
                100.0 * sumRealUnseededR5 / totalRealRrfQueries, 100.0 * sumRealUnseededR10 / totalRealRrfQueries,
                totalRealFilesScanned, totalRealJavaRedactions, totalRealJavaFiles,
                totalRealConfigRedactions, totalRealConfigFiles,
                totalCanaryRedacted, totalCanarySecrets, totalCanaryDiskLeaks);

        assertTrue(reposEvaluated >= 1);
        assertEquals(totalBuildConfigSafetyTotal, totalBuildConfigSafetyPassed);
        assertEquals(totalCanarySecrets, totalCanaryRedacted);
        assertEquals(0, totalCanaryDiskLeaks);
        assertTrue(totalRealRrfQueries >= 4);
    }

    private static List<RealRepoTarget> discoverRealRepositories() {
        var found = new ArrayList<RealRepoTarget>();
        Path cacheDir = Path.of(System.getProperty("evo.benchmark.cacheDir", "D:\\UIT\\evo-benchmark-cache"));
        Path petclinic = cacheDir.resolve("repos").resolve("spring-petclinic");
        if (Files.isRegularFile(petclinic.resolve("pom.xml"))) {
            found.add(new RealRepoTarget("spring-petclinic", petclinic,
                    List.of(".git", "target", ".mvn", "node_modules")));
        }
        Path ewolff = cacheDir.resolve("EMSE2025SAR-Replication").resolve("tool_comparison")
                .resolve("analyzed_applications").resolve("microservice");
        if (Files.isRegularFile(ewolff.resolve("pom.xml"))
                || Files.isRegularFile(ewolff.resolve("microservice-demo").resolve("pom.xml"))) {
            found.add(new RealRepoTarget("ewolff/microservice", ewolff,
                    List.of(".git", "target", ".mvn", "node_modules")));
        }
        // Always include a real Maven module from this workspace so real-repo evaluation runs on every machine/CI.
        Path cur = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 6 && cur != null; i++) {
            Path mod = cur.resolve("analyzer-maven");
            if (Files.isRegularFile(mod.resolve("pom.xml")) && Files.isDirectory(mod.resolve("src/main/java"))) {
                found.add(new RealRepoTarget("workspace:analyzer-maven", mod,
                        List.of(".git", "target", "node_modules")));
                break;
            }
            cur = cur.getParent();
        }
        return found;
    }

    private static List<String> collectRelativeFiles(Path root, List<String> excludedTopDirs, Set<String> extensions) throws Exception {
        var excluded = new HashSet<>(excludedTopDirs);
        var out = new ArrayList<String>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                Path rel = root.relativize(p);
                for (Path seg : rel) {
                    if (excluded.contains(seg.toString())) return;
                }
                String norm = rel.toString().replace('\\', '/');
                int dot = norm.lastIndexOf('.');
                if (dot >= 0 && extensions.contains(norm.substring(dot).toLowerCase(Locale.ROOT))) {
                    out.add(norm);
                }
            });
        }
        Collections.sort(out);
        return out;
    }

    private static List<String> collectRealBuildAndSpringConfigFiles(Path root, List<String> excludedTopDirs) throws Exception {
        var candidates = collectRelativeFiles(root, excludedTopDirs, Set.of(".xml", ".properties", ".yml", ".yaml"));
        var safety = new ArrayList<String>();
        for (String p : candidates) {
            String low = p.toLowerCase(Locale.ROOT);
            String name = low.substring(low.lastIndexOf('/') + 1);
            if (name.equals("pom.xml")
                    || ((name.startsWith("application") || name.startsWith("bootstrap"))
                    && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml")))) {
                safety.add(p);
            }
        }
        return safety.size() > 6 ? safety.subList(0, 6) : safety;
    }

    private static boolean isSurefireRunnableJavaTest(String path) {
        String norm = path.replace('\\', '/');
        String low = norm.toLowerCase(Locale.ROOT);
        if (low.contains("/src/main/") || low.startsWith("src/main/")) {
            return false;
        }
        boolean inTestDir = low.contains("/src/test/") || low.startsWith("src/test/")
                || low.contains("/src/it/") || low.startsWith("src/it/")
                || low.contains("/src/integrationtest/") || low.startsWith("src/integrationtest/")
                || low.contains("/src/integration-test/") || low.startsWith("src/integration-test/")
                || low.contains("/src/functionaltest/") || low.startsWith("src/functionaltest/")
                || low.contains("/test/") || low.startsWith("test/")
                || low.contains("/tests/") || low.startsWith("tests/");
        if (!inTestDir) {
            return false;
        }
        int slash = norm.lastIndexOf('/');
        String file = slash >= 0 ? norm.substring(slash + 1) : norm;
        int dot = file.lastIndexOf('.');
        String stem = dot > 0 ? file.substring(0, dot) : file;
        return stem.endsWith("Test")
                || stem.endsWith("Tests")
                || stem.endsWith("TestCase")
                || stem.endsWith("IT")
                || stem.endsWith("ITCase")
                || stem.endsWith("Spec")
                || (stem.startsWith("Test") && stem.length() > 4 && Character.isUpperCase(stem.charAt(4)))
                || (stem.startsWith("IT") && stem.length() > 2 && Character.isUpperCase(stem.charAt(2)));
    }

    /**
     * Independent lightweight Java source-reference graph built directly from real .java files on disk
     * (package + imports + simple-type token usage), used as a non-circular reference oracle for real-repo RTS.
     */
    private static Map<String, Set<String>> buildStaticJavaSourceReferenceGraph(Path root, List<String> javaRelPaths) throws Exception {
        var fqnToPath = new HashMap<String, String>();
        var pkgToPaths = new HashMap<String, List<String>>();
        var pathToSimple = new HashMap<String, String>();
        var pathToPkg = new HashMap<String, String>();
        var pathToText = new HashMap<String, String>();
        var pkgPattern = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;");
        var impPattern = java.util.regex.Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z0-9_.*]+)\\s*;");

        for (String rel : javaRelPaths) {
            String text = Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
            pathToText.put(rel, text);
            String file = rel.substring(rel.lastIndexOf('/') + 1);
            String simple = file.substring(0, file.length() - 5);
            pathToSimple.put(rel, simple);
            var pm = pkgPattern.matcher(text);
            String pkg = pm.find() ? pm.group(1) : "";
            pathToPkg.put(rel, pkg);
            if (!pkg.isEmpty()) {
                fqnToPath.putIfAbsent(pkg + "." + simple, rel);
                pkgToPaths.computeIfAbsent(pkg, k -> new ArrayList<>()).add(rel);
            }
        }

        var forward = new HashMap<String, Set<String>>();
        for (String rel : javaRelPaths) {
            String text = pathToText.get(rel);
            String ownPkg = pathToPkg.getOrDefault(rel, "");
            var deps = new LinkedHashSet<String>();
            var im = impPattern.matcher(text);
            while (im.find()) {
                String imp = im.group(1);
                if (imp.endsWith(".*")) {
                    String wildcardPkg = imp.substring(0, imp.length() - 2);
                    for (String cand : pkgToPaths.getOrDefault(wildcardPkg, List.of())) {
                        if (!cand.equals(rel) && containsIdentifier(text, pathToSimple.get(cand))) {
                            deps.add(cand);
                        }
                    }
                } else {
                    String direct = fqnToPath.get(imp);
                    if (direct != null && !direct.equals(rel)) {
                        deps.add(direct);
                    } else {
                        int lastDot = imp.lastIndexOf('.');
                        if (lastDot > 0) {
                            String owner = fqnToPath.get(imp.substring(0, lastDot));
                            if (owner != null && !owner.equals(rel)) deps.add(owner);
                        }
                    }
                }
            }
            for (String samePkg : pkgToPaths.getOrDefault(ownPkg, List.of())) {
                if (!samePkg.equals(rel) && containsIdentifier(text, pathToSimple.get(samePkg))) {
                    deps.add(samePkg);
                }
            }
            forward.put(rel, deps);
        }
        return forward;
    }

    private static boolean containsIdentifier(String text, String ident) {
        if (ident == null || ident.length() < 3) return false;
        int idx = text.indexOf(ident);
        while (idx >= 0) {
            boolean leftOk = idx == 0 || !Character.isJavaIdentifierPart(text.charAt(idx - 1));
            int end = idx + ident.length();
            boolean rightOk = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
            if (leftOk && rightOk) return true;
            idx = text.indexOf(ident, idx + 1);
        }
        return false;
    }

    private static Set<String> computeReverseSourceRefTests(
            Map<String, Set<String>> forward, Set<String> runnableTests, String modifiedProdPath) {
        var reverse = new HashMap<String, Set<String>>();
        for (var e : forward.entrySet()) {
            for (String dst : e.getValue()) {
                reverse.computeIfAbsent(dst, k -> new LinkedHashSet<>()).add(e.getKey());
            }
        }
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(modifiedProdPath);
        visited.add(modifiedProdPath);
        var affected = new TreeSet<String>();
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (runnableTests.contains(cur)) affected.add(cur);
            for (String caller : reverse.getOrDefault(cur, Set.of())) {
                if (visited.add(caller)) queue.add(caller);
            }
        }
        return affected;
    }

    /**
     * Systematically derives real developer retrieval queries from real production Java files and their
     * actual dependency edges (no repo-specific hardcoding or hand-picked queries).
     */
    private static List<RrfQueryCase> buildSystematicRealRepoRrfQueries(
            Path root, List<String> prodJava, Map<String, Set<String>> forwardDeps, int maxQueries) throws Exception {
        var prodSet = new HashSet<>(prodJava);
        var methodPattern = java.util.regex.Pattern.compile(
                "(?:public|protected|private)\\s+(?:static\\s+)?[A-Za-z0-9_<>,\\[\\]?\\s]+?\\s+([a-z][A-Za-z0-9]{7,40})\\s*\\(");
        var tokenDocFreq = new HashMap<String, Integer>();
        var tokensByFile = new HashMap<String, List<String>>();
        Set<String> commonMethods = Set.of("toString", "hashCode", "equals", "compareTo", "getClass", "iterator");

        for (String p : prodJava) {
            String text = Files.readString(root.resolve(p), StandardCharsets.UTF_8);
            var seen = new LinkedHashSet<String>();
            var m = methodPattern.matcher(text);
            while (m.find()) {
                String name = m.group(1);
                if (!commonMethods.contains(name)) seen.add(name);
            }
            tokensByFile.put(p, List.copyOf(seen));
            for (String t : seen) tokenDocFreq.merge(t, 1, Integer::sum);
        }

        var queries = new ArrayList<RrfQueryCase>();
        for (String caller : prodJava) {
            if (queries.size() >= maxQueries) break;
            var prodCallees = forwardDeps.getOrDefault(caller, Set.of()).stream()
                    .filter(prodSet::contains)
                    .sorted()
                    .toList();
            for (String callee : prodCallees) {
                if (queries.size() >= maxQueries) break;
                String distinctive = tokensByFile.getOrDefault(callee, List.of()).stream()
                        .filter(t -> tokenDocFreq.getOrDefault(t, 99) <= 2)
                        .findFirst()
                        .orElse(null);
                if (distinctive == null) continue;
                String callerFile = caller.substring(caller.lastIndexOf('/') + 1);
                String callerSimple = callerFile.substring(0, callerFile.length() - 5);
                // Query 1: Distinctive body method token in callee -> relevant = {callee, direct caller}
                queries.add(new RrfQueryCase(
                        "body-flow:" + distinctive,
                        List.of(distinctive),
                        Set.of(callee, caller)));
                if (queries.size() < maxQueries) {
                    // Query 2: Caller class symbol + callee distinctive token -> relevant = {caller, callee}
                    queries.add(new RrfQueryCase(
                            "pair:" + callerSimple + "+" + distinctive,
                            List.of(callerSimple, distinctive),
                            Set.of(caller, callee)));
                }
                break; // at most 1 pair per caller for broad coverage across the codebase
            }
        }
        return queries;
    }

    private static double mrrAtK(List<String> rankedPaths, Set<String> relevant, int k) {
        int limit = Math.min(k, rankedPaths.size());
        for (int i = 0; i < limit; i++) {
            if (relevant.contains(rankedPaths.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    // =========================================================================
    // SKG-RTS Multi-Architecture Builders & Exhaustive DFS Ground-Truth Oracle
    // =========================================================================

    private record RtsArchFixture(
            String name,
            int moduleCount,
            KnowledgeGraph graph,
            String contextId,
            Map<String, Set<String>> forwardDepsByPath,
            Set<String> testPaths,
            List<String> codeEdits,
            List<String> safetyEdits) {}

    private static Set<String> exhaustiveGroundTruthTests(RtsArchFixture arch, String modifiedPath) {
        // Independent reverse-DFS over the explicit architecture edge map + direct sibling test rule
        var reverse = new HashMap<String, Set<String>>();
        for (var e : arch.forwardDepsByPath().entrySet()) {
            for (String dst : e.getValue()) {
                reverse.computeIfAbsent(dst, k -> new LinkedHashSet<>()).add(e.getKey());
            }
        }
        var visited = new HashSet<String>();
        var stack = new ArrayDeque<String>();
        stack.push(modifiedPath);
        visited.add(modifiedPath);
        var affectedTests = new TreeSet<String>();

        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (arch.testPaths().contains(cur)) {
                affectedTests.add(cur);
            }
            for (String caller : reverse.getOrDefault(cur, Set.of())) {
                if (visited.add(caller)) {
                    stack.push(caller);
                }
            }
        }
        // Include direct sibling tests matching AffectedTestSelector contract (Foo.java -> FooTest.java / FooTests.java / FooIT.java)
        String file = modifiedPath.substring(modifiedPath.lastIndexOf('/') + 1);
        String stem = file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
        for (String tp : arch.testPaths()) {
            String tf = tp.substring(tp.lastIndexOf('/') + 1);
            if (tf.equals(stem + "Test.java") || tf.equals(stem + "Tests.java") || tf.equals(stem + "IT.java")) {
                affectedTests.add(tp);
            }
        }
        return affectedTests;
    }

    private RtsArchFixture buildMicroserviceMeshArchitecture() {
        var b = new SyntheticArchBuilder("microservice-mesh");
        var services = List.of("order", "payment", "inventory", "shipping", "billing",
                "catalog", "auth", "notification", "analytics", "gateway");
        for (String svc : services) {
            String cap = capitalize(svc);
            String repo = b.addProd(svc, cap + "Repository");
            String core = b.addProd(svc, cap + "Service");
            String ctrl = b.addProd(svc, cap + "Controller");
            b.addEdge(core, repo);
            b.addEdge(ctrl, core);
            String unitTest = b.addTest(svc, cap + "ServiceTest");
            String apiTest = b.addTest(svc, cap + "ControllerIT");
            b.addEdge(unitTest, core);
            b.addEdge(apiTest, ctrl);
        }
        // Realistic cross-service mesh edges: gateway -> order -> payment; order -> inventory; billing -> notification
        b.addEdge(b.prodPath("gateway", "GatewayService"), b.prodPath("order", "OrderController"));
        b.addEdge(b.prodPath("order", "OrderService"), b.prodPath("payment", "PaymentController"));
        b.addEdge(b.prodPath("order", "OrderService"), b.prodPath("inventory", "InventoryController"));
        b.addEdge(b.prodPath("billing", "BillingService"), b.prodPath("notification", "NotificationController"));

        return b.finish(services.size(),
                List.of(
                        b.prodPath("catalog", "CatalogService"),
                        b.prodPath("analytics", "AnalyticsRepository"),
                        b.prodPath("shipping", "ShippingController"),
                        b.prodPath("auth", "AuthService"),
                        b.prodPath("inventory", "InventoryRepository")),
                List.of());
    }

    private RtsArchFixture buildLayeredEnterpriseMonolithArchitecture() {
        var b = new SyntheticArchBuilder("layered-monolith");
        var domains = List.of("accounts", "ledger", "loans", "cards", "treasury",
                "kyc", "fraud", "reporting", "settlement", "audit");
        for (String dom : domains) {
            String cap = capitalize(dom);
            String dao = b.addProd(dom, cap + "Dao");
            String entity = b.addProd(dom, cap + "Domain");
            String app = b.addProd(dom, cap + "AppService");
            String web = b.addProd(dom, cap + "WebController");
            b.addEdge(dao, entity);
            b.addEdge(app, dao);
            b.addEdge(web, app);
            String domTest = b.addTest(dom, cap + "DomainTest");
            String webTest = b.addTest(dom, cap + "WebIT");
            b.addEdge(domTest, entity);
            b.addEdge(webTest, web);
        }
        // Horizontal cross-domain calls in enterprise monolith
        b.addEdge(b.prodPath("loans", "LoansAppService"), b.prodPath("kyc", "KycAppService"));
        b.addEdge(b.prodPath("cards", "CardsAppService"), b.prodPath("fraud", "FraudAppService"));
        b.addEdge(b.prodPath("settlement", "SettlementAppService"), b.prodPath("ledger", "LedgerAppService"));

        return b.finish(domains.size(),
                List.of(
                        b.prodPath("accounts", "AccountsWebController"),
                        b.prodPath("treasury", "TreasuryDao"),
                        b.prodPath("reporting", "ReportingAppService"),
                        b.prodPath("kyc", "KycDomain"),
                        b.prodPath("audit", "AuditWebController")),
                List.of());
    }

    private RtsArchFixture buildSharedKernelMultiModuleArchitecture() {
        var b = new SyntheticArchBuilder("shared-kernel");
        String money = b.addProd("kernel-money", "MoneyMath");
        String sec = b.addProd("kernel-sec", "TenantPrincipal");
        String moneyTest = b.addTest("kernel-money", "MoneyMathTest");
        String secTest = b.addTest("kernel-sec", "TenantPrincipalTest");
        b.addEdge(moneyTest, money);
        b.addEdge(secTest, sec);

        var features = List.of("checkout", "pricing", "discounts", "subscriptions",
                "refunds", "webhooks", "exports", "search");
        for (int i = 0; i < features.size(); i++) {
            String feat = features.get(i);
            String cap = capitalize(feat);
            String svc = b.addProd(feat, cap + "Engine");
            String api = b.addProd(feat, cap + "Api");
            b.addEdge(api, svc);
            if (i < 4) b.addEdge(svc, money);
            if (i >= 4) b.addEdge(svc, sec);
            String t1 = b.addTest(feat, cap + "EngineTest");
            String t2 = b.addTest(feat, cap + "ApiIT");
            b.addEdge(t1, svc);
            b.addEdge(t2, api);
        }
        // 10 modules (2 shared kernel + 8 features), 18 test classes
        return b.finish(2 + features.size(),
                List.of(
                        b.prodPath("checkout", "CheckoutApi"),
                        b.prodPath("pricing", "PricingEngine"),
                        b.prodPath("webhooks", "WebhooksEngine"),
                        b.prodPath("search", "SearchApi"),
                        money),
                List.of());
    }

    private RtsArchFixture buildHighFanoutUtilityLibraryArchitecture() {
        var b = new SyntheticArchBuilder("high-fanout-utility");
        String coreUtil = b.addProd("util-core", "CanonicalCodec");
        String utilTest = b.addTest("util-core", "CanonicalCodecTest");
        String utilPerfTest = b.addTest("util-core", "CanonicalCodecEdgeTest");
        b.addEdge(utilTest, coreUtil);
        b.addEdge(utilPerfTest, coreUtil);

        var consumers = List.of("csv", "json", "xml", "yaml", "avro", "parquet", "protobuf", "cbor", "msgpack");
        for (int i = 0; i < consumers.size(); i++) {
            String mod = consumers.get(i);
            String cap = capitalize(mod);
            String reader = b.addProd(mod, cap + "Reader");
            String writer = b.addProd(mod, cap + "Writer");
            // First 6 modules depend on CanonicalCodec (high fanout), last 3 are standalone format parsers
            if (i < 6) {
                b.addEdge(reader, coreUtil);
                b.addEdge(writer, coreUtil);
            }
            String rt = b.addTest(mod, cap + "ReaderTest");
            String wt = b.addTest(mod, cap + "WriterTest");
            b.addEdge(rt, reader);
            b.addEdge(wt, writer);
        }
        // 10 modules, 20 test classes
        return b.finish(1 + consumers.size(),
                List.of(
                        b.prodPath("csv", "CsvReader"),
                        b.prodPath("xml", "XmlWriter"),
                        b.prodPath("protobuf", "ProtobufReader"),
                        b.prodPath("msgpack", "MsgpackWriter"),
                        coreUtil),
                List.of());
    }

    private RtsArchFixture buildSafetyFallbackArchitecture() {
        var b = new SyntheticArchBuilder("build-config-safety-fallback");
        var mods = List.of("core", "web", "security", "batch", "events", "admin");
        for (String mod : mods) {
            String cap = capitalize(mod);
            String svc = b.addProd(mod, cap + "Service");
            String t1 = b.addTest(mod, cap + "ServiceTest");
            String t2 = b.addTest(mod, cap + "IntegrationIT");
            b.addEdge(t1, svc);
            b.addEdge(t2, svc);
        }
        // 6 modules, 12 test classes + 7 distinct safety-fallback mutation triggers
        return b.finish(mods.size(),
                List.of(b.prodPath("batch", "BatchService")),
                List.of(
                        "pom.xml",
                        "build.gradle",
                        "src/main/resources/application.yml",
                        "src/main/resources/application-prod.yml",
                        "src/main/resources/bootstrap.yml",
                        "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                        "src/main/java/com/acme/UnmappedNewFeature.java"));
    }

    private static final class SyntheticArchBuilder {
        private final String name;
        private final RepositoryIdentity repo;
        private final SnapshotIdentity snap;
        private final String snapCtxId;
        private final String codeCtxId;
        private final List<GraphEvidence> evidence = new ArrayList<>();
        private final List<KnowledgeGraph.Node> nodes = new ArrayList<>();
        private final List<KnowledgeGraph.Edge> edges = new ArrayList<>();
        private final Map<String, String> nodeIdByPath = new LinkedHashMap<>();
        private final Map<String, Set<String>> forwardDeps = new LinkedHashMap<>();
        private final Set<String> testPaths = new TreeSet<>();
        private int edgeSeq = 0;

        SyntheticArchBuilder(String name) {
            this.name = name;
            this.repo = RepositoryIdentity.fromCanonicalCoordinate("urn:e9-rts:" + name);
            this.snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("snap:" + name));
            this.snapCtxId = snap.value();
            this.codeCtxId = ContentDigest.sha256Utf8("ctx:" + name).value();
        }

        String prodPath(String module, String cls) {
            return module + "/src/main/java/com/acme/" + module.replace('-', '_') + "/" + cls + ".java";
        }

        String addProd(String module, String cls) {
            String path = prodPath(module, cls);
            register(path, "com.acme." + module.replace('-', '_') + "." + cls, false);
            return path;
        }

        String addTest(String module, String cls) {
            String path = module + "/src/test/java/com/acme/" + module.replace('-', '_') + "/" + cls + ".java";
            register(path, "com.acme." + module.replace('-', '_') + "." + cls, true);
            testPaths.add(path);
            return path;
        }

        private void register(String path, String fqn, boolean isTest) {
            if (nodeIdByPath.containsKey(path)) return;
            var docId = SourceDocumentIdentity.from(repo, path);
            var docEv = GraphEvidence.capture("source.document", Map.of("path", path, "language", "java"));
            evidence.add(docEv);
            nodes.add(new KnowledgeGraph.Node(docId.value(), snapCtxId, "SOURCE_DOCUMENT", List.of(docEv.identity())));

            String nid = ContentDigest.sha256Utf8("type:" + name + ":" + path).value();
            var span = new SourceSpan(docId, 1, 1, 20, 1);
            var declEv = GraphEvidence.capture("java.declaration", Map.of(
                    "entity", Map.of(
                            "qualifiedName", fqn,
                            "declaration", Map.of(
                                    "document", span.document().value(),
                                    "startLine", span.startLine(),
                                    "endLine", span.endLine()))));
            evidence.add(declEv);
            nodes.add(new KnowledgeGraph.Node(nid, codeCtxId, "CLASS", List.of(declEv.identity())));
            nodeIdByPath.put(path, nid);
            forwardDeps.putIfAbsent(path, new LinkedHashSet<>());
        }

        void addEdge(String srcPath, String dstPath) {
            String srcId = Objects.requireNonNull(nodeIdByPath.get(srcPath), "Missing src " + srcPath);
            String dstId = Objects.requireNonNull(nodeIdByPath.get(dstPath), "Missing dst " + dstPath);
            forwardDeps.computeIfAbsent(srcPath, k -> new LinkedHashSet<>()).add(dstPath);
            String eid = ContentDigest.sha256Utf8("edge:" + name + ":" + (++edgeSeq)).value();
            var ev = GraphEvidence.capture("java.calls", Map.of("edge", eid));
            evidence.add(ev);
            edges.add(new KnowledgeGraph.Edge(eid, codeCtxId, "java.calls", srcId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dstId), Optional.empty()),
                    List.of(ev.identity()), Optional.empty()));
        }

        RtsArchFixture finish(int moduleCount, List<String> codeEdits, List<String> safetyEdits) {
            var snapEv = GraphEvidence.capture("context.root", Map.of("ctx", snapCtxId));
            var codeEv = GraphEvidence.capture("context.root", Map.of("ctx", codeCtxId));
            evidence.add(snapEv);
            evidence.add(codeEv);
            var graph = new KnowledgeGraph(snap, List.of(
                    new KnowledgeGraph.Context(snapCtxId, snap, Optional.empty(), snapEv.identity()),
                    new KnowledgeGraph.Context(codeCtxId, snap, Optional.empty(), codeEv.identity())),
                    nodes, edges, evidence);
            return new RtsArchFixture(name, moduleCount, graph, codeCtxId, forwardDeps, testPaths, codeEdits, safetyEdits);
        }
    }

    // =========================================================================
    // Quarantine Fixture Builders & Canonical Subgraph / Leiden Helpers
    // =========================================================================

    private static void writeMultiCommunityJavaJpaProject(Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme.quarantine</groupId>
                  <artifactId>quarantine-app</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """);
        var javaFiles = new LinkedHashMap<String, String>();
        // Community 1: orders (dense internal clique)
        javaFiles.put("src/main/java/com/acme/orders/OrderEntity.java", """
                package com.acme.orders;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                @Entity
                @Table(name = "orders")
                public class OrderEntity { public long id; }
                """);
        javaFiles.put("src/main/java/com/acme/orders/OrderRepository.java", """
                package com.acme.orders;
                public class OrderRepository {
                    public OrderEntity find(long id) { return new OrderEntity(); }
                    public void save(OrderEntity e) {}
                }
                """);
        javaFiles.put("src/main/java/com/acme/orders/OrderValidator.java", """
                package com.acme.orders;
                public class OrderValidator {
                    private final OrderRepository repo;
                    public OrderValidator(OrderRepository repo) { this.repo = repo; }
                    public boolean valid(OrderEntity e) { return repo.find(e.id) != null; }
                }
                """);
        javaFiles.put("src/main/java/com/acme/orders/OrderService.java", """
                package com.acme.orders;
                import com.acme.billing.BillingService;
                public class OrderService {
                    private final OrderRepository repo;
                    private final OrderValidator validator;
                    private final BillingService billing;
                    public OrderService(OrderRepository repo, OrderValidator validator, BillingService billing) {
                        this.repo = repo; this.validator = validator; this.billing = billing;
                    }
                    public void place(OrderEntity e) {
                        if (validator.valid(e)) { repo.save(e); billing.charge(e.id); }
                    }
                }
                """);

        // Community 2: billing (dense internal clique)
        javaFiles.put("src/main/java/com/acme/billing/InvoiceEntity.java", """
                package com.acme.billing;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                @Entity
                @Table(name = "invoices")
                public class InvoiceEntity { public long id; }
                """);
        javaFiles.put("src/main/java/com/acme/billing/InvoiceRepository.java", """
                package com.acme.billing;
                public class InvoiceRepository {
                    public InvoiceEntity load(long id) { return new InvoiceEntity(); }
                    public void persist(InvoiceEntity inv) {}
                }
                """);
        javaFiles.put("src/main/java/com/acme/billing/TaxCalculator.java", """
                package com.acme.billing;
                public class TaxCalculator {
                    private final InvoiceRepository repo;
                    public TaxCalculator(InvoiceRepository repo) { this.repo = repo; }
                    public long compute(InvoiceEntity inv) { return repo.load(inv.id).id; }
                }
                """);
        javaFiles.put("src/main/java/com/acme/billing/BillingService.java", """
                package com.acme.billing;
                public class BillingService {
                    private final InvoiceRepository repo;
                    private final TaxCalculator tax;
                    public BillingService(InvoiceRepository repo, TaxCalculator tax) {
                        this.repo = repo; this.tax = tax;
                    }
                    public void charge(long id) {
                        InvoiceEntity inv = repo.load(id);
                        tax.compute(inv);
                        repo.persist(inv);
                    }
                }
                """);

        // Community 3: shipping (dense internal clique)
        javaFiles.put("src/main/java/com/acme/shipping/ShipmentEntity.java", """
                package com.acme.shipping;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                @Entity
                @Table(name = "shipments")
                public class ShipmentEntity { public long id; }
                """);
        javaFiles.put("src/main/java/com/acme/shipping/ShipmentRepository.java", """
                package com.acme.shipping;
                public class ShipmentRepository {
                    public ShipmentEntity fetch(long id) { return new ShipmentEntity(); }
                    public void store(ShipmentEntity s) {}
                }
                """);
        javaFiles.put("src/main/java/com/acme/shipping/CarrierRouter.java", """
                package com.acme.shipping;
                public class CarrierRouter {
                    private final ShipmentRepository repo;
                    public CarrierRouter(ShipmentRepository repo) { this.repo = repo; }
                    public String route(ShipmentEntity s) { return repo.fetch(s.id).toString(); }
                }
                """);
        javaFiles.put("src/main/java/com/acme/shipping/ShippingService.java", """
                package com.acme.shipping;
                public class ShippingService {
                    private final ShipmentRepository repo;
                    private final CarrierRouter router;
                    public ShippingService(ShipmentRepository repo, CarrierRouter router) {
                        this.repo = repo; this.router = router;
                    }
                    public void dispatch(long id) {
                        ShipmentEntity s = repo.fetch(id);
                        router.route(s);
                        repo.store(s);
                    }
                }
                """);

        for (var e : javaFiles.entrySet()) {
            Path p = root.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }
    }

    private static void writeAdvisoryFixtureFiles(Path root, boolean mutated) throws Exception {
        Files.createDirectories(root.resolve("db"));
        Files.createDirectories(root.resolve("infra"));
        Files.createDirectories(root.resolve("docs"));

        if (!mutated) {
            Files.writeString(root.resolve("db/V1__schema.sql"), """
                    CREATE TABLE orders (id BIGINT PRIMARY KEY);
                    """);
            Files.writeString(root.resolve("infra/main.tf"), """
                    resource "aws_s3_bucket" "orders_bucket" {
                      bucket = "acme-orders-v1"
                    }
                    """);
            Files.writeString(root.resolve("docs/ADR-001.md"), """
                    # ADR-001 Orders Boundary
                    ## Overview
                    The `OrderService` manages orders.
                    """);
            Files.writeString(root.resolve(".mcp.json"), """
                    {"mcpServers":{"evo-local":{"command":"java","env":{"APP_ENV":"dev"}}}}
                    """);
        } else {
            Files.writeString(root.resolve("db/V1__schema.sql"), """
                    CREATE TABLE orders (id BIGINT PRIMARY KEY);
                    CREATE TABLE invoices (
                        id BIGINT PRIMARY KEY,
                        order_id BIGINT REFERENCES orders(id)
                    );
                    CREATE TABLE shipments (
                        id BIGINT PRIMARY KEY,
                        order_id BIGINT REFERENCES orders(id)
                    );
                    CREATE VIEW active_shipments AS SELECT * FROM shipments;
                    CREATE TRIGGER trg_ship AFTER INSERT ON shipments EXECUTE FUNCTION audit_ship();
                    """);
            Files.writeString(root.resolve("infra/main.tf"), """
                    resource "aws_s3_bucket" "orders_bucket" {
                      bucket = "acme-orders-v2"
                    }
                    resource "aws_db_instance" "ledger_db" {
                      engine   = "postgres"
                      password = "MutatedTfSecretPassword99!"
                    }
                    module "vpc_mesh" {
                      source = "./modules/vpc"
                    }
                    output "db_endpoint" {
                      value = aws_db_instance.ledger_db.endpoint
                    }
                    """);
            Files.writeString(root.resolve("docs/ADR-001.md"), """
                    # ADR-001 Orders, Billing & Shipping Architecture
                    ## Orders Domain
                    The `com.acme.orders.OrderService` delegates to `OrderValidator` and `OrderRepository`.
                    ## Billing Domain
                    The `com.acme.billing.BillingService` computes taxes via `TaxCalculator` for `InvoiceEntity`.
                    ## Shipping Domain
                    The `com.acme.shipping.ShippingService` routes via `CarrierRouter` for `ShipmentEntity`.
                    """);
            String mcpApiToken = String.join("_", "ghp", "1234567890abcdef1234567890abcdef");
            Files.writeString(root.resolve(".mcp.json"),
                    "{\"mcpServers\":{\"evo-local\":{\"command\":\"java\",\"env\":{\"APP_ENV\":\"prod\",\"API_TOKEN\":\""
                            + mcpApiToken + "\"}}}}\n");
        }
    }

    private KnowledgeGraph runPassiveRepoAnalysis(Path repoDir, String repoCoord, List<String> exclusions, Path intake) throws Exception {
        return runPassiveRepoAnalysis(repoDir, repoCoord, exclusions, intake, false);
    }

    private KnowledgeGraph runPassiveRepoAnalysis(Path repoDir, String repoCoord, List<String> exclusions, Path intake, boolean includeTests) throws Exception {
        Path cache = Files.createTempDirectory(temp, "m2-");
        var runner = new LocalAnalysisRunner(
                new LocalAnalysisRunner.Input(
                        repoDir,
                        RepositoryIdentity.fromCanonicalCoordinate(repoCoord),
                        Path.of(System.getProperty("java.home")),
                        21,
                        cache,
                        Optional.empty(),
                        includeTests,
                        includeTests,
                        exclusions,
                        Optional.empty(),
                        LocalAnalysisRunner.SpringInput.absent()),
                intake);

        var store = new InMemoryGraphStore();
        var reports = new PublishedReports();
        try (var jobs = new AnalysisJobs(List.of(runner.registration("q")), store, reports,
                new AnalysisJobs.Limits(1, 1, 4, 90_000, 1000))) {
            UUID jobId = UUID.fromString(jobs.submit("q", "run").job().id());
            AnalysisJobs.View view = jobs.get(jobId);
            long deadline = System.currentTimeMillis() + 90_000L;
            while (view.state() == AnalysisJobs.State.QUEUED || view.state() == AnalysisJobs.State.RUNNING) {
                if (System.currentTimeMillis() > deadline) {
                    fail("Timed out waiting for quarantine analysis of " + repoDir);
                }
                Thread.sleep(20);
                view = jobs.get(jobId);
            }
            assertTrue(Set.of(AnalysisJobs.State.SUCCEEDED, AnalysisJobs.State.PARTIAL).contains(view.state()),
                    "Unexpected job state: " + view);
            return store.load(view.result().orElseThrow().graph().orElseThrow()).orElseThrow();
        }
    }

    private static String primaryAnalysisContext(KnowledgeGraph graph) {
        return graph.contexts().stream()
                .filter(c -> c.analysis().isPresent())
                .map(KnowledgeGraph.Context::identity)
                .sorted()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Java analysis context"));
    }

    private static ContentDigest canonicalAnalysisSubgraphDigest(KnowledgeGraph graph, String ctx) {
        var queries = new ArchitectureQueryService(graph);
        var req = new ArchitectureQuery.Request(
                graph.identity(), ctx, ArchitectureQuery.Scope.TYPE,
                ArchitectureQuery.Projection.BOUNDED_UNION, ArchitectureQuery.Direction.OUTGOING,
                Set.of("graph.depends-on"), ArchitectureQuery.Population.all(), false,
                Optional.empty(), ArchitectureQuery.Limits.defaults());
        var proj = queries.metricProjection(req).items().getFirst();
        var canonicalEntities = proj.entities().stream()
                .map(e -> List.of(e.identity(), e.kind(), e.qualifiedName().orElse(""), e.origin().orElse("")))
                .sorted(Comparator.comparing(Object::toString))
                .toList();
        var canonicalEdges = proj.edges().stream()
                .map(e -> List.of(e.source(), e.target(), e.kinds(), e.classification().name(), e.supportCount()))
                .sorted(Comparator.comparing(Object::toString))
                .toList();
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "entities", canonicalEntities,
                "edges", canonicalEdges)));
    }

    private static LeidenCommunityDetector.Result computeCanonicalLeiden(KnowledgeGraph graph, String ctx) {
        var queries = new ArchitectureQueryService(graph);
        var req = new ArchitectureQuery.Request(
                graph.identity(), ctx, ArchitectureQuery.Scope.TYPE,
                ArchitectureQuery.Projection.BOUNDED_UNION, ArchitectureQuery.Direction.OUTGOING,
                Set.of("graph.depends-on"), ArchitectureQuery.Population.all(), false,
                Optional.empty(), ArchitectureQuery.Limits.defaults());
        var proj = queries.metricProjection(req).items().getFirst();
        var projectIds = new HashSet<String>();
        var leidenNodes = new ArrayList<LeidenCommunityDetector.NodeInput>();
        for (var e : proj.entities()) {
            if ("PROJECT".equals(e.origin().orElse(""))) {
                projectIds.add(e.identity());
                String label = e.qualifiedName().or(e::spelling).orElse(e.identity());
                int dot = label.lastIndexOf('.');
                String pkg = dot > 0 ? label.substring(0, dot) : "";
                leidenNodes.add(new LeidenCommunityDetector.NodeInput(e.identity(), label, pkg, e.kind()));
            }
        }
        var leidenEdges = new ArrayList<LeidenCommunityDetector.EdgeInput>();
        for (var edge : proj.edges()) {
            if (projectIds.contains(edge.source()) && projectIds.contains(edge.target())) {
                String kind = edge.kinds().isEmpty() ? "graph.depends-on" : edge.kinds().getFirst();
                leidenEdges.add(new LeidenCommunityDetector.EdgeInput(edge.source(), edge.target(),
                        Math.max(1.0, edge.supportCount()), kind));
            }
        }
        return LeidenCommunityDetector.detect(leidenNodes, leidenEdges, () -> {});
    }

    // =========================================================================
    // Hybrid RRF Multi-Module Benchmark Fixture & Recall@K Helpers
    // =========================================================================

    private record RrfQueryCase(String id, List<String> seeds, Set<String> relevantPaths) {}

    private record RrfBenchmarkFixture(KnowledgeGraph graph, String contextId, int fileCount, List<RrfQueryCase> queries) {}

    private static RrfBenchmarkFixture buildMultiModuleRrfBenchmarkFixture() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:e9-rrf:multi-module-platform");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("e9-rrf-snap-v1"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("e9-rrf-mod-v1");
        String ctx = analysis.value();
        var mod = ModuleIdentity.from(repo, ".");

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", mod, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", mod, "path", "."));
        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv, modEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        nodes.add(new KnowledgeGraph.Node(mod.value(), ctx, "MODULE", List.of(modEv.identity())));

        record ModSpec(String modName, String bodyToken, String secondaryToken) {}
        var modules = List.of(
                new ModSpec("billing", "merkleLedgerDigestCheck", "invoiceProrationRule"),
                new ModSpec("orders", "idempotencyFenceToken", "sagaCompensationHook"),
                new ModSpec("inventory", "warehouseBinReservation", "stockBackorderQueue"),
                new ModSpec("shipping", "customsTariffManifest", "carrierLabelWebhook"),
                new ModSpec("auth", "pkceChallengeVerifier", "jwksKeyRotationWindow"),
                new ModSpec("telemetry", "otelSpanSamplerBudget", "prometheusScrapeGuard"));

        // 6 modules x 6 files = 36 Java source files with realistic architectural layers + body-only domain markers
        var files = new LinkedHashMap<String, String>();
        var classToPath = new LinkedHashMap<String, String>();
        var depPairs = new ArrayList<List<String>>();

        for (var m : modules) {
            String pkg = "com.acme." + m.modName();
            String cap = capitalize(m.modName());
            String base = "modules/" + m.modName() + "/src/main/java/com/acme/" + m.modName() + "/";

            String apiCls = cap + "GatewayController";
            String orchCls = cap + "Orchestrator";
            String coreCls = cap + "CoreEngine";
            String policyCls = cap + "PolicyEvaluator";
            String storeCls = cap + "PersistenceStore";
            String workerCls = cap + "AsyncWorker";

            files.put(base + apiCls + ".java", "package " + pkg + ";\npublic class " + apiCls
                    + " { " + orchCls + " orch; void handleRequest() { orch.coordinate(); } }\n");
            files.put(base + orchCls + ".java", "package " + pkg + ";\npublic class " + orchCls
                    + " { " + coreCls + " core; " + policyCls + " policy; void coordinate() { policy.check(); core.execute(); } }\n");
            files.put(base + coreCls + ".java", "package " + pkg + ";\npublic class " + coreCls
                    + " { " + storeCls + " store; void execute() { String tag = \"" + m.bodyToken() + "\"; store.commit(tag); } }\n");
            files.put(base + policyCls + ".java", "package " + pkg + ";\npublic class " + policyCls
                    + " { boolean check() { return \"" + m.secondaryToken() + "\".length() > 0; } }\n");
            files.put(base + storeCls + ".java", "package " + pkg + ";\npublic class " + storeCls
                    + " { void commit(String ref) {} }\n");
            files.put(base + workerCls + ".java", "package " + pkg + ";\npublic class " + workerCls
                    + " { " + coreCls + " core; void poll() { core.execute(); } }\n");

            for (String c : List.of(apiCls, orchCls, coreCls, policyCls, storeCls, workerCls)) {
                classToPath.put(m.modName() + "." + c, base + c + ".java");
            }
            // Layered dependency topology: API -> Orchestrator -> CoreEngine -> PersistenceStore; Orchestrator -> Policy; Worker -> CoreEngine
            depPairs.add(List.of(m.modName() + "." + apiCls, m.modName() + "." + orchCls));
            depPairs.add(List.of(m.modName() + "." + orchCls, m.modName() + "." + coreCls));
            depPairs.add(List.of(m.modName() + "." + orchCls, m.modName() + "." + policyCls));
            depPairs.add(List.of(m.modName() + "." + coreCls, m.modName() + "." + storeCls));
            depPairs.add(List.of(m.modName() + "." + workerCls, m.modName() + "." + coreCls));
        }

        // Add distractor utility files (12 files) so naive retrieval cannot trivially hit everything in top-5
        for (int i = 0; i < 12; i++) {
            String cls = "CommonHelper" + String.format("%02d", i);
            String p = "modules/common/src/main/java/com/acme/common/" + cls + ".java";
            files.put(p, "package com.acme.common;\npublic class " + cls
                    + " { void noop() { String x = \"genericUtilityRoutine" + i + "\"; } }\n");
            classToPath.put("common." + cls, p);
        }

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
        for (var e : classToPath.entrySet()) {
            String key = e.getKey();
            String modName = key.substring(0, key.indexOf('.'));
            String simple = key.substring(key.indexOf('.') + 1);
            String pkg = "com.acme." + modName;
            var ent = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                    JavaSymbolName.topLevelType(pkg, simple).canonicalName(), Optional.empty());
            var decl = GraphEvidence.capture("java.declaration", Map.of(
                    "entity", Map.of(
                            "identity", ent.identity().value(),
                            "origin", "PROJECT",
                            "kind", "TYPE",
                            "canonicalName", ent.canonicalName(),
                            "declaration", Map.of("document", docIds.get(e.getValue()))),
                    "spelling", simple,
                    "status", "RESOLVED"));
            evidence.add(decl);
            nodes.add(new KnowledgeGraph.Node(ent.identity().value(), ctx, "TYPE", List.of(decl.identity())));
            typeIds.put(key, ent.identity().value());
        }

        for (var pair : depPairs) {
            String src = typeIds.get(pair.get(0));
            String dst = typeIds.get(pair.get(1));
            var refEv = GraphEvidence.capture("java.reference", Map.of("from", src, "to", dst));
            evidence.add(refEv);
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("rrf-dep", pair), ctx, "java.calls", src,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                    List.of(refEv.identity()), Optional.empty()));
        }

        var graph = new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(ctx, snap, Optional.of(analysis), manEv.identity())),
                nodes, edges, evidence);

        // 12 realistic queries combining body-only tokens + structural 1-hop dependencies + snake_case/partial symbols
        var queries = new ArrayList<RrfQueryCase>();
        for (var m : modules) {
            String cap = capitalize(m.modName());
            String corePath = classToPath.get(m.modName() + "." + cap + "CoreEngine");
            String storePath = classToPath.get(m.modName() + "." + cap + "PersistenceStore");
            String policyPath = classToPath.get(m.modName() + "." + cap + "PolicyEvaluator");
            String orchPath = classToPath.get(m.modName() + "." + cap + "Orchestrator");

            // Query A: Body-only token + persistence target (BM25 finds CoreEngine, PPR reaches PersistenceStore!)
            queries.add(new RrfQueryCase(
                    m.modName() + "-body-plus-graph",
                    List.of(m.bodyToken()),
                    Set.of(corePath, storePath)));

            // Query B: Secondary body token + orchestrator symbol (requires BOTH body BM25 AND structural/symbol RRF!)
            queries.add(new RrfQueryCase(
                    m.modName() + "-policy-and-orch",
                    List.of(m.secondaryToken(), cap + "Orchestrator"),
                    Set.of(policyPath, orchPath, corePath)));
        }

        return new RrfBenchmarkFixture(graph, ctx, files.size(), queries);
    }

    private static KnowledgeGraph buildCapturedFilesOnlyGraph(Map<String, String> files) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:e9-redact:secrets");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("e9-redact-snap"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("e9-redact-mod");
        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of()));
        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        for (var e : files.entrySet()) {
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
        return new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(analysis.value(), snap, Optional.of(analysis), manEv.identity())),
                nodes, List.of(), evidence);
    }

    private static double recallAtK(List<String> rankedPaths, Set<String> relevant, int k) {
        if (relevant.isEmpty()) return 1.0;
        int hits = 0;
        int limit = Math.min(k, rankedPaths.size());
        for (int i = 0; i < limit; i++) {
            if (relevant.contains(rankedPaths.get(i))) {
                hits++;
            }
        }
        return (double) hits / relevant.size();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
