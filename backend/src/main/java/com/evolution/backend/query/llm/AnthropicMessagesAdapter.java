package com.evolution.backend.query.llm;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.query.BoundedJson;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Native Anthropic Messages API adapter ({@code /v1/messages}, {@code x-api-key}, {@code anthropic-version}).
 * Translates OpenAI-style messages and tool schemas cleanly into Anthropic wire format and back.
 */
public final class AnthropicMessagesAdapter implements LlmProviderAdapter {
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final GraphJson.Limits JSON = new GraphJson.Limits(LlmTransport.MAX_RESPONSE_BYTES, 32, 32_768);

    private final URI messagesEndpoint;
    private final URI modelsEndpoint;
    private final String model;
    private final String apiKey;
    private final int requestBytes;
    private final int maxOutputTokens;
    private final boolean jsonMode;
    private final Duration timeout;
    private final ProviderDescriptor descriptor;

    public AnthropicMessagesAdapter(URI endpoint, String model, String apiKey, boolean allowRemote,
                                    int requestBytes, int maxOutputTokens, boolean jsonMode, Duration timeout) {
        LlmTransport.validateEndpoint(endpoint, model, apiKey, allowRemote, "LLM_ANTHROPIC_CONFIGURATION");
        String path = endpoint.getRawPath() == null ? "" : endpoint.getRawPath().replaceAll("/+$", "");
        String basePath = path.endsWith("/messages")
                ? path.substring(0, path.length() - "/messages".length())
                : (path.isEmpty() ? "/v1" : path);
        String msgPath = path.endsWith("/messages") ? path : (basePath + "/messages");
        String origin = endpoint.getScheme().toLowerCase(Locale.ROOT) + "://" + endpoint.getRawAuthority();
        this.messagesEndpoint = URI.create(origin + msgPath);
        this.modelsEndpoint = URI.create(origin + basePath + "/models");
        this.model = model;
        this.apiKey = apiKey;
        this.requestBytes = requestBytes;
        this.maxOutputTokens = maxOutputTokens;
        this.jsonMode = jsonMode;
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        this.descriptor = new ProviderDescriptor(
                ProviderKind.ANTHROPIC,
                this.messagesEndpoint.toASCIIString(),
                model,
                !LlmTransport.isLoopback(this.messagesEndpoint),
                true,
                true,
                true,
                LlmTransport.isReasoningModel(model),
                requestBytes,
                maxOutputTokens,
                "max_tokens",
                jsonMode);
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ChatResponse complete(ChatRequest request, BooleanSupplier cancelled) {
        byte[] reqBytes = encodeRequest(request, false);
        var res = LlmTransport.sendJsonWithRetry(messagesEndpoint, headers(false), reqBytes, timeout, cancelled);
        return decodeSync(reqBytes, res.body());
    }

    @Override
    public ChatResponse stream(ChatRequest request, BooleanSupplier cancelled, Consumer<String> onDelta) {
        byte[] reqBytes = encodeRequest(request, true);
        var accumulated = new StringBuilder();
        var reasoning = new StringBuilder();
        var filter = new LlmTransport.StreamDisplayFilter(onDelta);
        String[] returnedModel = {model};
        String[] finishReason = {"stop"};
        int[] usageTokens = new int[4]; // input, output, cacheRead, cacheCreation
        var toolCalls = new ArrayList<ToolCall>();
        var activeToolId = new String[1];
        var activeToolName = new String[1];
        var activeToolInput = new StringBuilder();

        var outcome = LlmTransport.sendStreamWithRetry(messagesEndpoint, headers(true), reqBytes, timeout, cancelled, line -> {
            if (!line.startsWith("data:")) return;
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) return;
            Map<String, Object> ev;
            try {
                ev = GraphJson.object(GraphJson.read(payload, JSON));
            } catch (IllegalArgumentException invalid) {
                throw new LlmException(ErrorCode.INVALID_MODEL_OUTPUT, 502, "Malformed Anthropic SSE JSON chunk");
            }
            String type = ev.get("type") instanceof String t ? t : "";
            switch (type) {
                case "message_start" -> {
                    if (ev.get("message") instanceof Map<?, ?> msg) {
                        if (msg.get("model") instanceof String m && !m.isBlank()) {
                            returnedModel[0] = m;
                        }
                        if (msg.get("usage") instanceof Map<?, ?> usageMap) {
                            mergeAnthropicUsage(usageTokens, usageMap);
                        }
                    }
                }
                case "content_block_start" -> {
                    if (ev.get("content_block") instanceof Map<?, ?> block && "tool_use".equals(block.get("type"))) {
                        activeToolId[0] = block.get("id") instanceof String id ? id : ("tool_" + (toolCalls.size() + 1));
                        activeToolName[0] = block.get("name") instanceof String n ? n : "";
                        activeToolInput.setLength(0);
                    }
                }
                case "content_block_delta" -> {
                    if (ev.get("delta") instanceof Map<?, ?> delta) {
                        String dt = delta.get("type") instanceof String s ? s : "";
                        if ("text_delta".equals(dt) && delta.get("text") instanceof String text && !text.isEmpty()) {
                            accumulated.append(text);
                            filter.accept(text);
                        } else if ("thinking_delta".equals(dt) && delta.get("thinking") instanceof String th) {
                            reasoning.append(th);
                        } else if ("input_json_delta".equals(dt) && delta.get("partial_json") instanceof String pj) {
                            activeToolInput.append(pj);
                        }
                    }
                }
                case "content_block_stop" -> {
                    if (activeToolName[0] != null && !activeToolName[0].isBlank()) {
                        String args = activeToolInput.isEmpty() ? "{}" : activeToolInput.toString();
                        toolCalls.add(new ToolCall(activeToolId[0], activeToolName[0], args));
                        activeToolId[0] = null;
                        activeToolName[0] = null;
                        activeToolInput.setLength(0);
                    }
                }
                case "message_delta" -> {
                    if (ev.get("delta") instanceof Map<?, ?> delta && delta.get("stop_reason") instanceof String sr) {
                        finishReason[0] = mapStopReason(sr);
                    }
                    if (ev.get("usage") instanceof Map<?, ?> usageMap) {
                        mergeAnthropicUsage(usageTokens, usageMap);
                    }
                }
                default -> {}
            }
        });
        filter.finish();
        if (!outcome.contentType().contains("text/event-stream")) {
            var decoded = decodeSync(reqBytes, outcome.rawBytes());
            if (onDelta != null && !decoded.content().isEmpty()) onDelta.accept(decoded.content());
            return decoded;
        }
        var usage = toNormalizedAnthropicUsage(usageTokens);
        return new ChatResponse(returnedModel[0], finishReason[0], accumulated.toString(), reasoning.toString(),
                toolCalls, reqBytes, outcome.rawBytes(), Map.of(), usage);
    }

