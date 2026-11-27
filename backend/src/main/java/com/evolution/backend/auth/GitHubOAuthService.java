package com.evolution.backend.auth;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.github.GitHubCoordinates;
import com.evolution.backend.github.GitHubRestClient;
import com.evolution.backend.github.GitHubTokenBroker;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Multi-user GitHub authentication &amp; zero-env in-app setup engine supporting:
 * <ul>
 *   <li>OAuth 2.0 Web Application Flow with PKCE ({@code S256}) and single-use {@code state}</li>
 *   <li>OAuth 2.0 Device Authorization Grant (RFC 8628, {@code /login/device/code})</li>
 *   <li>1-Click GitHub App Manifest registration ({@code /settings/apps/new} &rarr; {@code /app-manifests/{code}/conversions})</li>
 *   <li>Live hot-reload encrypted in-app OAuth / webhook configuration ({@link GitHubOAuthConfigStore})</li>
 *   <li>Unified multi-user account linking &amp; auto-provisioning ({@link UserAccountStore})</li>
 *   <li>Expiring OAuth / GitHub App user-to-server refresh token renewal &amp; per-session PAT fallback</li>
 * </ul>
 */
public final class GitHubOAuthService {
    public static final String DEFAULT_SCOPES = "read:user user:email read:org repo";
    private static final long STATE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int MAX_PENDING_STATES = 512;
    private static final int MAX_PENDING_DEVICES = 512;
    private static final int MAX_PENDING_MANIFESTS = 256;
    private static final GraphJson.Limits JSON_LIMITS = new GraphJson.Limits(2 * 1024 * 1024, 32, 100_000);

    public record Config(
            boolean multiUserMode,
            String clientId,
            String clientSecret,
            String host,
            String scopes,
            boolean githubAppEnabled,
            Optional<String> publicBaseUrl,
            Optional<URI> oauthBaseOverride,
            Optional<URI> apiBaseOverride) {

        public Config {
            clientId = clientId == null ? "" : clientId.strip();
            clientSecret = clientSecret == null ? "" : clientSecret.strip();
            host = (host == null || host.isBlank()) ? GitHubCoordinates.DEFAULT_HOST : host.strip().toLowerCase(Locale.ROOT);
            if (!GitHubCoordinates.allowedWebHosts().contains(host)) {
                throw new IllegalArgumentException("OAuth host not in GitHub allowlist: " + host);
            }
            scopes = (scopes == null || scopes.isBlank()) ? DEFAULT_SCOPES : scopes.strip();
            publicBaseUrl = publicBaseUrl == null
                    ? Optional.empty()
                    : publicBaseUrl.map(String::strip).filter(s -> !s.isBlank()).map(GitHubOAuthService::stripTrailingSlash);
            oauthBaseOverride = oauthBaseOverride == null ? Optional.empty() : oauthBaseOverride;
            apiBaseOverride = apiBaseOverride == null ? Optional.empty() : apiBaseOverride;
        }

        public boolean oauthWebEnabled() {
            return !clientId.isBlank() && !clientSecret.isBlank();
        }

        public boolean deviceFlowEnabled() {
            return !clientId.isBlank();
        }

        public boolean isHttpsPublicOrigin() {
            return publicBaseUrl.map(u -> u.toLowerCase(Locale.ROOT).startsWith("https://")).orElse(false);
        }

        public static Config fromEnvironment() {
            return fromEnvironment(System::getenv, System::getProperty);
        }

        public static Config fromEnvironment(Function<String, String> env, Function<String, String> props) {
            String multiRaw = firstNonBlank(props.apply("evo.auth.multiUser"), env.apply("EVO_MULTI_USER_MODE"));
            boolean multiUser = "true".equalsIgnoreCase(multiRaw) || "1".equals(multiRaw);
            String clientId = firstNonBlank(props.apply("evo.github.oauth.clientId"), env.apply("EVO_GITHUB_OAUTH_CLIENT_ID"));
            String clientSecret = firstNonBlank(props.apply("evo.github.oauth.clientSecret"), env.apply("EVO_GITHUB_OAUTH_CLIENT_SECRET"));
            String host = firstNonBlank(props.apply("evo.github.host"), env.apply("EVO_GITHUB_HOST"));
            String scopes = firstNonBlank(props.apply("evo.github.oauth.scopes"), env.apply("EVO_GITHUB_OAUTH_SCOPES"));
            String appId = firstNonBlank(props.apply("evo.github.app.id"), env.apply("EVO_GITHUB_APP_ID"));
            String appFlag = firstNonBlank(props.apply("evo.github.app.enabled"), env.apply("EVO_GITHUB_APP_ENABLED"));
            boolean appEnabled = !appId.isBlank() || "true".equalsIgnoreCase(appFlag);
            String publicBase = firstNonBlank(props.apply("evo.publicBaseUrl"), env.apply("EVO_PUBLIC_BASE_URL"));
            return new Config(
                    multiUser,
                    clientId,
                    clientSecret,
                    host.isBlank() ? GitHubCoordinates.DEFAULT_HOST : host,
                    scopes.isBlank() ? DEFAULT_SCOPES : scopes,
                    appEnabled,
                    publicBase.isBlank() ? Optional.empty() : Optional.of(publicBase),
                    Optional.empty(),
                    Optional.empty());
        }
    }

