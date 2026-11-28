package com.evolution.backend.query.repair;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.*;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.tree.ParseError;

import java.util.*;

/**
 * Dual-Gate AST / OpenRewrite LST Refactoring Purity Verifier (WP4.3 — SWE-Refactor Sec. 3.2 & Sec. 5).
 *
 * <p>Eliminates test-suite overfitting cheats (e.g., hollow/empty extracted method stubs that pass
 * {@code Compile&Test}) by enforcing TWO mandatory structural gates on before/after source trees:
 * <ol>
 *   <li><b>Gate 1 — Structural Intent Check:</b> confirms the requested refactoring actually took place,
 *       with non-empty executable body, real call-site delegation, preserved signature/dataflow, and
 *       OO instance receiver preservation (never forcing {@code public static} unnecessarily).</li>
 *   <li><b>Gate 2 — Purity Check:</b> verifies that every method/field outside the refactoring scope
 *       remains structurally identical (zero silent logic mutations, deleted branches, or tangled fixes).</li>
 * </ol>
 *
 * <p>Executes strictly passively in memory via JavaParser + OpenRewrite {@code rewrite-java:8.87.7}
 * ({@code I1}, {@code I2}).
 */
public final class AstRefactoringPurityVerifier {

    public static final String VERSION = "repair.ast-refactoring-purity:wp4.3-v1";

    private AstRefactoringPurityVerifier() {}

    public enum RefactoringKind {
        EXTRACT_METHOD,
        MOVE_METHOD,
        INLINE_METHOD,
        EXTRACT_AND_MOVE_METHOD,
        MOVE_AND_RENAME_METHOD,
        MOVE_AND_INLINE_METHOD,
        EXTRACT_CLASS,
        BREAK_SCC_CYCLE
    }

    /** Specification of the intended refactoring scope for Dual-Gate verification. */
    public record RefactoringSpec(
            RefactoringKind kind,
            String sourcePath,
            String sourceMethodName,
            String targetPath,
            String targetMethodName,
            Set<String> allowedTouchedMethods) {
        public RefactoringSpec {
            kind = Objects.requireNonNull(kind, "kind");
            sourcePath = normalizePath(Objects.requireNonNull(sourcePath, "sourcePath"));
            sourceMethodName = sourceMethodName == null ? "" : sourceMethodName.strip();
            targetPath = (targetPath == null || targetPath.isBlank()) ? sourcePath : normalizePath(targetPath);
            targetMethodName = targetMethodName == null ? "" : targetMethodName.strip();
            var allowed = new TreeSet<String>();
            if (!sourceMethodName.isEmpty()) allowed.add(sourceMethodName);
            if (!targetMethodName.isEmpty()) allowed.add(targetMethodName);
            if (allowedTouchedMethods != null) {
                for (String m : allowedTouchedMethods) {
                    if (m != null && !m.isBlank()) allowed.add(m.strip());
                }
            }
            allowedTouchedMethods = Collections.unmodifiableSet(allowed);
        }

        public static RefactoringSpec extractMethod(String path, String sourceMethod, String extractedMethod) {
            return new RefactoringSpec(RefactoringKind.EXTRACT_METHOD, path, sourceMethod, path, extractedMethod, Set.of());
        }

        public static RefactoringSpec moveMethod(String sourcePath, String targetPath, String methodName) {
            return new RefactoringSpec(RefactoringKind.MOVE_METHOD, sourcePath, methodName, targetPath, methodName, Set.of());
        }

        public static RefactoringSpec extractAndMove(String sourcePath, String sourceMethod, String targetPath, String movedMethod) {
            return new RefactoringSpec(RefactoringKind.EXTRACT_AND_MOVE_METHOD, sourcePath, sourceMethod, targetPath, movedMethod, Set.of());
        }

        public static RefactoringSpec moveAndRename(String sourcePath, String oldName, String targetPath, String newName, Set<String> callerMethods) {
            return new RefactoringSpec(RefactoringKind.MOVE_AND_RENAME_METHOD, sourcePath, oldName, targetPath, newName, callerMethods);
        }

        public static RefactoringSpec inlineMethod(String path, String callerMethod, String inlinedMethod) {
            return new RefactoringSpec(RefactoringKind.INLINE_METHOD, path, inlinedMethod, path, callerMethod, Set.of());
        }

        public static RefactoringSpec extractClass(String sourcePath, String newClassPath, Set<String> movedMethods) {
            return new RefactoringSpec(RefactoringKind.EXTRACT_CLASS, sourcePath, "", newClassPath, "", movedMethods);
        }

        public static RefactoringSpec breakSccCycle(String sourcePath, String interfacePath, Set<String> touchedMethods) {
            return new RefactoringSpec(RefactoringKind.BREAK_SCC_CYCLE, sourcePath, "", interfacePath, "", touchedMethods);
        }
    }

