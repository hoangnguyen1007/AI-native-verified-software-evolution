package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.execution.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DurableAdmissionQueueTest {
    @TempDir Path root;
    static ContentDigest d(String s){return ContentDigest.sha256Utf8(s);}
    static ResourceBudget.Resources capacity(){return new ResourceBudget.Resources(10,10,10,10,10,10);}
    static AdmissionQueue.Request request(String job,String task){return new AdmissionQueue.Request(new AdmissionQueue.Ticket(d(job),d(task),1),d(job),new ResourceBudget.Resources(0,10,0,1,0,0),0);}
    @Test void restartRetainsReservationsBeforeLaunchAndTheFairRotationCursor()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));var a=request("a","one");var a2=request("a","two");var b=request("b","one");
        try(var queue=new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),2,100000)){
            queue.submit(0,a);queue.submit(1,a2);queue.submit(2,b);assertEquals(List.of(a.ticket()),queue.dispatch(3).admitted());
            assertThrows(IOException.class,()->new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),2,100000));
        }
        try(var queue=new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),2,100000)){
            var state=queue.get();assertEquals(a, state.active().getFirst().request());assertTrue(queue.dispatch(state.revision()).admitted().isEmpty());
            queue.release(state.revision(),a.ticket(),Optional.of(d("off")));
            assertEquals(List.of(b.ticket()),queue.dispatch(queue.get().revision()).admitted());
        }
    }
    @Test void staleSubmissionAndUnknownTerminationCannotFreeCapacityOrLosePendingRows()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));var a=request("a","one");
        try(var queue=new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),1,100000)){
            queue.submit(0,a);assertThrows(IOException.class,()->queue.submit(0,request("b","one")));queue.dispatch(1);
            assertThrows(IOException.class,()->queue.release(2,a.ticket(),Optional.empty()));assertEquals(1,queue.get().active().size());
        }
        try(var queue=new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),1,100000)){assertEquals(1,queue.get().active().size());assertTrue(queue.get().pending().isEmpty());}
    }
    @Test void corruptDerivedIndexRebuildsAndPolicyMismatchCannotResetReservation()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));
        try(var queue=new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),1,100000)){queue.submit(0,request("a","one"));queue.dispatch(1);}
        Files.writeString(root.resolve("journal/index.sqlite"),"corrupt");
        assertThrows(IOException.class,()->new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),2,100000));
        try(var queue=new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),1,100000)){assertEquals(1,queue.get().active().size());}
    }
    @Test void insufficientDecodeWorkerRetainsTheEntireQueueForLargerWorker()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));
        try(var queue=new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),1,100000)){
            for(int i=0;i<8;i++)queue.submit(i,request("job","task"+i));
        }
        assertThrows(IOException.class,()->new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),1,100));
        try(var queue=new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),1,100000)){assertEquals(8,queue.get().pending().size());}
    }
    @Test void restartCannotResetAgingToStarveLowPriorityWork()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));var low=request("job","old-low");
        var base=request("job","high");var high=new AdmissionQueue.Request(base.ticket(),base.job(),base.resources(),100);
        try(var queue=new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),1,100000)){
            queue.submit(0,low);queue.submit(1,high);
            assertEquals(List.of(high.ticket()),queue.dispatch(2).admitted());
        }
        try(var queue=new DurableAdmissionQueue(root.resolve("journal"),cas,capacity(),1,100000)){
            assertEquals(1,queue.get().pending().getFirst().bypasses());
            queue.release(queue.get().revision(),high.ticket(),Optional.of(d("off-high")));
            var next=request("job","newer-high");
            queue.submit(queue.get().revision(),new AdmissionQueue.Request(next.ticket(),next.job(),next.resources(),100));
            assertEquals(List.of(low.ticket()),queue.dispatch(queue.get().revision()).admitted());
        }
    }
}
