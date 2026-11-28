package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/**
 * Clean-room pure-Java incremental CDCL SAT solver using 2-Watched-Literals,
 * 1-UIP conflict clause learning, non-chronological backjumping, phase saving,
 * and deterministic VSIDS branching (0 external dependencies).
 */
public enum IncrementalCdclReasoner implements ConfigurationReasoner {
    INSTANCE;

    public static final VersionedIdentifier POLICY =
            new VersionedIdentifier("spring.configuration-reasoner", "cdcl-2wl-1uip-v1");

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
     * repeated blocking clause additions, and assumption-based solving.
     */
    public static final class Session {
        private static final double VSIDS_DECAY = 0.95;
        private static final double VSIDS_RESCALE_LIMIT = 1e100;

        private final SymbolicConfiguration.Limits limits;
        private final Map<ContentDigest, Integer> ids = new HashMap<>();
        private final Map<String, Integer> variables = new TreeMap<>();
        private final List<int[]> clauses = new ArrayList<>();

        private int numVars;
        private int capacity = 64;
        private byte[] values = new byte[capacity + 1];
        private byte[] savedPhase = new byte[capacity + 1];
        private int[] level = new int[capacity + 1];
        private int[] reason = new int[capacity + 1];
        private double[] activity = new double[capacity + 1];
        private boolean[] seenMark = new boolean[capacity + 1];
        private IntList[] watches = new IntList[(capacity + 1) * 2];

        private int[] trail = new int[capacity + 1];
        private int trailSize;
        private int qHead;
        private int[] trailLim = new int[capacity + 1];
        private int decisionLevel;

        private double varInc = 1.0;
        private long steps;
        private boolean topLevelUnsat;

        public Session(SymbolicConfiguration.Limits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            Arrays.fill(savedPhase, (byte) -1);
            Arrays.fill(reason, -1);
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
            trail = Arrays.copyOf(trail, capacity + 1);
            trailLim = Arrays.copyOf(trailLim, capacity + 1);

            int oldWatchLen = (oldCap + 1) * 2;
            int newWatchLen = (capacity + 1) * 2;
            watches = Arrays.copyOf(watches, newWatchLen);
            for (int i = oldWatchLen; i < newWatchLen; i++) {
                watches[i] = new IntList();
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

        private void addPermanentClause(int[] raw) {
            if (topLevelUnsat) {
                return;
            }
            if (clauses.size() >= limits.maxClauses()) {
                throw new Limit();
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
            int cIdx = clauses.size();
            clauses.add(normalized);
            watches[litIdx(normalized[0])].add(cIdx);
            watches[litIdx(normalized[1])].add(cIdx);
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

                int assumptionPtr = 0;
                while (true) {
                    int confl = propagate();
                    if (confl >= 0) {
                        if (decisionLevel == 0) {
                            topLevelUnsat = true;
                            return new SymbolicConfiguration.Answer(
                                    queryId, Satisfiability.UNSATISFIABLE, Map.of(), steps, Optional.empty());
                        }
                        if (decisionLevel <= assumptionPtr && assumptionPtr > 0) {
                            // Conflict directly caused by active assumptions before any free search decision
                            boolean anyFreeAtCurLevel = false;
                            int levelStart = trailLim[decisionLevel - 1];
                            for (int i = levelStart; i < trailSize; i++) {
                                if (reason[Math.abs(trail[i])] >= 0) {
                                    anyFreeAtCurLevel = true;
                                    break;
                                }
                            }
                            if (!anyFreeAtCurLevel) {
                                return new SymbolicConfiguration.Answer(
                                        queryId, Satisfiability.UNSATISFIABLE, Map.of(), steps, Optional.empty());
                            }
                        }
                        int[] btHolder = new int[1];
                        int[] learned = analyzeConflict(confl, btHolder);
                        int btLevel = btHolder[0];
                        backtrackTo(btLevel);
                        if (learned.length == 1) {
                            if (!enqueue(learned[0], -1)) {
                                topLevelUnsat = true;
                                return new SymbolicConfiguration.Answer(
                                        queryId, Satisfiability.UNSATISFIABLE, Map.of(), steps, Optional.empty());
                            }
                        } else {
                            if (clauses.size() >= limits.maxClauses()) {
                                throw new Limit();
                            }
                            int newIdx = clauses.size();
                            clauses.add(learned);
                            watches[litIdx(learned[0])].add(newIdx);
                            watches[litIdx(learned[1])].add(newIdx);
                            enqueue(learned[0], newIdx);
                        }
                        if (btLevel < assumptionPtr) {
                            assumptionPtr = btLevel;
                        }
                        continue;
                    }

                    // Propagate any pending assumptions first
                    if (assumptionPtr < assumptions.length) {
                        int nextLit = assumptions[assumptionPtr];
                        int curVal = litValue(nextLit);
                        if (curVal == -1) {
                            return new SymbolicConfiguration.Answer(
                                    queryId, Satisfiability.UNSATISFIABLE, Map.of(), steps, Optional.empty());
                        }
                        assumptionPtr++;
                        if (curVal == 0) {
                            newDecisionLevel();
                            enqueue(nextLit, -1);
                        }
                        continue;
                    }

                    // Pick next free decision variable via deterministic VSIDS
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

        private int[] analyzeConflict(int conflictIdx, int[] outBtLevel) {
            int cIdx = conflictIdx;
            int p = 0;
            int counter = 0;
            int trailIdx = trailSize - 1;
            var learned = new ArrayList<Integer>(8);
            learned.add(0); // slot 0 reserved for 1-UIP literal

            do {
                step();
                int[] c = clauses.get(cIdx);
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
            varInc /= VSIDS_DECAY;
            if (varInc > VSIDS_RESCALE_LIMIT) {
                rescaleActivity();
            }
            outBtLevel[0] = btLevel;
            return out;
        }

        private void bumpActivity(int v) {
            activity[v] += varInc;
            if (activity[v] > VSIDS_RESCALE_LIMIT) {
                rescaleActivity();
            }
        }

        private void rescaleActivity() {
            double inv = 1.0 / VSIDS_RESCALE_LIMIT;
            for (int i = 1; i <= numVars; i++) {
                activity[i] *= inv;
            }
            varInc *= inv;
        }

        private int pickBranchVar() {
            int bestVar = 0;
            double bestScore = -1.0;
            for (int v = 1; v <= numVars; v++) {
                step();
                if (values[v] == 0) {
                    double s = activity[v];
                    if (bestVar == 0 || s > bestScore) {
                        bestScore = s;
                        bestVar = v;
                    }
                }
            }
            return bestVar;
        }
    }
}
