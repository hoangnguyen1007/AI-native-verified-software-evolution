package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.*;

/** Evidence-bound structural synthesis. Never runs annotation processors or changes source ASTs. */
final class LombokExtraction {
    private static final Set<String> GENERATORS=Set.of(
            "RequiredArgsConstructor","AllArgsConstructor","NoArgsConstructor",
            "Data","Value","Builder","Getter","Setter","With","ToString","EqualsAndHashCode","UtilityClass");
    private static final Set<String> ACCESSORS=Set.of("Getter","Setter","With");
    private static final Set<String> NON_CONSTRUCTOR_GENERATORS=Set.of("Getter","Setter","With","ToString","EqualsAndHashCode");
    private static final Map<String,String> LOG_ANNOTATIONS=Map.of(
            "Slf4j","lombok.extern.slf4j.Slf4j",
            "XSlf4j","lombok.extern.slf4j.XSlf4j",
            "Log4j2","lombok.extern.log4j.Log4j2",
            "Log4j","lombok.extern.log4j.Log4j",
            "Log","lombok.extern.java.Log",
            "CommonsLog","lombok.extern.apachecommons.CommonsLog",
            "JBossLog","lombok.extern.jbosslog.JBossLog",
            "Flogger","lombok.extern.flogger.Flogger",
            "CustomLog","lombok.CustomLog");
    private static final Map<String,JavaSymbolName> LOGGER_TYPES=Map.of(
            "Slf4j",JavaSymbolName.topLevelType("org.slf4j","Logger"),
            "XSlf4j",JavaSymbolName.topLevelType("org.slf4j.ext","XLogger"),
            "Log4j2",JavaSymbolName.topLevelType("org.apache.logging.log4j","Logger"),
            "Log4j",JavaSymbolName.topLevelType("org.apache.log4j","Logger"),
            "Log",JavaSymbolName.topLevelType("java.util.logging","Logger"),
            "CommonsLog",JavaSymbolName.topLevelType("org.apache.commons.logging","Log"),
            "JBossLog",JavaSymbolName.topLevelType("org.jboss.logging","Logger"),
            "Flogger",JavaSymbolName.topLevelType("com.google.common.flogger","FluentLogger"),
            "CustomLog",JavaSymbolName.topLevelType("lombok","CustomLogger"));
    private static final Set<String> LOGGER_METHODS=Set.of(
            "trace","debug","info","warn","error","fatal","log","fine","finer","finest","severe","warning","config",
            "isTraceEnabled","isDebugEnabled","isInfoEnabled","isWarnEnabled","isErrorEnabled","isFatalEnabled",
            "atTrace","atDebug","atInfo","atWarn","atError","atFine","atFiner","atFinest","atSevere","atWarning","atConfig",
            "entering","exiting","throwing","printf","catching");
    private final Extraction context;
    private final List<Generated> generated=new ArrayList<>();
    private final Map<Entity,ClassOrInterfaceDeclaration> generatedOwners=new HashMap<>();
    private final Map<Entity,String> generatedMethodNames=new HashMap<>();
    private final Map<Entity,String> generatedAccess=new HashMap<>();
    private record BuilderShape(ClassOrInterfaceDeclaration owner,Entity factory,Entity build,Map<String,Entity> setters,List<VariableDeclarator> fields) {}
    private record BuilderCall(Entity target,BuilderShape builder,boolean built) {}
    private record LoggerShape(TypeDeclaration<?> owner,Entity field,JavaSymbolName loggerType) {}
    private record AccessorConfig(boolean fluent,boolean chain,boolean supported) {}
    private final Map<JavaSymbolName,BuilderShape> builders=new HashMap<>();
    private final Map<JavaSymbolName,LoggerShape> loggers=new HashMap<>();
    private final Map<JavaSymbolName,ClassOrInterfaceDeclaration> utilityClasses=new HashMap<>();
    private final Set<ClassOrInterfaceDeclaration> unsupportedConstructorOwners=Collections.newSetFromMap(new IdentityHashMap<>());
    private record Generated(Entity callable,JavaSymbolName name,List<VariableDeclarator> fields,List<Entity> parameters,String rule,boolean setter,boolean returnsOwner){}
    LombokExtraction(Extraction context){this.context=context;}

    void releaseBody(CompilationUnit unit){
        generated.removeIf(g->g.fields().stream().anyMatch(f->f.findCompilationUnit().filter(c->c==unit).isPresent()));
        generatedOwners.entrySet().removeIf(e->e.getValue().findCompilationUnit().filter(c->c==unit).isPresent());
        unsupportedConstructorOwners.removeIf(t->t.findCompilationUnit().filter(c->c==unit).isPresent());
        builders.values().removeIf(b->b.owner().findCompilationUnit().filter(c->c==unit).isPresent());
    }

    private static String generatorFqn(String simple) {
        return simple.equals("UtilityClass") ? "lombok.experimental.UtilityClass" : "lombok." + simple;
    }

    void loggerDeclarations(TypeDeclaration<?> type) {
        if(type instanceof ClassOrInterfaceDeclaration c&&c.isInterface()||type instanceof AnnotationDeclaration)return;
        for(var annotation:type.getAnnotations()) {
            String simple=annotation.getName().getIdentifier();
            String fqn=LOG_ANNOTATIONS.get(simple);
            if(fqn==null)continue;
            if(!verifiedOrRecovered(annotation,fqn))continue;
            boolean hasWrittenLog=type.getFields().stream().flatMap(f->f.getVariables().stream()).anyMatch(v->v.getNameAsString().equals("log"));
            if(hasWrittenLog)return;
            var ownerSymbol=context.name(type);
            var logSymbol=JavaSymbolName.field(ownerSymbol,"log");
            var logField=context.implicit(logSymbol,EntityKind.FIELD,type,"lombok.logger-field");
            loggers.put(ownerSymbol,new LoggerShape(type,logField,LOGGER_TYPES.get(simple)));
            return;
        }
    }

    void unsupportedGenerators(CompilationUnit unit) {
        unsupportedGenerators(unit,false);
    }
    void unsupportedGenerators(CompilationUnit unit,boolean localsOnly) {
        var otherGenerators=Set.of("Getter","Setter","With","WithBy","ToString","EqualsAndHashCode","SuperBuilder","UtilityClass","FieldDefaults");
        for(var annotation:unit.findAll(AnnotationExpr.class)) {
            String simple=annotation.getName().getIdentifier();var parent=annotation.getParentNode().orElse(null);
            if(GENERATORS.contains(simple)&&parent instanceof ClassOrInterfaceDeclaration type&&!type.isInterface())continue;
            if(ACCESSORS.contains(simple)&&parent instanceof FieldDeclaration)continue;
            if(!GENERATORS.contains(simple)&&!otherGenerators.contains(simple))continue;
            var nativeOwner=annotation.findAncestor(TypeDeclaration.class);
            if(localsOnly!=(nativeOwner.isPresent()&&context.hasLocalOwner(nativeOwner.orElseThrow())))continue;
            String expectedFqn=generatorFqn(simple);
            try {
                if(nativeOwner.isPresent()&&context.nativeOwner(nativeOwner.orElseThrow()))continue;
                var entity=context.typeEntity(annotation.resolve());
                if(entity.origin()==EntityOrigin.PROJECT)continue;
                String name=JavaTypeName.fromCanonical(entity.canonicalName()).map(JavaTypeName::qualifiedName).orElse("");
                if(!name.startsWith("lombok."))continue;
                context.synthesisGap(annotation,context.isLombok(entity,name)||context.isSourceRecoveryLombok(annotation,expectedFqn)
                        ?"lombok.generator-unsupported":"lombok.artifact-unverified");
                if(parent instanceof ClassOrInterfaceDeclaration type&&Set.of("SuperBuilder","UtilityClass").contains(simple))unsupportedConstructorOwners.add(type);
            }catch(RuntimeException failure){
                if(context.isSourceRecoveryLombok(annotation,expectedFqn)) {
                    context.synthesisGap(annotation,"lombok.generator-unsupported");
                    if(parent instanceof ClassOrInterfaceDeclaration type&&Set.of("SuperBuilder","UtilityClass").contains(simple))unsupportedConstructorOwners.add(type);
                } else {
                    context.synthesisGap(annotation,"lombok.annotation-unresolved");
                }
            }
        }
    }

