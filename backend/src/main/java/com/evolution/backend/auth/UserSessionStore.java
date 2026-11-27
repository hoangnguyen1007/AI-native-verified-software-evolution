package com.evolution.backend.auth;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.github.GitHubCoordinates;
import com.evolution.backend.github.GitHubTokenBroker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Multi-user session &amp; per-user credential store.
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Tokens live in RAM {@code char[]} by default and are zeroed on logout, expiry, eviction, or close.</li>
 *   <li>Optional disk persistence is ONLY enabled when an explicit 32+ byte {@code EVO_SESSION_SECRET} is
 *       provided and writes exclusively AES-256-GCM ciphertext with a fresh 96-bit IV per save.</li>
 *   <li>Zero plaintext tokens ever reach disk, logs, graphs, or HTTP responses.</li>
 * </ul>
 */
public final class UserSessionStore implements AutoCloseable {
    public static final String SESSION_COOKIE_NAME = "evo_session";
    public static final String PERSIST_SCHEMA = "evo.sessions.aes256gcm:v1";
    public static final int MAX_SESSIONS = 1_024;
    public static final long DEFAULT_IDLE_TTL_MS = TimeUnit.HOURS.toMillis(12);
    public static final long DEFAULT_MAX_TTL_MS = TimeUnit.HOURS.toMillis(24);
    public static final long REFRESH_WINDOW_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final System.Logger LOG = System.getLogger(UserSessionStore.class.getName());

    public record RefreshedToken(
            char[] accessToken,
            char[] refreshToken,
            long tokenExpiresAtMs,
            long refreshExpiresAtMs,
            List<String> scopes) {
    }

    @FunctionalInterface
    public interface TokenRefresher {
        Optional<RefreshedToken> refresh(String host, char[] refreshToken);
    }

    public record SessionView(String sessionId, AuthenticatedUser user) {
    }

    private static final class SessionEntry {
        final String sessionId;
        volatile AuthenticatedUser user;
        char[] accessToken;
        char[] refreshToken;
        long tokenExpiresAtMs;
        long refreshExpiresAtMs;
        final long createdAtMs;
        volatile long lastSeenAtMs;
        volatile boolean wiped;
        final Object refreshLock = new Object();

        SessionEntry(String sessionId, AuthenticatedUser user, char[] accessToken, char[] refreshToken,
                long tokenExpiresAtMs, long refreshExpiresAtMs, long nowMs) {
            this.sessionId = sessionId;
            this.user = user;
            this.accessToken = accessToken == null ? null : accessToken.clone();
            this.refreshToken = refreshToken == null ? null : refreshToken.clone();
            this.tokenExpiresAtMs = tokenExpiresAtMs;
            this.refreshExpiresAtMs = refreshExpiresAtMs;
            this.createdAtMs = nowMs;
            this.lastSeenAtMs = nowMs;
        }

        /** Must ONLY be invoked while holding {@code refreshLock} (and after removal from {@code sessions}). */
        void wipeUnderRefreshLock() {
            wiped = true;
            if (accessToken != null) {
                Arrays.fill(accessToken, '\0');
                accessToken = null;
            }
            if (refreshToken != null) {
                Arrays.fill(refreshToken, '\0');
                refreshToken = null;
            }
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final long idleTtlMs;
    private final long maxTtlMs;
    private final Optional<Path> encryptedStoreFile;
    private final Optional<SecretKey> aesKey;
    private final Map<String, SessionEntry> sessions;
    private final List<SessionEntry> evictedPendingWipe = new ArrayList<>();

    public UserSessionStore() {
        this(DEFAULT_IDLE_TTL_MS, DEFAULT_MAX_TTL_MS, Optional.empty(), Optional.empty());
    }

    public UserSessionStore(Optional<Path> encryptedStoreFile, Optional<byte[]> secretBytes) {
        this(DEFAULT_IDLE_TTL_MS, DEFAULT_MAX_TTL_MS, encryptedStoreFile, secretBytes);
    }

    public UserSessionStore(long idleTtlMs, long maxTtlMs, Optional<Path> encryptedStoreFile,
            Optional<byte[]> secretBytes) {
        if (idleTtlMs < 1_000L || maxTtlMs < idleTtlMs) {
            throw new IllegalArgumentException("Invalid session TTL bounds");
        }
        this.idleTtlMs = idleTtlMs;
        this.maxTtlMs = maxTtlMs;
        if (encryptedStoreFile != null && encryptedStoreFile.isPresent()
                && secretBytes != null && secretBytes.isPresent()) {
            byte[] raw = secretBytes.orElseThrow();
            if (raw.length < 32) {
                throw new IllegalArgumentException("EVO_SESSION_SECRET must be at least 32 bytes");
            }
            this.encryptedStoreFile = Optional.of(encryptedStoreFile.orElseThrow().toAbsolutePath().normalize());
            this.aesKey = Optional.of(deriveAesKey(raw));
        } else {
            this.encryptedStoreFile = Optional.empty();
            this.aesKey = Optional.empty();
        }
        this.sessions = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SessionEntry> eldest) {
                if (size() > MAX_SESSIONS) {
                    evictedPendingWipe.add(eldest.getValue());
                    return true;
                }
                return false;
            }
        };
        loadEncryptedIfPresent();
    }

