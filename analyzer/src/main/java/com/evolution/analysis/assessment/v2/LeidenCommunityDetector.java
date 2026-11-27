package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Pure Java 21 deterministic Leiden community detection (Traag, Waltman &amp; van Eck, 2019).
 *
 * <p>Executes fast queue-based local moving, connectivity-preserving refinement, hierarchical
 * aggregation, deterministic BFS connectivity verification, and bounded recursive splitting of
 * oversized or low-cohesion communities. All tie-breaking uses canonical {@code identity} order
 * and {@link StrictMath} so repeated runs over identical inputs yield bit-for-bit identical
 * community partitions and SHA-256 community IDs without external dependencies.
 */
public final class LeidenCommunityDetector {
    public static final String ALGORITHM = "leiden-modularity:java21-v1";
    private static final int MAX_LEVELS = 20;
    private static final int MAX_LOCAL_PASSES = 64;
    private static final double GAIN_EPSILON = 1e-12;
    private static final int MAX_CACHE_ENTRIES = 32;

    private static final Map<String, Result> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHE_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    public record NodeInput(String identity, String label, String declaredPackage, String kind) {
        public NodeInput {
            identity = Objects.requireNonNull(identity, "identity");
            label = (label == null || label.isBlank()) ? identity : label;
            declaredPackage = declaredPackage == null ? "" : declaredPackage.strip();
            kind = (kind == null || kind.isBlank()) ? "TYPE" : kind;
        }
    }

    public record EdgeInput(String source, String target, double weight, String kind) {
        public EdgeInput {
            source = Objects.requireNonNull(source, "source");
            target = Objects.requireNonNull(target, "target");
            if (!Double.isFinite(weight) || weight <= 0.0) {
                weight = 1.0;
            }
            kind = (kind == null || kind.isBlank()) ? "graph.depends-on" : kind;
        }
    }

    public record Config(
            double resolution,
            long seed,
            double maxClusterShare,
            int minSplitSize,
            double minCohesionForLarge,
            int minLargeSize,
            int maxSplitDepth) {
        public static Config defaults() {
            return new Config(1.0, 0x5EED_CAFE_2026L, 0.25, 10, 0.05, 50, 3);
        }

        public Config {
            if (!Double.isFinite(resolution) || resolution <= 0.0) resolution = 1.0;
            if (!Double.isFinite(maxClusterShare) || maxClusterShare <= 0.0 || maxClusterShare > 1.0) maxClusterShare = 0.25;
            minSplitSize = Math.max(4, minSplitSize);
            if (!Double.isFinite(minCohesionForLarge) || minCohesionForLarge < 0.0) minCohesionForLarge = 0.05;
            minLargeSize = Math.max(10, minLargeSize);
            maxSplitDepth = Math.max(0, Math.min(5, maxSplitDepth));
        }
    }

    public record EmergentCommunity(
            String id,
            String memberSetDigest,
            String label,
            String dominantPackage,
            String hubIdentity,
            String hubLabel,
            int memberCount,
            int internalEdgeCount,
            int externalEdgeCount,
            BigDecimal cohesion,
            int misplacedCount,
            int bridgeEdgeCount,
            List<String> members) {
        public EmergentCommunity {
            members = List.copyOf(members);
        }
    }

    public record MisplacedType(
            String identity,
            String label,
            String declaredPackage,
            String communityId,
            String communityLabel,
            String dominantPackage,
            int internalCommunityEdges,
            int declaredPackageEdges,
            String rationale) {}

    public record CommunityBridge(
            String fromCommunityId,
            String fromCommunityLabel,
            String toCommunityId,
            String toCommunityLabel,
            int edgeCount,
            BigDecimal totalWeight,
            List<String> sampleEdges) {
        public CommunityBridge {
            sampleEdges = List.copyOf(sampleEdges);
        }
    }

    public record Result(
            String algorithm,
            String cacheKey,
            BigDecimal modularity,
            int nodeCount,
            int edgeCount,
            List<EmergentCommunity> communities,
            List<MisplacedType> misplacedTypes,
            List<CommunityBridge> bridges,
            Map<String, String> communityByNode) {
        public Result {
            communities = List.copyOf(communities);
            misplacedTypes = List.copyOf(misplacedTypes);
            bridges = List.copyOf(bridges);
            communityByNode = Collections.unmodifiableMap(new LinkedHashMap<>(communityByNode));
        }
    }

    private LeidenCommunityDetector() {}

    public static Result detect(List<NodeInput> rawNodes, List<EdgeInput> rawEdges, Runnable checkpoint) {
        return detect(rawNodes, rawEdges, Config.defaults(), checkpoint);
    }

