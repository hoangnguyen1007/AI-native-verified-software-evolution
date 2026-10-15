package com.evolution.analysis.graph;

import java.util.*;

/** Strict bounded JSON reader for the graph format, without polymorphic class loading. */
public final class GraphJson {
    public static final class LimitExceededException extends IllegalArgumentException {
        public LimitExceededException(String message) {super(message);}
    }
    public record Limits(int maxCharacters, int maxDepth, int maxValues) {
        public Limits {
            if (maxCharacters < 1 || maxDepth < 1 || maxDepth > 256 || maxValues < 1)
                throw new IllegalArgumentException("Invalid graph JSON limits");
        }
        public static Limits defaults() { return new Limits(64_000_000, 128, 2_000_000); }
    }
    public static Object read(String json, Limits limits) {
        Objects.requireNonNull(json); Objects.requireNonNull(limits);
        if (json.length() > limits.maxCharacters()) throw new LimitExceededException("Graph JSON character limit");
        try{return read(new java.io.StringReader(json),limits);}catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
    }
    public static Object read(java.io.Reader reader,Limits limits)throws java.io.IOException {return new StreamingGraphJson(reader,limits).read();}
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?> map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String,Object>) map;
    }
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected JSON array");
        return (List<Object>) list;
    }
    public static String string(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Expected JSON string");
        return text;
    }
    static void fields(Map<String,Object> object, String... names) {
        if (!object.keySet().equals(Set.of(names))) throw new IllegalArgumentException("Unknown or missing graph fields");
    }
}
