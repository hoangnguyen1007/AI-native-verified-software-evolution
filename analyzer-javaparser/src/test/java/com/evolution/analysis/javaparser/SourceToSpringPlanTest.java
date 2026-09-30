package com.evolution.analysis.javaparser;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.spring.*;
import com.evolution.analysis.spring.binding.*;
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
    @org.springframework.beans.factory.annotation.Qualifier("chosen")
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
        return sourceFixture(Map.of("app/App.java",text));
    }
    private Fixture sourceFixture(Map<String,String> sources) throws Exception {
        var artifactFixture=ComponentIngestionTest.fixture(sources,false);
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
        if(sources.values().stream().anyMatch(text -> text.contains("jakarta.inject."))) {
            var path=java.nio.file.Path.of(jakarta.inject.Named.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var digest=ContentDigest.sha256(java.nio.file.Files.readAllBytes(path));
            binaries.add(new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"jakarta.inject:jakarta.inject-api:2.0.1@jar",digest),path));
            artifacts.add(new SpringFrameworkEvidence.Artifact("jakarta.inject:jakarta.inject-api:2.0.1",digest));
        }
        var files=new TreeMap<String,SourceInput>();
        var captured=new TreeMap<String,String>();captured.put("build.gradle","plugins { java }");
        sources.forEach((path,value) -> captured.put("src/main/java/"+path,value));
        captured.forEach((path,value) -> {
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
    @Test void inheritedHiddenFieldsKeepDeclaringPointsAndConditionalComponentOwners() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component("chosen") class Store {}
                abstract class Base {
                  @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("chosen") private Store field;
                }
                class Middle extends Base {}
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev") class Client extends Middle {
                  @org.springframework.beans.factory.annotation.Autowired Store field;
                }
                """);
        var plan=prepare(f,true);
        var fields=plan.binding().dependencies().stream().filter(d -> d.point().siteKind()==InjectionPoint.SiteKind.FIELD).toList();
        assertEquals(2,fields.size(),"Both hidden fields are injected, including the private ancestor field");
        assertTrue(fields.stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.COMPLETE));
        assertEquals(Set.of(candidate(plan,"client").identity()),fields.stream().flatMap(d -> d.owner().stream()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2,fields.stream().map(d -> d.point().identity()).distinct().count());
        assertEquals(Set.of("app.Base","app.Client"),fields.stream().map(d -> f.source().typeName(
                new com.evolution.analysis.contract.identity.EntityIdentity(d.point().ownerDeclarationKey()))).collect(java.util.stream.Collectors.toSet()));
        assertTrue(fields.stream().allMatch(d -> d.dependencyName().equals(InjectionBindingPlan.Name.of("field"))));
        assertTrue(fields.stream().allMatch(d -> d.evidence() instanceof ConditionEvidence.Source s&&s.span().isPresent()&&f.build().containsSource(s)));
        assertEquals(2,bindingAt(f,plan,true).rows().stream().filter(r -> r.outcome()==InjectionBindings.Outcome.SELECTED).count());
        assertTrue(bindingAt(f,plan,false).rows().stream().allMatch(r -> r.outcome()==InjectionBindings.Outcome.NOT_ACTIVE));
        var truth=evaluate(f,plan);
        assertEquals(2,truth.regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING
                &&r.classification()==TruthRegionEvaluation.Classification.MAY).count());
        assertFalse(truth.replays().isEmpty());assertTrue(truth.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        assertEquals(truth.identity(),evaluate(f,prepare(f,true)).identity());
        assertEquals("6.2.0",org.springframework.context.annotation.AnnotationConfigApplicationContext.class.getPackage().getImplementationVersion());
        for(boolean active:List.of(false,true))try(var context=container(active)) {
            context.register(OracleInheritedStore.class,OracleInheritedClient.class);context.refresh();
            assertEquals(active,context.containsBean("inheritedClient"));
            if(active) {
                var client=context.getBean(OracleInheritedClient.class);
                assertSame(context.getBean(OracleInheritedStore.class),((OracleInheritedBase)client).field);
                assertSame(((OracleInheritedBase)client).field,client.field);
            }
        }
    }
    @org.springframework.stereotype.Component("chosen") static class OracleInheritedStore {}
    static abstract class OracleInheritedBase {
        @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("chosen") private OracleInheritedStore field;
    }
    static class OracleInheritedMiddle extends OracleInheritedBase {}
    @org.springframework.stereotype.Component("inheritedClient") @org.springframework.context.annotation.Profile("dev")
    static class OracleInheritedClient extends OracleInheritedMiddle {
        @org.springframework.beans.factory.annotation.Autowired OracleInheritedStore field;
    }
    @Test void sharedAncestorFieldsFanOutAcrossComponentsAndKeepOriginalDocuments() throws Exception {
        var f=sourceFixture(Map.of("base/Base.java","""
                package base;
                public abstract class Base {
                  @org.springframework.beans.factory.annotation.Autowired private app.Store store;
                }
                ""","app/Store.java","package app; @org.springframework.stereotype.Component public class Store {}",
                "app/App.java","""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev") class Client extends base.Base {}
                @org.springframework.stereotype.Component class Other extends base.Base {}
                """));
        var plan=prepare(f,true);var fields=plan.binding().dependencies();
        assertEquals(2,fields.size());assertEquals(1,fields.stream().map(d -> d.point().identity()).distinct().count());
        assertEquals(2,fields.stream().map(InjectionBindingPlan.Dependency::identity).distinct().count());
        assertEquals(Set.of(candidate(plan,"client").identity(),candidate(plan,"other").identity()),
                fields.stream().flatMap(d -> d.owner().stream()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(fields.stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.COMPLETE));
        var span=((ConditionEvidence.Source)fields.getFirst().evidence()).span().orElseThrow();
        assertEquals(3,span.startLine());assertEquals(3,span.endLine());assertTrue(span.endColumn()>span.startColumn());
        var document=f.source().manifest().snapshot().documents().stream().filter(d -> d.identity().equals(span.document())).findFirst().orElseThrow();
        assertEquals("src/main/java/base/Base.java",document.path());
        assertEquals("store",f.source().declarations().values().stream()
                .filter(d -> d.entity().identity().value().equals(fields.getFirst().point().siteSlot())).findFirst().orElseThrow().spelling());
        assertEquals(2,bindingAt(f,plan,true).rows().stream().filter(r -> r.outcome()==InjectionBindings.Outcome.SELECTED).count());
        assertEquals(Set.of(InjectionBindings.Outcome.SELECTED,InjectionBindings.Outcome.NOT_ACTIVE),
                bindingAt(f,plan,false).rows().stream().map(InjectionBindings.Row::outcome).collect(java.util.stream.Collectors.toSet()));
        assertTrue(plan.binding().obligations().stream().anyMatch(o -> o.dependencies().size()==2));
        var truth=evaluate(f,plan);
        assertEquals(Set.of(TruthRegionEvaluation.Classification.MUST,TruthRegionEvaluation.Classification.MAY),truth.regions().stream()
                .filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING&&!r.trueWorlds().isEmpty())
                .map(TruthRegionEvaluation.Region::classification).collect(java.util.stream.Collectors.toSet()));
        assertEquals(plan.identity(),prepare(f,true).identity());
    }
    @Test void unannotatedHiddenChildFieldDoesNotSuppressAncestorInjection() throws Exception {
        var f=inheritedFixture("@org.springframework.beans.factory.annotation.Autowired private Store field;","Store field;","");
        var plan=prepare(f,true);assertEquals(1,plan.binding().dependencies().size());
        assertEquals("app.Base",f.source().typeName(new com.evolution.analysis.contract.identity.EntityIdentity(
                plan.binding().dependencies().getFirst().point().ownerDeclarationKey())));
        assertEquals(InjectionBindings.Outcome.SELECTED,bindingAt(f,plan,true).rows().getFirst().outcome());
        try(var context=container(true)) {
            context.register(OracleInheritedStore.class,OracleUnannotatedHiddenClient.class);context.refresh();
            var client=context.getBean(OracleUnannotatedHiddenClient.class);
            assertSame(context.getBean(OracleInheritedStore.class),((OracleInheritedBase)client).field);assertNull(client.field);
        }
    }
    @org.springframework.stereotype.Component static class OracleUnannotatedHiddenClient extends OracleInheritedBase {OracleInheritedStore field;}
    private Fixture inheritedFixture(String baseMembers,String childMembers,String baseMetadata) throws Exception {
        return sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                %s abstract class Base { %s }
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev") class Client extends Base { %s }
                """.formatted(baseMetadata,baseMembers,childMembers));
    }
    @Test void inheritedQualifierMismatchRetainsOptionalAbsenceAndRequiredFailure() throws Exception {
        for(boolean required:List.of(false,true)) {
            var f=inheritedFixture("@org.springframework.beans.factory.annotation.Autowired(required="+required+") "
                    +"@org.springframework.beans.factory.annotation.Qualifier(\"missing\") Store field;","","");
            var plan=prepare(f,true);var dependency=plan.binding().dependencies().getFirst();
            assertEquals(InjectionBindingPlan.Normalization.COMPLETE,dependency.normalization());
            assertEquals(required?InjectionBindingPlan.Required.REQUIRED:InjectionBindingPlan.Required.OPTIONAL,dependency.required());
            assertEquals(InjectionBindingPlan.Name.of("missing"),dependency.suggestedName());
            assertEquals(required?InjectionBindings.Outcome.UNSATISFIED:InjectionBindings.Outcome.ABSENT_OPTIONAL,
                    bindingAt(f,plan,true).rows().getFirst().outcome());
            assertEquals(InjectionBindings.Outcome.NOT_ACTIVE,bindingAt(f,plan,false).rows().getFirst().outcome());
            assertTrue(selected(evaluate(f,plan),true).isEmpty());
        }
        try(var context=container(true)) {
            context.register(OracleInheritedStore.class,OracleInheritedOptionalClient.class);context.refresh();
            assertNull(((OracleInheritedOptionalBase)context.getBean(OracleInheritedOptionalClient.class)).field);
        }
        try(var context=container(true)) {
            context.register(OracleInheritedStore.class,OracleInheritedRequiredClient.class);
            assertThrows(org.springframework.beans.factory.UnsatisfiedDependencyException.class,context::refresh);
        }
    }
    static abstract class OracleInheritedOptionalBase {
        @org.springframework.beans.factory.annotation.Autowired(required=false) @org.springframework.beans.factory.annotation.Qualifier("missing") OracleInheritedStore field;
    }
    @org.springframework.stereotype.Component static class OracleInheritedOptionalClient extends OracleInheritedOptionalBase {}
    static abstract class OracleInheritedRequiredBase {
        @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("missing") OracleInheritedStore field;
    }
    @org.springframework.stereotype.Component static class OracleInheritedRequiredClient extends OracleInheritedRequiredBase {}
    @Test void unsupportedSourceHierarchiesNeverCertifyMemberClosure() throws Exception {
        for(String header:List.of("class Base<T> {} class Client extends Base<Store>",
                "class Base<T> {} class Client extends Base", "class Client extends Missing",
                "class Client extends java.util.ArrayList<Store>","interface Base {} class Client implements Base")) {
            // Component annotation belongs to Client in each variant.
            var split=header.lastIndexOf("class Client");
            var f=sourceFixture("""
                    package app;
                    @org.springframework.context.annotation.ComponentScan("app") class App {}
                    @org.springframework.stereotype.Component class Store {}
                    %s @org.springframework.stereotype.Component %s {
                      @org.springframework.beans.factory.annotation.Autowired Store field;
                    }
                    """.formatted(header.substring(0,split),header.substring(split)));
            var plan=prepare(f,true);
            assertFalse(plan.gaps().isEmpty());assertEquals(UNKNOWN,plan.binding().environment().descriptorsComplete());
            assertEquals(1,plan.binding().dependencies().size());
            assertEquals(InjectionBindingPlan.Normalization.INCOMPLETE,plan.binding().dependencies().getFirst().normalization());
            assertTrue(bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED),header);
        }
    }
    @Test void inheritedClassMetadataAndComposedMemberMetadataRetainFootprintGaps() throws Exception {
        var f=inheritedFixture("@org.springframework.beans.factory.annotation.Autowired Store field;","",
                "@org.springframework.context.annotation.Primary");
        var plan=prepare(f,true);
        assertFalse(plan.gaps().isEmpty());assertTrue(bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        var composed=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.beans.factory.annotation.Autowired @interface Wire {}
                @org.springframework.stereotype.Component class Store {}
                abstract class Base { @Wire Store field; }
                @org.springframework.stereotype.Component class Client extends Base {}
                """);
        var qualified=prepare(composed,true);
        assertEquals(UNKNOWN,qualified.binding().environment().descriptorsComplete());assertFalse(qualified.gaps().isEmpty());
        assertTrue(qualified.inventory().rawObservations().stream().anyMatch(r -> r.spelling().contains("@Wire")));
    }
    @Test void inheritedMethodsKeepOwnerSpecificUnknownGroupsWithoutOverrideGuesses() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                abstract class Base { @org.springframework.beans.factory.annotation.Autowired void wire(Store a,Store b) {} }
                @org.springframework.stereotype.Component class Client extends Base { @Override void wire(Store a,Store b) {} }
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev") class Other extends Base {}
                """);
        var plan=prepare(f,true);
        assertEquals(4,plan.binding().dependencies().size());assertEquals(2,plan.binding().groups().size());
        assertTrue(plan.binding().groups().stream().allMatch(g -> g.dependencies().size()==2));
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.INCOMPLETE));
        assertTrue(bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        assertEquals(2,bindingAt(f,plan,false).rows().stream().filter(r -> r.outcome()==InjectionBindings.Outcome.NOT_ACTIVE).count());
        assertFalse(plan.gaps().isEmpty());
    }
    @Test void inheritedFieldFanOutRetainsEveryRequestWhenDescriptorBudgetIsExhausted() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                abstract class Base {
                  @org.springframework.beans.factory.annotation.Autowired Store first;
                  @org.springframework.beans.factory.annotation.Autowired Store second;
                }
                @org.springframework.stereotype.Component class Client extends Base {}
                @org.springframework.stereotype.Component class Other extends Base {}
                """);
        var plan=prepare(f,true,COMPLETE,3);
        assertEquals(4,plan.binding().dependencies().size());assertEquals(UNKNOWN,plan.binding().environment().descriptorsComplete());
        assertEquals(4,bindingAt(f,plan,true).rows().size());assertFalse(plan.gaps().isEmpty());
        assertEquals(4,plan.binding().obligations().stream().flatMap(o -> o.dependencies().stream()).distinct().count());
        assertEquals(plan.identity(),prepare(f,true,COMPLETE,3).identity());
    }
    @Test void boundedHierarchyCannotTreatTruncationAsATerminalClass() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                abstract class Base { @org.springframework.beans.factory.annotation.Autowired Store field; }
                class Middle extends Base {}
                @org.springframework.stereotype.Component class Client extends Middle {}
                """);
        var client=candidate(prepare(f,true),"client").exposedTypes().getFirst();
        assertFalse(f.source().sourceHierarchy(client,2).complete());assertTrue(f.source().sourceHierarchy(client,2).limited());
        assertEquals(3,f.source().sourceHierarchy(client,3).types().size());assertTrue(f.source().sourceHierarchy(client,3).complete());
        var bounded=prepare(f,true,COMPLETE,2);
        assertFalse(bounded.gaps().isEmpty());assertEquals(UNKNOWN,bounded.binding().environment().descriptorsComplete());
        assertTrue(bindingAt(f,bounded,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
    }
    @Test void missingOrConflictingSuperclassMetadataCannotProveHierarchyClosure() throws Exception {
        var f=inheritedFixture("@org.springframework.beans.factory.annotation.Autowired Store field;","","");
        var frontend=f.source().frontend();
        var client=candidate(prepare(f,true),"client").exposedTypes().getFirst();
        var store=candidate(prepare(f,true),"store").exposedTypes().getFirst();
        assertTrue(f.source().sourceHierarchy(client,100).complete());
        var absent=frontend.types().stream().filter(t -> !t.role().value().equals("java.extends")).toList();
        var conflicting=frontend.types().stream().map(t -> t.role().value().equals("java.extends")?
                new TypeUseRecord(t.owner(),t.role(),t.span(),new JavaType(JavaType.Kind.DECLARED,t.type().spelling(),Optional.of(store),
                        List.of(),Optional.empty(),com.evolution.analysis.contract.semantic.SemanticStatus.RESOLVED),false):t).toList();
        for(var types:List.of(absent,conflicting)) {
            var changed=new FrontendResult(frontend.analysis(),frontend.frontend(),frontend.state(),frontend.declarations(),frontend.occurrences(),frontend.observations(),
                    frontend.sources(),frontend.coverage(),frontend.diagnostics(),types,frontend.annotations(),frontend.derivedRelationships(),frontend.typeDeclarations(),frontend.memberDeclarations());
            var source=new SpringSourceEvidence(f.source().manifest(),changed,f.source().framework());
            assertFalse(source.sourceHierarchy(client,100).complete());
            assertEquals(types==absent?SpringSourceEvidence.HierarchyProblem.PARENT_EVIDENCE_MISSING:SpringSourceEvidence.HierarchyProblem.PARENT_EVIDENCE_CONFLICT,
                    source.sourceHierarchy(client,100).problem());
        }
        assertThrows(IllegalArgumentException.class,() -> f.source().sourceHierarchy(client,0));
    }
    @Test void inheritedStaticAndUnresolvedFieldsRetainUnknownRequestsAndUpstreamEvidence() throws Exception {
        for(String field:List.of("@org.springframework.beans.factory.annotation.Autowired static Store field;",
                "@org.springframework.beans.factory.annotation.Autowired Missing field;")) {
            var f=inheritedFixture(field,"","");var plan=prepare(f,true);
            assertTrue(plan.gaps().containsAll(f.result().gaps()));assertFalse(plan.gaps().isEmpty());
            assertEquals(UNKNOWN,plan.binding().environment().descriptorsComplete());
            assertEquals(1,plan.binding().dependencies().size());
            assertEquals(InjectionBindingPlan.Normalization.INCOMPLETE,plan.binding().dependencies().getFirst().normalization());
            assertTrue(plan.binding().dependencies().getFirst().evidence() instanceof ConditionEvidence.Source s&&s.span().isPresent());
            assertTrue(bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        }
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
    private ConfigurationSpace space(Fixture f) {
        var version=new VersionedIdentifier("test.authored-config","1");
        var proof=new ConditionEvidence.Derived(List.of(f.source().identity()),version,"finite-deployment-space");
        var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev");
        return new ConfigurationSpace(f.build(),new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,List.of(),List.of(),f.source().identity()),
                Optional.empty(),List.of(new FiniteDomain(variable,List.of(FiniteDomain.Value.bool(false),FiniteDomain.Value.bool(true)),proof)),List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(),proof),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of(),Map.of(),List.of(),proof),version,
                new ConfigurationSpace.FeasibilityPolicy(version,ConfigurationSpace.Limits.conservative(),proof));
    }
    private InjectionBindings.Result bindingAt(Fixture f,SourceToSpringPlan.Result plan,boolean active) {
        var space=space(f);var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev");
        return InjectionBindings.evaluate(plan.binding(),ConditionModel.create(space,plan.conditions().occurrences()),
                new ConfigurationAssignment(Map.of(variable,FiniteDomain.Value.bool(active)),space.domains().getFirst().evidence()),
                ExogenousConditionEvaluator.Limits.conservative());
    }
    private TruthRegionEvaluation.Result evaluate(Fixture f,SourceToSpringPlan.Result plan) {
        var space=space(f);var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev");
        var baseline=new ConfigurationAssignment(Map.of(variable,FiniteDomain.Value.bool(false)),space.domains().getFirst().evidence());
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
        return beanPlan(f,known,100,names);
    }
    private SourceToSpringPlan.Result beanPlan(Fixture f, boolean known, int limit, String... names) {
        var order=Arrays.stream(names).map(name -> f.source().declarations().values().stream()
                .filter(d -> d.entity().origin()==com.evolution.analysis.contract.semantic.EntityOrigin.PROJECT)
                .filter(d -> ("app."+name).equals(f.source().typeName(d.entity().identity()))
                        || d.entity().kind()==com.evolution.analysis.contract.semantic.EntityKind.METHOD
                          && d.entity().canonicalName().contains("\""+name+"\",["))
                .map(d -> d.entity().identity()).findFirst().orElseThrow()).toList();
        var proof=new ConditionEvidence.Derived(List.of(f.source().identity()),new VersionedIdentifier("test.authored-scope","1"),"bean-reader-order");
        return f.pipeline().prepareSpring(f.result(),f.key(),new SourceToSpringPlan.Scope("authored-context",f.source().identity(),order,
                known?COMPLETE:UNKNOWN,COMPLETE,COMPLETE,COMPLETE,COMPLETE,RegistrationPlan.OverridePolicy.FORBID,proof),limit);
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
    private Fixture memberFixture(String members) throws Exception {
        return sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev")
                @org.springframework.beans.factory.annotation.Qualifier("chosen") class Store {}
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev")
                class Client { %s }
                """.formatted(members));
    }
    @Test void voidMethodQualifierIsAcquiredForEachUnqualifiedParameter() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired
                @org.springframework.beans.factory.annotation.Qualifier("chosen")
                void wire(Store first, Store second) {}
                """);
        var plan=prepare(f,true);
        assertEquals(2,plan.binding().dependencies().size());
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.COMPLETE));
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.suggestedName().equals(InjectionBindingPlan.Name.of("chosen"))));
        assertTrue(bindingAt(f,plan,true).rows().stream().allMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        assertTrue(bindingAt(f,plan,false).rows().stream().allMatch(r -> r.outcome()==InjectionBindings.Outcome.NOT_ACTIVE));
        var truth=evaluate(f,plan);
        assertEquals(2,truth.regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING
                &&r.classification()==TruthRegionEvaluation.Classification.MAY).count());
        assertFalse(truth.replays().isEmpty());
        assertTrue(truth.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        assertEquals(plan.identity(),prepare(f,true).identity());
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleMethodQualifier.class);context.refresh();
            var client=context.getBean(OracleMethodQualifier.class);
            assertSame(context.getBean(OracleStore.class),client.first);assertSame(client.first,client.second);
        }
    }
    @org.springframework.stereotype.Component
    static class OracleMethodQualifier {
        OracleStore first,second;
        @org.springframework.beans.factory.annotation.Autowired
        @org.springframework.beans.factory.annotation.Qualifier("chosen")
        void wire(OracleStore first,OracleStore second){this.first=first;this.second=second;}
    }
    @Test void parameterQualifierOverridesMatchingConflictingAndEmptyMethodMetadata() throws Exception {
        for(String methodQualifier:List.of("chosen","missing","")) {
            var f=memberFixture("""
                    @org.springframework.beans.factory.annotation.Autowired
                    @org.springframework.beans.factory.annotation.Qualifier("%s")
                    void wire(@org.springframework.beans.factory.annotation.Qualifier("chosen") Store store) {}
                    """.formatted(methodQualifier));
            var plan=prepare(f,true);
            assertEquals(InjectionBindingPlan.Normalization.COMPLETE,plan.binding().dependencies().getFirst().normalization());
            assertEquals(InjectionBindingPlan.Name.of("chosen"),plan.binding().dependencies().getFirst().suggestedName());
            assertEquals(InjectionBindings.Outcome.SELECTED,bindingAt(f,plan,true).rows().getFirst().outcome());
        }
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleParameterOverride.class,OracleEmptyMethodOverride.class);context.refresh();
            assertSame(context.getBean(OracleStore.class),context.getBean(OracleParameterOverride.class).store);
            assertSame(context.getBean(OracleStore.class),context.getBean(OracleEmptyMethodOverride.class).store);
        }
    }
    @org.springframework.stereotype.Component
    static class OracleParameterOverride {
        OracleStore store;
        @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("missing")
        void wire(@org.springframework.beans.factory.annotation.Qualifier("chosen") OracleStore store){this.store=store;}
    }
    @org.springframework.stereotype.Component
    static class OracleEmptyMethodOverride {
        OracleStore store;
        @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier
        void wire(@org.springframework.beans.factory.annotation.Qualifier("chosen") OracleStore store){this.store=store;}
    }
    @Test void mismatchingParameterDoesNotFallBackToMatchingMethodQualifier() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("chosen")
                void wire(@org.springframework.beans.factory.annotation.Qualifier("missing") Store store) {}
                """);
        var plan=prepare(f,true);
        assertEquals(InjectionBindingPlan.Normalization.COMPLETE,plan.binding().dependencies().getFirst().normalization());
        assertEquals(InjectionBindings.Outcome.UNSATISFIED,bindingAt(f,plan,true).rows().getFirst().outcome());
        assertTrue(selected(evaluate(f,plan),true).isEmpty());
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleMismatchingParameter.class);
            assertThrows(org.springframework.beans.factory.UnsatisfiedDependencyException.class,context::refresh);
        }
    }
    @org.springframework.stereotype.Component
    static class OracleMismatchingParameter {
        @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("chosen")
        void wire(@org.springframework.beans.factory.annotation.Qualifier("missing") OracleStore store){}
    }
    @Test void nonVoidMethodQualifierDoesNotFilterItsUnqualifiedParameters() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("missing")
                Store wire(Store store) { return store; }
                """);
        var plan=prepare(f,true);
        assertEquals(InjectionBindingPlan.Normalization.COMPLETE,plan.binding().dependencies().getFirst().normalization());
        assertEquals(InjectionBindingPlan.Name.absent(),plan.binding().dependencies().getFirst().suggestedName());
        assertEquals(InjectionBindings.Outcome.SELECTED,bindingAt(f,plan,true).rows().getFirst().outcome());
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleNonVoidMethod.class);context.refresh();
            assertSame(context.getBean(OracleStore.class),context.getBean(OracleNonVoidMethod.class).store);
        }
    }
    @org.springframework.stereotype.Component
    static class OracleNonVoidMethod {
        OracleStore store;
        @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("missing")
        OracleStore wire(OracleStore store){this.store=store;return store;}
    }
    @Test void methodQualifierMismatchClearsRequiredAndOptionalGroupSelections() throws Exception {
        for(boolean required:List.of(false,true)) {
            var f=memberFixture("""
                    @org.springframework.beans.factory.annotation.Autowired(required=%s)
                    @org.springframework.beans.factory.annotation.Qualifier("missing")
                    void wire(@org.springframework.beans.factory.annotation.Qualifier("chosen") Store first,Store second) {}
                    """.formatted(required));
            var plan=prepare(f,true);
            assertEquals(2,plan.binding().dependencies().size());
            assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.COMPLETE));
            var rows=bindingAt(f,plan,true).rows();
            assertTrue(rows.stream().allMatch(r -> r.selected().isEmpty()));
            assertTrue(rows.stream().anyMatch(r -> r.outcome()==(required?InjectionBindings.Outcome.UNSATISFIED:InjectionBindings.Outcome.GROUP_SKIPPED)));
            assertTrue(bindingAt(f,plan,false).rows().stream().allMatch(r -> r.outcome()==InjectionBindings.Outcome.NOT_ACTIVE));
        }
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleOptionalMethodFallback.class);context.refresh();
            assertFalse(context.getBean(OracleOptionalMethodFallback.class).invoked);
        }
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleRequiredMethodFallback.class);
            assertThrows(org.springframework.beans.factory.UnsatisfiedDependencyException.class,context::refresh);
        }
    }
    @org.springframework.stereotype.Component
    static class OracleOptionalMethodFallback {
        boolean invoked;
        @org.springframework.beans.factory.annotation.Autowired(required=false) @org.springframework.beans.factory.annotation.Qualifier("missing")
        void wire(@org.springframework.beans.factory.annotation.Qualifier("chosen") OracleStore first,OracleStore second){invoked=true;}
    }
    @org.springframework.stereotype.Component
    static class OracleRequiredMethodFallback {
        @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("missing")
        void wire(@org.springframework.beans.factory.annotation.Qualifier("chosen") OracleStore first,OracleStore second){}
    }
    @Test void emptyAndMalformedMethodQualifiersRemainExplicitGaps() throws Exception {
        for(String qualifier:List.of("@org.springframework.beans.factory.annotation.Qualifier",
                "@org.springframework.beans.factory.annotation.Qualifier(value=\"chosen\",other=\"ignored\")")) {
            var f=memberFixture("@org.springframework.beans.factory.annotation.Autowired "+qualifier+" void wire(Store store) {}");
            var plan=prepare(f,true);
            assertEquals(InjectionBindingPlan.Normalization.INCOMPLETE,plan.binding().dependencies().getFirst().normalization());
            assertFalse(plan.gaps().isEmpty());
            assertTrue(bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        }
    }
    @Test void emptyParameterQualifierCannotBeReplacedByMatchingMethodMetadata() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("chosen")
                void wire(@org.springframework.beans.factory.annotation.Qualifier Store store) {}
                """);
        var plan=prepare(f,true);
        assertEquals(InjectionBindingPlan.Normalization.INCOMPLETE,plan.binding().dependencies().getFirst().normalization());
        assertFalse(plan.gaps().isEmpty());
        assertTrue(bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleEmptyParameter.class);
            assertThrows(org.springframework.beans.factory.UnsatisfiedDependencyException.class,context::refresh);
        }
    }
    @org.springframework.stereotype.Component
    static class OracleEmptyParameter {
        @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("chosen")
        void wire(@org.springframework.beans.factory.annotation.Qualifier OracleStore store){}
    }
    @Test void sourceMethodQualifierImpostorCannotEstablishFrameworkSemantics() throws Exception {
        var f=sourceFixture("""
                package org.springframework.beans.factory.annotation;
                @org.springframework.context.annotation.ComponentScan("org.springframework.beans.factory.annotation") class App {}
                @interface Qualifier { String value(); }
                @org.springframework.stereotype.Component class Store {}
                @org.springframework.stereotype.Component class Client {
                  @Autowired @Qualifier("chosen") void wire(Store store) {}
                }
                """);
        var plan=prepare(f,true);
        assertEquals(InjectionBindingPlan.Normalization.INCOMPLETE,plan.binding().dependencies().getFirst().normalization());
        assertFalse(plan.gaps().isEmpty());
        assertTrue(bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
    }
    @Test void methodNamedAndMixedQualifierKindsNeedAnnotationSpecificMatchingProofs() throws Exception {
        for(String qualifier:List.of("@jakarta.inject.Named(\"chosen\")",
                "@jakarta.inject.Named(\"chosen\") @org.springframework.beans.factory.annotation.Qualifier(\"chosen\")")) {
            var f=memberFixture("@org.springframework.beans.factory.annotation.Autowired "+qualifier+" void wire(Store store) {}");
            assertTrue(f.source().framework().artifacts().stream().anyMatch(a -> a.coordinate().equals("jakarta.inject:jakarta.inject-api:2.0.1")));
            var plan=prepare(f,true);
            assertEquals(InjectionBindingPlan.Normalization.INCOMPLETE,plan.binding().dependencies().getFirst().normalization());
            assertFalse(plan.gaps().isEmpty());
            assertTrue(bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        }
    }
    @Test void composedMethodAndParameterQualifiersAreNotSilentlyIgnored() throws Exception {
        for(String members:List.of("@Chosen void wire(Store store) {}","void wire(@Chosen Store store) {}")) {
            var f=sourceFixture("""
                    package app;
                    @org.springframework.context.annotation.ComponentScan("app") class App {}
                    @org.springframework.beans.factory.annotation.Qualifier("chosen") @interface Chosen {}
                    @org.springframework.stereotype.Component class Store {}
                    @org.springframework.stereotype.Component class Client {
                      @org.springframework.beans.factory.annotation.Autowired %s
                    }
                    """.formatted(members));
            var plan=prepare(f,true);
            assertEquals(InjectionBindingPlan.Normalization.INCOMPLETE,plan.binding().dependencies().getFirst().normalization());
            assertFalse(plan.gaps().isEmpty());
            assertTrue(bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        }
    }
    @Test void scalarFieldsAndMethodsProduceConditionalBindingsWithExactSourceEvidence() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired
                @org.springframework.beans.factory.annotation.Qualifier("chosen") private Store field;
                @org.springframework.beans.factory.annotation.Autowired
                private void wire(@org.springframework.beans.factory.annotation.Qualifier("chosen") Store store) {}
                """);
        var plan=prepare(f,true);
        assertEquals(2,plan.binding().dependencies().size());
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.COMPLETE));
        assertEquals(Set.of(InjectionPoint.SiteKind.FIELD,InjectionPoint.SiteKind.METHOD_PARAMETER),
                plan.binding().dependencies().stream().map(d -> d.point().siteKind()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.evidence() instanceof ConditionEvidence.Source s
                &&s.span().isPresent()&&f.build().containsSource(s)));
        var frontend=f.source().frontend();
        var fieldShape=frontend.memberDeclarations().stream().filter(m -> m.name().equals("field")).findFirst().orElseThrow();
        assertEquals("{\"abstractMember\":false,\"genericMethod\":false,\"member\":\""+fieldShape.member().value()
                        +"\",\"name\":\"field\",\"staticMember\":false}",
                com.evolution.analysis.contract.serialization.CanonicalJson.write(fieldShape));
        var reversed=new ArrayList<>(frontend.memberDeclarations());Collections.reverse(reversed);
        assertEquals(IngestionEvidence.digest(frontend),IngestionEvidence.digest(withMembers(frontend,reversed)));
        var foreignShape=new MemberDeclarationRecord(f.source().owner(fieldShape.member()).orElseThrow(),"field",false,false,false);
        assertThrows(IllegalArgumentException.class,() -> withMembers(frontend,List.of(foreignShape)));
        assertThrows(IllegalArgumentException.class,() -> withMembers(frontend,List.of(fieldShape,
                new MemberDeclarationRecord(fieldShape.member(),"field",true,false,false))));
        var result=evaluate(f,plan);
        assertEquals(2,result.regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING
                &&r.classification()==TruthRegionEvaluation.Classification.MAY).count());
        assertEquals(Set.of(candidate(plan,"store").identity().value()),selected(result,true));
        assertTrue(selected(result,false).isEmpty());
        assertEquals(result.identity(),evaluate(f,prepare(f,true)).identity());
        for(boolean active:List.of(false,true))try(var context=container(active)) {
            context.register(OracleStore.class,OracleMemberClient.class);context.refresh();
            assertEquals(active,context.containsBean("memberClient"));
            if(active) {
                var client=context.getBean(OracleMemberClient.class);
                assertSame(context.getBean(OracleStore.class),client.field);
                assertSame(client.field,client.method);
            }
        }
    }
    @org.springframework.stereotype.Component("memberClient") @org.springframework.context.annotation.Profile("dev")
    static class OracleMemberClient {
        @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("chosen") private OracleStore field;
        private OracleStore method;
        @org.springframework.beans.factory.annotation.Autowired private void wire(@org.springframework.beans.factory.annotation.Qualifier("chosen") OracleStore store){method=store;}
    }
    @Test void absentOptionalMethodParameterSuppressesAllTentativeBindings() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired(required=false)
                void wire(@org.springframework.beans.factory.annotation.Qualifier("chosen") Store first,
                          @org.springframework.beans.factory.annotation.Qualifier("missing") Store second) {}
                """);
        var plan=prepare(f,true);
        assertEquals(1,plan.binding().groups().size());
        assertEquals(2,plan.binding().groups().getFirst().dependencies().size());
        var result=evaluate(f,plan);
        assertTrue(bindingAt(f,plan,true).rows().stream().allMatch(r -> r.outcome()==InjectionBindings.Outcome.GROUP_SKIPPED));
        assertTrue(bindingAt(f,plan,false).rows().stream().allMatch(r -> r.outcome()==InjectionBindings.Outcome.NOT_ACTIVE));
        assertTrue(selected(result,true).isEmpty());
        assertTrue(result.regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING)
                .allMatch(r -> r.classification()==TruthRegionEvaluation.Classification.NEVER));
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleOptionalMethod.class);context.refresh();
            assertFalse(context.getBean(OracleOptionalMethod.class).invoked);
        }
    }
    @org.springframework.stereotype.Component
    static class OracleOptionalMethod {
        boolean invoked;
        @org.springframework.beans.factory.annotation.Autowired(required=false)
        void wire(OracleStore first,@org.springframework.beans.factory.annotation.Qualifier("missing") OracleStore second){invoked=true;}
    }
    @Test void completeOptionalMethodKeepsParameterOrderAndSelectsBothDependencies() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired(required=false)
                void wire(Store z, Store a) {}
                """);
        var plan=prepare(f,true);var group=plan.binding().groups().getFirst();
        assertEquals(group.dependencies().stream().sorted().toList(),group.skipOnAbsent());
        var byId=new HashMap<ContentDigest,InjectionBindingPlan.Dependency>();plan.binding().dependencies().forEach(d -> byId.put(d.identity(),d));
        assertEquals(List.of("Store z","Store a"),group.dependencies().stream().map(byId::get)
                .map(d -> f.source().declarations().entrySet().stream().filter(e -> e.getKey().value().equals(d.point().siteSlot())).findFirst().orElseThrow().getValue().spelling()).toList());
        assertTrue(bindingAt(f,plan,true).rows().stream().allMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        var result=evaluate(f,plan);
        assertEquals(2,result.regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING
                &&r.classification()==TruthRegionEvaluation.Classification.MAY).count());
        assertTrue(result.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleCompleteMethod.class);context.refresh();
            var client=context.getBean(OracleCompleteMethod.class);
            assertSame(context.getBean(OracleStore.class),client.z);assertSame(client.z,client.a);
        }
    }
    @org.springframework.stereotype.Component
    static class OracleCompleteMethod {
        OracleStore z,a;
        @org.springframework.beans.factory.annotation.Autowired(required=false) void wire(OracleStore z,OracleStore a){this.z=z;this.a=a;}
    }
    @Test void qualifierMismatchPreservesOptionalFieldAbsenceAndRequiredMethodFailure() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired(required=false)
                @org.springframework.beans.factory.annotation.Qualifier("missing") Store field;
                @org.springframework.beans.factory.annotation.Autowired
                void wire(@org.springframework.beans.factory.annotation.Qualifier("missing") Store store) {}
                """);
        var plan=prepare(f,true);var binding=bindingAt(f,plan,true);
        assertEquals(Set.of(InjectionBindings.Outcome.ABSENT_OPTIONAL,InjectionBindings.Outcome.UNSATISFIED),
                binding.rows().stream().map(InjectionBindings.Row::outcome).collect(java.util.stream.Collectors.toSet()));
        assertTrue(selected(evaluate(f,plan),true).isEmpty());
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleOptionalField.class);context.refresh();
            assertNull(context.getBean(OracleOptionalField.class).field);
        }
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleRequiredMethod.class);
            assertThrows(org.springframework.beans.factory.UnsatisfiedDependencyException.class,context::refresh);
        }
    }
    @org.springframework.stereotype.Component
    static class OracleOptionalField {
        @org.springframework.beans.factory.annotation.Autowired(required=false)
        @org.springframework.beans.factory.annotation.Qualifier("missing") OracleStore field;
    }
    @org.springframework.stereotype.Component
    static class OracleRequiredMethod {
        @org.springframework.beans.factory.annotation.Autowired
        void wire(@org.springframework.beans.factory.annotation.Qualifier("missing") OracleStore store){}
    }
    @Test void incompleteOptionalParameterCannotLeaveATentativeSelectionOrDisappear() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired(required=false)
                void wire(Store first,@org.springframework.context.annotation.Lazy Store second) {}
                """);
        var plan=prepare(f,true);
        assertEquals(2,plan.binding().dependencies().size());assertEquals(2,plan.binding().groups().getFirst().dependencies().size());
        assertFalse(plan.gaps().isEmpty());
        assertTrue(plan.binding().dependencies().stream().anyMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.INCOMPLETE));
        assertTrue(bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        assertTrue(selected(evaluate(f,plan),true).isEmpty());
    }
    @Test void unresolvedParameterTypesRetainSourceGapsAndCannotCertifyBinding() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired(required=false)
                void wire(Store first,Missing second) {}
                """);
        var plan=prepare(f,true);
        assertFalse(plan.gaps().isEmpty());
        assertTrue(f.source().frontend().observations().stream().anyMatch(o -> o.category().value().equals("java.parameter-type")
                &&o.attribution()==com.evolution.analysis.contract.semantic.SemanticStatus.UNRESOLVED));
        assertTrue(plan.binding().registrationPlan().discoveryPlan().events().stream()
                .filter(e -> e.candidate().flatMap(c -> c.declaredNameKey().primary()).filter("client"::equals).isPresent())
                .allMatch(e -> e.conditionMetadata()==UNKNOWN));
        assertTrue(selected(evaluate(f,plan),true).isEmpty());
    }
    @Test void staticFieldsAndMethodsRetainGapsAndNeverBecomeInstanceBindings() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired static Store field;
                @org.springframework.beans.factory.annotation.Autowired static void wire(Store store) {}
                """);
        var plan=prepare(f,true);
        assertEquals(2,plan.binding().dependencies().size());
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.INCOMPLETE));
        assertFalse(plan.gaps().isEmpty());assertTrue(selected(evaluate(f,plan),true).isEmpty());
        assertEquals(2,f.source().frontend().memberDeclarations().stream().filter(MemberDeclarationRecord::staticMember).count());
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleStaticMembers.class);context.refresh();
            assertNull(OracleStaticMembers.field);assertFalse(OracleStaticMembers.invoked);
        }
    }
    @org.springframework.stereotype.Component
    static class OracleStaticMembers {
        @org.springframework.beans.factory.annotation.Autowired static OracleStore field;
        static boolean invoked;
        @org.springframework.beans.factory.annotation.Autowired static void wire(OracleStore store){invoked=true;}
    }
    @Test void directMethodQualifierIsCompleteWhileGenericMethodRemainsIncomplete() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("missing")
                void wire(Store store) {}
                @org.springframework.beans.factory.annotation.Autowired <T> void generic(Store store) {}
                """);
        var plan=prepare(f,true);
        assertEquals(2,plan.binding().dependencies().size());assertEquals(2,plan.binding().groups().size());
        var qualified=plan.binding().dependencies().stream().filter(d -> d.suggestedName().equals(InjectionBindingPlan.Name.of("missing"))).findFirst().orElseThrow();
        assertEquals(InjectionBindingPlan.Normalization.COMPLETE,qualified.normalization());
        assertTrue(plan.binding().dependencies().stream().filter(d -> !d.identity().equals(qualified.identity()))
                .allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.INCOMPLETE));
        assertFalse(plan.gaps().isEmpty());assertTrue(selected(evaluate(f,plan),true).isEmpty());
    }
    @Test void fieldNamesAreEvidencedWhileMethodParameterNamesRemainUnknown() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean Store first(){return new Store();}
                  @org.springframework.context.annotation.Bean Store second(){return new Store();}
                }
                @org.springframework.stereotype.Component class Client {
                  @org.springframework.beans.factory.annotation.Autowired Store first;
                  @org.springframework.beans.factory.annotation.Autowired void wire(Store first) {}
                }
                """);
        var plan=beanPlan(f,true,"Config","first","second","Client");
        var field=plan.binding().dependencies().stream().filter(d -> d.point().siteKind()==InjectionPoint.SiteKind.FIELD).findFirst().orElseThrow();
        var parameter=plan.binding().dependencies().stream().filter(d -> d.point().siteKind()==InjectionPoint.SiteKind.METHOD_PARAMETER).findFirst().orElseThrow();
        assertEquals(InjectionBindingPlan.Name.of("first"),field.dependencyName());
        assertEquals(InjectionBindingPlan.Name.unknown(),parameter.dependencyName());
        var rows=bindingAt(f,plan,true).rows();
        assertEquals(InjectionBindings.Outcome.SELECTED,rows.stream().filter(r -> r.dependency().equals(field.identity())).findFirst().orElseThrow().outcome());
        assertEquals(InjectionBindings.Outcome.UNKNOWN,rows.stream().filter(r -> r.dependency().equals(parameter.identity())).findFirst().orElseThrow().outcome());
        try(var context=container(true)) {
            context.register(OracleNamedFieldConfig.class,OracleNamedField.class);context.refresh();
            assertSame(context.getBean("first"),context.getBean(OracleNamedField.class).first);
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleNamedFieldConfig {
        @org.springframework.context.annotation.Bean OracleBeanStore first(){return new OracleBeanStore();}
        @org.springframework.context.annotation.Bean OracleBeanStore second(){return new OracleBeanStore();}
    }
    @org.springframework.stereotype.Component
    static class OracleNamedField {
        @org.springframework.beans.factory.annotation.Autowired OracleBeanStore first;
    }
    @Test void canonicallyEquivalentUnicodeFieldNamesRemainDistinctJavaIdentifiers() throws Exception {
        String decomposed="e\u0301",composed="\u00e9";
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean(name="%s") Store first(){return new Store();}
                  @org.springframework.context.annotation.Bean(name="%s") Store second(){return new Store();}
                }
                @org.springframework.stereotype.Component class Client {
                  @org.springframework.beans.factory.annotation.Autowired Store %s;
                  @org.springframework.beans.factory.annotation.Autowired Store %s;
                }
                """.formatted(decomposed,composed,decomposed,composed));
        var plan=beanPlan(f,true,"Config","first","second","Client");
        assertTrue(plan.inventory().rawObservations().stream().anyMatch(r -> r.spelling().contains(decomposed)));
        assertEquals(Set.of(decomposed,composed),plan.binding().dependencies().stream()
                .map(d -> d.dependencyName().value().orElseThrow()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(decomposed,composed),bindingAt(f,plan,true).rows().stream().flatMap(r -> r.selected().stream())
                .map(InjectionBindings.Target::beanName).collect(java.util.stream.Collectors.toSet()));
        try(var context=container(true)) {
            context.register(OracleUnicodeFieldConfig.class,OracleUnicodeFields.class);context.refresh();
            var client=context.getBean(OracleUnicodeFields.class);
            assertSame(context.getBean(decomposed),client.e\u0301);assertSame(context.getBean(composed),client.\u00e9);
            assertNotSame(client.e\u0301,client.\u00e9);
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleUnicodeFieldConfig {
        @org.springframework.context.annotation.Bean(name="e\u0301") OracleBeanStore first(){return new OracleBeanStore();}
        @org.springframework.context.annotation.Bean(name="\u00e9") OracleBeanStore second(){return new OracleBeanStore();}
    }
    @org.springframework.stereotype.Component
    static class OracleUnicodeFields {
        @org.springframework.beans.factory.annotation.Autowired OracleBeanStore e\u0301;
        @org.springframework.beans.factory.annotation.Autowired OracleBeanStore \u00e9;
    }
    private FrontendResult withMembers(FrontendResult f,List<MemberDeclarationRecord> members) {
        return new FrontendResult(f.analysis(),f.frontend(),f.state(),f.declarations(),f.occurrences(),f.observations(),
                f.sources(),f.coverage(),f.diagnostics(),f.types(),f.annotations(),f.derivedRelationships(),f.typeDeclarations(),members);
    }
    @Test void beanProducedScalarFieldsAttachToTheirFactoryProduct() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Dependency {}
                final class Store { @org.springframework.beans.factory.annotation.Autowired Dependency dependency; }
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean Store store(){return new Store();}
                }
                """);
        var plan=beanPlan(f,true,"Dependency","Config","store");
        assertEquals(COMPLETE,plan.binding().environment().descriptorsComplete());
        var dependency=plan.binding().dependencies().getFirst();
        assertEquals(Optional.of(candidate(plan,"store").identity()),dependency.owner());
        assertEquals(InjectionPoint.SiteKind.FIELD,dependency.point().siteKind());
        assertEquals(InjectionBindingPlan.Normalization.COMPLETE,dependency.normalization());
        var evidence=new SpringSourceEvidence(f.source().manifest(),f.source().frontend(),f.source().framework());
        var sites=SpringInjectionSites.acquire(evidence,f.result().units().stream().filter(u -> u.sourceSet().equals(f.key()))
                        .findFirst().orElseThrow().constructors().orElseThrow(),FrameworkGeneration.from(evidence.framework(),Optional.empty()),100);
        assertEquals(1,sites.sites().stream().filter(s -> s.kind()==SpringInjectionSites.Kind.FIELD).count());
        assertEquals(InjectionBindings.Outcome.SELECTED,bindingAt(f,plan,true).rows().getFirst().outcome());
        assertTrue(selected(evaluate(f,plan),true).contains(candidate(plan,"dependency").identity().value()));
        assertTrue(plan.binding().obligations().stream().anyMatch(o -> o.dependencies().contains(dependency.identity())));
        try(var context=container(true)) {
            context.register(OracleProductFieldConfig.class);context.refresh();
            assertSame(context.getBean("dependency"),context.getBean(OracleProductField.class).dependency);
        }
    }
    static class OracleProductDependency {}
    static final class OracleProductField {
        @org.springframework.beans.factory.annotation.Autowired OracleProductDependency dependency;
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleProductFieldConfig {
        @org.springframework.context.annotation.Bean OracleProductDependency dependency(){return new OracleProductDependency();}
        @org.springframework.context.annotation.Bean OracleProductField store(){return new OracleProductField();}
    }
    private Fixture productFixture(String declaration,String members,String firstMetadata) throws Exception {
        return sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Dependency {}
                %s { %s }
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean %s Product first(){return %s;}
                  @org.springframework.context.annotation.Bean static Product second(){return %s;}
                }
                """.formatted(declaration,members,firstMetadata,
                        members.contains("Product(Dependency constructorOnly)")?"new Product(null)":"new Product()",
                        members.contains("Product(Dependency constructorOnly)")?"new Product(null)":"new Product()"));
    }
    @Test void productMethodQualifiersPreservePerOwnerGroupsSourcePointsAndWitnesses() throws Exception {
        var f=productFixture("final class Product","""
                @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("dependency")
                void wire(Dependency first,@org.springframework.beans.factory.annotation.Qualifier("dependency") Dependency second) {}
                ""","@org.springframework.context.annotation.Profile(\"dev\")");
        var plan=beanPlan(f,true,"Dependency","Config","first","second");
        assertEquals(COMPLETE,plan.binding().environment().descriptorsComplete());
        assertEquals(4,plan.binding().dependencies().size());assertEquals(2,plan.binding().groups().size());
        assertEquals(2,plan.binding().dependencies().stream().map(d -> d.point().identity()).distinct().count());
        for(var group:plan.binding().groups()) {
            var groupDependencies=plan.binding().dependencies().stream().filter(d -> group.dependencies().contains(d.identity())).toList();
            assertEquals(2,groupDependencies.size());
            assertEquals(1,groupDependencies.stream().map(InjectionBindingPlan.Dependency::owner).distinct().count());
        }
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.suggestedName().equals(InjectionBindingPlan.Name.of("dependency"))
                &&d.dependencyName().equals(InjectionBindingPlan.Name.unknown())&&d.evidence() instanceof ConditionEvidence.Source s
                &&s.span().isPresent()&&f.build().containsSource(s)));
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> plan.binding().obligations().stream().anyMatch(o -> o.dependencies().contains(d.identity()))));
        assertEquals(4,bindingAt(f,plan,true).rows().stream().filter(r -> r.outcome()==InjectionBindings.Outcome.SELECTED).count());
        assertEquals(2,bindingAt(f,plan,false).rows().stream().filter(r -> r.outcome()==InjectionBindings.Outcome.NOT_ACTIVE).count());
        var truth=evaluate(f,plan);
        assertFalse(truth.replays().isEmpty());assertTrue(truth.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        assertEquals(plan.identity(),beanPlan(f,true,"Dependency","Config","first","second").identity());
        for(boolean active:List.of(false,true))try(var context=container(active)) {
            context.register(OracleQualifiedProducts.class);context.refresh();
            var second=context.getBean("second",OracleQualifiedProduct.class);
            assertSame(context.getBean("dependency"),second.first);assertSame(second.first,second.second);
            assertEquals(active,context.containsBean("first"));
            if(active)assertSame(second.first,context.getBean("first",OracleQualifiedProduct.class).second);
        }
    }
    static final class OracleQualifiedProduct {
        OracleProductDependency first,second;
        @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("dependency")
        void wire(OracleProductDependency first,@org.springframework.beans.factory.annotation.Qualifier("dependency") OracleProductDependency second){this.first=first;this.second=second;}
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleQualifiedProducts {
        @org.springframework.context.annotation.Bean OracleProductDependency dependency(){return new OracleProductDependency();}
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Profile("dev")
        OracleQualifiedProduct first(){return new OracleQualifiedProduct();}
        @org.springframework.context.annotation.Bean static OracleQualifiedProduct second(){return new OracleQualifiedProduct();}
    }
    @Test void productMethodQualifierMismatchClearsEachOptionalAndRequiredOwnerGroup() throws Exception {
        for(boolean required:List.of(false,true)) {
            var f=productFixture("final class Product","""
                    @org.springframework.beans.factory.annotation.Autowired(required=%s) @org.springframework.beans.factory.annotation.Qualifier("missing")
                    void wire(@org.springframework.beans.factory.annotation.Qualifier("dependency") Dependency first,Dependency second) {}
                    """.formatted(required),"@org.springframework.context.annotation.Profile(\"dev\")");
            var plan=beanPlan(f,true,"Dependency","Config","first","second");
            assertEquals(COMPLETE,plan.binding().environment().descriptorsComplete());
            assertEquals(4,plan.binding().dependencies().size());assertEquals(2,plan.binding().groups().size());
            var rows=bindingAt(f,plan,true).rows();
            assertTrue(rows.stream().allMatch(r -> r.selected().isEmpty()));
            assertEquals(required?2:4,rows.stream().filter(r -> r.outcome()==(required?InjectionBindings.Outcome.UNSATISFIED:InjectionBindings.Outcome.GROUP_SKIPPED)).count());
            assertEquals(2,bindingAt(f,plan,false).rows().stream().filter(r -> r.outcome()==InjectionBindings.Outcome.NOT_ACTIVE).count());
        }
        try(var context=container(true)) {
            context.register(OracleProductDependencyConfig.class);context.registerBean("product",OracleOptionalProductFallback.class);context.refresh();
            assertFalse(context.getBean(OracleOptionalProductFallback.class).invoked);
        }
        try(var context=container(true)) {
            context.register(OracleProductDependencyConfig.class);context.registerBean("product",OracleRequiredProductFallback.class);
            assertThrows(org.springframework.beans.factory.UnsatisfiedDependencyException.class,context::refresh);
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleProductDependencyConfig {
        @org.springframework.context.annotation.Bean OracleProductDependency dependency(){return new OracleProductDependency();}
    }
    static final class OracleOptionalProductFallback {
        boolean invoked;
        @org.springframework.beans.factory.annotation.Autowired(required=false) @org.springframework.beans.factory.annotation.Qualifier("missing")
        void wire(@org.springframework.beans.factory.annotation.Qualifier("dependency") OracleProductDependency first,OracleProductDependency second){invoked=true;}
    }
    static final class OracleRequiredProductFallback {
        @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("missing")
        void wire(@org.springframework.beans.factory.annotation.Qualifier("dependency") OracleProductDependency first,OracleProductDependency second){}
    }
    @Test void missingMethodReturnEvidenceCannotDecideQualifierFallback() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("chosen")
                void wire(Store store) {}
                """);
        var frontend=f.source().frontend();
        var missingReturns=new FrontendResult(frontend.analysis(),frontend.frontend(),frontend.state(),frontend.declarations(),frontend.occurrences(),frontend.observations(),
                frontend.sources(),frontend.coverage(),frontend.diagnostics(),frontend.types().stream().filter(t -> !t.role().value().equals("java.returns")).toList(),
                frontend.annotations(),frontend.derivedRelationships(),frontend.typeDeclarations(),frontend.memberDeclarations());
        var source=new SpringSourceEvidence(f.source().manifest(),missingReturns,f.source().framework());
        var constructors=f.result().units().stream().filter(u -> u.sourceSet().equals(f.key())).findFirst().orElseThrow().constructors().orElseThrow();
        var sites=SpringInjectionSites.acquire(source,constructors,FrameworkGeneration.from(source.framework(),Optional.empty()),100);
        assertEquals(1,sites.sites().stream().filter(s -> s.kind()==SpringInjectionSites.Kind.METHOD).count());
        assertTrue(sites.sites().stream().filter(s -> s.kind()==SpringInjectionSites.Kind.METHOD).allMatch(s -> s.status()==SpringInjectionSites.Status.UNKNOWN));
        assertTrue(sites.issues().stream().anyMatch(i -> i.reason()==UniversalSpringEvidence.Reason.EVIDENCE_MISSING));
        assertFalse(sites.gaps().isEmpty());
    }
    @Test void sharedProductSitesKeepDistinctOwnersGroupsAndConditionalFacts() throws Exception {
        var f=productFixture("final class Product","""
                @org.springframework.beans.factory.annotation.Autowired Product(Dependency constructorOnly) {}
                @org.springframework.beans.factory.annotation.Autowired Dependency field;
                @org.springframework.beans.factory.annotation.Autowired void wire(Dependency z,Dependency a) {}
                ""","@org.springframework.context.annotation.Profile(\"dev\")");
        var plan=beanPlan(f,true,"Dependency","Config","first","second");
        assertEquals("m4uv2.2-inherited-fields-v1",SourceToSpringPlan.PROVIDER.version());
        assertEquals(6,plan.binding().dependencies().size());
        assertEquals(3,plan.binding().dependencies().stream().map(d -> d.point().identity()).distinct().count());
        assertEquals(6,plan.binding().dependencies().stream().map(InjectionBindingPlan.Dependency::identity).distinct().count());
        assertTrue(plan.binding().dependencies().stream().noneMatch(d -> d.point().siteKind()==InjectionPoint.SiteKind.CONSTRUCTOR_PARAMETER));
        assertEquals(COMPLETE,plan.binding().environment().descriptorsComplete());
        assertEquals(2,plan.binding().groups().size());
        for(var group:plan.binding().groups()) {
            var parameters=group.dependencies().stream().map(id -> plan.binding().dependencies().stream()
                    .filter(d -> d.identity().equals(id)).findFirst().orElseThrow()).toList();
            assertEquals(1,parameters.stream().map(InjectionBindingPlan.Dependency::owner).distinct().count());
            assertEquals(List.of("Dependency z","Dependency a"),parameters.stream().map(d -> f.source().declarations().values().stream()
                    .filter(s -> s.entity().identity().value().equals(d.point().siteSlot())).findFirst().orElseThrow().spelling()).toList());
            assertTrue(group.evidence() instanceof ConditionEvidence.Source s&&f.build().containsSource(s));
        }
        var first=candidate(plan,"first").identity();var second=candidate(plan,"second").identity();
        assertEquals(Optional.of(candidate(plan,"config").identity()),plan.binding().definitions().stream()
                .filter(d -> d.candidate().equals(first)).findFirst().orElseThrow().factoryOwner());
        assertTrue(plan.binding().definitions().stream().filter(d -> d.candidate().equals(second)).findFirst().orElseThrow().factoryOwner().isEmpty());
        for(boolean active:List.of(false,true)) {
            var rows=bindingAt(f,plan,active).rows();
            for(var dependency:plan.binding().dependencies())assertEquals(!active&&dependency.owner().equals(Optional.of(first))
                    ?InjectionBindings.Outcome.NOT_ACTIVE:InjectionBindings.Outcome.SELECTED,
                    rows.stream().filter(r -> r.dependency().equals(dependency.identity())).findFirst().orElseThrow().outcome());
            try(var context=container(active)) {
                context.register(OracleSharedProducts.class);context.refresh();
                assertEquals(active,context.containsBean("first"));
                var products=new ArrayList<OracleSharedProduct>();products.add(context.getBean("second",OracleSharedProduct.class));
                if(active)products.add(context.getBean("first",OracleSharedProduct.class));
                assertEquals(active?2:1,products.size());
                for(var product:products) {
                    assertNull(product.constructorOnly);
                    assertSame(context.getBean("dependency"),product.field);
                    assertSame(product.field,product.z);assertSame(product.z,product.a);
                }
                if(active)assertNotSame(products.get(0),products.get(1));
            }
        }
        var truth=evaluate(f,plan);
        assertEquals(3,truth.regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING
                &&r.classification()==TruthRegionEvaluation.Classification.MAY).count());
        assertEquals(3,truth.regions().stream().filter(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING
                &&r.classification()==TruthRegionEvaluation.Classification.MUST).count());
        assertFalse(truth.replays().isEmpty());assertTrue(truth.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        assertEquals(truth.identity(),evaluate(f,beanPlan(f,true,"Dependency","Config","first","second")).identity());
        assertTrue(plan.binding().obligations().stream().anyMatch(o -> o.dependencies().size()==4));
    }
    static final class OracleSharedProduct {
        final OracleProductDependency constructorOnly;
        @org.springframework.beans.factory.annotation.Autowired OracleSharedProduct(OracleProductDependency constructorOnly){this.constructorOnly=constructorOnly;}
        @org.springframework.beans.factory.annotation.Autowired OracleProductDependency field;
        OracleProductDependency z,a;
        @org.springframework.beans.factory.annotation.Autowired void wire(OracleProductDependency z,OracleProductDependency a){this.z=z;this.a=a;}
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleSharedProducts {
        @org.springframework.context.annotation.Bean OracleProductDependency dependency(){return new OracleProductDependency();}
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Profile("dev")
        OracleSharedProduct first(){return new OracleSharedProduct(null);}
        @org.springframework.context.annotation.Bean static OracleSharedProduct second(){return new OracleSharedProduct(null);}
    }
    @Test void productOptionalMethodGroupsSkipIndependentlyAndRetainInactiveParameters() throws Exception {
        var f=productFixture("final class Product","""
                @org.springframework.beans.factory.annotation.Autowired(required=false)
                void wire(Dependency first,@org.springframework.beans.factory.annotation.Qualifier("missing") Dependency second) {}
                ""","@org.springframework.context.annotation.Profile(\"dev\")");
        var plan=beanPlan(f,true,"Dependency","Config","first","second");
        assertEquals(4,plan.binding().dependencies().size());assertEquals(2,plan.binding().groups().size());
        assertTrue(plan.binding().groups().stream().allMatch(g -> g.dependencies().size()==2&&g.skipOnAbsent().size()==2));
        for(boolean active:List.of(false,true)) {
            var rows=bindingAt(f,plan,active).rows();
            assertEquals(active?4:2,rows.stream().filter(r -> r.outcome()==InjectionBindings.Outcome.GROUP_SKIPPED).count());
            assertEquals(active?0:2,rows.stream().filter(r -> r.outcome()==InjectionBindings.Outcome.NOT_ACTIVE).count());
            assertTrue(rows.stream().allMatch(r -> r.selected().isEmpty()));
            try(var context=container(active)) {
                context.register(OracleOptionalProducts.class);context.refresh();
                assertFalse(context.getBean("second",OracleOptionalProduct.class).invoked);
                if(active)assertFalse(context.getBean("first",OracleOptionalProduct.class).invoked);
            }
        }
        assertTrue(selected(evaluate(f,plan),true).isEmpty());
    }
    static final class OracleOptionalProduct {
        boolean invoked;
        @org.springframework.beans.factory.annotation.Autowired(required=false)
        void wire(OracleProductDependency first,@org.springframework.beans.factory.annotation.Qualifier("missing") OracleProductDependency second){invoked=true;}
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleOptionalProducts {
        @org.springframework.context.annotation.Bean OracleProductDependency dependency(){return new OracleProductDependency();}
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Profile("dev")
        OracleOptionalProduct first(){return new OracleOptionalProduct();}
        @org.springframework.context.annotation.Bean static OracleOptionalProduct second(){return new OracleOptionalProduct();}
    }
    @Test void productRequiredMethodFailureClearsSelectionsForEveryOwner() throws Exception {
        var f=productFixture("final class Product","""
                @org.springframework.beans.factory.annotation.Autowired
                void wire(Dependency first,@org.springframework.beans.factory.annotation.Qualifier("missing") Dependency second) {}
                ""","");
        var plan=beanPlan(f,true,"Dependency","Config","first","second");
        assertEquals(2,plan.binding().groups().size());
        var rows=bindingAt(f,plan,true).rows();
        assertEquals(2,rows.stream().filter(r -> r.outcome()==InjectionBindings.Outcome.UNSATISFIED).count());
        assertTrue(rows.stream().allMatch(r -> r.selected().isEmpty()));
        try(var context=container(true)) {
            context.register(OracleRequiredProductConfig.class);
            assertThrows(org.springframework.beans.factory.UnsatisfiedDependencyException.class,context::refresh);
        }
    }
    static final class OracleRequiredProduct {
        @org.springframework.beans.factory.annotation.Autowired
        void wire(OracleProductDependency first,@org.springframework.beans.factory.annotation.Qualifier("missing") OracleProductDependency second){}
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleRequiredProductConfig {
        @org.springframework.context.annotation.Bean OracleProductDependency dependency(){return new OracleProductDependency();}
        @org.springframework.context.annotation.Bean OracleRequiredProduct first(){return new OracleRequiredProduct();}
    }
    @Test void widerAndGenericProductTypesRetainDescriptorsWithoutCertifyingRuntimeMembers() throws Exception {
        for(String declaration:List.of("class Product","final class Product<T>","class Base {} final class Product extends Base",
                "/* final class is only a comment */ class Product")) {
            var f=productFixture(declaration,"@org.springframework.beans.factory.annotation.Autowired Dependency field;","");
            var plan=beanPlan(f,true,"Dependency","Config","first","second");
            assertEquals(2,plan.binding().dependencies().size());
            assertEquals(UNKNOWN,plan.binding().environment().descriptorsComplete());
            assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.INCOMPLETE));
            assertTrue(plan.gaps().stream().anyMatch(g -> g.reasonCode().equals("EVIDENCE_MISSING")));
            assertTrue(bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        }
    }
    @Test void declaredProductMethodDoesNotProveAnOverridingRuntimeSubtypeInjection() throws Exception {
        var f=productFixture("class Product","@org.springframework.beans.factory.annotation.Autowired void wire(Dependency dependency) {}","");
        var plan=beanPlan(f,true,"Dependency","Config","first","second");
        assertEquals(2,plan.binding().groups().size());
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.INCOMPLETE));
        assertTrue(bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        try(var context=container(true)) {
            context.register(OracleRuntimeSubtypeConfig.class);context.refresh();
            assertFalse(context.getBean("product",OracleWiderProduct.class).invoked);
        }
    }
    @Test void widerProductWithNoDeclaredSitesStillRetainsItsRuntimeFootprintGap() throws Exception {
        var f=productFixture("class Product","","");
        var plan=beanPlan(f,true,"Dependency","Config","first","second");
        assertTrue(plan.binding().dependencies().isEmpty());
        assertEquals(UNKNOWN,plan.binding().environment().descriptorsComplete());
        assertTrue(plan.gaps().stream().anyMatch(g -> g.reasonCode().equals("EVIDENCE_MISSING")));
        assertTrue(bindingAt(f,plan,true).capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("DESCRIPTOR_INVENTORY_OPEN")));
    }
    @Test void factoryParametersAndProductMembersHaveSeparateSitesOnTheSameOwner() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Dependency {}
                final class Product {
                  Product(Dependency supplied) {}
                  @org.springframework.beans.factory.annotation.Autowired Dependency field;
                }
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean Product product(Dependency factoryParameter){return new Product(factoryParameter);}
                }
                """);
        var plan=beanPlan(f,true,"Dependency","Config","product");
        assertEquals(2,plan.binding().dependencies().size());
        assertEquals(Set.of(InjectionPoint.SiteKind.FIELD,InjectionPoint.SiteKind.BEAN_PARAMETER),plan.binding().dependencies().stream()
                .map(d -> d.point().siteKind()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.owner().equals(Optional.of(candidate(plan,"product").identity()))));
        assertTrue(bindingAt(f,plan,true).rows().stream().allMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        assertEquals(2,plan.binding().dependencies().stream().map(d -> d.point().identity()).distinct().count());
    }
    static class OracleWiderProduct {
        boolean invoked;
        @org.springframework.beans.factory.annotation.Autowired void wire(OracleProductDependency dependency){invoked=true;}
    }
    static final class OracleRuntimeSubtype extends OracleWiderProduct {
        @Override void wire(OracleProductDependency dependency){invoked=true;}
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleRuntimeSubtypeConfig {
        @org.springframework.context.annotation.Bean OracleProductDependency dependency(){return new OracleProductDependency();}
        @org.springframework.context.annotation.Bean OracleWiderProduct product(){return new OracleRuntimeSubtype();}
    }
    @Test void productOwnerExpansionPreservesEveryRequestWhenTheBudgetIsExceeded() throws Exception {
        var f=productFixture("final class Product","""
                @org.springframework.beans.factory.annotation.Autowired Dependency field;
                @org.springframework.beans.factory.annotation.Autowired void wire(Dependency first,Dependency second) {}
                ""","");
        var plan=beanPlan(f,true,4,"Dependency","Config","first","second");
        assertEquals(6,plan.binding().dependencies().size());
        assertEquals(2,plan.binding().groups().size());
        assertEquals(UNKNOWN,plan.binding().environment().descriptorsComplete());
        assertEquals(16,plan.binding().matches().size());
        assertTrue(plan.gaps().stream().anyMatch(g -> g.reasonCode().equals("RESOURCE_LIMIT")));
        var binding=bindingAt(f,plan,true);
        assertEquals(6,binding.rows().size());
        assertTrue(binding.rows().stream().anyMatch(r -> r.operation()==InjectionBindings.Operation.LIMIT_EXCEEDED));
    }
    @Test void productUnsupportedMembersAndUnknownTypesStayInTheReportingDenominator() throws Exception {
        var f=productFixture("final class Product","""
                @org.springframework.beans.factory.annotation.Autowired static Dependency field;
                @org.springframework.beans.factory.annotation.Autowired void wire(Missing unknown) {}
                ""","");
        var plan=beanPlan(f,true,"Dependency","Config","first","second");
        // An unresolved method signature has no fully evidenced declaration/parameter sites.
        // Preserve its upstream observation and inventory obligation instead of inventing them.
        assertEquals(2,plan.binding().dependencies().size());
        assertEquals(UNKNOWN,plan.binding().environment().descriptorsComplete());
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.INCOMPLETE));
        assertTrue(f.source().frontend().observations().stream().anyMatch(o -> o.category().value().equals("java.parameter-type")
                &&o.attribution()==com.evolution.analysis.contract.semantic.SemanticStatus.UNRESOLVED));
        assertEquals(2,bindingAt(f,plan,true).rows().size());assertFalse(plan.gaps().isEmpty());
        assertTrue(selected(evaluate(f,plan),true).isEmpty());
    }
    @Test void unsupportedBeanReaderMetadataCannotCertifyProductMemberSelection() throws Exception {
        var f=productFixture("final class Product","@org.springframework.beans.factory.annotation.Autowired Dependency field;",
                "@Deprecated");
        var plan=beanPlan(f,true,"Dependency","Config","first","second");
        assertEquals(2,plan.binding().dependencies().size());
        var first=plan.binding().dependencies().stream().filter(d -> d.owner().equals(Optional.of(candidate(plan,"first").identity()))).findFirst().orElseThrow();
        assertEquals(InjectionBindingPlan.Normalization.INCOMPLETE,first.normalization());
        assertFalse(plan.gaps().isEmpty());assertTrue(bindingAt(f,plan,true).rows().stream().allMatch(r -> r.selected().isEmpty()));
    }
    @Test void requiredMethodFailureClearsTheEarlierTentativeSelection() throws Exception {
        var f=memberFixture("""
                @org.springframework.beans.factory.annotation.Autowired
                void wire(Store first,@org.springframework.beans.factory.annotation.Qualifier("missing") Store second) {}
                """);
        var plan=prepare(f,true);var group=plan.binding().groups().getFirst();
        assertTrue(group.skipOnAbsent().isEmpty());
        var rows=bindingAt(f,plan,true).rows();
        assertTrue(rows.stream().anyMatch(r -> r.outcome()==InjectionBindings.Outcome.UNSATISFIED));
        assertTrue(rows.stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        assertTrue(selected(evaluate(f,plan),true).isEmpty());
        try(var context=container(true)) {
            context.register(OracleStore.class,OracleRequiredTwoParameters.class);
            assertThrows(org.springframework.beans.factory.UnsatisfiedDependencyException.class,context::refresh);
        }
    }
    @org.springframework.stereotype.Component
    static class OracleRequiredTwoParameters {
        @org.springframework.beans.factory.annotation.Autowired
        void wire(OracleStore first,@org.springframework.beans.factory.annotation.Qualifier("missing") OracleStore second){}
    }
    @Test void optionalMethodPrimaryConflictRemainsAnErrorInsteadOfAbsence() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary Store first(){return new Store();}
                  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary Store second(){return new Store();}
                }
                @org.springframework.stereotype.Component class Client {
                  @org.springframework.beans.factory.annotation.Autowired(required=false) void wire(Store store) {}
                }
                """);
        var plan=beanPlan(f,true,"Config","first","second","Client");
        assertEquals(InjectionBindings.Outcome.AMBIGUOUS,bindingAt(f,plan,true).rows().getFirst().outcome());
        assertTrue(selected(evaluate(f,plan),true).isEmpty());
        try(var context=container(true)) {
            context.register(OracleOptionalPrimaryConfig.class,OracleOptionalAmbiguous.class);
            assertThrows(org.springframework.beans.factory.UnsatisfiedDependencyException.class,context::refresh);
        }
    }
    @org.springframework.stereotype.Component
    static class OracleOptionalAmbiguous {
        @org.springframework.beans.factory.annotation.Autowired(required=false) void wire(OracleBeanStore store){}
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleOptionalPrimaryConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary OracleBeanStore first(){return new OracleBeanStore();}
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary OracleBeanStore second(){return new OracleBeanStore();}
    }
    @Test void sourceDefinedInjectionAnnotationCannotManufactureAFrameworkDescriptor() throws Exception {
        var f=sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @interface Autowired {}
                @org.springframework.stereotype.Component class Store {}
                @org.springframework.stereotype.Component class Client { @Autowired Store field; }
                """);
        var plan=prepare(f,true);
        assertTrue(plan.binding().dependencies().isEmpty());assertFalse(plan.gaps().isEmpty());
        assertTrue(f.source().frontend().annotations().stream().anyMatch(a -> a.spelling().equals("@Autowired")));
        assertTrue(selected(evaluate(f,plan),true).isEmpty());
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class OracleIdentifier {
        @org.springframework.context.annotation.Bean OracleBeanStore st\u200bore(){return new OracleBeanStore();}
    }
}
