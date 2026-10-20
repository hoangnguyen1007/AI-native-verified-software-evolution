package com.evolution.analysis.execution.controller;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.hyperv.*;
import com.evolution.analysis.execution.qualification.*;
import com.evolution.analysis.filesystem.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Registered authored candidate fixture: production admission must reject launch; recovery may still
 * discover/stop the exact separately bootstrapped candidate. Never qualifies it or executes target builds.
 */
public final class HyperVRecoveryFixtureMain {
    static ContentDigest d(String s){return ContentDigest.sha256Utf8(s);}
    public static void main(String[] args)throws Exception{
        if((args.length!=5&&args.length!=6)||!Set.of("prepare","recover").contains(args[0]))throw new IllegalArgumentException("mode root workspace powershell driver [owned-attempt-uuid] required");
        Path root=Path.of(args[1]),workspace=Path.of(args[2]),powershell=Path.of(args[3]),script=Path.of(args[4]);
        Files.createDirectories(root);var cas=new StreamingContentStore(root.resolve("cas"));
        var pin=cas.put(new ByteArrayInputStream("authored hyperv recovery fixture; no target build".getBytes(StandardCharsets.UTF_8)),()->false);
        var driverPin=ContentDigest.sha256(Files.readAllBytes(script));
        String attempt=args.length==6?UUID.fromString(args[5]).toString():"44af410b-0714-4db9-a4ef-26c73a6bfc35";
        var launch=new SandboxExecutor.Launch(d("authored-recovery-plan"),d("authored-recovery-task"),d("no-target-command"),CanonicalJson.sha256(Map.of("candidateOnly",true,"driver",driverPin)),pin,d("authored-recovery-policy"),attempt,1);
        var resources=new ResourceBudget.Resources(2,2147483648L,1073741824L,1,1,1);
        try(var attempts=new DurableAttemptStore(root.resolve("attempts"),cas);var queue=new DurableAdmissionQueue(root.resolve("admission"),cas,resources,2,1000000);
                var tasks=new DurableExecutionJournal(root.resolve("tasks"),cas);var ledger=new ControllerTerminationLedger(root.resolve("terminations"),cas,HyperVWorkerControl.PROVIDER)){
            var backend=new HyperVWorkerControl(powershell,script,driverPin,workspace,launch,cas,ledger);
            var gate=new SandboxQualificationGate(SandboxQualificationGate.hyperVSuite(),HyperVWorkerControl.PROVIDER,Map.of(),cas,Clock.systemUTC(),Duration.ofHours(1),Set::of,Set::of);
            var controller=new ExecutionController(attempts,queue,tasks,new QualifiedSandboxExecutor(backend,gate,Optional.empty()),ledger,cas);
            ExecutionAttempt result;
            if(args[0].equals("prepare")){
                var current=tasks.create(launch.plan(),launch.task());
                current=tasks.compareAndSet(launch.plan(),current,current.advance(current.revision(),current.fence(),ExecutionState.Phase.READY,"AUTHORED_FIXTURE_ONLY",Optional.empty(),Optional.empty()));
                var request=new AdmissionQueue.Request(new AdmissionQueue.Ticket(launch.plan(),launch.task(),1),d("authored-fixture"),resources,0);
                controller.enqueue(launch,request,current);result=controller.start(launch.attempt());
                if(result.phase()!=ExecutionAttempt.Phase.QUARANTINED||queue.get().active().size()!=1)throw new IOException("UNQUALIFIED_LAUNCH_WAS_NOT_REJECTED");
                Files.writeString(root.resolve("request.json"),CanonicalJson.write(Map.of("launch",launch,"launchDigest",CanonicalJson.sha256(launch),"driverPin",driverPin)),StandardOpenOption.CREATE_NEW);
            }else{
                if(!attempts.get(launch.attempt()).launch().equals(launch))throw new IOException("FIXTURE_INPUT_REVISION_CHANGED");
                result=controller.recover(launch.attempt());
                if(result.phase()!=ExecutionAttempt.Phase.RELEASED||!queue.get().active().isEmpty()||queue.get().retained().size()!=1||!result.reason().equals("STORAGE_RESERVATION_RETAINED")||tasks.get(launch.plan(),launch.task()).orElseThrow().reservesWorker())throw new IOException("REAL_RECOVERY_NOT_CONFIRMED");
                Files.writeString(root.resolve("recovery-result.json"),CanonicalJson.write(result),StandardOpenOption.CREATE_NEW);
            }
            System.out.println(CanonicalJson.write(Map.of("schema","authored-hyperv-recovery:m8.4-v1","mode",args[0],"attempt",launch.attempt(),"launchDigest",CanonicalJson.sha256(launch),"phase",result.phase(),"activeReservations",queue.get().active().size(),"taskPhase",tasks.get(launch.plan(),launch.task()).orElseThrow().phase(),"termination",result.termination(),"qualified",false)));
        }
    }
}
