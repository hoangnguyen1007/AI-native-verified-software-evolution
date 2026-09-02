package com.evolution.analysis.contract.identity;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import java.util.List;

/** Identity from origin, stable scope, entity category, and canonical language-level symbol. */
public record EntityIdentity(String value) implements CanonicalIdentifier, Comparable<EntityIdentity> {

    public EntityIdentity {
        value = IdentitySupport.require(value, "entity");
    }

    public static EntityIdentity from(
            EntityOrigin origin, EntityScope stableScope, EntityKind kind, String canonicalName) {
        ContractChecks.notNull(origin, "entity origin");
        ContractChecks.notNull(stableScope, "entity stable scope");
        stableScope.validateOrigin(origin);
        ContractChecks.notNull(kind, "entity kind");
        ContractChecks.text(canonicalName, "entity canonical name");
        return new EntityIdentity(IdentitySupport.derive(
                "entity", List.of(origin.name(), stableScope.value(), kind.name(), canonicalName)));
    }

    @Override
    public int compareTo(EntityIdentity other) {
        return value.compareTo(other.value);
    }
}
