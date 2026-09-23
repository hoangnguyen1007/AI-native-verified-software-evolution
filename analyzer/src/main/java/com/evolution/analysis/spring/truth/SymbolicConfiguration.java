package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.util.*;

/** Solver-neutral Boolean DAG. UNKNOWN is modeled by separate true/false formulas, never a free Boolean. */
public final class SymbolicConfiguration {
    public enum Operator { TRUE, FALSE, VARIABLE, NOT, AND, OR }
    public static final class Formula {
        private final Operator operator;
        private final String variable;
        private final List<Formula> children;
        private final ContentDigest identity;
        private final int depth;
        private Formula(Operator op, String variable, List<Formula> children) {
            this.operator = op; this.variable = variable; this.children = List.copyOf(children);
            depth=Math.addExact(1,children.stream().mapToInt(Formula::depth).max().orElse(0));
            identity = IngestionEvidence.digest(List.of("spring.boolean-dag:m4u.2-v1", op, variable,
                    children.stream().map(Formula::identity).toList()));
        }
        public Operator operator() { return operator; }
        public String variable() { return variable; }
        public List<Formula> children() { return children; }
        public ContentDigest identity() { return identity; }
        public int depth(){return depth;}
        public boolean evaluate(Map<String, Boolean> assignment) {
            return evaluate(assignment,new HashMap<>());
        }
        private boolean evaluate(Map<String,Boolean> assignment,Map<ContentDigest,Boolean> values) {
            if(values.containsKey(identity))return values.get(identity);
            boolean value=switch (operator) {
                case TRUE -> true; case FALSE -> false;
                case VARIABLE -> Objects.requireNonNull(assignment.get(variable), "Missing Boolean assignment");
                case NOT -> !children.getFirst().evaluate(assignment,values);
                case AND -> children.stream().allMatch(c -> c.evaluate(assignment,values));
                case OR -> children.stream().anyMatch(c -> c.evaluate(assignment,values));
            };
            values.put(identity,value);return value;
        }
        public Object canonicalForm() { return Map.of("identity", identity, "operator", operator,
                "variable", variable, "children", children.stream().map(Formula::identity).toList()); }
        public Node view(){return new Node(identity,operator,variable,children.stream().map(Formula::identity).toList());}
    }
    public record Node(ContentDigest identity,Operator operator,String variable,List<ContentDigest> children) {
        public Node {children=List.copyOf(children);Objects.requireNonNull(operator);Objects.requireNonNull(variable);
            if(!identity.equals(IngestionEvidence.digest(List.of("spring.boolean-dag:m4u.2-v1",operator,variable,children))))throw new IllegalArgumentException("Boolean node identity mismatch");}
    }
    public static final Formula TRUE = new Formula(Operator.TRUE, "", List.of());
    public static final Formula FALSE = new Formula(Operator.FALSE, "", List.of());
    public static Formula variable(String name) { return new Formula(Operator.VARIABLE, ContractChecks.text(name, "Boolean variable"), List.of()); }
    public static Formula not(Formula child) {
        return switch(child.operator()) { case TRUE -> FALSE; case FALSE -> TRUE; case NOT -> child.children().getFirst();
            default -> new Formula(Operator.NOT, "", List.of(child)); };
    }
    public static Formula and(Formula... children) { return and(List.of(children)); }
    public static Formula or(Formula... children) { return or(List.of(children)); }
    public static Formula and(Collection<Formula> children) { return combine(Operator.AND, children); }
    public static Formula or(Collection<Formula> children) { return combine(Operator.OR, children); }
    private static Formula combine(Operator op, Collection<Formula> children) {
        var unique = new TreeMap<ContentDigest, Formula>();
        for (var child : children) {
            if (child.operator() == (op == Operator.AND ? Operator.FALSE : Operator.TRUE)) return op == Operator.AND ? FALSE : TRUE;
            if (child.operator() == (op == Operator.AND ? Operator.TRUE : Operator.FALSE)) continue;
            unique.put(child.identity(), child);
        }
        if (unique.isEmpty()) return op == Operator.AND ? TRUE : FALSE;
        if (unique.size() == 1) return unique.firstEntry().getValue();
        return new Formula(op, "", List.copyOf(unique.values()));
    }
    public record Limits(int maxNodes, int maxClauses, long maxSteps, int maxDepth) {
        public Limits { if (maxNodes < 1 || maxClauses < 1 || maxSteps < 1 || maxDepth < 1 || maxDepth > 512)
            throw new IllegalArgumentException("Invalid SAT limits"); }
        public static Limits defaults() { return new Limits(100_000, 500_000, 10_000_000, 256); }
    }
    public record Query(Formula formula, Limits limits) {
        public Query { Objects.requireNonNull(formula); Objects.requireNonNull(limits); }
        public ContentDigest identity() { return IngestionEvidence.digest(List.of(formula.identity(), limits)); }
    }
    public record Answer(ContentDigest query, ConfigurationReasoner.Satisfiability status,
                         Map<String, Boolean> witness, long steps, Optional<String> reason) {
        public Answer { Objects.requireNonNull(query); Objects.requireNonNull(status);
            witness = Collections.unmodifiableMap(new TreeMap<>(witness)); Objects.requireNonNull(reason); }
        public ContentDigest identity() { return IngestionEvidence.digest(this); }
    }
    private SymbolicConfiguration() {}
}
