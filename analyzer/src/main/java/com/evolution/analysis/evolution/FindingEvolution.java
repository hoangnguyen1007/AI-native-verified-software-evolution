package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.*;
import com.evolution.analysis.spring.condition.LogicalValue;
import java.io.IOException;
import java.util.*;
import static com.evolution.analysis.evolution.EvolutionRows.*;
import static com.evolution.analysis.evolution.ReportEvolution.Operand;
import static com.evolution.analysis.evolution.SnapshotComparison.Section;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;

/** Matches directed logical routes, then computes lifecycle from replayed positive and negative regions. */
final class FindingEvolution {
    record Route(ContentDigest key,List<String> vertices,List<List<Object>> edges) {}
    record Finding(PolicyEvaluation.Finding original,Route route) {}
    private final EvolutionRun run;
    private final String scope;
    private final Operand left,right;

    FindingEvolution(EvolutionRun run,String scope,Operand left,Operand right) {
        this.run=run;this.scope=scope;this.left=left;this.right=right;
    }

    void compare(Set<String> reasons) throws IOException {
        boolean compatible=reasons.isEmpty();
        var a=rules(left);var b=rules(right);
        var ids=new TreeSet<>(a.keySet());ids.addAll(b.keySet());
        if (ids.isEmpty()) reasons.add("RULE_DENOMINATOR_UNAVAILABLE");
        for (String id : ids) {
            EvolutionSnapshot.check(run.cancelled);
            var l=a.get(id);var r=b.get(id);
            var lc=ruleKey(left,l);var rc=ruleKey(right,r);
            var local=new TreeSet<String>();
            if (!compatible) local.add("REPORT_SCOPE_UNPROVED");
            if (l==null || r==null) local.add(left.report()==null||right.report()==null?"RULE_RESULT_UNAVAILABLE":"RULE_POPULATION_DRIFT");
            else if (lc==null || rc==null) local.add("POLICY_NAMESPACE_UNPROVED");
            else if (!lc.equals(rc)) local.add("RULE_DRIFT");
            if (left.report()!=null && right.report()!=null) {
                if (!left.report().evaluation().limits().equals(right.report().evaluation().limits())) local.add("EVALUATION_LIMITS_DRIFT");
                var aMapping=left.report().mapping();var bMapping=right.report().mapping();
                if (aMapping.isPresent() && bMapping.isPresent()
                        && !aMapping.orElseThrow().limits().equals(bMapping.orElseThrow().limits())) local.add("MAPPING_LIMITS_DRIFT");
            }
            var lRequest=request(left,l);var rRequest=request(right,r);
            if (lRequest!=null && rRequest!=null && !projection(lRequest).equals(projection(rRequest)))
                local.add("RULE_PROJECTION_DRIFT");
            if (lRequest==null || rRequest==null) local.add("RULE_PROJECTION_UNAVAILABLE");
            String ruleRow=scope+"|rule|"+hash("evolution.rule-row:v1",id).value();
            try {
                compareRoutes(ruleRow,lc,l,r,lRequest,rRequest,local);
            } catch (EvolutionSnapshot.ReadFailure invalid) {
                local.add("FINDING_OPERAND_"+invalid.reason().name());
            } catch (IllegalArgumentException | ArithmeticException | ClassCastException invalid) {
                local.add(invalid instanceof GraphJson.LimitExceededException?"FINDING_OPERAND_RESOURCE_LIMIT":"FINDING_OPERAND_CORRUPT");
            }
            preserveFindings(ruleRow,left,l,local);
            preserveFindings(ruleRow,right,r,local);
            run.put(Section.FINDINGS,ruleRow,row("kind","RULE_RESULT","scope",scope,"rule",id,
                    "left",ruleRef(left,l),"right",ruleRef(right,r),"severityChanged",l!=null&&r!=null&&l.rule().header().severity()!=r.rule().header().severity(),
                    "disposition",local.isEmpty()?"COMPARED":"QUALIFIED","reasons",local));
            reasons.addAll(local);
        }
    }

    private void preserveFindings(String rule,Operand side,PolicyEvaluation.RuleResult result,Set<String> reasons) throws IOException {
        if(result==null)return;
        for(var finding:result.findings()) {
            var key=rule+"|observed|"+side.input().name+"|"+finding.identity().value();
            if(run.index.get("used-findings",key).isPresent())continue;
            run.put(Section.FINDINGS,key,row("kind","FINDING_OBSERVATION","scope",scope,"side",side.input().name,
                    "finding",findingRef(side,new Finding(finding,null)),"disposition","UNPROVED","reasons",reasons));
        }
    }

