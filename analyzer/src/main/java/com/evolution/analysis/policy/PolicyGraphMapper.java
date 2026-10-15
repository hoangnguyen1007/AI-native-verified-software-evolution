package com.evolution.analysis.policy;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.query.*;
import java.util.*;
import static com.evolution.analysis.policy.ArchitecturePolicy.*;
import static com.evolution.analysis.policy.PolicySelector.Match;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Compiles AST selectors into bounded context-qualified query plans; does not emit violations or scores. */
public final class PolicyGraphMapper {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("policy.graph-mapper", "m6.1-v1");
    public record Limits(int maxBindings, ArchitectureQuery.Limits query) {
        public Limits {if(maxBindings<1||maxBindings>500_000)throw new IllegalArgumentException("Invalid policy mapping budget");Objects.requireNonNull(query);}
        public static Limits defaults(){return new Limits(200_000,ArchitectureQuery.Limits.defaults());}
    }
    /** For ACYCLIC these are eligible traversal edges, not cycle findings. */
    public record Candidate(EdgeView edge, Match eligibility) {
        public Candidate {Objects.requireNonNull(edge);Objects.requireNonNull(eligibility);if(eligibility==Match.NO)throw new IllegalArgumentException("Excluded candidate");}
        public ArchitectureCondition.Predicate predicate(){
            if(eligibility!=Match.YES)throw new IllegalStateException("Unknown selector eligibility must remain qualified before predicate execution");
            return new ArchitectureCondition.Predicate(List.of(new ArchitectureCondition.Term(List.of(edge.identity()))));
        }
    }
    public record RulePlan(Rule rule, Request request, AssessmentStatus status, Optional<ContentDigest> projection,
                           Map<String,Map<String,Match>> selections, List<Candidate> candidates, List<CapabilityGapRecord> gaps) {
        public RulePlan {var copy=new TreeMap<String,Map<String,Match>>();selections.forEach((k,v)->copy.put(k,Collections.unmodifiableMap(new TreeMap<>(v))));
            selections=Collections.unmodifiableMap(copy);candidates=List.copyOf(candidates);gaps=List.copyOf(gaps);}
    }
    public record Plan(ContentDigest compilation, ContentDigest graph, String context, Limits limits, AssessmentStatus status,
                       List<RulePlan> rules, List<CapabilityGapRecord> gaps) {
        public Plan {rules=List.copyOf(rules);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION,this)));}
    }
    public Plan bind(PolicyCompiler.Result compilation, ArchitectureQueryService service, String context, Limits limits) {
        Objects.requireNonNull(compilation);Objects.requireNonNull(service);Objects.requireNonNull(limits);
        var selected=service.graph().contexts().stream().filter(c->c.identity().equals(context)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Foreign policy context"));
        var evidenceContext=new EvidenceContext(service.graph().snapshot(),selected.analysis());
        var gaps=new ArrayList<CapabilityGapRecord>();var rules=new ArrayList<RulePlan>();
        if(compilation.policy().isEmpty()) {
            gaps.addAll(compilation.gaps());gaps.add(PolicyGaps.create(evidenceContext,VERSION,compilation.identity(),"COMPILATION_WITHHELD",context));
            return new Plan(compilation.identity(),service.graph().identity(),context,limits,AssessmentStatus.WITHHELD,rules,gaps);
        }
        int remaining=limits.maxBindings();boolean cancelled=false;
        for(var rule:compilation.policy().orElseThrow().rules()) {
            var header=rule.header();var request=new Request(service.graph().identity(),context,header.scope(),Projection.BOUNDED_UNION,Direction.OUTGOING,
                    header.relationships(),Population.all(),false,Optional.empty(),limits.query());
            var local=new ArrayList<CapabilityGapRecord>();var selections=new TreeMap<String,Map<String,Match>>();var candidates=new ArrayList<Candidate>();
            Optional<ContentDigest> projection=Optional.empty();boolean withheld=remaining<=0||cancelled;
            if(withheld)local.add(gap(evidenceContext,compilation,rule,request,cancelled?"MAPPING_CANCELLED":"MAPPING_LIMIT"));
            else {
                var projected=service.metricProjection(request);projection=Optional.of(projected.query());local.addAll(projected.gaps());
                cancelled=projected.operation()==Operation.CANCELLED;
                if(projected.operation()!=Operation.COMPLETED||projected.bounds().truncated()||projected.items().isEmpty()) {
                    withheld=true;local.add(gap(evidenceContext,compilation,rule,request,"PROJECTION_INCOMPLETE"));
                } else {
                    var view=projected.items().getFirst();var selectors=selectors(rule);
                    long cost=(long)view.entities().size()*selectors.size()+view.edges().size();
                    if(cost>remaining){remaining=0;withheld=true;local.add(gap(evidenceContext,compilation,rule,request,"MAPPING_LIMIT"));}
                    else {
                        remaining-=(int)cost;
                        for(var slot:selectors.entrySet()) {
                            var membership=new TreeMap<String,Match>();view.entities().forEach(e->membership.put(e.identity(),PolicySelector.match(slot.getValue(),e)));
                            if(membership.containsValue(Match.UNKNOWN))local.add(gap(evidenceContext,compilation,rule,request,"SELECTOR_METADATA_UNAVAILABLE"));
                            if(!membership.containsValue(Match.YES)&&!membership.containsValue(Match.UNKNOWN))local.add(gap(evidenceContext,compilation,rule,request,"SELECTOR_MATCH_EMPTY"));
                            selections.put(slot.getKey(),membership);
                        }
                        var reasons=new TreeSet<String>();
                        for(var edge:view.edges()) {
                            var match=eligibility(rule,selections,edge,reasons);if(match!=Match.NO)candidates.add(new Candidate(edge,match));
                        }
                        for(String reason:reasons)local.add(gap(evidenceContext,compilation,rule,request,reason));
                    }
                }
            }
            local=local.stream().distinct().sorted().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            rules.add(new RulePlan(rule,request,withheld?AssessmentStatus.WITHHELD:local.isEmpty()?AssessmentStatus.COMPLETE:AssessmentStatus.PARTIAL,projection,selections,candidates,local));gaps.addAll(local);
        }
        var status=rules.stream().allMatch(r->r.status()==AssessmentStatus.WITHHELD)?AssessmentStatus.WITHHELD:gaps.isEmpty()?AssessmentStatus.COMPLETE:AssessmentStatus.PARTIAL;
        return new Plan(compilation.identity(),service.graph().identity(),context,limits,status,
                rules,gaps.stream().distinct().sorted().toList());
    }
    private static Map<String,Selector> selectors(Rule rule) {
        return switch(rule) {
            case Forbidden f -> Map.of("from",f.from(),"to",f.to());case Boundary b -> Map.of("from",b.from(),"allowed",b.allowed());
            case Cycles c -> Map.of("within",c.within());case Layers l -> {var map=new TreeMap<String,Selector>();l.layers().forEach(v->map.put(v.name(),v.select()));yield map;}
        };
    }
    private static Match at(Map<String,Map<String,Match>> selections,String slot,String identity) {return selections.get(slot).getOrDefault(identity,Match.UNKNOWN);}
    private static Match eligibility(Rule rule,Map<String,Map<String,Match>> s,EdgeView edge,Set<String> reasons) {
        return switch(rule) {
            case Forbidden ignored -> at(s,"from",edge.source()).and(at(s,"to",edge.target()));
            case Boundary ignored -> at(s,"from",edge.source()).and(at(s,"allowed",edge.target()).not());
            case Cycles ignored -> at(s,"within",edge.source()).and(at(s,"within",edge.target()));
            case Layers l -> {
                var source=layer(s,edge.source(),reasons);var target=layer(s,edge.target(),reasons);
                if(source.isEmpty())yield Match.NO; // Outside the declared layer scope, explicitly excluded.
                if(source.orElseThrow().equals("?")||target.orElse("").equals("?"))yield Match.UNKNOWN;
                if(target.isEmpty()){reasons.add("LAYER_TARGET_UNMAPPED");yield Match.UNKNOWN;}
                yield source.equals(target)||l.allowed().getOrDefault(source.orElseThrow(),Set.of()).contains(target.orElseThrow())?Match.NO:Match.YES;
            }
        };
    }
    private static Optional<String> layer(Map<String,Map<String,Match>> s,String identity,Set<String> reasons) {
        var yes=s.entrySet().stream().filter(e->e.getValue().getOrDefault(identity,Match.UNKNOWN)==Match.YES).map(Map.Entry::getKey).toList();
        boolean unknown=s.values().stream().anyMatch(v->v.getOrDefault(identity,Match.UNKNOWN)==Match.UNKNOWN);
        if(yes.size()>1){reasons.add("LAYER_SELECTOR_OVERLAP");return Optional.of("?");}
        return unknown?Optional.of("?"):yes.stream().findFirst();
    }
    private static CapabilityGapRecord gap(EvidenceContext context,PolicyCompiler.Result compilation,Rule rule,Request request,String reason) {
        return PolicyGaps.create(context,VERSION,compilation.identity(),reason,List.of(rule.identity(),request.identity()));
    }
}
