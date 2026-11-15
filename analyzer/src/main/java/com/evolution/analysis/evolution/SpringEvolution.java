package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.spring.condition.LogicalValue;
import com.evolution.analysis.spring.truth.SatConfigurationReasoner;
import com.evolution.analysis.spring.truth.SymbolicConfiguration.*;
import java.io.IOException;
import java.util.*;
import static com.evolution.analysis.evolution.ConditionalRegionDelta.*;
import static com.evolution.analysis.evolution.EvolutionRows.*;
import static com.evolution.analysis.evolution.SnapshotComparison.Section;
import static com.evolution.analysis.spring.condition.LogicalValue.UNKNOWN;
import static com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;

/** Orchestrates the common-domain proof and emits paged region, proof and witness rows. */
final class SpringEvolution {
    static final Limits LIMITS=Limits.defaults(); // bound by SnapshotComparison.VERSION
    static final long MAX_CELLS=1_000_000;
    private static final long MAX_SOLVES=4096;
    private final EvolutionRun run;
    private final String scope;
    private final SpringRegionInput left,right;
    private final List<ContentDigest> refs;
    private final Map<String,Predicates> leftPredicates=new HashMap<>(),rightPredicates=new HashMap<>();
    private boolean provedRegion;
    private boolean residual;
    private long remainingCells=MAX_CELLS; // deterministic per selected partition, versioned with the engine
    private long remainingSolves=MAX_SOLVES;

    SpringEvolution(EvolutionRun run,String scope,SpringRegionInput left,SpringRegionInput right) {
        this.run=run;this.scope=scope;this.left=left;this.right=right;
        var refs=new ArrayList<>(left.references);refs.addAll(right.references);this.refs=List.copyOf(refs);
    }

    boolean compare()throws IOException {
        var reasons=new TreeSet<String>();
        if(!left.domain.alignable(right.domain))reasons.add("DOMAIN_CORRESPONDENCE_UNPROVED");
        if(!left.semantics.equals(right.semantics))reasons.add("SPRING_SEMANTICS_DRIFT");
        if(left.encoding.equals(right.encoding)&&!left.workPolicy.equals(right.workPolicy))reasons.add("REASONER_POLICY_DRIFT");
        if(!left.orderComplete||!right.orderComplete)reasons.add("REGISTRATION_ORDER_UNPROVED");
        if(!reasons.isEmpty()) {
            compatibility(reasons);
            observeOnly(reasons);
            return false;
        }
        var policy=row("solver",LIMITS,"maxCells",MAX_CELLS,"maxSolves",MAX_SOLVES);
        String proofKey=hash("evolution.spring.domain-proof:v1",List.of(scope,left.domain.identity,right.domain.identity,refs,policy)).value();
        run.put(Section.PROOFS,scope+"|domain",row("identity",proofKey,"scope",scope,"kind","DOMAIN_CORRESPONDENCE",
                "left",sideRef(left,null),"right",sideRef(right,null),"basis","EXACT_TYPED_COORDINATES",
                "order",Objects.equals(left.order,right.order)?"EQUAL_SCHEDULE":"REPLAY_PER_SIDE",
                "workPolicy",policy,"evidence",refs));
        boolean finite=left.encoding.equals("explicit")&&right.encoding.equals("explicit");
        Formula lLegal=finite?null:left.domain.legal(right.domain,LIMITS);
        Formula rLegal=finite?null:right.domain.legal(left.domain,LIMITS);
        var subjects=new TreeSet<>(left.facts.keySet());subjects.addAll(right.facts.keySet());
        var sites=new TreeMap<String,List<SpringRegionInput.Fact>>();
        long cost=Math.max(1L,(long)left.worlds.size()+right.worlds.size());
        for(String subject:subjects) {
            EvolutionSnapshot.check(run.cancelled);
            var a=left.facts.get(subject);var b=right.facts.get(subject);
            if(a==null||b==null||!spend(cost)||!finite&&!reserveSolves(19)) {
                residual=true;
                summary(subject,a,b,Shape.UNKNOWN,"UNKNOWN","UNKNOWN",proofKey,a==null||b==null?"FACT_CORRESPONDENCE_UNPROVED":"PROOF_BUDGET");
                continue;
            }
            try {
                witnessObservations(subject,a,b);
                if(finite)finite(subject,a,b,proofKey);
                else symbolic(subject,a,b,proofKey,lLegal,rLegal);
                if(a.kind().equals("SELECTED_BINDING"))sites.computeIfAbsent(a.site(),key->new ArrayList<>()).add(a);
            } catch(EvolutionSnapshot.ReadFailure invalid) {
                residual=true;
                summary(subject,a,b,Shape.UNKNOWN,"UNKNOWN","UNKNOWN",proofKey,"OPERAND_"+invalid.reason());
            } catch(IllegalArgumentException invalid) {
                residual=true;
                summary(subject,a,b,Shape.UNKNOWN,"UNKNOWN","UNKNOWN",proofKey,"OPERAND_CORRUPT");
            }
        }
        if(finite)domainFinite(proofKey);
        for(var site:sites.entrySet()) {
            EvolutionSnapshot.check(run.cancelled);
            if(finite)bindingFinite(site.getKey(),site.getValue(),proofKey);
            else bindingSymbolic(site.getKey(),site.getValue(),proofKey);
        }
        for(var side:List.of(left,right))for(var region:side.unaligned.values()) {
            residual=true;
            run.put(Section.REGIONS,scope+"|unaligned|"+side.side.name+"|"+region.id(),
                    row("scope",scope,"kind","UNALIGNED_FACT","side",sideRef(side,region),"reason","FACT_CORRESPONDENCE_UNPROVED"));
        }
        if(subjects.isEmpty()||residual)reasons.add("REGION_COVERAGE_UNPROVED");
        compatibility(reasons);
        return provedRegion;
    }

