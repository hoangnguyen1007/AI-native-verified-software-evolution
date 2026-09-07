package com.evolution.analysis.input;

import com.evolution.analysis.acquisition.CandidateSourceOwnership;
import com.evolution.analysis.buildmodel.BuildModelResult;
import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.classpath.ExactClasspathResult;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.frontend.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Deterministic assembly from decoded source, platform and exact binary evidence only. */
public final class FrontendInputAssembler {
    private FrontendInputAssembler() {}

    public static FrontendAssemblyResult assemble(
            CandidateSourceOwnership ownership,
            BuildModelResult buildModel,
            com.evolution.analysis.classpath.ClasspathResolutionRequest classpathRequest,
            ExactClasspathResult classpaths,
            SourceDecodingResult decoding,
            PlatformSymbolResult platformResult,
            List<BinaryInput> dependencyInputs,
            List<ReactorOutputInput> reactorOutputs,
            FrontendAssemblyPolicy policy) {
        Objects.requireNonNull(ownership); Objects.requireNonNull(buildModel);
        Objects.requireNonNull(classpathRequest); Objects.requireNonNull(classpaths);
        Objects.requireNonNull(decoding); Objects.requireNonNull(platformResult);
        Objects.requireNonNull(dependencyInputs); Objects.requireNonNull(reactorOutputs); Objects.requireNonNull(policy);
        if (!ownership.buildModel().equals(buildModel.identity())
                || !decoding.buildModelIdentity().equals(buildModel.identity())
                || !decoding.ownershipIdentity().equals(ownership.identity())
                || !classpathRequest.buildModel().identity().equals(buildModel.identity())
                || !classpathRequest.identity().equals(classpaths.requestIdentity())) {
            throw new IllegalArgumentException("Frontend assembly inputs belong to different workspace evidence");
        }

        Map<ModuleIdentity, BuildModelResult.ModuleModel> modules = buildModel.modules().stream()
                .collect(Collectors.toUnmodifiableMap(value -> value.module().identity(), Function.identity()));
        Map<com.evolution.analysis.contract.analysis.ClasspathEntry, BinaryInput> dependencies = unique(
                dependencyInputs, BinaryInput::entry, "dependency binary");
        Map<String, ReactorOutputInput> reactor = unique(reactorOutputs,
                value -> reactorKey(value.module(), value.sourceSet()), "reactor output");
        Set<ClasspathEntry> referencedDependencies = classpaths.manifests().stream()
                .flatMap(value -> value.entries().stream()).map(ExactClasspathResult.Entry::classpathEntry)
                .collect(Collectors.toSet());
        boolean hasExtraDependency = dependencyInputs.stream().anyMatch(value -> !referencedDependencies.contains(value.entry()));
        Set<String> referencedReactors = classpaths.manifests().stream().flatMap(value -> value.reactorEntries().stream())
                .map(value -> reactorKey(value.module(), value.targetSourceSet())).collect(Collectors.toSet());
        boolean hasExtraReactor = reactorOutputs.stream()
                .anyMatch(value -> !referencedReactors.contains(reactorKey(value.module(), value.sourceSet())));

        List<FrontendAssemblyResult.Outcome> results = new ArrayList<>();
        for (ExactClasspathResult.Manifest manifest : classpaths.manifests()) {
            List<FrontendAssemblyResult.Problem> problems = new ArrayList<>();
            BuildModelResult.ModuleModel module = modules.get(manifest.module());
            Optional<SourcePlanModel.SourceSetPlan> sourcePlan = sourcePlan(module, manifest.sourceSet());
            if (sourcePlan.isEmpty()) add(problems, FrontendAssemblyResult.Reason.MISSING_SOURCE_PLAN,
                    manifest.module().value(), FrontendAssemblyResult.Requirement.BUILD_SOURCE_PLAN);
            sourcePlan.ifPresent(plan -> {
                if (!usableInteger(plan.bytecodeTarget()) || invalidBoolean(plan.compilerSettings().get("enablePreview"))) {
                    add(problems, FrontendAssemblyResult.Reason.INVALID_SOURCE_PLAN,
                            manifest.module().value() + ":" + manifest.sourceSet(),
                            FrontendAssemblyResult.Requirement.BUILD_SOURCE_PLAN);
                }
                if (plan.platformRelease().status() != SourcePlanModel.Status.UNSPECIFIED
                        && plan.platformRelease().value().isEmpty()) {
                    add(problems, FrontendAssemblyResult.Reason.INVALID_SOURCE_PLAN,
                            plan.platformRelease().expression().orElse("platform-release"),
                            FrontendAssemblyResult.Requirement.BUILD_SOURCE_PLAN);
                }
                Optional<Integer> syntax = integer(plan.syntaxLevel());
                if (syntax.isEmpty() || syntax.orElseThrow() < 8 || syntax.orElseThrow() > 21) {
                    add(problems, FrontendAssemblyResult.Reason.UNSUPPORTED_SYNTAX_LEVEL,
                            plan.syntaxLevel().expression().orElse("unspecified"),
                            FrontendAssemblyResult.Requirement.BUILD_SOURCE_PLAN);
                }
                if (previewEnabled(plan)) {
                    add(problems, FrontendAssemblyResult.Reason.PREVIEW_UNSUPPORTED,
                            manifest.module().value() + ":" + manifest.sourceSet(),
                            FrontendAssemblyResult.Requirement.BUILD_SOURCE_PLAN);
                }
            });

            List<SourceInput> sources = decodedSources(ownership, decoding, manifest, problems);
            Optional<PlatformInput> platform = platformResult.platform();
            if (platform.isEmpty()) add(problems, FrontendAssemblyResult.Reason.PLATFORM_UNAVAILABLE,
                    "release", FrontendAssemblyResult.Requirement.PLATFORM_SYMBOLS);
            if (platform.isPresent() && sourcePlan.isPresent() && sourcePlan.get().platformRelease().value().isPresent()) {
                String declared = sourcePlan.get().platformRelease().value().orElseThrow();
                if (!declared.equals(Integer.toString(platform.get().release()))) {
                    add(problems, FrontendAssemblyResult.Reason.PLATFORM_RELEASE_MISMATCH,
                            declared, FrontendAssemblyResult.Requirement.PLATFORM_SYMBOLS);
                }
            }

            Map<Integer, BinaryInput> ordered = new TreeMap<>();
            for (ExactClasspathResult.Entry entry : manifest.entries()) {
                BinaryInput input = dependencies.get(entry.classpathEntry());
                if (input == null) add(problems, FrontendAssemblyResult.Reason.MISSING_DEPENDENCY_BINARY,
                        entry.coordinate().notation(), FrontendAssemblyResult.Requirement.DEPENDENCY_ARTIFACT);
                else ordered.put(entry.order(), input);
            }
            for (ExactClasspathResult.ReactorEntry entry : manifest.reactorEntries()) {
                ReactorOutputInput output = reactor.get(reactorKey(entry.module(), entry.targetSourceSet()));
                if (output == null) {
                    add(problems, FrontendAssemblyResult.Reason.MISSING_REACTOR_OUTPUT,
                            entry.coordinate().notation(), FrontendAssemblyResult.Requirement.REACTOR_OUTPUT);
                } else if (entry.outputDirectory().isEmpty()
                        || !entry.outputDirectory().orElseThrow().equals(output.outputDirectory())) {
                    add(problems, FrontendAssemblyResult.Reason.REACTOR_OUTPUT_MISMATCH,
                            entry.coordinate().notation(), FrontendAssemblyResult.Requirement.REACTOR_OUTPUT);
                } else ordered.put(entry.order(), output.binary());
            }
            manifest.problems().stream()
                    .filter(problem -> problem.reason() != ExactClasspathResult.Reason.REACTOR_OUTPUT_NOT_ACQUIRED)
                    .forEach(problem -> add(problems, FrontendAssemblyResult.Reason.CLASSPATH_PROBLEM,
                            problem.reason() + ":" + problem.subject(), FrontendAssemblyResult.Requirement.EXACT_CLASSPATH));
            if (hasExtraDependency || hasExtraReactor) add(problems,
                    FrontendAssemblyResult.Reason.UNREFERENCED_BINARY_INPUT, "binary-inputs",
                    FrontendAssemblyResult.Requirement.INPUT_CONFIGURATION);

            if (!problems.isEmpty() || sourcePlan.isEmpty() || platform.isEmpty()) {
                results.add(new FrontendAssemblyResult.Outcome(manifest.module(), manifest.sourceSet(),
                        FrontendAssemblyResult.Status.WITHHELD, Optional.empty(), problems.stream().distinct().toList()));
                continue;
            }
            SourcePlanModel.SourceSetPlan plan = sourcePlan.orElseThrow();
            FrontendPlan frontendPlan = new FrontendPlan(integer(plan.syntaxLevel()), integer(plan.bytecodeTarget()),
                    previewEnabled(plan), manifest.identity(), decoding.identity());
            SourceClassification classification = manifest.sourceSet() == SourcePlanModel.Kind.MAIN
                    ? SourceClassification.MAIN : SourceClassification.TEST;
            List<com.evolution.analysis.contract.source.SourceDocument> documents = sources.stream()
                    .map(SourceInput::document).toList();
            Map<String, String> options = FrontendRequest.options(frontendPlan, manifest.module(), classification,
                    documents, platform.orElseThrow());
            AnalysisManifest analysisManifest = AnalysisManifest.create(policy.manifestVersion(), decoding.snapshot(),
                    buildModel.modules().stream().map(BuildModelResult.ModuleModel::module).toList(),
                    concat(platform.orElseThrow().entry(), ordered.values()),
                    AnalysisConfiguration.create(policy.configurationSchema(), options),
                    policy.analyzer(), policy.ruleSet(), policy.graphSchema());
            FrontendRequest request = new FrontendRequest(analysisManifest, manifest.module(), classification,
                    frontendPlan, sources, platform.orElseThrow(), List.copyOf(ordered.values()));
            results.add(new FrontendAssemblyResult.Outcome(manifest.module(), manifest.sourceSet(),
                    FrontendAssemblyResult.Status.ASSEMBLED, Optional.of(request), List.of()));
        }
        List<ReactorView> reactorViews = reactorOutputs.stream().map(value -> new ReactorView(
                value.module(), value.sourceSet(), value.outputDirectory(), value.binary().entry()))
                .sorted(Comparator.comparing(CanonicalJson::write)).toList();
        List<ClasspathEntry> dependencyViews = dependencyInputs.stream().map(BinaryInput::entry)
                .sorted().toList();
        ContentDigest inputIdentity = ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", "frontend-input-assembly-request-v1",
                "ownership", ownership.identity(), "buildModel", buildModel.identity(),
                "classpathRequest", classpathRequest.identity(), "classpaths", classpaths.identity(),
                "decoding", decoding.identity(), "platform", platformResult.identity(),
                "dependencies", dependencyViews,
                "reactorOutputs", reactorViews, "policy", policy)));
        return FrontendAssemblyResult.create(inputIdentity, results);
    }

    private static List<SourceInput> decodedSources(
            CandidateSourceOwnership ownership,
            SourceDecodingResult decoding,
            ExactClasspathResult.Manifest manifest,
            List<FrontendAssemblyResult.Problem> problems) {
        Map<String, SourceDecodingResult.Outcome> outcomes = decoding.outcomes().stream()
                .collect(Collectors.toUnmodifiableMap(SourceDecodingResult.Outcome::path, Function.identity()));
        List<SourceInput> result = new ArrayList<>();
        ownership.candidates().stream().filter(candidate -> candidate.claims().stream().anyMatch(claim ->
                        claim.module().equals(manifest.module()) && claim.sourceSet() == manifest.sourceSet()))
                .forEach(candidate -> {
                    SourceDecodingResult.Outcome outcome = outcomes.get(candidate.path());
                    if (outcome == null || outcome.status() == SourceDecodingResult.Status.WITHHELD) {
                        add(problems, FrontendAssemblyResult.Reason.MISSING_SOURCE_INPUT, candidate.path(),
                                FrontendAssemblyResult.Requirement.DECODED_SOURCE);
                    } else if (outcome.status() == SourceDecodingResult.Status.INVALID) {
                        add(problems, FrontendAssemblyResult.Reason.INVALID_SOURCE_INPUT, candidate.path(),
                                FrontendAssemblyResult.Requirement.DECODED_SOURCE);
                    } else result.add(outcome.input().orElseThrow());
                });
        return result.stream().sorted(Comparator.comparing(value -> value.document().path())).toList();
    }

    private static Optional<SourcePlanModel.SourceSetPlan> sourcePlan(
            BuildModelResult.ModuleModel module, SourcePlanModel.Kind kind) {
        return module == null ? Optional.empty() : module.effectivePom().stream()
                .flatMap(pom -> pom.sourcePlan().sourceSets().stream()).filter(value -> value.kind() == kind).findFirst();
    }

    private static Optional<Integer> integer(SourcePlanModel.Setting setting) {
        return setting.value().map(Integer::parseInt);
    }

    private static boolean usableInteger(SourcePlanModel.Setting setting) {
        if (setting.value().isEmpty()) return true;
        try { return Integer.parseInt(setting.value().orElseThrow()) > 0; }
        catch (NumberFormatException exception) { return false; }
    }

    private static boolean invalidBoolean(SourcePlanModel.Setting setting) {
        return setting != null && setting.value().isPresent()
                && !Set.of("true", "false").contains(setting.value().orElseThrow());
    }

    private static boolean previewEnabled(SourcePlanModel.SourceSetPlan plan) {
        SourcePlanModel.Setting setting = plan.compilerSettings().get("enablePreview");
        return setting != null && setting.value().map(Boolean::parseBoolean).orElse(false);
    }

    private static List<ClasspathEntry> concat(ClasspathEntry platform, Collection<BinaryInput> binaries) {
        List<ClasspathEntry> entries = new ArrayList<>(); entries.add(platform);
        binaries.forEach(value -> entries.add(value.entry())); return entries;
    }

    private static String reactorKey(ModuleIdentity module, SourcePlanModel.Kind kind) {
        return module.value() + ":" + kind;
    }

    private static <K, V> Map<K, V> unique(List<V> values, Function<V, K> key, String name) {
        Map<K, V> result = new HashMap<>();
        for (V value : values) if (result.put(key.apply(value), value) != null)
            throw new IllegalArgumentException("Duplicate " + name + " binding");
        return Map.copyOf(result);
    }

    private static void add(List<FrontendAssemblyResult.Problem> problems,
            FrontendAssemblyResult.Reason reason, String subject, FrontendAssemblyResult.Requirement requirement) {
        problems.add(new FrontendAssemblyResult.Problem(reason, subject, requirement));
    }

    private record ReactorView(
            ModuleIdentity module,
            SourcePlanModel.Kind sourceSet,
            String outputDirectory,
            ClasspathEntry entry) {}
}
