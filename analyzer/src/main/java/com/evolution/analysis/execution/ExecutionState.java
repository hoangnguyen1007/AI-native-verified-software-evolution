package com.evolution.analysis.execution;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import java.util.*;

/** Durable task projection; operational state never implies semantic completeness. */
public record ExecutionState(String schema,ContentDigest task,long revision,long fence,Phase phase,
        Optional<String> attempt,Optional<ContentDigest> termination,Optional<ContentHandle> result,String reason) {
    public enum Phase { PENDING, READY, LEASED, RUNNING, COLLECTING, VALIDATING, SUCCEEDED, FAILED, CANCEL_REQUESTED, CANCELLED, WAITING_FOR_INPUT, WAITING_FOR_RESOURCES, QUARANTINED }
    public ExecutionState {
        if(!"execution-state:m8.4-v1".equals(schema))throw new IllegalArgumentException("UNSUPPORTED_SCHEMA");
        Objects.requireNonNull(task);Objects.requireNonNull(phase);Objects.requireNonNull(attempt);Objects.requireNonNull(termination);Objects.requireNonNull(result);Objects.requireNonNull(reason);
        if(revision<0||fence<0)throw new IllegalArgumentException("NEGATIVE_SEQUENCE");attempt.ifPresent(ExecutionGraph::text);
        if(fence==0&&attempt.isPresent()||fence>0&&attempt.isEmpty())throw new IllegalArgumentException("INVALID_ATTEMPT_FENCE");
        if((phase==Phase.SUCCEEDED||phase==Phase.CANCELLED)&&fence>0&&termination.isEmpty())throw new IllegalArgumentException("TERMINATION_UNCONFIRMED");
        if(phase==Phase.SUCCEEDED&&result.isEmpty())throw new IllegalArgumentException("PUBLICATION_REQUIRED");
    }
    public static ExecutionState initial(ContentDigest task){return new ExecutionState("execution-state:m8.4-v1",task,0,0,Phase.PENDING,Optional.empty(),Optional.empty(),Optional.empty(),"");}
    public boolean reservesWorker(){return fence>0&&termination.isEmpty();}
    public ExecutionState lease(long expectedRevision,String attemptId){
        if(revision!=expectedRevision)throw new IllegalArgumentException("STALE_REVISION");
        if(phase!=Phase.READY&&phase!=Phase.FAILED&&phase!=Phase.WAITING_FOR_INPUT&&phase!=Phase.WAITING_FOR_RESOURCES)throw new IllegalArgumentException("NOT_READY");
        if(reservesWorker())throw new IllegalArgumentException("TERMINATION_UNCONFIRMED");
        return new ExecutionState(schema,task,Math.addExact(revision,1),Math.addExact(fence,1),Phase.LEASED,Optional.of(ExecutionGraph.text(attemptId)),Optional.empty(),Optional.empty(),"");
    }
    public ExecutionState advance(long expectedRevision,long expectedFence,Phase next,String reason,Optional<ContentDigest> proof,Optional<ContentHandle> publication){
        if(revision!=expectedRevision||fence!=expectedFence)throw new IllegalArgumentException("STALE_FENCE");
        Objects.requireNonNull(next);Objects.requireNonNull(proof);Objects.requireNonNull(publication);
        boolean allowed=switch(phase){
            case PENDING -> next==Phase.READY||next==Phase.CANCELLED||waiting(next);
            case READY -> waiting(next)||next==Phase.CANCELLED;
            case LEASED -> next==Phase.RUNNING||next==Phase.CANCEL_REQUESTED||next==Phase.QUARANTINED||next==Phase.FAILED;
            case RUNNING -> next==Phase.COLLECTING||next==Phase.CANCEL_REQUESTED||next==Phase.FAILED||next==Phase.QUARANTINED;
            case COLLECTING -> next==Phase.VALIDATING||next==Phase.CANCEL_REQUESTED||next==Phase.FAILED||next==Phase.QUARANTINED;
            case VALIDATING -> next==Phase.SUCCEEDED||next==Phase.FAILED||next==Phase.CANCEL_REQUESTED||next==Phase.QUARANTINED;
            case CANCEL_REQUESTED -> next==Phase.CANCELLED||next==Phase.QUARANTINED;
            case WAITING_FOR_INPUT,WAITING_FOR_RESOURCES -> next==Phase.READY||next==Phase.CANCELLED;
            case QUARANTINED -> next==Phase.FAILED||next==Phase.CANCELLED;
            case FAILED -> next==Phase.FAILED&&fence>0&&termination.isEmpty()&&proof.isPresent();
            case SUCCEEDED,CANCELLED -> false;
        };
        if(!allowed)throw new IllegalArgumentException("INVALID_TRANSITION");
        if(proof.isPresent()&&fence==0)throw new IllegalArgumentException("FOREIGN_TERMINATION");
        var terminationProof=proof.isPresent()?proof:termination;
        if(termination.isPresent()&&proof.isPresent()&&!termination.equals(proof))throw new IllegalArgumentException("CONFLICTING_TERMINATION");
        if((next==Phase.COLLECTING||next==Phase.VALIDATING||next==Phase.SUCCEEDED||next==Phase.CANCELLED)&&fence>0&&terminationProof.isEmpty())throw new IllegalArgumentException("TERMINATION_UNCONFIRMED");
        if(publication.isPresent()&&next!=Phase.SUCCEEDED)throw new IllegalArgumentException("UNVALIDATED_PUBLICATION");
        return new ExecutionState(schema,task,Math.addExact(revision,1),fence,next,attempt,terminationProof,publication,reason);
    }
    private static boolean waiting(Phase phase){return phase==Phase.WAITING_FOR_INPUT||phase==Phase.WAITING_FOR_RESOURCES;}
    public void verifySuccessor(ExecutionState next){
        if(!task.equals(next.task()))throw new IllegalArgumentException("FOREIGN_TASK");
        ExecutionState expected;
        if(next.fence()==Math.addExact(fence,1))expected=lease(revision,next.attempt().orElseThrow());
        else expected=advance(revision,fence,next.phase(),next.reason(),next.termination(),next.result());
        if(!expected.equals(next))throw new IllegalArgumentException("INVALID_SUCCESSOR");
    }
}
