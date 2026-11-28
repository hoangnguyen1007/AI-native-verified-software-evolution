package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.frontend.BinaryInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import static org.junit.jupiter.api.Assertions.*;

class BytecodeAutoConfigScannerTest {

    @Test
    void extractsFactoriesImportsInnerConfigsConditionsBeansOrderingAndProvenanceDeterministically(@TempDir Path temp)
            throws Exception {
        byte[] sampleAutoBytes = buildSampleAutoConfigClass();
        byte[] innerConfigBytes = buildInnerConfigClass();
        byte[] importedConfigBytes = buildImportedConfigClass();
        byte[] legacyAutoBytes = buildLegacyFactoryAutoConfigClass();

        Path jar1 = temp.resolve("starter-modern-1.0.0.jar");
        try (var zos = new ZipOutputStream(Files.newOutputStream(jar1))) {
            putEntry(zos, "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                    "# comment\ncom.acme.starter.SampleAutoConfiguration\n".getBytes(StandardCharsets.UTF_8));
            putEntry(zos, "com/acme/starter/SampleAutoConfiguration.class", sampleAutoBytes);
            putEntry(zos, "com/acme/starter/SampleAutoConfiguration$NestedClientConfig.class", innerConfigBytes);
            putEntry(zos, "com/acme/starter/ImportedExtraConfig.class", importedConfigBytes);
            putEntry(zos, "com/acme/starter/CorruptMissing.class", "not-a-class".getBytes(StandardCharsets.UTF_8));
        }

        Path jar2 = temp.resolve("starter-legacy-1.0.0.jar");
        try (var zos = new ZipOutputStream(Files.newOutputStream(jar2))) {
            putEntry(zos, "META-INF/spring.factories",
                    "org.springframework.boot.autoconfigure.EnableAutoConfiguration=com.acme.legacy.LegacyCacheAutoConfiguration,com.acme.legacy.MissingTarget\n"
                            .getBytes(StandardCharsets.UTF_8));
            putEntry(zos, "com/acme/legacy/LegacyCacheAutoConfiguration.class", legacyAutoBytes);
            putEntry(zos, "com/acme/legacy/CorruptClass.class", new byte[] { 1, 2, 3, 4 });
        }

        // Also test corrupt class directly as a declared seed to confirm fail-closed ScanProblem
        Path jar3 = temp.resolve("starter-corrupt-1.0.0.jar");
        try (var zos = new ZipOutputStream(Files.newOutputStream(jar3))) {
            putEntry(zos, "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                    "com.acme.broken.BrokenAutoConfiguration\n".getBytes(StandardCharsets.UTF_8));
            putEntry(zos, "com/acme/broken/BrokenAutoConfiguration.class", new byte[] { 0x01, 0x02, 0x03 });
        }

        var dep1 = binaryInput("com.acme:starter-modern:1.0.0", jar1);
        var dep2 = binaryInput("com.acme:starter-legacy:1.0.0", jar2);
        var dep3 = binaryInput("com.acme:starter-corrupt:1.0.0", jar3);

        var limits = BytecodeAutoConfigScanner.Limits.defaults();
        var forward = BytecodeAutoConfigScanner.scan(
                List.of(dep1, dep2, dep3), FrameworkGeneration.MetadataPolicy.BOTH, limits);
        var reversed = BytecodeAutoConfigScanner.scan(
                List.of(dep3, dep2, dep1), FrameworkGeneration.MetadataPolicy.BOTH, limits);

        assertEquals(forward.identity(), reversed.identity(), "ScanResult digest must be input-order independent");
        assertEquals(3, forward.scannedJars());
        assertEquals(4, forward.configurations().size(), () -> "configs=" + forward.configurations());
        assertTrue(forward.totalBeans() >= 3);
        assertTrue(forward.problems().stream().anyMatch(p -> p.reason().equals("ENTRY_MISSING")
                && p.entryPath().contains("MissingTarget")));
        assertTrue(forward.problems().stream().anyMatch(p -> p.reason().equals("CLASS_UNREADABLE")
                && p.entryPath().contains("BrokenAutoConfiguration")));

        var sampleCfg = forward.configurations().stream()
                .filter(c -> c.classFqn().equals("com.acme.starter.SampleAutoConfiguration"))
                .findFirst()
                .orElseThrow();
        assertTrue(sampleCfg.autoConfiguration());
        assertFalse(sampleCfg.proxyBeanMethods());
        assertEquals(ContentDigest.sha256(sampleAutoBytes), sampleCfg.evidence().entryDigest());
        assertTrue(sampleCfg.importedClasses().contains("com.acme.starter.ImportedExtraConfig"));
        assertTrue(sampleCfg.nestedConfigurationClasses()
                .contains("com.acme.starter.SampleAutoConfiguration$NestedClientConfig"));
        assertFalse(sampleCfg.orderings().isEmpty());
        assertEquals(1, sampleCfg.beans().size());
        var primaryBean = sampleCfg.beans().getFirst();
        assertTrue(primaryBean.primary());
        assertEquals("com.acme.api.SampleClient", primaryBean.returnTypeFqn());
        assertEquals(List.of("com.acme.api.MetricsRegistry"), primaryBean.parameterTypeFqns());

        // Check IMPORTS-only vs FACTORIES-only vs NONE policies
        var importsOnly = BytecodeAutoConfigScanner.scan(
                List.of(dep1, dep2), FrameworkGeneration.MetadataPolicy.IMPORTS, limits);
        assertTrue(importsOnly.configurations().stream()
                .noneMatch(c -> c.classFqn().contains("LegacyCacheAutoConfiguration")));
        var factoriesOnly = BytecodeAutoConfigScanner.scan(
                List.of(dep1, dep2), FrameworkGeneration.MetadataPolicy.FACTORIES, limits);
        assertTrue(factoriesOnly.configurations().stream()
                .noneMatch(c -> c.classFqn().contains("SampleAutoConfiguration")));
        var nonePolicy = BytecodeAutoConfigScanner.scan(
                List.of(dep1, dep2), FrameworkGeneration.MetadataPolicy.NONE, limits);
        assertEquals(0, nonePolicy.configurations().size());

        // Go/No-Go Interaction + COI analysis
        var user = new BytecodeAutoConfigScanner.UserSpringSummary(
                Set.of("com.acme.api.SampleClient"),
                Set.of("customCache"),
                Set.of("com.acme.api.NestedHelper"),
                Map.of("acme.client.enabled", "true"),
                Set.of("cloud"),
                Set.of());
        var spike = BytecodeAutoConfigScanner.analyzeInteractions(forward, user);
        assertTrue(spike.g1UserStarterInteractions() >= 3, () -> "breakdown=" + spike.interactionBreakdown());
        assertTrue(spike.g2ConditionalFindings() >= 2);
        assertTrue(spike.g3CoiMetrics().componentCount() >= 1);
        assertTrue(spike.g3CoiMetrics().maxComponentVars() <= 64);
        assertTrue(spike.toMap().containsKey("goNoGoRecommendation"));
    }

    @Test
    void empiricalOfflineSpringBootStarterJarsSpike() throws Exception {
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        Path bootAutoDir = m2.resolve("org/springframework/boot/spring-boot-autoconfigure");
        Assumptions.assumeTrue(Files.isDirectory(bootAutoDir), "Offline spring-boot-autoconfigure not in ~/.m2");

        List<Path> candidates;
        try (var stream = Files.walk(bootAutoDir, 2)) {
            candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        Assumptions.assumeTrue(!candidates.isEmpty(), "No spring-boot-autoconfigure JAR found in ~/.m2");

        Path selectedBootJar = candidates.stream()
                .filter(p -> p.getFileName().toString().contains("3.5."))
                .findFirst()
                .orElse(candidates.getLast());

        var deps = new ArrayList<BinaryInput>();
        deps.add(binaryInput("org.springframework.boot:spring-boot-autoconfigure", selectedBootJar));

        Path cloudCommonsDir = m2.resolve("org/springframework/cloud/spring-cloud-commons");
        if (Files.isDirectory(cloudCommonsDir)) {
            try (var stream = Files.walk(cloudCommonsDir, 2)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                        .filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
                        .sorted()
                        .findFirst()
                        .ifPresent(p -> {
                            try {
                                deps.add(binaryInput("org.springframework.cloud:spring-cloud-commons", p));
                            } catch (IOException ignored) {
                            }
                        });
            }
        }

        var result = BytecodeAutoConfigScanner.scan(
                deps, FrameworkGeneration.MetadataPolicy.BOTH, BytecodeAutoConfigScanner.Limits.defaults());
        var reversedDeps = new ArrayList<>(deps);
        Collections.reverse(reversedDeps);
        var resultRev = BytecodeAutoConfigScanner.scan(
                reversedDeps, FrameworkGeneration.MetadataPolicy.BOTH, BytecodeAutoConfigScanner.Limits.defaults());

        assertEquals(result.identity(), resultRev.identity(), "Real starter JAR scan digest must be order-independent");
        assertTrue(result.configurations().size() >= 100,
                () -> "Expected >=100 auto-configurations, got " + result.configurations().size());
        assertTrue(result.totalBeans() >= 100, () -> "Expected >=100 @Beans, got " + result.totalBeans());
        assertTrue(result.totalConditionMarkers() >= 300,
                () -> "Expected >=300 conditional markers, got " + result.totalConditionMarkers());
        assertTrue(result.elapsedMs() < 5_000, () -> "Scan exceeded 5000ms: " + result.elapsedMs());
        assertTrue(result.problems().stream().noneMatch(p -> p.reason().equals("JAR_INVALID")));

        var spike = BytecodeAutoConfigScanner.analyzeInteractions(result,
                new BytecodeAutoConfigScanner.UserSpringSummary(
                        Set.of(), Set.of(), Set.of(),
                        Map.of("spring.threads.virtual.enabled", "true"),
                        Set.of("prod"), Set.of()));
        assertTrue(spike.g3CoiMetrics().componentCount() >= 1);
        assertTrue(spike.g3CoiMetrics().maxComponentVars() < spike.g3CoiMetrics().totalStarterVars(),
                "COI should decompose global starter variables into smaller components");

        System.out.println("[S1-EMPIRICAL] jar=" + selectedBootJar.getFileName()
                + " configs=" + result.configurations().size()
                + " beans=" + result.totalBeans()
                + " condMarkers=" + result.totalConditionMarkers()
                + " orderings=" + result.totalOrderingAnnotations()
                + " scanMs=" + result.elapsedMs()
                + " coiComponents=" + spike.g3CoiMetrics().componentCount()
                + " totalVars=" + spike.g3CoiMetrics().totalStarterVars()
                + " resolvedClassConds=" + spike.g3CoiMetrics().resolvedClassConditions()
                + " maxCompVars=" + spike.g3CoiMetrics().maxComponentVars());
    }

    @Test
    void expandsComposedMetaAnnotationsWithAliasForAndGuardsCyclesDeterministically(@TempDir Path temp)
            throws Exception {
        Path jar = temp.resolve("meta-starter-1.0.0.jar");
        try (var zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            putEntry(zos, "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                    "com.acme.meta.ComposedAutoConfiguration\n".getBytes(StandardCharsets.UTF_8));
            putEntry(zos, "com/acme/meta/ComposedAutoConfiguration.class", buildMetaUsingAutoConfig());
            putEntry(zos, "com/acme/meta/ConditionalOnCloudFeature.class", buildMetaAnnLevel1());
            putEntry(zos, "com/acme/meta/ConditionalOnFeatureFlag.class", buildMetaAnnLevel2());
            putEntry(zos, "com/acme/meta/CyclicAnnA.class", buildCyclicAnn("CyclicAnnA", "CyclicAnnB"));
            putEntry(zos, "com/acme/meta/CyclicAnnB.class", buildCyclicAnn("CyclicAnnB", "CyclicAnnA"));
            putEntry(zos, "com/acme/meta/OpaqueMetaCondition.class", buildOpaqueMetaAnn());
        }

        var dep = binaryInput("com.acme:meta-starter:1.0.0", jar);
        var result = BytecodeAutoConfigScanner.scan(
                List.of(dep), FrameworkGeneration.MetadataPolicy.IMPORTS, BytecodeAutoConfigScanner.Limits.defaults());
        assertEquals(1, result.configurations().size());
        var cfg = result.configurations().getFirst();
        assertTrue(result.metaExpandedConditionCount() >= 3,
                () -> "Expected >=3 meta-expanded conditions, got " + result.metaExpandedConditionCount());

        var propCond = cfg.conditions().stream()
                .filter(c -> c.annotationFqn().equals(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty"))
                .findFirst().orElseThrow();
        assertEquals(List.of("com.acme.meta.ConditionalOnCloudFeature"),
                propCond.attributes().get("_composedFrom"));
        assertEquals(List.of("acme.cloud"), propCond.attributes().get("prefix"));
        assertEquals(List.of("routing.enabled"), propCond.attributes().get("name"));

        var profCond = cfg.conditions().stream()
                .filter(c -> c.annotationFqn().equals("org.springframework.context.annotation.Profile"))
                .findFirst().orElseThrow();
        assertEquals(List.of("cloud"), profCond.attributes().get("value"));
        assertEquals(List.of("com.acme.meta.ConditionalOnCloudFeature"),
                profCond.attributes().get("_composedFrom"));

        var opaqueCond = cfg.conditions().stream()
                .filter(c -> c.annotationFqn().equals("org.springframework.context.annotation.Conditional"))
                .findFirst().orElseThrow();
        assertTrue(opaqueCond.opaque());
        assertEquals(List.of("com.acme.meta.OpaqueMetaCondition"),
                opaqueCond.attributes().get("_composedFrom"));

        var spike = BytecodeAutoConfigScanner.analyzeInteractions(result,
                new BytecodeAutoConfigScanner.UserSpringSummary(
                        Set.of(), Set.of(), Set.of(),
                        Map.of("acme.cloud.routing.enabled", "true"),
                        Set.of("cloud"), Set.of()));
        assertTrue(spike.g1UserStarterInteractions() >= 2, () -> "breakdown=" + spike.interactionBreakdown());
        assertTrue(spike.g2ConditionalFindings() >= 2);
    }

    @Test
    void empiricalOfflineBoot4AndSpringCloudMetaAnnotationSpike() throws Exception {
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        Path bootRoot = m2.resolve("org/springframework/boot");
        Path cloudCommons = m2.resolve("org/springframework/cloud/spring-cloud-commons/4.3.0/spring-cloud-commons-4.3.0.jar");
        Assumptions.assumeTrue(Files.isDirectory(bootRoot) && Files.isRegularFile(cloudCommons),
                "Offline Boot 4.1.2 + Spring Cloud 4.3.0 JARs not found in ~/.m2");

        var deps = new ArrayList<BinaryInput>();
        try (var stream = Files.walk(bootRoot, 3)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith("-4.1.2.jar")
                                && !n.contains("-test")
                                && !n.endsWith("-sources.jar")
                                && !n.endsWith("-javadoc.jar");
                    })
                    .sorted()
                    .forEach(p -> {
                        try {
                            String art = p.getParent().getParent().getFileName().toString();
                            deps.add(binaryInput("org.springframework.boot:" + art + ":4.1.2", p));
                        } catch (IOException ignored) {
                        }
                    });
        }
        Assumptions.assumeTrue(!deps.isEmpty(), "No Boot 4.1.2 JARs found in ~/.m2");
        deps.add(binaryInput("org.springframework.cloud:spring-cloud-commons:4.3.0", cloudCommons));
        Path cloudCfgClient = m2.resolve("org/springframework/cloud/spring-cloud-config-client/4.2.0/spring-cloud-config-client-4.2.0.jar");
        if (Files.isRegularFile(cloudCfgClient)) {
            deps.add(binaryInput("org.springframework.cloud:spring-cloud-config-client:4.2.0", cloudCfgClient));
        }
        Path springAiRoot = m2.resolve("org/springframework/ai");
        if (Files.isDirectory(springAiRoot)) {
            try (var stream = Files.walk(springAiRoot, 3)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                        .filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
                        .sorted()
                        .forEach(p -> {
                            try {
                                String art = p.getParent().getParent().getFileName().toString();
                                deps.add(binaryInput("org.springframework.ai:" + art, p));
                            } catch (IOException ignored) {
                            }
                        });
            }
        }

        var forward = BytecodeAutoConfigScanner.scan(
                deps, FrameworkGeneration.MetadataPolicy.BOTH, BytecodeAutoConfigScanner.Limits.defaults());
        var reversedDeps = new ArrayList<>(deps);
        Collections.reverse(reversedDeps);
        var reversed = BytecodeAutoConfigScanner.scan(
                reversedDeps, FrameworkGeneration.MetadataPolicy.BOTH, BytecodeAutoConfigScanner.Limits.defaults());

        assertEquals(forward.identity(), reversed.identity(), "Boot4 + Cloud scan digest must be deterministic");
        assertTrue(forward.configurations().size() >= 50,
                () -> "Expected >=50 modular Boot4+Cloud configs, got " + forward.configurations().size());
        assertTrue(forward.metaExpandedConditionCount() > 0,
                "Expected meta-annotation expansion across Spring Cloud / Boot 4 annotations");
        assertTrue(forward.elapsedMs() < 5_000, () -> "Elapsed ms exceeded budget: " + forward.elapsedMs());

        System.out.println("[S2-EMPIRICAL] jars=" + forward.scannedJars()
                + " configs=" + forward.configurations().size()
                + " beans=" + forward.totalBeans()
                + " condMarkers=" + forward.totalConditionMarkers()
                + " metaExpanded=" + forward.metaExpandedConditionCount()
                + " scanMs=" + forward.elapsedMs());
    }

    @Test
    void extractsBytecodeScopeQualifierAndAspectMetadataDeterministically(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("aop-scope-starter-1.0.0.jar");
        try (var zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            putEntry(zos, "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                    "com.acme.aopstarter.AopAutoConfiguration\n".getBytes(StandardCharsets.UTF_8));
            putEntry(zos, "com/acme/aopstarter/AopAutoConfiguration.class", buildScopedAndImportingAutoConfig());
            putEntry(zos, "com/acme/aopstarter/MetricsAspect.class", buildBytecodeAspectClass());
        }

        var dep = binaryInput("com.acme:aop-scope-starter:1.0.0", jar);
        var result = BytecodeAutoConfigScanner.scan(
                List.of(dep), FrameworkGeneration.MetadataPolicy.IMPORTS, BytecodeAutoConfigScanner.Limits.defaults());

        var cfg = result.configurations().stream()
                .filter(c -> c.classFqn().equals("com.acme.aopstarter.AopAutoConfiguration"))
                .findFirst().orElseThrow();
        assertEquals(1, cfg.beans().size());
        var scopedBean = cfg.beans().getFirst();
        assertEquals("prototype", scopedBean.scope());
        assertEquals(List.of("fastPool"), scopedBean.qualifiers());

        assertEquals(1, result.aspects().size());
        var aspect = result.aspects().getFirst();
        assertEquals("com.acme.aopstarter.MetricsAspect", aspect.classFqn());
        assertEquals(5, aspect.order());
        assertEquals(1, aspect.pointcuts().size());
        assertEquals("execution(* com.acme.service..*(..))", aspect.pointcuts().getFirst().expression());
        assertEquals(2, aspect.advices().size());
        assertTrue(aspect.advices().stream().anyMatch(a -> "AROUND".equals(a.adviceKind()) && "serviceCalls()".equals(a.pointcutExpression())));
        assertTrue(aspect.advices().stream().anyMatch(a -> "BEFORE".equals(a.adviceKind())));

        // Verify passive full-JAR scanAspects() discovers the same aspect independently
        var scannedAspects = BytecodeAutoConfigScanner.scanAspects(List.of(dep), BytecodeAutoConfigScanner.Limits.defaults());
        assertEquals(1, scannedAspects.size());
        assertEquals(aspect, scannedAspects.getFirst());
    }

    private static byte[] buildScopedAndImportingAutoConfig() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/acme/aopstarter/AopAutoConfiguration",
                null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/springframework/boot/autoconfigure/AutoConfiguration;", true).visitEnd();
        var imp = cw.visitAnnotation("Lorg/springframework/context/annotation/Import;", true);
        var arr = imp.visitArray("value");
        arr.visit(null, Type.getObjectType("com/acme/aopstarter/MetricsAspect"));
        arr.visitEnd();
        imp.visitEnd();

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "workerPool", "()Ljava/lang/Object;", null, null);
        mv.visitAnnotation("Lorg/springframework/context/annotation/Bean;", true).visitEnd();
        var scope = mv.visitAnnotation("Lorg/springframework/context/annotation/Scope;", true);
        scope.visit("value", "prototype");
        scope.visitEnd();
        var qual = mv.visitAnnotation("Lorg/springframework/beans/factory/annotation/Qualifier;", true);
        qual.visit("value", "fastPool");
        qual.visitEnd();
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildBytecodeAspectClass() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/acme/aopstarter/MetricsAspect",
                null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/aspectj/lang/annotation/Aspect;", true).visitEnd();
        var order = cw.visitAnnotation("Lorg/springframework/core/annotation/Order;", true);
        order.visit("value", 5);
        order.visitEnd();

        var pc = cw.visitMethod(Opcodes.ACC_PUBLIC, "serviceCalls", "()V", null, null);
        var pcAnn = pc.visitAnnotation("Lorg/aspectj/lang/annotation/Pointcut;", true);
        pcAnn.visit("value", "execution(* com.acme.service..*(..))");
        pcAnn.visitEnd();
        pc.visitEnd();

        var around = cw.visitMethod(Opcodes.ACC_PUBLIC, "measure", "()Ljava/lang/Object;", null, null);
        var aroundAnn = around.visitAnnotation("Lorg/aspectj/lang/annotation/Around;", true);
        aroundAnn.visit("value", "serviceCalls()");
        aroundAnn.visitEnd();
        around.visitEnd();

        var before = cw.visitMethod(Opcodes.ACC_PUBLIC, "tag", "()V", null, null);
        var beforeAnn = before.visitAnnotation("Lorg/aspectj/lang/annotation/Before;", true);
        beforeAnn.visit("value", "@annotation(com.acme.Tracked)");
        beforeAnn.visitEnd();
        before.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildMetaUsingAutoConfig() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/acme/meta/ComposedAutoConfiguration",
                null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/springframework/boot/autoconfigure/AutoConfiguration;", true).visitEnd();
        var cf = cw.visitAnnotation("Lcom/acme/meta/ConditionalOnCloudFeature;", true);
        cf.visit("feature", "routing.enabled");
        cf.visitEnd();
        cw.visitAnnotation("Lcom/acme/meta/CyclicAnnA;", true).visitEnd();
        cw.visitAnnotation("Lcom/acme/meta/OpaqueMetaCondition;", true).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildMetaAnnLevel1() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION,
                "com/acme/meta/ConditionalOnCloudFeature", null, "java/lang/Object",
                new String[] { "java/lang/annotation/Annotation" });
        var l2 = cw.visitAnnotation("Lcom/acme/meta/ConditionalOnFeatureFlag;", true);
        l2.visit("prefix", "acme.cloud");
        l2.visitEnd();
        var prof = cw.visitAnnotation("Lorg/springframework/context/annotation/Profile;", true);
        var parr = prof.visitArray("value");
        parr.visit(null, "cloud");
        parr.visitEnd();
        prof.visitEnd();

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "feature", "()Ljava/lang/String;", null, null);
        var alias = mv.visitAnnotation("Lorg/springframework/core/annotation/AliasFor;", true);
        alias.visit("annotation", Type.getObjectType("com/acme/meta/ConditionalOnFeatureFlag"));
        alias.visit("attribute", "flagName");
        alias.visitEnd();
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildMetaAnnLevel2() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION,
                "com/acme/meta/ConditionalOnFeatureFlag", null, "java/lang/Object",
                new String[] { "java/lang/annotation/Annotation" });
        var prop = cw.visitAnnotation(
                "Lorg/springframework/boot/autoconfigure/condition/ConditionalOnProperty;", true);
        prop.visit("havingValue", "true");
        prop.visitEnd();

        var mPrefix = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "prefix", "()Ljava/lang/String;", null, null);
        var aliasP = mPrefix.visitAnnotation("Lorg/springframework/core/annotation/AliasFor;", true);
        aliasP.visit("annotation", Type.getObjectType("org/springframework/boot/autoconfigure/condition/ConditionalOnProperty"));
        aliasP.visit("attribute", "prefix");
        aliasP.visitEnd();
        mPrefix.visitEnd();

