package com.evolution.backend.auth;

import java.net.URI;
import java.util.*;

/**
 * Immutable authenticated user identity for multi-user Workbench and CLI sessions.
 * Never holds raw OAuth tokens, PATs, or refresh tokens.
 */
public record AuthenticatedUser(
        String userId,
        String login,
        String name,
        String avatarUrl,
        String email,
        List<String> orgs,
        List<String> scopes,
        AuthMethod authMethod,
        String host,
        String csrfToken,
        long authenticatedAtMs,
        long expiresAtMs,
        String role,
        boolean githubLinked,
        String githubLogin) {

    public static final String LOCAL_USER_ID = "local";

    public enum AuthMethod {
        OAUTH_WEB,
        OAUTH_DEVICE,
        SESSION_PAT,
        PERSONAL_ACCESS_TOKEN,
        LOCAL_PASSWORD,
        LOCAL_CLI_OPERATOR
    }

    public AuthenticatedUser(
            String userId,
            String login,
            String name,
            String avatarUrl,
            String email,
            List<String> orgs,
            List<String> scopes,
            AuthMethod authMethod,
            String host,
            String csrfToken,
            long authenticatedAtMs,
            long expiresAtMs) {
        this(userId, login, name, avatarUrl, email, orgs, scopes, authMethod, host, csrfToken,
                authenticatedAtMs, expiresAtMs,
                LOCAL_USER_ID.equals(userId) ? UserAccountStore.ROLE_ADMIN : UserAccountStore.ROLE_MEMBER,
                authMethod == AuthMethod.OAUTH_WEB || authMethod == AuthMethod.OAUTH_DEVICE
                        || authMethod == AuthMethod.SESSION_PAT,
                (authMethod == AuthMethod.OAUTH_WEB || authMethod == AuthMethod.OAUTH_DEVICE
                        || authMethod == AuthMethod.SESSION_PAT) ? (login == null ? "" : login.strip()) : "");
    }

    public AuthenticatedUser {
        userId = requireNonBlank(userId, "userId");
        login = requireNonBlank(login, "login");
        name = (name == null || name.isBlank()) ? login : name.strip();
        avatarUrl = safeAvatarUrl(avatarUrl);
        email = safeEmail(email, userId, login);
        orgs = List.copyOf(orgs == null ? List.of() : orgs.stream()
                .filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).distinct().sorted().toList());
        scopes = List.copyOf(scopes == null ? List.of() : scopes.stream()
                .filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).distinct().sorted().toList());
        authMethod = Objects.requireNonNull(authMethod, "authMethod");
        host = (host == null || host.isBlank()) ? "github.com" : host.strip().toLowerCase(Locale.ROOT);
        csrfToken = csrfToken == null ? "" : csrfToken;
        role = UserAccountStore.ROLE_ADMIN.equalsIgnoreCase(role)
                ? UserAccountStore.ROLE_ADMIN
                : UserAccountStore.ROLE_MEMBER;
        githubLogin = githubLinked && githubLogin != null ? githubLogin.strip() : "";
    }

    public boolean isAdmin() {
        return UserAccountStore.ROLE_ADMIN.equals(role);
    }

    public boolean isLocalFallback() {
        return authMethod == AuthMethod.LOCAL_CLI_OPERATOR;
    }

    public boolean isAuthenticated() {
        return authMethod != AuthMethod.LOCAL_CLI_OPERATOR;
    }

    public Optional<Long> numericGitHubId() {
        try {
            return Optional.of(Long.parseLong(userId));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    /** Public JSON representation for /api/v1/auth/me (never includes csrfToken or secrets). */
    public Map<String, Object> toPublicUserMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("userId", userId);
        map.put("login", login);
        map.put("displayName", name);
        map.put("name", name);
        map.put("avatarUrl", avatarUrl);
        map.put("email", email);
        map.put("role", role);
        map.put("githubLinked", githubLinked);
        map.put("githubLogin", githubLogin);
        map.put("orgs", orgs);
        map.put("scopes", scopes);
        map.put("authMethod", authMethod.name());
        map.put("host", host);
        return map;
    }

    public static AuthenticatedUser localOperator() {
        return new AuthenticatedUser(
                LOCAL_USER_ID,
                "local",
                "Local Operator",
                "",
                "evo-repair@local.invalid",
                List.of(),
                List.of(),
                AuthMethod.LOCAL_CLI_OPERATOR,
                "github.com",
                "",
                0L,
                0L);
    }

    public static String safeAvatarUrl(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 512) {
            return "";
        }
        String trimmed = raw.strip();
        if (trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\0') >= 0) {
            return "";
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
                return "";
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "";
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    public static String safeEmail(String email, String userId, String login) {
        if (email != null && !email.isBlank() && email.length() <= 254
                && email.indexOf('@') > 0
                && email.indexOf('\r') < 0 && email.indexOf('\n') < 0
                && email.indexOf('<') < 0 && email.indexOf('>') < 0) {
            return email.strip();
        }
        String cleanLogin = login == null ? "local" : login.replaceAll("[^A-Za-z0-9._-]", "");
        if (cleanLogin.isEmpty()) {
            cleanLogin = "user";
        }
        if (LOCAL_USER_ID.equals(userId)) {
            return "evo-repair@local.invalid";
        }
        return userId + "+" + cleanLogin + "@users.noreply.github.com";
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value.strip();
    }
}