    private boolean reserveSolves(long count) {
        if((remainingSolves-=count)>=0)return true;
        residual=true;return false;
    }

    private void domainFinite(String proof)throws IOException {
        var a=new TreeMap<String,Cell>();var b=new TreeMap<String,Cell>();
        left.worlds.forEach((assignment,world)->a.put(assignment,new Cell(world.feasibility(),UNKNOWN)));
        right.worlds.forEach((assignment,world)->b.put(assignment,new Cell(world.feasibility(),UNKNOWN)));
        var domain=ConditionalRegionDelta.finite(a,b,left.complete,right.complete);
        if(!left.complete||!right.complete||domain.worlds().containsValue(Bucket.UNKNOWN_FEASIBILITY))
            residual=true;
        if(left.complete&&right.complete&&!domain.worlds().containsValue(Bucket.UNKNOWN_FEASIBILITY)
                ||domain.worlds().values().stream().anyMatch(bucket->bucket==Bucket.UNCERTAIN||bucket==Bucket.LEFT_ONLY||bucket==Bucket.RIGHT_ONLY))
            provedRegion=true;
        for(var entry:domain.worlds().entrySet()) {
            EvolutionSnapshot.check(run.cancelled);
            var assignment=entry.getKey();
            var l=left.worlds.get(assignment);var r=right.worlds.get(assignment);
            var bucket=entry.getValue()==Bucket.UNCERTAIN?"COMMON":entry.getValue().name();
            var data=row("scope",scope,"kind","DOMAIN_CELL","assignment",assignment,"disposition",bucket,"domainProof",proof,
                    "left",domainWorld(left,l),"right",domainWorld(right,r));
            var id=hash("evolution.spring.region-cell:v1",data);
            var detail=new TreeMap<>(data);detail.put("identity",id);
            run.put(Section.REGIONS,scope+"|domain|"+assignment,detail);
            if(entry.getValue()==Bucket.LEFT_ONLY||entry.getValue()==Bucket.RIGHT_ONLY)
                event(entry.getValue(),proof,id,data.get("left"),data.get("right"));
        }
    }

    private static Object domainWorld(SpringRegionInput input,SpringRegionInput.World w) {
        return row("operand",sideRef(input,w==null?null:w.operand()),"feasibility",w==null?null:w.feasibility(),
                "universeComplete",input.complete);
    }

    private boolean closedTargets(String site,List<SpringRegionInput.Fact> targets) {
        return left.unaligned.isEmpty()&&right.unaligned.isEmpty()&&
                countTargets(left,site)==targets.size()&&countTargets(right,site)==targets.size();
    }

    private static long countTargets(SpringRegionInput input,String site) {
        return input.facts.values().stream().filter(f->"SELECTED_BINDING".equals(f.kind())&&site.equals(f.site())).count();
    }

