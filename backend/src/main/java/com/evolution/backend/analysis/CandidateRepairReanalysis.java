package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.assessment.*;
import com.evolution.analysis.buildmodel.UniversalBuildIngestion;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.RepositoryInputDecoder;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import com.evolution.analysis.input.PlatformSymbolResult;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import com.evolution.analysis.maven.MavenBuildModelProvider;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.analysis.spring.truth.TruthRegionEvaluation;
import com.evolution.backend.query.CapturedRepair;
import com.evolution.backend.query.PublishedReports;
import com.evolution.backend.query.RepairPatch;
import com.evolution.backend.query.RepairReanalysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Verifies candidate and negative-control snapshots passively.
 * Uses zero-disk in-memory RepositoryAcquisitionResult + cached JDK platform + reused AcquiredFile
 * descriptors + concurrent candidate/control execution for Java/Spring repositories, falling back
 * to isolated temp dirs only when non-Java polyglot source files are present.
 */
public final class CandidateRepairReanalysis implements RepairReanalysis.Port {
    private static final String CONTROL_COMMENT = "\n// evo-mutation-control\n";
    private static final VersionedIdentifier IN_MEMORY_ACQUIRER =
            new VersionedIdentifier("repository.cas-compatibility", "m8.4-v2");
    private static final ExecutorService REANALYSIS_WORKERS = Executors.newFixedThreadPool(
            2,
            runnable -> {
                var thread = new Thread(runnable, "evo-repair-reanalysis");
                thread.setDaemon(true);
                return thread;
            });

    private final Path jdk;
    private final int release;
    private final Path mavenCache;
    private final Path storeDirectory;
    private final GraphStore graphs;
    private final PublishedReports reports;
    private final RepositoryConnections connections;

    private record CacheKey(ContentDigest proposal, ContentDigest baselineGraph, ContentDigest baselineReport, boolean includeControl) {}
    private record CandidateKey(ContentDigest proposal, ContentDigest baselineGraph, ContentDigest baselineReport) {}
    private record CandidateEntry(SnapshotIdentity snapshot, ContentDigest graph, Optional<ContentDigest> matchedReport,
                                  List<ContentDigest> reports) {}