    @Override
    public ProbeResult probe(BooleanSupplier cancelled, Duration probeTimeout) {
        long startNs = System.nanoTime();
        Duration eff = probeTimeout == null ? Duration.ofSeconds(10) : probeTimeout;
        var models = new ArrayList<ModelInfo>();
        int status = 0;
        String message;
        boolean reachable = false;
        try {
            var res = LlmTransport.sendGet(modelsEndpoint, headers(false), eff, cancelled);
            status = res.statusCode();
            if (status == 200) {
                reachable = true;
                var root = GraphJson.object(GraphJson.read(new String(res.body(), StandardCharsets.UTF_8), JSON));
                if (root.get("data") instanceof List<?> data) {
                    for (Object item : data) {
                        if (!(item instanceof Map<?, ?> m)) continue;
                        if (m.get("id") instanceof String id && !id.isBlank()) {
                            String display = m.get("display_name") instanceof String d ? d : null;
                            models.add(new ModelInfo(id, "anthropic", Optional.ofNullable(display)));
                        }
                    }
                }
                message = "Connected to Anthropic (" + models.size() + " models listed)";
            } else {
                message = LlmTransport.sanitizeErrorDetail(status, res.body());
            }
        } catch (LlmException ex) {
            status = ex.httpStatus();
            message = ex.detail().isBlank() ? ex.code().name() : ex.detail();
        }
        long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
        boolean found = models.stream().anyMatch(m -> LlmTransport.compatibleModel(model, m.id(), descriptor.remote()));
        return new ProbeResult(reachable, ProviderKind.ANTHROPIC, messagesEndpoint.toASCIIString(), model,
                found, elapsedMs, status, models, message);
    }

