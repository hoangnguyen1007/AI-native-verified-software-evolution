package com.evolution.analysis.spring.condition;

import java.util.Objects;

/** Strong Kleene algebra, spring.condition-ir:m4b.1-v1. No Spring predicate evaluation. */
public enum LogicalValue {
    TRUE, FALSE, UNKNOWN;

    public LogicalValue not() {
        return switch (this) { case TRUE -> FALSE; case FALSE -> TRUE; case UNKNOWN -> UNKNOWN; };
    }

    public LogicalValue and(LogicalValue other) {
        Objects.requireNonNull(other);
        return this == FALSE || other == FALSE ? FALSE : this == TRUE && other == TRUE ? TRUE : UNKNOWN;
    }

    public LogicalValue or(LogicalValue other) {
        Objects.requireNonNull(other);
        return this == TRUE || other == TRUE ? TRUE : this == FALSE && other == FALSE ? FALSE : UNKNOWN;
    }
}
