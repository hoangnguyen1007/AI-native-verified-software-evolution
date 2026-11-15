package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.evolution.analysis.evolution.SnapshotComparison.*;
import static org.junit.jupiter.api.Assertions.*;

class DeclaredYamlEvolutionTest {
    private static final RepositoryIdentity REPO = RepositoryIdentity.fromCanonicalCoordinate("urn:fixture:declared-yaml");
    private static final String PATH = "gateway/src/main/resources/application.yml";

    @Test void comparesOnlyBoundLocalDeclarationsAndKeepsImportEvidence() throws Exception {
        String before = """
                spring:
                  config:
                    import: optional:configserver:https://example.test
                server:
                  port: 8084
                """;
        String after = """
                spring:
                  config:
                    import: optional:configserver:https://example.test
                  cloud:
                    gateway:
                      routes:
                        - id: genai-service
                          uri: lb://genai-service
                """;
        var left = graph(before, true);
        var right = graph(after, true);
        var result = compare(left, right, 1);
        assertEquals(result.summary(), compare(left, right, 7).summary());
        var rows = EvolutionCompatibilityTest.items(result, Section.EVIDENCE);
        assertEquals(4, rows.size());
        assertTrue(EvolutionCompatibilityTest.items(result, Section.EVENTS).isEmpty());
        var port = rows.stream().filter(r -> r.get("configurationKey").equals(List.of("server", "port")))
                .findFirst().orElseThrow();
        assertEquals("DECLARATION_REMOVED", port.get("kind"));
        var operand = EvolutionRows.obj(port.get("left"));
        assertEquals(ContentDigest.sha256Utf8(before).value(), operand.get("contentDigest"));
        assertEquals(5, EvolutionRows.number(EvolutionRows.obj(operand.get("sourceSpan")).get("startLine")));
        assertEquals(3, EvolutionRows.list(EvolutionRows.obj(port.get("right")).get("evidence")).size());
        assertTrue(rows.stream().noneMatch(r -> Boolean.TRUE.equals(r.get("semanticAbsenceProved"))));
        assertTrue(rows.stream().anyMatch(r -> "IMPORT_DECLARATION_RETAINED".equals(r.get("kind"))));
        assertEquals(2, rows.stream().filter(r -> "DECLARATION_ADDED".equals(r.get("kind"))).count());
    }

    @Test void capsExpandedCoordinatesBeforeIndexingDeclarations() throws Exception {
        String sharedPrefix = "x".repeat(250) + ":\n";
        var input = new StringBuilder(sharedPrefix);
        for (int i = 0; i < 6_000; i++) input.append("  key").append(i).append(": 1\n");
        var result = compare(graph("server:\n  port: 8084\n", true), graph(input.toString(), true), 7);
        assertTrue(EvolutionCompatibilityTest.items(result, Section.EVIDENCE).isEmpty());
        assertTrue(EvolutionCompatibilityTest.items(result, Section.GAPS).stream()
                .anyMatch(r -> r.toString().contains("DECLARED_YAML_INPUT_LIMIT")));
    }

    @Test void unsupportedGraphSchemaCannotSupplyADecodedOperand() throws Exception {
        var left = graph("server:\n  port: 8084\n", true);
        var right = graph("server: {}\n", true);
        var memory = EvolutionSnapshot.memory(left, right);
        var request = new Request(REPO, left.identity(), right.identity(), Mode.STRICT,
                new Selection(Set.of(), Set.of(), Set.of(), false, true));
        var unsupported = new EvolutionSnapshot() {
            public Header header() { return new Header("unsupported-schema", right.identity(), right.snapshot(), 1, 2); }

            public com.evolution.analysis.graph.PartitionedGraphReader.Page<Partition> contexts(int size, Optional<String> cursor) {
                throw new AssertionError("No decoder is registered for this schema");
            }
            public com.evolution.analysis.graph.PartitionedGraphReader.Page<KnowledgeGraph.Node> nodes(
                    String context, int size, Optional<String> cursor) { throw new AssertionError(); }
            public com.evolution.analysis.graph.PartitionedGraphReader.Page<KnowledgeGraph.Edge> edges(
                    String context, int size, Optional<String> cursor) { throw new AssertionError(); }
            public com.evolution.analysis.graph.PartitionedGraphReader.Page<GraphEvidence> evidence(
                    int size, Optional<String> cursor) { throw new AssertionError(); }
            public Optional<GraphEvidence> evidence(ContentDigest id) { throw new AssertionError(); }
        };
        var result = SnapshotComparison.compare(request,
                (id, stop) -> id.equals(right.identity()) ? unsupported : memory.open(id, stop),
                EvolutionIndex.memory(), 3, () -> false);
        assertEquals(Status.WITHHELD, result.summary().status());
        assertTrue(EvolutionCompatibilityTest.items(result, Section.EVIDENCE).isEmpty());
        assertTrue(EvolutionCompatibilityTest.items(result, Section.INPUTS).stream()
                .anyMatch(row -> "RIGHT".equals(row.get("side")) && "UNSUPPORTED".equals(row.get("status"))));
    }

    @Test void missingOrUnparseableBytesNeverProveRemoval() throws Exception {
        var left = graph("server:\n  port: 8084\n", true);
        for (var right : List.of(graph("server: {}\n", false), graph("server: [\n", true),
                graph("server:\n  port: 123\n  port: 456\n", true))) {
            var result = compare(left, right, 3);
            assertEquals(Status.PARTIAL, result.summary().status());
            assertTrue(EvolutionCompatibilityTest.items(result, Section.EVIDENCE).isEmpty());
            assertTrue(EvolutionCompatibilityTest.items(result, Section.GAPS).stream()
                    .anyMatch(r -> r.toString().contains("DECLARED_YAML_")));
        }
    }

    private static Result compare(KnowledgeGraph left, KnowledgeGraph right, int size) throws Exception {
        var request = new Request(REPO, left.identity(), right.identity(), Mode.OBSERVATION,
                new Selection(Set.of(), Set.of(), Set.of(), false, true));
        return SnapshotComparison.compare(request, EvolutionSnapshot.memory(left, right),
                EvolutionIndex.memory(), size, () -> false);
    }

    private static KnowledgeGraph graph(String yaml, boolean captured) {
        var digest = ContentDigest.sha256Utf8(yaml);
        var module = ModuleDescriptor.create(REPO, "gateway", "gateway");
        var document = SourceDocument.create(REPO, module, PATH, digest, SourceClassification.OTHER);
        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false,
                List.of(SnapshotFile.from(document)), List.of(document));
        var repository = GraphEvidence.capture("repository.snapshot", snapshot);
        var context = new KnowledgeGraph.Context(snapshot.identity().value(), snapshot.identity(),
                Optional.empty(), repository.identity());
        var evidence = new ArrayList<>(List.of(repository));
        if (captured) {
            var bytes = yaml.getBytes(StandardCharsets.UTF_8);
            var content = GraphEvidence.capture("source.content", Map.of("contentDigest", digest,
                    "size", bytes.length, "bytesBase64", Base64.getEncoder().encodeToString(bytes)));
            var source = GraphEvidence.capture("source.bytes", Map.of("document", document,
                    "contentDigest", digest, "contentEvidence", content.identity()));
            evidence.add(content);
            evidence.add(source);
        }
        return new KnowledgeGraph(snapshot.identity(), List.of(context), List.of(), List.of(), evidence);
    }
}
