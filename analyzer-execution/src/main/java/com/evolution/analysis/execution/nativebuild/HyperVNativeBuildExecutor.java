package com.evolution.analysis.execution.nativebuild;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.controller.ControllerTerminationLedger;
import com.evolution.analysis.execution.hyperv.HyperVWorkerControl;
import com.evolution.analysis.execution.qualification.SandboxQualificationGate;
import com.evolution.analysis.execution.output.OutputCensus;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Synchronous native task adapter. An independently supervised fresh worker is always fenced
 * Off before captured bytes can be published. Recovery never relaunches a target lifecycle. */
final class HyperVNativeBuildExecutor implements SandboxExecutor {
    private final NativeBuildProfile profile;private final NativeBuildRegistrations.Selected registration;
    private final Path root;private final Launch launch;private final SandboxQualificationGate gate;
    private final SandboxQualificationGate.QualifiedProfile qualification;private final StreamingContentStore content;
    private final BooleanSupplier cancelled;private boolean executed,quiescent;
    HyperVNativeBuildExecutor(NativeBuildProfile profile,NativeBuildRegistrations.Selected registration,Path root,Launch launch,
            SandboxQualificationGate gate,SandboxQualificationGate.QualifiedProfile qualification,StreamingContentStore content,BooleanSupplier cancelled){
        this.profile=profile;this.registration=registration;this.root=root;this.launch=launch;this.gate=gate;this.qualification=qualification;this.content=content;this.cancelled=cancelled;
    }
    boolean executed(){return executed;}boolean requested(){return Files.exists(root.resolve("target-requested.json"),LinkOption.NOFOLLOW_LINKS);}boolean quiescent(){return quiescent;}
    public Probe probe()throws IOException{
        NativeBuildRegistrations.unchanged(registration);
        if(!gate.admissible(qualification))return new Probe(Availability.UNQUALIFIED,"CURRENT_SANDBOX_QUALIFICATION_REQUIRED",NativeBuildCoordinator.PROVIDER,Optional.empty());
        NativeBuildFiles.require(profile.python());NativeBuildFiles.require(profile.powershell());
        return new Probe(Availability.AVAILABLE,"",NativeBuildCoordinator.PROVIDER,qualification.descriptor().qualification());
    }
    private HyperVWorkerControl control(ControllerTerminationLedger ledger)throws IOException{
        Path script=root.resolve("tools/hyperv/worker.ps1");return new HyperVWorkerControl(Path.of(profile.powershell().path()),script,ContentDigest.sha256(NativeBuildFiles.resource("hyperv/worker.ps1")),root,launch,content,ledger);
    }
    public Handle launch(Launch request)throws IOException{
        if(!request.equals(launch)||!launch.profile().equals(qualification.descriptor().identity()))throw new IOException("NATIVE_LAUNCH_SCOPE_MISMATCH");
        if(probe().availability()!=Availability.AVAILABLE)throw new IOException("CURRENT_SANDBOX_QUALIFICATION_REQUIRED");
        NativeBuildCoordinator.check(cancelled);Process process=null;IOException failure=null;
        try(var ledger=new ControllerTerminationLedger(root.resolve("terminations"),content,NativeBuildCoordinator.PROVIDER)){
            try{
                if(!gate.admissible(qualification))throw new IOException("CURRENT_SANDBOX_QUALIFICATION_REQUIRED");
                var builder=new ProcessBuilder(profile.python().path(),"-I",root.resolve("tools/nativebuild/controller.py").toString(),"--job",root.toString(),"--request-pin",NativeBuildFiles.hash(root.resolve("request.json")).digest().value(),"--controller-pid",Long.toString(ProcessHandle.current().pid()));
                NativeBuildFiles.controllerEnvironment(builder);
                builder.directory(root.toFile()).redirectOutput(root.resolve("controller.private.log").toFile()).redirectError(root.resolve("controller-error.private.log").toFile());
                process=builder.start();long allowance=Math.addExact(profile.resources().runtimeMillis(),Math.multiplyExact(profile.resources().controlMillis(),8));long end=System.nanoTime()+Math.multiplyExact(allowance,1_000_000L);
                while(!process.waitFor(100,java.util.concurrent.TimeUnit.MILLISECONDS)){
                    NativeBuildCoordinator.check(cancelled);if(!gate.admissible(qualification))throw new IOException("CURRENT_SANDBOX_QUALIFICATION_REQUIRED");
                    if(System.nanoTime()>end)throw new IOException("NATIVE_CONTROL_WATCHDOG");
                }
                if(process.exitValue()!=0){String reason="NATIVE_CONTROLLER_FAILED";if(Files.exists(root.resolve("failure.json"),LinkOption.NOFOLLOW_LINKS)){var failed=NativeBuildFiles.read(root.resolve("failure.json"),16384);Object code=failed.get("reason");if(code instanceof String value&&value.matches("(?:NATIVE|REGISTERED)_[A-Z0-9_]{1,80}"))reason=value;}throw new IOException(reason);}
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();failure=new IOException("NATIVE_BUILD_CANCELLED",interrupted);}
            catch(java.util.concurrent.CancellationException stopped){failure=new IOException("NATIVE_BUILD_CANCELLED",stopped);}
            catch(IOException failed){failure=failed;}
            finally{
                if(process!=null&&process.isAlive())process.destroyForcibly();
                // Quiescence is independently observed through the existing authoritative driver.
                boolean interrupted=Thread.interrupted();
                try{var recovery=control(ledger).recover(launch);Optional<ContentDigest> proof=recovery.termination();if(recovery.handle().isPresent()){var off=control(ledger).terminate(recovery.handle().orElseThrow());quiescent=!off.running()&&off.termination().isPresent();proof=off.termination();}else quiescent=proof.isPresent();if(quiescent){if(!Files.exists(root.resolve("quiescent.json")))NativeBuildFiles.write(root.resolve("quiescent.json"),Map.of("schema","native-build-quiescence:m8.4-v1","launch",CanonicalJson.sha256(launch),"termination",proof.orElseThrow()));if(!Files.exists(root.resolve("controller-protocol-complete.json")))NativeBuildFiles.write(root.resolve("controller-protocol-complete.json"),Map.of("attempt",launch.attempt(),"fence",launch.fence(),"launch",CanonicalJson.sha256(launch)));}}
                catch(IOException stop){if(failure==null)failure=stop;else failure.addSuppressed(stop);}
                finally{if(interrupted)Thread.currentThread().interrupt();}
                executed=Files.exists(root.resolve("native-result.json"),LinkOption.NOFOLLOW_LINKS);
            }
            if(failure!=null)throw failure;if(!quiescent)throw new IOException("NATIVE_TERMINATION_UNCONFIRMED");
            var owner=NativeBuildFiles.read(root.resolve("owner-"+launch.attempt().replace("-","")+".json"),65536);
            return new Handle(launch.attempt(),launch.fence(),GraphJson.string(owner.get("vmId")));
        }
    }
    public Status inspect(Handle handle)throws IOException{try(var ledger=new ControllerTerminationLedger(root.resolve("terminations"),content,NativeBuildCoordinator.PROVIDER)){return control(ledger).inspect(handle);}}
    public Status terminate(Handle handle)throws IOException{try(var ledger=new ControllerTerminationLedger(root.resolve("terminations"),content,NativeBuildCoordinator.PROVIDER)){return control(ledger).terminate(handle);}}
    public Recovery recover(Launch request)throws IOException{if(!request.equals(launch))throw new IOException("NATIVE_LAUNCH_SCOPE_MISMATCH");try(var ledger=new ControllerTerminationLedger(root.resolve("terminations"),content,NativeBuildCoordinator.PROVIDER)){return control(ledger).recover(request);}}
    public Status collect(Handle handle)throws IOException{
        var status=inspect(handle);if(status.running()||status.termination().isEmpty())throw new IOException("NATIVE_TERMINATION_UNCONFIRMED");
        NativeBuildRegistrations.unchanged(registration);Path capture=root.resolve("capture");var resources=profile.resources();
        var export=NativeBuildFiles.read(root.resolve("export-request.json"),65536);var request=GraphJson.object(export.get("request"));
        var scope=new OutputCensus.Scope(launch.attempt().replace("-",""),GraphJson.string(request.get("nonce")),new OutputCensus.Budget(resources.maxRowBytes(),resources.maxObjectBytes(),resources.maxCaptureBytes()));
        var census=new OutputCensus(content);ContentHandle header;
        var rawHeader=NativeBuildFiles.read(capture.resolve("collector-result.json"),8192);
        for(String field:List.of("scope","manifest")){
            var expected=NativeBuildFiles.handle(rawHeader.get(field));Path file=capture.resolve(field.equals("scope")?"scope.json":"entries.ndjson");
            try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){if(!content.put(input,cancelled).equals(expected))throw new IOException("NATIVE_CENSUS_BYTES_CHANGED");}
        }
        try(var input=Files.newInputStream(capture.resolve("collector-result.json"),LinkOption.NOFOLLOW_LINKS)){header=content.put(input,cancelled);}
        var metadata=census.header(header,scope);
        var verified=census.verify(metadata,h->{Path path=capture.resolve("objects").resolve(h.digest().value().substring(7));NativeBuildFiles.safe(path.toString());return Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS);},row->{},cancelled);
        if(verified.decision()!=OutputCensus.Decision.CAPTURED)throw new IOException("NATIVE_OUTPUT_CAPTURE_PENDING");
        ContentHandle observation;try(var input=Files.newInputStream(root.resolve("native-result.json"),LinkOption.NOFOLLOW_LINKS)){observation=content.put(input,cancelled);}
        var result=NativeBuildFiles.read(root.resolve("native-result.json"),1048576);
        if(!"native-build-execution:m8.4-v1".equals(result.get("schema"))||!launch.attempt().equals(result.get("attempt"))||!CanonicalJson.sha256(launch).value().equals(result.get("launch")))throw new IOException("NATIVE_RESULT_SCOPE_MISMATCH");
        var receipt=Map.of("schema","native-build-captured-result:m8.4-v1","launch",launch,"nativeObservation",observation,"census",header,"termination",status.termination().orElseThrow(),"semanticTrust","PRODUCER_ASSERTION","semanticValidated",false);
        var document=content.put(new ByteArrayInputStream(CanonicalJson.write(receipt).getBytes(StandardCharsets.UTF_8)),cancelled);NativeBuildFiles.write(root.resolve("publication.json"),Map.of("document",document));
        return new Status(handle,false,status.termination(),Optional.of(document),"");
    }
}
