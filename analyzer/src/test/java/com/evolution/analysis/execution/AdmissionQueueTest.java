package com.evolution.analysis.execution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionQueueTest {
    @Test void retainedStorageWaiterDoesNotBecomeAnUndrainableAgingBarrier(){
        var a=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("a"),d("task"),1),d("a"),new ResourceBudget.Resources(1,10,10,1,0,0),0);
        var state=AdmissionQueue.dispatch(AdmissionQueue.submit(initial(1),a)).state();
        state=AdmissionQueue.release(state,a.ticket(),Optional.of(d("off-a")));
        var blocked=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("blocked"),d("task"),1),d("blocked"),new ResourceBudget.Resources(1,1,1,1,0,0),0);
        state=AdmissionQueue.submit(state,blocked);
        for(int i=0;i<3;i++){
            var compute=request("compute","task-"+i,10);
            var chosen=AdmissionQueue.dispatch(AdmissionQueue.submit(state,compute));
            assertEquals(List.of(compute.ticket()),chosen.admitted());
            assertEquals("WAITING_FOR_RETAINED_STORAGE",chosen.waiting().getFirst().reason());
            state=AdmissionQueue.release(chosen.state(),compute.ticket(),Optional.of(d("off-"+i)));
        }
    }
    @Test void quiescentWorkersReleaseComputeButRetainedStorageStillLimitsAdmission(){
        var a=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("a"),d("task"),1),d("a"),new ResourceBudget.Resources(2,10,6,1,2,0),0);
        var b=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("b"),d("task"),1),d("b"),new ResourceBudget.Resources(2,5,4,1,1,0),0);
        var c=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("c"),d("task"),1),d("b"),new ResourceBudget.Resources(2,5,1,1,1,0),0);
        var state=AdmissionQueue.dispatch(AdmissionQueue.submit(initial(2),a)).state();
        state=AdmissionQueue.release(state,a.ticket(),Optional.of(d("off-a")));
        state=AdmissionQueue.submit(AdmissionQueue.submit(state,b),c);
        var result=AdmissionQueue.dispatch(state);
        assertEquals(List.of(b.ticket()),result.admitted());
        assertEquals(List.of(c.ticket()),result.state().pending().stream().map(q->q.request().ticket()).toList());
        var replay=ExecutionCodec.read(CanonicalJson.write(result.state()),AdmissionQueue.State.class);
        assertTrue(AdmissionQueue.dispatch(replay).admitted().isEmpty());
    }
    @Test void retainedStorageSurvivesRetryWatermarksAndDuplicateLateRelease(){
        var first=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("job"),d("task"),1),d("job"),new ResourceBudget.Resources(1,10,4,1,2,0),0);
        var state=AdmissionQueue.dispatch(AdmissionQueue.submit(initial(2),first)).state();
        state=AdmissionQueue.release(state,first.ticket(),Optional.of(d("off-1")));
        assertEquals(new ResourceBudget.Resources(0,0,4,0,2,0),state.retained().getFirst().resources());
        var second=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("job"),d("task"),2),d("job"),first.resources(),0);
        state=AdmissionQueue.dispatch(AdmissionQueue.submit(state,second)).state();
        state=AdmissionQueue.release(state,second.ticket(),Optional.of(d("off-2")));
        assertEquals(2,state.retained().size());assertEquals(1,state.retired().size());
        assertEquals(state,AdmissionQueue.release(state,first.ticket(),Optional.of(d("off-1"))));
        var retained=state;
        assertThrows(IllegalArgumentException.class,()->AdmissionQueue.release(retained,first.ticket(),Optional.of(d("wrong"))));
        assertThrows(IllegalArgumentException.class,()->AdmissionQueue.releaseUnlaunched(retained,first.ticket(),Optional.of(d("off-1"))));
        var tooMuch=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("other"),d("task"),1),d("other"),new ResourceBudget.Resources(1,1,3,1,0,0),0);
        assertTrue(AdmissionQueue.dispatch(AdmissionQueue.submit(state,tooMuch)).admitted().isEmpty());
        var computeOnly=request("fresh","compute",10);
        assertEquals(List.of(computeOnly.ticket()),AdmissionQueue.dispatch(AdmissionQueue.submit(state,computeOnly)).admitted());
    }
    @Test void logCapacityAndLongOverflowRemainAccounted(){
        var capacity=new ResourceBudget.Resources(10,10,Long.MAX_VALUE,10,10,10);
        var first=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("job"),d("task"),1),d("job"),new ResourceBudget.Resources(1,10,Long.MAX_VALUE,1,10,0),0);
        var state=AdmissionQueue.dispatch(AdmissionQueue.submit(AdmissionQueue.initial(capacity,2),first)).state();
        state=AdmissionQueue.release(state,first.ticket(),Optional.of(d("off")));
        var disk=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("disk"),d("task"),1),d("disk"),new ResourceBudget.Resources(1,1,1,1,0,0),0);
        var log=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("log"),d("task"),1),d("log"),new ResourceBudget.Resources(1,1,0,1,1,0),0);
        assertTrue(AdmissionQueue.dispatch(AdmissionQueue.submit(AdmissionQueue.submit(state,disk),log)).admitted().isEmpty());
        assertEquals(state,ExecutionCodec.read(CanonicalJson.write(state),AdmissionQueue.State.class));
    }
    @Test void legacyMigrationDoesNotInventFreedStorageOrAcceptUnknownFields(){
        var a=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("job"),d("task"),1),d("job"),new ResourceBudget.Resources(1,10,4,1,2,0),0);
        var active=AdmissionQueue.dispatch(AdmissionQueue.submit(initial(2),a)).state();
        var legacy=new java.util.TreeMap<>(com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(CanonicalJson.write(active),com.evolution.analysis.graph.GraphJson.Limits.defaults())));
        legacy.remove("retained");legacy.put("schema","execution-admission:m8.4-v1");
        assertEquals(active,ExecutionCodec.read(CanonicalJson.write(legacy),AdmissionQueue.State.class));
        legacy.put("retained",List.of());assertThrows(IllegalArgumentException.class,()->ExecutionCodec.read(CanonicalJson.write(legacy),AdmissionQueue.State.class));
        legacy.remove("retained");legacy.put("active",List.of());legacy.put("retired",List.of(new AdmissionQueue.Retired(a,0,d("old-proof"))));
        assertEquals("ADMISSION_STORAGE_RECONCILIATION_REQUIRED",assertThrows(IllegalArgumentException.class,()->ExecutionCodec.read(CanonicalJson.write(legacy),AdmissionQueue.State.class)).getMessage());
        legacy.put("schema","execution-admission:m8.4-v2");assertThrows(IllegalArgumentException.class,()->ExecutionCodec.read(CanonicalJson.write(legacy),AdmissionQueue.State.class));
    }
    static ContentDigest d(String s){return ContentDigest.sha256Utf8(s);}
    static ResourceBudget.Resources resources(long memory){return new ResourceBudget.Resources(0,memory,0,1,0,0);}
    static AdmissionQueue.Request request(String job,String task,long memory){return new AdmissionQueue.Request(new AdmissionQueue.Ticket(d(job),d(task),1),d(job),resources(memory),0);}
    static AdmissionQueue.State initial(int bypasses){return AdmissionQueue.initial(new ResourceBudget.Resources(10,10,10,10,10,10),bypasses);}
    @Test void rotatesPersistedJobCursorInsteadOfDrainingOneJob(){
        var a1=request("a","a1",10);var a2=request("a","a2",10);var b1=request("b","b1",10);
        var state=AdmissionQueue.submit(AdmissionQueue.submit(AdmissionQueue.submit(initial(2),a1),a2),b1);
        var first=AdmissionQueue.dispatch(state);assertEquals(List.of(a1.ticket()),first.admitted());
        state=AdmissionQueue.release(first.state(),a1.ticket(),Optional.of(d("off-a1")));
        var replay=ExecutionCodec.read(CanonicalJson.write(state),AdmissionQueue.State.class);
        var second=AdmissionQueue.dispatch(replay);assertEquals(List.of(b1.ticket()),second.admitted());assertEquals(1,second.state().active().size());
    }
    @Test void agingLargeTaskReservesDrainingCapacityAgainstEndlessSmallWork(){
        var running=request("old","running",6);var state=AdmissionQueue.dispatch(AdmissionQueue.submit(initial(1),running)).state();
        var large=request("large","large",10);var small=request("small","one",2);var next=request("small","two",2);
        state=AdmissionQueue.submit(AdmissionQueue.submit(AdmissionQueue.submit(state,large),small),next);
        var chosen=AdmissionQueue.dispatch(state);assertEquals(List.of(small.ticket()),chosen.admitted());
        state=AdmissionQueue.release(chosen.state(),running.ticket(),Optional.of(d("off-running")));
        assertTrue(AdmissionQueue.dispatch(state).admitted().isEmpty());
        state=AdmissionQueue.release(state,small.ticket(),Optional.of(d("off-small")));
        assertEquals(List.of(large.ticket()),AdmissionQueue.dispatch(state).admitted());
    }
    @Test void oversizedRequestWaitsWithoutBlockingFittingJobsOrDroppingItsRecord(){
        var large=request("large","oversized",11);var small=request("small","fits",2);
        var result=AdmissionQueue.dispatch(AdmissionQueue.submit(AdmissionQueue.submit(initial(1),large),small));
        assertEquals(List.of(small.ticket()),result.admitted());assertEquals(large,result.state().pending().getFirst().request());
        assertEquals("WORKER_PROFILE_REQUIRED",result.waiting().getFirst().reason());
    }
    @Test void reservationsRequireQuiescenceAndOldFenceCannotReenterQueue(){
        var request=request("job","task",3);var state=AdmissionQueue.dispatch(AdmissionQueue.submit(initial(1),request)).state();
        var active=state;assertThrows(IllegalArgumentException.class,()->AdmissionQueue.release(active,request.ticket(),Optional.empty()));
        state=AdmissionQueue.release(state,request.ticket(),Optional.of(d("proof")));assertEquals(state,AdmissionQueue.release(state,request.ticket(),Optional.of(d("proof"))));
        assertEquals(state,AdmissionQueue.submit(state,request));
        var conflict=new AdmissionQueue.Request(request.ticket(),request.job(),resources(4),0);var retired=state;
        assertThrows(IllegalArgumentException.class,()->AdmissionQueue.submit(retired,conflict));
        var retry=new AdmissionQueue.Request(new AdmissionQueue.Ticket(request.ticket().execution(),request.ticket().task(),2),request.job(),request.resources(),0);
        var retried=AdmissionQueue.submit(state,retry);assertEquals(2,retried.pending().getFirst().request().ticket().fence());
        assertThrows(IllegalArgumentException.class,()->AdmissionQueue.submit(retried,request));
    }
    @Test void idlePollingAndDuplicateSubmissionDoNotCreateJournalChurn(){
        var state=initial(2);assertEquals(state,AdmissionQueue.dispatch(state).state());var request=request("job","task",1);
        state=AdmissionQueue.submit(state,request);assertEquals(state,AdmissionQueue.submit(state,request));
        var result=AdmissionQueue.dispatch(state);assertEquals(result.state(),AdmissionQueue.dispatch(result.state()).state());
        var malformed=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("job"),d("new"),3),d("job"),resources(1),0);
        assertThrows(IllegalArgumentException.class,()->AdmissionQueue.submit(result.state(),malformed));
    }
    @Test void resourceAndSequenceOverflowCannotOverAdmitOrWrap(){
        var capacity=new ResourceBudget.Resources(Long.MAX_VALUE,10,10,10,10,10);
        var first=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("j"),d("max"),1),d("j"),new ResourceBudget.Resources(Long.MAX_VALUE,0,0,1,0,0),0);
        var second=new AdmissionQueue.Request(new AdmissionQueue.Ticket(d("j"),d("next"),1),d("j"),new ResourceBudget.Resources(1,0,0,1,0,0),0);
        var result=AdmissionQueue.dispatch(AdmissionQueue.submit(AdmissionQueue.submit(AdmissionQueue.initial(capacity,2),first),second));
        assertEquals(List.of(first.ticket()),result.admitted());assertEquals(1,result.state().pending().size());
        var exhausted=new AdmissionQueue.State("execution-admission:m8.4-v1",Long.MAX_VALUE,Long.MAX_VALUE,capacity,1,Optional.empty(),List.of(),List.of(),List.of());
        assertThrows(ArithmeticException.class,()->AdmissionQueue.submit(exhausted,request("new","new",1)));
    }
    @Test void priorityIsDeterministicWithinAJobAndPendingCancelCannotReleaseLiveWork(){
        var low=request("job","low",10);var base=request("job","high",10);
        var high=new AdmissionQueue.Request(base.ticket(),base.job(),base.resources(),100);
        var result=AdmissionQueue.dispatch(AdmissionQueue.submit(AdmissionQueue.submit(initial(2),low),high));
        assertEquals(List.of(high.ticket()),result.admitted());
        assertThrows(IllegalArgumentException.class,()->AdmissionQueue.cancelPending(result.state(),high.ticket()));
        var cancelled=AdmissionQueue.cancelPending(result.state(),low.ticket());assertTrue(cancelled.pending().isEmpty());assertEquals(1,cancelled.active().size());
        assertEquals(cancelled,AdmissionQueue.cancelPending(cancelled,low.ticket()));
    }
    @Test void repeatedHigherPriorityArrivalsCannotStarveAnOlderFittingTask(){
        var low=request("job","old-low",10);
        var state=AdmissionQueue.submit(initial(1),low);
        var base=request("job","new-high",10);
        var high=new AdmissionQueue.Request(base.ticket(),base.job(),base.resources(),100);
        var first=AdmissionQueue.dispatch(AdmissionQueue.submit(state,high));
        assertEquals(List.of(high.ticket()),first.admitted());
        state=AdmissionQueue.release(first.state(),high.ticket(),Optional.of(d("off-high")));
        base=request("job","newer-high",10);
        var newer=new AdmissionQueue.Request(base.ticket(),base.job(),base.resources(),100);
        assertEquals(List.of(low.ticket()),AdmissionQueue.dispatch(AdmissionQueue.submit(state,newer)).admitted());
    }
    @Test void saturatedAgeDoesNotOverflowWhenAnotherAgedTaskIsAdmitted(){
        var state=new AdmissionQueue.State("execution-admission:m8.4-v1",3,3,initial(1).capacity(),Integer.MAX_VALUE,Optional.empty(),
            List.of(new AdmissionQueue.Queued(request("job","small",1),1,Integer.MAX_VALUE),new AdmissionQueue.Queued(request("job","large",10),2,Integer.MAX_VALUE)),
            List.of(new AdmissionQueue.Queued(request("old","running",1),0,0)),List.of());
        var result=AdmissionQueue.dispatch(state);
        assertEquals(1,result.admitted().size());
        assertEquals(Integer.MAX_VALUE,result.state().pending().getFirst().bypasses());
    }

}
