package com.evolution.analysis.spring.condition;

import java.util.*;

/** Strict supported Spring profile-expression grammar; mixed AND/OR requires parentheses. */
final class ProfileExpressionLowering {
    private ProfileExpressionLowering() {}
    static ConditionExpression parse(String input, ConditionExpression.Semantics semantics, int maxDepth) {
        Deque<Frame> stack = new ArrayDeque<>(); stack.push(new Frame(false));
        boolean negate = false;
        for (int index = 0; index < input.length();) {
            char c = input.charAt(index);
            if (c <= ' ') { index++; continue; }
            Frame frame = stack.peek();
            if (c == '!') { if (!frame.expectsOperand) throw LiteralConditionAnnotation.unsupported(); negate = !negate; index++; continue; }
            if (c == '(') {
                if (!frame.expectsOperand || stack.size() >= maxDepth) throw LiteralConditionAnnotation.unsupported();
                stack.push(new Frame(negate)); negate = false; index++; continue;
            }
            if (c == ')') {
                if (stack.size() == 1 || negate) throw LiteralConditionAnnotation.unsupported();
                Frame completed = stack.pop(); ConditionExpression result = completed.finish(semantics);
                stack.peek().add(completed.negate ? ConditionExpression.not(result) : result); index++; continue;
            }
            if (c == '&' || c == '|') {
                if (frame.expectsOperand || negate || frame.operator != 0 && frame.operator != c) throw LiteralConditionAnnotation.unsupported();
                frame.operator = c; frame.expectsOperand = true; index++; continue;
            }
            if (!frame.expectsOperand) throw LiteralConditionAnnotation.unsupported();
            int start = index;
            while (index < input.length() && "!()&|".indexOf(input.charAt(index)) < 0) index++;
            String name = input.substring(start, index).trim();
            if (name.isBlank()) throw LiteralConditionAnnotation.unsupported();
            var atom = ConditionExpression.atom(semantics, new ConditionExpression.Profile(name));
            frame.add(negate ? ConditionExpression.not(atom) : atom); negate = false;
        }
        if (stack.size() != 1 || negate) throw LiteralConditionAnnotation.unsupported();
        return stack.pop().finish(semantics);
    }
    static ConditionExpression combine(ConditionExpression.Semantics semantics, boolean all, List<ConditionExpression> values) {
        var unique = new TreeMap<ConditionExpression.Identity, ConditionExpression>(); values.forEach(v -> unique.put(v.identity(), v));
        if (unique.size() == 1) return unique.firstEntry().getValue();
        return all ? ConditionExpression.all(semantics, List.copyOf(unique.values())) : ConditionExpression.any(semantics, List.copyOf(unique.values()));
    }
    private static final class Frame {
        final boolean negate;
        final List<ConditionExpression> values = new ArrayList<>();
        boolean expectsOperand = true;
        char operator;
        Frame(boolean negate) { this.negate = negate; }
        void add(ConditionExpression value) { if (!expectsOperand) throw LiteralConditionAnnotation.unsupported(); values.add(value); expectsOperand = false; }
        ConditionExpression finish(ConditionExpression.Semantics semantics) {
            if (expectsOperand || values.isEmpty()) throw LiteralConditionAnnotation.unsupported();
            return combine(semantics, operator == '&', values);
        }
    }
}
