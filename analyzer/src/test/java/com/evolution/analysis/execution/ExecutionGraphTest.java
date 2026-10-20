package com.evolution.analysis.execution;

import com.evolution.analysis.contract.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.execution.ExecutionGraph.*;

class ExecutionGraphTest {
    static final VersionedIdentifier VERSION=new VersionedIdentifier("execution.test","1");
    static ContentDigest digest(String value){return ContentDigest.sha256Utf8(value);}
    static Task task(String root){return new Task("execution-task:m8.4-v1",Kind.NATIVE_RUN,root,List.of(root),digest("recipe"),List.of(),List.of("raw"),"configured",VERSION);}
    static Edge edge(Task a,Task b){return new Edge(a.identity(),b.identity(),Condition.AFTER_VALIDATION,List.of("raw"));}
    @Test void rejectsDanglingEdgesAndInvalidOutputRoles(){
        var a=task("a");var b=task("b");
        assertThrows(IllegalArgumentException.class,()->new ExecutionGraph(List.of(a),List.of(edge(a,b))));
        assertThrows(IllegalArgumentException.class,()->new ExecutionGraph(List.of(a,b),List.of(new Edge(a.identity(),b.identity(),Condition.AFTER_VALIDATION,List.of("forged")))));
    }
    @Test void cyclesDoNotDisappearAndIndependentTasksRemainRepresented(){
        var a=task("a");var b=task("b");var c=task("c");
        assertThrows(IllegalArgumentException.class,()->new ExecutionGraph(List.of(a,b,c),List.of(edge(a,b),edge(b,a))));
        var graph=new ExecutionGraph(List.of(c,b,a),List.of(edge(a,b),edge(a,b)));
        assertEquals(3,graph.schedule().size());assertEquals(1,graph.edges().size());
        assertTrue(graph.schedule().indexOf(a.identity())<graph.schedule().indexOf(b.identity()));
    }
    @Test void failedWorkAllowsCollectionButNotSemanticConsumption(){
        var a=task("a");var b=task("b");var c=task("c");
        var graph=new ExecutionGraph(List.of(a,b,c),List.of(new Edge(a.identity(),b.identity(),Condition.AFTER_TERMINATION,List.of("raw")),edge(a,c)));
        assertEquals(List.of(a.identity()),graph.ready(Map.of()));
        assertEquals(List.of(b.identity()),graph.ready(Map.of(a.identity(),new Progress(true,Set.of()))));
        assertEquals(Set.of(b.identity(),c.identity()),new HashSet<>(graph.ready(Map.of(a.identity(),new Progress(true,Set.of("raw"))))));
        assertTrue(graph.ready(Map.of(a.identity(),new Progress(false,Set.of("raw")))).isEmpty());
    }
    @Test void edgeConflictsSlotsAndSetDuplicatesAreRejected(){
        var a=task("a");var b=task("b");
        assertThrows(IllegalArgumentException.class,()->new ExecutionGraph(List.of(a,b),List.of(edge(a,b),new Edge(a.identity(),b.identity(),Condition.AFTER_TERMINATION,List.of("raw")))));
        var slot=new TaskInput("input","TASK_OUTPUT",a.identity(),"raw","all");
        var consumer=new Task("execution-task:m8.4-v1",Kind.RECONCILE,"root",List.of(),digest("r"),List.of(slot),List.of(),"worker",VERSION);
        assertThrows(IllegalArgumentException.class,()->new ExecutionGraph(List.of(a,consumer),List.of()));
        assertThrows(IllegalArgumentException.class,()->new ExecutionGraph(List.of(a,consumer),List.of(new Edge(a.identity(),consumer.identity(),Condition.AFTER_TERMINATION,List.of("raw")))));
        assertThrows(IllegalArgumentException.class,()->new Task("execution-task:m8.4-v1",Kind.NATIVE_RUN,"root",List.of("x","x"),digest("r"),List.of(),List.of(),"w",VERSION));
    }
    @Test void canonicalPermutationAndOrderedPriorityAreStable(){
        var a=task("a");var b=task("b");var c=task("c");var d=task("d");
        var edges=List.of(edge(a,b),edge(a,c),edge(c,d));
        var graph=new ExecutionGraph(List.of(a,b,c,d),edges);
        var reversed=new ExecutionGraph(List.of(d,c,b,a),List.of(edges.get(2),edges.get(1),edges.get(0)));
        assertEquals(a.identity(),graph.schedule().getFirst());assertEquals(graph.identity(),reversed.identity());assertEquals(graph.schedule(),reversed.schedule());
    }
}
