package com.evolution.analysis.query.projection;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQueryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Moonshot3ProjectionAndDsmTest {

    // ─── (a) Iterative Tarjan SCC on 5,000-node deep chain + multi-cycle graph ───

    @Test
    void tarjanSccIsIterativeOn5000NodeDeepChainAndMultiCycleDeterministic() {
        int n = 5_000;
        var nodeIds = new ArrayList<String>(n);
        var edges = new ArrayList<TarjanSccAnalyzer.DirectedEdge>(n + 16);

        for (int i = 0; i < n; i++) {
            nodeIds.add(String.format(Locale.ROOT, "n%04d", i));
        }
        // Deep chain: n0000 -> n0001 -> ... -> n4999 (would overflow JVM stack if recursive!)
        for (int i = 0; i < n - 1; i++) {
            edges.add(new TarjanSccAnalyzer.DirectedEdge(nodeIds.get(i), nodeIds.get(i + 1), 1));
        }

        // Pure 5,000-node DAG check first
        var dagResult = TarjanSccAnalyzer.analyze(nodeIds, edges);
        assertEquals(n, dagResult.blocks().size());
        assertTrue(dagResult.cyclicNodeIds().isEmpty());
        assertTrue(dagResult.cyclicEdgeKeys().isEmpty());
        assertEquals(1.0, dagResult.levelizationScore(), 1e-9);
        // Every edge u -> v must satisfy pos(u) > pos(v) (DSM lower triangle!)
        var dagPos = new HashMap<String, Integer>(n * 2);
        for (int i = 0; i < dagResult.topologicalOrder().size(); i++) {
            dagPos.put(dagResult.topologicalOrder().get(i), i);
        }
        for (var e : edges) {
            assertTrue(dagPos.get(e.source()) > dagPos.get(e.target()),
                    "DAG edge must be lower-triangle: " + e.source() + " -> " + e.target());
        }

        // Add 2 multi-node cycles + 1 explicit self-loop
        edges.add(new TarjanSccAnalyzer.DirectedEdge("n0104", "n0100", 2)); // 5-node cycle: n0100..n0104
        edges.add(new TarjanSccAnalyzer.DirectedEdge("n2502", "n2500", 3)); // 3-node cycle: n2500..n2502
        edges.add(new TarjanSccAnalyzer.DirectedEdge("n4000", "n4000", 1)); // self-loop on n4000

        var cycResult = TarjanSccAnalyzer.analyze(nodeIds, edges);
        assertEquals(n - 4 - 2, cycResult.blocks().size());
        var cyclicBlocks = cycResult.blocks().stream().filter(TarjanSccAnalyzer.SccBlock::cyclic).toList();
        assertEquals(3, cyclicBlocks.size());
        assertEquals(9, cycResult.cyclicNodeIds().size());
        assertTrue(cycResult.cyclicNodeIds().containsAll(List.of(
                "n0100", "n0101", "n0102", "n0103", "n0104",
                "n2500", "n2501", "n2502", "n4000")));
        assertTrue(cycResult.cyclicEdgeKeys().contains(TarjanSccAnalyzer.edgeKey("n0104", "n0100")));
        assertTrue(cycResult.cyclicEdgeKeys().contains(TarjanSccAnalyzer.edgeKey("n2502", "n2500")));
        assertTrue(cycResult.cyclicEdgeKeys().contains(TarjanSccAnalyzer.edgeKey("n4000", "n4000")));
        assertTrue(cycResult.levelizationScore() < 1.0 && cycResult.levelizationScore() > 0.99);

        // Verify strict determinism across shuffled input orderings
        var shuffledNodes = new ArrayList<>(nodeIds);
        var shuffledEdges = new ArrayList<>(edges);
        var rng = new Random(20261127L);
        Collections.shuffle(shuffledNodes, rng);
        Collections.shuffle(shuffledEdges, rng);
        var replay = TarjanSccAnalyzer.analyze(shuffledNodes, shuffledEdges);
        assertEquals(cycResult.blocks(), replay.blocks());
        assertEquals(cycResult.topologicalOrder(), replay.topologicalOrder());
        assertEquals(cycResult.nodeToBlockIndex(), replay.nodeToBlockIndex());
        assertEquals(cycResult.cyclicNodeIds(), replay.cyclicNodeIds());
        assertEquals(cycResult.cyclicEdgeKeys(), replay.cyclicEdgeKeys());
        assertEquals(cycResult.levelizationScore(), replay.levelizationScore(), 1e-12);
    }

    // ─── (b), (c), (e) Multi-Scale AUTO->MACRO (<50 KiB), MESO expand, MICRO focus & 0 digest mutation ───

    @Test
    void multiScaleAutoMacroMesoMicroAreDeterministicCompactHonestAndPreserveGraphDigest() {
        var builder = new GraphBuilder();
        // 8 cohesive clusters * 40 types = 320 project entities (> 250 AUTO threshold)
        int clusterCount = 8;
        int clusterSize = 40;
        for (int c = 0; c < clusterCount; c++) {
            String mod = "mod-" + (c / 2);
            String pkg = "com.acme.domain" + c;
            for (int i = 0; i < clusterSize; i++) {
                builder.type(mod, pkg, "C" + c + "T" + i, EntityOrigin.PROJECT);
            }
            // Dense intra-cluster ring + chords so Leiden cleanly isolates each cluster
            for (int i = 0; i < clusterSize; i++) {
                String u = "C" + c + "T" + i;
                builder.edge(u, "C" + c + "T" + ((i + 1) % clusterSize));
                builder.edge(u, "C" + c + "T" + ((i + 2) % clusterSize));
                builder.edge(u, "C" + c + "T" + ((i + 3) % clusterSize));
            }
        }
        // Inter-cluster dependencies + an inter-cluster cycle between cluster 0 <-> cluster 1
        builder.edge("C0T0", "C1T0");
        builder.edge("C1T1", "C0T1");
        for (int c = 2; c < clusterCount; c++) {
            builder.edge("C" + c + "T0", "C" + (c - 1) + "T0");
        }
        // Add external dependency nodes wired from project types
        for (int e = 0; e < 12; e++) {
            builder.type("ext-lib", "org.ext.lib", "Ext" + e, EntityOrigin.DEPENDENCY);
            builder.edge("C" + (e % clusterCount) + "T5", "Ext" + e);
        }

        KnowledgeGraph graph = builder.build();
        ContentDigest digestBefore = graph.identity();
        ContentDigest semanticBefore = graph.semanticIdentity();

        var service = new GraphProjectionService(new ArchitectureQueryService(graph));
        String violatedKey = TarjanSccAnalyzer.edgeKey(builder.typeId("C2T0"), builder.typeId("C1T0"));
        var autoReq = new GraphProjectionService.MultiScaleRequest(
                builder.context,
                GraphProjectionService.ProjectionLevel.AUTO,
                List.of(),
                List.of(),
                GraphProjectionService.CycleMode.SCC,
                GraphProjectionService.DEFAULT_MAX_NODES,
                GraphProjectionService.DEFAULT_MAX_EDGES,
                Set.of(violatedKey),
                Map.of(builder.typeId("C2T0"), 2));

        VisualGraph macroRun1 = service.projectMultiScale(graph, autoReq);
        VisualGraph macroRun2 = new GraphProjectionService(new ArchitectureQueryService(graph))
                .projectMultiScale(graph, autoReq);

        // AUTO resolves to MACRO for 320 > 250 project nodes
        assertEquals("AUTO", macroRun1.metadata().get("level"));
        assertEquals("MACRO", macroRun1.metadata().get("resolvedLevel"));
        assertEquals("SCC", macroRun1.metadata().get("cycleMode"));
        assertEquals(320, macroRun1.metadata().get("totalProjectNodes"));
        assertEquals(false, macroRun1.metadata().get("truncated"));
        assertNotNull(macroRun1.metadata().get("layoutSeed"));
        assertNotNull(macroRun1.metadata().get("sccSummary"));

        // Verify super-nodes & external super-node
        assertTrue(macroRun1.nodes().size() >= clusterCount);
        assertTrue(macroRun1.nodes().stream().allMatch(n ->
                GraphProjectionService.COMMUNITY_SUPER_NODE_KIND.equals(n.kind())));
        assertTrue(macroRun1.nodes().stream().anyMatch(n ->
                GraphProjectionService.EXTERNAL_SUPER_NODE_ID.equals(n.id())));

        VisualGraph.VisualNode firstProjectSuper = macroRun1.nodes().stream()
                .filter(n -> !GraphProjectionService.EXTERNAL_SUPER_NODE_ID.equals(n.id()))
                .findFirst()
                .orElseThrow();
        assertTrue(firstProjectSuper.id().startsWith("community:"));
        assertInstanceOf(BigDecimal.class, firstProjectSuper.properties().get("x"));
        assertInstanceOf(BigDecimal.class, firstProjectSuper.properties().get("y"));
        assertInstanceOf(BigDecimal.class, firstProjectSuper.properties().get("cohesion"));
        assertEquals(Boolean.TRUE, firstProjectSuper.properties().get("expandable"));
        assertEquals(TrustTier.COMPILER.name(), firstProjectSuper.properties().get("trustTier"));
        assertNotNull(firstProjectSuper.properties().get("memberCount"));
        assertNotNull(firstProjectSuper.properties().get("internalEdges"));
        assertNotNull(firstProjectSuper.properties().get("communityId"));
        assertFalse(((List<?>) firstProjectSuper.properties().get("sampleMembers")).isEmpty());

        // Inter-community cycle (C0 <-> C1) and policy violation (C2 -> C1) propagated onto super-edges
        assertTrue(macroRun1.edges().stream().anyMatch(VisualGraph.VisualEdge::isCycle),
                "Expected inter-community cycle edge flagged via TarjanSccAnalyzer");
        assertTrue(macroRun1.edges().stream().anyMatch(VisualGraph.VisualEdge::isViolation),
                "Expected violated super-edge propagated from violatedEdgeKeys");

        // Compact < 50 KiB JSON payload & 100% deterministic across 2 runs
        String json1 = CanonicalJson.write(macroRun1.toCytoscape());
        String json2 = CanonicalJson.write(macroRun2.toCytoscape());
        assertEquals(macroRun1.id(), macroRun2.id());
        assertEquals(json1, json2);
        int byteLen = json1.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(byteLen < 50 * 1024, "MACRO Cytoscape JSON must be < 50 KiB, got " + byteLen + " bytes");
        // Record itself is also CanonicalJson-safe (zero Double/Float)
        assertDoesNotThrow(() -> CanonicalJson.write(macroRun1));

        // ─── MESO: expand one community cleanly while others stay collapsed ───
        String targetCommId = (String) firstProjectSuper.properties().get("communityId");
        int expectedMembers = ((Number) firstProjectSuper.properties().get("memberCount")).intValue();
        var mesoReq = new GraphProjectionService.MultiScaleRequest(
                builder.context,
                GraphProjectionService.ProjectionLevel.MESO,
                List.of(targetCommId),
                List.of(),
                GraphProjectionService.CycleMode.SCC,
                200,
                1000,
                Set.of(violatedKey),
                Map.of());
        VisualGraph meso = service.projectMultiScale(graph, mesoReq);
        assertEquals("MESO", meso.metadata().get("resolvedLevel"));
        long remainingSuperNodes = meso.nodes().stream()
                .filter(n -> GraphProjectionService.COMMUNITY_SUPER_NODE_KIND.equals(n.kind()))
                .count();
        long expandedConcreteNodes = meso.nodes().stream()
                .filter(n -> !"COMMUNITY_SUPER_NODE".equals(n.kind()))
                .count();
        assertEquals(macroRun1.nodes().size() - 1, remainingSuperNodes);
        assertEquals(expectedMembers, expandedConcreteNodes);
        assertEquals((int) remainingSuperNodes, meso.metadata().get("superNodeCount"));
        assertTrue(meso.nodes().stream().allMatch(n ->
                n.properties().get("x") instanceof BigDecimal
                        && n.properties().get("y") instanceof BigDecimal
                        && n.properties().get("trustTier") != null));
        assertDoesNotThrow(() -> CanonicalJson.write(meso.toCytoscape()));

        // MESO with tight cap reports honest truncation
        var mesoCapped = service.projectMultiScale(graph, new GraphProjectionService.MultiScaleRequest(
                builder.context,
                GraphProjectionService.ProjectionLevel.MESO,
                List.of("community:" + targetCommId),
                List.of(),
                GraphProjectionService.CycleMode.SCC,
                15,
                10,
                Set.of(),
                Map.of()));
        assertEquals(Boolean.TRUE, mesoCapped.metadata().get("truncated"));
        assertTrue(((Number) mesoCapped.metadata().get("cappedNodeCount")).intValue() > 0);
        assertTrue(((Number) mesoCapped.metadata().get("cappedEdgeCount")).intValue() > 0);

        // ─── MICRO: focus on seed node + bounded 1-2 hop neighborhood with honest truncation ───
        String seedId = builder.typeId("C0T0");
        var microReq = new GraphProjectionService.MultiScaleRequest(
                builder.context,
                GraphProjectionService.ProjectionLevel.MICRO,
                List.of(),
                List.of(seedId),
                GraphProjectionService.CycleMode.SCC,
                12,
                20,
                Set.of(violatedKey),
                Map.of(seedId, 1));
        VisualGraph micro = service.projectMultiScale(graph, microReq);
        assertEquals("MICRO", micro.metadata().get("resolvedLevel"));
        assertEquals(0, micro.metadata().get("superNodeCount"));
        assertTrue(micro.nodes().size() <= 12);
        assertTrue(micro.edges().size() <= 20);
        assertEquals(Boolean.TRUE, micro.metadata().get("truncated"));
        assertTrue(((Number) micro.metadata().get("cappedNodeCount")).intValue() > 0);
        var seedVisual = micro.nodes().stream().filter(n -> n.id().equals(seedId)).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, seedVisual.properties().get("focusSeed"));
        assertEquals(1, seedVisual.violationCount());
        assertEquals(TrustTier.COMPILER.name(), seedVisual.properties().get("trustTier"));
        assertTrue(micro.edges().stream().allMatch(e -> TrustTier.COMPILER.name().equals(e.properties().get("trustTier"))));
        assertDoesNotThrow(() -> CanonicalJson.write(micro.toCytoscape()));

        // Verify Cytoscape export preserves position.{x, y} on multi-scale nodes
        @SuppressWarnings("unchecked")
        var cyElements = (Map<String, Object>) micro.toCytoscape().get("elements");
        @SuppressWarnings("unchecked")
        var cyNodes = (List<Map<String, Object>>) cyElements.get("nodes");
        assertTrue(cyNodes.stream().allMatch(n -> n.containsKey("position")));

        // Verify AUTO resolves to MICRO on small graphs (<= 250 project entities)
        var smallBuilder = new GraphBuilder()
                .type("app", "com.small.api", "Ctrl", EntityOrigin.PROJECT)
                .type("app", "com.small.svc", "Svc", EntityOrigin.PROJECT)
                .edge("Ctrl", "Svc");
        KnowledgeGraph smallGraph = smallBuilder.build();
        VisualGraph smallAuto = new GraphProjectionService(new ArchitectureQueryService(smallGraph))
                .projectMultiScale(smallGraph, GraphProjectionService.MultiScaleRequest.defaults(smallBuilder.context));
        assertEquals("MICRO", smallAuto.metadata().get("resolvedLevel"));
        assertEquals(2, smallAuto.nodes().size());

        // (e) Zero canonical KnowledgeGraph digest mutation
        assertEquals(digestBefore, graph.identity());
        assertEquals(semanticBefore, graph.semanticIdentity());
    }

    // ─── (d) & (e) DsmMatrixBuilder across MODULE, PACKAGE, TYPE on DAGs and Cyclic Graphs ───

    @Test
    void dsmMatrixBuilderProducesLevelizedOneOnDagsAndContiguousUpperTriangleBackEdgesOnCycles() {
        // 1. Strictly acyclic layered DAG: mod-web -> mod-svc -> mod-core
        var dagBuilder = new GraphBuilder()
                .type("mod-web", "com.acme.web", "OrderController", EntityOrigin.PROJECT)
                .type("mod-web", "com.acme.web", "UserController", EntityOrigin.PROJECT)
                .type("mod-svc", "com.acme.svc", "OrderService", EntityOrigin.PROJECT)
                .type("mod-svc", "com.acme.svc", "UserService", EntityOrigin.PROJECT)
                .type("mod-core", "com.acme.core", "OrderRepo", EntityOrigin.PROJECT)
                .type("mod-core", "com.acme.core", "UserRepo", EntityOrigin.PROJECT)
                .edge("OrderController", "OrderService")
                .edge("UserController", "UserService")
                .edge("OrderService", "OrderRepo")
                .edge("UserService", "UserRepo")
                .edge("OrderController", "OrderRepo");
        KnowledgeGraph dagGraph = dagBuilder.build();
        ContentDigest dagDigestBefore = dagGraph.identity();

        for (var granularity : DsmMatrixBuilder.Granularity.values()) {
            var dsm = DsmMatrixBuilder.build(dagGraph, DsmMatrixBuilder.DsmRequest.defaults(dagBuilder.context, granularity));
            assertEquals(0, dsm.levelizationScore().compareTo(BigDecimal.ONE),
                    "DAG levelizationScore must equal 1.0 for " + granularity + ", got " + dsm.levelizationScore());
            assertFalse(dsm.truncated());
            assertFalse(dsm.cells().isEmpty());
            for (var cell : dsm.cells()) {
                assertTrue(cell.r() > cell.c(),
                        "DAG cell must be in lower triangle (r > c) for " + granularity + ": " + cell);
                assertFalse(cell.isCycle());
                assertEquals(TrustTier.COMPILER, cell.trustTier());
            }
            assertTrue(dsm.sccBlocks().stream().noneMatch(DsmMatrixBuilder.DsmSccBlock::cyclic));
            // CanonicalJson-safe view map
            var view = dsm.toView();
            assertEquals(dagGraph.identity().value(), view.get("graphDigest"));
            assertEquals(granularity.name(), view.get("granularity"));
            assertDoesNotThrow(() -> CanonicalJson.write(view));
        }
        assertEquals(dagDigestBefore, dagGraph.identity());

        // 2. Cyclic graph across MODULE, PACKAGE, and TYPE levels
        var cycBuilder = new GraphBuilder()
                .type("mod-a", "com.acme.alpha", "AlphaA", EntityOrigin.PROJECT)
                .type("mod-a", "com.acme.alpha", "AlphaB", EntityOrigin.PROJECT)
                .type("mod-b", "com.acme.beta", "BetaA", EntityOrigin.PROJECT)
                .type("mod-c", "com.acme.gamma", "GammaA", EntityOrigin.PROJECT)
                // Cross-module / cross-package / cross-type cycle: AlphaA -> BetaA -> AlphaA
                .edge("AlphaA", "BetaA")
                .edge("BetaA", "AlphaA")
                // Acyclic caller depending on the cycle: GammaA -> AlphaA, AlphaB -> AlphaA
                .edge("GammaA", "AlphaA")
                .edge("AlphaB", "AlphaA");
        KnowledgeGraph cycGraph = cycBuilder.build();
        ContentDigest cycDigestBefore = cycGraph.identity();
        String violated = TarjanSccAnalyzer.edgeKey(cycBuilder.typeId("BetaA"), cycBuilder.typeId("AlphaA"));

        for (var granularity : DsmMatrixBuilder.Granularity.values()) {
            var req = new DsmMatrixBuilder.DsmRequest(
                    cycBuilder.context, granularity, Optional.empty(), 80, Set.of(violated));
            var dsm = DsmMatrixBuilder.build(cycGraph, req);

            assertTrue(dsm.levelizationScore().doubleValue() < 1.0 && dsm.levelizationScore().doubleValue() >= 0.0,
                    "Cyclic graph must have levelizationScore < 1.0 for " + granularity + ", got " + dsm.levelizationScore());

            // Cyclic SCC block must be contiguous on the diagonal
            var cyclicBlocks = dsm.sccBlocks().stream().filter(DsmMatrixBuilder.DsmSccBlock::cyclic).toList();
            assertEquals(1, cyclicBlocks.size(), "Expected 1 cyclic SCC block for " + granularity);
            var block = cyclicBlocks.getFirst();
            assertEquals(2, block.size());
            assertEquals(block.size(), block.end() - block.start() + 1);
            for (int idx = block.start(); idx <= block.end(); idx++) {
                assertEquals(block.index(), dsm.order().get(idx).sccIndex());
                assertTrue(block.members().contains(dsm.order().get(idx).id()));
            }

            // Must emit at least one upper-triangle back-edge (r < c) flagged isCycle=true
            var upperBackEdges = dsm.cells().stream()
                    .filter(cell -> cell.r() < cell.c())
                    .toList();
            assertFalse(upperBackEdges.isEmpty(), "Expected upper-triangle back-edge for " + granularity);
            assertTrue(upperBackEdges.stream().allMatch(DsmMatrixBuilder.DsmCell::isCycle));

            // Violation flag must propagate cleanly at every granularity
            assertTrue(dsm.cells().stream().anyMatch(DsmMatrixBuilder.DsmCell::isViolation),
                    "Expected violated cell for " + granularity);

            assertDoesNotThrow(() -> CanonicalJson.write(dsm.toView()));
        }

        // Test maxItems truncation honesty
        var cappedDsm = DsmMatrixBuilder.build(cycGraph, new DsmMatrixBuilder.DsmRequest(
                cycBuilder.context, DsmMatrixBuilder.Granularity.TYPE, Optional.empty(), 2, Set.of()));
        assertTrue(cappedDsm.truncated());
        assertEquals(4, cappedDsm.totalItems());
        assertEquals(2, cappedDsm.order().size());

        // P1-2 regression: MICRO focusNodes resolves DSM PACKAGE & MODULE IDs (canonical + synthetic + raw names)
        var cycService = new GraphProjectionService(new ArchitectureQueryService(cycGraph));
        var pkgDsm = DsmMatrixBuilder.build(cycGraph, DsmMatrixBuilder.DsmRequest.defaults(
                cycBuilder.context, DsmMatrixBuilder.Granularity.PACKAGE));
        String alphaPkgDsmId = pkgDsm.order().stream()
                .filter(it -> "com.acme.alpha".equals(it.label()))
                .map(DsmMatrixBuilder.DsmItem::id)
                .findFirst()
                .orElseThrow();
        var microFromPkgDsm = cycService.projectMultiScale(cycGraph, new GraphProjectionService.MultiScaleRequest(
                cycBuilder.context,
                GraphProjectionService.ProjectionLevel.MICRO,
                List.of(),
                List.of(alphaPkgDsmId),
                GraphProjectionService.CycleMode.SCC,
                50, 200, Set.of(), Map.of()));
        assertEquals("MICRO", microFromPkgDsm.metadata().get("resolvedLevel"));
        assertFalse(microFromPkgDsm.nodes().isEmpty(), "DSM PACKAGE focus must produce a populated MICRO subgraph");
        assertTrue(microFromPkgDsm.nodes().stream().anyMatch(n -> n.id().equals(cycBuilder.typeId("AlphaA"))
                && Boolean.TRUE.equals(n.properties().get("focusSeed"))));
        assertTrue(microFromPkgDsm.nodes().stream().anyMatch(n -> n.id().equals(cycBuilder.typeId("AlphaB"))
                && Boolean.TRUE.equals(n.properties().get("focusSeed"))));
        assertTrue(microFromPkgDsm.nodes().stream().anyMatch(n -> n.id().equals(cycBuilder.typeId("BetaA"))));

        // Also verify synthetic "package:<name>" and raw "<pkgName>" focus resolution
        var microFromSyntheticPkg = cycService.projectMultiScale(cycGraph, new GraphProjectionService.MultiScaleRequest(
                cycBuilder.context,
                GraphProjectionService.ProjectionLevel.MICRO,
                List.of(),
                List.of("package:com.acme.beta"),
                GraphProjectionService.CycleMode.SCC,
                50, 200, Set.of(), Map.of()));
        assertTrue(microFromSyntheticPkg.nodes().stream().anyMatch(n -> n.id().equals(cycBuilder.typeId("BetaA"))
                && Boolean.TRUE.equals(n.properties().get("focusSeed"))));

        var modDsm = DsmMatrixBuilder.build(cycGraph, DsmMatrixBuilder.DsmRequest.defaults(
                cycBuilder.context, DsmMatrixBuilder.Granularity.MODULE));
        String modADsmId = modDsm.order().stream()
                .filter(it -> "mod-a".equals(it.label()))
                .map(DsmMatrixBuilder.DsmItem::id)
                .findFirst()
                .orElseThrow();
        var microFromModDsm = cycService.projectMultiScale(cycGraph, new GraphProjectionService.MultiScaleRequest(
                cycBuilder.context,
                GraphProjectionService.ProjectionLevel.MICRO,
                List.of(),
                List.of(modADsmId),
                GraphProjectionService.CycleMode.SCC,
                2, 50, Set.of(), Map.of()));
        assertEquals(2, microFromModDsm.nodes().size(), "Container focus must still respect maxNodes bound");
        assertEquals(Boolean.TRUE, microFromModDsm.metadata().get("truncated"));
        assertTrue(microFromModDsm.nodes().stream().anyMatch(n -> n.id().equals(cycBuilder.typeId("AlphaA"))));

        var microFromSyntheticMod = cycService.projectMultiScale(cycGraph, new GraphProjectionService.MultiScaleRequest(
                cycBuilder.context,
                GraphProjectionService.ProjectionLevel.MICRO,
                List.of(),
                List.of("module:mod-c"),
                GraphProjectionService.CycleMode.SCC,
                50, 200, Set.of(), Map.of()));
        assertTrue(microFromSyntheticMod.nodes().stream().anyMatch(n -> n.id().equals(cycBuilder.typeId("GammaA"))
                && Boolean.TRUE.equals(n.properties().get("focusSeed"))));

        // (e) Zero canonical KnowledgeGraph digest mutation
        assertEquals(cycDigestBefore, cycGraph.identity());
    }

    @Test
    void trustTierReflectsSpringSatAndPolyglotSourceSyntaxTruthfully() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/poly");
        var snapshot = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("snap-poly"));
        String ctxId = KnowledgeGraph.derivedIdentity("context", "poly-test");
        var ctxEv = GraphEvidence.capture("polyglot.context", Map.of("identity", ctxId));

        var n1Ev = GraphEvidence.capture("polyglot.source-syntax", Map.of(
                "path", "web/src/App.tsx",
                "language", "typescript",
                "rowType", "SYMBOL",
                "fact", Map.of("name", "App")));
        var n2Ev = GraphEvidence.capture("polyglot.source-syntax", Map.of(
                "path", "web/src/Api.ts",
                "language", "typescript",
                "rowType", "SYMBOL",
                "fact", Map.of("name", "ApiClient")));
        String id1 = KnowledgeGraph.derivedIdentity("poly-sym", "App");
        String id2 = KnowledgeGraph.derivedIdentity("poly-sym", "ApiClient");
        String eId = KnowledgeGraph.derivedIdentity("poly-edge", "App->Api");
        var edgeEv = GraphEvidence.capture("polyglot.source-syntax", Map.of("kind", "import"));

        var graph = new KnowledgeGraph(
                snapshot,
                List.of(new KnowledgeGraph.Context(ctxId, snapshot, Optional.empty(), ctxEv.identity())),
                List.of(
                        new KnowledgeGraph.Node(id1, ctxId, "POLYGLOT_SYMBOL", List.of(n1Ev.identity())),
                        new KnowledgeGraph.Node(id2, ctxId, "POLYGLOT_SYMBOL", List.of(n2Ev.identity()))),
                List.of(new KnowledgeGraph.Edge(
                        eId, ctxId, "polyglot.imports", id1,
                        new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(id2), Optional.empty()),
                        List.of(edgeEv.identity()), Optional.empty())),
                List.of(ctxEv, n1Ev, n2Ev, edgeEv));

        var projection = new GraphProjectionService(new ArchitectureQueryService(graph))
                .projectMultiScale(graph, GraphProjectionService.MultiScaleRequest.defaults(ctxId));
        assertEquals(2, projection.nodes().size());
        assertTrue(projection.nodes().stream().allMatch(n ->
                TrustTier.SOURCE_SYNTAX.name().equals(n.properties().get("trustTier"))));
        assertEquals(1, projection.edges().size());
        assertEquals(TrustTier.SOURCE_SYNTAX.name(), projection.edges().getFirst().properties().get("trustTier"));

        var dsm = DsmMatrixBuilder.build(graph, DsmMatrixBuilder.DsmRequest.defaults(ctxId, DsmMatrixBuilder.Granularity.TYPE));
        assertEquals(2, dsm.order().size());
        assertTrue(dsm.order().stream().allMatch(it -> it.trustTier() == TrustTier.SOURCE_SYNTAX));
        assertEquals(TrustTier.SOURCE_SYNTAX, dsm.cells().getFirst().trustTier());
    }

    // ─── Compact Deterministic Synthetic KnowledgeGraph Builder ───

    private static final class GraphBuilder {
        final RepositoryIdentity repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/m3-repo");
        final SnapshotIdentity snapshot = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("m3-snapshot"));
        final String context = KnowledgeGraph.derivedIdentity("context", "m3-test");
        final GraphEvidence base = GraphEvidence.capture("fixture.context", Map.of("identity", context));
        final SourceDocumentIdentity doc = SourceDocumentIdentity.from(repo, "src/Main.java");
        final GraphEvidence occEv;
        final List<KnowledgeGraph.Node> nodes = new ArrayList<>();
        final List<KnowledgeGraph.Edge> edges = new ArrayList<>();
        final List<GraphEvidence> evidence = new ArrayList<>();
        final Set<String> addedNodeIds = new HashSet<>();
        final Map<String, String> typesByShortName = new HashMap<>();

        GraphBuilder() {
            evidence.add(base);
            var docEv = GraphEvidence.capture("source.document", Map.of(
                    "identity", doc,
                    "path", "src/Main.java",
                    "contentDigest", ContentDigest.sha256Utf8("class Main {}")));
            evidence.add(docEv);
            nodes.add(new KnowledgeGraph.Node(doc.value(), context, "SOURCE_DOCUMENT", List.of(docEv.identity())));
            addedNodeIds.add(doc.value());

            occEv = GraphEvidence.capture("java.occurrence", Map.of(
                    "status", "RESOLVED",
                    "derivation", "DIRECT",
                    "span", new SourceSpan(doc, 1, 1, 1, 10)));
            evidence.add(occEv);
        }

        GraphBuilder type(String modulePath, String pkg, String name, EntityOrigin origin) {
            var owner = ModuleIdentity.from(repo, modulePath);
            var scope = origin == EntityOrigin.PROJECT
                    ? EntityScope.project(owner)
                    : EntityScope.external(origin, modulePath, ContentDigest.sha256Utf8(modulePath));
            var entity = Entity.create(origin, scope, EntityKind.TYPE,
                    JavaSymbolName.topLevelType(pkg, name).canonicalName(), Optional.empty());
            var proof = GraphEvidence.capture("java.declaration", Map.of("entity", entity, "spelling", name));
            evidence.add(proof);

            if (origin == EntityOrigin.PROJECT) {
                if (addedNodeIds.add(owner.value())) {
                    var modEv = GraphEvidence.capture("module.descriptor", Map.of(
                            "identity", owner,
                            "path", modulePath));
                    evidence.add(modEv);
                    nodes.add(new KnowledgeGraph.Node(owner.value(), context, "MODULE", List.of(modEv.identity())));
                }
                String pkgId = EntityIdentity.from(EntityOrigin.PROJECT, EntityScope.project(owner),
                        EntityKind.PACKAGE, JavaSymbolName.packageName(pkg).canonicalName()).value();
                if (addedNodeIds.add(pkgId)) {
                    nodes.add(new KnowledgeGraph.Node(pkgId, context, "PACKAGE", List.of(proof.identity())));
                    link(owner.value(), pkgId);
                }
                link(pkgId, entity.identity().value());
            }
            if (addedNodeIds.add(entity.identity().value())) {
                nodes.add(new KnowledgeGraph.Node(entity.identity().value(), context, "TYPE", List.of(proof.identity())));
            }
            typesByShortName.put(name, entity.identity().value());
            return this;
        }

        String typeId(String shortName) {
            return Objects.requireNonNull(typesByShortName.get(shortName), "Unknown type: " + shortName);
        }

        private void link(String source, String target) {
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("contains", source + "->" + target + ":" + edges.size()),
                    context,
                    "graph.contains",
                    source,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(target), Optional.empty()),
                    List.of(base.identity()),
                    Optional.empty()));
        }

        GraphBuilder edge(String fromName, String toName) {
            String from = typeId(fromName);
            String to = typeId(toName);
            String edgeId = KnowledgeGraph.derivedIdentity("edge", from + "->" + to + ":" + edges.size());
            edges.add(new KnowledgeGraph.Edge(
                    edgeId,
                    context,
                    "graph.depends-on",
                    from,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(to), Optional.empty()),
                    List.of(occEv.identity()),
                    Optional.empty()));
            return this;
        }

        KnowledgeGraph build() {
            var ctx = new KnowledgeGraph.Context(context, snapshot, Optional.empty(), base.identity());
            return new KnowledgeGraph(snapshot, List.of(ctx), nodes, edges, evidence);
        }
    }
}
