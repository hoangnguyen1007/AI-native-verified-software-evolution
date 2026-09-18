package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Solver-neutral bounded configuration reasoning port. */
public interface ConfigurationReasoner {
    enum Satisfiability { SATISFIABLE, UNSATISFIABLE, UNKNOWN }

    record WorldKey(String value) implements CanonicalIdentifier, Comparable<WorldKey> {
        public WorldKey { value = TruthIdentity.require(value, "spring-world"); }
        @Override public int compareTo(WorldKey other) { return value.compareTo(other.value); }
    }

    /** Members are an explicit region inside the explored universe. Complete=false preserves unexplored residue. */
    record Region(Set<WorldKey> universe, Set<WorldKey> members, boolean complete) {
        public Region {
            universe = Collections.unmodifiableSet(new TreeSet<>(universe));
            members = Collections.unmodifiableSet(new TreeSet<>(members));
            if (!universe.containsAll(members)) throw new IllegalArgumentException("Region contains a foreign world");
        }
    }

    VersionedIdentifier policy();

    FiniteConfigurationEvaluation.Result enumerate(ConditionModel model,
            ConditionExpression.Semantics semantics, FiniteConfigurationEvaluation.Limits limits);

    Satisfiability satisfiability(Region region);

    Optional<WorldKey> witness(Region region);

    LogicalValue implies(Region antecedent, Region consequent);

    LogicalValue equivalent(Region left, Region right);
}
