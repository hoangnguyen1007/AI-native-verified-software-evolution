package com.evolution.backend.query;

import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import java.math.BigDecimal;
import java.util.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Strict transport decoder; the canonical query remains owned by analyzer. */
public final class QueryJson {
    public static ArchitectureQuery.Request request(ContentDigest graph, Object value) {
        var row = object(value, "graph", "context", "scope", "projection", "direction", "relationshipKinds",
                "population", "includeMust", "realization", "limits");
        if (row.containsKey("graph") && !graph.equals(new ContentDigest(text(row, "graph"))))
            throw new IllegalArgumentException("Foreign graph");
        var population = Population.all();
        if (row.containsKey("population")) {
            var p = object(row.get("population"), "nodeKinds", "modules", "origins", "sourceSets");
            population = new Population(strings(p, "nodeKinds"), strings(p, "modules"), strings(p, "origins"), strings(p, "sourceSets"));
        }
        Optional<Realization> realization = Optional.empty();
        if (row.get("realization") != null) {
            var r = object(row.get("realization"), "world", "assignment");
            var assignment = new TreeMap<String,Boolean>();
            if (r.containsKey("assignment")) GraphJson.object(r.get("assignment")).forEach((k,v) -> {
                if (k.isBlank() || k.length() > 256 || !(v instanceof Boolean flag)) throw new IllegalArgumentException("Invalid assignment");
                assignment.put(k, flag);
            });
            realization = Optional.of(new Realization(optionalText(r, "world"), assignment));
        }
        var defaults = Limits.defaults();
        var l = row.containsKey("limits") ? object(row.get("limits"), "maxDepth", "maxSteps", "maxResults", "maxVertices",
                "maxEdges", "maxSolverSteps", "maxFormulaNodes", "maxAuxiliaryStates") : Map.<String,Object>of();
        var limits = new Limits(integer(l,"maxDepth",defaults.maxDepth()), number(l,"maxSteps",defaults.maxSteps()),
                integer(l,"maxResults",defaults.maxResults()), integer(l,"maxVertices",defaults.maxVertices()),
                integer(l,"maxEdges",defaults.maxEdges()), number(l,"maxSolverSteps",defaults.maxSolverSteps()),
                integer(l,"maxFormulaNodes",defaults.maxFormulaNodes()), integer(l,"maxAuxiliaryStates",defaults.maxAuxiliaryStates()));
        return new Request(graph, text(row,"context"), Scope.valueOf(text(row,"scope")), Projection.valueOf(text(row,"projection")),
                Direction.valueOf(text(row,"direction")), stringsRequired(row,"relationshipKinds"), population,
                row.containsKey("includeMust") ? flag(row.get("includeMust")) : false, realization, limits);
    }
    public static Page page(Object value) {
        if (value == null) return Page.first();
        var row = object(value,"size","cursor");
        return new Page(integer(row,"size",50),optionalText(row,"cursor"));
    }
    static Map<String,Object> object(Object value, String... allowed) {
        var row = GraphJson.object(value);
        if (!Set.of(allowed).containsAll(row.keySet())) throw new IllegalArgumentException("Unknown field");
        return row;
    }
    static String text(Map<String,Object> row, String key) {
        String value = GraphJson.string(row.get(key));
        if (value.isBlank() || value.length() > 8192) throw new IllegalArgumentException("Invalid text");
        return value;
    }
    static Optional<String> optionalText(Map<String,Object> row, String key) {
        return row.get(key) == null ? Optional.empty() : Optional.of(text(row,key));
    }
    static Set<String> strings(Map<String,Object> row, String key) {
        return row.containsKey(key) ? stringsRequired(row,key) : Set.of();
    }
    static Set<String> stringsRequired(Map<String,Object> row, String key) {
        var result = new TreeSet<String>();
        for (Object element : GraphJson.array(row.get(key))) {
            String text = GraphJson.string(element);
            if (text.isBlank() || text.length() > 8192 || !result.add(text)) throw new IllegalArgumentException("Invalid set");
        }
        return result;
    }
    private static boolean flag(Object value) {
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("Boolean required");
        return flag;
    }
    private static long number(Map<String,Object> row, String key, long fallback) {
        if (!row.containsKey(key)) return fallback;
        if (!(row.get(key) instanceof BigDecimal value)) throw new IllegalArgumentException("Integer required");
        try { return value.longValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("Invalid integer"); }
    }
    private static int integer(Map<String,Object> row, String key, int fallback) {
        long value = number(row,key,fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Integer out of range");
        return (int)value;
    }
    private QueryJson() {}
}
