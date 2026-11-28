package com.evolution.backend.analysis;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.frontend.BinaryInput;
import com.evolution.analysis.spring.condition.ConeOfInfluenceSlicer;
import com.evolution.analysis.spring.condition.StarterAutoConfigSatReasoner;
import com.evolution.analysis.spring.condition.StarterAutoConfigSatReasoner.Dimension;
import com.evolution.analysis.spring.truth.SlicedDdnnfCompiler;
import com.evolution.analysis.spring.truth.SlicedDdnnfCompiler.CompiledDdnnf;
import com.evolution.analysis.spring.truth.SlicedDdnnfCompiler.SliceBuilder;
import com.evolution.analysis.spring.truth.TruthRegionEvaluation;
import com.evolution.analysis.spring.universal.BytecodeAutoConfigScanner;
import com.evolution.analysis.spring.universal.FrameworkGeneration;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jmx.export.MBeanExporter;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fast WP2.5 d-DNNF-sampled {@link ApplicationContextRunner} differential oracle slice test.
 *
 * <p>Compiles real {@code spring-boot-autoconfigure} COI slices via {@link SlicedDdnnfCompiler},
 * draws seeded deterministic uniform samples and symmetric {@code (witness+, witness-)} pairs
 * for every {@code MAY} bean directly from the compiled d-DNNF DAG, and validates every sampled
 * world against live Spring Boot {@link ApplicationContextRunner} execution ({@code 0} False-MUST,
 * {@code 0} False-NEVER, 100% witness-pair agreement).
 */
class SpringDdnnfOracleSliceTest {

    @Configuration(proxyBeanMethods = false)
    static class UserAvailabilityOverrideConfig {
        @Bean
        ApplicationAvailability customApplicationAvailability() {
            return new ApplicationAvailabilityBean();
        }
    }

    private record SliceCase(
            String name,
            List<Class<?>> autoConfigs,
            List<Class<?>> userConfigs,
            Set<String> hiddenClasses,
            List<Dimension> dimensions,
            List<String> targetBeans) {}

    @Test
    void ddnnfSampledWitnessPairsMatchLiveSpringBootApplicationContextRunnerWithZeroFalseMustOrNever()
            throws Exception {
        Path bootJar = locateRuntimeBootAutoconfigureJar();
        var dep = new BinaryInput(
                new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                        "org.springframework.boot:spring-boot-autoconfigure:runtime",
                        ContentDigest.sha256(Files.readAllBytes(bootJar))),
                bootJar);
        var scan = BytecodeAutoConfigScanner.scan(
                List.of(dep), FrameworkGeneration.MetadataPolicy.IMPORTS, BytecodeAutoConfigScanner.Limits.defaults());
        Set<String> baseClasspath = indexRuntimeClasspathPassively();

