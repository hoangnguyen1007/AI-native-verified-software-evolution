package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.assessment.*;
import com.evolution.analysis.buildmodel.UniversalBuildIngestion;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.execution.nativebuild.NativeBuildCoordinator;
import com.evolution.analysis.execution.nativebuild.NativeBuildProfile;
import com.evolution.analysis.execution.nativebuild.NativeBuildRegistrations;
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

/**
 * Passive composition over the same providers as evo scan. The runner never
 * executes target lifecycles.
 */
public final class LocalAnalysisRunner implements AnalysisJobs.Runner {
    private static final System.Logger LOG = System.getLogger(LocalAnalysisRunner.class.getName());
    private static final VersionedIdentifier POLICY_EVALUATION_PROVIDER = new VersionedIdentifier("policy.evaluation",
            "local-runner-v1");
    static final DependencyAcquisitionPolicy CACHE_ONLY = new DependencyAcquisitionPolicy(4096, 64_000_000,
            MavenScan.DEFAULT_DEPENDENCY_TOTAL_BYTES, 64, List.of(), 5000, 15000, 0, 8);
    private static final ExecutorService POLYGLOT_WORKERS = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
            runnable -> {
                var thread = new Thread(runnable, "evo-polyglot-scan");
                thread.setDaemon(true);
                return thread;
            });
    private static final ExecutorService PREFETCH_WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final long PROGRESS_THROTTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final int MAX_STAGE2_PREVIEW_GRAPHS = 16;
    private static final Object MODULE_CACHE_CONFIG_LOCK = new Object();
    private static int activeIncrementalModuleCacheRuns = 0;

    /**
     * Long-lived cross-job incremental caches shared across runs targeting the same intake store.
     * Operational cache state never changes canonical output digests when inputs match.
     */
    public static final class SharedCaches {
        private record LastPublication(String snapshotId, ContentDigest graphId, String jobId) {
        }

        private final BuildDeltaCache buildDelta;
        private final IncrementalSpringCache springCache;
        private final IncrementalGraphProjector graphProjector;
        private final Map<RepositoryIdentity, LastPublication> lastPublishedByRepo = Collections
                .synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<RepositoryIdentity, LastPublication> eldest) {
                        return size() > 64;
                    }
                });

        public SharedCaches(Path intakeDirectory) {
            Objects.requireNonNull(intakeDirectory);
            Path cacheRoot = intakeDirectory.toAbsolutePath().normalize().resolve("incremental-cache");
            this.buildDelta = new BuildDeltaCache(cacheRoot);
            this.springCache = new IncrementalSpringCache();
            this.graphProjector = new IncrementalGraphProjector(cacheRoot.resolve("graph-templates"));
        }

        public BuildDeltaCache buildDelta() {
            return buildDelta;
        }

        public IncrementalSpringCache springCache() {
            return springCache;
        }

        public IncrementalGraphProjector graphProjector() {
            return graphProjector;
        }

        Optional<LastPublication> lastPublished(RepositoryIdentity repository) {
            return Optional.ofNullable(lastPublishedByRepo.get(repository));
        }

        void recordPublished(RepositoryIdentity repository, SnapshotIdentity snapshot, ContentDigest graphId,
                String jobId) {
            if (repository != null && snapshot != null && graphId != null && jobId != null) {
                lastPublishedByRepo.put(repository, new LastPublication(snapshot.value(), graphId, jobId));
            }
        }
    }

    private static final int SHARED_CACHES_CAPACITY = 16;
    private static final Map<Path, SharedCaches> SHARED_CACHES_BY_INTAKE = Collections
            .synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, SharedCaches> eldest) {
                    return size() > SHARED_CACHES_CAPACITY;
                }
            });

    public static SharedCaches sharedCaches(Path intakeDirectory) {
        Path normalized = Objects.requireNonNull(intakeDirectory).toAbsolutePath().normalize();
        synchronized (SHARED_CACHES_BY_INTAKE) {
            return SHARED_CACHES_BY_INTAKE.computeIfAbsent(normalized, SharedCaches::new);
        }
    }

    private record AcquisitionDeltaContext(
            Optional<String> baseSnapshotId,
            Optional<String> baseGraphId,
            Optional<String> baseJobId,
            boolean incrementalUsed,
            String fallbackReason,
            Optional<StreamingRepositoryAcquirer.FileDelta> fileDelta) {
        static AcquisitionDeltaContext cold(String reason) {
            return new AcquisitionDeltaContext(Optional.empty(), Optional.empty(), Optional.empty(),
                    false, reason == null ? "" : reason, Optional.empty());
        }
    }

    private interface Progress extends BooleanSupplier {
        String id();

        default AnalysisMode mode() {
            return AnalysisMode.FULL;
        }

        default com.evolution.analysis.polyglot.ServiceArchitectureProjection.ProjectionProfile projectionProfile() {
            return configuredProjectionProfile();
        }

        default void recordAcquisitionTelemetry(long acquisitionAttempts, long pomAttempts) {
        }

        void stage(AnalysisJobs.Stage stage);

        void checkpoint();

        boolean beginReuseCheck();

        Optional<AnalysisJobs.Reused> reuse(ContentDigest key);

        void withholdReuse();

        default void recordCacheMetrics(Map<String, Long> metrics) {
        }

        default void reportProgress(int done, int total, String message) {
        }

        default void registerModules(List<String> modulePaths) {
        }

        default void reportModuleUpdate(String module, String status, int filesDone, int filesTotal,
                long elapsedMillis, Optional<String> contextId, Optional<String> reason) {
        }

        default Optional<AnalysisJobs.PreviewPublication> publishPreview(
                KnowledgeGraph previewGraph, AnalysisJobs.TruthTier truthTier,
                int completedContexts, int totalContexts, List<String> pendingModules) {
            return Optional.empty();
        }

        default void recordIncrementalMetrics(AnalysisJobs.IncrementalMetrics metrics) {
        }

        default boolean supportsProgressivePreviews() {
            return false;
        }
    }

    public record PolicyInput(byte[] bytes, Scope scoreScope) {
        public PolicyInput {
            bytes = bytes.clone();
            Objects.requireNonNull(scoreScope);
            if (bytes.length > 262_144)
                throw new IllegalArgumentException("Policy limit");
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record SpringInput(Map<UniversalSourceIngestion.SourceSet, SourceToSpringPlan.Scope> scopes,
            Optional<ConfigurationSpace> space, Optional<ConfigurationAssignment> baseline,
            boolean autoMultiConfigEnabled) {
        public SpringInput(Map<UniversalSourceIngestion.SourceSet, SourceToSpringPlan.Scope> scopes,
                Optional<ConfigurationSpace> space, Optional<ConfigurationAssignment> baseline) {
            this(scopes, space, baseline, false);
        }

        public SpringInput {
            scopes = Map.copyOf(scopes);
            Objects.requireNonNull(space);
            Objects.requireNonNull(baseline);
            if (space.isPresent() != baseline.isPresent())
                throw new IllegalArgumentException("Space requires exact baseline");
        }

        public static SpringInput absent() {
            return new SpringInput(Map.of(), Optional.empty(), Optional.empty(), false);
        }

        public static SpringInput autoMultiConfig() {
            return new SpringInput(Map.of(), Optional.empty(), Optional.empty(), true);
        }
    }

    /**
     * Paths are supplied by the local operator and never decoded from HTTP.
     * Revision/dirty are caller assertions.
     */
    public record Input(Path source, RepositoryIdentity repository, Path jdk, int release, Path mavenCache,
            Optional<String> revision, boolean dirty, boolean includeTests, List<String> exclusions,
            Optional<PolicyInput> policy, SpringInput spring) {
        public Input {
            source = Objects.requireNonNull(source).toAbsolutePath().normalize();
            jdk = Objects.requireNonNull(jdk).toAbsolutePath().normalize();
            mavenCache = Objects.requireNonNull(mavenCache).toAbsolutePath().normalize();
            Objects.requireNonNull(repository);
            Objects.requireNonNull(revision);
            exclusions = List.copyOf(exclusions);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(spring);
            if (release < 8 || release > 26)
                throw new IllegalArgumentException("Invalid target release");
        }
    }

    private record PlatformCacheKey(Path jdk, int release) {
    }

    private static final int PLATFORM_CACHE_CAPACITY = 6;
    private static final Map<PlatformCacheKey, PlatformSymbolResult> PLATFORM_CACHE = Collections
            .synchronizedMap(new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<PlatformCacheKey, PlatformSymbolResult> eldest) {
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
        if (cached != null)
            return cached;
        var acquired = new FilesystemJdkPlatformProvider().acquire(normalized,
                new PlatformSymbolRequest(release, 128, 128_000_000, 512_000_000));
        if (acquired.platform().isPresent())
            PLATFORM_CACHE.put(key, acquired);
        return acquired;
    }

    public record NativeExecutionConfig(
            String buildMode,
            String buildProfile,
            Optional<Path> javaCapture,
            Optional<Path> semanticStore,
            Optional<ContentDigest> semanticPlan,
            Optional<ContentDigest> semanticPolicy,
            Optional<Path> semanticAuthority,
            Optional<Path> androidSdk) {
        public NativeExecutionConfig {
            Objects.requireNonNull(buildMode);
            Objects.requireNonNull(buildProfile);
            Objects.requireNonNull(javaCapture);
            Objects.requireNonNull(semanticStore);
            Objects.requireNonNull(semanticPlan);
            Objects.requireNonNull(semanticPolicy);
            Objects.requireNonNull(semanticAuthority);
            Objects.requireNonNull(androidSdk);
            if (!Set.of("auto", "required", "off").contains(buildMode)) {
                throw new IllegalArgumentException("Invalid build mode: " + buildMode);
            }
            if ((semanticStore.isPresent() != semanticPlan.isPresent())
                    || (semanticStore.isEmpty() && (semanticPolicy.isPresent() || semanticAuthority.isPresent()))
                    || (semanticStore.isPresent() && semanticPolicy.isEmpty() && semanticAuthority.isEmpty())) {
                throw new IllegalArgumentException("Incomplete semantic capture configuration");
            }
        }

        public static NativeExecutionConfig fromEnvironment() {
            String mode = System.getProperty("evo.workbench.buildMode", "auto");
            if (!Set.of("auto", "required", "off").contains(mode))
                mode = "auto";
            String profile = System.getProperty("evo.workbench.buildProfile", "default");
            Optional<Path> javaCapture = optionalPathProperty("evo.workbench.javaCapture");
            Optional<Path> semanticStore = optionalPathProperty("evo.workbench.semanticStore");
            Optional<ContentDigest> semanticPlan = optionalDigestProperty("evo.workbench.semanticPlan");
            Optional<ContentDigest> semanticPolicy = optionalDigestProperty("evo.workbench.semanticPolicy");
            Optional<Path> semanticAuthority = optionalPathProperty("evo.workbench.semanticAuthority");
            Optional<Path> androidSdk = optionalPathProperty("evo.android.sdk").or(GradleScan::discoverAndroidSdk);
            if (semanticStore.isEmpty() || semanticPlan.isEmpty()
                    || (semanticPolicy.isEmpty() && semanticAuthority.isEmpty())) {
                semanticStore = Optional.empty();
                semanticPlan = Optional.empty();
                semanticPolicy = Optional.empty();
                semanticAuthority = Optional.empty();
            }
            return new NativeExecutionConfig(mode, profile, javaCapture, semanticStore, semanticPlan, semanticPolicy,
                    semanticAuthority, androidSdk);
        }

        private static Optional<Path> optionalPathProperty(String name) {
            String value = System.getProperty(name);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value.strip()));
        }

        private static Optional<ContentDigest> optionalDigestProperty(String name) {
            String value = System.getProperty(name);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(new ContentDigest(value.strip()));
        }
    }

    private final Input input;
    private final Path intakeDirectory;
    private final Optional<DependencyAcquisitionPolicy> artifactPolicy;
    private final NativeExecutionConfig nativeConfig;
    private final Optional<Path> graphStoreDirectory;
    private final SharedCaches sharedCaches;

    public LocalAnalysisRunner(Input input) {
        this(input, input.source().resolve(".evo/acquisition"));
    }

    public LocalAnalysisRunner(Input input, Path intakeDirectory) {
        this(input, intakeDirectory, Optional.empty());
    }

    LocalAnalysisRunner(Input input, Path intakeDirectory, Optional<DependencyAcquisitionPolicy> artifactPolicy) {
        this(input, intakeDirectory, artifactPolicy, NativeExecutionConfig.fromEnvironment(), Optional.empty());
    }

    public LocalAnalysisRunner(Input input, Path intakeDirectory, Optional<DependencyAcquisitionPolicy> artifactPolicy,
            NativeExecutionConfig nativeConfig) {
        this(input, intakeDirectory, artifactPolicy, nativeConfig, Optional.empty());
    }

    public LocalAnalysisRunner(Input input, Path intakeDirectory, Optional<DependencyAcquisitionPolicy> artifactPolicy,
            NativeExecutionConfig nativeConfig, Optional<Path> graphStoreDirectory) {
        this(input, intakeDirectory, artifactPolicy, nativeConfig, graphStoreDirectory,
                sharedCaches(intakeDirectory));
    }

    public LocalAnalysisRunner(Input input, Path intakeDirectory, Optional<DependencyAcquisitionPolicy> artifactPolicy,
            NativeExecutionConfig nativeConfig, Optional<Path> graphStoreDirectory, SharedCaches sharedCaches) {
        this.input = Objects.requireNonNull(input);
        this.intakeDirectory = intakeDirectory.toAbsolutePath().normalize();
        this.artifactPolicy = Objects.requireNonNull(artifactPolicy);
        this.nativeConfig = Objects.requireNonNull(nativeConfig);
        this.graphStoreDirectory = Objects.requireNonNull(graphStoreDirectory).map(p -> p.toAbsolutePath().normalize());
        this.sharedCaches = Objects.requireNonNull(sharedCaches);
    }

    public SharedCaches sharedCaches() {
        return sharedCaches;
    }

    public AnalysisJobs.Registration registration(String id) {
        return new AnalysisJobs.Registration(
                new AnalysisJobs.Setup(id, input.repository(), input.release(), input.includeTests(),
                        input.policy().map(p -> ContentDigest.sha256(p.bytes())),
                        input.spring().space().map(s -> s.identity().value()),
                        artifactPolicy.map(DependencyAcquisitionPolicy::identity)),
                this);
    }

    private static Progress adaptControl(AnalysisJobs.Control control) {
        return new Progress() {
            @Override
            public boolean getAsBoolean() {
                return control.getAsBoolean();
            }

            @Override
            public String id() {
                return control.id();
            }

            @Override
            public AnalysisMode mode() {
                return control.mode();
            }

            @Override
            public com.evolution.analysis.polyglot.ServiceArchitectureProjection.ProjectionProfile projectionProfile() {
                return parseProjectionProfile(control.projectionProfile());
            }

            @Override
            public void recordAcquisitionTelemetry(long acquisitionAttempts, long pomAttempts) {
                control.recordAcquisitionTelemetry(acquisitionAttempts, pomAttempts);
            }

            @Override
            public void stage(AnalysisJobs.Stage stage) {
                control.stage(stage);
            }

            @Override
            public void checkpoint() {
                control.checkpoint();
            }

            @Override
            public boolean beginReuseCheck() {
                return control.beginReuseCheck();
            }

            @Override
            public Optional<AnalysisJobs.Reused> reuse(ContentDigest key) {
                return control.reuse(key);
            }

            @Override
            public void withholdReuse() {
                control.withholdReuse();
            }

            @Override
            public void recordCacheMetrics(Map<String, Long> metrics) {
                control.recordCacheMetrics(metrics);
            }

            @Override
            public void reportProgress(int done, int total, String message) {
                control.reportProgress(done, total, message);
            }

            @Override
            public void registerModules(List<String> modulePaths) {
                control.registerModules(modulePaths);
            }

            @Override
            public void reportModuleUpdate(String module, String status, int filesDone, int filesTotal,
                    long elapsedMillis, Optional<String> contextId, Optional<String> reason) {
                control.reportModuleUpdate(module, status, filesDone, filesTotal, elapsedMillis, contextId, reason);
            }

            @Override
            public Optional<AnalysisJobs.PreviewPublication> publishPreview(
                    KnowledgeGraph previewGraph, AnalysisJobs.TruthTier truthTier,
                    int completedContexts, int totalContexts, List<String> pendingModules) {
                return Optional.of(control.publishPreview(previewGraph, truthTier,
                        completedContexts, totalContexts, pendingModules));
            }

            @Override
            public void recordIncrementalMetrics(AnalysisJobs.IncrementalMetrics metrics) {
                control.recordIncrementalMetrics(metrics);
            }

            @Override
            public boolean supportsProgressivePreviews() {
                return true;
            }
        };
    }

    @Override
    public AnalysisJobs.RunResult run(AnalysisJobs.Control control) throws Exception {
        var adapter = adaptControl(control);
        adapter.stage(AnalysisJobs.Stage.ACQUISITION);
        return runAcquired(adapter);
    }

    /**
     * Synchronous passive analysis without registering an AnalysisJobs history
     * entry.
     */
    public AnalysisJobs.Outcome analyzeDirect(String runId, BooleanSupplier cancelled) throws Exception {
        return analyzeDirect(runId, AnalysisMode.FULL, cancelled);
    }

    public AnalysisJobs.Outcome analyzeDirect(String runId, AnalysisMode mode, BooleanSupplier cancelled)
            throws Exception {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(cancelled);
        var direct = new Progress() {
            @Override
            public boolean getAsBoolean() {
                return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
            }

            @Override
            public String id() {
                return runId;
            }

            @Override
            public AnalysisMode mode() {
                return mode;
            }

            @Override
            public void stage(AnalysisJobs.Stage stage) {
                checkpoint();
            }

            @Override
            public void checkpoint() {
                if (getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
            }

            @Override
            public boolean beginReuseCheck() {
                return false;
            }

            @Override
            public Optional<AnalysisJobs.Reused> reuse(ContentDigest key) {
                return Optional.empty();
            }

            @Override
            public void withholdReuse() {
            }
        };
        return (AnalysisJobs.Outcome) runAcquired(direct);
    }

    AnalysisJobs.RunResult runAcquired(Progress control) throws Exception {
        try (var artifactSession = VerifiedArtifactSession.open();
             var ignoredResolutionSession = JavaParserFrontend.openVerificationSession()) {
            return runAcquiredInSession(control, artifactSession);
        }
    }

    private AutoCloseable enterModuleCacheScope(boolean incrementalEnabled) {
        synchronized (MODULE_CACHE_CONFIG_LOCK) {
            if (incrementalEnabled) {
                activeIncrementalModuleCacheRuns++;
                JavaParserFrontend.configureModuleCacheDirectory(
                        intakeDirectory.resolve("incremental-cache").resolve("javaparser-modules"));
            } else if (activeIncrementalModuleCacheRuns == 0) {
                JavaParserFrontend.configureModuleCacheDirectory(null);
            }
        }
        return () -> {
            if (incrementalEnabled) {
                synchronized (MODULE_CACHE_CONFIG_LOCK) {
                    activeIncrementalModuleCacheRuns = Math.max(0, activeIncrementalModuleCacheRuns - 1);
                }
            }
        };
    }

    private AnalysisJobs.RunResult runAcquiredInSession(Progress control, VerifiedArtifactSession artifactSession)
            throws Exception {
        boolean incrementalEnabled = !Boolean.getBoolean("evo.incremental.disabled");
        try (var ignoredCacheScope = enterModuleCacheScope(incrementalEnabled)) {
            return runAcquiredInScopedCache(control, artifactSession, incrementalEnabled);
        }
    }

    private AnalysisJobs.RunResult runAcquiredInScopedCache(Progress control, VerifiedArtifactSession artifactSession,
            boolean incrementalEnabled) throws Exception {
        control.reportProgress(0, 1, "Scanning repository files");
        var source = safe(input.source());
        var excluded = new TreeSet<>(input.exclusions());
        boolean sourceDirExists = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        if (!sourceDirExists
                && !Files.isRegularFile(intakeDirectory.resolve("metadata/index.sqlite"), LinkOption.NOFOLLOW_LINKS)) {
            var unavailable = new FilesystemRepositoryAcquirer().acquire(source,
                    new RepositoryAcquisitionRequest(input.repository(), input.revision(), input.dirty(), "pom.xml",
                            new RepositoryAcquisitionPolicy(1, 1, 1, 1, 1, List.copyOf(excluded))));
            return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED, Optional.empty(), List.of(), List.of(),
                    Optional.of(unavailable.identity()), Optional.empty(), inputProblems(unavailable));
        }
        var selection = ScanSelection.plan(source, input.includeTests(), false, List.copyOf(excluded));
        excluded.addAll(selection.excluded());
        var fullExcluded = new TreeSet<>(input.exclusions());
        fullExcluded.add(".git");
        if (intakeDirectory.startsWith(source))
            fullExcluded.add(source.relativize(intakeDirectory).toString().replace('\\', '/'));
        RepositoryAcquisitionResult acquisition;
        ReadinessPlan readiness;
        boolean verifiedInventory = false;
        AcquisitionDeltaContext deltaCtx = AcquisitionDeltaContext.cold("NO_PRIOR_SNAPSHOT");
        try (var intake = new RepositoryIntake(intakeDirectory)) {
            if (!sourceDirExists && intake.inventory().manifest(control.id()).isEmpty()) {
                var unavailable = new FilesystemRepositoryAcquirer().acquire(source,
                        new RepositoryAcquisitionRequest(input.repository(), input.revision(), input.dirty(), "pom.xml",
                                new RepositoryAcquisitionPolicy(1, 1, 1, 1, 1, List.copyOf(excluded))));
                return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED, Optional.empty(), List.of(), List.of(),
                        Optional.of(unavailable.identity()), Optional.empty(), inputProblems(unavailable));
            }
            var request = new StreamingAcquisitionRequest(input.repository(), input.revision(), input.dirty(),
                    List.copyOf(fullExcluded));
            var priorPublished = sharedCaches.lastPublished(input.repository());
            Optional<SqliteInventoryStore.SealedHead> priorHead = incrementalEnabled
                    ? intake.latestSealed(input.repository(), request.identity())
                    : Optional.empty();
            Optional<String> baseRevision = priorHead.map(SqliteInventoryStore.SealedHead::revision);
            Optional<String> baseSnapshotId = priorPublished.map(SharedCaches.LastPublication::snapshotId)
                    .or(() -> priorHead.flatMap(SqliteInventoryStore.SealedHead::compatibleV1Snapshot)
                            .map(SnapshotIdentity::value))
                    .or(() -> priorHead.map(h -> SnapshotIdentity.from(input.repository(), h.inventory()).value()));
            Optional<String> baseGraphId = priorPublished.map(p -> p.graphId().value());
            Optional<String> baseJobId = priorPublished.map(SharedCaches.LastPublication::jobId);

            StreamingRepositoryAcquirer.Receipt receipt;
            if (incrementalEnabled) {
                boolean verifyAll = !Boolean.getBoolean("evo.incremental.trustStatCache");
                var inc = intake.localCompleteIncremental(source, control.id(), baseRevision, request, verifyAll, control);
                receipt = inc.receipt();
                deltaCtx = new AcquisitionDeltaContext(baseSnapshotId, baseGraphId, baseJobId,
                        inc.incrementalUsed(), inc.fallbackReason(), inc.delta());
            } else {
                receipt = intake.localComplete(source, control.id(), request, control);
                deltaCtx = new AcquisitionDeltaContext(Optional.empty(), Optional.empty(), Optional.empty(),
                        false, "INCREMENTAL_DISABLED", Optional.empty());
            }
            control.checkpoint();
            if (receipt.manifest().isEmpty())
                throw new IOException(receipt.reason());
            long capturedFiles = receipt.manifest().orElseThrow().files();
            int capturedCount = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, capturedFiles));
            control.reportProgress(capturedCount, capturedCount,
                    deltaCtx.incrementalUsed() && deltaCtx.fileDelta().isPresent()
                            ? "Incremental capture complete (" + deltaCtx.fileDelta().orElseThrow().totalChanged()
                                    + " changed, " + deltaCtx.fileDelta().orElseThrow().unchangedFiles() + " reused)"
                            : "Captured " + capturedFiles + " repository files");
            control.stage(AnalysisJobs.Stage.PLATFORM);
            readiness = intake.readinessIncremental(control.id(),
                    incrementalEnabled ? deltaCtx.fileDelta() : Optional.empty(),
                    ReadinessPlanner.Policy.comprehensive(), control, control.mode().isFastArchitecture());
            if (nativeConfig.javaCapture().isEmpty() && !"off".equals(nativeConfig.buildMode())) {
                var nativeBuild = runNativeBuild(nativeConfig.buildProfile(), source, readiness, control.id(), intake,
                        control);
                if ("required".equals(nativeConfig.buildMode())
                        && !Set.of("BUILT", "NOT_APPLICABLE").contains(nativeBuild.status())) {
                    var snapshot = SnapshotIdentity.from(input.repository(),
                            receipt.manifest().orElseThrow().inventory());
                    return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED, Optional.empty(), List.of(),
                            List.of(nativeBuildRequiredGap(EvidenceContext.forSnapshot(snapshot), nativeBuild)),
                            Optional.empty(), Optional.empty(), List.of());
                }
            }
            acquisition = intake.compatibility(control.id(), input.repository(), input.revision(), input.dirty(),
                    input.includeTests(), false, List.copyOf(excluded));
            control.checkpoint();
            if (acquisition.snapshot().isEmpty()) {
                return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED, Optional.empty(), List.of(), List.of(),
                        Optional.of(acquisition.identity()), Optional.empty(), inputProblems(acquisition));
            }
            if (control.beginReuseCheck() && (input.dirty() || input.revision().isPresent())) {
                try {
                    verifiedInventory = !intake.hasUncapturedEntries(control.id());
                } catch (IOException unavailable) {
                    /* Reuse is optional; keep the normal analysis path. */ }
            }
        }
        if (nativeConfig.javaCapture().isPresent()) {
            var captureRoot = safe(nativeConfig.javaCapture().orElseThrow());
            if (captureRoot.startsWith(source)) {
                throw new IOException("CAPTURE_REGISTRATION_INSIDE_TARGET");
            }
            control.stage(AnalysisJobs.Stage.ANALYSIS);
            var expected = new CapturedJavaScan.Expected(
                    input.repository(), acquisition.snapshot().orElseThrow().identity(), readiness.identity());
            var nativeResult = new CapturedJavaScan().run(
                    captureRoot, expected, intakeDirectory.resolve("captured-java"), control);
            var outcome = nativeResult.outcome();
            if (nativeConfig.semanticStore().isPresent() && outcome.graph().isPresent()) {
                var languageEvidence = loadSemanticPublication(Optional.of(source), control);
                try (var roIntake = new RepositoryIntake(intakeDirectory, true)) {
                    var polyglotBytes = extractPolyglotBytes(acquisition);
                    var polyglot = new PolyglotScan().run(
                            control.id(), roIntake, readiness,
                            EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity()),
                            languageEvidence, intakeDirectory.resolve("semantic-work"), control,
                            digest -> {
                                var f = polyglotBytes.get(digest);
                                return f == null ? null : f.bytes();
                            },
                            control.mode().isFastArchitecture(), path -> false);
                    var combinedGaps = new TreeSet<>(outcome.gaps());
                    combinedGaps.addAll(polyglot.gaps());
                    return new AnalysisJobs.Outcome(outcome.completion(), outcome.graph(), outcome.reports(),
                            List.copyOf(combinedGaps), outcome.acquisition(), outcome.sourceReport(),
                            outcome.inputProblems());
                }
            }
            return outcome;
        }
        return runCapturedWithReadiness(acquisition, readiness, verifiedInventory, deltaCtx, artifactSession,
                Optional.of(source), control);
    }

    AnalysisJobs.RunResult runCaptured(RepositoryAcquisitionResult acquisition, AnalysisJobs.Control control)
            throws Exception {
        return runCaptured(acquisition, adaptControl(control));
    }

    AnalysisJobs.RunResult runCaptured(RepositoryAcquisitionResult acquisition, Progress control) throws Exception {
        boolean incrementalEnabled = !Boolean.getBoolean("evo.incremental.disabled");
        try (var artifactSession = VerifiedArtifactSession.open();
             var ignoredResolutionSession = JavaParserFrontend.openVerificationSession();
             var ignoredCacheScope = enterModuleCacheScope(incrementalEnabled)) {
            ReadinessPlan readiness;
            boolean verifiedInventory = false;
            var priorPublished = incrementalEnabled ? sharedCaches.lastPublished(input.repository())
                    : Optional.<SharedCaches.LastPublication>empty();
            var deltaCtx = new AcquisitionDeltaContext(
                    priorPublished.map(SharedCaches.LastPublication::snapshotId),
                    priorPublished.map(p -> p.graphId().value()),
                    priorPublished.map(SharedCaches.LastPublication::jobId),
                    false,
                    incrementalEnabled ? "PRECAPTURED_INPUT" : "INCREMENTAL_DISABLED",
                    Optional.empty());
            try (var intake = new RepositoryIntake(intakeDirectory)) {
                control.stage(AnalysisJobs.Stage.PLATFORM);
                readiness = intake.readinessIncremental(control.id(), Optional.empty(),
                        ReadinessPlanner.Policy.comprehensive(), control, control.mode().isFastArchitecture());
                if (control.beginReuseCheck() && (input.dirty() || input.revision().isPresent())) {
                    try {
                        verifiedInventory = !intake.hasUncapturedEntries(control.id());
                    } catch (IOException unavailable) {
                        /* Reuse is optional; keep the normal analysis path. */ }
                }
            }
            return runCapturedWithReadiness(acquisition, readiness, verifiedInventory, deltaCtx, artifactSession,
                    Optional.empty(), control);
        }
    }

    private record DependencySetup(
            List<com.evolution.analysis.maven.MavenBuildModelResolution> models,
            List<PlatformSymbolResult> additionalPlatforms,
            Map<Integer, com.evolution.analysis.frontend.PlatformInput> availablePlatforms,
            List<EvidenceAcquisitionLedger> platformLedgers,
            MavenScan.Inputs dependencies) {
    }

    private DependencySetup resolveDependenciesAndPlatforms(
            RepositoryAcquisitionResult acquisition,
            Optional<com.evolution.analysis.buildmodel.UniversalBuildModel> gradleModel,
            PlatformSymbolResult platform,
            Path cache,
            DependencyAcquisitionPolicy depPolicy,
            Optional<Path> resolvedAndroidSdk,
            BuildDeltaCache deltaCache) throws IOException {
        var models = MavenScan.models(acquisition, cache, depPolicy.remoteRepositories(), deltaCache);
        var requiredReleases = new TreeSet<>(MavenScan.platformReleases(models));
        requiredReleases.addAll(GradleScan.platformReleases(gradleModel));
        var platformViews = new TreeMap<Integer, PlatformSymbolResult>();
        platformViews.put(input.release(), platform);
        var additionalPlatforms = new ArrayList<PlatformSymbolResult>();
        for (int target : requiredReleases) {
            if (!platformViews.containsKey(target)) {
                var view = acquirePlatform(safe(input.jdk()), target);
                platformViews.put(target, view);
                additionalPlatforms.add(view);
            }
        }
        var availablePlatforms = new TreeMap<Integer, com.evolution.analysis.frontend.PlatformInput>();
        platformViews
                .forEach((target, view) -> view.platform().ifPresent(value -> availablePlatforms.put(target, value)));
        var platformLedgers = additionalPlatforms.isEmpty() ? List.<EvidenceAcquisitionLedger>of()
                : List.of(CapabilityGapNormalizer.normalize(
                        EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity()),
                        EvidenceNormalizationInput.builder().platformResults(additionalPlatforms).build()));
        var mavenBase = MavenScan.inputs(acquisition, models, cache, platform.platform(), depPolicy,
                availablePlatforms, deltaCache);
        var dependencies = GradleScan.augment(mavenBase, acquisition, gradleModel, cache, platform.platform(),
                depPolicy, availablePlatforms, resolvedAndroidSdk, deltaCache);
        return new DependencySetup(models, additionalPlatforms, availablePlatforms, platformLedgers, dependencies);
    }

    private AnalysisJobs.RunResult runCapturedWithReadiness(
            RepositoryAcquisitionResult acquisition,
            ReadinessPlan readiness,
            boolean verifiedInventory,
            AcquisitionDeltaContext deltaCtx,
            VerifiedArtifactSession artifactSession,
            Optional<Path> localSourceRoot,
            Progress control) throws Exception {
        boolean incrementalEnabled = !Boolean.getBoolean("evo.incremental.disabled");
        boolean progressiveEnabled = control.supportsProgressivePreviews()
                && !Boolean.getBoolean("evo.progressive.disabled")
                && acquisition.snapshot().isPresent();
        var cache = safe(input.mavenCache());
        boolean fast = control.mode().isFastArchitecture();
        var platform = acquirePlatform(safe(input.jdk()), input.release());
        control.checkpoint();
        boolean preferCacheOnly = Boolean.getBoolean("evo.workbench.offline")
                || (fast && Boolean.getBoolean("evo.workbench.fastCacheOnly"));
        var depPolicy = preferCacheOnly ? CACHE_ONLY : artifactPolicy.orElse(CACHE_ONLY);
        var defaults = UniversalBuildIngestion.Policy.defaults();
        var buildPolicy = new UniversalBuildIngestion.Policy(Optional.of(input.release()), Optional.of(input.release()),
                Optional.of("UTF-8"), 32_768, defaults.maxCharacters(), defaults.maxTokens(), 128);
        var decoded = new RepositoryInputDecoder.Prepared(acquisition,
                new RepositoryInputDecoder.Policy("UTF-8", 500_000, 100_000_000, fast));
        var gradleModel = GradleScan.model(decoded, buildPolicy, Map.of());
        Optional<Path> resolvedAndroidSdk = Optional.empty();
        if (nativeConfig.androidSdk().isPresent()) {
            resolvedAndroidSdk = Optional.of(safe(nativeConfig.androidSdk().orElseThrow()));
        }
        var languageEvidence = loadSemanticPublication(localSourceRoot, control);
        var snapshotId = acquisition.snapshot().orElseThrow().identity();

        var deltaCache = incrementalEnabled ? sharedCaches.buildDelta().forRun() : null;
        var beforeBuildStats = sharedCaches.buildDelta().stats();

        // Launch PolyglotScan early so static tree-sitter/gRPC/OpenAPI scanning overlaps with
        // Stage 1 preview building and remote Maven/Gradle artifact downloads.
        var verifiedPolyglotBytes = extractPolyglotBytes(acquisition);
        var failedFlag = new AtomicBoolean(false);
        BooleanSupplier subCancelled = () -> failedFlag.get() || control.getAsBoolean();
        var incrementalRoot = incrementalEnabled ? Optional.of(intakeDirectory.resolve("incremental-cache"))
                : Optional.<Path>empty();
        CompletableFuture<PolyglotScan.Result> polyglotFuture = CompletableFuture.supplyAsync(() -> {
            try (var attached = artifactSession.attach();
                 var intake = new RepositoryIntake(intakeDirectory, true)) {
                return new PolyglotScan().run(
                        control.id(), intake, readiness, EvidenceContext.forSnapshot(snapshotId),
                        languageEvidence, intakeDirectory.resolve("semantic-work"), subCancelled,
                        digest -> {
                            var file = verifiedPolyglotBytes.get(digest);
                            return file == null ? null : file.bytes();
                        },
                        fast, fast ? intake.installedNodeDependencies(control.id()) : path -> false, incrementalRoot);
            } catch (Exception failure) {
                failedFlag.set(true);
                throw new CompletionException(failure);
            }
        }, POLYGLOT_WORKERS);

        // Stage 1 (PRELIMINARY_STRUCTURE): run FastJavaSkeletonScanner + PreliminaryGraphBuilder
        // concurrently with remote Maven/Gradle artifact prefetch.
        var preliminaryModules = discoverPreliminaryModules(readiness, gradleModel, acquisition);
        var registeredModulePaths = preliminaryModules.stream()
                .map(PreliminaryGraphBuilder.ModuleInfo::path)
                .distinct()
                .sorted()
                .toList();
        if (!registeredModulePaths.isEmpty()) {
            control.registerModules(registeredModulePaths);
        }

        FastJavaSkeletonScanner.ScanResult stage1Skeleton = null;
        DependencySetup depSetup;
        if (progressiveEnabled) {
            final var finalResolvedAndroidSdk = resolvedAndroidSdk;
            CompletableFuture<DependencySetup> depFuture = CompletableFuture.supplyAsync(() -> {
                try (var attached = artifactSession.attach()) {
                    return resolveDependenciesAndPlatforms(acquisition, gradleModel, platform, cache, depPolicy,
                            finalResolvedAndroidSdk, deltaCache);
                } catch (Exception failure) {
                    failedFlag.set(true);
                    throw new CompletionException(failure);
                }
            }, PREFETCH_WORKERS);
            try {
                control.reportProgress(0, Math.max(1, registeredModulePaths.size()),
                        "Scanning syntax skeleton for Stage 1 preview");
                var skeletonInputs = collectLazySkeletonSources(acquisition, registeredModulePaths);
                stage1Skeleton = new FastJavaSkeletonScanner().scanLazy(skeletonInputs, subCancelled);
                control.checkpoint();
                Map<String, Object> stage1PolyglotSummary = extractStage1PolyglotSummary(polyglotFuture, acquisition);
                var stage1Graph = new PreliminaryGraphBuilder().build(
                        new PreliminaryGraphBuilder.Input(
                                input.repository(),
                                snapshotId,
                                AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE,
                                preliminaryModules,
                                stage1Skeleton,
                                List.of(),
                                registeredModulePaths,
                                stage1PolyglotSummary,
                                List.of()),
                        control);
                control.publishPreview(stage1Graph, AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE,
                        0, registeredModulePaths.size(), registeredModulePaths);
                control.reportProgress(Math.max(1, registeredModulePaths.size()),
                        Math.max(1, registeredModulePaths.size()),
                        "Stage 1 structural preview published; awaiting dependency resolution");
            } catch (CancellationException cancelled) {
                failedFlag.set(true);
                depFuture.cancel(true);
                polyglotFuture.cancel(true);
                throw cancelled;
            } catch (RuntimeException previewFailure) {
                LOG.log(System.Logger.Level.WARNING, "Stage 1 preliminary preview skipped ({0}: {1})",
                        previewFailure.getClass().getSimpleName(), previewFailure.getMessage());
            }
            try {
                depSetup = depFuture.join();
            } catch (CompletionException asyncFailure) {
                failedFlag.set(true);
                polyglotFuture.cancel(true);
                Throwable cause = asyncFailure.getCause();
                if (cause instanceof Exception ex)
                    throw ex;
                if (cause instanceof Error err)
                    throw err;
                throw asyncFailure;
            } catch (Throwable primaryFailure) {
                failedFlag.set(true);
                depFuture.cancel(true);
                polyglotFuture.cancel(true);
                throw primaryFailure;
            }
        } else {
            try {
                depSetup = resolveDependenciesAndPlatforms(acquisition, gradleModel, platform, cache, depPolicy,
                        resolvedAndroidSdk, deltaCache);
            } catch (Throwable primaryFailure) {
                failedFlag.set(true);
                polyglotFuture.cancel(true);
                throw primaryFailure;
            }
        }

        var models = depSetup.models();
        var additionalPlatforms = depSetup.additionalPlatforms();
        var availablePlatforms = depSetup.availablePlatforms();
        var platformLedgers = depSetup.platformLedgers();
        var dependencies = depSetup.dependencies();
        long[] operationalAcquisition = extractOperationalAcquisitionCounts(dependencies.summary());
        control.recordAcquisitionTelemetry(operationalAcquisition[0], operationalAcquisition[1]);
        var afterBuildStats = sharedCaches.buildDelta().stats();
        int deltaReusedBuildRoots = Math.max(0,
                (afterBuildStats.reusedBuildRoots() - beforeBuildStats.reusedBuildRoots())
                        + (afterBuildStats.gradleResolverReused() - beforeBuildStats.gradleResolverReused()));
        int deltaRecomputedBuildRoots = Math.max(0,
                (afterBuildStats.recomputedBuildRoots() - beforeBuildStats.recomputedBuildRoots())
                        + (afterBuildStats.gradleResolverRecomputed() - beforeBuildStats.gradleResolverRecomputed()));

        var frontend = new JavaParserFrontend();
        if (!incrementalEnabled) {
            JavaParserFrontend.configureModuleCacheDirectory(null);
            com.evolution.analysis.javaparser.ModuleIncrementalCache.clearInMemoryCache();
        }
        var javaIncrementalCache = new IncrementalJavaFrontendCache(intakeDirectory.resolve("incremental-cache"));
        var javaCacheHits = new java.util.concurrent.atomic.AtomicLong();
        var javaCacheMisses = new java.util.concurrent.atomic.AtomicLong();
        var reusedModulesCount = new java.util.concurrent.atomic.AtomicInteger();
        var recomputedModulesCount = new java.util.concurrent.atomic.AtomicInteger();
        var abiEarlyCutoffTotal = new java.util.concurrent.atomic.AtomicInteger();
        var impactedDependentsTotal = new java.util.concurrent.atomic.AtomicInteger();
        var reextractedJavaFilesTotal = new java.util.concurrent.atomic.AtomicInteger();
        var reusedJavaFilesTotal = new java.util.concurrent.atomic.AtomicInteger();

        int totalRepoJavaFiles = Math.max(1, (int) acquisition.files().stream()
                .filter(f -> isJavaOrKotlinPath(f.path())).count());
        var completedJavaFiles = new java.util.concurrent.atomic.AtomicInteger();
        var lastProgressEmitNanos = new java.util.concurrent.atomic.AtomicLong();

        // Stage 2 (PARTIAL_CONTEXTS) coalesced preview accumulator
        var stage2Lock = new java.util.concurrent.locks.ReentrantLock();
        var stage2PublishLock = new java.util.concurrent.locks.ReentrantLock();
        var stage2ModuleGraphSlots = new java.util.concurrent.atomic.AtomicInteger();
        var stage2CompletedModules = new LinkedHashSet<String>();
        var stage2PendingModules = new LinkedHashSet<String>(registeredModulePaths);
        var stage2ModuleGraphs = new ArrayList<KnowledgeGraph>();
        long[] lastStage2PublishNanos = { 0L };
        long stage2IntervalNanos = TimeUnit.MILLISECONDS.toNanos(
                Long.getLong("evo.progressive.partialPreviewIntervalMs", 2_000L));
        final var finalStage1Skeleton = stage1Skeleton;

        var guarded = new com.evolution.analysis.frontend.ConcurrentSemanticFrontend() {
            @Override
            public long estimatedWorkingBytes(com.evolution.analysis.frontend.FrontendRequest request) {
                return frontend.estimatedWorkingBytes(request);
            }

            @Override
            public void releaseCompletedContexts() {
                frontend.releaseCompletedContexts();
            }

            @Override
            public com.evolution.analysis.frontend.FrontendResult analyze(
                    com.evolution.analysis.frontend.FrontendRequest request) {
                try (var attached = artifactSession.attach()) {
                    control.checkpoint();
                    String modulePath = resolveRequestModulePath(request);
                    String moduleKey = request.sourceSet() == com.evolution.analysis.contract.source.SourceClassification.TEST
                            ? modulePath + ":test"
                            : modulePath;
                    String contextId = request.manifest().identity().value();
                    int totalFiles = request.sources().size();
                    long startedNanos = System.nanoTime();
                    control.reportModuleUpdate(moduleKey, "RUNNING", 0, totalFiles, 0L,
                            Optional.of(contextId), Optional.empty());

                    var cached = incrementalEnabled
                            ? javaIncrementalCache.get(request, JavaParserFrontend.PROVIDER)
                            : Optional.<FrontendResult>empty();
                    if (cached.isPresent()) {
                        var hitResult = cached.orElseThrow();
                        javaCacheHits.incrementAndGet();
                        reusedModulesCount.incrementAndGet();
                        reusedJavaFilesTotal.addAndGet(totalFiles);
                        long elapsedMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
                        int globalDone = Math.min(totalRepoJavaFiles, completedJavaFiles.addAndGet(totalFiles));
                        control.reportModuleUpdate(moduleKey, "REUSED", totalFiles, totalFiles, elapsedMs,
                                Optional.of(contextId), Optional.of("CACHE_HIT"));
                        emitThrottledProgress(control, lastProgressEmitNanos, globalDone, totalRepoJavaFiles,
                                "Reused cached module " + moduleKey, true);
                        maybePublishStage2Preview(modulePath, request.manifest(), hitResult);
                        control.checkpoint();
                        return hitResult;
                    }

                    javaCacheMisses.incrementAndGet();
                    var reportedForModule = new java.util.concurrent.atomic.AtomicInteger();
                    var moduleProgress = new FrontendProgress() {
                        @Override
                        public void onFileCompleted(String modPath, String filePath, boolean cacheHit) {
                            onFilesCompleted(modPath, 1, reportedForModule.get() + 1, totalFiles, cacheHit);
                        }

                        @Override
                        public void onFilesCompleted(String modPath, int deltaFiles, int doneInModule,
                                int totalInModule, boolean cacheHit) {
                            if (deltaFiles <= 0)
                                return;
                            int prev = reportedForModule.getAndUpdate(cur -> Math.min(totalFiles, cur + deltaFiles));
                            int actualDelta = Math.max(0, Math.min(totalFiles, prev + deltaFiles) - prev);
                            if (actualDelta <= 0)
                                return;
                            int globalDone = Math.min(totalRepoJavaFiles, completedJavaFiles.addAndGet(actualDelta));
                            long now = System.nanoTime();
                            long last = lastProgressEmitNanos.get();
                            if (now - last >= PROGRESS_THROTTLE_NANOS
                                    && lastProgressEmitNanos.compareAndSet(last, now)) {
                                long elapsedMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(now - startedNanos));
                                control.reportModuleUpdate(moduleKey, "RUNNING",
                                        Math.min(totalFiles, prev + actualDelta), totalFiles, elapsedMs,
                                        Optional.of(contextId), Optional.empty());
                                control.reportProgress(globalDone, totalRepoJavaFiles,
                                        "Analyzing " + moduleKey + " (" + Math.min(totalFiles, prev + actualDelta)
                                                + "/" + totalFiles + " files)");
                            }
                        }
                    };

                    var result = frontend.analyze(request, moduleProgress);
                    control.checkpoint();
                    var incStats = JavaParserFrontend.lastIncrementalStats();
                    if (incStats != null) {
                        abiEarlyCutoffTotal.addAndGet(Math.max(0, incStats.abiEarlyCutoffCount()));
                        impactedDependentsTotal.addAndGet(Math.max(0, incStats.impactedDependentsCount()));
                        reextractedJavaFilesTotal.addAndGet(Math.max(0, incStats.reextractedUnits()));
                        reusedJavaFilesTotal.addAndGet(Math.max(0, incStats.reusedUnits()));
                        if (incStats.incrementalHit() && incStats.reextractedUnits() == 0 && incStats.reusedUnits() > 0) {
                            reusedModulesCount.incrementAndGet();
                        } else {
                            recomputedModulesCount.incrementAndGet();
                        }
                    } else {
                        reextractedJavaFilesTotal.addAndGet(totalFiles);
                        recomputedModulesCount.incrementAndGet();
                    }
                    if (incrementalEnabled) {
                        javaIncrementalCache.put(request, result);
                    }
                    int remaining = Math.max(0, totalFiles - reportedForModule.get());
                    int globalDone = Math.min(totalRepoJavaFiles, completedJavaFiles.addAndGet(remaining));
                    long elapsedMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
                    boolean fullAbiReuse = incStats != null && incStats.incrementalHit()
                            && incStats.reextractedUnits() == 0 && incStats.reusedUnits() > 0;
                    control.reportModuleUpdate(moduleKey, fullAbiReuse ? "REUSED" : "COMPLETED",
                            totalFiles, totalFiles, elapsedMs, Optional.of(contextId),
                            fullAbiReuse ? Optional.of("ABI_EARLY_CUTOFF") : Optional.empty());
                    emitThrottledProgress(control, lastProgressEmitNanos, globalDone, totalRepoJavaFiles,
                            "Completed " + moduleKey, true);
                    maybePublishStage2Preview(modulePath, request.manifest(), result);
                    return result;
                }
            }

            private void maybePublishStage2Preview(String modulePath,
                    com.evolution.analysis.contract.analysis.AnalysisManifest manifest,
                    FrontendResult result) {
                if (!progressiveEnabled || finalStage1Skeleton == null) {
                    return;
                }
                // Build partial module graph OUTSIDE stage2Lock so parallel JavaParser workers never stall each other.
                KnowledgeGraph builtModuleGraph = null;
                if (stage2ModuleGraphSlots.getAndIncrement() < MAX_STAGE2_PREVIEW_GRAPHS) {
                    try {
                        builtModuleGraph = new CanonicalGraphBuilder().build(
                                manifest, result, List.of(), List.of(), fast);
                    } catch (RuntimeException ignored) {
                        /* Best-effort partial context projection; authoritative Stage 3 remains unaffected. */
                    }
                }
                boolean firstCompletion;
                List<String> completedList;
                List<String> pendingList;
                List<KnowledgeGraph> graphsSnapshot;
                int totalCtx;
                long now = System.nanoTime();
                stage2Lock.lock();
                try {
                    stage2CompletedModules.add(modulePath);
                    stage2PendingModules.remove(modulePath);
                    if (builtModuleGraph != null && stage2ModuleGraphs.size() < MAX_STAGE2_PREVIEW_GRAPHS) {
                        stage2ModuleGraphs.add(builtModuleGraph);
                    }
                    firstCompletion = stage2CompletedModules.size() == 1;
                    boolean shouldPublish = firstCompletion
                            || (now - lastStage2PublishNanos[0] >= stage2IntervalNanos);
                    if (!shouldPublish) {
                        return;
                    }
                    lastStage2PublishNanos[0] = now;
                    completedList = List.copyOf(stage2CompletedModules);
                    pendingList = List.copyOf(stage2PendingModules);
                    graphsSnapshot = List.copyOf(stage2ModuleGraphs);
                    totalCtx = Math.max(registeredModulePaths.size(), completedList.size() + pendingList.size());
                } finally {
                    stage2Lock.unlock();
                }
                boolean mustPublish = firstCompletion || stage2IntervalNanos == 0L;
                boolean publishAcquired = false;
                if (mustPublish) {
                    stage2PublishLock.lock();
                    publishAcquired = true;
                } else {
                    publishAcquired = stage2PublishLock.tryLock();
                }
                if (!publishAcquired) {
                    return;
                }
                try {
                    var stage2Graph = new PreliminaryGraphBuilder().build(
                            PreliminaryGraphBuilder.Input.stage2(
                                    input.repository(),
                                    snapshotId,
                                    preliminaryModules,
                                    finalStage1Skeleton,
                                    completedList,
                                    pendingList,
                                    graphsSnapshot),
                            control);
                    control.publishPreview(stage2Graph, AnalysisJobs.TruthTier.PARTIAL_CONTEXTS,
                            completedList.size(), totalCtx, pendingList);
                } catch (CancellationException cancelled) {
                    throw cancelled;
                } catch (RuntimeException previewFailure) {
                    LOG.log(System.Logger.Level.WARNING, "Stage 2 partial preview skipped ({0}: {1})",
                            previewFailure.getClass().getSimpleName(), previewFailure.getMessage());
                } finally {
                    stage2PublishLock.unlock();
                }
            }
        };
        var policy = new UniversalArchitecturePipeline.Policy(
                buildPolicy, decoded.policy(), guarded, JavaParserFrontend.PROVIDER,
                dependencies.resolutions(), platform.platform(), Optional.of(new MavenBuildModelProvider()),
                dependencies.externalPoms(), MavenScan.POLICY,
                100_000, 1_000, UniversalArchitecturePipeline.TruthEvaluationMode.AUTO, input.spring().scopes(),
                input.spring().space(), input.spring().baseline(),
                com.evolution.analysis.spring.truth.TruthRegionEvaluation.Limits.conservative())
                .withReadiness(readiness.identity())
                .withPrecomputedBuild(dependencies.externalPoms().isEmpty() ? gradleModel.orElse(null) : null);
        if (fast) {
            policy = policy.withAnalysisOptions(Map.of("analysis.profile", "FAST_ARCHITECTURE"));
        }
        if (input.spring().autoMultiConfigEnabled()) {
            policy = policy.withSpringSatMode(UniversalArchitecturePipeline.SpringSatMode.AUTO_MULTI_CONFIG_SAT);
        }
        var allPlatforms = new ArrayList<PlatformSymbolResult>();
        allPlatforms.add(platform);
        allPlatforms.addAll(additionalPlatforms);
        boolean allowReuse = verifiedInventory && languageEvidence.isEmpty();
        var reuseKey = allowReuse ? WorkbenchReuseInputs.key(acquisition, readiness, allPlatforms,
                models.stream().map(model -> model.identity()).toList(), gradleModel.map(model -> model.identity()),
                dependencies, policy, input.policy(), control) : Optional.<ContentDigest>empty();
        if (reuseKey.isPresent()) {
            var reusable = control.reuse(reuseKey.orElseThrow());
            if (reusable.isPresent()) {
                failedFlag.set(true);
                polyglotFuture.cancel(true);
                return reusable.orElseThrow();
            }
        }
        control.stage(AnalysisJobs.Stage.ANALYSIS);
        var finalPolicy = policy;

        long acquiredBytesTotal = acquisition.files().stream()
                .mapToLong(com.evolution.analysis.acquisition.AcquiredFile::size).sum();
        String customSpill = System.getProperty("evo.workbench.spilledGraphThresholdBytes");
        boolean useDiskSpill = customSpill != null && !customSpill.isBlank()
                ? acquiredBytesTotal >= Long.parseLong(customSpill.strip())
                : UniversalArchitecturePipeline.shouldUseDiskSpill(
                        acquiredBytesTotal, acquisition.files().size(), dependencies.resolutions().size());

        UniversalArchitecturePipeline.DetailedResult detailed;
        PolyglotScan.Result polyglot;
        var beforeSpringStats = sharedCaches.springCache().stats();
        try (FileAnalysisWorkspace diskWorkspace = useDiskSpill
                ? new FileAnalysisWorkspace(intakeDirectory.resolve("analysis-work-" + control.id()))
                : null) {
            AnalysisWorkspace analysisWorkspace = diskWorkspace != null ? diskWorkspace : AnalysisWorkspace.MEMORY;
            int aggregationWorkers = analysisWorkspace.concurrentTables()
                    ? com.evolution.analysis.concurrent.BoundedTasks.automaticWorkers()
                    : 1;
            try {
                java.util.function.Function<String, ContentDigest> polyglotSyntaxDigestForModule = modulePath ->
                        polyglotFuture.join().moduleSyntaxDigest(modulePath);
                detailed = new UniversalArchitecturePipeline(
                        analysisWorkspace,
                        aggregationWorkers,
                        incrementalEnabled ? sharedCaches.springCache() : null,
                        polyglotSyntaxDigestForModule)
                        .runWithEvidence(decoded, finalPolicy);
                control.checkpoint();
                polyglot = polyglotFuture.join();
            } catch (CompletionException asyncFailure) {
                failedFlag.set(true);
                Throwable cause = asyncFailure.getCause();
                if (cause instanceof Exception ex)
                    throw ex;
                if (cause instanceof Error err)
                    throw err;
                throw asyncFailure;
            } catch (Throwable primaryFailure) {
                failedFlag.set(true);
                polyglotFuture.cancel(true);
                throw primaryFailure;
            }
            control.checkpoint();
            var afterSpringStats = sharedCaches.springCache().stats();
            int deltaReusedSpring = Math.max(0, afterSpringStats.reusedContexts() - beforeSpringStats.reusedContexts());
            int deltaRecomputedSpring = Math.max(0,
                    afterSpringStats.recomputedContexts() - beforeSpringStats.recomputedContexts());

            long jHits = javaCacheHits.get();
            long jMisses = javaCacheMisses.get();
            long pHits = polyglot.syntax().cacheHits();
            long pMisses = polyglot.syntax().cacheMisses();
            control.recordCacheMetrics(Map.of(
                    "javaCacheHits", jHits,
                    "javaCacheMisses", jMisses,
                    "polyglotCacheHits", pHits,
                    "polyglotCacheMisses", pMisses,
                    "totalCacheHits", jHits + pHits,
                    "totalCacheMisses", jMisses + pMisses,
                    "buildDeltaReused", (long) deltaReusedBuildRoots,
                    "springCacheReused", (long) deltaReusedSpring));
            var gaps = new TreeSet<>(detailed.report().gaps());
            gaps.addAll(dependencies.gaps());
            gaps.addAll(filterSatisfiedReadinessGaps(
                    readiness, acquisition.snapshot().orElseThrow().identity(),
                    dependencies, availablePlatforms, allPlatforms, input.includeTests(), detailed, polyglot));
            gaps.addAll(polyglot.gaps());
            platformLedgers.forEach(ledger -> gaps.addAll(ledger.gaps()));
            gaps.addAll(CapabilityGapNormalizer
                    .normalize(EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity()),
                            EvidenceNormalizationInput.builder().repositoryAcquisitions(List.of(acquisition))
                                    .platformResults(allPlatforms).build())
                    .gaps());
            if (detailed.graphInput().isEmpty())
                return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED, Optional.empty(), List.of(),
                        List.copyOf(gaps), Optional.of(acquisition.identity()),
                        Optional.of(detailed.report().identity()), inputProblems(acquisition));
            control.stage(AnalysisJobs.Stage.GRAPH);
            var captured = detailed.graphInput().orElseThrow();
            gaps.addAll(captured.gaps());
            var graphLedgers = new ArrayList<>(dependencies.ledgers());
            graphLedgers.addAll(platformLedgers);
            var finalPolyglot = polyglot;
            var canonicalInput = new CanonicalGraphInput(
                    captured.repository(), captured.build(), captured.ingestion(), captured.spring(),
                    captured.issues(), List.copyOf(gaps), captured.acquisition(), captured.javaEvidence());

            Map<Path, ContentDigest> preverifiedMemo = deltaCache != null
                    ? deltaCache.runVerifiedDigestMemo()
                    : null;
            var projectionProfile = control.projectionProfile();

            if (useDiskSpill && graphStoreDirectory.isPresent()) {
                var storeDir = graphStoreDirectory.orElseThrow();
                var pagesStore = new PartitionedGraphStore(storeDir.resolve("graph-pages"));
                var beforeGraphStats = sharedCaches.graphProjector().stats();
                var publication = pagesStore.build(canonicalInput, storeDir, control, graphLedgers,
                        store -> new PolyglotScan().project(finalPolyglot, store, projectionProfile, control), fast,
                        incrementalEnabled ? sharedCaches.graphProjector() : null,
                        finalPolyglot::moduleProjectionDigest);
                var afterGraphStats = sharedCaches.graphProjector().stats();
                int deltaReusedGraphContexts = Math.max(0,
                        afterGraphStats.reusedContexts() - beforeGraphStats.reusedContexts());
                int deltaRebuiltGraphContexts = Math.max(0,
                        afterGraphStats.rebuiltContexts() - beforeGraphStats.rebuiltContexts());
                if (deltaReusedGraphContexts > 0) {
                    control.recordCacheMetrics(Map.of("graphProjectorReused", (long) deltaReusedGraphContexts));
                }
                control.checkpoint();
                var reports = new ArrayList<PublishedReports.Report>();
                if (input.policy().isPresent()) {
                    control.stage(AnalysisJobs.Stage.POLICY);
                    var selected = input.policy().orElseThrow();
                    try {
                        var compiled = new PolicyCompiler().compile(selected.bytes(),
                                EvidenceContext.forSnapshot(publication.snapshot()), PolicyCompiler.Limits.defaults());
                        gaps.addAll(compiled.gaps());
                        for (var context : publication.contexts()) {
                            if (context.analysis().isEmpty())
                                continue;
                            control.checkpoint();
                            try {
                                var contextSubgraph = pagesStore
                                        .contextGraph(publication.graph(), context.identity(), control)
                                        .orElseThrow(() -> new IOException("GRAPH_PARTITION_CONTEXT_MISSING"));
                                var queries = ArchitectureQueryService.replayPartition(contextSubgraph,
                                        publication.graph(), control);
                                var plan = new PolicyGraphMapper().bind(compiled, queries, context.identity(),
                                        PolicyGraphMapper.Limits.defaults());
                                var evaluation = new PolicyEvaluator().evaluate(compiled, plan, queries,
                                        PolicyEvaluation.Limits.defaults());
                                var metrics = new PolicyMetricsService().compute(compiled, plan, evaluation, queries,
                                        PolicyMetrics.Limits.defaults());
                                var score = new ArchitectureAssessmentService().assess(metrics, evaluation, queries,
                                        Optional.of(ArchitectureScoreModel.candidate(selected.scoreScope())),
                                        plan.limits().query(),
                                        ArchitectureMetric.Limits.defaults(), ArchitectureAssessment.Limits.defaults());
                                reports.add(new PublishedReports.Report(evaluation, Optional.of(metrics),
                                        Optional.of(score)));
                                gaps.addAll(plan.gaps());
                                gaps.addAll(evaluation.gaps());
                                gaps.addAll(metrics.gaps());
                                gaps.addAll(score.gaps());
                            } catch (CancellationException cancelled) {
                                throw cancelled;
                            } catch (RuntimeException failure) {
                                LOG.log(System.Logger.Level.WARNING,
                                        "Policy evaluation degraded for context {0} ({1}: {2})",
                                        context.identity(), failure.getClass().getSimpleName(), failure.getMessage());
                                gaps.add(policyDegradedGap(publication.graph(), publication.snapshot(),
                                        new EvidenceSubject(EvidenceSubject.Kind.CONFIGURATION, context.identity()),
                                        selected.scoreScope().name(), failure));
                            }
                        }
                    } catch (CancellationException cancelled) {
                        throw cancelled;
                    } catch (RuntimeException failure) {
                        LOG.log(System.Logger.Level.WARNING, "Policy compilation/setup degraded ({0}: {1})",
                                failure.getClass().getSimpleName(), failure.getMessage());
                        gaps.add(policyDegradedGap(publication.graph(), publication.snapshot(),
                                new EvidenceSubject(EvidenceSubject.Kind.REPOSITORY, input.repository().value()),
                                selected.scoreScope().name(), failure));
                    }
                }
                control.checkpoint();
                var summary = buildIncrementalSummary(incrementalEnabled, deltaCtx,
                        deltaReusedBuildRoots, deltaRecomputedBuildRoots,
                        abiEarlyCutoffTotal.get(), impactedDependentsTotal.get(),
                        reextractedJavaFilesTotal.get(), reusedJavaFilesTotal.get(),
                        reusedModulesCount.get(), recomputedModulesCount.get(),
                        deltaReusedSpring, deltaRecomputedSpring,
                        pHits, pMisses, deltaReusedGraphContexts,
                        deltaRebuiltGraphContexts > 0 || deltaReusedGraphContexts > 0
                                ? deltaRebuiltGraphContexts : publication.contexts().size());
                control.recordIncrementalMetrics(toIncrementalMetrics(summary));
                if (incrementalEnabled) {
                    sharedCaches.recordPublished(input.repository(), publication.snapshot(), publication.graph(),
                            control.id());
                }
                var completion = switch (detailed.report().status()) {
                    case COMPLETE ->
                        gaps.isEmpty() ? AnalysisJobs.Completion.COMPLETE : AnalysisJobs.Completion.PARTIAL;
                    case EMPTY_OR_NON_JAVA -> AnalysisJobs.Completion.EMPTY;
                    case TERMINAL_FAILURE, CANCELLED -> AnalysisJobs.Completion.FAILED;
                    case PARTIAL -> AnalysisJobs.Completion.PARTIAL;
                };
                if (reuseKey.isPresent() && (!WorkbenchReuseInputs.stableGaps(publication, gaps, polyglot.syntax())
                        || !WorkbenchReuseInputs.stillCurrent(allPlatforms, dependencies, control, preverifiedMemo))) {
                    control.withholdReuse();
                }
                return new AnalysisJobs.Outcome(completion, Optional.empty(), reports, List.copyOf(gaps),
                        Optional.of(acquisition.identity()), Optional.of(detailed.report().identity()),
                        inputProblems(acquisition), Optional.of(publication.graph()),
                        (int) Math.min(Integer.MAX_VALUE, publication.gapNodes()));
            }

            var beforeGraphStats = sharedCaches.graphProjector().stats();
            var graph = new CanonicalGraphBuilder().build(
                    canonicalInput,
                    graphLedgers,
                    store -> new PolyglotScan().project(finalPolyglot, store, projectionProfile, control),
                    fast,
                    incrementalEnabled ? sharedCaches.graphProjector() : null,
                    finalPolyglot::moduleProjectionDigest);
            var afterGraphStats = sharedCaches.graphProjector().stats();
            int deltaReusedGraphContexts = Math.max(0,
                    afterGraphStats.reusedContexts() - beforeGraphStats.reusedContexts());
            int deltaRebuiltGraphContexts = Math.max(0,
                    afterGraphStats.rebuiltContexts() - beforeGraphStats.rebuiltContexts());
            if (deltaReusedGraphContexts > 0) {
                control.recordCacheMetrics(Map.of("graphProjectorReused", (long) deltaReusedGraphContexts));
            }
            control.checkpoint();
            var reports = new ArrayList<PublishedReports.Report>();
            if (input.policy().isPresent()) {
                control.stage(AnalysisJobs.Stage.POLICY);
                var selected = input.policy().orElseThrow();
                try {
                    var compiled = new PolicyCompiler().compile(selected.bytes(),
                            EvidenceContext.forSnapshot(graph.snapshot()), PolicyCompiler.Limits.defaults());
                    gaps.addAll(compiled.gaps());
                    var guard = ArchitectureQueryService.operationalGuard(control, 0, 8_000_000);
                    var queries = new ArchitectureMetricsService(graph, ArchitectureMetric.Limits.defaults(), guard,
                            Optional.empty(), List.of()).queries();
                    for (var context : graph.contexts()) {
                        if (context.analysis().isEmpty())
                            continue;
                        control.checkpoint();
                        try {
                            var plan = new PolicyGraphMapper().bind(compiled, queries, context.identity(),
                                    PolicyGraphMapper.Limits.defaults());
                            var evaluation = new PolicyEvaluator().evaluate(compiled, plan, queries,
                                    PolicyEvaluation.Limits.defaults());
                            var metrics = new PolicyMetricsService().compute(compiled, plan, evaluation, queries,
                                    PolicyMetrics.Limits.defaults());
                            var score = new ArchitectureAssessmentService().assess(metrics, evaluation, queries,
                                    Optional.of(ArchitectureScoreModel.candidate(selected.scoreScope())),
                                    plan.limits().query(),
                                    ArchitectureMetric.Limits.defaults(), ArchitectureAssessment.Limits.defaults());
                            reports.add(
                                    new PublishedReports.Report(evaluation, Optional.of(metrics), Optional.of(score)));
                            gaps.addAll(plan.gaps());
                            gaps.addAll(evaluation.gaps());
                            gaps.addAll(metrics.gaps());
                            gaps.addAll(score.gaps());
                        } catch (CancellationException cancelled) {
                            throw cancelled;
                        } catch (RuntimeException failure) {
                            LOG.log(System.Logger.Level.WARNING,
                                    "Policy evaluation degraded for context {0} ({1}: {2})",
                                    context.identity(), failure.getClass().getSimpleName(), failure.getMessage());
                            gaps.add(policyDegradedGap(graph.identity(), graph.snapshot(),
                                    new EvidenceSubject(EvidenceSubject.Kind.CONFIGURATION, context.identity()),
                                    selected.scoreScope().name(), failure));
                        }
                    }
                } catch (CancellationException cancelled) {
                    throw cancelled;
                } catch (RuntimeException failure) {
                    LOG.log(System.Logger.Level.WARNING, "Policy compilation/setup degraded ({0}: {1})",
                            failure.getClass().getSimpleName(), failure.getMessage());
                    gaps.add(policyDegradedGap(graph.identity(), graph.snapshot(),
                            new EvidenceSubject(EvidenceSubject.Kind.REPOSITORY, input.repository().value()),
                            selected.scoreScope().name(), failure));
                }
            }
            control.checkpoint();
            var summary = buildIncrementalSummary(incrementalEnabled, deltaCtx,
                    deltaReusedBuildRoots, deltaRecomputedBuildRoots,
                    abiEarlyCutoffTotal.get(), impactedDependentsTotal.get(),
                    reextractedJavaFilesTotal.get(), reusedJavaFilesTotal.get(),
                    reusedModulesCount.get(), recomputedModulesCount.get(),
                    deltaReusedSpring, deltaRecomputedSpring,
                    pHits, pMisses, deltaReusedGraphContexts, deltaRebuiltGraphContexts);
            control.recordIncrementalMetrics(toIncrementalMetrics(summary));
            if (incrementalEnabled) {
                sharedCaches.recordPublished(input.repository(), graph.snapshot(), graph.identity(), control.id());
            }
            var completion = switch (detailed.report().status()) {
                case COMPLETE -> gaps.isEmpty() ? AnalysisJobs.Completion.COMPLETE : AnalysisJobs.Completion.PARTIAL;
                case EMPTY_OR_NON_JAVA -> AnalysisJobs.Completion.EMPTY;
                case TERMINAL_FAILURE, CANCELLED -> AnalysisJobs.Completion.FAILED;
                case PARTIAL -> AnalysisJobs.Completion.PARTIAL;
            };
            if (reuseKey.isPresent() && (!WorkbenchReuseInputs.stableGaps(graph, gaps, polyglot.syntax())
                    || !WorkbenchReuseInputs.stillCurrent(allPlatforms, dependencies, control, preverifiedMemo)))
                control.withholdReuse();
            return new AnalysisJobs.Outcome(completion, Optional.of(graph), reports, List.copyOf(gaps),
                    Optional.of(acquisition.identity()), Optional.of(detailed.report().identity()),
                    inputProblems(acquisition));
        }
    }

    private static void emitThrottledProgress(
            Progress control,
            java.util.concurrent.atomic.AtomicLong lastEmitNanos,
            int done,
            int total,
            String message,
            boolean force) {
        long now = System.nanoTime();
        long prev = lastEmitNanos.get();
        if (force || now - prev >= PROGRESS_THROTTLE_NANOS) {
            lastEmitNanos.set(now);
            control.reportProgress(done, total, message);
        }
    }

    private static boolean isJavaOrKotlinPath(String path) {
        if (path == null)
            return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt");
    }

    private static String resolveRequestModulePath(com.evolution.analysis.frontend.FrontendRequest request) {
        for (var mod : request.manifest().modules()) {
            if (mod.identity().equals(request.module())) {
                return normalizeModuleDir(mod.path());
            }
        }
        return normalizeModuleDir(request.module().value());
    }

    private static List<PreliminaryGraphBuilder.ModuleInfo> discoverPreliminaryModules(
            ReadinessPlan readiness,
            Optional<com.evolution.analysis.buildmodel.UniversalBuildModel> gradleModel,
            RepositoryAcquisitionResult acquisition) {
        var rootFamilyById = new HashMap<String, ReadinessPlan.Family>();
        for (var root : readiness.roots()) {
            rootFamilyById.put(root.id(), root.family());
        }
        var modulesByPath = new TreeMap<String, PreliminaryGraphBuilder.ModuleInfo>();
        for (var m : readiness.modules()) {
            String path = normalizeModuleDir(m.directory());
            var family = rootFamilyById.get(m.root());
            String tool = family != null ? family.name() : "UNKNOWN";
            String name = path.equals(".") ? m.target() : path;
            modulesByPath.putIfAbsent(path, new PreliminaryGraphBuilder.ModuleInfo(path, name, tool, 0));
        }
        gradleModel.ifPresent(model -> {
            for (var m : model.modules()) {
                String path = normalizeModuleDir(m.descriptor().path());
                modulesByPath.putIfAbsent(path, new PreliminaryGraphBuilder.ModuleInfo(
                        path, m.descriptor().displayName(), m.tool().name(), 0));
            }
        });
        for (String pomPath : acquisition.workspacePoms().keySet()) {
            int slash = pomPath.lastIndexOf('/');
            String dir = slash > 0 ? normalizeModuleDir(pomPath.substring(0, slash)) : ".";
            modulesByPath.putIfAbsent(dir, new PreliminaryGraphBuilder.ModuleInfo(dir, dir, "MAVEN", 0));
        }
        if (modulesByPath.isEmpty()) {
            modulesByPath.put(".", new PreliminaryGraphBuilder.ModuleInfo(".", ".", "UNKNOWN", 0));
        }
        // Count Java/Kotlin source files per module for accurate ModuleInfo metadata
        var sortedDirs = new ArrayList<>(modulesByPath.keySet());
        sortedDirs.sort(Comparator.comparingInt(String::length).reversed());
        var counts = new HashMap<String, Integer>();
        for (var file : acquisition.files()) {
            if (!isJavaOrKotlinPath(file.path()))
                continue;
            String owner = matchModuleDir(file.path(), sortedDirs);
            counts.merge(owner, 1, Integer::sum);
        }
        var result = new ArrayList<PreliminaryGraphBuilder.ModuleInfo>(modulesByPath.size());
        for (var info : modulesByPath.values()) {
            result.add(new PreliminaryGraphBuilder.ModuleInfo(
                    info.path(), info.displayName(), info.buildSystem(), counts.getOrDefault(info.path(), 0)));
        }
        return List.copyOf(result);
    }

    private static List<FastJavaSkeletonScanner.LazySource> collectLazySkeletonSources(
            RepositoryAcquisitionResult acquisition,
            List<String> modulePaths) {
        var sortedDirs = new ArrayList<>(modulePaths);
        sortedDirs.sort(Comparator.comparingInt(String::length).reversed());
        var out = new ArrayList<FastJavaSkeletonScanner.LazySource>();
        for (var file : acquisition.files()) {
            if (!isJavaOrKotlinPath(file.path()))
                continue;
            if (file.size() > 4L * 1024 * 1024)
                continue;
            String owner = matchModuleDir(file.path(), sortedDirs);
            out.add(new FastJavaSkeletonScanner.LazySource(
                    file.path(), owner, file.contentDigest(), file::bytes));
        }
        return out;
    }

    private static String matchModuleDir(String relativePath, List<String> dirsLongestFirst) {
        String norm = relativePath.replace('\\', '/');
        while (norm.startsWith("./"))
            norm = norm.substring(2);
        for (String dir : dirsLongestFirst) {
            if (".".equals(dir))
                continue;
            if (norm.equals(dir) || norm.startsWith(dir + "/")) {
                return dir;
            }
        }
        int srcIdx = norm.indexOf("/src/");
        if (srcIdx > 0) {
            return normalizeModuleDir(norm.substring(0, srcIdx));
        }
        return ".";
    }

    private static Map<String, Object> extractStage1PolyglotSummary(
            CompletableFuture<PolyglotScan.Result> polyglotFuture,
            RepositoryAcquisitionResult acquisition) {
        try {
            long waitMs = Long.getLong("evo.progressive.stage1PolyglotWaitMs", 1_500L);
            PolyglotScan.Result result = waitMs > 0L
                    ? polyglotFuture.get(waitMs, TimeUnit.MILLISECONDS)
                    : (polyglotFuture.isDone() ? polyglotFuture.getNow(null) : null);
            if (result != null) {
                var summary = new TreeMap<String, Object>();
                summary.put("status", "STATIC_SYNTAX_READY");
                summary.put("filesByLanguage", result.languages());
                summary.put("documents", result.syntax().documents());
                summary.put("parsedDocuments", result.syntax().parsed());
                summary.put("categories", result.syntax().categories());
                return Map.copyOf(summary);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted awaiting Stage 1 polyglot summary");
        } catch (TimeoutException | ExecutionException | CancellationException fallback) {
            // Fall through to lightweight file-extension summary so Stage 1 preview never blocks.
        }
        var extCounts = new TreeMap<String, Long>();
        for (var f : acquisition.files()) {
            String p = f.path().toLowerCase(Locale.ROOT);
            if (p.endsWith(".java"))
                extCounts.merge("java", 1L, Long::sum);
            else if (p.endsWith(".kt") || p.endsWith(".kts"))
                extCounts.merge("kotlin", 1L, Long::sum);
            else if (p.endsWith(".ts") || p.endsWith(".tsx"))
                extCounts.merge("typescript", 1L, Long::sum);
            else if (p.endsWith(".js") || p.endsWith(".jsx"))
                extCounts.merge("javascript", 1L, Long::sum);
            else if (p.endsWith(".py"))
                extCounts.merge("python", 1L, Long::sum);
            else if (p.endsWith(".go"))
                extCounts.merge("go", 1L, Long::sum);
            else if (p.endsWith(".proto"))
                extCounts.merge("protobuf", 1L, Long::sum);
        }
        return extCounts.isEmpty()
                ? Map.of()
                : Map.of("status", "SCANNING_IN_BACKGROUND", "filesByLanguage", extCounts);
    }

    static IncrementalSummary buildIncrementalSummary(
            boolean incrementalEnabled,
            AcquisitionDeltaContext deltaCtx,
            int reusedBuildRoots,
            int recomputedBuildRoots,
            int abiEarlyCutoffCount,
            int impactedDependentsCount,
            int reextractedJavaFiles,
            int reusedJavaFiles,
            int reusedModules,
            int recomputedModules,
            int reusedSpringContexts,
            int recomputedSpringContexts,
            long polyglotCacheHits,
            long polyglotCacheMisses,
            int reusedGraphContexts,
            int rebuiltGraphContexts) {
        if (!incrementalEnabled) {
            return IncrementalSummary.fallbackFull("INCREMENTAL_DISABLED",
                    deltaCtx.baseSnapshotId(), deltaCtx.baseGraphId(), deltaCtx.baseJobId());
        }
        long addedFiles = deltaCtx.fileDelta().map(StreamingRepositoryAcquirer.FileDelta::addedFiles).orElse(0L);
        long modifiedFiles = deltaCtx.fileDelta().map(StreamingRepositoryAcquirer.FileDelta::modifiedFiles).orElse(0L);
        long deletedFiles = deltaCtx.fileDelta().map(StreamingRepositoryAcquirer.FileDelta::deletedFiles).orElse(0L);
        long unchangedFiles = deltaCtx.fileDelta().map(StreamingRepositoryAcquirer.FileDelta::unchangedFiles).orElse(0L);
        long reusedFiles = deltaCtx.fileDelta().isPresent()
                ? unchangedFiles
                : (long) reusedJavaFiles + polyglotCacheHits;

        boolean anyReuse = deltaCtx.incrementalUsed()
                || reusedBuildRoots > 0
                || abiEarlyCutoffCount > 0
                || reusedJavaFiles > 0
                || reusedModules > 0
                || reusedSpringContexts > 0
                || polyglotCacheHits > 0
                || reusedGraphContexts > 0;
        String mode;
        String fallbackReason = "";
        if (deltaCtx.incrementalUsed() && deltaCtx.fileDelta().isPresent() && deltaCtx.fileDelta().orElseThrow().isEmpty()) {
            mode = IncrementalSummary.MODE_NO_CHANGE;
        } else if (anyReuse) {
            mode = IncrementalSummary.MODE_INCREMENTAL;
        } else {
            mode = IncrementalSummary.MODE_COLD;
            fallbackReason = deltaCtx.fallbackReason().isEmpty() ? "NO_PRIOR_SNAPSHOT" : deltaCtx.fallbackReason();
        }
        long timeSavedEstimateMs = anyReuse
                ? Math.max(0L,
                        (long) reusedModules * 150L
                                + (long) reusedJavaFiles * 8L
                                + (long) abiEarlyCutoffCount * 15L
                                + (long) reusedBuildRoots * 120L
                                + (long) reusedSpringContexts * 80L
                                + (long) reusedGraphContexts * 60L
                                + polyglotCacheHits * 5L
                                + unchangedFiles)
                : 0L;
        return new IncrementalSummary(
                mode,
                fallbackReason,
                deltaCtx.baseSnapshotId(),
                deltaCtx.baseGraphId(),
                deltaCtx.baseJobId(),
                addedFiles,
                modifiedFiles,
                deletedFiles,
                unchangedFiles,
                reusedFiles,
                reusedBuildRoots,
                recomputedBuildRoots,
                abiEarlyCutoffCount,
                impactedDependentsCount,
                reextractedJavaFiles,
                reusedJavaFiles,
                reusedModules,
                recomputedModules,
                reusedSpringContexts,
                recomputedSpringContexts,
                polyglotCacheHits,
                polyglotCacheMisses,
                reusedGraphContexts,
                rebuiltGraphContexts,
                timeSavedEstimateMs,
                timeSavedEstimateMs > 0L);
    }

    static AnalysisJobs.IncrementalMetrics toIncrementalMetrics(IncrementalSummary summary) {
        return new AnalysisJobs.IncrementalMetrics(
                summary.baseSnapshotId(),
                clampInt(summary.addedFiles()),
                clampInt(summary.modifiedFiles()),
                clampInt(summary.deletedFiles()),
                clampInt(summary.reusedFiles()),
                Math.max(0, summary.abiEarlyCutoffCount()),
                Math.max(0, summary.impactedDependentsCount()),
                Math.max(0, summary.reusedModules()),
                Math.max(0L, summary.timeSavedEstimateMs()));
    }

    private static int clampInt(long value) {
        if (value <= 0L)
            return 0;
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static CapabilityGapRecord policyDegradedGap(ContentDigest graphId, SnapshotIdentity snapshot,
            EvidenceSubject subject, String scope, RuntimeException failure) {
        var proof = CanonicalJson.sha256(List.of(subject, scope, failure.getClass().getName()));
        var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.CONFIGURATION, "policy.evaluation",
                List.of(subject), EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of("Provide a repository-compatible architecture policy YAML or inspect the policy evaluation diagnostic; the canonical knowledge graph remains published."));
        return CapabilityGapRecord.create(EvidenceContext.forSnapshot(snapshot), POLICY_EVALUATION_PROVIDER,
                "policy.evaluation", "POLICY_EVALUATION_DEGRADED", subject, List.of(),
                List.of(ProviderObservationReference.create(POLICY_EVALUATION_PROVIDER, "policy.evaluation",
                        graphId, proof)),
                List.of(requirement), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.POLICY, "policy.evaluation"),
                        new AffectedOutput(AffectedOutput.Kind.ASSESSMENT, "policy.evaluation")),
                List.of(), List.of(),
                List.of("Policy evaluation degraded (" + failure.getClass().getSimpleName()
                        + "); canonical graph and source intelligence remain preserved."));
    }

    private static List<AnalysisJobs.InputProblem> inputProblems(RepositoryAcquisitionResult acquisition) {
        return acquisition.problems().stream()
                .map(p -> new AnalysisJobs.InputProblem(acquisition.provider(), acquisition.identity(), p)).toList();
    }

    private static Map<ContentDigest, com.evolution.analysis.acquisition.AcquiredFile> extractPolyglotBytes(
            RepositoryAcquisitionResult acquisition) {
        var map = new HashMap<ContentDigest, com.evolution.analysis.acquisition.AcquiredFile>();
        long javaBudget = 32L * 1024L * 1024L;
        for (var file : acquisition.files()) {
            String p = file.path();
            if (!p.endsWith(".java") || p.endsWith("Grpc.java") || p.contains("grpc")) {
                map.putIfAbsent(file.contentDigest(), file);
            } else if (file.bytes().length <= 512_000 && javaBudget >= file.bytes().length) {
                if (map.putIfAbsent(file.contentDigest(), file) == null) {
                    javaBudget -= file.bytes().length;
                }
            }
        }
        return map;
    }

    private NativeBuildCoordinator.Result runNativeBuild(
            String profileName,
            Path source,
            ReadinessPlan plan,
            String revision,
            RepositoryIntake intake,
            BooleanSupplier cancelled) throws IOException {
        Optional<NativeBuildRegistrations.Selected> selected = NativeBuildRegistrations.selected(profileName, source);
        if (selected.isEmpty()) {
            return NativeBuildCoordinator.unavailable(plan, "NATIVE_BUILD_PROFILE_NOT_REGISTERED");
        }
        try {
            var registration = selected.orElseThrow();
            var profile = CanonicalRecordReader.read(registration.document(), NativeBuildProfile.class);
            if (!CanonicalJson.write(profile).equals(CanonicalJson.write(registration.document()))) {
                throw new IOException("NATIVE_BUILD_PROFILE_INVALID");
            }
            if (Path.of(profile.stateDirectory()).toAbsolutePath().normalize().startsWith(source)) {
                throw new IOException("NATIVE_STATE_INSIDE_TARGET");
            }
            return new NativeBuildCoordinator(profile, registration)
                    .run(plan, revision, intake.inventory(), intake.content(), source, cancelled);
        } catch (IOException unavailable) {
            String code = unavailable.getMessage();
            if ("NATIVE_TERMINATION_UNCONFIRMED".equals(code))
                throw unavailable;
            return NativeBuildCoordinator.unavailable(plan,
                    code != null && code.matches("(?:NATIVE|REGISTERED|CURRENT_SANDBOX)_[A-Z0-9_]{1,80}")
                            ? code
                            : "NATIVE_BUILD_UNAVAILABLE");
        }
    }

    private Optional<PolyglotScan.Published> loadSemanticPublication(Optional<Path> localSourceRoot,
            BooleanSupplier cancelled) throws IOException {
        if (nativeConfig.semanticStore().isEmpty())
            return Optional.empty();
        var store = safe(nativeConfig.semanticStore().orElseThrow());
        Optional<Path> authority = nativeConfig.semanticAuthority().isPresent()
                ? Optional.of(safe(nativeConfig.semanticAuthority().orElseThrow()))
                : Optional.empty();
        if (localSourceRoot.isPresent()) {
            var source = localSourceRoot.orElseThrow();
            if (store.startsWith(source) || authority.filter(p -> p.startsWith(source)).isPresent()) {
                throw new IOException("CAPTURE_REGISTRATION_INSIDE_TARGET");
            }
        }
        var trust = nativeConfig.semanticPolicy().isPresent()
                ? nativeConfig.semanticPolicy().orElseThrow()
                : SemanticAuthorityStore.policy(authority.orElseThrow());
        return Optional.of(PolyglotScan.load(
                store, nativeConfig.semanticPlan().orElseThrow(), trust, authority, cancelled));
    }

    private static CapabilityGapRecord nativeBuildRequiredGap(EvidenceContext context,
            NativeBuildCoordinator.Result result) {
        var proof = CanonicalJson
                .sha256(List.of(result.readiness(), result.status(), result.requested(), result.succeeded()));
        var subject = new EvidenceSubject(EvidenceSubject.Kind.REPOSITORY, result.readiness().value());
        var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT,
                "native.build.required",
                List.of(subject), EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,
                List.of("Register an operator Hyper-V worker profile outside the target workspace and execute --build=required cleanly."));
        return CapabilityGapRecord.create(context, NativeBuildCoordinator.PROVIDER, "native.isolated-build",
                "NATIVE_BUILD_REQUIRED_FAILED:" + result.status(), subject, List.of(),
                List.of(ProviderObservationReference.create(NativeBuildCoordinator.PROVIDER, "native.isolated-build",
                        result.readiness(), proof)),
                List.of(requirement), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "native.isolated-build")),
                List.of(), List.of(),
                List.of("Isolated native build did not complete cleanly; host fallback is disabled."));
    }

    /**
     * Suppresses only readiness gaps whose exact capability has already been established
     * by verified captured evidence in this run (e.g. a verified Android SDK android.jar,
     * a COMPLETE JDK platform attached to an analyzed Java Maven/Gradle context, or a
     * complete VALIDATED_OBSERVATION native semantic publication). Native build, model,
     * toolchain, dependency-closure and generator obligations remain explicit and truthful.
     */
    public static List<CapabilityGapRecord> filterSatisfiedReadinessGaps(
            ReadinessPlan readiness,
            SnapshotIdentity snapshot,
            MavenScan.Inputs dependencies,
            Map<Integer, com.evolution.analysis.frontend.PlatformInput> availablePlatforms,
            List<PlatformSymbolResult> allPlatforms,
            boolean includeTests,
            UniversalArchitecturePipeline.DetailedResult detailed,
            PolyglotScan.Result polyglot) {
        var raw = readiness.gaps(snapshot);
        if (raw.isEmpty())
            return raw;
        boolean anyAndroidSdkGap = dependencies != null && dependencies.gaps().stream()
                .anyMatch(g -> g.subject().identity().contains("android-sdk:"));
        boolean androidSdkVerified = dependencies != null
                && !anyAndroidSdkGap
                && dependencies.resolutions().values().stream()
                        .flatMap(r -> r.binaries().stream())
                        .anyMatch(b -> b.entry().logicalName().startsWith("android-sdk:platforms/android-")
                                && b.entry().logicalName().endsWith("/android.jar"));
        boolean allAcquiredPlatformsComplete = availablePlatforms != null && !availablePlatforms.isEmpty()
                && allPlatforms != null && !allPlatforms.isEmpty()
                && allPlatforms.stream().allMatch(p -> p.status() == PlatformSymbolResult.Status.COMPLETE);
        var analyzedModuleSourceSets = new HashSet<String>();
        var moduleDirById = new HashMap<ModuleIdentity, String>();
        if (detailed != null && detailed.graphInput().isPresent()) {
            var graphInput = detailed.graphInput().orElseThrow();
            graphInput.build().ifPresent(build -> {
                for (var m : build.modules()) {
                    if (m.tool() == com.evolution.analysis.buildmodel.UniversalBuildModel.Tool.MAVEN
                            || m.tool() == com.evolution.analysis.buildmodel.UniversalBuildModel.Tool.GRADLE_GROOVY
                            || m.tool() == com.evolution.analysis.buildmodel.UniversalBuildModel.Tool.GRADLE_KOTLIN) {
                        moduleDirById.put(m.descriptor().identity(), normalizeModuleDir(m.descriptor().path()));
                    }
                }
            });
            graphInput.ingestion().ifPresent(ing -> {
                for (var unit : ing.units()) {
                    if (unit.status() == UniversalIngestionPipeline.Status.ANALYZED && unit.frontend().isPresent()) {
                        String dir = moduleDirById.get(unit.sourceSet().module());
                        if (dir != null) {
                            analyzedModuleSourceSets.add(dir + "|" + unit.sourceSet().kind().name().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            });
        }
        var androidVerifiedModuleDirs = new HashSet<String>();
        if (dependencies != null && !moduleDirById.isEmpty()) {
            dependencies.resolutions().forEach((sourceSet, resolution) -> {
                boolean hasAndroidJar = resolution.binaries().stream()
                        .anyMatch(b -> b.entry().logicalName().startsWith("android-sdk:platforms/android-")
                                && b.entry().logicalName().endsWith("/android.jar"));
                if (hasAndroidJar) {
                    String dir = moduleDirById.get(sourceSet.module());
                    if (dir != null) {
                        androidVerifiedModuleDirs.add(dir);
                    }
                }
            });
        }
        boolean strongerNativePolyglotComplete = polyglot != null
                && polyglot.uncovered().isEmpty()
                && polyglot.published()
                        .filter(p -> "VALIDATED_OBSERVATION".equals(p.trust())
                                && Boolean.TRUE.equals(p.report().get("complete")))
                        .isPresent();

        var rootFamilyById = new HashMap<String, ReadinessPlan.Family>();
        for (var root : readiness.roots())
            rootFamilyById.put(root.id(), root.family());
        var moduleById = new HashMap<String, ReadinessPlan.Module>();
        for (var module : readiness.modules())
            moduleById.put(module.id(), module);
        var contextById = new HashMap<String, ReadinessPlan.Context>();
        for (var ctx : readiness.contexts())
            contextById.put(ctx.id(), ctx);

        var filtered = new ArrayList<CapabilityGapRecord>(raw.size());
        for (var gap : raw) {
            String reason = gap.reasonCode();
            if ("ANDROID_SDK_REQUIRED".equals(reason) && androidSdkVerified) {
                if (moduleDirById.isEmpty()) {
                    continue;
                }
                String subjectId = gap.subject().identity();
                ReadinessPlan.Module mod = moduleById.get(subjectId);
                if (mod == null) {
                    var ctx = contextById.get(subjectId);
                    if (ctx != null) {
                        mod = moduleById.get(ctx.module());
                    }
                }
                if (mod != null && androidVerifiedModuleDirs.contains(normalizeModuleDir(mod.directory()))) {
                    continue;
                }
            }
            if ("NATIVE_SEMANTIC_VALIDATION_REQUIRED".equals(reason) && strongerNativePolyglotComplete) {
                continue;
            }
            if ("ANALYZED_PLATFORM_REQUIRED".equals(reason) && allAcquiredPlatformsComplete
                    && !analyzedModuleSourceSets.isEmpty()) {
                var ctx = contextById.get(gap.subject().identity());
                if (ctx != null) {
                    var family = rootFamilyById.get(ctx.root());
                    var mod = moduleById.get(ctx.module());
                    boolean jvmRoot = family == ReadinessPlan.Family.MAVEN || family == ReadinessPlan.Family.GRADLE;
                    boolean javaOnlyModule = mod != null
                            && (mod.languages().isEmpty() || mod.languages().equals(List.of("java")));
                    boolean analyzedSourceSet = "main".equals(ctx.sourceSet())
                            || (includeTests && "test".equals(ctx.sourceSet()));
                    if (jvmRoot && javaOnlyModule && analyzedSourceSet
                            && analyzedModuleSourceSets.contains(
                                    normalizeModuleDir(mod.directory()) + "|" + ctx.sourceSet())) {
                        continue;
                    }
                }
            }
            filtered.add(gap);
        }
        return List.copyOf(filtered);
    }

    public static final List<String> PROJECTION_PROFILES = List.of("strict", "dfd-export");

    public static com.evolution.analysis.polyglot.ServiceArchitectureProjection.ProjectionProfile configuredProjectionProfile() {
        String raw = System.getProperty("evo.projection.profile");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("EVO_PROJECTION_PROFILE");
        }
        if (raw != null && ("dfd-export".equalsIgnoreCase(raw.strip())
                || "DFD_EXPORT".equalsIgnoreCase(raw.strip()))) {
            return com.evolution.analysis.polyglot.ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT;
        }
        return com.evolution.analysis.polyglot.ServiceArchitectureProjection.ProjectionProfile.STRICT;
    }

    public static String projectionProfileName(
            com.evolution.analysis.polyglot.ServiceArchitectureProjection.ProjectionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return profile == com.evolution.analysis.polyglot.ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT
                ? "dfd-export"
                : "strict";
    }

    public static String configuredProjectionProfileName() {
        return projectionProfileName(configuredProjectionProfile());
    }

    public static com.evolution.analysis.polyglot.ServiceArchitectureProjection.ProjectionProfile parseProjectionProfile(
            String raw) {
        if (raw == null || raw.isBlank()) {
            return configuredProjectionProfile();
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        if ("strict".equals(normalized)) {
            return com.evolution.analysis.polyglot.ServiceArchitectureProjection.ProjectionProfile.STRICT;
        }
        if ("dfd-export".equals(normalized) || "dfd_export".equals(normalized)) {
            return com.evolution.analysis.polyglot.ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT;
        }
        throw new IllegalArgumentException("Invalid projectionProfile");
    }

    static long[] extractOperationalAcquisitionCounts(Map<String, Object> summary) {
        if (summary == null || summary.isEmpty()) {
            return new long[] { 0L, 0L };
        }
        long acquisitionAttempts = 0L;
        long pomAttempts = 0L;
        Object rawRoots = summary.get("roots");
        if (rawRoots instanceof List<?> roots) {
            for (Object entry : roots) {
                if (entry instanceof Map<?, ?> rootMap) {
                    acquisitionAttempts += nonNegativeLong(rootMap.get("acquisitionAttempts"));
                    pomAttempts += nonNegativeLong(rootMap.get("pomAttempts"));
                }
            }
        }
        Object rawGradle = summary.get("gradle");
        if (rawGradle instanceof Map<?, ?> gradleMap) {
            acquisitionAttempts += nonNegativeLong(gradleMap.get("acquisitionAttempts"));
        }
        return new long[] { acquisitionAttempts, pomAttempts };
    }

    private static long nonNegativeLong(Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        return 0L;
    }

    private static String normalizeModuleDir(String path) {
        if (path == null || path.isEmpty() || path.equals("."))
            return ".";
        String norm = path.replace('\\', '/');
        while (norm.startsWith("./"))
            norm = norm.substring(2);
        while (norm.endsWith("/") && norm.length() > 1)
            norm = norm.substring(0, norm.length() - 1);
        return norm.isEmpty() ? "." : norm;
    }

    static Path safe(Path input) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        for (Path parent = path; parent != null; parent = parent.getParent())
            if (Files.isSymbolicLink(parent))
                throw new IOException("UNSAFE_PATH");
        return path;
    }
}
