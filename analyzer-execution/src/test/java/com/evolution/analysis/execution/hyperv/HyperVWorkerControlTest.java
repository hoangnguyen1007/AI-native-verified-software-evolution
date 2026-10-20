package com.evolution.analysis.execution.hyperv;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.controller.ControllerTerminationLedger;
import com.evolution.analysis.filesystem.StreamingContentStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HyperVWorkerControlTest {
    @TempDir Path root;
    static ContentDigest d(String s){return ContentDigest.sha256Utf8(s);}
    static final String ATTEMPT="44af410b-0714-4db9-a4ef-26c73a6bfc35", VM="01a6f92c-d952-4bd6-abf4-63c5437e71b2";
    SandboxExecutor.Launch launch(){return new SandboxExecutor.Launch(d("plan"),d("task"),d("recipe"),d("profile"),new ContentHandle(d("input"),1),d("policy"),ATTEMPT,1);}
    Map<String,Object> observation(String state){
        var out=new TreeMap<String,Object>();out.put("schema","hyperv-worker-state:m8.4-v1");out.put("attempt",ATTEMPT);out.put("name","evo-worker-"+ATTEMPT.replace("-",""));out.put("vmId",state.equals("Absent")?"":VM);out.put("state",state);out.put("processors",state.equals("Absent")?0:2);out.put("memoryBytes",state.equals("Absent")?0:2147483648L);out.put("isolationQualified",false);out.put("launch",CanonicalJson.sha256(launch()));out.put("fence",1);return out;
    }
    @Test void actualOffObservationIsCapturedAndCannotQualifyOrLaunchAnything()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));Path script=root.resolve("driver.ps1");Files.writeString(script,"# authored platform fixture\n");var calls=new AtomicInteger();
        try(var ledger=new ControllerTerminationLedger(root.resolve("ledger"),cas,HyperVWorkerControl.PROVIDER)){
            var driver=new HyperVWorkerControl(Path.of("pwsh"),script,ContentDigest.sha256(Files.readAllBytes(script)),root,launch(),cas,ledger,(args,source,deadline)->{calls.incrementAndGet();return CanonicalJson.write(observation("Off"));});
            assertEquals(SandboxExecutor.Availability.UNQUALIFIED,driver.probe().availability());assertThrows(IOException.class,()->driver.launch(launch()));assertEquals(0,calls.get());
            var found=driver.recover(launch());ledger.require(launch(),found.termination().orElseThrow());
            var stopped=driver.terminate(found.handle().orElseThrow());assertFalse(stopped.running());assertTrue(stopped.export().isEmpty());assertEquals("OUTPUT_CENSUS_REQUIRED",stopped.reason());assertEquals(2,calls.get());
        }
    }
    @Test void authoritativeAbsenceDiffersFromUnavailableServiceAndForgedScope()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));Path script=root.resolve("driver.ps1");Files.writeString(script,"# platform fixture");
        try(var ledger=new ControllerTerminationLedger(root.resolve("ledger"),cas,HyperVWorkerControl.PROVIDER)){
            var driver=new HyperVWorkerControl(Path.of("pwsh"),script,ContentDigest.sha256(Files.readAllBytes(script)),root,launch(),cas,ledger,(args,source,deadline)->CanonicalJson.write(observation("Absent")));
            var found=driver.recover(launch());assertTrue(found.handle().isEmpty());ledger.require(launch(),found.termination().orElseThrow());
            var unavailable=new HyperVWorkerControl(Path.of("pwsh"),script,ContentDigest.sha256(Files.readAllBytes(script)),root,launch(),cas,ledger,(args,source,deadline)->{throw new IOException("SERVICE_UNAVAILABLE");});
            assertThrows(IOException.class,()->unavailable.recover(launch()));
            var bad=observation("Off");bad.put("fence",2);
            var forged=new HyperVWorkerControl(Path.of("pwsh"),script,ContentDigest.sha256(Files.readAllBytes(script)),root,launch(),cas,ledger,(args,source,deadline)->CanonicalJson.write(bad));
            assertThrows(IOException.class,()->forged.recover(launch()));
            bad.put("fence",new java.math.BigDecimal("1.5"));assertThrows(IOException.class,()->forged.recover(launch()));
        }
    }
    @Test void pinnedLargeDriverUsesStdinAndWorkspaceCannotBecomeCode()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));Path script=root.resolve("driver.ps1");Files.writeString(script,"#"+"x".repeat(60000));
        Path workspace=root.resolve("user's workspace; write-host forbidden");var calls=new AtomicInteger();
        try(var ledger=new ControllerTerminationLedger(root.resolve("ledger"),cas,HyperVWorkerControl.PROVIDER)){
            var driver=new HyperVWorkerControl(Path.of("pwsh"),script,ContentDigest.sha256(Files.readAllBytes(script)),workspace,launch(),cas,ledger,(args,source,deadline)->{
                calls.incrementAndGet();assertTrue(args.stream().mapToInt(String::length).sum()<2048);assertTrue(source.length()>60000);assertFalse(source.contains(workspace.toString()));assertTrue(source.contains(Base64.getEncoder().encodeToString(workspace.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));return CanonicalJson.write(observation("Off"));
            });
            driver.recover(launch());assertEquals(1,calls.get());Files.writeString(script,"changed");
            assertThrows(IOException.class,()->driver.recover(launch()));assertEquals(1,calls.get());
            Files.writeString(script,"#"+"x".repeat(140000));
            assertEquals("HYPERV_DRIVER_PIN_UNAVAILABLE",assertThrows(IOException.class,()->driver.recover(launch())).getMessage());assertEquals(1,calls.get());
        }
    }
    @Test void staleOrForeignHandleCannotReachStopAndNonOffStateNeverProvesQuiescence()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));Path script=root.resolve("driver.ps1");Files.writeString(script,"# platform fixture");var calls=new ArrayList<String>();
        try(var ledger=new ControllerTerminationLedger(root.resolve("ledger"),cas,HyperVWorkerControl.PROVIDER)){
            var driver=new HyperVWorkerControl(Path.of("pwsh"),script,ContentDigest.sha256(Files.readAllBytes(script)),root,launch(),cas,ledger,(args,source,deadline)->{calls.add(source);return CanonicalJson.write(observation("Saved"));});
            assertThrows(IOException.class,()->driver.terminate(new SandboxExecutor.Handle(ATTEMPT,2,VM)));assertTrue(calls.isEmpty());
            assertThrows(IOException.class,()->driver.terminate(new SandboxExecutor.Handle(ATTEMPT,1,"foreign-vm")));assertEquals(1,calls.size());
            var found=driver.recover(launch());assertTrue(found.termination().isEmpty());
            var status=driver.terminate(found.handle().orElseThrow());assertTrue(status.running());assertTrue(status.termination().isEmpty());assertTrue(calls.getLast().contains("-Action 'Stop'"));
        }
    }
}
