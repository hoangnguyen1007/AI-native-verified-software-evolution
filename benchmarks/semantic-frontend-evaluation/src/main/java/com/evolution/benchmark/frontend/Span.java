package com.evolution.benchmark.frontend;

/** One-based, end-exclusive source coordinates independent of either parser API. */
public record Span(int startLine, int startColumn, int endLine, int endColumn) {
    public Span {
        if (startLine < 1 || startColumn < 1 || endLine < startLine
                || endLine == startLine && endColumn <= startColumn) {
            throw new IllegalArgumentException("invalid complete source span");
        }
    }
}
