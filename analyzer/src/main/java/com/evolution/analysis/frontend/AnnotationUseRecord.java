package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.*;

/** A source annotation use; site describes syntax, not an inferred @Target/retention interpretation. */
public record AnnotationUseRecord(String canonicalName, EntityIdentity owner, SourceSpan span,
        String site, String spelling) implements Comparable<AnnotationUseRecord> {
    public AnnotationUseRecord {
        Objects.requireNonNull(canonicalName); Objects.requireNonNull(owner); Objects.requireNonNull(span); Objects.requireNonNull(spelling);
        if (!canonicalName.startsWith("java:v1:[\"annotation-use\",")) throw new IllegalArgumentException("annotation use key");
        if (!Set.of("declaration-syntax","type-syntax","package-syntax","annotation-value").contains(site)) throw new IllegalArgumentException("annotation site");
    }
    @Override public int compareTo(AnnotationUseRecord other) { return canonicalName.compareTo(other.canonicalName); }
}
