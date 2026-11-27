package com.evolution.backend.query.llm;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Unified operator LLM configuration resolver across Chat, Repair, and Diagnosis roles.
 * Resolves JVM properties first, then environment variables. Never exposes API keys in descriptors or probes.
 */
public final class LlmConfigRegistry {

    public enum Role {
        CHAT("evo.arch.chat", "EVO_ARCH_CHAT", "max_completion_tokens", false),
        REPAIR("evo.repair.ai", "EVO_REPAIR_AI", "max_tokens", true),
        DIAGNOSIS("evo.diagnosis.ai", "EVO_DIAGNOSIS_AI", "max_tokens", true);

        final String propPrefix;
        final String envPrefix;
        final String defaultTokenParam;
        final boolean defaultJsonMode;

        Role(String propPrefix, String envPrefix, String defaultTokenParam, boolean defaultJsonMode) {
            this.propPrefix = propPrefix;
            this.envPrefix = envPrefix;
            this.defaultTokenParam = defaultTokenParam;
            this.defaultJsonMode = defaultJsonMode;
        }
    }

    public record RoleState(
            Role role,
            boolean configured,
            Optional<LlmProviderAdapter> adapter,
            Optional<String> disabledReason) {}

    private final Map<Role, RoleState> states;

    private LlmConfigRegistry(Map<Role, RoleState> states) {
        this.states = Collections.unmodifiableMap(new EnumMap<>(states));
    }

    public static LlmConfigRegistry fromEnvironment() {
        return fromLookups(System::getProperty, System::getenv);
    }

    public static LlmConfigRegistry fromLookups(Function<String, String> props, Function<String, String> env) {
        var map = new EnumMap<Role, RoleState>(Role.class);
        for (Role role : Role.values()) {
            map.put(role, resolveRole(role, props, env));
        }
        return new LlmConfigRegistry(map);
    }

    public static LlmConfigRegistry ofAdapters(Map<Role, LlmProviderAdapter> adapters) {
        var map = new EnumMap<Role, RoleState>(Role.class);
        for (Role role : Role.values()) {
            var adapter = adapters == null ? null : adapters.get(role);
            map.put(role, new RoleState(role, adapter != null, Optional.ofNullable(adapter),
                    adapter == null ? Optional.of("NOT_CONFIGURED") : Optional.empty()));
        }
        return new LlmConfigRegistry(map);
    }

    public Optional<LlmProviderAdapter> adapter(Role role) {
        var state = states.get(role);
        return state == null ? Optional.empty() : state.adapter();
    }

    public RoleState state(Role role) {
        return states.get(role);
    }

    public Map<String, Object> describeProviders() {
        var providers = new LinkedHashMap<String, Object>();
        for (Role role : Role.values()) {
            var st = states.get(role);
            var row = new LinkedHashMap<String, Object>();
            row.put("configured", st.configured());
            st.disabledReason().ifPresent(r -> row.put("reason", r));
            st.adapter().ifPresent(a -> {
                var d = a.descriptor();
                row.put("kind", d.kind().name());
                row.put("endpoint", d.endpoint());
                row.put("model", d.model());
                row.put("remote", d.remote());
                row.put("supportsTools", d.supportsTools());
                row.put("supportsStructuredOutput", d.supportsStructuredOutput());
                row.put("supportsStreaming", d.supportsStreaming());
                row.put("reasoningModel", d.reasoningModel());
                row.put("requestBytes", d.requestBytes());
                row.put("maxOutputTokens", d.maxOutputTokens());
                row.put("tokenParameter", d.tokenParameter());
                row.put("jsonMode", d.jsonMode());
            });
            providers.put(role.name().toLowerCase(Locale.ROOT), Collections.unmodifiableMap(row));
        }
        return Map.of(
                "version", "architecture.ai-providers:v1",
                "supportedKinds", Arrays.stream(LlmProviderAdapter.ProviderKind.values()).map(Enum::name).toList(),
                "providers", Collections.unmodifiableMap(providers));
    }

    public Map<String, Object> probeProviders(Optional<String> requestedRole, BooleanSupplier cancelled) {
        var results = new LinkedHashMap<String, Object>();
        List<Role> targets;
        if (requestedRole != null && requestedRole.isPresent() && !requestedRole.orElseThrow().isBlank()) {
            Role r = Role.valueOf(requestedRole.orElseThrow().strip().toUpperCase(Locale.ROOT));
            targets = List.of(r);
        } else {
            targets = List.of(Role.values());
        }
        for (Role role : targets) {
            if (cancelled.getAsBoolean()) break;
            var st = states.get(role);
            if (st.adapter().isEmpty()) {
                results.put(role.name().toLowerCase(Locale.ROOT), Map.of(
                        "configured", false,
                        "reachable", false,
                        "reason", st.disabledReason().orElse("NOT_CONFIGURED")));
            } else {
                var probe = st.adapter().orElseThrow().probe(cancelled, Duration.ofSeconds(8));
                var view = new LinkedHashMap<String, Object>(probe.toView());
                view.put("configured", true);
                results.put(role.name().toLowerCase(Locale.ROOT), Collections.unmodifiableMap(view));
            }
        }
        return Map.of("version", "architecture.ai-providers-probe:v1", "results", Collections.unmodifiableMap(results));
    }

