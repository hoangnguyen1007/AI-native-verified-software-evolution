package com.evolution.benchmark.frontend;

/** Result state before M1 mapping; it never represents a guessed resolved target. */
public enum ObservationState {
    RESOLVED,
    UNRESOLVED,
    AMBIGUOUS,
    UNSUPPORTED,
    ERROR
}
