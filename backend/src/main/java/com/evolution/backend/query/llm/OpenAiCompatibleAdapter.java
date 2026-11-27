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
 * OpenAI Chat Completions adapter covering OpenAI, OpenRouter, Ollama, LM Studio, vLLM, and DeepSeek.
 */
public final class OpenAiCompatibleAdapter implements LlmProviderAdapter {
    private static final GraphJson.Limits JSON = new GraphJson.Limits(LlmTransport.MAX_RESPONSE_BYTES, 32, 32_768);

    private final ProviderKind kind;
    private final URI chatEndpoint;
    private final URI baseUri;
    private final String model;
    private final String apiKey;
    private final int requestBytes;
    private final int maxOutputTokens;
    private final String tokenParameter;
    private final boolean jsonMode;
    private final Duration timeout;
    private final ProviderDescriptor descriptor;

    public OpenAiCompatibleAdapter(ProviderKind kind, URI endpoint, String model, String apiKey, boolean allowRemote,
                                   int requestBytes, int maxOutputTokens, String tokenParameter, boolean jsonMode,
                                   Duration timeout) {
        LlmTransport.validateEndpoint(endpoint, model, apiKey, allowRemote, "LLM_OPENAI_CONFIGURATION");
        this.kind = kind == null ? ProviderKind.infer(endpoint) : kind;
        String path = endpoint.getRawPath() == null ? "" : endpoint.getRawPath().replaceAll("/+$", "");
        String basePath = path.endsWith("/chat/completions")
                ? path.substring(0, path.length() - "/chat/completions".length())
                : path;
        String chatPath = path.endsWith("/chat/completions") ? path : (path + "/chat/completions");
        String origin = endpoint.getScheme().toLowerCase(Locale.ROOT) + "://" + endpoint.getRawAuthority();
        this.baseUri = URI.create(origin + basePath);
        this.chatEndpoint = URI.create(origin + chatPath);
        this.model = model;
        this.apiKey = apiKey;
        this.requestBytes = requestBytes;
        this.maxOutputTokens = maxOutputTokens;
        boolean reasoning = LlmTransport.isReasoningModel(model);
        this.tokenParameter = reasoning ? "max_completion_tokens"
                : (tokenParameter == null || tokenParameter.isBlank() ? "max_completion_tokens" : tokenParameter);
        this.jsonMode = jsonMode;
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        this.descriptor = new ProviderDescriptor(
                this.kind,
                this.chatEndpoint.toASCIIString(),
                model,
                !LlmTransport.isLoopback(this.chatEndpoint),
                true,
                true,
                true,
                reasoning,
                requestBytes,
                maxOutputTokens,
                this.tokenParameter,
                jsonMode);
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ChatResponse complete(ChatRequest request, BooleanSupplier cancelled) {
        byte[] reqBytes = encodeRequest(request, false);
        var res = LlmTransport.sendJsonWithRetry(chatEndpoint, authHeaders(false), reqBytes, timeout, cancelled);
        return decodeSync(reqBytes, res.body());
    }

    @Override
    public ChatResponse stream(ChatRequest request, BooleanSupplier cancelled, Consumer<String> onDelta) {
        byte[] reqBytes = encodeRequest(request, true);
        var accumulated = new StringBuilder();
        var reasoningAcc = new StringBuilder();
        var filter = new LlmTransport.StreamDisplayFilter(onDelta);
        String[] returnedModel = {model};
        String[] finishReason = {"stop"};
        UsageMetrics[] streamUsage = {UsageMetrics.empty()};
        var toolCallsByIndex = new TreeMap<Integer, ToolCallAccumulator>();

        var outcome = LlmTransport.sendStreamWithRetry(chatEndpoint, authHeaders(true), reqBytes, timeout, cancelled, line -> {
            if (!line.startsWith("data:")) return;
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) return;
            Map<String, Object> chunk;
            try {
                chunk = GraphJson.object(GraphJson.read(payload, JSON));
            } catch (IllegalArgumentException invalid) {
                throw new LlmException(ErrorCode.INVALID_MODEL_OUTPUT, 502, "Malformed SSE JSON chunk");
            }
            if (chunk.get("model") instanceof String m && !m.isBlank()) returnedModel[0] = m;
            if (chunk.get("usage") instanceof Map<?, ?> usageObj) {
                var parsedUsage = parseUsage(usageObj);
                if (parsedUsage.hasData()) streamUsage[0] = parsedUsage;
            }
            if (!(chunk.get("choices") instanceof List<?> choices) || choices.isEmpty()) return;
            Map<String, Object> choice;
            try {
                choice = GraphJson.object(choices.getFirst());
            } catch (IllegalArgumentException invalid) {
                throw new LlmException(ErrorCode.INVALID_MODEL_OUTPUT, 502, "Malformed SSE choice object");
            }
            if (choice.get("finish_reason") instanceof String fr && !fr.isBlank()) finishReason[0] = fr;
            if (choice.get("delta") instanceof Map<?, ?> deltaMap) {
                if (deltaMap.get("reasoning_content") instanceof String rc && !rc.isEmpty()) {
                    reasoningAcc.append(rc);
                }
                if (deltaMap.get("content") instanceof String s && !s.isEmpty()) {
                    accumulated.append(s);
                    filter.accept(s);
                }
                if (deltaMap.get("tool_calls") instanceof List<?> tcList) {
                    for (Object item : tcList) {
                        if (!(item instanceof Map<?, ?> tcMap)) continue;
                        int idx = tcMap.get("index") instanceof Number n ? n.intValue() : toolCallsByIndex.size();
                        var acc = toolCallsByIndex.computeIfAbsent(idx, ignored -> new ToolCallAccumulator());
                        if (tcMap.get("id") instanceof String id && !id.isBlank()) acc.id = id;
                        if (tcMap.get("function") instanceof Map<?, ?> fn) {
                            if (fn.get("name") instanceof String n && !n.isBlank()) acc.name = n;
                            if (fn.get("arguments") instanceof String a) acc.args.append(a);
                        }
                    }
                }
            }
        });
        filter.finish();
        if (!outcome.contentType().contains("text/event-stream")) {
            var decoded = decodeSync(reqBytes, outcome.rawBytes());
            if (onDelta != null && !decoded.content().isEmpty()) {
                var sep = LlmTransport.separateThinking(decoded.content());
                if (!sep.visible().isEmpty()) onDelta.accept(sep.visible());
            }
            return decoded;
        }
        var calls = toolCallsByIndex.values().stream()
                .filter(a -> a.name != null && !a.name.isBlank())
                .map(a -> new ToolCall(a.id != null ? a.id : ("call_" + a.name), a.name, a.args.toString()))
                .toList();
        var sep = LlmTransport.separateThinking(accumulated.toString());
        String combinedReasoning = mergeReasoning(reasoningAcc.toString(), sep.reasoning());
        return new ChatResponse(returnedModel[0], finishReason[0], sep.visible(), combinedReasoning,
                calls, reqBytes, outcome.rawBytes(), Map.of(), streamUsage[0]);
    }

