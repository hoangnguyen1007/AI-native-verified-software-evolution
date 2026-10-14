package com.evolution.analysis.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.spring.truth.*;
import java.util.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;

/** Three-valued region algebra. Parallel supports OR; consecutive edges AND. */
final class QueryRegion {
    enum Encoding { UNIVERSAL, EXPLICIT, SYMBOLIC, UNKNOWN }
    final Encoding encoding;
    final String domain;
    final Set<String> yes,possible,universe;
    final Formula trueFormula,possibleFormula,universeFormula;
    final boolean closed;
    final Classification upstream;
    private QueryRegion(Encoding encoding,String domain,Set<String> yes,Set<String> possible,Set<String> universe,
                        Formula y,Formula p,Formula u,boolean closed,Classification classification){
        this.encoding=encoding;this.domain=domain;this.yes=Set.copyOf(yes);this.possible=Set.copyOf(possible);this.universe=Set.copyOf(universe);
        trueFormula=y;possibleFormula=p;universeFormula=u;this.closed=closed;upstream=classification;
    }
    static QueryRegion universal(){return new QueryRegion(Encoding.UNIVERSAL,"",Set.of(),Set.of(),Set.of(),TRUE,TRUE,TRUE,true,Classification.MUST);}
    static QueryRegion unknown(){return new QueryRegion(Encoding.UNKNOWN,"",Set.of(),Set.of(),Set.of(),FALSE,TRUE,TRUE,false,Classification.UNKNOWN);}
    static QueryRegion explicit(String domain,Set<String> yes,Set<String> possible,Set<String> universe,boolean closed,Classification c){
        return new QueryRegion(Encoding.EXPLICIT,domain,yes,possible,universe,FALSE,TRUE,TRUE,closed,c);
    }
    static QueryRegion symbolic(String domain,Formula yes,Formula possible,Formula universe,boolean closed,Classification c){
        return new QueryRegion(Encoding.SYMBOLIC,domain,Set.of(),Set.of(),Set.of(),yes,possible,universe,closed,c);
    }
    QueryRegion combine(QueryRegion other,boolean intersection,QueryBudget budget){
        if(encoding==Encoding.UNIVERSAL)return intersection?other:this;
        if(other.encoding==Encoding.UNIVERSAL)return intersection?this:other;
        if(encoding!=other.encoding||!domain.equals(other.domain)||encoding==Encoding.EXPLICIT&&!universe.equals(other.universe)){
            budget.qualify("INCOMPATIBLE_REGION_CONTEXT");return unknown();
        }
        if(encoding==Encoding.EXPLICIT)return explicit(domain,combine(yes,other.yes,intersection),combine(possible,other.possible,intersection),universe,closed&&other.closed,Classification.UNKNOWN);
        if(encoding==Encoding.SYMBOLIC)return symbolic(domain,intersection?and(trueFormula,other.trueFormula):or(trueFormula,other.trueFormula),
                intersection?and(possibleFormula,other.possibleFormula):or(possibleFormula,other.possibleFormula),
                intersection?and(universeFormula,other.universeFormula):or(universeFormula,other.universeFormula),closed&&other.closed,Classification.UNKNOWN);
        return unknown();
    }
    private static Set<String> combine(Set<String> a,Set<String> b,boolean intersection){var s=new TreeSet<>(a);if(intersection)s.retainAll(b);else s.addAll(b);return s;}
    record Check(Truth truth,Optional<Witness> witness) {}
    Check check(QueryBudget budget,Optional<Realization> realization){
        if(encoding==Encoding.UNIVERSAL)return new Check(Truth.SAT,Optional.of(new Witness(Optional.empty(),Map.of(),identity(),true,false)));
        if(encoding==Encoding.UNKNOWN){budget.qualify("REGION_EVIDENCE_UNAVAILABLE");return new Check(Truth.UNKNOWN,Optional.empty());}
        if(encoding==Encoding.EXPLICIT){
            if(realization.isPresent()){
                var world=realization.orElseThrow().world();
                if(world.isEmpty()||!universe.contains(world.orElseThrow())){budget.qualify("REALIZATION_NOT_IN_MODELED_SPACE");return new Check(Truth.UNKNOWN,Optional.empty());}
                if(yes.contains(world.orElseThrow()))return explicitWitness(world.orElseThrow());
                return new Check(possible.contains(world.orElseThrow())?Truth.UNKNOWN:Truth.UNSAT,Optional.empty());
            }
            if(!yes.isEmpty())return explicitWitness(new TreeSet<>(yes).first());
            return new Check(possible.isEmpty()&&closed?Truth.UNSAT:Truth.UNKNOWN,Optional.empty());
        }
        if(realization.isPresent()){
            var r=realization.orElseThrow();
            if(r.world().isPresent()){budget.qualify("REALIZATION_ENCODING_MISMATCH");return new Check(Truth.UNKNOWN,Optional.empty());}
            try {
                if(!universeFormula.evaluate(r.assignment())){budget.qualify("REALIZATION_NOT_IN_MODELED_SPACE");return new Check(Truth.UNKNOWN,Optional.empty());}
                if(trueFormula.evaluate(r.assignment()))return new Check(Truth.SAT,Optional.of(new Witness(Optional.empty(),r.assignment(),identity(),true,false)));
                return new Check(possibleFormula.evaluate(r.assignment())?Truth.UNKNOWN:Truth.UNSAT,Optional.empty());
            }catch(NullPointerException missing){budget.qualify("INCOMPLETE_REALIZATION");return new Check(Truth.UNKNOWN,Optional.empty());}
        }
        var answer=solve(trueFormula,budget);
        if(answer.status()==ConfigurationReasoner.Satisfiability.SATISFIABLE)return new Check(Truth.SAT,
                Optional.of(new Witness(Optional.empty(),answer.witness(),identity(),true,false)));
        if(answer.status()==ConfigurationReasoner.Satisfiability.UNKNOWN)return new Check(Truth.UNKNOWN,Optional.empty());
        var possibleAnswer=solve(possibleFormula,budget);
        return new Check(possibleAnswer.status()==ConfigurationReasoner.Satisfiability.UNSATISFIABLE?Truth.UNSAT:Truth.UNKNOWN,Optional.empty());
    }
    Classification classify(QueryBudget budget){
        if(encoding==Encoding.UNIVERSAL)return Classification.MUST;
        if(encoding==Encoding.UNKNOWN)return Classification.UNKNOWN;
        if(encoding==Encoding.EXPLICIT){
            if(!closed||universe.isEmpty()||!possible.equals(yes))return Classification.UNKNOWN;
            return yes.equals(universe)?Classification.MUST:yes.isEmpty()?Classification.NEVER:Classification.MAY;
        }
        if(!closed)return Classification.UNKNOWN;
        var exists=solve(universeFormula,budget);if(exists.status()!=ConfigurationReasoner.Satisfiability.SATISFIABLE)return Classification.UNKNOWN;
        var residue=solve(and(possibleFormula,not(trueFormula)),budget);
        if(residue.status()!=ConfigurationReasoner.Satisfiability.UNSATISFIABLE)return Classification.UNKNOWN;
        var y=solve(trueFormula,budget);var n=solve(and(universeFormula,not(possibleFormula)),budget);
        if(y.status()==ConfigurationReasoner.Satisfiability.SATISFIABLE&&n.status()==ConfigurationReasoner.Satisfiability.UNSATISFIABLE)return Classification.MUST;
        if(y.status()==ConfigurationReasoner.Satisfiability.UNSATISFIABLE&&n.status()==ConfigurationReasoner.Satisfiability.SATISFIABLE)return Classification.NEVER;
        if(y.status()==ConfigurationReasoner.Satisfiability.SATISFIABLE&&n.status()==ConfigurationReasoner.Satisfiability.SATISFIABLE)return Classification.MAY;
        return Classification.UNKNOWN;
    }
    static Answer solve(Formula f,QueryBudget budget){
        long remaining=budget.limits.maxSolverSteps()-budget.solverSteps;
        if(!budget.running()||remaining<=0){budget.halt(StopReason.SOLVER);return new Answer(ContentDigest.sha256Utf8("solver-budget"),ConfigurationReasoner.Satisfiability.UNKNOWN,Map.of(),0,Optional.of("SOLVER_LIMIT"));}
        var answer=SatConfigurationReasoner.INSTANCE.solve(new SymbolicConfiguration.Query(f,new SymbolicConfiguration.Limits(budget.limits.maxFormulaNodes(),
                Math.min(500_000,budget.limits.maxFormulaNodes()*5),remaining,128)));
        budget.solverSteps+=answer.steps();if(answer.status()==ConfigurationReasoner.Satisfiability.UNKNOWN)budget.halt(StopReason.SOLVER);
        return answer;
    }
    private Check explicitWitness(String world){return new Check(Truth.SAT,Optional.of(new Witness(Optional.of(world),Map.of(),identity(),true,false)));}
    ContentDigest identity(){return ContentDigest.sha256Utf8(CanonicalJson.write(List.of("query.region:m5.2-v1",encoding,domain,yes,possible,universe,trueFormula.identity(),possibleFormula.identity(),universeFormula.identity(),closed)));}
}