    public record DeviceFlowStart(
            String deviceFlowId,
            String userCode,
            String verificationUri,
            int expiresIn,
            int interval) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("deviceFlowId", deviceFlowId);
            map.put("userCode", userCode);
            map.put("verificationUri", verificationUri);
            map.put("expiresIn", expiresIn);
            map.put("interval", interval);
            return map;
        }
    }

    public record DevicePollOutcome(
            String status,
            int interval,
            Optional<String> error,
            Optional<UserSessionStore.SessionView> session) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("status", status);
            map.put("interval", interval);
            error.ifPresent(e -> map.put("error", e));
            session.ifPresent(s -> {
                map.put("user", s.user().toPublicUserMap());
                map.put("csrfToken", s.user().csrfToken());
            });
            return map;
        }
    }

    public record CallbackOutcome(UserSessionStore.SessionView session, String returnTo) {
    }

    public record ManifestPrepareResult(
            String actionUrl,
            String manifestJson,
            String state,
            String callbackUrl,
            String manifestCallbackUrl) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("actionUrl", actionUrl);
            map.put("manifestJson", manifestJson);
            map.put("state", state);
            map.put("callbackUrl", callbackUrl);
            map.put("manifestCallbackUrl", manifestCallbackUrl);
            return map;
        }
    }

    public record ManifestCallbackOutcome(String redirectTo, String appSlug, String clientId) {
    }

    public static final class OAuthException extends RuntimeException {
        private final int status;
        private final String code;

        public OAuthException(int status, String code, String message) {
            super(GitHubTokenBroker.redact(message));
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private record PendingOAuthState(
            String codeVerifier,
            String redirectUri,
            String returnTo,
            String host,
            Optional<String> linkUserId,
            Optional<String> linkSessionId,
            long expiresAtMs) {
    }

    private record PendingManifestState(
            String host,
            String publicBaseUrl,
            long expiresAtMs) {
    }

    private static final class PendingDeviceFlow {
        final String deviceCode;
        final String host;
        final Optional<String> linkUserId;
        final Optional<String> linkSessionId;
        final long expiresAtMs;
        volatile int intervalSeconds;

        PendingDeviceFlow(String deviceCode, String host, Optional<String> linkUserId,
                Optional<String> linkSessionId, long expiresAtMs, int intervalSeconds) {
            this.deviceCode = deviceCode;
            this.host = host;
            this.linkUserId = linkUserId;
            this.linkSessionId = linkSessionId;
            this.expiresAtMs = expiresAtMs;
            this.intervalSeconds = Math.max(1, intervalSeconds);
        }
    }

    private record FetchedProfile(
            String userId,
            String login,
            String name,
            String avatarUrl,
            String email,
            List<String> orgs,
            List<String> scopes) {
    }

    private final Config baseConfig;
    private final UserSessionStore sessionStore;
    private final GitHubRestClient.Transport transport;
    private final GitHubOAuthConfigStore configStore;
    private final UserAccountStore accountStore;
    private volatile Consumer<Optional<String>> webhookSecretListener;

    private volatile Config effectiveConfig;
    private volatile String setupSource = "NONE";
    private volatile boolean inAppDeviceFlowEnabled = true;
    private volatile boolean hasSavedWebhookSecret = false;
    private volatile String savedAppSlug = "";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingOAuthState> pendingStates = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PendingOAuthState> eldest) {
                    return size() > MAX_PENDING_STATES;
                }
            });
    private final Map<String, PendingDeviceFlow> pendingDevices = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PendingDeviceFlow> eldest) {
                    return size() > MAX_PENDING_DEVICES;
                }
            });
    private final Map<String, PendingManifestState> pendingManifests = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PendingManifestState> eldest) {
                    return size() > MAX_PENDING_MANIFESTS;
                }
            });

    public GitHubOAuthService(Config config, UserSessionStore sessionStore, GitHubRestClient.Transport transport) {
        this(config, sessionStore, transport, null, null);
    }

    public GitHubOAuthService(
            Config config,
            UserSessionStore sessionStore,
            GitHubRestClient.Transport transport,
            GitHubOAuthConfigStore configStore,
            UserAccountStore accountStore) {
        this.baseConfig = Objects.requireNonNull(config, "config");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.configStore = configStore;
        this.accountStore = accountStore;
        this.effectiveConfig = this.baseConfig;
        reloadEffectiveConfig();
    }

    /** Registers a live listener notified whenever in-app webhook secrets are loaded, saved, or cleared. */
    public GitHubOAuthService withWebhookSecretListener(Consumer<Optional<String>> listener) {
        this.webhookSecretListener = listener;
        if (listener != null && configStore != null) {
            configStore.load()
                    .map(GitHubOAuthConfigStore.StoredConfig::webhookSecret)
                    .filter(s -> !s.isBlank())
                    .ifPresent(sec -> listener.accept(Optional.of(sec)));
        }
        return this;
    }

    public Config config() {
        return effectiveConfig;
    }

    public UserSessionStore sessionStore() {
        return sessionStore;
    }

    public UserAccountStore accountStore() {
        return accountStore;
    }

    public GitHubOAuthConfigStore configStore() {
        return configStore;
    }

    public boolean isGitHubSetupConfigured() {
        return !effectiveConfig.clientId().isBlank();
    }

    public boolean isDeviceFlowEnabled() {
        Config cfg = effectiveConfig;
        return cfg.deviceFlowEnabled() && (!"IN_APP".equals(setupSource) || inAppDeviceFlowEnabled);
    }

    public String setupSource() {
        return setupSource;
    }

    public synchronized void reloadEffectiveConfig() {
        Optional<GitHubOAuthConfigStore.StoredConfig> storedOpt = configStore == null ? Optional.empty() : configStore.load();
        if (storedOpt.isPresent() && !storedOpt.orElseThrow().clientId().isBlank()) {
            var s = storedOpt.orElseThrow();
            String resolvedHost = GitHubCoordinates.allowedWebHosts().contains(s.host())
                    ? s.host() : baseConfig.host();
            Optional<String> pubBase = !s.publicBaseUrl().isBlank()
                    ? Optional.of(s.publicBaseUrl())
                    : baseConfig.publicBaseUrl();
            this.effectiveConfig = new Config(
                    baseConfig.multiUserMode(),
                    s.clientId(),
                    s.clientSecret(),
                    resolvedHost,
                    s.scopes().isBlank() ? baseConfig.scopes() : s.scopes(),
                    s.githubAppEnabled() || baseConfig.githubAppEnabled(),
                    pubBase,
                    baseConfig.oauthBaseOverride(),
                    baseConfig.apiBaseOverride());
            this.setupSource = "IN_APP";
            this.inAppDeviceFlowEnabled = s.deviceFlowEnabled();
            this.hasSavedWebhookSecret = !s.webhookSecret().isBlank();
            this.savedAppSlug = s.appSlug();
            notifyWebhookListener(s.webhookSecret().isBlank() ? Optional.empty() : Optional.of(s.webhookSecret()));
        } else {
            this.effectiveConfig = baseConfig;
            this.setupSource = baseConfig.clientId().isBlank() ? "NONE" : "ENV";
            this.inAppDeviceFlowEnabled = baseConfig.deviceFlowEnabled();
            this.hasSavedWebhookSecret = false;
            this.savedAppSlug = "";
            notifyWebhookListener(Optional.empty());
        }
    }

    private void notifyWebhookListener(Optional<String> secret) {
        Consumer<Optional<String>> listener = this.webhookSecretListener;
        if (listener != null) {
            listener.accept(secret);
        }
    }

    public static boolean isLoopbackOrigin(String rawOrigin) {
        if (rawOrigin == null || rawOrigin.isBlank()) {
            return true;
        }
        try {
            URI uri = URI.create(rawOrigin.strip());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return true;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return lower.equals("localhost")
                    || lower.equals("127.0.0.1")
                    || lower.equals("::1")
                    || lower.equals("[::1]")
                    || lower.equals("0:0:0:0:0:0:0:1")
                    || lower.equals("[0:0:0:0:0:0:0:1]")
                    || lower.equals("0.0.0.0")
                    || lower.equals("::")
                    || lower.equals("[::]")
                    || lower.endsWith(".localhost")
                    || lower.startsWith("127.");
        } catch (IllegalArgumentException invalid) {
            return true;
        }
    }

    public static boolean isWebhookReachable(String rawOrigin) {
        if (rawOrigin == null || rawOrigin.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(rawOrigin.strip());
            return "https".equalsIgnoreCase(uri.getScheme()) && !isLoopbackOrigin(rawOrigin);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private String effectivePublicBaseUrl(String serverOrigin) {
        Config cfg = effectiveConfig;
        String baseOrigin = cfg.publicBaseUrl().orElse(
                serverOrigin == null || serverOrigin.isBlank() ? "http://127.0.0.1:8080" : serverOrigin.strip());
        return stripTrailingSlash(baseOrigin);
    }

    public Map<String, Object> publicConfig() {
        return publicConfig("http://127.0.0.1:8080", false);
    }

    public Map<String, Object> publicConfig(String serverOrigin, boolean canConfigureGitHubSetup) {
        Config cfg = effectiveConfig;
        String baseOrigin = effectivePublicBaseUrl(serverOrigin);
        String callbackUrl = baseOrigin + "/api/v1/auth/github/callback";
        String webhookUrl = baseOrigin + "/api/v1/github/webhooks";
        var map = new LinkedHashMap<String, Object>();
        map.put("multiUserMode", cfg.multiUserMode());
        map.put("localAccountsEnabled", accountStore != null);
        map.put("hasRegisteredUsers", accountStore != null && accountStore.hasRegisteredUsers());
        map.put("oauthWebEnabled", cfg.oauthWebEnabled());
        map.put("deviceFlowEnabled", isDeviceFlowEnabled());
        map.put("githubAppEnabled", cfg.githubAppEnabled());
        map.put("patEnabled", true);
        map.put("localOperatorFallbackEnabled", !cfg.multiUserMode());
        map.put("githubSetupConfigured", isGitHubSetupConfigured());
        map.put("githubSetupSource", setupSource);
        map.put("canConfigureGitHubSetup", canConfigureGitHubSetup);
        map.put("publicBaseUrl", baseOrigin);
        map.put("callbackUrl", callbackUrl);
        map.put("webhookUrl", webhookUrl);
        map.put("isLoopbackHost", isLoopbackOrigin(baseOrigin));
        map.put("webhookReachable", isWebhookReachable(baseOrigin));
        map.put("manifestFlowSupported", configStore != null);
        map.put("host", cfg.host());
        map.put("scopes", Arrays.stream(cfg.scopes().split("\\s+")).filter(s -> !s.isBlank()).toList());
        return map;
    }

    /** Public-safe view of current GitHub OAuth / App setup (NEVER includes clientSecret or webhookSecret). */
    public Map<String, Object> setupView(String serverOrigin, boolean canConfigure, boolean webhookConfigured) {
        Config cfg = effectiveConfig;
        String baseOrigin = effectivePublicBaseUrl(serverOrigin);
        var map = new LinkedHashMap<String, Object>();
        map.put("configured", isGitHubSetupConfigured());
        map.put("source", setupSource);
        map.put("host", cfg.host());
        map.put("clientId", cfg.clientId());
        map.put("hasClientSecret", !cfg.clientSecret().isBlank());
        map.put("hasWebhookSecret", hasSavedWebhookSecret || webhookConfigured);
        map.put("deviceFlowEnabled", isDeviceFlowEnabled());
        map.put("publicBaseUrl", baseOrigin);
        map.put("callbackUrl", baseOrigin + "/api/v1/auth/github/callback");
        map.put("webhookUrl", baseOrigin + "/api/v1/github/webhooks");
        map.put("isLoopbackHost", isLoopbackOrigin(baseOrigin));
        map.put("webhookReachable", isWebhookReachable(baseOrigin));
        map.put("manifestFlowSupported", configStore != null);
        if (!savedAppSlug.isBlank()) {
            map.put("appSlug", savedAppSlug);
        }
        map.put("canConfigure", canConfigure);
        return map;
    }

    /** Encrypts, persists, and hot-reloads in-app GitHub OAuth / webhook configuration without restart. */
    public synchronized Map<String, Object> saveInAppSetup(
            String rawClientId,
            Optional<String> rawClientSecret,
            Optional<String> rawHost,
            Optional<String> rawWebhookSecret,
            Optional<Boolean> deviceFlowEnabledOpt,
            Optional<Boolean> githubAppEnabledOpt,
            Optional<String> appSlugOpt,
            Optional<String> appIdOpt,
            Optional<String> publicBaseUrlOpt,
            String serverOrigin,
            boolean canConfigure,
            boolean webhookConfigured) {
        if (configStore == null) {
            throw new OAuthException(503, "IN_APP_SETUP_UNAVAILABLE", "Encrypted in-app GitHub configuration store is not enabled.");
        }
        String clientId = validateShortTokenField(rawClientId, "clientId", 256, true);
        String targetHost = validateHost(rawHost.orElse(effectiveConfig.host()));
        Optional<GitHubOAuthConfigStore.StoredConfig> existing = configStore.load();

        String clientSecret;
        if (rawClientSecret != null && rawClientSecret.isPresent()) {
            clientSecret = validateShortTokenField(rawClientSecret.orElseThrow(), "clientSecret", 512, false);
        } else {
            clientSecret = existing.map(GitHubOAuthConfigStore.StoredConfig::clientSecret).orElse("");
        }

        String webhookSecret;
        if (rawWebhookSecret != null && rawWebhookSecret.isPresent()) {
            webhookSecret = validateShortTokenField(rawWebhookSecret.orElseThrow(), "webhookSecret", 512, false);
        } else {
            webhookSecret = existing.map(GitHubOAuthConfigStore.StoredConfig::webhookSecret).orElse("");
        }

        boolean devFlow = deviceFlowEnabledOpt != null && deviceFlowEnabledOpt.isPresent()
                ? deviceFlowEnabledOpt.orElseThrow()
                : existing.map(GitHubOAuthConfigStore.StoredConfig::deviceFlowEnabled).orElse(true);
        boolean ghApp = githubAppEnabledOpt != null && githubAppEnabledOpt.isPresent()
                ? githubAppEnabledOpt.orElseThrow()
                : existing.map(GitHubOAuthConfigStore.StoredConfig::githubAppEnabled).orElse(false);
        String slug = appSlugOpt != null && appSlugOpt.isPresent()
                ? appSlugOpt.orElseThrow().strip()
                : existing.map(GitHubOAuthConfigStore.StoredConfig::appSlug).orElse("");
        String appId = appIdOpt != null && appIdOpt.isPresent()
                ? appIdOpt.orElseThrow().strip()
                : existing.map(GitHubOAuthConfigStore.StoredConfig::appId).orElse("");
        String pubBase = publicBaseUrlOpt != null && publicBaseUrlOpt.isPresent()
                ? validatePublicBaseUrl(publicBaseUrlOpt.orElseThrow())
                : existing.map(GitHubOAuthConfigStore.StoredConfig::publicBaseUrl).orElse("");

        configStore.save(new GitHubOAuthConfigStore.StoredConfig(
                clientId, clientSecret, targetHost, effectiveConfig.scopes(),
                devFlow, ghApp, webhookSecret, slug, appId, pubBase, System.currentTimeMillis()));
        reloadEffectiveConfig();
        return setupView(serverOrigin, canConfigure, webhookConfigured || !webhookSecret.isBlank());
    }

    /** Clears saved in-app GitHub OAuth configuration and hot-reloads effective config live. */
    public synchronized Map<String, Object> clearInAppSetup(String serverOrigin, boolean canConfigure, boolean envWebhookConfigured) {
        if (configStore == null) {
            throw new OAuthException(503, "IN_APP_SETUP_UNAVAILABLE", "Encrypted in-app GitHub configuration store is not enabled.");
        }
        configStore.clear();
        reloadEffectiveConfig();
        return setupView(serverOrigin, canConfigure, envWebhookConfigured);
    }

    /**
     * Prepares an official 1-click GitHub App Manifest registration payload and single-use CSRF state.
     * The frontend submits a hidden HTML form to {@code actionUrl} with input {@code name="manifest"}.
     */
    public ManifestPrepareResult prepareAppManifest(
            String serverOrigin,
            Optional<String> appNameOpt,
            Optional<String> hostOverride,
            Optional<String> publicBaseOverride) {
        if (configStore == null) {
            throw new OAuthException(503, "IN_APP_SETUP_UNAVAILABLE", "Encrypted in-app GitHub configuration store is not enabled.");
        }
        String targetHost = validateHost(hostOverride.orElse(effectiveConfig.host()));
        boolean hasExplicitPublicBase = publicBaseOverride != null && publicBaseOverride.isPresent()
                && !publicBaseOverride.orElseThrow().isBlank();
        String baseOrigin = hasExplicitPublicBase
                ? validatePublicBaseUrl(publicBaseOverride.orElseThrow())
                : effectivePublicBaseUrl(serverOrigin);
        baseOrigin = stripTrailingSlash(baseOrigin);
        String appName = sanitizeAppName(appNameOpt == null ? "" : appNameOpt.orElse(""));
        String callbackUrl = baseOrigin + "/api/v1/auth/github/callback";
        String manifestCallbackUrl = baseOrigin + "/api/v1/auth/github/manifest/callback";
        String webhookUrl = baseOrigin + "/api/v1/github/webhooks";

        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("name", appName);
        manifest.put("url", baseOrigin);
        var hookAttrs = new LinkedHashMap<String, Object>();
        hookAttrs.put("url", webhookUrl);
        hookAttrs.put("active", isWebhookReachable(baseOrigin));
        manifest.put("hook_attributes", hookAttrs);
        manifest.put("redirect_url", manifestCallbackUrl);
        manifest.put("callback_urls", List.of(callbackUrl));
        manifest.put("public", false);
        manifest.put("request_oauth_on_install", true);
        var perms = new LinkedHashMap<String, Object>();
        perms.put("contents", "write");
        perms.put("pull_requests", "write");
        perms.put("checks", "write");
        perms.put("issues", "write");
        perms.put("security_events", "write");
        perms.put("metadata", "read");
        perms.put("statuses", "write");
        manifest.put("default_permissions", perms);
        manifest.put("default_events", List.of("pull_request", "push", "issue_comment"));

        String manifestJson = CanonicalJson.write(manifest);
        String state = randomBase64Url(32);
        pendingManifests.put(state, new PendingManifestState(
                targetHost, hasExplicitPublicBase ? baseOrigin : "", System.currentTimeMillis() + STATE_TTL_MS));
        String actionUrl = stripTrailingSlash(oauthBase(targetHost).toString())
                + "/settings/apps/new?state=" + urlEncode(state);
        return new ManifestPrepareResult(actionUrl, manifestJson, state, callbackUrl, manifestCallbackUrl);
    }

    /**
     * Completes GitHub App Manifest callback: validates single-use state, converts temporary {@code code}
     * via {@code POST /app-manifests/{code}/conversions}, encrypts &amp; saves returned credentials, and hot-reloads live.
     */
    public ManifestCallbackOutcome completeAppManifestCallback(String code, String state, BooleanSupplier cancelled) {
        if (configStore == null) {
            throw new OAuthException(503, "IN_APP_SETUP_UNAVAILABLE", "Encrypted in-app GitHub configuration store is not enabled.");
        }
        if (state == null || state.isBlank()) {
            throw new OAuthException(400, "MANIFEST_STATE_INVALID", "Missing GitHub App Manifest state.");
        }
        PendingManifestState pending = pendingManifests.remove(state.strip());
        if (pending == null || System.currentTimeMillis() > pending.expiresAtMs()) {
            throw new OAuthException(400, "MANIFEST_STATE_INVALID", "GitHub App Manifest state is invalid or expired.");
        }
        if (code == null || code.isBlank() || !code.strip().matches("[A-Za-z0-9._-]{4,256}")) {
            throw new OAuthException(400, "MANIFEST_CODE_INVALID", "Invalid GitHub App Manifest conversion code.");
        }

        URI apiRoot = apiBase(pending.host());
        URI convertUri = URI.create(stripTrailingSlash(apiRoot.toString())
                + "/app-manifests/" + urlEncode(code.strip()) + "/conversions");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", GitHubRestClient.API_VERSION);
        headers.put("User-Agent", GitHubRestClient.USER_AGENT);

        try {
            var resp = transport.exchange(convertUri, "POST", headers, new byte[0],
                    cancelled == null ? () -> false : cancelled);
            if (resp.status() < 200 || resp.status() >= 300) {
                throw new OAuthException(502, "MANIFEST_CONVERSION_FAILED",
                        "GitHub App Manifest conversion failed with HTTP " + resp.status());
            }
            var obj = GraphJson.object(GraphJson.read(new String(resp.body(), StandardCharsets.UTF_8), JSON_LIMITS));
            String clientId = str(obj.get("client_id"), "");
            String clientSecret = str(obj.get("client_secret"), "");
            String webhookSecret = str(obj.get("webhook_secret"), "");
            String slug = str(obj.get("slug"), "");
            String appId = obj.get("id") instanceof Number n ? String.valueOf(n.longValue()) : str(obj.get("id"), "");
            if (clientId.isBlank() || clientSecret.isBlank()) {
                throw new OAuthException(502, "MANIFEST_CONVERSION_INCOMPLETE",
                        "GitHub App Manifest conversion response missing client_id or client_secret.");
            }
            saveInAppSetup(
                    clientId,
                    Optional.of(clientSecret),
                    Optional.of(pending.host()),
                    webhookSecret.isBlank() ? Optional.empty() : Optional.of(webhookSecret),
                    Optional.of(true),
                    Optional.of(true),
                    slug.isBlank() ? Optional.empty() : Optional.of(slug),
                    appId.isBlank() ? Optional.empty() : Optional.of(appId),
                    pending.publicBaseUrl().isBlank() ? Optional.empty() : Optional.of(pending.publicBaseUrl()),
                    pending.publicBaseUrl(),
                    true,
                    !webhookSecret.isBlank());
            return new ManifestCallbackOutcome("/?github_setup=manifest_connected", slug, clientId);
        } catch (IOException io) {
            throw new OAuthException(502, "OAUTH_NETWORK_ERROR", "Unable to reach GitHub App Manifest conversion endpoint.");
        }
    }

    /** Starts OAuth 2.0 Web Application Flow with PKCE S256 and returns the GitHub authorize redirect URI. */
    public URI startWebLogin(String serverOrigin, Optional<String> returnTo) {
        String safeReturn = validateReturnTo(returnTo.orElse("/"));
        Config cfg = effectiveConfig;
        if (!cfg.oauthWebEnabled()) {
            throw new OAuthException(503, "OAUTH_WEB_NOT_CONFIGURED",
                    "GitHub OAuth Web Flow is not configured yet. Configure it in Account & Workspace Settings.");
        }
        String baseOrigin = effectivePublicBaseUrl(serverOrigin);
        String redirectUri = baseOrigin + "/api/v1/auth/github/callback";
        String state = randomBase64Url(32);
        String codeVerifier = randomBase64Url(48);
        String codeChallenge = pkceS256(codeVerifier);

        Optional<String> linkUserId = Optional.empty();
        Optional<String> linkSessionId = Optional.empty();
        if (accountStore != null) {
            var active = RequestContext.current();
            if (active.isPresent() && active.orElseThrow().user().isAuthenticated()) {
                linkUserId = Optional.of(active.orElseThrow().user().userId());
                linkSessionId = active.orElseThrow().sessionId();
            }
        }
        pendingStates.put(state, new PendingOAuthState(
                codeVerifier, redirectUri, safeReturn, cfg.host(),
                linkUserId, linkSessionId, System.currentTimeMillis() + STATE_TTL_MS));

        URI base = oauthBase(cfg.host());
        String query = "client_id=" + urlEncode(cfg.clientId())
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&scope=" + urlEncode(cfg.scopes())
                + "&state=" + urlEncode(state)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=S256";
        return URI.create(stripTrailingSlash(base.toString()) + "/login/oauth/authorize?" + query);
    }

    /** Completes OAuth 2.0 Web Callback, verifies single-use state + PKCE verifier, and issues/updates a user session. */
    public CallbackOutcome completeWebCallback(String code, String state, BooleanSupplier cancelled) {
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            throw new OAuthException(400, "OAUTH_INVALID_CALLBACK", "Missing OAuth code or state.");
        }
        PendingOAuthState pending = pendingStates.remove(state);
        if (pending == null || System.currentTimeMillis() > pending.expiresAtMs()) {
            throw new OAuthException(400, "OAUTH_STATE_INVALID", "OAuth login state is invalid or expired.");
        }
        Config cfg = effectiveConfig;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", cfg.clientId());
        form.put("client_secret", cfg.clientSecret());
        form.put("code", code.strip());
        form.put("redirect_uri", pending.redirectUri());
        form.put("code_verifier", pending.codeVerifier());

        var tokenObj = postFormJson(
                URI.create(stripTrailingSlash(oauthBase(pending.host()).toString()) + "/login/oauth/access_token"),
                form, cancelled);
        if (tokenObj.containsKey("error")) {
            String err = str(tokenObj.get("error"), "oauth_exchange_failed");
            String desc = str(tokenObj.get("error_description"), "GitHub OAuth token exchange failed.");
            throw new OAuthException(401, "OAUTH_EXCHANGE_FAILED", err + ": " + desc);
        }
        var session = createOrLinkSessionFromTokenPayload(
                pending.host(), AuthenticatedUser.AuthMethod.OAUTH_WEB, tokenObj,
                pending.linkUserId(), pending.linkSessionId(), cancelled);
        String target = appendQueryFlag(pending.returnTo(), "auth=signed_in");
        return new CallbackOutcome(session, target);
    }

    /** Starts GitHub OAuth Device Authorization Flow (RFC 8628). */
    public DeviceFlowStart startDeviceFlow(Optional<String> scopesOverride, Optional<String> hostOverride,
            BooleanSupplier cancelled) {
        Config cfg = effectiveConfig;
        if (!isDeviceFlowEnabled()) {
            throw new OAuthException(503, "OAUTH_DEVICE_NOT_CONFIGURED",
                    "GitHub Device Flow is not configured yet. Configure a GitHub App or OAuth Client ID in Workspace Settings.");
        }
        String targetHost = validateHost(hostOverride.orElse(cfg.host()));
        String requestedScopes = scopesOverride.filter(s -> !s.isBlank()).orElse(cfg.scopes());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", cfg.clientId());
        form.put("scope", requestedScopes);

        var resp = postFormJson(
                URI.create(stripTrailingSlash(oauthBase(targetHost).toString()) + "/login/device/code"),
                form, cancelled);
        if (resp.containsKey("error")) {
            throw new OAuthException(400, "OAUTH_DEVICE_START_FAILED",
                    str(resp.get("error_description"), str(resp.get("error"), "Device code request rejected.")));
        }
        String deviceCode = str(resp.get("device_code"), "");
        String userCode = str(resp.get("user_code"), "");
        String verificationUri = str(resp.get("verification_uri"), "https://" + targetHost + "/login/device");
        int expiresIn = intVal(resp.get("expires_in"), 900);
        int interval = Math.max(1, intVal(resp.get("interval"), 5));
        if (deviceCode.isBlank() || userCode.isBlank()) {
            throw new OAuthException(502, "OAUTH_DEVICE_INVALID_RESPONSE", "GitHub returned an incomplete device code.");
        }
        Optional<String> linkUserId = Optional.empty();
        Optional<String> linkSessionId = Optional.empty();
        if (accountStore != null) {
            var active = RequestContext.current();
            if (active.isPresent() && active.orElseThrow().user().isAuthenticated()) {
                linkUserId = Optional.of(active.orElseThrow().user().userId());
                linkSessionId = active.orElseThrow().sessionId();
            }
        }
        String flowId = randomBase64Url(24);
        pendingDevices.put(flowId, new PendingDeviceFlow(
                deviceCode, targetHost, linkUserId, linkSessionId,
                System.currentTimeMillis() + (expiresIn * 1000L), interval));
        return new DeviceFlowStart(flowId, userCode, verificationUri, expiresIn, interval);
    }

    /** Polls GitHub for completion of an active Device Flow and mints/updates a user session on authorization. */
    public DevicePollOutcome pollDeviceFlow(String deviceFlowId, BooleanSupplier cancelled) {
        if (deviceFlowId == null || deviceFlowId.isBlank()) {
            throw new OAuthException(400, "INVALID_DEVICE_FLOW", "Missing deviceFlowId.");
        }
        PendingDeviceFlow pending = pendingDevices.get(deviceFlowId);
        if (pending == null) {
            return new DevicePollOutcome("EXPIRED", 5, Optional.of("Device authorization session not found or expired."), Optional.empty());
        }
        if (System.currentTimeMillis() > pending.expiresAtMs) {
            pendingDevices.remove(deviceFlowId);
            return new DevicePollOutcome("EXPIRED", pending.intervalSeconds, Optional.of("Device code expired."), Optional.empty());
        }
        Config cfg = effectiveConfig;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", cfg.clientId());
        form.put("device_code", pending.deviceCode);
        form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");

        var resp = postFormJson(
                URI.create(stripTrailingSlash(oauthBase(pending.host).toString()) + "/login/oauth/access_token"),
                form, cancelled);
        if (resp.containsKey("error")) {
            String err = str(resp.get("error"), "unknown_error");
            return switch (err) {
                case "authorization_pending" ->
                        new DevicePollOutcome("PENDING", pending.intervalSeconds, Optional.empty(), Optional.empty());
                case "slow_down" -> {
                    int next = intVal(resp.get("interval"), pending.intervalSeconds + 5);
                    pending.intervalSeconds = Math.max(pending.intervalSeconds + 5, next);
                    yield new DevicePollOutcome("SLOW_DOWN", pending.intervalSeconds, Optional.empty(), Optional.empty());
                }
                case "expired_token" -> {
                    pendingDevices.remove(deviceFlowId);
                    yield new DevicePollOutcome("EXPIRED", pending.intervalSeconds,
                            Optional.of("Device code expired. Start a new sign-in."), Optional.empty());
                }
                case "access_denied" -> {
                    pendingDevices.remove(deviceFlowId);
                    yield new DevicePollOutcome("DENIED", pending.intervalSeconds,
                            Optional.of("GitHub authorization was cancelled."), Optional.empty());
                }
                default -> {
                    pendingDevices.remove(deviceFlowId);
                    yield new DevicePollOutcome("ERROR", pending.intervalSeconds,
                            Optional.of(GitHubTokenBroker.redact(str(resp.get("error_description"), err))), Optional.empty());
                }
            };
        }
        pendingDevices.remove(deviceFlowId);
        var session = createOrLinkSessionFromTokenPayload(
                pending.host, AuthenticatedUser.AuthMethod.OAUTH_DEVICE, resp,
                pending.linkUserId, pending.linkSessionId, cancelled);
        return new DevicePollOutcome("AUTHORIZED", pending.intervalSeconds, Optional.empty(), Optional.of(session));
    }

    /** Validates a Personal Access Token against GitHub /user and creates or links a session-scoped user entry. */
    public UserSessionStore.SessionView authenticateSessionPat(char[] rawToken, Optional<String> hostOverride,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(rawToken, "rawToken");
        String cleaned = new String(rawToken).strip();
        Arrays.fill(rawToken, '\0');
        if (cleaned.isEmpty() || cleaned.length() > 512
                || cleaned.indexOf('\r') >= 0 || cleaned.indexOf('\n') >= 0 || cleaned.indexOf('\0') >= 0) {
            throw new OAuthException(400, "INVALID_TOKEN", "Invalid GitHub token format.");
        }
        String targetHost = validateHost(hostOverride.orElse(effectiveConfig.host()));
        FetchedProfile profile = fetchUserProfile(targetHost, cleaned, cancelled);
        Optional<String> linkUserId = Optional.empty();
        Optional<String> linkSessionId = Optional.empty();
        if (accountStore != null) {
            var active = RequestContext.current();
            if (active.isPresent() && active.orElseThrow().user().isAuthenticated()) {
                linkUserId = Optional.of(active.orElseThrow().user().userId());
                linkSessionId = active.orElseThrow().sessionId();
            }
        }
        return finalizeGitHubSession(targetHost, AuthenticatedUser.AuthMethod.SESSION_PAT, profile,
                cleaned.toCharArray(), null, 0L, 0L, linkUserId, linkSessionId);
    }

    /** Exchanges an expiring OAuth / GitHub App refresh token for a fresh access token pair. */
    public Optional<UserSessionStore.RefreshedToken> refreshAccessToken(String host, char[] refreshToken) {
        Config cfg = effectiveConfig;
        if (!cfg.oauthWebEnabled() || refreshToken == null || refreshToken.length == 0) {
            return Optional.empty();
        }
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", cfg.clientId());
            form.put("client_secret", cfg.clientSecret());
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", new String(refreshToken));
            var resp = postFormJson(
                    URI.create(stripTrailingSlash(oauthBase(validateHost(host)).toString()) + "/login/oauth/access_token"),
                    form, () -> false);
            String newAccess = str(resp.get("access_token"), "");
            if (newAccess.isBlank() || resp.containsKey("error")) {
                return Optional.empty();
            }
            String newRefresh = str(resp.get("refresh_token"), "");
            long now = System.currentTimeMillis();
            long expSec = longVal(resp.get("expires_in"), 0L);
            long refExpSec = longVal(resp.get("refresh_token_expires_in"), 0L);
            List<String> scopes = parseScopes(str(resp.get("scope"), ""));
            return Optional.of(new UserSessionStore.RefreshedToken(
                    newAccess.toCharArray(),
                    newRefresh.isBlank() ? null : newRefresh.toCharArray(),
                    expSec > 0L ? now + expSec * 1000L : 0L,
                    refExpSec > 0L ? now + refExpSec * 1000L : 0L,
                    scopes));
        } catch (RuntimeException failed) {
            return Optional.empty();
        }
    }

    private UserSessionStore.SessionView createOrLinkSessionFromTokenPayload(
            String host,
            AuthenticatedUser.AuthMethod method,
            Map<String, Object> tokenObj,
            Optional<String> linkUserId,
            Optional<String> linkSessionId,
            BooleanSupplier cancelled) {
        String accessToken = str(tokenObj.get("access_token"), "");
        if (accessToken.isBlank()) {
            throw new OAuthException(502, "OAUTH_NO_ACCESS_TOKEN", "GitHub OAuth response did not contain an access token.");
        }
        String refreshToken = str(tokenObj.get("refresh_token"), "");
        long now = System.currentTimeMillis();
        long expiresIn = longVal(tokenObj.get("expires_in"), 0L);
        long refreshExpiresIn = longVal(tokenObj.get("refresh_token_expires_in"), 0L);
        long tokenExpiresAt = expiresIn > 0L ? now + expiresIn * 1000L : 0L;
        long refreshExpiresAt = refreshExpiresIn > 0L ? now + refreshExpiresIn * 1000L : 0L;

        FetchedProfile profile = fetchUserProfile(host, accessToken, cancelled);
        List<String> scopes = profile.scopes().isEmpty() ? parseScopes(str(tokenObj.get("scope"), "")) : profile.scopes();
        FetchedProfile withScopes = new FetchedProfile(
                profile.userId(), profile.login(), profile.name(), profile.avatarUrl(),
                profile.email(), profile.orgs(), scopes);
        return finalizeGitHubSession(
                host, method, withScopes,
                accessToken.toCharArray(),
                refreshToken.isBlank() ? null : refreshToken.toCharArray(),
                tokenExpiresAt, refreshExpiresAt, linkUserId, linkSessionId);
    }

    private UserSessionStore.SessionView finalizeGitHubSession(
            String host,
            AuthenticatedUser.AuthMethod method,
            FetchedProfile profile,
            char[] accessToken,
            char[] refreshToken,
            long tokenExpiresAt,
            long refreshExpiresAt,
            Optional<String> linkUserId,
            Optional<String> linkSessionId) {
        long now = System.currentTimeMillis();
        if (accountStore != null) {
            UserAccountStore.Account account = linkUserId.isPresent()
                    ? accountStore.linkGitHub(linkUserId.orElseThrow(), profile.userId(), profile.login(), profile.avatarUrl())
                    : accountStore.upsertFromGitHubSignIn(profile.userId(), profile.login(), profile.name(),
                            profile.email(), profile.avatarUrl());
            var unifiedUser = new AuthenticatedUser(
                    account.userId(),
                    account.username(),
                    account.displayName(),
                    profile.avatarUrl(),
                    account.email(),
                    profile.orgs(),
                    profile.scopes(),
                    method,
                    host,
                    sessionStore.newCsrfToken(),
                    now,
                    tokenExpiresAt,
                    account.role(),
                    true,
                    profile.login());
            if (linkSessionId.isPresent()) {
                var rebound = sessionStore.bindGitHubToken(
                        linkSessionId.orElseThrow(), unifiedUser, accessToken, refreshToken,
                        tokenExpiresAt, refreshExpiresAt);
                if (rebound.isPresent()) {
                    return rebound.orElseThrow();
                }
            }
            return sessionStore.createSession(unifiedUser, accessToken, refreshToken, tokenExpiresAt, refreshExpiresAt);
        }

        var user = new AuthenticatedUser(
                profile.userId(),
                profile.login(),
                profile.name(),
                profile.avatarUrl(),
                profile.email(),
                profile.orgs(),
                profile.scopes(),
                method,
                host,
                sessionStore.newCsrfToken(),
                now,
                tokenExpiresAt);
        return sessionStore.createSession(user, accessToken, refreshToken, tokenExpiresAt, refreshExpiresAt);
    }

    private FetchedProfile fetchUserProfile(String host, String bearerToken, BooleanSupplier cancelled) {
        URI apiRoot = apiBase(host);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("User-Agent", GitHubRestClient.USER_AGENT);
        headers.put("X-GitHub-Api-Version", GitHubRestClient.API_VERSION);
        headers.put("Authorization", "Bearer " + bearerToken);
        try {
            var userResp = transport.exchange(
                    URI.create(stripTrailingSlash(apiRoot.toString()) + "/user"),
                    "GET", headers, new byte[0], cancelled == null ? () -> false : cancelled);
            if (userResp.status() == 401 || userResp.status() == 403) {
                throw new OAuthException(401, "GITHUB_AUTH_REQUIRED",
                        "Token could not be authenticated with GitHub and was not stored.");
            }
            if (userResp.status() < 200 || userResp.status() >= 300) {
                throw new OAuthException(502, "GITHUB_PROFILE_UNAVAILABLE",
                        "GitHub /user failed with HTTP " + userResp.status());
            }
            var userObj = GraphJson.object(GraphJson.read(new String(userResp.body(), StandardCharsets.UTF_8), JSON_LIMITS));
            String login = str(userObj.get("login"), "");
            if (login.isBlank()) {
                throw new OAuthException(502, "GITHUB_PROFILE_INVALID", "GitHub /user response missing login.");
            }
            String userId = userObj.get("id") instanceof Number n ? String.valueOf(n.longValue()) : login;
            String name = str(userObj.get("name"), login);
            String avatarUrl = str(userObj.get("avatar_url"), "");
            String email = str(userObj.get("email"), "");
            List<String> scopes = parseScopes(userResp.headers().getOrDefault("X-OAuth-Scopes", ""));

            // Best-effort org discovery (may be empty if read:org scope not granted)
            List<String> orgs = new ArrayList<>();
            try {
                var orgResp = transport.exchange(
                        URI.create(stripTrailingSlash(apiRoot.toString()) + "/user/orgs?per_page=100"),
                        "GET", headers, new byte[0], cancelled == null ? () -> false : cancelled);
                if (orgResp.status() >= 200 && orgResp.status() < 300 && orgResp.body().length > 0) {
                    for (Object item : GraphJson.array(
                            GraphJson.read(new String(orgResp.body(), StandardCharsets.UTF_8), JSON_LIMITS))) {
                        String orgLogin = str(GraphJson.object(item).get("login"), "");
                        if (!orgLogin.isBlank()) {
                            orgs.add(orgLogin);
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Org list is optional when scope lacks read:org.
            }
            return new FetchedProfile(userId, login, name, avatarUrl, email, orgs, scopes);
        } catch (IOException io) {
            throw new OAuthException(502, "GITHUB_NETWORK_ERROR", "GitHub authentication network call failed.");
        }
    }

    private Map<String, Object> postFormJson(URI uri, Map<String, String> form, BooleanSupplier cancelled) {
        var sb = new StringBuilder();
        for (var e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("User-Agent", GitHubRestClient.USER_AGENT);
        try {
            var resp = transport.exchange(uri, "POST", headers, payload, cancelled == null ? () -> false : cancelled);
            if (resp.status() >= 500) {
                throw new OAuthException(502, "OAUTH_UPSTREAM_ERROR", "GitHub OAuth endpoint returned HTTP " + resp.status());
            }
            String text = new String(resp.body(), StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                return Map.of("error", "empty_response");
            }
            return GraphJson.object(GraphJson.read(text, JSON_LIMITS));
        } catch (IOException io) {
            throw new OAuthException(502, "OAUTH_NETWORK_ERROR", "Unable to reach GitHub OAuth endpoint.");
        }
    }

    private URI oauthBase(String host) {
        return effectiveConfig.oauthBaseOverride().orElseGet(() -> URI.create("https://" + host));
    }

    private URI apiBase(String host) {
        return effectiveConfig.apiBaseOverride().orElseGet(() -> GitHubCoordinates.DEFAULT_HOST.equals(host)
                ? URI.create("https://" + GitHubCoordinates.DEFAULT_API_HOST)
                : URI.create("https://" + host + "/api/v3"));
    }

    private static String validateHost(String raw) {
        String h = (raw == null || raw.isBlank()) ? GitHubCoordinates.DEFAULT_HOST : raw.strip().toLowerCase(Locale.ROOT);
        if (!GitHubCoordinates.allowedWebHosts().contains(h)) {
            throw new OAuthException(400, "HOST_NOT_ALLOWED", "GitHub host is not in allowlist.");
        }
        return h;
    }

    private static String validateShortTokenField(String raw, String field, int maxLen, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new OAuthException(400, "INVALID_REQUEST", "Missing required field: " + field);
            }
            return "";
        }
        String trimmed = raw.strip();
        if (trimmed.length() > maxLen) {
            throw new OAuthException(400, "INVALID_REQUEST", field + " exceeds maximum length.");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new OAuthException(400, "INVALID_REQUEST", field + " contains control characters.");
            }
        }
        return trimmed;
    }

    private static String validatePublicBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.strip();
        if (trimmed.length() > 512 || trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0) {
            throw new OAuthException(400, "INVALID_REQUEST", "Invalid publicBaseUrl.");
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new OAuthException(400, "INVALID_REQUEST", "publicBaseUrl must be a valid http(s) origin.");
            }
            return stripTrailingSlash(uri.toASCIIString());
        } catch (IllegalArgumentException invalid) {
            throw new OAuthException(400, "INVALID_REQUEST", "publicBaseUrl must be a valid http(s) origin.");
        }
    }

    private static String sanitizeAppName(String raw) {
        String clean = raw == null ? "" : raw.strip().replaceAll("[^A-Za-z0-9 ._-]", "");
        if (clean.isBlank()) {
            return "Evo Architecture Workbench";
        }
        return clean.length() > 34 ? clean.substring(0, 34).strip() : clean;
    }

    public static String validateReturnTo(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/";
        }
        if (raw.length() > 512) {
            throw new OAuthException(400, "INVALID_REQUEST", "returnTo path exceeds maximum length.");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new OAuthException(400, "INVALID_REQUEST", "returnTo contains forbidden control characters.");
            }
        }
        String trimmed = raw.strip();
        if (!trimmed.startsWith("/") || trimmed.startsWith("//") || trimmed.startsWith("/\\")
                || trimmed.contains("\\") || trimmed.contains("://")) {
            throw new OAuthException(400, "INVALID_REQUEST",
                    "returnTo must be a safe relative path starting with '/'.");
        }
        return trimmed;
    }

    public static String sanitizeReturnTo(String raw) {
        try {
            return validateReturnTo(raw);
        } catch (OAuthException unsafe) {
            return "/";
        }
    }

    public static String pkceS256(String codeVerifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String randomBase64Url(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String appendQueryFlag(String path, String param) {
        int hashIdx = path.indexOf('#');
        String beforeHash = hashIdx >= 0 ? path.substring(0, hashIdx) : path;
        String hash = hashIdx >= 0 ? path.substring(hashIdx) : "";
        String sep = beforeHash.contains("?") ? "&" : "?";
        return beforeHash + sep + param + hash;
    }

    private static List<String> parseScopes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,\\s]+")).map(String::strip).filter(s -> !s.isEmpty()).distinct().sorted().toList();
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.strip();
        if (b != null && !b.isBlank()) return b.strip();
        return "";
    }

    private static String str(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s.strip() : fallback;
    }

    private static int intVal(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        return fallback;
    }

    private static long longVal(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        return fallback;
    }
}
