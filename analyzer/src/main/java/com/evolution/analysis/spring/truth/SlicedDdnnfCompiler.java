package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.AffectedOutput;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.evidence.EvidenceRequirement;
import com.evolution.analysis.evidence.EvidenceSubject;
import com.evolution.analysis.evidence.ProviderObservationReference;
import com.evolution.analysis.spring.truth.IncrementalCdclReasoner.NormalizedPb;
import com.evolution.analysis.spring.truth.IncrementalCdclReasoner.PbConstraint;
import com.evolution.analysis.spring.universal.UniversalSpringEvidence;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;

/**
 * Pure-Java per-COI-slice Pseudo-Boolean d-DNNF compiler, exact {@code BigInteger} {@code #SAT}
 * model counter, and seeded deterministic uniform random sampler (Sundermann et al., IEEE TSE '26).
 *
 * <p>Architectural invariants upheld:
 * <ul>
 *   <li><b>I1 (Pure Java):</b> zero external C++/Rust/JNI binaries.</li>
 *   <li><b>I2 (Determinism):</b> deterministic connected-component decomposition, deterministic
 *       cut-set/degree variable ordering (smallest variable index tie-break), collision-free
 *       {@link PBComponentKey} memoization, and seeded exact {@code BigInteger} uniform sampling.</li>
 *   <li><b>I3 (Bounded Fail-Closed):</b> if compiled DAG nodes exceed {@link #MAX_DDNNF_NODES}
 *       or a coupled component exceeds {@code maxSliceVars}, compilation aborts cleanly with
 *       {@link #GAP_DDNNF_COUNT_BOUNDED} so callers preserve CDCL {@code MUST}/{@code MAY}/{@code NEVER}.</li>
 * </ul>
 */
public final class SlicedDdnnfCompiler {

    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("spring.sliced-ddnnf", "pb-ddnnf-v1");
    public static final int MAX_DDNNF_NODES = 100_000;
    public static final int DEFAULT_MAX_SLICE_VARS = 64;
    public static final String GAP_DDNNF_COUNT_BOUNDED = "DDNNF_COUNT_BOUNDED";

    private static final int EXPOSURE_RATIO_SCALE = 12;

    public record Limits(int maxNodes, int maxSliceVars) {
        public Limits {
            if (maxNodes < 1 || maxSliceVars < 1) {
                throw new IllegalArgumentException("Positive d-DNNF limits required");
            }
        }

        /**
         * Default bounded limits enforcing {@link #MAX_DDNNF_NODES} ({@code 100_000}) while allowing
         * cut-set component decomposition to solve COI slices with multi-value one-hot encodings.
         */
        public static Limits defaults() {
            return new Limits(MAX_DDNNF_NODES, Integer.MAX_VALUE);
        }

        /** Strict COI slice limits bounding both compiled nodes and per-component primary variables. */
        public static Limits strictSlice() {
            return new Limits(MAX_DDNNF_NODES, DEFAULT_MAX_SLICE_VARS);
        }
    }

    /**
     * Exact deterministic residual subproblem cache key:
     * {@code ((sortedUnassignedVars), (constraintId, remainingRhs))}.
     */
    public static final class PBComponentKey {
        private final int[] sortedUnassignedVars;
        private final int[] constraintIds;
        private final int[] remainingRhs;
        private final int hash;

        PBComponentKey(int[] sortedUnassignedVars, int[] constraintIds, int[] remainingRhs) {
            this.sortedUnassignedVars = sortedUnassignedVars;
            this.constraintIds = constraintIds;
            this.remainingRhs = remainingRhs;
            int h = Arrays.hashCode(sortedUnassignedVars);
            h = 31 * h + Arrays.hashCode(constraintIds);
            h = 31 * h + Arrays.hashCode(remainingRhs);
            this.hash = h;
        }

        public int[] sortedUnassignedVars() {
            return Arrays.copyOf(sortedUnassignedVars, sortedUnassignedVars.length);
        }

        public int[] constraintIds() {
            return Arrays.copyOf(constraintIds, constraintIds.length);
        }

        public int[] remainingRhs() {
            return Arrays.copyOf(remainingRhs, remainingRhs.length);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PBComponentKey other)) return false;
            return hash == other.hash
                    && Arrays.equals(sortedUnassignedVars, other.sortedUnassignedVars)
                    && Arrays.equals(constraintIds, other.constraintIds)
                    && Arrays.equals(remainingRhs, other.remainingRhs);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** Immutable PB/CNF problem over a closed COI slice (1-indexed variables {@code 1..numVars}). */
    public static final class SliceProblem {
        private final int numVars;
        private final Map<String, Integer> variableIds;
        private final String[] variableNames;
        private final boolean[] auxiliaryVars;
        private final List<PbConstraint> constraints;

        SliceProblem(int numVars, Map<String, Integer> variableIds, String[] variableNames,
                     boolean[] auxiliaryVars, List<PbConstraint> constraints) {
            if (numVars < 0) {
                throw new IllegalArgumentException("Negative numVars: " + numVars);
            }
            this.numVars = numVars;
            this.variableIds = Collections.unmodifiableMap(new TreeMap<>(variableIds));
            this.variableNames = Arrays.copyOf(variableNames, numVars + 1);
            this.auxiliaryVars = Arrays.copyOf(auxiliaryVars, numVars + 1);
            this.constraints = List.copyOf(constraints);
        }

        public static SliceProblem of(int numVars, List<String> names, List<PbConstraint> constraints) {
            Objects.requireNonNull(names, "names");
            Objects.requireNonNull(constraints, "constraints");
            var map = new TreeMap<String, Integer>();
            String[] arr = new String[numVars + 1];
            boolean[] aux = new boolean[numVars + 1];
            for (int i = 0; i < names.size() && i < numVars; i++) {
                String name = names.get(i);
                if (name != null && !name.isBlank()) {
                    map.put(name, i + 1);
                    arr[i + 1] = name;
                }
            }
            return new SliceProblem(numVars, map, arr, aux, constraints);
        }

        public static SliceProblem fromFormula(SymbolicConfiguration.Formula formula) {
            Objects.requireNonNull(formula, "formula");
            var builder = new SliceBuilder();
            builder.addFormulaRoot(formula);
            return builder.build();
        }

        public int variableCount() {
            return numVars;
        }

        public boolean isAuxiliaryVar(int varId) {
            return varId >= 1 && varId <= numVars && auxiliaryVars[varId];
        }

        public Map<String, Integer> variableIds() {
            return variableIds;
        }

        public String variableName(int varId) {
            return (varId >= 1 && varId <= numVars) ? variableNames[varId] : null;
        }

        public List<PbConstraint> constraints() {
            return constraints;
        }
    }

