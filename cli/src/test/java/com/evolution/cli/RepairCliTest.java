package com.evolution.cli;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.backend.analysis.*;
import com.evolution.backend.query.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

final class RepairCliTest {
    @TempDir Path temp;

    @Test
    void cliRepairAndVerifyRepairWorkEndToEndOverHttp() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("cli-repo"));
        writeFile(repo.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>cli-repair</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """);
        writeFile(repo.resolve("src/main/java/demo/BadDep.java"), "package demo; public class BadDep {}\n");
        writeFile(repo.resolve("src/main/java/demo/Client.java"), """
                package demo;
                import demo.BadDep;
                public class Client {
                    private final BadDep unused;
                    public Client(BadDep unused) { this.unused = unused; }
                    public String ok() { return "ok"; }
                }
                """);

        String policyYaml = """
                schema: architecture-policy:v1
                id: cli.repair.test
                version: "1"
                rules:
                  - id: no-bad-dep
                    version: "1"
                    kind: FORBIDDEN_DEPENDENCY
                    severity: ERROR
                    scope: TYPE
                    relationships: [graph.depends-on]
                    from:
                      packages: [demo]
                    to:
                      names: [demo.BadDep]
                """;
        var policy = new LocalAnalysisRunner.PolicyInput(policyYaml.getBytes(StandardCharsets.UTF_8), Scope.TYPE);
        Path cache = Files.createDirectory(temp.resolve("cache"));
        Path state = Files.createDirectory(temp.resolve("state"));
        Path jdk = Path.of(System.getProperty("java.home"));
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/cli-repair.git");

        var graphs = new InMemoryGraphStore();
        var reports = new PublishedReports();
        var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(
                repo, repoId, jdk, 21, cache, Optional.empty(), true, false,
                List.of(), Optional.of(policy), LocalAnalysisRunner.SpringInput.absent()),
                state.resolve("acquisition"));

        try (var jobs = new AnalysisJobs(List.of(runner.registration("cli")), graphs, reports,
                new AnalysisJobs.Limits(1, 1, 4, 90_000, 1000))) {
            String jobId = jobs.submit("cli", "first").job().id();
            AnalysisJobs.View view = null;
            for (int i = 0; i < 600; i++) {
                view = jobs.get(UUID.fromString(jobId));
                if (AnalysisJobs.terminal(view.state())) break;
                Thread.sleep(50);
            }
            assertNotNull(view);
            assertNotEquals(AnalysisJobs.State.FAILED, view.state());

            ContentDigest graphId = view.result().orElseThrow().graph().orElseThrow();
            ContentDigest reportId = reports.forGraph(graphId).getFirst();
            var report = reports.get(reportId).orElseThrow();
            var finding = report.evaluation().findings().getFirst();

            var connections = new RepositoryConnections(jobs, jdk, 21, cache, state, Optional.of(policy));
            connections.describeLocal("cli", "cli-repo", repoId, repo, Optional.of(policy));
            var reanalysis = new CandidateRepairReanalysis(jdk, 21, cache, state, graphs, reports, connections);

            var apiNoVm = ArchitectureQueryApi.withRepairReanalysis(
                    ArchitectureQueryApi.withRepairStore(
                            new ArchitectureQueryApi(graphs, reports, ApiLimits.defaults(), jobs, connections),
                            new RepairProposalStore(state.resolve("repairs"))),
                    reanalysis);

            Path cliWork = Files.createDirectory(temp.resolve("cli-work"));
            Path propOut = temp.resolve("proposal.json");
            Path patchOut = temp.resolve("fix.patch");

            try (var server = new ArchitectureHttpServer(apiNoVm, 0)) {
                server.start();
                String port = Integer.toString(server.uri().getPort());

                var optsRes = execCli(cliWork, "--json", "repair", "options",
                        "--port", port, "--graph-id", graphId.value(), "--report", reportId.value(),
                        "--finding", finding.identity().value(), "--bundle", finding.bundle().identity().value(),
                        "--context", report.evaluation().context());
                assertEquals(0, optsRes.code(), optsRes.err());
                assertTrue(optsRes.out().contains("java.remove-unused-constructor-dependency"));

                var propRes = execCli(cliWork, "--json", "repair", "propose",
                        "--port", port, "--graph-id", graphId.value(), "--report", reportId.value(),
                        "--finding", finding.identity().value(), "--bundle", finding.bundle().identity().value(),
                        "--context", report.evaluation().context(),
                        "--strategy", "RECIPE", "--recipe", "java.remove-unused-constructor-dependency",
                        "--out", propOut.toString(), "--patch-out", patchOut.toString());
                assertEquals(0, propRes.code(), propRes.err());
                assertTrue(Files.isRegularFile(propOut));
                assertTrue(Files.readString(patchOut, StandardCharsets.UTF_8).contains("public Client()"));

                // Without VM capture -> UNVERIFIED (exit 3) even after automatic --reanalyze --with-control.
                var unverifiedRes = execCli(cliWork, "--json", "verify-repair", propOut.toString(),
                        "--port", port, "--reanalyze", "--with-control");
                assertEquals(3, unverifiedRes.code(), unverifiedRes.out() + "\n" + unverifiedRes.err());
                assertTrue(unverifiedRes.out().contains("\"verdict\":\"UNVERIFIED\""));
            }

            // Now attach an authentic VM capture compiled from the patched Client.java -> VERIFIED (exit 0).
            var propOutcome = GraphJson.object(GraphJson.read(Files.readString(propOut, StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
            var reRun = apiNoVm.handle("POST", "/api/v1/graphs/" + graphId.value() + "/reports/" + reportId.value() + "/repair-verification",
                    "application/json", CanonicalJson.write(Map.of("arguments", Map.of(
                            "identity", finding.identity().value(),
                            "bundle", finding.bundle().identity().value(),
                            "context", report.evaluation().context(),
                            "diagnosisInput", GraphJson.string(GraphJson.object(GraphJson.object(GraphJson.object(propOutcome.get("proposal")).get("candidate"))).get("diagnosisInput")),
                            "proposal", GraphJson.string(GraphJson.object(propOutcome.get("proposal")).get("identity"))))).getBytes(StandardCharsets.UTF_8),
                    () -> false);
            var verData = GraphJson.object(GraphJson.object(GraphJson.read(new String(reRun.body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults())).get("data"));

            Path captureDir = Files.createDirectory(temp.resolve("vm-cap"));
            writeAuthenticCliCapture(captureDir, repo, propOutcome, verData);

            String prevDir = System.getProperty("evo.repair.capture.dir");
            try {
                System.setProperty("evo.repair.capture.dir", captureDir.toString());
                var apiWithVm = ArchitectureQueryApi.withConfiguredCapturedRepairCapture(apiNoVm);
                try (var server = new ArchitectureHttpServer(apiWithVm, 0)) {
                    server.start();
                    String port = Integer.toString(server.uri().getPort());
                    var verifiedRes = execCli(cliWork, "--json", "verify-repair", propOut.toString(),
                            "--port", port, "--reanalyze", "--with-control");
                    assertEquals(0, verifiedRes.code(), verifiedRes.out() + "\n" + verifiedRes.err());
                    assertTrue(verifiedRes.out().contains("\"verdict\":\"VERIFIED\""));
                }
            } finally {
                if (prevDir == null) System.clearProperty("evo.repair.capture.dir");
                else System.setProperty("evo.repair.capture.dir", prevDir);
            }
        }
    }

    private record CliOut(int code, String out, String err) {}

    private static CliOut execCli(Path dir, String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        int code = new EvoApp(dir, new StringReader(""), new PrintWriter(out), new PrintWriter(err), false, Map.of("NO_COLOR", "1"))
                .execute(args);
        return new CliOut(code, out.toString(), err.toString());
    }

    private void writeAuthenticCliCapture(Path outDir, Path baselineRepo, Map<String, Object> propOutcome, Map<String, Object> verData) throws Exception {
        var envelope = GraphJson.object(propOutcome.get("proposal"));
        var candidate = GraphJson.object(envelope.get("candidate"));
        String proposalId = GraphJson.string(envelope.get("identity"));
        String planId = GraphJson.string(verData.get("planIdentity"));
        String patch = GraphJson.string(candidate.get("patch"));

        Path ctxInputs = outDir.resolve("context/inputs");
        Path candRepo = ctxInputs.resolve("candidate");
        Files.createDirectories(candRepo);
        Files.walkFileTree(baselineRepo, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(candRepo.resolve(baselineRepo.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, candRepo.resolve(baselineRepo.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        for (var eObj : GraphJson.array(candidate.get("edits"))) {
            String rel = GraphJson.string(GraphJson.object(eObj).get("path"));
            Path target = candRepo.resolve(rel);
            String next = RepairPatch.applyAll(Map.of(rel, Files.readString(target, StandardCharsets.UTF_8)), patch).get(rel);
            Files.writeString(target, next, StandardCharsets.UTF_8);
        }

        Path work = Files.createTempDirectory(temp, "cli-census-");
        Path classes = work.resolve("target/classes");
        Files.createDirectories(classes);
        var javac = ToolProvider.getSystemJavaCompiler();
        var srcs = Files.walk(candRepo).filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
        var compileCmd = new ArrayList<>(List.of("--release", "21", "-d", classes.toString()));
        compileCmd.addAll(srcs);
        assertEquals(0, javac.run(null, null, null, compileCmd.toArray(String[]::new)));

        Path toolDir = work.resolve("tool");
        Files.createDirectories(toolDir);
        String censusSrc = readControl("ClassDependencyCensus.java");
        Path censusSrcFile = toolDir.resolve("ClassDependencyCensus.java");
        Files.writeString(censusSrcFile, censusSrc, StandardCharsets.UTF_8);
        assertEquals(0, javac.run(null, null, null, "-d", toolDir.toString(), censusSrcFile.toString()));
        Path censusOut = work.resolve("census.json");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{toolDir.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            loader.loadClass("ClassDependencyCensus").getMethod("main", String[].class)
                    .invoke(null, (Object) new String[]{work.toString(), censusOut.toString()});
        }
        var rawCensus = GraphJson.object(GraphJson.read(Files.readString(censusOut, StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
        var censusDigest = CanonicalJson.sha256(rawCensus);

        Files.writeString(ctxInputs.resolve("ClassDependencyCensus.java"), censusSrc, StandardCharsets.UTF_8);
        var entries = new TreeMap<String, String>();
        Files.walk(ctxInputs).filter(Files::isRegularFile).forEach(f -> {
            String rel = ctxInputs.relativize(f).toString().replace('\\', '/');
            if (!rel.equals("manifest.sha256")) {
                try { entries.put(rel, ContentDigest.sha256(Files.readAllBytes(f)).value().substring(7)); }
                catch (IOException ex) { throw new UncheckedIOException(ex); }
            }
        });
        byte[] manifestBytes = (String.join("\n", entries.entrySet().stream().map(e -> e.getValue() + "  " + e.getKey()).toList()) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(ctxInputs.resolve("manifest.sha256"), manifestBytes);
        var inputsDigest = ContentDigest.sha256(manifestBytes);

        var observed = List.of(
                "EVO_VM_INPUTS " + inputsDigest.value().substring(7),
                "EVO_VM_ISOLATION network=loopback-only disks=none",
                "EVO_BUILD_TOOL maven",
                "EVO_COMPILE_EXIT 0",
                "EVO_CHECK candidate.compile",
                "EVO_TEST_EXIT 0",
                "EVO_TESTS run=1 failed=0 errors=0",
                "EVO_CLASS_CENSUS " + censusDigest.value(),
                "EVO_CHECK candidate.test",
                "EVO_VM_COMPLETE");
        String serialContent = String.join("\n", observed) + "\n";
        Files.writeString(outDir.resolve("serial.txt"), serialContent, StandardCharsets.UTF_8);

        var controls = new TreeMap<String, String>();
        for (String name : List.of("Dockerfile", "guest-init.sh", "run.py", "ClassDependencyCensus.java")) {
            controls.put(name, ContentDigest.sha256Utf8(readControl(name)).value());
        }
        var capture = new TreeMap<String, Object>();
        capture.put("version", "captured-repair.vm-capture:v1");
        capture.put("proposal", proposalId);
        capture.put("plan", planId);
        capture.put("inputs", inputsDigest.value());
        capture.put("controls", controls);
        capture.put("base", "eclipse-temurin@sha256:" + "a".repeat(64));
        capture.put("image", "sha256:" + "b".repeat(64));
        capture.put("container", "c".repeat(64));
        capture.put("buildTool", "maven");
        capture.put("modules", List.of("cli-repair"));
        capture.put("compileExit", 0);
        capture.put("testExit", 0);
        capture.put("testsRun", 1);
        capture.put("testsFailed", 0);
        capture.put("testsErrors", 0);
        capture.put("classCensusDigest", censusDigest.value());
        capture.put("classCensus", rawCensus);
        capture.put("observed", observed);
        capture.put("checks", List.of("candidate.compile", "candidate.test"));
        capture.put("serial", ContentDigest.sha256Utf8(serialContent).value());
        Files.writeString(outDir.resolve("capture.json"), CanonicalJson.write(capture), StandardCharsets.UTF_8);
    }

    private static String readControl(String name) throws IOException {
        try (var in = RepairCliTest.class.getResourceAsStream("/captured-repair/" + name)) {
            assertNotNull(in, "Missing resource /captured-repair/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
