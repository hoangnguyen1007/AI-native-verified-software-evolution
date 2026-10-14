package com.evolution.analysis.metrics;

import com.evolution.analysis.contract.assessment.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.metrics.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.query.ArchitectureQuery;
import java.math.*;
import java.time.Instant;
import java.util.*;

/** Additive M5 result contract. M1 MetricEnvelope and its exact-realization requirements are unchanged. */
public final class ArchitectureMetric {
    public static final VersionedIdentifier VERSION=new VersionedIdentifier("architecture.metrics","m5.3-v1");
    public static final String POPULATION="project-main-nongenerated-types:v1";
    public static final int SCALE=12;
    public record Limits(int maxFacts,int maxPayloadCharacters,long maxSourceBytes,int maxDetailRows) {
        public Limits {if(maxFacts<1||maxFacts>500_000||maxPayloadCharacters<1||maxPayloadCharacters>8_000_000||maxSourceBytes<1||maxSourceBytes>64_000_000
                ||maxDetailRows<1||maxDetailRows>50)throw new IllegalArgumentException("Invalid metric limits");}
        public static Limits defaults(){return new Limits(200_000,8_000_000,16_000_000,50);}
    }
    public record LayerMapping(VersionedIdentifier version,Map<String,String> componentLayers) {
        public LayerMapping {Objects.requireNonNull(version);componentLayers=Collections.unmodifiableMap(new TreeMap<>(componentLayers));}
        public ContentDigest identity(){return digest(this);}
    }
    public record Context(ContentDigest graph,String partition,List<AnalysisIdentity> analyses,ArchitectureQuery.Request projection,
                          String population,Limits limits,Optional<ContentDigest> layers) {
        public Context {analyses=analyses.stream().sorted().distinct().toList();Objects.requireNonNull(graph);Objects.requireNonNull(partition);Objects.requireNonNull(projection);
            Objects.requireNonNull(population);Objects.requireNonNull(limits);Objects.requireNonNull(layers);}
        public ContentDigest identity(){return digest(this);}
    }
    public record Telemetry(Instant computedAt) {public Telemetry {Objects.requireNonNull(computedAt);}}
    public record Result(VersionedIdentifier metric,ContentDigest definition,Context context,String subject,AssessmentStatus status,
                         Optional<MetricValue> value,Optional<RatioEvidence> ratio,Map<String,Object> details,List<ContentDigest> evidence,
                         List<CapabilityGapRecord> gaps,List<String> limitations,Optional<Telemetry> telemetry) {
        public Result {
            Objects.requireNonNull(metric);Objects.requireNonNull(definition);Objects.requireNonNull(context);Objects.requireNonNull(subject);Objects.requireNonNull(status);
            Objects.requireNonNull(value);Objects.requireNonNull(ratio);details=GraphForms.immutable(details);evidence=evidence.stream().sorted().distinct().toList();
            gaps=gaps.stream().sorted().distinct().toList();limitations=limitations.stream().sorted().distinct().toList();Objects.requireNonNull(telemetry);
            AssessmentStateRules.validate(status,value,List.of(),limitations,"architecture metric");
            if(status==AssessmentStatus.COMPLETE&&!gaps.isEmpty())throw new IllegalArgumentException("Complete metric cannot hide gaps");
            if(ratio.isPresent()&&ratio.orElseThrow().denominator().signum()==0&&status!=AssessmentStatus.NOT_APPLICABLE)
                throw new IllegalArgumentException("Zero denominator requires NOT_APPLICABLE");
        }
        public Object semanticForm(){return Map.ofEntries(Map.entry("schema",VERSION),Map.entry("metric",metric),Map.entry("definition",definition),Map.entry("context",context),
                Map.entry("subject",subject),Map.entry("status",status),Map.entry("value",value),Map.entry("ratio",ratio),Map.entry("details",details),Map.entry("evidence",evidence),
                Map.entry("gaps",gaps),Map.entry("limitations",limitations));}
        public ContentDigest identity(){return digest(semanticForm());}
        public Result withTelemetry(Telemetry measured){return new Result(metric,definition,context,subject,status,value,ratio,details,evidence,gaps,limitations,Optional.of(measured));}
    }
    public record Batch(ContentDigest graph,List<Context> contexts,List<Result> results) {
        public Batch {contexts=List.copyOf(contexts);results=List.copyOf(results);}
        public ContentDigest identity(){return digest(List.of(VERSION,graph,contexts,results.stream().map(Result::identity).toList()));}
    }
    public static BigDecimal divide(BigDecimal n,BigDecimal d){return n.divide(d,SCALE,RoundingMode.HALF_EVEN).stripTrailingZeros();}
    static ContentDigest digest(Object value){return ContentDigest.sha256Utf8(CanonicalJson.write(value));}
    /** Freeze neutral details, rejecting unsupported/binary-double values through canonical JSON. */
    private static final class GraphForms {
        static Map<String,Object> immutable(Map<String,Object> value){
            return freezeMap(com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(CanonicalJson.write(value),com.evolution.analysis.graph.GraphJson.Limits.defaults())));
        }
        static Map<String,Object> freezeMap(Map<String,Object> values){var copy=new TreeMap<String,Object>();values.forEach((k,v)->copy.put(k,freeze(v)));return Collections.unmodifiableMap(copy);}
        static Object freeze(Object value){if(value instanceof Map<?,?> map)return freezeMap(com.evolution.analysis.graph.GraphJson.object(map));
            if(value instanceof List<?> list)return list.stream().map(GraphForms::freeze).toList();return value;}
    }
    private ArchitectureMetric() {}
}
