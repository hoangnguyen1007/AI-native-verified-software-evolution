package com.evolution.analysis.javaparser;

import com.evolution.analysis.frontend.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import java.util.*;

/** Recovery evidence stays separate from Lombok's verified member synthesis. */
final class LombokCandidates {
    private static final Set<String> GENERATORS=Set.of("RequiredArgsConstructor","AllArgsConstructor","Data","Value","Builder");
    static List<LombokConstructorCandidate> extract(Extraction context,List<CompilationUnit> units,List<TypeUseRecord> uses) {
        return extract(context,units,uses,Set.of());
    }
    static List<LombokConstructorCandidate> extract(Extraction context,List<CompilationUnit> units,List<TypeUseRecord> uses,Set<String> allSourceTypes) {
        var sourceTypes=new HashSet<String>(allSourceTypes);
        for(var unit:units)for(TypeDeclaration<?> type:unit.findAll(TypeDeclaration.class))type.getFullyQualifiedName().ifPresent(sourceTypes::add);
        var fieldTypes=new HashMap<com.evolution.analysis.contract.identity.EntityIdentity,JavaType>();
        for(var use:uses)if(use.role().value().equals("java.field-type"))use.owner().ifPresent(id->fieldTypes.put(id,use.type()));
        var result=new ArrayList<LombokConstructorCandidate>();
        for(var unit:units)for(var type:unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if(type.isInterface()||context.nativeOwner(type)||context.lombok.hasGeneratedConstructor(type))continue;
            var annotations=new TreeMap<String,AnnotationExpr>();
            for(var annotation:type.getAnnotations()) {
                String name=annotation.getName().getIdentifier();
                if(GENERATORS.contains(name)&&isLombok(annotation,"lombok."+name,sourceTypes))annotations.put(name,annotation);
            }
            boolean explicit=type.getAnnotations().stream().anyMatch(a->Set.of("RequiredArgsConstructor","AllArgsConstructor","NoArgsConstructor").contains(a.getName().getIdentifier())
                    &&isLombok(a,"lombok."+a.getName().getIdentifier(),sourceTypes));
            boolean value=annotations.containsKey("Value");
            for(var entry:annotations.entrySet()) {
                String generator=entry.getKey();
                if(Set.of("Data","Value","Builder").contains(generator)&&(!type.getConstructors().isEmpty()||explicit))continue;
                if(generator.equals("Data")&&(value||annotations.containsKey("Builder"))||generator.equals("Value")&&annotations.containsKey("Builder"))continue;
                int order=0;
                for(var field:type.getFields())for(var variable:field.getVariables()) {
                    if(field.isStatic()||variable.getNameAsString().startsWith("$"))continue;
                    boolean required=generator.equals("RequiredArgsConstructor")||generator.equals("Data");
                    boolean nonNull=field.getAnnotations().stream().anyMatch(a->isLombok(a,"lombok.NonNull",sourceTypes));
                    boolean nonFinal=field.getAnnotations().stream().anyMatch(a->isLombok(a,"lombok.experimental.NonFinal",sourceTypes));
                    if(required?variable.getInitializer().isPresent()||!(field.isFinal()||nonNull)
                            :variable.getInitializer().isPresent()&&(field.isFinal()||value&&!nonFinal))continue;
                    int fieldOrder=order++;
                    try {
                        var entity=context.entity(variable);var written=fieldTypes.get(entity.identity());
                        if(written==null){context.synthesisGap(variable,"lombok.candidate-field-type-missing");continue;}
                        result.add(new LombokConstructorCandidate(context.entity(type).identity(),entity.identity(),fieldOrder,generator,written,
                                List.of(context.source(entry.getValue()).span(entry.getValue()),context.source(variable).span(variable)),LombokConstructorCandidate.Status.CANDIDATE));
                    }catch(RuntimeException failure){context.synthesisGap(variable,"lombok.candidate-source-unavailable");}
                }
                context.synthesisGap(entry.getValue(),"lombok.constructor-candidate");
            }
        }
        return result;
    }
    static boolean isLombok(AnnotationExpr annotation,String name,Set<String> sourceTypes) {
        if(sourceTypes.contains(name))return false;
        String spelling=annotation.getNameAsString();if(spelling.equals(name))return true;
        if(spelling.contains(".")||!name.endsWith("."+spelling))return false;
        var unit=annotation.findCompilationUnit().orElseThrow();
        String local=unit.getPackageDeclaration().map(p->p.getNameAsString()+".").orElse("")+spelling;
        if(sourceTypes.contains(local)||unit.findAll(TypeDeclaration.class).stream().anyMatch(t->t.getNameAsString().equals(spelling)))return false;
        var imports=unit.getImports().stream().filter(i->!i.isStatic()&&!i.isAsterisk()&&i.getName().getIdentifier().equals(spelling)).toList();
        if(!imports.isEmpty())return imports.size()==1&&imports.getFirst().getNameAsString().equals(name);
        String pkg=name.substring(0,name.lastIndexOf('.'));
        var stars=unit.getImports().stream().filter(i->!i.isStatic()&&i.isAsterisk()).toList();
        return stars.size()==1&&stars.getFirst().getNameAsString().equals(pkg);
    }
}
