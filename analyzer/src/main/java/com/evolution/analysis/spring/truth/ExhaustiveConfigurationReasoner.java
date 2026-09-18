package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Deterministic small-space oracle. It is not a SAT/BDD backend. */
public enum ExhaustiveConfigurationReasoner implements ConfigurationReasoner {
    INSTANCE;

    public static final VersionedIdentifier POLICY =
            new VersionedIdentifier("spring.configuration-reasoner", "exhaustive-m4d-v1");

    @Override public VersionedIdentifier policy() { return POLICY; }

    @Override public FiniteConfigurationEvaluation.Result enumerate(ConditionModel model,
            ConditionExpression.Semantics semantics, FiniteConfigurationEvaluation.Limits limits) {
        return FiniteConfigurationEvaluation.evaluate(model, semantics, limits);
    }

    @Override public Satisfiability satisfiability(Region region) {
        if (!region.members().isEmpty()) return Satisfiability.SATISFIABLE;
        return region.complete() ? Satisfiability.UNSATISFIABLE : Satisfiability.UNKNOWN;
    }

    @Override public Optional<WorldKey> witness(Region region) {
        return region.members().stream().min(Comparator.naturalOrder());
    }

    @Override public LogicalValue implies(Region antecedent, Region consequent) {
        var counterexamples = new TreeSet<>(antecedent.members());
        counterexamples.retainAll(consequent.universe());
        counterexamples.removeAll(consequent.members());
        if (!counterexamples.isEmpty()) return LogicalValue.FALSE;
        return antecedent.complete() && consequent.complete()
                && antecedent.universe().equals(consequent.universe()) ? LogicalValue.TRUE : LogicalValue.UNKNOWN;
    }

    @Override public LogicalValue equivalent(Region left, Region right) {
        var leftTrueRightFalse = new TreeSet<>(left.members());
        leftTrueRightFalse.retainAll(right.universe());
        leftTrueRightFalse.removeAll(right.members());
        var rightTrueLeftFalse = new TreeSet<>(right.members());
        rightTrueLeftFalse.retainAll(left.universe());
        rightTrueLeftFalse.removeAll(left.members());
        if (!leftTrueRightFalse.isEmpty() || !rightTrueLeftFalse.isEmpty()) return LogicalValue.FALSE;
        return left.complete() && right.complete() && left.universe().equals(right.universe())
                ? LogicalValue.TRUE : LogicalValue.UNKNOWN;
    }
}
