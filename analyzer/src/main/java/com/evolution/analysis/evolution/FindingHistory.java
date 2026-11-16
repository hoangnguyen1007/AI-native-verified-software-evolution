package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.spring.condition.LogicalValue;
import com.evolution.analysis.spring.truth.SymbolicConfiguration;
import com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula;
import java.io.IOException;
import java.util.*;
import static com.evolution.analysis.evolution.EvolutionRows.*;
import static com.evolution.analysis.evolution.SnapshotComparison.*;
import static com.evolution.analysis.spring.condition.LogicalValue.*;

/** Streams adjacent pair results through an index-backed, interval-qualified history. */
final class FindingHistory {
    private final EvolutionRun run;
    private final EvolutionSnapshot.Reader graphs;
    private final ReportReader reports;
    private final HistoryReader ancestry;
    private final EvolutionInput latest;

    FindingHistory(EvolutionRun run,EvolutionSnapshot.Reader graphs,ReportReader reports,HistoryReader ancestry,EvolutionInput latest) {
        this.run=run;this.graphs=graphs;this.reports=reports;this.ancestry=ancestry;this.latest=latest;
    }

    void compare(History history) throws IOException {
        var steps=history.steps();
        var selection=new Selection(run.request.selection().sourceSets(),Set.of(),Set.of(),false,false,true,false,false);
        var overall=new TreeSet<String>();
        for (int interval=1;interval<steps.size();interval++) {
            EvolutionSnapshot.check(run.cancelled);
            var a=steps.get(interval-1).revision();var b=steps.get(interval).revision();
            var refs=new ArrayList<ReportRef>();
            a.reports().forEach(ref -> refs.add(new ReportRef(Side.LEFT,ref.identity(),ref.context())));
            b.reports().forEach(ref -> refs.add(new ReportRef(Side.RIGHT,ref.identity(),ref.context())));
            var request=new Request(run.request.repository(),a.graph(),b.graph(),run.request.mode(),selection);
            var key=String.format(Locale.ROOT,"%010d",interval);
            // Pair working state is namespaced within the caller's scratch store.
            var index=new IntervalIndex(key);
            var identity=hash("evolution.history.interval:v1",List.of(run.requestIdentity,interval,request.identity(),refs));
            var pair=new EvolutionRun(request,identity,index,run.size,run.cancelled,Optional.empty());
            var result=pair.compare(graphs,refs,reports,List.of(),(ref,s,o,c)->ReportStatus.UNAVAILABLE,
                    Optional.empty(),ancestry);
            var linkage=linkage(steps.get(interval).interval(),a,b,index);
            var input = new Rows(index, Section.INPUTS.name(), "");
            for (var entry = input.next(); entry != null; entry = input.next())
                run.put(Section.INPUTS, "HISTORY|" + key + "|" + entry.key(), entry.value());
            run.put(Section.INPUTS, "HISTORY|" + key + "|interval", row("kind","HISTORY_INTERVAL",
                    "position",interval,"artifact",steps.get(interval).interval(),"status",linkage.reason()==null?"AVAILABLE":linkage.reason()));
            run.put(Section.HISTORY,key,row("kind","INTERVAL","position",interval,"from",a,"to",b,
                    "comparison",result.summary().identity(),"status",result.summary().status(),"linkage",linkage.proof(),
                    "reason",linkage.reason(),"declaredFrom",steps.getFirst().revision().graph(),"declaredTo",steps.getLast().revision().graph()));
            if (linkage.reason()!=null) overall.add(linkage.reason());
            if (result.summary().status()!=Status.COMPLETE) overall.add("INTERVAL_EVIDENCE_INCOMPLETE");
            copyDag(index);
            var rows=new Rows(index,Section.FINDINGS.name(),"");
            for (var entry=rows.next();entry!=null;entry=rows.next()) {
                EvolutionSnapshot.check(run.cancelled);
                var finding=entry.value();
                if (!"FINDING_TRANSITION".equals(finding.get("kind"))) continue;
                var proof=proof(index,finding);
                if (proof.isEmpty()) {
                    run.put(Section.HISTORY,key+"|observation|"+text(finding,"subject"),
                            row("kind","HISTORY_OBSERVATION","position",interval,"finding",finding,"continuity","UNKNOWN"));
                    continue;
                }
                var route=index.get("finding-routes",text(finding,"subject")).orElseThrow(EvolutionInput::corrupt);
                var anchor=text(route,"scope")+"|"+text(route,"rule")+"|"+text(route,"key");
                run.index.put("history-anchors",anchor,route);
                advance(interval,key,linkage,history,finding,proof.orElseThrow(),overall);
            }
        }
        var scope=hash("evolution.history.scope:v1",history).value();
        var evidence=List.of(steps.getFirst().revision().graph(),steps.getLast().revision().graph());
        run.compatibility(scope,"HISTORY",overall.isEmpty()?"COMPARABLE":"UNKNOWN",overall,evidence);
        if (!overall.isEmpty()) {
            run.unresolved();
            if (latest.readable) for (var reason : overall) run.gap(latest,null,reason,evidence);
        } else run.comparable();
    }

