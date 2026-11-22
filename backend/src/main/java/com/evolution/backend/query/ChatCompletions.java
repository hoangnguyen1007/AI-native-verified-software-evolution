package com.evolution.backend.query;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Shared operator settings for the two Chat Completions clients. */
final class ChatCompletions {
    static final int RESPONSE_BYTES = 1024 * 1024;
    static final Settings CHAT = new Settings(1024 * 1024, 8192, "max_completion_tokens", false, Duration.ofSeconds(120));
    static final Settings REPAIR = new Settings(1024 * 1024, 8192, "max_tokens", true, Duration.ofSeconds(120));

    record Settings(int requestBytes, int outputTokens, String tokenParameter, boolean jsonMode, Duration timeout) {
        Settings {
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
            request.put(tokenParameter, outputTokens);
            if (jsonMode) request.put("response_format", format);
        }
    }

    static boolean loopback(URI endpoint) {
        return "127.0.0.1".equals(endpoint.getHost()) || "[::1]".equals(endpoint.getHost());
    }

    static URI parse(String endpoint, String error) {
        try { return URI.create(endpoint.strip()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(error); }
    }

    static URI endpoint(URI input, String model, String key, boolean allowRemote, String error) {
        boolean local = loopback(input);
        String scheme = input.getScheme();
        if (input.getHost() == null || input.getRawUserInfo() != null || input.getRawQuery() != null
                || input.getRawFragment() != null || input.getPort() == 0 || input.getPort() > 65535
                || !(local && "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) && (local || allowRemote))
                || model == null || model.isBlank() || model.length() > 200 || model.codePoints().anyMatch(Character::isISOControl)
                || key != null && (key.length() > 8192 || key.codePoints().anyMatch(c -> c <= 32 || c >= 127)))
            throw new IllegalArgumentException(error);
        String path = input.getRawPath().replaceAll("/+$", "");
        if (!path.endsWith("/chat/completions")) path += "/chat/completions";
        return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + input.getRawAuthority() + path);
    }

    static String unwrapJson(String content) {
        String text = content.strip();
        if (!text.startsWith("```")) return text;
        int newline = text.indexOf('\n');
        if (newline < 0 || !text.endsWith("\n```")) return text;
        String language = text.substring(3, newline).strip();
        return language.isEmpty() || language.equalsIgnoreCase("json")
                ? text.substring(newline + 1, text.length() - 4).strip() : text;
    }

    private static int number(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid Chat Completions numeric setting"); }
    }

    private ChatCompletions() {}
}
