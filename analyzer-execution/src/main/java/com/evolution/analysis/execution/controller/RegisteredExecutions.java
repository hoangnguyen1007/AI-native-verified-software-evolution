package com.evolution.analysis.execution.controller;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import java.io.IOException;
import java.util.*;

/** Operator-composed registrations backed by the existing durable controller, never producer JSON.
 * Reconstructing the same catalog after restart addresses the same durable attempt and queue ticket.
 */
public final class RegisteredExecutions implements RegisteredExecutionPort {
    public record Registration(SandboxExecutor.Launch launch,AdmissionQueue.Request admission,
            ExecutionState expected,ExecutionController controller){
        public Registration {Objects.requireNonNull(controller);ExecutionAttempt.prepare(launch,admission,expected);}
        public ContentDigest identity(){return CanonicalJson.sha256(Map.of("schema","execution-registration:m8.4-v1",
                "launch",launch,"admission",admission,"expected",expected));}
    }
    private final Map<ContentDigest,Registration> registrations;
    public RegisteredExecutions(Collection<Registration> configured){
        var registrations=new HashMap<ContentDigest,Registration>();var namespaces=new HashSet<String>();
        for(var item:configured){
            if(registrations.putIfAbsent(item.identity(),item)!=null||!namespaces.add(item.launch().attempt()))throw new IllegalArgumentException("DUPLICATE_EXECUTION_REGISTRATION");
        }
        this.registrations=Map.copyOf(registrations);
    }
    @Override public View execute(Operation operation,ContentDigest id)throws IOException{
        Objects.requireNonNull(operation);Objects.requireNonNull(id);
        var registration=registrations.get(id);
        if(registration==null)throw new Rejected(Failure.NOT_REGISTERED);
        var controller=registration.controller();var launch=registration.launch();
        var snapshot=controller.snapshot(launch);
        if(operation==Operation.SUBMIT){
            var attempt=controller.enqueue(launch,registration.admission(),registration.expected());
            // Waiting for qualification is not a launch intent and consumes no worker lease.
            if(Set.of(ExecutionAttempt.Phase.QUEUED,ExecutionAttempt.Phase.RESERVED,ExecutionAttempt.Phase.LEASED).contains(attempt.phase())
                    &&controller.availability()==SandboxExecutor.Availability.AVAILABLE)controller.start(launch.attempt());
        }else{
            if(snapshot.attempt().isEmpty())throw new Rejected(Failure.NOT_SUBMITTED);
            if(operation==Operation.CANCEL)controller.cancel(launch.attempt());
            if(operation==Operation.RECOVER)controller.recover(launch);
        }
        if(operation!=Operation.STATUS)snapshot=controller.snapshot(launch);
        var availability=controller.availability();
        var attempt=snapshot.attempt().orElseThrow(()->new IOException("ATTEMPT_NOT_FOUND"));
        String reason=attempt.reason();
        if(reason.isEmpty())reason=snapshot.task().reason();
        if(reason.isEmpty()&&attempt.phase()==ExecutionAttempt.Phase.QUEUED&&availability!=SandboxExecutor.Availability.AVAILABLE)
            reason=availability==SandboxExecutor.Availability.UNAVAILABLE?"EXECUTOR_UNAVAILABLE":"CURRENT_SANDBOX_QUALIFICATION_REQUIRED";
        if(!reason.isEmpty()&&!reason.matches("[A-Z][A-Z0-9_]{0,95}"))reason="EXECUTION_OPERATION_FAILED";
        return new View(id,launch.plan(),launch.task(),launch.attempt(),attempt.phase().name(),snapshot.task().phase(),
                Long.toString(attempt.revision()),Long.toString(launch.fence()),availability,reason);
    }
}
