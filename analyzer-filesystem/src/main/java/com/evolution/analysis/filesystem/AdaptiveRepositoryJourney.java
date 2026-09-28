package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.ManifestComponent;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** One passive filesystem-to-exact-input journey with an explicit permitted evidence step. */
public final class AdaptiveRepositoryJourney {
    public static final String SCHEMA = "adaptive-repository-journey-v3";
    public static final VersionedIdentifier SUPERVISED_IMPORT_PROVIDER =
            new VersionedIdentifier("repository.supervised-bundle-import", "m4uv2.1-v2");
    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("repository.adaptive-journey", "m4uv2.1-v3");
    public static final VersionedIdentifier IMPORT_GAP_CATALOG =
            new VersionedIdentifier("evidence.v2-import-gaps", "m4uv2.1-v1");
    public record Policy(RepositoryInputDecoder.Policy decoding,
                         UniversalBuildIngestion.Policy build,
                         BuildModelPolicy maven) {
        public Policy { Objects.requireNonNull(decoding); Objects.requireNonNull(build); Objects.requireNonNull(maven); }
    }
    public record Components(ManifestComponent analyzer, ManifestComponent rules, ManifestComponent schema) {
        public Components { Objects.requireNonNull(analyzer); Objects.requireNonNull(rules); Objects.requireNonNull(schema); }
    }
    public record Intake(ContentDigest identity, RepositoryAcquisitionResult acquisition,
                         RepositoryInputDecoder.Result decoding,
                         Optional<UniversalBuildModel> build) {
        public Intake {
            Objects.requireNonNull(identity); Objects.requireNonNull(acquisition);
            Objects.requireNonNull(decoding); Objects.requireNonNull(build);
            if (build.isPresent() != decoding.inputs().isPresent())
                throw new IllegalArgumentException("Only complete decoded intake can have a build projection");
        }
        public RepositoryUnderstandingInventory inventory() { return decoding.inventory(); }
        public Optional<RepositoryInputs> inputs() { return decoding.inputs(); }
    }
    public record Selection(Path artifactRoot, UniversalSourceIngestion.SourceSet sourceSet,
                            FilesystemResolutionBundleImporter.Manifest manifest, PlatformInput platform,
                            List<BinaryInput> binaries, FilesystemResolutionBundleImporter.Policy policy,
                            Optional<FilesystemResolutionBundleImporter.ResolvedGraph> resolvedGraph) {
        public Selection {
            artifactRoot = Objects.requireNonNull(artifactRoot).toAbsolutePath().normalize();
            Objects.requireNonNull(sourceSet); Objects.requireNonNull(manifest);
            Objects.requireNonNull(platform); binaries = List.copyOf(binaries);
            Objects.requireNonNull(policy); Objects.requireNonNull(resolvedGraph);
        }
        public Selection(Path artifactRoot, UniversalSourceIngestion.SourceSet sourceSet,
                FilesystemResolutionBundleImporter.Manifest manifest, PlatformInput platform,
                List<BinaryInput> binaries, FilesystemResolutionBundleImporter.Policy policy) {
            this(artifactRoot, sourceSet, manifest, platform, binaries, policy, Optional.empty());
        }
    }
    public record Result(ContentDigest identity, Intake intake, UniversalBuildModel selectedBuild,
                         Optional<FilesystemResolutionBundleImporter.Result> imported,
                         AdaptiveEvidenceCoordinator.Result coordination,
                         UniversalSourceIngestion.Result sources,
                         EvidenceAcquisitionLedger ledger) {
        public Result {
            Objects.requireNonNull(identity); Objects.requireNonNull(intake); Objects.requireNonNull(selectedBuild);
            Objects.requireNonNull(imported);
            Objects.requireNonNull(coordination); Objects.requireNonNull(sources); Objects.requireNonNull(ledger);
        }
        public long outstandingGapCount() {
            var closed = new HashSet<CapabilityGapIdentity>();
            ledger.resolutions().stream().filter(r -> r.state() == GapResolutionRecord.State.SATISFIED)
                    .forEach(r -> closed.add(r.gapIdentity()));
            return ledger.gaps().stream().filter(g -> !closed.contains(g.gapIdentity())).count();
        }
    }

