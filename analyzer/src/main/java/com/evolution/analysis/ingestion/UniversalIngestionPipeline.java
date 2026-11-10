package com.evolution.analysis.ingestion;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.spring.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.universal.*;
import java.util.*;

/** Executes replaceable semantic providers over an already acquired, exact source/build context. */
public final class UniversalIngestionPipeline {
    private final AnalysisWorkspace workspace;
    public UniversalIngestionPipeline(){this(AnalysisWorkspace.MEMORY);}
    public UniversalIngestionPipeline(AnalysisWorkspace workspace){this.workspace=Objects.requireNonNull(workspace);}
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("repository.ingestion-pipeline","m8.4-spooled-source-v6");
    public enum Status { ANALYZED, NOT_ANALYZED, REJECTED, FAILED }
    public record Unit(UniversalSourceIngestion.SourceSet sourceSet,Status status,Optional<FrontendResult> frontend,
                       Optional<ComponentScanIngestion.Result> components,Optional<ConstructorInjectionIngestion.Result> constructors,
                       Optional<UniversalSpringSemantics.Result> spring,Optional<SourceWiringCandidates.Result> sourceWiring) {
        public Unit(UniversalSourceIngestion.SourceSet sourceSet,Status status,Optional<FrontendResult> frontend,
                    Optional<ComponentScanIngestion.Result> components,Optional<ConstructorInjectionIngestion.Result> constructors,Optional<UniversalSpringSemantics.Result> spring) {
            this(sourceSet,status,frontend,components,constructors,spring,Optional.empty());
        }
        public Unit(UniversalSourceIngestion.SourceSet sourceSet,Status status,Optional<FrontendResult> frontend,
                    Optional<ComponentScanIngestion.Result> components,Optional<ConstructorInjectionIngestion.Result> constructors) {
            this(sourceSet,status,frontend,components,constructors,Optional.empty());
        }
    }
    public record Result(ContentDigest inputIdentity,UniversalSourceIngestion.Result sourceIngestion,List<Unit> units,
                         List<IngestionEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Result {units=AnalysisWorkspace.ReplayList.copy(units);issues=AnalysisWorkspace.ReplayList.copy(issues);gaps=AnalysisWorkspace.ReplayList.copy(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(List.of(inputIdentity,sourceIngestion.identity(),units,issues,gaps));}
    }
    public Result run(UniversalSourceIngestion.Result sources,SemanticFrontend frontend,VersionedIdentifier frontendProvider,int maxTypes,int maxParameters) {
        return run(sources,frontend,frontendProvider,maxTypes,maxParameters,Runtime.getRuntime().availableProcessors(),Math.max(32L<<20,Runtime.getRuntime().maxMemory()/3));
    }
    /** Scheduling bounds are operational, not semantic inputs. Merge in input order so
     * identical captured evidence yields identical results at every worker count. */
    public Result run(UniversalSourceIngestion.Result sources,SemanticFrontend frontend,VersionedIdentifier frontendProvider,int maxTypes,int maxParameters,int parallelism,long memoryBudget) {
        Objects.requireNonNull(frontend);Objects.requireNonNull(frontendProvider);
        if(maxTypes<1||maxParameters<1||parallelism<1||memoryBudget<1)throw new IllegalArgumentException("Positive ingestion limits required");
        var identity=IngestionEvidence.digest(List.of(PROVIDER,sources.identity(),frontendProvider,maxTypes,maxParameters));
        if(!(frontend instanceof ConcurrentSemanticFrontend))
            return analyze(sources,sources.outcomes(),frontend,frontendProvider,maxTypes,maxParameters,identity);
        if(parallelism==1||sources.outcomes().size()<2) {
            var serial=new ArrayList<Result>();
            for(var outcome:sources.outcomes())try{serial.add(analyze(sources,List.of(outcome),frontend,frontendProvider,maxTypes,maxParameters,identity));}
                finally{((ConcurrentSemanticFrontend)frontend).releaseCompletedContexts();}
            return merge(sources,identity,serial);
        }
        int workers=Math.min(parallelism,sources.outcomes().size());
        var executor=java.util.concurrent.Executors.newFixedThreadPool(workers);
        var completion=new java.util.concurrent.ExecutorCompletionService<Map.Entry<Integer,Result>>(executor);
        var active=new HashMap<java.util.concurrent.Future<Map.Entry<Integer,Result>>,Long>();
        var results=new Result[sources.outcomes().size()];int next=0,finished=0;long admitted=0;
        try {
            while(finished<results.length) {
                while(next<results.length&&active.size()<workers) {
                    var outcome=sources.outcomes().get(next);long cost=outcome.request().map(((ConcurrentSemanticFrontend)frontend)::estimatedWorkingBytes).orElse(1L);
                    if(cost<1)throw new IllegalArgumentException("Positive frontend working estimate required");
                    long headroom=Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
                    long capacity=Math.min(memoryBudget,Math.max(0,headroom-(64L<<20)));
                    if(!active.isEmpty()&&cost>capacity-admitted)break;
                    int index=next++;var task=completion.submit(()->Map.entry(index,analyze(sources,List.of(outcome),frontend,frontendProvider,maxTypes,maxParameters,identity)));
                    active.put(task,cost);admitted+=cost;
                }
                var task=completion.take();active.remove(task);var result=task.get();results[result.getKey()]=result.getValue();finished++;
                // A provider may retain resolver/AST caches after returning neutral rows.
                // Reserve completed-context cost until a drained boundary releases them.
                if(active.isEmpty()){((ConcurrentSemanticFrontend)frontend).releaseCompletedContexts();admitted=0;}
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("INGESTION_CANCELLED");
        } catch(java.util.concurrent.ExecutionException failure) {
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            if(failure.getCause() instanceof Error error)throw error;
            throw new IllegalStateException("INGESTION_WORKER_FAILED",failure.getCause());
        } finally {for(var task:active.keySet())task.cancel(true);executor.shutdownNow();if(active.isEmpty())((ConcurrentSemanticFrontend)frontend).releaseCompletedContexts();}
        return merge(sources,identity,Arrays.asList(results));
    }
    private Result merge(UniversalSourceIngestion.Result sources,ContentDigest identity,Collection<Result> results) {
        var units=workspace.<Unit>list();var issues=workspace.<IngestionEvidence.Issue>list();var gaps=workspace.gaps(sources.gaps());
        for(var result:results){units.addAll(result.units());issues.addAll(result.issues());gaps.addAll(result.gaps());}
        var gapRows=workspace.<CapabilityGapRecord>list();gapRows.addAll(gaps);
        return new Result(identity,sources,workspace.freeze(units),workspace.distinct(issues,i->i.identity().value()),workspace.freeze(gapRows));
    }
    private Result analyze(UniversalSourceIngestion.Result sources,List<UniversalSourceIngestion.Outcome> outcomes,SemanticFrontend frontend,VersionedIdentifier frontendProvider,int maxTypes,int maxParameters,ContentDigest identity) {
        var units=workspace.<Unit>list();var issues=workspace.<IngestionEvidence.Issue>list();var gaps=workspace.gaps(outcomes==sources.outcomes()?sources.gaps():List.<com.evolution.analysis.evidence.CapabilityGapRecord>of());
        for(var source:outcomes) {
            if(source.request().isEmpty()) {
                units.add(new Unit(source.sourceSet(),Status.NOT_ANALYZED,Optional.empty(),Optional.empty(),Optional.empty()));continue;
            }
            var request=source.request().orElseThrow();
            Optional<FrontendResult> acquired=Optional.empty();Optional<ComponentScanIngestion.Result> scanned=Optional.empty();
            try {
                var result=frontend.analyze(request).validateFor(request);
                if(!result.frontend().equals(frontendProvider))throw new FrontendInputException("frontend.provider-identity","Frontend identity differs from the requested provider");
                acquired=Optional.of(result);
                gaps.addAll(CapabilityGapNormalizer.normalize(EvidenceContext.forAnalysis(request.manifest()),
                        EvidenceNormalizationInput.builder().frontendResults(List.of(result)).build()).gaps());
                if(request.sourceRecovery()) {
                    var issue=new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.CONTEXT_INCOMPLETE,
                            source.sourceSet()+"#SOURCE_RECOVERY",List.of(UniversalSpringEvidence.derived(identity,"source-recovery")));
                    gaps.addAll(UniversalSpringEvidence.gaps(request.manifest().snapshot().identity(),identity,List.of(issue)));
                }
                var framework=framework(request);
                var components=new ComponentScanIngestion().scan(request,result,framework,maxTypes);
                scanned=Optional.of(components);gaps.addAll(components.gaps());
                var constructors=new ConstructorInjectionIngestion().ingest(result,components,framework,maxParameters);
                gaps.addAll(constructors.gaps());
                var springSource=new SpringSourceEvidence(request,result,framework);
                var spring=UniversalSpringSemantics.analyze(springSource,framework,
                        components,constructors,source.configuration(),source.metadata(),Math.max(maxTypes,maxParameters));
                gaps.addAll(spring.gaps());
                var sourceWiring=new SourceWiringCandidates().analyze(springSource);
                units.add(new Unit(source.sourceSet(),Status.ANALYZED,Optional.of(result),Optional.of(components),Optional.of(constructors),Optional.of(spring),Optional.of(sourceWiring)));
            }catch(Exception | LinkageError | StackOverflowError failure) {
                if(failure instanceof java.util.concurrent.CancellationException)throw (RuntimeException)failure;
                // Retain the failed source-set obligation and continue unrelated modules. Never expose exception text.
                var reason=failure instanceof FrontendInputException?IngestionEvidence.Reason.SEMANTIC_INPUT_REJECTED:IngestionEvidence.Reason.SEMANTIC_PROVIDER_FAILED;
                String code=failure instanceof FrontendInputException input?input.diagnostic().code():failure.getClass().getSimpleName();
                if(!code.matches("[A-Za-z][A-Za-z0-9.-]{0,120}"))code="provider-error";
                var issue=new IngestionEvidence.Issue(reason,source.sourceSet()+"#"+code,List.of(identity,IngestionEvidence.digest(request.manifest().identity())));
                issues.add(issue);gaps.addAll(IngestionEvidence.gaps(request.manifest().snapshot().identity(),PROVIDER,identity,List.of(issue)));
                units.add(new Unit(source.sourceSet(),failure instanceof FrontendInputException?Status.REJECTED:Status.FAILED,acquired,scanned,Optional.empty()));
            }
        }
        var gapRows=workspace.<CapabilityGapRecord>list();gapRows.addAll(gaps);
        return new Result(identity,sources,workspace.freeze(units),workspace.distinct(issues,i->i.identity().value()),workspace.freeze(gapRows));
    }
    /** Continue an acquired unit into real M4C plans under an explicitly evidenced scope.
     * Missing order/container closure is represented by UNKNOWN completeness, never inferred from sorting. */
    public SourceToSpringPlan.Result prepareSpring(Result pipeline,UniversalSourceIngestion.SourceSet sourceSet,
                                                   SourceToSpringPlan.Scope scope,int limit) {
        var input=pipeline.sourceIngestion().outcomes().stream().filter(o -> o.sourceSet().equals(sourceSet)).findFirst().orElseThrow();
        var unit=pipeline.units().stream().filter(u -> u.sourceSet().equals(sourceSet)).findFirst().orElseThrow();
        if(unit.status()!=Status.ANALYZED||input.request().isEmpty())
            throw new IllegalArgumentException("Spring plans require an analyzed source set with explicit context evidence");
        var request=input.request().orElseThrow();
        var source=new SpringSourceEvidence(request,unit.frontend().orElseThrow(),framework(request));
        var build=SpringBuildContext.fromExactRequest(request,pipeline.sourceIngestion().identity());
        var normalized=SourceToSpringPlan.normalize(build,source,unit.components().orElseThrow(),unit.constructors().orElseThrow(),scope,limit);
        var inherited=workspace.gaps(pipeline.gaps());inherited.addAll(normalized.gaps());
        var gapRows=workspace.<CapabilityGapRecord>list();gapRows.addAll(inherited);
        return new SourceToSpringPlan.Result(normalized.inputIdentity(),normalized.inventory(),normalized.conditions(),
                normalized.binding(),workspace.freeze(gapRows),normalized.methodSelections());
    }
    private static SpringFrameworkEvidence framework(FrontendRequest request) {
        return SpringFrameworkEvidence.fromRequest(request);
    }
}
