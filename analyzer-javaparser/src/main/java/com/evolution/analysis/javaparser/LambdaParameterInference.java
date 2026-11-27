package com.evolution.analysis.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.logic.FunctionalInterfaceLogic;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import java.util.*;

/**
 * Materializes context-proven ground lambda parameter types so downstream resolution avoids
 * JavaParser constraint/LUB gaps without breaking genuine overload ties or promoting
 * raw/lower-wildcard receivers. Original source bytes and observation spans remain authoritative.
 */
final class LambdaParameterInference {
    static final DataKey<Boolean> IMPLICIT = new DataKey<>() {};
    static final DataKey<Boolean> INPUT = new DataKey<>() {};
    private static final ThreadLocal<JavaParser> TYPE_PARSER = ThreadLocal.withInitial(() ->
            new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                    .setStoreTokens(false).setAttributeComments(false)));

    private LambdaParameterInference() {}

    static boolean prepare(CompilationUnit unit) {
        return prepare(unit, null);
    }

    static boolean prepare(CompilationUnit unit, TypeSolver solver) {
        boolean rewrote = false;
        for (var lambda : unit.findAll(LambdaExpr.class)) {
            if (lambda.getParameters().isEmpty()
                    || lambda.getParameters().stream().anyMatch(p -> !p.getType().isUnknownType() && !p.getType().isVarType())) {
                continue;
            }
            try {
                List<ResolvedType> paramTypes = inferLambdaInputs(lambda, solver, 0);
                if (paramTypes == null || paramTypes.size() != lambda.getParameters().size()) continue;
                var types = new ArrayList<Type>(paramTypes.size());
                for (int i = 0; i < paramTypes.size(); i++) {
                    ResolvedType input = paramTypes.get(i);
                    if (!concrete(input)) {
                        types = null;
                        break;
                    }
                    var parameter = lambda.getParameter(i);
                    var written = parameter.getType();
                    var parsedType = TYPE_PARSER.get().parseType(input.describe());
                    if (!parsedType.isSuccessful() || parsedType.getResult().isEmpty()) {
                        types = null;
                        break;
                    }
                    var type = parsedType.getResult().orElseThrow();
                    var range = written.getRange().orElseGet(() -> parameter.getName().getRange().orElseThrow());
                    type.walk(n -> n.setRange(range));
                    type.setData(written.isUnknownType() ? IMPLICIT : RecordPatternInference.INFERRED, true);
                    types.add(type);
                }
                if (types == null) continue;
                for (int i = 0; i < types.size(); i++) lambda.getParameter(i).setType(types.get(i));
                rewrote = true;
                clearTypes(lambda);
                normalizeInputs(lambda);
            } catch (RuntimeException unavailable) {
                if (unavailable instanceof java.util.concurrent.CancellationException) throw unavailable;
                // Keep the original syntax and its typed observation outcome.
            }
        }
        // An outer lambda's rewrite can invalidate nested input caches. Restore
        // proven inputs once, without depending on source traversal order.
        if (rewrote) {
            for (var lambda : unit.findAll(LambdaExpr.class)) {
                if (lambda.getParameters().stream().anyMatch(p ->
                        p.getType().containsData(IMPLICIT) || p.getType().containsData(RecordPatternInference.INFERRED))) {
                    normalizeInputs(lambda);
                }
            }
        }
        return rewrote;
    }

    private static List<ResolvedType> inferLambdaInputs(LambdaExpr lambda, TypeSolver solver, int depth) {
        if (depth > 8) return null;
        Node argument = lambda;
        while (true) {
            Node p = argument.getParentNode().orElse(null);
            if (p instanceof EnclosedExpr) {
                argument = p;
            } else if (p instanceof ConditionalExpr cond
                    && (argument == cond.getThenExpr() || argument == cond.getElseExpr())) {
                argument = p;
            } else {
                break;
            }
        }
        Node parent = argument.getParentNode().orElse(null);
        if (parent == null) return null;

        if (parent instanceof MethodCallExpr call) {
            int index = call.getArguments().indexOf(argument);
            if (index < 0) return null;
            return inferFromMethodCall(call, index, lambda.getParameters().size(), solver);
        }
        if (parent instanceof ObjectCreationExpr creation) {
            int index = creation.getArguments().indexOf(argument);
            if (index < 0) return null;
            return inferFromConstructorCall(creation, index, lambda.getParameters().size());
        }
        ResolvedType directTarget = resolveDirectTargetType(parent, argument, solver, depth);
        if (directTarget != null) {
            return extractSamInputTypes(directTarget, lambda.getParameters().size());
        }
        return null;
    }

    private static ResolvedType resolveDirectTargetType(Node parent, Node child, TypeSolver solver, int depth) {
        if (depth > 8 || parent == null) return null;
        if (parent instanceof VariableDeclarator varDecl) {
            if (varDecl.getInitializer().map(init -> init == child).orElse(false)
                    && !varDecl.getType().isVarType()) {
                return varDecl.getType().resolve();
            }
            return null;
        }
        if (parent instanceof AssignExpr assign) {
            if (assign.getValue() == child) {
                return assign.getTarget().calculateResolvedType();
            }
            return null;
        }
        if (parent instanceof CastExpr cast) {
            if (cast.getExpression() == child) {
                return cast.getType().resolve();
            }
            return null;
        }
        if (parent instanceof ReturnStmt) {
            for (Node scope = parent.getParentNode().orElse(null); scope != null; scope = scope.getParentNode().orElse(null)) {
                if (scope instanceof LambdaExpr outerLambda) {
                    return resolveLambdaReturnTarget(outerLambda, solver, depth + 1);
                }
                if (scope instanceof MethodDeclaration method) {
                    return method.getType().isVoidType() ? null : method.getType().resolve();
                }
                if (scope instanceof TypeDeclaration<?> || scope instanceof ConstructorDeclaration
                        || scope instanceof InitializerDeclaration) {
                    return null;
                }
            }
            return null;
        }
        if (parent instanceof ExpressionStmt && parent.getParentNode().orElse(null) instanceof LambdaExpr outerLambda) {
            return resolveLambdaReturnTarget(outerLambda, solver, depth + 1);
        }
        if (parent instanceof LambdaExpr outerLambda) {
            return resolveLambdaReturnTarget(outerLambda, solver, depth + 1);
        }
        return null;
    }

    private static ResolvedType resolveLambdaReturnTarget(LambdaExpr lambda, TypeSolver solver, int depth) {
        if (depth > 8) return null;
        Node argument = lambda;
        while (true) {
            Node p = argument.getParentNode().orElse(null);
            if (p instanceof EnclosedExpr) argument = p;
            else if (p instanceof ConditionalExpr cond && (argument == cond.getThenExpr() || argument == cond.getElseExpr())) argument = p;
            else break;
        }
        Node parent = argument.getParentNode().orElse(null);
        ResolvedType functional = null;
        if (parent instanceof MethodCallExpr call) {
            int index = call.getArguments().indexOf(argument);
            if (index >= 0) {
                functional = resolveMethodCallFunctionalTarget(call, index, lambda.getParameters().size(), solver);
            }
        } else if (parent instanceof ObjectCreationExpr creation) {
            int index = creation.getArguments().indexOf(argument);
            if (index >= 0) {
                functional = resolveConstructorFunctionalTarget(creation, index, lambda.getParameters().size());
            }
        } else {
            functional = resolveDirectTargetType(parent, argument, solver, depth + 1);
        }
        if (functional == null || !functional.isReferenceType()) return null;
        var samOpt = FunctionalInterfaceLogic.getFunctionalMethod(functional);
        if (samOpt.isEmpty()) return null;
        ResolvedType ret = functional.asReferenceType().typeParametersMap().replaceAll(samOpt.orElseThrow().returnType());
        if (ret.isWildcard() && ret.asWildcard().isUpperBounded()) {
            ret = ret.asWildcard().getBoundedType();
        }
        return concrete(ret) ? ret : null;
    }

    private static List<ResolvedType> inferFromConstructorCall(
            ObjectCreationExpr creation, int lambdaArgIndex, int lambdaArity) {
        ResolvedType target = resolveConstructorFunctionalTarget(creation, lambdaArgIndex, lambdaArity);
        return target != null ? extractSamInputTypes(target, lambdaArity) : null;
    }

    private static ResolvedType resolveConstructorFunctionalTarget(
            ObjectCreationExpr creation, int lambdaArgIndex, int lambdaArity) {
        if (creation.getAnonymousClassBody().isPresent()) return null;
        ResolvedType constructed = creation.getType().resolve();
        if (!constructed.isReferenceType() || !concrete(constructed)) return null;
        var view = constructed.asReferenceType();
        var decl = view.getTypeDeclaration().orElse(null);
        if (decl == null) return null;
        int argCount = creation.getArguments().size();
        List<ResolvedConstructorDeclaration> candidates = decl.getConstructors().stream()
                .filter(c -> c.getNumberOfParams() == argCount
                        && (c.getNumberOfParams() == 0 || !c.getParam(c.getNumberOfParams() - 1).isVariadic()))
                .toList();
        if (candidates.isEmpty()) return null;
        return selectUniqueFunctionalParam(candidates, creation.getArguments(), lambdaArgIndex, lambdaArity, view.typeParametersMap(), Optional.empty());
    }

    private static List<ResolvedType> inferFromMethodCall(
            MethodCallExpr call, int lambdaArgIndex, int lambdaArity, TypeSolver solver) {
        ResolvedType target = resolveMethodCallFunctionalTarget(call, lambdaArgIndex, lambdaArity, solver);
        return target != null ? extractSamInputTypes(target, lambdaArity) : null;
    }

    private static ResolvedType resolveMethodCallFunctionalTarget(
            MethodCallExpr call, int lambdaArgIndex, int lambdaArity, TypeSolver solver) {
        int argCount = call.getArguments().size();
        if (call.getScope().isPresent()) {
            Expression scopeExpr = call.getScope().orElseThrow();
            // First check if scope is a type name (static method call like Collections.sort or Collectors.toMap).
            ResolvedReferenceTypeDeclaration staticOwner = resolveStaticScopeType(scopeExpr, solver);
            if (staticOwner != null) {
                return resolveFromStaticCandidates(staticOwner, call, lambdaArgIndex, lambdaArity);
            }
            // Instance method call on a receiver expression.
            ResolvedType scope;
            try {
                scope = scopeExpr.calculateResolvedType();
            } catch (RuntimeException ignored) {
                scope = null;
            }
            if ((scope == null || !concrete(scope)) && scopeExpr instanceof MethodCallExpr factory) {
                try {
                    scope = explicitFactoryReturn(factory);
                } catch (RuntimeException ignored) {
                    scope = null;
                }
            }
            if (scope == null || !scope.isReferenceType() || !concrete(scope)) return null;
            var receiver = scope.asReferenceType();
            var declaration = receiver.getTypeDeclaration().orElse(null);
            if (declaration == null) return null;
            List<ResolvedMethodDeclaration> candidates = distinctBySignature(
                    declaration.getAllMethods().stream()
                            .map(MethodUsage::getDeclaration)
                            .filter(m -> !m.isStatic()
                                    && m.getName().equals(call.getNameAsString())
                                    && m.getNumberOfParams() == argCount
                                    && (m.getNumberOfParams() == 0 || !m.getParam(m.getNumberOfParams() - 1).isVariadic()))
                            .toList());
            if (candidates.isEmpty()) return null;
            if (candidates.size() == 1) {
                // Fast exact path: verify via call.resolve() when possible (preserves existing strict contract).
                ResolvedMethodDeclaration only = candidates.getFirst();
                try {
                    var resolved = call.resolve();
                    if (resolved.isStatic() || !resolved.getQualifiedSignature().equals(only.getQualifiedSignature())) {
                        return null;
                    }
                    only = resolved;
                } catch (RuntimeException ignored) {
                    // When JavaParser fails to resolve the call itself because the lambda parameter is untyped,
                    // still proceed safely using the unique fixed-arity receiver signature.
                }
                ResolvedTypeParametersMap ownerMap = ownerTypeParametersMap(receiver, only.declaringType());
                return resolveFunctionalParamForCandidate(only, call.getArguments(), lambdaArgIndex, lambdaArity, ownerMap, call.getTypeArguments(), true);
            }
            // Overloaded instance method: narrow strictly using concrete non-lambda args and lambda arity.
            return selectUniqueInstanceFunctionalParam(candidates, receiver, call, lambdaArgIndex, lambdaArity);
        }

        // Unqualified method call: check enclosing type hierarchy, then static imports.
        var enclosingCandidates = new ArrayList<ResolvedMethodDeclaration>();
        boolean inStaticContext = isInStaticContext(call);
        for (Node cur = call.getParentNode().orElse(null); cur != null; cur = cur.getParentNode().orElse(null)) {
            if (cur instanceof TypeDeclaration<?> td) {
                try {
                    var decl = td.resolve();
                    if (decl.isReferenceType()) {
                        var refDecl = decl.asReferenceType();
                        for (var mu : refDecl.getAllMethods()) {
                            var m = mu.getDeclaration();
                            if (!m.getName().equals(call.getNameAsString()) || m.getNumberOfParams() != argCount) continue;
                            if (m.getNumberOfParams() > 0 && m.getParam(m.getNumberOfParams() - 1).isVariadic()) continue;
                            if (inStaticContext && !m.isStatic()) continue;
                            enclosingCandidates.add(m);
                        }
                        if (!enclosingCandidates.isEmpty()) {
                            var unique = distinctBySignature(enclosingCandidates);
                            return selectUniqueFunctionalParam(unique, call.getArguments(), lambdaArgIndex, lambdaArity,
                                    ResolvedTypeParametersMap.empty(), call.getTypeArguments());
                        }
                    }
                } catch (RuntimeException ignored) {}
                if (td.isStatic()) inStaticContext = true;
            }
        }
        // Static imports on the CompilationUnit.
        var cuOpt = call.findCompilationUnit();
        if (cuOpt.isPresent()) {
            var staticCandidates = new ArrayList<ResolvedMethodDeclaration>();
            String methodName = call.getNameAsString();
            for (var imp : cuOpt.orElseThrow().getImports()) {
                if (!imp.isStatic()) continue;
                String ownerFqn;
                if (imp.isAsterisk()) {
                    ownerFqn = imp.getNameAsString();
                } else {
                    if (!imp.getName().getIdentifier().equals(methodName)) continue;
                    String full = imp.getNameAsString();
                    int dot = full.lastIndexOf('.');
                    if (dot <= 0) continue;
                    ownerFqn = full.substring(0, dot);
                }
                ResolvedReferenceTypeDeclaration ownerDecl = solveTypeByName(call, ownerFqn, solver);
                if (ownerDecl == null) continue;
                for (var mu : ownerDecl.getAllMethods()) {
                    var m = mu.getDeclaration();
                    if (m.isStatic() && m.getName().equals(methodName) && m.getNumberOfParams() == argCount
                            && (m.getNumberOfParams() == 0 || !m.getParam(m.getNumberOfParams() - 1).isVariadic())) {
                        staticCandidates.add(m);
                    }
                }
            }
            if (!staticCandidates.isEmpty()) {
                return selectUniqueFunctionalParam(distinctBySignature(staticCandidates), call.getArguments(),
                        lambdaArgIndex, lambdaArity, ResolvedTypeParametersMap.empty(), call.getTypeArguments());
            }
        }
        return null;
    }

    private static ResolvedType resolveFromStaticCandidates(
            ResolvedReferenceTypeDeclaration staticOwner,
            MethodCallExpr call,
            int lambdaArgIndex,
            int lambdaArity) {
        int argCount = call.getArguments().size();
        List<ResolvedMethodDeclaration> candidates = distinctBySignature(
                staticOwner.getAllMethods().stream()
                        .map(MethodUsage::getDeclaration)
                        .filter(m -> m.isStatic()
                                && m.getName().equals(call.getNameAsString())
                                && m.getNumberOfParams() == argCount
                                && (m.getNumberOfParams() == 0 || !m.getParam(m.getNumberOfParams() - 1).isVariadic()))
                        .toList());
        if (candidates.isEmpty()) return null;
        return selectUniqueFunctionalParam(candidates, call.getArguments(), lambdaArgIndex, lambdaArity,
                ResolvedTypeParametersMap.empty(), call.getTypeArguments());
    }

    private static ResolvedType selectUniqueInstanceFunctionalParam(
            List<ResolvedMethodDeclaration> candidates,
            ResolvedReferenceType receiver,
            MethodCallExpr call,
            int lambdaArgIndex,
            int lambdaArity) {
        ResolvedType agreedTarget = null;
        List<String> agreedParamDescriptions = null;
        int matchedCount = 0;
        for (var m : candidates) {
            ResolvedTypeParametersMap ownerMap = ownerTypeParametersMap(receiver, m.declaringType());
            ResolvedType target = resolveFunctionalParamForCandidate(
                    m, call.getArguments(), lambdaArgIndex, lambdaArity, ownerMap, call.getTypeArguments(), false);
            if (target == null) continue;
            List<ResolvedType> inputs = extractSamInputTypes(target, lambdaArity);
            if (inputs == null) continue;
            List<String> descriptions = inputs.stream().map(ResolvedType::describe).toList();
            if (matchedCount == 0) {
                agreedTarget = target;
                agreedParamDescriptions = descriptions;
                matchedCount = 1;
            } else if (!agreedParamDescriptions.equals(descriptions)) {
                return null; // Ambiguous overload with distinct lambda parameter types: never guess.
            } else {
                matchedCount++;
            }
        }
        return matchedCount > 0 ? agreedTarget : null;
    }

    private static ResolvedType selectUniqueFunctionalParam(
            List<? extends ResolvedMethodLikeDeclaration> candidates,
            List<Expression> arguments,
            int lambdaArgIndex,
            int lambdaArity,
            ResolvedTypeParametersMap ownerMap,
            Optional<NodeList<Type>> explicitTypeArgs) {
        ResolvedType agreedTarget = null;
        List<String> agreedParamDescriptions = null;
        int matchedCount = 0;
        for (var m : candidates) {
            ResolvedType target = resolveFunctionalParamForCandidate(
                    m, arguments, lambdaArgIndex, lambdaArity, ownerMap, explicitTypeArgs, candidates.size() == 1);
            if (target == null) continue;
            List<ResolvedType> inputs = extractSamInputTypes(target, lambdaArity);
            if (inputs == null) continue;
            List<String> descriptions = inputs.stream().map(ResolvedType::describe).toList();
            if (matchedCount == 0) {
                agreedTarget = target;
                agreedParamDescriptions = descriptions;
                matchedCount = 1;
            } else if (!agreedParamDescriptions.equals(descriptions)) {
                return null; // Genuine overload ambiguity: withhold inference.
            } else {
                matchedCount++;
            }
        }
        return matchedCount > 0 ? agreedTarget : null;
    }

    private static ResolvedType resolveFunctionalParamForCandidate(
            ResolvedMethodLikeDeclaration candidate,
            List<Expression> arguments,
            int lambdaArgIndex,
            int lambdaArity,
            ResolvedTypeParametersMap ownerMap,
            Optional<NodeList<Type>> explicitTypeArgs,
            boolean uniqueCandidate) {
        var methodBindings = new HashMap<String, ResolvedType>();
        var methodMapBuilder = new ResolvedTypeParametersMap.Builder();

        // 1. Bind explicit call-site type arguments if present (e.g. Util.<String>apply(...)).
        if (explicitTypeArgs.isPresent()) {
            var typeParams = candidate.getTypeParameters();
            var args = explicitTypeArgs.orElseThrow();
            if (typeParams.size() != args.size()) return null;
            for (int i = 0; i < typeParams.size(); i++) {
                ResolvedType actual = args.get(i).resolve();
                if (!concrete(actual) || actual.isPrimitive()) return null;
                methodBindings.put(typeParams.get(i).getQualifiedName(), actual);
                methodMapBuilder.setValue(typeParams.get(i), actual);
            }
        }

        // 2. Check and unify all non-lambda/non-method-ref arguments for overload elimination and type-var inference.
        for (int j = 0; j < arguments.size(); j++) {
            if (j == lambdaArgIndex) continue;
            Expression arg = arguments.get(j);
            if (isUntypedPolyExpression(arg)) continue;
            ResolvedType formal = ownerMap.replaceAll(candidate.getParam(j).getType());
            ResolvedType actual;
            try {
                actual = arg.calculateResolvedType();
            } catch (RuntimeException unavailable) {
                if (uniqueCandidate) continue;
                return null;
            }
            if (actual.isNull()) {
                if (formal.isPrimitive()) return null;
                continue;
            }
            if (!unifyArgument(formal, actual, methodMapBuilder, methodBindings)) {
                // When a unique fixed-arity candidate exists (e.g. Comparator.thenComparing(fn, Comparator.nullsLast(...))),
                // an unrelated generic argument containing uninferred type vars must not block receiver-proven SAM inputs.
                if (uniqueCandidate && !concrete(actual)) continue;
                return null;
            }
        }

        ResolvedTypeParametersMap methodMap = methodMapBuilder.build();
        ResolvedType rawTarget = ownerMap.replaceAll(candidate.getParam(lambdaArgIndex).getType());
        ResolvedType target = methodMap.replaceAll(rawTarget);
        if (!target.isReferenceType()) return null;
        var samOpt = FunctionalInterfaceLogic.getFunctionalMethod(target);
        if (samOpt.isEmpty() || samOpt.orElseThrow().getNoParams() != lambdaArity) return null;
        return target;
    }

    private static boolean unifyArgument(
            ResolvedType formal,
            ResolvedType actual,
            ResolvedTypeParametersMap.Builder builder,
            Map<String, ResolvedType> bindings) {
        if (formal.isWildcard()) {
            var w = formal.asWildcard();
            if (!w.isBounded()) return true;
            return unifyArgument(w.getBoundedType(), actual, builder, bindings);
        }
        if (formal.isTypeVariable()) {
            var tp = formal.asTypeVariable().asTypeParameter();
            if (!tp.declaredOnMethod()) {
                return true;
            }
            ResolvedType boxed = boxIfPrimitive(actual);
            if (!concrete(boxed) || boxed.isPrimitive()) return false;
            // Verify declared extends bounds.
            for (var b : tp.getBounds()) {
                if (b.isExtends() && !b.getType().isTypeVariable()) {
                    try {
                        if (!b.getType().isAssignableBy(boxed)) return false;
                    } catch (RuntimeException ignored) {
                        return false;
                    }
                }
            }
            ResolvedType prior = bindings.putIfAbsent(tp.getQualifiedName(), boxed);
            if (prior != null && !prior.describe().equals(boxed.describe())) return false;
            builder.setValue(tp, boxed);
            return true;
        }
        if (formal.isArray() && actual.isArray()) {
            return unifyArgument(formal.asArrayType().getComponentType(), actual.asArrayType().getComponentType(), builder, bindings);
        }
        if (formal.isReferenceType() && actual.isReferenceType()) {
            var fRef = formal.asReferenceType();
            var aRef = actual.asReferenceType();
            if (fRef.typeParametersValues().isEmpty()) {
                try {
                    return fRef.isAssignableBy(aRef);
                } catch (RuntimeException ignored) {
                    return false;
                }
            }
            // Find matching ancestor of actual with same qualified name as formal.
            ResolvedReferenceType matchedActual = null;
            if (aRef.getQualifiedName().equals(fRef.getQualifiedName())) {
                matchedActual = aRef;
            } else {
                for (var anc : aRef.getAllAncestors()) {
                    if (anc.getQualifiedName().equals(fRef.getQualifiedName())) {
                        matchedActual = anc;
                        break;
                    }
                }
            }
            if (matchedActual == null) return false;
            var fArgs = fRef.typeParametersValues();
            var aArgs = matchedActual.typeParametersValues();
            if (fArgs.size() != aArgs.size()) return false;
            for (int i = 0; i < fArgs.size(); i++) {
                ResolvedType fa = fArgs.get(i);
                ResolvedType aa = aArgs.get(i);
                // Never unwrap a lower-bounded wildcard (? super T) on the actual argument!
                if (aa.isWildcard()) return false;
                if (!unifyArgument(fa, aa, builder, bindings)) return false;
            }
            return true;
        }
        try {
            return formal.isAssignableBy(actual);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static List<ResolvedType> extractSamInputTypes(ResolvedType target, int expectedArity) {
        if (target == null || !target.isReferenceType()) return null;
        var samOpt = FunctionalInterfaceLogic.getFunctionalMethod(target);
        if (samOpt.isEmpty()) return null;
        var sam = samOpt.orElseThrow();
        if (sam.getNoParams() != expectedArity) return null;
        var inputs = new ArrayList<ResolvedType>(expectedArity);
        var map = target.asReferenceType().typeParametersMap();
        for (int i = 0; i < expectedArity; i++) {
            ResolvedType input = map.replaceAll(sam.getParamType(i));
            // Non-wildcard ground input from an exact functional target context.
            if (input.isWildcard() && input.asWildcard().isBounded()) {
                input = input.asWildcard().getBoundedType();
            }
            if (!concrete(input)) return null;
            inputs.add(input);
        }
        return inputs;
    }

    private static ResolvedReferenceTypeDeclaration resolveStaticScopeType(Expression scopeExpr, TypeSolver solver) {
        if (!(scopeExpr instanceof NameExpr || scopeExpr instanceof FieldAccessExpr)) return null;
        // Values shadow type names: if it resolves to a local/parameter/field value, it is NOT a static type scope.
        try {
            if (scopeExpr instanceof NameExpr n) {
                n.resolve();
                return null;
            }
            if (scopeExpr instanceof FieldAccessExpr f) {
                f.resolve();
                return null;
            }
        } catch (RuntimeException notAValue) {
            // Expected for a type qualifier like Collections or java.util.Collections.
        }
        return solveTypeByName(scopeExpr, scopeExpr.toString(), solver);
    }

    private static ResolvedReferenceTypeDeclaration solveTypeByName(Node site, String name, TypeSolver solver) {
        if (solver != null) {
            try {
                var solved = JavaParserFactory.getContext(site, solver).solveType(name);
                if (solved.isSolved() && solved.getCorrespondingDeclaration().isReferenceType()) {
                    return solved.getCorrespondingDeclaration().asReferenceType();
                }
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    private static ResolvedTypeParametersMap ownerTypeParametersMap(
            ResolvedReferenceType receiver, ResolvedReferenceTypeDeclaration declaringType) {
        if (receiver.getQualifiedName().equals(declaringType.getQualifiedName())) {
            return receiver.typeParametersMap();
        }
        return receiver.getAllAncestors().stream()
                .filter(t -> t.getQualifiedName().equals(declaringType.getQualifiedName()))
                .findFirst()
                .map(ResolvedReferenceType::typeParametersMap)
                .orElse(ResolvedTypeParametersMap.empty());
    }

    private static boolean isUntypedPolyExpression(Expression expr) {
        Expression cur = expr;
        while (cur instanceof EnclosedExpr enc) cur = enc.getInner();
        if (cur instanceof LambdaExpr l) {
            return l.getParameters().stream().anyMatch(p -> p.getType().isUnknownType() || p.getType().isVarType());
        }
        return cur instanceof MethodReferenceExpr;
    }

    private static boolean isInStaticContext(Node node) {
        for (Node cur = node.getParentNode().orElse(null); cur != null; cur = cur.getParentNode().orElse(null)) {
            if (cur instanceof MethodDeclaration m) return m.isStatic();
            if (cur instanceof InitializerDeclaration i) return i.isStatic();
            if (cur instanceof FieldDeclaration f) return f.isStatic();
            if (cur instanceof ConstructorDeclaration || cur instanceof CompactConstructorDeclaration) return false;
            if (cur instanceof TypeDeclaration<?>) return false;
        }
        return false;
    }

    private static List<ResolvedMethodDeclaration> distinctBySignature(List<ResolvedMethodDeclaration> methods) {
        var bySig = new LinkedHashMap<String, ResolvedMethodDeclaration>();
        for (var m : methods) {
            bySig.putIfAbsent(m.getQualifiedSignature(), m);
        }
        return List.copyOf(bySig.values());
    }

    private static ResolvedType boxIfPrimitive(ResolvedType type) {
        return type;
    }

    private static ResolvedType explicitFactoryReturn(MethodCallExpr call) {
        if (call.getTypeArguments().isPresent()) throw new IllegalArgumentException("Explicit factory arguments require their own substitution");
        var method = call.resolve();
        if (!method.isStatic() || method.getNumberOfParams() == 0 || method.getParam(method.getNumberOfParams() - 1).isVariadic()) {
            throw new IllegalArgumentException("Not a fixed-arity static factory");
        }
        var same = method.declaringType().getAllMethods().stream().map(MethodUsage::getDeclaration)
                .filter(m -> m.isStatic() && m.getName().equals(method.getName()) && m.getNumberOfParams() == call.getArguments().size())
                .map(ResolvedMethodLikeDeclaration::getQualifiedSignature).distinct().count();
        if (same != 1) throw new IllegalArgumentException("Overloaded factory");
        var inferred = new ResolvedTypeParametersMap.Builder();
        var values = new HashMap<String, ResolvedType>();
        for (int i = 0; i < call.getArguments().size(); i++) {
            if (!(call.getArgument(i) instanceof LambdaExpr lambda)) continue;
            var target = method.getParam(i).getType();
            var sam = FunctionalInterfaceLogic.getFunctionalMethod(target).orElseThrow();
            if (!target.isReferenceType() || sam.getNoParams() != lambda.getParameters().size()) continue;
            for (int p = 0; p < sam.getNoParams(); p++) {
                var written = lambda.getParameter(p).getType();
                if (written.isUnknownType() || written.isVarType() || written.containsData(IMPLICIT) || written.containsData(RecordPatternInference.INFERRED)) continue;
                var input = target.asReferenceType().typeParametersMap().replaceAll(sam.getParamType(p));
                if (input.isWildcard() && input.asWildcard().isBounded()) input = input.asWildcard().getBoundedType();
                if (!input.isTypeVariable()) continue;
                var variable = input.asTypeVariable().asTypeParameter();
                if (!variable.declaredOnMethod() || variable.getBounds().stream().anyMatch(b ->
                        !b.isExtends() || !b.getType().isReferenceType() || !b.getType().asReferenceType().getQualifiedName().equals("java.lang.Object"))) {
                    continue;
                }
                var actual = written.resolve();
                if (!concrete(actual) || actual.isPrimitive()) continue;
                var prior = values.putIfAbsent(variable.getQualifiedName(), actual);
                if (prior != null && !prior.equals(actual)) throw new IllegalArgumentException("Conflicting explicit lambda inputs");
                inferred.setValue(variable, actual);
            }
        }
        var result = inferred.build().replaceAll(method.getReturnType());
        if (!concrete(result)) throw new IllegalArgumentException("Unavailable factory return");
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void normalizeInputs(LambdaExpr lambda) {
        for (var name : lambda.findAll(NameExpr.class)) {
            try {
                var binding = name.resolve().toAst().orElse(null);
                if (!(binding instanceof Parameter parameter) || lambda.getParameters().stream().noneMatch(p -> p == parameter)) continue;
                var exact = parameter.getType().resolve();
                if (!concrete(exact)) continue;
                name.setData(INPUT, true);
                var cached = name.calculateResolvedType();
                if (!cached.isConstraint() || !cached.asConstraintType().getBound().equals(exact)) continue;
                for (var key : new ArrayList<>(name.getDataKeys())) {
                    if (key.getClass().getEnclosingClass() == com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.class
                            && name.getData(key) instanceof ResolvedType) {
                        name.setData((DataKey<ResolvedType>) key, exact);
                    }
                }
            } catch (RuntimeException unavailable) {
                if (unavailable instanceof java.util.concurrent.CancellationException) throw unavailable;
            }
        }
    }

    private static void clearTypes(LambdaExpr lambda) {
        lambda.walk(LambdaParameterInference::clearNodeTypes);
        for (Node parent = lambda.getParentNode().orElse(null); parent != null; parent = parent.getParentNode().orElse(null)) {
            clearNodeTypes(parent);
        }
    }

    private static void clearNodeTypes(Node node) {
        for (var key : new ArrayList<>(node.getDataKeys())) {
            if (key.getClass().getEnclosingClass() == com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.class) {
                node.removeData(key);
            }
        }
    }

    private static boolean concrete(ResolvedType type) {
        if (type.isArray()) return concrete(type.asArrayType().getComponentType());
        if (type.isPrimitive()) return true;
        if (!type.isReferenceType()) return false;
        var view = type.asReferenceType();
        return view.getTypeDeclaration().map(d -> d.getTypeParameters().size() == view.typeParametersValues().size()).orElse(false)
                && view.typeParametersValues().stream().allMatch(LambdaParameterInference::concrete);
    }
}
