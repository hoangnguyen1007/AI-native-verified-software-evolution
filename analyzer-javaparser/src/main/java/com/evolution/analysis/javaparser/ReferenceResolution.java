package com.evolution.analysis.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
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
        var functional = groundTarget(targetType(reference));
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
        if(receiver!=null&&receiver.isWildcard()&&!receiver.asWildcard().isUpperBounded()
                &&!selected.declaringType().getQualifiedName().equals("java.lang.Object"))
            throw new UnsolvedSymbolException("lower wildcard receiver member");
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
            var supplied = args.get(i+offset);
            if (parameter.isTypeVariable()) {
                bindMethodTypeVar(parameter.asTypeVariable().asTypeParameter(), supplied, inferred, inferredValues);
            } else if (containsMethodTypeVar(parameter)) {
                unifyMethodTypeVars(parameter, supplied, inferred, inferredValues);
            } else if (!parameter.isAssignableBy(supplied)) {
                throw new UnsolvedSymbolException("incompatible functional argument");
            }
        }
        var selectedReturn=inferred.build().replaceAll(receiverMap.replaceAll(selected.getReturnType()));
        if (!returnType.isVoid() && containsMethodTypeVar(selectedReturn)) {
            var retInferred = new com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap.Builder();
            var retValues = new HashMap<>(inferredValues);
            if (unifyMethodTypeVars(selectedReturn, returnType, retInferred, retValues)) {
                selectedReturn = retInferred.build().replaceAll(selectedReturn);
            }
        }
        if (!returnType.isVoid() && selectedReturn.isTypeVariable()) throw new UnsupportedOperationException("Uninferred generic reference return");
        if (!returnType.isVoid() && !returnType.isAssignableBy(selectedReturn)) throw new UnsolvedSymbolException("incompatible functional return");
        checkThrows(selected,sam);
        return selected;
    }
    private static void bindMethodTypeVar(
            ResolvedTypeParameterDeclaration variable,
            ResolvedType actual,
            com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap.Builder inferred,
            Map<String, ResolvedType> inferredValues) {
        var previous=inferredValues.putIfAbsent(variable.getQualifiedName(),actual);
        if (previous!=null && !previous.describe().equals(actual.describe())) throw new UnsupportedOperationException("Joint generic reference inference");
        for (var bound:variable.getBounds()) if (bound.isExtends() && !bound.getType().isTypeVariable() && !bound.getType().isAssignableBy(actual)) throw new UnsolvedSymbolException("generic bound");
        inferred.setValue(variable,actual);
    }
    private static boolean containsMethodTypeVar(ResolvedType type) {
        if (type.isTypeVariable()) return type.asTypeVariable().asTypeParameter().declaredOnMethod();
        if (type.isArray()) return containsMethodTypeVar(type.asArrayType().getComponentType());
        if (type.isWildcard()) return type.asWildcard().isBounded() && containsMethodTypeVar(type.asWildcard().getBoundedType());
        if (type.isReferenceType()) {
            for (var arg : type.asReferenceType().typeParametersValues()) {
                if (containsMethodTypeVar(arg)) return true;
            }
        }
        return false;
    }
    private static boolean unifyMethodTypeVars(
            ResolvedType formal,
            ResolvedType actual,
            com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap.Builder inferred,
            Map<String, ResolvedType> inferredValues) {
        if (formal.isWildcard()) {
            if (!formal.asWildcard().isBounded()) return true;
            return unifyMethodTypeVars(formal.asWildcard().getBoundedType(), actual, inferred, inferredValues);
        }
        if (formal.isTypeVariable()) {
            var variable = formal.asTypeVariable().asTypeParameter();
            if (!variable.declaredOnMethod() || actual.isPrimitive() || actual.isWildcard() || actual.isTypeVariable()) {
                return false;
            }
            bindMethodTypeVar(variable, actual, inferred, inferredValues);
            return true;
        }
        if (formal.isArray() && actual.isArray()) {
            return unifyMethodTypeVars(formal.asArrayType().getComponentType(), actual.asArrayType().getComponentType(), inferred, inferredValues);
        }
        if (formal.isReferenceType() && actual.isReferenceType()) {
            var fRef = formal.asReferenceType();
            var aRef = actual.asReferenceType();
            final String fQName = fRef.getQualifiedName();
            final String aQName = aRef.getQualifiedName();
            var matched = aQName.equals(fQName) ? aRef :
                    aRef.getAllAncestors().stream().filter(t -> t.getQualifiedName().equals(fQName)).findFirst().orElse(null);
            if (matched == null) {
                matched = fRef.getAllAncestors().stream().filter(t -> t.getQualifiedName().equals(aQName)).findFirst().orElse(null);
                if (matched == null) return false;
                fRef = matched;
                matched = aRef;
            }
            var fArgs = fRef.typeParametersValues();
            var aArgs = matched.typeParametersValues();
            if (fArgs.size() != aArgs.size()) return false;
            boolean ok = true;
            for (int i = 0; i < fArgs.size(); i++) {
                ResolvedType fa = fArgs.get(i);
                ResolvedType aa = aArgs.get(i);
                if (aa.isWildcard() && aa.asWildcard().isBounded()) aa = aa.asWildcard().getBoundedType();
                if (!unifyMethodTypeVars(fa, aa, inferred, inferredValues)) ok = false;
            }
            return ok;
        }
        return formal.isAssignableBy(actual);
    }
    /** JLS 15.13.2/9.9: references use the non-wildcard ground target.
     * Do not erase a wildcard nested inside a SAM parameter (e.g. List<? super T>). */
    ResolvedType groundTarget(ResolvedType target) {
        if(!target.isReferenceType())throw new UnsolvedSymbolException("functional target");
        var view=target.asReferenceType();var declaration=view.getTypeDeclaration().orElseThrow();
        var values=new ArrayList<ResolvedType>();
        for(var entry:view.getTypeParametersMap()) {
            var value=entry.b;
            if(value.isWildcard()) {
                var wildcard=value.asWildcard();var bounds=entry.a.getBounds();
                if(wildcard.isBounded())value=wildcard.getBoundedType();
                else if(bounds.isEmpty())value=new com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl(solver.solveType("java.lang.Object"));
                else if(bounds.size()==1)value=view.typeParametersMap().replaceAll(bounds.getFirst().getType());
                else throw new UnsupportedOperationException("Intersection ground target bound");
                for(var bound:bounds)if(!bound.getType().isAssignableBy(value))throw new UnsolvedSymbolException("ground target bound");
            }
            values.add(value);
        }
        return new com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl(declaration,values);
    }
    private ResolvedMethodDeclaration select(MethodReferenceExpr reference,List<ResolvedType> args) {
        try { return reference.resolve(); }
        catch (UnsolvedSymbolException | UnsupportedOperationException failure) {
            boolean typeScope = reference.getScope() instanceof TypeExpr && value(reference, reference.getScope().toString()).isEmpty();
            if (typeScope) {
                var scope = reference.getScope().asTypeExpr().getType().resolve();
                if (!scope.isReferenceType()) throw failure;
                var methods = scope.asReferenceType().getTypeDeclaration().orElseThrow().getAllMethods().stream().map(MethodUsage::getDeclaration).toList();
                var staticMatch = MethodResolutionLogic.findMostApplicable(methods.stream().filter(ResolvedMethodDeclaration::isStatic).toList(), reference.getIdentifier(), args, solver);
                var instance = !args.isEmpty() && scope.isAssignableBy(args.getFirst())
                        ? MethodResolutionLogic.findMostApplicable(methods.stream().filter(m -> !m.isStatic()).toList(), reference.getIdentifier(), args.subList(1, args.size()), solver)
                        : com.github.javaparser.resolution.model.SymbolReference.<ResolvedMethodDeclaration>unsolved();
                if (staticMatch.isSolved() && !instance.isSolved()) return staticMatch.getCorrespondingDeclaration();
                if (instance.isSolved() && !staticMatch.isSolved()) return instance.getCorrespondingDeclaration();
                throw failure;
            }
            if (failure instanceof UnsupportedOperationException) {
                var receiverValue = value(reference, reference.getScope().toString());
                ResolvedType scope = receiverValue.isPresent()
                        ? receiverValue.orElseThrow().getType()
                        : reference.getScope().calculateResolvedType();
                if (scope.isReferenceType()) {
                    var methods = scope.asReferenceType().getTypeDeclaration().orElseThrow().getAllMethods().stream()
                            .map(MethodUsage::getDeclaration).filter(m -> !m.isStatic()).toList();
                    var bound = MethodResolutionLogic.findMostApplicable(methods, reference.getIdentifier(), args, solver);
                    if (bound.isSolved()) return bound.getCorrespondingDeclaration();
                }
            }
            throw failure;
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
        if (parent instanceof ConditionalExpr conditional
                && (expression == conditional.getThenExpr() || expression == conditional.getElseExpr())) {
            return targetType(conditional);
        }
        if (parent instanceof VariableDeclarator variable) return variable.getType().resolve();
        if (parent instanceof AssignExpr assignment) return assignment.getTarget().calculateResolvedType();
        if (parent instanceof CastExpr cast) return cast.getType().resolve();
        if (parent instanceof ReturnStmt) {
            for (Node scope=parent; scope!=null; scope=scope.getParentNode().orElse(null)) {
                if (scope instanceof LambdaExpr lambda) return functionalReturn(targetType(lambda));
                if (scope instanceof MethodDeclaration method) return method.getType().resolve();
            }
        }
        if (parent instanceof com.github.javaparser.ast.stmt.YieldStmt) {
            for (Node scope=parent.getParentNode().orElse(null); scope!=null; scope=scope.getParentNode().orElse(null)) {
                if (scope instanceof SwitchExpr switchExpr) return targetType(switchExpr);
                if (scope instanceof LambdaExpr || scope instanceof MethodDeclaration || scope instanceof TypeDeclaration<?>) break;
            }
        }
        if (parent instanceof com.github.javaparser.ast.stmt.ExpressionStmt
                && parent.getParentNode().orElse(null) instanceof com.github.javaparser.ast.stmt.SwitchEntry entry
                && entry.getParentNode().orElse(null) instanceof SwitchExpr switchExpr) {
            return targetType(switchExpr);
        }
        if (parent instanceof ArrayInitializerExpr arrayInit) {
            return arrayElementTarget(arrayInit);
        }
        if (parent instanceof LambdaExpr lambda) return functionalReturn(targetType(lambda));
        if (parent instanceof ObjectCreationExpr creation) {
            int index = creation.getArguments().indexOf(expression);
            if (index >= 0) {
                var constructed = creation.getType().resolve();
                if (constructed.isReferenceType()) {
                    var ref = constructed.asReferenceType();
                    var decl = ref.getTypeDeclaration().orElseThrow();
                    ResolvedConstructorDeclaration ctor = selectConstructorForReference(creation, decl, index);
                    if (ctor != null) {
                        var paramType = ref.typeParametersMap().replaceAll(
                                ctor.getParam(Math.min(index, ctor.getNumberOfParams() - 1)).getType());
                        return ctor.getParam(ctor.getNumberOfParams() - 1).isVariadic()
                                && index >= ctor.getNumberOfParams() - 1
                                ? paramType.asArrayType().getComponentType() : paramType;
                    }
                }
            }
        }
        if (parent instanceof MethodCallExpr call) {
            var method = JavaParserFacade.get(solver).solveMethodAsUsage(call);
            int index = call.getArguments().indexOf(expression);
            var type = method.getParamType(Math.min(index,method.getNoParams()-1));
            return method.getDeclaration().getParam(method.getNoParams()-1).isVariadic() && index >= method.getNoParams()-1 ? type.asArrayType().getComponentType() : type;
        }
        throw new UnsupportedOperationException("Functional target context");
    }
    private ResolvedType arrayElementTarget(ArrayInitializerExpr arrayInit) {
        Node owner = arrayInit.getParentNode().orElseThrow();
        ResolvedType arrayType = null;
        if (owner instanceof ArrayCreationExpr creation) {
            // Prefer enclosing target type when the array creation uses a raw generic element type
            // (e.g. Function<Pet, String>[] a = new Function[] { Pet::name }).
            try {
                ResolvedType contextual = targetType(creation);
                if (contextual.isArray()) arrayType = contextual;
            } catch (RuntimeException ignored) {}
            if (arrayType == null) {
                try {
                    arrayType = creation.calculateResolvedType();
                } catch (RuntimeException ignored) {
                    ResolvedType elem = creation.getElementType().resolve();
                    for (int d = 0; d < creation.getLevels().size(); d++) {
                        elem = new com.github.javaparser.resolution.types.ResolvedArrayType(elem);
                    }
                    arrayType = elem;
                }
            }
        } else if (owner instanceof VariableDeclarator varDecl) {
            arrayType = varDecl.getType().resolve();
        } else if (owner instanceof ArrayInitializerExpr outerInit) {
            arrayType = arrayElementTarget(outerInit);
        } else {
            throw new UnsupportedOperationException("Functional target context");
        }
        if (!arrayType.isArray()) throw new UnsupportedOperationException("Functional target context");
        return arrayType.asArrayType().getComponentType();
    }
    private ResolvedConstructorDeclaration selectConstructorForReference(
            ObjectCreationExpr creation, ResolvedReferenceTypeDeclaration decl, int refIndex) {
        try {
            return creation.resolve();
        } catch (RuntimeException ignored) {}
        int argCount = creation.getArguments().size();
        var matching = new ArrayList<ResolvedConstructorDeclaration>();
        for (var c : decl.getConstructors()) {
            boolean variadic = c.getNumberOfParams() > 0 && c.getParam(c.getNumberOfParams() - 1).isVariadic();
            if (variadic ? argCount < c.getNumberOfParams() - 1 : argCount != c.getNumberOfParams()) continue;
            boolean ok = true;
            for (int j = 0; j < argCount; j++) {
                if (j == refIndex) continue;
                var arg = creation.getArgument(j);
                if (arg instanceof MethodReferenceExpr || arg instanceof LambdaExpr) continue;
                try {
                    var argType = arg.calculateResolvedType();
                    var paramType = c.getParam(Math.min(j, c.getNumberOfParams() - 1)).getType();
                    if (variadic && j >= c.getNumberOfParams() - 1 && !paramType.isAssignableBy(argType)) {
                        paramType = paramType.asArrayType().getComponentType();
                    }
                    if (!paramType.isTypeVariable() && !paramType.isAssignableBy(argType)) {
                        ok = false;
                        break;
                    }
                } catch (RuntimeException e) {
                    ok = false;
                    break;
                }
            }
            if (ok) matching.add(c);
        }
        return matching.size() == 1 ? matching.getFirst() : null;
    }
    private ResolvedType functionalReturn(ResolvedType target) {
        target=groundTarget(target);
        return target.asReferenceType().typeParametersMap().replaceAll(FunctionalInterfaceLogic.getFunctionalMethod(target).orElseThrow().returnType());
    }
    private static final DataKey<Map<String, Optional<ResolvedValueDeclaration>>> VALUE_CACHE = new DataKey<>() {};
    private static final ThreadLocal<JavaParser> EXPR_PARSER = ThreadLocal.withInitial(() ->
            new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                    .setStoreTokens(false).setAttributeComments(false)));

    static Optional<ResolvedValueDeclaration> value(MethodReferenceExpr reference, String name) {
        Map<String, Optional<ResolvedValueDeclaration>> cache = reference.containsData(VALUE_CACHE)
                ? reference.getData(VALUE_CACHE) : null;
        if (cache != null) {
            var cached = cache.get(name);
            if (cached != null) return cached;
        }
        // The grammar represents an ambiguous receiver such as field::method as TypeExpr.
        // Reparse only for binding; all emitted evidence uses the original node's span.
        Optional<ResolvedValueDeclaration> resolved = Optional.empty();
        Expression receiver = null;
        try {
            receiver = buildSimpleOrDottedReceiver(name);
            if (receiver == null) {
                var parsed = EXPR_PARSER.get().parseExpression(name);
                if (parsed.isSuccessful() && parsed.getResult().isPresent()) {
                    receiver = parsed.getResult().orElseThrow();
                }
            }
            if (receiver != null) {
                receiver.setParentNode(reference);
                if (receiver instanceof NameExpr simple) resolved = Optional.of(simple.resolve());
                else if (receiver instanceof FieldAccessExpr field) resolved = Optional.of(field.resolve());
            }
        } catch (RuntimeException failure) { /* A type qualifier has no value binding. */ }
        finally { if (receiver != null) receiver.setParentNode(null); }
        if (cache == null) {
            cache = new HashMap<>(4);
            reference.setData(VALUE_CACHE, cache);
        }
        cache.put(name, resolved);
        return resolved;
    }

    private static Expression buildSimpleOrDottedReceiver(String name) {
        if (name.isEmpty()) return null;
        int start = 0;
        Expression expr = null;
        for (int i = 0; i <= name.length(); i++) {
            if (i == name.length() || name.charAt(i) == '.') {
                if (i == start) return null;
                String part = name.substring(start, i);
                if (!isPlainIdentifier(part) || part.equals("this") || part.equals("super") || part.equals("class")) {
                    return null;
                }
                expr = expr == null ? new NameExpr(part) : new FieldAccessExpr(expr, part);
                start = i + 1;
            } else {
                char c = name.charAt(i);
                if (!Character.isJavaIdentifierPart(c)) return null;
            }
        }
        return expr;
    }

    private static boolean isPlainIdentifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }
}
