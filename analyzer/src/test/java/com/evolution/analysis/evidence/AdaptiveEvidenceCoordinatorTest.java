package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.ingestion.IngestionFixtures;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveEvidenceCoordinatorTest {
    @Test void foreignProviderGapCannotPromoteCapturedBytesOrEnterLedger() {
        var context = context();
        var expected = ContentDigest.sha256(JAR);
        var requirement = requirement(expected);
        var foreign = EvidenceContext.forSnapshot(IngestionFixtures.inputs(
                Map.of("Other.java", "class Other {}")).snapshot().identity());
        AdaptiveEvidenceCoordinator.Provider provider = new AdaptiveEvidenceCoordinator.Provider() {
            public VersionedIdentifier id() { return IMPORTER; }
            public Set<EvidenceRequirement.Kind> kinds() { return Set.of(requirement.kind()); }
            public EvidenceRequirement.AuthorizationClass authorizationClass() {
                return EvidenceRequirement.AuthorizationClass.LOCAL_READ;
            }
            public int costClass() { return 0; }
            public AdaptiveEvidenceCoordinator.Acquisition acquire(EvidenceRequirement ignored) {
                return new AdaptiveEvidenceCoordinator.Acquisition(AcquisitionAttemptRecord.Outcome.SUCCEEDED,
                        Optional.of(artifact(context, REVISION, JAR, expected)),
                        List.of(gap(foreign, requirement, "foreign")));
            }
            public boolean satisfies(EvidenceRequirement ignored,
                    AdaptiveEvidenceCoordinator.CapturedArtifact artifact) { return true; }
        };
        var result = new AdaptiveEvidenceCoordinator().run(request(context, requirement, true), List.of(provider));
        assertEquals(AdaptiveEvidenceCoordinator.Termination.OUTSTANDING, result.termination());
        assertTrue(result.acquired().isEmpty());
        assertEquals(1, result.ledger().gaps().size());
        assertEquals(AcquisitionAttemptRecord.Outcome.FAILED, result.ledger().attempts().getFirst().outcome());
    }
    private static final ContentDigest REVISION = ContentDigest.sha256Utf8("captured-input-revision");
    private static final byte[] JAR = "fixture-jar".getBytes(StandardCharsets.UTF_8);
    private static final ContentDigest JAR_DIGEST = ContentDigest.sha256(JAR);
    private static final VersionedIdentifier DETECTOR = new VersionedIdentifier("test.detector", "1");
    private static final VersionedIdentifier IMPORTER = new VersionedIdentifier("test.bundle-import", "1");

    @Test void exactCapturedBytesCloseSharedObligationsOnceAndReplayDeterministically() {
        var context = context(); var requirement = requirement(JAR_DIGEST);
        var gaps = List.of(gap(context, requirement, "module-a"), gap(context, requirement, "module-b"));
        var request = new AdaptiveEvidenceCoordinator.Request(context, REVISION, gaps, policy(true));
        var bundle = new AdaptiveEvidenceCoordinator.CapturedBundleProvider(IMPORTER,
                Map.of(requirement, artifact(context, REVISION, JAR, JAR_DIGEST)));
        var coordinator = new AdaptiveEvidenceCoordinator();
        var result = coordinator.run(request, List.of(bundle));
        assertEquals(AdaptiveEvidenceCoordinator.Termination.COMPLETE, result.termination());
        assertTrue(result.open().isEmpty());
        assertEquals(1, result.ledger().attempts().size());
        assertEquals(2, result.ledger().resolutions().size());
        assertTrue(result.ledger().resolutions().stream().allMatch(r -> r.state() == GapResolutionRecord.State.SATISFIED));
        assertEquals(result.identity(), coordinator.run(request, List.of(bundle)).identity());
    }

    @Test void wrongBytesOrStaleContextCannotSatisfyExactRequirement() {
        var context = context(); var requirement = requirement(JAR_DIGEST);
        var request = request(context, requirement, true);
        for (var bad : List.of(
                artifact(context, REVISION, "wrong".getBytes(StandardCharsets.UTF_8), JAR_DIGEST),
                artifact(context, REVISION, "wrong".getBytes(StandardCharsets.UTF_8),
                        ContentDigest.sha256Utf8("wrong")),
                artifact(context, ContentDigest.sha256Utf8("older-revision"), JAR, JAR_DIGEST))) {
            var result = new AdaptiveEvidenceCoordinator().run(request,
                    List.of(new AdaptiveEvidenceCoordinator.CapturedBundleProvider(IMPORTER, Map.of(requirement, bad))));
            assertFalse(result.open().isEmpty());
            assertTrue(result.ledger().resolutions().isEmpty());
            assertEquals(AcquisitionAttemptRecord.Outcome.FAILED, result.ledger().attempts().getFirst().outcome());
            assertNotNull(result.identity());
        }
    }

    @Test void deniedProviderIsNeverCalled() {
        var context = context(); var requirement = requirement(JAR_DIGEST);
        var calls = new AtomicInteger();
        AdaptiveEvidenceCoordinator.Provider provider = new AdaptiveEvidenceCoordinator.Provider() {
            public VersionedIdentifier id() { return IMPORTER; }
            public Set<EvidenceRequirement.Kind> kinds() { return Set.of(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT); }
            public EvidenceRequirement.AuthorizationClass authorizationClass() { return EvidenceRequirement.AuthorizationClass.LOCAL_READ; }
            public int costClass() { return 0; }
            public AdaptiveEvidenceCoordinator.Acquisition acquire(EvidenceRequirement ignored) {
                calls.incrementAndGet();
                return new AdaptiveEvidenceCoordinator.Acquisition(AcquisitionAttemptRecord.Outcome.SUCCEEDED,
                        Optional.of(artifact(context, REVISION, JAR, JAR_DIGEST)));
            }
        };
        var result = new AdaptiveEvidenceCoordinator().run(request(context, requirement, false), List.of(provider));
        assertEquals(0, calls.get());
        assertEquals(AdaptiveEvidenceCoordinator.OpenReason.DENIED, result.open().values().iterator().next());
        assertEquals(AcquisitionAttemptRecord.Outcome.DENIED, result.ledger().attempts().getFirst().outcome());
    }

    @Test void exactBytesWithoutProviderSatisfactionProofRemainOpen() {
        var context = context(); var requirement = requirement(JAR_DIGEST);
        AdaptiveEvidenceCoordinator.Provider provider = new AdaptiveEvidenceCoordinator.Provider() {
            public VersionedIdentifier id() { return IMPORTER; }
            public Set<EvidenceRequirement.Kind> kinds() { return Set.of(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT); }
            public EvidenceRequirement.AuthorizationClass authorizationClass() { return EvidenceRequirement.AuthorizationClass.LOCAL_READ; }
            public int costClass() { return 0; }
            public AdaptiveEvidenceCoordinator.Acquisition acquire(EvidenceRequirement ignored) {
                return new AdaptiveEvidenceCoordinator.Acquisition(AcquisitionAttemptRecord.Outcome.SUCCEEDED,
                        Optional.of(artifact(context, REVISION, JAR, JAR_DIGEST)));
            }
        };
        var result = new AdaptiveEvidenceCoordinator().run(request(context, requirement, true), List.of(provider));
        assertEquals(AdaptiveEvidenceCoordinator.OpenReason.NARROWED,
                result.open().values().iterator().next());
        assertEquals(GapResolutionRecord.State.NARROWED, result.ledger().resolutions().getFirst().state());
    }

    @Test void validUnpinnedBytesOnlyNarrowTheQuestion() {
        var context = context();
        var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                "dependency.unpinned-artifact", List.of(new EvidenceSubject(EvidenceSubject.Kind.MODULE, "module-a")),
                EvidenceRequirement.AuthorizationClass.LOCAL_READ, List.of("Establish selected artifact and its bytes."));
        var result = new AdaptiveEvidenceCoordinator().run(request(context, requirement, true),
                List.of(new AdaptiveEvidenceCoordinator.CapturedBundleProvider(IMPORTER,
                        Map.of(requirement, artifact(context, REVISION, JAR, JAR_DIGEST)))));
        assertEquals(AdaptiveEvidenceCoordinator.OpenReason.NARROWED, result.open().values().iterator().next());
        assertEquals(GapResolutionRecord.State.NARROWED, result.ledger().resolutions().getFirst().state());
        assertNotNull(result.identity());
    }

    @Test void oneArtifactCannotCloseGapThatRequiresTwoArtifacts() {
        var context = context();
        var first = requirement(JAR_DIGEST);
        var secondBytes = "second-jar".getBytes(StandardCharsets.UTF_8);
        var second = requirement(ContentDigest.sha256(secondBytes));
        var gap = CapabilityGapRecord.create(context, DETECTOR, "build.dependency", "EXACT_ARTIFACT_MISSING",
                new EvidenceSubject(EvidenceSubject.Kind.MODULE, "module-a"), List.of(),
                List.of(ProviderObservationReference.create(DETECTOR, "test.missing-artifact", REVISION,
                        ContentDigest.sha256Utf8("module-a"))), List.of(first, second), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.FRONTEND_INPUT, "java.exact-input")),
                List.of(), List.of(), List.of());
        var request = new AdaptiveEvidenceCoordinator.Request(context, REVISION, List.of(gap), policy(true));
        var one = new AdaptiveEvidenceCoordinator.CapturedBundleProvider(IMPORTER,
                Map.of(first, artifact(context, REVISION, JAR, JAR_DIGEST)));
        var partial = new AdaptiveEvidenceCoordinator().run(request, List.of(one));
        assertEquals(AdaptiveEvidenceCoordinator.Termination.OUTSTANDING, partial.termination());
        assertTrue(partial.open().containsKey(gap.gapIdentity()));
        assertTrue(partial.ledger().resolutions().stream().noneMatch(
                r -> r.state() == GapResolutionRecord.State.SATISFIED));
        var both = new AdaptiveEvidenceCoordinator.CapturedBundleProvider(IMPORTER,
                Map.of(first, artifact(context, REVISION, JAR, JAR_DIGEST),
                        second, artifact(context, REVISION, secondBytes, ContentDigest.sha256(secondBytes))));
        var complete = new AdaptiveEvidenceCoordinator().run(request, List.of(both));
        assertEquals(AdaptiveEvidenceCoordinator.Termination.COMPLETE, complete.termination());
        assertEquals(2, complete.ledger().resolutions().stream()
                .filter(r -> r.state() == GapResolutionRecord.State.SATISFIED)
                .findFirst().orElseThrow().attemptReferences().size());
    }

    @Test void providerDeclaredTransientFailureRetriesWithinBudget() {
        var context = context(); var requirement = requirement(JAR_DIGEST);
        var calls = new AtomicInteger();
        AdaptiveEvidenceCoordinator.Provider provider = new AdaptiveEvidenceCoordinator.Provider() {
            public VersionedIdentifier id() { return IMPORTER; }
            public Set<EvidenceRequirement.Kind> kinds() { return Set.of(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT); }
            public EvidenceRequirement.AuthorizationClass authorizationClass() { return EvidenceRequirement.AuthorizationClass.LOCAL_READ; }
            public int costClass() { return 0; }
            public AdaptiveEvidenceCoordinator.Acquisition acquire(EvidenceRequirement ignored) {
                return calls.incrementAndGet() == 1 ? AdaptiveEvidenceCoordinator.Acquisition.unavailable()
                        : new AdaptiveEvidenceCoordinator.Acquisition(AcquisitionAttemptRecord.Outcome.SUCCEEDED,
                                Optional.of(artifact(context, REVISION, JAR, JAR_DIGEST)));
            }
            public boolean retryable(AcquisitionAttemptRecord.Outcome outcome) {
                return outcome == AcquisitionAttemptRecord.Outcome.UNAVAILABLE;
            }
            public boolean satisfies(EvidenceRequirement ignored,
                    AdaptiveEvidenceCoordinator.CapturedArtifact value) { return true; }
        };
        var policy = new AdaptiveEvidenceCoordinator.Policy(
                Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ), 3, 1000, 1);
        var request = new AdaptiveEvidenceCoordinator.Request(context, REVISION,
                List.of(gap(context, requirement, "module-a")), policy);
        var result = new AdaptiveEvidenceCoordinator().run(request, List.of(provider));
        assertEquals(AdaptiveEvidenceCoordinator.Termination.COMPLETE, result.termination());
        assertEquals(2, calls.get());
        assertEquals(2, result.ledger().attempts().size());
        calls.set(0);
        var bounded = new AdaptiveEvidenceCoordinator().run(new AdaptiveEvidenceCoordinator.Request(
                context, REVISION, request.gaps(), new AdaptiveEvidenceCoordinator.Policy(
                        Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ), 1, 1000, 1)), List.of(provider));
        assertEquals(AdaptiveEvidenceCoordinator.Termination.ATTEMPT_LIMIT, bounded.termination());
        assertEquals(1, calls.get());
    }

    @Test void cancellationRetainsCompletedEvidenceAndUnattemptedObligations() {
        var context = context();
        var first = requirement(JAR_DIGEST);
        var secondBytes = "second-jar".getBytes(StandardCharsets.UTF_8);
        var second = requirement(ContentDigest.sha256(secondBytes));
        var request = new AdaptiveEvidenceCoordinator.Request(context, REVISION,
                List.of(gap(context, first, "module-a"), gap(context, second, "module-b")), policy(true));
        var calls = new AtomicInteger();
        AdaptiveEvidenceCoordinator.Provider provider = new AdaptiveEvidenceCoordinator.Provider() {
            public VersionedIdentifier id() { return IMPORTER; }
            public Set<EvidenceRequirement.Kind> kinds() { return Set.of(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT); }
            public EvidenceRequirement.AuthorizationClass authorizationClass() {
                return EvidenceRequirement.AuthorizationClass.LOCAL_READ;
            }
            public int costClass() { return 0; }
            public AdaptiveEvidenceCoordinator.Acquisition acquire(EvidenceRequirement requirement) {
                calls.incrementAndGet();
                var bytes = requirement.equals(first) ? JAR : secondBytes;
                return new AdaptiveEvidenceCoordinator.Acquisition(AcquisitionAttemptRecord.Outcome.SUCCEEDED,
                        Optional.of(artifact(context, REVISION, bytes, ContentDigest.sha256(bytes))));
            }
            public boolean satisfies(EvidenceRequirement ignored,
                    AdaptiveEvidenceCoordinator.CapturedArtifact artifact) { return true; }
        };
        var result = new AdaptiveEvidenceCoordinator().run(request, List.of(provider), () -> calls.get() >= 1);
        assertEquals(AdaptiveEvidenceCoordinator.Termination.CANCELLED, result.termination());
        assertEquals(1, calls.get());
        assertEquals(1, result.ledger().attempts().size());
        assertEquals(1, result.ledger().resolutions().stream()
                .filter(r -> r.state() == GapResolutionRecord.State.SATISFIED).count());
        assertEquals(1, result.open().size());
        assertEquals(AdaptiveEvidenceCoordinator.OpenReason.CANCELLED,
                result.open().values().iterator().next());
    }

    @Test void conflictingUnpinnedBundlesWithholdAcquiredResult() {
        var context = context();
        var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                "dependency.unpinned-artifact", List.of(new EvidenceSubject(EvidenceSubject.Kind.MODULE, "module-a")),
                EvidenceRequirement.AuthorizationClass.LOCAL_READ, List.of("Establish selected artifact and its bytes."));
        var other = "different".getBytes(StandardCharsets.UTF_8);
        var result = new AdaptiveEvidenceCoordinator().run(request(context, requirement, true), List.of(
                new AdaptiveEvidenceCoordinator.CapturedBundleProvider(IMPORTER,
                        Map.of(requirement, artifact(context, REVISION, JAR, JAR_DIGEST))),
                new AdaptiveEvidenceCoordinator.CapturedBundleProvider(
                        new VersionedIdentifier("test.second-import", "1"),
                        Map.of(requirement, artifact(context, REVISION, other, ContentDigest.sha256(other))))));
        assertEquals(AdaptiveEvidenceCoordinator.OpenReason.CONFLICT, result.open().values().iterator().next());
        assertEquals(1, result.ledger().conflicts().size());
        assertTrue(result.acquired().isEmpty());
    }

    private static AdaptiveEvidenceCoordinator.Policy policy(boolean permitRead) {
        return new AdaptiveEvidenceCoordinator.Policy(permitRead
                ? Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ)
                : Set.of(EvidenceRequirement.AuthorizationClass.PASSIVE), 8, 1000);
    }
    private static AdaptiveEvidenceCoordinator.Request request(EvidenceContext context,
            EvidenceRequirement requirement, boolean permitRead) {
        return new AdaptiveEvidenceCoordinator.Request(context, REVISION,
                List.of(gap(context, requirement, "module-a")), policy(permitRead));
    }
    private static EvidenceContext context() {
        return EvidenceContext.forSnapshot(IngestionFixtures.inputs(Map.of("A.java", "class A {}"))
                .snapshot().identity());
    }
    private static EvidenceRequirement requirement(ContentDigest expected) {
        return new EvidenceRequirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                "dependency.exact-artifact", List.of(new EvidenceSubject(EvidenceSubject.Kind.ARTIFACT, expected.value())),
                EvidenceRequirement.AuthorizationClass.LOCAL_READ, List.of("Bytes match the selected artifact SHA-256."));
    }
    private static CapabilityGapRecord gap(EvidenceContext context, EvidenceRequirement requirement, String subject) {
        var observation = ProviderObservationReference.create(DETECTOR, "test.missing-artifact", REVISION,
                ContentDigest.sha256Utf8(subject));
        return CapabilityGapRecord.create(context, DETECTOR, "build.dependency", "EXACT_ARTIFACT_MISSING",
                new EvidenceSubject(EvidenceSubject.Kind.MODULE, subject), List.of(), List.of(observation),
                List.of(requirement), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.FRONTEND_INPUT, "java.exact-input")),
                List.of(), List.of(), List.of());
    }
    private static AdaptiveEvidenceCoordinator.CapturedArtifact artifact(EvidenceContext context,
            ContentDigest revision, byte[] bytes, ContentDigest declared) {
        return new AdaptiveEvidenceCoordinator.CapturedArtifact("lib/fixture.jar", bytes, declared, context, revision);
    }
}
