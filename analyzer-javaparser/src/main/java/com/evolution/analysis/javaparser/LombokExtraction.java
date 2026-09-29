package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import java.util.*;

/** Evidence-bound structural synthesis. Never runs annotation processors or changes source ASTs. */
final class LombokExtraction {
    private static final Set<String> GENERATORS=Set.of("RequiredArgsConstructor","AllArgsConstructor","NoArgsConstructor","Data","Value","Builder");
    private final Extraction context;
    private final List<Generated> generated=new ArrayList<>();
    private final Map<Entity,ClassOrInterfaceDeclaration> generatedOwners=new HashMap<>();
    private final Map<Entity,String> generatedMethodNames=new HashMap<>();
    private final Set<ClassOrInterfaceDeclaration> unsupportedConstructorOwners=Collections.newSetFromMap(new IdentityHashMap<>());
    private record Generated(Entity callable,JavaSymbolName name,List<VariableDeclarator> fields,List<Entity> parameters,String rule,boolean setter){}
    LombokExtraction(Extraction context){this.context=context;}

    void unsupportedGenerators(CompilationUnit unit) {
        var otherGenerators=Set.of("Getter","Setter","With","WithBy","ToString","EqualsAndHashCode","SuperBuilder","UtilityClass","FieldDefaults","Accessors");
        for(var annotation:unit.findAll(AnnotationExpr.class)) {
            String simple=annotation.getName().getIdentifier();var parent=annotation.getParentNode().orElse(null);
            if(GENERATORS.contains(simple)&&parent instanceof ClassOrInterfaceDeclaration type&&!type.isInterface())continue;
            if(!GENERATORS.contains(simple)&&!otherGenerators.contains(simple))continue;
            try {
                var entity=context.typeEntity(annotation.resolve());
                if(entity.origin()==EntityOrigin.PROJECT)continue;
                String name=JavaTypeName.fromCanonical(entity.canonicalName()).map(JavaTypeName::qualifiedName).orElse("");
                if(!name.startsWith("lombok."))continue;
                context.synthesisGap(annotation,context.isLombok(entity,name)?"lombok.generator-unsupported":"lombok.artifact-unverified");
                if(parent instanceof ClassOrInterfaceDeclaration type&&Set.of("SuperBuilder","UtilityClass").contains(simple))unsupportedConstructorOwners.add(type);
            }catch(RuntimeException failure){context.synthesisGap(annotation,"lombok.annotation-unresolved");}
        }
    }