    private static final int CACHE_CAPACITY = 32;
    private static final int BASELINE_FILE_CACHE_CAPACITY = 8;
    private final Map<ContentDigest, LocalAnalysisRunner.PolicyInput> registeredPolicies = new LinkedHashMap<>();
    private final Map<RepositoryIdentity, Path> registeredSources = new LinkedHashMap<>();
    private final Map<CacheKey, RepairReanalysis.Result> reanalysisCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<CacheKey, RepairReanalysis.Result> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };
    private final Map<CandidateKey, CandidateEntry> candidateCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<CandidateKey, CandidateEntry> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };
    private final Map<ContentDigest, Map<String, AcquiredFile>> baselineFilesCache = new LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<ContentDigest, Map<String, AcquiredFile>> eldest) {
            return size() > BASELINE_FILE_CACHE_CAPACITY;
        }
    };

    public CandidateRepairReanalysis(Path jdk, int release, Path mavenCache, Path storeDirectory,
                                     GraphStore graphs, PublishedReports reports, RepositoryConnections connections) {
        this.jdk = Objects.requireNonNull(jdk).toAbsolutePath().normalize();
        this.release = release;
        this.mavenCache = Objects.requireNonNull(mavenCache).toAbsolutePath().normalize();
        this.storeDirectory = Objects.requireNonNull(storeDirectory).toAbsolutePath().normalize();
        this.graphs = Objects.requireNonNull(graphs);
        this.reports = Objects.requireNonNull(reports);
        this.connections = connections;
    }

    public CandidateRepairReanalysis(Path jdk, int release, Path mavenCache, Path storeDirectory,
                                     GraphStore graphs, PublishedReports reports) {
        this(jdk, release, mavenCache, storeDirectory, graphs, reports, null);
    }

    public synchronized void registerPolicy(LocalAnalysisRunner.PolicyInput policy) {
        if (policy != null) registeredPolicies.put(ContentDigest.sha256(policy.bytes()), policy);
    }

    public synchronized void registerSource(RepositoryIdentity repository, Path sourceDirectory) {
        if (repository != null && sourceDirectory != null)
            registeredSources.put(repository, sourceDirectory.toAbsolutePath().normalize());
    }

    @Override
    public RepairReanalysis.Result reanalyze(CapturedRepair.Proposal proposal, KnowledgeGraph baseline,
                                            PublishedReports.Report baselineReport, boolean includeControl,
                                            BooleanSupplier cancelled) {
        var cacheKey = new CacheKey(proposal.identity(), baseline.identity(), baselineReport.identity(), includeControl);
        RepairReanalysis.Result cached;
        synchronized (this) { cached = reanalysisCache.get(cacheKey); }
        if (cached != null && isCacheReusable(cached, includeControl)) {
            return cached;
        }
        var candidate = proposal.candidate();
        var baselineSnapshot = baseline.snapshot();
        var tree = readSnapshotTree(baseline);
        if (tree == null || !tree.identity().equals(baselineSnapshot)) {
            return withheld("BASELINE_SNAPSHOT_UNAVAILABLE", proposal.identity(), baselineSnapshot);
        }
        ContentDigest policyDigest = policySource(baselineReport, candidate.baseline().finding());
        var policy = lookupPolicy(policyDigest);
        if (policy == null) {
            return withheld("POLICY_SOURCE_UNAVAILABLE", proposal.identity(), baselineSnapshot);
        }

        // Decode ONLY edited baseline file digests first so invalid patches fail in <1ms
        Set<ContentDigest> editDigests = new HashSet<>();
        for (var edit : candidate.edits()) editDigests.add(edit.before());
        Path acquisitionContent = storeDirectory.resolve("acquisition/content");
        StreamingContentStore cas = null;
        if (Files.isDirectory(acquisitionContent, LinkOption.NOFOLLOW_LINKS)) {
            try { cas = new StreamingContentStore(acquisitionContent); } catch (IOException ignored) {}
        }
        Path localCheckout = lookupCheckout(tree.repository());
        var editedBaseBytes = readSelectedGraphSources(baseline, editDigests);
        for (var edit : candidate.edits()) {
            if (!editedBaseBytes.containsKey(edit.before())) {
                byte[] fallback = resolveBaselineFile(edit.path(), edit.before(), editedBaseBytes, cas, localCheckout);
                if (fallback != null) editedBaseBytes.put(edit.before(), fallback);
            }
        }

        var patchedTexts = applyCandidatePatch(baseline, candidate, editedBaseBytes);
        if (patchedTexts == null) {
            return withheld("PATCH_APPLICATION_FAILED", proposal.identity(), baselineSnapshot);
        }

        boolean includeTests = hasTestContext(baseline);
        var candKey = new CandidateKey(proposal.identity(), baseline.identity(), baselineReport.identity());
        CandidateEntry candEntry;
        synchronized (this) { candEntry = candidateCache.get(candKey); }
        if (candEntry != null && !isCandidateReusable(candEntry)) {
            candEntry = null;
        }

        boolean needControl = includeControl && !candidate.edits().isEmpty();
        boolean inMemoryFastPath = isInMemoryEligible(tree, baseline);

        Path workRoot = null;
        try {
            Map<String, AcquiredFile> baseFiles = null;
            if (candEntry == null || needControl) {
                baseFiles = resolveAllBaselineFiles(baseline, tree, cas, localCheckout);
                if (baseFiles == null) {
                    return withheld("BASELINE_SOURCE_UNAVAILABLE", proposal.identity(), baselineSnapshot);
                }
            }

            if (inMemoryFastPath) {
                return executeInMemoryReanalysis(
                        proposal, baseline, baselineReport, tree, includeTests, policy,
                        cacheKey, candKey, candEntry, needControl, patchedTexts, baseFiles, cancelled);
            }

            // Fallback disk path only for polyglot repositories with non-JVM files
            if (candEntry == null || needControl) {
                Path parent = storeDirectory.resolve("reanalysis");
                Files.createDirectories(parent);
                workRoot = Files.createTempDirectory(parent, "run-");
            }

            final Path finalWorkRoot = workRoot;
            final Map<String, AcquiredFile> finalBaseFiles = baseFiles;
            CompletableFuture<AnalysisJobs.Outcome> ctrlFuture = null;
            if (needControl) {
                var firstEdit = candidate.edits().getFirst();
                AcquiredFile origFile = finalBaseFiles.get(firstEdit.path());
                if (origFile != null && origFile.contentDigest().equals(firstEdit.before())) {
                    Path ctrlDir = finalWorkRoot.resolve("control");
                    Files.createDirectories(ctrlDir);
                    byte[] orig = origFile.bytes();
                    byte[] suffix = CONTROL_COMMENT.getBytes(StandardCharsets.UTF_8);
                    byte[] ctrlEdited = Arrays.copyOf(orig, orig.length + suffix.length);
                    System.arraycopy(suffix, 0, ctrlEdited, orig.length, suffix.length);
                    for (var file : tree.files()) {
                        byte[] bytes = file.path().equals(firstEdit.path())
                                ? ctrlEdited
                                : finalBaseFiles.get(file.path()).bytes();
                        writeRelative(ctrlDir, file.path(), bytes);
                    }
                    ctrlFuture = CompletableFuture.supplyAsync(() -> {
                        try {
                            return runPassive(ctrlDir, tree.repository(), tree.revision(), includeTests, policy,
                                    finalWorkRoot.resolve("control-intake"), cancelled);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    }, REANALYSIS_WORKERS);
                }
            }

            if (candEntry == null) {
                Path candDir = finalWorkRoot.resolve("candidate");
                Files.createDirectories(candDir);
                for (var file : tree.files()) {
                    byte[] bytes = patchedTexts.containsKey(file.path())
                            ? patchedTexts.get(file.path())
                            : finalBaseFiles.get(file.path()).bytes();
                    writeRelative(candDir, file.path(), bytes);
                }
                for (var edit : candidate.edits()) {
                    if (!ContentDigest.sha256(patchedTexts.get(edit.path())).equals(edit.after())) {
                        if (ctrlFuture != null) ctrlFuture.cancel(true);
                        return withheld("CANDIDATE_DIGEST_MISMATCH", proposal.identity(), baselineSnapshot);
                    }
                }
                var candOutcome = runPassive(candDir, tree.repository(), tree.revision(), includeTests, policy,
                        finalWorkRoot.resolve("candidate-intake"), cancelled);
                if (candOutcome.graph().isEmpty()) {
                    if (ctrlFuture != null) ctrlFuture.cancel(true);
                    return withheld("CANDIDATE_ANALYSIS_FAILED", proposal.identity(), baselineSnapshot);
                }
                candEntry = publishCandidateOutcome(baseline, baselineReport, candKey, candOutcome, cancelled);
            }

            Optional<SnapshotIdentity> controlSnap = Optional.empty();
            Optional<ContentDigest> controlGraphId = Optional.empty();
            Optional<ContentDigest> controlReportId = Optional.empty();
            if (ctrlFuture != null) {
                var ctrlOutcome = ctrlFuture.join();
                if (ctrlOutcome.graph().isPresent()) {
                    KnowledgeGraph ctrlGraph = ctrlOutcome.graph().orElseThrow();
                    synchronized (this) {
                        graphs.save(ctrlGraph, cancelled);
                        List<ContentDigest> ctrlIds = reports.publishAll(ctrlOutcome.reports());
                        controlSnap = Optional.of(ctrlGraph.snapshot());
                        controlGraphId = Optional.of(ctrlGraph.identity());
                        controlReportId = matchReport(baseline, baselineReport, ctrlGraph, ctrlOutcome.reports(), ctrlIds)
                                .or(() -> ctrlIds.isEmpty() ? Optional.empty() : Optional.of(ctrlIds.getFirst()));
                    }
                }
            }

            var result = new RepairReanalysis.Result(RepairReanalysis.VERSION, "COMPLETED", Optional.empty(),
                    proposal.identity(), candEntry.snapshot(), Optional.of(candEntry.graph()),
                    candEntry.matchedReport(), controlSnap, controlGraphId, controlReportId, candEntry.reports());
            synchronized (this) { reanalysisCache.put(cacheKey, result); }
            return result;
        } catch (Exception failure) {
            return withheld("REANALYSIS_UNAVAILABLE", proposal.identity(), baselineSnapshot);
        } finally {
            if (workRoot != null) deleteQuietly(workRoot);
        }
    }

    private RepairReanalysis.Result executeInMemoryReanalysis(
            CapturedRepair.Proposal proposal,
            KnowledgeGraph baseline,
            PublishedReports.Report baselineReport,
            RepositorySnapshot tree,
            boolean includeTests,
            LocalAnalysisRunner.PolicyInput policy,
            CacheKey cacheKey,
            CandidateKey candKey,
            CandidateEntry candEntry,
            boolean needControl,
            Map<String, byte[]> patchedTexts,
            Map<String, AcquiredFile> baseFiles,
            BooleanSupplier cancelled) throws Exception {
        var candidate = proposal.candidate();
        var baselineSnapshot = baseline.snapshot();
        for (var edit : candidate.edits()) {
            byte[] next = patchedTexts.get(edit.path());
            if (next == null || !ContentDigest.sha256(next).equals(edit.after())) {
                return withheld("CANDIDATE_DIGEST_MISMATCH", proposal.identity(), baselineSnapshot);
            }
        }

        CompletableFuture<AnalysisJobs.Outcome> ctrlFuture = null;
        if (needControl) {
            var firstEdit = candidate.edits().getFirst();
            AcquiredFile origFile = baseFiles.get(firstEdit.path());
            if (origFile != null && origFile.contentDigest().equals(firstEdit.before())) {
                byte[] orig = origFile.bytes();
                byte[] suffix = CONTROL_COMMENT.getBytes(StandardCharsets.UTF_8);
                byte[] ctrlEdited = Arrays.copyOf(orig, orig.length + suffix.length);
                System.arraycopy(suffix, 0, ctrlEdited, orig.length, suffix.length);
                var ctrlAcquisition = buildInMemoryAcquisition(tree, baseFiles, Map.of(firstEdit.path(), ctrlEdited));
                ctrlFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return analyzeInMemory(ctrlAcquisition, includeTests, policy, cancelled);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, REANALYSIS_WORKERS);
            }
        }

        if (candEntry == null) {
            var candAcquisition = buildInMemoryAcquisition(tree, baseFiles, patchedTexts);
            AnalysisJobs.Outcome candOutcome;
            try {
                candOutcome = analyzeInMemory(candAcquisition, includeTests, policy, cancelled);
            } catch (Throwable err) {
                if (ctrlFuture != null) ctrlFuture.cancel(true);
                throw err;
            }
            if (candOutcome.graph().isEmpty()) {
                if (ctrlFuture != null) ctrlFuture.cancel(true);
                return withheld("CANDIDATE_ANALYSIS_FAILED", proposal.identity(), baselineSnapshot);
            }
            candEntry = publishCandidateOutcome(baseline, baselineReport, candKey, candOutcome, cancelled);
        }

        Optional<SnapshotIdentity> controlSnap = Optional.empty();
        Optional<ContentDigest> controlGraphId = Optional.empty();
        Optional<ContentDigest> controlReportId = Optional.empty();
        if (ctrlFuture != null) {
            AnalysisJobs.Outcome ctrlOutcome;
            try {
                ctrlOutcome = ctrlFuture.join();
            } catch (CompletionException ce) {
                if (ce.getCause() instanceof Exception ex) throw ex;
                throw ce;
            }
            if (ctrlOutcome.graph().isPresent()) {
                KnowledgeGraph ctrlGraph = ctrlOutcome.graph().orElseThrow();
                synchronized (this) {
                    graphs.save(ctrlGraph, cancelled);
                    List<ContentDigest> ctrlIds = reports.publishAll(ctrlOutcome.reports());
                    controlSnap = Optional.of(ctrlGraph.snapshot());
                    controlGraphId = Optional.of(ctrlGraph.identity());
                    controlReportId = matchReport(baseline, baselineReport, ctrlGraph, ctrlOutcome.reports(), ctrlIds)
                            .or(() -> ctrlIds.isEmpty() ? Optional.empty() : Optional.of(ctrlIds.getFirst()));
                }
            }
        }

        var result = new RepairReanalysis.Result(RepairReanalysis.VERSION, "COMPLETED", Optional.empty(),
                proposal.identity(), candEntry.snapshot(), Optional.of(candEntry.graph()),
                candEntry.matchedReport(), controlSnap, controlGraphId, controlReportId, candEntry.reports());
        synchronized (this) { reanalysisCache.put(cacheKey, result); }
        return result;
    }

    private CandidateEntry publishCandidateOutcome(
            KnowledgeGraph baseline,
            PublishedReports.Report baselineReport,
            CandidateKey candKey,
            AnalysisJobs.Outcome candOutcome,
            BooleanSupplier cancelled) {
        KnowledgeGraph candGraph = candOutcome.graph().orElseThrow();
        synchronized (this) {
            graphs.save(candGraph, cancelled);
            List<ContentDigest> candReportIds = reports.publishAll(candOutcome.reports());
            ContentDigest matchedCandReport = matchReport(baseline, baselineReport, candGraph, candOutcome.reports(), candReportIds)
                    .orElse(candReportIds.isEmpty() ? null : candReportIds.getFirst());
            var entry = new CandidateEntry(candGraph.snapshot(), candGraph.identity(),
                    Optional.ofNullable(matchedCandReport), candReportIds);
            candidateCache.put(candKey, entry);
            return entry;
        }
    }

    private AnalysisJobs.Outcome analyzeInMemory(
            RepositoryAcquisitionResult acquisition,
            boolean includeTests,
            LocalAnalysisRunner.PolicyInput policyInput,
            BooleanSupplier cancelled) throws Exception {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        var platform = LocalAnalysisRunner.acquireCachedPlatform(jdk, release);
        var models = MavenScan.models(acquisition, mavenCache, List.of());
        var defaults = UniversalBuildIngestion.Policy.defaults();
        var buildPolicy = new UniversalBuildIngestion.Policy(
                Optional.of(release), Optional.of(release), Optional.of("UTF-8"),
                32_768, defaults.maxCharacters(), defaults.maxTokens(), 128);
        var decoded = new RepositoryInputDecoder.Prepared(
                acquisition, new RepositoryInputDecoder.Policy("UTF-8", 500_000, 100_000_000));
        var gradleModel = GradleScan.model(decoded, buildPolicy, Map.of());
        var requiredReleases = new TreeSet<>(MavenScan.platformReleases(models));
        requiredReleases.addAll(GradleScan.platformReleases(gradleModel));
        var platformViews = new TreeMap<Integer, PlatformSymbolResult>();
        platformViews.put(release, platform);
        var additionalPlatforms = new ArrayList<PlatformSymbolResult>();
        for (int target : requiredReleases) {
            if (!platformViews.containsKey(target)) {
                var view = LocalAnalysisRunner.acquireCachedPlatform(jdk, target);
                platformViews.put(target, view);
                additionalPlatforms.add(view);
            }
        }
        var availablePlatforms = new TreeMap<Integer, PlatformInput>();
        platformViews.forEach((target, view) -> view.platform().ifPresent(value -> availablePlatforms.put(target, value)));
        var snapshotId = acquisition.snapshot().orElseThrow().identity();
        var platformLedgers = additionalPlatforms.isEmpty()
                ? List.<EvidenceAcquisitionLedger>of()
                : List.of(CapabilityGapNormalizer.normalize(
                        EvidenceContext.forSnapshot(snapshotId),
                        EvidenceNormalizationInput.builder().platformResults(additionalPlatforms).build()));
        var depPolicy = new DependencyAcquisitionPolicy(1024, 64_000_000, 256_000_000, 64, List.of(), 5000, 15000, 0);
        var mavenBase = MavenScan.inputs(acquisition, models, mavenCache, platform.platform(), depPolicy, availablePlatforms);
        var dependencies = GradleScan.augment(mavenBase, acquisition, gradleModel, mavenCache, platform.platform(), depPolicy, availablePlatforms);
        var frontend = new JavaParserFrontend();
        var guarded = new ConcurrentSemanticFrontend() {
            @Override public long estimatedWorkingBytes(FrontendRequest request) { return frontend.estimatedWorkingBytes(request); }
            @Override public void releaseCompletedContexts() { frontend.releaseCompletedContexts(); }
            @Override public FrontendResult analyze(FrontendRequest request) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                var res = frontend.analyze(request);
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                return res;
            }
        };
        var pipelinePolicy = new UniversalArchitecturePipeline.Policy(
                buildPolicy, decoded.policy(), guarded, JavaParserFrontend.PROVIDER,
                dependencies.resolutions(), platform.platform(), Optional.of(new MavenBuildModelProvider()),
                dependencies.externalPoms(), MavenScan.POLICY, 100_000, 1_000,
                UniversalArchitecturePipeline.TruthEvaluationMode.AUTO, Map.of(), Optional.empty(), Optional.empty(),
                TruthRegionEvaluation.Limits.conservative())
                .withPrecomputedBuild(dependencies.externalPoms().isEmpty() ? gradleModel.orElse(null) : null);

        var detailed = new UniversalArchitecturePipeline().runWithEvidence(decoded, pipelinePolicy);
        var allPlatforms = new ArrayList<PlatformSymbolResult>();
        allPlatforms.add(platform);
        allPlatforms.addAll(additionalPlatforms);
        var gaps = new TreeSet<>(detailed.report().gaps());
        gaps.addAll(dependencies.gaps());
        platformLedgers.forEach(ledger -> gaps.addAll(ledger.gaps()));
        gaps.addAll(CapabilityGapNormalizer.normalize(
                EvidenceContext.forSnapshot(snapshotId),
                EvidenceNormalizationInput.builder()
                        .repositoryAcquisitions(List.of(acquisition))
                        .platformResults(allPlatforms)
                        .build()).gaps());
        if (detailed.graphInput().isEmpty()) {
            return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED, Optional.empty(), List.of(), List.copyOf(gaps),
                    Optional.of(acquisition.identity()), Optional.of(detailed.report().identity()), List.of());
        }
        var captured = detailed.graphInput().orElseThrow();
        gaps.addAll(captured.gaps());
        var graphLedgers = new ArrayList<>(dependencies.ledgers());
        graphLedgers.addAll(platformLedgers);
        var graph = new CanonicalGraphBuilder().build(
                new CanonicalGraphInput(captured.repository(), captured.build(), captured.ingestion(), captured.spring(),
                        captured.issues(), List.copyOf(gaps), captured.acquisition(), captured.javaEvidence()),
                graphLedgers);

        var evalReports = new ArrayList<PublishedReports.Report>();
        var compiled = new PolicyCompiler().compile(
                policyInput.bytes(), EvidenceContext.forSnapshot(graph.snapshot()), PolicyCompiler.Limits.defaults());
        gaps.addAll(compiled.gaps());
        var guard = ArchitectureQueryService.operationalGuard(cancelled, 0, 8_000_000);
        var queries = new ArchitectureMetricsService(
                graph, ArchitectureMetric.Limits.defaults(), guard, Optional.empty(), List.of()).queries();
        for (var context : graph.contexts()) {
            if (context.analysis().isEmpty()) continue;
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
            var plan = new PolicyGraphMapper().bind(compiled, queries, context.identity(), PolicyGraphMapper.Limits.defaults());
            var evaluation = new PolicyEvaluator().evaluate(compiled, plan, queries, PolicyEvaluation.Limits.defaults());
            var metrics = new PolicyMetricsService().compute(compiled, plan, evaluation, queries, PolicyMetrics.Limits.defaults());
            var score = new ArchitectureAssessmentService().assess(metrics, evaluation, queries,
                    Optional.of(ArchitectureScoreModel.candidate(policyInput.scoreScope())), plan.limits().query(),
                    ArchitectureMetric.Limits.defaults(), ArchitectureAssessment.Limits.defaults());
            evalReports.add(new PublishedReports.Report(evaluation, Optional.of(metrics), Optional.of(score)));
            gaps.addAll(plan.gaps());
            gaps.addAll(evaluation.gaps());
            gaps.addAll(metrics.gaps());
            gaps.addAll(score.gaps());
        }
        var completion = switch (detailed.report().status()) {
            case COMPLETE -> gaps.isEmpty() ? AnalysisJobs.Completion.COMPLETE : AnalysisJobs.Completion.PARTIAL;
            case EMPTY_OR_NON_JAVA -> AnalysisJobs.Completion.EMPTY;
            case TERMINAL_FAILURE, CANCELLED -> AnalysisJobs.Completion.FAILED;
            case PARTIAL -> AnalysisJobs.Completion.PARTIAL;
        };
        return new AnalysisJobs.Outcome(completion, Optional.of(graph), evalReports, List.copyOf(gaps),
                Optional.of(acquisition.identity()), Optional.of(detailed.report().identity()), List.of());
    }

    private static RepositoryAcquisitionResult buildInMemoryAcquisition(
            RepositorySnapshot baseTree,
            Map<String, AcquiredFile> baseFiles,
            Map<String, byte[]> overrides) {
        var acqFiles = new ArrayList<AcquiredFile>(baseTree.files().size());
        var snapFiles = new ArrayList<SnapshotFile>(baseTree.files().size());
        var dirs = new TreeSet<String>();
        dirs.add(".");
        boolean hasRootPom = false;
        for (var file : baseTree.files()) {
            String p = file.path();
            if ("pom.xml".equals(p)) hasRootPom = true;
            int slash = p.indexOf('/');
            while (slash > 0) {
                dirs.add(p.substring(0, slash));
                slash = p.indexOf('/', slash + 1);
            }
            AcquiredFile af = overrides.containsKey(p)
                    ? new AcquiredFile(p, overrides.get(p))
                    : baseFiles.get(p);
            acqFiles.add(af);
            snapFiles.add(new SnapshotFile(p, af.contentDigest()));
        }
        var snapshot = RepositorySnapshot.create(baseTree.repository(), baseTree.revision(), true, snapFiles, List.of());
        var request = new RepositoryAcquisitionRequest(
                baseTree.repository(), baseTree.revision(), true, "pom.xml",
                new RepositoryAcquisitionPolicy(500_000, 500_000, 10_000_000L, 8_000_000_000L, 256, List.of()));
        List<RepositoryAcquisitionResult.Problem> problems = hasRootPom
                ? List.of()
                : List.of(new RepositoryAcquisitionResult.Problem(
                        RepositoryAcquisitionResult.Reason.MISSING_ROOT_POM,
                        "pom.xml",
                        RepositoryAcquisitionResult.Requirement.BUILD_MODEL_INPUT));
        return new RepositoryAcquisitionResult(
                RepositoryAcquisitionResult.SCHEMA,
                request,
                IN_MEMORY_ACQUIRER,
                RepositoryAcquisitionResult.Completion.COMPLETE,
                Optional.of(snapshot),
                acqFiles,
                List.copyOf(dirs),
                problems,
                List.of(),
                RepositoryAcquisitionResult.LIMITATIONS);
    }

    private static boolean isInMemoryEligible(RepositorySnapshot tree, KnowledgeGraph baseline) {
        for (var f : tree.files()) {
            String p = f.path().toLowerCase(Locale.ROOT);
            if (!(p.endsWith(".java") || p.endsWith("pom.xml") || p.endsWith(".gradle") || p.endsWith(".gradle.kts")
                    || p.endsWith(".properties") || p.endsWith(".yml") || p.endsWith(".yaml")
                    || p.endsWith(".xml") || p.endsWith(".json") || p.endsWith(".md") || p.endsWith(".txt"))) {
                return false;
            }
            if (!includeTestsPathAllowed(p) && p.contains("/test/") && false) {
                return false;
            }
        }
        return true;
    }

    private static boolean includeTestsPathAllowed(String p) {
        return !p.isEmpty();
    }

    private Map<String, AcquiredFile> resolveAllBaselineFiles(
            KnowledgeGraph baseline,
            RepositorySnapshot tree,
            StreamingContentStore cas,
            Path localCheckout) {
        synchronized (this) {
            var hit = baselineFilesCache.get(baseline.identity());
            if (hit != null) return hit;
        }
        Map<ContentDigest, byte[]> allGraphBytes = null;
        var resolved = new LinkedHashMap<String, AcquiredFile>(tree.files().size() * 2);
        for (var file : tree.files()) {
            byte[] bytes = resolveFromCasOrCheckout(file.path(), file.contentDigest(), cas, localCheckout);
            if (bytes == null) {
                if (allGraphBytes == null) allGraphBytes = readAllGraphSources(baseline);
                bytes = allGraphBytes.get(file.contentDigest());
            }
            if (bytes == null) return null;
            var af = new AcquiredFile(file.path(), bytes);
            if (!af.contentDigest().equals(file.contentDigest())) return null;
            resolved.put(file.path(), af);
        }
        var immutable = Collections.unmodifiableMap(resolved);
        synchronized (this) {
            baselineFilesCache.put(baseline.identity(), immutable);
        }
        return immutable;
    }

    private boolean isCandidateReusable(CandidateEntry entry) {
        if (graphs.load(entry.graph()).isEmpty()) return false;
        for (var reportId : entry.reports()) {
            if (reports.get(reportId).isEmpty()) return false;
        }
        return true;
    }

    private boolean isCacheReusable(RepairReanalysis.Result cached, boolean includeControl) {
        if (!"COMPLETED".equals(cached.status()) || cached.candidateGraph().isEmpty()) return false;
        if (graphs.load(cached.candidateGraph().orElseThrow()).isEmpty()) return false;
        if (cached.candidateReport().isPresent() && reports.get(cached.candidateReport().orElseThrow()).isEmpty()) return false;
        if (includeControl) {
            if (cached.controlGraph().isPresent() && graphs.load(cached.controlGraph().orElseThrow()).isEmpty()) return false;
            if (cached.controlReport().isPresent() && reports.get(cached.controlReport().orElseThrow()).isEmpty()) return false;
        }
        return true;
    }

    private AnalysisJobs.Outcome runPassive(Path dir, RepositoryIdentity repo, Optional<String> revision,
                                            boolean includeTests, LocalAnalysisRunner.PolicyInput policy,
                                            Path intakeDir, BooleanSupplier cancelled) throws Exception {
        var input = new LocalAnalysisRunner.Input(dir, repo, jdk, release, mavenCache, revision, true, includeTests,
                List.of(), Optional.of(policy), LocalAnalysisRunner.SpringInput.absent());
        return new LocalAnalysisRunner(input, intakeDir).analyzeDirect(UUID.randomUUID().toString(), cancelled);
    }

    private synchronized LocalAnalysisRunner.PolicyInput lookupPolicy(ContentDigest digest) {
        var p = registeredPolicies.get(digest);
        if (p != null) return p;
        return connections == null ? null : connections.policy(digest).orElse(null);
    }

    private synchronized Path lookupCheckout(RepositoryIdentity repo) {
        var p = registeredSources.get(repo);
        if (p != null) return p;
        return connections == null ? null : connections.localCheckout(repo).orElse(null);
    }

    private static Map<String, byte[]> applyCandidatePatch(
            KnowledgeGraph baseline,
            CapturedRepair.Candidate candidate,
            Map<ContentDigest, byte[]> bytesByDigest) {
        var originals = new LinkedHashMap<String, String>();
        var encodings = new LinkedHashMap<String, java.nio.charset.Charset>();
        for (var edit : candidate.edits()) {
            byte[] raw = bytesByDigest.get(edit.before());
            if (raw == null) return null;
            var charset = isAsciiSource(baseline, edit.path()) ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8;
            encodings.put(edit.path(), charset);
            originals.put(edit.path(), new String(raw, charset));
        }
        try {
            var applied = RepairPatch.applyAll(originals, candidate.patch());
            if (applied.size() != candidate.edits().size()) return null;
            var out = new LinkedHashMap<String, byte[]>();
            for (var edit : candidate.edits()) {
                String next = applied.get(edit.path());
                if (next == null) return null;
                byte[] nextBytes = next.getBytes(encodings.get(edit.path()));
                if (!ContentDigest.sha256(nextBytes).equals(edit.after())) return null;
                out.put(edit.path(), nextBytes);
            }
            return out;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean isAsciiSource(KnowledgeGraph baseline, String path) {
        String needle = "\"" + path + "\"";
        for (var ev : baseline.evidence()) {
            if (!ev.family().equals("source.bytes") || !ev.canonicalJson().contains(needle)) continue;
            try {
                var row = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                var doc = GraphJson.object(row.get("document"));
                if (path.equals(doc.get("path"))) {
                    var dec = GraphJson.object(row.get("decoding"));
                    return "US-ASCII".equals(dec.get("charset"));
                }
            } catch (RuntimeException ignored) {}
        }
        return false;
    }

    private static byte[] resolveBaselineFile(String path, ContentDigest expected, Map<ContentDigest, byte[]> graphBytes,
                                              StreamingContentStore cas, Path localCheckout) {
        byte[] direct = graphBytes.get(expected);
        if (direct != null && ContentDigest.sha256(direct).equals(expected)) return direct;
        return resolveFromCasOrCheckout(path, expected, cas, localCheckout);
    }

    private static byte[] resolveFromCasOrCheckout(String path, ContentDigest expected,
                                                   StreamingContentStore cas, Path localCheckout) {
        if (cas != null) {
            try {
                Path obj = storePath(cas, expected);
                if (obj != null && Files.isRegularFile(obj, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] raw = Files.readAllBytes(obj);
                    if (ContentDigest.sha256(raw).equals(expected)) return raw;
                }
            } catch (IOException ignored) {}
        }
        if (localCheckout != null) {
            try {
                Path candidate = localCheckout.resolve(path).normalize();
                if (candidate.startsWith(localCheckout) && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] raw = Files.readAllBytes(candidate);
                    if (ContentDigest.sha256(raw).equals(expected)) return raw;
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    private static Path storePath(StreamingContentStore cas, ContentDigest digest) {
        String hex = digest.value().substring(7);
        try {
            var field = StreamingContentStore.class.getDeclaredField("root");
            field.setAccessible(true);
            Path root = (Path) field.get(cas);
            return root.resolve("sha256").resolve(hex.substring(0, 2)).resolve(hex.substring(2));
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Map<ContentDigest, byte[]> readSelectedGraphSources(KnowledgeGraph graph, Set<ContentDigest> needed) {
        var result = new HashMap<ContentDigest, byte[]>();
        if (needed.isEmpty()) return result;
        Set<String> needles = new HashSet<>();
        for (var d : needed) needles.add(d.value());
        for (var ev : graph.evidence()) {
            if (!ev.family().equals("source.content")) continue;
            String json = ev.canonicalJson();
            boolean match = false;
            for (String needle : needles) {
                if (json.contains(needle)) { match = true; break; }
            }
            if (!match) continue;
            try {
                var row = GraphJson.object(GraphJson.read(json, new GraphJson.Limits(2_000_000, 16, 128)));
                var digest = new ContentDigest(GraphJson.string(row.get("contentDigest")));
                if (!needed.contains(digest)) continue;
                Object b64 = row.get("bytesBase64");
                if (!(b64 instanceof String b64Str)) continue;
                byte[] decoded = Base64.getDecoder().decode(b64Str);
                if (ContentDigest.sha256(decoded).equals(digest)) {
                    result.put(digest, decoded);
                    if (result.size() == needed.size()) break;
                }
            } catch (RuntimeException ignored) {}
        }
        return result;
    }

    private static Map<ContentDigest, byte[]> readAllGraphSources(KnowledgeGraph graph) {
        var result = new HashMap<ContentDigest, byte[]>();
        for (var ev : graph.evidence()) {
            if (!ev.family().equals("source.content")) continue;
            try {
                var row = GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(2_000_000, 16, 128)));
                Object b64 = row.get("bytesBase64");
                if (!(b64 instanceof String b64Str)) continue;
                var digest = new ContentDigest(GraphJson.string(row.get("contentDigest")));
                byte[] decoded = Base64.getDecoder().decode(b64Str);
                if (ContentDigest.sha256(decoded).equals(digest)) result.put(digest, decoded);
            } catch (RuntimeException ignored) {}
        }
        return result;
    }

    private static RepositorySnapshot readSnapshotTree(KnowledgeGraph graph) {
        for (var ev : graph.evidence()) {
            if (!ev.family().equals("repository.snapshot")) continue;
            try {
                var raw = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                var repo = new RepositoryIdentity(GraphJson.string(raw.get("repository")));
                var revision = raw.get("revision") instanceof String r && !r.isBlank() ? Optional.of(r) : Optional.<String>empty();
                boolean dirty = Boolean.TRUE.equals(raw.get("dirty"));
                var files = new ArrayList<SnapshotFile>();
                for (var item : GraphJson.array(raw.get("files"))) {
                    var row = GraphJson.object(item);
                    files.add(new SnapshotFile(GraphJson.string(row.get("path")), new ContentDigest(GraphJson.string(row.get("contentDigest")))));
                }
                return RepositorySnapshot.create(repo, revision, dirty, files, List.of());
            } catch (RuntimeException invalid) {
                return null;
            }
        }
        return null;
    }

    private static boolean hasTestContext(KnowledgeGraph graph) {
        for (var ctx : graph.contexts()) {
            if (ctx.analysis().isEmpty()) continue;
            var ev = graph.evidence(ctx.evidence()).filter(e -> e.family().equals("analysis.manifest")).orElse(null);
            if (ev != null && ev.canonicalJson().contains("\"java.source-set\":\"TEST\"")) return true;
        }
        return false;
    }

    private static Optional<ContentDigest> matchReport(KnowledgeGraph baseGraph, PublishedReports.Report baseReport,
                                                       KnowledgeGraph candGraph, List<PublishedReports.Report> candReports,
                                                       List<ContentDigest> candIds) {
        String baseScope = contextScope(baseGraph, baseReport.evaluation().context());
        for (int i = 0; i < candReports.size(); i++) {
            var r = candReports.get(i);
            String scope = contextScope(candGraph, r.evaluation().context());
            if (Objects.equals(baseScope, scope) && r.evaluation().compilation().equals(baseReport.evaluation().compilation()))
                return Optional.of(candIds.get(i));
        }
        return Optional.empty();
    }

    private static String contextScope(KnowledgeGraph graph, String contextId) {
        for (var ctx : graph.contexts()) {
            if (!ctx.identity().equals(contextId) || ctx.analysis().isEmpty()) continue;
            var manifestEv = graph.evidence(ctx.evidence()).filter(e -> e.family().equals("analysis.manifest")).orElse(null);
            if (manifestEv != null) {
                try {
                    var obj = GraphJson.object(GraphJson.read(manifestEv.canonicalJson(), GraphJson.Limits.defaults()));
                    var values = GraphJson.object(GraphJson.object(obj.get("configuration")).get("values"));
                    return "JAVA|" + Objects.toString(values.get("java.module"), "") + "|" + Objects.toString(values.get("java.source-set"), "");
                } catch (RuntimeException ignored) {}
            }
            var springEv = graph.evidence(ctx.evidence()).filter(e -> e.family().equals("spring.context")).orElse(null);
            if (springEv != null) {
                String parent = contextScope(graph, ctx.analysis().orElseThrow().value());
                if (parent != null && parent.startsWith("JAVA|")) return "SPRING|" + parent.substring(5);
            }
        }
        return null;
    }

    private static ContentDigest policySource(PublishedReports.Report report, ContentDigest findingId) {
        return report.evaluation().findings().stream().filter(f -> f.identity().equals(findingId)).findFirst()
                .map(f -> f.bundle().policySource())
                .orElseGet(() -> report.evaluation().findings().stream().findFirst()
                        .map(f -> f.bundle().policySource()).orElse(report.evaluation().compilation()));
    }

    private static void writeRelative(Path root, String relativePath, byte[] bytes) throws IOException {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) throw new IOException("UNSAFE_RELATIVE_PATH");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void deleteQuietly(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }

    private static RepairReanalysis.Result withheld(String reason, ContentDigest proposal, SnapshotIdentity snap) {
        return new RepairReanalysis.Result(RepairReanalysis.VERSION, "WITHHELD", Optional.of(reason), proposal, snap,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }
}
