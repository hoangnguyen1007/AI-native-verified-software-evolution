package com.evolution.analysis.execution.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.output.OutputCensus;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Opaque guest bytes -> neutral CAS tables. No execution, quiescence certificate or semantic promotion.
 * The caller supplies the precommitted output roles and keeps every census recorder outcome.
 */
public final class SemanticCaptureImport {
    private interface ExpectedBytes {LogicalPath path();ContentHandle bytes();}
    public record Expected(LogicalPath path,ContentHandle bytes,ContentDigest scope,String language,String engine,ContentDigest toolset) implements ExpectedBytes {
        public Expected{Objects.requireNonNull(path);Objects.requireNonNull(bytes);Objects.requireNonNull(scope);Objects.requireNonNull(language);Objects.requireNonNull(engine);Objects.requireNonNull(toolset);}
    }
    public enum State { CAPTURED, MISSING, UNAVAILABLE, INVALID }
    public record NativeExpected(LogicalPath path,ContentHandle bytes,ContentDigest scope,String language,String dialect,VersionedIdentifier provider,ContentDigest tool) implements ExpectedBytes {
        public NativeExpected{Objects.requireNonNull(path);Objects.requireNonNull(bytes);Objects.requireNonNull(scope);Objects.requireNonNull(language);Objects.requireNonNull(dialect);Objects.requireNonNull(provider);Objects.requireNonNull(tool);}
    }
    public record NativeOutcome(State state,Optional<OutputCensus.RowOutcome> row,Optional<NativeSemanticCapture> capture,String reason){}
    public record NativeResult(OutputCensus.Result census,Map<String,NativeOutcome> outcomes,ContentHandle report){
        public NativeResult{outcomes=Collections.unmodifiableMap(new TreeMap<>(outcomes));}
        public boolean bytesClosed(){return census.decision()==OutputCensus.Decision.CAPTURED&&outcomes.values().stream().allMatch(o->o.state()==State.CAPTURED);}
    }
    public record Outcome(State state,Optional<OutputCensus.RowOutcome> row,Optional<CapturedSemanticProvider.Capture> capture,String reason){}
    public record Result(OutputCensus.Result census,Map<String,Outcome> outcomes,ContentHandle report){
        public Result{outcomes=Collections.unmodifiableMap(new TreeMap<>(outcomes));}
        public boolean bytesClosed(){return census.decision()==OutputCensus.Decision.CAPTURED&&outcomes.values().stream().allMatch(o->o.state()==State.CAPTURED);}
    }
    public Result read(OutputCensus.Header header,OutputCensus.ObjectSource source,Map<String,Expected> expectations,
            RepositoryContentStore content,SemanticTables tables,int maxRowBytes,OutputCensus.Recorder recorder,BooleanSupplier cancelled)throws IOException{
        var imported=readBytes(header,source,expectations,content,recorder,cancelled,expected->{
            var capture=CapturedSemanticProvider.read(expected.bytes(),tables,content,maxRowBytes,cancelled);
            if(!capture.scope().equals(expected.scope())||!capture.language().equals(expected.language())||!capture.engine().equals(expected.engine())||!capture.toolset().equals(expected.toolset()))throw new IOException("SEMANTIC_CAPTURE_CONTEXT_MISMATCH");
            return capture;
        });
        var outcomes=new TreeMap<String,Outcome>();imported.outcomes().forEach((key,value)->outcomes.put(key,new Outcome(value.state(),value.row(),value.capture(),value.reason())));
        return new Result(imported.census(),outcomes,report("polyglot-capture-import:m8.4-v1",imported.census(),expectations,outcomes,content,cancelled));
    }
    public NativeResult readNative(OutputCensus.Header header,OutputCensus.ObjectSource source,Map<String,NativeExpected> expectations,
            RepositoryContentStore content,SemanticTables tables,int maxRowBytes,OutputCensus.Recorder recorder,BooleanSupplier cancelled)throws IOException{
        var imported=readBytes(header,source,expectations,content,recorder,cancelled,expected->{
            var capture=NativeSemanticCapture.read(expected.bytes(),tables,content,maxRowBytes,cancelled);
            if(!capture.scope().equals(expected.scope())||!capture.language().equals(expected.language())||!capture.dialect().equals(expected.dialect())||!capture.provider().equals(expected.provider())||!capture.tool().equals(expected.tool()))throw new IOException("SEMANTIC_CAPTURE_CONTEXT_MISMATCH");
            return capture;
        });
        var outcomes=new TreeMap<String,NativeOutcome>();imported.outcomes().forEach((key,value)->outcomes.put(key,new NativeOutcome(value.state(),value.row(),value.capture(),value.reason())));
        return new NativeResult(imported.census(),outcomes,report("native-capture-import:m8.4-v1",imported.census(),expectations,outcomes,content,cancelled));
    }
    private static ContentHandle report(String schema,OutputCensus.Result census,Object expectations,Object outcomes,RepositoryContentStore content,BooleanSupplier cancelled)throws IOException{
        byte[] bytes=CanonicalJson.write(Map.of("schema",schema,"census",census,"expectations",expectations,"outcomes",outcomes,"trust","PRODUCER_ASSERTION","qualified",false)).getBytes(StandardCharsets.UTF_8);
        return content.put(new ByteArrayInputStream(bytes),cancelled);
    }
    @FunctionalInterface private interface CaptureReader<E,C>{C read(E expected)throws IOException;}
    private record ImportedOutcome<C>(State state,Optional<OutputCensus.RowOutcome> row,Optional<C> capture,String reason){}
    private record Imported<C>(OutputCensus.Result census,Map<String,ImportedOutcome<C>> outcomes){}
    private <E extends ExpectedBytes,C> Imported<C> readBytes(OutputCensus.Header header,OutputCensus.ObjectSource source,Map<String,E> expectations,
            RepositoryContentStore content,OutputCensus.Recorder recorder,BooleanSupplier cancelled,CaptureReader<E,C> reader)throws IOException{
        if(expectations.isEmpty()||expectations.keySet().stream().anyMatch(String::isBlank))throw new IllegalArgumentException("SEMANTIC_CAPTURE_ROLES_REQUIRED");
        var wanted=new HashMap<LogicalPath,String>();
        for(var item:expectations.entrySet())if(wanted.put(item.getValue().path(),item.getKey())!=null)throw new IllegalArgumentException("SEMANTIC_CAPTURE_DUPLICATE_PATH");
        var matched=new TreeMap<String,OutputCensus.RowOutcome>();
        var census=new OutputCensus(content).verify(header,source,row->{
            recorder.append(row);
            var name=row.path().map(wanted::get).orElse(null);
            if(name!=null){var prior=matched.putIfAbsent(name,row);if(prior!=null)matched.put(name,new OutputCensus.RowOutcome(row.ordinal(),row.rawRow(),row.path(),OutputCensus.RowState.REJECTED,row.captured(),"OUTPUT_PATH_ORDER_OR_DUPLICATE"));}
        },cancelled);
        var outcomes=new TreeMap<String,ImportedOutcome<C>>();
        for(var item:new TreeMap<>(expectations).entrySet()){
            if(cancelled.getAsBoolean())throw new IOException("SEMANTIC_CAPTURE_CANCELLED");
            var expected=item.getValue();var row=matched.get(item.getKey());ImportedOutcome<C> outcome;
            if(row==null)outcome=new ImportedOutcome<>(State.MISSING,Optional.empty(),Optional.empty(),census.decision()==OutputCensus.Decision.CAPTURED?"SEMANTIC_OUTPUT_MISSING":"SEMANTIC_OUTPUT_ABSENCE_UNPROVEN");
            else if(row.state()!=OutputCensus.RowState.CAPTURED||row.captured().isEmpty())outcome=new ImportedOutcome<>(State.UNAVAILABLE,Optional.of(row),Optional.empty(),"SEMANTIC_OUTPUT_BYTES_UNAVAILABLE");
            else if(!row.captured().orElseThrow().equals(expected.bytes()))outcome=new ImportedOutcome<>(State.INVALID,Optional.of(row),Optional.empty(),"SEMANTIC_OUTPUT_HANDLE_MISMATCH");
            else{
                try{
                    var capture=reader.read(expected);
                    outcome=new ImportedOutcome<>(State.CAPTURED,Optional.of(row),Optional.of(capture),"");
                }catch(IOException invalid){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw invalid;outcome=new ImportedOutcome<>(State.INVALID,Optional.of(row),Optional.empty(),"SEMANTIC_CAPTURE_INVALID");}
            }
            outcomes.put(item.getKey(),outcome);
        }
        return new Imported<>(census,outcomes);
    }
}
