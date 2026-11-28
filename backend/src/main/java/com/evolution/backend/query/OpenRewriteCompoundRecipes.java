package com.evolution.backend.query;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.marker.Markers;
import org.openrewrite.tree.ParseError;

import java.util.*;

/**
 * Six deterministic OO & cross-file architectural compound refactoring recipes (WP4.3 — SWE-Refactor)
 * backed by JavaParser AST analysis and OpenRewrite {@code rewrite-java:8.87.7} Lossless Semantic Tree
 * (LST) parse verification.
 *
 * <p>Recipes:
 * <ol>
 *   <li>{@code EXTRACT_METHOD_SAFE} — checks escape variables & single-exit semantics, extracts block cleanly.</li>
 *   <li>{@code MOVE_INSTANCE_METHOD} — moves instance method preserving OO receiver (never forces {@code public static}).</li>
 *   <li>{@code EXTRACT_AND_MOVE_METHOD} — atomic 2-step compound extraction directly into collaborator target class.</li>
 *   <li>{@code MOVE_AND_RENAME_METHOD} — moves + renames method and deterministically updates all call sites.</li>
 *   <li>{@code EXTRACT_CLASS_GOD_DECOMPOSE} — extracts cohesive field+method cluster into new collaborator class.</li>
 *   <li>{@code BREAK_PACKAGE_SCC_CYCLE} — extracts interface in caller package and rewires concrete cyclic reference.</li>
 * </ol>
 */
public final class OpenRewriteCompoundRecipes {

    private OpenRewriteCompoundRecipes() {}

    /** Parameterized request for executing a deterministic OO / compound recipe. */
    public record CompoundRequest(
            JavaRepairRecipes.Recipe recipe,
            String sourcePath,
            String sourceMethodName,
            int startLine,
            int endLine,
            String targetPath,
            String targetClassName,
            String newMethodName,
            String collaboratorFieldName,
            Set<String> clusterFields,
            Set<String> clusterMethods) {
        public CompoundRequest {
            recipe = Objects.requireNonNull(recipe, "recipe");
            sourcePath = Objects.requireNonNull(sourcePath, "sourcePath").replace('\\', '/');
            sourceMethodName = sourceMethodName == null ? "" : sourceMethodName.strip();
            targetPath = targetPath == null ? "" : targetPath.replace('\\', '/');
            targetClassName = targetClassName == null ? "" : targetClassName.strip();
            newMethodName = newMethodName == null ? "" : newMethodName.strip();
            collaboratorFieldName = collaboratorFieldName == null ? "" : collaboratorFieldName.strip();
            clusterFields = clusterFields == null ? Set.of() : Set.copyOf(clusterFields);
            clusterMethods = clusterMethods == null ? Set.of() : Set.copyOf(clusterMethods);
        }
    }

    /** Outcome of executing a deterministic OpenRewrite-verified compound recipe. */
    public record CompoundOutcome(
            boolean applied,
            JavaRepairRecipes.Recipe recipe,
            List<JavaRepairRecipes.FileChange> changes,
            boolean openRewriteLstVerified,
            String reason) {
        public CompoundOutcome {
            recipe = Objects.requireNonNull(recipe, "recipe");
            changes = List.copyOf(changes == null ? List.of() : changes);
            reason = reason == null ? "" : reason;
        }
    }