    boolean isUtilityClass(ClassOrInterfaceDeclaration type) {
        try { return utilityClasses.containsKey(context.name(type)); }
        catch(RuntimeException unavailable) { return false; }
    }

    boolean declarations(ClassOrInterfaceDeclaration type) {
        if(unsupportedConstructorOwners.contains(type))return true;
        if(type.isInterface())return false;
        Map<String,AnnotationExpr> annotations=new TreeMap<>();
        Set<String> artifactVerified=new HashSet<>();
        boolean potential=false,constructorPotential=false;
        for(var annotation:type.getAnnotations()) {
            String simple=annotation.getName().getIdentifier();if(!GENERATORS.contains(simple))continue;
            String fqn=generatorFqn(simple);
            boolean ownsConstructor=!NON_CONSTRUCTOR_GENERATORS.contains(simple);
            try {
                var entity=context.typeEntity(annotation.resolve());
                if(entity.origin()==EntityOrigin.PROJECT)continue;
                if(context.isLombok(entity,fqn)||simple.equals("UtilityClass")&&context.isLombok(entity,"lombok.UtilityClass")){
                    annotations.put(simple,annotation);artifactVerified.add(simple);potential=true;constructorPotential|=ownsConstructor;
                } else if(context.isSourceRecoveryLombok(annotation,fqn)||simple.equals("UtilityClass")&&context.isSourceRecoveryLombok(annotation,"lombok.UtilityClass")) {
                    annotations.put(simple,annotation);potential=true;constructorPotential|=ownsConstructor;
                } else if(annotation.getNameAsString().startsWith("lombok.")||entity.canonicalName().contains("lombok")){
                    potential=true;constructorPotential|=ownsConstructor;context.synthesisGap(type,"lombok.artifact-unverified");
                }
            }catch(RuntimeException failure){
                if(context.isSourceRecoveryLombok(annotation,fqn)||simple.equals("UtilityClass")&&context.isSourceRecoveryLombok(annotation,"lombok.UtilityClass")) {
                    annotations.put(simple,annotation);potential=true;constructorPotential|=ownsConstructor;
                } else {
                    potential=true;constructorPotential|=ownsConstructor;context.synthesisGap(type,"lombok.annotation-unresolved");
                }
            }
        }
        // Field-only accessors do not own or suppress Java's implicit constructor.
        for(var field:type.getFields())for(var annotation:field.getAnnotations()) {
            String simple=annotation.getName().getIdentifier();
            if(ACCESSORS.contains(simple))try {
                var entity=context.typeEntity(annotation.resolve());if(entity.origin()==EntityOrigin.PROJECT)continue;
                if(context.isLombok(entity,"lombok."+simple)||context.isSourceRecoveryLombok(annotation,"lombok."+simple))potential=true;
                else if(entity.canonicalName().contains("lombok")){potential=true;context.synthesisGap(annotation,"lombok.artifact-unverified");}
            }catch(RuntimeException failure){
                if(context.isSourceRecoveryLombok(annotation,"lombok."+simple))potential=true;
                else {potential=true;context.synthesisGap(annotation,"lombok.annotation-unresolved");}
            }
        }
        if(!potential)return false;
        if(context.hasLombokConfiguration(type)){context.synthesisGap(type,"lombok.configuration-required");return constructorPotential;}
        if(constructorPotential&&type.isNestedType()&&!type.isStatic()){context.synthesisGap(type,"lombok.inner-class-constructor");return true;}
        if(type.findAll(AnnotationExpr.class).stream().anyMatch(a->Set.of("Tolerate","FieldDefaults","Singular","Default").contains(a.getName().getIdentifier()))) {
            context.synthesisGap(type,"lombok.member-customization");return constructorPotential;
        }
        AccessorConfig typeAccessors=accessorConfig(type.getAnnotations());
        boolean hasAccessorsAnnotation=type.findAll(AnnotationExpr.class).stream().anyMatch(a->a.getName().getIdentifier().equals("Accessors"));
        if(hasAccessorsAnnotation) {
            context.synthesisGap(type,"lombok.member-customization");
            if(!typeAccessors.supported())return constructorPotential;
        }
        if(annotations.containsKey("UtilityClass")) {
            utilityClasses.put(context.name(type),type);
            if(artifactVerified.contains("UtilityClass")&&type.getConstructors().isEmpty()) {
                var ctorName=JavaSymbolName.constructor(context.name(type),List.of());
                if(!context.hasDeclaration(ctorName)) {
                    var callable=context.implicit(ctorName,EntityKind.CONSTRUCTOR,type,"lombok.utility-class-constructor");
                    generatedOwners.put(callable,type);
                    generatedAccess.put(callable,"PRIVATE");
                    generated.add(new Generated(callable,ctorName,List.of(),List.of(),"lombok.utility-class-constructor",true,false));
                }
            }
            return true;
        }
        var fields=type.getFields().stream().filter(f->!f.isStatic()).flatMap(f->f.getVariables().stream()).filter(f->!f.getNameAsString().startsWith("$")).toList();
        if(constructorPotential&&fields.stream().anyMatch(f->field(f).getAnnotations().stream().anyMatch(a->Set.of("NonNull","Nonnull","NotNull","NonNullDecl").contains(a.getName().getIdentifier())&&!verifiedOrRecovered(a,"lombok.NonNull")))) {
            context.synthesisGap(type,"lombok.nullness-annotation-unverified");return true;
        }
        boolean value=annotations.containsKey("Value");
        var required=fields.stream().filter(f->f.getInitializer().isEmpty()&&(field(f).isFinal()||nonNull(field(f)))).toList();
        var all=fields.stream().filter(f->f.getInitializer().isEmpty()||!(field(f).isFinal()||value&&!nonFinal(field(f)))).toList();
        boolean explicitAnnotation=annotations.keySet().stream().anyMatch(n->n.endsWith("ArgsConstructor"));
        for(var entry:annotations.entrySet())try {
            String name=entry.getKey();var annotation=entry.getValue();
            if(NON_CONSTRUCTOR_GENERATORS.contains(name))continue;
            if(!optionsSupported(annotation)){context.synthesisGap(annotation,"lombok.annotation-options");continue;}
            if(artifactVerified.contains(name)) {
                if(name.equals("RequiredArgsConstructor"))constructor(type,required,"lombok.required-args-constructor");
                if(name.equals("AllArgsConstructor"))constructor(type,all,"lombok.all-args-constructor");
                if(name.equals("NoArgsConstructor")) {
                    boolean force=annotation instanceof NormalAnnotationExpr n&&n.getPairs().stream().anyMatch(p->p.getNameAsString().equals("force")&&p.getValue().isBooleanLiteralExpr()&&p.getValue().asBooleanLiteralExpr().getValue());
                    if(!force&&fields.stream().anyMatch(f->field(f).isFinal()&&f.getInitializer().isEmpty()))context.synthesisGap(annotation,"lombok.invalid-no-args");
                    else constructor(type,List.of(),"lombok.no-args-constructor");
                }
                if(Set.of("Data","Value","Builder").contains(name)&&type.getConstructors().isEmpty()&&!explicitAnnotation) {
                    if(name.equals("Data")&&!value&&!annotations.containsKey("Builder"))constructor(type,required,"lombok.data-constructor");
                    if(name.equals("Value")&&!annotations.containsKey("Builder"))constructor(type,all,"lombok.value-constructor");
                    if(name.equals("Builder"))constructor(type,all,"lombok.builder-constructor");
                }
            }
            if(name.equals("Builder"))builder(type,all);
        }catch(RuntimeException failure){context.synthesisGap(type,"lombok.member-type-unresolved");}
        boolean hasDataOrValue=annotations.containsKey("Data")||value;
        if(hasDataOrValue||annotations.containsKey("ToString")||annotations.containsKey("EqualsAndHashCode")) {
            objectMembers(type,value,hasDataOrValue||annotations.containsKey("ToString"),hasDataOrValue||annotations.containsKey("EqualsAndHashCode"));
        }
        for(var f:fields)try {
            AccessorConfig fieldAccessors=fieldAccessorConfig(field(f),typeAccessors);
            if(!fieldAccessors.supported()){context.synthesisGap(f,"lombok.member-customization");continue;}
            boolean bool=f.getType().isPrimitiveType()&&f.getType().asPrimitiveType().getType()==com.github.javaparser.ast.type.PrimitiveType.Primitive.BOOLEAN;
            String name=f.getNameAsString();String capital=capitalize(name);
            String getter=fieldAccessors.fluent()?name:(bool&&name.matches("is[A-Z].*")?name:(bool?"is":"get")+capital);
            var alternatives=new HashSet<String>();alternatives.add(getter);
            if(!fieldAccessors.fluent()&&bool){String base=name.matches("is[A-Z].*")?name.substring(2):capital;alternatives.add("get"+base);alternatives.add("is"+capital);alternatives.add("get"+capital);}
            String getterAccess=accessorAccess(field(f),annotations.get("Getter"),"Getter",annotations.containsKey("Data")||value);
            if(!getterAccess.equals("NONE")&&!suppressed(type,alternatives,0)) {
                member(type,getter,List.of(),List.of(f),"lombok.getter",false,false);
                generatedAccess.put(context.existing(JavaSymbolName.method(context.name(type),getter,List.of())),getterAccess);
            }
            String setterAccess=accessorAccess(field(f),annotations.get("Setter"),"Setter",annotations.containsKey("Data")&&!value);
            if(!setterAccess.equals("NONE")&&!field(f).isFinal()&&!value) {
                String setter=fieldAccessors.fluent()?name:"set"+(bool&&name.matches("is[A-Z].*")?name.substring(2):capital);
                if(!suppressed(type,Set.of(setter),1)) {
                    member(type,setter,List.of(f),List.of(f),"lombok.setter",true,fieldAccessors.chain());
                    generatedAccess.put(context.existing(JavaSymbolName.method(context.name(type),setter,List.of(context.erasedParameter(f.getType())))),setterAccess);
                }
            }
            String witherAccess=accessorAccess(field(f),annotations.get("With"),"With",annotations.containsKey("With"));
            if(!witherAccess.equals("NONE")&&!(field(f).isFinal()&&f.getInitializer().isPresent())) {
                String wither="with"+(bool&&name.matches("is[A-Z].*")?name.substring(2):capital);
                if(!suppressed(type,Set.of(wither),1)) {
                    member(type,wither,List.of(f),List.of(f),"lombok.with",true,true);
                    generatedAccess.put(context.existing(JavaSymbolName.method(context.name(type),wither,List.of(context.erasedParameter(f.getType())))),witherAccess);
                }
            }
        }catch(RuntimeException failure){context.synthesisGap(f,"lombok.member-customization");}
        for(var f:type.getFields())if(f.isStatic()&&f.getAnnotations().stream().anyMatch(a->ACCESSORS.contains(a.getName().getIdentifier())&&verifiedOrRecovered(a,"lombok."+a.getName().getIdentifier())))
            context.synthesisGap(f,"lombok.static-accessor-required");
        return constructorPotential;
    }

