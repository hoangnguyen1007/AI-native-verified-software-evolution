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
import java.util.regex.Pattern;

/** Indexed exact M2 evidence. Package spelling alone never establishes framework origin. */
public final class SpringSourceEvidence {
    public record Annotation(AnnotationUseRecord use,Optional<String> name,ConditionEvidence evidence) {}
    /** Child-to-parent source declarations, including the verified prefix on failure.
     * Interfaces and binary parents are not evidence of a closed instance-member inventory. */
    public enum HierarchyProblem { NONE, SOURCE_DECLARATION_MISSING, SOURCE_TYPES_INCOMPLETE,
        SOURCE_SHAPE_UNSUPPORTED, PARENT_EVIDENCE_MISSING, PARENT_EVIDENCE_CONFLICT, CYCLE, LIMIT }
    public record SourceHierarchy(List<EntityIdentity> types,HierarchyProblem problem) {
        public SourceHierarchy {types=List.copyOf(types);Objects.requireNonNull(problem);}
        public boolean complete(){return problem==HierarchyProblem.NONE;}
        public boolean limited(){return problem==HierarchyProblem.LIMIT;}
    }
    private static final Pattern ORDINARY_CLASS=Pattern.compile("^\\s*(?:(?:public|protected|private|static|abstract|final|strictfp)\\s+)*"
            +"class\\s+[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
            +"(?:\\s+extends\\s+([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$]*))?\\s*\\{");
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
    /** Close only ordinary, non-generic source superclass chains from exact declaration,
     * written-type and relationship evidence. Never infer closure from a truncated ancestry set. */
    public SourceHierarchy sourceHierarchy(EntityIdentity type,int limit) {
        if(limit<1)throw new IllegalArgumentException("Positive hierarchy limit required");
        var chain=new ArrayList<EntityIdentity>();var seen=new HashSet<EntityIdentity>();var problem=HierarchyProblem.NONE;
        EntityIdentity current=type;
        while(true) {
            if(chain.size()>=limit)return new SourceHierarchy(chain,HierarchyProblem.LIMIT);
            if(!seen.add(current))return new SourceHierarchy(chain,HierarchyProblem.CYCLE);
            var declaration=declarations.get(current);
            if(declaration==null||declaration.entity().origin()!=EntityOrigin.PROJECT
                    ||declaration.entity().kind()!=EntityKind.TYPE||declaration.entity().declaration().isEmpty())
                return new SourceHierarchy(chain,HierarchyProblem.SOURCE_DECLARATION_MISSING);
            chain.add(current);
            var id=current;
            boolean ordinary=frontend.typeDeclarations().stream().anyMatch(t -> t.type().equals(id)
                    &&t.kind()==TypeDeclarationRecord.Kind.CLASS&&t.independent());
            String header=declaration.spelling();
            for(var annotation:annotations(current))header=header.replace(annotation.use().spelling()," ");
            var matcher=ORDINARY_CLASS.matcher(header.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*"," "));
            boolean syntax=!header.contains("\\u")&&matcher.find();
            if(problem==HierarchyProblem.NONE) {
                if(declaration.status()!=SemanticStatus.RESOLVED)problem=HierarchyProblem.SOURCE_TYPES_INCOMPLETE;
                else if(!ordinary||!syntax)problem=HierarchyProblem.SOURCE_SHAPE_UNSUPPORTED;
            }
            var span=declaration.entity().declaration().orElseThrow();
            boolean diagnostics=frontend.diagnostics().stream().anyMatch(d -> d.span().filter(s -> s.document().equals(span.document())
                    &&before(s.startLine(),s.startColumn(),span.endLine(),span.endColumn())
                    &&before(span.startLine(),span.startColumn(),s.endLine(),s.endColumn())).isPresent());
            if(problem==HierarchyProblem.NONE&&diagnostics)problem=HierarchyProblem.SOURCE_TYPES_INCOMPLETE;
            var written=frontend.types().stream().filter(t -> t.owner().equals(Optional.of(id))&&t.role().value().equals("java.extends")).toList();
            var edges=relations.stream().filter(r -> r.source().equals(id)&&r.kind().value().equals("java.extends"))
                    .flatMap(r -> r.target() instanceof RelationshipTarget.Resolved t?java.util.stream.Stream.of(t.target()):java.util.stream.Stream.empty()).distinct().toList();
            if(written.isEmpty()&&edges.isEmpty())return new SourceHierarchy(chain,
                    problem!=HierarchyProblem.NONE?problem:matcher.group(1)==null?HierarchyProblem.NONE:HierarchyProblem.PARENT_EVIDENCE_MISSING);
            if(syntax&&matcher.group(1)==null)return new SourceHierarchy(chain,HierarchyProblem.PARENT_EVIDENCE_CONFLICT);
            if(written.size()!=1||edges.size()!=1)return new SourceHierarchy(chain,HierarchyProblem.PARENT_EVIDENCE_MISSING);
            var parent=written.getFirst().type();
            if(parent.target().isEmpty()||!parent.target().orElseThrow().equals(edges.getFirst()))return new SourceHierarchy(chain,HierarchyProblem.PARENT_EVIDENCE_CONFLICT);
            if(problem==HierarchyProblem.NONE) {
                if(parent.status()!=SemanticStatus.RESOLVED)problem=HierarchyProblem.SOURCE_TYPES_INCOMPLETE;
                else if(parent.kind()!=JavaType.Kind.DECLARED||!parent.components().isEmpty())problem=HierarchyProblem.SOURCE_SHAPE_UNSUPPORTED;
            }
            current=edges.getFirst();
            var terminal=declarations.get(current);
            if(terminal!=null&&terminal.entity().origin()==EntityOrigin.JDK&&"java.lang.Object".equals(typeName(current)))
                return new SourceHierarchy(chain,problem);
        }
    }
    private static boolean before(int line,int column,int otherLine,int otherColumn) {
        return line<otherLine||line==otherLine&&column<otherColumn;
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
