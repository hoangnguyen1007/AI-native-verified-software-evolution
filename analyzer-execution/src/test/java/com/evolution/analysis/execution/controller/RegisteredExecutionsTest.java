package com.evolution.analysis.execution.controller;

import com.evolution.analysis.execution.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.execution.RegisteredExecutionPort.*;

class RegisteredExecutionsTest {
    @TempDir Path root;
    static RegisteredExecutions.Registration registration(ExecutionAttempt a,ExecutionController controller){
        return new RegisteredExecutions.Registration(a.launch(),a.admission(),a.expectedTask(),controller);
    }
    @Test void submissionIsIdempotentAndRestartReadsTheSameDurableReceipt()throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=root;var h=fixture.new Harness();ExecutionAttempt prepared;View first;
        try(var open=h.open()){
            prepared=open.prepare("registered-attempt","compile");var r=registration(prepared,open.controller);var service=new RegisteredExecutions(List.of(r));
            assertEquals(Failure.NOT_SUBMITTED,assertThrows(Rejected.class,()->service.execute(Operation.STATUS,r.identity())).failure());
            first=service.execute(Operation.SUBMIT,r.identity());assertEquals("RUNNING",first.attemptPhase());
            assertEquals(first,service.execute(Operation.SUBMIT,r.identity()));assertEquals(1,h.backend.launches);
        }
        try(var open=h.open()){
            var r=registration(prepared,open.controller);var service=new RegisteredExecutions(List.of(r));
            assertEquals(first,service.execute(Operation.STATUS,r.identity()));
            var cancelled=service.execute(Operation.CANCEL,r.identity());assertEquals("RELEASED",cancelled.attemptPhase());
            assertEquals(ExecutionState.Phase.CANCELLED,cancelled.taskPhase());
            assertEquals(cancelled,service.execute(Operation.SUBMIT,r.identity()));
            assertEquals(cancelled,service.execute(Operation.RECOVER,r.identity()));
            assertEquals(1,h.backend.launches);assertEquals(1,h.backend.stops);
        }
    }
    @Test void unqualifiedSubmissionWaitsWithoutTakingAWorkerLeaseAndCanBeCancelled()throws Exception{
        var qualified=new AtomicBoolean(false);
        var backend=new ExecutionControllerTest.Backend(){
            @Override public Probe probe()throws java.io.IOException{return qualified.get()?super.probe():new Probe(Availability.UNQUALIFIED,"UNQUALIFIED",ExecutionControllerTest.VERSION,Optional.empty());}
        };
        var fixture=new ExecutionControllerTest();fixture.root=root;var h=fixture.new Harness(backend);
        try(var open=h.open()){
            var r=registration(open.prepare("waiting","compile"),open.controller);var service=new RegisteredExecutions(List.of(r));
            var waiting=service.execute(Operation.SUBMIT,r.identity());assertEquals("QUEUED",waiting.attemptPhase());
            assertEquals(ExecutionState.Phase.READY,waiting.taskPhase());assertEquals("CURRENT_SANDBOX_QUALIFICATION_REQUIRED",waiting.reason());
            assertEquals(0,backend.launches);assertTrue(open.queue.get().active().isEmpty());
            assertEquals(ExecutionState.Phase.CANCELLED,service.execute(Operation.CANCEL,r.identity()).taskPhase());
            qualified.set(true);service.execute(Operation.SUBMIT,r.identity());assertEquals(0,backend.launches);
        }
    }
    @Test void registrationsCannotAliasNamespacesOrReplaceAnExistingLaunch()throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=root;var h=fixture.new Harness();
        try(var open=h.open()){
            var original=open.prepare("one","compile");var r=registration(original,open.controller);
            assertThrows(IllegalArgumentException.class,()->new RegisteredExecutions(List.of(r,r)));
            var service=new RegisteredExecutions(List.of(r));service.execute(Operation.SUBMIT,r.identity());
            assertEquals(Failure.NOT_REGISTERED,assertThrows(Rejected.class,()->service.execute(Operation.CANCEL,ExecutionControllerTest.d("unknown"))).failure());
            var l=original.launch();var changed=new SandboxExecutor.Launch(l.plan(),l.task(),l.recipe(),l.profile(),l.immutableInputs(),ExecutionControllerTest.d("changed-policy"),l.attempt(),l.fence());
            var other=new RegisteredExecutions.Registration(changed,original.admission(),original.expectedTask(),open.controller);
            assertThrows(IllegalArgumentException.class,()->new RegisteredExecutions(List.of(r,other)));
            var changedService=new RegisteredExecutions(List.of(other));
            assertEquals(Failure.SCOPE_CONFLICT,assertThrows(Rejected.class,()->changedService.execute(Operation.STATUS,other.identity())).failure());
            assertEquals(1,h.backend.launches);assertEquals(0,h.backend.stops);
        }
    }
    @Test void failedProbeCannotPreventCancellationOrLeakPrivateDiagnostics()throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=root;var h=fixture.new Harness();
        try(var open=h.open()){
            var r=registration(open.prepare("cancel-probe-loss","compile"),open.controller);var service=new RegisteredExecutions(List.of(r));
            service.execute(Operation.SUBMIT,r.identity());h.backend.unavailableProbe=true;
            var cancelled=service.execute(Operation.CANCEL,r.identity());
            assertEquals(ExecutionState.Phase.CANCELLED,cancelled.taskPhase());
            assertEquals(SandboxExecutor.Availability.UNAVAILABLE,cancelled.availability());
            assertFalse(cancelled.toString().contains("PRIVATE_SENTINEL"));assertEquals(1,h.backend.stops);
        }
    }
    @Test void anOldReceiptCannotInheritTheStateOfANewerAttempt()throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=root;var h=fixture.new Harness();
        try(var open=h.open()){
            var r=registration(open.prepare("old-attempt","compile"),open.controller);var service=new RegisteredExecutions(List.of(r));
            service.execute(Operation.SUBMIT,r.identity());service.execute(Operation.RECOVER,r.identity());
            var previous=open.tasks.get(r.launch().plan(),r.launch().task()).orElseThrow();
            open.tasks.compareAndSet(r.launch().plan(),previous,previous.lease(previous.revision(),"new-attempt"));
            assertEquals(Failure.SCOPE_CONFLICT,assertThrows(Rejected.class,()->service.execute(Operation.STATUS,r.identity())).failure());
            assertEquals(Failure.SCOPE_CONFLICT,assertThrows(Rejected.class,()->service.execute(Operation.CANCEL,r.identity())).failure());
            assertEquals("new-attempt",open.tasks.get(r.launch().plan(),r.launch().task()).orElseThrow().attempt().orElseThrow());
        }
    }
    @Test void independentFacadesSubmittingConcurrentlyStillLaunchOneWorker()throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=root;var h=fixture.new Harness();
        try(var open=h.open();var pool=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var r=registration(open.prepare("parallel-submit","compile"),open.controller);
            var results=new ArrayList<java.util.concurrent.Future<View>>();
            for(int i=0;i<8;i++)results.add(pool.submit(()->new RegisteredExecutions(List.of(r)).execute(Operation.SUBMIT,r.identity())));
            for(var result:results)assertEquals("RUNNING",result.get(10,java.util.concurrent.TimeUnit.SECONDS).attemptPhase());
            assertEquals(1,h.backend.launches);assertEquals(1,open.queue.get().active().size());
        }
    }
    @Test void aBlockedProbeDoesNotHoldCancellationOrPermitLaunchAfterCancellation()throws Exception{
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var first=new AtomicBoolean(true);
        var backend=new ExecutionControllerTest.Backend(){
            @Override public Probe probe()throws java.io.IOException{
                if(first.compareAndSet(true,false)){
                    entered.countDown();try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("PROBE_TIMEOUT");}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException("PROBE_INTERRUPTED");}
                }
                return super.probe();
            }
        };
        var fixture=new ExecutionControllerTest();fixture.root=root;var h=fixture.new Harness(backend);
        try(var open=h.open();var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var r=registration(open.prepare("blocked-probe","compile"),open.controller);var service=new RegisteredExecutions(List.of(r));
            var submitting=pool.submit(()->service.execute(Operation.SUBMIT,r.identity()));
            try{
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                var cancelling=pool.submit(()->service.execute(Operation.CANCEL,r.identity()));
                assertEquals(ExecutionState.Phase.CANCELLED,cancelling.get(5,java.util.concurrent.TimeUnit.SECONDS).taskPhase());
                assertEquals(0,backend.launches);
            }finally{release.countDown();}
            assertEquals(ExecutionState.Phase.CANCELLED,submitting.get(5,java.util.concurrent.TimeUnit.SECONDS).taskPhase());
            assertEquals(0,backend.launches);assertTrue(open.queue.get().active().isEmpty());
        }
    }
}
