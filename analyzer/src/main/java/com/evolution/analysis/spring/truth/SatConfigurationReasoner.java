package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Pure Java symbolic backend. Explicit enumeration remains an opt-in compatibility operation. */
public enum SatConfigurationReasoner implements ConfigurationReasoner {
    INSTANCE;
    @Override public SymbolicConfiguration.Answer solve(SymbolicConfiguration.Query query) {
        var solver = new Solver(query);
        try {
            if(query.formula().depth()>query.limits().maxDepth())throw new Limit();
            int root = solver.encode(query.formula(), 0);
            solver.clause(root);
            byte[] solution = solver.search(new byte[solver.ids.size() + 1]);
            if (solution == null) return new SymbolicConfiguration.Answer(query.identity(), Satisfiability.UNSATISFIABLE,
                    Map.of(), solver.steps, Optional.empty());
            var witness = new TreeMap<String, Boolean>();
            solver.variables.forEach((name,id) -> witness.put(name, solution[id] == 1));
            // Independent interpretation of the original DAG, not the CNF encoder.
            if (!query.formula().evaluate(witness)) throw new IllegalStateException("SAT witness failed Boolean replay");
            return new SymbolicConfiguration.Answer(query.identity(), Satisfiability.SATISFIABLE,
                    witness, solver.steps, Optional.empty());
        } catch (Limit exceeded) {
            return new SymbolicConfiguration.Answer(query.identity(), Satisfiability.UNKNOWN, Map.of(), solver.steps,
                    Optional.of("SOLVER_LIMIT"));
        }
    }
    private static final class Limit extends RuntimeException {}
    private static final class Solver {
        final SymbolicConfiguration.Query query;
        final Map<com.evolution.analysis.contract.common.ContentDigest,Integer> ids = new HashMap<>();
        final Map<String,Integer> variables = new TreeMap<>();
        final List<int[]> clauses = new ArrayList<>();
        long steps;
        Solver(SymbolicConfiguration.Query query) { this.query=query; }
        void step() { if (steps >= query.limits().maxSteps()) throw new Limit(); steps++; }
        int encode(SymbolicConfiguration.Formula formula, int depth) {
            step();
            var known=ids.get(formula.identity()); if(known!=null)return known;
            if(depth>=query.limits().maxDepth() || ids.size()>=query.limits().maxNodes())throw new Limit();
            int id=ids.size()+1;ids.put(formula.identity(),id);
            var children=formula.children().stream().mapToInt(c->encode(c,depth+1)).toArray();
            switch(formula.operator()) {
                case TRUE -> clause(id); case FALSE -> clause(-id);
                case VARIABLE -> variables.put(formula.variable(),id);
                case NOT -> {clause(-id,-children[0]);clause(id,children[0]);}
                case AND, OR -> {
                    boolean and=formula.operator()==SymbolicConfiguration.Operator.AND;
                    int[] longClause=new int[children.length+1];longClause[0]=and?id:-id;
                    for(int i=0;i<children.length;i++) {
                        clause(and?-id:id,and?children[i]:-children[i]);
                        longClause[i+1]=and?-children[i]:children[i];
                    }
                    clause(longClause);
                }
            }
            return id;
        }
        void clause(int... literals) {
            if(clauses.size()>=query.limits().maxClauses())throw new Limit();
            clauses.add(literals);
        }
        byte[] search(byte[] initial) {
            // Explicit stack avoids JVM recursion depending on the number of configuration dimensions.
            var pending=new ArrayDeque<byte[]>();pending.push(initial);
            while(!pending.isEmpty()) {
                byte[] values=pending.pop();boolean changed, conflict=false;
                do {
                    changed=false;
                    for(int[] clause:clauses) {
                        boolean satisfied=false;int unset=0,last=0;
                        for(int literal:clause) {
                            step();byte value=values[Math.abs(literal)];
                            if(value==0){unset++;last=literal;}
                            else if((value==1)==(literal>0)){satisfied=true;break;}
                        }
                        if(satisfied)continue;
                        if(unset==0){conflict=true;break;}
                        if(unset==1){values[Math.abs(last)]=(byte)(last>0?1:-1);changed=true;}
                    }
                }while(changed&&!conflict);
                if(conflict)continue;
                int next=0;
                for(int[] clause:clauses) {
                    boolean satisfied=false;
                    for(int literal:clause){step();if(values[Math.abs(literal)]!=0&&(values[Math.abs(literal)]==1)==(literal>0)){satisfied=true;break;}}
                    if(!satisfied)for(int literal:clause)if(values[Math.abs(literal)]==0){next=Math.abs(literal);break;}
                    if(next!=0)break;
                }
                if(next==0)return values;
                byte[] positive=values.clone();positive[next]=1;pending.push(positive);
                values[next]=-1;pending.push(values);
            }
            return null;
        }
    }
    public VersionedIdentifier policy() { return new VersionedIdentifier("spring.configuration-reasoner", "dpll-m4u.2-v1"); }
    public FiniteConfigurationEvaluation.Result enumerate(ConditionModel m, ConditionExpression.Semantics s, FiniteConfigurationEvaluation.Limits l) {
        return ExhaustiveConfigurationReasoner.INSTANCE.enumerate(m,s,l);
    }
    public Satisfiability satisfiability(Region r) { return ExhaustiveConfigurationReasoner.INSTANCE.satisfiability(r); }
    public Optional<WorldKey> witness(Region r) { return ExhaustiveConfigurationReasoner.INSTANCE.witness(r); }
    public LogicalValue implies(Region a, Region b) { return ExhaustiveConfigurationReasoner.INSTANCE.implies(a,b); }
    public LogicalValue equivalent(Region a, Region b) { return ExhaustiveConfigurationReasoner.INSTANCE.equivalent(a,b); }
}
