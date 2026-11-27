package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalibrationAndTopologyV2Test {

    @Test void piecewiseCalibrationCurveMatchesAnchorsAndIsStrictlyMonotone() {
        var lower = CalibrationCurve.lowerIsBetter("0.00", "0.12", "0.35", "0.70", "92");
        assertEquals(0, new BigDecimal("100").compareTo(lower.evaluate(BigDecimal.ZERO, false)));
        assertEquals(0, new BigDecimal("92").compareTo(lower.evaluate(new BigDecimal("0.01"), true)));
        assertEquals(0, new BigDecimal("75").compareTo(lower.evaluate(new BigDecimal("0.12"), true)));
        assertEquals(0, new BigDecimal("35").compareTo(lower.evaluate(new BigDecimal("0.35"), true)));
        assertEquals(0, BigDecimal.ZERO.compareTo(lower.evaluate(new BigDecimal("0.70"), true)));
        assertEquals(0, BigDecimal.ZERO.compareTo(lower.evaluate(new BigDecimal("1.50"), true)));

        BigDecimal prev = new BigDecimal("101");
        for (int i = 0; i <= 100; i++) {
            BigDecimal x = new BigDecimal(i).divide(new BigDecimal("100"));
            BigDecimal score = lower.evaluate(x);
            assertTrue(score.compareTo(prev) <= 0, "Expected monotone non-increasing at x=" + x);
            prev = score;
        }

        var higher = CalibrationCurve.higherIsBetter("0.80", "0.60", "0.35", "0.15");
        assertEquals(0, new BigDecimal("100").compareTo(higher.evaluate(new BigDecimal("0.90"))));
        assertEquals(0, new BigDecimal("75").compareTo(higher.evaluate(new BigDecimal("0.60"))));
        assertEquals(0, new BigDecimal("35").compareTo(higher.evaluate(new BigDecimal("0.35"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(higher.evaluate(new BigDecimal("0.15"))));

        prev = new BigDecimal("-1");
        for (int i = 0; i <= 100; i++) {
            BigDecimal x = new BigDecimal(i).divide(new BigDecimal("100"));
            BigDecimal score = higher.evaluate(x);
            assertTrue(score.compareTo(prev) >= 0, "Expected monotone non-decreasing at x=" + x);
            prev = score;
        }
    }

    @Test void balancedBinaryTreeHasExactNccdOneAndZeroNormalizedPropagationCost() {
        // 7-node complete binary tree: root(0)->1,2; 1->3,4; 2->5,6.
        // Reachability sizes: 0->7, 1->3, 2->3, 3..6->1 => CCD = 7 + 3 + 3 + 4 = 17.
        // Lakos CCD_balanced(7) = (7+1)*log2(8) - 7 = 8*3 - 7 = 17 => NCCD = 1.0, PC_norm = 0.0!
        var nodes = new ArrayList<StructuralTopologyAnalyzer.NodeInfo>();
        for (int i = 0; i < 7; i++) {
            nodes.add(node("T" + i, "com.example.tree", "mod.a", "CLASS", false, 5));
        }
        var edges = List.of(
                edge("T0", "T1"), edge("T0", "T2"),
                edge("T1", "T3"), edge("T1", "T4"),
                edge("T2", "T5"), edge("T2", "T6"));
        var metrics = StructuralTopologyAnalyzer.analyze(new StructuralTopologyAnalyzer.StructuralGraph(nodes, edges), 10, () -> {});
        assertEquals(0, BigDecimal.ONE.compareTo(metrics.typeNccd().orElseThrow()));
        assertEquals(0, BigDecimal.ZERO.compareTo(metrics.normalizedPropagationCost().orElseThrow()));
        assertEquals(0, metrics.cyclicTypeSccCount());
    }

    @Test void completeCliqueHasNormalizedPropagationCostOneAndHighCyclicity() {
        int n = 6;
        var nodes = new ArrayList<StructuralTopologyAnalyzer.NodeInfo>();
        var edges = new ArrayList<StructuralTopologyAnalyzer.EdgeInfo>();
        for (int i = 0; i < n; i++) {
            nodes.add(node("K" + i, "com.example.pkg" + i, "mod.a", "CLASS", false, 5));
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) edges.add(edge("K" + i, "K" + j));
            }
        }
        var metrics = StructuralTopologyAnalyzer.analyze(new StructuralTopologyAnalyzer.StructuralGraph(nodes, edges), 10, () -> {});
        assertEquals(0, BigDecimal.ONE.compareTo(metrics.rawPropagationCost().orElseThrow()));
        assertEquals(0, BigDecimal.ONE.compareTo(metrics.normalizedPropagationCost().orElseThrow()));
        assertEquals(1, metrics.cyclicTypeSccCount());
        assertEquals(1, metrics.cyclicPackageSccCount());
        // RC_type = 6 / max(6, 20) = 0.30
        assertEquals(0, new BigDecimal("0.3").compareTo(metrics.typeRelativeCyclicity().orElseThrow()));
        // RC_pkg = 6 / max(6, 10) = 0.60
        assertEquals(0, new BigDecimal("0.6").compareTo(metrics.packageRelativeCyclicity().orElseThrow()));
        assertTrue(metrics.decouplingLevel().orElseThrow().compareTo(new BigDecimal("0.20")) < 0);
    }

    @Test void giantSccScoresStrictlyWorseThanTenSmallTwoNodeCycles() {
        // Both have 100 nodes and 20 tangled nodes, but giant 20-SCC has RC=0.20 vs 10*2-SCC RC=sqrt(40)/100=0.0632.
        var nodesA = new ArrayList<StructuralTopologyAnalyzer.NodeInfo>();
        var edgesA = new ArrayList<StructuralTopologyAnalyzer.EdgeInfo>();
        var nodesB = new ArrayList<StructuralTopologyAnalyzer.NodeInfo>();
        var edgesB = new ArrayList<StructuralTopologyAnalyzer.EdgeInfo>();
        for (int i = 0; i < 100; i++) {
            nodesA.add(node("A" + i, "com.example.p" + (i / 5), "mod.a", "CLASS", false, 5));
            nodesB.add(node("B" + i, "com.example.p" + (i / 5), "mod.a", "CLASS", false, 5));
        }
        // Graph A: 10 disjoint 2-cycles
        for (int k = 0; k < 10; k++) {
            edgesA.add(edge("A" + (2 * k), "A" + (2 * k + 1)));
            edgesA.add(edge("A" + (2 * k + 1), "A" + (2 * k)));
        }
        // Graph B: 1 giant 20-cycle
        for (int k = 0; k < 20; k++) {
            edgesB.add(edge("B" + k, "B" + ((k + 1) % 20)));
        }
        var mA = StructuralTopologyAnalyzer.analyze(new StructuralTopologyAnalyzer.StructuralGraph(nodesA, edgesA), 10, () -> {});
        var mB = StructuralTopologyAnalyzer.analyze(new StructuralTopologyAnalyzer.StructuralGraph(nodesB, edgesB), 10, () -> {});
        assertTrue(mB.typeRelativeCyclicity().orElseThrow().compareTo(mA.typeRelativeCyclicity().orElseThrow()) > 0);
        var curve = ArchitectureScoreModelV2.standard().subMetric(ArchitectureScoreModelV2.SUB_D1_TYPE_RC).orElseThrow().curve();
        assertTrue(curve.evaluate(mB.typeRelativeCyclicity().orElseThrow(), true)
                .compareTo(curve.evaluate(mA.typeRelativeCyclicity().orElseThrow(), true)) < 0);
    }

    @Test void stableLeafValueObjectIsNeverPenalizedAsCrossingHubOrZoneOfPain() {
        var nodes = new ArrayList<StructuralTopologyAnalyzer.NodeInfo>();
        var edges = new ArrayList<StructuralTopologyAnalyzer.EdgeInfo>();
        // Leaf immutable value package with RECORDs (fanIn=30, fanOut=0)
        nodes.add(node("Money", "com.example.model", "mod.a", "RECORD", false, 4));
        nodes.add(node("CurrencyCode", "com.example.model", "mod.a", "ENUM", false, 6));
        for (int i = 0; i < 30; i++) {
            String name = "Svc" + i;
            nodes.add(node(name, "com.example.service" + (i % 5), "mod.a", "CLASS", false, 8));
            edges.add(edge(name, "Money"));
            edges.add(edge(name, "CurrencyCode"));
        }
        var metrics = StructuralTopologyAnalyzer.analyze(new StructuralTopologyAnalyzer.StructuralGraph(nodes, edges), 10, () -> {});
        assertEquals(0, metrics.crossingHubCount(), "Leaf value object with fanOut=0 must never be flagged as Crossing Hub");
        assertEquals(0, BigDecimal.ZERO.compareTo(metrics.crossingHubDensity().orElseThrow()));
        assertTrue(metrics.mainSequenceWitnesses().stream().noneMatch(w -> w.label().contains("model")),
                "Pure RECORD/ENUM leaf value package must not trigger Zone of Pain");
    }

    @Test void decouplingLevelRanksVerticalSlicesAboveCoupledLayersAboveTangle() {
        // 12 nodes: 4 controllers, 4 services, 4 repos
        var vNodes = new ArrayList<StructuralTopologyAnalyzer.NodeInfo>();
        var vEdges = new ArrayList<StructuralTopologyAnalyzer.EdgeInfo>();
        var hEdges = new ArrayList<StructuralTopologyAnalyzer.EdgeInfo>();
        for (int i = 0; i < 4; i++) {
            vNodes.add(node("C" + i, "com.app.web", "m", "CLASS", false, 4));
            vNodes.add(node("S" + i, "com.app.service", "m", "CLASS", false, 4));
            vNodes.add(node("R" + i, "com.app.repository", "m", "CLASS", false, 4));
            vEdges.add(edge("C" + i, "S" + i));
            vEdges.add(edge("S" + i, "R" + i));
            for (int j = 0; j < 4; j++) {
                hEdges.add(edge("C" + i, "S" + j));
                hEdges.add(edge("S" + i, "R" + j));
            }
        }
        var vertical = StructuralTopologyAnalyzer.analyze(new StructuralTopologyAnalyzer.StructuralGraph(vNodes, vEdges), 5, () -> {});
        var horizontal = StructuralTopologyAnalyzer.analyze(new StructuralTopologyAnalyzer.StructuralGraph(vNodes, hEdges), 5, () -> {});
        assertTrue(vertical.decouplingLevel().orElseThrow().compareTo(horizontal.decouplingLevel().orElseThrow()) > 0,
                "Independent vertical slices (" + vertical.decouplingLevel() + ") must beat dense horizontal layers (" + horizontal.decouplingLevel() + ")");
    }

    @Test void inferredLayerClassifierDetectsHexagonalLeaksAndCleanSkips() {
        var typePkgs = new TreeMap<String, String>();
        typePkgs.put("t.web", "com.acme.web");
        typePkgs.put("t.app", "com.acme.application");
        typePkgs.put("t.dom", "com.acme.domain");
        typePkgs.put("t.port", "com.acme.port");
        typePkgs.put("t.adp", "com.acme.adapter.persistence");

        var edges = List.of(
                new InferredLayerClassifier.DirectedTypeEdge("e1", "t.web", "Api", "t.app", "UseCase", List.of()),
                new InferredLayerClassifier.DirectedTypeEdge("e2", "t.app", "UseCase", "t.dom", "Order", List.of()),
                new InferredLayerClassifier.DirectedTypeEdge("e3", "t.dom", "Order", "t.adp", "JpaRepo", List.of()), // upward leak (1.0)
                new InferredLayerClassifier.DirectedTypeEdge("e4", "t.port", "OrderPort", "t.adp", "JpaRepo", List.of()) // port->adapter leak (1.0)
        );
        var assessment = InferredLayerClassifier.evaluate(typePkgs, Map.of(), edges, 10);
        assertEquals("Hexagonal Architecture", assessment.layerMap().detectedStyle());
        assertEquals(4, assessment.crossLayerEdges());
        assertEquals(2, assessment.upwardLeakCount());
        assertEquals(0, new BigDecimal("0.5").compareTo(assessment.violationRatio().orElseThrow()));
    }

    @Test void modelAndCatalogAreCanonicalJsonSerializableAndDeterministic() {
        var model = ArchitectureScoreModelV2.standard();
        assertEquals(7, model.dimensions().size());
        assertEquals(model.identity(), ArchitectureScoreModelV2.standard().identity());
        assertFalse(CanonicalJson.write(model).isEmpty());
        assertEquals(25, ArchitectureMetricsV2Catalog.entries().size());
        assertEquals(ArchitectureMetricsV2Catalog.identity(), ArchitectureMetricsV2Catalog.identity());
        assertNotNull(ArchitectureMetricsV2Catalog.require(ArchitectureScoreModelV2.SUB_D2_DECOUPLING_LEVEL));
    }

    private static StructuralTopologyAnalyzer.NodeInfo node(
            String id, String pkg, String mod, String kind, boolean abs, int members) {
        return new StructuralTopologyAnalyzer.NodeInfo(
                id, id, "pkg:" + pkg, pkg, "mod:" + mod, mod, kind, abs, members, Set.of(), List.of());
    }

    private static StructuralTopologyAnalyzer.EdgeInfo edge(String from, String to) {
        return new StructuralTopologyAnalyzer.EdgeInfo(from + "->" + to, from, to, List.of());
    }
}
