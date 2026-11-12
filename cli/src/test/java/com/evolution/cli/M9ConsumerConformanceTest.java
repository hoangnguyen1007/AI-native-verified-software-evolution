package com.evolution.cli;

import com.evolution.analysis.assessment.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.ArchitectureQuery.Page;
import com.evolution.analysis.query.ArchitectureQuery.Request;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.backend.query.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.math.BigDecimal;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class M9ConsumerConformanceTest {
    private static final String A = "package example; class A { B b; }";
    private static final String B = "package example; class B {}";
    private static final String POLICY = """
            schema: architecture-policy:v1
            id: fixture.architecture
            version: "1"
            rules:
              - {id: fixture.boundary, version: "1", kind: FORBIDDEN_DEPENDENCY, severity: ERROR, scope: TYPE, relationships: [graph.depends-on], from: {names: [example.A]}, to: {names: [example.B]}}
              - {id: fixture.cycles, version: "1", kind: ACYCLIC, severity: ERROR, scope: TYPE, relationships: [graph.depends-on], within: {names: ['*']}}
            """;

    @TempDir Path workspace;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

    @Test void completeOperandsReachCliAssessmentAndJsonExport() throws Exception {
        var fixture = fixture();
        var graph = fixture.graph();
        var expected = report(graph, fixture.context());
        var score = expected.assessment().orElseThrow();
        assertEquals(new BigDecimal("67.5"), score.score().orElseThrow(), score.reasons().toString());
        assertEquals(new BigDecimal("6750"), score.weightedNumerator().stripTrailingZeros().setScale(0));
        assertEquals(100, score.applicableWeight().intValueExact());
        assertTrue(score.confidence().sufficient());
        var finding = expected.evaluation().findings().getFirst();
        assertEquals(List.of(fixture.source(), fixture.target()), finding.bundle().path());
        assertEquals(List.of(fixture.span()), finding.bundle().sources().stream().map(PolicyEvaluation.SourceEvidence::span).toList());
        assertEquals(ContentDigest.sha256Utf8(A), finding.bundle().sources().getFirst().sourceDigest());
        assertEquals(2, expected.metrics().orElseThrow().counts().declaredRules());

        var policy = Files.writeString(workspace.resolve("policy.yml"), POLICY);
        var flags = flags(graph, fixture.context(), policy);
        var cli = run(flags, "score", "--json");
        assertEquals(new BigDecimal("67.5"), data(cli.body()).get("score"));
        assertEquals(3, cli.code());
        var exported = run(flags, "export", "json", "--operation", "assessment");
        assertEquals(cli.code(), exported.code());
        assertEquals(cli.body(), exported.body());
        assertEquals(CanonicalJson.write(score), CanonicalJson.write(data(cli.body())));

        var degraded = withoutSourceAndClasspath(fixture);
        var degradedReport = report(degraded, fixture.context());
        var degradedFlags = flags(degraded, fixture.context(), policy);
        var store = new InMemoryGraphStore(); store.save(graph); store.save(degraded);
        var reports = new PublishedReports();
        String reportRoute = "/reports/" + reports.publish(expected).value();
        String degradedRoute = "/reports/" + reports.publish(degradedReport).value();
        try (var server = new ArchitectureHttpServer(new ArchitectureQueryApi(store, reports, ApiLimits.defaults()), 0)) {
            server.start();
            assertEquals(cli.body(), post(server, graph, reportRoute + "/assessment", Map.of()));
            var request = Request.dependencies(graph.identity(), fixture.context());
            var queries = new ArchitectureMetricsService(graph).queries();
            var summary = parity(server, graph, flags, "/queries", "summary", Map.of("request", request));
            assertEquals(CanonicalJson.write(queries.summary(request)), CanonicalJson.write(summary));
            assertEquals(Map.of("TYPE", 2L, "MODULE", 1L, "SOURCE_DOCUMENT", 2L), queries.summary(request).items().getFirst().nodeCounts());
            var metric = parity(server, graph, flags, "/queries", "metric", Map.of("request", request, "arguments",
                    Map.of("metric", "metric.density", "subject", fixture.context())));
            assertEquals(CanonicalJson.write(queries.metric(request, "metric.density", fixture.context())), CanonicalJson.write(metric));
            var policyMetrics = parity(server, graph, flags, reportRoute, "policy-metrics", Map.of());
            assertEquals(CanonicalJson.write(expected.metrics().orElseThrow()), CanonicalJson.write(policyMetrics));
            var findings = parity(server, graph, flags, reportRoute, "findings", Map.of("page", Page.first()));
            assertEquals(1, GraphJson.array(findings.get("items")).size());
            var bundle = parity(server, graph, flags, reportRoute, "bundle", Map.of("arguments", Map.of("identity", finding.bundle().identity())));
            assertEquals(CanonicalJson.write(finding.bundle()), CanonicalJson.write(bundle));
            var replay = parity(server, graph, flags, reportRoute, "replay", Map.of("arguments", Map.of("identity", finding.identity(), "counterWitness", false)));
            assertEquals(CanonicalJson.write(queries.replayRuleWitness(finding.bundle().request(), finding.bundle().predicate(), finding.bundle().region().witness().orElseThrow(), false)), CanonicalJson.write(replay));
            assertSarif(run(flags, "export", "sarif").body(), finding);

            var withheld = parity(server, degraded, degradedFlags, degradedRoute, "assessment", Map.of());
            assertEquals("WITHHELD", withheld.get("status")); assertNull(withheld.get("score"));
            var gaps = parity(server, degraded, degradedFlags, "/queries", "gaps", Map.of("request", Request.dependencies(degraded.identity(), fixture.context()), "page", Page.first()));
            assertEquals(1, GraphJson.array(gaps.get("items")).size());
            assertNull(gaps.get("exactTotal"));
            var proof = parity(server, graph, flags, "/queries", "evidence", Map.of("request", request, "arguments",
                    Map.of("reference", finding.bundle().sources().getFirst().evidence())));
            assertEquals(CanonicalJson.write(queries.evidence(request, finding.bundle().sources().getFirst().evidence())), CanonicalJson.write(proof));

            if (System.getProperty("m9.node") != null) runWorkbenchModel(Map.of("summary", summary, "assessment", data(cli.body()), "findings", findings,
                    "bundle", bundle, "bundleId", finding.bundle().identity(), "replay", replay, "metric", metric, "gaps", gaps, "withheld", withheld));
        }
    }

    private List<String> flags(KnowledgeGraph graph, String context, Path policy) throws IOException {
        var file = Files.writeString(workspace.resolve(graph.identity().value().substring(7) + ".json"), new GraphSnapshotCodec().encode(graph));
        return List.of("--graph", file.toString(), "--context", context, "--policy", policy.toString());
    }

    private Map<String, Object> parity(ArchitectureHttpServer server, KnowledgeGraph graph, List<String> flags,
                                        String route, String operation, Object request) throws Exception {
        var file = Files.writeString(workspace.resolve(operation + "-request.json"), CanonicalJson.write(request));
        var cli = run(flags, "query", operation, "--request", file.toString(), "--json");
        var export = run(flags, "export", "json", "--operation", operation, "--request", file.toString());
        assertEquals(post(server, graph, route + "/" + operation, request), cli.body(), operation);
        assertEquals(cli.body(), export.body(), operation);
        return data(cli.body());
    }

    private Map<String, Object> post(ArchitectureHttpServer server, KnowledgeGraph graph, String route, Object body) throws Exception {
        var url = server.uri().resolve("/api/v1/graphs/" + graph.identity().value() + route);
        var response = http.send(HttpRequest.newBuilder(url).timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), route);
        return object(response.body());
    }

    private static void assertSarif(Map<String, Object> sarif, PolicyEvaluation.Finding finding) {
        var run = GraphJson.object(GraphJson.array(sarif.get("runs")).getFirst());
        var row = GraphJson.object(GraphJson.array(run.get("results")).getFirst());
        var properties = GraphJson.object(row.get("properties"));
        assertEquals(finding.identity().value(), properties.get("findingIdentity"));
        assertEquals(CanonicalJson.write(finding), CanonicalJson.write(properties.get("canonicalFinding")));
        var location = GraphJson.object(GraphJson.array(row.get("locations")).getFirst());
        assertEquals(CanonicalJson.write(finding.bundle().sources().getFirst().span()),
                CanonicalJson.write(GraphJson.object(location.get("properties")).get("canonicalSpan")));
    }

    record Fixture(KnowledgeGraph graph, String context, String source, String target, SourceSpan span) {}

    static Fixture fixture() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:m9.3-consumers");
        var module = ModuleDescriptor.create(repo, ".", "fixture");
        var a = SourceDocument.create(repo, module, "src/main/java/example/A.java", ContentDigest.sha256Utf8(A), SourceClassification.MAIN);
        var b = SourceDocument.create(repo, module, "src/main/java/example/B.java", ContentDigest.sha256Utf8(B), SourceClassification.MAIN);
        var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false, List.of(SnapshotFile.from(a), SnapshotFile.from(b)), List.of(a, b));
        String root = snapshot.identity().value();
        var contextProof = GraphEvidence.capture("analysis.manifest", Map.of("fixture", "authored-field-type"));
        var analysis = new AnalysisIdentity("analysis:" + contextProof.identity().value());
        String context = analysis.value();
        var rootProof = GraphEvidence.capture("repository.snapshot", snapshot);
        var contexts = List.of(new KnowledgeGraph.Context(root, snapshot.identity(), Optional.empty(), rootProof.identity()),
                new KnowledgeGraph.Context(context, snapshot.identity(), Optional.of(analysis), contextProof.identity()));
        var evidence = new ArrayList<>(List.of(rootProof, contextProof));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var moduleProof = GraphEvidence.capture("module.descriptor", module);
        evidence.add(moduleProof);
        nodes.add(new KnowledgeGraph.Node(module.identity().value(), context, "MODULE", List.of(moduleProof.identity())));
        var entities = new ArrayList<String>();
        for (var document : List.of(a, b)) {
            String name = document.equals(a) ? "A" : "B";
            var declaration = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module.identity()), EntityKind.TYPE,
                    JavaSymbolName.topLevelType("example", name).canonicalName(), Optional.of(new SourceSpan(document.identity(), 1, 24, 1, 25)));
            var sourceProof = GraphEvidence.capture("source.document", document);
            var declarationProof = GraphEvidence.capture("java.declaration", Map.of("entity", declaration, "status", "RESOLVED", "spelling", name));
            evidence.addAll(List.of(sourceProof, declarationProof));
            nodes.add(new KnowledgeGraph.Node(document.identity().value(), root, "SOURCE_DOCUMENT", List.of(sourceProof.identity())));
            nodes.add(new KnowledgeGraph.Node(document.identity().value(), context, "SOURCE_DOCUMENT", List.of(sourceProof.identity())));
            nodes.add(new KnowledgeGraph.Node(declaration.identity().value(), context, "TYPE", List.of(declarationProof.identity())));
            entities.add(declaration.identity().value());
        }
        var span = new SourceSpan(a.identity(), 1, 28, 1, 29);
        var occurrence = GraphEvidence.capture("java.occurrence", Map.of("status", "RESOLVED", "derivation", "DIRECT", "span", span));
        evidence.add(occurrence);
        String source = entities.getFirst(), target = entities.getLast();
        var edge = new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("fixture-field-type", List.of(source, target)), context,
                "graph.depends-on", source, new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(target), Optional.empty()),
                List.of(occurrence.identity()), Optional.empty());
        return new Fixture(new KnowledgeGraph(snapshot.identity(), contexts, nodes, List.of(edge), evidence), context, source, target, span);
    }

    private static KnowledgeGraph withoutSourceAndClasspath(Fixture fixture) {
        var graph = fixture.graph();
        var analysis = new AnalysisIdentity(fixture.context());
        var context = new EvidenceContext(graph.snapshot(), Optional.of(analysis));
        var provider = new VersionedIdentifier("fixture.classpath", "v1");
        var observation = ProviderObservationReference.create(provider, "fixture.missing-artifact", graph.identity(), ContentDigest.sha256Utf8("removed classpath"));
        var subject = EvidenceSubject.observation(observation.identity());
        var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT, "java.exact-classpath", List.of(subject),
                EvidenceRequirement.AuthorizationClass.PASSIVE, List.of("Restore the registered artifact bytes."));
        var gap = CapabilityGapRecord.create(context, provider, "java.classpath", "CLASSPATH_ARTIFACT_UNAVAILABLE", subject, List.of(), List.of(observation),
                List.of(requirement), List.of(), List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "java.classpath")), List.of(), List.of(), List.of("Authored removal control."));
        var proof = GraphEvidence.capture("evidence.gap", gap);
        var nodes = new ArrayList<>(graph.nodes());
        nodes.removeIf(node -> node.context().equals(fixture.context()) && node.identity().equals(fixture.span().document().value()));
        nodes.add(new KnowledgeGraph.Node(gap.gapIdentity().value(), fixture.context(), "CAPABILITY_GAP", List.of(proof.identity())));
        var evidence = new ArrayList<>(graph.evidence()); evidence.add(proof);
        return new KnowledgeGraph(graph.snapshot(), graph.contexts(), nodes, graph.edges(), evidence);
    }

    static PublishedReports.Report report(KnowledgeGraph graph, String context) {
        var queries = new ArchitectureMetricsService(graph).queries();
        var compiled = new PolicyCompiler().compile(POLICY.getBytes(StandardCharsets.UTF_8), EvidenceContext.forSnapshot(graph.snapshot()), PolicyCompiler.Limits.defaults());
        var mapping = new PolicyGraphMapper().bind(compiled, queries, context, PolicyGraphMapper.Limits.defaults());
        var evaluation = new PolicyEvaluator().evaluate(compiled, mapping, queries, PolicyEvaluation.Limits.defaults());
        var metrics = new PolicyMetricsService().compute(compiled, mapping, evaluation, queries, PolicyMetrics.Limits.defaults());
        var score = new ArchitectureAssessmentService().assess(compiled, mapping, evaluation, queries, Optional.of(ArchitectureScoreModel.candidate(Scope.TYPE)),
                PolicyMetrics.Limits.defaults(), ArchitectureMetric.Limits.defaults(), ArchitectureAssessment.Limits.defaults());
        return new PublishedReports.Report(evaluation, Optional.of(metrics), Optional.of(score));
    }

    record CliResult(int code, Map<String, Object> body) {}

    CliResult run(List<String> flags, String... command) throws Exception {
        var args = new ArrayList<>(List.of(command));
        args.addAll(flags);
        var candidateJar = System.getProperty("m9.candidateJar");
        if (candidateJar != null) {
            var process = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", candidateJar));
            process.addAll(args);
            return externalCli(process);
        }
        var output = new StringWriter();
        var errors = new StringWriter();
        int code = new EvoApp(workspace, new StringReader(""), new PrintWriter(output), new PrintWriter(errors), false, Map.of()).execute(args.toArray(String[]::new));
        assertTrue(code == 0 || code == 3, () -> errors + "\n" + output);
        return new CliResult(code, object(output.toString()));
    }

    private CliResult externalCli(List<String> command) throws Exception {
        var out = Files.createTempFile(workspace, "cli-", ".json");
        var errors = Files.createTempFile(workspace, "cli-", ".err");
        int code = process(command, out, errors);
        assertTrue(code == 0 || code == 3, () -> "Packaged CLI exit " + code + " for " + command.subList(3, Math.min(6, command.size())));
        return new CliResult(code, object(Files.readString(out)));
    }

    private void runWorkbenchModel(Object payloads) throws Exception {
        var input = Files.writeString(workspace.resolve("http-payloads.json"), CanonicalJson.write(payloads));
        var script = Path.of("..", "backend", "src", "test", "js", "m9-consumer-model.test.cjs").toAbsolutePath().normalize();
        var output = workspace.resolve("node-result.txt");
        int code = process(List.of(System.getProperty("m9.node"), "--test-reporter=tap", script.toString(), input.toString()),
                output, workspace.resolve("node-result.err"));
        assertEquals(0, code, () -> Files.exists(output) ? read(output) : "Node produced no test output");
        assertTrue(Files.readString(output).contains("# pass 3"));
    }

    private int process(List<String> command, Path output, Path errors) throws Exception {
        var process = new ProcessBuilder(command).directory(workspace.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "External conformance check exceeded 30 seconds");
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String read(Path file) {
        try { return Files.readString(file); } catch (IOException unavailable) { return unavailable.getClass().getSimpleName(); }
    }

    static Map<String, Object> object(String text) {
        return GraphJson.object(GraphJson.read(text, GraphJson.Limits.defaults()));
    }

    static Map<String, Object> data(Map<String, Object> envelope) {
        return GraphJson.object(envelope.get("data"));
    }
}
