package com.evolution.analysis.javaparser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.logic.FunctionalInterfaceLogic;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.*;

/** Avoid provider constraint/LUB failures by materializing only receiver-proven
 * lambda inputs. No return-body inference, overload choice, or wildcard capture
 * is supplied here. Original source bytes and observation spans stay authoritative. */
final class LambdaParameterInference {
    static final DataKey<Boolean> IMPLICIT=new DataKey<>() {};
    static final DataKey<Boolean> INPUT=new DataKey<>() {};
    private LambdaParameterInference() {}
    static void prepare(CompilationUnit unit) {
        for(var lambda:unit.findAll(LambdaExpr.class)) {
            if(lambda.getParameters().isEmpty()||lambda.getParameters().stream().anyMatch(p->!p.getType().isUnknownType()&&!p.getType().isVarType()))continue;
            try {
                Node argument=lambda;while(argument.getParentNode().orElse(null) instanceof EnclosedExpr)argument=argument.getParentNode().orElseThrow();
                if(!(argument.getParentNode().orElse(null) instanceof MethodCallExpr call)||call.getScope().isEmpty())continue;
                int index=call.getArguments().indexOf(argument);if(index<0)continue;
                var expression=call.getScope().orElseThrow();
                var scope=expression.calculateResolvedType();
                if(!concrete(scope)&&expression instanceof MethodCallExpr factory)scope=explicitFactoryReturn(factory);
                if(!scope.isReferenceType()||!concrete(scope))continue;
                var receiver=scope.asReferenceType();var declaration=receiver.getTypeDeclaration().orElseThrow();
                // A unique fixed-arity member is required. Inferring a parameter
                // must never break an overload tie or resolve a raw receiver.
                var candidates=declaration.getAllMethods().stream().map(m->m.getDeclaration())
                        .filter(m->!m.isStatic()&&m.getName().equals(call.getNameAsString())&&m.getNumberOfParams()==call.getArguments().size())
                        .map(m->m.getQualifiedSignature()).distinct().toList();
                if(candidates.size()!=1)continue;
                var method=call.resolve();
                if(method.isStatic()||method.getParam(method.getNumberOfParams()-1).isVariadic()||!candidates.getFirst().equals(method.getQualifiedSignature()))continue;
                var view=receiver.getQualifiedName().equals(method.declaringType().getQualifiedName())?receiver:
                        receiver.getAllAncestors().stream().filter(t->t.getQualifiedName().equals(method.declaringType().getQualifiedName())).findFirst().orElseThrow();
                var target=view.typeParametersMap().replaceAll(method.getParam(index).getType());
                var sam=FunctionalInterfaceLogic.getFunctionalMethod(target).orElseThrow();
                if(!target.isReferenceType()||sam.getNoParams()!=lambda.getParameters().size())continue;
                var types=new ArrayList<Type>();
                for(int i=0;i<sam.getNoParams();i++) {
                    ResolvedType input=target.asReferenceType().typeParametersMap().replaceAll(sam.getParamType(i));
                    // Non-wildcard ground input, from an exact receiver context.
                    if(input.isWildcard()&&input.asWildcard().isBounded())input=input.asWildcard().getBoundedType();
                    if(!concrete(input))throw new IllegalArgumentException("Unavailable ground lambda input");
                    var parameter=lambda.getParameter(i);var written=parameter.getType();
                    var type=StaticJavaParser.parseType(input.describe());
                    var range=written.getRange().orElseGet(()->parameter.getName().getRange().orElseThrow());
                    type.walk(n->n.setRange(range));
                    type.setData(written.isUnknownType()?IMPLICIT:RecordPatternInference.INFERRED,true);types.add(type);
                }
                for(int i=0;i<types.size();i++)lambda.getParameter(i).setType(types.get(i));
                clearTypes(lambda);
                normalizeInputs(lambda);
            }catch(RuntimeException unavailable) {
                if(unavailable instanceof java.util.concurrent.CancellationException)throw unavailable;
                // Keep the original syntax and its typed observation outcome.
            }
        }
        // An outer lambda's rewrite can invalidate nested input caches. Restore
        // proven inputs once, without depending on source traversal order.
        for(var lambda:unit.findAll(LambdaExpr.class))if(lambda.getParameters().stream().anyMatch(p->p.getType().containsData(IMPLICIT)||p.getType().containsData(RecordPatternInference.INFERRED)))normalizeInputs(lambda);
    }
    private static ResolvedType explicitFactoryReturn(MethodCallExpr call) {
        if(call.getTypeArguments().isPresent())throw new IllegalArgumentException("Explicit factory arguments require their own substitution");
        var method=call.resolve();
        if(!method.isStatic()||method.getNumberOfParams()==0||method.getParam(method.getNumberOfParams()-1).isVariadic())throw new IllegalArgumentException("Not a fixed-arity static factory");
        var same=method.declaringType().getAllMethods().stream().map(m->m.getDeclaration())
                .filter(m->m.isStatic()&&m.getName().equals(method.getName())&&m.getNumberOfParams()==call.getArguments().size())
                .map(m->m.getQualifiedSignature()).distinct().count();
        if(same!=1)throw new IllegalArgumentException("Overloaded factory");
        var inferred=new com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap.Builder();
        var values=new HashMap<String,ResolvedType>();
        for(int i=0;i<call.getArguments().size();i++) {
            if(!(call.getArgument(i) instanceof LambdaExpr lambda))continue;
            var target=method.getParam(i).getType();var sam=FunctionalInterfaceLogic.getFunctionalMethod(target).orElseThrow();
            if(!target.isReferenceType()||sam.getNoParams()!=lambda.getParameters().size())continue;
            for(int p=0;p<sam.getNoParams();p++) {
                var written=lambda.getParameter(p).getType();if(written.isUnknownType()||written.isVarType()||written.containsData(IMPLICIT)||written.containsData(RecordPatternInference.INFERRED))continue;
                var input=target.asReferenceType().typeParametersMap().replaceAll(sam.getParamType(p));
                if(input.isWildcard()&&input.asWildcard().isBounded())input=input.asWildcard().getBoundedType();
                if(!input.isTypeVariable())continue;
                var variable=input.asTypeVariable().asTypeParameter();
                if(!variable.declaredOnMethod()||variable.getBounds().stream().anyMatch(b->!b.isExtends()||!b.getType().isReferenceType()||!b.getType().asReferenceType().getQualifiedName().equals("java.lang.Object")))continue;
                var actual=written.resolve();if(!concrete(actual)||actual.isPrimitive())continue; // Generic variables require reference types.
                var prior=values.putIfAbsent(variable.getQualifiedName(),actual);
                if(prior!=null&&!prior.equals(actual))throw new IllegalArgumentException("Conflicting explicit lambda inputs");
                inferred.setValue(variable,actual);
            }
        }
        var result=inferred.build().replaceAll(method.getReturnType());
        if(!concrete(result))throw new IllegalArgumentException("Unavailable factory return");
        return result;
    }
    @SuppressWarnings("unchecked")
    private static void normalizeInputs(LambdaExpr lambda) {
        for(var name:lambda.findAll(NameExpr.class)) {
            try {
                var binding=name.resolve().toAst().orElse(null);
                if(!(binding instanceof com.github.javaparser.ast.body.Parameter parameter)||lambda.getParameters().stream().noneMatch(p->p==parameter))continue;
                var exact=parameter.getType().resolve();if(!concrete(exact))continue;
                name.setData(INPUT,true);
                var cached=name.calculateResolvedType();
                if(!cached.isConstraint()||!cached.asConstraintType().getBound().equals(exact))continue;
                // Pinned provider's public node-data API: both facade type caches
                // hold ResolvedType. Normalize only a verified same-bound wrapper.
                for(var key:new ArrayList<>(name.getDataKeys()))if(key.getClass().getEnclosingClass()==com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.class
                        &&name.getData(key) instanceof ResolvedType)name.setData((DataKey<ResolvedType>)key,exact);
            }catch(RuntimeException unavailable) {if(unavailable instanceof java.util.concurrent.CancellationException)throw unavailable;}
        }
    }
    private static void clearTypes(LambdaExpr lambda) {
        lambda.walk(LambdaParameterInference::clearNodeTypes);
        for(Node parent=lambda.getParentNode().orElse(null);parent!=null;parent=parent.getParentNode().orElse(null))clearNodeTypes(parent);
    }
    private static void clearNodeTypes(Node node) {
        for(var key:new ArrayList<>(node.getDataKeys()))if(key.getClass().getEnclosingClass()==com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.class)node.removeData(key);
    }
    private static boolean concrete(ResolvedType type) {
        if(type.isArray())return concrete(type.asArrayType().getComponentType());
        if(type.isPrimitive())return true;
        if(!type.isReferenceType())return false;
        var view=type.asReferenceType();
        return view.getTypeDeclaration().map(d->d.getTypeParameters().size()==view.typeParametersValues().size()).orElse(false)
                &&view.typeParametersValues().stream().allMatch(LambdaParameterInference::concrete);
    }
}
