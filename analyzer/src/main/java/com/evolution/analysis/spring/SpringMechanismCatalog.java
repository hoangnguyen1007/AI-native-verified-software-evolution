package com.evolution.analysis.spring;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.EvidenceRequirement;
import com.evolution.analysis.frontend.JavaSymbolName;
import java.util.*;
import java.util.stream.Collectors;

/** Accepted closed M4-R0 mechanism denominator. Targets are future semantic promises, not scan results. */
public final class SpringMechanismCatalog {
    public static final VersionedIdentifier CATALOG = new VersionedIdentifier("spring-mechanisms", "v2");
    public static final String REVISION = "r0-candidate-1";

    public enum Target { SUPPORTED, CONDITIONAL, DYNAMIC, UNSUPPORTED, OUT_OF_SCOPE }

    public record GapPolicy(String reasonCode, EvidenceRequirement.Kind requirementKind, String question) {
        public GapPolicy {
            reasonCode = ContractChecks.token(reasonCode, "Spring gap reason");
            ContractChecks.notNull(requirementKind, "Spring gap requirement");
            question = ContractChecks.namespacedId(question, "Spring gap question");
        }
    }

    public record Entry(String id, Target target, List<String> annotationTypes, List<String> sources,
            GapPolicy gap, String variants) implements Comparable<Entry> {
        public Entry {
            id = ContractChecks.namespacedId(id, "Spring mechanism ID");
            ContractChecks.notNull(target, "Spring mechanism target");
            annotationTypes = ContractChecks.sortedStrings(annotationTypes, "Spring annotation types");
            sources = ContractChecks.sortedStrings(sources, "Spring mechanism sources");
            ContractChecks.notNull(gap, "Spring gap policy");
            variants = ContractChecks.text(variants, "Spring mechanism variants");
        }

        @Override public int compareTo(Entry other) { return id.compareTo(other.id); }
    }

    private static final List<Entry> ENTRIES = createEntries();
    private static final Map<String, Entry> BY_ID = ENTRIES.stream()
            .collect(Collectors.toUnmodifiableMap(Entry::id, entry -> entry));
    private static final Map<String, List<Entry>> BY_ANNOTATION_KEY = annotationIndex();
    private static final Set<String> BOOT_ANNOTATION_KEYS = annotationKeysWithPrefix("org.springframework.boot.");
    private static final Set<String> NON_SPRING_MARKER_KEYS = markerKeys();

    private SpringMechanismCatalog() {}

    public static List<Entry> entries() { return ENTRIES; }

    public static Entry require(String id) {
        Entry entry = BY_ID.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown Spring mechanism: " + id);
        return entry;
    }

    static List<Entry> entriesForAnnotationKey(String canonicalTypeKey) {
        return BY_ANNOTATION_KEY.getOrDefault(canonicalTypeKey, List.of());
    }

    static boolean isNonSpringMarker(String canonicalTypeKey) {
        return NON_SPRING_MARKER_KEYS.contains(canonicalTypeKey);
    }

    static boolean isBootAnnotationKey(String canonicalTypeKey) {
        return BOOT_ANNOTATION_KEYS.contains(canonicalTypeKey);
    }