    private void compareRoutes(String ruleRow,ContentDigest ruleKey,PolicyEvaluation.RuleResult l,PolicyEvaluation.RuleResult r,
                               Request lRequest,Request rRequest,Set<String> reasons) throws IOException {
        var a=findings(left,l);var b=findings(right,r);
        boolean align=reasons.isEmpty();
        if (!align) {
            for (var finding : a) observation(ruleRow,finding,null,reasons);
            for (var finding : b) observation(ruleRow,null,finding,reasons);
            return;
        }
        var lReplay=new RuleReplay(left,l,lRequest);
        var rReplay=new RuleReplay(right,r,rRequest);
        var lm=matched(a);var rm=matched(b);
        if (!lm.closed() || !rm.closed()) reasons.add("ROUTE_CORRESPONDENCE_UNPROVED");
        var keys=new TreeSet<>(lm.routes().keySet());keys.addAll(rm.routes().keySet());
        var seeds=new TreeMap<ContentDigest,Route>();
        var prefix=scope+"|"+ruleKey.value()+"|";
        var anchors=new Rows(run.index,"history-anchors",prefix);
        for(var entry=anchors.next();entry!=null;entry=anchors.next()) {
            var key=new ContentDigest(text(entry.value(),"key"));
            var vertices=list(entry.value().get("vertices")).stream().map(String.class::cast).toList();
            var edges=list(entry.value().get("edges")).stream().map(EvolutionRows::list).toList();
            seeds.put(key,new Route(key,vertices,edges));
        }
        keys.addAll(seeds.keySet());
        for (var key : keys) {
            EvolutionSnapshot.check(run.cancelled);
            var x=lm.routes().get(key);var y=rm.routes().get(key);
            var route=x!=null?x.route():y!=null?y.route():seeds.get(key);
            var subject=hash("architecture.evolution.finding-key:v1",List.of(ruleKey,scope,key));
            run.index.put("finding-routes",subject.value(),row("scope",scope,"rule",ruleKey,"key",key,"vertices",route.vertices(),"edges",route.edges()));
            var before=lReplay.truth(x,route,lm.closed());
            var after=rReplay.truth(y,route,rm.closed());
            if (before==null || after==null) reasons.add("ROUTE_TRUTH_UNPROVED");
            compareRegion(ruleRow,subject,x,y,before,after,reasons);
        }
        for (var unaligned : lm.unaligned()) observation(ruleRow,unaligned,null,Set.of("ROUTE_CORRESPONDENCE_UNPROVED"));
        for (var unaligned : rm.unaligned()) observation(ruleRow,null,unaligned,Set.of("ROUTE_CORRESPONDENCE_UNPROVED"));
        if (!lReplay.closed || !rReplay.closed) reasons.add("RULE_COVERAGE_UNPROVED");
    }

    static Map<String,PolicyEvaluation.RuleResult> rules(Operand side) {
        var rules=new TreeMap<String,PolicyEvaluation.RuleResult>();
        if (side.report()!=null) for (var rule : side.report().evaluation().rules())
            if (rules.putIfAbsent(rule.rule().header().id(),rule)!=null) throw new IllegalArgumentException("Duplicate rule result");
        return rules;
    }

    static ContentDigest ruleKey(Operand side,PolicyEvaluation.RuleResult result) {
        if (result==null) return null;
        var policy=side.report().compilation().flatMap(PolicyCompiler.Result::policy);
        Object namespace=policy.<Object>map(p -> List.of(p.id(),p.version())).orElseGet(() ->
                side.report().metrics().flatMap(m -> m.context().policy()).orElseGet(() ->
                        result.findings().stream().findFirst().map(f -> f.bundle().policy()).orElse(null)));
        if (namespace==null) return null;
        var rule=result.rule();var h=rule.header();
        Object selectors=switch (rule) {
            case ArchitecturePolicy.Forbidden f -> List.of(f.from(),f.to());
            case ArchitecturePolicy.Boundary b -> List.of(b.from(),b.allowed());
            case ArchitecturePolicy.Layers l -> List.of(l.layers(),l.allowed());
            case ArchitecturePolicy.Cycles c -> c.within();
        };
        return hash("evolution.rule-semantics:v1",List.of(namespace,h.id(),h.version(),rule.kind(),
                ArchitectureCondition.RULE_VERSION,h.scope(),h.relationships(),selectors));
    }

