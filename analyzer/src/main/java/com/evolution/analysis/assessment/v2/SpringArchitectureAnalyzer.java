package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQuery.*;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.math.BigDecimal;
import java.util.*;

/**
 * Conditional-sound Spring Architecture Health analyzer for Dimension D6 (Part A).
 *
 * <p>Strictly upholds ADR-004 conditional semantics:
 * <ul>
 *   <li>NEVER flattens mutually infeasible {@code MAY} bean wiring into a definite runtime cycle.</li>
 *   <li>NEVER penalizes Health for analyzer-opaque {@code UNKNOWN} conditions — routes condition
 *       definiteness cleanly to {@link ArchitectureAssessmentV2.AnalysisConfidenceV2#springSpaceDefiniteness()}.</li>
 * </ul>
 */
public final class SpringArchitectureAnalyzer {
    private static final GraphJson.Limits READ_LIMITS = new GraphJson.Limits(2_000_000, 64, 50_000);
    private static final BigDecimal MAY_CYCLE_WEIGHT = new BigDecimal("0.65");

    public record SpringMetrics(
            boolean applicable,
            boolean satBacked,
            Optional<BigDecimal> fieldInjectionRatio,
            int fieldInjectionCount,
            int totalDiSiteCount,
            List<ArchitectureAssessmentV2.EvidenceWitness> fieldInjectionWitnesses,
            Optional<BigDecimal> beanRelativeCyclicity,
            int mustCyclicBeanCount,
            int feasibleMayOnlyCyclicBeanCount,
            List<ArchitectureAssessmentV2.EvidenceWitness> beanCycleWitnesses,
            Optional<BigDecimal> wiringIssueRatio,
            int ambiguousOrUnsatisfiedSites,
            int evaluatedInjectionUses,
            List<ArchitectureAssessmentV2.EvidenceWitness> wiringWitnesses,
            Optional<BigDecimal> controllerBypassRatio,
            int bypassingControllers,
            int totalControllers,
            List<ArchitectureAssessmentV2.EvidenceWitness> bypassWitnesses,
            Optional<BigDecimal> spaceDefiniteness,
            long definiteConditionFacts,
            long unknownConditionFacts) {

        public static SpringMetrics notApplicable() {
            return new SpringMetrics(false, false, Optional.empty(), 0, 0, List.of(),
                    Optional.empty(), 0, 0, List.of(), Optional.empty(), 0, 0, List.of(),
                    Optional.empty(), 0, 0, List.of(), Optional.empty(), 0, 0);
        }
    }

