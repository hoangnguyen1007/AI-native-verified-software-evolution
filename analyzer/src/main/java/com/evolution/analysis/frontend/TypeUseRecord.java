package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.semantic.RelationshipKind;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.*;

/** Full written type syntax, separate from the per-named-leaf relationship occurrences. */
public record TypeUseRecord(Optional<EntityIdentity> owner, RelationshipKind role, SourceSpan span,
        JavaType type, boolean variadic) implements Comparable<TypeUseRecord> {
    public TypeUseRecord {
        Objects.requireNonNull(owner); Objects.requireNonNull(role); Objects.requireNonNull(span); Objects.requireNonNull(type);
    }
    @Override public int compareTo(TypeUseRecord other) {
        return com.evolution.analysis.contract.serialization.CanonicalJson.write(this)
                .compareTo(com.evolution.analysis.contract.serialization.CanonicalJson.write(other));
    }
}
