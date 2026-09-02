package com.evolution.analysis.contract.semantic;

import com.evolution.analysis.contract.common.ContractChecks;

/** Version-independent namespaced relationship category, such as {@code java.calls}. */
public record RelationshipKind(String value) implements Comparable<RelationshipKind> {

    public RelationshipKind {
        value = ContractChecks.namespacedId(value, "relationship kind");
    }

    @Override
    public int compareTo(RelationshipKind other) {
        return value.compareTo(other.value);
    }
}
