package com.evolution.analysis.ingestion;

import com.evolution.analysis.acquisition.AcquiredFile;
import com.evolution.analysis.acquisition.RepositoryAcquisitionPolicy;
import com.evolution.analysis.acquisition.RepositoryAcquisitionRequest;
import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.acquisition.RepositoryUnderstandingInventory;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.binding.InjectionBindingPlan;
import com.evolution.analysis.spring.binding.InjectionPoint;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import com.evolution.analysis.spring.truth.SymbolicConfiguration;
import com.evolution.analysis.spring.truth.TruthRegionEvaluation;
import com.evolution.analysis.spring.truth.SatConfigurationReasoner;
import com.evolution.analysis.spring.truth.ExhaustiveConfigurationReasoner;
import com.evolution.analysis.spring.universal.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.*;
import javax.xml.stream.*;

/**
 * Universal architecture intelligence pipeline.
 * Orchestrates repository intake, build modeling, source assembly, semantic
 * analysis,
 * Spring enrichment, truth evaluation, and compiles the canonical
 * UniversalArchitectureReport.
 */
public final class UniversalArchitecturePipeline {
    private final AnalysisWorkspace workspace;
    private final int aggregationWorkers;

    public UniversalArchitecturePipeline() {
        this(AnalysisWorkspace.MEMORY);
    }

    public UniversalArchitecturePipeline(AnalysisWorkspace workspace) {
        this(workspace,
                workspace.concurrentTables() ? com.evolution.analysis.concurrent.BoundedTasks.automaticWorkers() : 1);
    }

    public UniversalArchitecturePipeline(AnalysisWorkspace workspace, int aggregationWorkers) {
        this.workspace = Objects.requireNonNull(workspace);
        if (aggregationWorkers < 1 || aggregationWorkers > 8 || aggregationWorkers > 1 && !workspace.concurrentTables())
            throw new IllegalArgumentException("Aggregation workers require a compatible workspace and 1..8 admission");
        this.aggregationWorkers = aggregationWorkers;
    }

    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("architecture.pipeline",
            "m8.4-spooled-recovery-v3");

    public enum TruthEvaluationMode {
        AUTO,
        EXHAUSTIVE,
        SYMBOLIC,
        NONE
    }

