package com.evolution.analysis.evidence;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.BuildModelResult;
import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.classpath.ExactClasspathResult;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.input.PlatformSymbolRequest;
import com.evolution.analysis.input.PlatformSymbolResult;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CapabilityGapContractTest {
    private static final RepositoryIdentity REPOSITORY =
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/gaps.git");
    private static final ModuleDescriptor MODULE = ModuleDescriptor.create(REPOSITORY, "module-a", "Module A");
    private static final SourceDocument DOCUMENT = SourceDocument.create(
            REPOSITORY, MODULE, "module-a/src/main/java/example/A.java",
            ContentDigest.sha256Utf8("class A { void run() { missing(); } }"), SourceClassification.MAIN);
    private static final RepositorySnapshot SNAPSHOT = RepositorySnapshot.create(
            REPOSITORY, Optional.of("abc123"), false, List.of(SnapshotFile.from(DOCUMENT)), List.of(DOCUMENT));
    private static final EvidenceContext SNAPSHOT_CONTEXT =
            new EvidenceContext(SNAPSHOT.identity(), Optional.empty());
    private static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("frontend.test", "1");

    @Test
    void gapIdentityUsesOnlyStableContractFields() {
        var span = new SourceSpan(DOCUMENT.identity(), 1, 24, 1, 33);
        var reference = ProviderObservationReference.create(
                PROVIDER, "java.observation", ContentDigest.sha256Utf8("result"),
                ContentDigest.sha256Utf8("observation"));
        var requirement = requirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND,
                "java.resolve-call-target", EvidenceRequirement.AuthorizationClass.PASSIVE);

        CapabilityGapRecord first = CapabilityGapRecord.create(
                SNAPSHOT_CONTEXT, PROVIDER, "java.calls", "SEMANTIC_UNRESOLVED",
                EvidenceSubject.observation(reference.identity()), List.of(span), List.of(reference),
                List.of(requirement), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.RELATIONSHIP_CATEGORY, "java.calls")),
                List.of(), List.of(diagnostic("frontend.unresolved", "first explanation")),
                List.of("First bounded explanation."));
        CapabilityGapRecord changedProvenance = CapabilityGapRecord.create(
                SNAPSHOT_CONTEXT, PROVIDER, "java.calls", "SEMANTIC_UNRESOLVED",
                EvidenceSubject.observation(reference.identity()), List.of(span), List.of(reference),
                List.of(requirement),
                List.of(new CandidateProvider(new VersionedIdentifier("frontend.alternate", "candidate"),
                        new VersionedIdentifier("evidence.candidate-ordering", "1"), 0,
                        "Candidate only; execution is not authorized.")),
                List.of(new AffectedOutput(AffectedOutput.Kind.ASSESSMENT, "assessment.confidence")),
                List.of(), List.of(diagnostic("frontend.unresolved", "different explanation")),
                List.of("Different bounded explanation."));

        assertEquals(first.gapIdentity(), changedProvenance.gapIdentity());
        assertNotEquals(first.gapIdentity(), CapabilityGapRecord.create(
                SNAPSHOT_CONTEXT, PROVIDER, "java.calls", "SEMANTIC_AMBIGUOUS",
                EvidenceSubject.observation(reference.identity()), List.of(span), List.of(reference),
                List.of(requirement), List.of(), first.affectedOutputs(), List.of(), List.of(), List.of())
                .gapIdentity());
        assertNotEquals(first.gapIdentity(), CapabilityGapRecord.create(
                SNAPSHOT_CONTEXT, PROVIDER, "java.calls", "SEMANTIC_UNRESOLVED",
                new EvidenceSubject(EvidenceSubject.Kind.ENTITY, "java:type:other"), List.of(span),
                List.of(reference), List.of(requirement), List.of(), first.affectedOutputs(),
                List.of(), List.of(), List.of()).gapIdentity());
        var providerV2 = new VersionedIdentifier("frontend.test", "2");
        var referenceV2 = ProviderObservationReference.create(providerV2, "java.observation",
                ContentDigest.sha256Utf8("result"), ContentDigest.sha256Utf8("observation"));
        assertNotEquals(first.gapIdentity(), CapabilityGapRecord.create(
                SNAPSHOT_CONTEXT, providerV2, "java.calls",
                "SEMANTIC_UNRESOLVED", EvidenceSubject.observation(referenceV2.identity()),
                List.of(span), List.of(referenceV2), List.of(requirement), List.of(),
                first.affectedOutputs(), List.of(), List.of(), List.of()).gapIdentity());
        assertThrows(IllegalArgumentException.class, () -> CapabilityGapRecord.create(
                SNAPSHOT_CONTEXT, PROVIDER, "java.calls", "SEMANTIC_UNRESOLVED",
                EvidenceSubject.observation(reference.identity()), List.of(span), List.of(reference),
                List.of(requirement), List.of(new CandidateProvider(
                        new VersionedIdentifier("frontend.alternate", "candidate"),
                        new VersionedIdentifier("evidence.candidate-ordering", "1"), 1, "Invalid first rank.")),
                first.affectedOutputs(), List.of(), List.of(), List.of()));
    }

    @Test
    void ledgerDeterministicallyMergesDuplicateGapHistoryWithoutLosingEvidence() {
        var referenceA = ProviderObservationReference.create(PROVIDER, "build.problem",
                ContentDigest.sha256Utf8("result-a"), ContentDigest.sha256Utf8("problem-a"));
        var referenceB = ProviderObservationReference.create(PROVIDER, "build.problem",
                ContentDigest.sha256Utf8("result-b"), ContentDigest.sha256Utf8("problem-b"));
        var subject = new EvidenceSubject(EvidenceSubject.Kind.BUILD_INPUT, "parent:missing");
        var requirement = requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                "build.resolve-parent-model", EvidenceRequirement.AuthorizationClass.LOCAL_READ);
        var output = new AffectedOutput(AffectedOutput.Kind.FRONTEND_INPUT, "frontend.input");
        CapabilityGapRecord first = CapabilityGapRecord.create(SNAPSHOT_CONTEXT, PROVIDER, "build.pom",
                "MISSING_PARENT_POM", subject, List.of(), List.of(referenceA), List.of(requirement),
                List.of(), List.of(output), List.of(), List.of(), List.of("first"));
        CapabilityGapRecord duplicate = CapabilityGapRecord.create(SNAPSHOT_CONTEXT, PROVIDER, "build.pom",
                "MISSING_PARENT_POM", subject, List.of(), List.of(referenceB), List.of(requirement),
                List.of(), List.of(output), List.of(), List.of(), List.of("second"));

        EvidenceAcquisitionLedger ledger = EvidenceAcquisitionLedger.create(
                SNAPSHOT_CONTEXT, List.of(duplicate, first), List.of(), List.of(), List.of());

        assertEquals(1, ledger.gaps().size());
        assertEquals(List.of(referenceA, referenceB), ledger.gaps().getFirst().observationReferences());
        assertEquals(List.of("first", "second"), ledger.gaps().getFirst().limitations());
        assertEquals(ledger.identity(), EvidenceAcquisitionLedger.create(
                SNAPSHOT_CONTEXT, List.of(first, duplicate), List.of(), List.of(), List.of()).identity());
    }

    @Test
    void attemptIdentityExcludesRuntimeTimestampsAndMessagesButBindsOutcomeAndArtifacts() {
        var requirement = requirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                "build.acquire-dependency-artifact", EvidenceRequirement.AuthorizationClass.LOCAL_READ);
        var observation = ProviderObservationReference.create(PROVIDER, "build.artifact-attempt",
                ContentDigest.sha256Utf8("classpath"), ContentDigest.sha256Utf8("attempt"));
        var output = new AcquisitionAttemptRecord.OutputArtifact(
                "artifact:g:a:1", ContentDigest.sha256Utf8("jar"));
        AcquisitionAttemptRecord first = AcquisitionAttemptRecord.create(
                SNAPSHOT_CONTEXT, PROVIDER, observation,
                new EvidenceSubject(EvidenceSubject.Kind.ARTIFACT, "g:a:1"), requirement,
                List.of(ContentDigest.sha256Utf8("request")), Optional.empty(),
                AcquisitionAttemptRecord.TrustDecision.UNTRUSTED_INPUT,
                AcquisitionAttemptRecord.PermissionDecision.AUTHORIZED,
                Map.of("maxBytes", 1024L), Optional.of(Instant.parse("2026-09-08T01:00:00Z")),
                Optional.of(Instant.parse("2026-09-08T01:00:01Z")),
                AcquisitionAttemptRecord.Outcome.SUCCEEDED, List.of(output),
                List.of(diagnostic("acquisition.ok", "first runtime message")), List.of("none"));
        AcquisitionAttemptRecord sameStableAttempt = AcquisitionAttemptRecord.create(
                SNAPSHOT_CONTEXT, PROVIDER, observation,
                new EvidenceSubject(EvidenceSubject.Kind.ARTIFACT, "g:a:1"), requirement,
                List.of(ContentDigest.sha256Utf8("request")), Optional.empty(),
                AcquisitionAttemptRecord.TrustDecision.UNTRUSTED_INPUT,
                AcquisitionAttemptRecord.PermissionDecision.AUTHORIZED,
                Map.of("maxBytes", 1024L), Optional.of(Instant.parse("2026-09-08T02:00:00Z")),
                Optional.of(Instant.parse("2026-09-08T02:00:02Z")),
                AcquisitionAttemptRecord.Outcome.SUCCEEDED, List.of(output),
                List.of(diagnostic("acquisition.ok", "different runtime message")), List.of("none"));

        assertEquals(first.attemptIdentity(), sameStableAttempt.attemptIdentity());
        assertThrows(IllegalArgumentException.class, () -> AcquisitionAttemptRecord.create(
                SNAPSHOT_CONTEXT, PROVIDER, observation,
                new EvidenceSubject(EvidenceSubject.Kind.ARTIFACT, "g:a:1"), requirement,
                List.of(ContentDigest.sha256Utf8("request")), Optional.empty(),
                AcquisitionAttemptRecord.TrustDecision.UNTRUSTED_INPUT,
                AcquisitionAttemptRecord.PermissionDecision.DENIED, Map.of(), Optional.empty(), Optional.empty(),
                AcquisitionAttemptRecord.Outcome.DENIED, List.of(output), List.of(), List.of("none")));
    }

    @Test
    void conflictIdentityIsProviderOrderIndependentAndAdjudicationMustReferenceAnObservation() {
        var left = ProviderObservationReference.create(
                new VersionedIdentifier("frontend.one", "1"), "java.observation",
                ContentDigest.sha256Utf8("one-result"), ContentDigest.sha256Utf8("one"));
        var right = ProviderObservationReference.create(
                new VersionedIdentifier("frontend.two", "1"), "java.observation",
                ContentDigest.sha256Utf8("two-result"), ContentDigest.sha256Utf8("two"));
        var impact = List.of(new AffectedOutput(AffectedOutput.Kind.RELATIONSHIP_CATEGORY, "java.calls"));

        ProviderConflictRecord first = ProviderConflictRecord.create(
                SNAPSHOT_CONTEXT, left, right, List.of("semantic.target"),
                ProviderConflictRecord.AdjudicationStatus.UNRESOLVED, Optional.empty(), impact,
                List.of(), List.of("Providers disagree."));
        ProviderConflictRecord reversed = ProviderConflictRecord.create(
                SNAPSHOT_CONTEXT, right, left, List.of("semantic.target"),
                ProviderConflictRecord.AdjudicationStatus.UNRESOLVED, Optional.empty(), impact,
                List.of(), List.of("Same conflict."));

        assertEquals(first.conflictIdentity(), reversed.conflictIdentity());
        assertThrows(IllegalArgumentException.class, () -> ProviderConflictRecord.create(
                SNAPSHOT_CONTEXT, left, right, List.of("semantic.target"),
                ProviderConflictRecord.AdjudicationStatus.LEFT_ACCEPTED, Optional.empty(), impact,
                List.of(), List.of()));
    }

    @Test
    void aSuccessfulLaterAttemptCanSatisfyWithoutDeletingTheOriginalGap() {
        var observation = ProviderObservationReference.create(PROVIDER, "build.problem",
                ContentDigest.sha256Utf8("build"), ContentDigest.sha256Utf8("missing"));
        var requirement = requirement(EvidenceRequirement.Kind.BUILD_MODEL,
                "build.resolve-parent-model", EvidenceRequirement.AuthorizationClass.LOCAL_READ);
        var gap = CapabilityGapRecord.create(SNAPSHOT_CONTEXT, PROVIDER, "build.pom",
                "MISSING_PARENT_POM", new EvidenceSubject(EvidenceSubject.Kind.BUILD_INPUT, "parent"),
                List.of(), List.of(observation), List.of(requirement), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.FRONTEND_INPUT, "frontend.input")),
                List.of(), List.of(), List.of());
        var attemptProvider = new VersionedIdentifier("build.model-cache", "1");
        var attemptObservation = ProviderObservationReference.create(attemptProvider, "build.model-attempt",
                ContentDigest.sha256Utf8("attempt-result"), ContentDigest.sha256Utf8("attempt-payload"));
        var attempt = AcquisitionAttemptRecord.create(
                SNAPSHOT_CONTEXT, attemptProvider, attemptObservation,
                gap.subject(), requirement, List.of(ContentDigest.sha256Utf8("request")), Optional.empty(),
                AcquisitionAttemptRecord.TrustDecision.UNTRUSTED_INPUT,
                AcquisitionAttemptRecord.PermissionDecision.AUTHORIZED, Map.of(), Optional.empty(), Optional.empty(),
                AcquisitionAttemptRecord.Outcome.SUCCEEDED,
                List.of(new AcquisitionAttemptRecord.OutputArtifact("pom:g:a:1", ContentDigest.sha256Utf8("pom"))),
                List.of(), List.of("none"));
        var evidence = ProviderObservationReference.create(
                new VersionedIdentifier("build.model-cache", "1"), "build.model",
                ContentDigest.sha256Utf8("new-result"), ContentDigest.sha256Utf8("resolved-model"));
        var resolution = GapResolutionRecord.create(gap.gapIdentity(), GapResolutionRecord.State.SATISFIED,
                List.of(evidence), List.of(attempt.attemptIdentity()), List.of("Parent model acquired."));

        EvidenceAcquisitionLedger ledger = EvidenceAcquisitionLedger.create(
                SNAPSHOT_CONTEXT, List.of(gap), List.of(attempt), List.of(), List.of(resolution));

        assertEquals(gap, ledger.gaps().getFirst());
        assertEquals(GapResolutionRecord.State.SATISFIED, ledger.resolutions().getFirst().state());

        var deniedObservation = ProviderObservationReference.create(attemptProvider, "build.model-attempt",
                ContentDigest.sha256Utf8("denied-result"), ContentDigest.sha256Utf8("denied-payload"));
        var denied = AcquisitionAttemptRecord.create(SNAPSHOT_CONTEXT, attemptProvider, deniedObservation,
                gap.subject(), requirement, List.of(ContentDigest.sha256Utf8("request")), Optional.empty(),
                AcquisitionAttemptRecord.TrustDecision.UNTRUSTED_INPUT,
                AcquisitionAttemptRecord.PermissionDecision.DENIED, Map.of(), Optional.empty(), Optional.empty(),
                AcquisitionAttemptRecord.Outcome.DENIED, List.of(), List.of(), List.of("none"));
        var invalidResolution = GapResolutionRecord.create(gap.gapIdentity(), GapResolutionRecord.State.SATISFIED,
                List.of(evidence), List.of(denied.attemptIdentity()), List.of());
        assertThrows(IllegalArgumentException.class, () -> EvidenceAcquisitionLedger.create(
                SNAPSHOT_CONTEXT, List.of(gap), List.of(denied), List.of(), List.of(invalidResolution)));

        var partial = AcquisitionAttemptRecord.create(SNAPSHOT_CONTEXT, attemptProvider, attemptObservation,
                gap.subject(), requirement, List.of(ContentDigest.sha256Utf8("partial-request")), Optional.empty(),
                AcquisitionAttemptRecord.TrustDecision.UNTRUSTED_INPUT,
                AcquisitionAttemptRecord.PermissionDecision.NOT_RECORDED, Map.of(), Optional.empty(), Optional.empty(),
                AcquisitionAttemptRecord.Outcome.PARTIAL,
                List.of(new AcquisitionAttemptRecord.OutputArtifact("pom:g:a:partial", ContentDigest.sha256Utf8("partial"))),
                List.of(), List.of("none"));
        var premature = GapResolutionRecord.create(gap.gapIdentity(), GapResolutionRecord.State.SATISFIED,
                List.of(evidence), List.of(partial.attemptIdentity()), List.of());
        assertThrows(IllegalArgumentException.class, () -> EvidenceAcquisitionLedger.create(
                SNAPSHOT_CONTEXT, List.of(gap), List.of(partial), List.of(), List.of(premature)));
    }

    @Test
    void catalogCoversEveryRegisteredM2AndM3DegradedOutcome() {
        for (BuildModelResult.Reason value : BuildModelResult.Reason.values()) assertNotNull(CapabilityGapCatalog.entryFor(value));
        for (SourcePlanModel.Gap value : SourcePlanModel.Gap.values()) assertNotNull(CapabilityGapCatalog.entryFor(value));
        for (com.evolution.analysis.acquisition.RepositoryAcquisitionResult.Reason value : com.evolution.analysis.acquisition.RepositoryAcquisitionResult.Reason.values()) assertNotNull(CapabilityGapCatalog.entryFor(value));
        for (com.evolution.analysis.acquisition.CandidateSourceOwnership.Reason value : com.evolution.analysis.acquisition.CandidateSourceOwnership.Reason.values()) assertNotNull(CapabilityGapCatalog.entryFor(value));
        for (com.evolution.analysis.classpath.ExactClasspathResult.Reason value : com.evolution.analysis.classpath.ExactClasspathResult.Reason.values()) assertNotNull(CapabilityGapCatalog.entryFor(value));
        for (com.evolution.analysis.input.SourceDecodingResult.Reason value : com.evolution.analysis.input.SourceDecodingResult.Reason.values()) assertNotNull(CapabilityGapCatalog.entryFor(value));
        for (com.evolution.analysis.input.PlatformSymbolResult.Reason value : com.evolution.analysis.input.PlatformSymbolResult.Reason.values()) assertNotNull(CapabilityGapCatalog.entryFor(value));
        for (com.evolution.analysis.input.FrontendAssemblyResult.Reason value : com.evolution.analysis.input.FrontendAssemblyResult.Reason.values()) assertNotNull(CapabilityGapCatalog.entryFor(value));
        for (SemanticStatus value : SemanticStatus.values()) if (value != SemanticStatus.RESOLVED) assertNotNull(CapabilityGapCatalog.entryFor(value));
        for (ObservationRecord.EvidenceState value : ObservationRecord.EvidenceState.values()) if (value == ObservationRecord.EvidenceState.MISSING) assertNotNull(CapabilityGapCatalog.entryFor(value));
        for (SourceOutcome.State value : SourceOutcome.State.values()) if (value != SourceOutcome.State.PROCESSED) assertNotNull(CapabilityGapCatalog.entryFor(value));
        for (CategoryCoverage.Support value : CategoryCoverage.Support.values()) if (value != CategoryCoverage.Support.IMPLEMENTED) assertNotNull(CapabilityGapCatalog.entryFor(value));
        for (FrontendResult.State value : FrontendResult.State.values()) if (value != FrontendResult.State.COMPLETED) assertNotNull(CapabilityGapCatalog.entryFor(value));
    }

    @Test
    void buildNormalizerPreservesProblemAndDeniedAttemptWithoutSuggestingExecution() {
        var evidence = new BuildModelResult.PomEvidence("workspace:pom.xml", ContentDigest.sha256Utf8("pom"));
        var problem = new BuildModelResult.Problem(BuildModelResult.Reason.MISSING_PARENT_POM,
                "parent:g:a:1", BuildModelResult.Requirement.BUILD_MODEL, List.of(evidence));
        var attempt = new BuildModelResult.Attempt("pom.xml", BuildModelResult.AttemptKind.PARENT,
                "parent:g:a:1", BuildModelResult.Outcome.DENIED, Optional.empty());
        var result = new BuildModelResult(BuildModelResult.SCHEMA, ContentDigest.sha256Utf8("request"),
                new VersionedIdentifier("build.test", "1"), List.of(), List.of(problem), List.of(attempt), List.of());

        EvidenceAcquisitionLedger ledger = CapabilityGapNormalizer.normalize(
                SNAPSHOT_CONTEXT, EvidenceNormalizationInput.builder().buildModels(List.of(result)).build());

        assertEquals(1, ledger.gaps().size());
        assertEquals("build.pom", ledger.gaps().getFirst().mechanismCategory());
        assertEquals("MISSING_PARENT_POM", ledger.gaps().getFirst().reasonCode());
        assertTrue(ledger.gaps().getFirst().candidateProviders().isEmpty());
        assertEquals(AcquisitionAttemptRecord.Outcome.DENIED, ledger.attempts().getFirst().outcome());
        assertEquals(List.of(ledger.attempts().getFirst().attemptIdentity()),
                ledger.gaps().getFirst().acquisitionAttemptReferences());
    }

    @Test
    void failedBuildReadKeepsObservedInputEvidenceWithoutMislabelingItAsAnOutputArtifact() {
        var evidence = new BuildModelResult.PomEvidence("workspace:pom.xml", ContentDigest.sha256Utf8("invalid-pom"));
        var attempt = new BuildModelResult.Attempt("pom.xml", BuildModelResult.AttemptKind.MODULE,
                "workspace:pom.xml", BuildModelResult.Outcome.FAILED, Optional.of(evidence));
        var result = new BuildModelResult(BuildModelResult.SCHEMA, ContentDigest.sha256Utf8("request"),
                new VersionedIdentifier("build.test", "1"), List.of(), List.of(), List.of(attempt), List.of());

        EvidenceAcquisitionLedger ledger = CapabilityGapNormalizer.normalize(
                SNAPSHOT_CONTEXT, EvidenceNormalizationInput.builder().buildModels(List.of(result)).build());

        assertEquals(AcquisitionAttemptRecord.Outcome.FAILED, ledger.attempts().getFirst().outcome());
        assertTrue(ledger.attempts().getFirst().outputArtifacts().isEmpty());
    }

    @Test
    void frontendNormalizerSeparatesAttributionOriginAndRunCoverageGapsWithRealSpan() {
        AnalysisManifest manifest = manifest();
        var span = new SourceSpan(DOCUMENT.identity(), 1, 24, 1, 33);
        var unresolved = diagnostic("frontend.unresolved", "Target could not be resolved.");
        var observation = new ObservationRecord(DOCUMENT.identity(), new RelationshipKind("java.calls"),
                Optional.of(span), SemanticStatus.UNRESOLVED, ObservationRecord.EvidenceState.MISSING,
                ObservationRecord.EvidenceState.VERIFIED, Optional.empty(), "missing()", List.of(unresolved));
        List<CategoryCoverage> coverage = FrontendRequest.CATEGORIES.stream().map(category -> {
            boolean calls = category.equals("calls");
            return new CategoryCoverage(new RelationshipKind("java." + category), CategoryCoverage.Support.IMPLEMENTED,
                    calls ? 1 : 0, 0, calls ? 1 : 0);
        }).toList();
        var source = new SourceOutcome(DOCUMENT.identity(), SourceOutcome.State.PARTIAL, List.of(unresolved));
        var result = new FrontendResult(manifest.identity(), PROVIDER, FrontendResult.State.PARTIAL,
                List.of(), List.of(), List.of(observation), List.of(source), coverage, List.of(unresolved));
        EvidenceContext context = EvidenceContext.forAnalysis(manifest);

        EvidenceAcquisitionLedger ledger = CapabilityGapNormalizer.normalize(
                context, EvidenceNormalizationInput.builder().frontendResults(List.of(result)).build());

        Set<String> reasons = ledger.gaps().stream().map(CapabilityGapRecord::reasonCode).collect(java.util.stream.Collectors.toSet());
        assertTrue(reasons.containsAll(Set.of("FRONTEND_PARTIAL", "SOURCE_PARTIAL", "SEMANTIC_UNRESOLVED", "MISSING_ORIGIN")));
        CapabilityGapRecord semantic = ledger.gaps().stream()
                .filter(gap -> gap.reasonCode().equals("SEMANTIC_UNRESOLVED")).findFirst().orElseThrow();
        assertEquals(List.of(span), semantic.sourceSpans());
        assertEquals("java.calls", semantic.mechanismCategory());
        assertThrows(IllegalArgumentException.class, () -> CapabilityGapNormalizer.normalize(
                EvidenceContext.forSnapshot(SNAPSHOT.identity()),
                EvidenceNormalizationInput.builder().frontendResults(List.of(result)).build()));
    }

    @Test
    void normalizerAccountsForRepositoryOwnershipClasspathAndPlatformProvidersTogether() {
        var acquisitionRequest = new RepositoryAcquisitionRequest(REPOSITORY, Optional.of("abc123"), false,
                "pom.xml", new RepositoryAcquisitionPolicy(10, 10, 1_000, 10_000, 10, List.of()));
        var acquisitionProvider = new VersionedIdentifier("repository.test", "1");
        var acquisition = new RepositoryAcquisitionResult(RepositoryAcquisitionResult.SCHEMA,
                acquisitionRequest, acquisitionProvider, RepositoryAcquisitionResult.Completion.PARTIAL,
                Optional.empty(), List.of(), List.of("."),
                List.of(new RepositoryAcquisitionResult.Problem(RepositoryAcquisitionResult.Reason.MISSING_ROOT_POM,
                        "pom.xml", RepositoryAcquisitionResult.Requirement.BUILD_MODEL_INPUT)),
                List.of(new RepositoryAcquisitionResult.Attempt(RepositoryAcquisitionResult.AttemptKind.FILE,
                        "pom.xml", RepositoryAcquisitionResult.Outcome.UNAVAILABLE, Optional.empty())), List.of());

        var pomEvidence = new BuildModelResult.PomEvidence("workspace:pom.xml", ContentDigest.sha256Utf8("pom"));
        var claim = new CandidateSourceOwnership.Claim(MODULE.identity(), SourcePlanModel.Kind.MAIN,
                "module-a/src/main/java", List.of(pomEvidence));
        var ownership = new CandidateSourceOwnership(CandidateSourceOwnership.SCHEMA, SNAPSHOT.identity(),
                ContentDigest.sha256Utf8("build"), CandidateSourceOwnership.PROVIDER, List.of(),
                List.of(new CandidateSourceOwnership.Problem(CandidateSourceOwnership.Reason.MISSING_SOURCE_ROOT,
                        "module-a/src/main/java", CandidateSourceOwnership.Requirement.FILESYSTEM_SOURCE_ROOT,
                        List.of(claim))), List.of());

        var classpathProblem = new ExactClasspathResult.Problem(ExactClasspathResult.Reason.MISSING_ARTIFACT,
                "g:a:1", ExactClasspathResult.Requirement.DEPENDENCY_ARTIFACT, List.of());
        var classpath = ExactClasspathResult.create(ContentDigest.sha256Utf8("classpath-request"),
                new VersionedIdentifier("classpath.test", "1"),
                List.of(ExactClasspathResult.Manifest.create(MODULE.identity(), SourcePlanModel.Kind.MAIN,
                        List.of(), List.of(), List.of(), List.of(classpathProblem))),
                List.of(), List.of(), List.of());

        var platformRequest = new PlatformSymbolRequest(17, 10, 1_000, 10_000);
        var platformProvider = new VersionedIdentifier("platform.test", "1");
        var platform = PlatformSymbolResult.create(platformRequest, platformProvider, Optional.empty(),
                List.of(new PlatformSymbolResult.Problem(PlatformSymbolResult.Reason.JDK_ROOT_NOT_FOUND,
                        "jdk:17", PlatformSymbolResult.Requirement.CONFIGURED_JDK)),
                List.of(new PlatformSymbolResult.Attempt("jdk:17", PlatformSymbolResult.Outcome.UNAVAILABLE,
                        Optional.empty())));

        EvidenceAcquisitionLedger ledger = CapabilityGapNormalizer.normalize(SNAPSHOT_CONTEXT,
                EvidenceNormalizationInput.builder().repositoryAcquisitions(List.of(acquisition))
                        .sourceOwnerships(List.of(ownership)).classpaths(List.of(classpath))
                        .platformResults(List.of(platform)).build());

        assertEquals(Set.of("MISSING_ROOT_POM", "MISSING_SOURCE_ROOT", "MISSING_ARTIFACT", "JDK_ROOT_NOT_FOUND"),
                ledger.gaps().stream().map(CapabilityGapRecord::reasonCode).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, ledger.attempts().size());
        assertTrue(ledger.attempts().stream().allMatch(
                attempt -> attempt.outcome() == AcquisitionAttemptRecord.Outcome.UNAVAILABLE));
        assertTrue(ledger.gaps().stream().allMatch(gap -> !gap.evidenceRequirements().isEmpty()
                && !gap.affectedOutputs().isEmpty() && !gap.observationReferences().isEmpty()));
    }

    @Test
    void sourceAnchoredAndBuildLevelGapIdentitiesAndSerializationHaveGoldenDigests() {
        CapabilityGapRecord build = goldenBuildGap();
        CapabilityGapRecord source = goldenSourceGap();

        assertAll(
                () -> assertEquals("gap:sha256:04e1e284647d9d4cbab1b2050510360880b1f97c95732ecdbb03c67739192434", build.gapIdentity().value()),
                () -> assertEquals("sha256:8dfaca2a5709a28b2bce06a63c229343933b9822377011295df9ab82033bb201", ContentDigest.sha256Utf8(
                        com.evolution.analysis.contract.serialization.CanonicalJson.write(build)).value()),
                () -> assertEquals("gap:sha256:5bd20e7a2236ddc6569f5145203c4b2e326cd1e66fd90c217a1ed8e0b078e7cd", source.gapIdentity().value()),
                () -> assertEquals("sha256:daf670e14df90c5709e9c82c00bb53e8aef74a80ec06ae99b6dd5fe9589e5bd4", ContentDigest.sha256Utf8(
                        com.evolution.analysis.contract.serialization.CanonicalJson.write(source)).value()));
    }

    private static EvidenceRequirement requirement(EvidenceRequirement.Kind kind, String question,
            EvidenceRequirement.AuthorizationClass authorization) {
        return new EvidenceRequirement(kind, question, List.of(), authorization,
                List.of("Evidence establishes or narrows the requested fact."));
    }

    private static Diagnostic diagnostic(String code, String message) {
        return new Diagnostic(DiagnosticSeverity.WARNING, code, message, Optional.empty(), Map.of());
    }

    private static AnalysisManifest manifest() {
        var component = new ManifestComponent(new VersionedIdentifier("test.component", "1"),
                ContentDigest.sha256Utf8("component"));
        var config = AnalysisConfiguration.create(new VersionedIdentifier("analysis.configuration", "1"), Map.of());
        return AnalysisManifest.create(new VersionedIdentifier("analysis.manifest", "1"), SNAPSHOT,
                List.of(MODULE), List.of(), config, component, component, component);
    }

    private static CapabilityGapRecord goldenBuildGap() {
        var provider = new VersionedIdentifier("build.test", "1");
        var reference = ProviderObservationReference.create(provider, "build.problem",
                ContentDigest.sha256Utf8("golden-build-result"), ContentDigest.sha256Utf8("golden-build-problem"));
        return CapabilityGapRecord.create(SNAPSHOT_CONTEXT, provider, "build.pom", "MISSING_PARENT_POM",
                new EvidenceSubject(EvidenceSubject.Kind.BUILD_INPUT, "parent:g:a:1"), List.of(), List.of(reference),
                List.of(requirement(EvidenceRequirement.Kind.BUILD_MODEL, "build.resolve-parent-model",
                        EvidenceRequirement.AuthorizationClass.LOCAL_READ)), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.FRONTEND_INPUT, "frontend.input")),
                List.of(), List.of(), List.of("Parent model is unavailable."));
    }

    private static CapabilityGapRecord goldenSourceGap() {
        var reference = ProviderObservationReference.create(PROVIDER, "java.observation",
                ContentDigest.sha256Utf8("golden-frontend-result"), ContentDigest.sha256Utf8("golden-observation"));
        return CapabilityGapRecord.create(new EvidenceContext(SNAPSHOT.identity(), Optional.of(manifest().identity())),
                PROVIDER, "java.calls", "SEMANTIC_UNRESOLVED", EvidenceSubject.observation(reference.identity()),
                List.of(new SourceSpan(DOCUMENT.identity(), 1, 24, 1, 33)), List.of(reference),
                List.of(requirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND, "java.resolve-call-target",
                        EvidenceRequirement.AuthorizationClass.PASSIVE)), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.RELATIONSHIP_CATEGORY, "java.calls")),
                List.of(), List.of(diagnostic("frontend.unresolved", "Target unavailable.")), List.of());
    }
}