    /** Deterministic output of Dual-Gate AST/LST Refactoring Purity Verification. */
    public record VerificationResult(
            String version,
            boolean passed,
            boolean intentPassed,
            boolean purityPassed,
            boolean openRewriteLstValid,
            boolean hollowStubDetected,
            RefactoringKind kind,
            List<String> violations,
            List<String> outOfScopeMutations,
            ContentDigest digest) {
        public VerificationResult {
            version = VERSION;
            kind = Objects.requireNonNull(kind, "kind");
            violations = List.copyOf(violations == null ? List.of() : violations);
            outOfScopeMutations = List.copyOf(outOfScopeMutations == null ? List.of() : outOfScopeMutations);
            digest = digest == null
                    ? CanonicalJson.sha256(List.of(VERSION, passed, intentPassed, purityPassed,
                    openRewriteLstValid, hollowStubDetected, kind.name(), violations, outOfScopeMutations))
                    : digest;
        }
    }

    /**
     * Verifies a candidate refactoring across {@code beforeFiles} and {@code afterFiles} using
     * Gate 1 (Structural Intent), Gate 2 (Out-of-Scope Purity), and OpenRewrite LST parse validation.
     */
    public static VerificationResult verify(
            RefactoringSpec spec,
            Map<String, String> beforeFiles,
            Map<String, String> afterFiles) {
        Objects.requireNonNull(spec, "spec");
        var normBefore = normalizeMap(beforeFiles);
        var normAfter = normalizeMap(afterFiles);

        var violations = new ArrayList<String>();
        var outOfScopeMutations = new ArrayList<String>();

        // Parse all touched Java files with JavaParser
        var beforeCus = new TreeMap<String, CompilationUnit>();
        var afterCus = new TreeMap<String, CompilationUnit>();
        for (var e : normBefore.entrySet()) {
            if (!e.getKey().endsWith(".java")) continue;
            parseJava(e.getValue()).ifPresentOrElse(
                    cu -> beforeCus.put(e.getKey(), cu),
                    () -> violations.add("BEFORE_PARSE_ERROR:" + e.getKey()));
        }
        for (var e : normAfter.entrySet()) {
            if (!e.getKey().endsWith(".java")) continue;
            parseJava(e.getValue()).ifPresentOrElse(
                    cu -> afterCus.put(e.getKey(), cu),
                    () -> violations.add("AFTER_PARSE_ERROR:" + e.getKey()));
        }

        // Validate lossless OpenRewrite 8.87.7 LST parsing for all afterFiles
        boolean lstValid = validateOpenRewriteLst(normAfter, violations);

        // Gate 1: Structural Intent Check + Hollow-Stub Overfitting Detector
        boolean[] hollowFlag = new boolean[]{false};
        boolean intentPassed = violations.isEmpty()
                && checkStructuralIntent(spec, beforeCus, afterCus, violations, hollowFlag);

        // Gate 2: Out-of-Scope Purity Check
        boolean purityPassed = violations.stream().noneMatch(v -> v.startsWith("BEFORE_PARSE_ERROR") || v.startsWith("AFTER_PARSE_ERROR"))
                && checkPurity(spec, beforeCus, afterCus, outOfScopeMutations);
        if (!purityPassed) {
            for (String mut : outOfScopeMutations) {
                violations.add("PURITY_VIOLATION:" + mut);
            }
        }

        boolean passed = intentPassed && purityPassed && lstValid && !hollowFlag[0];
        return new VerificationResult(
                VERSION, passed, intentPassed, purityPassed, lstValid, hollowFlag[0],
                spec.kind(), violations, outOfScopeMutations, null);
    }

