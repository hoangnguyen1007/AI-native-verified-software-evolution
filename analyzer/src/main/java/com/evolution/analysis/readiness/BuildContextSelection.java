package com.evolution.analysis.readiness;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.util.*;

public record BuildContextSelection(String schema, Map<String,List<String>> requested,
        List<String> discovered, List<String> selected, List<String> unselected, List<String> unknown) {
    public static final String SCHEMA="build-context-selection:m8.4-v1";
    public BuildContextSelection {
        if(!SCHEMA.equals(schema))throw new IllegalArgumentException("Unsupported selection");
        var copy=new TreeMap<String,List<String>>();requested.forEach((key,values)->copy.put(key,sorted(values)));requested=Collections.unmodifiableMap(copy);
        discovered=sorted(discovered);selected=sorted(selected);unselected=sorted(unselected);unknown=sorted(unknown);
        var partition=new TreeSet<>(selected);
        if(!Collections.disjoint(selected,unselected))throw new IllegalArgumentException("Overlapping selection");
        partition.addAll(unselected);
        if(!partition.equals(new TreeSet<>(discovered)))throw new IllegalArgumentException("Selection must close discovered denominator");
    }
    public ContentDigest identity(){return IngestionEvidence.digest(this);}
    static List<String> sorted(Collection<String> values){return values.stream().distinct().sorted().toList();}
}
