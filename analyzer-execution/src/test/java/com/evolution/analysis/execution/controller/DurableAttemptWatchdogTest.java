package com.evolution.analysis.execution.controller;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DurableAttemptWatchdogTest {
    @TempDir Path root;
    DurableAttemptWatchdog armed;
    static final class Time extends Clock {
        Instant now=Instant.parse("2026-10-20T00:00:00Z");
        volatile Runnable onRead=()->{};
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){if(!zone.equals(ZoneOffset.UTC))throw new IllegalArgumentException();return this;}
        @Override public Instant instant(){onRead.run();return now;}
        void advance(long millis){now=now.plusMillis(millis);}
    }
    static final DurableAttemptWatchdog.Policy POLICY=new DurableAttemptWatchdog.Policy(1000,100,10,2,1024);
    ExecutionControllerTest.Harness harness()throws Exception{var fixture=new ExecutionControllerTest();fixture.root=root;return fixture.new Harness();}
    static ExecutionAttempt prepare(ExecutionControllerTest.Open open,DurableAttemptWatchdog.Policy policy)throws Exception{
        var a=open.prepare("guard","task",ExecutionControllerTest.CAPACITY);var l=a.launch();
        var bound=new SandboxExecutor.Launch(l.plan(),l.task(),l.recipe(),l.profile(),l.immutableInputs(),CanonicalJson.sha256(policy),l.attempt(),l.fence());
        var next=ExecutionAttempt.prepare(bound,a.admission(),a.expectedTask());open.enqueue(next);return next;
    }
    DurableAttemptWatchdog guard(ExecutionControllerTest.Harness h,ExecutionControllerTest.Open open,Time time)throws Exception{
        armed=new DurableAttemptWatchdog(root.resolve("guard"),h.cas,open.attempts,open.tasks,time);return armed;
    }
    ExecutionControllerTest.Open open(ExecutionControllerTest.Harness h)throws Exception{return h.open(point->{},launch->armed.activate(launch));}
    @Test void watchdogDoesNotHoldItsMonitorWhileWaitingForControllerRecovery()throws Exception{
        var h=harness();var time=new Time();SandboxExecutor.Launch launch;
        try(var open=h.open(point->{},l->{armed.activate(l);throw new ExecutionControllerTest.Crash();});var guard=guard(h,open,time)){
            var a=prepare(open,POLICY);launch=a.launch();guard.register(launch,POLICY);
            assertThrows(ExecutionControllerTest.Crash.class,()->open.controller.start("guard"));
        }
        time.advance(1000);var controllerHeld=new CountDownLatch(1);var guardHeld=new CountDownLatch(1);
        try(var open=h.open(point->{},l->{
            controllerHeld.countDown();
            try{if(!guardHeld.await(3,TimeUnit.SECONDS))throw new IOException("TEST_POLL_DID_NOT_RUN");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
            armed.activate(l);
        });var guard=guard(h,open,time)){
            time.onRead=()->{if(Thread.currentThread().getName().equals("watchdog-test-poll"))guardHeld.countDown();};
            var threads=Executors.newFixedThreadPool(2,r->{var t=new Thread(r);t.setDaemon(true);return t;});
            try{
                var starting=threads.submit(()->open.controller.start("guard"));assertTrue(controllerHeld.await(3,TimeUnit.SECONDS));
                var polling=threads.submit(()->{Thread.currentThread().setName("watchdog-test-poll");return guard.poll(launch,open.controller);});
                assertEquals(ExecutionAttempt.Phase.QUARANTINED,starting.get(3,TimeUnit.SECONDS).phase());
                assertEquals(ExecutionAttempt.Phase.RELEASED,polling.get(3,TimeUnit.SECONDS).attempt().orElseThrow().phase());
                assertEquals(0,h.backend.launches);
            }finally{threads.shutdownNow();}
        }
    }
    @Test void resourceQueueWaitingDoesNotConsumeWorkerLifetime()throws Exception{
        var h=harness();var time=new Time();
        try(var open=open(h);var guard=guard(h,open,time)){
            var a=prepare(open,POLICY);guard.register(a.launch(),POLICY);time.advance(2000);
            assertTrue(guard.poll(a.launch(),open.controller).attempt().isEmpty());
            assertEquals(ExecutionAttempt.Phase.QUEUED,open.attempts.get("guard").phase());
            open.controller.start("guard");assertTrue(guard.get(a.launch()).activated());
            assertEquals(time.instant(),guard.get(a.launch()).started());assertEquals(1,h.backend.launches);
        }
    }
    @Test void missingGuardRegistrationPreventsDriverLaunchAndDoesNotRetainUnallocatedStorage()throws Exception{
        var h=harness();var time=new Time();
        try(var open=open(h);var guard=guard(h,open,time)){
            var a=prepare(open,POLICY);
            assertEquals(ExecutionAttempt.Phase.QUARANTINED,open.controller.start("guard").phase());
            assertEquals(0,h.backend.launches);assertEquals("LAUNCH_GUARD_REJECTED",open.attempts.get("guard").reason());
            assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.recover(a.launch()).phase());
            assertTrue(open.queue.get().active().isEmpty());assertTrue(open.queue.get().retained().isEmpty());
        }
    }
    @Test void launchIntentCrashCannotRenewTheActivatedGuardAfterRestart()throws Exception{
        var h=harness();var time=new Time();SandboxExecutor.Launch launch;Instant started;
        try(var open=h.open(ExecutionControllerTest.crashAt(ExecutionController.Checkpoint.LAUNCH_INTENT),l->armed.activate(l));var guard=guard(h,open,time)){
            var a=prepare(open,POLICY);launch=a.launch();guard.register(launch,POLICY);
            assertThrows(ExecutionControllerTest.Crash.class,()->open.controller.start("guard"));
            started=guard.get(launch).started();assertTrue(guard.get(launch).activated());assertEquals(0,h.backend.launches);
        }
        time.advance(1000);
        try(var open=open(h);var guard=guard(h,open,time)){
            assertEquals(started,guard.register(launch,POLICY).started());
            assertThrows(IOException.class,()->guard.activate(launch));
            assertEquals("LEASE_EXPIRED",guard.get(launch).stopReason());
            assertEquals(ExecutionAttempt.Phase.RELEASED,guard.poll(launch,open.controller).attempt().orElseThrow().phase());
            assertEquals(0,h.backend.launches);
        }
    }
    @Test void unguardedLaunchIsRevokedAndReapedRatherThanReceivingANewLifetime()throws Exception{
        var h=harness();var time=new Time();
        try(var open=h.open();var guard=guard(h,open,time)){
            var a=prepare(open,POLICY);guard.register(a.launch(),POLICY);open.controller.start("guard");
            assertThrows(IOException.class,()->guard.authority(a.launch()).require("guard",1));
            var result=guard.poll(a.launch(),open.controller);
            assertEquals("UNGUARDED_LAUNCH",result.guard().stopReason());
            assertFalse(result.guard().activated());assertEquals(1,h.backend.stops);
            assertEquals(ExecutionAttempt.Phase.RELEASED,result.attempt().orElseThrow().phase());
        }
    }
    @Test void policyCannotExceedReservationOrOverflowTimeAtRegistration()throws Exception{
        Path base=root;
        for(boolean overflow:List.of(false,true)){
            root=base.resolve(Boolean.toString(overflow));var h=harness();var time=new Time();
            if(overflow)time.now=Instant.MAX;
            try(var open=open(h);var guard=guard(h,open,time)){
                var policy=overflow?POLICY:new DurableAttemptWatchdog.Policy(1000,100,10,3,1024);
                var a=prepare(open,policy);
                assertEquals(overflow?"WATCHDOG_TIME_OUT_OF_RANGE":"WATCHDOG_RESERVATION_TOO_SMALL",assertThrows(IOException.class,()->guard.register(a.launch(),policy)).getMessage());
                assertThrows(IOException.class,()->guard.get(a.launch()));assertEquals(0,h.backend.launches);
            }
        }
    }
    @Test void pollCannotReapSameNamedAttemptInAForeignController()throws Exception{
        Path base=root;root=base.resolve("owner");var h=harness();var time=new Time();
        try(var open=open(h);var guard=guard(h,open,time)){
            var a=prepare(open,POLICY);guard.register(a.launch(),POLICY);open.controller.start("guard");
            root=base.resolve("foreign");var foreign=harness();
            try(var other=foreign.open()){
                var b=other.prepare("guard","unrelated-task");other.enqueue(b);other.controller.start("guard");
                time.advance(100);
                assertThrows(IOException.class,()->guard.poll(a.launch(),other.controller));
                assertEquals(0,foreign.backend.stops);assertTrue(foreign.backend.running.contains("guard"));
            }
        }
    }
    @Test void logSpamCannotRenewIdleBudgetAndQuiescenceRetainsStorage()throws Exception{
        var h=harness();var time=new Time();
        try(var open=open(h);var guard=guard(h,open,time)){
            var a=prepare(open,POLICY);guard.register(a.launch(),POLICY);open.controller.start("guard");
            time.advance(90);assertEquals("",guard.observe(a.launch(),new DurableAttemptWatchdog.Observation(0,1,1,20)).stopReason());
            time.advance(10);var outcome=guard.poll(a.launch(),open.controller);
            assertEquals("IDLE_EXPIRED",outcome.guard().stopReason());assertEquals(ExecutionAttempt.Phase.RELEASED,outcome.attempt().orElseThrow().phase());
            assertEquals(1,h.backend.stops);assertTrue(open.queue.get().active().isEmpty());assertEquals(1,open.queue.get().retained().size());
            assertEquals(ExecutionState.Phase.FAILED,open.tasks.get(a.launch().plan(),a.launch().task()).orElseThrow().phase());
        }
    }
    @Test void restartCannotResetDeadlineOrStopLatchDuringPartition()throws Exception{
        var h=harness();var time=new Time();SandboxExecutor.Launch launch;
        var policy=new DurableAttemptWatchdog.Policy(100,1000,10,2,1024);
        try(var open=open(h);var guard=guard(h,open,time)){
            var a=prepare(open,policy);launch=a.launch();guard.register(launch,policy);open.controller.start("guard");
        }
        time.advance(100);
        try(var open=open(h);var guard=guard(h,open,time)){
            var original=guard.get(launch);assertEquals(original,guard.register(launch,policy));
            assertThrows(IOException.class,()->guard.authority(launch).require("guard",1));
            assertEquals("LEASE_EXPIRED",guard.get(launch).stopReason());
            h.backend.unknown=true;
            assertEquals(ExecutionAttempt.Phase.QUARANTINED,guard.poll(launch,open.controller).attempt().orElseThrow().phase());
            assertEquals(1,open.queue.get().active().size());
        }
        time.advance(-100); // Even returning to the initial wall clock cannot reopen the latched guard.
        try(var open=open(h);var guard=guard(h,open,time)){
            assertEquals("LEASE_EXPIRED",guard.get(launch).stopReason());h.backend.unknown=false;
            assertEquals(ExecutionAttempt.Phase.RELEASED,guard.poll(launch,open.controller).attempt().orElseThrow().phase());
            assertEquals(1,open.queue.get().retained().size());
        }
    }
    @Test void realProgressExtendsIdleWindowButLateProgressCannotReviveIt()throws Exception{
        var h=harness();var time=new Time();
        try(var open=open(h);var guard=guard(h,open,time)){
            var a=prepare(open,POLICY);guard.register(a.launch(),POLICY);open.controller.start("guard");
            time.advance(90);assertEquals("",guard.observe(a.launch(),new DurableAttemptWatchdog.Observation(1,1,0,1)).stopReason());
            time.advance(90);assertEquals("",guard.observe(a.launch(),new DurableAttemptWatchdog.Observation(1,2,1,0)).stopReason());
            assertEquals(1,guard.get(a.launch()).counters().diskBytes()); // Shrinking the overlay does not erase its high water.
            time.advance(10);assertEquals("IDLE_EXPIRED",guard.observe(a.launch(),new DurableAttemptWatchdog.Observation(2,3,2,1)).stopReason());
            assertThrows(IOException.class,()->guard.authority(a.launch()).require("guard",1));
        }
    }
    @Test void resourceBoundsRegressionAndClockRollbackHavePersistentExplicitCauses()throws Exception{
        Path base=root;
        for(String cause:List.of("WORK_BUDGET_EXHAUSTED","LOG_BUDGET_EXHAUSTED","DISK_BUDGET_EXHAUSTED","COUNTER_REGRESSION","CLOCK_ROLLBACK")){
            root=base.resolve(cause);var h=harness();var time=new Time();
            try(var open=open(h);var guard=guard(h,open,time)){
                var a=prepare(open,POLICY);guard.register(a.launch(),POLICY);open.controller.start("guard");
                time.advance(1);guard.observe(a.launch(),new DurableAttemptWatchdog.Observation(1,1,1,10));
                var next=switch(cause){
                    case "WORK_BUDGET_EXHAUSTED" -> new DurableAttemptWatchdog.Observation(1,10,1,10);
                    case "LOG_BUDGET_EXHAUSTED" -> new DurableAttemptWatchdog.Observation(1,1,3,10);
                    case "DISK_BUDGET_EXHAUSTED" -> new DurableAttemptWatchdog.Observation(1,1,1,1025);
                    case "COUNTER_REGRESSION" -> new DurableAttemptWatchdog.Observation(0,0,0,0);
                    default -> new DurableAttemptWatchdog.Observation(1,1,1,10);
                };
                if(cause.equals("CLOCK_ROLLBACK"))time.advance(-1);
                assertEquals(cause,guard.observe(a.launch(),next).stopReason());
                assertEquals(cause,guard.observe(a.launch(),new DurableAttemptWatchdog.Observation(2,2,2,20)).stopReason());
            }
        }
    }
    @Test void registrationBindsPolicyResourcesAndPrelaunchFrontier()throws Exception{
        var h=harness();var time=new Time();
        try(var open=open(h);var guard=guard(h,open,time)){
            var a=prepare(open,POLICY);
            assertThrows(IOException.class,()->guard.register(a.launch(),new DurableAttemptWatchdog.Policy(1001,100,10,2,1024)));
            open.controller.start("guard");assertThrows(IOException.class,()->guard.register(a.launch(),POLICY));
        }
    }
}
