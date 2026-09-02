package com.evolution.analysis.contract.identity;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.List;

public record OccurrenceIdentity(String value)
        implements CanonicalIdentifier, Comparable<OccurrenceIdentity> {

    public OccurrenceIdentity {
        value = IdentitySupport.require(value, "occurrence");
    }

    public static OccurrenceIdentity from(
            RelationshipIdentity relationship, SourceSpan span, int ordinal) {
        ContractChecks.notNull(relationship, "relationship");
        ContractChecks.notNull(span, "source span");
        if (ordinal < 0) {
            throw new IllegalArgumentException("occurrence ordinal must be non-negative");
        }
        return new OccurrenceIdentity(IdentitySupport.derive(
                "occurrence",
                List.of(relationship.value(), CanonicalJson.write(span), Integer.toString(ordinal))));
    }

    @Override
    public int compareTo(OccurrenceIdentity other) {
        return value.compareTo(other.value);
    }
}