    private static Set<String> annotationKeysWithPrefix(String prefix) {
        return ENTRIES.stream().flatMap(entry -> entry.annotationTypes().stream())
                .filter(type -> type.startsWith(prefix)).map(SpringMechanismCatalog::annotationKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, List<Entry>> annotationIndex() {
        TreeMap<String, List<Entry>> index = new TreeMap<>();
        for (Entry entry : ENTRIES) {
            for (String type : entry.annotationTypes()) {
                String key = annotationKey(type);
                ArrayList<Entry> values = new ArrayList<>(index.getOrDefault(key, List.of()));
                values.add(entry);
                values.sort(Comparator.naturalOrder());
                index.put(key, List.copyOf(values));
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static Set<String> markerKeys() {
        return Set.of(
                annotationKey("java.lang.Deprecated"),
                annotationKey("java.lang.FunctionalInterface"),
                annotationKey("java.lang.Override"),
                annotationKey("java.lang.SafeVarargs"),
                annotationKey("java.lang.SuppressWarnings"),
                annotationKey("java.lang.annotation.Documented"),
                annotationKey("java.lang.annotation.Inherited"),
                annotationKey("java.lang.annotation.Repeatable"),
                annotationKey("java.lang.annotation.Retention"),
                annotationKey("java.lang.annotation.Target"));
    }

    private static String annotationKey(String canonicalType) {
        int separator = canonicalType.lastIndexOf('.');
        if (separator < 1 || separator == canonicalType.length() - 1) {
            throw new IllegalArgumentException("Annotation type must be fully qualified: " + canonicalType);
        }
        return JavaSymbolName.topLevelType(
                canonicalType.substring(0, separator), canonicalType.substring(separator + 1)).canonicalName();
    }

    private static List<Entry> createEntries() {
        List<Entry> entries = List.of(
                entry("spring.discovery.component-scan", Target.CONDITIONAL,
                        annotations("org.springframework.context.annotation.ComponentScan",
                                "org.springframework.context.annotation.ComponentScans"), "S12",
                        "SCAN_INPUT_INCOMPLETE", EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                        "scan roots, nested configurations, include/exclude regex/assignable/annotation filters, resource/index selection"),
                entry("spring.bean.stereotype.direct", Target.SUPPORTED,
                        annotations("org.springframework.stereotype.Component", "org.springframework.stereotype.Service",
                                "org.springframework.stereotype.Repository", "org.springframework.stereotype.Controller",
                                "org.springframework.web.bind.annotation.RestController", "javax.inject.Named",
                                "jakarta.inject.Named"), "S02", "REGISTRATION_ELIGIBILITY_UNKNOWN",
                        EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                        "scan/import eligibility, custom bean names and duplicate names"),
                entry("spring.bean.stereotype.composed", Target.CONDITIONAL,
                        annotations("org.springframework.core.annotation.AliasFor", "org.springframework.stereotype.Indexed"),
                        "S21", "ANNOTATION_GRAPH_INCOMPLETE", EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                        "meta-annotations, aliases/defaults, repeatable containers, inheritance and cycles"),
                entry("spring.bean.factory-method", Target.CONDITIONAL,
                        annotations("org.springframework.context.annotation.Configuration",
                                "org.springframework.context.annotation.Bean"), "S13", "PRODUCER_SHAPE_UNSUPPORTED",
                        EvidenceRequirement.Kind.BYTECODE,
                        "full/lite configuration, static/instance factory methods, overrides, proxyBeanMethods, scopes"),
                entry("spring.injection.constructor.explicit", Target.SUPPORTED, injectionAnnotations(), "S04",
                        "INJECTION_TYPE_UNRESOLVED", EvidenceRequirement.Kind.EXACT_CLASSPATH,
                        "required/optional constructors, greedy overload selection"),
                entry("spring.injection.constructor.implicit", Target.SUPPORTED, List.of(), "S04",
                        "CONSTRUCTOR_SET_INCOMPLETE", EvidenceRequirement.Kind.BYTECODE,
                        "single constructor from 4.3; multiple/default constructors"),
                entry("spring.injection.constructor.generated", Target.CONDITIONAL,
                        annotations("lombok.RequiredArgsConstructor", "lombok.AllArgsConstructor", "lombok.Data"), "S36",
                        "GENERATED_MEMBER_NOT_ACQUIRED", EvidenceRequirement.Kind.GENERATED_SOURCE,
                        "Lombok version/config, initialized/non-null/static fields, constructor conflicts"),
                entry("spring.injection.field", Target.SUPPORTED, injectionAnnotations(), "S15",
                        "INJECTION_TYPE_UNRESOLVED", EvidenceRequirement.Kind.EXACT_CLASSPATH,
                        "Autowired/Inject field occurrence; static/final/inherited rules"),
                entry("spring.injection.method", Target.SUPPORTED, injectionAnnotations(), "S15",
                        "METHOD_INJECTION_UNMODELED", EvidenceRequirement.Kind.BYTECODE,
                        "setter/arbitrary methods, overrides, bridges, optional all-parameter invocation"),
                entry("spring.injection.bean-parameter", Target.SUPPORTED, List.of(), "S13",
                        "PARAMETER_EVIDENCE_INCOMPLETE", EvidenceRequirement.Kind.BYTECODE,
                        "parameter name metadata, qualifiers and factory owner"),
                entry("spring.injection.resource", Target.CONDITIONAL,
                        annotations("javax.annotation.Resource", "javax.annotation.Resources",
                                "jakarta.annotation.Resource", "jakarta.annotation.Resources"), "S18",
                        "RESOURCE_BINDING_UNMODELED", EvidenceRequirement.Kind.CONFIGURATION,
                        "javax/jakarta, name-first, default-name type fallback, explicit name and JNDI"),
                entry("spring.disambiguation.qualifier", Target.SUPPORTED,
                        annotations("org.springframework.beans.factory.annotation.Qualifier"), "S15",
                        "QUALIFIER_METADATA_INCOMPLETE", EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                        "qualifier attributes, meta-qualifiers and bean-name matching within type candidates"),
                entry("spring.disambiguation.priority", Target.CONDITIONAL,
                        annotations("org.springframework.context.annotation.Primary", "org.springframework.context.annotation.Fallback",
                                "javax.annotation.Priority", "jakarta.annotation.Priority",
                                "org.springframework.core.annotation.Order"), "S15", "ORDER_OR_SELECTION_UNKNOWN",
                        EvidenceRequirement.Kind.BYTECODE,
                        "priority/name version delta, primary conflicts, fallback 6.2 and defaultCandidate"),
                entry("spring.injection.aggregate", Target.CONDITIONAL,
                        annotations("org.springframework.context.annotation.Lazy", "org.springframework.lang.Nullable",
                                "org.jspecify.annotations.Nullable"), "S15", "AGGREGATE_OR_PROVIDER_UNMODELED",
                        EvidenceRequirement.Kind.BYTECODE,
                        "arrays/List/Set/Map, Optional/ObjectProvider/Provider, lazy/self/resolvable dependencies"),
                entry("spring.condition.profile", Target.CONDITIONAL,
                        annotations("org.springframework.context.annotation.Profile"), "S20", "PROFILE_ENVELOPE_INCOMPLETE",
                        EvidenceRequirement.Kind.CONFIGURATION,
                        "default/active profiles, OR annotation arrays, expression grammar, groups/include activation"),
                entry("spring.condition.property", Target.CONDITIONAL,
                        annotations("org.springframework.boot.autoconfigure.condition.ConditionalOnProperty"), "S22",
                        "PROPERTY_DOMAIN_INCOMPLETE", EvidenceRequirement.Kind.CONFIGURATION,
                        "all names, prefix, matchIfMissing, case-insensitive havingValue, absent vs empty"),
                entry("spring.condition.build-context", Target.CONDITIONAL,
                        annotations("org.springframework.boot.autoconfigure.condition.ConditionalOnClass",
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass",
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnResource",
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnJava",
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication",
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication",
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnWarDeployment",
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnNotWarDeployment",
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnCloudPlatform",
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnJndi"), "S30",
                        "BUILD_OR_ENVIRONMENT_CONSTANT_UNKNOWN", EvidenceRequirement.Kind.EXACT_CLASSPATH,
                        "class loading/linkage vs metadata, resources, Java version, servlet/reactive/war/cloud/JNDI predicates"),
                entry("spring.condition.bean-state", Target.CONDITIONAL,
                        annotations("org.springframework.boot.autoconfigure.condition.ConditionalOnBean",
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean",
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate"), "S23",
                        "BEAN_STATE_INCOMPLETE", EvidenceRequirement.Kind.CONFIGURATION,
                        "types/names/annotations/ignored/parameterized containers, CURRENT/ANCESTORS/ALL and candidate flags"),
                entry("spring.condition.custom-expression", Target.DYNAMIC,
                        annotations("org.springframework.context.annotation.Conditional",
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnExpression"), "S11",
                        "OPAQUE_CONDITION", EvidenceRequirement.Kind.RUNTIME_OBSERVATION,
                        "custom Condition/ConfigurationCondition, nested all/any/none conditions and opaque SpEL"),
                entry("spring.registration.auto-configuration", Target.CONDITIONAL,
                        annotations("org.springframework.boot.autoconfigure.EnableAutoConfiguration",
                                "org.springframework.boot.autoconfigure.SpringBootApplication",
                                "org.springframework.boot.SpringBootConfiguration",
                                "org.springframework.boot.autoconfigure.AutoConfiguration",
                                "org.springframework.boot.autoconfigure.AutoConfigureBefore",
                                "org.springframework.boot.autoconfigure.AutoConfigureAfter",
                                "org.springframework.boot.autoconfigure.AutoConfigureOrder",
                                "org.springframework.boot.autoconfigure.ImportAutoConfiguration",
                                "org.springframework.context.annotation.Import",
                                "org.springframework.context.annotation.ImportResource"), "S24",
                        "AUTO_CONFIGURATION_METADATA_INCOMPLETE", EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                        "spring.factories/imports, duplicate removal, exclusions, filters, selectors/deferred groups and ordering"),
                entry("spring.registration.spring-data", Target.CONDITIONAL,
                        annotations("org.springframework.data.repository.NoRepositoryBean",
                                "org.springframework.data.repository.RepositoryDefinition",
                                "org.springframework.data.jpa.repository.config.EnableJpaRepositories",
                                "org.springframework.data.mongodb.repository.config.EnableMongoRepositories"), "S37",
                        "REPOSITORY_REGISTRATION_UNPROVED", EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                        "store-specific enablement, strict multi-store binding, base packages, fragments/factory/proxy"),
                entry("spring.registration.factory", Target.CONDITIONAL, List.of(), "S01",
                        "FACTORY_PRODUCT_TYPE_UNKNOWN", EvidenceRequirement.Kind.BYTECODE,
                        "FactoryBean versus &factory, static/instance factory, null/unknown product type"),
                entry("spring.registration.xml", Target.CONDITIONAL, List.of(), "S01", "XML_SEMANTICS_UNMODELED",
                        EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                        "1.x DTD and 2.x XSD, bean/ref/idref/alias/import/parent/abstract/autowire/inner/collections/lookup/replaced-method, custom namespaces"),
                entry("spring.registration.programmatic", Target.DYNAMIC, List.of(), "S14",
                        "DYNAMIC_REGISTRY_MUTATION", EvidenceRequirement.Kind.RUNTIME_OBSERVATION,
                        "registerBean/Definition/Singleton, suppliers, initializers, registry/factory post-processors, registrars"),
                entry("spring.lookup.container", Target.DYNAMIC,
                        annotations("org.springframework.beans.factory.annotation.Lookup"), "S01",
                        "DYNAMIC_LOOKUP_TARGET", EvidenceRequirement.Kind.RUNTIME_OBSERVATION,
                        "getBean/provider/service locator, method injection, lookup/replaced-method"),
                entry("spring.expression.value", Target.DYNAMIC,
                        annotations("org.springframework.beans.factory.annotation.Value",
                                "org.springframework.context.annotation.PropertySource",
                                "org.springframework.context.annotation.PropertySources",
                                "org.springframework.boot.context.properties.ConfigurationProperties",
                                "org.springframework.boot.context.properties.EnableConfigurationProperties",
                                "org.springframework.boot.context.properties.ConfigurationPropertiesScan",
                                "org.springframework.boot.context.properties.ConstructorBinding"), "S09",
                        "CONFIGURATION_EXPRESSION_UNMODELED", EvidenceRequirement.Kind.CONFIGURATION,
                        "placeholder/SpEL binding and configuration properties, conversions, external/config-tree imports"),
                entry("spring.runtime.proxy-aop", Target.DYNAMIC,
                        annotations("org.springframework.context.annotation.Scope",
                                "org.springframework.aop.scope.annotation.ScopedProxy",
                                "org.springframework.context.annotation.DependsOn",
                                "org.springframework.context.annotation.EnableAspectJAutoProxy",
                                "org.springframework.transaction.annotation.EnableTransactionManagement",
                                "org.springframework.transaction.annotation.Transactional",
                                "org.springframework.scheduling.annotation.EnableAsync",
                                "org.springframework.scheduling.annotation.Async",
                                "org.springframework.cache.annotation.EnableCaching",
                                "org.springframework.cache.annotation.Cacheable",
                                "org.springframework.cache.annotation.CachePut",
                                "org.springframework.cache.annotation.CacheEvict",
                                "org.springframework.cache.annotation.Caching",
                                "org.springframework.cache.annotation.CacheConfig"), "S14",
                        "PROXY_OR_INTERCEPTOR_UNMODELED", EvidenceRequirement.Kind.RUNTIME_OBSERVATION,
                        "AOP advisors, JDK/CGLIB/scoped proxies, self-invocation, transactions, security, cache and async"),
                entry("spring.entrypoint.framework", Target.CONDITIONAL,
                        annotations("org.springframework.web.bind.annotation.RequestMapping",
                                "org.springframework.web.bind.annotation.GetMapping",
                                "org.springframework.web.bind.annotation.PostMapping",
                                "org.springframework.web.bind.annotation.PutMapping",
                                "org.springframework.web.bind.annotation.DeleteMapping",
                                "org.springframework.web.bind.annotation.PatchMapping",
                                "org.springframework.messaging.handler.annotation.MessageMapping",
                                "org.springframework.jms.annotation.JmsListener",
                                "org.springframework.kafka.annotation.KafkaListener",
                                "org.springframework.amqp.rabbit.annotation.RabbitListener",
                                "org.springframework.context.event.EventListener",
                                "org.springframework.transaction.event.TransactionalEventListener",
                                "org.springframework.scheduling.annotation.Scheduled",
                                "org.springframework.scheduling.annotation.Schedules",
                                "org.springframework.scheduling.annotation.EnableScheduling",
                                "javax.annotation.PostConstruct", "javax.annotation.PreDestroy",
                                "jakarta.annotation.PostConstruct", "jakarta.annotation.PreDestroy"), "S19",
                        "ENTRYPOINT_OR_LIFECYCLE_UNMODELED", EvidenceRequirement.Kind.RUNTIME_OBSERVATION,
                        "MVC/WebFlux functional routes, messaging/listeners, lifecycle/SmartLifecycle, test bootstrap and synthesized infrastructure"),
                entry("spring.mechanism.unclassified", Target.UNSUPPORTED, List.of(), "S21",
                        "UNCLASSIFIED_MECHANISM", EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                        "every unrecognized annotation/use, XML namespace, metadata entry or extension SPI; never silently dropped"));
        return ContractChecks.sortedDistinct(entries, Comparator.naturalOrder(), "Spring mechanism catalog");
    }

    private static Entry entry(String id, Target target, List<String> annotations, String source,
            String reason, EvidenceRequirement.Kind requirement, String variants) {
        return new Entry(id, target, annotations, List.of(source),
                new GapPolicy(reason, requirement, "spring.research.establish-mechanism-semantics"), variants);
    }

    private static List<String> injectionAnnotations() {
        return annotations("org.springframework.beans.factory.annotation.Autowired",
                "javax.inject.Inject", "jakarta.inject.Inject");
    }

    private static List<String> annotations(String... values) { return List.of(values); }
}
