package com.evolution.backend.query;

import com.evolution.backend.query.llm.LlmTransport;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Shared operator settings for the Chat Completions and multi-provider clients. */
public final class ChatCompletions {
    static final int RESPONSE_BYTES = 1024 * 1024;
    static final Settings CHAT = new Settings(1024 * 1024, 8192, "max_completion_tokens", false, Duration.ofSeconds(120));
    static final Settings REPAIR = new Settings(1024 * 1024, 8192, "max_tokens", true, Duration.ofSeconds(120));

    public record Settings(int requestBytes, int outputTokens, String tokenParameter, boolean jsonMode, Duration timeout) {
        public Settings {
            if (requestBytes < 16 * 1024 || requestBytes > 8 * 1024 * 1024 || outputTokens < 256 || outputTokens > 65_536
                    || !java.util.Set.of("max_tokens", "max_completion_tokens").contains(tokenParameter))
                throw new IllegalArgumentException("Invalid Chat Completions limits or token parameter");
        }

        Settings configured(Function<String,String> env) {
            String mode = env.apply("JSON_MODE");
            if (mode != null && !mode.equalsIgnoreCase("true") && !mode.equalsIgnoreCase("false"))
                throw new IllegalArgumentException("JSON_MODE must be true or false");
            return new Settings(number(env.apply("REQUEST_BYTES"), requestBytes),
                    number(env.apply("MAX_OUTPUT_TOKENS"), outputTokens),
                    env.apply("TOKEN_PARAMETER") == null ? tokenParameter : env.apply("TOKEN_PARAMETER"),
                    mode == null ? jsonMode : Boolean.parseBoolean(mode), timeout);
        }

        void addOutputSettings(Map<String,Object> request, Map<String,?> format) {
            addOutputSettings(request, format, request.get("model") instanceof String m ? m : null);
        }

        void addOutputSettings(Map<String,Object> request, Map<String,?> format, String model) {
            String param = LlmTransport.isReasoningModel(model) ? "max_completion_tokens" : tokenParameter;
            request.put(param, outputTokens);
            if (jsonMode && format != null && !format.isEmpty()) request.put("response_format", format);
        }
    }

    public static boolean loopback(URI endpoint) {
        return LlmTransport.isLoopback(endpoint);
    }

    static URI parse(String endpoint, String error) {
        try { return URI.create(endpoint.strip()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(error); }
    }

    static URI endpoint(URI input, String model, String key, boolean allowRemote, String error) {
        LlmTransport.validateEndpoint(input, model, key, allowRemote, error);
        String scheme = input.getScheme();
        String path = (input.getRawPath() == null ? "" : input.getRawPath()).replaceAll("/+$", "");
        if (!path.endsWith("/chat/completions")) path += "/chat/completions";
        return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + input.getRawAuthority() + path);
    }

    static String unwrapJson(String content) {
        return LlmTransport.extractJsonObject(content);
    }

    private static int number(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid Chat Completions numeric setting"); }
    }

    private ChatCompletions() {}
}
