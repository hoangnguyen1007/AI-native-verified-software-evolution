package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.*;

/** Tagged R0 declaration evidence. Missing source positions remain absent and are reported as gaps. */
public sealed interface ConditionEvidence {
    default ContentDigest identity() { return ConditionIdentitySupport.digest(this); }
    default List<SourceSpan> spans() { return List.of(); }

    record Source(String kind, SourceDocumentIdentity document, ContentDigest rawDigest,
                  Optional<SourceSpan> span, int ordinal) implements ConditionEvidence {
        public Source(SourceDocumentIdentity document, ContentDigest digest, Optional<SourceSpan> span, int ordinal) {
            this("SOURCE", document, digest, span, ordinal);
        }
        public Source {
            if (!"SOURCE".equals(kind)) throw new IllegalArgumentException("Invalid source evidence tag");
            Objects.requireNonNull(document); Objects.requireNonNull(rawDigest); Objects.requireNonNull(span);
            if (ordinal < 0 || span.isPresent() && !span.orElseThrow().document().equals(document)) {
                throw new IllegalArgumentException("Invalid source evidence position");
            }
        }
        @Override public List<SourceSpan> spans() { return span.stream().toList(); }
    }

    record Artifact(String kind, ContentDigest artifactDigest, ContentDigest entryDigest,
                    String metadataPointer) implements ConditionEvidence {
        public Artifact(ContentDigest artifact, ContentDigest entry, String pointer) {
            this("ARTIFACT", artifact, entry, pointer);
        }
        public Artifact {
            if (!"ARTIFACT".equals(kind)) throw new IllegalArgumentException("Invalid artifact evidence tag");
            Objects.requireNonNull(artifactDigest); Objects.requireNonNull(entryDigest);
            metadataPointer = ConditionIdentitySupport.name(metadataPointer);
        }
    }

    record Derived(String kind, List<ContentDigest> inputs, VersionedIdentifier method,
                   String slot) implements ConditionEvidence {
        public Derived(List<ContentDigest> inputs, VersionedIdentifier method, String slot) {
            this("DERIVED", inputs, method, slot);
        }
        public Derived {
            if (!"DERIVED".equals(kind)) throw new IllegalArgumentException("Invalid derived evidence tag");
            inputs = ContractChecks.sortedDistinct(inputs, Comparator.naturalOrder(), "derivation inputs");
            if (inputs.isEmpty()) throw new IllegalArgumentException("Derivation needs exact inputs");
            Objects.requireNonNull(method); slot = ConditionIdentitySupport.name(slot);
        }
    }
}
