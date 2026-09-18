package com.evolution.analysis.spring.binding;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Declaration identity, independent of candidate owner, realized configuration and selection. */
public record InjectionPoint(SpringBuildContext.Identity buildContextIdentity, String ownerDeclarationKey,
                             SiteKind siteKind, String siteSlot, ConditionEvidence declarationEvidenceKey) {
    public enum SiteKind { CONSTRUCTOR_PARAMETER, FIELD, METHOD_PARAMETER, BEAN_PARAMETER, XML_REFERENCE, GENERATED, OTHER }
    public InjectionPoint {
        Objects.requireNonNull(buildContextIdentity); ownerDeclarationKey = BindingIdentity.text(ownerDeclarationKey);
        Objects.requireNonNull(siteKind); siteSlot = BindingIdentity.text(siteSlot); Objects.requireNonNull(declarationEvidenceKey);
    }
    public Identity identity() {
        return new Identity(BindingIdentity.derive("spring-injection-point", Map.of(
                "buildContextIdentity", buildContextIdentity, "ownerDeclarationKey", ownerDeclarationKey,
                "siteKind", siteKind, "siteSlot", siteSlot, "declarationEvidenceKey", declarationEvidenceKey)));
    }
    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = BindingIdentity.require(value, "spring-injection-point"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }
}