    private AccessorConfig accessorConfig(NodeList<AnnotationExpr> annotations) {
        return mergeAccessorConfig(annotations,new AccessorConfig(false,false,true));
    }
    private AccessorConfig fieldAccessorConfig(FieldDeclaration field,AccessorConfig inherited) {
        return mergeAccessorConfig(field.getAnnotations(),inherited);
    }
    private AccessorConfig mergeAccessorConfig(NodeList<AnnotationExpr> annotations,AccessorConfig base) {
        var found=annotations.stream().filter(a->a.getName().getIdentifier().equals("Accessors")).findFirst();
        if(found.isEmpty())return base;
        var annotation=found.orElseThrow();
        if(!verifiedOrRecovered(annotation,"lombok.experimental.Accessors"))return new AccessorConfig(false,false,false);
        if(annotation.isMarkerAnnotationExpr())return base;
        if(!(annotation instanceof NormalAnnotationExpr normal))return new AccessorConfig(false,false,false);
        boolean fluent=base.fluent();
        Boolean chainExplicit=null;
        for(var pair:normal.getPairs()) {
            if(pair.getNameAsString().equals("fluent")&&pair.getValue().isBooleanLiteralExpr()) {
                fluent=pair.getValue().asBooleanLiteralExpr().getValue();
            } else if(pair.getNameAsString().equals("chain")&&pair.getValue().isBooleanLiteralExpr()) {
                chainExplicit=pair.getValue().asBooleanLiteralExpr().getValue();
            } else {
                return new AccessorConfig(false,false,false);
            }
        }
        boolean chain=chainExplicit!=null?chainExplicit:(fluent||base.chain());
        return new AccessorConfig(fluent,chain,true);
    }

