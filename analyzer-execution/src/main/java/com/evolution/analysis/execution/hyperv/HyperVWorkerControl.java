package com.evolution.analysis.execution.hyperv;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.controller.ControllerTerminationLedger;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Single-bound-attempt controller adapter for discovery and authoritative Stop-VM, including recovery
 * when no handle was published. Does not launch builds, create workers or qualify the backend.
 */
public final class HyperVWorkerControl implements SandboxExecutor {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("execution.hyperv-worker-control","m8.4-v1");
    private final Path powershell,script,workspace;
    private final ContentDigest scriptPin;
    private final Launch binding;
    private final UUID attempt;
    private final StreamingContentStore content;
    private final ControllerTerminationLedger ledger;
    @FunctionalInterface public interface Runner {String run(List<String> arguments,String trustedSource,Duration deadline)throws IOException;}
    private final Runner runner;
    public HyperVWorkerControl(Path powershell,Path script,ContentDigest scriptPin,Path workspace,Launch binding,StreamingContentStore content,ControllerTerminationLedger ledger){this(powershell,script,scriptPin,workspace,binding,content,ledger,HyperVWorkerControl::run);}
    public HyperVWorkerControl(Path powershell,Path script,ContentDigest scriptPin,Path workspace,Launch binding,StreamingContentStore content,ControllerTerminationLedger ledger,Runner runner){
        this.powershell=powershell.toAbsolutePath().normalize();this.script=script.toAbsolutePath().normalize();this.workspace=workspace.toAbsolutePath().normalize();this.scriptPin=Objects.requireNonNull(scriptPin);this.binding=Objects.requireNonNull(binding);this.content=Objects.requireNonNull(content);this.ledger=Objects.requireNonNull(ledger);this.runner=Objects.requireNonNull(runner);
        attempt=UUID.fromString(binding.attempt());if(!attempt.toString().equals(binding.attempt()))throw new IllegalArgumentException("CANONICAL_ATTEMPT_UUID_REQUIRED");
    }
    @Override public Probe probe(){return new Probe(Availability.UNQUALIFIED,"WORKER_LAUNCH_AND_CONTAINMENT_NOT_QUALIFIED",PROVIDER,Optional.empty());}
    @Override public Handle launch(Launch request)throws IOException{throw new IOException("QUALIFIED_HYPERV_LAUNCH_NOT_IMPLEMENTED");}
    private record Observation(String state,String vmId,String raw){}
    private Observation call(String action)throws IOException{
        if(Files.isSymbolicLink(script)||!Files.isRegularFile(script,LinkOption.NOFOLLOW_LINKS)||Files.size(script)>131072)throw new IOException("HYPERV_DRIVER_PIN_UNAVAILABLE");
        byte[] driver;
        try(var input=Files.newInputStream(script,LinkOption.NOFOLLOW_LINKS)){driver=input.readNBytes(131073);}
        if(driver.length>131072)throw new IOException("HYPERV_DRIVER_PIN_UNAVAILABLE");
        if(!ContentDigest.sha256(driver).equals(scriptPin))throw new IOException("HYPERV_DRIVER_PIN_MISMATCH");
        // Execute exactly the pinned platform bytes captured here, avoiding a second mutable file read.
        String source="& {\n"+new String(driver,StandardCharsets.UTF_8)+"\n} -Action '"+action+"' -Workspace ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('"+Base64.getEncoder().encodeToString(workspace.toString().getBytes(StandardCharsets.UTF_8))+"'))) -Attempt ([Guid]'"+attempt+"') -LaunchDigest '"+CanonicalJson.sha256(binding).value()+"' -Fence "+binding.fence();
        // A constant command reads the captured platform script from stdin: no Windows command-line ceiling.
        String wrapper="[Console]::InputEncoding=[Text.UTF8Encoding]::new($false); [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); & ([scriptblock]::Create([Console]::In.ReadToEnd()))";
        String encoded=Base64.getEncoder().encodeToString(wrapper.getBytes(StandardCharsets.UTF_16LE));
        String raw=runner.run(List.of(powershell.toString(),"-NoLogo","-NoProfile","-NonInteractive","-EncodedCommand",encoded),source,Duration.ofSeconds(30));
        try{
            var object=GraphJson.object(GraphJson.read(raw.strip(),new GraphJson.Limits(8192,8,128)));
            if(!object.keySet().equals(Set.of("schema","attempt","name","vmId","state","processors","memoryBytes","isolationQualified","launch","fence"))
                    ||!"hyperv-worker-state:m8.4-v1".equals(object.get("schema"))||!binding.attempt().equals(object.get("attempt"))
                    ||!("evo-worker-"+attempt.toString().replace("-","")).equals(object.get("name"))||!Boolean.FALSE.equals(object.get("isolationQualified"))
                    ||!CanonicalJson.sha256(binding).value().equals(object.get("launch"))||number(object.get("fence"))!=binding.fence())throw new IOException("HYPERV_WORKER_SCOPE_MISMATCH");
            String state=GraphJson.string(object.get("state")),vmId=GraphJson.string(object.get("vmId"));long cpu=number(object.get("processors")),memory=number(object.get("memoryBytes"));
            if(state.equals("Absent")){
                if(!vmId.isEmpty()||cpu!=0||memory!=0||!action.equals("Find"))throw new IOException("HYPERV_ABSENCE_INVALID");
            }else{
                if(state.isEmpty()||!UUID.fromString(vmId).toString().equals(vmId)||cpu<1||memory<1)throw new IOException("HYPERV_WORKER_STATE_INVALID");
            }
            return new Observation(state,vmId,raw);
        }catch(IllegalArgumentException|ClassCastException|ArithmeticException invalid){throw new IOException("HYPERV_WORKER_RESPONSE_INVALID",invalid);}
    }
    private static long number(Object value){return ((java.math.BigDecimal)value).longValueExact();}
    private ContentDigest proof(Observation observed)throws IOException{
        var observation=CanonicalJson.write(Map.of("schema","hyperv-control-observation:m8.4-v1","provider",PROVIDER,"driver",scriptPin,"response",observed.raw())).getBytes(StandardCharsets.UTF_8);
        var raw=content.put(new ByteArrayInputStream(observation),()->false);
        return ledger.record(binding,observed.vmId().isEmpty()?"evo-worker-"+attempt.toString().replace("-",""):observed.vmId(),observed.state().equals("Absent")?ControllerTerminationLedger.Disposition.ABSENT:ControllerTerminationLedger.Disposition.OFF,raw);
    }
    @Override public Recovery recover(Launch request)throws IOException{
        if(!binding.equals(request))throw new IOException("HYPERV_LAUNCH_SCOPE_MISMATCH");
        var observed=call("Find");
        if(observed.state().equals("Absent"))return new Recovery(Optional.empty(),Optional.of(proof(observed)),"");
        if(observed.state().equals("Off"))observed=fenceOff(observed);
        return new Recovery(Optional.of(new Handle(binding.attempt(),binding.fence(),observed.vmId())),observed.state().equals("Off")?Optional.of(proof(observed)):Optional.empty(),"");
    }
    private void exact(Handle handle)throws IOException{
        if(!handle.attempt().equals(binding.attempt())||handle.fence()!=binding.fence())throw new IOException("HYPERV_HANDLE_SCOPE_MISMATCH");
    }
    @Override public Status inspect(Handle handle)throws IOException{
        exact(handle);var observed=call("Find");
        if(!observed.vmId().equals(handle.backendId()))throw new IOException("HYPERV_WORKER_ID_MISMATCH");
        if(observed.state().equals("Off"))observed=fenceOff(observed);
        return status(handle,observed);
    }
    private Observation fenceOff(Observation observed)throws IOException{
        // An Off snapshot alone can race a stale Start. Stop installs the driver's durable barrier.
        var stopped=call("Stop");
        if(!stopped.vmId().equals(observed.vmId()))throw new IOException("HYPERV_WORKER_ID_MISMATCH");
        return stopped;
    }
    private Status status(Handle handle,Observation observed)throws IOException{return new Status(handle,!observed.state().equals("Off"),observed.state().equals("Off")?Optional.of(proof(observed)):Optional.empty(),Optional.empty(),observed.state().equals("Off")?"OUTPUT_CENSUS_REQUIRED":"");}
    @Override public Status terminate(Handle handle)throws IOException{
        exact(handle);var observed=call("Find");
        if(!observed.vmId().equals(handle.backendId()))throw new IOException("HYPERV_WORKER_ID_MISMATCH");
        observed=call("Stop");
        if(!observed.vmId().equals(handle.backendId()))throw new IOException("HYPERV_WORKER_ID_MISMATCH");
        return status(handle,observed);
    }
    @Override public Status collect(Handle handle)throws IOException{return inspect(handle);}
    private static String run(List<String> args,String source,Duration timeout)throws IOException{
        var process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var bytes=new ByteArrayOutputStream();var failure=new java.util.concurrent.atomic.AtomicReference<IOException>();
        var reader=new Thread(()->{try(var input=process.getInputStream()){int b;while((b=input.read())!=-1){if(bytes.size()>=8192)throw new IOException("HYPERV_OUTPUT_OVERSIZE");bytes.write(b);}}catch(IOException e){failure.set(e);process.destroyForcibly();}},"hyperv-worker-output");
        var writer=new Thread(()->{try(var output=process.getOutputStream()){output.write(source.getBytes(StandardCharsets.UTF_8));}catch(IOException e){failure.set(e);process.destroyForcibly();}},"hyperv-worker-input");
        reader.setDaemon(true);writer.setDaemon(true);reader.start();writer.start();
        try{
            if(!process.waitFor(timeout.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS))throw new IOException("HYPERV_WORKER_TIMEOUT");
            reader.join(1000);writer.join(1000);
            if(reader.isAlive()||writer.isAlive())throw new IOException("HYPERV_WORKER_PIPE_STALLED");
            if(failure.get()!=null)throw failure.get();if(process.exitValue()!=0)throw new IOException("HYPERV_WORKER_UNAVAILABLE");
            return bytes.toString(StandardCharsets.UTF_8);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("HYPERV_WORKER_CANCELLED");}
        finally{process.descendants().forEach(ProcessHandle::destroyForcibly);if(process.isAlive())process.destroyForcibly();}
    }
}
