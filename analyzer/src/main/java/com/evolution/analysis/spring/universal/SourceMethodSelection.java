package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.ConditionEvidence;
import java.util.*;
import java.util.regex.Pattern;

/** Passive, source-evidenced counterpart of Framework 6.2.0's most-specific-method
 * filter. Generic/bridge or incomplete declarations cannot prove site suppression. */
public final class SourceMethodSelection {
    public enum Status { INCLUDED, SUPPRESSED, UNKNOWN }
    public enum Problem { NONE, INCOMPLETE_HIERARCHY, METHOD_EVIDENCE_INCOMPLETE, COMPARISON_LIMIT }
    public record Decision(EntityIdentity component,EntityIdentity method,Status status,
                           Problem problem,Optional<EntityIdentity> moreSpecific,List<ConditionEvidence> evidence) {
        public Decision {
            Objects.requireNonNull(component);Objects.requireNonNull(method);Objects.requireNonNull(status);Objects.requireNonNull(problem);Objects.requireNonNull(moreSpecific);
            evidence=List.copyOf(evidence);
            if(evidence.isEmpty()||(status==Status.SUPPRESSED)!=moreSpecific.isPresent()||(status==Status.UNKNOWN)!=(problem!=Problem.NONE))
                throw new IllegalArgumentException("Method selection needs consistent status and evidence");
        }
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    private record Shape(String name,List<Object> parameters,Object returns,Set<String> modifiers) {}
    private final SpringSourceEvidence source;
    private final Map<EntityIdentity,Optional<Shape>> shapes=new HashMap<>();
    private final Map<EntityIdentity,List<EntityIdentity>> methods=new HashMap<>();
    private final int maxComparisons;private int comparisons;
    public SourceMethodSelection(SpringSourceEvidence source) {this(source,100_000);}
    public SourceMethodSelection(SpringSourceEvidence source,int maxComparisons) {
        this.source=Objects.requireNonNull(source);
        if(maxComparisons<1)throw new IllegalArgumentException("Positive method comparison limit required");
        this.maxComparisons=maxComparisons;
        for(var d:source.declarations().values())if(d.entity().origin()==EntityOrigin.PROJECT&&d.entity().kind()==EntityKind.METHOD)
            source.owner(d.entity().identity()).ifPresent(owner -> methods.computeIfAbsent(owner,k -> new ArrayList<>()).add(d.entity().identity()));
    }
    public Decision select(EntityIdentity component,EntityIdentity method,SpringSourceEvidence.SourceHierarchy hierarchy) {
        var evidence=new ArrayList<ConditionEvidence>();evidence.add(source.evidence(method));evidence.add(source.evidence(component));
        hierarchy.types().forEach(type -> evidence.add(source.evidence(type)));
        int declaring=source.owner(method).map(hierarchy.types()::indexOf).orElse(-1);
        var shape=shape(method);
        if(!hierarchy.complete())return decision(component,method,Status.UNKNOWN,Problem.INCOMPLETE_HIERARCHY,null,evidence);
        if(declaring<0||shape.isEmpty())return decision(component,method,Status.UNKNOWN,Problem.METHOD_EVIDENCE_INCOMPLETE,null,evidence);
        var base=shape.orElseThrow();
        if(declaring==0||base.modifiers().contains("private")||base.modifiers().contains("final"))
            return decision(component,method,Status.INCLUDED,null,evidence);
        boolean publicMethod=base.modifiers().contains("public");
        if(!publicMethod&&!base.modifiers().contains("protected")&&!packageName(component).equals(packageName(hierarchy.types().get(declaring))))
            return decision(component,method,Status.INCLUDED,null,evidence);
        boolean unknown=false;
        for(var type:hierarchy.types().subList(0,declaring))for(var candidate:methods.getOrDefault(type,List.of())) {
            if(comparisons>=maxComparisons)return decision(component,method,Status.UNKNOWN,Problem.COMPARISON_LIMIT,null,evidence);
            comparisons++;
            var member=source.frontend().memberDeclarations().stream().filter(m -> m.member().equals(candidate)).findFirst();
            if(member.isPresent()&&!member.orElseThrow().name().equals(base.name()))continue;
            var other=shape(candidate);
            // Missing shapes can hide an override even without an injection annotation.
            if(other.isEmpty()){unknown=true;evidence.add(source.evidence(candidate));continue;}
            var child=other.orElseThrow();
            if(!child.name().equals(base.name())||!child.parameters().equals(base.parameters()))continue;
            evidence.add(source.evidence(candidate));
            // Covariant/visibility bridges and malformed static/access reductions need
            // stronger evidence. Never guess their reflective method inventory.
            if(child.modifiers().contains("static")||child.modifiers().contains("private")
                    ||publicMethod&&!child.modifiers().contains("public")
                    ||base.modifiers().contains("protected")&&!child.modifiers().contains("protected")&&!child.modifiers().contains("public")
                    ||!child.returns().equals(base.returns())||unknown)
                return decision(component,method,Status.UNKNOWN,null,evidence);
            return decision(component,method,Status.SUPPRESSED,candidate,evidence);
        }
        return decision(component,method,unknown?Status.UNKNOWN:Status.INCLUDED,null,evidence);
    }
    private Decision decision(EntityIdentity component,EntityIdentity method,Status status,EntityIdentity moreSpecific,List<ConditionEvidence> evidence) {
        return decision(component,method,status,status==Status.UNKNOWN?Problem.METHOD_EVIDENCE_INCOMPLETE:Problem.NONE,moreSpecific,evidence);
    }
    private Decision decision(EntityIdentity component,EntityIdentity method,Status status,Problem problem,EntityIdentity moreSpecific,List<ConditionEvidence> evidence) {
        return new Decision(component,method,status,problem,Optional.ofNullable(moreSpecific),evidence.stream().distinct().toList());
    }
    private String packageName(EntityIdentity type) {
        return JavaTypeName.fromCanonical(source.declarations().get(type).entity().canonicalName()).orElseThrow().packageName();
    }
    private Optional<Shape> shape(EntityIdentity method){return shapes.computeIfAbsent(method,this::readShape);}
    private Optional<Shape> readShape(EntityIdentity method) {
        var d=source.declarations().get(method);
        var member=source.frontend().memberDeclarations().stream().filter(m -> m.member().equals(method)).findFirst();
        if(d==null||d.status()!=SemanticStatus.RESOLVED||d.entity().declaration().isEmpty()||member.isEmpty()||member.orElseThrow().genericMethod())return Optional.empty();
        var parameters=source.declarations().values().stream().filter(p -> p.entity().kind()==EntityKind.PARAMETER
                &&source.owner(p.entity().identity()).filter(method::equals).isPresent()).sorted(Comparator.comparing(p -> p.entity().declaration().orElseThrow())).toList();
        String header=d.spelling();
        for(var a:source.annotations(method))header=header.replace(a.use().spelling()," ");
        for(var p:parameters)for(var a:source.annotations(p.entity().identity()))header=header.replace(a.use().spelling()," ");
        header=header.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*"," ");
        if(header.contains("\\u"))return Optional.empty();
        // Raw spelling validates modifiers/header only; signature equality uses resolved
        // parameter targets below, never textual type-name equality.
        String id="[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*";
        var matcher=Pattern.compile("^\\s*((?:(?:public|protected|private|static|final|abstract|synchronized|native|strictfp)\\s+)*)"
                +id+"(?:\\."+id+")*(?:\\s*\\[\\s*\\])*\\s+("+id+")\\s*\\(([^()]*)\\)").matcher(header);
        if(!matcher.find())return Optional.empty();
        String name=matcher.group(2).codePoints().filter(c -> !Character.isIdentifierIgnorable(c)).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();
        if(!name.equals(member.orElseThrow().name()))return Optional.empty();
        String rawParameters=matcher.group(3).strip();
        if(rawParameters.contains("<")||rawParameters.contains(">")||rawParameters.contains("@")
                ||(rawParameters.isEmpty()?0:rawParameters.split(",",-1).length)!=parameters.size())return Optional.empty();
        var signature=new ArrayList<Object>();
        for(var p:parameters) {
            var types=source.frontend().types().stream().filter(t -> t.owner().equals(Optional.of(p.entity().identity()))&&t.role().value().equals("java.parameter-type")).toList();
            if(types.size()!=1)return Optional.empty();
            var key=typeKey(types.getFirst().type());if(key.isEmpty())return Optional.empty();signature.add(key.orElseThrow());
        }
        var returns=source.frontend().types().stream().filter(t -> t.owner().equals(Optional.of(method))&&t.role().value().equals("java.returns")).toList();
        if(returns.size()!=1)return Optional.empty();
        var returnKey=typeKey(returns.getFirst().type());if(returnKey.isEmpty())return Optional.empty();
        var modifiers=matcher.group(1).isBlank()?Set.<String>of():Set.copyOf(Arrays.asList(matcher.group(1).strip().split("\\s+")));
        if(modifiers.contains("static")!=member.orElseThrow().staticMember()||modifiers.contains("abstract")!=member.orElseThrow().abstractMember())return Optional.empty();
        return Optional.of(new Shape(name,List.copyOf(signature),returnKey.orElseThrow(),modifiers));
    }
    private Optional<Object> typeKey(JavaType type) {
        if(type.status()!=SemanticStatus.RESOLVED)return Optional.empty();
        return switch(type.kind()) {
            case DECLARED -> type.components().isEmpty()?Optional.of(type.target().orElseThrow()):Optional.empty();
            case VOID,PRIMITIVE -> Optional.of(List.of(type.kind(),type.spelling()));
            case ARRAY -> typeKey(type.components().getFirst()).map(k -> List.of(JavaType.Kind.ARRAY,k));
            default -> Optional.empty();
        };
    }
}
