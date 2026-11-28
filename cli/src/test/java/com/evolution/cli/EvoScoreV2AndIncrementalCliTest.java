package com.evolution.cli;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EvoScoreV2AndIncrementalCliTest {

    @Test void scoreV2AndArchScoreEvaluateWholeRepoContextPolicyStoreDigestAndHumanReport(@TempDir Path temp) throws Exception {
        var graph = buildLayeredGraph(false);
        var storeDir = Files.createDirectory(temp.resolve("graph-store"));
        new FileGraphStore(storeDir, 16_000_000L, new GraphJson.StreamLimits(16_000_000L, 128, 16_000_000L)).save(graph);
        Path graphFile = temp.resolve("layered.graph.json");
        Files.writeString(graphFile, new GraphSnapshotCodec().encode(graph));
        String analysisCtx = graph.contexts().stream().filter(c -> c.analysis().isPresent()).findFirst().orElseThrow().identity();

        // 1. evo score --v2 --graph <file> --json defaults to Whole Repository (snapshot:...)
        var wholeJson = run(temp, "score", "--v2", "--graph", graphFile.toString(), "--json");
        assertEquals(0, wholeJson.code(), wholeJson::err);
        var wholeData = wholeJson.data();
        assertEquals("WHOLE_REPOSITORY", wholeData.get("scopeMode"));
        assertEquals("COMPLETE", wholeData.get("status"));
        assertEquals(7, GraphJson.array(wholeData.get("dimensions")).size());
        assertEquals(1, GraphJson.array(wholeData.get("contextBreakdown")).size());
        assertEquals("HIGH", GraphJson.object(wholeData.get("confidence")).get("tier"));

        // 2. evo score --store <dir> --graph sha256:... (digest lookup without --v2 flag triggers v2 cleanly!)
        var digestJson = run(temp, "score", "--store", storeDir.toString(), "--graph", graph.identity().value(), "--json");
        assertEquals(0, digestJson.code(), digestJson::err);
        assertEquals(wholeJson.out().strip(), digestJson.out().strip());

        // 3. evo arch-score human-readable report (no --v2 flag needed) with explicit context + custom policy
        Path customPolicy = temp.resolve("policy.yaml");
        Files.writeString(customPolicy, """
                schema: architecture-policy:v1
                id: cli.v2.check
                version: "1"
                rules:
                  - id: cli.no-web-to-repo
                    version: "1"
                    kind: FORBIDDEN_DEPENDENCY
                    severity: ERROR
                    scope: TYPE
                    relationships: [graph.depends-on]
                    from: {packages: ["com.acme.web.**"], origins: [PROJECT]}
                    to: {packages: ["com.acme.repository.**"], origins: [PROJECT]}
                """);
        var human = run(temp, "arch-score", "--graph", graphFile.toString(), "--context", analysisCtx, "--policy", customPolicy.toString());
        assertEquals(0, human.code(), human::err);
        assertTrue(human.out().contains("Architecture Health Score v2 (architecture.score-formula:v2.0)"), human::out);
        assertTrue(human.out().contains("Architecture Health:"), human::out);
        assertTrue(human.out().contains("Analysis Confidence (Independent):"), human::out);
        assertTrue(human.out().contains("Inferred Style: Layered Architecture"), human::out);
        assertTrue(human.out().contains("Dimensions:"), human::out);

        // 4. Whole-repo human report includes Context Breakdown
        var humanWhole = run(temp, "arch-score", "--store", storeDir.toString(), "--graph", graph.identity().value());
        assertEquals(0, humanWhole.code(), humanWhole::err);
        assertTrue(humanWhole.out().contains("Context Breakdown (1):"), humanWhole::out);

        // 5. Legacy evo score --graph <file> --json (without --v2 / --store / digest) keeps M6 v1 POLICY_REQUIRED behavior
        var legacy = run(temp, "score", "--graph", graphFile.toString(), "--context", analysisCtx, "--json");
        assertEquals(2, legacy.code());
        assertTrue(legacy.err().contains("POLICY_REQUIRED"), legacy::err);
    }

    @Test void scoreV2HumanReportSurfacesOffendersAndWitnessesForCyclicUpwardLeakingGraph(@TempDir Path temp) throws Exception {
        var cyclicGraph = buildLayeredGraph(true);
        Path graphFile = temp.resolve("cyclic.graph.json");
        Files.writeString(graphFile, new GraphSnapshotCodec().encode(cyclicGraph));
        String analysisCtx = cyclicGraph.contexts().stream().filter(c -> c.analysis().isPresent()).findFirst().orElseThrow().identity();

        var res = run(temp, "score", "--v2", "--graph", graphFile.toString(), "--context", analysisCtx);
        assertEquals(0, res.code(), res::err);
        assertTrue(res.out().contains("Top Architectural Offenders:"), res::out);
        assertTrue(res.out().contains("Witness:"), res::out);
    }

    @Test void scanSurfacesIncrementalSummaryAcrossColdWarmModifiedAndNoIncrementalRuns(@TempDir Path temp) throws Exception {
        Path repo = Files.createDirectory(temp.resolve("inc-repo"));
        Path ws = Files.createDirectory(temp.resolve("inc-ws"));
        Path cache = Files.createDirectory(temp.resolve("inc-m2"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>cli-inc</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """);
        Path src = Files.createDirectories(repo.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("Controller.java"), """
                package demo;
                public class Controller {
                    private final Service service = new Service();
                    public int handle() { return service.compute(); }
                }
                """);
        Files.writeString(src.resolve("Service.java"), """
                package demo;
                public class Service {
                    public int compute() { return 1; }
                }
                """);

        Path summaryFile = ws.resolve(".evo/last-incremental-summary.json");

        // 1. Cold scan -> COLD
        var cold = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off",
                "--fast", "--telemetry", "--json");
        assertTrue(cold.code() == 0 || cold.code() == 3, () -> "code=" + cold.code() + " err=" + cold.err());
        assertFalse(cold.data().containsKey("incremental"), "Operational incremental telemetry must stay outside canonical stdout receipt");
        var coldInc = readJsonFile(summaryFile);
        assertEquals("COLD", coldInc.get("mode"));
        assertTrue(cold.err().contains("[evo] Incremental (COLD):"), cold::err);

        // 2. Warm identical scan -> NO_CHANGE with reused files & cached module, AND bit-for-bit identical canonical JSON stdout!
        var warm = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off",
                "--fast", "--telemetry", "--json");
        assertEquals(cold.code(), warm.code(), warm::err);
        assertEquals(cold.out(), warm.out(), "Canonical stdout receipt must remain bit-for-bit identical across cold & warm incremental runs");
        var warmInc = readJsonFile(summaryFile);
        assertEquals("NO_CHANGE", warmInc.get("mode"));
        assertTrue(((Number) warmInc.get("unchangedFiles")).longValue() >= 3);
        assertEquals(0L, ((Number) warmInc.get("modifiedFiles")).longValue());
        assertEquals(1, ((Number) warmInc.get("reusedModules")).intValue());
        assertNotNull(warmInc.get("baseSnapshotId"));
        assertNotNull(warmInc.get("baseGraphId"));
        assertTrue(((Number) warmInc.get("reusedGraphContexts")).intValue() >= 1);
        assertEquals(0, ((Number) warmInc.get("rebuiltGraphContexts")).intValue());
        assertTrue(warm.err().contains("[evo] Incremental (NO_CHANGE):"), warm::err);

        // 3. Edit 1 Java file -> INCREMENTAL
        Files.writeString(src.resolve("Service.java"), """
                package demo;
                public class Service {
                    public int compute() { return 2; }
                }
                """);
        var delta = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off",
                "--fast", "--telemetry", "--json");
        assertEquals(cold.code(), delta.code(), delta::err);
        var deltaInc = readJsonFile(summaryFile);
        assertEquals("INCREMENTAL", deltaInc.get("mode"));
        assertEquals(1L, ((Number) deltaInc.get("modifiedFiles")).longValue());
        assertTrue(((Number) deltaInc.get("unchangedFiles")).longValue() >= 2);
        assertTrue(delta.err().contains("[evo] Incremental (INCREMENTAL):"), delta::err);

        // 4. --no-incremental disables delta acquisition & caches cleanly while producing identical canonical stdout to delta!
        var clean = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off",
                "--fast", "--no-incremental", "--telemetry", "--json");
        assertEquals(delta.code(), clean.code(), clean::err);
        assertEquals(delta.out(), clean.out(), "Incremental and --no-incremental runs must yield bit-for-bit identical canonical receipts");
        var cleanInc = readJsonFile(summaryFile);
        assertEquals("COLD", cleanInc.get("mode"));
        assertEquals("NO_INCREMENTAL_FLAG", cleanInc.get("fallbackReason"));
        assertTrue(clean.err().contains("[evo] Cache: javaHits=0 javaMisses=1"), clean::err);
        assertTrue(clean.err().contains("reason=NO_INCREMENTAL_FLAG"), clean::err);
    }

    @Test void noChangeFastPathInvalidatesOnOptionChangeAndSingleByteEdit(@TempDir Path temp) throws Exception {
        Path repo = Files.createDirectory(temp.resolve("fp-repo"));
        Path ws = Files.createDirectory(temp.resolve("fp-ws"));
        Path cache = Files.createDirectory(temp.resolve("fp-m2"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>cli-fp</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """);
        Path src = Files.createDirectories(repo.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("App.java"), """
                package demo;
                public class App {
                    public int run() { return 1; }
                }
                """);

        // 1. Cold --fast
        var coldFast = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off", "--fast", "--json");
        assertTrue(coldFast.code() == 0 || coldFast.code() == 3, coldFast::err);
        assertFalse(coldFast.data().containsKey("springSatMode"));

        // 2. Warm --fast -> NO_CHANGE fast-path
        var warmFast = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off", "--fast", "--json");
        assertEquals(coldFast.code(), warmFast.code());
        assertEquals(coldFast.out(), warmFast.out());

        // 3. Changing --spring-sat on identical files MUST invalidate fast-path receipt
        var satScan = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off", "--fast", "--spring-sat", "--json");
        assertTrue(satScan.code() == 0 || satScan.code() == 3, satScan::err);
        assertEquals("AUTO_MULTI_CONFIG_SAT", satScan.data().get("springSatMode"));

        // 4. Repeat with --spring-sat -> hits fast-path and matches step 3
        var warmSat = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off", "--fast", "--spring-sat", "--json");
        assertEquals(satScan.code(), warmSat.code());
        assertEquals(satScan.out(), warmSat.out());

        // 5. 1-byte edit invalidates fast-path and matches clean --no-incremental output
        Files.writeString(src.resolve("App.java"), """
                package demo;
                public class App {
                    public int run() { return 2; }
                }
                """);
        var editedInc = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off", "--fast", "--spring-sat", "--json");
        var editedClean = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off", "--fast", "--spring-sat", "--no-incremental", "--json");
        assertEquals(editedClean.code(), editedInc.code());
        assertEquals(editedClean.out(), editedInc.out());

        // 6. --projection-profile=dfd-export invalidates fast-path and invalid option exits with code 2
        var dfdScan = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off", "--fast", "--projection-profile", "dfd-export", "--json");
        assertTrue(dfdScan.code() == 0 || dfdScan.code() == 3, dfdScan::err);
        assertEquals("dfd-export", dfdScan.data().get("projectionProfile"));
        var invalidProfile = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--offline", "--build", "off", "--projection-profile", "bogus", "--json");
        assertEquals(2, invalidProfile.code());
    }

    @Test void multiModuleOneFileEditReusesUnchangedGraphContextAndMatchesCleanRun(@TempDir Path temp) throws Exception {
        Path repo = Files.createDirectory(temp.resolve("mm-repo"));
        Path ws = Files.createDirectory(temp.resolve("mm-ws"));
        Path cache = Files.createDirectory(temp.resolve("mm-m2"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>mm-parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>mod-a</module>
                    <module>mod-b</module>
                  </modules>
                </project>
                """);
        for (String mod : List.of("mod-a", "mod-b")) {
            Path modDir = Files.createDirectory(repo.resolve(mod));
            Files.writeString(modDir.resolve("pom.xml"), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>demo</groupId>
                        <artifactId>mm-parent</artifactId>
                        <version>1.0.0</version>
                      </parent>
                      <artifactId>%s</artifactId>
                      <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                      </properties>
                    </project>
                    """.formatted(mod));
        }
        Path srcA = Files.createDirectories(repo.resolve("mod-a/src/main/java/a"));
        Files.writeString(srcA.resolve("AlphaService.java"), """
                package a;
                public class AlphaService { public String hello() { return "a"; } }
                """);
        Path srcB = Files.createDirectories(repo.resolve("mod-b/src/main/java/b"));
        Files.writeString(srcB.resolve("BetaService.java"), """
                package b;
                public class BetaService { public String hello() { return "b1"; } }
                """);

        Path summaryFile = ws.resolve(".evo/last-incremental-summary.json");
        var cold = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off",
                "--fast", "--telemetry", "--json");
        assertTrue(cold.code() == 0 || cold.code() == 3, cold::err);
        var coldInc = readJsonFile(summaryFile);
        assertEquals("COLD", coldInc.get("mode"));
        assertTrue(((Number) coldInc.get("rebuiltGraphContexts")).intValue() >= 2);

        // Edit only mod-b -> mod-a must reuse its graph template and Java module cache!
        Files.writeString(srcB.resolve("BetaService.java"), """
                package b;
                public class BetaService {
                    public String hello() { return "b2"; }
                    public int count() { return 42; }
                }
                """);
        var inc = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off",
                "--fast", "--telemetry", "--json");
        assertTrue(inc.code() == 0 || inc.code() == 3, inc::err);
        var incSum = readJsonFile(summaryFile);
        assertEquals("INCREMENTAL", incSum.get("mode"));
        assertEquals(1L, ((Number) incSum.get("modifiedFiles")).longValue());
        assertNotNull(incSum.get("baseGraphId"));
        assertTrue(((Number) incSum.get("reusedModules")).intValue() >= 1, () -> "summary=" + incSum);
        assertTrue(((Number) incSum.get("recomputedModules")).intValue() >= 1, () -> "summary=" + incSum);
        assertTrue(((Number) incSum.get("reusedGraphContexts")).intValue() >= 1, () -> "summary=" + incSum);
        assertTrue(((Number) incSum.get("rebuiltGraphContexts")).intValue() >= 1, () -> "summary=" + incSum);
        assertTrue(inc.err().contains("reusedGraphContexts="), inc::err);

        // Clean --no-incremental run on edited repo must yield bit-for-bit identical canonical receipt & graph digest
        var clean = run(ws, "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline", "--build", "off",
                "--fast", "--no-incremental", "--json");
        assertEquals(clean.code(), inc.code(), clean::err);
        assertEquals(clean.out(), inc.out());
    }

    @Test void springSpikeExtractsStarterJarBytecodeAndReportsGoNoGoMetrics(@TempDir Path temp) throws Exception {
        Path repo = Files.createDirectory(temp.resolve("spike-repo"));
        Path ws = Files.createDirectory(temp.resolve("spike-ws"));
        Path cache = Files.createDirectory(temp.resolve("spike-m2"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>spike-app</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """);
        Path res = Files.createDirectories(repo.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.yml"), """
                app:
                  cache:
                    enabled: true
                spring:
                  profiles:
                    active: prod
                """);
        Path src = Files.createDirectories(repo.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("AppConfig.java"), """
                package demo;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.stereotype.Service;

                @Configuration
                public class AppConfig {
                    @Bean
                    public CacheStore customCache() {
                        return new CacheStore();
                    }

                    @Service
                    public static class BillingService {
                        @Autowired
                        private MetricClient metrics;
                        private final AlertClient alerts = null;
                    }
                }
                """);

        // Synthesize passive starter JAR with AutoConfiguration.imports, @ConditionalOnProperty, @ConditionalOnMissingBean, @Profile
        Path starterJar = temp.resolve("demo-starter-1.0.0.jar");
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(starterJar))) {
            zos.putNextEntry(new java.util.zip.ZipEntry(
                    "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
            zos.write("com.acme.starter.DemoCacheAutoConfiguration\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new java.util.zip.ZipEntry("com/acme/starter/DemoCacheAutoConfiguration.class"));
            zos.write(buildStarterAutoConfigBytecode());
            zos.closeEntry();
        }

        var first = run(ws, "spring-spike", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline",
                "--extra-jar", starterJar.toString(), "--json");
        assertEquals(0, first.code(), first::err);
        var data1 = first.data();
        assertEquals(1, ((Number) data1.get("scannedJars")).intValue());
        assertEquals(1, ((Number) data1.get("totalConfigurations")).intValue());
        assertEquals(3, ((Number) data1.get("totalBeans")).intValue());
        assertTrue(((Number) data1.get("g1UserStarterInteractions")).intValue() >= 4, () -> "data=" + data1);
        assertTrue(((Number) data1.get("g2ConditionalFindings")).intValue() >= 2, () -> "data=" + data1);
        var coi = GraphJson.object(data1.get("g3CoiMetrics"));
        assertTrue(((Number) coi.get("componentCount")).intValue() >= 1);

        var second = run(ws, "spring-spike", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(), "--offline",
                "--extra-jar", starterJar.toString(), "--json");
        assertEquals(0, second.code(), second::err);
        assertEquals(data1.get("identity"), second.data().get("identity"));
    }

    private static byte[] buildStarterAutoConfigBytecode() {
        var cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "com/acme/starter/DemoCacheAutoConfiguration", null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/springframework/boot/autoconfigure/AutoConfiguration;", true).visitEnd();

        var prop = cw.visitAnnotation(
                "Lorg/springframework/boot/autoconfigure/condition/ConditionalOnProperty;", true);
        prop.visit("prefix", "app.cache");
        var names = prop.visitArray("name");
        names.visit(null, "enabled");
        names.visitEnd();
        prop.visit("havingValue", "true");
        prop.visitEnd();

        // Bean 1: @ConditionalOnMissingBean(demo.CacheStore.class)
        var m1 = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "cacheStore",
                "()Ldemo/CacheStore;", null, null);
        m1.visitAnnotation("Lorg/springframework/context/annotation/Bean;", true).visitEnd();
        var mb = m1.visitAnnotation(
                "Lorg/springframework/boot/autoconfigure/condition/ConditionalOnMissingBean;", true);
        var mba = mb.visitArray("value");
        mba.visit(null, org.objectweb.asm.Type.getObjectType("demo/CacheStore"));
        mba.visitEnd();
        mb.visitEnd();
        m1.visitCode();
        m1.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        m1.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        m1.visitMaxs(1, 1);
        m1.visitEnd();

        // Bean 2: demo.MetricClient @Profile("prod")
        var m2 = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "metricClient",
                "()Ldemo/MetricClient;", null, null);
        m2.visitAnnotation("Lorg/springframework/context/annotation/Bean;", true).visitEnd();
        var prof = m2.visitAnnotation("Lorg/springframework/context/annotation/Profile;", true);
        var pa = prof.visitArray("value");
        pa.visit(null, "prod");
        pa.visitEnd();
        prof.visitEnd();
        m2.visitCode();
        m2.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        m2.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        m2.visitMaxs(1, 1);
        m2.visitEnd();

        // Bean 3: demo.AlertClient
        var m3 = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "alertClient",
                "()Ldemo/AlertClient;", null, null);
        m3.visitAnnotation("Lorg/springframework/context/annotation/Bean;", true).visitEnd();
        m3.visitCode();
        m3.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        m3.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        m3.visitMaxs(1, 1);
        m3.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Map<String, Object> readJsonFile(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), () -> "Missing telemetry file: " + file);
        return GraphJson.object(GraphJson.read(Files.readString(file), GraphJson.Limits.defaults()));
    }

    private record Run(int code, String out, String err) {
        Map<String, Object> data() {
            return GraphJson.object(GraphJson.object(GraphJson.read(out.strip(), GraphJson.Limits.defaults())).get("data"));
        }
    }

    private static Run run(Path dir, String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        int code = new EvoApp(dir, new StringReader(""), new PrintWriter(out), new PrintWriter(err), false, Map.of()).execute(args);
        return new Run(code, out.toString(), err.toString());
    }

    private static KnowledgeGraph buildLayeredGraph(boolean injectCycleAndUpwardLeak) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/cli-score-v2.git");
        var snapshot = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("cli-v2-snap-" + injectCycleAndUpwardLeak));
        var analysisId = AnalysisIdentity.fromCanonicalManifestInputs("cli-v2-mod");
        String analysisCtx = analysisId.value();
        var module = ModuleIdentity.from(repo, ".");
        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snapshot));
        var manifestEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", module, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", module, "path", "."));

        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manifestEv, modEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        nodes.add(new KnowledgeGraph.Node(module.value(), analysisCtx, "MODULE", List.of(modEv.identity())));
        var typeIds = new HashMap<String, String>();
        var pkgIds = new HashSet<String>();
        for (var spec : List.of(
                List.of("com.acme.web", "OrderController"),
                List.of("com.acme.web", "BillingController"),
                List.of("com.acme.service", "OrderService"),
                List.of("com.acme.service", "BillingService"),
                List.of("com.acme.repository", "OrderRepository"),
                List.of("com.acme.repository", "BillingRepository"))) {
            var typeEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module), EntityKind.TYPE,
                    JavaSymbolName.topLevelType(spec.get(0), spec.get(1)).canonicalName(), Optional.empty());
            var decl = GraphEvidence.capture("java.declaration", Map.of("entity", typeEntity, "spelling", spec.get(1), "status", "RESOLVED"));
            var shape = GraphEvidence.capture("java.type-shape", Map.of("type", typeEntity.identity().value(), "kind", "CLASS", "abstractType", false));
            evidence.add(decl);
            evidence.add(shape);
            String pkgId = EntityIdentity.from(EntityOrigin.PROJECT, EntityScope.project(module), EntityKind.PACKAGE,
                    JavaSymbolName.packageName(spec.get(0)).canonicalName()).value();
            if (pkgIds.add(pkgId)) {
                nodes.add(new KnowledgeGraph.Node(pkgId, analysisCtx, "PACKAGE", List.of(decl.identity())));
                edges.add(new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("c", List.of(module.value(), pkgId)),
                        analysisCtx, "graph.contains", module.value(),
                        new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(pkgId), Optional.empty()),
                        List.of(decl.identity()), Optional.empty()));
            }
            nodes.add(new KnowledgeGraph.Node(typeEntity.identity().value(), analysisCtx, "TYPE", List.of(decl.identity(), shape.identity())));
            edges.add(new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("c", List.of(pkgId, typeEntity.identity().value())),
                    analysisCtx, "graph.contains", pkgId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(typeEntity.identity().value()), Optional.empty()),
                    List.of(decl.identity()), Optional.empty()));
            typeIds.put(spec.get(1), typeEntity.identity().value());
        }
        var deps = new ArrayList<List<String>>(List.of(
                List.of("OrderController", "OrderService"),
                List.of("OrderService", "OrderRepository"),
                List.of("BillingController", "BillingService"),
                List.of("BillingService", "BillingRepository")));
        if (injectCycleAndUpwardLeak) {
            deps.add(List.of("OrderRepository", "OrderController"));
            deps.add(List.of("BillingRepository", "BillingController"));
        }
        int eIdx = 0;
        for (var dep : deps) {
            edges.add(new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("dep", List.of(dep.get(0), dep.get(1), eIdx++)),
                    analysisCtx, "graph.depends-on", typeIds.get(dep.get(0)),
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(typeIds.get(dep.get(1))), Optional.empty()),
                    List.of(manifestEv.identity()), Optional.empty()));
        }
        return new KnowledgeGraph(snapshot,
                List.of(
                        new KnowledgeGraph.Context(snapshot.value(), snapshot, Optional.empty(), snapEv.identity()),
                        new KnowledgeGraph.Context(analysisCtx, snapshot, Optional.of(analysisId), manifestEv.identity())),
                nodes, edges, evidence);
    }
}