    static Request request(Operand side,PolicyEvaluation.RuleResult rule) {
        if (rule==null) return null;
        var exact=side.report().mapping().stream().flatMap(p -> p.rules().stream()).filter(r -> r.rule().equals(rule.rule()))
                .map(PolicyGraphMapper.RulePlan::request).findFirst().orElse(null);
        if (exact!=null) return exact;
        var requests=new HashSet<Request>();
        rule.findings().forEach(f -> requests.add(f.bundle().request()));
        side.report().metrics().ifPresent(m -> m.context().requests().stream()
                .filter(r -> r.scope()==rule.rule().header().scope() && r.relationshipKinds().equals(rule.rule().header().relationships()))
                .forEach(requests::add));
        return requests.size()==1?requests.iterator().next():null;
    }

    static Object projection(Request r) {
        return row("scope",r.scope(),"projection",r.projection(),"direction",r.direction(),"relationshipKinds",r.relationshipKinds(),
                "population",r.population(),"includeMust",r.includeMust(),"realization",r.realization(),"limits",r.limits());
    }

    private List<Finding> findings(Operand side,PolicyEvaluation.RuleResult rule) throws IOException {
        if (rule==null) return List.of();
        var endpoints=side.partition()==null?Map.<String,String>of():side.partition().endpoints(rule.rule().header().scope());
        var rows=new ArrayList<Finding>();
        for (var finding : rule.findings()) rows.add(new Finding(finding,route(rule.rule(),finding.bundle().relationships(),endpoints)));
        return rows;
    }

    static Route route(ArchitecturePolicy.Rule rule,List<EdgeView> edges,Map<String,String> endpoints) {
        if (edges.isEmpty() || edges.stream().anyMatch(e -> e.supportsTruncated()
                || e.supportCount()!=e.supports().size() || !endpoints.containsKey(e.source()) || !endpoints.containsKey(e.target()))) return null;
        var operands=new ArrayList<List<Object>>();
        for (var edge : edges) operands.add(List.of(endpoints.get(edge.source()),endpoints.get(edge.target()),edge.kinds()));
        for (int i=1;i<operands.size();i++) if (!operands.get(i-1).get(1).equals(operands.get(i).getFirst())) return null;
        if (rule.kind()==ArchitecturePolicy.Kind.ACYCLIC) {
            if (!operands.getLast().get(1).equals(operands.getFirst().getFirst())) return null;
            var ordered=new ArrayList<>(operands);
            for (int i=1;i<operands.size();i++) {
                var rotation=new ArrayList<>(operands.subList(i,operands.size()));rotation.addAll(operands.subList(0,i));
                if (com.evolution.analysis.contract.serialization.CanonicalJson.write(rotation)
                        .compareTo(com.evolution.analysis.contract.serialization.CanonicalJson.write(ordered))<0) ordered=rotation;
            }
            operands=ordered;
        } else if (operands.size()!=1) return null;
        var vertices=new ArrayList<String>();vertices.add((String)operands.getFirst().getFirst());
        for (var edge : operands) vertices.add((String)edge.get(1));
        return new Route(hash("evolution.finding-route:v1",operands),List.copyOf(vertices),List.copyOf(operands));
    }

    private record Matches(Map<ContentDigest,Finding> routes,List<Finding> unaligned) {
        boolean closed(){return unaligned.isEmpty();}
    }
    private static Matches matched(List<Finding> findings) {
        var grouped=new TreeMap<ContentDigest,List<Finding>>();
        var unknown=new ArrayList<Finding>();
        for (var f : findings) {
            if (f.route()==null) unknown.add(f);
            else grouped.computeIfAbsent(f.route().key(),ignored -> new ArrayList<>()).add(f);
        }
        var matches=new TreeMap<ContentDigest,Finding>();
        grouped.forEach((key,rows) -> {if (rows.size()==1) matches.put(key,rows.getFirst());else unknown.addAll(rows);});
        return new Matches(matches,unknown);
    }

    private final class RuleReplay {
        final Operand side;
        final Request request;
        final PolicyEvaluation.RuleResult rule;
        final boolean closed;