    public Intake intake(Path repositoryRoot, RepositoryAcquisitionRequest request, Policy policy) {
        Objects.requireNonNull(repositoryRoot); Objects.requireNonNull(request); Objects.requireNonNull(policy);
        var acquisition = new FilesystemRepositoryAcquirer().acquire(repositoryRoot, request);
        return intakeCaptured(acquisition, policy);
    }

    /** Resumes from an integrity-checked captured stage without reopening the target repository. */
    public Intake intakeCaptured(RepositoryAcquisitionResult acquisition, Policy policy) {
        Objects.requireNonNull(acquisition); Objects.requireNonNull(policy);
        var decoding = new RepositoryInputDecoder().decode(acquisition, policy.decoding());
        Optional<UniversalBuildModel> build = decoding.inputs().map(inputs ->
                new UniversalBuildIngestion().ingest(inputs, policy.build(), Optional.empty(), Map.of(), policy.maven()));
        var identity = IngestionEvidence.digest(List.of(SCHEMA, PROVIDER, acquisition.identity(),
                decoding.identity(), build.map(UniversalBuildModel::identity), policy));
        return new Intake(identity, acquisition, decoding, build);
    }

    public Result resolve(Intake intake, Selection selection,
            AdaptiveEvidenceCoordinator.Policy acquisitionPolicy, Components components) {
        return resolve(intake, selection, acquisitionPolicy, components, () -> false);
    }

    public Result resolve(Intake intake, Selection selection,
            AdaptiveEvidenceCoordinator.Policy acquisitionPolicy, Components components,
            BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(intake);
        return resolve(intake, intake.build().orElseThrow(), selection, acquisitionPolicy,
                components, cancellationRequested);
    }

    /** Accepts a separately imported selected build island bound to this exact captured input. */
    public Result resolve(Intake intake, UniversalBuildModel build, Selection selection,
            AdaptiveEvidenceCoordinator.Policy acquisitionPolicy, Components components) {
        return resolve(intake, build, selection, acquisitionPolicy, components, () -> false);
    }

    public Result resolve(Intake intake, UniversalBuildModel build, Selection selection,
            AdaptiveEvidenceCoordinator.Policy acquisitionPolicy, Components components,
            BooleanSupplier cancellationRequested) {
        return resolveInternal(intake, build, selection, acquisitionPolicy, components,
                cancellationRequested, Optional.empty());
    }

    /** Opt-in trusted-process validation of the captured exact-classpath receipt. */
    public Result resolveSupervised(Intake intake, Selection selection,
            AdaptiveEvidenceCoordinator.Policy acquisitionPolicy, Components components,
            WorkerProcessSupervisor.Policy workerPolicy, Path checkpointDirectory,
            BooleanSupplier cancellationRequested) {
        return resolveSupervised(intake, selection, acquisitionPolicy, components, workerPolicy,
                checkpointDirectory, new WorkerProcessSupervisor(), cancellationRequested);
    }

    /** The selected build can also be a separately captured source-plan island. */
    public Result resolveSupervised(Intake intake, UniversalBuildModel build, Selection selection,
            AdaptiveEvidenceCoordinator.Policy acquisitionPolicy, Components components,
            WorkerProcessSupervisor.Policy workerPolicy, Path checkpointDirectory,
            BooleanSupplier cancellationRequested) {
        return resolveInternal(intake, build, selection, acquisitionPolicy, components,
                cancellationRequested, Optional.of(new WorkerControl(workerPolicy, checkpointDirectory,
                        new WorkerProcessSupervisor(), new ArchiveWorkerSupervisor())));
    }

