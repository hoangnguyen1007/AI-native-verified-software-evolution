package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.evidence.AffectedOutput;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.evidence.EvidenceRequirement;
import com.evolution.analysis.evidence.EvidenceSubject;
import com.evolution.analysis.evidence.ProviderObservationReference;
import com.evolution.analysis.graph.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Matches static literal event producer and consumer syntax candidates across languages
 * (e.g. Kafka, RabbitMQ, NATS, EventBus) sharing an identical literal topic/channel name,
 * and resolves passive AMQP/RabbitMQ exchange-to-queue bindings via RFC/RabbitMQ dot-delimited
 * wildcard matching ({@code *} = 1 segment, {@code #} = 0+ segments).
 * Candidate edges never assert runtime broker delivery or configuration routing.
 */
public final class EventFlowProjection {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("polyglot.event-flow-candidates", "m8.4-v1");

    private record EventSite(
            KnowledgeGraph.Node node,
            String topic,
            String protocol,
            ContentDigest proof,
            boolean recovered,
            boolean symbolicTopic,
            String exchange,
            String routingKey,
            boolean dynamicRoutingKey,
            String rawRoutingKeyExpr) {}

    private record ExchangeBean(String exchangeName, String exchangeType) implements Comparable<ExchangeBean> {
        @Override
        public int compareTo(ExchangeBean other) {
            int cmp = this.exchangeName.compareTo(other.exchangeName);
            return cmp != 0 ? cmp : this.exchangeType.compareTo(other.exchangeType);
        }
    }

    private record AmqpBindingSite(
            AmqpBindingFact fact,
            KnowledgeGraph.Node node,
            ContentDigest proof,
            boolean dynamicRoutingKey,
            String rawRoutingKeyExpr) implements Comparable<AmqpBindingSite> {
        @Override
        public int compareTo(AmqpBindingSite other) {
            int cmp = this.fact.compareTo(other.fact);
            if (cmp != 0) return cmp;
            return this.node.identity().compareTo(other.node.identity());
        }
    }

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
        var amqpQueueNodes = store.valuesByKind("nodes", Set.of("SOURCE_AMQP_QUEUE"), GraphSnapshotCodec::node, KnowledgeGraph.Node::kind);
        var amqpExchangeNodes = store.valuesByKind("nodes", Set.of("SOURCE_AMQP_EXCHANGE"), GraphSnapshotCodec::node, KnowledgeGraph.Node::kind);
        var amqpBindingNodes = store.valuesByKind("nodes", Set.of("SOURCE_AMQP_BINDING"), GraphSnapshotCodec::node, KnowledgeGraph.Node::kind);
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

        // Cross-file [DiInj-Param] bean index for AMQP Queue and Exchange @Bean declarations
        var queueByBeanName = new TreeMap<String, String>();
        var queueNamesAll = new TreeSet<String>();
        for (var qNode : amqpQueueNodes) {
            check(cancelled);
            extractAmqpQueueBean(qNode, evidence, queueByBeanName, queueNamesAll);
        }
        var exchangeByBeanName = new TreeMap<String, ExchangeBean>();
        var exchangesByType = new TreeMap<String, TreeSet<ExchangeBean>>();
        var exchangesAll = new TreeSet<ExchangeBean>();
        for (var exNode : amqpExchangeNodes) {
            check(cancelled);
            extractAmqpExchangeBean(exNode, evidence, exchangeByBeanName, exchangesByType, exchangesAll);
        }

        // Deterministic TreeMap<exchangeName, TreeSet<AmqpBindingSite>> binding table
        var bindingsByExchange = new TreeMap<String, TreeSet<AmqpBindingSite>>();
        for (var bNode : amqpBindingNodes) {
            check(cancelled);
            extractAmqpBinding(bNode, evidence, queueByBeanName, queueNamesAll,
                    exchangeByBeanName, exchangesByType, exchangesAll)
                    .ifPresent(bindingSite -> {
                        if (bindingSite.dynamicRoutingKey()) {
                            emitDynamicRoutingKeyGap(
                                    root,
                                    bindingSite.node().identity(),
                                    bindingSite.proof(),
                                    bindingSite.fact().exchangeName(),
                                    bindingSite.rawRoutingKeyExpr(),
                                    "amqp_binding",
                                    evidence,
                                    additions);
                        } else if (!bindingSite.fact().exchangeName().isEmpty()
                                && !bindingSite.fact().queueName().isEmpty()) {
                            bindingsByExchange
                                    .computeIfAbsent(bindingSite.fact().exchangeName(), ignored -> new TreeSet<>())
                                    .add(bindingSite);
                        }
                    });
        }

        var consumersByTopic = new TreeMap<String, List<EventSite>>();
        var rabbitConsumersByQueue = new TreeMap<String, List<EventSite>>();
        for (var node : consumers) {
            check(cancelled);
            descriptor(node, evidence).ifPresent(site -> {
                if (!site.recovered()) {
                    emitBrokerEdge(site, false, brokersByProto, edges, evidence, additions, root, profile);
                    if (!site.symbolicTopic() && !site.topic().isEmpty()) {
                        consumersByTopic.computeIfAbsent(site.topic(), ignored -> new ArrayList<>()).add(site);
                        if ("RABBITMQ".equals(site.protocol())) {
                            rabbitConsumersByQueue.computeIfAbsent(site.topic(), ignored -> new ArrayList<>()).add(site);
                        }
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
            if ("RABBITMQ".equals(producer.protocol()) && isDynamicAmqpProducer(producer)) {
                emitDynamicRoutingKeyGap(
                        root,
                        producer.node().identity(),
                        producer.proof(),
                        producer.exchange(),
                        producer.rawRoutingKeyExpr().isEmpty() ? producer.topic() : producer.rawRoutingKeyExpr(),
                        "amqp_producer",
                        evidence,
                        additions);
                continue;
            }
            if (!producer.symbolicTopic() || ("RABBITMQ".equals(producer.protocol()) && !producer.exchange().isEmpty())) {
                producerList.add(producer);
            }
        }

        for (var producer : producerList) {
            check(cancelled);
            boolean hasAmqpExchange = "RABBITMQ".equals(producer.protocol()) && !producer.exchange().isEmpty();
            boolean exchangeHasDeclaredBindings = hasAmqpExchange && bindingsByExchange.containsKey(producer.exchange());

            if (exchangeHasDeclaredBindings) {
                var matchedBindings = bindingsByExchange.get(producer.exchange());
                for (var bindingSite : matchedBindings) {
                    check(cancelled);
                    AmqpBindingFact binding = bindingSite.fact();
                    if (!binding.matches(producer.routingKey())) {
                        continue;
                    }
                    var matchedConsumers = rabbitConsumersByQueue.getOrDefault(binding.queueName(), List.of()).stream()
                            .filter(c -> !c.node().identity().equals(producer.node().identity()))
                            .toList();
                    if (matchedConsumers.isEmpty()) {
                        continue;
                    }
                    emitAmqpBindingFlow(producer, bindingSite, matchedConsumers, brokersByProto,
                            edges, evidence, additions, root, profile);
                }
                // Explicit exchange with declared bindings: never fall back to accidental exchange-name == queue-name match.
                continue;
            }

            if (producer.symbolicTopic() || producer.topic().isEmpty()) {
                continue;
            }

            var matched = consumersByTopic.getOrDefault(producer.topic(), List.of()).stream()
                    .filter(c -> !c.node().identity().equals(producer.node().identity()))
                    .toList();
            if (matched.isEmpty()) continue;

            String from = producerReference(producer, root, evidence, additions);
            var targets = new ArrayList<String>();
            var supports = new TreeSet<ContentDigest>();
            supports.add(producer.proof());
            for (var consumer : matched) {
                targets.add(consumerReference(consumer, root, evidence, additions));
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

    private static boolean isDynamicAmqpProducer(EventSite producer) {
        if (producer.dynamicRoutingKey()) {
            return true;
        }
        if (producer.symbolicTopic() && producer.routingKey().isEmpty()) {
            return true;
        }
        return false;
    }

    private static void emitAmqpBindingFlow(
            EventSite producer,
            AmqpBindingSite bindingSite,
            List<EventSite> matchedConsumers,
            Map<String, List<KnowledgeGraph.Node>> brokersByProto,
            Map<String, KnowledgeGraph.Edge> edges,
            Map<String, GraphEvidence> evidence,
            Map<String, KnowledgeGraph.Node> additions,
            String root,
            ServiceArchitectureProjection.ProjectionProfile profile) {
        AmqpBindingFact binding = bindingSite.fact();
        String from = producerReference(producer, root, evidence, additions);
        var targets = new ArrayList<String>();
        var supports = new TreeSet<ContentDigest>();
        supports.add(producer.proof());
        supports.add(bindingSite.proof());
        for (var consumer : matchedConsumers) {
            targets.add(consumerReference(consumer, root, evidence, additions));
            supports.add(consumer.proof());
        }

        var proofFields = new LinkedHashMap<String, Object>();
        proofFields.put("provider", PROVIDER);
        proofFields.put("trustTier", TrustTier.SOURCE_SYNTAX.name());
        proofFields.put("protocol", "RABBITMQ");
        proofFields.put("exchange", binding.exchangeName());
        proofFields.put("exchangeType", binding.exchangeType());
        proofFields.put("routingKeyPattern", binding.routingKeyPattern());
        proofFields.put("matchedRoutingKey", producer.routingKey());
        proofFields.put("queue", binding.queueName());
        if (!binding.sourceSpan().isEmpty()) {
            proofFields.put("bindingSpan", binding.sourceSpan());
        }
        proofFields.put("bindingNode", bindingSite.node().identity());
        proofFields.put("producer", producer.node().identity());
        proofFields.put("consumers", matchedConsumers.stream().map(c -> c.node().identity()).toList());
        proofFields.put("sourceEvidence", List.copyOf(supports));
        proofFields.put("certainty", "CANDIDATE");
        proofFields.put("runtimeObserved", false);
        proofFields.put("brokerTopologyEstablished", true);

        var proof = GraphEvidence.capture("polyglot.amqp-binding-match", proofFields);
        evidence.putIfAbsent(proof.identity().value(), proof);
        supports.add(proof.identity());

        var target = new KnowledgeGraph.Target(
                targets.size() == 1 ? KnowledgeGraph.TargetState.RESOLVED : KnowledgeGraph.TargetState.CANDIDATES,
                targets, Optional.empty());
        String candidateId = KnowledgeGraph.derivedIdentity("event-flow-candidate",
                List.of(PROVIDER, root, producer.node().identity(), binding.exchangeName(), binding.queueName(),
                        producer.routingKey(), target, proof.identity()));
        edges.put(root + "|" + candidateId, new KnowledgeGraph.Edge(
                candidateId, root, "polyglot.event-flow.candidate", from, target, List.copyOf(supports), Optional.empty()));

        boolean brokerMediated = !resolveBrokers(producer.protocol(), brokersByProto).isEmpty();
        if (profile == ServiceArchitectureProjection.ProjectionProfile.STRICT || !brokerMediated) {
            String edgeId = KnowledgeGraph.derivedIdentity("architecture-event-edge",
                    List.of(PROVIDER, root, "PUBLISHES_EVENT", from, target, proof.identity()));
            edges.put(root + "|" + edgeId, new KnowledgeGraph.Edge(
                    edgeId, root, "PUBLISHES_EVENT", from, target, List.copyOf(supports), Optional.empty()));
        }

        String flowEdgeId = KnowledgeGraph.derivedIdentity("architecture-event-flow-edge",
                List.of(PROVIDER, root, "EVENT_FLOW", from, target, proof.identity()));
        edges.put(root + "|" + flowEdgeId, new KnowledgeGraph.Edge(
                flowEdgeId, root, "EVENT_FLOW", from, target, List.copyOf(supports), Optional.empty()));
    }

    private static void emitDynamicRoutingKeyGap(
            String root,
            String sourceNodeId,
            ContentDigest sourceProof,
            String exchangeName,
            String rawExpr,
            String siteKind,
            Map<String, GraphEvidence> evidence,
            Map<String, KnowledgeGraph.Node> additions) {
        var obsFields = new LinkedHashMap<String, Object>();
        obsFields.put("provider", PROVIDER);
        obsFields.put("site", sourceNodeId);
        obsFields.put("siteKind", siteKind);
        obsFields.put("exchange", exchangeName == null ? "" : exchangeName);
        obsFields.put("rawRoutingKeyExpr", rawExpr == null ? "" : rawExpr);
        obsFields.put("trustTier", TrustTier.SOURCE_SYNTAX.name());
        obsFields.put("reasonCode", "DYNAMIC_ROUTING_KEY_UNRESOLVED");
        var obsProof = GraphEvidence.capture("polyglot.amqp-dynamic-routing-key", obsFields);
        evidence.putIfAbsent(obsProof.identity().value(), obsProof);

        var subject = new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION, obsProof.identity().value());
        var requirement = new EvidenceRequirement(
                EvidenceRequirement.Kind.CONFIGURATION,
                "polyglot.amqp-routing",
                List.of(subject),
                EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of("Provide constant or statically foldable AMQP routing key and exchange binding definitions."));
        var gap = CapabilityGapRecord.create(
                EvidenceContext.forSnapshot(new SnapshotIdentity(root)),
                PROVIDER,
                "polyglot.amqp-routing",
                "DYNAMIC_ROUTING_KEY_UNRESOLVED",
                subject,
                List.of(),
                List.of(ProviderObservationReference.create(PROVIDER, "polyglot.amqp-dynamic-routing-key",
                        sourceProof, obsProof.identity())),
                List.of(requirement),
                List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "polyglot.event-flow")),
                List.of(),
                List.of(),
                List.of("Non-foldable dynamic AMQP routing keys cannot be wildcard-matched statically without runtime evidence."));
        var gapProof = GraphEvidence.capture("evidence.gap", gap);
        evidence.putIfAbsent(gapProof.identity().value(), gapProof);
        additions.put(root + "|" + gap.gapIdentity().value(), new KnowledgeGraph.Node(
                gap.gapIdentity().value(), root, "CAPABILITY_GAP", List.of(gapProof.identity())));
    }

    private static String producerReference(EventSite producer, String root,
            Map<String, GraphEvidence> evidence, Map<String, KnowledgeGraph.Node> additions) {
        String from = producer.node().identity();
        if (!producer.node().context().equals(root)) {
            from = KnowledgeGraph.derivedIdentity("event-producer-reference", List.of(producer.node().context(), producer.node().identity()));
            var ref = GraphEvidence.capture("polyglot.event-producer-reference",
                    Map.of("context", producer.node().context(), "producer", producer.node().identity(), "sourceEvidence", producer.proof()));
            evidence.putIfAbsent(ref.identity().value(), ref);
            additions.put(root + "|" + from, new KnowledgeGraph.Node(from, root, "EVENT_PRODUCER_REFERENCE", List.of(ref.identity(), producer.proof())));
        }
        return from;
    }

    private static String consumerReference(EventSite consumer, String root,
            Map<String, GraphEvidence> evidence, Map<String, KnowledgeGraph.Node> additions) {
        String id = consumer.node().identity();
        if (!consumer.node().context().equals(root)) {
            id = KnowledgeGraph.derivedIdentity("event-consumer-reference", List.of(consumer.node().context(), id));
            var ref = GraphEvidence.capture("polyglot.event-consumer-reference",
                    Map.of("context", consumer.node().context(), "consumer", consumer.node().identity(), "sourceEvidence", consumer.proof()));
            evidence.putIfAbsent(ref.identity().value(), ref);
            additions.put(root + "|" + id, new KnowledgeGraph.Node(id, root, "EVENT_CONSUMER_REFERENCE", List.of(ref.identity(), consumer.proof())));
        }
        return id;
    }

    private static void extractAmqpQueueBean(
            KnowledgeGraph.Node node,
            Map<String, GraphEvidence> evidence,
            Map<String, String> queueByBeanName,
            Set<String> queueNamesAll) {
        for (var support : node.evidence()) {
            var proof = evidence.get(support.value());
            if (proof == null || !proof.family().equals("polyglot.source-syntax")) continue;
            var fields = GraphJson.object(GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults())).get("fields"));
            var fact = GraphJson.object(fields.get("fact"));
            if (Boolean.TRUE.equals(fact.get("recovered"))) continue;
            var attrs = fact.get("attributes") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            if (isTestSource(attrs, decodePath(fields.get("path")))) continue;
            String queueName = unquote(str(attrs.get("queueName")));
            if (queueName.isEmpty()) queueName = unquote(GraphJson.string(fact.get("name")));
            String beanName = str(attrs.get("beanName"));
            if (!queueName.isEmpty()) {
                queueNamesAll.add(queueName);
                if (!beanName.isEmpty()) {
                    queueByBeanName.putIfAbsent(beanName, queueName);
                }
                String beanAliases = str(attrs.get("beanAliases"));
                if (!beanAliases.isEmpty()) {
                    for (String alias : beanAliases.split(",")) {
                        String trimmed = alias.trim();
                        if (!trimmed.isEmpty()) queueByBeanName.putIfAbsent(trimmed, queueName);
                    }
                }
            }
        }
    }

    private static void extractAmqpExchangeBean(
            KnowledgeGraph.Node node,
            Map<String, GraphEvidence> evidence,
            Map<String, ExchangeBean> exchangeByBeanName,
            Map<String, TreeSet<ExchangeBean>> exchangesByType,
            Set<ExchangeBean> exchangesAll) {
        for (var support : node.evidence()) {
            var proof = evidence.get(support.value());
            if (proof == null || !proof.family().equals("polyglot.source-syntax")) continue;
            var fields = GraphJson.object(GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults())).get("fields"));
            var fact = GraphJson.object(fields.get("fact"));
            if (Boolean.TRUE.equals(fact.get("recovered"))) continue;
            var attrs = fact.get("attributes") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            if (isTestSource(attrs, decodePath(fields.get("path")))) continue;
            String exchName = unquote(str(attrs.get("exchangeName")));
            if (exchName.isEmpty()) exchName = unquote(GraphJson.string(fact.get("name")));
            String exchType = AmqpBindingFact.normalizeExchangeType(str(attrs.get("exchangeType")));
            String beanName = str(attrs.get("beanName"));
            if (!exchName.isEmpty()) {
                var bean = new ExchangeBean(exchName, exchType);
                exchangesAll.add(bean);
                exchangesByType.computeIfAbsent(exchType, ignored -> new TreeSet<>()).add(bean);
                if (!beanName.isEmpty()) {
                    exchangeByBeanName.putIfAbsent(beanName, bean);
                }
                String beanAliases = str(attrs.get("beanAliases"));
                if (!beanAliases.isEmpty()) {
                    for (String alias : beanAliases.split(",")) {
                        String trimmed = alias.trim();
                        if (!trimmed.isEmpty()) exchangeByBeanName.putIfAbsent(trimmed, bean);
                    }
                }
            }
        }
    }

    private static Optional<AmqpBindingSite> extractAmqpBinding(
            KnowledgeGraph.Node node,
            Map<String, GraphEvidence> evidence,
            Map<String, String> queueByBeanName,
            Set<String> queueNamesAll,
            Map<String, ExchangeBean> exchangeByBeanName,
            Map<String, TreeSet<ExchangeBean>> exchangesByType,
            Set<ExchangeBean> exchangesAll) {
        for (var support : node.evidence()) {
            var proof = evidence.get(support.value());
            if (proof == null || !proof.family().equals("polyglot.source-syntax")) continue;
            var fields = GraphJson.object(GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults())).get("fields"));
            var fact = GraphJson.object(fields.get("fact"));
            if (Boolean.TRUE.equals(fact.get("recovered"))) continue;
            var attrs = fact.get("attributes") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            String normPath = decodePath(fields.get("path"));
            if (isTestSource(attrs, normPath)) return Optional.empty();

            String exchangeName = unquote(str(attrs.get("exchangeName")));
            String exchangeType = str(attrs.get("exchangeType"));
            String queueName = unquote(str(attrs.get("queueName")));
            String routingKeyPattern = unquote(str(attrs.get("routingKeyPattern")));
            boolean dynamicKey = "true".equalsIgnoreCase(str(attrs.get("dynamicRoutingKey")));
            String rawKeyExpr = str(attrs.get("rawRoutingKeyExpr"));

            // Cross-file [DiInj-Param] fallback resolution when Queue/Exchange beans live in another file
            if (queueName.isEmpty()) {
                String qRef = str(attrs.get("queueBeanRef"));
                if (!qRef.isEmpty() && queueByBeanName.containsKey(qRef)) {
                    queueName = queueByBeanName.get(qRef);
                } else if (queueNamesAll.size() == 1) {
                    queueName = queueNamesAll.iterator().next();
                }
            }
            if (exchangeName.isEmpty()) {
                String exRef = str(attrs.get("exchangeBeanRef"));
                String exBeanType = str(attrs.get("exchangeBeanType"));
                ExchangeBean resolved = null;
                if (!exRef.isEmpty()) {
                    resolved = exchangeByBeanName.get(exRef);
                }
                if (resolved == null && !exBeanType.isEmpty()) {
                    String normalizedWant = AmqpBindingFact.normalizeExchangeType(exBeanType);
                    var matchingType = exchangesByType.getOrDefault(normalizedWant, new TreeSet<>());
                    if (matchingType.size() == 1) {
                        resolved = matchingType.first();
                    }
                }
                if (resolved == null && exchangesAll.size() == 1) {
                    resolved = exchangesAll.iterator().next();
                }
                if (resolved != null) {
                    exchangeName = resolved.exchangeName();
                    if (exchangeType.isEmpty()) exchangeType = resolved.exchangeType();
                }
            }

            String spanText = str(attrs.get("sourceSpan"));
            if (spanText.isEmpty() && fact.get("span") instanceof Map<?, ?> sm) {
                var spanObj = GraphJson.object(sm);
                String start = String.valueOf(spanObj.getOrDefault("start", ""));
                String end = String.valueOf(spanObj.getOrDefault("end", ""));
                spanText = (normPath.isEmpty() ? "" : normPath + ":") + start + "-" + end;
            }
            if (dynamicKey || (!exchangeName.isEmpty() && !queueName.isEmpty())) {
                var bindingFact = new AmqpBindingFact(exchangeName, routingKeyPattern, queueName, exchangeType, spanText);
                return Optional.of(new AmqpBindingSite(bindingFact, node, support, dynamicKey, rawKeyExpr));
            }
        }
        return Optional.empty();
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
            boolean dynamicKey = attrs.get("dynamicRoutingKey") instanceof String dk && "true".equalsIgnoreCase(dk.trim());
            String exchange = str(attrs.get("exchange"));
            String routingKey = str(attrs.get("routingKey"));
            String rawRoutingKeyExpr = str(attrs.get("rawRoutingKeyExpr"));
            String raw = GraphJson.string(fact.get("name"));
            String topic;
            if (raw.length() >= 3 && raw.indexOf('\\') < 0 && raw.charAt(raw.length() - 1) == raw.charAt(0)
                    && (raw.charAt(0) == '\'' || raw.charAt(0) == '"' || raw.charAt(0) == '`')) {
                topic = raw.substring(1, raw.length() - 1).trim();
            } else if (symbolic || dynamicKey || !exchange.isEmpty()) {
                topic = unquote(raw);
            } else {
                continue;
            }
            if (topic.isEmpty() && !dynamicKey && exchange.isEmpty()) continue;
            String protocol = "EVENT_BUS";
            if (attrs.get("protocol") instanceof String p && !p.isBlank()) {
                protocol = p;
            }
            return Optional.of(new EventSite(node, topic, protocol, support,
                    Boolean.TRUE.equals(fact.get("recovered")), symbolic,
                    exchange, routingKey, dynamicKey, rawRoutingKeyExpr));
        }
        return Optional.empty();
    }

    private static String unquote(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == trimmed.charAt(trimmed.length() - 1)
                && (trimmed.charAt(0) == '"' || trimmed.charAt(0) == '\'' || trimmed.charAt(0) == '`')) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static String str(Object raw) {
        return raw instanceof String s ? s.trim() : "";
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