        RuleReplay(Operand side,PolicyEvaluation.RuleResult rule,Request request) throws IOException {
            this.side=side;this.rule=rule;this.request=request;
            boolean valid=request.graph().equals(side.input().graph) && request.context().equals(side.id())
                    && request.scope()==rule.rule().header().scope() && request.relationshipKinds().equals(rule.rule().header().relationships())
                    && request.projection()==Projection.BOUNDED_UNION && request.direction()==Direction.OUTGOING;
            var replay=valid?side.partition().queries.ruleCondition(request,rule.predicate()):null;
            closed=replay!=null && replay.query().equals(rule.query()) && replay.items().getFirst().equals(rule.region())
                    && completed(replay) && replay.status()==AssessmentStatus.COMPLETE && rule.status()==AssessmentStatus.COMPLETE
                    && rule.predicate().complete() && side.report().evaluation().status()!=AssessmentStatus.WITHHELD
                    && (side.partition().spring!=null || side.partition().dependenciesClosed(side.context())) && mappingClosed();
        }

        private boolean mappingClosed() {
            var mapping=side.report().mapping();
            if(mapping.isEmpty()) return side.report().metrics().stream().flatMap(m->m.counts().coverage().stream())
                    .anyMatch(c->c.rule().equals(rule.rule().identity()) && c.request().equals(request.identity())
                            && c.status()==AssessmentStatus.COMPLETE && c.relationships().equals(request.relationshipKinds()));
            var plan=mapping.orElseThrow();
            var mapped=plan.rules().stream().filter(r->r.rule().equals(rule.rule())).findFirst().orElse(null);
            if(mapped==null || mapped.status()!=AssessmentStatus.COMPLETE || !mapped.gaps().isEmpty()) return false;
            if(rule.rule().kind()==ArchitecturePolicy.Kind.ACYCLIC) {
                var ids=new TreeSet<String>();mapped.candidates().forEach(c->ids.add(c.edge().identity()));
                var cycles=side.partition().queries.policyCycles(request,ids);
                if(!completed(cycles)||cycles.status()!=AssessmentStatus.COMPLETE) return false;
                var actual=cycles.items().stream().map(c->new ArchitectureCondition.Alternative(
                        new ArchitectureCondition.Term(c.edges().stream().map(EdgeView::identity).toList()),true)).toList();
                return new ArchitectureCondition.RulePredicate(actual,true).equals(rule.predicate())
                        && mapped.candidates().stream().allMatch(c->c.eligibility()==PolicySelector.Match.YES);
            }
            var actual=mapped.candidates().stream().map(c->new ArchitectureCondition.Alternative(
                    new ArchitectureCondition.Term(List.of(c.edge().identity())),c.eligibility()==PolicySelector.Match.YES)).toList();
            return actual.size()<=50 && new ArchitectureCondition.RulePredicate(actual,true).equals(rule.predicate());
        }

        ArchitectureQueryService.RuleTruth truth(Finding finding,Route route,boolean mapped) throws IOException {
            if (finding!=null) {
                var bundle=finding.original().bundle();
                var term=Set.copyOf(bundle.relationships().stream().map(EdgeView::identity).toList());
                if (!bundle.rule().equals(rule.rule()) || !bundle.request().equals(request)
                        || bundle.predicate().alternatives().size()!=1 || !bundle.predicate().complete()
                        || !term.equals(Set.copyOf(bundle.predicate().alternatives().getFirst().term().edges()))
                        || !rule.predicate().alternatives().contains(bundle.predicate().alternatives().getFirst())
                        || !edgesMatch(bundle.relationships())) return null;
                if (side.partition().spring!=null && bundle.relationships().stream()
                        .flatMap(e->e.regions().stream()).anyMatch(id->!side.partition().spring.authenticatedPolicyRegion(id))) return null;
                var replay=side.partition().queries.ruleTruth(request,bundle.predicate());
                if (!completed(replay.evaluation()) || !replay.evaluation().query().equals(bundle.query())
                        || !replay.evaluation().items().getFirst().equals(bundle.region())) return null;
                if (bundle.status()==AssessmentStatus.WITHHELD) return null;
                return replay;
            }
            if (!closed || !mapped) return null;
            // A complete, replayed rule lists every non-NEVER route. Confirm its projection has no
            // unaligned candidate that might have the same route before using that complement.
            var projection=projection();
            if (!completed(projection) || projection.status()!=AssessmentStatus.COMPLETE || projection.items().size()!=1) return null;
            var endpointKeys=side.partition().endpoints(rule.rule().header().scope());
            var byId=new HashMap<String,EdgeView>();
            projection.items().getFirst().edges().forEach(edge -> byId.put(edge.identity(),edge));
            for (var alternative : rule.predicate().alternatives()) {
                var edges=alternative.term().edges().stream().map(byId::get).toList();
                if (!alternative.eligible() || edges.contains(null)) return null;
                var leftEdges=new ArrayList<>(edges);
                if (rule.rule().kind()==ArchitecturePolicy.Kind.ACYCLIC) {
                    var ordered=new ArrayList<EdgeView>();ordered.add(leftEdges.removeFirst());
                    while (!leftEdges.isEmpty()) {
                        var target=ordered.getLast().target();
                        var next=leftEdges.stream().filter(e -> e.source().equals(target)).toList();
                        if (next.size()!=1) return null;
                        ordered.add(next.getFirst());leftEdges.remove(next.getFirst());
                    }
                    leftEdges=ordered;
                }
                var key=route(rule.rule(),leftEdges,endpointKeys);
                if (key==null) return null;
                if (key.key().equals(route.key())) {
                    var replay=side.partition().queries.ruleTruth(request,new ArchitectureCondition.RulePredicate(List.of(alternative),true));
                    return completed(replay.evaluation()) && replay.evaluation().items().getFirst().classification()==Classification.NEVER
                            ?replay:null;
                }
            }
            return side.partition().queries.ruleTruth(request,new ArchitectureCondition.RulePredicate(List.of(),true));
        }

