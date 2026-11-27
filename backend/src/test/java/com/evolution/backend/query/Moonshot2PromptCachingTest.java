package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.backend.query.llm.AnthropicMessagesAdapter;
import com.evolution.backend.query.llm.GeminiNativeAdapter;
import com.evolution.backend.query.llm.LlmProviderAdapter;
import com.evolution.backend.query.llm.OpenAiCompatibleAdapter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

final class Moonshot2PromptCachingTest {

    @Test
    void anthropicAdapterEmitsEphemeralCacheControlAndParsesUsageSyncAndStream() throws Exception {
        var lastBody = new AtomicReference<String>();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/messages", ex -> {
                try (ex) {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    lastBody.set(body);
                    var req = GraphJson.object(GraphJson.read(body, GraphJson.Limits.defaults()));
                    if (Boolean.TRUE.equals(req.get("stream"))) {
                        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                        ex.sendResponseHeaders(200, 0);
                        String sse = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":180,\"output_tokens\":4,\"cache_read_input_tokens\":4200,\"cache_creation_input_tokens\":0}}}\n\n"
                                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Cached reply [E1].\"}}\n\n"
                                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":95}}\n\n";
                        ex.getResponseBody().write(sse.getBytes(StandardCharsets.UTF_8));
                    } else {
                        byte[] out = CanonicalJson.write(Map.of(
                                "model", "claude-sonnet-4-5",
                                "stop_reason", "end_turn",
                                "content", List.of(Map.of("type", "text", "text", "Anthropic answer [E1].")),
                                "usage", Map.of(
                                        "input_tokens", 240,
                                        "output_tokens", 120,
                                        "cache_read_input_tokens", 3600,
                                        "cache_creation_input_tokens", 960))).getBytes(StandardCharsets.UTF_8);
                        ex.getResponseHeaders().add("Content-Type", "application/json");
                        ex.sendResponseHeaders(200, out.length);
                        ex.getResponseBody().write(out);
                    }
                }
            });
            mock.start();

            URI uri = URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1/messages");
            var adapter = new AnthropicMessagesAdapter(uri, "claude-sonnet-4-5", "ant-test-key", false,
                    256 * 1024, 4096, false, Duration.ofSeconds(10));

            List<Map<String, Object>> tools = List.of(
                    sampleTool("alpha_tool", "First tool"),
                    sampleTool("beta_tool", "Second tool"));
            var hint = new LlmProviderAdapter.PromptCacheHint(true, true, Optional.of("sha256:abcdef0123456789"));
            var cachedReq = new LlmProviderAdapter.ChatRequest(
                    List.of(
                            Map.of("role", "system", "content", "Static instructions."),
                            Map.of("role", "system", "content", "Snapshot-pinned brief."),
                            Map.of("role", "user", "content", "Explain cycles.")),
                    tools, Optional.empty(), false, Optional.empty(), OptionalInt.of(1024), false, Optional.of(hint));

            var resp = adapter.complete(cachedReq, () -> false);
            assertEquals(4800, resp.usage().inputTokens(), "Anthropic inputTokens normalized to input+cacheRead+cacheCreation (240+3600+960)");
            assertEquals(120, resp.usage().outputTokens());
            assertEquals(3600, resp.usage().cacheReadTokens());
            assertEquals(960, resp.usage().cacheCreationTokens());
            assertTrue(resp.usage().hasData());
            assertEquals(75.0, resp.usage().estimatedCacheSavingsPct(), 0.01);

            // Also verify Anthropic cache hit when cacheCreationTokens == 0 && cacheRead < raw input_tokens has exact denominator
            var partialHit = new LlmProviderAdapter.UsageMetrics(4000, 100, 1000, 0);
            assertEquals(25.0, partialHit.estimatedCacheSavingsPct(), 0.01);

            var sent = GraphJson.object(GraphJson.read(lastBody.get(), GraphJson.Limits.defaults()));
            var sysBlocks = GraphJson.array(sent.get("system"));
            assertEquals(2, sysBlocks.size());
            var block0 = GraphJson.object(sysBlocks.get(0));
            var block1 = GraphJson.object(sysBlocks.get(1));
            assertEquals("text", block0.get("type"));
            assertEquals("Static instructions.", block0.get("text"));
            assertEquals(Map.of("type", "ephemeral"), block0.get("cache_control"));
            assertEquals("Snapshot-pinned brief.", block1.get("text"));
            assertEquals(Map.of("type", "ephemeral"), block1.get("cache_control"));

            var sentTools = GraphJson.array(sent.get("tools"));
            assertEquals(2, sentTools.size());
            assertFalse(GraphJson.object(sentTools.get(0)).containsKey("cache_control"),
                    "Only the last tool should carry cache_control");
            assertEquals(Map.of("type", "ephemeral"), GraphJson.object(sentTools.get(1)).get("cache_control"));

            // Backward compatibility when cacheHint is omitted/disabled
            var legacyReq = new LlmProviderAdapter.ChatRequest(
                    List.of(Map.of("role", "system", "content", "Legacy system."),
                            Map.of("role", "user", "content", "Hi")),
                    tools, Optional.empty(), false, Optional.empty(), OptionalInt.of(512), false);
            adapter.complete(legacyReq, () -> false);
            var legacySent = GraphJson.object(GraphJson.read(lastBody.get(), GraphJson.Limits.defaults()));
            assertEquals("Legacy system.", legacySent.get("system"));
            var legacyTools = GraphJson.array(legacySent.get("tools"));
            assertFalse(GraphJson.object(legacyTools.get(1)).containsKey("cache_control"));

            // Streaming SSE usage accumulation (message_start + message_delta)
            var deltas = new StringBuilder();
            var streamReq = new LlmProviderAdapter.ChatRequest(
                    List.of(Map.of("role", "system", "content", "Static instructions."),
                            Map.of("role", "user", "content", "Stream reply.")),
                    List.of(), Optional.empty(), false, Optional.empty(), OptionalInt.of(512), true, Optional.of(hint));
            var streamResp = adapter.stream(streamReq, () -> false, deltas::append);
            assertEquals("Cached reply [E1].", deltas.toString());
            assertEquals(4380, streamResp.usage().inputTokens(), "Anthropic SSE normalized inputTokens (180+4200+0)");
            assertEquals(95, streamResp.usage().outputTokens());
            assertEquals(4200, streamResp.usage().cacheReadTokens());
            assertEquals(0, streamResp.usage().cacheCreationTokens());
            assertEquals(95.9, streamResp.usage().estimatedCacheSavingsPct(), 0.01);
        } finally {
            mock.stop(0);
        }
    }

    @Test
    void openAiAdapterEmitsBoundedPromptCacheKeySkipsOllamaAndParsesCachedTokens() throws Exception {
        var lastBody = new AtomicReference<String>();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/chat/completions", ex -> {
                try (ex) {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    lastBody.set(body);
                    var req = GraphJson.object(GraphJson.read(body, GraphJson.Limits.defaults()));
                    if (Boolean.TRUE.equals(req.get("stream"))) {
                        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                        ex.sendResponseHeaders(200, 0);
                        String sse = "data: " + CanonicalJson.write(Map.of(
                                "model", "gpt-5-mini",
                                "choices", List.of(Map.of("delta", Map.of("content", "Streamed [E1]."))))) + "\n\n"
                                + "data: " + CanonicalJson.write(Map.of(
                                "model", "gpt-5-mini",
                                "choices", List.of(),
                                "usage", Map.of(
                                        "prompt_tokens", 5400,
                                        "completion_tokens", 310,
                                        "prompt_tokens_details", Map.of("cached_tokens", 4200)))) + "\n\n"
                                + "data: [DONE]\n\n";
                        ex.getResponseBody().write(sse.getBytes(StandardCharsets.UTF_8));
                    } else {
                        byte[] out = CanonicalJson.write(Map.of(
                                "model", "gpt-5-mini",
                                "choices", List.of(Map.of(
                                        "finish_reason", "stop",
                                        "message", Map.of("role", "assistant", "content", "OpenAI answer [E1]."))),
                                "usage", Map.of(
                                        "prompt_tokens", 5400,
                                        "completion_tokens", 280,
                                        "prompt_tokens_details", Map.of("cached_tokens", 4200)))).getBytes(StandardCharsets.UTF_8);
                        ex.getResponseHeaders().add("Content-Type", "application/json");
                        ex.sendResponseHeaders(200, out.length);
                        ex.getResponseBody().write(out);
                    }
                }
            });
            mock.start();

            URI base = URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1");
            String longDigest = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
            var hint = new LlmProviderAdapter.PromptCacheHint(true, true, Optional.of(longDigest));

            var openAi = new OpenAiCompatibleAdapter(
                    LlmProviderAdapter.ProviderKind.OPENAI_COMPAT, base, "gpt-5-mini", "sk-test", false,
                    256 * 1024, 4096, "max_completion_tokens", false, Duration.ofSeconds(10));
            var req = new LlmProviderAdapter.ChatRequest(
                    List.of(Map.of("role", "system", "content", "System"), Map.of("role", "user", "content", "Question")),
                    List.of(), Optional.empty(), false, Optional.empty(), OptionalInt.of(512), false, Optional.of(hint));

            var resp = openAi.complete(req, () -> false);
            assertEquals(5400, resp.usage().inputTokens());
            assertEquals(280, resp.usage().outputTokens());
            assertEquals(4200, resp.usage().cacheReadTokens());
            assertEquals(0, resp.usage().cacheCreationTokens());
            assertEquals(77.8, resp.usage().estimatedCacheSavingsPct(), 0.01);

            var view = resp.usage().toView();
            assertEquals(new BigDecimal("77.8"), view.get("estimatedCacheSavingsPct"));
            assertTrue(CanonicalJson.write(view).contains("\"cacheReadTokens\":4200"));

            var sent = GraphJson.object(GraphJson.read(lastBody.get(), GraphJson.Limits.defaults()));
            String cacheKey = GraphJson.string(sent.get("prompt_cache_key"));
            assertTrue(cacheKey.startsWith("sha256:0123456789abcdef"));
            assertTrue(cacheKey.length() <= 64, "prompt_cache_key must be bounded to <= 64 chars");

            // Streaming final chunk with empty choices still extracts usage and sends stream_options.include_usage=true
            var streamed = new StringBuilder();
            var streamResp = openAi.stream(new LlmProviderAdapter.ChatRequest(
                    req.messages(), List.of(), Optional.empty(), false, Optional.empty(), OptionalInt.of(512), true, Optional.of(hint)),
                    () -> false, streamed::append);
            assertEquals("Streamed [E1].", streamed.toString());
            assertEquals(5400, streamResp.usage().inputTokens());
            assertEquals(310, streamResp.usage().outputTokens());
            assertEquals(4200, streamResp.usage().cacheReadTokens());
            var streamSent = GraphJson.object(GraphJson.read(lastBody.get(), GraphJson.Limits.defaults()));
            assertEquals(Map.of("include_usage", true), streamSent.get("stream_options"));

            // Ollama must NOT emit prompt_cache_key or stream_options
            var ollama = new OpenAiCompatibleAdapter(
                    LlmProviderAdapter.ProviderKind.OLLAMA, base, "qwen2.5-coder:14b", null, false,
                    256 * 1024, 4096, "max_tokens", false, Duration.ofSeconds(10));
            ollama.complete(req, () -> false);
            var ollamaSent = GraphJson.object(GraphJson.read(lastBody.get(), GraphJson.Limits.defaults()));
            assertFalse(ollamaSent.containsKey("prompt_cache_key"), "OLLAMA must omit prompt_cache_key");
            ollama.stream(new LlmProviderAdapter.ChatRequest(
                    req.messages(), List.of(), Optional.empty(), false, Optional.empty(), OptionalInt.of(512), true, Optional.of(hint)),
                    () -> false, chunk -> {});
            var ollamaStreamSent = GraphJson.object(GraphJson.read(lastBody.get(), GraphJson.Limits.defaults()));
            assertFalse(ollamaStreamSent.containsKey("stream_options"), "OLLAMA must omit stream_options");
        } finally {
            mock.stop(0);
        }
    }

    @Test
    void geminiAdapterPreservesPrefixOrderAndParsesUsageMetadataSyncAndStream() throws Exception {
        var lastBody = new AtomicReference<String>();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1beta/models/gemini-2.5-pro:generateContent", ex -> {
                try (ex) {
                    lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] out = CanonicalJson.write(Map.of(
                            "modelVersion", "gemini-2.5-pro",
                            "candidates", List.of(Map.of(
                                    "finishReason", "STOP",
                                    "content", Map.of("role", "model", "parts", List.of(Map.of("text", "Gemini answer [E1]."))))),
                            "usageMetadata", Map.of(
                                    "promptTokenCount", 8000,
                                    "candidatesTokenCount", 450,
                                    "cachedContentTokenCount", 6000))).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                }
            });
            mock.createContext("/v1beta/models/gemini-2.5-pro:streamGenerateContent", ex -> {
                try (ex) {
                    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                    ex.sendResponseHeaders(200, 0);
                    String sse = "data: " + CanonicalJson.write(Map.of(
                            "modelVersion", "gemini-2.5-pro",
                            "candidates", List.of(Map.of(
                                    "finishReason", "STOP",
                                    "content", Map.of("parts", List.of(Map.of("text", "Gemini stream [E1]."))))),
                            "usageMetadata", Map.of(
                                    "promptTokenCount", 9000,
                                    "candidatesTokenCount", 320,
                                    "cachedContentTokenCount", 7200))) + "\n\n";
                    ex.getResponseBody().write(sse.getBytes(StandardCharsets.UTF_8));
                }
            });
            mock.start();

            URI uri = URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1beta");
            var adapter = new GeminiNativeAdapter(uri, "gemini-2.5-pro", "AIzaFakeKey12345678901234", false,
                    256 * 1024, 4096, false, Duration.ofSeconds(10));
            var hint = LlmProviderAdapter.PromptCacheHint.of(true, true, "sha256:gemini-prefix");
            var req = new LlmProviderAdapter.ChatRequest(
                    List.of(
                            Map.of("role", "system", "content", "Static prompt"),
                            Map.of("role", "system", "content", "Snapshot brief"),
                            Map.of("role", "user", "content", "Analyze graph")),
                    List.of(sampleTool("alpha_tool", "Alpha"), sampleTool("zeta_tool", "Zeta")),
                    Optional.empty(), false, Optional.empty(), OptionalInt.of(1024), false, Optional.of(hint));

            var resp = adapter.complete(req, () -> false);
            assertEquals(8000, resp.usage().inputTokens());
            assertEquals(450, resp.usage().outputTokens());
            assertEquals(6000, resp.usage().cacheReadTokens());
            assertEquals(75.0, resp.usage().estimatedCacheSavingsPct(), 0.01);

            var sent = GraphJson.object(GraphJson.read(lastBody.get(), GraphJson.Limits.defaults()));
            var sysParts = GraphJson.array(GraphJson.object(sent.get("systemInstruction")).get("parts"));
            assertEquals("Static prompt", GraphJson.object(sysParts.get(0)).get("text"));
            assertEquals("Snapshot brief", GraphJson.object(sysParts.get(1)).get("text"));
            var decls = GraphJson.array(GraphJson.object(GraphJson.array(sent.get("tools")).getFirst()).get("functionDeclarations"));
            assertEquals("alpha_tool", GraphJson.object(decls.get(0)).get("name"));
            assertEquals("zeta_tool", GraphJson.object(decls.get(1)).get("name"));

            var streamDeltas = new StringBuilder();
            var streamResp = adapter.stream(new LlmProviderAdapter.ChatRequest(
                    req.messages(), req.tools(), Optional.empty(), false, Optional.empty(), OptionalInt.of(1024), true, Optional.of(hint)),
                    () -> false, streamDeltas::append);
            assertEquals("Gemini stream [E1].", streamDeltas.toString());
            assertEquals(9000, streamResp.usage().inputTokens());
            assertEquals(320, streamResp.usage().outputTokens());
            assertEquals(7200, streamResp.usage().cacheReadTokens());
            assertEquals(80.0, streamResp.usage().estimatedCacheSavingsPct(), 0.01);
        } finally {
            mock.stop(0);
        }
    }

    @Test
    void architectureChatModelEnforcesStablePrefixOrderingAndAccumulatesMultiTurnUsage() {
        var fixture = new QueryFixture().node("controller").node("repository").edge("controller", "repository");
        var recordedRequests = new ArrayList<LlmProviderAdapter.ChatRequest>();
        var callCounter = new AtomicInteger();

        var stubAdapter = new LlmProviderAdapter() {
            private final ProviderDescriptor desc = new ProviderDescriptor(
                    ProviderKind.ANTHROPIC, "http://127.0.0.1:1/v1/messages", "claude-sonnet-4-5",
                    false, true, true, true, false, 256 * 1024, 4096, "max_tokens", false);

            @Override public ProviderDescriptor descriptor() { return desc; }

            @Override
            public ChatResponse complete(ChatRequest request, BooleanSupplier cancelled) {
                recordedRequests.add(request);
                int turn = callCounter.incrementAndGet();
                if (turn == 1) {
                    return new ChatResponse(
                            "claude-sonnet-4-5", "tool_calls", "", "",
                            List.of(new ToolCall("tc_1", "alpha_tool", "{}")),
                            "req1".getBytes(StandardCharsets.UTF_8), "res1".getBytes(StandardCharsets.UTF_8),
                            Map.of(), new UsageMetrics(200, 60, 0, 3000));
                }
                if (turn == 2) {
                    return new ChatResponse(
                            "claude-sonnet-4-5", "stop", "Verified architecture summary [E1].", "",
                            List.of(),
                            "req2".getBytes(StandardCharsets.UTF_8), "res2".getBytes(StandardCharsets.UTF_8),
                            Map.of(), new UsageMetrics(150, 140, 3000, 0));
                }
                return new ChatResponse(
                        "claude-sonnet-4-5", "stop", "Final synthesis [E1].", "",
                        List.of(),
                        "req3".getBytes(StandardCharsets.UTF_8), "res3".getBytes(StandardCharsets.UTF_8),
                        Map.of(), new UsageMetrics(100, 90, 3200, 0));
            }

            @Override
            public ChatResponse stream(ChatRequest request, BooleanSupplier cancelled, Consumer<String> onDelta) {
                var resp = complete(request, cancelled);
                if (onDelta != null && !resp.content().isEmpty()) onDelta.accept(resp.content());
                return resp;
            }

            @Override
            public ProbeResult probe(BooleanSupplier cancelled, Duration timeout) {
                return new ProbeResult(true, ProviderKind.ANTHROPIC, desc.endpoint(), desc.model(), true, 1, 200, List.of(), "ok");
            }
        };

        var model = ArchitectureChatModel.fromAdapter(stubAdapter);
        var pin = new ArchitectureChat.Pin(fixture.graph().identity(), fixture.snapshot.value(), fixture.context, Optional.empty());
        var evidence = List.of(new ArchitectureChat.Evidence("E1", ArchitectureChat.Kind.SUMMARY, "Summary", "id1", Map.of("count", 2)));
        var context = new ArchitectureChat.Context(pin, evidence, List.of(), List.of());
        var input = new ArchitectureChat.Input(
                fixture.context, ArchitectureChat.Mode.EXPLORE,
                List.of(new ArchitectureChat.Message("user", "Inspect architecture.")),
                Optional.empty(), Optional.empty(), false, false, false, Optional.empty(), false, true);

        // Start agentic run via buildPacket (sets snapshot-pinned brief & resets RunTelemetry)
        var packet = model.buildPacket(input, context, fixture.graph(), new ArrayList<>(), () -> StopReason.NONE);

        // Pass intentionally UNSORTED tools & interleaved messages to prove prefix normalization
        List<Map<String, Object>> unsortedTools1 = List.of(
                sampleTool("zeta_tool", "Z"),
                sampleTool("alpha_tool", "A"),
                sampleTool("middle_tool", "M"));
        List<Map<String, Object>> unsortedTools2 = List.of(
                sampleTool("middle_tool", "M"),
                sampleTool("zeta_tool", "Z"),
                sampleTool("alpha_tool", "A"));

        var round1Messages = List.<Map<String, Object>>of(
                Map.of("role", "user", "content", "Turn 1 volatile question"),
                Map.of("role", "system", "content", model.instructions(false, true)));
        var round1 = model.sendToolRoundWithAdapter(round1Messages, unsortedTools1, () -> StopReason.NONE);

        var round2Messages = List.<Map<String, Object>>of(
                Map.of("role", "system", "content", model.instructions(false, true)),
                Map.of("role", "user", "content", "Turn 1 volatile question"),
                Map.of("role", "assistant", "content", "Calling alpha_tool"),
                Map.of("role", "tool", "tool_call_id", "tc_1", "content", "{\"ok\":true}"));
        var round2 = model.sendToolRoundWithAdapter(round2Messages, unsortedTools2, () -> StopReason.NONE);

        assertEquals(2, recordedRequests.size());
        var req1 = recordedRequests.get(0);
        var req2 = recordedRequests.get(1);

        // Strict prefix ordering: [Static System Prompt] -> [Snapshot-Pinned Brief] -> [Volatile Turn History]
        assertEquals("system", req1.messages().get(0).get("role"));
        assertEquals(model.instructions(false, true), req1.messages().get(0).get("content"));
        assertEquals("system", req1.messages().get(1).get("role"));
        assertTrue(req1.messages().get(1).get("content").toString().contains("Snapshot-pinned architecture brief"));
        assertEquals("user", req1.messages().get(2).get("role"));

        // Tool schemas strictly sorted lexicographically regardless of input permutation
        assertEquals(List.of("alpha_tool", "middle_tool", "zeta_tool"),
                req1.tools().stream().map(t -> GraphJson.object(t.get("function")).get("name")).toList());
        assertEquals(List.of("alpha_tool", "middle_tool", "zeta_tool"),
                req2.tools().stream().map(t -> GraphJson.object(t.get("function")).get("name")).toList());

        // Stable prefix digest is IDENTICAL across rounds despite different volatile turns & permuted tools!
        var hint1 = req1.cacheHint().orElseThrow();
        var hint2 = req2.cacheHint().orElseThrow();
        assertTrue(hint1.cacheSystemPrefix());
        assertTrue(hint1.cacheTools());
        assertEquals(hint1.stablePrefixDigest(), hint2.stablePrefixDigest(),
                "Multi-turn volatile history and tool input order must preserve identical stablePrefixDigest");

        // Changing snapshot pin MUST change stablePrefixDigest
        var otherPin = new ArchitectureChat.Pin(ContentDigest.sha256Utf8("other-graph"), fixture.snapshot.value(), fixture.context, Optional.empty());
        var otherContext = new ArchitectureChat.Context(otherPin, evidence, List.of(), List.of());
        String otherDigest = ArchitectureChatModel.computeStablePrefixDigest(
                ArchitectureChatModel.enforcePrefixOrder(round1Messages, Optional.of(otherContext.snapshotPinnedBrief())),
                req1.tools(),
                Optional.of(otherContext.pinDigest().value()));
        assertNotEquals(hint1.stablePrefixDigest().orElseThrow(), otherDigest);

        // decodeAdapterResponse accumulates Round 1 + Round 2 WITHOUT double-counting Round 2
        var completion = model.decodeAdapterResponse(context, packet, round2.response(),
                new ArrayList<>(), false, true, List.of(), null);
        var totalUsage = completion.receipt().usage();
        assertEquals(350, totalUsage.inputTokens(), "200 + 150");
        assertEquals(200, totalUsage.outputTokens(), "60 + 140");
        assertEquals(3000, totalUsage.cacheReadTokens(), "0 + 3000");
        assertEquals(3000, totalUsage.cacheCreationTokens(), "3000 + 0");

        // Receipt serializes usage cleanly via CanonicalJson
        String receiptJson = CanonicalJson.write(completion.receipt());
        var parsedReceipt = GraphJson.object(GraphJson.read(receiptJson, GraphJson.Limits.defaults()));
        var usageJson = GraphJson.object(parsedReceipt.get("usage"));
        assertEquals(BigDecimal.valueOf(350), usageJson.get("inputTokens"));
        assertEquals(BigDecimal.valueOf(3000), usageJson.get("cacheReadTokens"));
        assertNotNull(usageJson.get("estimatedCacheSavingsPct"));

        // Also verify completeMessages synthesis path accumulates prior tool rounds + final call
        model.buildPacket(input, context, fixture.graph(), new ArrayList<>(), () -> StopReason.NONE);
        model.sendToolRoundWithAdapter(round1Messages, unsortedTools1, () -> StopReason.NONE); // turn 3 -> in=100, out=90, cacheRead=3200
        var synth = model.completeMessages(context, round2Messages, List.of(), new ArrayList<>(), false, true, () -> StopReason.NONE, null); // turn 4 -> in=100, out=90, cacheRead=3200
        assertEquals(200, synth.receipt().usage().inputTokens());
        assertEquals(180, synth.receipt().usage().outputTokens());
        assertEquals(6400, synth.receipt().usage().cacheReadTokens());

        // Backward-compatible 6-arg Receipt constructor omits empty usage field in CanonicalJson
        var legacyReceipt = new ArchitectureChatModel.Receipt(
                "m1", "m1", ContentDigest.sha256Utf8("p"), ContentDigest.sha256Utf8("req"),
                ContentDigest.sha256Utf8("res"), List.of());
        assertFalse(legacyReceipt.usage().hasData());
        assertFalse(GraphJson.object(GraphJson.read(CanonicalJson.write(legacyReceipt), GraphJson.Limits.defaults())).containsKey("usage"));
        assertEquals(200, round1.usage().inputTokens());
    }

    private static Map<String, Object> sampleTool(String name, String description) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", Map.of("type", "object", "properties", Map.of())));
    }
}
