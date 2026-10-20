package com.evolution.cli;

import java.io.*;
import java.nio.file.Path;
import java.util.Map;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.graph.GraphJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionCommandTest {
    @TempDir Path root;
    @Test void defaultCommandReportsMissingExecutorWithoutRunningAnyCommand(){
        var output=new StringWriter();var errors=new StringWriter();
        var app=new EvoApp(root,new StringReader(""),new PrintWriter(output),new PrintWriter(errors),false,Map.of());
        assertEquals(3,app.execute("execution","status","sha256:"+"a".repeat(64),"--json"));
        assertTrue(errors.toString().contains("EXECUTIONS_UNAVAILABLE"));assertTrue(output.toString().isEmpty());
    }
    @Test void cliUsesTheSameRegisteredPortAndNeverReportsRunningAsComplete(){
        var id=ContentDigest.sha256Utf8("registration");var calls=new java.util.ArrayList<RegisteredExecutionPort.Operation>();
        RegisteredExecutionPort port=(op,registration)->{
            calls.add(op);assertEquals(id,registration);boolean cancelled=op==RegisteredExecutionPort.Operation.CANCEL;
            return new RegisteredExecutionPort.View(id,ContentDigest.sha256Utf8("plan"),ContentDigest.sha256Utf8("task"),"attempt",cancelled?"RELEASED":"RUNNING",cancelled?ExecutionState.Phase.CANCELLED:ExecutionState.Phase.RUNNING,"9007199254740993","1",SandboxExecutor.Availability.AVAILABLE,"");
        };
        for(var op:RegisteredExecutionPort.Operation.values()){
            var output=new StringWriter();var errors=new StringWriter();var app=EvoApp.withExecutions(root,new StringReader(""),new PrintWriter(output),new PrintWriter(errors),false,Map.of(),port);
            assertEquals(op==RegisteredExecutionPort.Operation.CANCEL?0:3,app.execute("execution",op.name().toLowerCase(java.util.Locale.ROOT),id.value(),"--json"));
            var json=GraphJson.object(GraphJson.read(output.toString().strip(),GraphJson.Limits.defaults()));
            assertEquals(RegisteredExecutionPort.VERSION,json.get("apiVersion"));
            assertEquals("9007199254740993",GraphJson.object(json.get("data")).get("revision"));assertTrue(errors.toString().isEmpty());
        }
        assertEquals(java.util.List.of(RegisteredExecutionPort.Operation.values()),calls);
    }
    @Test void arbitraryCommandFlagsAndInvalidRegistrationsNeverReachThePort(){
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var output=new StringWriter();var errors=new StringWriter();var app=EvoApp.withExecutions(root,new StringReader(""),new PrintWriter(output),new PrintWriter(errors),false,Map.of(),(op,id)->{calls.incrementAndGet();throw new IOException("PRIVATE_SENTINEL");});
        assertEquals(2,app.execute("execution","submit","sha256:"+"a".repeat(64),"--argv","PRIVATE_SENTINEL","--json"));
        assertEquals(2,app.execute("execution","submit","not-a-registration","--json"));assertEquals(0,calls.get());
        errors.getBuffer().setLength(0);
        assertEquals(3,app.execute("execution","submit","sha256:"+"a".repeat(64),"--json"));
        assertFalse(errors.toString().contains("PRIVATE_SENTINEL"));assertTrue(output.toString().isEmpty());
    }
    @Test void coreMvpRequiresAnExplicitApprovedRegistrationBeforeAnyLauncher(){
        var output=new StringWriter();var errors=new StringWriter();
        try{java.nio.file.Files.writeString(root.resolve("setup.json"),"{\"acceptanceScope\":\"OTHER\",\"approvedBy\":\"NONE\"}");}catch(IOException failure){throw new AssertionError(failure);}
        var app=new EvoApp(root,new StringReader(""),new PrintWriter(output),new PrintWriter(errors),false,Map.of());
        assertEquals(1,app.execute("core-mvp",root.toString(),"--json"));assertTrue(output.toString().isEmpty());
        assertFalse(java.nio.file.Files.exists(root.resolve("tools")));
    }
    @Test void selfDeclaredTargetConfigurationCannotRegisterItsOwnHostLauncher()throws Exception{
        java.nio.file.Files.writeString(root.resolve("setup.json"),"{\"acceptanceScope\":\"CORE_MVP\",\"approvedBy\":\"HUMAN\",\"attempt\":\""+java.util.UUID.randomUUID()+"\"}");
        var output=new StringWriter();var errors=new StringWriter();
        var app=new EvoApp(root,new StringReader(""),new PrintWriter(output),new PrintWriter(errors),false,Map.of());
        assertEquals(1,app.execute("core-mvp",root.toString(),"--json"));assertTrue(output.toString().isEmpty());
        assertFalse(java.nio.file.Files.exists(root.resolve("tools")));
    }
}
