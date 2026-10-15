package com.evolution.analysis.policy;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.query.*;
import java.util.*;
import static com.evolution.analysis.policy.PolicyEvaluation.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Evaluates compiled plans inside one exact graph context. */
public final class PolicyEvaluator {
    private record Route(List<String> vertices,List<EdgeView> edges,boolean eligible) {}
    public PolicyEvaluation.Result evaluate(PolicyCompiler.Result compilation,PolicyGraphMapper.Plan plan,
                                            ArchitectureQueryService service,PolicyEvaluation.Limits limits){
        Objects.requireNonNull(compilation);Objects.requireNonNull(plan);Objects.requireNonNull(service);Objects.requireNonNull(limits);
        if(!compilation.identity().equals(plan.compilation())||!service.graph().identity().equals(plan.graph()))
            throw new IllegalArgumentException("Foreign policy compilation or graph");
        var context=service.graph().contexts().stream().filter(c->c.identity().equals(plan.context())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Foreign policy context"));
        var expected=compilation.policy().stream().flatMap(p->p.rules().stream()).toList();
        if(!expected.equals(plan.rules().stream().map(PolicyGraphMapper.RulePlan::rule).toList()))throw new IllegalArgumentException("Incomplete or foreign rule denominator");
        for(var rule:plan.rules())if(!rule.request().graph().equals(plan.graph())||!rule.request().context().equals(plan.context())
                ||rule.request().scope()!=rule.rule().header().scope()||!rule.request().relationshipKinds().equals(rule.rule().header().relationships())
                ||rule.request().projection()!=Projection.BOUNDED_UNION||rule.request().direction()!=Direction.OUTGOING)
            throw new IllegalArgumentException("Foreign rule request");
        var evidenceContext=new EvidenceContext(service.graph().snapshot(),context.analysis());var mapping=plan.identity();
        var provenance=new ArrayList<EvidenceReference>();
        provenance.add(new EvidenceReference(context.evidence(),service.graph().evidence(context.evidence()).orElseThrow().family()));
        context.analysis().ifPresent(a->service.graph().contexts().stream().filter(c->c.identity().equals(a.value())).findFirst()
                .ifPresent(c->provenance.add(new EvidenceReference(c.evidence(),service.graph().evidence(c.evidence()).orElseThrow().family()))));
        var contextGaps=new ArrayList<CapabilityGapRecord>();Map<String,Object> contextPayload;
        try{contextPayload=GraphJson.object(GraphJson.read(
                service.graph().evidence(context.evidence()).orElseThrow().canonicalJson(),new GraphJson.Limits(1_000_000,64,20_000)));}
        catch(IllegalArgumentException malformed){contextPayload=Map.of();contextGaps.add(PolicyGaps.create(evidenceContext,PolicyEvaluation.VERSION,plan.graph(),"CONTEXT_EVIDENCE_UNAVAILABLE",plan.context()));}
        var spaces=contextPayload.get("space") instanceof String space?List.of(space):List.<String>of();
        var upstream=new ArrayList<UpstreamEvidence>();
        if(service.graph().nodes().size()>limits.maxInputItems())contextGaps.add(PolicyGaps.create(evidenceContext,PolicyEvaluation.VERSION,plan.graph(),"UPSTREAM_EVIDENCE_LIMIT",limits));
        else for(var node:service.graph().nodes())if((node.kind().equals("CAPABILITY_GAP")||node.kind().equals("PROVIDER_CONFLICT"))
                &&(node.context().equals(plan.context())||node.context().equals(service.graph().snapshot().value())||context.analysis().map(a->a.value().equals(node.context())).orElse(false))){
            for(var ref:node.evidence()){
                if(upstream.size()==limits.maxEvidenceReads()){
                    contextGaps.add(PolicyGaps.create(evidenceContext,PolicyEvaluation.VERSION,plan.graph(),"UPSTREAM_EVIDENCE_LIMIT",limits));break;
                }
                upstream.add(new UpstreamEvidence(node.context(),ref,service.graph().evidence(ref).orElseThrow().family()));
            }
            if(upstream.size()==limits.maxEvidenceReads()){
                contextGaps.add(PolicyGaps.create(evidenceContext,PolicyEvaluation.VERSION,plan.graph(),"UPSTREAM_EVIDENCE_LIMIT",limits));break;
            }
        }
        var collector=new PolicyEvidenceCollector(service.graph(),plan.context(),limits,evidenceContext);
        var allGaps=new ArrayList<>(plan.gaps());allGaps.addAll(contextGaps);var results=new ArrayList<RuleResult>();int remaining=limits.maxFindings();
        for(var mapped:plan.rules()){
            var gaps=new ArrayList<>(mapped.gaps());gaps.addAll(contextGaps);var routes=new ArrayList<Route>();boolean complete=mapped.status()==AssessmentStatus.COMPLETE&&contextGaps.isEmpty();
            boolean withheld=mapped.status()==AssessmentStatus.WITHHELD;
            if(withheld)gaps.add(gap(evidenceContext,mapping,mapped.rule(),"MAPPING_WITHHELD",limits));
            else if(remaining==0&&!mapped.candidates().isEmpty()){
                withheld=true;complete=false;gaps.add(gap(evidenceContext,mapping,mapped.rule(),"EVALUATION_LIMIT",limits));
            }else if(mapped.rule() instanceof ArchitecturePolicy.Cycles){
                var eligible=new TreeSet<String>();mapped.candidates().forEach(c->eligible.add(c.edge().identity()));
                var cycles=service.policyCycles(mapped.request(),eligible);gaps.addAll(cycles.gaps());
                complete&=cycles.status()==AssessmentStatus.COMPLETE&&!cycles.bounds().truncated()&&cycles.operation()==Operation.COMPLETED;
                var matches=new HashMap<String,PolicySelector.Match>();mapped.candidates().forEach(c->matches.put(c.edge().identity(),c.eligibility()));
                for(var cycle:cycles.items())routes.add(new Route(cycle.vertices(),cycle.edges(),cycle.edges().stream().allMatch(e->matches.get(e.identity())==PolicySelector.Match.YES)));
            }else for(var candidate:mapped.candidates()){
                routes.add(new Route(List.of(candidate.edge().source(),candidate.edge().target()),List.of(candidate.edge()),candidate.eligibility()==PolicySelector.Match.YES));
                if(routes.size()>remaining)break;
            }
            // Canonical ordering and one global output budget close the denominator across all rules.
            routes.sort(Comparator.comparing(r->com.evolution.analysis.contract.serialization.CanonicalJson.write(r.vertices())));
            if(routes.size()>remaining){routes.subList(remaining,routes.size()).clear();complete=false;gaps.add(gap(evidenceContext,mapping,mapped.rule(),"EVALUATION_LIMIT",limits));}
            var alternatives=new ArrayList<ArchitectureCondition.Alternative>();var findings=new ArrayList<Finding>();
            for(var route:routes){
                var evidence=collector.collect(route.edges());gaps.addAll(evidence.gaps());
                var alternative=new ArchitectureCondition.Alternative(new ArchitectureCondition.Term(route.edges().stream().map(EdgeView::identity).toList()),route.eligible()&&evidence.complete()&&contextGaps.isEmpty());
                alternatives.add(alternative);
                var predicate=new ArchitectureCondition.RulePredicate(List.of(alternative),true);
                var evaluated=service.ruleCondition(mapped.request(),predicate);gaps.addAll(evaluated.gaps());
                var region=evaluated.items().getFirst();
                if(region.classification()!=Classification.NEVER){
                    var local=new ArrayList<>(mapped.gaps());local.addAll(contextGaps);local.addAll(evidence.gaps());local.addAll(evaluated.gaps());
                    var status=local.isEmpty()?AssessmentStatus.COMPLETE:AssessmentStatus.PARTIAL;
                    var bundle=new EvidenceBundle(service.graph().snapshot(),context.analysis(),plan.graph(),plan.context(),compilation.policy().orElseThrow().identity(),
                            compilation.source(),compilation.identity(),mapping,spaces,provenance,upstream,mapped.rule(),mapped.request(),evaluated.query(),predicate,region,route.vertices(),route.edges(),
                            evidence.supports(),evidence.sources(),evidence.references(),status,ordered(local),
                            List.of("Witnesses replay the modeled region/formula; fresh Spring semantic or runtime replay is not claimed.",
                                    "Source spans belong to supporting declarations/occurrences; derived relationships have no invented source span."));
                    findings.add(new Finding(mapped.rule().header(),mapped.rule().kind(),region.classification(),bundle));
                }
                remaining--;
            }
            var predicate=new ArchitectureCondition.RulePredicate(alternatives,complete&&!withheld);
            var aggregate=service.ruleCondition(mapped.request(),predicate);
            gaps.addAll(aggregate.gaps());var region=aggregate.items().getFirst();
            var status=withheld?AssessmentStatus.WITHHELD:gaps.isEmpty()?AssessmentStatus.COMPLETE:AssessmentStatus.PARTIAL;
            results.add(new RuleResult(mapped.rule(),status,region.classification(),aggregate.query(),predicate,region,findings.stream().sorted(Comparator.comparing(Finding::identity)).toList(),ordered(gaps)));
            allGaps.addAll(gaps);
        }
        var status=compilation.policy().isEmpty()||!results.isEmpty()&&results.stream().allMatch(r->r.status()==AssessmentStatus.WITHHELD)
                ?AssessmentStatus.WITHHELD:allGaps.isEmpty()?AssessmentStatus.COMPLETE:AssessmentStatus.PARTIAL;
        return new PolicyEvaluation.Result(compilation.identity(),mapping,plan.graph(),plan.context(),limits,status,results,ordered(allGaps),upstream);
    }
    private static List<CapabilityGapRecord> ordered(List<CapabilityGapRecord> values){return values.stream().distinct().sorted().toList();}
    private static CapabilityGapRecord gap(EvidenceContext context,ContentDigest mapping,ArchitecturePolicy.Rule rule,String reason,PolicyEvaluation.Limits limits){
        return PolicyGaps.create(context,PolicyEvaluation.VERSION,mapping,reason,List.of(rule.identity(),limits));
    }
}