    Result resolveSupervised(Intake intake, Selection selection,
            AdaptiveEvidenceCoordinator.Policy acquisitionPolicy, Components components,
            WorkerProcessSupervisor.Policy workerPolicy, Path checkpointDirectory,
            WorkerProcessSupervisor supervisor, BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(intake);
        return resolveInternal(intake, intake.build().orElseThrow(), selection, acquisitionPolicy,
                components, cancellationRequested,
                Optional.of(new WorkerControl(workerPolicy, checkpointDirectory, supervisor,
                        new ArchiveWorkerSupervisor())));
    }

    Result resolveSupervised(Intake intake, Selection selection,
            AdaptiveEvidenceCoordinator.Policy acquisitionPolicy, Components components,
            WorkerProcessSupervisor.Policy workerPolicy, Path checkpointDirectory,
            WorkerProcessSupervisor receiptSupervisor, ArchiveWorkerSupervisor archiveSupervisor,
            BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(intake);
        return resolveInternal(intake, intake.build().orElseThrow(), selection, acquisitionPolicy,
                components, cancellationRequested,
                Optional.of(new WorkerControl(workerPolicy, checkpointDirectory, receiptSupervisor,
                        archiveSupervisor)));
    }

    private record WorkerControl(WorkerProcessSupervisor.Policy policy, Path checkpointDirectory,
                                 WorkerProcessSupervisor supervisor,
                                 ArchiveWorkerSupervisor archiveSupervisor) {
        private WorkerControl {
            Objects.requireNonNull(policy); Objects.requireNonNull(checkpointDirectory);
            Objects.requireNonNull(supervisor); Objects.requireNonNull(archiveSupervisor);
        }
    }

