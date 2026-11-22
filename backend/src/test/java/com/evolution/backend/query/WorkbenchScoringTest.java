package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchScoringTest {
    @Test void publishedPythonInventoryScoresWithoutPolicyAndRetainsExactEvidence() {
        var first = repository("README.md", "pyproject.toml", "tests/test_api.py", "~.git");
        var second = repository("README.md", "pyproject.toml", "tests/test_api.py", ".github/workflows/test.yml");
        var data = data(request(first, "GET", "repository-score", null));
        assertEquals("COMPLETE", data.get("status"));
        assertEquals(80, ((Number) data.get("score")).intValue());
        assertArrayEquals(request(first, "GET", "repository-score", null).body(),
                request(first, "GET", "repository-score", null).body());
        assertEquals(100, ((Number) data(request(second, "GET", "repository-score", null)).get("score")).intValue());
    }

    @Test void unavailableRowsPreservePossiblePointsWithoutAwardingThem() {
        var fixture = repository("README.md", "pyproject.toml", "!tests/test_api.py");
        var score = data(request(fixture, "GET", "repository-score", null));
        assertEquals("PARTIAL", score.get("status"));
        assertNull(score.get("score"));
        assertEquals(50, ((Number) score.get("lower")).intValue());
        assertEquals(100, ((Number) score.get("upper")).intValue());
    }

    @Test void documentationOnlyRepositoryUsesApplicableWeights() {
        var score = data(request(repository("README.rst", ".github/workflows/docs.yml"), "GET", "repository-score", null));
        assertEquals(45, ((Number) score.get("applicableWeight")).intValue());
        assertEquals(100, ((Number) score.get("score")).intValue());
    }

    @Test void repositoryCanUseItsOwnExactEvidencePaths() {
        var fixture = repository("README.md", "pyproject.toml", "quality/smoke.sh", ".agents/skills/tool/tests/test_tool.py");
        var defaultScore = data(request(fixture, "GET", "repository-score", null));
        var custom = data(request(fixture, "POST", "repository-score", Map.of("paths", Map.of("tests", "quality/smoke.sh"))));
        assertEquals(50, ((Number) defaultScore.get("score")).intValue());
        assertEquals(80, ((Number) custom.get("score")).intValue());
        assertEquals(400, request(fixture, "POST", "repository-score", Map.of("paths", Map.of("tests", "../smoke.sh"))).status());
    }

    @Test void ownerCanEvaluateRulesOnTheSameSnapshotWithoutReanalysis() {
        var registry = new PublishedReports();
        var fixture = new QueryFixture();
        var manifest = GraphEvidence.capture("analysis.manifest", Map.of());
        var graph = new KnowledgeGraph(fixture.snapshot, List.of(new KnowledgeGraph.Context(
                fixture.context, fixture.snapshot, Optional.empty(), manifest.identity())),
                List.of(), List.of(), List.of(manifest));
        var store = new InMemoryGraphStore();
        store.save(graph);
        var api = new ArchitectureQueryApi(store, registry, ApiLimits.defaults());
        var path = "/api/v1/graphs/" + graph.identity().value() + "/policy-assessment";
        var defaultResult = data(api.handle("POST", path, "application/json",
                bytes(Map.of("context", fixture.context)), () -> false));
        assertEquals("DEFAULT", defaultResult.get("policyKind"));
        var custom = """
                schema: architecture-policy:v1
                id: project.boundaries
                version: "1"
                rules:
                  - id: project.no-cycles
                    version: "1"
                    kind: ACYCLIC
                    severity: ERROR
                    scope: TYPE
                    relationships: [graph.depends-on]
                    within: {origins: [PROJECT]}
                """;
        var customResult = data(api.handle("POST", path, "application/json",
                bytes(Map.of("context", fixture.context, "policy", custom)), () -> false));
        assertEquals("CUSTOM", customResult.get("policyKind"));
        assertNotEquals(defaultResult.get("report"), customResult.get("report"));
        assertEquals(2, registry.forGraph(graph.identity()).size());
        var invalid = data(api.handle("POST", path, "application/json",
                bytes(Map.of("context", fixture.context, "policy", "rules: [")), () -> false));
        assertNull(invalid.get("report"));
        assertFalse(GraphJson.array(invalid.get("problems")).isEmpty());
        assertEquals(2, registry.forGraph(graph.identity()).size());
    }

    private static KnowledgeGraph repository(String... paths) {
        var fixture = new QueryFixture();
        var scope = CanonicalJson.sha256(List.of(paths));
        var inventory = GraphEvidence.capture("polyglot.scan", Map.of(
                "inventory", Map.of("inventory", scope, "entries", Map.of("rows", paths.length))));
        fixture.evidence.add(inventory);
        for (var path : paths) {
            var row = GraphEvidence.capture("polyglot.inventory-entry", Map.of("scope", scope, "fields", Map.of(
                    "rawPath", LogicalPath.utf8(path.replaceFirst("^[!~]", "")).rawBase64(),
                    "kind", path.startsWith("~") ? "DIRECTORY" : "FILE",
                    "population", "SOURCE", "disposition", path.startsWith("!") ? "GAP"
                            : path.startsWith("~") ? "EXCLUDED" : "ACQUIRED")));
            fixture.evidence.add(row);
            fixture.nodes.add(new KnowledgeGraph.Node(QueryFixture.id(path), fixture.context, "INVENTORY_ENTRY", List.of(row.identity())));
        }
        return fixture.graph();
    }

    private static byte[] bytes(Object input) {
        return CanonicalJson.write(input).getBytes(StandardCharsets.UTF_8);
    }

    private static ArchitectureQueryApi.Response request(KnowledgeGraph graph, String method, String route, Object body) {
        var store = new InMemoryGraphStore();
        store.save(graph);
        return new ArchitectureQueryApi(store).handle(method, "/api/v1/graphs/" + graph.identity().value() + "/" + route,
                body == null ? null : "application/json", body == null ? new byte[0] : bytes(body), () -> false);
    }

    private static Map<String, Object> data(ArchitectureQueryApi.Response response) {
        assertEquals(200, response.status());
        return GraphJson.object(GraphJson.object(GraphJson.read(
                new String(response.body(), StandardCharsets.UTF_8), GraphJson.Limits.defaults())).get("data"));
    }
}
