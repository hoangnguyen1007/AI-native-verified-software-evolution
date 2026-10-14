package com.evolution.analysis.metrics;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.metrics.*;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.*;
import java.math.*;
import java.util.*;
import java.util.function.Supplier;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Bounded neutral evidence reader. Keeps small typed indices, never parser objects or source text in results. */
final class MetricEvidence {
    record Document(String identity,String module,String role,Optional<ContentDigest> digest) {}
    record Declaration(String identity,String kind,String origin,String module,String document,String canonicalName,String status,ContentDigest evidence) {}
    record Shape(String kind,boolean abstractType,ContentDigest evidence) {}
    final KnowledgeGraph graph;final Request request;final MetricCatalog.Entry metric;final ArchitectureMetric.Limits limits;
    final ArchitectureQueryService queries;final Supplier<StopReason> guard;
    final Map<String,KnowledgeGraph.Node> nodes=new TreeMap<>();final Map<String,Document> documents=new TreeMap<>();
    final Map<String,Declaration> declarations=new TreeMap<>();final Map<String,Shape> shapes=new HashMap<>();
    final Map<String,String> packages=new HashMap<>();
    final SortedSet<ContentDigest> references=new TreeSet<>();final SortedSet<String> reasons=new TreeSet<>();
    final Map<String,CapabilityGapRecord> gaps=new TreeMap<>();long facts,sourceBytes;boolean exhausted,populationUnknown,typeUnknown;
    final Set<String> recordedGapReasons=new HashSet<>();
    final List<Bounds> queryBounds=new ArrayList<>();
    MetricEvidence(KnowledgeGraph graph,Request request,MetricCatalog.Entry metric,ArchitectureMetric.Limits limits,Supplier<StopReason> guard){
        this.graph=graph;this.request=request;this.metric=metric;this.limits=limits;this.guard=guard;queries=new ArchitectureQueryService(graph,guard,Optional.empty());
        if(!graph.identity().equals(request.graph())||graph.contexts().stream().noneMatch(c->c.identity().equals(request.context())))throw new IllegalArgumentException("Foreign metric graph/context");
        for(var node:graph.nodes())if(node.context().equals(request.context())){
            if(!spend(1))break;nodes.put(node.identity(),node);
            boolean hasDocument=node.kind().equals("SOURCE_DOCUMENT")&&node.evidence().stream().anyMatch(ref->graph.evidence(ref).orElseThrow().family().equals("source.document"));
            if(node.kind().equals("SOURCE_DOCUMENT"))for(var ref:node.evidence()){
                var evidence=graph.evidence(ref).orElseThrow();Map<String,Object> value;
                if(evidence.family().equals("source.document"))value=read(evidence);
                else if(!hasDocument&&evidence.family().equals("source.bytes"))value=object(read(evidence).get("document"));else continue;
                if(value.isEmpty())continue;String module=text(value,"module"),role=text(value,"classification");
                try{
                    new com.evolution.analysis.contract.identity.ModuleIdentity(module);com.evolution.analysis.contract.source.SourceClassification.valueOf(role);
                    var document=new Document(node.identity(),module,role,Optional.of(new ContentDigest(text(value,"contentDigest"))));
                    var previous=documents.putIfAbsent(node.identity(),document);if(previous!=null&&!previous.equals(document)){populationUnknown=true;gap("CONFLICTING_SOURCE_POPULATION");}
                }catch(IllegalArgumentException invalid){populationUnknown=true;references.add(ref);gap("INVALID_SOURCE_DOCUMENT_METADATA");}
            }
        }
        for(var node:nodes.values()){
            if(!spend(1))break;
            if(node.kind().equals("CAPABILITY_GAP")||node.kind().equals("PROVIDER_CONFLICT")){gap("UPSTREAM_CAPABILITY_GAP");references.addAll(node.evidence());}
            for(var ref:node.evidence()){
                var evidence=graph.evidence(ref).orElseThrow();if(!evidence.family().equals("java.declaration"))continue;
                var value=read(evidence);var entity=object(value.get("entity"));if(!node.identity().equals(entity.get("identity")))continue;
                var span=object(entity.get("declaration"));
                declarations.put(node.identity(),new Declaration(node.identity(),node.kind(),text(entity,"origin"),text(entity,"stableScope"),text(span,"document"),text(entity,"canonicalName"),text(value,"status"),ref));
            }
        }
        var selected=graph.contexts().stream().filter(c->c.identity().equals(request.context())).findFirst().orElseThrow();
        if(nodes.values().stream().anyMatch(n->n.kind().equals("TYPE")&&!declarations.containsKey(n.identity()))){populationUnknown=true;gap("TYPE_DECLARATION_UNAVAILABLE");}
        // graph.depends-on contains only resolved projections: retain the raw semantic denominator too.
        for(var node:graph.nodes())if(node.context().equals(request.context())||selected.analysis().map(a->a.value().equals(node.context())).orElse(false))for(var ref:node.evidence()){
            var evidence=graph.evidence(ref).orElseThrow();if(!evidence.family().equals("java.observation"))continue;
            var row=read(evidence);String status=text(row,"attribution");
            if(!status.equals("RESOLVED")||row.get("mappedOccurrence")==null){references.add(ref);gap("SEMANTIC_OBSERVATION_INCOMPLETE");}
        }
        for(var node:graph.nodes())if(!nodes.containsKey(node.identity())&&(node.context().equals(graph.snapshot().value())||selected.analysis().map(a->a.value().equals(node.context())).orElse(false))&&Set.of("CAPABILITY_GAP","PROVIDER_CONFLICT").contains(node.kind())){
            if(!spend(1))break;references.addAll(node.evidence());gap("UPSTREAM_SNAPSHOT_GAP");
        }
        var typeIds=declarations.values().stream().filter(d->d.kind().equals("TYPE")).map(Declaration::identity).collect(java.util.stream.Collectors.toSet());
        for(var evidence:graph.evidence())if(evidence.family().equals("java.type-shape")){
            var value=read(evidence);String type=text(value,"type");if(!typeIds.contains(type))continue;
            if(!spend(1))break;
            if(!Set.of("CLASS","INTERFACE","RECORD","ENUM","ANNOTATION").contains(text(value,"kind"))||!(value.get("abstractType") instanceof Boolean)){typeUnknown=true;gap("INVALID_TYPE_SHAPE");continue;}
            var shape=new Shape(text(value,"kind"),Boolean.TRUE.equals(value.get("abstractType")),evidence.identity());
            var previous=shapes.putIfAbsent(type,shape);if(previous!=null&&!previous.equals(shape)){typeUnknown=true;gap("CONFLICTING_TYPE_SHAPE");}
        }
        for(var edge:graph.edges())if(edge.context().equals(request.context())&&edge.kind().equals("graph.contains")&&edge.target().state()==KnowledgeGraph.TargetState.RESOLVED){
            if(!spend(1))break;var source=nodes.get(edge.source());if(source!=null&&source.kind().equals("PACKAGE"))packages.put(edge.target().identities().getFirst(),source.identity());
        }
    }
    boolean spend(long units){
        if(exhausted)return false;var stop=guard.get();if(stop!=StopReason.NONE){exhausted=true;gap("OPERATION_"+stop);return false;}
        if(units<0||units>limits.maxFacts()-facts){exhausted=true;gap("METRIC_FACT_LIMIT");return false;}facts+=units;return true;
    }
    Map<String,Object> read(GraphEvidence evidence){
        // Charge transient payload decoding too; per-record limits alone do not bound cumulative work.
        if(!spend(1L+(evidence.canonicalJson().length()+63L)/64))return Map.of();
        try{return object(GraphJson.read(evidence.canonicalJson(),new GraphJson.Limits(limits.maxPayloadCharacters(),128,limits.maxFacts())));}
        catch(IllegalArgumentException invalid){gap("METRIC_PAYLOAD_LIMIT_OR_INVALID");exhausted=true;return Map.of();}
    }
    static Map<String,Object> object(Object value){return value instanceof Map<?,?>?GraphJson.object(value):Map.of();}
    static String text(Map<String,Object> value,String key){return value.get(key) instanceof String s?s:"";}
    boolean includesDocument(Document doc){return(request.population().modules().isEmpty()||request.population().modules().contains(doc.module()))
            &&(request.population().sourceSets().isEmpty()||request.population().sourceSets().contains(doc.role()));}
    boolean includes(Declaration decl){var pop=request.population();var doc=documents.get(decl.document());
        return(pop.nodeKinds().isEmpty()||pop.nodeKinds().contains(decl.kind()))&&(pop.modules().isEmpty()||pop.modules().contains(decl.module()))
                &&(pop.origins().isEmpty()||pop.origins().contains(decl.origin()))&&(pop.sourceSets().isEmpty()||doc!=null&&pop.sourceSets().contains(doc.role()));}
    boolean production(Declaration decl){
        if(!decl.kind().equals("TYPE")||!decl.origin().equals("PROJECT"))return false;
        var doc=documents.get(decl.document());if(doc==null||doc.role().isEmpty()){populationUnknown=true;gap("SOURCE_POPULATION_UNAVAILABLE");return false;}
        if(!doc.role().equals("MAIN"))return false;
        if(!decl.status().equals("RESOLVED")){typeUnknown=true;gap("QUALIFIED_TYPE_DECLARATION");}
        var p=request.population();return(p.modules().isEmpty()||p.modules().contains(decl.module()))&&(p.origins().isEmpty()||p.origins().contains("PROJECT"))
                &&(p.sourceSets().isEmpty()||p.sourceSets().contains("MAIN"));
    }
    Request productionQuery(){
        var p=request.population();var origins=p.origins().isEmpty()||p.origins().contains("PROJECT")?Set.of("PROJECT"):p.origins();
        var roles=p.sourceSets().isEmpty()||p.sourceSets().contains("MAIN")?Set.of("MAIN"):p.sourceSets();
        return new Request(request.graph(),request.context(),request.scope(),request.projection(),request.direction(),request.relationshipKinds(),
                new Population(Set.of(request.scope().name()),p.modules(),origins,roles),request.includeMust(),request.realization(),request.limits());
    }
    <T> void absorb(ArchitectureQuery.Result<T> result){result.gaps().forEach(g->gaps.put(g.gapIdentity().value(),g));references.addAll(result.evidence());
        queryBounds.add(result.bounds());
        if(result.status()!=AssessmentStatus.COMPLETE)reasons.add("QUERY_"+result.status());if(result.bounds().truncated())reasons.add("QUERY_"+result.bounds().stopReason());
        if(result.operation()!=Operation.COMPLETED)reasons.add("OPERATION_"+result.operation());}
    void gap(String reason){
        if(!recordedGapReasons.add(reason))return;
        reasons.add(reason);var selected=graph.contexts().stream().filter(c->c.identity().equals(request.context())).findFirst().orElseThrow();
        var observation=ProviderObservationReference.create(ArchitectureMetric.VERSION,"metrics.inputs",graph.identity(),ArchitectureMetric.digest(List.of(request.identity(),metric.identity(),reason)));
        var subject=EvidenceSubject.observation(observation.identity());
        var requirement=new EvidenceRequirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,"metrics.complete-inputs",List.of(subject),EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of("Supply complete compatible typed metric input evidence or adequate bounded resources."));
        var gap=CapabilityGapRecord.create(new VersionedIdentifier("evidence.metric-gaps","m5.3-v1"),new EvidenceContext(graph.snapshot(),selected.analysis()),ArchitectureMetric.VERSION,"metrics.architecture",reason,subject,List.of(),List.of(observation),List.of(requirement),List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.METRIC,metric.metric().id())),List.of(),List.of(),List.of("Observed inputs are retained; missing facts cannot prove healthy architecture."));
        gaps.put(gap.gapIdentity().value(),gap);
    }
    ArchitectureMetric.Context context(Optional<ArchitectureMetric.LayerMapping> layers){
        var analyses=graph.contexts().stream().filter(c->c.identity().equals(request.context())).flatMap(c->c.analysis().stream()).toList();
        return new ArchitectureMetric.Context(graph.identity(),request.context(),analyses,request,metric.population(),limits,layers.map(ArchitectureMetric.LayerMapping::identity));
    }
    ArchitectureMetric.Result result(String subject,AssessmentStatus status,Optional<BigDecimal> value,Optional<RatioEvidence> ratio,Map<String,Object> details,Optional<ArchitectureMetric.LayerMapping> layers){
        if(status==AssessmentStatus.COMPLETE&&(!gaps.isEmpty()||!reasons.isEmpty()))status=AssessmentStatus.PARTIAL;
        var limitations=new ArrayList<>(reasons);limitations.add("CATALOG_PROVISIONAL; projection="+request.projection()+"; no runtime/health/policy verdict or uniform configuration probability.");
        if(references.size()>limits.maxDetailRows())limitations.add("EVIDENCE_REFERENCES_TRUNCATED; full closure is in the graph identified by context.graph.");
        var qualifiedDetails=new TreeMap<String,Object>(details);qualifiedDetails.put("queryExecutions",queryBounds);qualifiedDetails.put("metricFactsUsed",facts);
        return new ArchitectureMetric.Result(metric.metric(),metric.identity(),context(layers),subject,status,value.map(n->new MetricValue(n,metric.unit())),ratio,qualifiedDetails,
                references.stream().limit(limits.maxDetailRows()).toList(),List.copyOf(gaps.values()),limitations,Optional.empty());
    }
    ArchitectureMetric.Result count(String subject,long count,Map<String,Object> details,Optional<ArchitectureMetric.LayerMapping> layers){return result(subject,AssessmentStatus.COMPLETE,Optional.of(BigDecimal.valueOf(count)),Optional.empty(),details,layers);}
    ArchitectureMetric.Result absent(String subject,AssessmentStatus status,String reason,Map<String,Object> details,Optional<RatioEvidence> ratio,Optional<ArchitectureMetric.LayerMapping> layers){
        reasons.add(reason);return result(subject,status,Optional.empty(),ratio,details,layers);
    }
    boolean incomplete(){return exhausted||populationUnknown||typeUnknown||!gaps.isEmpty();}
}