    private void bindingFinite(String site,List<SpringRegionInput.Fact> targets,String domainProof)throws IOException {
        if(!closedTargets(site,targets)||!spend((long)targets.size()*left.worlds.size()))return;
        for(var entry:left.worlds.entrySet()) {
            EvolutionSnapshot.check(run.cancelled);
            var l=entry.getValue();var r=right.worlds.get(entry.getKey());
            if(r==null||l.feasibility()!=LogicalValue.TRUE||r.feasibility()!=LogicalValue.TRUE)continue;
            var a=selected(left,l,targets);var b=selected(right,r,targets);
            if(a!=null&&b!=null&&!a.target().equals(b.target())) {
                var proof=row("assignment",entry.getKey(),"domainProof",domainProof,
                        "left",worldRef(left,a,l),"right",worldRef(right,b,r));
                bindingEvent(site,a,b,proof);
            }
        }
    }

    private SpringRegionInput.Fact selected(SpringRegionInput side,SpringRegionInput.World world,List<SpringRegionInput.Fact> targets) {
        SpringRegionInput.Fact selected=null;
        for(var t:targets) {
            var fact=side.facts.get(t.subject());
            switch(world.facts().getOrDefault(fact.local(),UNKNOWN)) {
                case UNKNOWN -> {return null;}
                case TRUE -> {if(selected!=null)return null;selected=fact;}
                default -> { }
            }
        }
        return selected;
    }

    private void bindingSymbolic(String site,List<SpringRegionInput.Fact> targets,String proof)throws IOException {
        if(!closedTargets(site,targets)||!spend((long)targets.size()*targets.size())
                ||!reserveSolves((long)targets.size()*(targets.size()-1)))return;
        var l=new TreeMap<String,Formula>();var r=new TreeMap<String,Formula>();
        for(var target:targets) {
            l.put(target.subject(),exclusive(target,targets,leftPredicates));
            r.put(target.subject(),exclusive(target,targets,rightPredicates));
        }
        for(var a:targets)for(var b:targets) {
            if(a.target().equals(b.target()))continue;
            EvolutionSnapshot.check(run.cancelled);
            var region=prove(and(l.get(a.subject()),r.get(b.subject())),LIMITS);
            if(region.status()==UNSATISFIABLE)continue;
            var after=right.facts.get(b.subject());
            String id=storeProof(site,"BINDING_TRANSITION",region,proof,sideRef(left,a.region()),sideRef(right,after.region()));
            if(region.status()!=SATISFIABLE){residual=true;continue;}
            bindingEvent(site,a,after,row("proof",id,"tier","FORMULA_REPLAY","assignment",region.assignment(),
                    "left",sideRef(left,a.region()),"right",sideRef(right,after.region())));
        }
    }

    private Formula exclusive(SpringRegionInput.Fact selected,List<SpringRegionInput.Fact> targets,Map<String,Predicates> predicates) {
        var conditions=new ArrayList<Formula>();
        for(var t:targets) {
            var p=predicates.get(t.subject());
            conditions.add(t.subject().equals(selected.subject())?and(p.feasible(),p.yes(),not(p.no())):
                    and(p.feasible(),p.no(),not(p.yes())));
        }
        return and(conditions);
    }

    private boolean spend(long cost) {
        if((remainingCells-=cost)>=0)return true;
        residual=true;return false;
    }

    private void bindingEvent(String site,SpringRegionInput.Fact a,SpringRegionInput.Fact b,Map<String,Object> proof)throws IOException {
        var data=row("request",run.requestIdentity,"family","BINDING","kind","SELECTED_BINDING_CHANGED","scope",scope,
                "site",site,"before",a.target(),"after",b.target(),"proof",proof);
        var id=hash("architecture.evolution.event:v1",data);
        var event=new TreeMap<>(data);event.put("identity",id);
        run.put(Section.EVENTS,scope+"|binding|"+id.value(),event);
    }

    private void compatibility(Set<String> reasons)throws IOException {
        String verdict=reasons.isEmpty()?"COMPARABLE":reasons.stream().anyMatch(s->s.endsWith("_DRIFT"))?"QUALIFIED":"UNKNOWN";
        run.compatibility(scope,"REGION",verdict,reasons,refs);
        run.compatibility(scope,"BINDING",verdict,reasons,refs);
        for(var reason:reasons){run.gap(left.side,left.context,reason,refs);run.gap(right.side,right.context,reason,refs);}
        if(!reasons.isEmpty())run.unresolved();
    }

