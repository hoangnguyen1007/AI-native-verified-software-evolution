package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded requirement-driven acquisition over explicitly registered, trusted analyzer providers. */
public final class AdaptiveEvidenceCoordinator {
    public static final String SCHEMA = "adaptive-evidence-coordination-v3";

    public record Policy(Set<EvidenceRequirement.AuthorizationClass> allowed, int maxAttempts,
                         long maxBytes, int maxRetriesPerProvider) {
        public Policy {
            allowed = Set.copyOf(Objects.requireNonNull(allowed));
            if (maxAttempts < 1 || maxBytes < 1 || maxRetriesPerProvider < 0)
                throw new IllegalArgumentException("Invalid acquisition limits");
        }
        public Policy(Set<EvidenceRequirement.AuthorizationClass> allowed, int maxAttempts, long maxBytes) {
            this(allowed, maxAttempts, maxBytes, 0);
        }
        public ContentDigest identity() { return IngestionEvidence.digest(List.of(SCHEMA,
                allowed.stream().sorted().toList(), maxAttempts, maxBytes, maxRetriesPerProvider)); }
    }

    public record Request(EvidenceContext context, ContentDigest inputRevision,
                          List<CapabilityGapRecord> gaps, Policy policy) {
        public Request {
            Objects.requireNonNull(context); Objects.requireNonNull(inputRevision); Objects.requireNonNull(policy);
            gaps = gaps.stream().sorted().distinct().toList();
            if (gaps.stream().anyMatch(g -> !g.context().equals(context)))
                throw new IllegalArgumentException("Acquisition gaps belong to different contexts");
        }
        public ContentDigest identity() {
            return IngestionEvidence.digest(List.of(SCHEMA, context, inputRevision,
                    gaps.stream().map(CapabilityGapRecord::gapIdentity).toList(), policy.identity()));
        }
    }

    /** Bytes are an import, not authority to execute their producer. The declared digest is checked by the coordinator. */
    public record CapturedArtifact(String logicalId, byte[] bytes, ContentDigest declaredDigest,
                                   EvidenceContext context, ContentDigest inputRevision) {
        public CapturedArtifact {
            logicalId = ContractChecks.text(logicalId, "captured artifact ID");
            bytes = Objects.requireNonNull(bytes).clone();
            Objects.requireNonNull(declaredDigest); Objects.requireNonNull(context); Objects.requireNonNull(inputRevision);
        }
        @Override public byte[] bytes() { return bytes.clone(); }
        public ContentDigest actualDigest() { return ContentDigest.sha256(bytes); }
        public long size() { return bytes.length; }
    }

    public record Acquisition(AcquisitionAttemptRecord.Outcome outcome, Optional<CapturedArtifact> artifact) {
        public Acquisition {
            Objects.requireNonNull(outcome); Objects.requireNonNull(artifact);
            if (artifact.isPresent() != (outcome == AcquisitionAttemptRecord.Outcome.SUCCEEDED
                    || outcome == AcquisitionAttemptRecord.Outcome.PARTIAL))
                throw new IllegalArgumentException("Only successful or partial acquisition has bytes");
        }
        public static Acquisition unavailable() {
            return new Acquisition(AcquisitionAttemptRecord.Outcome.UNAVAILABLE, Optional.empty());
        }
    }

    /** Implementations are analyzer-owned adapters; registration never grants permission to run target code. */
    public interface Provider {
        VersionedIdentifier id();
        Set<EvidenceRequirement.Kind> kinds();
        EvidenceRequirement.AuthorizationClass authorizationClass();
        int costClass();
        Acquisition acquire(EvidenceRequirement requirement);
        /** Explicit provider classification of a no-payload transient miss; defaults to no retry. */
        default boolean retryable(AcquisitionAttemptRecord.Outcome outcome) { return false; }
        /** Input trust is reported independently of byte integrity and semantic satisfaction. */
        default AcquisitionAttemptRecord.TrustDecision trustDecision(EvidenceRequirement requirement,
                CapturedArtifact artifact) { return AcquisitionAttemptRecord.TrustDecision.NOT_ASSESSED; }
        /** A byte match alone cannot establish a semantic or classpath question. */
        default boolean satisfies(EvidenceRequirement requirement, CapturedArtifact artifact) { return false; }
    }

