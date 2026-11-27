package com.evolution.backend.query;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.backend.query.llm.LlmProviderAdapter;
import com.evolution.backend.query.llm.OpenAiCompatibleAdapter;
import com.evolution.backend.query.repair.PrSuggestionFormatter;
import com.evolution.backend.query.repair.RepairVerificationReport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

final class PrSuggestionFormatterTest {

    @Test
    void formatsSingleAndMultiLineRightSuggestionsWithFourBacktickEscapeAndGitHubApiShape() {
        String githubPerFilePatch = """
                @@ -1,4 +1,6 @@
                 package com.acme.web;
                +import com.acme.legacy.Forbidden;
                +
                 public class OrderController {
                +    private final Forbidden bad = new Forbidden();
                     public String snippet() { return "```code```"; }
                 }
                """;
        var fileDiff = PrSuggestionFormatter.parseFilePatch("src/OrderController.java", "modified", githubPerFilePatch);
        assertTrue(fileDiff.commentableRightLines().containsAll(List.of(1, 2, 3, 4, 5, 6, 7)));

        // 1. Single-line suggestion on RIGHT line 5 (removes unused forbidden field)
        String before = """
                package com.acme.web;
                import com.acme.legacy.Forbidden;

                public class OrderController {
                    private final Forbidden bad = new Forbidden();
                    public String snippet() { return "```code```"; }
                }
                """;
        String afterSingleLine = """
                package com.acme.web;
                import com.acme.legacy.Forbidden;

                public class OrderController {
                    private final OrderPort port = null;
                    public String snippet() { return "```code```"; }
                }
                """;
        var finding = new PrSuggestionFormatter.FindingContext(
                "arch.no-web-to-legacy", "ERROR", "Web layer imports legacy class",
                "Route through OrderPort interface instead of Forbidden.",
                "src/OrderController.java", 5, 5, List.of("@acme/web-team"),
                Optional.of("https://github.com/acme/shop/blob/" + "a".repeat(40) + "/src/OrderController.java#L5"));

        var single = PrSuggestionFormatter.formatEditSuggestion(fileDiff, "src/OrderController.java",
                before, afterSingleLine, finding, Optional.of("SYNTAX_CHECKED")).orElseThrow();
        assertTrue(single.hasSuggestion());
        assertEquals(5, single.line());
        assertTrue(single.startLine().isEmpty(), "Single-line suggestion must omit start_line");
        var apiMap = single.toGitHubApiComment();
        assertEquals("src/OrderController.java", apiMap.get("path"));
        assertEquals(5, apiMap.get("line"));
        assertEquals("RIGHT", apiMap.get("side"));
        assertFalse(apiMap.containsKey("start_line"));
        assertFalse(apiMap.containsKey("start_side"));
        assertTrue(single.body().contains("```suggestion\n    private final OrderPort port = null;\n```"));
        assertTrue(single.body().contains("@acme/web-team"));

        // 2. Multi-line suggestion spanning lines 5-6 containing nested triple backticks -> MUST use 4-backtick fence!
        String afterMultiLine = """
                package com.acme.web;
                import com.acme.legacy.Forbidden;

                public class OrderController {
                    private final OrderPort port = null;
                    public String snippet() { return "```fixed```"; }
                }
                """;
        var multi = PrSuggestionFormatter.formatEditSuggestion(fileDiff, "src/OrderController.java",
                before, afterMultiLine, finding, Optional.of("ISOLATED_VM_VERIFIED")).orElseThrow();
        assertTrue(multi.hasSuggestion());
        assertEquals(Optional.of(5), multi.startLine());
        assertEquals(6, multi.line());
        var multiApi = multi.toGitHubApiComment();
        assertEquals(5, multiApi.get("start_line"));
        assertEquals("RIGHT", multiApi.get("start_side"));
        assertEquals(6, multiApi.get("line"));
        assertEquals("RIGHT", multiApi.get("side"));
        assertTrue(multi.body().contains("````suggestion\n"), "Nested ``` must escalate fence to 4 backticks:\n" + multi.body());
        assertTrue(multi.body().contains("\n````"), multi.body());
    }

    @Test
    void degradesSafelyWhenEditSpansMultipleHunksOrTouchesOutOfDiffLines() {
        String unified = """
                diff --git a/src/Big.java b/src/Big.java
                --- a/src/Big.java
                +++ b/src/Big.java
                @@ -1,2 +1,3 @@
                 class Big {
                +    int top = 1;
                     int a = 2;
                @@ -20,2 +21,3 @@
                     int b = 20;
                +    int bottom = 21;
                 }
                """;
        var parsed = PrSuggestionFormatter.parseUnifiedDiff(unified);
        var fileDiff = parsed.get("src/Big.java");
        assertNotNull(fileDiff);
        assertEquals(2, fileDiff.hunks().size());

        // Edit spans lines 2 through 22 (crosses two separate hunks + untouched middle lines -> GitHub would HTTP 422!)
        var lines = new ArrayList<String>();
        lines.add("class Big {");
        lines.add("    int top = 1;");
        for (int i = 3; i <= 21; i++) lines.add("    int line" + i + " = " + i + ";");
        lines.add("    int bottom = 21;");
        lines.add("}");
        String before = String.join("\n", lines) + "\n";
        var afterLines = new ArrayList<>(lines);
        afterLines.set(1, "    int top = 99;");
        afterLines.set(21, "    int bottom = 99;");
        String after = String.join("\n", afterLines) + "\n";

        var finding = new PrSuggestionFormatter.FindingContext(
                "arch.cycle", "ERROR", "Cross-hunk edit", "Touches lines outside one hunk.",
                "src/Big.java", 2, 2, List.of("@core"), Optional.empty());
        var comment = PrSuggestionFormatter.formatEditSuggestion(fileDiff, "src/Big.java",
                before, after, finding, Optional.empty()).orElseThrow();
        assertFalse(comment.hasSuggestion(), "Cross-hunk edit must degrade to diff details instead of invalid suggestion");
        assertEquals(2, comment.line());
        assertTrue(comment.body().contains("<details>"), comment.body());
        assertTrue(comment.body().contains("```diff"), comment.body());

        // Out-of-diff line returns Optional.empty() so it goes into outOfDiffFindings summary instead of HTTP 422
        var outFinding = new PrSuggestionFormatter.FindingContext(
                "arch.cycle", "WARN", "Unchanged line", "Line 12 is not in PR diff.",
                "src/Big.java", 12, 12, List.of("@core"), Optional.empty());
        assertTrue(PrSuggestionFormatter.formatFindingComment(fileDiff, outFinding).isEmpty());
    }

