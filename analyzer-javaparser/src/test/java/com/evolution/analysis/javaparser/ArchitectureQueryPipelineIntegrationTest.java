package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import com.evolution.analysis.spring.truth.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.analysis.javaparser.CanonicalGraphPipelineIntegrationTest.*;

class ArchitectureQueryPipelineIntegrationTest {
    static Request request(KnowledgeGraph graph,String context,Scope scope,Projection projection,Set<String> kinds){return new Request(graph.identity(),context,scope,projection,Direction.OUTGOING,kinds,Population.all(),false,Optional.empty(),Limits.defaults());}
    @Test void controllerToRepositoryAndPackageCycleRetainOccurrenceSpansAfterReload(){
        var detailed=new com.evolution.analysis.ingestion.UniversalArchitecturePipeline().runWithEvidence(inputs(Map.of(
                "src/main/java/api/Controller.java","package api; public class Controller { public void run(){ new data.Repository().load(); new data.Repository().load(); } }",
                "src/main/java/data/Repository.java","package data; public class Repository { public void load(){ api.Controller c = null; } }")),policy());
        var graph=new CanonicalGraphBuilder().build(detailed.graphInput().orElseThrow());var context=graph.nodes().stream().filter(n->n.kind().equals("TYPE")).findFirst().orElseThrow().context();
        var service=new ArchitectureQueryService(graph);var request=request(graph,context,Scope.TYPE,Projection.BOUNDED_UNION,Set.of("graph.depends-on"));
        var controller=service.entities(request,"api.Controller",Page.first()).items().getFirst();var repository=service.entities(request,"data.Repository",Page.first()).items().getFirst();
        var paths=service.findPaths(request,controller.identity(),repository.identity());assertEquals(1,paths.items().size(),()->paths.bounds()+" "+paths.gaps().stream().map(g->g.reasonCode()).toList());assertEquals(Truth.SAT,paths.items().getFirst().feasibility());
        var edge=paths.items().getFirst().edges().getFirst();assertFalse(edge.evidence().isEmpty());
        assertTrue(edge.evidence().stream().map(ref->service.evidence(request,ref)).flatMap(r->r.items().stream()).anyMatch(e->e.metadata().containsKey("span")));
        var calls=request(graph,context,Scope.ENTITY,Projection.BOUNDED_UNION,Set.of("java.calls"));
        assertTrue(service.relationships(calls,Page.first()).items().stream().anyMatch(r->r.evidence().size()==2));
        var packages=request(graph,context,Scope.PACKAGE,Projection.BOUNDED_UNION,Set.of("graph.depends-on"));
        var cycles=service.findCycles(packages);assertEquals(1,cycles.items().size());assertEquals(2,cycles.items().getFirst().vertices().size());
        assertTrue(service.entities(packages,"api",Page.first()).items().stream().anyMatch(e->e.kind().equals("PACKAGE")));
        var codec=new GraphSnapshotCodec();var restored=new ArchitectureQueryService(codec.decode(codec.encode(graph),GraphJson.Limits.defaults()));
        assertEquals(paths,restored.findPaths(request,controller.identity(),repository.identity()));assertEquals(paths.identity(),restored.findPaths(request,controller.identity(),repository.identity()).identity());
        assertEquals(cycles,restored.findCycles(packages));
    }
    @Test void configurationHiddenBindingAndUnsupportedPrototypeRemainEvidenceQualified() throws Exception {
        for(boolean prototype:List.of(false,true)){
        var helper=new SourceToSpringPlanTest();var fixture=helper.sourceFixture(Map.of(
                "app/App.java","package app; @org.springframework.context.annotation.ComponentScan(\"app\") class App {}",
                "app/Client.java","package app; @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile(\"dev\") class Client { public Client(Store store){} }",
                "app/Store.java","package app; @org.springframework.stereotype.Component "+(prototype?"@org.springframework.context.annotation.Scope(\"prototype\") ":"")+"public class Store { public Store(){} }"));
        var plan=helper.beanPlan(fixture,true,"Store","Client");var version=new VersionedIdentifier("query.authored-config","1");
        var proof=new ConditionEvidence.Derived(List.of(fixture.source().identity()),version,"finite-space");var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev");
        var space=new ConfigurationSpace(fixture.build(),new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,List.of(),List.of(),fixture.source().identity()),Optional.empty(),
                List.of(new FiniteDomain(variable,List.of(FiniteDomain.Value.bool(false),FiniteDomain.Value.bool(true)),proof)),List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(),proof),new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of(),Map.of(),List.of(),proof),version,
                new ConfigurationSpace.FeasibilityPolicy(version,ConfigurationSpace.Limits.conservative(),proof));
        var baseline=new ConfigurationAssignment(Map.of(variable,FiniteDomain.Value.bool(false)),proof);var limits=TruthRegionEvaluation.Limits.conservative();var artifact=ContentDigest.sha256Utf8("query-evaluator");
        var explicit=plan.evaluate(space,baseline,limits,artifact).truth();var symbolic=plan.evaluateSymbolic(space,baseline,limits,artifact,4,SymbolicConfiguration.Limits.defaults()).truth();
        if(!prototype)assertTrue(explicit.regions().stream().anyMatch(r->r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING&&!r.trueWorlds().isEmpty()),
                ()->"upstream="+explicit.regions().stream().filter(r->r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING).map(r->r.classification()+":"+r.trueWorlds().size()+":"+r.unknownWorlds().size()).toList()+" gaps="+plan.gaps().stream().map(g->g.reasonCode()).distinct().toList());
        var explicitSlice=new CanonicalGraphInput.SpringSlice(fixture.key(),plan,Optional.of(space),Optional.of(baseline),Optional.of(explicit),Optional.empty(),
                Optional.of(new CanonicalGraphInput.EvaluationParameters(limits,ExhaustiveConfigurationReasoner.INSTANCE.policy(),artifact,0,Optional.empty())));
        var symbolicSlice=new CanonicalGraphInput.SpringSlice(fixture.key(),plan,Optional.of(space),Optional.of(baseline),Optional.empty(),Optional.of(symbolic),
                Optional.of(new CanonicalGraphInput.EvaluationParameters(limits,SatConfigurationReasoner.INSTANCE.policy(),artifact,4,Optional.of(SymbolicConfiguration.Limits.defaults()))));
        String client=helper.candidate(plan,"client").identity().value(),store=helper.candidate(plan,"store").identity().value();
        for(var slice:List.of(explicitSlice,symbolicSlice)){
            var graph=new CanonicalGraphBuilder().build(fixture.source().manifest(),fixture.source().frontend(),List.of(slice));var service=new ArchitectureQueryService(graph);
            String context=graph.nodes().stream().filter(n->n.kind().equals("INJECTION_USE")).findFirst().orElseThrow().context();
            var union=request(graph,context,Scope.SPRING_COMPONENT,Projection.BOUNDED_UNION,Set.of("spring.selected-binding"));
            var paths=service.findPaths(union,client,store);
            if(prototype){
                assertTrue(paths.items().isEmpty());assertFalse(paths.gaps().isEmpty());
                assertFalse(service.edges(request(graph,context,Scope.SPRING_COMPONENT,Projection.UNKNOWN_OVERLAY,Set.of("spring.selected-binding")),Page.first()).items().isEmpty());
                assertTrue(graph.evidence().stream().filter(e->e.family().equals("evidence.gap")).anyMatch(e->e.canonicalJson().contains("ANNOTATION_UNSUPPORTED")));
                var gap=graph.nodes().stream().filter(n->n.kind().equals("CAPABILITY_GAP")).filter(n->n.evidence().stream().anyMatch(ref->graph.evidence(ref).orElseThrow().canonicalJson().contains("ANNOTATION_UNSUPPORTED"))).findFirst().orElseThrow();
                var descriptor=service.explain(union,gap.identity(),Page.first()).items().getFirst();
                assertEquals("ANNOTATION_UNSUPPORTED",descriptor.metadata().get("reasonCode"));assertFalse(GraphJson.array(descriptor.metadata().get("observationReferences")).isEmpty());
                assertFalse(service.gaps(union,Page.first()).items().isEmpty());
                continue;
            }
            assertEquals(1,paths.items().size(),()->paths.bounds()+" "+paths.gaps().stream().map(g->g.reasonCode()).toList());assertEquals(List.of(client,store),paths.items().getFirst().vertices());
            assertTrue(paths.items().getFirst().witness().isPresent());assertFalse(paths.items().getFirst().witness().orElseThrow().semanticReplayed());
            assertTrue(service.edges(request(graph,context,Scope.SPRING_COMPONENT,Projection.MUST,Set.of("spring.selected-binding")),Page.first()).items().isEmpty());
            assertEquals(1,service.edges(request(graph,context,Scope.SPRING_COMPONENT,Projection.MAY,Set.of("spring.selected-binding")),Page.first()).items().size());
            assertFalse(service.configurations(union,Page.first()).items().isEmpty());
            assertEquals(1,service.artifacts(union,Set.of("spring.space"),Page.first()).items().size());
            assertEquals(1,service.artifacts(union,Set.of("spring.space"),Page.first()).items().getFirst().metadata().get("domainCount"));
            var restored=new ArchitectureQueryService(new GraphSnapshotCodec().decode(new GraphSnapshotCodec().encode(graph),GraphJson.Limits.defaults()));
            assertEquals(paths,restored.findPaths(union,client,store));
            if(slice.explicitTruth().isPresent()){
                String disabled=explicit.worlds().stream().filter(w->w.assignment().baseline().get(variable).equals(FiniteDomain.Value.bool(false))).findFirst().orElseThrow().identity().value();
                var realized=new Request(graph.identity(),context,Scope.SPRING_COMPONENT,Projection.REALIZED,Direction.OUTGOING,Set.of("spring.selected-binding"),Population.all(),false,
                        Optional.of(new Realization(Optional.of(disabled),Map.of())),Limits.defaults());assertTrue(service.findPaths(realized,client,store).items().isEmpty());
                var witness=graph.nodes().stream().filter(n->n.kind().equals("CONFIGURATION_WITNESS")).findFirst().orElseThrow();
                assertTrue(service.explain(union,witness.identity(),Page.first()).items().getFirst().metadata().containsKey("legalOrderIdentity"));
                assertFalse(service.explain(union,witness.identity(),Page.first()).items().getFirst().metadata().containsKey("fullAssignment"));
                var replayLinks=request(graph,context,Scope.ENTITY,Projection.BOUNDED_UNION,Set.of("spring.has-replay"));
                assertFalse(service.dependencies(replayLinks,witness.identity(),Page.first()).items().isEmpty());
                var transition=service.spring(union,Set.of("REGISTRATION_TRANSITION"),Page.first()).items().getFirst();
                var eventLinks=request(graph,context,Scope.ENTITY,Projection.BOUNDED_UNION,Set.of("spring.invokes-event"));
                assertEquals(1,service.dependencies(eventLinks,transition.identity(),Page.first()).items().size());
            }
        }
        if(prototype)assertTrue(fixture.source().frontend().annotations().stream().anyMatch(a->a.spelling().contains("prototype")));
        }
    }
    @Test void partialSourceAndMainTestPartitionsDoNotBecomeCleanOrMerge(){
        var detailed=new com.evolution.analysis.ingestion.UniversalArchitecturePipeline().runWithEvidence(inputs(Map.of(
                "src/main/java/p/A.java","package p; class A {}","src/main/java/p/Bad.java","package p; class Bad { broken(",
                "src/test/java/p/A.java","package p; class A {}")),policy());
        var graph=new CanonicalGraphBuilder().build(detailed.graphInput().orElseThrow());
        var contexts=graph.nodes().stream().filter(n->n.kind().equals("TYPE")).map(KnowledgeGraph.Node::context).distinct().toList();assertEquals(2,contexts.size());
        for(String context:contexts){
            var request=request(graph,context,Scope.TYPE,Projection.BOUNDED_UNION,Set.of("graph.depends-on"));var service=new ArchitectureQueryService(graph);
            assertTrue(service.entities(request,"A",Page.first()).items().size()<=1);
            if(graph.nodes().stream().anyMatch(n->n.context().equals(context)&&n.kind().equals("CAPABILITY_GAP")))assertFalse(service.summary(request).gaps().isEmpty());
        }
    }
    @Test void changedSnapshotIsComparableButChangedProviderPolicyIsQualified(){
        var left=new CanonicalGraphBuilder().build(new com.evolution.analysis.ingestion.UniversalArchitecturePipeline().runWithEvidence(inputs(Map.of("src/main/java/p/A.java","package p; class A {}")),policy()).graphInput().orElseThrow());
        var right=new CanonicalGraphBuilder().build(new com.evolution.analysis.ingestion.UniversalArchitecturePipeline().runWithEvidence(inputs(Map.of("src/main/java/p/A.java","package p; class A { int added; }")),policy()).graphInput().orElseThrow());
        var lc=left.contexts().stream().filter(c->left.evidence(c.evidence()).orElseThrow().family().equals("analysis.manifest")).findFirst().orElseThrow();
        var rc=right.contexts().stream().filter(c->right.evidence(c.evidence()).orElseThrow().family().equals("analysis.manifest")).findFirst().orElseThrow();
        var request=request(left,lc.identity(),Scope.TYPE,Projection.BOUNDED_UNION,Set.of("graph.depends-on"));
        var descriptor=new ArchitectureQueryService(left).comparisonPreparation(request,right,rc.identity()).items().getFirst();
        assertFalse(descriptor.sameArtifact());assertTrue(descriptor.comparisonCompatible(),descriptor.reasons().toString());
        var payload=right.evidence(rc.evidence()).orElseThrow();var form=new TreeMap<>(GraphJson.object(GraphJson.read(payload.canonicalJson(),GraphJson.Limits.defaults())));
        form.put("analyzer",Map.of("changed",true));var changed=GraphEvidence.capture("analysis.manifest",form);var evidence=new ArrayList<>(right.evidence());evidence.add(changed);
        var contexts=new ArrayList<>(right.contexts());contexts.remove(rc);contexts.add(new KnowledgeGraph.Context(rc.identity(),rc.snapshot(),rc.analysis(),changed.identity()));
        var changedGraph=new KnowledgeGraph(right.snapshot(),contexts,right.nodes(),right.edges(),evidence);
        assertFalse(new ArchitectureQueryService(left).comparisonPreparation(request,changedGraph,rc.identity()).items().getFirst().comparisonCompatible());
    }
}
