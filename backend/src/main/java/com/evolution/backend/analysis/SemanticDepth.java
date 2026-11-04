package com.evolution.backend.analysis;

import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import java.util.*;

/** Observed analysis depth, separate from file/type inventory and operational completion.
 * Counts describe captured Java contexts; they are not accuracy or runtime coverage scores. */
public final class SemanticDepth {
    private SemanticDepth() {}

    /** Authenticated native scans already retain the neutral observation rows in their graph.
     * Read one row at a time; never reconstruct a second aggregate frontend payload. */
    public static Map<String,Object> summarize(KnowledgeGraph graph) {
        long analyzed=0,recovery=0,attempted=0,resolved=0,evaluated=0,plans=0;
        var categories=new TreeMap<String,long[]>();var states=new TreeMap<String,Long>();
        for(var evidence:graph.evidence())switch(evidence.family()) {
            case "frontend.result-index"->analyzed++;
            case "spring.context"->plans++;
            case "spring.explicit-result","spring.symbolic-result"->evaluated++;
            case "source.request"->{
                var request=GraphJson.object(GraphJson.read(evidence.canonicalJson(),GraphJson.Limits.defaults()));
                var manifest=GraphJson.object(request.get("manifest"));var configuration=GraphJson.object(manifest.get("configuration"));
                if("SOURCE_RECOVERY".equals(GraphJson.object(configuration.get("values")).get("java.resolution-mode")))recovery++;
            }
            default->{}
        }
        // The same immutable observation payload can belong to multiple analysis contexts.
        for(var node:graph.nodes())if(node.kind().equals("OBSERVATION")){
            var evidence=node.evidence().stream().map(graph::evidence).flatMap(Optional::stream).filter(e->e.family().equals("java.observation")).findFirst().orElseThrow();
                var row=GraphJson.object(GraphJson.read(evidence.canonicalJson(),GraphJson.Limits.defaults()));var category=GraphJson.string(GraphJson.object(row.get("category")).get("value"));
                if(category.equals("java.declares")||category.equals("java.has-parameter"))continue;
                attempted++;var counts=categories.computeIfAbsent(category,key->new long[2]);counts[0]++;
                var status=GraphJson.string(row.get("attribution"));states.merge(status,1L,Long::sum);
                if(status.equals("RESOLVED")&&row.get("mappedOccurrence")!=null){resolved++;counts[1]++;}
        }
        return summary(graph,analyzed,recovery,attempted,resolved,categories,states,plans,evaluated);
    }

    public static Map<String,Object> summarize(UniversalArchitecturePipeline.DetailedResult result,KnowledgeGraph graph) {
        var input=result.graphInput().orElseThrow();
        long analyzed=0,recovery=0,attempted=0,resolved=0;
        var categories=new TreeMap<String,long[]>();
        var states=new TreeMap<String,Long>();
        if(input.ingestion().isPresent()) {
            var ingestion=input.ingestion().orElseThrow();
            recovery=ingestion.sourceIngestion().outcomes().stream().flatMap(o->o.request().stream()).filter(r->r.sourceRecovery()).count();
            for(var unit:ingestion.units())if(unit.frontend().isPresent()) {
                analyzed++;
                for(var observation:unit.frontend().orElseThrow().observations()) {
                    String category=observation.category().value();
                    if(category.equals("java.declares")||category.equals("java.has-parameter"))continue;
                    attempted++;var counts=categories.computeIfAbsent(category,key->new long[2]);counts[0]++;
                    states.merge(observation.attribution().name(),1L,Long::sum);
                    if(observation.attribution()==SemanticStatus.RESOLVED&&observation.mappedOccurrence().isPresent()) {resolved++;counts[1]++;}
                }
            }
        }
        long evaluated=input.spring().stream().filter(s->s.explicitTruth().isPresent()||s.symbolicTruth().isPresent()).count();
        return summary(graph,analyzed,recovery,attempted,resolved,categories,states,input.spring().size(),evaluated);
    }
    private static Map<String,Object> summary(KnowledgeGraph graph,long analyzed,long recovery,long attempted,long resolved,
            Map<String,long[]> categories,Map<String,Long> states,long plans,long evaluated){
        var byCategory=new TreeMap<String,Object>();
        categories.forEach((key,value)->byCategory.put(key,Map.of("attempted",value[0],"resolved",value[1],"notResolved",value[0]-value[1])));
        var bindings=new TreeMap<String,Long>();
        for(var edge:graph.edges())if(Set.of("spring.selected-binding","spring.injection-candidate").contains(edge.kind()))bindings.merge(edge.kind(),1L,Long::sum);
        var summary=new TreeMap<String,Object>();
        summary.put("scope","CAPTURED_JAVA_SOURCE_CONTEXTS");
        summary.put("level",analyzed==0?"NO_ANALYSIS":resolved==0?"SURFACE_ONLY":recovery>0||resolved<attempted?"PARTIAL_RELATIONSHIPS":"ATTRIBUTED_RELATIONSHIPS");
        summary.put("analyzedContexts",analyzed);summary.put("sourceRecoveryContexts",recovery);
        summary.put("relationships",Map.of("attempted",attempted,"resolved",resolved,"notResolved",attempted-resolved,"byCategory",byCategory,"byAttributionStatus",states));
        summary.put("spring",Map.of("status",plans==0?"NOT_EVALUATED":evaluated==0?"PLAN_ONLY":evaluated<plans?"PARTIALLY_EVALUATED":"EVALUATED_IN_SELECTED_CONFIGURATION_SPACE",
                "planContexts",plans,"evaluatedContexts",evaluated,
                "selectedBindingFactRows",bindings.getOrDefault("spring.selected-binding",0L),"candidateBindingFactRows",bindings.getOrDefault("spring.injection-candidate",0L)));
        summary.put("interpretation","Resolved observations are attributed facts, not measured semantic correctness. Zero bindings without evaluation do not establish absence of dependencies.");
        return Collections.unmodifiableMap(summary);
    }
}
