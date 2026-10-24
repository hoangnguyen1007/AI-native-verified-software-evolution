package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.javaevidence.JavaSourceCorrespondence;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.logic.*;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import java.io.IOException;
import java.util.*;

/** Reads native members through the non-loading solver. It never inserts synthetic source ASTs. */
final class NativeMemberExtraction {
    private final Extraction context;
    private final FrontendRequest request;
    private final ResolutionEnvironment environment;
    private final Map<String,Owner> owners=new TreeMap<>();
    private final Map<String,Entity> entities=new HashMap<>();
    private record Owner(ContentDigest slot,ResolvedReferenceTypeDeclaration type,Map<String,JavaSourceCorrespondence.Mapping> members){}
    private record Receiver(Owner owner,boolean typeReceiver){}
    private record Selected(ResolvedMethodLikeDeclaration declaration,Entity entity){}

    NativeMemberExtraction(Extraction context,FrontendRequest request,ResolutionEnvironment environment){this.context=context;this.request=request;this.environment=environment;}
    void declarations(){
        if(request.correspondence().isEmpty())return;
        var binding=request.correspondence().orElseThrow();
        try{if(!binding.current())reject("revoked");}catch(IOException unavailable){reject("revoked");}
        for(var mapping:binding.mappings()){
            context.sourceDeclaration(mapping.source());var endpoint=mapping.endpoint();String name=endpoint.ownerName().canonicalName();
            var owner=owners.get(name);
            if(owner==null){
                String qualified=(endpoint.packageName().isEmpty()?"":endpoint.packageName()+".")+String.join(".",endpoint.owners());
                var type=environment.nativeType(endpoint.slot(),qualified);
                if(!context.typeName(type).equals(endpoint.ownerName()))reject("owner-mismatch");
                // Descriptor erasure cannot establish richer parameterized source types.
                if(!type.getTypeParameters().isEmpty())reject("generic-attribution-required");
                owner=new Owner(endpoint.slot(),type,new TreeMap<>());owners.put(name,owner);
            }
            if(!owner.slot().equals(endpoint.slot()))reject("duplicate-owner");
            if(owner.members().putIfAbsent(key(endpoint.kind(),endpoint.name(),endpoint.descriptor()),mapping)!=null)reject("duplicate-member");
        }
        var classCensus=new HashMap<ContentDigest,Set<String>>();
        for(var owner:owners.values()){
            var type=owner.type();
            classCensus.computeIfAbsent(owner.slot(),unused->new HashSet<>()).add(type.getQualifiedName());
            var expected=new HashSet<String>();expected.add(key(EntityKind.TYPE,type.getName(),""));
            for(var constructor:type.getConstructors())expected.add(key(EntityKind.CONSTRUCTOR,"<init>",descriptor(constructor)));
            for(var method:type.getDeclaredMethods())expected.add(key(EntityKind.METHOD,method.getName(),descriptor(method)));
            for(var field:type.getDeclaredFields())expected.add(key(EntityKind.FIELD,field.getName(),typeDescriptor(field.getType())));
            if(!owner.members().keySet().equals(expected))reject("census-incomplete");
            var sourceMembers=context.sourceMembers(context.typeName(type));
            var mappedSource=owner.members().values().stream().filter(m->m.record().text("outcome").equals("SAME_DECLARATION")).map(JavaSourceCorrespondence.Mapping::source).toList();
            if(sourceMembers.stream().anyMatch(member->!mappedSource.contains(member)))reject("handwritten-member-missing");
            var typeMapping=owner.members().get(key(EntityKind.TYPE,type.getName(),""));register(typeMapping,context.typeName(type),type);
            for(var constructor:type.getConstructors())register(owner.members().get(key(EntityKind.CONSTRUCTOR,"<init>",descriptor(constructor))),context.callableName(constructor),constructor);
            for(var method:type.getDeclaredMethods())register(owner.members().get(key(EntityKind.METHOD,method.getName(),descriptor(method))),context.callableName(method),method);
            for(var field:type.getDeclaredFields())register(owner.members().get(key(EntityKind.FIELD,field.getName(),typeDescriptor(field.getType()))),JavaSymbolName.field(context.typeName(type),field.getName()),field);
        }
        for(var entry:classCensus.entrySet())if(!entry.getValue().equals(environment.nativeClasses(entry.getKey())))reject("class-census-incomplete");
    }
    private void register(JavaSourceCorrespondence.Mapping mapping,JavaSymbolName symbol,ResolvedDeclaration nativeDeclaration){
        Entity entity;
        if(mapping.record().text("outcome").equals("SAME_DECLARATION")){
            if(!mapping.source().canonicalName().equals(symbol.canonicalName())||mapping.source().kind()!=mapping.endpoint().kind())reject("source-signature-mismatch");
            checkSourceSignature(mapping,nativeDeclaration);
            entity=context.sourceDeclaration(mapping.source());
        }else{
            if(mapping.endpoint().kind()!=EntityKind.TYPE&&!mapping.source().canonicalName().equals(mapping.endpoint().ownerName().canonicalName())){
                // Native nested members may belong to a generated owner within the source owner.
                boolean related=false;var endpoint=mapping.endpoint();var prefix=JavaSymbolName.topLevelType(endpoint.packageName(),endpoint.owners().getFirst());for(int index=1;index<endpoint.owners().size();index++){if(prefix.canonicalName().equals(mapping.source().canonicalName()))related=true;prefix=JavaSymbolName.memberType(prefix,endpoint.owners().get(index));}if(!related)reject("unrelated-owner");
            }
            entity=context.nativeDeclaration(symbol,mapping.endpoint().kind(),new Extraction.JavaEvidenceMapping(mapping.record(),mapping.source(),mapping.endpoint().name()));
        }
        entities.put(entityKey(mapping.endpoint().ownerName(),mapping.endpoint().kind(),mapping.endpoint().name(),mapping.endpoint().descriptor()),entity);
    }
    private void checkSourceSignature(JavaSourceCorrespondence.Mapping mapping,ResolvedDeclaration nativeDeclaration){
        var node=context.sourceDeclarationNode(mapping.source());
        try{
            if(node instanceof TypeDeclaration<?> type&&nativeDeclaration instanceof ResolvedReferenceTypeDeclaration binary){var source=type.resolve();if(source.isClass()!=binary.isClass()||source.isInterface()!=binary.isInterface()||source.isEnum()!=binary.isEnum())reject("source-signature-mismatch");return;}
            if(node instanceof MethodDeclaration method&&nativeDeclaration instanceof ResolvedMethodDeclaration binary){
                var source=method.resolve();if(!source.getTypeParameters().isEmpty()||source.formalParameterTypes().stream().anyMatch(NativeMemberExtraction::generic)||generic(source.getReturnType()))reject("generic-attribution-required");
                if(source.isStatic()!=binary.isStatic()||!descriptor(source).equals(mapping.endpoint().descriptor()))reject("source-signature-mismatch");return;
            }
            if(node instanceof ConstructorDeclaration constructor&&nativeDeclaration instanceof ResolvedConstructorDeclaration){var source=constructor.resolve();if(!source.getTypeParameters().isEmpty()||source.formalParameterTypes().stream().anyMatch(NativeMemberExtraction::generic))reject("generic-attribution-required");if(!descriptor(source).equals(mapping.endpoint().descriptor()))reject("source-signature-mismatch");return;}
            if(node instanceof VariableDeclarator variable&&nativeDeclaration instanceof ResolvedFieldDeclaration binary){var source=variable.resolve().asField();if(generic(source.getType()))reject("generic-attribution-required");if(source.isStatic()!=binary.isStatic()||!typeDescriptor(source.getType()).equals(mapping.endpoint().descriptor()))reject("source-signature-mismatch");return;}
        }catch(UnsolvedSymbolException|UnsupportedOperationException unavailable){reject("source-signature-unavailable");}
        reject("source-signature-unavailable");
    }
    private static boolean generic(ResolvedType type){if(type.isArray())return generic(type.asArrayType().getComponentType());return type.isTypeVariable()||type.isWildcard()||type.isReferenceType()&&!type.asReferenceType().typeParametersValues().isEmpty();}
    boolean owns(JavaSymbolName name){return owners.containsKey(name.canonicalName());}
    Optional<Entity> callable(ResolvedMethodLikeDeclaration declaration){
        if(declaration.toAst().isPresent())return Optional.empty();
        var name=context.typeName(declaration.declaringType());var kind=declaration instanceof ResolvedConstructorDeclaration?EntityKind.CONSTRUCTOR:EntityKind.METHOD;
        var owner=owners.get(name.canonicalName());if(owner==null||environment.selectedNativeSlot(declaration.declaringType().getQualifiedName()).filter(owner.slot()::equals).isEmpty()&&!environment.privateMemberSlot(owner.slot(),declaration.declaringType().getQualifiedName()))return Optional.empty();
        return Optional.ofNullable(entities.get(entityKey(name,kind,kind==EntityKind.CONSTRUCTOR?"<init>":declaration.getName(),descriptor(declaration))));
    }
    Optional<Entity> resolve(Expression expression){
        if(expression instanceof ObjectCreationExpr creation&&creation.getAnonymousClassBody().isPresent()
                &&creation.getScope().isEmpty()&&creation.getArguments().isEmpty()&&creation.getTypeArguments().isEmpty()){
            var resolved=creation.getType().resolve();
            if(resolved.isReferenceType()&&(resolved.asReferenceType().getTypeDeclaration().orElseThrow().isInterface()
                    ||creation.resolve().getNumberOfParams()==0)){
                // JLS 15.9.5.1: retain the actual anonymous constructor when its
                // selected superconstructor has no source parameters. Captured/
                // enclosing JVM parameters are not M1 keys.
                // The constructor belongs to the actual anonymous source owner.
                return Optional.of(context.implicit(JavaSymbolName.constructor(context.name(creation),List.of()),
                        EntityKind.CONSTRUCTOR,creation,"java.anonymous-zero-argument-constructor"));
            }
        }
        return select(expression).map(Selected::entity);
    }
    private Optional<Selected> select(Expression expression){
        if(owners.isEmpty())return Optional.empty();
        if(expression instanceof ObjectCreationExpr creation){
            if(creation.getAnonymousClassBody().isPresent())return Optional.empty();
            var reference=creation.getType().resolve();if(!reference.isReferenceType())return Optional.empty();var owner=owners.get(context.typeName(reference.asReferenceType().getTypeDeclaration().orElseThrow()).canonicalName());if(owner==null)return Optional.empty();
            if(creation.getType().getTypeArguments().isPresent())throw new UnsupportedOperationException("Native generic constructor attribution requires compiler evidence");
            var constructors=owner.type().getConstructors().stream().filter(c->accessible(c.accessSpecifier(),owner,creation)).toList();
            var selected=ConstructorResolutionLogic.findMostApplicable(constructors,arguments(creation.getArguments()),environment.solver);
            if(!selected.isSolved())throw new UnsolvedSymbolException("native constructor");return Optional.of(selected(selected.getCorrespondingDeclaration(),owner));
        }
        if(!(expression instanceof MethodCallExpr call))return Optional.empty();
        Optional<Receiver> receiver;
        if(call.getScope().isPresent())receiver=receiver(call.getScope().orElseThrow());
        else receiver=call.findAncestor(TypeDeclaration.class).map(type->owners.get(context.name(type).canonicalName())).map(owner->new Receiver(owner,staticContext(call)));
        if(receiver.isEmpty())return Optional.empty();var value=receiver.orElseThrow();var owner=value.owner();
        var named=owner.type().getDeclaredMethods().stream().filter(m->m.getName().equals(call.getNameAsString())).toList();
        if(named.isEmpty())return Optional.empty();
        if(named.stream().anyMatch(m->!m.getTypeParameters().isEmpty())||call.getTypeArguments().isPresent())throw new UnsupportedOperationException("Native generic method attribution requires compiler evidence");
        var methods=named.stream().filter(m->(!value.typeReceiver()||m.isStatic())&&accessible(m.accessSpecifier(),owner,call)).toList();
        var selected=MethodResolutionLogic.findMostApplicable(methods,call.getNameAsString(),arguments(call.getArguments()),environment.solver);
        if(!selected.isSolved())throw new UnsolvedSymbolException("native method");return Optional.of(selected(selected.getCorrespondingDeclaration(),owner));
    }
    private Selected selected(ResolvedMethodLikeDeclaration declaration,Owner owner){var type=context.typeName(declaration.declaringType());if(!type.equals(context.typeName(owner.type())))throw new UnsolvedSymbolException("unmapped native owner");var kind=declaration instanceof ResolvedConstructorDeclaration?EntityKind.CONSTRUCTOR:EntityKind.METHOD;var entity=entities.get(entityKey(type,kind,kind==EntityKind.CONSTRUCTOR?"<init>":declaration.getName(),descriptor(declaration)));if(entity==null)throw new UnsolvedSymbolException("unmapped native member");return new Selected(declaration,entity);}
    private Optional<Receiver> receiver(Expression scope){
        if(scope instanceof NameExpr name){try{return valueReceiver(name.resolve().getType());}catch(UnsolvedSymbolException absent){return typeReceiver(scope);}}
        if(scope instanceof FieldAccessExpr field){try{return valueReceiver(field.resolve().getType());}catch(UnsolvedSymbolException absent){return typeReceiver(scope);}}
        return valueReceiver(expressionType(scope));
    }
    private Optional<Receiver> valueReceiver(ResolvedType type){
        if(type.isReferenceType()){var owner=owners.get(context.typeName(type.asReferenceType().getTypeDeclaration().orElseThrow()).canonicalName());if(owner!=null)return Optional.of(new Receiver(owner,false));}return Optional.empty();
    }
    private Optional<Receiver> typeReceiver(Expression scope){
        var solved=JavaParserFactory.getContext(scope,environment.solver).solveType(scope.toString());
        if(solved.isSolved()&&solved.getCorrespondingDeclaration().isReferenceType()){var owner=owners.get(context.typeName(solved.getCorrespondingDeclaration().asReferenceType()).canonicalName());if(owner!=null)return Optional.of(new Receiver(owner,true));}
        return Optional.empty();
    }
    private static boolean staticContext(Expression expression){for(var node=expression.getParentNode().orElse(null);node!=null;node=node.getParentNode().orElse(null)){if(node instanceof MethodDeclaration method)return method.isStatic();if(node instanceof ConstructorDeclaration)return false;if(node instanceof InitializerDeclaration initializer)return initializer.isStatic();if(node instanceof FieldDeclaration field)return field.isStatic();if(node instanceof TypeDeclaration<?>)return false;}return false;}
    private ResolvedType expressionType(Expression expression){
        if(expression instanceof MethodCallExpr){var selected=select(expression);if(selected.isPresent())return ((ResolvedMethodDeclaration)selected.orElseThrow().declaration()).getReturnType();}
        return expression.calculateResolvedType();
    }
    private List<ResolvedType> arguments(List<? extends Expression> arguments){
        var result=new ArrayList<ResolvedType>();for(var argument:arguments){if(argument instanceof LambdaExpr||argument instanceof MethodReferenceExpr)throw new UnsupportedOperationException("Native poly expression attribution requires compiler evidence");result.add(expressionType(argument));}return result;
    }
    private boolean accessible(AccessSpecifier access,Owner owner,Expression site){
        if(access==AccessSpecifier.PUBLIC)return true;
        var unit=site.findCompilationUnit().orElseThrow();String pkg=unit.getPackageDeclaration().map(p->p.getNameAsString()).orElse("");
        var endpoint=owner.members().values().iterator().next().endpoint();if(access!=AccessSpecifier.PRIVATE)return pkg.equals(endpoint.packageName());
        var sourceType=site.findAncestor(TypeDeclaration.class);if(sourceType.isEmpty())return false;
        TypeDeclaration<?> top=sourceType.orElseThrow();while(top.findAncestor(TypeDeclaration.class).isPresent())top=top.findAncestor(TypeDeclaration.class).orElseThrow();
        return context.name(top).equals(JavaSymbolName.topLevelType(endpoint.packageName(),endpoint.owners().getFirst()));
    }
    private String descriptor(ResolvedMethodLikeDeclaration declaration){if(declaration instanceof ResolvedMethodDeclaration method&&method.toAst().isEmpty())return method.toDescriptor();var result=new StringBuilder("(");for(int i=0;i<declaration.getNumberOfParams();i++)result.append(typeDescriptor(declaration.getParam(i).getType()));return result.append(')').append(declaration instanceof ResolvedMethodDeclaration method?typeDescriptor(method.getReturnType()):"V").toString();}
    private String typeDescriptor(ResolvedType value){var type=value.erasure();if(type.isArray())return "["+typeDescriptor(type.asArrayType().getComponentType());if(type.isPrimitive()||type.isVoid())return type.toDescriptor();if(!type.isReferenceType())throw new UnsupportedOperationException("Native descriptor requires a concrete erased type");var name=JavaTypeName.fromCanonical(context.typeName(type.asReferenceType().getTypeDeclaration().orElseThrow()).canonicalName()).orElseThrow();return "L"+(name.packageName().isEmpty()?"":name.packageName().replace('.','/')+"/")+name.binarySimpleName()+";";}
    private static String key(EntityKind kind,String name,String descriptor){return kind+"/"+name+"/"+descriptor;}
    private static String entityKey(JavaSymbolName owner,EntityKind kind,String name,String descriptor){return owner.canonicalName()+"/"+key(kind,name,descriptor);}
    private static void reject(String code){throw new FrontendInputException("frontend.native-correspondence-"+code,"Captured source/native correspondence cannot establish this member view");}
}
