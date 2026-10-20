package com.evolution.analysis.execution;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/** Additive streaming wire envelope. Tables are verified by the persistence adapter before use. */
public record ExecutionPlan(String schema, ContentDigest readiness, ContentDigest inventory, ContentDigest selection,
        VersionedIdentifier planner, ContentDigest policy, Table tasks, Table edges, Table bindings,
        Optional<ContentDigest> parent, Optional<ContentHandle> discovery) {
    public static final String SCHEMA="execution-plan:m8.4-v1";
    public record Table(String schema,ContentHandle content,long rows) {
        public Table {ExecutionGraph.text(schema);Objects.requireNonNull(content);if(rows<0)throw new IllegalArgumentException("NEGATIVE_COUNT");}
    }
    public record ScopedRef(ContentDigest plan,String localId) {public ScopedRef {Objects.requireNonNull(plan);ExecutionGraph.text(localId);}}
    public record ContextRef(ContentDigest context,ScopedRef reference) {public ContextRef {Objects.requireNonNull(context);Objects.requireNonNull(reference);}}
    public enum Mode { ALL_OF, ANY_EQUIVALENT, REUSE, PENDING }
    public record Candidate(ContentDigest task,String outputRole,VersionedIdentifier predicate,String evidenceRole) {
        public Candidate {Objects.requireNonNull(task);ExecutionGraph.text(outputRole);Objects.requireNonNull(predicate);ExecutionGraph.text(evidenceRole);}
    }
    public record Binding(String schema,ScopedRef requirement,Mode mode,List<Candidate> candidates,Optional<ContentDigest> decision,Optional<String> reason) {
        public Binding {
            if(!"execution-binding:m8.4-v1".equals(schema))throw new IllegalArgumentException("UNSUPPORTED_SCHEMA");
            Objects.requireNonNull(requirement);Objects.requireNonNull(mode);Objects.requireNonNull(decision);Objects.requireNonNull(reason);reason.ifPresent(ExecutionGraph::text);
            var order=Comparator.comparing(Candidate::evidenceRole).thenComparing(Candidate::task).thenComparing(Candidate::outputRole).thenComparing(c->c.predicate().id()).thenComparing(c->c.predicate().version());
            candidates=candidates.stream().sorted(order).toList();
            if(new HashSet<>(candidates).size()!=candidates.size())throw new IllegalArgumentException("DUPLICATE_CANDIDATE");
            switch(mode){
                case ALL_OF,ANY_EQUIVALENT -> {if(candidates.isEmpty()||decision.isPresent()||reason.isPresent())throw new IllegalArgumentException("INVALID_BINDING");}
                case REUSE -> {if(!candidates.isEmpty()||decision.isEmpty()||reason.isPresent())throw new IllegalArgumentException("INVALID_BINDING");}
                case PENDING -> {if(!candidates.isEmpty()||decision.isPresent()||reason.isEmpty())throw new IllegalArgumentException("INVALID_BINDING");}
            }
            if(mode==Mode.ANY_EQUIVALENT&&candidates.stream().map(Candidate::evidenceRole).distinct().count()!=1)throw new IllegalArgumentException("INCOMPATIBLE_EVIDENCE_ROLES");
        }
    }
    public ExecutionPlan {
        if(!SCHEMA.equals(schema))throw new IllegalArgumentException("UNSUPPORTED_SCHEMA");
        Objects.requireNonNull(readiness);Objects.requireNonNull(inventory);Objects.requireNonNull(selection);Objects.requireNonNull(planner);Objects.requireNonNull(policy);
        Objects.requireNonNull(tasks);Objects.requireNonNull(edges);Objects.requireNonNull(bindings);Objects.requireNonNull(parent);Objects.requireNonNull(discovery);
        if(parent.isPresent()!=discovery.isPresent())throw new IllegalArgumentException("DISCOVERY_PARENT_MISMATCH");
        if(!tasks.schema().equals("execution-task-table:m8.4-v1")||!edges.schema().equals("execution-edge-table:m8.4-v1")||!bindings.schema().equals("execution-binding-table:m8.4-v1"))throw new IllegalArgumentException("INVALID_TABLE_SCHEMA");
    }
    public ContentDigest identity(){return CanonicalJson.sha256(this);}
    public static void validateBindings(com.evolution.analysis.readiness.ReadinessPlan readiness,ExecutionGraph graph,Collection<Binding> bindings) {
        var expected=new HashSet<>(readiness.requirements().stream().map(com.evolution.analysis.readiness.ReadinessPlan.Requirement::id).toList());var seen=new HashSet<String>();
        var roots=new HashSet<>(readiness.roots().stream().map(com.evolution.analysis.readiness.ReadinessPlan.Root::id).toList());
        var selected=new HashSet<>(readiness.selection().selected());
        for(var task:graph.tasks().values()){
            if(!roots.contains(task.root())||!selected.containsAll(task.contexts()))throw new IllegalArgumentException("FOREIGN_TASK_SCOPE");
            for(String id:task.contexts())if(readiness.contexts().stream().noneMatch(c->c.id().equals(id)&&c.root().equals(task.root())))throw new IllegalArgumentException("CROSS_ROOT_CONTEXT");
        }
        for(var binding:bindings){
            if(!binding.requirement().plan().equals(readiness.identity())||!expected.contains(binding.requirement().localId())||!seen.add(binding.requirement().localId()))throw new IllegalArgumentException("INVALID_REQUIREMENT_BINDING");
            for(var candidate:binding.candidates()){
                var task=graph.tasks().get(candidate.task());if(task==null||!task.outputRoles().contains(candidate.outputRole()))throw new IllegalArgumentException("INVALID_CANDIDATE");
            }
        }
        if(!seen.equals(expected))throw new IllegalArgumentException("OPEN_REQUIREMENT_DENOMINATOR");
    }
}
