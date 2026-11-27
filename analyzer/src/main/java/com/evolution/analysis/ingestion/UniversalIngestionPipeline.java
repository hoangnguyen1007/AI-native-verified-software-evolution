package com.evolution.analysis.ingestion;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.spring.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.universal.*;
import java.util.*;

/**
 * Executes replaceable semantic providers over an already acquired, exact
 * source/build context.
 */
public final class UniversalIngestionPipeline {
    private final AnalysisWorkspace workspace;

    public UniversalIngestionPipeline() {
        this(AnalysisWorkspace.MEMORY);
    }

    public UniversalIngestionPipeline(AnalysisWorkspace workspace) {
        this.workspace = Objects.requireNonNull(workspace);
    }

    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("repository.ingestion-pipeline",
            "m8.4-spooled-source-v6");

    public enum Status {
        ANALYZED, NOT_ANALYZED, REJECTED, FAILED
    }

    public record Unit(UniversalSourceIngestion.SourceSet sourceSet, Status status, Optional<FrontendResult> frontend,
            Optional<ComponentScanIngestion.Result> components,
            Optional<ConstructorInjectionIngestion.Result> constructors,
            Optional<UniversalSpringSemantics.Result> spring, Optional<SourceWiringCandidates.Result> sourceWiring) {
        public Unit(UniversalSourceIngestion.SourceSet sourceSet, Status status, Optional<FrontendResult> frontend,
                Optional<ComponentScanIngestion.Result> components,
                Optional<ConstructorInjectionIngestion.Result> constructors,
                Optional<UniversalSpringSemantics.Result> spring) {
            this(sourceSet, status, frontend, components, constructors, spring, Optional.empty());
        }

        public Unit(UniversalSourceIngestion.SourceSet sourceSet, Status status, Optional<FrontendResult> frontend,
                Optional<ComponentScanIngestion.Result> components,
                Optional<ConstructorInjectionIngestion.Result> constructors) {
            this(sourceSet, status, frontend, components, constructors, Optional.empty());
        }
    }

    public record Result(ContentDigest inputIdentity, UniversalSourceIngestion.Result sourceIngestion, List<Unit> units,
            List<IngestionEvidence.Issue> issues, List<CapabilityGapRecord> gaps) {
        public Result {
            units = AnalysisWorkspace.ReplayList.copy(units);
            issues = AnalysisWorkspace.ReplayList.copy(issues);
            gaps = AnalysisWorkspace.ReplayList.copy(gaps);
        }

        public ContentDigest identity() {
            return IngestionEvidence.digest(List.of(inputIdentity, sourceIngestion.identity(), units, issues, gaps));
        }
    }

    public Result run(UniversalSourceIngestion.Result sources, SemanticFrontend frontend,
            VersionedIdentifier frontendProvider, int maxTypes, int maxParameters) {
        return run(sources, frontend, frontendProvider, maxTypes, maxParameters,
                SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED);
    }

    public Result run(UniversalSourceIngestion.Result sources, SemanticFrontend frontend,
            VersionedIdentifier frontendProvider, int maxTypes, int maxParameters,
            SpringFrameworkEvidence.ValidationMode validationMode) {
        return run(sources, frontend, frontendProvider, maxTypes, maxParameters,
                com.evolution.analysis.concurrent.BoundedTasks.automaticWorkers(),
                Math.max(32L << 20, Runtime.getRuntime().maxMemory() / 3), validationMode);
    }

    /**
     * Scheduling bounds are operational, not semantic inputs. Merge in input order
     * so
     * identical captured evidence yields identical results at every worker count.
     */
    public Result run(UniversalSourceIngestion.Result sources, SemanticFrontend frontend,
            VersionedIdentifier frontendProvider, int maxTypes, int maxParameters, int parallelism, long memoryBudget) {
        return run(sources, frontend, frontendProvider, maxTypes, maxParameters, parallelism, memoryBudget,
                SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED);
    }

