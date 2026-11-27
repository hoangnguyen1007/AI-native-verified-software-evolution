package com.evolution.backend.query.repair;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.backend.query.CapturedRepair;

import java.util.*;

/**
 * Deterministic Spring SAT truth-region & selected-binding preservation checker comparing
 * baseline vs candidate {@link KnowledgeGraph} snapshots.
 *
 * <p>Flags unintended {@code MUST -> MAY / NEVER / UNKNOWN} flips, {@code MAY -> NEVER / UNKNOWN}
 * degradations, removed {@code MUST} bean definitions, and removed HTTP endpoints cleanly without
 * fabricating Spring facts when Spring contexts are absent ({@link Status#NOT_APPLICABLE}).
 */
public final class SpringPreservationChecker {

    public enum Status {
        PRESERVED,
        DRIFT_DETECTED,
        NOT_APPLICABLE
    }

    public record SpringDrift(
            String factKey,
            String factKind,
            String symbolLabel,
            String beforeClassification,
            String afterClassification,
            String severity,
            String detail) {}

    public record SpringPreservationReport(
            Status status,
            int baselineFactCount,
            int candidateFactCount,
            int preservedFactCount,
            int baselineWorldCount,
            int candidateWorldCount,
            int preservedWorldCount,
            List<SpringDrift> drifts,
            ContentDigest preservationDigest,
            String summary) {
        public SpringPreservationReport {
            status = status == null ? Status.NOT_APPLICABLE : status;
            drifts = drifts == null ? List.of() : List.copyOf(drifts);
        }
    }

    private record ExtractedFact(String key, String factKind, String label, String classification) {}

    private record SpringSnapshotFacts(
            boolean hasSpringContext,
            Map<String, ExtractedFact> factsByKey,
            Set<String> worldKeys,
            Set<String> endpoints) {}

    private SpringPreservationChecker() {}