    /** Executes one of the 6 deterministic WP4.3 compound recipes across the provided source files. */
    public static CompoundOutcome apply(CompoundRequest req, Map<String, String> filesByPath) {
        Objects.requireNonNull(req, "req");
        if (filesByPath == null || filesByPath.isEmpty()) {
            return new CompoundOutcome(false, req.recipe(), List.of(), false, "NO_FILES_PROVIDED");
        }
        var normFiles = new TreeMap<String, String>();
        for (var e : filesByPath.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                normFiles.put(e.getKey().replace('\\', '/'), e.getValue());
            }
        }
        CompoundOutcome raw = switch (req.recipe()) {
            case EXTRACT_METHOD_SAFE -> applyExtractMethodSafe(req, normFiles);
            case MOVE_INSTANCE_METHOD -> applyMoveInstanceMethod(req, normFiles);
            case EXTRACT_AND_MOVE_METHOD -> applyExtractAndMoveMethod(req, normFiles);
            case MOVE_AND_RENAME_METHOD -> applyMoveAndRenameMethod(req, normFiles);
            case EXTRACT_CLASS_GOD_DECOMPOSE -> applyExtractClassGodDecompose(req, normFiles);
            case BREAK_PACKAGE_SCC_CYCLE -> applyBreakPackageSccCycle(req, normFiles);
            default -> new CompoundOutcome(false, req.recipe(), List.of(), false, "NOT_A_COMPOUND_RECIPE");
        };
        if (!raw.applied() || raw.changes().isEmpty()) {
            return raw;
        }
        // Verify every produced Java file parses cleanly into an OpenRewrite 8.87.7 LST
        boolean lstOk = verifyOpenRewriteLst(raw.changes());
        if (!lstOk) {
            return new CompoundOutcome(false, req.recipe(), List.of(), false, "OPENREWRITE_LST_VALIDATION_FAILED");
        }
        return new CompoundOutcome(true, req.recipe(), raw.changes(), true, raw.reason());
    }

    // ─── 1. EXTRACT_METHOD_SAFE ───

    private static CompoundOutcome applyExtractMethodSafe(CompoundRequest req, Map<String, String> files) {
        String src = files.get(req.sourcePath());
        if (src == null || src.isBlank()) {
            return fail(req.recipe(), "SOURCE_FILE_NOT_FOUND");
        }
        Optional<CompilationUnit> cuOpt = parse(src);
        if (cuOpt.isEmpty()) return fail(req.recipe(), "SOURCE_PARSE_FAILED");
        CompilationUnit cu = cuOpt.get();

        MethodDeclaration origin = findMethod(cu, req.sourceMethodName());
        if (origin == null || origin.getBody().isEmpty()) {
            return fail(req.recipe(), "ORIGIN_METHOD_NOT_FOUND");
        }
        BlockStmt body = origin.getBody().get();
        List<Statement> selected = collectSelectedStatements(body, req.startLine(), req.endLine());
        if (selected.isEmpty()) {
            return fail(req.recipe(), "NO_STATEMENTS_IN_LINE_RANGE");
        }
        // Check safety: disallow extracting mid-loop break/continue without enclosing loop inside selected,
        // and disallow partial early return statements inside selected
        for (Statement s : selected) {
            if (containsBareJump(s)) {
                return fail(req.recipe(), "UNSAFE_ESCAPE_CONTROL_FLOW");
            }
        }
        if (containsUnsafeReturn(selected)) {
            return fail(req.recipe(), "UNSAFE_ESCAPE_CONTROL_FLOW");
        }
        // Determine variables defined in origin before selected & used inside selected (parameters),
        // variables defined inside selected & used after selected, AND outer locals reassigned inside selected & used after!
        var declaredBefore = collectDeclaredVarTypesBefore(origin, selected.getFirst());
        var usedInSelected = collectReferencedNames(selected);
        var definedInSelected = collectDeclaredVarTypesIn(selected);
        var assignedInSelected = collectAssignedNamesIn(selected);
        var usedAfterSelected = collectReferencedNamesAfter(body, selected.getLast());

        var escapedVars = new LinkedHashSet<String>();
        for (String v : definedInSelected.keySet()) {
            if (usedAfterSelected.contains(v)) {
                escapedVars.add(v);
            }
        }
        var reassignedEscapes = new LinkedHashSet<String>();
        for (String v : assignedInSelected) {
            if (declaredBefore.containsKey(v) && !definedInSelected.containsKey(v) && usedAfterSelected.contains(v)) {
                escapedVars.add(v);
                reassignedEscapes.add(v);
            }
        }
        if (escapedVars.size() > 1) {
            return fail(req.recipe(), "MULTIPLE_ESCAPE_VARIABLES:" + String.join(",", escapedVars));
        }

        // Build parameter list deterministically (reject unresolved `var` parameter types)
        var paramDecls = new ArrayList<String>();
        var callArgs = new ArrayList<String>();
        for (var entry : declaredBefore.entrySet()) {
            if (usedInSelected.contains(entry.getKey()) || reassignedEscapes.contains(entry.getKey())) {
                if ("var".equals(entry.getValue())) {
                    return fail(req.recipe(), "UNRESOLVED_VAR_TYPE_IN_EXTRACTION:" + entry.getKey());
                }
                paramDecls.add(entry.getValue() + " " + entry.getKey());
                callArgs.add(entry.getKey());
            }
        }

        String newName = req.newMethodName().isEmpty() ? req.sourceMethodName() + "Extracted" : req.newMethodName();
        String nl = src.contains("\r\n") ? "\r\n" : "\n";
        boolean endsWithReturn = selected.getLast() instanceof ReturnStmt;
        String returnType;
        String callStmt;
        String extraReturn = "";
        if (endsWithReturn) {
            returnType = origin.getTypeAsString();
            callStmt = ("void".equals(returnType) ? "" : "return ") + newName + "(" + String.join(", ", callArgs) + ");";
        } else if (escapedVars.size() == 1) {
            String esc = escapedVars.iterator().next();
            boolean isReassignedOuter = reassignedEscapes.contains(esc);
            returnType = isReassignedOuter
                    ? declaredBefore.getOrDefault(esc, "Object")
                    : definedInSelected.getOrDefault(esc, "Object");
            if ("var".equals(returnType)) {
                return fail(req.recipe(), "UNRESOLVED_VAR_TYPE_IN_EXTRACTION:" + esc);
            }
            callStmt = (isReassignedOuter ? (esc + " = ") : (returnType + " " + esc + " = "))
                    + newName + "(" + String.join(", ", callArgs) + ");";
            extraReturn = "        return " + esc + ";" + nl;
        } else {
            returnType = "void";
            callStmt = newName + "(" + String.join(", ", callArgs) + ");";
        }

        String[] lines = src.split("\\R", -1);
        int firstLine = selected.getFirst().getBegin().map(p -> p.line).orElse(req.startLine());
        int lastLine = selected.getLast().getEnd().map(p -> p.line).orElse(req.endLine());
        int methodEndLine = origin.getEnd().map(p -> p.line).orElse(lines.length);
        if (firstLine < 1 || lastLine > lines.length || firstLine > lastLine) {
            return fail(req.recipe(), "INVALID_STATEMENT_BOUNDS");
        }

        String indent = leadingWhitespace(lines[firstLine - 1]);
        var extractedBody = new StringBuilder();
        for (int i = firstLine; i <= lastLine; i++) {
            extractedBody.append(lines[i - 1]).append(nl);
        }
        String modifiers = origin.isStatic() ? "private static " : "private ";
        String extractedMethodText = nl
                + "    " + modifiers + returnType + " " + newName + "(" + String.join(", ", paramDecls) + ") {" + nl
                + extractedBody
                + extraReturn
                + "    }" + nl;

        var out = new StringBuilder();
        boolean appendedExtracted = false;
        for (int i = 1; i <= lines.length; i++) {
            if (i == firstLine) {
                out.append(indent).append(callStmt).append(nl);
            } else if (i > firstLine && i <= lastLine) {
                if (i == methodEndLine && !appendedExtracted) {
                    out.append(extractedMethodText);
                    appendedExtracted = true;
                }
                continue;
            } else {
                out.append(lines[i - 1]);
                if (i < lines.length) out.append(nl);
            }
            if (i == methodEndLine && !appendedExtracted) {
                out.append(extractedMethodText);
                appendedExtracted = true;
            }
        }
        String after = out.toString();
        return new CompoundOutcome(true, req.recipe(),
                List.of(new JavaRepairRecipes.FileChange(req.sourcePath(), src, after, false)),
                true, "EXTRACTED:" + newName);
    }

    // ─── 2. MOVE_INSTANCE_METHOD ───

    private static CompoundOutcome applyMoveInstanceMethod(CompoundRequest req, Map<String, String> files) {
        return moveMethodInternal(req, files, req.sourceMethodName(), false);
    }

    // ─── 4. MOVE_AND_RENAME_METHOD ───

    private static CompoundOutcome applyMoveAndRenameMethod(CompoundRequest req, Map<String, String> files) {
        String newName = req.newMethodName().isEmpty() ? req.sourceMethodName() + "Moved" : req.newMethodName();
        if (newName.equals(req.sourceMethodName())) {
            return fail(req.recipe(), "RENAME_MUST_DIFFER_FROM_SOURCE");
        }
        return moveMethodInternal(req, files, newName, true);
    }

    private static CompoundOutcome moveMethodInternal(
            CompoundRequest req, Map<String, String> files, String targetMethodName, boolean updateCallers) {
        String srcText = files.get(req.sourcePath());
        String tgtText = files.get(req.targetPath());
        if (srcText == null || tgtText == null) {
            return fail(req.recipe(), "SOURCE_OR_TARGET_FILE_NOT_FOUND");
        }
        Optional<CompilationUnit> srcCuOpt = parse(srcText);
        Optional<CompilationUnit> tgtCuOpt = parse(tgtText);
        if (srcCuOpt.isEmpty() || tgtCuOpt.isEmpty()) {
            return fail(req.recipe(), "PARSE_FAILED");
        }
        CompilationUnit srcCu = srcCuOpt.get();
        CompilationUnit tgtCu = tgtCuOpt.get();

        MethodDeclaration moving = findMethod(srcCu, req.sourceMethodName());
        if (moving == null || moving.getBody().isEmpty()) {
            return fail(req.recipe(), "METHOD_NOT_FOUND:" + req.sourceMethodName());
        }
        ClassOrInterfaceDeclaration tgtClass = tgtCu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
        if (tgtClass == null) return fail(req.recipe(), "TARGET_CLASS_NOT_FOUND");

        String nl = srcText.contains("\r\n") ? "\r\n" : "\n";
        String collabField = resolveCollaboratorFieldName(srcCu, tgtClass.getNameAsString(), req.collaboratorFieldName());

        // Preserve OO instance semantics (remove private -> public, NEVER blindly force static!)
        MethodDeclaration movedCopy = moving.clone();
        movedCopy.setName(targetMethodName);
        movedCopy.setPrivate(false);
        movedCopy.setPublic(true);
        // Rewrite `this.<collabField>.x` or `<collabField>.x` inside moved body to direct `this.x`
        if (!collabField.isEmpty()) {
            movedCopy.getBody().ifPresent(b -> {
                for (MethodCallExpr mc : b.findAll(MethodCallExpr.class)) {
                    if (mc.getScope().isPresent() && matchesCollaboratorScope(mc.getScope().get(), collabField)) {
                        mc.removeScope();
                    }
                }
                for (FieldAccessExpr fa : b.findAll(FieldAccessExpr.class)) {
                    if (matchesCollaboratorScope(fa.getScope(), collabField)) {
                        fa.setScope(new ThisExpr());
                    }
                }
            });
        }

        // Insert moved method into target class before closing brace
        String tgtAfter = insertMemberBeforeClassClose(tgtText, movedCopy.toString(), nl);

        // Update source class: either replace body with delegation to collaborator or remove method
        String[] srcLines = srcText.split("\\R", -1);
        int startLine = moving.getBegin().map(p -> p.line).orElse(-1);
        int endLine = moving.getEnd().map(p -> p.line).orElse(-1);
        if (startLine < 1 || endLine > srcLines.length) {
            return fail(req.recipe(), "INVALID_METHOD_LINES");
        }

        String argPass = String.join(", ", moving.getParameters().stream().map(Parameter::getNameAsString).toList());
        String retPrefix = "void".equals(moving.getTypeAsString()) ? "" : "return ";
        String receiverPrefix = collabField.isEmpty() ? "" : (collabField + ".");
        MethodDeclaration delegHeader = moving.clone();
        if (updateCallers) {
            // Keep source forwarding delegate coherent with renamed external callers (compilable!)
            delegHeader.setName(targetMethodName);
        }
        String delegation = "    " + delegHeader.getDeclarationAsString(true, true, true) + " {" + nl
                + "        " + retPrefix + receiverPrefix + targetMethodName + "(" + argPass + ");" + nl
                + "    }";

        var srcOut = new StringBuilder();
        for (int i = 1; i <= srcLines.length; i++) {
            if (i == startLine) {
                if (!collabField.isEmpty()) {
                    srcOut.append(delegation);
                    if (endLine < srcLines.length) srcOut.append(nl);
                }
            } else if (i > startLine && i <= endLine) {
                continue;
            } else {
                srcOut.append(srcLines[i - 1]);
                if (i < srcLines.length) srcOut.append(nl);
            }
        }
        String srcAfter = srcOut.toString();

        var changes = new ArrayList<JavaRepairRecipes.FileChange>();
        changes.add(new JavaRepairRecipes.FileChange(req.sourcePath(), srcText, srcAfter, false));
        changes.add(new JavaRepairRecipes.FileChange(req.targetPath(), tgtText, tgtAfter, false));

        // Update external callers via OpenRewrite 8.87.7 JavaIsoVisitor LST rewrite
        // scoped strictly to matching receiver types (source/target class) & exact parameter arity!
        if (updateCallers) {
            ClassOrInterfaceDeclaration srcClass = srcCu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
            var allowedReceiverTypes = new LinkedHashSet<String>();
            if (srcClass != null) allowedReceiverTypes.add(srcClass.getNameAsString());
            allowedReceiverTypes.add(tgtClass.getNameAsString());
            int expectedArity = moving.getParameters().size();
            for (var e : files.entrySet()) {
                String p = e.getKey();
                if (p.equals(req.sourcePath()) || p.equals(req.targetPath()) || !p.endsWith(".java")) continue;
                String orig = e.getValue();
                String replaced = rewriteCallSitesWithOpenRewriteLst(
                        orig, req.sourceMethodName(), targetMethodName, expectedArity, allowedReceiverTypes);
                if (!replaced.equals(orig)) {
                    changes.add(new JavaRepairRecipes.FileChange(p, orig, replaced, false));
                }
            }
        }
        return new CompoundOutcome(true, req.recipe(), changes, true,
                (updateCallers ? "MOVED_AND_RENAMED:" : "MOVED_INSTANCE:") + targetMethodName);
    }

    // ─── 3. EXTRACT_AND_MOVE_METHOD (Atomic 2-Step Compound) ───

    private static CompoundOutcome applyExtractAndMoveMethod(CompoundRequest req, Map<String, String> files) {
        String srcText = files.get(req.sourcePath());
        String tgtText = files.get(req.targetPath());
        if (srcText == null || tgtText == null) {
            return fail(req.recipe(), "SOURCE_OR_TARGET_FILE_NOT_FOUND");
        }
        Optional<CompilationUnit> srcCuOpt = parse(srcText);
        Optional<CompilationUnit> tgtCuOpt = parse(tgtText);
        if (srcCuOpt.isEmpty() || tgtCuOpt.isEmpty()) return fail(req.recipe(), "PARSE_FAILED");
        CompilationUnit srcCu = srcCuOpt.get();
        CompilationUnit tgtCu = tgtCuOpt.get();

        MethodDeclaration origin = findMethod(srcCu, req.sourceMethodName());
        ClassOrInterfaceDeclaration tgtClass = tgtCu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
        if (origin == null || origin.getBody().isEmpty() || tgtClass == null) {
            return fail(req.recipe(), "ORIGIN_OR_TARGET_NOT_FOUND");
        }
        String collabField = resolveCollaboratorFieldName(srcCu, tgtClass.getNameAsString(), req.collaboratorFieldName());
        if (collabField.isEmpty()) {
            return fail(req.recipe(), "COLLABORATOR_FIELD_NOT_FOUND:" + tgtClass.getNameAsString());
        }

        List<Statement> selected = collectSelectedStatements(origin.getBody().get(), req.startLine(), req.endLine());
        if (selected.isEmpty()) return fail(req.recipe(), "NO_STATEMENTS_IN_LINE_RANGE");
        for (Statement s : selected) {
            if (containsBareJump(s)) return fail(req.recipe(), "UNSAFE_ESCAPE_CONTROL_FLOW");
        }
        if (containsUnsafeReturn(selected)) return fail(req.recipe(), "UNSAFE_ESCAPE_CONTROL_FLOW");

        var declaredBefore = collectDeclaredVarTypesBefore(origin, selected.getFirst());
        var usedInSelected = collectReferencedNames(selected);
        var definedInSelected = collectDeclaredVarTypesIn(selected);
        var assignedInSelected = collectAssignedNamesIn(selected);
        var usedAfterSelected = collectReferencedNamesAfter(origin.getBody().get(), selected.getLast());

        var escapedVars = new LinkedHashSet<String>();
        for (String v : definedInSelected.keySet()) {
            if (usedAfterSelected.contains(v)) escapedVars.add(v);
        }
        var reassignedEscapes = new LinkedHashSet<String>();
        for (String v : assignedInSelected) {
            if (declaredBefore.containsKey(v) && !definedInSelected.containsKey(v) && usedAfterSelected.contains(v)) {
                escapedVars.add(v);
                reassignedEscapes.add(v);
            }
        }
        if (escapedVars.size() > 1) return fail(req.recipe(), "MULTIPLE_ESCAPE_VARIABLES");

        var paramDecls = new ArrayList<String>();
        var callArgs = new ArrayList<String>();
        for (var entry : declaredBefore.entrySet()) {
            if (!entry.getKey().equals(collabField)
                    && (usedInSelected.contains(entry.getKey()) || reassignedEscapes.contains(entry.getKey()))) {
                if ("var".equals(entry.getValue())) {
                    return fail(req.recipe(), "UNRESOLVED_VAR_TYPE_IN_EXTRACTION:" + entry.getKey());
                }
                paramDecls.add(entry.getValue() + " " + entry.getKey());
                callArgs.add(entry.getKey());
            }
        }

        String movedName = req.newMethodName().isEmpty() ? req.sourceMethodName() + "Helper" : req.newMethodName();
        String nl = srcText.contains("\r\n") ? "\r\n" : "\n";
        boolean endsWithReturn = selected.getLast() instanceof ReturnStmt;
        String returnType;
        String callStmt;
        String extraReturn = "";
        if (endsWithReturn) {
            returnType = origin.getTypeAsString();
            callStmt = ("void".equals(returnType) ? "" : "return ") + collabField + "." + movedName
                    + "(" + String.join(", ", callArgs) + ");";
        } else if (escapedVars.size() == 1) {
            String esc = escapedVars.iterator().next();
            boolean isReassignedOuter = reassignedEscapes.contains(esc);
            returnType = isReassignedOuter
                    ? declaredBefore.getOrDefault(esc, "Object")
                    : definedInSelected.getOrDefault(esc, "Object");
            if ("var".equals(returnType)) {
                return fail(req.recipe(), "UNRESOLVED_VAR_TYPE_IN_EXTRACTION:" + esc);
            }
            callStmt = (isReassignedOuter ? (esc + " = ") : (returnType + " " + esc + " = "))
                    + collabField + "." + movedName + "(" + String.join(", ", callArgs) + ");";
            extraReturn = "        return " + esc + ";" + nl;
        } else {
            returnType = "void";
            callStmt = collabField + "." + movedName + "(" + String.join(", ", callArgs) + ");";
        }

        String[] srcLines = srcText.split("\\R", -1);
        int firstLine = selected.getFirst().getBegin().map(p -> p.line).orElse(req.startLine());
        int lastLine = selected.getLast().getEnd().map(p -> p.line).orElse(req.endLine());
        String indent = leadingWhitespace(srcLines[firstLine - 1]);

        // Re-scope collaborator calls inside the extracted block to `this` inside target class
        var extractedBody = new StringBuilder();
        for (int i = firstLine; i <= lastLine; i++) {
            String line = srcLines[i - 1]
                    .replace("this." + collabField + ".", "this.")
                    .replace(collabField + ".", "this.");
            extractedBody.append(line).append(nl);
        }
        String newMethod = "    public " + returnType + " " + movedName + "(" + String.join(", ", paramDecls) + ") {" + nl
                + extractedBody
                + extraReturn
                + "    }";

        var srcOut = new StringBuilder();
        for (int i = 1; i <= srcLines.length; i++) {
            if (i == firstLine) {
                srcOut.append(indent).append(callStmt).append(nl);
            } else if (i > firstLine && i <= lastLine) {
                continue;
            } else {
                srcOut.append(srcLines[i - 1]);
                if (i < srcLines.length) srcOut.append(nl);
            }
        }
        String srcAfter = srcOut.toString();
        String tgtAfter = insertMemberBeforeClassClose(tgtText, newMethod, nl);

        return new CompoundOutcome(true, req.recipe(),
                List.of(
                        new JavaRepairRecipes.FileChange(req.sourcePath(), srcText, srcAfter, false),
                        new JavaRepairRecipes.FileChange(req.targetPath(), tgtText, tgtAfter, false)),
                true, "EXTRACTED_AND_MOVED:" + movedName);
    }

    // ─── 5. EXTRACT_CLASS_GOD_DECOMPOSE ───

    private static CompoundOutcome applyExtractClassGodDecompose(CompoundRequest req, Map<String, String> files) {
        String srcText = files.get(req.sourcePath());
        if (srcText == null || srcText.isBlank()) return fail(req.recipe(), "SOURCE_FILE_NOT_FOUND");
        Optional<CompilationUnit> cuOpt = parse(srcText);
        if (cuOpt.isEmpty()) return fail(req.recipe(), "SOURCE_PARSE_FAILED");
        CompilationUnit cu = cuOpt.get();
        ClassOrInterfaceDeclaration owner = cu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
        if (owner == null || req.clusterMethods().isEmpty()) {
            return fail(req.recipe(), "OWNER_OR_CLUSTER_EMPTY");
        }

        String nl = srcText.contains("\r\n") ? "\r\n" : "\n";
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String newClassName = req.targetClassName().isEmpty() ? owner.getNameAsString() + "Delegate" : req.targetClassName();
        String newClassPath = req.targetPath().isEmpty()
                ? siblingPath(req.sourcePath(), newClassName + ".java")
                : req.targetPath();
        String delegateField = req.collaboratorFieldName().isEmpty()
                ? Character.toLowerCase(newClassName.charAt(0)) + newClassName.substring(1)
                : req.collaboratorFieldName();

        var movedFieldDecls = new ArrayList<FieldDeclaration>();
        var movedMethodDecls = new ArrayList<MethodDeclaration>();
        for (FieldDeclaration fd : owner.getFields()) {
            if (fd.getVariables().stream().anyMatch(v -> req.clusterFields().contains(v.getNameAsString()))) {
                movedFieldDecls.add(fd);
            }
        }
        for (MethodDeclaration md : owner.getMethods()) {
            if (req.clusterMethods().contains(md.getNameAsString())) {
                movedMethodDecls.add(md);
            }
        }
        if (movedMethodDecls.isEmpty()) return fail(req.recipe(), "NO_MATCHING_CLUSTER_METHODS");

        // Build new collaborator class
        var newCls = new StringBuilder();
        if (!pkg.isEmpty()) {
            newCls.append("package ").append(pkg).append(";").append(nl).append(nl);
        }
        for (var imp : cu.getImports()) {
            newCls.append(imp.toString().strip()).append(nl);
        }
        if (!cu.getImports().isEmpty()) newCls.append(nl);
        newCls.append("public class ").append(newClassName).append(" {").append(nl);
        for (FieldDeclaration fd : movedFieldDecls) {
            newCls.append("    ").append(fd.toString().strip()).append(nl);
        }
        if (!movedFieldDecls.isEmpty()) newCls.append(nl);
        for (MethodDeclaration md : movedMethodDecls) {
            MethodDeclaration copy = md.clone();
            copy.setPrivate(false);
            copy.setPublic(true);
            newCls.append("    ").append(copy.toString().replace("\n", nl + "    ")).append(nl).append(nl);
        }
        newCls.append("}").append(nl);

        // Update original God Class: replace moved fields with delegate field and delegate moved methods
        CompilationUnit afterCu = cu.clone();
        ClassOrInterfaceDeclaration afterOwner = afterCu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        for (FieldDeclaration fd : new ArrayList<>(afterOwner.getFields())) {
            if (fd.getVariables().stream().anyMatch(v -> req.clusterFields().contains(v.getNameAsString()))) {
                fd.remove();
            }
        }
        afterOwner.addFieldWithInitializer(newClassName, delegateField,
                new ObjectCreationExpr(null, new com.github.javaparser.ast.type.ClassOrInterfaceType(null, newClassName),
                        new com.github.javaparser.ast.NodeList<>()),
                Modifier.Keyword.PRIVATE, Modifier.Keyword.FINAL);

        for (MethodDeclaration md : afterOwner.getMethods()) {
            if (req.clusterMethods().contains(md.getNameAsString())) {
                String args = String.join(", ", md.getParameters().stream().map(Parameter::getNameAsString).toList());
                String ret = "void".equals(md.getTypeAsString()) ? "" : "return ";
                BlockStmt delegBody = new BlockStmt();
                delegBody.addStatement(ret + delegateField + "." + md.getNameAsString() + "(" + args + ");");
                md.setBody(delegBody);
            }
        }

        return new CompoundOutcome(true, req.recipe(),
                List.of(
                        new JavaRepairRecipes.FileChange(req.sourcePath(), srcText, afterCu.toString(), false),
                        new JavaRepairRecipes.FileChange(newClassPath, "", newCls.toString(), true)),
                true, "EXTRACTED_CLASS:" + newClassName);
    }

    // ─── 6. BREAK_PACKAGE_SCC_CYCLE ───

    private static CompoundOutcome applyBreakPackageSccCycle(CompoundRequest req, Map<String, String> files) {
        String callerText = files.get(req.sourcePath());
        String targetText = files.get(req.targetPath());
        if (callerText == null || targetText == null) return fail(req.recipe(), "CALLER_OR_TARGET_NOT_FOUND");
        Optional<CompilationUnit> callerCuOpt = parse(callerText);
        Optional<CompilationUnit> targetCuOpt = parse(targetText);
        if (callerCuOpt.isEmpty() || targetCuOpt.isEmpty()) return fail(req.recipe(), "PARSE_FAILED");

        CompilationUnit callerCu = callerCuOpt.get();
        CompilationUnit targetCu = targetCuOpt.get();
        ClassOrInterfaceDeclaration targetCls = targetCu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
        if (targetCls == null) return fail(req.recipe(), "TARGET_CLASS_NOT_FOUND");

        String nl = callerText.contains("\r\n") ? "\r\n" : "\n";
        String callerPkg = callerCu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String targetPkg = targetCu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String targetSimple = targetCls.getNameAsString();
        String targetFqn = targetPkg.isEmpty() ? targetSimple : targetPkg + "." + targetSimple;
        String ifaceName = req.targetClassName().isEmpty() ? targetSimple + "Port" : req.targetClassName();
        String ifaceFqn = callerPkg.isEmpty() ? ifaceName : callerPkg + "." + ifaceName;
        String ifacePath = siblingPath(req.sourcePath(), ifaceName + ".java");

        // Build interface containing public non-static methods of targetClass + referenced imports
        var sigBuilder = new StringBuilder();
        for (MethodDeclaration md : targetCls.getMethods()) {
            if (md.isPublic() && !md.isStatic()) {
                sigBuilder.append("    ").append(md.getTypeAsString()).append(" ")
                        .append(md.getSignature().asString()).append(";").append(nl);
            }
        }
        String sigBlock = sigBuilder.toString();
        var iface = new StringBuilder();
        if (!callerPkg.isEmpty()) iface.append("package ").append(callerPkg).append(";").append(nl).append(nl);
        boolean addedImport = false;
        for (var imp : targetCu.getImports()) {
            if (imp.isStatic()) continue;
            String impName = imp.getNameAsString();
            String simpleImp = impName.contains(".") ? impName.substring(impName.lastIndexOf('.') + 1) : impName;
            if (imp.isAsterisk() || sigBlock.matches("(?s).*\\b" + java.util.regex.Pattern.quote(simpleImp) + "\\b.*")) {
                iface.append(imp.toString().strip()).append(nl);
                addedImport = true;
            }
        }
        if (addedImport) iface.append(nl);
        iface.append("public interface ").append(ifaceName).append(" {").append(nl)
                .append(sigBlock)
                .append("}").append(nl);

        // Update caller via OpenRewrite 8.87.7 JavaIsoVisitor LST rewrite:
        // replaces type references to targetSimple with ifaceName without corrupting comments, strings, or `new Target()`!
        String callerAfter = rewriteSccCallerWithOpenRewriteLst(callerText, targetFqn, targetSimple, ifaceName);

        // Update target class: import callerPkg.ifaceName (if cross-package) and add `implements ifaceName`
        // strictly on the actual class declaration (never corrupting Javadoc mentioning `class Target`)
        String targetAfter = targetText;
        if (!callerPkg.isEmpty() && !callerPkg.equals(targetPkg) && !targetAfter.contains("import " + ifaceFqn + ";")) {
            int pkgEndLine = targetCu.getPackageDeclaration().flatMap(Node::getEnd).map(p -> p.line).orElse(1);
            String[] tLines = targetAfter.split("\\R", -1);
            var tb = new StringBuilder();
            for (int i = 1; i <= tLines.length; i++) {
                tb.append(tLines[i - 1]);
                if (i < tLines.length) tb.append(nl);
                if (i == pkgEndLine) {
                    tb.append(nl).append("import ").append(ifaceFqn).append(";").append(nl);
                }
            }
            targetAfter = tb.toString();
        }
        targetAfter = addImplementsToClassWithOpenRewriteLst(targetAfter, targetSimple, ifaceName);

        return new CompoundOutcome(true, req.recipe(),
                List.of(
                        new JavaRepairRecipes.FileChange(ifacePath, "", iface.toString(), true),
                        new JavaRepairRecipes.FileChange(req.sourcePath(), callerText, callerAfter, false),
                        new JavaRepairRecipes.FileChange(req.targetPath(), targetText, targetAfter, false)),
                true, "BROKE_SCC_CYCLE:" + ifaceName);
    }

    // ─── Helpers & OpenRewrite 8.87.7 LST Verifier ───

    private static boolean verifyOpenRewriteLst(List<JavaRepairRecipes.FileChange> changes) {
        var sources = new ArrayList<String>();
        for (var ch : changes) {
            if (ch.path().endsWith(".java") && ch.afterText() != null && !ch.afterText().isBlank()) {
                sources.add(ch.afterText());
            }
        }
        if (sources.isEmpty()) return true;
        try {
            org.openrewrite.java.JavaParser parser = org.openrewrite.java.JavaParser.fromJavaVersion().build();
            ExecutionContext ctx = new InMemoryExecutionContext(t -> {});
            List<SourceFile> parsed = parser.parse(ctx, sources.toArray(String[]::new)).toList();
            if (parsed.size() != sources.size()) return false;
            return parsed.stream().noneMatch(sf -> sf instanceof ParseError);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static CompoundOutcome fail(JavaRepairRecipes.Recipe recipe, String reason) {
        return new CompoundOutcome(false, recipe, List.of(), false, reason);
    }

    private static Optional<CompilationUnit> parse(String src) {
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(true);
        var res = new JavaParser(cfg).parse(src);
        return res.isSuccessful() ? res.getResult() : Optional.empty();
    }

    private static MethodDeclaration findMethod(CompilationUnit cu, String methodName) {
        if (methodName == null || methodName.isBlank()) return null;
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            if (md.getNameAsString().equals(methodName) || md.getSignature().asString().equals(methodName)) {
                return md;
            }
        }
        return null;
    }

    private static List<Statement> collectSelectedStatements(BlockStmt body, int startLine, int endLine) {
        var out = new ArrayList<Statement>();
        for (Statement s : body.getStatements()) {
            int sBegin = s.getBegin().map(p -> p.line).orElse(-1);
            int sEnd = s.getEnd().map(p -> p.line).orElse(-1);
            if (sBegin >= startLine && sEnd <= endLine && sBegin > 0) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean containsBareJump(Statement s) {
        for (BreakStmt bs : s.findAll(BreakStmt.class)) {
            if (!hasEnclosingWithin(bs, s, ForStmt.class, WhileStmt.class, ForEachStmt.class, DoStmt.class, SwitchStmt.class)) {
                return true;
            }
        }
        for (ContinueStmt cs : s.findAll(ContinueStmt.class)) {
            if (!hasEnclosingWithin(cs, s, ForStmt.class, WhileStmt.class, ForEachStmt.class, DoStmt.class)) {
                return true;
            }
        }
        return false;
    }

    @SafeVarargs
    private static boolean hasEnclosingWithin(Node start, Node boundary, Class<? extends Node>... targets) {
        Optional<Node> cur = start.getParentNode();
        while (cur.isPresent()) {
            Node n = cur.get();
            for (Class<? extends Node> t : targets) {
                if (t.isInstance(n)) return true;
            }
            if (n == boundary) break;
            cur = n.getParentNode();
        }
        return false;
    }

    private static boolean containsUnsafeReturn(List<Statement> selected) {
        if (selected.isEmpty()) return false;
        Statement last = selected.getLast();
        for (int i = 0; i < selected.size(); i++) {
            Statement s = selected.get(i);
            for (ReturnStmt rs : s.findAll(ReturnStmt.class)) {
                // Skip returns nested inside lambdas or anonymous classes
                if (hasEnclosingWithin(rs, s, LambdaExpr.class, ObjectCreationExpr.class)) continue;
                // Only an unconditional top-level final ReturnStmt is safely extractable without control-flow split
                if (!(i == selected.size() - 1 && rs == last)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> collectAssignedNamesIn(List<Statement> stmts) {
        var assigned = new LinkedHashSet<String>();
        for (Statement st : stmts) {
            for (AssignExpr ae : st.findAll(AssignExpr.class)) {
                if (ae.getTarget() instanceof NameExpr ne) {
                    assigned.add(ne.getNameAsString());
                }
            }
            for (UnaryExpr ue : st.findAll(UnaryExpr.class)) {
                var op = ue.getOperator();
                if ((op == UnaryExpr.Operator.PREFIX_INCREMENT || op == UnaryExpr.Operator.POSTFIX_INCREMENT
                        || op == UnaryExpr.Operator.PREFIX_DECREMENT || op == UnaryExpr.Operator.POSTFIX_DECREMENT)
                        && ue.getExpression() instanceof NameExpr ne) {
                    assigned.add(ne.getNameAsString());
                }
            }
        }
        return assigned;
    }

    /**
     * Rewrites external method call sites using an OpenRewrite 8.87.7 {@link JavaIsoVisitor} LST pass:
     * only updates {@code J.MethodInvocation} calls whose method name, exact arity, AND declared receiver type
     * match {@code allowedReceiverTypes} — never touching comments, string literals, or unrelated classes.
     */
    private static String rewriteCallSitesWithOpenRewriteLst(
            String javaSource,
            String oldMethodName,
            String newMethodName,
            int expectedArity,
            Set<String> allowedReceiverTypes) {
        try {
            org.openrewrite.java.JavaParser parser = org.openrewrite.java.JavaParser.fromJavaVersion().build();
            ExecutionContext ctx = new InMemoryExecutionContext(t -> {});
            List<SourceFile> parsed = parser.parse(ctx, javaSource).toList();
            if (parsed.size() != 1 || !(parsed.getFirst() instanceof J.CompilationUnit cu)) {
                return javaSource;
            }
            // Collect declared variable/field/parameter simple types inside this compilation unit
            var varSimpleTypes = new HashMap<String, String>();
            new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, ExecutionContext c) {
                    if (vd.getTypeExpression() != null) {
                        String rawType = vd.getTypeExpression().printTrimmed(getCursor());
                        String simple = stripGenericsAndPackage(rawType);
                        for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                            varSimpleTypes.put(nv.getSimpleName(), simple);
                        }
                    }
                    return super.visitVariableDeclarations(vd, c);
                }
            }.visit(cu, ctx);

            J rewritten = new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext c) {
                    J.MethodInvocation updated = super.visitMethodInvocation(mi, c);
                    if (!oldMethodName.equals(updated.getSimpleName())) return updated;
                    int actualArity = updated.getArguments().size() == 1 && updated.getArguments().getFirst() instanceof J.Empty
                            ? 0 : updated.getArguments().size();
                    if (actualArity != expectedArity) return updated;
                    if (updated.getSelect() == null) return updated;
                    String selectText = updated.getSelect().printTrimmed(getCursor());
                    String recvType = resolveSelectSimpleType(selectText, varSimpleTypes);
                    if (recvType == null || !allowedReceiverTypes.contains(recvType)) {
                        return updated;
                    }
                    return updated.withName(updated.getName().withSimpleName(newMethodName));
                }
            }.visit(cu, ctx);
            return rewritten instanceof SourceFile sf ? sf.printAll() : javaSource;
        } catch (RuntimeException ex) {
            return javaSource;
        }
    }

    /**
     * Rewrites caller type references from {@code targetSimple} to {@code ifaceName} via OpenRewrite 8.87.7
     * {@link JavaIsoVisitor}, preserving comments, strings, and {@code new targetSimple(...)} constructor sites.
     */
    private static String rewriteSccCallerWithOpenRewriteLst(
            String callerSource, String targetFqn, String targetSimple, String ifaceName) {
        try {
            org.openrewrite.java.JavaParser parser = org.openrewrite.java.JavaParser.fromJavaVersion().build();
            ExecutionContext ctx = new InMemoryExecutionContext(t -> {});
            List<SourceFile> parsed = parser.parse(ctx, callerSource).toList();
            if (parsed.size() != 1 || !(parsed.getFirst() instanceof J.CompilationUnit cu)) {
                return callerSource;
            }
            boolean[] retainedConcreteNew = new boolean[]{false};
            J rewritten = new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.NewClass visitNewClass(J.NewClass nc, ExecutionContext c) {
                    if (nc.getClazz() != null && targetSimple.equals(stripGenericsAndPackage(nc.getClazz().printTrimmed(getCursor())))) {
                        retainedConcreteNew[0] = true;
                        // Do NOT turn `new Target()` into illegal `new TargetPort()`!
                        return nc;
                    }
                    return super.visitNewClass(nc, c);
                }

                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, ExecutionContext c) {
                    J.VariableDeclarations u = super.visitVariableDeclarations(vd, c);
                    if (u.getTypeExpression() instanceof J.Identifier id && targetSimple.equals(id.getSimpleName())) {
                        return u.withTypeExpression(id.withSimpleName(ifaceName));
                    }
                    return u;
                }

                @Override
                public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration md, ExecutionContext c) {
                    J.MethodDeclaration u = super.visitMethodDeclaration(md, c);
                    if (u.getReturnTypeExpression() instanceof J.Identifier id && targetSimple.equals(id.getSimpleName())) {
                        return u.withReturnTypeExpression(id.withSimpleName(ifaceName));
                    }
                    return u;
                }

                @Override
                public J.ParameterizedType visitParameterizedType(J.ParameterizedType pt, ExecutionContext c) {
                    J.ParameterizedType u = super.visitParameterizedType(pt, c);
                    if (u.getTypeParameters() != null) {
                        var newParams = new ArrayList<org.openrewrite.java.tree.Expression>(u.getTypeParameters().size());
                        boolean changed = false;
                        for (var tp : u.getTypeParameters()) {
                            if (tp instanceof J.Identifier id && targetSimple.equals(id.getSimpleName())) {
                                newParams.add(id.withSimpleName(ifaceName));
                                changed = true;
                            } else {
                                newParams.add(tp);
                            }
                        }
                        if (changed) u = u.withTypeParameters(newParams);
                    }
                    return u;
                }
            }.visit(cu, ctx);
            if (!(rewritten instanceof J.CompilationUnit rewrittenCu)) {
                return callerSource;
            }
            if (!retainedConcreteNew[0]) {
                var filteredImports = rewrittenCu.getImports().stream()
                        .filter(imp -> !targetFqn.equals(imp.getTypeName()))
                        .toList();
                rewrittenCu = rewrittenCu.withImports(filteredImports);
            }
            return rewrittenCu.printAll();
        } catch (RuntimeException ex) {
            return callerSource;
        }
    }

    /** Adds {@code implements ifaceName} cleanly on the target {@code J.ClassDeclaration} via OpenRewrite LST. */
    private static String addImplementsToClassWithOpenRewriteLst(
            String targetSource, String targetSimple, String ifaceName) {
        try {
            org.openrewrite.java.JavaParser parser = org.openrewrite.java.JavaParser.fromJavaVersion().build();
            ExecutionContext ctx = new InMemoryExecutionContext(t -> {});
            List<SourceFile> parsed = parser.parse(ctx, targetSource).toList();
            if (parsed.size() != 1 || !(parsed.getFirst() instanceof J.CompilationUnit cu)) {
                return targetSource;
            }
            J rewritten = new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, ExecutionContext c) {
                    if (!targetSimple.equals(cd.getSimpleName())) {
                        return super.visitClassDeclaration(cd, c);
                    }
                    List<TypeTree> existing = cd.getImplements();
                    if (existing != null && existing.stream().anyMatch(t -> ifaceName.equals(t.printTrimmed(getCursor())))) {
                        return cd;
                    }
                    J.Identifier ifaceIdent = new J.Identifier(
                            Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, List.of(), ifaceName, null, null);
                    var nextImpl = new ArrayList<TypeTree>();
                    if (existing != null) nextImpl.addAll(existing);
                    nextImpl.add(ifaceIdent);
                    return cd.withImplements(nextImpl);
                }
            }.visit(cu, ctx);
            return rewritten instanceof SourceFile sf ? sf.printAll() : targetSource;
        } catch (RuntimeException ex) {
            return targetSource;
        }
    }

    private static String resolveSelectSimpleType(String selectText, Map<String, String> varSimpleTypes) {
        if (selectText == null || selectText.isBlank()) return null;
        String clean = selectText.startsWith("this.") ? selectText.substring(5) : selectText;
        if (varSimpleTypes.containsKey(clean)) {
            return varSimpleTypes.get(clean);
        }
        // Static class receiver or direct simple type name (starts with uppercase)
        if (!clean.isEmpty() && Character.isUpperCase(clean.charAt(0)) && !clean.contains(".")) {
            return clean;
        }
        return null;
    }

    private static String stripGenericsAndPackage(String rawType) {
        if (rawType == null) return "";
        String t = rawType.strip();
        int lt = t.indexOf('<');
        if (lt >= 0) t = t.substring(0, lt);
        int br = t.indexOf('[');
        if (br >= 0) t = t.substring(0, br);
        int dot = t.lastIndexOf('.');
        if (dot >= 0) t = t.substring(dot + 1);
        return t.strip();
    }

    private static Map<String, String> collectDeclaredVarTypesBefore(MethodDeclaration origin, Statement firstSelected) {
        var map = new LinkedHashMap<String, String>();
        for (Parameter p : origin.getParameters()) {
            map.put(p.getNameAsString(), p.getTypeAsString());
        }
        int cutLine = firstSelected.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);
        origin.getBody().ifPresent(body -> {
            for (Statement st : body.getStatements()) {
                int line = st.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);
                if (line >= cutLine) break;
                for (VariableDeclarator vd : st.findAll(VariableDeclarator.class)) {
                    map.put(vd.getNameAsString(), vd.getTypeAsString());
                }
            }
        });
        return map;
    }

    private static Map<String, String> collectDeclaredVarTypesIn(List<Statement> stmts) {
        var map = new LinkedHashMap<String, String>();
        for (Statement st : stmts) {
            for (VariableDeclarator vd : st.findAll(VariableDeclarator.class)) {
                map.put(vd.getNameAsString(), vd.getTypeAsString());
            }
        }
        return map;
    }

    private static Set<String> collectReferencedNames(List<Statement> stmts) {
        var names = new LinkedHashSet<String>();
        for (Statement st : stmts) {
            for (NameExpr ne : st.findAll(NameExpr.class)) {
                names.add(ne.getNameAsString());
            }
        }
        return names;
    }

    private static Set<String> collectReferencedNamesAfter(BlockStmt body, Statement lastSelected) {
        var names = new LinkedHashSet<String>();
        int afterLine = lastSelected.getEnd().map(p -> p.line).orElse(Integer.MAX_VALUE);
        for (Statement st : body.getStatements()) {
            int line = st.getBegin().map(p -> p.line).orElse(-1);
            if (line > afterLine) {
                for (NameExpr ne : st.findAll(NameExpr.class)) {
                    names.add(ne.getNameAsString());
                }
            }
        }
        return names;
    }

    private static String resolveCollaboratorFieldName(CompilationUnit srcCu, String targetClassName, String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        for (FieldDeclaration fd : srcCu.findAll(FieldDeclaration.class)) {
            if (fd.getCommonType().asString().equals(targetClassName)
                    || fd.getCommonType().asString().endsWith("." + targetClassName)) {
                return fd.getVariables().isEmpty() ? "" : fd.getVariable(0).getNameAsString();
            }
        }
        return "";
    }

    private static boolean matchesCollaboratorScope(Expression scope, String fieldName) {
        if (scope instanceof NameExpr ne) return ne.getNameAsString().equals(fieldName);
        if (scope instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
            return fa.getNameAsString().equals(fieldName);
        }
        return false;
    }

    private static String insertMemberBeforeClassClose(String classSource, String memberCode, String nl) {
        int lastBrace = classSource.lastIndexOf('}');
        if (lastBrace < 0) return classSource + nl + memberCode + nl;
        return classSource.substring(0, lastBrace) + nl + memberCode + nl + classSource.substring(lastBrace);
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    private static String siblingPath(String path, String fileName) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash + 1) + fileName : fileName;
    }
}
