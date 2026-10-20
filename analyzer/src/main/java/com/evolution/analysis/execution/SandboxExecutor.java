package com.evolution.analysis.execution;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.*;
import java.io.IOException;
import java.util.*;

/** Out-of-process isolation port. There is deliberately no host shell/command operation. */
public interface SandboxExecutor {
    enum Availability { AVAILABLE, UNAVAILABLE, UNQUALIFIED }
    record Probe(Availability availability,String reason,VersionedIdentifier backend,Optional<ContentDigest> qualification) {
        public Probe {Objects.requireNonNull(availability);Objects.requireNonNull(reason);Objects.requireNonNull(backend);Objects.requireNonNull(qualification);if(availability==Availability.AVAILABLE&&qualification.isEmpty())throw new IllegalArgumentException("QUALIFICATION_REQUIRED");}
    }
    record Launch(ContentDigest plan,ContentDigest task,ContentDigest recipe,ContentDigest profile,ContentHandle immutableInputs,
            ContentDigest policy,String attempt,long fence) {
        public Launch {Objects.requireNonNull(plan);Objects.requireNonNull(task);Objects.requireNonNull(recipe);Objects.requireNonNull(profile);Objects.requireNonNull(immutableInputs);Objects.requireNonNull(policy);ExecutionGraph.text(attempt);if(fence<1)throw new IllegalArgumentException("INVALID_FENCE");}
    }
    record Handle(String attempt,long fence,String backendId) {public Handle {ExecutionGraph.text(attempt);ExecutionGraph.text(backendId);if(fence<1)throw new IllegalArgumentException("INVALID_FENCE");}}
    record Status(Handle handle,boolean running,Optional<ContentDigest> termination,Optional<ContentHandle> export,String reason) {
        public Status {Objects.requireNonNull(handle);Objects.requireNonNull(termination);Objects.requireNonNull(export);Objects.requireNonNull(reason);if(running&&(termination.isPresent()||export.isPresent())||export.isPresent()&&termination.isEmpty())throw new IllegalArgumentException("LIVE_EXPORT_NOT_SEALED");}
    }
    /** Controller-only discovery by the immutable launch namespace, including launch-before-handle crashes.
     * An empty handle without a termination proof means UNKNOWN, never proof of absence.
     */
    record Recovery(Optional<Handle> handle,Optional<ContentDigest> termination,String reason) {
        public Recovery {Objects.requireNonNull(handle);Objects.requireNonNull(termination);Objects.requireNonNull(reason);}
    }
    Probe probe()throws IOException;
    Handle launch(Launch request)throws IOException;
    Status inspect(Handle handle)throws IOException;
    Status terminate(Handle handle)throws IOException;
    Status collect(Handle handle)throws IOException;
    default Recovery recover(Launch request)throws IOException{return new Recovery(Optional.empty(),Optional.empty(),"EXECUTOR_RECOVERY_UNSUPPORTED");}
}