    public record Policy(
            UniversalBuildIngestion.Policy buildPolicy,
            RepositoryInputDecoder.Policy decodePolicy,
            SemanticFrontend frontend,
            VersionedIdentifier frontendProvider,
            Map<UniversalSourceIngestion.SourceSet, UniversalSourceIngestion.Resolution> resolutions,
            Optional<PlatformInput> defaultPlatform,
            Optional<BuildModelProvider> mavenProvider,
            Map<MavenCoordinate, PomInput> externalPoms,
            BuildModelPolicy mavenPolicy,
            int maxTypes,
            int maxParameters,
            TruthEvaluationMode truthEvaluationMode,
            Map<UniversalSourceIngestion.SourceSet, SourceToSpringPlan.Scope> springScopes,
            Optional<ConfigurationSpace> configurationSpace,
            Optional<ConfigurationAssignment> baselineConfiguration,
            TruthRegionEvaluation.Limits truthLimits,
            Optional<ContentDigest> readinessPlan) {
        public Policy(UniversalBuildIngestion.Policy buildPolicy, RepositoryInputDecoder.Policy decodePolicy,
                SemanticFrontend frontend, VersionedIdentifier frontendProvider,
                Map<UniversalSourceIngestion.SourceSet, UniversalSourceIngestion.Resolution> resolutions,
                Optional<PlatformInput> defaultPlatform, Optional<BuildModelProvider> mavenProvider,
                Map<MavenCoordinate, PomInput> externalPoms, BuildModelPolicy mavenPolicy, int maxTypes,
                int maxParameters, TruthEvaluationMode truthEvaluationMode,
                Map<UniversalSourceIngestion.SourceSet, SourceToSpringPlan.Scope> springScopes,
                Optional<ConfigurationSpace> configurationSpace,
                Optional<ConfigurationAssignment> baselineConfiguration, TruthRegionEvaluation.Limits truthLimits) {
            this(buildPolicy, decodePolicy, frontend, frontendProvider, resolutions, defaultPlatform, mavenProvider,
                    externalPoms, mavenPolicy, maxTypes, maxParameters, truthEvaluationMode, springScopes,
                    configurationSpace, baselineConfiguration, truthLimits, Optional.empty());
        }

        public Policy withReadiness(ContentDigest plan) {
            return new Policy(buildPolicy, decodePolicy, frontend, frontendProvider, resolutions, defaultPlatform,
                    mavenProvider, externalPoms, mavenPolicy, maxTypes, maxParameters, truthEvaluationMode,
                    springScopes, configurationSpace, baselineConfiguration, truthLimits, Optional.of(plan));
        }

        public Policy {
            Objects.requireNonNull(readinessPlan);
            Objects.requireNonNull(buildPolicy);
            Objects.requireNonNull(decodePolicy);
            Objects.requireNonNull(frontend);
            Objects.requireNonNull(frontendProvider);
            resolutions = Map.copyOf(resolutions);
            Objects.requireNonNull(defaultPlatform);
            Objects.requireNonNull(mavenProvider);
            externalPoms = Map.copyOf(externalPoms);
            Objects.requireNonNull(mavenPolicy);
            if (maxTypes < 1 || maxParameters < 1)
                throw new IllegalArgumentException("Positive limits required");
            Objects.requireNonNull(truthEvaluationMode);
            springScopes = Map.copyOf(springScopes);
            Objects.requireNonNull(configurationSpace);
            Objects.requireNonNull(baselineConfiguration);
            Objects.requireNonNull(truthLimits);
        }

        public static Policy standard(SemanticFrontend frontend, VersionedIdentifier frontendProvider) {
            return builder(frontend, frontendProvider).build();
        }

        public static Builder builder(SemanticFrontend frontend, VersionedIdentifier frontendProvider) {
            return new Builder(frontend, frontendProvider);
        }

        public static final class Builder {
            private final SemanticFrontend frontend;
            private final VersionedIdentifier frontendProvider;
            private UniversalBuildIngestion.Policy buildPolicy = UniversalBuildIngestion.Policy.defaults();
            private RepositoryInputDecoder.Policy decodePolicy = new RepositoryInputDecoder.Policy("UTF-8", 10_000,
                    10_000_000);
            private Map<UniversalSourceIngestion.SourceSet, UniversalSourceIngestion.Resolution> resolutions = new HashMap<>();
            private Optional<PlatformInput> defaultPlatform = Optional.empty();
            private Optional<BuildModelProvider> mavenProvider = Optional.empty();
            private Map<MavenCoordinate, PomInput> externalPoms = new HashMap<>();
            private BuildModelPolicy mavenPolicy = new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16);
            private int maxTypes = 1000;
            private int maxParameters = 100;
            private TruthEvaluationMode truthEvaluationMode = TruthEvaluationMode.AUTO;
            private Map<UniversalSourceIngestion.SourceSet, SourceToSpringPlan.Scope> springScopes = new HashMap<>();
            private Optional<ConfigurationSpace> configurationSpace = Optional.empty();
            private Optional<ConfigurationAssignment> baselineConfiguration = Optional.empty();
            private TruthRegionEvaluation.Limits truthLimits = TruthRegionEvaluation.Limits.conservative();

            public Builder(SemanticFrontend frontend, VersionedIdentifier frontendProvider) {
                this.frontend = Objects.requireNonNull(frontend);
                this.frontendProvider = Objects.requireNonNull(frontendProvider);
            }

            public Builder buildPolicy(UniversalBuildIngestion.Policy buildPolicy) {
                this.buildPolicy = Objects.requireNonNull(buildPolicy);
                return this;
            }

            public Builder decodePolicy(RepositoryInputDecoder.Policy decodePolicy) {
                this.decodePolicy = Objects.requireNonNull(decodePolicy);
                return this;
            }

            public Builder resolutions(
                    Map<UniversalSourceIngestion.SourceSet, UniversalSourceIngestion.Resolution> resolutions) {
                this.resolutions = new HashMap<>(resolutions);
                return this;
            }

            public Builder defaultPlatform(PlatformInput defaultPlatform) {
                this.defaultPlatform = Optional.ofNullable(defaultPlatform);
                return this;
            }

            public Builder defaultPlatformRelease(int release) {
                this.defaultPlatform = Optional.of(PlatformInput.create(release, String.valueOf(release),
                        "standard-jdk", List.of(
                                new PlatformInput.Artifact("java.base.jmod",
                                        ContentDigest.sha256Utf8("platform-" + release),
                                        Path.of("java.base.jmod"), PlatformInput.Format.JMOD))));
                return this;
            }

            public Builder mavenProvider(BuildModelProvider mavenProvider) {
                this.mavenProvider = Optional.ofNullable(mavenProvider);
                return this;
            }

            public Builder externalPoms(Map<MavenCoordinate, PomInput> externalPoms) {
                this.externalPoms = new HashMap<>(externalPoms);
                return this;
            }

            public Builder mavenPolicy(BuildModelPolicy mavenPolicy) {
                this.mavenPolicy = Objects.requireNonNull(mavenPolicy);
                return this;
            }

            public Builder maxTypes(int maxTypes) {
                this.maxTypes = maxTypes;
                return this;
            }

            public Builder maxParameters(int maxParameters) {
                this.maxParameters = maxParameters;
                return this;
            }

            public Builder truthEvaluationMode(TruthEvaluationMode mode) {
                this.truthEvaluationMode = Objects.requireNonNull(mode);
                return this;
            }

            public Builder springScopes(Map<UniversalSourceIngestion.SourceSet, SourceToSpringPlan.Scope> scopes) {
                this.springScopes = new HashMap<>(scopes);
                return this;
            }

            public Builder configurationSpace(ConfigurationSpace space) {
                this.configurationSpace = Optional.ofNullable(space);
                return this;
            }

            public Builder baselineConfiguration(ConfigurationAssignment baseline) {
                this.baselineConfiguration = Optional.ofNullable(baseline);
                return this;
            }

            public Builder truthLimits(TruthRegionEvaluation.Limits truthLimits) {
                this.truthLimits = Objects.requireNonNull(truthLimits);
                return this;
            }

            public Policy build() {
                var effectiveBuildPolicy = buildPolicy;
                var effectiveMavenPolicy = mavenPolicy;
                if (defaultPlatform.isPresent()) {
                    int release = defaultPlatform.get().release();
                    effectiveBuildPolicy = new UniversalBuildIngestion.Policy(
                            effectiveBuildPolicy.syntaxLevel().or(() -> Optional.of(release)),
                            effectiveBuildPolicy.platformRelease().or(() -> Optional.of(release)),
                            effectiveBuildPolicy.encoding().or(() -> Optional.of("UTF-8")),
                            effectiveBuildPolicy.maxFiles(),
                            effectiveBuildPolicy.maxCharacters(),
                            effectiveBuildPolicy.maxTokens(),
                            effectiveBuildPolicy.maxDepth());
                    if (effectiveMavenPolicy.userProperties().isEmpty()) {
                        var props = new TreeMap<String, String>();
                        props.put("maven.compiler.source", String.valueOf(release));
                        props.put("maven.compiler.target", String.valueOf(release));
                        props.put("maven.compiler.release", String.valueOf(release));
                        props.put("project.build.sourceEncoding", "UTF-8");
                        effectiveMavenPolicy = new BuildModelPolicy(
                                effectiveMavenPolicy.activeProfiles(),
                                effectiveMavenPolicy.inactiveProfiles(),
                                props,
                                effectiveMavenPolicy.maxPomBytes(),
                                effectiveMavenPolicy.maxPomCount(),
                                effectiveMavenPolicy.maxModelReads());
                    }
                }
                return new Policy(effectiveBuildPolicy, decodePolicy, frontend, frontendProvider, resolutions,
                        defaultPlatform,
                        mavenProvider, externalPoms, effectiveMavenPolicy, maxTypes, maxParameters, truthEvaluationMode,
                        springScopes, configurationSpace, baselineConfiguration, truthLimits);
            }
        }
    }

    public UniversalArchitectureReport run(RepositoryAcquisitionResult acquisition, Policy policy) {
        return runWithEvidence(acquisition, policy).report();
    }

    /**
     * Additive M5 boundary. Terminal decode failure remains explicit and has no
     * fabricated graph input.
     */
    public record DetailedResult(UniversalArchitectureReport report,
            Optional<com.evolution.analysis.graph.CanonicalGraphInput> graphInput,
            Optional<RepositoryAcquisitionResult> acquisition) {
        public DetailedResult(UniversalArchitectureReport report,
                Optional<com.evolution.analysis.graph.CanonicalGraphInput> graphInput) {
            this(report, graphInput, Optional.empty());
        }

        public DetailedResult {
            Objects.requireNonNull(report);
            Objects.requireNonNull(graphInput);
            Objects.requireNonNull(acquisition);
        }
    }

    public DetailedResult runWithEvidence(RepositoryAcquisitionResult acquisition, Policy policy) {
        Objects.requireNonNull(acquisition);
        Objects.requireNonNull(policy);
        var decoder = new RepositoryInputDecoder();
        var decoded = decoder.decode(acquisition, policy.decodePolicy());
        if (decoded.inputs().isEmpty()) {
            return new DetailedResult(
                    buildTerminalReport(decoded.identity(), acquisition.snapshot(), Optional.of(decoded.inventory()),
                            decoded.issues(), decoded.gaps(), UniversalArchitectureReport.Status.TERMINAL_FAILURE),
                    Optional.empty(), Optional.of(acquisition));
        }
        var captured = new ArrayList<com.evolution.analysis.graph.CanonicalGraphInput>();
        var report = run(decoded.inputs().orElseThrow(), policy, Optional.of(decoded.inventory()), decoded.issues(),
                decoded.gaps(), captured::add);
        return new DetailedResult(report,
                captured.stream().findFirst().map(input -> new com.evolution.analysis.graph.CanonicalGraphInput(
                        input.repository(), input.build(), input.ingestion(), input.spring(), input.issues(),
                        input.gaps(), Optional.of(acquisition))),
                Optional.of(acquisition));
    }

    public UniversalArchitectureReport run(RepositoryInputs inputs, Policy policy) {
        return runWithEvidence(inputs, policy).report();
    }

    public DetailedResult runWithEvidence(RepositoryInputs inputs, Policy policy) {
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(policy);
        var req = new RepositoryAcquisitionRequest(inputs.snapshot().repository(), inputs.snapshot().revision(),
                inputs.snapshot().dirty(), "pom.xml",
                new RepositoryAcquisitionPolicy(100, 100, 1000, 10000, 8, List.of()));
        var files = inputs.files().values().stream().map(f -> new AcquiredFile(f.document().path(), f.bytes()))
                .toList();
        var rawSnapshot = RepositorySnapshot.create(inputs.snapshot().repository(),
                inputs.snapshot().revision(), inputs.snapshot().dirty(),
                inputs.snapshot().files(), List.of());
        var acquisitionResult = new RepositoryAcquisitionResult(
                RepositoryAcquisitionResult.SCHEMA,
                req,
                PROVIDER,
                RepositoryAcquisitionResult.Completion.COMPLETE,
                Optional.of(rawSnapshot),
                files,
                List.of("."),
                List.of(),
                List.of(),
                List.of());
        var inventory = RepositoryUnderstandingInventory.from(acquisitionResult);
        var captured = new ArrayList<com.evolution.analysis.graph.CanonicalGraphInput>();
        var report = run(inputs, policy, Optional.of(inventory), List.of(), List.of(), captured::add);
        return new DetailedResult(report, captured.stream().findFirst());
    }

    /**
     * Additive native-evidence lane: consume the exact prepared model rather than
     * rediscovering it.
     */
    public DetailedResult runWithEvidence(com.evolution.analysis.javaevidence.JavaEvidenceAssembler.Prepared prepared,
            Policy policy) {
        return runWithEvidence(prepared, policy, Optional.empty(), Optional.empty());
    }

    public DetailedResult runWithEvidence(com.evolution.analysis.javaevidence.JavaEvidenceAssembler.Prepared prepared,
            Policy policy, com.evolution.analysis.javaevidence.JavaSemanticEvidence.Bundle semantic) {
        return runWithEvidence(prepared, policy, Optional.of(Objects.requireNonNull(semantic)), Optional.empty());
    }

    public DetailedResult runWithEvidence(com.evolution.analysis.javaevidence.JavaEvidenceAssembler.Prepared prepared,
            Policy policy, com.evolution.analysis.javaevidence.JavaCompilerAttribution.Bundle compiler,
            Optional<com.evolution.analysis.javaevidence.JavaSemanticEvidence.Bundle> semantic) {
        return runWithEvidence(prepared, policy, semantic, Optional.of(Objects.requireNonNull(compiler)));
    }

    private DetailedResult runWithEvidence(com.evolution.analysis.javaevidence.JavaEvidenceAssembler.Prepared prepared,
            Policy policy, Optional<com.evolution.analysis.javaevidence.JavaSemanticEvidence.Bundle> semantic,
            Optional<com.evolution.analysis.javaevidence.JavaCompilerAttribution.Bundle> compiler) {
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(policy);
        var invalidated = prepared.invalidationGaps();
        if (!invalidated.isEmpty()) {
            var gaps = new ArrayList<CapabilityGapRecord>(prepared.activeGaps());
            gaps.addAll(invalidated);
            var graphInput = new com.evolution.analysis.graph.CanonicalGraphInput(prepared.inputs(), Optional.empty(),
                    Optional.empty(), List.of(), List.of(), gaps, Optional.empty(), Optional.of(prepared.graph()));
            return new DetailedResult(
                    buildTerminalReport(prepared.view().identity(), Optional.of(prepared.inputs().snapshot()),
                            Optional.empty(), List.of(), gaps, UniversalArchitectureReport.Status.TERMINAL_FAILURE),
                    Optional.of(graphInput));
        }
        var captured = new ArrayList<com.evolution.analysis.graph.CanonicalGraphInput>();
        var currentGaps = new ArrayList<CapabilityGapRecord>(prepared.activeGaps());
        var report = run(prepared.inputs(), policy, Optional.empty(), List.of(), currentGaps, captured::add,
                Optional.of(prepared), semantic, compiler);
        return new DetailedResult(report, captured.stream().findFirst());
    }

    private UniversalArchitectureReport run(
            RepositoryInputs inputs,
            Policy policy,
            Optional<RepositoryUnderstandingInventory> inventoryOpt,
            List<IngestionEvidence.Issue> initialIssues,
            List<CapabilityGapRecord> initialGaps,
            java.util.function.Consumer<com.evolution.analysis.graph.CanonicalGraphInput> capture) {
        return run(inputs, policy, inventoryOpt, initialIssues, initialGaps, capture, Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private UniversalArchitectureReport run(RepositoryInputs inputs, Policy policy,
            Optional<RepositoryUnderstandingInventory> inventoryOpt,
            List<IngestionEvidence.Issue> initialIssues, List<CapabilityGapRecord> initialGaps,
            java.util.function.Consumer<com.evolution.analysis.graph.CanonicalGraphInput> capture,
            Optional<com.evolution.analysis.javaevidence.JavaEvidenceAssembler.Prepared> prepared,
            Optional<com.evolution.analysis.javaevidence.JavaSemanticEvidence.Bundle> semantic,
            Optional<com.evolution.analysis.javaevidence.JavaCompilerAttribution.Bundle> compiler) {
        long javaFilesCount = inputs.snapshot().files().stream()
                .filter(f -> f.path().endsWith(".java") || f.path().endsWith(".kt")).count();
        if (inputs.snapshot().files().isEmpty() || javaFilesCount == 0) {
            var emptyGaps = new ArrayList<CapabilityGapRecord>(initialGaps);
            prepared.ifPresent(value -> emptyGaps
                    .add(com.evolution.analysis.javaevidence.JavaEvidenceGaps.create(value.view().object("scope"),
                            com.evolution.analysis.javaevidence.JavaEvidenceGaps.Reason.SEMANTIC_MAPPING_UNPROVEN,
                            value.view().identity(), value.view().identity())));
            capture.accept(new com.evolution.analysis.graph.CanonicalGraphInput(inputs, Optional.empty(),
                    Optional.empty(), List.of(), initialIssues, emptyGaps, Optional.empty(),
                    prepared.map(com.evolution.analysis.javaevidence.JavaEvidenceAssembler.Prepared::graph)));
            return buildEmptyOrNonJavaReport(inputs, policy, inventoryOpt, initialIssues, emptyGaps);
        }

        var allIssues = workspace.<IngestionEvidence.Issue>list();
        allIssues.addAll(initialIssues);
        var allGaps = workspace.gaps(initialGaps);
        // Plans own validated non-record solver state; frontend rows/gaps and report
        // populations spill independently rather than serializing executable domain
        // objects.
        var springArtifacts = new ArrayList<com.evolution.analysis.graph.CanonicalGraphInput.SpringSlice>();

        // Map for resolving source paths from document identities
        Map<SourceDocumentIdentity, String> pathMap = new HashMap<>();
        inputs.files().values().forEach(f -> pathMap.put(f.document().identity(), f.document().path()));

        // 1. Build Ingestion
        var buildIngestion = new UniversalBuildIngestion();
        var buildModel = prepared.map(com.evolution.analysis.javaevidence.JavaEvidenceAssembler.Prepared::model)
                .orElseGet(() -> buildIngestion.ingest(inputs, policy.buildPolicy(), policy.mavenProvider(),
                        policy.externalPoms(), policy.mavenPolicy()));
        allIssues.addAll(buildModel.issues());
        allGaps.addAll(buildModel.gaps());

        // 2. Source Ingestion Resolutions & Assembly
        var resolutions = new HashMap<UniversalSourceIngestion.SourceSet, UniversalSourceIngestion.Resolution>(
                prepared.map(com.evolution.analysis.javaevidence.JavaEvidenceAssembler.Prepared::resolutions)
                        .orElse(policy.resolutions()));
        if (prepared.isEmpty() && policy.defaultPlatform().isPresent()) {
            var platform = policy.defaultPlatform().get();
            for (var module : buildModel.modules()) {
                for (var plan : module.sourcePlan().sourceSets()) {
                    var key = new UniversalSourceIngestion.SourceSet(plan.module(), plan.kind(), plan.name());
                    if (!resolutions.containsKey(key)) {
                        boolean declaredDependencies = !module.dependencies().isEmpty() || buildModel.mavenModels()
                                .stream()
                                .flatMap(m -> m.modules().stream())
                                .filter(m -> m.module().identity().equals(plan.module()))
                                .flatMap(m -> m.effectivePom().stream()).anyMatch(p -> !p.dependencies().isEmpty());
                        resolutions.put(key, new UniversalSourceIngestion.Resolution(platform, List.of(),
                                ContentDigest.sha256Utf8("declared-platform-policy-" + key), Optional.empty(),
                                !declaredDependencies));
                    }
                }
            }
        }
        // Check Maven declared dependencies against available binary resolutions
        // In the captured lane, the validated manifest already closes this named
        // context.
        // Passive declarations can belong to an inactive source set or variant.
        if (prepared.isEmpty()) {
            for (var mavenResult : buildModel.mavenModels()) {
                for (var mod : mavenResult.modules()) {
                    if (mod.effectivePom().isPresent()) {
                        var effective = mod.effectivePom().get();
                        for (var dep : effective.dependencies()) {
                            String notation = dep.groupId() + ":" + dep.artifactId() + ":" + dep.version();
                            boolean hasBinary = resolutions.values().stream()
                                    .flatMap(r -> r.binaries().stream())
                                    .anyMatch(b -> b.entry().logicalName().contains(dep.artifactId()));
                            if (!hasBinary) {
                                allIssues.add(new IngestionEvidence.Issue(
                                        IngestionEvidence.Reason.CLASSPATH_RESOLUTION_REQUIRED,
                                        mod.pomPath() + "#" + notation,
                                        List.of(inputs.identity())));
                            }
                        }
                    }
                }
            }
            for (var module : buildModel.modules()) {
                if (!module.dependencies().isEmpty()) {
                    for (var dep : module.dependencies()) {
                        boolean hasBinary = resolutions.values().stream()
                                .flatMap(r -> r.binaries().stream())
                                .anyMatch(b -> b.entry().logicalName().contains(dep.notation()));
                        if (!hasBinary) {
                            allIssues.add(new IngestionEvidence.Issue(
                                    IngestionEvidence.Reason.CLASSPATH_RESOLUTION_REQUIRED,
                                    module.descriptor().displayName() + "#" + dep.notation(),
                                    List.of(inputs.identity())));
                        }
                    }
                }
            }
        }
        var analyzerComp = new ManifestComponent(new VersionedIdentifier("architecture.analyzer", "1"),
                ContentDigest.sha256Utf8("analyzer-component"));
        var rulesComp = new ManifestComponent(new VersionedIdentifier("architecture.rules", "1"),
                ContentDigest.sha256Utf8("rules-component"));
        var schemaComp = com.evolution.analysis.graph.KnowledgeGraph.schemaComponent();
        var sourceIngestion = new UniversalSourceIngestion();
        var inputOptions = new TreeMap<String, String>();
        policy.readinessPlan().ifPresent(identity -> inputOptions.put("readiness.plan", identity.value()));
        prepared.ifPresent(value -> inputOptions.putAll(value.options()));
        compiler.ifPresent(bundle -> inputOptions.putAll(bundle.options(prepared.orElseThrow().policy())));
        var assembledSources = sourceIngestion.assemble(inputs, buildModel, resolutions, analyzerComp, rulesComp,
                schemaComp,
                inputOptions, policy.defaultPlatform());
        allIssues.addAll(assembledSources.issues());
        allGaps.addAll(assembledSources.gaps());

        // 3. Semantic Frontend Pipeline
        var pipeline = new UniversalIngestionPipeline(workspace);
        var compilerResults = new ArrayList<com.evolution.analysis.javaevidence.JavaCompilerAttribution.Result>();
        com.evolution.analysis.frontend.SemanticFrontend executionFrontend = policy.frontend();
        var executionProvider = policy.frontendProvider();
        if (compiler.isPresent()) {
            executionProvider = com.evolution.analysis.javaevidence.JavaCompilerAttribution.PROVIDER;
            executionFrontend = request -> {
                var primary = policy.frontend().analyze(request).validateFor(request);
                if (!primary.frontend().equals(policy.frontendProvider()))
                    throw new com.evolution.analysis.frontend.FrontendInputException("frontend.provider-identity",
                            "Primary provider differs from the requested provider");
                var value = prepared.orElseThrow();
                try {
                    var applied = new com.evolution.analysis.javaevidence.JavaCompilerAttribution().apply(
                            value.view().object("scope"), value.view().identity(), value.policy(), request, primary,
                            compiler.orElseThrow());
                    compilerResults.add(applied);
                    allGaps.addAll(applied.gaps());
                    return applied.frontend();
                } catch (java.io.IOException | RuntimeException unavailable) {
                    // Malformed optional captures must not discard the independently analyzed
                    // primary facts.
                    var reason = com.evolution.analysis.javaevidence.JavaEvidenceGaps.Reason.VALIDATION_REJECTED;
                    if ("JAVA_COMPILER_CANCELLED_WITH_FRONTIER".equals(unavailable.getMessage()))
                        reason = com.evolution.analysis.javaevidence.JavaEvidenceGaps.Reason.CANCELLED_WITH_FRONTIER;
                    if ("JAVA_COMPILER_WORKER_CAPACITY_REQUIRED".equals(unavailable.getMessage()))
                        reason = com.evolution.analysis.javaevidence.JavaEvidenceGaps.Reason.WORKER_CAPACITY_REQUIRED;
                    allGaps.add(
                            com.evolution.analysis.javaevidence.JavaEvidenceGaps.create(value.view().object("scope"),
                                    reason, value.view().identity(), compiler.orElseThrow().index().digest()));
                    return com.evolution.analysis.javaevidence.JavaCompilerAttribution.unavailable(primary);
                }
            };
        }
        var pipelineResult = pipeline.run(assembledSources, executionFrontend, executionProvider, policy.maxTypes(),
                policy.maxParameters());
        allIssues.addAll(pipelineResult.issues());
        allGaps.addAll(pipelineResult.gaps());
        // Keep the repository denominator once. A Spring context inherits global and
        // its own analysis gaps, not the semantic failures of every unrelated module.
        var contextGaps = new HashMap<Optional<AnalysisIdentity>, List<CapabilityGapRecord>>();
        for (var gap : pipelineResult.gaps())
            contextGaps.computeIfAbsent(gap.context().analysisIdentity(), key -> workspace.list()).add(gap);
        contextGaps.replaceAll((key, rows) -> workspace.freeze(rows));

        var javaGraph = prepared.map(com.evolution.analysis.javaevidence.JavaEvidenceAssembler.Prepared::graph);
        for (var applied : compilerResults)
            try {
                javaGraph = Optional.of(javaGraph.orElseThrow().withCompilerAttribution(applied));
            } catch (java.io.IOException revoked) {
                throw new com.evolution.analysis.frontend.FrontendInputException("frontend.compiler-evidence-revoked",
                        "Compiler evidence is no longer current");
            }
        if (prepared.isPresent()) {
            var value = prepared.orElseThrow();
            var reason = com.evolution.analysis.javaevidence.JavaEvidenceGaps.Reason.SEMANTIC_MAPPING_UNPROVEN;
            boolean reconciled = false;
            if (semantic.isPresent())
                try {
                    var requests = assembledSources.outcomes().stream().flatMap(o -> o.request().stream()).toList();
                    var results = pipelineResult.units().stream().flatMap(u -> u.frontend().stream()).toList();
                    if (requests.size() == 1 && results.size() == 1) {
                        var post = new com.evolution.analysis.javaevidence.JavaSemanticEvidence().reconcile(
                                value.view().object("scope"), value.view().identity(), value.policy(),
                                requests.getFirst(), results.getFirst(), semantic.orElseThrow(),
                                compilerResults.stream().map(r -> r.compilerProvider().id())
                                        .collect(java.util.stream.Collectors.toSet()));
                        javaGraph = Optional.of(javaGraph.orElseThrow().withPostAnalysis(post));
                        allGaps.addAll(post.gaps());
                        reconciled = true;
                    }
                } catch (java.io.IOException | RuntimeException unavailable) {
                    // Untrusted payload/availability failures cannot erase the independently
                    // analyzed facts.
                    reason = com.evolution.analysis.javaevidence.JavaEvidenceGaps.Reason.VALIDATION_REJECTED;
                    if (unavailable instanceof java.io.IOException
                            && "JAVA_SEMANTIC_WORKER_CAPACITY_REQUIRED".equals(unavailable.getMessage()))
                        reason = com.evolution.analysis.javaevidence.JavaEvidenceGaps.Reason.WORKER_CAPACITY_REQUIRED;
                    if (unavailable instanceof java.io.IOException
                            && "JAVA_SEMANTIC_CANCELLED_WITH_FRONTIER".equals(unavailable.getMessage()))
                        reason = com.evolution.analysis.javaevidence.JavaEvidenceGaps.Reason.CANCELLED_WITH_FRONTIER;
                }
            if (!reconciled)
                allGaps.add(com.evolution.analysis.javaevidence.JavaEvidenceGaps.create(value.view().object("scope"),
                        reason, value.view().identity(),
                        semantic.map(b -> b.census().digest()).orElse(value.view().identity())));
        }

        // 4. Extract Structural and Semantic Architecture
        var modules = workspace.<UniversalArchitectureReport.ModuleBoundary>list();
        var types = workspace.<UniversalArchitectureReport.TypeRecord>list();
        var dependencies = workspace.<UniversalArchitectureReport.DependencyRecord>list();
        var components = workspace.<UniversalArchitectureReport.ComponentRecord>list();
        var producers = workspace.<UniversalArchitectureReport.ProducerRecord>list();
        var injectionSites = workspace.<UniversalArchitectureReport.InjectionSiteRecord>list();
        var entryPoints = workspace.<UniversalArchitectureReport.EntryPointRecord>list();
        var truthRegions = workspace.<UniversalArchitectureReport.TruthRegionSummary>list();

        // Modules
        for (var module : buildModel.modules()) {
            var desc = module.descriptor();
            var modFiles = inputs.snapshot().files().stream()
                    .filter(f -> desc.path().equals(".") || f.path().equals(desc.path())
                            || f.path().startsWith(desc.path() + "/"))
                    .map(SnapshotFile::path).toList();
            var languages = detectLanguages(modFiles);
            var sourceRoots = module.sourcePlan().sourceSets().stream()
                    .flatMap(s -> s.sourceRoots().stream()).flatMap(r -> r.value().stream()).distinct().toList();
            var depStrings = module.dependencies().stream().map(d -> d.configuration() + ":" + d.notation()).toList();

            modules.add(new UniversalArchitectureReport.ModuleBoundary(
                    desc.identity(),
                    desc.displayName(),
                    desc.path(),
                    module.tool().name(),
                    module.buildFile(),
                    module.coordinate().map(MavenCoordinate::notation),
                    languages,
                    sourceRoots,
                    depStrings));
        }

        // Process units
        int analyzedUnitsCount = 0;
        long failedUnitsCount = 0;
        boolean completedFrontends = true;
        for (var unit : pipelineResult.units()) {
            if (unit.status() == UniversalIngestionPipeline.Status.FAILED
                    || unit.status() == UniversalIngestionPipeline.Status.REJECTED)
                failedUnitsCount++;
            if (unit.frontend().isPresent() && unit.frontend().orElseThrow().state() != FrontendResult.State.COMPLETED)
                completedFrontends = false;
            if (unit.status() == UniversalIngestionPipeline.Status.ANALYZED && unit.frontend().isPresent()) {
                analyzedUnitsCount++;
                var frontend = unit.frontend().get();
                var declarationsByIdentity = new HashMap<EntityIdentity, DeclarationRecord>();
                frontend.declarations().forEach(declaration -> declarationsByIdentity
                        .putIfAbsent(declaration.entity().identity(), declaration));

                var typeIdentityToFqn = new HashMap<EntityIdentity, String>();

                // Types
                for (var typeDecl : frontend.typeDeclarations()) {
                    var declOpt = Optional.ofNullable(declarationsByIdentity.get(typeDecl.type()));
                    String fqn = declOpt.map(d -> d.entity().canonicalName()).orElse(typeDecl.type().value());
                    typeIdentityToFqn.put(typeDecl.type(), fqn);
                    Optional<SourceSpan> span = declOpt.flatMap(d -> d.entity().declaration());
                    String path = span.map(s -> pathMap.getOrDefault(s.document(), "")).orElse("");
                    boolean isPublic = declOpt.map(d -> d.spelling().contains("public")).orElse(true);
                    types.add(new UniversalArchitectureReport.TypeRecord(
                            unit.sourceSet().module(), fqn, typeDecl.kind().name(), path, span, isPublic,
                            typeDecl.abstractType() ? List.of("ABSTRACT") : List.of()));
                }

                // Dependencies from frontend occurrences
                for (var occ : frontend.occurrences()) {
                    String src = occ.relationship().source().value();
                    String tgt = "";
                    if (occ.relationship().target() instanceof RelationshipTarget.Resolved r) {
                        tgt = r.target().value();
                    } else if (occ.relationship().target() instanceof RelationshipTarget.Candidates c) {
                        tgt = c.candidates().stream().map(EntityIdentity::value).sorted().reduce((a, b) -> a + ";" + b)
                                .orElse("");
                    } else if (occ.relationship().target() instanceof RelationshipTarget.Unresolved u) {
                        tgt = u.stableReference();
                    }
                    var layer = (occ.relationship().target() instanceof RelationshipTarget.Resolved)
                            ? UniversalArchitectureReport.Layer.EXACT_SEMANTIC
                            : UniversalArchitectureReport.Layer.STRUCTURAL;
                    dependencies.add(new UniversalArchitectureReport.DependencyRecord(
                            src, tgt, occ.relationship().kind().value(), layer, "SOURCE"));
                }

                var candidateFieldNames = frontend.constructorCandidates().isEmpty() ? Map.<EntityIdentity, String>of()
                        : frontend.memberDeclarations().stream().collect(java.util.stream.Collectors
                                .toMap(MemberDeclarationRecord::member, MemberDeclarationRecord::name));
                for (var candidate : frontend.constructorCandidates()) {
                    injectionSites.add(new UniversalArchitectureReport.InjectionSiteRecord(
                            typeIdentityToFqn.getOrDefault(candidate.owner(), candidate.owner().value()).strip(),
                            candidateFieldNames.getOrDefault(candidate.field(), candidate.field().value()).strip(),
                            candidate.type().spelling().strip(),
                            "LOMBOK_CONSTRUCTOR_CANDIDATE", List.of(), true));
                }
                // Main methods
                for (var memberDecl : frontend.memberDeclarations()) {
                    if (memberDecl.name().equals("main") && memberDecl.staticMember()) {
                        var declOpt = Optional.ofNullable(declarationsByIdentity.get(memberDecl.member()));
                        Optional<SourceSpan> span = declOpt.flatMap(d -> d.entity().declaration());
                        String path = span.map(s -> pathMap.getOrDefault(s.document(), "")).orElse("");
                        String declaringType = path.replace('/', '.').replaceAll("\\.(?:java|kt)$", "");
                        if (declaringType.isEmpty())
                            declaringType = memberDecl.member().value();
                        entryPoints.add(new UniversalArchitectureReport.EntryPointRecord(
                                declaringType, UniversalArchitectureReport.EntryPointKind.MAIN_METHOD,
                                "public static void main(String[] args)", path));
                    }
                }

                // Annotations / Boot Apps / Component Scans
                for (var ann : frontend.annotations()) {
                    if (ann.spelling().contains("SpringBootApplication")
                            || ann.spelling().contains("EnableAutoConfiguration")) {
                        var declOpt = Optional.ofNullable(declarationsByIdentity.get(ann.owner()));
                        String fqn = declOpt.map(d -> d.entity().canonicalName())
                                .orElseGet(() -> typeIdentityToFqn.getOrDefault(ann.owner(), ann.owner().value()));
                        String path = pathMap.getOrDefault(ann.span().document(), "");
                        entryPoints.add(new UniversalArchitectureReport.EntryPointRecord(
                                fqn, UniversalArchitectureReport.EntryPointKind.BOOT_APPLICATION,
                                ann.spelling(), path));
                    } else if (ann.spelling().contains("ComponentScan")) {
                        var declOpt = Optional.ofNullable(declarationsByIdentity.get(ann.owner()));
                        String fqn = declOpt.map(d -> d.entity().canonicalName())
                                .orElseGet(() -> typeIdentityToFqn.getOrDefault(ann.owner(), ann.owner().value()));
                        String path = pathMap.getOrDefault(ann.span().document(), "");
                        entryPoints.add(new UniversalArchitectureReport.EntryPointRecord(
                                fqn, UniversalArchitectureReport.EntryPointKind.COMPONENT_SCAN_ROOT,
                                ann.spelling(), path));
                    }
                }

                // Spring Components
                if (unit.components().isPresent()) {
                    for (var row : unit.components().get().rows()) {
                        if (row.status() == ComponentScanIngestion.Status.INCLUDED) {
                            String fqn = typeIdentityToFqn.getOrDefault(row.type(), row.type().value());
                            String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                            String defaultBeanName = simpleName.isEmpty() ? fqn
                                    : (Character.toLowerCase(simpleName.charAt(0))
                                            + (simpleName.length() > 1 ? simpleName.substring(1) : ""));
                            String beanName = row.beanName().orElse(defaultBeanName);
                            components.add(new UniversalArchitectureReport.ComponentRecord(
                                    "component:" + fqn, beanName, fqn,
                                    "COMPONENT", "default", List.of(), false));
                        }
                    }
                }

                // Spring Constructors & Injections
                if (unit.constructors().isPresent()) {
                    for (var row : unit.constructors().get().rows()) {
                        String ownerFqn = typeIdentityToFqn.getOrDefault(row.type(), row.type().value());
                        for (var param : row.parameters()) {
                            injectionSites.add(new UniversalArchitectureReport.InjectionSiteRecord(
                                    ownerFqn, "constructor", param.type().spelling(),
                                    "CONSTRUCTOR", List.of(), true));
                        }
                    }
                }

                // Source-level Spring component, producer, and injection detection
                for (var ann : frontend.annotations()) {
                    String spelling = ann.spelling();
                    if (spelling.contains("Component") || spelling.contains("Service") ||
                            spelling.contains("Repository") || spelling.contains("Controller") ||
                            spelling.contains("Configuration") || spelling.contains("RestController")) {
                        var declOpt = Optional.ofNullable(declarationsByIdentity.get(ann.owner()));
                        String fqn = declOpt.map(d -> d.entity().canonicalName())
                                .orElseGet(() -> typeIdentityToFqn.getOrDefault(ann.owner(), ann.owner().value()));
                        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                        String beanName = simpleName.isEmpty() ? fqn
                                : (Character.toLowerCase(simpleName.charAt(0))
                                        + (simpleName.length() > 1 ? simpleName.substring(1) : ""));
                        String kind = spelling.contains("Service") ? "SERVICE"
                                : spelling.contains("Repository") ? "REPOSITORY"
                                        : spelling.contains("Controller") ? "CONTROLLER"
                                                : spelling.contains("Configuration") ? "CONFIGURATION" : "COMPONENT";
                        components.add(new UniversalArchitectureReport.ComponentRecord(
                                "component:" + fqn, beanName, fqn,
                                kind, "source-annotation", List.of(), false));
                    } else if (spelling.contains("Bean")) {
                        var declOpt = Optional.ofNullable(declarationsByIdentity.get(ann.owner()));
                        if (declOpt.isPresent()) {
                            var decl = declOpt.get();
                            String methodName = frontend.memberDeclarations().stream()
                                    .filter(m -> m.member().equals(ann.owner()))
                                    .map(MemberDeclarationRecord::name)
                                    .findFirst()
                                    .orElseGet(() -> {
                                        String c = decl.entity().canonicalName();
                                        if (c.contains("#"))
                                            return c.substring(c.indexOf('#') + 1);
                                        if (c.contains("\"")) {
                                            int lastQuote = c.lastIndexOf('\"');
                                            int prevQuote = c.lastIndexOf('\"', lastQuote - 1);
                                            if (prevQuote >= 0)
                                                return c.substring(prevQuote + 1, lastQuote);
                                        }
                                        return decl.spelling();
                                    });
                            if (methodName.isBlank()) {
                                methodName = "bean";
                            }

                            Optional<EntityIdentity> ownerTypeOpt = frontend.occurrences().stream()
                                    .filter(o -> o.relationship().kind().value().contains("declares") &&
                                            o.relationship().target() instanceof RelationshipTarget.Resolved r &&
                                            r.target().equals(ann.owner()))
                                    .map(o -> o.relationship().source())
                                    .findFirst();

                            String factoryType = ownerTypeOpt
                                    .map(id -> typeIdentityToFqn.getOrDefault(id, id.value()))
                                    .orElseGet(() -> {
                                        String path = pathMap.getOrDefault(ann.span().document(), "");
                                        return types.stream()
                                                .filter(t -> !path.isBlank() && t.sourcePath().equals(path))
                                                .map(UniversalArchitectureReport.TypeRecord::qualifiedName)
                                                .findFirst()
                                                .orElseGet(() -> types.isEmpty() ? "Factory"
                                                        : types.getFirst().qualifiedName());
                                    });
                            if (factoryType.isBlank()) {
                                factoryType = "Factory";
                            }
                            factoryType = factoryType.strip();
                            methodName = methodName.strip();

                            var returnTypeOpt = frontend.types().stream()
                                    .filter(t -> t.owner().equals(Optional.of(ann.owner())) &&
                                            (t.role().value().contains("returns")
                                                    || t.role().value().equals("java.return-type")))
                                    .map(t -> t.type().spelling().strip())
                                    .filter(s -> !s.isBlank())
                                    .findFirst();
                            String productType = returnTypeOpt.orElse("Object").strip();
                            if (productType.isBlank()) {
                                productType = "Object";
                            }
                            producers.add(new UniversalArchitectureReport.ProducerRecord(
                                    factoryType, methodName, productType, methodName, List.of()));
                        }
                    } else if (spelling.contains("Autowired") || spelling.contains("Inject")) {
                        var declOpt = Optional.ofNullable(declarationsByIdentity.get(ann.owner()));
                        if (declOpt.isPresent()) {
                            var decl = declOpt.get();
                            Optional<EntityIdentity> ownerTypeOpt = frontend.occurrences().stream()
                                    .filter(o -> o.relationship().kind().value().contains("declares") &&
                                            o.relationship().target() instanceof RelationshipTarget.Resolved r &&
                                            r.target().equals(ann.owner()))
                                    .map(o -> o.relationship().source())
                                    .findFirst();

                            String ownerClass = ownerTypeOpt
                                    .map(id -> typeIdentityToFqn.getOrDefault(id, id.value()))
                                    .orElseGet(() -> {
                                        String path = pathMap.getOrDefault(ann.span().document(), "");
                                        return types.stream()
                                                .filter(t -> !path.isBlank() && t.sourcePath().equals(path))
                                                .map(UniversalArchitectureReport.TypeRecord::qualifiedName)
                                                .findFirst()
                                                .orElseGet(() -> types.isEmpty() ? "Owner"
                                                        : types.getFirst().qualifiedName());
                                    });
                            if (ownerClass.isBlank()) {
                                ownerClass = "Owner";
                            }
                            ownerClass = ownerClass.strip();

                            String rawMemberName = frontend.memberDeclarations().stream()
                                    .filter(m -> m.member().equals(ann.owner()))
                                    .map(MemberDeclarationRecord::name)
                                    .findFirst()
                                    .orElse(decl.spelling());
                            final String memberName = (rawMemberName.isBlank() ? "injected" : rawMemberName).strip();

                            String siteKind = decl.entity().kind().name().strip();

                            var paramTypes = frontend.types().stream()
                                    .filter(t -> t.owner().equals(Optional.of(ann.owner())))
                                    .map(t -> t.type().spelling().strip())
                                    .filter(s -> !s.isBlank())
                                    .toList();
                            if (!paramTypes.isEmpty()) {
                                for (String pType : paramTypes) {
                                    injectionSites.add(new UniversalArchitectureReport.InjectionSiteRecord(
                                            ownerClass, memberName, pType.strip(), siteKind, List.of(), true));
                                }
                            } else {
                                String targetType = types.stream()
                                        .filter(t -> t.qualifiedName().endsWith(memberName)
                                                || memberName.toLowerCase().contains(t.qualifiedName().toLowerCase()))
                                        .map(UniversalArchitectureReport.TypeRecord::qualifiedName)
                                        .findFirst()
                                        .orElse("Object");
                                injectionSites.add(new UniversalArchitectureReport.InjectionSiteRecord(
                                        ownerClass, memberName, targetType.strip(), siteKind, List.of(), true));
                            }
                        }
                    }
                }

                // Spring Semantics: Web Routes & Repositories
                if (unit.spring().isPresent()) {
                    var spring = unit.spring().get();
                    allGaps.addAll(spring.gaps());
                    for (var route : spring.web().routes()) {
                        String methods = String.join(",", route.methods());
                        String ctrlPath = types.stream()
                                .filter(t -> t.qualifiedName().equals(route.controller().value())
                                        || t.qualifiedName().endsWith("." + route.controller().value()))
                                .map(UniversalArchitectureReport.TypeRecord::sourcePath)
                                .filter(p -> !p.isBlank())
                                .findFirst()
                                .orElse("declared");
                        entryPoints.add(new UniversalArchitectureReport.EntryPointRecord(
                                route.controller().value(),
                                UniversalArchitectureReport.EntryPointKind.CONTROLLER_ENDPOINT,
                                methods + " " + route.path(),
                                ctrlPath));
                    }
                    for (var repo : spring.repositories().rows()) {
                        String repoPath = types.stream()
                                .filter(t -> t.qualifiedName().equals(repo.repository().value())
                                        || t.qualifiedName().endsWith("." + repo.repository().value()))
                                .map(UniversalArchitectureReport.TypeRecord::sourcePath)
                                .filter(p -> !p.isBlank())
                                .findFirst()
                                .orElse("declared");
                        entryPoints.add(new UniversalArchitectureReport.EntryPointRecord(
                                repo.repository().value(),
                                UniversalArchitectureReport.EntryPointKind.REPOSITORY_INTERFACE,
                                "Spring Data repository (" + String.join(", ",
                                        repo.exposedTypes().stream().map(EntityIdentity::value).toList()) + ")",
                                repoPath));
                    }
                    for (var site : spring.injections().sites()) {
                        String ownerFqn = typeIdentityToFqn.getOrDefault(site.owner(), site.owner().value());
                        String memberName = site.element().value();
                        var declOpt = Optional.ofNullable(declarationsByIdentity.get(site.element()));
                        if (declOpt.isPresent()) {
                            String cName = declOpt.get().entity().canonicalName();
                            memberName = cName.contains("#") ? cName.substring(cName.indexOf('#') + 1) : cName;
                        }
                        if (memberName == null || memberName.isBlank()) {
                            memberName = "<init>";
                        }
                        memberName = memberName.strip();
                        String targetType = site.type().map(JavaType::spelling).filter(s -> !s.isBlank())
                                .orElse("Object").strip();
                        if (ownerFqn == null || ownerFqn.isBlank()) {
                            ownerFqn = "unknown";
                        }
                        ownerFqn = ownerFqn.strip();
                        var qualifiers = site.qualifier()
                                .map(String::strip)
                                .filter(s -> !s.isBlank())
                                .map(List::of)
                                .orElse(List.of());
                        injectionSites.add(new UniversalArchitectureReport.InjectionSiteRecord(
                                ownerFqn,
                                memberName,
                                targetType,
                                site.kind().name().strip(),
                                qualifiers,
                                site.required()));
                        if (site.type().isPresent() && !site.type().get().spelling().isBlank()) {
                            dependencies.add(new UniversalArchitectureReport.DependencyRecord(
                                    ownerFqn, targetType, "SPRING_INJECTION",
                                    UniversalArchitectureReport.Layer.EXACT_SEMANTIC, "SPRING_METADATA"));
                        }
                    }
                }

                // 5. Spring Plan & Truth Evaluation
                boolean hasIncludedSpringComponents = unit.components().isPresent() &&
                        unit.components().get().rows().stream()
                                .anyMatch(r -> r.status() == ComponentScanIngestion.Status.INCLUDED);
                boolean hasExplicitSpringScope = policy.springScopes().containsKey(unit.sourceSet());
                boolean hasConfigurationSpace = policy.configurationSpace().isPresent();
                boolean hasObservedSpring = unit.sourceWiring().map(SourceWiringCandidates.Result::observed)
                        .orElse(false);
                if (hasIncludedSpringComponents || hasExplicitSpringScope || hasConfigurationSpace
                        || hasObservedSpring) {
                    try {
                        var scope = resolveScope(policy, unit, assembledSources, frontend);
                        if (scope.isPresent()) {
                            var related = workspace.gaps(contextGaps.getOrDefault(Optional.empty(), List.of()));
                            related.addAll(contextGaps.getOrDefault(Optional.of(frontend.analysis()), List.of()));
                            var relatedRows = workspace.<CapabilityGapRecord>list();
                            relatedRows.addAll(related);
                            var scopedPipeline = new UniversalIngestionPipeline.Result(pipelineResult.inputIdentity(),
                                    pipelineResult.sourceIngestion(), List.of(unit), List.of(),
                                    workspace.freeze(relatedRows));
                            var plan = pipeline.prepareSpring(scopedPipeline, unit.sourceSet(), scope.get(),
                                    Math.min(policy.maxTypes(), 1000));
                            allGaps.addAll(plan.gaps());
                            int artifactIndex = springArtifacts.size();
                            springArtifacts.add(new com.evolution.analysis.graph.CanonicalGraphInput.SpringSlice(
                                    unit.sourceSet(), plan,
                                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));

                            for (var cand : plan.binding().registrationPlan().discoveryPlan().initialDefinitions()) {
                                String beanName = cand.declaredNameKey().primary().orElse(cand.definitionSlot());
                                String type = cand.exposedTypes().isEmpty() ? ""
                                        : typeIdentityToFqn.getOrDefault(cand.exposedTypes().getFirst(),
                                                cand.exposedTypes().getFirst().value());
                                String fqn = type.isEmpty() ? cand.identity().value() : type;
                                components.add(new UniversalArchitectureReport.ComponentRecord(
                                        "component:" + fqn, beanName, fqn,
                                        cand.producer().producerKind().name(), cand.producer().containerKey(),
                                        List.of(), false));
                                if (cand.producer().producerKind() == BeanProducer.Kind.BEAN_METHOD) {
                                    String factoryType = cand.producer().containerKey();
                                    String methodName = cand.producer().declarationSlot();
                                    String productType = type.isEmpty() ? "Object" : type;
                                    producers.add(new UniversalArchitectureReport.ProducerRecord(
                                            factoryType, methodName, productType, beanName, List.of()));
                                }
                            }

                            for (var dep : plan.binding().dependencies()) {
                                injectionSites.add(new UniversalArchitectureReport.InjectionSiteRecord(
                                        dep.owner().map(BeanDefinitionCandidate.Identity::value).orElse("external"),
                                        dep.point().ownerDeclarationKey() + "#" + dep.point().siteSlot(),
                                        dep.requestedType().spelling(),
                                        dep.point().siteKind().name(),
                                        dep.dependencyName().value().map(List::of).orElse(List.of()),
                                        dep.required() == InjectionBindingPlan.Required.REQUIRED));
                            }

                            // Truth evaluation
                            if (policy.truthEvaluationMode() != TruthEvaluationMode.NONE) {
                                var spaceOpt = policy.configurationSpace();
                                var baselineOpt = policy.baselineConfiguration();
                                if (spaceOpt.isEmpty() && policy.truthEvaluationMode() == TruthEvaluationMode.AUTO) {
                                    var configuration = assembledSources.outcomes().stream()
                                            .filter(o -> o.sourceSet().equals(unit.sourceSet())).findFirst()
                                            .orElseThrow().configuration();
                                    var selected = RepositoryBaselineConfiguration.select(
                                            plan.binding().registrationPlan().discoveryPlan().buildContext(),
                                            configuration, plan.conditions().occurrences());
                                    spaceOpt = Optional.of(selected.space());
                                    baselineOpt = Optional.of(selected.baseline());
                                }
                                if (spaceOpt.isPresent()) {
                                    var rawSpace = spaceOpt.get();
                                    var planBuildContext = plan.binding().registrationPlan().discoveryPlan()
                                            .buildContext();
                                    var space = rawSpace.buildContext().equals(planBuildContext) ? rawSpace
                                            : new ConfigurationSpace(planBuildContext, rawSpace.repositoryEnvelope(),
                                                    rawSpace.deploymentEnvelope(),
                                                    rawSpace.domains(), rawSpace.constraints(),
                                                    rawSpace.precedencePolicy(), rawSpace.profilePolicy(),
                                                    rawSpace.abstractionVersion(), rawSpace.feasibilityPolicy());
                                    var baseline = baselineOpt.orElseGet(() -> new ConfigurationAssignment(Map.of(),
                                            new ConditionEvidence.Derived(
                                                    List.of(IngestionEvidence.digest(space.identity())), PROVIDER,
                                                    "unspecified-baseline")));
                                    if (policy.truthEvaluationMode() == TruthEvaluationMode.SYMBOLIC) {
                                        var symbolic = plan.evaluateSymbolic(space, baseline, policy.truthLimits(),
                                                ContentDigest.sha256Utf8("symbolic-truth-evaluator"), 64,
                                                SymbolicConfiguration.Limits.defaults());
                                        allGaps.addAll(symbolic.truth().gaps()); // plan gaps already retained above
                                        springArtifacts.set(artifactIndex,
                                                new com.evolution.analysis.graph.CanonicalGraphInput.SpringSlice(
                                                        unit.sourceSet(), plan,
                                                        Optional.of(space), Optional.of(baseline), Optional.empty(),
                                                        Optional.of(symbolic.truth()),
                                                        Optional.of(
                                                                new com.evolution.analysis.graph.CanonicalGraphInput.EvaluationParameters(
                                                                        policy.truthLimits(),
                                                                        SatConfigurationReasoner.INSTANCE.policy(),
                                                                        ContentDigest
                                                                                .sha256Utf8("symbolic-truth-evaluator"),
                                                                        64, Optional.of(SymbolicConfiguration.Limits
                                                                                .defaults())))));
                                        for (var region : symbolic.truth().regions()) {
                                            truthRegions.add(new UniversalArchitectureReport.TruthRegionSummary(
                                                    region.fact().identity().value(),
                                                    region.fact().kind().name(),
                                                    region.classification(),
                                                    0,
                                                    false));
                                        }
                                    } else {
                                        var evaluated = plan.evaluate(space, baseline, policy.truthLimits(),
                                                ContentDigest.sha256Utf8("exhaustive-truth-evaluator"));
                                        allGaps.addAll(evaluated.truth().capabilityGaps()); // plan gaps already
                                                                                            // retained above
                                        springArtifacts.set(artifactIndex,
                                                new com.evolution.analysis.graph.CanonicalGraphInput.SpringSlice(
                                                        unit.sourceSet(), plan,
                                                        Optional.of(space), Optional.of(baseline),
                                                        Optional.of(evaluated.truth()), Optional.empty(),
                                                        Optional.of(
                                                                new com.evolution.analysis.graph.CanonicalGraphInput.EvaluationParameters(
                                                                        policy.truthLimits(),
                                                                        ExhaustiveConfigurationReasoner.INSTANCE
                                                                                .policy(),
                                                                        ContentDigest.sha256Utf8(
                                                                                "exhaustive-truth-evaluator"),
                                                                        0, Optional.empty()))));
                                        for (var region : evaluated.truth().regions()) {
                                            truthRegions.add(new UniversalArchitectureReport.TruthRegionSummary(
                                                    region.fact().identity().value(),
                                                    region.fact().kind().name(),
                                                    region.classification(),
                                                    region.feasibility().feasibleWorlds(),
                                                    !evaluated.truth().witnesses().isEmpty()));
                                        }
                                    }
                                }
                            }
                        }
                    } catch (RuntimeException failure) {
                        allIssues.add(new IngestionEvidence.Issue(
                                IngestionEvidence.Reason.SEMANTIC_PROVIDER_FAILED,
                                unit.sourceSet() + "#spring-plan-failure",
                                List.of(pipelineResult.identity())));
                    }
                }
            }
        }

        // Scan legacy XML configurations (beans, properties, web.xml servlets)
        scanXmlConfigurations(inputs, components, injectionSites, entryPoints);

        // Independent, pure-key groups run concurrently. Each group retains its
        // original row order/first-winner rules and owns a separate output table.
        List<UniversalArchitectureReport.TypeRecord> distinctTypes;
        List<UniversalArchitectureReport.DependencyRecord> distinctDeps;
        List<UniversalArchitectureReport.ComponentRecord> distinctComponents;
        List<UniversalArchitectureReport.ProducerRecord> distinctProducers;
        List<UniversalArchitectureReport.InjectionSiteRecord> distinctInjectionSites;
        List<UniversalArchitectureReport.EntryPointRecord> distinctEntryPoints;
        List<UniversalArchitectureReport.TruthRegionSummary> distinctTruthRegions;
        List<IngestionEvidence.Issue> sortedIssues;
        try (var work = new com.evolution.analysis.concurrent.BoundedTasks(aggregationWorkers, 8, "evo-report",
                () -> Thread.currentThread().isInterrupted())) {
            var typeTask = work
                    .submit(() -> workspace.distinct(types, UniversalArchitectureReport.TypeRecord::qualifiedName));
            var dependencyTask = work.submit(
                    () -> workspace.distinct(dependencies, d -> d.source() + "->" + d.target() + ":" + d.kind()));
            var componentTask = work.submit(() -> {
                var merged = new LinkedHashMap<String, UniversalArchitectureReport.ComponentRecord>();
                for (var c : components) {
                    if (Thread.currentThread().isInterrupted())
                        throw new java.util.concurrent.CancellationException();
                    merged.merge(c.declaringType().isEmpty() ? c.identity() : c.declaringType(), c, (oldC,
                            newC) -> oldC.stereotype().equals("COMPONENT") && !newC.stereotype().equals("COMPONENT")
                                    ? newC
                                    : oldC);
                }
                return merged.values().stream()
                        .sorted(Comparator.comparing(UniversalArchitectureReport.ComponentRecord::identity)).toList();
            });
            var producerTask = work.submit(() -> workspace.distinct(producers,
                    p -> p.factoryType() + "#" + p.methodName() + ":" + p.beanName()));
            var injectionTask = work.submit(() -> workspace.distinct(injectionSites,
                    com.evolution.analysis.contract.serialization.CanonicalJson::write));
            var entryTask = work
                    .submit(() -> workspace.distinct(entryPoints, e -> e.declaringType() + ":" + e.detail()));
            var truthTask = work.submit(
                    () -> workspace.distinct(truthRegions, UniversalArchitectureReport.TruthRegionSummary::factKey));
            var issueTask = work.submit(() -> workspace.distinct(allIssues, i -> i.identity().value()));
            distinctTypes = typeTask.await();
            distinctDeps = dependencyTask.await();
            distinctComponents = componentTask.await();
            distinctProducers = producerTask.await();
            distinctInjectionSites = injectionTask.await();
            distinctEntryPoints = entryTask.await();
            distinctTruthRegions = truthTask.await();
            sortedIssues = issueTask.await();
        }

        // Group root causes
        var gapRows = workspace.<CapabilityGapRecord>list();
        gapRows.addAll(allGaps);
        var sortedGaps = javaGraph.map(value -> value.activeGaps(allGaps)).orElseGet(() -> workspace.freeze(gapRows));
        var rootCauses = classifyRootCauses(sortedIssues, sortedGaps);

        // Status determination
        UniversalArchitectureReport.Status status;
        if (analyzedUnitsCount > 0 && failedUnitsCount == 0 && completedFrontends && sortedIssues.isEmpty()
                && sortedGaps.isEmpty()
                && (prepared.isEmpty() || javaGraph.orElseThrow().closedGapDenominator())) {
            status = UniversalArchitectureReport.Status.COMPLETE;
        } else if (analyzedUnitsCount > 0) {
            status = UniversalArchitectureReport.Status.PARTIAL;
        } else {
            status = UniversalArchitectureReport.Status.TERMINAL_FAILURE;
        }

        int unknownFactsCount = (int) distinctTruthRegions.stream()
                .filter(r -> r.classification() == TruthRegionEvaluation.Classification.UNKNOWN).count();

        UniversalArchitectureReport.ConfidenceLevel confidence;
        if (status == UniversalArchitectureReport.Status.COMPLETE) {
            confidence = UniversalArchitectureReport.ConfidenceLevel.FULL;
        } else if (status == UniversalArchitectureReport.Status.PARTIAL && !distinctTypes.isEmpty()) {
            confidence = UniversalArchitectureReport.ConfidenceLevel.PARTIAL;
        } else if (status == UniversalArchitectureReport.Status.PARTIAL) {
            confidence = UniversalArchitectureReport.ConfidenceLevel.QUALIFIED;
        } else {
            confidence = UniversalArchitectureReport.ConfidenceLevel.MINIMAL;
        }

        String summaryText = "Analyzed " + analyzedUnitsCount + " units across " + modules.size()
                + " modules, yielding " + distinctTypes.size() + " types, "
                + distinctComponents.size() + " components, "
                + distinctEntryPoints.size() + " entry points, and "
                + sortedGaps.size() + " capability gaps.";

        var metrics = new UniversalArchitectureReport.ArchitectureMetrics(
                inputs.snapshot().files().size(),
                (int) javaFilesCount,
                modules.size(),
                distinctTypes.size(),
                distinctDeps.size(),
                distinctComponents.size(),
                distinctInjectionSites.size(),
                distinctEntryPoints.size(),
                distinctTruthRegions.size(),
                unknownFactsCount,
                confidence,
                summaryText);

        capture.accept(new com.evolution.analysis.graph.CanonicalGraphInput(inputs, Optional.of(buildModel),
                Optional.of(pipelineResult),
                workspace.freeze(springArtifacts), sortedIssues, sortedGaps, Optional.empty(), javaGraph));
        return new UniversalArchitectureReport(
                prepared.map(value -> value.view().identity()).orElse(inputs.identity()),
                status,
                inventoryOpt,
                workspace.freeze(modules),
                distinctTypes,
                distinctDeps,
                distinctComponents,
                distinctProducers,
                distinctInjectionSites,
                distinctEntryPoints,
                distinctTruthRegions,
                rootCauses,
                sortedIssues,
                sortedGaps,
                metrics);
    }

    private Optional<SourceToSpringPlan.Scope> resolveScope(
            Policy policy,
            UniversalIngestionPipeline.Unit unit,
            UniversalSourceIngestion.Result assembledSources,
            FrontendResult frontend) {
        if (policy.springScopes().containsKey(unit.sourceSet())) {
            return Optional.of(policy.springScopes().get(unit.sourceSet()));
        }
        var outcomeOpt = assembledSources.outcomes().stream()
                .filter(o -> o.sourceSet().equals(unit.sourceSet())).findFirst();
        if (outcomeOpt.isEmpty() || outcomeOpt.get().request().isEmpty())
            return Optional.empty();
        var req = outcomeOpt.get().request().get();
        if (req.sources().isEmpty())
            return Optional.empty();

        var evidence = new ConditionEvidence.Derived(
                List.of(IngestionEvidence.digest(req.manifest().identity()), IngestionEvidence.digest(frontend)),
                PROVIDER, "source-context-with-unproved-runtime-closure");

        var framework = extractSpringFramework(req);
        var sourceEvidence = new SpringSourceEvidence(req, frontend, framework);

        var scope = new SourceToSpringPlan.Scope(
                "architecture-scope",
                sourceEvidence.identity(),
                List.of(),
                RegistrationEvent.Completeness.UNKNOWN,
                RegistrationEvent.Completeness.UNKNOWN,
                RegistrationEvent.Completeness.UNKNOWN,
                RegistrationEvent.Completeness.UNKNOWN,
                RegistrationEvent.Completeness.UNKNOWN,
                RegistrationPlan.OverridePolicy.UNKNOWN,
                evidence,
                Optional.empty());
        return Optional.of(scope);
    }

    private static SpringFrameworkEvidence extractSpringFramework(FrontendRequest request) {
        return SpringFrameworkEvidence.fromRequest(request);
    }

    private List<UniversalArchitectureReport.RootCauseExplanation> classifyRootCauses(
            List<IngestionEvidence.Issue> issues,
            List<CapabilityGapRecord> gaps) {
        var map = new LinkedHashMap<UniversalArchitectureReport.RootCauseCategory, List<String>>();
        for (var issue : issues) {
            var category = categorizeIssue(issue.reason());
            map.computeIfAbsent(category, k -> workspace.list()).add(issue.subject());
        }
        for (var gap : gaps) {
            var category = categorizeGap(gap);
            map.computeIfAbsent(category, k -> workspace.list()).add(gap.subject().identity());
        }

        var result = new ArrayList<UniversalArchitectureReport.RootCauseExplanation>();
        for (var entry : map.entrySet()) {
            var category = entry.getKey();
            var subjects = workspace.distinct(entry.getValue(), s -> s);
            result.add(createExplanation(category, subjects));
        }
        return result.stream()
                .sorted(Comparator.comparing(UniversalArchitectureReport.RootCauseExplanation::description)).toList();
    }

    private UniversalArchitectureReport.RootCauseCategory categorizeIssue(IngestionEvidence.Reason reason) {
        return switch (reason) {
            case INPUT_LIMIT, UNSAFE_PATH, MALFORMED_INPUT ->
                UniversalArchitectureReport.RootCauseCategory.MALFORMED_OR_BOUNDED_INPUT;
            case INPUT_UNAVAILABLE ->
                UniversalArchitectureReport.RootCauseCategory.MISSING_SOURCE_OR_RESOURCE;
            case CLASSPATH_RESOLUTION_REQUIRED, UNRESOLVED_COORDINATE, DEPENDENCY_VERSION_UNRESOLVED ->
                UniversalArchitectureReport.RootCauseCategory.UNRESOLVED_DEPENDENCIES;
            case DYNAMIC_BUILD_LOGIC, CUSTOM_SOURCE_LAYOUT, CUSTOM_SOURCE_SET_ROLE_UNRESOLVED ->
                UniversalArchitectureReport.RootCauseCategory.DYNAMIC_BUILD_LOGIC;
            case COMPILER_CONFIGURATION_MISSING ->
                UniversalArchitectureReport.RootCauseCategory.COMPILER_CONFIGURATION_MISSING;
            case CONFIG_IMPORT_REQUIRED, CONFIG_EXTERNAL_INPUT_REQUIRED, CONFIG_ACTIVATION_UNKNOWN,
                    CONFIG_PLACEHOLDER_UNRESOLVED, CONFIG_PRECEDENCE_UNKNOWN, CONFIG_PROFILE_INVALID,
                    CONFIG_FORMAT_UNSUPPORTED ->
                UniversalArchitectureReport.RootCauseCategory.EXTERNAL_CONFIG_NOT_SUPPLIED;
            case SEMANTIC_INPUT_REJECTED, SEMANTIC_PROVIDER_FAILED ->
                UniversalArchitectureReport.RootCauseCategory.SEMANTIC_PROVIDER_FAILURE;
            default ->
                UniversalArchitectureReport.RootCauseCategory.OPAQUE_REGISTRAR_OR_CONDITION;
        };
    }

    private UniversalArchitectureReport.RootCauseCategory categorizeGap(CapabilityGapRecord gap) {
        String detail = (gap.mechanismCategory() + ":" + gap.reasonCode()).toLowerCase(Locale.ROOT);
        if (detail.contains("generated") || detail.contains("lombok") || detail.contains("processor")) {
            return UniversalArchitectureReport.RootCauseCategory.MISSING_GENERATED_CONTRACT_TYPES;
        } else if (detail.contains("config") || detail.contains("property") || detail.contains("profile")) {
            return UniversalArchitectureReport.RootCauseCategory.EXTERNAL_CONFIG_NOT_SUPPLIED;
        } else if (detail.contains("registrar") || detail.contains("condition") || detail.contains("opaque")) {
            return UniversalArchitectureReport.RootCauseCategory.OPAQUE_REGISTRAR_OR_CONDITION;
        } else if (detail.contains("classpath") || detail.contains("dependency") || detail.contains("version")) {
            return UniversalArchitectureReport.RootCauseCategory.UNRESOLVED_DEPENDENCIES;
        } else if (detail.contains("build") || detail.contains("gradle") || detail.contains("maven")) {
            return UniversalArchitectureReport.RootCauseCategory.DYNAMIC_BUILD_LOGIC;
        } else {
            return UniversalArchitectureReport.RootCauseCategory.UNSUPPORTED_LANGUAGE_OR_FRAMEWORK_FEATURE;
        }
    }

    private UniversalArchitectureReport.RootCauseExplanation createExplanation(
            UniversalArchitectureReport.RootCauseCategory category,
            List<String> subjects) {
        return switch (category) {
            case MISSING_GENERATED_CONTRACT_TYPES -> new UniversalArchitectureReport.RootCauseExplanation(
                    category,
                    "Missing generated contract types",
                    "Consumer calls and constructor bindings",
                    "Source recipe, selected cache, supplied bundle",
                    "Generated artifact matching input lineage",
                    subjects,
                    UniversalArchitectureReport.Avoidability.UNAVAILABLE_EVIDENCE);
            case EXTERNAL_CONFIG_NOT_SUPPLIED -> new UniversalArchitectureReport.RootCauseExplanation(
                    category,
                    "External config not supplied",
                    "Beans and routes reading external properties",
                    "Repository import closure",
                    "Explicit deployment envelope or configuration tree",
                    subjects,
                    UniversalArchitectureReport.Avoidability.UNAVAILABLE_EVIDENCE);
            case OPAQUE_REGISTRAR_OR_CONDITION -> new UniversalArchitectureReport.RootCauseExplanation(
                    category,
                    "Opaque registrar with global container effects",
                    "Registry-closure-dependent conclusions and truth regions",
                    "Source/binary metadata, bounded summary",
                    "Qualified observation or validated semantic pack",
                    subjects,
                    UniversalArchitectureReport.Avoidability.UNAVAILABLE_EVIDENCE);
            case UNRESOLVED_DEPENDENCIES -> new UniversalArchitectureReport.RootCauseExplanation(
                    category,
                    "Unresolved dependency coordinates or missing binary classpath",
                    "Cross-module symbol resolution and constructor parameter injection",
                    "Declared repository coordinates and lockfiles",
                    "Exact binary jar/pom matching requested GAV",
                    subjects,
                    UniversalArchitectureReport.Avoidability.UNAVAILABLE_EVIDENCE);
            case DYNAMIC_BUILD_LOGIC -> new UniversalArchitectureReport.RootCauseExplanation(
                    category,
                    "Dynamic build script logic or custom source layouts",
                    "Source-set boundaries and effective compiler settings",
                    "Declarative script parsing within safe grammar bounds",
                    "Explicit resolved source-plan or build export",
                    subjects,
                    UniversalArchitectureReport.Avoidability.UNAVAILABLE_EVIDENCE);
            case COMPILER_CONFIGURATION_MISSING -> new UniversalArchitectureReport.RootCauseExplanation(
                    category,
                    "Compiler syntax level or platform release missing",
                    "Language level syntax attribution and source file decoding",
                    "Build descriptor extraction and convention defaults",
                    "Explicit toolchain or compiler configuration in POM / Gradle",
                    subjects,
                    UniversalArchitectureReport.Avoidability.AVOIDABLE_DEFECT);
            case MALFORMED_OR_BOUNDED_INPUT -> new UniversalArchitectureReport.RootCauseExplanation(
                    category,
                    "Malformed, oversized, or path-violating source file",
                    "File-level AST parsing and syntax declarations",
                    "Local source file parsing and sanitization",
                    "Valid parseable source file without path traversal",
                    subjects,
                    UniversalArchitectureReport.Avoidability.UNAVAILABLE_EVIDENCE);
            case MISSING_SOURCE_OR_RESOURCE -> new UniversalArchitectureReport.RootCauseExplanation(
                    category,
                    "Referenced source or resource file missing from snapshot",
                    "Module boundary integrity and resource configuration binding",
                    "Repository file discovery and snapshot index lookup",
                    "Acquired source or resource bytes matching snapshot",
                    subjects,
                    UniversalArchitectureReport.Avoidability.UNAVAILABLE_EVIDENCE);
            case SEMANTIC_PROVIDER_FAILURE -> new UniversalArchitectureReport.RootCauseExplanation(
                    category,
                    "Semantic frontend provider failure or input rejection",
                    "AST occurrence extraction and type relationship analysis",
                    "Configured semantic frontend provider",
                    "Sanitized source AST or alternate parser provider",
                    subjects,
                    UniversalArchitectureReport.Avoidability.AVOIDABLE_DEFECT);
            case UNSUPPORTED_LANGUAGE_OR_FRAMEWORK_FEATURE -> new UniversalArchitectureReport.RootCauseExplanation(
                    category,
                    "Unsupported language or framework feature",
                    "Framework-specific semantics and truth evaluation",
                    "Standard feature handlers",
                    "Validated semantic extension pack",
                    subjects,
                    UniversalArchitectureReport.Avoidability.UNAVAILABLE_EVIDENCE);
            case RESOURCE_BUDGET_EXCEEDED -> new UniversalArchitectureReport.RootCauseExplanation(
                    category,
                    "Resource budget or traversal limit exceeded",
                    "Exhaustive candidate/type search",
                    "Bounded traversal up to configured limits",
                    "Higher resource budget or targeted analysis scope",
                    subjects,
                    UniversalArchitectureReport.Avoidability.UNAVAILABLE_EVIDENCE);
        };
    }

    private UniversalArchitectureReport buildEmptyOrNonJavaReport(
            RepositoryInputs inputs,
            Policy policy,
            Optional<RepositoryUnderstandingInventory> inventoryOpt,
            List<IngestionEvidence.Issue> initialIssues,
            List<CapabilityGapRecord> initialGaps) {
        var issues = new ArrayList<IngestionEvidence.Issue>(initialIssues);
        var gaps = new ArrayList<CapabilityGapRecord>(initialGaps);
        var modules = new ArrayList<UniversalArchitectureReport.ModuleBoundary>();

        var modFiles = inputs.snapshot().files().stream().map(SnapshotFile::path).toList();
        var languages = detectLanguages(modFiles);
        modules.add(new UniversalArchitectureReport.ModuleBoundary(
                ModuleDescriptor.create(inputs.snapshot().repository(), ".", "repository-root").identity(),
                "repository-root",
                ".",
                "PLAIN_OR_NON_JAVA",
                Optional.empty(),
                Optional.empty(),
                languages,
                List.of("."),
                List.of()));

        var metrics = new UniversalArchitectureReport.ArchitectureMetrics(
                inputs.snapshot().files().size(),
                0,
                modules.size(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                UniversalArchitectureReport.ConfidenceLevel.MINIMAL,
                "Repository contains 0 Java source files. Architectural analysis concluded as EMPTY_OR_NON_JAVA.");

        return new UniversalArchitectureReport(
                inputs.identity(),
                UniversalArchitectureReport.Status.EMPTY_OR_NON_JAVA,
                inventoryOpt,
                modules,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                classifyRootCauses(issues, gaps),
                issues,
                gaps,
                metrics);
    }

    private UniversalArchitectureReport buildTerminalReport(
            ContentDigest inputIdentity,
            Optional<RepositorySnapshot> snapshotOpt,
            Optional<RepositoryUnderstandingInventory> inventoryOpt,
            List<IngestionEvidence.Issue> issues,
            List<CapabilityGapRecord> gaps,
            UniversalArchitectureReport.Status status) {
        int totalFiles = snapshotOpt.map(s -> s.files().size()).orElse(0);
        var metrics = new UniversalArchitectureReport.ArchitectureMetrics(
                totalFiles,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                UniversalArchitectureReport.ConfidenceLevel.MINIMAL,
                "Analysis terminated with status: " + status);

        return new UniversalArchitectureReport(
                inputIdentity,
                status,
                inventoryOpt,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                classifyRootCauses(issues, gaps),
                issues,
                gaps,
                metrics);
    }

    private void scanXmlConfigurations(
            RepositoryInputs inputs,
            List<UniversalArchitectureReport.ComponentRecord> components,
            List<UniversalArchitectureReport.InjectionSiteRecord> injectionSites,
            List<UniversalArchitectureReport.EntryPointRecord> entryPoints) {
        var xmlFactory = XMLInputFactory.newFactory();
        xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        for (var entry : inputs.files().entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(".xml"))
                continue;
            byte[] bytes = entry.getValue().bytes();
            if (bytes.length == 0)
                continue;

            try {
                var reader = xmlFactory.createXMLStreamReader(new ByteArrayInputStream(bytes));
                String currentBeanId = null;
                String currentBeanClass = null;

                StringBuilder textBuffer = new StringBuilder();

                String lastServletName = null;
                String lastServletClass = null;
                String lastMappingServletName = null;
                String lastUrlPattern = null;

                Map<String, String> servletClasses = new LinkedHashMap<>();
                Map<String, String> servletMappings = new LinkedHashMap<>();

                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String currentElement = reader.getLocalName();
                        textBuffer.setLength(0);

                        if ("bean".equalsIgnoreCase(currentElement)) {
                            currentBeanId = null;
                            currentBeanClass = null;
                            for (int i = 0; i < reader.getAttributeCount(); i++) {
                                String attr = reader.getAttributeLocalName(i);
                                String val = reader.getAttributeValue(i);
                                if ("id".equalsIgnoreCase(attr) || "name".equalsIgnoreCase(attr)) {
                                    currentBeanId = val;
                                } else if ("class".equalsIgnoreCase(attr)) {
                                    currentBeanClass = val;
                                }
                            }
                            if (currentBeanClass != null && !currentBeanClass.isBlank()) {
                                String beanName = (currentBeanId != null && !currentBeanId.isBlank()) ? currentBeanId
                                        : (currentBeanClass.contains(".")
                                                ? currentBeanClass.substring(currentBeanClass.lastIndexOf('.') + 1)
                                                : currentBeanClass);
                                components.add(new UniversalArchitectureReport.ComponentRecord(
                                        "xml-bean:" + (currentBeanId != null ? currentBeanId : currentBeanClass),
                                        beanName,
                                        currentBeanClass,
                                        "XML_BEAN",
                                        path,
                                        List.of(),
                                        false));
                            }
                        } else if ("property".equalsIgnoreCase(currentElement) && currentBeanClass != null) {
                            String propName = null;
                            String refBean = null;
                            for (int i = 0; i < reader.getAttributeCount(); i++) {
                                String attr = reader.getAttributeLocalName(i);
                                String val = reader.getAttributeValue(i);
                                if ("name".equalsIgnoreCase(attr)) {
                                    propName = val;
                                } else if ("ref".equalsIgnoreCase(attr)) {
                                    refBean = val;
                                }
                            }
                            if (propName != null && refBean != null) {
                                injectionSites.add(new UniversalArchitectureReport.InjectionSiteRecord(
                                        currentBeanClass,
                                        propName,
                                        refBean,
                                        "XML_PROPERTY",
                                        List.of(),
                                        true));
                            }
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                        textBuffer.append(reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String endTag = reader.getLocalName();
                        String text = textBuffer.toString().trim();
                        if ("servlet-name".equalsIgnoreCase(endTag)) {
                            lastServletName = text;
                            lastMappingServletName = text;
                        } else if ("servlet-class".equalsIgnoreCase(endTag)) {
                            lastServletClass = text;
                            if (lastServletName != null && !lastServletClass.isEmpty()) {
                                servletClasses.put(lastServletName, lastServletClass);
                            }
                        } else if ("url-pattern".equalsIgnoreCase(endTag)) {
                            lastUrlPattern = text;
                            if (lastMappingServletName != null && !lastUrlPattern.isEmpty()) {
                                servletMappings.put(lastMappingServletName, lastUrlPattern);
                            }
                        } else if ("bean".equalsIgnoreCase(endTag)) {
                            currentBeanClass = null;
                            currentBeanId = null;
                        }
                        textBuffer.setLength(0);
                    }
                }
                reader.close();

                for (var mapping : servletMappings.entrySet()) {
                    String sName = mapping.getKey();
                    String pattern = mapping.getValue();
                    String sClass = servletClasses.getOrDefault(sName, sName);
                    entryPoints.add(new UniversalArchitectureReport.EntryPointRecord(
                            sClass,
                            UniversalArchitectureReport.EntryPointKind.CONTROLLER_ENDPOINT,
                            "SERVLET " + pattern,
                            path));
                }
            } catch (Exception ignored) {
                // Ignore XML parse errors in degraded/malformed documents per fault safety
                // invariant
            }
        }
    }

    private static List<String> detectLanguages(List<String> paths) {
        var set = new TreeSet<String>();
        for (var p : paths) {
            String lower = p.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".java"))
                set.add("JAVA");
            else if (lower.endsWith(".kt") || lower.endsWith(".kts"))
                set.add("KOTLIN");
            else if (lower.endsWith(".groovy"))
                set.add("GROOVY");
            else if (lower.endsWith(".scala"))
                set.add("SCALA");
            else if (lower.endsWith(".py"))
                set.add("PYTHON");
            else if (lower.endsWith(".js") || lower.endsWith(".jsx"))
                set.add("JAVASCRIPT");
            else if (lower.endsWith(".ts") || lower.endsWith(".tsx"))
                set.add("TYPESCRIPT");
            else if (lower.endsWith(".c") || lower.endsWith(".h") || lower.endsWith(".cpp"))
                set.add("C/C++");
            else if (lower.endsWith(".xml"))
                set.add("XML");
            else if (lower.endsWith(".properties") || lower.endsWith(".yml") || lower.endsWith(".yaml"))
                set.add("CONFIGURATION");
        }
        return List.copyOf(set);
    }

    private static <T> List<T> deduplicate(List<T> list, Comparator<T> comparator) {
        var set = new TreeSet<T>(comparator);
        set.addAll(list);
        return List.copyOf(set);
    }
}
