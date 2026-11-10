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

/** Passive filesystem/local-cache composition over the same providers as evo scan. No target execution or network. */
public final class LocalAnalysisRunner implements AnalysisJobs.Runner {
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
    private final Input input;
    private final Path intakeDirectory;
    public LocalAnalysisRunner(Input input) { this(input,input.source().resolve(".evo/acquisition")); }
    public LocalAnalysisRunner(Input input,Path intakeDirectory) { this.input = Objects.requireNonNull(input); this.intakeDirectory=intakeDirectory.toAbsolutePath().normalize(); }
    public AnalysisJobs.Registration registration(String id) {
        return new AnalysisJobs.Registration(new AnalysisJobs.Setup(id,input.repository(),input.release(),input.includeTests(),
                input.policy().map(p -> ContentDigest.sha256(p.bytes())),input.spring().space().map(s -> s.identity().value())),this);
    }
    @Override public AnalysisJobs.Outcome run(AnalysisJobs.Control control) throws Exception {
        control.stage(AnalysisJobs.Stage.ACQUISITION);
        return runAcquired(control);
    }
    AnalysisJobs.Outcome runAcquired(AnalysisJobs.Control control) throws Exception {
        var source = safe(input.source()); var cache = safe(input.mavenCache());
        var excluded = new TreeSet<>(input.exclusions());
        boolean captured=false;
        if(Files.isRegularFile(intakeDirectory.resolve("metadata/index.sqlite"),LinkOption.NOFOLLOW_LINKS))try(var intake=new RepositoryIntake(intakeDirectory,true)){captured=intake.inventory().manifest(control.id()).isPresent();}
        if (!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)&&!captured) {
            var unavailable = new FilesystemRepositoryAcquirer().acquire(source,new RepositoryAcquisitionRequest(input.repository(),input.revision(),input.dirty(),"pom.xml",new RepositoryAcquisitionPolicy(1,1,1,1,1,List.copyOf(excluded))));
            return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.of(),Optional.of(unavailable.identity()),Optional.empty(),inputProblems(unavailable));
        }
        var selection = ScanSelection.plan(source,input.includeTests(),false,List.copyOf(excluded)); excluded.addAll(selection.excluded());
        var acquisition = acquire(source,excluded,control); control.checkpoint();
        if (acquisition.snapshot().isEmpty()) return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.of(),Optional.of(acquisition.identity()),Optional.empty(),inputProblems(acquisition));
        return runCaptured(acquisition,control);
    }
    AnalysisJobs.Outcome runCaptured(RepositoryAcquisitionResult acquisition,AnalysisJobs.Control control)throws Exception {
        var cache=safe(input.mavenCache());
        ReadinessPlan readiness;
        try(var intake=new RepositoryIntake(intakeDirectory)){readiness=intake.readiness(control.id(),ReadinessPlanner.Policy.comprehensive(),control);}
        control.stage(AnalysisJobs.Stage.PLATFORM);
        var platform = new FilesystemJdkPlatformProvider().acquire(safe(input.jdk()),new PlatformSymbolRequest(input.release(),128,128_000_000,512_000_000));
        control.checkpoint();
        var models = MavenScan.models(acquisition,cache,List.of());
        var defaults = UniversalBuildIngestion.Policy.defaults();
        var gradleModel=GradleScan.model(acquisition,new UniversalBuildIngestion.Policy(Optional.of(input.release()),Optional.of(input.release()),Optional.of("UTF-8"),32_768,defaults.maxCharacters(),defaults.maxTokens(),128),new RepositoryInputDecoder.Policy("UTF-8",500_000,10_000_000),Map.of());
        var requiredReleases=new TreeSet<>(MavenScan.platformReleases(models));requiredReleases.addAll(GradleScan.platformReleases(gradleModel));
        var platformViews=new TreeMap<Integer,PlatformSymbolResult>();platformViews.put(input.release(),platform);
        var additionalPlatforms=new ArrayList<PlatformSymbolResult>();
        for(int target:requiredReleases)if(!platformViews.containsKey(target)) {
            var view=new FilesystemJdkPlatformProvider().acquire(safe(input.jdk()),new PlatformSymbolRequest(target,128,128_000_000,512_000_000));
            platformViews.put(target,view);additionalPlatforms.add(view);
        }
        var availablePlatforms=new TreeMap<Integer,com.evolution.analysis.frontend.PlatformInput>();platformViews.forEach((target,view)->view.platform().ifPresent(value->availablePlatforms.put(target,value)));
        var platformLedgers=additionalPlatforms.isEmpty()?List.<EvidenceAcquisitionLedger>of():List.of(CapabilityGapNormalizer.normalize(
                EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity()),EvidenceNormalizationInput.builder().platformResults(additionalPlatforms).build()));
        var depPolicy=new com.evolution.analysis.dependency.DependencyAcquisitionPolicy(1024,64_000_000,256_000_000,64,List.of(),5000,15000,0);
        var mavenBase=MavenScan.inputs(acquisition,models,cache,platform.platform(),depPolicy,availablePlatforms);
        var dependencies=GradleScan.augment(mavenBase,acquisition,gradleModel,cache,platform.platform(),depPolicy,availablePlatforms);
        var frontend = new JavaParserFrontend();
        SemanticFrontend guarded = request -> { control.checkpoint(); var result = frontend.analyze(request); control.checkpoint(); return result; };
        var policy = new UniversalArchitecturePipeline.Policy(
                new UniversalBuildIngestion.Policy(Optional.of(input.release()),Optional.of(input.release()),Optional.of("UTF-8"),32_768,defaults.maxCharacters(),defaults.maxTokens(),128),
                new RepositoryInputDecoder.Policy("UTF-8",500_000,10_000_000),guarded,JavaParserFrontend.PROVIDER,
                dependencies.resolutions(),platform.platform(),Optional.of(new MavenBuildModelProvider()),dependencies.externalPoms(),MavenScan.POLICY,
                100_000,1_000,UniversalArchitecturePipeline.TruthEvaluationMode.AUTO,input.spring().scopes(),input.spring().space(),input.spring().baseline(),
                com.evolution.analysis.spring.truth.TruthRegionEvaluation.Limits.conservative()).withReadiness(readiness.identity());
        control.stage(AnalysisJobs.Stage.ANALYSIS);
        var detailed = new UniversalArchitecturePipeline().runWithEvidence(acquisition,policy); control.checkpoint();
        PolyglotScan.Result polyglot;
        try(var intake=new RepositoryIntake(intakeDirectory)){
            polyglot=new PolyglotScan().run(control.id(),intake,readiness,EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity()),Optional.empty(),intakeDirectory.resolve("semantic-work"),control);
        }
        var gaps = new TreeSet<>(detailed.report().gaps()); gaps.addAll(dependencies.gaps());
        gaps.addAll(readiness.gaps(acquisition.snapshot().orElseThrow().identity()));
        gaps.addAll(polyglot.gaps());
        platformLedgers.forEach(ledger->gaps.addAll(ledger.gaps()));
        var allPlatforms=new ArrayList<PlatformSymbolResult>();allPlatforms.add(platform);allPlatforms.addAll(additionalPlatforms);
        gaps.addAll(CapabilityGapNormalizer.normalize(EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity()),
                EvidenceNormalizationInput.builder().repositoryAcquisitions(List.of(acquisition)).platformResults(allPlatforms).build()).gaps());
        if (detailed.graphInput().isEmpty()) return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.copyOf(gaps),Optional.of(acquisition.identity()),Optional.of(detailed.report().identity()),inputProblems(acquisition));
        control.stage(AnalysisJobs.Stage.GRAPH);
        var captured = detailed.graphInput().orElseThrow(); gaps.addAll(captured.gaps());
        var graphLedgers=new ArrayList<>(dependencies.ledgers());graphLedgers.addAll(platformLedgers);
        var graph = new CanonicalGraphBuilder().build(new CanonicalGraphInput(captured.repository(),captured.build(),captured.ingestion(),captured.spring(),captured.issues(),List.copyOf(gaps),captured.acquisition(),captured.javaEvidence()),
                graphLedgers,store->new PolyglotScan().project(polyglot,store,control));
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
                var score = new ArchitectureAssessmentService().evaluate(metrics,evaluation,queries,Optional.of(ArchitectureScoreModel.candidate(selected.scoreScope())),List.of(),ArchitectureAssessment.Limits.defaults());
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
        return new AnalysisJobs.Outcome(completion,Optional.of(graph),reports,List.copyOf(gaps),Optional.of(acquisition.identity()),Optional.of(detailed.report().identity()),inputProblems(acquisition));
    }
    private static List<AnalysisJobs.InputProblem> inputProblems(RepositoryAcquisitionResult acquisition) {
        return acquisition.problems().stream().map(p -> new AnalysisJobs.InputProblem(acquisition.provider(),acquisition.identity(),p)).toList();
    }
    private RepositoryAcquisitionResult acquire(Path source,Set<String> excluded,AnalysisJobs.Control control)throws IOException {
        var full=new TreeSet<>(input.exclusions());full.add(".git");
        if(intakeDirectory.startsWith(source))full.add(source.relativize(intakeDirectory).toString().replace('\\','/'));
        try(var intake=new RepositoryIntake(intakeDirectory)){
            var request=new StreamingAcquisitionRequest(input.repository(),input.revision(),input.dirty(),List.copyOf(full));
            var receipt=intake.localComplete(source,control.id(),request,control);control.checkpoint();
            if(receipt.manifest().isEmpty())throw new IOException(receipt.reason());
            return intake.compatibility(control.id(),input.repository(),input.revision(),input.dirty(),input.includeTests(),false,List.copyOf(excluded));
        }
    }
    static Path safe(Path input) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        for (Path parent = path; parent != null; parent = parent.getParent()) if (Files.isSymbolicLink(parent)) throw new IOException("UNSAFE_PATH");
        return path;
    }
}
