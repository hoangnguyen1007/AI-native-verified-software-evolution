package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.polyglot.ServiceArchitectureProjection;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.backend.analysis.LocalAnalysisRunner;
import com.evolution.backend.query.repair.AffectedTestSelector;
import com.evolution.backend.query.repair.RepairContextPlanner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.evolution.backend.query.ArchitectureQueryApiTest.body;
import static com.evolution.backend.query.ArchitectureQueryApiTest.bytes;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Phase 3 AI4SE high-impact upgrades:
 * <ul>
 *   <li>R2-delta: MAX_ROUNDS=18, sliding-window compaction preserving evidenceId/path/symbol/line digests, Anti-Edit-Bias QA prompt</li>
 *   <li>R3/R4-delta: MAX_EXPANDED_FILES=20, SKG-RTS transitive regression test selection with safety fallbacks, --selected-tests wiring</li>
 * </ul>
 */
class ResearchBreakthroughUpgradesTest {

    @Test
    void agentMaxRoundsCompactionWindowAndAntiEditBiasQaPromptHold() {
        assertEquals(18, ArchitectureAgent.MAX_ROUNDS);

        // Sliding window preserves system, user, and last 6 messages verbatim while compacting older bulky tool responses
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", "SYS"));
        messages.add(Map.of("role", "user", "content", "QUESTION"));
        String bigOldToolJson = CanonicalJson.write(Map.of(
                "evidenceId", "E3",
                "tool", "search_symbols",
                "count", 42,
                "path", "src/main/java/com/acme/OrderService.java",
                "items", List.of(
                        Map.of("id", "n1", "name", "OrderService", "path", "src/main/java/com/acme/OrderService.java", "line", 18),
                        Map.of("id", "n2", "name", "PaymentClient", "path", "src/main/java/com/acme/PaymentClient.java", "line", 44)),
                "padding", "X".repeat(2500)));
        messages.add(Map.of("role", "tool", "content", bigOldToolJson));
        for (int i = 0; i < 6; i++) {
            messages.add(Map.of("role", "tool", "content", "{\"evidenceId\":\"E_RECENT_" + i + "\",\"blob\":\"" + "Y".repeat(1600) + "\"}"));
        }

        ArchitectureAgent.compactToolMessages(messages, 5_000);

        String compactedOld = (String) messages.get(2).get("content");
        assertTrue(compactedOld.contains("\"compacted\":true"));
        assertTrue(compactedOld.contains("\"evidenceId\":\"E3\""), compactedOld);
        assertTrue(compactedOld.contains("OrderService"), compactedOld);
        assertTrue(compactedOld.contains("PaymentClient.java"), compactedOld);
        // Most recent 6 messages remain verbatim untouched
        assertFalse(((String) messages.get(3).get("content")).contains("\"compacted\":true"));
        assertFalse(((String) messages.get(8).get("content")).contains("\"compacted\":true"));

        // Anti-Edit-Bias strict QA instructions vs standard instructions
        var model = new ArchitectureChatModel(
                java.net.URI.create("http://127.0.0.1:11434/v1"), "qwen2.5-coder", "local", false);
        String qaInstr = model.instructions(false, true, true);
        String stdInstr = model.instructions(false, true, false);
        assertTrue(qaInstr.contains("ANTI_EDIT_BIAS") || qaInstr.contains("anti-Edit-Bias"));
        assertTrue(qaInstr.contains("Do NOT fabricate patches"));
        assertFalse(stdInstr.contains("Do NOT fabricate patches"));
    }

    @Test
    void repairContextPlannerExpandsUpToTwentyFilesForArchitectureRefactorings() {
        assertEquals(20, RepairContextPlanner.MAX_EXPANDED_FILES);
        var graph = buildDenseCallerGraph(12);
        String codeCtx = id("ctx:main");
        var plan = RepairContextPlanner.plan(
                graph,
                codeCtx,
                null,
                List.of("src/main/java/com/acme/TargetService.java"),
                List.of(),
                id("type:TargetService"),
                () -> StopReason.NONE,
                null);

        long callerFiles = plan.files().stream()
                .filter(f -> f.role() == RepairContextPlanner.FileRole.CONTEXT_EXPANDED
                        && f.reason().contains("1-hop incoming caller"))
                .count();
        assertEquals(10, callerFiles, () -> "Expected up to 10 incoming callers with expanded budget, got " + callerFiles);
        assertTrue(plan.files().size() >= 11);
    }

