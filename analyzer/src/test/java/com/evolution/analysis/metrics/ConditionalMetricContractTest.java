package com.evolution.analysis.metrics;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.metrics.StructuralMetricsTest.*;

class ConditionalMetricContractTest {
    @Test void malformedDocumentMetadataProducesAGapInsteadOfCrashingMetric(){
        var f=golden();var doc=f.nodes.stream().filter(n->n.kind().equals("SOURCE_DOCUMENT")).findFirst().orElseThrow();
        var bad=GraphEvidence.capture("source.document",Map.of("module",f.module.identity(),"classification","MAIN","contentDigest","invalid-digest"));f.evidence.add(bad);
        f.nodes.remove(doc);f.nodes.add(new KnowledgeGraph.Node(doc.identity(),doc.context(),doc.kind(),List.of(bad.identity())));
        var r=new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.density",f.context);assertEquals(AssessmentStatus.WITHHELD,r.status());assertFalse(r.gaps().isEmpty());
    }
    static void formula(Fixture fixture,com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula formula){
        for(var child:formula.children())formula(fixture,child);
        if(fixture.nodes.stream().noneMatch(n->n.identity().equals(formula.identity().value()))){var proof=GraphEvidence.capture("spring.formula",formula.view());fixture.evidence.add(proof);fixture.nodes.add(new KnowledgeGraph.Node(formula.identity().value(),fixture.context,"REGION_FORMULA",List.of(proof.identity())));}
    }
    @Test void conditionalMetricsDoNotPromoteImpossibleUnionCycleToARealWorld(){
        var f=new Fixture().type("p","P",false).type("q","Q",false).dependency("P","Q").dependency("Q","P");
        var p=com.evolution.analysis.spring.truth.SymbolicConfiguration.variable("profile.dev");var n=com.evolution.analysis.spring.truth.SymbolicConfiguration.not(p);var z=com.evolution.analysis.spring.truth.SymbolicConfiguration.FALSE;
        formula(f,p);formula(f,n);formula(f,z);f.evidence.add(GraphEvidence.capture("spring.symbolic-result",Map.of("inputIdentity",com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("evaluation"),"signaturesComplete",true)));
        int index=0;for(int i=0;i<f.edges.size();i++){var edge=f.edges.get(i);if(!edge.kind().equals("graph.depends-on"))continue;
            var y=index++==0?p:n;var no=com.evolution.analysis.spring.truth.SymbolicConfiguration.not(y);
            var proof=GraphEvidence.capture("spring.symbolic-region",Map.of("encoding","symbolic-dag-m4u.2-v1","space",id("space"),"evaluationInput",com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("evaluation"),
                    "region",Map.of("fact",Map.of("kind","SELECTED_BINDING","factSemanticsVersion",Map.of("id","spring.conditional-fact","version","m4d-v1")),"trueRegion",y.identity(),"falseRegion",no.identity(),"unknownRegion",z.identity(),"classification","MAY")));
            f.evidence.add(proof);String region=id("region"+index);f.nodes.add(new KnowledgeGraph.Node(region,f.context,"TRUTH_REGION",List.of(proof.identity())));
            f.edges.set(i,new KnowledgeGraph.Edge(edge.identity(),edge.context(),edge.kind(),edge.source(),edge.target(),List.of(proof.identity()),Optional.of(region)));
        }
        var engine=new ArchitectureMetricsService(f.graph());var union=engine.compute(f.request(),"metric.cycles",f.context);assertEquals(java.math.BigDecimal.ZERO,amount(union));assertEquals(AssessmentStatus.COMPLETE,union.status());
        assertTrue(engine.evaluate(f.request(),"metric.cycles",f.context).bounds().rejectedInfeasible()>0);
        var r=f.request();var must=new ArchitectureQuery.Request(r.graph(),r.context(),r.scope(),ArchitectureQuery.Projection.MUST,r.direction(),r.relationshipKinds(),r.population(),false,Optional.empty(),r.limits());
        assertEquals(java.math.BigDecimal.ZERO,amount(engine.compute(must,"metric.density",f.context)));assertEquals(java.math.BigDecimal.ONE,amount(engine.compute(r,"metric.density",f.context)));
        var realized=new ArchitectureQuery.Request(r.graph(),r.context(),r.scope(),ArchitectureQuery.Projection.REALIZED,r.direction(),r.relationshipKinds(),r.population(),false,Optional.of(new ArchitectureQuery.Realization(Optional.empty(),Map.of("profile.dev",true))),r.limits());
        assertEquals(new java.math.BigDecimal("0.5"),amount(engine.compute(realized,"metric.density",f.context)));assertNotEquals(engine.compute(r,"metric.density",f.context).identity(),engine.compute(realized,"metric.density",f.context).identity());
    }
    @Test void unmappedSemanticObservationCannotBecomeCleanZeroCoupling(){
        var f=golden();var doc=f.nodes.stream().filter(n->n.kind().equals("SOURCE_DOCUMENT")).findFirst().orElseThrow();
        var unresolved=GraphEvidence.capture("java.observation",Map.of("document",doc.identity(),"category","java.calls","attribution","UNRESOLVED","origin","MISSING","provenance","VERIFIED","reference","missing-call"));
        f.evidence.add(unresolved);f.nodes.add(new KnowledgeGraph.Node(id("unmapped"),f.context,"OBSERVATION",List.of(unresolved.identity())));
        var result=new ArchitectureMetricsService(f.graph()).compute(f.request(),"metric.instability",id("p"));assertEquals(AssessmentStatus.WITHHELD,result.status());assertFalse(result.gaps().isEmpty());
    }
    @Test void cycleWitnessQuotaNeverBecomesAnExactMetricAndBridgeRetainsBounds(){
        var f=new Fixture();for(int i=0;i<7;i++)f.type("p"+i,"T"+i,false);
        for(int i=0;i<7;i++)for(int j=0;j<7;j++)if(i!=j)f.dependency("T"+i,"T"+j);
        var engine=new ArchitectureMetricsService(f.graph());var metric=engine.compute(f.request(),"metric.cycles",f.context);
        assertEquals(AssessmentStatus.WITHHELD,metric.status());assertTrue(metric.value().isEmpty());assertEquals(new java.math.BigDecimal("50"),metric.details().get("witnessesReturned"));
        var bridge=engine.evaluate(f.request(),"metric.cycles",f.context);assertTrue(bridge.bounds().truncated());assertTrue(bridge.bounds().outputBudgetExceeded());assertEquals(50,bridge.bounds().witnessesReturned());assertTrue(bridge.bounds().stepsUsed()>0);
    }
    @Test void canonicalReorderingAndBatchPartitionsPreserveIdentityWithoutInventedAnalysis(){
        var f=golden();var first=f.graph();Collections.reverse(f.nodes);Collections.reverse(f.edges);Collections.reverse(f.evidence);var reordered=f.graph();
        assertEquals(first.identity(),reordered.identity());var a=new ArchitectureMetricsService(first).compute(f.request(),"metric.density",f.context);
        assertEquals(a.identity(),new ArchitectureMetricsService(reordered).compute(f.request(),"metric.density",f.context).identity());assertTrue(a.context().analyses().isEmpty());
        var batch=new ArchitectureMetricsService(first).computeBatch(List.of(f.request()),List.of("metric.inventory","metric.density"));assertEquals(2,batch.results().size());
        assertThrows(IllegalArgumentException.class,()->new ArchitectureMetricsService(first).computeBatch(List.of(f.request(),f.request()),List.of("metric.density")));
        var limited=new ArchitectureMetricsService(first,new ArchitectureMetric.Limits(10000,1000000,1000000,20),()->ArchitectureQuery.StopReason.NONE,Optional.empty(),List.of()).compute(f.request(),"metric.density",f.context);
        assertEquals(amount(a),amount(limited));assertNotEquals(a.identity(),limited.identity());
    }
    @Test void metricResultsIgnoreUiPagesAndPreserveContextVersionsBudgetsAndTelemetrySeparation(){
        var f=golden();var graph=f.graph();var engine=new ArchitectureMetricsService(graph);var query=engine.queries();var before=engine.compute(f.request(),"metric.density",f.context);
        query.edges(f.request(),new ArchitectureQuery.Page(1,Optional.empty()));query.edges(f.request(),ArchitectureQuery.Page.first());
        assertEquals(before.identity(),engine.compute(f.request(),"metric.density",f.context).identity());
        assertEquals(before.identity(),before.withTelemetry(new ArchitectureMetric.Telemetry(Instant.parse("2026-01-01T00:00:00Z"))).identity());
        assertEquals(graph.identity(),before.context().graph());assertEquals("m5.3-v1",before.metric().version());
        assertEquals(ArchitectureMetric.POPULATION,before.context().population());
        var encoded=new GraphSnapshotCodec().encode(graph);var restored=new ArchitectureMetricsService(new GraphSnapshotCodec().decode(encoded,GraphJson.Limits.defaults()));
        assertEquals(before.identity(),restored.compute(f.request(),"metric.density",f.context).identity());
        var locale=Locale.getDefault();var zone=TimeZone.getDefault();try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals(before.identity(),engine.compute(f.request(),"metric.density",f.context).identity());}finally{Locale.setDefault(locale);TimeZone.setDefault(zone);}
        var r=f.request();var may=new ArchitectureQuery.Request(r.graph(),r.context(),r.scope(),ArchitectureQuery.Projection.MAY,r.direction(),r.relationshipKinds(),r.population(),false,Optional.empty(),r.limits());
        assertNotEquals(before.identity(),engine.compute(may,"metric.density",f.context).identity());
        assertEquals(java.math.BigDecimal.ZERO,amount(engine.compute(may,"metric.density",f.context)));
    }
    @Test void realMetricsPortHasBoundCatalogPaginationAndNoFakeProviderFallback(){
        var f=golden();var engine=new ArchitectureMetricsService(f.graph());var query=engine.queries();var first=query.metricCatalog(f.request(),new ArchitectureQuery.Page(2,Optional.empty()));
        assertEquals(2,first.items().size());assertTrue(first.continuation().isPresent());assertEquals(19,first.exactTotal().orElseThrow());
        var next=query.metricCatalog(f.request(),new ArchitectureQuery.Page(2,first.continuation()));assertTrue(Collections.disjoint(first.items(),next.items()));
        assertThrows(IllegalArgumentException.class,()->query.metricCatalog(f.request(),new ArchitectureQuery.Page(3,first.continuation())));
        var result=query.metric(f.request(),"metric.instability",id("p"));assertEquals(AssessmentStatus.COMPLETE,result.status());assertEquals("COMPLETE",result.items().getFirst().get("status"));
        assertThrows(IllegalArgumentException.class,()->engine.compute(f.request(),"metric.unregistered",id("p")));
    }
    @Test void inputTruncationWithholdsRatiosButRetainsObservedCountsAndTypedGaps(){
        var f=golden();var limits=new ArchitectureMetric.Limits(5,1_000_000,1000,10);var engine=new ArchitectureMetricsService(f.graph(),limits,()->ArchitectureQuery.StopReason.NONE,Optional.empty(),List.of());
        var ratio=engine.compute(f.request(),"metric.density",f.context);assertEquals(AssessmentStatus.WITHHELD,ratio.status());assertTrue(ratio.value().isEmpty());assertFalse(ratio.gaps().isEmpty());
        var inventory=engine.compute(f.request(),"metric.inventory",f.context);assertEquals(AssessmentStatus.PARTIAL,inventory.status());assertTrue(inventory.value().isPresent());
    }
    @Test void operationalDurationsRequireMeasuredProvenanceAndUnknownOverlayIsNotCertainArchitecture(){
        var f=golden();var engine=new ArchitectureMetricsService(f.graph());assertEquals(AssessmentStatus.WITHHELD,engine.compute(f.request(),"metric.operational",f.context).status());
        var r=f.request();var unknown=new ArchitectureQuery.Request(r.graph(),r.context(),r.scope(),ArchitectureQuery.Projection.UNKNOWN_OVERLAY,r.direction(),r.relationshipKinds(),r.population(),false,Optional.empty(),r.limits());
        assertEquals(AssessmentStatus.WITHHELD,engine.compute(unknown,"metric.density",f.context).status());
    }
}
