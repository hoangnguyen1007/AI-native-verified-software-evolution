package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.query.ArchitectureQueryService.RuleTruth;
import com.evolution.analysis.spring.condition.LogicalValue;
import com.evolution.analysis.spring.truth.SymbolicConfiguration;
import com.evolution.analysis.spring.truth.SymbolicConfiguration.*;
import java.io.IOException;
import java.util.*;
import static com.evolution.analysis.evolution.ConditionalRegionDelta.*;
import static com.evolution.analysis.evolution.EvolutionRows.*;
import static com.evolution.analysis.evolution.ReportEvolution.Operand;
import static com.evolution.analysis.evolution.SnapshotComparison.Section;
import static com.evolution.analysis.spring.condition.LogicalValue.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.and;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.not;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.or;

/** Reuses the M11.3 typed coordinates and common-domain proof for M6 route predicates. */
final class FindingRegions {
    @FunctionalInterface interface Emit {
        void accept(String bucket,Object region,Object left,Object right,LogicalValue before,LogicalValue after) throws IOException;
    }
    private final EvolutionRun run;
    private final String scope;
    private final SpringRegionInput left,right;

    FindingRegions(EvolutionRun run,String scope,Operand left,Operand right) {
        this.run=run;this.scope=scope;this.left=left.partition().spring;this.right=right.partition().spring;
    }

    void compare(RuleTruth a,RuleTruth b,FindingEvolution.Finding leftFinding,
                 FindingEvolution.Finding rightFinding,Emit emit) throws IOException {
        if (left.encoding.equals("explicit") && right.encoding.equals("explicit")) {
            if (!run.spendFindingCells((long)left.worlds.size()+right.worlds.size())) {
                emit.accept("PROOF_BUDGET",null,reference(left,a),reference(right,b),UNKNOWN,UNKNOWN);
                return;
            }
            var l=cells(left,a,leftFinding);var r=cells(right,b,rightFinding);
            var delta=finite(l,r,left.complete,right.complete);
            if(delta.worlds().values().stream().allMatch(bucket->bucket==Bucket.INFEASIBLE)) {
                emit.accept("EMPTY_COMMON_DOMAIN",null,reference(left,a),reference(right,b),UNKNOWN,UNKNOWN);
                return;
            }
            for (var entry : delta.worlds().entrySet()) {
                EvolutionSnapshot.check(run.cancelled);
                var key=entry.getKey();var x=l.get(key);var y=r.get(key);
                if (entry.getValue()==Bucket.INFEASIBLE) continue;
                boolean common=x!=null&&y!=null&&x.feasible()==TRUE&&y.feasible()==TRUE;
                var before=x==null?UNKNOWN:x.truth();var after=y==null?UNKNOWN:y.truth();
                emit.accept(common?FindingEvolution.bucket(before,after):entry.getValue().name(),key,
                        worldRef(left,a,key),worldRef(right,b,key),before,after);
            }
            return;
        }
        if (!run.spendFindingSolves(19)) {
            emit.accept("PROOF_BUDGET",null,reference(left,a),reference(right,b),UNKNOWN,UNKNOWN);return;
        }
        var l=predicates(left,right,a,leftFinding);var r=predicates(right,left,b,rightFinding);
        var result=symbolic(l,r,SpringEvolution.LIMITS);
        for (var entry : result.proofs().entrySet()) {
            var proof=entry.getValue();
            if (proof.status()==com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability.UNSATISFIABLE) continue;
            saveDag(proof.formula());
            var region=row("formula",proof.formula().identity(),"answer",proof.answer(),
                    "reasoner",com.evolution.analysis.spring.truth.SatConfigurationReasoner.INSTANCE.policy(),"limits",SpringEvolution.LIMITS);
            var bucket=entry.getKey();
            var before=bucket==Bucket.PERSISTED||bucket==Bucket.RESOLVED?TRUE:bucket==Bucket.INTRODUCED||bucket==Bucket.CLEAN?FALSE:UNKNOWN;
            var after=bucket==Bucket.PERSISTED||bucket==Bucket.INTRODUCED?TRUE:bucket==Bucket.RESOLVED||bucket==Bucket.CLEAN?FALSE:UNKNOWN;
            boolean proved=proof.status()==com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability.SATISFIABLE;
            emit.accept(proved?bucket.name():"PROOF_BUDGET",region,reference(left,a),reference(right,b),proved?before:UNKNOWN,proved?after:UNKNOWN);
        }
        if(result.proofs().values().stream().allMatch(p->p.status()==
                com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability.UNSATISFIABLE))
            emit.accept("EMPTY_COMMON_DOMAIN",null,reference(left,a),reference(right,b),UNKNOWN,UNKNOWN);
    }

