package com.evolution.analysis.execution.controller;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.qualification.*;
import com.evolution.analysis.filesystem.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionControllerTest {
    @TempDir Path root;
    static ContentDigest d(String s){return ContentDigest.sha256Utf8(s);}
    static final VersionedIdentifier VERSION=new VersionedIdentifier("authored.controller-backend","1");
    static final Instant NOW=Instant.parse("2026-10-19T10:00:00Z");
    static final ResourceBudget.Resources CAPACITY=new ResourceBudget.Resources(2,1024,1024,2,2,2);
    static final class Crash extends Error {}
    static Consumer<ExecutionController.Checkpoint> crashAt(ExecutionController.Checkpoint at){return point->{if(point==at)throw new Crash();};}
    static class Backend implements SandboxExecutor {
        Map<String,Launch> workers=new HashMap<>();Map<String,Handle> handles=new HashMap<>();Set<String> running=new HashSet<>();
        ControllerTerminationLedger ledger;StreamingContentStore cas;ContentDigest certificate;int launches,stops;
        boolean unknown,foreign,noProof,untrustedProof,unavailableProbe;
        public Probe probe()throws IOException{if(unavailableProbe)throw new IOException("PRIVATE_SENTINEL");return new Probe(Availability.AVAILABLE,"",VERSION,Optional.of(certificate));}
        public Handle launch(Launch l)throws IOException{launches++;workers.put(l.attempt(),l);var h=new Handle(l.attempt(),l.fence(),"owned-"+l.attempt());handles.put(l.attempt(),h);running.add(l.attempt());return h;}
        public Recovery recover(Launch l)throws IOException{
            if(unknown)return new Recovery(Optional.empty(),Optional.empty(),"PARTITION");
            if(foreign)return new Recovery(Optional.of(new Handle("foreign",l.fence(),"foreign-vm")),Optional.empty(),"");
            var h=handles.get(l.attempt());
            return h==null?new Recovery(Optional.empty(),Optional.of(proof(l,"absent",ControllerTerminationLedger.Disposition.ABSENT)),""):new Recovery(Optional.of(h),Optional.empty(),"");
        }
        ContentDigest proof(Launch l,String namespace,ControllerTerminationLedger.Disposition disposition)throws IOException{
            var raw=cas.put(new ByteArrayInputStream(("authored observed "+disposition+" "+namespace).getBytes(StandardCharsets.UTF_8)),()->false);
            return ledger.record(l,namespace,disposition,raw);
        }
        public Status inspect(Handle h){return new Status(h,running.contains(h.attempt()),Optional.empty(),Optional.empty(),"");}
        public Status terminate(Handle h)throws IOException{
            stops++;running.remove(h.attempt());
            return new Status(h,false,noProof?Optional.empty():Optional.of(untrustedProof?d("guest-says-off"):proof(workers.get(h.attempt()),h.backendId(),ControllerTerminationLedger.Disposition.OFF)),Optional.empty(),"");
        }
        public Status collect(Handle h){return new Status(h,false,Optional.empty(),Optional.empty(),"NOT_IMPLEMENTED");}
    }
    static final class PersistentBackend extends Backend {
        final Path root;
        PersistentBackend(Path root){this.root=root;}
        public Handle launch(Launch launch)throws IOException{
            var handle=super.launch(launch);
            Files.writeString(root.resolve("simulated-worker.json"),CanonicalJson.write(launch),StandardOpenOption.CREATE_NEW);
            try(var file=java.nio.channels.FileChannel.open(root.resolve("simulated-worker.json"),StandardOpenOption.WRITE)){file.force(true);}
            return handle;
        }
        public Recovery recover(Launch launch)throws IOException{
            if(Files.exists(root.resolve("simulated-worker.json"))&&!Files.exists(root.resolve("simulated-worker-stopped"))){
                try(var input=Files.newInputStream(root.resolve("simulated-worker.json"))){
                    var actual=ExecutionCodec.read(input,Launch.class,new com.evolution.analysis.graph.GraphJson.Limits(64000,16,512));
                    workers.put(actual.attempt(),actual);handles.put(actual.attempt(),new Handle(actual.attempt(),actual.fence(),"owned-"+actual.attempt()));running.add(actual.attempt());
                }
            }
            return super.recover(launch);
        }
        public Status terminate(Handle handle)throws IOException{
            var result=super.terminate(handle);Files.writeString(root.resolve("simulated-worker-stopped"),"Off",StandardOpenOption.CREATE_NEW);return result;
        }
    }
    class Harness {
        final StreamingContentStore cas;final Backend backend;final KeyPair key;final ContentHandle pin;
        Harness()throws Exception{this(new Backend());}
        Harness(Backend backend)throws Exception{this.backend=backend;cas=new StreamingContentStore(root.resolve("cas"));pin=cas.put(new ByteArrayInputStream(new byte[]{1}),()->false);key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();backend.cas=cas;}
        Open open(Consumer<ExecutionController.Checkpoint> checkpoint)throws Exception{return new Open(this,checkpoint);}
        Open open(Consumer<ExecutionController.Checkpoint> checkpoint,ExecutionController.LaunchGuard guard)throws Exception{return new Open(this,checkpoint,guard);}
        Open open()throws Exception{return open(p->{});}
    }
    class Open implements AutoCloseable {
        final DurableAttemptStore attempts;final DurableAdmissionQueue queue;final DurableExecutionJournal tasks;
        final ControllerTerminationLedger ledger;final ExecutionController controller;final ContentDigest profile;final Harness h;
        Open(Harness h,Consumer<ExecutionController.Checkpoint> checkpoint)throws Exception{
            this(h,checkpoint,launch->{});
        }
        Open(Harness h,Consumer<ExecutionController.Checkpoint> checkpoint,ExecutionController.LaunchGuard guard)throws Exception{
            this.h=h;attempts=new DurableAttemptStore(root.resolve("attempts"),h.cas);queue=new DurableAdmissionQueue(root.resolve("admission"),h.cas,CAPACITY,2,1000000);tasks=new DurableExecutionJournal(root.resolve("tasks"),h.cas);ledger=new ControllerTerminationLedger(root.resolve("terminations"),h.cas,VERSION);h.backend.ledger=ledger;
            var gate=new SandboxQualificationGate(new SandboxQualificationGate.Suite(VERSION,List.of("authored")),VERSION,Map.of("harness",h.key.getPublic()),h.cas,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofHours(1),Set::of,Set::of);
            var descriptor=new SandboxQualificationGate.Profile(VERSION,h.pin,h.pin,Optional.empty(),"linux","x86_64",d("e"),d("t"),d("r"),d("n"),Optional.empty());
            var cert=new SandboxQualificationGate.Certificate("execution-qualification-certificate:m8.4-v1",descriptor.underTest(),VERSION,VERSION,gate.policy(),List.of(new SandboxQualificationGate.Control("authored",SandboxQualificationGate.Outcome.PASS,SandboxQualificationGate.Origin.REAL_BACKEND,List.of(h.pin),"")),NOW.minusSeconds(1),NOW.plusSeconds(60),SandboxQualificationGate.Outcome.PASS);
            var signature=Signature.getInstance("Ed25519");signature.initSign(h.key.getPrivate());signature.update(CanonicalJson.write(Map.of("keyId","harness","payload",cert)).getBytes(StandardCharsets.UTF_8));
            var token=gate.evaluate(descriptor,cert,new EvidenceVerifier.SignatureEnvelope("harness",Base64.getEncoder().encodeToString(signature.sign()))).profile().orElseThrow();
            profile=token.descriptor().identity();h.backend.certificate=cert.identity();
            controller=new ExecutionController(attempts,queue,tasks,new QualifiedSandboxExecutor(h.backend,gate,Optional.of(token)),ledger,h.cas,checkpoint,guard);
        }
        ExecutionAttempt prepare(String attempt,String task)throws IOException{
            // This simulated backend creates no worker disk or log allocation.
            return prepare(attempt,task,new ResourceBudget.Resources(2,1024,0,2,0,2));
        }
        ExecutionAttempt prepare(String attempt,String task,ResourceBudget.Resources resources)throws IOException{
            var current=tasks.create(d("plan"),d(task));
            if(current.phase()==ExecutionState.Phase.PENDING)current=tasks.compareAndSet(d("plan"),current,current.advance(current.revision(),current.fence(),ExecutionState.Phase.READY,"",Optional.empty(),Optional.empty()));
            var launch=new SandboxExecutor.Launch(d("plan"),current.task(),d("recipe"),profile,h.pin,d("policy"),attempt,Math.addExact(current.fence(),1));
            var request=new AdmissionQueue.Request(new AdmissionQueue.Ticket(launch.plan(),launch.task(),launch.fence()),d("job"),resources,0);
            return ExecutionAttempt.prepare(launch,request,current);
        }
        ExecutionAttempt enqueue(ExecutionAttempt a)throws IOException{return controller.enqueue(a.launch(),a.admission(),a.expectedTask());}
        @Override public void close()throws IOException{ledger.close();tasks.close();queue.close();attempts.close();}
    }
    @Test void quiescenceDoesNotFreePotentiallyRetainedDiskOrLogReservations()throws Exception{
        var baseRoot=root;
        for(var resources:List.of(new ResourceBudget.Resources(2,1024,1024,2,0,2),new ResourceBudget.Resources(2,1024,0,2,2,2))){
            root=baseRoot.resolve("disk-"+resources.disk());Files.createDirectories(root);var h=new Harness();ExecutionAttempt prepared;
            try(var open=h.open()){
                prepared=open.prepare("retained","task",resources);open.enqueue(prepared);open.controller.start("retained");
                var stopped=open.controller.finish("retained");
                assertEquals(ExecutionAttempt.Phase.RELEASED,stopped.phase());
                assertEquals("STORAGE_RESERVATION_RETAINED",stopped.reason());
                assertTrue(open.queue.get().active().isEmpty());assertEquals(1,open.queue.get().retained().size());assertTrue(h.backend.running.isEmpty());
                assertEquals(ExecutionState.Phase.COLLECTING,open.tasks.get(prepared.launch().plan(),prepared.launch().task()).orElseThrow().phase());
            }
            try(var open=h.open()){
                assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.recover("retained").phase());
                assertTrue(open.queue.get().active().isEmpty());assertEquals(1,open.queue.get().retained().size());
                var next=open.prepare("waiting","independent",resources);open.enqueue(next);
                assertEquals(ExecutionAttempt.Phase.QUEUED,open.controller.start("waiting").phase());
                assertEquals(1,h.backend.launches);
                var compute=open.prepare("compute","compute");open.enqueue(compute);
                assertEquals(ExecutionAttempt.Phase.RUNNING,open.controller.start("compute").phase());
                open.controller.cancel("compute");assertEquals(1,open.queue.get().retained().size());
            }
        }
    }
    @Test void cancellationBeforeAnyLaunchReleasesUnusedStorageReservation()throws Exception{
        var h=new Harness();ExecutionAttempt prepared;
        try(var open=h.open(crashAt(ExecutionController.Checkpoint.ADMISSION_RESERVED))){
            prepared=open.prepare("never-launched","task",CAPACITY);open.enqueue(prepared);
            assertThrows(Crash.class,()->open.controller.start("never-launched"));
        }
        try(var open=h.open()){
            assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.cancel("never-launched").phase());
            assertTrue(open.queue.get().active().isEmpty());assertEquals(0,h.backend.launches);
            assertTrue(open.queue.get().retained().isEmpty());
        }
    }
    @Test void crashDuringComputeToStorageHandoffCannotLoseOrDoubleCountStorage()throws Exception{
        var base=root;
        for(var point:List.of(ExecutionController.Checkpoint.QUIESCENCE_COMMITTED,ExecutionController.Checkpoint.TASK_QUIESCENT,ExecutionController.Checkpoint.RESERVATION_RELEASED,ExecutionController.Checkpoint.ATTEMPT_CLOSED)){
            root=base.resolve(point.name());Files.createDirectories(root);var h=new Harness();ExecutionAttempt a;
            try(var open=h.open()){a=open.prepare("retained","task",CAPACITY);open.enqueue(a);open.controller.start("retained");}
            try(var open=h.open(crashAt(point))){assertThrows(Crash.class,()->open.controller.cancel("retained"));}
            try(var open=h.open()){
                assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.recover("retained").phase());
                assertTrue(open.queue.get().active().isEmpty());assertEquals(1,open.queue.get().retained().size());
                assertEquals(CAPACITY,open.queue.get().retained().getFirst().request().resources());
                assertEquals(1,h.backend.launches);assertFalse(open.tasks.get(a.launch().plan(),a.launch().task()).orElseThrow().reservesWorker());
            }
        }
    }
    @Test void recoveryAcrossEveryLaunchCommitBoundaryNeverRelaunchesOrLosesReservation()throws Exception{
        var baseRoot=root;
        for(var point:List.of(ExecutionController.Checkpoint.INTENT,ExecutionController.Checkpoint.QUEUE_SUBMITTED,ExecutionController.Checkpoint.ADMISSION_RESERVED,ExecutionController.Checkpoint.TASK_LEASED,ExecutionController.Checkpoint.LAUNCH_INTENT,ExecutionController.Checkpoint.DRIVER_LAUNCHED,ExecutionController.Checkpoint.HANDLE_COMMITTED,ExecutionController.Checkpoint.TASK_RUNNING)){
            root=baseRoot.resolve(point.name());Files.createDirectories(root);var h=new Harness();String attempt="attempt-"+point;ExecutionAttempt prepared;
            try(var open=h.open(crashAt(point))){
                prepared=open.prepare(attempt,"task");
                assertThrows(Crash.class,()->{open.enqueue(prepared);open.controller.start(attempt);});
            }
            int launches=h.backend.launches;
            try(var open=h.open()){
                var result=open.controller.recover(attempt);assertEquals(ExecutionAttempt.Phase.RELEASED,result.phase(),point.name());
                assertTrue(open.queue.get().active().isEmpty(),point.name());assertTrue(open.queue.get().pending().isEmpty(),point.name());
                assertEquals(launches,h.backend.launches);assertTrue(h.backend.running.isEmpty());
                var current=open.tasks.get(prepared.launch().plan(),prepared.launch().task()).orElseThrow();
                assertFalse(current.reservesWorker());assertNotEquals(ExecutionState.Phase.SUCCEEDED,current.phase());
            }
        }
    }
    @Test void finishRecoveryAcrossEveryReleaseBoundaryRetainsProofWithoutPublishingSuccess()throws Exception{
        var baseRoot=root;
        for(var point:List.of(ExecutionController.Checkpoint.STOP_INTENT,ExecutionController.Checkpoint.DRIVER_STOPPED,ExecutionController.Checkpoint.QUIESCENCE_COMMITTED,ExecutionController.Checkpoint.TASK_QUIESCENT,ExecutionController.Checkpoint.RESERVATION_RELEASED,ExecutionController.Checkpoint.ATTEMPT_CLOSED)){
            root=baseRoot.resolve(point.name());Files.createDirectories(root);var h=new Harness();ExecutionAttempt prepared;
            try(var open=h.open()){prepared=open.prepare("finish-"+point,"task");open.enqueue(prepared);open.controller.start(prepared.launch().attempt());}
            try(var open=h.open(crashAt(point))){assertThrows(Crash.class,()->open.controller.finish(prepared.launch().attempt()));}
            try(var open=h.open()){
                var result=open.controller.recover(prepared.launch().attempt());assertEquals(ExecutionAttempt.Phase.RELEASED,result.phase());
                assertEquals(ExecutionAttempt.Stop.FINISH,result.stop());assertTrue(open.queue.get().active().isEmpty());
                var state=open.tasks.get(prepared.launch().plan(),prepared.launch().task()).orElseThrow();assertEquals(ExecutionState.Phase.COLLECTING,state.phase());assertTrue(state.result().isEmpty());assertTrue(state.termination().isPresent());
                assertEquals(1,h.backend.launches);assertTrue(h.backend.running.isEmpty());
            }
        }
    }
    @Test void unknownRecoveryRetainsCapacityUntilAuthoritativeProofArrives()throws Exception{
        var h=new Harness();ExecutionAttempt prepared;
        try(var open=h.open(crashAt(ExecutionController.Checkpoint.DRIVER_LAUNCHED))){prepared=open.prepare("partitioned","task");open.enqueue(prepared);assertThrows(Crash.class,()->open.controller.start("partitioned"));}
        h.backend.unknown=true;
        try(var open=h.open()){
            assertEquals(ExecutionAttempt.Phase.QUARANTINED,open.controller.recover("partitioned").phase());assertEquals(1,open.queue.get().active().size());
            assertTrue(open.tasks.get(prepared.launch().plan(),prepared.launch().task()).orElseThrow().reservesWorker());
            var held=open.tasks.get(prepared.launch().plan(),prepared.launch().task()).orElseThrow();
            assertEquals("NOT_READY",assertThrows(IllegalArgumentException.class,()->held.lease(held.revision(),"retry")).getMessage());
        }
        h.backend.unknown=false;
        try(var open=h.open()){assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.recover("partitioned").phase());assertTrue(open.queue.get().active().isEmpty());}
    }
    @Test void foreignHandleMissingProofAndUnregisteredGuestProofCannotReleaseCapacity()throws Exception{
        var baseRoot=root;
        for(String mode:List.of("foreign","missing","guest")){
            root=baseRoot.resolve(mode);Files.createDirectories(root);var h=new Harness();
            try(var open=h.open()){
                var a=open.prepare(mode,"task");open.enqueue(a);open.controller.start(mode);
                h.backend.foreign=mode.equals("foreign");h.backend.noProof=mode.equals("missing");h.backend.untrustedProof=mode.equals("guest");
                assertEquals(ExecutionAttempt.Phase.QUARANTINED,open.controller.cancel(mode).phase());assertEquals(1,open.queue.get().active().size());
                if(h.backend.foreign)assertEquals(0,h.backend.stops);
                h.backend.foreign=false;h.backend.noProof=false;h.backend.untrustedProof=false;
                assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.recover(mode).phase());assertTrue(open.queue.get().active().isEmpty());
            }
        }
    }
    @Test void cancelBeforeLaunchDoesNotTouchAnotherWorkersReservation()throws Exception{
        var h=new Harness();try(var open=h.open()){
            var running=open.prepare("running","first");open.enqueue(running);open.controller.start("running");
            var waiting=open.prepare("waiting","second");open.enqueue(waiting);assertEquals(ExecutionAttempt.Phase.QUEUED,open.controller.start("waiting").phase());
            assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.cancel("waiting").phase());assertEquals(1,open.queue.get().active().size());assertEquals(1,h.backend.launches);assertEquals(0,h.backend.stops);
            assertEquals(ExecutionState.Phase.CANCELLED,open.tasks.get(waiting.launch().plan(),waiting.launch().task()).orElseThrow().phase());
            open.controller.cancel("running");
        }
    }
    @Test void duplicateSubmissionAndLaunchAreIdempotentAndSecondControllerIsExcluded()throws Exception{
        var h=new Harness();try(var open=h.open()){
            var a=open.prepare("idempotent","task");var queued=open.enqueue(a);assertEquals(queued,open.enqueue(a));
            var running=open.controller.start("idempotent");assertEquals(running,open.controller.start("idempotent"));assertEquals(1,h.backend.launches);
            assertThrows(IOException.class,()->new DurableAttemptStore(root.resolve("attempts"),h.cas));
            var altered=new SandboxExecutor.Launch(a.launch().plan(),a.launch().task(),d("other-recipe"),a.launch().profile(),h.pin,a.launch().policy(),a.launch().attempt(),1);
            assertThrows(IOException.class,()->open.controller.enqueue(altered,a.admission(),a.expectedTask()));
            open.controller.cancel("idempotent");
        }
    }
    @Test void intentScopedProofCannotBeReusedForAnotherFenceOrLaunch()throws Exception{
        var h=new Harness();try(var open=h.open()){
            var a=open.prepare("one","task");var b=open.prepare("two","other");
            var proof=h.backend.proof(a.launch(),"absent",ControllerTerminationLedger.Disposition.ABSENT);
            open.ledger.require(a.launch(),proof);assertThrows(IOException.class,()->open.ledger.require(b.launch(),proof));
            assertThrows(IOException.class,()->open.ledger.require(a.launch(),d("invented")));
        }
    }
    @Test void failedFinishRecoveryEventuallyReleasesCapacityWithoutInventingSuccess()throws Exception{
        var h=new Harness();try(var open=h.open()){
            var a=open.prepare("finish-partition","task");open.enqueue(a);open.controller.start(a.launch().attempt());
            h.backend.unknown=true;assertEquals(ExecutionAttempt.Phase.QUARANTINED,open.controller.finish(a.launch().attempt()).phase());
            h.backend.unknown=false;
            assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.recover(a.launch().attempt()).phase());
            var state=open.tasks.get(a.launch().plan(),a.launch().task()).orElseThrow();assertEquals(ExecutionState.Phase.FAILED,state.phase());assertFalse(state.reservesWorker());assertTrue(open.queue.get().active().isEmpty());
        }
    }
    @Test void prelaunchCancellationSurvivesStopIntentCrashWithoutNeedingBackendDiscovery()throws Exception{
        var h=new Harness();
        try(var open=h.open()){open.enqueue(open.prepare("pending-cancel","task"));}
        try(var open=h.open(crashAt(ExecutionController.Checkpoint.STOP_INTENT))){assertThrows(Crash.class,()->open.controller.cancel("pending-cancel"));}
        h.backend.unknown=true;
        try(var open=h.open()){assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.recover("pending-cancel").phase());assertTrue(open.queue.get().pending().isEmpty());assertEquals(0,h.backend.launches);assertEquals(0,h.backend.stops);}
    }
    @Test void lateClosingAnOldReleasedReservationCannotClobberANewerFence()throws Exception{
        var h=new Harness();ExecutionAttempt old;
        try(var open=h.open()){old=open.prepare("old","task");open.enqueue(old);open.controller.start("old");}
        try(var open=h.open(crashAt(ExecutionController.Checkpoint.RESERVATION_RELEASED))){assertThrows(Crash.class,()->open.controller.recover("old"));}
        try(var open=h.open()){
            var newer=open.prepare("new","task");assertEquals(2,newer.launch().fence());open.enqueue(newer);open.controller.start("new");
            assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.recover("old").phase());
            assertEquals(1,open.queue.get().active().size());assertTrue(h.backend.running.contains("new"));
            assertEquals(Optional.of("new"),open.tasks.get(newer.launch().plan(),newer.launch().task()).orElseThrow().attempt());
            open.controller.cancel("new");
        }
    }
    @Test void missingImmutableInputsCannotReachLaunchOrReserveAWorker()throws Exception{
        var h=new Harness();try(var open=h.open()){
            var a=open.prepare("missing-inputs","task");var l=a.launch();
            var unavailable=new SandboxExecutor.Launch(l.plan(),l.task(),l.recipe(),l.profile(),new ContentHandle(d("missing-input"),42),l.policy(),l.attempt(),l.fence());
            var result=open.controller.enqueue(unavailable,a.admission(),a.expectedTask());
            assertEquals(ExecutionAttempt.Phase.QUARANTINED,result.phase());assertEquals("IMMUTABLE_INPUTS_UNAVAILABLE",result.reason());
            assertTrue(open.queue.get().pending().isEmpty());assertTrue(open.queue.get().active().isEmpty());assertEquals(0,h.backend.launches);
            assertEquals(ExecutionState.Phase.WAITING_FOR_INPUT,open.tasks.get(l.plan(),l.task()).orElseThrow().phase());
        }
    }
    @Test void abruptJvmHaltReleasesOsLocksAndFreshControllerReapsUnpublishedHandle()throws Exception{
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(javaExecutable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ExecutionControllerTest.class.getName(),root.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(77,process.exitValue());}
        finally{if(process.isAlive())process.destroyForcibly();}
        assertTrue(Files.isRegularFile(root.resolve("simulated-worker.json")));
        var backend=new PersistentBackend(root);var h=new Harness(backend);
        try(var open=h.open()){
            assertEquals(ExecutionAttempt.Phase.LAUNCH_INTENT,open.attempts.get("process-owned").phase());assertEquals(1,open.queue.get().active().size());
            assertEquals(ExecutionAttempt.Phase.RELEASED,open.controller.recover("process-owned").phase());
            assertTrue(open.queue.get().active().isEmpty());assertEquals(0,backend.launches);assertEquals(1,backend.stops);
            assertEquals("Off",Files.readString(root.resolve("simulated-worker-stopped")));
        }
    }
    @Test void corruptAttemptCannotPreventRecoveryOfIndependentWorkInTheSamePage()throws Exception{
        var h=new Harness();try(var open=h.open()){
            open.enqueue(open.prepare("corrupt","one"));open.enqueue(open.prepare("intact","two"));
            var page=open.attempts.page(0,100,0);
            var handle=page.entries().getFirst().record().payload().orElseThrow();String hex=handle.digest().value().substring(7);
            // Deliberate bitrot in an authored disposable test CAS, never in native evidence.
            Files.writeString(root.resolve("cas/sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2)),"corrupt");
            var recovered=assertDoesNotThrow(()->open.controller.recoverPage(0,100,0));
            assertEquals(2,recovered.outcomes().size());assertTrue(recovered.outcomes().getFirst().result().isEmpty());assertEquals("CAS_UNAVAILABLE",recovered.outcomes().getFirst().reason());
            assertEquals(ExecutionAttempt.Phase.RELEASED,open.attempts.get("intact").phase());
            assertEquals(1,open.queue.get().pending().size());
        }
    }
    @Test void paginatedRecoveryKeepsAStableFrontierWhileItsOwnCommitsMoveRows()throws Exception{
        var h=new Harness();try(var open=h.open()){
            for(int i=0;i<7;i++)open.enqueue(open.prepare("paged-"+i,"task-"+i));
            long cursor=0,high=0;var visited=new HashSet<String>();
            while(true){
                var page=open.controller.recoverPage(cursor,2,high);high=page.frontier().highWaterSequence();
                for(var outcome:page.outcomes()){assertTrue(visited.add(outcome.source().id()));assertEquals(ExecutionAttempt.Phase.RELEASED,outcome.result().orElseThrow().phase());}
                if(page.frontier().continuation().isEmpty())break;
                cursor=page.frontier().continuation().orElseThrow();
            }
            assertEquals(7,visited.size());assertTrue(open.queue.get().pending().isEmpty());assertEquals(0,h.backend.launches);
        }
    }
    public static void main(String[] args)throws Exception{
        var test=new ExecutionControllerTest();test.root=Path.of(args[0]);
        var harness=test.new Harness(new PersistentBackend(test.root));
        try(var open=harness.open(point->{if(point==ExecutionController.Checkpoint.DRIVER_LAUNCHED)Runtime.getRuntime().halt(77);})){
            open.enqueue(open.prepare("process-owned","task"));open.controller.start("process-owned");
        }
        throw new AssertionError("CRASH_CHECKPOINT_NOT_REACHED");
    }
}
