package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/** Written field/annotation evidence only. This does not assert a generated constructor or a bean binding. */
public record LombokConstructorCandidate(EntityIdentity owner, EntityIdentity field, int fieldOrder,
        String generator, JavaType type, List<SourceSpan> supportingSpans, Status status)
        implements Comparable<LombokConstructorCandidate> {
    public enum Status { CANDIDATE }
    public LombokConstructorCandidate {
        Objects.requireNonNull(owner);Objects.requireNonNull(field);Objects.requireNonNull(type);Objects.requireNonNull(status);
        if(fieldOrder<0||!Set.of("RequiredArgsConstructor","AllArgsConstructor","Data","Value","Builder").contains(generator))
            throw new IllegalArgumentException("Invalid Lombok constructor candidate");
        supportingSpans=List.copyOf(supportingSpans);
        if(supportingSpans.size()!=2)throw new IllegalArgumentException("Annotation and field source spans required");
    }
    @Override public int compareTo(LombokConstructorCandidate other){return CanonicalJson.write(this).compareTo(CanonicalJson.write(other));}
}