    private static Map<String,Cell> cells(SpringRegionInput input,RuleTruth truth,FindingEvolution.Finding finding) {
        var cells=new TreeMap<String,Cell>();
        input.worlds.forEach((key,world) -> {
            var value=UNKNOWN;
            if (world.feasibility()==LogicalValue.TRUE && truth!=null && "EXPLICIT".equals(truth.encoding())
                    && truth.worlds().contains(world.operand().id())) {
                if (truth.trueWorlds().contains(world.operand().id())) value=TRUE;
                else if (!truth.possibleWorlds().contains(world.operand().id()) && negativeAllowed(truth,finding)) value=FALSE;
            }
            cells.put(key,new Cell(world.feasibility(),value));
        });
        return cells;
    }

    private static boolean negativeAllowed(RuleTruth truth,FindingEvolution.Finding finding) {
        // The enclosing replay validates the particular selector and support membership.
        // The certified route formula can have definite false cells even when an unrelated
        // upstream gap leaves the aggregate rule population open.
        return truth.closed() && (finding==null || finding.original().bundle().predicate().alternatives().getFirst().eligible());
    }

    private static Predicates predicates(SpringRegionInput input,SpringRegionInput other,RuleTruth truth,
                                        FindingEvolution.Finding finding) throws IOException {
        var legal=input.domain.legal(other.domain,SpringEvolution.LIMITS);
        if (input.encoding.equals("explicit")) {
            var known=new ArrayList<Formula>();var denied=new ArrayList<Formula>();
            var yes=new ArrayList<Formula>();var no=new ArrayList<Formula>();
            var cells=cells(input,truth,finding);
            for (var entry : input.worlds.entrySet()) {
                var cube=input.domain.cube(entry.getValue().assignment());
                var cell=cells.get(entry.getKey());
                if (cell.feasible()==TRUE) known.add(cube);
                if (cell.feasible()==FALSE) denied.add(cube);
                if (cell.truth()==TRUE) yes.add(cube);
                if (cell.truth()==FALSE) no.add(cube);
            }
            var feasible=and(legal,or(known));
            return new Predicates(feasible,and(legal,not(or(denied))),
                    and(legal,or(yes)),and(legal,or(no)));
        }
        if (truth==null || !"SYMBOLIC".equals(truth.encoding()))
            return new Predicates(SymbolicConfiguration.FALSE,legal,SymbolicConfiguration.FALSE,SymbolicConfiguration.FALSE);
        var formulas=new HashMap<ContentDigest,Formula>();
        var modeled=and(legal,normalize(input.domain,truth.universeFormula(),formulas));
        var feasible=input.complete?modeled:and(modeled,input.provedSymbolicUniverse());
        var yes=and(feasible,normalize(input.domain,truth.trueFormula(),formulas));
        var no=negativeAllowed(truth,finding)?and(feasible,not(normalize(input.domain,truth.possibleFormula(),formulas))):
                SymbolicConfiguration.FALSE;
        return new Predicates(feasible,input.complete?modeled:legal,yes,no);
    }

    private static Formula normalize(SpringDomain domain,Formula f,Map<ContentDigest,Formula> cache) {
        var existing=cache.get(f.identity());
        if (existing!=null) return existing;
        if (f.depth()>SpringEvolution.LIMITS.maxDepth() || cache.size()>=SpringEvolution.LIMITS.maxNodes())
            throw new com.evolution.analysis.graph.GraphJson.LimitExceededException("Finding formula working set");
        var result=switch(f.operator()) {
            case TRUE -> SymbolicConfiguration.TRUE;
            case FALSE -> SymbolicConfiguration.FALSE;
            case VARIABLE -> domain.normalize(f.variable());
            case NOT -> not(normalize(domain,f.children().getFirst(),cache));
            case AND -> and(f.children().stream().map(c -> normalize(domain,c,cache)).toList());
            case OR -> or(f.children().stream().map(c -> normalize(domain,c,cache)).toList());
        };
        cache.put(f.identity(),result);
        return result;
    }

    private void saveDag(Formula formula) throws IOException {
        var queue=new ArrayDeque<Formula>();queue.add(formula);
        while (!queue.isEmpty()) {
            EvolutionSnapshot.check(run.cancelled);
            var next=queue.removeFirst();var key=scope+"|dag|"+next.identity().value();
            if (run.index.get(Section.PROOFS.name(),key).isPresent()) continue;
            run.put(Section.PROOFS,key,row("kind","FORMULA_NODE","scope",scope,"formula",next.view()));
            queue.addAll(next.children());
        }
    }

    private static Object reference(SpringRegionInput input,RuleTruth truth) {
        return row("domain",input.domain.identity,"evidence",input.references,"truth",FindingEvolution.truthRef(truth));
    }

    private static Object worldRef(SpringRegionInput input,RuleTruth truth,String key) {
        var world=input.worlds.get(key);
        return row("region",reference(input,truth),"world",world==null?null:world.operand().id(),
                "worldEvidence",world==null?null:world.operand().evidence(),"feasibility",world==null?null:world.feasibility());
    }

}
