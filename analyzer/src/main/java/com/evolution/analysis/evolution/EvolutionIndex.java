package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.IOException;
import java.util.*;

/** Single-request scratch and result index. Storage orders ASCII keys; the domain owns their meaning. */
public interface EvolutionIndex extends AutoCloseable {
    record Entry(String key, Map<String,Object> value) {}
    record Batch(List<Entry> rows,boolean more) {public Batch{rows=List.copyOf(rows);}}
    Optional<Map<String,Object>> get(String table,String key) throws IOException;
    void put(String table,String key,Map<String,Object> value) throws IOException;
    Batch page(String table,String afterExclusive,int size) throws IOException;
    @Override default void close() throws IOException {}

    static EvolutionIndex memory() {
        return new EvolutionIndex() {
            private final Map<String,NavigableMap<String,Map<String,Object>>> tables=new HashMap<>();
            private NavigableMap<String,Map<String,Object>> table(String name) {
                return tables.computeIfAbsent(name,ignored->new TreeMap<>());
            }
            public Optional<Map<String,Object>> get(String name,String key) { return Optional.ofNullable(table(name).get(key)); }
            public void put(String name,String key,Map<String,Object> value) {
                table(name).put(key,GraphJson.object(GraphJson.read(CanonicalJson.write(value),GraphJson.Limits.defaults())));
            }
            public Batch page(String name,String after,int size) {
                var rows=table(name).tailMap(after,false).entrySet().stream().limit((long)size+1)
                        .map(row->new Entry(row.getKey(),row.getValue())).toList();
                return new Batch(rows.subList(0,Math.min(size,rows.size())),rows.size()>size);
            }
        };
    }
}
