package com.evolution.analysis.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.semantic.Derivation;
import com.evolution.analysis.contract.semantic.DerivationKind;
import com.evolution.analysis.contract.semantic.Diagnostic;
import com.evolution.analysis.contract.semantic.DiagnosticSeverity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.semantic.RelationshipKind;
import com.evolution.analysis.contract.semantic.RelationshipOccurrence;
import com.evolution.analysis.contract.semantic.RelationshipTarget;
import com.evolution.analysis.contract.semantic.SemanticRelationship;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.contract.semantic.Uncertainty;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SemanticContractTest {

    private static final EntityIdentity SOURCE = EntityIdentity.from(
            EntityOrigin.PROJECT,
            EntityScope.project(ContractFixtures.moduleA().identity()),
            EntityKind.METHOD,
            "example.A#run()");
    private static final EntityIdentity TARGET_A = EntityIdentity.from(
            EntityOrigin.PROJECT,
            EntityScope.project(ContractFixtures.moduleA().identity()),
            EntityKind.METHOD,
            "example.B#work()");
    private static final EntityIdentity TARGET_B = EntityIdentity.from(
            EntityOrigin.DEPENDENCY,
            EntityScope.external(
                    EntityOrigin.DEPENDENCY,
                    "org.example:library:1",
                    com.evolution.analysis.contract.common.ContentDigest.sha256Utf8(
                            "library-binary")),
            EntityKind.METHOD,
            "example.B#work()");
    private static final RelationshipKind CALLS = new RelationshipKind("java.calls");

    @Test
    void candidateTargetsAreSortedAndCannotContainDuplicates() {
        RelationshipTarget.Candidates candidates =
                new RelationshipTarget.Candidates(List.of(TARGET_B, TARGET_A));

        assertEquals(List.of(TARGET_A, TARGET_B).stream().sorted().toList(), candidates.candidates());
        assertThrows(
                IllegalArgumentException.class,
                () -> new RelationshipTarget.Candidates(List.of(TARGET_A, TARGET_A)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RelationshipTarget.Candidates(List.of(TARGET_A)));
    }

    @Test
    void relationshipIdentityIncludesTargetStateWithoutInventingResolution() {
        SemanticRelationship resolved = SemanticRelationship.create(
                SOURCE, CALLS, new RelationshipTarget.Resolved(TARGET_A));
        SemanticRelationship unresolved = SemanticRelationship.create(
                SOURCE, CALLS, new RelationshipTarget.Unresolved("example.B#work()"));

        assertTrue(resolved.target() instanceof RelationshipTarget.Resolved);
        assertTrue(unresolved.target() instanceof RelationshipTarget.Unresolved);
        assertTrue(!resolved.identity().equals(unresolved.identity()));
    }

    @Test
    void ambiguousOccurrenceRequiresCandidateTargetsAndExplicitUncertainty() {
        SemanticRelationship candidates = SemanticRelationship.create(
                SOURCE, CALLS, new RelationshipTarget.Candidates(List.of(TARGET_A, TARGET_B)));
        SourceSpan span = new SourceSpan(ContractFixtures.sourceA().identity(), 1, 20, 1, 26);
        Derivation derivation = new Derivation(
                DerivationKind.DIRECT,
                new VersionedIdentifier("semantic.source-observation", "1"),
                List.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> RelationshipOccurrence.create(
                        candidates,
                        span,
                        0,
                        SemanticStatus.AMBIGUOUS,
                        derivation,
                        List.of(),
                        List.of()));
        RelationshipOccurrence occurrence = RelationshipOccurrence.create(
                candidates,
                span,
                0,
                SemanticStatus.AMBIGUOUS,
                derivation,
                List.of(new Uncertainty(
                        "semantic.multiple-candidates",
                        "Two targets remain possible.",
                        List.of("exact-classpath-binding"))),
                List.of());

        assertEquals(SemanticStatus.AMBIGUOUS, occurrence.status());
    }

    @Test
    void semanticStatusMustAgreeWithTargetShape() {
        SemanticRelationship unresolved = SemanticRelationship.create(
                SOURCE, CALLS, new RelationshipTarget.Unresolved("example.B#work()"));
        SourceSpan span = new SourceSpan(ContractFixtures.sourceA().identity(), 1, 20, 1, 26);

        assertThrows(
                IllegalArgumentException.class,
                () -> RelationshipOccurrence.create(
                        unresolved,
                        span,
                        0,
                        SemanticStatus.RESOLVED,
                        directDerivation(),
                        List.of(),
                        List.of()));
    }

    @Test
    void errorStatusRequiresAnErrorDiagnostic() {
        SemanticRelationship error = SemanticRelationship.create(
                SOURCE, CALLS, new RelationshipTarget.Unresolved("example.B#work()"));
        SourceSpan span = new SourceSpan(ContractFixtures.sourceA().identity(), 1, 20, 1, 26);
        Uncertainty uncertainty = new Uncertainty(
                "semantic.frontend-error", "Frontend failed.", List.of("target"));

        assertThrows(
                IllegalArgumentException.class,
                () -> RelationshipOccurrence.create(
                        error,
                        span,
                        0,
                        SemanticStatus.ERROR,
                        directDerivation(),
                        List.of(uncertainty),
                        List.of()));
        RelationshipOccurrence occurrence = RelationshipOccurrence.create(
                error,
                span,
                0,
                SemanticStatus.ERROR,
                directDerivation(),
                List.of(uncertainty),
                List.of(new Diagnostic(
                        DiagnosticSeverity.ERROR,
                        "parser.failure",
                        "Resolution failed.",
                        Optional.of(span),
                        Map.of("exceptionType", "ExampleFailure"))));

        assertEquals(DiagnosticSeverity.ERROR, occurrence.diagnostics().getFirst().severity());
    }

    @Test
    void derivedAndInferredFactsRequireTraceableInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Derivation(
                        DerivationKind.DERIVED,
                        new VersionedIdentifier("semantic.transitive", "1"),
                        List.of()));
    }

    private static Derivation directDerivation() {
        return new Derivation(
                DerivationKind.DIRECT,
                new VersionedIdentifier("semantic.source-observation", "1"),
                List.of());
    }
}