    private Result resolveInternal(Intake intake, UniversalBuildModel build, Selection selection,
            AdaptiveEvidenceCoordinator.Policy acquisitionPolicy, Components components,
            BooleanSupplier cancellationRequested, Optional<WorkerControl> workerControl) {
        Objects.requireNonNull(intake); Objects.requireNonNull(selection);
        Objects.requireNonNull(build); Objects.requireNonNull(acquisitionPolicy); Objects.requireNonNull(components);
        Objects.requireNonNull(cancellationRequested);
        var inputs = intake.inputs().orElseThrow(() -> new IllegalArgumentException("Partial intake has no exact input context"));
        if (!build.repositoryInputIdentity().equals(inputs.identity())
                || !selection.manifest().sourceIdentity().equals(inputs.identity())
                || !selection.manifest().buildIdentity().equals(build.identity())
                || !selection.manifest().sourceSet().equals(selection.sourceSet()))
            throw new IllegalArgumentException("Selection belongs to a different intake revision");
        var context = EvidenceContext.forSnapshot(inputs.snapshot().identity());
        var baseRevision = IngestionEvidence.digest(List.of(SCHEMA, inputs.identity(), build.identity(),
                selection.manifest().identity(), selection.platform().entry(),
                selection.binaries().stream().map(BinaryInput::entry).toList(), selection.policy(),
                selection.resolvedGraph().map(FilesystemResolutionBundleImporter.ResolvedGraph::identity)));
        var revision = workerControl.map(control -> IngestionEvidence.digest(List.of(baseRevision,
                SUPERVISED_IMPORT_PROVIDER, control.policy().identity()))).orElse(baseRevision);
        byte[] receiptBytes = com.evolution.analysis.contract.serialization.CanonicalJson.write(List.of(
                SCHEMA, "captured-resolution", selection.manifest().identity(),
                selection.platform().entry(), selection.binaries().stream().map(BinaryInput::entry).toList(),
                selection.policy(), selection.resolvedGraph()
                        .map(FilesystemResolutionBundleImporter.ResolvedGraph::identity)))
                .getBytes(StandardCharsets.UTF_8);
        var expected = ContentDigest.sha256(receiptBytes);
        var subject = new EvidenceSubject(EvidenceSubject.Kind.SOURCE_SET,
                selection.sourceSet().module().value() + ":" + selection.sourceSet().name());
        var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.EXACT_CLASSPATH,
                "build.captured-exact-classpath",
                List.of(new EvidenceSubject(EvidenceSubject.Kind.ARTIFACT, expected.value())),
                workerControl.isPresent() ? EvidenceRequirement.AuthorizationClass.LOCAL_WRITE
                        : EvidenceRequirement.AuthorizationClass.LOCAL_READ,
                List.of("A permitted importer verified every selected artifact and the captured order."));
        var observation = ProviderObservationReference.create(PROVIDER, "repository.exact-input-required",
                build.identity(), selection.manifest().identity());
        var gap = CapabilityGapRecord.create(context, PROVIDER, "build.classpath",
                "EXACT_CLASSPATH_REQUIRED", subject, List.of(), List.of(observation),
                List.of(requirement), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.FRONTEND_INPUT, "java.exact-input")),
                List.of(), List.of(), List.of("A declaration alone is not an exact classpath."));
        var imported = new AtomicReference<FilesystemResolutionBundleImporter.Result>();
        AdaptiveEvidenceCoordinator.Provider provider = new AdaptiveEvidenceCoordinator.Provider() {
            @Override public VersionedIdentifier id() { return workerControl.isPresent()
                    ? SUPERVISED_IMPORT_PROVIDER : FilesystemResolutionBundleImporter.PROVIDER; }
            @Override public Set<EvidenceRequirement.Kind> kinds() {
                return Set.of(EvidenceRequirement.Kind.EXACT_CLASSPATH);
            }
            @Override public EvidenceRequirement.AuthorizationClass authorizationClass() {
                return workerControl.isPresent() ? EvidenceRequirement.AuthorizationClass.LOCAL_WRITE
                        : EvidenceRequirement.AuthorizationClass.LOCAL_READ;
            }
            @Override public Set<EvidenceRequirement.AuthorizationClass> requiredPermissions() {
                return workerControl.isPresent() ? Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ,
                        EvidenceRequirement.AuthorizationClass.LOCAL_WRITE)
                        : Set.of(EvidenceRequirement.AuthorizationClass.LOCAL_READ);
            }
            @Override public Map<String, Long> resourceLimits() {
                if (workerControl.isEmpty()) return Map.of();
                var limits = workerControl.orElseThrow().policy();
                return Map.of("workerTimeoutNanos", limits.timeout().toNanos(),
                        "workerHeapMiB", (long) limits.maxHeapMiB(),
                        "workerInputBytes", (long) limits.maxInputBytes(),
                        "workerOutputBytes", (long) limits.maxOutputBytes(),
                        "workerMaxTasks", (long) limits.maxTasks());
            }
            @Override public int costClass() { return 0; }
            @Override public AdaptiveEvidenceCoordinator.Acquisition acquire(EvidenceRequirement ignored) {
                var importer = new FilesystemResolutionBundleImporter();
                FilesystemResolutionBundleImporter.Result result;
                if (workerControl.isPresent()) {
                    var control = workerControl.orElseThrow();
                    result = selection.resolvedGraph().isPresent()
                            ? importer.importBundleSupervised(selection.artifactRoot(), inputs, build,
                                    selection.sourceSet(), selection.manifest(), selection.platform(),
                                    selection.binaries(), selection.policy(), selection.resolvedGraph().orElseThrow(),
                                    control.policy(), control.checkpointDirectory(),
                                    control.archiveSupervisor(), cancellationRequested)
                            : importer.importBundleSupervised(selection.artifactRoot(), inputs, build,
                                    selection.sourceSet(), selection.manifest(), selection.platform(),
                                    selection.binaries(), selection.policy(), control.policy(),
                                    control.checkpointDirectory(), control.archiveSupervisor(), cancellationRequested);
                } else {
                    result = selection.resolvedGraph().isPresent()
                            ? importer.importBundle(selection.artifactRoot(), inputs, build,
                                    selection.sourceSet(), selection.manifest(), selection.platform(),
                                    selection.binaries(), selection.policy(), selection.resolvedGraph().orElseThrow())
                            : importer.importBundle(selection.artifactRoot(), inputs, build,
                                    selection.sourceSet(), selection.manifest(), selection.platform(),
                                    selection.binaries(), selection.policy());
                }
                imported.set(result);
                if (result.status() != FilesystemResolutionBundleImporter.Status.EXACT)
                    return AdaptiveEvidenceCoordinator.Acquisition.unavailable();
                if (workerControl.isPresent()) {
                    var control = workerControl.orElseThrow();
                    var task = new WorkerProcessSupervisor.Task("classpath-" + revision.value().substring(7, 39),
                            receiptBytes, expected);
                    var checked = control.supervisor().run(List.of(task), control.policy(),
                            control.checkpointDirectory(), cancellationRequested);
                    if (checked.termination() != WorkerProcessSupervisor.Termination.COMPLETE) {
                        var status = checked.units().getFirst().status();
                        return new AdaptiveEvidenceCoordinator.Acquisition(
                                status == WorkerProcessSupervisor.Status.CANCELLED
                                        ? AcquisitionAttemptRecord.Outcome.CANCELED
                                        : AcquisitionAttemptRecord.Outcome.FAILED,
                                Optional.empty(), checked.gaps(context, SUPERVISED_IMPORT_PROVIDER,
                                        EvidenceRequirement.AuthorizationClass.LOCAL_WRITE));
                    }
                }
                return new AdaptiveEvidenceCoordinator.Acquisition(AcquisitionAttemptRecord.Outcome.SUCCEEDED,
                        Optional.of(new AdaptiveEvidenceCoordinator.CapturedArtifact(
                                "captured-classpath", receiptBytes, expected, context, revision)));
            }
            @Override public boolean satisfies(EvidenceRequirement ignored,
                    AdaptiveEvidenceCoordinator.CapturedArtifact artifact) {
                return imported.get() != null
                        && imported.get().status() == FilesystemResolutionBundleImporter.Status.EXACT
                        && artifact.actualDigest().equals(expected);
            }
            @Override public AcquisitionAttemptRecord.TrustDecision trustDecision(EvidenceRequirement ignored,
                    AdaptiveEvidenceCoordinator.CapturedArtifact artifact) {
                return imported.get() != null
                        && imported.get().status() == FilesystemResolutionBundleImporter.Status.EXACT
                        ? AcquisitionAttemptRecord.TrustDecision.TRUSTED_INPUT
                        : AcquisitionAttemptRecord.TrustDecision.NOT_ASSESSED;
            }
        };
        var coordination = new AdaptiveEvidenceCoordinator().run(
                new AdaptiveEvidenceCoordinator.Request(context, revision, List.of(gap), acquisitionPolicy),
                List.of(provider), cancellationRequested);
        var resolutions = new HashMap<UniversalSourceIngestion.SourceSet, UniversalSourceIngestion.Resolution>();
        if (coordination.termination() == AdaptiveEvidenceCoordinator.Termination.COMPLETE
                && imported.get() != null && imported.get().resolution().isPresent())
            resolutions.put(selection.sourceSet(), imported.get().resolution().orElseThrow());
        var sources = new UniversalSourceIngestion().assemble(inputs, build, resolutions,
                components.analyzer(), components.rules(), components.schema());
        var allGaps = new ArrayList<CapabilityGapRecord>(coordination.ledger().gaps());
        allGaps.addAll(build.gaps());
        sources.gaps().stream().filter(g -> g.context().equals(context)).forEach(allGaps::add);
        if (imported.get() != null) for (var problem : imported.get().problems())
            allGaps.add(importProblemGap(context, revision, selection, imported.get(), problem));
        var allResolutions = new ArrayList<>(coordination.ledger().resolutions());
        if (!resolutions.isEmpty()) {
            var successful = coordination.ledger().attempts().stream()
                    .filter(a -> a.outcome() == AcquisitionAttemptRecord.Outcome.SUCCEEDED)
                    .findFirst().orElseThrow();
            var module = build.modules().stream().filter(m -> m.descriptor().identity().equals(
                    selection.sourceSet().module())).findFirst().orElseThrow();
            var matchingIssues = build.issues().stream().filter(i ->
                    i.reason() == IngestionEvidence.Reason.CLASSPATH_RESOLUTION_REQUIRED
                            && i.subject().equals(module.descriptor().path()))
                    .map(IngestionEvidence.Issue::identity).toList();
            build.gaps().stream().filter(g -> g.reasonCode().equals("CLASSPATH_RESOLUTION_REQUIRED")
                    && g.observationReferences().stream().anyMatch(o -> matchingIssues.contains(o.payloadDigest())))
                    .forEach(g -> allResolutions.add(GapResolutionRecord.create(g.gapIdentity(),
                            GapResolutionRecord.State.SATISFIED, List.of(successful.sourceObservation()),
                            List.of(successful.attemptIdentity()), List.of())));
        }
        var ledger = EvidenceAcquisitionLedger.create(EvidenceAcquisitionLedger.V3_COORDINATOR,
                context, allGaps, coordination.ledger().attempts(), coordination.ledger().conflicts(), allResolutions);
        var identity = IngestionEvidence.digest(List.of(SCHEMA, intake.identity(), selection.manifest().identity(),
                coordination.identity(), Optional.ofNullable(imported.get()).map(FilesystemResolutionBundleImporter.Result::identity),
                sources.identity(), ledger.identity(), components));
        return new Result(identity, intake, build, Optional.ofNullable(imported.get()), coordination, sources, ledger);
    }

    private static CapabilityGapRecord importProblemGap(EvidenceContext context, ContentDigest revision,
            Selection selection, FilesystemResolutionBundleImporter.Result imported,
            FilesystemResolutionBundleImporter.Problem problem) {
        boolean workerFailure = problem.reason().name().startsWith("WORKER_");
        VersionedIdentifier problemProvider = workerFailure
                ? SUPERVISED_IMPORT_PROVIDER : FilesystemResolutionBundleImporter.PROVIDER;
        var platform = selection.platform().artifacts().stream()
                .filter(a -> a.logicalName().equals(problem.subject())).findFirst();
        var binary = selection.binaries().stream()
                .filter(b -> b.entry().logicalName().equals(problem.subject())).findFirst();
        EvidenceRequirement.Kind kind = workerFailure ? EvidenceRequirement.Kind.REPOSITORY_CONTENT
                : platform.isPresent()
                ? EvidenceRequirement.Kind.PLATFORM_SYMBOLS
                : binary.isPresent() ? EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT
                : EvidenceRequirement.Kind.EXACT_CLASSPATH;
        var required = platform.map(a -> new EvidenceSubject(EvidenceSubject.Kind.ARTIFACT,
                a.contentDigest().value())).or(() -> binary.map(b -> new EvidenceSubject(
                EvidenceSubject.Kind.ARTIFACT, b.entry().contentDigest().value())))
                .orElse(new EvidenceSubject(EvidenceSubject.Kind.BUILD_INPUT,
                        selection.manifest().identity().value()));
        var requirement = new EvidenceRequirement(kind, workerFailure
                ? "worker.archive-inspection" : "artifact.valid-selected-input",
                List.of(required), workerFailure ? EvidenceRequirement.AuthorizationClass.LOCAL_WRITE
                        : EvidenceRequirement.AuthorizationClass.LOCAL_READ,
                workerFailure ? List.of("Replay bounded archive inspection in the trusted worker with captured bytes and policy.")
                        : List.of("Re-import an exact, bounded artifact with verified context and structural bytes."));
        var observation = ProviderObservationReference.create(problemProvider,
                "filesystem.bundle-import-problem", imported.identity(), revision);
        return CapabilityGapRecord.create(IMPORT_GAP_CATALOG, context,
                problemProvider, "build.artifact",
                problem.reason().name(), new EvidenceSubject(EvidenceSubject.Kind.ARTIFACT,
                        problem.subject()), List.of(), List.of(observation), List.of(requirement),
                List.of(), List.of(new AffectedOutput(AffectedOutput.Kind.FRONTEND_INPUT,
                        "java.exact-input")), List.of(), List.of(),
                List.of("Selected artifact import did not establish an exact input."));
    }
}
