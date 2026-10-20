package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.execution.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.execution.ExecutionState.Phase.*;

class DurableExecutionJournalTest {
    @TempDir Path temp;
    static ContentDigest d(String v){return ContentDigest.sha256Utf8(v);}
    @Test void restartRetainsLeaseFenceAndRejectsStalePublication()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));ExecutionState leased;
        try(var journal=new DurableExecutionJournal(temp.resolve("journal"),cas)){
            var initial=journal.create(d("plan"),d("task"));var ready=initial.advance(0,0,READY,"",Optional.empty(),Optional.empty());
            journal.compareAndSet(d("plan"),initial,ready);leased=ready.lease(1,"attempt");journal.compareAndSet(d("plan"),ready,leased);
            assertThrows(IOException.class,()->journal.compareAndSet(d("plan"),initial,ready));
        }
        try(var journal=new DurableExecutionJournal(temp.resolve("journal"),cas)){
            assertEquals(leased,journal.get(d("plan"),d("task")).orElseThrow());assertTrue(leased.reservesWorker());
            assertEquals(leased,journal.create(d("plan"),d("task")));
            var running=leased.advance(2,1,RUNNING,"",Optional.empty(),Optional.empty());journal.compareAndSet(d("plan"),leased,running);
            assertThrows(IOException.class,()->journal.compareAndSet(d("plan"),leased,running));
        }
    }
    @Test void forgedSuccessorAndUnavailablePublicationCannotBeCommitted()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));try(var journal=new DurableExecutionJournal(temp.resolve("journal"),cas)){
            var initial=journal.create(d("p"),d("t"));
            var forged=new ExecutionState(initial.schema(),initial.task(),1,1,SUCCEEDED,Optional.of("fake"),Optional.of(d("termination")),Optional.of(new com.evolution.analysis.acquisition.streaming.ContentHandle(d("absent"),0)),"fake");
            assertThrows(IOException.class,()->journal.compareAndSet(d("p"),initial,forged));assertEquals(initial,journal.get(d("p"),d("t")).orElseThrow());
        }
    }
    @Test void corruptDerivedIndexRebuildsWithoutLosingCancelledState()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));ExecutionState done;
        try(var journal=new DurableExecutionJournal(temp.resolve("journal"),cas)){
            var initial=journal.create(d("p"),d("t"));done=initial.advance(0,0,CANCELLED,"before launch",Optional.empty(),Optional.empty());journal.compareAndSet(d("p"),initial,done);
        }
        Files.writeString(temp.resolve("journal/index.sqlite"),"corrupt");
        try(var journal=new DurableExecutionJournal(temp.resolve("journal"),cas)){
            assertEquals(done,journal.get(d("p"),d("t")).orElseThrow());assertThrows(IllegalArgumentException.class,()->done.lease(1,"late"));
        }
    }
    @Test void reapsFailedWorkerAfterRestartBeforeIssuingNewFence()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));
        try(var journal=new DurableExecutionJournal(temp.resolve("journal"),cas)){
            var initial=journal.create(d("p"),d("t"));var ready=initial.advance(0,0,READY,"",Optional.empty(),Optional.empty());
            journal.compareAndSet(d("p"),initial,ready);var leased=ready.lease(1,"old");journal.compareAndSet(d("p"),ready,leased);
            var failed=leased.advance(2,1,FAILED,"lost",Optional.empty(),Optional.empty());journal.compareAndSet(d("p"),leased,failed);
        }
        try(var journal=new DurableExecutionJournal(temp.resolve("journal"),cas)){
            var failed=journal.get(d("p"),d("t")).orElseThrow();assertTrue(failed.reservesWorker());
            var reaped=failed.advance(3,1,FAILED,"confirmed off",Optional.of(d("termination")),Optional.empty());journal.compareAndSet(d("p"),failed,reaped);
            var next=reaped.lease(4,"new");journal.compareAndSet(d("p"),reaped,next);assertEquals(2,next.fence());
        }
        try(var journal=new DurableExecutionJournal(temp.resolve("journal"),cas)){
            assertEquals("new",journal.get(d("p"),d("t")).orElseThrow().attempt().orElseThrow());
        }
    }

}