    private void copyDag(EvolutionIndex index)throws IOException {
        var rows=new Rows(index,Section.PROOFS.name(),"");
        for(var entry=rows.next();entry!=null;entry=rows.next()) {
            EvolutionSnapshot.check(run.cancelled);
            if(!"FORMULA_NODE".equals(entry.value().get("kind")))continue;
            var scope=text(entry.value(),"scope");var formula=obj(entry.value().get("formula"));
            run.put(Section.PROOFS,scope+"|history-dag|"+text(formula,"identity"),entry.value());
        }
    }

    private record Linkage(HistoryProof proof,String reason) {}
    private Linkage linkage(Optional<ContentDigest> ref,HistoryRevision left,HistoryRevision right,EvolutionIndex index) throws IOException {
        if (ref.isEmpty()) return new Linkage(null,"HISTORY_INTERVAL_UNPROVED");
        HistoryProof proof;
        try {
            proof=ancestry.read(ref.orElseThrow(),run.cancelled).orElse(null);
        } catch (EvolutionSnapshot.ReadFailure invalid) {
            return new Linkage(null,"HISTORY_PROOF_"+invalid.reason().name());
        } catch (IllegalArgumentException invalid) {
            return new Linkage(null,"HISTORY_PROOF_CORRUPT");
        }
        if (proof==null) return new Linkage(null,"HISTORY_PROOF_UNAVAILABLE");
        var a=index.get(Section.INPUTS.name(),"0|LEFT|graph").orElse(Map.of());
        var b=index.get(Section.INPUTS.name(),"0|RIGHT|graph").orElse(Map.of());
        if (!proof.identity().equals(ref.orElseThrow()) || !proof.repository().equals(run.request.repository())
                || !proof.from().equals(left.graph()) || !proof.to().equals(right.graph())
                || !proof.before().value().equals(a.get("snapshot")) || !proof.after().value().equals(b.get("snapshot")))
            return new Linkage(null,"HISTORY_PROOF_CORRUPT");
        if (!proof.complete() || proof.parents().size()>1) return new Linkage(proof,"HISTORY_MERGE_OR_OPEN_INTERVAL");
        if (proof.basis()==HistoryBasis.AUTHENTICATED_PARENT && !proof.parents().equals(List.of(left.graph()))
                || !proof.parents().isEmpty() && !proof.parents().equals(List.of(left.graph())))
            return new Linkage(proof,"HISTORY_PARENT_UNPROVED");
        return new Linkage(proof,null);
    }

    private static Optional<Map<String,Object>> proof(EvolutionIndex index,Map<String,Object> finding) throws IOException {
        var id=maybe(finding,"proof");
        return id==null?Optional.empty():index.get(Section.PROOFS.name(),text(finding,"scope")+"|finding|"+id)
                .map(row -> obj(row.get("proof")));
    }

