package com.evolution.analysis.query.trace;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.trace.VerticalTraceEngine.TraceHop;
import com.evolution.analysis.query.trace.VerticalTraceEngine.TraceRequest;
import com.evolution.analysis.query.trace.VerticalTraceEngine.VerticalLayer;
import com.evolution.analysis.query.trace.VerticalTraceEngine.VerticalTraceResult;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Moonshot4VerticalTraceTest {

    @Test
    void fullStackVerticalTracePreservesLayersSpringMayWitnessGapsAndZeroGraphMutation() {
        var fixture = buildFullStackFixture();
        KnowledgeGraph graph = fixture.graph;

        ContentDigest identityBefore = graph.identity();
        ContentDigest semanticBefore = graph.semanticIdentity();

        // Seed by Spring Controller simple name inside the Java analysis context
        TraceRequest req = TraceRequest.defaults(fixture.javaContext, "OrderController");
        VerticalTraceResult run1 = VerticalTraceEngine.trace(graph, req);
        VerticalTraceResult run2 = VerticalTraceEngine.trace(graph, req);

        // 1. Zero canonical KnowledgeGraph mutation
        assertEquals(identityBefore, graph.identity());
        assertEquals(semanticBefore, graph.semanticIdentity());

        // 2. Bit-identical deterministic replay & CanonicalJson compatibility
        assertEquals(run1, run2);
        String json1 = CanonicalJson.write(run1.toView());
        String json2 = CanonicalJson.write(run2.toView());
        assertEquals(json1, json2);

        // 3. All 6 canonical layers traversed in exact swimlane order
        assertFalse(run1.truncated());
        assertEquals(0, run1.advisoryHopCount());
        assertEquals(run1.totalHops(), run1.canonicalHopCount());
        assertEquals(
                List.of("FRONTEND", "API_ROUTE", "SPRING_CONTROLLER", "SPRING_DI", "SERVICE", "JPA_ENTITY"),
                run1.layers());

        // 4. Verify strict ordering invariant: (layer.ordinal() ASC, index ASC, nodeId ASC)
        for (int i = 0; i < run1.hops().size(); i++) {
            TraceHop hop = run1.hops().get(i);
            assertEquals(i, hop.index());
            if (i > 0) {
                TraceHop prev = run1.hops().get(i - 1);
                assertTrue(prev.layer().ordinal() <= hop.layer().ordinal(),
                        "Layer ordering violated between " + prev.layer() + " and " + hop.layer());
            }
        }

        // 5. Verify per-hop provenance, TrustTier, and Spring MAY + witnessId
        TraceHop feHop = hopByLayer(run1, VerticalLayer.FRONTEND);
        assertEquals(TrustTier.SOURCE_SYNTAX, feHop.trustTier());
        assertEquals(Optional.of("web/src/OrderPage.tsx"), feHop.sourcePath());
        assertEquals(OptionalInt.of(14), feHop.startLine());
        assertEquals(OptionalInt.of(22), feHop.endLine());
        assertTrue(feHop.contentSha256().isPresent());

        TraceHop apiHop = hopByLayer(run1, VerticalLayer.API_ROUTE);
        assertEquals("GET /api/orders", apiHop.label());
        assertEquals(Optional.of("src/main/java/com/acme/web/OrderController.java"), apiHop.sourcePath());

        TraceHop ctrlHop = hopByLayer(run1, VerticalLayer.SPRING_CONTROLLER);
        assertEquals(TrustTier.COMPILER, ctrlHop.trustTier());
        assertEquals("com.acme.web.OrderController", ctrlHop.label());
        assertEquals(OptionalInt.of(10), ctrlHop.startLine());
        assertEquals(OptionalInt.of(35), ctrlHop.endLine());
        assertEquals(Optional.of(fixture.controllerSha), ctrlHop.contentSha256());

        TraceHop diHop = hopByLayer(run1, VerticalLayer.SPRING_DI);
        assertEquals(TrustTier.SPRING_SAT, diHop.trustTier());
        assertEquals(Optional.of("MAY"), diHop.classification());
        assertEquals(Optional.of(fixture.witnessId), diHop.witnessId());

        TraceHop svcHop = hopByLayer(run1, VerticalLayer.SERVICE);
        assertEquals(TrustTier.COMPILER, svcHop.trustTier());
        assertEquals("com.acme.service.OrderService", svcHop.label());
        assertEquals(Optional.of("APT_GENERATED_MAPPER_UNAVAILABLE"), svcHop.gapReason());

        TraceHop jpaHop = hopByLayer(run1, VerticalLayer.JPA_ENTITY);
        assertEquals(TrustTier.COMPILER, jpaHop.trustTier());
        assertEquals("com.acme.entity.OrderEntity", jpaHop.label());

        // 6. Verify I3 closed capability gap reporting
        assertEquals(1, run1.gaps().size());
        Map<String, Object> gap = run1.gaps().getFirst();
        assertEquals(fixture.gapId, gap.get("gapId"));
        assertEquals("java.apt-codegen", gap.get("mechanismCategory"));
        assertEquals("APT_GENERATED_MAPPER_UNAVAILABLE", gap.get("reasonCode"));
        @SuppressWarnings("unchecked")
        List<String> affected = (List<String>) gap.get("affectedNodeIds");
        assertTrue(affected.contains(fixture.serviceId));

        // 7. Seed resolution by route path, source path, and exact node identity produce the exact same full trace
        VerticalTraceResult byRoute = VerticalTraceEngine.trace(
                graph, TraceRequest.defaults(fixture.javaContext, "/api/orders"));
        VerticalTraceResult bySourcePath = VerticalTraceEngine.trace(
                graph, TraceRequest.defaults(fixture.snapshotContext, "web/src/OrderPage.tsx"));
        VerticalTraceResult byNodeId = VerticalTraceEngine.trace(
                graph, TraceRequest.defaults(fixture.javaContext, fixture.entityId));

        assertEquals(run1.layers(), byRoute.layers());
        assertEquals(run1.layers(), bySourcePath.layers());
        assertEquals(run1.layers(), byNodeId.layers());
        assertEquals(run1.totalHops(), byRoute.totalHops());

        // 8. World filter: active world promotes MAY -> MUST; inactive world suppresses conditional Spring DI branch
        VerticalTraceResult activeWorld = VerticalTraceEngine.trace(
                graph, new TraceRequest(fixture.javaContext, "OrderController", Optional.empty(),
                        Optional.of(fixture.worldEnabledId), 8, 60));
        assertEquals(Optional.of("MUST"), hopByLayer(activeWorld, VerticalLayer.SPRING_DI).classification());

        VerticalTraceResult disabledWorld = VerticalTraceEngine.trace(
                graph, new TraceRequest(fixture.javaContext, "OrderController", Optional.empty(),
                        Optional.of(fixture.worldDisabledId), 8, 60));
        assertTrue(disabledWorld.hops().stream().noneMatch(h -> h.nodeId().equals(fixture.serviceId)),
                "Conditional DI branch evaluating to NEVER in disabled world must not traverse into OrderService");

        // 9. Bounded maxHops truncation is honest
        VerticalTraceResult capped = VerticalTraceEngine.trace(
                graph, new TraceRequest(fixture.javaContext, "OrderController", Optional.empty(), Optional.empty(), 8, 3));
        assertTrue(capped.truncated());
        assertEquals(3, capped.totalHops());
    }

    @Test
    void methodSliceResultViewAndDirectionParsingAreCanonicalAndStrict() {
        assertEquals(MethodSliceResult.SliceDirection.BACKWARD, MethodSliceResult.SliceDirection.parse("backward"));
        assertEquals(MethodSliceResult.SliceDirection.FORWARD, MethodSliceResult.SliceDirection.parse("FORWARD"));
        assertEquals(MethodSliceResult.SliceDirection.BOTH, MethodSliceResult.SliceDirection.parse("both"));
        assertEquals(MethodSliceResult.SliceDirection.BACKWARD, MethodSliceResult.SliceDirection.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> MethodSliceResult.SliceDirection.parse("sideways"));

        var stmt = new MethodSliceResult.SliceStatement(12, 12, "RETURN", "return total;", List.of(10, 5, 10), "SEED");
        assertEquals(List.of(5, 10), stmt.dependsOnLines());
        var slice = new MethodSliceResult(
                "src/OrderService.java",
                "computeTotal(int)",
                12,
                MethodSliceResult.SliceDirection.BACKWARD,
                TrustTier.SOURCE_SYNTAX,
                false,
                8,
                List.of(stmt),
                List.of(12, 5, 12),
                List.of("Intra-procedural passive AST slice"));
        assertEquals(List.of(5, 12), slice.sliceLines());
        String json = CanonicalJson.write(slice.toView());
        assertTrue(json.contains("\"trustTier\":\"SOURCE_SYNTAX\""));
        assertTrue(json.contains("\"direction\":\"BACKWARD\""));
    }

    private static TraceHop hopByLayer(VerticalTraceResult result, VerticalLayer layer) {
        return result.hops().stream()
                .filter(h -> h.layer() == layer)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing hop in layer " + layer + "; got " + result.hops()));
    }

    private record FullStackFixture(
            KnowledgeGraph graph,
            String snapshotContext,
            String javaContext,
            String springContext,
            String serviceId,
            String entityId,
            String witnessId,
            String gapId,
            String controllerSha,
            String worldEnabledId,
            String worldDisabledId
    ) {}

    private static FullStackFixture buildFullStackFixture() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/m4-trace-repo");
        var snapshot = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("m4-trace-snapshot"));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("m4-trace-java-analysis");
        var moduleId = ModuleIdentity.from(repo, "app");
        var moduleScope = EntityScope.project(moduleId);

        String snapCtx = snapshot.value();
        String javaCtx = analysis.value();
        String springCtx = KnowledgeGraph.derivedIdentity("spring-context", List.of(javaCtx, "m4-spring"));

        var evidence = new ArrayList<GraphEvidence>();
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();

        GraphEvidence snapCtxEv = addEv(evidence, "repository.inventory-snapshot", Map.of("snapshot", snapCtx));
        GraphEvidence javaCtxEv = addEv(evidence, "analysis.manifest", Map.of("analysis", javaCtx));
        GraphEvidence springCtxEv = addEv(evidence, "spring.context", Map.of("analysis", javaCtx));

        var contexts = List.of(
                new KnowledgeGraph.Context(snapCtx, snapshot, Optional.empty(), snapCtxEv.identity()),
                new KnowledgeGraph.Context(javaCtx, snapshot, Optional.of(analysis), javaCtxEv.identity()),
                new KnowledgeGraph.Context(springCtx, snapshot, Optional.of(analysis), springCtxEv.identity())
        );

        // Source documents
        String ctrlSha = ContentDigest.sha256Utf8("OrderController.java bytes").value();
        String svcSha = ContentDigest.sha256Utf8("OrderService.java bytes").value();
        String entSha = ContentDigest.sha256Utf8("OrderEntity.java bytes").value();
        String feSha = ContentDigest.sha256Utf8("OrderPage.tsx bytes").value();

        String ctrlDocId = SourceDocumentIdentity.from(repo, "src/main/java/com/acme/web/OrderController.java").value();
        String svcDocId = SourceDocumentIdentity.from(repo, "src/main/java/com/acme/service/OrderService.java").value();
        String entDocId = SourceDocumentIdentity.from(repo, "src/main/java/com/acme/entity/OrderEntity.java").value();

        GraphEvidence ctrlDocEv = addEv(evidence, "source.document",
                Map.of("path", "src/main/java/com/acme/web/OrderController.java", "contentDigest", ctrlSha, "module", moduleId.value()));
        GraphEvidence svcDocEv = addEv(evidence, "source.document",
                Map.of("path", "src/main/java/com/acme/service/OrderService.java", "contentDigest", svcSha, "module", moduleId.value()));
        GraphEvidence entDocEv = addEv(evidence, "source.document",
                Map.of("path", "src/main/java/com/acme/entity/OrderEntity.java", "contentDigest", entSha, "module", moduleId.value()));

        nodes.add(new KnowledgeGraph.Node(ctrlDocId, javaCtx, "SOURCE_DOCUMENT", List.of(ctrlDocEv.identity())));
        nodes.add(new KnowledgeGraph.Node(svcDocId, javaCtx, "SOURCE_DOCUMENT", List.of(svcDocEv.identity())));
        nodes.add(new KnowledgeGraph.Node(entDocId, javaCtx, "SOURCE_DOCUMENT", List.of(entDocEv.identity())));

        // 1. FRONTEND node in snapshot context (polyglot.source-syntax)
        String feNodeId = KnowledgeGraph.derivedIdentity("polyglot-symbol", "OrderPage#fetchOrders");
        GraphEvidence feEv = addEv(evidence, "polyglot.source-syntax", Map.of(
                "path", "web/src/OrderPage.tsx",
                "language", "tsx",
                "contentDigest", feSha,
                "routePath", "/api/orders",
                "fact", Map.of(
                        "name", "OrderPage.fetchOrders",
                        "span", Map.of("startLine", 14, "endLine", 22))));
        nodes.add(new KnowledgeGraph.Node(feNodeId, snapCtx, "FRONTEND_COMPONENT", List.of(feEv.identity())));

        // 2. API_ROUTE node in java context
        String endpointId = KnowledgeGraph.derivedIdentity("endpoint-declaration", "GET /api/orders");
        GraphEvidence epEv = addEv(evidence, "spring.endpoint-declaration", Map.of(
                "route", Map.of(
                        "path", "/api/orders",
                        "methods", List.of("GET"),
                        "span", Map.of("document", ctrlDocId, "startLine", 18, "endLine", 24)),
                "module", moduleId.value(),
                "sourceSet", "main"));
        nodes.add(new KnowledgeGraph.Node(endpointId, javaCtx, "ENDPOINT", List.of(epEv.identity())));

        // 3. SPRING_CONTROLLER node in java + spring contexts
        String ctrlCanonical = JavaSymbolName.topLevelType("com.acme.web", "OrderController").canonicalName();
        String ctrlId = EntityIdentity.from(EntityOrigin.PROJECT, moduleScope, EntityKind.TYPE, ctrlCanonical).value();
        GraphEvidence ctrlEv = addEv(evidence, "java.declaration", Map.of(
                "entity", Map.of(
                        "canonicalName", ctrlCanonical,
                        "origin", "PROJECT",
                        "stableScope", moduleId.value(),
                        "declaration", Map.of("document", ctrlDocId, "startLine", 10, "endLine", 35)),
                "annotations", List.of("org.springframework.web.bind.annotation.RestController"),
                "spelling", "OrderController"));
        nodes.add(new KnowledgeGraph.Node(ctrlId, javaCtx, "TYPE", List.of(ctrlEv.identity())));
        nodes.add(new KnowledgeGraph.Node(ctrlId, springCtx, "BEAN_PRODUCER", List.of(ctrlEv.identity())));

        GraphEvidence containsEv = addEv(evidence, "spring.endpoint-declaration", Map.of("owner", ctrlId));
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("graph-relation", List.of("contains", ctrlId, endpointId)),
                javaCtx, "graph.contains", ctrlId,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(endpointId), Optional.empty()),
                List.of(containsEv.identity()), Optional.empty()));

        // 4. SPRING_DI node + TRUTH_REGION (MAY) + CONFIGURATION_WITNESS in spring context
        String diId = KnowledgeGraph.derivedIdentity("bean-candidate", "orderServiceBean");
        String regionId = KnowledgeGraph.derivedIdentity("truth-region", "may-order-service");
        String witnessId = KnowledgeGraph.derivedIdentity("witness", "profile-prod-witness");
        String worldEnabled = KnowledgeGraph.derivedIdentity("world", "profile-prod");
        String worldDisabled = KnowledgeGraph.derivedIdentity("world", "profile-test");

        GraphEvidence diEv = addEv(evidence, "spring.candidate", Map.of(
                "declaredNameKey", Map.of("primary", "orderServiceBean"),
                "span", Map.of("document", svcDocId, "startLine", 8, "endLine", 12)));
        GraphEvidence worldEnEv = addEv(evidence, "spring.world", Map.of("profile", "prod"));
        GraphEvidence worldDisEv = addEv(evidence, "spring.world", Map.of("profile", "test"));
        GraphEvidence regionEv = addEv(evidence, "spring.explicit-region", Map.of(
                "encoding", "explicit-worlds-m4d-v1",
                "region", Map.of(
                        "classification", "MAY",
                        "trueWorlds", List.of(worldEnabled),
                        "falseWorlds", List.of(worldDisabled),
                        "unknownWorlds", List.of())));
        GraphEvidence witnessEv = addEv(evidence, "spring.witness", Map.of(
                "factRegionIdentity", regionId,
                "world", worldEnabled));

        nodes.add(new KnowledgeGraph.Node(diId, springCtx, "BEAN_DEFINITION_CANDIDATE", List.of(diEv.identity())));
        nodes.add(new KnowledgeGraph.Node(worldEnabled, springCtx, "CONFIGURATION_WORLD", List.of(worldEnEv.identity())));
        nodes.add(new KnowledgeGraph.Node(worldDisabled, springCtx, "CONFIGURATION_WORLD", List.of(worldDisEv.identity())));
        nodes.add(new KnowledgeGraph.Node(regionId, springCtx, "TRUTH_REGION", List.of(regionEv.identity())));
        nodes.add(new KnowledgeGraph.Node(witnessId, springCtx, "CONFIGURATION_WITNESS", List.of(witnessEv.identity())));

        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("graph-relation", List.of("has-witness", regionId, witnessId)),
                springCtx, "spring.has-witness", regionId,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(witnessId), Optional.empty()),
                List.of(witnessEv.identity()), Optional.empty()));

        // 5. SERVICE node in java + spring contexts
        String svcCanonical = JavaSymbolName.topLevelType("com.acme.service", "OrderService").canonicalName();
        String svcId = EntityIdentity.from(EntityOrigin.PROJECT, moduleScope, EntityKind.TYPE, svcCanonical).value();
        GraphEvidence svcEv = addEv(evidence, "java.declaration", Map.of(
                "entity", Map.of(
                        "canonicalName", svcCanonical,
                        "origin", "PROJECT",
                        "stableScope", moduleId.value(),
                        "declaration", Map.of("document", svcDocId, "startLine", 8, "endLine", 60)),
                "annotations", List.of("org.springframework.stereotype.Service"),
                "spelling", "OrderService"));
        nodes.add(new KnowledgeGraph.Node(svcId, javaCtx, "TYPE", List.of(svcEv.identity())));
        nodes.add(new KnowledgeGraph.Node(svcId, springCtx, "BEAN_PRODUCER", List.of(svcEv.identity())));

        // Wire OrderController -> orderServiceBean (SPRING_DI, MAY + witness) -> OrderService
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("conditional-edge", List.of(ctrlId, diId, regionId)),
                springCtx, "spring.selected-binding", ctrlId,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(diId), Optional.empty()),
                List.of(regionEv.identity()), Optional.of(regionId)));
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("conditional-edge", List.of(diId, svcId, regionId)),
                springCtx, "spring.produces-candidate", diId,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(svcId), Optional.empty()),
                List.of(regionEv.identity()), Optional.of(regionId)));

        // 6. JPA_ENTITY node in java context wired from OrderService
        String entCanonical = JavaSymbolName.topLevelType("com.acme.entity", "OrderEntity").canonicalName();
        String entId = EntityIdentity.from(EntityOrigin.PROJECT, moduleScope, EntityKind.TYPE, entCanonical).value();
        GraphEvidence entEv = addEv(evidence, "java.declaration", Map.of(
                "entity", Map.of(
                        "canonicalName", entCanonical,
                        "origin", "PROJECT",
                        "stableScope", moduleId.value(),
                        "declaration", Map.of("document", entDocId, "startLine", 5, "endLine", 42)),
                "annotations", List.of("jakarta.persistence.Entity", "jakarta.persistence.Table"),
                "spelling", "OrderEntity"));
        nodes.add(new KnowledgeGraph.Node(entId, javaCtx, "TYPE", List.of(entEv.identity())));

        GraphEvidence depEv = addEv(evidence, "java.occurrence", Map.of("status", "RESOLVED"));
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("graph-relation", List.of("depends", svcId, entId)),
                javaCtx, "graph.depends-on", svcId,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(entId), Optional.empty()),
                List.of(depEv.identity()), Optional.empty()));

        // 7. CAPABILITY_GAP touching OrderService (APT MapStruct MapperImpl unavailable without isolated worker)
        String gapId = KnowledgeGraph.derivedIdentity("gap", "apt-mapstruct-order-mapper");
        GraphEvidence gapEv = addEv(evidence, "evidence.gap", Map.of(
                "mechanismCategory", "java.apt-codegen",
                "reasonCode", "APT_GENERATED_MAPPER_UNAVAILABLE",
                "subject", Map.of("identity", svcId),
                "sourceSpans", List.of(Map.of("document", svcDocId, "startLine", 25, "endLine", 25)),
                "limitations", List.of("MapStruct OrderMapperImpl requires isolated worker javac/APT capture.")));
        nodes.add(new KnowledgeGraph.Node(gapId, javaCtx, "CAPABILITY_GAP", List.of(gapEv.identity())));

        var graph = new KnowledgeGraph(snapshot, contexts, nodes, edges, evidence);
        return new FullStackFixture(
                graph, snapCtx, javaCtx, springCtx, svcId, entId, witnessId, gapId,
                ctrlSha, worldEnabled, worldDisabled);
    }

    private static GraphEvidence addEv(List<GraphEvidence> list, String family, Object payload) {
        GraphEvidence ev = GraphEvidence.capture(family, payload);
        list.add(ev);
        return ev;
    }
}
