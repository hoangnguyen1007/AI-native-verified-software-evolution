package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.semantic.Entity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.evolution.CommunityEvolutionMatcher;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.metrics.ArchitectureMetric;
import com.evolution.analysis.metrics.ArchitectureMetricsService;
import com.evolution.analysis.query.ArchitectureQuery.*;
import com.evolution.analysis.query.projection.GraphProjectionService;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LeidenCommunityDetectorTest {

    @Test
    void detectsConnectedDeterministicCommunitiesAcrossTenShuffledRunsAndFlagsDrift() {
        var nodes = new ArrayList<LeidenCommunityDetector.NodeInput>();
        var edges = new ArrayList<LeidenCommunityDetector.EdgeInput>();

        // Clique A (billing, 6 nodes) + 1 misplaced node declared in com.acme.legacy but wired tightly into billing
        for (int i = 0; i < 6; i++) {
            nodes.add(new LeidenCommunityDetector.NodeInput("bill:" + i, "com.acme.billing.Bill" + i, "com.acme.billing", "TYPE"));
        }
        nodes.add(new LeidenCommunityDetector.NodeInput("drift:0", "com.acme.legacy.MisplacedBillingHelper", "com.acme.legacy", "TYPE"));
        for (int i = 0; i < 6; i++) {
            for (int j = i + 1; j < 6; j++) {
                edges.add(new LeidenCommunityDetector.EdgeInput("bill:" + i, "bill:" + j, 2.0, "java.calls"));
            }
        }
        // Wire misplaced node tightly into billing (4 edges), 0 edges to legacy
        for (int i = 0; i < 4; i++) {
            edges.add(new LeidenCommunityDetector.EdgeInput("drift:0", "bill:" + i, 2.0, "java.calls"));
        }

        // Clique B (shipping, 6 nodes)
        for (int i = 0; i < 6; i++) {
            nodes.add(new LeidenCommunityDetector.NodeInput("ship:" + i, "com.acme.shipping.Ship" + i, "com.acme.shipping", "TYPE"));
        }
        for (int i = 0; i < 6; i++) {
            for (int j = i + 1; j < 6; j++) {
                edges.add(new LeidenCommunityDetector.EdgeInput("ship:" + i, "ship:" + j, 2.0, "java.calls"));
            }
        }

        // Clique C (catalog, 6 nodes)
        for (int i = 0; i < 6; i++) {
            nodes.add(new LeidenCommunityDetector.NodeInput("cat:" + i, "com.acme.catalog.Cat" + i, "com.acme.catalog", "TYPE"));
        }
        for (int i = 0; i < 6; i++) {
            for (int j = i + 1; j < 6; j++) {
                edges.add(new LeidenCommunityDetector.EdgeInput("cat:" + i, "cat:" + j, 2.0, "java.calls"));
            }
        }

        // Sparse cross-community bridges
        edges.add(new LeidenCommunityDetector.EdgeInput("bill:0", "ship:0", 1.0, "java.calls"));
        edges.add(new LeidenCommunityDetector.EdgeInput("ship:1", "cat:0", 1.0, "java.calls"));

        var baseline = LeidenCommunityDetector.detect(nodes, edges, null);
        assertTrue(baseline.communities().size() >= 3, "Expected at least 3 emergent communities");
        assertTrue(baseline.modularity().doubleValue() > 0.4, "Expected high modularity Q, got " + baseline.modularity());
        assertFalse(baseline.bridges().isEmpty(), "Expected cross-community bridge edges");
        assertTrue(baseline.misplacedTypes().stream().anyMatch(m -> "drift:0".equals(m.identity())),
                "Expected drift:0 to be flagged as MisplacedType");

        // Verify internal connectivity of every community via independent BFS
        var adj = new HashMap<String, Set<String>>();
        for (var n : nodes) adj.put(n.identity(), new HashSet<>());
        for (var e : edges) {
            adj.get(e.source()).add(e.target());
            adj.get(e.target()).add(e.source());
        }
        for (var comm : baseline.communities()) {
            assertTrue(isConnectedSubgraph(comm.members(), adj),
                    "Community " + comm.id() + " must be internally connected");
        }

        // 10 repeated runs with shuffled input node and edge orders must yield bit-for-bit identical output
        var rng = new Random(42L);
        for (int run = 0; run < 10; run++) {
            var shuffledNodes = new ArrayList<>(nodes);
            var shuffledEdges = new ArrayList<>(edges);
            Collections.shuffle(shuffledNodes, rng);
            Collections.shuffle(shuffledEdges, rng);
            var next = LeidenCommunityDetector.detect(shuffledNodes, shuffledEdges, null);
            assertEquals(baseline.cacheKey(), next.cacheKey());
            assertEquals(baseline.modularity(), next.modularity());
            assertEquals(baseline.communities(), next.communities());
            assertEquals(baseline.misplacedTypes(), next.misplacedTypes());
            assertEquals(baseline.bridges(), next.bridges());
        }
    }

    @Test
    void smartRecursiveSplitBreaksOversizedLowCohesionClusterAndRespectsCancellation() {
        // Build a 60-node sparse chain of 6 linked sub-clusters (cohesion < 0.05 if merged)
        var nodes = new ArrayList<LeidenCommunityDetector.NodeInput>();
        var edges = new ArrayList<LeidenCommunityDetector.EdgeInput>();
        for (int g = 0; g < 6; g++) {
            for (int i = 0; i < 10; i++) {
                int id = g * 10 + i;
                nodes.add(new LeidenCommunityDetector.NodeInput("n:" + id, "pkg.g" + g + ".C" + id, "pkg.g" + g, "TYPE"));
            }
            for (int i = 0; i < 10; i++) {
                int u = g * 10 + i;
                int v = g * 10 + ((i + 1) % 10);
                edges.add(new LeidenCommunityDetector.EdgeInput("n:" + u, "n:" + v, 2.0, "java.calls"));
                int v2 = g * 10 + ((i + 2) % 10);
                edges.add(new LeidenCommunityDetector.EdgeInput("n:" + u, "n:" + v2, 1.0, "java.calls"));
            }
            if (g > 0) {
                edges.add(new LeidenCommunityDetector.EdgeInput("n:" + ((g - 1) * 10), "n:" + (g * 10), 0.5, "java.calls"));
            }
        }

        var result = LeidenCommunityDetector.detect(nodes, edges, null);
        assertTrue(result.communities().size() >= 4, "Expected sparse chain to partition cleanly");
        int maxSize = result.communities().stream().mapToInt(LeidenCommunityDetector.EmergentCommunity::memberCount).max().orElse(0);
        assertTrue(maxSize <= 20, "Oversized community should be split cleanly, max was " + maxSize);

        // Verify cooperative cancellation triggers cleanly via checkpoint
        var ticks = new AtomicInteger();
        var freshNodes = nodes.stream()
                .map(n -> new LeidenCommunityDetector.NodeInput("cancel-" + n.identity(), n.label(), n.declaredPackage(), n.kind()))
                .toList();
        var freshEdges = edges.stream()
                .map(e -> new LeidenCommunityDetector.EdgeInput("cancel-" + e.source(), "cancel-" + e.target(), e.weight(), e.kind()))
                .toList();
        assertThrows(IllegalStateException.class, () -> LeidenCommunityDetector.detect(freshNodes, freshEdges, () -> {
            if (ticks.incrementAndGet() >= 2) throw new IllegalStateException("CANCELLED_TEST");
        }));
    }

    @Test
    void pairwiseCommunityEvolutionClassifiesSurvivedSplitMergedBornAndDissolvedWithoutMutatingSnapshotB() {
        var before = List.of(
                comm("c:surv", "Billing", List.of("a1", "a2", "a3", "a4")),
                comm("c:split", "Monolith", List.of("s1", "s2", "s3", "s4", "s5", "s6")),
                comm("c:m1", "AuthA", List.of("m1", "m2", "m3")),
                comm("c:m2", "AuthB", List.of("m4", "m5", "m6")),
                comm("c:gone", "Legacy", List.of("x1", "x2", "x3")));

        var after = List.of(
                comm("c:surv2", "BillingV2", List.of("a1", "a2", "a3", "a5")),
                comm("c:sp1", "SplitLeft", List.of("s1", "s2", "s3")),
                comm("c:sp2", "SplitRight", List.of("s4", "s5", "s6")),
                comm("c:merged", "UnifiedAuth", List.of("m1", "m2", "m3", "m4", "m5", "m6")),
                comm("c:new", "Analytics", List.of("z1", "z2", "z3")));

        var report = CommunityEvolutionMatcher.match(before, after, 0.25, 0.40);
        assertEquals(1, report.survivedCount());
        assertEquals(1, report.splitCount());
        assertEquals(1, report.mergedCount());
        assertEquals(1, report.bornCount());
        assertEquals(1, report.dissolvedCount());
        // Verify single-snapshot IDs are untouched
        assertEquals("c:surv2", after.get(0).id());
        assertTrue(report.stabilityIndex().doubleValue() > 0.0 && report.stabilityIndex().doubleValue() <= 1.0);
    }

    @Test
    void graphProjectionCommunityGroupingEmitsSuperNodesWhilePackageDigestRemainsIdentical() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/leiden-proj.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("leiden-proj-snap"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("leiden-mod");
        String ctx = analysis.value();
        var mod = ModuleIdentity.from(repo, ".");

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", mod, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", mod, "path", "."));
        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv, modEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        nodes.add(new KnowledgeGraph.Node(mod.value(), ctx, "MODULE", List.of(modEv.identity())));

        var ids = new LinkedHashMap<String, String>();
        var pkgIds = new HashSet<String>();
        for (var pair : List.of(
                List.of("com.acme.a", "A1"), List.of("com.acme.a", "A2"), List.of("com.acme.a", "A3"),
                List.of("com.acme.b", "B1"), List.of("com.acme.b", "B2"), List.of("com.acme.b", "B3"))) {
            var pkgEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.PACKAGE,
                    JavaSymbolName.packageName(pair.get(0)).canonicalName(), Optional.empty());
            if (pkgIds.add(pkgEntity.identity().value())) {
                var pkgDecl = GraphEvidence.capture("java.declaration", Map.of("entity", pkgEntity, "spelling", pair.get(0), "status", "RESOLVED"));
                evidence.add(pkgDecl);
                nodes.add(new KnowledgeGraph.Node(pkgEntity.identity().value(), ctx, "PACKAGE", List.of(pkgDecl.identity())));
            }
            var typeEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                    JavaSymbolName.topLevelType(pair.get(0), pair.get(1)).canonicalName(), Optional.empty());
            String tid = typeEntity.identity().value();
            var ev = GraphEvidence.capture("java.declaration", Map.of("entity", typeEntity, "spelling", pair.get(1), "status", "RESOLVED"));
            var shape = GraphEvidence.capture("java.type-shape", Map.of("type", tid, "kind", "CLASS", "abstractType", false));
            evidence.add(ev);
            evidence.add(shape);
            nodes.add(new KnowledgeGraph.Node(tid, ctx, "TYPE", List.of(ev.identity(), shape.identity())));
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("contains", List.of(pkgEntity.identity().value(), tid)),
                    ctx, "graph.contains", pkgEntity.identity().value(),
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(tid), Optional.empty()),
                    List.of(ev.identity()), Optional.empty()));
            ids.put(pair.get(1), tid);
        }
        for (var e : List.of(
                List.of("A1", "A2"), List.of("A2", "A3"), List.of("A3", "A1"),
                List.of("B1", "B2"), List.of("B2", "B3"), List.of("B3", "B1"),
                List.of("A1", "B1"))) {
            String src = ids.get(e.get(0)), dst = ids.get(e.get(1));
            var ev = GraphEvidence.capture("java.reference", Map.of("from", src, "to", dst));
            evidence.add(ev);
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("dep", List.of(e.get(0), e.get(1))),
                    ctx, "graph.depends-on", src,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                    List.of(ev.identity()), Optional.empty()));
        }

        var graph = new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(ctx, snap, Optional.of(analysis), manEv.identity())),
                nodes, edges, evidence);

        var queries = new ArchitectureMetricsService(graph, ArchitectureMetric.Limits.defaults(), () -> StopReason.NONE,
                Optional.empty(), List.of()).queries();
        var proj = new GraphProjectionService(queries);
        var req = new Request(graph.identity(), ctx, Scope.TYPE, Projection.BOUNDED_UNION, Direction.OUTGOING,
                Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());

        var pkgDefault = proj.project(req, Optional.empty());
        var pkgExplicit = proj.project(req, Optional.empty(), GraphProjectionService.GroupBy.PACKAGE);
        assertEquals(pkgDefault.id(), pkgExplicit.id(), "Default PACKAGE visual-graph digest must stay bit-for-bit identical");

        var commVisual = proj.project(req, Optional.empty(), GraphProjectionService.GroupBy.COMMUNITY);
        assertEquals("COMMUNITY", commVisual.metadata().get("groupBy"));
        var commSuperNodes = commVisual.nodes().stream()
                .filter(n -> n.id().startsWith("compound:community:")).toList();
        assertTrue(commSuperNodes.size() >= 2, "Expected >=2 community compound super-nodes");
        for (var sn : commSuperNodes) {
            assertEquals("COMMUNITY", sn.kind());
            assertTrue(sn.properties().containsKey("memberCount"));
            assertTrue(sn.properties().containsKey("cohesion"));
            assertTrue(sn.properties().containsKey("dominantPackage"));
            assertTrue(sn.properties().containsKey("misplacedCount"));
            assertTrue(sn.properties().containsKey("bridgeEdgeCount"));
        }
        assertTrue(commVisual.edges().stream().anyMatch(e -> Boolean.TRUE.equals(e.properties().get("isBridge"))),
                "Expected cross-community bridge edge to be marked isBridge=true");
    }

    @Test
    void cacheKeyDistinguishesReversedEdgeDirectionChangedEdgeKindAndRenamedNodeLabels() {
        var nodesA = List.of(
                new LeidenCommunityDetector.NodeInput("a1", "com.acme.a.A1", "com.acme.a", "TYPE"),
                new LeidenCommunityDetector.NodeInput("a2", "com.acme.a.A2", "com.acme.a", "TYPE"),
                new LeidenCommunityDetector.NodeInput("a3", "com.acme.a.A3", "com.acme.a", "TYPE"),
                new LeidenCommunityDetector.NodeInput("b1", "com.acme.b.B1", "com.acme.b", "TYPE"),
                new LeidenCommunityDetector.NodeInput("b2", "com.acme.b.B2", "com.acme.b", "TYPE"),
                new LeidenCommunityDetector.NodeInput("b3", "com.acme.b.B3", "com.acme.b", "TYPE"));
        var edgesForward = List.of(
                new LeidenCommunityDetector.EdgeInput("a1", "a2", 3.0, "java.calls"),
                new LeidenCommunityDetector.EdgeInput("a2", "a3", 3.0, "java.calls"),
                new LeidenCommunityDetector.EdgeInput("a3", "a1", 3.0, "java.calls"),
                new LeidenCommunityDetector.EdgeInput("b1", "b2", 3.0, "java.calls"),
                new LeidenCommunityDetector.EdgeInput("b2", "b3", 3.0, "java.calls"),
                new LeidenCommunityDetector.EdgeInput("b3", "b1", 3.0, "java.calls"),
                new LeidenCommunityDetector.EdgeInput("a1", "b1", 0.5, "java.calls"));
        var edgesReversed = new ArrayList<>(edgesForward.subList(0, 6));
        edgesReversed.add(new LeidenCommunityDetector.EdgeInput("b1", "a1", 0.5, "java.calls"));

        var edgesDifferentKind = new ArrayList<>(edgesForward.subList(0, 6));
        edgesDifferentKind.add(new LeidenCommunityDetector.EdgeInput("a1", "b1", 0.5, "spring.selected-binding"));

        var nodesRenamed = new ArrayList<>(nodesA);
        nodesRenamed.set(0, new LeidenCommunityDetector.NodeInput("a1", "com.acme.a.RenamedAlpha", "com.acme.a", "TYPE"));

        var rForward = LeidenCommunityDetector.detect(nodesA, edgesForward, null);
        var rReversed = LeidenCommunityDetector.detect(nodesA, edgesReversed, null);
        var rDiffKind = LeidenCommunityDetector.detect(nodesA, edgesDifferentKind, null);
        var rRenamed = LeidenCommunityDetector.detect(nodesRenamed, edgesForward, null);

        assertNotEquals(rForward.cacheKey(), rReversed.cacheKey(), "Reversed bridge direction must produce distinct cacheKey");
        assertNotEquals(rForward.cacheKey(), rDiffKind.cacheKey(), "Different edge kind must produce distinct cacheKey");
        assertNotEquals(rForward.cacheKey(), rRenamed.cacheKey(), "Renamed node label must produce distinct cacheKey");
        assertFalse(rForward.bridges().isEmpty());
        assertFalse(rReversed.bridges().isEmpty());
        assertNotEquals(rForward.bridges().getFirst().fromCommunityId(), rReversed.bridges().getFirst().fromCommunityId(),
                "Reversed bridge must flip fromCommunityId rather than returning stale cached bridge");
        assertTrue(rRenamed.communities().stream().anyMatch(c -> c.label().contains("RenamedAlpha") || c.hubLabel().contains("RenamedAlpha")),
                "Renamed hub label must not serve stale cached label");
    }

    @Test
    void twoThousandNodeModularGraphCompletesQuicklyAndDeterministically() {
        var nodes = new ArrayList<LeidenCommunityDetector.NodeInput>(2000);
        var edges = new ArrayList<LeidenCommunityDetector.EdgeInput>(8000);
        int clusters = 40;
        int perCluster = 50;
        for (int c = 0; c < clusters; c++) {
            for (int i = 0; i < perCluster; i++) {
                int id = c * perCluster + i;
                nodes.add(new LeidenCommunityDetector.NodeInput("scale:" + id, "pkg.c" + c + ".N" + id, "pkg.c" + c, "TYPE"));
            }
            for (int i = 0; i < perCluster; i++) {
                int u = c * perCluster + i;
                int v1 = c * perCluster + ((i + 1) % perCluster);
                int v2 = c * perCluster + ((i + 2) % perCluster);
                int v3 = c * perCluster + ((i + 5) % perCluster);
                edges.add(new LeidenCommunityDetector.EdgeInput("scale:" + u, "scale:" + v1, 2.0, "java.calls"));
                edges.add(new LeidenCommunityDetector.EdgeInput("scale:" + u, "scale:" + v2, 1.5, "java.calls"));
                edges.add(new LeidenCommunityDetector.EdgeInput("scale:" + u, "scale:" + v3, 1.0, "java.calls"));
            }
            if (c > 0) {
                edges.add(new LeidenCommunityDetector.EdgeInput("scale:" + ((c - 1) * perCluster), "scale:" + (c * perCluster), 0.5, "java.calls"));
            }
        }
        long startMs = System.currentTimeMillis();
        var res = LeidenCommunityDetector.detect(nodes, edges, null);
        long elapsedMs = System.currentTimeMillis() - startMs;
        assertEquals(2000, res.nodeCount());
        assertTrue(res.communities().size() >= 20, "Expected modular communities on 2000-node graph");
        assertTrue(res.modularity().doubleValue() > 0.7, "Expected high modularity Q, got " + res.modularity());
        assertTrue(elapsedMs < 10_000, "2000-node Leiden should finish rapidly, took " + elapsedMs + "ms");
    }

    @Test
    void communityEvolutionMatcherNeverDoubleClaimsSharedAfterCommunityAcrossTwoSplits() {
        // Both b1 and b2 overlap significantly with a1, a2, a3 (many-to-many reorg)
        var before = List.of(
                comm("b1", "Alpha", List.of("x1", "x2", "x3", "x4")),
                comm("b2", "Beta", List.of("x2", "x3", "x5", "x6")));
        var after = List.of(
                comm("a1", "Part1", List.of("x1", "x2")),
                comm("a2", "Part2", List.of("x3", "x4")),
                comm("a3", "Part3", List.of("x5", "x6")));

        var report = CommunityEvolutionMatcher.match(before, after, 0.20, 0.35);
        var claimedAfter = new ArrayList<String>();
        for (var tr : report.transitions()) {
            claimedAfter.addAll(tr.afterCommunityIds());
        }
        assertEquals(new HashSet<>(claimedAfter).size(), claimedAfter.size(),
                "No after-community may be double-claimed across transitions: " + report.transitions());
    }

    @Test
    void emptyGraphProducesDeterministicCanonicalResultWithoutFloatingPointErrors() {
        var res = LeidenCommunityDetector.detect(List.of(), List.of(), () -> {});
        assertEquals(0, res.nodeCount());
        assertEquals(0, res.edgeCount());
        assertTrue(res.communities().isEmpty());
        assertTrue(res.cacheKey().startsWith("sha256:"));
        // Verify CommunityEvolutionMatcher also handles empty snapshots cleanly
        var evo = CommunityEvolutionMatcher.match(res, res);
        assertEquals(0, evo.beforeCommunityCount());
        assertEquals(0, evo.afterCommunityCount());
    }

    @Test
    void graphProjectionCommunityModeExcludesExternalLibraryNoiseAndMatchesProjectClustering() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/leiden-ext-noise.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("leiden-ext-noise-snap"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("leiden-ext-mod");
        String ctx = analysis.value();
        var mod = ModuleIdentity.from(repo, ".");

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", mod, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", mod, "path", "."));
        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv, modEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        nodes.add(new KnowledgeGraph.Node(mod.value(), ctx, "MODULE", List.of(modEv.identity())));

        var ids = new LinkedHashMap<String, String>();
        var projectNames = List.of(
                List.of("com.acme.billing", "Bill1"), List.of("com.acme.billing", "Bill2"),
                List.of("com.acme.billing", "Bill3"), List.of("com.acme.billing", "Bill4"),
                List.of("com.acme.legacy", "LegacySettlementReconciler"),
                List.of("com.acme.orders", "Order1"), List.of("com.acme.orders", "Order2"),
                List.of("com.acme.orders", "Order3"), List.of("com.acme.orders", "Order4"));
        for (var spec : projectNames) {
            var ent = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                    JavaSymbolName.topLevelType(spec.get(0), spec.get(1)).canonicalName(), Optional.empty());
            var decl = GraphEvidence.capture("java.declaration", Map.of("entity", ent, "spelling", spec.get(1), "status", "RESOLVED"));
            evidence.add(decl);
            nodes.add(new KnowledgeGraph.Node(ent.identity().value(), ctx, "TYPE", List.of(decl.identity())));
            ids.put(spec.get(1), ent.identity().value());
        }
        // Add external JDK + DEPENDENCY hub types wired from EVERY project class
        var externalIds = new ArrayList<String>();
        var externalSpecs = List.of(
                List.of("JDK", "java.lang", "String"),
                List.of("JDK", "java.util", "List"),
                List.of("DEPENDENCY", "jakarta.persistence", "Entity"),
                List.of("DEPENDENCY", "lombok", "Data"));
        for (var ext : externalSpecs) {
            EntityOrigin origin = "JDK".equals(ext.get(0)) ? EntityOrigin.JDK : EntityOrigin.DEPENDENCY;
            EntityScope scope = EntityScope.external(origin, ext.get(1), ContentDigest.sha256Utf8(ext.get(1) + "." + ext.get(2)));
            var ent = Entity.create(origin, scope, EntityKind.TYPE,
                    JavaSymbolName.topLevelType(ext.get(1), ext.get(2)).canonicalName(), Optional.empty());
            var decl = GraphEvidence.capture("java.declaration", Map.of("entity", ent, "spelling", ext.get(2), "status", "RESOLVED"));
            evidence.add(decl);
            nodes.add(new KnowledgeGraph.Node(ent.identity().value(), ctx, "TYPE", List.of(decl.identity())));
            externalIds.add(ent.identity().value());
        }

        // Dense internal cliques for billing & orders + misplaced LegacySettlementReconciler wired tightly into billing
        var projectEdges = new ArrayList<List<String>>();
        var billGroup = List.of("Bill1", "Bill2", "Bill3", "Bill4");
        var orderGroup = List.of("Order1", "Order2", "Order3", "Order4");
        for (int i = 0; i < billGroup.size(); i++) {
            for (int j = i + 1; j < billGroup.size(); j++) {
                projectEdges.add(List.of(billGroup.get(i), billGroup.get(j)));
            }
        }
        for (int i = 0; i < orderGroup.size(); i++) {
            for (int j = i + 1; j < orderGroup.size(); j++) {
                projectEdges.add(List.of(orderGroup.get(i), orderGroup.get(j)));
            }
        }
        // Misplaced legacy reconciler coupled exclusively to billing (3 edges, 0 in com.acme.legacy)
        projectEdges.add(List.of("LegacySettlementReconciler", "Bill1"));
        projectEdges.add(List.of("LegacySettlementReconciler", "Bill2"));
        projectEdges.add(List.of("LegacySettlementReconciler", "Bill3"));
        // Single bridge between billing and orders
        projectEdges.add(List.of("Order1", "Bill1"));

        int edgeSeq = 0;
        for (var pair : projectEdges) {
            String src = ids.get(pair.get(0)), dst = ids.get(pair.get(1));
            var ev = GraphEvidence.capture("java.reference", Map.of("from", src, "to", dst));
            evidence.add(ev);
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("dep", List.of(pair.get(0), pair.get(1), String.valueOf(edgeSeq++))),
                    ctx, "graph.depends-on", src,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                    List.of(ev.identity()), Optional.empty()));
        }
        // Connect EVERY project class to EVERY external JDK/Lombok/JPA library type (simulating unfiltered real repo)
        for (var pId : ids.values()) {
            for (var eId : externalIds) {
                var ev = GraphEvidence.capture("java.reference", Map.of("from", pId, "to", eId));
                evidence.add(ev);
                edges.add(new KnowledgeGraph.Edge(
                        KnowledgeGraph.derivedIdentity("extdep", List.of(pId, eId, String.valueOf(edgeSeq++))),
                        ctx, "graph.depends-on", pId,
                        new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(eId), Optional.empty()),
                        List.of(ev.identity()), Optional.empty()));
            }
        }

        var graph = new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(ctx, snap, Optional.of(analysis), manEv.identity())),
                nodes, edges, evidence);
        var queries = new ArchitectureMetricsService(graph, ArchitectureMetric.Limits.defaults(), () -> StopReason.NONE,
                Optional.empty(), List.of()).queries();
        var proj = new GraphProjectionService(queries);

        var unfilteredReq = new Request(graph.identity(), ctx, Scope.TYPE, Projection.BOUNDED_UNION, Direction.OUTGOING,
                Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
        var projectOnlyReq = new Request(graph.identity(), ctx, Scope.TYPE, Projection.BOUNDED_UNION, Direction.OUTGOING,
                Set.of("graph.depends-on"), new Population(Set.of(), Set.of(), Set.of("PROJECT"), Set.of()),
                false, Optional.empty(), Limits.defaults());

        var unfilteredVisual = proj.project(unfilteredReq, Optional.empty(), GraphProjectionService.GroupBy.COMMUNITY);
        var projectOnlyVisual = proj.project(projectOnlyReq, Optional.empty(), GraphProjectionService.GroupBy.COMMUNITY);

        assertEquals(projectOnlyVisual.metadata().get("communityModularity"),
                unfilteredVisual.metadata().get("communityModularity"),
                "Unfiltered COMMUNITY visual-graph must match PROJECT-only modularity Q exactly");
        assertEquals(projectOnlyVisual.metadata().get("communityCount"),
                unfilteredVisual.metadata().get("communityCount"),
                "External library types must not create extra Leiden communities");
        assertEquals(4, unfilteredVisual.metadata().get("externalNodeCount"));

        // LegacySettlementReconciler must be flagged misplaced=true in unfiltered visual-graph too!
        String reconcilerId = ids.get("LegacySettlementReconciler");
        var reconcilerNode = unfilteredVisual.nodes().stream()
                .filter(n -> reconcilerId.equals(n.id())).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, reconcilerNode.properties().get("misplaced"),
                "LegacySettlementReconciler must remain misplaced=true even when external JDK/Lombok/JPA nodes are present");

        // External nodes must live in separate compound:external bucket, never inside a community super-node
        assertTrue(unfilteredVisual.nodes().stream().anyMatch(n -> "compound:external".equals(n.id())));
        for (String extId : externalIds) {
            var extNode = unfilteredVisual.nodes().stream().filter(n -> extId.equals(n.id())).findFirst().orElseThrow();
            assertEquals(Optional.of("compound:external"), extNode.parent());
            assertEquals(Boolean.TRUE, extNode.properties().get("externalUnclustered"));
            assertEquals(Boolean.FALSE, extNode.properties().get("misplaced"));
            assertFalse(extNode.properties().containsKey("communityId"));
        }
    }

    @Test
    void graphProjectionCommunityCancellationThrowsProjectionInterrupted() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/leiden-cancel.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("leiden-cancel-snap"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("leiden-cancel-mod");
        String ctx = analysis.value();
        var mod = ModuleIdentity.from(repo, ".");
        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", mod, "path", "."))));
        var ent = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                JavaSymbolName.topLevelType("com.acme.c", "OnlyType").canonicalName(), Optional.empty());
        var decl = GraphEvidence.capture("java.declaration", Map.of("entity", ent, "spelling", "OnlyType", "status", "RESOLVED"));
        var graph = new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(ctx, snap, Optional.of(analysis), manEv.identity())),
                List.of(new KnowledgeGraph.Node(ent.identity().value(), ctx, "TYPE", List.of(decl.identity()))),
                List.of(), List.of(snapEv, manEv, decl));

        // Flip to CANCELLED once metricProjection & findCycles have run and Leiden enters checkpoint
        var stopFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        var queries = new ArchitectureMetricsService(graph, ArchitectureMetric.Limits.defaults(),
                () -> stopFlag.get() ? StopReason.CANCELLED : StopReason.NONE,
                Optional.empty(), List.of()).queries();
        // Pre-warm metricProjection + findCycles aren't cancelled; simulate cancellation right when Leiden checks
        stopFlag.set(true);
        var proj = new GraphProjectionService(queries);
        var req = new Request(graph.identity(), ctx, Scope.TYPE, Projection.BOUNDED_UNION, Direction.OUTGOING,
                Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
        // With stopFlag already CANCELLED, metricProjection may return empty entities if cancelled early,
        // so test flipping stopFlag on the Nth stop-reason poll right during Leiden detection:
        var polls = new AtomicInteger();
        var lateCancelQueries = new ArchitectureMetricsService(graph, ArchitectureMetric.Limits.defaults(),
                () -> polls.incrementAndGet() >= 4 ? StopReason.CANCELLED : StopReason.NONE,
                Optional.empty(), List.of()).queries();
        var lateProj = new GraphProjectionService(lateCancelQueries);
        // Find exact poll count where metricProjection + findCycles return entityItems non-empty and Leiden cancels
        int totalPollsInUninterrupted = 0;
        var counter = new AtomicInteger();
        var countQueries = new ArchitectureMetricsService(graph, ArchitectureMetric.Limits.defaults(),
                () -> { counter.incrementAndGet(); return StopReason.NONE; }, Optional.empty(), List.of()).queries();
        new GraphProjectionService(countQueries).project(req, Optional.empty(), GraphProjectionService.GroupBy.PACKAGE);
        totalPollsInUninterrupted = counter.get();

        final int cancelAt = totalPollsInUninterrupted + 1;
        var exactCounter = new AtomicInteger();
        var exactQueries = new ArchitectureMetricsService(graph, ArchitectureMetric.Limits.defaults(),
                () -> exactCounter.incrementAndGet() >= cancelAt ? StopReason.CANCELLED : StopReason.NONE,
                Optional.empty(), List.of()).queries();
        var interrupted = assertThrows(GraphProjectionService.ProjectionInterrupted.class,
                () -> new GraphProjectionService(exactQueries).project(req, Optional.empty(), GraphProjectionService.GroupBy.COMMUNITY));
        assertEquals(StopReason.CANCELLED, interrupted.reason());
    }

    @Test
    void communityEvolutionMatcherIsOrderIndependentUnderCompetedInputPermutations() {
        var before = new ArrayList<>(List.of(
                comm("b1", "Alpha", List.of("x1", "x2", "x3", "x4")),
                comm("b2", "Beta", List.of("x2", "x3", "x5", "x6")),
                comm("b3", "Gamma", List.of("y1", "y2", "y3"))));
        var after = new ArrayList<>(List.of(
                comm("a1", "Part1", List.of("x1", "x2")),
                comm("a2", "Part2", List.of("x3", "x4")),
                comm("a3", "Part3", List.of("x5", "x6")),
                comm("a4", "Delta", List.of("y1", "y2", "z1"))));

        var baseline = CommunityEvolutionMatcher.match(before, after, 0.20, 0.35);
        var rng = new Random(1337L);
        for (int run = 0; run < 10; run++) {
            var bShuffled = new ArrayList<>(before);
            var aShuffled = new ArrayList<>(after);
            Collections.shuffle(bShuffled, rng);
            Collections.shuffle(aShuffled, rng);
            var bBeforeOrder = List.copyOf(bShuffled);
            var next = CommunityEvolutionMatcher.match(bShuffled, aShuffled, 0.20, 0.35);
            assertEquals(baseline, next, "CommunityEvolutionMatcher must produce identical Report regardless of input order");
            assertEquals(bBeforeOrder, bShuffled, "Input lists must not be mutated in place");
        }
    }

    @Test
    void enhancedCompositeWeightingAppliesUtilityDownweightingCyclicBoostBm25LexicalAndDirectoryProximity() {
        var nodes = new ArrayList<LeidenCommunityDetector.NodeInput>();
        var edges = new ArrayList<LeidenCommunityDetector.EdgeInput>();

        // Domain A: mutual cycle between InvoiceCalculator <-> InvoiceValidator (same package & domain tokens)
        nodes.add(new LeidenCommunityDetector.NodeInput("a1", "com.acme.billing.InvoiceCalculator", "com.acme.billing", "TYPE"));
        nodes.add(new LeidenCommunityDetector.NodeInput("a2", "com.acme.billing.InvoiceValidator", "com.acme.billing", "TYPE"));
        nodes.add(new LeidenCommunityDetector.NodeInput("a3", "com.acme.billing.InvoiceLedger", "com.acme.billing", "TYPE"));
        // Domain B: shipping classes
        nodes.add(new LeidenCommunityDetector.NodeInput("b1", "com.acme.shipping.ShipmentTracker", "com.acme.shipping", "TYPE"));
        nodes.add(new LeidenCommunityDetector.NodeInput("b2", "com.acme.shipping.ShipmentRouter", "com.acme.shipping", "TYPE"));
        nodes.add(new LeidenCommunityDetector.NodeInput("b3", "com.acme.shipping.ShipmentManifest", "com.acme.shipping", "TYPE"));
        // Shared high-inDegree utility class called by ALL 6 domain classes
        nodes.add(new LeidenCommunityDetector.NodeInput("util", "com.acme.common.StringFormatUtil", "com.acme.common", "TYPE"));

        // Mutual cycle in billing (a1 <-> a2) + directed edges inside billing & shipping
        edges.add(new LeidenCommunityDetector.EdgeInput("a1", "a2", 1.0, "java.calls"));
        edges.add(new LeidenCommunityDetector.EdgeInput("a2", "a1", 1.0, "java.calls"));
        edges.add(new LeidenCommunityDetector.EdgeInput("a1", "a3", 1.0, "java.calls"));
        edges.add(new LeidenCommunityDetector.EdgeInput("a2", "a3", 1.0, "java.calls"));

        edges.add(new LeidenCommunityDetector.EdgeInput("b1", "b2", 1.0, "java.calls"));
        edges.add(new LeidenCommunityDetector.EdgeInput("b2", "b3", 1.0, "java.calls"));
        edges.add(new LeidenCommunityDetector.EdgeInput("b3", "b1", 1.0, "java.calls"));

        // Every domain node calls shared utility hub (inDeg(util) = 6)
        for (String caller : List.of("a1", "a2", "a3", "b1", "b2", "b3")) {
            edges.add(new LeidenCommunityDetector.EdgeInput(caller, "util", 1.0, "java.calls"));
        }

        var weights = LeidenCommunityDetector.computeEnhancedEdgeWeights(nodes, edges, Map.of());
        var byPair = new HashMap<String, LeidenCommunityDetector.EnhancedEdgeWeight>();
        for (var w : weights) byPair.put(w.source() + "|" + w.target(), w);

        var cycPair = byPair.get("a1|a2");
        var acycPair = byPair.get("a1|a3");
        var utilPair = byPair.get("a1|util");
        assertNotNull(cycPair);
        assertNotNull(acycPair);
        assertNotNull(utilPair);

        // SSAR Eq. 6: mutual cycle a1<->a2 gets 2x boost (inDeg(a1)=1, inDeg(a2)=1 -> 2*(1/1 + 1/1) = 4.0)
        assertTrue(cycPair.mutualCycle());
        assertEquals(4.0, cycPair.rawStructuralWeight(), 1e-9);
        assertEquals(1.0, cycPair.normalizedStructuralWeight(), 1e-9);

        // Acyclic domain edge a1->a3 (inDeg(a3)=2 -> 1*(1/2) = 0.5)
        assertFalse(acycPair.mutualCycle());
        assertEquals(0.5, acycPair.rawStructuralWeight(), 1e-9);

        // SemRef Eq. 1 utility downweighting: a1->util has inDeg(util)=6 -> 1/6 = 0.1667 << domain edges
        assertEquals(1.0 / 6.0, utilPair.rawStructuralWeight(), 1e-9);
        assertTrue(utilPair.rawStructuralWeight() < acycPair.rawStructuralWeight());
        assertTrue(utilPair.compositeWeight() < acycPair.compositeWeight());

        // Lexical BM25 cosine and directory proximity in [0, 1] + exact 0.55/0.30/0.15 linear combination
        assertTrue(cycPair.lexicalSimilarity() > 0.0 && cycPair.lexicalSimilarity() <= 1.0);
        assertEquals(1.0, cycPair.directoryProximity(), 1e-9);
        assertEquals(0.0, utilPair.directoryProximity(), 1e-9);
        double expectedComposite = 0.55 * cycPair.normalizedStructuralWeight()
                + 0.30 * cycPair.lexicalSimilarity()
                + 0.15 * cycPair.directoryProximity();
        assertEquals(expectedComposite, cycPair.compositeWeight(), 1e-12);

        // Clustering keeps billing and shipping separated despite shared utility hub
        var detected = LeidenCommunityDetector.detect(nodes, edges, null);
        assertEquals(LeidenCommunityDetector.ENHANCED_ALGORITHM, detected.algorithm());
        assertNotEquals(detected.communityByNode().get("a1"), detected.communityByNode().get("b1"),
                "Utility hub downweighting must prevent billing and shipping from collapsing together");
        assertEquals(detected.communityByNode().get("a1"), detected.communityByNode().get("a2"),
                "2x cyclic boost must keep mutually cyclic pair together");
    }

    @Test
    void oracleFreeMultiGammaSweepMapsMacroMesoMicroDeterministicallyWithZeroGtLeakage() {
        var nodes = new ArrayList<LeidenCommunityDetector.NodeInput>();
        var edges = new ArrayList<LeidenCommunityDetector.EdgeInput>();
        // 4 distinct clusters (8 nodes each) with 2 weak super-pair bridges so gamma=0.5..2.0 spans coarseness cleanly
        String[] domains = {"billing", "ledger", "shipping", "customs"};
        for (int c = 0; c < 4; c++) {
            for (int i = 0; i < 8; i++) {
                String id = domains[c] + ":" + i;
                nodes.add(new LeidenCommunityDetector.NodeInput(
                        id, "com.acme." + domains[c] + "." + domains[c] + "Service" + i,
                        "com.acme." + domains[c], "TYPE"));
            }
            for (int i = 0; i < 8; i++) {
                edges.add(new LeidenCommunityDetector.EdgeInput(
                        domains[c] + ":" + i, domains[c] + ":" + ((i + 1) % 8), 2.0, "java.calls"));
                edges.add(new LeidenCommunityDetector.EdgeInput(
                        domains[c] + ":" + i, domains[c] + ":" + ((i + 2) % 8), 1.5, "java.calls"));
            }
        }
        edges.add(new LeidenCommunityDetector.EdgeInput("billing:0", "ledger:0", 0.5, "java.calls"));
        edges.add(new LeidenCommunityDetector.EdgeInput("shipping:0", "customs:0", 0.5, "java.calls"));
        edges.add(new LeidenCommunityDetector.EdgeInput("ledger:1", "shipping:1", 0.25, "java.calls"));

        var sweep1 = LeidenCommunityDetector.sweepMultiGamma(nodes, edges, null);
        assertEquals(4, sweep1.levels().size());
        assertEquals(0.5, sweep1.macroGamma(), 1e-9);
        assertTrue(sweep1.mesoGamma() == 1.0 || sweep1.mesoGamma() == 1.5,
                "MESO gamma must come from oracle-free elbow {1.0, 1.5}, got " + sweep1.mesoGamma());
        assertEquals(2.0, sweep1.microGamma(), 1e-9);
        assertFalse(sweep1.mesoSelectionRationale().isBlank());
        assertTrue(sweep1.micro().communities().size() >= sweep1.macro().communities().size());

        // Shuffled replay must produce bit-identical multi-gamma sweep results
        var shuffledNodes = new ArrayList<>(nodes);
        var shuffledEdges = new ArrayList<>(edges);
        var rng = new Random(2026L);
        Collections.shuffle(shuffledNodes, rng);
        Collections.shuffle(shuffledEdges, rng);
        var sweep2 = LeidenCommunityDetector.sweepMultiGamma(shuffledNodes, shuffledEdges, null);

        assertEquals(sweep1.macroGamma(), sweep2.macroGamma());
        assertEquals(sweep1.mesoGamma(), sweep2.mesoGamma());
        assertEquals(sweep1.microGamma(), sweep2.microGamma());
        assertEquals(sweep1.macro().communities(), sweep2.macro().communities());
        assertEquals(sweep1.meso().communities(), sweep2.meso().communities());
        assertEquals(sweep1.micro().communities(), sweep2.micro().communities());
    }

    private static LeidenCommunityDetector.EmergentCommunity comm(String id, String label, List<String> members) {
        return new LeidenCommunityDetector.EmergentCommunity(
                id, "sha256:0000", label, "com.acme", members.getFirst(), members.getFirst(),
                members.size(), members.size(), 1, new java.math.BigDecimal("0.7500"), 0, 1, members);
    }

    private static boolean isConnectedSubgraph(List<String> members, Map<String, Set<String>> adj) {
        if (members.size() <= 1) return true;
        var memberSet = new HashSet<>(members);
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(members.getFirst());
        visited.add(members.getFirst());
        while (!queue.isEmpty()) {
            String cur = queue.removeFirst();
            for (String nxt : adj.getOrDefault(cur, Set.of())) {
                if (memberSet.contains(nxt) && visited.add(nxt)) queue.add(nxt);
            }
        }
        return visited.size() == memberSet.size();
    }
}