    @Test
    void buildsExecutivePrReviewWithScoreDeltaPermalinksCodeownersAndOptionalNarrator() throws Exception {
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/chat/completions", ex -> {
                try (ex) {
                    byte[] ok = CanonicalJson.write(Map.of(
                            "model", "review-model",
                            "choices", List.of(Map.of(
                                    "finish_reason", "stop",
                                    "message", Map.of("role", "assistant",
                                            "content", "Focus on breaking the new controller-to-legacy edge before merge.")))))
                            .getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, ok.length);
                    ex.getResponseBody().write(ok);
                }
            });
            mock.start();
            var adapter = new OpenAiCompatibleAdapter(
                    LlmProviderAdapter.ProviderKind.OPENAI_COMPAT,
                    URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1"),
                    "review-model", null, false, 128 * 1024, 256, "max_tokens", false, Duration.ofSeconds(10));

            String sha = "b".repeat(40);
            var gate = new PrSuggestionFormatter.QualityGateSummary("FAILED",
                    List.of("1 new MUST architecture violation"), List.of("Score dropped by -4.50"));
            var scoreDelta = new RepairVerificationReport.ScoreDelta(
                    Optional.of(new BigDecimal("88.00")), Optional.of(new BigDecimal("83.50")),
                    Optional.of(new BigDecimal("-4.50")), Optional.of(new BigDecimal("80.00")),
                    Optional.of(new BigDecimal("76.00")), "Healthy", "Healthy",
                    new BigDecimal("92.00"), new BigDecimal("92.00"), "HIGH", "HIGH",
                    List.of(new RepairVerificationReport.DimensionDelta("D1", "Acyclicity",
                            Optional.of(new BigDecimal("100.00")), Optional.of(new BigDecimal("85.00")),
                            Optional.of(new BigDecimal("-15.00")), "COMPLETE", "COMPLETE")));
            var structDelta = new RepairVerificationReport.StructuralDelta(0, 1, 0, 2, 4, 7, 0, 1,
                    new BigDecimal("0.30"), new BigDecimal("0.42"), new BigDecimal("0.10"), new BigDecimal("0.18"));
            var f1 = new PrSuggestionFormatter.FindingContext(
                    "no-web-to-legacy", "ERROR", "Web depends on legacy", "Forbidden edge",
                    "src/web/OrderController.java", 14, 14, List.of("@acme/web"), Optional.empty());
            var f2 = new PrSuggestionFormatter.FindingContext(
                    "no-cycles", "ERROR", "Package cycle", "Out of diff file",
                    "src/core/Billing.java", 40, 45, List.of("@acme/billing"), Optional.empty());
            var inline = List.of(new PrSuggestionFormatter.GitHubReviewComment(
                    "src/web/OrderController.java", 14, Optional.empty(), "RIGHT", Optional.empty(),
                    "Inline suggestion", true, Optional.of("// fixed"),
                    Optional.of("no-web-to-legacy"), Optional.of("ERROR"), List.of("@acme/web"), Optional.empty()));

            var review = PrSuggestionFormatter.buildExecutiveReview(
                    "acme", "shop", 42, sha, "main", "feat/checkout",
                    gate, Optional.of(scoreDelta), Optional.of(structDelta),
                    List.of(f1, f2), inline,
                    Map.of("src/web/OrderController.java", List.of("@acme/web"),
                            "src/core/Billing.java", List.of("@acme/billing")),
                    "PASSIVE_STATIC_ANALYSIS", Optional.of(adapter), () -> false);

            assertEquals("FAILED", review.verdict());
            assertEquals(1, review.outOfDiffFindings().size());
            assertEquals("src/core/Billing.java", review.outOfDiffFindings().getFirst().path());
            String md = review.markdownBody();
            assertTrue(md.startsWith(PrSuggestionFormatter.REVIEW_MARKER));
            assertTrue(md.contains("88.00"));
            assertTrue(md.contains("-4.50"));
            assertTrue(md.contains("https://github.com/acme/shop/blob/" + sha + "/src/web/OrderController.java#L14"));
            assertTrue(md.contains("@acme/web"));
            assertTrue(md.contains("@acme/billing"));
            assertTrue(md.contains("Advisory AI Summary"));
            assertTrue(md.contains("controller-to-legacy"));
        } finally {
            mock.stop(0);
        }
    }
}
