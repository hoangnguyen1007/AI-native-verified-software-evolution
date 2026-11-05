package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import java.util.*;

/** Evidence-bound structural synthesis. Never runs annotation processors or changes source ASTs. */
final class LombokExtraction {
    private static final Set<String> GENERATORS=Set.of("RequiredArgsConstructor","AllArgsConstructor","NoArgsConstructor","Data","Value","Builder","Getter","Setter");
    private static final Set<String> ACCESSORS=Set.of("Getter","Setter");
    private final Extraction context;
    private final List<Generated> generated=new ArrayList<>();
    private final Map<Entity,ClassOrInterfaceDeclaration> generatedOwners=new HashMap<>();
    private final Map<Entity,String> generatedMethodNames=new HashMap<>();
    private final Map<Entity,String> generatedAccess=new HashMap<>();
    private record BuilderShape(ClassOrInterfaceDeclaration owner,Entity factory,Entity build,Map<String,Entity> setters,List<VariableDeclarator> fields) {}
    private record BuilderCall(Entity target,BuilderShape builder,boolean built) {}
    private final Map<JavaSymbolName,BuilderShape> builders=new HashMap<>();
    private final Set<ClassOrInterfaceDeclaration> unsupportedConstructorOwners=Collections.newSetFromMap(new IdentityHashMap<>());
    private record Generated(Entity callable,JavaSymbolName name,List<VariableDeclarator> fields,List<Entity> parameters,String rule,boolean setter){}
    LombokExtraction(Extraction context){this.context=context;}

    void releaseBody(CompilationUnit unit){
        generated.removeIf(g->g.fields().stream().anyMatch(f->f.findCompilationUnit().filter(c->c==unit).isPresent()));
        generatedOwners.entrySet().removeIf(e->e.getValue().findCompilationUnit().filter(c->c==unit).isPresent());
        unsupportedConstructorOwners.removeIf(t->t.findCompilationUnit().filter(c->c==unit).isPresent());
        builders.values().removeIf(b->b.owner().findCompilationUnit().filter(c->c==unit).isPresent());
    }

