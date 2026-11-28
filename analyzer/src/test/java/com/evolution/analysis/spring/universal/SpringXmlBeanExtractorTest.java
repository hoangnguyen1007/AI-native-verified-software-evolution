package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.contract.source.SourceDocument;
import com.evolution.analysis.frontend.SourceInput;
import com.evolution.analysis.ingestion.RepositoryInputs;
import com.evolution.analysis.spring.condition.ConfigDataIngestion;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpringXmlBeanExtractorTest {

    private static final SnapshotIdentity SNAPSHOT =
            new SnapshotIdentity("snapshot:sha256:0000000000000000000000000000000000000000000000000000000000000001");
    private static final RepositoryIdentity REPO =
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/spring-xml-fixture.git");
    private static final ModuleDescriptor MODULE =
            ModuleDescriptor.create(REPO, ".", "app");

    @Test
    void extractsSpringXmlBeansPropertiesConstructorArgsImportsAuthProvidersAndPinnedContractFields() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:security="http://www.springframework.org/schema/security"
                       xmlns:p="http://www.springframework.org/schema/p">
                    <import resource="classpath:datasource-context.xml"/>
                    <alias name="orderService" alias="primaryOrderService"/>
                    <bean id="orderRepository" class="com.acme.repo.JdbcOrderRepository"
                          scope="singleton" init-method="openPool" destroy-method="closePool">
                        <qualifier value="jdbcRepo"/>
                    </bean>
                    <bean id="orderService" class="com.acme.service.OrderServiceImpl"
                          scope="prototype" init-method="init" destroy-method="cleanup"
                          p:auditEnabled="true">
                        <constructor-arg index="0" type="com.acme.repo.OrderRepository" ref="orderRepository"/>
                        <constructor-arg index="1" name="region" value="us-east"/>
                        <property name="retryLimit" value="3"/>
                        <property name="notifier"><ref bean="alertNotifier"/></property>
                        <property name="label"><value>checkout-flow</value></property>
                    </bean>
                    <security:authentication-manager>
                        <security:authentication-provider ref="customAuthProvider"/>
                    </security:authentication-manager>
                </beans>
                """;

        var result = SpringXmlBeanExtractor.extractFromXml(
                SNAPSHOT, "src/main/resources/applicationContext.xml", xml,
                SpringXmlBeanExtractor.Limits.defaults());
        var replay = SpringXmlBeanExtractor.extractFromXml(
                SNAPSHOT, "src/main/resources/applicationContext.xml", xml,
                SpringXmlBeanExtractor.Limits.defaults());

        assertEquals(result.identity(), replay.identity(), "I2: identical XML must yield bit-identical SHA-256 digest");
        assertTrue(result.gaps().isEmpty(), () -> "Unexpected XML gaps: " + result.gaps());
        assertEquals(2, result.beans().size());
        assertEquals(1, result.imports().size());
        assertEquals("classpath:datasource-context.xml", result.imports().getFirst().resource());
        assertEquals(1, result.authProviders().size());
        assertEquals("customAuthProvider", result.authProviders().getFirst().ref());

        var orderSvc = result.beans().stream()
                .filter(b -> b.beanId().equals("orderService"))
                .findFirst().orElseThrow();
        assertEquals("com.acme.service.OrderServiceImpl", orderSvc.className());
        assertEquals("prototype", orderSvc.scope());
        assertEquals("init", orderSvc.initMethod());
        assertEquals("cleanup", orderSvc.destroyMethod());
        assertEquals(List.of("primaryOrderService"), orderSvc.aliases());
        assertEquals(TrustTier.SOURCE_SYNTAX, orderSvc.trustTier());
        assertEquals(SpringXmlBeanExtractor.EVIDENCE_FAMILY_XML_BEAN, orderSvc.evidenceFamily());
        assertEquals(2, orderSvc.constructorArgs().size());
        assertEquals("orderRepository", orderSvc.constructorArgs().get(0).ref());
        assertEquals("us-east", orderSvc.constructorArgs().get(1).value());
        assertTrue(orderSvc.properties().stream().anyMatch(p -> p.name().equals("notifier") && p.ref().equals("alertNotifier")));
        assertTrue(orderSvc.properties().stream().anyMatch(p -> p.name().equals("retryLimit") && p.value().equals("3")));
        assertTrue(orderSvc.properties().stream().anyMatch(p -> p.name().equals("auditEnabled") && p.value().equals("true")));
        assertTrue(orderSvc.properties().stream().anyMatch(p -> p.name().equals("label") && p.value().equals("checkout-flow")));

        // Verify pinned shared evidence contract fields for WP2.2 interoperability
        Map<String, Object> payload = orderSvc.toEvidencePayload();
        for (String requiredKey : List.of("beanId", "className", "scope", "initMethod", "destroyMethod",
                "properties", "constructorArgs", "imports", "authProviders", "sourceFile", "line")) {
            assertTrue(payload.containsKey(requiredKey), "Missing pinned spring.xml-bean field: " + requiredKey);
        }
        assertEquals(List.of("classpath:datasource-context.xml"), payload.get("imports"));
        assertEquals(List.of("customAuthProvider"), payload.get("authProviders"));
    }

    @Test
    void rejectsXxeAndDtdPayloadsFailClosedWithoutExternalEntityResolution() {
        String xxePayload = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE beans [
                  <!ENTITY secret SYSTEM "file:///etc/passwd">
                ]>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="exfil" class="com.acme.ExfilBean">
                        <property name="data" value="&secret;"/>
                    </bean>
                </beans>
                """;
        var xxeResult = SpringXmlBeanExtractor.extractFromXml(
                SNAPSHOT, "src/main/resources/xxe-context.xml", xxePayload,
                SpringXmlBeanExtractor.Limits.defaults());

        assertTrue(xxeResult.beans().isEmpty(), "I1: XXE/DTD payload must never produce extracted beans");
        assertEquals(1, xxeResult.gaps().size());
        assertEquals(SpringXmlBeanExtractor.GAP_XML_PARSE_FAILED, xxeResult.gaps().getFirst().reasonCode());

        String billionLaughs = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE lolz [
                  <!ENTITY a "aaaaaaaaaa">
                  <!ENTITY b "&a;&a;&a;&a;&a;">
                ]>
                <beans><bean id="x" class="com.acme.X"><property name="v" value="&b;"/></bean></beans>
                """;
        var laughsResult = SpringXmlBeanExtractor.extractFromXml(
                SNAPSHOT, "src/main/resources/entity-bomb.xml", billionLaughs,
                SpringXmlBeanExtractor.Limits.defaults());
        assertTrue(laughsResult.beans().isEmpty());
        assertFalse(laughsResult.gaps().isEmpty());
    }

    @Test
    void configDataIngestionPassivelyExtractsSpringXmlAndSkipsNonSpringXml() {
        var files = new TreeMap<String, SourceInput>();
        addFile(files, "pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>");
        addFile(files, "src/main/resources/application.properties", "app.mode=standard\n");
        addFile(files, "src/main/resources/spring-beans.xml", """
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="billingClient" class="com.acme.BillingClient" scope="request"/>
                </beans>
                """);
        var docs = files.values().stream().map(SourceInput::document).toList();
        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false,
                docs.stream().map(SnapshotFile::from).toList(), docs);
        var inputs = new RepositoryInputs(snapshot, files);

        var configResult = new ConfigDataIngestion().ingest(inputs, ConfigDataIngestion.Policy.defaults());
        assertTrue(configResult.gaps().isEmpty(), () -> "Non-Spring pom.xml must not cause XML gaps: " + configResult.gaps());
        assertEquals(1, configResult.xmlBeans().beans().size());
        assertEquals("billingClient", configResult.xmlBeans().beans().getFirst().beanId());
        assertEquals("request", configResult.xmlBeans().beans().getFirst().scope());
        assertEquals(List.of("src/main/resources/spring-beans.xml"), ConfigDataIngestion.discoverSpringXmlPaths(inputs));
    }

    @Test
    void dynamicLookupGapMatchesPinnedReasonCodeAndBeanLookupPayloadContract() {
        var edge = new SpringXmlBeanExtractor.BeanLookupEdge(
                "com.acme.Controller#handle", "orderService", "com.acme.OrderService",
                false, "orderService", "src/main/java/com/acme/Controller.java", 42, TrustTier.SOURCE_SYNTAX);
        assertEquals("RESOLVES_BEAN", edge.edgeType());
        assertEquals("spring.bean-lookup", edge.evidenceFamily());
        var payload = edge.toEvidencePayload();
        assertEquals("orderService", payload.get("beanName"));
        assertEquals("com.acme.OrderService", payload.get("beanType"));
        assertEquals(false, payload.get("dynamic"));
        assertEquals("src/main/java/com/acme/Controller.java", payload.get("sourceFile"));
        assertEquals(42, payload.get("line"));

        var gap = SpringXmlBeanExtractor.createDynamicLookupGap(
                SNAPSHOT, "src/main/java/com/acme/Controller.java", 55, "ctx.getBean(beanKey)");
        assertEquals("DYNAMIC_BEAN_LOOKUP_UNRESOLVED", gap.reasonCode());
    }

    @Test
    void collectionContainerSummaryModelsMapCollectionAndSpringCacheSoundModuloAnalysis() {
        var mapPut = CollectionContainerSummary.lookup("java.util.HashMap", "put", 2).orElseThrow();
        assertEquals(CollectionContainerSummary.FlowDirection.PUT_TO_CONTENTS, mapPut.direction());
        assertEquals("contents", mapPut.abstractField());
        assertEquals(1, mapPut.valueParamIndex());
        assertTrue(mapPut.collapsesInternalTreeNode(), "JackEE Sec. 4: HashMap collapses TreeNode");

        var chmGet = CollectionContainerSummary.lookup("java.util.concurrent.ConcurrentHashMap<String,Order>", "get", 1)
                .orElseThrow();
        assertEquals(CollectionContainerSummary.FlowDirection.GET_FROM_CONTENTS, chmGet.direction());

        var listAdd = CollectionContainerSummary.lookup("java.util.ArrayList", "add", 1).orElseThrow();
        assertEquals(CollectionContainerSummary.FlowDirection.PUT_TO_CONTENTS, listAdd.direction());

        var cacheable = CollectionContainerSummary.lookupAnnotation("org.springframework.cache.annotation.Cacheable");
        assertEquals(2, cacheable.size(), "@Cacheable models both cache-hit GET and cache-miss PUT on contents");
        assertTrue(cacheable.stream().anyMatch(e -> e.direction() == CollectionContainerSummary.FlowDirection.GET_FROM_CONTENTS));
        assertTrue(cacheable.stream().anyMatch(e -> e.direction() == CollectionContainerSummary.FlowDirection.PUT_TO_CONTENTS));

        var flowEdge = CollectionContainerSummary.resolveFlowEdge("orderCache", "java.util.LinkedHashMap", "put", 2)
                .orElseThrow();
        assertEquals("orderCache.contents", flowEdge.abstractSlot());
        assertEquals(CollectionContainerSummary.FlowDirection.PUT_TO_CONTENTS, flowEdge.direction());
    }

    private static void addFile(Map<String, SourceInput> files, String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        var doc = SourceDocument.create(REPO, MODULE, path, ContentDigest.sha256(bytes), SourceClassification.OTHER);
        files.put(path, new SourceInput(doc, bytes));
    }
}
