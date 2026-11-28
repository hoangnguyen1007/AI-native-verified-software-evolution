package com.evolution.analysis.javaparser;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded String-only constant folding; never executes an initializer or reads
 * runtime configuration. Unknown and mutable values stay unknown. */
final class HttpClientConstants {
    private static final Pattern PRINTF_TOKEN = Pattern.compile(
            "%(?:\\d+\\$)?[-+# 0,(]*\\d*(?:\\.\\d+)?([%sdifbBhHcCoxXeEgGaAtTn])");

    private HttpClientConstants() {}
    static String uri(Expression expression) {
        try{return new Evaluation().value(expression,0).orElse("");}
        catch(RuntimeException unknown){if(unknown instanceof java.util.concurrent.CancellationException)throw unknown;return "";}
    }
    /** Syntax-only constant folding for --build=off Java source extraction using pre-collected
     * effectively-final local/private constants, without invoking symbol resolution. */
    static String syntaxUri(Expression expression, Map<String, String> constants) {
        if (expression == null) return "";
        try {
            return syntaxValue(expression, constants == null ? Map.of() : constants, 0).orElse("");
        } catch (RuntimeException unknown) {
            if (unknown instanceof java.util.concurrent.CancellationException) throw unknown;
            return "";
        }
    }
    private static Optional<String> syntaxValue(Expression expression, Map<String, String> constants, int depth) {
        if (depth > 64 || expression == null) return Optional.empty();
        if (expression instanceof StringLiteralExpr literal) return bounded(literal.asString());
        if (expression instanceof TextBlockLiteralExpr literal) return bounded(literal.asString());
        if (expression instanceof EnclosedExpr enclosed) return syntaxValue(enclosed.getInner(), constants, depth + 1);
        if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            var left = syntaxValue(binary.getLeft(), constants, depth + 1);
            if (left.isEmpty()) return Optional.empty();
            String leftStr = left.orElseThrow();
            var right = syntaxValue(binary.getRight(), constants, depth + 1);
            if (right.isPresent()) {
                return bounded(leftStr + right.orElseThrow());
            }
            if (isUriOrPathPrefix(leftStr)) {
                return bounded(leftStr + dynamicOrPrimitiveSegment(binary.getRight()));
            }
            return Optional.empty();
        }
        if (expression instanceof NameExpr name) {
            String v = constants.get(name.getNameAsString());
            return v != null ? bounded(v) : Optional.empty();
        }
        if (expression instanceof FieldAccessExpr field) {
            if (field.getScope() instanceof ThisExpr || field.getScope() instanceof NameExpr) {
                String scoped = constants.get(field.getNameAsString());
                if (scoped != null) return bounded(scoped);
                String qualified = constants.get(field.toString());
                if (qualified != null) return bounded(qualified);
            }
            return Optional.empty();
        }
        if (expression instanceof MethodCallExpr call) {
            return foldFormatCall(call, arg -> syntaxValue(arg, constants, depth + 1));
        }
        return Optional.empty();
    }
    private static Optional<String> foldFormatCall(MethodCallExpr call, java.util.function.Function<Expression, Optional<String>> resolver) {
        if (!call.getNameAsString().equals("format") || call.getArguments().isEmpty()) return Optional.empty();
        boolean stringScope = call.getScope().map(s -> {
            String t = s.toString();
            return t.equals("String") || t.equals("java.lang.String");
        }).orElse(true);
        if (!stringScope) return Optional.empty();
        int templateIdx = 0;
        if (call.getArguments().size() >= 2) {
            String first = call.getArgument(0).toString();
            if (first.startsWith("Locale.") || first.startsWith("java.util.Locale.")) {
                templateIdx = 1;
            }
        }
        if (templateIdx >= call.getArguments().size()) return Optional.empty();
        var templateOpt = resolver.apply(call.getArgument(templateIdx));
        if (templateOpt.isEmpty()) return Optional.empty();
        String template = templateOpt.orElseThrow();
        Matcher m = PRINTF_TOKEN.matcher(template);
        var sb = new StringBuilder(template.length());
        int argCursor = templateIdx + 1;
        while (m.find()) {
            String conv = m.group(1);
            if ("%".equals(conv)) {
                m.appendReplacement(sb, Matcher.quoteReplacement("%"));
                continue;
            }
            String rep = "{param}";
            if (argCursor < call.getArguments().size()) {
                Expression arg = call.getArgument(argCursor++);
                var folded = resolver.apply(arg);
                if (folded.isPresent()) {
                    rep = folded.orElseThrow();
                } else if (arg instanceof IntegerLiteralExpr || arg instanceof LongLiteralExpr
                        || arg instanceof BooleanLiteralExpr || arg instanceof CharLiteralExpr) {
                    rep = arg.toString();
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return bounded(sb.toString());
    }
    private static boolean isReassignedInScope(VariableDeclarationExpr local, VariableDeclarator variable) {
        String targetName = variable.getNameAsString();
        Node scope = local.findAncestor(CallableDeclaration.class).map(Node.class::cast)
                .or(() -> local.findAncestor(InitializerDeclaration.class).map(Node.class::cast))
                .or(() -> local.findAncestor(BlockStmt.class).map(Node.class::cast))
                .orElse(null);
        if (scope == null) return true;
        for (AssignExpr assign : scope.findAll(AssignExpr.class)) {
            if (assign.getTarget() instanceof NameExpr n && n.getNameAsString().equals(targetName)) return true;
        }
        for (UnaryExpr unary : scope.findAll(UnaryExpr.class)) {
            if (unary.getExpression() instanceof NameExpr n && n.getNameAsString().equals(targetName)) return true;
        }
        return false;
    }
    private static boolean isReassignedInType(FieldDeclaration field, VariableDeclarator variable) {
        String targetName = variable.getNameAsString();
        Node owner = field.getParentNode().orElse(null);
        if (owner == null) return true;
        for (AssignExpr assign : owner.findAll(AssignExpr.class)) {
            Expression tgt = assign.getTarget();
            if (tgt instanceof NameExpr n && n.getNameAsString().equals(targetName)) return true;
            if (tgt instanceof FieldAccessExpr f && f.getNameAsString().equals(targetName)
                    && (f.getScope() instanceof ThisExpr || f.getScope() instanceof NameExpr)) return true;
        }
        return false;
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
        if(expression instanceof MethodCallExpr call) {
            var formatted=foldFormatCall(call,arg->value(arg,depth+1));
            if(formatted.isPresent())return formatted;
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
            boolean fixed=parent instanceof VariableDeclarationExpr local&&(local.isFinal()||!isReassignedInScope(local,variable))
                    ||parent instanceof FieldDeclaration field&&(field.isFinal()||field.getParentNode().filter(n->n instanceof ClassOrInterfaceDeclaration type&&type.isInterface()).isPresent()||(field.isPrivate()&&!isReassignedInType(field,variable)));
            if(!fixed||!variable.getType().resolve().describe().equals("java.lang.String"))return Optional.empty();
            var result=variable.getInitializer().flatMap(initializer->value(initializer,depth+1));memo.put(variable,result);return result;
        }finally{visiting.remove(variable);}
    }
    }
    private static boolean isUriOrPathPrefix(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("lb://") || value.startsWith("/");
    }
    private static String dynamicOrPrimitiveSegment(Expression expr) {
        Expression cur = expr;
        while (cur instanceof EnclosedExpr enclosed) {
            cur = enclosed.getInner();
        }
        if (cur instanceof IntegerLiteralExpr || cur instanceof BooleanLiteralExpr) {
            return cur.toString();
        }
        if (cur instanceof LongLiteralExpr longLit) {
            return longLit.asNumber().toString();
        }
        if (cur instanceof CharLiteralExpr charLit) {
            return String.valueOf(charLit.asChar());
        }
        return "{param}";
    }
    private static Optional<String> bounded(String value){return value.length()<=16_384?Optional.of(value):Optional.empty();}
}