    /**
     * Incremental slice problem builder supporting native PB constraints and functional
     * bi-implication Tseitin encoding of {@link SymbolicConfiguration.Formula} DAGs
     * (preserving an exact 1-to-1 {@code #SAT} bijection with zero model inflation).
     */
    public static final class SliceBuilder {
        private int numVars;
        private final Map<String, Integer> variableIds = new TreeMap<>();
        private final List<String> varNameList = new ArrayList<>();
        private final List<Boolean> auxFlags = new ArrayList<>();
        private final Map<ContentDigest, Integer> formulaIds = new HashMap<>();
        private final List<PbConstraint> constraints = new ArrayList<>();

        public SliceBuilder() {
            varNameList.add(null); // 1-indexed sentinel
            auxFlags.add(Boolean.FALSE);
        }

        public int newAuxiliaryVar() {
            numVars++;
            varNameList.add(null);
            auxFlags.add(Boolean.TRUE);
            return numVars;
        }

        public int variable(String name) {
            Objects.requireNonNull(name, "name");
            Integer existing = variableIds.get(name);
            if (existing != null) {
                return existing;
            }
            var formula = SymbolicConfiguration.variable(name);
            return encodeFormula(formula);
        }

        public int variableId(String name) {
            return variableIds.getOrDefault(Objects.requireNonNull(name, "name"), 0);
        }

        public int encodeFormula(SymbolicConfiguration.Formula formula) {
            Objects.requireNonNull(formula, "formula");
            Integer known = formulaIds.get(formula.identity());
            if (known != null) {
                return known;
            }
            int id;
            if (formula.operator() == SymbolicConfiguration.Operator.VARIABLE) {
                Integer byName = variableIds.get(formula.variable());
                if (byName != null) {
                    formulaIds.put(formula.identity(), byName);
                    return byName;
                }
                numVars++;
                id = numVars;
                varNameList.add(formula.variable());
                auxFlags.add(Boolean.FALSE);
                variableIds.put(formula.variable(), id);
                formulaIds.put(formula.identity(), id);
                return id;
            }
            var children = formula.children();
            int[] childLits = new int[children.size()];
            for (int i = 0; i < children.size(); i++) {
                childLits[i] = encodeFormula(children.get(i));
            }
            // Optimize pure NOT of a literal without allocating extra gate when already encoded
            if (formula.operator() == SymbolicConfiguration.Operator.NOT) {
                int neg = -childLits[0];
                formulaIds.put(formula.identity(), neg);
                return neg;
            }
            id = newAuxiliaryVar();
            formulaIds.put(formula.identity(), id);
            switch (formula.operator()) {
                case TRUE -> addClause(id);
                case FALSE -> addClause(-id);
                case AND, OR -> {
                    boolean isAnd = formula.operator() == SymbolicConfiguration.Operator.AND;
                    int[] longClause = new int[childLits.length + 1];
                    longClause[0] = isAnd ? id : -id;
                    for (int i = 0; i < childLits.length; i++) {
                        addClause(isAnd ? -id : id, isAnd ? childLits[i] : -childLits[i]);
                        longClause[i + 1] = isAnd ? -childLits[i] : childLits[i];
                    }
                    addClause(longClause);
                }
                default -> throw new IllegalStateException("Unexpected operator: " + formula.operator());
            }
            return id;
        }

        public int addFormulaRoot(SymbolicConfiguration.Formula formula) {
            int lit = encodeFormula(formula);
            addClause(lit);
            return lit;
        }

        public void addClause(int... lits) {
            addPbConstraint(PbConstraint.clause(lits));
        }

        public void addPbConstraint(PbConstraint constraint) {
            Objects.requireNonNull(constraint, "constraint");
            for (int lit : constraint.lits()) {
                int v = Math.abs(lit);
                while (numVars < v) {
                    newAuxiliaryVar();
                }
            }
            constraints.add(constraint);
        }

        public void addAtLeastK(int k, int... lits) {
            addPbConstraint(PbConstraint.atLeastK(lits, k));
        }

        public void addAtMostK(int k, int... lits) {
            addPbConstraint(PbConstraint.atMostK(lits, k));
        }

        public void addExactly1(int... lits) {
            for (var pb : PbConstraint.exactly1(lits)) {
                addPbConstraint(pb);
            }
        }

        public void addExactlyK(int k, int... lits) {
            for (var pb : PbConstraint.exactlyK(lits, k)) {
                addPbConstraint(pb);
            }
        }

        public SliceProblem build() {
            String[] names = varNameList.toArray(new String[0]);
            boolean[] aux = new boolean[numVars + 1];
            for (int i = 1; i <= numVars; i++) {
                aux[i] = auxFlags.get(i);
            }
            return new SliceProblem(numVars, variableIds, names, aux, constraints);
        }
    }

    /** Symmetric {@code (witness+, witness-)} pair uniformly sampled from a {@code MAY} slice. */
    public record SymmetricWitnessPair(
            Map<String, Boolean> witnessPositive,
            Map<String, Boolean> witnessNegative,
            BigInteger trueWorldCount,
            BigInteger falseWorldCount,
            BigInteger validWorldCount,
            BigDecimal mayExposureRatio) {
        public SymmetricWitnessPair {
            witnessPositive = Collections.unmodifiableMap(new TreeMap<>(witnessPositive));
            witnessNegative = Collections.unmodifiableMap(new TreeMap<>(witnessNegative));
            Objects.requireNonNull(trueWorldCount);
            Objects.requireNonNull(falseWorldCount);
            Objects.requireNonNull(validWorldCount);
            Objects.requireNonNull(mayExposureRatio);
        }
    }

    /** Result of per-COI-slice d-DNNF compilation with explicit bounded fallback status (I3). */
    public record CompileResult(
            Optional<CompiledDdnnf> ddnnf,
            boolean bounded,
            Optional<String> gapReasonCode,
            int compiledNodeCount,
            int cacheHits,
            long elapsedNanos) {
        public CompileResult {
            Objects.requireNonNull(ddnnf);
            Objects.requireNonNull(gapReasonCode);
        }

        public boolean isComplete() {
            return !bounded && ddnnf.isPresent();
        }
    }

    /**
     * Compiled smooth, decomposable, deterministic d-DNNF DAG supporting linear-time
     * {@code O(|G|)} {@code BigInteger} model counting, conditioned {@code #SAT}, exact
     * {@code mayExposureRatio}, seeded uniform random sampling, and symmetric {@code MAY} witness pairs.
     */
    public static final class CompiledDdnnf {
        static final byte KIND_FALSE = 0;
        static final byte KIND_TRUE = 1;
        static final byte KIND_LITERAL = 2;
        static final byte KIND_FREE_VARS = 3;
        static final byte KIND_AND = 4;
        static final byte KIND_OR = 5;