    public static SpringPreservationReport compare(KnowledgeGraph baseline, Optional<KnowledgeGraph> candidate) {
        if (baseline == null) {
            return notApplicable(0, 0, "Baseline graph unavailable for Spring preservation check.");
        }
        var baseFacts = extract(baseline);
        if (!baseFacts.hasSpringContext() && baseFacts.factsByKey().isEmpty() && baseFacts.endpoints().isEmpty()) {
            return notApplicable(0, 0, "No Spring contexts or conditional truth regions modeled in baseline graph.");
        }
        if (candidate == null || candidate.isEmpty()) {
            return notApplicable(baseFacts.factsByKey().size(), baseFacts.worldKeys().size(),
                    "Candidate reanalysis graph unavailable; Spring truth-region preservation could not be compared.");
        }
        var candFacts = extract(candidate.orElseThrow());
        var drifts = new ArrayList<SpringDrift>();
        int preservedFacts = 0;

        for (var entry : baseFacts.factsByKey().entrySet()) {
            String key = entry.getKey();
            var before = entry.getValue();
            var after = candFacts.factsByKey().get(key);
            if (after == null) {
                if ("MUST".equals(before.classification())) {
                    drifts.add(new SpringDrift(
                            key, before.factKind(), before.label(),
                            before.classification(), "REMOVED", "HIGH",
                            "Baseline MUST Spring fact '" + before.label() + "' (" + before.factKind() + ") disappeared in candidate snapshot."));
                } else if ("MAY".equals(before.classification())) {
                    drifts.add(new SpringDrift(
                            key, before.factKind(), before.label(),
                            before.classification(), "REMOVED", "MEDIUM",
                            "Baseline conditional MAY Spring fact '" + before.label() + "' disappeared in candidate snapshot."));
                } else {
                    preservedFacts++;
                }
                continue;
            }
            if (before.classification().equals(after.classification())) {
                preservedFacts++;
            } else if (isRegressionFlip(before.classification(), after.classification())) {
                String severity = "MUST".equals(before.classification()) ? "HIGH" : "MEDIUM";
                drifts.add(new SpringDrift(
                        key, before.factKind(), before.label(),
                        before.classification(), after.classification(), severity,
                        "Spring truth region flipped " + before.classification() + " -> " + after.classification()
                                + " for '" + before.label() + "' (" + before.factKind() + ")."));
            } else {
                // Non-regressive strengthening (e.g. UNKNOWN -> MAY/MUST or NEVER -> MAY) counts as preserved
                preservedFacts++;
            }
        }

        for (String endpoint : baseFacts.endpoints()) {
            if (!candFacts.endpoints().contains(endpoint)) {
                int sep = endpoint.indexOf('|');
                String epLabel = sep >= 0 ? endpoint.substring(sep + 1) : endpoint;
                drifts.add(new SpringDrift(
                        "ENDPOINT|" + endpoint,
                        "ENDPOINT_DECLARATION",
                        epLabel,
                        "MUST",
                        "REMOVED",
                        "HIGH",
                        "Spring HTTP endpoint '" + epLabel + "' present in baseline was removed or lost in candidate."));
            }
        }

        int preservedWorlds = 0;
        for (String w : baseFacts.worldKeys()) {
            if (candFacts.worldKeys().contains(w)) preservedWorlds++;
        }

        Status status = drifts.isEmpty() ? Status.PRESERVED : Status.DRIFT_DETECTED;
        String summary = status == Status.PRESERVED
                ? ("Spring truth regions preserved across " + preservedFacts + "/" + baseFacts.factsByKey().size()
                        + " conditional facts"
                        + (baseFacts.endpoints().isEmpty() ? "" : (", " + baseFacts.endpoints().size() + " endpoint(s)"))
                        + (baseFacts.worldKeys().isEmpty() ? "." : (", and " + preservedWorlds + "/" + baseFacts.worldKeys().size() + " modeled configuration worlds.")))
                : ("Spring truth-region drift detected: " + drifts.size() + " unintended classification flip(s) or removed binding(s) across "
                        + baseFacts.factsByKey().size() + " baseline fact(s)"
                        + (baseFacts.endpoints().isEmpty() ? "." : (" and " + baseFacts.endpoints().size() + " endpoint(s).")));
        ContentDigest digest = CanonicalJson.sha256(List.of(
                "spring-preservation:v1", status.name(),
                baseFacts.factsByKey().size(), candFacts.factsByKey().size(), preservedFacts,
                baseFacts.worldKeys().size(), candFacts.worldKeys().size(), preservedWorlds, drifts));
        return new SpringPreservationReport(
                status,
                baseFacts.factsByKey().size(),
                candFacts.factsByKey().size(),
                preservedFacts,
                baseFacts.worldKeys().size(),
                candFacts.worldKeys().size(),
                preservedWorlds,
                drifts,
                digest,
                summary);
    }

    private static boolean isRegressionFlip(String before, String after) {
        if ("MUST".equals(before)) {
            return Set.of("MAY", "NEVER", "UNKNOWN").contains(after);
        }
        if ("MAY".equals(before)) {
            return Set.of("NEVER", "UNKNOWN").contains(after);
        }
        return false;
    }

    private static SpringPreservationReport notApplicable(int baseFacts, int baseWorlds, String summary) {
        ContentDigest digest = CanonicalJson.sha256(List.of("spring-preservation:v1", "NOT_APPLICABLE", baseFacts, baseWorlds));
        return new SpringPreservationReport(
                Status.NOT_APPLICABLE, baseFacts, 0, 0, baseWorlds, 0, 0, List.of(), digest, summary);
    }

    private static final Set<String> VOLATILE_WORLD_KEYS = Set.of(
            "evidence", "sourceIdentity", "contentDigest", "snapshotIdentity", "documentDigest");

    private record ParsedRegistrationEvent(
            String eventId,
            String parentEventId,
            boolean directConditioned,
            boolean directUncertain,
            boolean completeMetadata,
            Set<String> candidateKeys) {}

