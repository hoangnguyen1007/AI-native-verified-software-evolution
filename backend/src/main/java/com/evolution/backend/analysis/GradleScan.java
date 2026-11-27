package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.BinaryInput;
import com.evolution.analysis.frontend.PlatformInput;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.maven.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/**
 * Product composition for passive Gradle recovery. Target settings/plugins
 * remain data.
 */
public final class GradleScan {
    private GradleScan() {
    }

    public static Optional<Path> discoverAndroidSdk() {
        var candidates = new LinkedHashSet<String>();
        addSdkCandidate(candidates, System.getProperty("evo.android.sdk"));
        addSdkCandidate(candidates, System.getenv("ANDROID_SDK_ROOT"));
        addSdkCandidate(candidates, System.getenv("ANDROID_HOME"));
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(Path.of(localAppData, "Android", "Sdk").toString());
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            candidates.add(Path.of(userHome, "AppData", "Local", "Android", "Sdk").toString());
            candidates.add(Path.of(userHome, "Android", "Sdk").toString());
            candidates.add(Path.of(userHome, "Library", "Android", "sdk").toString());
        }
        for (String raw : candidates) {
            Path path = Path.of(raw).toAbsolutePath().normalize();
            if (!isSafeDirectory(path))
                continue;
            Path platforms = path.resolve("platforms");
            if (Files.isDirectory(platforms, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(platforms)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    private static void addSdkCandidate(Set<String> target, String value) {
        if (value != null && !value.isBlank())
            target.add(value.strip());
    }

    private static boolean isSafeDirectory(Path candidate) {
        if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
            return false;
        for (Path p = candidate; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p))
                return false;
        }
        return true;
    }

    public static Optional<UniversalBuildModel> model(RepositoryAcquisitionResult acquisition,
            UniversalBuildIngestion.Policy policy,
            RepositoryInputDecoder.Policy decoding, Map<MavenCoordinate, PomInput> poms) {
        return model(new RepositoryInputDecoder.Prepared(acquisition, decoding), policy, poms);
    }

    public static Optional<UniversalBuildModel> model(RepositoryInputDecoder.Prepared prepared,
            UniversalBuildIngestion.Policy policy,
            Map<MavenCoordinate, PomInput> poms) {
        if (prepared.acquisition().files().stream()
                .noneMatch(f -> f.path().endsWith("build.gradle") || f.path().endsWith("build.gradle.kts")
                        || f.path().endsWith("settings.gradle") || f.path().endsWith("settings.gradle.kts")))
            return Optional.empty();
        return prepared.result().inputs().map(inputs -> new UniversalBuildIngestion().ingest(inputs, policy,
                Optional.of(new MavenBuildModelProvider()), poms, MavenScan.POLICY));
    }

    public static Set<Integer> platformReleases(Optional<UniversalBuildModel> model) {
        var releases = new TreeSet<Integer>();
        model.ifPresent(value -> value.modules().stream().filter(m -> m.tool() != UniversalBuildModel.Tool.MAVEN)
                .flatMap(m -> m.sourcePlan().sourceSets().stream())
                .flatMap(s -> s.platformRelease().value().stream()).forEach(v -> {
                    try {
                        releases.add(Integer.parseInt(v));
                    } catch (NumberFormatException unavailable) {
                        /* Assembly retains the compiler gap. */}
                }));
        return Set.copyOf(releases);
    }

    public static MavenScan.Inputs augment(MavenScan.Inputs prior, RepositoryAcquisitionResult acquisition,
            Optional<UniversalBuildModel> model, Path cache,
            Optional<PlatformInput> fallback, DependencyAcquisitionPolicy policy,
            Map<Integer, PlatformInput> platforms) {
        return augment(prior, acquisition, model, cache, fallback, policy, platforms, discoverAndroidSdk());
    }

    public static MavenScan.Inputs augment(MavenScan.Inputs prior, RepositoryAcquisitionResult acquisition,
            Optional<UniversalBuildModel> model, Path cache,
            Optional<PlatformInput> fallback, DependencyAcquisitionPolicy policy, Map<Integer, PlatformInput> platforms,
            Optional<Path> androidSdk) {
        return augment(prior, acquisition, model, cache, fallback, policy, platforms, androidSdk, null);
    }

    public static MavenScan.Inputs augment(MavenScan.Inputs prior, RepositoryAcquisitionResult acquisition,
            Optional<UniversalBuildModel> model, Path cache,
            Optional<PlatformInput> fallback, DependencyAcquisitionPolicy policy, Map<Integer, PlatformInput> platforms,
            Optional<Path> androidSdk, BuildDeltaCache deltaCache) {
        if (model.isEmpty())
            return prior;
        MavenScan.prepareCache(cache, policy.remoteRepositories());
        var build = model.orElseThrow();
        GradleDependencyArtifactResolver.Result result;
        if (deltaCache != null) {
            var d3Key = BuildDeltaCache.gradleD3Key(acquisition, build, policy, cache, androidSdk);
            var cached = deltaCache.getGradleResolution(d3Key, build);
            if (cached.isPresent()) {
                result = cached.orElseThrow();
            } else {
                result = new GradleDependencyArtifactResolver(cache).resolve(build, policy);
                deltaCache.putGradleResolution(d3Key, result);
            }
        } else {
            result = new GradleDependencyArtifactResolver(cache).resolve(build, policy);
        }
        var resolutions = new HashMap<>(prior.resolutions());
        var gaps = new TreeSet<>(prior.gaps());
        var ledgers = new ArrayList<>(prior.ledgers());
        var context = EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity());
        var ledger = CapabilityGapNormalizer.normalize(context,
                EvidenceNormalizationInput.builder().dependencyAcquisitions(result.acquisitions()).build());
        ledgers.add(ledger);
        gaps.addAll(ledger.gaps());
        var reasons = new TreeMap<String, Long>();
        long recovered = 0;
        var localJars = new HashMap<String, com.evolution.analysis.acquisition.AcquiredFile>();
        for (var file : acquisition.files())
            if (file.path().endsWith(".jar") || file.path().endsWith(".aar"))
                localJars.put(file.path(), file);
        long remainingLocalBudget = policy.maxTotalBytes();
        var allBinaries = new ArrayList<BinaryInput>();
        for (var row : result.contexts()) {
            var contextBinaries = new ArrayList<>(row.binaries());
            var existingDigests = new HashSet<ContentDigest>();
            contextBinaries.forEach(b -> existingDigests.add(b.entry().contentDigest()));
            var localAdded = new ArrayList<BinaryInput>();
            var localPaths = new LinkedHashSet<String>();
            build.modules().stream().filter(m -> m.descriptor().identity().equals(row.sourceSet().module())).findFirst()
                    .ifPresent(m -> collectLocalJars(m, row.sourceSet().kind(), false, localPaths));
            for (var peerId : row.projects()) {
                build.modules().stream().filter(m -> m.descriptor().identity().equals(peerId)).findFirst()
                        .ifPresent(m -> collectLocalJars(m, SourcePlanModel.Kind.MAIN, true, localPaths));
            }
            for (String jarPath : localPaths) {
                var capturedJar = localJars.get(jarPath);
                if (capturedJar == null) {
                    var problem = new GradleDependencyArtifactResolver.Problem("GRADLE_BINARY_UNAVAILABLE",
                            "local-jar:" + jarPath);
                    reasons.merge(problem.reason(), 1L, Long::sum);
                    gaps.add(gap(context, build, row.identity(), problem));
                    continue;
                }
                try {
                    var recoveredLocals = GradleLocalJarInputs.recoverAll(cache, jarPath, capturedJar.bytes(),
                            capturedJar.contentDigest(),
                            policy.maxArtifactBytes(), Math.max(0L, remainingLocalBudget));
                    for (var binary : recoveredLocals) {
                        remainingLocalBudget = Math.max(0L, remainingLocalBudget - Files.size(binary.path()));
                        if (existingDigests.add(binary.entry().contentDigest())) {
                            contextBinaries.add(binary);
                            localAdded.add(binary);
                        }
                    }
                } catch (IOException unavailable) {
                    var problem = new GradleDependencyArtifactResolver.Problem("GRADLE_BINARY_UNAVAILABLE",
                            "local-jar:" + jarPath);
                    reasons.merge(problem.reason(), 1L, Long::sum);
                    gaps.add(gap(context, build, row.identity(), problem));
                }
            }
            var set = build.modules().stream().flatMap(m -> m.sourcePlan().sourceSets().stream())
                    .filter(s -> s.module().equals(row.sourceSet().module()) && s.kind() == row.sourceSet().kind()
                            && s.name().equals(row.sourceSet().name()))
                    .findFirst().orElseThrow();
            OptionalInt compileSdk = findAndroidCompileSdk(build, set, row.projects());
            if ((compileSdk.isPresent() || isAndroidModule(build, set, row.projects())) && androidSdk.isPresent()) {
                try {
                    var androidJar = GradleLocalJarInputs.recoverAndroidPlatformJar(
                            cache, androidSdk.orElseThrow(), compileSdk,
                            policy.maxArtifactBytes(), Math.max(0L, remainingLocalBudget));
                    if (androidJar.isPresent()) {
                        var binary = androidJar.orElseThrow();
                        remainingLocalBudget = Math.max(0L, remainingLocalBudget - Files.size(binary.path()));
                        if (existingDigests.add(binary.entry().contentDigest())) {
                            contextBinaries.add(binary);
                            localAdded.add(binary);
                        }
                    } else {
                        var problem = new GradleDependencyArtifactResolver.Problem("GRADLE_BINARY_UNAVAILABLE",
                                "android-sdk:android.jar");
                        reasons.merge(problem.reason(), 1L, Long::sum);
                        gaps.add(gap(context, build, row.identity(), problem));
                    }
                } catch (IOException unavailable) {
                    var problem = new GradleDependencyArtifactResolver.Problem("GRADLE_BINARY_UNAVAILABLE",
                            "android-sdk:android.jar");
                    reasons.merge(problem.reason(), 1L, Long::sum);
                    gaps.add(gap(context, build, row.identity(), problem));
                }
            }
            recovered += contextBinaries.size();
            allBinaries.addAll(contextBinaries);
            var effectiveIdentity = localAdded.isEmpty() ? row.identity()
                    : com.evolution.analysis.contract.serialization.CanonicalJson.sha256(
                            List.of(row.identity(), localAdded.stream().map(BinaryInput::entry).toList()));
            var platform = set.platformRelease().value().flatMap(v -> {
                try {
                    return Optional.ofNullable(platforms.get(Integer.parseInt(v)));
                } catch (NumberFormatException invalid) {
                    return Optional.empty();
                }
            }).or(() -> fallback);
            if (platform.isPresent()) {
                var peers = new LinkedHashSet<UniversalSourceIngestion.SourceSet>();
                if (row.sourceSet().kind() == SourcePlanModel.Kind.TEST)
                    peers.add(new UniversalSourceIngestion.SourceSet(row.sourceSet().module(),
                            SourcePlanModel.Kind.MAIN));
                for (var project : row.projects())
                    peers.add(new UniversalSourceIngestion.SourceSet(project, SourcePlanModel.Kind.MAIN));
                var slots = new ArrayList<UniversalSourceIngestion.ReactorSlot>();
                for (var peer : peers)
                    slots.add(new UniversalSourceIngestion.ReactorSlot(peer, slots.size()));
                var resolution = new UniversalSourceIngestion.Resolution(platform.orElseThrow(), contextBinaries,
                        effectiveIdentity, Optional.empty(), false, slots);
                boolean gradleSelected = build.modules().stream()
                        .anyMatch(m -> m.descriptor().identity().equals(row.sourceSet().module())
                                && (m.tool() == UniversalBuildModel.Tool.GRADLE_GROOVY
                                        || m.tool() == UniversalBuildModel.Tool.GRADLE_KOTLIN));
                if (gradleSelected)
                    resolutions.put(row.sourceSet(), resolution);
                else {
                    var existing = resolutions.putIfAbsent(row.sourceSet(), resolution);
                    if (existing != null && !existing.equals(resolution)) {
                        resolutions.remove(row.sourceSet());
                        gaps.add(gap(context, build, row.identity(), new GradleDependencyArtifactResolver.Problem(
                                "GRADLE_SOURCE_SET_CONFLICT", row.sourceSet().toString())));
                    }
                }
            }
            for (var problem : row.problems()) {
                reasons.merge(problem.reason(), 1L, Long::sum);
                gaps.add(gap(context, build, row.identity(), problem));
            }
        }
        for (var problem : result.problems()) {
            reasons.merge(problem.reason(), 1L, Long::sum);
            gaps.add(gap(context, build, build.identity(), problem));
        }
        long unique = allBinaries.stream().map(BinaryInput::entry).distinct().count();
        var summary = new TreeMap<>(prior.summary());
        summary.put("gradle",
                Map.of("provider", GradleDependencyArtifactResolver.PROVIDER, "model", build.identity(), "contexts",
                        result.contexts().size(), "binaryInputs", recovered, "uniqueBinaryArtifacts", unique,
                        "problemsByReason", reasons,
                        "trust", "PASSIVE_RECOVERY", "effectiveGradleContext", false, "acquisitionReceipts",
                        result.acquisitions().stream().map(a -> a.identity()).toList()));
        if (prior.resolutions().isEmpty())
            summary.put("mode", policy.remoteRepositories().isEmpty() ? "PASSIVE_CACHE_THEN_ISOLATED_REQUIREMENT"
                    : "EXPLICIT_ARTIFACT_ACQUISITION");
        return new MavenScan.Inputs(Map.copyOf(resolutions), prior.externalPoms(), List.copyOf(gaps),
                Map.copyOf(summary), List.copyOf(ledgers));
    }

