package com.evolution.backend.query.llm;

import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Multi-provider LLM adapter boundary supporting OpenAI-compatible (OpenAI, OpenRouter, Ollama,
 * LM Studio, vLLM), Anthropic Messages API, and Google Gemini native API.
 * Model output has zero mutation, file-write, or execution authority.
 */
public interface LlmProviderAdapter {

    enum ProviderKind {
        OPENAI_COMPAT,
        OPENROUTER,
        OLLAMA,
        ANTHROPIC,
        GEMINI;

        public static ProviderKind parse(String raw) {
            if (raw == null || raw.isBlank()) return OPENAI_COMPAT;
            return switch (raw.strip().toUpperCase(Locale.ROOT).replace('-', '_')) {
                case "OPENAI", "OPENAI_COMPAT", "OPENAI_COMPATIBLE", "LOCAL", "VLLM", "LMSTUDIO", "LM_STUDIO", "DEEPSEEK" -> OPENAI_COMPAT;
                case "OPENROUTER" -> OPENROUTER;
                case "OLLAMA" -> OLLAMA;
                case "ANTHROPIC", "CLAUDE" -> ANTHROPIC;
                case "GEMINI", "GOOGLE", "GOOGLE_GEMINI" -> GEMINI;
                default -> throw new IllegalArgumentException("Unsupported LLM provider kind: " + raw);
            };
        }

        public static ProviderKind infer(java.net.URI endpoint) {
            if (endpoint == null || endpoint.getHost() == null) return OPENAI_COMPAT;
            String host = endpoint.getHost().toLowerCase(Locale.ROOT);
            String path = endpoint.getRawPath() == null ? "" : endpoint.getRawPath().toLowerCase(Locale.ROOT);
            if (host.equals("api.anthropic.com") || host.endsWith(".anthropic.com") || path.endsWith("/messages")) {
                return ANTHROPIC;
            }
            if ((host.equals("generativelanguage.googleapis.com") || host.endsWith(".googleapis.com"))
                    && !path.contains("/openai")) {
                return GEMINI;
            }
            if (host.equals("openrouter.ai") || host.endsWith(".openrouter.ai")) {
                return OPENROUTER;
            }
            if (endpoint.getPort() == 11434 || path.startsWith("/api/chat") || path.startsWith("/api/generate")) {
                return OLLAMA;
            }
            return OPENAI_COMPAT;
        }
    }

    enum ErrorCode {
        BUSY,
        PROVIDER_UNAVAILABLE,
        INVALID_MODEL_OUTPUT,
        RESOURCE_LIMIT,
        RATE_LIMITED,
        AUTH_FAILED
    }

    final class LlmException extends RuntimeException {
        private final ErrorCode code;
        private final int httpStatus;
        private final String detail;

        public LlmException(ErrorCode code, int httpStatus, String detail) {
            super(code.name() + (detail == null || detail.isBlank() ? "" : ": " + detail));
            this.code = Objects.requireNonNull(code);
            this.httpStatus = httpStatus;
            this.detail = detail == null ? "" : detail;
        }

        public ErrorCode code() { return code; }
        public int httpStatus() { return httpStatus; }
        public String detail() { return detail; }
    }

    record ProviderDescriptor(
            ProviderKind kind,
            String endpoint,
            String model,
            boolean remote,
            boolean supportsTools,
            boolean supportsStructuredOutput,
            boolean supportsStreaming,
            boolean reasoningModel,
            int requestBytes,
            int maxOutputTokens,
            String tokenParameter,
            boolean jsonMode) {}

    record ToolCall(String id, String name, String argumentsJson) {}

    record PromptCacheHint(
            boolean cacheSystemPrefix,
            boolean cacheTools,
            Optional<String> stablePrefixDigest) {
        public PromptCacheHint {
            stablePrefixDigest = stablePrefixDigest == null
                    ? Optional.empty()
                    : stablePrefixDigest.map(String::strip).filter(s -> !s.isEmpty());
        }

        public static PromptCacheHint disabled() {
            return new PromptCacheHint(false, false, Optional.empty());
        }

        public static PromptCacheHint of(boolean cacheSystemPrefix, boolean cacheTools, String stablePrefixDigest) {
            return new PromptCacheHint(cacheSystemPrefix, cacheTools, Optional.ofNullable(stablePrefixDigest));
        }

        public boolean enabled() {
            return cacheSystemPrefix || cacheTools || stablePrefixDigest.isPresent();
        }
    }

