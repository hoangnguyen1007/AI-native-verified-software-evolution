package com.evolution.analysis.contract.semantic;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.RelationshipIdentity;

/** Stable semantic relationship shape; evidence and state live on occurrences. */
public record SemanticRelationship(
        RelationshipIdentity identity,
        EntityIdentity source,
        RelationshipKind kind,
        RelationshipTarget target)
        implements Comparable<SemanticRelationship> {

    public SemanticRelationship {
        ContractChecks.notNull(identity, "relationship identity");
        ContractChecks.notNull(source, "relationship source");
        ContractChecks.notNull(kind, "relationship kind");
        ContractChecks.notNull(target, "relationship target");
        RelationshipIdentity expected = RelationshipIdentity.from(source, kind, target);
        if (!identity.equals(expected)) {
            throw new IllegalArgumentException(
                    "relationship identity does not match its canonical inputs");
        }
    }

    public static SemanticRelationship create(
            EntityIdentity source, RelationshipKind kind, RelationshipTarget target) {
        return new SemanticRelationship(
                RelationshipIdentity.from(source, kind, target), source, kind, target);
    }

    @Override
    public int compareTo(SemanticRelationship other) {
        return identity.compareTo(other.identity);
    }
}
