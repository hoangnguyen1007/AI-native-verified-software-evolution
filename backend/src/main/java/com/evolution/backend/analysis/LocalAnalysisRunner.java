package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.assessment.*;
import com.evolution.analysis.buildmodel.UniversalBuildIngestion;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.input.*;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import com.evolution.analysis.maven.MavenBuildModelProvider;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.readiness.*;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.universal.SourceToSpringPlan;
import com.evolution.backend.query.PublishedReports;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Passive filesystem/local-cache composition over the same providers as evo scan. No target execution or network. */
public final class LocalAnalysisRunner implements AnalysisJobs.Runner {
    private static final ExecutorService POLYGLOT_WORKERS = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
            runnable -> {
                var thread = new Thread(runnable, "evo-polyglot-scan");
                thread.setDaemon(true);
                return thread;
            });
    private interface Progress extends BooleanSupplier {
        String id();
        default AnalysisMode mode() { return AnalysisMode.FULL; }
        void stage(AnalysisJobs.Stage stage);
        void checkpoint();
        boolean beginReuseCheck();
        Optional<AnalysisJobs.Reused> reuse(ContentDigest key);
        void withholdReuse();
    }
    public record PolicyInput(byte[] bytes, Scope scoreScope) {
        public PolicyInput { bytes = bytes.clone(); Objects.requireNonNull(scoreScope); if (bytes.length > 262_144) throw new IllegalArgumentException("Policy limit"); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record SpringInput(Map<UniversalSourceIngestion.SourceSet,SourceToSpringPlan.Scope> scopes,
                              Optional<ConfigurationSpace> space, Optional<ConfigurationAssignment> baseline) {
        public SpringInput { scopes = Map.copyOf(scopes); Objects.requireNonNull(space); Objects.requireNonNull(baseline);
            if (space.isPresent() != baseline.isPresent()) throw new IllegalArgumentException("Space requires exact baseline"); }
        public static SpringInput absent() { return new SpringInput(Map.of(),Optional.empty(),Optional.empty()); }
    }
    /** Paths are supplied by the local operator and never decoded from HTTP. Revision/dirty are caller assertions. */
    public record Input(Path source, RepositoryIdentity repository, Path jdk, int release, Path mavenCache,
                        Optional<String> revision, boolean dirty, boolean includeTests, List<String> exclusions,
                        Optional<PolicyInput> policy, SpringInput spring) {
        public Input {
            source = Objects.requireNonNull(source).toAbsolutePath().normalize(); jdk = Objects.requireNonNull(jdk).toAbsolutePath().normalize();
            mavenCache = Objects.requireNonNull(mavenCache).toAbsolutePath().normalize(); Objects.requireNonNull(repository);
            Objects.requireNonNull(revision); exclusions = List.copyOf(exclusions); Objects.requireNonNull(policy); Objects.requireNonNull(spring);
            if (release < 8 || release > 26) throw new IllegalArgumentException("Invalid target release");
        }
    }
    private record PlatformCacheKey(Path jdk, int release) {}
    private static final int PLATFORM_CACHE_CAPACITY = 6;
    private static final Map<PlatformCacheKey, PlatformSymbolResult> PLATFORM_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<PlatformCacheKey, PlatformSymbolResult> eldest) {
                    return size() > PLATFORM_CACHE_CAPACITY;
                }
            });
    public static PlatformSymbolResult acquireCachedPlatform(Path jdk, int release) {
        return acquirePlatform(jdk, release);
    }
    private static PlatformSymbolResult acquirePlatform(Path jdk, int release) {
        var normalized = jdk.toAbsolutePath().normalize();
        var key = new PlatformCacheKey(normalized, release);
        var cached = PLATFORM_CACHE.get(key);
        if (cached != null) return cached;
        var acquired = new FilesystemJdkPlatformProvider().acquire(normalized, new PlatformSymbolRequest(release, 128, 128_000_000, 512_000_000));
        if (acquired.platform().isPresent()) PLATFORM_CACHE.put(key, acquired);
        return acquired;
    }
    private final Input input;
    private final Path intakeDirectory;
    public LocalAnalysisRunner(Input input) { this(input,input.source().resolve(".evo/acquisition")); }
    public LocalAnalysisRunner(Input input,Path intakeDirectory) { this.input = Objects.requireNonNull(input); this.intakeDirectory=intakeDirectory.toAbsolutePath().normalize(); }
    public AnalysisJobs.Registration registration(String id) {
        return new AnalysisJobs.Registration(new AnalysisJobs.Setup(id,input.repository(),input.release(),input.includeTests(),
                input.policy().map(p -> ContentDigest.sha256(p.bytes())),input.spring().space().map(s -> s.identity().value())),this);
    }
    @Override public AnalysisJobs.RunResult run(AnalysisJobs.Control control) throws Exception {
        var adapter = new Progress() {
            @Override public boolean getAsBoolean() { return control.getAsBoolean(); }
            @Override public String id() { return control.id(); }
            @Override public AnalysisMode mode() { return control.mode(); }
            @Override public void stage(AnalysisJobs.Stage stage) { control.stage(stage); }
            @Override public void checkpoint() { control.checkpoint(); }
            @Override public boolean beginReuseCheck() { return control.beginReuseCheck(); }
            @Override public Optional<AnalysisJobs.Reused> reuse(ContentDigest key) { return control.reuse(key); }
            @Override public void withholdReuse() { control.withholdReuse(); }
        };
        adapter.stage(AnalysisJobs.Stage.ACQUISITION);
        return runAcquired(adapter);
    }
    /** Synchronous passive analysis without registering an AnalysisJobs history entry. */
    public AnalysisJobs.Outcome analyzeDirect(String runId, BooleanSupplier cancelled) throws Exception {
        return analyzeDirect(runId, AnalysisMode.FULL, cancelled);
    }
    public AnalysisJobs.Outcome analyzeDirect(String runId, AnalysisMode mode, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(cancelled);
        var direct = new Progress() {
            @Override public boolean getAsBoolean() { return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted(); }
            @Override public String id() { return runId; }
            @Override public AnalysisMode mode() { return mode; }
            @Override public void stage(AnalysisJobs.Stage stage) { checkpoint(); }
            @Override public void checkpoint() { if (getAsBoolean()) throw new java.util.concurrent.CancellationException(); }
            @Override public boolean beginReuseCheck() { return false; }
            @Override public Optional<AnalysisJobs.Reused> reuse(ContentDigest key) { return Optional.empty(); }
            @Override public void withholdReuse() {}
        };
        return (AnalysisJobs.Outcome) runAcquired(direct);
    }
    AnalysisJobs.RunResult runAcquired(Progress control) throws Exception {
        var source = safe(input.source());
        var excluded = new TreeSet<>(input.exclusions());
        boolean sourceDirExists = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        if (!sourceDirExists && !Files.isRegularFile(intakeDirectory.resolve("metadata/index.sqlite"), LinkOption.NOFOLLOW_LINKS)) {
            var unavailable = new FilesystemRepositoryAcquirer().acquire(source,new RepositoryAcquisitionRequest(input.repository(),input.revision(),input.dirty(),"pom.xml",new RepositoryAcquisitionPolicy(1,1,1,1,1,List.copyOf(excluded))));
            return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.of(),Optional.of(unavailable.identity()),Optional.empty(),inputProblems(unavailable));
        }
        var selection = ScanSelection.plan(source,input.includeTests(),false,List.copyOf(excluded));
        excluded.addAll(selection.excluded());
        var fullExcluded = new TreeSet<>(input.exclusions());
        fullExcluded.add(".git");
        if (intakeDirectory.startsWith(source)) fullExcluded.add(source.relativize(intakeDirectory).toString().replace('\\','/'));
        RepositoryAcquisitionResult acquisition;
        ReadinessPlan readiness;
        boolean verifiedInventory = false;
        try (var intake = new RepositoryIntake(intakeDirectory)) {
            if (!sourceDirExists && intake.inventory().manifest(control.id()).isEmpty()) {
                var unavailable = new FilesystemRepositoryAcquirer().acquire(source,new RepositoryAcquisitionRequest(input.repository(),input.revision(),input.dirty(),"pom.xml",new RepositoryAcquisitionPolicy(1,1,1,1,1,List.copyOf(excluded))));
                return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.of(),Optional.of(unavailable.identity()),Optional.empty(),inputProblems(unavailable));
            }
            var request = new StreamingAcquisitionRequest(input.repository(),input.revision(),input.dirty(),List.copyOf(fullExcluded));
            var receipt = intake.localComplete(source,control.id(),request,control);
            control.checkpoint();
            if (receipt.manifest().isEmpty()) throw new IOException(receipt.reason());
            control.stage(AnalysisJobs.Stage.PLATFORM);
            acquisition = intake.compatibility(control.id(),input.repository(),input.revision(),input.dirty(),input.includeTests(),false,List.copyOf(excluded));
            control.checkpoint();
            if (acquisition.snapshot().isEmpty()) {
                return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.of(),Optional.of(acquisition.identity()),Optional.empty(),inputProblems(acquisition));
            }
            readiness = intake.readiness(control.id(),ReadinessPlanner.Policy.comprehensive(),control,control.mode().isFastArchitecture());
            if (control.beginReuseCheck() && (input.dirty() || input.revision().isPresent())) {
                try { verifiedInventory = !intake.hasUncapturedEntries(control.id()); }
                catch (IOException unavailable) { /* Reuse is optional; keep the normal analysis path. */ }
            }
        }
        return runCapturedWithReadiness(acquisition, readiness, verifiedInventory, control);
    }
    AnalysisJobs.RunResult runCaptured(RepositoryAcquisitionResult acquisition,AnalysisJobs.Control control)throws Exception {
        return runCaptured(acquisition,new Progress() {
            @Override public boolean getAsBoolean() { return control.getAsBoolean(); }
            @Override public String id() { return control.id(); }
            @Override public AnalysisMode mode() { return control.mode(); }
            @Override public void stage(AnalysisJobs.Stage stage) { control.stage(stage); }
            @Override public void checkpoint() { control.checkpoint(); }
            @Override public boolean beginReuseCheck() { return control.beginReuseCheck(); }
            @Override public Optional<AnalysisJobs.Reused> reuse(ContentDigest key) { return control.reuse(key); }
            @Override public void withholdReuse() { control.withholdReuse(); }
        });
    }
    AnalysisJobs.RunResult runCaptured(RepositoryAcquisitionResult acquisition,Progress control)throws Exception {
        ReadinessPlan readiness;
        boolean verifiedInventory = false;
        try(var intake=new RepositoryIntake(intakeDirectory)){
            control.stage(AnalysisJobs.Stage.PLATFORM);
            readiness=intake.readiness(control.id(),ReadinessPlanner.Policy.comprehensive(),control,control.mode().isFastArchitecture());
            if (control.beginReuseCheck() && (input.dirty() || input.revision().isPresent())) {
                try { verifiedInventory = !intake.hasUncapturedEntries(control.id()); }
                catch (IOException unavailable) { /* Reuse is optional; keep the normal analysis path. */ }
            }
        }
        return runCapturedWithReadiness(acquisition, readiness, verifiedInventory, control);
    }
    private AnalysisJobs.RunResult runCapturedWithReadiness(
            RepositoryAcquisitionResult acquisition,
            ReadinessPlan readiness,
            boolean verifiedInventory,
            Progress control) throws Exception {
        var cache=safe(input.mavenCache());
        boolean fast = control.mode().isFastArchitecture();
        var platform = acquirePlatform(safe(input.jdk()), input.release());
        control.checkpoint();
        var models = MavenScan.models(acquisition,cache,List.of());
        var defaults = UniversalBuildIngestion.Policy.defaults();
        var buildPolicy=new UniversalBuildIngestion.Policy(Optional.of(input.release()),Optional.of(input.release()),Optional.of("UTF-8"),32_768,defaults.maxCharacters(),defaults.maxTokens(),128);
        var decoded=new RepositoryInputDecoder.Prepared(acquisition,new RepositoryInputDecoder.Policy("UTF-8",500_000,100_000_000,fast));
        var gradleModel=GradleScan.model(decoded,buildPolicy,Map.of());
        var requiredReleases=new TreeSet<>(MavenScan.platformReleases(models));requiredReleases.addAll(GradleScan.platformReleases(gradleModel));
        var platformViews=new TreeMap<Integer,PlatformSymbolResult>();platformViews.put(input.release(),platform);
        var additionalPlatforms=new ArrayList<PlatformSymbolResult>();
        for(int target:requiredReleases)if(!platformViews.containsKey(target)) {
            var view=acquirePlatform(safe(input.jdk()), target);
            platformViews.put(target,view);additionalPlatforms.add(view);
        }
        var availablePlatforms=new TreeMap<Integer,com.evolution.analysis.frontend.PlatformInput>();platformViews.forEach((target,view)->view.platform().ifPresent(value->availablePlatforms.put(target,value)));
        var platformLedgers=additionalPlatforms.isEmpty()?List.<EvidenceAcquisitionLedger>of():List.of(CapabilityGapNormalizer.normalize(
                EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity()),EvidenceNormalizationInput.builder().platformResults(additionalPlatforms).build()));
        var depPolicy=new com.evolution.analysis.dependency.DependencyAcquisitionPolicy(1024,64_000_000,256_000_000,64,List.of(),5000,15000,0);
        var mavenBase=MavenScan.inputs(acquisition,models,cache,platform.platform(),depPolicy,availablePlatforms);
        var dependencies=GradleScan.augment(mavenBase,acquisition,gradleModel,cache,platform.platform(),depPolicy,availablePlatforms);
        var frontend = new JavaParserFrontend();
        var guarded = new com.evolution.analysis.frontend.ConcurrentSemanticFrontend() {
            @Override public long estimatedWorkingBytes(com.evolution.analysis.frontend.FrontendRequest request) {
                return frontend.estimatedWorkingBytes(request);
            }
            @Override public void releaseCompletedContexts() { frontend.releaseCompletedContexts(); }
            @Override public com.evolution.analysis.frontend.FrontendResult analyze(com.evolution.analysis.frontend.FrontendRequest request) {
                control.checkpoint();
                var result=frontend.analyze(request);
                control.checkpoint();
                return result;
            }
        };
        var policy = new UniversalArchitecturePipeline.Policy(
                buildPolicy,decoded.policy(),guarded,JavaParserFrontend.PROVIDER,
                dependencies.resolutions(),platform.platform(),Optional.of(new MavenBuildModelProvider()),dependencies.externalPoms(),MavenScan.POLICY,
                100_000,1_000,UniversalArchitecturePipeline.TruthEvaluationMode.AUTO,input.spring().scopes(),input.spring().space(),input.spring().baseline(),
                com.evolution.analysis.spring.truth.TruthRegionEvaluation.Limits.conservative())
                .withReadiness(readiness.identity())
                .withPrecomputedBuild(dependencies.externalPoms().isEmpty() ? gradleModel.orElse(null) : null);
        if (fast) {
            policy = policy.withAnalysisOptions(Map.of("analysis.profile", "FAST_ARCHITECTURE"));
        }
        var allPlatforms=new ArrayList<PlatformSymbolResult>();allPlatforms.add(platform);allPlatforms.addAll(additionalPlatforms);
        var reuseKey = verifiedInventory ? WorkbenchReuseInputs.key(acquisition, readiness, allPlatforms,
                models.stream().map(model -> model.identity()).toList(), gradleModel.map(model -> model.identity()),
                dependencies, policy, input.policy(), control) : Optional.<ContentDigest>empty();
        if (reuseKey.isPresent()) {
            var reusable = control.reuse(reuseKey.orElseThrow());
            if (reusable.isPresent()) return reusable.orElseThrow();
        }
        control.stage(AnalysisJobs.Stage.ANALYSIS);
        var verifiedPolyglotBytes = extractPolyglotBytes(acquisition);
        var failedFlag = new AtomicBoolean(false);
        BooleanSupplier subCancelled = () -> failedFlag.get() || control.getAsBoolean();
        var snapshotId = acquisition.snapshot().orElseThrow().identity();
        var finalPolicy = policy;
        CompletableFuture<PolyglotScan.Result> polyglotFuture = CompletableFuture.supplyAsync(() -> {
            try (var intake = new RepositoryIntake(intakeDirectory, true)) {
                return new PolyglotScan().run(
                        control.id(), intake, readiness, EvidenceContext.forSnapshot(snapshotId),
                        Optional.empty(), intakeDirectory.resolve("semantic-work"), subCancelled,
                        digest->{var file=verifiedPolyglotBytes.get(digest);return file==null?null:file.bytes();},
                        fast,fast?intake.installedNodeDependencies(control.id()):path->false);
            } catch (Exception failure) {
                failedFlag.set(true);
                throw new CompletionException(failure);
            }
        }, POLYGLOT_WORKERS);

        UniversalArchitecturePipeline.DetailedResult detailed;
        PolyglotScan.Result polyglot;
        try {
            detailed = new UniversalArchitecturePipeline().runWithEvidence(decoded, finalPolicy);
            control.checkpoint();
            polyglot = polyglotFuture.join();
        } catch (CompletionException asyncFailure) {
            failedFlag.set(true);
            Throwable cause = asyncFailure.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw asyncFailure;
        } catch (Throwable primaryFailure) {
            failedFlag.set(true);
            polyglotFuture.cancel(true);
            throw primaryFailure;
        }
        control.checkpoint();
        var gaps = new TreeSet<>(detailed.report().gaps()); gaps.addAll(dependencies.gaps());
        gaps.addAll(readiness.gaps(acquisition.snapshot().orElseThrow().identity()));
        gaps.addAll(polyglot.gaps());
        platformLedgers.forEach(ledger->gaps.addAll(ledger.gaps()));
        gaps.addAll(CapabilityGapNormalizer.normalize(EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity()),
                EvidenceNormalizationInput.builder().repositoryAcquisitions(List.of(acquisition)).platformResults(allPlatforms).build()).gaps());
        if (detailed.graphInput().isEmpty()) return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.copyOf(gaps),Optional.of(acquisition.identity()),Optional.of(detailed.report().identity()),inputProblems(acquisition));
        control.stage(AnalysisJobs.Stage.GRAPH);
        var captured = detailed.graphInput().orElseThrow(); gaps.addAll(captured.gaps());
        var graphLedgers=new ArrayList<>(dependencies.ledgers());graphLedgers.addAll(platformLedgers);
        var finalPolyglot = polyglot;
        var graph = new CanonicalGraphBuilder().build(
                new CanonicalGraphInput(captured.repository(),captured.build(),captured.ingestion(),captured.spring(),captured.issues(),List.copyOf(gaps),captured.acquisition(),captured.javaEvidence()),
                graphLedgers,
                store->new PolyglotScan().project(finalPolyglot,store,control),
                fast);
        control.checkpoint();
        var reports = new ArrayList<PublishedReports.Report>();
        if (input.policy().isPresent()) {
            control.stage(AnalysisJobs.Stage.POLICY); var selected = input.policy().orElseThrow();
            var compiled = new PolicyCompiler().compile(selected.bytes(),EvidenceContext.forSnapshot(graph.snapshot()),PolicyCompiler.Limits.defaults());
            gaps.addAll(compiled.gaps());
            var guard = ArchitectureQueryService.operationalGuard(control,0,8_000_000);
            var queries = new ArchitectureMetricsService(graph,ArchitectureMetric.Limits.defaults(),guard,Optional.empty(),List.of()).queries();
            for (var context : graph.contexts()) {
                if (context.analysis().isEmpty()) continue;
                control.checkpoint();
                var plan = new PolicyGraphMapper().bind(compiled,queries,context.identity(),PolicyGraphMapper.Limits.defaults());
                var evaluation = new PolicyEvaluator().evaluate(compiled,plan,queries,PolicyEvaluation.Limits.defaults());
                var metrics = new PolicyMetricsService().compute(compiled,plan,evaluation,queries,PolicyMetrics.Limits.defaults());
                var score = new ArchitectureAssessmentService().assess(metrics,evaluation,queries,
                        Optional.of(ArchitectureScoreModel.candidate(selected.scoreScope())),plan.limits().query(),
                        ArchitectureMetric.Limits.defaults(),ArchitectureAssessment.Limits.defaults());
                reports.add(new PublishedReports.Report(evaluation,Optional.of(metrics),Optional.of(score)));
                gaps.addAll(plan.gaps()); gaps.addAll(evaluation.gaps()); gaps.addAll(metrics.gaps()); gaps.addAll(score.gaps());
            }
        }
        control.checkpoint();
        var completion = switch (detailed.report().status()) {
            case COMPLETE -> gaps.isEmpty() ? AnalysisJobs.Completion.COMPLETE : AnalysisJobs.Completion.PARTIAL;
            case EMPTY_OR_NON_JAVA -> AnalysisJobs.Completion.EMPTY;
            case TERMINAL_FAILURE, CANCELLED -> AnalysisJobs.Completion.FAILED;
            case PARTIAL -> AnalysisJobs.Completion.PARTIAL;
        };
        if (reuseKey.isPresent() && (!WorkbenchReuseInputs.stableGaps(graph, gaps, polyglot.syntax())
                || !WorkbenchReuseInputs.stillCurrent(allPlatforms, dependencies, control))) control.withholdReuse();
        return new AnalysisJobs.Outcome(completion,Optional.of(graph),reports,List.copyOf(gaps),Optional.of(acquisition.identity()),Optional.of(detailed.report().identity()),inputProblems(acquisition));
    }
    private static List<AnalysisJobs.InputProblem> inputProblems(RepositoryAcquisitionResult acquisition) {
        return acquisition.problems().stream().map(p -> new AnalysisJobs.InputProblem(acquisition.provider(),acquisition.identity(),p)).toList();
    }
    private static Map<ContentDigest, com.evolution.analysis.acquisition.AcquiredFile> extractPolyglotBytes(RepositoryAcquisitionResult acquisition) {
        var map = new HashMap<ContentDigest, com.evolution.analysis.acquisition.AcquiredFile>();
        for (var file : acquisition.files()) {
            String p = file.path();
            if (!p.endsWith(".java") || p.endsWith("Grpc.java") || p.contains("grpc")) {
                map.putIfAbsent(file.contentDigest(), file);
            }
        }
        return map;
    }
    static Path safe(Path input) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        for (Path parent = path; parent != null; parent = parent.getParent()) if (Files.isSymbolicLink(parent)) throw new IOException("UNSAFE_PATH");
        return path;
    }
}