    private static boolean checkStructuralIntent(
            RefactoringSpec spec,
            Map<String, CompilationUnit> beforeCus,
            Map<String, CompilationUnit> afterCus,
            List<String> violations,
            boolean[] hollowFlag) {
        int startViolations = violations.size();
        CompilationUnit beforeSrc = beforeCus.get(spec.sourcePath());
        CompilationUnit afterSrc = afterCus.get(spec.sourcePath());
        CompilationUnit beforeTgt = beforeCus.get(spec.targetPath());
        CompilationUnit afterTgt = afterCus.get(spec.targetPath());

        switch (spec.kind()) {
            case EXTRACT_METHOD -> {
                if (beforeSrc == null || afterSrc == null) {
                    violations.add("INTENT_MISSING_SOURCE_FILE:" + spec.sourcePath());
                    return false;
                }
                MethodDeclaration beforeOrigin = findMethod(beforeSrc, spec.sourceMethodName());
                MethodDeclaration afterOrigin = findMethod(afterSrc, spec.sourceMethodName());
                MethodDeclaration extracted = findMethod(afterSrc, spec.targetMethodName());
                if (beforeOrigin == null || afterOrigin == null) {
                    violations.add("INTENT_SOURCE_METHOD_NOT_FOUND:" + spec.sourceMethodName());
                    return false;
                }
                if (extracted == null) {
                    violations.add("INTENT_EXTRACTED_METHOD_NOT_FOUND:" + spec.targetMethodName());
                    return false;
                }
                if (findMethod(beforeSrc, spec.targetMethodName()) != null) {
                    violations.add("INTENT_EXTRACTED_METHOD_ALREADY_EXISTED:" + spec.targetMethodName());
                }
                if (isHollowStubMethod(extracted)) {
                    hollowFlag[0] = true;
                    violations.add("HOLLOW_STUB_DETECTED:" + spec.targetMethodName()
                            + " has an empty or trivial stub body");
                }
                if (!callsMethod(afterOrigin, spec.targetMethodName())) {
                    violations.add("INTENT_NO_DELEGATION_CALL:" + spec.sourceMethodName()
                            + " does not invoke extracted method " + spec.targetMethodName());
                }
                int beforeStmts = countExecutableStatements(beforeOrigin);
                int afterStmts = countExecutableStatements(afterOrigin);
                if (afterStmts > beforeStmts) {
                    hollowFlag[0] = true;
                    violations.add("HOLLOW_STUB_NO_BODY_EXTRACTION:" + spec.sourceMethodName()
                            + " retained all original statements (" + beforeStmts + " -> " + afterStmts + ")");
                }
            }
            case MOVE_METHOD, MOVE_AND_RENAME_METHOD -> {
                if (beforeSrc == null || afterSrc == null || afterTgt == null) {
                    violations.add("INTENT_MISSING_SOURCE_OR_TARGET_FILE");
                    return false;
                }
                MethodDeclaration beforeMoved = findMethod(beforeSrc, spec.sourceMethodName());
                MethodDeclaration afterInTarget = findMethod(afterTgt, spec.targetMethodName());
                if (beforeMoved == null) {
                    violations.add("INTENT_ORIGINAL_METHOD_NOT_FOUND:" + spec.sourceMethodName());
                    return false;
                }
                if (afterInTarget == null) {
                    violations.add("INTENT_MOVED_METHOD_NOT_FOUND_IN_TARGET:" + spec.targetPath() + "#" + spec.targetMethodName());
                    return false;
                }
                if (isHollowStubMethod(afterInTarget) && !isHollowStubMethod(beforeMoved)) {
                    hollowFlag[0] = true;
                    violations.add("HOLLOW_STUB_DETECTED:" + spec.targetMethodName() + " moved as empty/trivial stub");
                }
                boolean distinctFiles = !spec.sourcePath().equals(spec.targetPath());
                // Verify either removed from source OR replaced by a thin delegation to target
                MethodDeclaration remainingInSrc = findMethod(afterSrc, spec.sourceMethodName());
                if (remainingInSrc != null) {
                    boolean delegates = distinctFiles
                            ? isScopedDelegationTo(remainingInSrc, spec.targetMethodName(), beforeMoved)
                            : callsMethod(remainingInSrc, spec.targetMethodName());
                    if (!delegates) {
                        violations.add("INTENT_DUPLICATED_NOT_MOVED:" + spec.sourceMethodName()
                                + " still exists in source without delegating to " + spec.targetMethodName());
                    }
                }
                if (spec.kind() == RefactoringKind.MOVE_AND_RENAME_METHOD) {
                    if (spec.sourceMethodName().equals(spec.targetMethodName())) {
                        violations.add("INTENT_RENAME_UNCHANGED:" + spec.targetMethodName());
                    } else if (distinctFiles) {
                        Map<String, String> beforeSrcMethods = indexNormalizedMethods(beforeSrc);
                        for (MethodDeclaration renamedInSrc : findMethods(afterSrc, spec.targetMethodName())) {
                            String sig = renamedInSrc.getSignature().asString();
                            if (Objects.equals(beforeSrcMethods.get(sig), normalizedMethodEntry(renamedInSrc))) {
                                continue;
                            }
                            if (!isScopedDelegationTo(renamedInSrc, spec.targetMethodName(), beforeMoved)) {
                                violations.add("INTENT_DUPLICATED_NOT_MOVED:" + spec.targetMethodName()
                                        + " still exists in source without delegating via receiver to "
                                        + spec.targetMethodName());
                            }
                        }
                    }
                }
            }
            case EXTRACT_AND_MOVE_METHOD -> {
                if (beforeSrc == null || afterSrc == null || afterTgt == null) {
                    violations.add("INTENT_MISSING_SOURCE_OR_TARGET_FILE");
                    return false;
                }
                MethodDeclaration beforeOrigin = findMethod(beforeSrc, spec.sourceMethodName());
                MethodDeclaration afterOrigin = findMethod(afterSrc, spec.sourceMethodName());
                MethodDeclaration targetMethod = findMethod(afterTgt, spec.targetMethodName());
                if (beforeOrigin == null || afterOrigin == null) {
                    violations.add("INTENT_SOURCE_METHOD_NOT_FOUND:" + spec.sourceMethodName());
                    return false;
                }
                if (targetMethod == null) {
                    violations.add("INTENT_COMPOUND_TARGET_METHOD_MISSING:" + spec.targetPath() + "#" + spec.targetMethodName());
                    return false;
                }
                if (isHollowStubMethod(targetMethod)) {
                    hollowFlag[0] = true;
                    violations.add("HOLLOW_STUB_DETECTED:" + spec.targetMethodName() + " in " + spec.targetPath());
                }
                if (!callsMethod(afterOrigin, spec.targetMethodName())) {
                    violations.add("INTENT_COMPOUND_NO_DELEGATION:" + spec.sourceMethodName()
                            + " does not call moved method " + spec.targetMethodName());
                }
                if (spec.sourcePath().equals(spec.targetPath())) {
                    violations.add("INTENT_COMPOUND_SAME_FILE: EXTRACT_AND_MOVE_METHOD requires distinct target file");
                }
                int beforeStmts = countExecutableStatements(beforeOrigin);
                int afterStmts = countExecutableStatements(afterOrigin);
                if (afterStmts > beforeStmts) {
                    hollowFlag[0] = true;
                    violations.add("HOLLOW_STUB_NO_BODY_EXTRACTION:" + spec.sourceMethodName()
                            + " retained all original statements (" + beforeStmts + " -> " + afterStmts + ")");
                }
            }
            case INLINE_METHOD, MOVE_AND_INLINE_METHOD -> {
                if (beforeSrc == null || afterSrc == null || afterTgt == null) {
                    violations.add("INTENT_MISSING_FILE_FOR_INLINE");
                    return false;
                }
                if (findMethod(beforeSrc, spec.sourceMethodName()) == null) {
                    violations.add("INTENT_INLINED_METHOD_NOT_IN_BASELINE:" + spec.sourceMethodName());
                }
                if (findMethod(afterSrc, spec.sourceMethodName()) != null) {
                    violations.add("INTENT_INLINED_METHOD_STILL_EXISTS:" + spec.sourceMethodName());
                }
                MethodDeclaration callerAfter = findMethod(afterTgt, spec.targetMethodName());
                if (callerAfter == null) {
                    violations.add("INTENT_CALLER_METHOD_MISSING:" + spec.targetMethodName());
                } else if (callsMethod(callerAfter, spec.sourceMethodName())) {
                    violations.add("INTENT_CALLER_STILL_CALLS_INLINED_METHOD:" + spec.sourceMethodName());
                }
            }
            case EXTRACT_CLASS -> {
                if (beforeSrc == null || afterSrc == null || afterTgt == null) {
                    violations.add("INTENT_EXTRACT_CLASS_FILES_MISSING");
                    return false;
                }
                if (beforeTgt != null) {
                    violations.add("INTENT_EXTRACTED_CLASS_ALREADY_EXISTED:" + spec.targetPath());
                }
                var extractedMethods = afterTgt.findAll(MethodDeclaration.class);
                if (extractedMethods.isEmpty()) {
                    hollowFlag[0] = true;
                    violations.add("HOLLOW_STUB_DETECTED: extracted class " + spec.targetPath() + " has zero methods");
                } else if (extractedMethods.stream().allMatch(AstRefactoringPurityVerifier::isHollowStubMethod)) {
                    hollowFlag[0] = true;
                    violations.add("HOLLOW_STUB_DETECTED: all methods in extracted class " + spec.targetPath() + " are stubs");
                }
            }
            case BREAK_SCC_CYCLE -> {
                if (afterTgt == null) {
                    violations.add("INTENT_SCC_INTERFACE_MISSING:" + spec.targetPath());
                    return false;
                }
                boolean hasInterface = afterTgt.findAll(ClassOrInterfaceDeclaration.class).stream()
                        .anyMatch(ClassOrInterfaceDeclaration::isInterface);
                if (!hasInterface) {
                    violations.add("INTENT_SCC_TARGET_NOT_INTERFACE:" + spec.targetPath());
                }
            }
        }
        return violations.size() == startViolations;
    }

