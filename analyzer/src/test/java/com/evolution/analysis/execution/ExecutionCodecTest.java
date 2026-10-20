package com.evolution.analysis.execution;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.execution.ExecutionGraphTest.*;

class ExecutionCodecTest {
    @Test void taskAndStreamingEnvelopeRoundTripWithoutChangingCanonicalIdentity(){
        var t=task("root");assertEquals(t,ExecutionCodec.read(CanonicalJson.write(t),ExecutionGraph.Task.class));
        var content=new ContentHandle(digest("table"),Long.MAX_VALUE);
        var plan=new ExecutionPlan(ExecutionPlan.SCHEMA,digest("r"),digest("i"),digest("s"),VERSION,digest("p"),new ExecutionPlan.Table("execution-task-table:m8.4-v1",content,Long.MAX_VALUE),new ExecutionPlan.Table("execution-edge-table:m8.4-v1",content,0),new ExecutionPlan.Table("execution-binding-table:m8.4-v1",content,0),Optional.empty(),Optional.empty());
        assertEquals(plan,ExecutionCodec.read(CanonicalJson.write(plan),ExecutionPlan.class));
        assertEquals(plan.identity(),ExecutionCodec.read(CanonicalJson.write(plan),ExecutionPlan.class).identity());
    }
    @Test void rejectsDuplicateUnknownFieldsWrongTypesUnicodeAndDigestNewline(){
        var json=CanonicalJson.write(task("root"));
        for(var bad:List.of(json.replace("\"root\":\"root\"","\"root\":\"root\",\"root\":\"other\""),json.replace("\"kind\":\"NATIVE_RUN\"","\"kind\":\"HOST_RUN\""),json.replace("\"contexts\":[\"root\"]","\"contexts\":[\"root\",\"root\"]"),json.substring(0,json.length()-1)+",\"shell\":\"cmd\"}",json.replace("\"root\":\"root\"","\"root\":\"\\uD800\""),json.replace(digest("recipe").value(),digest("recipe").value()+"\\n"))){
            assertThrows(IllegalArgumentException.class,()->ExecutionCodec.read(bad,ExecutionGraph.Task.class));
        }
    }
    @Test void inputTagsAndBindingModesRemainClosed(){
        var candidate=new ExecutionPlan.Candidate(digest("task"),"raw",VERSION,"model");var reference=new ExecutionPlan.ScopedRef(digest("plan"),"req");
        var binding=new ExecutionPlan.Binding("execution-binding:m8.4-v1",reference,ExecutionPlan.Mode.ALL_OF,List.of(candidate),Optional.empty(),Optional.empty());
        assertEquals(binding,ExecutionCodec.read(CanonicalJson.write(binding),ExecutionPlan.Binding.class));
        assertThrows(IllegalArgumentException.class,()->new ExecutionPlan.Binding(binding.schema(),reference,ExecutionPlan.Mode.PENDING,List.of(candidate),Optional.empty(),Optional.of("missing")));
        assertThrows(IllegalArgumentException.class,()->new ExecutionPlan.Table("table",new ContentHandle(digest("x"),0),-1));
    }
    @Test void malformedUtf8AndParserWorkingSetStayExplicit()throws Exception{
        assertThrows(IOException.class,()->ExecutionCodec.read(new ByteArrayInputStream(new byte[]{(byte)0xff}),ExecutionGraph.Task.class,GraphJson.Limits.defaults()));
        assertThrows(GraphJson.LimitExceededException.class,()->ExecutionCodec.read(new ByteArrayInputStream(CanonicalJson.write(task("root")).getBytes(java.nio.charset.StandardCharsets.UTF_8)),ExecutionGraph.Task.class,new GraphJson.Limits(10,10,10)));
        assertThrows(IllegalArgumentException.class,()->ExecutionCodec.read("{}",Runtime.class));
    }
}
