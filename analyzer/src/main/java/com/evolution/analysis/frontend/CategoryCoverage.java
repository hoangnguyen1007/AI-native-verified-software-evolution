package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.semantic.RelationshipKind;
import java.util.Objects;

/** Unsupported categories remain registered even when not visited by this slice. */
public record CategoryCoverage(RelationshipKind category, Support support, long attempted, long emitted, long unmappable)
        implements Comparable<CategoryCoverage> {
    public enum Support { IMPLEMENTED, PARTIAL, UNSUPPORTED }
    public CategoryCoverage {
        Objects.requireNonNull(category); Objects.requireNonNull(support);
        if (attempted < 0 || emitted < 0 || unmappable < 0 || attempted != emitted + unmappable) throw new IllegalArgumentException("coverage does not reconcile");
    }
    @Override public int compareTo(CategoryCoverage other) { return category.compareTo(other.category); }
}
