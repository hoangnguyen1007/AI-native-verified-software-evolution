package com.evolution.analysis.evidence;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.ExactClasspathResult;
import com.evolution.analysis.dependency.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.Diagnostic;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.input.*;
import com.evolution.analysis.spring.*;
import java.util.*;

/**
 * Pure normalizer: it observes supplied immutable results and never selects or
 * invokes a provider.
 */
public final class CapabilityGapNormalizer {
        public static final int MAX_COALESCED_REPRESENTATIVE_SPANS = 3;
        private static final int MAX_COALESCED_REPRESENTATIVE_DIAGNOSTICS = 3;

        private CapabilityGapNormalizer() {
        }

        private record AttemptKey(EvidenceContext context, EvidenceSubject subject, EvidenceRequirement.Kind kind) {
        }

        private record UnresolvedGroupKey(com.evolution.analysis.contract.identity.SourceDocumentIdentity document,
                        String category) implements Comparable<UnresolvedGroupKey> {
                @Override
                public int compareTo(UnresolvedGroupKey other) {
                        int docCmp = document.value().compareTo(other.document.value());
                        return docCmp != 0 ? docCmp : category.compareTo(other.category);
                }
        }

        /**
         * Returns the closed-denominator observation count represented by a gap record
         * (1 for uncoalesced gaps, {@code occurrenceCount=N} for coalesced document groups).
         */
        public static int occurrenceCount(CapabilityGapRecord gap) {
                for (String limitation : gap.limitations()) {
                        if (limitation.startsWith("occurrenceCount=")) {
                                try {
                                        int parsed = Integer.parseInt(limitation.substring("occurrenceCount=".length()));
                                        if (parsed > 0) {
                                                return parsed;
                                        }
                                } catch (NumberFormatException ignored) {
                                        // Fall through to default count of 1.
                                }
                        }
                }
                return 1;
        }

        public static EvidenceAcquisitionLedger normalize(EvidenceContext context, EvidenceNormalizationInput input) {
                ContractChecks.notNull(context, "normalization context");
                ContractChecks.notNull(input, "normalization input");
                validateContext(context, input);

                ArrayList<AcquisitionAttemptRecord> attempts = new ArrayList<>(input.additionalAttempts());
                input.repositoryAcquisitions().forEach(value -> normalizeAttempts(context, value, attempts));
                input.buildModels().forEach(value -> normalizeAttempts(context, value, attempts));
                input.classpaths().forEach(value -> normalizeAttempts(context, value, attempts));
                input.dependencyAcquisitions().forEach(value -> normalizeAttempts(context, value, attempts));
                input.platformResults().forEach(value -> normalizeAttempts(context, value, attempts));

                Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex = indexAttempts(attempts);

                ArrayList<CapabilityGapRecord> gaps = new ArrayList<>();
                input.repositoryAcquisitions().forEach(value -> normalize(context, value, attemptIndex, gaps));
                input.buildModels().forEach(value -> normalize(context, value, attemptIndex, gaps));
                input.sourceOwnerships().forEach(value -> normalize(context, value, attemptIndex, gaps));
                input.classpaths().forEach(value -> normalize(context, value, attemptIndex, gaps));
                input.dependencyAcquisitions().forEach(value -> normalize(context, value, attemptIndex, gaps));
                input.sourceDecodings().forEach(value -> normalize(context, value, attemptIndex, gaps));
                input.platformResults().forEach(value -> normalize(context, value, attemptIndex, gaps));
                input.frontendAssemblies().forEach(value -> normalize(context, value, attemptIndex, gaps));
                input.frontendResults().forEach(value -> normalize(context, value, attemptIndex, gaps));
                input.springInventories().forEach(value -> normalize(context, value, gaps));

                VersionedIdentifier normalizer = !input.frontendResults().isEmpty()
                                ? EvidenceAcquisitionLedger.OBSERVATION_NORMALIZER
                                : input.springInventories().isEmpty()
                                                ? EvidenceAcquisitionLedger.NORMALIZER
                                                : EvidenceAcquisitionLedger.M4A2_NORMALIZER;
                return EvidenceAcquisitionLedger.create(
                                normalizer, context, gaps, attempts, input.conflicts(), input.resolutions());
        }

        private static Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> indexAttempts(
                        List<AcquisitionAttemptRecord> attempts) {
                if (attempts.isEmpty())
                        return Map.of();
                Map<AttemptKey, TreeSet<AcquisitionAttemptRecord.Identity>> grouped = new HashMap<>(
                                Math.max(16, attempts.size() * 2));
                for (AcquisitionAttemptRecord attempt : attempts) {
                        AttemptKey key = new AttemptKey(attempt.context(), attempt.subject(),
                                        attempt.requestedRequirement().kind());
                        grouped.computeIfAbsent(key, ignored -> new TreeSet<>()).add(attempt.attemptIdentity());
                }
                Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> index = new HashMap<>(
                                Math.max(16, grouped.size() * 2));
                grouped.forEach((key, ids) -> index.put(key, List.copyOf(ids)));
                return index;
        }

