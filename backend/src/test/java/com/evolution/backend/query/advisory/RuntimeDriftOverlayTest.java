package com.evolution.backend.query.advisory;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphProjectionStore;
import com.evolution.analysis.graph.GraphSnapshotCodec;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.polyglot.DeclarativeTopologyProjection;
import com.evolution.analysis.polyglot.ServiceArchitectureProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract &amp; quarantine tests for {@link RuntimeDriftOverlayExtractor} (WP1.3):
 * Ripple {@code post.csv} and OpenTelemetry Service Map JSON static-vs-runtime diffing
 * strictly isolated in {@code TrustTier.RUNTIME_OBSERVED} with zero mutation of canonical
 * {@link KnowledgeGraph#identity()} SHA-256.
 */
class RuntimeDriftOverlayTest {

    @Test
    void rippleCsvDiffComputesShadowUnexercisedConcordantMetricsDeterministicallyWithoutMutatingGraph(@TempDir Path tempDir)
            throws Exception {
        KnowledgeGraph staticGraph = buildProjectedTopologyGraph();
        ContentDigest digestBefore = staticGraph.identity();

        // Static graph has 3 edges:
        //   api-gateway -> order-service
        //   order-service -> inventory-service
        //   order-service -> orders-db
        // Runtime post.csv observes:
        //   api-gateway -> order-service      (CONCORDANT, duplicated to test weight aggregation)
        //   order-service -> orders-db        (CONCORDANT)
        //   order-service -> payment-service  (SHADOW_RUNTIME_DEPENDENCY)
        // Missing in runtime:
        //   order-service -> inventory-service (UNEXERCISED_STATIC_PATH)
        String csv = """
                # Ripple post.csv export
                src,dst,weight
                "order-service","payment-service",17
                api-gateway,order-service,10
                order-service,orders-db,25
                api-gateway,order-service,5
                order-service,order-service,1
                malformed_single_column
                """;
        Path csvFile = tempDir.resolve("post.csv");
        Files.writeString(csvFile, csv, StandardCharsets.UTF_8);

        var snapshot = RuntimeDriftOverlayExtractor.extract(staticGraph, csvFile);

        // I2/I4: Canonical KnowledgeGraph SHA-256 identity MUST be 100% bit-identical before & after
        assertEquals(digestBefore, staticGraph.identity(),
                "RuntimeDriftOverlayExtractor must NEVER mutate staticGraph.identity()");
        assertEquals(digestBefore.value(), snapshot.graphIdentity());
        assertEquals(RuntimeDriftOverlayExtractor.SCHEMA, snapshot.schema());
        assertEquals("RUNTIME_OBSERVED", snapshot.trust());
        assertTrue(snapshot.quarantined());
        assertEquals(RuntimeDriftOverlayExtractor.InputFormat.RIPPLE_CSV, snapshot.inputFormat());

        // Set algebra verification
        assertEquals(1, snapshot.shadowDependencies().size());
        assertEquals("order-service->payment-service", edgeLabel(snapshot.shadowDependencies().getFirst()));
        assertEquals(17.0, snapshot.shadowDependencies().getFirst().runtimeWeight().orElseThrow());

        assertEquals(1, snapshot.unexercisedStaticPaths().size());
        assertEquals("order-service->inventory-service", edgeLabel(snapshot.unexercisedStaticPaths().getFirst()));

        assertEquals(2, snapshot.concordantEdges().size());
        assertEquals(List.of("api-gateway->order-service", "order-service->orders-db"),
                snapshot.concordantEdges().stream().map(RuntimeDriftOverlayTest::edgeLabel).toList());
        assertEquals(15.0, snapshot.concordantEdges().getFirst().runtimeWeight().orElseThrow(),
                "Duplicate runtime rows should deterministically sum weights (10 + 5)");

        // Closed denominator warning for malformed row (I3)
        assertEquals(1, snapshot.warnings().size());
        assertTrue(snapshot.warnings().getFirst().contains("malformed") || snapshot.warnings().getFirst().contains("2 columns"));

        // Exact precision, recall, F1, Jaccard:
        // |E_static|=3, |E_runtime|=3, |intersection|=2, |union|=4
        // precision = 2/3 = 0.6667, recall = 2/3 = 0.6667, f1 = 0.6667, jaccard = 2/4 = 0.5
        var m = snapshot.metrics();
        assertEquals(3, m.staticEdgeCount());
        assertEquals(3, m.runtimeEdgeCount());
        assertEquals(2, m.concordantCount());
        assertEquals(1, m.shadowRuntimeCount());
        assertEquals(1, m.unexercisedStaticCount());
        assertEquals(0.6667, m.precision(), 1e-4);
        assertEquals(0.6667, m.recall(), 1e-4);
        assertEquals(0.6667, m.f1(), 1e-4);
        assertEquals(0.5, m.jaccard(), 1e-4);

        // Every finding strictly carries RUNTIME_OBSERVED and quarantined=true
        assertEquals(4, snapshot.findings().size());
        assertTrue(snapshot.findings().stream().allMatch(f -> "RUNTIME_OBSERVED".equals(f.trust()) && f.quarantined()));

        // Determinism under row permutation (I2)
        String reorderedCsv = """
                src,dst,weight
                order-service,orders-db,25
                api-gateway,order-service,5
                api-gateway,order-service,10
                order-service,payment-service,17
                """;
        var reordered = RuntimeDriftOverlayExtractor.extractFromCsv(staticGraph, reorderedCsv, "post.csv");
        assertEquals(
                snapshot.findings().stream().map(f -> f.classification() + ":" + f.source() + "->" + f.target() + ":" + f.id()).toList(),
                reordered.findings().stream().map(f -> f.classification() + ":" + f.source() + "->" + f.target() + ":" + f.id()).toList(),
                "Drift finding order and IDs must be invariant to input row order");
        assertEquals(snapshot.metrics(), reordered.metrics());

        // View payload exposes quarantine & canonicalLeakage=0
        var view = snapshot.toView(100);
        assertEquals("RUNTIME_OBSERVED", view.get("trust"));
        assertEquals(true, view.get("quarantined"));
        assertEquals(0, view.get("canonicalLeakage"));
    }

    @Test
    void headerlessRippleCsvAndOpenTelemetryServiceMapJsonParseCleanly(@TempDir Path tempDir) throws Exception {
        KnowledgeGraph staticGraph = buildProjectedTopologyGraph();
        ContentDigest digestBefore = staticGraph.identity();

        // 1. Headerless Ripple post.csv (`src,dst` without header or weight)
        String headerless = "api-gateway,order-service\norder-service,inventory-service\norder-service,orders-db\n";
        var csvSnap = RuntimeDriftOverlayExtractor.extractFromCsv(staticGraph, headerless);
        assertEquals(3, csvSnap.concordantEdges().size());
        assertEquals(0, csvSnap.shadowDependencies().size());
        assertEquals(0, csvSnap.unexercisedStaticPaths().size());
        assertEquals(1.0, csvSnap.metrics().precision());
        assertEquals(1.0, csvSnap.metrics().recall());
        assertEquals(1.0, csvSnap.metrics().jaccard());

        // 2. OpenTelemetry / Tempo Service Map JSON with node-ID indirection + K8s DNS & port suffixes
        String otelJson = """
                {
                  "serviceMap": {
                    "nodes": [
                      { "id": "n1", "serviceName": "api-gateway.prod.svc.cluster.local:8080" },
                      { "id": "n2", "serviceName": "order_service" },
                      { "id": "n3", "serviceName": "orders-db:5432" },
                      { "id": "n4", "serviceName": "fraud-detector" }
                    ],
                    "edges": [
                      { "source": "n1", "target": "n2", "callCount": 120 },
                      { "source": "n2", "target": "n3", "callCount": 88 },
                      { "source": "n2", "target": "n4", "callCount": 4 }
                    ]
                  }
                }
                """;
        Path otelFile = tempDir.resolve("otel-service-map.json");
        Files.writeString(otelFile, otelJson, StandardCharsets.UTF_8);

        var otelSnap = RuntimeDriftOverlayExtractor.extract(staticGraph, otelFile);
        assertEquals(digestBefore, staticGraph.identity());
        assertEquals(RuntimeDriftOverlayExtractor.InputFormat.OTEL_SERVICE_MAP_JSON, otelSnap.inputFormat());
        assertEquals("RUNTIME_OBSERVED", otelSnap.trust());
        assertTrue(otelSnap.quarantined());
        assertEquals(List.of("order-service->fraud-detector"),
                otelSnap.shadowDependencies().stream().map(RuntimeDriftOverlayTest::edgeLabel).toList());
        assertEquals(List.of("order-service->inventory-service"),
                otelSnap.unexercisedStaticPaths().stream().map(RuntimeDriftOverlayTest::edgeLabel).toList());
        assertEquals(List.of("api-gateway->order-service", "order-service->orders-db"),
                otelSnap.concordantEdges().stream().map(RuntimeDriftOverlayTest::edgeLabel).toList());
        assertEquals(120.0, otelSnap.concordantEdges().getFirst().runtimeWeight().orElseThrow());

        // 3. Jaeger / OTel dependencies array format
        String jaegerJson = """
                {
                  "data": [
                    { "parent": "api-gateway", "child": "order-service", "callCount": 42 },
                    { "parent": "order-service", "child": "inventory-service", "callCount": 19 }
                  ]
                }
                """;
        var jaegerSnap = RuntimeDriftOverlayExtractor.extractFromJson(staticGraph, jaegerJson);
        assertEquals(2, jaegerSnap.concordantEdges().size());
        assertEquals(0, jaegerSnap.shadowDependencies().size());
        assertEquals(1, jaegerSnap.unexercisedStaticPaths().size());
        assertEquals("order-service->orders-db", edgeLabel(jaegerSnap.unexercisedStaticPaths().getFirst()));
        assertEquals(digestBefore, staticGraph.identity());
    }

    private static String edgeLabel(RuntimeDriftOverlayExtractor.DriftFinding f) {
        return f.source() + "->" + f.target();
    }

    private static KnowledgeGraph buildProjectedTopologyGraph() throws Exception {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/runtime-drift.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("runtime-drift-snap"));
        String root = snap.value();
        var store = GraphProjectionStore.memory();

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        store.records("evidence", GraphSnapshotCodec::evidence).put(snapEv.identity().value(), snapEv);

        putNode(store, root, id("bc-gw"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "api-gateway", "directory", "api-gateway")));
        putNode(store, root, id("bc-ord"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "order-service", "directory", "order-service")));
        putNode(store, root, id("bc-inv"), "BUILD_COMPONENT",
                GraphEvidence.capture("polyglot.build-component", Map.of("id", "inventory-service", "directory", "inventory-service")));

        putNode(store, root, id("gw-rt"), "SOURCE_GATEWAY_ROUTE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("api-gateway/src/main/resources/application.yml"),
                        "fact", Map.of("nodeKind", "gateway_route", "name", "order-service",
                                "attributes", Map.of("targetService", "order-service"))))));
        putNode(store, root, id("ord-inv"), "SOURCE_HTTP_CALL_CANDIDATE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("order-service/src/main/java/InventoryClient.java"),
                        "fact", Map.of("nodeKind", "method_call", "name", "\"http://inventory-service/items\"",
                                "attributes", Map.of("method", "GET", "targetService", "inventory-service"))))));
        putNode(store, root, id("dc-odb"), "SOURCE_DECLARATION",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "document", "dc", "path", b64("docker-compose.yml"),
                        "fact", Map.of("nodeKind", "compose_service", "name", "orders-db",
                                "span", Map.of("start", 1, "end", 5), "attributes", Map.of())))));
        putNode(store, root, id("ord-db"), "SOURCE_INFRA_CONFIG_REF",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("order-service/src/main/resources/application.yml"),
                        "fact", Map.of("name", "orders-db",
                                "attributes", Map.of("role", "datasource", "target", "orders-db"))))));

        DeclarativeTopologyProjection.project(store, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);

        var nodes = new ArrayList<>(store.records("nodes", GraphSnapshotCodec::node).values());
        var edges = new ArrayList<>(store.records("edges", GraphSnapshotCodec::edge).values());
        var evidence = new ArrayList<>(store.records("evidence", GraphSnapshotCodec::evidence).values());
        return new KnowledgeGraph(
                snap,
                List.of(new KnowledgeGraph.Context(root, snap, Optional.empty(), snapEv.identity())),
                nodes,
                edges,
                evidence);
    }

    private static void putNode(GraphProjectionStore s, String ctx, String id, String kind, GraphEvidence proof) {
        s.records("evidence", GraphSnapshotCodec::evidence).put(proof.identity().value(), proof);
        s.records("nodes", GraphSnapshotCodec::node).put(ctx + "|" + id,
                new KnowledgeGraph.Node(id, ctx, kind, List.of(proof.identity())));
    }

    private static String id(String v) {
        return ContentDigest.sha256Utf8(v).value();
    }

    private static String b64(String p) {
        return Base64.getEncoder().encodeToString(p.getBytes(StandardCharsets.UTF_8));
    }
}
