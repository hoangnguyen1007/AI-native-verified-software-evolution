package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.metrics.ArchitectureMetricsService;
import com.evolution.analysis.query.ArchitectureQuery.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.evolution.backend.query.ArchitectureQueryApiTest.body;
import static com.evolution.backend.query.ArchitectureQueryApiTest.bytes;
import static org.junit.jupiter.api.Assertions.*;

/** Focused regression tests covering all AI, GraphRAG, insights, policy, HTTP/MCP, and repair recipe fixes. */
class AiAndRepairUpgradeTest {
    private static final JavaRepairSyntax.Profile JAVA_21 = new JavaRepairSyntax.Profile(21, false, "TEST", Optional.empty());

    // --- 1. JavaRepairRecipes AST / Scope / False-Positive Defect Fixes ---

    @Test void objectProviderRewriteHandlesIdentifiersShadowingInnerClassesAndAssignments() {
        String src = """
                package demo;
                import java.util.function.Consumer;
                class ServiceA {
                    private ServiceB serviceB;
                    ServiceA(ServiceB serviceB) {
                        this.serviceB = serviceB;
                    }
                    ServiceB directReturn() { return serviceB; }
                    void paramShadow(ServiceB serviceB) {
                        serviceB.ping();
                        this.serviceB.ping();
                    }
                    void localShadow() {
                        ServiceB copy = serviceB;
                        ServiceB serviceB = copy;
                        serviceB.ping();
                    }
                    void lambdaShadow() {
                        Consumer<ServiceB> c = serviceB -> serviceB.ping();
                        Consumer<String> outer = s -> serviceB.ping();
                    }
                    void assignments(ServiceB other) {
                        serviceB = other;
                        this.serviceB = other;
                        serviceB.flag = true;
                        this.serviceB.flag = true;
                    }
                    class Inner {
                        private String serviceB = "x";
                        String innerBare() { return serviceB; }
                        String innerThis() { return this.serviceB; }
                        ServiceB outerQualified() { return ServiceA.this.serviceB; }
                    }
                }
                """;
        var doc = new SourceDocumentIdentity("source:sha256:" + "1".repeat(64));
        var file = citedFile("src/main/java/demo/ServiceA.java", src, doc, 4, 5, 4, 30);
        var cands = JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.OBJECT_PROVIDER_CYCLE_BREAKER,
                file, JavaRepairSyntax.parse(src, JAVA_21));
        assertEquals(1, cands.size());
        String out = cands.getFirst();
        assertEquals(JavaRepairSyntax.Status.PASSED, JavaRepairSyntax.parse(out, JAVA_21).check().status(), out);
        assertTrue(out.contains("return serviceB.getObject();"), out);
        assertTrue(out.contains("void paramShadow(ServiceB serviceB) {\n        serviceB.ping();\n        this.serviceB.getObject().ping();"), out);
        assertTrue(out.contains("ServiceB copy = serviceB.getObject();"), out);
        assertTrue(out.contains("ServiceB serviceB = copy;\n        serviceB.ping();"), out);
        assertTrue(out.contains("c = serviceB -> serviceB.ping();"), out);
        assertTrue(out.contains("outer = s -> serviceB.getObject().ping();"), out);
        assertTrue(out.contains("serviceB = other;"), out);
        assertTrue(out.contains("this.serviceB = other;"), out);
        assertTrue(out.contains("serviceB.getObject().flag = true;"), out);
        assertTrue(out.contains("this.serviceB.getObject().flag = true;"), out);
        assertTrue(out.contains("String innerBare() { return serviceB; }"), out);
        assertTrue(out.contains("String innerThis() { return this.serviceB; }"), out);
        assertTrue(out.contains("return ServiceA.this.serviceB.getObject();"), out);
    }

    @Test void convertFieldInjectionSkipsAnnotationsCommentsAndRejectsDelegatingConstructor() {
        String annotatedCtor = """
                package demo;
                import org.springframework.beans.factory.annotation.Autowired;
                class AnnotatedCtor {
                    @Autowired
                    private Repo repo;
                    @SuppressWarnings("all")
                    /* (fake paren in comment) */
                    AnnotatedCtor() {}
                }
                """;
        var doc = new SourceDocumentIdentity("source:sha256:" + "2".repeat(64));
        var file1 = citedFile("src/main/java/demo/AnnotatedCtor.java", annotatedCtor, doc, 4, 5, 5, 22);
        var cands1 = JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.CONVERT_FIELD_INJECTION,
                file1, JavaRepairSyntax.parse(annotatedCtor, JAVA_21));
        assertEquals(1, cands1.size());
        String out1 = cands1.getFirst();
        assertTrue(out1.contains("@SuppressWarnings(\"all\")"), out1);
        assertTrue(out1.contains("AnnotatedCtor(Repo repo)"), out1);
        assertEquals(JavaRepairSyntax.Status.PASSED, JavaRepairSyntax.parse(out1, JAVA_21).check().status(), out1);

        String delegating = """
                package demo;
                import org.springframework.beans.factory.annotation.Autowired;
                class Delegating {
                    @Autowired
                    private Repo repo;
                    Delegating() { this("x"); }
                }
                """;
        var file2 = citedFile("src/main/java/demo/Delegating.java", delegating, doc, 4, 5, 5, 22);
        var cands2 = JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.CONVERT_FIELD_INJECTION,
                file2, JavaRepairSyntax.parse(delegating, JAVA_21));
        assertTrue(cands2.isEmpty(), "Delegating this(...) constructor must be rejected");
    }

    @Test void makeDependencyOptionalRejectsVarargsRewritesRequireNonNullAndDeclinesUnsupportedUses() {
        var doc = new SourceDocumentIdentity("source:sha256:" + "3".repeat(64));

        String varargsSrc = """
                package demo;
                class VarargsBean {
                    VarargsBean(String... items) {}
                }
                """;
        var fVar = citedFile("src/main/java/demo/VarargsBean.java", varargsSrc, doc, 3, 17, 3, 32);
        assertTrue(JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.MAKE_DEPENDENCY_OPTIONAL,
                fVar, JavaRepairSyntax.parse(varargsSrc, JAVA_21)).isEmpty());

        String reqNonNullSrc = """
                package demo;
                import java.util.Objects;
                class CheckedBean {
                    private final Tracer tracer;
                    CheckedBean(Tracer tracer) {
                        this.tracer = Objects.requireNonNull(tracer);
                    }
                }
                """;
        var fReq = citedFile("src/main/java/demo/CheckedBean.java", reqNonNullSrc, doc, 5, 17, 5, 30);
        var reqCands = JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.MAKE_DEPENDENCY_OPTIONAL,
                fReq, JavaRepairSyntax.parse(reqNonNullSrc, JAVA_21));
        assertEquals(1, reqCands.size());
        assertTrue(reqCands.getFirst().contains("Optional<Tracer> tracer"));
        assertTrue(reqCands.getFirst().contains("this.tracer = tracer.orElseThrow();"));
        assertEquals(JavaRepairSyntax.Status.PASSED, JavaRepairSyntax.parse(reqCands.getFirst(), JAVA_21).check().status());

        String unsupportedSrc = """
                package demo;
                class DirectCallBean {
                    DirectCallBean(Tracer tracer) {
                        tracer.start();
                    }
                }
                """;
        var fUnsup = citedFile("src/main/java/demo/DirectCallBean.java", unsupportedSrc, doc, 3, 20, 3, 33);
        assertTrue(JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.MAKE_DEPENDENCY_OPTIONAL,
                fUnsup, JavaRepairSyntax.parse(unsupportedSrc, JAVA_21)).isEmpty());
    }

    @Test void replaceNewExcludesJdkCollectionConcurrencyAndUtilityTypes() {
        var doc = new SourceDocumentIdentity("source:sha256:" + "4".repeat(64));
        for (String decl : List.of(
                "private java.util.List<String> list = new ArrayList<>();",
                "private Map<String, Object> map = new ConcurrentHashMap<>();",
                "private AtomicInteger counter = new AtomicInteger();",
                "private StringBuilder sb = new StringBuilder();",
                "private ReentrantLock lock = new ReentrantLock();",
                "private Date now = new Date();",
                "private SecureRandom rng = new SecureRandom();",
                "private java.util.HashSet<String> set = new java.util.HashSet<>();",
                "private javax.management.ObjectName name = new javax.management.ObjectName();")) {
            String src = "package demo;\nimport java.util.*;\nimport java.util.concurrent.*;\nimport java.util.concurrent.atomic.*;\nimport java.util.concurrent.locks.*;\nimport java.security.*;\nclass Holder {\n    " + decl + "\n}\n";
            var f = citedFile("src/main/java/demo/Holder.java", src, doc, 8, 5, 8, 5 + decl.length());
            assertTrue(JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.REPLACE_NEW_WITH_CONSTRUCTOR_INJECTION,
                    f, JavaRepairSyntax.parse(src, JAVA_21)).isEmpty(), "Should exclude: " + decl);
        }
    }

    @Test void convertSetterInjectionRejectsReferencedSetterAndAlreadyAssignedField() {
        var doc = new SourceDocumentIdentity("source:sha256:" + "5".repeat(64));
        String calledSetter = """
                package demo;
                import org.springframework.beans.factory.annotation.Autowired;
                class StillCalled {
                    private OrderRepo repo;
                    @Autowired
                    public void setRepo(OrderRepo repo) { this.repo = repo; }
                    void reset(OrderRepo r) { setRepo(r); }
                }
                """;
        var f1 = citedFile("src/main/java/demo/StillCalled.java", calledSetter, doc, 5, 1, 6, 60);
        assertTrue(JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.CONVERT_SETTER_INJECTION,
                f1, JavaRepairSyntax.parse(calledSetter, JAVA_21)).isEmpty());

        String assignedInCtor = """
                package demo;
                import org.springframework.beans.factory.annotation.Autowired;
                class AlreadyAssigned {
                    private OrderRepo repo;
                    AlreadyAssigned() { this.repo = null; }
                    @Autowired
                    public void setRepo(OrderRepo repo) { this.repo = repo; }
                }
                """;
        var f2 = citedFile("src/main/java/demo/AlreadyAssigned.java", assignedInCtor, doc, 6, 1, 7, 60);
        assertTrue(JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.CONVERT_SETTER_INJECTION,
                f2, JavaRepairSyntax.parse(assignedInCtor, JAVA_21)).isEmpty());
    }

    @Test void importConflictCheckAndCommentStringStrippingWorkAccurately() {
        var doc = new SourceDocumentIdentity("source:sha256:" + "6".repeat(64));

        // Conflicting Optional import must block MAKE_DEPENDENCY_OPTIONAL
        String conflictOpt = """
                package demo;
                import com.custom.Optional;
                class ConflictBean {
                    private final Tracer tracer;
                    ConflictBean(Tracer tracer) { this.tracer = tracer; }
                }
                """;
        var fConflict = citedFile("src/main/java/demo/ConflictBean.java", conflictOpt, doc, 5, 18, 5, 31);
        assertTrue(JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.MAKE_DEPENDENCY_OPTIONAL,
                fConflict, JavaRepairSyntax.parse(conflictOpt, JAVA_21)).isEmpty());

        // Comment and string literal mentioning "Autowired" must NOT block pruning the unused import
        String commentMention = """
                package demo;
                import org.springframework.beans.factory.annotation.Autowired;
                class Commented {
                    // Previously Autowired here
                    private static final String NOTE = "Autowired";
                    @Autowired
                    private Repo repo;
                }
                """;
        var fComment = citedFile("src/main/java/demo/Commented.java", commentMention, doc, 6, 5, 7, 22);
        var cands = JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.CONVERT_FIELD_INJECTION,
                fComment, JavaRepairSyntax.parse(commentMention, JAVA_21));
        assertEquals(1, cands.size());
        assertFalse(cands.getFirst().contains("import org.springframework.beans.factory.annotation.Autowired;"),
                cands.getFirst());
    }

    // --- 2. RepairSource Non-Java Context & CapturedFileIndex & RepoMap & HTTP API ---

    @Test void capturedFilesSourceRepoMapAndNonJavaContextWorkAcrossJavaAndPolyglot() throws Exception {
        byte[] javaBytes = "package demo;\npublic class OrderService {\n    public void submit() {}\n}\n".getBytes(StandardCharsets.UTF_8);
        byte[] pyBytes = "def process_order(order_id):\n    # handle order\n    return {'id': order_id, 'ok': True}\n".getBytes(StandardCharsets.UTF_8);
        var javaDigest = ContentDigest.sha256(javaBytes);
        var pyDigest = ContentDigest.sha256(pyBytes);

        var fixture = new QueryFixture();
        var docJava = SourceDocumentIdentity.from(
                RepositoryIdentity.fromCanonicalCoordinate("https://example.test/poly.git"),
                "backend/src/main/java/demo/OrderService.java");
        var sourceBytesEv = GraphEvidence.capture("source.bytes", Map.of(
                "document", Map.of("identity", docJava.value(), "path", "backend/src/main/java/demo/OrderService.java",
                        "contentDigest", javaDigest.value(), "module", "backend", "classification", "MAIN"),
                "decoding", Map.of("charset", "UTF-8", "bom", "NONE"),
                "bytesBase64", Base64.getEncoder().encodeToString(javaBytes)));

        var pyRawPath = LogicalPath.utf8("services/billing/worker.py").rawBase64();
        var pyInvEv = GraphEvidence.capture("polyglot.inventory-entry", Map.of(
                "kind", "DOCUMENT", "key", "services/billing/worker.py", "scope", fixture.snapshot.value(),
                "fields", Map.of(
                        "rawPath", pyRawPath,
                        "kind", "FILE",
                        "population", "SOURCE",
                        "disposition", "ACQUIRED",
                        "content", Map.of("digest", pyDigest.value(), "size", pyBytes.length),
                        "languageCandidates", List.of("python"))));

        var docNode = new KnowledgeGraph.Node(docJava.value(), fixture.context, "SOURCE_DOCUMENT", List.of(sourceBytesEv.identity()));
        var orderDecl = GraphEvidence.capture("java.declaration", Map.of(
                "entity", Map.of(
                        "canonicalName", "java:v1:[\"type\",[\"demo\"],\"OrderService\"]",
                        "origin", "PROJECT",
                        "declaration", Map.of("document", docJava.value(), "startLine", 2, "startColumn", 1, "endLine", 4, "endColumn", 2)),
                "spelling", "OrderService"));
        var workerSyntax = GraphEvidence.capture("polyglot.source-syntax", Map.of(
                "fields", Map.of("path", "services/billing/worker.py", "language", "python", "name", "process_order")));

        var orderNode = new KnowledgeGraph.Node(QueryFixture.id("OrderService"), fixture.context, "TYPE", List.of(orderDecl.identity()));
        var workerNode = new KnowledgeGraph.Node(QueryFixture.id("worker"), fixture.context, "POLYGLOT_SYMBOL", List.of(workerSyntax.identity()));
        // Auxiliary non-file / opaque nodes that must NEVER leak into RepoMap files
        var typeUseNode = new KnowledgeGraph.Node("type-use:sha256:" + "a".repeat(64), fixture.context, "TYPE_USE", List.of(fixture.base.identity()));
        var annUseNode = new KnowledgeGraph.Node("annotation-use:sha256:" + "b".repeat(64), fixture.context, "ANNOTATION_USE", List.of(fixture.base.identity()));
        var obsNode = new KnowledgeGraph.Node("observation:sha256:" + "c".repeat(64), fixture.context, "OBSERVATION", List.of(fixture.base.identity()));
        var callSiteNode = new KnowledgeGraph.Node("call-site:sha256:" + "d".repeat(64), fixture.context, "CALL_SITE", List.of(fixture.base.identity()));
        var depArtifactNode = new KnowledgeGraph.Node("artifact:sha256:" + "e".repeat(64), fixture.context, "DEPENDENCY_ARTIFACT", List.of(fixture.base.identity()));
        var callEdge = new KnowledgeGraph.Edge(QueryFixture.id("e1"), fixture.context, "CALLS_SERVICE",
                orderNode.identity(),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(workerNode.identity()), Optional.empty()),
                List.of(fixture.base.identity()), Optional.empty());
        var noiseEdge = new KnowledgeGraph.Edge(QueryFixture.id("e2"), fixture.context, "java.type-uses",
                orderNode.identity(),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(typeUseNode.identity()), Optional.empty()),
                List.of(fixture.base.identity()), Optional.empty());

        var graph = new KnowledgeGraph(fixture.snapshot,
                List.of(new KnowledgeGraph.Context(fixture.context, fixture.snapshot, Optional.empty(), fixture.base.identity())),
                List.of(docNode, orderNode, workerNode, typeUseNode, annUseNode, obsNode, callSiteNode, depArtifactNode),
                List.of(callEdge, noiseEdge),
                List.of(fixture.base, sourceBytesEv, pyInvEv, orderDecl, workerSyntax));
        var store = new InMemoryGraphStore();
        store.save(graph);

        RepairSource.registerContentFallback((digest, size) ->
                digest.equals(pyDigest) ? Optional.of(pyBytes) : Optional.empty());
        try {
            // RepairSource context helper reads Python cleanly while RepairSource.open still enforces Java boundary
            var pyCtx = RepairSource.readContextFile(graph, "services/billing/worker.py");
            assertTrue(pyCtx.isPresent());
            assertEquals("python", pyCtx.orElseThrow().language());
            assertTrue(pyCtx.orElseThrow().text().contains("def process_order"));
            var pyDocId = SourceDocumentIdentity.from(
                    RepositoryIdentity.fromCanonicalCoordinate("https://example.test/poly.git"),
                    "services/billing/worker.py");
            var pyCitation = new FindingDiagnosis.Citation(pyDigest, fixture.context, pyInvEv.identity(),
                    "polyglot.source-syntax", "services/billing/worker.py",
                    new SourceSpan(pyDocId, 1, 1, 1, 20), pyDigest);
            var ex = assertThrows(RepairSource.Unavailable.class, () -> RepairSource.open(graph, List.of(pyCitation)));
            assertEquals(RepairSource.Issue.COMPILER_UNSUPPORTED, ex.issue);
            assertEquals("services/billing/worker.py", RepairSource.read(graph, List.of(pyCitation), false).path());

            // HTTP API: /api/v1 capabilities + /captured-files + /captured-source + /repo-map
            var api = new ArchitectureQueryApi(store);
            var caps = GraphJson.object(body(api.handle("GET", "/api/v1", null, new byte[0], () -> false)).get("data"));
            assertEquals(Boolean.TRUE, caps.get("capturedFilesEnabled"));
            assertEquals(Boolean.TRUE, caps.get("capturedSourceEnabled"));
            assertEquals(Boolean.TRUE, caps.get("repoMapEnabled"));

            var listGet = api.handle("GET", "/api/v1/graphs/" + graph.identity().value() + "/captured-files",
                    null, new byte[0], () -> false);
            assertEquals(200, listGet.status());
            var listData = GraphJson.object(body(listGet).get("data"));
            assertEquals(2, ((Number) listData.get("totalFiles")).intValue());

            var listPy = api.handle("POST", "/api/v1/graphs/" + graph.identity().value() + "/captured-files",
                    "application/json", bytes(Map.of("language", "python", "query", "**/*.py")), () -> false);
            assertEquals(200, listPy.status());
            assertEquals(1, ((Number) GraphJson.object(body(listPy).get("data")).get("matchedFiles")).intValue());

            var readPy = api.handle("POST", "/api/v1/graphs/" + graph.identity().value() + "/captured-source",
                    "application/json", bytes(Map.of("path", "worker.py", "startLine", 1, "endLine", 3)), () -> false);
            assertEquals(200, readPy.status());
            var srcData = GraphJson.object(body(readPy).get("data"));
            assertEquals(Boolean.TRUE, srcData.get("found"));
            assertEquals("python", srcData.get("language"));
            assertTrue(GraphJson.string(srcData.get("numberedSource")).contains("1: def process_order"));

            var mapGet = api.handle("GET", "/api/v1/graphs/" + graph.identity().value() + "/repo-map",
                    null, new byte[0], () -> false);
            assertEquals(200, mapGet.status());
            var mapData = GraphJson.object(body(mapGet).get("data"));
            assertEquals("personalized-pagerank-v1", mapData.get("algorithm"));
            assertEquals(2, ((Number) mapData.get("totalFiles")).intValue());
            // worker.py receives incoming PageRank mass from OrderService.java, so worker.py ranks #1 unseeded!
            var rankedFiles = GraphJson.array(mapData.get("files"));
            assertEquals(2, rankedFiles.size());
            assertEquals("services/billing/worker.py", GraphJson.object(rankedFiles.getFirst()).get("path"));
            for (var f : rankedFiles) {
                String p = GraphJson.string(GraphJson.object(f).get("path"));
                assertFalse(p.contains("sha256:") || p.startsWith("type-use:"),
                        "RepoMap must never emit raw opaque digest paths: " + p);
            }

            // Seeded on OrderService boosts OrderService.java to #1!
            var mapSeeded = api.handle("POST", "/api/v1/graphs/" + graph.identity().value() + "/repo-map",
                    "application/json", bytes(Map.of("seeds", List.of("OrderService"), "maxFiles", 5)), () -> false);
            assertEquals(200, mapSeeded.status());
            var seededFiles = GraphJson.array(GraphJson.object(body(mapSeeded).get("data")).get("files"));
            assertEquals("backend/src/main/java/demo/OrderService.java", GraphJson.object(seededFiles.getFirst()).get("path"));
        } finally {
            RepairSource.registerContentFallback(null);
        }
    }

    // --- 3. Peak AI / GraphRAG Agent, Citations, History Cap, Reviewer Agentic & Stream Title Filter ---

    @Test void agentExposesTwentyToolsTruncatesJsonGracefullyAndRunsInReviewerMode() throws Exception {
        var withSource = ArchitectureAgent.toolSchemas(true);
        var withoutSource = ArchitectureAgent.toolSchemas(false);
        assertEquals(20, withSource.size());
        assertEquals(17, withoutSource.size());
        assertTrue(withoutSource.stream()
                .map(t -> GraphJson.string(GraphJson.object(t.get("function")).get("name")))
                .anyMatch("get_architecture_score"::equals));
        assertTrue(withoutSource.stream()
                .map(t -> GraphJson.string(GraphJson.object(t.get("function")).get("name")))
                .anyMatch("inspect_codeowners"::equals));
        assertTrue(withoutSource.stream()
                .map(t -> GraphJson.string(GraphJson.object(t.get("function")).get("name")))
                .anyMatch("architecture_brief"::equals));
        assertTrue(withoutSource.stream()
                .map(t -> GraphJson.string(GraphJson.object(t.get("function")).get("name")))
                .anyMatch("inspect_advisory_links"::equals));
        assertTrue(withSource.stream()
                .map(t -> GraphJson.string(GraphJson.object(t.get("function")).get("name")))
                .anyMatch("read_markdown_section"::equals));

        // Graceful JSON truncation never cuts mid-JSON
        var hugePayload = Map.<String, Object>of(
                "evidenceId", "E9",
                "bigText", "A".repeat(120_000),
                "items", Collections.nCopies(500, Map.of("name", "com.example.VeryLongClassName", "detail", "X".repeat(200))));
        String truncatedJson = ArchitectureAgent.boundedJson(hugePayload);
        assertTrue(truncatedJson.getBytes(StandardCharsets.UTF_8).length <= ArchitectureAgent.MAX_TOOL_JSON_BYTES);
        var parsedTrunc = GraphJson.object(GraphJson.read(truncatedJson, GraphJson.Limits.defaults()));
        assertEquals(Boolean.TRUE, parsedTrunc.get("truncated"));

        // Citation grouping & range parsing + mention extraction
        assertEquals(List.of("E1", "E2", "E3", "E4", "E6"),
                ArchitectureChatModel.extractInlineCitations("Confirmed in [E1, E2-E4, E6] and ignored [not-a-cite]."));
        assertTrue(ArchitectureChat.extractMentionCandidates("How does `order_service` relate to OrderController in billing_api.py?")
                .containsAll(List.of("order_service", "OrderController", "billing_api")));

        // Reviewer + agentic mode executes tools & accepts >4000-char assistant history (up to 32,000)
        var fixture = new QueryFixture().node("OrderController").node("OrderService").edge("OrderController", "OrderService");
        var calls = new AtomicInteger();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/chat/completions", exchange -> {
                byte[] reqBytes = exchange.getRequestBody().readAllBytes();
                var req = GraphJson.object(GraphJson.read(new String(reqBytes, StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
                int call = calls.incrementAndGet();
                byte[] out;
                if (call == 1) {
                    assertTrue(req.containsKey("tools"), "Reviewer mode with agentic=true must send tools");
                    out = CanonicalJson.write(Map.of(
                            "model", "rev-model",
                            "choices", List.of(Map.of("finish_reason", "tool_calls", "message", Map.of(
                                    "role", "assistant", "content", "",
                                    "tool_calls", List.of(
                                            Map.of("id", "t1", "type", "function", "function",
                                                    Map.of("name", "get_repo_map", "arguments", "{}")),
                                            Map.of("id", "t2", "type", "function", "function",
                                                    Map.of("name", "inspect_spring_conditions", "arguments", "{}")))))))).getBytes(StandardCharsets.UTF_8);
                } else {
                    out = CanonicalJson.write(Map.of(
                            "model", "rev-model",
                            "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of(
                                    "role", "assistant",
                                    "content", "<conversation_title>Review findings</conversation_title>\nConfirmed risks in [E1-E3].")))))
                            .getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            mock.start();
            var model = new ArchitectureChatModel(URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1/chat/completions"),
                    "rev-model", null, false);
            var api = ArchitectureQueryApi.withArchitectureChat(new ArchitectureQueryApi(fixture.store()), model);

            String longAssistantTurn = "Detailed prior architectural review. ".repeat(300); // > 10,000 chars (< 32,000)
            var resp = api.handle("POST", "/api/v1/graphs/" + fixture.graph().identity().value() + "/architecture-chat",
                    "application/json",
                    bytes(Map.of(
                            "context", fixture.context,
                            "mode", "DESIGN",
                            "reviewer", true,
                            "agentic", true,
                            "messages", List.of(
                                    Map.of("role", "user", "content", "Review initial layers."),
                                    Map.of("role", "assistant", "content", longAssistantTurn),
                                    Map.of("role", "user", "content", "Now check repo map and Spring conditions.")))),
                    () -> false);
            assertEquals(200, resp.status(), () -> body(resp).toString());
            var data = GraphJson.object(body(resp).get("data"));
            assertEquals("ADVICE", data.get("status"));
            assertEquals("Review findings", data.get("conversationTitle"));
            assertFalse(GraphJson.string(data.get("answer")).contains("<conversation_title>"));
            assertEquals(2, GraphJson.array(data.get("agentTrace")).size());
            assertTrue(GraphJson.array(data.get("citations")).size() >= 3);
        } finally {
            mock.stop(0);
        }
    }

    @Test void streamStripsChunkSplitConversationTitleAndMapsMalformedSseTo502() throws Exception {
        var fixture = new QueryFixture().node("A");
        var badMode = new AtomicBoolean(false);
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                String sse;
                if (badMode.get()) {
                    sse = "data: {not-valid-json}\n\ndata: [DONE]\n\n";
                } else {
                    sse = "data: " + CanonicalJson.write(Map.of("model", "m", "choices", List.of(Map.of("delta", Map.of("content", "<conv"))))) + "\n\n"
                        + "data: " + CanonicalJson.write(Map.of("model", "m", "choices", List.of(Map.of("delta", Map.of("content", "ersation_title>Clean Title</conversation_"))))) + "\n\n"
                        + "data: " + CanonicalJson.write(Map.of("model", "m", "choices", List.of(Map.of("delta", Map.of("content", "title>\n\nVisible answer [E1]."))))) + "\n\n"
                        + "data: [DONE]\n\n";
                }
                exchange.getResponseBody().write(sse.getBytes(StandardCharsets.UTF_8));
                exchange.close();
            });
            mock.start();
            var model = new ArchitectureChatModel(URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1/chat/completions"),
                    "m", null, false);
            var api = ArchitectureQueryApi.withArchitectureChat(new ArchitectureQueryApi(fixture.store()), model);

            var deltas = new StringBuilder();
            var completed = new String[1];
            api.streamArchitectureChat(fixture.graph().identity(),
                    bytes(Map.of("context", fixture.context, "mode", "EXPLORE",
                            "messages", List.of(Map.of("role", "user", "content", "Explain A.")))),
                    "application/json", () -> false, new ArchitectureQueryApi.SseEmitter() {
                        @Override public void stage(String message) {}
                        @Override public void tool(Map<String, Object> payload) {}
                        @Override public void delta(String text) { deltas.append(text); }
                        @Override public void complete(byte[] envelopeJson) { completed[0] = new String(envelopeJson, StandardCharsets.UTF_8); }
                        @Override public void error(int status, String code, String message) { fail("Unexpected error: " + code); }
                    });
            assertEquals("Visible answer [E1].", deltas.toString());
            assertNotNull(completed[0]);
            var data = GraphJson.object(GraphJson.object(GraphJson.read(completed[0], GraphJson.Limits.defaults())).get("data"));
            assertEquals("Clean Title", data.get("conversationTitle"));
            assertEquals("Visible answer [E1].", data.get("answer"));

            badMode.set(true);
            var errorStatus = new int[1];
            var errorCode = new String[1];
            api.streamArchitectureChat(fixture.graph().identity(),
                    bytes(Map.of("context", fixture.context, "mode", "EXPLORE",
                            "messages", List.of(Map.of("role", "user", "content", "Explain A.")))),
                    "application/json", () -> false, new ArchitectureQueryApi.SseEmitter() {
                        @Override public void stage(String message) {}
                        @Override public void tool(Map<String, Object> payload) {}
                        @Override public void delta(String text) {}
                        @Override public void complete(byte[] envelopeJson) { fail("Should fail on malformed SSE"); }
                        @Override public void error(int status, String code, String message) { errorStatus[0] = status; errorCode[0] = code; }
                    });
            assertEquals(502, errorStatus[0]);
            assertEquals("CHAT_INVALID_MODEL_OUTPUT", errorCode[0]);
        } finally {
            mock.stop(0);
        }
    }

    // --- 4. Insights Edge-Level SDP + Martin Abstractness/Distance & Hexagonal/Clean Auto-Policy ---

    @Test void insightsComputesEdgeSdpViolationsAndAbstractnessDistanceMetrics() {
        // StableCore has 3 incoming callers (I = 1/4 = 0.25) and depends on UnstableUtil (0 in except StableCore, 3 out -> I = 3/4 = 0.75)
        // Delta = 0.75 - 0.25 = 0.50 > 0.20 -> edge-level SDP violation!
        var f = new QueryFixture()
                .node("Caller1").node("Caller2").node("Caller3")
                .node("StableCore").node("UnstableUtil")
                .node("Leaf1").node("Leaf2").node("Leaf3")
                .edge("Caller1", "StableCore")
                .edge("Caller2", "StableCore")
                .edge("Caller3", "StableCore")
                .edge("StableCore", "UnstableUtil")
                .edge("UnstableUtil", "Leaf1")
                .edge("UnstableUtil", "Leaf2")
                .edge("UnstableUtil", "Leaf3");
        // Mark StableCore and Leaf1 as abstract types via canonical java.type-shape evidence
        f.evidence.add(GraphEvidence.capture("java.type-shape",
                Map.of("type", QueryFixture.id("StableCore"), "kind", "INTERFACE", "abstractType", true, "independent", true)));
        f.evidence.add(GraphEvidence.capture("java.type-shape",
                Map.of("type", QueryFixture.id("Leaf1"), "kind", "CLASS", "abstractType", true, "independent", true)));

        var service = new ArchitectureMetricsService(f.graph()).queries();
        var report = ArchitectureInsightsAdvisor.analyze(service, f.request(), Optional.empty(), 10);

        assertEquals(1, report.summary().edgeSdpViolationCount());
        assertEquals(1, report.sdpViolations().size());
        assertEquals(QueryFixture.id("StableCore"), report.sdpViolations().getFirst().source());
        assertEquals(QueryFixture.id("UnstableUtil"), report.sdpViolations().getFirst().target());
        assertTrue(report.sdpViolations().getFirst().delta().compareTo(new BigDecimal("0.2000")) > 0);
        // 2 abstract out of 8 entities -> A = 0.2500
        assertEquals(new BigDecimal("0.2500"), report.summary().abstractness());
        assertFalse(report.packageMetrics().isEmpty());
        assertTrue(report.markdownReport().contains("Edge-Level Stable Dependencies Principle (SDP) Violations"));
        assertTrue(report.markdownReport().contains("Package Abstractness & Distance from Main Sequence"));
    }

    @Test void suggestPolicyDetectsHexagonalAndCleanArchitectureRules() {
        var fixture = new QueryFixture();
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("hex-test");
        var ctx = new KnowledgeGraph.Context(analysis.value(), fixture.snapshot, Optional.of(analysis), fixture.base.identity());
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var evs = new ArrayList<GraphEvidence>(List.of(fixture.base));
        for (String pkg : List.of("com.acme.domain", "com.acme.domain.order", "com.acme.application.usecase",
                "com.acme.port.out", "com.acme.adapter.persistence", "com.acme.web.controller")) {
            var ev = GraphEvidence.capture("java.declaration", Map.of(
                    "entity", Map.of("canonicalName", pkg, "origin", "PROJECT"),
                    "spelling", pkg));
            evs.add(ev);
            nodes.add(new KnowledgeGraph.Node(QueryFixture.id("pkg:" + pkg), ctx.identity(), "PACKAGE", List.of(ev.identity())));
        }
        var graph = new KnowledgeGraph(fixture.snapshot, List.of(ctx), nodes, List.of(), evs);
        var queries = new ArchitectureMetricsService(graph).queries();
        var suggested = WorkbenchPolicyAssessment.suggestPolicy(queries, ctx, Scope.TYPE, () -> {});
        assertTrue(suggested.summary().contains("Hexagonal Architecture"), suggested.summary());
        assertTrue(suggested.yaml().contains("auto.ports-no-adapter-leakage"), suggested.yaml());
        assertTrue(suggested.yaml().contains("auto.domain-no-web-leakage"), suggested.yaml());
        assertTrue(suggested.yaml().contains("auto.domain-no-infrastructure-leakage"), suggested.yaml());
        assertTrue(suggested.yaml().contains("auto.domain-no-application-leakage"), suggested.yaml());
        assertTrue(suggested.yaml().contains("auto.application-no-web-leakage"), suggested.yaml());
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
