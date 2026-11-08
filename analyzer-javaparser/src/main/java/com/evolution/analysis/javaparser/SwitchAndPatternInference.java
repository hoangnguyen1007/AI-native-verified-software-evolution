package com.evolution.analysis.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adapter-local inference for Java 17-21 SwitchExpr result types and flow-scoped / guarded
 * TypePatternExpr bindings. Original AST nodes and spans remain completely unchanged.
 */
final class SwitchAndPatternInference {
    static final DataKey<TypePatternExpr> PATTERN_VAR = new DataKey<>() {};

    private SwitchAndPatternInference() {}

    static void prepare(CompilationUnit unit) {
        preparePatternVariables(unit);
        prepareSwitchExpressions(unit);
    }

    private static void preparePatternVariables(CompilationUnit unit) {
        // 1. SwitchEntry type patterns (including guards and statements)
        for (SwitchEntry entry : unit.findAll(SwitchEntry.class)) {
            for (TypePatternExpr pattern : entry.findAll(TypePatternExpr.class)) {
                if (!isDirectSwitchLabelPattern(entry, pattern)) continue;
                bindPatternInScope(pattern, entry);
            }
        }
        // 2. Positive instanceof scopes (IfStmt then-branch, BinaryExpr AND rhs, ConditionalExpr then-expr)
        for (InstanceOfExpr instanceOf : unit.findAll(InstanceOfExpr.class)) {
            if (instanceOf.getPattern().isEmpty()) continue;
            for (TypePatternExpr pattern : instanceOf.getPattern().orElseThrow().findAll(TypePatternExpr.class)) {
                bindPositiveInstanceOf(instanceOf, pattern);
            }
        }
        // 3. Early-exit negated instanceof in BlockStmt: if (!(x instanceof T v)) { return/throw/break/continue; }
        for (BlockStmt block : unit.findAll(BlockStmt.class)) {
            var statements = block.getStatements();
            for (int i = 0; i < statements.size(); i++) {
                if (!(statements.get(i) instanceof IfStmt ifStmt) || ifStmt.getElseStmt().isPresent()) continue;
                if (!alwaysAbrupt(ifStmt.getThenStmt())) continue;
                List<TypePatternExpr> negatedPatterns = extractNegatedPatterns(ifStmt.getCondition());
                if (negatedPatterns.isEmpty()) continue;
                for (TypePatternExpr pattern : negatedPatterns) {
                    try {
                        ResolvedType resolved = pattern.getType().resolve();
                        for (int j = i + 1; j < statements.size(); j++) {
                            bindTypeToNames(statements.get(j), pattern, resolved);
                        }
                    } catch (RuntimeException ignored) {}
                }
            }
        }
    }

    private static boolean isDirectSwitchLabelPattern(SwitchEntry entry, TypePatternExpr pattern) {
        for (Node current = pattern; current != null && current != entry; current = current.getParentNode().orElse(null)) {
            if (current instanceof Statement) return false;
        }
        return true;
    }

    private static void bindPositiveInstanceOf(InstanceOfExpr instanceOf, TypePatternExpr pattern) {
        try {
            ResolvedType resolved = pattern.getType().resolve();
            for (Node current = instanceOf; current != null; current = current.getParentNode().orElse(null)) {
                Node parent = current.getParentNode().orElse(null);
                if (parent instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.AND && binary.getLeft() == current) {
                    bindTypeToNames(binary.getRight(), pattern, resolved);
                } else if (parent instanceof ConditionalExpr cond && cond.getCondition() == current && !isNegatedBetween(instanceOf, current)) {
                    bindTypeToNames(cond.getThenExpr(), pattern, resolved);
                    break;
                } else if (parent instanceof IfStmt ifStmt && ifStmt.getCondition() == current && !isNegatedBetween(instanceOf, current)) {
                    bindTypeToNames(ifStmt.getThenStmt(), pattern, resolved);
                    break;
                } else if (parent instanceof Statement) {
                    break;
                }
            }
        } catch (RuntimeException ignored) {}
    }

    private static boolean isNegatedBetween(Node inner, Node outer) {
        for (Node cur = inner; cur != null && cur != outer.getParentNode().orElse(null); cur = cur.getParentNode().orElse(null)) {
            if (cur instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                return true;
            }
        }
        return false;
    }

    private static List<TypePatternExpr> extractNegatedPatterns(Expression condition) {
        Expression unwrapped = condition;
        while (unwrapped instanceof EnclosedExpr enclosed) unwrapped = enclosed.getInner();
        if (unwrapped instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            Expression inner = unary.getExpression();
            while (inner instanceof EnclosedExpr enclosed) inner = enclosed.getInner();
            if (inner instanceof InstanceOfExpr instanceOf && instanceOf.getPattern().isPresent()) {
                return instanceOf.getPattern().orElseThrow().findAll(TypePatternExpr.class);
            }
        }
        return List.of();
    }

