package com.evolution.analysis.execution.controller;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.output.OutputCensus;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DurableOutputPublicationTest {
    @Test void precommittedCaptureIntentSurvivesRejectedLaunchAndRecovery()throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=root;var harness=fixture.new Harness();
        try(var open=harness.open();var p=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536)){
            var a=open.prepare(ATTEMPT,"task",ExecutionControllerTest.CAPACITY);open.enqueue(a);
            assertDoesNotThrow(()->p.prepare(a.launch(),scope(),List.of(new DurableOutputPublication.Expectation("bytecode",LogicalPath.utf8("classes/Caller.class"),true))));
            harness.backend.certificate=ExecutionControllerTest.d("unqualified");
            assertEquals(ExecutionAttempt.Phase.QUARANTINED,open.controller.start(ATTEMPT).phase());
            assertEquals(0,harness.backend.launches);
            assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.recover(ATTEMPT).phase());
            assertEquals(1,open.queue.get().retained().size());
            assertEquals(OutputCensus.Decision.CAPTURED,p.publish(a.launch(),header(harness.cas,true),()->false).decision());
            assertEquals(ExecutionState.Phase.FAILED,open.tasks.get(a.launch().plan(),a.launch().task()).orElseThrow().phase());
        }
    }
    @Test void stoppedAttemptCannotInventANewCaptureScopeAfterTheFact()throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=root;var harness=fixture.new Harness();
        try(var open=harness.open();var p=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536)){
            var a=open.prepare(ATTEMPT,"task");open.enqueue(a);open.controller.start(ATTEMPT);open.controller.cancel(ATTEMPT);
            assertEquals("PUBLICATION_PREPARE_REQUIRES_OPEN_ATTEMPT",assertThrows(IOException.class,()->p.prepare(a.launch(),scope(),List.of())).getMessage());
        }
    }
    @TempDir Path root;
    static final String ATTEMPT="0123456789abcdef0123456789abcdef";
    static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    static ContentHandle put(com.evolution.analysis.filesystem.StreamingContentStore cas,Object value)throws IOException{return cas.put(new ByteArrayInputStream(bytes(CanonicalJson.write(value))),()->false);}
    static OutputCensus.Scope scope(){return new OutputCensus.Scope(ATTEMPT,"f".repeat(32),new OutputCensus.Budget(65536,1024,4096));}
    static ContentHandle header(com.evolution.analysis.filesystem.StreamingContentStore cas,boolean closed)throws Exception{
        return header(cas,closed,true);
    }
    static ContentHandle header(com.evolution.analysis.filesystem.StreamingContentStore cas,boolean closed,boolean available)throws Exception{
        var object=new ContentHandle(ContentDigest.sha256Utf8("bytecode"),8);
        if(available)assertEquals(object,cas.put(new ByteArrayInputStream(bytes("bytecode")),()->false));
        var row=new TreeMap<String,Object>();row.put("path",LogicalPath.utf8("classes/Caller.class").rawBase64());row.put("kind","FILE");row.put("size",8);row.put("mode",384);row.put("blocks",0);row.put("links",1);row.put("content",object);row.put("linkTarget",null);row.put("linkGroup",null);row.put("disposition","PRESENT");row.put("reason","");
        var manifest=cas.put(new ByteArrayInputStream(bytes(CanonicalJson.write(row)+"\n")),()->false);
        // Guest scope contains the absolute guest path, without any host-path interpretation.
        var scopeRecord=put(cas,Map.of("schema","guest-output-scope:m8.4-v1","nonce",scope().nonce(),"work",Base64.getEncoder().encodeToString(bytes("/work/"+ATTEMPT)),"budget",scope().budget()));
        var h=new TreeMap<String,Object>();h.put("schema","guest-output-census:m8.4-v2");h.put("attempt",ATTEMPT);h.put("nonce",scope().nonce());h.put("entries",1);h.put("logicalBytes",8);h.put("capturedBytes",8);h.put("pending",0);h.put("rejected",0);h.put("inventoryClosed",closed);h.put("captureClosed",closed);h.put("manifest",manifest);h.put("scope",scopeRecord);h.put("trust","PRODUCER_ASSERTION");return put(cas,h);
    }
    @Test void closedPublicationSurvivesRestartWithoutPromotingTrustOrTaskSuccess()throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=root;var harness=fixture.new Harness();
        SandboxExecutor.Launch launch;ContentHandle header;
        try(var open=harness.open();var publication=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536)){
            var a=open.prepare(ATTEMPT,"task");open.enqueue(a);open.controller.start(ATTEMPT);launch=a.launch();
            publication.prepare(launch,scope(),List.of(new DurableOutputPublication.Expectation("bytecode",LogicalPath.utf8("classes/Caller.class"),true)));
            header=header(harness.cas,true);assertThrows(IOException.class,()->publication.publish(launch,header,()->false));
            open.controller.finish(ATTEMPT);
            assertDoesNotThrow(()->publication.prepare(launch,scope(),List.of(new DurableOutputPublication.Expectation("bytecode",LogicalPath.utf8("classes/Caller.class"),true))));
            var result=publication.publish(launch,header,()->false);
            assertEquals(OutputCensus.Decision.CAPTURED,result.decision());assertEquals("CAPTURED_BYTES",result.roles().getFirst().outcome());
            assertEquals(EvidenceVerifier.Trust.PRODUCER_ASSERTION,result.effectiveTrust());
            assertEquals(ExecutionState.Phase.COLLECTING,open.tasks.get(launch.plan(),launch.task()).orElseThrow().phase());
            assertEquals(result,publication.publish(launch,header,()->false));
        }
        try(var open=harness.open();var publication=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536)){
            var result=publication.publish(launch,header,()->false);assertEquals(OutputCensus.Decision.CAPTURED,result.decision());assertTrue(harness.cas.verify(result.outcomes()));
        }
    }
    @Test void requiredOptionalAndUnobservedRolesRetainSeparateOutcomes()throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=root;var harness=fixture.new Harness();
        try(var open=harness.open();var p=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536)){
            var a=open.prepare(ATTEMPT,"task");open.enqueue(a);open.controller.start(ATTEMPT);
            p.prepare(a.launch(),scope(),List.of(new DurableOutputPublication.Expectation("missing",LogicalPath.utf8("missing.jar"),true),new DurableOutputPublication.Expectation("optional",LogicalPath.utf8("optional.jar"),false)));
            open.controller.cancel(ATTEMPT);var result=p.publish(a.launch(),header(harness.cas,true),()->false);
            assertEquals(OutputCensus.Decision.OPEN,result.decision());assertEquals(List.of("MISSING_REQUIRED","OPTIONAL_ABSENT"),result.roles().stream().map(DurableOutputPublication.RoleResult::outcome).toList());
            assertEquals(ExecutionState.Phase.CANCELLED,open.tasks.get(a.launch().plan(),a.launch().task()).orElseThrow().phase());
        }
    }
    @Test void budgetAndCancellationKeepIntentForALargerWorkerWithoutLosingRows()throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=root;var harness=fixture.new Harness();SandboxExecutor.Launch launch;ContentHandle source;
        try(var open=harness.open();var p=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,0)){
            var a=open.prepare(ATTEMPT,"task");launch=a.launch();open.enqueue(a);open.controller.start(ATTEMPT);p.prepare(launch,scope(),List.of());open.controller.finish(ATTEMPT);source=header(harness.cas,true);
            assertEquals("OUTPUT_PUBLICATION_WORKER_REQUIRED",assertThrows(IOException.class,()->p.publish(launch,source,()->false)).getMessage());
        }
        try(var open=harness.open();var p=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536)){
            assertThrows(IOException.class,()->p.publish(launch,source,()->true));
            var result=p.publish(launch,source,()->false);assertEquals(OutputCensus.Decision.CAPTURED,result.decision());
            try(var input=harness.cas.open(result.outcomes())){assertEquals(1,new String(input.readAllBytes(),StandardCharsets.UTF_8).lines().count());}
        }
    }
    static final class Crash extends Error {}
    @Test void restartAtEachPublicationBoundaryReusesExactlyTheSameEvidence()throws Exception{
        var base=root;
        for(var point:DurableOutputPublication.Checkpoint.values()){
            root=base.resolve(point.name());Files.createDirectories(root);var fixture=new ExecutionControllerTest();fixture.root=root;var harness=fixture.new Harness();SandboxExecutor.Launch launch;ContentHandle source;
            try(var open=harness.open();var p=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536,c->{if(c==point)throw new Crash();})){
                var a=open.prepare(ATTEMPT,"task");launch=a.launch();open.enqueue(a);open.controller.start(ATTEMPT);p.prepare(launch,scope(),List.of());source=header(harness.cas,true);open.controller.finish(ATTEMPT);
                assertThrows(Crash.class,()->p.publish(launch,source,()->false));
            }
            try(var open=harness.open();var p=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536)){
                var result=p.publish(launch,source,()->false);assertEquals(result,p.publish(launch,source,()->false));assertEquals(1,harness.backend.launches);
            }
        }
    }
    @Test void changedNonceRolesAndCensusCannotReplaceAnExistingPublication()throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=root;var harness=fixture.new Harness();
        try(var open=harness.open();var p=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536)){
            var a=open.prepare(ATTEMPT,"task");open.enqueue(a);open.controller.start(ATTEMPT);p.prepare(a.launch(),scope(),List.of());
            var foreign=new OutputCensus.Scope("a".repeat(32),scope().nonce(),scope().budget());assertThrows(IOException.class,()->p.prepare(a.launch(),foreign,List.of()));
            assertThrows(IOException.class,()->p.prepare(a.launch(),scope(),List.of(new DurableOutputPublication.Expectation("changed",LogicalPath.utf8("other"),true))));
            open.controller.finish(ATTEMPT);var original=header(harness.cas,true);var result=p.publish(a.launch(),original,()->false);
            assertEquals("PUBLICATION_EVIDENCE_REVISION_REQUIRED",assertThrows(IOException.class,()->p.publish(a.launch(),header(harness.cas,false),()->false)).getMessage());
            assertEquals(result,p.publish(a.launch(),original,()->false));
        }
    }
    @Test void optionalUnavailableBytesAndOpenInventoryAreNotReportedAsAbsent()throws Exception{
        var base=root;
        for(boolean available:List.of(false,true)){
            root=base.resolve(Boolean.toString(available));Files.createDirectories(root);var fixture=new ExecutionControllerTest();fixture.root=root;var harness=fixture.new Harness();
            try(var open=harness.open();var p=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536)){
                var a=open.prepare(ATTEMPT,"task");open.enqueue(a);open.controller.start(ATTEMPT);
                p.prepare(a.launch(),scope(),List.of(new DurableOutputPublication.Expectation("optional",LogicalPath.utf8(available?"unknown.jar":"classes/Caller.class"),false)));
                open.controller.finish(ATTEMPT);var result=p.publish(a.launch(),header(harness.cas,!available,available),()->false);
                assertEquals(OutputCensus.Decision.OPEN,result.decision());assertEquals(available?"ABSENCE_UNPROVEN":"UNAVAILABLE",result.roles().getFirst().outcome());
            }
        }
    }
    @Test void forcedProcessExitBeforeAndAfterCommitIsRecoveredWithoutAnotherBuild()throws Exception{
        var base=root;var binary=Path.of(System.getProperty("java.home"),"bin","java.exe");String javaExecutable=(Files.exists(binary)?binary:binary.resolveSibling("java")).toString();
        for(var point:List.of(DurableOutputPublication.Checkpoint.REPORT_CAPTURED,DurableOutputPublication.Checkpoint.PUBLISHED)){
            root=base.resolve(point.name());Files.createDirectories(root);
            var process=new ProcessBuilder(javaExecutable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),DurableOutputPublicationTest.class.getName(),root.toString(),point.name()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(25,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();fail("TRUSTED_PUBLICATION_FIXTURE_TIMEOUT");}
            assertEquals(78,process.exitValue());var fixture=new ExecutionControllerTest();fixture.root=root;var harness=fixture.new Harness();
            try(var open=harness.open();var p=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536)){
                var a=open.attempts.get(ATTEMPT);assertEquals(ExecutionAttempt.Phase.RELEASED,a.phase());
                var result=p.publish(a.launch(),header(harness.cas,true),()->false);assertEquals(result,p.publish(a.launch(),header(harness.cas,true),()->false));
                assertEquals(OutputCensus.Decision.CAPTURED,result.decision());assertEquals(0,harness.backend.launches);
            }
        }
    }
    @Test void emptyAndRejectedCensusesKeepExactDispositionWithoutSemanticPublication()throws Exception{
        var base=root;
        for(boolean empty:List.of(true,false)){
            root=base.resolve(Boolean.toString(empty));Files.createDirectories(root);var fixture=new ExecutionControllerTest();fixture.root=root;var harness=fixture.new Harness();
            try(var open=harness.open();var p=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536)){
                var a=open.prepare(ATTEMPT,"task");open.enqueue(a);open.controller.start(ATTEMPT);
                assertThrows(IOException.class,()->p.prepare(a.launch(),scope(),List.of(new DurableOutputPublication.Expectation("duplicate",LogicalPath.utf8("a"),true),new DurableOutputPublication.Expectation("duplicate",LogicalPath.utf8("b"),false))));
                p.prepare(a.launch(),scope(),List.of());open.controller.finish(ATTEMPT);
                var source=header(harness.cas,true);Map<String,Object> value;
                try(var input=harness.cas.open(source)){value=new TreeMap<>(com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),com.evolution.analysis.graph.GraphJson.Limits.defaults())));}
                if(empty){value.put("manifest",harness.cas.put(new ByteArrayInputStream(new byte[0]),()->false));value.put("entries",0);value.put("logicalBytes",0);value.put("capturedBytes",0);}
                else value.put("entries",2);
                var result=p.publish(a.launch(),put(harness.cas,value),()->false);assertEquals(empty?OutputCensus.Decision.CAPTURED:OutputCensus.Decision.REJECTED,result.decision());
                try(var input=harness.cas.open(result.document())){
                    var report=com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),com.evolution.analysis.graph.GraphJson.Limits.defaults()));
                    assertEquals(false,report.get("activeSemanticPublication"));assertEquals("PRODUCER_ASSERTION",report.get("trust"));
                }
            }
        }
    }
    public static void main(String[] args)throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=Path.of(args[0]);var harness=fixture.new Harness();var point=DurableOutputPublication.Checkpoint.valueOf(args[1]);
        try(var open=harness.open();var p=new DurableOutputPublication(fixture.root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536,c->{if(c==point)Runtime.getRuntime().halt(78);})){
            var a=open.prepare(ATTEMPT,"task");open.enqueue(a);open.controller.start(ATTEMPT);p.prepare(a.launch(),scope(),List.of());open.controller.finish(ATTEMPT);p.publish(a.launch(),header(harness.cas,true),()->false);
        }
        throw new AssertionError("PUBLICATION_CRASH_CHECKPOINT_NOT_REACHED");
    }
}