    boolean declarations(ClassOrInterfaceDeclaration type) {
        if(unsupportedConstructorOwners.contains(type))return true;
        if(type.isInterface())return false;
        Map<String,AnnotationExpr> annotations=new TreeMap<>();boolean potential=false;
        for(var annotation:type.getAnnotations()) {
            String simple=annotation.getName().getIdentifier();if(!GENERATORS.contains(simple))continue;
            try {
                var entity=context.typeEntity(annotation.resolve());
                if(entity.origin()==EntityOrigin.PROJECT)continue;
                if(context.isLombok(entity,"lombok."+simple)){annotations.put(simple,annotation);potential=true;}
                else if(annotation.getNameAsString().startsWith("lombok.")||entity.canonicalName().contains("lombok")){potential=true;context.synthesisGap(type,"lombok.artifact-unverified");}
            }catch(RuntimeException failure){potential=true;context.synthesisGap(type,"lombok.annotation-unresolved");}
        }
        if(!potential)return false;
        if(annotations.isEmpty())return true;
        if(context.hasLombokConfiguration(type)){context.synthesisGap(type,"lombok.configuration-required");return true;}
        if(type.isNestedType()&&!type.isStatic()){context.synthesisGap(type,"lombok.inner-class-constructor");return true;}
        if(type.findAll(AnnotationExpr.class).stream().anyMatch(a->Set.of("Accessors","Tolerate","FieldDefaults","Getter","Setter","Singular","Default").contains(a.getName().getIdentifier()))) {
            context.synthesisGap(type,"lombok.member-customization");return true;
        }
        var fields=type.getFields().stream().filter(f->!f.isStatic()).flatMap(f->f.getVariables().stream()).filter(f->!f.getNameAsString().startsWith("$")).toList();
        if(fields.stream().anyMatch(f->field(f).getAnnotations().stream().anyMatch(a->Set.of("NonNull","Nonnull","NotNull","NonNullDecl").contains(a.getName().getIdentifier())&&!exact(a,"lombok.NonNull")))) {
            context.synthesisGap(type,"lombok.nullness-annotation-unverified");return true;
        }
        boolean value=annotations.containsKey("Value");
        var required=fields.stream().filter(f->f.getInitializer().isEmpty()&&(field(f).isFinal()||nonNull(field(f)))).toList();
        var all=fields.stream().filter(f->f.getInitializer().isEmpty()||!(field(f).isFinal()||value&&!nonFinal(field(f)))).toList();
        boolean explicitAnnotation=annotations.keySet().stream().anyMatch(n->n.endsWith("ArgsConstructor"));
        for(var entry:annotations.entrySet())try {
            String name=entry.getKey();var annotation=entry.getValue();
            if(!optionsSupported(annotation)){context.synthesisGap(annotation,"lombok.annotation-options");continue;}
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
            if(name.equals("Builder"))builder(type,all);
        }catch(RuntimeException failure){context.synthesisGap(type,"lombok.member-type-unresolved");}
        if(annotations.containsKey("Data")||value) {
            objectMembers(type,value);
            for(var f:fields)try {
                boolean bool=f.getType().isPrimitiveType()&&f.getType().asPrimitiveType().getType()==com.github.javaparser.ast.type.PrimitiveType.Primitive.BOOLEAN;
                String name=f.getNameAsString();String capital=Character.toUpperCase(name.charAt(0))+name.substring(1);
                String getter=bool&&name.matches("is[A-Z].*")?name:(bool?"is":"get")+capital;
                if(type.getMethodsByName(getter).stream().noneMatch(m->m.getParameters().isEmpty()))member(type,getter,List.of(),List.of(f),"lombok.getter",false);
                if(!value&&!field(f).isFinal()) {
                    String setter="set"+(bool&&name.matches("is[A-Z].*")?name.substring(2):capital);
                    if(type.getMethodsByName(setter).stream().noneMatch(m->m.getParameters().size()==1))member(type,setter,List.of(f),List.of(f),"lombok.setter",true);
                }
            }catch(RuntimeException failure){context.synthesisGap(f,"lombok.member-type-unresolved");}
        }
        return true;
    }
    private void objectMembers(ClassOrInterfaceDeclaration type,boolean value) {
        boolean equalitySuppressed=type.getMethodsByName("equals").stream().anyMatch(m->m.getParameters().size()==1)
                ||type.getMethodsByName("hashCode").stream().anyMatch(m->m.getParameters().isEmpty());
        for(String method:List.of("toString","hashCode","equals","canEqual")) {
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
        if(type.getMethodsByName("builder").isEmpty()) {
            var factory=context.implicit(factoryName,EntityKind.METHOD,type,"lombok.builder-factory");
            context.derive(factory,builder,"returns",type,"lombok.builder-factory");
        }
        var build=context.implicit(JavaSymbolName.method(builderName,"build",List.of()),EntityKind.METHOD,builder,type,"lombok.builder-build");
        context.derive(build,context.entity(type),"returns",type,"lombok.builder-build");
        context.implicit(JavaSymbolName.method(builderName,"toString",List.of()),EntityKind.METHOD,builder,type,"lombok.builder-to-string");
        for(var f:fields) {
            var name=JavaSymbolName.method(builderName,f.getNameAsString(),List.of(context.erasedParameter(f.getType())));
            var setter=context.implicit(name,EntityKind.METHOD,builder,f,"lombok.builder-setter");
            var parameter=context.implicit(JavaSymbolName.parameter(name,0),EntityKind.PARAMETER,setter,f,"lombok.builder-parameter");
            context.derive(setter,parameter,"has-parameter",f,"lombok.builder-setter");context.derive(setter,builder,"returns",f,"lombok.builder-setter");
            // These parameters derive types from source fields, but builder storage is a different field.
            generated.add(new Generated(setter,name,List.of(f),List.of(parameter),"lombok.builder-setter",true));
        }
    }
    private void constructor(ClassOrInterfaceDeclaration type,List<VariableDeclarator> fields,String rule) {
        var name=JavaSymbolName.constructor(context.name(type),fields.stream().map(f->context.erasedParameter(f.getType())).toList());
        if(context.hasDeclaration(name)){context.synthesisGap(type,"lombok.constructor-collision");return;}
        var callable=context.implicit(name,EntityKind.CONSTRUCTOR,type,rule);
        generatedOwners.put(callable,type);
        generated.add(new Generated(callable,name,fields,parameters(callable,name,fields,rule),rule,true));
    }
    private void member(ClassOrInterfaceDeclaration type,String method,List<VariableDeclarator> parameters,List<VariableDeclarator> fields,String rule,boolean setter) {
        var name=JavaSymbolName.method(context.name(type),method,parameters.stream().map(f->context.erasedParameter(f.getType())).toList());
        if(context.hasDeclaration(name))return;
        var callable=context.implicit(name,EntityKind.METHOD,type,rule);
        generatedOwners.put(callable,type);
        generatedMethodNames.put(callable,method);
        generated.add(new Generated(callable,name,fields,parameters(callable,name,parameters,rule),rule,setter));
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
    /** Exact-argument generated overloads participate before native resolution can select a
     * wider handwritten overload. No AST mutation, processor execution, or guessed coercion. */
    Optional<Entity> resolve(Expression expression) {
        boolean affectedGenerated=false;
        try {
            ClassOrInterfaceDeclaration owner;
            List<Expression> arguments;
            String method;
            boolean constructor=expression instanceof ObjectCreationExpr;
            if(expression instanceof ObjectCreationExpr creation) {
                if(creation.getAnonymousClassBody().isPresent())return Optional.empty();
                var ast=creation.getType().resolve().asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
                if(!(ast instanceof ClassOrInterfaceDeclaration type))return Optional.empty();
                owner=type;arguments=creation.getArguments();method="";
            } else if(expression instanceof MethodCallExpr call) {
                if(call.getTypeArguments().isPresent())return Optional.empty();
                if(call.getScope().isPresent()) {
                    var scope=call.getScope().orElseThrow();
                    if(scope instanceof NameExpr name && context.isTypeName(scope,name.getNameAsString()))return Optional.empty();
                    var resolved=scope.calculateResolvedType();
                    if(!resolved.isReferenceType())return Optional.empty();
                    var ast=resolved.asReferenceType().getTypeDeclaration().orElseThrow().toAst().orElse(null);
                    if(!(ast instanceof ClassOrInterfaceDeclaration type))return Optional.empty();
                    owner=type;
                } else {
                    owner=call.findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow();
                    // An implicit receiver is not established in static methods or nested anonymous scopes.
                    for(Node parent=call.getParentNode().orElse(null);parent!=owner;parent=parent.getParentNode().orElse(null)) {
                        if(parent==null || parent instanceof ObjectCreationExpr
                                || parent instanceof MethodDeclaration m && m.isStatic()
                                || parent instanceof InitializerDeclaration i && i.isStatic()
                                || parent instanceof FieldDeclaration f && f.isStatic())return Optional.empty();
                    }
                }
                method=call.getNameAsString();arguments=call.getArguments();
            } else return Optional.empty();
            boolean generatedConstructor=constructor && generated.stream().anyMatch(g -> generatedOwners.get(g.callable())==owner
                    && g.callable().kind()==EntityKind.CONSTRUCTOR);
            affectedGenerated=generatedConstructor || !constructor && generated.stream().anyMatch(g ->
                    generatedOwners.get(g.callable())==owner && method.equals(generatedMethodNames.get(g.callable())));
            if(!owner.getTypeParameters().isEmpty() || !owner.getExtendedTypes().isEmpty()) {
                if(affectedGenerated)throw new GeneratedAccessFailure();
                return Optional.empty();
            }
            var actual=arguments.stream().map(a -> a.calculateResolvedType().describe()).toList();
            var matches=new ArrayList<Entity>();
            for(var candidate:generated) {
                if(generatedOwners.get(candidate.callable())!=owner)continue;
                if(constructor!=(candidate.callable().kind()==EntityKind.CONSTRUCTOR))continue;
                if(!constructor && !candidate.name().equals(JavaSymbolName.method(context.name(owner),method,
                        candidate.setter()?candidate.fields().stream().map(f -> context.erasedParameter(f.getType())).toList():List.of())))continue;
                var formal=(constructor||candidate.setter()?candidate.fields():List.<VariableDeclarator>of()).stream()
                        .map(f -> f.getType().resolve().describe()).toList();
                if(!formal.equals(actual))continue;
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
    private static final class GeneratedAccessFailure extends com.github.javaparser.resolution.UnsolvedSymbolException {
        GeneratedAccessFailure() {super("Generated member applicability or accessibility requires evidence");}
    }
    private boolean nonNull(FieldDeclaration field){return field.getAnnotations().stream().anyMatch(a->exact(a,"lombok.NonNull"));}
    private boolean nonFinal(FieldDeclaration field){return field.getAnnotations().stream().anyMatch(a->exact(a,"lombok.experimental.NonFinal"));}
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