    private static boolean alwaysAbrupt(Statement stmt) {
        if (stmt instanceof ReturnStmt || stmt instanceof ThrowStmt || stmt instanceof BreakStmt || stmt instanceof ContinueStmt || stmt instanceof YieldStmt) {
            return true;
        }
        if (stmt instanceof BlockStmt block && !block.getStatements().isEmpty()) {
            return alwaysAbrupt(block.getStatements().getLast().orElseThrow());
        }
        return false;
    }

    private static void bindPatternInScope(TypePatternExpr pattern, Node scope) {
        try {
            ResolvedType resolved = pattern.getType().resolve();
            bindTypeToNames(scope, pattern, resolved);
        } catch (RuntimeException ignored) {}
    }

    private static void bindTypeToNames(Node scope, TypePatternExpr pattern, ResolvedType resolved) {
        String varName = pattern.getNameAsString();
        for (NameExpr name : scope.findAll(NameExpr.class)) {
            if (!name.getNameAsString().equals(varName)) continue;
            name.setData(PATTERN_VAR, pattern);
            cacheResolvedType(name, resolved);
        }
    }

    private static void prepareSwitchExpressions(CompilationUnit unit) {
        unit.walk(Node.TreeTraversal.POSTORDER, node -> {
            if (!(node instanceof SwitchExpr switchExpr)) return;
            try {
                ResolvedType existing = switchExpr.calculateResolvedType();
                if (existing != null && !existing.isVoid() && !existing.isNull()) return;
            } catch (RuntimeException ignored) {}
            try {
                Optional<ResolvedType> inferred = inferSwitchType(switchExpr);
                if (inferred.isPresent()) {
                    cacheResolvedType(switchExpr, inferred.orElseThrow());
                    Node parent = switchExpr.getParentNode().orElse(null);
                    while (parent instanceof EnclosedExpr enclosed) {
                        cacheResolvedType(enclosed, inferred.orElseThrow());
                        parent = enclosed.getParentNode().orElse(null);
                    }
                }
            } catch (RuntimeException ignored) {}
        });
    }

    private static Optional<ResolvedType> inferSwitchType(SwitchExpr switchExpr) {
        List<ResolvedType> armTypes = new ArrayList<>();
        for (SwitchEntry entry : switchExpr.getEntries()) {
            if (entry.getStatements().size() == 1 && entry.getStatement(0) instanceof ExpressionStmt exprStmt) {
                try {
                    ResolvedType t = exprStmt.getExpression().calculateResolvedType();
                    if (!t.isNull() && !t.isVoid()) armTypes.add(t);
                } catch (RuntimeException ignored) {}
            } else {
                for (YieldStmt yieldStmt : entry.findAll(YieldStmt.class)) {
                    if (yieldStmt.findAncestor(SwitchExpr.class).orElse(null) != switchExpr) continue;
                    try {
                        ResolvedType t = yieldStmt.getExpression().calculateResolvedType();
                        if (!t.isNull() && !t.isVoid()) armTypes.add(t);
                    } catch (RuntimeException ignored) {}
                }
            }
        }
        for (ResolvedType candidate : armTypes) {
            if (armTypes.stream().allMatch(other -> isCompatible(candidate, other))) {
                return Optional.of(candidate);
            }
        }
        if (!armTypes.isEmpty()) {
            return Optional.of(armTypes.getFirst());
        }
        return contextualSwitchTargetType(switchExpr);
    }

    private static boolean isCompatible(ResolvedType target, ResolvedType source) {
        try {
            return target.equals(source) || target.isAssignableBy(source);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Optional<ResolvedType> contextualSwitchTargetType(Expression expr) {
        Node parent = expr.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr) parent = parent.getParentNode().orElse(null);
        try {
            if (parent instanceof VariableDeclarator varDecl && !varDecl.getType().isVarType()) {
                return Optional.of(varDecl.getType().resolve());
            }
            if (parent instanceof ReturnStmt) {
                var method = parent.findAncestor(MethodDeclaration.class);
                if (method.isPresent() && !method.orElseThrow().getType().isVoidType()) {
                    return Optional.of(method.orElseThrow().getType().resolve());
                }
            }
            if (parent instanceof AssignExpr assign && assign.getValue() == expr) {
                return Optional.of(assign.getTarget().calculateResolvedType());
            }
        } catch (RuntimeException ignored) {}
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    static void cacheResolvedType(Expression node, ResolvedType resolvedType) {
        try {
            var probe = new BooleanLiteralExpr(true);
            node.getParentNode().ifPresent(probe::setParentNode);
            probe.calculateResolvedType();
            for (var key : new ArrayList<>(probe.getDataKeys())) {
                if (key.getClass().getEnclosingClass() == com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.class) {
                    node.setData((DataKey<ResolvedType>) key, resolvedType);
                }
            }
        } catch (RuntimeException ignored) {}
    }
}
