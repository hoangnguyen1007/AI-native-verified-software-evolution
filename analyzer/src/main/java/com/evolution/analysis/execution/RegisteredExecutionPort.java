package com.evolution.analysis.execution;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.IOException;
import java.util.Objects;

/** Public execution boundary: an operator-registered identity, never a command or host path. */
@FunctionalInterface
public interface RegisteredExecutionPort {
    String VERSION="execution.registered-api:m8.4-v1";
    enum Operation { SUBMIT,STATUS,CANCEL,RECOVER }
    enum Failure { NOT_REGISTERED,NOT_SUBMITTED,SCOPE_CONFLICT }
    final class Rejected extends IOException {
        private final Failure failure;
        public Rejected(Failure failure){super(Objects.requireNonNull(failure).name());this.failure=failure;}
        public Failure failure(){return failure;}
    }
    /** Decimal strings preserve counters above the JavaScript exact-integer boundary. */
    record View(ContentDigest registration,ContentDigest plan,ContentDigest task,String attempt,
            String attemptPhase,ExecutionState.Phase taskPhase,String revision,String fence,
            SandboxExecutor.Availability availability,String reason) {
        public View {
            Objects.requireNonNull(registration);Objects.requireNonNull(plan);Objects.requireNonNull(task);
            ExecutionGraph.text(attempt);ExecutionAttempt.Phase.valueOf(attemptPhase);Objects.requireNonNull(taskPhase);
            if(!Long.toString(Long.parseLong(revision)).equals(revision)||Long.parseLong(revision)<0
                    ||!Long.toString(Long.parseLong(fence)).equals(fence)||Long.parseLong(fence)<1)throw new IllegalArgumentException("INVALID_EXECUTION_COUNTER");
            Objects.requireNonNull(availability);Objects.requireNonNull(reason);
            if(!reason.isEmpty()&&!reason.matches("[A-Z][A-Z0-9_]{0,95}"))throw new IllegalArgumentException("INVALID_PUBLIC_EXECUTION_REASON");
        }
    }
    View execute(Operation operation,ContentDigest registration)throws IOException;
}