    @FunctionalInterface
    public interface UnitListener {
        void onUnitCompleted(int originalIndex, Unit unit, long durationMillis, boolean cacheHit);
    }

    public Result run(UniversalSourceIngestion.Result sources, SemanticFrontend frontend,
            VersionedIdentifier frontendProvider, int maxTypes, int maxParameters, int parallelism, long memoryBudget,
            SpringFrameworkEvidence.ValidationMode validationMode) {
        return run(sources, frontend, frontendProvider, maxTypes, maxParameters, parallelism, memoryBudget,
                validationMode, FrontendProgress.NOOP, null, null);
    }

    public Result run(UniversalSourceIngestion.Result sources, SemanticFrontend frontend,
            VersionedIdentifier frontendProvider, int maxTypes, int maxParameters,
            SpringFrameworkEvidence.ValidationMode validationMode,
            FrontendProgress progress, UnitListener unitListener,
            java.util.function.Predicate<FrontendRequest> cacheHitHint) {
        return run(sources, frontend, frontendProvider, maxTypes, maxParameters,
                com.evolution.analysis.concurrent.BoundedTasks.automaticWorkers(),
                Math.max(32L << 20, Runtime.getRuntime().maxMemory() / 3), validationMode,
                progress, unitListener, cacheHitHint);
    }

    public Result run(UniversalSourceIngestion.Result sources, SemanticFrontend frontend,
            VersionedIdentifier frontendProvider, int maxTypes, int maxParameters, int parallelism, long memoryBudget,
            SpringFrameworkEvidence.ValidationMode validationMode,
            FrontendProgress progress, UnitListener unitListener) {
        return run(sources, frontend, frontendProvider, maxTypes, maxParameters, parallelism, memoryBudget,
                validationMode, progress, unitListener, null);
    }

