package com.evolution.analysis.javaparser.asm;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.Context;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.logic.MethodResolutionLogic;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.*;
import java.util.*;
import org.objectweb.asm.Opcodes;

/**
 * Recursive-descent parser and resolver for JVMS §4.7.9.1 generic signatures and raw bytecode descriptors.
 * Produces JavaParser {@link ResolvedType} instances matching Javassist symbol solver semantics.
 */
final class AsmSignatureResolver {
    private AsmSignatureResolver() {}

    record ParsedTypeParam(String name, List<String> boundSignatures) {}

    record ParsedClassSignature(
            List<ParsedTypeParam> typeParams,
            String superClassSignature,
            List<String> interfaceSignatures) {}

    record ParsedMethodSignature(
            List<ParsedTypeParam> typeParams,
            List<String> paramSignatures,
            String returnSignature,
            List<String> exceptionSignatures) {}

    static AccessSpecifier accessSpecifier(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) return AccessSpecifier.PUBLIC;
        if ((access & Opcodes.ACC_PROTECTED) != 0) return AccessSpecifier.PROTECTED;
        if ((access & Opcodes.ACC_PRIVATE) != 0) return AccessSpecifier.PRIVATE;
        return AccessSpecifier.NONE;
    }

    static ParsedClassSignature parseClassSignature(String sig) {
        var c = new Cursor(sig);
        List<ParsedTypeParam> tps = c.peek() == '<' ? parseFormalTypeParams(c) : List.of();
        String superSig = readFieldTypeSignature(c);
        var ifaces = new ArrayList<String>(2);
        while (c.hasMore()) {
            ifaces.add(readFieldTypeSignature(c));
        }
        return new ParsedClassSignature(tps, superSig, ifaces);
    }

    static ParsedMethodSignature parseMethodSignature(String sig) {
        var c = new Cursor(sig);
        List<ParsedTypeParam> tps = c.peek() == '<' ? parseFormalTypeParams(c) : List.of();
        c.expect('(');
        var params = new ArrayList<String>(4);
        while (c.peek() != ')') {
            params.add(readJavaTypeSignature(c));
        }
        c.expect(')');
        String ret = c.peek() == 'V' ? String.valueOf(c.next()) : readJavaTypeSignature(c);
        var excs = new ArrayList<String>(2);
        while (c.hasMore() && c.peek() == '^') {
            c.next();
            excs.add(readFieldTypeSignature(c));
        }
        return new ParsedMethodSignature(tps, params, ret, excs);
    }

    static List<String> parseMethodDescriptorParams(String desc) {
        var c = new Cursor(desc);
        c.expect('(');
        var params = new ArrayList<String>(4);
        while (c.peek() != ')') {
            params.add(readJavaTypeSignature(c));
        }
        return params;
    }

    static String parseMethodDescriptorReturn(String desc) {
        int idx = desc.indexOf(')');
        return idx >= 0 ? desc.substring(idx + 1) : "V";
    }

    static ResolvedType resolveDescriptorOrSignature(
            String sig, TypeSolver rootSolver, ResolvedTypeParametrizable container) {
        return resolveType(new Cursor(sig), rootSolver, container);
    }

    static ResolvedType resolveInternalClass(String internalName, TypeSolver rootSolver) {
        if (internalName.startsWith("[")) {
            return resolveDescriptorOrSignature(internalName, rootSolver, null);
        }
        String canonical = AsmClassStub.internalToQualifiedName(internalName);
        return new ReferenceTypeImpl(rootSolver.solveType(canonical));
    }

    static Optional<ResolvedType> findTypeParameter(
            String name, ResolvedTypeParametrizable container, TypeSolver rootSolver) {
        ResolvedTypeParametrizable current = container;
        while (current != null) {
            var direct = current.findTypeParameter(name);
            if (direct.isPresent()) {
                return Optional.of(new ResolvedTypeVariable(direct.orElseThrow()));
            }
            if (current instanceof ResolvedMethodLikeDeclaration methodLike) {
                current = methodLike.declaringType();
            } else if (current instanceof ResolvedReferenceTypeDeclaration ref) {
                Optional<ResolvedReferenceTypeDeclaration> outer;
                try {
                    outer = ref.containerType();
                } catch (UnsupportedOperationException ignored) {
                    outer = Optional.empty();
                }
                current = outer.orElse(null);
            } else {
                break;
            }
        }
        return Optional.empty();
    }

    private static ResolvedType resolveType(
            Cursor c, TypeSolver rootSolver, ResolvedTypeParametrizable container) {
        char ch = c.peek();
        return switch (ch) {
            case 'V' -> { c.next(); yield ResolvedVoidType.INSTANCE; }
            case 'Z' -> { c.next(); yield ResolvedPrimitiveType.BOOLEAN; }
            case 'B' -> { c.next(); yield ResolvedPrimitiveType.BYTE; }
            case 'C' -> { c.next(); yield ResolvedPrimitiveType.CHAR; }
            case 'S' -> { c.next(); yield ResolvedPrimitiveType.SHORT; }
            case 'I' -> { c.next(); yield ResolvedPrimitiveType.INT; }
            case 'J' -> { c.next(); yield ResolvedPrimitiveType.LONG; }
            case 'F' -> { c.next(); yield ResolvedPrimitiveType.FLOAT; }
            case 'D' -> { c.next(); yield ResolvedPrimitiveType.DOUBLE; }
            case '[' -> {
                c.next();
                yield new ResolvedArrayType(resolveType(c, rootSolver, container));
            }
            case 'T' -> {
                c.next();
                String tvName = c.readUntil(';');
                c.expect(';');
                yield findTypeParameter(tvName, container, rootSolver)
                        .orElseGet(() -> new ReferenceTypeImpl(rootSolver.solveType("java.lang.Object")));
            }
            case 'L' -> resolveClassTypeSignature(c, rootSolver, container);
            default -> throw new IllegalArgumentException("Invalid JVM type signature at " + c.pos + ": " + c.s);
        };
    }

    private record SignatureSegment(String canonicalName, List<ResolvedType> args) {}

    private static ResolvedReferenceType resolveClassTypeSignature(
            Cursor c, TypeSolver rootSolver, ResolvedTypeParametrizable container) {
        c.expect('L');
        var fullName = new StringBuilder(64);
        var segments = new ArrayList<SignatureSegment>(2);
        boolean hasOuterArgs = false;
        while (true) {
            int start = c.pos;
            while (c.hasMore()) {
                char ch = c.peek();
                if (ch == '<' || ch == ';' || ch == '.') break;
                c.next();
            }
            String segment = c.s.substring(start, c.pos).replace('/', '.').replace('$', '.');
            if (!fullName.isEmpty()) fullName.append('.');
            fullName.append(segment);

            List<ResolvedType> segArgs = List.of();
            if (c.hasMore() && c.peek() == '<') {
                c.next();
                var list = new ArrayList<ResolvedType>(2);
                while (c.peek() != '>') {
                    list.add(resolveTypeArgument(c, rootSolver, container));
                }
                c.expect('>');
                segArgs = list;
            }
            String currentCanonical = fullName.toString();
            if (c.peek() == '.') {
                if (!segArgs.isEmpty()) {
                    hasOuterArgs = true;
                }
                segments.add(new SignatureSegment(currentCanonical, segArgs));
                c.next();
                continue;
            }
            segments.add(new SignatureSegment(currentCanonical, segArgs));
            c.expect(';');
            break;
        }
        String canonical = fullName.toString();
        ResolvedReferenceTypeDeclaration decl = rootSolver.solveType(canonical);
        List<ResolvedType> ownArgs = segments.getLast().args();
        List<ResolvedTypeParameterDeclaration> declParams = decl.getTypeParameters();
        List<ResolvedType> matchedOwnArgs = (!ownArgs.isEmpty() && ownArgs.size() == declParams.size())
                ? ownArgs
                : List.of();
        if (!hasOuterArgs) {
            return matchedOwnArgs.isEmpty()
                    ? new ReferenceTypeImpl(decl)
                    : new ReferenceTypeImpl(decl, matchedOwnArgs);
        }
        var outerBindings = new LinkedHashMap<ResolvedTypeParameterDeclaration, ResolvedType>();
        for (int sIdx = 0; sIdx < segments.size() - 1; sIdx++) {
            var seg = segments.get(sIdx);
            if (seg.args().isEmpty()) continue;
            var outerSolved = rootSolver.tryToSolveType(seg.canonicalName());
            if (!outerSolved.isSolved()) continue;
            var outerParams = outerSolved.getCorrespondingDeclaration().getTypeParameters();
            if (outerParams.size() != seg.args().size()) continue;
            for (int i = 0; i < outerParams.size(); i++) {
                outerBindings.put(outerParams.get(i), seg.args().get(i));
            }
        }
        if (outerBindings.isEmpty()) {
            return matchedOwnArgs.isEmpty()
                    ? new ReferenceTypeImpl(decl)
                    : new ReferenceTypeImpl(decl, matchedOwnArgs);
        }
        return new EnclosingParameterizedReferenceType(decl, matchedOwnArgs, outerBindings);
    }

    /**
     * {@link ReferenceTypeImpl} that preserves enclosing outer type arguments (e.g. {@code Outer<String>.Inner})
     * inside {@link #typeParametersMap()} while keeping {@link #typeParametersValues()} scoped to the inner type's
     * own type parameters so JavaParser callers checking arity parity remain unaffected.
     */
    private static final class EnclosingParameterizedReferenceType extends ReferenceTypeImpl {
        private final Map<ResolvedTypeParameterDeclaration, ResolvedType> outerBindings;
        private final com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap combinedMap;

        EnclosingParameterizedReferenceType(
                ResolvedReferenceTypeDeclaration decl,
                List<ResolvedType> ownArgs,
                Map<ResolvedTypeParameterDeclaration, ResolvedType> outerBindings) {
            super(decl, ownArgs);
            this.outerBindings = new LinkedHashMap<>(outerBindings);
            var builder = new com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap.Builder();
            for (var e : this.outerBindings.entrySet()) {
                builder.setValue(e.getKey(), e.getValue());
            }
            var ownParams = decl.getTypeParameters();
            for (int i = 0; i < ownParams.size() && i < ownArgs.size(); i++) {
                builder.setValue(ownParams.get(i), ownArgs.get(i));
            }
            this.combinedMap = builder.build();
        }

        @Override
        public com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap typeParametersMap() {
            return combinedMap;
        }

        @Override
        public Optional<ResolvedType> typeParamValue(ResolvedTypeParameterDeclaration typeParameterDeclaration) {
            Optional<ResolvedType> own = super.typeParamValue(typeParameterDeclaration);
            if (own.isPresent()) return own;
            ResolvedType direct = outerBindings.get(typeParameterDeclaration);
            if (direct != null) return Optional.of(direct);
            for (var e : outerBindings.entrySet()) {
                if (e.getKey().getQualifiedName().equals(typeParameterDeclaration.getQualifiedName())) {
                    return Optional.of(e.getValue());
                }
            }
            return Optional.empty();
        }

        @Override
        protected ResolvedReferenceType create(
                ResolvedReferenceTypeDeclaration typeDeclaration, List<ResolvedType> typeParametersCorrected) {
            if (getTypeDeclaration().map(d -> d.getQualifiedName().equals(typeDeclaration.getQualifiedName())).orElse(false)) {
                return new EnclosingParameterizedReferenceType(typeDeclaration, typeParametersCorrected, outerBindings);
            }
            return new ReferenceTypeImpl(typeDeclaration, typeParametersCorrected);
        }

        @Override
        public ResolvedReferenceType deriveTypeParameters(
                com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap typeParametersMap) {
            var mergedOuter = new LinkedHashMap<ResolvedTypeParameterDeclaration, ResolvedType>();
            for (var e : outerBindings.entrySet()) {
                mergedOuter.put(e.getKey(), typeParametersMap.replaceAll(e.getValue()));
            }
            ResolvedReferenceType base = super.deriveTypeParameters(typeParametersMap);
            return new EnclosingParameterizedReferenceType(
                    base.getTypeDeclaration().orElseThrow(),
                    base.typeParametersValues(),
                    mergedOuter);
        }
    }

    private static ResolvedType resolveTypeArgument(
            Cursor c, TypeSolver rootSolver, ResolvedTypeParametrizable container) {
        char ch = c.peek();
        if (ch == '*') {
            c.next();
            return ResolvedWildcard.UNBOUNDED;
        }
        if (ch == '+') {
            c.next();
            return ResolvedWildcard.extendsBound(resolveType(c, rootSolver, container));
        }
        if (ch == '-') {
            c.next();
            return ResolvedWildcard.superBound(resolveType(c, rootSolver, container));
        }
        return resolveType(c, rootSolver, container);
    }

    private static List<ParsedTypeParam> parseFormalTypeParams(Cursor c) {
        c.expect('<');
        var list = new ArrayList<ParsedTypeParam>(2);
        while (c.peek() != '>') {
            String name = c.readUntil(':');
            c.expect(':');
            var bounds = new ArrayList<String>(2);
            // Class bound is optional (immediately followed by ':' when omitted)
            if (c.peek() != ':') {
                bounds.add(readFieldTypeSignature(c));
            }
            while (c.peek() == ':') {
                c.next();
                bounds.add(readFieldTypeSignature(c));
            }
            list.add(new ParsedTypeParam(name, List.copyOf(bounds)));
        }
        c.expect('>');
        return List.copyOf(list);
    }

    private static String readJavaTypeSignature(Cursor c) {
        char ch = c.peek();
        return switch (ch) {
            case 'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D', 'V' -> String.valueOf(c.next());
            default -> readFieldTypeSignature(c);
        };
    }

    private static String readFieldTypeSignature(Cursor c) {
        int start = c.pos;
        skipFieldTypeSignature(c);
        return c.s.substring(start, c.pos);
    }

    private static void skipFieldTypeSignature(Cursor c) {
        char ch = c.peek();
        if (ch == '[') {
            c.next();
            skipJavaTypeSignature(c);
        } else if (ch == 'T') {
            c.next();
            while (c.next() != ';') { /* scan */ }
        } else if (ch == 'L') {
            c.next();
            int angleDepth = 0;
            while (c.hasMore()) {
                char cur = c.next();
                if (cur == '<') angleDepth++;
                else if (cur == '>') angleDepth--;
                else if (cur == ';' && angleDepth == 0) break;
            }
        } else {
            throw new IllegalArgumentException("Unexpected signature token '" + ch + "' at " + c.pos + " in " + c.s);
        }
    }

    private static void skipJavaTypeSignature(Cursor c) {
        char ch = c.peek();
        switch (ch) {
            case 'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D', 'V' -> c.next();
            default -> skipFieldTypeSignature(c);
        }
    }

    static SymbolReference<ResolvedMethodDeclaration> solveMethod(
            String name,
            List<ResolvedType> argumentsTypes,
            boolean staticOnly,
            TypeSolver rootSolver,
            ResolvedReferenceTypeDeclaration typeDeclaration) {
        var candidates = new ArrayList<ResolvedMethodDeclaration>();
        for (var m : typeDeclaration.getDeclaredMethods()) {
            if (m.getName().equals(name) && (!staticOnly || m.isStatic())) {
                candidates.add(m);
            }
        }
        for (ResolvedReferenceType ancestor : typeDeclaration.getAncestors(true)) {
            ancestor.getTypeDeclaration().ifPresent(ancestorDecl -> {
                SymbolReference<ResolvedMethodDeclaration> res = MethodResolutionLogic.solveMethodInType(
                        ancestorDecl, name, argumentsTypes, staticOnly);
                if (res.isSolved()) {
                    candidates.add(res.getCorrespondingDeclaration());
                }
            });
        }
        if (typeDeclaration.isInterface() && !typeDeclaration.isJavaLangObject()) {
            var objRef = rootSolver.tryToSolveType("java.lang.Object");
            if (objRef.isSolved()) {
                SymbolReference<ResolvedMethodDeclaration> res = MethodResolutionLogic.solveMethodInType(
                        objRef.getCorrespondingDeclaration(), name, argumentsTypes, staticOnly);
                if (res.isSolved()) {
                    candidates.add(res.getCorrespondingDeclaration());
                }
            }
        }
        return MethodResolutionLogic.findMostApplicable(candidates, name, argumentsTypes, rootSolver);
    }

    static Optional<MethodUsage> solveMethodAsUsage(
            String name,
            List<ResolvedType> argumentsTypes,
            TypeSolver rootSolver,
            Context invokationContext,
            List<ResolvedType> typeParameters,
            ResolvedReferenceTypeDeclaration typeDeclaration) {
        var declaredTypeParams = typeDeclaration.getTypeParameters();
        var subMapBuilder = new com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap.Builder();
        if (typeParameters != null && !typeParameters.isEmpty()) {
            int limit = Math.min(declaredTypeParams.size(), typeParameters.size());
            for (int i = 0; i < limit; i++) {
                subMapBuilder.setValue(declaredTypeParams.get(i), typeParameters.get(i));
            }
        }
        var subMap = subMapBuilder.build();
        var candidates = new ArrayList<MethodUsage>();
        for (var m : typeDeclaration.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                var mu = applySubstitution(new MethodUsage(m), subMap);
                candidates.add(mu);
                if (argumentsTypes.isEmpty() && mu.getNoParams() == 0) {
                    return Optional.of(mu);
                }
            }
        }
        for (ResolvedReferenceType ancestor : typeDeclaration.getAncestors(true)) {
            Optional<ResolvedReferenceTypeDeclaration> ancestorDeclOpt = ancestor.getTypeDeclaration();
            if (ancestorDeclOpt.isEmpty()) continue;
            var ancestorDecl = ancestorDeclOpt.orElseThrow();
            if (ancestorDecl instanceof com.github.javaparser.symbolsolver.core.resolution.MethodUsageResolutionCapability cap) {
                var ancestorTypeParams = ancestorDecl.getTypeParameters();
                var ancestorMap = ancestor.typeParametersMap();
                List<ResolvedType> ancestorTypeArgs = List.of();
                if (!ancestorTypeParams.isEmpty() && !ancestor.typeParametersValues().isEmpty()) {
                    var mapped = new ArrayList<ResolvedType>(ancestorTypeParams.size());
                    for (var tp : ancestorTypeParams) {
                        ResolvedType val = ancestorMap.getValue(tp);
                        if (val != null && !subMap.isEmpty()) {
                            val = subMap.replaceAll(val);
                        }
                        mapped.add(val != null ? val : new ResolvedTypeVariable(tp));
                    }
                    ancestorTypeArgs = mapped;
                }
                Optional<MethodUsage> found = cap.solveMethodAsUsage(name, argumentsTypes, invokationContext, ancestorTypeArgs);
                if (found.isPresent()) {
                    MethodUsage usage = found.orElseThrow();
                    usage = applySubstitution(usage, ancestorMap);
                    usage = applySubstitution(usage, subMap);
                    candidates.add(usage);
                }
            }
        }
        if (typeDeclaration.isInterface() && !typeDeclaration.isJavaLangObject()) {
            var objRef = rootSolver.tryToSolveType("java.lang.Object");
            if (objRef.isSolved()
                    && objRef.getCorrespondingDeclaration() instanceof com.github.javaparser.symbolsolver.core.resolution.MethodUsageResolutionCapability cap) {
                cap.solveMethodAsUsage(name, argumentsTypes, invokationContext, List.of())
                        .ifPresent(candidates::add);
            }
        }
        return MethodResolutionLogic.findMostApplicableUsage(candidates, name, argumentsTypes, rootSolver);
    }

    /** Applies simultaneous type-parameter substitution without sequential swap aliasing (e.g. {@code <V, K> -> <K, V>}). */
    private static MethodUsage applySubstitution(
            MethodUsage usage,
            com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap map) {
        if (map == null || map.isEmpty()) return usage;
        ResolvedType newRet = map.replaceAll(usage.returnType());
        var newParams = new ArrayList<ResolvedType>(usage.getNoParams());
        for (int i = 0; i < usage.getNoParams(); i++) {
            newParams.add(map.replaceAll(usage.getParamType(i)));
        }
        return new MethodUsage(usage.getDeclaration(), newParams, newRet);
    }

    private static final class Cursor {
        final String s;
        int pos;

        Cursor(String s) {
            this.s = s;
        }

        boolean hasMore() {
            return pos < s.length();
        }

        char peek() {
            return s.charAt(pos);
        }

        char next() {
            return s.charAt(pos++);
        }

        void expect(char expected) {
            char actual = next();
            if (actual != expected) {
                throw new IllegalArgumentException(
                        "Expected '" + expected + "' but got '" + actual + "' at " + (pos - 1) + " in " + s);
            }
        }

        String readUntil(char delimiter) {
            int start = pos;
            int idx = s.indexOf(delimiter, pos);
            if (idx < 0) {
                throw new IllegalArgumentException("Missing '" + delimiter + "' in " + s);
            }
            pos = idx;
            return s.substring(start, idx);
        }
    }
}
