package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Automatic normalized constructor, field, method and dual-namespace injection evidence. */
public final class SpringInjectionSites {
    public enum Kind { CONSTRUCTOR, FIELD, METHOD, BEAN_PARAMETER, RESOURCE, VALUE }
    public enum Status { ACQUIRED, UNKNOWN }
    public record Site(EntityIdentity owner,EntityIdentity element,Kind kind,Optional<JavaType> type,
                       Optional<String> name,Optional<String> qualifier,boolean required,Status status,ConditionEvidence evidence) {
        public Site {Objects.requireNonNull(type);Objects.requireNonNull(name);Objects.requireNonNull(qualifier);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    public record Result(ContentDigest inputIdentity,List<Site> sites,List<UniversalSpringEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Result {sites=List.copyOf(sites);issues=List.copyOf(issues);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    private static final Set<String> INJECT=Set.of("org.springframework.beans.factory.annotation.Autowired","javax.inject.Inject","jakarta.inject.Inject",
            "javax.annotation.Resource","jakarta.annotation.Resource","org.springframework.beans.factory.annotation.Value");
    private static final Set<String> MEMBER_METADATA=Set.of("org.springframework.beans.factory.annotation.Autowired",
            "org.springframework.beans.factory.annotation.Qualifier","javax.inject.Inject","jakarta.inject.Inject","javax.inject.Named","jakarta.inject.Named");
    public static Result acquire(SpringSourceEvidence source,ConstructorInjectionIngestion.Result constructors,FrameworkGeneration generation,int maxSites) {
        if(maxSites<1)throw new IllegalArgumentException("Positive injection limit required");
        if(!constructors.analysis().equals(source.frontend().analysis()))throw new IllegalArgumentException("Foreign constructor evidence");
        var input=IngestionEvidence.digest(List.of("spring.injection-sites:m4uv2.2-method-qualifiers-v1",source.identity(),constructors.identity(),generation,maxSites));
        var sites=new TreeMap<String,Site>();var issues=new ArrayList<UniversalSpringEvidence.Issue>();
        for(var row:constructors.rows())if(row.status()==ConstructorInjectionIngestion.Status.SELECTED)for(var p:row.parameters()) {
            var metadata=metadata(source,p.parameter(),generation,issues);
            var site=new Site(row.type(),p.parameter(),Kind.CONSTRUCTOR,Optional.of(p.type()),Optional.empty(),metadata.qualifier(),true,
                    metadata.valid()?Status.ACQUIRED:Status.UNKNOWN,p.evidence());sites.put(p.parameter().value(),site);
        }
        for(var declaration:source.declarations().values())if(declaration.entity().origin()==EntityOrigin.PROJECT) {
            var element=declaration.entity().identity();
            var annotations=source.annotations(element).stream().filter(a->a.name().filter(n -> INJECT.contains(n)||n.equals(BeanMethodIngestion.BEAN)).isPresent()).toList();
            if(annotations.isEmpty())continue;
            var annotation=annotations.getFirst();var name=annotation.name().orElseThrow();
            var owner=source.owner(element);while(owner.isPresent()&&source.declarations().get(owner.orElseThrow()).entity().kind()!=EntityKind.TYPE)owner=source.owner(owner.orElseThrow());
            if(owner.isEmpty()){issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.EVIDENCE_MISSING,element.value(),List.of(annotation.evidence())));continue;}
            boolean namespace=compatible(name,generation);if(!namespace)issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.NAMESPACE_INCOMPATIBLE,element.value(),List.of(annotation.evidence())));
            var kind=name.equals(BeanMethodIngestion.BEAN)?Kind.BEAN_PARAMETER:name.endsWith(".Resource")?Kind.RESOURCE:name.endsWith(".Value")?Kind.VALUE:
                    declaration.entity().kind()==EntityKind.CONSTRUCTOR?Kind.CONSTRUCTOR:declaration.entity().kind()==EntityKind.FIELD?Kind.FIELD:Kind.METHOD;
            var shape=source.frontend().memberDeclarations().stream().filter(m -> m.member().equals(element)).findFirst();
            boolean memberComplete=kind!=Kind.FIELD&&kind!=Kind.METHOD || shape.filter(m -> !m.staticMember()&&!m.abstractMember()&&!m.genericMethod()).isPresent()
                    &&declaration.status()==SemanticStatus.RESOLVED
                    &&source.annotations(element).stream().allMatch(a -> a.name().filter(MEMBER_METADATA::contains).isPresent());
            var parameters=source.relationships().stream().filter(r->r.source().equals(element)&&r.kind().value().equals("java.has-parameter")&&r.target() instanceof RelationshipTarget.Resolved)
                    .map(r->((RelationshipTarget.Resolved)r.target()).target()).distinct().toList();
            List<EntityIdentity> targets=declaration.entity().kind()==EntityKind.FIELD||declaration.entity().kind()==EntityKind.PARAMETER?List.of(element):parameters;
            if(targets.isEmpty()&&kind!=Kind.CONSTRUCTOR&&kind!=Kind.BEAN_PARAMETER)issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED,element.value(),List.of(annotation.evidence())));
            for(var target:targets) {
                boolean valid=namespace&&annotations.size()==1&&kind!=Kind.VALUE&&memberComplete;
                Optional<String> resourceName=kind==Kind.FIELD?shape.map(MemberDeclarationRecord::name):Optional.empty();boolean required=true;
                try {
                    var values=LiteralConditionAnnotation.parse(annotation.use().spelling());
                    if(kind==Kind.RESOURCE){if(!Set.of("name").containsAll(values.keySet()))valid=false;String explicit=LiteralConditionAnnotation.string(values,"name","");if(!explicit.isEmpty())resourceName=Optional.of(explicit);}
                    else if(kind!=Kind.VALUE&&kind!=Kind.BEAN_PARAMETER){if(!Set.of("required").containsAll(values.keySet()))valid=false;required=LiteralConditionAnnotation.bool(values,"required",true);}
                }catch(IllegalArgumentException malformed){valid=false;}
                var types=source.frontend().types().stream().filter(t->t.owner().equals(Optional.of(target))&&Set.of("java.parameter-type","java.field-type").contains(t.role().value())).map(TypeUseRecord::type).toList();
                var metadata=metadata(source,target,generation,issues);
                if(kind==Kind.METHOD)metadata=methodMetadata(source,element,target,metadata,generation,issues);
                valid&=metadata.valid()&&types.size()==1&&types.getFirst().status()==SemanticStatus.RESOLVED;
                if(!valid)issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED,target.value(),List.of(annotation.evidence())));
                sites.put(target.value(),new Site(kind==Kind.BEAN_PARAMETER?element:owner.orElseThrow(),target,kind,types.size()==1?Optional.of(types.getFirst()):Optional.empty(),resourceName,metadata.qualifier(),required,valid?Status.ACQUIRED:Status.UNKNOWN,source.evidence(target)));
            }
        }
        var result=new ArrayList<Site>();int seen=0;
        for(var site:sites.values()) {
            if(++seen<=maxSites)result.add(site);
            else {result.add(new Site(site.owner(),site.element(),site.kind(),site.type(),site.name(),site.qualifier(),site.required(),Status.UNKNOWN,site.evidence()));issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.RESOURCE_LIMIT,site.element().value(),List.of(site.evidence())));}
        }
        return new Result(input,result,issues,UniversalSpringEvidence.gaps(source.manifest().snapshot().identity(),input,issues));
    }
    private record Metadata(Optional<String> qualifier,boolean valid) {}
    private static Metadata methodMetadata(SpringSourceEvidence source,EntityIdentity method,EntityIdentity parameter,
                                           Metadata parameterMetadata,FrameworkGeneration generation,List<UniversalSpringEvidence.Issue> issues) {
        // Framework 6.2.0 checks parameter qualifiers first. A matched parameter qualifier
        // takes precedence, even over a different method qualifier. Only void methods
        // use method annotations as a fallback (factory return metadata is not a request).
        if(!parameterMetadata.valid()||parameterMetadata.qualifier().isPresent())return parameterMetadata;
        var methodQualifiers=source.annotations(method).stream().filter(a -> a.name().filter(n ->
                n.equals("org.springframework.beans.factory.annotation.Qualifier")||n.equals("javax.inject.Named")||n.equals("jakarta.inject.Named")).isPresent()).toList();
        if(methodQualifiers.isEmpty())return parameterMetadata;
        var returns=source.frontend().types().stream().filter(t -> t.owner().equals(Optional.of(method))&&t.role().value().equals("java.returns"))
                .map(TypeUseRecord::type).toList();
        if(returns.size()!=1||returns.getFirst().status()!=SemanticStatus.RESOLVED) {
            issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.EVIDENCE_MISSING,parameter.value(),List.of(source.evidence(method))));
            return new Metadata(Optional.empty(),false);
        }
        if(returns.getFirst().kind()!=JavaType.Kind.VOID)return parameterMetadata;
        // Candidate matching currently supplies direct Spring Qualifier proofs, not
        // annotation-kind-aware JSR-330 or combined qualifier proofs.
        if(methodQualifiers.size()!=1||!methodQualifiers.getFirst().name().orElseThrow().equals("org.springframework.beans.factory.annotation.Qualifier")) {
            issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED,parameter.value(),List.of(source.evidence(method))));
            return new Metadata(Optional.empty(),false);
        }
        return metadata(source,method,generation,issues);
    }
    private static Metadata metadata(SpringSourceEvidence source,EntityIdentity element,FrameworkGeneration generation,List<UniversalSpringEvidence.Issue> issues) {
        var qualifiers=new TreeSet<String>();boolean valid=true;
        for(var a:source.annotations(element))if(a.name().filter(n->n.equals("org.springframework.beans.factory.annotation.Qualifier")||n.equals("javax.inject.Named")||n.equals("jakarta.inject.Named")).isPresent()) {
            try {var values=LiteralConditionAnnotation.parse(a.use().spelling());
                valid&=compatible(a.name().orElseThrow(),generation)&&Set.of("value").containsAll(values.keySet());
                qualifiers.add(LiteralConditionAnnotation.string(values,"value",""));}
            catch(IllegalArgumentException invalid){valid=false;}
        }else if(a.name().isEmpty())valid=false;
        if(qualifiers.size()>1)valid=false;
        if(!valid)issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED,element.value(),List.of(source.evidence(element))));
        return new Metadata(qualifiers.size()==1?Optional.of(qualifiers.first()):Optional.empty(),valid);
    }
    private static boolean compatible(String name,FrameworkGeneration generation) {
        if(name.startsWith("javax."))return generation.namespace()==FrameworkGeneration.Namespace.JAVAX;
        if(name.startsWith("jakarta."))return generation.namespace()==FrameworkGeneration.Namespace.JAKARTA;
        return generation.consistent();
    }
    private SpringInjectionSites() {}
}