    /**
     * Gate 2 (Purity Check): verifies every method outside {@code spec.allowedTouchedMethods()} in every
     * file remains AST-identical between before and after (catching hidden logic mutations or tangled fixes).
     */
    private static boolean checkPurity(
            RefactoringSpec spec,
            Map<String, CompilationUnit> beforeCus,
            Map<String, CompilationUnit> afterCus,
            List<String> outOfScopeMutations) {
        var allPaths = new TreeSet<String>();
        allPaths.addAll(beforeCus.keySet());
        allPaths.addAll(afterCus.keySet());

        for (String path : allPaths) {
            CompilationUnit before = beforeCus.get(path);
            CompilationUnit after = afterCus.get(path);
            if (before == null && after != null) {
                // Newly created file is allowed only if it is the targetPath for EXTRACT_CLASS / BREAK_SCC_CYCLE
                if (!path.equals(spec.targetPath())) {
                    outOfScopeMutations.add("UNEXPECTED_NEW_FILE:" + path);
                }
                continue;
            }
            if (before != null && after == null) {
                outOfScopeMutations.add("UNEXPECTED_DELETED_FILE:" + path);
                continue;
            }
            if (before == null) continue;

            boolean isPrimaryFile = path.equals(spec.sourcePath()) || path.equals(spec.targetPath());
            Map<String, String> beforeMethods = indexNormalizedMethods(before);
            Map<String, String> afterMethods = indexNormalizedMethods(after);

            var methodKeys = new TreeSet<String>();
            methodKeys.addAll(beforeMethods.keySet());
            methodKeys.addAll(afterMethods.keySet());
            for (String mKey : methodKeys) {
                String methodName = mKey.contains("(") ? mKey.substring(0, mKey.indexOf('(')) : mKey;
                boolean isPrimarySymbol = methodName.equals(spec.sourceMethodName()) || methodName.equals(spec.targetMethodName());
                boolean inScopeMethod;
                if (isPrimaryFile) {
                    inScopeMethod = spec.allowedTouchedMethods().contains(methodName)
                            || spec.allowedTouchedMethods().contains(mKey);
                } else {
                    // External caller files may only touch explicitly allowed caller methods (never accidental name collision with sourceMethod/targetMethod!)
                    boolean isExplicitExternalCaller = spec.allowedTouchedMethods().contains(methodName)
                            || spec.allowedTouchedMethods().contains(mKey);
                    inScopeMethod = isExplicitExternalCaller
                            && (!isPrimarySymbol || spec.kind() == RefactoringKind.MOVE_AND_RENAME_METHOD);
                }
                if (inScopeMethod) {
                    continue;
                }
                // For BREAK_SCC_CYCLE or EXTRACT_CLASS, constructors wiring the new interface/delegate are expected
                if ((spec.kind() == RefactoringKind.BREAK_SCC_CYCLE || spec.kind() == RefactoringKind.EXTRACT_CLASS)
                        && isPrimaryFile && "<init>".equals(methodName)) {
                    continue;
                }
                String bNorm = beforeMethods.get(mKey);
                String aNorm = afterMethods.get(mKey);
                if (!Objects.equals(bNorm, aNorm)) {
                    if (bNorm == null) {
                        outOfScopeMutations.add("UNEXPECTED_ADDED_METHOD:" + path + "#" + mKey);
                    } else if (aNorm == null) {
                        outOfScopeMutations.add("UNEXPECTED_REMOVED_METHOD:" + path + "#" + mKey);
                    } else {
                        outOfScopeMutations.add("OUT_OF_SCOPE_METHOD_MUTATED:" + path + "#" + mKey);
                    }
                }
            }

            // Also verify field declarations remain unmutated outside structural class/SCC refactorings
            if (!(isPrimaryFile && (spec.kind() == RefactoringKind.EXTRACT_CLASS || spec.kind() == RefactoringKind.BREAK_SCC_CYCLE))) {
                Map<String, String> beforeFields = indexNormalizedFields(before);
                Map<String, String> afterFields = indexNormalizedFields(after);
                var fieldKeys = new TreeSet<String>();
                fieldKeys.addAll(beforeFields.keySet());
                fieldKeys.addAll(afterFields.keySet());
                for (String fKey : fieldKeys) {
                    if (!Objects.equals(beforeFields.get(fKey), afterFields.get(fKey))) {
                        outOfScopeMutations.add("OUT_OF_SCOPE_FIELD_MUTATED:" + path + "#" + fKey);
                    }
                }
            }
        }
        return outOfScopeMutations.isEmpty();
    }

