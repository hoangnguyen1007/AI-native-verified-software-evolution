package com.evolution.analysis.ingestion;

import com.evolution.analysis.evidence.CapabilityGapRecord;
import java.util.*;
import java.util.function.Function;

/** Request-owned storage for neutral analysis rows. Its lifetime includes graph publication.
 * Providers retain no parser objects here; scheduling and storage never change semantic identity. */
public interface AnalysisWorkspace {
    <T> List<T> list();
    Set<CapabilityGapRecord> gaps(Collection<CapabilityGapRecord> initial);
    <T> List<T> distinct(Collection<T> rows, Function<T,String> key);

    AnalysisWorkspace MEMORY = new AnalysisWorkspace() {
        public <T> List<T> list(){return new ArrayList<>();}
        public Set<CapabilityGapRecord> gaps(Collection<CapabilityGapRecord> initial){return new TreeSet<>(initial);}
        public <T> List<T> distinct(Collection<T> rows,Function<T,String> key){
            var ordered=new TreeMap<String,T>();for(var row:rows)ordered.putIfAbsent(key.apply(row),row);return List.copyOf(ordered.values());
        }
    };

    /** Immutable replay list: construction must not materialize an external table. */
    abstract class ReplayList<T> extends AbstractList<T> {
        public static <T> List<T> copy(List<T> rows){return rows instanceof ReplayList<?>?rows:List.copyOf(rows);}
        public static <T,R> List<R> map(List<T> rows,Function<T,R> mapper){
            return new ReplayList<>() {
                public int size(){return rows.size();}
                public R get(int index){return mapper.apply(rows.get(index));}
            };
        }
    }
    /** Seal a mutable request table before exposing it through an immutable contract. */
    default <T> List<T> freeze(List<T> rows){return List.copyOf(rows);}
}
