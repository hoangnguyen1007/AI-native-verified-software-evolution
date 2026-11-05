package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import java.util.*;

/** Positive source evidence under an open container. These are type-compatible
 * possibilities, never selected bindings, an exhaustive bean registry or runtime DI. */
public final class SourceWiringCandidates {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("spring.source-wiring","m8.4-v1");
    public record Bean(EntityIdentity declaration,EntityIdentity exposedType,String kind,SourceSpan span) {}
    public record Site(EntityIdentity owner,EntityIdentity element,JavaType type,String kind,SourceSpan span) {}
    public record Match(Site site,Bean bean,String activation,String selection,String qualifierCompatibility,String genericCompatibility) {}
    public record Result(AnalysisIdentity analysis,ContentDigest input,List<Bean> beans,List<Site> sites,List<Match> matches) {
        public Result{Objects.requireNonNull(analysis);Objects.requireNonNull(input);beans=List.copyOf(beans);sites=List.copyOf(sites);matches=List.copyOf(matches);
            var knownBeans=new HashSet<>(beans);var knownSites=new HashSet<>(sites);if(matches.stream().anyMatch(m->!knownBeans.contains(m.bean())||!knownSites.contains(m.site())))throw new IllegalArgumentException("Foreign source wiring match");}
        public boolean observed(){return !beans.isEmpty()||!sites.isEmpty();}
    }
    private static final Set<String> COMPONENTS=Set.of("org.springframework.stereotype.Component","org.springframework.stereotype.Service","org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller","org.springframework.web.bind.annotation.RestController","org.springframework.context.annotation.Configuration","org.springframework.boot.autoconfigure.SpringBootApplication");
    private static final Set<String> INJECT=Set.of("org.springframework.beans.factory.annotation.Autowired","javax.inject.Inject","jakarta.inject.Inject","javax.annotation.Resource","jakarta.annotation.Resource");
    public Result analyze(FrontendRequest request,FrontendResult frontend) {
        return analyze(new SpringSourceEvidence(request,frontend,SpringFrameworkEvidence.fromRequest(request)));
    }
    public Result analyze(SpringSourceEvidence source) {
        var frontend=source.frontend();var input=IngestionEvidence.digest(List.of(PROVIDER,source.identity()));
        if(source.framework().artifacts().stream().noneMatch(a->a.groupId().startsWith("org.springframework")))return new Result(frontend.analysis(),input,List.of(),List.of(),List.of());
        var declarations=source.declarations();var types=new TreeMap<EntityIdentity,List<TypeUseRecord>>();
        var componentOwners=annotatedOwners(source,COMPONENTS);var injectionOwners=annotatedOwners(source,INJECT);
        var beanOwners=annotatedOwners(source,Set.of("org.springframework.context.annotation.Bean"));
        frontend.types().forEach(t->t.owner().ifPresent(owner->types.computeIfAbsent(owner,k->new ArrayList<>()).add(t)));
        var constructors=new TreeMap<EntityIdentity,List<EntityIdentity>>();var parameters=new TreeMap<EntityIdentity,List<EntityIdentity>>();
        for(var relation:source.relationships())if(relation.target() instanceof RelationshipTarget.Resolved target) {
            var declaration=declarations.get(target.target());if(declaration==null)continue;
            if(relation.kind().value().equals("java.declares")&&declaration.entity().kind()==EntityKind.CONSTRUCTOR)constructors.computeIfAbsent(relation.source(),k->new ArrayList<>()).add(target.target());
            if(relation.kind().value().equals("java.has-parameter"))parameters.computeIfAbsent(relation.source(),k->new ArrayList<>()).add(target.target());
        }
        var beans=new ArrayList<Bean>();var componentTypes=new HashSet<EntityIdentity>();
        for(var shape:frontend.typeDeclarations())if(shape.independent()&&!shape.abstractType()&&Set.of(TypeDeclarationRecord.Kind.CLASS,TypeDeclarationRecord.Kind.RECORD).contains(shape.kind())
                &&componentOwners.contains(shape.type())) {
            var declaration=declarations.get(shape.type());if(declaration.entity().origin()!=EntityOrigin.PROJECT)continue;
            beans.add(new Bean(shape.type(),shape.type(),"COMPONENT_DECLARATION",declaration.entity().declaration().orElseThrow()));componentTypes.add(shape.type());
        }
        for(var shape:frontend.typeDeclarations())if(shape.kind()==TypeDeclarationRecord.Kind.INTERFACE&&SpringDataRepositories.sourceRepositoryDeclaration(source,shape.type(),Math.max(1,declarations.size()+1))) {
            var declaration=declarations.get(shape.type());if(declaration.entity().origin()==EntityOrigin.PROJECT)
                beans.add(new Bean(shape.type(),shape.type(),"SPRING_DATA_INTERFACE_CANDIDATE",declaration.entity().declaration().orElseThrow()));
        }
        var beanMethods=new HashSet<EntityIdentity>();
        for(var declaration:declarations.values())if(declaration.entity().origin()==EntityOrigin.PROJECT&&declaration.entity().kind()==EntityKind.METHOD
                &&beanOwners.contains(declaration.entity().identity())) {
            var member=declaration.entity().identity();beanMethods.add(member);
            for(var type:types.getOrDefault(member,List.of()))if(type.role().value().equals("java.returns")&&nominal(type.type()))
                beans.add(new Bean(member,type.type().target().orElseThrow(),"BEAN_METHOD_DECLARATION",type.span()));
        }
        var sites=new TreeMap<String,Site>();
        for(var owner:componentTypes) {
            var choices=constructors.getOrDefault(owner,List.of()).stream().distinct().toList();
            for(var constructor:choices)if(choices.size()==1||injectionOwners.contains(constructor))for(var parameter:parameters.getOrDefault(constructor,List.of()))
                addSite(sites,types,owner,parameter,"CONSTRUCTOR","java.parameter-type");
        }
        for(var candidate:frontend.constructorCandidates())if(componentTypes.contains(candidate.owner())&&nominal(candidate.type())) {
            var declaration=declarations.get(candidate.field());
            var site=new Site(candidate.owner(),candidate.field(),candidate.type(),"LOMBOK_CONSTRUCTOR_CANDIDATE",declaration.entity().declaration().orElseThrow());
            sites.put(IngestionEvidence.digest(site).value(),site);
        }
        for(var declaration:declarations.values())if(declaration.entity().origin()==EntityOrigin.PROJECT) {
            var element=declaration.entity().identity();boolean injected=injectionOwners.contains(element),bean=beanMethods.contains(element);
            if(!injected&&!bean)continue;
            var owner=source.owner(element);while(owner.isPresent()&&declarations.get(owner.orElseThrow()).entity().kind()!=EntityKind.TYPE)owner=source.owner(owner.orElseThrow());
            if(owner.isEmpty())continue;
            if(declaration.entity().kind()==EntityKind.FIELD) {
                if(frontend.memberDeclarations().stream().anyMatch(m->m.member().equals(element)&&m.staticMember()))continue;
                addSite(sites,types,owner.orElseThrow(),element,"FIELD","java.field-type");
            } else if(Set.of(EntityKind.CONSTRUCTOR,EntityKind.METHOD).contains(declaration.entity().kind())) {
                if(frontend.memberDeclarations().stream().anyMatch(m->m.member().equals(element)&&m.staticMember())&&!bean)continue;
                for(var parameter:parameters.getOrDefault(element,List.of()))addSite(sites,types,owner.orElseThrow(),parameter,bean?"BEAN_PARAMETER":"EXPLICIT_INJECTION_PARAMETER","java.parameter-type");
            }
        }
        var matches=new ArrayList<Match>();var byType=new HashMap<EntityIdentity,List<Bean>>();
        for(var bean:beans)for(var parent:source.ancestry(bean.exposedType(),Math.max(1,declarations.size()+1)))byType.computeIfAbsent(parent,k->new ArrayList<>()).add(bean);
        for(var site:sites.values())for(var bean:byType.getOrDefault(site.type().target().orElseThrow(),List.of()))
            matches.add(new Match(site,bean,"UNKNOWN","NOT_SELECTED","NOT_EVALUATED","NOT_EVALUATED"));
        beans.sort(Comparator.comparing(b->IngestionEvidence.digest(b).value()));matches.sort(Comparator.comparing(m->IngestionEvidence.digest(m).value()));
        return new Result(frontend.analysis(),input,beans,List.copyOf(sites.values()),matches);
    }
    private static Set<EntityIdentity> annotatedOwners(SpringSourceEvidence source,Set<String> roots) {
        var annotationTypes=new HashSet<EntityIdentity>();source.frontend().typeDeclarations().stream().filter(t->t.kind()==TypeDeclarationRecord.Kind.ANNOTATION).forEach(t->annotationTypes.add(t.type()));
        var reverse=new HashMap<EntityIdentity,Set<EntityIdentity>>();
        for(var relation:source.relationships())if(relation.kind().value().equals("java.annotated-with")&&relation.target() instanceof RelationshipTarget.Resolved target)
            reverse.computeIfAbsent(target.target(),key->new HashSet<>()).add(relation.source());
        var owners=new HashSet<EntityIdentity>();var pending=new ArrayDeque<EntityIdentity>();
        for(var owner:source.declarations().keySet())if(source.annotations(owner).stream().anyMatch(a->a.name().filter(roots::contains).isPresent())) {
            owners.add(owner);if(annotationTypes.contains(owner))pending.add(owner);
        }
        while(!pending.isEmpty())for(var owner:reverse.getOrDefault(pending.removeFirst(),Set.of()))if(owners.add(owner)&&annotationTypes.contains(owner))pending.addLast(owner);
        return owners;
    }
    private static boolean nominal(JavaType type){return type.kind()==JavaType.Kind.DECLARED&&type.status()==SemanticStatus.RESOLVED&&type.target().isPresent();}
    private static void addSite(Map<String,Site> sites,Map<EntityIdentity,List<TypeUseRecord>> types,EntityIdentity owner,EntityIdentity element,String kind,String role){
        for(var type:types.getOrDefault(element,List.of()))if(type.role().value().equals(role)&&nominal(type.type())) {
            var site=new Site(owner,element,type.type(),kind,type.span());sites.put(IngestionEvidence.digest(List.of(owner,element,type)).value(),site);
        }
    }
}
