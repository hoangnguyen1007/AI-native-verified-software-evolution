package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.ContractChecks;

/** Downstream result family that must remain qualified while a gap or conflict is open. */
public record AffectedOutput(Kind kind, String identifier) implements Comparable<AffectedOutput> {
    public enum Kind {
        FRONTEND_INPUT, RELATIONSHIP_CATEGORY, GRAPH_PROJECTION, METRIC, POLICY, ASSESSMENT, ANALYSIS_COVERAGE
    }
    public AffectedOutput {
        ContractChecks.notNull(kind, "affected output kind");
        identifier = ContractChecks.namespacedId(identifier, "affected output identifier");
    }
    @Override public int compareTo(AffectedOutput other) {
        int kindComparison = kind.compareTo(other.kind);
        return kindComparison != 0 ? kindComparison : identifier.compareTo(other.identifier);
    }
}
