package com.evolution.backend.query;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.Entity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.backend.query.CapturedRepair.ImpactRichnessSchedule;
import com.evolution.backend.query.CapturedRepair.PropagationStep;
import com.evolution.backend.query.CapturedRepair.ScheduledTarget;
import com.evolution.backend.query.CapturedRepair.TargetEvidenceProfile;
import com.evolution.backend.query.CapturedRepair.VerifiedSkgEdge;
import com.evolution.backend.query.llm.LlmTransport;
import com.evolution.backend.query.repair.RepairContextPlanner;
import com.evolution.backend.query.repair.RepairContextPlanner.CalleeContext;
import com.evolution.backend.query.repair.RepairContextPlanner.CompressedContext;
import com.evolution.backend.query.repair.RepairContextPlanner.ContextRenderMode;
import com.evolution.backend.query.repair.RepairContextPlanner.StaticEvidenceHint;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class SaltContextCompressionAndSchedulingTest {

    @Test
    void signatureCompressionAchievesAtLeast60PercentTokenReductionAndPreservesFullBodyForEditedAndUnknownEntities() {
        var fixture = buildRichSaltCompressionGraph();

        var directEditPaths = Set.of("src/main/java/com/acme/orders/OrderController.java");
        var candidatePaths = List.of(
                "src/main/java/com/acme/orders/OrderController.java",
                "src/main/java/com/acme/orders/PaymentGateway.java",
                "src/main/java/com/acme/orders/InventoryLedger.java",
                "src/main/java/com/acme/orders/TaxCalculator.java",
                "src/main/java/com/acme/orders/ReceiptFormatter.java",
                "src/main/java/com/acme/orders/AuditDispatcher.java",
                "src/main/java/com/acme/orders/DynamicPluginBridge.java");

        CompressedContext compressed = RepairContextPlanner.compressContext(
                fixture.graph(),
                fixture.context(),
                fixture.graph().snapshot().value(),
                directEditPaths,
                Set.of(fixture.entityIds().get("OrderController")),
                candidatePaths,
                () -> StopReason.NONE);

        assertEquals(7, compressed.contexts().size());
        var byPath = new HashMap<String, CalleeContext>();
        for (var cc : compressed.contexts()) byPath.put(cc.path(), cc);

        // 1. Directly edited entity keeps FULL_BODY
        var edited = byPath.get("src/main/java/com/acme/orders/OrderController.java");
        assertNotNull(edited);
        assertEquals(ContextRenderMode.FULL_BODY, edited.renderMode());
        assertTrue(edited.renderedContent().contains("paymentGateway.charge(order)"));

        // 2. Unresolved UNKNOWN entity (has CAPABILITY_GAP) keeps FULL_BODY
        var unknown = byPath.get("src/main/java/com/acme/orders/DynamicPluginBridge.java");
        assertNotNull(unknown);
        assertEquals(ContextRenderMode.FULL_BODY, unknown.renderMode());
        assertTrue(unknown.unknownOrUnresolved());

        // 3. Out-of-edit-scope resolved SKG callees emit ONLY compact Signature(n) + 1-line Javadoc
        for (String calleePath : List.of(
                "src/main/java/com/acme/orders/PaymentGateway.java",
                "src/main/java/com/acme/orders/InventoryLedger.java",
                "src/main/java/com/acme/orders/TaxCalculator.java",
                "src/main/java/com/acme/orders/ReceiptFormatter.java",
                "src/main/java/com/acme/orders/AuditDispatcher.java")) {
            var cc = byPath.get(calleePath);
            assertNotNull(cc, "Missing callee context for " + calleePath);
            assertEquals(ContextRenderMode.SIGNATURE_ONLY, cc.renderMode(), calleePath + " must use SIGNATURE_ONLY");
            assertTrue(cc.resolvedInSkg());
            assertFalse(cc.unknownOrUnresolved());
            assertFalse(cc.javadocSummary().isBlank(), calleePath + " must keep 1-line Javadoc summary");
            assertFalse(cc.signature().isBlank(), calleePath + " must keep compact Signature(n)");
            assertFalse(cc.renderedContent().contains("INTERNAL_IMPLEMENTATION_BODY_SENTINEL"),
                    "Out-of-edit-scope callee body must be stripped: " + cc.renderedContent());
            assertTrue(cc.estimatedTokens() < cc.fullBodyTokens(),
                    "Compressed callee tokens must be strictly less than full body tokens");
        }

        // 4. Quantitative token reduction >= 60% vs full-body baseline
        System.out.printf(Locale.ROOT,
                "[WP2.4-SALT-METRICS] fullBodyBaselineTokens=%d compressedTokens=%d tokenReductionPercent=%.2f%%%n",
                compressed.fullBodyBaselineTokens(),
                compressed.compressedTokens(),
                compressed.tokenReductionPercent());
        assertTrue(compressed.fullBodyBaselineTokens() > 400, "Baseline should be substantial");
        assertTrue(compressed.tokenReductionPercent() >= 60.0,
                String.format(Locale.ROOT, "Expected >= 60.0%% token reduction, measured %.2f%% (%d -> %d tokens)",
                        compressed.tokenReductionPercent(),
                        compressed.fullBodyBaselineTokens(),
                        compressed.compressedTokens()));

        // 5. Every emitted static evidence hint line is <= 30 tokens and preserves SpringTruth + SliceHint syntax
        assertFalse(compressed.hints().isEmpty());
        boolean sawSpringTruth = false;
        boolean sawSliceHint = false;
        for (StaticEvidenceHint hint : compressed.hints()) {
            for (String line : hint.lines()) {
                int tok = RepairContextPlanner.estimateTokens(line);
                assertTrue(tok <= RepairContextPlanner.MAX_STATIC_HINT_TOKENS,
                        "Static evidence hint exceeded 30 tokens (" + tok + "): " + line);
                if (line.startsWith(RepairContextPlanner.SPRING_TRUTH_HINT_PREFIX)) sawSpringTruth = true;
                if (line.startsWith(RepairContextPlanner.SLICE_HINT_PREFIX)) sawSliceHint = true;
            }
        }
        assertTrue(sawSpringTruth, "Expected # SpringTruth: compact hint");
        assertTrue(sawSliceHint, "Expected # SliceHint: compact hint");

        // 6. RepoMapBuilder.buildCompressed integrates Signature(n) + <=30-token hints cleanly
        var compressedMap = RepoMapBuilder.buildCompressed(
                fixture.graph(),
                Optional.of(fixture.context()),
                List.of("OrderController"),
                10,
                4_000,
                new RepoMapBuilder.SaltCompressionOptions(directEditPaths, Set.of(), true, true),
                () -> StopReason.NONE,
                null);
        assertTrue(compressedMap.algorithm().endsWith("+salt-sig-v1"));
        assertTrue(compressedMap.renderedMap().contains("# SpringTruth:"));
        assertTrue(compressedMap.renderedMap().contains("# SliceHint:"));
        assertFalse(compressedMap.renderedMap().contains("INTERNAL_IMPLEMENTATION_BODY_SENTINEL"));
    }

    @Test
    void compactStaticHintsStayWithin30TokensAndPreserveLlmRedactV2Redaction() {
        assertEquals("llm-redact:v2", LlmTransport.REDACTION_VERSION);
        // Assemble synthetic credential dynamically via String.join (zero raw secret literals)
        String syntheticSecret = String.join("_", "ghp", "1234567890abcdef1234567890abcdef");

        var verboseWitness = new LinkedHashMap<String, String>();
        verboseWitness.put("profile", "prod");
        verboseWitness.put("cache", "true");
        verboseWitness.put("token", syntheticSecret);
        verboseWitness.put("region", "us-east-1-very-long-cluster-zone");

        String springHint = RepairContextPlanner.formatSpringTruthHint(
                "com.acme.orders.PaymentGatewayPrimaryBean",
                "MAY",
                75.0,
                verboseWitness,
                List.of("com.acme.orders.FallbackPaymentGateway", "com.acme.orders.MockPaymentGateway", "ExtraPeer"));
        assertTrue(springHint.startsWith("# SpringTruth: "));
        assertFalse(springHint.contains(syntheticSecret), "Secret must be redacted by llm-redact:v2");
        assertTrue(RepairContextPlanner.estimateTokens(springHint) <= 30,
                "SpringTruth hint exceeded 30 tokens: " + springHint);

        String sliceHint = RepairContextPlanner.formatSliceHint(
                List.of("com.acme.orders.OrderController.createOrder", "com.acme.orders.BatchJob.replay", "ThirdCaller"),
                List.of("com.acme.orders.PaymentGateway.charge", "com.acme.orders.InventoryLedger.reserve", "ThirdCallee"));
        assertTrue(sliceHint.startsWith("# SliceHint: "));
        assertTrue(RepairContextPlanner.estimateTokens(sliceHint) <= 30,
                "SliceHint exceeded 30 tokens: " + sliceHint);

        // Verify signature extraction also runs llm-redact:v2 before emitting signatures/Javadoc
        String pyWithSecret = String.join("\n",
                "def charge_customer(order_id: str) -> dict:",
                "    \"\"\"Charges order with key " + syntheticSecret + " safely.\"\"\"",
                "    token = \"" + syntheticSecret + "\"",
                "    return {'ok': True}");
        var extracted = RepairContextPlanner.extractCompactSignatureAndJavadoc("billing/client.py", "python", pyWithSecret);
        assertFalse(extracted.rendered().contains(syntheticSecret), "Python signature extraction must redact secrets");
        assertTrue(extracted.signature().contains("def charge_customer(order_id: str) -> dict:"));
        assertFalse(extracted.rendered().contains("return {'ok': True}"));

        // Verify TypeScript signature extraction omits function body
        String tsSource = """
                /** Formats settled ledger statements for downstream consumers. */
                export class StatementService {
                  format(id: string): string {
                    const internalVar = "BODY_ONLY_VALUE";
                    return internalVar + id;
                  }
                }
                """;
        var tsExtracted = RepairContextPlanner.extractCompactSignatureAndJavadoc("src/statement.ts", "typescript", tsSource);
        assertTrue(tsExtracted.javadocSummary().contains("Formats settled ledger statements"));
        assertTrue(tsExtracted.signature().contains("export class StatementService"));
        assertFalse(tsExtracted.rendered().contains("BODY_ONLY_VALUE"));
    }

    @Test
    void impactRichnessSchedulerRanksStrictlyByVerifiedSkgEdgesAndShrinksDependentsIncrementally() {
        // Build targets:
        // - hubB: 3 verified unresolved neighbors (A, C, D), richness 2/4 = 0.50
        // - speculativeX: 0 verified neighbors, 4 speculative/advisory neighbors, richness 1.0 -> MUST have Impact=0!
        // - leafA & leafC: both have 1 verified neighbor (hubB), leafA richness=0.75 > leafC richness=0.50
        // - tie1 & tie2: equal Impact=1 (connected to each other), equal Richness=0.50 -> tie-break entityId ASC!
        var profiles = List.of(
                new TargetEvidenceProfile("ent:tie2", "Tie2", "Tie2.java", 1, 1, Set.of()),
                new TargetEvidenceProfile("ent:tie1", "Tie1", "Tie1.java", 1, 1, Set.of()),
                new TargetEvidenceProfile("ent:specX", "SpecX", "SpecX.java", 4, 0, Set.of()),
                new TargetEvidenceProfile("ent:leafD", "LeafD", "LeafD.java", 1, 1, Set.of("ent:hubB")),
                new TargetEvidenceProfile("ent:leafC", "LeafC", "LeafC.java", 1, 1, Set.of("ent:hubB")),
                new TargetEvidenceProfile("ent:leafA", "LeafA", "LeafA.java", 3, 1, Set.of("ent:hubB")),
                new TargetEvidenceProfile("ent:hubB", "HubB", "HubB.java", 2, 2, Set.of()));

        var edges = List.of(
                // Verified SKG edges
                new VerifiedSkgEdge("ent:leafA", "ent:hubB", "java.calls", true),
                new VerifiedSkgEdge("ent:leafC", "ent:hubB", "java.calls", true),
                new VerifiedSkgEdge("ent:leafD", "ent:hubB", "graph.depends-on", true),
                new VerifiedSkgEdge("ent:tie1", "ent:tie2", "java.calls", true),
                // Speculative / advisory edges MUST be excluded from Impact(m)!
                new VerifiedSkgEdge("ent:specX", "ent:hubB", "polyglot.service-call.candidate", false),
                new VerifiedSkgEdge("ent:specX", "ent:leafA", "advisory.doc-mention", false),
                new VerifiedSkgEdge("ent:specX", "ent:leafC", "advisory.sql-map", false),
                new VerifiedSkgEdge("ent:specX", "ent:leafD", "speculative.dynamic", false));

        ImpactRichnessSchedule schedule = CapturedRepair.createImpactRichnessSchedule(profiles, edges);
        List<ScheduledTarget> initial = schedule.rankedTargets();

        assertEquals(7, initial.size());
        // Rank 1: hubB (Impact=3, verified neighbors=[leafA, leafC, leafD])
        assertEquals("ent:hubB", initial.get(0).entityId());
        assertEquals(3, initial.get(0).impact());
        assertEquals(new BigDecimal("0.500000"), initial.get(0).richness());
        assertEquals(List.of("ent:leafA", "ent:leafC", "ent:leafD"), initial.get(0).verifiedUnresolvedNeighbors());

        // Next group has Impact=1: leafA (richness 0.75) > leafC/leafD/tie1/tie2 (richness 0.50, sorted by entityId ASC)
        assertEquals("ent:leafA", initial.get(1).entityId());
        assertEquals(1, initial.get(1).impact());
        assertEquals(new BigDecimal("0.750000"), initial.get(1).richness());
        assertEquals("ent:leafC", initial.get(2).entityId());
        assertEquals("ent:leafD", initial.get(3).entityId());
        assertEquals("ent:tie1", initial.get(4).entityId());
        assertEquals("ent:tie2", initial.get(5).entityId());

        // Last: specX has Impact=0 because ALL its 4 edges are speculative/advisory!
        assertEquals("ent:specX", initial.get(6).entityId());
        assertEquals(0, initial.get(6).impact());
        assertEquals(new BigDecimal("1.000000"), initial.get(6).richness());

        // I2 determinism: shuffled input yields identical canonical SHA-256 digest
        var shuffledProfiles = new ArrayList<>(profiles);
        Collections.reverse(shuffledProfiles);
        var shuffledEdges = new ArrayList<>(edges);
        Collections.reverse(shuffledEdges);
        ImpactRichnessSchedule second = CapturedRepair.createImpactRichnessSchedule(shuffledProfiles, shuffledEdges);
        assertEquals(schedule.canonicalDigest(), second.canonicalDigest());
        assertEquals(initial, second.rankedTargets());

        // Incremental deterministic re-propagation after verified patch lands on ent:hubB
        PropagationStep step1 = schedule.applyVerifiedPatch("ent:hubB", 1);
        assertEquals(1, step1.stepNumber());
        assertEquals("ent:hubB", step1.patchedEntityId());
        // leafA, leafC, leafD were each blocked ONLY by hubB with 1 unknown -> all 3 auto-resolve (NOFEEDBACK savings!)
        assertEquals(List.of("ent:leafA", "ent:leafC", "ent:leafD"), step1.autoResolvedDependents());
        assertEquals(3, schedule.autoResolvedCount());
        assertEquals(1, schedule.directPatchesApplied());
        assertEquals(3, schedule.remainingCount());
        assertTrue(schedule.noFeedbackSavingsPercent() > 40.0);

        // Updated queue now has tie1 (Impact=1) > tie2 (Impact=1) > specX (Impact=0)
        assertEquals(List.of("ent:tie1", "ent:tie2", "ent:specX"),
                step1.updatedQueue().stream().map(ScheduledTarget::entityId).toList());
    }

    @Test
    void graphBackedSchedulerAndArchitectureAgentExcludeAdvisoryEdgesAndPreserveToolSchemaCount() {
        var fixture = buildRichSaltCompressionGraph();
        var targets = List.of(
                fixture.entityIds().get("OrderController"),
                fixture.entityIds().get("PaymentGateway"),
                fixture.entityIds().get("InventoryLedger"),
                fixture.entityIds().get("DynamicPluginBridge"));

        ImpactRichnessSchedule agentSchedule = ArchitectureAgent.scheduleArchitectureTargets(
                fixture.graph(), fixture.context(), targets);
        var ranked = agentSchedule.rankedTargets();
        assertEquals(4, ranked.size());
        // OrderController has 2 verified unresolved outgoing callees (PaymentGateway, InventoryLedger);
        // speculative candidate edge to DynamicPluginBridge MUST be excluded from Impact!
        assertEquals(fixture.entityIds().get("OrderController"), ranked.getFirst().entityId());
        assertEquals(2, ranked.getFirst().impact());

        var bridgeRow = ranked.stream()
                .filter(t -> t.entityId().equals(fixture.entityIds().get("DynamicPluginBridge")))
                .findFirst().orElseThrow();
        assertEquals(0, bridgeRow.impact(), "Speculative .candidate edge must not inflate verified SKG Impact");

        // Compact hints via ArchitectureAgent helper respect <= 30 token cap
        var hints = ArchitectureAgent.compactHintsForEntities(
                fixture.graph(), fixture.context(), fixture.graph().snapshot().value(), targets, () -> StopReason.NONE);
        assertFalse(hints.isEmpty());
        for (var h : hints) {
            for (String line : h.lines()) {
                assertTrue(RepairContextPlanner.estimateTokens(line) <= 30, "Exceeded 30 tokens: " + line);
            }
        }

        // Preserve Moonshot4 tool schema count contract (18 without source, 22 with source)
        assertEquals(18, ArchitectureAgent.toolSchemas(false).size());
        assertEquals(22, ArchitectureAgent.toolSchemas(true).size());
    }

    // -------------------------------------------------------------------------
    // Fixture Builder
    // -------------------------------------------------------------------------

    private record Fixture(KnowledgeGraph graph, String context, Map<String, String> entityIds) {}

    private static Fixture buildRichSaltCompressionGraph() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/salt-wp24.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("salt-wp24-snap"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("salt-wp24-mod");
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
        files.put("src/main/java/com/acme/orders/OrderController.java", """
                package com.acme.orders;
                /** Entry point coordinating order checkout. */
                public class OrderController {
                    private final PaymentGateway paymentGateway;
                    private final InventoryLedger inventoryLedger;
                    public OrderController(PaymentGateway paymentGateway, InventoryLedger inventoryLedger) {
                        this.paymentGateway = paymentGateway;
                        this.inventoryLedger = inventoryLedger;
                    }
                    public String create(String order) {
                        inventoryLedger.reserve(order);
                        return paymentGateway.charge(order);
                    }
                }
                """);
        files.put("src/main/java/com/acme/orders/PaymentGateway.java",
                verboseJavaCallee("PaymentGateway", "Executes idempotent payment settlement.", "charge", "String order", "String"));
        files.put("src/main/java/com/acme/orders/InventoryLedger.java",
                verboseJavaCallee("InventoryLedger", "Reserves stock units in ledger.", "reserve", "String order", "boolean"));
        files.put("src/main/java/com/acme/orders/TaxCalculator.java",
                verboseJavaCallee("TaxCalculator", "Computes regional sales tax totals.", "compute", "long cents", "long"));
        files.put("src/main/java/com/acme/orders/ReceiptFormatter.java",
                verboseJavaCallee("ReceiptFormatter", "Formats human-readable order receipts.", "format", "String id", "String"));
        files.put("src/main/java/com/acme/orders/AuditDispatcher.java",
                verboseJavaCallee("AuditDispatcher", "Publishes signed compliance audit records.", "dispatch", "String event", "void"));
        files.put("src/main/java/com/acme/orders/DynamicPluginBridge.java", """
                package com.acme.orders;
                /** Reflective bridge with unresolved target. */
                public class DynamicPluginBridge {
                    public Object invoke(String name) {
                        return name;
                    }
                }
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

        var entityIds = new LinkedHashMap<String, String>();
        for (String cls : List.of("OrderController", "PaymentGateway", "InventoryLedger",
                "TaxCalculator", "ReceiptFormatter", "AuditDispatcher", "DynamicPluginBridge")) {
            String path = "src/main/java/com/acme/orders/" + cls + ".java";
            var ent = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                    JavaSymbolName.topLevelType("com.acme.orders", cls).canonicalName(), Optional.empty());
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
            var nodeEvs = new ArrayList<ContentDigest>(List.of(decl.identity()));
            if ("PaymentGateway".equals(cls)) {
                var springEv = GraphEvidence.capture("spring.symbolic-region", Map.of(
                        "beanName", "paymentGateway",
                        "classification", "MAY",
                        "probabilityPercent", new BigDecimal("75.0"),
                        "witness", Map.of("profile", "prod", "cache", "true"),
                        "peers", List.of("fallbackGateway")));
                evidence.add(springEv);
                nodeEvs.add(springEv.identity());
            }
            nodes.add(new KnowledgeGraph.Node(ent.identity().value(), ctx, "TYPE", nodeEvs));
            entityIds.put(cls, ent.identity().value());
        }

        // Attach CAPABILITY_GAP for DynamicPluginBridge so it is recognized as unresolved UNKNOWN
        var gapEv = GraphEvidence.capture("evidence.gap", Map.of(
                "reasonCode", "REFLECTION_UNRESOLVED",
                "mechanismCategory", "java.reflection",
                "sourceSpans", List.of(Map.of("document", docIds.get("src/main/java/com/acme/orders/DynamicPluginBridge.java"))),
                "subject", Map.of("kind", "ENTITY", "identity", entityIds.get("DynamicPluginBridge"))));
        evidence.add(gapEv);
        nodes.add(new KnowledgeGraph.Node(
                KnowledgeGraph.derivedIdentity("gap", List.of("dynamic-bridge")),
                ctx, "CAPABILITY_GAP", List.of(gapEv.identity())));

        // Verified SKG call edges
        addEdge(edges, evidence, ctx, "java.calls", entityIds.get("OrderController"), entityIds.get("PaymentGateway"), false);
        addEdge(edges, evidence, ctx, "java.calls", entityIds.get("OrderController"), entityIds.get("InventoryLedger"), false);
        addEdge(edges, evidence, ctx, "java.calls", entityIds.get("PaymentGateway"), entityIds.get("TaxCalculator"), false);
        addEdge(edges, evidence, ctx, "java.calls", entityIds.get("PaymentGateway"), entityIds.get("ReceiptFormatter"), false);
        addEdge(edges, evidence, ctx, "java.calls", entityIds.get("InventoryLedger"), entityIds.get("AuditDispatcher"), false);
        // Speculative candidate edge (must be ignored by verified-SKG Impact)
        addEdge(edges, evidence, ctx, "polyglot.service-call.candidate",
                entityIds.get("OrderController"), entityIds.get("DynamicPluginBridge"), true);

        var graph = new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(ctx, snap, Optional.of(analysis), manEv.identity())),
                nodes, edges, evidence);
        return new Fixture(graph, ctx, entityIds);
    }

    private static void addEdge(
            List<KnowledgeGraph.Edge> edges,
            List<GraphEvidence> evidence,
            String ctx,
            String kind,
            String src,
            String dst,
            boolean speculative) {
        var ev = GraphEvidence.capture(speculative ? "advisory.speculative" : "java.reference",
                speculative
                        ? Map.of("from", src, "to", dst, "speculative", true, "trustTier", "ADVISORY_UNVERIFIED")
                        : Map.of("from", src, "to", dst));
        evidence.add(ev);
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("edge", List.of(kind, src, dst)),
                ctx, kind, src,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                List.of(ev.identity()), Optional.empty()));
    }

    private static String verboseJavaCallee(String className, String doc, String methodName, String param, String retType) {
        var sb = new StringBuilder();
        sb.append("package com.acme.orders;\n");
        sb.append("import java.util.*;\n");
        sb.append("/**\n * ").append(doc).append("\n * Detailed internal notes omitted in compressed view.\n */\n");
        sb.append("public class ").append(className).append(" {\n");
        sb.append("    public ").append(retType).append(' ').append(methodName).append('(').append(param).append(") {\n");
        sb.append("        String sentinel = \"INTERNAL_IMPLEMENTATION_BODY_SENTINEL\";\n");
        for (int i = 0; i < 24; i++) {
            sb.append("        int step").append(i).append(" = sentinel.length() + ").append(i).append(";\n");
            sb.append("        if (step").append(i).append(" < 0) throw new IllegalStateException(\"impossible\");\n");
        }
        if ("void".equals(retType)) {
            sb.append("        return;\n");
        } else if ("boolean".equals(retType)) {
            sb.append("        return sentinel.length() > 0;\n");
        } else if ("long".equals(retType)) {
            sb.append("        return sentinel.length();\n");
        } else {
            sb.append("        return sentinel;\n");
        }
        sb.append("    }\n}\n");
        return sb.toString();
    }
}
