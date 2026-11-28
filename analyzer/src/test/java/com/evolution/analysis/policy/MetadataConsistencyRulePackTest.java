package com.evolution.analysis.policy;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.policy.MetadataConsistencyRulePack.*;
import static com.evolution.analysis.policy.MetadataConsistencyRulePack.*;
import com.evolution.analysis.query.ArchitectureQuery.Classification;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.analysis.spring.condition.FiniteDomain;
import com.evolution.analysis.spring.truth.SymbolicConfiguration;
import com.evolution.analysis.spring.truth.SymbolicTruthRegions;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WP2.2 MeCheck/RSL cross-artifact metadata consistency suite covering all 6 modern Spring Boot 2/3/4
 * and XML rules, offline absent-JAR negative controls, SAT counterexample witnesses, relaxed binding,
 * bounded declarative RSL rules, and memoized lazy query cache bit-identical parity.
 */
class MetadataConsistencyRulePackTest {

    // ─── Lightweight Graph Builder Helper ───

    private static final class GraphFixture {
        final RepositoryIdentity repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/wp22-meta.git");
        final SnapshotIdentity snapshot = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("wp22-snap"));
        final String context = id("ctx");
        final GraphEvidence ctxEvidence = GraphEvidence.capture("analysis.manifest", Map.of("schema", "test-ctx"));
        final List<KnowledgeGraph.Node> nodes = new ArrayList<>();
        final List<KnowledgeGraph.Edge> edges = new ArrayList<>();
        final List<GraphEvidence> evidence = new ArrayList<>(List.of(ctxEvidence));

        static String id(String seed) {
            return ContentDigest.sha256Utf8("wp22:" + seed).value();
        }

        GraphFixture addDocument(String relativePath) {
            var ev = GraphEvidence.capture("source.document", Map.of("identity", id("doc:" + relativePath), "path", relativePath));
            evidence.add(ev);
            nodes.add(new KnowledgeGraph.Node(id("doc:" + relativePath), context, "SOURCE_DOCUMENT", List.of(ev.identity())));
            return this;
        }

        GraphFixture addEvidenceNode(String seed, String kind, String family, Map<String, Object> payload) {
            var ev = GraphEvidence.capture(family, payload);
            evidence.add(ev);
            nodes.add(new KnowledgeGraph.Node(id(seed), context, kind, List.of(ev.identity())));
            return this;
        }

        GraphFixture addEvidenceOnly(String family, Map<String, Object> payload) {
            evidence.add(GraphEvidence.capture(family, payload));
            return this;
        }

        String addJavaClass(String fqcn, String typeKind, boolean abstractType, Set<String> superTypes, List<String> annotations) {
            String typeId = id("type:" + fqcn);
            var declMap = new LinkedHashMap<String, Object>();
            declMap.put("entity", Map.of("canonicalName", fqcn, "origin", "PROJECT", "stableScope", id("mod")));
            declMap.put("spelling", fqcn.substring(fqcn.lastIndexOf('.') + 1));
            declMap.put("typeKind", typeKind);
            declMap.put("abstractType", abstractType);
            if (!superTypes.isEmpty()) declMap.put("superTypes", List.copyOf(superTypes));
            var declEv = GraphEvidence.capture("java.declaration", declMap);
            evidence.add(declEv);
            nodes.add(new KnowledgeGraph.Node(typeId, context, "TYPE", List.of(declEv.identity())));
            for (int i = 0; i < annotations.size(); i++) {
                addAnnotation(typeId, fqcn + "@ann" + i, annotations.get(i), fqcn.replace('.', '/') + ".java", 10 + i);
            }
            return typeId;
        }

