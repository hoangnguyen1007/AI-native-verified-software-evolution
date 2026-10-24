package com.evolution.analysis.javaparser;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import java.util.*;

/** Named types used as expression qualifiers; imports remain outside the M2 occurrence catalog. */
final class ExpressionTypeExtraction {
    private final Extraction context;
    ExpressionTypeExtraction(Extraction context){this.context=context;}
    void extract(CompilationUnit unit){
        for(var expression:unit.stream().filter(node->node instanceof NameExpr||node instanceof FieldAccessExpr).map(Expression.class::cast).toList()){
            var name=dotted(expression);if(name.isEmpty())continue;
            Expression root=expression;while(root instanceof FieldAccessExpr access)root=access.getScope();
            if(!(root instanceof NameExpr first))continue;
            var terminal=expression instanceof NameExpr simple?simple.getName():((FieldAccessExpr)expression).getName();
            if(expression instanceof FieldAccessExpr access){
                try{access.resolve();continue;}
                catch(UnsolvedSymbolException noField){/* A qualified nested type can still bind below. */}
                catch(RuntimeException failure){context.unmapped(terminal,"type-uses",Extraction.failureStatus(failure),"java.expression-type-classification");continue;}
            }
            // A value in expression scope wins over a same-spelled type, even when a later field is unresolved.
            try{first.resolve();continue;}
            catch(UnsolvedSymbolException noValue){/* A type or package qualifier can still bind below. */}
            catch(RuntimeException failure){context.unmapped(terminal,"type-uses",Extraction.failureStatus(failure),"java.expression-type-classification");continue;}
            ResolvedTypeDeclaration type;
            try{type=context.resolveTypeName(expression,name.orElseThrow());}
            catch(UnsolvedSymbolException noType){continue;}
            catch(RuntimeException failure){context.unmapped(terminal,"type-uses",Extraction.failureStatus(failure),"java.expression-type-classification");continue;}
            context.observe(terminal,"type-uses",()->context.typeEntity(type),()->context.typeUseOwner(expression));
        }
    }
    private static Optional<String> dotted(Expression expression){
        if(expression instanceof NameExpr name)return Optional.of(name.getNameAsString());
        if(expression instanceof FieldAccessExpr access)return dotted(access.getScope()).map(scope->scope+"."+access.getNameAsString());
        return Optional.empty();
    }
}
