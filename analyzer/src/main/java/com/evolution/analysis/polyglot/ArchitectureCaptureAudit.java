package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.function.*;

/** Independent, source-bound expectations; a satisfied predicate is never a qualification certificate. */
public final class ArchitectureCaptureAudit {
    /** A half-open source window; matching observations must be wholly inside it. */
    public record Range(long start,long end){public Range{if(start<0||end<start)throw new IllegalArgumentException("ARCHITECTURE_AUDIT_RANGE");}}
    public record Expected(String id,String document,ContentHandle source,String category,String kind,Optional<Range> range,
            Optional<String> target,Map<String,Object> attributes,List<String> statuses,long minimum,long maximum){
        public Expected{ContractChecks.token(id,"expectation id");ContractChecks.repositoryRelativePath(document,"expectation document");Objects.requireNonNull(source);Objects.requireNonNull(range);Objects.requireNonNull(target);
            if(!ArchitectureCapture.CATEGORIES.contains(category)||minimum<0||maximum<minimum||statuses.isEmpty()||!Set.of("RESOLVED","PARTIAL","CONDITIONAL","UNRESOLVED","UNSUPPORTED","AMBIGUOUS","ERROR","CANDIDATES").containsAll(statuses))throw new IllegalArgumentException("ARCHITECTURE_AUDIT_EXPECTATION");
            ContractChecks.token(kind,"expected kind");statuses=ContractChecks.sortedStrings(statuses,"expected statuses");attributes=new SemanticTables.Row(SemanticTables.Kind.CENSUS,id,source.digest(),attributes).fields();}
    }
    public record Counts(long expected,long satisfied,long omitted,long uncertain,long mismatch,long ambiguous,long forbidden){public Counts{
        for(long n:new long[]{expected,satisfied,omitted,uncertain,mismatch,ambiguous,forbidden})if(n<0)throw new IllegalArgumentException("ARCHITECTURE_AUDIT_COUNTS");
        if(expected!=Math.addExact(Math.addExact(Math.addExact(satisfied,omitted),Math.addExact(uncertain,mismatch)),Math.addExact(ambiguous,forbidden)))throw new IllegalArgumentException("ARCHITECTURE_AUDIT_PARTITION");}}
    public record Result(ArchitectureCapture.Result capture,SemanticTables.Table expectations,SemanticTables.Table dispositions,Counts counts){
        public ContentDigest identity(){return CanonicalJson.sha256(List.of("architecture-audit:m8.4-v1",this));}
        public boolean qualified(){return false;}
    }
    public static Result read(ArchitectureCapture.Request request,ContentHandle raw,List<Expected> expected,SemanticTables tables,RepositoryContentStore content,int workerBytes,Consumer<CapabilityGapRecord> gaps,BooleanSupplier cancelled)throws IOException{
        if(expected.isEmpty())throw new IllegalArgumentException("ARCHITECTURE_INDEPENDENT_DENOMINATOR_REQUIRED");
        if(CanonicalJson.write(expected).getBytes(StandardCharsets.UTF_8).length>workerBytes)throw new IOException("ARCHITECTURE_EXPECTATION_WORKER_REQUIRED");
        var capture=ArchitectureCapture.read(request,raw,tables,content,workerBytes,gaps,cancelled);
        var documents=new HashMap<String,SemanticTables.Row>();try(var rows=tables.open(request.documents(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null)documents.put(row.key(),row);}
        var facts=new HashMap<String,List<SemanticTables.Row>>();try(var rows=tables.open(capture.facts(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null)facts.computeIfAbsent(key(row.fields()),k->new ArrayList<>()).add(row);}
        var labels=new TreeMap<String,SemanticTables.Row>();var dispositions=new TreeMap<String,SemanticTables.Row>();var counts=new long[7];var coordinates=new HashMap<String,SourceCoordinates>();
        for(var e:expected){
            check(cancelled);var document=documents.get(e.document());
            if(document==null||!e.source().equals(LanguageSemanticRequest.handle(document.fields().get("content"))))throw new IOException("ARCHITECTURE_EXPECTATION_SOURCE_BINDING");
            if(e.range().isPresent()){
                if(!"UTF-8".equals(document.fields().get("encoding")))throw new IOException("ARCHITECTURE_EXPECTATION_BINARY_RANGE");
                if(!coordinates.containsKey(e.document()))try(var input=content.open(e.source())){if(e.source().size()>workerBytes)throw new IOException("ARCHITECTURE_EXPECTATION_SOURCE_WORKER_REQUIRED");coordinates.put(e.document(),new SourceCoordinates(input.readNBytes(workerBytes),"UTF-8"));}
                try{var range=e.range().orElseThrow();coordinates.get(e.document()).point(range.start(),SourceCoordinates.Encoding.UTF16);coordinates.get(e.document()).point(range.end(),SourceCoordinates.Encoding.UTF16);}catch(IllegalArgumentException invalid){throw new IOException("ARCHITECTURE_EXPECTATION_SOURCE_RANGE",invalid);}
            }
            var fields=new TreeMap<String,Object>();fields.put("path",e.document());fields.put("source",e.source());fields.put("category",e.category());fields.put("kind",e.kind());fields.put("range",e.range().map(r->Map.of("start",r.start(),"end",r.end())).orElse(null));fields.put("target",e.target().orElse(null));fields.put("attributes",e.attributes());fields.put("statuses",e.statuses());fields.put("minimum",e.minimum());fields.put("maximum",e.maximum());
            var label=new SemanticTables.Row(SemanticTables.Kind.CENSUS,e.id(),request.scope(),fields);if(labels.put(e.id(),label)!=null)throw new IOException("ARCHITECTURE_EXPECTATION_DUPLICATE");
            var candidates=facts.getOrDefault(e.document()+"\0"+e.category()+"\0"+e.kind(),List.of()).stream().filter(f->rangeMatches(e.range(),f.fields().get("span"))).toList();
            var matching=candidates.stream().filter(f->e.target().isEmpty()||e.target().orElseThrow().equals(f.fields().get("target"))).filter(f->attributesMatch(e.attributes(),GraphJson.object(f.fields().get("attributes")))).toList();
            var accepted=matching.stream().filter(f->e.statuses().contains(f.fields().get("status"))).toList();String outcome;
            if(accepted.size()>=e.minimum()&&accepted.size()<=e.maximum())outcome="SATISFIED";
            else if(accepted.size()>e.maximum())outcome=e.maximum()==0?"FORBIDDEN":"AMBIGUOUS";
            else if(matching.size()>=e.minimum())outcome="UNCERTAIN";
            else outcome=candidates.isEmpty()?"OMITTED":"MISMATCH";
            counts[0]++;counts[switch(outcome){case "SATISFIED"->1;case "OMITTED"->2;case "UNCERTAIN"->3;case "MISMATCH"->4;case "AMBIGUOUS"->5;default->6;}]++;
            var values=new TreeMap<String,Object>();values.put("path",e.document());values.put("category",e.category());values.put("kind",e.kind());values.put("expectation",label.identity());values.put("capture",capture.identity());values.put("outcome",outcome);values.put("candidates",candidates.stream().map(SemanticTables.Row::key).toList());values.put("matched",matching.stream().map(SemanticTables.Row::key).toList());values.put("accepted",accepted.stream().map(SemanticTables.Row::key).toList());values.put("observedStatuses",matching.stream().map(f->GraphJson.string(f.fields().get("status"))).distinct().sorted().toList());values.put("sourceAnchored",e.range().isPresent());
            dispositions.put(e.id(),new SemanticTables.Row(SemanticTables.Kind.CENSUS,e.id(),request.scope(),values));
        }
        var expectedTable=tables.write(SemanticTables.Kind.CENSUS,labels.values().iterator(),cancelled);var actualTable=tables.write(SemanticTables.Kind.CENSUS,dispositions.values().iterator(),cancelled);
        var provider=new VersionedIdentifier("semantic.architecture-audit","m8.4-v1");
        for(var row:dispositions.values())if(!"SATISFIED".equals(row.fields().get("outcome"))){
            var subject=new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION,row.identity().value());var observation=ProviderObservationReference.create(provider,"architecture.occurrence-audit",actualTable.content().digest(),row.identity());
            var need=new EvidenceRequirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND,"architecture.expected-occurrence",List.of(subject),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,List.of("Resolve the exact source-bound expectation without removing it from the denominator."));
            gaps.accept(CapabilityGapRecord.create(ArchitectureCapture.CATALOG,request.context(),provider,"architecture.semantic","ARCHITECTURE_OCCURRENCE_"+row.fields().get("outcome"),subject,List.of(),List.of(observation),List.of(need),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"architecture.semantic")),List.of(),List.of(),List.of("Authored expectations do not certify unregistered semantic accuracy or universal occurrence completeness.")));
        }
        return new Result(capture,expectedTable,actualTable,new Counts(counts[0],counts[1],counts[2],counts[3],counts[4],counts[5],counts[6]));
    }
    private static String key(Map<String,Object> fields){return fields.get("path")+"\0"+fields.get("category")+"\0"+fields.get("kind");}
    private static boolean attributesMatch(Map<String,Object> expected,Map<String,Object> actual){return expected.entrySet().stream().allMatch(e->actual.containsKey(e.getKey())&&CanonicalJson.write(e.getValue()).equals(CanonicalJson.write(actual.get(e.getKey()))));}
    private static boolean rangeMatches(Optional<Range> expected,Object actual){if(expected.isEmpty())return true;if(actual==null)return false;var span=GraphJson.object(actual);return expected.get().start()<=((java.math.BigDecimal)span.get("start")).longValueExact()&&expected.get().end()>=((java.math.BigDecimal)span.get("end")).longValueExact();}
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("ARCHITECTURE_AUDIT_CANCELLED");}
}
