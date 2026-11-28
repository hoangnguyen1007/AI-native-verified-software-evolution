package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.readiness.ReadinessPlan;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Projects standard declarative cloud-native topology (Docker Compose, Kubernetes, Spring
 * Cloud Gateway/Zuul, Apache/Nginx reverse proxies, Spring config references, Elastic Filebeat,
 * Prometheus scrape targets, Grafana datasources, and Java Feign/Discovery/RestTemplate calls)
 * plus opt-in DFD-compatibility rules under {@link ServiceArchitectureProjection.ProjectionProfile#DFD_EXPORT}.
 * <p>
 * {@link ServiceArchitectureProjection.ProjectionProfile#STRICT} emits only directly evidenced
 * components and caller-&gt;callee dependency edges. Uses targeted {@code valuesByKind} lookups
 * (never full node/edge table scans).
 */
public final class DeclarativeTopologyProjection {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("architecture.declarative-topology", "m8.4-v1");

    /**
     * Generic architectural protocol roles driving {@link ServiceArchitectureProjection.ProjectionProfile#DFD_EXPORT}
     * orientation under {@link ServiceArchitectureProjection.EvidenceConcordance#DERIVED_DFD_ORIENTATION}.
     * Derived strictly from open-standard protocol/framework roles (zero repository/app-specific exceptions).
     */
    public enum ProtocolOrientationRole {
        DATA_STORE,
        CONFIG_SERVER,
        DISCOVERY_REGISTRY,
        BROKER_CONSUMER,
        CONFIG_SOURCE_REPO,
        OBSERVABILITY_PIPELINE,
        CONTROL_PLANE_ADMIN,
        AUTH_PROVIDER,
        COORDINATION_SERVICE,
        DFD_POLICY_SYNTHESIS
    }

    private static final Set<String> DEPLOYMENT_LAYERS = Set.of("COMPOSE", "K8S");
    private static final Set<String> APPLICATION_LAYERS = Set.of(
            "BUILD", "SPRING_CONFIG", "SOURCE_SYNTAX", "PROXY_CONFIG", "PROMETHEUS_CONFIG");

    private static final Pattern ROOT_AGGREGATOR_ID = Pattern.compile("^[A-Za-z0-9_-]+:\\.(?:->\\.)?$");
    private static final String IDE_DESCRIPTOR_PREFIX = ReadinessPlan.Family.IDE.name() + ":";
    private static final Pattern IPV4_HOST = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

    private static final Set<String> KNOWN_INFRA_NAMES = Set.of(
            "mysql", "postgres", "postgresql", "mariadb", "mongodb", "mongo", "h2", "hsqldb", "sqlite",
            "redis", "valkey", "memcached", "cassandra", "couchdb", "neo4j", "clickhouse", "influxdb", "opensearch",
            "rabbitmq", "kafka", "nats", "activemq", "zookeeper", "zipkin",
            "jaeger", "prometheus", "grafana", "smtp", "mail", "mailhog", "maildev", "mailpit", "postfix", "git",
            "github-repository", "gitlab-repository", "git-repository",
            "config", "registry", "eureka", "consul",
            "elasticsearch", "kibana", "logstash",
            "apache", "httpd", "nginx");

    private static final Set<String> INFRA_IMAGES = Set.of(
            "mysql", "postgres", "mariadb", "mongo", "redis", "valkey", "memcached",
            "cassandra", "couchdb", "neo4j", "clickhouse", "influxdb", "opensearch",
            "rabbitmq", "kafka", "nats", "activemq", "zookeeper", "openzipkin", "zipkin", "jaeger",
            "prom/prometheus", "prometheus", "grafana", "mailhog", "maildev", "mailpit", "postfix",
            "elasticsearch", "kibana", "logstash", "elk", "consul",
            "nginx", "httpd", "apache", "envoy", "traefik", "haproxy");

    private static final Set<String> DATASTORE_TOKENS = Set.of(
            "mysql", "postgres", "postgresql", "mariadb", "mongodb", "mongo", "h2", "hsqldb", "sqlite",
            "redis", "valkey", "memcached", "cassandra", "couchdb", "neo4j", "clickhouse", "influxdb", "opensearch");

    private static final Set<String> DATASTORE_IMAGE_TOKENS = Set.of(
            "mysql", "postgres", "mariadb", "mongo", "redis", "valkey", "memcached", "h2", "hsqldb", "sqlite",
            "cassandra", "couchdb", "neo4j", "oracle", "mssql", "clickhouse", "influxdb", "opensearch");

    private static final Set<String> EMBEDDED_DB_TOKENS = Set.of("h2", "hsqldb", "sqlite");

    private static final Set<String> BROKER_TOKENS = Set.of("rabbitmq", "rabbit", "kafka", "nats", "activemq");

    private static final Set<String> REVERSE_PROXY_TOKENS = Set.of(
            "apache", "httpd", "nginx", "envoy", "traefik", "haproxy");

    private static final Set<String> GIT_REPO_TOKENS = Set.of(
            "github-repository", "gitlab-repository", "git-repository", "git");

    private static final Set<String> NON_INGRESS_INFRA_TOKENS = Set.of(
            "mysql", "postgres", "postgresql", "mariadb", "mongodb", "mongo", "h2", "hsqldb", "sqlite",
            "db", "database", "redis", "valkey", "memcached", "cassandra", "couchdb", "neo4j",
            "clickhouse", "influxdb", "opensearch",
            "rabbitmq", "rabbit", "kafka", "nats", "activemq", "zookeeper", "zipkin", "jaeger",
            "prometheus", "grafana", "smtp", "mail", "mailhog", "maildev", "mailpit", "postfix", "git",
            "github-repository", "gitlab-repository", "git-repository",
            "elasticsearch", "kibana", "logstash", "elk", "consul",
            "eureka", "registry", "config");

    private static final Set<String> DATASTORE_WELL_KNOWN_PORTS = Set.of(
            "6379", "5432", "3306", "27017", "11211", "9042", "5984", "7687");

    private static final Set<String> BROKER_WELL_KNOWN_PORTS = Set.of(
            "5672", "9092", "4222", "61616");

    private static final Set<String> SMTP_WELL_KNOWN_PORTS = Set.of(
            "25", "465", "587", "1025", "2525");

    private static final Set<String> OBSERVABILITY_AGENT_TOKENS = Set.of(
            "cadvisor", "weave", "weave-scope", "promtail", "filebeat", "metricbeat",
            "fluentd", "fluentbit", "fluent-bit", "logspout", "vector", "telegraf",
            "datadog", "otel", "opentelemetry", "collector", "monitor", "monitoring",
            "probe", "exporter", "agent", "inspector", "glances", "netdata");

    private static final Set<String> TEST_MODULE_DIR_NAMES = Set.of(
            "test", "tests", "spec", "specs", "unit-tests", "unit-test",
            "integration-tests", "integration-test", "e2e-tests", "e2e-test",
            "functional-tests", "acceptance-tests");

    private static final Set<String> LAYOUT_CODE_SUBDIRS = Set.of(
            "src", "app", "main", "source");

    private static final Set<String> ZUUL_EXCLUDED_TARGET_ROLES = Set.of(
            "EUREKA_SERVER", "CONFIG_SERVER", "ADMIN_SERVER", "HYSTRIX_DASHBOARD",
            "TURBINE", "TURBINE_STREAM", "ZIPKIN_SERVER", "ZIPKIN_STREAM", "ZIPKIN_STREAM_SERVER",
            "ZUUL_PROXY", "API_GATEWAY");

    private static final Set<String> CONTROL_PLANE_ROLES = Set.of(
            "EUREKA_SERVER", "CONFIG_SERVER", "ADMIN_SERVER", "HYSTRIX_DASHBOARD",
            "TURBINE", "TURBINE_STREAM", "ZIPKIN_SERVER", "ZIPKIN_STREAM", "ZIPKIN_STREAM_SERVER",
            "OAUTH2_AUTH_SERVER");

    private static final Set<String> FRONTEND_BUILD_TOOLS = Set.of(
            "NPM", "YARN", "PNPM", "NODE", "VITE", "WEBPACK", "ANGULAR", "NEXT");

    private static final List<String> GENERIC_ROLE_SUFFIXES = List.of(
            "-microservice", "-service", "-server", "-ms", "-app", "-dashboard", "-registry");

    private static final List<String> COLLAPSED_ROLE_SUFFIXES = List.of(
            "microservice", "service", "server", "dashboard", "registry", "app");

    private static final Set<String> NON_BUILD_SYNTAX_KINDS = Set.of(
            "SOURCE_DECLARATION",
            "SOURCE_CONFIG_SERVICE_IDENTITY",
            "SOURCE_INFRA_BASE_IMAGE",
            "SOURCE_INFRA_PORT",
            "SOURCE_INFRA_DEPENDENCY",
            "SOURCE_INFRA_ENV_REF",
            "SOURCE_INFRA_CONFIG_REF",
            "SOURCE_INFRA_SCRAPE_TARGET",
            "SOURCE_GATEWAY_ROUTE",
            "SOURCE_SPRING_CLOUD_ROLE",
            "SOURCE_CONFIG_PROPERTY",
            "SOURCE_HTTP_CALL_CANDIDATE",
            "SOURCE_INFRA_DEPENDENCY_STARTER",
            "SOURCE_EVENT_PRODUCER_CANDIDATE",
            "SOURCE_EVENT_CONSUMER_CANDIDATE");

    private static final Set<String> ENDPOINT_AND_RPC_KINDS = Set.of(
            "ENDPOINT",
            "ROUTE_CANDIDATE",
            "SOURCE_RPC_HANDLER_CANDIDATE",
            "SOURCE_RPC_CALL_CANDIDATE");

    private static final Set<String> ENDPOINT_EVIDENCE_FAMILIES = Set.of(
            "spring.endpoint-declaration",
            "jaxrs.endpoint-declaration",
            "servlet.endpoint-declaration");

    private record BuildCompInfo(KnowledgeGraph.Node node, String rawDir, String normDir, String compId,
            String fallbackName, String tool, Set<String> languages, ContentDigest proof) {
        boolean isFrontendTool() {
            return FRONTEND_BUILD_TOOLS.contains(tool);
        }
    }

    private record ServiceEntry(String nodeId, String canonicalName, String dirPrefix, boolean hasCodeBuild,
            boolean frontendBuild) {}

    private record InfraOriginMeta(String name, String image, String spec, ContentDigest sourceProof) {}

    private record IdentityOrigin(String serviceName, String spec, ContentDigest sourceEvidence) {}

    private static final class PendingEdge {
        final ServiceEntry src;
        final ServiceEntry dst;
        final String primaryMechanism;
        final String trust;
        final boolean dfdDerived;
        final ProtocolOrientationRole orientationRole;
        boolean hasDirectEvidence;
        final Set<String> mechanisms = new TreeSet<>();
        final Set<ContentDigest> supports = new TreeSet<>();
        final Set<String> layers = new TreeSet<>();

        PendingEdge(ServiceEntry src, ServiceEntry dst, String mechanism, String trust,
                boolean dfdDerived, ProtocolOrientationRole orientationRole,
                boolean directEvidence, ContentDigest support, String layer) {
            this.src = src;
            this.dst = dst;
            this.primaryMechanism = mechanism;
            this.trust = trust;
            this.dfdDerived = dfdDerived;
            this.orientationRole = orientationRole;
            this.hasDirectEvidence = directEvidence;
            if (mechanism != null && !mechanism.isEmpty()) this.mechanisms.add(mechanism);
            if (support != null) this.supports.add(support);
            if (layer != null && !layer.isEmpty()) this.layers.add(layer);
        }

        void mergeCorroboration(String mechanism, ContentDigest support, String layer, boolean directEvidence) {
            if (directEvidence) {
                this.hasDirectEvidence = true;
            }
            if (mechanism != null && !mechanism.isEmpty()) this.mechanisms.add(mechanism);
            if (support != null) this.supports.add(support);
            if (layer != null && !layer.isEmpty()) this.layers.add(layer);
        }
    }

    private record ConfigIdentitySite(KnowledgeGraph.Node node, String dir, String path, String serviceName,
            String configRepoFile, ContentDigest proof) {}

    private record DeclSite(KnowledgeGraph.Node node, String rawName, String dir, String path, String doc,
            String slot, String nodeKind, Map<String, Object> attrs, ContentDigest proof) {}

    private record PortSite(KnowledgeGraph.Node node, String doc, String dir, String path, String owner,
            String nodeKind, String port, boolean externalPort, String configRepoFile, String spec, ContentDigest proof) {}

    private record RefSite(KnowledgeGraph.Node node, String doc, String dir, String path, String owner,
            String nodeKind, String role, String target, String port, String configRepoFile, Map<String, Object> attrs,
            ContentDigest proof) {}

    private record RouteSite(KnowledgeGraph.Node node, String doc, String dir, String path, String nodeKind,
            String target, String port, String configRepoFile, Map<String, Object> attrs, ContentDigest proof) {}

    private record RoleSite(KnowledgeGraph.Node node, String doc, String dir, String role, ContentDigest proof) {}

    private record PropSite(KnowledgeGraph.Node node, String doc, String dir, String path, String nodeKind,
            String key, String value, String target, String port, String configRepoFile,
            Map<String, Object> attrs, ContentDigest proof) {}

    private record HttpCallSite(KnowledgeGraph.Node node, String doc, String dir, String uri, String targetService,
            String propertyKey, Map<String, Object> attrs, ContentDigest proof) {}

    private record StarterSite(KnowledgeGraph.Node node, String doc, String dir, String starter, ContentDigest proof) {}

    private record MonitorSite(ServiceEntry service, ContentDigest proof) {}

    private static final class RoleContext {
        final Map<String, Set<String>> rolesByNodeId = new HashMap<>();
        final Map<String, String> infraImageByNodeId = new HashMap<>();
        final Map<String, Set<String>> portsByNodeId = new HashMap<>();
        final Map<String, Set<String>> eventProducerProtosByNodeId = new HashMap<>();
        final Map<String, Set<String>> eventConsumerProtosByNodeId = new HashMap<>();
        final Set<String> eurekaRegisterFalse = new HashSet<>();
        final Set<String> eurekaFetchFalse = new HashSet<>();
        final Set<String> reverseProxyNodeIds = new HashSet<>();
        final Set<String> gatewayRouteOwnerNodeIds = new HashSet<>();
        final Set<String> gatewayRouteToAuthNodeIds = new HashSet<>();
        final Set<String> infraNodeIds = new HashSet<>();
        ServiceEntry configServer;
        ServiceEntry eurekaServer;
        ServiceEntry zipkinServer;
        ServiceEntry adminServer;
        ServiceEntry authServer;
        ServiceEntry hystrixDashboard;
        ServiceEntry turbineServer;

        void addRole(String nodeId, String role) {
            if (nodeId != null && !nodeId.isEmpty() && role != null && !role.isEmpty()) {
                rolesByNodeId.computeIfAbsent(nodeId, k -> new TreeSet<>()).add(role);
            }
        }

        void recordImage(String nodeId, String image) {
            if (nodeId != null && !nodeId.isEmpty() && image != null && !image.isBlank()) {
                infraImageByNodeId.putIfAbsent(nodeId, image.trim());
            }
        }

        void recordPort(String nodeId, String port) {
            if (nodeId != null && !nodeId.isEmpty() && port != null && port.matches("\\d+")) {
                portsByNodeId.computeIfAbsent(nodeId, k -> new TreeSet<>()).add(port);
            }
        }

        boolean hasAnyPort(ServiceEntry se, Set<String> wantedPorts) {
            if (se == null) return false;
            var ports = portsByNodeId.getOrDefault(se.nodeId(), Set.of());
            for (String p : wantedPorts) {
                if (ports.contains(p)) return true;
            }
            return false;
        }

        String imageOf(ServiceEntry se) {
            return se == null ? "" : infraImageByNodeId.getOrDefault(se.nodeId(), "");
        }

        boolean hasRole(ServiceEntry se, String role) {
            return se != null && rolesByNodeId.getOrDefault(se.nodeId(), Set.of()).contains(role);
        }

        boolean hasAnyRole(ServiceEntry se, Set<String> wanted) {
            if (se == null) return false;
            var roles = rolesByNodeId.getOrDefault(se.nodeId(), Set.of());
            for (String w : wanted) {
                if (roles.contains(w)) return true;
            }
            return false;
        }

        boolean isConfigServer(ServiceEntry se) {
            if (se == null) return false;
            return (configServer != null && configServer.nodeId().equals(se.nodeId()))
                    || hasRole(se, "CONFIG_SERVER")
                    || se.canonicalName().equals("config") || se.canonicalName().equals("config-server");
        }

        boolean isConsul(ServiceEntry se) {
            if (se == null) return false;
            String n = se.canonicalName();
            return hasToken(n, "consul") || n.startsWith("consul")
                    || imageOf(se).toLowerCase(Locale.ROOT).contains("consul");
        }

        boolean isDiscovery(ServiceEntry se) {
            if (se == null) return false;
            return (eurekaServer != null && eurekaServer.nodeId().equals(se.nodeId()))
                    || hasRole(se, "EUREKA_SERVER")
                    || hasToken(se.canonicalName(), "eureka") || isConsul(se)
                    || se.canonicalName().equals("registry") || se.canonicalName().equals("discovery")
                    || se.canonicalName().equals("discovery-server") || se.canonicalName().equals("discovery-service");
        }

        boolean isGateway(ServiceEntry se) {
            if (se == null) return false;
            return hasRole(se, "ZUUL_PROXY") || hasRole(se, "API_GATEWAY")
                    || gatewayRouteOwnerNodeIds.contains(se.nodeId())
                    || reverseProxyNodeIds.contains(se.nodeId())
                    || hasToken(se.canonicalName(), "gateway") || hasToken(se.canonicalName(), "zuul");
        }

        boolean isAdminServer(ServiceEntry se) {
            if (se == null) return false;
            return (adminServer != null && adminServer.nodeId().equals(se.nodeId()))
                    || hasRole(se, "ADMIN_SERVER");
        }

        boolean isAuthServer(ServiceEntry se) {
            if (se == null) return false;
            return (authServer != null && authServer.nodeId().equals(se.nodeId()))
                    || hasRole(se, "OAUTH2_AUTH_SERVER");
        }

        boolean isOAuth2Resource(ServiceEntry se) {
            return hasRole(se, "OAUTH2_RESOURCE") || hasRole(se, "OAUTH2_SSO");
        }

        boolean isHystrixOrTurbine(ServiceEntry se) {
            if (se == null) return false;
            return (hystrixDashboard != null && hystrixDashboard.nodeId().equals(se.nodeId()))
                    || (turbineServer != null && turbineServer.nodeId().equals(se.nodeId()))
                    || hasAnyRole(se, Set.of("HYSTRIX_DASHBOARD", "TURBINE", "TURBINE_STREAM"))
                    || hasToken(se.canonicalName(), "hystrix") || hasToken(se.canonicalName(), "turbine");
        }

        boolean isStreamConsumer(ServiceEntry se) {
            return hasAnyRole(se, Set.of("TURBINE_STREAM", "ZIPKIN_STREAM_SERVER"));
        }

        boolean isGitRepo(ServiceEntry se) {
            if (se == null) return false;
            return GIT_REPO_TOKENS.contains(se.canonicalName());
        }

        boolean hasDatastoreImage(ServiceEntry se) {
            String img = imageOf(se).toLowerCase(Locale.ROOT);
            if (img.isEmpty()) return false;
            for (String tok : DATASTORE_IMAGE_TOKENS) {
                if (img.contains(tok)) return true;
            }
            return false;
        }

        boolean isDatastore(ServiceEntry se) {
            if (se == null || se.hasCodeBuild()) return false;
            String n = se.canonicalName();
            return matchesAnyToken(n, DATASTORE_TOKENS)
                    || hasToken(n, "db") || hasToken(n, "database")
                    || hasDatastoreImage(se)
                    || hasAnyPort(se, DATASTORE_WELL_KNOWN_PORTS);
        }

        boolean isBroker(ServiceEntry se) {
            if (se == null || se.hasCodeBuild()) return false;
            String n = se.canonicalName();
            String img = imageOf(se).toLowerCase(Locale.ROOT);
            return matchesAnyToken(n, BROKER_TOKENS)
                    || img.contains("rabbitmq") || img.contains("kafka") || img.contains("nats") || img.contains("activemq")
                    || hasAnyPort(se, BROKER_WELL_KNOWN_PORTS);
        }

        String brokerProtocolOf(ServiceEntry broker) {
            if (broker == null) return "";
            String combined = (broker.canonicalName() + " " + imageOf(broker)).toLowerCase(Locale.ROOT);
            if (combined.contains("rabbitmq") || combined.contains("amqp") || hasToken(broker.canonicalName(), "rabbit")) {
                return "RABBITMQ";
            }
            if (combined.contains("kafka")) return "KAFKA";
            if (combined.contains("nats")) return "NATS";
            if (combined.contains("redis")) return "REDIS";
            return "";
        }

        boolean isPureBrokerConsumer(ServiceEntry caller, ServiceEntry broker) {
            if (caller == null || broker == null) return false;
            String proto = brokerProtocolOf(broker);
            if (proto.isEmpty()) return false;
            var cons = eventConsumerProtosByNodeId.getOrDefault(caller.nodeId(), Set.of());
            var prods = eventProducerProtosByNodeId.getOrDefault(caller.nodeId(), Set.of());
            boolean consumes = matchesBrokerProtocol(cons, proto);
            boolean produces = matchesBrokerProtocol(prods, proto);
            return consumes && !produces;
        }

        private static boolean matchesBrokerProtocol(Set<String> protos, String brokerProto) {
            if (protos.contains(brokerProto)) return true;
            return ("KAFKA".equals(brokerProto) || "RABBITMQ".equals(brokerProto))
                    && protos.contains("SPRING_CLOUD_STREAM");
        }

        boolean isPrometheus(ServiceEntry se) {
            return se != null && (hasToken(se.canonicalName(), "prometheus")
                    || imageOf(se).toLowerCase(Locale.ROOT).contains("prometheus"));
        }

        boolean isGrafana(ServiceEntry se) {
            return se != null && (hasToken(se.canonicalName(), "grafana")
                    || imageOf(se).toLowerCase(Locale.ROOT).contains("grafana"));
        }

        boolean isElasticsearch(ServiceEntry se) {
            return se != null && (hasToken(se.canonicalName(), "elasticsearch") || se.canonicalName().equals("es")
                    || imageOf(se).toLowerCase(Locale.ROOT).contains("elasticsearch"));
        }

        boolean isKibana(ServiceEntry se) {
            return se != null && (hasToken(se.canonicalName(), "kibana")
                    || imageOf(se).toLowerCase(Locale.ROOT).contains("kibana"));
        }

        boolean isKafka(ServiceEntry se) {
            return se != null && (hasToken(se.canonicalName(), "kafka")
                    || imageOf(se).toLowerCase(Locale.ROOT).contains("kafka"));
        }

        boolean isZookeeper(ServiceEntry se) {
            return se != null && (hasToken(se.canonicalName(), "zookeeper")
                    || imageOf(se).toLowerCase(Locale.ROOT).contains("zookeeper"));
        }

        boolean isEurekaDisabled(ServiceEntry se) {
            return se != null && eurekaRegisterFalse.contains(se.nodeId()) && eurekaFetchFalse.contains(se.nodeId());
        }

        boolean fetchesOnlyDiscovery(ServiceEntry se) {
            return se != null && eurekaRegisterFalse.contains(se.nodeId()) && !eurekaFetchFalse.contains(se.nodeId());
        }

        boolean isControlPlane(ServiceEntry se) {
            if (se == null) return false;
            if (hasAnyRole(se, CONTROL_PLANE_ROLES)) return true;
            return (configServer != null && configServer.nodeId().equals(se.nodeId()))
                    || (eurekaServer != null && eurekaServer.nodeId().equals(se.nodeId()))
                    || (zipkinServer != null && zipkinServer.nodeId().equals(se.nodeId()))
                    || (adminServer != null && adminServer.nodeId().equals(se.nodeId()))
                    || (authServer != null && authServer.nodeId().equals(se.nodeId()))
                    || (hystrixDashboard != null && hystrixDashboard.nodeId().equals(se.nodeId()))
                    || (turbineServer != null && turbineServer.nodeId().equals(se.nodeId()));
        }
    }

    private DeclarativeTopologyProjection() {}

    public static void project(GraphProjectionStore store, String root,
            ServiceArchitectureProjection.ProjectionProfile profile, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        store.records("projection-metadata", GraphJson::object)
                .put(root + "|profile", Map.of("profile", profile.name()));

        var nodes = store.records("nodes", GraphSnapshotCodec::node);
        var edges = store.records("edges", GraphSnapshotCodec::edge);
        var evidence = store.records("evidence", GraphSnapshotCodec::evidence);
        var additions = store.records("declarative-topology-additions", GraphSnapshotCodec::node);
        var addedEdges = store.records("declarative-edge-additions", GraphSnapshotCodec::edge);

        // Track existing (source -> target) CALLS_SERVICE pairs so declarative edges avoid duplicating URI matches.
        var existingCalls = new HashSet<String>();
        for (var e : store.valuesByKind("edges", Set.of("CALLS_SERVICE"), GraphSnapshotCodec::edge, KnowledgeGraph.Edge::kind)) {
            for (String tgt : e.target().identities()) {
                existingCalls.add(e.source() + "->" + tgt);
            }
        }

        // Pass 1: collect all build-backed component nodes (including re-projected SERVICE_COMPONENT / SHARED_LIBRARY_MODULE)
        // so every syntax path resolves via longest-prefix matching and re-projection remains strictly idempotent (I2).
        var buildComps = new ArrayList<BuildCompInfo>();
        var buildCompsByDir = new TreeMap<String, BuildCompInfo>();
        var buildCompsByNodeId = new HashMap<String, BuildCompInfo>();
        for (var node : store.valuesByKind("nodes", Set.of("BUILD_COMPONENT", "SERVICE_COMPONENT", "SHARED_LIBRARY_MODULE"),
                GraphSnapshotCodec::node, KnowledgeGraph.Node::kind)) {
            check(cancelled);
            for (var id : node.evidence()) {
                var proof = evidence.get(id.value());
                if (proof == null || !proof.family().equals("polyglot.build-component")) continue;
                var fields = GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults()));
                String rawDir = str(fields.get("directory")).replace('\\', '/');
                String normDir = normDir(rawDir);
                String compId = fields.get("id") instanceof String s ? s : rawDir;
                if (isNonDeployableBuildDescriptor(compId, normDir)) continue;
                String tool = str(fields.get("tool"));
                if (tool.isEmpty()) tool = str(fields.get("kind"));
                if (tool.isEmpty()) {
                    int colon = compId.indexOf(':');
                    if (colon > 0) tool = compId.substring(0, colon);
                }
                var langs = new TreeSet<String>();
                if (fields.get("languages") instanceof List<?> rawLangs) {
                    for (Object l : rawLangs) {
                        if (l instanceof String ls && !ls.isBlank()) {
                            langs.add(ls.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
                String fallbackName = normalizeName(baseOrDirName(rawDir, compId));
                var info = new BuildCompInfo(node, rawDir, normDir, compId, fallbackName,
                        tool.toUpperCase(Locale.ROOT), Set.copyOf(langs), id);
                buildComps.add(info);
                buildCompsByNodeId.put(node.identity(), info);
                if (!normDir.isEmpty()) {
                    buildCompsByDir.putIfAbsent(normDir, info);
                }
                break;
            }
        }

        var buildDirsByLength = buildCompsByDir.keySet().stream()
                .filter(d -> !d.isEmpty())
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();

        // Pass 2: collect all syntax facts with O(depth) longest-prefix directory attribution and test-source filtering.
        var configIdentities = new ArrayList<ConfigIdentitySite>();
        var declSites = new ArrayList<DeclSite>();
        var docSlotToImage = new HashMap<String, String>();
        var dockerfileDirToImage = new HashMap<String, String>();
        var dockerfilePathByDir = new TreeMap<String, String>();
        var portSites = new ArrayList<PortSite>();
        var envOrDepRefs = new ArrayList<RefSite>();
        var configRefs = new ArrayList<RefSite>();
        var scrapeTargets = new ArrayList<RefSite>();
        var gatewayRoutes = new ArrayList<RouteSite>();
        var springRoles = new ArrayList<RoleSite>();
        var roleProofByDirAndRole = new HashMap<String, Map<String, ContentDigest>>();
        var configProps = new ArrayList<PropSite>();
        var httpCalls = new ArrayList<HttpCallSite>();
        var starterSites = new ArrayList<StarterSite>();
        var eventProducerProtosByDir = new HashMap<String, Set<String>>();
        var eventConsumerProtosByDir = new HashMap<String, Set<String>>();
        var packagingByDir = new HashMap<String, String>();
        var executableDirs = new HashSet<String>();
        var descriptorTestProjectDirs = new HashSet<String>();
        var dirsWithEndpointsOrRoutes = new HashSet<String>();
        var dirsWithServiceCallsOrRpc = new HashSet<String>();
        var routePrefixesByDir = new HashMap<String, Set<String>>();

        for (var node : store.valuesByKind("nodes", NON_BUILD_SYNTAX_KINDS, GraphSnapshotCodec::node, KnowledgeGraph.Node::kind)) {
            check(cancelled);
            var parsedOpt = readSyntax(node, evidence);
            if (parsedOpt.isEmpty()) continue;
            var info = parsedOpt.orElseThrow();
            String path = str(info.get("_path"));
            var fact = GraphJson.object(info.get("fact"));
            var attrs = fact.get("attributes") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            if (isTestSource(attrs, path)) continue;

            String dir = resolveServiceDirFast(path, buildCompsByDir);
            String doc = str(info.get("_document"));
            String nodeKind = str(fact.get("nodeKind"));
            String name = str(fact.get("name"));
            ContentDigest proof = (ContentDigest) info.get("_proof");
            String configRepoFile = str(attrs.get("configRepoFile"));

            switch (node.kind()) {
                case "SOURCE_CONFIG_SERVICE_IDENTITY" -> {
                    String svcName = attrs.get("serviceName") instanceof String s && !s.isBlank() ? s.trim() : name.trim();
                    if (!svcName.isEmpty() && !svcName.contains("${")) {
                        configIdentities.add(new ConfigIdentitySite(node, dir, path, svcName, configRepoFile, proof));
                    }
                }
                case "SOURCE_DECLARATION" -> {
                    if ("compose_service".equals(nodeKind) || nodeKind.startsWith("k8s_")
                            || isProxyDecl(nodeKind, attrs)) {
                        var span = fact.get("span") instanceof Map<?, ?> sm ? GraphJson.object(sm) : Map.<String, Object>of();
                        String declSlot = span.containsKey("start") && span.containsKey("end")
                                ? span.get("start") + ":" + span.get("end") : "";
                        declSites.add(new DeclSite(node, name, dir, path, doc, declSlot, nodeKind, attrs, proof));
                    }
                }
                case "SOURCE_INFRA_BASE_IMAGE" -> {
                    String img = attrs.get("image") instanceof String s ? s : name;
                    String owner = str(fact.get("owner"));
                    if (!owner.isEmpty()) {
                        docSlotToImage.put(doc + "|" + owner, img);
                    }
                    // Only Dockerfile FROM instructions (never multi-service K8s/Compose manifests) associate a directory with a base image.
                    boolean isDockerfile = "dockerfile_from".equals(nodeKind)
                            || (owner.isEmpty() && path.toLowerCase(Locale.ROOT).contains("dockerfile"));
                    if (isDockerfile) {
                        docSlotToImage.putIfAbsent(doc + "|", img);
                        String parentDir = parentDirOf(path);
                        if (!parentDir.isEmpty()) {
                            dockerfileDirToImage.putIfAbsent(parentDir, img);
                            dockerfilePathByDir.putIfAbsent(parentDir, path);
                        }
                        if (!dir.isEmpty()) {
                            dockerfileDirToImage.putIfAbsent(dir, img);
                            dockerfilePathByDir.putIfAbsent(dir, path);
                        }
                    }
                }
                case "SOURCE_INFRA_PORT" -> {
                    String port = str(attrs.get("port"));
                    if (port.isEmpty()) port = name;
                    boolean external = "true".equalsIgnoreCase(str(attrs.get("externalPort"))) || "compose_port".equals(nodeKind);
                    portSites.add(new PortSite(node, doc, dir, path, str(fact.get("owner")), nodeKind,
                            port, external, configRepoFile, str(attrs.get("spec")), proof));
                }
                case "SOURCE_INFRA_DEPENDENCY", "SOURCE_INFRA_ENV_REF" ->
                        envOrDepRefs.add(new RefSite(node, doc, dir, path, str(fact.get("owner")), nodeKind, "",
                                str(attrs.get("target")), str(attrs.get("port")), configRepoFile, attrs, proof));
                case "SOURCE_INFRA_CONFIG_REF" ->
                        configRefs.add(new RefSite(node, doc, dir, path, str(fact.get("owner")), nodeKind, str(attrs.get("role")),
                                str(attrs.get("target")), str(attrs.get("port")), configRepoFile, attrs, proof));
                case "SOURCE_INFRA_SCRAPE_TARGET" ->
                        scrapeTargets.add(new RefSite(node, doc, dir, path, str(fact.get("owner")), nodeKind, "prometheus-scrape",
                                str(attrs.get("target")), str(attrs.get("port")), "", attrs, proof));
                case "SOURCE_GATEWAY_ROUTE" -> {
                    String target = str(attrs.get("targetService"));
                    if (target.isEmpty()) target = str(attrs.get("target"));
                    gatewayRoutes.add(new RouteSite(node, doc, dir, path, nodeKind, target, str(attrs.get("port")),
                            configRepoFile, attrs, proof));
                    String pathPrefix = str(attrs.get("pathPrefix"));
                    if (!pathPrefix.isEmpty() && !dir.isEmpty()) {
                        routePrefixesByDir.computeIfAbsent(dir, k -> new TreeSet<>()).add(pathPrefix);
                    }
                }
                case "SOURCE_SPRING_CLOUD_ROLE" -> {
                    String role = str(attrs.get("role"));
                    if (!role.isEmpty()) {
                        springRoles.add(new RoleSite(node, doc, dir, role, proof));
                        roleProofByDirAndRole.computeIfAbsent(dir, k -> new HashMap<>()).putIfAbsent(role, proof);
                    }
                }
                case "SOURCE_CONFIG_PROPERTY" -> {
                    String key = str(attrs.get("key"));
                    String value = str(attrs.get("value"));
                    if ("build_descriptor_metadata".equals(nodeKind)) {
                        String propDir = parentDirOf(path);
                        if ("build.isTestProject".equals(key) && "true".equalsIgnoreCase(value) && !propDir.isEmpty()) {
                            descriptorTestProjectDirs.add(propDir);
                        } else if ("build.packaging".equals(key) && !value.isEmpty()) {
                            String pkg = value.toLowerCase(Locale.ROOT);
                            boolean exec = "true".equalsIgnoreCase(str(attrs.get("executable")));
                            if (!propDir.isEmpty()) {
                                packagingByDir.putIfAbsent(propDir, pkg);
                                if (exec) executableDirs.add(propDir);
                            }
                            if (!dir.isEmpty() && exec) {
                                executableDirs.add(dir);
                            }
                        }
                    } else if (!key.isEmpty()) {
                        configProps.add(new PropSite(node, doc, dir, path, nodeKind, key, value, str(attrs.get("target")),
                                str(attrs.get("port")), configRepoFile, attrs, proof));
                    }
                }
                case "SOURCE_HTTP_CALL_CANDIDATE" -> {
                    String uri = unquoteLiteral(name);
                    String targetSvc = str(attrs.get("targetService"));
                    String propKey = str(attrs.get("propertyKey"));
                    if (!targetSvc.isEmpty() || !propKey.isEmpty() || !uri.isEmpty()) {
                        httpCalls.add(new HttpCallSite(node, doc, dir, uri, targetSvc, propKey, attrs, proof));
                        if (!dir.isEmpty()) dirsWithServiceCallsOrRpc.add(dir);
                    }
                }
                case "SOURCE_INFRA_DEPENDENCY_STARTER" -> {
                    String starter = str(attrs.get("starter")).toLowerCase(Locale.ROOT);
                    if (starter.isEmpty()) starter = name.toLowerCase(Locale.ROOT);
                    if (!starter.isEmpty()) {
                        starterSites.add(new StarterSite(node, doc, dir, starter, proof));
                    }
                }
                case "SOURCE_EVENT_PRODUCER_CANDIDATE" -> {
                    if (!Boolean.TRUE.equals(fact.get("recovered")) && !dir.isEmpty()) {
                        String proto = str(attrs.get("protocol")).toUpperCase(Locale.ROOT);
                        eventProducerProtosByDir.computeIfAbsent(dir, k -> new TreeSet<>())
                                .add(proto.isEmpty() ? "EVENT_BUS" : proto);
                    }
                }
                case "SOURCE_EVENT_CONSUMER_CANDIDATE" -> {
                    if (!Boolean.TRUE.equals(fact.get("recovered")) && !dir.isEmpty()) {
                        String proto = str(attrs.get("protocol")).toUpperCase(Locale.ROOT);
                        eventConsumerProtosByDir.computeIfAbsent(dir, k -> new TreeSet<>())
                                .add(proto.isEmpty() ? "EVENT_BUS" : proto);
                    }
                }
                default -> {}
            }
        }

        // Collect endpoint, route, and RPC presence by module directory (targeted kind lookup only).
        for (var node : store.valuesByKind("nodes", ENDPOINT_AND_RPC_KINDS, GraphSnapshotCodec::node, KnowledgeGraph.Node::kind)) {
            check(cancelled);
            if ("ENDPOINT".equals(node.kind())) {
                for (var id : node.evidence()) {
                    var proof = evidence.get(id.value());
                    if (proof == null || !ENDPOINT_EVIDENCE_FAMILIES.contains(proof.family())) continue;
                    var raw = GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults()));
                    var route = raw.get("route") instanceof Map<?, ?> rm ? GraphJson.object(rm) : Map.<String, Object>of();
                    String mod = str(route.get("module"));
                    if (mod.isEmpty()) mod = str(raw.get("module"));
                    String modDir = mod.isEmpty() ? "" : resolveServiceDirFast(mod, buildCompsByDir);
                    if (!modDir.isEmpty()) {
                        dirsWithEndpointsOrRoutes.add(modDir);
                        String routePath = str(route.get("path"));
                        if (!routePath.isEmpty()) {
                            routePrefixesByDir.computeIfAbsent(modDir, k -> new TreeSet<>()).add(routePath);
                        }
                    }
                    break;
                }
            } else {
                var parsedOpt = readSyntax(node, evidence);
                if (parsedOpt.isEmpty()) continue;
                var info = parsedOpt.orElseThrow();
                String path = str(info.get("_path"));
                var fact = GraphJson.object(info.get("fact"));
                var attrs = fact.get("attributes") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
                if (isTestSource(attrs, path)) continue;
                String dir = resolveServiceDirFast(path, buildCompsByDir);
                if (dir.isEmpty()) continue;
                if ("ROUTE_CANDIDATE".equals(node.kind())) {
                    dirsWithEndpointsOrRoutes.add(dir);
                    String routePath = unquoteLiteral(str(fact.get("name")));
                    if (!routePath.isEmpty()) {
                        routePrefixesByDir.computeIfAbsent(dir, k -> new TreeSet<>()).add(routePath);
                    }
                } else {
                    dirsWithServiceCallsOrRpc.add(dir);
                }
            }
        }

        // Compute test-only modules (dir name + .csproj IsTestProject/xunit/nunit) and aggregators.
        var testModuleDirs = new HashSet<String>();
        for (String d : buildDirsByLength) {
            if (isTestOnlyModuleDir(d) || descriptorTestProjectDirs.contains(d)) {
                testModuleDirs.add(d);
            }
        }

        var aggregatorDirs = new HashSet<String>();
        var foldedLayoutChildToParent = new HashMap<String, String>();
        for (String d : buildDirsByLength) {
            if (testModuleDirs.contains(d)) continue;
            var nonTestChildren = new ArrayList<String>();
            for (String other : buildDirsByLength) {
                if (!other.equals(d) && !testModuleDirs.contains(other) && other.startsWith(d + "/")) {
                    nonTestChildren.add(other);
                }
            }
            if (nonTestChildren.isEmpty()) continue;
            boolean singleLayoutSubdir = nonTestChildren.size() == 1
                    && LAYOUT_CODE_SUBDIRS.contains(nonTestChildren.getFirst().substring(d.length() + 1).toLowerCase(Locale.ROOT));
            if (singleLayoutSubdir) {
                foldedLayoutChildToParent.put(nonTestChildren.getFirst(), d);
            } else {
                aggregatorDirs.add(d);
            }
        }
        if (!buildDirsByLength.isEmpty()) {
            aggregatorDirs.add("");
        }

        var servicesByName = new TreeMap<String, ServiceEntry>();
        var servicesByDir = new TreeMap<String, ServiceEntry>();
        var servicesByNodeId = new LinkedHashMap<String, ServiceEntry>();
        var codeDirs = new HashSet<String>();
        var resolver = new CanonicalServiceIdentityResolver();
        for (var bc : buildComps) {
            if (bc.fallbackName().isEmpty()) continue;
            if (aggregatorDirs.contains(bc.normDir()) || testModuleDirs.contains(bc.normDir())
                    || foldedLayoutChildToParent.containsKey(bc.normDir())) {
                continue;
            }
            var se = new ServiceEntry(bc.node().identity(), bc.fallbackName(), bc.normDir(), true, bc.isFrontendTool());
            servicesByName.putIfAbsent(bc.fallbackName(), se);
            servicesByNodeId.put(bc.node().identity(), se);
            if (!bc.normDir().isEmpty()) {
                servicesByDir.putIfAbsent(bc.normDir(), se);
                codeDirs.add(bc.normDir());
            } else if (buildComps.size() == 1) {
                servicesByDir.putIfAbsent("", se);
            }
            String dfPath = dockerfilePathByDir.getOrDefault(bc.normDir(), "");
            resolver.registerBuildComponent(new CanonicalServiceIdentityResolver.BuildModuleDescriptor(
                    bc.node().identity(), bc.rawDir(), bc.normDir(), bc.compId(),
                    bc.fallbackName(), bc.fallbackName(), bc.tool(), bc.languages(),
                    packagingByDir.getOrDefault(bc.normDir(), ""),
                    executableDirs.contains(bc.normDir()),
                    descriptorTestProjectDirs.contains(bc.normDir()),
                    !dfPath.isEmpty(), dfPath,
                    Set.of(), routePrefixesByDir.getOrDefault(bc.normDir(), Set.of())));
        }
        for (var fold : foldedLayoutChildToParent.entrySet()) {
            ServiceEntry parentEntry = servicesByDir.get(fold.getValue());
            if (parentEntry != null) {
                servicesByDir.putIfAbsent(fold.getKey(), parentEntry);
                codeDirs.add(fold.getKey());
            }
        }

        // Step 2.5: Record canonical spring.application.name identity origins onto BUILD_COMPONENTs
        // (materialized with final multi-layer evidenceLayers and concordance in Step 7).
        var buildNodesWithCanonicalIdentity = new HashSet<String>();
        var identityOriginByNodeId = new HashMap<String, IdentityOrigin>();
        var bestIdentityByDir = new TreeMap<String, ConfigIdentitySite>();
        for (var ci : configIdentities) {
            if (!ci.configRepoFile().isEmpty() || isConfigServerRepoPath(ci.path())) continue;
            if (ci.dir().isEmpty() && buildComps.size() > 1) continue;
            var current = bestIdentityByDir.get(ci.dir());
            if (current == null || isHigherIdentityPriority(ci, current)) {
                bestIdentityByDir.put(ci.dir(), ci);
            }
        }
        for (var entry : bestIdentityByDir.entrySet()) {
            String dir = entry.getKey();
            var ci = entry.getValue();
            String normSvc = normalizeName(ci.serviceName());
            if (normSvc.isEmpty()) continue;
            BuildCompInfo bc = buildCompsByDir.get(dir);
            if (bc == null && dir.isEmpty() && buildComps.size() == 1) {
                bc = buildComps.getFirst();
            }
            if (bc != null && !aggregatorDirs.contains(bc.normDir())) {
                identityOriginByNodeId.put(bc.node().identity(),
                        new IdentityOrigin(ci.serviceName(), "spring-config", ci.proof()));
                var updated = new ServiceEntry(bc.node().identity(), normSvc, bc.normDir(), true, bc.isFrontendTool());
                servicesByDir.put(bc.normDir(), updated);
                servicesByName.put(normSvc, updated);
                servicesByNodeId.put(bc.node().identity(), updated);
                if (!bc.fallbackName().isEmpty()) {
                    servicesByName.put(bc.fallbackName(), updated);
                }
                resolver.updateCanonicalName(bc.node().identity(), normSvc, bc.normDir(),
                        CanonicalServiceIdentityResolver.EvidenceLayer.SPRING_CONFIG);
                buildNodesWithCanonicalIdentity.add(bc.node().identity());
            } else {
                var se = servicesByDir.getOrDefault(dir,
                        new ServiceEntry(ci.node().identity(), normSvc, dir, !dir.isEmpty(), false));
                servicesByName.putIfAbsent(normSvc, se);
                servicesByNodeId.putIfAbsent(se.nodeId(), se);
                if (!dir.isEmpty()) servicesByDir.putIfAbsent(dir, se);
            }
        }

        // Collect all unique leaf BUILD_COMPONENT ServiceEntries.
        var leafBuildServices = new LinkedHashMap<String, ServiceEntry>();
        for (var se : servicesByDir.values()) {
            if (se.hasCodeBuild() && !aggregatorDirs.contains(se.dirPrefix())) {
                leafBuildServices.put(se.nodeId(), se);
                servicesByNodeId.put(se.nodeId(), se);
            }
        }

        // Step 2.5b: Populate Spring Cloud roles, dependency starters, and messaging producer/consumer roles.
        var roles = new RoleContext();
        var startersByService = new TreeMap<String, Map<String, ContentDigest>>();
        for (var rs : springRoles) {
            ServiceEntry se = servicesByDir.get(rs.dir());
            if (se != null) {
                roles.addRole(se.nodeId(), rs.role());
            }
        }
        for (var ss : starterSites) {
            if (aggregatorDirs.contains(ss.dir())) continue;
            ServiceEntry se = servicesByDir.get(ss.dir());
            if (se == null || !se.hasCodeBuild()) continue;
            startersByService.computeIfAbsent(se.nodeId(), k -> new TreeMap<>())
                    .putIfAbsent(ss.starter(), ss.proof());
            String mappedRole = mapStarterToRole(ss.starter());
            if (!mappedRole.isEmpty()) {
                roles.addRole(se.nodeId(), mappedRole);
            }
        }
        for (var entry : eventProducerProtosByDir.entrySet()) {
            ServiceEntry se = servicesByDir.get(entry.getKey());
            if (se != null) {
                roles.eventProducerProtosByNodeId.computeIfAbsent(se.nodeId(), k -> new TreeSet<>()).addAll(entry.getValue());
            }
        }
        for (var entry : eventConsumerProtosByDir.entrySet()) {
            ServiceEntry se = servicesByDir.get(entry.getKey());
            if (se != null) {
                roles.eventConsumerProtosByNodeId.computeIfAbsent(se.nodeId(), k -> new TreeSet<>()).addAll(entry.getValue());
            }
        }
        refreshRoleOwners(roles, leafBuildServices.values(), servicesByName);

        // Step 2.6: Reconcile Compose/K8s/Proxy declarations via CanonicalServiceIdentityResolver or promote INFRA_COMPONENTs.
        var docSlotToService = new HashMap<String, ServiceEntry>();
        var dockerSocketMonitors = new ArrayList<MonitorSite>();
        var explicitIngress = new TreeMap<String, ServiceEntry>();
        var composeContainerServices = new TreeMap<String, ServiceEntry>();
        var k8sWorkloadNodeIds = new HashSet<String>();
        var composeWorkloadNodeIds = new HashSet<String>();
        var infraOriginMeta = new LinkedHashMap<String, InfraOriginMeta>();
        var infraAppLayersByNodeId = new HashMap<String, Set<String>>();
        var pendingEdges = new TreeMap<String, PendingEdge>();
        var infraEntries = new LinkedHashMap<String, ServiceEntry>();
        var infraByBaseToken = new HashMap<String, ServiceEntry>();
        for (var se : leafBuildServices.values()) {
            composeContainerServices.put(se.nodeId(), se);
        }

        // Pre-index declared ports by doc+slot so cross-layer port+route reconciliation has O(1) access.
        var portsByDocSlot = new HashMap<String, Set<String>>();
        for (var ps : portSites) {
            if (ps.port().matches("\\d+") && !ps.doc().isEmpty() && !ps.owner().isEmpty()) {
                portsByDocSlot.computeIfAbsent(ps.doc() + "|" + ps.owner(), k -> new TreeSet<>()).add(ps.port());
            }
        }

        // Observe all normalized declaration names for data-driven shared prefix detection (e.g. "app-").
        var observedDeclNames = new ArrayList<String>(declSites.size());
        for (var d : declSites) {
            String n = normalizeName(d.rawName());
            if (!n.isEmpty()) observedDeclNames.add(n);
        }
        resolver.observeDeclarationNames(observedDeclNames);

        // Process container workloads (Compose/K8s Deployment/StatefulSet/DaemonSet) before
        // K8s Service aliases (which bind via spec.selector) and standalone reverse-proxy configs.
        var docDeclCounts = new HashMap<String, Integer>();
        for (var d : declSites) {
            if (!d.doc().isEmpty()) {
                docDeclCounts.merge(d.doc(), 1, Integer::sum);
            }
        }
        declSites.sort(Comparator
                .comparingInt((DeclSite d) -> declPriority(d.nodeKind(), d.attrs()))
                .thenComparing(DeclSite::doc)
                .thenComparing(DeclSite::slot)
                .thenComparing(DeclSite::rawName));

        for (var decl : declSites) {
            check(cancelled);
            String norm = normalizeName(decl.rawName());
            if (norm.isEmpty()) continue;
            String buildDir = normDir(str(decl.attrs().get("buildDir")));
            String declParent = parentDirOf(decl.path());
            String resolvedBuildDir = (!buildDir.isEmpty() && !declParent.isEmpty()
                    && !buildDir.startsWith(declParent + "/") && !buildDir.equals(declParent))
                    ? normDir(declParent + "/" + buildDir) : "";
            String effectiveBuildDir = !resolvedBuildDir.isEmpty() && servicesByDir.containsKey(resolvedBuildDir)
                    ? resolvedBuildDir : buildDir;
            boolean proxyDecl = isProxyDecl(decl.nodeKind(), decl.attrs());
            boolean standaloneProxyDecl = proxyDecl && !"compose_service".equals(decl.nodeKind()) && !decl.nodeKind().startsWith("k8s_");
            String img = !decl.slot().isEmpty() ? docSlotToImage.getOrDefault(decl.doc() + "|" + decl.slot(), "") : "";
            if (img.isEmpty() && !resolvedBuildDir.isEmpty()) {
                img = dockerfileDirToImage.getOrDefault(resolvedBuildDir, "");
            }
            if (img.isEmpty() && !buildDir.isEmpty()) {
                img = dockerfileDirToImage.getOrDefault(buildDir, "");
            }
            if (img.isEmpty() && standaloneProxyDecl) {
                img = dockerfileDirToImage.getOrDefault(declParent,
                        dockerfileDirToImage.getOrDefault(decl.dir(), ""));
            }
            String imageTail = normalizeName(extractImageTail(img));
            String containerName = normalizeName(str(decl.attrs().get("containerName")));
            boolean dockerSocket = "true".equalsIgnoreCase(str(decl.attrs().get("dockerSocket")));
            String selectorTarget = "k8s_service".equals(decl.nodeKind())
                    ? normalizeName(str(decl.attrs().get("selectorTarget"))) : "";
            boolean singleDeclDoc = !decl.doc().isEmpty() && docDeclCounts.getOrDefault(decl.doc(), 0) == 1;
            var declLayer = "compose_service".equals(decl.nodeKind())
                    ? CanonicalServiceIdentityResolver.EvidenceLayer.COMPOSE
                    : (decl.nodeKind().startsWith("k8s_")
                            ? CanonicalServiceIdentityResolver.EvidenceLayer.K8S
                            : null);
            Set<String> declPorts = !decl.slot().isEmpty()
                    ? portsByDocSlot.getOrDefault(decl.doc() + "|" + decl.slot(), Set.of())
                    : Set.of();
            String declDockerfile = str(decl.attrs().get("dockerfile"));
            if (declDockerfile.isEmpty() && !effectiveBuildDir.isEmpty()) {
                declDockerfile = dockerfilePathByDir.getOrDefault(effectiveBuildDir, "");
            }

            ServiceEntry matched = standaloneProxyDecl
                    ? resolveOwnerService(decl.dir(), decl.path(), servicesByDir)
                    : null;
            if (matched == null) {
                boolean strictInfra = isDatastoreOrBrokerOrLogInfra(norm, img);
                var signal = new CanonicalServiceIdentityResolver.DeclarationSignal(
                        norm, effectiveBuildDir, declDockerfile, containerName, img, imageTail,
                        declPorts, routePrefixesByDir.getOrDefault(decl.dir(), Set.of()),
                        declLayer, strictInfra);
                matched = resolver.resolveDeclaration(signal).map(servicesByNodeId::get).orElse(null);
            }
            if (matched == null && !isDatastoreOrBrokerOrLogInfra(norm, img)) {
                matched = matchUniqueControlRoleAlias(norm, leafBuildServices.values(), roles);
            }
            if (matched == null && !selectorTarget.isEmpty()) {
                matched = servicesByName.get(selectorTarget);
                if (matched == null) {
                    var selSignal = new CanonicalServiceIdentityResolver.DeclarationSignal(
                            selectorTarget, "", "", "", "", "", Set.of(), Set.of(),
                            declLayer, isDatastoreOrBrokerOrLogInfra(selectorTarget, ""));
                    matched = resolver.resolveDeclaration(selSignal).map(servicesByNodeId::get).orElse(null);
                }
                if (matched == null && !isDatastoreOrBrokerOrLogInfra(selectorTarget, "")) {
                    matched = matchUniqueControlRoleAlias(selectorTarget, leafBuildServices.values(), roles);
                }
            }

            boolean isKnownInfra = isInfraNameOrImage(norm, img);
            boolean noCodeDir = decl.dir().isEmpty() || (!codeDirs.contains(decl.dir()) && servicesByDir.get(decl.dir()) == null);
            boolean k8sWorkload = isK8sWorkload(decl.nodeKind());

            if (matched != null) {
                if (declLayer != null) {
                    resolver.recordLayer(matched.nodeId(), declLayer);
                }
                if ("compose_service".equals(decl.nodeKind())) {
                    composeWorkloadNodeIds.add(matched.nodeId());
                } else if (k8sWorkload) {
                    k8sWorkloadNodeIds.add(matched.nodeId());
                }
                // Compose/K8s suffix service-identity fallback when leaf BUILD_COMPONENT lacks spring.application.name
                String strippedNorm = CanonicalServiceIdentityResolver.stripSharedPrefix(norm, resolver.sharedDeclPrefix());
                String identityCandidate = (!strippedNorm.isEmpty() && !strippedNorm.equals(norm)
                        && (matched.canonicalName().replace('.', '-').endsWith("-" + strippedNorm)
                                || matched.canonicalName().equals(strippedNorm)))
                        ? strippedNorm : norm;
                if (matched.hasCodeBuild() && !buildNodesWithCanonicalIdentity.contains(matched.nodeId())
                        && identityCandidate.length() >= 2
                        && ("compose_service".equals(decl.nodeKind()) || decl.nodeKind().startsWith("k8s_"))) {
                    BuildCompInfo bc = buildCompsByNodeId.get(matched.nodeId());
                    String fbDash = bc != null ? bc.fallbackName().replace('.', '-') : "";
                    if (bc != null && fbDash.endsWith("-" + identityCandidate)
                            && !identityCandidate.equals(matched.canonicalName())) {
                        identityOriginByNodeId.put(bc.node().identity(), new IdentityOrigin(
                                identityCandidate,
                                "compose_service".equals(decl.nodeKind()) ? "docker-compose" : "kubernetes",
                                decl.proof()));
                        var updated = new ServiceEntry(matched.nodeId(), identityCandidate, matched.dirPrefix(), true, matched.frontendBuild());
                        servicesByDir.put(matched.dirPrefix(), updated);
                        servicesByNodeId.put(matched.nodeId(), updated);
                        for (var e : new ArrayList<>(servicesByName.entrySet())) {
                            if (e.getValue().nodeId().equals(matched.nodeId())) {
                                servicesByName.put(e.getKey(), updated);
                            }
                        }
                        servicesByName.put(identityCandidate, updated);
                        servicesByName.put(bc.fallbackName(), updated);
                        resolver.updateCanonicalName(matched.nodeId(), identityCandidate, matched.dirPrefix(), declLayer);
                        leafBuildServices.put(matched.nodeId(), updated);
                        composeContainerServices.put(matched.nodeId(), updated);
                        buildNodesWithCanonicalIdentity.add(matched.nodeId());
                        matched = updated;
                    }
                }
                if (!img.isEmpty()) roles.recordImage(matched.nodeId(), img);
                if (standaloneProxyDecl && !matched.hasCodeBuild()) {
                    infraAppLayersByNodeId.computeIfAbsent(matched.nodeId(), k -> new TreeSet<>()).add("PROXY_CONFIG");
                }
                if (!buildDir.isEmpty()) servicesByDir.putIfAbsent(buildDir, matched);
                if (!resolvedBuildDir.isEmpty()) servicesByDir.putIfAbsent(resolvedBuildDir, matched);
                if (!decl.slot().isEmpty()) docSlotToService.put(decl.doc() + "|" + decl.slot(), matched);
                docSlotToService.put(decl.doc() + "|" + decl.node().identity(), matched);
                if (singleDeclDoc && !decl.dir().isEmpty()) docSlotToService.putIfAbsent(decl.doc(), matched);
                servicesByName.putIfAbsent(norm, matched);
                if (!containerName.isEmpty()) servicesByName.putIfAbsent(containerName, matched);
                if (!imageTail.isEmpty() && !isInfraNameOrImage(imageTail, img)) {
                    servicesByName.putIfAbsent(imageTail, matched);
                }
                if (!matched.hasCodeBuild()) {
                    resolver.registerInfraComponent(matched.nodeId(), norm, buildDir, imageTail, declPorts, declLayer);
                }
                composeContainerServices.put(matched.nodeId(), matched);
                if (dockerSocket) {
                    dockerSocketMonitors.add(new MonitorSite(matched, decl.proof()));
                }
                if (proxyDecl || isReverseProxyNameOrImage(norm, img)) {
                    roles.reverseProxyNodeIds.add(matched.nodeId());
                    explicitIngress.put(matched.nodeId(), matched);
                }
            } else if (proxyDecl || isKnownInfra
                    || ("compose_service".equals(decl.nodeKind()) && noCodeDir)
                    || (decl.nodeKind().startsWith("k8s_") && noCodeDir)) {
                String canonicalInfra = canonicalizeProxyName(norm, decl.nodeKind(), decl.attrs());
                ServiceEntry existingByCanon = servicesByName.get(canonicalInfra);
                ServiceEntry entry;
                if (existingByCanon != null) {
                    entry = existingByCanon;
                    if (declLayer != null) resolver.recordLayer(entry.nodeId(), declLayer);
                    if ("compose_service".equals(decl.nodeKind())) composeWorkloadNodeIds.add(entry.nodeId());
                    else if (k8sWorkload) k8sWorkloadNodeIds.add(entry.nodeId());
                } else {
                    String matchedDfDir = k8sWorkload && !isKnownInfra && !proxyDecl
                            ? findUniqueDockerfileDir(norm, resolver.sharedDeclPrefix(), dockerfilePathByDir, servicesByDir)
                            : "";
                    if (!matchedDfDir.isEmpty()) {
                        // Elevate standalone Dockerfile + K8s workload (without Maven/Gradle/build manifest) to SERVICE_COMPONENT.
                        String svcId = KnowledgeGraph.derivedIdentity("service-component", List.of(PROVIDER, root, matchedDfDir, canonicalInfra));
                        var svcProof = GraphEvidence.capture("architecture.service-identity", Map.ofEntries(
                                Map.entry("provider", PROVIDER),
                                Map.entry("serviceName", canonicalInfra),
                                Map.entry("name", canonicalInfra),
                                Map.entry("directory", matchedDfDir),
                                Map.entry("sourceEvidence", decl.proof()),
                                Map.entry("spec", "kubernetes-dockerfile"),
                                Map.entry("hasDockerfile", true),
                                Map.entry("componentRole", "SERVICE_COMPONENT"),
                                Map.entry("evidenceLayers", List.of("K8S")),
                                Map.entry("concordance", ServiceArchitectureProjection.EvidenceConcordance.SINGLE_LAYER_CONFIRMED.name()),
                                Map.entry("trust", "DECLARATIVE_CONFIG"),
                                Map.entry("runtimeObserved", false)));
                        evidence.putIfAbsent(svcProof.identity().value(), svcProof);
                        additions.putIfAbsent(root + "|" + svcId, new KnowledgeGraph.Node(svcId, root, "SERVICE_COMPONENT",
                                List.of(svcProof.identity())));
                        entry = new ServiceEntry(svcId, canonicalInfra, matchedDfDir, true, false);
                        servicesByName.put(canonicalInfra, entry);
                        servicesByDir.putIfAbsent(matchedDfDir, entry);
                        servicesByNodeId.put(svcId, entry);
                        leafBuildServices.put(svcId, entry);
                        codeDirs.add(matchedDfDir);
                        k8sWorkloadNodeIds.add(svcId);
                        resolver.recordLayer(svcId, CanonicalServiceIdentityResolver.EvidenceLayer.K8S);
                    } else {
                        String infraId = KnowledgeGraph.derivedIdentity("infra-component", List.of(PROVIDER, root, canonicalInfra));
                        var infraProof = GraphEvidence.capture("architecture.infra-component", Map.of(
                                "provider", PROVIDER, "name", canonicalInfra, "image", img, "spec", decl.nodeKind(),
                                "sourceEvidence", decl.proof(),
                                "concordance", ServiceArchitectureProjection.EvidenceConcordance.SINGLE_LAYER_CONFIRMED.name(),
                                "trust", "DECLARATIVE_CONFIG", "runtimeObserved", false));
                        evidence.putIfAbsent(infraProof.identity().value(), infraProof);
                        additions.putIfAbsent(root + "|" + infraId, new KnowledgeGraph.Node(infraId, root, "INFRA_COMPONENT",
                                List.of(infraProof.identity())));
                        entry = new ServiceEntry(infraId, canonicalInfra, buildDir, false, false);
                        roles.infraNodeIds.add(infraId);
                        servicesByName.put(canonicalInfra, entry);
                        servicesByNodeId.put(infraId, entry);
                        infraOriginMeta.putIfAbsent(infraId, new InfraOriginMeta(canonicalInfra, img, decl.nodeKind(), decl.proof()));
                        if (standaloneProxyDecl) {
                            infraAppLayersByNodeId.computeIfAbsent(infraId, k -> new TreeSet<>()).add("PROXY_CONFIG");
                        }
                        indexInfraEntry(entry, infraEntries, infraByBaseToken);
                        resolver.registerInfraComponent(infraId, canonicalInfra, buildDir, imageTail, declPorts, declLayer);
                        if ("compose_service".equals(decl.nodeKind())) composeWorkloadNodeIds.add(infraId);
                        else if (k8sWorkload) k8sWorkloadNodeIds.add(infraId);
                    }
                }
                if (!img.isEmpty()) roles.recordImage(entry.nodeId(), img);
                if (!buildDir.isEmpty()) servicesByDir.putIfAbsent(buildDir, entry);
                if (!resolvedBuildDir.isEmpty()) servicesByDir.putIfAbsent(resolvedBuildDir, entry);
                servicesByName.putIfAbsent(norm, entry);
                if (!containerName.isEmpty()) servicesByName.putIfAbsent(containerName, entry);
                if ("apache".equals(canonicalInfra)) servicesByName.putIfAbsent("httpd", entry);
                if ("httpd".equals(canonicalInfra)) servicesByName.putIfAbsent("apache", entry);
                if (!decl.slot().isEmpty()) docSlotToService.put(decl.doc() + "|" + decl.slot(), entry);
                docSlotToService.put(decl.doc() + "|" + decl.node().identity(), entry);
                if (singleDeclDoc) docSlotToService.putIfAbsent(decl.doc(), entry);
                composeContainerServices.put(entry.nodeId(), entry);
                if (dockerSocket) {
                    dockerSocketMonitors.add(new MonitorSite(entry, decl.proof()));
                }
                if (proxyDecl || isReverseProxyNameOrImage(canonicalInfra, img)) {
                    roles.reverseProxyNodeIds.add(entry.nodeId());
                    explicitIngress.put(entry.nodeId(), entry);
                }
            }
        }

        // Attach declared container/service ports onto RoleContext before role refresh so
        // standard IANA datastore/broker/SMTP/tracing ports assist structural role inference.
        for (var ps : portSites) {
            if (!ps.port().matches("\\d+")) continue;
            ServiceEntry portOwner = !ps.owner().isEmpty() ? docSlotToService.get(ps.doc() + "|" + ps.owner()) : null;
            if (portOwner == null) portOwner = docSlotToService.get(ps.doc());
            if (portOwner == null && !"k8s_port".equals(ps.nodeKind()) && !"compose_port".equals(ps.nodeKind())) {
                portOwner = resolveOwnerService(ps.dir(), ps.path(), servicesByDir);
            }
            if (portOwner != null) {
                roles.recordPort(portOwner.nodeId(), ps.port());
            }
        }

        // Refresh role owners now that Compose/K8s infra components, Docker images, and ports are registered.
        refreshRoleOwners(roles, leafBuildServices.values(), servicesByName);

        // Helper to resolve or promote referenced infrastructure components (reusing existing infra first via O(1) index).
        java.util.function.Function<String, ServiceEntry> resolveOrPromoteTarget = rawTarget -> {
            String norm = normalizeName(rawTarget);
            if (norm.isEmpty()) return null;
            ServiceEntry existing = servicesByName.get(norm);
            if (existing != null) return existing;
            if ("httpd".equals(norm) && servicesByName.containsKey("apache")) return servicesByName.get("apache");
            if ("apache".equals(norm) && servicesByName.containsKey("httpd")) return servicesByName.get("httpd");
            if (!isInfraNameOrImage(norm, "")) {
                ServiceEntry alias = resolver.findUniqueByBaseToken(norm).map(servicesByNodeId::get).orElse(null);
                if (alias != null) return alias;
                return null;
            }
            ServiceEntry reusedInfra = findExistingInfraIndexed(servicesByName, infraEntries, infraByBaseToken,
                    roles, KNOWN_INFRA_NAMES.contains(norm), norm);
            if (reusedInfra != null) return reusedInfra;
            String infraId = KnowledgeGraph.derivedIdentity("infra-component", List.of(PROVIDER, root, norm));
            var infraProof = GraphEvidence.capture("architecture.infra-component", Map.of(
                    "provider", PROVIDER, "name", norm, "image", norm, "spec", "config-reference",
                    "evidenceLayers", List.of("SPRING_CONFIG"),
                    "concordance", ServiceArchitectureProjection.EvidenceConcordance.SINGLE_LAYER_CONFIRMED.name(),
                    "trust", "DECLARATIVE_CONFIG", "runtimeObserved", false));
            evidence.putIfAbsent(infraProof.identity().value(), infraProof);
            additions.putIfAbsent(root + "|" + infraId, new KnowledgeGraph.Node(infraId, root, "INFRA_COMPONENT",
                    List.of(infraProof.identity())));
            var promoted = new ServiceEntry(infraId, norm, "", false, false);
            roles.infraNodeIds.add(infraId);
            infraOriginMeta.putIfAbsent(infraId, new InfraOriginMeta(norm, norm, "config-reference", null));
            infraAppLayersByNodeId.computeIfAbsent(infraId, k -> new TreeSet<>()).add("SPRING_CONFIG");
            servicesByName.put(norm, promoted);
            servicesByNodeId.put(infraId, promoted);
            indexInfraEntry(promoted, infraEntries, infraByBaseToken);
            return promoted;
        };

        // Step 2.7: Index server.port -> unique ServiceEntry, externalPort services, config properties, and config clients.
        var portToServices = new HashMap<String, Map<String, ServiceEntry>>();
        var externalPortServices = new TreeMap<String, ServiceEntry>();
        for (var ps : portSites) {
            if (ps.externalPort()) {
                ServiceEntry extSvc = !ps.owner().isEmpty() ? docSlotToService.get(ps.doc() + "|" + ps.owner()) : null;
                if (extSvc == null) extSvc = docSlotToService.get(ps.doc());
                if (extSvc == null) extSvc = resolveOwnerService(ps.dir(), ps.path(), servicesByDir);
                if (extSvc != null) {
                    externalPortServices.put(extSvc.nodeId(), extSvc);
                }
            }
            if (("spring_server_port".equals(ps.nodeKind()) || "spring-config".equals(ps.spec()))
                    && ps.port().matches("\\d+")) {
                String repoFile = effectiveConfigRepoFile(ps.configRepoFile(), ps.path(), ps.dir(), roles, servicesByDir);
                ServiceEntry owner = null;
                if (!repoFile.isEmpty()) {
                    if (!isSharedApplicationConfigFile(repoFile)) {
                        owner = resolveServiceByNameOrAlias(repoFile, servicesByName, leafBuildServices.values());
                    }
                } else {
                    owner = resolveOwnerService(ps.dir(), ps.path(), servicesByDir);
                }
                if (owner != null) {
                    portToServices.computeIfAbsent(ps.port(), k -> new TreeMap<>()).put(owner.nodeId(), owner);
                }
            }
        }
        var uniquePortToService = new HashMap<String, ServiceEntry>();
        for (var entry : portToServices.entrySet()) {
            if (entry.getValue().size() == 1) {
                uniquePortToService.put(entry.getKey(), entry.getValue().values().iterator().next());
            }
        }

        // Index CONFIG_PROPERTY facts + Spring Cloud feature flags (zuul.ignored-services, eureka client flags).
        boolean zuulIgnoredAllGlobal = false;
        var zuulIgnoredAllNodeIds = new HashSet<String>();
        var propsByServiceId = new HashMap<String, Map<String, PropSite>>();
        var propsByRepoFile = new HashMap<String, Map<String, PropSite>>();
        var globalProps = new HashMap<String, List<PropSite>>();
        for (var prop : configProps) {
            String repoFile = effectiveConfigRepoFile(prop.configRepoFile(), "", prop.dir(), roles, servicesByDir);
            String key = prop.key();
            String val = prop.value().trim();
            if ("zuul.ignored-services".equals(key) && "*".equals(val)) {
                ServiceEntry owner = resolvePropOwner(repoFile, prop.dir(), servicesByDir, servicesByName, leafBuildServices.values());
                if (owner != null) zuulIgnoredAllNodeIds.add(owner.nodeId());
                else zuulIgnoredAllGlobal = true;
            } else if ("eureka.client.enabled".equals(key) && "false".equalsIgnoreCase(val)) {
                ServiceEntry owner = resolvePropOwner(repoFile, prop.dir(), servicesByDir, servicesByName, leafBuildServices.values());
                if (owner != null) {
                    roles.eurekaRegisterFalse.add(owner.nodeId());
                    roles.eurekaFetchFalse.add(owner.nodeId());
                }
            } else if (("eureka.client.register-with-eureka".equals(key) || "eureka.client.registerWithEureka".equals(key))
                    && "false".equalsIgnoreCase(val)) {
                ServiceEntry owner = resolvePropOwner(repoFile, prop.dir(), servicesByDir, servicesByName, leafBuildServices.values());
                if (owner != null) roles.eurekaRegisterFalse.add(owner.nodeId());
            } else if (("eureka.client.fetch-registry".equals(key) || "eureka.client.fetchRegistry".equals(key))
                    && "false".equalsIgnoreCase(val)) {
                ServiceEntry owner = resolvePropOwner(repoFile, prop.dir(), servicesByDir, servicesByName, leafBuildServices.values());
                if (owner != null) roles.eurekaFetchFalse.add(owner.nodeId());
            }
            if (!repoFile.isEmpty()) {
                propsByRepoFile.computeIfAbsent(normalizeName(repoFile), k -> new TreeMap<>()).putIfAbsent(key, prop);
            } else {
                ServiceEntry owner = servicesByDir.get(prop.dir());
                if (owner != null) {
                    propsByServiceId.computeIfAbsent(owner.nodeId(), k -> new TreeMap<>()).putIfAbsent(key, prop);
                }
            }
            globalProps.computeIfAbsent(key, k -> new ArrayList<>()).add(prop);
        }

        // Collect evidenced config-client services for shared configRepoFile="application" attribution.
        var configClientServices = new TreeMap<String, ServiceEntry>();
        for (var ref : configRefs) {
            String repoFile = effectiveConfigRepoFile(ref.configRepoFile(), ref.path(), ref.dir(), roles, servicesByDir);
            if ("config-client".equals(ref.role()) && repoFile.isEmpty()) {
                ServiceEntry src = resolveOwnerService(ref.dir(), ref.path(), servicesByDir);
                if (src != null && !roles.isConfigServer(src)) {
                    configClientServices.put(src.nodeId(), src);
                }
            }
            if (!repoFile.isEmpty() && !isSharedApplicationConfigFile(repoFile)) {
                ServiceEntry matched = resolveServiceByNameOrAlias(repoFile, servicesByName, leafBuildServices.values());
                if (matched != null && !roles.isConfigServer(matched)) {
                    configClientServices.put(matched.nodeId(), matched);
                }
            }
        }
        for (var entry : startersByService.entrySet()) {
            if (entry.getValue().containsKey("config-client")) {
                ServiceEntry se = leafBuildServices.get(entry.getKey());
                if (se != null && !roles.isConfigServer(se)) {
                    configClientServices.put(se.nodeId(), se);
                }
            }
        }
        for (var route : gatewayRoutes) {
            String repoFile = effectiveConfigRepoFile(route.configRepoFile(), route.path(), route.dir(), roles, servicesByDir);
            if (!repoFile.isEmpty() && !isSharedApplicationConfigFile(repoFile)) {
                ServiceEntry matched = resolveServiceByNameOrAlias(repoFile, servicesByName, leafBuildServices.values());
                if (matched != null && !roles.isConfigServer(matched)) {
                    configClientServices.put(matched.nodeId(), matched);
                }
            }
        }

        var servicesWithDatastoreEdge = new HashSet<String>();
        var servicesWithMongoEdge = new HashSet<String>();
        var servicesWithRedisEdge = new HashSet<String>();

        // 1. Compose depends_on / links / external_links / env and K8s env refs
        for (var ref : envOrDepRefs) {
            check(cancelled);
            ServiceEntry src = !ref.owner().isEmpty() ? docSlotToService.get(ref.doc() + "|" + ref.owner()) : null;
            if (src == null) src = docSlotToService.get(ref.doc());
            if (src == null) src = resolveOwnerService(ref.dir(), ref.path(), servicesByDir);
            if (src == null) continue;
            ServiceEntry dst = resolveTargetWithLoopback(ref.target(), ref.port(), "", uniquePortToService,
                    roles, resolveOrPromoteTarget, servicesByName);
            if (dst != null && !src.nodeId().equals(dst.nodeId())) {
                recordDatastoreEdgeIfApplicable(src, dst, roles, servicesWithDatastoreEdge, servicesWithMongoEdge, servicesWithRedisEdge);
                String deployLayer = ref.nodeKind().startsWith("k8s_") ? "K8S" : "COMPOSE";
                recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, dst,
                        ref.proof(), "ENV_OR_COMPOSE_TOPOLOGY", "DECLARATIVE_CONFIG", deployLayer, false, null, roles);
            }
        }

        // 2. Spring Cloud Gateway, Netflix Zuul, Apache & Nginx reverse proxy routes
        var explicitZuulRouteGatewayIds = new HashSet<String>();
        for (var route : gatewayRoutes) {
            check(cancelled);
            String repoFile = effectiveConfigRepoFile(route.configRepoFile(), route.path(), route.dir(), roles, servicesByDir);
            ServiceEntry src = null;
            String proxyKind = detectProxySpec(route.nodeKind(), route.attrs(), route.path());
            if (!repoFile.isEmpty() && !isSharedApplicationConfigFile(repoFile)) {
                src = resolveServiceByNameOrAlias(repoFile, servicesByName, leafBuildServices.values());
            } else if (!proxyKind.isEmpty()) {
                src = resolveOwnerService(route.dir(), route.path(), servicesByDir);
                if (src == null || (!roles.reverseProxyNodeIds.contains(src.nodeId())
                        && !isReverseProxyNameOrImage(src.canonicalName(), roles.imageOf(src)))) {
                    src = resolveOrPromoteTarget.apply(proxyKind);
                }
                if (src != null) {
                    roles.reverseProxyNodeIds.add(src.nodeId());
                    explicitIngress.put(src.nodeId(), src);
                }
            } else {
                src = resolveOwnerService(route.dir(), route.path(), servicesByDir);
                if (src == null) {
                    src = findGatewayFallback(leafBuildServices.values(), servicesByName, roles);
                }
            }
            if (src == null) continue;
            roles.gatewayRouteOwnerNodeIds.add(src.nodeId());
            if ("zuul_route".equals(route.nodeKind()) || "netflix-zuul".equals(route.attrs().get("spec"))) {
                explicitZuulRouteGatewayIds.add(src.nodeId());
            }
            if (!roles.isControlPlane(src)) {
                explicitIngress.put(src.nodeId(), src);
            }
            ServiceEntry dst = resolveTargetWithLoopback(route.target(), route.port(), "", uniquePortToService,
                    roles, resolveOrPromoteTarget, servicesByName);
            if (dst != null && !src.nodeId().equals(dst.nodeId())) {
                if (roles.isAuthServer(dst)) {
                    roles.gatewayRouteToAuthNodeIds.add(src.nodeId());
                }
                String routeLayer = !proxyKind.isEmpty() ? "PROXY_CONFIG" : "SPRING_CONFIG";
                recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, dst,
                        route.proof(), "GATEWAY_ROUTE", "DECLARATIVE_CONFIG", routeLayer, false, null, roles);
            }
        }

        // 3. Spring Boot/Cloud Config, Filebeat, Eureka, Consul, Datastores, Brokers, Zipkin, SMTP, Logstash, Admin, OAuth2, Turbine, Grafana
        // Multi-profile datastore precedence: process explicit networked refs first, then fallback loopback/embedded DB refs.
        boolean hasRepoStarters = !startersByService.isEmpty();
        boolean hasExplicitTurbineMonitorRef = false;
        var primaryConfigRefs = new ArrayList<RefSite>(configRefs.size());
        var fallbackDatastoreRefs = new ArrayList<RefSite>();
        for (var ref : configRefs) {
            String role = ref.role().toLowerCase(Locale.ROOT);
            if (isDatastoreConfigRole(role) && isFallbackDatastoreRef(ref.target(), ref.attrs())) {
                fallbackDatastoreRefs.add(ref);
            } else {
                primaryConfigRefs.add(ref);
            }
        }

        for (int pass = 0; pass < 2; pass++) {
            boolean fallbackPass = (pass == 1);
            List<RefSite> batch = fallbackPass ? fallbackDatastoreRefs : primaryConfigRefs;
            for (var ref : batch) {
                check(cancelled);
                String role = ref.role().toLowerCase(Locale.ROOT);
                String repoFile = effectiveConfigRepoFile(ref.configRepoFile(), ref.path(), ref.dir(), roles, servicesByDir);
                List<ServiceEntry> owners;
                String proxyKind = detectProxyRole(role, ref.attrs(), ref.path());
                if (!proxyKind.isEmpty()) {
                    ServiceEntry proxy = resolveOwnerService(ref.dir(), ref.path(), servicesByDir);
                    if (proxy == null || (!roles.reverseProxyNodeIds.contains(proxy.nodeId())
                            && !isReverseProxyNameOrImage(proxy.canonicalName(), roles.imageOf(proxy)))) {
                        proxy = resolveOrPromoteTarget.apply(proxyKind);
                    }
                    if (proxy != null) {
                        roles.reverseProxyNodeIds.add(proxy.nodeId());
                        explicitIngress.put(proxy.nodeId(), proxy);
                    }
                    owners = proxy != null ? List.of(proxy) : List.of();
                } else if ("grafana-datasource".equals(role)) {
                    ServiceEntry g = resolveOwnerService(ref.dir(), ref.path(), servicesByDir);
                    if (g == null) g = resolveOrPromoteTarget.apply("grafana");
                    owners = g != null ? List.of(g) : List.of();
                } else if (!repoFile.isEmpty()) {
                    if (isSharedApplicationConfigFile(repoFile)) {
                        owners = filterSharedConfigClients(configClientServices.values(), role,
                                hasRepoStarters, startersByService, roles);
                    } else {
                        ServiceEntry matched = resolveServiceByNameOrAlias(repoFile, servicesByName, leafBuildServices.values());
                        owners = matched != null ? List.of(matched) : List.of();
                    }
                } else {
                    ServiceEntry src = resolveOwnerService(ref.dir(), ref.path(), servicesByDir);
                    owners = src != null ? List.of(src) : List.of();
                }
                if (owners.isEmpty()) continue;

                // Filter out owners that already have an explicit networked datastore edge of this family on fallback pass.
                if (fallbackPass) {
                    owners = owners.stream()
                            .filter(src -> !hasExplicitDatastoreFamilyEdge(src, role, servicesWithDatastoreEdge,
                                    servicesWithMongoEdge, servicesWithRedisEdge))
                            .toList();
                    if (owners.isEmpty()) continue;
                }

                String targetName = ref.target();
                if ("config-git".equals(role) || "config-git-repo".equals(role)) {
                    targetName = normalizeGitRepoTarget(targetName);
                }
                ServiceEntry dst = resolveConfigRefDestination(role, targetName, ref.port(), ref.attrs(),
                        uniquePortToService, roles, resolveOrPromoteTarget, servicesByName);
                if (dst == null) continue;

                String mechanism = !proxyKind.isEmpty() ? "GATEWAY_ROUTE" : "SPRING_CONFIG_" + role.toUpperCase(Locale.ROOT);
                String cfgLayer = !proxyKind.isEmpty() ? "PROXY_CONFIG" : "SPRING_CONFIG";
                for (ServiceEntry src : owners) {
                    if (src.nodeId().equals(dst.nodeId())) continue;
                    if ("eureka-client".equals(role) && roles.isEurekaDisabled(src)) continue;
                    if ("turbine-monitor".equals(role)) hasExplicitTurbineMonitorRef = true;
                    recordDatastoreEdgeIfApplicable(src, dst, roles, servicesWithDatastoreEdge, servicesWithMongoEdge, servicesWithRedisEdge);
                    recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, dst,
                            ref.proof(), mechanism, "DECLARATIVE_CONFIG", cfgLayer, false, null, roles);
                }
            }
        }

        // 4. Prometheus scrape targets
        if (!scrapeTargets.isEmpty()) {
            ServiceEntry prometheusComp = resolveOrPromoteTarget.apply("prometheus");
            if (prometheusComp != null) {
                for (var scrape : scrapeTargets) {
                    check(cancelled);
                    ServiceEntry dst = resolveTargetWithLoopback(scrape.target(), scrape.port(), "",
                            uniquePortToService, roles, resolveOrPromoteTarget, servicesByName);
                    if (dst != null && !prometheusComp.nodeId().equals(dst.nodeId())) {
                        recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, prometheusComp, dst,
                                scrape.proof(), "PROMETHEUS_SCRAPE", "DECLARATIVE_CONFIG", "PROMETHEUS_CONFIG", false, null, roles);
                    }
                }
            }
        }

        // 5. Java Feign / RestTemplate / RestOperations / WebClient / DiscoveryClient / @Value propertyKey / localhost:<port> calls
        for (var call : httpCalls) {
            check(cancelled);
            ServiceEntry src = servicesByDir.get(call.dir());
            if (src == null) continue;
            String resolvedUri = call.uri();
            ServiceEntry dst = null;

            if (!call.targetService().isEmpty() && !isLoopback(call.targetService())) {
                dst = resolveServiceByNameOrAlias(call.targetService(), servicesByName, leafBuildServices.values());
            }
            if (dst == null && !call.propertyKey().isEmpty()) {
                PropSite prop = lookupConfigProperty(call.propertyKey(), src, propsByServiceId, propsByRepoFile, globalProps);
                if (prop != null) {
                    if (!prop.value().isEmpty()) {
                        resolvedUri = prop.value();
                    }
                    String propTarget = !prop.target().isEmpty() ? prop.target() : extractUriHostRaw(prop.value());
                    String propPort = !prop.port().isEmpty() ? prop.port() : extractUriPort(prop.value());
                    if (isLoopback(propTarget) && !propPort.isEmpty()) {
                        dst = uniquePortToService.get(propPort);
                    } else if (!propTarget.isEmpty() && !isLoopback(propTarget)) {
                        dst = resolveServiceByNameOrAlias(propTarget, servicesByName, leafBuildServices.values());
                    }
                }
            }
            if (dst == null && !resolvedUri.isEmpty()) {
                String rawHost = extractUriHostRaw(resolvedUri);
                String rawPort = extractUriPort(resolvedUri);
                if (isLoopback(rawHost) && !rawPort.isEmpty()) {
                    dst = uniquePortToService.get(rawPort);
                } else if (!rawHost.isEmpty() && !isLoopback(rawHost)) {
                    dst = resolveServiceByNameOrAlias(rawHost, servicesByName, leafBuildServices.values());
                }
            }

            if (dst != null && !src.nodeId().equals(dst.nodeId())) {
                recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, dst,
                        call.proof(), "JAVA_SERVICE_HOST_CALL", "SOURCE_SYNTAX", "SOURCE_SYNTAX", false, null, roles);
            } else if (profile == ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT
                    && dst == null && isExplicitExternalHttpsUrl(resolvedUri, servicesByName)) {
                String extHost = extractUriHostRaw(resolvedUri);
                String actorName = extHost;
                String extId = KnowledgeGraph.derivedIdentity("external-actor", List.of(PROVIDER, root, actorName));
                var extProof = GraphEvidence.capture("architecture.external-actor", Map.of(
                        "provider", PROVIDER, "name", actorName, "host", extHost,
                        "category", "external-http-service",
                        "sourceEvidence", call.proof(),
                        "concordance", ServiceArchitectureProjection.EvidenceConcordance.DERIVED_DFD_ORIENTATION.name(),
                        "orientationRole", ProtocolOrientationRole.DFD_POLICY_SYNTHESIS.name(),
                        "trust", "DERIVED_DFD_POLICY",
                        "rule", "DFD_EXTERNAL_HTTPS_API", "runtimeObserved", false));
                evidence.putIfAbsent(extProof.identity().value(), extProof);
                String extKey = root + "|" + extId;
                var existingExt = additions.get(extKey);
                if (existingExt == null) {
                    additions.put(extKey, new KnowledgeGraph.Node(extId, root, "EXTERNAL_ACTOR",
                            List.of(extProof.identity())));
                } else {
                    var merged = new TreeSet<>(existingExt.evidence());
                    merged.add(extProof.identity());
                    additions.put(extKey, new KnowledgeGraph.Node(extId, root, "EXTERNAL_ACTOR",
                            List.copyOf(merged)));
                }
                var extEntry = new ServiceEntry(extId, actorName, "", false, false);
                String httpMethod = str(call.attrs().get("method")).toUpperCase(Locale.ROOT);
                boolean isGet = "GET".equals(httpMethod) || httpMethod.isEmpty() || "UNKNOWN".equals(httpMethod);
                if (isGet) {
                    recordTopologyEdge(pendingEdges, existingCalls, extEntry, src,
                            call.proof(), "DFD_EXTERNAL_HTTPS_API", "DERIVED_DFD_POLICY",
                            "DFD_POLICY", true, ProtocolOrientationRole.DFD_POLICY_SYNTHESIS, false);
                } else {
                    recordTopologyEdge(pendingEdges, existingCalls, src, extEntry,
                            call.proof(), "DFD_EXTERNAL_HTTPS_API", "DERIVED_DFD_POLICY",
                            "DFD_POLICY", true, ProtocolOrientationRole.DFD_POLICY_SYNTHESIS, false);
                }
            }
        }

        // 5b. Direct Java @EnableEurekaClient / @EnableDiscoveryClient -> unique @EnableEurekaServer (STRICT & DFD_EXPORT)
        long eurekaServerCount = leafBuildServices.values().stream()
                .filter(se -> roles.hasRole(se, "EUREKA_SERVER")).count();
        boolean hasConsulServer = hasAnyConsul(servicesByName, roles);
        if (eurekaServerCount == 1 && roles.eurekaServer != null && roles.hasRole(roles.eurekaServer, "EUREKA_SERVER")
                && !hasConsulServer) {
            for (var src : leafBuildServices.values()) {
                check(cancelled);
                if (src.nodeId().equals(roles.eurekaServer.nodeId()) || roles.isEurekaDisabled(src)) continue;
                ContentDigest roleProof = findRoleProofFast(roleProofByDirAndRole, src.dirPrefix(), Set.of("EUREKA_CLIENT", "DISCOVERY_CLIENT"));
                if (roleProof != null) {
                    recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, roles.eurekaServer,
                            roleProof, "JAVA_EUREKA_CLIENT_ROLE", "SOURCE_SYNTAX", "SOURCE_SYNTAX", false, null, roles);
                }
            }
        }

        // 6. Opt-In DFD_EXPORT Compatibility Rules (all derived actors/edges tagged trust="DERIVED_DFD_POLICY"
        //    and concordance="DERIVED_DFD_ORIENTATION", strictly driven by generic ProtocolOrientationRole)
        if (profile == ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT) {
            // Rule 6a: Starter-evidenced & Spring-role control-plane / infra wiring
            ServiceEntry existingRabbit = findExistingInfraIndexed(servicesByName, infraEntries, infraByBaseToken, roles, true, "rabbitmq", "rabbit");
            ServiceEntry existingKafka = findExistingInfraIndexed(servicesByName, infraEntries, infraByBaseToken, roles, true, "kafka");
            ServiceEntry existingConsul = findExistingInfraIndexed(servicesByName, infraEntries, infraByBaseToken, roles, true, "consul");
            ServiceEntry existingPrometheus = findExistingInfraIndexed(servicesByName, infraEntries, infraByBaseToken, roles, true, "prometheus");
            boolean hasTurbineStream = leafBuildServices.values().stream()
                    .anyMatch(s -> roles.hasRole(s, "TURBINE_STREAM"));

            for (var src : leafBuildServices.values()) {
                check(cancelled);
                var modStarters = startersByService.getOrDefault(src.nodeId(), Map.of());

                // Discovery registration / lookup
                ContentDigest eurekaProof = modStarters.get("eureka-client");
                if (eurekaProof == null && (roles.hasRole(src, "EUREKA_CLIENT") || roles.hasRole(src, "DISCOVERY_CLIENT"))) {
                    eurekaProof = findRoleProofFast(roleProofByDirAndRole, src.dirPrefix(), Set.of("EUREKA_CLIENT", "DISCOVERY_CLIENT"));
                }
                if (eurekaProof != null && roles.eurekaServer != null && !src.nodeId().equals(roles.eurekaServer.nodeId())
                        && !roles.isEurekaDisabled(src)) {
                    recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, roles.eurekaServer,
                            eurekaProof, "STARTER_EUREKA_CLIENT", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                            ProtocolOrientationRole.DISCOVERY_REGISTRY, roles);
                }

                // Config server client
                ContentDigest cfgProof = modStarters.get("config-client");
                if (cfgProof != null && roles.configServer != null && !src.nodeId().equals(roles.configServer.nodeId())) {
                    recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, roles.configServer,
                            cfgProof, "STARTER_CONFIG_CLIENT", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                            ProtocolOrientationRole.CONFIG_SERVER, roles);
                }

                // Zipkin HTTP tracing (skip if Zipkin server consumes via stream; require explicit zipkin reporter starter)
                ContentDigest traceProof = modStarters.get("zipkin");
                if (traceProof != null && roles.zipkinServer != null
                        && !roles.hasRole(roles.zipkinServer, "ZIPKIN_STREAM_SERVER")
                        && !src.nodeId().equals(roles.zipkinServer.nodeId())) {
                    recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, roles.zipkinServer,
                            traceProof, "STARTER_ZIPKIN_TRACING", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                            ProtocolOrientationRole.OBSERVABILITY_PIPELINE, roles);
                }

                // RabbitMQ stream / hystrix-stream (never bare bus-amqp unless paired with CIRCUIT_BREAKER + TURBINE_STREAM)
                ContentDigest rabbitProof = modStarters.getOrDefault("stream-rabbit", modStarters.get("hystrix-stream"));
                if (rabbitProof == null && hasTurbineStream && roles.hasRole(src, "CIRCUIT_BREAKER")) {
                    rabbitProof = modStarters.get("bus-amqp");
                }
                if (rabbitProof != null && existingRabbit != null && !src.nodeId().equals(existingRabbit.nodeId())) {
                    recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, existingRabbit,
                            rabbitProof, "STARTER_RABBITMQ", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                            ProtocolOrientationRole.BROKER_CONSUMER, roles);
                }

                // Kafka stream
                ContentDigest kafkaProof = modStarters.get("stream-kafka");
                if (kafkaProof != null && existingKafka != null && !src.nodeId().equals(existingKafka.nodeId())) {
                    recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, existingKafka,
                            kafkaProof, "STARTER_KAFKA", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                            ProtocolOrientationRole.BROKER_CONSUMER, roles);
                }

                // Spring Boot Admin client
                ContentDigest adminProof = modStarters.get("boot-admin-client");
                if (adminProof != null && roles.adminServer != null && !src.nodeId().equals(roles.adminServer.nodeId())) {
                    recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, roles.adminServer,
                            adminProof, "STARTER_BOOT_ADMIN_CLIENT", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                            ProtocolOrientationRole.CONTROL_PLANE_ADMIN, roles);
                }

                // OAuth2 resource server -> auth server
                ContentDigest oauthProof = modStarters.getOrDefault("oauth2-resource", modStarters.get("oauth2-client"));
                if (oauthProof == null && roles.isOAuth2Resource(src)) {
                    oauthProof = findRoleProofFast(roleProofByDirAndRole, src.dirPrefix(), Set.of("OAUTH2_RESOURCE", "OAUTH2_SSO"));
                }
                if (oauthProof != null && roles.authServer != null && !src.nodeId().equals(roles.authServer.nodeId())) {
                    recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, roles.authServer,
                            oauthProof, "STARTER_OAUTH2_RESOURCE", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                            ProtocolOrientationRole.AUTH_PROVIDER, roles);
                }

                // Consul
                ContentDigest consulProof = modStarters.get("consul");
                if (consulProof != null && existingConsul != null && !src.nodeId().equals(existingConsul.nodeId())) {
                    recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, existingConsul,
                            consulProof, "STARTER_CONSUL", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                            ProtocolOrientationRole.DISCOVERY_REGISTRY, roles);
                }

                // Prometheus starter
                ContentDigest promProof = modStarters.get("prometheus");
                if (promProof != null && existingPrometheus != null && !src.nodeId().equals(existingPrometheus.nodeId())) {
                    recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, existingPrometheus,
                            promProof, "STARTER_PROMETHEUS", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                            ProtocolOrientationRole.OBSERVABILITY_PIPELINE, roles);
                }

                // Logstash logback encoder
                ContentDigest logstashProof = modStarters.get("logstash-logback");
                if (logstashProof != null) {
                    ServiceEntry logTarget = findExistingInfraIndexed(servicesByName, infraEntries, infraByBaseToken, roles, true, "logstash", "elk");
                    if (logTarget == null) logTarget = resolveOrPromoteTarget.apply("logstash");
                    if (logTarget != null && !src.nodeId().equals(logTarget.nodeId())) {
                        recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, logTarget,
                                logstashProof, "STARTER_LOGSTASH", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                                ProtocolOrientationRole.OBSERVABILITY_PIPELINE, roles);
                    }
                }

                // Spring Mail starter -> declared SMTP relay container or standard RFC 5321 "smtp" protocol service
                ContentDigest mailProof = modStarters.get("mail");
                if (mailProof != null) {
                    ServiceEntry mailTarget = resolveSmtpTarget("", servicesByName, roles, resolveOrPromoteTarget);
                    if (mailTarget != null && !src.nodeId().equals(mailTarget.nodeId())) {
                        recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, mailTarget,
                                mailProof, "STARTER_SMTP", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                                ProtocolOrientationRole.DFD_POLICY_SYNTHESIS, roles);
                    }
                }

                // Datastore starters: ONLY wire to already-declared datastore INFRA_COMPONENTs (never conjure DBs from POMs)
                if (!servicesWithDatastoreEdge.contains(src.nodeId()) && modStarters.containsKey("data-jpa")) {
                    String dbKind = modStarters.containsKey("jdbc-mysql") ? "mysql"
                            : modStarters.containsKey("jdbc-postgres") ? "postgres"
                            : modStarters.containsKey("jdbc-h2") ? "h2" : "";
                    if (!dbKind.isEmpty()) {
                        ContentDigest dbProof = modStarters.getOrDefault("jdbc-" + dbKind, modStarters.get("data-jpa"));
                        ServiceEntry dbTarget = findDeclaredDatastoreInfra(src, dbKind, servicesByName, roles);
                        if (dbTarget != null && !src.nodeId().equals(dbTarget.nodeId())) {
                            servicesWithDatastoreEdge.add(src.nodeId());
                            recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, dbTarget,
                                    dbProof, "STARTER_DATASOURCE", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                                    ProtocolOrientationRole.DATA_STORE, roles);
                        }
                    }
                }
                if (!servicesWithMongoEdge.contains(src.nodeId()) && modStarters.containsKey("data-mongodb")) {
                    ServiceEntry mongoTarget = findDeclaredDatastoreInfra(src, "mongodb", servicesByName, roles);
                    if (mongoTarget != null && !src.nodeId().equals(mongoTarget.nodeId())) {
                        servicesWithMongoEdge.add(src.nodeId());
                        recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, mongoTarget,
                                modStarters.get("data-mongodb"), "STARTER_MONGODB", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                                ProtocolOrientationRole.DATA_STORE, roles);
                    }
                }
                if (!servicesWithRedisEdge.contains(src.nodeId()) && modStarters.containsKey("data-redis")) {
                    ServiceEntry redisTarget = findDeclaredDatastoreInfra(src, "redis", servicesByName, roles);
                    if (redisTarget != null && !src.nodeId().equals(redisTarget.nodeId())) {
                        servicesWithRedisEdge.add(src.nodeId());
                        recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, redisTarget,
                                modStarters.get("data-redis"), "STARTER_REDIS", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                                ProtocolOrientationRole.DATA_STORE, roles);
                    }
                }

                // Stream consumers (Turbine Stream / Zipkin Stream Server) reading from concrete broker
                if (roles.isStreamConsumer(src)) {
                    ContentDigest streamRoleProof = findRoleProofFast(roleProofByDirAndRole, src.dirPrefix(),
                            Set.of("TURBINE_STREAM", "ZIPKIN_STREAM_SERVER"));
                    if (streamRoleProof == null) streamRoleProof = modStarters.get("turbine-stream");
                    ServiceEntry broker = existingRabbit != null ? existingRabbit : existingKafka;
                    if (streamRoleProof != null && broker != null && !src.nodeId().equals(broker.nodeId())) {
                        recordOrientedEdge(profile, pendingEdges, existingCalls, infraAppLayersByNodeId, src, broker,
                                streamRoleProof, "STARTER_STREAM_CONSUMER", "DERIVED_DFD_POLICY", "DFD_POLICY", true,
                                ProtocolOrientationRole.BROKER_CONSUMER, roles);
                    }
                }

                // HTTP Turbine: wire @EnableCircuitBreaker/@EnableHystrix services -> HTTP Turbine when no explicit turbine.appConfig exists
                if (roles.turbineServer != null && !roles.isStreamConsumer(roles.turbineServer)
                        && !hasExplicitTurbineMonitorRef && !roles.isControlPlane(src)
                        && !src.nodeId().equals(roles.turbineServer.nodeId())
                        && roles.hasRole(src, "CIRCUIT_BREAKER")) {
                    ContentDigest cbProof = findRoleProofFast(roleProofByDirAndRole, src.dirPrefix(), Set.of("CIRCUIT_BREAKER"));
                    if (cbProof != null) {
                        recordTopologyEdge(pendingEdges, existingCalls, src, roles.turbineServer,
                                cbProof, "DFD_HTTP_TURBINE_CIRCUIT_BREAKER", "DERIVED_DFD_POLICY",
                                "DFD_POLICY", true, ProtocolOrientationRole.OBSERVABILITY_PIPELINE, false);
                    }
                }
            }

            // Turbine -> Hystrix Dashboard flow when both exist in repository
            if (roles.turbineServer != null && roles.hystrixDashboard != null
                    && !roles.turbineServer.nodeId().equals(roles.hystrixDashboard.nodeId())) {
                ContentDigest p = findRoleProofFast(roleProofByDirAndRole, roles.hystrixDashboard.dirPrefix(), Set.of("HYSTRIX_DASHBOARD"));
                if (p == null) p = findRoleProofFast(roleProofByDirAndRole, roles.turbineServer.dirPrefix(), Set.of("TURBINE", "TURBINE_STREAM"));
                if (p != null) {
                    recordTopologyEdge(pendingEdges, existingCalls, roles.turbineServer, roles.hystrixDashboard,
                            p, "DFD_TURBINE_DASHBOARD_STREAM", "DERIVED_DFD_POLICY",
                            "DFD_POLICY", true, ProtocolOrientationRole.OBSERVABILITY_PIPELINE, false);
                }
            }

            // Rule 6b: Tightened @EnableZuulProxy -> business Eureka-registered services (skip if explicit zuul.routes.* exist)
            for (var gw : leafBuildServices.values()) {
                if (!roles.hasRole(gw, "ZUUL_PROXY")) continue;
                if (zuulIgnoredAllGlobal || zuulIgnoredAllNodeIds.contains(gw.nodeId())) continue;
                if (explicitZuulRouteGatewayIds.contains(gw.nodeId())) continue;
                ContentDigest gwProof = findRoleProofFast(roleProofByDirAndRole, gw.dirPrefix(), Set.of("ZUUL_PROXY"));
                if (gwProof == null) gwProof = startersByService.getOrDefault(gw.nodeId(), Map.of()).get("zuul");
                if (gwProof == null) continue;
                for (var target : leafBuildServices.values()) {
                    if (target.nodeId().equals(gw.nodeId())) continue;
                    if (!target.hasCodeBuild()) continue;
                    if (roles.hasAnyRole(target, ZUUL_EXCLUDED_TARGET_ROLES) || roles.isControlPlane(target)) continue;
                    if (roles.isEurekaDisabled(target)) continue;
                    boolean registered = roles.hasRole(target, "EUREKA_CLIENT")
                            || roles.hasRole(target, "DISCOVERY_CLIENT")
                            || startersByService.getOrDefault(target.nodeId(), Map.of()).containsKey("eureka-client");
                    if (registered) {
                        recordTopologyEdge(pendingEdges, existingCalls, gw, target,
                                gwProof, "ZUUL_EUREKA_AUTO_PROXY", "DERIVED_DFD_POLICY",
                                "DFD_POLICY", true, ProtocolOrientationRole.DFD_POLICY_SYNTHESIS, false);
                    }
                }
            }

            // Rule 6c: Evidenced container observability/telemetry agents mounting /var/run/docker.sock -> DFD telemetry flow (target -> collector)
            // Require explicit observability/telemetry agent signals (image or role token) and exclude non-observability containers.
            for (var monitor : dockerSocketMonitors) {
                ServiceEntry mon = monitor.service();
                if (!isObservabilityAgent(mon, roles)) {
                    continue;
                }
                for (var target : composeContainerServices.values()) {
                    if (target.nodeId().equals(mon.nodeId())) continue;
                    if (roles.isGitRepo(target)) continue;
                    recordTopologyEdge(pendingEdges, existingCalls, target, mon,
                            monitor.proof(), "DFD_DOCKER_SOCKET_MONITOR", "DERIVED_DFD_POLICY",
                            "DFD_POLICY", true, ProtocolOrientationRole.OBSERVABILITY_PIPELINE, false);
                }
            }

            // Rule 6d: Tightened EXTERNAL_ACTOR("user") -> explicit ingress entrypoints with outer proxy/frontend precedence
            // plus bidirectional return flow entrypoint -> user.
            for (var se : leafBuildServices.values()) {
                if (roles.isControlPlane(se)) continue;
                if (roles.hasRole(se, "ZUUL_PROXY") || roles.hasRole(se, "API_GATEWAY")
                        || se.frontendBuild() || isFrontendOrIngressName(se.canonicalName())) {
                    explicitIngress.put(se.nodeId(), se);
                }
            }
            explicitIngress.entrySet().removeIf(e -> isNonIngressService(e.getValue(), roles));

            var outerIngress = new TreeMap<String, ServiceEntry>();
            for (var entry : explicitIngress.entrySet()) {
                ServiceEntry se = entry.getValue();
                if (roles.reverseProxyNodeIds.contains(se.nodeId())
                        || se.frontendBuild()
                        || isFrontendSpaName(se.canonicalName())) {
                    outerIngress.put(entry.getKey(), se);
                }
            }

            var fallbackEntries = new TreeMap<String, ServiceEntry>();
            for (var entry : externalPortServices.entrySet()) {
                if (!isNonIngressService(entry.getValue(), roles)) {
                    fallbackEntries.put(entry.getKey(), entry.getValue());
                }
            }
            var selectedEntries = !outerIngress.isEmpty() ? outerIngress
                    : (!explicitIngress.isEmpty() ? explicitIngress : fallbackEntries);
            if (!selectedEntries.isEmpty()) {
                String actorId = KnowledgeGraph.derivedIdentity("external-actor", List.of(PROVIDER, root, "user"));
                var actorProof = GraphEvidence.capture("architecture.external-actor", Map.of(
                        "provider", PROVIDER, "name", "user",
                        "concordance", ServiceArchitectureProjection.EvidenceConcordance.DERIVED_DFD_ORIENTATION.name(),
                        "orientationRole", ProtocolOrientationRole.DFD_POLICY_SYNTHESIS.name(),
                        "trust", "DERIVED_DFD_POLICY",
                        "rule", "DFD_EXTERNAL_ENTRY_POINT", "runtimeObserved", false));
                evidence.putIfAbsent(actorProof.identity().value(), actorProof);
                additions.put(root + "|" + actorId, new KnowledgeGraph.Node(actorId, root, "EXTERNAL_ACTOR",
                        List.of(actorProof.identity())));
                var actorEntry = new ServiceEntry(actorId, "user", "", false, false);
                for (ServiceEntry entry : selectedEntries.values()) {
                    recordTopologyEdge(pendingEdges, existingCalls, actorEntry, entry,
                            actorProof.identity(), "DFD_EXTERNAL_ENTRY_POINT", "DERIVED_DFD_POLICY",
                            "DFD_POLICY", true, ProtocolOrientationRole.DFD_POLICY_SYNTHESIS, false);
                    recordTopologyEdge(pendingEdges, existingCalls, entry, actorEntry,
                            actorProof.identity(), "DFD_EXTERNAL_ENTRY_RESPONSE", "DERIVED_DFD_POLICY",
                            "DFD_POLICY", true, ProtocolOrientationRole.DFD_POLICY_SYNTHESIS, false);
                }
            }
        }

        // Step 7: Materialize cross-layer evidenceLayers, test-only flags, Dockerfile+K8s SERVICE_COMPONENT
        // elevation, and SHARED_LIBRARY_MODULE classification deterministically without dropping endpoints.
        boolean repoHasContainerServices = !k8sWorkloadNodeIds.isEmpty() || !composeWorkloadNodeIds.isEmpty();
        boolean repoHasDeployableServices = repoHasContainerServices
                || !dirsWithEndpointsOrRoutes.isEmpty()
                || !executableDirs.isEmpty()
                || !springRoles.isEmpty();
        int totalLeafBuildCount = leafBuildServices.size();

        for (var bc : buildComps) {
            if (aggregatorDirs.contains(bc.normDir()) || foldedLayoutChildToParent.containsKey(bc.normDir())) {
                continue;
            }
            String nodeId = bc.node().identity();
            String nodeKey = bc.node().context() + "|" + nodeId;
            boolean isTestOnly = testModuleDirs.contains(bc.normDir());
            if (isTestOnly) {
                var classProof = GraphEvidence.capture("architecture.component-classification", Map.of(
                        "provider", PROVIDER,
                        "role", CanonicalServiceIdentityResolver.ComponentRole.TEST_ONLY_MODULE.name(),
                        "isTestOnlyModule", true,
                        "directory", bc.rawDir(),
                        "packaging", packagingByDir.getOrDefault(bc.normDir(), ""),
                        "evidenceLayers", List.of("BUILD"),
                        "concordance", ServiceArchitectureProjection.EvidenceConcordance.SINGLE_LAYER_CONFIRMED.name(),
                        "trust", "DECLARATIVE_CONFIG",
                        "runtimeObserved", false));
                evidence.putIfAbsent(classProof.identity().value(), classProof);
                var baseNode = additions.getOrDefault(nodeKey, bc.node());
                var combined = new TreeSet<>(baseNode.evidence());
                combined.add(classProof.identity());
                additions.put(nodeKey, new KnowledgeGraph.Node(nodeId, bc.node().context(),
                        bc.node().kind(), List.copyOf(combined)));
                continue;
            }

            ServiceEntry se = servicesByNodeId.get(nodeId);
            String svcName = se != null ? se.canonicalName() : bc.fallbackName();
            boolean hasDf = dockerfilePathByDir.containsKey(bc.normDir()) || resolver.hasDockerfile(nodeId);
            boolean hasCompose = composeWorkloadNodeIds.contains(nodeId);
            boolean hasK8s = k8sWorkloadNodeIds.contains(nodeId);
            boolean hasSpringRole = !roles.rolesByNodeId.getOrDefault(nodeId, Set.of()).isEmpty();
            boolean hasSpringApp = bestIdentityByDir.containsKey(bc.normDir());
            boolean hasPort = !roles.portsByNodeId.getOrDefault(nodeId, Set.of()).isEmpty();
            boolean hasEp = dirsWithEndpointsOrRoutes.contains(bc.normDir());
            boolean hasCallOrRpc = dirsWithServiceCallsOrRpc.contains(bc.normDir());
            boolean hasEv = !roles.eventProducerProtosByNodeId.getOrDefault(nodeId, Set.of()).isEmpty()
                    || !roles.eventConsumerProtosByNodeId.getOrDefault(nodeId, Set.of()).isEmpty();
            String pkg = packagingByDir.getOrDefault(bc.normDir(), "");
            boolean execPkg = executableDirs.contains(bc.normDir());

            var role = CanonicalServiceIdentityResolver.classifyBuildComponent(
                    new CanonicalServiceIdentityResolver.ClassificationSignals(
                            false, false, false, bc.tool(), bc.compId(), bc.languages(),
                            pkg, execPkg, hasDf, hasCompose, hasK8s,
                            hasSpringRole, hasSpringApp, hasPort, hasEp, hasCallOrRpc, hasEv,
                            totalLeafBuildCount, repoHasDeployableServices, repoHasContainerServices));
            List<String> layers = resolver.evidenceLayers(nodeId);
            if (layers.isEmpty()) layers = List.of("BUILD");
            var nodeConcordance = classifyConcordance(layers, false);

            var baseNode = additions.getOrDefault(nodeKey, bc.node());
            if (role == CanonicalServiceIdentityResolver.ComponentRole.SHARED_LIBRARY_MODULE) {
                var classProof = GraphEvidence.capture("architecture.component-classification", Map.of(
                        "provider", PROVIDER,
                        "role", role.name(),
                        "isTestOnlyModule", false,
                        "directory", bc.rawDir(),
                        "packaging", pkg,
                        "evidenceLayers", layers,
                        "concordance", nodeConcordance.name(),
                        "trust", "DECLARATIVE_CONFIG",
                        "runtimeObserved", false));
                evidence.putIfAbsent(classProof.identity().value(), classProof);
                var combined = new TreeSet<>(baseNode.evidence());
                combined.add(classProof.identity());
                additions.put(nodeKey, new KnowledgeGraph.Node(nodeId, bc.node().context(),
                        "SHARED_LIBRARY_MODULE", List.copyOf(combined)));
            } else {
                IdentityOrigin origin = identityOriginByNodeId.get(nodeId);
                String displayServiceName = origin != null ? origin.serviceName() : svcName;
                String spec = origin != null ? origin.spec() : "cross-layer-reconciliation";
                var idFields = new LinkedHashMap<String, Object>();
                idFields.put("provider", PROVIDER);
                idFields.put("serviceName", displayServiceName);
                idFields.put("name", svcName);
                idFields.put("directory", bc.rawDir());
                if (origin != null && origin.sourceEvidence() != null) {
                    idFields.put("sourceEvidence", origin.sourceEvidence());
                }
                idFields.put("spec", spec);
                idFields.put("hasDockerfile", hasDf);
                idFields.put("componentRole", role.name());
                idFields.put("isTestOnlyModule", false);
                idFields.put("evidenceLayers", layers);
                idFields.put("concordance", nodeConcordance.name());
                idFields.put("trust", "DECLARATIVE_CONFIG");
                idFields.put("runtimeObserved", false);
                var idProof = GraphEvidence.capture("architecture.service-identity", idFields);
                evidence.putIfAbsent(idProof.identity().value(), idProof);
                var combined = new TreeSet<>(baseNode.evidence());
                combined.add(idProof.identity());
                String targetKind = role == CanonicalServiceIdentityResolver.ComponentRole.SERVICE_COMPONENT
                        ? "SERVICE_COMPONENT" : baseNode.kind();
                additions.put(nodeKey, new KnowledgeGraph.Node(nodeId, bc.node().context(),
                        targetKind, List.copyOf(combined)));
            }
        }

        // Attach final multi-layer evidenceLayers and explicit concordance onto EVERY promoted INFRA_COMPONENT
        // while preserving the invariant that INFRA_COMPONENT carries only architecture.infra-component evidence.
        for (var entry : infraOriginMeta.entrySet()) {
            String infraId = entry.getKey();
            var mergedLayers = new TreeSet<String>(resolver.evidenceLayers(infraId));
            mergedLayers.addAll(infraAppLayersByNodeId.getOrDefault(infraId, Set.of()));
            List<String> finalLayers = List.copyOf(mergedLayers);
            var infraConcordance = classifyConcordance(finalLayers, false);
            var meta = entry.getValue();
            var infraFields = new LinkedHashMap<String, Object>();
            infraFields.put("provider", PROVIDER);
            infraFields.put("name", meta.name());
            infraFields.put("image", meta.image());
            infraFields.put("spec", meta.spec());
            if (meta.sourceProof() != null) {
                infraFields.put("sourceEvidence", meta.sourceProof());
            }
            if (!finalLayers.isEmpty()) {
                infraFields.put("evidenceLayers", finalLayers);
            }
            infraFields.put("concordance", infraConcordance.name());
            infraFields.put("trust", "DECLARATIVE_CONFIG");
            infraFields.put("runtimeObserved", false);
            var updatedProof = GraphEvidence.capture("architecture.infra-component", infraFields);
            evidence.putIfAbsent(updatedProof.identity().value(), updatedProof);
            additions.put(root + "|" + infraId, new KnowledgeGraph.Node(infraId, root, "INFRA_COMPONENT",
                    List.of(updatedProof.identity())));
        }

        materializePendingEdges(pendingEdges, addedEdges, evidence, root);

        nodes.putAll(additions);
        edges.putAll(addedEdges);
    }

    /**
     * Computes explicit 3-tier multi-layer evidence concordance (AND/OR):
     * <ul>
     *   <li>{@code DERIVED_DFD_ORIENTATION}: emitted strictly for {@code DFD_EXPORT} policy/orientation edges and actors.</li>
     *   <li>{@code HIGH_CONFIDENCE_CONCORDANT} ({@code AND}): corroborated simultaneously by deployment layer
     *       ({@code COMPOSE}/{@code K8S}) AND application source/config/build layer.</li>
     *   <li>{@code SINGLE_LAYER_CONFIRMED} ({@code OR}): evidenced by a single concrete layer family.</li>
     * </ul>
     */
    static ServiceArchitectureProjection.EvidenceConcordance classifyConcordance(
            Collection<String> layers, boolean dfdDerived) {
        if (dfdDerived) {
            return ServiceArchitectureProjection.EvidenceConcordance.DERIVED_DFD_ORIENTATION;
        }
        boolean hasDeployment = false;
        boolean hasApplication = false;
        if (layers != null) {
            for (String layer : layers) {
                if (DEPLOYMENT_LAYERS.contains(layer)) {
                    hasDeployment = true;
                } else if (APPLICATION_LAYERS.contains(layer)) {
                    hasApplication = true;
                }
            }
        }
        return (hasDeployment && hasApplication)
                ? ServiceArchitectureProjection.EvidenceConcordance.HIGH_CONFIDENCE_CONCORDANT
                : ServiceArchitectureProjection.EvidenceConcordance.SINGLE_LAYER_CONFIRMED;
    }

    private static void recordOrientedEdge(
            ServiceArchitectureProjection.ProjectionProfile profile,
            Map<String, PendingEdge> pendingEdges,
            Set<String> existingCalls,
            Map<String, Set<String>> infraAppLayersByNodeId,
            ServiceEntry caller, ServiceEntry callee,
            ContentDigest support, String mechanism, String baseTrust,
            String sourceLayer, boolean dfdPolicyEdge,
            ProtocolOrientationRole defaultDfdRole,
            RoleContext roles) {
        boolean directEvidence = !dfdPolicyEdge;
        if (directEvidence && APPLICATION_LAYERS.contains(sourceLayer)) {
            if (roles.infraNodeIds.contains(caller.nodeId())) {
                infraAppLayersByNodeId.computeIfAbsent(caller.nodeId(), k -> new TreeSet<>()).add(sourceLayer);
            }
            if (roles.infraNodeIds.contains(callee.nodeId())) {
                infraAppLayersByNodeId.computeIfAbsent(callee.nodeId(), k -> new TreeSet<>()).add(sourceLayer);
            }
        }
        if (profile == ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT) {
            Optional<ProtocolOrientationRole> reversedRole = resolveDfdOrientationRole(caller, callee, mechanism, roles);
            if (reversedRole.isPresent()) {
                recordTopologyEdge(pendingEdges, existingCalls, callee, caller,
                        support, mechanism, "DERIVED_DFD_POLICY", sourceLayer, true, reversedRole.get(), directEvidence);
                return;
            }
        }
        recordTopologyEdge(pendingEdges, existingCalls, caller, callee,
                support, mechanism, baseTrust, sourceLayer, dfdPolicyEdge,
                dfdPolicyEdge ? (defaultDfdRole != null ? defaultDfdRole : ProtocolOrientationRole.DFD_POLICY_SYNTHESIS) : null,
                directEvidence);
    }

    /**
     * Generic architectural protocol-role DFD data-flow orientation (invoked ONLY under {@code DFD_EXPORT}).
     * Driven strictly by standardized {@link ProtocolOrientationRole} semantics ({@code DATA_STORE},
     * {@code CONFIG_SERVER}, {@code DISCOVERY_REGISTRY}, {@code BROKER_CONSUMER}, etc.) with zero
     * application-specific exceptions. Never alters {@code STRICT} caller-&gt;callee dependency orientation.
     */
    private static Optional<ProtocolOrientationRole> resolveDfdOrientationRole(
            ServiceEntry caller, ServiceEntry callee, String mechanism, RoleContext roles) {
        if (Set.of("GATEWAY_ROUTE", "ZUUL_EUREKA_AUTO_PROXY", "JAVA_SERVICE_HOST_CALL",
                "DFD_EXTERNAL_ENTRY_POINT", "DFD_EXTERNAL_ENTRY_RESPONSE",
                "DFD_DOCKER_SOCKET_MONITOR", "DFD_EXTERNAL_HTTPS_API",
                "DFD_HTTP_TURBINE_CIRCUIT_BREAKER").contains(mechanism)) {
            return Optional.empty();
        }
        // 1. CONFIG_SERVER: config server -> client
        if ("SPRING_CONFIG_CONFIG-CLIENT".equals(mechanism) || "STARTER_CONFIG_CLIENT".equals(mechanism)
                || (roles.isConfigServer(callee) && !roles.isGitRepo(caller))) {
            return Optional.of(ProtocolOrientationRole.CONFIG_SERVER);
        }
        // 2. CONFIG_SOURCE_REPO: git-repo -> config server
        if ("SPRING_CONFIG_CONFIG-GIT".equals(mechanism) || "SPRING_CONFIG_CONFIG-GIT-REPO".equals(mechanism)
                || (roles.isGitRepo(callee) && roles.isConfigServer(caller))) {
            return Optional.of(ProtocolOrientationRole.CONFIG_SOURCE_REPO);
        }
        // 3. DISCOVERY_REGISTRY: consul -> non-consul client; discovery -> gateway / fetch-only client
        if (roles.isConsul(callee) && !roles.isConsul(caller)) {
            return Optional.of(ProtocolOrientationRole.DISCOVERY_REGISTRY);
        }
        if (roles.isDiscovery(callee) && (roles.isGateway(caller) || roles.fetchesOnlyDiscovery(caller))) {
            return Optional.of(ProtocolOrientationRole.DISCOVERY_REGISTRY);
        }
        // 4. DATA_STORE: datastore -> service (DFD persisted state supply)
        if (roles.isDatastore(callee) && !roles.isDatastore(caller)) {
            return Optional.of(ProtocolOrientationRole.DATA_STORE);
        }
        // 5. BROKER_CONSUMER: message broker -> stream consumer or pure event consumer
        if (roles.isBroker(callee) && (roles.isStreamConsumer(caller) || roles.isPureBrokerConsumer(caller, callee))) {
            return Optional.of(ProtocolOrientationRole.BROKER_CONSUMER);
        }
        // 6. OBSERVABILITY_PIPELINE: subject -> prometheus -> grafana; elasticsearch -> kibana; service -> hystrix/turbine
        if ("PROMETHEUS_SCRAPE".equals(mechanism) || (roles.isPrometheus(caller) && !roles.isGrafana(callee))) {
            return Optional.of(ProtocolOrientationRole.OBSERVABILITY_PIPELINE);
        }
        if ("SPRING_CONFIG_GRAFANA-DATASOURCE".equals(mechanism)
                || (roles.isGrafana(caller) && (roles.isPrometheus(callee) || roles.isElasticsearch(callee) || roles.isDatastore(callee)))) {
            return Optional.of(ProtocolOrientationRole.OBSERVABILITY_PIPELINE);
        }
        if (roles.isKibana(caller) && roles.isElasticsearch(callee)) {
            return Optional.of(ProtocolOrientationRole.OBSERVABILITY_PIPELINE);
        }
        if ("SPRING_CONFIG_TURBINE-MONITOR".equals(mechanism)
                || (roles.isHystrixOrTurbine(caller) && callee.hasCodeBuild() && !roles.isControlPlane(callee))) {
            return Optional.of(ProtocolOrientationRole.OBSERVABILITY_PIPELINE);
        }
        // 7. COORDINATION_SERVICE: zookeeper -> kafka
        if (roles.isKafka(caller) && roles.isZookeeper(callee)) {
            return Optional.of(ProtocolOrientationRole.COORDINATION_SERVICE);
        }
        // 8. CONTROL_PLANE_ADMIN: admin server -> managed service
        if ("SPRING_CONFIG_ADMIN-CLIENT".equals(mechanism) || "STARTER_BOOT_ADMIN_CLIENT".equals(mechanism)
                || (roles.isAdminServer(callee) && !roles.isDiscovery(caller) && !roles.isConfigServer(caller))) {
            return Optional.of(ProtocolOrientationRole.CONTROL_PLANE_ADMIN);
        }
        // 9. AUTH_PROVIDER: auth server -> resource-service (never reverse gateway -> auth unless OAUTH2_SSO without explicit route)
        boolean oauthCallerEligible = !roles.isGateway(caller)
                || (roles.hasRole(caller, "OAUTH2_SSO") && !roles.gatewayRouteToAuthNodeIds.contains(caller.nodeId()));
        if (oauthCallerEligible
                && ("SPRING_CONFIG_OAUTH2-AUTH".equals(mechanism) || "STARTER_OAUTH2_RESOURCE".equals(mechanism)
                || (roles.isAuthServer(callee) && roles.isOAuth2Resource(caller)))) {
            return Optional.of(ProtocolOrientationRole.AUTH_PROVIDER);
        }
        return Optional.empty();
    }

    private static void recordTopologyEdge(
            Map<String, PendingEdge> pendingEdges,
            Set<String> existingCalls,
            ServiceEntry src, ServiceEntry dst,
            ContentDigest support, String mechanism, String trust,
            String sourceLayer, boolean dfdDerived,
            ProtocolOrientationRole orientationRole,
            boolean directEvidence) {
        String key = src.nodeId() + "->" + dst.nodeId();
        if (existingCalls.contains(key)) {
            return;
        }
        PendingEdge existing = pendingEdges.get(key);
        if (existing == null) {
            pendingEdges.put(key, new PendingEdge(src, dst, mechanism, trust, dfdDerived,
                    orientationRole, directEvidence, support, sourceLayer));
            return;
        }
        // Merge corroborating direct evidence across deployment and application layers (AND concordance);
        // never let a DFD_EXPORT policy synthesis edge overwrite or pollute an already-evidenced direct edge.
        if (directEvidence || !existing.hasDirectEvidence) {
            existing.mergeCorroboration(mechanism, support, sourceLayer, directEvidence);
        }
    }

    private static void materializePendingEdges(
            Map<String, PendingEdge> pendingEdges,
            Map<String, KnowledgeGraph.Edge> edges,
            Map<String, GraphEvidence> evidence,
            String root) {
        for (var pending : pendingEdges.values()) {
            var concordance = classifyConcordance(pending.layers, pending.dfdDerived);
            var proofFields = new LinkedHashMap<String, Object>();
            proofFields.put("provider", PROVIDER);
            proofFields.put("kind", "CALLS_SERVICE");
            proofFields.put("mechanism", pending.primaryMechanism);
            proofFields.put("mechanisms", List.copyOf(pending.mechanisms));
            proofFields.put("source", pending.src.canonicalName());
            proofFields.put("target", pending.dst.canonicalName());
            proofFields.put("support", List.copyOf(pending.supports));
            proofFields.put("evidenceLayers", List.copyOf(pending.layers));
            proofFields.put("concordance", concordance.name());
            if (pending.orientationRole != null) {
                proofFields.put("orientationRole", pending.orientationRole.name());
                if (pending.hasDirectEvidence) {
                    proofFields.put("underlyingConcordance",
                            classifyConcordance(pending.layers, false).name());
                }
            }
            proofFields.put("trust", pending.trust);
            proofFields.put("runtimeObserved", false);
            var proof = GraphEvidence.capture("architecture.declarative-topology", proofFields);
            evidence.putIfAbsent(proof.identity().value(), proof);
            var target = new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,
                    List.of(pending.dst.nodeId()), Optional.empty());
            String id = KnowledgeGraph.derivedIdentity("declarative-service-edge",
                    List.of(PROVIDER, root, pending.src.nodeId(), target, pending.primaryMechanism, pending.trust));
            var edgeEvidence = new TreeSet<ContentDigest>(pending.supports);
            edgeEvidence.add(proof.identity());
            edges.put(root + "|" + id, new KnowledgeGraph.Edge(id, root, "CALLS_SERVICE", pending.src.nodeId(),
                    target, List.copyOf(edgeEvidence), Optional.empty()));
        }
    }

    private static void refreshRoleOwners(RoleContext roles, Collection<ServiceEntry> buildServices,
            Map<String, ServiceEntry> servicesByName) {
        roles.configServer = findRoleOwner(roles, buildServices, servicesByName,
                Set.of("CONFIG_SERVER"), List.of("config", "config-server", "config-service"));
        roles.eurekaServer = findRoleOwner(roles, buildServices, servicesByName,
                Set.of("EUREKA_SERVER"), List.of("eureka", "eureka-server", "registry", "discovery", "discovery-server"));
        roles.zipkinServer = findRoleOwner(roles, buildServices, servicesByName,
                Set.of("ZIPKIN_SERVER", "ZIPKIN_STREAM_SERVER"), List.of("zipkin", "zipkin-server"));
        if (roles.zipkinServer == null) {
            roles.zipkinServer = findUniqueByNameToken(servicesByName, "zipkin");
        }
        if (roles.zipkinServer == null) {
            roles.zipkinServer = findUniqueByImageToken(servicesByName, roles, "zipkin");
        }
        if (roles.zipkinServer == null) {
            roles.zipkinServer = findUniqueByPort(servicesByName, roles, "9411");
        }
        roles.adminServer = findRoleOwner(roles, buildServices, servicesByName,
                Set.of("ADMIN_SERVER"), List.of("admin", "admin-server", "spring-boot-admin"));
        roles.authServer = findRoleOwner(roles, buildServices, servicesByName,
                Set.of("OAUTH2_AUTH_SERVER"), List.of("auth", "auth-service", "uaa", "oauth", "oauth2", "authorization-server"));
        roles.hystrixDashboard = findRoleOwner(roles, buildServices, servicesByName,
                Set.of("HYSTRIX_DASHBOARD"), List.of("hystrix-dashboard", "hystrix"));
        roles.turbineServer = findRoleOwner(roles, buildServices, servicesByName,
                Set.of("TURBINE", "TURBINE_STREAM"), List.of("turbine", "turbine-server", "turbine-stream"));
    }

    private static ServiceEntry findRoleOwner(RoleContext roles, Collection<ServiceEntry> buildServices,
            Map<String, ServiceEntry> servicesByName, Set<String> roleNames, List<String> fallbackNames) {
        for (var se : buildServices) {
            if (roles.hasAnyRole(se, roleNames)) return se;
        }
        for (String name : fallbackNames) {
            ServiceEntry se = servicesByName.get(name);
            if (se != null) return se;
        }
        return null;
    }

    private static ServiceEntry findUniqueByNameToken(Map<String, ServiceEntry> servicesByName, String token) {
        String normTok = normalizeName(token);
        ServiceEntry found = null;
        for (var se : servicesByName.values()) {
            if (hasToken(se.canonicalName(), normTok)) {
                if (found != null && !found.nodeId().equals(se.nodeId())) return null;
                found = se;
            }
        }
        return found;
    }

    private static ServiceEntry findUniqueByPort(Map<String, ServiceEntry> servicesByName, RoleContext roles, String port) {
        Set<String> singlePort = Set.of(port);
        ServiceEntry found = null;
        for (var se : servicesByName.values()) {
            if (roles.hasAnyPort(se, singlePort)) {
                if (found != null && !found.nodeId().equals(se.nodeId())) return null;
                found = se;
            }
        }
        return found;
    }

    private static ServiceEntry findUniqueByImageToken(Map<String, ServiceEntry> servicesByName, RoleContext roles, String token) {
        String lowerTok = token.toLowerCase(Locale.ROOT);
        ServiceEntry found = null;
        for (var se : servicesByName.values()) {
            if (roles.imageOf(se).toLowerCase(Locale.ROOT).contains(lowerTok)) {
                if (found != null && !found.nodeId().equals(se.nodeId())) return null;
                found = se;
            }
        }
        return found;
    }

    private static boolean hasAnyConsul(Map<String, ServiceEntry> servicesByName, RoleContext roles) {
        for (var se : servicesByName.values()) {
            if (roles.isConsul(se)) return true;
        }
        return false;
    }

    private static ServiceEntry matchExistingServiceForDecl(String norm, String buildDir, String containerName,
            String imageTail, String img, List<String> buildDirsByLength,
            Map<String, ServiceEntry> servicesByDir, Map<String, ServiceEntry> servicesByName,
            Collection<ServiceEntry> leafBuildServices, RoleContext roles) {
        // 1. Exact or prefix match via Docker Compose buildDir
        if (!buildDir.isEmpty()) {
            ServiceEntry byDir = servicesByDir.get(buildDir);
            if (byDir != null) return byDir;
            for (String bdir : buildDirsByLength) {
                if (buildDir.equals(bdir) || buildDir.startsWith(bdir + "/")) {
                    ServiceEntry se = servicesByDir.get(bdir);
                    if (se != null) return se;
                }
            }
            if (!buildDir.contains("/")) {
                ServiceEntry uniqueByBase = findUniqueByDirBasename(buildDir, leafBuildServices);
                if (uniqueByBase != null) return uniqueByBase;
            }
        }
        // 2. Exact match on service key, container_name, or code-build image tail (never collapse infra containers by imageTail)
        ServiceEntry exact = servicesByName.get(norm);
        if (exact != null) return exact;
        if (!containerName.isEmpty()) {
            ServiceEntry byContainer = servicesByName.get(containerName);
            if (byContainer != null) return byContainer;
        }
        if (!imageTail.isEmpty() && !isDatastoreOrBrokerOrLogInfra(norm, img)) {
            ServiceEntry byImg = servicesByName.get(imageTail);
            if (byImg != null && byImg.hasCodeBuild() && !isInfraNameOrImage(imageTail, img)) {
                return byImg;
            }
        }
        // 3. Unique Spring Cloud role match (e.g. compose "gateway" -> @EnableZuulProxy, "eureka*" -> @EnableEurekaServer)
        if (!isDatastoreOrBrokerOrLogInfra(norm, img)) {
            ServiceEntry byRole = matchUniqueControlRoleAlias(norm, leafBuildServices, roles);
            if (byRole != null) return byRole;
        }
        // 4. Morphological base-token / suffix alias match onto a unique leaf BUILD_COMPONENT
        if (!isDatastoreOrBrokerOrLogInfra(norm, img)) {
            ServiceEntry byToken = findUniqueBaseTokenMatch(norm, leafBuildServices);
            if (byToken != null) return byToken;
            if (norm.length() >= 3) {
                ServiceEntry bySuffix = findUniqueSuffixMatch(norm, leafBuildServices);
                if (bySuffix != null) return bySuffix;
            }
            if (!containerName.isEmpty()) {
                byToken = findUniqueBaseTokenMatch(containerName, leafBuildServices);
                if (byToken != null) return byToken;
            }
            if (!imageTail.isEmpty() && !isInfraNameOrImage(imageTail, img)) {
                byToken = findUniqueBaseTokenMatch(imageTail, leafBuildServices);
                if (byToken != null) return byToken;
            }
        }
        return null;
    }

    private static ServiceEntry findUniqueSuffixMatch(String norm, Collection<ServiceEntry> leafBuildServices) {
        String suffix = "-" + norm;
        ServiceEntry found = null;
        for (var se : leafBuildServices) {
            String canonDash = se.canonicalName().replace('.', '-');
            String dirDash = normalizeName(baseOrDirName(se.dirPrefix(), "")).replace('.', '-');
            if (canonDash.endsWith(suffix) || dirDash.endsWith(suffix)) {
                if (found != null && !found.nodeId().equals(se.nodeId())) return null;
                found = se;
            }
        }
        return found;
    }

    private static ServiceEntry matchUniqueControlRoleAlias(String norm, Collection<ServiceEntry> leafBuildServices,
            RoleContext roles) {
        String collapsed = norm.replace("-", "");
        Set<String> targetRoles = null;
        if (collapsed.equals("gateway") || collapsed.equals("zuul") || collapsed.equals("apigateway") || collapsed.equals("edge")) {
            targetRoles = Set.of("ZUUL_PROXY", "API_GATEWAY");
        } else if (collapsed.startsWith("eureka") || collapsed.equals("registry") || collapsed.equals("discovery")
                || collapsed.equals("discoveryserver") || collapsed.equals("discoveryservice")) {
            targetRoles = Set.of("EUREKA_SERVER");
        } else if (collapsed.equals("config") || collapsed.equals("configserver")) {
            targetRoles = Set.of("CONFIG_SERVER");
        } else if (collapsed.equals("zipkin") || collapsed.equals("zipkinserver")) {
            targetRoles = Set.of("ZIPKIN_SERVER", "ZIPKIN_STREAM_SERVER");
        } else if (collapsed.equals("monitor") || collapsed.equals("monitoring") || collapsed.equals("hystrix")
                || collapsed.equals("turbine")) {
            targetRoles = Set.of("HYSTRIX_DASHBOARD", "TURBINE", "TURBINE_STREAM");
        } else if (collapsed.equals("admin") || collapsed.equals("adminserver")) {
            targetRoles = Set.of("ADMIN_SERVER");
        }
        if (targetRoles == null) return null;
        ServiceEntry found = null;
        for (var se : leafBuildServices) {
            if (roles.hasAnyRole(se, targetRoles)) {
                if (found != null) return null; // ambiguous
                found = se;
            }
        }
        return found;
    }

    private static ServiceEntry findUniqueByDirBasename(String basename, Collection<ServiceEntry> leafBuildServices) {
        String target = normalizeName(basename);
        ServiceEntry found = null;
        for (var se : leafBuildServices) {
            String dirBase = normalizeName(baseOrDirName(se.dirPrefix(), ""));
            if (dirBase.equals(target)) {
                if (found != null) return null;
                found = se;
            }
        }
        return found;
    }

    private static ServiceEntry findUniqueBaseTokenMatch(String raw, Collection<ServiceEntry> leafBuildServices) {
        String target = canonicalBaseToken(raw);
        if (target.length() < 2) return null;
        ServiceEntry found = null;
        for (var se : leafBuildServices) {
            String nameTok = canonicalBaseToken(se.canonicalName());
            String dirTok = canonicalBaseToken(baseOrDirName(se.dirPrefix(), ""));
            if (target.equals(nameTok) || (!dirTok.isEmpty() && target.equals(dirTok))) {
                if (found != null && !found.nodeId().equals(se.nodeId())) return null;
                found = se;
            }
        }
        return found;
    }

    private static ServiceEntry resolveServiceByNameOrAlias(String raw, Map<String, ServiceEntry> servicesByName,
            Collection<ServiceEntry> leafBuildServices) {
        String norm = normalizeName(raw);
        if (norm.isEmpty()) return null;
        ServiceEntry exact = servicesByName.get(norm);
        if (exact != null) return exact;
        return findUniqueBaseTokenMatch(norm, leafBuildServices);
    }

    private static ServiceEntry resolveOwnerService(String dir, String path, Map<String, ServiceEntry> servicesByDir) {
        if (path != null && !path.isEmpty()) {
            ServiceEntry best = CanonicalServiceIdentityResolver.resolveNearestDir(path, servicesByDir);
            if (best != null) return best;
        }
        if (dir != null && !dir.isEmpty()) {
            ServiceEntry exact = servicesByDir.get(dir);
            if (exact != null) return exact;
        }
        return dir != null && dir.isEmpty() ? servicesByDir.get("") : null;
    }

    private static ServiceEntry resolvePropOwner(String repoFile, String dir,
            Map<String, ServiceEntry> servicesByDir, Map<String, ServiceEntry> servicesByName,
            Collection<ServiceEntry> leafBuildServices) {
        if (!repoFile.isEmpty() && !isSharedApplicationConfigFile(repoFile)) {
            return resolveServiceByNameOrAlias(repoFile, servicesByName, leafBuildServices);
        }
        return !dir.isEmpty() ? servicesByDir.get(dir) : null;
    }

    private static ServiceEntry resolveTargetWithLoopback(String target, String port, String role,
            Map<String, ServiceEntry> uniquePortToService, RoleContext roles,
            java.util.function.Function<String, ServiceEntry> resolveOrPromoteTarget,
            Map<String, ServiceEntry> servicesByName) {
        if (target == null || target.isBlank()) return null;
        if (isLoopback(target)) {
            if (port != null && !port.isBlank()) {
                ServiceEntry byPort = uniquePortToService.get(port.trim());
                if (byPort != null) return byPort;
            }
            return resolveLoopbackByRoleOrPort(role, port, Map.of(), roles, resolveOrPromoteTarget, servicesByName);
        }
        return resolveOrPromoteTarget.apply(target);
    }

    private static ServiceEntry resolveConfigRefDestination(String role, String targetName, String port,
            Map<String, Object> attrs, Map<String, ServiceEntry> uniquePortToService, RoleContext roles,
            java.util.function.Function<String, ServiceEntry> resolveOrPromoteTarget,
            Map<String, ServiceEntry> servicesByName) {
        if (isLoopback(targetName)) {
            // Role-before-port precedence for standard Spring Cloud control-plane roles
            ServiceEntry byRole = switch (role) {
                case "config-client" -> roles.configServer;
                case "eureka-client" -> roles.eurekaServer;
                case "admin-client" -> roles.adminServer;
                case "oauth2-auth" -> roles.authServer;
                case "zipkin" -> roles.zipkinServer;
                default -> null;
            };
            if (byRole != null) return byRole;
            if (port != null && !port.isBlank()) {
                ServiceEntry byPort = uniquePortToService.get(port.trim());
                if (byPort != null && isPortMatchCompatibleWithRole(role, byPort, roles)) {
                    return byPort;
                }
            }
            return resolveLoopbackByRoleOrPort(role, port, attrs, roles, resolveOrPromoteTarget, servicesByName);
        }
        return switch (role) {
            case "config-client" -> roles.configServer != null ? roles.configServer : resolveOrPromoteTarget.apply(targetName);
            case "eureka-client" -> roles.eurekaServer != null ? roles.eurekaServer : resolveOrPromoteTarget.apply(targetName);
            case "admin-client" -> roles.adminServer != null ? roles.adminServer : resolveOrPromoteTarget.apply(targetName);
            case "oauth2-auth" -> roles.authServer != null ? roles.authServer : resolveOrPromoteTarget.apply(targetName);
            case "zipkin" -> roles.zipkinServer != null ? roles.zipkinServer : resolveOrPromoteTarget.apply(targetName);
            case "smtp" -> resolveSmtpTarget(targetName, servicesByName, roles, resolveOrPromoteTarget);
            case "logstash" -> {
                ServiceEntry existing = findExistingInfra(servicesByName, roles, true, "logstash", "elk");
                if (existing != null) yield existing;
                ServiceEntry explicit = resolveOrPromoteTarget.apply(targetName);
                yield explicit != null ? explicit : resolveOrPromoteTarget.apply("logstash");
            }
            default -> resolveOrPromoteTarget.apply(targetName);
        };
    }

    private static boolean isPortMatchCompatibleWithRole(String role, ServiceEntry candidate, RoleContext roles) {
        if ("config-client".equals(role) && roles.isDiscovery(candidate)) return false;
        if ("eureka-client".equals(role) && roles.isConfigServer(candidate)) return false;
        return true;
    }

    private static ServiceEntry resolveSmtpTarget(String targetName, Map<String, ServiceEntry> servicesByName,
            RoleContext roles, java.util.function.Function<String, ServiceEntry> resolveOrPromoteTarget) {
        ServiceEntry existing = findExistingInfra(servicesByName, roles, true,
                "smtp", "mail", "mailhog", "maildev", "mailpit", "postfix");
        if (existing != null) return existing;
        // Match any declared non-code infra component with token "smtp"/"mail" or exposing an IANA SMTP port
        ServiceEntry bySignal = null;
        for (var se : servicesByName.values()) {
            if (se.hasCodeBuild()) continue;
            String n = se.canonicalName();
            if (hasToken(n, "smtp") || hasToken(n, "mail") || roles.hasAnyPort(se, SMTP_WELL_KNOWN_PORTS)) {
                if (bySignal != null && !bySignal.nodeId().equals(se.nodeId())) {
                    bySignal = null;
                    break;
                }
                bySignal = se;
            }
        }
        if (bySignal != null) return bySignal;
        String norm = normalizeName(targetName);
        if (!norm.isEmpty() && !isLoopback(norm) && !"smtp".equals(norm) && !"mail".equals(norm)) {
            ServiceEntry explicit = servicesByName.get(norm);
            if (explicit != null && !explicit.hasCodeBuild()) return explicit;
        }
        return resolveOrPromoteTarget.apply("smtp");
    }

    private static ServiceEntry resolveLoopbackByRoleOrPort(String role, String port, Map<String, Object> attrs,
            RoleContext roles, java.util.function.Function<String, ServiceEntry> resolveOrPromoteTarget,
            Map<String, ServiceEntry> servicesByName) {
        if (role == null) role = "";
        return switch (role) {
            case "config-client" -> roles.configServer;
            case "eureka-client" -> roles.eurekaServer;
            case "admin-client" -> roles.adminServer;
            case "oauth2-auth" -> roles.authServer;
            case "zipkin" -> roles.zipkinServer != null ? roles.zipkinServer : resolveOrPromoteTarget.apply("zipkin");
            case "consul" -> resolveOrPromoteTarget.apply("consul");
            case "rabbitmq" -> resolveOrPromoteTarget.apply("rabbitmq");
            case "kafka" -> resolveOrPromoteTarget.apply("kafka");
            case "redis" -> resolveOrPromoteTarget.apply("redis");
            case "mongodb" -> resolveOrPromoteTarget.apply("mongodb");
            case "smtp" -> resolveSmtpTarget("", servicesByName, roles, resolveOrPromoteTarget);
            case "logstash" -> {
                ServiceEntry elk = findExistingInfra(servicesByName, roles, true, "logstash", "elk");
                yield elk != null ? elk : resolveOrPromoteTarget.apply("logstash");
            }
            case "datasource" -> {
                String vendor = inferDatasourceVendor(attrs, port);
                yield vendor.isEmpty() ? null : resolveOrPromoteTarget.apply(vendor);
            }
            case "grafana-datasource" -> {
                String dsType = str(attrs.get("datasourceType")).toLowerCase(Locale.ROOT);
                if (dsType.contains("prometheus") || "9090".equals(port)) {
                    yield resolveOrPromoteTarget.apply("prometheus");
                }
                if (dsType.contains("elasticsearch")) {
                    yield resolveOrPromoteTarget.apply("elasticsearch");
                }
                yield null;
            }
            default -> null;
        };
    }

    private static boolean isDatastoreConfigRole(String role) {
        return "datasource".equals(role) || "mongodb".equals(role) || "redis".equals(role);
    }

    private static boolean isFallbackDatastoreRef(String target, Map<String, Object> attrs) {
        if (isLoopback(target)) return true;
        String normTarget = normalizeName(target);
        if (EMBEDDED_DB_TOKENS.contains(normTarget)) return true;
        String dbVendor = str(attrs.get("dbVendor")).toLowerCase(Locale.ROOT);
        return EMBEDDED_DB_TOKENS.contains(dbVendor);
    }

    private static boolean hasExplicitDatastoreFamilyEdge(ServiceEntry src, String role,
            Set<String> withDatastore, Set<String> withMongo, Set<String> withRedis) {
        return switch (role) {
            case "mongodb" -> withMongo.contains(src.nodeId());
            case "redis" -> withRedis.contains(src.nodeId());
            case "datasource" -> withDatastore.contains(src.nodeId());
            default -> false;
        };
    }

    private static String inferDatasourceVendor(Map<String, Object> attrs, String port) {
        String dbVendor = str(attrs.get("dbVendor")).toLowerCase(Locale.ROOT);
        String vendor = str(attrs.get("vendor")).toLowerCase(Locale.ROOT);
        String rawUrl = str(attrs.get("url")).toLowerCase(Locale.ROOT);
        String combined = dbVendor + " " + vendor + " " + rawUrl;
        if (combined.contains("mysql")) return "mysql";
        if (combined.contains("postgres")) return "postgres";
        if (combined.contains("mariadb")) return "mariadb";
        if (combined.contains("h2")) return "h2";
        if (combined.contains("hsqldb")) return "hsqldb";
        if (combined.contains("sqlite")) return "sqlite";
        if ("3306".equals(port)) return "mysql";
        if ("5432".equals(port)) return "postgres";
        if ("27017".equals(port)) return "mongodb";
        return "";
    }

    private static List<ServiceEntry> filterSharedConfigClients(Collection<ServiceEntry> configClients, String role,
            boolean hasRepoStarters, Map<String, Map<String, ContentDigest>> startersByService, RoleContext roles) {
        if (configClients.isEmpty()) return List.of();
        var filtered = new ArrayList<ServiceEntry>();
        for (var se : configClients) {
            if (!hasRepoStarters) {
                filtered.add(se);
                continue;
            }
            var starters = startersByService.getOrDefault(se.nodeId(), Map.of());
            boolean keep = switch (role) {
                case "eureka-client" -> !roles.isEurekaDisabled(se)
                        && (starters.containsKey("eureka-client")
                        || roles.hasRole(se, "EUREKA_CLIENT")
                        || roles.hasRole(se, "DISCOVERY_CLIENT"));
                case "rabbitmq" -> starters.containsKey("bus-amqp") || starters.containsKey("stream-rabbit")
                        || starters.containsKey("hystrix-stream") || roles.isStreamConsumer(se);
                case "kafka" -> starters.containsKey("stream-kafka");
                case "zipkin" -> starters.containsKey("zipkin") || starters.containsKey("sleuth");
                case "oauth2-auth" -> roles.isOAuth2Resource(se) || starters.containsKey("oauth2-resource")
                        || starters.containsKey("oauth2-client");
                case "admin-client" -> starters.containsKey("boot-admin-client");
                case "mongodb" -> starters.containsKey("data-mongodb");
                case "redis" -> starters.containsKey("data-redis");
                case "datasource" -> starters.containsKey("data-jpa") || starters.containsKey("jdbc-mysql")
                        || starters.containsKey("jdbc-postgres") || starters.containsKey("jdbc-h2");
                case "smtp" -> starters.containsKey("mail");
                case "logstash" -> starters.containsKey("logstash-logback");
                case "consul" -> starters.containsKey("consul");
                default -> true;
            };
            if (keep) filtered.add(se);
        }
        return filtered;
    }

    private static PropSite lookupConfigProperty(String propertyKey, ServiceEntry src,
            Map<String, Map<String, PropSite>> propsByServiceId,
            Map<String, Map<String, PropSite>> propsByRepoFile,
            Map<String, List<PropSite>> globalProps) {
        PropSite local = propsByServiceId.getOrDefault(src.nodeId(), Map.of()).get(propertyKey);
        if (local != null) return local;
        PropSite bySvcName = propsByRepoFile.getOrDefault(src.canonicalName(), Map.of()).get(propertyKey);
        if (bySvcName != null) return bySvcName;
        PropSite byApp = propsByRepoFile.getOrDefault("application", Map.of()).get(propertyKey);
        if (byApp != null) return byApp;
        List<PropSite> all = globalProps.getOrDefault(propertyKey, List.of());
        if (all.size() == 1) return all.getFirst();
        if (all.size() > 1) {
            String first = all.getFirst().value();
            if (all.stream().allMatch(p -> p.value().equals(first))) return all.getFirst();
        }
        return null;
    }

    /**
     * Resolves ONLY already-declared datastore INFRA_COMPONENTs (scoped or shared by name/image).
     * Never promotes synthetic datastore components from Maven/Gradle starters alone.
     */
    private static ServiceEntry findDeclaredDatastoreInfra(ServiceEntry src, String infraKind,
            Map<String, ServiceEntry> servicesByName, RoleContext roles) {
        String base = canonicalBaseToken(src.canonicalName());
        for (String candidate : List.of(src.canonicalName() + "-" + infraKind, base + "-" + infraKind,
                src.canonicalName() + "-db", base + "-db")) {
            ServiceEntry scoped = servicesByName.get(candidate);
            if (scoped != null && !scoped.hasCodeBuild()) return scoped;
        }
        return switch (infraKind) {
            case "mongodb" -> findExistingInfra(servicesByName, roles, true, "mongodb", "mongo");
            case "postgres" -> findExistingInfra(servicesByName, roles, true, "postgres", "postgresql");
            case "mysql" -> findExistingInfra(servicesByName, roles, true, "mysql", "mariadb");
            default -> findExistingInfra(servicesByName, roles, true, infraKind);
        };
    }

    private static final ServiceEntry AMBIGUOUS_INFRA = new ServiceEntry("__AMBIGUOUS__", "", "", false, false);

    private static void indexInfraEntry(ServiceEntry entry, Map<String, ServiceEntry> infraEntries,
            Map<String, ServiceEntry> infraByBaseToken) {
        infraEntries.putIfAbsent(entry.nodeId(), entry);
        String tok = canonicalBaseToken(entry.canonicalName());
        if (tok.length() >= 2) {
            ServiceEntry prev = infraByBaseToken.get(tok);
            if (prev == null) {
                infraByBaseToken.put(tok, entry);
            } else if (!prev.nodeId().equals(entry.nodeId())) {
                infraByBaseToken.put(tok, AMBIGUOUS_INFRA);
            }
        }
    }

    private static ServiceEntry findExistingInfraIndexed(
            Map<String, ServiceEntry> servicesByName,
            Map<String, ServiceEntry> infraEntries,
            Map<String, ServiceEntry> infraByBaseToken,
            RoleContext roles,
            boolean allowImageMatch,
            String... names) {
        for (String name : names) {
            ServiceEntry se = servicesByName.get(name);
            if (se != null && !se.hasCodeBuild()) return se;
        }
        for (String name : names) {
            String targetTok = canonicalBaseToken(name);
            if (targetTok.length() < 2) continue;
            ServiceEntry byTok = infraByBaseToken.get(targetTok);
            if (byTok != null && byTok != AMBIGUOUS_INFRA) return byTok;
        }
        if (allowImageMatch && !infraEntries.isEmpty()) {
            for (String name : names) {
                String imgTok = imageSearchToken(name);
                if (imgTok.isEmpty()) continue;
                ServiceEntry found = null;
                for (var se : infraEntries.values()) {
                    String img = roles.imageOf(se).toLowerCase(Locale.ROOT);
                    if (!img.isEmpty() && img.contains(imgTok)) {
                        if (found != null && !found.nodeId().equals(se.nodeId())) {
                            found = null;
                            break;
                        }
                        found = se;
                    }
                }
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isK8sWorkload(String nodeKind) {
        return "k8s_deployment".equals(nodeKind)
                || "k8s_statefulset".equals(nodeKind)
                || "k8s_daemonset".equals(nodeKind)
                || "k8s_pod".equals(nodeKind);
    }

    private static String findUniqueDockerfileDir(String norm, String sharedPrefix,
            Map<String, String> dockerfilePathByDir, Map<String, ServiceEntry> servicesByDir) {
        if (norm == null || norm.isEmpty() || dockerfilePathByDir.isEmpty()) return "";
        String stripped = CanonicalServiceIdentityResolver.stripSharedPrefix(norm, sharedPrefix);
        String found = null;
        for (String dfDir : dockerfilePathByDir.keySet()) {
            if (dfDir.isEmpty() || servicesByDir.containsKey(dfDir)) continue;
            String base = normalizeName(baseOrDirName(dfDir, ""));
            String baseDash = base.replace('.', '-');
            boolean matches = base.equals(norm)
                    || (!stripped.isEmpty() && base.equals(stripped))
                    || baseDash.endsWith("-" + norm)
                    || (!stripped.isEmpty() && baseDash.endsWith("-" + stripped));
            if (matches) {
                if (found != null && !found.equals(dfDir)) return "";
                found = dfDir;
            }
        }
        return found == null ? "" : found;
    }

    private static String resolveServiceDirFast(String normPath, Map<String, BuildCompInfo> buildCompsByDir) {
        if (normPath == null || normPath.isBlank()) return "";
        String clean = normPath.replace('\\', '/');
        while (clean.startsWith("./")) clean = clean.substring(2);
        BuildCompInfo matched = CanonicalServiceIdentityResolver.resolveNearestDir(clean, buildCompsByDir);
        if (matched != null && !matched.normDir().isEmpty()) {
            return matched.normDir();
        }
        return topServiceDir(clean);
    }

    private static ContentDigest findRoleProofFast(Map<String, Map<String, ContentDigest>> roleProofByDirAndRole,
            String dir, Set<String> wantedRoles) {
        var byRole = roleProofByDirAndRole.get(dir);
        if (byRole == null || byRole.isEmpty()) return null;
        for (String r : wantedRoles) {
            ContentDigest p = byRole.get(r);
            if (p != null) return p;
        }
        return null;
    }

    private static ServiceEntry findExistingInfra(Map<String, ServiceEntry> servicesByName, RoleContext roles,
            boolean allowImageMatch, String... names) {
        for (String name : names) {
            ServiceEntry se = servicesByName.get(name);
            if (se != null && !se.hasCodeBuild()) return se;
        }
        for (String name : names) {
            String targetTok = canonicalBaseToken(name);
            if (targetTok.length() < 2) continue;
            ServiceEntry found = null;
            for (var se : servicesByName.values()) {
                if (se.hasCodeBuild()) continue;
                if (canonicalBaseToken(se.canonicalName()).equals(targetTok)) {
                    if (found != null && !found.nodeId().equals(se.nodeId())) {
                        found = null;
                        break;
                    }
                    found = se;
                }
            }
            if (found != null) return found;
        }
        if (allowImageMatch) {
            for (String name : names) {
                String imgTok = imageSearchToken(name);
                if (imgTok.isEmpty()) continue;
                ServiceEntry found = null;
                for (var se : servicesByName.values()) {
                    if (se.hasCodeBuild()) continue;
                    String img = roles.imageOf(se).toLowerCase(Locale.ROOT);
                    if (!img.isEmpty() && img.contains(imgTok)) {
                        if (found != null && !found.nodeId().equals(se.nodeId())) {
                            found = null;
                            break;
                        }
                        found = se;
                    }
                }
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String imageSearchToken(String infraName) {
        return switch (normalizeName(infraName)) {
            case "mysql" -> "mysql";
            case "mariadb" -> "mariadb";
            case "postgres", "postgresql" -> "postgres";
            case "mongodb", "mongo" -> "mongo";
            case "redis" -> "redis";
            case "valkey" -> "valkey";
            case "memcached" -> "memcached";
            case "rabbitmq", "rabbit" -> "rabbitmq";
            case "kafka" -> "kafka";
            case "nats" -> "nats";
            case "activemq" -> "activemq";
            case "zookeeper" -> "zookeeper";
            case "zipkin" -> "zipkin";
            case "prometheus" -> "prometheus";
            case "grafana" -> "grafana";
            case "elasticsearch" -> "elasticsearch";
            case "kibana" -> "kibana";
            case "logstash" -> "logstash";
            case "elk" -> "elk";
            case "mailhog" -> "mailhog";
            case "maildev" -> "maildev";
            case "mailpit" -> "mailpit";
            case "postfix" -> "postfix";
            default -> "";
        };
    }

    private static ServiceEntry findGatewayFallback(Collection<ServiceEntry> buildServices,
            Map<String, ServiceEntry> servicesByName, RoleContext roles) {
        ServiceEntry byName = servicesByName.get("api-gateway");
        if (byName != null) return byName;
        ServiceEntry found = null;
        for (var se : buildServices) {
            if (roles.hasRole(se, "ZUUL_PROXY") || roles.hasRole(se, "API_GATEWAY")) {
                if (found != null) return null;
                found = se;
            }
        }
        return found;
    }

    private static ContentDigest findRoleProof(List<RoleSite> springRoles, String dir, Set<String> wantedRoles) {
        for (var rs : springRoles) {
            if (rs.dir().equals(dir) && wantedRoles.contains(rs.role())) {
                return rs.proof();
            }
        }
        return null;
    }

    private static void recordDatastoreEdgeIfApplicable(ServiceEntry src, ServiceEntry dst, RoleContext roles,
            Set<String> withDatastore, Set<String> withMongo, Set<String> withRedis) {
        if (dst.hasCodeBuild()) return;
        String n = dst.canonicalName();
        String img = roles.imageOf(dst).toLowerCase(Locale.ROOT);
        if (hasToken(n, "mongodb") || hasToken(n, "mongo") || img.contains("mongo")) {
            withMongo.add(src.nodeId());
            withDatastore.add(src.nodeId());
        } else if (hasToken(n, "redis") || img.contains("redis")) {
            withRedis.add(src.nodeId());
        } else if (roles.isDatastore(dst)) {
            withDatastore.add(src.nodeId());
        }
    }

    private static String mapStarterToRole(String starter) {
        return switch (starter) {
            case "eureka-server" -> "EUREKA_SERVER";
            case "eureka-client" -> "EUREKA_CLIENT";
            case "config-server" -> "CONFIG_SERVER";
            case "config-client" -> "CONFIG_CLIENT";
            case "boot-admin-server" -> "ADMIN_SERVER";
            case "boot-admin-client" -> "ADMIN_CLIENT";
            case "hystrix-dashboard" -> "HYSTRIX_DASHBOARD";
            case "turbine" -> "TURBINE";
            case "turbine-stream" -> "TURBINE_STREAM";
            case "zuul" -> "ZUUL_PROXY";
            case "gateway" -> "API_GATEWAY";
            case "oauth2-resource" -> "OAUTH2_RESOURCE";
            default -> "";
        };
    }

    private static String effectiveConfigRepoFile(String explicitRepoFile, String path, String dir,
            RoleContext roles, Map<String, ServiceEntry> servicesByDir) {
        if (explicitRepoFile != null && !explicitRepoFile.isBlank()) {
            return explicitRepoFile.trim();
        }
        if (path != null && !path.isBlank()) {
            String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
            ServiceEntry owner = servicesByDir.get(dir);
            if (owner != null && roles.isConfigServer(owner)
                    && (lower.contains("/shared/") || lower.contains("/config-repo/"))) {
                int slash = path.lastIndexOf('/');
                String file = slash >= 0 ? path.substring(slash + 1) : path;
                int dot = file.lastIndexOf('.');
                return dot > 0 ? file.substring(0, dot) : file;
            }
        }
        return "";
    }

    private static boolean isSharedApplicationConfigFile(String repoFile) {
        String lower = repoFile.toLowerCase(Locale.ROOT);
        return lower.equals("application") || lower.startsWith("application-");
    }

    private static boolean isConfigServerRepoPath(String path) {
        if (path == null || path.isEmpty()) return false;
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.contains("/shared/") || lower.contains("/config-repo/");
    }

    private static boolean isHigherIdentityPriority(ConfigIdentitySite candidate, ConfigIdentitySite current) {
        int cp = configIdentityPriority(candidate.path());
        int cur = configIdentityPriority(current.path());
        if (cp != cur) return cp > cur;
        return candidate.path().compareTo(current.path()) < 0;
    }

    private static int configIdentityPriority(String path) {
        String lower = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        boolean mainRes = lower.contains("/src/main/resources/") || lower.startsWith("src/main/resources/");
        int slash = lower.lastIndexOf('/');
        String base = slash >= 0 ? lower.substring(slash + 1) : lower;
        boolean baseFile = base.equals("bootstrap.yml") || base.equals("bootstrap.yaml")
                || base.equals("bootstrap.properties") || base.equals("application.yml")
                || base.equals("application.yaml") || base.equals("application.properties");
        return (mainRes ? 10 : 0) + (baseFile ? 5 : 0);
    }

    private static String normalizeGitRepoTarget(String raw) {
        String lower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (lower.contains("github")) return "github-repository";
        if (lower.contains("gitlab")) return "gitlab-repository";
        return "git-repository";
    }

    private static boolean isProxyDecl(String nodeKind, Map<String, Object> attrs) {
        String nk = nodeKind == null ? "" : nodeKind.toLowerCase(Locale.ROOT);
        String spec = str(attrs.get("spec")).toLowerCase(Locale.ROOT);
        String role = str(attrs.get("role")).toLowerCase(Locale.ROOT);
        return nk.contains("apache") || nk.contains("httpd") || nk.contains("nginx") || nk.contains("proxy")
                || spec.startsWith("apache") || "httpd".equals(spec) || spec.startsWith("nginx")
                || "reverse-proxy".equals(role) || "proxy".equals(role);
    }

    private static String canonicalizeProxyName(String norm, String nodeKind, Map<String, Object> attrs) {
        String spec = str(attrs.get("spec")).toLowerCase(Locale.ROOT);
        String proxy = str(attrs.get("proxy")).toLowerCase(Locale.ROOT);
        if ("apache".equals(proxy) || "httpd".equals(proxy) || spec.startsWith("apache") || "httpd".equals(spec)) return "apache";
        if ("nginx".equals(proxy) || spec.startsWith("nginx")) return "nginx";
        if (nodeKind != null && (nodeKind.toLowerCase(Locale.ROOT).contains("apache")
                || nodeKind.toLowerCase(Locale.ROOT).contains("httpd"))) return "apache";
        if (nodeKind != null && nodeKind.toLowerCase(Locale.ROOT).contains("nginx")) return "nginx";
        return norm;
    }

    private static String detectProxySpec(String nodeKind, Map<String, Object> attrs, String path) {
        String spec = str(attrs.get("spec")).toLowerCase(Locale.ROOT);
        String proxy = str(attrs.get("proxy")).toLowerCase(Locale.ROOT);
        String nk = nodeKind == null ? "" : nodeKind.toLowerCase(Locale.ROOT);
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if ("apache".equals(proxy) || "httpd".equals(proxy) || spec.startsWith("apache") || "httpd".equals(spec)
                || nk.contains("apache") || nk.contains("httpd") || p.contains("httpd")) return "apache";
        if ("nginx".equals(proxy) || spec.startsWith("nginx") || nk.contains("nginx") || p.contains("nginx")) return "nginx";
        return "";
    }

    private static String detectProxyRole(String role, Map<String, Object> attrs, String path) {
        if (role.contains("apache") || role.contains("httpd")) return "apache";
        if (role.contains("nginx")) return "nginx";
        if (role.equals("reverse-proxy") || role.equals("proxy-pass")) {
            String spec = detectProxySpec("", attrs, path);
            return spec.isEmpty() ? "apache" : spec;
        }
        return "";
    }

    private static boolean isReverseProxyNameOrImage(String name, String image) {
        String n = normalizeName(name);
        String img = image == null ? "" : image.toLowerCase(Locale.ROOT);
        if (matchesAnyToken(n, REVERSE_PROXY_TOKENS)) return true;
        for (String tok : REVERSE_PROXY_TOKENS) {
            if (img.contains(tok)) return true;
        }
        return false;
    }

    private static boolean isFrontendSpaName(String name) {
        String n = normalizeName(name);
        return hasToken(n, "frontend") || hasToken(n, "front-end") || hasToken(n, "webui")
                || hasToken(n, "web-ui") || hasToken(n, "webapp") || hasToken(n, "web-app")
                || n.equals("web") || n.equals("ui") || n.endsWith("-ui") || n.endsWith("-web")
                || n.endsWith("-frontend");
    }

    private static boolean isFrontendOrIngressName(String name) {
        String n = normalizeName(name);
        return isFrontendSpaName(n) || hasToken(n, "gateway") || hasToken(n, "zuul")
                || n.equals("proxy") || n.equals("edge") || n.endsWith("-gateway") || n.endsWith("-proxy");
    }

    private static boolean isNonIngressService(ServiceEntry se, RoleContext roles) {
        if (se == null) return true;
        if (roles.isControlPlane(se)) return true;
        if (roles.reverseProxyNodeIds.contains(se.nodeId())) return false;
        if (roles.hasRole(se, "ZUUL_PROXY") || roles.hasRole(se, "API_GATEWAY")
                || roles.gatewayRouteOwnerNodeIds.contains(se.nodeId())) return false;
        if (!se.hasCodeBuild() && (matchesAnyToken(se.canonicalName(), NON_INGRESS_INFRA_TOKENS)
                || roles.isDatastore(se) || roles.isBroker(se))) {
            return true;
        }
        return false;
    }

    private static boolean isDatastoreOrBrokerOrLogInfra(String name, String image) {
        String n = normalizeName(name);
        String img = image == null ? "" : image.toLowerCase(Locale.ROOT);
        Set<String> strictInfra = Set.of(
                "mysql", "postgres", "postgresql", "mariadb", "mongodb", "mongo", "h2", "hsqldb", "sqlite",
                "db", "database", "redis", "valkey", "memcached", "cassandra", "couchdb", "neo4j",
                "clickhouse", "influxdb", "opensearch",
                "rabbitmq", "rabbit", "kafka", "nats", "activemq", "zookeeper", "elasticsearch", "kibana",
                "logstash", "elk", "prometheus", "grafana", "smtp", "mailhog", "maildev", "mailpit", "postfix", "jaeger");
        if (matchesAnyToken(n, strictInfra)) return true;
        for (String tok : strictInfra) {
            if (tok.length() >= 3 && img.contains(tok)) return true;
        }
        return false;
    }

    private static boolean isObservabilityAgent(ServiceEntry mon, RoleContext roles) {
        if (mon == null || mon.hasCodeBuild() || roles.isBroker(mon) || roles.isDatastore(mon)
                || roles.reverseProxyNodeIds.contains(mon.nodeId())
                || isReverseProxyNameOrImage(mon.canonicalName(), roles.imageOf(mon))
                || roles.isControlPlane(mon)) {
            return false;
        }
        String n = mon.canonicalName();
        String img = roles.imageOf(mon).toLowerCase(Locale.ROOT);
        if (matchesAnyToken(n, OBSERVABILITY_AGENT_TOKENS)) return true;
        for (String tok : OBSERVABILITY_AGENT_TOKENS) {
            if (tok.length() >= 4 && img.contains(tok)) return true;
        }
        return false;
    }

    private static int declPriority(String nodeKind, Map<String, Object> attrs) {
        if ("compose_service".equals(nodeKind) || "k8s_deployment".equals(nodeKind)
                || "k8s_statefulset".equals(nodeKind) || "k8s_daemonset".equals(nodeKind)) {
            return 0;
        }
        if ("k8s_service".equals(nodeKind) || (nodeKind != null && nodeKind.startsWith("k8s_"))) {
            return 1;
        }
        return isProxyDecl(nodeKind, attrs) ? 2 : 1;
    }

    private static boolean isTestOnlyModuleDir(String normDir) {
        if (normDir == null || normDir.isEmpty()) return false;
        String base = baseOrDirName(normDir, "").toLowerCase(Locale.ROOT);
        return TEST_MODULE_DIR_NAMES.contains(base)
                || base.endsWith("-tests") || base.endsWith("-test")
                || base.endsWith(".tests") || base.endsWith(".test")
                || base.endsWith("_tests") || base.endsWith("_test");
    }

    private static boolean isExplicitExternalHttpsUrl(String uri, Map<String, ServiceEntry> servicesByName) {
        if (uri == null || !uri.toLowerCase(Locale.ROOT).startsWith("https://")) return false;
        String host = extractUriHostRaw(uri);
        if (host.isEmpty() || !host.contains(".") || isLoopback(host)) return false;
        if (host.contains("{") || host.contains("}") || host.contains("$") || host.contains("%")) return false;
        if (IPV4_HOST.matcher(host).matches()) return false;
        for (String internalSuffix : List.of(".local", ".internal", ".localhost", ".svc", ".cluster.local")) {
            if (host.endsWith(internalSuffix)) return false;
        }
        return !servicesByName.containsKey(normalizeName(host));
    }

    private static String extractUriHostRaw(String rawUri) {
        if (rawUri == null || rawUri.isBlank()) return "";
        try {
            var uri = URI.create(rawUri.replace("{", "%7B").replace("}", "%7D"));
            if (!uri.isAbsolute() || uri.getHost() == null) return "";
            return uri.getHost().toLowerCase(Locale.ROOT).trim();
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    private static String extractUriPort(String rawUri) {
        if (rawUri == null || rawUri.isBlank()) return "";
        try {
            var uri = URI.create(rawUri.replace("{", "%7B").replace("}", "%7D"));
            if (!uri.isAbsolute()) return "";
            int port = uri.getPort();
            return port > 0 ? Integer.toString(port) : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    private static String extractImageTail(String image) {
        if (image == null || image.isBlank()) return "";
        String clean = image.trim();
        int at = clean.indexOf('@');
        if (at >= 0) clean = clean.substring(0, at);
        int slash = clean.lastIndexOf('/');
        String tail = slash >= 0 ? clean.substring(slash + 1) : clean;
        int colon = tail.indexOf(':');
        if (colon >= 0) tail = tail.substring(0, colon);
        return tail.trim();
    }

    private static String canonicalBaseToken(String raw) {
        String s = normalizeName(raw);
        for (String suf : GENERIC_ROLE_SUFFIXES) {
            if (s.endsWith(suf) && s.length() > suf.length() + 1) {
                s = s.substring(0, s.length() - suf.length());
                break;
            }
        }
        String collapsed = s.replace("-", "");
        for (String suf : COLLAPSED_ROLE_SUFFIXES) {
            if (collapsed.endsWith(suf) && collapsed.length() > suf.length() + 2) {
                collapsed = collapsed.substring(0, collapsed.length() - suf.length());
                break;
            }
        }
        return collapsed;
    }

    private static boolean hasToken(String normalizedName, String token) {
        return normalizedName.equals(token)
                || normalizedName.startsWith(token + "-")
                || normalizedName.endsWith("-" + token)
                || normalizedName.contains("-" + token + "-");
    }

    private static boolean matchesAnyToken(String normalizedName, Set<String> tokens) {
        for (String tok : tokens) {
            if (hasToken(normalizedName, tok)) return true;
        }
        return false;
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT).trim();
        return Set.of("localhost", "127.0.0.1", "0.0.0.0").contains(lower);
    }

    /**
     * Filters non-deployable build descriptors: repository-root aggregators (e.g. {@code MAVEN:.})
     * and {@link ReadinessPlan.Family#IDE} project metadata descriptors ({@code .project},
     * {@code .classpath}, {@code *.iml}) prefixed with {@code IDE:}.
     */
    private static boolean isNonDeployableBuildDescriptor(String compId, String normDir) {
        if (compId == null) return false;
        String s = compId.trim();
        if (s.toUpperCase(Locale.ROOT).startsWith(IDE_DESCRIPTOR_PREFIX)) {
            return true;
        }
        return normDir.isEmpty() && ROOT_AGGREGATOR_ID.matcher(s).matches();
    }

    private static boolean isTestSource(Map<String, Object> attrs, String normPath) {
        if ("true".equalsIgnoreCase(str(attrs.get("testSource")))) return true;
        if (normPath == null || normPath.isEmpty()) return false;
        String lower = normPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.startsWith("src/test/") || lower.contains("/src/test/");
    }

    private static String unquoteLiteral(String raw) {
        if (raw == null) return "";
        if (raw.length() >= 2) {
            char a = raw.charAt(0), b = raw.charAt(raw.length() - 1);
            if (a == b && (a == '"' || a == '\'' || a == '`')) {
                return raw.substring(1, raw.length() - 1);
            }
        }
        return raw;
    }

    private static String str(Object raw) {
        return raw instanceof String s ? s.trim() : "";
    }

    private static Optional<Map<String, Object>> readSyntax(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence) {
        for (var id : node.evidence()) {
            var proof = evidence.get(id.value());
            if (proof == null || !proof.family().equals("polyglot.source-syntax")) continue;
            var fields = GraphJson.object(GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults())).get("fields"));
            if (!(fields.get("fact") instanceof Map<?, ?>)) continue;
            var out = new LinkedHashMap<String, Object>();
            out.put("fact", fields.get("fact"));
            out.put("_path", decodePath(fields.get("path")));
            out.put("_document", fields.get("document") instanceof String s ? s : "");
            out.put("_proof", id);
            return Optional.of(out);
        }
        return Optional.empty();
    }

    private static boolean isInfraNameOrImage(String name, String image) {
        String n = normalizeName(name);
        String img = image == null ? "" : image.toLowerCase(Locale.ROOT);
        for (String known : KNOWN_INFRA_NAMES) {
            if (n.equals(known) || n.endsWith("-" + known) || n.startsWith(known + "-")) return true;
        }
        for (String knownImg : INFRA_IMAGES) {
            if (img.contains(knownImg)) return true;
        }
        return false;
    }

    private static String decodePath(Object raw) {
        if (!(raw instanceof String encoded) || encoded.isBlank()) return "";
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).replace('\\', '/');
        } catch (IllegalArgumentException plain) {
            return encoded.replace('\\', '/');
        }
    }

    private static String resolveServiceDir(String normPath, List<String> buildDirsByLength) {
        if (normPath == null || normPath.isBlank()) return "";
        String clean = normPath.replace('\\', '/');
        while (clean.startsWith("./")) clean = clean.substring(2);
        for (String bdir : buildDirsByLength) {
            if (clean.equals(bdir) || clean.startsWith(bdir + "/")) {
                return bdir;
            }
        }
        return topServiceDir(clean);
    }

    private static String topServiceDir(String normPath) {
        if (normPath == null || normPath.isBlank()) return "";
        int slash = normPath.indexOf('/');
        if (slash <= 0) return "";
        String first = normPath.substring(0, slash);
        if (Set.of("src", "services", "apps", "packages", "modules", "microservices").contains(first.toLowerCase(Locale.ROOT))) {
            int next = normPath.indexOf('/', slash + 1);
            return next > slash ? normPath.substring(0, next) : first;
        }
        return first;
    }

    private static String parentDirOf(String path) {
        if (path == null || path.isBlank()) return "";
        String clean = path.replace('\\', '/').trim();
        while (clean.startsWith("./")) clean = clean.substring(2);
        int slash = clean.lastIndexOf('/');
        return slash > 0 ? normDir(clean.substring(0, slash)) : "";
    }

    private static String normDir(String dir) {
        if (dir == null) return "";
        String norm = dir.replace('\\', '/').trim();
        while (norm.startsWith("./")) norm = norm.substring(2);
        while (norm.endsWith("/") && norm.length() > 1) norm = norm.substring(0, norm.length() - 1);
        return norm.equals(".") ? "" : norm;
    }

    private static String baseOrDirName(String dir, String fallbackId) {
        String norm = normDir(dir);
        if (!norm.isEmpty()) {
            int slash = norm.lastIndexOf('/');
            return slash >= 0 ? norm.substring(slash + 1) : norm;
        }
        if (fallbackId == null) return "";
        int colon = fallbackId.lastIndexOf(':');
        return colon >= 0 ? fallbackId.substring(colon + 1) : fallbackId;
    }

    private static String normalizeName(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT).replace('_', '-').trim();
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException();
        }
    }
}
