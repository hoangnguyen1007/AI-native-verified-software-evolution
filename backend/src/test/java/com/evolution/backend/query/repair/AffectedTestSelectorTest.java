package com.evolution.backend.query.repair;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AffectedTestSelectorTest {

    private static final SnapshotIdentity SNAPSHOT =
            new SnapshotIdentity("snapshot:sha256:2000000000000000000000000000000000000000000000000000000000000001");

    @Test
    void propagatesImpactAcrossAdvisesMethodAndSharedSingletonButIsolatesPrototypeConsumers() {
        var graph = buildGraph();

        // 1. Editing AuditAspect propagates forward over ADVISES_METHOD -> OrderService -> OrderServiceTest
        //    without selecting unrelated BillingServiceTest!
        var aspectRes = AffectedTestSelector.select(
                graph, null, List.of("src/main/java/com/acme/AuditAspect.java"));
        assertEquals(AffectedTestSelector.SelectionMode.SELECTED_TESTS, aspectRes.mode(), aspectRes.rationale());
        var aspectTests = aspectRes.selectedTests().stream().map(AffectedTestSelector.SelectedTest::testClass).toList();
        assertTrue(aspectTests.contains("OrderServiceTest"), () -> "Expected OrderServiceTest via ADVISES_METHOD: " + aspectTests);
        assertFalse(aspectTests.contains("BillingServiceTest"), () -> "Unrelated test should not be selected: " + aspectTests);

        // 2. Editing ConsumerA (which depends on shared singleton CacheBean also used by ConsumerB)
        //    must propagate impact to BOTH ConsumerATest and ConsumerBTest!
        var singletonRes = AffectedTestSelector.select(
                graph, null, List.of("src/main/java/com/acme/ConsumerA.java"));
        assertEquals(AffectedTestSelector.SelectionMode.SELECTED_TESTS, singletonRes.mode(), singletonRes.rationale());
        var singletonTests = singletonRes.selectedTests().stream().map(AffectedTestSelector.SelectedTest::testClass).toList();
        assertTrue(singletonTests.contains("ConsumerATest"), () -> "tests=" + singletonTests);
        assertTrue(singletonTests.contains("ConsumerBTest"), () -> "Shared singleton bean must propagate to sibling ConsumerBTest: " + singletonTests);
        assertFalse(singletonTests.contains("ProtoBTest"));

        // 3. Editing ProtoA (which depends on a PROTOTYPE bean also used by ProtoB)
        //    must select ProtoATest but NOT cross-contaminate isolated ProtoBTest!
        var protoRes = AffectedTestSelector.select(
                graph, null, List.of("src/main/java/com/acme/ProtoA.java"));
        assertEquals(AffectedTestSelector.SelectionMode.SELECTED_TESTS, protoRes.mode(), protoRes.rationale());
        var protoTests = protoRes.selectedTests().stream().map(AffectedTestSelector.SelectedTest::testClass).toList();
        assertTrue(protoTests.contains("ProtoATest"), () -> "tests=" + protoTests);
        assertFalse(protoTests.contains("ProtoBTest"), () -> "Prototype bean must NOT cross-propagate to sibling consumer: " + protoTests);

        // 4. Editing Spring XML applicationContext.xml fails closed to FULL_SUITE
        var xmlRes = AffectedTestSelector.select(
                graph, null, List.of("src/main/resources/applicationContext.xml"));
        assertEquals(AffectedTestSelector.SelectionMode.FULL_SUITE, xmlRes.mode());
    }

    @Test
    void sharedCrossCuttingAspectDoesNotCrossContaminateUnrelatedAdvisedServices() {
        var graph = buildGraphWithSharedAspectAdvisingTwoServices();

        // Editing the shared aspect MUST select BOTH OrderServiceTest and BillingServiceTest
        var aspectRes = AffectedTestSelector.select(
                graph, null, List.of("src/main/java/com/acme/AuditAspect.java"));
        assertEquals(AffectedTestSelector.SelectionMode.SELECTED_TESTS, aspectRes.mode(), aspectRes.rationale());
        var aspectTests = aspectRes.selectedTests().stream().map(AffectedTestSelector.SelectedTest::testClass).toList();
        assertTrue(aspectTests.contains("OrderServiceTest"), () -> "tests=" + aspectTests);
        assertTrue(aspectTests.contains("BillingServiceTest"), () -> "tests=" + aspectTests);

        // Editing OrderService MUST select OrderServiceTest and MUST NOT cross-contaminate BillingServiceTest via AuditAspect!
        var orderRes = AffectedTestSelector.select(
                graph, null, List.of("src/main/java/com/acme/OrderService.java"));
        assertEquals(AffectedTestSelector.SelectionMode.SELECTED_TESTS, orderRes.mode(), orderRes.rationale());
        var orderTests = orderRes.selectedTests().stream().map(AffectedTestSelector.SelectedTest::testClass).toList();
        assertTrue(orderTests.contains("OrderServiceTest"), () -> "tests=" + orderTests);
        assertFalse(orderTests.contains("BillingServiceTest"),
                () -> "ADVISES_METHOD must be one-way (aspect -> target) and not leak OrderService edit to BillingServiceTest: " + orderTests);
    }

    private static KnowledgeGraph buildGraphWithSharedAspectAdvisingTwoServices() {
        var base = buildGraph();
        String ctxId = base.contexts().stream()
                .map(KnowledgeGraph.Context::identity)
                .filter(id -> !id.equals(SNAPSHOT.value()))
                .findFirst().orElseThrow();
        String aspectNode = "type:" + ContentDigest.sha256Utf8("com.acme.AuditAspect").value();
        String billingNode = "type:" + ContentDigest.sha256Utf8("com.acme.BillingService").value();

        var evList = new ArrayList<>(base.evidence());
        var edges = new ArrayList<>(base.edges());
        var aopEv2 = GraphEvidence.capture("spring.aop-advice", Map.of(
                "aspectClass", "com.acme.AuditAspect",
                "adviceKind", "BEFORE",
                "order", 1,
                "pointcut", "execution(* com.acme.*Service.*(..))",
                "targetClass", "com.acme.BillingService",
                "targetMethod", "bill"));
        evList.add(aopEv2);
        edges.add(new KnowledgeGraph.Edge(
                "edge:" + ContentDigest.sha256Utf8("aop-2").value(), ctxId, "ADVISES_METHOD", aspectNode,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(billingNode), Optional.empty()),
                List.of(aopEv2.identity()), Optional.empty()));
        return new KnowledgeGraph(SNAPSHOT, base.contexts(), base.nodes(), edges, evList);
    }

    private static KnowledgeGraph buildGraph() {
        String snapCtxId = SNAPSHOT.value();
        String ctxId = "ctx:" + ContentDigest.sha256Utf8("main-ctx").value();
        var evList = new ArrayList<GraphEvidence>();
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();

        var snapEv = GraphEvidence.capture("source.snapshot", Map.of("snapshot", snapCtxId));
        evList.add(snapEv);
        var ctxEv = GraphEvidence.capture("spring.context", Map.of("module", "app", "sourceSet", "main"));
        evList.add(ctxEv);

        var files = List.of(
                "src/main/java/com/acme/AuditAspect.java",
                "src/main/java/com/acme/OrderService.java",
                "src/main/java/com/acme/BillingService.java",
                "src/main/java/com/acme/ConsumerA.java",
                "src/main/java/com/acme/ConsumerB.java",
                "src/main/java/com/acme/ProtoA.java",
                "src/main/java/com/acme/ProtoB.java",
                "src/test/java/com/acme/OrderServiceTest.java",
                "src/test/java/com/acme/BillingServiceTest.java",
                "src/test/java/com/acme/ConsumerATest.java",
                "src/test/java/com/acme/ConsumerBTest.java",
                "src/test/java/com/acme/ProtoATest.java",
                "src/test/java/com/acme/ProtoBTest.java");
        var docIds = new HashMap<String, String>();
        for (String path : files) {
            String docId = "doc:" + ContentDigest.sha256Utf8(path).value();
            docIds.put(path, docId);
            var docEv = GraphEvidence.capture("source.document", Map.of("path", path, "identity", docId));
            evList.add(docEv);
            nodes.add(new KnowledgeGraph.Node(docId, snapCtxId, "SOURCE_DOCUMENT", List.of(docEv.identity())));
        }

        String aspectNode = addType(ctxId, "com.acme.AuditAspect", docIds.get("src/main/java/com/acme/AuditAspect.java"), evList, nodes);
        String orderNode = addType(ctxId, "com.acme.OrderService", docIds.get("src/main/java/com/acme/OrderService.java"), evList, nodes);
        String billingNode = addType(ctxId, "com.acme.BillingService", docIds.get("src/main/java/com/acme/BillingService.java"), evList, nodes);
        String consA = addType(ctxId, "com.acme.ConsumerA", docIds.get("src/main/java/com/acme/ConsumerA.java"), evList, nodes);
        String consB = addType(ctxId, "com.acme.ConsumerB", docIds.get("src/main/java/com/acme/ConsumerB.java"), evList, nodes);
        String protoA = addType(ctxId, "com.acme.ProtoA", docIds.get("src/main/java/com/acme/ProtoA.java"), evList, nodes);
        String protoB = addType(ctxId, "com.acme.ProtoB", docIds.get("src/main/java/com/acme/ProtoB.java"), evList, nodes);

        String orderTest = addType(ctxId, "com.acme.OrderServiceTest", docIds.get("src/test/java/com/acme/OrderServiceTest.java"), evList, nodes);
        String billingTest = addType(ctxId, "com.acme.BillingServiceTest", docIds.get("src/test/java/com/acme/BillingServiceTest.java"), evList, nodes);
        String consATest = addType(ctxId, "com.acme.ConsumerATest", docIds.get("src/test/java/com/acme/ConsumerATest.java"), evList, nodes);
        String consBTest = addType(ctxId, "com.acme.ConsumerBTest", docIds.get("src/test/java/com/acme/ConsumerBTest.java"), evList, nodes);
        String protoATest = addType(ctxId, "com.acme.ProtoATest", docIds.get("src/test/java/com/acme/ProtoATest.java"), evList, nodes);
        String protoBTest = addType(ctxId, "com.acme.ProtoBTest", docIds.get("src/test/java/com/acme/ProtoBTest.java"), evList, nodes);

        // Shared singleton bean node + prototype bean node
        String sharedSingleton = "bean:" + ContentDigest.sha256Utf8("sharedCache").value();
        var singEv = GraphEvidence.capture("spring.xml-bean", Map.of("beanId", "sharedCache", "className", "com.acme.SharedCache", "scope", "singleton"));
        evList.add(singEv);
        nodes.add(new KnowledgeGraph.Node(sharedSingleton, ctxId, "SPRING_BEAN", List.of(singEv.identity())));

        String protoBean = "bean:" + ContentDigest.sha256Utf8("protoHelper").value();
        var protoEv = GraphEvidence.capture("spring.xml-bean", Map.of("beanId", "protoHelper", "className", "com.acme.ProtoHelper", "scope", "prototype"));
        evList.add(protoEv);
        nodes.add(new KnowledgeGraph.Node(protoBean, ctxId, "SPRING_BEAN", List.of(protoEv.identity())));

        // AOP edge: AuditAspect ADVISES_METHOD OrderService
        var aopEv = GraphEvidence.capture("spring.aop-advice", Map.of(
                "aspectClass", "com.acme.AuditAspect",
                "adviceKind", "BEFORE",
                "order", 1,
                "pointcut", "execution(* com.acme.OrderService.*(..))",
                "targetClass", "com.acme.OrderService",
                "targetMethod", "submit"));
        evList.add(aopEv);
        edges.add(new KnowledgeGraph.Edge(
                "edge:" + ContentDigest.sha256Utf8("aop-1").value(), ctxId, "ADVISES_METHOD", aspectNode,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(orderNode), Optional.empty()),
                List.of(aopEv.identity()), Optional.empty()));

        // Shared singleton dependency edges: ConsumerA -> sharedSingleton, ConsumerB -> sharedSingleton
        addEdge(ctxId, "spring.selected-binding", consA, sharedSingleton, evList, edges);
        addEdge(ctxId, "spring.selected-binding", consB, sharedSingleton, evList, edges);

        // Prototype dependency edges: ProtoA -> protoBean, ProtoB -> protoBean
        addEdge(ctxId, "spring.selected-binding", protoA, protoBean, evList, edges);
        addEdge(ctxId, "spring.selected-binding", protoB, protoBean, evList, edges);

        // Test -> production edges
        addEdge(ctxId, "java.calls", orderTest, orderNode, evList, edges);
        addEdge(ctxId, "java.calls", billingTest, billingNode, evList, edges);
        addEdge(ctxId, "java.calls", consATest, consA, evList, edges);
        addEdge(ctxId, "java.calls", consBTest, consB, evList, edges);
        addEdge(ctxId, "java.calls", protoATest, protoA, evList, edges);
        addEdge(ctxId, "java.calls", protoBTest, protoB, evList, edges);

        return new KnowledgeGraph(
                SNAPSHOT,
                List.of(
                        new KnowledgeGraph.Context(snapCtxId, SNAPSHOT, Optional.empty(), snapEv.identity()),
                        new KnowledgeGraph.Context(ctxId, SNAPSHOT, Optional.empty(), ctxEv.identity())),
                nodes,
                edges,
                evList);
    }

    private static String addType(
            String ctxId, String fqn, String docId,
            List<GraphEvidence> evList, List<KnowledgeGraph.Node> nodes) {
        String id = "type:" + ContentDigest.sha256Utf8(fqn).value();
        int dot = fqn.lastIndexOf('.');
        String pkg = fqn.substring(0, dot);
        String simple = fqn.substring(dot + 1);
        var ev = GraphEvidence.capture("java.declaration", Map.of(
                "entity", Map.of(
                        "canonicalName", "java:v1:[\"type\",\"" + pkg + "\",\"" + simple + "\"]",
                        "declaration", Map.of("document", docId, "startLine", 1, "startColumn", 1, "endLine", 10, "endColumn", 1))));
        evList.add(ev);
        nodes.add(new KnowledgeGraph.Node(id, ctxId, "TYPE", List.of(ev.identity())));
        return id;
    }

    private static void addEdge(
            String ctxId, String kind, String src, String dst,
            List<GraphEvidence> evList, List<KnowledgeGraph.Edge> edges) {
        var ev = GraphEvidence.capture("graph.edge", Map.of("kind", kind, "src", src, "dst", dst));
        evList.add(ev);
        edges.add(new KnowledgeGraph.Edge(
                "edge:" + ContentDigest.sha256Utf8(kind + ":" + src + "->" + dst).value(),
                ctxId, kind, src,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                List.of(ev.identity()), Optional.empty()));
    }
}