    private static OptionalInt findAndroidCompileSdk(
            UniversalBuildModel build,
            SourcePlanModel.SourceSetPlan set,
            List<com.evolution.analysis.contract.identity.ModuleIdentity> projects) {
        var own = set.compilerSettings().get("android.compileSdk");
        if (own != null && own.value().isPresent()) {
            try {
                return OptionalInt.of(Integer.parseInt(own.value().orElseThrow()));
            } catch (NumberFormatException ignored) {
                /* Preserve fallback to sibling/plugin check. */ }
        }
        for (var peerId : projects) {
            for (var module : build.modules()) {
                if (!module.descriptor().identity().equals(peerId))
                    continue;
                for (var peerSet : module.sourcePlan().sourceSets()) {
                    if (peerSet.kind() != SourcePlanModel.Kind.MAIN)
                        continue;
                    var peerSdk = peerSet.compilerSettings().get("android.compileSdk");
                    if (peerSdk != null && peerSdk.value().isPresent()) {
                        try {
                            return OptionalInt.of(Integer.parseInt(peerSdk.value().orElseThrow()));
                        } catch (NumberFormatException ignored) {
                            /* Continue scanning peers. */ }
                    }
                }
            }
        }
        return OptionalInt.empty();
    }

    private static boolean isAndroidModule(
            UniversalBuildModel build,
            SourcePlanModel.SourceSetPlan set,
            List<com.evolution.analysis.contract.identity.ModuleIdentity> projects) {
        var relevant = new HashSet<com.evolution.analysis.contract.identity.ModuleIdentity>(projects);
        relevant.add(set.module());
        for (var module : build.modules()) {
            if (!relevant.contains(module.descriptor().identity()))
                continue;
            for (var plugin : module.sourcePlan().plugins()) {
                String id = plugin.coordinate();
                if (id.startsWith("com.android.") || id.equals("org.jetbrains.kotlin.android"))
                    return true;
            }
        }
        return false;
    }

