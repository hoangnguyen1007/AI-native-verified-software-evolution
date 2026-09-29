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
        return sourceFixture(text);
    }
    private Fixture sourceFixture(String text) throws Exception {
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

    private Fixture beanFixture(String metadata, String qualifier) throws Exception {
        return sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                class Client { Client(Store store) {} }
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
                @org.springframework.context.annotation.Profile("dev") class Config {
                  @org.springframework.context.annotation.Bean %s Store store() { return new Store(); }
                  @org.springframework.context.annotation.Bean Client client(%s Store store) { return new Client(store); }
                }
                """.formatted(metadata,qualifier));
    }
    private SourceToSpringPlan.Result beanPlan(Fixture f, boolean known, String... names) {
        var order=Arrays.stream(names).map(name -> f.source().declarations().values().stream()
                .filter(d -> d.entity().origin()==com.evolution.analysis.contract.semantic.EntityOrigin.PROJECT)
                .filter(d -> ("app."+name).equals(f.source().typeName(d.entity().identity()))
                        || d.entity().kind()==com.evolution.analysis.contract.semantic.EntityKind.METHOD
                          && d.entity().canonicalName().contains("\""+name+"\",["))
                .map(d -> d.entity().identity()).findFirst().orElseThrow()).toList();
        var proof=new ConditionEvidence.Derived(List.of(f.source().identity()),new VersionedIdentifier("test.authored-scope","1"),"bean-reader-order");
        return f.pipeline().prepareSpring(f.result(),f.key(),new SourceToSpringPlan.Scope("authored-context",f.source().identity(),order,
                known?COMPLETE:UNKNOWN,COMPLETE,COMPLETE,COMPLETE,COMPLETE,RegistrationPlan.OverridePolicy.FORBID,proof),100);
    }
    @Test void beanMethodsProduceConditionalBindingsFromActualSource() throws Exception {
        var f=beanFixture("@org.springframework.beans.factory.annotation.Qualifier(\"chosen\")",
                "@org.springframework.beans.factory.annotation.Qualifier(\"chosen\")");
        var plan=beanPlan(f,true,"Config","store","client");
        assertEquals(3,plan.binding().registrationPlan().steps().size());
        assertEquals(1,plan.binding().dependencies().size());
        assertEquals(com.evolution.analysis.spring.binding.InjectionPoint.SiteKind.BEAN_PARAMETER,
                plan.binding().dependencies().getFirst().point().siteKind());
        var result=evaluate(f,plan);
        assertTrue(result.regions().stream().anyMatch(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING
                &&r.classification()==TruthRegionEvaluation.Classification.MAY),result.operationalOutcomes().toString());
        assertFalse(result.replays().isEmpty());
        assertTrue(result.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        assertEquals(result.identity(),evaluate(f,beanPlan(f,true,"Config","store","client")).identity());
        for(boolean active:List.of(false,true))try(var context=container(active)) {
            context.register(OracleConfig.class);context.refresh();
            assertEquals(active,context.containsBean("client"));
            if(active)assertSame(context.getBean("store"),context.getBean(OracleBeanClient.class).store);
            assertEquals(context.containsBean("client"),selected(result,active).contains(candidate(plan,"store").identity().value()));
        }
    }

    static class OracleBeanStore {}
    static class OracleBeanClient { final OracleBeanStore store; OracleBeanClient(OracleBeanStore store){this.store=store;} }
    @org.springframework.context.annotation.Configuration(value="config",proxyBeanMethods=false)
    @org.springframework.context.annotation.Profile("dev")
    static class OracleConfig {
        @org.springframework.context.annotation.Bean @org.springframework.beans.factory.annotation.Qualifier("chosen")
        OracleBeanStore store(){return new OracleBeanStore();}
        @org.springframework.context.annotation.Bean OracleBeanClient client(@org.springframework.beans.factory.annotation.Qualifier("chosen") OracleBeanStore store){return new OracleBeanClient(store);}
    }
    private org.springframework.context.annotation.AnnotationConfigApplicationContext container(boolean active) {
        var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        var environment=new org.springframework.core.env.StandardEnvironment();var properties=environment.getPropertySources();
        for(var property:java.util.stream.StreamSupport.stream(properties.spliterator(),false).toList())properties.remove(property.getName());
        environment.setActiveProfiles(active?new String[]{"dev"}:new String[]{"disabled"});context.setEnvironment(environment);return context;
    }
    private Set<String> selected(TruthRegionEvaluation.Result result,boolean active) {
        var world=result.worlds().stream().filter(w -> w.assignment().baseline().get(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev"))
                .equals(FiniteDomain.Value.bool(active))).findFirst().orElseThrow();
        return result.regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING&&r.trueWorlds().contains(world.identity()))
                .map(r -> r.fact().target().orElseThrow().value()).collect(java.util.stream.Collectors.toSet());
    }
    private BeanDefinitionCandidate candidate(SourceToSpringPlan.Result plan,String name) {
        return plan.binding().registrationPlan().discoveryPlan().events().stream().flatMap(e -> e.candidate().stream())
                .filter(c -> c.declaredNameKey().primary().filter(name::equals).isPresent()).findFirst().orElseThrow();
    }
    @Test void beanOrderMustIncludeMethodsAndOwnerBeforeItsProducts() throws Exception {
        var f=beanFixture("","");
        assertThrows(IllegalArgumentException.class,() -> beanPlan(f,true,"Config","client"));
        assertThrows(IllegalArgumentException.class,() -> beanPlan(f,true,"store","Config","client"));
        var unknown=beanPlan(f,false);
        assertTrue(evaluate(f,unknown).regions().stream().allMatch(r -> r.classification()==TruthRegionEvaluation.Classification.UNKNOWN));
    }
    @Test void beanQualifierMismatchAndMissingProducerMetadataDoNotSelect() throws Exception {
        var wrong=beanFixture("@org.springframework.beans.factory.annotation.Qualifier(\"chosen\")",
                "@org.springframework.beans.factory.annotation.Qualifier(\"wrong\")");
        var result=evaluate(wrong,beanPlan(wrong,true,"Config","store","client"));
        assertTrue(selected(result,true).isEmpty());
        assertTrue(result.regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING)
                .allMatch(r -> r.classification()==TruthRegionEvaluation.Classification.NEVER));
        var opaque=beanFixture("@org.springframework.context.annotation.Lazy","");
        var plan=beanPlan(opaque,true,"Config","store","client");
        assertFalse(plan.gaps().isEmpty());assertTrue(selected(evaluate(opaque,plan),true).isEmpty());
    }
    @Test void distinctBeanProducersWithSameReturnTypeUsePrimaryAndKeepSourceEvidence() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev")
                class Client { Client(Store store) {} }
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary Store first() { return new Store(); }
                  @org.springframework.context.annotation.Bean Store second() { return new Store(); }
                }
                """);
        var plan=beanPlan(f,true,"Config","first","second","Client");
        assertNotEquals(candidate(plan,"first").identity(),candidate(plan,"second").identity());
        assertEquals(Set.of(candidate(plan,"first").identity().value()),selected(evaluate(f,plan),true));
        assertEquals(com.evolution.analysis.spring.binding.InjectionPoint.SiteKind.CONSTRUCTOR_PARAMETER,plan.binding().dependencies().getFirst().point().siteKind());
        assertInstanceOf(ConditionEvidence.Source.class,candidate(plan,"first").producer().declarationEvidenceKey());
        assertEquals(1,plan.binding().registrationPlan().discoveryPlan().inventory().obligations().stream()
                .filter(o -> o.primaryMechanism().equals("spring.injection.constructor.implicit")).count());
    }
    @Test void instanceFactoryAndStaticFactoryOwnershipStayDistinct() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean static Store first() { return new Store(); }
                  @org.springframework.context.annotation.Bean Store second() { return new Store(); }
                }
                """);
        var plan=beanPlan(f,true,"Config","first","second");
        var definitions=plan.binding().definitions();
        assertTrue(definitions.stream().filter(d -> d.candidate().equals(candidate(plan,"first").identity())).findFirst().orElseThrow().factoryOwner().isEmpty());
        assertEquals(Optional.of(candidate(plan,"config").identity()),definitions.stream().filter(d -> d.candidate().equals(candidate(plan,"second").identity())).findFirst().orElseThrow().factoryOwner());
    }
    @Test void missingBeanConditionUsesActualRegistrationPrefixAndReversingItChangesPresence() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class ConfigFirst {
                  @org.springframework.context.annotation.Bean Store first() { return new Store(); }
                }
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class ConfigSecond {
                  @org.springframework.context.annotation.Bean
                  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name="first")
                  Store second() { return new Store(); }
                }
                """);
        var normal=beanPlan(f,true,"ConfigFirst","first","ConfigSecond","second");
        var reversed=beanPlan(f,true,"ConfigSecond","second","ConfigFirst","first");
        assertNotEquals(normal.identity(),reversed.identity());
        assertEquals(TruthRegionEvaluation.Classification.NEVER,presence(evaluate(f,normal),candidate(normal,"second")));
        assertEquals(TruthRegionEvaluation.Classification.MUST,presence(evaluate(f,reversed),candidate(reversed,"second")));
        try(var context=container(true)) {context.register(OracleFirst.class,OracleSecond.class);context.refresh();assertFalse(context.containsBean("second"));}
        try(var context=container(true)) {context.register(OracleSecond.class,OracleFirst.class);context.refresh();assertTrue(context.containsBean("second"));}
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleFirst { @org.springframework.context.annotation.Bean OracleBeanStore first(){return new OracleBeanStore();} }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleSecond {
        @org.springframework.context.annotation.Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name="first")
        OracleBeanStore second(){return new OracleBeanStore();}
    }
    private TruthRegionEvaluation.Classification presence(TruthRegionEvaluation.Result result,BeanDefinitionCandidate candidate) {
        return result.regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.DEFINITION_PRESENT
                &&r.fact().definition().filter(candidate.identity()::equals).isPresent()).findFirst().orElseThrow().classification();
    }
    @Test void beanNameAndEligibilityFlagsMatchPinnedContainerDefinitions() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                class Client { Client(Store store) {} }
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean(name="named",defaultCandidate=false) Store store() { return new Store(); }
                  @org.springframework.context.annotation.Bean(autowireCandidate=false) Store excluded() { return new Store(); }
                  @org.springframework.context.annotation.Bean Client client(@org.springframework.beans.factory.annotation.Qualifier("named") Store store) { return new Client(store); }
                }
                """);
        var plan=beanPlan(f,true,"Config","store","excluded","client");
        assertEquals(Set.of(candidate(plan,"named").identity().value()),selected(evaluate(f,plan),true));
        try(var context=container(true)) {
            context.register(OracleFlags.class);context.refresh();
            assertFalse(context.getBeanFactory().getBeanDefinition("excluded").isAutowireCandidate());
            assertFalse(((org.springframework.beans.factory.support.AbstractBeanDefinition)context.getBeanFactory().getBeanDefinition("named")).isDefaultCandidate());
            assertSame(context.getBean("named"),context.getBean(OracleBeanClient.class).store);
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleFlags {
        @org.springframework.context.annotation.Bean(name="named",defaultCandidate=false) OracleBeanStore store(){return new OracleBeanStore();}
        @org.springframework.context.annotation.Bean(autowireCandidate=false) OracleBeanStore excluded(){return new OracleBeanStore();}
        @org.springframework.context.annotation.Bean OracleBeanClient client(@org.springframework.beans.factory.annotation.Qualifier("named") OracleBeanStore store){return new OracleBeanClient(store);}
    }
    @Test void missingReturnTypeAndUnsupportedAliasesRemainExplicitCandidatesWithGaps() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean Unknown store() { return null; }
                  @org.springframework.context.annotation.Bean({"one","two"}) Store other() { return new Store(); }
                }
                """);
        var plan=beanPlan(f,true,"Config","store","other");
        assertEquals(3,plan.binding().registrationPlan().steps().size());assertFalse(plan.gaps().isEmpty());
        assertTrue(candidate(plan,"store").exposedTypes().isEmpty());
        assertTrue(plan.binding().registrationPlan().discoveryPlan().events().stream()
                .filter(e -> e.kind()==RegistrationEvent.Kind.BEAN_METHOD).allMatch(e -> e.conditionMetadata()==UNKNOWN));
        assertTrue(evaluate(f,plan).regions().stream().noneMatch(r -> r.classification()==TruthRegionEvaluation.Classification.MUST));
    }
    @Test void producerNameCollisionCannotInventReaderOverrideBehavior() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean("duplicate") Store first() { return new Store(); }
                  @org.springframework.context.annotation.Bean("duplicate") Store second() { return new Store(); }
                }
                """);
        var plan=beanPlan(f,true,"Config","first","second");
        assertTrue(plan.binding().registrationPlan().discoveryPlan().events().stream()
                .filter(e -> e.kind()==RegistrationEvent.Kind.BEAN_METHOD).allMatch(e -> e.conditionMetadata()==UNKNOWN));
        assertFalse(plan.gaps().isEmpty());
    }
    @Test void unprovedRuntimeParameterNameRemainsUnknownForMultipleFactoryCandidates() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                class Client { Client(Store store) {} }
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean Store first() { return new Store(); }
                  @org.springframework.context.annotation.Bean Store second() { return new Store(); }
                  @org.springframework.context.annotation.Bean Client client(Store first) { return new Client(first); }
                }
                """);
        var plan=beanPlan(f,true,"Config","first","second","client");
        assertEquals(com.evolution.analysis.spring.binding.InjectionBindingPlan.NameStatus.UNKNOWN,
                plan.binding().dependencies().getFirst().dependencyName().status());
        assertTrue(evaluate(f,plan).regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING)
                .allMatch(r -> r.classification()==TruthRegionEvaluation.Classification.UNKNOWN));
    }
    @Test void duplicatePrimaryProducersKeepAmbiguityAndCannotPublishASelection() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                class Client { Client(Store store) {} }
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary Store first() { return new Store(); }
                  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary Store second() { return new Store(); }
                  @org.springframework.context.annotation.Bean Client client(Store store) { return new Client(store); }
                }
                """);
        var result=evaluate(f,beanPlan(f,true,"Config","first","second","client"));
        assertTrue(selected(result,true).isEmpty());
        assertTrue(result.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("PRIMARY_CONFLICT")));
        try(var context=container(true)) {
            context.register(OraclePrimaryConflict.class);
            assertThrows(org.springframework.beans.factory.UnsatisfiedDependencyException.class,context::refresh);
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OraclePrimaryConflict {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary OracleBeanStore first(){return new OracleBeanStore();}
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary OracleBeanStore second(){return new OracleBeanStore();}
        @org.springframework.context.annotation.Bean OracleBeanClient client(OracleBeanStore store){return new OracleBeanClient(store);}
    }
    @Test void projectAnnotationCannotBecomeABeanProducerAndProxyMetadataStaysOpen() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @interface Bean {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @Bean Store store() { return new Store(); }
                }
                """);
        var plan=beanPlan(f,true,"Config");
        assertTrue(plan.binding().registrationPlan().discoveryPlan().events().stream().noneMatch(e -> e.kind()==RegistrationEvent.Kind.BEAN_METHOD));
        assertFalse(plan.gaps().isEmpty());
        var enhanced=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration class Config {
                  @org.springframework.context.annotation.Bean Store store() { return new Store(); }
                }
                """);
        var enhancedPlan=beanPlan(enhanced,true,"Config","store");
        assertFalse(enhancedPlan.gaps().isEmpty());
        assertTrue(enhancedPlan.binding().registrationPlan().discoveryPlan().events().stream().allMatch(e -> e.conditionMetadata()==UNKNOWN));
    }
    @Test void defaultBeanNameUsesJavaIdentifierEqualityRatherThanRawSpelling() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean Store st%sore() { return new Store(); }
                }
                """.formatted("\u200b"));
        var plan=beanPlan(f,true,"Config","store");
        var method=plan.binding().registrationPlan().discoveryPlan().events().stream()
                .filter(e -> e.kind()==RegistrationEvent.Kind.BEAN_METHOD).findFirst().orElseThrow();
        assertEquals(Optional.of("store"),method.candidate().orElseThrow().declaredNameKey().primary());
        try(var context=container(true)) {
            context.register(OracleIdentifier.class);context.refresh();assertTrue(context.containsBean("store"));
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleIdentifier {
        @org.springframework.context.annotation.Bean OracleBeanStore st\u200bore(){return new OracleBeanStore();}
    }
}
