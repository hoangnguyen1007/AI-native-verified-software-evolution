package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.metrics.ArchitectureMetricsService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class GitHubProvenanceAndCodeownersTest {

    @Test
    void parsesOfficialGitHubCodeownersPrecedenceLastMatchCaseSensitivityAndUnsupportedRules() {
        String codeowners = """
                # Global fallback
                * @acme/platform

                # Directory rules & globs
                *.java @acme/java-guild
                /docs/ @acme/docs-team
                docs/* @acme/root-docs-only
                apps/ @acme/apps-team
                **/billing/ @acme/billing-team
                src/main/java/com/acme/payments/** @acme/payments @alice

                # Case sensitivity check
                SRC/MAIN/JAVA/COM/ACME/PAYMENTS/** @wrong-case

                # Unsupported .gitignore syntax MUST be ignored per official GitHub spec
                !*.java @ignored-negation
                src/[a-z]/*.java @ignored-range
                \\#literal @ignored-escaped-hash

                # Explicit unowned override clears prior match
                src/main/java/com/acme/payments/generated/
                """;
        var index = GitHubProvenance.CodeownersIndex.parse(".github/CODEOWNERS", codeowners);
        assertTrue(index.found());
        assertEquals(Optional.of(".github/CODEOWNERS"), index.sourceFile());

        // Global fallback
        assertEquals(List.of("@acme/platform"), index.ownersOf("README.md"));
        // *.java overrides *
        assertEquals(List.of("@acme/java-guild"), index.ownersOf("src/main/java/com/acme/App.java"));
        // /docs/ is recursive, while docs/* only matches direct children (last match wins for direct child)
        assertEquals(List.of("@acme/root-docs-only"), index.ownersOf("docs/guide.md"));
        assertEquals(List.of("@acme/docs-team"), index.ownersOf("docs/nested/deep.md"));
        // Unanchored apps/ matches nested apps/ anywhere
        assertEquals(List.of("@acme/apps-team"), index.ownersOf("services/apps/worker.go"));
        // **/billing/ matches nested billing dir
        assertEquals(List.of("@acme/billing-team"), index.ownersOf("modules/eu/billing/Invoice.java"));
        // Payments team + @alice (case-sensitive; uppercase rule does not match!)
        assertEquals(List.of("@acme/payments", "@alice"), index.ownersOf("src/main/java/com/acme/payments/ChargeService.java"));
        // Explicit unowned directory rule clears owners
        assertEquals(List.of(), index.ownersOf("src/main/java/com/acme/payments/generated/Dto.java"));
        // Reverse lookup
        var ownedByAlice = index.pathsOwnedBy("alice", List.of(
                "README.md",
                "src/main/java/com/acme/payments/ChargeService.java",
                "src/main/java/com/acme/payments/generated/Dto.java"));
        assertEquals(List.of("src/main/java/com/acme/payments/ChargeService.java"), ownedByAlice);
    }

    @Test
    void extractsGitHubCoordsPermalinksCodeownersPrecedenceAndEnrichesAgentTools() throws Exception {
        String sha = "0123456789abcdef0123456789abcdef01234567";
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("https://github.com/acme/payments-core.git");
        var snapId = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("gh-prov-snap"));
        String ctx = snapId.value();

        String ghCo = "* @acme/default\nsrc/main/java/com/acme/OrderService.java @acme/orders @bob\n";
        String rootCo = "* @acme/should-lose-precedence\n";
        String javaSrc = "package com.acme;\npublic class OrderService {\n    void place() {}\n}\n";

        var baseEv = GraphEvidence.capture("fixture.context", Map.of("version", "1"));
        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of(
                "repository", repoId.value(),
                "remoteUrl", "https://github.com/acme/payments-core.git",
                "revision", sha,
                "identity", snapId.value(),
                "dirty", false));
        var coGhEv = sourceBytesEv(".github/CODEOWNERS", ghCo);
        var coRootEv = sourceBytesEv("CODEOWNERS", rootCo);
        var javaEv = sourceBytesEv("src/main/java/com/acme/OrderService.java", javaSrc);

        var graph = new KnowledgeGraph(
                snapId,
                List.of(new KnowledgeGraph.Context(ctx, snapId, Optional.empty(), baseEv.identity())),
                List.of(new KnowledgeGraph.Node(QueryFixture.id("OrderService"), ctx, "TYPE", List.of(baseEv.identity()))),
                List.of(),
                List.of(baseEv, snapEv, coGhEv, coRootEv, javaEv));

        // 1. Provenance extraction & permalink encoding
        var coords = GitHubProvenance.extractFromGraph(graph).orElseThrow();
        assertEquals("github.com", coords.host());
        assertEquals("acme/payments-core", coords.fullName());
        assertEquals(sha, coords.commitSha());
        assertEquals(Optional.of("https://github.com/acme/payments-core/blob/" + sha + "/src/main/java/com/acme/OrderService.java#L2-L3"),
                GitHubProvenance.blobPermalink(coords, "src/main/java/com/acme/OrderService.java", 2, 3));
        assertEquals(Optional.of("https://github.com/acme/payments-core/blob/" + sha + "/docs/my%20file.md#L10"),
                GitHubProvenance.blobPermalink(coords, "docs/my file.md", 10, 10));
        assertTrue(GitHubProvenance.blobPermalink(coords, "../secret.txt", 1, 1).isEmpty());

        // 2. .github/CODEOWNERS wins over root CODEOWNERS
        var co = GitHubProvenance.CodeownersIndex.fromGraph(graph);
        assertEquals(Optional.of(".github/CODEOWNERS"), co.sourceFile());
        assertEquals(List.of("@acme/orders", "@bob"), co.ownersOf("src/main/java/com/acme/OrderService.java"));

        // 3. ArchitectureAgent executes inspect_codeowners + read_captured_source with owners & permalink
        var callCount = new AtomicInteger();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/chat/completions", ex -> {
                try (ex) {
                    int n = callCount.incrementAndGet();
                    Map<String, Object> msg;
                    if (n == 1) {
                        msg = Map.of("role", "assistant", "content", "", "tool_calls", List.of(
                                Map.of("id", "c1", "type", "function", "function", Map.of(
                                        "name", "inspect_codeowners",
                                        "arguments", CanonicalJson.write(Map.of("path", "src/main/java/com/acme/OrderService.java")))),
                                Map.of("id", "c2", "type", "function", "function", Map.of(
                                        "name", "read_captured_source",
                                        "arguments", CanonicalJson.write(Map.of("path", "src/main/java/com/acme/OrderService.java", "startLine", 1, "endLine", 4))))));
                    } else {
                        msg = Map.of("role", "assistant", "content",
                                "<conversation_title>OrderService ownership</conversation_title>\nOwned by @acme/orders and @bob [E3] [E4].");
                    }
                    byte[] ok = CanonicalJson.write(Map.of(
                            "model", "agent-test",
                            "choices", List.of(Map.of("finish_reason", n == 1 ? "tool_calls" : "stop", "message", msg))))
                            .getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, ok.length);
                    ex.getResponseBody().write(ok);
                }
            });
            mock.start();

            var store = new InMemoryGraphStore();
            store.save(graph);
            var queries = new ArchitectureMetricsService(graph).queries();
            var chatModel = new ArchitectureChatModel(
                    URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1"),
                    "agent-test", null, false);
            var input = new ArchitectureChat.Input(ctx, ArchitectureChat.Mode.EXPLORE,
                    List.of(new ArchitectureChat.Message("user", "Who owns OrderService.java?")),
                    Optional.empty(), Optional.empty(), false, false, true, Optional.empty(), false, true);
            var initialCtx = ArchitectureChat.retrieve(input, graph.contexts().getFirst(), queries, Optional.empty());
            var result = ArchitectureAgent.run(chatModel, input, initialCtx, queries, new PublishedReports(),
                    Optional.empty(), queries::assessmentStopReason, ArchitectureAgent.ProgressListener.noop());

            assertEquals("ADVICE", result.status());
            assertTrue(result.agentTrace().stream().anyMatch(s -> "inspect_codeowners".equals(s.tool())));
            var coEv = result.evidence().stream()
                    .filter(e -> e.label().contains("CODEOWNERS lookup"))
                    .findFirst().orElseThrow();
            assertEquals(List.of("@acme/orders", "@bob"), coEv.data().get("owners"));
            var srcEv = result.evidence().stream()
                    .filter(e -> e.kind() == ArchitectureChat.Kind.SOURCE)
                    .findFirst().orElseThrow();
            assertEquals(List.of("@acme/orders", "@bob"), srcEv.data().get("owners"));
            assertEquals("https://github.com/acme/payments-core/blob/" + sha + "/src/main/java/com/acme/OrderService.java#L1-L4",
                    srcEv.data().get("permalink"));
        } finally {
            mock.stop(0);
        }
    }

    private static GraphEvidence sourceBytesEv(String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String digest = ContentDigest.sha256(bytes).value();
        return GraphEvidence.capture("source.bytes", Map.of(
                "document", Map.of("path", path, "contentDigest", digest, "size", bytes.length),
                "bytesBase64", Base64.getEncoder().encodeToString(bytes)));
    }
}
