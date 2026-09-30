package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.identity.EntityIdentity;
import java.util.Objects;
import javax.lang.model.SourceVersion;

/** Neutral source member shape. The referenced declaration owns its original span and origin. */
public record MemberDeclarationRecord(EntityIdentity member, String name, boolean staticMember,
                                      boolean abstractMember, boolean genericMethod)
        implements Comparable<MemberDeclarationRecord> {
    public MemberDeclarationRecord {
        Objects.requireNonNull(member); Objects.requireNonNull(name);
        // Java identifier equality does not apply Unicode normalization.
        if(!SourceVersion.isIdentifier(name))throw new IllegalArgumentException("Invalid member identifier");
    }
    @Override public int compareTo(MemberDeclarationRecord other) { return member.compareTo(other.member); }
}