        var mFlag = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "flagName", "()Ljava/lang/String;", null, null);
        var aliasF = mFlag.visitAnnotation("Lorg/springframework/core/annotation/AliasFor;", true);
        aliasF.visit("annotation", Type.getObjectType("org/springframework/boot/autoconfigure/condition/ConditionalOnProperty"));
        aliasF.visit("attribute", "name");
        aliasF.visitEnd();
        var defF = mFlag.visitAnnotationDefault();
        defF.visit(null, "default.flag");
        defF.visitEnd();
        mFlag.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildCyclicAnn(String selfSimple, String otherSimple) {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION,
                "com/acme/meta/" + selfSimple, null, "java/lang/Object",
                new String[] { "java/lang/annotation/Annotation" });
        cw.visitAnnotation("Lcom/acme/meta/" + otherSimple + ";", true).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildOpaqueMetaAnn() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION,
                "com/acme/meta/OpaqueMetaCondition", null, "java/lang/Object",
                new String[] { "java/lang/annotation/Annotation" });
        var cond = cw.visitAnnotation("Lorg/springframework/context/annotation/Conditional;", true);
        var arr = cond.visitArray("value");
        arr.visit(null, Type.getObjectType("com/acme/meta/CustomConditionImpl"));
        arr.visitEnd();
        cond.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static BinaryInput binaryInput(String coord, Path jar) throws IOException {
        ContentDigest digest = ContentDigest.sha256(Files.readAllBytes(jar));
        var entry = new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, coord, digest);
        return new BinaryInput(entry, jar);
    }

    private static void putEntry(ZipOutputStream zos, String name, byte[] bytes) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
    }

    private static byte[] buildSampleAutoConfigClass() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/acme/starter/SampleAutoConfiguration",
                null, "java/lang/Object", null);
        cw.visitInnerClass("com/acme/starter/SampleAutoConfiguration$NestedClientConfig",
                "com/acme/starter/SampleAutoConfiguration", "NestedClientConfig",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);

        var autoAnn = cw.visitAnnotation("Lorg/springframework/boot/autoconfigure/AutoConfiguration;", true);
        autoAnn.visit("proxyBeanMethods", Boolean.FALSE);
        var afterArr = autoAnn.visitArray("afterName");
        afterArr.visit(null, "com.acme.legacy.LegacyCacheAutoConfiguration");
        afterArr.visitEnd();
        autoAnn.visitEnd();

        var impAnn = cw.visitAnnotation("Lorg/springframework/context/annotation/Import;", true);
        var impArr = impAnn.visitArray("value");
        impArr.visit(null, Type.getObjectType("com/acme/starter/ImportedExtraConfig"));
        impArr.visitEnd();
        impAnn.visitEnd();

        var propAnn = cw.visitAnnotation(
                "Lorg/springframework/boot/autoconfigure/condition/ConditionalOnProperty;", true);
        propAnn.visit("prefix", "acme.client");
        var nameArr = propAnn.visitArray("name");
        nameArr.visit(null, "enabled");
        nameArr.visitEnd();
        propAnn.visit("havingValue", "true");
        propAnn.visitEnd();

        var orderAnn = cw.visitAnnotation(
                "Lorg/springframework/boot/autoconfigure/AutoConfigureBefore;", true);
        var ordArr = orderAnn.visitArray("value");
        ordArr.visit(null, Type.getObjectType("com/acme/starter/ImportedExtraConfig"));
        ordArr.visitEnd();
        orderAnn.visitEnd();

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "sampleClient",
                "(Lcom/acme/api/MetricsRegistry;)Lcom/acme/api/SampleClient;", null, null);
        mv.visitAnnotation("Lorg/springframework/context/annotation/Bean;", true).visitEnd();
        mv.visitAnnotation("Lorg/springframework/context/annotation/Primary;", true).visitEnd();
        var missingAnn = mv.visitAnnotation(
                "Lorg/springframework/boot/autoconfigure/condition/ConditionalOnMissingBean;", true);
        var missArr = missingAnn.visitArray("value");
        missArr.visit(null, Type.getObjectType("com/acme/api/SampleClient"));
        missArr.visitEnd();
        missingAnn.visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildInnerConfigClass() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "com/acme/starter/SampleAutoConfiguration$NestedClientConfig",
                null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/springframework/context/annotation/Configuration;", true).visitEnd();
        var profAnn = cw.visitAnnotation("Lorg/springframework/context/annotation/Profile;", true);
        var arr = profAnn.visitArray("value");
        arr.visit(null, "cloud");
        arr.visitEnd();
        profAnn.visitEnd();

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "nestedHelper", "()Lcom/acme/api/NestedHelper;", null, null);
        mv.visitAnnotation("Lorg/springframework/context/annotation/Bean;", true).visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildImportedConfigClass() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/acme/starter/ImportedExtraConfig",
                null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/springframework/context/annotation/Configuration;", true).visitEnd();
        var condAnn = cw.visitAnnotation("Lorg/springframework/context/annotation/Conditional;", true);
        var arr = condAnn.visitArray("value");
        arr.visit(null, Type.getObjectType("com/acme/starter/CustomOpaqueCondition"));
        arr.visitEnd();
        condAnn.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildLegacyFactoryAutoConfigClass() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/acme/legacy/LegacyCacheAutoConfiguration",
                null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/springframework/context/annotation/Configuration;", true).visitEnd();
        var onClass = cw.visitAnnotation(
                "Lorg/springframework/boot/autoconfigure/condition/ConditionalOnClass;", true);
        var clsArr = onClass.visitArray("name");
        clsArr.visit(null, "java.lang.String");
        clsArr.visitEnd();
        onClass.visitEnd();

        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "legacyCache", "()Ljava/lang/Object;", null, null);
        var beanAnn = mv.visitAnnotation("Lorg/springframework/context/annotation/Bean;", true);
        var bnArr = beanAnn.visitArray("name");
        bnArr.visit(null, "legacyCache");
        bnArr.visitEnd();
        beanAnn.visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
