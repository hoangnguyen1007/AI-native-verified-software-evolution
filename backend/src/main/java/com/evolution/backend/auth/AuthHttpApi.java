package com.evolution.backend.auth;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.github.GitHubRestClient;
import com.evolution.backend.github.GitHubTokenBroker;
import com.evolution.backend.query.ApiLimits;
import com.evolution.backend.query.ArchitectureQueryApi;
import com.evolution.backend.query.BoundedJson;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * HTTP router for {@code /api/v1/auth/*} multi-user authentication, local account management,
 * encrypted in-app GitHub OAuth / 1-click App Manifest setup, session cookies, and constant-time CSRF validation.
 */
public final class AuthHttpApi {
    public static final String PREFIX = "/api/v1/auth/";
    public static final String CSRF_HEADER = "X-Evo-CSRF";
    private static final long COOKIE_MAX_AGE_SECONDS = 12L * 3600L;

    private final GitHubOAuthService oauthService;
    private final GitHubRestClient restClient;
    private final ApiLimits limits;
    private final BooleanSupplier webhookConfiguredSupplier;

    public AuthHttpApi(GitHubOAuthService oauthService, GitHubRestClient restClient, ApiLimits limits) {
        this(oauthService, restClient, limits, () -> false);
    }

    public AuthHttpApi(
            GitHubOAuthService oauthService,
            GitHubRestClient restClient,
            ApiLimits limits,
            BooleanSupplier webhookConfiguredSupplier) {
        this.oauthService = Objects.requireNonNull(oauthService, "oauthService");
        this.restClient = restClient;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.webhookConfiguredSupplier = webhookConfiguredSupplier == null ? () -> false : webhookConfiguredSupplier;
    }

    public GitHubOAuthService oauthService() {
        return oauthService;
    }

    public UserSessionStore sessionStore() {
        return oauthService.sessionStore();
    }

    public UserAccountStore accountStore() {
        return oauthService.accountStore();
    }

    public boolean isMultiUserMode() {
        return oauthService.config().multiUserMode();
    }

    public static String allowedMethods(String path) {
        if (path.equals("/api/v1/auth/config")
                || path.equals("/api/v1/auth/me")
                || path.equals("/api/v1/auth/github/login")
                || path.equals("/api/v1/auth/github/callback")
                || path.equals("/api/v1/auth/github/manifest/callback")
                || path.equals("/api/v1/auth/users")) {
            return "GET";
        }
        if (path.equals("/api/v1/auth/github/setup")) {
            return "GET, POST, DELETE";
        }
        if (path.equals("/api/v1/auth/tokens")) {
            return "GET, POST";
        }
        if (path.startsWith("/api/v1/auth/tokens/")) {
            return "DELETE";
        }
        return "POST";
    }

    /** Extracts the {@code evo_session} cookie value from raw Cookie header(s). */
    public static Optional<String> extractSessionCookie(List<String> cookieHeaders) {
        if (cookieHeaders == null || cookieHeaders.isEmpty()) {
            return Optional.empty();
        }
        for (String header : cookieHeaders) {
            if (header == null || header.isBlank()) continue;
            for (String part : header.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String name = part.substring(0, eq).strip();
                if (UserSessionStore.SESSION_COOKIE_NAME.equals(name)) {
                    String value = part.substring(eq + 1).strip();
                    if (!value.isEmpty() && value.length() <= 256
                            && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
                        return Optional.of(value);
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static boolean isCsrfValid(String expected, String supplied) {
        if (expected == null || expected.isBlank() || supplied == null || supplied.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.strip().getBytes(StandardCharsets.UTF_8));
    }

    public String sessionCookieHeader(String sessionId) {
        return sessionCookieHeader(sessionId, RequestContext.currentServerOrigin());
    }

    public String sessionCookieHeader(String sessionId, String serverOrigin) {
        StringBuilder sb = new StringBuilder();
        sb.append(UserSessionStore.SESSION_COOKIE_NAME).append('=').append(sessionId)
                .append("; Path=/; HttpOnly; SameSite=Lax; Max-Age=").append(COOKIE_MAX_AGE_SECONDS);
        if (isSecureCookieOrigin(serverOrigin)) {
            sb.append("; Secure");
        }
        return sb.toString();
    }

    public String clearSessionCookieHeader() {
        return clearSessionCookieHeader(RequestContext.currentServerOrigin());
    }

    public String clearSessionCookieHeader(String serverOrigin) {
        StringBuilder sb = new StringBuilder();
        sb.append(UserSessionStore.SESSION_COOKIE_NAME).append("=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        if (isSecureCookieOrigin(serverOrigin)) {
            sb.append("; Secure");
        }
        return sb.toString();
    }

    private boolean isSecureCookieOrigin(String serverOrigin) {
        return oauthService.config().isHttpsPublicOrigin()
                || (serverOrigin != null && serverOrigin.strip().toLowerCase(Locale.ROOT).startsWith("https://"));
    }

    public static Optional<String> extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Optional.empty();
        }
        String trimmed = authorizationHeader.strip();
        if (trimmed.length() <= 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = trimmed.substring(7).strip();
        if (token.isEmpty() || token.length() > 256
                || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0 || token.indexOf(' ') >= 0) {
            return Optional.empty();
        }
        return Optional.of(token);
    }

    /** Resolves an active request security context from the incoming Cookie header. */
    public RequestContext.ActiveRequest resolveRequestContext(List<String> cookieHeaders) {
        return resolveRequestContext(cookieHeaders, null);
    }

    /**
     * Resolves an active request security context from the incoming Cookie header or scoped
     * {@code Authorization: Bearer <sessionId>} header (used by operator-configured MCP bridges).
     */
    public RequestContext.ActiveRequest resolveRequestContext(List<String> cookieHeaders, String authorizationHeader) {
        Optional<String> cookieSid = extractSessionCookie(cookieHeaders);
        boolean viaCookie = cookieSid.isPresent();
        Optional<String> sidOpt = viaCookie ? cookieSid : extractBearerToken(authorizationHeader);
        if (sidOpt.isPresent()) {
            String sid = sidOpt.orElseThrow();
            if (!viaCookie && sid.startsWith(UserAccountStore.PAT_PREFIX)) {
                UserAccountStore accounts = oauthService.accountStore();
                if (accounts != null) {
                    var patOpt = accounts.authenticatePersonalAccessToken(sid);
                    if (patOpt.isPresent()) {
                        var resolved = patOpt.orElseThrow();
                        var acc = resolved.account();
                        var pat = resolved.token();
                        var patUser = new AuthenticatedUser(
                                acc.userId(),
                                acc.username(),
                                acc.displayName(),
                                acc.githubAvatarUrl(),
                                acc.email(),
                                List.of(),
                                pat.scopes(),
                                AuthenticatedUser.AuthMethod.PERSONAL_ACCESS_TOKEN,
                                oauthService.config().host(),
                                "",
                                pat.createdAtMs(),
                                pat.expiresAtMs(),
                                acc.role(),
                                acc.githubLinked(),
                                acc.githubLogin());
                        return new RequestContext.ActiveRequest(
                                Optional.empty(), patUser, false, isMultiUserMode(), host -> Optional.empty());
                    }
                }
                return unauthenticatedContext();
            }
            var viewOpt = oauthService.sessionStore().get(sid);
            if (viewOpt.isPresent()) {
                var view = viewOpt.orElseThrow();
                AuthenticatedUser user = view.user();
                UserAccountStore accounts = oauthService.accountStore();
                if (accounts != null) {
                    var accOpt = accounts.findById(user.userId());
                    if (accOpt.isPresent()) {
                        var acc = accOpt.orElseThrow();
                        if (acc.disabled()) {
                            oauthService.sessionStore().invalidate(sid);
                            return unauthenticatedContext();
                        }
                        user = syncUserWithAccount(user, acc);
                    }
                }
                RequestContext.TokenResolver resolver = host -> oauthService.sessionStore()
                        .resolveToken(sid, host, oauthService::refreshAccessToken);
                return new RequestContext.ActiveRequest(
                        Optional.of(sid), user, viaCookie, isMultiUserMode(), resolver);
            }
        }
        return unauthenticatedContext();
    }

    private RequestContext.ActiveRequest unauthenticatedContext() {
        return new RequestContext.ActiveRequest(
                Optional.empty(),
                AuthenticatedUser.localOperator(),
                false,
                isMultiUserMode(),
                host -> Optional.empty());
    }

    private boolean canConfigureGitHubSetup(Optional<RequestContext.ActiveRequest> active) {
        UserAccountStore accounts = oauthService.accountStore();
        boolean hasUsers = accounts != null && accounts.hasRegisteredUsers();
        if (active.isPresent() && active.orElseThrow().user().isAuthenticated()) {
            return active.orElseThrow().user().isAdmin();
        }
        return !hasUsers;
    }

    private void requireCanConfigureGitHubSetup(Optional<RequestContext.ActiveRequest> active) {
        UserAccountStore accounts = oauthService.accountStore();
        boolean hasUsers = accounts != null && accounts.hasRegisteredUsers();
        if (active.isPresent() && active.orElseThrow().user().isAuthenticated()) {
            if (!active.orElseThrow().user().isAdmin()) {
                throw new GitHubOAuthService.OAuthException(403, "ADMIN_REQUIRED",
                        "Administrator privileges are required to modify GitHub OAuth configuration.");
            }
            return;
        }
        if (hasUsers) {
            throw new GitHubOAuthService.OAuthException(401, "AUTH_REQUIRED",
                    "Sign in as an administrator to configure GitHub OAuth.");
        }
    }

    private AuthenticatedUser requireAuthenticatedUser(Optional<RequestContext.ActiveRequest> active) {
        if (active.isEmpty() || !active.orElseThrow().user().isAuthenticated()) {
            throw new GitHubOAuthService.OAuthException(401, "AUTH_REQUIRED", "Authentication is required.");
        }
        return active.orElseThrow().user();
    }

    private AuthenticatedUser requireAdminUser(Optional<RequestContext.ActiveRequest> active) {
        AuthenticatedUser user = requireAuthenticatedUser(active);
        if (!user.isAdmin()) {
            throw new GitHubOAuthService.OAuthException(403, "ADMIN_REQUIRED",
                    "Administrator privileges are required for this operation.");
        }
        return user;
    }

    /** Ensures a PAT bearer caller holds {@code api:write} before mutating account, token, or admin state. */
    private static void requireWriteScopeIfPat(AuthenticatedUser caller) {
        if (caller.authMethod() == AuthenticatedUser.AuthMethod.PERSONAL_ACCESS_TOKEN
                && !caller.scopes().contains("api:write")) {
            throw new GitHubOAuthService.OAuthException(403, "INSUFFICIENT_TOKEN_SCOPE",
                    "Personal access token requires 'api:write' scope for mutating operations.");
        }
    }

    public ArchitectureQueryApi.Response handle(
            String method,
            String rawPath,
            String rawQuery,
            String contentType,
            byte[] body,
            String serverOrigin,
            BooleanSupplier cancelled) {
        try {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return error(409, "REQUEST_CANCELLED", "Request cancelled.");
            }
            if (body != null && body.length > limits.requestBytes()) {
                return error(413, "REQUEST_TOO_LARGE", "Request exceeds transport limit.");
            }
            BooleanSupplier cancelCheck = cancelled == null ? () -> false : cancelled;
            var active = RequestContext.current();
            if (active.isPresent()
                    && active.orElseThrow().user().authMethod() == AuthenticatedUser.AuthMethod.PERSONAL_ACCESS_TOKEN
                    && !ArchitectureQueryApi.isPatAuthorized(active.orElseThrow().user().scopes(), method, rawPath)) {
                return error(403, "INSUFFICIENT_TOKEN_SCOPE",
                        "Personal access token lacks the required scope for this authentication endpoint.");
            }

            if (rawPath.equals("/api/v1/auth/config")) {
                requireMethod(method, "GET");
                requireEmpty(body);
                return ok(200, oauthService.publicConfig(serverOrigin, canConfigureGitHubSetup(active)), Map.of());
            }

            if (rawPath.equals("/api/v1/auth/me")) {
                requireMethod(method, "GET");
                requireEmpty(body);
                var user = active.map(RequestContext.ActiveRequest::user).orElseGet(AuthenticatedUser::localOperator);
                var data = new LinkedHashMap<String, Object>();
                data.put("authenticated", user.isAuthenticated());
                data.put("multiUserMode", isMultiUserMode());
                data.put("user", user.toPublicUserMap());
                data.put("csrfToken", user.isAuthenticated() ? user.csrfToken() : "");
                if (restClient != null) {
                    restClient.lastRateLimit().ifPresent(rl -> data.put("rateLimit", rl.toMap()));
                }
                return ok(200, data, Map.of());
            }

            if (rawPath.equals("/api/v1/auth/register")) {
                requireMethod(method, "POST");
                UserAccountStore accounts = requireAccountStore();
                var input = decode(body, contentType);
                checkKeys(input, "email", "username", "displayName", "password");
                String email = requiredText(input, "email");
                Optional<String> username = optionalText(input, "username");
                Optional<String> displayName = optionalText(input, "displayName");
                String passwordStr = requiredRawSecret(input, "password");
                UserAccountStore.Account created = accounts.register(email, username, displayName, passwordStr.toCharArray());
                var session = createLocalPasswordSession(created);
                return ok(201, Map.of(
                                "authenticated", true,
                                "user", session.user().toPublicUserMap(),
                                "csrfToken", session.user().csrfToken()),
                        Map.of("Set-Cookie", List.of(sessionCookieHeader(session.sessionId(), serverOrigin))));
            }

            if (rawPath.equals("/api/v1/auth/login")) {
                requireMethod(method, "POST");
                UserAccountStore accounts = requireAccountStore();
                var input = decode(body, contentType);
                checkKeys(input, "identifier", "password");
                String identifier = requiredText(input, "identifier");
                String passwordStr = requiredRawSecret(input, "password");
                UserAccountStore.Account acc = accounts.authenticate(identifier, passwordStr.toCharArray());
                var session = createLocalPasswordSession(acc);
                return ok(200, Map.of(
                                "authenticated", true,
                                "user", session.user().toPublicUserMap(),
                                "csrfToken", session.user().csrfToken()),
                        Map.of("Set-Cookie", List.of(sessionCookieHeader(session.sessionId(), serverOrigin))));
            }

            if (rawPath.equals("/api/v1/auth/profile")) {
                requireMethod(method, "POST");
                AuthenticatedUser caller = requireAuthenticatedUser(active);
                requireWriteScopeIfPat(caller);
                UserAccountStore accounts = requireAccountStore();
                var input = decode(body, contentType);
                checkKeys(input, "displayName", "currentPassword", "newPassword");
                Optional<String> displayName = optionalText(input, "displayName");
                Optional<char[]> currentPw = optionalRawSecret(input, "currentPassword").map(String::toCharArray);
                Optional<char[]> newPw = optionalRawSecret(input, "newPassword").map(String::toCharArray);
                UserAccountStore.Account updated = accounts.updateProfile(caller.userId(), displayName, currentPw, newPw);
                oauthService.sessionStore().updateUserAcrossSessions(caller.userId(), u -> syncUserWithAccount(u, updated));
                AuthenticatedUser synced = syncUserWithAccount(caller, updated);
                return ok(200, Map.of("user", synced.toPublicUserMap()), Map.of());
            }

            if (rawPath.equals("/api/v1/auth/github/unlink")) {
                requireMethod(method, "POST");
                AuthenticatedUser caller = requireAuthenticatedUser(active);
                requireWriteScopeIfPat(caller);
                UserAccountStore accounts = oauthService.accountStore();
                UserAccountStore.Account updatedAcc = null;
                if (accounts != null && accounts.findById(caller.userId()).isPresent()) {
                    updatedAcc = accounts.unlinkGitHub(caller.userId());
                }
                final UserAccountStore.Account accRef = updatedAcc;
                Optional<String> sidOpt = active.flatMap(RequestContext.ActiveRequest::sessionId);
                java.util.function.UnaryOperator<AuthenticatedUser> unlinkUpdater = u -> {
                    AuthenticatedUser base = accRef != null ? syncUserWithAccount(u, accRef) : u;
                    return new AuthenticatedUser(
                            base.userId(), base.login(), base.name(), "", base.email(),
                            List.of(), List.of(), AuthenticatedUser.AuthMethod.LOCAL_PASSWORD,
                            base.host(), base.csrfToken(), base.authenticatedAtMs(), 0L,
                            base.role(), false, "");
                };
                var cleared = oauthService.sessionStore().clearGitHubTokensForUser(
                        caller.userId(), sidOpt.orElse(null), unlinkUpdater);
                AuthenticatedUser nextUser = cleared.map(UserSessionStore.SessionView::user)
                        .orElseGet(() -> unlinkUpdater.apply(caller));
                return ok(200, Map.of("unlinked", true, "user", nextUser.toPublicUserMap()), Map.of());
            }

            if (rawPath.equals("/api/v1/auth/users")) {
                requireMethod(method, "GET");
                requireEmpty(body);
                requireAdminUser(active);
                UserAccountStore accounts = requireAccountStore();
                var items = accounts.listUsers().stream().map(UserAccountStore.Account::toPublicMap).toList();
                return ok(200, Map.of("items", items), Map.of());
            }

            if (rawPath.startsWith("/api/v1/auth/users/") && rawPath.endsWith("/role")) {
                requireMethod(method, "POST");
                AuthenticatedUser adminCaller = requireAdminUser(active);
                requireWriteScopeIfPat(adminCaller);
                UserAccountStore accounts = requireAccountStore();
                String[] parts = rawPath.split("/", -1);
                if (parts.length != 7 || !"users".equals(parts[4]) || !"role".equals(parts[6])) {
                    return error(404, "NOT_FOUND", "Auth route not found.");
                }
                String targetUserId = parts[5];
                var input = decode(body, contentType);
                checkKeys(input, "role", "disabled");
                String role = requiredText(input, "role");
                Optional<Boolean> disabledOpt = input.containsKey("disabled") && input.get("disabled") != null
                        ? Optional.of(requireBoolean(input, "disabled"))
                        : Optional.empty();
                UserAccountStore.Account updated = accounts.updateRoleAndStatus(targetUserId, role, disabledOpt);
                if (updated.disabled()) {
                    oauthService.sessionStore().invalidateAllForUser(updated.userId());
                } else {
                    oauthService.sessionStore().updateUserAcrossSessions(updated.userId(), u -> syncUserWithAccount(u, updated));
                }
                return ok(200, Map.of("user", updated.toPublicMap()), Map.of());
            }

            if (rawPath.equals("/api/v1/auth/tokens")) {
                AuthenticatedUser caller = requireAuthenticatedUser(active);
                UserAccountStore accounts = requireAccountStore();
                if ("GET".equals(method)) {
                    requireEmpty(body);
                    var items = accounts.listPersonalAccessTokens(caller.userId()).stream()
                            .map(UserAccountStore.PersonalAccessToken::toPublicMap)
                            .toList();
                    return ok(200, Map.of("items", items, "allowedScopes", List.of("mcp:read", "api:read", "api:write")), Map.of());
                }
                if ("POST".equals(method)) {
                    requireWriteScopeIfPat(caller);
                    var input = decode(body, contentType);
                    checkKeys(input, "label", "scopes", "expiresInDays");
                    String label = requiredText(input, "label");
                    List<String> scopes = List.of("mcp:read");
                    if (input.containsKey("scopes") && input.get("scopes") != null) {
                        var rawList = GraphJson.array(input.get("scopes"));
                        var parsed = new ArrayList<String>(rawList.size());
                        for (Object item : rawList) parsed.add(GraphJson.string(item));
                        scopes = parsed;
                    }
                    Optional<Integer> expiresInDays = Optional.empty();
                    if (input.containsKey("expiresInDays") && input.get("expiresInDays") != null) {
                        if (!(input.get("expiresInDays") instanceof java.math.BigDecimal bd)) {
                            throw new IllegalArgumentException("Expected integer for expiresInDays");
                        }
                        try {
                            expiresInDays = Optional.of(bd.intValueExact());
                        } catch (ArithmeticException bad) {
                            throw new IllegalArgumentException("Invalid expiresInDays");
                        }
                    }
                    var minted = accounts.createPersonalAccessToken(caller.userId(), label, scopes, expiresInDays);
                    var resp = new LinkedHashMap<String, Object>();
                    resp.put("token", minted.plaintextToken());
                    resp.put("metadata", minted.metadata().toPublicMap());
                    resp.put("notice", "Store this token now — it will never be shown again.");
                    return ok(201, Collections.unmodifiableMap(resp), Map.of("Cache-Control", List.of("no-store")));
                }
                return error(405, "METHOD_NOT_ALLOWED", "Use GET or POST.");
            }

            if (rawPath.startsWith("/api/v1/auth/tokens/")) {
                requireMethod(method, "DELETE");
                requireEmpty(body);
                AuthenticatedUser caller = requireAuthenticatedUser(active);
                requireWriteScopeIfPat(caller);
                UserAccountStore accounts = requireAccountStore();
                String[] parts = rawPath.split("/", -1);
                if (parts.length != 6 || !"tokens".equals(parts[4]) || parts[5].isBlank()) {
                    return error(404, "NOT_FOUND", "Auth route not found.");
                }
                var revoked = accounts.revokePersonalAccessToken(parts[5], caller.userId(), caller.isAdmin());
                return ok(200, Map.of("revoked", true, "metadata", revoked.toPublicMap()), Map.of());
            }

            if (rawPath.equals("/api/v1/auth/github/setup")) {
                if ("GET".equals(method)) {
                    requireEmpty(body);
                    return ok(200, oauthService.setupView(serverOrigin, canConfigureGitHubSetup(active),
                            webhookConfiguredSupplier.getAsBoolean()), Map.of());
                }
                if ("POST".equals(method)) {
                    requireCanConfigureGitHubSetup(active);
                    active.map(RequestContext.ActiveRequest::user).filter(AuthenticatedUser::isAuthenticated)
                            .ifPresent(AuthHttpApi::requireWriteScopeIfPat);
                    var input = decode(body, contentType);
                    checkKeys(input, "clientId", "clientSecret", "host", "webhookSecret", "deviceFlowEnabled", "publicBaseUrl");
                    String clientId = requiredText(input, "clientId");
                    Optional<String> clientSecret = optionalText(input, "clientSecret");
                    Optional<String> host = optionalText(input, "host");
                    Optional<String> webhookSecret = optionalText(input, "webhookSecret");
                    Optional<Boolean> deviceFlow = input.containsKey("deviceFlowEnabled") && input.get("deviceFlowEnabled") != null
                            ? Optional.of(requireBoolean(input, "deviceFlowEnabled"))
                            : Optional.empty();
                    Optional<String> publicBaseUrl = optionalText(input, "publicBaseUrl");
                    var view = oauthService.saveInAppSetup(
                            clientId, clientSecret, host, webhookSecret, deviceFlow,
                            Optional.empty(), Optional.empty(), Optional.empty(), publicBaseUrl,
                            serverOrigin, true, webhookConfiguredSupplier.getAsBoolean());
                    return ok(200, view, Map.of());
                }
                if ("DELETE".equals(method)) {
                    requireCanConfigureGitHubSetup(active);
                    active.map(RequestContext.ActiveRequest::user).filter(AuthenticatedUser::isAuthenticated)
                            .ifPresent(AuthHttpApi::requireWriteScopeIfPat);
                    var view = oauthService.clearInAppSetup(serverOrigin, true, webhookConfiguredSupplier.getAsBoolean());
                    return ok(200, view, Map.of());
                }
                return error(405, "METHOD_NOT_ALLOWED", "Use GET, POST, or DELETE.");
            }

            if (rawPath.equals("/api/v1/auth/github/manifest/prepare")) {
                requireMethod(method, "POST");
                requireCanConfigureGitHubSetup(active);
                active.map(RequestContext.ActiveRequest::user).filter(AuthenticatedUser::isAuthenticated)
                        .ifPresent(AuthHttpApi::requireWriteScopeIfPat);
                Map<String, Object> input = (body == null || body.length == 0) ? Map.of() : decode(body, contentType);
                checkKeys(input, "appName", "host", "publicBaseUrl");
                var prepared = oauthService.prepareAppManifest(
                        serverOrigin,
                        optionalText(input, "appName"),
                        optionalText(input, "host"),
                        optionalText(input, "publicBaseUrl"));
                return ok(200, prepared.toView(), Map.of());
            }

            if (rawPath.equals("/api/v1/auth/github/manifest/callback")) {
                requireMethod(method, "GET");
                requireEmpty(body);
                String code = queryParam(rawQuery, "code").orElse("");
                String state = queryParam(rawQuery, "state").orElse("");
                var outcome = oauthService.completeAppManifestCallback(code, state, cancelCheck);
                return new ArchitectureQueryApi.Response(302, new byte[0], Map.of(
                        "Location", List.of(outcome.redirectTo()),
                        "Cache-Control", List.of("no-store")));
            }

            if (rawPath.equals("/api/v1/auth/github/login")) {
                requireMethod(method, "GET");
                requireEmpty(body);
                Optional<String> returnTo = queryParam(rawQuery, "returnTo");
                URI authorizeUri = oauthService.startWebLogin(serverOrigin, returnTo);
                return new ArchitectureQueryApi.Response(302, new byte[0], Map.of(
                        "Location", List.of(authorizeUri.toASCIIString()),
                        "Cache-Control", List.of("no-store")));
            }

            if (rawPath.equals("/api/v1/auth/github/callback")) {
                requireMethod(method, "GET");
                requireEmpty(body);
                Optional<String> oauthError = queryParam(rawQuery, "error");
                if (oauthError.isPresent()) {
                    return new ArchitectureQueryApi.Response(302, new byte[0], Map.of(
                            "Location", List.of("/?auth=denied"),
                            "Cache-Control", List.of("no-store")));
                }
                String code = queryParam(rawQuery, "code").orElse("");
                String state = queryParam(rawQuery, "state").orElse("");
                var outcome = oauthService.completeWebCallback(code, state, cancelCheck);
                return new ArchitectureQueryApi.Response(302, new byte[0], Map.of(
                        "Location", List.of(outcome.returnTo()),
                        "Set-Cookie", List.of(sessionCookieHeader(outcome.session().sessionId(), serverOrigin)),
                        "Cache-Control", List.of("no-store")));
            }

            if (rawPath.equals("/api/v1/auth/github/device/start")) {
                requireMethod(method, "POST");
                Map<String, Object> input = (body == null || body.length == 0) ? Map.of() : decode(body, contentType);
                checkKeys(input, "scopes", "host");
                var started = oauthService.startDeviceFlow(
                        optionalText(input, "scopes"),
                        optionalText(input, "host"),
                        cancelCheck);
                return ok(200, started.toView(), Map.of());
            }

            if (rawPath.equals("/api/v1/auth/github/device/poll")) {
                requireMethod(method, "POST");
                var input = decode(body, contentType);
                checkKeys(input, "deviceFlowId");
                String flowId = requiredText(input, "deviceFlowId");
                var poll = oauthService.pollDeviceFlow(flowId, cancelCheck);
                Map<String, List<String>> headers = poll.session().isPresent()
                        ? Map.of("Set-Cookie", List.of(sessionCookieHeader(poll.session().orElseThrow().sessionId(), serverOrigin)))
                        : Map.of();
                return ok(200, poll.toView(), headers);
            }

            if (rawPath.equals("/api/v1/auth/logout")) {
                requireMethod(method, "POST");
                boolean revoked = active.flatMap(RequestContext.ActiveRequest::sessionId)
                        .map(oauthService.sessionStore()::invalidate)
                        .orElse(false);
                return ok(200, Map.of("signedOut", true, "revoked", revoked),
                        Map.of("Set-Cookie", List.of(clearSessionCookieHeader(serverOrigin))));
            }

            if (rawPath.equals("/api/v1/auth/logout-all")) {
                requireMethod(method, "POST");
                int count = 0;
                if (active.isPresent() && active.orElseThrow().user().isAuthenticated()) {
                    requireWriteScopeIfPat(active.orElseThrow().user());
                    count = oauthService.sessionStore().invalidateAllForUser(active.orElseThrow().user().userId());
                }
                return ok(200, Map.of("signedOut", true, "revokedSessions", count),
                        Map.of("Set-Cookie", List.of(clearSessionCookieHeader(serverOrigin))));
            }

            return error(404, "NOT_FOUND", "Auth route not found.");
        } catch (UserAccountStore.AccountException err) {
            return error(err.status(), err.code(), err.getMessage());
        } catch (GitHubOAuthService.OAuthException err) {
            return error(err.status(), err.code(), err.getMessage());
        } catch (IllegalArgumentException invalid) {
            return error(400, "INVALID_REQUEST", GitHubTokenBroker.redact(invalid.getMessage()));
        } catch (RuntimeException unexpected) {
            return error(500, "INTERNAL_ERROR", "Internal authentication error.");
        }
    }

    private UserAccountStore requireAccountStore() {
        UserAccountStore accounts = oauthService.accountStore();
        if (accounts == null) {
            throw new GitHubOAuthService.OAuthException(503, "LOCAL_ACCOUNTS_UNAVAILABLE",
                    "Local multi-user account store is not enabled on this server.");
        }
        return accounts;
    }

    private UserSessionStore.SessionView createLocalPasswordSession(UserAccountStore.Account account) {
        long now = System.currentTimeMillis();
        var user = new AuthenticatedUser(
                account.userId(),
                account.username(),
                account.displayName(),
                account.githubAvatarUrl(),
                account.email(),
                List.of(),
                List.of(),
                AuthenticatedUser.AuthMethod.LOCAL_PASSWORD,
                oauthService.config().host(),
                oauthService.sessionStore().newCsrfToken(),
                now,
                0L,
                account.role(),
                account.githubLinked(),
                account.githubLogin());
        return oauthService.sessionStore().createSession(user, new char[0], null, 0L, 0L);
    }

    private static AuthenticatedUser syncUserWithAccount(AuthenticatedUser existing, UserAccountStore.Account acc) {
        String avatar = existing.avatarUrl().isBlank() ? acc.githubAvatarUrl() : existing.avatarUrl();
        return new AuthenticatedUser(
                acc.userId(),
                acc.username(),
                acc.displayName(),
                acc.githubLinked() ? avatar : "",
                acc.email(),
                acc.githubLinked() ? existing.orgs() : List.of(),
                acc.githubLinked() ? existing.scopes() : List.of(),
                existing.authMethod(),
                existing.host(),
                existing.csrfToken(),
                existing.authenticatedAtMs(),
                existing.expiresAtMs(),
                acc.role(),
                acc.githubLinked(),
                acc.githubLogin());
    }

    private ArchitectureQueryApi.Response ok(int status, Object data, Map<String, List<String>> extraHeaders) {
        byte[] canonicalData = BoundedJson.encode(data, limits.responseBytes());
        byte[] envelope = BoundedJson.encode(Map.of(
                "apiVersion", ArchitectureQueryApi.VERSION,
                "queryVersion", ArchitectureQuery.VERSION,
                "resultIdentity", ContentDigest.sha256(canonicalData),
                "data", data), limits.responseBytes());
        return new ArchitectureQueryApi.Response(status, envelope, extraHeaders);
    }

    private ArchitectureQueryApi.Response error(int status, String code, String message) {
        var errObj = new LinkedHashMap<String, Object>();
        errObj.put("code", code);
        errObj.put("message", GitHubTokenBroker.redact(message == null ? code : message));
        return new ArchitectureQueryApi.Response(status, BoundedJson.encode(Map.of(
                "apiVersion", ArchitectureQueryApi.VERSION,
                "queryVersion", ArchitectureQuery.VERSION,
                "error", errObj), limits.responseBytes()));
    }

    private Map<String, Object> decode(byte[] body, String contentType) {
        if (contentType == null) {
            throw new GitHubOAuthService.OAuthException(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type application/json required.");
        }
        String norm = contentType.toLowerCase(Locale.ROOT).replace(" ", "");
        if (!norm.equals("application/json") && !norm.equals("application/json;charset=utf-8")) {
            throw new GitHubOAuthService.OAuthException(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type application/json required.");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            return GraphJson.object(GraphJson.read(text,
                    new GraphJson.Limits(limits.requestBytes(), limits.jsonDepth(), limits.jsonValues())));
        } catch (CharacterCodingException badUtf8) {
            throw new IllegalArgumentException("Invalid UTF-8 request body");
        }
    }

    private static void requireMethod(String actual, String required) {
        if (!required.equals(actual)) {
            throw new GitHubOAuthService.OAuthException(405, "METHOD_NOT_ALLOWED", "Use " + required + ".");
        }
    }

    private static void requireEmpty(byte[] body) {
        if (body != null && body.length > 0) {
            throw new IllegalArgumentException("Request body not allowed on GET");
        }
    }

    private static void checkKeys(Map<String, Object> map, String... allowed) {
        Set<String> valid = Set.of(allowed);
        for (String key : map.keySet()) {
            if (!valid.contains(key)) {
                throw new IllegalArgumentException("Unexpected field: " + key);
            }
        }
    }

    private static String requiredText(Map<String, Object> map, String key) {
        return optionalText(map, key).orElseThrow(() -> new IllegalArgumentException("Missing field: " + key));
    }

    private static Optional<String> optionalText(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return Optional.empty();
        if (!(val instanceof String s)) throw new IllegalArgumentException("Expected string for field: " + key);
        String trimmed = s.strip();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    private static String requiredRawSecret(Map<String, Object> map, String key) {
        return optionalRawSecret(map, key).orElseThrow(() -> new IllegalArgumentException("Missing field: " + key));
    }

    private static Optional<String> optionalRawSecret(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return Optional.empty();
        if (!(val instanceof String s)) throw new IllegalArgumentException("Expected string for field: " + key);
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }

    private static boolean requireBoolean(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (!(val instanceof Boolean b)) throw new IllegalArgumentException("Expected boolean for field: " + key);
        return b;
    }

    private static Optional<String> queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) return Optional.empty();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            if (name.equals(URLDecoder.decode(k, StandardCharsets.UTF_8))) {
                String decoded = URLDecoder.decode(v, StandardCharsets.UTF_8).strip();
                return decoded.isEmpty() ? Optional.empty() : Optional.of(decoded);
            }
        }
        return Optional.empty();
    }
}