        private final int numVars;
        private final Map<String, Integer> variableIds;
        private final String[] variableNames;
        private final int rootNodeId;
        private final int nodeCount;
        private final byte[] nodeKind;
        private final int[] nodeDataA;     // LITERAL: lit; OR: decisionVar
        private final int[] nodeDataB;     // OR: highChild
        private final int[] nodeDataC;     // OR: lowChild
        private final int[][] nodeChildren; // AND: children; FREE_VARS: vars
        private final BigInteger[] modelCounts;

        CompiledDdnnf(
                int numVars,
                Map<String, Integer> variableIds,
                String[] variableNames,
                int rootNodeId,
                int nodeCount,
                byte[] nodeKind,
                int[] nodeDataA,
                int[] nodeDataB,
                int[] nodeDataC,
                int[][] nodeChildren) {
            this.numVars = numVars;
            this.variableIds = Collections.unmodifiableMap(new TreeMap<>(variableIds));
            this.variableNames = Arrays.copyOf(variableNames, numVars + 1);
            this.rootNodeId = rootNodeId;
            this.nodeCount = nodeCount;
            this.nodeKind = Arrays.copyOf(nodeKind, nodeCount);
            this.nodeDataA = Arrays.copyOf(nodeDataA, nodeCount);
            this.nodeDataB = Arrays.copyOf(nodeDataB, nodeCount);
            this.nodeDataC = Arrays.copyOf(nodeDataC, nodeCount);
            this.nodeChildren = Arrays.copyOf(nodeChildren, nodeCount);
            this.modelCounts = computeCounts(null);
        }

        public int nodeCount() {
            return nodeCount;
        }

        public int rootNodeId() {
            return rootNodeId;
        }

        public int variableCount() {
            return numVars;
        }

        public Map<String, Integer> variableIds() {
            return variableIds;
        }

        /** Linear-time {@code O(|G|)} exact {@code BigInteger} total model count. */
        public BigInteger countModels() {
            return modelCounts[rootNodeId];
        }

        /** Linear-time {@code O(|G|)} exact {@code BigInteger} model count under literal assumptions. */
        public BigInteger countModelsUnder(int... assumptionLits) {
            if (assumptionLits == null || assumptionLits.length == 0) {
                return countModels();
            }
            byte[] assumed = parseAssumptions(assumptionLits);
            if (assumed == null) {
                return BigInteger.ZERO;
            }
            return computeCounts(assumed)[rootNodeId];
        }

        /** Computes exact {@code mayExposureRatio = count(slice && phiLit) / count(slice)}. */
        public Optional<BigDecimal> exposureRatio(int phiLit) {
            BigInteger total = countModels();
            if (total.signum() == 0) {
                return Optional.empty();
            }
            BigInteger yes = countModelsUnder(phiLit);
            return Optional.of(computeExposureRatio(yes, total));
        }

        /** Classifies {@code phiLit} over the compiled slice into {@code MUST}/{@code MAY}/{@code NEVER}/{@code UNKNOWN}. */
        public TruthRegionEvaluation.Classification classify(int phiLit) {
            BigInteger total = countModels();
            if (total.signum() == 0) {
                return TruthRegionEvaluation.Classification.UNKNOWN;
            }
            BigInteger yes = countModelsUnder(phiLit);
            if (yes.signum() == 0) {
                return TruthRegionEvaluation.Classification.NEVER;
            }
            if (yes.equals(total)) {
                return TruthRegionEvaluation.Classification.MUST;
            }
            return TruthRegionEvaluation.Classification.MAY;
        }

        /**
         * Seeded deterministic uniform random sampler weighted by exact {@code BigInteger}
         * left/right branch model counts (zero floating-point bias, strictly I2-deterministic).
         */
        public List<Map<String, Boolean>> sampleUniform(int count, long seed) {
            return sampleUniformUnder(count, seed);
        }

        /**
         * Seeded deterministic uniform random sampler conditioned on {@code assumptionLits}.
         */
        public List<Map<String, Boolean>> sampleUniformUnder(int count, long seed, int... assumptionLits) {
            if (count < 0) {
                throw new IllegalArgumentException("Negative sample count: " + count);
            }
            if (count == 0) {
                return List.of();
            }
            byte[] assumed = (assumptionLits == null || assumptionLits.length == 0)
                    ? null : parseAssumptions(assumptionLits);
            if (assumptionLits != null && assumptionLits.length > 0 && assumed == null) {
                return List.of();
            }
            BigInteger[] activeCounts = assumed == null ? modelCounts : computeCounts(assumed);
            if (activeCounts[rootNodeId].signum() == 0) {
                return List.of();
            }
            var rng = new Random(seed);
            var samples = new ArrayList<Map<String, Boolean>>(count);
            int[] stack = new int[Math.max(16, Math.min(nodeCount, 4096))];
            for (int s = 0; s < count; s++) {
                boolean[] assign = new boolean[numVars + 1];
                int top = 0;
                stack[top++] = rootNodeId;
                while (top > 0) {
                    int id = stack[--top];
                    switch (nodeKind[id]) {
                        case KIND_TRUE -> { /* no-op */ }
                        case KIND_FALSE -> throw new IllegalStateException("Reached FALSE node with non-zero count");
                        case KIND_LITERAL -> {
                            int lit = nodeDataA[id];
                            assign[Math.abs(lit)] = lit > 0;
                        }
                        case KIND_FREE_VARS -> {
                            int[] vars = nodeChildren[id];
                            for (int v : vars) {
                                if (assumed != null && assumed[v] != 0) {
                                    assign[v] = assumed[v] == 1;
                                } else {
                                    assign[v] = rng.nextBoolean();
                                }
                            }
                        }
                        case KIND_AND -> {
                            int[] children = nodeChildren[id];
                            if (top + children.length > stack.length) {
                                stack = Arrays.copyOf(stack, Math.max(stack.length * 2, top + children.length));
                            }
                            for (int i = children.length - 1; i >= 0; i--) {
                                stack[top++] = children[i];
                            }
                        }
                        case KIND_OR -> {
                            int var = nodeDataA[id];
                            int high = nodeDataB[id];
                            int low = nodeDataC[id];
                            boolean chooseHigh;
                            if (assumed != null && assumed[var] != 0) {
                                chooseHigh = assumed[var] == 1;
                            } else {
                                BigInteger ch = activeCounts[high];
                                BigInteger cl = activeCounts[low];
                                if (ch.signum() == 0) {
                                    chooseHigh = false;
                                } else if (cl.signum() == 0) {
                                    chooseHigh = true;
                                } else {
                                    BigInteger r = nextBigIntegerBelow(ch.add(cl), rng);
                                    chooseHigh = r.compareTo(ch) < 0;
                                }
                            }
                            assign[var] = chooseHigh;
                            if (top + 1 > stack.length) {
                                stack = Arrays.copyOf(stack, stack.length * 2);
                            }
                            stack[top++] = chooseHigh ? high : low;
                        }
                        default -> throw new IllegalStateException("Unknown d-DNNF node kind: " + nodeKind[id]);
                    }
                }
                var named = new TreeMap<String, Boolean>();
                for (var entry : variableIds.entrySet()) {
                    named.put(entry.getKey(), assign[entry.getValue()]);
                }
                samples.add(Collections.unmodifiableMap(named));
            }
            return List.copyOf(samples);
        }

