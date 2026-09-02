package com.evolution.analysis.contract.semantic;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.EntityIdentity;
import java.util.Comparator;
import java.util.List;

/** Explicit resolved, candidate, or unresolved target state. */
public sealed interface RelationshipTarget
        permits RelationshipTarget.Resolved,
                RelationshipTarget.Candidates,
                RelationshipTarget.Unresolved {

    record Resolved(EntityIdentity target) implements RelationshipTarget {
        public Resolved {
            ContractChecks.notNull(target, "resolved target");
        }
    }

    record Candidates(List<EntityIdentity> candidates) implements RelationshipTarget {
        public Candidates {
            candidates = ContractChecks.sortedDistinct(
                    candidates, Comparator.naturalOrder(), "target candidates");
            if (candidates.size() < 2) {
                throw new IllegalArgumentException("candidate targets require at least two identities");
            }
        }
    }

    record Unresolved(String stableReference) implements RelationshipTarget {
        public Unresolved {
            stableReference =
                    ContractChecks.text(stableReference, "unresolved stable reference");
        }
    }
}