    private byte[] encodeRequest(ChatRequest request, boolean stream) {
        var systemParts = new ArrayList<String>();
        var translated = new ArrayList<Map<String, Object>>();
        var pendingToolResults = new ArrayList<Map<String, Object>>();

        for (var msg : request.messages()) {
            String role = Objects.toString(msg.get("role"), "");
            if ("system".equals(role)) {
                if (msg.get("content") instanceof String s && !s.isBlank()) systemParts.add(s);
                continue;
            }
            if ("tool".equals(role)) {
                String toolUseId = Objects.toString(msg.get("tool_call_id"), "tool_1");
                String content = Objects.toString(msg.get("content"), "");
                pendingToolResults.add(Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", content));
                continue;
            }
            if (!pendingToolResults.isEmpty()) {
                appendAnthropicUserBlocks(translated, List.copyOf(pendingToolResults));
                pendingToolResults.clear();
            }
            if ("assistant".equals(role) && msg.get("tool_calls") instanceof List<?> toolCalls && !toolCalls.isEmpty()) {
                var blocks = new ArrayList<Map<String, Object>>();
                if (msg.get("content") instanceof String s && !s.isBlank()) {
                    blocks.add(Map.of("type", "text", "text", s));
                }
                for (Object tc : toolCalls) {
                    var tcObj = GraphJson.object(tc);
                    var fn = GraphJson.object(tcObj.get("function"));
                    String id = Objects.toString(tcObj.get("id"), "call_1");
                    String name = Objects.toString(fn.get("name"), "");
                    Object rawArgs = fn.get("arguments");
                    Map<String, Object> inputMap = Map.of();
                    if (rawArgs instanceof Map<?, ?>) {
                        inputMap = GraphJson.object(rawArgs);
                    } else if (rawArgs instanceof String s && !s.isBlank()) {
                        try { inputMap = GraphJson.object(GraphJson.read(s, JSON)); }
                        catch (RuntimeException ignored) {}
                    }
                    blocks.add(Map.of("type", "tool_use", "id", id, "name", name, "input", inputMap));
                }
                translated.add(Map.of("role", "assistant", "content", blocks));
            } else {
                String content = Objects.toString(msg.get("content"), "");
                if ("assistant".equals(role)) {
                    translated.add(Map.of("role", "assistant", "content", content));
                } else {
                    appendAnthropicUserText(translated, content);
                }
            }
        }
        if (!pendingToolResults.isEmpty()) {
            appendAnthropicUserBlocks(translated, List.copyOf(pendingToolResults));
        }

        var hint = request.cacheHint().orElse(PromptCacheHint.disabled());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", model);
        payload.put("max_tokens", request.maxOutputTokens().orElse(maxOutputTokens));
        payload.put("stream", stream);
        if (!systemParts.isEmpty()) {
            if (hint.cacheSystemPrefix()) {
                var systemBlocks = new ArrayList<Map<String, Object>>(systemParts.size());
                for (int i = 0; i < systemParts.size(); i++) {
                    var block = new LinkedHashMap<String, Object>();
                    block.put("type", "text");
                    block.put("text", systemParts.get(i));
                    if (i == systemParts.size() - 1 || (systemParts.size() > 1 && i == 0)) {
                        block.put("cache_control", Map.of("type", "ephemeral"));
                    }
                    systemBlocks.add(Collections.unmodifiableMap(block));
                }
                payload.put("system", List.copyOf(systemBlocks));
            } else {
                payload.put("system", String.join("\n\n", systemParts));
            }
        }
        payload.put("messages", translated);
        if (request.temperature().isPresent() && !descriptor.reasoningModel()) {
            payload.put("temperature", java.math.BigDecimal.valueOf(request.temperature().orElseThrow()));
        }
        if (!request.tools().isEmpty()) {
            var anthropicTools = new ArrayList<Map<String, Object>>(request.tools().size());
            for (int i = 0; i < request.tools().size(); i++) {
                var tool = request.tools().get(i);
                var fn = GraphJson.object(tool.get("function"));
                var entry = new LinkedHashMap<String, Object>();
                entry.put("name", Objects.toString(fn.get("name"), ""));
                entry.put("description", Objects.toString(fn.get("description"), ""));
                entry.put("input_schema", fn.getOrDefault("parameters", Map.of("type", "object", "properties", Map.of())));
                if (hint.cacheTools() && i == request.tools().size() - 1) {
                    entry.put("cache_control", Map.of("type", "ephemeral"));
                }
                anthropicTools.add(Collections.unmodifiableMap(entry));
            }
            payload.put("tools", List.copyOf(anthropicTools));
        }
        try {
            return BoundedJson.encode(payload, requestBytes);
        } catch (BoundedJson.TooLarge large) {
            throw new LlmException(ErrorCode.RESOURCE_LIMIT, 0, "Anthropic request exceeded byte budget");
        }
    }

    private ChatResponse decodeSync(byte[] reqBytes, byte[] responseBytes) {
        try {
            String wire = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(responseBytes)).toString();
            var root = GraphJson.object(GraphJson.read(wire, JSON));
            String returned = root.get("model") instanceof String m && !m.isBlank() ? m : model;
            String stopReason = root.get("stop_reason") instanceof String s ? mapStopReason(s) : "stop";
            var text = new StringBuilder();
            var reasoning = new StringBuilder();
            var calls = new ArrayList<ToolCall>();
            if (root.get("content") instanceof List<?> blocks) {
                for (Object raw : blocks) {
                    var block = GraphJson.object(raw);
                    String type = Objects.toString(block.get("type"), "");
                    if ("text".equals(type) && block.get("text") instanceof String s) {
                        text.append(s);
                    } else if ("thinking".equals(type) && block.get("thinking") instanceof String th) {
                        reasoning.append(th);
                    } else if ("tool_use".equals(type)) {
                        String id = Objects.toString(block.get("id"), "tool_" + (calls.size() + 1));
                        String name = Objects.toString(block.get("name"), "");
                        String argsJson = block.get("input") == null ? "{}" : CanonicalJson.write(block.get("input"));
                        if (!name.isBlank()) calls.add(new ToolCall(id, name, argsJson));
                    }
                }
            }
            UsageMetrics usage = parseAnthropicUsage(root.get("usage"));
            // Synthesize OpenAI-compatible rawObject for callers expecting choices[0].message
            var openAiCompat = toOpenAiEnvelope(returned, stopReason, text.toString(), calls);
            return new ChatResponse(returned, stopReason, text.toString(), reasoning.toString(),
                    calls, reqBytes, responseBytes, openAiCompat, usage);
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new LlmException(ErrorCode.INVALID_MODEL_OUTPUT, 200, invalid.getMessage());
        }
    }

