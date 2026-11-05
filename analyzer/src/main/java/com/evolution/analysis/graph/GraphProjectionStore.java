package com.evolution.analysis.graph;

import java.util.*;
import java.util.function.Function;

/** Request-local projection workspace. Maps preserve canonical key order and must support
 * bounded iteration while other maps are updated. Storage never chooses domain facts. */
public interface GraphProjectionStore {
    <T> Map<String,T> records(String space, Function<Object,T> decode);

    static GraphProjectionStore memory() {
        return new GraphProjectionStore() {
            public <T> Map<String,T> records(String space,Function<Object,T> decode) {return new TreeMap<>();}
        };
    }
}