        private static void validateContext(EvidenceContext context, EvidenceNormalizationInput input) {
                input.repositoryAcquisitions().stream().flatMap(value -> value.snapshot().stream())
                                .forEach(snapshot -> {
                                        if (!snapshot.identity().equals(context.snapshotIdentity())) {
                                                throw new IllegalArgumentException(
                                                                "Repository acquisition belongs to a different snapshot");
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
                        if (context.analysisIdentity().isEmpty()
                                        || !context.analysisIdentity().orElseThrow().equals(value.analysis())) {
                                throw new IllegalArgumentException(
                                                "Frontend normalization requires its exact pre-existing analysis identity");
                        }
                });
                input.springInventories().forEach(value -> {
                        if (context.analysisIdentity().isEmpty()
                                        || !context.analysisIdentity().orElseThrow().equals(value.analysis())) {
                                throw new IllegalArgumentException(
                                                "Spring inventory normalization requires its exact pre-existing analysis identity");
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
                        ProviderObservationReference observation = reference(result.provider(),
                                        "repository.acquisition-attempt",
                                        resultIdentity, attempt);
                        boolean entryPom = attempt.kind() == RepositoryAcquisitionResult.AttemptKind.FILE
                                        && attempt.subject().equals(result.request().rootPom());
                        EvidenceRequirement requirement = requirement(
                                        entryPom ? EvidenceRequirement.Kind.BUILD_MODEL
                                                        : EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                                        entryPom ? "build.acquire-root-pom" : "repository.acquire-content",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        output.add(attempt(context, result.provider(), observation, subject, requirement,
                                        List.of(result.request().identity()), map(attempt.outcome()),
                                        attempt.evidence()));
                }
        }

        private static void normalizeAttempts(EvidenceContext context, BuildModelResult result,
                        List<AcquisitionAttemptRecord> output) {
                ContentDigest resultIdentity = result.identity();
                for (BuildModelResult.Attempt attempt : result.attempts()) {
                        EvidenceSubject subject = subject(EvidenceSubject.Kind.BUILD_INPUT, attempt.requested());
                        ProviderObservationReference observation = reference(result.provider(), "build.model-attempt",
                                        resultIdentity, attempt);
                        EvidenceRequirement requirement = requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                                        "build.establish-model", EvidenceRequirement.AuthorizationClass.PASSIVE,
                                        subject);
                        output.add(attempt(context, result.provider(), observation, subject, requirement,
                                        List.of(result.requestIdentity()), map(attempt.outcome()),
                                        attempt.evidence().map(BuildModelResult.PomEvidence::digest)));
                }
        }

        private static void normalizeAttempts(EvidenceContext context, ExactClasspathResult result,
                        List<AcquisitionAttemptRecord> output) {
                ContentDigest resultIdentity = result.identity();
                for (ExactClasspathResult.Attempt attempt : result.attempts()) {
                        EvidenceSubject subject = subject(EvidenceSubject.Kind.ARTIFACT, attempt.subject());
                        ProviderObservationReference observation = reference(result.provider(),
                                        "build.artifact-attempt",
                                        resultIdentity, attempt);
                        EvidenceRequirement requirement = requirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                                        "build.acquire-dependency-artifact",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        output.add(attempt(context, result.provider(), observation, subject, requirement,
                                        List.of(result.requestIdentity()), map(attempt.outcome()),
                                        attempt.contentDigest()));
                }
        }

        private static void normalizeAttempts(EvidenceContext context, DependencyAcquisitionResult result,
                        List<AcquisitionAttemptRecord> output) {
                ContentDigest resultIdentity = result.identity();
                for (DependencyAcquisitionResult.Attempt attempt : result.attempts()) {
                        EvidenceSubject subject = subject(EvidenceSubject.Kind.ARTIFACT,
                                        attempt.coordinate().notation());
                        ProviderObservationReference observation = reference(result.provider(),
                                        "build.dependency-acquisition-attempt", resultIdentity, attempt);
                        boolean remote = attempt.origin() == DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY;
                        EvidenceRequirement.Kind kind = attempt.coordinate().extension().equals("pom")
                                        ? EvidenceRequirement.Kind.BUILD_MODEL
                                        : EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT;
                        EvidenceRequirement requirement = requirement(kind,
                                        kind == EvidenceRequirement.Kind.BUILD_MODEL
                                                        ? "build.acquire-artifact-pom"
                                                        : "build.acquire-dependency-artifact",
                                        remote ? EvidenceRequirement.AuthorizationClass.NETWORK
                                                        : EvidenceRequirement.AuthorizationClass.LOCAL_READ,
                                        subject);
                        AcquisitionAttemptRecord.Outcome outcome = map(attempt.outcome());
                        List<AcquisitionAttemptRecord.OutputArtifact> artifacts = attempt.contentDigest().stream()
                                        .map(value -> new AcquisitionAttemptRecord.OutputArtifact(
                                                        attempt.coordinate().repositoryPath(), value))
                                        .toList();
                        Map<String, Long> limits = remote ? Map.of(
                                        "connectTimeoutMillis", (long) result.policy().connectTimeoutMillis(),
                                        "readTimeoutMillis", (long) result.policy().readTimeoutMillis(),
                                        "maxRetries", (long) result.policy().maxRetries(),
                                        "maxArtifactBytes", result.policy().maxArtifactBytes(),
                                        "maxTotalBytes", result.policy().maxTotalBytes())
                                        : Map.of(
                                                        "maxArtifactBytes", result.policy().maxArtifactBytes(),
                                                        "maxTotalBytes", result.policy().maxTotalBytes());
                        output.add(AcquisitionAttemptRecord.create(context, result.provider(), observation, subject,
                                        requirement, List.of(result.requestIdentity()), Optional.empty(),
                                        AcquisitionAttemptRecord.TrustDecision.UNTRUSTED_INPUT,
                                        outcome == AcquisitionAttemptRecord.Outcome.DENIED
                                                        ? AcquisitionAttemptRecord.PermissionDecision.DENIED
                                                        : remote ? AcquisitionAttemptRecord.PermissionDecision.AUTHORIZED
                                                                        : AcquisitionAttemptRecord.PermissionDecision.NOT_RECORDED,
                                        limits, Optional.empty(), Optional.empty(), outcome, artifacts, List.of(),
                                        remote && outcome == AcquisitionAttemptRecord.Outcome.SUCCEEDED
                                                        ? List.of("write-selected-cache")
                                                        : List.of("none")));
                }
        }

        private static void normalizeAttempts(EvidenceContext context, PlatformSymbolResult result,
                        List<AcquisitionAttemptRecord> output) {
                ContentDigest resultIdentity = result.identity();
                for (PlatformSymbolResult.Attempt attempt : result.attempts()) {
                        EvidenceSubject subject = subject(EvidenceSubject.Kind.PLATFORM, attempt.subject());
                        ProviderObservationReference observation = reference(result.provider(),
                                        "build.platform-attempt",
                                        resultIdentity, attempt);
                        EvidenceRequirement requirement = requirement(EvidenceRequirement.Kind.PLATFORM_SYMBOLS,
                                        "build.acquire-platform-symbols",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        output.add(attempt(context, result.provider(), observation, subject, requirement,
                                        List.of(result.requestIdentity()), map(attempt.outcome()), attempt.evidence()));
                }
        }

        private static AcquisitionAttemptRecord attempt(EvidenceContext context, VersionedIdentifier provider,
                        ProviderObservationReference observation, EvidenceSubject subject,
                        EvidenceRequirement requirement,
                        List<ContentDigest> inputs, AcquisitionAttemptRecord.Outcome outcome,
                        Optional<ContentDigest> evidence) {
                List<AcquisitionAttemptRecord.OutputArtifact> artifacts = outcome == AcquisitionAttemptRecord.Outcome.SUCCEEDED
                                || outcome == AcquisitionAttemptRecord.Outcome.PARTIAL
                                                ? evidence.stream()
                                                                .map(value -> new AcquisitionAttemptRecord.OutputArtifact(
                                                                                subject.identity(), value))
                                                                .toList()
                                                : List.of();
                return AcquisitionAttemptRecord.create(context, provider, observation, subject, requirement, inputs,
                                Optional.empty(), AcquisitionAttemptRecord.TrustDecision.UNTRUSTED_INPUT,
                                outcome == AcquisitionAttemptRecord.Outcome.DENIED
                                                ? AcquisitionAttemptRecord.PermissionDecision.DENIED
                                                : AcquisitionAttemptRecord.PermissionDecision.NOT_RECORDED,
                                Map.of(), Optional.empty(), Optional.empty(), outcome, artifacts, List.of(),
                                List.of("none"));
        }

        private static void normalize(EvidenceContext context, RepositoryAcquisitionResult result,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> gaps) {
                ContentDigest resultIdentity = result.identity();
                for (RepositoryAcquisitionResult.Problem problem : result.problems()) {
                        EvidenceSubject subject = subject(EvidenceSubject.Kind.REPOSITORY_PATH, problem.subject());
                        addGap(context, result.provider(), resultIdentity, "repository.problem", problem,
                                        CapabilityGapCatalog.entryFor(problem.reason()),
                                        requirement(problem.requirement(), subject), subject, List.of(), List.of(),
                                        attemptIndex, gaps);
                }
        }

        private static void normalize(EvidenceContext context, BuildModelResult result,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> gaps) {
                ContentDigest resultIdentity = result.identity();
                for (BuildModelResult.Problem problem : result.problems()) {
                        EvidenceSubject subject = subject(EvidenceSubject.Kind.BUILD_INPUT, problem.subject());
                        addGap(context, result.provider(), resultIdentity, "build.problem", problem,
                                        CapabilityGapCatalog.entryFor(problem.reason()),
                                        requirement(problem.requirement(), subject), subject, List.of(), List.of(),
                                        attemptIndex, gaps);
                }
                for (BuildModelResult.ModuleModel module : result.modules()) {
                        module.effectivePom().ifPresent(pom -> pom.sourcePlan().sourceSets()
                                        .forEach(sourceSet -> sourceSet.gaps().forEach(sourceGap -> {
                                                Map<String, Object> payload = Map.of("module", sourceSet.module(),
                                                                "sourceSet", sourceSet.kind(), "gap", sourceGap);
                                                EvidenceSubject subject = subject(EvidenceSubject.Kind.SOURCE_SET,
                                                                sourceSet.module().value() + "#" + sourceSet.kind());
                                                addGap(context, result.provider(), resultIdentity,
                                                                "build.source-plan-gap", payload,
                                                                CapabilityGapCatalog.entryFor(sourceGap), subject,
                                                                List.of(), List.of(), attemptIndex, gaps);
                                        })));
                }
        }

        private static void normalize(EvidenceContext context, CandidateSourceOwnership result,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> gaps) {
                ContentDigest resultIdentity = result.identity();
                for (CandidateSourceOwnership.Problem problem : result.problems()) {
                        EvidenceSubject subject = subject(EvidenceSubject.Kind.REPOSITORY_PATH, problem.subject());
                        addGap(context, result.provider(), resultIdentity, "workspace.ownership-problem", problem,
                                        CapabilityGapCatalog.entryFor(problem.reason()),
                                        requirement(problem.requirement(), subject), subject, List.of(), List.of(),
                                        attemptIndex, gaps);
                }
        }

        private static void normalize(EvidenceContext context, ExactClasspathResult result,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> gaps) {
                ContentDigest resultIdentity = result.identity();
                for (ExactClasspathResult.Manifest manifest : result.manifests()) {
                        for (ExactClasspathResult.Problem problem : manifest.problems()) {
                                EvidenceSubject subject = subject(EvidenceSubject.Kind.ARTIFACT, problem.subject());
                                addGap(context, result.provider(), resultIdentity, "build.classpath-problem", problem,
                                                CapabilityGapCatalog.entryFor(problem.reason()),
                                                requirement(problem.requirement(), subject),
                                                subject, List.of(), List.of(), attemptIndex, gaps);
                        }
                }
        }

        private static void normalize(EvidenceContext context, DependencyAcquisitionResult result,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> gaps) {
                ContentDigest resultIdentity = result.identity();
                for (DependencyAcquisitionResult.Outcome outcome : result.outcomes()) {
                        if (outcome.status() != DependencyAcquisitionResult.Status.FAILED)
                                continue;
                        DependencyAcquisitionResult.FailureReason reason = outcome.failureReason().orElseThrow();
                        EvidenceSubject subject = subject(EvidenceSubject.Kind.ARTIFACT,
                                        outcome.requirement().coordinate().notation());
                        CapabilityGapCatalog.Entry entry = CapabilityGapCatalog.entryFor(reason);
                        EvidenceRequirement.Kind kind = outcome.requirement().coordinate().extension().equals("pom")
                                        ? EvidenceRequirement.Kind.BUILD_MODEL
                                        : EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT;
                        EvidenceRequirement requirement = requirement(kind,
                                        kind == EvidenceRequirement.Kind.BUILD_MODEL
                                                        ? "build.acquire-artifact-pom"
                                                        : "build.acquire-dependency-artifact",
                                        entry.authorizationClass(), subject);
                        addGap(context, result.provider(), resultIdentity, "build.dependency-acquisition-problem",
                                        outcome, entry, requirement, subject, List.of(), List.of(), attemptIndex, gaps);
                }
        }

        private static void normalize(EvidenceContext context, SourceDecodingResult result,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> gaps) {
                ContentDigest resultIdentity = result.identity();
                for (SourceDecodingResult.Outcome outcome : result.outcomes()) {
                        for (SourceDecodingResult.Problem problem : outcome.problems()) {
                                EvidenceSubject subject = subject(EvidenceSubject.Kind.REPOSITORY_PATH, outcome.path());
                                Map<String, Object> payload = Map.of("path", outcome.path(), "rawDigest",
                                                outcome.rawDigest(), "problem", problem);
                                addGap(context, result.provider(), resultIdentity, "build.source-decoding-problem",
                                                payload,
                                                CapabilityGapCatalog.entryFor(problem.reason()),
                                                requirement(problem.requirement(), subject),
                                                subject, List.of(), List.of(), attemptIndex, gaps);
                        }
                }
        }

        private static void normalize(EvidenceContext context, PlatformSymbolResult result,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> gaps) {
                ContentDigest resultIdentity = result.identity();
                for (PlatformSymbolResult.Problem problem : result.problems()) {
                        EvidenceSubject subject = subject(EvidenceSubject.Kind.PLATFORM, problem.subject());
                        addGap(context, result.provider(), resultIdentity, "build.platform-problem", problem,
                                        CapabilityGapCatalog.entryFor(problem.reason()),
                                        requirement(problem.requirement(), subject), subject, List.of(), List.of(),
                                        attemptIndex, gaps);
                }
        }

        private static void normalize(EvidenceContext context, FrontendAssemblyResult result,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> gaps) {
                ContentDigest resultIdentity = result.identity();
                for (FrontendAssemblyResult.Outcome outcome : result.outcomes()) {
                        EvidenceSubject subject = subject(EvidenceSubject.Kind.SOURCE_SET,
                                        outcome.module().value() + "#" + outcome.sourceSet());
                        for (FrontendAssemblyResult.Problem problem : outcome.problems()) {
                                Map<String, Object> payload = Map.of("module", outcome.module(), "sourceSet",
                                                outcome.sourceSet(), "problem", problem);
                                addGap(context, result.provider(), resultIdentity, "frontend.assembly-problem", payload,
                                                CapabilityGapCatalog.entryFor(problem.reason()),
                                                requirement(problem.requirement(), subject),
                                                subject, List.of(), List.of(), attemptIndex, gaps);
                        }
                }
        }

        private static void normalize(EvidenceContext context, FrontendResult result,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> gaps) {
                ContentDigest resultIdentity = CanonicalJson.sha256(result);
                if (result.state() != FrontendResult.State.COMPLETED) {
                        CapabilityGapCatalog.Entry entry = CapabilityGapCatalog.entryFor(result.state());
                        // Recovery's full diagnostic census remains in observations/source outcomes and
                        // the
                        // graph's diagnostic index. The context-state gap cites the run-level cause
                        // rather
                        // than nesting the entire census again in one unbounded aggregate record.
                        var recovery = result.diagnostics().stream()
                                        .filter(d -> d.code().equals("java.source-recovery")).toList();
                        addGap(context, result.frontend(), resultIdentity, "java.frontend-state", result.state(), entry,
                                        subject(EvidenceSubject.Kind.PROVIDER,
                                                        result.frontend().id() + ":" + result.frontend().version()),
                                        List.of(), recovery.isEmpty() ? result.diagnostics() : recovery, attemptIndex,
                                        gaps);
                }
                for (SourceOutcome source : result.sources()) {
                        if (source.state() != SourceOutcome.State.PROCESSED) {
                                var boundedDiagnostics = source.diagnostics().size() <= 32
                                                ? source.diagnostics()
                                                : source.diagnostics().subList(0, 32);
                                var boundedSource = source.diagnostics().size() <= 32
                                                ? source
                                                : new SourceOutcome(source.document(), source.state(),
                                                                boundedDiagnostics);
                                addGap(context, result.frontend(), resultIdentity, "java.source-outcome", boundedSource,
                                                CapabilityGapCatalog.entryFor(source.state()),
                                                subject(EvidenceSubject.Kind.DOCUMENT, source.document().value()),
                                                List.of(),
                                                boundedDiagnostics, attemptIndex, gaps);
                        }
                }
                for (CategoryCoverage coverage : result.coverage()) {
                        if (coverage.support() != CategoryCoverage.Support.IMPLEMENTED) {
                                CapabilityGapCatalog.Entry base = CapabilityGapCatalog.entryFor(coverage.support());
                                CapabilityGapCatalog.Entry entry = new CapabilityGapCatalog.Entry(
                                                coverage.category().value(), base.reasonCode(), base.requirementKind(),
                                                base.question(),
                                                base.authorizationClass(), new AffectedOutput(
                                                                AffectedOutput.Kind.RELATIONSHIP_CATEGORY,
                                                                coverage.category().value()));
                                addGap(context, result.frontend(), resultIdentity, "java.category-coverage", coverage,
                                                entry,
                                                subject(EvidenceSubject.Kind.CATEGORY, coverage.category().value()),
                                                List.of(), List.of(), attemptIndex, gaps);
                        }
                }
                var unresolvedGroups = new TreeMap<UnresolvedGroupKey, List<ObservationRecord>>();
                for (ObservationRecord observation : result.observations()) {
                        boolean unresolved = observation
                                        .attribution() != com.evolution.analysis.contract.semantic.SemanticStatus.RESOLVED;
                        boolean missingOrigin = !unresolved
                                        && observation.origin() == ObservationRecord.EvidenceState.MISSING;
                        boolean missingProvenance = observation.provenance() == ObservationRecord.EvidenceState.MISSING;
                        boolean unmapped = !unresolved && observation.mappedOccurrence().isEmpty();
                        if (!unresolved && !missingOrigin && !missingProvenance && !unmapped) {
                                continue;
                        }
                        if (observation.attribution() == com.evolution.analysis.contract.semantic.SemanticStatus.UNRESOLVED) {
                                unresolvedGroups.computeIfAbsent(
                                                new UnresolvedGroupKey(observation.document(), observation.category().value()),
                                                ignored -> new ArrayList<>()).add(observation);
                                if (missingProvenance) {
                                        ProviderObservationReference reference = reference(result.frontend(), "java.observation",
                                                        resultIdentity, observation);
                                        EvidenceSubject subject = EvidenceSubject.observation(reference.identity());
                                        List<SourceSpan> spans = observation.span().stream().toList();
                                        addGap(context, result.frontend(), reference, CapabilityGapCatalog.missingProvenance(),
                                                        subject, spans, observation.diagnostics(), attemptIndex, gaps);
                                }
                                continue;
                        }
                        ProviderObservationReference reference = reference(result.frontend(), "java.observation",
                                        resultIdentity, observation);
                        EvidenceSubject subject = EvidenceSubject.observation(reference.identity());
                        List<SourceSpan> spans = observation.span().stream().toList();
                        List<AffectedOutput> outputs = List.of(new AffectedOutput(
                                        AffectedOutput.Kind.RELATIONSHIP_CATEGORY, observation.category().value()));
                        if (unresolved) {
                                emitSingleUnresolvedObservationGap(context, result, observation, reference, subject,
                                                spans, outputs, attemptIndex, gaps);
                        }
                        if (missingOrigin) {
                                addGap(context, result.frontend(), reference, CapabilityGapCatalog.missingOrigin(),
                                                subject,
                                                spans, observation.diagnostics(), attemptIndex, gaps);
                        }
                        if (missingProvenance) {
                                addGap(context, result.frontend(), reference, CapabilityGapCatalog.missingProvenance(),
                                                subject,
                                                spans, observation.diagnostics(), attemptIndex, gaps);
                        }
                        if (unmapped) {
                                addGap(context, result.frontend(), reference,
                                                CapabilityGapCatalog.unmappedObservation(), subject,
                                                spans, observation.diagnostics(), attemptIndex, gaps);
                        }
                }
                for (var entry : unresolvedGroups.entrySet()) {
                        List<ObservationRecord> group = entry.getValue();
                        if (group.size() == 1) {
                                ObservationRecord observation = group.getFirst();
                                ProviderObservationReference reference = reference(result.frontend(), "java.observation",
                                                resultIdentity, observation);
                                EvidenceSubject subject = EvidenceSubject.observation(reference.identity());
                                List<SourceSpan> spans = observation.span().stream().toList();
                                List<AffectedOutput> outputs = List.of(new AffectedOutput(
                                                AffectedOutput.Kind.RELATIONSHIP_CATEGORY, observation.category().value()));
                                emitSingleUnresolvedObservationGap(context, result, observation, reference, subject,
                                                spans, outputs, attemptIndex, gaps);
                        } else {
                                emitCoalescedUnresolvedGroupGap(context, result, resultIdentity, entry.getKey(), group,
                                                attemptIndex, gaps);
                        }
                }
        }

        private static void emitSingleUnresolvedObservationGap(
                        EvidenceContext context,
                        FrontendResult result,
                        ObservationRecord observation,
                        ProviderObservationReference reference,
                        EvidenceSubject subject,
                        List<SourceSpan> spans,
                        List<AffectedOutput> outputs,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> gaps) {
                CapabilityGapCatalog.Entry base = CapabilityGapCatalog.entryFor(observation.attribution());
                var entry = new CapabilityGapCatalog.Entry(
                                observation.category().value(), base.reasonCode(),
                                base.requirementKind(), base.question(),
                                base.authorizationClass(), outputs.getFirst());
                // Missing target origin and an unmapped occurrence are consequences of the same
                // failed attribution. Preserve all dimensions and requirements in one root gap.
                var requirements = new ArrayList<EvidenceRequirement>();
                requirements.add(entry.requirement(List.of(subject)));
                var affected = new ArrayList<AffectedOutput>(outputs);
                var dimensions = new ArrayList<String>();
                if (observation.origin() == ObservationRecord.EvidenceState.MISSING) {
                        var origin = CapabilityGapCatalog.missingOrigin();
                        requirements.add(origin.requirement(List.of(subject)));
                        affected.add(origin.affectedOutput());
                        dimensions.add("origin=MISSING");
                }
                if (observation.mappedOccurrence().isEmpty()) {
                        var mapping = CapabilityGapCatalog.unmappedObservation();
                        requirements.add(mapping.requirement(List.of(subject)));
                        affected.add(mapping.affectedOutput());
                        dimensions.add("mapping=MISSING");
                }
                List<AcquisitionAttemptRecord.Identity> related = relatedAttempts(context, subject,
                                requirements, attemptIndex);
                gaps.add(CapabilityGapRecord.create(context, result.frontend(),
                                entry.mechanismCategory(), entry.reasonCode(), subject, spans,
                                List.of(reference), requirements.stream().distinct().toList(),
                                List.of(), affected.stream().distinct().toList(), related,
                                observation.diagnostics(), dimensions));
        }

        private static void emitCoalescedUnresolvedGroupGap(
                        EvidenceContext context,
                        FrontendResult result,
                        ContentDigest resultIdentity,
                        UnresolvedGroupKey key,
                        List<ObservationRecord> group,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> gaps) {
                record Member(ObservationRecord observation, ProviderObservationReference reference) {}
                var members = new ArrayList<Member>(group.size());
                for (ObservationRecord obs : group) {
                        members.add(new Member(obs, reference(result.frontend(), "java.observation", resultIdentity, obs)));
                }
                members.sort((a, b) -> {
                        var spanA = a.observation().span();
                        var spanB = b.observation().span();
                        if (spanA.isPresent() != spanB.isPresent()) {
                                return spanA.isPresent() ? -1 : 1;
                        }
                        if (spanA.isPresent()) {
                                int cmp = spanA.get().compareTo(spanB.get());
                                if (cmp != 0) return cmp;
                        }
                        int refCmp = Objects.toString(a.observation().reference(), "")
                                        .compareTo(Objects.toString(b.observation().reference(), ""));
                        if (refCmp != 0) return refCmp;
                        return a.reference().identity().value().compareTo(b.reference().identity().value());
                });

                var representativeSpans = new LinkedHashSet<SourceSpan>();
                var allMemberDescriptors = new ArrayList<List<Object>>(members.size());
                boolean anyMissingOrigin = false;
                boolean anyUnmapped = false;
                var representativeDiagnostics = new TreeSet<Diagnostic>();
                for (Member member : members) {
                        ObservationRecord obs = member.observation();
                        if (representativeSpans.size() < MAX_COALESCED_REPRESENTATIVE_SPANS) {
                                obs.span().ifPresent(representativeSpans::add);
                        }
                        if (obs.origin() == ObservationRecord.EvidenceState.MISSING) {
                                anyMissingOrigin = true;
                        }
                        if (obs.mappedOccurrence().isEmpty()) {
                                anyUnmapped = true;
                        }
                        for (Diagnostic d : obs.diagnostics()) {
                                if (representativeDiagnostics.size() < MAX_COALESCED_REPRESENTATIVE_DIAGNOSTICS) {
                                        representativeDiagnostics.add(d);
                                }
                        }
                        List<Integer> spanCoords = obs.span()
                                        .map(s -> List.of(s.startLine(), s.startColumn(), s.endLine(), s.endColumn()))
                                        .orElse(List.of());
                        allMemberDescriptors.add(List.of(
                                        spanCoords,
                                        Objects.toString(obs.reference(), ""),
                                        member.reference().payloadDigest().value()));
                }

                ContentDigest allSpansDigest = ContentDigest.sha256Utf8(CanonicalJson.write(allMemberDescriptors));
                ProviderObservationReference coalescedRef = ProviderObservationReference.create(
                                result.frontend(), "java.observation.coalesced", resultIdentity, allSpansDigest);
                EvidenceSubject subject = EvidenceSubject.observation(coalescedRef.identity());

                CapabilityGapCatalog.Entry base = CapabilityGapCatalog
                                .entryFor(com.evolution.analysis.contract.semantic.SemanticStatus.UNRESOLVED);
                AffectedOutput categoryOutput = new AffectedOutput(
                                AffectedOutput.Kind.RELATIONSHIP_CATEGORY, key.category());
                var entry = new CapabilityGapCatalog.Entry(
                                key.category(), base.reasonCode(), base.requirementKind(),
                                base.question(), base.authorizationClass(), categoryOutput);

                var requirements = new ArrayList<EvidenceRequirement>();
                requirements.add(entry.requirement(List.of(subject)));
                var affected = new ArrayList<AffectedOutput>();
                affected.add(categoryOutput);
                var dimensions = new LinkedHashSet<String>();
                if (anyMissingOrigin) {
                        var origin = CapabilityGapCatalog.missingOrigin();
                        requirements.add(origin.requirement(List.of(subject)));
                        affected.add(origin.affectedOutput());
                        dimensions.add("origin=MISSING");
                }
                if (anyUnmapped) {
                        var mapping = CapabilityGapCatalog.unmappedObservation();
                        requirements.add(mapping.requirement(List.of(subject)));
                        affected.add(mapping.affectedOutput());
                        dimensions.add("mapping=MISSING");
                }
                dimensions.add("coalesced=true");
                dimensions.add("occurrenceCount=" + group.size());
                dimensions.add("allSpansDigest=" + allSpansDigest.value());

                var diagnostics = new ArrayList<Diagnostic>(representativeDiagnostics);
                diagnostics.add(new Diagnostic(
                                com.evolution.analysis.contract.semantic.DiagnosticSeverity.WARNING,
                                "evidence.gap.coalesced",
                                "Coalesced " + group.size() + " SEMANTIC_UNRESOLVED observations in document.",
                                Optional.empty(),
                                Map.of(
                                                "coalesced", "true",
                                                "occurrenceCount", String.valueOf(group.size()),
                                                "allSpansDigest", allSpansDigest.value())));

                var relatedSet = new TreeSet<AcquisitionAttemptRecord.Identity>(
                                relatedAttempts(context, subject, requirements, attemptIndex));
                if (!attemptIndex.isEmpty()) {
                        for (Member member : members) {
                                relatedSet.addAll(relatedAttempts(
                                                context,
                                                EvidenceSubject.observation(member.reference().identity()),
                                                requirements,
                                                attemptIndex));
                        }
                }

                gaps.add(CapabilityGapRecord.create(
                                context,
                                result.frontend(),
                                entry.mechanismCategory(),
                                entry.reasonCode(),
                                subject,
                                List.copyOf(representativeSpans),
                                List.of(coalescedRef),
                                requirements.stream().distinct().toList(),
                                List.of(),
                                affected.stream().distinct().toList(),
                                List.copyOf(relatedSet),
                                diagnostics,
                                List.copyOf(dimensions)));
        }

        private static List<AcquisitionAttemptRecord.Identity> relatedAttempts(EvidenceContext context,
                        EvidenceSubject subject, List<EvidenceRequirement> requirements,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex) {
                if (attemptIndex.isEmpty() || requirements.isEmpty())
                        return List.of();
                if (requirements.size() == 1) {
                        return attemptIndex.getOrDefault(
                                        new AttemptKey(context, subject, requirements.getFirst().kind()), List.of());
                }
                TreeSet<AcquisitionAttemptRecord.Identity> matched = null;
                for (EvidenceRequirement requirement : requirements) {
                        List<AcquisitionAttemptRecord.Identity> ids = attemptIndex
                                        .get(new AttemptKey(context, subject, requirement.kind()));
                        if (ids != null && !ids.isEmpty()) {
                                if (matched == null)
                                        matched = new TreeSet<>();
                                matched.addAll(ids);
                        }
                }
                return matched == null ? List.of() : List.copyOf(matched);
        }

        private static void normalize(EvidenceContext context, SpringMechanismInventory inventory,
                        List<CapabilityGapRecord> gaps) {
                for (SpringMechanismInventory.Problem problem : inventory.problems()) {
                        SpringCapabilityGapCatalog.Entry entry = SpringCapabilityGapCatalog.entryFor(problem.reason());
                        ProviderObservationReference observation = reference(inventory.provider(),
                                        "spring.inventory-problem", inventory.identity(), problem);
                        gaps.add(CapabilityGapRecord.create(SpringCapabilityGapCatalog.CATALOG, context,
                                        inventory.provider(), problem.mechanismCategory(), problem.reason().name(),
                                        problem.subject(), problem.sourceSpans(), List.of(observation),
                                        List.of(entry.requirement(problem.subject())), List.of(),
                                        List.of(entry.affectedOutput()), List.of(), problem.diagnostics(),
                                        problem.limitations()));
                }
        }

        private static void addGap(EvidenceContext context, VersionedIdentifier provider, ContentDigest resultIdentity,
                        String kind, Object payload, CapabilityGapCatalog.Entry entry, EvidenceSubject subject,
                        List<SourceSpan> spans, List<Diagnostic> diagnostics,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> output) {
                addGap(context, provider, reference(provider, kind, resultIdentity, payload), entry, subject,
                                spans, diagnostics, attemptIndex, output);
        }

        private static void addGap(EvidenceContext context, VersionedIdentifier provider, ContentDigest resultIdentity,
                        String kind, Object payload, CapabilityGapCatalog.Entry entry, EvidenceRequirement requirement,
                        EvidenceSubject subject, List<SourceSpan> spans, List<Diagnostic> diagnostics,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> output) {
                addGap(context, provider, reference(provider, kind, resultIdentity, payload), entry, requirement,
                                subject, spans, diagnostics, attemptIndex, output);
        }

        private static void addGap(EvidenceContext context, VersionedIdentifier provider,
                        ProviderObservationReference reference, CapabilityGapCatalog.Entry entry,
                        EvidenceSubject subject,
                        List<SourceSpan> spans, List<Diagnostic> diagnostics,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> output) {
                addGap(context, provider, reference, entry, entry.requirement(List.of(subject)), subject,
                                spans, diagnostics, attemptIndex, output);
        }

        private static void addGap(EvidenceContext context, VersionedIdentifier provider,
                        ProviderObservationReference reference, CapabilityGapCatalog.Entry entry,
                        EvidenceRequirement requirement,
                        EvidenceSubject subject, List<SourceSpan> spans, List<Diagnostic> diagnostics,
                        Map<AttemptKey, List<AcquisitionAttemptRecord.Identity>> attemptIndex,
                        List<CapabilityGapRecord> output) {
                List<AcquisitionAttemptRecord.Identity> relatedAttempts = attemptIndex.isEmpty()
                                ? List.of()
                                : attemptIndex.getOrDefault(new AttemptKey(context, subject, requirement.kind()),
                                                List.of());
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

        private static EvidenceRequirement requirement(RepositoryAcquisitionResult.Requirement value,
                        EvidenceSubject subject) {
                return switch (value) {
                        case REPOSITORY_SELECTION -> requirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                                        "repository.select-content", EvidenceRequirement.AuthorizationClass.LOCAL_READ,
                                        subject);
                        case FILESYSTEM_READ -> requirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                                        "repository.acquire-content", EvidenceRequirement.AuthorizationClass.LOCAL_READ,
                                        subject);
                        case ACQUISITION_POLICY -> requirement(EvidenceRequirement.Kind.CONFIGURATION,
                                        "repository.configure-acquisition",
                                        EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
                        case BUILD_MODEL_INPUT -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                                        "build.acquire-root-pom", EvidenceRequirement.AuthorizationClass.LOCAL_READ,
                                        subject);
                };
        }

        private static EvidenceRequirement requirement(BuildModelResult.Requirement value, EvidenceSubject subject) {
                return switch (value) {
                        case BUILD_MODEL -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                                        "build.establish-model", EvidenceRequirement.AuthorizationClass.PASSIVE,
                                        subject);
                        case ANALYSIS_CONFIGURATION -> requirement(EvidenceRequirement.Kind.CONFIGURATION,
                                        "analysis.establish-configuration",
                                        EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
                        case AUTHORIZED_EXTENSION_PROVIDER ->
                                requirement(EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT,
                                                "build.acquire-isolated-output",
                                                EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION, subject);
                };
        }

        private static EvidenceRequirement requirement(CandidateSourceOwnership.Requirement value,
                        EvidenceSubject subject) {
                return switch (value) {
                        case FILESYSTEM_SOURCE_ROOT -> requirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                                        "repository.acquire-source-root",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        case BUILD_CONFIGURATION -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                                        "build.establish-source-roots", EvidenceRequirement.AuthorizationClass.PASSIVE,
                                        subject);
                };
        }

        private static EvidenceRequirement requirement(ExactClasspathResult.Requirement value,
                        EvidenceSubject subject) {
                return switch (value) {
                        case PASSIVE_LOCAL_CACHE -> requirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                                        "build.acquire-dependency-artifact",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        case ARTIFACT_POM -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                                        "build.acquire-artifact-pom", EvidenceRequirement.AuthorizationClass.LOCAL_READ,
                                        subject);
                        case DEPENDENCY_ARTIFACT -> requirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                                        "build.acquire-dependency-artifact",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        case EXACT_VERSION -> requirement(EvidenceRequirement.Kind.EXACT_CLASSPATH,
                                        "build.establish-exact-version", EvidenceRequirement.AuthorizationClass.PASSIVE,
                                        subject);
                        case ANALYSIS_CONFIGURATION -> requirement(EvidenceRequirement.Kind.CONFIGURATION,
                                        "analysis.establish-configuration",
                                        EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
                        case REACTOR_OUTPUT -> requirement(EvidenceRequirement.Kind.REACTOR_OUTPUT,
                                        "build.acquire-reactor-output",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        case COMPLETE_BUILD_MODEL -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                                        "build.establish-model", EvidenceRequirement.AuthorizationClass.PASSIVE,
                                        subject);
                };
        }

        private static EvidenceRequirement requirement(SourceDecodingResult.Requirement value,
                        EvidenceSubject subject) {
                return switch (value) {
                        case SOURCE_OWNERSHIP -> requirement(EvidenceRequirement.Kind.SOURCE_OWNERSHIP,
                                        "workspace.establish-source-ownership",
                                        EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
                        case ACQUIRED_SOURCE_BYTES -> requirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                                        "repository.acquire-source-bytes",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        case BUILD_ENCODING -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                                        "build.establish-source-encoding",
                                        EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
                        case ANALYSIS_ENCODING_POLICY -> requirement(EvidenceRequirement.Kind.CONFIGURATION,
                                        "analysis.establish-source-encoding-policy",
                                        EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
                };
        }

        private static EvidenceRequirement requirement(PlatformSymbolResult.Requirement value,
                        EvidenceSubject subject) {
                return switch (value) {
                        case CONFIGURED_JDK -> requirement(EvidenceRequirement.Kind.PLATFORM_SYMBOLS,
                                        "build.configure-target-jdk", EvidenceRequirement.AuthorizationClass.LOCAL_READ,
                                        subject);
                        case PLATFORM_METADATA -> requirement(EvidenceRequirement.Kind.PLATFORM_SYMBOLS,
                                        "build.acquire-platform-metadata",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        case PLATFORM_SYMBOLS -> requirement(EvidenceRequirement.Kind.PLATFORM_SYMBOLS,
                                        "build.acquire-platform-symbols",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        case ACQUISITION_POLICY -> requirement(EvidenceRequirement.Kind.CONFIGURATION,
                                        "build.configure-platform-acquisition",
                                        EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
                };
        }

        private static EvidenceRequirement requirement(FrontendAssemblyResult.Requirement value,
                        EvidenceSubject subject) {
                return switch (value) {
                        case DECODED_SOURCE -> requirement(EvidenceRequirement.Kind.DECODED_SOURCE,
                                        "build.decode-source", EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
                        case BUILD_SOURCE_PLAN -> requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                                        "build.establish-source-plan", EvidenceRequirement.AuthorizationClass.PASSIVE,
                                        subject);
                        case PLATFORM_SYMBOLS -> requirement(EvidenceRequirement.Kind.PLATFORM_SYMBOLS,
                                        "build.acquire-platform-symbols",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        case EXACT_CLASSPATH -> requirement(EvidenceRequirement.Kind.EXACT_CLASSPATH,
                                        "build.establish-exact-classpath",
                                        EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
                        case DEPENDENCY_ARTIFACT -> requirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                                        "build.acquire-dependency-artifact",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        case REACTOR_OUTPUT -> requirement(EvidenceRequirement.Kind.REACTOR_OUTPUT,
                                        "build.acquire-reactor-output",
                                        EvidenceRequirement.AuthorizationClass.LOCAL_READ, subject);
                        case INPUT_CONFIGURATION -> requirement(EvidenceRequirement.Kind.CONFIGURATION,
                                        "analysis.establish-input-configuration",
                                        EvidenceRequirement.AuthorizationClass.PASSIVE, subject);
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

        private static AcquisitionAttemptRecord.Outcome map(DependencyAcquisitionResult.AttemptOutcome outcome) {
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