    private String accessorAccess(FieldDeclaration field,AnnotationExpr typeAnnotation,String name,boolean implicit) {
        var annotation=field.getAnnotations().stream().filter(a->verifiedOrRecovered(a,"lombok."+name)).findFirst().orElse(typeAnnotation);
        if(annotation==null)return implicit?"PUBLIC":"NONE";
        if(annotation.isMarkerAnnotationExpr())return "PUBLIC";
        Expression level;
        if(annotation instanceof SingleMemberAnnotationExpr single)level=single.getMemberValue();
        else if(annotation instanceof NormalAnnotationExpr normal) {
            level=null;
            for(var pair:normal.getPairs()) {
                if(pair.getNameAsString().equals("value"))level=pair.getValue();
                else if(!pair.getNameAsString().equals("lazy")||!pair.getValue().isBooleanLiteralExpr()||pair.getValue().asBooleanLiteralExpr().getValue())throw new IllegalArgumentException();
            }
            if(level==null)return "PUBLIC";
        } else throw new IllegalArgumentException();
        try {
            var resolved=level instanceof FieldAccessExpr access?access.resolve():level instanceof NameExpr simple?simple.resolve():null;
            if(resolved!=null&&resolved.isField()&&context.isLombok(context.typeEntity(resolved.asField().declaringType().asReferenceType()),"lombok.AccessLevel")) {
                String value=resolved.getName();
                if(Set.of("PUBLIC","PROTECTED","PACKAGE","PRIVATE","NONE").contains(value))return value;
            }
        } catch(RuntimeException ignored) {}
        String raw=level.toString();
        if(raw.matches("(?:lombok\\.)?AccessLevel\\.(PUBLIC|PROTECTED|PACKAGE|PRIVATE|NONE)")) {
            return raw.substring(raw.lastIndexOf('.')+1);
        }
        throw new IllegalArgumentException();
    }
    private static String capitalize(String name) {
        if(name.length()>1&&Character.isLowerCase(name.charAt(0))&&(Character.isUpperCase(name.charAt(1))||Character.isTitleCase(name.charAt(1))))return Character.toUpperCase(name.charAt(0))+name.substring(1);
        return Character.toTitleCase(name.charAt(0))+name.substring(1);
    }
    private static boolean suppressed(ClassOrInterfaceDeclaration type,Set<String> names,int arity) {
        return type.getMethods().stream().anyMatch(m->names.stream().anyMatch(n->n.equalsIgnoreCase(m.getNameAsString()))
                &&(m.getParameters().size()==arity||!m.getParameters().isEmpty()&&m.getParameter(m.getParameters().size()-1).isVarArgs()&&arity>=m.getParameters().size()-1));
    }
    private void objectMembers(ClassOrInterfaceDeclaration type,boolean value,boolean includeToString,boolean includeEqualsHashCode) {
        boolean equalitySuppressed=!includeEqualsHashCode||type.getMethodsByName("equals").stream().anyMatch(m->m.getParameters().size()==1)
                ||type.getMethodsByName("hashCode").stream().anyMatch(m->m.getParameters().isEmpty());
        for(String method:List.of("toString","hashCode","equals","canEqual")) {
            if(method.equals("toString")&&!includeToString)continue;
            if(!method.equals("toString")&&equalitySuppressed)continue;
            if(method.equals("canEqual")&&(value||type.isFinal())&&type.getExtendedTypes().isEmpty())continue;
            int count=Set.of("equals","canEqual").contains(method)?1:0;
            if(type.getMethodsByName(method).stream().anyMatch(m->m.getParameters().size()==count))continue;
            var object=ErasedType.declared(JavaSymbolName.topLevelType("java.lang","Object"));
            var name=JavaSymbolName.method(context.name(type),method,count==1?List.of(object):List.of());
            var entity=context.implicit(name,EntityKind.METHOD,type,"lombok.object-methods");
            if(count==1) {
                var parameter=context.implicit(JavaSymbolName.parameter(name,0),EntityKind.PARAMETER,entity,type,"lombok.equals-parameter");
                context.derive(entity,parameter,"has-parameter",type,"lombok.object-methods");
            }
        }
    }
    private void builder(ClassOrInterfaceDeclaration type,List<VariableDeclarator> fields) {
        if(!type.getTypeParameters().isEmpty()){context.synthesisGap(type,"lombok.generic-builder-required");return;}
        var builderName=JavaSymbolName.memberType(context.name(type),type.getNameAsString()+"Builder");
        if(context.hasDeclaration(builderName)){context.synthesisGap(type,"lombok.existing-builder-required");return;}
        var builder=context.implicit(builderName,EntityKind.TYPE,type,"lombok.builder-type");
        context.implicit(JavaSymbolName.constructor(builderName,List.of()),EntityKind.CONSTRUCTOR,builder,type,"lombok.builder-no-args");
        var factoryName=JavaSymbolName.method(context.name(type),"builder",List.of());
        Entity factory=null;
        if(type.getMethodsByName("builder").isEmpty()) {
            factory=context.implicit(factoryName,EntityKind.METHOD,type,"lombok.builder-factory");
            context.derive(factory,builder,"returns",type,"lombok.builder-factory");
        }
        var build=context.implicit(JavaSymbolName.method(builderName,"build",List.of()),EntityKind.METHOD,builder,type,"lombok.builder-build");
        context.derive(build,context.entity(type),"returns",type,"lombok.builder-build");
        context.implicit(JavaSymbolName.method(builderName,"toString",List.of()),EntityKind.METHOD,builder,type,"lombok.builder-to-string");
        var setters=new TreeMap<String,Entity>();
        for(var f:fields) {
            var name=JavaSymbolName.method(builderName,f.getNameAsString(),List.of(context.erasedParameter(f.getType())));
            var setter=context.implicit(name,EntityKind.METHOD,builder,f,"lombok.builder-setter");
            setters.put(f.getNameAsString(),setter);
            var parameter=context.implicit(JavaSymbolName.parameter(name,0),EntityKind.PARAMETER,setter,f,"lombok.builder-parameter");
            context.derive(setter,parameter,"has-parameter",f,"lombok.builder-setter");context.derive(setter,builder,"returns",f,"lombok.builder-setter");
            // These parameters derive types from source fields, but builder storage is a different field.
            generated.add(new Generated(setter,name,List.of(f),List.of(parameter),"lombok.builder-setter",true,false));
        }
        builders.put(context.name(type),new BuilderShape(type,factory,build,setters,List.copyOf(fields)));
    }
    boolean hasGeneratedConstructor(ClassOrInterfaceDeclaration type) {
        return generatedOwners.entrySet().stream().anyMatch(e->sameOwner(e.getKey(),type)&&e.getKey().kind()==EntityKind.CONSTRUCTOR);
    }
    private void constructor(ClassOrInterfaceDeclaration type,List<VariableDeclarator> fields,String rule) {
        var name=JavaSymbolName.constructor(context.name(type),fields.stream().map(f->context.erasedParameter(f.getType())).toList());
        if(context.hasDeclaration(name)){context.synthesisGap(type,"lombok.constructor-collision");return;}
        var callable=context.implicit(name,EntityKind.CONSTRUCTOR,type,rule);
        generatedOwners.put(callable,type);
        generated.add(new Generated(callable,name,fields,parameters(callable,name,fields,rule),rule,true,false));
    }
    private void member(ClassOrInterfaceDeclaration type,String method,List<VariableDeclarator> parameters,List<VariableDeclarator> fields,String rule,boolean setter,boolean returnsOwner) {
        var name=JavaSymbolName.method(context.name(type),method,parameters.stream().map(f->context.erasedParameter(f.getType())).toList());
        if(context.hasDeclaration(name))return;
        var callable=context.implicit(name,EntityKind.METHOD,type,rule);
        if(returnsOwner)context.derive(callable,context.entity(type),"returns",type,rule);
        generatedOwners.put(callable,type);
        generatedMethodNames.put(callable,method);
        generated.add(new Generated(callable,name,fields,parameters(callable,name,parameters,rule),rule,setter,returnsOwner));
    }
    private List<Entity> parameters(Entity callable,JavaSymbolName name,List<VariableDeclarator> fields,String rule) {
        var result=new ArrayList<Entity>();
        for(int i=0;i<fields.size();i++) {
            var field=fields.get(i);var parameter=context.implicit(JavaSymbolName.parameter(name,i),EntityKind.PARAMETER,callable,field,rule+"-parameter");
            context.derive(callable,parameter,"has-parameter",field,rule);result.add(parameter);
        }
        return result;
    }
    void relationships(CompilationUnit unit) {
        for(var g:generated)for(int i=0;i<g.fields().size();i++) {
            var f=g.fields().get(i);if(f.findCompilationUnit().orElseThrow()!=unit)continue;
            if(!g.rule().equals("lombok.builder-setter"))context.derive(g.callable(),context.entity(f),g.setter()?"writes-field":"reads-field",f,g.rule());
            if(g.setter()&&i<g.parameters().size())context.derivedMemberTypes(f,g.parameters().get(i),"parameter-type",g.rule());
            else if(!g.setter())context.derivedMemberTypes(f,g.callable(),"returns",g.rule());
        }
    }
    private boolean sameOwner(Entity callable,ClassOrInterfaceDeclaration owner) {
        var recorded=generatedOwners.get(callable);
        if(recorded==null)return false;
        if(recorded==owner)return true;
        try{return context.name(recorded).equals(context.name(owner));}catch(RuntimeException unavailable){return false;}
    }