    private void observeOnly(Set<String> reasons)throws IOException {
        for(var side:List.of(left,right))for(var fact:side.facts.values())
            run.put(Section.REGIONS,scope+"|observation|"+side.side.name+"|"+fact.subject(),
                    row("scope",scope,"kind","WITHHELD_FACT","subject",fact.subject(),"nativeClassification",fact.classification(),
                            "side",sideRef(side,fact.region()),"reason",reasons));
        for(var side:List.of(left,right))for(var region:side.unaligned.values())
            run.put(Section.REGIONS,scope+"|unaligned|"+side.side.name+"|"+region.id(),
                    row("scope",scope,"kind","UNALIGNED_FACT","side",sideRef(side,region),"reason","FACT_CORRESPONDENCE_UNPROVED"));
    }

    private void finite(String subject,SpringRegionInput.Fact a,SpringRegionInput.Fact b,String proofKey)throws IOException {
        var delta=ConditionalRegionDelta.finite(cells(left,a),cells(right,b),left.complete,right.complete);
        provedRegion|=delta.shape()!=Shape.UNKNOWN||delta.worlds().values().stream().anyMatch(SpringEvolution::proved);
        summary(subject,a,b,delta.shape(),delta.left(),delta.right(),proofKey,null);
        if(delta.shape()==Shape.UNKNOWN)residual=true;
        for(var e:delta.worlds().entrySet()) {
            EvolutionSnapshot.check(run.cancelled);
            String assignment=e.getKey();
            var lw=left.worlds.get(assignment);var rw=right.worlds.get(assignment);
            var kind=e.getValue();
            var support=row("scope",scope,"subject",subject,"kind",kind,"encoding","EXPLICIT",
                    "assignment",assignment,"domainProof",proofKey,"left",worldRef(left,a,lw),"right",worldRef(right,b,rw));
            var id=hash("evolution.spring.region-cell:v1",support);
            var detail=new TreeMap<>(support);detail.put("identity",id);
            run.put(Section.REGIONS,scope+"|cell|"+subject+"|"+assignment,detail);
            if(kind==Bucket.INTRODUCED||kind==Bucket.RESOLVED)event(kind,subject,id,support.get("left"),support.get("right"));
        }
    }

    private static Map<String,Cell> cells(SpringRegionInput side,SpringRegionInput.Fact fact) {
        var out=new TreeMap<String,Cell>();
        side.worlds.forEach((key,world)->out.put(key,new Cell(world.feasibility(),world.facts().getOrDefault(fact.local(),UNKNOWN))));
        return out;
    }

    private void symbolic(String subject,SpringRegionInput.Fact a,SpringRegionInput.Fact b,String proofKey,Formula lLegal,Formula rLegal)throws IOException {
        var l=predicates(left,a,lLegal);var r=predicates(right,b,rLegal);
        leftPredicates.put(a.subject(),l);rightPredicates.put(b.subject(),r);
        var delta=ConditionalRegionDelta.symbolic(l,r,LIMITS);
        provedRegion|=delta.shape()!=Shape.UNKNOWN||delta.proofs().entrySet().stream().anyMatch(e->e.getValue().status()==SATISFIABLE&&proved(e.getKey()));
        if(delta.shape()==Shape.UNKNOWN)residual=true;
        summary(subject,a,b,delta.shape(),delta.left(),delta.right(),proofKey,null);
        var witnesses=new EnumMap<Bucket,List<Object>>(Bucket.class);
        for(var e:delta.proofs().entrySet()) {
            var p=e.getValue();
            witnesses.put(e.getKey(),List.of(symbolicWitness(left,a,l,p),symbolicWitness(right,b,r,p)));
        }
        storeProof(subject,"COMMON",delta.common(),proofKey,sideRef(left,a.region()),sideRef(right,b.region()));
        for(var e:delta.proofs().entrySet()) {
            var p=e.getValue();
            String proof=storeProof(subject,e.getKey().name(),p,proofKey,sideRef(left,a.region()),sideRef(right,b.region()));
            var lWitness=witnesses.get(e.getKey()).getFirst();
            var rWitness=witnesses.get(e.getKey()).getLast();
            run.put(Section.REGIONS,scope+"|symbolic|"+subject+"|"+e.getKey(),row("scope",scope,"subject",subject,
                    "kind",e.getKey(),"status",p.status(),"proof",proof,"left",lWitness,"right",rWitness));
            if(p.status()!=SATISFIABLE||!definite(e.getKey()))continue;
            if(e.getKey()==Bucket.LEFT_ONLY||e.getKey()==Bucket.RIGHT_ONLY) {
                String key=scope+"|domain-symbolic|"+e.getKey()+"|"+p.formula().identity().value();
                if(run.index.get(Section.REGIONS.name(),key).isPresent())continue;
                run.put(Section.REGIONS,key,row("scope",scope,"kind","DOMAIN_FORMULA","disposition",e.getKey(),"proof",proof));
                event(e.getKey(),proofKey,new ContentDigest(proof),lWitness,rWitness);
            } else event(e.getKey(),subject,new ContentDigest(proof),lWitness,rWitness);
        }
    }

