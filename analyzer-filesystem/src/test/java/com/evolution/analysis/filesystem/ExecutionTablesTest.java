package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.readiness.CapturedBuildEvidence;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionTablesTest {
    @TempDir Path temp;
    static ContentDigest d(String v){return ContentDigest.sha256Utf8(v);}
    static ExecutionGraph.Task task(String root){return new ExecutionGraph.Task("execution-task:m8.4-v1",ExecutionGraph.Kind.NATIVE_RUN,root,List.of(),d("recipe"),List.of(),List.of("raw"),"configured",new VersionedIdentifier("execution.test","1"));}
    @Test void streamedTablesRetainAllRowsAndEnforceDecodeWorkingSet()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));var tables=new ExecutionTables(cas);
        var tasks=List.of(task("a"),task("b")).stream().sorted(Comparator.comparing(ExecutionGraph.Task::identity)).toList();
        var table=tables.put(ExecutionGraph.Task.class,tasks.iterator(),10000,()->false);
        assertEquals(tasks,tables.readBounded(table,ExecutionGraph.Task.class,2,10000));assertEquals(2,table.rows());
        assertThrows(IOException.class,()->tables.readBounded(table,ExecutionGraph.Task.class,1,10000));assertTrue(cas.verify(table.content()));
        assertThrows(IOException.class,()->tables.put(ExecutionGraph.Task.class,List.of(tasks.getFirst(),tasks.getFirst()).iterator(),10000,()->false));
        assertThrows(IOException.class,()->tables.put(ExecutionGraph.Task.class,tasks.iterator(),10,()->false));
        assertThrows(IOException.class,()->tables.readBounded(new ExecutionPlan.Table(table.schema(),table.content(),1),ExecutionGraph.Task.class,2,10000));
    }
    @Test void truncatedAndNoncanonicalRowsAreNeverRestoredAsValidatedTables()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));var tables=new ExecutionTables(cas);String json=CanonicalJson.write(task("a"));
        for(var raw:List.of(json,json+" \n",json+"\n\n")){
            var handle=cas.put(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),()->false);
            assertThrows(IOException.class,()->tables.readBounded(new ExecutionPlan.Table("execution-task-table:m8.4-v1",handle,1),ExecutionGraph.Task.class,2,10000));
        }
    }
    @Test void forgedNativeTrustFlagStaysProducerAssertion()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));var provider=new VersionedIdentifier("capture.test","1");
        var manifest=new CapturedBuildEvidence.Manifest(d("ctx"),d("selection"),d("closure"),provider,d("tool"),List.of(d("first"),d("second")),List.of(),List.of("--release","21"),List.of());
        var observation=new CapturedBuildEvidence.Observation(manifest,CapturedBuildEvidence.Trust.VALIDATED_OBSERVATION,0,true,true,Optional.empty(),List.of(d("output")),d("forged-receipt"));
        var bytes=CanonicalJson.write(new NativeCaptureParser.Envelope("native-build-capture:m8.4-v1",observation)).getBytes(StandardCharsets.UTF_8);
        var handle=cas.put(new ByteArrayInputStream(bytes),()->false);var captured=NativeCaptureParser.read(handle,cas,GraphJson.Limits.defaults());
        assertEquals(EvidenceVerifier.Trust.PRODUCER_ASSERTION,captured.trust());assertEquals(observation,captured.declared().observation());assertEquals(handle,captured.raw());
        assertTrue(CapturedBuildEvidence.validate(manifest,captured.declared().observation(),ignored->false).isEmpty());
    }
    @Test void hostileNativeNumbersAndOversizedCaptureRemainTypedParserOutcomes()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));var provider=new VersionedIdentifier("capture.test","1");
        var manifest=new CapturedBuildEvidence.Manifest(d("ctx"),d("sel"),d("closure"),provider,d("tool"),List.of(),List.of(),List.of(),List.of());
        var observation=new CapturedBuildEvidence.Observation(manifest,CapturedBuildEvidence.Trust.PRODUCER_ASSERTION,0,true,false,Optional.empty(),List.of(),d("receipt"));
        String json=CanonicalJson.write(new NativeCaptureParser.Envelope("native-build-capture:m8.4-v1",observation));
        for(String value:List.of("999999999999999999999999999","0.5")){
            var bytes=json.replace("\"exitCode\":0","\"exitCode\":"+value).getBytes(StandardCharsets.UTF_8);
            var handle=cas.put(new ByteArrayInputStream(bytes),()->false);
            assertThrows(IOException.class,()->NativeCaptureParser.read(handle,cas,GraphJson.Limits.defaults()));
        }
        var large=cas.put(new ByteArrayInputStream((json+" ".repeat(1000)).getBytes(StandardCharsets.UTF_8)),()->false);
        assertEquals("NATIVE_CAPTURE_WORKER_REQUIRED",assertThrows(IOException.class,()->NativeCaptureParser.read(large,cas,new GraphJson.Limits(100,24,100))).getMessage());
    }

}
