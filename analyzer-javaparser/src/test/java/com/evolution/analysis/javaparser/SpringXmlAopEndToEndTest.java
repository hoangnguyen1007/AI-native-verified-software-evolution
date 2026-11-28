package com.evolution.analysis.javaparser;

import com.evolution.analysis.buildmodel.BuildModelPolicy;
import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.buildmodel.UniversalBuildIngestion;
import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.analysis.ManifestComponent;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.contract.source.SourceDocument;
import com.evolution.analysis.frontend.BinaryInput;
import com.evolution.analysis.frontend.FrontendResult;
import com.evolution.analysis.frontend.SourceInput;
import com.evolution.analysis.ingestion.RepositoryInputs;
import com.evolution.analysis.ingestion.UniversalIngestionPipeline;
import com.evolution.analysis.ingestion.UniversalSourceIngestion;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.condition.ConditionEvidence;
import com.evolution.analysis.spring.condition.SpringBuildContext;
import com.evolution.analysis.spring.universal.BytecodeAutoConfigScanner;
import com.evolution.analysis.spring.universal.FrameworkGeneration;
import com.evolution.analysis.spring.universal.SourceToSpringPlan;
import com.evolution.analysis.spring.universal.SpringAopMetadataExtractor;
import com.evolution.analysis.spring.universal.SpringProxyTrapDetector;
import com.evolution.analysis.spring.universal.SpringSourceEvidence;
import com.evolution.analysis.spring.universal.SpringXmlBeanExtractor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import static org.junit.jupiter.api.Assertions.*;

class SpringXmlAopEndToEndTest {

