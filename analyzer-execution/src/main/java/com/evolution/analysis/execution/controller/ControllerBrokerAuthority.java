package com.evolution.analysis.execution.controller;

import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.broker.ArtifactBroker;
import java.io.IOException;
import java.util.*;

/** Exact-launch authority backed by controller journals, never by a guest heartbeat.
 * A committed stop/quarantine or a lost task fence closes grants before VM termination.
 * Recreating a broker after restart cannot reactivate a stopped attempt. This is not a
 * persistent grant issuer or a substitute for lease/watchdog and byte-budget enforcement.
 */
public final class ControllerBrokerAuthority implements ArtifactBroker.Authority {
    private final DurableAttemptStore attempts;
    private final ExecutionJournal tasks;
    private final SandboxExecutor.Launch binding;

    public ControllerBrokerAuthority(DurableAttemptStore attempts,ExecutionJournal tasks,SandboxExecutor.Launch binding){
        this.attempts=Objects.requireNonNull(attempts);this.tasks=Objects.requireNonNull(tasks);this.binding=Objects.requireNonNull(binding);
    }
    @Override public void require(String attempt,long fence)throws IOException{
        if(!binding.attempt().equals(attempt)||binding.fence()!=fence)throw new IOException("BROKER_ATTEMPT_SCOPE_MISMATCH");
        var a=attempts.get(attempt);
        if(!a.launch().equals(binding)||a.stop()!=ExecutionAttempt.Stop.NONE
                ||a.phase()!=ExecutionAttempt.Phase.LAUNCH_INTENT&&a.phase()!=ExecutionAttempt.Phase.RUNNING)
            throw new IOException("BROKER_ATTEMPT_NOT_ACTIVE");
        var task=tasks.get(binding.plan(),binding.task()).orElseThrow(()->new IOException("BROKER_TASK_MISSING"));
        if(task.fence()!=fence||!task.attempt().equals(Optional.of(attempt))
                ||task.phase()!=ExecutionState.Phase.LEASED&&task.phase()!=ExecutionState.Phase.RUNNING)
            throw new IOException("BROKER_TASK_NOT_OWNED");
    }
}
