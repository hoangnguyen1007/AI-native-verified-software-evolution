package com.evolution.analysis.javaparser;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import java.util.*;

/** Bounded String-only constant folding; never executes an initializer or reads
 * runtime configuration. Unknown and mutable values stay unknown. */
final class HttpClientConstants {
    private HttpClientConstants() {}
    static String uri(Expression expression) {
        try{return new Evaluation().value(expression,0).orElse("");}
        catch(RuntimeException unknown){if(unknown instanceof java.util.concurrent.CancellationException)throw unknown;return "";}
    }
    private static final class Evaluation {
    private final Set<VariableDeclarator> visiting=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<VariableDeclarator,Optional<String>> memo=new IdentityHashMap<>();
    private int remaining=4096;
    private Optional<String> value(Expression expression,int depth) {
        if(depth>128||--remaining<0)return Optional.empty();
        if(expression instanceof StringLiteralExpr literal)return bounded(literal.asString());
        if(expression instanceof TextBlockLiteralExpr literal)return bounded(literal.asString());
        if(expression instanceof EnclosedExpr enclosed)return value(enclosed.getInner(),depth+1);
        if(expression instanceof BinaryExpr binary&&binary.getOperator()==BinaryExpr.Operator.PLUS) {
            var left=value(binary.getLeft(),depth+1);var right=left.isEmpty()?Optional.<String>empty():value(binary.getRight(),depth+1);
            return left.isPresent()&&right.isPresent()?bounded(left.orElseThrow()+right.orElseThrow()):Optional.empty();
        }
        var resolved=expression instanceof NameExpr name?name.resolve():expression instanceof FieldAccessExpr field?field.resolve():null;
        if(resolved==null)return Optional.empty();Node ast=resolved.toAst().orElse(null);
        VariableDeclarator variable;
        if(ast instanceof VariableDeclarator candidate)variable=candidate;
        else if(ast instanceof FieldDeclaration field)variable=field.getVariables().stream().filter(v->v.getNameAsString().equals(resolved.getName())).findFirst().orElse(null);
        else if(ast instanceof VariableDeclarationExpr local)variable=local.getVariables().stream().filter(v->v.getNameAsString().equals(resolved.getName())).findFirst().orElse(null);
        else variable=null;
        if(variable==null)return Optional.empty();if(memo.containsKey(variable))return memo.get(variable);
        if(!visiting.add(variable))return Optional.empty();
        try {
            Node parent=variable.getParentNode().orElse(null);
            boolean fixed=parent instanceof VariableDeclarationExpr local&&local.isFinal()
                    ||parent instanceof FieldDeclaration field&&(field.isFinal()||field.getParentNode().filter(n->n instanceof ClassOrInterfaceDeclaration type&&type.isInterface()).isPresent());
            if(!fixed||!variable.getType().resolve().describe().equals("java.lang.String"))return Optional.empty();
            var result=variable.getInitializer().flatMap(initializer->value(initializer,depth+1));memo.put(variable,result);return result;
        }finally{visiting.remove(variable);}
    }
    }
    private static Optional<String> bounded(String value){return value.length()<=16_384?Optional.of(value):Optional.empty();}
}
