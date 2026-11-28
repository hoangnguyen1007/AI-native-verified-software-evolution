package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/**
 * Clean-room pure-Java incremental CDCL SAT solver using 2-Watched-Literals,
 * 1-UIP conflict clause learning, non-chronological backjumping, exact
 * assumption-level tracking, phase saving, deterministic VSIDS binary max-heap,
 * Luby restarts, and LBD learned-clause reduction (0 external dependencies).
 */
public enum IncrementalCdclReasoner implements ConfigurationReasoner {
    INSTANCE;

    public static final VersionedIdentifier POLICY =
            new VersionedIdentifier("spring.configuration-reasoner", "cdcl-2wl-1uip-v2");

    private static final class Limit extends RuntimeException {}

    @Override
    public SymbolicConfiguration.Answer solve(SymbolicConfiguration.Query query) {
        Objects.requireNonNull(query, "query");
        var session = new Session(query.limits());
        try {
            if (query.formula().depth() > query.limits().maxDepth()) {
                throw new Limit();
            }
            session.addRoot(query.formula());
            var answer = session.solveIncremental(query.identity());
            if (answer.status() == Satisfiability.SATISFIABLE && !query.formula().evaluate(answer.witness())) {
                throw new IllegalStateException("CDCL witness failed Boolean DAG replay");
            }
            return answer;
        } catch (Limit exceeded) {
            return new SymbolicConfiguration.Answer(
                    query.identity(), Satisfiability.UNKNOWN, Map.of(), session.steps(), Optional.of("SOLVER_LIMIT"));
        }
    }

    @Override
    public VersionedIdentifier policy() {
        return POLICY;
    }

    @Override
    public FiniteConfigurationEvaluation.Result enumerate(
            ConditionModel m, ConditionExpression.Semantics s, FiniteConfigurationEvaluation.Limits l) {
        return ExhaustiveConfigurationReasoner.INSTANCE.enumerate(m, s, l);
    }

    @Override
    public Satisfiability satisfiability(Region r) {
        return ExhaustiveConfigurationReasoner.INSTANCE.satisfiability(r);
    }

    @Override
    public Optional<WorldKey> witness(Region r) {
        return ExhaustiveConfigurationReasoner.INSTANCE.witness(r);
    }

    @Override
    public LogicalValue implies(Region a, Region b) {
        return ExhaustiveConfigurationReasoner.INSTANCE.implies(a, b);
    }

    @Override
    public LogicalValue equivalent(Region a, Region b) {
        return ExhaustiveConfigurationReasoner.INSTANCE.equivalent(a, b);
    }