        private ArchitectureQuery.Result<MetricProjection> projection;
        private ArchitectureQuery.Result<MetricProjection> projection() {
            if(projection==null) projection=side.partition().queries.metricProjection(request);
            return projection;
        }

        private boolean edgesMatch(List<EdgeView> edges) {
            var projected=projection();
            if(!completed(projected)||projected.items().size()!=1) return false;
            if(projectedEdges==null) {
                projectedEdges=new HashMap<>();
                projected.items().getFirst().edges().forEach(edge->projectedEdges.put(edge.identity(),edge));
            }
            return edges.stream().allMatch(edge->edge.equals(projectedEdges.get(edge.identity())));
        }
        private Map<String,EdgeView> projectedEdges;
    }

    private static boolean completed(ArchitectureQuery.Result<?> result) {
        return result.operation()==Operation.COMPLETED && !result.bounds().truncated()
                && result.bounds().frontier().isEmpty() && result.continuation().isEmpty();
    }

    private void compareRegion(String rule,ContentDigest subject,Finding a,Finding b,
                               ArchitectureQueryService.RuleTruth x,ArchitectureQueryService.RuleTruth y,Set<String> reasons) throws IOException {
        if (left.partition().spring==null && right.partition().spring==null) {
            var before=structural(x,a);var after=structural(y,b);
            if(before==LogicalValue.UNKNOWN || after==LogicalValue.UNKNOWN)reasons.add("ROUTE_TRUTH_UNPROVED");
            transition(rule,subject,a,b,bucket(before,after),"STRUCTURAL_CONTEXT",truthRef(x),truthRef(y),before,after);
        } else if (left.partition().spring!=null && right.partition().spring!=null) {
            new FindingRegions(run,scope,left,right).compare(x,y,a,b,(bucket,region,proofLeft,proofRight,before,after) -> {
                if (!proved(bucket))
                    reasons.add(bucket.equals("PROOF_BUDGET")?"FINDING_PROOF_BUDGET":"ROUTE_REGION_UNPROVED");
                transition(rule,subject,a,b,bucket,region,proofLeft,proofRight,before,after);
            });
        }
    }

    private static LogicalValue structural(ArchitectureQueryService.RuleTruth truth, Finding finding) {
        if (truth==null) return LogicalValue.UNKNOWN;
        return switch (truth.evaluation().items().getFirst().classification()) {
            case MUST -> finding==null?LogicalValue.UNKNOWN:LogicalValue.TRUE;
            case NEVER -> truth.closed()?LogicalValue.FALSE:LogicalValue.UNKNOWN;
            default -> LogicalValue.UNKNOWN;
        };
    }

    private void observation(String rule,Finding a,Finding b,Set<String> reasons) throws IOException {
        var f=a==null?b:a;
        var subject=hash("evolution.unmatched-finding:v1",List.of(a==null?"RIGHT":"LEFT",f.original().identity()));
        var known=f.original().classification()==Classification.MUST||f.original().classification()==Classification.MAY;
        run.put(Section.FINDINGS,rule+"|"+subject.value(),row("kind","FINDING_TRANSITION","scope",scope,"subject",subject,
                "transition",!known?"UNKNOWN":a==null?"UNKNOWN_TO_PRESENT":"PRESENT_TO_UNKNOWN","proof",null,"before",findingRef(left,a),
                "after",findingRef(right,b),"reasons",reasons,"observationOnly",true));
        used(rule,left,a);used(rule,right,b);
    }

