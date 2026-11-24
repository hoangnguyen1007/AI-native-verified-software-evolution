package com.evolution.backend.query;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQuery.*;
import com.evolution.analysis.query.ArchitectureQueryService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Deterministic, zero-hallucination Architecture Insights & Refactoring Advisor.
 * Computes exact Tarjan Strongly Connected Components (cycles & tangles), minimal cycle-breaking
 * edge candidates backed by canonical edge evidence, Robert C. Martin Instability, Abstractness,
 * Distance-from-Main-Sequence, edge-level SDP violations, condensation-DAG transitive blast radius,
 * cross-layer coupling matrix (DSM), and an executive Markdown report — strictly from canonical
 * ArchitectureQueryService projections.
 */
public final class ArchitectureInsightsAdvisor {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("architecture.insights", "m9-advisor-v2");
    private static final int MAX_SCC_EXACT_CUT_NODES = 120;
    private static final int REACHABILITY_BATCH_SIZE = 1_024;
    private static final BigDecimal INSTABILITY_THRESHOLD = new BigDecimal("0.6500");
    private static final BigDecimal SDP_EDGE_MARGIN = new BigDecimal("0.2000");
    private static final BigDecimal ZERO_4 = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final BigDecimal MAIN_SEQ_BAND = new BigDecimal("0.2500");
    private static final BigDecimal PAIN_THRESHOLD = new BigDecimal("0.3000");
    private static final BigDecimal USELESS_THRESHOLD = new BigDecimal("0.7000");
    private static final Comparator<Hotspot> HOTSPOT_ORDER =
            Comparator.comparingInt(Hotspot::hotspotScore).reversed()
                    .thenComparing(Comparator.comparingInt(Hotspot::transitiveDependents).reversed())
                    .thenComparing(Comparator.comparingInt((Hotspot h) -> h.fanIn() + h.fanOut()).reversed())
                    .thenComparing(Hotspot::identity);

    private ArchitectureInsightsAdvisor() {}

    public record CycleBreakCandidate(
            String edgeIdentity,
            String source,
            String sourceLabel,
            String target,
            String targetLabel,
            Classification classification,
            int supportCount,
            List<ContentDigest> evidence,
            boolean simulated,
            boolean breaksEntireTangle,
            int remainingLargestComponentSize,
            int splitComponentCount,
            String recommendedStrategy,
            String rationale) {
        public CycleBreakCandidate {
            evidence = List.copyOf(evidence);
        }
    }

    public record Tangle(
            String id,
            String severity,
            int memberCount,
            int internalEdgeCount,
            int totalSupportWeight,
            List<String> members,
            List<String> memberLabels,
            List<CycleBreakCandidate> breakCandidates) {
        public Tangle {
            members = List.copyOf(members);
            memberLabels = List.copyOf(memberLabels);
            breakCandidates = List.copyOf(breakCandidates);
        }
    }

    public record Hotspot(
            String identity,
            String label,
            String kind,
            String group,
            String archetype,
            int hotspotScore,
            int fanIn,
            int fanOut,
            int externalDependencies,
            BigDecimal instability,
            int transitiveDependents,
            boolean inCycle,
            int policyViolations,
            boolean sdpViolation) {}

    public record CouplingCell(
            String fromGroup,
            String toGroup,
            int edgeCount,
            int supportWeight,
            boolean bidirectional,
            boolean backEdge) {}

    public record CouplingMatrix(
            List<String> orderedGroups,
            Map<String, Integer> groupSizes,
            List<CouplingCell> cells,
            int backEdgeCellCount,
            int bidirectionalPairCount) {
        public CouplingMatrix {
            orderedGroups = List.copyOf(orderedGroups);
            groupSizes = Collections.unmodifiableMap(new LinkedHashMap<>(groupSizes));
            cells = List.copyOf(cells);
        }
    }

    public record SdpEdgeViolation(
            String edgeIdentity,
            String source,
            String sourceLabel,
            BigDecimal sourceInstability,
            String target,
            String targetLabel,
            BigDecimal targetInstability,
            BigDecimal delta) {}

    public record PackageMetric(
            String group,
            int entityCount,
            int abstractCount,
            BigDecimal abstractness,
            int fanIn,
            int fanOut,
            BigDecimal instability,
            BigDecimal distance,
            String zone) {}

    public record SummaryMetrics(
            int analyzedEntities,
            int internalDependencies,
            int externalDependencies,
            int tangleCount,
            int tangledEntityCount,
            BigDecimal tangledEntityRatio,
            BigDecimal averageInstability,
            int maxBlastRadius,
            int sdpViolationCount,
            int policyFindingCount,
            BigDecimal abstractness,
            BigDecimal distanceFromMainSequence,
            int edgeSdpViolationCount) {
        public SummaryMetrics(
                int analyzedEntities,
                int internalDependencies,
                int externalDependencies,
                int tangleCount,
                int tangledEntityCount,
                BigDecimal tangledEntityRatio,
                BigDecimal averageInstability,
                int maxBlastRadius,
                int sdpViolationCount,
                int policyFindingCount) {
            this(analyzedEntities, internalDependencies, externalDependencies, tangleCount, tangledEntityCount,
                    tangledEntityRatio, averageInstability, maxBlastRadius, sdpViolationCount, policyFindingCount,
                    ZERO_4, ZERO_4, 0);
        }
    }

    public record InsightsReport(
            VersionedIdentifier schemaVersion,
            ContentDigest graph,
            String context,
            Scope scope,
            Projection projection,
            AssessmentStatus status,
            Operation operation,
            List<CapabilityGapRecord> gaps,
            Bounds bounds,
            SummaryMetrics summary,
            List<Tangle> tangles,
            List<Hotspot> hotspots,
            CouplingMatrix couplingMatrix,
            String markdownReport,
            List<SdpEdgeViolation> sdpViolations,
            List<PackageMetric> packageMetrics) {
        public InsightsReport(
                VersionedIdentifier schemaVersion,
                ContentDigest graph,
                String context,
                Scope scope,
                Projection projection,
                AssessmentStatus status,
                Operation operation,
                List<CapabilityGapRecord> gaps,
                Bounds bounds,
                SummaryMetrics summary,
                List<Tangle> tangles,
                List<Hotspot> hotspots,
                CouplingMatrix couplingMatrix,
                String markdownReport) {
            this(schemaVersion, graph, context, scope, projection, status, operation, gaps, bounds, summary,
                    tangles, hotspots, couplingMatrix, markdownReport, List.of(), List.of());
        }
        public InsightsReport {
            gaps = List.copyOf(gaps);
            tangles = List.copyOf(tangles);
            hotspots = List.copyOf(hotspots);
            sdpViolations = List.copyOf(sdpViolations);
            packageMetrics = List.copyOf(packageMetrics);
        }
    }