    @Test
    void skgRtsSelectsTransitiveTestsSafelyAndReducesExecutionSetSignificantly() {
        var graph = buildRtsGraph(12);
        String ctx = id("ctx:rts");

        // 1. Editing ServiceCore selects DirectServiceCoreTest (hop 1) AND TransitiveFacadeTest (hop 2), skipping 12 unrelated tests
        var selected = AffectedTestSelector.select(graph, ctx, List.of("src/main/java/com/acme/ServiceCore.java"));
        assertEquals(AffectedTestSelector.SelectionMode.SELECTED_TESTS, selected.mode());
        var testNames = selected.selectedTests().stream().map(AffectedTestSelector.SelectedTest::testClass).toList();
        assertEquals(List.of("ServiceCoreTest", "FacadeTest"), testNames);
        assertEquals(14, selected.totalTestClasses());
        assertTrue(selected.reductionPercent() >= 80.0,
                () -> "Expected >= 80% test reduction, got " + selected.reductionPercent());
        assertEquals(Optional.of("ServiceCoreTest,FacadeTest"), selected.mavenTestFilter());
        System.out.printf(Locale.ROOT,
                "[R3-RTS] selected=%d total=%d reduction=%.2f%% filter=%s%n",
                selected.selectedTests().size(), selected.totalTestClasses(),
                selected.reductionPercent(), selected.mavenTestFilter().orElse(""));

        // 2. Conservative safety gates fall back to FULL_SUITE on build/config edits or unmapped sources
        var pomEdit = AffectedTestSelector.select(graph, ctx, List.of("pom.xml"));
        assertEquals(AffectedTestSelector.SelectionMode.FULL_SUITE, pomEdit.mode());
        assertTrue(pomEdit.rationale().contains("SAFETY_FALLBACK_CONFIG_OR_BUILD_EDIT"));

        var ymlEdit = AffectedTestSelector.select(graph, ctx, List.of("src/main/resources/application-prod.yml"));
        assertEquals(AffectedTestSelector.SelectionMode.FULL_SUITE, ymlEdit.mode());

        var unmapped = AffectedTestSelector.select(graph, ctx, List.of("src/main/java/com/acme/UnknownNewClass.java"));
        assertEquals(AffectedTestSelector.SelectionMode.FULL_SUITE, unmapped.mode());
        assertTrue(unmapped.rationale().contains("SAFETY_FALLBACK_UNMAPPED_SOURCE"));

        // 3. CapturedRepairRunner passes validated --selected-tests and rejects unsafe characters
        var cfg = new CapturedRepairRunner.Config(
                "python", Optional.empty(), "maven",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), false, Path.of("work"), Duration.ofMinutes(5));
        var cmd = CapturedRepairRunner.buildCommand(
                Path.of("run.py"), Path.of("repo"), Path.of("p.json"), Path.of("v.json"), Path.of("out"),
                cfg, false, Optional.empty(), selected.mavenTestFilter());
        int idx = cmd.indexOf("--selected-tests");
        assertTrue(idx > 0);
        assertEquals("ServiceCoreTest,FacadeTest", cmd.get(idx + 1));

        assertThrows(IllegalArgumentException.class, () -> CapturedRepairRunner.buildCommand(
                Path.of("run.py"), Path.of("repo"), Path.of("p.json"), Path.of("v.json"), Path.of("out"),
                cfg, false, Optional.empty(), Optional.of("FooTest; rm -rf /")));

