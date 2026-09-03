package com.evolution.analysis.javaparser;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import java.util.*;
import java.util.function.Supplier;

/** Field binding and access mode are separate: an array-element write only reads its array field. */
final class FieldExtraction {
    static final Set<String> CATEGORIES = Set.of("reads-field", "writes-field");
    private final Extraction context;
    FieldExtraction(Extraction context) { this.context = context; }

    void extract(CompilationUnit unit) {
        for (var node : unit.stream().filter(n -> n instanceof FieldAccessExpr || n instanceof NameExpr).toList()) {
            var expression = (Expression)node;
            if (expression instanceof FieldAccessExpr access && arrayLength(access)) {
                emit(expression,() -> { throw new UnsupportedOperationException("Array length has no declared field entity"); });
                continue;
            }
            ResolvedValueDeclaration value;
            try { value = expression instanceof FieldAccessExpr access ? access.resolve() : ((NameExpr)expression).resolve(); }
            catch (RuntimeException failure) {
                // Dotted names can contain package/type qualifiers, which are not evaluated field reads.
                if (!hasValueRoot(expression) && (typeName(expression) || qualifierFragment(expression))) continue;
                if (expression instanceof FieldAccessExpr) emit(expression,() -> { throw failure; });
                else for (var mode : modes(expression)) context.unmapped(expression,mode,Extraction.failureStatus(failure),"java.unclassified-name");
                continue;
            }
            if (value.isField() || value.isEnumConstant()) emit(expression,() -> value);
            // Parameters, locals, pattern variables and types deliberately have no field relationship.
        }
    }
    private void emit(Expression expression, Supplier<ResolvedValueDeclaration> target) {
        for (var mode : modes(expression))
            context.observe(expression,mode,() -> context.field(target.get()),() -> context.fieldOwner(expression));
    }
    private boolean arrayLength(FieldAccessExpr access) {
        if (!access.getNameAsString().equals("length")) return false;
        try { return access.getScope().calculateResolvedType().isArray(); }
        catch (RuntimeException failure) { return false; } // Preserve the actual binding failure below.
    }
    private boolean typeName(Expression expression) {
        var name = dottedName(expression);
        return name.isPresent() && context.isTypeName(expression,name.orElseThrow());
    }
    private static boolean qualifierFragment(Expression expression) {
        return expression.getParentNode().orElse(null) instanceof FieldAccessExpr parent
                && parent.getScope() == expression && dottedName(expression).isPresent();
    }
    private static Optional<String> dottedName(Expression expression) {
        if (expression instanceof NameExpr name) return Optional.of(name.getNameAsString());
        if (expression instanceof FieldAccessExpr field)
            return dottedName(field.getScope()).map(scope -> scope + "." + field.getNameAsString());
        return Optional.empty();
    }
    private static boolean hasValueRoot(Expression expression) {
        Expression root = expression;
        while (root instanceof FieldAccessExpr field) root = field.getScope();
        if (!(root instanceof NameExpr name)) return true;
        try { return !name.resolve().isType(); }
        catch (RuntimeException failure) { return false; }
    }
    private static List<String> modes(Expression expression) {
        Node operand = expression;
        while (operand.getParentNode().orElse(null) instanceof EnclosedExpr enclosed) operand = enclosed;
        Node parent = operand.getParentNode().orElse(null);
        if (parent instanceof AssignExpr assignment && assignment.getTarget() == operand)
            return assignment.getOperator() == AssignExpr.Operator.ASSIGN ? List.of("writes-field") : List.of("reads-field","writes-field");
        if (parent instanceof UnaryExpr unary && switch (unary.getOperator()) {
            case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> true;
            default -> false;
        }) return List.of("reads-field","writes-field");
        return List.of("reads-field");
    }
}