    public static Result detect(List<NodeInput> rawNodes, List<EdgeInput> rawEdges, Config config, Runnable checkpoint) {
        Config cfg = config == null ? Config.defaults() : config;
        Runnable tick = checkpoint == null ? () -> {} : checkpoint;
        tick.run();

        // 1. Canonical sort & deduplication by identity
        var nodeMap = new TreeMap<String, NodeInput>();
        if (rawNodes != null) {
            for (var n : rawNodes) {
                if (n != null) nodeMap.putIfAbsent(n.identity(), n);
            }
        }
        int n = nodeMap.size();
        if (n == 0) {
            String emptyKey = ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
                    ALGORITHM, Double.toString(cfg.resolution()), cfg.seed(), List.of()))).value();
            return new Result(ALGORITHM, emptyKey, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                    0, 0, List.of(), List.of(), List.of(), Map.of());
        }

        List<NodeInput> nodes = List.copyOf(nodeMap.values());
        var idToIndex = new HashMap<String, Integer>(n * 2);
        for (int i = 0; i < n; i++) {
            idToIndex.put(nodes.get(i).identity(), i);
        }

        // 2. Canonical undirected weighted adjacency + preserved directed edges for bridge reporting
        var undirectedWeights = new ArrayList<TreeMap<Integer, Double>>(n);
        for (int i = 0; i < n; i++) undirectedWeights.add(new TreeMap<>());
        double[] selfLoops = new double[n];
        var directedEdges = new ArrayList<ResolvedDirectedEdge>();
        int validEdgeCount = 0;

        if (rawEdges != null) {
            // Sort edges canonically before aggregation so floating-point summation order is strictly deterministic
            var sortedEdges = new ArrayList<EdgeInput>(rawEdges.size());
            for (var e : rawEdges) {
                if (e != null && idToIndex.containsKey(e.source()) && idToIndex.containsKey(e.target())) {
                    sortedEdges.add(e);
                }
            }
            sortedEdges.sort(Comparator.comparing(EdgeInput::source)
                    .thenComparing(EdgeInput::target)
                    .thenComparing(EdgeInput::kind)
                    .thenComparingDouble(EdgeInput::weight));

            for (var e : sortedEdges) {
                int u = idToIndex.get(e.source());
                int v = idToIndex.get(e.target());
                validEdgeCount++;
                if (u == v) {
                    selfLoops[u] += e.weight();
                } else {
                    undirectedWeights.get(u).merge(v, e.weight(), Double::sum);
                    undirectedWeights.get(v).merge(u, e.weight(), Double::sum);
                    directedEdges.add(new ResolvedDirectedEdge(u, v, e.weight(), e.kind()));
                }
            }
        }

        // 3. Canonical sorted SHA-256 cache key (never XOR)
        String cacheKey = computeCacheKey(nodes, directedEdges, selfLoops, validEdgeCount, cfg);
        Result cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        tick.run();

        // 4. Build compact graph representation & execute Leiden
        WeightedGraph graph = WeightedGraph.fromAdjacency(n, undirectedWeights, selfLoops);
        int[] rawPartition = runLeiden(graph, cfg.resolution(), cfg.seed(), tick);
        int[] connectedPartition = enforceConnectedCommunities(graph, rawPartition, tick);
        int[] finalPartition = applySmartRecursiveSplits(graph, connectedPartition, cfg, 0, tick);

        // 5. Compute modularity Q on the final partition
        double modularityQ = computeModularity(graph, finalPartition, cfg.resolution());

        // 6. Materialize canonical communities, drift (misplaced types), and cross-community bridges
        Result built = buildResult(cacheKey, nodes, graph, directedEdges, validEdgeCount, finalPartition, modularityQ);
        CACHE.put(cacheKey, built);
        return built;
    }

    private record ResolvedDirectedEdge(int u, int v, double weight, String kind) {}

    private static final class WeightedGraph {
        final int n;
        final int[][] neighbors;
        final double[][] weights;
        final double[] selfLoop;
        final double[] degree;
        final double twoM;

        WeightedGraph(int n, int[][] neighbors, double[][] weights, double[] selfLoop) {
            this.n = n;
            this.neighbors = neighbors;
            this.weights = weights;
            this.selfLoop = selfLoop;
            this.degree = new double[n];
            double total = 0.0;
            for (int u = 0; u < n; u++) {
                double d = 2.0 * selfLoop[u];
                for (double w : weights[u]) {
                    d += w;
                }
                degree[u] = d;
                total += d;
            }
            this.twoM = total;
        }

        static WeightedGraph fromAdjacency(int n, List<TreeMap<Integer, Double>> adj, double[] selfLoops) {
            int[][] nbrs = new int[n][];
            double[][] wts = new double[n][];
            for (int u = 0; u < n; u++) {
                var map = adj.get(u);
                int k = map.size();
                nbrs[u] = new int[k];
                wts[u] = new double[k];
                int idx = 0;
                for (var entry : map.entrySet()) {
                    nbrs[u][idx] = entry.getKey();
                    wts[u][idx] = entry.getValue();
                    idx++;
                }
            }
            return new WeightedGraph(n, nbrs, wts, Arrays.copyOf(selfLoops, n));
        }

        WeightedGraph inducedSubgraph(int[] memberIndices) {
            int subN = memberIndices.length;
            var globalToLocal = new HashMap<Integer, Integer>(subN * 2);
            for (int i = 0; i < subN; i++) globalToLocal.put(memberIndices[i], i);
            var subAdj = new ArrayList<TreeMap<Integer, Double>>(subN);
            for (int i = 0; i < subN; i++) subAdj.add(new TreeMap<>());
            double[] subSelf = new double[subN];
            for (int localU = 0; localU < subN; localU++) {
                int globalU = memberIndices[localU];
                subSelf[localU] = selfLoop[globalU];
                int[] nbrs = neighbors[globalU];
                double[] wts = weights[globalU];
                for (int j = 0; j < nbrs.length; j++) {
                    Integer localV = globalToLocal.get(nbrs[j]);
                    if (localV != null) {
                        subAdj.get(localU).put(localV, wts[j]);
                    }
                }
            }
            return fromAdjacency(subN, subAdj, subSelf);
        }
    }

    /** Full hierarchical Traag et al. (2019) Leiden: local moving -> refinement -> aggregation. */
    private static int[] runLeiden(WeightedGraph root, double gamma, long seed, Runnable tick) {
        int[] rootToCurrent = new int[root.n];
        for (int i = 0; i < root.n; i++) rootToCurrent[i] = i;

        if (root.twoM <= 0.0 || root.n <= 1) {
            return rootToCurrent;
        }

        WeightedGraph current = root;
        int[] partition = new int[current.n];
        for (int i = 0; i < current.n; i++) partition[i] = i;

        boolean appliedFinal = false;
        for (int level = 0; level < MAX_LEVELS; level++) {
            tick.run();
            boolean moved = moveNodesFast(current, partition, gamma, seed ^ (level * 0x9E3779B97F4A7C15L), tick);
            int[] refined = refinePartition(current, partition, gamma, tick);
            int numRefined = renumberCanonical(refined);

            // Map root nodes into refined partition for output if we stop or aggregate
            if (!moved && level > 0) {
                applyMapping(rootToCurrent, partition);
                appliedFinal = true;
                break;
            }
            if (numRefined >= current.n) {
                applyMapping(rootToCurrent, partition);
                appliedFinal = true;
                break;
            }

            // Build aggregated graph on refined communities; initialize coarse partition from local-moving partition
            applyMapping(rootToCurrent, refined);
            int[] superInitPartition = new int[numRefined];
            Arrays.fill(superInitPartition, -1);
            for (int u = 0; u < current.n; u++) {
                int r = refined[u];
                if (superInitPartition[r] < 0) {
                    superInitPartition[r] = partition[u];
                }
            }
            renumberCanonical(superInitPartition);

            current = aggregateGraph(current, refined, numRefined);
            partition = superInitPartition;
            if (current.n <= 1) {
                applyMapping(rootToCurrent, partition);
                appliedFinal = true;
                break;
            }
        }
        if (!appliedFinal) {
            applyMapping(rootToCurrent, partition);
        }
        renumberCanonical(rootToCurrent);
        return rootToCurrent;
    }

    /** Phase 1: Fast queue-based local moving with deterministic tie-breaking by smallest member index. */
    private static boolean moveNodesFast(WeightedGraph g, int[] comm, double gamma, long seed, Runnable tick) {
        int n = g.n;
        if (g.twoM <= 0.0 || n <= 1) return false;
        double invTwoM = 1.0 / g.twoM;

        int maxComm = renumberCanonical(comm);
        int capacity = Math.max(n, maxComm);
        double[] commDeg = new double[capacity];
        int[] commSize = new int[capacity];
        int[] commMinNode = new int[capacity];
        Arrays.fill(commMinNode, Integer.MAX_VALUE);
        var commMembers = new ArrayList<TreeSet<Integer>>(capacity);
        for (int c = 0; c < capacity; c++) commMembers.add(new TreeSet<>());

        for (int u = 0; u < n; u++) {
            int c = comm[u];
            commDeg[c] += g.degree[u];
            commSize[c]++;
            commMembers.get(c).add(u);
            if (u < commMinNode[c]) commMinNode[c] = u;
        }

        // Deterministic seeded initial visit order
        int[] visitOrder = new int[n];
        for (int i = 0; i < n; i++) visitOrder[i] = i;
        var rng = new SplittableRandom(seed);
        for (int i = n - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = visitOrder[i];
            visitOrder[i] = visitOrder[j];
            visitOrder[j] = tmp;
        }

        boolean[] inQueue = new boolean[n];
        int[] queue = new int[n + 1];
        int head = 0, tail = 0;
        for (int u : visitOrder) {
            queue[tail] = u;
            tail = (tail + 1) % queue.length;
            inQueue[u] = true;
        }

        // Track free/empty community IDs so a node can move to its own singleton community if beneficial
        var emptyComms = new PriorityQueue<Integer>();
        for (int c = maxComm; c < commDeg.length; c++) emptyComms.add(c);

        double[] weightToComm = new double[commDeg.length];
        int[] touched = new int[commDeg.length];
        boolean anyMoved = false;
        long pops = 0;
        long maxPops = (long) n * MAX_LOCAL_PASSES;

        while (head != tail && pops < maxPops) {
            if ((++pops & 511L) == 0L) tick.run();
            int v = queue[head];
            head = (head + 1) % queue.length;
            inQueue[v] = false;

            int oldC = comm[v];
            double kv = g.degree[v];
            int[] nbrs = g.neighbors[v];
            double[] wts = g.weights[v];
            if (nbrs.length == 0) continue;

            int touchedCount = 0;
            for (int i = 0; i < nbrs.length; i++) {
                int nc = comm[nbrs[i]];
                if (weightToComm[nc] == 0.0) {
                    touched[touchedCount++] = nc;
                }
                weightToComm[nc] += wts[i];
            }

            // Temporarily remove v from oldC
            double oldCommDegWithoutV = commDeg[oldC] - kv;
            double currentScore = weightToComm[oldC] - gamma * kv * oldCommDegWithoutV * invTwoM;

            int bestC = oldC;
            double bestScore = currentScore;
            int bestTieKey = (commSize[oldC] == 1) ? v : commMinNode[oldC];

            // Consider singleton community (score 0.0) when v is currently not alone
            if (commSize[oldC] > 1 && !emptyComms.isEmpty()) {
                double emptyScore = 0.0;
                if (emptyScore > bestScore + GAIN_EPSILON) {
                    bestScore = emptyScore;
                    bestC = emptyComms.peek();
                    bestTieKey = v;
                }
            }

            for (int i = 0; i < touchedCount; i++) {
                int cand = touched[i];
                if (cand == oldC) continue;
                double candScore = weightToComm[cand] - gamma * kv * commDeg[cand] * invTwoM;
                int candTieKey = commMinNode[cand];
                if (candScore > bestScore + GAIN_EPSILON
                        || (StrictMath.abs(candScore - bestScore) <= GAIN_EPSILON
                                && candScore > currentScore + GAIN_EPSILON
                                && candTieKey < bestTieKey)) {
                    bestScore = candScore;
                    bestC = cand;
                    bestTieKey = candTieKey;
                }
            }

            // Reset scratch array
            for (int i = 0; i < touchedCount; i++) {
                weightToComm[touched[i]] = 0.0;
            }

            if (bestC != oldC) {
                anyMoved = true;
                if (commSize[bestC] == 0) {
                    emptyComms.remove(bestC);
                }
                comm[v] = bestC;
                commDeg[oldC] -= kv;
                commSize[oldC]--;
                commMembers.get(oldC).remove(v);
                commDeg[bestC] += kv;
                commSize[bestC]++;
                commMembers.get(bestC).add(v);

                if (commSize[oldC] == 0) {
                    commDeg[oldC] = 0.0;
                    commMinNode[oldC] = Integer.MAX_VALUE;
                    emptyComms.add(oldC);
                } else if (commMinNode[oldC] == v) {
                    commMinNode[oldC] = commMembers.get(oldC).first();
                }
                if (v < commMinNode[bestC]) {
                    commMinNode[bestC] = v;
                }

                // Re-enqueue neighbors in other communities in canonical order
                for (int u : nbrs) {
                    if (comm[u] != bestC && !inQueue[u]) {
                        queue[tail] = u;
                        tail = (tail + 1) % queue.length;
                        inQueue[u] = true;
                    }
                }
            }
        }
        return anyMoved;
    }

    /** Phase 2: Traag et al. (2019) connectivity-preserving refinement within each coarse community. */
    private static int[] refinePartition(WeightedGraph g, int[] coarse, double gamma, Runnable tick) {
        int n = g.n;
        int numCoarse = renumberCanonical(coarse);
        var membersByCoarse = new ArrayList<List<Integer>>(numCoarse);
        for (int c = 0; c < numCoarse; c++) membersByCoarse.add(new ArrayList<>());
        double[] coarseDeg = new double[numCoarse];
        for (int u = 0; u < n; u++) {
            int c = coarse[u];
            membersByCoarse.get(c).add(u);
            coarseDeg[c] += g.degree[u];
        }

        int[] refined = new int[n];
        for (int u = 0; u < n; u++) refined[u] = u;
        double[] refDeg = Arrays.copyOf(g.degree, n);
        int[] refSize = new int[n];
        Arrays.fill(refSize, 1);
        double[] refInternalToCoarse = new double[n];
        double invTwoM = g.twoM > 0.0 ? (1.0 / g.twoM) : 0.0;

        // Precompute each node's edge weight staying inside its coarse community
        for (int u = 0; u < n; u++) {
            int c = coarse[u];
            double inWeight = 0.0;
            int[] nbrs = g.neighbors[u];
            double[] wts = g.weights[u];
            for (int i = 0; i < nbrs.length; i++) {
                if (coarse[nbrs[i]] == c) inWeight += wts[i];
            }
            refInternalToCoarse[u] = inWeight;
        }

        double[] wToRef = new double[n];
        int[] touched = new int[n];

        for (int c = 0; c < numCoarse; c++) {
            var members = membersByCoarse.get(c);
            if (members.size() <= 1) continue;
            double totalCoarseDeg = coarseDeg[c];

            for (int v : members) {
                tick.run();
                if (refSize[refined[v]] != 1) continue; // Only merge nodes still in singleton sub-communities
                double kv = g.degree[v];
                double wellConnThresh = gamma * kv * (totalCoarseDeg - kv) * invTwoM;
                if (refInternalToCoarse[v] + GAIN_EPSILON < wellConnThresh) continue;

                int[] nbrs = g.neighbors[v];
                double[] wts = g.weights[v];
                int touchedCount = 0;
                for (int i = 0; i < nbrs.length; i++) {
                    int u = nbrs[i];
                    if (coarse[u] != c) continue;
                    int r = refined[u];
                    if (r == refined[v]) continue;
                    if (wToRef[r] == 0.0) touched[touchedCount++] = r;
                    wToRef[r] += wts[i];
                }

                int bestR = -1;
                double bestGain = GAIN_EPSILON;
                for (int i = 0; i < touchedCount; i++) {
                    int candR = touched[i];
                    double candDeg = refDeg[candR];
                    double candWellThresh = gamma * candDeg * (totalCoarseDeg - candDeg) * invTwoM;
                    if (refInternalToCoarse[candR] + GAIN_EPSILON < candWellThresh) continue;
                    double gain = wToRef[candR] - gamma * kv * candDeg * invTwoM;
                    if (gain > bestGain + GAIN_EPSILON
                            || (bestR >= 0 && StrictMath.abs(gain - bestGain) <= GAIN_EPSILON && candR < bestR)) {
                        bestGain = gain;
                        bestR = candR;
                    }
                }

                if (bestR >= 0) {
                    int oldR = refined[v];
                    double edgeToBest = wToRef[bestR];
                    refined[v] = bestR;
                    refSize[oldR]--;
                    refSize[bestR]++;
                    refDeg[oldR] -= kv;
                    refDeg[bestR] += kv;
                    // Update cut-weight within coarse community C for merged sub-community
                    refInternalToCoarse[bestR] = refInternalToCoarse[bestR] + refInternalToCoarse[oldR] - 2.0 * edgeToBest;
                }

                for (int i = 0; i < touchedCount; i++) wToRef[touched[i]] = 0.0;
            }
        }
        return refined;
    }

    /** Phase 3: Aggregate graph over refined sub-communities. */
    private static WeightedGraph aggregateGraph(WeightedGraph g, int[] refined, int numRefined) {
        var superAdj = new ArrayList<TreeMap<Integer, Double>>(numRefined);
        for (int i = 0; i < numRefined; i++) superAdj.add(new TreeMap<>());
        double[] superSelf = new double[numRefined];

        for (int u = 0; u < g.n; u++) {
            int cu = refined[u];
            superSelf[cu] += g.selfLoop[u];
            int[] nbrs = g.neighbors[u];
            double[] wts = g.weights[u];
            for (int i = 0; i < nbrs.length; i++) {
                int v = nbrs[i];
                if (v < u) continue; // Count each undirected edge once
                int cv = refined[v];
                double w = wts[i];
                if (cu == cv) {
                    superSelf[cu] += w;
                } else {
                    superAdj.get(cu).merge(cv, w, Double::sum);
                    superAdj.get(cv).merge(cu, w, Double::sum);
                }
            }
        }
        return WeightedGraph.fromAdjacency(numRefined, superAdj, superSelf);
    }

    /** Guarantees every emitted community is a single connected component in the original graph. */
    private static int[] enforceConnectedCommunities(WeightedGraph g, int[] partition, Runnable tick) {
        int n = g.n;
        int[] out = new int[n];
        Arrays.fill(out, -1);
        int nextId = 0;
        int[] bfs = new int[n];

        for (int start = 0; start < n; start++) {
            if (out[start] >= 0) continue;
            if ((start & 255) == 0) tick.run();
            int origComm = partition[start];
            int cid = nextId++;
            int head = 0, tail = 0;
            bfs[tail++] = start;
            out[start] = cid;
            while (head < tail) {
                int u = bfs[head++];
                for (int v : g.neighbors[u]) {
                    if (out[v] < 0 && partition[v] == origComm) {
                        out[v] = cid;
                        bfs[tail++] = v;
                    }
                }
            }
        }
        return out;
    }

    /** Recursively splits oversized (>25% & >=10) or low-cohesion (<0.05 & >=50) communities. */
    private static int[] applySmartRecursiveSplits(
            WeightedGraph root, int[] partition, Config cfg, int depth, Runnable tick) {
        if (depth >= cfg.maxSplitDepth()) return partition;
        int numComms = renumberCanonical(partition);
        var groups = new ArrayList<List<Integer>>(numComms);
        for (int c = 0; c < numComms; c++) groups.add(new ArrayList<>());
        for (int u = 0; u < root.n; u++) groups.get(partition[u]).add(u);

        int[] updated = new int[root.n];
        int nextCid = 0;
        boolean anyNeedsFurther = false;

        for (int c = 0; c < numComms; c++) {
            tick.run();
            var members = groups.get(c);
            int size = members.size();
            boolean oversized = size >= cfg.minSplitSize() && ((double) size / root.n) > cfg.maxClusterShare();
            boolean lowCohesion = size >= cfg.minLargeSize() && computeSubgraphDensity(root, members) < cfg.minCohesionForLarge();

            if (!oversized && !lowCohesion) {
                int cid = nextCid++;
                for (int u : members) updated[u] = cid;
                continue;
            }

            int[] memberIdx = members.stream().mapToInt(Integer::intValue).toArray();
            WeightedGraph sub = root.inducedSubgraph(memberIdx);
            double splitGamma = cfg.resolution() * StrictMath.pow(1.5, depth + 1);
            int[] subPart = enforceConnectedCommunities(sub,
                    runLeiden(sub, splitGamma, cfg.seed() ^ ((depth + 1L) * 0x517CC1B727220A95L) ^ c, tick), tick);
            int subCount = renumberCanonical(subPart);
            if (subCount <= 1) {
                // Retry once at elevated resolution if community is dense enough to resist 1.5x
                subPart = enforceConnectedCommunities(sub,
                        runLeiden(sub, splitGamma * 1.8, cfg.seed() ^ ((depth + 11L) * 0x517CC1B727220A95L) ^ c, tick), tick);
                subCount = renumberCanonical(subPart);
            }

            if (subCount <= 1) {
                int cid = nextCid++;
                for (int u : members) updated[u] = cid;
            } else {
                var subGroups = new ArrayList<List<Integer>>(subCount);
                for (int sc = 0; sc < subCount; sc++) subGroups.add(new ArrayList<>());
                for (int i = 0; i < sub.n; i++) subGroups.get(subPart[i]).add(memberIdx[i]);
                for (var sg : subGroups) {
                    int cid = nextCid++;
                    for (int globalU : sg) updated[globalU] = cid;
                    if ((sg.size() >= cfg.minSplitSize() && ((double) sg.size() / root.n) > cfg.maxClusterShare())
                            || (sg.size() >= cfg.minLargeSize() && computeSubgraphDensity(root, sg) < cfg.minCohesionForLarge())) {
                        anyNeedsFurther = true;
                    }
                }
            }
        }
        renumberCanonical(updated);
        if (anyNeedsFurther && depth + 1 < cfg.maxSplitDepth()) {
            return applySmartRecursiveSplits(root, updated, cfg, depth + 1, tick);
        }
        return updated;
    }

    private static double computeSubgraphDensity(WeightedGraph g, List<Integer> members) {
        int k = members.size();
        if (k <= 1) return 1.0;
        var set = new HashSet<Integer>(k * 2);
        for (int u : members) set.add(u);
        long internalTwice = 0;
        for (int u : members) {
            for (int v : g.neighbors[u]) {
                if (set.contains(v)) internalTwice++;
            }
        }
        long internalEdges = internalTwice / 2;
        long possible = (long) k * (k - 1) / 2;
        return possible == 0 ? 1.0 : Math.min(1.0, (double) internalEdges / possible);
    }

    private static double computeModularity(WeightedGraph g, int[] comm, double gamma) {
        if (g.twoM <= 0.0 || g.n == 0) return 0.0;
        int numComms = 0;
        for (int c : comm) if (c + 1 > numComms) numComms = c + 1;
        double[] inWeightTwice = new double[numComms];
        double[] totDeg = new double[numComms];
        for (int u = 0; u < g.n; u++) {
            int cu = comm[u];
            totDeg[cu] += g.degree[u];
            inWeightTwice[cu] += 2.0 * g.selfLoop[u];
            int[] nbrs = g.neighbors[u];
            double[] wts = g.weights[u];
            for (int i = 0; i < nbrs.length; i++) {
                if (comm[nbrs[i]] == cu) {
                    inWeightTwice[cu] += wts[i];
                }
            }
        }
        double q = 0.0;
        for (int c = 0; c < numComms; c++) {
            if (totDeg[c] <= 0.0) continue;
            q += (inWeightTwice[c] / g.twoM) - gamma * StrictMath.pow(totDeg[c] / g.twoM, 2);
        }
        return Double.isFinite(q) ? q : 0.0;
    }

    private static Result buildResult(
            String cacheKey,
            List<NodeInput> nodes,
            WeightedGraph graph,
            List<ResolvedDirectedEdge> directedEdges,
            int validEdgeCount,
            int[] partition,
            double modularityQ) {
        int n = nodes.size();
        int numComms = renumberCanonical(partition);

        var memberIndicesByCid = new ArrayList<List<Integer>>(numComms);
        for (int c = 0; c < numComms; c++) memberIndicesByCid.add(new ArrayList<>());
        for (int u = 0; u < n; u++) memberIndicesByCid.get(partition[u]).add(u);

        // Compute canonical member-set SHA-256 community IDs and labels
        String[] cidToCanonicalId = new String[numComms];
        String[] cidToDigest = new String[numComms];
        String[] cidToDomPkg = new String[numComms];
        String[] cidToHubId = new String[numComms];
        String[] cidToHubLabel = new String[numComms];
        String[] cidToLabel = new String[numComms];
        int[] cidInternalEdges = new int[numComms];
        int[] cidExternalEdges = new int[numComms];

        for (int u = 0; u < n; u++) {
            int cu = partition[u];
            for (int v : graph.neighbors[u]) {
                if (v < u) continue;
                int cv = partition[v];
                if (cu == cv) {
                    cidInternalEdges[cu]++;
                } else {
                    cidExternalEdges[cu]++;
                    cidExternalEdges[cv]++;
                }
            }
        }

        for (int c = 0; c < numComms; c++) {
            var members = memberIndicesByCid.get(c);
            var memberIds = new ArrayList<String>(members.size());
            var pkgCounts = new TreeMap<String, Integer>();
            int hubIdx = members.getFirst();
            int bestInternalDeg = -1;
            int bestTotalDeg = -1;

            for (int u : members) {
                var node = nodes.get(u);
                memberIds.add(node.identity());
                if (!node.declaredPackage().isEmpty()) {
                    pkgCounts.merge(node.declaredPackage(), 1, Integer::sum);
                }
                int intDeg = 0;
                for (int v : graph.neighbors[u]) {
                    if (partition[v] == c) intDeg++;
                }
                int totDeg = graph.neighbors[u].length;
                if (intDeg > bestInternalDeg
                        || (intDeg == bestInternalDeg && totDeg > bestTotalDeg)
                        || (intDeg == bestInternalDeg && totDeg == bestTotalDeg && u < hubIdx)) {
                    bestInternalDeg = intDeg;
                    bestTotalDeg = totDeg;
                    hubIdx = u;
                }
            }
            Collections.sort(memberIds);
            String digest = ContentDigest.sha256Utf8(CanonicalJson.write(memberIds)).value();
            String hex = digest.startsWith("sha256:") ? digest.substring(7) : digest;
            String commId = "comm:" + hex.substring(0, Math.min(16, hex.length()));
            String domPkg = pkgCounts.entrySet().stream()
                    .max(Comparator.comparingInt(Map.Entry<String, Integer>::getValue)
                            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                    .map(Map.Entry::getKey)
                    .orElse("");
            String hubLabel = shortSymbolName(nodes.get(hubIdx).label());
            String label = !domPkg.isEmpty() ? (shortPackage(domPkg) + " · " + hubLabel) : hubLabel;

            cidToCanonicalId[c] = commId;
            cidToDigest[c] = digest;
            cidToDomPkg[c] = domPkg;
            cidToHubId[c] = nodes.get(hubIdx).identity();
            cidToHubLabel[c] = nodes.get(hubIdx).label();
            cidToLabel[c] = label;
        }

        // Detect Package-vs-Community Drift ("Misplaced Types")
        var misplaced = new ArrayList<MisplacedType>();
        int[] cidMisplacedCount = new int[numComms];
        for (int u = 0; u < n; u++) {
            var node = nodes.get(u);
            int cu = partition[u];
            String domPkg = cidToDomPkg[cu];
            if (node.declaredPackage().isEmpty() || domPkg.isEmpty() || node.declaredPackage().equals(domPkg)) {
                continue;
            }
            int commEdges = 0;
            int pkgEdges = 0;
            for (int v : graph.neighbors[u]) {
                if (partition[v] == cu) commEdges++;
                if (node.declaredPackage().equals(nodes.get(v).declaredPackage())) pkgEdges++;
            }
            boolean drifted = (commEdges >= 2 && commEdges > pkgEdges)
                    || (commEdges >= 1 && pkgEdges == 0 && memberIndicesByCid.get(cu).size() >= 3);
            if (drifted) {
                cidMisplacedCount[cu]++;
                String rationale = String.format(Locale.ROOT,
                        "Declared in %s (%d intra-package edges), but clusters with %s (%d intra-community edges).",
                        node.declaredPackage(), pkgEdges, domPkg, commEdges);
                misplaced.add(new MisplacedType(
                        node.identity(),
                        node.label(),
                        node.declaredPackage(),
                        cidToCanonicalId[cu],
                        cidToLabel[cu],
                        domPkg,
                        commEdges,
                        pkgEdges,
                        rationale));
            }
        }
        misplaced.sort(Comparator.comparingInt((MisplacedType m) -> m.internalCommunityEdges() - m.declaredPackageEdges()).reversed()
                .thenComparing(MisplacedType::identity));

        // Aggregate Cross-Community Bridges
        record BridgeAcc(int fromCid, int toCid, int count, double weight, List<String> samples) {}
        var bridgeMap = new TreeMap<String, BridgeAcc>();
        int[] cidBridgeEdges = new int[numComms];
        for (var de : directedEdges) {
            int cu = partition[de.u()];
            int cv = partition[de.v()];
            if (cu == cv) continue;
            cidBridgeEdges[cu]++;
            cidBridgeEdges[cv]++;
            String key = cidToCanonicalId[cu] + "->" + cidToCanonicalId[cv];
            var prev = bridgeMap.get(key);
            String sample = shortSymbolName(nodes.get(de.u()).label()) + " -> " + shortSymbolName(nodes.get(de.v()).label()) + " (" + de.kind() + ")";
            if (prev == null) {
                var samples = new ArrayList<String>();
                samples.add(sample);
                bridgeMap.put(key, new BridgeAcc(cu, cv, 1, de.weight(), samples));
            } else {
                if (prev.samples().size() < 5 && !prev.samples().contains(sample)) {
                    prev.samples().add(sample);
                }
                bridgeMap.put(key, new BridgeAcc(cu, cv, prev.count() + 1, prev.weight() + de.weight(), prev.samples()));
            }
        }
        var bridges = bridgeMap.values().stream()
                .map(acc -> new CommunityBridge(
                        cidToCanonicalId[acc.fromCid()],
                        cidToLabel[acc.fromCid()],
                        cidToCanonicalId[acc.toCid()],
                        cidToLabel[acc.toCid()],
                        acc.count(),
                        BigDecimal.valueOf(acc.weight()).setScale(2, RoundingMode.HALF_UP),
                        acc.samples()))
                .sorted(Comparator.comparingInt(CommunityBridge::edgeCount).reversed()
                        .thenComparing(CommunityBridge::fromCommunityId)
                        .thenComparing(CommunityBridge::toCommunityId))
                .toList();

        // Build final sorted EmergentCommunity records + node->community map
        var communities = new ArrayList<EmergentCommunity>(numComms);
        var communityByNode = new LinkedHashMap<String, String>(n * 2);
        for (int c = 0; c < numComms; c++) {
            var members = memberIndicesByCid.get(c);
            var memberIds = members.stream().map(idx -> nodes.get(idx).identity()).sorted().toList();
            for (String mid : memberIds) communityByNode.put(mid, cidToCanonicalId[c]);
            int k = members.size();
            double density = (k <= 1) ? 1.0 : Math.min(1.0, (2.0 * cidInternalEdges[c]) / ((double) k * (k - 1)));
            communities.add(new EmergentCommunity(
                    cidToCanonicalId[c],
                    cidToDigest[c],
                    cidToLabel[c],
                    cidToDomPkg[c],
                    cidToHubId[c],
                    cidToHubLabel[c],
                    k,
                    cidInternalEdges[c],
                    cidExternalEdges[c],
                    BigDecimal.valueOf(density).setScale(4, RoundingMode.HALF_UP),
                    cidMisplacedCount[c],
                    cidBridgeEdges[c],
                    memberIds));
        }
        communities.sort(Comparator.comparingInt(EmergentCommunity::memberCount).reversed()
                .thenComparing(EmergentCommunity::id));

        return new Result(
                ALGORITHM,
                cacheKey,
                BigDecimal.valueOf(modularityQ).setScale(4, RoundingMode.HALF_UP),
                n,
                validEdgeCount,
                communities,
                misplaced,
                bridges,
                communityByNode);
    }

    private static String computeCacheKey(
            List<NodeInput> nodes,
            List<ResolvedDirectedEdge> directedEdges,
            double[] selfLoops,
            int validEdgeCount,
            Config cfg) {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        // Stream directly into SHA-256 without allocating a multi-megabyte StringBuilder.
        // Byte stream matches the exact length-prefixed canonical format.
        var scratch = new StringBuilder(256);
        scratch.append(ALGORITHM).append('|')
                .append(Double.doubleToLongBits(cfg.resolution())).append('|')
                .append(cfg.seed()).append('|')
                .append(Double.doubleToLongBits(cfg.maxClusterShare())).append('|')
                .append(cfg.minSplitSize()).append('|')
                .append(Double.doubleToLongBits(cfg.minCohesionForLarge())).append('|')
                .append(cfg.minLargeSize()).append('|')
                .append(cfg.maxSplitDepth()).append('|')
                .append(validEdgeCount).append('\n');
        flushDigest(md, scratch);
        for (int u = 0; u < nodes.size(); u++) {
            var node = nodes.get(u);
            scratch.append('N').append(u).append(':');
            appendLenPrefixed(scratch, node.identity());
            appendLenPrefixed(scratch, node.label());
            appendLenPrefixed(scratch, node.declaredPackage());
            appendLenPrefixed(scratch, node.kind());
            if (selfLoops[u] != 0.0) {
                scratch.append('S').append(Double.doubleToLongBits(selfLoops[u]));
            }
            scratch.append('\n');
            if (scratch.length() >= 4096) flushDigest(md, scratch);
        }
        for (var e : directedEdges) {
            scratch.append('D').append(e.u()).append('>').append(e.v()).append(':');
            appendLenPrefixed(scratch, e.kind());
            scratch.append(Double.doubleToLongBits(e.weight())).append('\n');
            if (scratch.length() >= 4096) flushDigest(md, scratch);
        }
        flushDigest(md, scratch);
        return "sha256:" + HexFormat.of().formatHex(md.digest());
    }

    private static void flushDigest(java.security.MessageDigest md, StringBuilder scratch) {
        if (scratch.isEmpty()) return;
        md.update(scratch.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        scratch.setLength(0);
    }

    private static void appendLenPrefixed(StringBuilder sb, String value) {
        String safe = value == null ? "" : value;
        sb.append(safe.length()).append(':').append(safe).append('|');
    }

    private static int renumberCanonical(int[] partition) {
        var remap = new HashMap<Integer, Integer>();
        int next = 0;
        for (int i = 0; i < partition.length; i++) {
            int old = partition[i];
            Integer mapped = remap.get(old);
            if (mapped == null) {
                mapped = next++;
                remap.put(old, mapped);
            }
            partition[i] = mapped;
        }
        return next;
    }

    private static void applyMapping(int[] rootToCurrent, int[] currentToNext) {
        for (int i = 0; i < rootToCurrent.length; i++) {
            rootToCurrent[i] = currentToNext[rootToCurrent[i]];
        }
    }

    private static String shortSymbolName(String label) {
        if (label == null || label.isBlank()) return "unknown";
        int slash = label.lastIndexOf('/');
        String s = slash >= 0 ? label.substring(slash + 1) : label;
        int dot = s.lastIndexOf('.');
        return dot >= 0 && dot < s.length() - 1 ? s.substring(dot + 1) : s;
    }

    private static String shortPackage(String pkg) {
        String[] parts = pkg.split("\\.");
        if (parts.length <= 2) return pkg;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
}
