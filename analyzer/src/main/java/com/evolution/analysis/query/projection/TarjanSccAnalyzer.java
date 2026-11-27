package com.evolution.analysis.query.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Deterministic, iterative (non-recursive) O(V + E) Tarjan Strongly Connected Components
 * and condensation-DAG levelization engine.
 *
 * <p>Uses explicit primitive traversal stacks so graphs with 50,000+ vertices never hit
 * {@link StackOverflowError}. All nodes and edges are canonically sorted prior to traversal,
 * and SCC blocks are emitted in topological condensation order:
 * <ul>
 *   <li>Low-level dependencies (condensation sinks, {@code level == 0}) appear first.</li>
 *   <li>High-level dependents (callers, larger {@code level}) appear last.</li>
 * </ul>
 *
 * <p>This ordering aligns directly with Dependency Structure Matrix (DSM) convention where
 * row {@code r} depends on column {@code c}:
 * <ul>
 *   <li>Every acyclic cross-SCC dependency satisfies {@code r > c} (strictly lower triangle).</li>
 *   <li>Every cyclic SCC occupies a contiguous diagonal block {@code [start..end]} containing
 *       at least one upper-triangle back-edge ({@code r < c}) or diagonal self-loop ({@code r == c}).</li>
 * </ul>
 */
public final class TarjanSccAnalyzer {

    /** Weighted directed edge input for SCC and DSM levelization analysis. */
    public record DirectedEdge(String source, String target, int weight) {
        public DirectedEdge {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            weight = Math.max(1, weight);
        }

        public DirectedEdge(String source, String target) {
            this(source, target, 1);
        }
    }

    /** One strongly connected component block in topological condensation order. */
    public record SccBlock(
            int index,
            List<String> members,
            boolean cyclic,
            int internalEdgeCount,
            int level
    ) {
        public SccBlock {
            members = List.copyOf(Objects.requireNonNull(members, "members"));
            if (index < 0) throw new IllegalArgumentException("Negative SCC block index");
            if (internalEdgeCount < 0) throw new IllegalArgumentException("Negative internal edge count");
            if (level < 0) throw new IllegalArgumentException("Negative condensation level");
        }
    }

