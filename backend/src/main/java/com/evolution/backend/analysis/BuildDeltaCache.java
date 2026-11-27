package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.AcquiredFile;
import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import com.evolution.analysis.frontend.BinaryInput;
import com.evolution.analysis.frontend.PlatformInput;
import com.evolution.analysis.maven.*;
import com.evolution.analysis.readiness.ReadinessDelta;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layer-2 Build & Dependency Delta Cache.
 * <p>
 * Caches Maven build model resolutions, Maven dependency inputs, and D3-descriptor-keyed Gradle
 * passive dependency resolutions while enforcing SHA-256 verification of all referenced artifact
 * paths (hashed at most once per unique path per run via a bounded verification memo).
 * <p>
 * Soundness invariant: never reuses a cached result whose underlying build descriptors, policy,
 * or verified artifact bytes changed. Cross-snapshot Gradle reuse deterministically rebinds each
 * {@link GradleDependencyArtifactResolver.Context#identity()} to the new {@link UniversalBuildModel#identity()}
 * only when the exact hash preimage is 100% known (zero multi-context AAR ambiguity).
 */
public final class BuildDeltaCache {
    private static final int MAX_MEM_ENTRIES = 64;

    public record Stats(
            int reusedBuildRoots,
            int recomputedBuildRoots,
            int gradleResolverReused,
            int gradleResolverRecomputed) {}

    private record CachedGradleRecovery(
            GradleDependencyArtifactResolver.Result result,
            Map<String, List<BinaryInput>> localJarsByPath,
            Optional<BinaryInput> androidPlatformJar,
            boolean hasAarArtifacts) {}

    private final Path root;
    private final Map<ContentDigest, List<MavenBuildModelResolution>> mavenModelsCache;
    private final Map<ContentDigest, MavenScan.Inputs> mavenInputsCache;
    private final Map<ContentDigest, CachedGradleRecovery> gradleRecoveryCache;
    private final ConcurrentHashMap<Path, ContentDigest> runVerifiedDigests;
    private final AtomicInteger reusedBuildRoots;
    private final AtomicInteger recomputedBuildRoots;
    private final AtomicInteger gradleResolverReused;
    private final AtomicInteger gradleResolverRecomputed;

    public BuildDeltaCache(Path cacheRoot) {
        this.root = Objects.requireNonNull(cacheRoot).toAbsolutePath().normalize().resolve("build-delta");
        this.mavenModelsCache = lruMap();
        this.mavenInputsCache = lruMap();
        this.gradleRecoveryCache = lruMap();
        this.runVerifiedDigests = new ConcurrentHashMap<>();
        this.reusedBuildRoots = new AtomicInteger();
        this.recomputedBuildRoots = new AtomicInteger();
        this.gradleResolverReused = new AtomicInteger();
        this.gradleResolverRecomputed = new AtomicInteger();
    }

    private BuildDeltaCache(BuildDeltaCache shared, ConcurrentHashMap<Path, ContentDigest> perRunDigests) {
        this.root = shared.root;
        this.mavenModelsCache = shared.mavenModelsCache;
        this.mavenInputsCache = shared.mavenInputsCache;
        this.gradleRecoveryCache = shared.gradleRecoveryCache;
        this.runVerifiedDigests = perRunDigests;
        this.reusedBuildRoots = shared.reusedBuildRoots;
        this.recomputedBuildRoots = shared.recomputedBuildRoots;
        this.gradleResolverReused = shared.gradleResolverReused;
        this.gradleResolverRecomputed = shared.gradleResolverRecomputed;
    }

    /**
     * Returns an isolated per-run view sharing the underlying LRU caches and counters while owning
     * its own thread-safe artifact verification memo so concurrent jobs never stomp each other's digests.
     */
    public BuildDeltaCache forRun() {
        return new BuildDeltaCache(this, new ConcurrentHashMap<>());
    }

    public Path root() {
        return root;
    }

    /** Clears per-run artifact verification memo before starting a new analysis run. */
    public void beginRun() {
        runVerifiedDigests.clear();
    }

    public Stats stats() {
        return new Stats(
                reusedBuildRoots.get(),
                recomputedBuildRoots.get(),
                gradleResolverReused.get(),
                gradleResolverRecomputed.get());
    }

    public void resetStats() {
        reusedBuildRoots.set(0);
        recomputedBuildRoots.set(0);
        gradleResolverReused.set(0);
        gradleResolverRecomputed.set(0);
    }

    /** Computes a canonical D3 descriptor fingerprint across all build descriptors in an acquisition. */
    public static ContentDigest d3DescriptorFingerprint(RepositoryAcquisitionResult acquisition) {
        var descriptors = new TreeMap<String, String>();
        for (AcquiredFile file : acquisition.files()) {
            if (ReadinessDelta.classifyPath(file.path()) == ReadinessDelta.DurabilityClass.D3_BUILD) {
                descriptors.put(file.path(), file.contentDigest().value());
            }
        }
        return CanonicalJson.sha256(List.of("build-d3-fingerprint:v1", descriptors));
    }

    /** Computes a Gradle-specific D3 descriptor + local JAR/AAR fingerprint independent of .java/.kt edits. */
    public static ContentDigest gradleD3Key(
            RepositoryAcquisitionResult acquisition,
            UniversalBuildModel build,
            DependencyAcquisitionPolicy policy,
            Path cache,
            Optional<Path> androidSdk) {
        var descriptors = new TreeMap<String, String>();
        var localArchives = new TreeMap<String, String>();
        for (AcquiredFile file : acquisition.files()) {
            String path = file.path();
            if (path.endsWith(".jar") || path.endsWith(".aar")) {
                localArchives.put(path, file.contentDigest().value());
            } else if (ReadinessDelta.classifyPath(path) == ReadinessDelta.DurabilityClass.D3_BUILD) {
                descriptors.put(path, file.contentDigest().value());
            }
        }
        var moduleSummary = new ArrayList<Object>(build.modules().size());
        for (var m : build.modules()) {
            if (m.tool() == UniversalBuildModel.Tool.GRADLE_GROOVY || m.tool() == UniversalBuildModel.Tool.GRADLE_KOTLIN) {
                moduleSummary.add(List.of(m.descriptor().identity(), m.descriptor().path(), m.tool().name(),
                        m.dependencies(), m.sourcePlan()));
            }
        }
        return CanonicalJson.sha256(List.of(
                "gradle-delta-cache:v1",
                GradleDependencyArtifactResolver.PROVIDER,
                descriptors,
                localArchives,
                moduleSummary,
                policy.identity(),
                cache.toAbsolutePath().normalize().toString(),
                androidSdk.map(p -> p.toAbsolutePath().normalize().toString()).orElse("")));
    }

    Optional<List<MavenBuildModelResolution>> getMavenModels(
            RepositoryAcquisitionResult acquisition,
            Path cache,
            List<URI> repositories) {
        if (Boolean.getBoolean("evo.incremental.disabled") || acquisition.snapshot().isEmpty()) {
            return Optional.empty();
        }
        ContentDigest key = mavenModelsKey(acquisition, cache, repositories);
        List<MavenBuildModelResolution> cached;
        synchronized (mavenModelsCache) {
            cached = mavenModelsCache.get(key);
        }
        if (cached == null) return Optional.empty();
        // Verify all acquired local-cache POM files are still present with matching digest on disk.
        for (var res : cached) {
            if (!res.problems().isEmpty()) {
                synchronized (mavenModelsCache) { mavenModelsCache.remove(key); }
                return Optional.empty();
            }
            for (var pom : res.acquiredPoms()) {
                if (pom.origin() != MavenBuildModelResolution.Origin.LOCAL_CACHE) {
                    synchronized (mavenModelsCache) { mavenModelsCache.remove(key); }
                    return Optional.empty();
                }
                Path pomPath = cache.toAbsolutePath().normalize().resolve(pom.logicalLocation());
                if (!verifyPathDigest(pomPath, pom.contentDigest())) {
                    synchronized (mavenModelsCache) { mavenModelsCache.remove(key); }
                    return Optional.empty();
                }
            }
        }
        reusedBuildRoots.addAndGet(cached.size());
        return Optional.of(cached);
    }

    void putMavenModels(
            RepositoryAcquisitionResult acquisition,
            Path cache,
            List<URI> repositories,
            List<MavenBuildModelResolution> models) {
        recomputedBuildRoots.addAndGet(models.size());
        if (Boolean.getBoolean("evo.incremental.disabled") || acquisition.snapshot().isEmpty()) return;
        ContentDigest key = mavenModelsKey(acquisition, cache, repositories);
        synchronized (mavenModelsCache) {
            mavenModelsCache.put(key, List.copyOf(models));
        }
    }

    Optional<MavenScan.Inputs> getMavenInputs(
            RepositoryAcquisitionResult acquisition,
            List<MavenBuildModelResolution> builds,
            Path cache,
            Optional<PlatformInput> platform,
            DependencyAcquisitionPolicy policy,
            Map<Integer, PlatformInput> platforms) {
        if (Boolean.getBoolean("evo.incremental.disabled") || acquisition.snapshot().isEmpty()) {
            return Optional.empty();
        }
        ContentDigest key = mavenInputsKey(acquisition, builds, cache, platform, policy, platforms);
        MavenScan.Inputs cached;
        synchronized (mavenInputsCache) {
            cached = mavenInputsCache.get(key);
        }
        if (cached == null) return Optional.empty();
        for (var res : cached.resolutions().values()) {
            for (var binary : res.binaries()) {
                if (!verifyPathDigest(binary.path(), binary.entry().contentDigest())) {
                    synchronized (mavenInputsCache) { mavenInputsCache.remove(key); }
                    return Optional.empty();
                }
            }
        }
        return Optional.of(cached);
    }

    void putMavenInputs(
            RepositoryAcquisitionResult acquisition,
            List<MavenBuildModelResolution> builds,
            Path cache,
            Optional<PlatformInput> platform,
            DependencyAcquisitionPolicy policy,
            Map<Integer, PlatformInput> platforms,
            MavenScan.Inputs inputs) {
        if (Boolean.getBoolean("evo.incremental.disabled") || acquisition.snapshot().isEmpty()) return;
        ContentDigest key = mavenInputsKey(acquisition, builds, cache, platform, policy, platforms);
        synchronized (mavenInputsCache) {
            mavenInputsCache.put(key, inputs);
        }
    }

    /**
     * Retrieves and deterministically rebinds a D3-keyed Gradle passive dependency resolution to
     * {@code currentBuild}, avoiding re-parsing .m2 POMs and re-validating JARs across Java/Kotlin edits.
     */
    Optional<GradleDependencyArtifactResolver.Result> getGradleResolution(
            ContentDigest d3Key,
            UniversalBuildModel currentBuild) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return Optional.empty();
        CachedGradleRecovery cached;
        synchronized (gradleRecoveryCache) {
            cached = gradleRecoveryCache.get(d3Key);
        }
        if (cached == null) return Optional.empty();
        // Verify every referenced binary artifact still matches its cryptographic digest.
        for (var ctx : cached.result().contexts()) {
            for (var bin : ctx.binaries()) {
                if (!verifyPathDigest(bin.path(), bin.entry().contentDigest())) {
                    synchronized (gradleRecoveryCache) { gradleRecoveryCache.remove(d3Key); }
                    return Optional.empty();
                }
            }
        }
        // Soundness guard: if AARs are present and there are >1 contexts, only reuse on exact build identity match
        // because per-context aarDigests subsets cannot be inferred unambiguously across contexts.
        boolean sameModelIdentity = cached.result().contexts().stream().allMatch(c ->
                c.identity().equals(recomputeGradleContextProof(currentBuild, c)));
        if (cached.hasAarArtifacts() && !sameModelIdentity) {
            return Optional.empty();
        }
        if (sameModelIdentity) {
            gradleResolverReused.incrementAndGet();
            return Optional.of(cached.result());
        }
        // Zero AARs: rebind Context.identity() deterministically to currentBuild.identity() (exact match to GradleDependencyArtifactResolver:210).
        var reboundContexts = new ArrayList<GradleDependencyArtifactResolver.Context>(cached.result().contexts().size());
        for (var c : cached.result().contexts()) {
            var newProof = recomputeGradleContextProof(currentBuild, c);
            reboundContexts.add(new GradleDependencyArtifactResolver.Context(
                    c.sourceSet(), c.binaries(), c.projects(), c.problems(), c.metadata(), newProof));
        }
        gradleResolverReused.incrementAndGet();
        return Optional.of(new GradleDependencyArtifactResolver.Result(
                reboundContexts, cached.result().acquisitions(), cached.result().problems()));
    }

    private static ContentDigest recomputeGradleContextProof(
            UniversalBuildModel build,
            GradleDependencyArtifactResolver.Context c) {
        var metaMap = new TreeMap<String, BuildModelResult.PomEvidence>();
        for (var ev : c.metadata()) {
            metaMap.put(ev.logicalId(), ev);
        }
        return CanonicalJson.sha256(List.of(
                GradleDependencyArtifactResolver.PROVIDER,
                build.identity(),
                c.sourceSet(),
                c.binaries().stream().map(BinaryInput::entry).toList(),
                c.projects(),
                c.problems(),
                metaMap,
                Map.of()));
    }

    void putGradleResolution(ContentDigest d3Key, GradleDependencyArtifactResolver.Result result) {
        gradleResolverRecomputed.incrementAndGet();
        if (Boolean.getBoolean("evo.incremental.disabled")) return;
        boolean hasAar = result.acquisitions().stream()
                .flatMap(a -> a.outcomes().stream())
                .anyMatch(o -> o.requirement().coordinate().extension().equalsIgnoreCase("aar"));
        synchronized (gradleRecoveryCache) {
            gradleRecoveryCache.put(d3Key, new CachedGradleRecovery(result, Map.of(), Optional.empty(), hasAar));
        }
    }

    /** Verifies a file's SHA-256 digest, memoizing the result per unique normalized path within the current run. */
    public boolean verifyPathDigest(Path path, ContentDigest expected) {
        if (path == null || expected == null) return false;
        Path norm = path.toAbsolutePath().normalize();
        ContentDigest memo = runVerifiedDigests.get(norm);
        if (memo != null) return expected.equals(memo);
        try {
            if (!Files.isRegularFile(norm, LinkOption.NOFOLLOW_LINKS)) return false;
            ContentDigest actual = sha256File(norm);
            runVerifiedDigests.put(norm, actual);
            return expected.equals(actual);
        } catch (IOException unavailable) {
            return false;
        }
    }

    public Map<Path, ContentDigest> runVerifiedDigestMemo() {
        return runVerifiedDigests;
    }

    private static ContentDigest mavenModelsKey(
            RepositoryAcquisitionResult acquisition,
            Path cache,
            List<URI> repositories) {
        var pomDigests = new TreeMap<String, String>();
        acquisition.workspacePoms().forEach((k, v) -> pomDigests.put(k, v.digest().value()));
        return CanonicalJson.sha256(List.of(
                "maven-models-cache:v1",
                acquisition.snapshot().orElseThrow().identity(),
                pomDigests,
                MavenScan.POLICY,
                MavenScan.pomPolicy(repositories).identity(),
                cache.toAbsolutePath().normalize().toString()));
    }

    private static ContentDigest mavenInputsKey(
            RepositoryAcquisitionResult acquisition,
            List<MavenBuildModelResolution> builds,
            Path cache,
            Optional<PlatformInput> platform,
            DependencyAcquisitionPolicy policy,
            Map<Integer, PlatformInput> platforms) {
        var platformViews = new TreeMap<String, ContentDigest>();
        platforms.forEach((rel, p) -> platformViews.put(String.valueOf(rel), p.entry().contentDigest()));
        return CanonicalJson.sha256(List.of(
                "maven-inputs-cache:v1",
                acquisition.identity(),
                acquisition.snapshot().orElseThrow().identity(),
                builds.stream().map(MavenBuildModelResolution::identity).toList(),
                platform.map(p -> p.entry().contentDigest().value()).orElse(""),
                platformViews,
                policy.identity(),
                cache.toAbsolutePath().normalize().toString()));
    }

    private static ContentDigest sha256File(Path path) throws IOException {
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        byte[] buf = new byte[64 * 1024];
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest()));
    }

    private static <K, V> Map<K, V> lruMap() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > MAX_MEM_ENTRIES;
            }
        };
    }
}
