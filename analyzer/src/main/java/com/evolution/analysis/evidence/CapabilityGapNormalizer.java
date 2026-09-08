package com.evolution.analysis.evidence;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.ExactClasspathResult;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.Diagnostic;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.input.*;
import java.util.*;

/** Pure normalizer: it observes supplied immutable results and never selects or invokes a provider. */
public final class CapabilityGapNormalizer {
    private CapabilityGapNormalizer() {}

    public static EvidenceAcquisitionLedger normalize(EvidenceContext context, EvidenceNormalizationInput input) {
        ContractChecks.notNull(context, "normalization context");
        ContractChecks.notNull(input, "normalization input");
        validateContext(context, input);

        ArrayList<AcquisitionAttemptRecord> attempts = new ArrayList<>(input.additionalAttempts());
        input.repositoryAcquisitions().forEach(value -> normalizeAttempts(context, value, attempts));
        input.buildModels().forEach(value -> normalizeAttempts(context, value, attempts));
        input.classpaths().forEach(value -> normalizeAttempts(context, value, attempts));
        input.platformResults().forEach(value -> normalizeAttempts(context, value, attempts));

        ArrayList<CapabilityGapRecord> gaps = new ArrayList<>();
        input.repositoryAcquisitions().forEach(value -> normalize(context, value, attempts, gaps));
        input.buildModels().forEach(value -> normalize(context, value, attempts, gaps));
        input.sourceOwnerships().forEach(value -> normalize(context, value, attempts, gaps));
        input.classpaths().forEach(value -> normalize(context, value, attempts, gaps));
        input.sourceDecodings().forEach(value -> normalize(context, value, attempts, gaps));
        input.platformResults().forEach(value -> normalize(context, value, attempts, gaps));
        input.frontendAssemblies().forEach(value -> normalize(context, value, attempts, gaps));
        input.frontendResults().forEach(value -> normalize(context, value, attempts, gaps));

        return EvidenceAcquisitionLedger.create(context, gaps, attempts, input.conflicts(), input.resolutions());
    }

    private static void validateContext(EvidenceContext context, EvidenceNormalizationInput input) {
        input.repositoryAcquisitions().stream().flatMap(value -> value.snapshot().stream()).forEach(snapshot -> {
            if (!snapshot.identity().equals(context.snapshotIdentity())) {
                throw new IllegalArgumentException("Repository acquisition belongs to a different snapshot");
            }
        });
        input.sourceOwnerships().forEach(value -> {
            if (!value.snapshot().equals(context.snapshotIdentity())) {
                throw new IllegalArgumentException("Source ownership belongs to a different snapshot");
            }
        });
        input.sourceDecodings().forEach(value -> {
            if (!value.snapshot().identity().equals(context.snapshotIdentity())) {
                throw new IllegalArgumentException("Source decoding belongs to a different snapshot");
            }
        });
        input.frontendResults().forEach(value -> {
            if (context.analysisIdentity().isEmpty() || !context.analysisIdentity().orElseThrow().equals(value.analysis())) {
                throw new IllegalArgumentException("Frontend normalization requires its exact pre-existing analysis identity");
            }
        });
        if (input.additionalAttempts().stream().anyMatch(value -> !value.context().equals(context))
                || input.conflicts().stream().anyMatch(value -> !value.context().equals(context))) {
            throw new IllegalArgumentException("Additional evidence records belong to a different context");
        }
    }

