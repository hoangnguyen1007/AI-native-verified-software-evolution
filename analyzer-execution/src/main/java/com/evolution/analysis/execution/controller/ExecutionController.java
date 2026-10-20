package com.evolution.analysis.execution.controller;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.qualification.QualifiedSandboxExecutor;
import com.evolution.analysis.filesystem.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import static com.evolution.analysis.execution.ExecutionAttempt.Phase.*;

/** Internal orchestration of prevalidated task requests. Not a public command/plan acceptance API.
 * The exclusive attempt store is held together with the task/admission stores for this controller's lifetime.
 * Reservation and task state are separate recoverable commits: intent precedes both; quiescence precedes release.
 */
public final class ExecutionController {
    public enum Checkpoint { INTENT,QUEUE_SUBMITTED,ADMISSION_RESERVED,TASK_LEASED,LAUNCH_INTENT,DRIVER_LAUNCHED,HANDLE_COMMITTED,TASK_RUNNING,STOP_INTENT,DRIVER_STOPPED,QUIESCENCE_COMMITTED,TASK_QUIESCENT,RESERVATION_RELEASED,ATTEMPT_CLOSED }
    private final DurableAttemptStore attempts;
    private final DurableAdmissionQueue queue;
    private final ExecutionJournal tasks;
    private final QualifiedSandboxExecutor executor;
    private final ControllerTerminationLedger terminations;
    private final StreamingContentStore content;
    private final Consumer<Checkpoint> checkpoint;
    @FunctionalInterface public interface LaunchGuard {void beforeLaunch(SandboxExecutor.Launch launch)throws IOException;}
    private final LaunchGuard launchGuard;
    public ExecutionController(DurableAttemptStore attempts,DurableAdmissionQueue queue,ExecutionJournal tasks,QualifiedSandboxExecutor executor,ControllerTerminationLedger terminations,StreamingContentStore content){this(attempts,queue,tasks,executor,terminations,content,p->{});}
    public ExecutionController(DurableAttemptStore attempts,DurableAdmissionQueue queue,ExecutionJournal tasks,QualifiedSandboxExecutor executor,ControllerTerminationLedger terminations,StreamingContentStore content,Consumer<Checkpoint> checkpoint){
        this(attempts,queue,tasks,executor,terminations,content,checkpoint,launch->{});
    }
    public ExecutionController(DurableAttemptStore attempts,DurableAdmissionQueue queue,ExecutionJournal tasks,QualifiedSandboxExecutor executor,ControllerTerminationLedger terminations,StreamingContentStore content,Consumer<Checkpoint> checkpoint,LaunchGuard launchGuard){
        this.attempts=Objects.requireNonNull(attempts);this.queue=Objects.requireNonNull(queue);this.tasks=Objects.requireNonNull(tasks);this.executor=Objects.requireNonNull(executor);this.terminations=Objects.requireNonNull(terminations);this.content=Objects.requireNonNull(content);this.checkpoint=Objects.requireNonNull(checkpoint);this.launchGuard=Objects.requireNonNull(launchGuard);
    }
    public synchronized ExecutionAttempt enqueue(SandboxExecutor.Launch launch,AdmissionQueue.Request admission,ExecutionState expected)throws IOException{
        var a=attempts.create(ExecutionAttempt.prepare(launch,admission,expected));checkpoint.accept(Checkpoint.INTENT);
        if(a.phase()!=PREPARED)return a;
        if(!task(a).equals(expected))return quarantine(a,"TASK_PRECONDITION_CHANGED");
        if(!inputsAvailable(a)){
            if(expected.phase()==ExecutionState.Phase.READY)advance(a,expected,ExecutionState.Phase.WAITING_FOR_INPUT,Optional.empty());
            return quarantine(a,"IMMUTABLE_INPUTS_UNAVAILABLE");
        }
        queue.submit(queue.get().revision(),admission);checkpoint.accept(Checkpoint.QUEUE_SUBMITTED);
        return step(a,QUEUED,a.handle(),Optional.empty(),a.stop(),"");
    }
    public record Snapshot(Optional<ExecutionAttempt> attempt,ExecutionState task){}
    /** Read persisted state; no guest command or implicit recovery is triggered by a status query. */
    public synchronized Snapshot snapshot(SandboxExecutor.Launch launch)throws IOException{
        var current=attempts.find(launch.attempt());
        if(current.isPresent()&&!current.orElseThrow().launch().equals(launch))throw new RegisteredExecutionPort.Rejected(RegisteredExecutionPort.Failure.SCOPE_CONFLICT);
        var task=tasks.get(launch.plan(),launch.task()).orElseThrow(()->new IOException("TASK_NOT_FOUND"));
        if(task.fence()>launch.fence()||task.fence()==launch.fence()&&!task.attempt().equals(Optional.of(launch.attempt())))
            throw new RegisteredExecutionPort.Rejected(RegisteredExecutionPort.Failure.SCOPE_CONFLICT);
        return new Snapshot(current,task);
    }
    /** A slow remote probe cannot hold the durable controller lock ahead of cancellation. */
    public SandboxExecutor.Availability availability(){
        try{return executor.probe().availability();}catch(IOException unavailable){return SandboxExecutor.Availability.UNAVAILABLE;}
    }
    public synchronized ExecutionAttempt start(String attempt)throws IOException{
        var a=attempts.get(attempt);
        if(a.phase()==RELEASED||a.phase()==RUNNING)return a;
        if(a.stop()!=ExecutionAttempt.Stop.NONE||a.phase()==LAUNCH_INTENT||a.phase()==QUARANTINED)return recover(attempt);
        if(a.phase()==PREPARED)a=enqueue(a.launch(),a.admission(),a.expectedTask());
        if(a.phase()!=QUEUED&&a.phase()!=RESERVED&&a.phase()!=LEASED)return a;
        if(a.phase()==QUEUED){
            queue.dispatch(queue.get().revision());
            if(!reserved(a))return a;
            a=step(a,RESERVED,a.handle(),Optional.empty(),a.stop(),"");checkpoint.accept(Checkpoint.ADMISSION_RESERVED);
        }
        if(!reserved(a))return quarantine(a,"ADMISSION_RESERVATION_MISSING");
        if(!inputsAvailable(a))return quarantine(a,"IMMUTABLE_INPUTS_UNAVAILABLE");
        var current=lease(a);
        if(!owned(a,current)||current.phase()!=ExecutionState.Phase.LEASED)return quarantine(a,"TASK_LEASE_SCOPE_MISMATCH");
        if(a.phase()==RESERVED)a=step(a,LEASED,a.handle(),Optional.empty(),a.stop(),"");
        try{launchGuard.beforeLaunch(a.launch());}catch(IOException rejected){return quarantine(a,"LAUNCH_GUARD_REJECTED");}
        a=step(a,LAUNCH_INTENT,a.handle(),Optional.empty(),a.stop(),"");checkpoint.accept(Checkpoint.LAUNCH_INTENT);
        SandboxExecutor.Handle handle;
        try{handle=executor.launch(a.launch());}catch(IOException unavailable){return quarantine(a,"LAUNCH_DISPOSITION_UNKNOWN");}
        checkpoint.accept(Checkpoint.DRIVER_LAUNCHED);
        if(!matches(a,handle))return quarantine(a,"DRIVER_HANDLE_SCOPE_MISMATCH");
        a=step(a,RUNNING,Optional.of(handle),Optional.empty(),a.stop(),"");checkpoint.accept(Checkpoint.HANDLE_COMMITTED);
        current=task(a);
        if(!owned(a,current)||current.phase()!=ExecutionState.Phase.LEASED)return quarantine(a,"TASK_LEASE_SCOPE_MISMATCH");
        tasks.compareAndSet(a.launch().plan(),current,current.advance(current.revision(),current.fence(),ExecutionState.Phase.RUNNING,"",Optional.empty(),Optional.empty()));checkpoint.accept(Checkpoint.TASK_RUNNING);
        return a;
    }
    /** Recovery always fences/reaps old work, never silently starts it again. */
    public synchronized ExecutionAttempt recover(String attempt)throws IOException{return stop(attempt,ExecutionAttempt.Stop.RECOVERY);}
    /** Composition boundaries must bind the full launch, not a possibly reused attempt label. */
    public synchronized ExecutionAttempt recover(SandboxExecutor.Launch launch)throws IOException{
        if(!attempts.get(launch.attempt()).launch().equals(launch))throw new IOException("RECOVERY_LAUNCH_SCOPE_MISMATCH");
        return recover(launch.attempt());
    }
    public synchronized ExecutionAttempt cancel(String attempt)throws IOException{return stop(attempt,ExecutionAttempt.Stop.CANCEL);}
    /** Finish seals worker quiescence for collection, never publishes success or semantic acceptance. */
    public synchronized ExecutionAttempt finish(String attempt)throws IOException{
        var a=attempts.get(attempt);
        if(a.phase()!=RUNNING&&a.stop()==ExecutionAttempt.Stop.NONE)throw new IOException("FINISH_REQUIRES_RUNNING_TASK");
        return stop(attempt,ExecutionAttempt.Stop.FINISH);
    }
    private ExecutionAttempt stop(String attempt,ExecutionAttempt.Stop requested)throws IOException{
        var a=attempts.get(attempt);if(a.phase()==RELEASED)return a;
        if(a.phase()==QUIESCENT)return release(a);
        boolean neverLaunched=a.handle().isEmpty()&&!a.launchPossible();
        var winner=a.stop()==ExecutionAttempt.Stop.NONE?requested:a.stop();
        if(a.phase()!=STOP_INTENT)a=step(a,STOP_INTENT,a.handle(),Optional.empty(),winner,"");
        checkpoint.accept(Checkpoint.STOP_INTENT);
        if(reserved(a)){
            var current=lease(a);
            if(!owned(a,current))return quarantine(a,"TASK_LEASE_SCOPE_MISMATCH");
        }else if(!neverLaunched)return quarantine(a,"ADMISSION_RESERVATION_MISSING");
        ContentDigest proof;
        try{
            if(neverLaunched){
                var raw=content.put(new ByteArrayInputStream(CanonicalJson.write(Map.of("intent",a,"queueRevision",queue.get().revision(),"disposition","NO_LAUNCH_CALL_PRECEDED_STOP_INTENT")).getBytes(StandardCharsets.UTF_8)),()->false);
                proof=terminations.record(a.launch(),"controller-intent",ControllerTerminationLedger.Disposition.NEVER_LAUNCHED,raw);
            }else{
                var found=executor.recover(a.launch());
                if(found.handle().isPresent()){
                    var handle=found.handle().orElseThrow();
                    if(!matches(a,handle)||a.handle().isPresent()&&!a.handle().orElseThrow().equals(handle))return quarantine(a,"RECOVERY_HANDLE_SCOPE_MISMATCH");
                    if(a.handle().isEmpty())a=step(a,STOP_INTENT,Optional.of(handle),Optional.empty(),winner,"");
                    var status=executor.terminate(handle);
                    if(!handle.equals(status.handle())||status.running()||status.termination().isEmpty())return quarantine(a,"TERMINATION_UNCONFIRMED");
                    proof=status.termination().orElseThrow();
                }else{
                    if(found.termination().isEmpty())return quarantine(a,"RECOVERY_DISPOSITION_UNKNOWN");
                    proof=found.termination().orElseThrow();
                }
                terminations.require(a.launch(),proof);checkpoint.accept(Checkpoint.DRIVER_STOPPED);
            }
        }catch(IOException unavailable){return quarantine(a,"TERMINATION_UNCONFIRMED");}
        a=step(a,QUIESCENT,a.handle(),Optional.of(proof),winner,"");checkpoint.accept(Checkpoint.QUIESCENCE_COMMITTED);
        return release(a);
    }
    private ExecutionAttempt release(ExecutionAttempt a)throws IOException{
        var proof=a.termination().orElseThrow();terminations.require(a.launch(),proof);
        var current=task(a);var state=queue.get();
        boolean active=state.active().stream().anyMatch(q->q.request().ticket().equals(a.admission().ticket()));
        var hold=state.retained().stream().filter(q->q.request().ticket().equals(a.admission().ticket())).findFirst();
        var retiredRow=state.retired().stream().filter(q->q.request().ticket().equals(a.admission().ticket())).findFirst()
                .or(()->hold.map(h->new AdmissionQueue.Retired(h.request(),h.sequence(),h.termination())));
        boolean retired=retiredRow.isPresent();
        if(active||retired){
            if(retired){
                var prior=retiredRow.orElseThrow();
                if(!prior.request().equals(a.admission())||!prior.termination().equals(proof))throw new IOException("ADMISSION_TERMINATION_CONFLICT");
                if(retainsStorage(a)&&hold.isEmpty())throw new IOException("ADMISSION_STORAGE_HOLD_MISSING");
            }
            if(!owned(a,current)&&!(retired&&!active&&current.fence()>a.launch().fence()))throw new IOException("TASK_LEASE_SCOPE_MISMATCH");
            if(owned(a,current)&&current.termination().isPresent()&&!current.termination().orElseThrow().equals(proof))throw new IOException("TASK_TERMINATION_CONFLICT");
            if(owned(a,current)&&current.termination().isEmpty()){
                if(a.stop()==ExecutionAttempt.Stop.FINISH){
                    if(current.phase()==ExecutionState.Phase.QUARANTINED||current.phase()==ExecutionState.Phase.FAILED)current=advance(a,current,ExecutionState.Phase.FAILED,Optional.of(proof));
                    else{
                        if(current.phase()==ExecutionState.Phase.LEASED)current=advance(a,current,ExecutionState.Phase.RUNNING,Optional.empty());
                        current=advance(a,current,ExecutionState.Phase.COLLECTING,Optional.of(proof));
                    }
                }else if(a.stop()==ExecutionAttempt.Stop.CANCEL){
                    if(current.phase()==ExecutionState.Phase.FAILED)current=advance(a,current,ExecutionState.Phase.FAILED,Optional.of(proof));
                    else{
                        if(current.phase()!=ExecutionState.Phase.CANCEL_REQUESTED&&current.phase()!=ExecutionState.Phase.QUARANTINED)current=advance(a,current,ExecutionState.Phase.CANCEL_REQUESTED,Optional.empty());
                        current=advance(a,current,ExecutionState.Phase.CANCELLED,Optional.of(proof));
                    }
                }else{
                    if(current.phase()!=ExecutionState.Phase.FAILED&&current.phase()!=ExecutionState.Phase.QUARANTINED)current=advance(a,current,ExecutionState.Phase.QUARANTINED,Optional.empty());
                    current=advance(a,current,ExecutionState.Phase.FAILED,Optional.of(proof));
                }
            }
            checkpoint.accept(Checkpoint.TASK_QUIESCENT);
            // One admission commit atomically retires compute and retains the full disk/log bounds.
            if(a.launchPossible())queue.release(state.revision(),a.admission().ticket(),Optional.of(proof));
            else queue.releaseUnlaunched(state.revision(),a.admission().ticket(),Optional.of(proof));
        }else{
            for(var pending:state.pending())if(pending.request().ticket().equals(a.admission().ticket())&&!pending.request().equals(a.admission()))throw new IOException("ADMISSION_REQUEST_CONFLICT");
            queue.cancelPending(state.revision(),a.admission().ticket());
            if(a.stop()==ExecutionAttempt.Stop.CANCEL&&current.equals(a.expectedTask())&&(current.phase()==ExecutionState.Phase.READY||current.phase()==ExecutionState.Phase.WAITING_FOR_INPUT||current.phase()==ExecutionState.Phase.WAITING_FOR_RESOURCES))advance(a,current,ExecutionState.Phase.CANCELLED,Optional.empty());
        }
        checkpoint.accept(Checkpoint.RESERVATION_RELEASED);
        var closed=step(a,RELEASED,a.handle(),a.termination(),a.stop(),retainsStorage(a)?"STORAGE_RESERVATION_RETAINED":"");checkpoint.accept(Checkpoint.ATTEMPT_CLOSED);return closed;
    }
    private static boolean retainsStorage(ExecutionAttempt a){
        var resources=a.admission().resources();
        return a.launchPossible()&&(resources.disk()>0||resources.log()>0);
    }
    private ExecutionState advance(ExecutionAttempt a,ExecutionState current,ExecutionState.Phase phase,Optional<ContentDigest> proof)throws IOException{
        try{return tasks.compareAndSet(a.launch().plan(),current,current.advance(current.revision(),current.fence(),phase,"CONTROLLER_"+a.stop(),proof,Optional.empty()));}
        catch(IllegalArgumentException invalid){throw new IOException("TASK_RECOVERY_TRANSITION_REJECTED",invalid);}
    }
    private ExecutionState lease(ExecutionAttempt a)throws IOException{
        var current=task(a);
        if(current.equals(a.expectedTask())){
            current=tasks.compareAndSet(a.launch().plan(),current,current.lease(current.revision(),a.launch().attempt()));checkpoint.accept(Checkpoint.TASK_LEASED);
        }
        return current;
    }
    private ExecutionState task(ExecutionAttempt a)throws IOException{return tasks.get(a.launch().plan(),a.launch().task()).orElseThrow(()->new IOException("EXECUTION_TASK_MISSING"));}
    private boolean inputsAvailable(ExecutionAttempt a){try{return content.verify(a.launch().immutableInputs());}catch(IOException unavailable){return false;}}
    private boolean reserved(ExecutionAttempt a)throws IOException{
        for(var row:queue.get().active())if(row.request().ticket().equals(a.admission().ticket())){
            if(!row.request().equals(a.admission()))throw new IOException("ADMISSION_REQUEST_CONFLICT");return true;
        }
        return false;
    }
    private static boolean owned(ExecutionAttempt a,ExecutionState s){return s.fence()==a.launch().fence()&&s.attempt().equals(Optional.of(a.launch().attempt()));}
    private static boolean matches(ExecutionAttempt a,SandboxExecutor.Handle handle){return handle.attempt().equals(a.launch().attempt())&&handle.fence()==a.launch().fence();}
    private ExecutionAttempt step(ExecutionAttempt a,ExecutionAttempt.Phase phase,Optional<SandboxExecutor.Handle> handle,Optional<ContentDigest> proof,ExecutionAttempt.Stop stop,String reason)throws IOException{return attempts.commit(a,a.next(phase,handle,proof,stop,reason));}
    private ExecutionAttempt quarantine(ExecutionAttempt a,String reason)throws IOException{
        var current=task(a);
        if(owned(a,current)&&Set.of(ExecutionState.Phase.LEASED,ExecutionState.Phase.RUNNING,ExecutionState.Phase.COLLECTING,ExecutionState.Phase.VALIDATING,ExecutionState.Phase.CANCEL_REQUESTED).contains(current.phase()))advance(a,current,ExecutionState.Phase.QUARANTINED,Optional.empty());
        if(a.phase()==QUARANTINED&&a.reason().equals(reason))return a;
        return step(a,QUARANTINED,a.handle(),Optional.empty(),a.stop(),reason);
    }
    /** Replay page keeps its captured high-water mark; it never allocates a whole-repository recovery list. */
    public record RecoveryOutcome(JobJournal.Record source,Optional<ExecutionAttempt> result,String reason){
        public RecoveryOutcome{Objects.requireNonNull(source);Objects.requireNonNull(result);Objects.requireNonNull(reason);}
    }
    public record RecoveryPage(JobJournal.HistoryPage frontier,List<RecoveryOutcome> outcomes){
        public RecoveryPage{Objects.requireNonNull(frontier);outcomes=List.copyOf(outcomes);if(frontier.entries().size()!=outcomes.size())throw new IllegalArgumentException("RECOVERY_DENOMINATOR_MISMATCH");}
    }
    public synchronized RecoveryPage recoverPage(long cursor,int size,long highWater)throws IOException{
        var page=attempts.page(cursor,size,highWater);
        var outcomes=new ArrayList<RecoveryOutcome>();
        for(var row:page.entries()){
            try{
                var a=attempts.record(row.record());if(a.phase()!=RELEASED)a=recover(a.launch().attempt());
                outcomes.add(new RecoveryOutcome(row.record(),Optional.of(a),a.reason()));
            }catch(IOException unavailable){
                String message=unavailable.getMessage();
                String reason=message!=null&&Set.of("CAS_UNAVAILABLE","ATTEMPT_RECORD_CORRUPT","EXECUTION_TASK_MISSING","TERMINATION_RECORD_CORRUPT","TASK_LEASE_SCOPE_MISMATCH","TASK_TERMINATION_CONFLICT","ADMISSION_TERMINATION_CONFLICT").contains(message)?message:"RECOVERY_RETRY_REQUIRED";
                outcomes.add(new RecoveryOutcome(row.record(),Optional.empty(),reason));
            }
        }
        return new RecoveryPage(page,outcomes);
    }
}
