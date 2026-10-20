package com.evolution.analysis.execution;

import com.evolution.analysis.contract.common.ContentDigest;
import java.util.*;

/** Recoverable reservation/lease/launch protocol; operational state, never a semantic acceptance. */
public record ExecutionAttempt(String schema,long revision,SandboxExecutor.Launch launch,AdmissionQueue.Request admission,
        ExecutionState expectedTask,Phase phase,Optional<SandboxExecutor.Handle> handle,Optional<ContentDigest> termination,
        Stop stop,boolean launchPossible,String reason) {
    public enum Phase { PREPARED,QUEUED,RESERVED,LEASED,LAUNCH_INTENT,RUNNING,STOP_INTENT,QUARANTINED,QUIESCENT,RELEASED }
    public enum Stop { NONE,FINISH,CANCEL,RECOVERY }
    public ExecutionAttempt {
        if(!"execution-attempt-state:m8.4-v1".equals(schema)||revision<0)throw new IllegalArgumentException("INVALID_ATTEMPT_STATE");
        Objects.requireNonNull(launch);Objects.requireNonNull(admission);Objects.requireNonNull(expectedTask);Objects.requireNonNull(phase);
        Objects.requireNonNull(handle);Objects.requireNonNull(termination);Objects.requireNonNull(stop);Objects.requireNonNull(reason);
        if(!launch.plan().equals(admission.ticket().execution())||!launch.task().equals(admission.ticket().task())||launch.fence()!=admission.ticket().fence()
                ||!expectedTask.task().equals(launch.task())||launch.fence()!=Math.addExact(expectedTask.fence(),1))throw new IllegalArgumentException("ATTEMPT_SCOPE_MISMATCH");
        expectedTask.lease(expectedTask.revision(),launch.attempt());
        if((phase==Phase.LAUNCH_INTENT||phase==Phase.RUNNING)&&!launchPossible||(phase==Phase.PREPARED||phase==Phase.QUEUED||phase==Phase.RESERVED||phase==Phase.LEASED)&&launchPossible)throw new IllegalArgumentException("INVALID_LAUNCH_FRONTIER");
        if(handle.isPresent()&&(!handle.orElseThrow().attempt().equals(launch.attempt())||handle.orElseThrow().fence()!=launch.fence()))throw new IllegalArgumentException("ATTEMPT_HANDLE_MISMATCH");
        if(phase==Phase.RUNNING&&handle.isEmpty()||(phase==Phase.QUIESCENT||phase==Phase.RELEASED)&&termination.isEmpty())throw new IllegalArgumentException("ATTEMPT_PROOF_REQUIRED");
        if((phase==Phase.STOP_INTENT||phase==Phase.QUIESCENT)&&stop==Stop.NONE)throw new IllegalArgumentException("ATTEMPT_STOP_REASON_REQUIRED");
        if(termination.isPresent()&&phase!=Phase.QUIESCENT&&phase!=Phase.RELEASED)throw new IllegalArgumentException("PREMATURE_TERMINATION");
    }
    public static ExecutionAttempt prepare(SandboxExecutor.Launch launch,AdmissionQueue.Request request,ExecutionState expected){
        return new ExecutionAttempt("execution-attempt-state:m8.4-v1",0,launch,request,expected,Phase.PREPARED,Optional.empty(),Optional.empty(),Stop.NONE,false,"");
    }
    public ExecutionAttempt next(Phase phase,Optional<SandboxExecutor.Handle> handle,Optional<ContentDigest> proof,Stop stop,String reason){
        if(this.phase==Phase.RELEASED)throw new IllegalArgumentException("ATTEMPT_ALREADY_CLOSED");
        boolean allowed=switch(this.phase){
            case PREPARED -> phase==Phase.QUEUED||phase==Phase.STOP_INTENT||phase==Phase.QUARANTINED;
            case QUEUED -> phase==Phase.RESERVED||phase==Phase.STOP_INTENT||phase==Phase.QUARANTINED;
            case RESERVED -> phase==Phase.LEASED||phase==Phase.STOP_INTENT||phase==Phase.QUARANTINED;
            case LEASED -> phase==Phase.LAUNCH_INTENT||phase==Phase.STOP_INTENT||phase==Phase.QUARANTINED;
            case LAUNCH_INTENT -> phase==Phase.RUNNING||phase==Phase.STOP_INTENT||phase==Phase.QUARANTINED;
            case RUNNING -> phase==Phase.STOP_INTENT||phase==Phase.QUARANTINED;
            case STOP_INTENT -> phase==Phase.STOP_INTENT||phase==Phase.QUIESCENT||phase==Phase.QUARANTINED;
            case QUARANTINED -> phase==Phase.STOP_INTENT||phase==Phase.QUARANTINED;
            case QUIESCENT -> phase==Phase.RELEASED;
            case RELEASED -> false;
        };
        if(!allowed)throw new IllegalArgumentException("INVALID_ATTEMPT_TRANSITION");
        if(this.stop!=Stop.NONE&&stop!=this.stop)throw new IllegalArgumentException("ATTEMPT_STOP_CONFLICT");
        if(this.handle.isPresent()&&!this.handle.equals(handle))throw new IllegalArgumentException("ATTEMPT_HANDLE_CONFLICT");
        if(this.termination.isPresent()&&!this.termination.equals(proof))throw new IllegalArgumentException("ATTEMPT_TERMINATION_CONFLICT");
        return new ExecutionAttempt(schema,Math.addExact(revision,1),launch,admission,expectedTask,phase,handle,proof,stop,launchPossible||phase==Phase.LAUNCH_INTENT,reason);
    }
    public void verifySuccessor(ExecutionAttempt next){
        if(!launch.equals(next.launch)||!admission.equals(next.admission)||!expectedTask.equals(next.expectedTask)
                ||!next(next.phase,next.handle,next.termination,next.stop,next.reason).equals(next))throw new IllegalArgumentException("INVALID_ATTEMPT_SUCCESSOR");
    }
}