    private Predicates predicates(SpringRegionInput side,SpringRegionInput.Fact fact,Formula legal)throws IOException {
        if(side.encoding.equals("explicit")) {
            var t=new ArrayList<Formula>();var f=new ArrayList<Formula>();var known=new ArrayList<Formula>();var denied=new ArrayList<Formula>();
            for(var world:side.worlds.values()) {
                var cube=side.domain.cube(world.assignment());
                if(world.feasibility()==LogicalValue.TRUE) {
                    known.add(cube);
                    switch(world.facts().getOrDefault(fact.local(),UNKNOWN)){case TRUE->t.add(cube);case FALSE->f.add(cube);default->{}}
                } else if(world.feasibility()==LogicalValue.FALSE)denied.add(cube);
            }
            return new Predicates(and(legal,or(known)),and(legal,not(or(denied))),and(legal,or(t)),and(legal,or(f)));
        }
        var region=obj(fact.region().raw().get("region"));
        var yes=side.formula(text(region,"trueRegion"),true,LIMITS);
        var no=side.formula(text(region,"falseRegion"),true,LIMITS);
        var unknown=side.formula(text(region,"unknownRegion"),true,LIMITS);
        var possible=and(legal,or(yes,no,unknown));
        Formula known;
        if(side.complete)known=possible;
        else {
            known=and(possible,side.provedSymbolicUniverse());
            // Incomplete upstream feasibility cannot prove side-only assignments by subtraction.
            possible=legal;
        }
        return new Predicates(known,possible,and(legal,yes),and(legal,no));
    }

    private Object symbolicWitness(SpringRegionInput side,SpringRegionInput.Fact fact,Predicates predicates,Proof proof)throws IOException {
        boolean sat=proof.status()==SATISFIABLE;
        var assignment=new TreeMap<>(proof.assignment());
        if(sat) {
            side.domain.atoms.values().stream().map(SpringDomain::atom).forEach(a->assignment.putIfAbsent(a.variable(),false));
        }
        var local=sat?side.domain.localChoices(assignment):null;
        boolean feasible=sat&&predicates.feasible().evaluate(assignment);
        String truth=sat?predicates.yes().evaluate(assignment)==predicates.no().evaluate(assignment)?"UNKNOWN":
                predicates.yes().evaluate(assignment)?"TRUE":"FALSE":null;
        Object replay=null;
        if(feasible&&side.encoding.equals("explicit")) {
            var world=side.worlds.get(side.domain.replay(local).key());
            if(world==null||!world.facts().getOrDefault(fact.local(),UNKNOWN).name().equals(truth))throw EvolutionInput.corrupt();
            replay=worldRef(side,fact,world);
        } else if(feasible) {
            var region=obj(fact.region().raw().get("region"));
            boolean yes=side.formula(text(region,"trueRegion"),false,LIMITS).evaluate(local);
            boolean no=side.formula(text(region,"falseRegion"),false,LIMITS).evaluate(local);
            if(!(yes==no?"UNKNOWN":yes?"TRUE":"FALSE").equals(truth))throw EvolutionInput.corrupt();
        }
        return row("operand",sideRef(side,fact.region()),"tier",sat?"FORMULA_REPLAY":null,
                "assignment",sat?assignment:null,"localChoices",local,"worldReplay",replay,
                "feasible",sat?feasible:null,"truth",truth);
    }

    private String storeProof(String subject,String bucket,Proof proof,String domain,Object a,Object b)throws IOException {
        storeDag(proof.formula());
        var data=row("scope",scope,"subject",subject,"kind",bucket,"domainProof",domain,"formula",proof.formula().identity(),
                "reasoner",SatConfigurationReasoner.INSTANCE.policy(),"limits",LIMITS,"answer",proof.answer(),"left",a,"right",b);
        var id=hash("evolution.spring.proof:v1",data);
        run.put(Section.PROOFS,scope+"|proof|"+id.value(),row("identity",id,"proof",data));
        return id.value();
    }

