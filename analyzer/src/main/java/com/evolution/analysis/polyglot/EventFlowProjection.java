package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.graph.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Matches static literal event producer and consumer syntax candidates across languages
 * (e.g. Kafka, RabbitMQ, NATS, EventBus) sharing an identical literal topic/channel name.
 * Candidate edges never assert runtime broker delivery or configuration routing.
 */
public final class EventFlowProjection {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("polyglot.event-flow-candidates", "m8.4-v1");

    private record EventSite(KnowledgeGraph.Node node, String topic, String protocol, ContentDigest proof,
            boolean recovered, boolean symbolicTopic) {}

    private EventFlowProjection() {}

    public static void project(GraphProjectionStore store, String root, BooleanSupplier cancelled) throws IOException {
        project(store, root, resolveProfile(store, root), cancelled);
    }

    public static void project(GraphProjectionStore store, String root,
            ServiceArchitectureProjection.ProjectionProfile profile, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        var producers = store.valuesByKind("nodes", Set.of("SOURCE_EVENT_PRODUCER_CANDIDATE"), GraphSnapshotCodec::node, KnowledgeGraph.Node::kind);
        var consumers = store.valuesByKind("nodes", Set.of("SOURCE_EVENT_CONSUMER_CANDIDATE"), GraphSnapshotCodec::node, KnowledgeGraph.Node::kind);
        var infraNodes = store.valuesByKind("nodes", Set.of("INFRA_COMPONENT"), GraphSnapshotCodec::node, KnowledgeGraph.Node::kind);
        var edges = store.records("edges", GraphSnapshotCodec::edge);
        var evidence = store.records("evidence", GraphSnapshotCodec::evidence);
        var additions = store.records("event-projection-additions", GraphSnapshotCodec::node);

        var brokersByProto = new TreeMap<String, List<KnowledgeGraph.Node>>();
        for (var infra : infraNodes) {
            check(cancelled);
            String proto = detectBrokerProtocol(infra, evidence);
            if (!proto.isEmpty()) {
                brokersByProto.computeIfAbsent(proto, ignored -> new ArrayList<>()).add(infra);
            }
        }

        var consumersByTopic = new TreeMap<String, List<EventSite>>();
        for (var node : consumers) {
            check(cancelled);
            descriptor(node, evidence).ifPresent(site -> {
                if (!site.recovered()) {
                    emitBrokerEdge(site, false, brokersByProto, edges, evidence, additions, root, profile);
                    if (!site.symbolicTopic()) {
                        consumersByTopic.computeIfAbsent(site.topic(), ignored -> new ArrayList<>()).add(site);
                    }
                }
            });
        }
        var producerList = new ArrayList<EventSite>();
        for (var node : producers) {
            check(cancelled);
            var optProducer = descriptor(node, evidence);
            if (optProducer.isEmpty() || optProducer.orElseThrow().recovered()) continue;
            var producer = optProducer.orElseThrow();
            emitBrokerEdge(producer, true, brokersByProto, edges, evidence, additions, root, profile);
            if (!producer.symbolicTopic()) {
                producerList.add(producer);
            }
        }

        for (var producer : producerList) {
            check(cancelled);
            var matched = consumersByTopic.getOrDefault(producer.topic(), List.of()).stream()
                    .filter(c -> !c.node().identity().equals(producer.node().identity()))
                    .toList();
            if (matched.isEmpty()) continue;

            String from = producer.node().identity();
            if (!producer.node().context().equals(root)) {
                from = KnowledgeGraph.derivedIdentity("event-producer-reference", List.of(producer.node().context(), producer.node().identity()));
                var ref = GraphEvidence.capture("polyglot.event-producer-reference",
                        Map.of("context", producer.node().context(), "producer", producer.node().identity(), "sourceEvidence", producer.proof()));
                evidence.putIfAbsent(ref.identity().value(), ref);
                additions.put(root + "|" + from, new KnowledgeGraph.Node(from, root, "EVENT_PRODUCER_REFERENCE", List.of(ref.identity(), producer.proof())));
            }

            var targets = new ArrayList<String>();
            var supports = new TreeSet<ContentDigest>();
            supports.add(producer.proof());
            for (var consumer : matched) {
                String id = consumer.node().identity();
                if (!consumer.node().context().equals(root)) {
                    id = KnowledgeGraph.derivedIdentity("event-consumer-reference", List.of(consumer.node().context(), id));
                    var ref = GraphEvidence.capture("polyglot.event-consumer-reference",
                            Map.of("context", consumer.node().context(), "consumer", consumer.node().identity(), "sourceEvidence", consumer.proof()));
                    evidence.putIfAbsent(ref.identity().value(), ref);
                    additions.put(root + "|" + id, new KnowledgeGraph.Node(id, root, "EVENT_CONSUMER_REFERENCE", List.of(ref.identity(), consumer.proof())));
                }
                targets.add(id);
                supports.add(consumer.proof());
            }

            var proof = GraphEvidence.capture("polyglot.event-topic-match", Map.ofEntries(
                    Map.entry("provider", PROVIDER),
                    Map.entry("topic", producer.topic()),
                    Map.entry("protocol", producer.protocol()),
                    Map.entry("producer", producer.node().identity()),
                    Map.entry("consumers", matched.stream().map(c -> c.node().identity()).toList()),
                    Map.entry("sourceEvidence", List.copyOf(supports)),
                    Map.entry("certainty", "CANDIDATE"),
                    Map.entry("runtimeObserved", false),
                    Map.entry("brokerTopologyEstablished", false)));
            evidence.putIfAbsent(proof.identity().value(), proof);
            supports.add(proof.identity());

            var target = new KnowledgeGraph.Target(
                    targets.size() == 1 ? KnowledgeGraph.TargetState.RESOLVED : KnowledgeGraph.TargetState.CANDIDATES,
                    targets, Optional.empty());
            String candidateId = KnowledgeGraph.derivedIdentity("event-flow-candidate", List.of(PROVIDER, root, producer.node().identity(), target, proof.identity()));
            edges.put(root + "|" + candidateId, new KnowledgeGraph.Edge(
                    candidateId, root, "polyglot.event-flow.candidate", from, target, List.copyOf(supports), Optional.empty()));

            boolean brokerMediated = !resolveBrokers(producer.protocol(), brokersByProto).isEmpty();
            if (profile == ServiceArchitectureProjection.ProjectionProfile.STRICT || !brokerMediated) {
                String edgeId = KnowledgeGraph.derivedIdentity("architecture-event-edge", List.of(PROVIDER, root, "PUBLISHES_EVENT", from, target, proof.identity()));
                edges.put(root + "|" + edgeId, new KnowledgeGraph.Edge(
                        edgeId, root, "PUBLISHES_EVENT", from, target, List.copyOf(supports), Optional.empty()));
            }

            String flowEdgeId = KnowledgeGraph.derivedIdentity("architecture-event-flow-edge", List.of(PROVIDER, root, "EVENT_FLOW", from, target, proof.identity()));
            edges.put(root + "|" + flowEdgeId, new KnowledgeGraph.Edge(
                    flowEdgeId, root, "EVENT_FLOW", from, target, List.copyOf(supports), Optional.empty()));
        }
        store.records("nodes", GraphSnapshotCodec::node).putAll(additions);
    }

