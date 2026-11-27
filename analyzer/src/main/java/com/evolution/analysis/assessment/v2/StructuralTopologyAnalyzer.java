package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.common.ContentDigest;
import java.math.BigDecimal;
import java.util.*;

/**
 * Deterministic, linear-plus-bitset structural topology analyzer for Architecture Scoring v2.
 *
 * <p>Computes exact iterative Tarjan SCCs, Relative Cyclicity (Type/Package/Module),
 * condensation-DAG batched bitset reachability (Lakos CCD/ACD/NCCD, size-normalized
 * MacCormack Propagation Cost, Mo/Cai/Kazman DRH Decoupling Level), Parnas/Modulith
 * Package Encapsulation, normalized Bunch TurboMQ, Robert C. Martin A/I/D &amp; edge-level
 * SDP violations, DSM levelization back-edges, Inferred Layer Conformance, Mo/Cai Crossing
 * Hubs, Structure101 Fat components, and Vasa et al. Coupling Gini skew.
 *
 * <p>All arithmetic is deterministic ({@link BigDecimal} scale 12 {@code HALF_EVEN} and
 * JLS {@link StrictMath} logarithms) and cooperatively cancellable via {@code checkpoint}.
 */
public final class StructuralTopologyAnalyzer {
    private static final int REACHABILITY_BATCH_WORDS = 16; // 1024 components per bitset pass
    private static final int REACHABILITY_BATCH_BITS = REACHABILITY_BATCH_WORDS * 64;
    private static final int MIN_PKG_CYCLICITY_DENOM = 10;
    private static final int MIN_TYPE_CYCLICITY_DENOM = 20;
    private static final int INTERNAL_FAN_OUT_BUDGET = 10;
    private static final int INTERNAL_FAN_OUT_SPAN = 15;
    private static final int TYPE_FAT_THRESHOLD = 30;
    private static final int TYPE_FAT_SPAN = 45;
    private static final int PACKAGE_FAT_THRESHOLD = 35;
    private static final int PACKAGE_FAT_SPAN = 65;
    private static final int CROSSING_MIN_DEGREE = 4;
    private static final int MIN_GINI_NODES = 8;
    private static final BigDecimal SDP_EDGE_MARGIN = new BigDecimal("0.20");
    private static final BigDecimal MAIN_SEQ_TOLERANCE = new BigDecimal("0.35");
    private static final BigDecimal CONTRACT_RATIO_THRESHOLD = new BigDecimal("0.70");

    public record NodeInfo(
            String identity,
            String label,
            String packageId,
            String packageName,
            String moduleId,
            String moduleName,
            String typeKind,
            boolean abstractType,
            int memberCount,
            Set<String> annotations,
            List<ContentDigest> evidence) {
        public NodeInfo {
            Objects.requireNonNull(identity);
            label = label == null || label.isBlank() ? identity : label;
            packageId = packageId == null ? "" : packageId;
            packageName = packageName == null ? "" : packageName;
            moduleId = moduleId == null ? "" : moduleId;
            moduleName = moduleName == null ? "" : moduleName;
            typeKind = typeKind == null || typeKind.isBlank() ? "CLASS" : typeKind;
            memberCount = Math.max(0, memberCount);
            annotations = Collections.unmodifiableSet(new TreeSet<>(annotations == null ? Set.of() : annotations));
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }
    }

    public record EdgeInfo(
            String identity,
            String source,
            String target,
            List<ContentDigest> evidence) {
        public EdgeInfo {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(source);
            Objects.requireNonNull(target);
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }
    }

    public record StructuralGraph(List<NodeInfo> nodes, List<EdgeInfo> edges) {
        public StructuralGraph {
            nodes = List.copyOf(Objects.requireNonNull(nodes));
            edges = List.copyOf(Objects.requireNonNull(edges));
        }
    }

    public record Metrics(
            int typeCount,
            int packageCount,
            int moduleCount,
            int internalTypeEdgeCount,
            int interPackageEdgeCount,
            // D1: Acyclicity
            Optional<BigDecimal> packageRelativeCyclicity,
            int cyclicPackageSccCount,
            List<ArchitectureAssessmentV2.EvidenceWitness> packageCycleWitnesses,
            Optional<BigDecimal> typeRelativeCyclicity,
            int cyclicTypeSccCount,
            List<ArchitectureAssessmentV2.EvidenceWitness> typeCycleWitnesses,
            Optional<BigDecimal> moduleRelativeCyclicity,
            int cyclicModuleSccCount,
            List<ArchitectureAssessmentV2.EvidenceWitness> moduleCycleWitnesses,
            Optional<BigDecimal> tangleEdgeRatio,
            // D2: Modularity
            Optional<BigDecimal> decouplingLevel,
            List<ArchitectureAssessmentV2.EvidenceWitness> decouplingWitnesses,
            Optional<BigDecimal> encapsulationRatio,
            List<ArchitectureAssessmentV2.EvidenceWitness> encapsulationWitnesses,
            Optional<BigDecimal> packageTurboMq,
            List<ArchitectureAssessmentV2.EvidenceWitness> turboMqWitnesses,
            // D3: Coupling & Propagation
            Optional<BigDecimal> typeNccd,
            Optional<BigDecimal> packageNccd,
            Optional<BigDecimal> blendedNccdRaw,
            Optional<BigDecimal> normalizedPropagationCost,
            Optional<BigDecimal> rawPropagationCost,
            Optional<BigDecimal> excessInternalFanOut,
            List<ArchitectureAssessmentV2.EvidenceWitness> highFanOutWitnesses,
            // D4: Hierarchy & Direction
            Optional<BigDecimal> sdpViolationRatio,
            int sdpViolationCount,
            List<ArchitectureAssessmentV2.EvidenceWitness> sdpWitnesses,
            Optional<BigDecimal> mainSequenceExcessDistance,
            List<ArchitectureAssessmentV2.EvidenceWitness> mainSequenceWitnesses,
            Optional<BigDecimal> dsmBackEdgeRatio,
            int dsmBackEdgeCount,
            List<ArchitectureAssessmentV2.EvidenceWitness> dsmBackEdgeWitnesses,
            InferredLayerClassifier.LayerAssessment inferredLayers,
            // D5: Balance & Hotspots
            Optional<BigDecimal> crossingHubDensity,
            int crossingHubCount,
            List<ArchitectureAssessmentV2.EvidenceWitness> crossingHubWitnesses,
            Optional<BigDecimal> fatComponentRatio,
            List<ArchitectureAssessmentV2.EvidenceWitness> fatComponentWitnesses,
            Optional<BigDecimal> couplingGini) {}

    public record SccResult(int[] compOf, List<int[]> components) {}

