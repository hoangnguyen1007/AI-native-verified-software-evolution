package com.evolution.analysis.contract.identity;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.semantic.RelationshipKind;
import com.evolution.analysis.contract.semantic.RelationshipTarget;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.List;

public record RelationshipIdentity(String value)
        implements CanonicalIdentifier, Comparable<RelationshipIdentity> {

    public RelationshipIdentity {
        value = IdentitySupport.require(value, "relationship");
    }

    public static RelationshipIdentity from(
            EntityIdentity source, RelationshipKind kind, RelationshipTarget target) {
        ContractChecks.notNull(source, "relationship source");
        ContractChecks.notNull(kind, "relationship kind");
        ContractChecks.notNull(target, "relationship target");
        return new RelationshipIdentity(IdentitySupport.derive(
                "relationship", List.of(source.value(), kind.value(), CanonicalJson.write(target))));
    }

    @Override
    public int compareTo(RelationshipIdentity other) {
        return value.compareTo(other.value);
    }
}
