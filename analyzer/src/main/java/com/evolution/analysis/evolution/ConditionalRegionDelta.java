package com.evolution.analysis.evolution;

import com.evolution.analysis.spring.condition.LogicalValue;
import com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability;
import com.evolution.analysis.spring.truth.SatConfigurationReasoner;
import com.evolution.analysis.spring.truth.SymbolicConfiguration.*;
import java.util.*;

import static com.evolution.analysis.spring.condition.LogicalValue.*;
import static com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability.SATISFIABLE;
import static com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability.UNSATISFIABLE;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.and;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.not;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.or;
import static com.evolution.analysis.spring.truth.TruthRegionEvaluation.Classification;

/** Region algebra over already aligned assignments. Possible feasibility never proves absence. */
final class ConditionalRegionDelta {
    private ConditionalRegionDelta() {}

    enum Bucket { INTRODUCED, RESOLVED, PERSISTED, CLEAN, UNCERTAIN, LEFT_ONLY, RIGHT_ONLY,
        UNKNOWN_FEASIBILITY, UNMAPPED, INFEASIBLE }
    enum Shape { UNCHANGED, EXPANDED, SHRUNK, RESHAPED, EMPTY, UNKNOWN }

    record Cell(LogicalValue feasible, LogicalValue truth) {}
    record Finite(Map<String, Bucket> worlds, Classification left, Classification right, Shape shape) {}

    static Finite finite(Map<String,Cell> left, Map<String,Cell> right, boolean leftComplete, boolean rightComplete) {
        var keys = new TreeSet<>(left.keySet());
        keys.addAll(right.keySet());
        var worlds = new TreeMap<String,Bucket>();
        var l = EnumSet.noneOf(LogicalValue.class);
        var r = EnumSet.noneOf(LogicalValue.class);
        for (String key : keys) {
            Cell a = left.get(key), b = right.get(key);
            Bucket bucket;
            if (a != null && a.feasible == UNKNOWN || b != null && b.feasible == UNKNOWN) bucket = Bucket.UNKNOWN_FEASIBILITY;
            else if (a == null && !leftComplete || b == null && !rightComplete) bucket = Bucket.UNMAPPED;
            else if (a == null || a.feasible == FALSE) bucket = b != null && b.feasible == TRUE ? Bucket.RIGHT_ONLY : Bucket.INFEASIBLE;
            else if (b == null || b.feasible == FALSE) bucket = Bucket.LEFT_ONLY;
            else {
                l.add(a.truth);
                r.add(b.truth);
                bucket = a.truth == UNKNOWN || b.truth == UNKNOWN ? Bucket.UNCERTAIN :
                        a.truth == TRUE ? b.truth == TRUE ? Bucket.PERSISTED : Bucket.RESOLVED :
                                b.truth == TRUE ? Bucket.INTRODUCED : Bucket.CLEAN;
            }
            worlds.put(key, bucket);
        }
        var buckets = EnumSet.noneOf(Bucket.class);
        buckets.addAll(worlds.values());
        boolean closed = leftComplete && rightComplete && !buckets.contains(Bucket.UNKNOWN_FEASIBILITY) && !buckets.contains(Bucket.UNMAPPED);
        return new Finite(Collections.unmodifiableMap(worlds), classify(l,closed), classify(r,closed),
                shape(!l.isEmpty(),closed && !buckets.contains(Bucket.UNCERTAIN),
                        buckets.contains(Bucket.INTRODUCED),buckets.contains(Bucket.RESOLVED)));
    }

    private static Classification classify(Set<LogicalValue> values, boolean closed) {
        if (!closed || values.isEmpty() || values.contains(UNKNOWN)) return Classification.UNKNOWN;
        if (values.size() == 2) return Classification.MAY;
        return values.contains(TRUE) ? Classification.MUST : Classification.NEVER;
    }

    private static Shape shape(boolean nonempty, boolean closed, boolean gain, boolean loss) {
        if (!closed) return Shape.UNKNOWN;
        if (!nonempty) return Shape.EMPTY;
        if (gain && loss) return Shape.RESHAPED;
        return gain ? Shape.EXPANDED : loss ? Shape.SHRUNK : Shape.UNCHANGED;
    }

