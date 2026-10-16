package com.evolution.analysis.query;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.spring.truth.SymbolicConfiguration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.analysis.query.ConditionalPathCycleTest.*;

class WorkbenchQueryTest {
    @Test void sourceRootLabelsAndOwnedFilesUseRecordedBuildProofWithoutExposingOtherValues(){
        var f=new Fixture();String module=id("module");
        var evidence=GraphEvidence.capture("build.module",Map.of("descriptor",Map.of("identity",module,"path","app"),"sourcePlan",Map.of("sourceSets",List.of(Map.of("kind","MAIN","name","main","sourceRoots",List.of(Map.of("value","src/main/java"))))),"secret","PRIVATE_SENTINEL"));
        String root=KnowledgeGraph.derivedIdentity("source-root",List.of(module,"MAIN","main","src/main/java"));
        f.evidence.add(evidence);f.nodes.add(new KnowledgeGraph.Node(root,f.context,"SOURCE_ROOT",List.of(evidence.identity())));
        var q=new ArchitectureQueryService(f.graph());assertEquals("src/main/java",q.entity(f.request(),root).items().getFirst().qualifiedName().orElseThrow());
        assertFalse(com.evolution.analysis.contract.serialization.CanonicalJson.write(q.explain(f.request(),root,Page.first())).contains("PRIVATE_SENTINEL"));
    }
    @Test void metricDefinitionsRetainApprovedFormulaAndUnavailableProviderIsExplicit() {
        var f=new Fixture().node("a");var q=new com.evolution.analysis.metrics.ArchitectureMetricsService(f.graph()).queries();
        var definition=q.metricDefinition(f.request(),"metric.instability").items().getFirst();
        assertEquals("Ce/(Ca+Ce); denominator zero => NOT_APPLICABLE.",definition.get("formula"));
        assertEquals(List.of("MODULE","PACKAGE"),definition.get("scopes"));
        assertThrows(IllegalArgumentException.class,()->q.metricDefinition(f.request(),"metric.invented"));
        var absent=new ArchitectureQueryService(f.graph()).metricDefinition(f.request(),"metric.instability");
        assertTrue(absent.items().isEmpty());assertEquals("METRIC_DEFINITION_UNAVAILABLE",absent.gaps().getFirst().reasonCode());
    }
    @Test void childrenUseOnlyDirectContainmentWithBoundPaginationAndDuplicateSupports() {
        var f=new Fixture().node("parent").node("a").node("b").node("dependency").edge("parent","dependency");
        for(String child:List.of("a","a","b"))f.edges.add(new KnowledgeGraph.Edge(id("contain"+f.edges.size()),f.context,"graph.declares",id("parent"),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(id(child)),Optional.empty()),List.of(f.base.identity()),Optional.empty()));
        var q=new ArchitectureQueryService(f.graph());var first=q.children(f.request(),id("parent"),new Page(1,Optional.empty()));
        assertEquals(1,first.items().size());assertEquals(2L,first.exactTotal().orElseThrow());
        var next=q.children(f.request(),id("parent"),new Page(1,first.continuation()));
        assertNotEquals(first.items().getFirst().identity(),next.items().getFirst().identity());assertTrue(next.continuation().isEmpty());
        assertThrows(IllegalArgumentException.class,()->q.children(f.request(),id("a"),new Page(1,first.continuation())));
        assertThrows(IllegalArgumentException.class,()->q.children(f.request(),id("foreign"),Page.first()));
    }
    @Test void symbolicWitnessAndCounterexampleReplayAtTheirExactAssignments() {
        var p=SymbolicConfiguration.variable("profile-digest");var f=new Fixture().node("a").node("b").edge("a","b",p,SymbolicConfiguration.not(p),SymbolicConfiguration.FALSE);
        var q=new ArchitectureQueryService(f.graph());var r=f.request();var edge=q.edges(r,Page.first()).items().getFirst();
        var predicate=new ArchitectureCondition.RulePredicate(List.of(new ArchitectureCondition.Alternative(new ArchitectureCondition.Term(List.of(edge.identity())),true)),true);
        var region=q.ruleCondition(r,predicate).items().getFirst();assertEquals(Classification.MAY,region.classification());
        var witness=region.witness().orElseThrow();var counter=region.counterWitness().orElseThrow();
        var yes=q.replayRuleWitness(r,predicate,witness,false).items().getFirst();
        assertTrue(yes.matched());assertEquals(Truth.SAT,yes.actualTruth());assertTrue(yes.formulaReplayed());assertFalse(yes.semanticReplayed());
        assertTrue(q.replayRuleWitness(r,predicate,counter,true).items().getFirst().matched());
        assertFalse(q.replayRuleWitness(r,predicate,counter,false).items().getFirst().matched());
        var foreign=new Witness(witness.world(),witness.assignment(),ContentDigest.sha256Utf8("foreign"),true,false);
        var rejected=q.replayRuleWitness(r,predicate,foreign,false);assertEquals(Truth.UNKNOWN,rejected.items().getFirst().actualTruth());assertFalse(rejected.gaps().isEmpty());
        var missing=new Witness(Optional.empty(),Map.of(),witness.jointRegion(),true,false);
        assertEquals(Truth.UNKNOWN,q.replayRuleWitness(r,predicate,missing,false).items().getFirst().actualTruth());
        var stopped=new ArchitectureQueryService(f.graph(),()->StopReason.CANCELLED,Optional.empty()).replayRuleWitness(r,predicate,witness,false);
        assertEquals(Operation.CANCELLED,stopped.operation());assertFalse(stopped.items().getFirst().matched());
        var examples=q.realizations(r,Page.first());assertEquals(2,examples.items().size());
        for(var example:examples.items()){
            var realized=new Request(r.graph(),r.context(),r.scope(),Projection.REALIZED,r.direction(),r.relationshipKinds(),r.population(),false,Optional.of(example.realization()),r.limits());
            var result=q.edges(realized,Page.first());assertEquals(AssessmentStatus.COMPLETE,result.status());
            assertEquals(Boolean.TRUE.equals(example.realization().assignment().get("profile-digest"))?1:0,result.items().size());
        }
        assertEquals(examples,new ArchitectureQueryService(new GraphSnapshotCodec().decode(new GraphSnapshotCodec().encode(f.graph()),GraphJson.Limits.defaults())).realizations(r,Page.first()));
    }
    @Test void recordedWorldsRemainSeparateAndImpossibleCombinedPathsStayAbsent() {
        var f=new Fixture().node("a").node("b").node("c").explicit("a","b",Set.of("dev"),Set.of(),Set.of("dev","prod"),true)
                .explicit("b","c",Set.of("prod"),Set.of(),Set.of("dev","prod"),true);
        var q=new ArchitectureQueryService(f.graph());assertEquals(2,q.realizations(f.request(),Page.first()).items().size());
        assertTrue(q.findPaths(f.request(),id("a"),id("c")).items().isEmpty());
        var region=q.ruleCondition(f.request(),new ArchitectureCondition.RulePredicate(List.of(new ArchitectureCondition.Alternative(new ArchitectureCondition.Term(List.of(q.edges(f.request(),Page.first()).items().getFirst().identity())),true)),true)).items().getFirst();
        assertTrue(region.witness().orElseThrow().world().isPresent());
        assertTrue(q.realizations(f.request(),new Page(1,Optional.empty())).continuation().isPresent());
    }
}
