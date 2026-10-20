package com.evolution.analysis.execution.controller;

import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.broker.ArtifactBroker;
import com.evolution.analysis.execution.broker.ArtifactBrokerServer;
import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ControllerBrokerAuthorityTest {
    @TempDir Path root;
    static final URI REPOSITORY=URI.create("https://artifacts.example.test/repository/");

    @Test void stopDuringAuditCannotLeakAResponseThroughTheHttpFacade()throws Exception{
        var fixtures=new ExecutionControllerTest();fixtures.root=root;var h=fixtures.new Harness();
        try(var open=h.open()){
            var a=open.prepare("http-stop","task");open.enqueue(a);open.controller.start("http-stop");
            var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(REPOSITORY)),false,h.cas,
                    (uri,c)->new ByteArrayInputStream(new byte[]{42}),material->{
                        if(material.state()==ArtifactBroker.State.ACQUIRED)open.controller.cancel("http-stop");
                    },Clock.systemUTC(),new ControllerBrokerAuthority(open.attempts,open.tasks,a.launch()));
            var grant=broker.grant("http-stop",1,Duration.ofMinutes(1),100);
            try(var server=new ArtifactBrokerServer(broker,grant,1,Map.of("test",REPOSITORY),h.cas,Map.of())){
                var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(server.baseUri().resolve("test/a.jar")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(503,response.statusCode());assertEquals(0,response.body().length);
            }
        }
    }

    @Test void queuedForeignAndChangedLaunchCannotAuthorizeAndLaunchIntentCan()throws Exception{
        var fixtures=new ExecutionControllerTest();fixtures.root=root;var h=fixtures.new Harness();
        try(var open=h.open(ExecutionControllerTest.crashAt(ExecutionController.Checkpoint.LAUNCH_INTENT))){
            var a=open.prepare("scope","task");open.enqueue(a);
            var authority=new ControllerBrokerAuthority(open.attempts,open.tasks,a.launch());
            assertThrows(IOException.class,()->authority.require("scope",1));
            assertThrows(ExecutionControllerTest.Crash.class,()->open.controller.start("scope"));
            authority.require("scope",1); // Native bootstrap may begin before its handle is committed.
            assertThrows(IOException.class,()->authority.require("scope",2));
            assertThrows(IOException.class,()->authority.require("foreign",1));
            var l=a.launch();var changed=new SandboxExecutor.Launch(l.plan(),l.task(),ExecutionControllerTest.d("changed-recipe"),l.profile(),l.immutableInputs(),l.policy(),l.attempt(),l.fence());
            assertThrows(IOException.class,()->new ControllerBrokerAuthority(open.attempts,open.tasks,changed).require("scope",1));
        }
    }

    @Test void restartAndReissuedGrantCannotUndoCommittedStopIntent()throws Exception{
        var fixtures=new ExecutionControllerTest();fixtures.root=root;var h=fixtures.new Harness();SandboxExecutor.Launch launch;
        try(var open=h.open()){
            var a=open.prepare("restart","task");launch=a.launch();open.enqueue(a);open.controller.start("restart");
        }
        try(var open=h.open(ExecutionControllerTest.crashAt(ExecutionController.Checkpoint.STOP_INTENT))){
            assertThrows(ExecutionControllerTest.Crash.class,()->open.controller.cancel("restart"));
            assertTrue(h.backend.running.contains("restart"));
        }
        try(var open=h.open()){
            var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(REPOSITORY)),true,h.cas,
                    (uri,c)->{throw new AssertionError("revoked transfer");},m->{},Clock.systemUTC(),
                    new ControllerBrokerAuthority(open.attempts,open.tasks,launch));
            var reissued=broker.grant("restart",1,Duration.ofMinutes(5),100);
            assertEquals("BROKER_GRANT_INVALID",assertThrows(IOException.class,()->broker.acquire(reissued,1,REPOSITORY,Optional.of(h.pin),()->false)).getMessage());
            assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.recover("restart").phase());
            assertThrows(IOException.class,()->broker.acquire(reissued,1,REPOSITORY,Optional.of(h.pin),()->false));
        }
    }

    @Test void revocationDuringReadCannotCommitSuccessfulMaterial()throws Exception{
        var fixtures=new ExecutionControllerTest();fixtures.root=root;var h=fixtures.new Harness();
        try(var open=h.open()){
            var a=open.prepare("in-flight","task");open.enqueue(a);open.controller.start("in-flight");
            var events=new ArrayList<ArtifactBroker.Material>();var stopped=new AtomicBoolean();
            var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(REPOSITORY)),false,h.cas,(uri,c)->new InputStream(){
                @Override public int read()throws IOException{
                    if(stopped.getAndSet(true))return -1;
                    h.backend.unknown=true;open.controller.cancel("in-flight");return 42;
                }
            },events::add,Clock.systemUTC(),new ControllerBrokerAuthority(open.attempts,open.tasks,a.launch()));
            var grant=broker.grant("in-flight",1,Duration.ofMinutes(1),100);
            var material=broker.acquire(grant,1,REPOSITORY,Optional.empty(),()->false);
            assertEquals(ArtifactBroker.State.UNAVAILABLE,material.state());assertTrue(material.content().isEmpty());
            assertEquals("BROKER_GRANT_INVALID",material.reason());assertEquals(1,events.size());
        }
    }

    @Test void taskQuarantineClosesGrantEvenBeforeAttemptStop()throws Exception{
        var fixtures=new ExecutionControllerTest();fixtures.root=root;var h=fixtures.new Harness();
        try(var open=h.open()){
            var a=open.prepare("task-fence","task");open.enqueue(a);open.controller.start("task-fence");
            var authority=new ControllerBrokerAuthority(open.attempts,open.tasks,a.launch());authority.require("task-fence",1);
            var state=open.tasks.get(a.launch().plan(),a.launch().task()).orElseThrow();
            open.tasks.compareAndSet(a.launch().plan(),state,state.advance(state.revision(),1,ExecutionState.Phase.QUARANTINED,"trusted-fence",Optional.empty(),Optional.empty()));
            assertThrows(IOException.class,()->authority.require("task-fence",1));
        }
    }

    @Test void committedStopIntentRevokesCachedAndNewTransfersBeforeVmStop()throws Exception{
        var fixtures=new ExecutionControllerTest();fixtures.root=root;var h=fixtures.new Harness();
        try(var open=h.open()){
            var attempt=open.prepare("broker-stop","task");open.enqueue(attempt);open.controller.start(attempt.launch().attempt());
            var events=new ArrayList<ArtifactBroker.Material>();
            var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(REPOSITORY)),false,h.cas,
                    (uri,c)->new ByteArrayInputStream(new byte[]{42}),events::add,Clock.systemUTC(),
                    new ControllerBrokerAuthority(open.attempts,open.tasks,attempt.launch()));
            var grant=broker.grant(attempt.launch().attempt(),attempt.launch().fence(),Duration.ofMinutes(5),100);
            var captured=broker.acquire(grant,1,REPOSITORY.resolve("a.jar"),Optional.empty(),()->false);
            assertEquals(ArtifactBroker.State.ACQUIRED,captured.state());
            h.backend.unknown=true;
            assertEquals(ExecutionAttempt.Phase.QUARANTINED,open.controller.cancel(attempt.launch().attempt()).phase());
            assertEquals(1,open.queue.get().active().size());
            assertThrows(IOException.class,()->broker.acquire(grant,1,REPOSITORY.resolve("a.jar"),captured.content(),()->false));
            assertThrows(IOException.class,()->broker.acquire(grant,1,REPOSITORY.resolve("new.jar"),Optional.empty(),()->false));
        }
    }
}
