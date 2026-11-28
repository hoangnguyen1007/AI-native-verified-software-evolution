package com.evolution.analysis.query;

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
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.SymbolEgoGraphExtractor.ExtractionMode;
import com.evolution.analysis.query.SymbolEgoGraphExtractor.Request;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SymbolEgoGraphExtractorTest {

    @Test
    void extractsExact1HopEgoGraphFiltersStdlibNoiseAndPreservesSpringFrameworkWiring() {
        var fixture = buildRichEgoGraphFixture();
        var extractor = new SymbolEgoGraphExtractor();

        // Lookup by searchTerm (simple name)
        var byTerm = extractor.extract(fixture.graph, Request.bySearchTerm("OrderProcessorImpl"));
        assertTrue(byTerm.found());
        assertEquals(ExtractionMode.FLATTENED_1HOP, byTerm.mode());
        assertEquals(1, byTerm.effectiveHops());
        assertFalse(byTerm.guardrailApplied());
        assertTrue(byTerm.estimatedTokens() <= SymbolEgoGraphExtractor.HARD_MAX_TOKENS,
                "k=1 output must stay <= 2,500 tokens, got " + byTerm.estimatedTokens());

        // Lookup by exact symbolId yields identical canonical digest (I2)
        var byId = extractor.extract(fixture.graph, Request.bySymbolId(fixture.ids.get("OrderProcessorImpl")));
        assertTrue(byId.found());
        assertEquals(byTerm.canonicalDigest(), byId.canonicalDigest(), "symbolId and searchTerm lookups must match deterministically");

        var nodeFqns = byTerm.nodes().stream().map(SymbolEgoGraphExtractor.EgoNode::fqn).collect(java.util.stream.Collectors.toSet());
        // Center + exact 1-hop neighbors across IMPLEMENTS, INJECTS, CALLS, CONTAINS, ADVISES_METHOD
        assertTrue(nodeFqns.contains("com.acme.orders.OrderProcessorImpl"));
        assertTrue(nodeFqns.contains("com.acme.orders.OrderProcessor"));
        assertTrue(nodeFqns.contains("com.acme.orders.OrderController"));
        assertTrue(nodeFqns.contains("com.acme.orders.InventoryClient"));
        assertTrue(nodeFqns.contains("com.acme.orders.OrderAuditAspect"));
        assertTrue(nodeFqns.contains("com.acme.orders.OrderProcessorImpl.process"));
        // Spring framework wiring external node MUST be preserved through Tier-2 filter!
        assertTrue(nodeFqns.contains("org.springframework.context.ApplicationEventPublisher"),
                "Spring/framework wiring edge must be preserved: " + nodeFqns);

        // 2-hop distant node must NOT leak into k=1 ego-graph!
        assertFalse(nodeFqns.contains("com.acme.orders.DistantTwoHopLedger"),
                "k=1 must exclude 2-hop nodes");

        // Tier-1 stdlib noise & Tier-2 unrelated external library MUST be filtered!
        assertFalse(nodeFqns.contains("java.lang.String"));
        assertFalse(nodeFqns.contains("java.util.List"));
        assertFalse(nodeFqns.contains("builtins.len"));
        assertFalse(nodeFqns.contains("com.thirdparty.util.NoiseHelper"));
        assertTrue(byTerm.filteredNoiseNodes() >= 4,
                "Expected >=4 filtered noise nodes (String, List, builtins.len, NoiseHelper), got " + byTerm.filteredNoiseNodes());

        // Verify normalized edge kinds preserved
        var edgeKinds = byTerm.edges().stream().map(SymbolEgoGraphExtractor.EgoEdge::normalizedKind)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(edgeKinds.contains("IMPLEMENTS"));
        assertTrue(edgeKinds.contains("INJECTS"));
        assertTrue(edgeKinds.contains("CALLS"));
        assertTrue(edgeKinds.contains("CONTAINS"));
        assertTrue(edgeKinds.contains("ADVISES_METHOD"));

        // Verify flattened 1-hop mode includes code snippet lines for center
        assertTrue(byTerm.center().isPresent());
        assertFalse(byTerm.center().orElseThrow().compressedSignatureOnly());
        assertTrue(byTerm.center().orElseThrow().snippet().contains("class OrderProcessorImpl"),
                "k=1 flattened mode should include bounded code snippet");
    }

    @Test
    void enforcesRepoGraphTable4GuardrailOnK2BySwitchingToSignatureCompressedModeWithin2500Tokens() {
        var fixture = buildRichEgoGraphFixture();
        var extractor = new SymbolEgoGraphExtractor();

        var k2Req = Request.of(Optional.of(fixture.context), Optional.of(fixture.ids.get("OrderProcessorImpl")),
                Optional.empty(), 2, 2_500);
        var k2Res = extractor.extract(fixture.graph, k2Req);

        assertTrue(k2Res.found());
        assertEquals(ExtractionMode.SIGNATURE_COMPRESSED_2HOP, k2Res.mode(),
                "k>=2 MUST automatically switch to SIGNATURE_COMPRESSED_2HOP");
        assertTrue(k2Res.guardrailApplied());
        assertFalse(k2Res.guardrailNote().isBlank());
        assertEquals(2, k2Res.effectiveHops());
        assertTrue(k2Res.estimatedTokens() <= SymbolEgoGraphExtractor.HARD_MAX_TOKENS,
                "k=2 compressed output must stay <= 2,500 tokens, got " + k2Res.estimatedTokens());

        // 2-hop neighbor IS reachable in compressed 2-hop mode
        assertTrue(k2Res.nodes().stream().anyMatch(n -> n.fqn().equals("com.acme.orders.DistantTwoHopLedger") && n.hopDistance() == 2),
                "2-hop node should appear in SIGNATURE_COMPRESSED_2HOP mode");

        // MANDATORY: ZERO raw flattened bodies emitted in k>=2 mode!
        for (var node : k2Res.nodes()) {
            assertTrue(node.compressedSignatureOnly(), "Every node in k>=2 mode must be signature-only: " + node.fqn());
            assertTrue(node.snippet().isEmpty(), "Raw body snippet MUST be empty in k>=2 mode: " + node.fqn());
            assertFalse(node.signature().isBlank(), "Compact Signature(n) must be preserved: " + node.fqn());
        }
        assertFalse(k2Res.renderedText().contains("RAW_BODY_SENTINEL_MUST_NOT_LEAK_IN_K2"),
                "Raw body text must NEVER leak into k>=2 renderedText");
        assertTrue(k2Res.renderedText().contains("[GUARDRAIL]"));

        // Requesting k=4 with oversized maxTokens=99999 still clamps hard to <= 2,500 tokens and SIGNATURE_COMPRESSED_2HOP
        var k4Res = extractor.extract(fixture.graph,
                Request.of(Optional.of(fixture.context), Optional.empty(), Optional.of("OrderProcessorImpl"), 4, 99_999));
        assertEquals(ExtractionMode.SIGNATURE_COMPRESSED_2HOP, k4Res.mode());
        assertEquals(SymbolEgoGraphExtractor.HARD_MAX_TOKENS, k4Res.tokenBudget());
        assertTrue(k4Res.estimatedTokens() <= SymbolEgoGraphExtractor.HARD_MAX_TOKENS);
    }

    @Test
    void enforcesHard2500TokenCapEvenOnDenseHighFanout1HopGraph() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/dense-ego.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("dense-ego-snap"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("dense-ego-mod");
        String ctx = analysis.value();
        var mod = ModuleIdentity.from(repo, ".");

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of()));
        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();

        StringBuilder bigBody = new StringBuilder("package com.acme.dense;\npublic class HubService {\n");
        for (int i = 0; i < 40; i++) {
            bigBody.append("    public void step").append(i).append("() { int x = ").append(i).append("; }\n");
        }
        bigBody.append("}\n");
        String hubId = addTypeWithSource(snap.value(), ctx, mod, "com.acme.dense", "HubService",
                "src/main/java/com/acme/dense/HubService.java", bigBody.toString(), 2, 42, "PROJECT", evidence, nodes);

        for (int i = 0; i < 60; i++) {
            String name = String.format("WorkerNode%02d", i);
            StringBuilder wb = new StringBuilder("package com.acme.dense;\npublic class " + name + " {\n");
            for (int l = 0; l < 15; l++) {
                wb.append("    String line").append(l).append(" = \"detailed payload content value ").append(i).append("_").append(l).append("\";\n");
            }
            wb.append("}\n");
            String wid = addTypeWithSource(snap.value(), ctx, mod, "com.acme.dense", name,
                    "src/main/java/com/acme/dense/" + name + ".java", wb.toString(), 2, 18, "PROJECT", evidence, nodes);
            var refEv = GraphEvidence.capture("java.reference", Map.of("from", hubId, "to", wid));
            evidence.add(refEv);
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("call", List.of(hubId, wid)), ctx, "java.calls", hubId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(wid), Optional.empty()),
                    List.of(refEv.identity()), Optional.empty()));
        }

        var graph = new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(ctx, snap, Optional.of(analysis), manEv.identity())),
                nodes, edges, evidence);

        var res = new SymbolEgoGraphExtractor().extract(graph, Request.bySearchTerm("HubService"));
        assertTrue(res.found());
        assertEquals(ExtractionMode.FLATTENED_1HOP, res.mode());
        assertTrue(res.truncated(), "Dense 60-neighbor graph should truncate cleanly within budget");
        assertTrue(res.estimatedTokens() <= SymbolEgoGraphExtractor.HARD_MAX_TOKENS,
                "Hard 2,500 token budget violated: " + res.estimatedTokens());
        assertTrue(SymbolEgoGraphExtractor.estimateTokens(res.renderedText()) <= SymbolEgoGraphExtractor.HARD_MAX_TOKENS);
    }

    private record Fixture(KnowledgeGraph graph, String context, Map<String, String> ids) {}

    private static Fixture buildRichEgoGraphFixture() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/ego-fixture.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("ego-fixture-snap"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("ego-fixture-mod");
        String ctx = analysis.value();
        var mod = ModuleIdentity.from(repo, ".");

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of()));
        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        var ids = new LinkedHashMap<String, String>();

        ids.put("OrderProcessor", addTypeWithSource(snap.value(), ctx, mod, "com.acme.orders", "OrderProcessor",
                "src/main/java/com/acme/orders/OrderProcessor.java",
                "package com.acme.orders;\n/** Processes orders. */\npublic interface OrderProcessor {\n    void process(String id);\n}\n",
                3, 5, "PROJECT", evidence, nodes));

        ids.put("OrderProcessorImpl", addTypeWithSource(snap.value(), ctx, mod, "com.acme.orders", "OrderProcessorImpl",
                "src/main/java/com/acme/orders/OrderProcessorImpl.java",
                "package com.acme.orders;\n/** Default order processor implementation. */\npublic class OrderProcessorImpl implements OrderProcessor {\n    private final InventoryClient inventory;\n    public void process(String id) {\n        String marker = \"RAW_BODY_SENTINEL_MUST_NOT_LEAK_IN_K2\";\n        inventory.reserve(id);\n    }\n}\n",
                3, 9, "PROJECT", evidence, nodes));

        ids.put("OrderController", addTypeWithSource(snap.value(), ctx, mod, "com.acme.orders", "OrderController",
                "src/main/java/com/acme/orders/OrderController.java",
                "package com.acme.orders;\npublic class OrderController {\n    private final OrderProcessor processor;\n    public void submit(String id) { processor.process(id); }\n}\n",
                2, 5, "PROJECT", evidence, nodes));

        ids.put("InventoryClient", addTypeWithSource(snap.value(), ctx, mod, "com.acme.orders", "InventoryClient",
                "src/main/java/com/acme/orders/InventoryClient.java",
                "package com.acme.orders;\npublic class InventoryClient {\n    private final DistantTwoHopLedger ledger;\n    public void reserve(String id) { ledger.record(id); }\n}\n",
                2, 5, "PROJECT", evidence, nodes));

        ids.put("DistantTwoHopLedger", addTypeWithSource(snap.value(), ctx, mod, "com.acme.orders", "DistantTwoHopLedger",
                "src/main/java/com/acme/orders/DistantTwoHopLedger.java",
                "package com.acme.orders;\npublic class DistantTwoHopLedger {\n    public void record(String id) { String s = \"RAW_BODY_SENTINEL_MUST_NOT_LEAK_IN_K2\"; }\n}\n",
                2, 4, "PROJECT", evidence, nodes));

        ids.put("OrderAuditAspect", addTypeWithSource(snap.value(), ctx, mod, "com.acme.orders", "OrderAuditAspect",
                "src/main/java/com/acme/orders/OrderAuditAspect.java",
                "package com.acme.orders;\npublic class OrderAuditAspect {\n    public void audit() {}\n}\n",
                2, 4, "PROJECT", evidence, nodes));

        // Contained method node inside OrderProcessorImpl
        String methodId = KnowledgeGraph.derivedIdentity("entity", List.of("com.acme.orders.OrderProcessorImpl.process"));
        var methodEv = GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "fqn", "com.acme.orders.OrderProcessorImpl.process",
                "name", "com.acme.orders.OrderProcessorImpl.process",
                "path", "src/main/java/com/acme/orders/OrderProcessorImpl.java",
                "startLine", 5, "endLine", 8, "trustTier", "COMPILER")));
        evidence.add(methodEv);
        nodes.add(new KnowledgeGraph.Node(methodId, ctx, "METHOD", List.of(methodEv.identity())));
        ids.put("OrderProcessorImpl.process", methodId);

        // External framework wiring node (MUST be preserved!)
        String springPubId = addExternalNode(ctx, "org.springframework.context.ApplicationEventPublisher", "EXTERNAL", evidence, nodes);
        // Stdlib & unrelated external noise (MUST be filtered!)
        String strId = addExternalNode(ctx, "java.lang.String", "JDK", evidence, nodes);
        String listId = addExternalNode(ctx, "java.util.List", "JDK", evidence, nodes);
        String pyLenId = addExternalNode(ctx, "builtins.len", "EXTERNAL", evidence, nodes);
        String thirdPartyId = addExternalNode(ctx, "com.thirdparty.util.NoiseHelper", "EXTERNAL", evidence, nodes);

        // Wire edges
        addEdge(ctx, "java.implements", ids.get("OrderProcessorImpl"), ids.get("OrderProcessor"), "COMPILER", evidence, edges);
        addEdge(ctx, "spring.selected-binding", ids.get("OrderController"), ids.get("OrderProcessorImpl"), "SPRING_SAT", evidence, edges);
        addEdge(ctx, "java.calls", ids.get("OrderProcessorImpl"), ids.get("InventoryClient"), "COMPILER", evidence, edges);
        addEdge(ctx, "graph.contains", ids.get("OrderProcessorImpl"), methodId, "COMPILER", evidence, edges);
        addEdge(ctx, "ADVISES_METHOD", ids.get("OrderAuditAspect"), ids.get("OrderProcessorImpl"), "SPRING_SAT", evidence, edges);
        addEdge(ctx, "spring.selected-binding", ids.get("OrderProcessorImpl"), springPubId, "SPRING_SAT", evidence, edges);
        // 2-hop edge from InventoryClient -> DistantTwoHopLedger
        addEdge(ctx, "java.calls", ids.get("InventoryClient"), ids.get("DistantTwoHopLedger"), "COMPILER", evidence, edges);
        // Noise edges from center -> stdlib / external
        addEdge(ctx, "java.references", ids.get("OrderProcessorImpl"), strId, "COMPILER", evidence, edges);
        addEdge(ctx, "java.references", ids.get("OrderProcessorImpl"), listId, "COMPILER", evidence, edges);
        addEdge(ctx, "java.calls", ids.get("OrderProcessorImpl"), pyLenId, "SOURCE_SYNTAX", evidence, edges);
        addEdge(ctx, "java.calls", ids.get("OrderProcessorImpl"), thirdPartyId, "COMPILER", evidence, edges);

        var graph = new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(ctx, snap, Optional.of(analysis), manEv.identity())),
                nodes, edges, evidence);
        return new Fixture(graph, ctx, ids);
    }

    private static String addTypeWithSource(
            String snapCtx, String ctx, ModuleIdentity mod, String pkg, String simple, String path,
            String source, int startLine, int endLine, String origin,
            List<GraphEvidence> evidence, List<KnowledgeGraph.Node> nodes) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        var digest = ContentDigest.sha256(bytes);
        String docId = KnowledgeGraph.derivedIdentity("source-doc", List.of(path));
        var bytesEv = GraphEvidence.capture("source.bytes", Map.of(
                "document", Map.of("path", path, "contentDigest", digest.value()),
                "size", bytes.length,
                "bytesBase64", Base64.getEncoder().encodeToString(bytes)));
        evidence.add(bytesEv);
        nodes.add(new KnowledgeGraph.Node(docId, snapCtx, "SOURCE_DOCUMENT", List.of(bytesEv.identity())));

        var ent = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                JavaSymbolName.topLevelType(pkg, simple).canonicalName(), Optional.empty());
        var declEv = GraphEvidence.capture("java.declaration", Map.of(
                "entity", Map.of(
                        "identity", ent.identity().value(),
                        "origin", origin,
                        "kind", "TYPE",
                        "canonicalName", ent.canonicalName(),
                        "qualifiedName", pkg + "." + simple,
                        "declaration", Map.of("document", docId, "startLine", startLine, "endLine", endLine)),
                "spelling", simple,
                "status", "RESOLVED"));
        evidence.add(declEv);
        nodes.add(new KnowledgeGraph.Node(ent.identity().value(), ctx, "TYPE", List.of(declEv.identity())));
        return ent.identity().value();
    }

    private static String addExternalNode(
            String ctx, String fqn, String origin,
            List<GraphEvidence> evidence, List<KnowledgeGraph.Node> nodes) {
        String id = KnowledgeGraph.derivedIdentity("ext-entity", List.of(fqn));
        var declEv = GraphEvidence.capture("java.declaration", Map.of(
                "entity", Map.of(
                        "identity", id,
                        "origin", origin,
                        "kind", "TYPE",
                        "qualifiedName", fqn)));
        evidence.add(declEv);
        nodes.add(new KnowledgeGraph.Node(id, ctx, "TYPE", List.of(declEv.identity())));
        return id;
    }

    private static void addEdge(
            String ctx, String kind, String src, String dst, String trustTier,
            List<GraphEvidence> evidence, List<KnowledgeGraph.Edge> edges) {
        var ev = GraphEvidence.capture("java.reference", Map.of(
                "from", src, "to", dst, "kind", kind, "trustTier", trustTier));
        evidence.add(ev);
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("edge", List.of(kind, src, dst)), ctx, kind, src,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                List.of(ev.identity()), Optional.empty()));
    }
}
