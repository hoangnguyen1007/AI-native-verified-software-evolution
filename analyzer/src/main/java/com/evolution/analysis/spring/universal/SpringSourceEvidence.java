package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.condition.ConditionEvidence;
import java.util.*;

/** Indexed exact M2 evidence. Package spelling alone never establishes framework origin. */
public final class SpringSourceEvidence {
    public record Annotation(AnnotationUseRecord use,Optional<String> name,ConditionEvidence evidence) {}
    private final FrontendResult frontend;private final AnalysisManifest manifest;
    private final SpringFrameworkEvidence framework;
    private final Map<EntityIdentity,DeclarationRecord> declarations=new TreeMap<>();
    private final Map<EntityIdentity,List<Annotation>> annotations=new TreeMap<>();
    private final Map<EntityIdentity,List<EntityIdentity>> parents=new TreeMap<>();
    private final Map<EntityIdentity,EntityIdentity> owners=new TreeMap<>();
    private final Map<EntityIdentity,SpringFrameworkEvidence.Artifact> artifacts=new TreeMap<>();
    private final List<SemanticRelationship> relations;
    private final ContentDigest identity;
    public SpringSourceEvidence(AnalysisManifest manifest,FrontendResult frontend,SpringFrameworkEvidence framework) {
        this.manifest=Objects.requireNonNull(manifest);this.frontend=Objects.requireNonNull(frontend);this.framework=Objects.requireNonNull(framework);
        var relationships=new ArrayList<SemanticRelationship>();
        frontend.occurrences().stream().filter(o->o.status()==SemanticStatus.RESOLVED).forEach(o->relationships.add(o.relationship()));
        frontend.derivedRelationships().stream().filter(d->d.status()==SemanticStatus.RESOLVED).forEach(d->relationships.add(d.relationship()));
        relations=relationships.stream().distinct().sorted().toList();
        if(!manifest.identity().equals(frontend.analysis()))throw new IllegalArgumentException("Foreign frontend evidence");
        for(var artifact:framework.artifacts())if(manifest.classpath().stream().noneMatch(c->c.logicalName().equals(artifact.classpathLogicalName())&&c.contentDigest().equals(artifact.contentDigest())))
            throw new IllegalArgumentException("Artifact evidence is outside the exact classpath");
        identity=IngestionEvidence.digest(List.of("spring.source-index:m4u.2-v1",manifest.identity(),IngestionEvidence.digest(frontend),framework.identity()));
        for(var declaration:frontend.declarations()) {
            declarations.put(declaration.entity().identity(),declaration);
            if(declaration.entity().origin()==EntityOrigin.DEPENDENCY)framework.artifacts().stream()
                    .filter(a->a.entityScope().equals(declaration.entity().stableScope())).findFirst().ifPresent(a->artifacts.put(declaration.entity().identity(),a));
        }
        for(var relation:relationships())if(relation.target() instanceof RelationshipTarget.Resolved target) {
            if(Set.of("java.extends","java.implements").contains(relation.kind().value()))parents.computeIfAbsent(relation.source(),k->new ArrayList<>()).add(target.target());
            if(relation.kind().value().equals("java.declares"))owners.put(target.target(),relation.source());
        }
        var annotationTargets=new HashMap<Object,List<EntityIdentity>>();
        frontend.occurrences().stream().filter(o->o.status()==SemanticStatus.RESOLVED&&o.relationship().kind().value().equals("java.annotated-with")&&o.relationship().target() instanceof RelationshipTarget.Resolved)
                .forEach(o->annotationTargets.computeIfAbsent(List.of(o.relationship().source(),o.span()),k->new ArrayList<>()).add(((RelationshipTarget.Resolved)o.relationship().target()).target()));
        for(var use:frontend.annotations()) {
            var resolved=annotationTargets.getOrDefault(List.of(use.owner(),use.span()),List.of()).stream().distinct().toList();
            Optional<String> name=resolved.size()==1&&artifacts.containsKey(resolved.getFirst())?Optional.ofNullable(typeName(resolved.getFirst())):Optional.empty();
            if(name.isPresent()) {
                var artifact=artifacts.get(resolved.getFirst());String n=name.orElseThrow();
                boolean valid=n.startsWith("org.springframework.boot.")?artifact.groupId().equals("org.springframework.boot"):
                        n.startsWith("org.springframework.data.")?artifact.groupId().equals("org.springframework.data"):
                        n.startsWith("org.springframework.")?artifact.groupId().equals("org.springframework"):
                        n.startsWith("javax.inject.")?artifact.groupId().equals("javax.inject"):
                        n.startsWith("jakarta.inject.")?artifact.groupId().equals("jakarta.inject"):
                        n.startsWith("javax.annotation.")?Set.of("javax.annotation","jakarta.annotation").contains(artifact.groupId()):
                        n.startsWith("jakarta.annotation.")?artifact.groupId().equals("jakarta.annotation"):false;
                if(!valid)name=Optional.empty();
            }
            var document=manifest.snapshot().documents().stream().filter(d->d.identity().equals(use.span().document())).findFirst().orElseThrow();
            ConditionEvidence evidence=new ConditionEvidence.Source(document.identity(),document.contentDigest(),Optional.of(use.span()),0);
            annotations.computeIfAbsent(use.owner(),k->new ArrayList<>()).add(new Annotation(use,name,evidence));
        }
    }
    public ContentDigest identity(){return identity;}
    public AnalysisManifest manifest(){return manifest;}
    public FrontendResult frontend(){return frontend;}
    public SpringFrameworkEvidence framework(){return framework;}
    public Map<EntityIdentity,DeclarationRecord> declarations(){return Collections.unmodifiableMap(declarations);}
    public List<Annotation> annotations(EntityIdentity owner){return List.copyOf(annotations.getOrDefault(owner,List.of()));}
    public Optional<SpringFrameworkEvidence.Artifact> artifact(EntityIdentity type){return Optional.ofNullable(artifacts.get(type));}
    public Optional<EntityIdentity> owner(EntityIdentity member){return Optional.ofNullable(owners.get(member));}
    public String typeName(EntityIdentity type){var d=declarations.get(type);return d==null?null:JavaTypeName.fromCanonical(d.entity().canonicalName()).map(JavaTypeName::qualifiedName).orElse(null);}
    public List<SemanticRelationship> relationships(){return relations;}
    public Set<EntityIdentity> ancestry(EntityIdentity type,int limit) {
        var result=new TreeSet<EntityIdentity>();var pending=new ArrayDeque<EntityIdentity>();pending.add(type);
        while(!pending.isEmpty()&&result.size()<limit){var next=pending.removeFirst();if(result.add(next))pending.addAll(parents.getOrDefault(next,List.of()));}
        return Collections.unmodifiableSet(result);
    }
    public ConditionEvidence evidence(EntityIdentity entity) {
        var declaration=declarations.get(entity);
        if(declaration!=null&&declaration.entity().declaration().isPresent()) {
            var span=declaration.entity().declaration().orElseThrow();
            var doc=manifest.snapshot().documents().stream().filter(d->d.identity().equals(span.document())).findFirst();
            if(doc.isPresent())return new ConditionEvidence.Source(span.document(),doc.orElseThrow().contentDigest(),Optional.of(span),0);
        }
        return UniversalSpringEvidence.derived(identity,"entity:"+entity.value());
    }
}