    public static LlmProviderAdapter createAdapter(
            LlmProviderAdapter.ProviderKind kind,
            URI endpoint,
            String model,
            String apiKey,
            boolean allowRemote,
            int requestBytes,
            int maxOutputTokens,
            String tokenParameter,
            boolean jsonMode,
            Duration timeout) {
        var effectiveKind = kind == null ? LlmProviderAdapter.ProviderKind.infer(endpoint) : kind;
        return switch (effectiveKind) {
            case ANTHROPIC -> new AnthropicMessagesAdapter(endpoint, model, apiKey, allowRemote,
                    requestBytes, maxOutputTokens, jsonMode, timeout);
            case GEMINI -> new GeminiNativeAdapter(endpoint, model, apiKey, allowRemote,
                    requestBytes, maxOutputTokens, jsonMode, timeout);
            case OPENAI_COMPAT, OPENROUTER, OLLAMA -> new OpenAiCompatibleAdapter(effectiveKind, endpoint, model,
                    apiKey, allowRemote, requestBytes, maxOutputTokens, tokenParameter, jsonMode, timeout);
        };
    }

    private static RoleState resolveRole(Role role, Function<String, String> props, Function<String, String> env) {
        String endpointRaw = first(props, env, role.propPrefix + ".endpoint", role.envPrefix + "_ENDPOINT");
        String model = first(props, env, role.propPrefix + ".model", role.envPrefix + "_MODEL");
        String providerRaw = first(props, env, role.propPrefix + ".provider", role.envPrefix + "_PROVIDER");
        String apiKey = first(props, env, role.propPrefix + ".apiKey", role.envPrefix + "_API_KEY");
        boolean allowRemote = Boolean.parseBoolean(first(props, env, role.propPrefix + ".allowRemote", role.envPrefix + "_ALLOW_REMOTE"));

        if (endpointRaw == null && model == null) {
            return new RoleState(role, false, Optional.empty(), Optional.of("NOT_CONFIGURED"));
        }
        if (endpointRaw == null || model == null) {
            return new RoleState(role, false, Optional.empty(), Optional.of("INCOMPLETE_CONFIGURATION"));
        }
        URI uri;
        try {
            uri = URI.create(endpointRaw.strip());
        } catch (IllegalArgumentException invalid) {
            return new RoleState(role, false, Optional.empty(), Optional.of("INVALID_ENDPOINT"));
        }
        if (!allowRemote && !LlmTransport.isLoopback(uri) && "https".equalsIgnoreCase(uri.getScheme())) {
            return new RoleState(role, false, Optional.empty(), Optional.of("REMOTE_DISABLED_BY_POLICY"));
        }
        int reqBytes = parseInt(first(props, env, role.propPrefix + ".requestBytes", role.envPrefix + "_REQUEST_BYTES"), 1024 * 1024, 16 * 1024, 8 * 1024 * 1024);
        int outTokens = parseInt(first(props, env, role.propPrefix + ".maxOutputTokens", role.envPrefix + "_MAX_OUTPUT_TOKENS"), 8192, 256, 65_536);
        String tokenParam = first(props, env, role.propPrefix + ".tokenParameter", role.envPrefix + "_TOKEN_PARAMETER");
        if (tokenParam == null) tokenParam = role.defaultTokenParam;
        String jsonModeRaw = first(props, env, role.propPrefix + ".jsonMode", role.envPrefix + "_JSON_MODE");
        boolean jsonMode = jsonModeRaw == null ? role.defaultJsonMode : Boolean.parseBoolean(jsonModeRaw);
        var kind = providerRaw == null ? LlmProviderAdapter.ProviderKind.infer(uri) : LlmProviderAdapter.ProviderKind.parse(providerRaw);
        try {
            var adapter = createAdapter(kind, uri, model, apiKey, allowRemote, reqBytes, outTokens, tokenParam, jsonMode, Duration.ofSeconds(120));
            return new RoleState(role, true, Optional.of(adapter), Optional.empty());
        } catch (IllegalArgumentException invalid) {
            return new RoleState(role, false, Optional.empty(), Optional.of("INVALID_CONFIGURATION"));
        }
    }

    private static String first(Function<String, String> props, Function<String, String> env, String propKey, String envKey) {
        String p = props == null ? null : props.apply(propKey);
        if (p != null && !p.isBlank()) return p.strip();
        String e = env == null ? null : env.apply(envKey);
        return (e != null && !e.isBlank()) ? e.strip() : null;
    }

    private static int parseInt(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int val = Integer.parseInt(raw.strip());
            return Math.max(min, Math.min(max, val));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