        String addJavaMethod(String ownerTypeId, String ownerFqcn, String name, List<String> paramTypes,
                String returnType, List<String> annotations) {
            String methodId = id("method:" + ownerFqcn + "#" + name + "(" + String.join(",", paramTypes) + ")");
            var declMap = new LinkedHashMap<String, Object>();
            declMap.put("entity", Map.of("canonicalName", ownerFqcn + "." + name + "(" + String.join(",", paramTypes) + ")",
                    "origin", "PROJECT", "stableScope", id("mod")));
            declMap.put("spelling", name);
            declMap.put("paramCount", paramTypes.size());
            declMap.put("paramTypes", paramTypes);
            declMap.put("returnType", returnType);
            var declEv = GraphEvidence.capture("java.declaration", declMap);
            evidence.add(declEv);
            nodes.add(new KnowledgeGraph.Node(methodId, context, "METHOD", List.of(declEv.identity())));
            var edgeEv = GraphEvidence.capture("java.declares", Map.of("owner", ownerTypeId, "member", methodId));
            evidence.add(edgeEv);
            edges.add(new KnowledgeGraph.Edge(id("decl:" + ownerTypeId + ":" + methodId), context, "graph.declares",
                    ownerTypeId, new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(methodId), Optional.empty()),
                    List.of(edgeEv.identity()), Optional.empty()));
            for (int i = 0; i < annotations.size(); i++) {
                addAnnotation(methodId, methodId + "@ann" + i, annotations.get(i), ownerFqcn.replace('.', '/') + ".java", 20 + i);
            }
            return methodId;
        }

        String addJavaField(String ownerTypeId, String ownerFqcn, String name, String fieldType, List<String> annotations) {
            String fieldId = id("field:" + ownerFqcn + "#" + name);
            var declMap = new LinkedHashMap<String, Object>();
            declMap.put("entity", Map.of("canonicalName", ownerFqcn + "." + name, "origin", "PROJECT", "stableScope", id("mod")));
            declMap.put("spelling", name);
            declMap.put("fieldType", fieldType);
            var declEv = GraphEvidence.capture("java.declaration", declMap);
            evidence.add(declEv);
            nodes.add(new KnowledgeGraph.Node(fieldId, context, "FIELD", List.of(declEv.identity())));
            var edgeEv = GraphEvidence.capture("java.declares", Map.of("owner", ownerTypeId, "member", fieldId));
            evidence.add(edgeEv);
            edges.add(new KnowledgeGraph.Edge(id("decl:" + ownerTypeId + ":" + fieldId), context, "graph.declares",
                    ownerTypeId, new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(fieldId), Optional.empty()),
                    List.of(edgeEv.identity()), Optional.empty()));
            for (int i = 0; i < annotations.size(); i++) {
                addAnnotation(fieldId, fieldId + "@ann" + i, annotations.get(i), ownerFqcn.replace('.', '/') + ".java", 30 + i);
            }
            return fieldId;
        }

        void addAnnotation(String ownerId, String seed, String spelling, String file, int line) {
            String docId = id("doc:" + file);
            if (nodes.stream().noneMatch(n -> n.identity().equals(docId))) {
                addDocument(file);
            }
            var annEv = GraphEvidence.capture("java.annotation-use", Map.of(
                    "canonicalName", "java:v1:[\"annotation-use\",\"" + seed + "\"]",
                    "owner", ownerId,
                    "spelling", spelling,
                    "span", Map.of("document", docId, "startLine", line, "startColumn", 1, "endLine", line, "endColumn", 20)));
            evidence.add(annEv);
            String annNodeId = id("ann:" + seed);
            nodes.add(new KnowledgeGraph.Node(annNodeId, context, "ANNOTATION_USE", List.of(annEv.identity())));
            edges.add(new KnowledgeGraph.Edge(id("has-ann:" + ownerId + ":" + annNodeId), context, "graph.has-annotation-use",
                    ownerId, new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(annNodeId), Optional.empty()),
                    List.of(annEv.identity()), Optional.empty()));
        }

        KnowledgeGraph build() {
            return new KnowledgeGraph(snapshot,
                    List.of(new KnowledgeGraph.Context(context, snapshot, Optional.empty(), ctxEvidence.identity())),
                    nodes, edges, evidence);
        }
    }

    // ─── Rule 1: spring-xml-bean-class-and-props + Offline LIBRARY_JAR_ABSENT negative control ───

    @Test
    void rule1ValidatesXmlBeanClassConstructorPropsImportsAndEmitsLibraryJarAbsentNotFpWhenOffline() {
        var f = new GraphFixture();
        f.addDocument("src/main/resources/applicationContext.xml");
        f.addDocument("src/main/resources/included-beans.xml");

        // Project class with 1-arg constructor and setTimeout setter
        String orderSvc = f.addJavaClass("com.acme.OrderProcessor", "CLASS", false, Set.of(), List.of(
                "@ImportResource(\"classpath:included-beans.xml\")",
                "@ImportResource(\"classpath:missing-extra.xml\")"));
        f.addJavaMethod(orderSvc, "com.acme.OrderProcessor", "<init>", List.of("java.lang.String"), "void", List.of());
        f.addJavaMethod(orderSvc, "com.acme.OrderProcessor", "setTimeout", List.of("int"), "void", List.of());

        // Abstract class trap
        f.addJavaClass("com.acme.AbstractGateway", "CLASS", true, Set.of(), List.of());

        // 1. Valid XML bean
        f.addEvidenceOnly(FAMILY_XML_BEAN, Map.of(
                "beanId", "validOrderProcessor",
                "className", "com.acme.OrderProcessor",
                "scope", "singleton",
                "constructorArgs", List.of(Map.of("index", 0, "type", "String", "value", "default")),
                "properties", List.of(Map.of("name", "timeout", "value", "30")),
                "imports", List.of("classpath:included-beans.xml"),
                "sourceFile", "src/main/resources/applicationContext.xml",
                "line", 10));

        // 2. Broken XML bean: wrong constructor arg count + missing setter
        f.addEvidenceOnly(FAMILY_XML_BEAN, Map.of(
                "beanId", "brokenOrderProcessor",
                "className", "com.acme.OrderProcessor",
                "constructorArgs", List.of(Map.of("value", "a"), Map.of("value", "b")),
                "properties", List.of(Map.of("name", "nonExistentFlag", "value", "true")),
                "sourceFile", "src/main/resources/applicationContext.xml",
                "line", 22));

        // 3. Non-existent class bean
        f.addEvidenceOnly(FAMILY_XML_BEAN, Map.of(
                "beanId", "ghostBean",
                "className", "com.acme.DoesNotExist",
                "sourceFile", "src/main/resources/applicationContext.xml",
                "line", 35));

        // 4. Abstract class bean without abstract=true
        f.addEvidenceOnly(FAMILY_XML_BEAN, Map.of(
                "beanId", "abstractBean",
                "className", "com.acme.AbstractGateway",
                "sourceFile", "src/main/resources/applicationContext.xml",
                "line", 44));

        // 5. External library class resolved via exact ClasspathTypeLookup (ZERO regex!)
        f.addEvidenceOnly(FAMILY_XML_BEAN, Map.of(
                "beanId", "libDataSource",
                "className", "org.vendor.db.PooledDataSource",
                "properties", List.of(Map.of("name", "jdbcUrl", "value", "jdbc:h2:mem:test")),
                "sourceFile", "src/main/resources/applicationContext.xml",
                "line", 52));

        var graph = f.build();
        var service = new ArchitectureQueryService(graph);
        var pack = new MetadataConsistencyRulePack();

        // Case A: Complete classpath containing org.vendor.db.PooledDataSource
        var completeClasspath = ClasspathTypeLookup.ofExactTypes(Map.of(
                "org.vendor.db.PooledDataSource", TypeDescriptor.simpleClass(
                        "org.vendor.db.PooledDataSource",
                        List.of(ConstructorSignature.of(0)),
                        List.of(MethodSignature.setter("setJdbcUrl", "java.lang.String")),
                        List.of())),
                true);

        var completeReport = pack.evaluatePack(service, f.context, completeClasspath, EvaluationOptions.defaults());
        var r1Findings = completeReport.findingsForRule(RULE_XML_BEAN_CLASS_AND_PROPS);

        // Must catch: constructor mismatch, missing setter, missing class, non-instantiable abstract class, missing @ImportResource
        assertTrue(r1Findings.stream().anyMatch(x -> x.subCode().equals("XML_CONSTRUCTOR_ARG_MISMATCH") && x.classification() == Classification.MUST));
        assertTrue(r1Findings.stream().anyMatch(x -> x.subCode().equals("XML_PROPERTY_SETTER_NOT_FOUND") && x.classification() == Classification.MUST));
        assertTrue(r1Findings.stream().anyMatch(x -> x.subCode().equals("XML_BEAN_CLASS_NOT_FOUND") && x.subject().contains("ghostBean")));
        assertTrue(r1Findings.stream().anyMatch(x -> x.subCode().equals("XML_BEAN_CLASS_NOT_INSTANTIABLE") && x.subject().contains("abstractBean")));
        assertTrue(r1Findings.stream().anyMatch(x -> x.subCode().equals("IMPORT_RESOURCE_NOT_FOUND") && x.subject().contains("missing-extra.xml")));
        // Valid beans and exact-classpath library bean MUST stay 100% clean
        assertTrue(r1Findings.stream().noneMatch(x -> x.subject().contains("validOrderProcessor")));
        assertTrue(r1Findings.stream().noneMatch(x -> x.subject().contains("libDataSource")));

        // Case B: Offline incomplete classpath (library JAR absent!) -> MUST emit UNKNOWN + LIBRARY_JAR_ABSENT, ZERO False Positive!
        var offlineReport = pack.evaluatePack(service, f.context, ClasspathTypeLookup.emptyOffline(), EvaluationOptions.offlineIncomplete());
        var offlineLibFindings = offlineReport.findingsForRule(RULE_XML_BEAN_CLASS_AND_PROPS).stream()
                .filter(x -> x.subject().contains("libDataSource"))
                .toList();
        assertEquals(1, offlineLibFindings.size());
        assertEquals(Classification.UNKNOWN, offlineLibFindings.getFirst().classification());
        assertEquals(Optional.of(GAP_LIBRARY_JAR_ABSENT), offlineLibFindings.getFirst().gapReason());
        assertTrue(offlineReport.gaps().stream().anyMatch(g -> g.reasonCode().equals(GAP_LIBRARY_JAR_ABSENT)));
        // Never emit MUST / XML_BEAN_CLASS_NOT_FOUND when offline JAR is absent!
        assertTrue(offlineReport.findingsForRule(RULE_XML_BEAN_CLASS_AND_PROPS).stream()
                .noneMatch(x -> x.subCode().equals("XML_BEAN_CLASS_NOT_FOUND")));

        // Case C: Reuse SAME cached service with a DIFFERENT complete classpath lacking org.vendor.db.PooledDataSource
        // (regression guard: must NEVER suffer cross-delegate memoization cache collision!)
        var emptyCompleteReport = pack.evaluatePack(service, f.context, ClasspathTypeLookup.emptyComplete(), EvaluationOptions.defaults());
        assertTrue(emptyCompleteReport.findingsForRule(RULE_XML_BEAN_CLASS_AND_PROPS).stream()
                .anyMatch(x -> x.subject().contains("libDataSource") && x.subCode().equals("XML_BEAN_CLASS_NOT_FOUND")));
    }

    // ─── Rule 2: spring-bean-lifecycle-method-exists ───

    @Test
    void rule2VerifiesXmlAndJavaBeanZeroArgLifecycleMethodsAndIgnoresInferredSentinel() {
        var f = new GraphFixture();

        // Target pool class: has 0-arg start() and close(), plus 1-arg shutdown(boolean) (trap!)
        String poolCls = f.addJavaClass("com.acme.ConnectionPool", "CLASS", false, Set.of(), List.of());
        f.addJavaMethod(poolCls, "com.acme.ConnectionPool", "start", List.of(), "void", List.of());
        f.addJavaMethod(poolCls, "com.acme.ConnectionPool", "close", List.of(), "void", List.of());
        f.addJavaMethod(poolCls, "com.acme.ConnectionPool", "shutdown", List.of("boolean"), "void", List.of());

        // Java @Configuration with @Bean lifecycle methods
        String cfgCls = f.addJavaClass("com.acme.PoolConfig", "CLASS", false, Set.of(), List.of("@Configuration"));
        f.addJavaMethod(cfgCls, "com.acme.PoolConfig", "validPool", List.of(), "com.acme.ConnectionPool",
                List.of("@Bean(initMethod = \"start\", destroyMethod = \"close\")"));
        f.addJavaMethod(cfgCls, "com.acme.PoolConfig", "inferredPool", List.of(), "com.acme.ConnectionPool",
                List.of("@Bean(initMethod = \"start\", destroyMethod = \"(inferred)\")"));
        f.addJavaMethod(cfgCls, "com.acme.PoolConfig", "wrongArityPool", List.of(), "com.acme.ConnectionPool",
                List.of("@Bean(destroyMethod = \"shutdown\")")); // shutdown takes boolean -> NOT 0-arg!
        f.addJavaMethod(cfgCls, "com.acme.PoolConfig", "missingInitPool", List.of(), "com.acme.ConnectionPool",
                List.of("@Bean(initMethod = \"bootstrapNow\")"));

        // XML beans with valid and missing lifecycle methods
        f.addEvidenceOnly(FAMILY_XML_BEAN, Map.of(
                "beanId", "xmlValidPool",
                "className", "com.acme.ConnectionPool",
                "initMethod", "start",
                "destroyMethod", "close",
                "sourceFile", "src/main/resources/beans.xml",
                "line", 8));
        f.addEvidenceOnly(FAMILY_XML_BEAN, Map.of(
                "beanId", "xmlBadPool",
                "className", "com.acme.ConnectionPool",
                "initMethod", "initializeMissing",
                "destroyMethod", "shutdown",
                "sourceFile", "src/main/resources/beans.xml",
                "line", 14));

        var report = new MetadataConsistencyRulePack().evaluatePack(
                new ArchitectureQueryService(f.build()), f.context, ClasspathTypeLookup.emptyComplete(), EvaluationOptions.defaults());
        var findings = report.findingsForRule(RULE_BEAN_LIFECYCLE_METHOD);

        assertEquals(4, findings.size(), () -> "Expected 4 lifecycle findings, got: " + findings);
        assertTrue(findings.stream().allMatch(x -> x.subCode().equals("LIFECYCLE_METHOD_NOT_FOUND") && x.classification() == Classification.MUST));
        assertTrue(findings.stream().anyMatch(x -> x.subject().contains("wrongArityPool")));
        assertTrue(findings.stream().anyMatch(x -> x.subject().contains("missingInitPool")));
        assertTrue(findings.stream().anyMatch(x -> x.subject().contains("xmlBadPool#init-method=initializeMissing")));
        assertTrue(findings.stream().anyMatch(x -> x.subject().contains("xmlBadPool#destroy-method=shutdown")));
        // Valid & (inferred) sentinel stay clean
        assertTrue(findings.stream().noneMatch(x -> x.subject().contains("validPool") || x.subject().contains("inferredPool") || x.subject().contains("xmlValidPool")));
    }

    // ─── Rule 3: boot-property-key-exists ───

    @Test
    void rule3CrossChecksValueAndConditionalOnPropertyAcrossAllLayersWithProfileTruthScope() {
        var f = new GraphFixture();

        // Multi-layer ConfigDataIngestion evidence: bootstrap, application, shared, profile-specific dev/staging
        f.addEvidenceOnly(FAMILY_CONFIG_DATA, Map.of(
                "documents", List.of(
                        Map.of("path", "src/main/resources/bootstrap.yml", "properties", Map.of("spring.application.name", "billing-svc")),
                        Map.of("path", "src/main/resources/shared/application.yml", "properties", Map.of("shared.tracing.endpoint", "http://otel:4317")),
                        Map.of("path", "src/main/resources/application.yml", "properties", Map.of("app.base.timeout", "5000")),
                        Map.of("path", "src/main/resources/application-dev.yml", "profile", "dev", "properties", Map.of("app.gateway.url", "http://dev-gw")),
                        Map.of("path", "src/main/resources/application-staging.yml", "profile", "staging", "properties", Map.of("app.staging.only", "true")))));

        String svc = f.addJavaClass("com.acme.BillingClient", "CLASS", false, Set.of(), List.of("@Profile({\"dev\", \"staging\"})"));
        // 1. Present in shared layer -> CLEAN
        f.addJavaField(svc, "com.acme.BillingClient", "tracingUrl", "java.lang.String",
                List.of("@Value(\"${shared.tracing.endpoint}\")"));
        // 2. Has :default -> CLEAN
        f.addJavaField(svc, "com.acme.BillingClient", "retries", "int",
                List.of("@Value(\"${app.unconfigured.retries:3}\")"));
        // 3. Present in dev, MISSING in staging -> MAY warning with explicit staging profile scope + witness!
        f.addJavaField(svc, "com.acme.BillingClient", "gatewayUrl", "java.lang.String",
                List.of("@Value(\"${app.gateway.url}\")"));
        // 4. Absent everywhere -> MUST error!
        f.addJavaField(svc, "com.acme.BillingClient", "missingKey", "java.lang.String",
                List.of("@Value(\"${app.missing.mandatory}\")"));

        // 5. @ConditionalOnProperty missing vs matchIfMissing=true
        f.addJavaClass("com.acme.DeadFeatureToggle", "CLASS", false, Set.of(),
                List.of("@ConditionalOnProperty(prefix = \"feature\", name = \"ghost\")"));
        f.addJavaClass("com.acme.SafeDefaultToggle", "CLASS", false, Set.of(),
                List.of("@ConditionalOnProperty(prefix = \"feature\", name = \"optionalFlag\", matchIfMissing = true)"));

        var report = new MetadataConsistencyRulePack().evaluatePack(
                new ArchitectureQueryService(f.build()), f.context, ClasspathTypeLookup.emptyComplete(), EvaluationOptions.defaults());
        var findings = report.findingsForRule(RULE_BOOT_PROPERTY_KEY);

        assertEquals(3, findings.size(), () -> "Unexpected Rule 3 findings: " + findings);

        var stagingMissing = findings.stream().filter(x -> x.subject().contains("gatewayUrl")).findFirst().orElseThrow();
        assertEquals(Classification.MAY, stagingMissing.classification());
        assertEquals(ArchitecturePolicy.Severity.WARNING, stagingMissing.severity());
        assertTrue(stagingMissing.truthRegionScope().orElse("").contains("staging"));
        assertEquals("staging", stagingMissing.counterexampleWitness().get("spring.profiles.active"));

        var alwaysMissing = findings.stream().filter(x -> x.subject().contains("missingKey")).findFirst().orElseThrow();
        assertEquals(Classification.MUST, alwaysMissing.classification());
        assertEquals(ArchitecturePolicy.Severity.ERROR, alwaysMissing.severity());

        var condMissing = findings.stream().filter(x -> x.subject().contains("DeadFeatureToggle")).findFirst().orElseThrow();
        assertEquals("CONDITIONAL_PROPERTY_KEY_MISSING", condMissing.subCode());
        assertTrue(findings.stream().noneMatch(x -> x.subject().contains("SafeDefaultToggle")
                || x.subject().contains("retries") || x.subject().contains("tracingUrl")));
    }

    // ─── Rule 4: boot-config-properties-binding ───

    @Test
    void rule4ValidatesRelaxedKebabCamelUpperSnakeBindingAndFlagsTyposAndMissingSetters() {
        var f = new GraphFixture();

        // @ConfigurationProperties(prefix = "app.pool", ignoreUnknownFields = false)
        String poolProps = f.addJavaClass("com.acme.PoolProperties", "CLASS", false, Set.of(),
                List.of("@ConfigurationProperties(prefix = \"app.pool\", ignoreUnknownFields = false)"));
        f.addJavaField(poolProps, "com.acme.PoolProperties", "maxPoolSize", "int", List.of());
        f.addJavaMethod(poolProps, "com.acme.PoolProperties", "setMaxPoolSize", List.of("int"), "void", List.of());
        f.addJavaField(poolProps, "com.acme.PoolProperties", "idleTimeout", "java.time.Duration", List.of());
        f.addJavaMethod(poolProps, "com.acme.PoolProperties", "setIdleTimeout", List.of("java.time.Duration"), "void", List.of());
        f.addJavaField(poolProps, "com.acme.PoolProperties", "extraHeaders", "java.util.Map<String,String>", List.of());
        f.addJavaMethod(poolProps, "com.acme.PoolProperties", "getExtraHeaders", List.of(), "java.util.Map<String,String>", List.of());
        // Immutable field with NO setter -> must flag CONFIG_PROPERTIES_NO_SETTER
        f.addJavaField(poolProps, "com.acme.PoolProperties", "readOnlyMode", "boolean", List.of());

        f.addEvidenceOnly(FAMILY_CONFIG_DATA, Map.of(
                "documents", List.of(Map.of(
                        "path", "src/main/resources/application.yml",
                        "properties", Map.of(
                                "app.pool.max-pool-size", "32",              // kebab-case -> matches maxPoolSize
                                "app.pool.idleTimeout", "10s",               // camelCase -> matches idleTimeout
                                "APP_POOL_MAX_POOL_SIZE", "64",              // UPPER_SNAKE -> matches maxPoolSize
                                "app.pool.extra-headers.x-trace", "enabled", // Map property -> valid dynamic subkey
                                "app.pool.read-only-mode", "true",           // matches field but NO setter!
                                "app.pool.max-pool-sz", "16"                 // TYPO -> unbound key!
                        )))));

        var report = new MetadataConsistencyRulePack().evaluatePack(
                new ArchitectureQueryService(f.build()), f.context, ClasspathTypeLookup.emptyComplete(), EvaluationOptions.defaults());
        var findings = report.findingsForRule(RULE_BOOT_CONFIG_PROPERTIES);

        assertEquals(2, findings.size(), () -> "Expected typo + missing setter, got: " + findings);
        assertTrue(findings.stream().anyMatch(x -> x.subCode().equals("CONFIG_PROPERTIES_UNBOUND_KEY")
                && x.subject().contains("app.pool.max-pool-sz")
                && x.severity() == ArchitecturePolicy.Severity.ERROR));
        assertTrue(findings.stream().anyMatch(x -> x.subCode().equals("CONFIG_PROPERTIES_NO_SETTER")
                && x.subject().contains("app.pool.read-only-mode")));
        // Verify relaxed binding helper directly
        assertTrue(MetadataConsistencyRulePack.relaxedEquivalent("app.pool.max-pool-size", "app.pool.maxPoolSize"));
        assertTrue(MetadataConsistencyRulePack.relaxedEquivalent("app.pool.max-pool-size", "APP_POOL_MAX_POOL_SIZE"));
    }

    // ─── Rule 5: spring-getbean-conditional-safety (with real SAT SymbolicTruthRegions!) ───

    @Test
    void rule5JoinsGetBeanWithSatTruthRegionsForMustMayNeverAndDynamicLookups() {
        var f = new GraphFixture();

        // Real SAT formula DAG for conditional MAY bean: active when profile:prod=true, absent when profile:prod=false
        var prodVar = new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "prod");
        String prodTrueKey = SymbolicTruthRegions.baselineChoiceVariable(prodVar, FiniteDomain.Value.bool(true));
        String prodFalseKey = SymbolicTruthRegions.baselineChoiceVariable(prodVar, FiniteDomain.Value.bool(false));
        var trueFormula = SymbolicConfiguration.variable(prodTrueKey);
        var falseFormula = SymbolicConfiguration.variable(prodFalseKey);

        f.addEvidenceOnly("spring.space", Map.of(
                "domains", List.of(Map.of(
                        "variable", Map.of("kind", "PROFILE", "name", "prod"),
                        "values", List.of(
                                Map.of("kind", "BOOLEAN", "bool", true),
                                Map.of("kind", "BOOLEAN", "bool", false))))));
        f.addEvidenceOnly("spring.formula", Map.of(
                "identity", trueFormula.identity().value(), "operator", "VARIABLE",
                "variable", prodTrueKey, "children", List.of()));
        f.addEvidenceOnly("spring.formula", Map.of(
                "identity", falseFormula.identity().value(), "operator", "VARIABLE",
                "variable", prodFalseKey, "children", List.of()));

        // 1. MUST bean -> CLEAN
        f.addEvidenceOnly(FAMILY_SYMBOLIC_REGION, Map.of(
                "beanName", "alwaysBean",
                "beanType", "com.acme.AlwaysService",
                "classification", "MUST"));
        // 2. NEVER bean -> MUST error
        f.addEvidenceOnly(FAMILY_SYMBOLIC_REGION, Map.of(
                "beanName", "deadBean",
                "beanType", "com.acme.DeadService",
                "classification", "NEVER"));
        // 3. MAY bean backed by real SAT falseRegion formula -> MAY warning + SAT counterexample witness!
        f.addEvidenceOnly(FAMILY_SYMBOLIC_REGION, Map.of(
                "encoding", "symbolic-dag-m4u.2-v1",
                "beanName", "prodCache",
                "beanType", "com.acme.ProdCache",
                "region", Map.of(
                        "fact", Map.of("kind", "DEFINITION_PRESENT", "definition", "bean:prodCache", "beanName", "prodCache"),
                        "classification", "MAY",
                        "trueRegion", trueFormula.identity().value(),
                        "falseRegion", falseFormula.identity().value(),
                        "unknownRegion", SymbolicConfiguration.FALSE.identity().value())));

        // getBean() lookup sites
        f.addEvidenceOnly(FAMILY_BEAN_LOOKUP, Map.of("beanName", "alwaysBean", "beanType", "com.acme.AlwaysService",
                "dynamic", false, "sourceFile", "src/App.java", "line", 12));
        f.addEvidenceOnly(FAMILY_BEAN_LOOKUP, Map.of("beanName", "deadBean", "beanType", "com.acme.DeadService",
                "dynamic", false, "sourceFile", "src/App.java", "line", 18));
        f.addEvidenceOnly(FAMILY_BEAN_LOOKUP, Map.of("beanName", "prodCache", "beanType", "com.acme.ProdCache",
                "dynamic", false, "sourceFile", "src/App.java", "line", 24));
        f.addEvidenceOnly(FAMILY_BEAN_LOOKUP, Map.of("beanName", "", "beanType", "",
                "dynamic", true, "sourceFile", "src/App.java", "line", 30));
        f.addEvidenceOnly(FAMILY_BEAN_LOOKUP, Map.of("beanName", "unmodeledBean", "beanType", "com.acme.Unmodeled",
                "dynamic", false, "sourceFile", "src/App.java", "line", 36));

        var report = new MetadataConsistencyRulePack().evaluatePack(
                new ArchitectureQueryService(f.build()), f.context, ClasspathTypeLookup.emptyComplete(), EvaluationOptions.defaults());
        var findings = report.findingsForRule(RULE_GETBEAN_CONDITIONAL_SAFETY);

        // alwaysBean MUST be clean (absent from findings)
        assertTrue(findings.stream().noneMatch(x -> x.subject().contains("alwaysBean")));

        var deadFinding = findings.stream().filter(x -> x.subject().contains("deadBean")).findFirst().orElseThrow();
        assertEquals(Classification.MUST, deadFinding.classification());
        assertEquals(ArchitecturePolicy.Severity.ERROR, deadFinding.severity());
        assertEquals("GETBEAN_TARGET_NEVER_ACTIVE", deadFinding.subCode());

        var mayFinding = findings.stream().filter(x -> x.subject().contains("prodCache")).findFirst().orElseThrow();
        assertEquals(Classification.MAY, mayFinding.classification());
        assertEquals(ArchitecturePolicy.Severity.WARNING, mayFinding.severity());
        assertEquals("GETBEAN_TARGET_CONDITIONAL_MAY", mayFinding.subCode());
        assertEquals("false", mayFinding.counterexampleWitness().get("profile:prod"),
                () -> "Expected SAT counterexample witness profile:prod=false, got: " + mayFinding.counterexampleWitness());

        var dynFinding = findings.stream().filter(x -> x.line() == 30).findFirst().orElseThrow();
        assertEquals(Classification.UNKNOWN, dynFinding.classification());
        assertEquals(Optional.of(GAP_DYNAMIC_BEAN_LOOKUP), dynFinding.gapReason());
        // Harmonized with WP2.1 SpringXmlBeanExtractor.GAP_DYNAMIC_BEAN_LOOKUP ("DYNAMIC_BEAN_LOOKUP_UNRESOLVED")
        assertEquals("DYNAMIC_BEAN_LOOKUP_UNRESOLVED", GAP_DYNAMIC_BEAN_LOOKUP);
        assertEquals(com.evolution.analysis.spring.universal.SpringXmlBeanExtractor.GAP_DYNAMIC_BEAN_LOOKUP, GAP_DYNAMIC_BEAN_LOOKUP);
        assertTrue(MetadataConsistencyRulePack.isDynamicBeanLookupGap("DYNAMIC_BEAN_LOOKUP_UNRESOLVED"));
        assertTrue(MetadataConsistencyRulePack.isDynamicBeanLookupGap("DYNAMIC_BEAN_LOOKUP"));

        var unmodeledFinding = findings.stream().filter(x -> x.subject().contains("unmodeledBean")).findFirst().orElseThrow();
        assertEquals(Classification.UNKNOWN, unmodeledFinding.classification());
        assertEquals(Optional.of(GAP_BEAN_TRUTH_UNKNOWN), unmodeledFinding.gapReason());
    }

    // ─── Rule 6: feign-controller-contract-parity ───

    @Test
    void rule6VerifiesFeignClientVerbAndNormalizedRoutePathAgainstControllers() {
        var f = new GraphFixture();

        // Target service identity + controller endpoints
        f.addEvidenceOnly("architecture.service-identity", Map.of("serviceName", "inventory-svc"));
        f.addEvidenceOnly(FAMILY_ENDPOINT_DECLARATION, Map.of(
                "serviceName", "inventory-svc",
                "route", Map.of("controller", "InventoryController", "handler", "getItem",
                        "path", "/api/v1/items/{id}", "methods", List.of("GET"), "implicitHead", true)));
        f.addEvidenceOnly(FAMILY_ENDPOINT_DECLARATION, Map.of(
                "serviceName", "inventory-svc",
                "route", Map.of("controller", "InventoryController", "handler", "createItem",
                        "path", "/api/v1/items", "methods", List.of("POST"), "implicitHead", false)));

        // @FeignClient interface with:
        // 1. Matching GET /api/v1/items/{itemId} (path variable name differs, normalized {*} matches!) -> CLEAN
        // 2. Wrong HTTP verb DELETE /api/v1/items/{itemId} -> FEIGN_HTTP_VERB_MISMATCH
        // 3. Missing route GET /api/v1/items/{itemId}/stock-history -> FEIGN_ENDPOINT_NOT_FOUND
        String feignIface = f.addJavaClass("com.acme.InventoryClient", "INTERFACE", true, Set.of(),
                List.of("@FeignClient(name = \"inventory-svc\", path = \"/api/v1/items\")"));
        f.addJavaMethod(feignIface, "com.acme.InventoryClient", "findItem", List.of("String"), "Object",
                List.of("@GetMapping(\"/{itemId}\")"));
        f.addJavaMethod(feignIface, "com.acme.InventoryClient", "wrongVerb", List.of("String"), "void",
                List.of("@DeleteMapping(\"/{itemId}\")"));
        f.addJavaMethod(feignIface, "com.acme.InventoryClient", "missingEndpoint", List.of("String"), "Object",
                List.of("@GetMapping(\"/{itemId}/stock-history\")"));

        // External uncaptured service -> UNKNOWN + FEIGN_TARGET_UNRESOLVED (ZERO FP!)
        String extFeign = f.addJavaClass("com.acme.ExternalPartnerClient", "INTERFACE", true, Set.of(),
                List.of("@FeignClient(name = \"external-partner-api\")"));
        f.addJavaMethod(extFeign, "com.acme.ExternalPartnerClient", "ping", List.of(), "String",
                List.of("@GetMapping(\"/health\")"));

        var report = new MetadataConsistencyRulePack().evaluatePack(
                new ArchitectureQueryService(f.build()), f.context, ClasspathTypeLookup.emptyComplete(), EvaluationOptions.defaults());
        var findings = report.findingsForRule(RULE_FEIGN_CONTROLLER_PARITY);

        assertEquals(3, findings.size(), () -> "Unexpected Rule 6 findings: " + findings);
        assertTrue(findings.stream().noneMatch(x -> x.subject().contains("findItem")));
        assertTrue(findings.stream().anyMatch(x -> x.subject().contains("wrongVerb")
                && x.subCode().equals("FEIGN_HTTP_VERB_MISMATCH") && x.classification() == Classification.MUST));
        assertTrue(findings.stream().anyMatch(x -> x.subject().contains("missingEndpoint")
                && x.subCode().equals("FEIGN_ENDPOINT_NOT_FOUND") && x.classification() == Classification.MUST));
        assertTrue(findings.stream().anyMatch(x -> x.subject().contains("ExternalPartnerClient")
                && x.classification() == Classification.UNKNOWN
                && x.gapReason().equals(Optional.of(GAP_FEIGN_TARGET_UNRESOLVED))));
    }

    // ─── Bounded Declarative RSL + 26-Rule Memoized Cache Single-Scan Parity & Thread-Safety ───

    @Test
    void twentySixRulesScanGraphOnceWithBitIdenticalParityAndThreadSafety() throws Exception {
        var f = new GraphFixture();
        f.addDocument("src/main/resources/applicationContext.xml");
        for (int i = 0; i < 10; i++) {
            String cls = f.addJavaClass("com.acme.Service" + i, "CLASS", false, Set.of(), List.of());
            f.addJavaMethod(cls, "com.acme.Service" + i, "init", List.of(), "void", List.of());
            f.addEvidenceOnly(FAMILY_XML_BEAN, Map.of(
                    "beanId", "svc" + i,
                    "className", "com.acme.Service" + i,
                    "scope", i % 2 == 0 ? "singleton" : "prototype",
                    "initMethod", i == 9 ? "missingInit" : "init",
                    "sourceFile", "src/main/resources/applicationContext.xml",
                    "line", 10 + i));
        }

        // Build YAML activating the 6-rule built-in pack + 20 custom bounded declarative RSL rules (26 rules total!)
        var yaml = new StringBuilder("""
                schema: architecture-policy:v1
                id: benchmark.twenty-six-rules
                version: "1"
                packs:
                  - metadata-consistency-pack
                metadataRules:
                """);
        for (int r = 0; r < 20; r++) {
            yaml.append("""
                      - id: custom.rsl-init-check-%d
                        version: "1"
                        severity: ERROR
                        forEach: { var: bean, in: XML_BEANS }
                        where:
                          - { field: bean.initMethod, op: PRESENT }
                        assert:
                          exists:
                            var: m
                            in: METHODS
                            where:
                              - { field: m.ownerClass, op: EQUALS, rhsField: bean.className }
                              - { field: m.name, op: EQUALS, rhsField: bean.initMethod }
                              - { field: m.paramCount, op: EQUALS, value: "0" }
                          message: "XML bean initMethod must resolve to a 0-arg method"
                    """.formatted(r));
        }

        var graph = f.build();
        byte[] yamlBytes = yaml.toString().getBytes(StandardCharsets.UTF_8);
        var evaluator = new PolicyEvaluator();

        var cachedService = new ArchitectureQueryService(graph);
        var uncachedService = ArchitectureQueryService.withoutMemoization(graph);

        var cachedReport = evaluator.evaluateMetadataPolicy(
                yamlBytes, cachedService, f.context, ClasspathTypeLookup.emptyComplete(), EvaluationOptions.defaults());
        var uncachedReport = evaluator.evaluateMetadataPolicy(
                yamlBytes, uncachedService, f.context, ClasspathTypeLookup.emptyComplete(), EvaluationOptions.defaults());

        assertEquals(26, cachedReport.rules().size());
        // Bit-identical report & SHA-256 identity with vs without memoization cache!
        assertEquals(uncachedReport, cachedReport);
        assertEquals(uncachedReport.identity(), cachedReport.identity());
        assertEquals(CanonicalJson.write(uncachedReport.toView()), CanonicalJson.write(cachedReport.toView()));

        // Single underlying graph scan across all 26 rules + high memoization cache hit count!
        var stats = cachedService.cacheStats();
        assertEquals(1L, stats.graphScans(), () -> "Expected single graph scan across 26 rules, got: " + stats);
        assertTrue(stats.hits() >= 20, () -> "Expected >= 20 memoized hits across 26 rules, got: " + stats);
        assertTrue(uncachedService.cacheStats().graphScans() > 1, "Uncached service should scan repeatedly");

        // Concurrent thread-safety verification on shared cachedService
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<ContentDigest>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> evaluator.evaluateMetadataPolicy(
                        yamlBytes, cachedService, f.context, ClasspathTypeLookup.emptyComplete(), EvaluationOptions.defaults())
                        .identity()));
            }
            for (var fut : futures) {
                assertEquals(cachedReport.identity(), fut.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rslMethodExistsConfigKeyBoundEmitLibraryJarAbsentNotFpAndSpringBeansDeduplicates() {
        var f = new GraphFixture();

        // 1. Subclass with external supertype whose JAR is absent -> RSL METHOD_EXISTS must emit UNKNOWN + LIBRARY_JAR_ABSENT!
        f.addJavaClass("com.acme.CustomPool", "CLASS", false, Set.of("org.vendor.pool.BasePool"), List.of());
        f.addEvidenceOnly(FAMILY_XML_BEAN, Map.of(
                "beanId", "customPool",
                "className", "com.acme.CustomPool",
                "initMethod", "superInit",
                "sourceFile", "src/main/resources/beans.xml",
                "line", 10));

        // 2. @ConfigurationProperties bean with (a) nested field whose type JAR is absent and (b) bean class from absent JAR
        String nestedHolder = f.addJavaClass("com.acme.HolderProps", "CLASS", false, Set.of(),
                List.of("@ConfigurationProperties(prefix = \"app.holder\")"));
        f.addJavaField(nestedHolder, "com.acme.HolderProps", "nested", "org.vendor.cfg.NestedVendorOpts", List.of());
        f.addJavaMethod(nestedHolder, "com.acme.HolderProps", "getNested", List.of(), "org.vendor.cfg.NestedVendorOpts", List.of());
        // Add a @ConfigurationProperties bean via @Bean returning an absent-JAR class
        String cfgCls = f.addJavaClass("com.acme.VendorCfg", "CLASS", false, Set.of(), List.of("@Configuration"));
        f.addJavaMethod(cfgCls, "com.acme.VendorCfg", "vendorProps", List.of(), "org.vendor.cfg.ExternalProps",
                List.of("@Bean", "@ConfigurationProperties(prefix = \"app.vendor\")"));

        // 3. Two distinct SPRING_BEANS with beanId, beanName, AND beanType (indexed under 3 lookup keys internally!)
        f.addEvidenceOnly(FAMILY_SYMBOLIC_REGION, Map.of(
                "beanId", "bean:alpha", "beanName", "alphaBean", "beanType", "com.acme.Alpha", "classification", "MUST"));
        f.addEvidenceOnly(FAMILY_SYMBOLIC_REGION, Map.of(
                "beanId", "bean:beta", "beanName", "betaBean", "beanType", "com.acme.Beta", "classification", "MAY"));

        String rslYaml = """
                schema: architecture-policy:v1
                id: regression.rsl-i3-and-dedup
                version: "1"
                metadataRules:
                  - id: rsl.method-supertype-gap
                    version: "1"
                    severity: ERROR
                    forEach: { var: l, in: LIFECYCLE_METHODS }
                    assert:
                      op: METHOD_EXISTS
                      targetField: l.methodName
                      message: "Lifecycle method must exist"
                  - id: rsl.config-nested-gap
                    version: "1"
                    severity: ERROR
                    forEach: { var: cp, in: CONFIG_PROPERTIES_BEANS }
                    assert:
                      op: CONFIG_KEY_BOUND
                      targetField: cp.prefix
                      message: "Config property key must bind"
                  - id: rsl.spring-beans-dedup
                    version: "1"
                    severity: INFO
                    forEach: { var: sb, in: SPRING_BEANS }
                    assert:
                      field: sb.classification
                      op: NOT_EQUALS
                      value: NEVER
                      message: "Spring bean must not be NEVER"
                """;

        // Add synthetic nested key check via custom RSL rule targeting a nested property on HolderProps
        var graph = f.build();
        var service = new ArchitectureQueryService(graph);
        var evaluator = new PolicyEvaluator();
        var report = evaluator.evaluateMetadataPolicy(
                rslYaml.getBytes(StandardCharsets.UTF_8), service, f.context,
                ClasspathTypeLookup.emptyOffline(), EvaluationOptions.offlineIncomplete());

        // Check 1: METHOD_EXISTS on absent supertype JAR emits UNKNOWN + LIBRARY_JAR_ABSENT (not silent skip, not FP MUST)
        var methodFindings = report.findingsForRule("rsl.method-supertype-gap");
        assertEquals(1, methodFindings.size());
        assertEquals(Classification.UNKNOWN, methodFindings.getFirst().classification());
        assertEquals(Optional.of(GAP_LIBRARY_JAR_ABSENT), methodFindings.getFirst().gapReason());

        // Check 2: CONFIG_KEY_BOUND on absent class JAR emits UNKNOWN + LIBRARY_JAR_ABSENT (not dropped)
        var cpFindings = report.findingsForRule("rsl.config-nested-gap");
        assertTrue(cpFindings.stream().anyMatch(x -> x.subject().equals("org.vendor.cfg.ExternalProps")
                && x.classification() == Classification.UNKNOWN
                && x.gapReason().equals(Optional.of(GAP_LIBRARY_JAR_ABSENT))));

        // Check 2b: CONFIG_KEY_BOUND on FOUND HolderProps with absent nested type JAR emits UNKNOWN + LIBRARY_JAR_ABSENT (NEVER FP MUST!)
        var f2 = new GraphFixture();
        String holder2 = f2.addJavaClass("com.acme.HolderProps", "CLASS", false, Set.of(), List.of());
        f2.addJavaField(holder2, "com.acme.HolderProps", "nested", "org.vendor.cfg.NestedVendorOpts", List.of());
        f2.addJavaMethod(holder2, "com.acme.HolderProps", "getNested", List.of(), "org.vendor.cfg.NestedVendorOpts", List.of());
        f2.addEvidenceOnly(FAMILY_XML_BEAN, Map.of(
                "beanId", "holder", "className", "com.acme.HolderProps",
                "properties", List.of(Map.of("name", "nested.timeout", "value", "5")),
                "sourceFile", "src/main/resources/beans.xml", "line", 5));
        String nestedBindYaml = """
                schema: architecture-policy:v1
                id: regression.rsl-nested-bind
                version: "1"
                metadataRules:
                  - id: rsl.nested-prop-bound
                    version: "1"
                    severity: ERROR
                    forEach: { var: p, in: XML_PROPERTIES }
                    assert:
                      op: CONFIG_KEY_BOUND
                      targetField: p.name
                      message: "Nested property must bind"
                """;
        var nestedReport = evaluator.evaluateMetadataPolicy(
                nestedBindYaml.getBytes(StandardCharsets.UTF_8),
                new ArchitectureQueryService(f2.build()), f2.context,
                ClasspathTypeLookup.emptyOffline(), EvaluationOptions.offlineIncomplete());
        var nestedFindings = nestedReport.findingsForRule("rsl.nested-prop-bound");
        assertEquals(1, nestedFindings.size());
        assertEquals(Classification.UNKNOWN, nestedFindings.getFirst().classification());
        assertEquals(Optional.of(GAP_LIBRARY_JAR_ABSENT), nestedFindings.getFirst().gapReason());
        assertTrue(nestedFindings.stream().noneMatch(x -> x.subCode().equals("RSL_CONFIG_KEY_UNBOUND")));

        // Check 3: SPRING_BEANS domain deduplicates canonical beans (2 symbolic + 1 XML = 3 distinct beans, NOT 9!)
        var dedupOutcome = report.rules().stream()
                .filter(r -> r.ruleId().equals("rsl.spring-beans-dedup"))
                .findFirst().orElseThrow();
        assertEquals(3, dedupOutcome.checkedCount(),
                () -> "Expected exactly 3 deduplicated SPRING_BEANS (alpha, beta, customPool), got: " + dedupOutcome.checkedCount());
    }
}
