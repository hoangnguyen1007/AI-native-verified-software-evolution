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
}