    private static void normalizeAttempts(EvidenceContext context, RepositoryAcquisitionResult result,
            List<AcquisitionAttemptRecord> output) {
        ContentDigest resultIdentity = result.identity();
        for (RepositoryAcquisitionResult.Attempt attempt : result.attempts()) {
            EvidenceSubject subject = subject(EvidenceSubject.Kind.REPOSITORY_PATH, attempt.subject());
            ProviderObservationReference observation = reference(result.provider(), "repository.acquisition-attempt",
                    resultIdentity, attempt);
            boolean entryPom = attempt.kind() == RepositoryAcquisitionResult.AttemptKind.FILE
                    && attempt.subject().equals(result.request().rootPom());
            EvidenceRequirement requirement = requirement(
                    entryPom ? EvidenceRequirement.Kind.BUILD_MODEL : EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                    entryPom ? "build.acquire-root-pom" : "repository.acquire-content",
                    EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            output.add(attempt(context, result.provider(), observation, subject, requirement,
                    List.of(result.request().identity()), map(attempt.outcome()), attempt.evidence()));
        }
    }

    private static void normalizeAttempts(EvidenceContext context, BuildModelResult result,
            List<AcquisitionAttemptRecord> output) {
        ContentDigest resultIdentity = result.identity();
        for (BuildModelResult.Attempt attempt : result.attempts()) {
            EvidenceSubject subject = subject(EvidenceSubject.Kind.BUILD_INPUT, attempt.requested());
            ProviderObservationReference observation = reference(result.provider(), "build.model-attempt", resultIdentity, attempt);
            EvidenceRequirement requirement = requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                    "build.establish-model", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
            output.add(attempt(context, result.provider(), observation, subject, requirement,
                    List.of(result.requestIdentity()), map(attempt.outcome()), attempt.evidence().map(BuildModelResult.PomEvidence::digest)));
        }
    }

    private static void normalizeAttempts(EvidenceContext context, ExactClasspathResult result,
            List<AcquisitionAttemptRecord> output) {
        for (ExactClasspathResult.Attempt attempt : result.attempts()) {
            EvidenceSubject subject = subject(EvidenceSubject.Kind.ARTIFACT, attempt.subject());
            ProviderObservationReference observation = reference(result.provider(), "build.artifact-attempt",
                    result.identity(), attempt);
            EvidenceRequirement requirement = requirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                    "build.acquire-dependency-artifact", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            output.add(attempt(context, result.provider(), observation, subject, requirement,
                    List.of(result.requestIdentity()), map(attempt.outcome()), attempt.contentDigest()));
        }
    }

    private static void normalizeAttempts(EvidenceContext context, PlatformSymbolResult result,
            List<AcquisitionAttemptRecord> output) {
        for (PlatformSymbolResult.Attempt attempt : result.attempts()) {
            EvidenceSubject subject = subject(EvidenceSubject.Kind.PLATFORM, attempt.subject());
            ProviderObservationReference observation = reference(result.provider(), "build.platform-attempt",
                    result.identity(), attempt);
            EvidenceRequirement requirement = requirement(EvidenceRequirement.Kind.PLATFORM_SYMBOLS,
                    "build.acquire-platform-symbols", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            output.add(attempt(context, result.provider(), observation, subject, requirement,
                    List.of(result.requestIdentity()), map(attempt.outcome()), attempt.evidence()));
        }
    }

    private static AcquisitionAttemptRecord attempt(EvidenceContext context, VersionedIdentifier provider,
            ProviderObservationReference observation, EvidenceSubject subject, EvidenceRequirement requirement,
            List<ContentDigest> inputs, AcquisitionAttemptRecord.Outcome outcome, Optional<ContentDigest> evidence) {
        List<AcquisitionAttemptRecord.OutputArtifact> artifacts =
                outcome == AcquisitionAttemptRecord.Outcome.SUCCEEDED
                        || outcome == AcquisitionAttemptRecord.Outcome.PARTIAL
                        ? evidence.stream()
                                .map(value -> new AcquisitionAttemptRecord.OutputArtifact(subject.identity(), value))
                                .toList()
                        : List.of();
        return AcquisitionAttemptRecord.create(context, provider, observation, subject, requirement, inputs,
                Optional.empty(), AcquisitionAttemptRecord.TrustDecision.UNTRUSTED_INPUT,
                outcome == AcquisitionAttemptRecord.Outcome.DENIED
                        ? AcquisitionAttemptRecord.PermissionDecision.DENIED
                        : AcquisitionAttemptRecord.PermissionDecision.NOT_RECORDED,
                Map.of(), Optional.empty(), Optional.empty(), outcome, artifacts, List.of(), List.of("none"));
    }

    private static void normalize(EvidenceContext context, RepositoryAcquisitionResult result,
            List<AcquisitionAttemptRecord> attempts, List<CapabilityGapRecord> gaps) {
        for (RepositoryAcquisitionResult.Problem problem : result.problems()) {
            EvidenceSubject subject = subject(EvidenceSubject.Kind.REPOSITORY_PATH, problem.subject());
            addGap(context, result.provider(), result.identity(), "repository.problem", problem,
                    CapabilityGapCatalog.entryFor(problem.reason()),
                    requirement(problem.requirement(), subject), subject, List.of(), List.of(), attempts, gaps);
        }
    }

    private static void normalize(EvidenceContext context, BuildModelResult result,
            List<AcquisitionAttemptRecord> attempts, List<CapabilityGapRecord> gaps) {
        for (BuildModelResult.Problem problem : result.problems()) {
            EvidenceSubject subject = subject(EvidenceSubject.Kind.BUILD_INPUT, problem.subject());
            addGap(context, result.provider(), result.identity(), "build.problem", problem,
                    CapabilityGapCatalog.entryFor(problem.reason()),
                    requirement(problem.requirement(), subject), subject, List.of(), List.of(), attempts, gaps);
        }
        for (BuildModelResult.ModuleModel module : result.modules()) {
            module.effectivePom().ifPresent(pom -> pom.sourcePlan().sourceSets().forEach(sourceSet ->
                    sourceSet.gaps().forEach(sourceGap -> {
                        Map<String, Object> payload = Map.of("module", sourceSet.module(),
                                "sourceSet", sourceSet.kind(), "gap", sourceGap);
                        EvidenceSubject subject = subject(EvidenceSubject.Kind.SOURCE_SET,
                                sourceSet.module().value() + "#" + sourceSet.kind());
                        addGap(context, result.provider(), result.identity(), "build.source-plan-gap", payload,
                                CapabilityGapCatalog.entryFor(sourceGap), subject, List.of(), List.of(), attempts, gaps);
                    })));
        }
    }

    private static void normalize(EvidenceContext context, CandidateSourceOwnership result,
            List<AcquisitionAttemptRecord> attempts, List<CapabilityGapRecord> gaps) {
        for (CandidateSourceOwnership.Problem problem : result.problems()) {
            EvidenceSubject subject = subject(EvidenceSubject.Kind.REPOSITORY_PATH, problem.subject());
            addGap(context, result.provider(), result.identity(), "workspace.ownership-problem", problem,
                    CapabilityGapCatalog.entryFor(problem.reason()),
                    requirement(problem.requirement(), subject), subject, List.of(), List.of(), attempts, gaps);
        }
    }

    private static void normalize(EvidenceContext context, ExactClasspathResult result,
            List<AcquisitionAttemptRecord> attempts, List<CapabilityGapRecord> gaps) {
        for (ExactClasspathResult.Manifest manifest : result.manifests()) {
            for (ExactClasspathResult.Problem problem : manifest.problems()) {
                EvidenceSubject subject = subject(EvidenceSubject.Kind.ARTIFACT, problem.subject());
                addGap(context, result.provider(), result.identity(), "build.classpath-problem", problem,
                        CapabilityGapCatalog.entryFor(problem.reason()), requirement(problem.requirement(), subject),
                        subject, List.of(), List.of(), attempts, gaps);
            }
        }
    }

    private static void normalize(EvidenceContext context, SourceDecodingResult result,
            List<AcquisitionAttemptRecord> attempts, List<CapabilityGapRecord> gaps) {
        for (SourceDecodingResult.Outcome outcome : result.outcomes()) {
            for (SourceDecodingResult.Problem problem : outcome.problems()) {
                EvidenceSubject subject = subject(EvidenceSubject.Kind.REPOSITORY_PATH, outcome.path());
                Map<String, Object> payload = Map.of("path", outcome.path(), "rawDigest", outcome.rawDigest(), "problem", problem);
                addGap(context, result.provider(), result.identity(), "build.source-decoding-problem", payload,
                        CapabilityGapCatalog.entryFor(problem.reason()), requirement(problem.requirement(), subject),
                        subject, List.of(), List.of(), attempts, gaps);
            }
        }
    }

    private static void normalize(EvidenceContext context, PlatformSymbolResult result,
            List<AcquisitionAttemptRecord> attempts, List<CapabilityGapRecord> gaps) {
        for (PlatformSymbolResult.Problem problem : result.problems()) {
            EvidenceSubject subject = subject(EvidenceSubject.Kind.PLATFORM, problem.subject());
            addGap(context, result.provider(), result.identity(), "build.platform-problem", problem,
                    CapabilityGapCatalog.entryFor(problem.reason()),
                    requirement(problem.requirement(), subject), subject, List.of(), List.of(), attempts, gaps);
        }
    }

    private static void normalize(EvidenceContext context, FrontendAssemblyResult result,
            List<AcquisitionAttemptRecord> attempts, List<CapabilityGapRecord> gaps) {
        for (FrontendAssemblyResult.Outcome outcome : result.outcomes()) {
            EvidenceSubject subject = subject(EvidenceSubject.Kind.SOURCE_SET,
                    outcome.module().value() + "#" + outcome.sourceSet());
            for (FrontendAssemblyResult.Problem problem : outcome.problems()) {
                Map<String, Object> payload = Map.of("module", outcome.module(), "sourceSet", outcome.sourceSet(), "problem", problem);
                addGap(context, result.provider(), result.identity(), "frontend.assembly-problem", payload,
                        CapabilityGapCatalog.entryFor(problem.reason()), requirement(problem.requirement(), subject),
                        subject, List.of(), List.of(), attempts, gaps);
            }
        }
    }

    private static void normalize(EvidenceContext context, FrontendResult result,
            List<AcquisitionAttemptRecord> attempts, List<CapabilityGapRecord> gaps) {
        ContentDigest resultIdentity = ContentDigest.sha256Utf8(CanonicalJson.write(result));
        if (result.state() != FrontendResult.State.COMPLETED) {
            CapabilityGapCatalog.Entry entry = CapabilityGapCatalog.entryFor(result.state());
            addGap(context, result.frontend(), resultIdentity, "java.frontend-state", result.state(), entry,
                    subject(EvidenceSubject.Kind.PROVIDER, result.frontend().id() + ":" + result.frontend().version()),
                    List.of(), result.diagnostics(), attempts, gaps);
        }
        for (SourceOutcome source : result.sources()) {
            if (source.state() != SourceOutcome.State.PROCESSED) {
                addGap(context, result.frontend(), resultIdentity, "java.source-outcome", source,
                        CapabilityGapCatalog.entryFor(source.state()),
                        subject(EvidenceSubject.Kind.DOCUMENT, source.document().value()), List.of(),
                        source.diagnostics(), attempts, gaps);
            }
        }
        for (CategoryCoverage coverage : result.coverage()) {
            if (coverage.support() != CategoryCoverage.Support.IMPLEMENTED) {
                CapabilityGapCatalog.Entry base = CapabilityGapCatalog.entryFor(coverage.support());
                CapabilityGapCatalog.Entry entry = new CapabilityGapCatalog.Entry(
                        coverage.category().value(), base.reasonCode(), base.requirementKind(), base.question(),
                        base.authorizationClass(), new AffectedOutput(
                                AffectedOutput.Kind.RELATIONSHIP_CATEGORY, coverage.category().value()));
                addGap(context, result.frontend(), resultIdentity, "java.category-coverage", coverage, entry,
                        subject(EvidenceSubject.Kind.CATEGORY, coverage.category().value()), List.of(), List.of(), attempts, gaps);
            }
        }
        for (ObservationRecord observation : result.observations()) {
            ProviderObservationReference reference = reference(result.frontend(), "java.observation", resultIdentity, observation);
            EvidenceSubject subject = EvidenceSubject.observation(reference.identity());
            List<SourceSpan> spans = observation.span().stream().toList();
            List<AffectedOutput> outputs = List.of(new AffectedOutput(
                    AffectedOutput.Kind.RELATIONSHIP_CATEGORY, observation.category().value()));
            if (observation.attribution() != com.evolution.analysis.contract.semantic.SemanticStatus.RESOLVED) {
                CapabilityGapCatalog.Entry base = CapabilityGapCatalog.entryFor(observation.attribution());
                addGap(context, result.frontend(), reference, new CapabilityGapCatalog.Entry(
                        observation.category().value(), base.reasonCode(), base.requirementKind(), base.question(),
                        base.authorizationClass(), outputs.getFirst()), subject, spans,
                        observation.diagnostics(), attempts, gaps);
            }
            if (observation.origin() == ObservationRecord.EvidenceState.MISSING) {
                addGap(context, result.frontend(), reference, CapabilityGapCatalog.missingOrigin(), subject,
                        spans, observation.diagnostics(), attempts, gaps);
            }
            if (observation.provenance() == ObservationRecord.EvidenceState.MISSING) {
                addGap(context, result.frontend(), reference, CapabilityGapCatalog.missingProvenance(), subject,
                        spans, observation.diagnostics(), attempts, gaps);
            }
            if (observation.mappedOccurrence().isEmpty()) {
                addGap(context, result.frontend(), reference, CapabilityGapCatalog.unmappedObservation(), subject,
                        spans, observation.diagnostics(), attempts, gaps);
            }
        }
    }

    private static void addGap(EvidenceContext context, VersionedIdentifier provider, ContentDigest resultIdentity,
            String kind, Object payload, CapabilityGapCatalog.Entry entry, EvidenceSubject subject,
            List<SourceSpan> spans, List<Diagnostic> diagnostics, List<AcquisitionAttemptRecord> attempts,
            List<CapabilityGapRecord> output) {
        addGap(context, provider, reference(provider, kind, resultIdentity, payload), entry, subject,
                spans, diagnostics, attempts, output);
    }

    private static void addGap(EvidenceContext context, VersionedIdentifier provider, ContentDigest resultIdentity,
            String kind, Object payload, CapabilityGapCatalog.Entry entry, EvidenceRequirement requirement,
            EvidenceSubject subject, List<SourceSpan> spans, List<Diagnostic> diagnostics,
            List<AcquisitionAttemptRecord> attempts, List<CapabilityGapRecord> output) {
        addGap(context, provider, reference(provider, kind, resultIdentity, payload), entry, requirement,
                subject, spans, diagnostics, attempts, output);
    }

    private static void addGap(EvidenceContext context, VersionedIdentifier provider,
            ProviderObservationReference reference, CapabilityGapCatalog.Entry entry, EvidenceSubject subject,
            List<SourceSpan> spans, List<Diagnostic> diagnostics, List<AcquisitionAttemptRecord> attempts,
            List<CapabilityGapRecord> output) {
        addGap(context, provider, reference, entry, entry.requirement(List.of(subject)), subject,
                spans, diagnostics, attempts, output);
    }

    private static void addGap(EvidenceContext context, VersionedIdentifier provider,
            ProviderObservationReference reference, CapabilityGapCatalog.Entry entry, EvidenceRequirement requirement,
            EvidenceSubject subject, List<SourceSpan> spans, List<Diagnostic> diagnostics,
            List<AcquisitionAttemptRecord> attempts, List<CapabilityGapRecord> output) {
        List<AcquisitionAttemptRecord.Identity> relatedAttempts = attempts.stream()
                .filter(value -> value.context().equals(context) && value.subject().equals(subject)
                        && value.requestedRequirement().kind() == requirement.kind())
                .map(AcquisitionAttemptRecord::attemptIdentity).sorted().distinct().toList();
        output.add(CapabilityGapRecord.create(context, provider, entry.mechanismCategory(), entry.reasonCode(),
                subject, spans, List.of(reference), List.of(requirement), List.of(),
                List.of(entry.affectedOutput()), relatedAttempts, diagnostics, List.of()));
    }

    private static ProviderObservationReference reference(VersionedIdentifier provider, String kind,
            ContentDigest resultIdentity, Object payload) {
        return ProviderObservationReference.create(provider, kind, resultIdentity,
                ContentDigest.sha256Utf8(CanonicalJson.write(payload)));
    }

    private static EvidenceSubject subject(EvidenceSubject.Kind kind, String value) {
        return new EvidenceSubject(kind, value);
    }

    private static EvidenceRequirement requirement(EvidenceRequirement.Kind kind, String question,
            EvidenceRequirement.AuthorizationClass authorization, EvidenceSubject subject) {
        return new EvidenceRequirement(kind, question, List.of(subject), authorization,
                List.of("Evidence establishes or narrows the requested fact."));
    }

    private static EvidenceRequirement requirement(RepositoryAcquisitionResult.Requirement value, EvidenceSubject subject) {
        return switch (value) {
            case REPOSITORY_SELECTION -> requirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                    "repository.select-content", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case FILESYSTEM_READ -> requirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                    "repository.acquire-content", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case ACQUISITION_POLICY -> requirement(EvidenceRequirement.Kind.CONFIGURATION,
                    "repository.configure-acquisition", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
            case BUILD_MODEL_INPUT -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                    "build.acquire-root-pom", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
        };
    }

    private static EvidenceRequirement requirement(BuildModelResult.Requirement value, EvidenceSubject subject) {
        return switch (value) {
            case BUILD_MODEL -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                    "build.establish-model", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
            case ANALYSIS_CONFIGURATION -> requirement(EvidenceRequirement.Kind.CONFIGURATION,
                    "analysis.establish-configuration", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
            case AUTHORIZED_EXTENSION_PROVIDER -> requirement(EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT,
                    "build.acquire-isolated-output", EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION, subject);
        };
    }

    private static EvidenceRequirement requirement(CandidateSourceOwnership.Requirement value, EvidenceSubject subject) {
        return switch (value) {
            case FILESYSTEM_SOURCE_ROOT -> requirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                    "repository.acquire-source-root", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case BUILD_CONFIGURATION -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                    "build.establish-source-roots", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
        };
    }

    private static EvidenceRequirement requirement(ExactClasspathResult.Requirement value, EvidenceSubject subject) {
        return switch (value) {
            case PASSIVE_LOCAL_CACHE -> requirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                    "build.acquire-dependency-artifact", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case ARTIFACT_POM -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                    "build.acquire-artifact-pom", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case DEPENDENCY_ARTIFACT -> requirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                    "build.acquire-dependency-artifact", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case EXACT_VERSION -> requirement(EvidenceRequirement.Kind.EXACT_CLASSPATH,
                    "build.establish-exact-version", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
            case ANALYSIS_CONFIGURATION -> requirement(EvidenceRequirement.Kind.CONFIGURATION,
                    "analysis.establish-configuration", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
            case REACTOR_OUTPUT -> requirement(EvidenceRequirement.Kind.REACTOR_OUTPUT,
                    "build.acquire-reactor-output", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case COMPLETE_BUILD_MODEL -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                    "build.establish-model", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
        };
    }

    private static EvidenceRequirement requirement(SourceDecodingResult.Requirement value, EvidenceSubject subject) {
        return switch (value) {
            case SOURCE_OWNERSHIP -> requirement(EvidenceRequirement.Kind.SOURCE_OWNERSHIP,
                    "workspace.establish-source-ownership", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
            case ACQUIRED_SOURCE_BYTES -> requirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                    "repository.acquire-source-bytes", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case BUILD_ENCODING -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                    "build.establish-source-encoding", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
            case ANALYSIS_ENCODING_POLICY -> requirement(EvidenceRequirement.Kind.CONFIGURATION,
                    "analysis.establish-source-encoding-policy", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
        };
    }

    private static EvidenceRequirement requirement(PlatformSymbolResult.Requirement value, EvidenceSubject subject) {
        return switch (value) {
            case CONFIGURED_JDK -> requirement(EvidenceRequirement.Kind.PLATFORM_SYMBOLS,
                    "build.configure-target-jdk", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case PLATFORM_METADATA -> requirement(EvidenceRequirement.Kind.PLATFORM_SYMBOLS,
                    "build.acquire-platform-metadata", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case PLATFORM_SYMBOLS -> requirement(EvidenceRequirement.Kind.PLATFORM_SYMBOLS,
                    "build.acquire-platform-symbols", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case ACQUISITION_POLICY -> requirement(EvidenceRequirement.Kind.CONFIGURATION,
                    "build.configure-platform-acquisition", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
        };
    }

    private static EvidenceRequirement requirement(FrontendAssemblyResult.Requirement value, EvidenceSubject subject) {
        return switch (value) {
            case DECODED_SOURCE -> requirement(EvidenceRequirement.Kind.DECODED_SOURCE,
                    "build.decode-source", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
            case BUILD_SOURCE_PLAN -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                    "build.establish-source-plan", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
            case PLATFORM_SYMBOLS -> requirement(EvidenceRequirement.Kind.PLATFORM_SYMBOLS,
                    "build.acquire-platform-symbols", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case EXACT_CLASSPATH -> requirement(EvidenceRequirement.Kind.EXACT_CLASSPATH,
                    "build.establish-exact-classpath", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
            case DEPENDENCY_ARTIFACT -> requirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                    "build.acquire-dependency-artifact", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case REACTOR_OUTPUT -> requirement(EvidenceRequirement.Kind.REACTOR_OUTPUT,
                    "build.acquire-reactor-output", EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
            case INPUT_CONFIGURATION -> requirement(EvidenceRequirement.Kind.CONFIGURATION,
                    "analysis.establish-input-configuration", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
        };
    }

    private static AcquisitionAttemptRecord.Outcome map(RepositoryAcquisitionResult.Outcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> AcquisitionAttemptRecord.Outcome.SUCCEEDED;
            case FAILED -> AcquisitionAttemptRecord.Outcome.FAILED;
            case DENIED -> AcquisitionAttemptRecord.Outcome.DENIED;
            case UNAVAILABLE -> AcquisitionAttemptRecord.Outcome.UNAVAILABLE;
            case EXCLUDED -> AcquisitionAttemptRecord.Outcome.EXCLUDED;
        };
    }

    private static AcquisitionAttemptRecord.Outcome map(BuildModelResult.Outcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> AcquisitionAttemptRecord.Outcome.SUCCEEDED;
            case FAILED -> AcquisitionAttemptRecord.Outcome.FAILED;
            case DENIED -> AcquisitionAttemptRecord.Outcome.DENIED;
            case UNAVAILABLE -> AcquisitionAttemptRecord.Outcome.UNAVAILABLE;
        };
    }

    private static AcquisitionAttemptRecord.Outcome map(ExactClasspathResult.AttemptOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> AcquisitionAttemptRecord.Outcome.SUCCEEDED;
            case FAILED -> AcquisitionAttemptRecord.Outcome.FAILED;
            case LIMIT_EXCEEDED -> AcquisitionAttemptRecord.Outcome.LIMIT_EXCEEDED;
            case DENIED -> AcquisitionAttemptRecord.Outcome.DENIED;
            case UNAVAILABLE -> AcquisitionAttemptRecord.Outcome.UNAVAILABLE;
        };
    }

    private static AcquisitionAttemptRecord.Outcome map(PlatformSymbolResult.Outcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> AcquisitionAttemptRecord.Outcome.SUCCEEDED;
            case FAILED -> AcquisitionAttemptRecord.Outcome.FAILED;
            case LIMIT_EXCEEDED -> AcquisitionAttemptRecord.Outcome.LIMIT_EXCEEDED;
            case DENIED -> AcquisitionAttemptRecord.Outcome.DENIED;
            case UNAVAILABLE -> AcquisitionAttemptRecord.Outcome.UNAVAILABLE;
        };
    }
}
