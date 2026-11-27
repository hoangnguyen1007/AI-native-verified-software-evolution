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
 * Native Google Gemini API adapter ({@code /v1beta/models/{model}:generateContent} and
 * {@code :streamGenerateContent?alt=sse}, authenticated via {@code x-goog-api-key} header — never in query string).
 */
public final class GeminiNativeAdapter implements LlmProviderAdapter {
    private static final GraphJson.Limits JSON = new GraphJson.Limits(LlmTransport.MAX_RESPONSE_BYTES, 32, 32_768);

    private final URI generateEndpoint;
    private final URI streamEndpoint;
    private final URI modelsEndpoint;
    private final String model;
    private final String apiKey;
    private final int requestBytes;
    private final int maxOutputTokens;
    private final boolean jsonMode;
    private final Duration timeout;
    private final ProviderDescriptor descriptor;

    public GeminiNativeAdapter(URI endpoint, String model, String apiKey, boolean allowRemote,
                               int requestBytes, int maxOutputTokens, boolean jsonMode, Duration timeout) {
        LlmTransport.validateEndpoint(endpoint, model, apiKey, allowRemote, "LLM_GEMINI_CONFIGURATION");
        String bareModel = model.startsWith("models/") ? model.substring("models/".length()) : model;
        String path = endpoint.getRawPath() == null ? "" : endpoint.getRawPath().replaceAll("/+$", "");
        String origin = endpoint.getScheme().toLowerCase(Locale.ROOT) + "://" + endpoint.getRawAuthority();
        String apiRoot;
        if (path.contains(":generateContent") || path.contains(":streamGenerateContent")) {
            int modelsIdx = path.indexOf("/models/");
            apiRoot = modelsIdx >= 0 ? path.substring(0, modelsIdx) : "/v1beta";
        } else if (path.endsWith("/models")) {
            apiRoot = path.substring(0, path.length() - "/models".length());
        } else if (path.isEmpty()) {
            apiRoot = "/v1beta";
        } else {
            apiRoot = path;
        }
        this.generateEndpoint = URI.create(origin + apiRoot + "/models/" + bareModel + ":generateContent");
        this.streamEndpoint = URI.create(origin + apiRoot + "/models/" + bareModel + ":streamGenerateContent?alt=sse");
        this.modelsEndpoint = URI.create(origin + apiRoot + "/models");
        this.model = model;
        this.apiKey = apiKey;
        this.requestBytes = requestBytes;
        this.maxOutputTokens = maxOutputTokens;
        this.jsonMode = jsonMode;
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        this.descriptor = new ProviderDescriptor(
                ProviderKind.GEMINI,
                this.generateEndpoint.toASCIIString(),
                model,
                !LlmTransport.isLoopback(this.generateEndpoint),
                true,
                true,
                true,
                LlmTransport.isReasoningModel(model),
                requestBytes,
                maxOutputTokens,
                "maxOutputTokens",
                jsonMode);
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ChatResponse complete(ChatRequest request, BooleanSupplier cancelled) {
        byte[] reqBytes = encodeRequest(request);
        var res = LlmTransport.sendJsonWithRetry(generateEndpoint, headers(false), reqBytes, timeout, cancelled);
        return decodeSync(reqBytes, res.body());
    }

    @Override
    public ChatResponse stream(ChatRequest request, BooleanSupplier cancelled, Consumer<String> onDelta) {
        byte[] reqBytes = encodeRequest(request);
        var accumulated = new StringBuilder();
        var reasoning = new StringBuilder();
        var filter = new LlmTransport.StreamDisplayFilter(onDelta);
        String[] finishReason = {"stop"};
        String[] returnedModel = {model};
        UsageMetrics[] streamUsage = {UsageMetrics.empty()};
        var calls = new ArrayList<ToolCall>();

        var outcome = LlmTransport.sendStreamWithRetry(streamEndpoint, headers(true), reqBytes, timeout, cancelled, line -> {
            if (!line.startsWith("data:")) return;
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) return;
            Map<String, Object> chunk;
            try {
                chunk = GraphJson.object(GraphJson.read(payload, JSON));
            } catch (IllegalArgumentException invalid) {
                throw new LlmException(ErrorCode.INVALID_MODEL_OUTPUT, 502, "Malformed Gemini SSE JSON chunk");
            }
            if (chunk.get("modelVersion") instanceof String mv && !mv.isBlank()) returnedModel[0] = mv;
            if (chunk.get("usageMetadata") instanceof Map<?, ?> meta) {
                var parsedUsage = parseUsage(meta);
                if (parsedUsage.hasData()) streamUsage[0] = parsedUsage;
            }
            if (!(chunk.get("candidates") instanceof List<?> cands) || cands.isEmpty()) return;
            Map<String, Object> cand;
            try {
                cand = GraphJson.object(cands.getFirst());
            } catch (IllegalArgumentException invalid) {
                throw new LlmException(ErrorCode.INVALID_MODEL_OUTPUT, 502, "Malformed Gemini SSE candidate object");
            }
            if (cand.get("finishReason") instanceof String fr && !fr.isBlank()) {
                finishReason[0] = mapFinishReason(fr);
            }
            extractCandidateParts(cand, accumulated, reasoning, calls, filter);
        });
        filter.finish();
        if (!outcome.contentType().contains("text/event-stream")) {
            var decoded = decodeSync(reqBytes, outcome.rawBytes());
            if (onDelta != null && !decoded.content().isEmpty()) onDelta.accept(decoded.content());
            return decoded;
        }
        return new ChatResponse(returnedModel[0], finishReason[0], accumulated.toString(), reasoning.toString(),
                calls, reqBytes, outcome.rawBytes(), Map.of(), streamUsage[0]);
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
                if (root.get("models") instanceof List<?> list) {
                    for (Object item : list) {
                        if (!(item instanceof Map<?, ?> m)) continue;
                        if (m.get("name") instanceof String name && !name.isBlank()) {
                            String display = m.get("displayName") instanceof String d ? d : null;
                            models.add(new ModelInfo(name, "google", Optional.ofNullable(display)));
                        }
                    }
                }
                message = "Connected to Gemini (" + models.size() + " models listed)";
            } else {
                message = LlmTransport.sanitizeErrorDetail(status, res.body());
            }
        } catch (LlmException ex) {
            status = ex.httpStatus();
            message = ex.detail().isBlank() ? ex.code().name() : ex.detail();
        }
        long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
        boolean found = models.stream().anyMatch(m -> LlmTransport.compatibleModel(model, m.id(), descriptor.remote()));
        return new ProbeResult(reachable, ProviderKind.GEMINI, generateEndpoint.toASCIIString(), model,
                found, elapsedMs, status, models, message);
    }

    private byte[] encodeRequest(ChatRequest request) {
        var systemParts = new ArrayList<Map<String, Object>>();
        var contents = new ArrayList<Map<String, Object>>();
        var toolIdToName = new HashMap<String, String>();

        for (var msg : request.messages()) {
            String role = Objects.toString(msg.get("role"), "");
            if ("system".equals(role)) {
                if (msg.get("content") instanceof String s && !s.isBlank()) {
                    systemParts.add(Map.of("text", s));
                }
                continue;
            }
            if ("assistant".equals(role)) {
                var parts = new ArrayList<Map<String, Object>>();
                if (msg.get("content") instanceof String s && !s.isBlank()) {
                    parts.add(Map.of("text", s));
                }
                if (msg.get("tool_calls") instanceof List<?> toolCalls) {
                    for (Object tc : toolCalls) {
                        var tcObj = GraphJson.object(tc);
                        var fn = GraphJson.object(tcObj.get("function"));
                        String id = Objects.toString(tcObj.get("id"), "");
                        String name = Objects.toString(fn.get("name"), "");
                        if (!id.isBlank() && !name.isBlank()) toolIdToName.put(id, name);
                        Map<String, Object> argsMap = Map.of();
                        Object rawArgs = fn.get("arguments");
                        if (rawArgs instanceof Map<?, ?>) argsMap = GraphJson.object(rawArgs);
                        else if (rawArgs instanceof String s && !s.isBlank()) {
                            try { argsMap = GraphJson.object(GraphJson.read(s, JSON)); } catch (RuntimeException ignored) {}
                        }
                        parts.add(Map.of("functionCall", Map.of("name", name, "args", argsMap)));
                    }
                }
                if (!parts.isEmpty()) contents.add(Map.of("role", "model", "parts", parts));
            } else if ("tool".equals(role)) {
                String callId = Objects.toString(msg.get("tool_call_id"), "");
                String name = toolIdToName.getOrDefault(callId, "tool");
                String rawContent = Objects.toString(msg.get("content"), "{}");
                Map<String, Object> respMap;
                try {
                    respMap = GraphJson.object(GraphJson.read(rawContent, JSON));
                } catch (RuntimeException ignored) {
                    respMap = Map.of("output", rawContent);
                }
                appendGeminiUserPart(contents, Map.of(
                        "functionResponse", Map.of("name", name, "response", respMap)));
            } else {
                String text = Objects.toString(msg.get("content"), "");
                appendGeminiUserPart(contents, Map.of("text", text));
            }
        }

        var payload = new LinkedHashMap<String, Object>();
        if (!systemParts.isEmpty()) {
            payload.put("systemInstruction", Map.of("parts", systemParts));
        }
        payload.put("contents", contents);

        var genCfg = new LinkedHashMap<String, Object>();
        genCfg.put("maxOutputTokens", request.maxOutputTokens().orElse(maxOutputTokens));
        if (request.temperature().isPresent() && !descriptor.reasoningModel()) {
            genCfg.put("temperature", java.math.BigDecimal.valueOf(request.temperature().orElseThrow()));
        }
        if (request.tools().isEmpty() && (request.jsonSchema().isPresent() || request.forceJsonObject() || jsonMode)) {
            genCfg.put("responseMimeType", "application/json");
            request.jsonSchema().ifPresent(schema -> genCfg.put("responseSchema", sanitizeGeminiSchema(schema)));
        }
        payload.put("generationConfig", genCfg);

        if (!request.tools().isEmpty()) {
            var decls = new ArrayList<Map<String, Object>>();
            for (var tool : request.tools()) {
                var fn = GraphJson.object(tool.get("function"));
                var decl = new LinkedHashMap<String, Object>();
                decl.put("name", Objects.toString(fn.get("name"), ""));
                decl.put("description", Objects.toString(fn.get("description"), ""));
                if (fn.get("parameters") instanceof Map<?, ?> params) {
                    decl.put("parameters", sanitizeGeminiSchema(GraphJson.object(params)));
                }
                decls.add(decl);
            }
            payload.put("tools", List.of(Map.of("functionDeclarations", decls)));
        }
        try {
            return BoundedJson.encode(payload, requestBytes);
        } catch (BoundedJson.TooLarge large) {
            throw new LlmException(ErrorCode.RESOURCE_LIMIT, 0, "Gemini request exceeded byte budget");
        }
    }

    /** Merges adjacent Gemini {@code user} turns (e.g. parallel tool responses or budget finalization) for strict role alternation. */
    private static void appendGeminiUserPart(List<Map<String, Object>> contents, Map<String, Object> part) {
        if (!contents.isEmpty() && "user".equals(contents.getLast().get("role"))) {
            var merged = new ArrayList<Map<String, Object>>();
            Object prevParts = contents.getLast().get("parts");
            if (prevParts instanceof List<?> prevList) {
                for (Object item : prevList) {
                    if (item instanceof Map<?, ?>) merged.add(GraphJson.object(item));
                }
            }
            merged.add(part);
            contents.set(contents.size() - 1, Map.of("role", "user", "parts", List.copyOf(merged)));
        } else {
            contents.add(Map.of("role", "user", "parts", List.of(part)));
        }
    }

    /** Strips JSON Schema keywords Gemini rejects (e.g. additionalProperties). */
    private static Map<String, Object> sanitizeGeminiSchema(Map<String, Object> schema) {
        var out = new LinkedHashMap<String, Object>();
        for (var e : schema.entrySet()) {
            String k = e.getKey();
            if ("additionalProperties".equals(k) || "$schema".equals(k)) continue;
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                if ("properties".equals(k)) {
                    var props = new LinkedHashMap<String, Object>();
                    for (var pe : m.entrySet()) {
                        props.put(Objects.toString(pe.getKey()), pe.getValue() instanceof Map<?, ?> pm
                                ? sanitizeGeminiSchema(GraphJson.object(pm)) : pe.getValue());
                    }
                    out.put(k, props);
                } else {
                    out.put(k, sanitizeGeminiSchema(GraphJson.object(m)));
                }
            } else {
                out.put(k, v);
            }
        }
        return out;
    }

    private ChatResponse decodeSync(byte[] reqBytes, byte[] responseBytes) {
        try {
            String wire = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(responseBytes)).toString();
            var root = GraphJson.object(GraphJson.read(wire, JSON));
            String returned = root.get("modelVersion") instanceof String mv && !mv.isBlank() ? mv : model;
            var candidates = GraphJson.array(root.get("candidates"));
            if (candidates.isEmpty()) throw new IllegalArgumentException("Empty Gemini candidates");
            var cand = GraphJson.object(candidates.getFirst());
            String finish = cand.get("finishReason") instanceof String fr ? mapFinishReason(fr) : "stop";
            var text = new StringBuilder();
            var reasoning = new StringBuilder();
            var calls = new ArrayList<ToolCall>();
            extractCandidateParts(cand, text, reasoning, calls, null);
            var openAiCompat = AnthropicMessagesAdapter.toOpenAiEnvelope(returned, finish, text.toString(), calls);
            UsageMetrics usage = parseUsage(root.get("usageMetadata"));
            return new ChatResponse(returned, finish, text.toString(), reasoning.toString(),
                    calls, reqBytes, responseBytes, openAiCompat, usage);
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new LlmException(ErrorCode.INVALID_MODEL_OUTPUT, 200, invalid.getMessage());
        }
    }

    private static UsageMetrics parseUsage(Object raw) {
        if (!(raw instanceof Map<?, ?> meta)) return UsageMetrics.empty();
        int input = intField(meta, "promptTokenCount");
        int output = intField(meta, "candidatesTokenCount");
        int cached = intField(meta, "cachedContentTokenCount");
        return new UsageMetrics(input, output, cached, 0);
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

    private static void extractCandidateParts(Map<String, Object> cand, StringBuilder textOut,
                                              StringBuilder reasoningOut, List<ToolCall> calls,
                                              LlmTransport.StreamDisplayFilter filter) {
        if (!(cand.get("content") instanceof Map<?, ?> content)) return;
        if (!(content.get("parts") instanceof List<?> parts)) return;
        for (Object rawPart : parts) {
            if (!(rawPart instanceof Map<?, ?> part)) continue;
            boolean thought = Boolean.TRUE.equals(part.get("thought"));
            if (part.get("text") instanceof String s && !s.isEmpty()) {
                if (thought) {
                    reasoningOut.append(s);
                } else {
                    textOut.append(s);
                    if (filter != null) filter.accept(s);
                }
            } else if (part.get("functionCall") instanceof Map<?, ?> fc) {
                String name = Objects.toString(fc.get("name"), "");
                String argsJson = fc.get("args") == null ? "{}" : CanonicalJson.write(fc.get("args"));
                if (!name.isBlank()) {
                    calls.add(new ToolCall("gemini_call_" + (calls.size() + 1), name, argsJson));
                }
            }
        }
    }

    private static String mapFinishReason(String geminiReason) {
        return switch (geminiReason.toUpperCase(Locale.ROOT)) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            default -> geminiReason.toLowerCase(Locale.ROOT);
        };
    }

    private Map<String, String> headers(boolean sse) {
        var h = new LinkedHashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("Accept", sse ? "text/event-stream, application/json" : "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            h.put("x-goog-api-key", apiKey);
        }
        return h;
    }
}
