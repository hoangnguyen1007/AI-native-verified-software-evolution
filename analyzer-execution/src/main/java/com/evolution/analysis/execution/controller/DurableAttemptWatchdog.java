package com.evolution.analysis.execution.controller;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.broker.ArtifactBroker;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Controller-owned operational guard. Counters come from trusted observers, never guest logs.
 * STOP_REQUIRED is durable before reaping; restart cannot renew the original work window.
 * An external service must invoke poll independently of target progress. Counter observation is
 * not a physical disk quota, a VM qualification certificate or semantic evidence.
 */
public final class DurableAttemptWatchdog implements AutoCloseable {
    public record Policy(long leaseMillis,long idleMillis,long workUnits,long logBytes,long diskBytes){
        public Policy{if(leaseMillis<1||idleMillis<1||workUnits<1||logBytes<0||diskBytes<0)throw new IllegalArgumentException("INVALID_WATCHDOG_POLICY");}
    }
    public record Observation(long progressSequence,long workUnits,long logBytes,long diskBytes){
        public Observation{if(progressSequence<0||workUnits<0||logBytes<0||diskBytes<0)throw new IllegalArgumentException("INVALID_WATCHDOG_OBSERVATION");}
    }
    public record State(String schema,long revision,ContentDigest launch,Policy policy,boolean activated,Instant started,Instant observed,Instant progressed,Observation counters,String stopReason){
        public State{
            if(!"execution-work-guard:m8.4-v1".equals(schema)||revision<0)throw new IllegalArgumentException("INVALID_WATCHDOG_RECORD");
            Objects.requireNonNull(launch);Objects.requireNonNull(policy);Objects.requireNonNull(started);Objects.requireNonNull(observed);Objects.requireNonNull(progressed);Objects.requireNonNull(counters);
            if(observed.isBefore(started)||progressed.isBefore(started)||progressed.isAfter(observed)||!REASONS.contains(stopReason))throw new IllegalArgumentException("INVALID_WATCHDOG_RECORD");
            started.plusMillis(policy.leaseMillis());progressed.plusMillis(policy.idleMillis());
        }
    }
    public record Outcome(State guard,Optional<ExecutionAttempt> attempt){public Outcome{Objects.requireNonNull(guard);Objects.requireNonNull(attempt);}}
    private static final Set<String> REASONS=Set.of("","CLOCK_ROLLBACK","LEASE_EXPIRED","IDLE_EXPIRED","WORK_BUDGET_EXHAUSTED","LOG_BUDGET_EXHAUSTED","DISK_BUDGET_EXHAUSTED","COUNTER_REGRESSION","UNGUARDED_LAUNCH");
    private final SqliteInventoryStore journal;
    private final StreamingContentStore content;
    private final DurableAttemptStore attempts;
    private final ExecutionJournal tasks;
    private final Clock clock;
    public DurableAttemptWatchdog(Path root,StreamingContentStore content,DurableAttemptStore attempts,ExecutionJournal tasks,Clock clock)throws IOException{
        this.content=Objects.requireNonNull(content);this.attempts=Objects.requireNonNull(attempts);this.tasks=Objects.requireNonNull(tasks);this.clock=Objects.requireNonNull(clock);journal=new SqliteInventoryStore(root,content);
    }
    private static String id(SandboxExecutor.Launch launch){return "work-guard-"+CanonicalJson.sha256(launch).value().substring(7);}
    private ContentHandle put(State state)throws IOException{return content.put(new ByteArrayInputStream(CanonicalJson.write(state).getBytes(StandardCharsets.UTF_8)),()->false);}
    private static void fields(Map<String,Object> map,String... names){if(!map.keySet().equals(Set.of(names)))throw new IllegalArgumentException("WATCHDOG_FIELDS_INVALID");}
    private static long number(Object value){return ((BigDecimal)value).longValueExact();}
    private State decode(JobJournal.Record row,SandboxExecutor.Launch launch)throws IOException{
        var handle=row.payload().orElseThrow(()->new IOException("WATCHDOG_RECORD_MISSING"));
        if(handle.size()>8192||!content.verify(handle))throw new IOException("WATCHDOG_RECORD_CORRUPT");
        try(var input=content.open(handle)){
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            var m=GraphJson.object(GraphJson.read(new InputStreamReader(input,decoder),new GraphJson.Limits(8192,8,128)));
            fields(m,"schema","revision","launch","policy","activated","started","observed","progressed","counters","stopReason");
            var p=GraphJson.object(m.get("policy"));fields(p,"leaseMillis","idleMillis","workUnits","logBytes","diskBytes");
            var c=GraphJson.object(m.get("counters"));fields(c,"progressSequence","workUnits","logBytes","diskBytes");
            var state=new State(GraphJson.string(m.get("schema")),number(m.get("revision")),new ContentDigest(GraphJson.string(m.get("launch"))),
                    new Policy(number(p.get("leaseMillis")),number(p.get("idleMillis")),number(p.get("workUnits")),number(p.get("logBytes")),number(p.get("diskBytes"))),
                    bool(m.get("activated")),
                    Instant.parse(GraphJson.string(m.get("started"))),Instant.parse(GraphJson.string(m.get("observed"))),Instant.parse(GraphJson.string(m.get("progressed"))),
                    new Observation(number(c.get("progressSequence")),number(c.get("workUnits")),number(c.get("logBytes")),number(c.get("diskBytes"))),GraphJson.string(m.get("stopReason")));
            if(!state.launch().equals(CanonicalJson.sha256(launch))||!CanonicalJson.sha256(state.policy()).equals(launch.policy())||state.revision()!=row.revision()||!row.id().equals(id(launch)))throw new IOException("WATCHDOG_SCOPE_MISMATCH");
            return state;
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException|DateTimeException invalid){throw new IOException("WATCHDOG_RECORD_CORRUPT",invalid);}
    }
    private static boolean bool(Object value){if(!(value instanceof Boolean b))throw new IllegalArgumentException("WATCHDOG_BOOLEAN_REQUIRED");return b;}
    private ExecutionAttempt exact(SandboxExecutor.Launch launch)throws IOException{
        var attempt=attempts.get(launch.attempt());if(!attempt.launch().equals(launch))throw new IOException("WATCHDOG_LAUNCH_MISMATCH");return attempt;
    }
    public synchronized State register(SandboxExecutor.Launch launch,Policy policy)throws IOException{
        Objects.requireNonNull(policy);var attempt=exact(launch);
        if(!CanonicalJson.sha256(policy).equals(launch.policy()))throw new IOException("WATCHDOG_POLICY_MISMATCH");
        var existing=journal.get(id(launch));if(existing.isPresent())return decode(existing.orElseThrow(),launch);
        if(attempt.stop()!=ExecutionAttempt.Stop.NONE||attempt.launchPossible()||attempt.phase()==ExecutionAttempt.Phase.QUARANTINED)throw new IOException("WATCHDOG_PRELAUNCH_REQUIRED");
        if(policy.logBytes()>attempt.admission().resources().log()||policy.diskBytes()>attempt.admission().resources().disk())throw new IOException("WATCHDOG_RESERVATION_TOO_SMALL");
        var now=clock.instant();State state;
        try{state=new State("execution-work-guard:m8.4-v1",0,CanonicalJson.sha256(launch),policy,false,now,now,now,new Observation(0,0,0,0),"");}
        catch(DateTimeException invalid){throw new IOException("WATCHDOG_TIME_OUT_OF_RANGE",invalid);}
        return decode(journal.create(id(launch),state.launch(),Optional.of(put(state))),launch);
    }
    public synchronized State get(SandboxExecutor.Launch launch)throws IOException{exact(launch);return decode(journal.get(id(launch)).orElseThrow(()->new IOException("WATCHDOG_NOT_REGISTERED")),launch);}
    /** Called by ExecutionController's LaunchGuard after reservation/task lease and before launch intent. */
    public synchronized State activate(SandboxExecutor.Launch launch)throws IOException{
        var previous=get(launch);
        if(previous.activated()){
            if(!due(previous,clock.instant(),previous.counters()).isEmpty()){
                observe(launch,previous.counters());throw new IOException("WATCHDOG_ALREADY_EXPIRED");
            }
            return previous;
        }
        var a=exact(launch);var task=tasks.get(launch.plan(),launch.task()).orElseThrow(()->new IOException("WATCHDOG_TASK_MISSING"));
        if(!previous.stopReason().isEmpty()||a.stop()!=ExecutionAttempt.Stop.NONE||a.phase()!=ExecutionAttempt.Phase.LEASED
                ||task.phase()!=ExecutionState.Phase.LEASED||task.fence()!=launch.fence()||!task.attempt().equals(Optional.of(launch.attempt())))throw new IOException("WATCHDOG_ACTIVATION_LEASE_REQUIRED");
        var now=clock.instant();
        try{return commit(launch,previous,new State(previous.schema(),Math.addExact(previous.revision(),1),previous.launch(),previous.policy(),true,now,now,now,previous.counters(),""));}
        catch(ArithmeticException|DateTimeException invalid){throw new IOException("WATCHDOG_FRONTIER_OUT_OF_RANGE",invalid);}
    }
    private State commit(SandboxExecutor.Launch launch,State previous,State state)throws IOException{
        return decode(journal.transition(id(launch),previous.revision(),state.stopReason().isEmpty()?JobJournal.State.RUNNING:JobJournal.State.FAILED,"CONTROLLER_WORK_GUARD",Optional.of(put(state))),launch);
    }
    private static String due(State state,Instant now,Observation next){
        if(!state.stopReason().isEmpty())return state.stopReason();
        if(now.isBefore(state.observed()))return "CLOCK_ROLLBACK";
        if(!now.isBefore(state.started().plusMillis(state.policy().leaseMillis())))return "LEASE_EXPIRED";
        if(!now.isBefore(state.progressed().plusMillis(state.policy().idleMillis())))return "IDLE_EXPIRED";
        var old=state.counters();
        if(next.progressSequence()<old.progressSequence()||next.workUnits()<old.workUnits()||next.logBytes()<old.logBytes())return "COUNTER_REGRESSION";
        if(next.workUnits()>=state.policy().workUnits())return "WORK_BUDGET_EXHAUSTED";
        if(next.logBytes()>state.policy().logBytes())return "LOG_BUDGET_EXHAUSTED";
        if(next.diskBytes()>state.policy().diskBytes())return "DISK_BUDGET_EXHAUSTED";
        return "";
    }
    public synchronized State observe(SandboxExecutor.Launch launch,Observation next)throws IOException{
        Objects.requireNonNull(next);var previous=get(launch);if(!previous.stopReason().isEmpty())return previous;
        if(!previous.activated())throw new IOException("WATCHDOG_ACTIVATION_REQUIRED");
        Instant now=clock.instant();String reason=due(previous,now,next);
        // Freeze the last valid frontier on regression/clock rollback; a failure cannot erase it.
        if(reason.equals("COUNTER_REGRESSION")||reason.equals("CLOCK_ROLLBACK")){now=previous.observed();next=previous.counters();}
        var counters=new Observation(next.progressSequence(),next.workUnits(),next.logBytes(),Math.max(next.diskBytes(),previous.counters().diskBytes()));
        Instant progress=reason.isEmpty()&&next.progressSequence()>previous.counters().progressSequence()?now:previous.progressed();
        State state;
        try{state=new State(previous.schema(),Math.addExact(previous.revision(),1),previous.launch(),previous.policy(),true,previous.started(),now,progress,counters,reason);}
        catch(ArithmeticException|DateTimeException invalid){throw new IOException("WATCHDOG_FRONTIER_OUT_OF_RANGE",invalid);}
        if(state.observed().equals(previous.observed())&&state.progressed().equals(previous.progressed())&&state.counters().equals(previous.counters())&&reason.equals(previous.stopReason()))return previous;
        return commit(launch,previous,state);
    }
    /** The stop latch precedes driver operations; UNKNOWN termination keeps its reservation. */
    private synchronized State pollState(SandboxExecutor.Launch launch)throws IOException{
        var state=get(launch);
        if(!state.activated()){
            if(!exact(launch).launchPossible())return state;
            if(state.stopReason().isEmpty()){
                try{state=commit(launch,state,new State(state.schema(),Math.addExact(state.revision(),1),state.launch(),state.policy(),false,state.started(),state.observed(),state.progressed(),state.counters(),"UNGUARDED_LAUNCH"));}
                catch(ArithmeticException invalid){throw new IOException("WATCHDOG_FRONTIER_OUT_OF_RANGE",invalid);}
            }
        }else state=observe(launch,state.counters());
        return state;
    }
    public Outcome poll(SandboxExecutor.Launch launch,ExecutionController controller)throws IOException{
        var state=pollState(launch);
        // Launch activation acquires controller -> guard. Never hold guard -> controller here.
        return new Outcome(state,state.stopReason().isEmpty()?Optional.empty():Optional.of(controller.recover(launch)));
    }
    public ArtifactBroker.Authority authority(SandboxExecutor.Launch binding){
        var controller=new ControllerBrokerAuthority(attempts,tasks,binding);
        return (attempt,fence)->{
            controller.require(attempt,fence);
            synchronized(this){var state=get(binding);if(!state.activated())throw new IOException("BROKER_WORK_GUARD_NOT_ACTIVATED");
            if(!due(state,clock.instant(),state.counters()).isEmpty()){
                observe(binding,state.counters());throw new IOException("BROKER_WORK_GUARD_STOP_REQUIRED");
            }}
        };
    }
    @Override public void close()throws IOException{journal.close();}
}