    /** Cache proven return types on Lombok-resolved calls so downstream JavaParser resolution
     * (e.g. order.getItems().stream() or service.findById(dto.getId())) sees the exact return type. */
    @SuppressWarnings("unchecked")
    void prepareExpressions(CompilationUnit unit) {
        if(generated.isEmpty()&&builders.isEmpty()&&utilityClasses.isEmpty())return;
        unit.walk(Node.TreeTraversal.POSTORDER,node->{
            if(!(node instanceof MethodCallExpr call))return;
            try {
                var returnType=inferredReturnType(call);
                if(returnType.isEmpty())return;
                cacheResolvedType(call,returnType.orElseThrow());
            }catch(RuntimeException ignored){}
        });
    }

    @SuppressWarnings("unchecked")
    private static void cacheResolvedType(Expression node,ResolvedType resolvedType) {
        try {
            var probe=new BooleanLiteralExpr(true);
            node.getParentNode().ifPresent(probe::setParentNode);
            probe.calculateResolvedType();
            for(var key:new ArrayList<>(probe.getDataKeys())) {
                if(key.getClass().getEnclosingClass()==com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.class) {
                    node.setData((DataKey<ResolvedType>)key,resolvedType);
                }
            }
        }catch(RuntimeException ignored){}
    }

    private Optional<ResolvedType> inferredReturnType(MethodCallExpr call) {
        var built=builderCall(call,0);
        if(built.isPresent()) {
            if(built.orElseThrow().built())return Optional.of(new ReferenceTypeImpl(built.orElseThrow().builder().owner().resolve()));
            return Optional.empty();
        }
        var utility=resolveUtilityMethodDeclaration(call);
        if(utility.isPresent()&&!utility.orElseThrow().getType().isVoidType()) {
            return Optional.of(utility.orElseThrow().getType().resolve());
        }
        var resolvedEntity=resolve(call);
        if(resolvedEntity.isEmpty())return Optional.empty();
        var target=resolvedEntity.orElseThrow();
        for(var candidate:generated) {
            if(!candidate.callable().equals(target))continue;
            if(candidate.returnsOwner()) {
                var owner=generatedOwners.get(target);
                if(owner!=null)return Optional.of(new ReferenceTypeImpl(owner.resolve()));
            }
            if(!candidate.setter()&&candidate.fields().size()==1) {
                return Optional.of(candidate.fields().getFirst().getType().resolve());
            }
        }
        return Optional.empty();
    }

    Optional<Entity> resolveField(Expression expression) {
        if(loggers.isEmpty()&&utilityClasses.isEmpty())return Optional.empty();
        try {
            if(expression instanceof NameExpr name) {
                if(name.getNameAsString().equals("log")) {
                    if(hasLocalShadow(name,"log"))return Optional.empty();
                    for(Node current=name.getParentNode().orElse(null);current!=null;current=current.getParentNode().orElse(null)) {
                        if(current instanceof TypeDeclaration<?> enclosing) {
                            var shape=loggers.get(context.name(enclosing));
                            if(shape!=null)return Optional.of(shape.field());
                        }
                    }
                }
                for(Node current=name.getParentNode().orElse(null);current!=null;current=current.getParentNode().orElse(null)) {
                    if(current instanceof ClassOrInterfaceDeclaration enclosing&&isUtilityClass(enclosing)) {
                        var match=enclosing.getFields().stream().flatMap(f->f.getVariables().stream())
                                .filter(v->v.getNameAsString().equals(name.getNameAsString())).findFirst();
                        if(match.isPresent())return Optional.of(context.entity(match.orElseThrow()));
                    }
                }
            } else if(expression instanceof FieldAccessExpr access) {
                if(access.getNameAsString().equals("log")) {
                    if(access.getScope() instanceof ThisExpr) {
                        for(Node current=access.getParentNode().orElse(null);current!=null;current=current.getParentNode().orElse(null)) {
                            if(current instanceof TypeDeclaration<?> enclosing) {
                                var shape=loggers.get(context.name(enclosing));
                                if(shape!=null)return Optional.of(shape.field());
                            }
                        }
                    } else if(access.getScope() instanceof NameExpr||access.getScope() instanceof FieldAccessExpr) {
                        var ast=context.resolveTypeName(access.getScope(),access.getScope().toString()).toAst().orElse(null);
                        if(ast instanceof TypeDeclaration<?> owner) {
                            var shape=loggers.get(context.name(owner));
                            if(shape!=null)return Optional.of(shape.field());
                        }
                    }
                }
                if(access.getScope() instanceof NameExpr||access.getScope() instanceof FieldAccessExpr) {
                    var ast=context.resolveTypeName(access.getScope(),access.getScope().toString()).toAst().orElse(null);
                    if(ast instanceof ClassOrInterfaceDeclaration owner&&isUtilityClass(owner)) {
                        var match=owner.getFields().stream().flatMap(f->f.getVariables().stream())
                                .filter(v->v.getNameAsString().equals(access.getNameAsString())).findFirst();
                        if(match.isPresent())return Optional.of(context.entity(match.orElseThrow()));
                    }
                }
            }
        }catch(RuntimeException ignored){}
        return Optional.empty();
    }

