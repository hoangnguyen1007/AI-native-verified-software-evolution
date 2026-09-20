package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.identity.EntityIdentity;
import java.util.Objects;

/** Parser-neutral declaration shape, tied to the original declaration and its source evidence. */
public record TypeDeclarationRecord(EntityIdentity type, Kind kind, boolean abstractType, boolean independent)
        implements Comparable<TypeDeclarationRecord> {
    public enum Kind { CLASS, INTERFACE, RECORD, ENUM, ANNOTATION }
    public TypeDeclarationRecord { Objects.requireNonNull(type); Objects.requireNonNull(kind); }
    @Override public int compareTo(TypeDeclarationRecord other) { return type.compareTo(other.type); }
}
