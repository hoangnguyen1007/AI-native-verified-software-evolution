package com.evolution.analysis.javaparser;

import com.evolution.analysis.spring.binding.*;
import com.evolution.analysis.spring.universal.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import com.evolution.analysis.spring.truth.*;
import com.evolution.analysis.contract.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source-acquired descriptors, with independent fixed Spring container controls. */
class SemanticClosureIntegrationTest {
    private final SourceToSpringPlanTest fixtures=new SourceToSpringPlanTest();
    private SourceToSpringPlan.Result resourcePlan(SourceToSpringPlanTest.Fixture f,String... order) {
        var initial=fixtures.beanPlan(f,true,order);var r=initial.binding().registrationPlan();
        var ids=r.establishedOrderPrefix().stream().filter(slot -> f.source().declarations().keySet().stream().anyMatch(id -> id.value().equals(slot)))
                .map(slot -> f.source().declarations().values().stream()
                .filter(d -> d.entity().identity().value().equals(slot)).findFirst().orElseThrow().entity().identity()).toList();
        var proof=new ConditionEvidence.Derived(List.of(f.source().identity()),new VersionedIdentifier("test.resource-policy","6.2.0-defaults"),"authored-policy");
        var scope=new SourceToSpringPlan.Scope("authored-context",f.source().identity(),ids,RegistrationEvent.Completeness.COMPLETE,
                RegistrationEvent.Completeness.COMPLETE,RegistrationEvent.Completeness.COMPLETE,RegistrationEvent.Completeness.COMPLETE,
                RegistrationEvent.Completeness.COMPLETE,RegistrationPlan.OverridePolicy.FORBID,r.orderEvidence(),
                Optional.of(new SourceToSpringPlan.ResourcePolicy(proof)));
        return f.pipeline().prepareSpring(f.result(),f.key(),scope,100);
    }
    @Test void inheritedPrivateResourceFieldsKeepSeparateConditionalOwners() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                abstract class Base { @jakarta.annotation.Resource private Store store; }
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("dev") class First extends Base {}
                @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile("!dev") class Second extends Base {}
                """);
        var plan=resourcePlan(f,"Store","First","Second");var deps=plan.binding().dependencies();
        assertEquals(2,deps.size());assertEquals(deps.getFirst().point(),deps.getLast().point());
        assertNotEquals(deps.getFirst().owner(),deps.getLast().owner());
        assertTrue(deps.stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.COMPLETE));
        assertTrue(plan.binding().obligations().stream().anyMatch(o -> o.dependencies().size()==2));
        for(boolean active:List.of(false,true)) {
            var rows=fixtures.bindingAt(f,plan,active).rows();
            assertEquals(1,rows.stream().filter(r -> r.outcome()==InjectionBindings.Outcome.SELECTED).count());
            assertEquals(1,rows.stream().filter(r -> r.outcome()==InjectionBindings.Outcome.NOT_ACTIVE).count());
        }
        var truth=fixtures.evaluate(f,plan);assertTrue(truth.replays().stream().allMatch(TruthRegionEvaluation.WitnessReplay::valid));
        assertEquals(plan.identity(),resourcePlan(f,"Store","First","Second").identity());
        try(var c=fixtures.container(true)){c.register(Store.class,ResourceChild.class);c.refresh();
            assertSame(c.getBean(Store.class),((ResourceBase)c.getBean(ResourceChild.class)).store);}
    }
    @Test void resourceSetterUsesPropertyNameAndMostSpecificMethod() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component("URL") class Store {}
                abstract class Base { @jakarta.annotation.Resource void setURL(Store s) {} }
                @org.springframework.stereotype.Component class Client extends Base {}
                @org.springframework.stereotype.Component class Suppressed extends Base { @Override void setURL(Store s) {} }
                """);
        var plan=resourcePlan(f,"Store","Client","Suppressed");
        assertEquals(1,plan.binding().dependencies().size());var d=plan.binding().dependencies().getFirst();
        assertEquals("URL",d.dependencyName().value().orElseThrow());
        assertEquals(InjectionPoint.SiteKind.METHOD_PARAMETER,d.point().siteKind());
        assertEquals(InjectionBindingPlan.Normalization.COMPLETE,d.normalization());
        assertEquals(InjectionBindings.Outcome.SELECTED,fixtures.bindingAt(f,plan,true).rows().getFirst().outcome());
        assertTrue(plan.methodSelections().stream().anyMatch(s -> s.status()==SourceMethodSelection.Status.SUPPRESSED));
        try(var c=fixtures.container(true)){c.registerBean("URL",Store.class);c.register(SetterChild.class,SetterSuppressed.class);c.refresh();
            assertSame(c.getBean("URL"),c.getBean(SetterChild.class).value);assertNull(c.getBean(SetterSuppressed.class).value);}
    }
    @Test void optionalAndProviderFieldsReachTheirDistinctBindingSemantics() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Missing {}
                @org.springframework.stereotype.Component class Client {
                  @org.springframework.beans.factory.annotation.Autowired java.util.Optional<Missing> optional;
                  @org.springframework.beans.factory.annotation.Autowired org.springframework.beans.factory.ObjectProvider<Missing> provider;
                }
                """);
        var plan=fixtures.beanPlan(f,true,"Client");
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.COMPLETE));
        assertEquals(Set.of(InjectionBindings.Outcome.ABSENT_OPTIONAL,InjectionBindings.Outcome.DEFERRED),
                new HashSet<>(fixtures.bindingAt(f,plan,true).rows().stream().map(InjectionBindings.Row::outcome).toList()));
        try(var c=fixtures.container(true)){c.register(OptionalClient.class);c.refresh();var client=c.getBean(OptionalClient.class);
            assertTrue(client.optional.isEmpty());assertNull(client.provider.getIfAvailable());}
    }
    @Test void aggregateFieldsRetainEveryEligibleElementAndMapName() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary Store first(){return new Store();}
                  @org.springframework.context.annotation.Bean Store second(){return new Store();}
                }
                @org.springframework.stereotype.Component class Client {
                  @org.springframework.beans.factory.annotation.Autowired java.util.List<Store> list;
                  @org.springframework.beans.factory.annotation.Autowired java.util.Set<Store> set;
                  @org.springframework.beans.factory.annotation.Autowired java.util.Map<String,Store> map;
                  @org.springframework.beans.factory.annotation.Autowired Store[] array;
                }
                """);
        var plan=fixtures.beanPlan(f,true,"Config","first","second","Client");
        assertEquals(4,plan.binding().dependencies().size());
        for(var row:fixtures.bindingAt(f,plan,true).rows()) {
            assertEquals(InjectionBindings.Outcome.AGGREGATE,row.outcome());
            assertEquals(Set.of("first","second"),new HashSet<>(row.selected().stream().map(InjectionBindings.Target::beanName).toList()));
        }
        fixtures.evaluate(f,plan);
        try(var c=fixtures.container(true)){c.register(AggregateConfig.class,AggregateClient.class);c.refresh();var client=c.getBean(AggregateClient.class);
            assertEquals(2,client.list.size());assertEquals(2,client.set.size());assertEquals(2,client.array.length);
            assertEquals(Set.of("first","second"),client.map.keySet());}
    }
    @Test void beanAliasesParticipateInResourceNameLookup() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                final class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean({"store","alias"}) Store make(){return new Store();}
                }
                @org.springframework.stereotype.Component class Client { @jakarta.annotation.Resource(name="alias") Store field; }
                """);
        var plan=resourcePlan(f,"Config","make","Client");
        var row=fixtures.bindingAt(f,plan,true).rows().getFirst();
        assertEquals(InjectionBindings.Outcome.SELECTED,row.outcome());assertEquals("store",row.selected().getFirst().beanName());
        try(var c=fixtures.container(true)){c.register(AliasConfig.class,AliasClient.class);c.refresh();
            assertSame(c.getBean("store"),c.getBean("alias"));assertSame(c.getBean("store"),c.getBean(AliasClient.class).field);}
    }
    @Test void optionalWrapperDoesNotSkipAnOptionalMethodButMissingScalarDoes() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                class Missing {}
                @org.springframework.stereotype.Component class Client {
                  @org.springframework.beans.factory.annotation.Autowired(required=false) void configure(Store s, java.util.Optional<Missing> m) {}
                }
                """);
        var plan=fixtures.beanPlan(f,true,"Store","Client");
        assertEquals(1,plan.binding().groups().getFirst().skipOnAbsent().size());
        assertEquals(Set.of(InjectionBindings.Outcome.SELECTED,InjectionBindings.Outcome.ABSENT_OPTIONAL),
                new HashSet<>(fixtures.bindingAt(f,plan,true).rows().stream().map(InjectionBindings.Row::outcome).toList()));
        try(var c=fixtures.container(true)){c.register(Store.class,OptionalMethodClient.class);c.refresh();
            assertSame(c.getBean(Store.class),c.getBean(OptionalMethodClient.class).value);}
    }
    @Test void emptyConstructorAggregatesAreAdaptedButRequiredFieldsStayUnsatisfied() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Missing {}
                @org.springframework.stereotype.Component class Client { Client(java.util.List<Missing> values) {} }
                @org.springframework.stereotype.Component class Field { @org.springframework.beans.factory.annotation.Autowired java.util.List<Missing> values; }
                """);
        var plan=fixtures.beanPlan(f,true,"Client","Field");var rows=fixtures.bindingAt(f,plan,true).rows();
        for(var d:plan.binding().dependencies()) {
            var row=rows.stream().filter(r -> r.dependency().equals(d.identity())).findFirst().orElseThrow();
            assertEquals(d.point().siteKind()==InjectionPoint.SiteKind.CONSTRUCTOR_PARAMETER?InjectionBindings.Outcome.AGGREGATE:InjectionBindings.Outcome.UNSATISFIED,row.outcome());
            assertTrue(row.selected().isEmpty());
        }
        try(var c=fixtures.container(true)){c.register(EmptyConstructorClient.class);c.refresh();assertTrue(c.getBean(EmptyConstructorClient.class).values.isEmpty());}
        try(var c=fixtures.container(true)){c.register(EmptyFieldClient.class);assertThrows(org.springframework.beans.factory.BeanCreationException.class,c::refresh);}
    }
    @Test void optionalAmbiguityAndQualifierMismatchNeverChooseAnArbitraryBean() throws Exception {
        for(String qualifier:List.of("","@org.springframework.beans.factory.annotation.Qualifier(\"absent\")")) {
            var f=fixtures.sourceFixture("""
                    package app;
                    @org.springframework.context.annotation.ComponentScan("app") class App {}
                    final class Store {}
                    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                      @org.springframework.context.annotation.Bean Store first(){return new Store();}
                      @org.springframework.context.annotation.Bean Store second(){return new Store();}
                    }
                    @org.springframework.stereotype.Component class Client { @org.springframework.beans.factory.annotation.Autowired %s java.util.Optional<Store> value; }
                    """.formatted(qualifier));
            var row=fixtures.bindingAt(f,fixtures.beanPlan(f,true,"Config","first","second","Client"),true).rows().getFirst();
            assertTrue(row.selected().isEmpty());
            // Parameter/field name is evidenced here; neither candidate has that name.
            assertEquals(qualifier.isEmpty()?InjectionBindings.Outcome.AMBIGUOUS:InjectionBindings.Outcome.ABSENT_OPTIONAL,row.outcome());
        }
        try(var c=fixtures.container(true)){c.registerBean("first",Store.class);c.registerBean("second",Store.class);c.register(AmbiguousOptionalClient.class);
            assertThrows(org.springframework.beans.factory.BeanCreationException.class,c::refresh);}
    }
    @Test void rawNestedWildcardAndNonStringMapDescriptorsRetainTypedGaps() throws Exception {
        for(String type:List.of("java.util.List","java.util.Optional<java.util.List<Store>>","java.util.List<? extends Store>","java.util.Map<Integer,Store>")) {
            var f=fixtures.sourceFixture("""
                    package app;
                    @org.springframework.context.annotation.ComponentScan("app") class App {}
                    @org.springframework.stereotype.Component class Store {}
                    @org.springframework.stereotype.Component class Client { @org.springframework.beans.factory.annotation.Autowired %s value; }
                    """.formatted(type));
            var plan=fixtures.beanPlan(f,true,"Store","Client");
            assertEquals(InjectionBindingPlan.Normalization.INCOMPLETE,plan.binding().dependencies().getFirst().normalization(),type);
            assertEquals(InjectionBindings.Outcome.UNKNOWN,fixtures.bindingAt(f,plan,true).rows().getFirst().outcome(),type);
            assertFalse(plan.gaps().isEmpty());
        }
    }
    @Test void aliasesAreConditionalOrderedAndCollisionsStayQualified() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                final class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean({"store","alias"}) @org.springframework.context.annotation.Profile("dev") Store make(){return new Store();}
                }
                @org.springframework.stereotype.Component class Client { @jakarta.annotation.Resource(name="alias") Store field; }
                """);
        var plan=resourcePlan(f,"Config","make","Client");
        assertEquals(InjectionBindings.Outcome.SELECTED,fixtures.bindingAt(f,plan,true).rows().getFirst().outcome());
        assertEquals(InjectionBindings.Outcome.UNSATISFIED,fixtures.bindingAt(f,plan,false).rows().getFirst().outcome());
        var registration=plan.binding().registrationPlan();var stepOrder=registration.establishedOrderPrefix();
        var alias=registration.steps().stream().filter(s -> s.operation()==BeanRegistrationPlan.Operation.REGISTER_ALIAS).findFirst().orElseThrow();
        var definition=registration.steps().stream().filter(s -> s.operation()==BeanRegistrationPlan.Operation.REGISTER_DEFINITION&&s.discoveryEvent().equals(alias.discoveryEvent())).findFirst().orElseThrow();
        assertTrue(stepOrder.indexOf(alias.key())<stepOrder.indexOf(definition.key()));
        fixtures.evaluate(f,plan);
        var clash=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                final class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean({"store","alias"}) Store first(){return new Store();}
                  @org.springframework.context.annotation.Bean("alias") Store second(){return new Store();}
                }
                """);
        var qualified=fixtures.beanPlan(clash,true,"Config","first","second");
        assertTrue(qualified.binding().registrationPlan().discoveryPlan().events().stream().filter(e -> e.kind()==RegistrationEvent.Kind.BEAN_METHOD)
                .allMatch(e -> e.conditionMetadata()==RegistrationEvent.Completeness.UNKNOWN));
        assertFalse(qualified.gaps().isEmpty());
    }
    @Test void unsupportedResourceMethodsKeepEveryObservedParameterWithoutSelecting() throws Exception {
        for(String method:List.of("static void setStore(Store s) {}","void setStores(Store a,Store b) {}","<T> void setStore(T s) {}")) {
            var f=fixtures.sourceFixture("""
                    package app;
                    @org.springframework.context.annotation.ComponentScan("app") class App {}
                    @org.springframework.stereotype.Component class Store {}
                    @org.springframework.stereotype.Component class Client { @jakarta.annotation.Resource %s }
                    """.formatted(method));
            var plan=resourcePlan(f,"Store","Client");
            assertFalse(plan.gaps().isEmpty());
            assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.INCOMPLETE));
            assertTrue(fixtures.bindingAt(f,plan,true).rows().stream().noneMatch(r -> r.outcome()==InjectionBindings.Outcome.SELECTED));
        }
    }
    @Test void implicitObjectSupertypeIsAProvenResourceTypeMatch() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                @org.springframework.stereotype.Component class Client { @jakarta.annotation.Resource(name="store") Object value; }
                """);
        var plan=resourcePlan(f,"Store","Client");
        var row=fixtures.bindingAt(f,plan,true).rows().getFirst();
        assertEquals(InjectionBindings.Outcome.SELECTED,row.outcome());assertEquals("store",row.selected().getFirst().beanName());
        try(var c=fixtures.container(true)){c.register(Store.class,ObjectResourceClient.class);c.refresh();
            assertSame(c.getBean(Store.class),c.getBean(ObjectResourceClient.class).value);}
    }
    @Test void qualifiedStaticNestedTypesRetainScalarAndElementBindings() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                class Outer { @org.springframework.stereotype.Component static class Store {} }
                @org.springframework.stereotype.Component class Client {
                  @org.springframework.beans.factory.annotation.Autowired Outer.Store scalar;
                  @org.springframework.beans.factory.annotation.Autowired java.util.List<Outer.Store> values;
                }
                """);
        var plan=fixtures.beanPlan(f,true,"Outer$Store","Client");
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.COMPLETE));
        assertEquals(Set.of(InjectionBindings.Outcome.SELECTED,InjectionBindings.Outcome.AGGREGATE),
                new HashSet<>(fixtures.bindingAt(f,plan,true).rows().stream().map(InjectionBindings.Row::outcome).toList()));
    }
    @Test void inheritedParameterizedMethodsUseResolvedSignaturesAndDoNotGuessErasureClashes() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                abstract class Base { @org.springframework.beans.factory.annotation.Autowired void configure(java.util.List<Store> values) {} }
                @org.springframework.stereotype.Component class Client extends Base {}
                @org.springframework.stereotype.Component class Suppressed extends Base { @Override void configure(java.util.List<Store> values) {} }
                """);
        var plan=fixtures.beanPlan(f,true,"Store","Client","Suppressed");
        assertEquals(1,plan.binding().dependencies().size());
        assertEquals(InjectionBindings.Outcome.AGGREGATE,fixtures.bindingAt(f,plan,true).rows().getFirst().outcome());
        assertTrue(plan.methodSelections().stream().anyMatch(s -> s.status()==SourceMethodSelection.Status.SUPPRESSED));
        var bad=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                @org.springframework.stereotype.Component class Store {}
                class Wrong {}
                abstract class Base { @org.springframework.beans.factory.annotation.Autowired void configure(java.util.List<Store> values) {} }
                @org.springframework.stereotype.Component class Client extends Base { void configure(java.util.List<Wrong> values) {} }
                """);
        var uncertain=fixtures.beanPlan(bad,true,"Store","Client");
        assertTrue(uncertain.methodSelections().stream().allMatch(s -> s.status()==SourceMethodSelection.Status.UNKNOWN));
        assertTrue(fixtures.bindingAt(bad,uncertain,true).rows().stream().allMatch(r -> r.selected().isEmpty()));
        assertFalse(uncertain.gaps().isEmpty());
    }
    @Test void aliasesQualifyAggregatesAndProvidersKeepTheirDeferredTargets() throws Exception {
        var f=fixtures.sourceFixture("""
                package app;
                @org.springframework.context.annotation.ComponentScan("app") class App {}
                final class Store {}
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) class Config {
                  @org.springframework.context.annotation.Bean({"store","alias"}) Store make(){return new Store();}
                }
                @org.springframework.stereotype.Component class Client {
                  @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("alias") java.util.Collection<Store> values;
                  @org.springframework.beans.factory.annotation.Autowired org.springframework.beans.factory.ObjectFactory<Store> factory;
                  @jakarta.inject.Inject jakarta.inject.Provider<Store> provider;
                }
                """);
        var plan=fixtures.beanPlan(f,true,"Config","make","Client");
        assertTrue(plan.binding().dependencies().stream().allMatch(d -> d.normalization()==InjectionBindingPlan.Normalization.COMPLETE));
        var rows=fixtures.bindingAt(f,plan,true).rows();
        assertEquals(2,rows.stream().filter(r -> r.outcome()==InjectionBindings.Outcome.DEFERRED).count());
        var aggregate=rows.stream().filter(r -> r.outcome()==InjectionBindings.Outcome.AGGREGATE).findFirst().orElseThrow();
        assertEquals(List.of("store"),aggregate.selected().stream().map(InjectionBindings.Target::beanName).toList());
        try(var c=fixtures.container(true)){c.register(AliasConfig.class,AliasProviderClient.class);c.refresh();
            var client=c.getBean(AliasProviderClient.class);var value=c.getBean("store");
            assertEquals(List.of(value),new ArrayList<>(client.values));
            assertSame(value,client.factory.getObject());assertSame(value,client.provider.get());}
    }
    @org.springframework.stereotype.Component("store") static class Store {}
    static class Missing {}
    abstract static class ResourceBase {@jakarta.annotation.Resource private Store store;}
    @org.springframework.stereotype.Component static class ResourceChild extends ResourceBase {}
    abstract static class SetterBase {Store value;@jakarta.annotation.Resource void setURL(Store s){value=s;}}
    @org.springframework.stereotype.Component static class SetterChild extends SetterBase {}
    @org.springframework.stereotype.Component static class SetterSuppressed extends SetterBase {@Override void setURL(Store s){value=s;}}
    @org.springframework.stereotype.Component static class OptionalClient {
        @org.springframework.beans.factory.annotation.Autowired Optional<Missing> optional;
        @org.springframework.beans.factory.annotation.Autowired org.springframework.beans.factory.ObjectProvider<Missing> provider;
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) static class AggregateConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary Store first(){return new Store();}
        @org.springframework.context.annotation.Bean Store second(){return new Store();}
    }
    @org.springframework.stereotype.Component static class AggregateClient {
        @org.springframework.beans.factory.annotation.Autowired List<Store> list;
        @org.springframework.beans.factory.annotation.Autowired Set<Store> set;
        @org.springframework.beans.factory.annotation.Autowired Map<String,Store> map;
        @org.springframework.beans.factory.annotation.Autowired Store[] array;
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) static class AliasConfig {
        @org.springframework.context.annotation.Bean({"store","alias"}) Store make(){return new Store();}
    }
    @org.springframework.stereotype.Component static class AliasClient {@jakarta.annotation.Resource(name="alias") Store field;}
    @org.springframework.stereotype.Component static class OptionalMethodClient {
        Store value;@org.springframework.beans.factory.annotation.Autowired(required=false) void configure(Store s,Optional<Missing> m){value=s;}
    }
    @org.springframework.stereotype.Component static class EmptyConstructorClient { final List<Missing> values;EmptyConstructorClient(List<Missing> values){this.values=values;} }
    @org.springframework.stereotype.Component static class EmptyFieldClient {@org.springframework.beans.factory.annotation.Autowired List<Missing> values;}
    @org.springframework.stereotype.Component static class AmbiguousOptionalClient {@org.springframework.beans.factory.annotation.Autowired Optional<Store> value;}
    @org.springframework.stereotype.Component static class ObjectResourceClient {@jakarta.annotation.Resource(name="store") Object value;}
    @org.springframework.stereotype.Component static class AliasProviderClient {
        @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("alias") Collection<Store> values;
        @org.springframework.beans.factory.annotation.Autowired org.springframework.beans.factory.ObjectFactory<Store> factory;
        @jakarta.inject.Inject jakarta.inject.Provider<Store> provider;
    }
}