    private void advance(int interval,String key,Linkage link,History history,
                         Map<String,Object> finding,Map<String,Object> proof,Set<String> overall) throws IOException {
        var subject=text(finding,"subject");var region=finding.get("region");
        if(region instanceof Map<?,?> && Set.of("INTRODUCED","RESOLVED","CLEAN","PERSISTED").contains(finding.get("transition"))) {
            symbolic(interval,key,link,history,finding,proof,overall);
            return;
        }
        var stateKey=subject+"|"+hash("evolution.history.region:v1",region).value();
        var previous=run.index.get("history-state",stateKey).orElse(Map.of());
        var before=LogicalValue.valueOf(text(proof,"leftTruth"));var after=LogicalValue.valueOf(text(proof,"rightTruth"));
        boolean comparableRegion=region instanceof String && Set.of("INTRODUCED","RESOLVED","CLEAN","PERSISTED").contains(finding.get("transition"));
        boolean continuous=link.reason()==null && comparableRegion;
        boolean joined=continuous && previous.get("position")!=null && number(previous.get("position"))==interval-1
                && before.name().equals(previous.get("truth")) && Objects.equals(previous.get("operand"),proof.get("before"))
                && Objects.equals(previous.get("report"),proof.get("leftReport"));
        boolean ambiguousJoin=continuous && previous.get("position")!=null && number(previous.get("position"))==interval-1 && !joined;
        var earlier=maybe(previous,"earliest");
        if (earlier==null && before==TRUE) earlier=history.steps().get(interval-1).revision().graph().value();
        if (earlier==null && after==TRUE) earlier=history.steps().get(interval).revision().graph().value();
        boolean gone=continuous && (before==TRUE && after==FALSE || joined && before==FALSE && after==FALSE
                && Boolean.TRUE.equals(previous.get("provenAbsence")));
        boolean returned=joined && before==FALSE && after==TRUE && Boolean.TRUE.equals(previous.get("provenAbsence"));
        if (!continuous || ambiguousJoin) overall.add("HISTORY_REGION_CONTINUITY_UNPROVED");
        var proofId=text(finding,"proof");
        run.put(Section.PROOFS,text(finding,"scope")+"|history|"+proofId,row("identity",proofId,"kind","HISTORY_FINDING_REGION","proof",proof));
        var data=row("kind","HISTORY_TRANSITION","position",interval,"subject",subject,"scope",finding.get("scope"),"region",region,
                "transition",returned?"REINTRODUCED":finding.get("transition"),"continuity",continuous&&!ambiguousJoin?"PROVED":"UNKNOWN",
                "earliestObserved",earlier,"declaredFrom",history.steps().getFirst().revision().graph(),
                "declaredTo",history.steps().getLast().revision().graph(),"intervalProof",link.proof()==null?null:link.proof().identity(),
                "previousAbsenceProof",returned?previous.get("absenceProof"):null,
                "priorPresenceProof",returned?previous.get("presenceProof"):null,"proof",proofId,
                "before",finding.get("before"),"after",finding.get("after"));
        run.put(Section.HISTORY,key+"|"+stateKey,data);
        if (returned) {
            var event=hash("architecture.evolution.event:v1",List.of(run.requestIdentity,data));
            var fields=new TreeMap<>(data);fields.put("identity",event);fields.put("kind","FINDING_REINTRODUCED");
            run.put(Section.EVENTS,event.value(),fields);
        }
        run.index.put("history-state",stateKey,row("position",interval,"truth",after,"operand",proof.get("after"),
                "report",proof.get("rightReport"),"earliest",earlier,"provenAbsence",gone,
                "absenceProof",gone?proofId:null,"presenceProof",gone?(before==TRUE?proofId:previous.get("presenceProof")):null));
    }

