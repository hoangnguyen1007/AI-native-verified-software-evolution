package com.evolution.analysis.execution.hyperv;

import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.execution.SandboxExecutor;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only controller probe. VM presence never qualifies a build executor. */
public final class HyperVProbe {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("execution.hyperv-probe","m8.4-v1");
    public record Observation(String vmName,String vmId,String state,long processors,long memoryBytes,String reason) {}
    @FunctionalInterface public interface Runner {String run(List<String> arguments,Duration deadline)throws IOException;}
    private final Path powershell;private final Runner runner;
    public HyperVProbe(Path powershell){this(powershell,HyperVProbe::run);}
    public HyperVProbe(Path powershell,Runner runner){this.powershell=powershell.toAbsolutePath().normalize();this.runner=Objects.requireNonNull(runner);}
    public Observation inspect(String vmName)throws IOException {
        if(vmName==null||!vmName.matches("[A-Za-z0-9_. -]{1,128}"))throw new IllegalArgumentException("INVALID_VM_NAME");
        // vmName is passed via stdin-safe base64 data and decoded as a single cmdlet parameter, never as code.
        String encoded=Base64.getEncoder().encodeToString(vmName.getBytes(StandardCharsets.UTF_8));
        String script="$ErrorActionPreference='Stop'; try { $n=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('"+encoded+"')); $v=Get-VM -Name $n -ErrorAction Stop; [ordered]@{vmName=$v.Name;vmId=$v.Id.ToString();state=$v.State.ToString();processors=[long]$v.ProcessorCount;memoryBytes=[long]$v.MemoryStartup;reason=''} | ConvertTo-Json -Compress } catch { [ordered]@{vmName='';vmId='';state='';processors=0;memoryBytes=0;reason='HYPERV_VM_UNAVAILABLE'} | ConvertTo-Json -Compress }";
        String command=Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        var text=runner.run(List.of(powershell.toString(),"-NoLogo","-NoProfile","-NonInteractive","-EncodedCommand",command),Duration.ofSeconds(30));
        try{
            var object=GraphJson.object(GraphJson.read(text.strip(),new GraphJson.Limits(8192,8,64)));
            if(!object.keySet().equals(Set.of("vmName","vmId","state","processors","memoryBytes","reason")))throw new IOException("HYPERV_PROBE_INVALID");
            var result=new Observation(GraphJson.string(object.get("vmName")),GraphJson.string(object.get("vmId")),GraphJson.string(object.get("state")),((java.math.BigDecimal)object.get("processors")).longValueExact(),((java.math.BigDecimal)object.get("memoryBytes")).longValueExact(),GraphJson.string(object.get("reason")));
            if(result.reason().isEmpty()&&(!result.vmName().equals(vmName)||result.processors()<1||result.memoryBytes()<1))throw new IOException("HYPERV_PROBE_INVALID");
            if(result.reason().isEmpty())UUID.fromString(result.vmId());return result;
        }catch(IllegalArgumentException|ClassCastException|ArithmeticException invalid){throw new IOException("HYPERV_PROBE_INVALID",invalid);}
    }
    public SandboxExecutor.Probe readiness(String name)throws IOException {
        var observation=inspect(name);return new SandboxExecutor.Probe(observation.reason().isEmpty()?SandboxExecutor.Availability.UNQUALIFIED:SandboxExecutor.Availability.UNAVAILABLE,
                observation.reason().isEmpty()?"ISOLATION_AND_GUEST_TRANSPORT_NOT_QUALIFIED":observation.reason(),PROVIDER,Optional.empty());
    }
    private static String run(List<String> args,Duration timeout)throws IOException {
        var process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var bytes=new ByteArrayOutputStream();var failure=new java.util.concurrent.atomic.AtomicReference<IOException>();
        Thread reader=new Thread(()->{try(var in=process.getInputStream()){int b;while((b=in.read())!=-1){if(bytes.size()>=8192)throw new IOException("HYPERV_PROBE_OVERSIZE");bytes.write(b);}}catch(IOException e){failure.set(e);process.destroyForcibly();}},"hyperv-probe-output");reader.setDaemon(true);reader.start();
        try{
            if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS))throw new IOException("HYPERV_PROBE_TIMEOUT");
            reader.join(5000);if(reader.isAlive())throw new IOException("HYPERV_PROBE_OUTPUT_STALLED");if(failure.get()!=null)throw failure.get();if(process.exitValue()!=0)throw new IOException("HYPERV_PROBE_UNAVAILABLE");return bytes.toString(StandardCharsets.UTF_8);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("HYPERV_PROBE_CANCELLED");}
        finally{process.descendants().forEach(ProcessHandle::destroyForcibly);if(process.isAlive())process.destroyForcibly();}
    }
}
