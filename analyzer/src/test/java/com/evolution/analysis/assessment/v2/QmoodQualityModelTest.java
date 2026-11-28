package com.evolution.analysis.assessment.v2;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit & contract tests for WP4.2 QMOOD 11-property model and Non-Regression Guard. */
class QmoodQualityModelTest {

    @Test
    void computesDeterministic11PropertiesAnd6AttributesAndHandlesEmptyInput() {
        var empty = QmoodQualityModel.compute(List.of(), 0, 100.0);
        assertEquals(0.0, empty.properties().dsc());
        assertEquals(0.0, empty.attributes().understandability());

        var classes = List.of(
                new QmoodQualityModel.ClassMetricProfile("com.acme.OrderService", 0, 4, 4, 2, 0.75, 2, 0, 6, 0, 3, 6, true),
                new QmoodQualityModel.ClassMetricProfile("com.acme.BillingHelper", 1, 2, 2, 1, 0.85, 1, 2, 6, 1, 2, 4, false));
        var snap1 = QmoodQualityModel.compute(classes, 0, 88.5);
        var snap2 = QmoodQualityModel.compute(List.of(classes.get(1), classes.get(0)), 0, 88.5);

        assertEquals(snap1.digest(), snap2.digest(), "I2: QMOOD snapshot must be order-independent & bit-identical");
        assertEquals(2.0, snap1.properties().dsc());
        assertEquals(1.0, snap1.properties().noh());
        assertEquals(1.0, snap1.properties().dam());
        assertEquals(11, snap1.properties().toView().size());
        assertEquals(6, snap1.attributes().toView().size());
    }

    @Test
    void blocksRefAgentStyleUnderstandabilityRegressionAndSccCycleIncrease() {
        var before = QmoodQualityModel.compute(List.of(
                new QmoodQualityModel.ClassMetricProfile("com.acme.Core", 0, 3, 3, 2, 0.80, 1, 0, 5, 0, 3, 5, false)),
                1, 82.0);
        // Simulate RefAgent over-fragmentation exploding class count, coupling & method count (-14%+ Understandability drop)
        var degradedAfter = QmoodQualityModel.compute(List.of(
                new QmoodQualityModel.ClassMetricProfile("com.acme.Core", 2, 1, 4, 7, 0.25, 4, 2, 14, 4, 6, 12, false),
                new QmoodQualityModel.ClassMetricProfile("com.acme.CoreHelper1", 2, 0, 3, 6, 0.20, 3, 2, 10, 3, 5, 8, false),
                new QmoodQualityModel.ClassMetricProfile("com.acme.CoreHelper2", 2, 0, 3, 6, 0.20, 3, 2, 10, 3, 5, 8, false)),
                2, 74.0);

        var guard = QmoodQualityModel.evaluateNonRegression(before, degradedAfter);
        assertFalse(guard.passed());
        assertTrue(guard.understandabilityDeltaPercent() < QmoodQualityModel.DEFAULT_UNDERSTANDABILITY_FLOOR_PERCENT);
        assertTrue(guard.violations().stream().anyMatch(v -> v.startsWith("QMOOD_UNDERSTANDABILITY_REGRESSION")));
        assertTrue(guard.violations().stream().anyMatch(v -> v.startsWith("SCC_CYCLE_REGRESSION")));

        // Clean decomposition improving cohesion & reducing coupling + keeping SCC non-increasing passes
        var cleanAfter = QmoodQualityModel.compute(List.of(
                new QmoodQualityModel.ClassMetricProfile("com.acme.Core", 0, 3, 3, 1, 0.92, 1, 0, 4, 0, 3, 4, false)),
                0, 91.0);
        var okGuard = QmoodQualityModel.evaluateNonRegression(before, cleanAfter);
        assertTrue(okGuard.passed(), () -> "Expected pass, got: " + okGuard.violations());
        assertTrue(okGuard.understandabilityDeltaPercent() >= QmoodQualityModel.DEFAULT_UNDERSTANDABILITY_FLOOR_PERCENT);
    }

