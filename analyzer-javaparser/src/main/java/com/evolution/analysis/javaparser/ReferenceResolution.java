package com.evolution.analysis.javaparser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.resolution.*;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.logic.*;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import java.util.*;

/** Contextual functional signature is required even when the parser returns a method binding. */
final class ReferenceResolution {
    private final TypeSolver solver;
    ReferenceResolution(TypeSolver solver) { this.solver = solver; }

    ResolvedMethodLikeDeclaration resolve(MethodReferenceExpr reference) {
        if (reference.getScope() instanceof TypeExpr type && type.getType().isArrayType())
            throw new UnsupportedOperationException("Array constructor/reference has no declared callable");
        var functional = targetType(reference);
        var sam = FunctionalInterfaceLogic.getFunctionalMethod(functional).orElseThrow(() -> new UnsolvedSymbolException("functional target"));
        var substitutions = functional.asReferenceType().typeParametersMap();
        var args = sam.getParamTypes().stream().map(substitutions::replaceAll).toList();
        var returnType = substitutions.replaceAll(sam.returnType());
        if (reference.getIdentifier().equals("new")) {
            if (!(reference.getScope() instanceof TypeExpr type)) throw new UnsupportedOperationException("Constructor reference scope");
            var constructed = type.getType().resolve();
            var declaration = constructed.asReferenceType().getTypeDeclaration().orElseThrow();
            var selected = ConstructorResolutionLogic.findMostApplicable(declaration.getConstructors(),args,solver);
            if (!selected.isSolved() || !returnType.isVoid() && !returnType.isAssignableBy(constructed)) throw new UnsolvedSymbolException("constructor reference");
            checkThrows(selected.getCorrespondingDeclaration(),sam);
            return selected.getCorrespondingDeclaration();
        }
        var selected = select(reference,args);
        var receiverValue=value(reference,reference.getScope().toString());
        if (selected.isStatic() && (receiverValue.isPresent() || !(reference.getScope() instanceof TypeExpr)))
            throw new UnsolvedSymbolException("static reference through a value");
        boolean unbound = !selected.isStatic() && reference.getScope() instanceof TypeExpr
                && receiverValue.isEmpty();
        int offset = unbound ? 1 : 0;
        boolean variadic = selected.getNumberOfParams() > 0 && selected.getParam(selected.getNumberOfParams()-1).isVariadic();
        int arity = args.size()-offset;
        if (arity < 0 || (variadic ? arity < selected.getNumberOfParams()-1 : arity != selected.getNumberOfParams()))
            throw new UnsolvedSymbolException("incompatible functional arity");
        ResolvedType receiver = null;
        if (!selected.isStatic()) {
            if (unbound) receiver=args.getFirst();
            else if (receiverValue.isPresent()) receiver=receiverValue.orElseThrow().getType();
            else try { receiver=reference.getScope().calculateResolvedType(); } catch (RuntimeException ignored) { }
        }
        var receiverMap=com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap.empty();
        if (receiver!=null && receiver.isReferenceType()) {
            var view=receiver.asReferenceType();
            if (!view.getQualifiedName().equals(selected.declaringType().getQualifiedName()))
                view=view.getAllAncestors().stream().filter(t -> t.getQualifiedName().equals(selected.declaringType().getQualifiedName())).findFirst().orElse(view);
            receiverMap=view.typeParametersMap();
        }
        if (unbound && !reference.getScope().asTypeExpr().getType().resolve().isAssignableBy(args.getFirst()))
            throw new UnsolvedSymbolException("incompatible reference receiver");
        var inferred=new com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap.Builder();
        var inferredValues=new HashMap<String,ResolvedType>();
        for (int i=0; i<arity; i++) {
            var parameter = receiverMap.replaceAll(selected.getParam(Math.min(i,selected.getNumberOfParams()-1)).getType());
            if (variadic && i >= selected.getNumberOfParams()-1 && !parameter.isAssignableBy(args.get(i+offset))) parameter = parameter.asArrayType().getComponentType();
            if (parameter.isTypeVariable()) {
                var variable=parameter.asTypeVariable().asTypeParameter(); var actual=args.get(i+offset);
                var previous=inferredValues.putIfAbsent(variable.getQualifiedName(),actual);
                if (previous!=null && !previous.describe().equals(actual.describe())) throw new UnsupportedOperationException("Joint generic reference inference");
                for (var bound:variable.getBounds()) if (bound.isExtends() && !bound.getType().isAssignableBy(actual)) throw new UnsolvedSymbolException("generic bound");
                inferred.setValue(variable,actual);
            } else if (!parameter.isAssignableBy(args.get(i+offset))) throw new UnsolvedSymbolException("incompatible functional argument");
        }
        var selectedReturn=inferred.build().replaceAll(receiverMap.replaceAll(selected.getReturnType()));
        if (!returnType.isVoid() && selectedReturn.isTypeVariable()) throw new UnsupportedOperationException("Uninferred generic reference return");
        if (!returnType.isVoid() && !returnType.isAssignableBy(selectedReturn)) throw new UnsolvedSymbolException("incompatible functional return");
        checkThrows(selected,sam);
        return selected;
    }
    private ResolvedMethodDeclaration select(MethodReferenceExpr reference,List<ResolvedType> args) {
        try { return reference.resolve(); }
        catch (UnsolvedSymbolException failure) {
            if (!(reference.getScope() instanceof TypeExpr type) || value(reference,type.toString()).isPresent() || args.isEmpty()) throw failure;
            var scope=type.getType().resolve();
            if (!scope.isReferenceType() || !scope.isAssignableBy(args.getFirst())) throw failure;
            var methods=scope.asReferenceType().getTypeDeclaration().orElseThrow().getAllMethods().stream().map(MethodUsage::getDeclaration).toList();
            var instance=MethodResolutionLogic.findMostApplicable(methods.stream().filter(m -> !m.isStatic()).toList(),reference.getIdentifier(),args.subList(1,args.size()),solver);
            var staticMatch=MethodResolutionLogic.findMostApplicable(methods.stream().filter(ResolvedMethodDeclaration::isStatic).toList(),reference.getIdentifier(),args,solver);
            if (!instance.isSolved() || staticMatch.isSolved()) throw failure;
            return instance.getCorrespondingDeclaration();
        }
    }
    private void checkThrows(ResolvedMethodLikeDeclaration selected, MethodUsage sam) {
        var runtime=solver.solveType("java.lang.RuntimeException"); var error=solver.solveType("java.lang.Error");
        for (var thrown:selected.getSpecifiedExceptions()) {
            if (runtime.isAssignableBy(thrown) || error.isAssignableBy(thrown)) continue;
            if (sam.exceptionTypes().stream().noneMatch(allowed -> allowed.isAssignableBy(thrown)))
                throw new UnsolvedSymbolException("incompatible checked exception");
        }
    }
    ResolvedType targetType(Expression expression) {
        Node parent = expression.getParentNode().orElseThrow();
        if (parent instanceof EnclosedExpr enclosed) return targetType(enclosed);
        if (parent instanceof ConditionalExpr conditional) return targetType(conditional);
        if (parent instanceof VariableDeclarator variable) return variable.getType().resolve();
        if (parent instanceof AssignExpr assignment) return assignment.getTarget().calculateResolvedType();
        if (parent instanceof CastExpr cast) return cast.getType().resolve();
        if (parent instanceof ReturnStmt) {
            for (Node scope=parent; scope!=null; scope=scope.getParentNode().orElse(null)) {
                if (scope instanceof LambdaExpr lambda) return functionalReturn(targetType(lambda));
                if (scope instanceof MethodDeclaration method) return method.getType().resolve();
            }
        }
        if (parent instanceof LambdaExpr lambda) return functionalReturn(targetType(lambda));
        if (parent instanceof MethodCallExpr call) {
            var method = JavaParserFacade.get(solver).solveMethodAsUsage(call);
            int index = call.getArguments().indexOf(expression);
            var type = method.getParamType(Math.min(index,method.getNoParams()-1));
            return method.getDeclaration().getParam(method.getNoParams()-1).isVariadic() && index >= method.getNoParams()-1 ? type.asArrayType().getComponentType() : type;
        }
        throw new UnsupportedOperationException("Functional target context");
    }
    private static ResolvedType functionalReturn(ResolvedType target) {
        return target.asReferenceType().typeParametersMap().replaceAll(FunctionalInterfaceLogic.getFunctionalMethod(target).orElseThrow().returnType());
    }
    static Optional<ResolvedValueDeclaration> value(MethodReferenceExpr reference, String name) {
        // The grammar represents an ambiguous receiver such as field::method as TypeExpr.
        // Reparse only for binding; all emitted evidence uses the original node's span.
        Expression receiver = null;
        try {
            receiver = StaticJavaParser.parseExpression(name);
            receiver.setParentNode(reference);
            if (receiver instanceof NameExpr simple) return Optional.of(simple.resolve());
            if (receiver instanceof FieldAccessExpr field) return Optional.of(field.resolve());
        } catch (RuntimeException failure) { /* A type qualifier has no value binding. */ }
        finally { if (receiver != null) receiver.setParentNode(null); }
        return Optional.empty();
    }
}
