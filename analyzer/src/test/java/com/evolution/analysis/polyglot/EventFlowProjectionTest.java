package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphProjectionStore;
import com.evolution.analysis.graph.GraphSnapshotCodec;
import com.evolution.analysis.graph.KnowledgeGraph;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventFlowProjectionTest {
    private final SnapshotIdentity snapshot = SnapshotIdentity.from(
            RepositoryIdentity.fromCanonicalCoordinate("urn:test:amqp-event-flow"),
            ContentDigest.sha256Utf8("amqp-inputs"));
    private final String root = snapshot.value();

    @Test
    void amqpWildcardMatcherFollowsRfcDotDelimitedSemanticsForStarHashDirectAndFanout() {
        // * matches exactly 1 segment
        assertTrue(AmqpBindingFact.matchesRoutingKey("order.*.created", "order.us.created", "TOPIC"));
        assertFalse(AmqpBindingFact.matchesRoutingKey("order.*.created", "order.created", "TOPIC"));
        assertFalse(AmqpBindingFact.matchesRoutingKey("order.*.created", "order.us.east.created", "TOPIC"));
        assertTrue(AmqpBindingFact.matchesRoutingKey("*", "single", "TOPIC"));
        assertFalse(AmqpBindingFact.matchesRoutingKey("*", "", "TOPIC"));
        assertFalse(AmqpBindingFact.matchesRoutingKey("*", "a.b", "TOPIC"));

        // # matches 0 or more segments
        assertTrue(AmqpBindingFact.matchesRoutingKey("#", "", "TOPIC"));
        assertTrue(AmqpBindingFact.matchesRoutingKey("#", "a", "TOPIC"));
        assertTrue(AmqpBindingFact.matchesRoutingKey("#", "a.b.c", "TOPIC"));
        assertTrue(AmqpBindingFact.matchesRoutingKey("audit.#", "audit", "TOPIC"));
        assertTrue(AmqpBindingFact.matchesRoutingKey("audit.#", "audit.order.created", "TOPIC"));
        assertTrue(AmqpBindingFact.matchesRoutingKey("order.#.v1", "order.v1", "TOPIC"));
        assertTrue(AmqpBindingFact.matchesRoutingKey("order.#.v1", "order.eu.retail.v1", "TOPIC"));
        assertFalse(AmqpBindingFact.matchesRoutingKey("order.#.v1", "order.eu.v2", "TOPIC"));

        // DIRECT requires exact match without wildcard expansion
        assertTrue(AmqpBindingFact.matchesRoutingKey("invoice.paid", "invoice.paid", "DIRECT"));
        assertFalse(AmqpBindingFact.matchesRoutingKey("invoice.*", "invoice.paid", "DIRECT"));

        // FANOUT matches any routing key including empty
        assertTrue(AmqpBindingFact.matchesRoutingKey("", "anything.here", "FANOUT"));
        assertTrue(AmqpBindingFact.matchesRoutingKey("#", "", "FANOUT"));

        // HEADERS never matches by routing key alone
        assertFalse(AmqpBindingFact.matchesRoutingKey("#", "a.b", "HEADERS"));

        // Deterministic TreeMap ordering
        var map = new TreeMap<AmqpBindingFact, String>();
        var b2 = new AmqpBindingFact("z.ex", "b.*", "q2", "TOPIC", "10:20");
        var b1 = new AmqpBindingFact("a.ex", "a.#", "q1", "TOPIC", "1:5");
        map.put(b2, "second");
        map.put(b1, "first");
        assertEquals(List.of(b1, b2), List.copyOf(map.keySet()));
    }

    @Test
    void projectsAmqpExchangeWildcardBindingsToConsumersWithSourceSyntaxTrustTierAndMatchedRoutingKey() throws Exception {
        var store = GraphProjectionStore.memory();

        // Topic exchange bindings: order.*.created -> order-created-q, order.# -> order-audit-q
        putNode(store, root, id("b-created"), "SOURCE_AMQP_BINDING", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of(
                        "path", b64("order-service/src/main/java/RabbitConfig.java"),
                        "fact", Map.of("name", "\"orders.ex|order.*.created|order-created-q\"", "recovered", false,
                                "span", Map.of("start", 100, "end", 180),
                                "attributes", Map.of(
                                        "exchangeName", "orders.ex",
                                        "routingKeyPattern", "order.*.created",
                                        "queueName", "order-created-q",
                                        "exchangeType", "TOPIC"))))));
        putNode(store, root, id("b-audit"), "SOURCE_AMQP_BINDING", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of(
                        "path", b64("audit-service/src/main/java/AuditRabbitConfig.java"),
                        "fact", Map.of("name", "\"orders.ex|order.#|order-audit-q\"", "recovered", false,
                                "span", Map.of("start", 200, "end", 280),
                                "attributes", Map.of(
                                        "exchangeName", "orders.ex",
                                        "routingKeyPattern", "order.#",
                                        "queueName", "order-audit-q",
                                        "exchangeType", "TOPIC"))))));

        // Cross-file [DiInj-Param] queue/exchange bean + unresolved-param binding
        putNode(store, root, id("q-fanout"), "SOURCE_AMQP_QUEUE", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of(
                        "path", b64("notify-service/src/main/java/Queues.java"),
                        "fact", Map.of("name", "\"notify-broadcast-q\"", "recovered", false,
                                "attributes", Map.of("beanName", "notifyQueue", "queueName", "notify-broadcast-q"))))));
        putNode(store, root, id("ex-fanout"), "SOURCE_AMQP_EXCHANGE", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of(
                        "path", b64("notify-service/src/main/java/Exchanges.java"),
                        "fact", Map.of("name", "\"notify.fanout\"", "recovered", false,
                                "attributes", Map.of("beanName", "notifyExchange", "exchangeName", "notify.fanout",
                                        "exchangeType", "FANOUT"))))));
        putNode(store, root, id("b-fanout"), "SOURCE_AMQP_BINDING", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of(
                        "path", b64("notify-service/src/main/java/Bindings.java"),
                        "fact", Map.of("name", "\"|#|\"", "recovered", false,
                                "attributes", Map.of(
                                        "queueBeanRef", "notifyQueue",
                                        "exchangeBeanRef", "notifyExchange",
                                        "exchangeBeanType", "FANOUT",
                                        "routingKeyPattern", "#"))))));

        // Consumers
        String createdConsumer = putNode(store, root, id("c-created"), "SOURCE_EVENT_CONSUMER_CANDIDATE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("billing-service/src/main/java/BillingListener.java"),
                        "fact", Map.of("name", "\"order-created-q\"", "recovered", false,
                                "attributes", Map.of("protocol", "RABBITMQ", "role", "CONSUMER"))))));
        String auditConsumer = putNode(store, root, id("c-audit"), "SOURCE_EVENT_CONSUMER_CANDIDATE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("audit-service/src/main/java/AuditListener.java"),
                        "fact", Map.of("name", "\"order-audit-q\"", "recovered", false,
                                "attributes", Map.of("protocol", "RABBITMQ", "role", "CONSUMER"))))));
        String notifyConsumer = putNode(store, root, id("c-notify"), "SOURCE_EVENT_CONSUMER_CANDIDATE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("notify-service/src/main/java/NotifyListener.java"),
                        "fact", Map.of("name", "\"notify-broadcast-q\"", "recovered", false,
                                "attributes", Map.of("protocol", "RABBITMQ", "role", "CONSUMER"))))));

        // Producer 1: matches BOTH order.*.created AND order.#
        String prodCreated = putNode(store, root, id("p-created"), "SOURCE_EVENT_PRODUCER_CANDIDATE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("order-service/src/main/java/OrderPublisher.java"),
                        "fact", Map.of("name", "\"orders.ex\"", "recovered", false,
                                "attributes", Map.of("protocol", "RABBITMQ", "role", "PRODUCER",
                                        "exchange", "orders.ex", "routingKey", "order.us.created"))))));
        // Producer 2: matches ONLY order.# (NOT order.*.created because 2 segments between order and created)
        String prodDeep = putNode(store, root, id("p-deep"), "SOURCE_EVENT_PRODUCER_CANDIDATE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("order-service/src/main/java/OrderPublisher.java"),
                        "fact", Map.of("name", "\"orders.ex\"", "recovered", false,
                                "attributes", Map.of("protocol", "RABBITMQ", "role", "PRODUCER",
                                        "exchange", "orders.ex", "routingKey", "order.us.east.created"))))));
        // Producer 3: unmatched routing key on orders.ex must emit ZERO EVENT_FLOW edges
        String prodUnmatched = putNode(store, root, id("p-unmatched"), "SOURCE_EVENT_PRODUCER_CANDIDATE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("order-service/src/main/java/OrderPublisher.java"),
                        "fact", Map.of("name", "\"orders.ex\"", "recovered", false,
                                "attributes", Map.of("protocol", "RABBITMQ", "role", "PRODUCER",
                                        "exchange", "orders.ex", "routingKey", "payment.failed"))))));
        // Producer 4: cross-file fanout exchange
        String prodFanout = putNode(store, root, id("p-fanout"), "SOURCE_EVENT_PRODUCER_CANDIDATE",
                GraphEvidence.capture("polyglot.source-syntax", Map.of("fields", Map.of(
                        "path", b64("order-service/src/main/java/OrderPublisher.java"),
                        "fact", Map.of("name", "\"notify.fanout\"", "recovered", false,
                                "attributes", Map.of("protocol", "RABBITMQ", "role", "PRODUCER",
                                        "exchange", "notify.fanout", "routingKey", "ignored.key"))))));

        EventFlowProjection.project(store, root, () -> false);

        var eventFlows = store.records("edges", GraphSnapshotCodec::edge).values().stream()
                .filter(e -> "EVENT_FLOW".equals(e.kind())).toList();
        var evidenceMap = store.records("evidence", GraphSnapshotCodec::evidence);

        // prodCreated -> createdConsumer AND prodCreated -> auditConsumer
        assertTrue(eventFlows.stream().anyMatch(e -> e.source().equals(prodCreated)
                && e.target().identities().equals(List.of(createdConsumer))));
        assertTrue(eventFlows.stream().anyMatch(e -> e.source().equals(prodCreated)
                && e.target().identities().equals(List.of(auditConsumer))));

        // prodDeep -> auditConsumer ONLY (NOT createdConsumer)
        assertTrue(eventFlows.stream().anyMatch(e -> e.source().equals(prodDeep)
                && e.target().identities().equals(List.of(auditConsumer))));
        assertTrue(eventFlows.stream().noneMatch(e -> e.source().equals(prodDeep)
                && e.target().identities().equals(List.of(createdConsumer))));

        // prodUnmatched -> zero edges
        assertTrue(eventFlows.stream().noneMatch(e -> e.source().equals(prodUnmatched)));

        // prodFanout -> notifyConsumer (via cross-file [DiInj-Param] resolution)
        assertTrue(eventFlows.stream().anyMatch(e -> e.source().equals(prodFanout)
                && e.target().identities().equals(List.of(notifyConsumer))));

        // Verify TrustTier.SOURCE_SYNTAX and matchedRoutingKey metadata on amqp binding proofs
        var bindingProofs = evidenceMap.values().stream()
                .filter(ev -> "polyglot.amqp-binding-match".equals(ev.family())).toList();
        assertEquals(4, bindingProofs.size());
        assertTrue(bindingProofs.stream().allMatch(ev -> ev.canonicalJson().contains("\"trustTier\":\"" + TrustTier.SOURCE_SYNTAX.name() + "\"")));
        assertTrue(bindingProofs.stream().anyMatch(ev -> ev.canonicalJson().contains("\"matchedRoutingKey\":\"order.us.created\"")));

        // Deterministic replay check (I2)
        var snapshotBefore = new TreeMap<>(store.records("edges", GraphSnapshotCodec::edge));
        EventFlowProjection.project(store, root, () -> false);
        assertEquals(snapshotBefore, new TreeMap<>(store.records("edges", GraphSnapshotCodec::edge)));
    }

    @Test
    void emitsDynamicRoutingKeyUnresolvedCapabilityGapForNonFoldableAmqpKeys() throws Exception {
        var store = GraphProjectionStore.memory();
        putNode(store, root, id("c-q"), "SOURCE_EVENT_CONSUMER_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of(
                        "path", b64("billing/src/main/java/Consumer.java"),
                        "fact", Map.of("name", "\"billing.q\"", "recovered", false,
                                "attributes", Map.of("protocol", "RABBITMQ", "role", "CONSUMER"))))));
        putNode(store, root, id("p-dyn"), "SOURCE_EVENT_PRODUCER_CANDIDATE", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of(
                        "path", b64("order/src/main/java/Producer.java"),
                        "fact", Map.of("name", "\"orders.ex\"", "recovered", false,
                                "attributes", Map.of("protocol", "RABBITMQ", "role", "PRODUCER",
                                        "exchange", "orders.ex", "dynamicRoutingKey", "true",
                                        "rawRoutingKeyExpr", "request.getRoutingKey()"))))));
        putNode(store, root, id("b-dyn"), "SOURCE_AMQP_BINDING", GraphEvidence.capture("polyglot.source-syntax",
                Map.of("fields", Map.of(
                        "path", b64("order/src/main/java/RabbitConfig.java"),
                        "fact", Map.of("name", "\"orders.ex||billing.q\"", "recovered", false,
                                "attributes", Map.of("exchangeName", "orders.ex", "queueName", "billing.q",
                                        "exchangeType", "TOPIC", "dynamicRoutingKey", "true",
                                        "rawRoutingKeyExpr", "env.getProperty(\"k\")"))))));

        EventFlowProjection.project(store, root, () -> false);

        var edges = store.records("edges", GraphSnapshotCodec::edge).values();
        assertTrue(edges.stream().noneMatch(e -> "EVENT_FLOW".equals(e.kind())),
                "Dynamic non-foldable AMQP keys must not emit speculative EVENT_FLOW edges");

        var gaps = store.records("nodes", GraphSnapshotCodec::node).values().stream()
                .filter(n -> "CAPABILITY_GAP".equals(n.kind())).toList();
        assertEquals(2, gaps.size(), "Expected 2 DYNAMIC_ROUTING_KEY_UNRESOLVED capability gap nodes");
        var gapEvidence = store.records("evidence", GraphSnapshotCodec::evidence).values().stream()
                .filter(e -> "evidence.gap".equals(e.family())
                        && e.canonicalJson().contains("\"reasonCode\":\"DYNAMIC_ROUTING_KEY_UNRESOLVED\""))
                .toList();
        assertEquals(2, gapEvidence.size());
    }

    private static String putNode(GraphProjectionStore store, String ctx, String id, String kind, GraphEvidence proof) {
        store.records("evidence", GraphSnapshotCodec::evidence).put(proof.identity().value(), proof);
        store.records("nodes", GraphSnapshotCodec::node).put(ctx + "|" + id,
                new KnowledgeGraph.Node(id, ctx, kind, List.of(proof.identity())));
        return id;
    }

    private static String id(String seed) {
        return ContentDigest.sha256Utf8(seed).value();
    }

    private static String b64(String path) {
        return Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }
}
