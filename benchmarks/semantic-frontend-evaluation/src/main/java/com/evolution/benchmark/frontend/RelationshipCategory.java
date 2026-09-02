package com.evolution.benchmark.frontend;

/** The shared, architecture-relevant semantic denominator used by both adapters. */
public enum RelationshipCategory {
    DECLARES,
    EXTENDS,
    IMPLEMENTS,
    PERMITS,
    TYPE_USE,
    PARAMETER_TYPE,
    RETURN_TYPE,
    THROWS,
    ANNOTATED_WITH,
    CALLS,
    CONSTRUCTOR_CALLS,
    FIELD_READS,
    FIELD_WRITES,
    METHOD_REFERENCE
}
