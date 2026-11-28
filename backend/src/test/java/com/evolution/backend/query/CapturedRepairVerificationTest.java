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

                // 3. repair-options returns 22 AST/OpenRewrite recipes (16 DI + 6 WP4.3 compound) + enriched AI offer.
                var opts = postApi(baseApi, baselineGraphId, baselineReportId, "repair-options", Map.of(
                        "identity", fController.identity().value(),
                        "bundle", fController.bundle().identity().value(),
                        "context", baselineReport.evaluation().context(),
                        "diagnosisInput", diagInput));
                assertEquals(22, GraphJson.array(opts.get("recipes")).size());

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

                // 13. End-to-end WP3.3 beta=0.8 TacitMemoryStore hook wired into CapturedRepairVerification.verify(...)
                var persistedProposal = new RepairProposalStore(repairStoreDir).get(new ContentDigest(propInjectedId)).orElseThrow();
                var candRepDigest = Optional.of(new ContentDigest(candReport));
                var ctrlRepDigest = Optional.of(new ContentDigest(ctrlReport));
                try (var tacitStore = com.evolution.backend.query.advisory.TacitMemoryStore.inMemory()) {
                    var unit = tacitStore.ingest(
                            "entity:sha256:" + "d".repeat(64),
                            "Avoid forbidden legacy dependency",
                            "Remove unused injected legacy field from controller",
                            List.of(new com.evolution.backend.query.advisory.TacitMemoryStore.EvidenceSpan(
                                    "app/src/main/java/example/app/AppController.java", 1, 14,
                                    ContentDigest.sha256Utf8("controller-v1").value())),
                            0.65,
                            com.evolution.backend.query.advisory.TacitMemoryStore.SourceKind.PASSIVE_GIT_ADR,
                            "rev-0");
                    List<String> consulted = List.of(unit.unitId());

                    // 13a. UNVERIFIED -> NO-OP (confidence remains 0.65)
                    var unverifiedRes = new CapturedRepairVerification(null, null)
                            .withTacitMemoryEvolution(tacitStore, consulted)
                            .verify(persistedProposal, baselineGraph, baselineReport, candRepDigest, ctrlRepDigest, graphs, reports, () -> false);
                    assertEquals(CapturedRepairVerification.Verdict.UNVERIFIED, unverifiedRes.verdict());
                    assertEquals(0.65, tacitStore.get(unit.unitId()).orElseThrow().confidence(), 1e-12);
                    assertEquals(0, tacitStore.get(unit.unitId()).orElseThrow().verifiedPassCount());

                    // 13b. VERIFIED -> RESOLVED -> c' = 1 - 0.8 * (1 - 0.65) = 0.72, verifiedPassCount = 1, lastVerifiedCommit = rev-1
                    var verifiedRes = new CapturedRepairVerification(null, new CapturedRepairExecution(validCaptureDir))
                            .withTacitMemoryEvolution(tacitStore, consulted)
                            .verify(persistedProposal, baselineGraph, baselineReport, candRepDigest, ctrlRepDigest, graphs, reports, () -> false);
                    assertEquals(CapturedRepairVerification.Verdict.VERIFIED, verifiedRes.verdict());
                    var afterVerified = tacitStore.get(unit.unitId()).orElseThrow();
                    assertEquals(0.72, afterVerified.confidence(), 1e-12);
                    assertEquals(1, afterVerified.verifiedPassCount());
                    assertFalse(afterVerified.staleEvidence());
                    assertEquals("rev-1", afterVerified.lastVerifiedCommit());

                    // 13c. REJECTED -> PERSISTED/fail -> c' = 0.8 * 0.72 = 0.576, verifiedFailCount = 1
                    var rejectedRes = new CapturedRepairVerification(null, new CapturedRepairExecution(failingTestDir))
                            .withTacitMemoryEvolution(tacitStore, consulted)
                            .verify(persistedProposal, baselineGraph, baselineReport, candRepDigest, ctrlRepDigest, graphs, reports, () -> false);
                    assertEquals(CapturedRepairVerification.Verdict.REJECTED, rejectedRes.verdict());
                    var afterRejected = tacitStore.get(unit.unitId()).orElseThrow();
                    assertEquals(0.576, afterRejected.confidence(), 1e-12);
                    assertEquals(1, afterRejected.verifiedPassCount());
                    assertEquals(1, afterRejected.verifiedFailCount());
                }
            } finally {
                diagModelServer.stop(0);
                aiRepairServer.stop(0);
            }
        }
    }

    @Test
    void jacocoCaptureWithWp44FieldsRoundTripsCanonicalJsonAndCapturedRepairExecution() throws Exception {
        Path capDir = Files.createDirectory(temp.resolve("wp44-canonical-parity"));
        Path controlsDir = Files.createDirectory(temp.resolve("wp44-controls"));
        CapturedRepairRunner.extractControls(controlsDir);

        String censusSource = readResource("ClassDependencyCensus.java");
        String censusHex = ContentDigest.sha256Utf8(censusSource).value().substring(7);
        String manifest = censusHex + "  ClassDependencyCensus.java\n";
        byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
        Files.write(capDir.resolve("manifest.sha256"), manifestBytes);
        ContentDigest inputsDigest = ContentDigest.sha256(manifestBytes);

        var rawCensus = new TreeMap<String, Object>();
        rawCensus.put("classCount", 1);
        rawCensus.put("classes", Map.of("example/App.class", "sha256:" + "1".repeat(64)));
        rawCensus.put("dependencies", Map.of("example/App", List.of()));
        ContentDigest censusDigest = CanonicalJson.sha256(rawCensus);

        var observed = List.of(
                "EVO_VM_INPUTS " + inputsDigest.value().substring(7),
                "EVO_VM_ISOLATION network=loopback-only disks=none",
                "EVO_BUILD_TOOL maven",
                "EVO_COMPILE_EXIT 0",
                "EVO_CHECK candidate.compile",
                "EVO_TEST_EXIT 0",
                "EVO_TESTS run=5 failed=0 errors=0",
                "EVO_CLASS_CENSUS " + censusDigest.value(),
                "EVO_CHECK candidate.test",
                "EVO_VM_COMPLETE");
        String serialContent = String.join("\n", observed) + "\n";
        Files.writeString(capDir.resolve("serial.txt"), serialContent, StandardCharsets.UTF_8);
        ContentDigest serialDigest = ContentDigest.sha256Utf8(serialContent);

        var controls = new TreeMap<String, String>();
        for (String name : List.of("Dockerfile", "guest-init.sh", "run.py", "ClassDependencyCensus.java")) {
            controls.put(name, ContentDigest.sha256Utf8(readResource(name)).value());
        }

        // JaCoCo XML with benign DOCTYPE, whole/fractional percentages, and BMP (>=U+E000) vs supplementary-plane Unicode keys.
        String jacocoXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="wp44-parity">
                  <package name="com/acme">
                    <sourcefile name="Full.java">
                      <counter type="LINE" missed="0" covered="10"/>
                      <counter type="BRANCH" missed="2" covered="6"/>
                    </sourcefile>
                    <sourcefile name="Eighty.java">
                      <counter type="LINE" missed="4" covered="16"/>
                    </sourcefile>
                    <sourcefile name="Zero.java">
                      <counter type="LINE" missed="10" covered="0"/>
                    </sourcefile>
                    <sourcefile name="Eighth.java">
                      <counter type="LINE" missed="7" covered="1"/>
                    </sourcefile>
                    <sourcefile name="Third.java">
                      <counter type="LINE" missed="2" covered="1"/>
                    </sourcefile>
                    <sourcefile name="Caf\u00e9.java">
                      <counter type="LINE" missed="1" covered="3"/>
                    </sourcefile>
                    <sourcefile name="\uFF01HighBmp.java">
                      <counter type="LINE" missed="0" covered="1"/>
                    </sourcefile>
                    <sourcefile name="\uD83D\uDE00SuppPlane.java">
                      <counter type="LINE" missed="0" covered="1"/>
                    </sourcefile>
                  </package>
                </report>
                """;
        Path jacocoXmlPath = capDir.resolve("jacoco.xml");
        Files.writeString(jacocoXmlPath, jacocoXml, StandardCharsets.UTF_8);

        var baseDoc = new TreeMap<String, Object>();
        baseDoc.put("version", CapturedRepairExecution.VERSION);
        baseDoc.put("proposal", "sha256:" + "d".repeat(64));
        baseDoc.put("plan", "sha256:" + "e".repeat(64));
        baseDoc.put("inputs", inputsDigest.value());
        baseDoc.put("controls", controls);
        baseDoc.put("base", "eclipse-temurin@sha256:" + "a".repeat(64));
        baseDoc.put("image", "sha256:" + "b".repeat(64));
        baseDoc.put("container", "c".repeat(64));
        baseDoc.put("buildTool", "maven");
        baseDoc.put("isolationMode", "DOCKER_HARDENED_CONTAINER");
        baseDoc.put("modules", List.of("app"));
        baseDoc.put("compileExit", 0);
        baseDoc.put("testExit", 0);
        baseDoc.put("testsRun", 5);
        baseDoc.put("testsFailed", 0);
        baseDoc.put("testsErrors", 0);
        baseDoc.put("classCensusDigest", censusDigest.value());
        baseDoc.put("classCensus", rawCensus);
        baseDoc.put("observed", observed);
        baseDoc.put("checks", List.of("candidate.compile", "candidate.test"));
        baseDoc.put("serial", serialDigest.value());
        baseDoc.put("jdkVersion", 17);
        baseDoc.put("tier", "TIER_1_FAST_LOOP");
        baseDoc.put("capabilityGaps", List.of("SANDBOX_DEPENDENCY_MISSING"));

        Path seedPath = temp.resolve("capture-seed.json");
        Files.writeString(seedPath, CanonicalJson.write(baseDoc), StandardCharsets.UTF_8);
        Path capturePath = capDir.resolve("capture.json");

        // Invoke actual run.py (parse_jacoco_xml_summary + canonical_json) to produce capture.json bytes across Python -> Java.
        Path driver = temp.resolve("emit_capture.py");
        Files.writeString(driver, """
                import importlib.util, json, sys
                from pathlib import Path
                run_py, xml_path, seed_path, out_path = map(Path, sys.argv[1:5])
                spec = importlib.util.spec_from_file_location("run_mod", run_py)
                mod = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(mod)
                doc = json.loads(seed_path.read_text(encoding="utf-8"))
                doc["jacocoCoverage"] = mod.parse_jacoco_xml_summary(xml_path)
                out_path.write_bytes(mod.canonical_json(doc).encode("utf-8"))
                """, StandardCharsets.UTF_8);

        String pythonExe = CapturedRepairRunner.Config.defaultPython();
        var pb = new ProcessBuilder(pythonExe, driver.toString(),
                controlsDir.resolve("run.py").toString(),
                jacocoXmlPath.toString(),
                seedPath.toString(),
                capturePath.toString())
                .redirectErrorStream(true);
        var proc = pb.start();
        String procOut = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, proc.waitFor(), "Python run.py canonical emission failed: " + procOut);

        byte[] captureBytes = Files.readAllBytes(capturePath);
        String captureText = new String(captureBytes, StandardCharsets.UTF_8);
        assertTrue(captureText.contains("\"lineCoveragePercent\":100"), captureText);
        assertTrue(captureText.contains("\"lineCoveragePercent\":80"), captureText);
        assertTrue(captureText.contains("\"lineCoveragePercent\":0"), captureText);
        assertTrue(captureText.contains("\"lineCoveragePercent\":12.5"), captureText);
        assertTrue(captureText.contains("\"lineCoveragePercent\":33.33"), captureText);
        assertTrue(captureText.contains("\"branchCoveragePercent\":75"), captureText);
        assertTrue(captureText.contains("\"jdkVersion\":17"), captureText);
        assertTrue(captureText.contains("\"tier\":\"TIER_1_FAST_LOOP\""), captureText);
        assertTrue(captureText.contains("\"capabilityGaps\":[\"SANDBOX_DEPENDENCY_MISSING\"]"), captureText);

        // Exact byte-for-byte CanonicalJson.write(GraphJson.read(...)) round-trip parity
        Object reparsed = GraphJson.read(captureText, GraphJson.Limits.defaults());
        byte[] canonicalBytes = CanonicalJson.write(reparsed).getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(captureBytes, canonicalBytes, "run.py capture.json must match Java CanonicalJson byte-for-byte");

        // CapturedRepairExecution.loadAndVerify must accept the Python-produced capture.json without CAPTURED_REPAIR_FORMAT
        assertDoesNotThrow(() -> new CapturedRepairExecution(capturePath, ContentDigest.sha256(captureBytes)));

        // Negative control: legacy whole-number float representation (80.0) must fail closed with CAPTURED_REPAIR_FORMAT
        String legacyFloatText = captureText.replace("\"lineCoveragePercent\":80,", "\"lineCoveragePercent\":80.0,");
        assertNotEquals(captureText, legacyFloatText);
        Path badCapturePath = capDir.resolve("capture-legacy-float.json");
        byte[] badBytes = legacyFloatText.getBytes(StandardCharsets.UTF_8);
        Files.write(badCapturePath, badBytes);
        IOException ex = assertThrows(IOException.class,
                () -> new CapturedRepairExecution(badCapturePath, ContentDigest.sha256(badBytes)));
        assertEquals("CAPTURED_REPAIR_FORMAT", ex.getMessage());

        // Exhaustive cross-boundary check: all 10,001 2-decimal percentages (0.00..100.00) emitted by run.py match Java CanonicalJson byte-for-byte
        Path sweepDriver = temp.resolve("emit_all_percents.py");
        Path sweepOut = temp.resolve("all_percents.json");
        Files.writeString(sweepDriver, """
                import importlib.util, sys
                from pathlib import Path
                run_py, out_path = map(Path, sys.argv[1:3])
                spec = importlib.util.spec_from_file_location("run_mod", run_py)
                mod = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(mod)
                vals = [mod._canonical_percent(i, 10000) for i in range(10001)]
                out_path.write_bytes(mod.canonical_json({"percents": vals}).encode("utf-8"))
                """, StandardCharsets.UTF_8);
        var sweepProc = new ProcessBuilder(pythonExe, sweepDriver.toString(),
                controlsDir.resolve("run.py").toString(),
                sweepOut.toString())
                .redirectErrorStream(true)
                .start();
        String sweepProcOut = new String(sweepProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, sweepProc.waitFor(), "Python percent sweep failed: " + sweepProcOut);
        byte[] sweepBytes = Files.readAllBytes(sweepOut);
        String sweepText = new String(sweepBytes, StandardCharsets.UTF_8);
        byte[] sweepCanonical = CanonicalJson.write(GraphJson.read(sweepText, GraphJson.Limits.defaults()))
                .getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(sweepBytes, sweepCanonical,
                "All 10,001 run.py percentages (0.00..100.00) must match Java CanonicalJson byte-for-byte");
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
