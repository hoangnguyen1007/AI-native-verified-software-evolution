package com.evolution.backend.github;

import com.evolution.analysis.filesystem.GitHostPolicy;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * RAM-only GitHub credential broker.
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Tokens are NEVER persisted to disk, SQLite, catalogs, logs, HTTP responses, or error diagnostics.</li>
 *   <li>Read-only {@link GitHostPolicy.Credentials} bridge serves HTTPS Git clones via pinned allowlisted hosts only.</li>
 *   <li>Write authorization is isolated behind {@link #writeGitAuthorization(String)} for explicitly confirmed PR pushes.</li>
 * </ul>
 */
public final class GitHubTokenBroker implements AutoCloseable {
    public static final String SOURCE_NONE = "NONE";
    public static final String SOURCE_SESSION_PAT = "SESSION_PAT";
    public static final String SOURCE_ENV_EVO = "ENV_EVO_GITHUB_TOKEN";
    public static final String SOURCE_ENV_GITHUB = "ENV_GITHUB_TOKEN";
    public static final String SOURCE_ENV_GH = "ENV_GH_TOKEN";
    public static final String SOURCE_GH_CLI = "GH_CLI";

    private static final long GH_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static final long GH_CLI_TIMEOUT_MS = 3_000L;

    private static final Pattern SECRET_PATTERNS = Pattern.compile(
            "(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{10,}"
                    + "|github_pat_[A-Za-z0-9_]{16,}"
                    + "|(?i)(?:Bearer|Basic)\\s+[A-Za-z0-9+/=._-]{8,}"
                    + "|x-access-token:[^@\\s]+@");

    public record ResolvedToken(String token, String source) {
        public ResolvedToken {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(source, "source");
        }
    }

    private record CachedGhToken(String host, String token, long expiresAtNanos) {
    }

    private final Function<String, String> envReader;
    private final Function<String, Optional<String>> ghCliReader;
    private volatile boolean multiUserMode;
    private char[] sessionToken;
    private String sessionHost;
    private CachedGhToken cachedGh;

    public GitHubTokenBroker() {
        this(System::getenv, GitHubTokenBroker::readGhCliToken);
    }

    public GitHubTokenBroker(Function<String, String> envReader, Function<String, Optional<String>> ghCliReader) {
        this.envReader = Objects.requireNonNull(envReader, "envReader");
        this.ghCliReader = Objects.requireNonNull(ghCliReader, "ghCliReader");
    }

    public void setMultiUserMode(boolean enabled) {
        this.multiUserMode = enabled;
    }

    public boolean isMultiUserMode() {
        return multiUserMode;
    }

    /** Stores a RAM-only session PAT for the active Workbench process; never touches disk. */
    public synchronized void setSessionToken(char[] token, Optional<String> host) {
        Objects.requireNonNull(token, "token");
        String cleaned = new String(token).strip();
        Arrays.fill(token, '\0');
        if (cleaned.isEmpty() || cleaned.length() > 512
                || cleaned.indexOf('\r') >= 0 || cleaned.indexOf('\n') >= 0 || cleaned.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid GitHub token");
        }
        String targetHost = host == null || host.isEmpty()
                ? GitHubCoordinates.DEFAULT_HOST
                : host.orElseThrow().strip().toLowerCase(Locale.ROOT);
        if (!GitHubCoordinates.allowedWebHosts().contains(targetHost)) {
            throw new IllegalArgumentException("Host not permitted by GitHub allowlist");
        }
        zeroSessionTokenLocked();
        this.sessionToken = cleaned.toCharArray();
        this.sessionHost = targetHost;
    }

    public synchronized void clearSessionToken() {
        zeroSessionTokenLocked();
        this.cachedGh = null;
    }

    public synchronized boolean hasSessionToken() {
        return sessionToken != null && sessionToken.length > 0;
    }

    public Optional<ResolvedToken> resolve(String host) {
        String normalized = (host == null || host.isBlank())
                ? GitHubCoordinates.DEFAULT_HOST
                : host.strip().toLowerCase(Locale.ROOT);
        if (!GitHubCoordinates.isAllowedHost(normalized)) {
            return Optional.empty();
        }
        String webHost = GitHubCoordinates.DEFAULT_API_HOST.equals(normalized)
                ? GitHubCoordinates.DEFAULT_HOST
                : normalized;
        // Per-user session isolation: an authenticated user request ONLY ever sees its own session token.
        var activeRequest = com.evolution.backend.auth.RequestContext.current();
        if (activeRequest.isPresent()) {
            var req = activeRequest.orElseThrow();
            if (req.user().isAuthenticated()) {
                return req.tokenResolver().resolve(webHost);
            }
            if (req.multiUserMode() || this.multiUserMode) {
                return Optional.empty();
            }
        } else if (this.multiUserMode) {
            return Optional.empty();
        }
        synchronized (this) {
            if (sessionToken != null && sessionToken.length > 0
                    && (sessionHost == null || sessionHost.equals(webHost))) {
                return Optional.of(new ResolvedToken(new String(sessionToken), SOURCE_SESSION_PAT));
            }
        }
        String evoTok = safeEnv("EVO_GITHUB_TOKEN");
        if (evoTok != null) {
            return Optional.of(new ResolvedToken(evoTok, SOURCE_ENV_EVO));
        }
        String ghTok = safeEnv("GITHUB_TOKEN");
        if (ghTok != null) {
            return Optional.of(new ResolvedToken(ghTok, SOURCE_ENV_GITHUB));
        }
        String altTok = safeEnv("GH_TOKEN");
        if (altTok != null) {
            return Optional.of(new ResolvedToken(altTok, SOURCE_ENV_GH));
        }
        if (Boolean.getBoolean("evo.github.disableGhCli")) {
            return Optional.empty();
        }
        long now = System.nanoTime();
        synchronized (this) {
            if (cachedGh != null && cachedGh.host().equals(webHost) && now < cachedGh.expiresAtNanos()) {
                return cachedGh.token() == null
                        ? Optional.empty()
                        : Optional.of(new ResolvedToken(cachedGh.token(), SOURCE_GH_CLI));
            }
        }
        Optional<String> cli = Optional.empty();
        try {
            cli = ghCliReader.apply(webHost).map(String::strip).filter(GitHubTokenBroker::isSafeTokenValue);
        } catch (RuntimeException ignored) {
            cli = Optional.empty();
        }
        synchronized (this) {
            cachedGh = new CachedGhToken(webHost, cli.orElse(null), System.nanoTime() + GH_CACHE_TTL_NANOS);
        }
        return cli.map(tok -> new ResolvedToken(tok, SOURCE_GH_CLI));
    }

    /**
     * Read-only {@link GitHostPolicy.Credentials} bridge for {@code GitSnapshotAcquirer} HTTPS fetches/clones.
     * Enforces host allowlist and returns a typed read-only {@link GitHostPolicy.CredentialReference}.
     */
    public GitHostPolicy.Credentials readOnlyGitCredentials() {
        return new GitHostPolicy.Credentials() {
            @Override
            public Optional<String> authorization(URI endpoint) {
                if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
                    return Optional.empty();
                }
                String host = endpoint.getHost().toLowerCase(Locale.ROOT);
                if (!GitHubCoordinates.allowedWebHosts().contains(host)) {
                    return Optional.empty();
                }
                String rawPath = endpoint.getRawPath();
                if (rawPath != null && rawPath.startsWith("/api/v3")) {
                    return Optional.empty();
                }
                return resolve(host).map(t -> basicXAccessToken(t.token()));
            }

            @Override
            public Optional<GitHostPolicy.CredentialReference> reference(URI endpoint) {
                if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
                    return Optional.empty();
                }
                String host = endpoint.getHost().toLowerCase(Locale.ROOT);
                if (!GitHubCoordinates.allowedWebHosts().contains(host)) {
                    return Optional.empty();
                }
                return resolve(host).map(t -> new GitHostPolicy.CredentialReference("github-broker-ro", true));
            }
        };
    }

    /**
     * Write-capable HTTPS Authorization header ONLY for explicitly user-confirmed repair branch pushes.
     */
    Optional<String> writeGitAuthorization(String host) {
        if (host == null || !GitHubCoordinates.allowedWebHosts().contains(host.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return resolve(host).map(t -> basicXAccessToken(t.token()));
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : "";
        }
        return SECRET_PATTERNS.matcher(text).replaceAll("[REDACTED]");
    }

    private static String basicXAccessToken(String token) {
        String encoded = Base64.getEncoder()
                .encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private String safeEnv(String name) {
        String val = envReader.apply(name);
        if (val == null) {
            return null;
        }
        String trimmed = val.strip();
        return isSafeTokenValue(trimmed) ? trimmed : null;
    }

    private static boolean isSafeTokenValue(String value) {
        return value != null && !value.isEmpty() && value.length() <= 512
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0 && value.indexOf('\0') < 0;
    }

    private static Optional<String> readGhCliToken(String hostname) {
        if (!GitHubCoordinates.allowedWebHosts().contains(hostname)) {
            return Optional.empty();
        }
        Process process = null;
        try {
            var pb = new ProcessBuilder("gh", "auth", "token", "--hostname", hostname);
            pb.redirectErrorStream(false);
            var inherited = new HashMap<>(pb.environment());
            pb.environment().clear();
            for (String key : List.of("PATH", "Path", "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
                    "XDG_CONFIG_HOME", "GH_CONFIG_DIR", "SystemRoot", "SYSTEMROOT", "TEMP", "TMP")) {
                if (inherited.containsKey(key)) {
                    pb.environment().put(key, inherited.get(key));
                }
            }
            pb.environment().put("GH_PROMPT_DISABLED", "1");
            process = pb.start();
            byte[] outBytes = process.getInputStream().readNBytes(1024);
            boolean finished = process.waitFor(GH_CLI_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished || process.exitValue() != 0) {
                return Optional.empty();
            }
            String token = new String(outBytes, StandardCharsets.UTF_8).strip();
            return isSafeTokenValue(token) ? Optional.of(token) : Optional.empty();
        } catch (IOException | InterruptedException unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void zeroSessionTokenLocked() {
        if (sessionToken != null) {
            Arrays.fill(sessionToken, '\0');
            sessionToken = null;
        }
        sessionHost = null;
    }

    @Override
    public synchronized void close() {
        zeroSessionTokenLocked();
        cachedGh = null;
    }
}