    @Override
    public ProbeResult probe(BooleanSupplier cancelled, Duration probeTimeout) {
        long startNs = System.nanoTime();
        Duration eff = probeTimeout == null ? Duration.ofSeconds(10) : probeTimeout;
        var models = new ArrayList<ModelInfo>();
        int status = 0;
        String message = "";
        boolean reachable = false;
        try {
            URI modelsUri = buildModelsUri();
            var res = LlmTransport.sendGet(modelsUri, authHeaders(false), eff, cancelled);
            status = res.statusCode();
            if (status == 200) {
                reachable = true;
                parseOpenAiModels(res.body(), models);
                message = "Connected (" + models.size() + " models listed)";
            } else {
                message = LlmTransport.sanitizeErrorDetail(status, res.body());
            }
            // Also try Ollama native /api/tags when Ollama or loopback /v1/models returned empty/404
            if ((kind == ProviderKind.OLLAMA || (LlmTransport.isLoopback(chatEndpoint) && !reachable)) && !cancelled.getAsBoolean()) {
                URI tagsUri = URI.create(baseUri.getScheme() + "://" + baseUri.getRawAuthority() + "/api/tags");
                try {
                    var tagRes = LlmTransport.sendGet(tagsUri, Map.of("Accept", "application/json"), eff, cancelled);
                    if (tagRes.statusCode() == 200) {
                        reachable = true;
                        status = 200;
                        parseOllamaTags(tagRes.body(), models);
                        message = "Connected to Ollama (" + models.size() + " models available)";
                    }
                } catch (LlmException ignored) {}
            }
        } catch (LlmException ex) {
            status = ex.httpStatus();
            message = ex.detail().isBlank() ? ex.code().name() : ex.detail();
        }
        long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
        boolean found = models.stream().anyMatch(m -> LlmTransport.compatibleModel(model, m.id(), descriptor.remote()));
        return new ProbeResult(reachable, kind, chatEndpoint.toASCIIString(), model, found, elapsedMs, status, models, message);
    }