    /**
     * 0-based Luby restart sequence: {@code 1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8, ...}.
     */
    public static int luby(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Negative Luby index: " + index);
        }
        int i = index;
        int size = 1;
        int seq = 0;
        while (size < i + 1 && seq < 29) {
            seq++;
            size = 2 * size + 1;
        }
        while (size - 1 != i) {
            size = (size - 1) >>> 1;
            seq--;
            if (i >= size) {
                i -= size;
            }
        }
        return 1 << seq;
    }

    private static final class IntList {
        int[] data = new int[4];
        int size;

        void add(int val) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = val;
        }

        int get(int idx) {
            return data[idx];
        }

        int size() {
            return size;
        }

        void removeAtSwap(int idx) {
            int last = --size;
            if (idx < last) {
                data[idx] = data[last];
            }
        }
    }

    /**
     * Reusable incremental CDCL session supporting one-time Tseitin encoding of Boolean DAGs,
     * repeated blocking clause additions, assumption-based solving, deterministic VSIDS max-heap
     * branching, Luby restarts, and LBD learned-clause reduction.
     */
    public static final class Session {
        private static final double VSIDS_DECAY = 0.95;
        private static final double VSIDS_RESCALE_LIMIT = 1e100;
        private static final double CLAUSE_DECAY = 0.999;
        private static final double CLAUSE_RESCALE_LIMIT = 1e20;
        private static final int LUBY_RESTART_BASE = 100;
        private static final int INITIAL_LEARNT_BUDGET = 200;
        private static final int LEARNT_BUDGET_INCREMENT = 100;
        private static final int GLUE_LBD_LIMIT = 2;

        private final SymbolicConfiguration.Limits limits;
        private final Map<ContentDigest, Integer> ids = new HashMap<>();
        private final Map<String, Integer> variables = new TreeMap<>();
        private final List<int[]> clauses = new ArrayList<>();

        private int clauseCapacity = 128;
        private boolean[] clauseLearnt = new boolean[clauseCapacity];
        private int[] clauseLbd = new int[clauseCapacity];
        private double[] clauseActivity = new double[clauseCapacity];
        private int permanentCount;
        private int learntCount;
        private int learntBudget = INITIAL_LEARNT_BUDGET;
        private double clauseInc = 1.0;

        private int numVars;
        private int capacity = 64;
        private byte[] values = new byte[capacity + 1];
        private byte[] savedPhase = new byte[capacity + 1];
        private int[] level = new int[capacity + 1];
        private int[] reason = new int[capacity + 1];
        private double[] activity = new double[capacity + 1];
        private boolean[] seenMark = new boolean[capacity + 1];
        private int[] lbdStamp = new int[capacity + 1];
        private int lbdEpoch;
        private IntList[] watches = new IntList[(capacity + 1) * 2];

        // Deterministic binary max-heap over variables 1..numVars (tie-break by smallest var index)
        private int[] heap = new int[capacity + 1];
        private int[] heapPos = new int[capacity + 1];
        private int heapSize;

        private int[] trail = new int[capacity + 1];
        private int trailSize;
        private int qHead;
        private int[] trailLim = new int[capacity + 1];
        private int decisionLevel;

        private double varInc = 1.0;
        private long steps;
        private long totalConflicts;
        private int totalRestarts;
        private int totalClauseReductions;
        private int totalPrunedClauses;
        private boolean topLevelUnsat;

        public Session(SymbolicConfiguration.Limits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            Arrays.fill(savedPhase, (byte) -1);
            Arrays.fill(reason, -1);
            Arrays.fill(heapPos, -1);
            for (int i = 0; i < watches.length; i++) {
                watches[i] = new IntList();
            }
        }

        public long steps() {
            return steps;
        }

        public int variableCount() {
            return numVars;
        }

        public long conflicts() {
            return totalConflicts;
        }

        public int restarts() {
            return totalRestarts;
        }

        public int learnedClauseReductions() {
            return totalClauseReductions;
        }

        public int prunedLearnedClauses() {
            return totalPrunedClauses;
        }

        public int learntClauseCount() {
            return learntCount;
        }

        public int glueClauseCount() {
            int count = 0;
            int n = clauses.size();
            for (int i = 0; i < n; i++) {
                if (clauseLearnt[i] && clauseLbd[i] <= GLUE_LBD_LIMIT) {
                    count++;
                }
            }
            return count;
        }

        private void step() {
            if (steps >= limits.maxSteps()) {
                throw new Limit();
            }
            steps++;
        }

        private void ensureCapacity(int neededVars) {
            if (neededVars <= capacity) {
                return;
            }
            int oldCap = capacity;
            while (capacity < neededVars) {
                capacity *= 2;
            }
            int oldSavedLen = savedPhase.length;
            values = Arrays.copyOf(values, capacity + 1);
            savedPhase = Arrays.copyOf(savedPhase, capacity + 1);
            Arrays.fill(savedPhase, oldSavedLen, savedPhase.length, (byte) -1);
            level = Arrays.copyOf(level, capacity + 1);
            int oldReasonLen = reason.length;
            reason = Arrays.copyOf(reason, capacity + 1);
            Arrays.fill(reason, oldReasonLen, reason.length, -1);
            activity = Arrays.copyOf(activity, capacity + 1);
            seenMark = Arrays.copyOf(seenMark, capacity + 1);
            lbdStamp = Arrays.copyOf(lbdStamp, capacity + 1);
            heap = Arrays.copyOf(heap, capacity + 1);
            int oldHeapPosLen = heapPos.length;
            heapPos = Arrays.copyOf(heapPos, capacity + 1);
            Arrays.fill(heapPos, oldHeapPosLen, heapPos.length, -1);
            trail = Arrays.copyOf(trail, capacity + 1);
            trailLim = Arrays.copyOf(trailLim, capacity + 1);

            int oldWatchLen = (oldCap + 1) * 2;
            int newWatchLen = (capacity + 1) * 2;
            watches = Arrays.copyOf(watches, newWatchLen);
            for (int i = oldWatchLen; i < newWatchLen; i++) {
                watches[i] = new IntList();
            }
        }

        private void ensureClauseCapacity(int neededClauses) {
            if (neededClauses <= clauseCapacity) {
                return;
            }
            while (clauseCapacity < neededClauses) {
                clauseCapacity *= 2;
            }
            clauseLearnt = Arrays.copyOf(clauseLearnt, clauseCapacity);
            clauseLbd = Arrays.copyOf(clauseLbd, clauseCapacity);
            clauseActivity = Arrays.copyOf(clauseActivity, clauseCapacity);
        }

        private boolean higherPriority(int varA, int varB) {
            double scoreA = activity[varA];
            double scoreB = activity[varB];
            if (scoreA != scoreB) {
                return scoreA > scoreB;
            }
            return varA < varB;
        }

        private void heapInsert(int v) {
            if (heapPos[v] != -1) {
                return;
            }
            int idx = heapSize++;
            heap[idx] = v;
            heapPos[v] = idx;
            heapSiftUp(idx);
        }

        private void heapSiftUp(int idx) {
            int v = heap[idx];
            while (idx > 0) {
                int parent = (idx - 1) >>> 1;
                int pv = heap[parent];
                if (!higherPriority(v, pv)) {
                    break;
                }
                heap[idx] = pv;
                heapPos[pv] = idx;
                idx = parent;
            }
            heap[idx] = v;
            heapPos[v] = idx;
        }

        private void heapSiftDown(int idx) {
            int v = heap[idx];
            while (true) {
                int left = (idx << 1) + 1;
                if (left >= heapSize) {
                    break;
                }
                int right = left + 1;
                int best = (right < heapSize && higherPriority(heap[right], heap[left])) ? right : left;
                int bv = heap[best];
                if (!higherPriority(bv, v)) {
                    break;
                }
                heap[idx] = bv;
                heapPos[bv] = idx;
                idx = best;
            }
            heap[idx] = v;
            heapPos[v] = idx;
        }

        private int heapPopMax() {
            int top = heap[0];
            heapPos[top] = -1;
            heapSize--;
            if (heapSize > 0) {
                int last = heap[heapSize];
                heap[0] = last;
                heapPos[last] = 0;
                heapSiftDown(0);
            }
            return top;
        }

        private void heapRebuild() {
            for (int i = (heapSize >>> 1) - 1; i >= 0; i--) {
                heapSiftDown(i);
            }
        }

        private static int litIdx(int lit) {
            return (Math.abs(lit) << 1) | (lit > 0 ? 0 : 1);
        }

        private int litValue(int lit) {
            byte v = values[Math.abs(lit)];
            if (v == 0) {
                return 0;
            }
            return (lit > 0) ? v : -v;
        }

        private int newVar() {
            numVars++;
            ensureCapacity(numVars);
            heapPos[numVars] = -1;
            heapInsert(numVars);
            return numVars;
        }

        public int encode(SymbolicConfiguration.Formula formula) {
            return encodeInternal(formula, 0);
        }

        public void addRoot(SymbolicConfiguration.Formula formula) {
            backtrackTo(0);
            int root = encodeInternal(formula, 0);
            addPermanentClause(new int[] { root });
        }

        public void addBlockingClause(int... literals) {
            backtrackTo(0);
            addPermanentClause(literals);
        }

        private int encodeInternal(SymbolicConfiguration.Formula formula, int depth) {
            step();
            var known = ids.get(formula.identity());
            if (known != null) {
                return known;
            }
            if (depth >= limits.maxDepth() || ids.size() >= limits.maxNodes()) {
                throw new Limit();
            }
            int id = newVar();
            ids.put(formula.identity(), id);
            var children = formula.children();
            int[] childLits = new int[children.size()];
            for (int i = 0; i < children.size(); i++) {
                childLits[i] = encodeInternal(children.get(i), depth + 1);
            }
            switch (formula.operator()) {
                case TRUE -> addPermanentClause(new int[] { id });
                case FALSE -> addPermanentClause(new int[] { -id });
                case VARIABLE -> {
                    variables.put(formula.variable(), id);
                    activity[id] += 1.0;
                    if (heapPos[id] >= 0) {
                        heapSiftUp(heapPos[id]);
                    }
                }
                case NOT -> {
                    addPermanentClause(new int[] { -id, -childLits[0] });
                    addPermanentClause(new int[] { id, childLits[0] });
                }
                case AND, OR -> {
                    boolean isAnd = formula.operator() == SymbolicConfiguration.Operator.AND;
                    int[] longClause = new int[childLits.length + 1];
                    longClause[0] = isAnd ? id : -id;
                    for (int i = 0; i < childLits.length; i++) {
                        addPermanentClause(new int[] { isAnd ? -id : id, isAnd ? childLits[i] : -childLits[i] });
                        longClause[i + 1] = isAnd ? -childLits[i] : childLits[i];
                    }
                    addPermanentClause(longClause);
                }
            }
            return id;
        }

        private int addClauseInternal(int[] normalized, boolean learnt, int lbd) {
            int cIdx = clauses.size();
            ensureClauseCapacity(cIdx + 1);
            clauses.add(normalized);
            clauseLearnt[cIdx] = learnt;
            clauseLbd[cIdx] = lbd;
            clauseActivity[cIdx] = learnt ? clauseInc : 0.0;
            if (learnt) {
                learntCount++;
            } else {
                permanentCount++;
            }
            watches[litIdx(normalized[0])].add(cIdx);
            watches[litIdx(normalized[1])].add(cIdx);
            return cIdx;
        }

        private void addPermanentClause(int[] raw) {
            if (topLevelUnsat) {
                return;
            }
            if (clauses.size() >= limits.maxClauses()) {
                if (learntCount > 0) {
                    reduceLearnedClauses();
                }
                if (clauses.size() >= limits.maxClauses()) {
                    throw new Limit();
                }
            }
            // Normalize: deduplicate, drop false@level0, detect satisfied@level0 and tautology
            int[] tmp = new int[raw.length];
            int count = 0;
            for (int lit : raw) {
                if (lit == 0) {
                    continue;
                }
                int val = litValue(lit);
                if (val == 1) {
                    return;
                }
                if (val == -1) {
                    continue;
                }
                boolean dup = false;
                for (int j = 0; j < count; j++) {
                    if (tmp[j] == lit) {
                        dup = true;
                        break;
                    }
                    if (tmp[j] == -lit) {
                        return; // Tautology
                    }
                }
                if (!dup) {
                    tmp[count++] = lit;
                }
            }
            if (count == 0) {
                topLevelUnsat = true;
                return;
            }
            if (count == 1) {
                if (!enqueue(tmp[0], -1)) {
                    topLevelUnsat = true;
                }
                return;
            }
            int[] normalized = Arrays.copyOf(tmp, count);
            addClauseInternal(normalized, false, 0);
        }

        public SymbolicConfiguration.Answer solveIncremental(ContentDigest queryId, int... assumptions) {
            backtrackTo(0);
            try {
                if (topLevelUnsat) {
                    return new SymbolicConfiguration.Answer(
                            queryId, Satisfiability.UNSATISFIABLE, Map.of(), steps, Optional.empty());
                }
                int topConflict = propagate();
                if (topConflict >= 0) {
                    topLevelUnsat = true;
                    return new SymbolicConfiguration.Answer(
                            queryId, Satisfiability.UNSATISFIABLE, Map.of(), steps, Optional.empty());
                }

                // Exact assumption-level tracking: assumptionDecisionLevel[i] records the exact
                // decision level active once assumptions[i] is placed (monotonically non-decreasing).
                // maxAssumptionDecisionLevel advances ONLY when curVal == 0 actually opens a new level.
                int[] assumptionDecisionLevel = new int[assumptions.length];
                int assumptionPtr = 0;
                int maxAssumptionDecisionLevel = 0;

                int conflictsSinceRestart = 0;
                int restartIndex = 0;
                int nextRestartLimit = LUBY_RESTART_BASE * luby(0);

                while (true) {
                    int confl = propagate();
                    if (confl >= 0) {
                        totalConflicts++;
                        conflictsSinceRestart++;
                        if (decisionLevel == 0) {
                            topLevelUnsat = true;
                            return new SymbolicConfiguration.Answer(
                                    queryId, Satisfiability.UNSATISFIABLE, Map.of(), steps, Optional.empty());
                        }
                        if (decisionLevel <= maxAssumptionDecisionLevel) {
                            // Every decision level 1..decisionLevel was opened by an active assumption;
                            // no free search decisions exist on the trail -> UNSAT under assumptions.
                            return new SymbolicConfiguration.Answer(
                                    queryId, Satisfiability.UNSATISFIABLE, Map.of(), steps, Optional.empty());
                        }
                        int[] outMeta = new int[2]; // [0] = btLevel, [1] = lbd
                        int[] learned = analyzeConflict(confl, outMeta);
                        int btLevel = outMeta[0];
                        int lbd = outMeta[1];
                        backtrackTo(btLevel);
                        if (btLevel < maxAssumptionDecisionLevel) {
                            while (assumptionPtr > 0 && assumptionDecisionLevel[assumptionPtr - 1] > btLevel) {
                                assumptionPtr--;
                            }
                            maxAssumptionDecisionLevel = assumptionPtr == 0
                                    ? 0
                                    : assumptionDecisionLevel[assumptionPtr - 1];
                        }
                        if (learned.length == 1) {
                            if (!enqueue(learned[0], -1)) {
                                topLevelUnsat = true;
                                return new SymbolicConfiguration.Answer(
                                        queryId, Satisfiability.UNSATISFIABLE, Map.of(), steps, Optional.empty());
                            }
                        } else {
                            if (clauses.size() >= limits.maxClauses()) {
                                reduceLearnedClauses();
                                if (clauses.size() >= limits.maxClauses()) {
                                    throw new Limit();
                                }
                            }
                            int newIdx = addClauseInternal(learned, true, lbd);
                            enqueue(learned[0], newIdx);
                        }
                        continue;
                    }

                    // Propagate any pending assumptions first
                    if (assumptionPtr < assumptions.length) {
                        int nextLit = assumptions[assumptionPtr];
                        int v = Math.abs(nextLit);
                        if (v == 0 || v > numVars) {
                            throw new IllegalArgumentException("Unknown assumption literal: " + nextLit);
                        }
                        int curVal = litValue(nextLit);
                        if (curVal == -1) {
                            return new SymbolicConfiguration.Answer(
                                    queryId, Satisfiability.UNSATISFIABLE, Map.of(), steps, Optional.empty());
                        }
                        if (curVal == 0) {
                            newDecisionLevel();
                            enqueue(nextLit, -1);
                            maxAssumptionDecisionLevel = decisionLevel;
                        }
                        assumptionDecisionLevel[assumptionPtr] = decisionLevel;
                        assumptionPtr++;
                        continue;
                    }

                    // Luby restart: backtrack to maxAssumptionDecisionLevel, preserving phase saving
                    // and keeping all active assumption levels intact.
                    if (conflictsSinceRestart >= nextRestartLimit) {
                        conflictsSinceRestart = 0;
                        restartIndex++;
                        totalRestarts++;
                        nextRestartLimit = LUBY_RESTART_BASE * luby(restartIndex);
                        if (decisionLevel > maxAssumptionDecisionLevel) {
                            backtrackTo(maxAssumptionDecisionLevel);
                        }
                    }

                    // LBD learned-clause reduction when learnts exceed budget (retaining glue LBD <= 2 & reasons)
                    if (learntCount > learntBudget) {
                        reduceLearnedClauses();
                    }

                    // Pick next free decision variable via deterministic VSIDS binary max-heap
                    int nextVar = pickBranchVar();
                    if (nextVar == 0) {
                        var witness = new TreeMap<String, Boolean>();
                        variables.forEach((name, id) -> witness.put(name, values[id] == 1));
                        return new SymbolicConfiguration.Answer(
                                queryId, Satisfiability.SATISFIABLE, witness, steps, Optional.empty());
                    }
                    newDecisionLevel();
                    int decisionLit = savedPhase[nextVar] == 1 ? nextVar : -nextVar;
                    enqueue(decisionLit, -1);
                }
            } catch (Limit exceeded) {
                return new SymbolicConfiguration.Answer(
                        queryId, Satisfiability.UNKNOWN, Map.of(), steps, Optional.of("SOLVER_LIMIT"));
            } finally {
                backtrackTo(0);
            }
        }

        private void newDecisionLevel() {
            trailLim[decisionLevel++] = trailSize;
        }

        private void backtrackTo(int targetLevel) {
            while (decisionLevel > targetLevel) {
                int start = trailLim[--decisionLevel];
                for (int c = trailSize - 1; c >= start; c--) {
                    int v = Math.abs(trail[c]);
                    savedPhase[v] = values[v];
                    values[v] = 0;
                    reason[v] = -1;
                    level[v] = 0;
                    heapInsert(v);
                }
                trailSize = start;
                qHead = start;
            }
        }

        private boolean enqueue(int lit, int reasonClauseIdx) {
            int v = Math.abs(lit);
            byte sign = (byte) (lit > 0 ? 1 : -1);
            if (values[v] != 0) {
                return values[v] == sign;
            }
            values[v] = sign;
            level[v] = decisionLevel;
            reason[v] = reasonClauseIdx;
            trail[trailSize++] = lit;
            return true;
        }

        private int propagate() {
            while (qHead < trailSize) {
                step();
                int p = trail[qHead++];
                int falseLit = -p;
                IntList ws = watches[litIdx(falseLit)];
                int i = 0;
                while (i < ws.size()) {
                    step();
                    int cIdx = ws.get(i);
                    int[] c = clauses.get(cIdx);
                    if (c[0] == falseLit) {
                        c[0] = c[1];
                        c[1] = falseLit;
                    }
                    int first = c[0];
                    if (litValue(first) == 1) {
                        i++;
                        continue;
                    }
                    boolean foundReplacement = false;
                    for (int k = 2; k < c.length; k++) {
                        step();
                        if (litValue(c[k]) != -1) {
                            c[1] = c[k];
                            c[k] = falseLit;
                            ws.removeAtSwap(i);
                            watches[litIdx(c[1])].add(cIdx);
                            foundReplacement = true;
                            break;
                        }
                    }
                    if (foundReplacement) {
                        continue;
                    }
                    i++;
                    if (litValue(first) == -1) {
                        return cIdx;
                    }
                    enqueue(first, cIdx);
                }
            }
            return -1;
        }

        private int computeLbd(int[] clause) {
            if (++lbdEpoch == Integer.MAX_VALUE) {
                Arrays.fill(lbdStamp, 0);
                lbdEpoch = 1;
            }
            int lbd = 0;
            for (int lit : clause) {
                int lvl = level[Math.abs(lit)];
                if (lvl > 0 && lbdStamp[lvl] != lbdEpoch) {
                    lbdStamp[lvl] = lbdEpoch;
                    lbd++;
                }
            }
            return lbd;
        }

        private int[] analyzeConflict(int conflictIdx, int[] outMeta) {
            int cIdx = conflictIdx;
            int p = 0;
            int counter = 0;
            int trailIdx = trailSize - 1;
            var learned = new ArrayList<Integer>(8);
            learned.add(0); // slot 0 reserved for 1-UIP literal

            do {
                step();
                int[] c = clauses.get(cIdx);
                if (clauseLearnt[cIdx]) {
                    bumpClauseActivity(cIdx);
                    int updatedLbd = computeLbd(c);
                    if (updatedLbd < clauseLbd[cIdx]) {
                        clauseLbd[cIdx] = updatedLbd;
                    }
                }
                int start = (p == 0) ? 0 : 1;
                for (int j = start; j < c.length; j++) {
                    int lit = c[j];
                    int v = Math.abs(lit);
                    int lvl = level[v];
                    if (!seenMark[v] && lvl > 0) {
                        bumpActivity(v);
                        seenMark[v] = true;
                        if (lvl == decisionLevel) {
                            counter++;
                        } else {
                            learned.add(lit);
                        }
                    }
                }
                while (trailIdx >= 0 && !seenMark[Math.abs(trail[trailIdx])]) {
                    trailIdx--;
                }
                if (trailIdx < 0) {
                    break;
                }
                p = trail[trailIdx--];
                int pv = Math.abs(p);
                seenMark[pv] = false;
                counter--;
                cIdx = reason[pv];
            } while (counter > 0 && cIdx >= 0);

            learned.set(0, -p);
            int[] out = new int[learned.size()];
            int btLevel = 0;
            int maxPos = 1;
            for (int i = 0; i < learned.size(); i++) {
                int l = learned.get(i);
                out[i] = l;
                seenMark[Math.abs(l)] = false;
                if (i >= 1) {
                    int lvl = level[Math.abs(l)];
                    if (lvl > btLevel) {
                        btLevel = lvl;
                        maxPos = i;
                    }
                }
            }
            if (out.length > 2 && maxPos != 1) {
                int tmp = out[1];
                out[1] = out[maxPos];
                out[maxPos] = tmp;
            }
            int lbd = computeLbd(out);
            varInc /= VSIDS_DECAY;
            if (varInc > VSIDS_RESCALE_LIMIT) {
                rescaleActivity();
            }
            clauseInc /= CLAUSE_DECAY;
            if (clauseInc > CLAUSE_RESCALE_LIMIT) {
                rescaleClauseActivity();
            }
            outMeta[0] = btLevel;
            outMeta[1] = lbd;
            return out;
        }

        private void reduceLearnedClauses() {
            int oldCount = clauses.size();
            boolean[] locked = new boolean[oldCount];
            for (int t = 0; t < trailSize; t++) {
                int r = reason[Math.abs(trail[t])];
                if (r >= 0) {
                    locked[r] = true;
                }
            }

            int candCount = 0;
            for (int i = 0; i < oldCount; i++) {
                step();
                if (clauseLearnt[i] && clauseLbd[i] > GLUE_LBD_LIMIT && !locked[i]) {
                    candCount++;
                }
            }
            totalClauseReductions++;
            if (candCount == 0) {
                learntBudget = Math.max(learntBudget, learntCount) + LEARNT_BUDGET_INCREMENT;
                return;
            }

            Integer[] candidates = new Integer[candCount];
            int ci = 0;
            for (int i = 0; i < oldCount; i++) {
                if (clauseLearnt[i] && clauseLbd[i] > GLUE_LBD_LIMIT && !locked[i]) {
                    candidates[ci++] = i;
                }
            }
            // Deterministic total order: prune worst (highest LBD, longest, lowest activity, lowest index) first
            Arrays.sort(candidates, (a, b) -> {
                int ia = a;
                int ib = b;
                int cmp = Integer.compare(clauseLbd[ib], clauseLbd[ia]);
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Integer.compare(clauses.get(ib).length, clauses.get(ia).length);
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Double.compare(clauseActivity[ia], clauseActivity[ib]);
                if (cmp != 0) {
                    return cmp;
                }
                return Integer.compare(ia, ib);
            });

            int toRemove = Math.max(1, candCount / 2);
            boolean[] remove = new boolean[oldCount];
            for (int i = 0; i < toRemove; i++) {
                remove[candidates[i]] = true;
            }

            int[] oldToNew = new int[oldCount];
            Arrays.fill(oldToNew, -1);
            int newCount = 0;
            for (int oldIdx = 0; oldIdx < oldCount; oldIdx++) {
                if (remove[oldIdx]) {
                    continue;
                }
                oldToNew[oldIdx] = newCount;
                if (newCount != oldIdx) {
                    clauses.set(newCount, clauses.get(oldIdx));
                    clauseLearnt[newCount] = clauseLearnt[oldIdx];
                    clauseLbd[newCount] = clauseLbd[oldIdx];
                    clauseActivity[newCount] = clauseActivity[oldIdx];
                }
                newCount++;
            }
            clauses.subList(newCount, oldCount).clear();
            learntCount -= toRemove;
            totalPrunedClauses += toRemove;

            for (int v = 1; v <= numVars; v++) {
                int r = reason[v];
                if (r >= 0) {
                    int mapped = oldToNew[r];
                    if (mapped < 0) {
                        throw new IllegalStateException("Removed locked reason clause " + r);
                    }
                    reason[v] = mapped;
                }
            }

            int watchLen = (numVars + 1) * 2;
            for (int w = 2; w < watchLen; w++) {
                IntList ws = watches[w];
                int write = 0;
                for (int r = 0; r < ws.size; r++) {
                    int mapped = oldToNew[ws.data[r]];
                    if (mapped >= 0) {
                        ws.data[write++] = mapped;
                    }
                }
                ws.size = write;
            }

            learntBudget = Math.max(learntBudget, learntCount) + LEARNT_BUDGET_INCREMENT;
        }

        private void bumpActivity(int v) {
            activity[v] += varInc;
            if (activity[v] > VSIDS_RESCALE_LIMIT) {
                rescaleActivity();
            } else if (heapPos[v] >= 0) {
                heapSiftUp(heapPos[v]);
            }
        }

        private void rescaleActivity() {
            double inv = 1.0 / VSIDS_RESCALE_LIMIT;
            for (int i = 1; i <= numVars; i++) {
                activity[i] *= inv;
            }
            varInc *= inv;
            heapRebuild();
        }

        private void bumpClauseActivity(int cIdx) {
            clauseActivity[cIdx] += clauseInc;
            if (clauseActivity[cIdx] > CLAUSE_RESCALE_LIMIT) {
                rescaleClauseActivity();
            }
        }

        private void rescaleClauseActivity() {
            double inv = 1.0 / CLAUSE_RESCALE_LIMIT;
            int n = clauses.size();
            for (int i = 0; i < n; i++) {
                if (clauseLearnt[i]) {
                    clauseActivity[i] *= inv;
                }
            }
            clauseInc *= inv;
        }

        private int pickBranchVar() {
            while (heapSize > 0) {
                step();
                int v = heapPopMax();
                if (values[v] == 0) {
                    return v;
                }
            }
            return 0;
        }
    }
}
