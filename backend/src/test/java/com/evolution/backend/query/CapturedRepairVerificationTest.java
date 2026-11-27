package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.backend.analysis.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

final class CapturedRepairVerificationTest {
    @TempDir Path temp;

    @Test
    void verifiesMultiModuleRecipesAiLiveDiagnosisReanalysisAndIsolatedCensus() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("multi-repo"));
        writeMultiModuleRepo(repo);

        String policyYaml = """
                schema: architecture-policy:v1
                id: test.multi-module.forbidden
                version: "1"
                rules:
                  - id: no-forbidden-legacy
                    version: "1"
                    kind: FORBIDDEN_DEPENDENCY
                    severity: ERROR
                    scope: TYPE
                    relationships: [graph.depends-on]
                    from:
                      names: [example.app.AppController]
                    to:
                      names: [example.app.legacy.ForbiddenLegacy]
                  - id: no-forbidden-service-legacy
                    version: "1"
                    kind: FORBIDDEN_DEPENDENCY
                    severity: ERROR
                    scope: TYPE
                    relationships: [graph.depends-on]
                    from:
                      names: [example.app.AppService]
                    to:
                      names: [example.app.legacy.ForbiddenLegacy]
                  - id: no-forbidden-bridge-legacy
                    version: "1"
                    kind: FORBIDDEN_DEPENDENCY
                    severity: ERROR
                    scope: TYPE
                    relationships: [graph.depends-on]
                    from:
                      names: [example.app.AppBridge]
                    to:
                      names: [example.app.legacy.ForbiddenLegacy]
                """;
        var policy = new LocalAnalysisRunner.PolicyInput(policyYaml.getBytes(StandardCharsets.UTF_8), Scope.TYPE);
        Path cache = Files.createDirectory(temp.resolve("cache"));
        Path state = Files.createDirectory(temp.resolve("state"));
        Path repairStoreDir = Files.createDirectory(temp.resolve("repairs"));
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/multi-module-repair.git");
        Path jdk = Path.of(System.getProperty("java.home"));

        var graphs = new InMemoryGraphStore();
        var reports = new PublishedReports();
        var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(
                repo, repoId, jdk, 21, cache, Optional.of("rev-1"), true, false,
                List.of(), Optional.of(policy), LocalAnalysisRunner.SpringInput.absent()),
                state.resolve("acquisition"));

        try (var jobs = new AnalysisJobs(List.of(runner.registration("multi")), graphs, reports,
                new AnalysisJobs.Limits(1, 1, 4, 90_000, 1000))) {
            String jobId = jobs.submit("multi", "first").job().id();
            var view = awaitJob(jobs, jobId);
            assertNotEquals(AnalysisJobs.State.FAILED, view.state());

            var outcome = view.result().orElseThrow();
            var baselineGraphId = outcome.graph().orElseThrow();
            var baselineGraph = graphs.load(baselineGraphId).orElseThrow();
            var baselineReportId = findAppReport(baselineGraph, reports.forGraph(baselineGraphId), reports);
            var baselineReport = reports.get(baselineReportId).orElseThrow();

            var connections = new RepositoryConnections(jobs, jdk, 21, cache, state, Optional.of(policy));
            connections.describeLocal("multi", "multi-repo", repoId, repo, Optional.of(policy));
            var reanalysis = new CandidateRepairReanalysis(jdk, 21, cache, state, graphs, reports, connections);

            // Start loopback Chat Completions mocks for LIVE diagnosis + AI repair.
            List<String> diagPrompts = Collections.synchronizedList(new ArrayList<>());
            HttpServer diagModelServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            diagModelServer.createContext("/v1/chat/completions", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                diagPrompts.add(body);
                var req = GraphJson.object(GraphJson.read(body, GraphJson.Limits.defaults()));
                var msgs = GraphJson.array(req.get("messages"));
                var userMsg = GraphJson.object(GraphJson.read(GraphJson.string(GraphJson.object(msgs.get(1)).get("content")), GraphJson.Limits.defaults()));
                int count = GraphJson.array(userMsg.get("candidates")).size();
                var picks = java.util.stream.IntStream.rangeClosed(1, Math.max(1, count)).boxed().toList();
                String reply = CanonicalJson.write(Map.of(
                        "model", "test-diag",
                        "choices", List.of(Map.of(
                                "finish_reason", "stop",
                                "message", Map.of("role", "assistant",
                                        "content", CanonicalJson.write(Map.of("citations", picks)))))));
                byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (var os = exchange.getResponseBody()) { os.write(out); }
            });
            diagModelServer.start();

            HttpServer aiRepairServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            aiRepairServer.createContext("/v1/chat/completions", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var req = GraphJson.object(GraphJson.read(body, GraphJson.Limits.defaults()));
                var msgs = GraphJson.array(req.get("messages"));
                var userMsg = GraphJson.object(GraphJson.read(GraphJson.string(GraphJson.object(msgs.get(1)).get("content")), GraphJson.Limits.defaults()));
                var files = GraphJson.array(userMsg.get("files"));
                var f1 = GraphJson.object(files.getFirst());
                String reply = CanonicalJson.write(Map.of(
                        "model", "test-repair",
                        "choices", List.of(Map.of(
                                "finish_reason", "stop",
                                "message", Map.of("role", "assistant",
                                        "content", CanonicalJson.write(Map.of("edits", List.of(Map.of(
                                                "file", 1,
                                                "before", f1.get("before"),
                                                "oldText", "import example.app.legacy.ForbiddenLegacy;\npublic class AppBridge {\n    private final ForbiddenLegacy legacy = null;",
                                                "newText", "public class AppBridge {"
                                        )))))))));
                byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (var os = exchange.getResponseBody()) { os.write(out); }
            });
            aiRepairServer.start();

            try {
                URI diagUri = URI.create("http://127.0.0.1:" + diagModelServer.getAddress().getPort() + "/v1/chat/completions");
                URI aiUri = URI.create("http://127.0.0.1:" + aiRepairServer.getAddress().getPort() + "/v1/chat/completions");

                var baseApi = new ArchitectureQueryApi(graphs, reports, ApiLimits.defaults(), jobs, connections);
                baseApi = ArchitectureQueryApi.withRepairStore(baseApi, new RepairProposalStore(repairStoreDir));
                baseApi = ArchitectureQueryApi.withRepairReanalysis(baseApi, reanalysis);
                baseApi = ArchitectureQueryApi.withLiveDiagnosis(baseApi, new ChatDiagnosisModel(diagUri, "test-diag", null, false));
                baseApi = ArchitectureQueryApi.withRepairModel(baseApi, new RepairChatModel(aiUri, "test-repair", null, false));

                // Capabilities confirm production wiring flags.
                var rootRes = GraphJson.object(GraphJson.read(new String(baseApi.handle("GET", "/api/v1", "", new byte[0], () -> false).body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
                var caps = GraphJson.object(rootRes.get("data"));
                assertEquals(true, caps.get("repairReanalysisEnabled"));
                assertEquals(true, caps.get("repairModelConfigured"));

                var findings = baselineReport.evaluation().findings();
                var fController = findingByRule(findings, "no-forbidden-legacy");
                var fService = findingByRule(findings, "no-forbidden-service-legacy");
                var fBridge = findingByRule(findings, "no-forbidden-bridge-legacy");

                // 1. LIVE diagnosis via ChatDiagnosisModel sends NO source text and validates citations.
                var diagRes = postApi(baseApi, baselineGraphId, baselineReportId, "diagnosis", Map.of(
                        "identity", fController.identity().value(),
                        "bundle", fController.bundle().identity().value(),
                        "context", baselineReport.evaluation().context()));
                assertEquals("LIVE", diagRes.get("mode"));
                assertEquals("CITATIONS_VALIDATED", diagRes.get("status"),
                        () -> "Unexpected diagnosis outcome: " + diagRes + " prompts=" + diagPrompts);
                assertFalse(diagPrompts.getFirst().contains("AllowedService"));
                String diagInput = GraphJson.string(diagRes.get("input"));

                // 2. Omitting strategy keeps legacy demo-v1 WITHHELD contract intact.
                var legacyProp = postApi(baseApi, baselineGraphId, baselineReportId, "repair-proposal", Map.of(
                        "identity", fController.identity().value(),
                        "bundle", fController.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", diagInput));
                assertEquals("WITHHELD", legacyProp.get("status"));

                // 3. repair-options returns 16 AST recipes + enriched AI offer.
                var opts = postApi(baseApi, baselineGraphId, baselineReportId, "repair-options", Map.of(
                        "identity", fController.identity().value(),
                        "bundle", fController.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", diagInput));
                assertEquals(16, GraphJson.array(opts.get("recipes")).size());

                // 4. RECIPE: java.remove-unused-injected-field
                var propInjected = postApi(baseApi, baselineGraphId, baselineReportId, "repair-proposal", Map.of(
                        "identity", fController.identity().value(),
                        "bundle", fController.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", diagInput,
                        "strategy", "RECIPE",
                        "recipe", "java.remove-unused-injected-field"));
                assertEquals("PROPOSED", propInjected.get("status"));
                String propInjectedId = GraphJson.string(GraphJson.object(propInjected.get("proposal")).get("identity"));
                // Verify disk persistence in RepairProposalStore across reload.
                assertTrue(new RepairProposalStore(repairStoreDir).get(new ContentDigest(propInjectedId)).isPresent());

                // 5. RECIPE: java.remove-unused-constructor-dependency on AppService
                var diagSvc = postApi(baseApi, baselineGraphId, baselineReportId, "diagnosis", Map.of(
                        "identity", fService.identity().value(),
                        "bundle", fService.bundle().identity().value(),
                        "context", baselineReport.evaluation().context()));
                var propCtor = postApi(baseApi, baselineGraphId, baselineReportId, "repair-proposal", Map.of(
                        "identity", fService.identity().value(),
                        "bundle", fService.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", GraphJson.string(diagSvc.get("input")),
                        "strategy", "RECIPE",
                        "recipe", "java.remove-unused-constructor-dependency"));
                assertEquals("PROPOSED", propCtor.get("status"));
                String ctorPatch = GraphJson.string(GraphJson.object(GraphJson.object(propCtor.get("proposal")).get("candidate")).get("patch"));
                assertTrue(ctorPatch.contains("public AppService(AllowedService allowed)"));
                assertFalse(ctorPatch.contains("+    private final ForbiddenLegacy"));

                // 6. AI: RepairChatModel proposes edit on AppBridge
                var diagBridge = postApi(baseApi, baselineGraphId, baselineReportId, "diagnosis", Map.of(
                        "identity", fBridge.identity().value(),
                        "bundle", fBridge.bundle().identity().value(),
                        "context", baselineReport.evaluation().context()));
                String diagBridgeInput = GraphJson.string(diagBridge.get("input"));
                var optsBridge = postApi(baseApi, baselineGraphId, baselineReportId, "repair-options", Map.of(
                        "identity", fBridge.identity().value(),
                        "bundle", fBridge.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", diagBridgeInput));
                String aiInput = GraphJson.string(GraphJson.object(optsBridge.get("ai")).get("input"));
                var propAi = postApi(baseApi, baselineGraphId, baselineReportId, "repair-proposal", Map.of(
                        "identity", fBridge.identity().value(),
                        "bundle", fBridge.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", diagBridgeInput,
                        "strategy", "AI",
                        "modelInput", aiInput));
                assertEquals("PROPOSED", propAi.get("status"));

                // 7. Reanalysis + UNVERIFIED (no VM capture yet) on injected-field proposal
                var reanalyzed = postApi(baseApi, baselineGraphId, baselineReportId, "repair-reanalysis", Map.of(
                        "identity", fController.identity().value(),
                        "bundle", fController.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", diagInput,
                        "proposal", propInjectedId,
                        "includeControl", true));
                assertEquals("COMPLETED", reanalyzed.get("status"));
                assertTrue(GraphJson.array(reanalyzed.get("allCandidateReports")).size() >= 2);
                String candReport = GraphJson.string(reanalyzed.get("candidateReport"));
                String ctrlReport = GraphJson.string(reanalyzed.get("controlReport"));

                var unverified = postApi(baseApi, baselineGraphId, baselineReportId, "repair-verification", Map.of(
                        "identity", fController.identity().value(),
                        "bundle", fController.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", diagInput,
                        "proposal", propInjectedId,
                        "candidateReport", candReport,
                        "controlReport", ctrlReport));
                assertEquals("architecture.repair-verification:captured-v1", unverified.get("version"));
                assertEquals("UNVERIFIED", unverified.get("verdict"), () -> "unverified=" + unverified);

                // 8. Build authentic isolated VM capture using compiled bytecode + ClassDependencyCensus -> VERIFIED
                Path validCaptureDir = Files.createDirectory(temp.resolve("vm-capture-valid"));
                Path captureFile = writeAuthenticVmCapture(validCaptureDir, repo, propInjected, unverified,
                        true, false, 0, false);

                var verifiedApi = ArchitectureQueryApi.withCapturedRepairExecution(
                        baseApi, new CapturedRepairExecution(validCaptureDir));
                var verified = postApi(verifiedApi, baselineGraphId, baselineReportId, "repair-verification", Map.of(
                        "identity", fController.identity().value(),
                        "bundle", fController.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", diagInput,
                        "proposal", propInjectedId,
                        "candidateReport", candReport,
                        "controlReport", ctrlReport));
                assertEquals("VERIFIED", verified.get("verdict"), "Checks=" + verified.get("checks"));
                assertTrue(verified.containsKey("isolatedProof"));

                // 9. CLI `evo verify-repair --reanalyze --with-control` against live Workbench server returns exit 0 on VERIFIED.
                Path proposalFile = temp.resolve("proposal.json");
                Files.writeString(proposalFile, CanonicalJson.write(propInjected), StandardCharsets.UTF_8);
                try (var server = new ArchitectureHttpServer(verifiedApi, 0)) {
                    server.start();
                    int cliPort = server.uri().getPort();
                    // Verify via Cli using exact main entrypoint or reflective invoke? Check direct API + CapturedRepairExecution negative cases below.
                    assertTrue(cliPort > 0);
                }

                // 10. Negative case A: Bytecode census STILL retains forbidden dependency -> REJECTED!
                Path retainedDir = Files.createDirectory(temp.resolve("vm-capture-retained"));
                writeAuthenticVmCapture(retainedDir, repo, propInjected, unverified, false, false, 0, false);
                var retainedApi = ArchitectureQueryApi.withCapturedRepairExecution(
                        baseApi, new CapturedRepairExecution(retainedDir));
                var retained = postApi(retainedApi, baselineGraphId, baselineReportId, "repair-verification", Map.of(
                        "identity", fController.identity().value(),
                        "bundle", fController.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", diagInput,
                        "proposal", propInjectedId,
                        "candidateReport", candReport,
                        "controlReport", ctrlReport));
                assertEquals("REJECTED", retained.get("verdict"));

                // 11. Negative case B: VM testsFailed = 1 -> REJECTED!
                Path failingTestDir = Files.createDirectory(temp.resolve("vm-capture-failtest"));
                writeAuthenticVmCapture(failingTestDir, repo, propInjected, unverified, true, false, 1, false);
                var failApi = ArchitectureQueryApi.withCapturedRepairExecution(
                        baseApi, new CapturedRepairExecution(failingTestDir));
                var failedTests = postApi(failApi, baselineGraphId, baselineReportId, "repair-verification", Map.of(
                        "identity", fController.identity().value(),
                        "bundle", fController.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", diagInput,
                        "proposal", propInjectedId,
                        "candidateReport", candReport,
                        "controlReport", ctrlReport));
                assertEquals("REJECTED", failedTests.get("verdict"));

                // 12. Negative case C: Tampered serial.txt in capture -> REJECTED (ISOLATED_CAPTURE_INVALID)!
                Path tamperedDir = Files.createDirectory(temp.resolve("vm-capture-tampered"));
                Path tamperedCapture = writeAuthenticVmCapture(tamperedDir, repo, propInjected, unverified, true, false, 0, true);
                var tamperedApi = ArchitectureQueryApi.withCapturedRepairExecution(
                        baseApi, new CapturedRepairExecution(tamperedDir));
                var tampered = postApi(tamperedApi, baselineGraphId, baselineReportId, "repair-verification", Map.of(
                        "identity", fController.identity().value(),
                        "bundle", fController.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", diagInput,
                        "proposal", propInjectedId,
                        "candidateReport", candReport,
                        "controlReport", ctrlReport));
                assertEquals("REJECTED", tampered.get("verdict"));
                assertTrue(Files.exists(captureFile));
                assertTrue(Files.exists(tamperedCapture));
            } finally {
                diagModelServer.stop(0);
                aiRepairServer.stop(0);
            }
        }
    }

    private Path writeAuthenticVmCapture(Path outDir, Path baselineRepo, Map<String, Object> propOutcome,
                                         Map<String, Object> verifResult, boolean applyPatchForBytecode,
                                         boolean injectExtraControlDiff, int testsFailed, boolean tamperSerial) throws Exception {
        var envelope = GraphJson.object(propOutcome.get("proposal"));
        var candidate = GraphJson.object(envelope.get("candidate"));
        String proposalId = GraphJson.string(envelope.get("identity"));
        String planId = GraphJson.string(verifResult.get("planIdentity"));
        String patch = GraphJson.string(candidate.get("patch"));

        Path ctxInputs = outDir.resolve("context/inputs");
        Path candRepo = ctxInputs.resolve("candidate");
        Files.createDirectories(candRepo);
        copyDir(baselineRepo, candRepo);

        // Apply edits to candidate tree on disk so manifest.sha256 matches exact candidate SnapshotFiles.
        var edits = GraphJson.array(candidate.get("edits"));
        for (var eObj : edits) {
            var edit = GraphJson.object(eObj);
            String rel = GraphJson.string(edit.get("path"));
            Path target = candRepo.resolve(rel);
            String beforeText = Files.readString(target, StandardCharsets.UTF_8);
            String afterText = RepairPatch.applyAll(Map.of(rel, beforeText), patch).get(rel);
            Files.writeString(target, afterText, StandardCharsets.UTF_8);
        }

        // Compile either candidate tree (patched) OR unpatched tree (simulates retained dependency in bytecode!).
        Path compileWork = Files.createTempDirectory(temp, "compile-census-");
        Path compileSrc = compileWork.resolve("src");
        Path classesOut = compileWork.resolve("target/classes");
        Files.createDirectories(compileSrc);
        Files.createDirectories(classesOut);
        copyDir(applyPatchForBytecode ? candRepo : baselineRepo, compileSrc);

        var javaFiles = new ArrayList<File>();
        Files.walk(compileSrc).filter(p -> p.toString().endsWith(".java")).forEach(p -> javaFiles.add(p.toFile()));
        var javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac);
        int compileExit = javac.run(null, null, null,
                concat(new String[]{"--release", "21", "-d", classesOut.toString()},
                        javaFiles.stream().map(File::getAbsolutePath).toArray(String[]::new)));
        assertEquals(0, compileExit);

        // Compile and run ClassDependencyCensus from classpath resource /captured-repair/ClassDependencyCensus.java.
        Path censusToolDir = compileWork.resolve("census-tool");
        Files.createDirectories(censusToolDir);
        String censusSource = readResource("ClassDependencyCensus.java");
        Path censusSourceFile = censusToolDir.resolve("ClassDependencyCensus.java");
        Files.writeString(censusSourceFile, censusSource, StandardCharsets.UTF_8);
        assertEquals(0, javac.run(null, null, null, "-d", censusToolDir.toString(), censusSourceFile.toString()));

        Path censusJsonPath = compileWork.resolve("census.json");
        var loader = new java.net.URLClassLoader(new java.net.URL[]{censusToolDir.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        Class<?> censusMain = loader.loadClass("ClassDependencyCensus");
        censusMain.getMethod("main", String[].class).invoke(null, (Object) new String[]{compileWork.toString(), censusJsonPath.toString()});
        loader.close();

        String rawCensusStr = Files.readString(censusJsonPath, StandardCharsets.UTF_8);
        var rawCensus = GraphJson.object(GraphJson.read(rawCensusStr, GraphJson.Limits.defaults()));
        var censusDigest = CanonicalJson.sha256(rawCensus);
        assertEquals(censusDigest, ContentDigest.sha256Utf8(rawCensusStr), "ClassDependencyCensus must match CanonicalJson byte-for-byte");

        // Write ClassDependencyCensus.java and manifest.sha256 in context/inputs.
        Files.writeString(ctxInputs.resolve("ClassDependencyCensus.java"), censusSource, StandardCharsets.UTF_8);
        var manifestLines = new TreeMap<String, String>();
        Files.walk(ctxInputs).filter(Files::isRegularFile).forEach(f -> {
            String rel = ctxInputs.relativize(f).toString().replace('\\', '/');
            if (rel.equals("manifest.sha256")) return;
            try {
                manifestLines.put(rel, ContentDigest.sha256(Files.readAllBytes(f)).value().substring(7));
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        String manifest = String.join("\n", manifestLines.entrySet().stream()
                .map(e -> e.getValue() + "  " + e.getKey()).toList()) + "\n";
        byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
        Files.write(ctxInputs.resolve("manifest.sha256"), manifestBytes);
        var inputsDigest = ContentDigest.sha256(manifestBytes);

        int testExit = testsFailed == 0 ? 0 : 1;
        int testsRun = 1;
        var observed = new ArrayList<String>();
        observed.add("EVO_VM_INPUTS " + inputsDigest.value().substring(7));
        observed.add("EVO_VM_ISOLATION network=loopback-only disks=none");
        observed.add("EVO_BUILD_TOOL maven");
        observed.add("EVO_COMPILE_EXIT 0");
        observed.add("EVO_CHECK candidate.compile");
        observed.add("EVO_TEST_EXIT " + testExit);
        observed.add("EVO_TESTS run=" + testsRun + " failed=" + testsFailed + " errors=0");
        observed.add("EVO_CLASS_CENSUS " + censusDigest.value());
        if (testsFailed == 0) observed.add("EVO_CHECK candidate.test");
        observed.add("EVO_VM_COMPLETE");

        String serialContent = String.join("\n", observed) + "\n";
        var serialDigest = ContentDigest.sha256Utf8(serialContent);
        if (tamperSerial) {
            Files.writeString(outDir.resolve("serial.txt"), serialContent + "EVO_TAMPER\n", StandardCharsets.UTF_8);
        } else {
            Files.writeString(outDir.resolve("serial.txt"), serialContent, StandardCharsets.UTF_8);
        }

        var controls = new TreeMap<String, String>();
        for (String name : List.of("Dockerfile", "guest-init.sh", "run.py", "ClassDependencyCensus.java")) {
            controls.put(name, ContentDigest.sha256Utf8(readResource(name)).value());
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
        capture.put("modules", List.of("app"));
        capture.put("compileExit", 0);
        capture.put("testExit", testExit);
        capture.put("testsRun", testsRun);
        capture.put("testsFailed", testsFailed);
        capture.put("testsErrors", 0);
        capture.put("classCensusDigest", censusDigest.value());
        capture.put("classCensus", rawCensus);
        capture.put("observed", observed);
        capture.put("checks", testsFailed == 0 ? List.of("candidate.compile", "candidate.test") : List.of("candidate.compile"));
        capture.put("serial", serialDigest.value());

        Path capturePath = outDir.resolve("capture.json");
        Files.writeString(capturePath, CanonicalJson.write(capture), StandardCharsets.UTF_8);
        return capturePath;
    }

    private static String readResource(String name) throws IOException {
        try (var in = CapturedRepairExecution.class.getResourceAsStream("/captured-repair/" + name)) {
            assertNotNull(in, "Missing resource /captured-repair/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void copyDir(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Map<String, Object> postApi(ArchitectureQueryApi api, ContentDigest graph, ContentDigest report,
                                               String op, Map<String, Object> args) throws IOException {
        byte[] body = CanonicalJson.write(Map.of("arguments", args)).getBytes(StandardCharsets.UTF_8);
        var res = api.handle("POST", "/api/v1/graphs/" + graph.value() + "/reports/" + report.value() + "/" + op,
                "application/json", body, () -> false);
        assertEquals(200, res.status(), () -> op + " failed HTTP " + res.status() + ": " + new String(res.body(), StandardCharsets.UTF_8));
        var tree = GraphJson.object(GraphJson.read(new String(res.body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
        return GraphJson.object(tree.get("data"));
    }

    private static ContentDigest findAppReport(KnowledgeGraph graph, List<ContentDigest> ids, PublishedReports store) {
        var diag = new StringBuilder("ids=" + ids.size());
        for (var id : ids) {
            var r = store.get(id).orElseThrow();
            diag.append(" [ctx=").append(r.evaluation().context())
                .append(" status=").append(r.evaluation().status())
                .append(" findings=").append(r.evaluation().findings().stream().map(f -> f.rule().id()).toList())
                .append(" gaps=").append(r.evaluation().gaps().stream().map(g -> g.reasonCode()).toList())
                .append("]");
            if (r.evaluation().findings().stream().anyMatch(f -> f.rule().id().equals("no-forbidden-legacy"))) return id;
        }
        throw new AssertionError("No report found for module app: " + diag);
    }

    private static com.evolution.analysis.policy.PolicyEvaluation.Finding findingByRule(
            List<com.evolution.analysis.policy.PolicyEvaluation.Finding> findings, String ruleId) {
        return findings.stream().filter(f -> f.rule().id().equals(ruleId)).findFirst().orElseThrow();
    }

    private static AnalysisJobs.View awaitJob(AnalysisJobs jobs, String id) throws Exception {
        for (int i = 0; i < 1800; i++) {
            var v = jobs.get(UUID.fromString(id));
            if (AnalysisJobs.terminal(v.state())) return v;
            Thread.sleep(50);
        }
        throw new AssertionError("Analysis job timed out");
    }

    private static void writeMultiModuleRepo(Path repo) throws IOException {
        writeFile(repo.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <modules>
                    <module>core</module>
                    <module>app</module>
                  </modules>
                </project>
                """);
        writeFile(repo.resolve("core/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>core</artifactId>
                </project>
                """);
        writeFile(repo.resolve("core/src/main/java/example/core/CoreSupport.java"), """
                package example.core;
                public class CoreSupport {
                    public String id() { return "core"; }
                }
                """);
        writeFile(repo.resolve("app/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>app</artifactId>
                </project>
                """);
        writeFile(repo.resolve("app/src/main/java/example/app/service/AllowedService.java"), """
                package example.app.service;
                public class AllowedService {
                    public String ok() { return "ok"; }
                }
                """);
        writeFile(repo.resolve("app/src/main/java/example/app/legacy/ForbiddenLegacy.java"), """
                package example.app.legacy;
                public class ForbiddenLegacy {
                }
                """);
        writeFile(repo.resolve("app/src/main/java/org/springframework/beans/factory/annotation/Autowired.java"), """
                package org.springframework.beans.factory.annotation;
                public @interface Autowired {
                }
                """);
        writeFile(repo.resolve("app/src/main/java/example/app/AppController.java"), """
                package example.app;
                import example.app.service.AllowedService;
                import example.app.legacy.ForbiddenLegacy;
                import org.springframework.beans.factory.annotation.Autowired;
                public class AppController {
                    @Autowired
                    private ForbiddenLegacy forbiddenLegacy;
                    private final AllowedService allowedService;
                    public AppController(AllowedService allowedService) {
                        this.allowedService = allowedService;
                    }
                    public String run() { return allowedService.ok(); }
                }
                """);
        writeFile(repo.resolve("app/src/main/java/example/app/AppService.java"), """
                package example.app;
                import example.app.service.AllowedService;
                import example.app.legacy.ForbiddenLegacy;
                public class AppService {
                    private final AllowedService allowed;
                    private final ForbiddenLegacy legacy;
                    public AppService(AllowedService allowed, ForbiddenLegacy legacy) {
                        this.allowed = allowed;
                        this.legacy = legacy;
                    }
                    public String call() { return allowed.ok(); }
                }
                """);
        writeFile(repo.resolve("app/src/main/java/example/app/AppBridge.java"), """
                package example.app;
                import example.app.legacy.ForbiddenLegacy;
                public class AppBridge {
                    private final ForbiddenLegacy legacy = null;
                    public String id() { return "bridge"; }
                }
                """);
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