        /**
         * Samples a deterministic symmetric {@code (witness+, witness-)} pair for a {@code MAY}
         * literal {@code phiLit}. Returns {@link Optional#empty()} if {@code phiLit} is not {@code MAY}.
         */
        public Optional<SymmetricWitnessPair> sampleSymmetricMayWitnessPair(int phiLit, long seed) {
            BigInteger total = countModels();
            if (total.signum() == 0) {
                return Optional.empty();
            }
            BigInteger yesCount = countModelsUnder(phiLit);
            BigInteger noCount = countModelsUnder(-phiLit);
            if (yesCount.signum() == 0 || noCount.signum() == 0) {
                return Optional.empty();
            }
            var pos = sampleUniformUnder(1, seed ^ 0x9E3779B97F4A7C15L, phiLit);
            var neg = sampleUniformUnder(1, seed ^ 0xBF58476D1CE4E5B9L, -phiLit);
            if (pos.isEmpty() || neg.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new SymmetricWitnessPair(
                    pos.getFirst(),
                    neg.getFirst(),
                    yesCount,
                    noCount,
                    total,
                    computeExposureRatio(yesCount, total)));
        }

        /** Evaluates exact model counts bottom-up in strictly linear {@code O(|G|)} time. */
        private BigInteger[] computeCounts(byte[] assumed) {
            BigInteger[] counts = new BigInteger[nodeCount];
            for (int id = 0; id < nodeCount; id++) {
                switch (nodeKind[id]) {
                    case KIND_FALSE -> counts[id] = BigInteger.ZERO;
                    case KIND_TRUE -> counts[id] = BigInteger.ONE;
                    case KIND_LITERAL -> {
                        int lit = nodeDataA[id];
                        int v = Math.abs(lit);
                        byte sign = (byte) (lit > 0 ? 1 : -1);
                        if (assumed != null && assumed[v] != 0 && assumed[v] != sign) {
                            counts[id] = BigInteger.ZERO;
                        } else {
                            counts[id] = BigInteger.ONE;
                        }
                    }
                    case KIND_FREE_VARS -> {
                        int[] vars = nodeChildren[id];
                        int free = 0;
                        if (assumed == null) {
                            free = vars.length;
                        } else {
                            for (int v : vars) {
                                if (assumed[v] == 0) {
                                    free++;
                                }
                            }
                        }
                        counts[id] = BigInteger.ONE.shiftLeft(free);
                    }
                    case KIND_AND -> {
                        BigInteger prod = BigInteger.ONE;
                        for (int child : nodeChildren[id]) {
                            BigInteger cc = counts[child];
                            if (cc.signum() == 0) {
                                prod = BigInteger.ZERO;
                                break;
                            }
                            prod = prod.multiply(cc);
                        }
                        counts[id] = prod;
                    }
                    case KIND_OR -> {
                        int var = nodeDataA[id];
                        int high = nodeDataB[id];
                        int low = nodeDataC[id];
                        if (assumed != null && assumed[var] == 1) {
                            counts[id] = counts[high];
                        } else if (assumed != null && assumed[var] == -1) {
                            counts[id] = counts[low];
                        } else {
                            counts[id] = counts[high].add(counts[low]);
                        }
                    }
                    default -> throw new IllegalStateException("Unknown d-DNNF node kind: " + nodeKind[id]);
                }
            }
            return counts;
        }

        private byte[] parseAssumptions(int[] assumptionLits) {
            byte[] assumed = new byte[numVars + 1];
            for (int lit : assumptionLits) {
                int v = Math.abs(lit);
                if (v == 0 || v > numVars) {
                    throw new IllegalArgumentException("Assumption literal out of bounds: " + lit);
                }
                byte sign = (byte) (lit > 0 ? 1 : -1);
                if (assumed[v] != 0 && assumed[v] != sign) {
                    return null;
                }
                assumed[v] = sign;
            }
            return assumed;
        }

        private static BigInteger nextBigIntegerBelow(BigInteger bound, Random rng) {
            int bitLen = bound.bitLength();
            BigInteger r;
            do {
                r = new BigInteger(bitLen, rng);
            } while (r.compareTo(bound) >= 0);
            return r;
        }
    }

