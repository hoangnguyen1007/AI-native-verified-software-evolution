package com.evolution.analysis.contract;

import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DerivedEvidenceTest {
    @Test void derivedEvidenceRetainsLanguageRuleInputsWithoutRequiringAFakeOccurrence() {
        var owner=EntityIdentity.from(EntityOrigin.PROJECT,EntityScope.project(ContractFixtures.moduleA().identity()),EntityKind.TYPE,"Owner");
        var target=EntityIdentity.from(EntityOrigin.PROJECT,EntityScope.project(ContractFixtures.moduleA().identity()),EntityKind.CONSTRUCTOR,"Owner()");
        var relationship=SemanticRelationship.create(owner,new RelationshipKind("java.declares"),new RelationshipTarget.Resolved(target));
        var derivation=new Derivation(DerivationKind.DERIVED,new VersionedIdentifier("java.default-constructor","1"),List.of(owner.value()));
        var support=new SourceSpan(ContractFixtures.sourceA().identity(),1,1,1,2);
        var result=new DerivedRelationshipRecord(relationship,SemanticStatus.RESOLVED,derivation,List.of(support),List.of(),List.of());
        assertEquals(List.of(support),result.supportingSpans());
        assertThrows(UnsupportedOperationException.class,() -> result.supportingSpans().clear());
        assertThrows(IllegalArgumentException.class,() -> new DerivedRelationshipRecord(relationship,SemanticStatus.UNRESOLVED,derivation,List.of(support),List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,() -> new DerivedRelationshipRecord(relationship,SemanticStatus.RESOLVED,new Derivation(DerivationKind.DIRECT,new VersionedIdentifier("java.source","1"),List.of()),List.of(support),List.of(),List.of()));
    }
}
