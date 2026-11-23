package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.graph.*;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Matches static literal event producer and consumer syntax candidates across languages
 * (e.g. Kafka, RabbitMQ, NATS, EventBus) sharing an identical literal topic/channel name.
 * Candidate edges never assert runtime broker delivery or configuration routing.
 */
public final class EventFlowProjection {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("polyglot.event-flow-candidates", "m8.4-v1");

    private record EventSite(KnowledgeGraph.Node node, String topic, String protocol, ContentDigest proof, boolean recovered) {}

    private EventFlowProjection() {}

    public static void project(GraphProjectionStore store, String root, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        var producers = store.valuesByKind("nodes", Set.of("SOURCE_EVENT_PRODUCER_CANDIDATE"), GraphSnapshotCodec::node, KnowledgeGraph.Node::kind);
        var consumers = store.valuesByKind("nodes", Set.of("SOURCE_EVENT_CONSUMER_CANDIDATE"), GraphSnapshotCodec::node, KnowledgeGraph.Node::kind);
        var edges = store.records("edges", GraphSnapshotCodec::edge);
        var evidence = store.records("evidence", GraphSnapshotCodec::evidence);
        var additions = store.records("event-projection-additions", GraphSnapshotCodec::node);

        var consumersByTopic = new TreeMap<String, List<EventSite>>();
        for (var node : consumers) {
            check(cancelled);
            descriptor(node, evidence).ifPresent(site -> {
                if (!site.recovered()) {
                    consumersByTopic.computeIfAbsent(site.topic(), ignored -> new ArrayList<>()).add(site);
                }
            });
        }
        if (consumersByTopic.isEmpty()) return;

        for (var node : producers) {
            check(cancelled);
            var optProducer = descriptor(node, evidence);
            if (optProducer.isEmpty() || optProducer.orElseThrow().recovered()) continue;
            var producer = optProducer.orElseThrow();
            var matched = consumersByTopic.getOrDefault(producer.topic(), List.of()).stream()
                    .filter(c -> !c.node().identity().equals(node.identity()))
                    .toList();
            if (matched.isEmpty()) continue;

            String from = node.identity();
            if (!node.context().equals(root)) {
                from = KnowledgeGraph.derivedIdentity("event-producer-reference", List.of(node.context(), node.identity()));
                var ref = GraphEvidence.capture("polyglot.event-producer-reference",
                        Map.of("context", node.context(), "producer", node.identity(), "sourceEvidence", producer.proof()));
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
            String candidateId = KnowledgeGraph.derivedIdentity("event-flow-candidate", List.of(PROVIDER, root, node.identity(), target, proof.identity()));
            edges.put(root + "|" + candidateId, new KnowledgeGraph.Edge(
                    candidateId, root, "polyglot.event-flow.candidate", from, target, List.copyOf(supports), Optional.empty()));

            String edgeId = KnowledgeGraph.derivedIdentity("architecture-event-edge", List.of(PROVIDER, root, "PUBLISHES_EVENT", from, target, proof.identity()));
            edges.put(root + "|" + edgeId, new KnowledgeGraph.Edge(
                    edgeId, root, "PUBLISHES_EVENT", from, target, List.copyOf(supports), Optional.empty()));
        }
        store.records("nodes", GraphSnapshotCodec::node).putAll(additions);
    }

    private static Optional<EventSite> descriptor(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence) {
        for (var support : node.evidence()) {
            var proof = evidence.get(support.value());
            if (proof == null || !proof.family().equals("polyglot.source-syntax")) continue;
            var fields = GraphJson.object(GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults())).get("fields"));
            var fact = GraphJson.object(fields.get("fact"));
            String raw = GraphJson.string(fact.get("name"));
            if (raw.length() < 3 || raw.indexOf('\\') >= 0 || raw.charAt(raw.length() - 1) != raw.charAt(0)
                    || (raw.charAt(0) != '\'' && raw.charAt(0) != '"' && raw.charAt(0) != '`')) continue;
            String topic = raw.substring(1, raw.length() - 1).trim();
            if (topic.isEmpty()) continue;
            String protocol = "EVENT_BUS";
            if (fact.get("attributes") instanceof Map<?, ?> attrs && attrs.get("protocol") instanceof String p && !p.isBlank()) {
                protocol = p;
            }
            return Optional.of(new EventSite(node, topic, protocol, support, Boolean.TRUE.equals(fact.get("recovered"))));
        }
        return Optional.empty();
    }

    private static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException("EVENT_FLOW_PROJECTION_CANCELLED");
        }
    }
}
