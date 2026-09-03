package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import java.util.*;

/** Recursive written type: arguments, array dimensions, wildcard bounds and member qualifiers remain distinct. */
public record JavaType(Kind kind, String spelling, Optional<EntityIdentity> target,
        List<JavaType> components, Optional<JavaType> qualifier, SemanticStatus status) {
    public enum Kind { PRIMITIVE, VOID, DECLARED, TYPE_VARIABLE, ARRAY, WILDCARD,
        EXTENDS_WILDCARD, SUPER_WILDCARD, INTERSECTION, UNION, UNKNOWN }
    public JavaType {
        Objects.requireNonNull(kind); Objects.requireNonNull(spelling); Objects.requireNonNull(target);
        components = List.copyOf(components); Objects.requireNonNull(qualifier); Objects.requireNonNull(status);
        boolean named = kind == Kind.DECLARED || kind == Kind.TYPE_VARIABLE;
        if (named != target.isPresent()) throw new IllegalArgumentException("named types require a verified entity; unknown types retain no guessed target");
        if (kind != Kind.DECLARED && kind != Kind.UNKNOWN && qualifier.isPresent()) throw new IllegalArgumentException("only named member types have qualifiers");
        switch (kind) {
            case ARRAY, EXTENDS_WILDCARD, SUPER_WILDCARD -> { if (components.size() != 1) throw new IllegalArgumentException("one component required"); }
            case PRIMITIVE, VOID, TYPE_VARIABLE, WILDCARD -> { if (!components.isEmpty()) throw new IllegalArgumentException("leaf type cannot contain components"); }
            case INTERSECTION, UNION -> { if (components.size() < 2) throw new IllegalArgumentException("compound type requires alternatives"); }
            default -> { }
        }
        if (kind == Kind.UNKNOWN && status == SemanticStatus.RESOLVED) throw new IllegalArgumentException("unknown type cannot be resolved");
        if (status == SemanticStatus.RESOLVED && (components.stream().anyMatch(t -> t.status() != SemanticStatus.RESOLVED)
                || qualifier.stream().anyMatch(t -> t.status() != SemanticStatus.RESOLVED))) throw new IllegalArgumentException("resolved type contains incomplete components");
    }
    public Set<EntityIdentity> referencedEntities() {
        var result = new HashSet<EntityIdentity>(); target.ifPresent(result::add);
        components.forEach(t -> result.addAll(t.referencedEntities())); qualifier.ifPresent(t -> result.addAll(t.referencedEntities()));
        return Set.copyOf(result);
    }
}