    public static InsightsReport analyze(
            ArchitectureQueryService service,
            Request baseRequest,
            Optional<PublishedReports.Report> publishedReport,
            int topLimit) {
        Objects.requireNonNull(service);
        Objects.requireNonNull(baseRequest);
        Objects.requireNonNull(publishedReport);
        int topN = Math.max(1, Math.min(50, topLimit));

        var projRequest = new Request(
                baseRequest.graph(),
                baseRequest.context(),
                baseRequest.scope(),
                baseRequest.projection(),
                Direction.OUTGOING,
                baseRequest.relationshipKinds(),
                baseRequest.population(),
                baseRequest.includeMust(),
                baseRequest.realization(),
                baseRequest.limits());

        Result<MetricProjection> projected = service.metricProjection(projRequest);
        Runnable checkpoint = () -> {
            var stop = service.assessmentStopReason();
            if (stop != StopReason.NONE) throw new CalculationInterrupted(stop);
        };
        checkpoint.run();
        MetricProjection mp = projected.items().isEmpty()
                ? new MetricProjection(List.of(), List.of(), List.of(), List.of())
                : projected.items().getFirst();

        // Partition internal project entities vs external JDK/DEPENDENCY targets
        Map<String, EntityView> projectEntities = new TreeMap<>();
        for (EntityView ev : mp.entities()) {
            if (isProjectEntity(ev)) {
                projectEntities.put(ev.identity(), ev);
            }
        }

        int n = projectEntities.size();
        List<String> nodeIds = List.copyOf(projectEntities.keySet());
        Map<String, Integer> nodeIndex = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            nodeIndex.put(nodeIds.get(i), i);
        }

        int[] fanIn = new int[n];
        int[] fanOut = new int[n];
        int[] extDeps = new int[n];
        boolean[] selfLoop = new boolean[n];
        List<List<Integer>> outAdj = new ArrayList<>(n);
        List<EdgeView> internalEdges = new ArrayList<>();
        int externalEdgeCount = 0;

        for (int i = 0; i < n; i++) {
            outAdj.add(new ArrayList<>());
        }

        for (EdgeView edge : mp.edges()) {
            Integer u = nodeIndex.get(edge.source());
            if (u == null) continue;
            Integer v = nodeIndex.get(edge.target());
            if (v == null) {
                extDeps[u]++;
                externalEdgeCount++;
                continue;
            }
            internalEdges.add(edge);
            if (u.intValue() == v.intValue()) {
                selfLoop[u] = true;
            } else {
                outAdj.get(u).add(v);
                fanOut[u]++;
                fanIn[v]++;
            }
        }
        for (int i = 0; i < n; i++) {
            var adj = outAdj.get(i);
            if (adj.size() > 1) {
                Collections.sort(adj);
            }
        }

        // 1. Exact Iterative Tarjan SCC
        SccResult scc = tarjanScc(n, outAdj);
        int compCount = scc.components.size();
        boolean[] inCycle = new boolean[n];
        int tangledEntityCount = 0;
        List<int[]> cyclicComps = new ArrayList<>();
        for (int c = 0; c < compCount; c++) {
            int[] members = scc.components.get(c);
            if (members.length > 1 || (members.length == 1 && selfLoop[members[0]])) {
                cyclicComps.add(members);
                for (int u : members) {
                    inCycle[u] = true;
                    tangledEntityCount++;
                }
            }
        }

        // Index internal edges per SCC for tangle reporting & minimal cycle-breaker analysis
        Map<Integer, List<EdgeView>> edgesByComp = new HashMap<>();
        for (EdgeView edge : internalEdges) {
            int u = nodeIndex.get(edge.source());
            int v = nodeIndex.get(edge.target());
            if (scc.compOf[u] == scc.compOf[v] && inCycle[u]) {
                edgesByComp.computeIfAbsent(scc.compOf[u], k -> new ArrayList<>()).add(edge);
            }
        }

        // Sort cyclic components: largest size desc, internal edges desc, first canonical ID asc
        cyclicComps.sort((a, b) -> {
            int cmp = Integer.compare(b.length, a.length);
            if (cmp != 0) return cmp;
            int ea = edgesByComp.getOrDefault(scc.compOf[a[0]], List.of()).size();
            int eb = edgesByComp.getOrDefault(scc.compOf[b[0]], List.of()).size();
            cmp = Integer.compare(eb, ea);
            if (cmp != 0) return cmp;
            return nodeIds.get(a[0]).compareTo(nodeIds.get(b[0]));
        });

        List<Tangle> tangles = new ArrayList<>();
        for (int idx = 0; idx < Math.min(topN, cyclicComps.size()); idx++) {
            checkpoint.run();
            int[] members = cyclicComps.get(idx);
            int compId = scc.compOf[members[0]];
            List<EdgeView> cEdges = edgesByComp.getOrDefault(compId, List.of());
            List<String> mIds = new ArrayList<>(members.length);
            List<String> mLabels = new ArrayList<>(members.length);
            for (int u : members) {
                String id = nodeIds.get(u);
                mIds.add(id);
                mLabels.add(shortLabel(projectEntities.get(id), baseRequest.scope()));
            }
            int supportSum = 0;
            for (EdgeView e : cEdges) supportSum += Math.max(1, e.supportCount());
            String tangleDigest = CanonicalJson.sha256(List.of("tangle", mIds)).value();
            String severity = members.length >= 8 ? "CRITICAL" : members.length >= 4 ? "HIGH" : "MODERATE";
            List<CycleBreakCandidate> breaks = computeBreakCandidates(
                    members, cEdges, nodeIds, nodeIndex, projectEntities, fanIn, fanOut, baseRequest.scope());
            tangles.add(new Tangle(
                    tangleDigest, severity, members.length, cEdges.size(), supportSum, mIds, mLabels, breaks));
        }