    private URI buildModelsUri() {
        String bp = baseUri.getRawPath() == null ? "" : baseUri.getRawPath().replaceAll("/+$", "");
        String path = bp.endsWith("/models") ? bp : (bp + "/models");
        return URI.create(baseUri.getScheme() + "://" + baseUri.getRawAuthority() + path);
    }

    private byte[] encodeRequest(ChatRequest request, boolean stream) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", model);
        payload.put("stream", stream);
        if (stream && kind != ProviderKind.OLLAMA) {
            payload.put("stream_options", Map.of("include_usage", true));
        }
        payload.put("messages", request.messages());
        int outTokens = request.maxOutputTokens().orElse(maxOutputTokens);
        payload.put(tokenParameter, outTokens);
        if (!descriptor.reasoningModel() && request.temperature().isPresent()) {
            payload.put("temperature", java.math.BigDecimal.valueOf(request.temperature().orElseThrow()));
        }
        if (!request.tools().isEmpty()) {
            payload.put("tools", request.tools());
            payload.put("tool_choice", "auto");
        } else if (request.jsonSchema().isPresent()) {
            var schema = request.jsonSchema().orElseThrow();
            if (descriptor.remote() || kind == ProviderKind.OPENROUTER) {
                payload.put("response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of("name", "evo_response", "strict", true, "schema", schema)));
            } else {
                // Local gateways (llama.cpp/Ollama/vLLM) accept json_object + optional schema
                var rf = new LinkedHashMap<String, Object>();
                rf.put("type", "json_object");
                rf.put("schema", schema);
                payload.put("response_format", rf);
            }
        } else if (request.forceJsonObject() || jsonMode) {
            payload.put("response_format", Map.of("type", "json_object"));
        }
        if (kind != ProviderKind.OLLAMA && request.cacheHint().isPresent()) {
            request.cacheHint().orElseThrow().stablePrefixDigest()
                    .map(OpenAiCompatibleAdapter::sanitizePromptCacheKey)
                    .filter(s -> !s.isEmpty())
                    .ifPresent(cacheKey -> payload.put("prompt_cache_key", cacheKey));
        }
        try {
            return BoundedJson.encode(payload, requestBytes);
        } catch (BoundedJson.TooLarge tooLarge) {
            throw new LlmException(ErrorCode.RESOURCE_LIMIT, 0, "Request exceeded " + requestBytes + " byte budget");
        }
    }

    static String sanitizePromptCacheKey(String raw) {
        if (raw == null || raw.isBlank()) return "";
        var sb = new StringBuilder(Math.min(64, raw.length()));
        for (int i = 0; i < raw.length() && sb.length() < 64; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == ':' || c == '.') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Map<String, String> authHeaders(boolean sse) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", sse ? "text/event-stream, application/json" : "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        if (kind == ProviderKind.OPENROUTER) {
            headers.put("HTTP-Referer", "https://evo.local");
            headers.put("X-Title", "Evo Architecture Platform");
        }
        return headers;
    }