    /** A concrete passive supplied-bundle provider. It only returns captured bytes for exact requirement keys. */
    public record CapturedBundleProvider(VersionedIdentifier id, Map<EvidenceRequirement, CapturedArtifact> entries)
            implements Provider {
        public CapturedBundleProvider {
            Objects.requireNonNull(id);
            entries = Map.copyOf(Objects.requireNonNull(entries));
        }
        @Override public Set<EvidenceRequirement.Kind> kinds() {
            EnumSet<EvidenceRequirement.Kind> kinds = EnumSet.noneOf(EvidenceRequirement.Kind.class);
            entries.keySet().forEach(r -> kinds.add(r.kind()));
            return kinds;
        }
        @Override public EvidenceRequirement.AuthorizationClass authorizationClass() {
            return EvidenceRequirement.AuthorizationClass.LOCAL_READ;
        }
        @Override public int costClass() { return 0; }
        @Override public Acquisition acquire(EvidenceRequirement requirement) {
            return Optional.ofNullable(entries.get(requirement))
                    .map(a -> new Acquisition(AcquisitionAttemptRecord.Outcome.SUCCEEDED, Optional.of(a)))
                    .orElseGet(Acquisition::unavailable);
        }
        @Override public boolean satisfies(EvidenceRequirement requirement, CapturedArtifact artifact) {
            return requirement.kind() == EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT;
        }
    }

    public enum OpenReason { NO_PROVIDER, DENIED, UNAVAILABLE, FAILED, LIMIT_EXCEEDED, NARROWED, CONFLICT, CANCELLED }
    public enum Termination { COMPLETE, OUTSTANDING, ATTEMPT_LIMIT, CANCELLED }
    public record Result(ContentDigest inputIdentity, EvidenceAcquisitionLedger ledger,
                         Map<CapabilityGapIdentity, OpenReason> open,
                         Map<EvidenceRequirement, CapturedArtifact> acquired,
                         Termination termination) {
        public Result {
            Objects.requireNonNull(inputIdentity); Objects.requireNonNull(ledger);
            open = Collections.unmodifiableMap(new TreeMap<>(open));
            acquired = Collections.unmodifiableMap(new TreeMap<>(acquired));
            Objects.requireNonNull(termination);
            if ((termination == Termination.COMPLETE) != open.isEmpty())
                throw new IllegalArgumentException("Complete acquisition must close every supplied gap");
        }
        public ContentDigest identity() {
            return IngestionEvidence.digest(List.of(SCHEMA, inputIdentity, ledger.identity(),
                    open.entrySet().stream().map(e -> List.of(e.getKey(), e.getValue())).toList(),
                    acquired.entrySet().stream().map(e -> List.of(e.getKey(), e.getValue().actualDigest())).toList(), termination));
        }
    }

    public Result run(Request request, List<Provider> registry) {
        return run(request, registry, () -> false);
    }

