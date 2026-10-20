package com.evolution.analysis.execution.qualification;

import com.evolution.analysis.execution.SandboxExecutor;
import java.io.IOException;
import java.util.*;

/** Admission decorator for a registered backend. It exposes no host execution fallback.
 * Revocation blocks new launches, never inspection or termination of already owned workers.
 * The underlying driver must enforce ownership/fencing, and cannot be supplied by target data.
 */
public final class QualifiedSandboxExecutor implements SandboxExecutor {
    private final SandboxExecutor driver;
    private final SandboxQualificationGate gate;
    private final Optional<SandboxQualificationGate.QualifiedProfile> qualification;

    public QualifiedSandboxExecutor(SandboxExecutor driver,SandboxQualificationGate gate,
            Optional<SandboxQualificationGate.QualifiedProfile> qualification){
        this.driver=Objects.requireNonNull(driver);
        this.gate=Objects.requireNonNull(gate);
        this.qualification=Objects.requireNonNull(qualification);
    }

    @Override public Probe probe()throws IOException {
        var observed=driver.probe();
        if(observed.availability()==Availability.UNAVAILABLE)return observed;
        if(qualification.isEmpty()||!gate.admissible(qualification.orElseThrow()))
            return new Probe(Availability.UNQUALIFIED,"CURRENT_SANDBOX_QUALIFICATION_REQUIRED",observed.backend(),Optional.empty());
        var profile=qualification.orElseThrow().descriptor();
        if(!observed.backend().equals(profile.backend())||!observed.qualification().equals(profile.qualification()))
            return new Probe(Availability.UNQUALIFIED,"BACKEND_QUALIFICATION_MISMATCH",observed.backend(),Optional.empty());
        return observed;
    }

    @Override public Handle launch(Launch request)throws IOException {
        Objects.requireNonNull(request);
        if(probe().availability()!=Availability.AVAILABLE)throw new IOException("SANDBOX_NOT_QUALIFIED");
        var token=qualification.orElseThrow();
        if(!request.profile().equals(token.descriptor().identity()))throw new IOException("LAUNCH_PROFILE_MISMATCH");
        // Recheck after the backend probe, which may perform an operational remote request.
        if(!gate.admissible(token))throw new IOException("CURRENT_SANDBOX_QUALIFICATION_REQUIRED");
        return driver.launch(request);
    }

    @Override public Status inspect(Handle handle)throws IOException{return driver.inspect(handle);}
    @Override public Status terminate(Handle handle)throws IOException{return driver.terminate(handle);}
    @Override public Status collect(Handle handle)throws IOException{return driver.collect(handle);}
    @Override public Recovery recover(Launch request)throws IOException{return driver.recover(request);}
}
