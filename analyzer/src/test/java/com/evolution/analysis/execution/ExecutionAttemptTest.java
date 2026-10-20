package com.evolution.analysis.execution;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionAttemptTest {
    static ContentDigest d(String s){return ContentDigest.sha256Utf8(s);}
    static ExecutionAttempt prepared(){
        var task=ExecutionState.initial(d("task")).advance(0,0,ExecutionState.Phase.READY,"",Optional.empty(),Optional.empty());
        var launch=new SandboxExecutor.Launch(d("plan"),task.task(),d("recipe"),d("profile"),new ContentHandle(d("inputs"),1),d("policy"),"owned-attempt",1);
        var request=new AdmissionQueue.Request(new AdmissionQueue.Ticket(launch.plan(),launch.task(),1),d("job"),new ResourceBudget.Resources(1,1,1,1,1,1),0);
        return ExecutionAttempt.prepare(launch,request,task);
    }
    @Test void noLaunchOrReleaseBeforeDurableProtocolAndQuiescence(){
        var a=prepared();var handle=Optional.of(new SandboxExecutor.Handle(a.launch().attempt(),1,"vm"));
        assertThrows(IllegalArgumentException.class,()->a.next(ExecutionAttempt.Phase.RUNNING,handle,Optional.empty(),ExecutionAttempt.Stop.NONE,""));
        assertThrows(IllegalArgumentException.class,()->a.next(ExecutionAttempt.Phase.RELEASED,Optional.empty(),Optional.empty(),ExecutionAttempt.Stop.NONE,""));
        assertEquals(a,ExecutionCodec.read(CanonicalJson.write(a),ExecutionAttempt.class));
    }
    @Test void stopWinnerAndExactHandleArePreserved(){
        var a=prepared().next(ExecutionAttempt.Phase.STOP_INTENT,Optional.empty(),Optional.empty(),ExecutionAttempt.Stop.CANCEL,"");
        assertThrows(IllegalArgumentException.class,()->a.next(ExecutionAttempt.Phase.QUIESCENT,Optional.empty(),Optional.of(d("off")),ExecutionAttempt.Stop.FINISH,""));
        assertThrows(IllegalArgumentException.class,()->a.next(ExecutionAttempt.Phase.QUIESCENT,Optional.of(new SandboxExecutor.Handle("foreign",1,"vm")),Optional.of(d("off")),ExecutionAttempt.Stop.CANCEL,""));
    }
}
