package com.evolution.backend.query.repair;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpringPreservationCheckerTest {

    private static final SnapshotIdentity SNAP_BASE =
            new SnapshotIdentity("snapshot:sha256:1000000000000000000000000000000000000000000000000000000000000001");
    private static final SnapshotIdentity SNAP_CAND =
            new SnapshotIdentity("snapshot:sha256:1000000000000000000000000000000000000000000000000000000000000002");

    @Test
    void detectsBeanScopeRegressionAndPreservesDistinctQualifiedSameTypeBeans() {
        var baseline = buildScopeAndAopGraph(
                SNAP_BASE, "prototype", List.of("fast"), "singleton", List.of("audit"),
                true, 10, true);
        var preserved = buildScopeAndAopGraph(
                SNAP_CAND, "prototype", List.of("fast"), "singleton", List.of("audit"),
                true, 10, true);

        var okReport = SpringPreservationChecker.compare(baseline, Optional.of(preserved));
        assertEquals(SpringPreservationChecker.Status.PRESERVED, okReport.status(), () -> okReport.drifts().toString());
        assertTrue(okReport.drifts().isEmpty());

        // Regression 1: prototype -> singleton on beanA, plus dropped qualifier on beanB
        var scopeDrifted = buildScopeAndAopGraph(
                SNAP_CAND, "singleton", List.of("fast"), "singleton", List.of(),
                true, 10, true);
        var driftReport = SpringPreservationChecker.compare(baseline, Optional.of(scopeDrifted));
        assertEquals(SpringPreservationChecker.Status.DRIFT_DETECTED, driftReport.status());
        assertTrue(driftReport.drifts().stream().anyMatch(d ->
                "BEAN_SCOPE".equals(d.factKind())
                        && "SCOPE:prototype".equals(d.beforeClassification())
                        && "SCOPE:singleton".equals(d.afterClassification())
                        && "HIGH".equals(d.severity())),
                () -> "Expected prototype->singleton HIGH drift: " + driftReport.drifts());
        assertTrue(driftReport.drifts().stream().anyMatch(d ->
                "BEAN_QUALIFIER".equals(d.factKind()) && "HIGH".equals(d.severity())),
                () -> "Expected dropped @Qualifier drift: " + driftReport.drifts());
    }

    @Test
    void detectsDroppedAopAdviceDroppedProxyAndChangedAdviceOrder() {
        var baseline = buildScopeAndAopGraph(
                SNAP_BASE, "prototype", List.of("fast"), "singleton", List.of("audit"),
                true, 10, true);

        // Regression: drop ADVISES_METHOD and PROXIED_BY_ASPECT
        var droppedAop = buildScopeAndAopGraph(
                SNAP_CAND, "prototype", List.of("fast"), "singleton", List.of("audit"),
                false, 10, false);
        var droppedReport = SpringPreservationChecker.compare(baseline, Optional.of(droppedAop));
        assertEquals(SpringPreservationChecker.Status.DRIFT_DETECTED, droppedReport.status());
        assertTrue(droppedReport.drifts().stream().anyMatch(d ->
                "AOP_ADVICE".equals(d.factKind()) && "REMOVED".equals(d.afterClassification())));
        assertTrue(droppedReport.drifts().stream().anyMatch(d ->
                "AOP_PROXY".equals(d.factKind()) && "REMOVED".equals(d.afterClassification())));

        // Regression: @Order precedence changed from 10 to 50
        var reorderedAop = buildScopeAndAopGraph(
                SNAP_CAND, "prototype", List.of("fast"), "singleton", List.of("audit"),
                true, 50, true);
        var reorderReport = SpringPreservationChecker.compare(baseline, Optional.of(reorderedAop));
        assertEquals(SpringPreservationChecker.Status.DRIFT_DETECTED, reorderReport.status());
        assertTrue(reorderReport.drifts().stream().anyMatch(d ->
                "AOP_ADVICE_ORDER".equals(d.factKind())
                        && "ORDER:10".equals(d.beforeClassification())
                        && "ORDER:50".equals(d.afterClassification())));
        assertEquals(reorderReport.preservationDigest(),
                SpringPreservationChecker.compare(baseline, Optional.of(reorderedAop)).preservationDigest(),
                "I2: preservation digest must be deterministic");
    }

    private static KnowledgeGraph buildScopeAndAopGraph(
            SnapshotIdentity snapshot,
            String beanAScope,
            List<String> beanAQualifiers,
            String beanBScope,
            List<String> beanBQualifiers,
            boolean includeAdvice,
            int adviceOrder,
            boolean includeProxy) {
        String ctxId = "spring:" + ContentDigest.sha256Utf8("ctx-main").value();
        var evList = new ArrayList<GraphEvidence>();
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();

        var ctxEv = GraphEvidence.capture("spring.context", Map.of("module", "app", "sourceSet", "main"));
        evList.add(ctxEv);

        // Two beans of the SAME class com.acme.Worker with different beanIds and qualifiers
        String beanAId = "bean:" + ContentDigest.sha256Utf8("beanA").value();
        var beanAEv = GraphEvidence.capture("spring.xml-bean", Map.of(
                "beanId", "fastWorker", "className", "com.acme.Worker",
                "scope", beanAScope, "qualifiers", beanAQualifiers));
        evList.add(beanAEv);
        nodes.add(new KnowledgeGraph.Node(beanAId, ctxId, "SPRING_BEAN", List.of(beanAEv.identity())));

        String beanBId = "bean:" + ContentDigest.sha256Utf8("beanB").value();
        var beanBEv = GraphEvidence.capture("spring.xml-bean", Map.of(
                "beanId", "auditWorker", "className", "com.acme.Worker",
                "scope", beanBScope, "qualifiers", beanBQualifiers));
        evList.add(beanBEv);
        nodes.add(new KnowledgeGraph.Node(beanBId, ctxId, "SPRING_BEAN", List.of(beanBEv.identity())));

        String aspectId = "type:" + ContentDigest.sha256Utf8("com.acme.AuditAspect").value();
        var aspectEv = GraphEvidence.capture("java.declaration", Map.of(
                "entity", Map.of("canonicalName", "java:v1:[\"type\",\"com.acme\",\"AuditAspect\"]")));
        evList.add(aspectEv);
        nodes.add(new KnowledgeGraph.Node(aspectId, ctxId, "TYPE", List.of(aspectEv.identity())));

        String targetMethodId = "method:" + ContentDigest.sha256Utf8("com.acme.Worker#execute").value();
        var targetEv = GraphEvidence.capture("java.declaration", Map.of(
                "entity", Map.of("canonicalName", "java:v1:[\"method\",[\"type\",\"com.acme\",\"Worker\"],\"execute\",[]]")));
        evList.add(targetEv);
        nodes.add(new KnowledgeGraph.Node(targetMethodId, ctxId, "METHOD", List.of(targetEv.identity())));

        if (includeAdvice) {
            var adviceEv = GraphEvidence.capture("spring.aop-advice", Map.of(
                    "aspectClass", "com.acme.AuditAspect",
                    "adviceKind", "AROUND",
                    "order", adviceOrder,
                    "pointcut", "execution(* com.acme.Worker.execute(..))",
                    "targetClass", "com.acme.Worker",
                    "targetMethod", "execute"));
            evList.add(adviceEv);
            String edgeId = "edge:" + ContentDigest.sha256Utf8("advises-" + adviceOrder).value();
            edges.add(new KnowledgeGraph.Edge(
                    edgeId, ctxId, "ADVISES_METHOD", aspectId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(targetMethodId), Optional.empty()),
                    List.of(adviceEv.identity()), Optional.empty()));
        }

        if (includeProxy) {
            var proxyEv = GraphEvidence.capture("spring.aop-advice", Map.of(
                    "aspectClass", "com.acme.AuditAspect",
                    "targetClass", "com.acme.Worker"));
            evList.add(proxyEv);
            String proxyEdgeId = "edge:" + ContentDigest.sha256Utf8("proxied").value();
            edges.add(new KnowledgeGraph.Edge(
                    proxyEdgeId, ctxId, "PROXIED_BY_ASPECT", beanAId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(aspectId), Optional.empty()),
                    List.of(proxyEv.identity()), Optional.empty()));
        }

        return new KnowledgeGraph(
                snapshot,
                List.of(new KnowledgeGraph.Context(ctxId, snapshot, Optional.empty(), ctxEv.identity())),
                nodes,
                edges,
                evList);
    }
}
