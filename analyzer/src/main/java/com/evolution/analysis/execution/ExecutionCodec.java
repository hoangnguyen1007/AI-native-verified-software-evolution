package com.evolution.analysis.execution;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.time.Instant;
import java.util.*;

/** Strict allowlisted record codec. No class name from input is loaded or instantiated. */
public final class ExecutionCodec {
    private static final Set<Class<?>> RECORDS=Set.of(ExecutionPlan.class,ExecutionPlan.Table.class,ExecutionPlan.ScopedRef.class,
            ExecutionPlan.ContextRef.class,ExecutionPlan.Binding.class,ExecutionPlan.Candidate.class,ExecutionGraph.Task.class,
            ExecutionGraph.Edge.class,ExecutionGraph.CapturedInput.class,ExecutionGraph.TaskInput.class,ExecutionState.class,
            AdmissionQueue.State.class,AdmissionQueue.Ticket.class,AdmissionQueue.Request.class,AdmissionQueue.Queued.class,AdmissionQueue.Retired.class,AdmissionQueue.StorageHold.class,ResourceBudget.Resources.class,
            ExecutionAttempt.class,SandboxExecutor.Launch.class,SandboxExecutor.Handle.class,
            NativeCaptureParser.Envelope.class,com.evolution.analysis.readiness.CapturedBuildEvidence.Manifest.class,
            com.evolution.analysis.readiness.CapturedBuildEvidence.Observation.class,VersionedIdentifier.class,ContentHandle.class);
    public static <T> T read(InputStream input,Class<T> type,GraphJson.Limits limits)throws IOException {
        if(!RECORDS.contains(type))throw new IllegalArgumentException("UNREGISTERED_RECORD");
        var reader=new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        return type.cast(convert(GraphJson.read(reader,limits),type));
    }
    public static <T> T read(String text,Class<T> type) {
        if(!RECORDS.contains(type))throw new IllegalArgumentException("UNREGISTERED_RECORD");
        return type.cast(convert(GraphJson.read(text,GraphJson.Limits.defaults()),type));
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static Object convert(Object value,Type type) {
        if(type instanceof ParameterizedType p){
            if(p.getRawType()==Optional.class)return value==null?Optional.empty():Optional.of(convert(value,p.getActualTypeArguments()[0]));
            if(p.getRawType()==List.class){var out=new ArrayList<>();for(var item:GraphJson.array(value))out.add(convert(item,p.getActualTypeArguments()[0]));return List.copyOf(out);}
            throw new IllegalArgumentException("UNSUPPORTED_RECORD_TYPE");
        }
        if(!(type instanceof Class<?> cls))throw new IllegalArgumentException("UNSUPPORTED_RECORD_TYPE");
        if(cls==String.class)return GraphJson.string(value);
        if(cls==ContentDigest.class)return new ContentDigest(GraphJson.string(value));
        if(cls==Instant.class)return Instant.parse(GraphJson.string(value));
        if(cls==long.class){if(!(value instanceof BigDecimal n))throw new IllegalArgumentException("INTEGER_REQUIRED");try{return n.longValueExact();}catch(ArithmeticException invalid){throw new IllegalArgumentException("INTEGER_OUT_OF_RANGE_OR_FRACTIONAL",invalid);}}
        if(cls==int.class){if(!(value instanceof BigDecimal n))throw new IllegalArgumentException("INTEGER_REQUIRED");try{return n.intValueExact();}catch(ArithmeticException invalid){throw new IllegalArgumentException("INTEGER_OUT_OF_RANGE_OR_FRACTIONAL",invalid);}}
        if(cls==boolean.class){if(!(value instanceof Boolean))throw new IllegalArgumentException("BOOLEAN_REQUIRED");return value;}
        if(cls.isEnum())return Enum.valueOf((Class)cls,GraphJson.string(value));
        if(cls==ExecutionGraph.Input.class){var obj=GraphJson.object(value);return convert(value,switch(GraphJson.string(obj.get("kind"))){case "CAPTURED"->ExecutionGraph.CapturedInput.class;case "TASK_OUTPUT"->ExecutionGraph.TaskInput.class;default->throw new IllegalArgumentException("INVALID_INPUT_KIND");});}
        if(!RECORDS.contains(cls))throw new IllegalArgumentException("UNREGISTERED_RECORD");
        var object=GraphJson.object(value);
        if(cls==AdmissionQueue.State.class&&"execution-admission:m8.4-v1".equals(object.get("schema"))){
            if(!object.keySet().equals(Set.of("schema","revision","nextSequence","capacity","maxBypasses","lastJob","pending","active","retired")))throw new IllegalArgumentException("UNKNOWN_OR_MISSING_FIELDS");
            for(var row:GraphJson.array(object.get("retired"))){
                var retired=(AdmissionQueue.Retired)convert(row,AdmissionQueue.Retired.class);var r=retired.request().resources();
                // v1 did not retain storage history. Never infer freed bytes from its retired watermark.
                if(r.disk()>0||r.log()>0||retired.request().ticket().fence()>1)throw new IllegalArgumentException("ADMISSION_STORAGE_RECONCILIATION_REQUIRED");
            }
            object=new LinkedHashMap<>(object);object.put("schema","execution-admission:m8.4-v2");object.put("retained",List.of());
        }
        var fields=cls.getRecordComponents();var names=new HashSet<String>();for(var field:fields)names.add(field.getName());
        if(!object.keySet().equals(names))throw new IllegalArgumentException("UNKNOWN_OR_MISSING_FIELDS");
        var args=new Object[fields.length];var types=new Class<?>[fields.length];
        for(int i=0;i<fields.length;i++){args[i]=convert(object.get(fields[i].getName()),fields[i].getGenericType());types[i]=fields[i].getType();}
        try{return cls.getConstructor(types).newInstance(args);}
        catch(InvocationTargetException rejected){if(rejected.getCause() instanceof RuntimeException cause)throw cause;throw new IllegalArgumentException("INVALID_RECORD");}
        catch(ReflectiveOperationException invalid){throw new IllegalArgumentException("UNSUPPORTED_RECORD",invalid);}
    }
    private ExecutionCodec(){}
}
