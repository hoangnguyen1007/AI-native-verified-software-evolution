package com.evolution.analysis.query;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.spring.truth.SymbolicConfiguration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

class ConditionalPathCycleTest {
    static String id(String s){return KnowledgeGraph.derivedIdentity("fixture",s);}
    static final class Fixture {
        final SnapshotIdentity snapshot=SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("https://example.test/query.git"),ContentDigest.sha256Utf8("snapshot"));
        final String context=snapshot.value();
        final List<GraphEvidence> evidence=new ArrayList<>();final List<KnowledgeGraph.Node> nodes=new ArrayList<>();final List<KnowledgeGraph.Edge> edges=new ArrayList<>();
        final GraphEvidence base=GraphEvidence.capture("fixture.context",Map.of("version","1"));
        Fixture(){evidence.add(base);evidence.add(GraphEvidence.capture("spring.symbolic-result",Map.of("inputIdentity",ContentDigest.sha256Utf8("evaluation"),"signaturesComplete",true)));}
        Fixture node(String name){nodes.add(new KnowledgeGraph.Node(id(name),context,"TYPE",List.of(base.identity())));return this;}
        void formula(SymbolicConfiguration.Formula f){
            for(var child:f.children())formula(child);
            if(nodes.stream().noneMatch(n->n.identity().equals(f.identity().value()))) {
                var p=GraphEvidence.capture("spring.formula",f.view());evidence.add(p);nodes.add(new KnowledgeGraph.Node(f.identity().value(),context,"REGION_FORMULA",List.of(p.identity())));
            }
        }
        Fixture edge(String a,String b,SymbolicConfiguration.Formula yes,SymbolicConfiguration.Formula no,SymbolicConfiguration.Formula unknown){
            formula(yes);formula(no);formula(unknown);
            var fact=Map.of("kind","SELECTED_BINDING","factSemanticsVersion",Map.of("id","spring.conditional-fact","version","m4d-v1"));
            var region=GraphEvidence.capture("spring.symbolic-region",Map.of("encoding","symbolic-dag-m4u.2-v1","space",id("space"),"evaluationInput",ContentDigest.sha256Utf8("evaluation"),
                    "region",Map.of("fact",fact,"trueRegion",yes.identity(),"falseRegion",no.identity(),"unknownRegion",unknown.identity(),"classification",
                            yes==SymbolicConfiguration.TRUE?"MUST":no==SymbolicConfiguration.TRUE?"NEVER":unknown!=SymbolicConfiguration.FALSE?"UNKNOWN":"MAY")));
            evidence.add(region);String r=id("region"+edges.size());nodes.add(new KnowledgeGraph.Node(r,context,"TRUTH_REGION",List.of(region.identity())));
            edges.add(new KnowledgeGraph.Edge(id("edge"+edges.size()),context,"fixture.depends",id(a),new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(id(b)),Optional.empty()),List.of(region.identity()),Optional.of(r)));return this;
        }
        Fixture edge(String a,String b){edges.add(new KnowledgeGraph.Edge(id("edge"+edges.size()),context,"fixture.depends",id(a),new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(id(b)),Optional.empty()),List.of(base.identity()),Optional.empty()));return this;}
        Fixture explicit(String a,String b,Set<String> yes,Set<String> unknown,Set<String> universe,boolean closed){
            for(String world:universe)if(nodes.stream().noneMatch(n->n.identity().equals(id("world"+world))))nodes.add(new KnowledgeGraph.Node(id("world"+world),context,"CONFIGURATION_WORLD",List.of(base.identity())));
            var no=new TreeSet<>(universe);no.removeAll(yes);no.removeAll(unknown);
            var fact=Map.of("kind","SELECTED_BINDING","factSemanticsVersion",Map.of("id","spring.conditional-fact","version","m4d-v1"));
            var payload=GraphEvidence.capture("spring.explicit-region",Map.of("encoding","explicit-worlds-m4d-v1","space",id("space"),"semanticsContext",context,
                    "region",Map.of("fact",fact,"trueWorlds",yes.stream().map(w->id("world"+w)).toList(),"falseWorlds",no.stream().map(w->id("world"+w)).toList(),
                            "unknownWorlds",unknown.stream().map(w->id("world"+w)).toList(),"classification","UNKNOWN","feasibility",Map.of("enumerationComplete",closed,"unknownFeasibilityWorlds",0))));
            evidence.add(payload);String region=id("region"+edges.size());nodes.add(new KnowledgeGraph.Node(region,context,"TRUTH_REGION",List.of(payload.identity())));
            edges.add(new KnowledgeGraph.Edge(id("edge"+edges.size()),context,"fixture.depends",id(a),new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(id(b)),Optional.empty()),List.of(payload.identity()),Optional.of(region)));return this;
        }
        KnowledgeGraph graph(){return new KnowledgeGraph(snapshot,List.of(new KnowledgeGraph.Context(context,snapshot,Optional.empty(),base.identity())),nodes,edges,evidence);}
        Request request(){return request(Limits.defaults());}
        Request request(Limits limits){return new Request(graph().identity(),context,Scope.ENTITY,Projection.BOUNDED_UNION,Direction.OUTGOING,Set.of("fixture.depends"),Population.all(),false,Optional.empty(),limits);}
    }
    @Test void impossiblePrefixIsPrunedBeforeFurtherExpansion(){
        var p=SymbolicConfiguration.variable("p");var f=new Fixture().node("a").node("b").node("c").node("d")
                .edge("a","b",p,SymbolicConfiguration.not(p),SymbolicConfiguration.FALSE)
                .edge("b","c",SymbolicConfiguration.not(p),p,SymbolicConfiguration.FALSE).edge("c","d");
        var result=new ArchitectureQueryService(f.graph()).findPaths(f.request(),id("a"),id("d"));
        assertTrue(result.items().isEmpty());assertEquals(1,result.bounds().rejectedInfeasible());assertEquals(2,result.bounds().stepsUsed());
        assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.COMPLETE,result.status());
    }
    @Test void pairwiseIntersectionDoesNotProveJointPath(){
        var all=Set.of("a","b","c");var f=new Fixture().node("a").node("b").node("c").node("d")
                .explicit("a","b",Set.of("a","b"),Set.of(),all,true).explicit("b","c",Set.of("b","c"),Set.of(),all,true)
                .explicit("c","d",Set.of("a","c"),Set.of(),all,true);
        var r=new ArchitectureQueryService(f.graph()).findPaths(f.request(),id("a"),id("d"));assertTrue(r.items().isEmpty());assertEquals(1,r.bounds().rejectedInfeasible());
    }
    @Test void independentPrefixesToSameVertexRemainExplorable(){
        var p=SymbolicConfiguration.variable("p");var n=SymbolicConfiguration.not(p);var z=SymbolicConfiguration.FALSE;
        var f=new Fixture().node("a").node("b").node("c").node("d").node("e")
                .edge("a","b",p,n,z).edge("a","c",n,p,z).edge("b","d").edge("c","d").edge("d","e",n,p,z);
        var r=new ArchitectureQueryService(f.graph()).findPaths(f.request(),id("a"),id("e"));assertEquals(1,r.items().size());
        assertEquals(List.of(id("a"),id("c"),id("d"),id("e")),r.items().getFirst().vertices());assertEquals(false,r.items().getFirst().witness().orElseThrow().assignment().get("p"));
        assertTrue(r.items().getFirst().witness().orElseThrow().formulaReplayed());assertFalse(r.items().getFirst().witness().orElseThrow().semanticReplayed());
    }
    @Test void profileNamesAreNotAnExclusionConstraint(){
        var dev=SymbolicConfiguration.variable("dev");var prod=SymbolicConfiguration.variable("prod");var z=SymbolicConfiguration.FALSE;
        var f=new Fixture().node("a").node("b").node("c").edge("a","b",dev,SymbolicConfiguration.not(dev),z).edge("b","c",prod,SymbolicConfiguration.not(prod),z);
        assertEquals(1,new ArchitectureQueryService(f.graph()).findPaths(f.request(),id("a"),id("c")).items().size());
        var exclusive=SymbolicConfiguration.not(SymbolicConfiguration.and(dev,prod));
        var g=new Fixture().node("a").node("b").node("c").edge("a","b",SymbolicConfiguration.and(exclusive,dev),SymbolicConfiguration.and(exclusive,SymbolicConfiguration.not(dev)),z)
                .edge("b","c",SymbolicConfiguration.and(exclusive,prod),SymbolicConfiguration.and(exclusive,SymbolicConfiguration.not(prod)),z);
        assertTrue(new ArchitectureQueryService(g.graph()).findPaths(g.request(),id("a"),id("c")).items().isEmpty());
    }
    @Test void unknownFeasibilityIsQualifiedWithoutInventingWitness(){
        var f=new Fixture().node("a").node("b").edge("a","b",SymbolicConfiguration.FALSE,SymbolicConfiguration.FALSE,SymbolicConfiguration.TRUE).edge("b","a");
        var r=new ArchitectureQueryService(f.graph()).findCycles(f.request());assertTrue(r.items().isEmpty());assertTrue(r.bounds().unknownCandidates()>0);
        assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.PARTIAL,r.status());assertTrue(r.exactTotal().isEmpty());assertFalse(r.gaps().isEmpty());
    }
    static Limits limits(int depth,long steps,int results){return new Limits(depth,steps,results,100_000,500_000,1_000_000,100_000,200_000);}
    @Test void directedCyclesAreDistinctButParallelSupportsDoNotMultiplyThem(){
        var f=new Fixture().node("a").node("b").node("c").node("isolated").edge("a","a").edge("a","b").edge("a","b").edge("b","a")
                .edge("b","c").edge("c","a").edge("a","c").edge("c","b");
        var r=new ArchitectureQueryService(f.graph()).findCycles(f.request());assertEquals(6,r.items().size());assertEquals(6,r.exactTotal().orElseThrow());
        assertEquals(1,r.bounds().cyclicComponents());assertEquals(6,r.items().stream().map(Path::identity).distinct().count());
        assertTrue(r.items().stream().flatMap(v->v.edges().stream()).anyMatch(e->e.supports().size()==2));
    }
    @Test void cycleOutputQuotaStopsAt50AcrossSccs(){
        for(int count:List.of(49,50,51)){
            var f=new Fixture();for(int i=0;i<count;i++)f.node("n"+i).edge("n"+i,"n"+i);
            var r=new ArchitectureQueryService(f.graph()).findCycles(f.request());assertEquals(Math.min(count,50),r.items().size());
            assertEquals(count>=50,r.bounds().truncated());assertEquals(count>=50,r.bounds().outputBudgetExceeded());assertFalse(r.bounds().workBudgetExceeded());
            assertEquals(count>=50,r.exactTotal().isEmpty());
        }
    }
    @Test void cycleDepthCountsVerticesAndNeverOverflowsBudget(){
        for(int count:List.of(20,21)){
            var f=new Fixture();for(int i=0;i<count;i++)f.node("n"+i).edge("n"+i,"n"+((i+1)%count));
            var r=new ArchitectureQueryService(f.graph()).findCycles(f.request());assertEquals(count==20?1:0,r.items().size());
            assertEquals(count==21,r.bounds().workBudgetExceeded());assertEquals(count==21?StopReason.DEPTH:StopReason.NONE,r.bounds().stopReason());
        }
        var self=new Fixture().node("a").edge("a","a");assertEquals(1,new ArchitectureQueryService(self.graph()).findCycles(self.request(limits(1,100,50))).items().size());
    }
    @Test void traversalCountersDoNotResetAndCanStopBeforeFirstWitness(){
        var f=new Fixture().node("a").node("b").edge("a","a").edge("b","b");
        var r=new ArchitectureQueryService(f.graph()).findCycles(f.request(limits(20,1,50)));assertTrue(r.items().isEmpty());assertEquals(1,r.bounds().stepsUsed());
        assertEquals(StopReason.STEPS,r.bounds().stopReason());assertTrue(r.bounds().truncated());assertTrue(r.bounds().workBudgetExceeded());
    }
    @Test void tarjanHandlesDeepAcyclicInputsWithoutUsingCycleDepthBudget(){
        var f=new Fixture();for(int i=0;i<2500;i++){f.node("n"+i);if(i>0)f.edge("n"+(i-1),"n"+i);}
        var r=new ArchitectureQueryService(f.graph()).findCycles(f.request(limits(1,1,50)));assertTrue(r.items().isEmpty());assertEquals(0,r.bounds().stepsUsed());
        assertEquals(2500,r.bounds().structuralComponents());assertFalse(r.bounds().truncated());assertEquals(0,r.exactTotal().orElseThrow());
    }
    @Test void smallBooleanOracleAgreesAndLargeSpaceIsNotEnumerated(){
        var p=SymbolicConfiguration.variable("p");var q=SymbolicConfiguration.variable("q");var choices=List.of(p,SymbolicConfiguration.not(p),q,SymbolicConfiguration.not(q));
        for(var first:choices)for(var second:choices)for(var third:choices){
            boolean expected=false;for(boolean pv:List.of(false,true))for(boolean qv:List.of(false,true)){
                var assignment=Map.of("p",pv,"q",qv);expected|=first.evaluate(assignment)&&second.evaluate(assignment)&&third.evaluate(assignment);}
            var f=new Fixture().node("a").node("b").node("c").node("d").edge("a","b",first,SymbolicConfiguration.not(first),SymbolicConfiguration.FALSE)
                    .edge("b","c",second,SymbolicConfiguration.not(second),SymbolicConfiguration.FALSE).edge("c","d",third,SymbolicConfiguration.not(third),SymbolicConfiguration.FALSE);
            assertEquals(expected,!new ArchitectureQueryService(f.graph()).findPaths(f.request(),id("a"),id("d")).items().isEmpty());
        }
        var variables=new ArrayList<SymbolicConfiguration.Formula>();for(int i=0;i<60;i++)variables.add(SymbolicConfiguration.variable("v"+i));
        var all=SymbolicConfiguration.and(variables);var f=new Fixture().node("a").node("b").edge("a","b",all,SymbolicConfiguration.not(all),SymbolicConfiguration.FALSE);
        var r=new ArchitectureQueryService(f.graph()).findPaths(f.request(),id("a"),id("b"));assertEquals(1,r.items().size());assertEquals(60,r.items().getFirst().witness().orElseThrow().assignment().size());
        assertTrue(r.bounds().solverStepsUsed()<1_000_000);
    }
    @Test void operationalGuardsAndInputCapsReturnTypedPartialResults(){
        var f=new Fixture().node("a").edge("a","a");
        for(var reason:List.of(StopReason.CANCELLED,StopReason.MEMORY,StopReason.TIME)){
            var r=new ArchitectureQueryService(f.graph(),()->reason,Optional.empty()).findCycles(f.request());assertTrue(r.items().isEmpty());assertEquals(reason,r.bounds().stopReason());
            assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.PARTIAL,r.status());assertFalse(r.gaps().isEmpty());
        }
        var g=new Fixture().node("a").node("b").edge("a","b").edge("b","a");
        var l=new Limits(20,100_000,50,1,1,100,100,100);
        assertEquals(StopReason.INPUT,new ArchitectureQueryService(g.graph()).findCycles(g.request(l)).bounds().stopReason());
    }
    @Test void stepLimit100000IsGlobalEvenWhenEveryCycleIsInfeasible(){
        var f=new Fixture();int width=48;
        for(int i=0;i<width;i++){f.node("left"+i);f.node("right"+i);}
        for(int i=0;i<width;i++)for(int j=0;j<width;j++){
            f.explicit("left"+i,"right"+j,Set.of("left"),Set.of(),Set.of("left","right"),true);
            f.explicit("right"+i,"left"+j,Set.of("right"),Set.of(),Set.of("left","right"),true);
        }
        var r=new ArchitectureQueryService(f.graph()).findCycles(f.request());assertTrue(r.items().isEmpty());assertEquals(100_000,r.bounds().stepsUsed());
        assertEquals(StopReason.STEPS,r.bounds().stopReason());assertTrue(r.bounds().workBudgetExceeded());assertTrue(r.bounds().rejectedInfeasible()>0);assertTrue(r.exactTotal().isEmpty());
    }
    @Test void solverBudgetAndIncompatibleWorldUniversesAreNeverFalseProofs(){
        var p=SymbolicConfiguration.variable("p");var f=new Fixture().node("a").node("b").edge("a","b",p,SymbolicConfiguration.not(p),SymbolicConfiguration.FALSE);
        var l=new Limits(20,100_000,50,100_000,500_000,1,100_000,200_000);
        var limited=new ArchitectureQueryService(f.graph()).findPaths(f.request(l),id("a"),id("b"));assertEquals(StopReason.SOLVER,limited.bounds().stopReason());
        assertTrue(limited.bounds().solverStepsUsed()<=1);assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.PARTIAL,limited.status());assertTrue(limited.items().isEmpty());
        var g=new Fixture().node("a").node("b").node("c").explicit("a","b",Set.of("one"),Set.of(),Set.of("one"),true)
                .explicit("b","c",Set.of("two"),Set.of(),Set.of("two"),true);
        var incompatible=new ArchitectureQueryService(g.graph()).findPaths(g.request(),id("a"),id("c"));assertTrue(incompatible.items().isEmpty());
        assertTrue(incompatible.gaps().stream().anyMatch(gap->gap.reasonCode().equals("INCOMPATIBLE_REGION_CONTEXT")));assertEquals(0,incompatible.bounds().rejectedInfeasible());
    }
    @Test void pendingPrefixesAreBoundedBeforeQueueGrowth(){
        var f=new Fixture();for(String node:List.of("a","b","c","d","e","f","g","h"))f.node(node);
        for(String mid:List.of("b","c","d")){f.edge("a",mid);for(String end:List.of("e","f","g"))f.edge(mid,end);}
        for(String end:List.of("e","f","g"))f.edge(end,"h");
        var l=new Limits(20,100_000,50,1000,1000,1000,1000,129);
        var r=new ArchitectureQueryService(f.graph()).findPaths(f.request(l),id("a"),id("h"));assertEquals(StopReason.MEMORY,r.bounds().stopReason());
        assertTrue(r.bounds().truncated());assertFalse(r.bounds().frontier().isEmpty());assertFalse(r.gaps().isEmpty());
    }
    @Test void cycleSearchDoesNotBlockASecondPrefixWithADifferentRegion(){
        // Canonical order deliberately explores the infeasible branch first.
        var names=java.util.stream.Stream.of("n0","n1","n2","n3").sorted(Comparator.comparing(ConditionalPathCycleTest::id)).toList();
        String start=names.get(0),first=names.get(1),second=names.get(2),shared=names.get(3);
        var p=SymbolicConfiguration.variable("p");var n=SymbolicConfiguration.not(p);var zero=SymbolicConfiguration.FALSE;
        var f=new Fixture();names.forEach(f::node);
        f.edge(start,first,p,n,zero).edge(start,second,n,p,zero).edge(first,shared).edge(second,shared).edge(shared,start,n,p,zero);
        var result=new ArchitectureQueryService(f.graph()).findCycles(f.request());assertEquals(1,result.items().size());
        assertEquals(List.of(id(start),id(second),id(shared)),result.items().getFirst().vertices());
        assertEquals(false,result.items().getFirst().witness().orElseThrow().assignment().get("p"));assertTrue(result.bounds().rejectedInfeasible()>0);
        assertEquals(1,result.exactTotal().orElseThrow());
    }
}