    /**
     * Analyzes Spring architecture health for either a {@code spring.context} (with optional linked Java
     * {@code analysis.manifest}) or a Java context containing {@code SPRING_SOURCE_WIRING} / Spring stereotypes.
     */
    public static SpringMetrics analyze(
            ArchitectureQueryService queries,
            Optional<String> springContextId,
            Optional<String> javaContextId,
            int maxWitnesses,
            Runnable checkpoint) {
        Objects.requireNonNull(queries);
        int limit = Math.max(1, Math.min(25, maxWitnesses));
        var graph = queries.graph();
        checkpoint.run();

        boolean hasSpringCtx = springContextId.isPresent();
        boolean hasSourceWiring = javaContextId.map(cid -> hasSpringSourceSignals(graph, cid)).orElse(false);
        if (!hasSpringCtx && !hasSourceWiring) {
            return SpringMetrics.notApplicable();
        }

        // 1. Field Injection Ratio (from SAT INJECTION_POINT or fallback SOURCE_INJECTION_SITE)
        int fieldSites = 0;
        int totalDiSites = 0;
        var fieldWitnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        if (hasSpringCtx) {
            String ctx = springContextId.orElseThrow();
            for (var node : graph.nodes(ctx)) {
                if (!"INJECTION_POINT".equals(node.kind())) continue;
                var info = readInjectionPoint(graph, node);
                if (info == null || "VALUE".equals(info.siteKind)) continue;
                totalDiSites++;
                if ("FIELD".equals(info.siteKind)) {
                    fieldSites++;
                    if (fieldWitnesses.size() < limit) {
                        fieldWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                                "SPRING_FIELD_INJECTION", node.identity(),
                                info.ownerLabel + "." + info.slot,
                                "Field injection (@Autowired/@Inject on field); prefer constructor injection",
                                node.evidence()));
                    }
                }
            }
        }
        if (totalDiSites == 0 && javaContextId.isPresent()) {
            String jCtx = javaContextId.orElseThrow();
            for (var node : graph.nodes(jCtx)) {
                if (!"SOURCE_INJECTION_SITE".equals(node.kind())) continue;
                var info = readSourceInjectionSite(graph, node);
                if (info == null || "VALUE".equals(info.siteKind)) continue;
                totalDiSites++;
                if ("FIELD".equals(info.siteKind)) {
                    fieldSites++;
                    if (fieldWitnesses.size() < limit) {
                        fieldWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                                "SPRING_FIELD_INJECTION", node.identity(),
                                info.ownerLabel + "." + info.slot,
                                "Field injection detected from source wiring; prefer constructor injection",
                                node.evidence()));
                    }
                }
            }
        }
        Optional<BigDecimal> fieldInjectionRatio = totalDiSites == 0
                ? Optional.empty()
                : Optional.of(CalibrationCurve.divide(fieldSites, totalDiSites));
        checkpoint.run();

        // 2. Conditional-Safe Spring Bean Wiring Cyclicity + Wiring Hygiene + Space Definiteness
        Optional<BigDecimal> beanRc = Optional.empty();
        int mustCyclicCount = 0;
        int feasibleMayOnlyCount = 0;
        var cycleWitnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();

        Optional<BigDecimal> wiringRatio = Optional.empty();
        int badSites = 0;
        int evalSites = 0;
        var wiringWitnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();

        Optional<BigDecimal> spaceDefiniteness = Optional.empty();
        long definiteFacts = 0;
        long unknownFacts = 0;

        if (hasSpringCtx) {
            String sCtx = springContextId.orElseThrow();
            var mustReq = new Request(queries.graphIdentity(), sCtx, Scope.SPRING_COMPONENT, Projection.MUST,
                    Direction.OUTGOING, Set.of("spring.selected-binding"), Population.all(), false,
                    Optional.empty(), Limits.defaults());
            var unionReq = new Request(queries.graphIdentity(), sCtx, Scope.SPRING_COMPONENT, Projection.BOUNDED_UNION,
                    Direction.OUTGOING, Set.of("spring.selected-binding"), Population.all(), false,
                    Optional.empty(), Limits.defaults());

            var mustProjRes = queries.metricProjection(mustReq);
            var unionProjRes = queries.metricProjection(unionReq);
            checkpoint.run();

            if (!unionProjRes.items().isEmpty()) {
                var unionProj = unionProjRes.items().getFirst();
                var mustProj = mustProjRes.items().isEmpty()
                        ? new MetricProjection(List.of(), List.of(), List.of(), List.of())
                        : mustProjRes.items().getFirst();

                var beanIds = new ArrayList<String>();
                var labels = new HashMap<String, String>();
                for (var e : unionProj.entities()) {
                    beanIds.add(e.identity());
                    labels.put(e.identity(), e.qualifiedName().or(e::spelling).or(e::canonicalName).orElse(e.identity()));
                }
                Collections.sort(beanIds);
                int bCount = beanIds.size();
                if (bCount >= 2) {
                    var bIdx = new HashMap<String, Integer>(bCount * 2);
                    for (int i = 0; i < bCount; i++) bIdx.put(beanIds.get(i), i);

                    var mustAdj = buildAdjacency(bCount, bIdx, mustProj.edges(), true);
                    var mustScc = StructuralTopologyAnalyzer.tarjanScc(bCount, mustAdj.adj);
                    long mustSq = 0;
                    var mustCyclicNodes = new HashSet<Integer>();
                    for (int[] comp : mustScc.components()) {
                        if (comp.length > 1 || (comp.length == 1 && mustAdj.selfLoop[comp[0]])) {
                            mustSq += (long) comp.length * (long) comp.length;
                            for (int u : comp) mustCyclicNodes.add(u);
                            if (cycleWitnesses.size() < limit) {
                                cycleWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                                        "SPRING_MUST_BEAN_CYCLE", beanIds.get(comp[0]),
                                        formatCompLabel(comp, beanIds, labels),
                                        "Unconditional (MUST) circular Spring bean dependency (" + comp.length + " beans)",
                                        List.of()));
                            }
                        }
                    }
                    mustCyclicCount = mustCyclicNodes.size();
                    BigDecimal denom = BigDecimal.valueOf(Math.max(bCount, 10));
                    BigDecimal rcMust = mustSq == 0 ? BigDecimal.ZERO
                            : CalibrationCurve.divide(CalibrationCurve.sqrt(BigDecimal.valueOf(mustSq)), denom);

                    // Check UNION for potential extra MAY cycles; verify joint feasibility via queries.findCycles (ADR-004!)
                    var unionAdj = buildAdjacency(bCount, bIdx, unionProj.edges(), false);
                    var unionScc = StructuralTopologyAnalyzer.tarjanScc(bCount, unionAdj.adj);
                    boolean hasExtraUnionCycles = false;
                    for (int[] comp : unionScc.components()) {
                        if (comp.length > 1 || (comp.length == 1 && unionAdj.selfLoop[comp[0]])) {
                            for (int u : comp) {
                                if (!mustCyclicNodes.contains(u)) {
                                    hasExtraUnionCycles = true;
                                    break;
                                }
                            }
                        }
                    }

                    BigDecimal rcMay = rcMust;
                    if (hasExtraUnionCycles) {
                        var feasibleCycles = queries.findCycles(unionReq);
                        checkpoint.run();
                        var feasibleMayNodes = new HashSet<Integer>();
                        var mayAdjSets = new ArrayList<TreeSet<Integer>>(bCount);
                        for (int i = 0; i < bCount; i++) mayAdjSets.add(new TreeSet<>());
                        boolean[] maySelf = new boolean[bCount];
                        for (var path : feasibleCycles.items()) {
                            if (path.feasibility() != Truth.SAT) continue;
                            boolean hasMayEdge = false;
                            for (var edge : path.edges()) {
                                if (edge.classification() == Classification.MAY) hasMayEdge = true;
                                Integer u = bIdx.get(edge.source());
                                Integer v = bIdx.get(edge.target());
                                if (u == null || v == null) continue;
                                if (u.intValue() == v.intValue()) maySelf[u] = true;
                                else mayAdjSets.get(u).add(v);
                            }
                            if (hasMayEdge) {
                                for (String vid : path.vertices()) {
                                    Integer idx = bIdx.get(vid);
                                    if (idx != null && !mustCyclicNodes.contains(idx)) feasibleMayNodes.add(idx);
                                }
                            }
                        }
                        if (!feasibleMayNodes.isEmpty()) {
                            var mayAdj = new ArrayList<int[]>(bCount);
                            for (int i = 0; i < bCount; i++) {
                                var s = mayAdjSets.get(i);
                                int[] a = new int[s.size()];
                                int k = 0;
                                for (int v : s) a[k++] = v;
                                mayAdj.add(a);
                            }
                            var feasibleScc = StructuralTopologyAnalyzer.tarjanScc(bCount, mayAdj);
                            long feasibleSq = 0;
                            for (int[] comp : feasibleScc.components()) {
                                if (comp.length > 1 || (comp.length == 1 && maySelf[comp[0]])) {
                                    feasibleSq += (long) comp.length * (long) comp.length;
                                    boolean isMayOnly = false;
                                    for (int u : comp) if (feasibleMayNodes.contains(u)) isMayOnly = true;
                                    if (isMayOnly && cycleWitnesses.size() < limit) {
                                        cycleWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                                                "SPRING_MAY_BEAN_CYCLE", beanIds.get(comp[0]),
                                                formatCompLabel(comp, beanIds, labels),
                                                "Jointly feasible conditional (MAY) Spring bean cycle (" + comp.length + " beans) [POTENTIAL_CONFIGURATION_EXPOSURE]",
                                                List.of()));
                                    }
                                }
                            }
                            feasibleMayOnlyCount = feasibleMayNodes.size();
                            rcMay = feasibleSq == 0 ? rcMust
                                    : CalibrationCurve.divide(CalibrationCurve.sqrt(BigDecimal.valueOf(feasibleSq)), denom);
                        }
                    }

                    BigDecimal extraMay = rcMay.subtract(rcMust).max(BigDecimal.ZERO);
                    beanRc = Optional.of(rcMust.add(MAY_CYCLE_WEIGHT.multiply(extraMay)).min(BigDecimal.ONE));
                }
            }

            // Spring Wiring Hygiene (0-candidate required uses or ambiguous multi-candidate uses without selected binding)
            var entityBindReq = new Request(queries.graphIdentity(), sCtx, Scope.ENTITY, Projection.BOUNDED_UNION,
                    Direction.OUTGOING, Set.of("spring.selected-binding", "spring.injection-candidate"), Population.all(),
                    false, Optional.empty(), Limits.defaults());
            var bindProj = queries.metricProjection(entityBindReq);
            if (!bindProj.items().isEmpty()) {
                var selectedByUse = new HashMap<String, Set<String>>();
                var candidatesByUse = new HashMap<String, Set<String>>();
                var unknownUses = new HashSet<String>();
                for (var edge : bindProj.items().getFirst().edges()) {
                    if (edge.classification() == Classification.UNKNOWN) {
                        unknownUses.add(edge.source());
                        continue;
                    }
                    if (!edge.definite()) continue;
                    if (edge.kinds().contains("spring.selected-binding")) {
                        selectedByUse.computeIfAbsent(edge.source(), k -> new TreeSet<>()).add(edge.target());
                    }
                    if (edge.kinds().contains("spring.injection-candidate")) {
                        candidatesByUse.computeIfAbsent(edge.source(), k -> new TreeSet<>()).add(edge.target());
                    }
                }
                for (var node : graph.nodes(sCtx)) {
                    if (!"INJECTION_USE".equals(node.kind())) continue;
                    String useId = node.identity();
                    if (unknownUses.contains(useId)) continue; // UNKNOWN goes to confidence, never penalized in Health
                    boolean required = isInjectionUseRequired(graph, node);
                    if (!required) continue;
                    evalSites++;
                    int sel = selectedByUse.getOrDefault(useId, Set.of()).size();
                    int cand = candidatesByUse.getOrDefault(useId, Set.of()).size();
                    if (sel == 0 && cand != 1) {
                        badSites++;
                        if (wiringWitnesses.size() < limit) {
                            String kind = cand == 0 ? "SPRING_UNSATISFIED_INJECTION" : "SPRING_AMBIGUOUS_INJECTION";
                            String detail = cand == 0
                                    ? "Required injection site has 0 matching bean candidates"
                                    : "Required injection site has " + cand + " ambiguous candidates without deterministic selection";
                            wiringWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(kind, useId, useId, detail, node.evidence()));
                        }
                    }
                }
                if (evalSites > 0) {
                    wiringRatio = Optional.of(CalibrationCurve.divide(badSites, evalSites));
                }
            }

            // Spring Configuration Space Definiteness -> routed strictly to AnalysisConfidenceV2!
            // Pre-index context nodes once for O(1) lookup (S2).
            var springNodesById = new HashMap<String, KnowledgeGraph.Node>();
            for (var node : graph.nodes(sCtx)) springNodesById.put(node.identity(), node);
            for (var node : graph.nodes(sCtx)) {
                if (!"TRUTH_REGION".equals(node.kind())) continue;
                var outcome = classifyTruthRegionDefiniteness(graph, node, springNodesById);
                if (outcome == 1) definiteFacts++;
                else if (outcome == -1) unknownFacts++;
            }
            if (definiteFacts + unknownFacts > 0) {
                spaceDefiniteness = Optional.of(CalibrationCurve.divide(definiteFacts, definiteFacts + unknownFacts));
            }
        }
        checkpoint.run();

        // 3. Controller-to-Repository Bypass (when Controllers, Services, and Repositories exist)
        Optional<BigDecimal> bypassRatio = Optional.empty();
        int bypassing = 0;
        int controllerTotal = 0;
        var bypassWitnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        if (javaContextId.isPresent()) {
            var bypass = evaluateControllerBypass(graph, javaContextId.orElseThrow(), limit);
            bypassRatio = bypass.ratio;
            bypassing = bypass.bypassing;
            controllerTotal = bypass.controllers;
            bypassWitnesses.addAll(bypass.witnesses);
        }

        boolean anyMetric = fieldInjectionRatio.isPresent() || beanRc.isPresent()
                || wiringRatio.isPresent() || bypassRatio.isPresent();
        return new SpringMetrics(
                anyMetric,
                hasSpringCtx,
                fieldInjectionRatio, fieldSites, totalDiSites, fieldWitnesses,
                beanRc, mustCyclicCount, feasibleMayOnlyCount, cycleWitnesses,
                wiringRatio, badSites, evalSites, wiringWitnesses,
                bypassRatio, bypassing, controllerTotal, bypassWitnesses,
                spaceDefiniteness, definiteFacts, unknownFacts);
    }

    private record Adj(List<int[]> adj, boolean[] selfLoop) {}

    private static Adj buildAdjacency(int n, Map<String, Integer> idx, List<EdgeView> edges, boolean mustOnly) {
        var sets = new ArrayList<TreeSet<Integer>>(n);
        for (int i = 0; i < n; i++) sets.add(new TreeSet<>());
        boolean[] self = new boolean[n];
        for (var e : edges) {
            if (!e.definite()) continue;
            if (mustOnly && e.classification() != Classification.MUST) continue;
            Integer u = idx.get(e.source());
            Integer v = idx.get(e.target());
            if (u == null || v == null) continue;
            if (u.intValue() == v.intValue()) self[u] = true;
            else sets.get(u).add(v);
        }
        var out = new ArrayList<int[]>(n);
        for (int i = 0; i < n; i++) {
            var s = sets.get(i);
            int[] arr = new int[s.size()];
            int k = 0;
            for (int v : s) arr[k++] = v;
            out.add(arr);
        }
        return new Adj(out, self);
    }

    private static String formatCompLabel(int[] comp, List<String> ids, Map<String, String> labels) {
        var parts = new ArrayList<String>();
        for (int i = 0; i < Math.min(4, comp.length); i++) {
            parts.add(labels.getOrDefault(ids.get(comp[i]), ids.get(comp[i])));
        }
        return String.join(" <-> ", parts) + (comp.length > 4 ? " (+" + (comp.length - 4) + " more)" : "");
    }

    private static boolean hasSpringSourceSignals(KnowledgeGraph graph, String javaContextId) {
        for (var node : graph.nodes(javaContextId)) {
            if (Set.of("SPRING_SOURCE_WIRING", "SOURCE_INJECTION_SITE", "SOURCE_BEAN_CANDIDATE", "ENDPOINT").contains(node.kind())) {
                return true;
            }
        }
        for (var node : graph.nodes(javaContextId)) {
            if (!"ANNOTATION_USE".equals(node.kind())) continue;
            for (var ref : node.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev != null && "java.annotation-use".equals(ev.family())
                        && (ev.canonicalJson().contains("org.springframework.") || ev.canonicalJson().contains("RestController")
                                || ev.canonicalJson().contains("Autowired"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private record SiteInfo(String siteKind, String ownerLabel, String slot) {}

    private static SiteInfo readInjectionPoint(KnowledgeGraph graph, KnowledgeGraph.Node node) {
        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null || !"spring.injection-dependency".equals(ev.family())) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                if (obj.get("point") instanceof Map<?, ?> rawPt) {
                    var pt = GraphJson.object(rawPt);
                    String kind = pt.get("siteKind") instanceof String s ? s : "";
                    String owner = pt.get("ownerDeclarationKey") instanceof String s ? s : node.identity();
                    String slot = pt.get("siteSlot") instanceof String s ? s : "";
                    return new SiteInfo(kind, owner, slot);
                }
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    private static SiteInfo readSourceInjectionSite(KnowledgeGraph graph, KnowledgeGraph.Node node) {
        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null || !"spring.source-injection-site".equals(ev.family())) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                String kind = obj.get("kind") instanceof String s ? s : "";
                String owner = obj.get("owner") instanceof String s ? s : node.identity();
                String slot = obj.get("name") instanceof String s && !s.isBlank() ? s
                        : (obj.get("element") instanceof String s2 ? s2 : "");
                return new SiteInfo(kind, owner, slot);
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    private static boolean isInjectionUseRequired(KnowledgeGraph graph, KnowledgeGraph.Node node) {
        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null || !"spring.injection-dependency".equals(ev.family())) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                if (obj.get("required") instanceof Boolean req) return req;
                if (obj.get("cardinality") instanceof String c && ("OPTIONAL".equals(c) || "COLLECTION".equals(c))) return false;
            } catch (RuntimeException ignored) {}
        }
        return true;
    }

    /** Returns +1 if definite (no unknown worlds/formulas), -1 if unknown region is non-empty, 0 if unreadable. */
    private static int classifyTruthRegionDefiniteness(KnowledgeGraph graph, KnowledgeGraph.Node node,
            Map<String, KnowledgeGraph.Node> contextNodesById) {
        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            if ("spring.explicit-region".equals(ev.family())) {
                try {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                    var region = GraphJson.object(obj.get("region"));
                    if (region.get("unknownWorlds") instanceof List<?> unk) {
                        return unk.isEmpty() ? 1 : -1;
                    }
                } catch (RuntimeException ignored) {}
            } else if ("spring.symbolic-region".equals(ev.family())) {
                try {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                    var region = GraphJson.object(obj.get("region"));
                    String q = region.get("classification") instanceof String s ? s : "";
                    if ("UNKNOWN".equals(q)) return -1;
                    if (Set.of("MUST", "MAY", "NEVER").contains(q)) return 1;
                    // Check unknownRegion formula if present via O(1) index
                    if (region.get("unknownRegion") instanceof String unkFormulaId) {
                        var fNode = contextNodesById.get(unkFormulaId);
                        if (fNode != null) {
                            for (var fRef : fNode.evidence()) {
                                var fEv = graph.evidence(fRef).orElse(null);
                                if (fEv != null && "spring.formula".equals(fEv.family())) {
                                    if (fEv.canonicalJson().contains("\"kind\":\"FALSE\"")) return 1;
                                    return -1;
                                }
                            }
                        }
                    }
                    return 1;
                } catch (RuntimeException ignored) {}
            }
        }
        return 0;
    }

    private static String extractAnnotationSimpleName(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw.trim();
        if (s.startsWith("@")) s = s.substring(1).trim();
        int paren = s.indexOf('(');
        if (paren >= 0) s = s.substring(0, paren).trim();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private record BypassSummary(Optional<BigDecimal> ratio, int bypassing, int controllers,
                                 List<ArchitectureAssessmentV2.EvidenceWitness> witnesses) {}

    private static BypassSummary evaluateControllerBypass(KnowledgeGraph graph, String javaContextId, int limit) {
        var controllers = new TreeSet<String>();
        var services = new TreeSet<String>();
        var repositories = new TreeSet<String>();
        var labels = new HashMap<String, String>();
        var annNodeToName = new HashMap<String, String>();
        var endpointIds = new HashSet<String>();

        // Single O(N) pass over context nodes (S2)
        for (var node : graph.nodes(javaContextId)) {
            String kind = node.kind();
            if ("TYPE".equals(kind)) {
                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null || !"java.declaration".equals(ev.family())) continue;
                    try {
                        var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                        var ent = GraphJson.object(obj.get("entity"));
                        if (!"PROJECT".equals(ent.get("origin"))) continue;
                        String spelling = obj.get("spelling") instanceof String s && !s.isBlank()
                                ? s : String.valueOf(ent.getOrDefault("canonicalName", node.identity()));
                        labels.put(node.identity(), spelling);
                    } catch (RuntimeException ignored) {}
                }
            } else if ("ANNOTATION_USE".equals(kind)) {
                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null || !"java.annotation-use".equals(ev.family())) continue;
                    try {
                        var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                        String name = obj.get("annotationName") instanceof String s ? s
                                : obj.get("spelling") instanceof String s2 ? s2 : "";
                        annNodeToName.put(node.identity(), name);
                    } catch (RuntimeException ignored) {}
                }
            } else if ("ENDPOINT".equals(kind)) {
                endpointIds.add(node.identity());
            }
        }

        // Single O(E) pass over context edges (replaces O(E * N) inner node scan!)
        var methodToOwningType = new HashMap<String, String>();
        var endpointParents = new HashSet<String>();
        for (var edge : graph.edges(javaContextId)) {
            String ek = edge.kind();
            if ("graph.has-annotation-use".equals(ek) && labels.containsKey(edge.source())) {
                for (String target : edge.target().identities()) {
                    String simple = extractAnnotationSimpleName(annNodeToName.getOrDefault(target, ""));
                    if ("RestController".equals(simple) || "Controller".equals(simple)) {
                        controllers.add(edge.source());
                    } else if ("Service".equals(simple)) {
                        services.add(edge.source());
                    } else if ("Repository".equals(simple)) {
                        repositories.add(edge.source());
                    }
                }
            } else if ("graph.declares".equals(ek) && labels.containsKey(edge.source())) {
                for (String target : edge.target().identities()) {
                    methodToOwningType.putIfAbsent(target, edge.source());
                }
            } else if ("graph.contains".equals(ek)) {
                for (String target : edge.target().identities()) {
                    if (endpointIds.contains(target)) {
                        if (labels.containsKey(edge.source())) controllers.add(edge.source());
                        else endpointParents.add(edge.source());
                    }
                }
            }
        }
        for (String handlerId : endpointParents) {
            String ownerType = methodToOwningType.get(handlerId);
            if (ownerType != null) controllers.add(ownerType);
        }

        if (controllers.isEmpty() || repositories.isEmpty() || services.isEmpty()) {
            return new BypassSummary(Optional.empty(), 0, controllers.size(), List.of());
        }

        var bypassingSet = new TreeMap<String, List<String>>();
        for (var edge : graph.edges(javaContextId)) {
            if (!"graph.depends-on".equals(edge.kind()) || !controllers.contains(edge.source())) continue;
            for (String target : edge.target().identities()) {
                if (repositories.contains(target)) {
                    bypassingSet.computeIfAbsent(edge.source(), k -> new ArrayList<>())
                            .add(labels.getOrDefault(target, target));
                }
            }
        }

        var witnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        for (var entry : bypassingSet.entrySet()) {
            if (witnesses.size() >= limit) break;
            String ctrlId = entry.getKey();
            var repos = entry.getValue().stream().distinct().sorted().toList();
            witnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                    "SPRING_CONTROLLER_BYPASS", ctrlId, labels.getOrDefault(ctrlId, ctrlId),
                    "Controller directly depends on repository layer bypassing @Service: " + String.join(", ", repos),
                    List.of()));
        }
        return new BypassSummary(
                Optional.of(CalibrationCurve.divide(bypassingSet.size(), controllers.size())),
                bypassingSet.size(), controllers.size(), witnesses);
    }

    private SpringArchitectureAnalyzer() {}
}