        var cases = List.of(
                new SliceCase(
                        "Jmx_PropertyMatrix",
                        List.of(JmxAutoConfiguration.class),
                        List.of(),
                        Set.of(),
                        List.of(Dimension.property("spring.jmx.enabled",
                                List.of(Optional.empty(), Optional.of("false"), Optional.of("true")))),
                        List.of("mbeanExporter", "objectNamingStrategy", "mbeanServer")),
                new SliceCase(
                        "AdminJmxAndJmx_OrderedMatrix",
                        List.of(JmxAutoConfiguration.class, SpringApplicationAdminJmxAutoConfiguration.class),
                        List.of(),
                        Set.of(),
                        List.of(
                                Dimension.property("spring.jmx.enabled",
                                        List.of(Optional.of("false"), Optional.of("true"))),
                                Dimension.property("spring.application.admin.enabled",
                                        List.of(Optional.empty(), Optional.of("false"), Optional.of("true")))),
                        List.of("mbeanExporter", "springApplicationAdminRegistrar")),
                new SliceCase(
                        "Aop_NestedTwoPropertyMatrix",
                        List.of(AopAutoConfiguration.class),
                        List.of(),
                        Set.of(),
                        List.of(
                                Dimension.property("spring.aop.auto",
                                        List.of(Optional.empty(), Optional.of("false"), Optional.of("true"))),
                                Dimension.property("spring.aop.proxy-target-class",
                                        List.of(Optional.empty(), Optional.of("false"), Optional.of("true")))),
                        List.of("forceAutoProxyCreatorToUseClassProxying")),
                new SliceCase(
                        "TaskExecution_VirtualVsPlatform",
                        List.of(TaskExecutionAutoConfiguration.class),
                        List.of(),
                        Set.of(),
                        List.of(Dimension.property("spring.threads.virtual.enabled",
                                List.of(Optional.empty(), Optional.of("false"), Optional.of("true")))),
                        List.of("applicationTaskExecutor", "threadPoolTaskExecutorBuilder", "simpleAsyncTaskExecutorBuilder")),
                new SliceCase(
                        "Jmx_HiddenMBeanExporterClass",
                        List.of(JmxAutoConfiguration.class),
                        List.of(),
                        Set.of(MBeanExporter.class.getName()),
                        List.of(Dimension.property("spring.jmx.enabled",
                                List.of(Optional.of("false"), Optional.of("true")))),
                        List.of("mbeanExporter", "objectNamingStrategy")),
                new SliceCase(
                        "Availability_DefaultMust",
                        List.of(ApplicationAvailabilityAutoConfiguration.class),
                        List.of(),
                        Set.of(),
                        List.of(Dimension.profile("check")),
                        List.of("applicationAvailability")),
                new SliceCase(
                        "Availability_UserOverrideNever",
                        List.of(ApplicationAvailabilityAutoConfiguration.class),
                        List.of(UserAvailabilityOverrideConfig.class),
                        Set.of(),
                        List.of(Dimension.profile("override")),
                        List.of("applicationAvailability")),
                new SliceCase(
                        "LifecycleAndSsl_MustDefaults",
                        List.of(LifecycleAutoConfiguration.class, SslAutoConfiguration.class),
                        List.of(),
                        Set.of(),
                        List.of(Dimension.profile("core")),
                        List.of("lifecycleProcessor", "sslBundleRegistry")));

        int falseMust = 0;
        int falseNever = 0;
        int mayPairsVerified = 0;
        int mustSamplesVerified = 0;
        int neverSamplesVerified = 0;
        long seed = 2026112805L;