    record UsageMetrics(
            int inputTokens,
            int outputTokens,
            int cacheReadTokens,
            int cacheCreationTokens) {
        public static final UsageMetrics EMPTY = new UsageMetrics(0, 0, 0, 0);

        public UsageMetrics {
            inputTokens = Math.max(0, inputTokens);
            outputTokens = Math.max(0, outputTokens);
            cacheReadTokens = Math.max(0, cacheReadTokens);
            cacheCreationTokens = Math.max(0, cacheCreationTokens);
        }

        public static UsageMetrics empty() {
            return EMPTY;
        }

        public boolean hasData() {
            return inputTokens > 0 || outputTokens > 0 || cacheReadTokens > 0 || cacheCreationTokens > 0;
        }

        public UsageMetrics add(UsageMetrics other) {
            if (other == null || !other.hasData()) return this;
            if (!this.hasData()) return other;
            return new UsageMetrics(
                    saturatingAdd(this.inputTokens, other.inputTokens),
                    saturatingAdd(this.outputTokens, other.outputTokens),
                    saturatingAdd(this.cacheReadTokens, other.cacheReadTokens),
                    saturatingAdd(this.cacheCreationTokens, other.cacheCreationTokens));
        }

        public double estimatedCacheSavingsPct() {
            if (cacheReadTokens <= 0) return 0.0;
            long totalInput = Math.max((long) inputTokens, (long) cacheReadTokens);
            if (totalInput <= 0) return 0.0;
            double pct = Math.round((cacheReadTokens * 1000.0) / totalInput) / 10.0;
            return Math.min(100.0, Math.max(0.0, pct));
        }

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("inputTokens", inputTokens);
            map.put("outputTokens", outputTokens);
            map.put("cacheReadTokens", cacheReadTokens);
            map.put("cacheCreationTokens", cacheCreationTokens);
            map.put("estimatedCacheSavingsPct", java.math.BigDecimal.valueOf(estimatedCacheSavingsPct()));
            return Collections.unmodifiableMap(map);
        }

        private static int saturatingAdd(int a, int b) {
            long sum = (long) a + (long) b;
            return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, sum);
        }
    }

    record ChatRequest(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Optional<Map<String, Object>> jsonSchema,
            boolean forceJsonObject,
            Optional<Double> temperature,
            OptionalInt maxOutputTokens,
            boolean stream,
            Optional<PromptCacheHint> cacheHint) {
        public ChatRequest(
                List<Map<String, Object>> messages,
                List<Map<String, Object>> tools,
                Optional<Map<String, Object>> jsonSchema,
                boolean forceJsonObject,
                Optional<Double> temperature,
                OptionalInt maxOutputTokens,
                boolean stream) {
            this(messages, tools, jsonSchema, forceJsonObject, temperature, maxOutputTokens, stream, Optional.empty());
        }

        public ChatRequest {
            messages = List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
            jsonSchema = jsonSchema == null ? Optional.empty() : jsonSchema;
            temperature = temperature == null ? Optional.empty() : temperature;
            maxOutputTokens = maxOutputTokens == null ? OptionalInt.empty() : maxOutputTokens;
            cacheHint = cacheHint == null ? Optional.empty() : cacheHint;
        }
    }

    record ChatResponse(
            String returnedModel,
            String finishReason,
            String content,
            String reasoningContent,
            List<ToolCall> toolCalls,
            byte[] requestBytes,
            byte[] rawResponseBytes,
            Map<String, Object> rawObject,
            UsageMetrics usage) {
        public ChatResponse(
                String returnedModel,
                String finishReason,
                String content,
                String reasoningContent,
                List<ToolCall> toolCalls,
                byte[] requestBytes,
                byte[] rawResponseBytes,
                Map<String, Object> rawObject) {
            this(returnedModel, finishReason, content, reasoningContent, toolCalls,
                    requestBytes, rawResponseBytes, rawObject, UsageMetrics.empty());
        }

        public ChatResponse {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            requestBytes = requestBytes == null ? new byte[0] : requestBytes.clone();
            rawResponseBytes = rawResponseBytes == null ? new byte[0] : rawResponseBytes.clone();
            rawObject = rawObject == null ? Map.of() : rawObject;
            usage = usage == null ? UsageMetrics.empty() : usage;
        }
    }

    record ModelInfo(String id, String ownedBy, Optional<String> displayName) {}

    record ProbeResult(
            boolean reachable,
            ProviderKind kind,
            String endpoint,
            String configuredModel,
            boolean configuredModelFound,
            long latencyMs,
            int httpStatus,
            List<ModelInfo> models,
            String message) {
        public ProbeResult {
            models = models == null ? List.of() : List.copyOf(models);
        }

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("reachable", reachable);
            map.put("kind", kind.name());
            map.put("endpoint", endpoint);
            map.put("configuredModel", configuredModel);
            map.put("configuredModelFound", configuredModelFound);
            map.put("latencyMs", latencyMs);
            map.put("httpStatus", httpStatus);
            map.put("modelCount", models.size());
            map.put("models", models.stream().limit(64).map(m -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("id", m.id());
                if (m.ownedBy() != null && !m.ownedBy().isBlank()) row.put("ownedBy", m.ownedBy());
                m.displayName().ifPresent(d -> row.put("displayName", d));
                return row;
            }).toList());
            map.put("message", message);
            return Collections.unmodifiableMap(map);
        }
    }

    ProviderDescriptor descriptor();

    ChatResponse complete(ChatRequest request, BooleanSupplier cancelled);

    ChatResponse stream(ChatRequest request, BooleanSupplier cancelled, Consumer<String> onDelta);

    ProbeResult probe(BooleanSupplier cancelled, Duration timeout);
}
