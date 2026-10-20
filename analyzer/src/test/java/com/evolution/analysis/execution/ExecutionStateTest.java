package com.evolution.analysis.execution;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.execution.ExecutionState.Phase.*;
import static com.evolution.analysis.execution.ExecutionGraphTest.*;

class ExecutionStateTest {
    static ExecutionState ready(){return ExecutionState.initial(digest("task")).advance(0,0,READY,"",Optional.empty(),Optional.empty());}
    @Test void leaseAndCancellationRequireFencingAndQuiescence(){
        var leased=ready().lease(1,"attempt");assertEquals(1,leased.fence());assertTrue(leased.reservesWorker());
        var running=leased.advance(2,1,RUNNING,"",Optional.empty(),Optional.empty());
        assertThrows(IllegalArgumentException.class,()->running.advance(3,1,COLLECTING,"",Optional.empty(),Optional.empty()));
        var cancelled=running.advance(3,1,CANCEL_REQUESTED,"user",Optional.empty(),Optional.empty());
        assertThrows(IllegalArgumentException.class,()->cancelled.advance(4,1,CANCELLED,"",Optional.empty(),Optional.empty()));
        var done=cancelled.advance(4,1,CANCELLED,"",Optional.of(digest("termination")),Optional.empty());assertFalse(done.reservesWorker());
        assertThrows(IllegalArgumentException.class,()->done.advance(5,1,SUCCEEDED,"late",Optional.of(digest("termination")),Optional.of(new ContentHandle(digest("output"),0))));
    }
    @Test void publicationIsNotPossibleBeforeValidationAndTermination(){
        var running=ready().lease(1,"a").advance(2,1,RUNNING,"",Optional.empty(),Optional.empty());
        var collecting=running.advance(3,1,COLLECTING,"",Optional.of(digest("proof")),Optional.empty());
        var validating=collecting.advance(4,1,VALIDATING,"",Optional.empty(),Optional.empty());
        assertThrows(IllegalArgumentException.class,()->validating.advance(5,1,SUCCEEDED,"",Optional.empty(),Optional.empty()));
        var success=validating.advance(5,1,SUCCEEDED,"validated",Optional.empty(),Optional.of(new ContentHandle(digest("decision"),0)));
        assertFalse(success.reservesWorker());assertEquals(SUCCEEDED,success.phase());
    }
    @Test void failedAttemptCannotRetryUntilItsWorkerIsTerminated(){
        var leased=ready().lease(1,"a");var failed=leased.advance(2,1,FAILED,"lost",Optional.empty(),Optional.empty());
        assertThrows(IllegalArgumentException.class,()->failed.lease(3,"b"));
        var terminated=leased.advance(2,1,FAILED,"exited",Optional.of(digest("proof")),Optional.empty());
        assertEquals(2,terminated.lease(3,"b").fence());assertThrows(IllegalArgumentException.class,()->terminated.lease(2,"stale"));
    }
    @Test void zeroFenceCancellationAndResourceOverflowAreExplicit(){
        assertEquals(CANCELLED,ExecutionState.initial(digest("task")).advance(0,0,CANCELLED,"cancelled before launch",Optional.empty(),Optional.empty()).phase());
        var capacity=new ResourceBudget.Resources(Long.MAX_VALUE,10,10,10,10,10);var budget=new ResourceBudget(capacity);
        assertTrue(budget.reserve("a",new ResourceBudget.Resources(Long.MAX_VALUE,1,1,1,1,1)));
        assertFalse(budget.reserve("b",new ResourceBudget.Resources(1,1,1,1,1,1)));budget.release("a");assertTrue(budget.reserve("b",new ResourceBudget.Resources(1,1,1,1,1,1)));
    }
    @Test void lostFailedWorkerCanBeReapedDurablyBeforeRetryWithoutLateSuccess(){
        var running=ready().lease(1,"attempt-a").advance(2,1,RUNNING,"",Optional.empty(),Optional.empty());
        var failed=running.advance(3,1,FAILED,"controller lost contact",Optional.empty(),Optional.empty());
        assertTrue(failed.reservesWorker());
        var reaped=failed.advance(4,1,FAILED,"worker confirmed off",Optional.of(digest("termination")),Optional.empty());
        failed.verifySuccessor(reaped);
        assertFalse(reaped.reservesWorker());assertEquals(2,reaped.lease(5,"attempt-b").fence());
        assertThrows(IllegalArgumentException.class,()->reaped.advance(5,1,FAILED,"repeated",Optional.of(digest("termination")),Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->reaped.advance(5,1,SUCCEEDED,"late",Optional.empty(),Optional.of(new ContentHandle(digest("out"),0))));
    }

}