    private ChatResponse decodeSync(byte[] reqBytes, byte[] responseBytes) {
        try {
            String wire = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(responseBytes)).toString();
            var result = GraphJson.object(GraphJson.read(wire, JSON));
            String returned = result.get("model") instanceof String m ? m : model;
            var choices = GraphJson.array(result.get("choices"));
            if (returned.length() > 200 || returned.codePoints().anyMatch(Character::isISOControl) || choices.isEmpty()) {
                throw new IllegalArgumentException("Invalid model or empty choices");
            }
            var choice = GraphJson.object(choices.getFirst());
            var message = GraphJson.object(choice.get("message"));
            String finishReason = choice.get("finish_reason") instanceof String s ? s : "";
            if (!LlmTransport.isAcceptableFinishReason(finishReason) || !"assistant".equals(message.get("role"))
                    || message.get("refusal") != null) {
                throw new IllegalArgumentException("Unexpected completion status: " + finishReason);
            }
            var calls = new ArrayList<ToolCall>();
            if (message.get("tool_calls") instanceof List<?> rawCalls) {
                int idx = 0;
                for (Object rawCall : rawCalls) {
                    idx++;
                    var callObj = GraphJson.object(rawCall);
                    String id = callObj.get("id") instanceof String s && !s.isBlank() ? s : ("call_" + idx);
                    var fn = GraphJson.object(callObj.get("function"));
                    String name = fn.get("name") instanceof String n ? n : "";
                    Object rawArgs = fn.get("arguments");
                    String argsJson = rawArgs instanceof String s ? s : (rawArgs == null ? "{}" : CanonicalJson.write(rawArgs));
                    if (!name.isBlank()) {
                        calls.add(new ToolCall(id, name, argsJson));
                    }
                }
            }
            String content = message.get("content") instanceof String s ? s : "";
            String reasoning = message.get("reasoning_content") instanceof String r ? r : "";
            var sep = LlmTransport.separateThinking(content);
            UsageMetrics usage = parseUsage(result.get("usage"));
            return new ChatResponse(returned, finishReason, sep.visible(), mergeReasoning(reasoning, sep.reasoning()),
                    calls, reqBytes, responseBytes, result, usage);
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new LlmException(ErrorCode.INVALID_MODEL_OUTPUT, 200, invalid.getMessage());
        }
    }

    private static UsageMetrics parseUsage(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return UsageMetrics.empty();
        int input = intField(map, "prompt_tokens");
        if (input == 0) input = intField(map, "input_tokens");
        int output = intField(map, "completion_tokens");
        if (output == 0) output = intField(map, "output_tokens");
        int cached = 0;
        int cacheCreation = 0;
        if (map.get("prompt_tokens_details") instanceof Map<?, ?> details) {
            cached = intField(details, "cached_tokens");
            cacheCreation = intField(details, "cache_creation_tokens");
        }
        if (cached == 0) cached = intField(map, "prompt_cache_hit_tokens");
        if (cacheCreation == 0) cacheCreation = intField(map, "cache_creation_input_tokens");
        return new UsageMetrics(input, output, cached, cacheCreation);
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

    private static void parseOpenAiModels(byte[] body, List<ModelInfo> out) {
        try {
            var root = GraphJson.object(GraphJson.read(new String(body, StandardCharsets.UTF_8), JSON));
            var seen = new HashSet<String>();
            if (root.get("data") instanceof List<?> data) {
                for (Object item : data) {
                    if (!(item instanceof Map<?, ?> m)) continue;
                    if (m.get("id") instanceof String id && !id.isBlank() && seen.add(id)) {
                        String owner = m.get("owned_by") instanceof String o ? o : "";
                        String name = m.get("name") instanceof String n && !n.isBlank() ? n : null;
                        out.add(new ModelInfo(id, owner, Optional.ofNullable(name)));
                    }
                }
            }
        } catch (RuntimeException ignored) {}
    }

    private static void parseOllamaTags(byte[] body, List<ModelInfo> out) {
        try {
            var root = GraphJson.object(GraphJson.read(new String(body, StandardCharsets.UTF_8), JSON));
            var seen = new HashSet<String>();
            for (var existing : out) seen.add(existing.id());
            if (root.get("models") instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) continue;
                    String id = m.get("name") instanceof String n ? n : (m.get("model") instanceof String mod ? mod : null);
                    if (id != null && !id.isBlank() && seen.add(id)) {
                        out.add(new ModelInfo(id, "ollama", Optional.of(id)));
                    }
                }
            }
        } catch (RuntimeException ignored) {}
    }

    private static String mergeReasoning(String a, String b) {
        if (a == null || a.isBlank()) return b == null ? "" : b;
        if (b == null || b.isBlank()) return a;
        return a + "\n" + b;
    }

    private static final class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }
}