    private static SpringSnapshotFacts extract(KnowledgeGraph graph) {
        boolean hasSpring = false;
        var facts = new TreeMap<String, ExtractedFact>();
        var worlds = new TreeSet<String>();
        var endpoints = new TreeSet<String>();
        var labels = buildLabelIndex(graph);
        var contextScopes = resolveContextScopes(graph);

        for (var ctx : graph.contexts()) {
            var ev = graph.evidence(ctx.evidence()).orElse(null);
            boolean ctxIsSpring = ev != null && "spring.context".equals(ev.family());
            if (ctxIsSpring) {
                hasSpring = true;
            }
            String ctxScope = contextScopes.getOrDefault(ctx.identity(), "default");
            var conditionedEvents = new HashSet<String>();
            for (var edge : graph.edges(ctx.identity())) {
                if ("spring.has-condition".equals(edge.kind())) {
                    conditionedEvents.add(edge.source());
                }
            }

            var ctxTruthFacts = new TreeMap<String, ExtractedFact>();
            var ctxFallbackBeans = new ArrayList<KnowledgeGraph.Node>();
            var ctxEventsById = new LinkedHashMap<String, ParsedRegistrationEvent>();

            for (var node : graph.nodes(ctx.identity())) {
                if ("CONFIGURATION_WORLD".equals(node.kind())) {
                    hasSpring = true;
                    extractWorldKey(graph, node).ifPresent(wk -> worlds.add(ctxScope + "|" + wk));
                } else if ("ENDPOINT".equals(node.kind())) {
                    hasSpring = true;
                    String ep = extractEndpointLabel(graph, node);
                    if (ep != null && !ep.isBlank()) endpoints.add(ctxScope + "|" + ep);
                } else if ("TRUTH_REGION".equals(node.kind())) {
                    hasSpring = true;
                    extractTruthRegion(graph, node, labels, ctxScope)
                            .ifPresent(f -> ctxTruthFacts.put(f.key(), f));
                } else if ("BEAN_DEFINITION_CANDIDATE".equals(node.kind())) {
                    hasSpring = true;
                    ctxFallbackBeans.add(node);
                } else if ("REGISTRATION_EVENT".equals(node.kind())) {
                    hasSpring = true;
                    extractRegistrationEvent(graph, node, labels, conditionedEvents.contains(node.identity()))
                            .ifPresent(parsed -> ctxEventsById.put(parsed.eventId(), parsed));
                }
            }

            if (!ctxTruthFacts.isEmpty()) {
                facts.putAll(ctxTruthFacts);
            } else if (!ctxFallbackBeans.isEmpty()) {
                // Propagate conditionality & uncertainty from parent registration events to child bean events.
                var conditionalKeys = new HashSet<String>();
                var uncertainKeys = new HashSet<String>();
                var completeUnconditionalKeys = new HashSet<String>();
                for (var event : ctxEventsById.values()) {
                    boolean effectiveCond = isEventConditioned(event, ctxEventsById);
                    boolean effectiveUncertain = isEventUncertain(event, ctxEventsById);
                    if (effectiveCond) {
                        conditionalKeys.addAll(event.candidateKeys());
                    } else if (effectiveUncertain) {
                        uncertainKeys.addAll(event.candidateKeys());
                    } else if (event.completeMetadata()) {
                        completeUnconditionalKeys.addAll(event.candidateKeys());
                    }
                }
                boolean hasEvents = !ctxEventsById.isEmpty();
                for (var beanNode : ctxFallbackBeans) {
                    extractBeanCandidateFallback(graph, beanNode, labels, ctxScope,
                            conditionalKeys, uncertainKeys, completeUnconditionalKeys, hasEvents)
                            .ifPresent(f -> facts.putIfAbsent(f.key(), f));
                }
            }
        }
        return new SpringSnapshotFacts(hasSpring, facts, worlds, endpoints);
    }