    @Test
    void fromGraphContextComputesOrderIndependentDccAndServiceReportsRealTarjanSccCounts() {
        var snap = new com.evolution.analysis.contract.identity.SnapshotIdentity("snapshot:sha256:" + "1".repeat(64));
        String ctxId = com.evolution.analysis.graph.KnowledgeGraph.derivedIdentity("ctx", List.of("qmood-test"));
        String tA = com.evolution.analysis.graph.KnowledgeGraph.derivedIdentity("type", List.of("com.acme.A"));
        String tB = com.evolution.analysis.graph.KnowledgeGraph.derivedIdentity("type", List.of("com.acme.B"));
        String mA = com.evolution.analysis.graph.KnowledgeGraph.derivedIdentity("method", List.of("com.acme.A#m"));
        String mB = com.evolution.analysis.graph.KnowledgeGraph.derivedIdentity("method", List.of("com.acme.B#m"));
        var ctxEv = com.evolution.analysis.graph.GraphEvidence.capture(
                "analysis.manifest", java.util.Map.of("context", ctxId));
        var nodeAEv = com.evolution.analysis.graph.GraphEvidence.capture(
                "java.declaration", java.util.Map.of("entity", java.util.Map.of("qualifiedName", "com.acme.A")));
        var nodeBEv = com.evolution.analysis.graph.GraphEvidence.capture(
                "java.declaration", java.util.Map.of("entity", java.util.Map.of("qualifiedName", "com.acme.B")));
        var ctx = new com.evolution.analysis.graph.KnowledgeGraph.Context(ctxId, snap, java.util.Optional.empty(), ctxEv.identity());
        var nA = new com.evolution.analysis.graph.KnowledgeGraph.Node(tA, ctxId, "TYPE", List.of(nodeAEv.identity()));
        var nB = new com.evolution.analysis.graph.KnowledgeGraph.Node(tB, ctxId, "TYPE", List.of(nodeBEv.identity()));
        var mANode = new com.evolution.analysis.graph.KnowledgeGraph.Node(mA, ctxId, "METHOD", List.of(nodeAEv.identity()));
        var mBNode = new com.evolution.analysis.graph.KnowledgeGraph.Node(mB, ctxId, "METHOD", List.of(nodeBEv.identity()));
        var callEdge = new com.evolution.analysis.graph.KnowledgeGraph.Edge(
                com.evolution.analysis.graph.KnowledgeGraph.derivedIdentity("edge", List.of("call", mA, mB)),
                ctxId, "java.calls", mA,
                new com.evolution.analysis.graph.KnowledgeGraph.Target(
                        com.evolution.analysis.graph.KnowledgeGraph.TargetState.RESOLVED, List.of(mB), java.util.Optional.empty()),
                List.of(nodeAEv.identity()), java.util.Optional.empty());
        var declA = new com.evolution.analysis.graph.KnowledgeGraph.Edge(
                com.evolution.analysis.graph.KnowledgeGraph.derivedIdentity("edge", List.of("decl", tA, mA)),
                ctxId, "graph.declares", tA,
                new com.evolution.analysis.graph.KnowledgeGraph.Target(
                        com.evolution.analysis.graph.KnowledgeGraph.TargetState.RESOLVED, List.of(mA), java.util.Optional.empty()),
                List.of(nodeAEv.identity()), java.util.Optional.empty());
        var declB = new com.evolution.analysis.graph.KnowledgeGraph.Edge(
                com.evolution.analysis.graph.KnowledgeGraph.derivedIdentity("edge", List.of("decl", tB, mB)),
                ctxId, "graph.declares", tB,
                new com.evolution.analysis.graph.KnowledgeGraph.Target(
                        com.evolution.analysis.graph.KnowledgeGraph.TargetState.RESOLVED, List.of(mB), java.util.Optional.empty()),
                List.of(nodeBEv.identity()), java.util.Optional.empty());
        // Put java.calls BEFORE graph.declares to verify two-pass coupling calculation
        var g1 = new com.evolution.analysis.graph.KnowledgeGraph(
                snap, List.of(ctx), List.of(nA, nB, mANode, mBNode), List.of(callEdge, declA, declB), List.of(ctxEv, nodeAEv, nodeBEv));
        var g2 = new com.evolution.analysis.graph.KnowledgeGraph(
                snap, List.of(ctx), List.of(nA, nB, mANode, mBNode), List.of(declA, declB, callEdge), List.of(ctxEv, nodeAEv, nodeBEv));

        var q1 = QmoodQualityModel.fromGraphContext(g1, ctxId, 0, 90.0);
        var q2 = QmoodQualityModel.fromGraphContext(g2, ctxId, 0, 90.0);
        assertEquals(0.5, q1.properties().dcc(), "Type tA couples to tB across member call mA->mB (mean DCC=1/2=0.5)");
        assertEquals(q1.digest(), q2.digest(), "I2: QMOOD DCC must be independent of edge ordering");

        // Verify ArchitectureAssessmentV2Service.assessQmood & buildMetricsOnlyBrief report REAL Tarjan SCC counts > 0 on a cyclic graph
        var cyclicFx = new ArchitectureAssessmentV2ServiceTest.GraphBuilder();
        cyclicFx.type("com.acme.a", "Alpha", "CLASS", false, 4);
        cyclicFx.type("com.acme.b", "Beta", "CLASS", false, 4);
        cyclicFx.edge("Alpha", "Beta");
        cyclicFx.edge("Beta", "Alpha");
        var queries = new com.evolution.analysis.query.ArchitectureQueryService(cyclicFx.graph());
        var svc = new ArchitectureAssessmentV2Service();
        var qmoodCyclic = svc.assessQmood(queries, cyclicFx.context);
        var briefCyclic = svc.buildMetricsOnlyBrief(queries, cyclicFx.context);
        assertTrue(qmoodCyclic.sccCycleCount() >= 2,
                "Cyclic Alpha<->Beta across 2 packages must report real Tarjan package+type SCC cycles >= 2, got " + qmoodCyclic.sccCycleCount());
        assertEquals(qmoodCyclic.sccCycleCount(), briefCyclic.sccCycleCount());
        assertTrue(briefCyclic.meanCoupling() > 0.0, "Mean coupling must be positive on cyclic graph");
    }
}