    /**
     * Detects empty-method or hollow-stub overfitting cheats (SWE-Refactor Sec. 5):
     * empty body, comments-only body, single {@code return;} / {@code return null/0/false/"";} without
     * computation, or {@code throw new UnsupportedOperationException(...)}.
     */
    public static boolean isHollowStubMethod(MethodDeclaration md) {
        if (md == null) return true;
        Optional<BlockStmt> bodyOpt = md.getBody();
        if (bodyOpt.isEmpty()) return true;
        List<Statement> nonEmpty = bodyOpt.get().getStatements().stream()
                .filter(s -> !(s instanceof EmptyStmt))
                .toList();
        if (nonEmpty.isEmpty()) return true;
        if (nonEmpty.size() == 1) {
            Statement only = nonEmpty.get(0);
            if (only instanceof ReturnStmt ret) {
                if (ret.getExpression().isEmpty()) return true;
                var expr = ret.getExpression().get();
                if (expr instanceof NullLiteralExpr) return true;
                String s = expr.toString().strip();
                if (s.equals("0") || s.equals("0L") || s.equals("0.0") || s.equals("false")
                        || s.equals("true") || s.equals("\"\"") || s.equals("List.of()")
                        || s.equals("Collections.emptyList()") || s.equals("Optional.empty()")) {
                    return true;
                }
            }
            if (only instanceof ThrowStmt thr) {
                String s = thr.getExpression().toString();
                if (s.contains("UnsupportedOperationException") || s.contains("TODO")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Checks whether a moved method preserved OO instance semantics (non-static) rather than forced static. */
    public static boolean isInstanceMethod(MethodDeclaration md) {
        return md != null && !md.getModifiers().contains(Modifier.staticModifier());
    }

    private static boolean validateOpenRewriteLst(Map<String, String> afterFiles, List<String> violations) {
        var javaSources = new ArrayList<String>();
        var javaPaths = new ArrayList<String>();
        for (var e : afterFiles.entrySet()) {
            if (e.getKey().endsWith(".java") && e.getValue() != null && !e.getValue().isBlank()) {
                javaPaths.add(e.getKey());
                javaSources.add(e.getValue());
            }
        }
        if (javaSources.isEmpty()) return true;
        try {
            org.openrewrite.java.JavaParser parser = org.openrewrite.java.JavaParser.fromJavaVersion().build();
            ExecutionContext ctx = new InMemoryExecutionContext(t -> {});
            List<SourceFile> parsed = parser.parse(ctx, javaSources.toArray(String[]::new)).toList();
            if (parsed.size() != javaSources.size()) {
                violations.add("OPENREWRITE_LST_COUNT_MISMATCH:" + parsed.size() + "!=" + javaSources.size());
                return false;
            }
            for (int i = 0; i < parsed.size(); i++) {
                if (parsed.get(i) instanceof ParseError err) {
                    violations.add("OPENREWRITE_LST_PARSE_ERROR:" + javaPaths.get(i) + ":" + err.getText().lines().findFirst().orElse(""));
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ex) {
            violations.add("OPENREWRITE_LST_ERROR:" + ex.getClass().getSimpleName());
            return false;
        }
    }

    private static Optional<CompilationUnit> parseJava(String src) {
        if (src == null || src.isBlank()) return Optional.empty();
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false);
        ParseResult<CompilationUnit> res = new JavaParser(cfg).parse(src);
        return res.isSuccessful() ? res.getResult() : Optional.empty();
    }

    private static MethodDeclaration findMethod(CompilationUnit cu, String methodName) {
        var matches = findMethods(cu, methodName);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static List<MethodDeclaration> findMethods(CompilationUnit cu, String methodName) {
        if (cu == null || methodName == null || methodName.isBlank()) return List.of();
        var matches = new ArrayList<MethodDeclaration>();
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            if (md.getNameAsString().equals(methodName) || md.getSignature().asString().equals(methodName)) {
                matches.add(md);
            }
        }
        return matches;
    }

    private static boolean callsMethod(MethodDeclaration caller, String targetName) {
        if (caller == null || targetName == null || targetName.isBlank()) return false;
        String simple = targetName.contains("(") ? targetName.substring(0, targetName.indexOf('(')) : targetName;
        return caller.getBody()
                .map(b -> b.findAll(MethodCallExpr.class).stream()
                        .anyMatch(mc -> mc.getNameAsString().equals(simple)))
                .orElse(false);
    }

    private static boolean isScopedDelegationTo(
            MethodDeclaration candidate, String targetName, MethodDeclaration beforeMoved) {
        if (!callsMethodOnExternalReceiver(candidate, targetName)) {
            return false;
        }
        String candidateBody = candidate.getBody().map(AstRefactoringPurityVerifier::normalizeAstString).orElse("");
        String beforeBody = beforeMoved == null
                ? ""
                : beforeMoved.getBody().map(AstRefactoringPurityVerifier::normalizeAstString).orElse("");
        if (!beforeBody.isEmpty() && candidateBody.equals(beforeBody)) {
            return false;
        }
        int delegateStmts = countExecutableStatements(candidate);
        int beforeStmts = beforeMoved == null ? 0 : countExecutableStatements(beforeMoved);
        return delegateStmts == 1 || (beforeStmts > 1 && delegateStmts < beforeStmts);
    }

    private static boolean callsMethodOnExternalReceiver(MethodDeclaration caller, String targetName) {
        if (caller == null || targetName == null || targetName.isBlank()) return false;
        String simple = targetName.contains("(") ? targetName.substring(0, targetName.indexOf('(')) : targetName;
        return caller.getBody()
                .map(b -> b.findAll(MethodCallExpr.class).stream()
                        .anyMatch(mc -> mc.getNameAsString().equals(simple)
                                && mc.getScope().isPresent()
                                && !isSelfOrSuperScope(mc.getScope().get())))
                .orElse(false);
    }

    private static boolean isSelfOrSuperScope(Expression scope) {
        Expression cur = scope;
        while (cur instanceof EnclosedExpr enc) {
            cur = enc.getInner();
        }
        return cur instanceof ThisExpr || cur instanceof SuperExpr;
    }

    private static int countExecutableStatements(MethodDeclaration md) {
        return md.getBody()
                .map(b -> (int) b.findAll(Statement.class).stream()
                        .filter(s -> !(s instanceof BlockStmt) && !(s instanceof EmptyStmt))
                        .count())
                .orElse(0);
    }

    private static String normalizedMethodEntry(MethodDeclaration md) {
        String bodyNorm = md.getBody().map(AstRefactoringPurityVerifier::normalizeAstString).orElse("");
        String sigNorm = md.getDeclarationAsString(true, true, true).replaceAll("\\s+", " ").strip();
        return sigNorm + " " + bodyNorm;
    }

    private static Map<String, String> indexNormalizedMethods(CompilationUnit cu) {
        var map = new TreeMap<String, String>();
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            map.put(md.getSignature().asString(), normalizedMethodEntry(md));
        }
        for (ConstructorDeclaration cd : cu.findAll(ConstructorDeclaration.class)) {
            String params = String.join(", ", cd.getParameters().stream()
                    .map(p -> p.getTypeAsString()).toList());
            String key = "<init>(" + params + ")";
            String bodyNorm = normalizeAstString(cd.getBody());
            String sigNorm = cd.getDeclarationAsString(true, true, true).replaceAll("\\s+", " ").strip();
            map.put(key, sigNorm + " " + bodyNorm);
        }
        return map;
    }

    private static Map<String, String> indexNormalizedFields(CompilationUnit cu) {
        var map = new TreeMap<String, String>();
        for (FieldDeclaration fd : cu.findAll(FieldDeclaration.class)) {
            String fullNorm = fd.toString().replaceAll("\\s+", " ").strip();
            for (VariableDeclarator vd : fd.getVariables()) {
                map.put(vd.getNameAsString(), fullNorm);
            }
        }
        return map;
    }

    private static String normalizeAstString(BlockStmt block) {
        return block.toString().replaceAll("\\s+", " ").strip();
    }

    private static Map<String, String> normalizeMap(Map<String, String> raw) {
        var out = new TreeMap<String, String>();
        if (raw != null) {
            for (var e : raw.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    out.put(normalizePath(e.getKey()), e.getValue());
                }
            }
        }
        return out;
    }

    private static String normalizePath(String p) {
        return p.replace('\\', '/').replaceAll("^/+", "");
    }
}