    private void transition(String rule,ContentDigest subject,Finding a,Finding b,String bucket,Object region,Object l,Object r,
                            LogicalValue before,LogicalValue after) throws IOException {
        var proof=row("scope",scope,"subject",subject,"region",region,"before",l,"after",r,"leftTruth",before,"rightTruth",after,
                "rule",rule,"leftReport",left.referenceRow(),"rightReport",right.referenceRow(),"verifier",SnapshotComparison.VERSION);
        var id=hash("evolution.finding-proof:v1",proof);
        run.put(Section.PROOFS,scope+"|finding|"+id.value(),row("identity",id,"kind","FINDING_REGION","proof",proof));
        var data=row("kind","FINDING_TRANSITION","scope",scope,"subject",subject,"transition",bucket,"region",region,"proof",id,
                "before",findingRef(left,a),"after",findingRef(right,b),"observationOnly",false);
        run.put(Section.FINDINGS,rule+"|"+subject.value()+"|"+id.value(),data);
        used(rule,left,a);used(rule,right,b);
        if (proved(bucket)) run.comparable();
        else run.unresolved();
        if (Set.of("INTRODUCED","RESOLVED").contains(bucket)) {
            var event=hash("architecture.evolution.event:v1",List.of(run.requestIdentity,data));
            var fields=new TreeMap<>(data);fields.put("identity",event);fields.put("kind","FINDING_"+bucket);
            run.put(Section.EVENTS,event.value(),fields);
        }
    }

    private static boolean proved(String bucket) {
        return Set.of("INTRODUCED","PERSISTED","RESOLVED","CLEAN","LEFT_ONLY","RIGHT_ONLY","EMPTY_COMMON_DOMAIN").contains(bucket);
    }

    private void used(String rule,Operand side,Finding finding) throws IOException {
        if(finding!=null)run.index.put("used-findings",rule+"|observed|"+side.input().name+"|"+finding.original().identity().value(),
                row("present",true));
    }

    static String bucket(LogicalValue a,LogicalValue b) {
        return switch (a) {
            case TRUE -> switch (b) {case TRUE -> "PERSISTED";case FALSE -> "RESOLVED";case UNKNOWN -> "PRESENT_TO_UNKNOWN";};
            case FALSE -> switch (b) {case TRUE -> "INTRODUCED";case FALSE -> "CLEAN";case UNKNOWN -> "ABSENT_TO_UNKNOWN";};
            case UNKNOWN -> switch (b) {case TRUE -> "UNKNOWN_TO_PRESENT";case FALSE -> "UNKNOWN_TO_ABSENT";case UNKNOWN -> "UNKNOWN";};
        };
    }

    private static Object ruleRef(Operand side,PolicyEvaluation.RuleResult rule) {
        return row("input",side.referenceRow(),"result",rule==null?null:rule.identity(),"status",rule==null?null:rule.status(),
                "classification",rule==null?null:rule.classification(),"severity",rule==null?null:rule.rule().header().severity(),
                "completePredicate",rule==null?null:rule.predicate().complete(),"region",rule==null?null:rule.region().jointRegion());
    }

    private static Object findingRef(Operand side,Finding finding) {
        if (finding==null) return side.referenceRow();
        var f=finding.original();
        return row("input",side.referenceRow(),"finding",f.identity(),"originalKey",f.key(),"bundle",f.bundle().identity(),
                "path",f.bundle().path(),"relationships",f.bundle().relationships().stream().map(EdgeView::identity).toList(),
                "sources",f.bundle().sources().stream().limit(50).toList(),"sourceCount",f.bundle().sources().size(),
                "classification",f.classification(),"severity",f.rule().severity(),"status",f.bundle().status());
    }

    static Object truthRef(ArchitectureQueryService.RuleTruth truth) {
        if (truth==null) return null;
        var r=truth.evaluation();
        var evaluation=r.items().getFirst();
        return row("query",r.query(),"region",evaluation.jointRegion(),"classification",evaluation.classification(),
                "status",r.status(),"evidence",evaluation.evidence(),"gaps",r.gaps().stream().map(g -> g.gapIdentity()).toList());
    }
}