        for (var sc : cases) {
            Set<String> effectiveCp = baseClasspath;
            if (!sc.hiddenClasses().isEmpty()) {
                var filtered = new HashSet<>(baseClasspath);
                filtered.removeAll(sc.hiddenClasses());
                effectiveCp = filtered;
            }
            var userBytes = new ArrayList<byte[]>(sc.userConfigs().size());
            for (var uc : sc.userConfigs()) {
                userBytes.add(readClassBytes(uc));
            }
            var userInv = StarterAutoConfigSatReasoner.extractUserBeansPassively(userBytes);
            var rootFqns = sc.autoConfigs().stream().map(Class::getName).toList();

            // 1. Static production CDCL classification over the cohort
            var cohort = StarterAutoConfigSatReasoner.evaluateAutoConfigCohort(
                    scan, rootFqns, sc.dimensions(), effectiveCp, userInv, Map.of(), sc.targetBeans());

            // 2. Compile exact per-COI-slice PB d-DNNF over the cohort's scoped configs + finite domains
            var sliceScope = collectScope(scan, rootFqns);
            var encoded = StarterAutoConfigSatReasoner.encodeStarterGraphSat(
                    scan, sliceScope.configFqns(), sliceScope.beanIds(), Map.of(), "");

            var builder = new SliceBuilder();
            builder.addFormulaRoot(encoded.formula());
            // Fix classpath class:* literals against passive closed classpath
            for (var entry : encoded.formula() != null ? builder.build().variableIds().entrySet() : Set.<Map.Entry<String, Integer>>of()) {
                String vn = entry.getKey();
                int lit = entry.getValue();
                if (vn.startsWith("class:")) {
                    String cls = vn.substring(6);
                    builder.addClause(effectiveCp.contains(cls) ? lit : -lit);
                } else if (vn.startsWith("extbean:")) {
                    String type = vn.substring(8);
                    boolean hasUserBean = userInv.beans().stream().anyMatch(b -> b.returnTypeFqn().equals(type));
                    builder.addClause(hasUserBean ? lit : -lit);
                } else if (vn.startsWith("extname:")) {
                    String bn = vn.substring(8);
                    boolean hasUserName = userInv.beans().stream().anyMatch(b -> b.beanName().equals(bn))
                            || userInv.syntheticBeanNames().contains(bn);
                    builder.addClause(hasUserName ? lit : -lit);
                }
            }
            // Constrain property domains to the exact finite Dimension values of this slice case
            var dimByNormKey = new HashMap<String, Dimension>();
            var activeProfileDims = new HashSet<String>();
            for (var d : sc.dimensions()) {
                if (d.isProfile()) activeProfileDims.add(d.key());
                else dimByNormKey.put(BytecodeAutoConfigScanner.normalizePropertyKey(d.key()), d);
            }
            for (var propEntry : encoded.propertyDomains().entrySet()) {
                String key = propEntry.getKey();
                List<String> dom = propEntry.getValue();
                Dimension dim = dimByNormKey.get(key);
                for (String token : dom) {
                    int lit = builder.variableId(StarterAutoConfigSatReasoner.propValueVarName(key, token));
                    if (lit == 0) continue;
                    boolean allowed;
                    if (dim == null) {
                        allowed = StarterAutoConfigSatReasoner.MISSING_VALUE_TOKEN.equals(token);
                    } else if (StarterAutoConfigSatReasoner.MISSING_VALUE_TOKEN.equals(token)) {
                        allowed = dim.values().stream().anyMatch(Optional::isEmpty);
                    } else if (StarterAutoConfigSatReasoner.OTHER_VALUE_TOKEN.equals(token)) {
                        allowed = false;
                    } else {
                        allowed = dim.values().stream()
                                .anyMatch(opt -> opt.isPresent() && opt.orElseThrow().equalsIgnoreCase(token));
                    }
                    if (!allowed) {
                        builder.addClause(-lit);
                    }
                }
            }
            for (var entry : builder.build().variableIds().entrySet()) {
                String vn = entry.getKey();
                if (vn.startsWith("profile:") && !activeProfileDims.contains(vn.substring(8))) {
                    builder.addClause(-entry.getValue());
                }
            }

            // Encode combined target bean activation literals by bean name
            var targetLitByBeanName = new LinkedHashMap<String, Integer>();
            for (String beanName : sc.targetBeans()) {
                var matchingFormulas = new ArrayList<com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula>();
                for (var bean : sliceScope.beans()) {
                    if (bean.beanNames().contains(beanName)) {
                        var f = encoded.beanActiveVars().get(ConeOfInfluenceSlicer.beanNodeId(bean));
                        if (f != null) matchingFormulas.add(f);
                    }
                }
                assertFalse(matchingFormulas.isEmpty(), "Missing bean " + beanName + " in slice " + sc.name());
                var combined = matchingFormulas.size() == 1
                        ? matchingFormulas.getFirst()
                        : com.evolution.analysis.spring.truth.SymbolicConfiguration.or(matchingFormulas);
                targetLitByBeanName.put(beanName, builder.encodeFormula(combined));
            }

            var compileRes = SlicedDdnnfCompiler.compile(builder.build());
            assertTrue(compileRes.isComplete(), "Real Boot slice failed d-DNNF compilation: " + sc.name());
            CompiledDdnnf ddnnf = compileRes.ddnnf().orElseThrow();
            assertTrue(ddnnf.countModels().compareTo(BigInteger.ZERO) > 0, "Empty d-DNNF space: " + sc.name());

            for (String beanName : sc.targetBeans()) {
                int targetLit = targetLitByBeanName.get(beanName);
                var ddnnfClass = ddnnf.classify(targetLit);
                String cdclClass = cohort.beanClassifications().get(beanName);
                assertEquals(cdclClass, ddnnfClass.name(),
                        "CDCL vs PB-d-DNNF classification mismatch on " + sc.name() + "." + beanName);

                if (ddnnfClass == TruthRegionEvaluation.Classification.MAY) {
                    var pairOpt = ddnnf.sampleSymmetricMayWitnessPair(targetLit, seed++);
                    assertTrue(pairOpt.isPresent(), "Expected symmetric MAY pair for " + sc.name() + "." + beanName);
                    var pair = pairOpt.orElseThrow();
                    assertTrue(pair.mayExposureRatio().compareTo(BigDecimal.ZERO) > 0
                            && pair.mayExposureRatio().compareTo(BigDecimal.ONE) < 0);

                    boolean posPresent = runBeanPresenceInContextRunner(sc, pair.witnessPositive(), beanName);
                    boolean negPresent = runBeanPresenceInContextRunner(sc, pair.witnessNegative(), beanName);
                    assertTrue(posPresent, "d-DNNF witness+ MUST activate " + sc.name() + "." + beanName
                            + ", witness+=" + extractPropsFromWitness(sc, pair.witnessPositive()));
                    assertFalse(negPresent, "d-DNNF witness- MUST suppress " + sc.name() + "." + beanName
                            + ", witness-=" + extractPropsFromWitness(sc, pair.witnessNegative()));
                    mayPairsVerified++;
                } else if (ddnnfClass == TruthRegionEvaluation.Classification.MUST) {
                    var samples = ddnnf.sampleUniform(3, seed++);
                    assertFalse(samples.isEmpty());
                    for (var w : samples) {
                        boolean present = runBeanPresenceInContextRunner(sc, w, beanName);
                        if (!present) falseMust++;
                        assertTrue(present, "False-MUST detected on " + sc.name() + "." + beanName);
                        mustSamplesVerified++;
                    }
                } else if (ddnnfClass == TruthRegionEvaluation.Classification.NEVER) {
                    var samples = ddnnf.sampleUniform(3, seed++);
                    assertFalse(samples.isEmpty());
                    for (var w : samples) {
                        boolean present = runBeanPresenceInContextRunner(sc, w, beanName);
                        if (present) falseNever++;
                        assertFalse(present, "False-NEVER detected on " + sc.name() + "." + beanName);
                        neverSamplesVerified++;
                    }
                }
            }
        }

