package com.evolution.analysis.javaparser;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.spring.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import com.evolution.analysis.spring.truth.*;
import com.evolution.analysis.spring.universal.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.registration.RegistrationEvent.Completeness.*;

class SourceToSpringPlanTest {
    @org.springframework.stereotype.Component("store") @org.springframework.context.annotation.Profile("dev")
    static class OracleStore {}
    @org.springframework.stereotype.Component("client") @org.springframework.context.annotation.Profile("dev")
    static class OracleClient {
        final OracleStore store;
        OracleClient(OracleStore store) {this.store=store;}
    }
    record Fixture(UniversalIngestionPipeline pipeline,UniversalIngestionPipeline.Result result,
                   UniversalSourceIngestion.SourceSet key,SpringSourceEvidence source,SpringBuildContext build) {}
    private Fixture fixture(String qualifier) throws Exception {
        String text="""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev") class Store {}
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev") class Client {
                  Client(%s Store store) {}
                }
                """.formatted(qualifier);
        var artifactFixture=ComponentIngestionTest.fixture(Map.of("fixture/App.java",text),false);
        var binaries=new ArrayList<>(artifactFixture.request().dependencies());
        var artifacts=new ArrayList<>(artifactFixture.framework().artifacts());
        for(var entry:Map.of("org.springframework:spring-aop:6.2.0",org.springframework.aop.SpringProxy.class,
                "org.springframework:spring-expression:6.2.0",org.springframework.expression.Expression.class,
                "org.springframework:spring-jcl:6.2.0",org.apache.commons.logging.Log.class).entrySet()) {
            var path=java.nio.file.Path.of(entry.getValue().getProtectionDomain().getCodeSource().getLocation().toURI());
            var digest=ContentDigest.sha256(java.nio.file.Files.readAllBytes(path));
            binaries.add(new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,entry.getKey()+"@jar",digest),path));
            artifacts.add(new SpringFrameworkEvidence.Artifact(entry.getKey(),digest));
        }
        var files=new TreeMap<String,SourceInput>();
        Map.of("build.gradle","plugins { java }", "src/main/java/app/App.java",text).forEach((path,value) -> {
            byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
            files.put(path,new SourceInput(SourceDocument.create(TestInputs.REPO,TestInputs.MODULE,path,ContentDigest.sha256(bytes),SourceClassification.MAIN),bytes));
        });
        var docs=files.values().stream().map(SourceInput::document).toList();
        var inputs=new RepositoryInputs(RepositorySnapshot.create(TestInputs.REPO,Optional.empty(),false,docs.stream().map(SnapshotFile::from).toList(),docs),files);
        var build=new UniversalBuildIngestion().ingest(inputs,new UniversalBuildIngestion.Policy(Optional.of(21),Optional.of(21),Optional.of("UTF-8"),100,10000,1000,16),
                Optional.empty(),Map.of(),new BuildModelPolicy(List.of(),List.of(),Map.of(),10000,100,16));
        var key=new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(),SourcePlanModel.Kind.MAIN);
        var component=new ManifestComponent(new VersionedIdentifier("test.components","1"),ContentDigest.sha256Utf8("source-plan-test"));
        var assembled=new UniversalSourceIngestion().assemble(inputs,build,Map.of(key,new UniversalSourceIngestion.Resolution(
                artifactFixture.request().platform(),binaries,ContentDigest.sha256Utf8("captured-spring-classpath"))),component,component,component);
        var pipeline=new UniversalIngestionPipeline();
        var result=pipeline.run(assembled,new JavaParserFrontend(),JavaParserFrontend.PROVIDER,100,100);
        var unit=result.units().stream().filter(u -> u.sourceSet().equals(key)).findFirst().orElseThrow();
        assertEquals(UniversalIngestionPipeline.Status.ANALYZED,unit.status(),result.issues().toString());
        var request=assembled.outcomes().stream().filter(o -> o.sourceSet().equals(key)).findFirst().orElseThrow().request().orElseThrow();
        var framework=new SpringFrameworkEvidence(true,Optional.of(request.plan().classpathManifest()),artifacts);
        return new Fixture(pipeline,result,key,new SpringSourceEvidence(request.manifest(),unit.frontend().orElseThrow(),framework),
                SpringBuildContext.fromExactRequest(request,assembled.identity()));
    }
    private SourceToSpringPlan.Result prepare(Fixture fixture,boolean knownOrder) {
        return prepare(fixture,knownOrder,COMPLETE,100);
    }
    private SourceToSpringPlan.Result prepare(Fixture fixture,boolean knownOrder,RegistrationEvent.Completeness registry,int limit) {
        var components=fixture.result().units().stream().filter(u -> u.sourceSet().equals(fixture.key())).findFirst().orElseThrow().components().orElseThrow();
        var order=components.rows().stream().filter(r -> r.status()==ComponentScanIngestion.Status.INCLUDED).map(ComponentScanIngestion.Row::type).toList();
        var evidence=new ConditionEvidence.Derived(List.of(fixture.source().identity()),new VersionedIdentifier("test.authored-scope","1"),"explicit-container-order-and-closure");
        var scope=new SourceToSpringPlan.Scope("authored-context",fixture.source().identity(),knownOrder?order:List.of(),
                knownOrder?COMPLETE:UNKNOWN,registry,COMPLETE,COMPLETE,COMPLETE,RegistrationPlan.OverridePolicy.FORBID,evidence);
        return fixture.pipeline().prepareSpring(fixture.result(),fixture.key(),scope,limit);
    }
    private TruthRegionEvaluation.Result evaluate(Fixture f,SourceToSpringPlan.Result plan) {
        var version=new VersionedIdentifier("test.authored-config","1");
        var proof=new ConditionEvidence.Derived(List.of(f.source().identity()),version,"finite-deployment-space");
        var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev");
        var space=new ConfigurationSpace(f.build(),new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,List.of(),List.of(),f.source().identity()),
                Optional.empty(),List.of(new FiniteDomain(variable,List.of(FiniteDomain.Value.bool(false),FiniteDomain.Value.bool(true)),proof)),List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(),proof),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of(),Map.of(),List.of(),proof),version,
                new ConfigurationSpace.FeasibilityPolicy(version,ConfigurationSpace.Limits.conservative(),proof));
        var baseline=new ConfigurationAssignment(Map.of(variable,FiniteDomain.Value.bool(false)),proof);
        var evaluated=plan.evaluate(space,baseline,
                TruthRegionEvaluation.Limits.conservative(),ContentDigest.sha256Utf8("authored-replay-policy"));
        var symbolicallyEvaluated=plan.evaluateSymbolic(space,baseline,TruthRegionEvaluation.Limits.conservative(),
                ContentDigest.sha256Utf8("authored-replay-policy"),4,SymbolicConfiguration.Limits.defaults());
        assertTrue(evaluated.capabilityGaps().containsAll(plan.gaps()));
        assertTrue(symbolicallyEvaluated.capabilityGaps().containsAll(plan.gaps()));
        var exhaustive=evaluated.truth();var symbolic=symbolicallyEvaluated.truth();
        assertEquals(exhaustive.regions().stream().map(r -> List.of(r.fact(),r.classification())).toList(),
                symbolic.regions().stream().map(r -> List.of(r.fact(),r.classification())).toList());
        return exhaustive;
    }
    @Test void sourceThroughUniversalPipelineProducesRealPlansBindingsRegionsAndReplayedWitnesses() throws Exception {
        var fixture=fixture("");var plan=prepare(fixture,true);
        assertTrue(plan.gaps().containsAll(fixture.result().gaps()),"Continuation must retain the upstream evidence ledger");
        assertEquals(2,plan.binding().registrationPlan().steps().size());
        assertEquals(1,plan.binding().dependencies().size());
        assertTrue(plan.binding().registrationPlan().discoveryPlan().events().stream().allMatch(e -> e.conditionMetadata()==COMPLETE),
                fixture.source().frontend().diagnostics().stream().map(d -> d.code()).distinct().toList().toString());
        assertTrue(plan.binding().registrationPlan().discoveryPlan().issues().isEmpty(),
                plan.binding().registrationPlan().discoveryPlan().issues().stream().map(i -> i.reason()).toList().toString());
        var result=evaluate(fixture,plan);
        assertTrue(result.regions().stream().anyMatch(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING
                &&r.classification()==TruthRegionEvaluation.Classification.MAY),result.operationalOutcomes().toString());
        assertFalse(result.replays().isEmpty());assertTrue(result.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        assertEquals(result.identity(),evaluate(fixture,prepare(fixture,true)).identity());
        // Actual pinned container over fixed authored classes; no target source is executed.
        assertEquals("6.2.0",org.springframework.context.annotation.AnnotationConfigApplicationContext.class.getPackage().getImplementationVersion());
        for(boolean active:List.of(false,true))try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            var environment=new org.springframework.core.env.StandardEnvironment();
            var propertySources=environment.getPropertySources();
            for(var property:java.util.stream.StreamSupport.stream(propertySources.spliterator(),false).toList())propertySources.remove(property.getName());
            environment.setActiveProfiles(active?new String[]{"dev"}:new String[]{"disabled"});
            context.setEnvironment(environment);context.register(OracleStore.class,OracleClient.class);context.refresh();
            boolean selected=context.containsBean("client")&&context.containsBean("store");
            if(selected)assertSame(context.getBean(OracleStore.class),context.getBean(OracleClient.class).store);
            var world=result.worlds().stream().filter(w -> w.assignment().baseline().get(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev"))
                    .equals(FiniteDomain.Value.bool(active))).findFirst().orElseThrow();
            assertEquals(selected,result.regions().stream().anyMatch(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING&&r.trueWorlds().contains(world.identity())));
        }
    }
    @Test void missingOrderCannotCreateASelectionOrUniversalPresence() throws Exception {
        var fixture=fixture("");var result=evaluate(fixture,prepare(fixture,false));
        assertTrue(result.regions().stream().allMatch(r -> r.classification()==TruthRegionEvaluation.Classification.UNKNOWN));
        assertFalse(result.capabilityGaps().isEmpty());
    }
    @Test void wrongQualifierRemainsADefiniteNonSelection() throws Exception {
        var fixture=fixture("@org.springframework.beans.factory.annotation.Qualifier(\"different\")");
        var result=evaluate(fixture,prepare(fixture,true));
        assertTrue(result.regions().stream().anyMatch(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING));
        assertTrue(result.regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING)
                .allMatch(r -> r.classification()==TruthRegionEvaluation.Classification.NEVER));
    }
    @Test void emptyQualifierIsAnExplicitGapRatherThanAnExceptionOrSelection() throws Exception {
        var fixture=fixture("@org.springframework.beans.factory.annotation.Qualifier");
        var plan=assertDoesNotThrow(() -> prepare(fixture,true));
        assertFalse(plan.gaps().isEmpty());
        assertEquals(com.evolution.analysis.spring.binding.InjectionBindingPlan.Normalization.INCOMPLETE,
                plan.binding().dependencies().getFirst().normalization());
        assertTrue(evaluate(fixture,plan).regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING)
                .noneMatch(r -> r.classification()==TruthRegionEvaluation.Classification.MAY||r.classification()==TruthRegionEvaluation.Classification.MUST));
    }
    @Test void openRegistryAndAcquisitionBudgetCannotCertifyBinding() throws Exception {
        var fixture=fixture("");
        var open=evaluate(fixture,prepare(fixture,true,UNKNOWN,100));
        assertTrue(open.regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING)
                .allMatch(r -> r.classification()==TruthRegionEvaluation.Classification.UNKNOWN));
        var bounded=prepare(fixture,true,COMPLETE,1);
        assertFalse(bounded.gaps().isEmpty());
        assertEquals(UNKNOWN,bounded.binding().environment().descriptorsComplete());
    }
}