    private static UsageMetrics parseAnthropicUsage(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return UsageMetrics.empty();
        int[] acc = new int[4];
        mergeAnthropicUsage(acc, map);
        return toNormalizedAnthropicUsage(acc);
    }

    /**
     * Normalizes Anthropic usage so {@code inputTokens} equals total prompt input tokens
     * ({@code input_tokens + cache_read_input_tokens + cache_creation_input_tokens}),
     * matching OpenAI {@code prompt_tokens} and Gemini {@code promptTokenCount}.
     */
    private static UsageMetrics toNormalizedAnthropicUsage(int[] acc) {
        long totalInput = (long) acc[0] + acc[2] + acc[3];
        int normalizedInput = totalInput >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, totalInput);
        return new UsageMetrics(normalizedInput, acc[1], acc[2], acc[3]);
    }

    private static void mergeAnthropicUsage(int[] acc, Map<?, ?> usageMap) {
        int input = intField(usageMap, "input_tokens");
        int output = intField(usageMap, "output_tokens");
        int cacheRead = intField(usageMap, "cache_read_input_tokens");
        int cacheCreation = intField(usageMap, "cache_creation_input_tokens");
        if (input > 0) acc[0] = input;
        if (output > 0) acc[1] = output;
        if (cacheRead > 0) acc[2] = cacheRead;
        if (cacheCreation > 0) acc[3] = cacheCreation;
    }

    private static int intField(Map<?, ?> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return Math.max(0, n.intValue());
        if (val instanceof String s) {
            try { return Math.max(0, Integer.parseInt(s.strip())); }
            catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    static Map<String, Object> toOpenAiEnvelope(String model, String finishReason, String content, List<ToolCall> calls) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("role", "assistant");
        msg.put("content", content);
        if (!calls.isEmpty()) {
            msg.put("tool_calls", calls.stream().map(c -> Map.<String, Object>of(
                    "id", c.id(),
                    "type", "function",
                    "function", Map.of("name", c.name(), "arguments", c.argumentsJson()))).toList());
        }
        return Map.of("model", model, "choices", List.of(Map.of("finish_reason", finishReason, "message", msg)));
    }

    private static String mapStopReason(String anthropicStop) {
        return switch (anthropicStop) {
            case "end_turn", "stop_sequence" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> anthropicStop;
        };
    }

    private static void appendAnthropicUserBlocks(List<Map<String, Object>> translated, List<Map<String, Object>> blocks) {
        if (blocks.isEmpty()) return;
        if (!translated.isEmpty() && "user".equals(translated.getLast().get("role"))) {
            var merged = new ArrayList<Map<String, Object>>();
            Object prev = translated.getLast().get("content");
            if (prev instanceof List<?> prevList) {
                for (Object item : prevList) {
                    if (item instanceof Map<?, ?>) merged.add(GraphJson.object(item));
                }
            } else if (prev instanceof String s && !s.isBlank()) {
                merged.add(Map.of("type", "text", "text", s));
            }
            merged.addAll(blocks);
            translated.set(translated.size() - 1, Map.of("role", "user", "content", List.copyOf(merged)));
        } else {
            translated.add(Map.of("role", "user", "content", List.copyOf(blocks)));
        }
    }

    private static void appendAnthropicUserText(List<Map<String, Object>> translated, String content) {
        if (!translated.isEmpty() && "user".equals(translated.getLast().get("role"))) {
            Object prev = translated.getLast().get("content");
            if (prev instanceof List<?> prevList) {
                var merged = new ArrayList<Map<String, Object>>();
                for (Object item : prevList) {
                    if (item instanceof Map<?, ?>) merged.add(GraphJson.object(item));
                }
                if (content != null && !content.isEmpty()) {
                    merged.add(Map.of("type", "text", "text", content));
                }
                translated.set(translated.size() - 1, Map.of("role", "user", "content", List.copyOf(merged)));
            } else {
                String prevStr = prev == null ? "" : prev.toString();
                String combined = prevStr.isBlank() ? (content == null ? "" : content)
                        : (content == null || content.isBlank() ? prevStr : prevStr + "\n\n" + content);
                translated.set(translated.size() - 1, Map.of("role", "user", "content", combined));
            }
        } else {
            translated.add(Map.of("role", "user", "content", content == null ? "" : content));
        }
    }

    private Map<String, String> headers(boolean sse) {
        var h = new LinkedHashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("Accept", sse ? "text/event-stream, application/json" : "application/json");
        h.put("anthropic-version", ANTHROPIC_VERSION);
        if (apiKey != null && !apiKey.isEmpty()) {
            h.put("x-api-key", apiKey);
        }
        return h;
    }
}
