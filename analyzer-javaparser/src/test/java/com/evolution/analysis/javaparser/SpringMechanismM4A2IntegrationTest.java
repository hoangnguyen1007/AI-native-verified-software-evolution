package com.evolution.analysis.javaparser;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.Entity;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.spring.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringMechanismM4A2IntegrationTest {
    @TempDir Path temporary;

    @Test
    void exactFrontendEvidenceClosesTheRemainingJavaMechanismCandidates() throws Exception {
        BinaryInput springApi = springApiJar();
        String source = """
                package fixture;
                import java.util.List;
                import org.springframework.beans.factory.*;
                import org.springframework.beans.factory.support.*;
                import org.springframework.context.annotation.*;
                import org.springframework.data.repository.Repository;
                @org.springframework.stereotype.Component class App {
                  App(ObjectProvider<Service> service) {}
                  @Bean Service service(List<Service> values) { return null; }
                  void dynamic(BeanDefinitionRegistry registry, BeanFactory factory) { registry.registerBeanDefinition("x", null); factory.getBean(Service.class); }
                }
                class Service {}
                @org.springframework.stereotype.Component @interface Layer {
                  @org.springframework.core.annotation.AliasFor(annotation=org.springframework.stereotype.Component.class, attribute="value")
                  String value() default "";
                }
                @Layer class Layered {}
                @lombok.RequiredArgsConstructor @org.springframework.stereotype.Component class Generated { final Service service = null; }
                class ProductFactory implements FactoryBean<Service> {}
                interface Orders extends Repository<Service,Long> {}
                class Registrar implements BeanDefinitionRegistryPostProcessor {}
                class Runner implements org.springframework.boot.ApplicationRunner {}
                """;
        FrontendRequest request = TestInputs.request(Map.of("fixture/App.java", source), List.of(springApi));
        FrontendResult frontend = new JavaParserFrontend().analyze(request);
        assertEquals(FrontendResult.State.COMPLETED, frontend.state());

        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                frontend, frameworkEvidence(springApi), List.of(), List.of()));
        Map<String, Long> counts = inventory.obligations().stream().collect(Collectors.groupingBy(
                SpringMechanismInventory.SemanticObligation::primaryMechanism,
                TreeMap::new, Collectors.counting()));

        assertEquals(1L, counts.get("spring.injection.constructor.implicit"));
        assertEquals(2L, counts.get("spring.bean.stereotype.composed"));
        assertEquals(1L, counts.get("spring.injection.constructor.generated"));
        assertEquals(1L, counts.get("spring.injection.bean-parameter"));
        assertEquals(2L, counts.get("spring.injection.aggregate"));
        assertEquals(1L, counts.get("spring.registration.factory"));
        assertEquals(1L, counts.get("spring.registration.spring-data"));
        assertEquals(2L, counts.get("spring.registration.programmatic"));
        assertEquals(1L, counts.get("spring.lookup.container"));
        assertEquals(1L, counts.get("spring.entrypoint.framework"));

        Entity layerType = declaration(frontend,
                JavaSymbolName.topLevelType("fixture", "Layer").canonicalName());
        SpringMechanismInventory.RawObservation composedUse = inventory.obligations().stream()
                .filter(value -> value.primaryMechanism().equals("spring.bean.stereotype.composed"))
                .map(value -> inventory.rawObservations().stream()
                        .filter(raw -> raw.identity().equals(value.rawObservationIdentity()))
                        .findFirst().orElseThrow())
                .filter(raw -> raw.resolvedTarget().equals(Optional.of(layerType.identity())))
                .findFirst().orElseThrow();
        assertTrue(inventory.annotationDeclarations().stream()
                .filter(value -> value.declaration().equals(composedUse.resolvedTarget().orElseThrow()))
                .allMatch(SpringMechanismInventory.AnnotationDeclarationEvidence::graphComplete));
        SpringMechanismInventory.AnnotationDeclarationEvidence layer = inventory.annotationDeclarations().stream()
                .filter(value -> value.declaration().equals(composedUse.resolvedTarget().orElseThrow()))
                .findFirst().orElseThrow();
        assertEquals(1, layer.attributes().size());
        assertTrue(layer.attributes().getFirst().defaultDeclared());
        assertEquals(1, layer.attributes().getFirst().aliasObservationIdentities().size());
        assertFalse(inventory.problems().stream().anyMatch(problem ->
                problem.reason() == SpringMechanismInventory.ProblemReason.ANNOTATION_GRAPH_INCOMPLETE
                        && problem.rawObservationIdentity().equals(Optional.of(composedUse.identity()))));

        Set<SpringMechanismInventory.ProblemReason> reasons = inventory.problems().stream()
                .map(SpringMechanismInventory.Problem::reason).collect(Collectors.toSet());
        assertTrue(reasons.containsAll(Set.of(
                SpringMechanismInventory.ProblemReason.AGGREGATE_OR_PROVIDER_UNMODELED,
                SpringMechanismInventory.ProblemReason.GENERATED_MEMBER_NOT_ACQUIRED,
                SpringMechanismInventory.ProblemReason.FACTORY_PRODUCT_TYPE_UNKNOWN,
                SpringMechanismInventory.ProblemReason.REPOSITORY_REGISTRATION_UNPROVED,
                SpringMechanismInventory.ProblemReason.DYNAMIC_REGISTRY_MUTATION,
                SpringMechanismInventory.ProblemReason.DYNAMIC_LOOKUP_TARGET,
                SpringMechanismInventory.ProblemReason.ENTRYPOINT_OR_LIFECYCLE_UNMODELED)));
        assertEquals(inventory.identity(), SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                frontend, frameworkEvidence(springApi), List.of(), List.of())).identity());

        EvidenceContext context = new EvidenceContext(request.manifest().snapshot().identity(),
                Optional.of(frontend.analysis()));
        EvidenceAcquisitionLedger ledger = CapabilityGapNormalizer.normalize(context,
                EvidenceNormalizationInput.builder().springInventories(List.of(inventory)).build());
        assertEquals(EvidenceAcquisitionLedger.M4A2_NORMALIZER, ledger.normalizer());
        assertTrue(ledger.gaps().stream().filter(gap -> gap.reasonCode().equals("DYNAMIC_REGISTRY_MUTATION"))
                .allMatch(gap -> gap.evidenceRequirements().getFirst().authorizationClass()
                        == EvidenceRequirement.AuthorizationClass.RUNTIME_ACCESS));
        assertTrue(ledger.gaps().stream().allMatch(gap ->
                gap.catalog().equals(SpringCapabilityGapCatalog.CATALOG)));
    }

    @Test
    void projectDefinedSpringApiImpostorCannotCrossTheExactArtifactBoundary() throws Exception {
        BinaryInput springApi = springApiJar();
        FrontendResult frontend = new JavaParserFrontend().analyze(TestInputs.request("""
                package org.springframework.beans.factory.support;
                class BeanDefinitionRegistry { void registerBeanDefinition(String name, Object value) {} }
                class Caller { void call(BeanDefinitionRegistry registry) { registry.registerBeanDefinition("x", null); } }
                """));

        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                frontend, frameworkEvidence(springApi), List.of(), List.of()));

        assertEquals(1, inventory.rawObservations().stream()
                .filter(raw -> raw.kind() == SpringMechanismInventory.RawKind.JAVA_RELATIONSHIP).count());
        assertEquals(1, inventory.coverage().unclassifiedObservationCount());
        assertFalse(inventory.obligations().stream().anyMatch(value ->
                value.primaryMechanism().equals("spring.registration.programmatic")));
        assertEquals(Set.of(SpringMechanismInventory.ProblemReason.UNCLASSIFIED_MECHANISM),
                inventory.problems().stream().map(SpringMechanismInventory.Problem::reason)
                        .collect(Collectors.toSet()));
    }

    @Test
    void unresolvedBeanParameterTypeRemainsAClassifiedPointWithATypedGap() throws Exception {
        BinaryInput springApi = springApiJar();
        FrontendResult frontend = new JavaParserFrontend().analyze(TestInputs.request(
                Map.of("fixture/Config.java", """
                        package fixture;
                        class Config {
                          @org.springframework.context.annotation.Bean Object bean(java.util.List<Missing> dependency) { return null; }
                        }
                        """), List.of(springApi)));

        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                frontend, frameworkEvidence(springApi), List.of(), List.of()));

        assertEquals(1, inventory.obligations().stream().filter(value ->
                value.primaryMechanism().equals("spring.injection.bean-parameter")).count());
        assertTrue(inventory.problems().stream().anyMatch(problem ->
                problem.reason() == SpringMechanismInventory.ProblemReason.PARAMETER_EVIDENCE_INCOMPLETE
                        && problem.rawObservationIdentity().isPresent()));
    }

    @Test
    void nestedTypeDeclarationsDoNotChangeTheOuterTypeKind() throws Exception {
        BinaryInput springApi = springApiJar();
        FrontendResult frontend = new JavaParserFrontend().analyze(TestInputs.request(
                Map.of("fixture/Nested.java", """
                        package fixture;
                        @SuppressWarnings({"unused", "rawtypes"})
                        @org.springframework.stereotype.Component class OrderService {
                          @interface InternalMarker {}
                          OrderService(Service service) {}
                        }
                        @org.springframework.stereotype.Component interface Port {
                          class Helper {}
                        }
                        class Service {}
                        """), List.of(springApi)));
        assertEquals(FrontendResult.State.COMPLETED, frontend.state());

        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                frontend, frameworkEvidence(springApi), List.of(), List.of()));
        Entity orderService = declaration(frontend,
                JavaSymbolName.topLevelType("fixture", "OrderService").canonicalName());
        Entity port = declaration(frontend, JavaSymbolName.topLevelType("fixture", "Port").canonicalName());
        Entity marker = declaration(frontend, JavaSymbolName.memberType(
                JavaSymbolName.topLevelType("fixture", "OrderService"), "InternalMarker").canonicalName());

        assertEquals(1, inventory.obligations().stream().filter(value ->
                value.primaryMechanism().equals("spring.injection.constructor.implicit")).count());
        assertTrue(inventory.annotationDeclarations().stream().anyMatch(value ->
                value.declaration().equals(marker.identity())));
        assertFalse(inventory.annotationDeclarations().stream().anyMatch(value ->
                value.declaration().equals(orderService.identity()) || value.declaration().equals(port.identity())));
        assertFalse(inventory.problems().stream().anyMatch(problem ->
                problem.reason() == SpringMechanismInventory.ProblemReason.CONSTRUCTOR_SET_INCOMPLETE
                        && problem.subject().identity().equals(port.identity().value())));
    }

    @Test
    void concreteSpringContextAndBeanFactoryDeclarationsRemainContainerLookups() throws Exception {
        BinaryInput springApi = springApiJar();
        FrontendResult frontend = new JavaParserFrontend().analyze(TestInputs.request(
                Map.of("fixture/Lookups.java", """
                        package fixture;
                        class Lookups {
                          void lookup(org.springframework.context.support.AbstractApplicationContext context,
                              org.springframework.beans.factory.support.AbstractBeanFactory factory,
                              org.springframework.beans.factory.support.DefaultListableBeanFactory listable) {
                            context.getBean(Service.class);
                            factory.getBean(Service.class);
                            listable.getBeanNamesForType(Service.class);
                          }
                        }
                        class Service {}
                        """), List.of(springApi)));
        assertEquals(FrontendResult.State.COMPLETED, frontend.state());

        SpringMechanismInventory inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(
                frontend, frameworkEvidence(springApi), List.of(), List.of()));

        assertEquals(3, inventory.obligations().stream().filter(value ->
                value.primaryMechanism().equals("spring.lookup.container")).count());
    }

    private static Entity declaration(FrontendResult frontend, String canonicalName) {
        return frontend.declarations().stream().map(DeclarationRecord::entity)
                .filter(value -> value.canonicalName().equals(canonicalName)).findFirst().orElseThrow();
    }

    private BinaryInput springApiJar() throws Exception {
        Map<String, String> sources = Map.ofEntries(
                Map.entry("org/springframework/stereotype/Component.java", """
                        package org.springframework.stereotype;
                        public @interface Component { String value() default ""; }
                        """),
                Map.entry("org/springframework/core/annotation/AliasFor.java", """
                        package org.springframework.core.annotation;
                        public @interface AliasFor {
                          Class<? extends java.lang.annotation.Annotation> annotation()
                              default java.lang.annotation.Annotation.class;
                          String attribute() default "";
                        }
                        """),
                Map.entry("org/springframework/context/annotation/Bean.java", """
                        package org.springframework.context.annotation;
                        public @interface Bean {}
                        """),
                Map.entry("org/springframework/beans/factory/ObjectProvider.java", """
                        package org.springframework.beans.factory;
                        public interface ObjectProvider<T> { T getObject(); }
                        """),
                Map.entry("org/springframework/beans/factory/FactoryBean.java", """
                        package org.springframework.beans.factory;
                        public interface FactoryBean<T> {}
                        """),
                Map.entry("org/springframework/beans/factory/BeanFactory.java", """
                        package org.springframework.beans.factory;
                        public interface BeanFactory { Object getBean(Class<?> type); }
                        """),
                Map.entry("org/springframework/beans/factory/config/ConfigurableListableBeanFactory.java", """
                        package org.springframework.beans.factory.config;
                        public interface ConfigurableListableBeanFactory extends org.springframework.beans.factory.BeanFactory {}
                        """),
                Map.entry("org/springframework/beans/factory/support/AbstractBeanFactory.java", """
                        package org.springframework.beans.factory.support;
                        public abstract class AbstractBeanFactory implements org.springframework.beans.factory.config.ConfigurableListableBeanFactory {
                          public Object getBean(Class<?> type) { return null; }
                        }
                        """),
                Map.entry("org/springframework/beans/factory/support/DefaultListableBeanFactory.java", """
                        package org.springframework.beans.factory.support;
                        public class DefaultListableBeanFactory extends AbstractBeanFactory {
                          public String[] getBeanNamesForType(Class<?> type) { return null; }
                        }
                        """),
                Map.entry("org/springframework/context/ApplicationContext.java", """
                        package org.springframework.context;
                        public interface ApplicationContext extends org.springframework.beans.factory.BeanFactory {}
                        """),
                Map.entry("org/springframework/context/ConfigurableApplicationContext.java", """
                        package org.springframework.context;
                        public interface ConfigurableApplicationContext extends ApplicationContext {}
                        """),
                Map.entry("org/springframework/context/support/AbstractApplicationContext.java", """
                        package org.springframework.context.support;
                        public abstract class AbstractApplicationContext implements org.springframework.context.ConfigurableApplicationContext {
                          public Object getBean(Class<?> type) { return null; }
                        }
                        """),
                Map.entry("org/springframework/web/context/WebApplicationContext.java", """
                        package org.springframework.web.context;
                        public interface WebApplicationContext extends org.springframework.context.ApplicationContext {}
                        """),
                Map.entry("org/springframework/beans/factory/support/BeanDefinitionRegistry.java", """
                        package org.springframework.beans.factory.support;
                        public interface BeanDefinitionRegistry { void registerBeanDefinition(String name, Object definition); }
                        """),
                Map.entry("org/springframework/beans/factory/support/BeanDefinitionRegistryPostProcessor.java", """
                        package org.springframework.beans.factory.support;
                        public interface BeanDefinitionRegistryPostProcessor {}
                        """),
                Map.entry("org/springframework/data/repository/Repository.java", """
                        package org.springframework.data.repository;
                        public interface Repository<T, ID> {}
                        """),
                Map.entry("org/springframework/boot/ApplicationRunner.java", """
                        package org.springframework.boot;
                        public interface ApplicationRunner {}
                        """),
                Map.entry("lombok/RequiredArgsConstructor.java", """
                        package lombok;
                        public @interface RequiredArgsConstructor {}
                        """));
        Path classes = temporary.resolve("classes");
        Files.createDirectories(classes);
        ArrayList<String> compilerArguments = new ArrayList<>(List.of(
                "--release", "17", "-proc:none", "-encoding", "UTF-8", "-d", classes.toString()));
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path path = temporary.resolve("sources").resolve(entry.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, entry.getValue());
            compilerArguments.add(path.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                compilerArguments.toArray(String[]::new)));
        Path jar = temporary.resolve("spring-m4a2-api.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                JarEntry entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/'));
                entry.setTime(0);
                output.putNextEntry(entry);
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
        ContentDigest digest = ContentDigest.sha256(Files.readAllBytes(jar));
        return new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "test:spring-m4a2:1", digest), jar);
    }

    private static SpringFrameworkEvidence frameworkEvidence(BinaryInput springApi) {
        ArrayList<SpringFrameworkEvidence.Artifact> artifacts = new ArrayList<>(List.of(
                artifact("org.springframework:spring-aop:5.3.31", "3f0c666f317abaa845fc3a24fba219b1f469716bf309cccd755eecb8fee20430"),
                artifact("org.springframework:spring-beans:5.3.31", "a8d6d99003d0a28049cba4273afbcfc64e1107ee3c33f67935853e9711544aa7"),
                artifact("org.springframework:spring-context:5.3.31", "38def055d1e22b5514b1cb19cef4474e5c1b0d2127c483e7d014bde87c4a4cf3"),
                artifact("org.springframework:spring-core:5.3.31", "7013ed3da15a8d4be797f5c310f9aa1b196b97f2313bc41e60ef3f5627224fe9"),
                artifact("org.springframework:spring-expression:5.3.31", "e027f122b8a4e3030339068220bed02d1c9d397eb5897f1e33ba2f63b22591ac"),
                artifact("org.springframework:spring-jcl:5.3.31", "eee0df6a25a9c56d228ea86272546aa5a0656caf2f14e7b375417b066abbc0db"),
                artifact("org.springframework.boot:spring-boot:2.7.18", "530f4e0fdfeb3a0e2b3a369d15cdea38fbdc1696f8b030c35a6ad65c27524950"),
                artifact("org.springframework.boot:spring-boot-autoconfigure:2.7.18", "1c4e0aadcb662b6149b536a2cf288003ffefe81a6cc69846e9f14976529a1b08")));
        artifacts.add(new SpringFrameworkEvidence.Artifact("test:spring-m4a2:1",
                springApi.entry().logicalName(), springApi.entry().contentDigest()));
        return new SpringFrameworkEvidence(true,
                Optional.of(ContentDigest.sha256Utf8("m4a2-integration-manifest")), artifacts);
    }

    private static SpringFrameworkEvidence.Artifact artifact(String coordinate, String hex) {
        return new SpringFrameworkEvidence.Artifact(coordinate, new ContentDigest("sha256:" + hex));
    }
}
