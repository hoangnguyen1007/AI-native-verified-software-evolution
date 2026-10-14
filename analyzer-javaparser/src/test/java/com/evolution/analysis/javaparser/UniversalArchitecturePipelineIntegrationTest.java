package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.maven.MavenBuildModelProvider;
import com.evolution.analysis.spring.condition.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task V2.3: Comprehensive integration and acceptance test suite for UniversalArchitecturePipeline.
 * Verifies all 8 acceptance journeys, truth regions, root-cause categorization, and deterministic replay.
 */
class UniversalArchitecturePipelineIntegrationTest {

    private static final RepositoryIdentity REPO = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/universal-repo.git");
    private static final ModuleDescriptor MODULE = ModuleDescriptor.create(REPO, "test-module", "test-module");
    private static final VersionedIdentifier VERSION = new VersionedIdentifier("fixture.policy", "1");

    private RepositoryInputs inputs(Map<String, String> filesMap) {
        var files = new TreeMap<String, SourceInput>();
        filesMap.forEach((path, value) -> {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            files.put(path, new SourceInput(
                    SourceDocument.create(REPO, MODULE, path, ContentDigest.sha256(bytes), SourceClassification.MAIN),
                    bytes
            ));
        });
        var docs = files.values().stream().map(SourceInput::document).toList();
        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false,
                docs.stream().map(SnapshotFile::from).toList(), docs);
        return new RepositoryInputs(snapshot, files);
    }

    private PlatformInput jdkPlatform() {
        return TestInputs.request("class PlatformStub {}").platform();
    }

    private static SpringBuildContext testBuildContext(PlatformInput platform, List<BinaryInput> dependencies) {
        byte[] bytes = "package com.truth; public class TruthApp {}".getBytes(StandardCharsets.UTF_8);
        var doc = SourceDocument.create(REPO, MODULE, "TruthApp.java",
                ContentDigest.sha256(bytes), SourceClassification.MAIN);
        var plan = new FrontendPlan(Optional.of(platform.release()), Optional.of(platform.release()), false,
                ContentDigest.sha256Utf8("classpath-fixture"), ContentDigest.sha256Utf8("decoding-fixture"));
        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false,
                List.of(SnapshotFile.from(doc)), List.of(doc));
        var entries = new ArrayList<ClasspathEntry>();
        entries.add(platform.entry());
        dependencies.forEach(d -> entries.add(d.entry()));
        var component = new ManifestComponent(new VersionedIdentifier("fixture.component", "1"), ContentDigest.sha256Utf8("component"));
        var options = FrontendRequest.options(plan, MODULE.identity(), SourceClassification.MAIN, List.of(doc), platform);
        var manifest = AnalysisManifest.create(
                new VersionedIdentifier("analysis.manifest", "1"),
                snapshot,
                List.of(MODULE),
                entries,
                AnalysisConfiguration.create(
                        new VersionedIdentifier("analysis.configuration", "1"),
                        options
                ),
                component, component, component
        );
        var request = new FrontendRequest(
                manifest,
                MODULE.identity(),
                SourceClassification.MAIN,
                plan,
                List.of(new SourceInput(doc, bytes)),
                platform,
                dependencies
        );
        return SpringBuildContext.fromExactRequest(request, ContentDigest.sha256Utf8("assembly-fixture"));
    }

    @Test
    void journey1_PlainJavaStudentRepo() {
        var sourceMap = Map.of(
                "src/main/java/calculator/Calculator.java", """
                        package calculator;
                        public class Calculator {
                            public int add(int a, int b) { return a + b; }
                        }
                        """,
                "src/main/java/calculator/Main.java", """
                        package calculator;
                        public class Main {
                            public static void main(String[] args) {
                                Calculator calc = new Calculator();
                                System.out.println(calc.add(1, 2));
                            }
                        }
                        """
        );
        var input = inputs(sourceMap);
        var policy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .defaultPlatform(jdkPlatform())
                .build();

        var pipeline = new UniversalArchitecturePipeline();
        var report = pipeline.run(input, policy);

        assertNotNull(report);
        assertEquals(UniversalArchitectureReport.Status.COMPLETE, report.status(), () -> report.issues().toString());
        assertEquals(UniversalArchitectureReport.ConfidenceLevel.FULL, report.metrics().confidence());
        assertFalse(report.modules().isEmpty());
        assertEquals(UniversalBuildModel.Tool.PLAIN_JAVA.name(), report.modules().getFirst().tool());

        assertTrue(report.types().stream().anyMatch(t -> t.qualifiedName().contains("Calculator")));
        assertTrue(report.types().stream().anyMatch(t -> t.qualifiedName().contains("Main")));

        assertTrue(report.entryPoints().stream().anyMatch(e ->
                e.kind() == UniversalArchitectureReport.EntryPointKind.MAIN_METHOD && e.declaringType().contains("Main")
        ));

        assertTrue(report.metrics().declaredTypes() >= 2);
        assertNotNull(report.identity());
    }

    @Test
    void journey2_MavenMultiModuleWithMissingDependency() {
        var rootPom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.enterprise</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>core</module>
                        <module>api</module>
                    </modules>
                </project>
                """;
        var corePom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.enterprise</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>core</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.private.internal</groupId>
                            <artifactId>missing-secret-jar</artifactId>
                            <version>9.9.9</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        var coreSource = """
                package core;
                public class CoreService {
                    public String compute() { return "core"; }
                }
                """;
        var apiPom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.enterprise</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>api</artifactId>
                </project>
                """;
        var apiSource = """
                package api;
                public class ApiEndpoint {
                    public String get() { return "api"; }
                }
                """;

        var input = inputs(Map.of(
                "pom.xml", rootPom,
                "core/pom.xml", corePom,
                "core/src/main/java/core/CoreService.java", coreSource,
                "api/pom.xml", apiPom,
                "api/src/main/java/api/ApiEndpoint.java", apiSource
        ));

        var policy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .mavenProvider(new MavenBuildModelProvider())
                .defaultPlatform(jdkPlatform())
                .build();

        var pipeline = new UniversalArchitecturePipeline();
        var report = pipeline.run(input, policy);

        assertEquals(UniversalArchitectureReport.Status.PARTIAL, report.status());
        assertEquals(3, report.modules().size()); // parent, core, api
        assertTrue(report.types().stream().anyMatch(t -> t.qualifiedName().contains("CoreService")));
        assertTrue(report.types().stream().anyMatch(t -> t.qualifiedName().contains("ApiEndpoint")));

        // Root cause classification
        assertFalse(report.rootCauses().isEmpty());
        String rootCausesTable = report.toRootCausesTable();
        assertTrue(rootCausesTable.contains("Root cause"));
        assertTrue(rootCausesTable.contains("Ảnh hưởng"));
    }

    @Test
    void journey3_GradleKotlinDslWithVersionCatalog() {
        var settings = """
                rootProject.name = "gradle-service"
                """;
        var build = """
                plugins {
                    java
                }
                group = "com.sample"
                version = "1.0.0"
                dependencies {
                    implementation(libs.guava)
                }
                """;
        var catalog = """
                [versions]
                guava = "32.1.3-jre"
                [libraries]
                guava = { module = "com.google.guava:guava", version.ref = "guava" }
                """;
        var java = """
                package com.sample;
                public class App {
                    public static void main(String[] args) {
                        System.out.println("hello gradle");
                    }
                }
                """;

        var input = inputs(Map.of(
                "settings.gradle.kts", settings,
                "build.gradle.kts", build,
                "gradle/libs.versions.toml", catalog,
                "src/main/java/com/sample/App.java", java
        ));

        var policy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .defaultPlatform(jdkPlatform())
                .build();

        var pipeline = new UniversalArchitecturePipeline();
        var report = pipeline.run(input, policy);

        assertNotNull(report);
        assertEquals(1, report.modules().size());
        assertEquals(UniversalBuildModel.Tool.GRADLE_KOTLIN.name(), report.modules().getFirst().tool());
        assertTrue(report.types().stream().anyMatch(t -> t.qualifiedName().contains("App")));
        assertTrue(report.entryPoints().stream().anyMatch(e -> e.kind() == UniversalArchitectureReport.EntryPointKind.MAIN_METHOD));
    }

    @Test
    void journey4_LegacyXmlServletBeansAndEntryPoints() {
        var springXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:schemaLocation="http://www.springframework.org/schema/beans
                       http://www.springframework.org/schema/beans/spring-beans.xsd">
                    <bean id="userDao" class="com.legacy.UserDao" />
                    <bean id="userService" class="com.legacy.UserService">
                        <property name="userDao" ref="userDao"/>
                    </bean>
                </beans>
                """;

        var webXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee" version="3.1">
                    <servlet>
                        <servlet-name>dispatcher</servlet-name>
                        <servlet-class>org.springframework.web.servlet.DispatcherServlet</servlet-class>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>dispatcher</servlet-name>
                        <url-pattern>/api/*</url-pattern>
                    </servlet-mapping>
                </web-app>
                """;

        var userDaoJava = """
                package com.legacy;
                public class UserDao {
                    public String find() { return "data"; }
                }
                """;

        var userServiceJava = """
                package com.legacy;
                public class UserService {
                    private UserDao userDao;
                    public void setUserDao(UserDao userDao) { this.userDao = userDao; }
                }
                """;

        var input = inputs(Map.of(
                "src/main/resources/applicationContext.xml", springXml,
                "src/main/webapp/WEB-INF/web.xml", webXml,
                "src/main/java/com/legacy/UserDao.java", userDaoJava,
                "src/main/java/com/legacy/UserService.java", userServiceJava
        ));

        var policy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .defaultPlatform(jdkPlatform())
                .build();

        var pipeline = new UniversalArchitecturePipeline();
        var report = pipeline.run(input, policy);

        assertNotNull(report);
        assertEquals(UniversalArchitectureReport.Status.COMPLETE, report.status());

        // Java types detected
        assertTrue(report.types().stream().anyMatch(t -> t.qualifiedName().contains("UserDao")));
        assertTrue(report.types().stream().anyMatch(t -> t.qualifiedName().contains("UserService")));

        // XML-configured beans extracted
        assertTrue(report.components().stream().anyMatch(c -> c.beanName().equals("userDao") && c.declaringType().equals("com.legacy.UserDao")));
        assertTrue(report.components().stream().anyMatch(c -> c.beanName().equals("userService") && c.declaringType().equals("com.legacy.UserService")));

        // XML property injection extracted
        assertTrue(report.injectionSites().stream().anyMatch(s ->
                s.owner().equals("com.legacy.UserService") && s.memberName().equals("userDao") && s.targetType().equals("userDao")
        ));

        // Servlet entry point extracted
        assertTrue(report.entryPoints().stream().anyMatch(e ->
                e.declaringType().contains("DispatcherServlet") && e.detail().contains("SERVLET /api/*")
        ));
    }

    @Test
    void journey5_ModernBootConditionalSpringData() throws Exception {
        var springSource = """
                package com.demo;
                @org.springframework.boot.autoconfigure.SpringBootApplication
                public class DemoApp {
                    public static void main(String[] args) {}
                }
                @org.springframework.context.annotation.Configuration
                public class AppConfig {
                    @org.springframework.context.annotation.Bean
                    public String dataSource() { return "dataSource"; }
                }
                @org.springframework.stereotype.Service
                public class DemoService {
                    public String message() { return "hello"; }
                }
                @org.springframework.web.bind.annotation.RestController
                public class DemoController {
                    private final DemoService service;
                    @org.springframework.beans.factory.annotation.Autowired
                    public DemoController(DemoService service) { this.service = service; }
                    @org.springframework.web.bind.annotation.GetMapping("/orders")
                    public String getOrders() { return service.message(); }
                }
                public interface OrderRepository extends org.springframework.data.repository.Repository<Object, Long> {
                }
                """;

        var input = inputs(Map.of(
                "src/main/java/com/demo/DemoApp.java", springSource,
                "src/main/resources/application.properties", "feature.active=true\n"
        ));

        var exact = ComponentIngestionTest.fixture(Map.of("fixture/App.java", springSource), false).request();
        var build = new UniversalBuildIngestion().ingest(
                input,
                UniversalBuildIngestion.Policy.defaults(),
                Optional.empty(),
                Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16)
        );
        var key = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(), SourcePlanModel.Kind.MAIN);
        var resolution = new UniversalSourceIngestion.Resolution(exact.platform(), exact.dependencies(), ContentDigest.sha256Utf8("spring-truth-res"));

        var evidence = new ConditionEvidence.Derived(List.of(ContentDigest.sha256Utf8("evidence")), VERSION, "test");
        var domain = new FiniteDomain(
                new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"),
                List.of(FiniteDomain.Value.bool(false), FiniteDomain.Value.bool(true)),
                evidence
        );
        var dummyContext = testBuildContext(exact.platform(), exact.dependencies());
        var space = new ConfigurationSpace(
                dummyContext,
                new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY, List.of(), List.of(), ContentDigest.sha256Utf8("envelope")),
                Optional.empty(),
                List.of(domain),
                List.of(),
                new ConfigurationSpace.PrecedencePolicy(VERSION, List.of(), evidence),
                new ConfigurationSpace.ProfilePolicy(VERSION, List.of(), Map.of(), List.of(), evidence),
                VERSION,
                new ConfigurationSpace.FeasibilityPolicy(VERSION, ConfigurationSpace.Limits.conservative(), evidence)
        );

        var policy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .resolutions(Map.of(key, resolution))
                .defaultPlatform(exact.platform())
                .configurationSpace(space)
                .truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.SYMBOLIC)
                .build();

        var pipeline = new UniversalArchitecturePipeline();
        var detailed = pipeline.runWithEvidence(input, policy);
        var report = detailed.report();
        var graph = new com.evolution.analysis.graph.CanonicalGraphBuilder().build(detailed.graphInput().orElseThrow());
        assertTrue(graph.nodes().stream().anyMatch(n -> n.kind().equals("BEAN_PRODUCER")));
        assertTrue(graph.nodes().stream().anyMatch(n -> n.kind().equals("INJECTION_POINT")));
        assertTrue(graph.nodes().stream().anyMatch(n -> n.kind().equals("TRUTH_REGION")));
        assertTrue(graph.nodes().stream().anyMatch(n -> n.kind().equals("REGION_FORMULA")));
        var codec = new com.evolution.analysis.graph.GraphSnapshotCodec();
        assertEquals(graph.identity(), codec.decode(codec.encode(graph), com.evolution.analysis.graph.GraphJson.Limits.defaults()).identity());

        assertNotNull(report);
        // Components & Entry Points
        assertTrue(report.types().stream().anyMatch(t -> t.qualifiedName().contains("DemoApp")));
        assertTrue(report.types().stream().anyMatch(t -> t.qualifiedName().contains("DemoService")));
        assertTrue(report.types().stream().anyMatch(t -> t.qualifiedName().contains("DemoController")));
        assertTrue(report.components().stream().anyMatch(c -> c.beanName().contains("DemoService") || c.declaringType().contains("DemoService")));
        assertTrue(report.entryPoints().stream().anyMatch(e -> e.kind() == UniversalArchitectureReport.EntryPointKind.BOOT_APPLICATION));
        assertTrue(report.entryPoints().stream().anyMatch(e -> e.kind() == UniversalArchitectureReport.EntryPointKind.CONTROLLER_ENDPOINT));

        // Producers extracted from @Bean
        assertFalse(report.producers().isEmpty(), "Producers should contain @Bean factory methods");
        assertTrue(report.producers().stream().anyMatch(p -> p.methodName().equals("dataSource") || p.beanName().equals("dataSource")));

        // Injection site verified without hardcoded values
        assertTrue(report.injectionSites().stream().anyMatch(s -> s.memberName().contains("DemoController") || s.targetType().contains("DemoService")));

        // Truth regions evaluated
        assertFalse(report.truthRegions().isEmpty());
        for (var tr : report.truthRegions()) {
            assertNotNull(tr.factKey());
            assertNotNull(tr.classification());
        }

        // Markdown summary contains Truth Regions section
        String markdown = report.toMarkdownSummary();
        assertTrue(markdown.contains("Truth Regions"));
        assertTrue(markdown.contains("Architecture Inventory"));
    }

    @Test
    void journey6_PolyglotMultiLanguageRepo() {
        var input = inputs(Map.of(
                "src/main/java/poly/JavaService.java", "package poly; public class JavaService {}",
                "src/main/kotlin/poly/KotlinService.kt", "package poly; class KotlinService",
                "scripts/deploy.py", "print('deploying')",
                "src/main/resources/application.properties", "key=val"
        ));

        var policy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .defaultPlatform(jdkPlatform())
                .build();

        var pipeline = new UniversalArchitecturePipeline();
        var report = pipeline.run(input, policy);

        assertNotNull(report);
        assertEquals(1, report.modules().size());
        var languages = report.modules().getFirst().languages();
        assertTrue(languages.contains("JAVA"));
        assertTrue(languages.contains("KOTLIN"));
        assertTrue(languages.contains("PYTHON"));

        // Java is analyzed
        assertTrue(report.types().stream().anyMatch(t -> t.qualifiedName().contains("JavaService")));
    }

    @Test
    void journey7_DegradedAndHostileInputs() {
        // 7A: Empty or Non-Java repo
        var nonJavaInput = inputs(Map.of(
                "README.md", "# Non-Java Project\nThis has no java code.",
                "package.json", "{\"name\": \"frontend\", \"version\": \"1.0.0\"}",
                "index.js", "console.log('hello');"
        ));
        var policy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .defaultPlatform(jdkPlatform())
                .build();
        var pipeline = new UniversalArchitecturePipeline();
        var reportNonJava = pipeline.run(nonJavaInput, policy);

        assertNotNull(reportNonJava);
        assertEquals(UniversalArchitectureReport.Status.EMPTY_OR_NON_JAVA, reportNonJava.status());
        assertEquals(UniversalArchitectureReport.ConfidenceLevel.MINIMAL, reportNonJava.metrics().confidence());
        assertTrue(reportNonJava.types().isEmpty());
        assertEquals(0, reportNonJava.metrics().declaredTypes());

        // 7B: Broken syntax in one file does not fail entire pipeline
        var mixedInput = inputs(Map.of(
                "src/main/java/broken/Bad.java", "package broken; class { invalid syntax here",
                "src/main/java/good/Good.java", "package good; public class Good { public void ok() {} }"
        ));
        var reportMixed = pipeline.run(mixedInput, policy);

        assertNotNull(reportMixed);
        // Valid file is extracted
        assertTrue(reportMixed.types().stream().anyMatch(t -> t.qualifiedName().contains("Good")));
        // Pipeline does not throw hard exception, produces structured report with summary
        assertNotNull(reportMixed.metrics().summaryText());
    }

    @Test
    void journey8_ChangedSourceEvidenceInvalidationAndImmutability() {
        var sourceV1 = Map.of(
                "src/main/java/sample/Service.java", """
                        package sample;
                        public class Service {
                            public int execute() { return 1; }
                        }
                        """
        );
        var inputV1 = inputs(sourceV1);
        var policy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .defaultPlatform(jdkPlatform())
                .build();

        var pipeline = new UniversalArchitecturePipeline();
        var report1 = pipeline.run(inputV1, policy);

        assertNotNull(report1);
        var initialId = report1.identity();
        var initialTypeCount = report1.types().size();

        // 1. Source modification yields new identity (invalidation)
        var sourceV2 = Map.of(
                "src/main/java/sample/Service.java", """
                        package sample;
                        public class Service {
                            public int execute() { return 2; }
                            public String extra() { return "v2"; }
                        }
                        """,
                "src/main/java/sample/Extra.java", """
                        package sample;
                        public class Extra {}
                        """
        );
        var inputV2 = inputs(sourceV2);
        var report2 = pipeline.run(inputV2, policy);

        assertNotNull(report2);
        assertNotEquals(initialId, report2.identity(), "Changed source must generate a new report identity");
        assertEquals(2, report2.types().size());

        // 2. Old report remains strictly immutable
        assertEquals(initialId, report1.identity(), "Historical report identity must remain immutable");
        assertEquals(initialTypeCount, report1.types().size(), "Historical report data must not mutate");

        // 3. Evidence loss does not silently claim resolved violation
        var missingDepPom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.sample</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.missing</groupId>
                            <artifactId>missing-lib</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        var degradedInput = inputs(Map.of(
                "pom.xml", missingDepPom,
                "src/main/java/sample/Consumer.java", "package sample; public class Consumer {}"
        ));
        var mavenPolicy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .mavenProvider(new MavenBuildModelProvider())
                .defaultPlatform(jdkPlatform())
                .build();
        var report3 = pipeline.run(degradedInput, mavenPolicy);

        assertNotNull(report3);
        // Evidence loss is recorded explicitly as PARTIAL with root causes, never false completeness
        assertEquals(UniversalArchitectureReport.Status.PARTIAL, report3.status());
        assertFalse(report3.rootCauses().isEmpty(), "Evidence loss must produce explicit root causes, not silent resolution");
        assertTrue(report3.rootCauses().stream().anyMatch(rc ->
                rc.category() == UniversalArchitectureReport.RootCauseCategory.UNRESOLVED_DEPENDENCIES
        ));
    }

    @Test
    void journey9_DeterministicReplay() {
        var fileA = "package sample; public class Alpha { public int val = 1; }";
        var fileB = "package sample; public class Beta { public String msg = \"hi\"; }";

        // Map order 1
        var map1 = new LinkedHashMap<String, String>();
        map1.put("src/main/java/sample/Alpha.java", fileA);
        map1.put("src/main/java/sample/Beta.java", fileB);

        // Map order 2 (reversed)
        var map2 = new LinkedHashMap<String, String>();
        map2.put("src/main/java/sample/Beta.java", fileB);
        map2.put("src/main/java/sample/Alpha.java", fileA);

        var input1 = inputs(map1);
        var input2 = inputs(map2);

        var policy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .defaultPlatform(jdkPlatform())
                .build();

        var pipeline = new UniversalArchitecturePipeline();
        var report1 = pipeline.run(input1, policy);
        var report2 = pipeline.run(input2, policy);

        // Bit-for-bit deterministic identities
        assertEquals(report1.identity(), report2.identity());
        assertEquals(report1.metrics().summaryText(), report2.metrics().summaryText());
        assertEquals(report1.toRootCausesTable(), report2.toRootCausesTable());
        assertEquals(report1.toMarkdownSummary(), report2.toMarkdownSummary());
    }

    @Test
    void repositoryAcquisitionResultIntakePath() {
        var input = inputs(Map.of(
                "src/main/java/simple/Simple.java", "package simple; public class Simple {}"
        ));
        var req = new RepositoryAcquisitionRequest(input.snapshot().repository(), Optional.empty(), false, "pom.xml",
                new RepositoryAcquisitionPolicy(100, 100, 1000, 10000, 8, List.of()));
        var files = input.files().values().stream().map(f -> new AcquiredFile(f.document().path(), f.bytes())).toList();
        var rawSnapshot = RepositorySnapshot.create(input.snapshot().repository(),
                input.snapshot().revision(), input.snapshot().dirty(),
                input.snapshot().files(), List.of());
        var acquisitionResult = new RepositoryAcquisitionResult(
                RepositoryAcquisitionResult.SCHEMA,
                req,
                UniversalArchitecturePipeline.PROVIDER,
                RepositoryAcquisitionResult.Completion.COMPLETE,
                Optional.of(rawSnapshot),
                files,
                List.of("."),
                List.of(),
                List.of(),
                List.of()
        );

        var policy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .defaultPlatform(jdkPlatform())
                .build();

        var pipeline = new UniversalArchitecturePipeline();
        var report = pipeline.run(acquisitionResult, policy);

        assertNotNull(report);
        assertEquals(UniversalArchitectureReport.Status.COMPLETE, report.status());
        assertTrue(report.types().stream().anyMatch(t -> t.qualifiedName().contains("Simple")));
    }
}