    /** Runs the complete deterministic structural topology analysis over one graph. */
    public static Metrics analyze(StructuralGraph graph, int maxWitnesses, Runnable checkpoint) {
        Objects.requireNonNull(graph, "graph");
        int limit = Math.max(1, Math.min(25, maxWitnesses));
        checkpoint.run();

        // Canonical sorted node ordering
        var sortedNodes = new ArrayList<>(graph.nodes());
        sortedNodes.sort(Comparator.comparing(NodeInfo::identity));
        int n = sortedNodes.size();
        var nodeIndex = new HashMap<String, Integer>(Math.max(16, n * 2));
        for (int i = 0; i < n; i++) {
            nodeIndex.put(sortedNodes.get(i).identity(), i);
        }

        // Deduplicated internal directed type adjacency
        var typeAdjSets = new ArrayList<TreeSet<Integer>>(n);
        for (int i = 0; i < n; i++) typeAdjSets.add(new TreeSet<>());
        boolean[] typeSelfLoop = new boolean[n];
        int selfLoopCount = 0;
        int[] fanIn = new int[n];
        int[] fanOut = new int[n];
        var internalEdges = new ArrayList<EdgeInfo>();
        var seenPairs = new HashSet<Long>();

        for (var e : graph.edges()) {
            Integer u = nodeIndex.get(e.source());
            Integer v = nodeIndex.get(e.target());
            if (u == null || v == null) continue;
            if (u.intValue() == v.intValue()) {
                if (!typeSelfLoop[u]) {
                    typeSelfLoop[u] = true;
                    selfLoopCount++;
                }
                continue;
            }
            long pairKey = (((long) u) << 32) | (v & 0xffffffffL);
            if (seenPairs.add(pairKey)) {
                typeAdjSets.get(u).add(v);
                fanOut[u]++;
                fanIn[v]++;
                internalEdges.add(e);
            }
        }
        var typeAdj = new ArrayList<int[]>(n);
        for (int i = 0; i < n; i++) {
            var set = typeAdjSets.get(i);
            int[] arr = new int[set.size()];
            int idx = 0;
            for (int v : set) arr[idx++] = v;
            typeAdj.add(arr);
        }
        checkpoint.run();

        // Group into packages & modules
        var pkgMembers = new TreeMap<String, List<Integer>>();
        var pkgNames = new HashMap<String, String>();
        var modMembers = new TreeMap<String, List<Integer>>();
        var modNames = new HashMap<String, String>();
        var typeToPkgKey = new String[n];
        var typeToModKey = new String[n];

        for (int i = 0; i < n; i++) {
            var node = sortedNodes.get(i);
            String pkgKey = !node.packageId().isEmpty() ? node.packageId()
                    : !node.packageName().isEmpty() ? "pkg:" + node.packageName() : "";
            if (!pkgKey.isEmpty()) {
                typeToPkgKey[i] = pkgKey;
                pkgMembers.computeIfAbsent(pkgKey, k -> new ArrayList<>()).add(i);
                pkgNames.putIfAbsent(pkgKey, node.packageName().isEmpty() ? pkgKey : node.packageName());
            }
            String modKey = !node.moduleId().isEmpty() ? node.moduleId()
                    : !node.moduleName().isEmpty() ? "mod:" + node.moduleName() : "";
            if (!modKey.isEmpty()) {
                typeToModKey[i] = modKey;
                modMembers.computeIfAbsent(modKey, k -> new ArrayList<>()).add(i);
                modNames.putIfAbsent(modKey, node.moduleName().isEmpty() ? modKey : node.moduleName());
            }
        }

        // Build Package & Module induced graphs
        var pkgList = List.copyOf(pkgMembers.keySet());
        var pkgGraph = buildGroupGraph(pkgList, pkgNames, typeToPkgKey, internalEdges, nodeIndex);
        var modList = List.copyOf(modMembers.keySet());
        var modGraph = buildGroupGraph(modList, modNames, typeToModKey, internalEdges, nodeIndex);
        checkpoint.run();

        // ─── D1: Exact Iterative Tarjan SCC across Type, Package, Module ───
        SccResult typeScc = tarjanScc(n, typeAdj);
        var typeCycleInfo = evaluateCyclicity(
                n, MIN_TYPE_CYCLICITY_DENOM, typeScc, typeSelfLoop, typeAdj,
                idx -> sortedNodes.get(idx).identity(),
                idx -> sortedNodes.get(idx).label(),
                idx -> sortedNodes.get(idx).evidence(),
                "TYPE_CYCLE", limit);

        SccResult pkgScc = tarjanScc(pkgList.size(), pkgGraph.adj);
        var pkgCycleInfo = evaluateCyclicity(
                pkgList.size(), MIN_PKG_CYCLICITY_DENOM, pkgScc, new boolean[pkgList.size()], pkgGraph.adj,
                pkgList::get,
                idx -> pkgNames.getOrDefault(pkgList.get(idx), pkgList.get(idx)),
                idx -> List.of(),
                "PACKAGE_CYCLE", limit);

        SccResult modScc = tarjanScc(modList.size(), modGraph.adj);
        var modCycleInfo = evaluateCyclicity(
                modList.size(), 1, modScc, new boolean[modList.size()], modGraph.adj,
                modList::get,
                idx -> modNames.getOrDefault(modList.get(idx), modList.get(idx)),
                idx -> List.of(),
                "MODULE_CYCLE", limit);

        // Cyclic edge tangle ratio (prefer package level when inter-package edges exist)
        int totalTypeEdges = internalEdges.size() + selfLoopCount;
        Optional<BigDecimal> tangleEdgeRatio;
        if (pkgGraph.edgeCount > 0) {
            tangleEdgeRatio = Optional.of(CalibrationCurve.divide(pkgCycleInfo.intraCyclicEdges, pkgGraph.edgeCount));
        } else if (totalTypeEdges > 0) {
            tangleEdgeRatio = Optional.of(CalibrationCurve.divide(typeCycleInfo.intraCyclicEdges, totalTypeEdges));
        } else {
            tangleEdgeRatio = Optional.empty();
        }
        checkpoint.run();

        // ─── Condensation-DAG Exact Bitset Reachability (CCD, NCCD, PC, DRH DL) ───
        ReachabilitySummary typeReach = computeReachability(n, typeAdj, typeScc, checkpoint);
        ReachabilitySummary pkgReach = pkgList.size() >= 2
                ? computeReachability(pkgList.size(), pkgGraph.adj, pkgScc, checkpoint)
                : null;

        // D2.1: Exact Mo/Cai/Kazman DRH Decoupling Level
        Optional<BigDecimal> decouplingLevel = Optional.empty();
        var decouplingWitnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        if (n >= 2) {
            BigDecimal dl = BigDecimal.ZERO;
            BigDecimal nDec = BigDecimal.valueOf(n);
            record DlDrag(int compId, int size, int dependents, BigDecimal drag) {}
            var drags = new ArrayList<DlDrag>();
            for (int c = 0; c < typeScc.components.size(); c++) {
                int s = typeScc.components.get(c).length;
                BigDecimal sizeFactor = s <= 5
                        ? CalibrationCurve.divide(s, n)
                        : CalibrationCurve.divide(BigDecimal.valueOf(s), nDec.multiply(CalibrationCurve.strictLog5(s)));
                int depNodes = typeReach.incomingDependentsByComp[c];
                if (s > 1) depNodes = Math.min(n - 1, depNodes + (s - 1));
                BigDecimal decoupledFactor = BigDecimal.ONE.subtract(CalibrationCurve.divide(depNodes, n));
                BigDecimal contribution = sizeFactor.multiply(decoupledFactor);
                dl = dl.add(contribution);
                BigDecimal idealContribution = CalibrationCurve.divide(s, n);
                BigDecimal drag = idealContribution.subtract(contribution);
                if (drag.compareTo(new BigDecimal("0.01")) > 0) {
                    drags.add(new DlDrag(c, s, depNodes, drag));
                }
            }
            decouplingLevel = Optional.of(CalibrationCurve.clamp(CalibrationCurve.round(dl, CalibrationCurve.SCALE), BigDecimal.ZERO, BigDecimal.ONE));
            drags.sort(Comparator.comparing(DlDrag::drag).reversed().thenComparingInt(DlDrag::compId));
            for (int i = 0; i < Math.min(limit, drags.size()); i++) {
                var d = drags.get(i);
                int rep = typeScc.components.get(d.compId)[0];
                String label = d.size > 1
                        ? sortedNodes.get(rep).label() + " (+" + (d.size - 1) + " in SCC)"
                        : sortedNodes.get(rep).label();
                decouplingWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                        "DRH_COUPLED_MODULE", sortedNodes.get(rep).identity(), label,
                        "Module size=" + d.size + ", transitive dependents=" + d.dependents + "/" + n,
                        sortedNodes.get(rep).evidence()));
            }
        }

        // D2.2 & D2.3: Package Encapsulation (Parnas/Modulith) & Normalized TurboMQ
        var encapAndMq = computePackageModularity(pkgList, pkgNames, pkgMembers, sortedNodes, typeToPkgKey, internalEdges, nodeIndex, limit);
        checkpoint.run();

        // D3: Lakos NCCD, Normalized Propagation Cost, Excess Internal Fan-Out
        Optional<BigDecimal> typeNccd = Optional.empty();
        Optional<BigDecimal> pkgNccd = Optional.empty();
        Optional<BigDecimal> blendedNccd = Optional.empty();
        Optional<BigDecimal> rawPc = Optional.empty();
        Optional<BigDecimal> normPc = Optional.empty();
        if (n >= 2) {
            BigDecimal ccdBal = CalibrationCurve.balancedBinaryTreeCcd(n);
            BigDecimal tNccd = CalibrationCurve.divide(BigDecimal.valueOf(typeReach.ccd), ccdBal);
            typeNccd = Optional.of(tNccd);
            BigDecimal nSq = BigDecimal.valueOf((long) n * (long) n);
            BigDecimal pc = CalibrationCurve.divide(BigDecimal.valueOf(typeReach.ccd), nSq);
            rawPc = Optional.of(pc);
            BigDecimal pcTree = CalibrationCurve.divide(ccdBal, nSq);
            BigDecimal denom = BigDecimal.ONE.subtract(pcTree);
            BigDecimal pcNorm = denom.signum() <= 0
                    ? BigDecimal.ZERO
                    : CalibrationCurve.clamp(
                            CalibrationCurve.divide(pc.subtract(pcTree).max(BigDecimal.ZERO), denom),
                            BigDecimal.ZERO, BigDecimal.ONE);
            normPc = Optional.of(pcNorm);
            if (pkgList.size() >= 4 && pkgReach != null) {
                BigDecimal pBal = CalibrationCurve.balancedBinaryTreeCcd(pkgList.size());
                BigDecimal pNccd = CalibrationCurve.divide(BigDecimal.valueOf(pkgReach.ccd), pBal);
                pkgNccd = Optional.of(pNccd);
                blendedNccd = Optional.of(CalibrationCurve.divide(tNccd.add(pNccd), BigDecimal.valueOf(2)));
            } else {
                blendedNccd = typeNccd;
            }
        }

        Optional<BigDecimal> excessInternalFanOut = Optional.empty();
        var highFanOutWitnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        if (n >= 2) {
            BigDecimal sum = BigDecimal.ZERO;
            record FanOutRow(int idx, int ce) {}
            var offenders = new ArrayList<FanOutRow>();
            for (int i = 0; i < n; i++) {
                if (fanOut[i] > INTERNAL_FAN_OUT_BUDGET) {
                    BigDecimal ex = CalibrationCurve.divide(fanOut[i] - INTERNAL_FAN_OUT_BUDGET, INTERNAL_FAN_OUT_SPAN).min(BigDecimal.ONE);
                    sum = sum.add(ex);
                    offenders.add(new FanOutRow(i, fanOut[i]));
                }
            }
            excessInternalFanOut = Optional.of(CalibrationCurve.divide(sum, BigDecimal.valueOf(n)));
            offenders.sort(Comparator.comparingInt(FanOutRow::ce).reversed()
                    .thenComparing(r -> sortedNodes.get(r.idx).identity()));
            for (int i = 0; i < Math.min(limit, offenders.size()); i++) {
                var r = offenders.get(i);
                var node = sortedNodes.get(r.idx);
                highFanOutWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                        "HIGH_EFFERENT_COUPLING", node.identity(), node.label(),
                        "Internal fan-out=" + r.ce + " (budget <= " + INTERNAL_FAN_OUT_BUDGET + ")",
                        node.evidence()));
            }
        }
        checkpoint.run();

        // ─── D4: SDP, Main Sequence D_excess, DSM Back-Edges, Inferred Layering ───
        var sdpInfo = computeSdp(pkgList, pkgNames, pkgGraph, sortedNodes, fanIn, fanOut, internalEdges, nodeIndex, limit);
        var msInfo = computeMainSequence(pkgList, pkgNames, pkgMembers, pkgGraph, sortedNodes, limit);
        var dsmInfo = computeDsmBackEdges(pkgList, pkgNames, pkgGraph, pkgScc, limit);

        var typeToPkgName = new TreeMap<String, String>();
        var typeAnnotations = new HashMap<String, Set<String>>();
        for (var node : sortedNodes) {
            typeToPkgName.put(node.identity(), node.packageName());
            if (!node.annotations().isEmpty()) {
                typeAnnotations.put(node.identity(), node.annotations());
            }
        }
        var directedForLayers = new ArrayList<InferredLayerClassifier.DirectedTypeEdge>(internalEdges.size());
        for (var e : internalEdges) {
            var src = sortedNodes.get(nodeIndex.get(e.source()));
            var dst = sortedNodes.get(nodeIndex.get(e.target()));
            directedForLayers.add(new InferredLayerClassifier.DirectedTypeEdge(
                    e.identity(), e.source(), src.label(), e.target(), dst.label(), e.evidence()));
        }
        var inferredLayers = InferredLayerClassifier.evaluate(typeToPkgName, typeAnnotations, directedForLayers, limit);
        checkpoint.run();

        // ─── D5: Crossing Hubs, Fat Components, Coupling Gini ───
        var hubInfo = computeCrossingHubs(sortedNodes, fanIn, fanOut, limit);
        var fatInfo = computeFatComponents(sortedNodes, pkgList, pkgNames, pkgMembers, limit);
        Optional<BigDecimal> gini = computeGini(n, fanIn, fanOut);

        return new Metrics(
                n,
                pkgList.size(),
                modList.size(),
                internalEdges.size(),
                pkgGraph.edgeCount,
                pkgCycleInfo.relativeCyclicity,
                pkgCycleInfo.cyclicSccCount,
                pkgCycleInfo.witnesses,
                typeCycleInfo.relativeCyclicity,
                typeCycleInfo.cyclicSccCount,
                typeCycleInfo.witnesses,
                modCycleInfo.relativeCyclicity,
                modCycleInfo.cyclicSccCount,
                modCycleInfo.witnesses,
                tangleEdgeRatio,
                decouplingLevel,
                decouplingWitnesses,
                encapAndMq.encapsulationRatio,
                encapAndMq.encapWitnesses,
                encapAndMq.turboMq,
                encapAndMq.mqWitnesses,
                typeNccd,
                pkgNccd,
                blendedNccd,
                normPc,
                rawPc,
                excessInternalFanOut,
                highFanOutWitnesses,
                sdpInfo.ratio,
                sdpInfo.count,
                sdpInfo.witnesses,
                msInfo.excessDistance,
                msInfo.witnesses,
                dsmInfo.ratio,
                dsmInfo.count,
                dsmInfo.witnesses,
                inferredLayers,
                hubInfo.density,
                hubInfo.count,
                hubInfo.witnesses,
                fatInfo.ratio,
                fatInfo.witnesses,
                gini);
    }

    private record GroupGraph(List<int[]> adj, int[] fanIn, int[] fanOut, int edgeCount, Map<Long, Integer> edgeWeights) {}

    private static GroupGraph buildGroupGraph(
            List<String> groups,
            Map<String, String> names,
            String[] nodeToGroup,
            List<EdgeInfo> typeEdges,
            Map<String, Integer> nodeIndex) {
        int g = groups.size();
        var gIdx = new HashMap<String, Integer>(Math.max(16, g * 2));
        for (int i = 0; i < g; i++) gIdx.put(groups.get(i), i);
        var adjSets = new ArrayList<TreeSet<Integer>>(g);
        for (int i = 0; i < g; i++) adjSets.add(new TreeSet<>());
        int[] inDeg = new int[g];
        int[] outDeg = new int[g];
        var weights = new HashMap<Long, Integer>();
        int distinctEdges = 0;
        for (var e : typeEdges) {
            int u = nodeIndex.get(e.source());
            int v = nodeIndex.get(e.target());
            String gu = nodeToGroup[u];
            String gv = nodeToGroup[v];
            if (gu == null || gv == null || gu.equals(gv)) continue;
            int gi = gIdx.get(gu);
            int gj = gIdx.get(gv);
            long key = (((long) gi) << 32) | (gj & 0xffffffffL);
            weights.merge(key, 1, Integer::sum);
            if (adjSets.get(gi).add(gj)) {
                outDeg[gi]++;
                inDeg[gj]++;
                distinctEdges++;
            }
        }
        var adj = new ArrayList<int[]>(g);
        for (int i = 0; i < g; i++) {
            var set = adjSets.get(i);
            int[] arr = new int[set.size()];
            int k = 0;
            for (int v : set) arr[k++] = v;
            adj.add(arr);
        }
        return new GroupGraph(adj, inDeg, outDeg, distinctEdges, weights);
    }

    /** Exact iterative Tarjan SCC — zero recursion depth risk on large graphs. */
    public static SccResult tarjanScc(int n, List<int[]> adj) {
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
        var components = new ArrayList<int[]>();

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
                int[] neighbors = adj.get(u);
                int ei = callEdgeIdx[depth];
                if (ei < neighbors.length) {
                    callEdgeIdx[depth] = ei + 1;
                    int v = neighbors[ei];
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

    private record CycleSummary(
            Optional<BigDecimal> relativeCyclicity,
            int cyclicSccCount,
            int intraCyclicEdges,
            List<ArchitectureAssessmentV2.EvidenceWitness> witnesses) {}

    private static CycleSummary evaluateCyclicity(
            int n,
            int minDenom,
            SccResult scc,
            boolean[] selfLoops,
            List<int[]> adj,
            java.util.function.IntFunction<String> idOf,
            java.util.function.IntFunction<String> labelOf,
            java.util.function.IntFunction<List<ContentDigest>> evidenceOf,
            String witnessKind,
            int maxWitnesses) {
        if (n < 2) {
            return new CycleSummary(Optional.empty(), 0, 0, List.of());
        }
        var cyclic = new ArrayList<int[]>();
        long sqSum = 0;
        for (int[] comp : scc.components) {
            if (comp.length > 1 || (comp.length == 1 && selfLoops[comp[0]])) {
                cyclic.add(comp);
                sqSum += (long) comp.length * (long) comp.length;
            }
        }
        int intraCyclicEdges = 0;
        for (int u = 0; u < n; u++) {
            int cu = scc.compOf[u];
            boolean cyc = scc.components.get(cu).length > 1 || selfLoops[u];
            if (!cyc) continue;
            if (selfLoops[u]) intraCyclicEdges++;
            for (int v : adj.get(u)) {
                if (scc.compOf[v] == cu) intraCyclicEdges++;
            }
        }
        int denom = Math.max(n, minDenom);
        BigDecimal rc = sqSum == 0
                ? BigDecimal.ZERO
                : CalibrationCurve.divide(CalibrationCurve.sqrt(BigDecimal.valueOf(sqSum)), BigDecimal.valueOf(denom));
        cyclic.sort((a, b) -> {
            int cmp = Integer.compare(b.length, a.length);
            return cmp != 0 ? cmp : idOf.apply(a[0]).compareTo(idOf.apply(b[0]));
        });
        var witnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        for (int i = 0; i < Math.min(maxWitnesses, cyclic.size()); i++) {
            int[] members = cyclic.get(i);
            var preview = new ArrayList<String>();
            for (int k = 0; k < Math.min(5, members.length); k++) preview.add(labelOf.apply(members[k]));
            String suffix = members.length > 5 ? " (+" + (members.length - 5) + " more)" : "";
            witnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                    witnessKind,
                    idOf.apply(members[0]),
                    String.join(" <-> ", preview) + suffix,
                    "Strongly connected cycle of " + members.length + " components",
                    evidenceOf.apply(members[0])));
        }
        return new CycleSummary(Optional.of(rc), cyclic.size(), intraCyclicEdges, witnesses);
    }

    private record ReachabilitySummary(long ccd, int[] incomingDependentsByComp, int[] outgoingReachByComp) {}

    /**
     * Exact batched bitset transitive reachability on the SCC condensation DAG.
     * Computes exact Lakos CCD (including self) and exact incoming transitive dependents per SCC.
     */
    private static ReachabilitySummary computeReachability(int n, List<int[]> adj, SccResult scc, Runnable checkpoint) {
        int cCount = scc.components.size();
        if (cCount == 0) return new ReachabilitySummary(0, new int[0], new int[0]);

        var dagOut = new ArrayList<int[]>(cCount);
        var dagIn = new ArrayList<List<Integer>>(cCount);
        int[] inDegree = new int[cCount];
        for (int c = 0; c < cCount; c++) dagIn.add(new ArrayList<>());

        int[] stamp = new int[cCount];
        for (int c = 0; c < cCount; c++) {
            var targets = new ArrayList<Integer>();
            for (int u : scc.components.get(c)) {
                for (int v : adj.get(u)) {
                    int tc = scc.compOf[v];
                    if (tc != c && stamp[tc] != c + 1) {
                        stamp[tc] = c + 1;
                        targets.add(tc);
                        dagIn.get(tc).add(c);
                        inDegree[tc]++;
                    }
                }
            }
            int[] arr = new int[targets.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = targets.get(i);
            dagOut.add(arr);
        }

        // Deterministic Kahn topological sort
        int[] topo = new int[cCount];
        int[] remIn = inDegree.clone();
        var queue = new PriorityQueue<Integer>();
        for (int c = 0; c < cCount; c++) if (remIn[c] == 0) queue.add(c);
        int tPos = 0;
        while (!queue.isEmpty()) {
            int u = queue.poll();
            topo[tPos++] = u;
            for (int v : dagOut.get(u)) {
                if (--remIn[v] == 0) queue.add(v);
            }
        }

        int[] compSize = new int[cCount];
        for (int c = 0; c < cCount; c++) compSize[c] = scc.components.get(c).length;

        int[] outReach = new int[cCount];
        int[] inDeps = new int[cCount];

        // Batched outgoing reachability (reverse topo) + incoming dependents (forward topo)
        long[][] bits = new long[cCount][REACHABILITY_BATCH_WORDS];
        for (int base = 0; base < cCount; base += REACHABILITY_BATCH_BITS) {
            checkpoint.run();
            int end = Math.min(cCount, base + REACHABILITY_BATCH_BITS);
            int words = (end - base + 63) / 64;

            // Outgoing pass (target components in [base, end))
            for (int c = 0; c < cCount; c++) Arrays.fill(bits[c], 0, words, 0L);
            for (int idx = cCount - 1; idx >= 0; idx--) {
                int c = topo[idx];
                if (c >= base && c < end) {
                    int bit = c - base;
                    bits[c][bit >>> 6] |= (1L << (bit & 63));
                }
                for (int next : dagOut.get(c)) {
                    for (int w = 0; w < words; w++) bits[c][w] |= bits[next][w];
                }
                int sum = 0;
                for (int w = 0; w < words; w++) {
                    long word = bits[c][w];
                    while (word != 0L) {
                        int tz = Long.numberOfTrailingZeros(word);
                        sum += compSize[base + (w << 6) + tz];
                        word &= word - 1;
                    }
                }
                outReach[c] += sum;
            }

            // Incoming pass (source components in [base, end), excluding self)
            for (int c = 0; c < cCount; c++) Arrays.fill(bits[c], 0, words, 0L);
            for (int idx = 0; idx < cCount; idx++) {
                int c = topo[idx];
                for (int prev : dagIn.get(c)) {
                    for (int w = 0; w < words; w++) bits[c][w] |= bits[prev][w];
                    if (prev >= base && prev < end) {
                        int bit = prev - base;
                        bits[c][bit >>> 6] |= (1L << (bit & 63));
                    }
                }
                int sum = 0;
                for (int w = 0; w < words; w++) {
                    long word = bits[c][w];
                    while (word != 0L) {
                        int tz = Long.numberOfTrailingZeros(word);
                        sum += compSize[base + (w << 6) + tz];
                        word &= word - 1;
                    }
                }
                inDeps[c] += sum;
            }
        }

        long ccd = 0;
        for (int c = 0; c < cCount; c++) {
            ccd += (long) compSize[c] * (long) outReach[c];
        }
        return new ReachabilitySummary(ccd, inDeps, outReach);
    }

    private record ModularityInfo(
            Optional<BigDecimal> encapsulationRatio,
            List<ArchitectureAssessmentV2.EvidenceWitness> encapWitnesses,
            Optional<BigDecimal> turboMq,
            List<ArchitectureAssessmentV2.EvidenceWitness> mqWitnesses) {}

    private static ModularityInfo computePackageModularity(
            List<String> pkgList,
            Map<String, String> pkgNames,
            Map<String, List<Integer>> pkgMembers,
            List<NodeInfo> nodes,
            String[] typeToPkgKey,
            List<EdgeInfo> internalEdges,
            Map<String, Integer> nodeIndex,
            int limit) {
        if (pkgList.size() < 2) {
            return new ModularityInfo(Optional.empty(), List.of(), Optional.empty(), List.of());
        }
        var mu = new HashMap<String, Integer>();
        var epsIn = new HashMap<String, Integer>();
        var epsOut = new HashMap<String, Integer>();
        var externallyReferencedTypes = new HashMap<String, Set<Integer>>();

        for (var e : internalEdges) {
            int u = nodeIndex.get(e.source());
            int v = nodeIndex.get(e.target());
            String pu = typeToPkgKey[u];
            String pv = typeToPkgKey[v];
            if (pu == null || pv == null) continue;
            if (pu.equals(pv)) {
                mu.merge(pu, 1, Integer::sum);
            } else {
                epsOut.merge(pu, 1, Integer::sum);
                epsIn.merge(pv, 1, Integer::sum);
                externallyReferencedTypes.computeIfAbsent(pv, k -> new HashSet<>()).add(v);
            }
        }

        // Encapsulation over implementation packages (size >= 2, excluding pure contract/value packages)
        BigDecimal encapWeightedSum = BigDecimal.ZERO;
        long encapTypeTotal = 0;
        record PkgScoreRow(String pkgId, String pkgName, int size, BigDecimal ratio, String detail) {}
        var encapLow = new ArrayList<PkgScoreRow>();

        BigDecimal mqSum = BigDecimal.ZERO;
        int mqCount = 0;
        var mqLow = new ArrayList<PkgScoreRow>();

        for (String pkgId : pkgList) {
            var members = pkgMembers.get(pkgId);
            int size = members.size();
            if (size < 2) continue;
            String pkgName = pkgNames.getOrDefault(pkgId, pkgId);
            int contractOrDataCount = 0;
            for (int idx : members) {
                var node = nodes.get(idx);
                if (node.abstractType()
                        || Set.of("INTERFACE", "ANNOTATION", "RECORD", "ENUM").contains(node.typeKind())) {
                    contractOrDataCount++;
                }
            }
            int inCount = epsIn.getOrDefault(pkgId, 0);
            int outCount = epsOut.getOrDefault(pkgId, 0);
            int intraCount = mu.getOrDefault(pkgId, 0);
            BigDecimal contractRatio = CalibrationCurve.divide(contractOrDataCount, size);
            boolean pureContractOrLeafModel = contractRatio.compareTo(CONTRACT_RATIO_THRESHOLD) >= 0 || outCount == 0;

            if (!pureContractOrLeafModel) {
                int exposed = externallyReferencedTypes.getOrDefault(pkgId, Set.of()).size();
                BigDecimal encap = BigDecimal.ONE.subtract(CalibrationCurve.divide(exposed, size));
                encapWeightedSum = encapWeightedSum.add(encap.multiply(BigDecimal.valueOf(size)));
                encapTypeTotal += size;
                if (exposed > 0) {
                    encapLow.add(new PkgScoreRow(pkgId, pkgName, size, encap,
                            exposed + "/" + size + " types referenced externally (encapsulation="
                                    + CalibrationCurve.roundDisplay(encap.multiply(CalibrationCurve.HUNDRED)) + "%)"));
                }
            }

            // TurboMQ: active non-trivial packages with both internal/external interactions (exclude pure entry/leaf layers)
            int denom = 2 * intraCount + inCount + outCount;
            if (denom > 0 && inCount > 0 && outCount > 0) {
                BigDecimal cf = CalibrationCurve.divide(2L * intraCount, denom);
                mqSum = mqSum.add(cf);
                mqCount++;
                mqLow.add(new PkgScoreRow(pkgId, pkgName, size, cf,
                        "TurboMQ CF=" + CalibrationCurve.roundDisplay(cf) + " (intra=" + intraCount + ", in=" + inCount + ", out=" + outCount + ")"));
            }
        }

        Optional<BigDecimal> encapRatio = encapTypeTotal == 0
                ? Optional.empty()
                : Optional.of(CalibrationCurve.divide(encapWeightedSum, BigDecimal.valueOf(encapTypeTotal)));
        Optional<BigDecimal> turboMq = mqCount == 0
                ? Optional.empty()
                : Optional.of(CalibrationCurve.divide(mqSum, BigDecimal.valueOf(mqCount)));

        encapLow.sort(Comparator.comparing(PkgScoreRow::ratio).thenComparing(PkgScoreRow::pkgId));
        var encapWitnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        for (int i = 0; i < Math.min(limit, encapLow.size()); i++) {
            var r = encapLow.get(i);
            encapWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness("LOW_ENCAPSULATION_PACKAGE", r.pkgId, r.pkgName, r.detail, List.of()));
        }

        mqLow.sort(Comparator.comparing(PkgScoreRow::ratio).thenComparing(PkgScoreRow::pkgId));
        var mqWitnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        for (int i = 0; i < Math.min(limit, mqLow.size()); i++) {
            var r = mqLow.get(i);
            mqWitnesses.add(new ArchitectureAssessmentV2.EvidenceWitness("LOW_COHESION_PACKAGE", r.pkgId, r.pkgName, r.detail, List.of()));
        }
        return new ModularityInfo(encapRatio, encapWitnesses, turboMq, mqWitnesses);
    }

    private record SdpInfo(Optional<BigDecimal> ratio, int count, List<ArchitectureAssessmentV2.EvidenceWitness> witnesses) {}

    private static SdpInfo computeSdp(
            List<String> pkgList,
            Map<String, String> pkgNames,
            GroupGraph pkgGraph,
            List<NodeInfo> nodes,
            int[] typeFanIn,
            int[] typeFanOut,
            List<EdgeInfo> typeEdges,
            Map<String, Integer> nodeIndex,
            int limit) {
        record Viol(String id, String label, BigDecimal delta, String detail, List<ContentDigest> ev) {}
        var viols = new ArrayList<Viol>();
        int totalEdges;

        if (pkgList.size() >= 2 && pkgGraph.edgeCount > 0) {
            totalEdges = pkgGraph.edgeCount;
            BigDecimal[] inst = new BigDecimal[pkgList.size()];
            for (int i = 0; i < pkgList.size(); i++) {
                int ca = pkgGraph.fanIn[i], ce = pkgGraph.fanOut[i];
                inst[i] = (ca + ce == 0) ? BigDecimal.ZERO : CalibrationCurve.divide(ce, ca + ce);
            }
            for (int u = 0; u < pkgList.size(); u++) {
                if (pkgGraph.fanIn[u] < 1) continue;
                for (int v : pkgGraph.adj.get(u)) {
                    if (pkgGraph.fanOut[v] < 1) continue;
                    BigDecimal delta = inst[v].subtract(inst[u]);
                    if (delta.compareTo(SDP_EDGE_MARGIN) > 0) {
                        String uName = pkgNames.getOrDefault(pkgList.get(u), pkgList.get(u));
                        String vName = pkgNames.getOrDefault(pkgList.get(v), pkgList.get(v));
                        viols.add(new Viol(pkgList.get(u) + "->" + pkgList.get(v), uName + " -> " + vName, delta,
                                "Stable package (I=" + CalibrationCurve.roundDisplay(inst[u])
                                        + ") depends on unstable package (I=" + CalibrationCurve.roundDisplay(inst[v]) + ")",
                                List.of()));
                    }
                }
            }
        } else if (!typeEdges.isEmpty()) {
            totalEdges = typeEdges.size();
            int n = nodes.size();
            BigDecimal[] inst = new BigDecimal[n];
            for (int i = 0; i < n; i++) {
                int ca = typeFanIn[i], ce = typeFanOut[i];
                inst[i] = (ca + ce == 0) ? BigDecimal.ZERO : CalibrationCurve.divide(ce, ca + ce);
            }
            for (var e : typeEdges) {
                int u = nodeIndex.get(e.source());
                int v = nodeIndex.get(e.target());
                if (typeFanIn[u] < 1 || typeFanOut[v] < 1) continue;
                BigDecimal delta = inst[v].subtract(inst[u]);
                if (delta.compareTo(SDP_EDGE_MARGIN) > 0) {
                    viols.add(new Viol(e.identity(), nodes.get(u).label() + " -> " + nodes.get(v).label(), delta,
                            "I_source=" + CalibrationCurve.roundDisplay(inst[u]) + " -> I_target=" + CalibrationCurve.roundDisplay(inst[v]),
                            e.evidence()));
                }
            }
        } else {
            return new SdpInfo(Optional.empty(), 0, List.of());
        }

        viols.sort(Comparator.comparing(Viol::delta).reversed().thenComparing(Viol::id));
        var witnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        for (int i = 0; i < Math.min(limit, viols.size()); i++) {
            var v = viols.get(i);
            witnesses.add(new ArchitectureAssessmentV2.EvidenceWitness("SDP_VIOLATION", v.id, v.label, v.detail, v.ev));
        }
        return new SdpInfo(Optional.of(CalibrationCurve.divide(viols.size(), totalEdges)), viols.size(), witnesses);
    }

    private record MainSeqInfo(Optional<BigDecimal> excessDistance, List<ArchitectureAssessmentV2.EvidenceWitness> witnesses) {}

    private static MainSeqInfo computeMainSequence(
            List<String> pkgList,
            Map<String, String> pkgNames,
            Map<String, List<Integer>> pkgMembers,
            GroupGraph pkgGraph,
            List<NodeInfo> nodes,
            int limit) {
        if (pkgList.size() < 2) return new MainSeqInfo(Optional.empty(), List.of());
        BigDecimal weightedExcess = BigDecimal.ZERO;
        long totalTypes = 0;
        record Row(String pkgId, String pkgName, BigDecimal d, BigDecimal excess, String detail) {}
        var outliers = new ArrayList<Row>();

        for (int i = 0; i < pkgList.size(); i++) {
            String pkgId = pkgList.get(i);
            var members = pkgMembers.get(pkgId);
            int size = members.size();
            int ca = pkgGraph.fanIn[i], ce = pkgGraph.fanOut[i];
            if (size < 2 || ca + ce == 0) continue;

            int absCount = 0;
            int valueOnlyCount = 0;
            for (int idx : members) {
                var node = nodes.get(idx);
                if (node.abstractType() || Set.of("INTERFACE", "ANNOTATION").contains(node.typeKind())) absCount++;
                if (Set.of("RECORD", "ENUM").contains(node.typeKind())) valueOnlyCount++;
            }
            // Exclude pure immutable value-object leaf packages (ce == 0 && all records/enums) from Zone-of-Pain penalty.
            if (ce == 0 && valueOnlyCount == size) continue;

            BigDecimal a = CalibrationCurve.divide(absCount, size);
            BigDecimal inst = CalibrationCurve.divide(ce, ca + ce);
            BigDecimal d = a.add(inst).subtract(BigDecimal.ONE).abs();
            BigDecimal excess = d.subtract(MAIN_SEQ_TOLERANCE).max(BigDecimal.ZERO);
            weightedExcess = weightedExcess.add(excess.multiply(BigDecimal.valueOf(size)));
            totalTypes += size;
            if (excess.signum() > 0) {
                String zone = a.compareTo(new BigDecimal("0.30")) < 0 && inst.compareTo(new BigDecimal("0.30")) < 0
                        ? "ZONE_OF_PAIN"
                        : a.compareTo(new BigDecimal("0.70")) > 0 && inst.compareTo(new BigDecimal("0.70")) > 0
                        ? "ZONE_OF_USELESSNESS" : "OFF_MAIN_SEQUENCE";
                outliers.add(new Row(pkgId, pkgNames.getOrDefault(pkgId, pkgId), d, excess,
                        zone + " (A=" + CalibrationCurve.roundDisplay(a) + ", I=" + CalibrationCurve.roundDisplay(inst)
                                + ", D=" + CalibrationCurve.roundDisplay(d) + ", types=" + size + ")"));
            }
        }
        if (totalTypes == 0) return new MainSeqInfo(Optional.empty(), List.of());
        outliers.sort(Comparator.comparing(Row::excess).reversed().thenComparing(Row::pkgId));
        var witnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        for (int i = 0; i < Math.min(limit, outliers.size()); i++) {
            var r = outliers.get(i);
            witnesses.add(new ArchitectureAssessmentV2.EvidenceWitness("MAIN_SEQUENCE_OUTLIER", r.pkgId, r.pkgName, r.detail, List.of()));
        }
        return new MainSeqInfo(Optional.of(CalibrationCurve.divide(weightedExcess, BigDecimal.valueOf(totalTypes))), witnesses);
    }

    private record DsmInfo(Optional<BigDecimal> ratio, int count, List<ArchitectureAssessmentV2.EvidenceWitness> witnesses) {}

    private static DsmInfo computeDsmBackEdges(
            List<String> pkgList,
            Map<String, String> pkgNames,
            GroupGraph pkgGraph,
            SccResult pkgScc,
            int limit) {
        int g = pkgList.size();
        if (g < 2 || pkgGraph.edgeCount == 0) return new DsmInfo(Optional.empty(), 0, List.of());

        // Compute longest-path levelization on package condensation DAG: callers at higher level, sinks at 0.
        int cCount = pkgScc.components.size();
        var cOut = new ArrayList<Set<Integer>>(cCount);
        int[] inDeg = new int[cCount];
        for (int c = 0; c < cCount; c++) cOut.add(new TreeSet<>());
        for (int u = 0; u < g; u++) {
            int cu = pkgScc.compOf[u];
            for (int v : pkgGraph.adj.get(u)) {
                int cv = pkgScc.compOf[v];
                if (cu != cv && cOut.get(cu).add(cv)) inDeg[cv]++;
            }
        }
        int[] rem = inDeg.clone();
        var topo = new int[cCount];
        var pq = new PriorityQueue<Integer>();
        for (int c = 0; c < cCount; c++) if (rem[c] == 0) pq.add(c);
        int pos = 0;
        while (!pq.isEmpty()) {
            int u = pq.poll();
            topo[pos++] = u;
            for (int v : cOut.get(u)) if (--rem[v] == 0) pq.add(v);
        }
        int[] level = new int[cCount];
        for (int i = cCount - 1; i >= 0; i--) {
            int c = topo[i];
            int best = 0;
            for (int next : cOut.get(c)) best = Math.max(best, level[next] + 1);
            level[c] = best;
        }

        // Order packages: higher level first (top callers -> lower sinks), then (fanOut - fanIn) desc, then name asc.
        var orderList = new ArrayList<Integer>(g);
        for (int i = 0; i < g; i++) orderList.add(i);
        orderList.sort((a, b) -> {
            int cmp = Integer.compare(level[pkgScc.compOf[b]], level[pkgScc.compOf[a]]);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(pkgGraph.fanOut[b] - pkgGraph.fanIn[b], pkgGraph.fanOut[a] - pkgGraph.fanIn[a]);
            if (cmp != 0) return cmp;
            return pkgList.get(a).compareTo(pkgList.get(b));
        });
        int[] rank = new int[g];
        for (int i = 0; i < g; i++) rank[orderList.get(i)] = i;

        record BackRow(String id, String label, int weight, String detail) {}
        var backs = new ArrayList<BackRow>();
        for (int u = 0; u < g; u++) {
            for (int v : pkgGraph.adj.get(u)) {
                if (rank[v] < rank[u]) {
                    long key = (((long) u) << 32) | (v & 0xffffffffL);
                    int w = pkgGraph.edgeWeights.getOrDefault(key, 1);
                    String uName = pkgNames.getOrDefault(pkgList.get(u), pkgList.get(u));
                    String vName = pkgNames.getOrDefault(pkgList.get(v), pkgList.get(v));
                    backs.add(new BackRow(pkgList.get(u) + "->" + pkgList.get(v), uName + " -> " + vName, w,
                            "DSM upward back-edge (" + w + " underlying type dependencies)"));
                }
            }
        }
        backs.sort(Comparator.comparingInt(BackRow::weight).reversed().thenComparing(BackRow::id));
        var witnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        for (int i = 0; i < Math.min(limit, backs.size()); i++) {
            var b = backs.get(i);
            witnesses.add(new ArchitectureAssessmentV2.EvidenceWitness("DSM_BACK_EDGE", b.id, b.label, b.detail, List.of()));
        }
        return new DsmInfo(Optional.of(CalibrationCurve.divide(backs.size(), pkgGraph.edgeCount)), backs.size(), witnesses);
    }

    private record HubInfo(Optional<BigDecimal> density, int count, List<ArchitectureAssessmentV2.EvidenceWitness> witnesses) {}

    private static HubInfo computeCrossingHubs(List<NodeInfo> nodes, int[] fanIn, int[] fanOut, int limit) {
        int n = nodes.size();
        if (n < 2) return new HubInfo(Optional.empty(), 0, List.of());
        int sqrtN = (int) StrictMath.floor(StrictMath.sqrt(n));
        int tHub = Math.min(10, Math.max(5, sqrtN / 2));
        BigDecimal tHubDec = BigDecimal.valueOf(tHub);
        BigDecimal twoTHub = BigDecimal.valueOf(2L * tHub);

        BigDecimal sum = BigDecimal.ZERO;
        record HubRow(int idx, BigDecimal severity, int ca, int ce) {}
        var hubs = new ArrayList<HubRow>();
        for (int i = 0; i < n; i++) {
            if (fanIn[i] < CROSSING_MIN_DEGREE || fanOut[i] < CROSSING_MIN_DEGREE) continue;
            BigDecimal geo = CalibrationCurve.sqrt(BigDecimal.valueOf((long) fanIn[i] * (long) fanOut[i]));
            if (geo.compareTo(tHubDec) <= 0) continue;
            BigDecimal sev = CalibrationCurve.divide(geo.subtract(tHubDec), twoTHub).min(BigDecimal.ONE);
            sum = sum.add(sev);
            hubs.add(new HubRow(i, sev, fanIn[i], fanOut[i]));
        }
        hubs.sort(Comparator.comparing(HubRow::severity).reversed()
                .thenComparing(r -> nodes.get(r.idx).identity()));
        var witnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        for (int i = 0; i < Math.min(limit, hubs.size()); i++) {
            var h = hubs.get(i);
            var node = nodes.get(h.idx);
            witnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(
                    "CROSSING_HUB", node.identity(), node.label(),
                    "Crossing hub: fanIn=" + h.ca + ", fanOut=" + h.ce + " (threshold=" + tHub + ")",
                    node.evidence()));
        }
        return new HubInfo(Optional.of(CalibrationCurve.divide(sum, BigDecimal.valueOf(n))), hubs.size(), witnesses);
    }

    private record FatInfo(Optional<BigDecimal> ratio, List<ArchitectureAssessmentV2.EvidenceWitness> witnesses) {}

    private static FatInfo computeFatComponents(
            List<NodeInfo> nodes,
            List<String> pkgList,
            Map<String, String> pkgNames,
            Map<String, List<Integer>> pkgMembers,
            int limit) {
        int n = nodes.size();
        if (n < 2) return new FatInfo(Optional.empty(), List.of());

        BigDecimal typeFatSum = BigDecimal.ZERO;
        int eligibleTypes = 0;
        record FatRow(String kind, String id, String label, BigDecimal excess, String detail, List<ContentDigest> ev) {}
        var offenders = new ArrayList<FatRow>();

        for (var node : nodes) {
            if ("ENUM".equals(node.typeKind()) || "ANNOTATION".equals(node.typeKind())) continue;
            eligibleTypes++;
            if (node.memberCount() > TYPE_FAT_THRESHOLD) {
                BigDecimal ex = CalibrationCurve.divide(node.memberCount() - TYPE_FAT_THRESHOLD, TYPE_FAT_SPAN).min(BigDecimal.ONE);
                typeFatSum = typeFatSum.add(ex);
                offenders.add(new FatRow("FAT_TYPE", node.identity(), node.label(), ex,
                        node.memberCount() + " declared members (threshold <= " + TYPE_FAT_THRESHOLD + ")",
                        node.evidence()));
            }
        }
        BigDecimal fType = eligibleTypes == 0 ? BigDecimal.ZERO : CalibrationCurve.divide(typeFatSum, BigDecimal.valueOf(eligibleTypes));

        BigDecimal blended;
        if (pkgList.size() >= 2) {
            BigDecimal pkgFatSum = BigDecimal.ZERO;
            for (String pkgId : pkgList) {
                int size = pkgMembers.get(pkgId).size();
                if (size > PACKAGE_FAT_THRESHOLD) {
                    BigDecimal ex = CalibrationCurve.divide(size - PACKAGE_FAT_THRESHOLD, PACKAGE_FAT_SPAN).min(BigDecimal.ONE);
                    pkgFatSum = pkgFatSum.add(ex);
                    offenders.add(new FatRow("FAT_PACKAGE", pkgId, pkgNames.getOrDefault(pkgId, pkgId), ex,
                            size + " production types (threshold <= " + PACKAGE_FAT_THRESHOLD + ")", List.of()));
                }
            }
            BigDecimal fPkg = CalibrationCurve.divide(pkgFatSum, BigDecimal.valueOf(pkgList.size()));
            blended = new BigDecimal("0.60").multiply(fType).add(new BigDecimal("0.40").multiply(fPkg));
        } else {
            blended = fType;
        }

        offenders.sort(Comparator.comparing(FatRow::excess).reversed().thenComparing(FatRow::id));
        var witnesses = new ArrayList<ArchitectureAssessmentV2.EvidenceWitness>();
        for (int i = 0; i < Math.min(limit, offenders.size()); i++) {
            var o = offenders.get(i);
            witnesses.add(new ArchitectureAssessmentV2.EvidenceWitness(o.kind, o.id, o.label, o.detail, o.ev));
        }
        return new FatInfo(Optional.of(CalibrationCurve.round(blended, CalibrationCurve.SCALE)), witnesses);
    }

    private static Optional<BigDecimal> computeGini(int n, int[] fanIn, int[] fanOut) {
        if (n < MIN_GINI_NODES) return Optional.empty();
        long[] degrees = new long[n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            degrees[i] = (long) fanIn[i] + (long) fanOut[i];
            total += degrees[i];
        }
        if (total == 0) return Optional.empty();
        Arrays.sort(degrees);
        BigDecimal weighted = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            long coef = 2L * (i + 1) - n - 1L;
            weighted = weighted.add(BigDecimal.valueOf(coef).multiply(BigDecimal.valueOf(degrees[i])));
        }
        BigDecimal denom = BigDecimal.valueOf(n).multiply(BigDecimal.valueOf(total));
        return Optional.of(CalibrationCurve.clamp(CalibrationCurve.divide(weighted, denom), BigDecimal.ZERO, BigDecimal.ONE));
    }

    private StructuralTopologyAnalyzer() {}
}
