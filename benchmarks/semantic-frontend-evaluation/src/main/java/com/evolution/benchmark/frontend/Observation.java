package com.evolution.benchmark.frontend;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Parser-neutral raw semantic fact emitted by an experimental frontend. */
public record Observation(
        RelationshipCategory category,
        String sourceIdentity,
        String targetIdentity,
        List<String> candidateIdentities,
        TargetOrigin targetOrigin,
        Path sourceFile,
        Span span,
        ObservationState state,
        String diagnostic) {

    public Observation {
        Objects.requireNonNull(category, "category");
        sourceIdentity = requireText(sourceIdentity, "sourceIdentity");
        targetIdentity = targetIdentity == null ? "" : targetIdentity;
        candidateIdentities = List.copyOf(candidateIdentities == null ? List.of() : candidateIdentities);
        Objects.requireNonNull(targetOrigin, "targetOrigin");
        Objects.requireNonNull(sourceFile, "sourceFile");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(state, "state");
        diagnostic = diagnostic == null ? "" : diagnostic;
        if (state == ObservationState.RESOLVED && targetIdentity.isBlank()) {
            throw new IllegalArgumentException("resolved observation requires target identity");
        }
        if (state == ObservationState.AMBIGUOUS && candidateIdentities.size() < 2) {
            throw new IllegalArgumentException("ambiguous observation requires two candidates");
        }
    }

    public static Observation unresolved(
            RelationshipCategory category, String source, String unresolvedReference,
            Path sourceFile, Span span, String diagnostic) {
        return new Observation(category, source, unresolvedReference, List.of(), TargetOrigin.UNKNOWN,
                sourceFile, span, ObservationState.UNRESOLVED, diagnostic);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value;
    }
}