    private static Map<String, String> resolveContextScopes(KnowledgeGraph graph) {
        var javaScopesByAnalysis = new HashMap<String, String>();
        int springContextCount = 0;
        for (var ctx : graph.contexts()) {
            var ev = graph.evidence(ctx.evidence()).orElse(null);
            if (ev == null) continue;
            if ("spring.context".equals(ev.family())) {
                springContextCount++;
            } else if ("analysis.manifest".equals(ev.family()) && ctx.analysis().isPresent()) {
                try {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                    if (obj.get("configuration") instanceof Map<?, ?> cfgObj
                            && cfgObj.get("values") instanceof Map<?, ?> vals) {
                        String mod = optText(vals.get("java.module"));
                        String ss = optText(vals.get("java.source-set"));
                        if (!mod.isBlank() || !ss.isBlank()) {
                            javaScopesByAnalysis.put(ctx.analysis().orElseThrow().value(), mod + ":" + ss);
                        }
                    }
                } catch (RuntimeException ignored) {}
            }
        }
        var out = new HashMap<String, String>();
        var usedScopes = new HashMap<String, Integer>();
        for (var ctx : graph.contexts()) {
            var ev = graph.evidence(ctx.evidence()).orElse(null);
            if (ev == null || !"spring.context".equals(ev.family())) continue;
            String scope = "";
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                scope = extractModuleSourceSetFromSpringContext(obj);
            } catch (RuntimeException ignored) {}
            if (scope.isBlank() && ctx.analysis().isPresent()) {
                scope = javaScopesByAnalysis.getOrDefault(ctx.analysis().orElseThrow().value(), "");
            }
            if (scope.isBlank()) {
                scope = springContextCount > 1 ? ctx.identity() : "default";
            }
            int dup = usedScopes.merge(scope, 1, Integer::sum);
            out.put(ctx.identity(), dup > 1 ? (scope + "#" + dup) : scope);
        }
        return out;
    }

    private static String extractModuleSourceSetFromSpringContext(Map<String, Object> obj) {
        if (obj.get("module") instanceof String m && !m.isBlank()) {
            String ss = optText(obj.get("sourceSet"));
            return ss.isBlank() ? m.strip() : (m.strip() + ":" + ss);
        }
        if (obj.get("build") instanceof Map<?, ?> buildObj) {
            Object mss = buildObj.get("moduleSourceSet");
            if (mss == null && buildObj.get("canonicalForm") instanceof Map<?, ?> cf) {
                mss = cf.get("moduleSourceSet");
            }
            if (mss instanceof Map<?, ?> mssMap) {
                String mod = optText(mssMap.get("module"));
                String ss = optText(mssMap.get("sourceSet"));
                if (!mod.isBlank() || !ss.isBlank()) {
                    return mod + ":" + ss;
                }
            }
        }
        return "";
    }

    private static boolean isEventConditioned(ParsedRegistrationEvent event, Map<String, ParsedRegistrationEvent> byId) {
        var cur = event;
        var visited = new HashSet<String>();
        while (cur != null && visited.add(cur.eventId())) {
            if (cur.directConditioned()) return true;
            cur = cur.parentEventId().isBlank() ? null : byId.get(cur.parentEventId());
        }
        return false;
    }

    private static boolean isEventUncertain(ParsedRegistrationEvent event, Map<String, ParsedRegistrationEvent> byId) {
        var cur = event;
        var visited = new HashSet<String>();
        while (cur != null && visited.add(cur.eventId())) {
            if (cur.directUncertain()) return true;
            cur = cur.parentEventId().isBlank() ? null : byId.get(cur.parentEventId());
        }
        return false;
    }

    private static Map<String, String> buildLabelIndex(KnowledgeGraph graph) {
        var map = new HashMap<String, String>();
        // Pass 1: non-bean nodes (Java declarations, injection points, endpoints, etc.) so exposedTypes can resolve to FQNs.
        for (var ctx : graph.contexts()) {
            for (var node : graph.nodes(ctx.identity())) {
                if ("BEAN_DEFINITION_CANDIDATE".equals(node.kind()) || map.containsKey(node.identity())) continue;
                if ("INJECTION_POINT".equals(node.kind())) {
                    String ipLabel = extractInjectionPointLabel(graph, node);
                    if (ipLabel != null && !ipLabel.isBlank()) {
                        map.put(node.identity(), ipLabel);
                        continue;
                    }
                }
                String lbl = CapturedRepair.nodeLabel(graph, node);
                if (lbl != null && !lbl.isBlank()) {
                    map.put(node.identity(), lbl);
                }
            }
        }
        // Pass 2: BEAN_DEFINITION_CANDIDATE nodes with snapshot-invariant semantic fallback for Names.unresolved().
        for (var ctx : graph.contexts()) {
            for (var node : graph.nodes(ctx.identity())) {
                if (!"BEAN_DEFINITION_CANDIDATE".equals(node.kind()) || map.containsKey(node.identity())) continue;
                String beanLabel = extractBeanCandidateLabel(graph, node, map);
                if (beanLabel != null && !beanLabel.isBlank()) {
                    map.put(node.identity(), beanLabel);
                }
            }
        }
        return map;
    }

    private static String extractBeanCandidateLabel(KnowledgeGraph graph, KnowledgeGraph.Node beanNode, Map<String, String> labels) {
        for (var ref : beanNode.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null || !"spring.candidate".equals(ev.family())) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                String semantic = beanSemanticKeyFromObject(obj, labels);
                if (!semantic.isBlank()) return semantic;
            } catch (RuntimeException ignored) {}
        }
        String fallback = CapturedRepair.nodeLabel(graph, beanNode);
        return (fallback == null || fallback.isBlank()) ? beanNode.identity() : fallback;
    }

    private static String beanSemanticKeyFromObject(Map<String, Object> candObj, Map<String, String> labels) {
        if (candObj.get("declaredNameKey") instanceof Map<?, ?> names
                && names.get("primary") instanceof String p && !p.isBlank()) {
            return p.strip();
        }
        // Snapshot-invariant fallback for Names.unresolved() beans: producer + definitionSlot + exposedTypes.
        String producerSig = producerSignature(candObj);
        String slot = optText(candObj.get("definitionSlot"));
        String types = exposedTypesSignature(candObj, labels);
        var parts = new ArrayList<String>();
        if (!producerSig.isBlank()) parts.add(producerSig);
        if (!slot.isBlank()) parts.add(slot);
        if (!types.isBlank()) parts.add(types);
        return String.join("::", parts);
    }

    private static String producerSignature(Map<String, Object> candObj) {
        if (!(candObj.get("producer") instanceof Map<?, ?> prod)) {
            return "";
        }
        String container = optText(prod.get("containerKey"));
        String kind = optText(prod.get("producerKind"));
        String declSlot = optText(prod.get("declarationSlot"));
        var sb = new StringBuilder();
        if (!container.isBlank()) sb.append(container);
        if (!declSlot.isBlank()) {
            if (!sb.isEmpty()) sb.append('#');
            sb.append(declSlot);
        }
        if (sb.isEmpty() && !kind.isBlank()) {
            sb.append(kind);
        }
        return sb.toString();
    }

    private static String exposedTypesSignature(Map<String, Object> candObj, Map<String, String> labels) {
        if (!(candObj.get("exposedTypes") instanceof List<?> rawTypes) || rawTypes.isEmpty()) {
            return "";
        }
        var names = new TreeSet<String>();
        for (Object item : rawTypes) {
            String rawId = "";
            if (item instanceof String s) {
                rawId = s.strip();
            } else if (item instanceof Map<?, ?> m && m.get("value") instanceof String v) {
                rawId = v.strip();
            }
            if (!rawId.isBlank()) {
                names.add(labels.getOrDefault(rawId, rawId));
            }
        }
        return String.join(",", names);
    }

    private static Set<String> candidateMatchKeys(Map<String, Object> candObj, String nodeId, Map<String, String> labels) {
        var keys = new LinkedHashSet<String>();
        if (nodeId != null && !nodeId.isBlank()) {
            keys.add("ID:" + nodeId);
        }
        if (candObj.get("identity") instanceof String id && !id.isBlank()) {
            keys.add("ID:" + id.strip());
        }
        if (candObj.get("declaredNameKey") instanceof Map<?, ?> names
                && names.get("primary") instanceof String p && !p.isBlank()) {
            keys.add("NAME:" + p.strip());
        }
        String prodSig = producerSignature(candObj);
        String defSlot = optText(candObj.get("definitionSlot"));
        if (!prodSig.isBlank() || !defSlot.isBlank()) {
            keys.add("SLOT:" + prodSig + "::" + defSlot);
        }
        String semantic = beanSemanticKeyFromObject(candObj, labels);
        if (!semantic.isBlank()) {
            keys.add("SEM:" + semantic);
        }
        return keys;
    }

    private static String extractInjectionPointLabel(KnowledgeGraph graph, KnowledgeGraph.Node pointNode) {
        for (var ref : pointNode.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null || !"spring.injection-dependency".equals(ev.family())) continue;
            try {
                var root = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                var pt = root.get("point") instanceof Map<?, ?> rawPt ? GraphJson.object(rawPt) : root;
                String ownerDecl = optText(pt.get("ownerDeclarationKey"));
                String siteKind = optText(pt.get("siteKind"));
                String siteSlot = optText(pt.get("siteSlot"));
                if (!ownerDecl.isBlank() || !siteSlot.isBlank()) {
                    StringBuilder sb = new StringBuilder();
                    if (!ownerDecl.isBlank()) sb.append(ownerDecl);
                    if (!siteKind.isBlank() || !siteSlot.isBlank()) {
                        sb.append('#').append(siteKind).append(':').append(siteSlot);
                    }
                    return sb.toString();
                }
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    private static Optional<ExtractedFact> extractTruthRegion(
            KnowledgeGraph graph, KnowledgeGraph.Node regionNode, Map<String, String> labels, String ctxScope) {
        for (var ref : regionNode.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            if (!"spring.explicit-region".equals(ev.family()) && !"spring.symbolic-region".equals(ev.family())) {
                continue;
            }
            try {
                var root = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                var regionObj = root.get("region") instanceof Map<?, ?> r ? GraphJson.object(r) : root;
                String classification = Objects.toString(regionObj.get("classification"), "").toUpperCase(Locale.ROOT);
                if (classification.isBlank()) {
                    classification = Objects.toString(root.get("classification"), "UNKNOWN").toUpperCase(Locale.ROOT);
                }
                if (!Set.of("MUST", "MAY", "NEVER", "UNKNOWN").contains(classification)) {
                    classification = "UNKNOWN";
                }
                String factKind = "TRUTH_REGION";
                String symbolLabel = regionNode.identity();
                String key;
                if (regionObj.get("fact") instanceof Map<?, ?> rawFact) {
                    var fact = GraphJson.object(rawFact);
                    factKind = Objects.toString(fact.get("kind"), "TRUTH_REGION");
                    String defId = optText(fact.get("definition"));
                    String owner = optText(fact.get("bindingOwner"));
                    String target = optText(fact.get("target"));
                    String point = optText(fact.get("injectionPoint"));
                    String resolvedDef = resolveLabel(defId, labels);
                    String resolvedOwner = resolveLabel(owner, labels);
                    String resolvedTarget = resolveLabel(target, labels);
                    String resolvedPoint = resolveLabel(point, labels);
                    if (!resolvedDef.isBlank()) {
                        symbolLabel = resolvedDef;
                        key = ctxScope + "|" + factKind + "|" + resolvedDef;
                    } else if (!resolvedOwner.isBlank() || !resolvedTarget.isBlank() || !resolvedPoint.isBlank()) {
                        String lhs;
                        if (!resolvedOwner.isBlank() && !resolvedPoint.isBlank() && !resolvedPoint.equals(resolvedOwner)) {
                            lhs = resolvedOwner + " [" + resolvedPoint + "]";
                        } else {
                            lhs = resolvedOwner.isBlank() ? resolvedPoint : resolvedOwner;
                        }
                        symbolLabel = lhs + (resolvedTarget.isBlank() ? "" : (" -> " + resolvedTarget));
                        key = ctxScope + "|" + factKind + "|" + symbolLabel;
                    } else {
                        String fid = optText(fact.get("identity"));
                        symbolLabel = fid.isBlank() ? regionNode.identity() : fid;
                        key = ctxScope + "|" + factKind + "|" + symbolLabel;
                    }
                } else {
                    String explicitLabel = optText(regionObj.get("label"));
                    if (!explicitLabel.isBlank()) symbolLabel = explicitLabel;
                    key = ctxScope + "|" + factKind + "|" + symbolLabel;
                }
                return Optional.of(new ExtractedFact(key, factKind, symbolLabel, classification));
            } catch (RuntimeException ignored) {}
        }
        return Optional.empty();
    }

    private static Optional<ParsedRegistrationEvent> extractRegistrationEvent(
            KnowledgeGraph graph, KnowledgeGraph.Node eventNode, Map<String, String> labels, boolean hasConditionEdge) {
        for (var ref : eventNode.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null || !"spring.registration-event".equals(ev.family())) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                String parentId = "";
                if (obj.get("parent") instanceof String ps) {
                    parentId = ps.strip();
                } else if (obj.get("parent") instanceof Map<?, ?> pm && pm.get("value") instanceof String pv) {
                    parentId = pv.strip();
                }
                boolean directConditioned = hasConditionEdge
                        || (obj.get("conditions") instanceof List<?> cl && !cl.isEmpty());
                String membership = optText(obj.get("membership")).toUpperCase(Locale.ROOT);
                String condMeta = optText(obj.get("conditionMetadata")).toUpperCase(Locale.ROOT);
                boolean directUncertain = "UNKNOWN".equals(membership) || "UNKNOWN".equals(condMeta);
                boolean completeMeta = "COMPLETE".equals(membership) && "COMPLETE".equals(condMeta);
                var candKeys = new LinkedHashSet<String>();
                if (obj.get("candidate") instanceof Map<?, ?> rawCand) {
                    candKeys.addAll(candidateMatchKeys(GraphJson.object(rawCand), "", labels));
                } else if (obj.get("candidate") instanceof String id && !id.isBlank()) {
                    candKeys.add("ID:" + id.strip());
                    candKeys.add("NAME:" + id.strip());
                }
                return Optional.of(new ParsedRegistrationEvent(
                        eventNode.identity(), parentId, directConditioned, directUncertain, completeMeta, candKeys));
            } catch (RuntimeException ignored) {}
        }
        return Optional.empty();
    }

    private static Optional<ExtractedFact> extractBeanCandidateFallback(
            KnowledgeGraph graph,
            KnowledgeGraph.Node beanNode,
            Map<String, String> labels,
            String ctxScope,
            Set<String> conditionalKeys,
            Set<String> uncertainKeys,
            Set<String> completeUnconditionalKeys,
            boolean hasEvents) {
        for (var ref : beanNode.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null || !"spring.candidate".equals(ev.family())) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                String label = beanSemanticKeyFromObject(obj, labels);
                if (label.isBlank()) {
                    label = labels.getOrDefault(beanNode.identity(), beanNode.identity());
                }
                var keys = candidateMatchKeys(obj, beanNode.identity(), labels);
                String cls = Objects.toString(obj.get("classification"), "").toUpperCase(Locale.ROOT);
                if (!Set.of("MUST", "MAY", "NEVER", "UNKNOWN").contains(cls)) {
                    boolean conditional = keys.stream().anyMatch(conditionalKeys::contains)
                            || (obj.get("conditions") instanceof List<?> c && !c.isEmpty());
                    boolean uncertain = keys.stream().anyMatch(uncertainKeys::contains);
                    boolean attestedComplete = keys.stream().anyMatch(completeUnconditionalKeys::contains);
                    if (conditional) {
                        cls = "MAY";
                    } else if (uncertain || !hasEvents || !attestedComplete) {
                        // Without SAT TRUTH_REGION evaluation or a complete unconditional registration event,
                        // avoid promoting uncertain bean candidates to unconditional MUST.
                        cls = "UNKNOWN";
                    } else {
                        cls = "MUST";
                    }
                }
                return Optional.of(new ExtractedFact(ctxScope + "|BEAN_CANDIDATE|" + label, "BEAN_CANDIDATE", label, cls));
            } catch (RuntimeException ignored) {}
        }
        return Optional.empty();
    }

    private static Optional<String> extractWorldKey(KnowledgeGraph graph, KnowledgeGraph.Node worldNode) {
        for (var ref : worldNode.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null || !"spring.world".equals(ev.family())) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                if (obj.get("assignment") instanceof Map<?, ?> a) {
                    Object sanitized = stripVolatileWorldDigests(a);
                    return Optional.of(CanonicalJson.write(sanitized));
                }
                if (obj.get("assignments") instanceof Map<?, ?> a) {
                    Object sanitized = stripVolatileWorldDigests(a);
                    return Optional.of(CanonicalJson.write(sanitized));
                }
                if (obj.get("activeProfiles") != null) {
                    return Optional.of(CanonicalJson.write(obj.get("activeProfiles")));
                }
            } catch (RuntimeException ignored) {}
        }
        return Optional.of(worldNode.identity());
    }

    private static Object stripVolatileWorldDigests(Object node) {
        if (node instanceof Map<?, ?> map) {
            var out = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) {
                String key = Objects.toString(entry.getKey(), "");
                if (VOLATILE_WORLD_KEYS.contains(key)) {
                    continue;
                }
                Object val = entry.getValue();
                if ("sourceChoices".equals(key) && val instanceof List<?> choices) {
                    var sanitizedChoices = new ArrayList<Object>(choices.size());
                    for (Object ch : choices) {
                        sanitizedChoices.add(stripVolatileWorldDigests(ch));
                    }
                    // ConfigurationAssignment sorts sourceChoices by digest(choice) which embeds volatile sourceIdentity;
                    // re-sort deterministically by sanitized canonical JSON so config edits preserve world identity.
                    sanitizedChoices.sort(Comparator.comparing(CanonicalJson::write));
                    out.put(key, sanitizedChoices);
                } else {
                    out.put(key, stripVolatileWorldDigests(val));
                }
            }
            return out;
        }
        if (node instanceof List<?> list) {
            var out = new ArrayList<Object>(list.size());
            for (Object item : list) {
                out.add(stripVolatileWorldDigests(item));
            }
            return out;
        }
        return node;
    }

    private static String extractEndpointLabel(KnowledgeGraph graph, KnowledgeGraph.Node node) {
        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null || !"spring.endpoint-declaration".equals(ev.family())) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                if (obj.get("route") instanceof Map<?, ?> route) {
                    String methods = route.get("methods") instanceof List<?> list
                            ? String.join(",", list.stream().map(Object::toString).sorted().toList())
                            : "";
                    String p = Objects.toString(route.get("path"), "");
                    return (methods + " " + p).trim();
                }
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    private static String resolveLabel(String rawId, Map<String, String> labels) {
        if (rawId == null || rawId.isBlank()) return "";
        return labels.getOrDefault(rawId, rawId);
    }

    private static String optText(Object raw) {
        return raw instanceof String s ? s.strip() : "";
    }
}