    record Predicates(Formula feasible, Formula possible, Formula yes, Formula no) {}
    record Proof(Formula formula, Answer answer) {
        Satisfiability status() { return answer.status(); }
        Map<String,Boolean> assignment() { return answer.witness(); }
    }
    record Symbolic(Map<Bucket,Proof> proofs, Proof common, Classification left, Classification right, Shape shape) {}

    static Symbolic symbolic(Predicates left, Predicates right, Limits limits) {
        var common = and(left.feasible,right.feasible);
        // Conflicted true/false certificates are unknown, not two simultaneous events.
        var ly = and(left.yes,not(left.no)); var ln = and(left.no,not(left.yes));
        var ry = and(right.yes,not(right.no)); var rn = and(right.no,not(right.yes));
        var parts = new EnumMap<Bucket,Proof>(Bucket.class);
        parts.put(Bucket.INTRODUCED,prove(and(common,ln,ry),limits));
        parts.put(Bucket.RESOLVED,prove(and(common,ly,rn),limits));
        parts.put(Bucket.PERSISTED,prove(and(common,ly,ry),limits));
        parts.put(Bucket.CLEAN,prove(and(common,ln,rn),limits));
        parts.put(Bucket.UNCERTAIN,prove(and(common,not(and(or(ly,ln),or(ry,rn)))),limits));
        parts.put(Bucket.LEFT_ONLY,prove(and(left.feasible,not(right.possible)),limits));
        parts.put(Bucket.RIGHT_ONLY,prove(and(right.feasible,not(left.possible)),limits));
        parts.put(Bucket.UNKNOWN_FEASIBILITY,prove(or(and(left.possible,not(left.feasible)),
                and(right.possible,not(right.feasible))),limits));
        var commonProof = prove(common,limits);
        boolean feasibilityClosed = parts.get(Bucket.UNKNOWN_FEASIBILITY).status() == UNSATISFIABLE;
        boolean closed = feasibilityClosed && parts.get(Bucket.UNCERTAIN).status() == UNSATISFIABLE
                && parts.values().stream().noneMatch(p -> p.status() == Satisfiability.UNKNOWN)
                && commonProof.status() != Satisfiability.UNKNOWN;
        var l = quantify(commonProof,ly,ln,feasibilityClosed,limits);
        var r = quantify(commonProof,ry,rn,feasibilityClosed,limits);
        return new Symbolic(Collections.unmodifiableMap(parts),commonProof,l,r,shape(commonProof.status() == SATISFIABLE,closed,
                parts.get(Bucket.INTRODUCED).status() == SATISFIABLE, parts.get(Bucket.RESOLVED).status() == SATISFIABLE));
    }

    private static Classification quantify(Proof common, Formula yes, Formula no, boolean closed, Limits limits) {
        if (!closed || common.status() != SATISFIABLE ||
                prove(and(common.formula,not(or(yes,no))),limits).status() != UNSATISFIABLE) return Classification.UNKNOWN;
        var y = prove(and(common.formula,yes),limits).status();
        var n = prove(and(common.formula,no),limits).status();
        if (y == SATISFIABLE && n == SATISFIABLE) return Classification.MAY;
        if (y == SATISFIABLE && n == UNSATISFIABLE) return Classification.MUST;
        if (y == UNSATISFIABLE && n == SATISFIABLE) return Classification.NEVER;
        return Classification.UNKNOWN;
    }

    static Proof prove(Formula formula, Limits limits) {
        var query = new Query(formula,limits);
        var answer = SatConfigurationReasoner.INSTANCE.solve(query);
        if (answer.status() == SATISFIABLE && !formula.evaluate(answer.witness()))
            answer = new Answer(query.identity(),Satisfiability.UNKNOWN,Map.of(),answer.steps(),Optional.of("FORMULA_REPLAY_FAILED"));
        return new Proof(formula,answer);
    }
}
