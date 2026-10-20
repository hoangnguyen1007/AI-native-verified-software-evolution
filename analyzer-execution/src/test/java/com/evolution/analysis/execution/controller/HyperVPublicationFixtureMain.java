package com.evolution.analysis.execution.controller;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.hyperv.HyperVWorkerControl;
import com.evolution.analysis.execution.output.OutputCensus;
import com.evolution.analysis.execution.qualification.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Authored fixture only. Production launch must reject; a separately controlled candidate runs
 * only the registered Maven fixture. This controller recovers, reaps and publishes asserted bytes.
 */
public final class HyperVPublicationFixtureMain {
    static final Map<String,String> EXPECTED=Map.of(
            "model/target/generated-sources/evo/fixture/Generated.java","88196b39f5d19646ce44935fb0bdb49228dea4cf049c8eaf9cd20c41857fbfe2",
            "model/target/classes/fixture/Generated.class","545cd424d601dab99ee0df94739b361bad5c85bd7cdb7dd1e2dc975c74834cd5",
            "app/target/classes/fixture/Caller.class","0af3ef25593c7473ad03c0aca468137bfcc95f2f9d5bbdd317a09bf5059752c5");
    static ContentDigest d(String value){return ContentDigest.sha256Utf8(value);}
    static ContentHandle capture(StreamingContentStore cas,Path source)throws IOException{
        try(var input=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)){return cas.put(input,()->false);}
    }
    static void write(Path target,Object value)throws IOException{
        byte[] bytes=CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8);
        try(var file=java.nio.channels.FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);
        }
    }
    static Map<String,Object> read(Path source)throws IOException{
        if(Files.size(source)>65536)throw new IOException("FIXTURE_METADATA_WORKER_REQUIRED");
        return GraphJson.object(GraphJson.read(Files.readString(source,StandardCharsets.UTF_8),new GraphJson.Limits(65536,24,8192)));
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=6||!Set.of("prepare","publish","replay","recover-only").contains(args[0]))throw new IllegalArgumentException("mode experiment-root workspace powershell driver attempt-uuid required");
        String mode=args[0],attempt=UUID.fromString(args[5]).toString();Path root=Path.of(args[1]),workspace=Path.of(args[2]),powershell=Path.of(args[3]),driver=Path.of(args[4]);
        Path state=root.resolve("controller");Files.createDirectories(state);var cas=new StreamingContentStore(state.resolve("cas"));
        var driverPin=ContentDigest.sha256(Files.readAllBytes(driver));
        String workAttempt=attempt.replace("-","");String nonce=d("authored-publication-nonce:v1\n"+attempt).value().substring(7,39);
        var scope=new OutputCensus.Scope(workAttempt,nonce,new OutputCensus.Budget(65536,134217728,536870912));
        var image=capture(cas,root.resolve("image-pins.json"));var repo=Path.of("").toAbsolutePath();
        var imageMetadata=read(root.resolve("image-pins.json"));
        for(var artifact:Map.of("bootstrapSeed","bootstrap-seed.iso","networkPolicy","network-policy.json").entrySet())if(imageMetadata.containsKey(artifact.getKey())){
            var pin=GraphJson.object(imageMetadata.get(artifact.getKey()));var actual=capture(cas,root.resolve(artifact.getValue()));
            if(!actual.digest().value().equals(pin.get("digest"))||actual.size()!=((java.math.BigDecimal)pin.get("size")).longValueExact())throw new IOException("REGISTERED_BOOTSTRAP_INPUT_CHANGED");
        }
        var collector=capture(cas,repo.resolve("analyzer-execution/src/main/resources/guest/export.py"));
        var runner=capture(cas,repo.resolve("analyzer-execution/src/main/resources/guest/runner.py"));
        var inputs=new TreeMap<String,ContentHandle>();Path fixture=repo.resolve("analyzer-execution/src/test/resources/native/maven-generated");
        try(var paths=Files.walk(fixture)){for(var path:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted().toList())inputs.put(fixture.relativize(path).toString().replace('\\','/'),capture(cas,path));}
        var inputManifest=CanonicalJson.write(Map.of("fixture","authored-maven-publication:v1","image",image,"collector",collector,"runner",runner,"driver",driverPin,"sources",inputs));
        var input=cas.put(new ByteArrayInputStream(inputManifest.getBytes(StandardCharsets.UTF_8)),()->false);
        var launch=new SandboxExecutor.Launch(d("authored-maven-publication-plan:v1"),d("authored-maven-publication-task:v1"),d("authored-offline-maven-compile:v1"),CanonicalJson.sha256(Map.of("candidateOnly",true,"image",image,"driver",driverPin)),input,d("authored-no-egress-policy:v1"),attempt,1);
        long disk=((java.math.BigDecimal)imageMetadata.get("virtualDiskBytes")).longValueExact();
        if(disk<1)throw new IOException("FIXTURE_DISK_SCOPE_INVALID");
        var resources=new ResourceBudget.Resources(2,2147483648L,disk,256,16777216,0);
        var expectations=EXPECTED.keySet().stream().sorted().map(path->new DurableOutputPublication.Expectation(path,LogicalPath.utf8(path),true)).toList();
        try(var attempts=new DurableAttemptStore(state.resolve("attempts"),cas);var queue=new DurableAdmissionQueue(state.resolve("admission"),cas,resources,2,1000000);
                var tasks=new DurableExecutionJournal(state.resolve("tasks"),cas);var ledger=new ControllerTerminationLedger(state.resolve("terminations"),cas,HyperVWorkerControl.PROVIDER);
                var publication=new DurableOutputPublication(state.resolve("publication"),cas,attempts,ledger,16,16777216)){
            var backend=new HyperVWorkerControl(powershell,driver,driverPin,workspace,launch,cas,ledger);
            var gate=new SandboxQualificationGate(SandboxQualificationGate.hyperVSuite(),HyperVWorkerControl.PROVIDER,Map.of(),cas,Clock.systemUTC(),Duration.ofHours(1),Set::of,Set::of);
            var controller=new ExecutionController(attempts,queue,tasks,new QualifiedSandboxExecutor(backend,gate,Optional.empty()),ledger,cas);
            if(mode.equals("prepare")){
                if(Files.exists(state.resolve("request.json")))throw new IOException("FRESH_FIXTURE_REQUIRED");
                var task=tasks.create(launch.plan(),launch.task());task=tasks.compareAndSet(launch.plan(),task,task.advance(task.revision(),task.fence(),ExecutionState.Phase.READY,"AUTHORED_FIXTURE_ONLY",Optional.empty(),Optional.empty()));
                controller.enqueue(launch,new AdmissionQueue.Request(new AdmissionQueue.Ticket(launch.plan(),launch.task(),1),d("authored-publication-job"),resources,0),task);
                publication.prepare(launch,scope,expectations);
                var export=new TreeMap<String,Object>();export.put("attempt",workAttempt);export.put("nonce",nonce);export.put("work","/work/"+workAttempt);export.put("maxRowBytes",scope.budget().maxRowBytes());export.put("maxObjectBytes",scope.budget().maxObjectBytes());export.put("maxCaptureBytes",scope.budget().maxCaptureBytes());
                write(state.resolve("export-request.json"),Map.of("request",export,"collectorPin",collector.digest()));
                write(state.resolve("request.json"),Map.of("launch",launch,"launchDigest",CanonicalJson.sha256(launch),"collectorPin",collector.digest(),"runnerPin",runner.digest(),"image",image));
                var result=controller.start(attempt);
                if(result.phase()!=ExecutionAttempt.Phase.QUARANTINED||queue.get().active().size()!=1||backend.probe().availability()!=SandboxExecutor.Availability.UNQUALIFIED)throw new IOException("PRODUCTION_LAUNCH_NOT_REJECTED");
                System.out.println(CanonicalJson.write(Map.of("mode",mode,"attempt",attempt,"launchDigest",CanonicalJson.sha256(launch),"phase",result.phase(),"scope",scope,"qualified",false)));return;
            }
            if(!attempts.get(attempt).launch().equals(launch))throw new IOException("FIXTURE_INPUT_REVISION_CHANGED");
            var result=controller.recover(attempt);
            if(result.phase()!=ExecutionAttempt.Phase.RELEASED||!queue.get().active().isEmpty()||queue.get().retained().size()!=1||tasks.get(launch.plan(),launch.task()).orElseThrow().phase()!=ExecutionState.Phase.FAILED)throw new IOException("FIXTURE_RECOVERY_OR_RETENTION_FAILED");
            if(mode.equals("recover-only")){
                var outcome=Map.of("schema","authored-hyperv-failed-fixture-recovery:m8.4-v1","attempt",attempt,"phase",result.phase(),"termination",result.termination(),"storageHolds",queue.get().retained().size(),"activeReservations",queue.get().active().size(),"taskPhase","FAILED","qualified",false);
                write(state.resolve("failed-fixture-recovery.json"),outcome);System.out.println(CanonicalJson.write(outcome));return;
            }
            var nativeRun=read(root.resolve("native-result.json"));
            if(!workAttempt.equals(nativeRun.get("attempt"))||!java.math.BigDecimal.ZERO.equals(nativeRun.get("buildExit"))||!java.math.BigDecimal.ZERO.equals(nativeRun.get("consumerExit"))||!Boolean.FALSE.equals(nativeRun.get("qualified")))throw new IOException("AUTHORED_NATIVE_RUN_NOT_SUCCESSFUL");
            var nativeObservation=capture(cas,root.resolve("native-result.json"));
            Path captured=root.resolve("capture");capture(cas,captured.resolve("scope.json"));capture(cas,captured.resolve("entries.ndjson"));var header=capture(cas,captured.resolve("collector-result.json"));
            long files=0;try(var paths=Files.list(captured.resolve("objects"))){for(var path:paths.toList()){
                if(!path.getFileName().toString().matches("[a-f0-9]{64}")||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("UNSAFE_OPAQUE_OBJECT");
                var h=capture(cas,path);if(!h.digest().value().substring(7).equals(path.getFileName().toString()))throw new IOException("OPAQUE_OBJECT_DIGEST_MISMATCH");files++;
            }}
            var published=publication.publish(launch,header,()->false);
            if(published.decision()!=OutputCensus.Decision.CAPTURED||published.roles().size()!=EXPECTED.size())throw new IOException("FIXTURE_OUTPUT_CLOSURE_NOT_CAPTURED");
            for(var role:published.roles())if(!role.outcome().equals("CAPTURED_BYTES")||!role.captured().orElseThrow().digest().value().equals("sha256:"+EXPECTED.get(role.role())))throw new IOException("INDEPENDENT_MAVEN_OUTPUT_ORACLE_MISMATCH");
            var summary=Map.ofEntries(Map.entry("schema","authored-hyperv-output-publication:m8.4-v1"),Map.entry("attempt",attempt),Map.entry("launchDigest",CanonicalJson.sha256(launch)),Map.entry("publication",published),Map.entry("termination",result.termination()),Map.entry("retained",queue.get().retained()),Map.entry("activeReservations",queue.get().active().size()),Map.entry("byteObjects",files),Map.entry("taskPhase",tasks.get(launch.plan(),launch.task()).orElseThrow().phase()),Map.entry("nativeObservation",nativeObservation),Map.entry("effectiveTrust",published.effectiveTrust()),Map.entry("qualified",false));
            if(mode.equals("publish"))write(state.resolve("publication-result.json"),summary);
            else{
                var previous=read(state.resolve("publication-result.json"));
                if(!CanonicalJson.write(GraphJson.object(previous.get("publication"))).equals(CanonicalJson.write(published)))throw new IOException("PUBLICATION_REPLAY_CHANGED");
                write(state.resolve("replay-result.json"),summary);
            }
            System.out.println(CanonicalJson.write(Map.of("mode",mode,"attempt",attempt,"decision",published.decision(),"roles",published.roles().size(),"byteObjects",files,"document",published.document(),"activeReservations",0,"storageHolds",1,"effectiveTrust",published.effectiveTrust(),"qualified",false)));
        }
    }
}