    private void storeDag(Formula formula)throws IOException {
        var pending=new ArrayDeque<Formula>();pending.add(formula);
        while(!pending.isEmpty()) {
            EvolutionSnapshot.check(run.cancelled);
            var next=pending.removeFirst();
            String key=scope+"|dag|"+next.identity().value();
            if(run.index.get(Section.PROOFS.name(),key).isPresent())continue;
            run.put(Section.PROOFS,key,row("kind","FORMULA_NODE","node",next.view()));
            pending.addAll(next.children());
        }
    }

    private void summary(String subject,SpringRegionInput.Fact a,SpringRegionInput.Fact b,Shape shape,Object l,Object r,
                         String domain,String reason)throws IOException {
        run.put(Section.REGIONS,scope+"|fact|"+subject,row("scope",scope,"subject",subject,"kind","FACT_SUMMARY",
                "factKind",a==null?b.kind():a.kind(),"shape",shape,"domainProof",domain,"reason",reason,
                "left",factRef(left,a,l),"right",factRef(right,b,r)));
    }
    private static Object factRef(SpringRegionInput input,SpringRegionInput.Fact fact,Object common) {
        return fact==null?null:row("operand",sideRef(input,fact.region()),"nativeClassification",fact.classification(),"commonClassification",common);
    }
    private static Object worldRef(SpringRegionInput input,SpringRegionInput.Fact fact,SpringRegionInput.World world) {
        if(world==null)return row("operand",sideRef(input,fact.region()),"universeComplete",input.complete,"world",null);
        var truth=world.facts().getOrDefault(fact.local(),UNKNOWN);
        return row("operand",sideRef(input,fact.region()),"world",world.operand().id(),"worldEvidence",world.operand().evidence(),
                "truth",truth,"feasibility",world.feasibility(),"tier","CAPTURED_WORLD_REPLAY");
    }
    private static Object sideRef(SpringRegionInput input,SpringRegionInput.Operand operand) {
        return row("side",input.side.name,"graph",input.side.graph,"context",input.context,"space",input.domain.identity,
                "result",input.result.get("evidence"),"legalOrder",input.result.get("legalOrderIdentity"),
                "node",operand==null?null:operand.id(),"evidence",operand==null?null:operand.evidence());
    }
    private static boolean definite(Bucket kind) {
        return kind==Bucket.INTRODUCED||kind==Bucket.RESOLVED||kind==Bucket.LEFT_ONLY||kind==Bucket.RIGHT_ONLY;
    }
    private static boolean proved(Bucket kind) {
        return definite(kind)||kind==Bucket.CLEAN||kind==Bucket.PERSISTED;
    }
    private void event(Bucket kind,String subject,ContentDigest proof,Object a,Object b)throws IOException {
        String label=switch(kind){
            case LEFT_ONLY->"DOMAIN_REMOVED";case RIGHT_ONLY->"DOMAIN_ADDED";default->"REGION_"+kind.name();
        };
        var preimage=row("request",run.requestIdentity,"family","CONDITIONAL","scope",scope,"subject",subject,
                "kind",label,"proof",proof,"before",a,"after",b);
        var id=hash("architecture.evolution.event:v1",preimage);
        var data=new TreeMap<>(preimage);data.put("identity",id);
        run.put(Section.EVENTS,scope+"|conditional|"+id.value(),data);
    }

    private void witnessObservations(String subject,SpringRegionInput.Fact a,SpringRegionInput.Fact b)throws IOException {
        var before=left.witnesses(a);
        var after=right.witnesses(b);
        var truth=new TreeSet<>(before.keySet());truth.addAll(after.keySet());
        for(var value:truth) {
            var l=before.get(value);var r=after.get(value);
            if(l!=null&&r!=null&&l.get("assignment").equals(r.get("assignment"))&&Objects.equals(l.get("tier"),r.get("tier")))continue;
            run.put(Section.EVIDENCE,scope+"|witness|"+subject+"|"+value,row("scope",scope,"subject",subject,
                    "kind","WITNESS_OBSERVATION_CHANGED","truth",value,"left",l,"right",r,
                    "replayTierChanged",l!=null&&r!=null&&!Objects.equals(l.get("tier"),r.get("tier"))));
        }
    }
}
