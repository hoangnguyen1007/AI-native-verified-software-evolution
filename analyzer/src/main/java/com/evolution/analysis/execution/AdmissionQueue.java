package com.evolution.analysis.execution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/** Pure, persisted admission projection. Reserve before leasing/launching; recovery retains every active
 * reservation until the trusted controller supplies authoritative quiescence. This is not semantic evidence.
 */
public final class AdmissionQueue {
    public record Ticket(ContentDigest execution,ContentDigest task,long fence) implements Comparable<Ticket> {
        public Ticket {Objects.requireNonNull(execution);Objects.requireNonNull(task);if(fence<1)throw new IllegalArgumentException("INVALID_ADMISSION_FENCE");}
        @Override public int compareTo(Ticket b){int order=execution.compareTo(b.execution);if(order==0)order=task.compareTo(b.task);return order==0?Long.compare(fence,b.fence):order;}
    }
    public record Request(Ticket ticket,ContentDigest job,ResourceBudget.Resources resources,long priority) {
        public Request {Objects.requireNonNull(ticket);Objects.requireNonNull(job);Objects.requireNonNull(resources);if(priority<0||resources.equals(ZERO))throw new IllegalArgumentException("INVALID_ADMISSION_REQUEST");}
    }
    public record Queued(Request request,long sequence,int bypasses) {
        public Queued {Objects.requireNonNull(request);if(sequence<0||bypasses<0)throw new IllegalArgumentException("INVALID_ADMISSION_SEQUENCE");}
    }
    public record Retired(Request request,long sequence,ContentDigest termination) {
        public Retired {Objects.requireNonNull(request);Objects.requireNonNull(termination);if(sequence<0)throw new IllegalArgumentException("INVALID_ADMISSION_SEQUENCE");}
    }
    /** Conservative upper bounds for controller-retained worker disks and logs. No guest usage estimate. */
    public record StorageHold(Request request,long sequence,ContentDigest termination) {
        public StorageHold {
            Objects.requireNonNull(request);Objects.requireNonNull(termination);
            if(sequence<0||request.resources().disk()==0&&request.resources().log()==0)throw new IllegalArgumentException("INVALID_STORAGE_HOLD");
        }
        public ResourceBudget.Resources resources(){var r=request.resources();return new ResourceBudget.Resources(0,0,r.disk(),0,r.log(),0);}
    }
    public record State(String schema,long revision,long nextSequence,ResourceBudget.Resources capacity,int maxBypasses,
            Optional<ContentDigest> lastJob,List<Queued> pending,List<Queued> active,List<Retired> retired,List<StorageHold> retained) {
        /** Source compatibility for pre-retention callers; serialized v1 is decoded explicitly by the codec. */
        public State(String schema,long revision,long nextSequence,ResourceBudget.Resources capacity,int maxBypasses,Optional<ContentDigest> lastJob,List<Queued> pending,List<Queued> active,List<Retired> retired){
            this(legacySchema(schema,retired),revision,nextSequence,capacity,maxBypasses,lastJob,pending,active,retired,List.of());
        }
        public State {
            if(!"execution-admission:m8.4-v2".equals(schema)||revision<0||nextSequence<0||revision<nextSequence||maxBypasses<1)throw new IllegalArgumentException("INVALID_ADMISSION_STATE");
            Objects.requireNonNull(capacity);Objects.requireNonNull(lastJob);
            pending=pending.stream().sorted(ORDER).toList();active=active.stream().sorted(ORDER).toList();
            retired=retired.stream().sorted(Comparator.comparing(r->r.request().ticket())).toList();
            retained=retained.stream().sorted(Comparator.comparing(r->r.request().ticket())).toList();
            var watermarks=new HashMap<Subject,Retired>();var sequences=new HashSet<Long>();var subjects=new HashSet<Subject>();
            for(var row:retired){if(watermarks.put(subject(row.request().ticket()),row)!=null||row.sequence()>=nextSequence||!sequences.add(row.sequence()))throw new IllegalArgumentException("INVALID_ADMISSION_HISTORY");}
            for(var row:concat(pending,active)){
                if(row.sequence()>=nextSequence||row.bypasses()>maxBypasses||!sequences.add(row.sequence())||!subjects.add(subject(row.request().ticket())))throw new IllegalArgumentException("DUPLICATE_ADMISSION");
                var prior=watermarks.get(subject(row.request().ticket()));long expected=prior==null?1:Math.addExact(prior.request().ticket().fence(),1);
                if(row.request().ticket().fence()!=expected)throw new IllegalArgumentException("ADMISSION_FENCE_SEQUENCE");
            }
            var heldTickets=new HashSet<Ticket>();var heldSequences=new HashSet<Long>();
            for(var hold:retained){
                var prior=watermarks.get(subject(hold.request().ticket()));
                if(!heldTickets.add(hold.request().ticket())||!heldSequences.add(hold.sequence())||hold.sequence()>=nextSequence||prior==null
                        ||hold.request().ticket().fence()>prior.request().ticket().fence()||hold.request().ticket().fence()<prior.request().ticket().fence()&&hold.sequence()>=prior.sequence()||sequences.contains(hold.sequence())&&hold.sequence()!=prior.sequence()
                        ||hold.request().ticket().equals(prior.request().ticket())&&(!hold.request().equals(prior.request())||hold.sequence()!=prior.sequence()||!hold.termination().equals(prior.termination())))throw new IllegalArgumentException("INVALID_STORAGE_HISTORY");
            }
            ResourceBudget.Resources used=ZERO;
            try{for(var row:active)used=used.plus(row.request().resources());for(var hold:retained)used=used.plus(hold.resources());}catch(ArithmeticException overflow){throw new IllegalArgumentException("ADMISSION_RESOURCE_OVERFLOW");}
            if(!used.fits(capacity))throw new IllegalArgumentException("ADMISSION_CAPACITY_EXCEEDED");
        }
        public ContentDigest policy(){return CanonicalJson.sha256(Map.of("schema","execution-admission-policy:m8.4-v1","capacity",capacity,"maxBypasses",maxBypasses));}
    }
    private static String legacySchema(String schema,List<Retired> retired){
        if(!Set.of("execution-admission:m8.4-v1","execution-admission:m8.4-v2").contains(schema))throw new IllegalArgumentException("INVALID_ADMISSION_STATE");
        if("execution-admission:m8.4-v1".equals(schema))for(var row:retired){var r=row.request().resources();if(r.disk()>0||r.log()>0||row.request().ticket().fence()>1)throw new IllegalArgumentException("ADMISSION_STORAGE_RECONCILIATION_REQUIRED");}
        return "execution-admission:m8.4-v2";
    }
    public record Waiting(Ticket ticket,String reason) {}
    public record Admission(State state,List<Ticket> admitted,List<Waiting> waiting) {
        public Admission {Objects.requireNonNull(state);admitted=List.copyOf(admitted);waiting=List.copyOf(waiting);}
    }
    private record Subject(ContentDigest execution,ContentDigest task) {}
    private static final ResourceBudget.Resources ZERO=new ResourceBudget.Resources(0,0,0,0,0,0);
    private static final Comparator<Queued> ORDER=Comparator.comparingLong(Queued::sequence).thenComparing(q->q.request().ticket());
    private static final Comparator<Queued> PRIORITY=Comparator.<Queued>comparingLong(q->q.request().priority()).reversed().thenComparing(ORDER);
    private static Subject subject(Ticket t){return new Subject(t.execution(),t.task());}
    private static List<Queued> concat(List<Queued> a,List<Queued> b){var out=new ArrayList<>(a);out.addAll(b);return out;}
    public static State initial(ResourceBudget.Resources capacity,int bypasses){return new State("execution-admission:m8.4-v1",0,0,capacity,bypasses,Optional.empty(),List.of(),List.of(),List.of());}
    public static State submit(State s,Request request){
        Objects.requireNonNull(request);
        for(var row:concat(s.pending(),s.active()))if(subject(row.request().ticket()).equals(subject(request.ticket()))){
            if(row.request().equals(request))return s;throw new IllegalArgumentException("TASK_ALREADY_RESERVED");
        }
        var prior=s.retired().stream().filter(r->subject(r.request().ticket()).equals(subject(request.ticket()))).findFirst();
        if(prior.isPresent()&&prior.orElseThrow().request().ticket().equals(request.ticket())){
            if(prior.orElseThrow().request().equals(request))return s;throw new IllegalArgumentException("ADMISSION_REQUEST_CONFLICT");
        }
        long expected=prior.isEmpty()?1:Math.addExact(prior.orElseThrow().request().ticket().fence(),1);
        if(request.ticket().fence()!=expected)throw new IllegalArgumentException("ADMISSION_FENCE_SEQUENCE");
        var pending=new ArrayList<>(s.pending());pending.add(new Queued(request,s.nextSequence(),0));
        return copy(s,Math.addExact(s.nextSequence(),1),s.lastJob(),pending,s.active(),s.retired());
    }
    public static Admission dispatch(State s){
        var pending=new ArrayList<>(s.pending());var active=new ArrayList<>(s.active());var admitted=new ArrayList<Ticket>();
        ResourceBudget.Resources held=ZERO;for(var hold:s.retained())held=held.plus(hold.resources());
        final var storage=held;
        ResourceBudget.Resources used=storage;for(var row:active)used=used.plus(row.request().resources());
        var cursor=s.lastJob();
        while(!pending.isEmpty()){
            var barrier=pending.stream().filter(q->q.bypasses()>=s.maxBypasses()&&fits(storage,q.request().resources(),s.capacity())).min(ORDER);
            Queued selected;
            if(barrier.isPresent()){
                selected=barrier.orElseThrow();if(!fits(used,selected.request().resources(),s.capacity()))break;
            }else{
                selected=choose(pending,cursor,used,s.capacity());if(selected==null)break;
            }
            pending.remove(selected);
            for(int i=0;i<pending.size();i++){
                var row=pending.get(i);
                // Every bypass ages viable work, including low-priority work that already fits.
                // Saturation is deliberate: age is a barrier threshold, never an unbounded counter.
                if(fits(storage,row.request().resources(),s.capacity())&&row.bypasses()<s.maxBypasses())pending.set(i,new Queued(row.request(),row.sequence(),row.bypasses()+1));
            }
            active.add(selected);used=used.plus(selected.request().resources());cursor=Optional.of(selected.request().job());admitted.add(selected.request().ticket());
        }
        State next=admitted.isEmpty()?s:copy(s,s.nextSequence(),cursor,pending,active,s.retired());
        var barrier=next.pending().stream().filter(q->q.bypasses()>=s.maxBypasses()&&fits(storage,q.request().resources(),s.capacity())).min(ORDER);
        var waiting=new ArrayList<Waiting>();
        for(var row:next.pending())waiting.add(new Waiting(row.request().ticket(),!row.request().resources().fits(s.capacity())?"WORKER_PROFILE_REQUIRED":!fits(storage,row.request().resources(),s.capacity())?"WAITING_FOR_RETAINED_STORAGE":barrier.isPresent()&&!barrier.orElseThrow().request().ticket().equals(row.request().ticket())?"RESERVATION_BARRIER":"WAITING_FOR_RESOURCES"));
        return new Admission(next,admitted,waiting);
    }
    private static Queued choose(List<Queued> pending,Optional<ContentDigest> cursor,ResourceBudget.Resources used,ResourceBudget.Resources capacity){
        var jobs=pending.stream().map(q->q.request().job()).distinct().sorted().toList();
        int start=0;
        if(cursor.isEmpty())start=jobs.indexOf(pending.stream().min(ORDER).orElseThrow().request().job());
        else {boolean found=false;for(int i=0;i<jobs.size();i++)if(jobs.get(i).compareTo(cursor.orElseThrow())>0){start=i;found=true;break;}if(!found)start=0;}
        for(int offset=0;offset<jobs.size();offset++){
            ContentDigest job=jobs.get((start+offset)%jobs.size());
            var selected=pending.stream().filter(q->q.request().job().equals(job)&&fits(used,q.request().resources(),capacity)).min(PRIORITY);
            if(selected.isPresent())return selected.orElseThrow();
        }
        return null;
    }
    private static boolean fits(ResourceBudget.Resources used,ResourceBudget.Resources demand,ResourceBudget.Resources capacity){try{return used.plus(demand).fits(capacity);}catch(ArithmeticException overflow){return false;}}
    /** proof must come from the controller's qualified termination boundary, never a producer/API field. */
    public static State release(State s,Ticket ticket,Optional<ContentDigest> proof){
        return release(s,ticket,proof,true);
    }
    /** Only for a controller-proved never-launched intent, not VM Off/absence or guest assertions. */
    public static State releaseUnlaunched(State s,Ticket ticket,Optional<ContentDigest> proof){
        if(s.retained().stream().anyMatch(h->h.request().ticket().equals(ticket)))throw new IllegalArgumentException("STORAGE_ALREADY_RETAINED");
        return release(s,ticket,proof,false);
    }
    private static State release(State s,Ticket ticket,Optional<ContentDigest> proof,boolean retain){
        Objects.requireNonNull(proof);if(proof.isEmpty())throw new IllegalArgumentException("TERMINATION_UNCONFIRMED");
        var row=s.active().stream().filter(q->q.request().ticket().equals(ticket)).findFirst();
        if(row.isEmpty()){
            var prior=s.retired().stream().filter(r->r.request().ticket().equals(ticket)).findFirst();
            if(prior.isPresent()&&prior.orElseThrow().termination().equals(proof.orElseThrow()))return s;
            if(s.retained().stream().anyMatch(h->h.request().ticket().equals(ticket)&&h.termination().equals(proof.orElseThrow())))return s;
            throw new IllegalArgumentException("RESERVATION_NOT_ACTIVE_OR_PROOF_CONFLICT");
        }
        var active=new ArrayList<>(s.active());active.remove(row.orElseThrow());
        var retired=new ArrayList<>(s.retired());retired.removeIf(r->subject(r.request().ticket()).equals(subject(ticket)));
        retired.add(new Retired(row.orElseThrow().request(),row.orElseThrow().sequence(),proof.orElseThrow()));
        var retained=new ArrayList<>(s.retained());var request=row.orElseThrow().request();
        if(retain&&(request.resources().disk()>0||request.resources().log()>0))retained.add(new StorageHold(request,row.orElseThrow().sequence(),proof.orElseThrow()));
        return new State(s.schema(),Math.addExact(s.revision(),1),s.nextSequence(),s.capacity(),s.maxBypasses(),s.lastJob(),s.pending(),active,retired,retained);
    }
    public static State cancelPending(State s,Ticket ticket){
        if(s.active().stream().anyMatch(q->q.request().ticket().equals(ticket)))throw new IllegalArgumentException("TERMINATION_UNCONFIRMED");
        var pending=s.pending().stream().filter(q->!q.request().ticket().equals(ticket)).toList();
        return pending.size()==s.pending().size()?s:copy(s,s.nextSequence(),s.lastJob(),pending,s.active(),s.retired());
    }
    private static State copy(State s,long sequence,Optional<ContentDigest> cursor,List<Queued> pending,List<Queued> active,List<Retired> retired){return new State(s.schema(),Math.addExact(s.revision(),1),sequence,s.capacity(),s.maxBypasses(),cursor,pending,active,retired,s.retained());}
    private AdmissionQueue(){}
}
