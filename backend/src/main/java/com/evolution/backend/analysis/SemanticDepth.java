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
        return summary(bindings(graph),analyzed,recovery,attempted,resolved,categories,states,plans,evaluated);
    }

    public static Map<String,Object> summarize(UniversalArchitecturePipeline.DetailedResult result,KnowledgeGraph graph) {
        return summarize(result,bindings(graph));
    }
    public static Map<String,Object> summarize(UniversalArchitecturePipeline.DetailedResult result,Map<String,Long> edgeKinds) {
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
        return summary(edgeKinds,analyzed,recovery,attempted,resolved,categories,states,input.spring().size(),evaluated);
    }
    private static Map<String,Long> bindings(KnowledgeGraph graph) {
        var bindings=new TreeMap<String,Long>();
        for(var edge:graph.edges())if(Set.of("spring.selected-binding","spring.injection-candidate","spring.source-type-candidate","spring.source-wiring-evaluated").contains(edge.kind()))bindings.merge(edge.kind(),1L,Long::sum);
        return bindings;
    }
    /** Declaration-level relations that define architecture structure (type hierarchy, signatures, annotations). */
    static final Set<String> STRUCTURE=Set.of("java.extends","java.implements","java.permits","java.field-type","java.parameter-type","java.returns",
            "java.throws","java.annotated-with","java.type-argument","java.type-parameter-bound");
    /** Type-hierarchy and component/stereotype annotation relations required for architectural component & boundary recovery. */
    static final Set<String> HIERARCHY_AND_ANNOTATIONS=Set.of("java.extends","java.implements","java.permits","java.annotated-with");
    /** Minimum per-mille resolution threshold (95.0%) for passive architectural structure completion. */
    public static final long ARCHITECTURE_THRESHOLD_PER_MILLE=950L;
    /** Expression-level relations inside bodies; third-party calls here must not hide an exact structural skeleton. */
    static final Set<String> USAGE=Set.of("java.calls","java.constructor-calls","java.reads-field","java.writes-field","java.method-references","java.type-uses");

    public static boolean meetsPassiveArchitectureThreshold(
            Map<String,Object> semanticDepth,
            Map<String,Object> polyglotSummary,
            UniversalArchitecturePipeline.DetailedResult detailed,
            List<com.evolution.analysis.evidence.CapabilityGapRecord> polyglotGaps) {
        if (detailed == null
                || detailed.report().status() == com.evolution.analysis.ingestion.UniversalArchitectureReport.Status.TERMINAL_FAILURE
                || detailed.report().status() == com.evolution.analysis.ingestion.UniversalArchitectureReport.Status.CANCELLED) {
            return false;
        }
        boolean hasRejectedOrFailedUnits = detailed.report().issues().stream().anyMatch(i ->
                i.reason() == com.evolution.analysis.ingestion.IngestionEvidence.Reason.SEMANTIC_INPUT_REJECTED
                        || i.reason() == com.evolution.analysis.ingestion.IngestionEvidence.Reason.SEMANTIC_PROVIDER_FAILED);
        boolean hasWorkerRequired = detailed.report().gaps().stream()
                .anyMatch(g -> "JAVA_COMPATIBILITY_WORKER_REQUIRED".equals(g.reasonCode()))
                || (polyglotGaps != null && polyglotGaps.stream()
                        .anyMatch(g -> "JAVA_COMPATIBILITY_WORKER_REQUIRED".equals(g.reasonCode())));
        boolean hasUnresolvedRpcGap = polyglotGaps != null && polyglotGaps.stream()
                .anyMatch(g -> "GRPC_ROUTING_EVIDENCE_MISSING".equals(g.reasonCode()));
        long totalJavaFiles = detailed.report().metrics().javaFiles();
        return meetsPassiveArchitectureThreshold(
                semanticDepth,
                polyglotSummary,
                hasRejectedOrFailedUnits,
                hasWorkerRequired,
                totalJavaFiles,
                hasUnresolvedRpcGap);
    }

    public static boolean meetsPassiveArchitectureThreshold(
            Map<String,Object> semanticDepth,
            Map<String,Object> polyglotSummary,
            boolean hasRejectedOrFailedUnits,
            boolean hasCompatibilityWorkerRequired,
            long totalJavaFiles,
            boolean hasUnresolvedRpcRoutingGap) {
        if (semanticDepth == null || hasRejectedOrFailedUnits || hasCompatibilityWorkerRequired || hasUnresolvedRpcRoutingGap) {
            return false;
        }
        long analyzedContexts = number(semanticDepth.get("analyzedContexts"));
        if (analyzedContexts > 0) {
            var archRes = GraphJson.object(semanticDepth.get("architectureResolution"));
            var structure = GraphJson.object(archRes.get("structure"));
            long structAttempted = number(structure.get("attempted"));
            long structPerMille = number(structure.get("resolvedPerMille"));
            if (structAttempted <= 0 || structPerMille < ARCHITECTURE_THRESHOLD_PER_MILLE) {
                return false;
            }
            var relationships = GraphJson.object(semanticDepth.get("relationships"));
            var byCategory = GraphJson.object(relationships.get("byCategory"));
            long hierAttempted = 0L;
            long hierResolved = 0L;
            for (String cat : HIERARCHY_AND_ANNOTATIONS) {
                if (byCategory.containsKey(cat)) {
                    var row = GraphJson.object(byCategory.get(cat));
                    hierAttempted += number(row.get("attempted"));
                    hierResolved += number(row.get("resolved"));
                }
            }
            if (hierAttempted <= 0 || (hierResolved * 1000L / hierAttempted) < ARCHITECTURE_THRESHOLD_PER_MILLE) {
                return false;
            }
            if (polyglotSummary != null && polyglotSummary.containsKey("sourceSyntax")) {
                var syntax = GraphJson.object(polyglotSummary.get("sourceSyntax"));
                long docs = number(syntax.get("documents"));
                long parsed = number(syntax.get("parsedDocuments"));
                if (docs > 0 && (parsed * 1000L / docs) < ARCHITECTURE_THRESHOLD_PER_MILLE) {
                    return false;
                }
            }
            return true;
        }
        if (totalJavaFiles > 0 || polyglotSummary == null || !polyglotSummary.containsKey("sourceSyntax")) {
            return false;
        }
        var syntax = GraphJson.object(polyglotSummary.get("sourceSyntax"));
        long docs = number(syntax.get("documents"));
        long parsed = number(syntax.get("parsedDocuments"));
        long edges = number(polyglotSummary.get("edges"));
        if (edges <= 0 && syntax.get("categories") instanceof Map<?, ?> cats) {
            for (Object val : cats.values()) {
                edges += number(val);
            }
        }
        return docs > 0 && edges > 0 && (parsed * 1000L / docs) >= ARCHITECTURE_THRESHOLD_PER_MILLE;
    }

    public static String evidenceTier(
            com.evolution.analysis.ingestion.UniversalArchitectureReport.Status reportStatus,
            boolean exactComplete,
            boolean passiveArchitectureComplete) {
        if (exactComplete) {
            return "VERIFIED_EXACT";
        }
        if (passiveArchitectureComplete) {
            return "PASSIVE_ARCHITECTURE_RECOVERY";
        }
        if (reportStatus == com.evolution.analysis.ingestion.UniversalArchitectureReport.Status.TERMINAL_FAILURE
                || reportStatus == com.evolution.analysis.ingestion.UniversalArchitectureReport.Status.CANCELLED) {
            return "FAILED";
        }
        return "PARTIAL_RECOVERY";
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
    /** Splits the same observed denominator by relation role. Nothing is re-counted, re-labelled or removed; per-mille keeps canonical JSON integral. */
    static Map<String,Object> architectureResolution(Map<String,long[]> categories){
        long[] structure=new long[2],usage=new long[2];
        categories.forEach((category,counts)->{
            long[] target=STRUCTURE.contains(category)?structure:USAGE.contains(category)?usage:null;
            if(target!=null){target[0]+=counts[0];target[1]+=counts[1];}
        });
        var result=new TreeMap<String,Object>();
        result.put("structure",role(structure));result.put("usage",role(usage));
        result.put("interpretation","Structure covers declaration-level relations; usage covers expression-level relations. Both are attributed-fact ratios over observed Java relationships, not recall against an external ground truth.");
        return Collections.unmodifiableMap(result);
    }
    private static Map<String,Object> role(long[] counts){
        return Map.of("attempted",counts[0],"resolved",counts[1],"notResolved",counts[0]-counts[1],"resolvedPerMille",counts[0]==0?0L:counts[1]*1000/counts[0]);
    }
    private static Map<String,Object> summary(Map<String,Long> bindings,long analyzed,long recovery,long attempted,long resolved,
            Map<String,long[]> categories,Map<String,Long> states,long plans,long evaluated){
        var byCategory=new TreeMap<String,Object>();
        categories.forEach((key,value)->byCategory.put(key,Map.of("attempted",value[0],"resolved",value[1],"notResolved",value[0]-value[1])));
        var summary=new TreeMap<String,Object>();
        summary.put("scope","CAPTURED_JAVA_SOURCE_CONTEXTS");
        summary.put("level",analyzed==0?"NO_ANALYSIS":resolved==0?"SURFACE_ONLY":recovery>0||resolved<attempted?"PARTIAL_RELATIONSHIPS":"ATTRIBUTED_RELATIONSHIPS");
        summary.put("analyzedContexts",analyzed);summary.put("sourceRecoveryContexts",recovery);
        summary.put("relationships",Map.of("attempted",attempted,"resolved",resolved,"notResolved",attempted-resolved,"byCategory",byCategory,"byAttributionStatus",states));
        summary.put("architectureResolution",architectureResolution(categories));
        long sourceContexts=bindings.getOrDefault("spring.source-wiring-evaluated",0L);
        summary.put("spring",Map.of("status",plans==0?(sourceContexts>0?"SOURCE_CANDIDATES_ONLY":"NOT_EVALUATED"):evaluated==0?(sourceContexts>0?"PLAN_AND_SOURCE_CANDIDATES":"PLAN_ONLY"):evaluated<plans?"PARTIALLY_EVALUATED":"EVALUATED_IN_SELECTED_CONFIGURATION_SPACE",
                "planContexts",plans,"evaluatedContexts",evaluated,
                "sourceEvaluatedContexts",sourceContexts,"sourceTypeCandidateRows",bindings.getOrDefault("spring.source-type-candidate",0L),
                "selectedBindingFactRows",bindings.getOrDefault("spring.selected-binding",0L),"candidateBindingFactRows",bindings.getOrDefault("spring.injection-candidate",0L)));
        summary.put("interpretation","Resolved observations are attributed facts, not measured semantic correctness. Zero bindings without evaluation do not establish absence of dependencies.");
        return Collections.unmodifiableMap(summary);
    }
}