    @Test
    void endToEndGetBeanFoldingXmlBeansBytecodeStarterAopOrderingAndProxyTraps(@TempDir Path temp) throws Exception {
        // 1. Build a passive starter JAR containing a @Scope("prototype") @Qualifier bean + @Aspect
        Path starterJar = temp.resolve("acme-starter-1.0.0.jar");
        try (var zos = new ZipOutputStream(Files.newOutputStream(starterJar))) {
            putZip(zos, "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                    "com.acme.starter.StarterAutoConfig\n".getBytes(StandardCharsets.UTF_8));
            putZip(zos, "com/acme/starter/StarterAutoConfig.class", buildStarterAutoConfig());
            putZip(zos, "com/acme/starter/StarterAuditAspect.class", buildStarterAspect());
        }
        byte[] jarBytes = Files.readAllBytes(starterJar);
        var starterDep = new BinaryInput(
                new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "com.acme:acme-starter:1.0.0@jar",
                        ContentDigest.sha256(jarBytes)),
                starterJar);
        var bytecodeScan = BytecodeAutoConfigScanner.scan(
                List.of(starterDep), FrameworkGeneration.MetadataPolicy.IMPORTS,
                BytecodeAutoConfigScanner.Limits.defaults());
        assertEquals(1, bytecodeScan.aspects().size());

        // 2. Build captured repository sources (Java + Spring XML) in passive --build=off source-scoped mode
        Map<String, String> captured = new TreeMap<>();
        captured.put("build.gradle", "plugins { java }");
        captured.put("src/main/resources/applicationContext.xml", """
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="xmlGateway" class="com.acme.XmlGateway" scope="request" init-method="start" destroy-method="stop">
                        <property name="timeout" value="30"/>
                    </bean>
                </beans>
                """);
        captured.put("src/main/java/app/App.java", """
                package app;
                import org.springframework.beans.factory.BeanFactory;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.context.ApplicationContext;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.ComponentScan;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.context.annotation.Scope;
                import org.springframework.core.annotation.Order;
                import org.springframework.stereotype.Component;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.aspectj.lang.annotation.Aspect;
                import org.aspectj.lang.annotation.Before;
                import org.aspectj.lang.annotation.Around;
                import org.aspectj.lang.annotation.Pointcut;

                @ComponentScan("app")
                @Configuration
                class App {
                    @Bean @Scope("prototype") @Qualifier("fast")
                    Worker fastWorker() { return new Worker(); }

                    @Bean @Scope("singleton") @Qualifier("slow")
                    Worker slowWorker() { return new Worker(); }
                }

                class Worker {}

                @Aspect
                @Order(10)
                class TracingAspect {
                    @Pointcut("execution(* app.OrderService.*(..))")
                    void orderOps() {}

                    @Around("orderOps()")
                    Object traceAround() { return null; }

                    @Before("orderOps()")
                    void traceBefore() {}
                }

                @Service
                class OrderService {
                    static final String XML_BEAN = "xml" + "Gateway";

                    public void process(ApplicationContext ctx, BeanFactory bf, String dynamicName) {
                        ctx.getBean(XML_BEAN);
                        bf.getBean(Worker.class);
                        ctx.getBean(dynamicName);
                        this.persistOrder();
                    }

                    @Transactional
                    public void persistOrder() {}

                    @Transactional
                    private void hiddenTx() {}

                    @GetMapping("/orders")
                    public final String fetchOrders() { return "ok"; }
                }
                """);

        var files = new TreeMap<String, SourceInput>();
        captured.forEach((path, text) -> {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            SourceClassification cls = path.endsWith(".java") ? SourceClassification.MAIN : SourceClassification.OTHER;
            var doc = SourceDocument.create(TestInputs.REPO, TestInputs.MODULE, path, ContentDigest.sha256(bytes), cls);
            files.put(path, new SourceInput(doc, bytes));
        });
        var docs = files.values().stream().map(SourceInput::document).toList();
        var snapshot = RepositorySnapshot.create(TestInputs.REPO, Optional.empty(), false,
                docs.stream().map(SnapshotFile::from).toList(), docs);
        var inputs = new RepositoryInputs(snapshot, files);

        var build = new UniversalBuildIngestion().ingest(inputs,
                new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21), Optional.of("UTF-8"), 100, 10000, 1000, 16),
                Optional.empty(), Map.of(), new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16));
        var key = new UniversalSourceIngestion.SourceSet(build.modules().getFirst().descriptor().identity(), SourcePlanModel.Kind.MAIN);
        var component = new ManifestComponent(new VersionedIdentifier("test.wp21", "1"), ContentDigest.sha256Utf8("wp21"));
        var platform = ComponentIngestionTest.fixture(Map.of("app/Stub.java", "package app; class Stub {}"), false)
                .request().platform();
        var assembled = new UniversalSourceIngestion().assemble(inputs, build,
                Map.of(key, new UniversalSourceIngestion.Resolution(
                        platform, List.of(), ContentDigest.sha256Utf8("empty-cp"), Optional.empty(), false)),
                component, component, com.evolution.analysis.graph.KnowledgeGraph.schemaComponent());
        var pipeline = new UniversalIngestionPipeline();
        var run = pipeline.run(assembled, new JavaParserFrontend(), JavaParserFrontend.PROVIDER, 100, 100,
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);
        var unit = run.units().stream().filter(u -> u.sourceSet().equals(key)).findFirst().orElseThrow();
        var req = assembled.outcomes().stream().filter(o -> o.sourceSet().equals(key)).findFirst().orElseThrow().request().orElseThrow();
        FrontendResult frontend = unit.frontend().orElseThrow();

        // Verify Extraction emitted spring.bean-lookup diagnostics for constant & dynamic getBean() calls
        var lookupDiags = frontend.diagnostics().stream()
                .filter(d -> "spring.bean-lookup".equals(d.code())).toList();
        assertEquals(3, lookupDiags.size(), () -> "Expected 3 getBean() diagnostics: " + lookupDiags);
        assertTrue(lookupDiags.stream().anyMatch(d -> "xmlGateway".equals(d.details().get("beanName"))
                && "false".equals(d.details().get("dynamic"))));
        assertTrue(lookupDiags.stream().anyMatch(d -> d.details().getOrDefault("beanType", "").endsWith("Worker")
                && "false".equals(d.details().get("dynamic"))));
        assertTrue(lookupDiags.stream().anyMatch(d -> "true".equals(d.details().get("dynamic"))
                && "DYNAMIC_BEAN_LOOKUP_UNRESOLVED".equals(d.details().get("reasonCode"))));

        // 3. End-to-end SourceToSpringPlan with XML + Bytecode starter supplemental inputs
        var xmlResult = SpringXmlBeanExtractor.extract(inputs);
        var framework = new SpringFrameworkEvidence(false, Optional.of(req.plan().classpathManifest()), List.of());
        var sourceEvidence = new SpringSourceEvidence(req, frontend, framework,
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);
        var springBuild = SpringBuildContext.fromExactRequest(req, assembled.identity());
        var scopeEv = new ConditionEvidence.Derived(List.of(sourceEvidence.identity()), SourceToSpringPlan.PROVIDER, "test");

        var plan = SourceToSpringPlan.normalizeAutoScoped(
                springBuild, sourceEvidence, unit.components().orElseThrow(), unit.constructors().orElseThrow(),
                "wp21-container", scopeEv, SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED,
                500, SourceToSpringPlan.SupplementalInputs.of(xmlResult, bytecodeScan));

        // Check @Scope & @Qualifier preservation without singleton conflation
        var fastMeta = plan.beanMetadata().stream()
                .filter(b -> b.beanId().equals("fastWorker")).findFirst().orElseThrow();
        var slowMeta = plan.beanMetadata().stream()
                .filter(b -> b.beanId().equals("slowWorker")).findFirst().orElseThrow();
        assertEquals("prototype", fastMeta.scope());
        assertEquals(List.of("fast"), fastMeta.qualifiers());
        assertEquals("singleton", slowMeta.scope());
        assertEquals(List.of("slow"), slowMeta.qualifiers());

        // Check XML bean + Bytecode starter bean wired end-to-end
        assertTrue(plan.beanMetadata().stream().anyMatch(b -> b.beanId().equals("xmlGateway")
                && b.scope().equals("request") && b.sourceKind().equals("XML_BEAN") && b.trustTier() == TrustTier.SOURCE_SYNTAX));
        assertTrue(plan.starterBeans().size() == 1 && plan.beanMetadata().stream().anyMatch(b ->
                b.beanId().equals("starterWorker") && b.scope().equals("prototype") && b.sourceKind().equals("STARTER_BYTECODE")));

        // Check getBean() RESOLVES_BEAN edges & dynamic lookup gap
        assertEquals(3, plan.xmlBeans().beanLookups().size());
        assertTrue(plan.xmlBeans().beanLookups().stream().anyMatch(e -> !e.dynamic() && "xmlGateway".equals(e.resolvedBeanId())));
        assertTrue(plan.gaps().stream().anyMatch(g -> SpringXmlBeanExtractor.GAP_DYNAMIC_BEAN_LOOKUP.equals(g.reasonCode())));

        // Check AOP edges from BOTH bytecode StarterAuditAspect (@Order 1) AND source TracingAspect (@Order 10)
        var persistAdvices = plan.aopMetadata().advicesForMethod("app.OrderService", "persistOrder");
        assertEquals(3, persistAdvices.size(), () -> "Expected 1 bytecode + 2 source advices: " + persistAdvices);
        assertEquals("com.acme.starter.StarterAuditAspect", persistAdvices.get(0).aspectClass());
        assertEquals("app.TracingAspect", persistAdvices.get(1).aspectClass());
        assertEquals(SpringAopMetadataExtractor.AdviceKind.AROUND, persistAdvices.get(1).adviceKind());
        assertEquals(SpringAopMetadataExtractor.AdviceKind.BEFORE, persistAdvices.get(2).adviceKind());

        // Check BOTH static proxy trap detectors fired on OrderService!
        assertTrue(plan.proxyTraps().selfInvocationFindings().stream()
                .anyMatch(f -> f.targetMethod().equals("persistOrder") && f.callerMethod().equals("process")),
                () -> "Expected self-invocation bypass on persistOrder: " + plan.proxyTraps().findings());
        assertTrue(plan.proxyTraps().cglibPrivateFinalFindings().stream()
                .anyMatch(f -> f.targetMethod().equals("hiddenTx")),
                () -> "Expected CGLIB private trap on hiddenTx: " + plan.proxyTraps().findings());
        assertTrue(plan.proxyTraps().cglibPrivateFinalFindings().stream()
                .anyMatch(f -> f.targetMethod().equals("fetchOrders")),
                () -> "Expected CGLIB final trap on fetchOrders: " + plan.proxyTraps().findings());
    }

    private static void putZip(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static byte[] buildStarterAutoConfig() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/acme/starter/StarterAutoConfig", null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/springframework/boot/autoconfigure/AutoConfiguration;", true).visitEnd();
        var imp = cw.visitAnnotation("Lorg/springframework/context/annotation/Import;", true);
        var arr = imp.visitArray("value");
        arr.visit(null, Type.getObjectType("com/acme/starter/StarterAuditAspect"));
        arr.visitEnd();
        imp.visitEnd();

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "starterWorker", "()Ljava/lang/Object;", null, null);
        mv.visitAnnotation("Lorg/springframework/context/annotation/Bean;", true).visitEnd();
        var sc = mv.visitAnnotation("Lorg/springframework/context/annotation/Scope;", true);
        sc.visit("value", "prototype");
        sc.visitEnd();
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildStarterAspect() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/acme/starter/StarterAuditAspect", null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/aspectj/lang/annotation/Aspect;", true).visitEnd();
        var ord = cw.visitAnnotation("Lorg/springframework/core/annotation/Order;", true);
        ord.visit("value", 1);
        ord.visitEnd();

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "auditTx", "()V", null, null);
        var before = mv.visitAnnotation("Lorg/aspectj/lang/annotation/Before;", true);
        before.visit("value", "@annotation(org.springframework.transaction.annotation.Transactional)");
        before.visitEnd();
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