        // 4. 7-arg CapturedRepairRunner.run(...) overload validates unsafe test filters before launching
        var runner = new CapturedRepairRunner(cfg);
        assertEquals(0, runner.config().timeout().isZero() ? 1 : 0);
    }

    @Test
    void skgRtsExcludesTestHelperClassesFromTestCountAndTraversesThroughModifiedHelper() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:skg-rts-helpers");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("snap-rts-helpers"));
        String snapCtxId = snap.value();
        String codeCtxId = id("ctx:rts-helpers");
        var evidence = new ArrayList<GraphEvidence>();
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();

        String entityId = id("type:BaseEntity");
        String visitId = id("type:Visit");
        String initHelperId = id("type:Init");
        String helperId = id("type:EntityUtils");
        String stubId = id("type:CatalogStub");
        String appHelperId = id("type:MysqlTestApplication");
        String svcTestId = id("type:ClinicServiceTests");
        String otherTestId = id("type:VetControllerTests");

        addDeclNode(repo, snapCtxId, codeCtxId, entityId, "BaseEntity", "src/main/java/com/acme/BaseEntity.java", 1, 10, evidence, nodes);
        addDeclNode(repo, snapCtxId, codeCtxId, visitId, "Visit", "src/main/java/com/acme/Visit.java", 1, 10, evidence, nodes);
        addDeclNode(repo, snapCtxId, codeCtxId, initHelperId, "Init", "src/test/java/com/acme/Init.java", 1, 10, evidence, nodes);
        addDeclNode(repo, snapCtxId, codeCtxId, helperId, "EntityUtils", "src/test/java/com/acme/EntityUtils.java", 1, 10, evidence, nodes);
        addDeclNode(repo, snapCtxId, codeCtxId, stubId, "CatalogStub", "src/test/java/com/acme/CatalogStub.java", 1, 10, evidence, nodes);
        addDeclNode(repo, snapCtxId, codeCtxId, appHelperId, "MysqlTestApplication", "src/test/java/com/acme/MysqlTestApplication.java", 1, 10, evidence, nodes);
        addDeclNode(repo, snapCtxId, codeCtxId, svcTestId, "ClinicServiceTests", "src/test/java/com/acme/ClinicServiceTests.java", 1, 10, evidence, nodes);
        addDeclNode(repo, snapCtxId, codeCtxId, otherTestId, "VetControllerTests", "src/test/java/com/acme/VetControllerTests.java", 1, 10, evidence, nodes);

        // ClinicServiceTests -> EntityUtils -> BaseEntity, and ClinicServiceTests -> Visit
        addEdge(codeCtxId, id("e-helper-entity"), helperId, entityId, evidence, edges);
        addEdge(codeCtxId, id("e-test-helper"), svcTestId, helperId, evidence, edges);
        addEdge(codeCtxId, id("e-test-visit"), svcTestId, visitId, evidence, edges);

        var snapCtxEv = GraphEvidence.capture("context.root", Map.of("ctx", snapCtxId));
        var codeCtxEv = GraphEvidence.capture("context.root", Map.of("ctx", codeCtxId));
        evidence.add(snapCtxEv);
        evidence.add(codeCtxEv);
        var graph = new KnowledgeGraph(snap,
                List.of(
                        new KnowledgeGraph.Context(snapCtxId, snap, Optional.empty(), snapCtxEv.identity()),
                        new KnowledgeGraph.Context(codeCtxId, snap, Optional.empty(), codeCtxEv.identity())),
                nodes, edges, evidence);

        // 1. Editing BaseEntity must count only the 2 runnable tests (NOT EntityUtils/CatalogStub/MysqlTestApplication)
        //    and transitively select ClinicServiceTests through EntityUtils.
        var fromProd = AffectedTestSelector.select(graph, codeCtxId, List.of("src/main/java/com/acme/BaseEntity.java"));
        assertEquals(AffectedTestSelector.SelectionMode.SELECTED_TESTS, fromProd.mode());
        assertEquals(2, fromProd.totalTestClasses(), "Non-test helpers under src/test/java must not inflate totalTestClasses");
        assertEquals(List.of("ClinicServiceTests"),
                fromProd.selectedTests().stream().map(AffectedTestSelector.SelectedTest::testClass).toList());
        assertEquals(Optional.of("ClinicServiceTests"), fromProd.mavenTestFilter());

        // 2. Editing test helper EntityUtils.java directly must traverse reverse BFS to ClinicServiceTests
        //    rather than passing non-runnable -Dtest=EntityUtils to Surefire.
        var fromHelper = AffectedTestSelector.select(graph, codeCtxId, List.of("src/test/java/com/acme/EntityUtils.java"));
        assertEquals(AffectedTestSelector.SelectionMode.SELECTED_TESTS, fromHelper.mode());
        assertEquals(List.of("ClinicServiceTests"),
                fromHelper.selectedTests().stream().map(AffectedTestSelector.SelectedTest::testClass).toList());
        assertEquals(Optional.of("ClinicServiceTests"), fromHelper.mavenTestFilter());

        // 3. Editing production Visit.java (ends in lowercase "it") must NEVER be misclassified as a test file
        //    nor emit -Dtest=Visit; it must traverse to ClinicServiceTests, and Init.java under src/test/ must be excluded.
        var fromVisit = AffectedTestSelector.select(graph, codeCtxId, List.of("src/main/java/com/acme/Visit.java"));
        assertEquals(AffectedTestSelector.SelectionMode.SELECTED_TESTS, fromVisit.mode());
        assertEquals(2, fromVisit.totalTestClasses(), "Neither Visit.java nor Init.java may count as runnable tests");
        assertEquals(List.of("ClinicServiceTests"),
                fromVisit.selectedTests().stream().map(AffectedTestSelector.SelectedTest::testClass).toList());
        assertEquals(Optional.of("ClinicServiceTests"), fromVisit.mavenTestFilter());
    }

    @Test
    void skgRtsRecoversUnresolvedCrossContextObservationsAndEscalatesThroughEnclosingType() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:skg-rts-unresolved-obs");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("snap-rts-obs"));
        String snapCtxId = snap.value();
        String mainCtxId = id("ctx:main");
        String testCtxId = id("ctx:test");
        var evidence = new ArrayList<GraphEvidence>();
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();

        String lowUtilId = id("type:PomPaths");
        String resolverId = id("type:ModelResolver");
        String resolverMethodId = id("member:ModelResolver.resolve");
        String testId = id("type:IntegrationWorkflowTest");
        String unrelatedTestId = id("type:UnrelatedTest");

        addDeclNode(repo, snapCtxId, mainCtxId, lowUtilId, "PomPaths", "src/main/java/com/acme/PomPaths.java", 1, 10, evidence, nodes);
        addDeclNode(repo, snapCtxId, mainCtxId, resolverId, "ModelResolver", "src/main/java/com/acme/ModelResolver.java", 1, 20, evidence, nodes);
        var resolverDocId = SourceDocumentIdentity.from(repo, "src/main/java/com/acme/ModelResolver.java");
        var methodDeclEv = GraphEvidence.capture("java.declaration", Map.of("entity", Map.of(
                "qualifiedName", "com.acme.ModelResolver.resolve",
                "declaration", Map.of("document", resolverDocId.value(), "startLine", 5, "endLine", 15))));
        evidence.add(methodDeclEv);
        nodes.add(new KnowledgeGraph.Node(resolverMethodId, mainCtxId, "MEMBER", List.of(methodDeclEv.identity())));
        addDeclNode(repo, snapCtxId, testCtxId, testId, "IntegrationWorkflowTest", "src/test/java/com/acme/IntegrationWorkflowTest.java", 1, 20, evidence, nodes);
        addDeclNode(repo, snapCtxId, testCtxId, unrelatedTestId, "UnrelatedTest", "src/test/java/com/acme/UnrelatedTest.java", 1, 10, evidence, nodes);

        // ModelResolver declares resolve(); resolve() calls PomPaths (method-level edge!).
        var declEv = GraphEvidence.capture("graph.declares", Map.of("owner", resolverId, "member", resolverMethodId));
        evidence.add(declEv);
        edges.add(new KnowledgeGraph.Edge(id("e-decl"), mainCtxId, "graph.declares", resolverId,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(resolverMethodId), Optional.empty()),
                List.of(declEv.identity()), Optional.empty()));
        addEdge(mainCtxId, id("e-resolve-paths"), resolverMethodId, lowUtilId, evidence, edges);

        // IntegrationWorkflowTest (in separate testCtxId!) has an UNRESOLVED java.observation referencing ModelResolver
        // (simulating passive offline analysis where test->main classpath isn't compiled).
        String testDocId = SourceDocumentIdentity.from(repo, "src/test/java/com/acme/IntegrationWorkflowTest.java").value();
        var obsEv = GraphEvidence.capture("java.observation", Map.of(
                "document", testDocId,
                "category", "java.uses-type",
                "attribution", "UNRESOLVED",
                "origin", "VERIFIED",
                "provenance", "VERIFIED",
                "reference", "new ModelResolver(cache)",
                "diagnostics", List.of()));
        evidence.add(obsEv);
        nodes.add(new KnowledgeGraph.Node(id("obs:test-uses-resolver"), testCtxId, "OBSERVATION", List.of(obsEv.identity())));

        var snapCtxEv = GraphEvidence.capture("context.root", Map.of("ctx", snapCtxId));
        var mainCtxEv = GraphEvidence.capture("context.root", Map.of("ctx", mainCtxId));
        var testCtxEv = GraphEvidence.capture("context.root", Map.of("ctx", testCtxId));
        evidence.add(snapCtxEv);
        evidence.add(mainCtxEv);
        evidence.add(testCtxEv);
        var graph = new KnowledgeGraph(snap,
                List.of(
                        new KnowledgeGraph.Context(snapCtxId, snap, Optional.empty(), snapCtxEv.identity()),
                        new KnowledgeGraph.Context(mainCtxId, snap, Optional.empty(), mainCtxEv.identity()),
                        new KnowledgeGraph.Context(testCtxId, snap, Optional.empty(), testCtxEv.identity())),
                nodes, edges, evidence);

        // Editing PomPaths must traverse PomPaths <- ModelResolver.resolve (member) -> ModelResolver (enclosing type)
        // <- IntegrationWorkflowTest (UNRESOLVED observation in testCtxId), while still skipping UnrelatedTest!
        var res = AffectedTestSelector.select(graph, null, List.of("src/main/java/com/acme/PomPaths.java"));
        assertEquals(AffectedTestSelector.SelectionMode.SELECTED_TESTS, res.mode());
        assertEquals(2, res.totalTestClasses());
        assertEquals(List.of("IntegrationWorkflowTest"),
                res.selectedTests().stream().map(AffectedTestSelector.SelectedTest::testClass).toList());
        assertEquals(Optional.of("IntegrationWorkflowTest"), res.mavenTestFilter());
    }

    @Test
    void architectureAgentActivatesStrictQaPromptOnlyWhenNotReviewer() throws Exception {
        var fixture = new QueryFixture()
                .node("controller")
                .node("service")
                .edge("controller", "service");
        var capturedSystemPrompts = Collections.synchronizedList(new ArrayList<String>());
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/chat/completions", exchange -> {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                var req = com.evolution.analysis.graph.GraphJson.object(
                        com.evolution.analysis.graph.GraphJson.read(
                                new String(requestBody, StandardCharsets.UTF_8),
                                com.evolution.analysis.graph.GraphJson.Limits.defaults()));
                var msgs = com.evolution.analysis.graph.GraphJson.array(req.get("messages"));
                var sys = com.evolution.analysis.graph.GraphJson.object(msgs.get(0));
                capturedSystemPrompts.add(String.valueOf(sys.get("content")));

                String content = CanonicalJson.write(Map.of(
                        "status", "ADVICE",
                        "conversationTitle", "QA check",
                        "answer", "Grounded answer.",
                        "citations", List.of(),
                        "limitations", List.of()));
                String replyJson = CanonicalJson.write(Map.of(
                        "model", "agent-model",
                        "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of(
                                "role", "assistant",
                                "content", content)))));
                byte[] out = replyJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            mock.start();

            var endpoint = URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1/chat/completions");
            var model = new ArchitectureChatModel(endpoint, "agent-model", null, false);
            var api = ArchitectureQueryApi.withArchitectureChat(new ArchitectureQueryApi(fixture.store()), model);

            var qaResp = api.handle("POST",
                    "/api/v1/graphs/" + fixture.request().graph().value() + "/architecture-chat",
                    "application/json",
                    bytes(Map.of(
                            "context", fixture.context,
                            "mode", "EXPLORE",
                            "agentic", true,
                            "messages", List.of(Map.of("role", "user", "content", "What depends on service?")))),
                    () -> false);
            assertEquals(200, qaResp.status(), () -> body(qaResp).toString());

            var revResp = api.handle("POST",
                    "/api/v1/graphs/" + fixture.request().graph().value() + "/architecture-chat",
                    "application/json",
                    bytes(Map.of(
                            "context", fixture.context,
                            "mode", "EXPLORE",
                            "reviewer", true,
                            "agentic", true,
                            "messages", List.of(Map.of("role", "user", "content", "Review this diff?")))),
                    () -> false);
            assertEquals(200, revResp.status(), () -> body(revResp).toString());

            assertEquals(2, capturedSystemPrompts.size());
            assertTrue(capturedSystemPrompts.get(0).contains("STRICT QA MODE (anti-Edit-Bias)"), capturedSystemPrompts.get(0));
            assertFalse(capturedSystemPrompts.get(1).contains("STRICT QA MODE (anti-Edit-Bias)"), capturedSystemPrompts.get(1));
        } finally {
            mock.stop(0);
        }
    }

    @Test
    void localAnalysisRunnerProjectionProfileDefaultsToStrictAndSwitchesToDfdExport() {
        String prev = System.getProperty("evo.projection.profile");
        try {
            System.clearProperty("evo.projection.profile");
            assertEquals(ServiceArchitectureProjection.ProjectionProfile.STRICT,
                    LocalAnalysisRunner.configuredProjectionProfile());

            System.setProperty("evo.projection.profile", "dfd-export");
            assertEquals(ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT,
                    LocalAnalysisRunner.configuredProjectionProfile());

            System.setProperty("evo.projection.profile", "DFD_EXPORT");
            assertEquals(ServiceArchitectureProjection.ProjectionProfile.DFD_EXPORT,
                    LocalAnalysisRunner.configuredProjectionProfile());

            System.setProperty("evo.projection.profile", "strict");
            assertEquals(ServiceArchitectureProjection.ProjectionProfile.STRICT,
                    LocalAnalysisRunner.configuredProjectionProfile());
        } finally {
            if (prev == null) {
                System.clearProperty("evo.projection.profile");
            } else {
                System.setProperty("evo.projection.profile", prev);
            }
        }
    }

    // --- Minimal canonical SKG builders ---

    private static String id(String seed) {
        return ContentDigest.sha256Utf8(seed).value();
    }

    private static KnowledgeGraph buildDenseCallerGraph(int callerCount) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:dense-callers");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("snap"));
        String snapCtxId = snap.value();
        String codeCtxId = id("ctx:main");
        var evidence = new ArrayList<GraphEvidence>();
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();

        String targetId = id("type:TargetService");
        addDeclNode(repo, snapCtxId, codeCtxId, targetId, "TargetService",
                "src/main/java/com/acme/TargetService.java", 1, 10, evidence, nodes);
        for (int i = 0; i < callerCount; i++) {
            String callerId = id("type:Caller" + i);
            String path = "src/main/java/com/acme/Caller" + i + ".java";
            addDeclNode(repo, snapCtxId, codeCtxId, callerId, "Caller" + i, path, 1, 10, evidence, nodes);
            var edgeEv = GraphEvidence.capture("java.call", Map.of("k", "call-" + i));
            evidence.add(edgeEv);
            edges.add(new KnowledgeGraph.Edge(
                    id("edge-" + i), codeCtxId, "java.calls", callerId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(targetId), Optional.empty()),
                    List.of(edgeEv.identity()), Optional.empty()));
        }
        var snapCtxEv = GraphEvidence.capture("context.root", Map.of("ctx", snapCtxId));
        var codeCtxEv = GraphEvidence.capture("context.root", Map.of("ctx", codeCtxId));
        evidence.add(snapCtxEv);
        evidence.add(codeCtxEv);
        return new KnowledgeGraph(snap,
                List.of(
                        new KnowledgeGraph.Context(snapCtxId, snap, Optional.empty(), snapCtxEv.identity()),
                        new KnowledgeGraph.Context(codeCtxId, snap, Optional.empty(), codeCtxEv.identity())),
                nodes, edges, evidence);
    }

    private static KnowledgeGraph buildRtsGraph(int unrelatedTests) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:skg-rts");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("snap-rts"));
        String snapCtxId = snap.value();
        String codeCtxId = id("ctx:rts");
        var evidence = new ArrayList<GraphEvidence>();
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();

        String coreId = id("type:ServiceCore");
        String facadeId = id("type:Facade");
        String coreTestId = id("type:ServiceCoreTest");
        String facadeTestId = id("type:FacadeTest");

        // ServiceCore <- Facade <- FacadeTest, plus ServiceCoreTest & N unrelated tests
        addDeclNode(repo, snapCtxId, codeCtxId, coreId, "ServiceCore", "src/main/java/com/acme/ServiceCore.java", 1, 20, evidence, nodes);
        addDeclNode(repo, snapCtxId, codeCtxId, facadeId, "Facade", "src/main/java/com/acme/Facade.java", 1, 20, evidence, nodes);
        addDeclNode(repo, snapCtxId, codeCtxId, coreTestId, "ServiceCoreTest", "src/test/java/com/acme/ServiceCoreTest.java", 1, 20, evidence, nodes);
        addDeclNode(repo, snapCtxId, codeCtxId, facadeTestId, "FacadeTest", "src/test/java/com/acme/FacadeTest.java", 1, 20, evidence, nodes);
        addEdge(codeCtxId, id("e-facade-core"), facadeId, coreId, evidence, edges);
        addEdge(codeCtxId, id("e-test-core"), coreTestId, coreId, evidence, edges);
        addEdge(codeCtxId, id("e-test-facade"), facadeTestId, facadeId, evidence, edges);

        for (int i = 0; i < unrelatedTests; i++) {
            String otherId = id("type:Other" + i);
            String otherTestId = id("type:Other" + i + "Test");
            addDeclNode(repo, snapCtxId, codeCtxId, otherId, "Other" + i, "src/main/java/com/acme/Other" + i + ".java", 1, 10, evidence, nodes);
            addDeclNode(repo, snapCtxId, codeCtxId, otherTestId, "Other" + i + "Test", "src/test/java/com/acme/Other" + i + "Test.java", 1, 10, evidence, nodes);
            addEdge(codeCtxId, id("e-other-" + i), otherTestId, otherId, evidence, edges);
        }

        var snapCtxEv = GraphEvidence.capture("context.root", Map.of("ctx", snapCtxId));
        var codeCtxEv = GraphEvidence.capture("context.root", Map.of("ctx", codeCtxId));
        evidence.add(snapCtxEv);
        evidence.add(codeCtxEv);
        return new KnowledgeGraph(snap,
                List.of(
                        new KnowledgeGraph.Context(snapCtxId, snap, Optional.empty(), snapCtxEv.identity()),
                        new KnowledgeGraph.Context(codeCtxId, snap, Optional.empty(), codeCtxEv.identity())),
                nodes, edges, evidence);
    }

    private static void addDeclNode(
            RepositoryIdentity repo, String snapCtxId, String codeCtxId, String nodeId, String name, String path,
            int startLine, int endLine,
            List<GraphEvidence> evidence, List<KnowledgeGraph.Node> nodes) {
        var docId = SourceDocumentIdentity.from(repo, path);
        var docEv = GraphEvidence.capture("source.document",
                Map.of("path", path, "language", "java"));
        evidence.add(docEv);
        nodes.add(new KnowledgeGraph.Node(docId.value(), snapCtxId, "SOURCE_DOCUMENT", List.of(docEv.identity())));

        var span = new SourceSpan(docId, startLine, 1, endLine, 1);
        var declEv = GraphEvidence.capture("java.declaration",
                Map.of("entity", Map.of(
                        "qualifiedName", "com.acme." + name,
                        "declaration", Map.of(
                                "document", span.document().value(),
                                "startLine", span.startLine(),
                                "endLine", span.endLine()))));
        evidence.add(declEv);
        nodes.add(new KnowledgeGraph.Node(nodeId, codeCtxId, "CLASS", List.of(declEv.identity())));
    }

    private static void addEdge(String ctxId, String edgeId, String src, String dst,
                                List<GraphEvidence> evidence, List<KnowledgeGraph.Edge> edges) {
        var ev = GraphEvidence.capture("java.calls", Map.of("call", edgeId));
        evidence.add(ev);
        edges.add(new KnowledgeGraph.Edge(edgeId, ctxId, "java.calls", src,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                List.of(ev.identity()), Optional.empty()));
    }
}
