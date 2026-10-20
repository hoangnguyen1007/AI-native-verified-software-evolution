package com.evolution.analysis.execution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.acquisition.streaming.ContentHandle;
import java.util.*;

/** Immutable, bounded worker view of the execution DAG. This is not an execution permission. */
public final class ExecutionGraph {
    public enum Kind { NATIVE_RUN, COLLECT, VALIDATE, RECONCILE }
    public enum Condition { AFTER_TERMINATION, AFTER_VALIDATION }
    public sealed interface Input permits CapturedInput, TaskInput { String role(); }
    public record CapturedInput(String role, String kind, ContentHandle content) implements Input {
        public CapturedInput { text(role); if (!"CAPTURED".equals(kind)) throw new IllegalArgumentException("INVALID_INPUT_KIND"); Objects.requireNonNull(content); }
    }
    public record TaskInput(String role, String kind, ContentDigest task, String outputRole, String selector) implements Input {
        public TaskInput { text(role); if (!"TASK_OUTPUT".equals(kind)) throw new IllegalArgumentException("INVALID_INPUT_KIND"); Objects.requireNonNull(task); text(outputRole); text(selector); }
    }
    public record Task(String schema, Kind kind, String root, List<String> contexts, ContentDigest recipe, List<Input> inputs,
            List<String> outputRoles, String resourceClass, VersionedIdentifier predicate) {
        public Task {
            if (!"execution-task:m8.4-v1".equals(schema)) throw new IllegalArgumentException("UNSUPPORTED_SCHEMA");
            Objects.requireNonNull(kind); text(root); Objects.requireNonNull(recipe); text(resourceClass); Objects.requireNonNull(predicate);
            contexts = strings(contexts); outputRoles = strings(outputRoles);
            inputs=inputs.stream().sorted(Comparator.comparing(Input::role)).toList();
            var roles=new HashSet<String>();for(var input:inputs)if(!roles.add(input.role()))throw new IllegalArgumentException("DUPLICATE_INPUT_ROLE");
        }
        public ContentDigest identity() { return CanonicalJson.sha256(this); }
    }
    public record Edge(String schema, ContentDigest predecessor, ContentDigest consumer, Condition condition, List<String> outputRoles) {
        public Edge {
            if (!"execution-edge:m8.4-v1".equals(schema))throw new IllegalArgumentException("UNSUPPORTED_SCHEMA");
            Objects.requireNonNull(predecessor); Objects.requireNonNull(consumer); Objects.requireNonNull(condition); outputRoles = strings(outputRoles);
            if(condition==Condition.AFTER_VALIDATION&&outputRoles.isEmpty())throw new IllegalArgumentException("OUTPUT_ROLE_REQUIRED");
        }
        public Edge(ContentDigest predecessor,ContentDigest consumer,Condition condition,List<String> roles){this("execution-edge:m8.4-v1",predecessor,consumer,condition,roles);}
    }
    private final Map<ContentDigest,Task> tasks;
    private final List<Edge> edges;
    private final List<ContentDigest> schedule;
    private final Map<ContentDigest,List<Edge>> incoming;
    public ExecutionGraph(Collection<Task> tasks, Collection<Edge> edges) {
        var map = new TreeMap<ContentDigest,Task>();
        for (var task : tasks) if (map.putIfAbsent(task.identity(), task) != null) throw new IllegalArgumentException("DUPLICATE_TASK");
        this.tasks = Collections.unmodifiableMap(map);
        var unique=new TreeMap<String,Edge>();
        for(var edge:edges){
            if(!map.containsKey(edge.predecessor())||!map.containsKey(edge.consumer()))throw new IllegalArgumentException("MISSING_ENDPOINT");
            if(edge.predecessor().equals(edge.consumer()))throw new IllegalArgumentException("EXECUTION_CYCLE");
            if(!map.get(edge.predecessor()).outputRoles().containsAll(edge.outputRoles()))throw new IllegalArgumentException("UNKNOWN_OUTPUT_ROLE");
            String key=edge.predecessor().value()+edge.consumer().value();var old=unique.putIfAbsent(key,edge);
            if(old!=null&&!old.equals(edge))throw new IllegalArgumentException("CONFLICTING_EDGE");
        }
        this.edges = List.copyOf(unique.values());
        var in=new TreeMap<ContentDigest,List<Edge>>();var out=new TreeMap<ContentDigest,List<ContentDigest>>();var degree=new TreeMap<ContentDigest,Integer>();
        map.keySet().forEach(id->{degree.put(id,0);in.put(id,new ArrayList<>());out.put(id,new ArrayList<>());});
        for(var edge:this.edges){in.get(edge.consumer()).add(edge);out.get(edge.predecessor()).add(edge.consumer());degree.compute(edge.consumer(),(k,v)->Math.addExact(v,1));}
        for(var task:map.values())for(var input:task.inputs())if(input instanceof TaskInput slot){
            if(!map.containsKey(slot.task())||!map.get(slot.task()).outputRoles().contains(slot.outputRole()))throw new IllegalArgumentException("INVALID_INPUT_SLOT");
            if(in.get(task.identity()).stream().noneMatch(edge->edge.predecessor().equals(slot.task())&&edge.outputRoles().contains(slot.outputRole())))throw new IllegalArgumentException("UNBOUND_INPUT_SLOT");
            if(task.kind()!=Kind.VALIDATE&&task.kind()!=Kind.COLLECT&&in.get(task.identity()).stream().anyMatch(edge->edge.predecessor().equals(slot.task())&&edge.condition()==Condition.AFTER_TERMINATION))throw new IllegalArgumentException("UNVALIDATED_SEMANTIC_INPUT");
        }
        var ready=new PriorityQueue<ContentDigest>(Comparator.<ContentDigest>comparingInt(id->out.get(id).size()).reversed().thenComparing(Comparator.naturalOrder()));
        degree.forEach((id,count)->{if(count==0)ready.add(id);});var ordered=new ArrayList<ContentDigest>();
        while(!ready.isEmpty()){var id=ready.remove();ordered.add(id);for(var next:out.get(id))if(degree.compute(next,(k,v)->v-1)==0)ready.add(next);}
        if(ordered.size()!=map.size())throw new IllegalArgumentException("EXECUTION_CYCLE");
        this.schedule=List.copyOf(ordered);var frozen=new TreeMap<ContentDigest,List<Edge>>();in.forEach((id,list)->frozen.put(id,List.copyOf(list)));this.incoming=Collections.unmodifiableMap(frozen);
    }
    public Map<ContentDigest,Task> tasks() { return tasks; }
    public List<Edge> edges() { return edges; }
    public List<ContentDigest> schedule() { return schedule; }
    /** Progress is supplied by a trusted journal projection, never by a guest/API completed-ID list. */
    public record Progress(boolean quiescent, Set<String> acceptedRoles) { public Progress {acceptedRoles=Set.copyOf(acceptedRoles);} }
    public List<ContentDigest> ready(Map<ContentDigest,Progress> progress) {
        if(!tasks.keySet().containsAll(progress.keySet()))throw new IllegalArgumentException("FOREIGN_TASK_PROGRESS");
        return schedule.stream().filter(id->!progress.containsKey(id)).filter(id->incoming.get(id).stream().allMatch(edge->{
            var p=progress.get(edge.predecessor());return p!=null&&p.quiescent()&&(edge.condition()==Condition.AFTER_TERMINATION||p.acceptedRoles().containsAll(edge.outputRoles()));
        })).toList();
    }
    public ContentDigest identity(){return CanonicalJson.sha256(Map.of("schema","execution-graph-view:m8.4-v1","tasks",tasks.values(),"edges",edges));}
    public static String text(String value) {
        Objects.requireNonNull(value);
        if (value.isEmpty() || value.indexOf('\0') >= 0) throw new IllegalArgumentException("INVALID_ID");
        for (int i=0;i<value.length();i++) if (Character.isSurrogate(value.charAt(i))) {
            if (!Character.isHighSurrogate(value.charAt(i)) || i+1==value.length() || !Character.isLowSurrogate(value.charAt(++i))) throw new IllegalArgumentException("INVALID_UNICODE");
        }
        return value;
    }
    public static List<String> strings(Collection<String> values) {
        var set=new TreeSet<String>();
        for (String value:values) if (!set.add(text(value))) throw new IllegalArgumentException("DUPLICATE_ID");
        return List.copyOf(set);
    }
}