    /** Exact normalized {@code BigDecimal} exposure ratio {@code trueCount / totalCount} in {@code [0, 1]}. */
    public static BigDecimal computeExposureRatio(BigInteger trueCount, BigInteger totalCount) {
        Objects.requireNonNull(trueCount, "trueCount");
        Objects.requireNonNull(totalCount, "totalCount");
        if (totalCount.signum() <= 0) {
            throw new IllegalArgumentException("Positive totalCount required");
        }
        if (trueCount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (trueCount.compareTo(totalCount) >= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal ratio = new BigDecimal(trueCount)
                .divide(new BigDecimal(totalCount), EXPOSURE_RATIO_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        // Preserve strict open-interval (0, 1) semantics for genuine MAY ratios even on tiny fractions
        if (ratio.signum() == 0) {
            ratio = BigDecimal.ONE.movePointLeft(EXPOSURE_RATIO_SCALE);
        } else if (ratio.compareTo(BigDecimal.ONE) >= 0) {
            ratio = BigDecimal.ONE.subtract(BigDecimal.ONE.movePointLeft(EXPOSURE_RATIO_SCALE));
        }
        return ratio;
    }

    /** Builds a typed {@link CapabilityGapRecord} for {@code DDNNF_COUNT_BOUNDED} fallback (I3). */
    public static CapabilityGapRecord boundedCountGap(
            SnapshotIdentity snapshot,
            ContentDigest inputIdentity,
            String subjectLabel,
            int maxNodes,
            int maxSliceVars) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(inputIdentity, "inputIdentity");
        String note = "Per-COI-slice d-DNNF model counting exceeded bound (maxNodes="
                + maxNodes + ", maxSliceVars=" + maxSliceVars
                + "); preserved exact CDCL MUST/MAY/NEVER classification without quantitative ratio.";
        var obs = ProviderObservationReference.create(
                PROVIDER,
                "spring.ddnnf.bounded",
                inputIdentity,
                ContentDigest.sha256Utf8(GAP_DDNNF_COUNT_BOUNDED + ":" + subjectLabel));
        var subject = EvidenceSubject.observation(obs.identity());
        var req = new EvidenceRequirement(
                EvidenceRequirement.Kind.CONFIGURATION,
                "spring.ddnnf.ddnnf-count-bounded",
                List.of(subject),
                EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of(note));
        return CapabilityGapRecord.create(
                UniversalSpringEvidence.CATALOG,
                EvidenceContext.forSnapshot(snapshot),
                PROVIDER,
                "spring.sliced-ddnnf",
                GAP_DDNNF_COUNT_BOUNDED,
                subject,
                List.of(),
                List.of(obs),
                List.of(req),
                List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.sliced-ddnnf")),
                List.of(),
                List.of(),
                List.of(note));
    }

    /** Compiles a closed COI slice into a smooth d-DNNF DAG using default limits ({@code 100_000} nodes). */
    public static CompileResult compile(SliceProblem problem) {
        return compile(problem, Limits.defaults());
    }

    /** Compiles a closed COI slice into a smooth d-DNNF DAG with bounded node & component safeguards. */
    public static CompileResult compile(SliceProblem problem, Limits limits) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(limits, "limits");
        return new CompilerSession(problem, limits).run();
    }

    private static final class NodeLimitExceeded extends RuntimeException {}

    private static final class CompilerSession {
        private final SliceProblem problem;
        private final Limits limits;
        private final int numVars;

        // Normalized constraints
        private final int numCons;
        private final int[][] conLits;
        private final int[][] conWeights;
        private final int[] remainingRhs;
        private final int[] maxRemainingWeight;
        private final boolean topLevelUnsat;

        // Var -> incident constraints adjacency (with literal & weight stored inline)
        private final int[][] varConIds;
        private final int[][] varConLits;
        private final int[][] varConWeights;

        // Mutable search state with trail undo
        private final byte[] values;
        private final int[] trail;
        private int trailSize;

        // PB-BCP queue (dynamically resizable for multi-wave PB cascades where constraints re-enter)
        private int[] bcpQueue;
        private final boolean[] inBcpQueue;
        private int bcpTail;

        // Reusable epoch-stamped scratch buffers for connected-component BFS & cut-set scoring
        private final int[] varSubStamp;
        private final int[] varVisitStamp;
        private final int[] varHasActiveConStamp;
        private final int[] conActiveStamp;
        private final int[] conVisitStamp;
        private final int[] varScoreStamp;
        private final int[] varScoreBuf;
        private final int[] varDegBuf;
        private int stampEpoch;
        private final int[] bfsQueue;

        // Memoization cache: PBComponentKey -> d-DNNF node id
        private final Map<PBComponentKey, Integer> componentCache = new HashMap<>(256);
        private int cacheHits;
        private long recursiveCalls;
        private final long maxRecursiveCalls;

        // Compact d-DNNF DAG arena
        private int nodeCapacity = 256;
        private int nodeCount = 2; // 0 = FALSE, 1 = TRUE
        private byte[] nodeKind = new byte[nodeCapacity];
        private int[] nodeDataA = new int[nodeCapacity];
        private int[] nodeDataB = new int[nodeCapacity];
        private int[] nodeDataC = new int[nodeCapacity];
        private int[][] nodeChildren = new int[nodeCapacity][];
        private final int[] litNodeCache;

        CompilerSession(SliceProblem problem, Limits limits) {
            this.problem = problem;
            this.limits = limits;
            this.numVars = problem.variableCount();
            this.maxRecursiveCalls = Math.max(1_024L, (long) limits.maxNodes() * 4L);

            nodeKind[0] = CompiledDdnnf.KIND_FALSE;
            nodeKind[1] = CompiledDdnnf.KIND_TRUE;
            litNodeCache = new int[(numVars + 1) * 2];
            Arrays.fill(litNodeCache, -1);

            var normList = new ArrayList<NormalizedPb>(problem.constraints().size());
            boolean unsat = false;
            for (var raw : problem.constraints()) {
                NormalizedPb n = raw.normalize();
                if (n.conflict) {
                    unsat = true;
                    break;
                }
                if (!n.tautology) {
                    normList.add(n);
                }
            }
            this.topLevelUnsat = unsat;
            this.numCons = unsat ? 0 : normList.size();
            this.conLits = new int[numCons][];
            this.conWeights = new int[numCons][];
            this.remainingRhs = new int[numCons];
            this.maxRemainingWeight = new int[numCons];

            int[] varDeg = new int[numVars + 1];
            for (int c = 0; c < numCons; c++) {
                NormalizedPb n = normList.get(c);
                conLits[c] = n.lits;
                conWeights[c] = n.weights;
                remainingRhs[c] = Math.toIntExact(n.rhs);
                maxRemainingWeight[c] = Math.toIntExact(n.maxSum);
                for (int lit : n.lits) {
                    varDeg[Math.abs(lit)]++;
                }
            }

            this.varConIds = new int[numVars + 1][];
            this.varConLits = new int[numVars + 1][];
            this.varConWeights = new int[numVars + 1][];
            int[] fillPos = new int[numVars + 1];
            for (int v = 1; v <= numVars; v++) {
                varConIds[v] = new int[varDeg[v]];
                varConLits[v] = new int[varDeg[v]];
                varConWeights[v] = new int[varDeg[v]];
            }
            for (int c = 0; c < numCons; c++) {
                int[] ls = conLits[c];
                int[] ws = conWeights[c];
                for (int i = 0; i < ls.length; i++) {
                    int v = Math.abs(ls[i]);
                    int pos = fillPos[v]++;
                    varConIds[v][pos] = c;
                    varConLits[v][pos] = ls[i];
                    varConWeights[v][pos] = ws[i];
                }
            }

            this.values = new byte[numVars + 1];
            this.trail = new int[numVars + 1];
            this.bcpQueue = new int[Math.max(16, numCons + 1)];
            this.inBcpQueue = new boolean[numCons];
            this.varSubStamp = new int[numVars + 1];
            this.varVisitStamp = new int[numVars + 1];
            this.varHasActiveConStamp = new int[numVars + 1];
            this.conActiveStamp = new int[numCons];
            this.conVisitStamp = new int[numCons];
            this.varScoreStamp = new int[numVars + 1];
            this.varScoreBuf = new int[numVars + 1];
            this.varDegBuf = new int[numVars + 1];
            this.bfsQueue = new int[Math.max(16, numVars + numCons + 4)];
        }

        CompileResult run() {
            long t0 = System.nanoTime();
            if (topLevelUnsat) {
                var compiled = new CompiledDdnnf(
                        numVars, problem.variableIds(), extractVarNames(),
                        0, nodeCount, nodeKind, nodeDataA, nodeDataB, nodeDataC, nodeChildren);
                return new CompileResult(Optional.of(compiled), false, Optional.empty(),
                        nodeCount, 0, System.nanoTime() - t0);
            }
            try {
                int[] allVars = new int[numVars];
                for (int i = 0; i < numVars; i++) {
                    allVars[i] = i + 1;
                }
                int[] allCons = new int[numCons];
                for (int i = 0; i < numCons; i++) {
                    allCons[i] = i;
                }
                int rootId = compileSubproblem(allVars, allCons, 0);
                var compiled = new CompiledDdnnf(
                        numVars, problem.variableIds(), extractVarNames(),
                        rootId, nodeCount, nodeKind, nodeDataA, nodeDataB, nodeDataC, nodeChildren);
                return new CompileResult(Optional.of(compiled), false, Optional.empty(),
                        nodeCount, cacheHits, System.nanoTime() - t0);
            } catch (NodeLimitExceeded bounded) {
                return new CompileResult(Optional.empty(), true, Optional.of(GAP_DDNNF_COUNT_BOUNDED),
                        nodeCount, cacheHits, System.nanoTime() - t0);
            }
        }

        private String[] extractVarNames() {
            String[] names = new String[numVars + 1];
            for (int v = 1; v <= numVars; v++) {
                names[v] = problem.variableName(v);
            }
            return names;
        }

        private int compileSubproblem(int[] subVars, int[] subCons, int decisionLit) {
            if (++recursiveCalls > maxRecursiveCalls) {
                throw new NodeLimitExceeded();
            }
            int savedTrail = trailSize;
            int forcedStart;
            int qHead = 0;
            bcpTail = 0;

            try {
                if (decisionLit != 0) {
                    if (!assignLit(decisionLit)) {
                        clearQueue(qHead, bcpTail);
                        return 0; // FALSE
                    }
                    forcedStart = trailSize;
                } else {
                    forcedStart = trailSize;
                    // Seed PB-BCP queue with any initially conflicting or unit-forcing constraints
                    for (int c : subCons) {
                        if (remainingRhs[c] <= 0) {
                            continue;
                        }
                        if (remainingRhs[c] > maxRemainingWeight[c]) {
                            clearQueue(qHead, bcpTail);
                            return 0; // FALSE
                        }
                        if (maxRemainingWeight[c] - conWeights[c][0] < remainingRhs[c]) {
                            enqueueBcp(c);
                        }
                    }
                }

                // Exhaustive Chai-Kuehlmann PB-BCP to fixpoint
                while (qHead < bcpTail) {
                    int c = bcpQueue[qHead++];
                    inBcpQueue[c] = false;
                    if (remainingRhs[c] <= 0) {
                        continue;
                    }
                    if (remainingRhs[c] > maxRemainingWeight[c]) {
                        clearQueue(qHead, bcpTail);
                        return 0; // FALSE
                    }
                    int margin = maxRemainingWeight[c] - remainingRhs[c];
                    int[] ls = conLits[c];
                    int[] ws = conWeights[c];
                    for (int j = 0; j < ls.length && ws[j] > margin; j++) {
                        int lit = ls[j];
                        if (values[Math.abs(lit)] == 0) {
                            if (!assignLit(lit)) {
                                clearQueue(qHead, bcpTail);
                                return 0; // FALSE
                            }
                            if (remainingRhs[c] <= 0) {
                                break;
                            }
                        }
                    }
                }

                // Record forced literals produced by PB-BCP at this node (excluding decisionLit)
                int forcedCount = trailSize - forcedStart;
                int[] forcedNodes = forcedCount > 0 ? new int[forcedCount] : null;
                for (int i = 0; i < forcedCount; i++) {
                    forcedNodes[i] = mkLiteral(trail[forcedStart + i]);
                }

                // Decompose remaining unassigned subVars & unsatisfied subCons into connected components
                int epoch = ++stampEpoch;
                int unassignedCount = 0;
                for (int v : subVars) {
                    if (values[v] == 0) {
                        varSubStamp[v] = epoch;
                        unassignedCount++;
                    }
                }
                if (unassignedCount == 0) {
                    return combineAnd(forcedNodes, null, null, 0);
                }

                int activeConCount = 0;
                for (int c : subCons) {
                    if (remainingRhs[c] > 0) {
                        conActiveStamp[c] = epoch;
                        activeConCount++;
                        for (int lit : conLits[c]) {
                            int u = Math.abs(lit);
                            if (varSubStamp[u] == epoch) {
                                varHasActiveConStamp[u] = epoch;
                            }
                        }
                    }
                }
                if (activeConCount == 0) {
                    int[] freeVars = new int[unassignedCount];
                    int fi = 0;
                    for (int v : subVars) {
                        if (values[v] == 0) {
                            freeVars[fi++] = v;
                        }
                    }
                    return combineAnd(forcedNodes, freeVars, null, 0);
                }

                int[] freeBuf = new int[unassignedCount];
                int freeCount = 0;
                var compVarsList = new ArrayList<int[]>(4);
                var compConsList = new ArrayList<int[]>(4);

                for (int startVar : subVars) {
                    if (values[startVar] != 0 || varVisitStamp[startVar] == epoch) {
                        continue;
                    }
                    if (varHasActiveConStamp[startVar] != epoch) {
                        varVisitStamp[startVar] = epoch;
                        freeBuf[freeCount++] = startVar;
                        continue;
                    }

                    // BFS bipartite component discovery (var <-> active constraint)
                    int bHead = 0;
                    int bTail = 0;
                    varVisitStamp[startVar] = epoch;
                    bfsQueue[bTail++] = startVar; // positive = var, negative = -(c + 1)
                    int vCount = 0;
                    int cCount = 0;
                    while (bHead < bTail) {
                        int item = bfsQueue[bHead++];
                        if (item > 0) {
                            vCount++;
                            for (int c : varConIds[item]) {
                                if (conActiveStamp[c] == epoch && conVisitStamp[c] != epoch) {
                                    conVisitStamp[c] = epoch;
                                    bfsQueue[bTail++] = -(c + 1);
                                }
                            }
                        } else {
                            cCount++;
                            int c = -item - 1;
                            for (int lit : conLits[c]) {
                                int u = Math.abs(lit);
                                if (varSubStamp[u] == epoch && varVisitStamp[u] != epoch) {
                                    varVisitStamp[u] = epoch;
                                    bfsQueue[bTail++] = u;
                                }
                            }
                        }
                    }

                    int[] cv = new int[vCount];
                    int[] cc = new int[cCount];
                    int vi = 0;
                    int ci = 0;
                    for (int i = 0; i < bTail; i++) {
                        int item = bfsQueue[i];
                        if (item > 0) {
                            cv[vi++] = item;
                        } else {
                            cc[ci++] = -item - 1;
                        }
                    }
                    Arrays.sort(cv);
                    Arrays.sort(cc);
                    if (limits.maxSliceVars() < Integer.MAX_VALUE) {
                        int primaryVarsInComp = 0;
                        for (int v : cv) {
                            if (!problem.isAuxiliaryVar(v)) {
                                primaryVarsInComp++;
                            }
                        }
                        if (primaryVarsInComp > limits.maxSliceVars()) {
                            throw new NodeLimitExceeded();
                        }
                    }
                    compVarsList.add(cv);
                    compConsList.add(cc);
                }

                int numComps = compVarsList.size();
                int[] compNodes = numComps > 0 ? new int[numComps] : null;
                for (int r = 0; r < numComps; r++) {
                    int[] cv = compVarsList.get(r);
                    int[] cc = compConsList.get(r);
                    PBComponentKey key = null;
                    int compNode;
                    boolean useCache = cv.length >= 3;
                    if (useCache) {
                        int[] remRhsSnapshot = new int[cc.length];
                        for (int i = 0; i < cc.length; i++) {
                            remRhsSnapshot[i] = remainingRhs[cc[i]];
                        }
                        key = new PBComponentKey(cv, cc, remRhsSnapshot);
                        Integer cached = componentCache.get(key);
                        if (cached != null) {
                            cacheHits++;
                            compNode = cached;
                            if (compNode == 0) {
                                return 0;
                            }
                            compNodes[r] = compNode;
                            continue;
                        }
                    }
                    int decVar = pickDecisionVar(cv, cc);
                    int[] nextVars = removeVarSorted(cv, decVar);
                    int high = compileSubproblem(nextVars, cc, decVar);
                    int low = compileSubproblem(nextVars, cc, -decVar);
                    if (high == 0 && low == 0) {
                        compNode = 0;
                    } else if (high == 0) {
                        compNode = combineAndPair(mkLiteral(-decVar), low);
                    } else if (low == 0) {
                        compNode = combineAndPair(mkLiteral(decVar), high);
                    } else {
                        compNode = mkOr(decVar, high, low);
                    }
                    if (useCache) {
                        componentCache.put(key, compNode);
                    }
                    if (compNode == 0) {
                        return 0; // Short-circuit UNSAT component
                    }
                    compNodes[r] = compNode;
                }

                int[] freeVars = freeCount > 0 ? Arrays.copyOf(freeBuf, freeCount) : null;
                return combineAnd(forcedNodes, freeVars, compNodes, numComps);
            } finally {
                undoTo(savedTrail);
            }
        }

        private boolean assignLit(int lit) {
            int v = Math.abs(lit);
            byte sign = (byte) (lit > 0 ? 1 : -1);
            if (values[v] != 0) {
                return values[v] == sign;
            }
            values[v] = sign;
            trail[trailSize++] = lit;
            int[] cIds = varConIds[v];
            int[] cLits = varConLits[v];
            int[] cWs = varConWeights[v];
            boolean ok = true;
            for (int i = 0; i < cIds.length; i++) {
                int c = cIds[i];
                int w = cWs[i];
                maxRemainingWeight[c] -= w;
                if (cLits[i] == lit) {
                    remainingRhs[c] -= w;
                }
                if (remainingRhs[c] > 0) {
                    if (remainingRhs[c] > maxRemainingWeight[c]) {
                        ok = false;
                    } else if (maxRemainingWeight[c] - conWeights[c][0] < remainingRhs[c]) {
                        enqueueBcp(c);
                    }
                }
            }
            return ok;
        }

        private void enqueueBcp(int c) {
            if (!inBcpQueue[c]) {
                inBcpQueue[c] = true;
                if (bcpTail == bcpQueue.length) {
                    bcpQueue = Arrays.copyOf(bcpQueue, bcpQueue.length * 2);
                }
                bcpQueue[bcpTail++] = c;
            }
        }

        private void undoTo(int targetTrail) {
            while (trailSize > targetTrail) {
                int lit = trail[--trailSize];
                int v = Math.abs(lit);
                values[v] = 0;
                int[] cIds = varConIds[v];
                int[] cLits = varConLits[v];
                int[] cWs = varConWeights[v];
                for (int i = 0; i < cIds.length; i++) {
                    int c = cIds[i];
                    int w = cWs[i];
                    maxRemainingWeight[c] += w;
                    if (cLits[i] == lit) {
                        remainingRhs[c] += w;
                    }
                }
            }
        }

        private void clearQueue(int qHead, int qTail) {
            for (int i = qHead; i < qTail; i++) {
                inBcpQueue[bcpQueue[i]] = false;
            }
        }

        /**
         * Deterministic cut-set / tight-degree variable ordering (smallest variable index tie-break, I2):
         * scores variables in a single linear pass over active constraints (prioritizing high-degree
         * binary/tight PB hubs), with exact balanced cut-set BFS refinement over the top-3 candidates
         * on small/medium components ({@code 4 <= n <= 24}).
         */
        private int pickDecisionVar(int[] compVars, int[] compCons) {
            int n = compVars.length;
            if (n == 1) {
                return compVars[0];
            }
            int epoch = ++stampEpoch;
            for (int v : compVars) {
                varSubStamp[v] = epoch;
            }
            for (int c : compCons) {
                conActiveStamp[c] = epoch;
                int[] ls = conLits[c];
                int uLen = 0;
                for (int lit : ls) {
                    if (values[Math.abs(lit)] == 0) {
                        uLen++;
                    }
                }
                int bonus = (uLen <= 2) ? 4 : (uLen == 3 ? 2 : 1);
                for (int lit : ls) {
                    int u = Math.abs(lit);
                    if (values[u] == 0) {
                        if (varScoreStamp[u] != epoch) {
                            varScoreStamp[u] = epoch;
                            varScoreBuf[u] = bonus;
                            varDegBuf[u] = 1;
                        } else {
                            varScoreBuf[u] += bonus;
                            varDegBuf[u]++;
                        }
                    }
                }
            }

            // Pick top-3 deterministic candidates by (score desc, deg desc, var asc)
            int top0 = compVars[0], top1 = 0, top2 = 0;
            int s0 = -1, d0 = -1;
            int s1 = -1, d1 = -1;
            int s2 = -1, d2 = -1;
            for (int v : compVars) {
                int sv = varScoreStamp[v] == epoch ? varScoreBuf[v] : 0;
                int dv = varScoreStamp[v] == epoch ? varDegBuf[v] : 0;
                if (sv > s0 || (sv == s0 && (dv > d0 || (dv == d0 && v < top0)))) {
                    top2 = top1; s2 = s1; d2 = d1;
                    top1 = top0; s1 = s0; d1 = d0;
                    top0 = v; s0 = sv; d0 = dv;
                } else if (sv > s1 || (sv == s1 && (dv > d1 || (dv == d1 && v < top1)))) {
                    top2 = top1; s2 = s1; d2 = d1;
                    top1 = v; s1 = sv; d1 = dv;
                } else if (sv > s2 || (sv == s2 && (dv > d2 || (dv == d2 && v < top2)))) {
                    top2 = v; s2 = sv; d2 = dv;
                }
            }

            // Exact balanced cut-set tie-break over top-3 candidates for compact components
            if (n >= 4 && n <= 24 && d0 >= 2) {
                int bestVar = top0;
                int bestMaxPart = largestResidualComponentWithout(compVars, top0, epoch);
                int bestScore = s0;
                if (top1 != 0 && d1 >= 2) {
                    int p1 = largestResidualComponentWithout(compVars, top1, epoch);
                    if (p1 < bestMaxPart || (p1 == bestMaxPart && (s1 > bestScore || (s1 == bestScore && top1 < bestVar)))) {
                        bestMaxPart = p1;
                        bestScore = s1;
                        bestVar = top1;
                    }
                }
                if (top2 != 0 && d2 >= 2) {
                    int p2 = largestResidualComponentWithout(compVars, top2, epoch);
                    if (p2 < bestMaxPart || (p2 == bestMaxPart && (s2 > bestScore || (s2 == bestScore && top2 < bestVar)))) {
                        bestVar = top2;
                    }
                }
                return bestVar;
            }
            return top0;
        }

        private int largestResidualComponentWithout(int[] compVars, int removedVar, int activeEpoch) {
            int visitEpoch = ++stampEpoch;
            varVisitStamp[removedVar] = visitEpoch;
            int maxPart = 0;
            for (int start : compVars) {
                if (varVisitStamp[start] == visitEpoch) {
                    continue;
                }
                int head = 0;
                int tail = 0;
                varVisitStamp[start] = visitEpoch;
                bfsQueue[tail++] = start;
                int partVars = 0;
                while (head < tail) {
                    int item = bfsQueue[head++];
                    if (item > 0) {
                        partVars++;
                        for (int c : varConIds[item]) {
                            if (conActiveStamp[c] == activeEpoch && conVisitStamp[c] != visitEpoch) {
                                conVisitStamp[c] = visitEpoch;
                                bfsQueue[tail++] = -(c + 1);
                            }
                        }
                    } else {
                        int c = -item - 1;
                        for (int lit : conLits[c]) {
                            int u = Math.abs(lit);
                            if (varSubStamp[u] == activeEpoch && varVisitStamp[u] != visitEpoch) {
                                varVisitStamp[u] = visitEpoch;
                                bfsQueue[tail++] = u;
                            }
                        }
                    }
                }
                if (partVars > maxPart) {
                    maxPart = partVars;
                }
            }
            return maxPart;
        }

        private static int[] removeVarSorted(int[] sortedVars, int target) {
            int[] out = new int[sortedVars.length - 1];
            int j = 0;
            for (int v : sortedVars) {
                if (v != target) {
                    out[j++] = v;
                }
            }
            return out;
        }

        private int allocNode(byte kind) {
            if (nodeCount >= limits.maxNodes()) {
                throw new NodeLimitExceeded();
            }
            if (nodeCount == nodeCapacity) {
                nodeCapacity *= 2;
                nodeKind = Arrays.copyOf(nodeKind, nodeCapacity);
                nodeDataA = Arrays.copyOf(nodeDataA, nodeCapacity);
                nodeDataB = Arrays.copyOf(nodeDataB, nodeCapacity);
                nodeDataC = Arrays.copyOf(nodeDataC, nodeCapacity);
                nodeChildren = Arrays.copyOf(nodeChildren, nodeCapacity);
            }
            int id = nodeCount++;
            nodeKind[id] = kind;
            return id;
        }

        private int mkLiteral(int lit) {
            int slot = (Math.abs(lit) << 1) | (lit > 0 ? 0 : 1);
            int cached = litNodeCache[slot];
            if (cached >= 0) {
                return cached;
            }
            int id = allocNode(CompiledDdnnf.KIND_LITERAL);
            nodeDataA[id] = lit;
            litNodeCache[slot] = id;
            return id;
        }

        private int mkFreeVars(int[] vars) {
            int id = allocNode(CompiledDdnnf.KIND_FREE_VARS);
            nodeChildren[id] = vars;
            return id;
        }

        private int mkOr(int decisionVar, int high, int low) {
            int id = allocNode(CompiledDdnnf.KIND_OR);
            nodeDataA[id] = decisionVar;
            nodeDataB[id] = high;
            nodeDataC[id] = low;
            return id;
        }

        private int combineAndPair(int a, int b) {
            if (a == 0 || b == 0) return 0;
            if (a == 1) return b;
            if (b == 1) return a;
            int id = allocNode(CompiledDdnnf.KIND_AND);
            nodeChildren[id] = new int[] { a, b };
            return id;
        }

        private int combineAnd(int[] forcedNodes, int[] freeVars, int[] compNodes, int numComps) {
            int total = (forcedNodes == null ? 0 : forcedNodes.length)
                    + (freeVars == null ? 0 : 1)
                    + numComps;
            if (total == 0) {
                return 1; // TRUE
            }
            int[] buf = new int[total];
            int k = 0;
            if (forcedNodes != null) {
                for (int n : forcedNodes) {
                    if (n == 0) return 0;
                    if (n != 1) buf[k++] = n;
                }
            }
            if (freeVars != null && freeVars.length > 0) {
                buf[k++] = mkFreeVars(freeVars);
            }
            if (compNodes != null) {
                for (int i = 0; i < numComps; i++) {
                    int n = compNodes[i];
                    if (n == 0) return 0;
                    if (n != 1) buf[k++] = n;
                }
            }
            if (k == 0) return 1;
            if (k == 1) return buf[0];
            int id = allocNode(CompiledDdnnf.KIND_AND);
            nodeChildren[id] = Arrays.copyOf(buf, k);
            return id;
        }
    }

    private SlicedDdnnfCompiler() {}
}
