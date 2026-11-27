package com.evolution.backend.query;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.query.llm.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class LlmProviderAdapterTest {

    @Test
    void openAiAdapterHandlesReasoningModelsRetryAfterEmptyToolCallsThinkingAndOpenRouterHeaders() throws Exception {
        var calls = new AtomicInteger();
        var lastBody = new AtomicReference<String>();
        var lastReferer = new AtomicReference<String>();
        var lastTitle = new AtomicReference<String>();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/chat/completions", ex -> {
                try (ex) {
                    int c = calls.incrementAndGet();
                    lastReferer.set(ex.getRequestHeaders().getFirst("HTTP-Referer"));
                    lastTitle.set(ex.getRequestHeaders().getFirst("X-Title"));
                    lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    if (c == 1) {
                        ex.getResponseHeaders().add("Retry-After", "1");
                        byte[] err = "{\"error\":{\"message\":\"rate limited sk-secret1234567890123\"}}".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(429, err.length);
                        ex.getResponseBody().write(err);
                        return;
                    }
                    byte[] ok = CanonicalJson.write(Map.of(
                            "model", "o3-mini-2026-01-31",
                            "choices", List.of(Map.of(
                                    "finish_reason", "STOP",
                                    "message", Map.of(
                                            "role", "assistant",
                                            "content", "<think>internal chain</think>\nFinal answer [E1].",
                                            "tool_calls", List.of()))))).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, ok.length);
                    ex.getResponseBody().write(ok);
                }
            });
            mock.createContext("/api/tags", ex -> {
                try (ex) {
                    byte[] ok = CanonicalJson.write(Map.of("models", List.of(
                            Map.of("name", "qwen2.5-coder:14b"),
                            Map.of("name", "llama3.1:8b")))).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, ok.length);
                    ex.getResponseBody().write(ok);
                }
            });
            mock.createContext("/v1/models", ex -> {
                try (ex) {
                    ex.sendResponseHeaders(404, -1);
                }
            });
            mock.start();

            URI base = URI.create("http://localhost:" + mock.getAddress().getPort() + "/v1");
            assertTrue(LlmTransport.isLoopback(base), "localhost must be accepted as loopback");

            var adapter = new OpenAiCompatibleAdapter(
                    LlmProviderAdapter.ProviderKind.OPENROUTER,
                    base, "o3-mini", "sk-test-token", false,
                    256 * 1024, 4096, "max_tokens", false, Duration.ofSeconds(15));
            var req = new LlmProviderAdapter.ChatRequest(
                    List.of(Map.of("role", "user", "content", "Analyze architecture.")),
                    List.of(), Optional.empty(), false, Optional.of(0.7), OptionalInt.of(1024), false);

            var resp = adapter.complete(req, () -> false);
            assertEquals(2, calls.get(), "429 Retry-After should retry once and succeed");
            assertEquals("o3-mini-2026-01-31", resp.returnedModel());
            assertEquals("Final answer [E1].", resp.content());
            assertEquals("internal chain", resp.reasoningContent());
            assertTrue(resp.toolCalls().isEmpty());
            assertNotNull(lastReferer.get());
            assertEquals("Evo Architecture Platform", lastTitle.get());

            // Reasoning model must auto-switch max_tokens -> max_completion_tokens and omit temperature
            var sent = GraphJson.object(GraphJson.read(lastBody.get(), GraphJson.Limits.defaults()));
            assertTrue(sent.containsKey("max_completion_tokens"));
            assertFalse(sent.containsKey("max_tokens"));
            assertFalse(sent.containsKey("temperature"));

            // Ollama probe falls back from 404 /v1/models -> /api/tags
            var ollama = new OpenAiCompatibleAdapter(
                    LlmProviderAdapter.ProviderKind.OLLAMA,
                    base, "qwen2.5-coder:14b", null, false,
                    256 * 1024, 4096, "max_tokens", false, Duration.ofSeconds(10));
            var probe = ollama.probe(() -> false, Duration.ofSeconds(5));
            assertTrue(probe.reachable());
            assertTrue(probe.configuredModelFound());
            assertEquals(2, probe.models().size());
        } finally {
            mock.stop(0);
        }
    }

    @Test
    void anthropicAdapterSupportsNativeMessagesToolsSseStreamingAndProbe() throws Exception {
        var apiKeyHeader = new AtomicReference<String>();
        var versionHeader = new AtomicReference<String>();
        var lastReq = new AtomicReference<String>();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1/messages", ex -> {
                try (ex) {
                    apiKeyHeader.set(ex.getRequestHeaders().getFirst("x-api-key"));
                    versionHeader.set(ex.getRequestHeaders().getFirst("anthropic-version"));
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    lastReq.set(body);
                    var reqObj = GraphJson.object(GraphJson.read(body, GraphJson.Limits.defaults()));
                    if (Boolean.TRUE.equals(reqObj.get("stream"))) {
                        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                        ex.sendResponseHeaders(200, 0);
                        String sse = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-sonnet-4-5\"}}\n\n"
                                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"plan\"}}\n\n"
                                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}\n\n"
                                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Claude [E1].\"}}\n\n"
                                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n";
                        ex.getResponseBody().write(sse.getBytes(StandardCharsets.UTF_8));
                    } else {
                        byte[] out = CanonicalJson.write(Map.of(
                                "model", "claude-sonnet-4-5",
                                "stop_reason", "tool_use",
                                "content", List.of(
                                        Map.of("type", "text", "text", "Checking graph."),
                                        Map.of("type", "tool_use", "id", "toolu_1", "name", "get_repo_map",
                                                "input", Map.of("maxFiles", 10))))).getBytes(StandardCharsets.UTF_8);
                        ex.getResponseHeaders().add("Content-Type", "application/json");
                        ex.sendResponseHeaders(200, out.length);
                        ex.getResponseBody().write(out);
                    }
                }
            });
            mock.createContext("/v1/models", ex -> {
                try (ex) {
                    byte[] out = CanonicalJson.write(Map.of("data", List.of(
                            Map.of("id", "claude-sonnet-4-5", "display_name", "Claude Sonnet 4.5")))).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                }
            });
            mock.start();

            URI uri = URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1/messages");
            var adapter = new AnthropicMessagesAdapter(uri, "claude-sonnet-4-5", "ant-secret-key", false,
                    256 * 1024, 4096, false, Duration.ofSeconds(10));

            var toolReq = new LlmProviderAdapter.ChatRequest(
                    List.of(Map.of("role", "system", "content", "System rule."),
                            Map.of("role", "user", "content", "Map the repo.")),
                    ArchitectureAgent.toolSchemas(false), Optional.empty(), false,
                    Optional.empty(), OptionalInt.of(1024), false);
            var toolResp = adapter.complete(toolReq, () -> false);
            assertEquals("ant-secret-key", apiKeyHeader.get());
            assertEquals("2023-06-01", versionHeader.get());
            assertEquals(1, toolResp.toolCalls().size());
            assertEquals("get_repo_map", toolResp.toolCalls().getFirst().name());
            var sentObj = GraphJson.object(GraphJson.read(lastReq.get(), GraphJson.Limits.defaults()));
            assertEquals("System rule.", sentObj.get("system"));

            var streamed = new StringBuilder();
            var streamReq = new LlmProviderAdapter.ChatRequest(
                    List.of(Map.of("role", "user", "content", "Say hi.")),
                    List.of(), Optional.empty(), false, Optional.empty(), OptionalInt.of(1024), true);
            var streamResp = adapter.stream(streamReq, () -> false, streamed::append);
            assertEquals("Hello Claude [E1].", streamed.toString());
            assertEquals("Hello Claude [E1].", streamResp.content());
            assertEquals("plan", streamResp.reasoningContent());
            assertEquals("stop", streamResp.finishReason());

            var probe = adapter.probe(() -> false, Duration.ofSeconds(5));
            assertTrue(probe.reachable());
            assertTrue(probe.configuredModelFound());
        } finally {
            mock.stop(0);
        }
    }

    @Test
    void geminiAdapterSupportsNativeGenerateStreamThoughtFilteringSchemaAndProbe() throws Exception {
        var apiKeyHeader = new AtomicReference<String>();
        var lastReq = new AtomicReference<String>();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            mock.createContext("/v1beta/models/gemini-2.5-pro:generateContent", ex -> {
                try (ex) {
                    apiKeyHeader.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
                    lastReq.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] out = CanonicalJson.write(Map.of(
                            "modelVersion", "gemini-2.5-pro",
                            "candidates", List.of(Map.of(
                                    "finishReason", "STOP",
                                    "content", Map.of("role", "model", "parts", List.of(
                                            Map.of("thought", true, "text", "hidden thought"),
                                            Map.of("text", "{\"edits\":[]}"))))))).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                }
            });
            mock.createContext("/v1beta/models/gemini-2.5-pro:streamGenerateContent", ex -> {
                try (ex) {
                    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                    ex.sendResponseHeaders(200, 0);
                    String sse = "data: " + CanonicalJson.write(Map.of("candidates", List.of(Map.of(
                            "content", Map.of("parts", List.of(
                                    Map.of("thought", true, "text", "thinking"),
                                    Map.of("text", "Part1 "))))))) + "\n\n"
                            + "data: " + CanonicalJson.write(Map.of("candidates", List.of(Map.of(
                            "finishReason", "STOP",
                            "content", Map.of("parts", List.of(Map.of("text", "Part2 [E1]"))))))) + "\n\n";
                    ex.getResponseBody().write(sse.getBytes(StandardCharsets.UTF_8));
                }
            });
            mock.createContext("/v1beta/models", ex -> {
                try (ex) {
                    byte[] out = CanonicalJson.write(Map.of("models", List.of(
                            Map.of("name", "models/gemini-2.5-pro", "displayName", "Gemini 2.5 Pro")))).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                }
            });
            mock.start();

            URI uri = URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/v1beta");
            var adapter = new GeminiNativeAdapter(uri, "gemini-2.5-pro", "AIzaFakeKey12345678901234", false,
                    256 * 1024, 4096, true, Duration.ofSeconds(10));
            var schema = Map.<String, Object>of("type", "object",
                    "properties", Map.of("edits", Map.of("type", "array")),
                    "additionalProperties", false);
            var req = new LlmProviderAdapter.ChatRequest(
                    List.of(Map.of("role", "system", "content", "Return JSON"),
                            Map.of("role", "user", "content", "Fix bug")),
                    List.of(), Optional.of(schema), true, Optional.empty(), OptionalInt.of(512), false);
            var resp = adapter.complete(req, () -> false);
            assertEquals("AIzaFakeKey12345678901234", apiKeyHeader.get());
            assertEquals("{\"edits\":[]}", resp.content());
            assertEquals("hidden thought", resp.reasoningContent());
            assertFalse(lastReq.get().contains("additionalProperties"), "Gemini schema must strip additionalProperties");

            var deltas = new StringBuilder();
            var streamResp = adapter.stream(
                    new LlmProviderAdapter.ChatRequest(List.of(Map.of("role", "user", "content", "Hi")),
                            List.of(), Optional.empty(), false, Optional.empty(), OptionalInt.of(512), true),
                    () -> false, deltas::append);
            assertEquals("Part1 Part2 [E1]", deltas.toString());
            assertEquals("Part1 Part2 [E1]", streamResp.content());

            var probe = adapter.probe(() -> false, Duration.ofSeconds(5));
            assertTrue(probe.reachable());
            assertTrue(probe.configuredModelFound());
        } finally {
            mock.stop(0);
        }
    }

    @Test
    void configRegistryResolvesJvmPropertiesBeforeEnvAndNeverExposesKeys() {
        var props = Map.of(
                "evo.arch.chat.endpoint", "http://127.0.0.1:11434/v1",
                "evo.arch.chat.model", "qwen2.5-coder:14b",
                "evo.arch.chat.provider", "ollama",
                "evo.arch.chat.apiKey", "sk-super-secret-value-999999");
        var env = Map.of(
                "EVO_ARCH_CHAT_ENDPOINT", "http://127.0.0.1:9999/v1",
                "EVO_ARCH_CHAT_MODEL", "fallback-model",
                "EVO_REPAIR_AI_ENDPOINT", "https://api.anthropic.com/v1/messages",
                "EVO_REPAIR_AI_MODEL", "claude-sonnet-4-5",
                "EVO_REPAIR_AI_ALLOW_REMOTE", "false");
        var registry = LlmConfigRegistry.fromLookups(props::get, env::get);
        assertTrue(registry.adapter(LlmConfigRegistry.Role.CHAT).isPresent());
        assertEquals(LlmProviderAdapter.ProviderKind.OLLAMA,
                registry.adapter(LlmConfigRegistry.Role.CHAT).orElseThrow().descriptor().kind());
        assertTrue(registry.adapter(LlmConfigRegistry.Role.REPAIR).isEmpty());
        assertEquals(Optional.of("REMOTE_DISABLED_BY_POLICY"),
                registry.state(LlmConfigRegistry.Role.REPAIR).disabledReason());
        String described = CanonicalJson.write(registry.describeProviders());
        assertFalse(described.contains("sk-super-secret"), "API keys must never appear in describeProviders");
    }
}
