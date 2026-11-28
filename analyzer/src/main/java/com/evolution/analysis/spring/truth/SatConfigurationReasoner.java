package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Pure Java symbolic backend delegating to the clean-room Incremental CDCL solver.
 * Retains the original policy identifier so canonical digests remain compatible. */
public enum SatConfigurationReasoner implements ConfigurationReasoner {
    INSTANCE;
    @Override public SymbolicConfiguration.Answer solve(SymbolicConfiguration.Query query) {
        return IncrementalCdclReasoner.INSTANCE.solve(query);
    }
    public VersionedIdentifier policy() { return new VersionedIdentifier("spring.configuration-reasoner", "dpll-m4u.2-v1"); }
    public FiniteConfigurationEvaluation.Result enumerate(ConditionModel m, ConditionExpression.Semantics s, FiniteConfigurationEvaluation.Limits l) {
        return ExhaustiveConfigurationReasoner.INSTANCE.enumerate(m,s,l);
    }
    public Satisfiability satisfiability(Region r) { return ExhaustiveConfigurationReasoner.INSTANCE.satisfiability(r); }
    public Optional<WorldKey> witness(Region r) { return ExhaustiveConfigurationReasoner.INSTANCE.witness(r); }
    public LogicalValue implies(Region a, Region b) { return ExhaustiveConfigurationReasoner.INSTANCE.implies(a,b); }
    public LogicalValue equivalent(Region a, Region b) { return ExhaustiveConfigurationReasoner.INSTANCE.equivalent(a,b); }
}