    private static void collectLocalJars(UniversalBuildModel.Module module, SourcePlanModel.Kind kind, boolean exported,
            Set<String> target) {
        for (var dep : module.dependencies())
            if (dep.configuration().endsWith(".local-file")) {
                String baseConfig = dep.configuration().substring(0,
                        dep.configuration().length() - ".local-file".length());
                if (GradleDependencyArtifactResolver.visible(baseConfig, kind, exported))
                    target.add(dep.notation());
            }
    }

    private static CapabilityGapRecord gap(EvidenceContext context, UniversalBuildModel model, ContentDigest proof,
            GradleDependencyArtifactResolver.Problem problem) {
        var subject = new EvidenceSubject(EvidenceSubject.Kind.SOURCE_SET, problem.subject());
        var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT,
                "gradle.effective-context", List.of(subject), EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,
                List.of("Capture the selected Gradle variant, exact ordered classpath, constraints, plugin effects and generated outputs in the registered isolated executor; passive JAR recovery does not establish this context."));
        return CapabilityGapRecord.create(context, GradleDependencyArtifactResolver.PROVIDER, "build.gradle-recovery",
                problem.reason(), subject, List.of(),
                List.of(ProviderObservationReference.create(GradleDependencyArtifactResolver.PROVIDER,
                        "gradle.passive-context", model.identity(), proof)),
                List.of(requirement), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "build.gradle-recovery")), List.of(),
                List.of(),
                List.of("Verified available binaries can support recovery while this obligation remains open."));
    }
}
