package com.evolution.backend.query;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.metrics.ArchitectureMetricsService;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.backend.query.repair.RepairEditV2;
import com.evolution.backend.query.repair.RepairSyntaxGate;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class CapturedRepairV2Test {
    private static final JavaRepairSyntax.Profile JAVA_21 = new JavaRepairSyntax.Profile(21, false, "TEST", Optional.empty());

    @Test
    void repairEditV2ParsesJsonAndSearchReplaceBlocksForModifyCreateDelete() {
        String json = """
                <think>planning the decoupling</think>
                ```json
                {
                  "summary": "Extract port interface",
                  "edits": [
                    {"op": "CREATE", "path": "src/main/java/demo/BillingPort.java", "content": "package demo;\\npublic interface BillingPort { void pay(); }\\n", "rationale": "DIP port"},
                    {"op": "MODIFY", "file": 1, "search": "BillingService", "replace": "BillingPort", "startLine": 4},
                    {"op": "DELETE", "path": "src/main/java/demo/Dead.java"}
                  ]
                }
                ```
                """;
        var parsedJson = RepairEditV2.parseResponse(json, 16);
        assertEquals(3, parsedJson.size());
        assertEquals(RepairEditV2.Op.CREATE, parsedJson.get(0).op());
        assertEquals(Optional.of("src/main/java/demo/BillingPort.java"), parsedJson.get(0).path());
        assertEquals(RepairEditV2.Op.MODIFY, parsedJson.get(1).op());
        assertEquals(OptionalInt.of(1), parsedJson.get(1).fileIndex());
        assertEquals(RepairEditV2.Op.DELETE, parsedJson.get(2).op());

        String sr = """
                FILE: src/main/java/demo/OrderService.java
                START_LINE: 5
                <<<<<<< SEARCH
                private final BillingService billing;
                =======
                private final BillingPort billing;
                >>>>>>> REPLACE

                FILE: src/main/java/demo/BillingPort.java
                OP: CREATE
                <<<<<<< SEARCH
                =======
                package demo;
                public interface BillingPort { void pay(); }
                >>>>>>> REPLACE
                """;
        var parsedSr = RepairEditV2.parseResponse(sr, 16);
        assertEquals(2, parsedSr.size());
        assertEquals(RepairEditV2.Op.MODIFY, parsedSr.get(0).op());
        assertEquals(Integer.valueOf(5), parsedSr.get(0).startLine());
        assertEquals(RepairEditV2.Op.CREATE, parsedSr.get(1).op());
        assertTrue(parsedSr.get(1).replace().contains("public interface BillingPort"));
    }

    @Test
    void extractInterfaceDecoupleAndLombokFieldInjectionProduceValidMultiFileAndSingleFileRepairs() {
        String callerSrc = """
                package com.acme.domain;
                import com.acme.adapter.PaymentGateway;
                public class OrderService {
                    private final PaymentGateway gateway;
                    public OrderService(PaymentGateway gateway) {
                        this.gateway = gateway;
                    }
                    public boolean checkout(String id) {
                        return gateway.charge(id);
                    }
                }
                """;
        String targetSrc = """
                package com.acme.adapter;
                public class PaymentGateway {
                    public boolean charge(String id) {
                        return id != null;
                    }
                }
                """;
        var doc1 = new SourceDocumentIdentity("source:sha256:" + "1".repeat(64));
        var doc2 = new SourceDocumentIdentity("source:sha256:" + "2".repeat(64));
        var callerFile = citedFile("src/main/java/com/acme/domain/OrderService.java", callerSrc, doc1, 4, 5, 4, 41);
        var targetFile = citedFile("src/main/java/com/acme/adapter/PaymentGateway.java", targetSrc, doc2, 2, 1, 2, 30);
        var profile = new JavaRepairSyntax.ContextProfile("demo", "MAIN", JAVA_21);

        var multi = JavaRepairRecipes.multiFileCandidates(
                JavaRepairRecipes.Recipe.EXTRACT_INTERFACE_DECOUPLE,
                callerFile,
                JavaRepairSyntax.parse(callerSrc, JAVA_21),
                Map.of(callerFile.path(), callerFile, targetFile.path(), targetFile),
                profile);
        assertEquals(1, multi.size());
        var changes = multi.getFirst().changes();
        assertEquals(3, changes.size());
        var created = changes.stream().filter(JavaRepairRecipes.FileChange::created).findFirst().orElseThrow();
        assertEquals("src/main/java/com/acme/domain/PaymentGatewayPort.java", created.path());
        assertTrue(created.afterText().contains("public interface PaymentGatewayPort"));
        assertTrue(created.afterText().contains("boolean charge(String id);"));
        var updatedCaller = changes.stream().filter(c -> c.path().equals(callerFile.path())).findFirst().orElseThrow();
        assertFalse(updatedCaller.afterText().contains("import com.acme.adapter.PaymentGateway;"));
        assertTrue(updatedCaller.afterText().contains("private final PaymentGatewayPort gateway;"));
        var updatedTarget = changes.stream().filter(c -> c.path().equals(targetFile.path())).findFirst().orElseThrow();
        assertTrue(updatedTarget.afterText().contains("implements PaymentGatewayPort"));
        assertTrue(updatedTarget.afterText().contains("import com.acme.domain.PaymentGatewayPort;"));

        // Lombok @RequiredArgsConstructor recipe
        String lombokSrc = """
                package com.acme.service;
                import lombok.RequiredArgsConstructor;
                import org.springframework.beans.factory.annotation.Autowired;
                @RequiredArgsConstructor
                public class InvoiceService {
                    @Autowired
                    private TaxClient taxClient;
                    public void bill() { taxClient.calc(); }
                }
                """;
        var lombokFile = citedFile("src/main/java/com/acme/service/InvoiceService.java", lombokSrc, doc1, 6, 5, 7, 32);
        var lombokCands = JavaRepairRecipes.candidates(
                JavaRepairRecipes.Recipe.CONVERT_LOMBOK_FIELD_INJECTION,
                lombokFile,
                JavaRepairSyntax.parse(lombokSrc, JAVA_21));
        assertEquals(1, lombokCands.size());
        assertTrue(lombokCands.getFirst().contains("private final TaxClient taxClient;"));
        assertFalse(lombokCands.getFirst().contains("@Autowired"));
        assertEquals(JavaRepairSyntax.Status.PASSED, JavaRepairSyntax.parse(lombokCands.getFirst(), JAVA_21).check().status());
    }

    @Test
    void syntaxGateValidatesJavaPolyglotTreeSitterJsonYamlAndProperties() {
        assertEquals(JavaRepairSyntax.Status.PASSED,
                RepairSyntaxGate.check("App.java", "class App { int x = 1; }", Optional.of(JAVA_21)).status());
        var badJava = RepairSyntaxGate.check("App.java", "class App { int x = ; }", Optional.of(JAVA_21));
        assertEquals(JavaRepairSyntax.Status.REJECTED, badJava.status());
        assertFalse(badJava.diagnostics().isEmpty());
        assertFalse(badJava.diagnostics().getFirst().message().isBlank());

        assertEquals(JavaRepairSyntax.Status.PASSED,
                RepairSyntaxGate.check("worker.py", "def compute(x):\n    return x + 1\n", Optional.empty()).status());
        assertEquals(JavaRepairSyntax.Status.REJECTED,
                RepairSyntaxGate.check("worker.py", "def compute(x:\n    return\n", Optional.empty()).status());

        assertEquals(JavaRepairSyntax.Status.PASSED,
                RepairSyntaxGate.check("client.ts", "export function greet(name: string): string { return name; }\n", Optional.empty()).status());
        assertEquals(JavaRepairSyntax.Status.REJECTED,
                RepairSyntaxGate.check("client.ts", "export function greet(: string { return; }\n", Optional.empty()).status());

        assertEquals(JavaRepairSyntax.Status.PASSED,
                RepairSyntaxGate.check("config.json", "{\"ok\":true}", Optional.empty()).status());
        assertEquals(JavaRepairSyntax.Status.REJECTED,
                RepairSyntaxGate.check("config.json", "{\"ok\":}", Optional.empty()).status());

        assertEquals(JavaRepairSyntax.Status.PASSED,
                RepairSyntaxGate.check("application.yml", "server:\n  port: 8080\n", Optional.empty()).status());
        assertEquals(JavaRepairSyntax.Status.REJECTED,
                RepairSyntaxGate.check("application.yml", "server:\n\tport: 8080\n", Optional.empty()).status());
    }

    @Test
    void selfHealingLoopRecoversFromAnchorAndSyntaxFailuresAndSupportsAdHocPolyglotRepair() throws Exception {
        String pySource = "import legacy_mod\n\ndef handle(req):\n    return {'ok': True}\n";
        byte[] pyBytes = pySource.getBytes(StandardCharsets.UTF_8);
        var pyDigest = ContentDigest.sha256(pyBytes);

        var fixture = new QueryFixture();
        var pyDoc = SourceDocumentIdentity.from(
                RepositoryIdentity.fromCanonicalCoordinate("https://example.test/py-repair.git"),
                "services/api/handler.py");
        var sourceBytesEv = GraphEvidence.capture("source.bytes", Map.of(
                "document", Map.of("identity", pyDoc.value(), "path", "services/api/handler.py",
                        "contentDigest", pyDigest.value(), "module", "api", "classification", "MAIN"),
                "decoding", Map.of("charset", "UTF-8", "bom", "NONE"),
                "bytesBase64", Base64.getEncoder().encodeToString(pyBytes)));
        var docNode = new KnowledgeGraph.Node(pyDoc.value(), fixture.context, "SOURCE_DOCUMENT", List.of(sourceBytesEv.identity()));
        var graph = new KnowledgeGraph(fixture.snapshot,
                List.of(new KnowledgeGraph.Context(fixture.context, fixture.snapshot, Optional.empty(), fixture.base.identity())),
                List.of(docNode), List.of(), List.of(fixture.base, sourceBytesEv));

        var attempt = new AtomicInteger();
        var seenFeedback = Collections.synchronizedList(new ArrayList<String>());
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/chat/completions", ex -> {
                try (ex) {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    var reqObj = GraphJson.object(GraphJson.read(body, GraphJson.Limits.defaults()));
                    var msgs = GraphJson.array(reqObj.get("messages"));
                    int n = attempt.incrementAndGet();
                    if (n > 1) {
                        seenFeedback.add(GraphJson.string(GraphJson.object(msgs.getLast()).get("content")));
                    }
                    String content;
                    if (n == 1) {
                        // Attempt 1: bad anchor -> triggers nearest-lines feedback
                        content = CanonicalJson.write(Map.of("edits", List.of(Map.of(
                                "file", 1, "before", pyDigest.value(),
                                "oldText", "import nonexistent_line", "newText", ""))));
                    } else if (n == 2) {
                        // Attempt 2: broken Python syntax -> triggers Tree-sitter syntax feedback
                        content = CanonicalJson.write(Map.of("edits", List.of(Map.of(
                                "file", 1, "before", pyDigest.value(),
                                "oldText", "def handle(req):", "newText", "def handle(req:"))));
                    } else {
                        // Attempt 3: clean SEARCH/REPLACE block removing unused legacy import
                        content = "FILE: services/api/handler.py\n<<<<<<< SEARCH\nimport legacy_mod\n\ndef handle(req):\n=======\ndef handle(req):\n>>>>>>> REPLACE\n";
                    }
                    byte[] out = CanonicalJson.write(Map.of(
                            "model", "repair-v2",
                            "choices", List.of(Map.of(
                                    "finish_reason", "stop",
                                    "message", Map.of("role", "assistant", "content", content))))).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                }
            });
            mock.start();

            var model = new RepairChatModel(URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1"),
                    "repair-v2", null, false);
            var repair = new CapturedRepair(model);
            var queries = new ArchitectureMetricsService(graph).queries();
            var adHoc = new CapturedRepair.AdHocRequest(
                    fixture.context,
                    "Remove unused legacy_mod import",
                    "Decouple handler.py from legacy_mod",
                    List.of("services/api/handler.py"),
                    List.of(),
                    CapturedRepair.Strategy.AI,
                    Optional.empty(),
                    3);
            var outcome = repair.proposeAdHoc(adHoc, graph, queries, () -> StopReason.NONE);
            assertEquals("PROPOSED", outcome.status(), () -> "Outcome=" + outcome);
            assertEquals(3, outcome.attempts());
            assertTrue(outcome.proposal().isPresent());
            assertTrue(seenFeedback.get(0).contains("Nearest actual source lines"), seenFeedback.get(0));
            assertTrue(seenFeedback.get(1).contains("Syntax check rejected"), seenFeedback.get(1));
            String patch = outcome.proposal().orElseThrow().candidate().patch();
            assertTrue(patch.contains("-import legacy_mod"), patch);
            assertTrue(CapturedRepair.authentic(outcome.proposal().orElseThrow()));
        } finally {
            mock.stop(0);
        }
    }

    @Test
    void capturedRepairOptionsAndProposeRouteBreakPackageSccCycleEndToEnd() {
        String callerPath = "src/main/java/com/acme/domain/OrderService.java";
        String targetPath = "src/main/java/com/acme/adapter/PaymentGateway.java";
        String callerSrc = """
                package com.acme.domain;
                import com.acme.adapter.PaymentGateway;
                public class OrderService {
                    private final PaymentGateway gateway;
                    public OrderService(PaymentGateway gateway) {
                        this.gateway = gateway;
                    }
                    public boolean checkout(String id) {
                        return gateway.charge(id);
                    }
                }
                """;
        String targetSrc = """
                package com.acme.adapter;
                public class PaymentGateway {
                    public boolean charge(String id) {
                        return id != null;
                    }
                }
                """;

        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/scc-repair.git");
        var callerDoc = SourceDocumentIdentity.from(repo, callerPath);
        var targetDoc = SourceDocumentIdentity.from(repo, targetPath);
        byte[] callerBytes = callerSrc.getBytes(StandardCharsets.UTF_8);
        byte[] targetBytes = targetSrc.getBytes(StandardCharsets.UTF_8);
        var callerDigest = ContentDigest.sha256(callerBytes);
        var targetDigest = ContentDigest.sha256(targetBytes);

        var fixture = new QueryFixture();
        var callerBytesEv = GraphEvidence.capture("source.bytes", Map.of(
                "document", Map.of("identity", callerDoc.value(), "path", callerPath,
                        "contentDigest", callerDigest.value(), "module", "demo", "classification", "MAIN"),
                "decoding", Map.of("charset", "UTF-8", "bom", "NONE"),
                "bytesBase64", Base64.getEncoder().encodeToString(callerBytes)));
        var targetBytesEv = GraphEvidence.capture("source.bytes", Map.of(
                "document", Map.of("identity", targetDoc.value(), "path", targetPath,
                        "contentDigest", targetDigest.value(), "module", "demo", "classification", "MAIN"),
                "decoding", Map.of("charset", "UTF-8", "bom", "NONE"),
                "bytesBase64", Base64.getEncoder().encodeToString(targetBytes)));
        var callerTypeEv = GraphEvidence.capture("java.type", Map.of(
                "qualifiedName", "com.acme.domain.OrderService",
                "document", callerDoc.value(),
                "path", callerPath));
        var targetTypeEv = GraphEvidence.capture("java.type", Map.of(
                "qualifiedName", "com.acme.adapter.PaymentGateway",
                "document", targetDoc.value(),
                "path", targetPath));

        String callerTypeId = QueryFixture.id("com.acme.domain.OrderService");
        String targetTypeId = QueryFixture.id("com.acme.adapter.PaymentGateway");
        var nodes = List.of(
                new KnowledgeGraph.Node(callerDoc.value(), fixture.context, "SOURCE_DOCUMENT", List.of(callerBytesEv.identity())),
                new KnowledgeGraph.Node(targetDoc.value(), fixture.context, "SOURCE_DOCUMENT", List.of(targetBytesEv.identity())),
                new KnowledgeGraph.Node(callerTypeId, fixture.context, "TYPE", List.of(callerTypeEv.identity())),
                new KnowledgeGraph.Node(targetTypeId, fixture.context, "TYPE", List.of(targetTypeEv.identity())));
        var edges = List.of(new KnowledgeGraph.Edge(
                QueryFixture.id("dep-scc-1"), fixture.context, "graph.depends-on", callerTypeId,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(targetTypeId), Optional.empty()),
                List.of(fixture.base.identity()), Optional.empty()));
        var graph = new KnowledgeGraph(
                fixture.snapshot,
                List.of(new KnowledgeGraph.Context(fixture.context, fixture.snapshot, Optional.empty(), fixture.base.identity())),
                nodes, edges, List.of(fixture.base, callerBytesEv, targetBytesEv, callerTypeEv, targetTypeEv));

        var citeCaller = new FindingDiagnosis.Citation(
                ContentDigest.sha256Utf8("cite-caller"), fixture.context, callerBytesEv.identity(),
                "java.occurrence", callerPath, new SourceSpan(callerDoc, 4, 5, 4, 41), callerDigest);
        var citeTarget = new FindingDiagnosis.Citation(
                ContentDigest.sha256Utf8("cite-target"), fixture.context, targetBytesEv.identity(),
                "java.occurrence", targetPath, new SourceSpan(targetDoc, 2, 1, 2, 30), targetDigest);
        var dummyDigest = ContentDigest.sha256Utf8("scc-finding");
        var pin = new FindingDiagnosis.Pin(
                fixture.snapshot, Optional.empty(), graph.identity(), fixture.context,
                dummyDigest, dummyDigest, dummyDigest);
        var proof = new FindingDiagnosis.Proof(
                new VersionedIdentifier("policy.acyclic-packages", "1"),
                ArchitectureQuery.Scope.TYPE, callerTypeId, targetTypeId, "dep-scc-1",
                List.of("graph.depends-on"), ArchitectureQuery.Classification.MUST, dummyDigest);
        var diagnosis = new FindingDiagnosis.Outcome(
                FindingDiagnosis.VERSION, FindingDiagnosis.Status.CITATIONS_VALIDATED, Optional.empty(),
                FindingDiagnosis.Mode.LOCAL_REPLAY, new VersionedIdentifier("diagnosis.test", "1"),
                pin, Optional.of(dummyDigest), Optional.of(proof), Optional.of("Break package SCC cycle"),
                List.of(citeCaller, citeTarget), AssessmentStatus.COMPLETE, List.of());

        var repair = new CapturedRepair(null);
        var opts = repair.options(diagnosis, graph, () -> StopReason.NONE);
        assertTrue(opts.applicableRecipes().contains(JavaRepairRecipes.Recipe.BREAK_PACKAGE_SCC_CYCLE.id),
                () -> "Expected BREAK_PACKAGE_SCC_CYCLE in applicableRecipes, got " + opts.applicableRecipes());
        assertTrue(opts.applicableRecipes().contains(JavaRepairRecipes.Recipe.EXTRACT_INTERFACE_DECOUPLE.id));

        var outcome = repair.propose(
                diagnosis, graph, CapturedRepair.Strategy.RECIPE,
                Optional.of(JavaRepairRecipes.Recipe.BREAK_PACKAGE_SCC_CYCLE),
                Optional.empty(), () -> StopReason.NONE);
        assertEquals("PROPOSED", outcome.status(), () -> "Expected PROPOSED, got " + outcome);
        assertTrue(outcome.proposal().isPresent());
        var proposal = outcome.proposal().orElseThrow();
        assertTrue(CapturedRepair.authentic(proposal));
        assertEquals(JavaRepairRecipes.Recipe.BREAK_PACKAGE_SCC_CYCLE.id,
                proposal.candidate().recipe().orElseThrow().id());
        assertEquals(3, proposal.candidate().edits().size());
        assertTrue(proposal.candidate().edits().stream()
                .anyMatch(e -> e.isCreate() && e.path().equals("src/main/java/com/acme/domain/PaymentGatewayPort.java")));
        assertTrue(proposal.candidate().patch().contains("public interface PaymentGatewayPort"));
        assertTrue(proposal.candidate().patch().contains("implements PaymentGatewayPort"));

        // Also verify proposeAdHoc with Strategy.RECIPE for BREAK_PACKAGE_SCC_CYCLE
        var queries = new ArchitectureMetricsService(graph).queries();
        var adHocOutcome = repair.proposeAdHoc(
                new CapturedRepair.AdHocRequest(
                        fixture.context,
                        "Break package SCC",
                        "Decouple OrderService from PaymentGateway",
                        List.of(callerPath, targetPath),
                        List.of("com.acme.domain.OrderService", "com.acme.adapter.PaymentGateway"),
                        CapturedRepair.Strategy.RECIPE,
                        Optional.of(JavaRepairRecipes.Recipe.BREAK_PACKAGE_SCC_CYCLE.id),
                        1),
                graph, queries, () -> StopReason.NONE);
        assertEquals("PROPOSED", adHocOutcome.status(), () -> "Expected ad-hoc PROPOSED, got " + adHocOutcome);
        assertTrue(CapturedRepair.authentic(adHocOutcome.proposal().orElseThrow()));
    }

    private static RepairSource.File citedFile(String path, String text, SourceDocumentIdentity docId,
                                               int startLine, int startCol, int endLine, int endCol) {
        var digest = ContentDigest.sha256Utf8(text);
        var cite = new FindingDiagnosis.Citation(new ContentDigest("sha256:" + "a".repeat(64)),
                "analysis:1", new ContentDigest("sha256:" + "b".repeat(64)), "java.occurrence",
                path, new SourceSpan(docId, startLine, startCol, endLine, endCol), digest);
        return new RepairSource.File(path, digest, new ContentDigest("sha256:" + "c".repeat(64)),
                text, true, "demo", SourceClassification.MAIN, List.of(cite));
    }
}
