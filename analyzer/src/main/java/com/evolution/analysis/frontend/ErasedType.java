package com.evolution.analysis.frontend;

import java.util.List;
import java.util.Set;

/** Declaration parameter erasure; arrays are flattened to element plus positive rank. */
public final class ErasedType {
    private final List<Object> tuple;
    private ErasedType(Object... components) { tuple = List.of(components); }
    public static ErasedType primitive(String name) {
        if (!Set.of("boolean", "byte", "short", "int", "long", "char", "float", "double").contains(name)) throw new IllegalArgumentException("parameter primitive required");
        return new ErasedType("primitive", name);
    }
    public static ErasedType declared(JavaSymbolName type) { return new ErasedType("declared", JavaSymbolName.typeOwner(type)); }
    public static ErasedType array(ErasedType element, int rank) {
        if (rank < 1) throw new IllegalArgumentException("positive array rank required");
        if (element.tuple.getFirst().equals("array")) return new ErasedType("array", element.tuple.get(1), Math.addExact(rank, (int) element.tuple.get(2)));
        return new ErasedType("array", element.tuple, rank);
    }
    List<Object> tuple() { return tuple; }
    @Override public boolean equals(Object other) { return other instanceof ErasedType type && tuple.equals(type.tuple); }
    @Override public int hashCode() { return tuple.hashCode(); }
}