    public static Optional<byte[]> secretFromEnvironment(Function<String, String> envReader) {
        String raw = envReader == null ? null : envReader.apply("EVO_SESSION_SECRET");
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            LOG.log(System.Logger.Level.WARNING,
                    "Ignoring EVO_SESSION_SECRET shorter than 32 bytes; falling back to RAM-only sessions.");
            return Optional.empty();
        }
        return Optional.of(bytes);
    }

    public boolean isEncryptedPersistenceEnabled() {
        return encryptedStoreFile.isPresent() && aesKey.isPresent();
    }

    public String newCsrfToken() {
        return randomBase64Url(32);
    }

    public SessionView createSession(
            AuthenticatedUser user,
            char[] accessToken,
            char[] refreshToken,
            long tokenExpiresAtMs,
            long refreshExpiresAtMs) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(accessToken, "accessToken");
        long now = System.currentTimeMillis();
        SessionView created;
        List<SessionEntry> toWipe;
        synchronized (this) {
            toWipe = pruneExpiredLocked(now);
            String sid = randomBase64Url(32);
            String csrf = user.csrfToken().isBlank() ? newCsrfToken() : user.csrfToken();
            AuthenticatedUser bound = new AuthenticatedUser(
                    user.userId(), user.login(), user.name(), user.avatarUrl(), user.email(),
                    user.orgs(), user.scopes(), user.authMethod(), user.host(), csrf,
                    user.authenticatedAtMs() > 0L ? user.authenticatedAtMs() : now,
                    tokenExpiresAtMs, user.role(), user.githubLinked(), user.githubLogin());
            var entry = new SessionEntry(sid, bound, accessToken, refreshToken, tokenExpiresAtMs, refreshExpiresAtMs, now);
            Arrays.fill(accessToken, '\0');
            if (refreshToken != null) {
                Arrays.fill(refreshToken, '\0');
            }
            sessions.put(sid, entry);
            if (!evictedPendingWipe.isEmpty()) {
                toWipe.addAll(evictedPendingWipe);
                evictedPendingWipe.clear();
            }
            persistEncryptedLocked();
            created = new SessionView(sid, bound);
        }
        wipeAllOutsideStoreLock(toWipe);
        return created;
    }

    public Optional<SessionView> get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        SessionEntry expiredEntry = null;
        Optional<SessionView> result;
        synchronized (this) {
            SessionEntry entry = sessions.get(sessionId);
            if (entry == null) {
                return Optional.empty();
            }
            long now = System.currentTimeMillis();
            if (isExpired(entry, now)) {
                sessions.remove(sessionId);
                persistEncryptedLocked();
                expiredEntry = entry;
                result = Optional.empty();
            } else {
                entry.lastSeenAtMs = now;
                result = Optional.of(new SessionView(entry.sessionId, entry.user));
            }
        }
        if (expiredEntry != null) {
            wipeOutsideStoreLock(expiredEntry);
        }
        return result;
    }

    public Optional<GitHubTokenBroker.ResolvedToken> resolveToken(
            String sessionId,
            String host,
            TokenRefresher refresher) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String normalizedHost = (host == null || host.isBlank())
                ? GitHubCoordinates.DEFAULT_HOST
                : host.strip().toLowerCase(Locale.ROOT);
        if (GitHubCoordinates.DEFAULT_API_HOST.equals(normalizedHost)) {
            normalizedHost = GitHubCoordinates.DEFAULT_HOST;
        }
        SessionEntry entry;
        SessionEntry expiredEntry = null;
        synchronized (this) {
            entry = sessions.get(sessionId);
            if (entry == null) {
                return Optional.empty();
            }
            long now = System.currentTimeMillis();
            if (isExpired(entry, now)) {
                sessions.remove(sessionId);
                persistEncryptedLocked();
                expiredEntry = entry;
                entry = null;
            } else if (!entry.user.host().equals(normalizedHost)) {
                return Optional.empty();
            } else {
                entry.lastSeenAtMs = now;
            }
        }
        if (expiredEntry != null) {
            wipeOutsideStoreLock(expiredEntry);
            return Optional.empty();
        }

        // Handle expiring OAuth/GitHub App access tokens under strict lock order: entry.refreshLock -> this.
        synchronized (entry.refreshLock) {
            if (entry.wiped) {
                return Optional.empty();
            }
            long now = System.currentTimeMillis();
            boolean expiringSoon = entry.tokenExpiresAtMs > 0L && now >= (entry.tokenExpiresAtMs - REFRESH_WINDOW_MS);
            if (expiringSoon && entry.refreshToken != null && entry.refreshToken.length > 0 && refresher != null
                    && (entry.refreshExpiresAtMs == 0L || now < entry.refreshExpiresAtMs)) {
                char[] refreshCopy = entry.refreshToken.clone();
                try {
                    Optional<RefreshedToken> refreshed = refresher.refresh(entry.user.host(), refreshCopy);
                    if (refreshed.isPresent()) {
                        var r = refreshed.orElseThrow();
                        synchronized (this) {
                            if (sessions.get(sessionId) == entry && !entry.wiped) {
                                if (entry.accessToken != null) {
                                    Arrays.fill(entry.accessToken, '\0');
                                }
                                entry.accessToken = r.accessToken().clone();
                                Arrays.fill(r.accessToken(), '\0');
                                if (r.refreshToken() != null && r.refreshToken().length > 0) {
                                    if (entry.refreshToken != null) {
                                        Arrays.fill(entry.refreshToken, '\0');
                                    }
                                    entry.refreshToken = r.refreshToken().clone();
                                    Arrays.fill(r.refreshToken(), '\0');
                                }
                                entry.tokenExpiresAtMs = r.tokenExpiresAtMs();
                                if (r.refreshExpiresAtMs() > 0L) {
                                    entry.refreshExpiresAtMs = r.refreshExpiresAtMs();
                                }
                                if (r.scopes() != null && !r.scopes().isEmpty()) {
                                    var u = entry.user;
                                    entry.user = new AuthenticatedUser(u.userId(), u.login(), u.name(), u.avatarUrl(),
                                            u.email(), u.orgs(), r.scopes(), u.authMethod(), u.host(), u.csrfToken(),
                                            u.authenticatedAtMs(), entry.tokenExpiresAtMs,
                                            u.role(), u.githubLinked(), u.githubLogin());
                                }
                                persistEncryptedLocked();
                            } else {
                                Arrays.fill(r.accessToken(), '\0');
                                if (r.refreshToken() != null) {
                                    Arrays.fill(r.refreshToken(), '\0');
                                }
                                return Optional.empty();
                            }
                        }
                    } else if (entry.tokenExpiresAtMs > 0L && now >= entry.tokenExpiresAtMs) {
                        synchronized (this) {
                            if (sessions.get(sessionId) == entry) {
                                sessions.remove(sessionId);
                                persistEncryptedLocked();
                            }
                        }
                        entry.wipeUnderRefreshLock();
                        return Optional.empty();
                    }
                } finally {
                    Arrays.fill(refreshCopy, '\0');
                }
            } else if (entry.tokenExpiresAtMs > 0L && now >= entry.tokenExpiresAtMs) {
                synchronized (this) {
                    if (sessions.get(sessionId) == entry) {
                        sessions.remove(sessionId);
                        persistEncryptedLocked();
                    }
                }
                entry.wipeUnderRefreshLock();
                return Optional.empty();
            }

            synchronized (this) {
                if (sessions.get(sessionId) != entry || entry.wiped
                        || entry.accessToken == null || entry.accessToken.length == 0) {
                    return Optional.empty();
                }
                return Optional.of(new GitHubTokenBroker.ResolvedToken(
                        new String(entry.accessToken),
                        entry.user.authMethod().name()));
            }
        }
    }

    /** Updates the user metadata on a single active session (preserving CSRF and tokens). */
    public Optional<SessionView> updateSessionUser(String sessionId,
            java.util.function.UnaryOperator<AuthenticatedUser> updater) {
        if (sessionId == null || sessionId.isBlank() || updater == null) {
            return Optional.empty();
        }
        synchronized (this) {
            SessionEntry entry = sessions.get(sessionId);
            if (entry == null || entry.wiped) {
                return Optional.empty();
            }
            AuthenticatedUser next = Objects.requireNonNull(updater.apply(entry.user), "updated user");
            entry.user = next;
            entry.lastSeenAtMs = System.currentTimeMillis();
            persistEncryptedLocked();
            return Optional.of(new SessionView(entry.sessionId, entry.user));
        }
    }

    /** Updates user metadata across all active sessions belonging to {@code userId}. */
    public void updateUserAcrossSessions(String userId,
            java.util.function.UnaryOperator<AuthenticatedUser> updater) {
        if (userId == null || userId.isBlank() || updater == null) {
            return;
        }
        synchronized (this) {
            boolean changed = false;
            for (SessionEntry entry : sessions.values()) {
                if (!entry.wiped && userId.equals(entry.user.userId())) {
                    entry.user = Objects.requireNonNull(updater.apply(entry.user), "updated user");
                    changed = true;
                }
            }
            if (changed) {
                persistEncryptedLocked();
            }
        }
    }

    /**
     * Revokes stored GitHub OAuth/PAT tokens across ALL active sessions belonging to {@code userId}
     * while keeping local user sessions signed in.
     * Enforces strict deadlock-free lock order: snapshot under {@code this}, then {@code entry.refreshLock -> this}.
     */
    public Optional<SessionView> clearGitHubTokensForUser(
            String userId,
            String preferredSessionId,
            java.util.function.UnaryOperator<AuthenticatedUser> updater) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        List<SessionEntry> snapshot = new ArrayList<>();
        synchronized (this) {
            for (SessionEntry entry : sessions.values()) {
                if (!entry.wiped && userId.equals(entry.user.userId())) {
                    snapshot.add(entry);
                }
            }
        }
        SessionView preferred = null;
        SessionView fallback = null;
        for (SessionEntry entry : snapshot) {
            synchronized (entry.refreshLock) {
                synchronized (this) {
                    if (sessions.get(entry.sessionId) != entry || entry.wiped) {
                        continue;
                    }
                    if (entry.accessToken != null) {
                        Arrays.fill(entry.accessToken, '\0');
                        entry.accessToken = new char[0];
                    }
                    if (entry.refreshToken != null) {
                        Arrays.fill(entry.refreshToken, '\0');
                        entry.refreshToken = null;
                    }
                    entry.tokenExpiresAtMs = 0L;
                    entry.refreshExpiresAtMs = 0L;
                    if (updater != null) {
                        entry.user = Objects.requireNonNull(updater.apply(entry.user), "updated user");
                    }
                    persistEncryptedLocked();
                    var view = new SessionView(entry.sessionId, entry.user);
                    if (entry.sessionId.equals(preferredSessionId)) {
                        preferred = view;
                    } else if (fallback == null) {
                        fallback = view;
                    }
                }
            }
        }
        return Optional.ofNullable(preferred != null ? preferred : fallback);
    }

    /**
     * Revokes stored GitHub OAuth/PAT tokens on a session while keeping the local user session active.
     * Enforces deadlock-free lock order: {@code entry.refreshLock -> this}.
     */
    public Optional<SessionView> clearGitHubToken(String sessionId,
            java.util.function.UnaryOperator<AuthenticatedUser> updater) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        SessionEntry entry;
        synchronized (this) {
            entry = sessions.get(sessionId);
            if (entry == null) {
                return Optional.empty();
            }
        }
        synchronized (entry.refreshLock) {
            synchronized (this) {
                if (sessions.get(sessionId) != entry || entry.wiped) {
                    return Optional.empty();
                }
                if (entry.accessToken != null) {
                    Arrays.fill(entry.accessToken, '\0');
                    entry.accessToken = new char[0];
                }
                if (entry.refreshToken != null) {
                    Arrays.fill(entry.refreshToken, '\0');
                    entry.refreshToken = null;
                }
                entry.tokenExpiresAtMs = 0L;
                entry.refreshExpiresAtMs = 0L;
                if (updater != null) {
                    entry.user = Objects.requireNonNull(updater.apply(entry.user), "updated user");
                }
                persistEncryptedLocked();
                return Optional.of(new SessionView(entry.sessionId, entry.user));
            }
        }
    }

    /**
     * Binds newly linked GitHub tokens and updated identity onto an existing authenticated session.
     * Enforces deadlock-free lock order: {@code entry.refreshLock -> this}.
     */
    public Optional<SessionView> bindGitHubToken(
            String sessionId,
            AuthenticatedUser updatedUser,
            char[] accessToken,
            char[] refreshToken,
            long tokenExpiresAtMs,
            long refreshExpiresAtMs) {
        try {
            if (sessionId == null || sessionId.isBlank() || updatedUser == null) {
                return Optional.empty();
            }
            SessionEntry entry;
            synchronized (this) {
                entry = sessions.get(sessionId);
                if (entry == null) {
                    return Optional.empty();
                }
            }
            synchronized (entry.refreshLock) {
                synchronized (this) {
                    if (sessions.get(sessionId) != entry || entry.wiped) {
                        return Optional.empty();
                    }
                    if (entry.accessToken != null) {
                        Arrays.fill(entry.accessToken, '\0');
                    }
                    entry.accessToken = accessToken == null ? new char[0] : accessToken.clone();
                    if (entry.refreshToken != null) {
                        Arrays.fill(entry.refreshToken, '\0');
                    }
                    entry.refreshToken = (refreshToken == null || refreshToken.length == 0) ? null : refreshToken.clone();
                    entry.tokenExpiresAtMs = tokenExpiresAtMs;
                    entry.refreshExpiresAtMs = refreshExpiresAtMs;
                    entry.user = new AuthenticatedUser(
                            updatedUser.userId(), updatedUser.login(), updatedUser.name(), updatedUser.avatarUrl(),
                            updatedUser.email(), updatedUser.orgs(), updatedUser.scopes(), updatedUser.authMethod(),
                            updatedUser.host(), entry.user.csrfToken(),
                            updatedUser.authenticatedAtMs() > 0L ? updatedUser.authenticatedAtMs() : System.currentTimeMillis(),
                            tokenExpiresAtMs, updatedUser.role(), updatedUser.githubLinked(), updatedUser.githubLogin());
                    entry.lastSeenAtMs = System.currentTimeMillis();
                    persistEncryptedLocked();
                    return Optional.of(new SessionView(entry.sessionId, entry.user));
                }
            }
        } finally {
            if (accessToken != null) Arrays.fill(accessToken, '\0');
            if (refreshToken != null) Arrays.fill(refreshToken, '\0');
        }
    }

    public boolean invalidate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        SessionEntry removed;
        synchronized (this) {
            removed = sessions.remove(sessionId);
            if (removed != null) {
                persistEncryptedLocked();
            }
        }
        if (removed != null) {
            wipeOutsideStoreLock(removed);
            return true;
        }
        return false;
    }

    public int invalidateAllForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        var removed = new ArrayList<SessionEntry>();
        synchronized (this) {
            var it = sessions.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (userId.equals(e.getValue().user.userId())) {
                    removed.add(e.getValue());
                    it.remove();
                }
            }
            if (!removed.isEmpty()) {
                persistEncryptedLocked();
            }
        }
        wipeAllOutsideStoreLock(removed);
        return removed.size();
    }

    public int activeSessionCount() {
        List<SessionEntry> expired;
        int count;
        synchronized (this) {
            expired = pruneExpiredLocked(System.currentTimeMillis());
            count = sessions.size();
        }
        wipeAllOutsideStoreLock(expired);
        return count;
    }

    @Override
    public void close() {
        List<SessionEntry> all;
        synchronized (this) {
            all = new ArrayList<>(sessions.values());
            sessions.clear();
            if (!evictedPendingWipe.isEmpty()) {
                all.addAll(evictedPendingWipe);
                evictedPendingWipe.clear();
            }
        }
        wipeAllOutsideStoreLock(all);
    }

    private static void wipeOutsideStoreLock(SessionEntry entry) {
        synchronized (entry.refreshLock) {
            entry.wipeUnderRefreshLock();
        }
    }

    private static void wipeAllOutsideStoreLock(List<SessionEntry> entries) {
        for (SessionEntry entry : entries) {
            wipeOutsideStoreLock(entry);
        }
    }

    private boolean isExpired(SessionEntry entry, long now) {
        if (now - entry.lastSeenAtMs > idleTtlMs) {
            return true;
        }
        if (now - entry.createdAtMs > maxTtlMs) {
            return true;
        }
        return false;
    }

    private List<SessionEntry> pruneExpiredLocked(long now) {
        var expired = new ArrayList<SessionEntry>();
        var it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next().getValue();
            if (isExpired(entry, now)) {
                expired.add(entry);
                it.remove();
            }
        }
        if (!expired.isEmpty()) {
            persistEncryptedLocked();
        }
        return expired;
    }

    private String randomBase64Url(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static SecretKey deriveAesKey(byte[] secret) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update("evo-session-store:v1:".getBytes(StandardCharsets.UTF_8));
            sha256.update(secret);
            return new SecretKeySpec(sha256.digest(), "AES");
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void persistEncryptedLocked() {
        if (encryptedStoreFile.isEmpty() || aesKey.isEmpty()) {
            return;
        }
        Path target = encryptedStoreFile.orElseThrow();
        try {
            var rows = new ArrayList<Map<String, Object>>(sessions.size());
            for (SessionEntry e : sessions.values()) {
                var row = new LinkedHashMap<String, Object>();
                row.put("sessionId", e.sessionId);
                row.put("userId", e.user.userId());
                row.put("login", e.user.login());
                row.put("name", e.user.name());
                row.put("avatarUrl", e.user.avatarUrl());
                row.put("email", e.user.email());
                row.put("orgs", e.user.orgs());
                row.put("scopes", e.user.scopes());
                row.put("authMethod", e.user.authMethod().name());
                row.put("host", e.user.host());
                row.put("csrfToken", e.user.csrfToken());
                row.put("role", e.user.role());
                row.put("githubLinked", e.user.githubLinked());
                row.put("githubLogin", e.user.githubLogin());
                row.put("authenticatedAtMs", e.user.authenticatedAtMs());
                row.put("tokenExpiresAtMs", e.tokenExpiresAtMs);
                row.put("refreshExpiresAtMs", e.refreshExpiresAtMs);
                row.put("createdAtMs", e.createdAtMs);
                row.put("lastSeenAtMs", e.lastSeenAtMs);
                row.put("accessToken", e.accessToken == null ? "" : new String(e.accessToken));
                row.put("refreshToken", e.refreshToken == null ? "" : new String(e.refreshToken));
                rows.add(row);
            }
            byte[] plaintext = CanonicalJson.write(Map.of("sessions", rows)).getBytes(StandardCharsets.UTF_8);
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey.orElseThrow(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(PERSIST_SCHEMA.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            Arrays.fill(plaintext, (byte) 0);

            var envelope = new LinkedHashMap<String, Object>();
            envelope.put("schema", PERSIST_SCHEMA);
            envelope.put("iv", Base64.getEncoder().encodeToString(iv));
            envelope.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
            byte[] encoded = CanonicalJson.write(envelope).getBytes(StandardCharsets.UTF_8);

            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = Files.createTempFile(target.getParent(), "evo-sessions-", ".tmp");
            try {
                Files.write(tmp, encoded);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException fallback) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | GeneralSecurityException err) {
            LOG.log(System.Logger.Level.WARNING, "Failed to persist encrypted session store ({0})",
                    err.getClass().getSimpleName());
        }
    }

    private void loadEncryptedIfPresent() {
        if (encryptedStoreFile.isEmpty() || aesKey.isEmpty()) {
            return;
        }
        Path target = encryptedStoreFile.orElseThrow();
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            String raw = Files.readString(target, StandardCharsets.UTF_8);
            var env = GraphJson.object(GraphJson.read(raw, new GraphJson.Limits(8 * 1024 * 1024, 32, 200_000)));
            if (!PERSIST_SCHEMA.equals(env.get("schema"))) {
                return;
            }
            byte[] iv = Base64.getDecoder().decode(String.valueOf(env.get("iv")));
            byte[] ciphertext = Base64.getDecoder().decode(String.valueOf(env.get("ciphertext")));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey.orElseThrow(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(PERSIST_SCHEMA.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(ciphertext);
            String json = new String(plaintext, StandardCharsets.UTF_8);
            Arrays.fill(plaintext, (byte) 0);

            var payload = GraphJson.object(GraphJson.read(json, new GraphJson.Limits(8 * 1024 * 1024, 32, 200_000)));
            long now = System.currentTimeMillis();
            for (Object item : GraphJson.array(payload.get("sessions"))) {
                var row = GraphJson.object(item);
                String sid = String.valueOf(row.get("sessionId"));
                String accessStr = String.valueOf(row.get("accessToken"));
                String refreshStr = String.valueOf(row.get("refreshToken"));
                AuthenticatedUser.AuthMethod method = AuthenticatedUser.AuthMethod.valueOf(String.valueOf(row.get("authMethod")));
                if (sid.isBlank() || (method != AuthenticatedUser.AuthMethod.LOCAL_PASSWORD && accessStr.isBlank())) {
                    continue;
                }
                var orgs = new ArrayList<String>();
                if (row.get("orgs") instanceof List<?> rawOrgs) {
                    for (Object o : rawOrgs) orgs.add(String.valueOf(o));
                }
                var scopes = new ArrayList<String>();
                if (row.get("scopes") instanceof List<?> rawScopes) {
                    for (Object s : rawScopes) scopes.add(String.valueOf(s));
                }
                long authAt = longVal(row.get("authenticatedAtMs"), now);
                long tokenExp = longVal(row.get("tokenExpiresAtMs"), 0L);
                long refreshExp = longVal(row.get("refreshExpiresAtMs"), 0L);
                long createdAt = longVal(row.get("createdAtMs"), now);
                long lastSeen = longVal(row.get("lastSeenAtMs"), now);
                String role = row.get("role") instanceof String r && !r.isBlank()
                        ? r : UserAccountStore.ROLE_MEMBER;
                boolean ghLinked = row.containsKey("githubLinked")
                        ? Boolean.TRUE.equals(row.get("githubLinked"))
                        : (method == AuthenticatedUser.AuthMethod.OAUTH_WEB
                                || method == AuthenticatedUser.AuthMethod.OAUTH_DEVICE
                                || method == AuthenticatedUser.AuthMethod.SESSION_PAT);
                String ghLogin = row.get("githubLogin") instanceof String gl ? gl : (ghLinked ? String.valueOf(row.get("login")) : "");
                var user = new AuthenticatedUser(
                        String.valueOf(row.get("userId")),
                        String.valueOf(row.get("login")),
                        String.valueOf(row.get("name")),
                        String.valueOf(row.get("avatarUrl")),
                        String.valueOf(row.get("email")),
                        orgs,
                        scopes,
                        method,
                        String.valueOf(row.get("host")),
                        String.valueOf(row.get("csrfToken")),
                        authAt,
                        tokenExp,
                        role,
                        ghLinked,
                        ghLogin);
                var entry = new SessionEntry(
                        sid, user,
                        accessStr.isBlank() ? new char[0] : accessStr.toCharArray(),
                        refreshStr.isBlank() ? null : refreshStr.toCharArray(),
                        tokenExp, refreshExp, createdAt);
                entry.lastSeenAtMs = lastSeen;
                if (!isExpired(entry, now)) {
                    sessions.put(sid, entry);
                } else {
                    wipeOutsideStoreLock(entry);
                }
            }
        } catch (IOException | GeneralSecurityException | RuntimeException corrupt) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring unreadable encrypted session store ({0})",
                    corrupt.getClass().getSimpleName());
        }
    }

    private static long longVal(Object value, long fallback) {
        if (value instanceof java.math.BigDecimal bd) {
            return bd.longValue();
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return fallback;
    }
}