    private static ServiceArchitectureProjection.ProjectionProfile resolveProfile(GraphProjectionStore store, String root) {
        var meta = store.records("projection-metadata", GraphJson::object).get(root + "|profile");
        if (meta != null && "DFD_EXPORT".equals(meta.get("profile"))) {
            return ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT;
        }
        return ServiceArchitectureProjection.ProjectionProfile.STRICT;
    }

    private static TreeMap<String, KnowledgeGraph.Node> resolveBrokers(String protocol,
            Map<String, List<KnowledgeGraph.Node>> brokersByProto) {
        var brokers = new TreeMap<String, KnowledgeGraph.Node>();
        if ("SPRING_CLOUD_STREAM".equals(protocol)) {
            for (String p : List.of("RABBITMQ", "KAFKA")) {
                for (var b : brokersByProto.getOrDefault(p, List.of())) brokers.put(b.identity(), b);
            }
        } else {
            for (var b : brokersByProto.getOrDefault(protocol, List.of())) brokers.put(b.identity(), b);
        }
        return brokers;
    }

    private static void emitBrokerEdge(EventSite site, boolean producer, Map<String, List<KnowledgeGraph.Node>> brokersByProto,
            Map<String, KnowledgeGraph.Edge> edges, Map<String, GraphEvidence> evidence,
            Map<String, KnowledgeGraph.Node> additions, String root,
            ServiceArchitectureProjection.ProjectionProfile profile) {
        var brokers = resolveBrokers(site.protocol(), brokersByProto);
        if (brokers.isEmpty()) return;
        String siteRef = site.node().identity();
        if (!site.node().context().equals(root)) {
            siteRef = KnowledgeGraph.derivedIdentity(producer ? "event-producer-reference" : "event-consumer-reference",
                    List.of(site.node().context(), site.node().identity()));
            var ref = GraphEvidence.capture(producer ? "polyglot.event-producer-reference" : "polyglot.event-consumer-reference",
                    Map.of("context", site.node().context(), producer ? "producer" : "consumer", site.node().identity(), "sourceEvidence", site.proof()));
            evidence.putIfAbsent(ref.identity().value(), ref);
            additions.put(root + "|" + siteRef, new KnowledgeGraph.Node(siteRef, root,
                    producer ? "EVENT_PRODUCER_REFERENCE" : "EVENT_CONSUMER_REFERENCE", List.of(ref.identity(), site.proof())));
        }
        if (profile == ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT && !producer) {
            // DFD data-flow orientation: broker -> consumer, explicitly tagged DERIVED_DFD_POLICY.
            var consumerTarget = new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(siteRef), Optional.empty());
            for (var broker : brokers.values()) {
                var proof = GraphEvidence.capture("polyglot.event-broker-link", Map.of(
                        "provider", PROVIDER, "kind", "CONSUMES_FROM", "topic", site.topic(), "protocol", site.protocol(),
                        "site", site.node().identity(), "brokers", List.of(broker.identity()),
                        "certainty", "CANDIDATE", "trust", "DERIVED_DFD_POLICY", "orientation", "DFD_DATA_FLOW",
                        "runtimeObserved", false));
                evidence.putIfAbsent(proof.identity().value(), proof);
                String edgeId = KnowledgeGraph.derivedIdentity("event-broker-edge",
                        List.of(PROVIDER, root, "CONSUMES_FROM", broker.identity(), consumerTarget, proof.identity()));
                edges.put(root + "|" + edgeId, new KnowledgeGraph.Edge(edgeId, root, "CONSUMES_FROM",
                        broker.identity(), consumerTarget, List.of(site.proof(), proof.identity()), Optional.empty()));
            }
            return;
        }
        var targetIds = List.copyOf(brokers.keySet());
        var target = new KnowledgeGraph.Target(targetIds.size() == 1 ? KnowledgeGraph.TargetState.RESOLVED : KnowledgeGraph.TargetState.CANDIDATES,
                targetIds, Optional.empty());
        String kind = producer ? "PUBLISHES_TO" : "CONSUMES_FROM";
        var proof = GraphEvidence.capture("polyglot.event-broker-link", Map.of(
                "provider", PROVIDER, "kind", kind, "topic", site.topic(), "protocol", site.protocol(),
                "site", site.node().identity(), "brokers", targetIds, "certainty", "CANDIDATE", "runtimeObserved", false));
        evidence.putIfAbsent(proof.identity().value(), proof);
        String edgeId = KnowledgeGraph.derivedIdentity("event-broker-edge", List.of(PROVIDER, root, kind, siteRef, target, proof.identity()));
        edges.put(root + "|" + edgeId, new KnowledgeGraph.Edge(edgeId, root, kind, siteRef, target, List.of(site.proof(), proof.identity()), Optional.empty()));
    }

    private static String detectBrokerProtocol(KnowledgeGraph.Node infra, Map<String, GraphEvidence> evidence) {
        for (var id : infra.evidence()) {
            var proof = evidence.get(id.value());
            if (proof == null) continue;
            String json = proof.canonicalJson().toLowerCase(Locale.ROOT);
            if (json.contains("rabbitmq") || json.contains("amqp") || json.contains("\"rabbit\"")) return "RABBITMQ";
            if (json.contains("kafka")) return "KAFKA";
            if (json.contains("nats")) return "NATS";
            if (json.contains("redis")) return "REDIS";
        }
        return "";
    }

    private static Optional<EventSite> descriptor(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence) {
        for (var support : node.evidence()) {
            var proof = evidence.get(support.value());
            if (proof == null || !proof.family().equals("polyglot.source-syntax")) continue;
            var fields = GraphJson.object(GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults())).get("fields"));
            var fact = GraphJson.object(fields.get("fact"));
            var attrs = fact.get("attributes") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            if (isTestSource(attrs, decodePath(fields.get("path")))) return Optional.empty();
            boolean symbolic = attrs.get("symbolicTopic") instanceof String st && "true".equalsIgnoreCase(st.trim());
            String raw = GraphJson.string(fact.get("name"));
            String topic;
            if (raw.length() >= 3 && raw.indexOf('\\') < 0 && raw.charAt(raw.length() - 1) == raw.charAt(0)
                    && (raw.charAt(0) == '\'' || raw.charAt(0) == '"' || raw.charAt(0) == '`')) {
                topic = raw.substring(1, raw.length() - 1).trim();
            } else if (symbolic) {
                topic = raw.trim();
            } else {
                continue;
            }
            if (topic.isEmpty()) continue;
            String protocol = "EVENT_BUS";
            if (attrs.get("protocol") instanceof String p && !p.isBlank()) {
                protocol = p;
            }
            return Optional.of(new EventSite(node, topic, protocol, support, Boolean.TRUE.equals(fact.get("recovered")), symbolic));
        }
        return Optional.empty();
    }

    private static boolean isTestSource(Map<String, Object> attrs, String normPath) {
        if (attrs.get("testSource") instanceof String s && "true".equalsIgnoreCase(s.trim())) return true;
        if (normPath == null || normPath.isEmpty()) return false;
        String lower = normPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.startsWith("src/test/") || lower.contains("/src/test/");
    }

    private static String decodePath(Object raw) {
        if (!(raw instanceof String encoded) || encoded.isBlank()) return "";
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).replace('\\', '/');
        } catch (IllegalArgumentException plain) {
            return encoded.replace('\\', '/');
        }
    }

    private static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException("EVENT_FLOW_PROJECTION_CANCELLED");
        }
    }
}
