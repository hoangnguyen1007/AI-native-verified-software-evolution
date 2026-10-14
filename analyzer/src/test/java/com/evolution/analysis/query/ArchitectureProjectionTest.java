package com.evolution.analysis.query;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.analysis.query.ConditionalPathCycleTest.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;

class ArchitectureProjectionTest {
    static Request project(Request r,Projection mode,boolean includeMust,Optional<Realization> realized){return new Request(r.graph(),r.context(),r.scope(),mode,r.direction(),r.relationshipKinds(),r.population(),includeMust,realized,r.limits());}
    @Test void fiveModesDistinguishMustMayNeverUnknown(){
        var p=variable("p");var f=new Fixture().node("a").node("must").node("may").node("never").node("unknown")
                .edge("a","must",TRUE,FALSE,FALSE).edge("a","may",p,not(p),FALSE).edge("a","never",FALSE,TRUE,FALSE).edge("a","unknown",FALSE,FALSE,TRUE);
        var service=new ArchitectureQueryService(f.graph());var r=f.request();
        assertEquals(1,service.edges(project(r,Projection.MUST,false,Optional.empty()),Page.first()).items().size());
        assertEquals(1,service.edges(project(r,Projection.MAY,false,Optional.empty()),Page.first()).items().size());
        assertEquals(2,service.edges(project(r,Projection.MAY,true,Optional.empty()),Page.first()).items().size());
        assertEquals(3,service.edges(r,Page.first()).items().size());
        assertEquals(1,service.edges(project(r,Projection.UNKNOWN_OVERLAY,false,Optional.empty()),Page.first()).items().size());
        var realized=project(r,Projection.REALIZED,false,Optional.of(new Realization(Optional.empty(),Map.of("p",false))));
        assertEquals(List.of(id("must")),service.edges(realized,Page.first()).items().stream().map(EdgeView::target).toList());
    }
    @Test void parallelSupportsAreUnionedBeforePathIntersection(){
        var p=variable("p");var f=new Fixture().node("a").node("b").node("c").edge("a","b",p,not(p),FALSE).edge("a","b",not(p),p,FALSE).edge("b","c",p,not(p),FALSE);
        var service=new ArchitectureQueryService(f.graph());var edges=service.edges(project(f.request(),Projection.MUST,false,Optional.empty()),Page.first()).items();
        assertEquals(1,edges.size());assertEquals(2,edges.getFirst().supports().size());assertEquals(Classification.MUST,edges.getFirst().classification());
        assertEquals(1,service.findPaths(f.request(),id("a"),id("c")).items().size());
    }
    @Test void incompleteExplicitSpaceCannotBePromotedToMust(){
        var f=new Fixture().node("a").node("b").explicit("a","b",Set.of("only"),Set.of(),Set.of("only"),false);
        var service=new ArchitectureQueryService(f.graph());assertTrue(service.edges(project(f.request(),Projection.MUST,false,Optional.empty()),Page.first()).items().isEmpty());
        assertEquals(1,service.findPaths(f.request(),id("a"),id("b")).items().size());
    }
    @Test void realizationMustBelongToSelectedExplicitContext(){
        var f=new Fixture().node("a").node("b").explicit("a","b",Set.of("yes"),Set.of(),Set.of("yes","no"),true);
        var service=new ArchitectureQueryService(f.graph());
        assertEquals(1,service.edges(project(f.request(),Projection.REALIZED,false,Optional.of(new Realization(Optional.of(id("worldyes")),Map.of()))),Page.first()).items().size());
        assertTrue(service.edges(project(f.request(),Projection.REALIZED,false,Optional.of(new Realization(Optional.of(id("worldno")),Map.of()))),Page.first()).items().isEmpty());
        var absent=service.edges(project(f.request(),Projection.REALIZED,false,Optional.of(new Realization(Optional.of(id("missing")),Map.of()))),Page.first());
        assertEquals(com.evolution.analysis.contract.assessment.AssessmentStatus.PARTIAL,absent.status());assertFalse(absent.gaps().isEmpty());
    }
}
