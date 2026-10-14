package com.evolution.analysis.graph;

/** Sanitized operational/integrity outcome; raw host paths are never included. */
public final class GraphStoreException extends RuntimeException {
    public enum Reason { IO_FAILURE, CORRUPT_SNAPSHOT, LIMIT_EXCEEDED, UNSAFE_PATH, CANCELLED }
    private final Reason reason;
    public GraphStoreException(Reason reason) {super("Graph store: "+reason.name());this.reason=reason;}
    public Reason reason() {return reason;}
}