    private static boolean hasLocalShadow(Node site,String identifier) {
        for(Node current=site.getParentNode().orElse(null);current!=null&&!(current instanceof TypeDeclaration<?>);current=current.getParentNode().orElse(null)) {
            if(current instanceof CallableDeclaration<?> callable&&callable.getParameters().stream().anyMatch(p->p.getNameAsString().equals(identifier)))return true;
            if(current instanceof LambdaExpr lambda&&lambda.getParameters().stream().anyMatch(p->p.getNameAsString().equals(identifier)))return true;
            if(current instanceof com.github.javaparser.ast.stmt.CatchClause catchClause&&catchClause.getParameter().getNameAsString().equals(identifier))return true;
            if(current instanceof com.github.javaparser.ast.stmt.BlockStmt block) {
                for(var stmt:block.getStatements()) {
                    if(stmt.isAncestorOf(site))break;
                    if(stmt.findAll(VariableDeclarator.class).stream().anyMatch(v->v.getNameAsString().equals(identifier)))return true;
                }
            }
        }
        return false;
    }

    private Optional<LoggerShape> resolveLoggerReceiver(Expression scope,int depth) {
        if(depth>16||loggers.isEmpty())return Optional.empty();
        var field=resolveField(scope);
        if(field.isPresent()) {
            return loggers.values().stream().filter(s->s.field().equals(field.orElseThrow())).findFirst();
        }
        if(scope instanceof MethodCallExpr nested&&nested.getScope().isPresent()) {
            return resolveLoggerReceiver(nested.getScope().orElseThrow(),depth+1);
        }
        return Optional.empty();
    }

    private Optional<Entity> resolveLoggerCall(MethodCallExpr call) {
        if(call.getScope().isEmpty()||loggers.isEmpty())return Optional.empty();
        String method=call.getNameAsString();
        if(!LOGGER_METHODS.contains(method))return Optional.empty();
        var shape=resolveLoggerReceiver(call.getScope().orElseThrow(),0);
        if(shape.isEmpty())return Optional.empty();
        var logger=shape.orElseThrow();
        var erasedArgs=new ArrayList<ErasedType>();
        for(var arg:call.getArguments()) {
            try {
                var t=arg.calculateResolvedType();
                if(t.isPrimitive())erasedArgs.add(ErasedType.primitive(t.describe()));
                else if(t.isReferenceType())erasedArgs.add(ErasedType.declared(context.typeName(t.asReferenceType().getTypeDeclaration().orElseThrow())));
                else erasedArgs.add(ErasedType.declared(JavaSymbolName.topLevelType("java.lang","Object")));
            }catch(RuntimeException fallback) {
                erasedArgs.add(ErasedType.declared(JavaSymbolName.topLevelType("java.lang","Object")));
            }
        }
        var symbol=JavaSymbolName.method(logger.loggerType(),method,erasedArgs);
        return Optional.of(context.implicit(symbol,EntityKind.METHOD,logger.field(),logger.owner(),"lombok.logger-call"));
    }

    private Optional<MethodDeclaration> resolveUtilityMethodDeclaration(MethodCallExpr call) {
        if(utilityClasses.isEmpty())return Optional.empty();
        try {
            ClassOrInterfaceDeclaration owner=null;
            if(call.getScope().isEmpty()) {
                var enclosing=call.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
                if(enclosing!=null&&isUtilityClass(enclosing))owner=enclosing;
            } else {
                var scope=call.getScope().orElseThrow();
                if(scope instanceof NameExpr||scope instanceof FieldAccessExpr) {
                    var ast=context.resolveTypeName(scope,scope.toString()).toAst().orElse(null);
                    if(ast instanceof ClassOrInterfaceDeclaration c&&isUtilityClass(c))owner=c;
                }
            }
            if(owner==null)return Optional.empty();
            var candidates=owner.getMethodsByName(call.getNameAsString()).stream()
                    .filter(m->m.getParameters().size()==call.getArguments().size()).toList();
            if(candidates.size()==1)return Optional.of(candidates.getFirst());
        }catch(RuntimeException ignored){}
        return Optional.empty();
    }

