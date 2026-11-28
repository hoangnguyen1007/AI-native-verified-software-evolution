package com.evolution.analysis.query;

import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.analysis.query.ConditionalPathCycleTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PolyglotQueryServiceTest {

    @Test
    void describesPolyglotSyntaxInventoryAndServiceEndpointNodesHumanReadably() {
        var f = new Fixture();

        // 1. polyglot.source-syntax DOCUMENT
        var pyDocEv = GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "rowType", "DOCUMENT",
                "document", id("py-doc"),
                "path", LogicalPath.utf8("billing/service.py").rawBase64(),
                "language", "python")));
        f.evidence.add(pyDocEv);
        f.nodes.add(new KnowledgeGraph.Node(id("py-doc"), f.context, "SOURCE_DOCUMENT", List.of(pyDocEv.identity())));

        // 2. polyglot.source-syntax FACT (Python function declaration)
        var pyFnEv = GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "rowType", "FACT",
                "document", id("py-doc"),
                "path", LogicalPath.utf8("billing/service.py").rawBase64(),
                "language", "python",
                "fact", Map.of("category", "DECLARATION", "nodeKind", "function_definition",
                        "name", "create_invoice", "written", "def create_invoice(req):", "attributes", Map.of()))));
        f.evidence.add(pyFnEv);
        f.nodes.add(new KnowledgeGraph.Node(id("py-fn"), f.context, "SOURCE_DECLARATION", List.of(pyFnEv.identity())));

        // 3. polyglot.source-syntax FACT (TypeScript HTTP route candidate)
        var tsRouteEv = GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "rowType", "FACT",
                "document", id("ts-doc"),
                "path", LogicalPath.utf8("gateway/routes.ts").rawBase64(),
                "language", "typescript",
                "fact", Map.of("category", "ROUTE_CANDIDATE", "nodeKind", "call_expression",
                        "name", "'/api/v1/orders'", "written", "app.get('/api/v1/orders', handler)",
                        "attributes", Map.of("method", "GET")))));
        f.evidence.add(tsRouteEv);
        f.nodes.add(new KnowledgeGraph.Node(id("ts-route"), f.context, "ROUTE_CANDIDATE", List.of(tsRouteEv.identity())));

        // 4. polyglot.source-syntax FACT (Protobuf message with qualifiedName)
        var protoEv = GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "rowType", "FACT",
                "document", id("proto-doc"),
                "path", LogicalPath.utf8("proto/orders.proto").rawBase64(),
                "language", "protobuf",
                "fact", Map.of("category", "DECLARATION", "nodeKind", "message",
                        "name", "OrderRequest", "written", "message OrderRequest {}",
                        "attributes", Map.of("qualifiedName", "orders.v1.OrderRequest")))));
        f.evidence.add(protoEv);
        f.nodes.add(new KnowledgeGraph.Node(id("proto-msg"), f.context, "PROTOBUF_MESSAGE", List.of(protoEv.identity())));

        // 5. polyglot.inventory-entry
        var invEv = GraphEvidence.capture("polyglot.inventory-entry", Map.of("fields", Map.of(
                "rawPath", LogicalPath.utf8("frontend/src/App.tsx").rawBase64(),
                "kind", "FILE",
                "population", "SOURCE",
                "disposition", "ACQUIRED",
                "languageCandidates", List.of("typescript"),
                "reason", "CLASSIFICATION_HINT_ONLY")));
        f.evidence.add(invEv);
        f.nodes.add(new KnowledgeGraph.Node(id("inv-tsx"), f.context, "INVENTORY_ENTRY", List.of(invEv.identity())));

        // 6. architecture.service-endpoint-reference carrying sourceEvidence
        var refEv = GraphEvidence.capture("architecture.service-endpoint-reference", Map.of(
                "sourceContext", f.context,
                "sourceIdentity", id("ts-route"),
                "sourceKind", "ROUTE_CANDIDATE",
                "role", "ENDPOINT_SITE",
                "sourceEvidence", List.of(tsRouteEv.identity())));
        f.evidence.add(refEv);
        f.nodes.add(new KnowledgeGraph.Node(id("svc-ref"), f.context, "SERVICE_ENDPOINT_REFERENCE", List.of(refEv.identity())));

        var service = new ArchitectureQueryService(f.graph());
        var req = f.request();

        var pyDoc = service.entity(req, id("py-doc")).items().getFirst();
        assertEquals(Optional.of("billing/service.py"), pyDoc.qualifiedName());
        assertEquals(Optional.of("billing/service.py [python]"), pyDoc.spelling());
        assertEquals(Optional.of("billing"), pyDoc.module());
        assertEquals(Optional.of("python"), pyDoc.sourceSet());

        var pyFn = service.entity(req, id("py-fn")).items().getFirst();
        assertEquals(Optional.of("billing/service.py#create_invoice"), pyFn.qualifiedName());
        assertEquals(Optional.of("create_invoice [python]"), pyFn.spelling());
        assertEquals(Optional.of("billing"), pyFn.module());
        assertEquals(Optional.of("python"), pyFn.sourceSet());

        var tsRoute = service.entity(req, id("ts-route")).items().getFirst();
        assertEquals(Optional.of("gateway/routes.ts#GET /api/v1/orders"), tsRoute.qualifiedName());
        assertEquals(Optional.of("GET /api/v1/orders [typescript]"), tsRoute.spelling());
        assertEquals(Optional.of("gateway"), tsRoute.module());
        assertEquals(Optional.of("typescript"), tsRoute.sourceSet());

        var protoMsg = service.entity(req, id("proto-msg")).items().getFirst();
        assertEquals(Optional.of("proto/orders.proto#orders.v1.OrderRequest"), protoMsg.qualifiedName());
        assertEquals(Optional.of("orders.v1.OrderRequest [protobuf]"), protoMsg.spelling());

        var inv = service.entity(req, id("inv-tsx")).items().getFirst();
        assertEquals(Optional.of("frontend/src/App.tsx"), inv.qualifiedName());
        assertEquals(Optional.of("frontend/src/App.tsx [typescript]"), inv.spelling());
        assertEquals(Optional.of("frontend"), inv.module());
        assertEquals(Optional.of("typescript"), inv.sourceSet());

        var svcRef = service.entity(req, id("svc-ref")).items().getFirst();
        assertEquals(Optional.of("gateway/routes.ts#GET /api/v1/orders"), svcRef.qualifiedName());
        assertEquals(Optional.of("GET /api/v1/orders [typescript]"), svcRef.spelling());
        assertEquals(Optional.of("typescript"), svcRef.sourceSet());
    }

    @Test
    void entitiesSearchSupportsCaseInsensitiveSubstringMatchingWithDeterministicOrdering() {
        var f = new Fixture();
        var evA = GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "rowType", "FACT", "path", LogicalPath.utf8("billing/InvoiceService.py").rawBase64(), "language", "python",
                "fact", Map.of("category", "DECLARATION", "nodeKind", "class_definition",
                        "name", "InvoiceService", "written", "class InvoiceService:", "attributes", Map.of()))));
        var evB = GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                "rowType", "FACT", "path", LogicalPath.utf8("orders/order_api.ts").rawBase64(), "language", "typescript",
                "fact", Map.of("category", "ROUTE_CANDIDATE", "nodeKind", "call_expression",
                        "name", "'/api/invoices'", "written", "router.post('/api/invoices')",
                        "attributes", Map.of("method", "POST")))));
        var evC = GraphEvidence.capture("polyglot.inventory-entry", Map.of("fields", Map.of(
                "rawPath", LogicalPath.utf8("docs/README.md").rawBase64(),
                "languageCandidates", List.of("markdown"),
                "reason", "CLASSIFICATION_HINT_ONLY")));
        f.evidence.addAll(List.of(evA, evB, evC));
        f.nodes.add(new KnowledgeGraph.Node(id("n-b"), f.context, "ROUTE_CANDIDATE", List.of(evB.identity())));
        f.nodes.add(new KnowledgeGraph.Node(id("n-a"), f.context, "SOURCE_DECLARATION", List.of(evA.identity())));
        f.nodes.add(new KnowledgeGraph.Node(id("n-c"), f.context, "INVENTORY_ENTRY", List.of(evC.identity())));

        var service = new ArchitectureQueryService(f.graph());
        var matched = service.entities(f.request(), "INVOICE", Page.first());
        assertEquals(2, matched.items().size());
        var ids = matched.items().stream().map(EntityView::identity).toList();
        assertEquals(ids.stream().sorted().toList(), ids, "Entity search results must preserve deterministic identity ordering");

        // Searching a single hex letter 'f' must NOT spuriously match nodes solely because their SHA-256 identity contains 'f'.
        var readmeOnly = service.entities(f.request(), "readme", Page.first());
        assertEquals(1, readmeOnly.items().size());
        assertEquals(id("n-c"), readmeOnly.items().getFirst().identity());

        // Unsafe host paths in polyglot payloads must be rejected without leaking host paths.
        var fUnsafe = new Fixture();
        var badEv = GraphEvidence.capture("polyglot.inventory-entry", Map.of("fields", Map.of(
                "rawPath", "C:/PRIVATE_HOST_PATH/secret.py",
                "languageCandidates", List.of("python"))));
        fUnsafe.evidence.add(badEv);
        fUnsafe.nodes.add(new KnowledgeGraph.Node(id("bad-poly"), fUnsafe.context, "INVENTORY_ENTRY", List.of(badEv.identity())));
        var badRes = new ArchitectureQueryService(fUnsafe.graph()).entity(fUnsafe.request(), id("bad-poly"));
        assertFalse(com.evolution.analysis.contract.serialization.CanonicalJson.write(badRes).contains("PRIVATE_HOST_PATH"));
        assertTrue(badRes.gaps().stream().anyMatch(g -> "INVALID_SOURCE_PATH".equals(g.reasonCode())));
    }

    @Test
    void describesServiceTopologyComponentsAndWhitelistsServiceEdgeEvidence() {
        var f = new Fixture();
        var buildEv = GraphEvidence.capture("polyglot.build-component", Map.of(
                "id", "orders-app", "directory", "services/orders-service", "kind", "MAVEN", "name", "orders-service"));
        var infraEv = GraphEvidence.capture("architecture.infra-component", Map.of(
                "name", "eureka", "image", "springcloud/eureka", "spec", "compose_service",
                "trust", "DECLARATIVE_CONFIG", "runtimeObserved", false));
        var actorEv = GraphEvidence.capture("architecture.external-actor", Map.of(
                "name", "user", "trust", "DERIVED_DFD_POLICY", "rule", "DFD_EXTERNAL_ENTRY_POINT",
                "runtimeObserved", false));
        var declEdgeEv = GraphEvidence.capture("architecture.declarative-topology", Map.of(
                "kind", "CALLS_SERVICE", "mechanism", "SPRING_CONFIG_EUREKA-CLIENT",
                "source", "orders-service", "target", "eureka",
                "trust", "DECLARATIVE_CONFIG", "runtimeObserved", false));
        var svcCallEv = GraphEvidence.capture("architecture.service-call", Map.of(
                "kind", "CALLS_SERVICE", "certainty", "CANDIDATE",
                "routingEstablished", false, "runtimeObserved", false,
                "source", Map.of("context", f.context, "identity", id("svc-build"), "role", "CALLER_DECLARATION"),
                "destinations", List.of(Map.of("context", f.context, "identity", id("svc-infra"), "role", "ENDPOINT_SITE"))));
        f.evidence.addAll(List.of(buildEv, infraEv, actorEv, declEdgeEv, svcCallEv));
        f.nodes.add(new KnowledgeGraph.Node(id("svc-build"), f.context, "BUILD_COMPONENT", List.of(buildEv.identity())));
        f.nodes.add(new KnowledgeGraph.Node(id("svc-infra"), f.context, "INFRA_COMPONENT", List.of(infraEv.identity())));
        f.nodes.add(new KnowledgeGraph.Node(id("svc-actor"), f.context, "EXTERNAL_ACTOR", List.of(actorEv.identity())));
        f.edges.add(new KnowledgeGraph.Edge(id("e-decl"), f.context, "CALLS_SERVICE", id("svc-build"),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(id("svc-infra")), Optional.empty()),
                List.of(declEdgeEv.identity()), Optional.empty()));
        f.edges.add(new KnowledgeGraph.Edge(id("e-call"), f.context, "CALLS_SERVICE", id("svc-actor"),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(id("svc-build")), Optional.empty()),
                List.of(svcCallEv.identity()), Optional.empty()));

        var service = new ArchitectureQueryService(f.graph());
        var req = f.request();

        var buildEnt = service.entity(req, id("svc-build")).items().getFirst();
        assertEquals(Optional.of("services/orders-service"), buildEnt.canonicalName());
        assertEquals(Optional.of("services/orders-service"), buildEnt.spelling());
        assertEquals(Optional.of("PROJECT"), buildEnt.origin());

        var infraEnt = service.entity(req, id("svc-infra")).items().getFirst();
        assertEquals(Optional.of("eureka"), infraEnt.canonicalName());
        assertEquals(Optional.of("eureka"), infraEnt.spelling());
        assertEquals(Optional.of("INFRA"), infraEnt.origin());

        var actorEnt = service.entity(req, id("svc-actor")).items().getFirst();
        assertEquals(Optional.of("user"), actorEnt.canonicalName());
        assertEquals(Optional.of("EXTERNAL"), actorEnt.origin());

        var declMeta = service.evidence(req, declEdgeEv.identity()).items().getFirst().metadata();
        assertEquals("orders-service", declMeta.get("source"));
        assertEquals("eureka", declMeta.get("target"));
        assertEquals("SPRING_CONFIG_EUREKA-CLIENT", declMeta.get("mechanism"));

        var callMeta = service.evidence(req, svcCallEv.identity()).items().getFirst().metadata();
        assertEquals("CALLS_SERVICE", callMeta.get("kind"));
        assertTrue(callMeta.containsKey("source"));
        assertTrue(callMeta.containsKey("destinations"));
    }

    @Test
    void buildComponentUsesCanonicalServiceIdentityWhilePreservingModuleDirectory() {
        var f = new Fixture();
        var buildEv = GraphEvidence.capture("polyglot.build-component", Map.of(
                "id", "catalog-dir", "directory", "services/nested/catalog-dir", "kind", "MAVEN", "name", "catalog-dir"));
        var identityEv = GraphEvidence.capture("architecture.service-identity", Map.of(
                "serviceName", "catalog-api",
                "name", "catalog-api",
                "directory", "services/nested/catalog-dir",
                "spec", "spring-config",
                "trust", "DECLARATIVE_CONFIG",
                "runtimeObserved", false,
                "secretRawPath", "C:/PRIVATE_HOST/secret.yml"));
        var brokerEv = GraphEvidence.capture("polyglot.event-broker-link", Map.of(
                "kind", "CONSUMES_FROM", "topic", "orders.placed", "protocol", "KAFKA",
                "site", id("cons"), "brokers", List.of(id("kafka")),
                "certainty", "CANDIDATE", "trust", "DERIVED_DFD_POLICY", "orientation", "DFD_DATA_FLOW",
                "runtimeObserved", false));
        f.evidence.addAll(List.of(buildEv, identityEv, brokerEv));
        f.nodes.add(new KnowledgeGraph.Node(id("svc-order-1"), f.context, "BUILD_COMPONENT",
                List.of(buildEv.identity(), identityEv.identity())));
        f.nodes.add(new KnowledgeGraph.Node(id("svc-order-2"), f.context, "BUILD_COMPONENT",
                List.of(identityEv.identity(), buildEv.identity())));
        f.nodes.add(new KnowledgeGraph.Node(id("broker-node"), f.context, "INFRA_COMPONENT",
                List.of(brokerEv.identity())));

        var service = new ArchitectureQueryService(f.graph());
        var req = f.request();

        for (String nid : List.of(id("svc-order-1"), id("svc-order-2"))) {
            var ent = service.entity(req, nid).items().getFirst();
            assertEquals(Optional.of("catalog-api"), ent.canonicalName());
            assertEquals(Optional.of("catalog-api"), ent.spelling());
            assertEquals(Optional.of("services/nested/catalog-dir"), ent.module());
            assertEquals(Optional.of("PROJECT"), ent.origin());
        }

        var idView = service.evidence(req, identityEv.identity()).items().getFirst();
        assertTrue(idView.redacted());
        assertEquals("catalog-api", idView.metadata().get("serviceName"));
        assertEquals("services/nested/catalog-dir", idView.metadata().get("directory"));
        assertEquals("DECLARATIVE_CONFIG", idView.metadata().get("trust"));
        assertFalse(idView.metadata().containsKey("secretRawPath"));
        assertFalse(com.evolution.analysis.contract.serialization.CanonicalJson.write(idView).contains("PRIVATE_HOST"));

        var brokerMeta = service.evidence(req, brokerEv.identity()).items().getFirst().metadata();
        assertEquals("DERIVED_DFD_POLICY", brokerMeta.get("trust"));
        assertEquals("DFD_DATA_FLOW", brokerMeta.get("orientation"));
    }

    @Test
    void operationalGuardReclaimsTransientPreGcHeapDipBeforeHaltingEdgesQuery() {
        var f = new Fixture();
        var srcEv = GraphEvidence.capture("polyglot.build-component", Map.of(
                "id", "orders-service", "directory", "orders-service", "kind", "MAVEN", "name", "orders-service"));
        var dstEv = GraphEvidence.capture("architecture.infra-component", Map.of(
                "name", "github-repository", "image", "github-repository", "spec", "config-reference",
                "trust", "DECLARATIVE_CONFIG", "runtimeObserved", false));
        var edgeEv = GraphEvidence.capture("architecture.declarative-topology", Map.of(
                "kind", "CALLS_SERVICE", "mechanism", "SPRING_CONFIG_CONFIG-GIT-REPO",
                "source", "github-repository", "target", "orders-service",
                "trust", "DERIVED_DFD_POLICY", "runtimeObserved", false));
        f.evidence.addAll(List.of(srcEv, dstEv, edgeEv));
        f.nodes.add(new KnowledgeGraph.Node(id("svc-src"), f.context, "BUILD_COMPONENT", List.of(srcEv.identity())));
        f.nodes.add(new KnowledgeGraph.Node(id("svc-dst"), f.context, "INFRA_COMPONENT", List.of(dstEv.identity())));
        f.edges.add(new KnowledgeGraph.Edge(id("e-1"), f.context, "CALLS_SERVICE", id("svc-dst"),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(id("svc-src")), Optional.empty()),
                List.of(edgeEv.identity()), Optional.empty()));
        var graph = f.graph();
        var req = new Request(graph.identity(), f.context, Scope.ENTITY, Projection.BOUNDED_UNION,
                Direction.OUTGOING, Set.of("CALLS_SERVICE"), Population.all(), false, Optional.empty(), Limits.defaults());

        // 1. Transient pre-GC dip (< 8 MB) reclaimed once -> edges query completes with ALL edges (no silent empty list).
        var available = new java.util.concurrent.atomic.AtomicLong(2_000_000L);
        var gcCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        var clock = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        var recoverGuard = ArchitectureQueryService.operationalGuard(
                () -> false, 0L, 8_000_000L, available::get, () -> 1_536_000_000L,
                () -> {
                    gcCalls.incrementAndGet();
                    available.set(500_000_000L);
                },
                clock::get);
        var recovered = new ArchitectureQueryService(graph, recoverGuard, Optional.empty()).edges(req, Page.first());
        assertEquals(1, gcCalls.get(), "transient pre-GC dip must trigger a single reclaim check");
        assertEquals(StopReason.NONE, recovered.bounds().stopReason());
        assertEquals(Operation.COMPLETED, recovered.operation());
        assertEquals(1, recovered.items().size());

        // 2. Genuine heap exhaustion (still < 8 MB after GC) -> fail-closed StopReason.MEMORY without GC thrashing.
        var stuckAvailable = new java.util.concurrent.atomic.AtomicLong(2_000_000L);
        var stuckGcCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        var stuckGuard = ArchitectureQueryService.operationalGuard(
                () -> false, 0L, 8_000_000L, stuckAvailable::get, () -> 1_536_000_000L,
                stuckGcCalls::incrementAndGet, clock::get);
        var halted = new ArchitectureQueryService(graph, stuckGuard, Optional.empty()).edges(req, Page.first());
        assertEquals(1, stuckGcCalls.get(), "cooldown must prevent repeated GC calls under sustained memory pressure");
        assertEquals(StopReason.MEMORY, halted.bounds().stopReason());
        assertEquals(Operation.RESOURCE_LIMIT, halted.operation());
        assertTrue(halted.items().isEmpty());
        // Additional poll within cooldown returns MEMORY immediately with zero extra GC.
        assertEquals(StopReason.MEMORY, stuckGuard.get());
        assertEquals(1, stuckGcCalls.get());

        // 3. Reserve >= maxHeap fails closed immediately with zero GC calls.
        var impossibleGcCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        var impossibleGuard = ArchitectureQueryService.operationalGuard(
                () -> false, 0L, 1_536_000_000L, () -> 500_000_000L, () -> 1_536_000_000L,
                impossibleGcCalls::incrementAndGet, clock::get);
        assertEquals(StopReason.MEMORY, impossibleGuard.get());
        assertEquals(0, impossibleGcCalls.get());

        // 4. Public overload honors CANCELLED, impossible reserve -> MEMORY, and healthy reserve -> NONE.
        assertEquals(StopReason.CANCELLED, ArchitectureQueryService.operationalGuard(() -> true, 0L, 8_000_000L).get());
        assertEquals(StopReason.MEMORY, ArchitectureQueryService.operationalGuard(() -> false, 0L, Long.MAX_VALUE).get());
        assertEquals(StopReason.NONE, ArchitectureQueryService.operationalGuard(() -> false, 0L, 1L).get());
    }
}