        assertEquals(0, falseMust, "Zero False-MUST required across d-DNNF-sampled real Spring Boot slices");
        assertEquals(0, falseNever, "Zero False-NEVER required across d-DNNF-sampled real Spring Boot slices");
        final int finalMayPairs = mayPairsVerified;
        final int finalMustSamples = mustSamplesVerified;
        final int finalNeverSamples = neverSamplesVerified;
        assertTrue(finalMayPairs >= 6, () -> "Expected >= 6 MAY (witness+, witness-) pairs, got " + finalMayPairs);
        assertTrue(finalMustSamples >= 6, () -> "Expected >= 6 MUST uniform samples, got " + finalMustSamples);
        assertTrue(finalNeverSamples >= 6, () -> "Expected >= 6 NEVER uniform samples, got " + finalNeverSamples);

        System.out.printf(Locale.ROOT,
                "[WP2.5-DDNNF-ORACLE-SLICE] cases=%d mayPairsVerified=%d mustSamples=%d neverSamples=%d falseMust=%d falseNever=%d%n",
                cases.size(), mayPairsVerified, mustSamplesVerified, neverSamplesVerified, falseMust, falseNever);
    }

    private static boolean runBeanPresenceInContextRunner(
            SliceCase sc,
            Map<String, Boolean> ddnnfWitness,
            String beanName) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(sc.autoConfigs().toArray(Class<?>[]::new)));
        if (!sc.userConfigs().isEmpty()) {
            runner = runner.withUserConfiguration(sc.userConfigs().toArray(Class<?>[]::new));
        }
        if (!sc.hiddenClasses().isEmpty()) {
            runner = runner.withClassLoader(new FilteredClassLoader(sc.hiddenClasses().toArray(String[]::new)));
        }
        List<String> propArgs = extractPropsFromWitness(sc, ddnnfWitness);
        if (!propArgs.isEmpty()) {
            runner = runner.withPropertyValues(propArgs.toArray(String[]::new));
        }
        boolean[] present = new boolean[1];
        runner.run(ctx -> {
            assertNull(ctx.getStartupFailure(),
                    () -> "Context startup failure for " + sc.name() + " props=" + propArgs + ": " + ctx.getStartupFailure());
            present[0] = ctx.containsBeanDefinition(beanName);
        });
        return present[0];
    }

    private static List<String> extractPropsFromWitness(SliceCase sc, Map<String, Boolean> witness) {
        var props = new ArrayList<String>();
        var activeProfiles = new ArrayList<String>();
        for (var dim : sc.dimensions()) {
            if (dim.isProfile()) {
                if (Boolean.TRUE.equals(witness.get("profile:" + dim.key()))) {
                    activeProfiles.add(dim.key());
                }
            } else {
                String normKey = BytecodeAutoConfigScanner.normalizePropertyKey(dim.key());
                for (var optVal : dim.values()) {
                    if (optVal.isEmpty()) continue;
                    String token = optVal.orElseThrow();
                    String varName = StarterAutoConfigSatReasoner.propValueVarName(normKey, token);
                    if (Boolean.TRUE.equals(witness.get(varName))) {
                        props.add(dim.key() + "=" + token);
                        break;
                    }
                }
            }
        }
        if (!activeProfiles.isEmpty()) {
            props.add("spring.profiles.active=" + String.join(",", activeProfiles));
        }
        return props;
    }

    private record Scope(Set<String> configFqns, Set<String> beanIds, List<BytecodeAutoConfigScanner.StarterBean> beans) {}

    private static Scope collectScope(BytecodeAutoConfigScanner.ScanResult scan, List<String> rootFqns) {
        var byFqn = new HashMap<String, BytecodeAutoConfigScanner.StarterConfiguration>();
        for (var cfg : scan.configurations()) byFqn.put(cfg.classFqn(), cfg);
        var configs = new LinkedHashSet<String>();
        var beanIds = new LinkedHashSet<String>();
        var beans = new ArrayList<BytecodeAutoConfigScanner.StarterBean>();
        var q = new ArrayDeque<String>(rootFqns);
        while (!q.isEmpty()) {
            String fqn = q.poll();
            var cfg = byFqn.get(fqn);
            if (cfg == null || !configs.add(fqn)) continue;
            for (var b : cfg.beans()) {
                beanIds.add(ConeOfInfluenceSlicer.beanNodeId(b));
                beans.add(b);
            }
            for (String imp : cfg.importedClasses()) if (byFqn.containsKey(imp)) q.add(imp);
            for (String nest : cfg.nestedConfigurationClasses()) if (byFqn.containsKey(nest)) q.add(nest);
        }
        return new Scope(configs, beanIds, beans);
    }

    private static Path locateRuntimeBootAutoconfigureJar() throws Exception {
        var loc = JmxAutoConfiguration.class.getProtectionDomain().getCodeSource().getLocation();
        Path jar = Path.of(loc.toURI());
        assertTrue(Files.isRegularFile(jar), "Expected spring-boot-autoconfigure JAR at " + jar);
        return jar;
    }

    private static Set<String> indexRuntimeClasspathPassively() throws IOException {
        String cp = System.getProperty("java.class.path", "");
        var paths = new ArrayList<Path>();
        for (String entry : cp.split(File.pathSeparator)) {
            if (!entry.isBlank()) paths.add(Path.of(entry));
        }
        return StarterAutoConfigSatReasoner.indexClasspathPassively(paths, true);
    }

    private static byte[] readClassBytes(Class<?> cls) throws IOException {
        String resource = cls.getName().replace('.', '/') + ".class";
        try (InputStream is = cls.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(is, "Missing class bytes for " + cls.getName());
            return is.readAllBytes();
        }
    }
}