    void unsupportedGenerators(CompilationUnit unit) {
        var otherGenerators=Set.of("Getter","Setter","With","WithBy","ToString","EqualsAndHashCode","SuperBuilder","UtilityClass","FieldDefaults","Accessors");
        for(var annotation:unit.findAll(AnnotationExpr.class)) {
            String simple=annotation.getName().getIdentifier();var parent=annotation.getParentNode().orElse(null);
            var nativeOwner=annotation.findAncestor(TypeDeclaration.class);if(nativeOwner.isPresent()&&context.nativeOwner(nativeOwner.orElseThrow()))continue;
            if(GENERATORS.contains(simple)&&parent instanceof ClassOrInterfaceDeclaration type&&!type.isInterface())continue;
            if(ACCESSORS.contains(simple)&&parent instanceof FieldDeclaration)continue;
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
        Map<String,AnnotationExpr> annotations=new TreeMap<>();boolean potential=false,constructorPotential=false;
        for(var annotation:type.getAnnotations()) {
            String simple=annotation.getName().getIdentifier();if(!GENERATORS.contains(simple))continue;
            try {
                var entity=context.typeEntity(annotation.resolve());
                if(entity.origin()==EntityOrigin.PROJECT)continue;
                if(context.isLombok(entity,"lombok."+simple)){annotations.put(simple,annotation);potential=true;constructorPotential|=!ACCESSORS.contains(simple);}
                else if(annotation.getNameAsString().startsWith("lombok.")||entity.canonicalName().contains("lombok")){potential=true;constructorPotential|=!ACCESSORS.contains(simple);context.synthesisGap(type,"lombok.artifact-unverified");}
            }catch(RuntimeException failure){potential=true;constructorPotential|=!ACCESSORS.contains(simple);context.synthesisGap(type,"lombok.annotation-unresolved");}
        }
        // Field-only accessors do not own or suppress Java's implicit constructor.
        for(var field:type.getFields())for(var annotation:field.getAnnotations())if(ACCESSORS.contains(annotation.getName().getIdentifier()))try {
            var entity=context.typeEntity(annotation.resolve());if(entity.origin()==EntityOrigin.PROJECT)continue;
            if(context.isLombok(entity,"lombok."+annotation.getName().getIdentifier()))potential=true;
            else if(entity.canonicalName().contains("lombok")){potential=true;context.synthesisGap(annotation,"lombok.artifact-unverified");}
        }catch(RuntimeException failure){potential=true;context.synthesisGap(annotation,"lombok.annotation-unresolved");}
        if(!potential)return false;
        if(context.hasLombokConfiguration(type)){context.synthesisGap(type,"lombok.configuration-required");return constructorPotential;}
        if(constructorPotential&&type.isNestedType()&&!type.isStatic()){context.synthesisGap(type,"lombok.inner-class-constructor");return true;}
        if(type.findAll(AnnotationExpr.class).stream().anyMatch(a->Set.of("Accessors","Tolerate","FieldDefaults","Singular","Default").contains(a.getName().getIdentifier()))) {
            context.synthesisGap(type,"lombok.member-customization");return constructorPotential;
        }
        var fields=type.getFields().stream().filter(f->!f.isStatic()).flatMap(f->f.getVariables().stream()).filter(f->!f.getNameAsString().startsWith("$")).toList();
        if(constructorPotential&&fields.stream().anyMatch(f->field(f).getAnnotations().stream().anyMatch(a->Set.of("NonNull","Nonnull","NotNull","NonNullDecl").contains(a.getName().getIdentifier())&&!exact(a,"lombok.NonNull")))) {
            context.synthesisGap(type,"lombok.nullness-annotation-unverified");return true;
        }
        boolean value=annotations.containsKey("Value");
        var required=fields.stream().filter(f->f.getInitializer().isEmpty()&&(field(f).isFinal()||nonNull(field(f)))).toList();
        var all=fields.stream().filter(f->f.getInitializer().isEmpty()||!(field(f).isFinal()||value&&!nonFinal(field(f)))).toList();
        boolean explicitAnnotation=annotations.keySet().stream().anyMatch(n->n.endsWith("ArgsConstructor"));
        for(var entry:annotations.entrySet())try {
            String name=entry.getKey();var annotation=entry.getValue();
            if(ACCESSORS.contains(name))continue;
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
        if(annotations.containsKey("Data")||value)objectMembers(type,value);
        for(var f:fields)try {
            boolean bool=f.getType().isPrimitiveType()&&f.getType().asPrimitiveType().getType()==com.github.javaparser.ast.type.PrimitiveType.Primitive.BOOLEAN;
            String name=f.getNameAsString();String capital=capitalize(name);
            String getter=bool&&name.matches("is[A-Z].*")?name:(bool?"is":"get")+capital;
            var alternatives=new HashSet<String>();alternatives.add(getter);
            if(bool){String base=name.matches("is[A-Z].*")?name.substring(2):capital;alternatives.add("get"+base);alternatives.add("is"+capital);alternatives.add("get"+capital);}
            String getterAccess=accessorAccess(field(f),annotations.get("Getter"),"Getter",annotations.containsKey("Data")||value);
            if(!getterAccess.equals("NONE")&&!suppressed(type,alternatives,0)) {
                member(type,getter,List.of(),List.of(f),"lombok.getter",false);generatedAccess.put(context.existing(JavaSymbolName.method(context.name(type),getter,List.of())),getterAccess);
            }
            String setterAccess=accessorAccess(field(f),annotations.get("Setter"),"Setter",annotations.containsKey("Data")&&!value);
            if(!setterAccess.equals("NONE")&&!field(f).isFinal()&&!value) {
                String setter="set"+(bool&&name.matches("is[A-Z].*")?name.substring(2):capital);
                if(!suppressed(type,Set.of(setter),1)) {
                    member(type,setter,List.of(f),List.of(f),"lombok.setter",true);
                    generatedAccess.put(context.existing(JavaSymbolName.method(context.name(type),setter,List.of(context.erasedParameter(f.getType())))),setterAccess);
                }
            }
        }catch(RuntimeException failure){context.synthesisGap(f,"lombok.member-customization");}
        for(var f:type.getFields())if(f.isStatic()&&f.getAnnotations().stream().anyMatch(a->ACCESSORS.contains(a.getName().getIdentifier())&&exact(a,"lombok."+a.getName().getIdentifier())))
            context.synthesisGap(f,"lombok.static-accessor-required");
        return constructorPotential;
    }
    private String accessorAccess(FieldDeclaration field,AnnotationExpr typeAnnotation,String name,boolean implicit) {
        var annotation=field.getAnnotations().stream().filter(a->exact(a,"lombok."+name)).findFirst().orElse(typeAnnotation);
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
        var resolved=level instanceof FieldAccessExpr access?access.resolve():level instanceof NameExpr simple?simple.resolve():null;
        if(resolved==null||!resolved.isField()||!context.isLombok(context.typeEntity(resolved.asField().declaringType().asReferenceType()),"lombok.AccessLevel"))throw new IllegalArgumentException();
        String value=resolved.getName();
        if(!Set.of("PUBLIC","PROTECTED","PACKAGE","PRIVATE","NONE").contains(value))throw new IllegalArgumentException();return value;
    }
    private static String capitalize(String name) {
        if(name.length()>1&&Character.isLowerCase(name.charAt(0))&&(Character.isUpperCase(name.charAt(1))||Character.isTitleCase(name.charAt(1))))return Character.toUpperCase(name.charAt(0))+name.substring(1);
        return Character.toTitleCase(name.charAt(0))+name.substring(1);
    }
    private static boolean suppressed(ClassOrInterfaceDeclaration type,Set<String> names,int arity) {
        return type.getMethods().stream().anyMatch(m->names.stream().anyMatch(n->n.equalsIgnoreCase(m.getNameAsString()))
                &&(m.getParameters().size()==arity||!m.getParameters().isEmpty()&&m.getParameter(m.getParameters().size()-1).isVarArgs()&&arity>=m.getParameters().size()-1));
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
            generated.add(new Generated(setter,name,List.of(f),List.of(parameter),"lombok.builder-setter",true));
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
    private boolean sameOwner(Entity callable,ClassOrInterfaceDeclaration owner) {
        var recorded=generatedOwners.get(callable);
        return recorded!=null&&(recorded==owner||context.name(recorded).equals(context.name(owner)));
    }
    /** Exact-argument generated overloads participate before native resolution can select a
     * wider handwritten overload. No AST mutation, processor execution, or guessed coercion. */
    Optional<Entity> resolve(Expression expression) {
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
                        var resolved=scope.calculateResolvedType();
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
            if(!owner.getTypeParameters().isEmpty()&&(constructor||!arguments.isEmpty())||constructor&&!owner.getExtendedTypes().isEmpty()) {
                if(affectedGenerated)throw new GeneratedAccessFailure();
                return Optional.empty();
            }
            var actualTypes=arguments.stream().map(Expression::calculateResolvedType).toList();
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
        if(!field.getType().resolve().isAssignableBy(call.getArgument(0).calculateResolvedType()))throw new GeneratedAccessFailure();
        return Optional.of(new BuilderCall(setter,shape,false));
    }
    private void builderAccess(ClassOrInterfaceDeclaration owner,Expression site) {
        boolean samePackage=owner.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p->p.getNameAsString())
                .equals(site.findCompilationUnit().orElseThrow().getPackageDeclaration().map(p->p.getNameAsString()));
        if(!owner.isPublic()&&!samePackage)throw new GeneratedAccessFailure();
        for(Node enclosing=owner;enclosing!=null;enclosing=enclosing.getParentNode().orElse(null))
            if(enclosing instanceof ClassOrInterfaceDeclaration type&&(type.isPrivate()||type.isProtected()))throw new GeneratedAccessFailure();
        boolean customized=owner.getAnnotations().stream().filter(a->exact(a,"lombok.Builder")).filter(NormalAnnotationExpr.class::isInstance)
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
                if(!expectedReturn.isVoid()&&(candidate.setter()||!expectedReturn.isAssignableBy(typeMap.replaceAll(candidate.fields().getFirst().getType().resolve()))))continue;
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
