package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.ConfigurationIdentity;
import com.evolution.analysis.contract.semantic.Diagnostic;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.time.Instant;
import java.util.*;

/** Bounded acquisition provenance. Runtime timing and explanatory messages do not alter stable identity. */
public record AcquisitionAttemptRecord(
        String schemaVersion,
        Identity attemptIdentity,
        EvidenceContext context,
        VersionedIdentifier provider,
        ProviderObservationReference sourceObservation,
        EvidenceSubject subject,
        EvidenceRequirement requestedRequirement,
        List<ContentDigest> inputIdentities,
        Optional<ConfigurationIdentity> configurationIdentity,
        TrustDecision trustDecision,
        PermissionDecision permissionDecision,
        Map<String, Long> resourceLimits,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt,
        Outcome outcome,
        List<OutputArtifact> outputArtifacts,
        List<Diagnostic> diagnostics,
        List<String> declaredSideEffects) implements Comparable<AcquisitionAttemptRecord> {
    public static final String SCHEMA = "acquisition-attempt-record-v1";
    public enum TrustDecision { TRUSTED_INPUT, UNTRUSTED_INPUT, NOT_ASSESSED }
    public enum PermissionDecision { NOT_REQUIRED, AUTHORIZED, DENIED, NOT_REQUESTED, NOT_RECORDED }
    public enum Outcome { SUCCEEDED, PARTIAL, FAILED, DENIED, UNAVAILABLE, CANCELED, EXCLUDED, LIMIT_EXCEEDED }

    public AcquisitionAttemptRecord {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported acquisition-attempt schema");
        ContractChecks.notNull(attemptIdentity, "attempt identity");
        ContractChecks.notNull(context, "attempt context");
        ContractChecks.notNull(provider, "attempt provider");
        ContractChecks.notNull(sourceObservation, "attempt source observation");
        if (!sourceObservation.provider().equals(provider)) {
            throw new IllegalArgumentException("Attempt observation must come from the attempting provider");
        }
        ContractChecks.notNull(subject, "attempt subject");
        ContractChecks.notNull(requestedRequirement, "requested evidence requirement");
        inputIdentities = ContractChecks.sortedDistinct(inputIdentities, Comparator.naturalOrder(), "attempt input identities");
        configurationIdentity = ContractChecks.notNull(configurationIdentity, "attempt configuration identity");
        ContractChecks.notNull(trustDecision, "attempt trust decision");
        ContractChecks.notNull(permissionDecision, "attempt permission decision");
        resourceLimits = limits(resourceLimits);
        startedAt = ContractChecks.notNull(startedAt, "attempt start time");
        completedAt = ContractChecks.notNull(completedAt, "attempt completion time");
        if (startedAt.isPresent() != completedAt.isPresent()) throw new IllegalArgumentException("Attempt timing must be complete or absent");
        if (startedAt.isPresent() && completedAt.orElseThrow().isBefore(startedAt.orElseThrow())) {
            throw new IllegalArgumentException("Attempt completion cannot precede its start");
        }
        ContractChecks.notNull(outcome, "attempt outcome");
        outputArtifacts = ContractChecks.sortedDistinct(
                outputArtifacts, Comparator.comparing(OutputArtifact::logicalId), "attempt output artifacts");
        if (outcome != Outcome.SUCCEEDED && outcome != Outcome.PARTIAL && !outputArtifacts.isEmpty()) {
            throw new IllegalArgumentException("Only successful or partial attempts carry output artifacts");
        }
        if ((outcome == Outcome.DENIED) != (permissionDecision == PermissionDecision.DENIED)) {
            throw new IllegalArgumentException("Denied outcome and permission decision must agree");
        }
        diagnostics = ContractChecks.sortedDistinct(diagnostics, Comparator.naturalOrder(), "attempt diagnostics");
        declaredSideEffects = ContractChecks.sortedStrings(declaredSideEffects, "declared side effects");
        if (declaredSideEffects.isEmpty()) throw new IllegalArgumentException("Attempt must explicitly declare side effects or none");
        Identity expected = derive(context, provider, sourceObservation, subject, requestedRequirement,
                inputIdentities, configurationIdentity, trustDecision, permissionDecision,
                resourceLimits, outcome, outputArtifacts, declaredSideEffects);
        if (!attemptIdentity.equals(expected)) throw new IllegalArgumentException("Attempt identity does not match stable fields");
    }

    public static AcquisitionAttemptRecord create(
            EvidenceContext context, VersionedIdentifier provider, ProviderObservationReference sourceObservation,
            EvidenceSubject subject, EvidenceRequirement requestedRequirement, List<ContentDigest> inputIdentities,
            Optional<ConfigurationIdentity> configurationIdentity, TrustDecision trustDecision,
            PermissionDecision permissionDecision, Map<String, Long> resourceLimits,
            Optional<Instant> startedAt, Optional<Instant> completedAt, Outcome outcome,
            List<OutputArtifact> outputArtifacts, List<Diagnostic> diagnostics, List<String> declaredSideEffects) {
        Identity identity = derive(context, provider, sourceObservation, subject, requestedRequirement,
                inputIdentities.stream().sorted().distinct().toList(), configurationIdentity, trustDecision,
                permissionDecision, limits(resourceLimits), outcome,
                outputArtifacts.stream().sorted(Comparator.comparing(OutputArtifact::logicalId)).distinct().toList(),
                declaredSideEffects.stream().sorted().distinct().toList());
        return new AcquisitionAttemptRecord(SCHEMA, identity, context, provider, sourceObservation, subject,
                requestedRequirement, inputIdentities, configurationIdentity, trustDecision, permissionDecision,
                resourceLimits, startedAt, completedAt, outcome, outputArtifacts, diagnostics, declaredSideEffects);
    }

    private static Identity derive(
            EvidenceContext context, VersionedIdentifier provider, ProviderObservationReference observation,
            EvidenceSubject subject, EvidenceRequirement requirement, List<ContentDigest> inputs,
            Optional<ConfigurationIdentity> configuration, TrustDecision trust, PermissionDecision permission,
            Map<String, Long> limits, Outcome outcome, List<OutputArtifact> outputs, List<String> sideEffects) {
        return new Identity(EvidenceIdentity.derive("attempt", Map.ofEntries(
                Map.entry("schema", SCHEMA), Map.entry("context", context), Map.entry("provider", provider),
                Map.entry("sourceObservation", observation.identity()), Map.entry("subject", subject),
                Map.entry("requirement", requirement), Map.entry("inputs", inputs),
                Map.entry("configuration", configuration), Map.entry("trust", trust),
                Map.entry("permission", permission), Map.entry("resourceLimits", limits),
                Map.entry("outcome", outcome), Map.entry("outputs", outputs), Map.entry("sideEffects", sideEffects))));
    }

    private static Map<String, Long> limits(Map<String, Long> source) {
        ContractChecks.notNull(source, "attempt resource limits");
        TreeMap<String, Long> sorted = new TreeMap<>();
        source.forEach((key, value) -> {
            String checked = ContractChecks.token(key, "resource-limit key");
            if (value == null || value < 0) throw new IllegalArgumentException("Resource limits must not be negative");
            sorted.put(checked, value);
        });
        return Collections.unmodifiableMap(sorted);
    }

    @Override public int compareTo(AcquisitionAttemptRecord other) { return attemptIdentity.compareTo(other.attemptIdentity); }

    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = EvidenceIdentity.require(value, "attempt"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }

    public record OutputArtifact(String logicalId, ContentDigest contentDigest) {
        public OutputArtifact {
            logicalId = ContractChecks.text(logicalId, "output artifact logical ID");
            ContractChecks.notNull(contentDigest, "output artifact digest");
        }
    }
}
