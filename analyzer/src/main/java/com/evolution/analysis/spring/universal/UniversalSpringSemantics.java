package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.FrontendResult;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** One source-unit projection connecting M4U.1 evidence to dynamic candidates, injections, conditions and routes. */
public final class UniversalSpringSemantics {
    public enum BindingStatus { UNIQUE_CANDIDATE, MULTIPLE_CANDIDATES, UNKNOWN }
    public record CandidateBinding(ContentDigest site,EntityIdentity owner,List<EntityIdentity> candidates,BindingStatus status,ConditionEvidence evidence) {
        public CandidateBinding {candidates=candidates.stream().distinct().sorted().toList();}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    public record ExpressionRow(EntityIdentity owner,ConditionEvidence evidence,BasicSpelEvaluator.Result result) {}
    public record Path(ContentDigest route,List<EntityIdentity> components,List<ContentDigest> bindingEvidence) {
        public Path {components=List.copyOf(components);bindingEvidence=List.copyOf(bindingEvidence);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    public record Result(ContentDigest inputIdentity,FrameworkGeneration generation,SpringDataRepositories.Result repositories,
                         SpringInjectionSites.Result injections,WebRouteMapper.Result web,List<CandidateBinding> bindings,
                         List<ExpressionRow> expressions,List<Path> paths,AutoConfigurationMetadata.Result metadata,
                         List<UniversalSpringEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Result {bindings=List.copyOf(bindings);expressions=List.copyOf(expressions);paths=List.copyOf(paths);issues=List.copyOf(issues);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    public static Result analyze(SpringSourceEvidence source,SpringFrameworkEvidence framework,ComponentScanIngestion.Result components,
                                 ConstructorInjectionIngestion.Result constructors,ConfigDataIngestion.Result config,int limit) {
        return analyze(source,framework,components,constructors,config,List.of(),limit);
    }
    public static Result analyze(SpringSourceEvidence source,SpringFrameworkEvidence framework,ComponentScanIngestion.Result components,
                                 ConstructorInjectionIngestion.Result constructors,ConfigDataIngestion.Result config,
                                 List<AutoConfigurationMetadata.Resource> resources,int limit) {
        if(!components.frontendEvidence().equals(IngestionEvidence.digest(source.frontend()))||!components.frameworkEvidence().equals(framework.identity()))
            throw new IllegalArgumentException("Foreign component acquisition");
        Optional<Boolean> override=Optional.empty();String property=config.properties().get("spring.main.allow-bean-definition-overriding");
        if("true".equalsIgnoreCase(property)||"false".equalsIgnoreCase(property))override=Optional.of(Boolean.parseBoolean(property));
        var generation=FrameworkGeneration.from(framework,override);
        var input=IngestionEvidence.digest(List.of(UniversalSpringEvidence.PROVIDER,source.identity(),framework.identity(),components.identity(),constructors.identity(),config.identity(),resources.stream().map(AutoConfigurationMetadata.Resource::identity).toList(),limit));
        var metadata=AutoConfigurationMetadata.read(resources,generation,source.manifest().snapshot().identity(),1_000_000,limit);
        var repositories=SpringDataRepositories.synthesize(source,config.properties(),limit);var injections=SpringInjectionSites.acquire(source,constructors,generation,limit);
        var web=WebRouteMapper.map(source,config.properties(),limit);var issues=new ArrayList<UniversalSpringEvidence.Issue>();
        if(!generation.consistent()&&source.frontend().annotations().stream().anyMatch(a->a.spelling().contains("Spring")||a.spelling().contains("Autowired")))
            issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.FRAMEWORK_VERSION_UNKNOWN,"framework-generation",List.of(UniversalSpringEvidence.derived(input,"framework-generation"))));
        if(property!=null&&override.isEmpty())issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.PROPERTY_UNRESOLVED,"bean-override-policy",List.of(UniversalSpringEvidence.derived(config.identity(),"bean-override-policy"))));
        var types=new TreeMap<EntityIdentity,Set<EntityIdentity>>();var names=new TreeMap<EntityIdentity,String>();
        for(var row:components.rows())if(row.status()==ComponentScanIngestion.Status.INCLUDED){types.put(row.type(),source.ancestry(row.type(),limit));row.beanName().ifPresent(n->names.put(row.type(),n));}
        for(var row:repositories.rows())if(row.status()==SpringDataRepositories.Status.CANDIDATE){types.put(row.repository(),new TreeSet<>(row.exposedTypes()));row.beanName().ifPresent(n->names.put(row.repository(),n));}
        var bindings=new ArrayList<CandidateBinding>();
        for(var site:injections.sites()) {
            var candidates=new ArrayList<EntityIdentity>();
            // Resource requires a final registry and processor-policy proof. This
            // structural projection cannot substitute qualifier filtering for its name path.
            if(site.kind()!=SpringInjectionSites.Kind.RESOURCE&&site.status()==SpringInjectionSites.Status.ACQUIRED&&site.type().isPresent()) {
                var type=site.type().orElseThrow();
                // Generic assignability is an existing M4C descriptor obligation, never erasure-only certainty.
                if(type.components().isEmpty()&&type.target().isPresent())for(var entry:types.entrySet())if(entry.getValue().contains(type.target().orElseThrow())
                        &&site.name().map(n->n.equals(names.get(entry.getKey()))).orElse(true)
                        &&site.qualifier().map(q->q.equals(names.get(entry.getKey()))||source.annotations(entry.getKey()).stream().anyMatch(a->qualifier(a,q))).orElse(true))candidates.add(entry.getKey());
            }
            BindingStatus status=candidates.size()==1?BindingStatus.UNIQUE_CANDIDATE:candidates.size()>1?BindingStatus.MULTIPLE_CANDIDATES:BindingStatus.UNKNOWN;
            var evidence=new ConditionEvidence.Derived(List.of(input,site.evidence().identity(),repositories.identity(),components.identity()),UniversalSpringEvidence.PROVIDER,"candidate-binding:"+site.identity().value());
            bindings.add(new CandidateBinding(site.identity(),site.owner(),candidates,status,evidence));
            if(status==BindingStatus.UNKNOWN)issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.BINDING_UNKNOWN,site.identity().value(),List.of(site.evidence())));
            if(status==BindingStatus.MULTIPLE_CANDIDATES)issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.BINDING_AMBIGUOUS,site.identity().value(),List.of(site.evidence())));
        }
        var expressions=new ArrayList<ExpressionRow>();
        for(var declaration:source.declarations().values())for(var annotation:source.annotations(declaration.entity().identity()))if(annotation.name().filter("org.springframework.boot.autoconfigure.condition.ConditionalOnExpression"::equals).isPresent()) {
            try {
                String expression=LiteralConditionAnnotation.string(LiteralConditionAnnotation.parse(annotation.use().spelling()),"value","true");
                var result=BasicSpelEvaluator.evaluate(expression,config.properties(),annotation.evidence(),source.manifest().snapshot().identity(),BasicSpelEvaluator.Limits.defaults());
                expressions.add(new ExpressionRow(declaration.entity().identity(),annotation.evidence(),result));
            }catch(IllegalArgumentException invalid){issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.EXPRESSION_UNSUPPORTED,annotation.use().canonicalName(),List.of(annotation.evidence())));}
        }
        var paths=new ArrayList<Path>();
        for(var route:web.routes()) {
            var queue=new ArrayDeque<Path>();queue.add(new Path(route.identity(),List.of(route.controller()),List.of()));
            while(!queue.isEmpty()) {
                var path=queue.removeFirst();if(paths.size()>=limit){issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.RESOURCE_LIMIT,"architecture-paths",List.of(route.evidence())));break;}
                paths.add(path);if(path.components().size()>=16){issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.RESOURCE_LIMIT,"architecture-path-depth",List.of(route.evidence())));continue;}
                for(var binding:bindings)if(binding.owner().equals(path.components().getLast()))for(var candidate:binding.candidates())if(!path.components().contains(candidate)) {
                    if(paths.size()+queue.size()>=limit){issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.RESOURCE_LIMIT,"architecture-paths",List.of(route.evidence())));break;}
                    var sequence=new ArrayList<>(path.components());sequence.add(candidate);var proof=new ArrayList<>(path.bindingEvidence());proof.add(binding.identity());queue.addLast(new Path(route.identity(),sequence,proof));
                }
            }
        }
        var gaps=new TreeSet<>(components.gaps());gaps.addAll(constructors.gaps());gaps.addAll(config.gaps());gaps.addAll(repositories.gaps());gaps.addAll(injections.gaps());gaps.addAll(web.gaps());
        expressions.forEach(e->gaps.addAll(e.result().gaps()));gaps.addAll(metadata.gaps());gaps.addAll(UniversalSpringEvidence.gaps(source.manifest().snapshot().identity(),input,issues));
        return new Result(input,generation,repositories,injections,web,bindings,expressions,paths,metadata,issues,List.copyOf(gaps));
    }
    private static boolean qualifier(SpringSourceEvidence.Annotation annotation,String expected) {
        if(annotation.name().filter("org.springframework.beans.factory.annotation.Qualifier"::equals).isEmpty())return false;
        try{return expected.equals(LiteralConditionAnnotation.string(LiteralConditionAnnotation.parse(annotation.use().spelling()),"value",""));}catch(IllegalArgumentException invalid){return false;}
    }
    private UniversalSpringSemantics() {}
}
