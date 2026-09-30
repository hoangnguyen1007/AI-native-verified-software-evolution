package com.evolution.analysis.javaparser;

import com.evolution.analysis.spring.binding.*;
import com.evolution.analysis.spring.universal.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.truth.*;
import java.util.*;
import static com.evolution.analysis.spring.registration.RegistrationEvent.Completeness.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual passive source journey; containers execute only fixed authored controls. */
class ResourceFieldIngestionTest {
    private final SourceToSpringPlanTest fixtures=new SourceToSpringPlanTest();
    private SourceToSpringPlan.Result plan(SourceToSpringPlanTest.Fixture f,String... order) {
        return plan(f,100,true,order);
    }
    private SourceToSpringPlan.Scope scope(SourceToSpringPlanTest.Fixture f,boolean policy,String... order) {
        var ordinary=fixtures.beanPlan(f,true,order);
        var schedule=ordinary.binding().registrationPlan();
        var proof=schedule.orderEvidence();
        var resourceProof=new ConditionEvidence.Derived(List.of(f.source().identity()),
                new VersionedIdentifier("test.resource-policy","6.2.0-defaults"),"authored-standard-resource-processor");
        return new SourceToSpringPlan.Scope("authored-context",f.source().identity(),
                schedule.establishedOrderPrefix().stream()
                        .map(slot -> f.source().declarations().values().stream().filter(d -> d.entity().identity().value().equals(slot)).findFirst().orElseThrow().entity().identity()).toList(),
                COMPLETE,COMPLETE,COMPLETE,COMPLETE,COMPLETE,
                com.evolution.analysis.spring.registration.RegistrationPlan.OverridePolicy.FORBID,proof,
                policy?Optional.of(new SourceToSpringPlan.ResourcePolicy(resourceProof)):Optional.empty());
    }
    private SourceToSpringPlan.Result plan(SourceToSpringPlanTest.Fixture f,int limit,boolean policy,String... order) {
        return f.pipeline().prepareSpring(f.result(),f.key(),scope(f,policy,order),limit);
    }
    private SourceToSpringPlanTest.Fixture fixture(String annotation,String field) throws Exception {
        return fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev")
                class Client { %s Store %s; }
                """.formatted(annotation,field));
    }
    @Test void defaultResourceFieldUsesItsEvidencedNameAndResourceSelectionPath() throws Exception {
        var f=fixture("@jakarta.annotation.Resource","store");
        var plan=plan(f,"Store","Client");
        var dependency=plan.binding().dependencies().getFirst();
        assertEquals(InjectionBindingPlan.Normalization.COMPLETE,dependency.normalization());
        assertEquals(InjectionBindingPlan.Mode.RESOURCE,dependency.mode());
        assertEquals(InjectionPoint.SiteKind.FIELD,dependency.point().siteKind());
        assertEquals("store",dependency.dependencyName().value().orElseThrow());
        assertTrue(dependency.resourceDefaultName());
        assertTrue(dependency.resourceTypeFallback());
        var row=fixtures.bindingAt(f,plan,true).rows().getFirst();
        assertEquals(InjectionBindings.Outcome.SELECTED,row.outcome());
        assertEquals(InjectionBindings.Stage.RESOURCE_NAME,row.selectionStage().orElseThrow());
        assertEquals("6.2.0",org.springframework.context.annotation.CommonAnnotationBeanPostProcessor.class.getPackage().getImplementationVersion());
        assertEquals("m4uv2.2-resource-fields-v1",SourceToSpringPlan.PROVIDER.version());
        var evidence=assertInstanceOf(ConditionEvidence.Source.class,dependency.evidence());
        assertTrue(evidence.span().isPresent());assertTrue(f.build().containsSource(evidence));
        assertTrue(plan.binding().obligations().stream().anyMatch(o -> o.dependencies().contains(dependency.identity())));
        assertEquals(InjectionBindings.Outcome.NOT_ACTIVE,fixtures.bindingAt(f,plan,false).rows().getFirst().outcome());
        var evaluated=fixtures.evaluate(f,plan);
        assertTrue(evaluated.regions().stream().anyMatch(r -> r.fact().kind()==ConditionalFactKey.Kind.SELECTED_BINDING
                &&r.classification()==TruthRegionEvaluation.Classification.MAY));
        assertFalse(evaluated.replays().isEmpty());assertTrue(evaluated.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        assertEquals(evaluated.identity(),fixtures.evaluate(f,plan(f,"Store","Client")).identity());
        assertTrue(plan.gaps().containsAll(f.result().gaps()));
        var structural=f.result().units().getFirst().spring().orElseThrow();
        assertTrue(structural.bindings().stream().allMatch(b -> b.status()==UniversalSpringSemantics.BindingStatus.UNKNOWN));
        try(var context=fixtures.container(true)) {
            context.register(Store.class,Client.class);context.refresh();
            assertSame(context.getBean(Store.class),context.getBean(Client.class).store);
        }
    }

    @Test void explicitNameBypassesQualifierAutowireDefaultAndPrimaryFilters() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean(autowireCandidate=false,defaultCandidate=false) Store store() { return new Store(); }
                  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary Store other() { return new Store(); }
                }
                @org.springframework.stereotype.Component class Client {
                  @jakarta.annotation.Resource(name="store") @org.springframework.beans.factory.annotation.Qualifier("excluded") Store field;
                }
                """);
        var plan=plan(f,"Config","store","other","Client");
        var d=plan.binding().dependencies().getFirst();assertFalse(d.resourceDefaultName());
        assertEquals(InjectionBindingPlan.Normalization.COMPLETE,d.normalization());
        var row=fixtures.bindingAt(f,plan,true).rows().getFirst();
        assertEquals(InjectionBindings.Outcome.SELECTED,row.outcome());assertEquals("store",row.selected().getFirst().beanName());
        assertEquals(InjectionBindings.Stage.RESOURCE_NAME,row.selectionStage().orElseThrow());
        try(var context=fixtures.container(true)) {context.register(NameConfig.class,ExplicitClient.class);context.refresh();
            assertSame(context.getBean("store"),context.getBean(ExplicitClient.class).field);}
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) static class NameConfig {
        @org.springframework.context.annotation.Bean(autowireCandidate=false,defaultCandidate=false) Store store(){return new Store();}
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary Store other(){return new Store();}
    }
    @org.springframework.stereotype.Component static class ExplicitClient {
        @jakarta.annotation.Resource(name="store") @org.springframework.beans.factory.annotation.Qualifier("excluded") Store field;
    }

    @Test void absentDefaultNameFallsBackButAbsentExplicitNameDoesNot() throws Exception {
        for(String annotation:List.of("@jakarta.annotation.Resource","@jakarta.annotation.Resource(name=\"\")","@jakarta.annotation.Resource(name=\"absent\")")) {
            var f=fixture(annotation,"absent");var plan=plan(f,"Store","Client");
            var d=plan.binding().dependencies().getFirst();var row=fixtures.bindingAt(f,plan,true).rows().getFirst();
            boolean explicit=annotation.contains("absent");
            assertEquals(!explicit,d.resourceDefaultName());
            assertEquals(explicit?InjectionBindings.Outcome.UNSATISFIED:InjectionBindings.Outcome.SELECTED,row.outcome());
            if(!explicit)assertEquals("store",row.selected().getFirst().beanName());
            else assertTrue(row.selected().isEmpty());
        }
        try(var context=fixtures.container(true)){context.register(Store.class,FallbackClient.class,EmptyNameClient.class);context.refresh();
            assertSame(context.getBean(Store.class),context.getBean(FallbackClient.class).absent);
            assertSame(context.getBean(Store.class),context.getBean(EmptyNameClient.class).absent);}
        try(var context=fixtures.container(true)){context.register(Store.class,MissingExplicitClient.class);
            assertThrows(org.springframework.beans.factory.BeanCreationException.class,context::refresh);}
    }
    @org.springframework.stereotype.Component static class FallbackClient {@jakarta.annotation.Resource Store absent;}
    @org.springframework.stereotype.Component static class EmptyNameClient {@jakarta.annotation.Resource(name="") Store absent;}
    @org.springframework.stereotype.Component static class MissingExplicitClient {@jakarta.annotation.Resource(name="absent") Store field;}

    @Test void existingWrongTypeIsAnErrorAndNeverFallsBack() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component("other") class Store {}
                @org.springframework.stereotype.Component("store") class Wrong {}
                @org.springframework.stereotype.Component class Client { @jakarta.annotation.Resource Store store; }
                """);
        var plan=plan(f,"Store","Wrong","Client");var row=fixtures.bindingAt(f,plan,true).rows().getFirst();
        assertEquals(InjectionBindings.Outcome.ERROR,row.outcome());assertTrue(row.selected().isEmpty());
        assertTrue(fixtures.bindingAt(f,plan,true).issues().stream().anyMatch(i -> i.reason()==BindingProcessing.Reason.NAMED_TYPE_MISMATCH));
        try(var context=fixtures.container(true)){context.registerBean("other",Store.class);context.registerBean("store",Object.class);context.register(Client.class);
            assertThrows(org.springframework.beans.factory.BeanCreationException.class,context::refresh);}
    }

    @Test void conditionalResourceNameAbsenceIsEvaluatedAgainstTheRealizedRegistry() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev") class Store {}
                @org.springframework.stereotype.Component class Client { @jakarta.annotation.Resource(name="store") Store field; }
                """);
        var plan=plan(f,"Store","Client");
        assertEquals(InjectionBindings.Outcome.SELECTED,fixtures.bindingAt(f,plan,true).rows().getFirst().outcome());
        var absent=fixtures.bindingAt(f,plan,false).rows().getFirst();
        assertEquals(InjectionBindings.Outcome.UNSATISFIED,absent.outcome());assertTrue(absent.selected().isEmpty());
        fixtures.evaluate(f,plan);
        try(var context=fixtures.container(true)){context.register(ProfileStore.class,ExplicitClient.class);context.refresh();
            assertSame(context.getBean("store"),context.getBean(ExplicitClient.class).field);}
        try(var context=fixtures.container(false)){context.register(ProfileStore.class,ExplicitClient.class);
            assertThrows(org.springframework.beans.factory.BeanCreationException.class,context::refresh);}
    }
    @org.springframework.stereotype.Component("store") @org.springframework.context.annotation.Profile("dev") static class ProfileStore extends Store {}

    @Test void defaultTypeFallbackRetainsAmbiguityAndAppliesItsOwnQualifierFilters() throws Exception {
        for(String qualifier:List.of("","@org.springframework.beans.factory.annotation.Qualifier(\"first\")")) {
            var f=fixtures.sourceFixture("""
                    package app;
                    @org.springframework.context.annotation.ComponentScan("app") class App {}
                    class Store {}
                    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                      @org.springframework.context.annotation.Bean Store first() { return new Store(); }
                      @org.springframework.context.annotation.Bean Store second() { return new Store(); }
                    }
                    @org.springframework.stereotype.Component class Client { @jakarta.annotation.Resource %s Store absent; }
                    """.formatted(qualifier));
            var plan=plan(f,"Config","first","second","Client");var row=fixtures.bindingAt(f,plan,true).rows().getFirst();
            assertEquals(qualifier.isEmpty()?InjectionBindings.Outcome.AMBIGUOUS:InjectionBindings.Outcome.SELECTED,row.outcome());
            if(qualifier.isEmpty())assertTrue(row.selected().isEmpty());else assertEquals("first",row.selected().getFirst().beanName());
        }
        try(var context=fixtures.container(true)){context.registerBean("first",Store.class);context.registerBean("second",Store.class);context.register(FallbackClient.class);
            assertThrows(org.springframework.beans.factory.BeanCreationException.class,context::refresh);}
        try(var context=fixtures.container(true)){context.registerBean("first",Store.class);context.registerBean("second",Store.class);context.register(QualifiedFallbackClient.class);context.refresh();
            assertSame(context.getBean("first"),context.getBean(QualifiedFallbackClient.class).absent);}
    }
    @org.springframework.stereotype.Component static class QualifiedFallbackClient {
        @jakarta.annotation.Resource @org.springframework.beans.factory.annotation.Qualifier("first") Store absent;
    }

    @Test void sourceProductsRetainSharedPointsSeparateConditionalOwnersAndFactoryOwnership() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                final class Product { @jakarta.annotation.Resource private Store store; }
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Profile("dev") Product first(){ return new Product(); }
                  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Profile("!dev") Product second(){ return new Product(); }
                }
                """);
        var plan=plan(f,"Store","Config","first","second");var deps=plan.binding().dependencies();
        assertEquals(2,deps.size());assertEquals(deps.getFirst().point().identity(),deps.getLast().point().identity());
        assertNotEquals(deps.getFirst().owner(),deps.getLast().owner());assertNotEquals(deps.getFirst().identity(),deps.getLast().identity());
        assertTrue(deps.stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.COMPLETE));
        assertTrue(plan.binding().groups().isEmpty());
        for(var d:deps)assertEquals(Optional.of(fixtures.candidate(plan,"config").identity()),plan.binding().definitions().stream()
                .filter(def -> d.owner().filter(def.candidate()::equals).isPresent()).findFirst().orElseThrow().factoryOwner());
        assertTrue(plan.binding().obligations().stream().anyMatch(o -> o.dependencies().size()==2));
        var truth=fixtures.evaluate(f,plan);assertTrue(truth.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        for(boolean active:List.of(false,true)) {
            var rows=fixtures.bindingAt(f,plan,active).rows();
            assertEquals(1,rows.stream().filter(r -> r.outcome()==InjectionBindings.Outcome.SELECTED).count());
            assertEquals(1,rows.stream().filter(r -> r.outcome()==InjectionBindings.Outcome.NOT_ACTIVE).count());
            try(var context=fixtures.container(active)){context.register(Store.class,ProductConfig.class);context.refresh();
                assertSame(context.getBean(Store.class),context.getBean(active?"first":"second",Product.class).store);}
        }
        var limited=plan(f,1,true,"Store","Config","first","second");
        assertEquals(2,limited.binding().dependencies().size());assertFalse(limited.gaps().isEmpty());
        assertEquals(2,fixtures.bindingAt(f,limited,true).coverage().dependencies());
    }
    static final class Product {@jakarta.annotation.Resource private Store store;}
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) static class ProductConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Profile("dev") Product first(){return new Product();}
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Profile("!dev") Product second(){return new Product();}
    }

    @Test void missingProcessorPolicyKeepsResourceUnknownAndParticipatesInIdentity() throws Exception {
        var f=fixture("@jakarta.annotation.Resource","store");var known=plan(f,"Store","Client");
        var unknown=plan(f,100,false,"Store","Client");
        assertNotEquals(known.inputIdentity(),unknown.inputIdentity());
        assertEquals(InjectionBindingPlan.Normalization.INCOMPLETE,unknown.binding().dependencies().getFirst().normalization());
        assertEquals(InjectionBindings.Outcome.UNKNOWN,fixtures.bindingAt(f,unknown,true).rows().getFirst().outcome());
        assertEquals(UNKNOWN,unknown.binding().environment().descriptorsComplete());assertFalse(unknown.gaps().isEmpty());
        try(var context=fixtures.container(true)) {
            context.register(Store.class,FallbackClient.class);
            context.addBeanFactoryPostProcessor(factory -> ((org.springframework.context.annotation.CommonAnnotationBeanPostProcessor)
                    factory.getBean(org.springframework.context.annotation.AnnotationConfigUtils.COMMON_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .setFallbackToDefaultTypeMatch(false));
            assertThrows(org.springframework.beans.factory.BeanCreationException.class,context::refresh);
        }
    }

    @Test void processorProofMustBelongToTheExactBuildEvidence() throws Exception {
        var f=fixture("@jakarta.annotation.Resource","store");var normal=scope(f,true,"Store","Client");
        var foreign=ContentDigest.sha256Utf8("authored-foreign-artifact-control");
        var bad=new SourceToSpringPlan.Scope(normal.container(),normal.sourceEvidence(),normal.registrationOrder(),normal.order(),
                normal.registry(),normal.noParent(),normal.noResolvableDependencies(),normal.noPostRegistrationMutation(),normal.overrides(),normal.evidence(),
                Optional.of(new SourceToSpringPlan.ResourcePolicy(new ConditionEvidence.Artifact(foreign,foreign,"processor-policy"))));
        assertThrows(IllegalArgumentException.class,() -> f.pipeline().prepareSpring(f.result(),f.key(),bad,100));
        var field=f.source().frontend().memberDeclarations().stream().filter(m -> m.name().equals("store")).findFirst().orElseThrow();
        var source=assertInstanceOf(ConditionEvidence.Source.class,f.source().evidence(field.member()));
        var missingSpan=new SourceToSpringPlan.Scope(normal.container(),normal.sourceEvidence(),normal.registrationOrder(),normal.order(),
                normal.registry(),normal.noParent(),normal.noResolvableDependencies(),normal.noPostRegistrationMutation(),normal.overrides(),normal.evidence(),
                Optional.of(new SourceToSpringPlan.ResourcePolicy(new ConditionEvidence.Source(source.document(),source.rawDigest(),Optional.empty(),0))));
        assertThrows(IllegalArgumentException.class,() -> f.pipeline().prepareSpring(f.result(),f.key(),missingSpan,100));
    }

    @Test void unsupportedAttributesShapesAndDynamicNamesNeverBecomeSelections() throws Exception {
        for(String member:List.of("@jakarta.annotation.Resource(type=Store.class) Store store;",
                "@jakarta.annotation.Resource(mappedName=\"remote\") Store store;",
                "@jakarta.annotation.Resource(lookup=\"remote\") Store store;",
                "@jakarta.annotation.Resource(name=\"${store.name}\") Store store;",
                "@jakarta.annotation.Resource(name=\"#{beanName}\") Store store;",
                "@jakarta.annotation.Resource(name=true) Store store;",
                "@jakarta.annotation.Resource static Store store;",
                "@jakarta.annotation.Resource void setStore(Store store) {}",
                "@jakarta.annotation.Resource @org.springframework.beans.factory.annotation.Autowired Store store;",
                "@jakarta.annotation.Resource @org.springframework.context.annotation.Lazy Store store;",
                "@jakarta.annotation.Resource @jakarta.inject.Named(\"store\") Store store;",
                "@jakarta.annotation.Resource java.util.List<Store> store;",
                "@jakarta.annotation.Resource Missing store;")) {
            var f=fixtures.sourceFixture("""
                    package app;
                    @org.springframework.context.annotation.ComponentScan("app") class App {}
                    @org.springframework.stereotype.Component class Store {}
                    @org.springframework.stereotype.Component class Client { %s }
                    """.formatted(member));
            var plan=plan(f,"Store","Client");assertFalse(plan.gaps().isEmpty(),member);
            assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()!=InjectionBindingPlan.Normalization.COMPLETE),member);
            assertTrue(fixtures.bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED),member);
        }
        try(var context=fixtures.container(true)){context.register(Store.class,StaticResourceClient.class);
            assertThrows(org.springframework.beans.factory.BeanCreationException.class,context::refresh);}
    }
    @org.springframework.stereotype.Component static class StaticResourceClient {@jakarta.annotation.Resource static Store store;}

    @Test void inheritedResourceAndUnprovedProductRuntimeTypeRemainExplicitGaps() throws Exception {
        var inherited=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                class Base { @jakarta.annotation.Resource Store store; }
                @org.springframework.stereotype.Component class Client extends Base {}
                """);
        var plan=plan(inherited,"Store","Client");assertEquals(1,plan.binding().dependencies().size());
        assertEquals(InjectionBindingPlan.Normalization.INCOMPLETE,plan.binding().dependencies().getFirst().normalization());
        assertFalse(plan.gaps().isEmpty());assertEquals(InjectionBindings.Outcome.UNKNOWN,fixtures.bindingAt(inherited,plan,true).rows().getFirst().outcome());
        var wider=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                class Product { @jakarta.annotation.Resource Store store; }
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean Product product(){ return new Product(); }
                }
                """);
        var product=plan(wider,"Store","Config","product");
        assertEquals(InjectionBindingPlan.Normalization.INCOMPLETE,product.binding().dependencies().getFirst().normalization());
        assertFalse(product.gaps().isEmpty());
    }

    @Test void absentArtifactAndSourceImpostorsRetainObservationsWithoutFabricatingDescriptors() throws Exception {
        String text="""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                @org.springframework.stereotype.Component class Client { @jakarta.annotation.Resource Store store; }
                """;
        for(var f:List.of(fixtures.sourceFixture(Map.of("app/App.java",text),false),fixtures.sourceFixture(Map.of(
                "app/App.java",text,"jakarta/annotation/Resource.java","package jakarta.annotation; public @interface Resource {}")))) {
            var plan=plan(f,"Store","Client");assertTrue(plan.binding().dependencies().isEmpty());
            assertFalse(plan.gaps().isEmpty());assertTrue(plan.gaps().containsAll(f.result().gaps()));
            assertFalse(f.source().frontend().annotations().isEmpty());
            assertTrue(plan.inventory().rawObservations().stream().anyMatch(o -> o.spelling().contains("Resource")&&o.span().isPresent()));
        }
    }
    @org.springframework.stereotype.Component("store") static class Store {}
    @org.springframework.stereotype.Component("client") @org.springframework.context.annotation.Profile("dev")
    static class Client { @jakarta.annotation.Resource Store store; }
}
