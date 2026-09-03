package com.evolution.analysis.javaparser;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.*;

final class AnnotationExtraction {
    private final Extraction context;
    AnnotationExtraction(Extraction context) { this.context = context; }
    void extract(CompilationUnit unit) {
        for (var annotation : unit.findAll(AnnotationExpr.class)) {
            boolean nested=false;
            for (Node node=annotation.getParentNode().orElse(null);node!=null;node=node.getParentNode().orElse(null))
                if (node instanceof AnnotationExpr || node instanceof AnnotationMemberDeclaration member
                        && member.getDefaultValue().stream().anyMatch(value -> value==annotation || value.isAncestorOf(annotation))) { nested=true; break; }
            String site = annotation.getParentNode().orElse(null) instanceof Type ? "type-syntax"
                    : annotation.getParentNode().orElse(null) instanceof PackageDeclaration ? "package-syntax" : "declaration-syntax";
            if (nested) site="annotation-value";
            Node parent = annotation.getParentNode().orElseThrow();
            if (parent instanceof FieldDeclaration fields) {
                for (var field : fields.getVariables()) context.annotation(annotation,field,site);
                continue;
            }
            Node declaration = null;
            for (Node node=parent; node!=null; node=node.getParentNode().orElse(null)) {
                if (node instanceof ObjectCreationExpr || node instanceof ArrayCreationExpr) break;
                if (node instanceof VariableDeclarator variable && variable.getParentNode().orElse(null) instanceof FieldDeclaration
                        || node instanceof CallableDeclaration<?> || node instanceof CompactConstructorDeclaration || node instanceof TypeDeclaration<?>
                        || node instanceof AnnotationMemberDeclaration || node instanceof TypeParameter || node instanceof LambdaExpr
                        || node instanceof InitializerDeclaration || node instanceof CompilationUnit
                        || node instanceof Parameter parameter && (parameter.getParentNode().orElse(null) instanceof CallableDeclaration<?> || parameter.getParentNode().orElse(null) instanceof RecordDeclaration)) {
                    declaration = node; break;
                }
            }
            context.annotation(annotation,declaration,site,!nested);
        }
    }
}
