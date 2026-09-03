package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import java.util.*;
import java.util.stream.Collectors;

/** Deterministic neutral output. Explicit occurrences and language-rule evidence remain separate. */
public record FrontendResult(AnalysisIdentity analysis, VersionedIdentifier frontend, State state,
        List<DeclarationRecord> declarations, List<RelationshipOccurrence> occurrences,
        List<ObservationRecord> observations, List<SourceOutcome> sources,
        List<CategoryCoverage> coverage, List<Diagnostic> diagnostics, List<TypeUseRecord> types, List<AnnotationUseRecord> annotations,
        List<DerivedRelationshipRecord> derivedRelationships) {
    public enum State { COMPLETED, PARTIAL, INVALID_INPUT, FAILED, CANCELED }
    public FrontendResult {
        Objects.requireNonNull(analysis); Objects.requireNonNull(frontend); Objects.requireNonNull(state);
        declarations = sorted(declarations, "declarations"); occurrences = sorted(occurrences, "occurrences");
        observations = sorted(observations, "observations"); sources = sorted(sources, "source outcomes");
        coverage = sorted(coverage, "category coverage"); diagnostics = sorted(diagnostics, "diagnostics");
        types = sorted(types, "type uses");
        annotations = sorted(annotations,"annotation uses");
        derivedRelationships = sorted(derivedRelationships,"derived relationships");
        var entities = declarations.stream().map(d -> d.entity().identity()).collect(Collectors.toSet());
        var observed = observations.stream().flatMap(o -> o.mappedOccurrence().stream()).toList();
        if (observed.size() != new HashSet<>(observed).size() || !new HashSet<>(observed).equals(occurrences.stream().map(RelationshipOccurrence::identity).collect(Collectors.toSet()))) throw new IllegalArgumentException("ledger and occurrence mapping differ");
        var byId = occurrences.stream().collect(Collectors.toMap(RelationshipOccurrence::identity, o -> o));
        var documents = sources.stream().map(SourceOutcome::document).collect(Collectors.toSet());
        var entityInputs = entities.stream().map(id -> id.value()).collect(Collectors.toSet());
        for (var derived : derivedRelationships) {
            var relation=derived.relationship();
            if (!FrontendRequest.CATEGORIES.stream().anyMatch(c -> relation.kind().value().equals("java."+c))) throw new IllegalArgumentException("unregistered derived category");
            if (!entities.contains(relation.source()) || !entityInputs.containsAll(derived.derivation().inputIdentities())
                    || derived.supportingSpans().stream().anyMatch(s -> !documents.contains(s.document()))) throw new IllegalArgumentException("derived relationship references missing evidence");
            if (relation.target() instanceof RelationshipTarget.Resolved r && !entities.contains(r.target())
                    || relation.target() instanceof RelationshipTarget.Candidates c && !entities.containsAll(c.candidates())) throw new IllegalArgumentException("derived target missing");
        }
        for (var annotation : annotations) {
            if (!entities.contains(annotation.owner()) || !documents.contains(annotation.span().document())) throw new IllegalArgumentException("annotation use references missing evidence");
        }
        for (var type : types) {
            if (!documents.contains(type.span().document()) || !entities.containsAll(type.type().referencedEntities())
                    || type.owner().stream().anyMatch(id -> !entities.contains(id))) throw new IllegalArgumentException("type detail references missing evidence");
        }
        for (var observation : observations) {
            if (!documents.contains(observation.document())) throw new IllegalArgumentException("observed document missing from coverage");
            if (observation.mappedOccurrence().isEmpty()) continue;
            var occurrence = byId.get(observation.mappedOccurrence().orElseThrow());
            if (!observation.category().equals(occurrence.relationship().kind()) || !observation.span().equals(Optional.of(occurrence.span()))
                    || observation.attribution() != occurrence.status() || !observation.diagnostics().containsAll(occurrence.diagnostics()))
                throw new IllegalArgumentException("mapped ledger disagrees with occurrence");
            if (!(occurrence.relationship().target() instanceof RelationshipTarget.Unresolved) && observation.origin() != ObservationRecord.EvidenceState.VERIFIED)
                throw new IllegalArgumentException("mapped entity target requires verified origin");
        }
        for (var declaration : declarations) {
            if (declaration.entity().declaration().isPresent() && !documents.contains(declaration.entity().declaration().orElseThrow().document()))
                throw new IllegalArgumentException("declaration document missing from coverage");
        }
        for (var occurrence : occurrences) {
            if (occurrence.ordinal() != 0 || !entities.contains(occurrence.relationship().source())) throw new IllegalArgumentException("invalid catalog occurrence");
            var target = occurrence.relationship().target();
            if (target instanceof RelationshipTarget.Resolved r && !entities.contains(r.target())) throw new IllegalArgumentException("target entity missing");
            if (target instanceof RelationshipTarget.Candidates c && !entities.containsAll(c.candidates())) throw new IllegalArgumentException("candidate entities missing");
        }
        if (state == State.COMPLETED && sources.stream().anyMatch(s -> s.state() != SourceOutcome.State.PROCESSED)) throw new IllegalArgumentException("completed run has incomplete sources");
        if (!coverage.stream().map(c -> c.category().value()).collect(Collectors.toSet()).equals(FrontendRequest.CATEGORIES.stream().map(c -> "java." + c).collect(Collectors.toSet())))
            throw new IllegalArgumentException("every registered category requires coverage");
        var registered = FrontendRequest.CATEGORIES.stream().map(c -> "java." + c).collect(Collectors.toSet());
        if (observations.stream().anyMatch(o -> !registered.contains(o.category().value())))
            throw new IllegalArgumentException("unregistered observation category");
        for (var c : coverage) {
            long attempted = observations.stream().filter(o -> o.category().equals(c.category())).count();
            long emitted = occurrences.stream().filter(o -> o.relationship().kind().equals(c.category())).count();
            if (attempted != c.attempted() || emitted != c.emitted()) throw new IllegalArgumentException("coverage differs from output");
        }
    }
    public FrontendResult(AnalysisIdentity analysis, VersionedIdentifier frontend, State state,
            List<DeclarationRecord> declarations, List<RelationshipOccurrence> occurrences,
            List<ObservationRecord> observations, List<SourceOutcome> sources,
            List<CategoryCoverage> coverage, List<Diagnostic> diagnostics, List<TypeUseRecord> types, List<AnnotationUseRecord> annotations) {
        this(analysis,frontend,state,declarations,occurrences,observations,sources,coverage,diagnostics,types,annotations,List.of());
    }
    public FrontendResult(AnalysisIdentity analysis, VersionedIdentifier frontend, State state,
            List<DeclarationRecord> declarations, List<RelationshipOccurrence> occurrences,
            List<ObservationRecord> observations, List<SourceOutcome> sources,
            List<CategoryCoverage> coverage, List<Diagnostic> diagnostics, List<TypeUseRecord> types) {
        this(analysis,frontend,state,declarations,occurrences,observations,sources,coverage,diagnostics,types,List.of());
    }
    public FrontendResult(AnalysisIdentity analysis, VersionedIdentifier frontend, State state,
            List<DeclarationRecord> declarations, List<RelationshipOccurrence> occurrences,
            List<ObservationRecord> observations, List<SourceOutcome> sources,
            List<CategoryCoverage> coverage, List<Diagnostic> diagnostics) {
        this(analysis, frontend, state, declarations, occurrences, observations, sources, coverage, diagnostics, List.of());
    }
    /** Bind output coverage back to the complete requested input set, including documents with no facts. */
    public FrontendResult validateFor(FrontendRequest request) {
        if (!analysis.equals(request.manifest().identity()) || !sources.stream().map(SourceOutcome::document).collect(Collectors.toSet())
                .equals(request.sources().stream().map(s -> s.document().identity()).collect(Collectors.toSet())))
            throw new IllegalArgumentException("result does not cover the requested analysis");
        return this;
    }
    private static <T extends Comparable<? super T>> List<T> sorted(List<T> values, String name) { return ContractChecks.sortedDistinct(values, Comparator.naturalOrder(), name); }
}