    /** Complete deterministic SCC, cycle membership, topological order, and levelization result. */
    public record SccResult(
            List<SccBlock> blocks,
            Map<String, Integer> nodeToBlockIndex,
            List<String> topologicalOrder,
            Set<String> cyclicNodeIds,
            Set<String> cyclicEdgeKeys,
            double levelizationScore
    ) {
        public SccResult {
            blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
            nodeToBlockIndex = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(nodeToBlockIndex)));
            topologicalOrder = List.copyOf(Objects.requireNonNull(topologicalOrder, "topologicalOrder"));
            cyclicNodeIds = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(cyclicNodeIds)));
            cyclicEdgeKeys = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(cyclicEdgeKeys)));
            if (!Double.isFinite(levelizationScore)) {
                levelizationScore = 1.0;
            } else {
                levelizationScore = Math.max(0.0, Math.min(1.0, levelizationScore));
            }
        }

        /** CanonicalJson-safe decimal representation of {@link #levelizationScore()} at scale 4. */
        public BigDecimal levelizationScoreDecimal() {
            return BigDecimal.valueOf(levelizationScore).setScale(4, RoundingMode.HALF_UP);
        }
    }

    private TarjanSccAnalyzer() {}

    /** Canonical edge key matching {@code source + "->" + target}. */
    public static String edgeKey(String source, String target) {
        return Objects.requireNonNull(source, "source") + "->" + Objects.requireNonNull(target, "target");
    }

    /** Runs deterministic iterative Tarjan SCC over node IDs and weighted directed edges. */
    public static SccResult analyze(Collection<String> nodeIds, Collection<DirectedEdge> edges) {
        return analyze(nodeIds, edges, null);
    }

    /** Runs deterministic iterative Tarjan SCC over node IDs and an unweighted adjacency map. */
    public static SccResult analyzeAdjacency(
            Collection<String> nodeIds,
            Map<String, ? extends Collection<String>> adjacency) {
        var edgeList = new ArrayList<DirectedEdge>();
        if (adjacency != null) {
            for (var entry : adjacency.entrySet()) {
                String src = entry.getKey();
                if (src == null || entry.getValue() == null) continue;
                for (String dst : entry.getValue()) {
                    if (dst != null) {
                        edgeList.add(new DirectedEdge(src, dst, 1));
                    }
                }
            }
        }
        return analyze(nodeIds, edgeList, null);
    }

    /**
     * Runs deterministic iterative O(V + E) Tarjan SCC with cooperative cancellation checkpoints.
     *
     * <p>Edges whose source or target is not present in {@code nodeIds} are ignored. Parallel edges
     * between identical {@code (source, target)} pairs are aggregated by summing their weights.
     */
    public static SccResult analyze(
            Collection<String> nodeIds,
            Collection<DirectedEdge> edges,
            Runnable checkpoint) {
        Runnable tick = checkpoint == null ? () -> {} : checkpoint;
        tick.run();

        // 1. Canonical sort & deduplication of node IDs
        var uniqueNodes = new TreeSet<String>();
        if (nodeIds != null) {
            for (String id : nodeIds) {
                if (id != null) {
                    uniqueNodes.add(id);
                }
            }
        }
        int n = uniqueNodes.size();
        if (n == 0) {
            return new SccResult(List.of(), Map.of(), List.of(), Set.of(), Set.of(), 1.0);
        }

        String[] idOf = uniqueNodes.toArray(new String[0]);
        var indexOf = new HashMap<String, Integer>(Math.max(16, (int) (n / 0.75f) + 1));
        for (int i = 0; i < n; i++) {
            indexOf.put(idOf[i], i);
        }

        // 2. Canonical deduplicated weighted directed adjacency (O(E log deg))
        var adjWeights = new ArrayList<TreeMap<Integer, Long>>(n);
        for (int i = 0; i < n; i++) {
            adjWeights.add(new TreeMap<>());
        }
        long[] selfLoopWeight = new long[n];

        if (edges != null) {
            int seen = 0;
            for (DirectedEdge edge : edges) {
                if ((++seen & 4095) == 0) tick.run();
                if (edge == null) continue;
                Integer u = indexOf.get(edge.source());
                Integer v = indexOf.get(edge.target());
                if (u == null || v == null) continue;
                long w = edge.weight();
                if (u.intValue() == v.intValue()) {
                    selfLoopWeight[u] += w;
                } else {
                    adjWeights.get(u).merge(v, w, Long::sum);
                }
            }
        }

        int[][] adj = new int[n][];
        long[][] weights = new long[n][];
        for (int u = 0; u < n; u++) {
            var map = adjWeights.get(u);
            int deg = map.size();
            adj[u] = new int[deg];
            weights[u] = new long[deg];
            int k = 0;
            for (var entry : map.entrySet()) {
                adj[u][k] = entry.getKey();
                weights[u][k] = entry.getValue();
                k++;
            }
        }

        // 3. Iterative Tarjan SCC (zero recursion; primitive arrays only)
        int[] disc = new int[n];
        int[] low = new int[n];
        int[] rawCompOf = new int[n];
        Arrays.fill(disc, -1);
        Arrays.fill(rawCompOf, -1);
        boolean[] onStack = new boolean[n];
        int[] tarjanStack = new int[n];
        int sp = 0;

        int[] callNode = new int[n];
        int[] callEdgeIdx = new int[n];
        int timer = 0;
        var rawComponents = new ArrayList<int[]>();

        for (int root = 0; root < n; root++) {
            if (disc[root] != -1) continue;
            if ((root & 1023) == 0) tick.run();
            int depth = 0;
            callNode[0] = root;
            callEdgeIdx[0] = 0;
            disc[root] = low[root] = timer++;
            tarjanStack[sp++] = root;
            onStack[root] = true;

            while (depth >= 0) {
                int u = callNode[depth];
                int[] neighbors = adj[u];
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
                        if (disc[v] < low[u]) {
                            low[u] = disc[v];
                        }
                    }
                } else {
                    if (low[u] == disc[u]) {
                        int count = 0;
                        for (int k = sp - 1; k >= 0; k--) {
                            count++;
                            if (tarjanStack[k] == u) break;
                        }
                        int[] comp = new int[count];
                        int compId = rawComponents.size();
                        for (int k = 0; k < count; k++) {
                            int w = tarjanStack[--sp];
                            onStack[w] = false;
                            rawCompOf[w] = compId;
                            comp[k] = w;
                        }
                        Arrays.sort(comp);
                        rawComponents.add(comp);
                    }
                    depth--;
                    if (depth >= 0) {
                        int parent = callNode[depth];
                        if (low[u] < low[parent]) {
                            low[parent] = low[u];
                        }
                    }
                }
            }
        }
        tick.run();

        // 4. Build condensation DAG and compute exact longest-path levelization iteratively
        //    level[c] = 0 for sinks (no outgoing dependencies to other SCCs),
        //    level[c] = 1 + max_{c -> d} level[d] for dependents.
        int cCount = rawComponents.size();
        int[] remOut = new int[cCount];
        var revDag = new ArrayList<List<Integer>>(cCount);
        for (int c = 0; c < cCount; c++) {
            revDag.add(new ArrayList<>());
        }
        int[] internalEdgesPerRawComp = new int[cCount];
        int[] stamp = new int[cCount];

        for (int c = 0; c < cCount; c++) {
            int mark = c + 1;
            int outCount = 0;
            for (int u : rawComponents.get(c)) {
                if (selfLoopWeight[u] > 0L) {
                    internalEdgesPerRawComp[c]++;
                }
                for (int v : adj[u]) {
                    int tc = rawCompOf[v];
                    if (tc == c) {
                        internalEdgesPerRawComp[c]++;
                    } else if (stamp[tc] != mark) {
                        stamp[tc] = mark;
                        revDag.get(tc).add(c);
                        outCount++;
                    }
                }
            }
            remOut[c] = outCount;
        }

        int[] rawLevel = new int[cCount];
        int[] bfsQueue = new int[cCount];
        int qHead = 0;
        int qTail = 0;
        for (int c = 0; c < cCount; c++) {
            if (remOut[c] == 0) {
                bfsQueue[qTail++] = c;
            }
        }
        while (qHead < qTail) {
            int d = bfsQueue[qHead++];
            int nextLevel = rawLevel[d] + 1;
            for (int c : revDag.get(d)) {
                if (nextLevel > rawLevel[c]) {
                    rawLevel[c] = nextLevel;
                }
                if (--remOut[c] == 0) {
                    bfsQueue[qTail++] = c;
                }
            }
        }

        // 5. Canonical topological block ordering:
        //    level ASC (low-level dependencies first -> high-level dependents last),
        //    tie-broken by lexicographically smallest member ID ASC.
        Integer[] orderRaw = new Integer[cCount];
        for (int c = 0; c < cCount; c++) {
            orderRaw[c] = c;
        }
        Arrays.sort(orderRaw, (a, b) -> {
            int cmp = Integer.compare(rawLevel[a], rawLevel[b]);
            if (cmp != 0) return cmp;
            // Members inside each rawComponent are sorted by node index, which matches idOf ASC.
            return Integer.compare(rawComponents.get(a)[0], rawComponents.get(b)[0]);
        });

        var blocks = new ArrayList<SccBlock>(cCount);
        var nodeToBlockIndex = new LinkedHashMap<String, Integer>(Math.max(16, (int) (n / 0.75f) + 1));
        var topologicalOrder = new ArrayList<String>(n);
        var cyclicNodeIds = new LinkedHashSet<String>();
        int[] posOfNode = new int[n];
        int[] finalBlockOfNode = new int[n];
        boolean[] blockCyclic = new boolean[cCount];

        for (int blockIdx = 0; blockIdx < cCount; blockIdx++) {
            int rc = orderRaw[blockIdx];
            int[] comp = rawComponents.get(rc);
            boolean cyclic = comp.length > 1 || selfLoopWeight[comp[0]] > 0L;
            blockCyclic[blockIdx] = cyclic;
            var memberIds = new ArrayList<String>(comp.length);
            for (int u : comp) {
                String nid = idOf[u];
                memberIds.add(nid);
                posOfNode[u] = topologicalOrder.size();
                topologicalOrder.add(nid);
                nodeToBlockIndex.put(nid, blockIdx);
                finalBlockOfNode[u] = blockIdx;
                if (cyclic) {
                    cyclicNodeIds.add(nid);
                }
            }
            blocks.add(new SccBlock(
                    blockIdx,
                    memberIds,
                    cyclic,
                    internalEdgesPerRawComp[rc],
                    rawLevel[rc]));
        }

        // 6. Collect cyclic edge keys and compute weighted levelizationScore.
        //    In DSM layout (row r = source, col c = target):
        //      r > c  => strictly lower-triangle forward topological dependency
        //      r <= c => upper-triangle back-edge or diagonal self-loop
        var cyclicEdgeKeys = new LinkedHashSet<String>();
        long totalWeight = 0L;
        long forwardWeight = 0L;

        for (int u = 0; u < n; u++) {
            if (selfLoopWeight[u] > 0L) {
                totalWeight += selfLoopWeight[u];
                cyclicEdgeKeys.add(edgeKey(idOf[u], idOf[u]));
            }
            int bu = finalBlockOfNode[u];
            int pu = posOfNode[u];
            int[] neighbors = adj[u];
            long[] wts = weights[u];
            for (int i = 0; i < neighbors.length; i++) {
                int v = neighbors[i];
                long w = wts[i];
                totalWeight += w;
                if (pu > posOfNode[v]) {
                    forwardWeight += w;
                }
                if (bu == finalBlockOfNode[v] && blockCyclic[bu]) {
                    cyclicEdgeKeys.add(edgeKey(idOf[u], idOf[v]));
                }
            }
        }

        double levelizationScore = totalWeight == 0L
                ? 1.0
                : (double) forwardWeight / (double) totalWeight;

        return new SccResult(
                blocks,
                nodeToBlockIndex,
                topologicalOrder,
                cyclicNodeIds,
                cyclicEdgeKeys,
                levelizationScore);
    }
}
