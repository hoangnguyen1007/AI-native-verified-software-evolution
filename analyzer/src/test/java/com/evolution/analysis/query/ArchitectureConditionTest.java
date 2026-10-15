package com.evolution.analysis.query;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.analysis.query.ConditionalPathCycleTest.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;
import com.evolution.analysis.contract.assessment.AssessmentStatus;

class ArchitectureConditionTest {
    @Test void emptyClosedRuleIsCleanOnlyInsideANonemptyCompleteUniverse() {
        var f=new Fixture().node("a");var service=new ArchitectureQueryService(f.graph());
        var empty=new ArchitectureCondition.RulePredicate(List.of(),true);
        assertEquals(Classification.NEVER,service.ruleCondition(f.request(),empty).items().getFirst().classification());
        var noWorlds=new Fixture().node("a").node("b").explicit("a","b",Set.of(),Set.of(),Set.of(),true);
        assertEquals(Classification.UNKNOWN,new ArchitectureQueryService(noWorlds.graph()).ruleCondition(noWorlds.request(),empty).items().getFirst().classification());
    }
    @Test void unknownEligibilityAndOpenDenominatorsPreserveKnownRegions(){
        var p=variable("p");var f=new Fixture().node("a").node("b").node("c").edge("a","b",p,not(p),FALSE).edge("b","c",not(p),p,FALSE);
        var service=new ArchitectureQueryService(f.graph());var edges=service.edges(f.request(),Page.first()).items();
        var alternatives=edges.stream().map(e->new ArchitectureCondition.Alternative(new ArchitectureCondition.Term(List.of(e.identity())),true)).toList();
        assertEquals(Classification.MUST,service.ruleCondition(f.request(),new ArchitectureCondition.RulePredicate(alternatives,true)).items().getFirst().classification());
        var unknown=service.ruleCondition(f.request(),new ArchitectureCondition.RulePredicate(alternatives.stream().map(a->new ArchitectureCondition.Alternative(a.term(),false)).toList(),true)).items().getFirst();
        assertEquals(Classification.UNKNOWN,unknown.classification());assertTrue(unknown.witness().isEmpty());assertTrue(unknown.counterWitness().isEmpty());
        var incomplete=service.ruleCondition(f.request(),new ArchitectureCondition.RulePredicate(List.of(alternatives.getFirst()),false)).items().getFirst();
        assertEquals(Classification.UNKNOWN,incomplete.classification());assertTrue(incomplete.witness().isPresent());assertTrue(incomplete.counterWitness().isEmpty());
        assertEquals(Classification.MUST,service.ruleCondition(f.request(),new ArchitectureCondition.RulePredicate(alternatives,false)).items().getFirst().classification());
    }
    @Test void inducedCyclesExcludeOtherEdgesAndRetainUnknownPaths(){
        var f=new Fixture().node("a").node("b").node("c").edge("a","b").edge("b","a").edge("c","c");
        var service=new ArchitectureQueryService(f.graph());var eligible=service.edges(f.request(),Page.first()).items().stream().filter(e->!e.source().equals(id("c"))).map(EdgeView::identity).collect(java.util.stream.Collectors.toSet());
        var cycles=service.policyCycles(f.request(),eligible);assertEquals(1,cycles.items().size());assertEquals(2,cycles.items().getFirst().edges().size());
        var u=new Fixture().node("a").node("b").edge("a","b",FALSE,FALSE,TRUE).edge("b","a");service=new ArchitectureQueryService(u.graph());
        var unknown=service.policyCycles(u.request(),service.edges(u.request(),Page.first()).items().stream().map(EdgeView::identity).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1,unknown.items().size());assertEquals(Truth.UNKNOWN,unknown.items().getFirst().feasibility());assertTrue(unknown.items().getFirst().witness().isEmpty());
    }
    @Test void ruleUniverseUsesPreferredEncodingWhenBothEquivalentRepresentationsExist(){
        var p=variable("p");var f=new Fixture().node("a").node("b")
                .explicit("a","b",Set.of("on"),Set.of(),Set.of("on","off"),true).edge("a","b",p,not(p),FALSE);
        var service=new ArchitectureQueryService(f.graph());var edge=service.edges(f.request(),Page.first()).items().getFirst();
        var predicate=new ArchitectureCondition.RulePredicate(List.of(new ArchitectureCondition.Alternative(new ArchitectureCondition.Term(List.of(edge.identity())),true)),true);
        var result=service.ruleCondition(f.request(),predicate);assertEquals(Classification.MAY,result.items().getFirst().classification());
        assertEquals(true,result.items().getFirst().witness().orElseThrow().assignment().get("p"));
        assertFalse(result.gaps().stream().anyMatch(g->g.reasonCode().equals("INCOMPATIBLE_REGION_CONTEXT")));
    }
    @Test void conflictingSymbolicUniversesCannotNarrowThePolicySpaceIntoAMustVerdict(){
        var p=variable("p");var f=new Fixture().node("a").node("b").node("c")
                .edge("a","b",p,not(p),FALSE).edge("b","c",p,FALSE,FALSE);
        var service=new ArchitectureQueryService(f.graph());var edge=service.edges(f.request(),Page.first()).items().stream()
                .filter(e->e.source().equals(id("a"))).findFirst().orElseThrow();
        var predicate=new ArchitectureCondition.RulePredicate(List.of(new ArchitectureCondition.Alternative(new ArchitectureCondition.Term(List.of(edge.identity())),true)),true);
        var result=service.ruleCondition(f.request(),predicate);assertEquals(Classification.UNKNOWN,result.items().getFirst().classification());
        assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("INCOMPATIBLE_REGION_CONTEXT")));
    }
    @Test void conjunctionUsesJointRegionAndDoesNotFlattenMayEdges() {
        var p=variable("p");var f=new Fixture().node("a").node("b").node("c")
                .edge("a","b",p,not(p),FALSE).edge("b","c",not(p),p,FALSE);
        var service=new ArchitectureQueryService(f.graph());
        var edges=service.edges(f.request(),Page.first()).items().stream().map(EdgeView::identity).toList();
        var result=service.condition(f.request(),new ArchitectureCondition.Predicate(List.of(new ArchitectureCondition.Term(edges))));
        assertEquals(Classification.NEVER,result.items().getFirst().classification());
        assertTrue(result.items().getFirst().witness().isEmpty());
        assertTrue(result.items().getFirst().counterWitness().isPresent());
    }
    static ArchitectureCondition.Predicate all(ArchitectureQueryService service,Request request) {
        return new ArchitectureCondition.Predicate(List.of(new ArchitectureCondition.Term(service.edges(request,Page.first()).items().stream().map(EdgeView::identity).toList())));
    }
    @Test void disjunctionHasIndependentCounterWitnessAndPreservesSeverityOutsideTruth() {
        var p=variable("p");var f=new Fixture().node("a").node("b").edge("a","b",p,not(p),FALSE);
        var service=new ArchitectureQueryService(f.graph());var predicate=all(service,f.request());var result=service.condition(f.request(),predicate);
        var value=result.items().getFirst();assertEquals(Classification.MAY,value.classification());
        assertEquals(true,value.witness().orElseThrow().assignment().get("p"));assertEquals(false,value.counterWitness().orElseThrow().assignment().get("p"));
        assertTrue(value.witness().orElseThrow().formulaReplayed());assertFalse(value.witness().orElseThrow().semanticReplayed());
        assertEquals(value.jointRegion(),value.counterWitness().orElseThrow().jointRegion());
        assertEquals(result,service.condition(f.request(),predicate));
    }
    @Test void alternativeViolatingRegionsAreUnioned() {
        var p=variable("p");var f=new Fixture().node("a").node("b").node("c").edge("a","b",p,not(p),FALSE).edge("b","c",not(p),p,FALSE);
        var service=new ArchitectureQueryService(f.graph());var terms=service.edges(f.request(),Page.first()).items().stream().map(e->new ArchitectureCondition.Term(List.of(e.identity()))).toList();
        var value=service.condition(f.request(),new ArchitectureCondition.Predicate(terms)).items().getFirst();
        assertEquals(Classification.MUST,value.classification());assertTrue(value.witness().isPresent());assertTrue(value.counterWitness().isEmpty());
    }
    @Test void explicitRegionsUseFullConjunctionAndWitnessWorld() {
        var f=new Fixture().node("a").node("b").node("c").node("d")
                .explicit("a","b",Set.of("x","y"),Set.of(),Set.of("x","y","z"),true)
                .explicit("b","c",Set.of("y","z"),Set.of(),Set.of("x","y","z"),true)
                .explicit("c","d",Set.of("x","z"),Set.of(),Set.of("x","y","z"),true);
        var service=new ArchitectureQueryService(f.graph());var value=service.condition(f.request(),all(service,f.request())).items().getFirst();
        assertEquals(Classification.NEVER,value.classification());assertTrue(value.witness().isEmpty());assertTrue(value.counterWitness().orElseThrow().world().isPresent());
    }
    @Test void incompleteUnknownAndForeignEvidenceCannotBecomeHealthy() {
        for(boolean closed:List.of(false,true)) {
            var f=new Fixture().node("a").node("b").explicit("a","b",Set.of(),Set.of("x"),Set.of("x"),closed);
            var service=new ArchitectureQueryService(f.graph());var result=service.condition(f.request(),all(service,f.request()));
            assertEquals(Classification.UNKNOWN,result.items().getFirst().classification());assertEquals(AssessmentStatus.PARTIAL,result.status());
            assertTrue(result.items().getFirst().witness().isEmpty());assertTrue(result.items().getFirst().counterWitness().isEmpty());assertFalse(result.gaps().isEmpty());
        }
        var f=new Fixture().node("a").node("b").edge("a","b");var service=new ArchitectureQueryService(f.graph());
        var missing=service.condition(f.request(),new ArchitectureCondition.Predicate(List.of(new ArchitectureCondition.Term(List.of(id("foreign"))))));
        assertEquals(Classification.UNKNOWN,missing.items().getFirst().classification());assertTrue(missing.gaps().stream().anyMatch(g->g.reasonCode().equals("PREDICATE_EDGE_UNAVAILABLE")));
    }
    @Test void cancelledAndSolverLimitedPredicatesRetainGaps() {
        var p=variable("p");var f=new Fixture().node("a").node("b").edge("a","b",p,not(p),FALSE);
        var predicate=all(new ArchitectureQueryService(f.graph()),f.request());
        var cancelled=new ArchitectureQueryService(f.graph(),()->StopReason.CANCELLED,Optional.empty()).condition(f.request(),predicate);
        assertEquals(Operation.CANCELLED,cancelled.operation());assertEquals(Classification.UNKNOWN,cancelled.items().getFirst().classification());
        var l=new ArchitectureQuery.Limits(20,100_000,50,100_000,500_000,1,100_000,200_000);
        var limited=new ArchitectureQueryService(f.graph()).condition(f.request(l),predicate);
        assertEquals(AssessmentStatus.PARTIAL,limited.status());assertEquals(Classification.UNKNOWN,limited.items().getFirst().classification());assertFalse(limited.gaps().isEmpty());
    }
    @Test void incompleteAndIncompatibleUniversesCannotProveUniversalPredicates() {
        var incomplete=new Fixture().node("a").node("b").explicit("a","b",Set.of("x"),Set.of(),Set.of("x"),false);
        var service=new ArchitectureQueryService(incomplete.graph());var result=service.condition(incomplete.request(),all(service,incomplete.request()));
        assertEquals(Classification.UNKNOWN,result.items().getFirst().classification());assertTrue(result.items().getFirst().witness().isPresent());
        assertTrue(result.items().getFirst().counterWitness().isEmpty());assertEquals(AssessmentStatus.PARTIAL,result.status());
        var incompatible=new Fixture().node("a").node("b").node("c")
                .explicit("a","b",Set.of("x"),Set.of(),Set.of("x"),true)
                .explicit("b","c",Set.of("y"),Set.of(),Set.of("y"),true);
        service=new ArchitectureQueryService(incompatible.graph());result=service.condition(incompatible.request(),all(service,incompatible.request()));
        assertEquals(Classification.UNKNOWN,result.items().getFirst().classification());assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("INCOMPATIBLE_REGION_CONTEXT")));
    }
    @Test void expressionOrderIsCanonicalAndProjectionMustRetainAllRegions() {
        var f=new Fixture().node("a").node("b").node("c").edge("a","b").edge("b","c");var service=new ArchitectureQueryService(f.graph());
        var predicate=all(service,f.request());var reversed=new ArrayList<>(predicate.alternatives().getFirst().edges());Collections.reverse(reversed);
        assertEquals(predicate,new ArchitectureCondition.Predicate(List.of(new ArchitectureCondition.Term(reversed))));
        var request=f.request();var must=new Request(request.graph(),request.context(),request.scope(),Projection.MUST,request.direction(),request.relationshipKinds(),request.population(),false,Optional.empty(),request.limits());
        assertThrows(IllegalArgumentException.class,()->service.condition(must,predicate));
        assertThrows(IllegalArgumentException.class,()->new ArchitectureCondition.Predicate(List.of()));
        assertThrows(IllegalArgumentException.class,()->new ArchitectureCondition.Term(List.of()));
    }
}
