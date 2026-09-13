package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import java.util.*;

/** One denominator row. Shared formulas do not erase separate declaration occurrences. */
public record ConditionOccurrence(ConditionExpression expression, ConditionEvidence declarationEvidenceKey,
                                  String metadataPath, String role, int ordinal) {
    public ConditionOccurrence {
        Objects.requireNonNull(expression); Objects.requireNonNull(declarationEvidenceKey);
        metadataPath = ConditionIdentitySupport.name(metadataPath); role = ConditionIdentitySupport.name(role);
        if (ordinal < 0) throw new IllegalArgumentException("Occurrence ordinal must not be negative");
    }
    public Identity identity() {
        return new Identity(ConditionIdentitySupport.derive("spring-condition-occurrence", canonicalForm()));
    }
    public Map<String, Object> canonicalForm() {
        return Map.of("expressionIdentity", expression.identity(), "declarationEvidenceKey", declarationEvidenceKey,
                "metadataPath", metadataPath, "role", role, "ordinal", ordinal);
    }
    public View view() { return new View(expression.identity(), declarationEvidenceKey, metadataPath, role, ordinal); }
    /** Typed flat export; the formula itself is identified separately in the expression table. */
    public record View(ConditionExpression.Identity expressionIdentity, ConditionEvidence declarationEvidenceKey,
                       String metadataPath, String role, int ordinal) {
        public View {
            Objects.requireNonNull(expressionIdentity); Objects.requireNonNull(declarationEvidenceKey);
            metadataPath = ConditionIdentitySupport.name(metadataPath); role = ConditionIdentitySupport.name(role);
            if (ordinal < 0) throw new IllegalArgumentException("Occurrence ordinal must not be negative");
        }
        public Identity identity() {
            return new Identity(ConditionIdentitySupport.derive("spring-condition-occurrence", Map.of(
                    "expressionIdentity", expressionIdentity, "declarationEvidenceKey", declarationEvidenceKey,
                    "metadataPath", metadataPath, "role", role, "ordinal", ordinal)));
        }
    }
    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = ConditionIdentitySupport.require(value, "spring-condition-occurrence"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }
}