    /** Exact-argument generated overloads participate before native resolution can select a
     * wider handwritten overload. No AST mutation, processor execution, or guessed coercion. */
    Optional<Entity> resolve(Expression expression) {
        if(expression instanceof MethodCallExpr call) {
            var loggerCall=resolveLoggerCall(call);
            if(loggerCall.isPresent())return loggerCall;
            var utilityMethod=resolveUtilityMethodDeclaration(call);
            if(utilityMethod.isPresent())return Optional.of(context.entity(utilityMethod.orElseThrow()));
        }
        if(generated.isEmpty()&&builders.isEmpty())return Optional.empty();
        boolean affectedGenerated=false;
        try {
            ClassOrInterfaceDeclaration receiver;
            List<Expression> arguments;
            String method;
            boolean constructor=expression instanceof ObjectCreationExpr;
            if(expression instanceof ObjectCreationExpr creation) {
                if(creation.getAnonymousClassBody().isPresent())return Optional.empty();
                var ast=creation.getType().resolve().asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
                if(!(ast instanceof ClassOrInterfaceDeclaration type))return Optional.empty();
                receiver=type;arguments=creation.getArguments();method="";
            } else if(expression instanceof MethodCallExpr call) {
                if(call.getTypeArguments().isPresent())return Optional.empty();
                var built=builderCall(call,0);if(built.isPresent())return Optional.of(built.orElseThrow().target());
                if(call.getScope().isPresent()) {
                    var scope=call.getScope().orElseThrow();
                    if(scope instanceof NameExpr name && context.isTypeName(scope,name.getNameAsString()))return Optional.empty();
                    var prior=scope instanceof MethodCallExpr nested?builderCall(nested,0):Optional.<BuilderCall>empty();
                    if(prior.isPresent()&&prior.orElseThrow().built())receiver=prior.orElseThrow().builder().owner();
                    else {
                        var resolved=scope instanceof MethodCallExpr nestedCall?inferredReturnType(nestedCall).orElseGet(scope::calculateResolvedType):scope.calculateResolvedType();
                        if(!resolved.isReferenceType())return Optional.empty();
                        var ast=resolved.asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
                        if(!(ast instanceof ClassOrInterfaceDeclaration type))return Optional.empty();
                        receiver=type;
                    }
                } else {
                    receiver=call.findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow();
                    // An implicit receiver is not established in static methods or nested anonymous scopes.
                    for(Node parent=call.getParentNode().orElse(null);parent!=receiver;parent=parent.getParentNode().orElse(null)) {
                        if(parent==null || parent instanceof ObjectCreationExpr
                                || parent instanceof MethodDeclaration m && m.isStatic()
                                || parent instanceof InitializerDeclaration i && i.isStatic()
                                || parent instanceof FieldDeclaration f && f.isStatic())return Optional.empty();
                    }
                }
                method=call.getNameAsString();arguments=call.getArguments();
            } else return Optional.empty();
            final ClassOrInterfaceDeclaration owner=!constructor?inheritedAccessorOwner(receiver,method,arguments.size()):receiver;
            boolean generatedConstructor=constructor && generated.stream().anyMatch(g -> sameOwner(g.callable(),owner)
                    && g.callable().kind()==EntityKind.CONSTRUCTOR);
            affectedGenerated=generatedConstructor || !constructor && generated.stream().anyMatch(g ->
                    sameOwner(g.callable(),owner) && method.equals(generatedMethodNames.get(g.callable())));
            if(!owner.getTypeParameters().isEmpty()&&(constructor||!arguments.isEmpty())||constructor&&!owner.getExtendedTypes().isEmpty()
                    ||!constructor&&hasAncestorHandwrittenMethod(owner,method,arguments.size())) {
                if(affectedGenerated)throw new GeneratedAccessFailure();
                return Optional.empty();
            }
            var actualTypes=arguments.stream().map(arg->arg instanceof MethodCallExpr nestedCall?inferredReturnType(nestedCall).orElseGet(arg::calculateResolvedType):arg.calculateResolvedType()).toList();
            var actual=actualTypes.stream().map(t->t.describe()).toList();
            var matches=new ArrayList<Entity>();
            for(var candidate:generated) {
                if(!sameOwner(candidate.callable(),owner))continue;
                if(constructor!=(candidate.callable().kind()==EntityKind.CONSTRUCTOR))continue;
                if(!constructor && !candidate.name().equals(JavaSymbolName.method(context.name(owner),method,
                        candidate.setter()?candidate.fields().stream().map(f -> context.erasedParameter(f.getType())).toList():List.of())))continue;
                var formal=(constructor||candidate.setter()?candidate.fields():List.<VariableDeclarator>of()).stream()
                        .map(f -> f.getType().resolve().describe()).toList();
                if(!formal.equals(actual)) {
                    if(constructor||!candidate.setter()||candidate.fields().size()!=1||actualTypes.size()!=1)continue;
                    var expected=candidate.fields().getFirst().getType().resolve();var supplied=actualTypes.getFirst();
                    boolean nullable=supplied.isNull()&&(expected.isReferenceType()||expected.isArray());
                    var boxing=Map.of("boolean","java.lang.Boolean","byte","java.lang.Byte","short","java.lang.Short","char","java.lang.Character","int","java.lang.Integer","long","java.lang.Long","float","java.lang.Float","double","java.lang.Double");
                    boolean boxed=expected.isPrimitive()&&supplied.isReferenceType()&&supplied.asReferenceType().getQualifiedName().equals(boxing.get(expected.describe()))
                            ||supplied.isPrimitive()&&expected.isReferenceType()&&expected.asReferenceType().getQualifiedName().equals(boxing.get(supplied.describe()));
                    if(!nullable&&!boxed)continue;
                    // Conversion could expose an inherited overload; only an exact owned signature can bypass it.
                    if(!owner.getExtendedTypes().isEmpty())throw new GeneratedAccessFailure();
                }
                memberAccess(owner,candidate.callable(),expression);
                if(constructor && (candidate.rule().equals("lombok.builder-constructor") || owner.getAnnotations().stream()
                        .filter(a -> a instanceof NormalAnnotationExpr)
                        .map(a -> (NormalAnnotationExpr)a).flatMap(a -> a.getPairs().stream())
                        .anyMatch(p -> p.getNameAsString().equals("access")&&!p.getValue().toString().matches("(?:lombok\\.)?AccessLevel.PUBLIC"))))
                    throw new GeneratedAccessFailure();
                if(!owner.isPublic()&&!owner.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p -> p.getNameAsString())
                        .equals(expression.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p -> p.getNameAsString())))
                    throw new GeneratedAccessFailure();
                for(Node enclosing=owner;enclosing!=null;enclosing=enclosing.getParentNode().orElse(null))
                    if(enclosing instanceof ClassOrInterfaceDeclaration type && (type.isPrivate()||type.isProtected()))
                        throw new GeneratedAccessFailure();
                matches.add(candidate.callable());
            }
            if(matches.size()==1)return Optional.of(matches.getFirst());
            // Native resolution sees only handwritten overloads. Null, conversions and generics
            // must not silently pick one while a generated constructor can change the result.
            if(affectedGenerated)throw new GeneratedAccessFailure();
            return Optional.empty();
        } catch(GeneratedAccessFailure unavailable) {throw unavailable;}
        catch(RuntimeException unavailable) {
            if(affectedGenerated)throw new GeneratedAccessFailure();
            return Optional.empty();
        }
    }
    private boolean hasAncestorHandwrittenMethod(ClassOrInterfaceDeclaration owner,String method,int arity) {
        var current=owner;var visited=new HashSet<JavaSymbolName>();
        while(visited.add(context.name(current))&&current.getExtendedTypes().size()==1) {
            var parent=current.getExtendedTypes().get(0).resolve().asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
            if(!(parent instanceof ClassOrInterfaceDeclaration next))return false;
            if(suppressed(next,Set.of(method),arity))return true;
            current=next;
        }
        return false;
    }
    private ClassOrInterfaceDeclaration inheritedAccessorOwner(ClassOrInterfaceDeclaration receiver,String method,int arity) {
        var current=receiver;var visited=new HashSet<JavaSymbolName>();
        while(visited.add(context.name(current))) {
            final var at=current;
            if(generated.stream().anyMatch(g->sameOwner(g.callable(),at)&&method.equals(generatedMethodNames.get(g.callable()))&&(g.setter()?g.fields().size():0)==arity))return current;
            // A written method on the path needs native overload selection.
            if(suppressed(current,Set.of(method),arity)||current.getExtendedTypes().size()!=1)return receiver;
            var parent=current.getExtendedTypes().get(0).resolve().asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
            if(!(parent instanceof ClassOrInterfaceDeclaration next))return receiver;current=next;
        }
        throw new GeneratedAccessFailure();
    }
    private Optional<BuilderCall> builderCall(MethodCallExpr call,int depth) {
        if(depth>=128)throw new GeneratedAccessFailure();
        if(call.getScope().isEmpty()||call.getTypeArguments().isPresent())return Optional.empty();
        var scope=call.getScope().orElseThrow();
        if(call.getNameAsString().equals("builder")&&call.getArguments().isEmpty()) {
            if(!(scope instanceof NameExpr||scope instanceof FieldAccessExpr))return Optional.empty();
            try {
                // Values shadow type names; factory binding requires the actual type qualifier.
                if(scope instanceof NameExpr name)try{name.resolve();return Optional.empty();}catch(com.github.javaparser.resolution.UnsolvedSymbolException typeQualifier){}
                if(scope instanceof FieldAccessExpr field)try{field.resolve();return Optional.empty();}catch(com.github.javaparser.resolution.UnsolvedSymbolException typeQualifier){}
                var ast=context.resolveTypeName(scope,scope.toString()).toAst().orElse(null);
                if(!(ast instanceof ClassOrInterfaceDeclaration owner))return Optional.empty();
                var shape=builders.get(context.name(owner));if(shape==null||shape.factory()==null)return Optional.empty();
                builderAccess(shape.owner(),call);
                return Optional.of(new BuilderCall(shape.factory(),shape,false));
            }catch(GeneratedAccessFailure failed){throw failed;}
            catch(RuntimeException unavailable){return Optional.empty();}
        }
        if(!(scope instanceof MethodCallExpr nested))return Optional.empty();
        var previous=builderCall(nested,depth+1);if(previous.isEmpty()||previous.orElseThrow().built())return Optional.empty();
        var shape=previous.orElseThrow().builder();builderAccess(shape.owner(),call);
        if(call.getNameAsString().equals("build")&&call.getArguments().isEmpty())return Optional.of(new BuilderCall(shape.build(),shape,true));
        var setter=shape.setters().get(call.getNameAsString());
        if(setter==null||call.getArguments().size()!=1)throw new GeneratedAccessFailure();
        var field=shape.fields().stream().filter(f->f.getNameAsString().equals(call.getNameAsString())).findFirst().orElseThrow();
        var arg=call.getArgument(0);
        var argType=arg instanceof MethodCallExpr nestedCall?inferredReturnType(nestedCall).orElseGet(arg::calculateResolvedType):arg.calculateResolvedType();
        if(!field.getType().resolve().isAssignableBy(argType))throw new GeneratedAccessFailure();
        return Optional.of(new BuilderCall(setter,shape,false));
    }
    private void builderAccess(ClassOrInterfaceDeclaration owner,Expression site) {
        boolean samePackage=owner.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p->p.getNameAsString())
                .equals(site.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p->p.getNameAsString()));
        if(!owner.isPublic()&&!samePackage)throw new GeneratedAccessFailure();
        for(Node enclosing=owner;enclosing!=null;enclosing=enclosing.getParentNode().orElse(null))
            if(enclosing instanceof ClassOrInterfaceDeclaration type&&(type.isPrivate()||type.isProtected()))throw new GeneratedAccessFailure();
        boolean customized=owner.getAnnotations().stream().filter(a->verifiedOrRecovered(a,"lombok.Builder")).filter(NormalAnnotationExpr.class::isInstance)
                .map(NormalAnnotationExpr.class::cast).flatMap(a->a.getPairs().stream()).anyMatch(p->p.getNameAsString().equals("access")&&!p.getValue().toString().matches("(?:lombok\\.)?AccessLevel.PUBLIC"));
        if(customized)throw new GeneratedAccessFailure();
    }
    private static final class GeneratedAccessFailure extends com.github.javaparser.resolution.UnsolvedSymbolException {
        GeneratedAccessFailure() {super("Generated member applicability or accessibility requires evidence");}
    }
    Optional<Entity> resolveReference(MethodReferenceExpr reference,ReferenceResolution references) {
        if(generated.isEmpty())return Optional.empty();
        boolean affected=false;
        try {
            var functional=references.groundTarget(references.targetType(reference));
            var sam=com.github.javaparser.resolution.logic.FunctionalInterfaceLogic.getFunctionalMethod(functional).orElseThrow();
            var substitutions=functional.asReferenceType().typeParametersMap();
            var arguments=new ArrayList<>(sam.getParamTypes().stream().map(substitutions::replaceAll).toList());
            var expectedReturn=substitutions.replaceAll(sam.returnType());
            var value=ReferenceResolution.value(reference,reference.getScope().toString());
            boolean unbound=reference.getScope() instanceof TypeExpr&&value.isEmpty();
            var receiver=value.isPresent()?value.orElseThrow().getType():reference.getScope() instanceof TypeExpr type?type.getType().resolve():reference.getScope().calculateResolvedType();
            if(!receiver.isReferenceType())return Optional.empty();
            var ast=receiver.asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
            if(!(ast instanceof ClassOrInterfaceDeclaration owner))return Optional.empty();
            String method=reference.getIdentifier();
            var candidates=generated.stream().filter(g->sameOwner(g.callable(),owner)&&method.equals(generatedMethodNames.get(g.callable()))).toList();
            if(candidates.isEmpty())return Optional.empty();affected=true;
            if(unbound){if(arguments.isEmpty()||!receiver.isAssignableBy(arguments.removeFirst()))throw new GeneratedAccessFailure();}
            var matches=new ArrayList<Entity>();
            for(var candidate:candidates) {
                int count=candidate.setter()?candidate.fields().size():0;if(count!=arguments.size())continue;
                var typeMap=receiver.asReferenceType().typeParametersMap();boolean compatible=true;
                for(int i=0;i<count;i++)if(!typeMap.replaceAll(candidate.fields().get(i).getType().resolve()).isAssignableBy(arguments.get(i)))compatible=false;
                if(!compatible)continue;
                if(!expectedReturn.isVoid()) {
                    if(candidate.returnsOwner()) {
                        if(!expectedReturn.isAssignableBy(receiver))continue;
                    } else if(candidate.setter()||!expectedReturn.isAssignableBy(typeMap.replaceAll(candidate.fields().getFirst().getType().resolve())))continue;
                }
                memberAccess(owner,candidate.callable(),reference);matches.add(candidate.callable());
            }
            if(matches.size()!=1)throw new GeneratedAccessFailure();return Optional.of(matches.getFirst());
        }catch(GeneratedAccessFailure failed){throw failed;}
        catch(RuntimeException unavailable){if(affected)throw new GeneratedAccessFailure();return Optional.empty();}
    }
    private void memberAccess(ClassOrInterfaceDeclaration owner,Entity callable,Expression site) {
        String access=generatedAccess.getOrDefault(callable,"PUBLIC");
        boolean samePackage=owner.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p->p.getNameAsString())
                .equals(site.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p->p.getNameAsString()));
        boolean sameType=site.findAncestor(ClassOrInterfaceDeclaration.class).map(c->context.name(c).equals(context.name(owner))).orElse(false);
        if(access.equals("PRIVATE")&&!sameType||!access.equals("PUBLIC")&&!access.equals("PRIVATE")&&!samePackage)throw new GeneratedAccessFailure();
        if(!owner.isPublic()&&!samePackage)throw new GeneratedAccessFailure();
        for(Node enclosing=owner;enclosing!=null;enclosing=enclosing.getParentNode().orElse(null))
            if(enclosing instanceof ClassOrInterfaceDeclaration type&&(type.isPrivate()||type.isProtected()))throw new GeneratedAccessFailure();
    }
    private boolean nonNull(FieldDeclaration field){return field.getAnnotations().stream().anyMatch(a->verifiedOrRecovered(a,"lombok.NonNull"));}
    private boolean nonFinal(FieldDeclaration field){return field.getAnnotations().stream().anyMatch(a->verifiedOrRecovered(a,"lombok.experimental.NonFinal"));}
    private boolean verifiedOrRecovered(AnnotationExpr annotation,String name){return exact(annotation,name)||context.isSourceRecoveryLombok(annotation,name);}
    private boolean exact(AnnotationExpr annotation,String name){try{return context.isLombok(context.typeEntity(annotation.resolve()),name);}catch(RuntimeException failure){return false;}}
    private static FieldDeclaration field(VariableDeclarator variable){return (FieldDeclaration)variable.getParentNode().orElseThrow();}
    private static boolean optionsSupported(AnnotationExpr annotation) {
        if(annotation.isMarkerAnnotationExpr())return true;
        if(!(annotation instanceof NormalAnnotationExpr normal))return false;
        return normal.getPairs().stream().allMatch(p->p.getNameAsString().equals("force")&&p.getValue().isBooleanLiteralExpr()
                ||p.getNameAsString().equals("access")&&p.getValue().toString().matches("(?:lombok\\.)?AccessLevel\\.(PUBLIC|PROTECTED|PACKAGE|PRIVATE)")
                ||Set.of("staticName","staticConstructor").contains(p.getNameAsString())&&p.getValue().isStringLiteralExpr()&&p.getValue().asStringLiteralExpr().asString().isEmpty());
    }
}
