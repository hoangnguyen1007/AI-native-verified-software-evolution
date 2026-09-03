package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrontendResultTest {
    static final VersionedIdentifier VERSION = new VersionedIdentifier("frontend.test", "1");
    static final RepositoryIdentity REPO = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/result.git");
    static final ModuleIdentity MODULE = ModuleIdentity.from(REPO, "fixture");
    static final SourceDocumentIdentity DOCUMENT = SourceDocumentIdentity.from(REPO, "fixture/A.java");
    static final SourceSpan SPAN = new SourceSpan(DOCUMENT, 1,1,1,5);
    static final Derivation DIRECT = new Derivation(DerivationKind.DIRECT, VERSION, List.of());
    static final Entity ENTITY = Entity.create(EntityOrigin.PROJECT, EntityScope.project(MODULE), EntityKind.METHOD,
            "java:v1:[\"method\",[\"type\",[],\"A\"],\"f\",[]]", Optional.of(SPAN));
    static final RelationshipOccurrence CALL = RelationshipOccurrence.create(SemanticRelationship.create(ENTITY.identity(), new RelationshipKind("java.calls"), new RelationshipTarget.Resolved(ENTITY.identity())), SPAN, 0, SemanticStatus.RESOLVED, DIRECT,List.of(),List.of());
    private static FrontendResult result(List<ObservationRecord> observations, List<SourceOutcome> sources, List<CategoryCoverage> coverage) {
        return new FrontendResult(AnalysisIdentity.fromCanonicalManifestInputs("{}"), VERSION, FrontendResult.State.COMPLETED,
                List.of(new DeclarationRecord(ENTITY,"f",SemanticStatus.RESOLVED,DIRECT,List.of(),List.of())), List.of(CALL), observations,sources,coverage,List.of());
    }
    private static ObservationRecord observation(String category, SourceSpan span, ObservationRecord.EvidenceState origin) {
        return new ObservationRecord(DOCUMENT,new RelationshipKind(category),Optional.of(span),SemanticStatus.RESOLVED,origin,ObservationRecord.EvidenceState.VERIFIED,Optional.of(CALL.identity()),"f()",List.of());
    }
    @Test void mappedLedgerMustAgreeWithOccurrenceCategorySpanAndOrigin() {
        var source = List.of(new SourceOutcome(DOCUMENT, SourceOutcome.State.PROCESSED,List.of()));
        var coverage = FrontendRequest.CATEGORIES.stream().map(c -> new CategoryCoverage(new RelationshipKind("java." + c),CategoryCoverage.Support.PARTIAL,c.equals("calls")?1:0,c.equals("calls")?1:0,0)).toList();
        assertDoesNotThrow(() -> result(List.of(observation("java.calls",SPAN,ObservationRecord.EvidenceState.VERIFIED)),source,coverage));
        assertThrows(IllegalArgumentException.class, () -> result(List.of(observation("java.calls",new SourceSpan(DOCUMENT,2,1,2,5),ObservationRecord.EvidenceState.VERIFIED)),source,coverage));
        assertThrows(IllegalArgumentException.class, () -> result(List.of(observation("java.calls",SPAN,ObservationRecord.EvidenceState.MISSING)),source,coverage));
    }
    @Test void nonemptyFactsCannotDisappearFromSourceAndCategoryCoverage() {
        var observation = List.of(observation("java.calls",SPAN,ObservationRecord.EvidenceState.VERIFIED));
        assertThrows(IllegalArgumentException.class, () -> result(observation,List.of(),List.of()));
    }
    @Test void foreignCategoryCannotHideOutsideRegisteredCoverage() {
        var diagnostic = new Diagnostic(DiagnosticSeverity.WARNING,"test.unsupported","Unsupported observation",Optional.of(SPAN),Map.of());
        var foreign = new ObservationRecord(DOCUMENT,new RelationshipKind("fake.calls"),Optional.of(SPAN),SemanticStatus.UNSUPPORTED,
                ObservationRecord.EvidenceState.MISSING,ObservationRecord.EvidenceState.VERIFIED,Optional.empty(),"f()",List.of(diagnostic));
        var coverage = FrontendRequest.CATEGORIES.stream().map(c -> new CategoryCoverage(new RelationshipKind("java." + c),CategoryCoverage.Support.PARTIAL,c.equals("calls")?1:0,c.equals("calls")?1:0,0)).toList();
        assertThrows(IllegalArgumentException.class,() -> result(List.of(observation("java.calls",SPAN,ObservationRecord.EvidenceState.VERIFIED),foreign),
                List.of(new SourceOutcome(DOCUMENT,SourceOutcome.State.PROCESSED,List.of())),coverage));
    }
}
