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
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("repository.ingestion-pipeline","m4u.2");
    public enum Status { ANALYZED, NOT_ANALYZED, REJECTED, FAILED }
    public record Unit(UniversalSourceIngestion.SourceSet sourceSet,Status status,Optional<FrontendResult> frontend,
                       Optional<ComponentScanIngestion.Result> components,Optional<ConstructorInjectionIngestion.Result> constructors,
                       Optional<UniversalSpringSemantics.Result> spring) {
        public Unit(UniversalSourceIngestion.SourceSet sourceSet,Status status,Optional<FrontendResult> frontend,
                    Optional<ComponentScanIngestion.Result> components,Optional<ConstructorInjectionIngestion.Result> constructors) {
            this(sourceSet,status,frontend,components,constructors,Optional.empty());
        }
    }
    public record Result(ContentDigest inputIdentity,UniversalSourceIngestion.Result sourceIngestion,List<Unit> units,
                         List<IngestionEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Result {units=List.copyOf(units);issues=List.copyOf(issues);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(List.of(inputIdentity,sourceIngestion.identity(),units,issues,gaps));}
    }
    public Result run(UniversalSourceIngestion.Result sources,SemanticFrontend frontend,VersionedIdentifier frontendProvider,int maxTypes,int maxParameters) {
        Objects.requireNonNull(frontend);Objects.requireNonNull(frontendProvider);
        if(maxTypes<1||maxParameters<1)throw new IllegalArgumentException("Positive ingestion limits required");
        var identity=IngestionEvidence.digest(List.of(PROVIDER,sources.identity(),frontendProvider,maxTypes,maxParameters));
        var units=new ArrayList<Unit>();var issues=new ArrayList<IngestionEvidence.Issue>();var gaps=new TreeSet<>(sources.gaps());
        for(var source:sources.outcomes()) {
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
                var framework=framework(request);
                var components=new ComponentScanIngestion().scan(request.manifest(),result,framework,maxTypes);
                scanned=Optional.of(components);gaps.addAll(components.gaps());
                var constructors=new ConstructorInjectionIngestion().ingest(result,components,framework,maxParameters);
                gaps.addAll(constructors.gaps());
                var spring=UniversalSpringSemantics.analyze(new SpringSourceEvidence(request.manifest(),result,framework),framework,
                        components,constructors,source.configuration(),source.metadata(),Math.max(maxTypes,maxParameters));
                gaps.addAll(spring.gaps());
                units.add(new Unit(source.sourceSet(),Status.ANALYZED,Optional.of(result),Optional.of(components),Optional.of(constructors),Optional.of(spring)));
            }catch(RuntimeException failure) {
                // Retain the failed source-set obligation and continue unrelated modules. Never expose exception text.
                var reason=failure instanceof FrontendInputException?IngestionEvidence.Reason.SEMANTIC_INPUT_REJECTED:IngestionEvidence.Reason.SEMANTIC_PROVIDER_FAILED;
                String code=failure instanceof FrontendInputException input?input.diagnostic().code():failure.getClass().getSimpleName();
                if(!code.matches("[A-Za-z][A-Za-z0-9.-]{0,120}"))code="provider-error";
                var issue=new IngestionEvidence.Issue(reason,source.sourceSet()+"#"+code,List.of(identity,IngestionEvidence.digest(request.manifest().identity())));
                issues.add(issue);gaps.addAll(IngestionEvidence.gaps(request.manifest().snapshot().identity(),PROVIDER,identity,List.of(issue)));
                units.add(new Unit(source.sourceSet(),failure instanceof FrontendInputException?Status.REJECTED:Status.FAILED,acquired,scanned,Optional.empty()));
            }
        }
        return new Result(identity,sources,units,issues.stream().sorted(Comparator.comparing(IngestionEvidence.Issue::identity)).toList(),List.copyOf(gaps));
    }
    /** Continue an acquired unit into real M4C plans under an explicitly evidenced scope.
     * Missing order/container closure is represented by UNKNOWN completeness, never inferred from sorting. */
    public SourceToSpringPlan.Result prepareSpring(Result pipeline,UniversalSourceIngestion.SourceSet sourceSet,
                                                   SourceToSpringPlan.Scope scope,int limit) {
        var input=pipeline.sourceIngestion().outcomes().stream().filter(o -> o.sourceSet().equals(sourceSet)).findFirst().orElseThrow();
        var unit=pipeline.units().stream().filter(u -> u.sourceSet().equals(sourceSet)).findFirst().orElseThrow();
        if(unit.status()!=Status.ANALYZED||input.request().isEmpty())
            throw new IllegalArgumentException("Spring plans require an analyzed exact source set");
        var request=input.request().orElseThrow();
        var source=new SpringSourceEvidence(request.manifest(),unit.frontend().orElseThrow(),framework(request));
        var build=SpringBuildContext.fromExactRequest(request,pipeline.sourceIngestion().identity());
        var normalized=SourceToSpringPlan.normalize(build,source,unit.components().orElseThrow(),unit.constructors().orElseThrow(),scope,limit);
        var inherited=new TreeSet<>(pipeline.gaps());inherited.addAll(normalized.gaps());
        return new SourceToSpringPlan.Result(normalized.inputIdentity(),normalized.inventory(),normalized.conditions(),
                normalized.binding(),List.copyOf(inherited),normalized.methodSelections());
    }
    private static SpringFrameworkEvidence framework(FrontendRequest request) {
        var artifacts=new ArrayList<SpringFrameworkEvidence.Artifact>();
        for(var entry:request.manifest().classpath()) {
            String name=entry.logicalName();
            if(name.matches("[^:@]+:[^:@]+:[^:@]+@jar"))
                artifacts.add(new SpringFrameworkEvidence.Artifact(name.substring(0,name.length()-4),name,entry.contentDigest()));
        }
        var versions=artifacts.stream().filter(a->a.groupId().equals("org.springframework")).map(SpringFrameworkEvidence.Artifact::version).distinct().toList();
        var bootVersions=artifacts.stream().filter(a->a.groupId().equals("org.springframework.boot")).map(SpringFrameworkEvidence.Artifact::version).distinct().toList();
        boolean compatible=versions.size()<=1&&bootVersions.size()<=1;
        if(!versions.isEmpty()&&!bootVersions.isEmpty())compatible&=switch(versions.getFirst()) {
            case "5.3.31"->bootVersions.equals(List.of("2.7.18"));case "6.1.14"->bootVersions.equals(List.of("3.3.5"));
            case "6.2.0"->bootVersions.equals(List.of("3.4.0"));default->false;
        };
        return new SpringFrameworkEvidence(compatible,Optional.of(request.plan().classpathManifest()),artifacts);
    }
}