    /**
     * Priority-ordered execution with deterministic original-input-order merge and operational
     * progress callbacks. Scheduling order and callbacks never enter canonical identities or outputs.
     */
    public Result run(UniversalSourceIngestion.Result sources, SemanticFrontend frontend,
            VersionedIdentifier frontendProvider, int maxTypes, int maxParameters, int parallelism, long memoryBudget,
            SpringFrameworkEvidence.ValidationMode validationMode,
            FrontendProgress progress, UnitListener unitListener,
            java.util.function.Predicate<FrontendRequest> cacheHitHint) {
        Objects.requireNonNull(frontend);
        Objects.requireNonNull(frontendProvider);
        Objects.requireNonNull(validationMode);
        FrontendProgress safeProgress = progress != null ? progress : FrontendProgress.NOOP;
        if (maxTypes < 1 || maxParameters < 1 || parallelism < 1 || memoryBudget < 1)
            throw new IllegalArgumentException("Positive ingestion limits required");
        var identity = validationMode == SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED
                ? IngestionEvidence
                        .digest(List.of(PROVIDER, sources.identity(), frontendProvider, maxTypes, maxParameters))
                : IngestionEvidence.digest(List.of(PROVIDER, sources.identity(), frontendProvider, maxTypes,
                        maxParameters, validationMode.name()));
        var outcomes = sources.outcomes();
        int[] scheduleOrder = computeScheduleOrder(outcomes, cacheHitHint);
        boolean[] cacheHintFlags = new boolean[outcomes.size()];
        if (cacheHitHint != null) {
            for (int i = 0; i < outcomes.size(); i++) {
                var req = outcomes.get(i).request();
                cacheHintFlags[i] = req.isPresent() && cacheHitHint.test(req.orElseThrow());
            }
        }
        if (!(frontend instanceof ConcurrentSemanticFrontend)) {
            if (unitListener == null && safeProgress == FrontendProgress.NOOP && cacheHitHint == null) {
                return analyze(sources, outcomes, frontend, frontendProvider, maxTypes, maxParameters, identity,
                        validationMode, safeProgress);
            }
            var orderedResults = new Result[outcomes.size()];
            for (int pos = 0; pos < scheduleOrder.length; pos++) {
                int originalIndex = scheduleOrder[pos];
                var outcome = outcomes.get(originalIndex);
                long started = System.nanoTime();
                Result single = analyze(sources, List.of(outcome), frontend, frontendProvider, maxTypes, maxParameters,
                        identity, validationMode, safeProgress);
                long durationMs = (System.nanoTime() - started) / 1_000_000L;
                orderedResults[originalIndex] = single;
                notifyUnitCompleted(unitListener, safeProgress, originalIndex, single, durationMs,
                        cacheHintFlags[originalIndex]);
            }
            return merge(sources, identity, Arrays.asList(orderedResults));
        }
        if (parallelism == 1 || outcomes.size() < 2) {
            var orderedResults = new Result[outcomes.size()];
            for (int pos = 0; pos < scheduleOrder.length; pos++) {
                int originalIndex = scheduleOrder[pos];
                var outcome = outcomes.get(originalIndex);
                try {
                    long started = System.nanoTime();
                    Result single = analyze(sources, List.of(outcome), frontend, frontendProvider, maxTypes,
                            maxParameters, identity, validationMode, safeProgress);
                    long durationMs = (System.nanoTime() - started) / 1_000_000L;
                    orderedResults[originalIndex] = single;
                    notifyUnitCompleted(unitListener, safeProgress, originalIndex, single, durationMs,
                            cacheHintFlags[originalIndex]);
                } finally {
                    ((ConcurrentSemanticFrontend) frontend).releaseCompletedContexts();
                }
            }
            return merge(sources, identity, Arrays.asList(orderedResults));
        }
        record CompletedUnit(int originalIndex, Result result, long durationMillis, boolean cacheHit) {}
        int workers = Math.min(parallelism, outcomes.size());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(workers);
        var completion = new java.util.concurrent.ExecutorCompletionService<CompletedUnit>(executor);
        var active = new HashMap<java.util.concurrent.Future<CompletedUnit>, Long>();
        var results = new Result[outcomes.size()];
        int next = 0, finished = 0;
        long admitted = 0;
        try {
            while (finished < results.length) {
                while (next < results.length && active.size() < workers) {
                    int originalIndex = scheduleOrder[next];
                    var outcome = outcomes.get(originalIndex);
                    long cost = outcome.request().map(((ConcurrentSemanticFrontend) frontend)::estimatedWorkingBytes)
                            .orElse(1L);
                    if (cost < 1)
                        throw new IllegalArgumentException("Positive frontend working estimate required");
                    long headroom = Runtime.getRuntime().maxMemory()
                            - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
                    long capacity = Math.min(memoryBudget, Math.max(0, headroom - (64L << 20)));
                    if (!active.isEmpty() && cost > capacity - admitted)
                        break;
                    next++;
                    boolean hintHit = cacheHintFlags[originalIndex];
                    var task = completion.submit(() -> {
                        long started = System.nanoTime();
                        Result res = analyze(sources, List.of(outcome), frontend, frontendProvider, maxTypes,
                                maxParameters, identity, validationMode, safeProgress);
                        long durationMs = (System.nanoTime() - started) / 1_000_000L;
                        return new CompletedUnit(originalIndex, res, durationMs, hintHit);
                    });
                    active.put(task, cost);
                    admitted += cost;
                }
                var task = completion.take();
                Long completedCost = active.remove(task);
                if (completedCost != null)
                    admitted = Math.max(0L, admitted - completedCost);
                var completed = task.get();
                results[completed.originalIndex()] = completed.result();
                finished++;
                notifyUnitCompleted(unitListener, safeProgress, completed.originalIndex(), completed.result(),
                        completed.durationMillis(), completed.cacheHit());
                if (active.isEmpty()) {
                    ((ConcurrentSemanticFrontend) frontend).releaseCompletedContexts();
                    admitted = 0;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("INGESTION_CANCELLED");
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime)
                throw runtime;
            if (failure.getCause() instanceof Error error)
                throw error;
            throw new IllegalStateException("INGESTION_WORKER_FAILED", failure.getCause());
        } finally {
            for (var task : active.keySet())
                task.cancel(true);
            executor.shutdownNow();
            // Workers can still own request rows and provider caches after cancellation.
            boolean interrupted = Thread.interrupted();
            try {
                while (!executor.isTerminated())
                    try {
                        executor.awaitTermination(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException again) {
                        interrupted = true;
                    }
                if (!active.isEmpty())
                    ((ConcurrentSemanticFrontend) frontend).releaseCompletedContexts();
            } finally {
                if (interrupted)
                    Thread.currentThread().interrupt();
            }
        }
        return merge(sources, identity, Arrays.asList(results));
    }

    private static int[] computeScheduleOrder(
            List<UniversalSourceIngestion.Outcome> outcomes,
            java.util.function.Predicate<FrontendRequest> cacheHitHint) {
        int n = outcomes.size();
        record Slot(int index, int priorityTier, int fileCount, long totalBytes) {}
        var slots = new ArrayList<Slot>(n);
        for (int i = 0; i < n; i++) {
            var reqOpt = outcomes.get(i).request();
            if (reqOpt.isEmpty()) {
                slots.add(new Slot(i, 0, 0, 0L));
            } else {
                var req = reqOpt.orElseThrow();
                boolean hit = cacheHitHint != null && cacheHitHint.test(req);
                int files = req.sources().size();
                long bytes = 0L;
                for (var s : req.sources()) bytes += s.bytes().length;
                slots.add(new Slot(i, hit ? 0 : 1, files, bytes));
            }
        }
        slots.sort(Comparator.comparingInt(Slot::priorityTier)
                .thenComparingInt(Slot::fileCount)
                .thenComparingLong(Slot::totalBytes)
                .thenComparingInt(Slot::index));
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = slots.get(i).index();
        return order;
    }

    private static void notifyUnitCompleted(
            UnitListener unitListener,
            FrontendProgress progress,
            int originalIndex,
            Result single,
            long durationMillis,
            boolean cacheHit) {
        if (single == null || single.units().isEmpty()) return;
        Unit unit = single.units().getFirst();
        if (progress != FrontendProgress.NOOP && unit.status() != Status.ANALYZED) {
            int files = unit.frontend().map(f -> f.sources().size()).orElse(0);
            int types = unit.frontend().map(f -> f.typeDeclarations().size()).orElse(0);
            progress.onModuleCompleted(
                    unit.sourceSet().module().value(),
                    unit.sourceSet().name(),
                    files,
                    types,
                    durationMillis,
                    cacheHit);
        }
        if (unitListener != null) {
            unitListener.onUnitCompleted(originalIndex, unit, durationMillis, cacheHit);
        }
    }

    private Result merge(UniversalSourceIngestion.Result sources, ContentDigest identity, Collection<Result> results) {
        var units = workspace.<Unit>list();
        var issues = workspace.<IngestionEvidence.Issue>list();
        var gaps = workspace.gaps(sources.gaps());
        for (var result : results) {
            units.addAll(result.units());
            issues.addAll(result.issues());
            gaps.addAll(result.gaps());
        }
        var gapRows = workspace.<CapabilityGapRecord>list();
        gapRows.addAll(gaps);
        return new Result(identity, sources, workspace.freeze(units),
                workspace.distinct(issues, i -> i.identity().value()), workspace.freeze(gapRows));
    }

    private Result analyze(UniversalSourceIngestion.Result sources, List<UniversalSourceIngestion.Outcome> outcomes,
            SemanticFrontend frontend, VersionedIdentifier frontendProvider, int maxTypes, int maxParameters,
            ContentDigest identity, SpringFrameworkEvidence.ValidationMode validationMode, FrontendProgress progress) {
        var units = workspace.<Unit>list();
        var issues = workspace.<IngestionEvidence.Issue>list();
        var gaps = workspace.gaps(outcomes == sources.outcomes() ? sources.gaps()
                : List.<com.evolution.analysis.evidence.CapabilityGapRecord>of());
        try (var scope = FrontendProgress.bind(progress)) {
            for (var source : outcomes) {
                if (source.request().isEmpty()) {
                    units.add(new Unit(source.sourceSet(), Status.NOT_ANALYZED, Optional.empty(), Optional.empty(),
                            Optional.empty()));
                    continue;
                }
                var request = source.request().orElseThrow();
                if (progress != FrontendProgress.NOOP) {
                    progress.onModuleStarted(
                            source.sourceSet().module().value(),
                            source.sourceSet().name(),
                            request.sources().size());
                }
                Optional<FrontendResult> acquired = Optional.empty();
                Optional<ComponentScanIngestion.Result> scanned = Optional.empty();
                try {
                    var result = frontend.analyze(request).validateFor(request);
                    if (!result.frontend().equals(frontendProvider))
                        throw new FrontendInputException("frontend.provider-identity",
                                "Frontend identity differs from the requested provider");
                    acquired = Optional.of(result);
                    gaps.addAll(CapabilityGapNormalizer.normalize(EvidenceContext.forAnalysis(request.manifest()),
                            EvidenceNormalizationInput.builder().frontendResults(List.of(result)).build()).gaps());
                    if (request.sourceRecovery()) {
                        var issue = new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.CONTEXT_INCOMPLETE,
                                source.sourceSet() + "#SOURCE_RECOVERY",
                                List.of(UniversalSpringEvidence.derived(identity, "source-recovery")));
                        gaps.addAll(UniversalSpringEvidence.gaps(request.manifest().snapshot().identity(), identity,
                                List.of(issue)));
                    }
                    var framework = framework(request);
                    var components = new ComponentScanIngestion().scan(request, result, framework, maxTypes,
                            validationMode);
                    scanned = Optional.of(components);
                    gaps.addAll(components.gaps());
                    var constructors = new ConstructorInjectionIngestion().ingest(result, components, framework,
                            maxParameters, validationMode);
                    gaps.addAll(constructors.gaps());
                    var springSource = new SpringSourceEvidence(request, result, framework, validationMode);
                    var spring = UniversalSpringSemantics.analyze(springSource, framework,
                            components, constructors, source.configuration(), source.metadata(),
                            Math.max(maxTypes, maxParameters));
                    gaps.addAll(spring.gaps());
                    var sourceWiring = new SourceWiringCandidates().analyze(springSource);
                    units.add(new Unit(source.sourceSet(), Status.ANALYZED, Optional.of(result), Optional.of(components),
                            Optional.of(constructors), Optional.of(spring), Optional.of(sourceWiring)));
                } catch (Exception | LinkageError | StackOverflowError failure) {
                    if (failure instanceof java.util.concurrent.CancellationException)
                        throw (RuntimeException) failure;
                    // Retain the failed source-set obligation and continue unrelated modules. Never
                    // expose exception text.
                    var reason = failure instanceof FrontendInputException
                            ? IngestionEvidence.Reason.SEMANTIC_INPUT_REJECTED
                            : IngestionEvidence.Reason.SEMANTIC_PROVIDER_FAILED;
                    String code = failure instanceof FrontendInputException input ? input.diagnostic().code()
                            : failure.getClass().getSimpleName();
                    if (!code.matches("[A-Za-z][A-Za-z0-9.-]{0,120}"))
                        code = "provider-error";
                    var issue = new IngestionEvidence.Issue(reason, source.sourceSet() + "#" + code,
                            List.of(identity, IngestionEvidence.digest(request.manifest().identity())));
                    issues.add(issue);
                    gaps.addAll(IngestionEvidence.gaps(request.manifest().snapshot().identity(), PROVIDER, identity,
                            List.of(issue)));
                    units.add(new Unit(source.sourceSet(),
                            failure instanceof FrontendInputException ? Status.REJECTED : Status.FAILED, acquired, scanned,
                            Optional.empty()));
                }
            }
        }
        var gapRows = workspace.<CapabilityGapRecord>list();
        gapRows.addAll(gaps);
        return new Result(identity, sources, workspace.freeze(units),
                workspace.distinct(issues, i -> i.identity().value()), workspace.freeze(gapRows));
    }

    /**
     * Continue an acquired unit into real M4C plans under an explicitly evidenced
     * scope.
     * Missing order/container closure is represented by UNKNOWN completeness, never
     * inferred from sorting.
     */
    public SourceToSpringPlan.Result prepareSpring(Result pipeline, UniversalSourceIngestion.SourceSet sourceSet,
            SourceToSpringPlan.Scope scope, int limit) {
        return prepareSpring(pipeline, sourceSet, scope, limit,
                SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED);
    }

    public SourceToSpringPlan.Result prepareSpring(Result pipeline, UniversalSourceIngestion.SourceSet sourceSet,
            SourceToSpringPlan.Scope scope, int limit,
            SpringFrameworkEvidence.ValidationMode validationMode) {
        var input = pipeline.sourceIngestion().outcomes().stream().filter(o -> o.sourceSet().equals(sourceSet))
                .findFirst().orElseThrow();
        var unit = pipeline.units().stream().filter(u -> u.sourceSet().equals(sourceSet)).findFirst().orElseThrow();
        if (unit.status() != Status.ANALYZED || input.request().isEmpty())
            throw new IllegalArgumentException(
                    "Spring plans require an analyzed source set with explicit context evidence");
        var request = input.request().orElseThrow();
        var source = new SpringSourceEvidence(request, unit.frontend().orElseThrow(), framework(request),
                validationMode);
        var build = SpringBuildContext.fromExactRequest(request, pipeline.sourceIngestion().identity());
        var normalized = SourceToSpringPlan.normalize(build, source, unit.components().orElseThrow(),
                unit.constructors().orElseThrow(), scope, limit, validationMode);
        var inherited = workspace.gaps(pipeline.gaps());
        inherited.addAll(normalized.gaps());
        var gapRows = workspace.<CapabilityGapRecord>list();
        gapRows.addAll(inherited);
        return new SourceToSpringPlan.Result(normalized.inputIdentity(), normalized.inventory(),
                normalized.conditions(),
                normalized.binding(), workspace.freeze(gapRows), normalized.methodSelections());
    }

    public SourceToSpringPlan.Result prepareSpringAutoScoped(Result pipeline,
            UniversalSourceIngestion.SourceSet sourceSet,
            String container, ConditionEvidence scopeEvidence, int limit,
            SpringFrameworkEvidence.ValidationMode validationMode) {
        var input = pipeline.sourceIngestion().outcomes().stream().filter(o -> o.sourceSet().equals(sourceSet))
                .findFirst().orElseThrow();
        var unit = pipeline.units().stream().filter(u -> u.sourceSet().equals(sourceSet)).findFirst().orElseThrow();
        if (unit.status() != Status.ANALYZED || input.request().isEmpty())
            throw new IllegalArgumentException(
                    "Spring plans require an analyzed source set with explicit context evidence");
        var request = input.request().orElseThrow();
        var source = new SpringSourceEvidence(request, unit.frontend().orElseThrow(), framework(request),
                validationMode);
        var build = SpringBuildContext.fromExactRequest(request, pipeline.sourceIngestion().identity());
        var normalized = SourceToSpringPlan.normalizeAutoScoped(build, source, unit.components().orElseThrow(),
                unit.constructors().orElseThrow(),
                container, scopeEvidence, validationMode, limit);
        var inherited = workspace.gaps(pipeline.gaps());
        inherited.addAll(normalized.gaps());
        var gapRows = workspace.<CapabilityGapRecord>list();
        gapRows.addAll(inherited);
        return new SourceToSpringPlan.Result(normalized.inputIdentity(), normalized.inventory(),
                normalized.conditions(),
                normalized.binding(), workspace.freeze(gapRows), normalized.methodSelections());
    }

    private static SpringFrameworkEvidence framework(FrontendRequest request) {
        return SpringFrameworkEvidence.fromRequest(request);
    }
}