    /** Cooperative cancellation is checked between trusted provider calls, never inside untrusted target code. */
    public Result run(Request request, List<Provider> registry, BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(request); Objects.requireNonNull(registry);
        Objects.requireNonNull(cancellationRequested);
        var providers = registry.stream().sorted(Comparator.comparing((Provider p) -> p.authorizationClass().ordinal())
                .thenComparingInt(Provider::costClass).thenComparing(p -> p.id().toString())).toList();
        if (providers.stream().map(Provider::id).distinct().count() != providers.size()
                || providers.stream().anyMatch(p -> p.costClass() < 0))
            throw new IllegalArgumentException("Provider registry needs unique IDs and nonnegative costs");
        Map<EvidenceRequirement, List<CapabilityGapRecord>> demand = new TreeMap<>();
        for (var gap : request.gaps()) for (var requirement : gap.evidenceRequirements())
            demand.computeIfAbsent(requirement, ignored -> new ArrayList<>()).add(gap);
        var ordered = demand.keySet().stream().sorted(Comparator
                .<EvidenceRequirement>comparingInt(r -> -demand.get(r).size())
                .thenComparing(Comparator.naturalOrder())).toList();
        var attempts = new ArrayList<AcquisitionAttemptRecord>();
        var resolutions = new ArrayList<GapResolutionRecord>();
        var conflicts = new ArrayList<ProviderConflictRecord>();
        var acquired = new TreeMap<EvidenceRequirement, CapturedArtifact>();
        var conflicted = new HashSet<EvidenceRequirement>();
        var satisfiedProofs = new TreeMap<EvidenceRequirement, AcquisitionAttemptRecord>();
        var open = new TreeMap<CapabilityGapIdentity, OpenReason>();
        request.gaps().forEach(g -> open.put(g.gapIdentity(), OpenReason.NO_PROVIDER));
        var attempted = new HashSet<ContentDigest>();
        var completedRequirements = new HashSet<EvidenceRequirement>();
        long usedBytes = 0;
        boolean attemptLimit = false;
        boolean cancelled = false;
        requirementsLoop:
        for (var requirement : ordered) {
            if (stopRequested(cancellationRequested)) { cancelled = true; break; }
            var candidates = providers.stream().filter(p -> p.kinds().contains(requirement.kind())
                    && p.authorizationClass().ordinal() <= requirement.authorizationClass().ordinal()).toList();
            providersLoop:
            for (var provider : candidates) {
              for (int retry = 0; retry <= request.policy().maxRetriesPerProvider(); retry++) {
                if (stopRequested(cancellationRequested)) { cancelled = true; break providersLoop; }
                if (attempts.size() >= request.policy().maxAttempts()) { attemptLimit = true; break providersLoop; }
                var attemptKey = IngestionEvidence.digest(List.of(requirement, provider.id(),
                        request.inputRevision(), request.policy().identity(), retry));
                if (!attempted.add(attemptKey)) continue;
                boolean permitted = request.policy().allowed().contains(provider.authorizationClass());
                Acquisition result;
                if (!permitted) result = new Acquisition(AcquisitionAttemptRecord.Outcome.DENIED, Optional.empty());
                else try { result = Objects.requireNonNull(provider.acquire(requirement)); }
                catch (RuntimeException failure) { result = new Acquisition(AcquisitionAttemptRecord.Outcome.FAILED, Optional.empty()); }
                var outcome = result.outcome();
                var artifact = result.artifact();
                if (artifact.isPresent()) {
                    var value = artifact.orElseThrow();
                    if (!value.context().equals(request.context())
                            || !value.inputRevision().equals(request.inputRevision())
                            || !value.declaredDigest().equals(value.actualDigest())) {
                        outcome = AcquisitionAttemptRecord.Outcome.FAILED; artifact = Optional.empty();
                    } else if (requirement.requiredInputs().stream()
                            .filter(s -> s.kind() == EvidenceSubject.Kind.ARTIFACT).count() == 1
                            && requirement.requiredInputs().stream()
                            .filter(s -> s.kind() == EvidenceSubject.Kind.ARTIFACT)
                            .noneMatch(s -> s.identity().equals(value.actualDigest().value()))) {
                        outcome = AcquisitionAttemptRecord.Outcome.FAILED; artifact = Optional.empty();
                    } else if (value.size() > request.policy().maxBytes() - usedBytes) {
                        outcome = AcquisitionAttemptRecord.Outcome.LIMIT_EXCEEDED; artifact = Optional.empty();
                    } else usedBytes += value.size();
                }
                ContentDigest payload = artifact.map(CapturedArtifact::actualDigest).orElse(attemptKey);
                var resultIdentity = IngestionEvidence.digest(List.of(attemptKey, outcome, payload));
                var observation = ProviderObservationReference.create(provider.id(),
                        "evidence.acquisition", resultIdentity, payload);
                var permission = !permitted ? AcquisitionAttemptRecord.PermissionDecision.DENIED
                        : provider.authorizationClass() == EvidenceRequirement.AuthorizationClass.PASSIVE
                        ? AcquisitionAttemptRecord.PermissionDecision.NOT_REQUIRED
                        : AcquisitionAttemptRecord.PermissionDecision.AUTHORIZED;
                var output = artifact.map(a -> List.of(new AcquisitionAttemptRecord.OutputArtifact(
                        a.logicalId(), a.actualDigest()))).orElse(List.of());
                var trust = AcquisitionAttemptRecord.TrustDecision.NOT_ASSESSED;
                if (artifact.isPresent()) try {
                    trust = Objects.requireNonNull(provider.trustDecision(requirement, artifact.orElseThrow()));
                } catch (RuntimeException failure) { trust = AcquisitionAttemptRecord.TrustDecision.NOT_ASSESSED; }
                var attempt = AcquisitionAttemptRecord.create(request.context(), provider.id(), observation,
                        demand.get(requirement).getFirst().subject(), requirement, List.of(request.inputRevision()),
                        Optional.empty(), trust, permission,
                        Map.of("maxBytes", request.policy().maxBytes(), "maxAttempts", (long) request.policy().maxAttempts(),
                                "retryOrdinal", (long) retry),
                        Optional.empty(), Optional.empty(), outcome, output, List.of(), List.of("none"));
                attempts.add(attempt);
                if (artifact.isEmpty()) {
                    var reason = switch (outcome) {
                        case DENIED -> OpenReason.DENIED;
                        case LIMIT_EXCEEDED -> OpenReason.LIMIT_EXCEEDED;
                        case UNAVAILABLE -> OpenReason.UNAVAILABLE;
                        default -> OpenReason.FAILED;
                    };
                    demand.get(requirement).forEach(g -> open.put(g.gapIdentity(), reason));
                    boolean retryable = permitted && outcome == AcquisitionAttemptRecord.Outcome.UNAVAILABLE
                            && retry < request.policy().maxRetriesPerProvider();
                    if (retryable) try { retryable = provider.retryable(outcome); }
                    catch (RuntimeException failure) { retryable = false; }
                    if (retryable) continue;
                    break;
                }
                var value = artifact.orElseThrow();
                if (conflicted.contains(requirement)) break;
                var prior = acquired.putIfAbsent(requirement, value);
                if (prior != null && !prior.actualDigest().equals(value.actualDigest())) {
                    var priorAttempt = attempts.stream().filter(a -> a.requestedRequirement().equals(requirement)
                            && a.outputArtifacts().stream().anyMatch(o -> o.contentDigest().equals(prior.actualDigest())))
                            .findFirst().orElseThrow();
                    conflicts.add(ProviderConflictRecord.create(request.context(), priorAttempt.sourceObservation(), observation,
                            List.of("artifact.content"), ProviderConflictRecord.AdjudicationStatus.UNRESOLVED,
                            Optional.empty(), demand.get(requirement).stream().flatMap(g -> g.affectedOutputs().stream())
                                    .distinct().sorted().toList(), List.of(), List.of("Conflicting captured bytes withhold resolution.")));
                    acquired.remove(requirement);
                    conflicted.add(requirement);
                    satisfiedProofs.remove(requirement);
                    demand.get(requirement).forEach(g -> open.put(g.gapIdentity(), OpenReason.CONFLICT));
                    break;
                }
                long exactMatches = requirement.requiredInputs().stream()
                        .filter(s -> s.kind() == EvidenceSubject.Kind.ARTIFACT)
                        .filter(s -> s.identity().equals(value.actualDigest().value())).count();
                long exactSubjects = requirement.requiredInputs().stream()
                        .filter(s -> s.kind() == EvidenceSubject.Kind.ARTIFACT).count();
                boolean providerProof;
                try { providerProof = provider.satisfies(requirement, value); }
                catch (RuntimeException failure) { providerProof = false; }
                boolean satisfied = outcome == AcquisitionAttemptRecord.Outcome.SUCCEEDED
                        && exactSubjects == 1 && exactMatches == 1 && providerProof;
                if (satisfied) satisfiedProofs.put(requirement, attempt);
                for (var gap : demand.get(requirement)) {
                    boolean gapSatisfied = gap.evidenceRequirements().stream().allMatch(satisfiedProofs::containsKey);
                    var gapProofs = gapSatisfied ? gap.evidenceRequirements().stream()
                            .map(satisfiedProofs::get).toList() : List.of(attempt);
                    resolutions.add(GapResolutionRecord.create(gap.gapIdentity(),
                            gapSatisfied ? GapResolutionRecord.State.SATISFIED : GapResolutionRecord.State.NARROWED,
                            gapProofs.stream().map(AcquisitionAttemptRecord::sourceObservation).toList(),
                            gapProofs.stream().map(AcquisitionAttemptRecord::attemptIdentity).toList(),
                            gapSatisfied ? List.of() : List.of("Outstanding evidence requirements remain.")));
                    if (gapSatisfied) open.remove(gap.gapIdentity());
                    else if (open.containsKey(gap.gapIdentity())) open.put(gap.gapIdentity(), OpenReason.NARROWED);
                }
                if (satisfied) break providersLoop;
                break;
              }
            }
            if (cancelled) break requirementsLoop;
            if (attemptLimit) break;
            completedRequirements.add(requirement);
        }
        if (cancelled) for (var gap : request.gaps()) {
            if (open.containsKey(gap.gapIdentity())
                    && open.get(gap.gapIdentity()) != OpenReason.CONFLICT
                    && gap.evidenceRequirements().stream()
                    .anyMatch(requirement -> !completedRequirements.contains(requirement)))
                open.put(gap.gapIdentity(), OpenReason.CANCELLED);
        }
        var ledger = EvidenceAcquisitionLedger.create(EvidenceAcquisitionLedger.V3_COORDINATOR,
                request.context(), request.gaps(), attempts, conflicts, resolutions);
        var termination = open.isEmpty() ? Termination.COMPLETE
                : cancelled ? Termination.CANCELLED
                : attemptLimit ? Termination.ATTEMPT_LIMIT : Termination.OUTSTANDING;
        return new Result(request.identity(), ledger, open, acquired, termination);
    }

    private static boolean stopRequested(BooleanSupplier cancellationRequested) {
        try { return cancellationRequested.getAsBoolean(); }
        catch (RuntimeException failure) { return true; }
    }
}
