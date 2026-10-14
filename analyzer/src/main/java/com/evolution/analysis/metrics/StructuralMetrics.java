package com.evolution.analysis.metrics;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.metrics.RatioEvidence;
import com.evolution.analysis.query.ArchitectureQuery;
import java.math.*;
import java.util.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Metrics of the selected full component graph, never of a returned UI page. */
final class StructuralMetrics {
    private static final Set<String> MARTIN=Set.of("metric.instability","metric.abstractness","metric.distance");
    final MetricEvidence input;final Map<String,EntityView> vertices=new TreeMap<>();
    final Map<String,SortedSet<String>> out=new TreeMap<>(),in=new TreeMap<>();
    final Set<String> selfLoops=new HashSet<>();
    final Map<String,List<MetricEvidence.Declaration>> types=new HashMap<>();
    final Request projected;
    StructuralMetrics(MetricEvidence input){
        this.input=input;projected=input.request.scope()==Scope.SPRING_COMPONENT?input.request:input.productionQuery();
        // Abstractness needs type population/shape evidence, not successful dependency extraction.
        var full=new MetricProjection(List.of(),List.of(),List.of(),List.of());
        if(!input.metric.metric().id().equals("metric.abstractness")){
            var projection=input.queries.metricProjection(projected);input.absorb(projection);full=projection.items().getFirst();
        }
        var active=new HashSet<>(full.selectedDefinitions());
        full.edges().stream().filter(EdgeView::definite).forEach(e->{active.add(e.source());active.add(e.target());input.references.addAll(e.evidence());});
        for(var entity:full.entities()){
            if(input.request.scope()==Scope.SPRING_COMPONENT&&!active.contains(entity.identity()))continue;
            if(!input.spend(1))break;vertices.put(entity.identity(),entity);out.put(entity.identity(),new TreeSet<>());in.put(entity.identity(),new TreeSet<>());
        }
        for(var edge:full.edges()){
            if(!input.spend(2))break;if(!edge.definite()){input.gap("UNKNOWN_EDGE_EXCLUDED_FROM_DEFINITE_METRIC");continue;}
            if(edge.source().equals(edge.target())&&out.containsKey(edge.source()))selfLoops.add(edge.source());
            if(!edge.source().equals(edge.target())&&out.containsKey(edge.source())&&out.containsKey(edge.target())){out.get(edge.source()).add(edge.target());in.get(edge.target()).add(edge.source());}
        }
        // Java populations still need validation for non-Martin metrics. Spring vertices instead come
        // from definition activation; a Java declaration need not have a Martin component owner there.
        if(input.request.scope()!=Scope.SPRING_COMPONENT)for(var declaration:input.declarations.values())if(input.production(declaration)){
            input.references.add(declaration.evidence());
            if(!MARTIN.contains(input.metric.metric().id()))continue;
            String owner=switch(input.request.scope()){case TYPE->declaration.identity();case PACKAGE->input.packages.get(declaration.identity());case MODULE->declaration.module();default->null;};
            if(owner==null){input.populationUnknown=true;input.gap("COMPONENT_OWNER_UNAVAILABLE");continue;}
            types.computeIfAbsent(owner,k->new ArrayList<>()).add(declaration);
        }
    }
    long pairs(){return out.values().stream().mapToLong(Set::size).sum();}
    Map<String,Object> details(){
        var detail=new TreeMap<String,Object>();detail.put("vertices",vertices.size());detail.put("distinctInternalPairs",pairs());detail.put("population",input.metric.population());
        var exposed=new TreeMap<String,Long>();long unresolved=0;
        for(var edge:input.graph.edges())if(edge.context().equals(input.request.context())&&input.request.relationshipKinds().contains(edge.kind())){
            if(edge.target().state()!=com.evolution.analysis.graph.KnowledgeGraph.TargetState.RESOLVED){unresolved++;continue;}
            String target=edge.target().identities().getFirst();var decl=input.declarations.get(target);
            if(decl!=null&&!input.production(decl))exposed.merge(decl.origin()+":"+Optional.ofNullable(input.documents.get(decl.document())).map(MetricEvidence.Document::role).orElse("UNKNOWN"),1L,Long::sum);
        }
        detail.put("excludedExposureRowsByOriginAndRole",exposed);detail.put("unresolvedRelationshipRows",unresolved);
        detail.put("exposureUnit","raw relationship rows, not internal distinct component pairs");return detail;
    }
    ArchitectureMetric.Result compute(String subject,Optional<ArchitectureMetric.LayerMapping> layers){
        String id=input.metric.metric().id();var detail=details();boolean whole=subject.equals(input.request.context());
        boolean martin=MARTIN.contains(id);
        boolean emptyComponent=martin&&input.nodes.containsKey(subject)&&input.nodes.get(subject).kind().equals(input.request.scope().name());
        if(!whole&&!vertices.containsKey(subject)&&!emptyComponent)return input.absent(subject,AssessmentStatus.NOT_APPLICABLE,"SUBJECT_OUTSIDE_DECLARED_POPULATION",detail,Optional.empty(),layers);
        if(!whole&&Set.of("metric.density","metric.hotspots","metric.concentration","metric.crossings","metric.cycles","metric.scc").contains(id))return input.absent(subject,AssessmentStatus.NOT_APPLICABLE,"WHOLE_CONTEXT_SUBJECT_REQUIRED",detail,Optional.empty(),layers);
        long ca=whole?pairs():in.getOrDefault(subject,Collections.emptySortedSet()).size(),ce=whole?pairs():out.getOrDefault(subject,Collections.emptySortedSet()).size();
        if(id.equals("metric.ca")||id.equals("metric.fan-in"))return input.count(subject,ca,detail,layers);
        if(id.equals("metric.ce")||id.equals("metric.fan-out"))return input.count(subject,ce,detail,layers);
        if(martin){
            if(whole)return input.absent(subject,AssessmentStatus.NOT_APPLICABLE,"COMPONENT_SUBJECT_REQUIRED",detail,Optional.empty(),layers);
            long n=types.getOrDefault(subject,List.of()).size(),na=0;boolean shapesMissing=false;
            for(var type:id.equals("metric.instability")?List.<MetricEvidence.Declaration>of():types.getOrDefault(subject,List.of())){
                var shape=input.shapes.get(type.identity());if(shape==null){shapesMissing=true;input.gap("TYPE_SHAPE_UNAVAILABLE");continue;}input.references.add(shape.evidence());
                if(Set.of("INTERFACE","ANNOTATION").contains(shape.kind())||shape.kind().equals("CLASS")&&shape.abstractType())na++;
            }
            detail.putAll(Map.of("Ca",ca,"Ce",ce,"N",n,"Na",na,"annotationUsesIncluded",false));
            if(input.exhausted||input.populationUnknown||input.typeUnknown||shapesMissing||!input.gaps.isEmpty())return input.absent(subject,AssessmentStatus.WITHHELD,"INCOMPLETE_METRIC_POPULATION_OR_DEPENDENCIES",detail,Optional.empty(),layers);
            BigDecimal numerator,denominator;
            if(id.equals("metric.instability")){numerator=BigDecimal.valueOf(ce);denominator=BigDecimal.valueOf(ca+ce);}
            else if(id.equals("metric.abstractness")){numerator=BigDecimal.valueOf(na);denominator=BigDecimal.valueOf(n);}
            else {denominator=BigDecimal.valueOf(n).multiply(BigDecimal.valueOf(ca+ce));numerator=BigDecimal.valueOf(na).multiply(BigDecimal.valueOf(ca+ce)).add(BigDecimal.valueOf(ce).multiply(BigDecimal.valueOf(n))).subtract(denominator).abs();}
            return ratio(subject,numerator,denominator,detail,layers);
        }
        if(id.equals("metric.density")){long n=vertices.size();return ratio(subject,BigDecimal.valueOf(pairs()),BigDecimal.valueOf(n).multiply(BigDecimal.valueOf(n-1)),detail,layers);}
        if(id.equals("metric.hotspots")||id.equals("metric.concentration")){
            var ranking=vertices.keySet().stream().sorted(Comparator.<String>comparingInt(v->id.equals("metric.concentration")?in.get(v).size():in.get(v).size()+out.get(v).size()).reversed().thenComparing(v->v)).toList();
            detail.put("ranking",ranking.stream().limit(input.limits.maxDetailRows()).map(v->Map.of("identity",v,"fanIn",in.get(v).size(),"fanOut",out.get(v).size())).toList());
            detail.put("rankingPopulation",ranking.size());detail.put("rankingTruncated",ranking.size()>input.limits.maxDetailRows());
            if(id.equals("metric.hotspots"))return input.count(subject,pairs(),detail,layers);
            return ratio(subject,BigDecimal.valueOf(ranking.isEmpty()?0:in.get(ranking.getFirst()).size()),BigDecimal.valueOf(pairs()),detail,layers);
        }
        if(id.equals("metric.crossings")){
            if(layers.isEmpty())return input.absent(subject,AssessmentStatus.NOT_APPLICABLE,"VERSIONED_LAYER_MAPPING_REQUIRED",detail,Optional.empty(),layers);
            long crossings=0;for(String source:out.keySet())for(String target:out.get(source)){
                String a=layers.orElseThrow().componentLayers().get(source),b=layers.orElseThrow().componentLayers().get(target);
                if(a==null||b==null){input.gap("LAYER_ASSIGNMENT_UNAVAILABLE");continue;}if(!a.equals(b))crossings++;
            }
            detail.put("mapping",layers.orElseThrow().identity());return input.count(subject,crossings,detail,layers);
        }
        if(id.equals("metric.cycles")){
            var cycles=input.queries.findCycles(projected);input.absorb(cycles);detail.put("witnessesReturned",cycles.items().size());detail.put("bounds",cycles.bounds());
            detail.put("witnesses",cycles.items().stream().limit(input.limits.maxDetailRows()).map(p->Map.of("identity",p.identity(),"vertices",p.vertices(),"feasibility",p.feasibility(),"witness",p.witness())).toList());
            if(cycles.exactTotal().isEmpty())return input.absent(subject,AssessmentStatus.WITHHELD,"EXACT_CYCLE_TOTAL_UNAVAILABLE",detail,Optional.empty(),layers);
            return input.count(subject,cycles.exactTotal().orElseThrow(),detail,layers);
        }
        if(id.equals("metric.reachability")){
            if(whole)return input.absent(subject,AssessmentStatus.NOT_APPLICABLE,"COMPONENT_SUBJECT_REQUIRED",detail,Optional.empty(),layers);
            var incoming=new Request(projected.graph(),projected.context(),projected.scope(),projected.projection(),Direction.INCOMING,projected.relationshipKinds(),projected.population(),projected.includeMust(),projected.realization(),projected.limits());
            var paths=input.queries.reachability(incoming,subject);input.absorb(paths);detail.put("bounds",paths.bounds());
            detail.put("distances",paths.items().stream().limit(input.limits.maxDetailRows()).map(p->Map.of("identity",p.vertices().getLast(),"distance",p.edges().size(),"path",p.identity())).toList());
            return input.count(subject,paths.items().stream().map(p->p.vertices().getLast()).distinct().count(),detail,layers);
        }
        if(id.equals("metric.scc")){
            var components=components();detail.put("sccCount",components.size());detail.put("sccSizes",components.stream().map(Set::size).sorted(Comparator.reverseOrder()).limit(input.limits.maxDetailRows()).toList());
            detail.put("participants",components.stream().filter(this::cyclic).limit(input.limits.maxDetailRows()).map(s->s.stream().limit(input.limits.maxDetailRows()).toList()).toList());
            detail.put("cyclicComponents",components.stream().filter(this::cyclic).count());detail.put("interpretation","structural SCCs, not jointly feasible cycle count");return input.count(subject,components.size(),detail,layers);
        }
        throw new IllegalArgumentException("Not a structural metric");
    }
    private boolean cyclic(Set<String> component){return component.size()>1||component.stream().anyMatch(selfLoops::contains);}
    private ArchitectureMetric.Result ratio(String subject,BigDecimal n,BigDecimal d,Map<String,Object> details,Optional<ArchitectureMetric.LayerMapping> layers){
        if(input.incomplete())return input.absent(subject,AssessmentStatus.WITHHELD,"INCOMPLETE_RATIO_POPULATION",details,Optional.empty(),layers);
        var ratio=new RatioEvidence(n,d);
        if(d.signum()==0)return input.absent(subject,AssessmentStatus.NOT_APPLICABLE,"ZERO_DENOMINATOR",details,Optional.of(ratio),layers);
        return input.result(subject,AssessmentStatus.COMPLETE,Optional.of(ArchitectureMetric.divide(n,d)),Optional.of(ratio),details,layers);
    }
    private List<SortedSet<String>> components(){
        if(!input.spend(6L*vertices.size()+2*pairs()))return List.of();
        var seen=new HashSet<String>();var finished=new ArrayList<String>();
        record Frame(String node,boolean exit){}var stack=new ArrayDeque<Frame>();
        for(String root:vertices.keySet())if(!seen.contains(root)){
            stack.push(new Frame(root,false));while(!stack.isEmpty()){
                var frame=stack.pop();if(frame.exit()){finished.add(frame.node());continue;}if(!seen.add(frame.node()))continue;
                stack.push(new Frame(frame.node(),true));for(String target:out.get(frame.node()))if(!seen.contains(target))stack.push(new Frame(target,false));
            }
        }
        seen.clear();var result=new ArrayList<SortedSet<String>>();
        for(int i=finished.size()-1;i>=0;i--){String root=finished.get(i);if(!seen.add(root))continue;var component=new TreeSet<String>();var pending=new ArrayDeque<String>();pending.push(root);
            while(!pending.isEmpty()){String node=pending.pop();component.add(node);for(String parent:in.get(node))if(seen.add(parent))pending.push(parent);}result.add(component);}
        result.sort(Comparator.comparing(SortedSet::first));return result;
    }
}
