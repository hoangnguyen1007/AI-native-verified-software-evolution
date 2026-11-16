package com.evolution.backend.query;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.marker.Markers;
import java.util.List;

/** Removes the sole unused constructor parameter in the registered demo source. */
public final class DemoRepairRecipe extends Recipe {
    @Override public String getDisplayName() { return "Remove the demo client's unused Store injection"; }
    @Override public String getDescription() { return "Removes the unused Store parameter from the pinned empty Client constructor."; }

    @Override public JavaIsoVisitor<ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {
            @Override public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration clazz, ExecutionContext ctx) {
                var unit = getCursor().firstEnclosing(J.CompilationUnit.class);
                if (unit == null || unit.getPackageDeclaration() == null
                        || !unit.getPackageDeclaration().getExpression().printTrimmed().equals("app")
                        || !unit.getImports().isEmpty() || unit.getClasses().size() != 1
                        || !clazz.getSimpleName().equals("Client") || clazz.getBody().getStatements().size() != 1) return clazz;
                return super.visitClassDeclaration(clazz,ctx);
            }

            @Override public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                if (!method.isConstructor() || !method.getSimpleName().equals("Client")
                        || method.getBody() == null || !method.getBody().getStatements().isEmpty()
                        || method.getParameters().size() != 1
                        || !method.getParameters().getFirst().printTrimmed().equals("Store store")) return method;
                var empty = new J.Empty(Tree.randomId(),Space.EMPTY,Markers.EMPTY);
                var type = method.getMethodType();
                return method.withParameters(List.of(empty)).withMethodType(type == null ? null
                        : type.withParameterTypes(List.of()).withParameterNames(List.of()));
            }
        };
    }
}
