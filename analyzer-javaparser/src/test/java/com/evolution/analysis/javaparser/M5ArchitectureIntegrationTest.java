package com.evolution.analysis.javaparser;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.truth.*;
import com.evolution.analysis.query.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Real source/build/frontend/Spring/graph/store/query/metric boundaries; no target build execution. */
class M5ArchitectureIntegrationTest {
    @TempDir Path directory;
    @Test void actualNestedLocalAnonymousInventoryReconcilesDeclarationsRatherThanGraphNodes(){
        var input=CanonicalGraphPipelineIntegrationTest.inputs(Map.of("src/main/java/p/Outer.java","""
                package p;
                class Outer {
                  class Member {}
                  void f(int value) { class Local {} Runnable action = new Runnable(){ public void run(){} }; }
                }
                """));
        var detailed=new UniversalArchitecturePipeline().runWithEvidence(input,CanonicalGraphPipelineIntegrationTest.policy());var graph=new CanonicalGraphBuilder().build(detailed.graphInput().orElseThrow());
        var context=graph.contexts().stream().filter(c->c.analysis().isPresent()).findFirst().orElseThrow();var all=request(graph,context.identity(),Scope.ENTITY,Projection.BOUNDED_UNION);
        var r=new Request(all.graph(),all.context(),all.scope(),all.projection(),all.direction(),all.relationshipKinds(),new Population(Set.of(),Set.of(),Set.of("PROJECT"),Set.of()),false,Optional.empty(),all.limits());
        var inventory=new ArchitectureMetricsService(graph).compute(r,"metric.inventory",r.context());var kinds=GraphJson.object(inventory.details().get("declarationsByKind"));
        assertEquals(new BigDecimal("4"),kinds.get("TYPE"));assertEquals(BigDecimal.ONE,inventory.details().get("nestedTypes"));assertEquals(new BigDecimal("2"),inventory.details().get("localTypesIncludingAnonymous"));assertEquals(BigDecimal.ONE,inventory.details().get("anonymousTypes"));
        var frontend=detailed.graphInput().orElseThrow().ingestion().orElseThrow().units().stream().flatMap(u->u.frontend().stream()).findFirst().orElseThrow();
        assertEquals(BigDecimal.valueOf(frontend.declarations().stream().filter(d->d.entity().origin()==com.evolution.analysis.contract.semantic.EntityOrigin.PROJECT).count()),inventory.value().orElseThrow().amount());assertTrue(graph.nodes().size()>frontend.declarations().size());
    }
    static Request request(KnowledgeGraph graph,String context,Scope scope,Projection projection){
        return new Request(graph.identity(),context,scope,projection,Direction.OUTGOING,
                Set.of(scope==Scope.SPRING_COMPONENT?"spring.selected-binding":"graph.depends-on"),Population.all(),false,Optional.empty(),Limits.defaults());
    }
    KnowledgeGraph reload(KnowledgeGraph graph){
        new FileGraphStore(directory).save(graph);var restored=new FileGraphStore(directory).load(graph.identity()).orElseThrow();
        assertEquals(new GraphSnapshotCodec().encode(graph),new GraphSnapshotCodec().encode(restored));return restored;
    }
    @Test void multiModuleSpringPipelineHasIndependentManifestsMetricsAndSourceDrilldown() throws Exception {
        var helper=new SourceToSpringPlanTest();var fixture=helper.sourceFixture("package app; @org.springframework.context.annotation.ComponentScan(\"app\") class App {}");
        var exact=fixture.result().sourceIngestion().outcomes().stream().filter(o->o.sourceSet().equals(fixture.key())).findFirst().orElseThrow().request().orElseThrow();
        String root="<project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>a</module><module>b</module></modules></project>";
        String child="<project><modelVersion>4.0.0</modelVersion><parent><groupId>x</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>%s</artifactId></project>";
        var texts=new TreeMap<String,String>(Map.of("pom.xml",root,"a/pom.xml",child.formatted("a"),"b/pom.xml",child.formatted("b")));
        for(String module:List.of("a","b")){
            texts.put(module+"/src/main/java/app/App.java","package app; @org.springframework.context.annotation.ComponentScan(\"app\") class App {}");
            texts.put(module+"/src/main/java/app/Client.java","package app; @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile(\"dev\") class Client { @org.springframework.beans.factory.annotation.Autowired data.Store store; }");
            texts.put(module+"/src/main/java/data/Store.java","package data; @org.springframework.stereotype.Component public class Store {}");
        }
        // Scan both packages using an explicit source annotation; no directory-based inference.
        texts.replaceAll((path,text)->path.endsWith("App.java")?text.replace("ComponentScan(\"app\")","ComponentScan({\"app\",\"data\"})"):text);
        var inputs=CanonicalGraphPipelineIntegrationTest.inputs(texts);
        var provider=new com.evolution.analysis.maven.MavenBuildModelProvider();
        var build=new UniversalBuildIngestion().ingest(inputs,UniversalBuildIngestion.Policy.defaults(),Optional.of(provider),Map.of(),new BuildModelPolicy(List.of(),List.of(),Map.of(),10000,100,16));
        var resolutions=new HashMap<UniversalSourceIngestion.SourceSet,UniversalSourceIngestion.Resolution>();
        for(var module:build.modules())if(!module.descriptor().path().equals("."))resolutions.put(new UniversalSourceIngestion.SourceSet(module.descriptor().identity(),SourcePlanModel.Kind.MAIN),new UniversalSourceIngestion.Resolution(exact.platform(),exact.dependencies(),ContentDigest.sha256Utf8("m5-captured-classpath")));
        var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER).mavenProvider(provider).defaultPlatform(exact.platform()).resolutions(resolutions).configurationSpace(space(fixture)).build();
        var detailed=new UniversalArchitecturePipeline().runWithEvidence(inputs,policy);var graph=new CanonicalGraphBuilder().build(detailed.graphInput().orElseThrow());var restored=reload(graph);
        var analyses=graph.contexts().stream().filter(c->c.identity().equals(c.analysis().map(a->a.value()).orElse(""))).toList();assertEquals(2,analyses.size());
        var engine=new ArchitectureMetricsService(graph);var reloaded=new ArchitectureMetricsService(restored);
        var requests=analyses.stream().map(c->request(graph,c.identity(),Scope.PACKAGE,Projection.BOUNDED_UNION)).toList();
        var batch=engine.computeBatch(requests,List.of("metric.inventory","metric.density"));assertEquals(4,batch.results().size());
        assertEquals(2,batch.contexts().stream().flatMap(c->c.analyses().stream()).distinct().count());
        for(var context:analyses){
            var r=request(graph,context.identity(),Scope.PACKAGE,Projection.BOUNDED_UNION);var metric=engine.compute(r,"metric.abstractness",new ArchitectureQueryService(graph).entities(r,"app",Page.first()).items().getFirst().identity());
            assertEquals(metric.identity(),reloaded.compute(r,"metric.abstractness",metric.subject()).identity());
            assertEquals(1,metric.context().analyses().size());assertEquals(context.analysis().orElseThrow(),metric.context().analyses().getFirst());
            var query=engine.queries();assertFalse(query.summary(r).items().isEmpty());var types=request(graph,context.identity(),Scope.TYPE,Projection.BOUNDED_UNION);
            var client=query.entities(types,"app.Client",Page.first()).items().getFirst();var store=query.entities(types,"data.Store",Page.first()).items().getFirst();
            var paths=query.findPaths(types,client.identity(),store.identity());assertFalse(paths.items().isEmpty());assertEquals(Truth.SAT,paths.items().getFirst().feasibility());
            assertTrue(client.evidence().stream().anyMatch(e->graph.evidence(e).orElseThrow().family().equals("java.declaration")));
            var source=query.evidence(types,client.evidence().getFirst());assertEquals("java.declaration",source.items().getFirst().family());
            assertTrue(graph.evidence(client.evidence().getFirst()).orElseThrow().canonicalJson().contains("startLine"));
            var quality=engine.compute(r,"metric.quality",r.context());var categories=GraphJson.object(quality.details().get("categories"));assertEquals(com.evolution.analysis.frontend.FrontendRequest.CATEGORIES.size(),categories.size());assertTrue(categories.containsKey("java.calls"));
            categories.values().forEach(v->{var row=GraphJson.object(v);assertEquals(((BigDecimal)row.get("attempted")),((BigDecimal)row.get("emitted")).add((BigDecimal)row.get("unmapped")));});
        }
        var spring=graph.contexts().stream().filter(c->graph.evidence(c.evidence()).orElseThrow().family().equals("spring.context")).toList();assertEquals(2,spring.size());
        for(var c:spring){
            var r=request(graph,c.identity(),Scope.SPRING_COMPONENT,Projection.BOUNDED_UNION);var result=engine.compute(r,"metric.spring",c.identity());
            assertTrue(result.value().orElseThrow().amount().signum()>0,()->"discovered="+result.details().get("inventory")+"; limits="+result.limitations());
            assertEquals(result.identity(),reloaded.compute(r,"metric.spring",c.identity()).identity());
            var active=(BigDecimal)result.details().get("projectedActiveDefinitions");var unknown=(BigDecimal)result.details().get("unknownActivationDefinitions");
            assertTrue(active.add(unknown).signum()>0,"Default pipeline retains candidate activation or its missing-order uncertainty");
            if(unknown.signum()>0){assertEquals(AssessmentStatus.PARTIAL,result.status());assertFalse(result.gaps().isEmpty());}
        }
    }
    static ConfigurationSpace space(SourceToSpringPlanTest.Fixture fixture){
        var version=new VersionedIdentifier("fixture.m5-space","1");var proof=new ConditionEvidence.Derived(List.of(fixture.source().identity()),version,"finite-profile");
        return new ConfigurationSpace(fixture.build(),new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,List.of(),List.of(),fixture.source().identity()),Optional.empty(),
                List.of(new FiniteDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev"),List.of(FiniteDomain.Value.bool(false),FiniteDomain.Value.bool(true)),proof)),List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(),proof),new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of(),Map.of(),List.of(),proof),version,new ConfigurationSpace.FeasibilityPolicy(version,ConfigurationSpace.Limits.conservative(),proof));
    }
    @Test void malformedSiblingRetainsInventoryAndWithholdsRatiosAfterStoreReload(){
        var input=CanonicalGraphPipelineIntegrationTest.inputs(Map.of("src/main/java/p/Good.java","package p; class Good {}","src/main/java/p/Bad.java","package p; class Bad { broken("));
        var detailed=new UniversalArchitecturePipeline().runWithEvidence(input,CanonicalGraphPipelineIntegrationTest.policy());var graph=reload(new CanonicalGraphBuilder().build(detailed.graphInput().orElseThrow()));
        var context=graph.contexts().stream().filter(c->c.analysis().isPresent()).findFirst().orElseThrow();var r=request(graph,context.identity(),Scope.PACKAGE,Projection.BOUNDED_UNION);var engine=new ArchitectureMetricsService(graph);
        var inventory=engine.compute(r,"metric.inventory",r.context());assertEquals(AssessmentStatus.PARTIAL,inventory.status());assertTrue(inventory.value().orElseThrow().amount().signum()>0);assertFalse(inventory.gaps().isEmpty());
        assertEquals(AssessmentStatus.WITHHELD,engine.compute(r,"metric.density",r.context()).status());
        var quality=engine.compute(r,"metric.quality",r.context());assertTrue(GraphJson.object(quality.details().get("sourceOutcomes")).containsKey("ERROR"));assertEquals("NOT_MEASURED_WITHOUT_GROUND_TRUTH",quality.details().get("accuracy"));
    }
    @Test void explicitAndSymbolicMetricsPreserveConditionalPopulationAndActivation() throws Exception {
        var helper=new SourceToSpringPlanTest();var fixture=helper.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev") class Client { Client(Store store){} }
                @org.springframework.stereotype.Component class Store {}
                """);var plan=helper.beanPlan(fixture,true,"Client","Store");var space=space(fixture);var proof=space.domains().getFirst().evidence();
        var acquisitionGaps=Set.of("CONSTRUCTOR_SET_INCOMPLETE","CATEGORY_PARTIAL");
        assertEquals(acquisitionGaps,plan.gaps().stream().map(g->g.reasonCode()).collect(java.util.stream.Collectors.toSet()));
        var baseline=new ConfigurationAssignment(Map.of(space.domains().getFirst().variable(),FiniteDomain.Value.bool(false)),proof);var limits=TruthRegionEvaluation.Limits.conservative();var artifact=ContentDigest.sha256Utf8("fixture.m5-evaluator");
        for(boolean symbolic:List.of(false,true)){
            var explicit=symbolic?Optional.<TruthRegionEvaluation.Result>empty():Optional.of(plan.evaluate(space,baseline,limits,artifact).truth());
            var sym=symbolic?Optional.of(plan.evaluateSymbolic(space,baseline,limits,artifact,4,SymbolicConfiguration.Limits.defaults()).truth()):Optional.<SymbolicTruthRegions.StagedResult>empty();
            var parameters=new CanonicalGraphInput.EvaluationParameters(limits,symbolic?SatConfigurationReasoner.INSTANCE.policy():ExhaustiveConfigurationReasoner.INSTANCE.policy(),artifact,symbolic?4:0,symbolic?Optional.of(SymbolicConfiguration.Limits.defaults()):Optional.empty());
            var slice=new CanonicalGraphInput.SpringSlice(fixture.key(),plan,Optional.of(space),Optional.of(baseline),explicit,sym,Optional.of(parameters));
            var graph=reload(new CanonicalGraphBuilder().build(fixture.source().manifest(),fixture.source().frontend(),List.of(slice)));
            var c=graph.contexts().stream().filter(x->graph.evidence(x.evidence()).orElseThrow().family().equals("spring.context")).findFirst().orElseThrow();var engine=new ArchitectureMetricsService(graph);
            var union=engine.compute(request(graph,c.identity(),Scope.SPRING_COMPONENT,Projection.BOUNDED_UNION),"metric.spring",c.identity());
            var must=engine.compute(request(graph,c.identity(),Scope.SPRING_COMPONENT,Projection.MUST),"metric.spring",c.identity());
            assertNotEquals(union.identity(),must.identity());assertTrue(((BigDecimal)union.details().get("projectedActiveDefinitions")).compareTo((BigDecimal)must.details().get("projectedActiveDefinitions"))>0);
            var cycles=engine.compute(request(graph,c.identity(),Scope.SPRING_COMPONENT,Projection.BOUNDED_UNION),"metric.cycles",c.identity());
            // This source fixture has explicit acquisition gaps: retain them without inventing owner failures.
            assertEquals(AssessmentStatus.WITHHELD,cycles.status(),()->"symbolic="+symbolic+"; "+cycles.limitations());assertTrue(cycles.value().isEmpty());
            assertEquals(BigDecimal.ZERO,cycles.details().get("witnessesReturned"));assertTrue(cycles.limitations().contains("EXACT_CYCLE_TOTAL_UNAVAILABLE"));
            assertFalse(cycles.gaps().isEmpty());assertTrue(cycles.gaps().stream().allMatch(g->g.reasonCode().equals("UPSTREAM_CAPABILITY_GAP")));
            var retainedReasons=cycles.evidence().stream().map(ref->graph.evidence(ref).orElseThrow()).filter(e->e.family().equals("evidence.gap"))
                    .map(e->GraphJson.object(GraphJson.read(e.canonicalJson(),GraphJson.Limits.defaults())).get("reasonCode")).collect(java.util.stream.Collectors.toSet());
            assertTrue(retainedReasons.containsAll(acquisitionGaps));
        }
    }
}