        // 2. Exact Condensation-DAG Transitive Incoming Blast Radius
        int[] transitiveDependents = computeBlastRadius(n, outAdj, scc, checkpoint);

        // 3. Policy Violation Concentration per Entity
        Map<String, Integer> violationsByEntity = new HashMap<>();
        int totalPolicyFindings = 0;
        if (publishedReport.isPresent()) {
            List<PolicyEvaluation.Finding> findings = publishedReport.orElseThrow().evaluation().findings();
            totalPolicyFindings = findings.size();
            for (PolicyEvaluation.Finding f : findings) {
                for (String vId : new LinkedHashSet<>(f.bundle().path())) {
                    violationsByEntity.merge(vId, 1, Integer::sum);
                }
            }
        }

        // 4. Common Package Prefix for clean DSM & Hotspot Grouping
        String commonPkgPrefix = computeCommonPackagePrefix(projectEntities.values(), baseRequest.scope());
        Map<String, String> groupByNode = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            String id = nodeIds.get(i);
            groupByNode.put(id, groupName(projectEntities.get(id), baseRequest.scope(), commonPkgPrefix));
        }

        // 5. Instability, Edge-Level SDP Violations, Abstractness & Distance-from-Main-Sequence
        BigDecimal[] instabilityByNode = new BigDecimal[n];
        BigDecimal instabilitySum = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            int ca = fanIn[i];
            int ce = fanOut[i];
            BigDecimal inst = (ca + ce == 0)
                    ? ZERO_4
                    : BigDecimal.valueOf(ce).divide(BigDecimal.valueOf(ca + ce), 4, RoundingMode.HALF_UP);
            instabilityByNode[i] = inst;
            instabilitySum = instabilitySum.add(inst);
        }

        boolean[] hasEdgeSdpViolation = new boolean[n];
        List<SdpEdgeViolation> allSdpEdges = new ArrayList<>();
        for (EdgeView edge : internalEdges) {
            int u = nodeIndex.get(edge.source());
            int v = nodeIndex.get(edge.target());
            if (u == v || fanIn[u] < 1 || fanOut[v] < 1) continue;
            BigDecimal delta = instabilityByNode[v].subtract(instabilityByNode[u]);
            if (delta.compareTo(SDP_EDGE_MARGIN) > 0) {
                hasEdgeSdpViolation[u] = true;
                allSdpEdges.add(new SdpEdgeViolation(
                        edge.identity(),
                        edge.source(),
                        shortLabel(projectEntities.get(edge.source()), baseRequest.scope()),
                        instabilityByNode[u],
                        edge.target(),
                        shortLabel(projectEntities.get(edge.target()), baseRequest.scope()),
                        instabilityByNode[v],
                        delta));
            }
        }
        allSdpEdges.sort(Comparator.comparing(SdpEdgeViolation::delta).reversed()
                .thenComparing(SdpEdgeViolation::edgeIdentity));
        List<SdpEdgeViolation> topSdpEdges = allSdpEdges.size() > topN
                ? List.copyOf(allSdpEdges.subList(0, topN))
                : List.copyOf(allSdpEdges);

        Set<String> abstractEntityIds = collectAbstractEntityIds(service);
        int abstractCountTotal = 0;
        for (String id : nodeIds) {
            if (abstractEntityIds.contains(id)) abstractCountTotal++;
        }

        int maxBlast = 0;
        int sdpViolations = 0;
        var ranked = new PriorityQueue<>(topN, HOTSPOT_ORDER.reversed());

        for (int i = 0; i < n; i++) {
            if ((i & 1023) == 0) checkpoint.run();
            String id = nodeIds.get(i);
            EntityView ev = projectEntities.get(id);
            int ca = fanIn[i];
            int ce = fanOut[i];
            int ext = extDeps[i];
            int blast = transitiveDependents[i];
            if (blast > maxBlast) maxBlast = blast;
            BigDecimal inst = instabilityByNode[i];

            boolean sdp = (ca >= 3 && ce >= 2 && inst.compareTo(INSTABILITY_THRESHOLD) > 0) || hasEdgeSdpViolation[i];
            if (sdp) sdpViolations++;
            int pViolations = violationsByEntity.getOrDefault(id, 0);
            boolean cyclic = inCycle[i];

            String archetype = classifyArchetype(ca, ce, blast, cyclic, sdp, pViolations, n);
            int score = computeHotspotScore(ca, ce, blast, cyclic, sdp, pViolations, n);
            var hotspot = new Hotspot(
                    id,
                    shortLabel(ev, baseRequest.scope()),
                    ev.kind(),
                    groupByNode.get(id),
                    archetype,
                    score,
                    ca,
                    ce,
                    ext,
                    inst,
                    blast,
                    cyclic,
                    pViolations,
                    sdp);
            if (ranked.size() < topN) {
                ranked.add(hotspot);
            } else if (HOTSPOT_ORDER.compare(hotspot, ranked.element()) < 0) {
                ranked.remove();
                ranked.add(hotspot);
            }
        }

        List<Hotspot> topHotspots = ranked.stream().sorted(HOTSPOT_ORDER).toList();

        // 6. Cross-Module / Package Layer Coupling Matrix (DSM) & Martin Package Main-Sequence Metrics
        CouplingMatrix dsm = buildCouplingMatrix(nodeIds, groupByNode, internalEdges);
        List<PackageMetric> packageMetrics = computePackageMetrics(nodeIds, groupByNode, abstractEntityIds, internalEdges);
        checkpoint.run();

        BigDecimal tangledRatio = n == 0
                ? ZERO_4
                : BigDecimal.valueOf(tangledEntityCount).divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        BigDecimal avgInstability = n == 0
                ? ZERO_4
                : instabilitySum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        BigDecimal abstractness = n == 0
                ? ZERO_4
                : BigDecimal.valueOf(abstractCountTotal).divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        BigDecimal distance = n == 0
                ? ZERO_4
                : abstractness.add(avgInstability).subtract(BigDecimal.ONE).abs().setScale(4, RoundingMode.HALF_UP);

        SummaryMetrics summary = new SummaryMetrics(
                n,
                internalEdges.size(),
                externalEdgeCount,
                cyclicComps.size(),
                tangledEntityCount,
                tangledRatio,
                avgInstability,
                maxBlast,
                sdpViolations,
                totalPolicyFindings,
                abstractness,
                distance,
                allSdpEdges.size());

        String markdown = renderMarkdown(
                baseRequest, projected.status(), projected.gaps(), summary, tangles, topHotspots, dsm, topSdpEdges, packageMetrics);

        return new InsightsReport(
                VERSION,
                baseRequest.graph(),
                baseRequest.context(),
                baseRequest.scope(),
                baseRequest.projection(),
                projected.status(),
                projected.operation(),
                projected.gaps(),
                projected.bounds(),
                summary,
                tangles,
                topHotspots,
                dsm,
                markdown,
                topSdpEdges,
                packageMetrics);
    }

    private static Set<String> collectAbstractEntityIds(ArchitectureQueryService service) {
        var ids = new HashSet<String>();
        for (var ev : service.graph().evidence()) {
            if (!"java.type-shape".equals(ev.family())) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(65_536, 16, 1024)));
                if ((Boolean.TRUE.equals(obj.get("abstractType")) || "INTERFACE".equals(obj.get("kind")))
                        && obj.get("type") instanceof String typeId) {
                    ids.add(typeId);
                }
            } catch (RuntimeException ignored) {}
        }
        return ids;
    }

    private static List<PackageMetric> computePackageMetrics(
            List<String> nodeIds,
            Map<String, String> groupByNode,
            Set<String> abstractEntityIds,
            List<EdgeView> internalEdges) {
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> abstracts = new HashMap<>();
        for (String id : nodeIds) {
            String g = groupByNode.get(id);
            counts.merge(g, 1, Integer::sum);
            if (abstractEntityIds.contains(id)) abstracts.merge(g, 1, Integer::sum);
        }
        Map<String, Integer> gFanIn = new HashMap<>();
        Map<String, Integer> gFanOut = new HashMap<>();
        for (EdgeView edge : internalEdges) {
            String from = groupByNode.get(edge.source());
            String to = groupByNode.get(edge.target());
            if (from == null || to == null || from.equals(to)) continue;
            gFanOut.merge(from, 1, Integer::sum);
            gFanIn.merge(to, 1, Integer::sum);
        }
        var metrics = new ArrayList<PackageMetric>(counts.size());
        for (var entry : counts.entrySet()) {
            String g = entry.getKey();
            int total = entry.getValue();
            int abs = abstracts.getOrDefault(g, 0);
            int ca = gFanIn.getOrDefault(g, 0);
            int ce = gFanOut.getOrDefault(g, 0);
            BigDecimal a = total == 0 ? ZERO_4
                    : BigDecimal.valueOf(abs).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
            BigDecimal inst = (ca + ce == 0) ? ZERO_4
                    : BigDecimal.valueOf(ce).divide(BigDecimal.valueOf(ca + ce), 4, RoundingMode.HALF_UP);
            BigDecimal dist = a.add(inst).subtract(BigDecimal.ONE).abs().setScale(4, RoundingMode.HALF_UP);
            String zone;
            if (dist.compareTo(MAIN_SEQ_BAND) <= 0) zone = "MAIN_SEQUENCE";
            else if (a.compareTo(PAIN_THRESHOLD) < 0 && inst.compareTo(PAIN_THRESHOLD) < 0) zone = "ZONE_OF_PAIN";
            else if (a.compareTo(USELESS_THRESHOLD) > 0 && inst.compareTo(USELESS_THRESHOLD) > 0) zone = "ZONE_OF_USELESSNESS";
            else zone = "BALANCED";
            metrics.add(new PackageMetric(g, total, abs, a, ca, ce, inst, dist, zone));
        }
        metrics.sort(Comparator.comparing(PackageMetric::distance).reversed()
                .thenComparing(Comparator.comparingInt(PackageMetric::entityCount).reversed())
                .thenComparing(PackageMetric::group));
        return List.copyOf(metrics);
    }

    private static boolean isProjectEntity(EntityView ev) {
        return ev.origin().isEmpty() || "PROJECT".equals(ev.origin().orElse(""));
    }

    private record SccResult(int[] compOf, List<int[]> components) {}

    /** Exact iterative Tarjan's Strongly Connected Components algorithm. Zero recursion depth risk. */
    private static SccResult tarjanScc(int n, List<List<Integer>> adj) {
        int[] disc = new int[n];
        int[] low = new int[n];
        int[] compOf = new int[n];
        Arrays.fill(disc, -1);
        Arrays.fill(compOf, -1);
        boolean[] onStack = new boolean[n];
        int[] tarjanStack = new int[n];
        int sp = 0;
        int[] callNode = new int[n];
        int[] callEdgeIdx = new int[n];
        int timer = 0;
        List<int[]> components = new ArrayList<>();

        for (int root = 0; root < n; root++) {
            if (disc[root] != -1) continue;
            int depth = 0;
            callNode[0] = root;
            callEdgeIdx[0] = 0;
            disc[root] = low[root] = timer++;
            tarjanStack[sp++] = root;
            onStack[root] = true;

            while (depth >= 0) {
                int u = callNode[depth];
                List<Integer> neighbors = adj.get(u);
                int ei = callEdgeIdx[depth];
                if (ei < neighbors.size()) {
                    callEdgeIdx[depth] = ei + 1;
                    int v = neighbors.get(ei);
                    if (disc[v] == -1) {
                        depth++;
                        callNode[depth] = v;
                        callEdgeIdx[depth] = 0;
                        disc[v] = low[v] = timer++;
                        tarjanStack[sp++] = v;
                        onStack[v] = true;
                    } else if (onStack[v]) {
                        if (disc[v] < low[u]) low[u] = disc[v];
                    }
                } else {
                    if (low[u] == disc[u]) {
                        int count = 0;
                        for (int k = sp - 1; k >= 0; k--) {
                            count++;
                            if (tarjanStack[k] == u) break;
                        }
                        int[] comp = new int[count];
                        int compId = components.size();
                        for (int k = 0; k < count; k++) {
                            int w = tarjanStack[--sp];
                            onStack[w] = false;
                            compOf[w] = compId;
                            comp[k] = w;
                        }
                        Arrays.sort(comp);
                        components.add(comp);
                    }
                    depth--;
                    if (depth >= 0) {
                        int parent = callNode[depth];
                        if (low[u] < low[parent]) low[parent] = low[u];
                    }
                }
            }
        }
        return new SccResult(compOf, components);
    }

    private static List<CycleBreakCandidate> computeBreakCandidates(
            int[] members,
            List<EdgeView> compEdges,
            List<String> nodeIds,
            Map<String, Integer> globalIdx,
            Map<String, EntityView> entities,
            int[] fanIn,
            int[] fanOut,
            Scope scope) {
        if (compEdges.isEmpty()) return List.of();
        int m = members.length;
        Map<Integer, Integer> localIdx = new HashMap<>(m * 2);
        for (int i = 0; i < m; i++) localIdx.put(members[i], i);

        // Pre-sort candidate edges by lowest supportCount, conditional MAY preference, then canonical ID
        List<EdgeView> sortedEdges = new ArrayList<>(compEdges);
        sortedEdges.sort((a, b) -> {
            int cmp = Integer.compare(Math.max(1, a.supportCount()), Math.max(1, b.supportCount()));
            if (cmp != 0) return cmp;
            boolean aMay = a.classification() == Classification.MAY;
            boolean bMay = b.classification() == Classification.MAY;
            if (aMay != bMay) return aMay ? -1 : 1;
            return a.identity().compareTo(b.identity());
        });

        boolean exactCutAnalysis = m <= MAX_SCC_EXACT_CUT_NODES && sortedEdges.size() <= 400;
        int evalLimit = Math.min(sortedEdges.size(), exactCutAnalysis ? 48 : 8);

        List<CycleBreakCandidate> evaluated = new ArrayList<>(evalLimit);
        for (int eIdx = 0; eIdx < evalLimit; eIdx++) {
            EdgeView candidate = sortedEdges.get(eIdx);
            int gu = globalIdx.get(candidate.source());
            int gv = globalIdx.get(candidate.target());
            int largestRemaining = m;
            int splitCount = 1;
            boolean remainingSelfLoop = false;
            if (exactCutAnalysis) {
                List<List<Integer>> subAdj = new ArrayList<>(m);
                for (int i = 0; i < m; i++) subAdj.add(new ArrayList<>());
                for (EdgeView e : compEdges) {
                    if (e.identity().equals(candidate.identity())) continue;
                    int lu = localIdx.get(globalIdx.get(e.source()));
                    int lv = localIdx.get(globalIdx.get(e.target()));
                    if (lu == lv) remainingSelfLoop = true;
                    else subAdj.get(lu).add(lv);
                }
                SccResult afterCut = tarjanScc(m, subAdj);
                splitCount = afterCut.components.size();
                largestRemaining = 1;
                for (int[] subComp : afterCut.components) {
                    if (subComp.length > largestRemaining) largestRemaining = subComp.length;
                }
            }
            boolean breaksAll = exactCutAnalysis && largestRemaining == 1 && !remainingSelfLoop;
            EntityView srcEv = entities.get(candidate.source());
            EntityView dstEv = entities.get(candidate.target());
            String srcLabel = shortLabel(srcEv, scope);
            String dstLabel = shortLabel(dstEv, scope);
            String strategy = recommendDecouplingStrategy(scope, m, fanIn[gu], fanOut[gu], fanIn[gv], fanOut[gv], candidate);
            int supports = Math.max(1, candidate.supportCount());
            String rationale;
            if (!exactCutAnalysis) rationale = "Low-support edge to investigate. This cluster exceeded the cut simulation budget.";
            else if (breaksAll) rationale = "Removing this edge eliminates cycles within this cluster in the selected structural projection.";
            else if (largestRemaining < m) rationale = "Removing this edge splits the cluster. Cycles remain in the selected projection.";
            else rationale = "Removing this edge leaves the cluster cyclic in the selected projection.";
            evaluated.add(new CycleBreakCandidate(
                    candidate.identity(),
                    candidate.source(),
                    srcLabel,
                    candidate.target(),
                    dstLabel,
                    candidate.classification(),
                    supports,
                    candidate.evidence(),
                    exactCutAnalysis,
                    breaksAll,
                    largestRemaining,
                    splitCount,
                    strategy,
                    rationale));
        }

        evaluated.sort((a, b) -> {
            if (a.breaksEntireTangle() != b.breaksEntireTangle()) return a.breaksEntireTangle() ? -1 : 1;
            int cmp = Integer.compare(a.remainingLargestComponentSize(), b.remainingLargestComponentSize());
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.supportCount(), b.supportCount());
            if (cmp != 0) return cmp;
            return a.edgeIdentity().compareTo(b.edgeIdentity());
        });
        return evaluated.subList(0, Math.min(3, evaluated.size()));
    }

    private static String recommendDecouplingStrategy(
            Scope scope, int sccSize, int srcFanIn, int srcFanOut, int dstFanIn, int dstFanOut, EdgeView edge) {
        if (scope == Scope.SPRING_COMPONENT || edge.classification() == Classification.MAY) {
            return sccSize == 2 ? "PUBLISH_DOMAIN_EVENT_OR_LAZY_PORT" : "EXTRACT_SPI_PORT_BEAN";
        }
        if (scope == Scope.PACKAGE || scope == Scope.MODULE) {
            return "EXTRACT_SHARED_CONTRACT_PACKAGE";
        }
        if (dstFanIn >= srcFanIn + 2) {
            return "DEPENDENCY_INVERSION_EXTRACT_INTERFACE";
        }
        if (sccSize == 2) {
            return "EXTRACT_VALUE_TYPE_OR_EVENT_CALLBACK";
        }
        return "INTRODUCE_PORT_INTERFACE_AT_BOUNDARY";
    }

    static final class CalculationInterrupted extends RuntimeException {
        final StopReason reason;

        CalculationInterrupted(StopReason reason) {
            super("Architecture insights interrupted: " + reason);
            this.reason = reason;
        }
    }

    /** Exact incoming reachability over the SCC condensation, excluding the subject. */
    private static int[] computeBlastRadius(int n, List<List<Integer>> outAdj, SccResult scc, Runnable checkpoint) {
        int components = scc.components.size();
        var dependents = new int[components];
        var callers = new int[components];
        var lastSource = new int[components];
        var edges = new ArrayList<List<Integer>>(components);
        boolean singleCaller = true;
        for (int c = 0; c < components; c++) {
            var targets = new ArrayList<Integer>();
            for (int u : scc.components.get(c)) {
                for (int v : outAdj.get(u)) {
                    int target = scc.compOf[v];
                    if (target == c || lastSource[target] == c + 1) continue;
                    lastSource[target] = c + 1;
                    targets.add(target);
                    if (++callers[target] > 1) singleCaller = false;
                }
            }
            edges.add(targets);
        }

        // Tarjan assigns the dependency a lower component index than its caller.
        if (singleCaller) {
            for (int c = components - 1; c >= 0; c--) {
                dependents[c] += scc.components.get(c).length;
                for (int target : edges.get(c)) dependents[target] = dependents[c];
            }
        } else {
            // Propagate one batch of caller identities at a time. Joins count a caller once,
            // and the working set grows linearly with the number of components.
            var reachedBy = new BitSet[components];
            for (int low = 0; low < components; low += REACHABILITY_BATCH_SIZE) {
                checkpoint.run();
                int high = Math.min(components, low + REACHABILITY_BATCH_SIZE);
                var weighted = new ArrayList<Integer>();
                for (int c = low; c < high; c++) {
                    if (scc.components.get(c).length > 1) weighted.add(c);
                }
                for (int c = high - 1; c >= 0; c--) {
                    if ((c & 1023) == 0) checkpoint.run();
                    BitSet set = reachedBy[c];
                    reachedBy[c] = null;
                    if (c >= low) {
                        set = set == null ? new BitSet() : (BitSet) set.clone();
                        set.set(c - low);
                    }
                    if (set == null) continue;
                    int count = set.cardinality();
                    for (int caller : weighted) {
                        if (set.get(caller - low)) count += scc.components.get(caller).length - 1;
                    }
                    dependents[c] += count;
                    for (int target : edges.get(c)) {
                        BitSet previous = reachedBy[target];
                        if (previous == null) {
                            // Sharing is safe when the target clones before it mutates its set.
                            reachedBy[target] = set;
                        } else if (previous != set) {
                            var union = (BitSet) previous.clone();
                            union.or(set);
                            reachedBy[target] = union;
                        }
                    }
                }
            }
        }

        int[] result = new int[n];
        for (int u = 0; u < n; u++) result[u] = dependents[scc.compOf[u]] - 1;
        return result;
    }

    private static String classifyArchetype(
            int ca, int ce, int blast, boolean cyclic, boolean sdp, int violations, int totalNodes) {
        if (violations > 0 && cyclic) return "CYCLIC_POLICY_HOTSPOT";
        if (ca >= 3 && ce >= 3) return "HUB_BOTTLENECK";
        if (sdp) return "UNSTABLE_FOUNDATION";
        if (cyclic) return "CYCLE_PARTICIPANT";
        if (violations > 0) return "POLICY_HOTSPOT";
        if (ce >= 6 && ca <= 1) return "HIGH_EFFERENT_COORDINATOR";
        if (ca >= 4 && ce == 0) return "STABLE_CORE_ABSTRACTION";
        if (blast >= Math.max(4, totalNodes / 4)) return "HIGH_BLAST_FOUNDATION";
        return "BALANCED_COMPONENT";
    }

    private static int computeHotspotScore(
            int ca, int ce, int blast, boolean cyclic, boolean sdp, int violations, int totalNodes) {
        double blastRatio = totalNodes <= 1 ? 0.0 : (double) blast / (totalNodes - 1);
        double hubProduct = Math.min(25.0, Math.sqrt((double) ca * ce) * 5.0);
        double blastPoints = Math.min(25.0, blastRatio * 25.0 + Math.min(10.0, ca * 1.5));
        double cyclePoints = cyclic ? 22.0 : 0.0;
        double sdpPoints = sdp ? 14.0 : 0.0;
        double policyPoints = Math.min(20.0, violations * 10.0);
        double fanOutPoints = Math.min(10.0, ce * 1.0);
        int raw = (int) Math.round(hubProduct + blastPoints + cyclePoints + sdpPoints + policyPoints + fanOutPoints);
        return Math.max(0, Math.min(100, raw));
    }

    private static CouplingMatrix buildCouplingMatrix(
            List<String> nodeIds, Map<String, String> groupByNode, List<EdgeView> internalEdges) {
        Map<String, Integer> groupSizes = new TreeMap<>();
        for (String id : nodeIds) {
            groupSizes.merge(groupByNode.get(id), 1, Integer::sum);
        }
        record Pair(String from, String to) {}
        Map<Pair, int[]> pairCounts = new LinkedHashMap<>();
        Map<String, Integer> outFlow = new HashMap<>();
        Map<String, Integer> inFlow = new HashMap<>();

        for (EdgeView edge : internalEdges) {
            String gFrom = groupByNode.get(edge.source());
            String gTo = groupByNode.get(edge.target());
            if (gFrom == null || gTo == null || gFrom.equals(gTo)) continue;
            int weight = Math.max(1, edge.supportCount());
            int[] stats = pairCounts.computeIfAbsent(new Pair(gFrom, gTo), k -> new int[2]);
            stats[0]++;
            stats[1] += weight;
            outFlow.merge(gFrom, weight, Integer::sum);
            inFlow.merge(gTo, weight, Integer::sum);
        }

        // Order groups from upper/consumer layers (high net outFlow - inFlow) down to foundational layers
        List<String> orderedGroups = new ArrayList<>(groupSizes.keySet());
        orderedGroups.sort((a, b) -> {
            int netA = outFlow.getOrDefault(a, 0) - inFlow.getOrDefault(a, 0);
            int netB = outFlow.getOrDefault(b, 0) - inFlow.getOrDefault(b, 0);
            int cmp = Integer.compare(netB, netA);
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        Map<String, Integer> rankOf = new HashMap<>();
        for (int i = 0; i < orderedGroups.size(); i++) rankOf.put(orderedGroups.get(i), i);

        List<CouplingCell> cells = new ArrayList<>(pairCounts.size());
        int backEdges = 0;
        Set<String> bidirPairs = new HashSet<>();
        for (var entry : pairCounts.entrySet()) {
            String from = entry.getKey().from();
            String to = entry.getKey().to();
            boolean bidir = pairCounts.containsKey(new Pair(to, from));
            if (bidir) {
                bidirPairs.add(from.compareTo(to) < 0 ? from + "<->" + to : to + "<->" + from);
            }
            boolean backEdge = rankOf.getOrDefault(from, 0) > rankOf.getOrDefault(to, 0);
            if (backEdge) backEdges++;
            cells.add(new CouplingCell(from, to, entry.getValue()[0], entry.getValue()[1], bidir, backEdge));
        }
        cells.sort((a, b) -> {
            int cmp = Integer.compare(b.supportWeight(), a.supportWeight());
            if (cmp != 0) return cmp;
            cmp = a.fromGroup().compareTo(b.fromGroup());
            return cmp != 0 ? cmp : a.toGroup().compareTo(b.toGroup());
        });

        Map<String, Integer> orderedSizes = new LinkedHashMap<>();
        for (String g : orderedGroups) orderedSizes.put(g, groupSizes.get(g));
        return new CouplingMatrix(orderedGroups, orderedSizes, cells, backEdges, bidirPairs.size());
    }

    private static String computeCommonPackagePrefix(Collection<EntityView> entities, Scope scope) {
        if (scope == Scope.MODULE) return "";
        String common = null;
        int seen = 0;
        for (EntityView ev : entities) {
            String qn = ev.qualifiedName().or(ev::canonicalName).orElse("");
            int lastDot = scope == Scope.PACKAGE ? qn.length() : qn.lastIndexOf('.');
            if (lastDot <= 0) continue;
            String pkg = qn.substring(0, lastDot);
            seen++;
            if (common == null) {
                common = pkg;
            } else {
                common = sharedDotPrefix(common, pkg);
                if (common.isEmpty()) break;
            }
        }
        return seen >= 2 && common != null ? common : "";
    }

    private static String sharedDotPrefix(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int limit = Math.min(pa.length, pb.length);
        int match = 0;
        while (match < limit && pa[match].equals(pb[match])) match++;
        if (match == 0) return "";
        return String.join(".", Arrays.copyOf(pa, match));
    }

    private static String groupName(EntityView ev, Scope scope, String commonPrefix) {
        if (scope == Scope.MODULE) {
            return shortLabel(ev, scope);
        }
        String qn = ev.qualifiedName().or(ev::canonicalName).orElse("");
        if (scope == Scope.PACKAGE) {
            if (!commonPrefix.isEmpty() && qn.startsWith(commonPrefix + ".")) {
                String rem = qn.substring(commonPrefix.length() + 1);
                int dot = rem.indexOf('.');
                return dot > 0 ? rem.substring(0, dot) : rem;
            }
            return qn.isEmpty() ? "(default)" : qn;
        }
        int lastDot = qn.lastIndexOf('.');
        if (lastDot <= 0) return ev.module().orElse("(root)");
        String pkg = qn.substring(0, lastDot);
        if (!commonPrefix.isEmpty()) {
            if (pkg.equals(commonPrefix)) return "(root)";
            if (pkg.startsWith(commonPrefix + ".")) {
                String rem = pkg.substring(commonPrefix.length() + 1);
                int dot = rem.indexOf('.');
                return dot > 0 ? rem.substring(0, dot) : rem;
            }
        }
        int lastPkgDot = pkg.lastIndexOf('.');
        return lastPkgDot >= 0 ? pkg.substring(lastPkgDot + 1) : pkg;
    }

    private static String shortLabel(EntityView ev, Scope scope) {
        if (ev == null) return "unknown";
        String raw = ev.qualifiedName().or(ev::canonicalName).or(ev::spelling).orElse(ev.identity());
        if (scope == Scope.TYPE || scope == Scope.SPRING_COMPONENT) {
            int dot = raw.lastIndexOf('.');
            if (dot > 0 && dot + 1 < raw.length() && !raw.startsWith("sha256:")) {
                int prevDot = raw.lastIndexOf('.', dot - 1);
                return prevDot >= 0 ? raw.substring(prevDot + 1) : raw;
            }
        }
        return raw;
    }

    private static String renderMarkdown(
            Request req,
            AssessmentStatus status,
            List<CapabilityGapRecord> gaps,
            SummaryMetrics s,
            List<Tangle> tangles,
            List<Hotspot> hotspots,
            CouplingMatrix dsm,
            List<SdpEdgeViolation> sdpEdges,
            List<PackageMetric> packageMetrics) {
        var md = new StringBuilder(4096);
        md.append("# Architecture Intelligence & Refactoring Executive Report\n\n");
        md.append("- **Graph Digest:** `").append(req.graph().value()).append("`\n");
        md.append("- **Context:** `").append(req.context()).append("`\n");
        md.append("- **Scope / Projection:** `").append(req.scope()).append("` / `").append(req.projection()).append("`\n");
        md.append("- **Evidence Status:** `").append(status).append("` (Recorded capability gaps: ").append(gaps.size()).append(")\n\n");

        if (status != AssessmentStatus.COMPLETE || !gaps.isEmpty()) {
            md.append("> **Qualification Notice:** This projection carries `").append(status)
              .append("` status with ").append(gaps.size())
              .append(" explicit capability gap(s). Unobserved dynamic or missing-classpath dependencies are never guessed.\n\n");
        }

        md.append("## 1. Structural Summary KPIs\n\n");
        md.append("| Metric | Value |\n|---|---|\n");
        md.append("| Analyzed Project Entities | ").append(s.analyzedEntities()).append(" |\n");
        md.append("| Internal Dependencies | ").append(s.internalDependencies()).append(" |\n");
        md.append("| Cyclic Tangles (SCCs) | ").append(s.tangleCount())
          .append(" (").append(s.tangledEntityCount()).append(" entities, ")
          .append(s.tangledEntityRatio().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)).append("%) |\n");
        md.append("| Average Martin Instability ($I$) | ").append(s.averageInstability()).append(" |\n");
        md.append("| Abstractness ($A$) / Distance ($D$) | ").append(s.abstractness()).append(" / ").append(s.distanceFromMainSequence()).append(" |\n");
        md.append("| Max Incoming Blast Radius | ").append(s.maxBlastRadius()).append(" dependents |\n");
        md.append("| Stable Dependencies Principle (SDP) Warnings | ").append(s.sdpViolationCount())
          .append(" components / ").append(s.edgeSdpViolationCount()).append(" edges |\n");
        md.append("| Active Policy Violations | ").append(s.policyFindingCount()).append(" |\n\n");

        md.append("## 2. Structural Dependency Cycles and Candidate Edges\n\n");
        md.append("Cycles are computed on the selected projection. A union can include edges from different configurations.\n\n");
        if (tangles.isEmpty()) {
            md.append("No structural dependency cycles detected in this scope (`0` non-trivial SCCs).\n\n");
        } else {
            for (int i = 0; i < tangles.size(); i++) {
                Tangle t = tangles.get(i);
                md.append("### Tangle #").append(i + 1).append(" — `").append(t.severity())
                  .append("` (").append(t.memberCount()).append(" components, ")
                  .append(t.internalEdgeCount()).append(" internal edges)\n");
                md.append("- **Members:** ")
                  .append(String.join(", ", t.memberLabels().subList(0, Math.min(12, t.memberLabels().size()))))
                  .append(t.memberLabels().size() > 12 ? " …" : "").append("\n");
                for (CycleBreakCandidate c : t.breakCandidates()) {
                    md.append("- **Recommended Cut:** `").append(c.sourceLabel()).append(" -> ").append(c.targetLabel())
                      .append("` (`").append(c.recommendedStrategy()).append("`, supports=").append(c.supportCount())
                      .append(", simulated=").append(c.simulated());
                    if (c.simulated()) md.append(", breaksAll=").append(c.breaksEntireTangle());
                    md.append(") — ").append(c.rationale()).append("\n");
                }
                md.append("\n");
            }
        }

        md.append("## 3. Top Architectural Hotspots & Blast Radius\n\n");
        if (hotspots.isEmpty()) {
            md.append("No project entities in scope.\n\n");
        } else {
            md.append("| Rank | Component | Archetype | Score | Fan-In ($C_a$) | Fan-Out ($C_e$) | Instability ($I$) | Blast Radius | Violations |\n");
            md.append("|---|---|---|---|---|---|---|---|---|\n");
            for (int i = 0; i < Math.min(10, hotspots.size()); i++) {
                Hotspot h = hotspots.get(i);
                md.append("| ").append(i + 1)
                  .append(" | `").append(h.label()).append("`")
                  .append(" | ").append(h.archetype())
                  .append(" | **").append(h.hotspotScore()).append("**")
                  .append(" | ").append(h.fanIn())
                  .append(" | ").append(h.fanOut())
                  .append(" | ").append(h.instability())
                  .append(" | ").append(h.transitiveDependents())
                  .append(" | ").append(h.policyViolations()).append(" |\n");
            }
            md.append("\n");
        }

        md.append("## 4. Cross-Layer / Package Coupling Matrix (DSM Top Flows)\n\n");
        if (dsm.cells().isEmpty()) {
            md.append("No cross-group dependencies observed in this scope.\n");
        } else {
            md.append("- **Ordered Layers (Consumer -> Foundation):** ")
              .append(String.join(" -> ", dsm.orderedGroups())).append("\n");
            md.append("- **Bidirectional Layer Tangles:** ").append(dsm.bidirectionalPairCount())
              .append(" · **Upward Back-Edges:** ").append(dsm.backEdgeCellCount()).append("\n\n");
            md.append("| From Layer/Group | To Layer/Group | Edges | Support Weight | Flags |\n");
            md.append("|---|---|---|---|---|\n");
            for (int i = 0; i < Math.min(15, dsm.cells().size()); i++) {
                CouplingCell c = dsm.cells().get(i);
                List<String> flags = new ArrayList<>();
                if (c.bidirectional()) flags.add("BIDIRECTIONAL_TANGLE");
                if (c.backEdge()) flags.add("UPWARD_BACK_EDGE");
                md.append("| `").append(c.fromGroup()).append("` | `").append(c.toGroup()).append("` | ")
                  .append(c.edgeCount()).append(" | ").append(c.supportWeight()).append(" | ")
                  .append(flags.isEmpty() ? "OK" : String.join(", ", flags)).append(" |\n");
            }
        }
        if (!sdpEdges.isEmpty()) {
            md.append("\n## 5. Edge-Level Stable Dependencies Principle (SDP) Violations\n\n");
            md.append("| Stable Source | $I(\\text{src})$ | Less Stable Target | $I(\\text{dst})$ | $\\Delta I$ |\n");
            md.append("|---|---|---|---|---|\n");
            for (int i = 0; i < Math.min(10, sdpEdges.size()); i++) {
                SdpEdgeViolation v = sdpEdges.get(i);
                md.append("| `").append(v.sourceLabel()).append("` | ").append(v.sourceInstability())
                  .append(" | `").append(v.targetLabel()).append("` | ").append(v.targetInstability())
                  .append(" | +").append(v.delta()).append(" |\n");
            }
        }
        if (!packageMetrics.isEmpty()) {
            md.append("\n## 6. Package Abstractness & Distance from Main Sequence\n\n");
            md.append("| Group | Entities | Abstractness ($A$) | Instability ($I$) | Distance ($D$) | Zone |\n");
            md.append("|---|---|---|---|---|---|\n");
            for (int i = 0; i < Math.min(12, packageMetrics.size()); i++) {
                PackageMetric pm = packageMetrics.get(i);
                md.append("| `").append(pm.group()).append("` | ").append(pm.entityCount())
                  .append(" | ").append(pm.abstractness())
                  .append(" | ").append(pm.instability())
                  .append(" | ").append(pm.distance())
                  .append(" | `").append(pm.zone()).append("` |\n");
            }
        }
        return md.toString();
    }
}
