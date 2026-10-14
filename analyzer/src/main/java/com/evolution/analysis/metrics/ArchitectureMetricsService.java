package com.evolution.analysis.metrics;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Storage-neutral metric engine and the M5.2 query metrics port. No score or policy verdict is produced. */
public final class ArchitectureMetricsService implements Metrics {
    public record PhaseMeasurement(String phase,long durationNanos,boolean failed,ContentDigest observation) {
        public PhaseMeasurement {ContractChecks.namespacedId(phase,"measured phase");if(durationNanos<0)throw new IllegalArgumentException("Negative duration");Objects.requireNonNull(observation);}
    }
    private final KnowledgeGraph graph;private final ArchitectureMetric.Limits limits;private final Supplier<StopReason> guard;
    private final Optional<ArchitectureMetric.LayerMapping> layers;private final List<PhaseMeasurement> measurements;
    public ArchitectureMetricsService(KnowledgeGraph graph){this(graph,ArchitectureMetric.Limits.defaults(),ArchitectureQueryService.operationalGuard(()->false,0,8_000_000),Optional.empty(),List.of());}
    public ArchitectureMetricsService(KnowledgeGraph graph,ArchitectureMetric.Limits limits,Supplier<StopReason> guard,Optional<ArchitectureMetric.LayerMapping> layers,List<PhaseMeasurement> measurements){
        this.graph=Objects.requireNonNull(graph);this.limits=Objects.requireNonNull(limits);this.guard=Objects.requireNonNull(guard);this.layers=Objects.requireNonNull(layers);
        this.measurements=measurements.stream().distinct().sorted(Comparator.comparing(PhaseMeasurement::phase).thenComparing(PhaseMeasurement::observation)).toList();
        if(measurements.size()>limits.maxDetailRows())throw new IllegalArgumentException("Too many phase measurements");
    }
    public ArchitectureQueryService queries(){return new ArchitectureQueryService(graph,guard,Optional.of(this));}
    public ArchitectureMetric.Result compute(Request request,String metric,String subject){
        Objects.requireNonNull(subject);var definition=MetricCatalog.require(metric);validate(request);
        var input=new MetricEvidence(graph,request,definition,limits,guard);
        if(!definition.scopes().contains(request.scope()))return input.absent(subject,AssessmentStatus.NOT_APPLICABLE,"METRIC_SCOPE_NOT_APPLICABLE",Map.of(),Optional.empty(),layers);
        if(Set.of("metric.inventory","metric.quality","metric.physical-loc","metric.operational","metric.spring").contains(metric)&&!subject.equals(request.context()))return input.absent(subject,AssessmentStatus.NOT_APPLICABLE,"WHOLE_CONTEXT_SUBJECT_REQUIRED",Map.of(),Optional.empty(),layers);
        if(metric.equals("metric.inventory"))return new InventoryMetrics(input).inventory(subject);
        if(metric.equals("metric.quality"))return new InventoryMetrics(input).quality(subject);
        if(metric.equals("metric.physical-loc"))return new InventoryMetrics(input).loc(subject);
        if(metric.equals("metric.operational")){
            if(measurements.isEmpty()){input.gap("PHASE_MEASUREMENT_UNAVAILABLE");return input.absent(subject,AssessmentStatus.WITHHELD,"NO_MEASURED_PHASE_DURATION",Map.of("measurementStatus","NOT_MEASURED"),Optional.empty(),layers);}
            for(var measurement:measurements){
                var proof=graph.evidence(measurement.observation());
                if(proof.isEmpty()||!proof.orElseThrow().family().equals("metrics.phase-measurement")){input.gap("PHASE_MEASUREMENT_PROVENANCE_UNAVAILABLE");continue;}
                var row=input.read(proof.orElseThrow());
                if(!request.context().equals(row.get("context"))||!measurement.phase().equals(row.get("phase"))||!BigDecimal.valueOf(measurement.durationNanos()).equals(row.get("durationNanos"))||!Boolean.valueOf(measurement.failed()).equals(row.get("failed")))input.gap("PHASE_MEASUREMENT_PROVENANCE_CONFLICT");
            }
            if(input.incomplete())return input.absent(subject,AssessmentStatus.WITHHELD,"UNVERIFIED_PHASE_MEASUREMENT",Map.of(),Optional.empty(),layers);
            BigDecimal duration=measurements.stream().map(m->BigDecimal.valueOf(m.durationNanos())).reduce(BigDecimal.ZERO,BigDecimal::add);measurements.forEach(m->input.references.add(m.observation()));
            return input.result(subject,AssessmentStatus.COMPLETE,Optional.of(duration),Optional.empty(),Map.of("phases",measurements,"failures",measurements.stream().filter(PhaseMeasurement::failed).count()),layers);
        }
        if(metric.equals("metric.spring"))return spring(input,subject);
        if(request.projection()==Projection.UNKNOWN_OVERLAY){input.gap("UNKNOWN_PROJECTION_HAS_NO_DEFINITE_METRIC");return input.absent(subject,AssessmentStatus.WITHHELD,"UNKNOWN_DEPENDENCIES",Map.of(),Optional.empty(),layers);}
        if(request.scope()!=Scope.SPRING_COMPONENT&&(!request.population().origins().isEmpty()&&!request.population().origins().contains("PROJECT")||!request.population().sourceSets().isEmpty()&&!request.population().sourceSets().contains("MAIN")))return input.absent(subject,AssessmentStatus.NOT_APPLICABLE,"NO_PRODUCTION_POPULATION_SELECTED",Map.of(),Optional.empty(),layers);
        if(request.direction()!=Direction.OUTGOING)throw new IllegalArgumentException("Structural metrics use OUTGOING dependency orientation");
        if(request.relationshipKinds().stream().anyMatch(k->!k.equals("graph.depends-on")&&!k.startsWith("java.")&&!Set.of("spring.selected-binding","spring.injection-candidate").contains(k)
                ||Set.of("java.declares","java.has-parameter").contains(k)))throw new IllegalArgumentException("Structural metrics require dependency relationship kinds");
        return new StructuralMetrics(input).compute(subject,layers);
    }
    /** Independent partition results, never a fusion of incompatible Spring/build worlds or the first manifest. */
    public ArchitectureMetric.Batch computeBatch(List<Request> requests,List<String> metrics){
        if(requests.isEmpty()||requests.size()>limits.maxDetailRows()||metrics.isEmpty()||metrics.size()>MetricCatalog.entries().size())throw new IllegalArgumentException("Invalid metric batch size");
        var ordered=requests.stream().sorted(Comparator.comparing(Request::context)).toList();if(ordered.stream().map(Request::context).distinct().count()!=ordered.size())throw new IllegalArgumentException("Duplicate batch context");
        var ids=metrics.stream().sorted().distinct().toList();if(ids.size()!=metrics.size())throw new IllegalArgumentException("Duplicate batch metric");
        var results=new ArrayList<ArchitectureMetric.Result>();for(var request:ordered)for(String id:ids)results.add(compute(request,id,request.context()));
        var contexts=results.stream().map(ArchitectureMetric.Result::context).distinct().sorted(Comparator.comparing(ArchitectureMetric.Context::partition).thenComparing(ArchitectureMetric.Context::population)).toList();
        return new ArchitectureMetric.Batch(graph.identity(),contexts,results);
    }
    private ArchitectureMetric.Result spring(MetricEvidence input,String subject){
        var projection=input.queries.metricProjection(input.request);input.absorb(projection);var counts=new TreeMap<String,Long>();
        for(var node:input.nodes.values())if(Set.of("BEAN_PRODUCER","BEAN_DEFINITION_CANDIDATE","INJECTION_POINT","INJECTION_USE","REGISTRATION_EVENT","REGISTRATION_TRANSITION","ENDPOINT").contains(node.kind()))counts.merge(node.kind(),1L,Long::sum);
        var detail=new TreeMap<String,Object>();detail.put("inventory",counts);detail.put("projectedActiveDefinitions",projection.items().getFirst().selectedDefinitions().size());
        detail.put("unknownActivationDefinitions",projection.items().getFirst().unknownDefinitions().size());detail.put("activeIdentitySamples",projection.items().getFirst().selectedDefinitions().stream().limit(limits.maxDetailRows()).toList());
        detail.put("activationBounds",projection.bounds());
        var cardinalities=new TreeMap<String,Set<String>>();long selected=0;
        for(var edge:input.graph.edges())if(edge.context().equals(input.request.context())&&Set.of("spring.injection-candidate","spring.selected-binding").contains(edge.kind())){
            if(!input.spend(1))break;cardinalities.computeIfAbsent(edge.source(),k->new TreeSet<>()).addAll(edge.target().identities());if(edge.kind().equals("spring.selected-binding"))selected++;}
        detail.put("candidateCardinalities",cardinalities.entrySet().stream().limit(limits.maxDetailRows()).map(e->Map.of("ownerQualifiedUse",e.getKey(),"candidateTargets",e.getValue().size())).toList());
        detail.put("candidateUsePopulation",cardinalities.size());detail.put("selectedBindingFactRows",selected);
        detail.put("candidateCardinalityInterpretation","Discovered candidate targets per owner-qualified injection use; not proof that targets coexist or are selected.");
        var r=input.request;var bindingRequest=new Request(r.graph(),r.context(),Scope.ENTITY,r.projection(),Direction.OUTGOING,Set.of("spring.selected-binding","spring.injection-candidate"),r.population(),r.includeMust(),r.realization(),r.limits());
        var bindings=input.queries.metricProjection(bindingRequest);input.absorb(bindings);var projectedCandidates=new TreeMap<String,Set<String>>();long selectedPairs=0;
        for(var edge:bindings.items().getFirst().edges())if(edge.definite()){
            if(!input.spend(1))break;input.references.addAll(edge.evidence());
            if(edge.kinds().contains("spring.injection-candidate"))projectedCandidates.computeIfAbsent(edge.source(),k->new TreeSet<>()).add(edge.target());
            if(edge.kinds().contains("spring.selected-binding"))selectedPairs++;
        }
        detail.put("projectedSelectedBindingPairs",selectedPairs);detail.put("projectedCandidateCardinalities",projectedCandidates.entrySet().stream().limit(limits.maxDetailRows()).map(e->Map.of("ownerQualifiedUse",e.getKey(),"feasibleTargets",e.getValue().size())).toList());
        var planRefs=new TreeSet<ContentDigest>();input.graph.edges().stream().filter(e->e.context().equals(r.context())&&e.kind().equals("spring.produces-candidate")).forEach(e->planRefs.addAll(e.evidence()));
        var mechanisms=new TreeMap<String,Long>();
        for(var ref:planRefs){var evidence=graph.evidence(ref).orElseThrow();if(!evidence.family().equals("spring.plan"))continue;var inventory=MetricEvidence.object(input.read(evidence).get("inventory"));
            for(Object row:InventoryMetrics.array(inventory.get("obligations"))){if(!input.spend(1))break;InventoryMetrics.increment(mechanisms,MetricEvidence.text(MetricEvidence.object(row),"primaryMechanism"));}input.references.add(ref);}
        detail.put("evidencedMechanismObligations",mechanisms);
        if(!counts.containsKey("ENDPOINT")){input.gap("ENDPOINT_COVERAGE_UNAVAILABLE");detail.put("endpointCountStatus","WITHHELD");}
        return input.count(subject,counts.getOrDefault("BEAN_DEFINITION_CANDIDATE",0L),detail,layers);
    }
    private void validate(Request request){if(!graph.identity().equals(request.graph())||graph.contexts().stream().noneMatch(c->c.identity().equals(request.context())))throw new IllegalArgumentException("Foreign metric graph/context");}
    @Override public ArchitectureQuery.Result<String> catalog(Request request,Page page){
        validate(request);var ids=MetricCatalog.entries().stream().map(e->e.metric().id()).toList();int size=Math.min(page.size(),request.limits().maxResults());
        var query=ArchitectureMetric.digest(List.of(ArchitectureMetric.VERSION,"catalog",MetricCatalog.identity(),request.identity(),size));int start=0;
        if(page.cursor().isPresent()){
            try {String value=new String(Base64.getUrlDecoder().decode(page.cursor().orElseThrow()),StandardCharsets.UTF_8);String prefix=query.value()+"|";
                if(!value.startsWith(prefix))throw new IllegalArgumentException();int index=ids.indexOf(value.substring(prefix.length()));if(index<0)throw new IllegalArgumentException();start=index+1;
            }catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid metric catalog cursor");}
        }
        int end=Math.min(ids.size(),start+size);var items=ids.subList(start,end);boolean truncated=end<ids.size();
        var continuation=truncated?Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString((query.value()+"|"+items.getLast()).getBytes(StandardCharsets.UTF_8))):Optional.<String>empty();
        return new ArchitectureQuery.Result<>(query,truncated?AssessmentStatus.PARTIAL:AssessmentStatus.COMPLETE,Operation.COMPLETED,items,List.of(),truncated?List.of(catalogGap(request,query)):List.of(),
                new Bounds(truncated,false,truncated,truncated?StopReason.OUTPUT:StopReason.NONE,0,0,items.size(),0,0,0,0,List.of()),continuation,Optional.of((long)ids.size()));
    }
    private CapabilityGapRecord catalogGap(Request request,ContentDigest query){
        var c=graph.contexts().stream().filter(ctx->ctx.identity().equals(request.context())).findFirst().orElseThrow();
        var observation=ProviderObservationReference.create(ArchitectureMetric.VERSION,"metrics.catalog-page",graph.identity(),query);var subject=EvidenceSubject.observation(observation.identity());
        var requirement=new EvidenceRequirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,"metrics.next-catalog-page",List.of(subject),EvidenceRequirement.AuthorizationClass.PASSIVE,List.of("Continue with the bound cursor."));
        return CapabilityGapRecord.create(new VersionedIdentifier("evidence.metric-gaps","m5.3-v1"),new EvidenceContext(graph.snapshot(),c.analysis()),ArchitectureMetric.VERSION,"metrics.catalog","OUTPUT",subject,List.of(),List.of(observation),List.of(requirement),List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.METRIC,"metric.catalog")),List.of(),List.of(),List.of("Catalog pagination does not alter a metric's declared population."));
    }
    @Override public ArchitectureQuery.Result<Map<String,Object>> evaluate(Request request,String metric,String subject){
        var result=compute(request,metric,subject);var payload=new TreeMap<>(GraphJson.object(GraphJson.read(CanonicalJson.write(result.semanticForm()),GraphJson.Limits.defaults())));payload.put("identity",result.identity().value());
        var operation=result.limitations().stream().anyMatch(s->s.contains("CANCELLED"))?Operation.CANCELLED:result.limitations().stream().anyMatch(s->s.contains("MEMORY")||s.contains("RESOURCE_LIMIT")||s.contains("TIME"))?Operation.RESOURCE_LIMIT:Operation.COMPLETED;
        return new ArchitectureQuery.Result<>(ArchitectureMetric.digest(List.of(ArchitectureMetric.VERSION,request.identity(),metric,subject,limits,layers.map(ArchitectureMetric.LayerMapping::identity))),result.status(),operation,List.of(Collections.unmodifiableMap(payload)),result.evidence(),result.gaps(),
                executionBounds(result),Optional.empty(),Optional.empty());
    }
    private Bounds executionBounds(ArchitectureMetric.Result result){
        boolean truncated=false,work=false,output=false;long steps=0,solver=0,rejected=0,unknown=0;int witnesses=0,components=0,cyclic=0;StopReason stop=StopReason.NONE;var frontier=new TreeSet<String>();
        for(Object value:InventoryMetrics.array(result.details().get("queryExecutions"))){
            var b=MetricEvidence.object(value);truncated|=Boolean.TRUE.equals(b.get("truncated"));work|=Boolean.TRUE.equals(b.get("workBudgetExceeded"));output|=Boolean.TRUE.equals(b.get("outputBudgetExceeded"));
            steps+=InventoryMetrics.number(b.get("stepsUsed"));solver+=InventoryMetrics.number(b.get("solverStepsUsed"));rejected+=InventoryMetrics.number(b.get("rejectedInfeasible"));unknown+=InventoryMetrics.number(b.get("unknownCandidates"));
            witnesses=(int)InventoryMetrics.number(b.get("witnessesReturned"));components=(int)InventoryMetrics.number(b.get("structuralComponents"));cyclic=(int)InventoryMetrics.number(b.get("cyclicComponents"));
            if(stop==StopReason.NONE&&b.get("stopReason") instanceof String s)stop=StopReason.valueOf(s);
            InventoryMetrics.array(b.get("frontier")).stream().filter(String.class::isInstance).map(String.class::cast).limit(limits.maxDetailRows()).forEach(frontier::add);
        }
        for(String reason:result.limitations()){
            if(reason.equals("METRIC_FACT_LIMIT")||reason.equals("METRIC_PAYLOAD_LIMIT_OR_INVALID")||reason.equals("SOURCE_BYTE_LIMIT")){truncated=true;work=true;if(stop==StopReason.NONE)stop=StopReason.INPUT;}
            if(reason.startsWith("OPERATION_")){try{var interrupted=StopReason.valueOf(reason.substring(10));truncated=true;work=true;if(stop==StopReason.NONE)stop=interrupted;}catch(IllegalArgumentException ignored){/* operation status is carried separately */}}
        }
        return new Bounds(truncated,work,output,stop,steps,solver,witnesses,rejected,unknown,components,cyclic,frontier.stream().limit(limits.maxDetailRows()).toList());
    }
}
