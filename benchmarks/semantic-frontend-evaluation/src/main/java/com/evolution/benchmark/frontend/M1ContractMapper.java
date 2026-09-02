package com.evolution.benchmark.frontend;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.semantic.Derivation;
import com.evolution.analysis.contract.semantic.DerivationKind;
import com.evolution.analysis.contract.semantic.Diagnostic;
import com.evolution.analysis.contract.semantic.DiagnosticSeverity;
import com.evolution.analysis.contract.semantic.Entity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.semantic.RelationshipKind;
import com.evolution.analysis.contract.semantic.RelationshipOccurrence;
import com.evolution.analysis.contract.semantic.RelationshipTarget;
import com.evolution.analysis.contract.semantic.SemanticRelationship;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.contract.semantic.Uncertainty;
import com.evolution.analysis.contract.source.SourceSpan;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Maps only parser-neutral observations into the existing M1 contracts. */
public final class M1ContractMapper {
    private final RepositoryIdentity repository;
    private final ModuleIdentity module;

    private M1ContractMapper(RepositoryIdentity repository, ModuleIdentity module) {
        this.repository = repository;
        this.module = module;
    }

    public static M1ContractMapper forFixture() {
        RepositoryIdentity repository = RepositoryIdentity.fromCanonicalCoordinate(
                URI.create("https://example.invalid/semantic-fixture").normalize().toString());
        return new M1ContractMapper(repository, ModuleIdentity.from(repository, "fixture"));
    }

    public RelationshipOccurrence map(Observation observation) {
        SourceDocumentIdentity document = SourceDocumentIdentity.from(repository,
                observation.sourceFile().toString().replace('\\', '/'));
        SourceSpan span = new SourceSpan(document, observation.span().startLine(), observation.span().startColumn(),
                observation.span().endLine(), observation.span().endColumn());
        Entity source = entity(EntityOrigin.PROJECT, observation.sourceIdentity(), document, span);
        RelationshipTarget target = target(observation, document, span);
        SemanticRelationship relationship = SemanticRelationship.create(
                source.identity(), new RelationshipKind("java." + observation.category().name().toLowerCase()), target);
        SemanticStatus status = switch (observation.state()) {
            case RESOLVED -> SemanticStatus.RESOLVED;
            case UNRESOLVED -> SemanticStatus.UNRESOLVED;
            case AMBIGUOUS -> SemanticStatus.AMBIGUOUS;
            case UNSUPPORTED -> SemanticStatus.UNSUPPORTED;
            case ERROR -> SemanticStatus.ERROR;
        };
        List<Uncertainty> uncertainty = status == SemanticStatus.RESOLVED ? List.of() : List.of(new Uncertainty(
                "frontend." + observation.state().name().toLowerCase(),
                observation.diagnostic().isBlank() ? "frontend could not establish a single target" : observation.diagnostic(),
                List.of("classpath-or-attribution")));
        List<Diagnostic> diagnostics = status == SemanticStatus.ERROR ? List.of(new Diagnostic(
                DiagnosticSeverity.ERROR, "frontend.error", observation.diagnostic(), Optional.of(span), Map.of())) : List.of();
        return RelationshipOccurrence.create(relationship, span, 0, status,
                new Derivation(DerivationKind.DIRECT, new VersionedIdentifier("benchmark.frontend-adapter", "1"), List.of()),
                uncertainty, diagnostics);
    }

    private RelationshipTarget target(Observation observation, SourceDocumentIdentity document, SourceSpan span) {
        if (observation.state() == ObservationState.AMBIGUOUS) {
            return new RelationshipTarget.Candidates(observation.candidateIdentities().stream()
                    .map(name -> entity(originFor(observation.targetOrigin()), name, document, span).identity()).toList());
        }
        if (observation.state() != ObservationState.RESOLVED) return new RelationshipTarget.Unresolved(observation.targetIdentity());
        return new RelationshipTarget.Resolved(entity(originFor(observation.targetOrigin()), observation.targetIdentity(), document, span).identity());
    }

    private Entity entity(EntityOrigin origin, String name, SourceDocumentIdentity document, SourceSpan span) {
        EntityScope scope = origin == EntityOrigin.PROJECT ? EntityScope.project(module)
                : EntityScope.external(origin, "benchmark:" + origin.name().toLowerCase(), ContentDigest.sha256Utf8(name));
        return Entity.create(origin, scope, EntityKind.TYPE, name,
                origin == EntityOrigin.PROJECT ? Optional.of(span) : Optional.empty());
    }

    private EntityOrigin originFor(TargetOrigin origin) {
        return switch (origin) {
            case PROJECT -> EntityOrigin.PROJECT;
            case JDK -> EntityOrigin.JDK;
            case DEPENDENCY, UNKNOWN -> EntityOrigin.DEPENDENCY;
            case SYNTHETIC -> EntityOrigin.SYNTHETIC;
        };
    }
}
