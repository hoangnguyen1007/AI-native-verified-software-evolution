package com.evolution.analysis.graph;

import java.util.*;
import java.util.function.Function;

/** Request-local projection workspace. Maps preserve canonical key order and must support
 * bounded iteration while other maps are updated. Storage never chooses domain facts. */
public interface GraphProjectionStore {
    @FunctionalInterface interface Contribution { void project(GraphProjectionStore store) throws java.io.IOException; }

    <T> Map<String,T> records(String space, Function<Object,T> decode);

    default <T> Iterable<T> valuesByKind(String space, Set<String> kinds, Function<Object,T> decode, Function<T,String> kindExtractor) {
        Objects.requireNonNull(space); Objects.requireNonNull(kinds); Objects.requireNonNull(decode); Objects.requireNonNull(kindExtractor);
        if (kinds.isEmpty()) return List.of();
        return () -> records(space, decode).values().stream().filter(v -> kinds.contains(kindExtractor.apply(v))).iterator();
    }

    static GraphProjectionStore memory() {
        return new GraphProjectionStore() {
            public <T> Map<String,T> records(String space,Function<Object,T> decode) {return new TreeMap<>();}
        };
    }
}