    private void symbolic(int interval,String key,Linkage link,History history,Map<String,Object> finding,
                          Map<String,Object> proof,Set<String> overall) throws IOException {
        var scope=text(finding,"scope");
        var subject=text(finding,"subject");
        var current=text(obj(finding.get("region")),"formula");
        var proofId=text(finding,"proof");
        run.put(Section.PROOFS,scope+"|history|"+proofId,row("identity",proofId,"kind","HISTORY_FINDING_REGION","proof",proof));
        var position=hash("evolution.history.symbolic-row:v1",List.of(subject,current,proofId)).value();
        var before=text(proof,"leftTruth");var after=text(proof,"rightTruth");
        var earliest=run.index.get("history-earliest",subject).map(r->text(r,"graph")).orElse(null);
        if(earliest==null && ("TRUE".equals(before)||"TRUE".equals(after))) {
            earliest=history.steps().get("TRUE".equals(before)?interval-1:interval).revision().graph().value();
            run.index.put("history-earliest",subject,row("graph",earliest));
        }
        var incoming=new Rows(run.index,"history-symbolic-absence",subject+"|"+String.format(Locale.ROOT,"%010d",interval-1)+"|");
        var nextPrefix=subject+"|"+key+"|";
        var currentRegion=loadFormula(scope,current,new HashMap<>());
        boolean residual=false;
        if(link.reason()==null && "FALSE".equals(before)) {
            for(var item=incoming.next();item!=null;item=incoming.next()) {
                EvolutionSnapshot.check(run.cancelled);
                var previous=item.value();
                if(!Objects.equals(previous.get("operand"),proof.get("before"))
                        || !Objects.equals(previous.get("report"),proof.get("leftReport"))) {
                    residual=true;continue;
                }
                if(!run.spendFindingSolves(1)) {residual=true;break;}
                var prior=loadFormula(scope,text(previous,"formula"),new HashMap<>());
                var joint=ConditionalRegionDelta.prove(SymbolicConfiguration.and(prior,currentRegion),SpringEvolution.LIMITS);
                switch(joint.status()) {
                    case UNKNOWN -> residual=true;
                    case UNSATISFIABLE -> { }
                    case SATISFIABLE -> {
                        saveFormula(scope,joint.formula());
                        var formula=joint.formula().identity().value();
                        if("FALSE".equals(after)) {
                            run.index.put("history-symbolic-absence",nextPrefix+formula,row("formula",formula,"operand",proof.get("after"),
                                    "report",proof.get("rightReport"),"absenceProof",proofId,"presenceProof",previous.get("presenceProof")));
                        } else if("TRUE".equals(after)) {
                            var region=row("formula",formula,"answer",joint.answer(),"limits",SpringEvolution.LIMITS);
                            var data=row("kind","HISTORY_TRANSITION","position",interval,"subject",subject,"scope",scope,"region",region,
                                    "transition","REINTRODUCED","continuity","PROVED","earliestObserved",earliest,
                                    "declaredFrom",history.steps().getFirst().revision().graph(),"declaredTo",history.steps().getLast().revision().graph(),
                                    "intervalProof",link.proof().identity(),"previousAbsenceProof",previous.get("absenceProof"),
                                    "priorPresenceProof",previous.get("presenceProof"),"proof",proofId,
                                    "before",finding.get("before"),"after",finding.get("after"));
                            run.put(Section.HISTORY,key+"|"+position+"|"+formula,data);
                            var event=hash("architecture.evolution.event:v1",List.of(run.requestIdentity,data));
                            var fields=new TreeMap<>(data);fields.put("identity",event);fields.put("kind","FINDING_REINTRODUCED");
                            run.put(Section.EVENTS,event.value(),fields);
                        }
                    }
                }
            }
        }
        if(link.reason()==null && "TRUE".equals(before) && "FALSE".equals(after))
            run.index.put("history-symbolic-absence",nextPrefix+current,row("formula",current,"operand",proof.get("after"),
                    "report",proof.get("rightReport"),"absenceProof",proofId,"presenceProof",proofId));
        if(residual)overall.add("HISTORY_REGION_CONTINUITY_UNPROVED");
        run.put(Section.HISTORY,key+"|"+position,row("kind","HISTORY_TRANSITION","position",interval,"subject",subject,"scope",scope,
                "region",finding.get("region"),"transition",finding.get("transition"),"continuity",link.reason()==null&&!residual?"PROVED":"UNKNOWN",
                "earliestObserved",earliest,"declaredFrom",history.steps().getFirst().revision().graph(),
                "declaredTo",history.steps().getLast().revision().graph(),"intervalProof",link.proof()==null?null:link.proof().identity(),
                "proof",proofId,"before",finding.get("before"),"after",finding.get("after")));
    }

    private Formula loadFormula(String scope,String id,Map<String,Formula> cache) throws IOException {
        var known=cache.get(id);
        if(known!=null)return known;
        var node=obj(run.index.get(Section.PROOFS.name(),scope+"|history-dag|"+id).orElseThrow(EvolutionInput::corrupt).get("formula"));
        var children=new ArrayList<Formula>();
        for(var child:list(node.get("children")))children.add(loadFormula(scope,(String)child,cache));
        var formula=switch(SymbolicConfiguration.Operator.valueOf(text(node,"operator"))) {
            case TRUE -> SymbolicConfiguration.TRUE;
            case FALSE -> SymbolicConfiguration.FALSE;
            case VARIABLE -> SymbolicConfiguration.variable(text(node,"variable"));
            case NOT -> SymbolicConfiguration.not(children.getFirst());
            case AND -> SymbolicConfiguration.and(children);
            case OR -> SymbolicConfiguration.or(children);
        };
        if(!formula.identity().value().equals(id))throw EvolutionInput.corrupt();
        cache.put(id,formula);
        return formula;
    }

    private void saveFormula(String scope,Formula formula) throws IOException {
        var queue=new ArrayDeque<Formula>();
        queue.add(formula);
        while(!queue.isEmpty()) {
            EvolutionSnapshot.check(run.cancelled);
            var next=queue.removeFirst();
            var key=scope+"|history-dag|"+next.identity().value();
            if(run.index.get(Section.PROOFS.name(),key).isPresent())continue;
            run.put(Section.PROOFS,key,row("kind","FORMULA_NODE","scope",scope,"formula",next.view()));
            queue.addAll(next.children());
        }
    }

    private final class IntervalIndex implements EvolutionIndex {
        private final String prefix;
        IntervalIndex(String key){prefix="history-"+key+"-";}
        public Optional<Map<String,Object>> get(String table,String key) throws IOException {return run.index.get(table(table),key);}
        public void put(String table,String key,Map<String,Object> value) throws IOException {run.index.put(prefix+table,key,value);}
        public Batch page(String table,String after,int size) throws IOException {return run.index.page(table(table),after,size);}
        private String table(String name){return name.equals("history-anchors")?name:prefix+name;}
    }
}
