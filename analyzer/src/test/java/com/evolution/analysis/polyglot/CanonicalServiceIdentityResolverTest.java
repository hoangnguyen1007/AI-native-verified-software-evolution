package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalServiceIdentityResolverTest {

    private static String id(String value) {
        return ContentDigest.sha256Utf8(value).value();
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void putNode(GraphProjectionStore store, String ctx, String identity, String kind, GraphEvidence proof) {
        store.records("evidence", GraphSnapshotCodec::evidence).put(proof.identity().value(), proof);
        store.records("nodes", GraphSnapshotCodec::node).put(ctx + "|" + identity,
                new KnowledgeGraph.Node(identity, ctx, kind, List.of(proof.identity())));
    }

    @Test
    void crossLayerReconciliationMergesBuildComposeK8sByBuildDirDockerfileSuffixAndPortRoute() {
        var resolver = new CanonicalServiceIdentityResolver();
        String billingId = id("mod:billing");
        String catalogId = id("mod:catalog");
        String dbId = id("infra:db");

        resolver.registerBuildComponent(new CanonicalServiceIdentityResolver.BuildModuleDescriptor(
                billingId, "services/shop.billing", "services/shop.billing",
                "MAVEN:services/shop.billing", "shop.billing", "shop.billing",
                "MAVEN", Set.of("java"), "war", true, false,
                true, "services/shop.billing/Dockerfile",
                Set.of("8080"), Set.of("/api/billing")));
        resolver.registerBuildComponent(new CanonicalServiceIdentityResolver.BuildModuleDescriptor(
                catalogId, "services/catalog-core", "services/catalog-core",
                "GO:services/catalog-core", "catalog-core", "catalog-core",
                "GO", Set.of("go"), "", false, false,
                true, "services/catalog-core/Dockerfile",
                Set.of("8084"), Set.of("/v1/catalog")));
        resolver.observeDeclarationNames(List.of("shop-billing", "shop-catalog", "shop-db"));
        assertEquals("shop-", resolver.sharedDeclPrefix());

        // 1. Reconcile Compose by buildContextDir
        var composeBilling = resolver.resolveDeclaration(new CanonicalServiceIdentityResolver.DeclarationSignal(
                "billing", "services/shop.billing", "", "billing", "acme/shop-billing:1.0", "shop-billing",
                Set.of("8080"), Set.of(), CanonicalServiceIdentityResolver.EvidenceLayer.COMPOSE, false));
        assertEquals(Optional.of(billingId), composeBilling);
        resolver.recordLayer(billingId, CanonicalServiceIdentityResolver.EvidenceLayer.COMPOSE);

        // 2. Reconcile K8s by normalized artifact suffix after shared prefix stripping
        var k8sBilling = resolver.resolveDeclaration(new CanonicalServiceIdentityResolver.DeclarationSignal(
                "shop-billing", "", "", "", "acme/shop-billing:1.0", "shop-billing",
                Set.of("8080"), Set.of(), CanonicalServiceIdentityResolver.EvidenceLayer.K8S, false));
        assertEquals(Optional.of(billingId), k8sBilling);
        resolver.recordLayer(billingId, CanonicalServiceIdentityResolver.EvidenceLayer.K8S);
        assertEquals(List.of("BUILD", "COMPOSE", "K8S"), resolver.evidenceLayers(billingId));

        // 3. Reconcile by Dockerfile path
        var byDockerfile = resolver.resolveDeclaration(new CanonicalServiceIdentityResolver.DeclarationSignal(
                "unmatched-name", "", "services/catalog-core/Dockerfile", "", "", "",
                Set.of(), Set.of(), CanonicalServiceIdentityResolver.EvidenceLayer.COMPOSE, false));
        assertEquals(Optional.of(catalogId), byDockerfile);

        // 4. Reconcile by exposedPort + routePrefix when names differ
        var byPortRoute = resolver.resolveDeclaration(new CanonicalServiceIdentityResolver.DeclarationSignal(
                "edge-proxy-target", "", "", "", "", "",
                Set.of("8084"), Set.of("/v1/catalog/*"), CanonicalServiceIdentityResolver.EvidenceLayer.K8S, false));
        assertEquals(Optional.of(catalogId), byPortRoute);

        // 5. Cross-layer infra reconciliation (Compose "db" + K8s "shop-db" sharing imageTail "shop-db")
        resolver.registerInfraComponent(dbId, "db", "", "shop-db", Set.of("3306"),
                CanonicalServiceIdentityResolver.EvidenceLayer.COMPOSE);
        var k8sDb = resolver.resolveDeclaration(new CanonicalServiceIdentityResolver.DeclarationSignal(
                "shop-db", "", "", "", "acme/shop-db:1.0", "shop-db",
                Set.of("3306"), Set.of(), CanonicalServiceIdentityResolver.EvidenceLayer.K8S, true));
        assertEquals(Optional.of(dbId), k8sDb);
        resolver.recordLayer(dbId, CanonicalServiceIdentityResolver.EvidenceLayer.K8S);
        assertEquals(List.of("COMPOSE", "K8S"), resolver.evidenceLayers(dbId));
    }

    @Test
    void declarativeProjectionElevatesDockerfilePlusK8sServicesAndFlagsTestOnlyModules() throws Exception {
        var store = GraphProjectionStore.memory();
        String root = id("snap:polyglot-k8s");

        // Non-JVM services (Python + Node + .NET) with Dockerfile + K8s Deployment -> SERVICE_COMPONENT
        putNode(store, root, id("mod-py"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "PYTHON:src/recommender", "directory", "src/recommender", "languages", List.of("python"))));
        putNode(store, root, id("mod-node"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "NODE:src/currency", "directory", "src/currency", "languages", List.of("javascript"))));
        putNode(store, root, id("mod-dotnet"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "DOTNET:src/basket", "directory", "src/basket", "languages", List.of("csharp"))));
        // JVM Gradle service with Dockerfile + K8s Deployment -> stays BUILD_COMPONENT with [BUILD, K8S]
        putNode(store, root, id("mod-jvm"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "GRADLE:src/ads", "directory", "src/ads", "languages", List.of("java"))));
        // Test-only modules: by directory name & by .csproj build.isTestProject=true metadata
        putNode(store, root, id("mod-test-dir"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "NODE:src/currency/tests", "directory", "src/currency/tests", "languages", List.of("javascript"))));
        putNode(store, root, id("mod-csproj-test"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "DOTNET:src/basket-suite", "directory", "src/basket-suite", "languages", List.of("csharp"))));

        // Dockerfiles (including one standalone Go dir without a BUILD_COMPONENT)
        for (String df : List.of("src/recommender/Dockerfile", "src/currency/Dockerfile",
                "src/basket/Dockerfile", "src/ads/Dockerfile", "src/checkout/Dockerfile")) {
            putNode(store, root, id("df:" + df), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax",
                    Map.of("fields", Map.of("path", b64(df),
                            "fact", Map.of("name", "base", "attributes", Map.of("spec", "dockerfile", "image", "alpine:3.19"))))));
        }

        // .csproj test metadata flag (from DeclarativeTopologyParsers.parseBuildDescriptorMetadata)
        putNode(store, root, id("csproj-test-prop"), "SOURCE_CONFIG_PROPERTY", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", b64("src/basket-suite/Basket.Suite.csproj"),
                        "fact", Map.of("name", "build.isTestProject", "nodeKind", "build_descriptor_metadata",
                                "attributes", Map.of("spec", "build-descriptor", "key", "build.isTestProject", "value", "true"))))));

        // K8s Deployments for all 5 runtime services
        for (String svc : List.of("recommender", "currency", "basket", "ads", "checkout")) {
            putNode(store, root, id("k8s:" + svc), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax",
                    Map.of("fields", Map.of("path", b64("k8s/" + svc + ".yaml"),
                            "fact", Map.of("name", svc, "nodeKind", "k8s_deployment",
                                    "attributes", Map.of("spec", "kubernetes", "image", "acme/" + svc + ":1.0"))))));
        }

        DeclarativeTopologyProjection.project(store, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);

        var nodes = store.records("nodes", GraphSnapshotCodec::node);
        var evidence = store.records("evidence", GraphSnapshotCodec::evidence);

        assertEquals("SERVICE_COMPONENT", nodes.get(root + "|" + id("mod-py")).kind());
        assertEquals("SERVICE_COMPONENT", nodes.get(root + "|" + id("mod-node")).kind());
        assertEquals("SERVICE_COMPONENT", nodes.get(root + "|" + id("mod-dotnet")).kind());
        assertEquals("BUILD_COMPONENT", nodes.get(root + "|" + id("mod-jvm")).kind());

        // Standalone Dockerfile + K8s Deployment ("checkout") must also be elevated to SERVICE_COMPONENT
        boolean checkoutElevated = nodes.values().stream()
                .filter(n -> "SERVICE_COMPONENT".equals(n.kind()))
                .anyMatch(n -> hasServiceIdentity(n, evidence, "checkout", List.of("K8S")));
        assertTrue(checkoutElevated, "Standalone Dockerfile + K8s Deployment must be elevated to SERVICE_COMPONENT");

        // All non-JVM services carry evidenceLayers = [BUILD, K8S]
        assertTrue(hasServiceIdentity(nodes.get(root + "|" + id("mod-dotnet")), evidence, "basket", List.of("BUILD", "K8S")));
        assertTrue(hasServiceIdentity(nodes.get(root + "|" + id("mod-jvm")), evidence, "ads", List.of("BUILD", "K8S")));

        // Both test-only modules must be flagged isTestOnlyModule=true & NOT elevated to SERVICE_COMPONENT
        assertTrue(hasTestOnlyClassification(nodes.get(root + "|" + id("mod-test-dir")), evidence));
        assertTrue(hasTestOnlyClassification(nodes.get(root + "|" + id("mod-csproj-test")), evidence));
        assertNotEquals("SERVICE_COMPONENT", nodes.get(root + "|" + id("mod-test-dir")).kind());
        assertNotEquals("SERVICE_COMPONENT", nodes.get(root + "|" + id("mod-csproj-test")).kind());

        // Re-projection idempotency (I2): running a second pass must not spawn duplicate SERVICE_COMPONENT/INFRA_COMPONENT nodes
        var beforeNodes = new TreeMap<>(nodes);
        var beforeEdges = new TreeMap<>(store.records("edges", GraphSnapshotCodec::edge));
        DeclarativeTopologyProjection.project(store, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);
        assertEquals(beforeNodes, store.records("nodes", GraphSnapshotCodec::node),
                "Re-projecting elevated SERVICE_COMPONENT nodes must be strictly idempotent");
        assertEquals(beforeEdges, store.records("edges", GraphSnapshotCodec::edge));
    }

    @Test
    void multiModuleWarUtilitiesInterfacesComposeK8sConsolidatesTwentyEightToSevenPreservingAllFortyEightEndpoints() throws Exception {
        var store = GraphProjectionStore.memory();
        String root = id("snap:multi-war-28-to-7");

        // 15 Build components: 1 root aggregator + 1 e2e-tests + 6 WAR services + 1 interface JAR + 6 utility JAR/POMs
        putNode(store, root, id("mod:root"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "MAVEN:.", "directory", ".", "languages", List.of("java"))));
        putNode(store, root, id("mod:e2e"), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "MAVEN:e2e-tests", "directory", "e2e-tests", "languages", List.of("java"))));

        List<String> coreServices = List.of("auth", "image", "persistence", "recommender", "registry", "webui");
        for (String svc : coreServices) {
            String dir = "services/acme." + svc;
            putNode(store, root, id("mod:" + svc), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                    Map.of("id", "MAVEN:" + dir, "directory", dir, "languages", List.of("java"))));
            putNode(store, root, id("pkg:" + svc), "SOURCE_CONFIG_PROPERTY", GraphEvidence.capture("polyglot.source-syntax",
                    Map.of("fields", Map.of("path", b64(dir + "/pom.xml"),
                            "fact", Map.of("name", "build.packaging", "nodeKind", "build_descriptor_metadata",
                                    "attributes", Map.of("spec", "build-descriptor", "key", "build.packaging", "value", "war", "executable", "true"))))));
            putNode(store, root, id("df:" + svc), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax",
                    Map.of("fields", Map.of("path", b64(dir + "/Dockerfile"),
                            "fact", Map.of("name", "base", "attributes", Map.of("spec", "dockerfile", "image", "acme/base:1.0"))))));
        }

        // 1 shared interface JAR + 5 utility JARs + 1 POM base image utility (with EXPOSE 8080 in its base Dockerfile)
        List<String> jarLibDirs = List.of(
                "interfaces/acme.entities",
                "utilities/acme.probes",
                "utilities/acme.registryclient",
                "utilities/acme.memoryconfig",
                "utilities/acme.common",
                "utilities/acme.metrics");
        for (String dir : jarLibDirs) {
            putNode(store, root, id("mod:" + dir), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                    Map.of("id", "MAVEN:" + dir, "directory", dir, "languages", List.of("java"))));
            putNode(store, root, id("pkg:" + dir), "SOURCE_CONFIG_PROPERTY", GraphEvidence.capture("polyglot.source-syntax",
                    Map.of("fields", Map.of("path", b64(dir + "/pom.xml"),
                            "fact", Map.of("name", "build.packaging", "nodeKind", "build_descriptor_metadata",
                                    "attributes", Map.of("spec", "build-descriptor", "key", "build.packaging", "value", "jar", "executable", "false"))))));
        }
        String baseDir = "utilities/acme.dockerbase";
        putNode(store, root, id("mod:" + baseDir), "BUILD_COMPONENT", GraphEvidence.capture("polyglot.build-component",
                Map.of("id", "MAVEN:" + baseDir, "directory", baseDir, "languages", List.of("java"))));
        putNode(store, root, id("pkg:" + baseDir), "SOURCE_CONFIG_PROPERTY", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", b64(baseDir + "/pom.xml"),
                        "fact", Map.of("name", "build.packaging", "nodeKind", "build_descriptor_metadata",
                                "attributes", Map.of("spec", "build-descriptor", "key", "build.packaging", "value", "pom", "executable", "false"))))));
        putNode(store, root, id("df:" + baseDir), "SOURCE_INFRA_BASE_IMAGE", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", b64(baseDir + "/Dockerfile"),
                        "fact", Map.of("name", "base", "attributes", Map.of("spec", "dockerfile", "image", "tomcat:9"))))));
        putNode(store, root, id("port:" + baseDir), "SOURCE_INFRA_PORT", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", b64(baseDir + "/Dockerfile"),
                        "fact", Map.of("name", "8080", "attributes", Map.of("spec", "dockerfile", "port", "8080"))))));

        // 13 declarative workloads across Compose (7: 6 services + db) and K8s (6 services + db => 14 or 13 total):
        // Compose: 6 services + db (7)
        for (String svc : coreServices) {
            putNode(store, root, id("compose:" + svc), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax",
                    Map.of("fields", Map.of("path", b64("examples/docker/docker-compose.yaml"),
                            "fact", Map.of("name", svc, "nodeKind", "compose_service",
                                    "attributes", Map.of("spec", "docker-compose",
                                            "buildDir", "services/acme." + svc,
                                            "image", "acme/shop-" + svc + ":1.0"))))));
        }
        putNode(store, root, id("compose:db"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", b64("examples/docker/docker-compose.yaml"),
                        "fact", Map.of("name", "db", "nodeKind", "compose_service",
                                "attributes", Map.of("spec", "docker-compose", "image", "acme/shop-db:1.0"))))));

        // K8s Deployments: 6 services + db (prefixed "shop-*", matching real K8s manifests)
        for (String svc : coreServices) {
            putNode(store, root, id("k8s:" + svc), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax",
                    Map.of("fields", Map.of("path", b64("examples/kubernetes/shop-" + svc + ".yaml"),
                            "fact", Map.of("name", "shop-" + svc, "nodeKind", "k8s_deployment",
                                    "attributes", Map.of("spec", "kubernetes", "image", "acme/shop-" + svc + ":1.0"))))));
        }
        putNode(store, root, id("k8s:db"), "SOURCE_DECLARATION", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of("path", b64("examples/kubernetes/shop-db.yaml"),
                        "fact", Map.of("name", "shop-db", "nodeKind", "k8s_deployment",
                                "attributes", Map.of("spec", "kubernetes", "image", "acme/shop-db:1.0"))))));

        // 48 ENDPOINT nodes across the 6 services (8 endpoints per service)
        for (String svc : coreServices) {
            for (int ep = 1; ep <= 8; ep++) {
                putNode(store, root, id("ep:" + svc + ":" + ep), "ENDPOINT", GraphEvidence.capture("spring.endpoint-declaration",
                        Map.of("route", Map.of("path", "/api/" + svc + "/op" + ep, "methods", List.of("GET")))));
            }
        }

        DeclarativeTopologyProjection.project(store, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);

        var nodes = store.records("nodes", GraphSnapshotCodec::node);
        var evidence = store.records("evidence", GraphSnapshotCodec::evidence);

        // Count logical deployable services (excluding AGGREGATOR & TEST_ONLY_MODULE) + INFRA_COMPONENT
        var deployableServices = new TreeSet<String>();
        int sharedLibraryCount = 0;
        int endpointCount = 0;
        for (var n : nodes.values()) {
            switch (n.kind()) {
                case "ENDPOINT" -> endpointCount++;
                case "SHARED_LIBRARY_MODULE" -> sharedLibraryCount++;
                case "BUILD_COMPONENT", "SERVICE_COMPONENT" -> {
                    if (isAggregatorOrTestOnly(n, evidence)) continue;
                    String svcName = extractServiceName(n, evidence);
                    deployableServices.add(svcName);
                }
                case "INFRA_COMPONENT" -> {
                    String infraName = extractInfraName(n, evidence);
                    deployableServices.add(infraName);
                }
            }
        }

        // Consolidates 28+ raw components -> 7 logical services while preserving all 48 endpoints!
        assertEquals(Set.of("auth", "db", "image", "persistence", "recommender", "registry", "webui"),
                deployableServices, "Expected exactly 7 logical services after cross-layer dedup");
        assertEquals(7, sharedLibraryCount, "All 6 library JARs + 1 POM base utility must be SHARED_LIBRARY_MODULE");
        assertEquals(48, endpointCount, "All 48 endpoints must be 100% preserved");
        assertTrue(hasTestOnlyClassification(nodes.get(root + "|" + id("mod:e2e")), evidence));

        // Verify evidenceLayers provenance on all 6 WAR services + DB
        for (String svc : coreServices) {
            assertTrue(hasServiceIdentity(nodes.get(root + "|" + id("mod:" + svc)), evidence,
                    svc, List.of("BUILD", "COMPOSE", "K8S")), "Expected [BUILD, COMPOSE, K8S] on " + svc);
        }
        var dbNodes = nodes.values().stream().filter(n -> "INFRA_COMPONENT".equals(n.kind())).toList();
        assertEquals(1, dbNodes.size(), "Compose db + K8s shop-db must unify into 1 INFRA_COMPONENT");
        assertTrue(hasInfraEvidenceLayers(dbNodes.getFirst(), evidence, List.of("COMPOSE", "K8S")));

        // Determinism check (I2): re-projecting onto the same store is idempotent
        var beforeNodes = new TreeMap<>(nodes);
        var beforeEdges = new TreeMap<>(store.records("edges", GraphSnapshotCodec::edge));
        DeclarativeTopologyProjection.project(store, root, ServiceArchitectureProjection.ProjectionProfile.STRICT, () -> false);
        assertEquals(beforeNodes, store.records("nodes", GraphSnapshotCodec::node));
        assertEquals(beforeEdges, store.records("edges", GraphSnapshotCodec::edge));
    }

    private static boolean hasServiceIdentity(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence,
            String expectedName, List<String> expectedLayers) {
        if (node == null) return false;
        for (var ref : node.evidence()) {
            var ev = evidence.get(ref.value());
            if (ev != null && "architecture.service-identity".equals(ev.family())) {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                if (expectedName.equals(obj.get("serviceName")) && expectedLayers.equals(obj.get("evidenceLayers"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasInfraEvidenceLayers(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence,
            List<String> expectedLayers) {
        for (var ref : node.evidence()) {
            var ev = evidence.get(ref.value());
            if (ev != null && "architecture.infra-component".equals(ev.family())) {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                if (expectedLayers.equals(obj.get("evidenceLayers"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTestOnlyClassification(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence) {
        if (node == null) return false;
        for (var ref : node.evidence()) {
            var ev = evidence.get(ref.value());
            if (ev != null && "architecture.component-classification".equals(ev.family())) {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                if (Boolean.TRUE.equals(obj.get("isTestOnlyModule")) && "TEST_ONLY_MODULE".equals(obj.get("role"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAggregatorOrTestOnly(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence) {
        for (var ref : node.evidence()) {
            var ev = evidence.get(ref.value());
            if (ev == null) continue;
            if ("architecture.component-classification".equals(ev.family())) {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                if (Boolean.TRUE.equals(obj.get("isTestOnlyModule"))) return true;
            }
            if ("polyglot.build-component".equals(ev.family())) {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                if (".".equals(obj.get("directory"))) return true;
            }
        }
        return false;
    }

    private static String extractServiceName(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence) {
        for (var ref : node.evidence()) {
            var ev = evidence.get(ref.value());
            if (ev != null && "architecture.service-identity".equals(ev.family())) {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                if (obj.get("serviceName") instanceof String s) return s;
            }
        }
        return node.identity();
    }

    private static String extractInfraName(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence) {
        for (var ref : node.evidence()) {
            var ev = evidence.get(ref.value());
            if (ev != null && "architecture.infra-component".equals(ev.family())) {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                if (obj.get("name") instanceof String s) return s;
            }
        }
        return node.identity();
    }
}
