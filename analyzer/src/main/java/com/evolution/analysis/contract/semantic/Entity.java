package com.evolution.analysis.contract.semantic;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.Optional;

/** Minimal stable semantic entity contract; parser-specific objects are intentionally absent. */
public record Entity(
        EntityIdentity identity,
        EntityOrigin origin,
        EntityScope stableScope,
        EntityKind kind,
        String canonicalName,
        Optional<SourceSpan> declaration)
        implements Comparable<Entity> {

    public Entity {
        ContractChecks.notNull(identity, "entity identity");
        ContractChecks.notNull(origin, "entity origin");
        ContractChecks.notNull(stableScope, "entity stable scope");
        stableScope.validateOrigin(origin);
        ContractChecks.notNull(kind, "entity kind");
        canonicalName = ContractChecks.text(canonicalName, "entity canonical name");
        declaration = ContractChecks.notNull(declaration, "entity declaration");
        EntityIdentity expected = EntityIdentity.from(origin, stableScope, kind, canonicalName);
        if (!identity.equals(expected)) {
            throw new IllegalArgumentException("entity identity does not match its canonical inputs");
        }
    }

    public static Entity create(
            EntityOrigin origin,
            EntityScope stableScope,
            EntityKind kind,
            String canonicalName,
            Optional<SourceSpan> declaration) {
        return new Entity(
                EntityIdentity.from(origin, stableScope, kind, canonicalName),
                origin,
                stableScope,
                kind,
                canonicalName,
                declaration);
    }

    @Override
    public int compareTo(Entity other) {
        return identity.compareTo(other.identity);
    }
}
