package com.evolution.analysis.contract.semantic;

/** Evidence state of one semantic occurrence. */
public enum SemanticStatus {
    RESOLVED,
    PARTIAL,
    UNRESOLVED,
    AMBIGUOUS,
    CONDITIONAL,
    UNSUPPORTED,
    ERROR
}
