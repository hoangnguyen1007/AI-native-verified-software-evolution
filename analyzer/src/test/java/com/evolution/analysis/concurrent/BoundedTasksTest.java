package com.evolution.analysis.concurrent;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedTasksTest {
    @Test void boundedJobsOverlapRetainInputOrderAndRefuseUnboundedAdmission() {
        var gate=new CountDownLatch(2);
        try(var work=new BoundedTasks(2,2,"fixture-workers",()->false)) {
            var first=work.submit(()->{gate.countDown();assertTrue(gate.await(2,TimeUnit.SECONDS));return "first";});
            var second=work.submit(()->{gate.countDown();assertTrue(gate.await(2,TimeUnit.SECONDS));return "second";});
            assertThrows(IllegalStateException.class,()->work.submit(()->"overflow"));
            assertEquals("first",first.await());assertEquals("second",second.await());
        }
    }
    @Test void firstAwaitedFailureRetainsItsCauseAndCloseDrainsCancelledPeers()throws Exception {
        var entered=new CountDownLatch(1);var stopped=new AtomicBoolean();var cause=new IOException("FIXTURE_IO");
        try(var work=new BoundedTasks(2,2,"fixture-failure",()->false)) {
            var first=work.submit(()->{assertTrue(entered.await(2,TimeUnit.SECONDS));throw cause;});
            work.submit(()->{entered.countDown();try{new CountDownLatch(1).await();}finally{stopped.set(true);}return "unused";});
            assertSame(cause,assertThrows(UncheckedIOException.class,first::await).getCause());
        }
        assertTrue(stopped.get(),"No worker can outlive the scratch storage scope");
    }
    @Test void cancellationDoesNotStartWorkAndInterruptStatusSurvivesClose() {
        var ran=new AtomicBoolean();
        try(var work=new BoundedTasks(1,1,"cancelled",()->true)){assertThrows(CancellationException.class,()->work.submit(()->{ran.set(true);return 1;}));}
        assertFalse(ran.get());
        try {try(var work=new BoundedTasks(2,2,"interrupt",()->false)){Thread.currentThread().interrupt();}}
        finally {assertTrue(Thread.interrupted());}
    }
}
