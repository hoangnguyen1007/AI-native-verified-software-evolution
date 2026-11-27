package com.evolution.backend.auth;

import java.io.IOException;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Built-in SQLite multi-user account store ({@code <store>/auth/accounts.db}).
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Passwords are hashed exclusively with PBKDF2WithHmacSHA256 (210,000 iterations, 16-byte SecureRandom salt)
 *       and verified in constant time via {@link MessageDigest#isEqual}.</li>
 *   <li>Unknown or password-less accounts still execute a dummy PBKDF2 derivation to mitigate timing enumeration.</li>
 *   <li>First registered user automatically receives {@code ADMIN}; subsequent users receive {@code MEMBER}.</li>
 *   <li>The last active {@code ADMIN} cannot be demoted or disabled ({@code LAST_ADMIN_PROTECTED}).</li>
 *   <li>Zero password hashes, salts, or GitHub tokens are ever returned in public views.</li>
 * </ul>
 */
public final class UserAccountStore implements AutoCloseable {
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MEMBER = "MEMBER";
    public static final String PAT_PREFIX = "evo_pat_";
    public static final Set<String> VALID_PAT_SCOPES = Set.of("mcp:read", "api:read", "api:write");
    public static final int MAX_ACTIVE_PATS_PER_USER = 25;
    public static final int DEFAULT_PAT_EXPIRY_DAYS = 90;
    public static final int MAX_PAT_EXPIRY_DAYS = 365;
    public static final int PBKDF2_ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{1,39}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s<>\"']+@[^@\\s<>\"']+\\.[^@\\s<>\"']+$");

    public static final class AccountException extends RuntimeException {
        private final int status;
        private final String code;

        public AccountException(int status, String code, String message) {
            super(message);
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

    public record Account(
            String userId,
            String username,
            String displayName,
            String email,
            String role,
            boolean githubLinked,
            String githubUserId,
            String githubLogin,
            String githubAvatarUrl,
            boolean disabled,
            boolean hasPassword,
            long createdAtMs,
            long updatedAtMs) {

        public boolean isAdmin() {
            return ROLE_ADMIN.equals(role);
        }

        /** Public JSON map (zero password hashes, salts, or tokens). */
        public Map<String, Object> toPublicMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("userId", userId);
            map.put("login", username);
            map.put("displayName", displayName);
            map.put("name", displayName);
            map.put("email", email);
            map.put("role", role);
            map.put("githubLinked", githubLinked);
            map.put("githubLogin", githubLogin);
            map.put("avatarUrl", githubAvatarUrl);
            map.put("disabled", disabled);
            map.put("createdAtMs", createdAtMs);
            return map;
        }
    }

    /** Public metadata for a scoped Personal Access Token (never stores or returns plaintext or hash). */
    public record PersonalAccessToken(
            String tokenId,
            String userId,
            String label,
            String tokenPrefix,
            List<String> scopes,
            long createdAtMs,
            long expiresAtMs,
            long lastUsedAtMs,
            boolean revoked,
            long revokedAtMs) {
        public PersonalAccessToken {
            scopes = List.copyOf(scopes == null ? List.of() : scopes);
        }

        public Map<String, Object> toPublicMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("tokenId", tokenId);
            map.put("userId", userId);
            map.put("label", label);
            map.put("prefix", tokenPrefix);
            map.put("scopes", scopes);
            map.put("createdAtMs", createdAtMs);
            map.put("expiresAtMs", expiresAtMs);
            map.put("lastUsedAtMs", lastUsedAtMs);
            map.put("revoked", revoked);
            map.put("revokedAtMs", revokedAtMs);
            map.put("expired", expiresAtMs > 0 && System.currentTimeMillis() > expiresAtMs);
            return map;
        }
    }

    /** Returned ONCE when minting a new PAT; plaintext is never persisted to SQLite. */
    public record MintedToken(PersonalAccessToken metadata, String plaintextToken) {}

    /** Authenticated PAT lookup result pairing the owning account and token metadata. */
    public record ResolvedToken(Account account, PersonalAccessToken token) {}

    private final Path dbFile;
    private final Connection connection;
    private final SecureRandom random = new SecureRandom();
    private final byte[] dummySalt;
    private final byte[] dummyHash;

    public UserAccountStore(Path dbFile) {
        this.dbFile = Objects.requireNonNull(dbFile, "dbFile").toAbsolutePath().normalize();
        this.dummySalt = new byte[SALT_BYTES];
        this.random.nextBytes(this.dummySalt);
        this.dummyHash = derivePbkdf2("evo-dummy-password-timing-guard".toCharArray(), this.dummySalt, PBKDF2_ITERATIONS);
        try {
            if (this.dbFile.getParent() != null) {
                Files.createDirectories(this.dbFile.getParent());
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbFile);
            initializeSchema();
        } catch (IOException | SQLException err) {
            throw new IllegalStateException("Failed to initialize user account store at " + this.dbFile, err);
        }
    }

    private void initializeSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS user_accounts (
                        user_id TEXT PRIMARY KEY,
                        username TEXT NOT NULL UNIQUE,
                        username_lower TEXT NOT NULL UNIQUE,
                        email TEXT NOT NULL UNIQUE,
                        email_lower TEXT NOT NULL UNIQUE,
                        display_name TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        password_salt TEXT NOT NULL,
                        password_iterations INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        github_user_id TEXT UNIQUE,
                        github_login TEXT NOT NULL DEFAULT '',
                        github_avatar_url TEXT NOT NULL DEFAULT '',
                        disabled INTEGER NOT NULL DEFAULT 0,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_user_accounts_created ON user_accounts(created_at_ms)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS personal_access_tokens (
                        token_id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        label TEXT NOT NULL,
                        token_prefix TEXT NOT NULL,
                        token_hash TEXT NOT NULL UNIQUE,
                        scopes TEXT NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        expires_at_ms INTEGER NOT NULL,
                        last_used_at_ms INTEGER NOT NULL DEFAULT 0,
                        revoked_at_ms INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (user_id) REFERENCES user_accounts(user_id) ON DELETE CASCADE
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_pat_user_created ON personal_access_tokens(user_id, created_at_ms DESC)");
        }
    }

    public Path dbFile() {
        return dbFile;
    }

    public synchronized boolean hasRegisteredUsers() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM user_accounts LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        } catch (SQLException err) {
            throw new IllegalStateException("Account lookup failed", err);
        }
    }

    public synchronized Optional<Account> findById(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT user_id, username, display_name, email, role, github_user_id, github_login, "
                        + "github_avatar_url, disabled, password_hash, created_at_ms, updated_at_ms "
                        + "FROM user_accounts WHERE user_id = ?")) {
            ps.setString(1, userId.strip());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readAccount(rs)) : Optional.empty();
            }
        } catch (SQLException err) {
            throw new IllegalStateException("Account lookup failed", err);
        }
    }

    public synchronized List<Account> listUsers() {
        var out = new ArrayList<Account>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT user_id, username, display_name, email, role, github_user_id, github_login, "
                        + "github_avatar_url, disabled, password_hash, created_at_ms, updated_at_ms "
                        + "FROM user_accounts ORDER BY created_at_ms ASC, user_id ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(readAccount(rs));
            }
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to list user accounts", err);
        }
        return List.copyOf(out);
    }

    /**
     * Registers a new local user account with PBKDF2WithHmacSHA256 password hashing.
     * The first registered user automatically receives {@code ADMIN}; subsequent users receive {@code MEMBER}.
     */
    public synchronized Account register(String rawEmail, String rawUsername, Optional<String> rawDisplayName,
            char[] password) {
        Objects.requireNonNull(password, "password");
        String validatedUsername;
        try {
            validatedUsername = validateUsername(rawUsername);
        } catch (RuntimeException invalid) {
            Arrays.fill(password, '\0');
            throw invalid;
        }
        return register(rawEmail, Optional.of(validatedUsername), rawDisplayName, password);
    }

    /**
     * Registers a new local user account with optional {@code username}.
     * When {@code rawUsernameOpt} is omitted or blank, derives a clean lowercase handle from the email prefix
     * ({@code [a-z0-9_-]}, length 2..40, avoiding reserved {@code local}) and appends {@code -2}, {@code -3}, ... on collision.
     * The first registered user automatically receives {@code ADMIN}; subsequent users receive {@code MEMBER}.
     */
    public synchronized Account register(String rawEmail, Optional<String> rawUsernameOpt,
            Optional<String> rawDisplayName, char[] password) {
        Objects.requireNonNull(password, "password");
        try {
            String email = validateEmail(rawEmail);
            Optional<String> explicitUsername = rawUsernameOpt == null
                    ? Optional.empty()
                    : rawUsernameOpt.map(String::strip)
                            .filter(s -> !s.isEmpty())
                            .map(UserAccountStore::validateUsername);
            Optional<String> explicitDisplayName = rawDisplayName == null
                    ? Optional.empty()
                    : rawDisplayName.map(String::strip)
                            .filter(s -> !s.isEmpty())
                            .map(UserAccountStore::validateDisplayName);
            validatePassword(password);

            byte[] salt = new byte[SALT_BYTES];
            random.nextBytes(salt);
            byte[] hash = derivePbkdf2(password, salt, PBKDF2_ITERATIONS);
            String saltB64 = Base64.getEncoder().encodeToString(salt);
            String hashB64 = Base64.getEncoder().encodeToString(hash);
            Arrays.fill(hash, (byte) 0);

            long now = System.currentTimeMillis();
            String userId = "usr_" + randomHex(16);

            boolean startedTx = false;
            try {
                execute("BEGIN IMMEDIATE");
                startedTx = true;
                if (existsEmail(email.toLowerCase(Locale.ROOT))) {
                    execute("ROLLBACK");
                    startedTx = false;
                    throw new AccountException(409, "ACCOUNT_ALREADY_EXISTS",
                            "An account with that email or username already exists.");
                }
                String username;
                if (explicitUsername.isPresent()) {
                    username = explicitUsername.orElseThrow();
                    if (existsUsername(username.toLowerCase(Locale.ROOT))) {
                        execute("ROLLBACK");
                        startedTx = false;
                        throw new AccountException(409, "ACCOUNT_ALREADY_EXISTS",
                                "An account with that email or username already exists.");
                    }
                } else {
                    username = allocateSequentialUsername(deriveUsernameBaseFromEmail(email));
                }
                String displayName = explicitDisplayName.orElse(username);
                String role = countTotalUsers() == 0 ? ROLE_ADMIN : ROLE_MEMBER;
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO user_accounts (user_id, username, username_lower, email, email_lower, "
                                + "display_name, password_hash, password_salt, password_iterations, role, "
                                + "github_user_id, github_login, github_avatar_url, disabled, created_at_ms, updated_at_ms) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, '', '', 0, ?, ?)")) {
                    ps.setString(1, userId);
                    ps.setString(2, username);
                    ps.setString(3, username.toLowerCase(Locale.ROOT));
                    ps.setString(4, email);
                    ps.setString(5, email.toLowerCase(Locale.ROOT));
                    ps.setString(6, displayName);
                    ps.setString(7, hashB64);
                    ps.setString(8, saltB64);
                    ps.setInt(9, PBKDF2_ITERATIONS);
                    ps.setString(10, role);
                    ps.setLong(11, now);
                    ps.setLong(12, now);
                    ps.executeUpdate();
                }
                execute("COMMIT");
                startedTx = false;
                return new Account(userId, username, displayName, email, role, false, "", "", "", false, true, now, now);
            } catch (SQLException err) {
                if (startedTx) {
                    rollbackQuietly();
                }
                if (err.getMessage() != null && err.getMessage().toLowerCase(Locale.ROOT).contains("unique")) {
                    throw new AccountException(409, "ACCOUNT_ALREADY_EXISTS",
                            "An account with that email or username already exists.");
                }
                throw new IllegalStateException("Failed to register account", err);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Authenticates by email or username using constant-time PBKDF2 verification.
     * Unknown or password-less accounts still execute a dummy derivation to prevent timing enumeration.
     */
    public synchronized Account authenticate(String rawIdentifier, char[] password) {
        Objects.requireNonNull(password, "password");
        try {
            if (rawIdentifier == null || rawIdentifier.isBlank() || password.length == 0 || password.length > 128) {
                derivePbkdf2(password.length == 0 ? new char[]{'x'} : password, dummySalt, PBKDF2_ITERATIONS);
                throw new AccountException(401, "INVALID_CREDENTIALS", "Invalid email/username or password.");
            }
            String lookup = rawIdentifier.strip().toLowerCase(Locale.ROOT);
            Account account = null;
            String hashB64 = "";
            String saltB64 = "";
            int iterations = PBKDF2_ITERATIONS;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT user_id, username, display_name, email, role, github_user_id, github_login, "
                            + "github_avatar_url, disabled, password_hash, password_salt, password_iterations, "
                            + "created_at_ms, updated_at_ms "
                            + "FROM user_accounts WHERE email_lower = ? OR username_lower = ? LIMIT 1")) {
                ps.setString(1, lookup);
                ps.setString(2, lookup);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        account = readAccount(rs);
                        hashB64 = rs.getString("password_hash");
                        saltB64 = rs.getString("password_salt");
                        iterations = rs.getInt("password_iterations");
                    }
                }
            } catch (SQLException err) {
                throw new IllegalStateException("Authentication lookup failed", err);
            }

            if (account == null || hashB64 == null || hashB64.isBlank() || saltB64 == null || saltB64.isBlank()) {
                byte[] dummy = derivePbkdf2(password, dummySalt, PBKDF2_ITERATIONS);
                MessageDigest.isEqual(dummy, dummyHash);
                Arrays.fill(dummy, (byte) 0);
                throw new AccountException(401, "INVALID_CREDENTIALS", "Invalid email/username or password.");
            }

            byte[] salt = Base64.getDecoder().decode(saltB64);
            byte[] expectedHash = Base64.getDecoder().decode(hashB64);
            byte[] candidateHash = derivePbkdf2(password, salt, Math.max(10_000, iterations));
            boolean matches = MessageDigest.isEqual(expectedHash, candidateHash);
            Arrays.fill(expectedHash, (byte) 0);
            Arrays.fill(candidateHash, (byte) 0);

            if (!matches) {
                throw new AccountException(401, "INVALID_CREDENTIALS", "Invalid email/username or password.");
            }
            if (account.disabled()) {
                throw new AccountException(403, "ACCOUNT_DISABLED", "This user account has been disabled by an administrator.");
            }
            return account;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Updates displayName and/or password for an authenticated account. */
    public synchronized Account updateProfile(
            String userId,
            Optional<String> rawDisplayName,
            Optional<char[]> currentPassword,
            Optional<char[]> newPassword) {
        try {
            Account existing = findById(userId)
                    .orElseThrow(() -> new AccountException(404, "ACCOUNT_NOT_FOUND", "User account not found."));
            String nextDisplayName = rawDisplayName.isPresent()
                    ? validateDisplayName(rawDisplayName.orElseThrow())
                    : existing.displayName();

            String nextHash = null;
            String nextSalt = null;
            if (newPassword != null && newPassword.isPresent()) {
                char[] nextPw = newPassword.orElseThrow();
                validatePassword(nextPw);
                if (existing.hasPassword()) {
                    if (currentPassword == null || currentPassword.isEmpty() || currentPassword.orElseThrow().length == 0) {
                        throw new AccountException(400, "CURRENT_PASSWORD_REQUIRED",
                                "Current password is required to change your password.");
                    }
                    verifyStoredPassword(userId, currentPassword.orElseThrow());
                }
                byte[] salt = new byte[SALT_BYTES];
                random.nextBytes(salt);
                byte[] hash = derivePbkdf2(nextPw, salt, PBKDF2_ITERATIONS);
                nextSalt = Base64.getEncoder().encodeToString(salt);
                nextHash = Base64.getEncoder().encodeToString(hash);
                Arrays.fill(hash, (byte) 0);
            }

            long now = System.currentTimeMillis();
            if (nextHash != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE user_accounts SET display_name = ?, password_hash = ?, password_salt = ?, "
                                + "password_iterations = ?, updated_at_ms = ? WHERE user_id = ?")) {
                    ps.setString(1, nextDisplayName);
                    ps.setString(2, nextHash);
                    ps.setString(3, nextSalt);
                    ps.setInt(4, PBKDF2_ITERATIONS);
                    ps.setLong(5, now);
                    ps.setString(6, existing.userId());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE user_accounts SET display_name = ?, updated_at_ms = ? WHERE user_id = ?")) {
                    ps.setString(1, nextDisplayName);
                    ps.setLong(2, now);
                    ps.setString(3, existing.userId());
                    ps.executeUpdate();
                }
            }
            return findById(existing.userId()).orElseThrow();
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to update user profile", err);
        } finally {
            if (currentPassword != null && currentPassword.isPresent()) {
                Arrays.fill(currentPassword.orElseThrow(), '\0');
            }
            if (newPassword != null && newPassword.isPresent()) {
                Arrays.fill(newPassword.orElseThrow(), '\0');
            }
        }
    }

    /** Updates role and/or disabled status (admin-only); refuses to demote or disable the last active admin. */
    public synchronized Account updateRoleAndStatus(String targetUserId, String rawRole, Optional<Boolean> disabledOpt) {
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new AccountException(400, "INVALID_REQUEST", "Missing target userId.");
        }
        String nextRole = normalizeRole(rawRole);
        boolean startedTx = false;
        try {
            execute("BEGIN IMMEDIATE");
            startedTx = true;
            Account target = findById(targetUserId)
                    .orElseThrow(() -> new AccountException(404, "ACCOUNT_NOT_FOUND", "User account not found."));
            boolean nextDisabled = disabledOpt.orElse(target.disabled());

            boolean losingAdmin = ROLE_ADMIN.equals(target.role()) && !target.disabled()
                    && (!ROLE_ADMIN.equals(nextRole) || nextDisabled);
            if (losingAdmin && countOtherActiveAdmins(target.userId()) == 0) {
                execute("ROLLBACK");
                startedTx = false;
                throw new AccountException(409, "LAST_ADMIN_PROTECTED",
                        "Cannot demote or disable the last active administrator.");
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE user_accounts SET role = ?, disabled = ?, updated_at_ms = ? WHERE user_id = ?")) {
                ps.setString(1, nextRole);
                ps.setInt(2, nextDisabled ? 1 : 0);
                ps.setLong(3, now);
                ps.setString(4, target.userId());
                ps.executeUpdate();
            }
            execute("COMMIT");
            startedTx = false;
            return findById(target.userId()).orElseThrow();
        } catch (AccountException ex) {
            if (startedTx) rollbackQuietly();
            throw ex;
        } catch (SQLException err) {
            if (startedTx) rollbackQuietly();
            throw new IllegalStateException("Failed to update user role", err);
        }
    }

    /** Links a GitHub identity onto an existing local account. */
    public synchronized Account linkGitHub(String userId, String githubUserId, String githubLogin, String githubAvatarUrl) {
        if (githubUserId == null || githubUserId.isBlank() || githubLogin == null || githubLogin.isBlank()) {
            throw new AccountException(400, "INVALID_REQUEST", "Missing GitHub identity.");
        }
        String cleanGhId = githubUserId.strip();
        String cleanGhLogin = githubLogin.strip();
        String safeAvatar = AuthenticatedUser.safeAvatarUrl(githubAvatarUrl);
        boolean startedTx = false;
        try {
            execute("BEGIN IMMEDIATE");
            startedTx = true;
            Account target = findById(userId)
                    .orElseThrow(() -> new AccountException(404, "ACCOUNT_NOT_FOUND", "User account not found."));
            Optional<Account> conflict = findByGitHubUserId(cleanGhId);
            if (conflict.isPresent() && !conflict.orElseThrow().userId().equals(target.userId())) {
                execute("ROLLBACK");
                startedTx = false;
                throw new AccountException(409, "GITHUB_ALREADY_LINKED",
                        "That GitHub account is already linked to another workspace user.");
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE user_accounts SET github_user_id = ?, github_login = ?, github_avatar_url = ?, "
                            + "updated_at_ms = ? WHERE user_id = ?")) {
                ps.setString(1, cleanGhId);
                ps.setString(2, cleanGhLogin);
                ps.setString(3, safeAvatar);
                ps.setLong(4, now);
                ps.setString(5, target.userId());
                ps.executeUpdate();
            }
            execute("COMMIT");
            startedTx = false;
            return findById(target.userId()).orElseThrow();
        } catch (AccountException ex) {
            if (startedTx) rollbackQuietly();
            throw ex;
        } catch (SQLException err) {
            if (startedTx) rollbackQuietly();
            throw new IllegalStateException("Failed to link GitHub identity", err);
        }
    }

    /** Unlinks GitHub identity from a user account without deleting the local account. */
    public synchronized Account unlinkGitHub(String userId) {
        Account target = findById(userId)
                .orElseThrow(() -> new AccountException(404, "ACCOUNT_NOT_FOUND", "User account not found."));
        if (!target.hasPassword()) {
            throw new AccountException(409, "PASSWORD_REQUIRED_BEFORE_UNLINK",
                    "Set a local password in Profile & Security before unlinking GitHub so you cannot lose access to your account.");
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE user_accounts SET github_user_id = NULL, github_login = '', github_avatar_url = '', "
                        + "updated_at_ms = ? WHERE user_id = ?")) {
            ps.setLong(1, now);
            ps.setString(2, target.userId());
            ps.executeUpdate();
            return findById(target.userId()).orElseThrow();
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to unlink GitHub identity", err);
        }
    }

    /**
     * Finds or auto-provisions a unified workspace account when signing in directly via GitHub OAuth / Device Flow / PAT.
     * The very first account in the workspace receives {@code ADMIN}; subsequent accounts receive {@code MEMBER}.
     */
    public synchronized Account upsertFromGitHubSignIn(
            String githubUserId,
            String githubLogin,
            String displayName,
            String email,
            String avatarUrl) {
        String cleanGhId = Objects.requireNonNull(githubUserId, "githubUserId").strip();
        String cleanLogin = Objects.requireNonNull(githubLogin, "githubLogin").strip();
        String safeAvatar = AuthenticatedUser.safeAvatarUrl(avatarUrl);
        String safeName = (displayName == null || displayName.isBlank()) ? cleanLogin : validateDisplayName(displayName);
        boolean startedTx = false;
        try {
            execute("BEGIN IMMEDIATE");
            startedTx = true;
            Optional<Account> existing = findByGitHubUserId(cleanGhId);
            long now = System.currentTimeMillis();
            if (existing.isPresent()) {
                Account acc = existing.orElseThrow();
                if (acc.disabled()) {
                    execute("ROLLBACK");
                    startedTx = false;
                    throw new AccountException(403, "ACCOUNT_DISABLED",
                            "This user account has been disabled by an administrator.");
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE user_accounts SET github_login = ?, github_avatar_url = ?, updated_at_ms = ? WHERE user_id = ?")) {
                    ps.setString(1, cleanLogin);
                    ps.setString(2, safeAvatar);
                    ps.setLong(3, now);
                    ps.setString(4, acc.userId());
                    ps.executeUpdate();
                }
                execute("COMMIT");
                startedTx = false;
                return findById(acc.userId()).orElseThrow();
            }

            String role = countTotalUsers() == 0 ? ROLE_ADMIN : ROLE_MEMBER;
            String userId = "usr_" + randomHex(16);
            String baseUsername = sanitizeGitHubUsername(cleanLogin);
            String uniqueUsername = allocateUniqueUsername(baseUsername);
            String safeEmail = AuthenticatedUser.safeEmail(email, cleanGhId, cleanLogin);
            String uniqueEmail = allocateUniqueEmail(safeEmail, cleanGhId, cleanLogin);

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO user_accounts (user_id, username, username_lower, email, email_lower, "
                            + "display_name, password_hash, password_salt, password_iterations, role, "
                            + "github_user_id, github_login, github_avatar_url, disabled, created_at_ms, updated_at_ms) "
                            + "VALUES (?, ?, ?, ?, ?, ?, '', '', 0, ?, ?, ?, ?, 0, ?, ?)")) {
                ps.setString(1, userId);
                ps.setString(2, uniqueUsername);
                ps.setString(3, uniqueUsername.toLowerCase(Locale.ROOT));
                ps.setString(4, uniqueEmail);
                ps.setString(5, uniqueEmail.toLowerCase(Locale.ROOT));
                ps.setString(6, safeName);
                ps.setString(7, role);
                ps.setString(8, cleanGhId);
                ps.setString(9, cleanLogin);
                ps.setString(10, safeAvatar);
                ps.setLong(11, now);
                ps.setLong(12, now);
                ps.executeUpdate();
            }
            execute("COMMIT");
            startedTx = false;
            return findById(userId).orElseThrow();
        } catch (AccountException ex) {
            if (startedTx) rollbackQuietly();
            throw ex;
        } catch (SQLException err) {
            if (startedTx) rollbackQuietly();
            throw new IllegalStateException("Failed to provision GitHub account", err);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    private void verifyStoredPassword(String userId, char[] candidate) throws SQLException {
        String hashB64 = "";
        String saltB64 = "";
        int iterations = PBKDF2_ITERATIONS;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT password_hash, password_salt, password_iterations FROM user_accounts WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    hashB64 = rs.getString("password_hash");
                    saltB64 = rs.getString("password_salt");
                    iterations = rs.getInt("password_iterations");
                }
            }
        }
        if (hashB64 == null || hashB64.isBlank() || saltB64 == null || saltB64.isBlank()) {
            throw new AccountException(401, "INVALID_CREDENTIALS", "Current password is incorrect.");
        }
        byte[] expected = Base64.getDecoder().decode(hashB64);
        byte[] actual = derivePbkdf2(candidate, Base64.getDecoder().decode(saltB64), Math.max(10_000, iterations));
        boolean ok = MessageDigest.isEqual(expected, actual);
        Arrays.fill(expected, (byte) 0);
        Arrays.fill(actual, (byte) 0);
        if (!ok) {
            throw new AccountException(401, "INVALID_CREDENTIALS", "Current password is incorrect.");
        }
    }

    private Optional<Account> findByGitHubUserId(String ghId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT user_id, username, display_name, email, role, github_user_id, github_login, "
                        + "github_avatar_url, disabled, password_hash, created_at_ms, updated_at_ms "
                        + "FROM user_accounts WHERE github_user_id = ? LIMIT 1")) {
            ps.setString(1, ghId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readAccount(rs)) : Optional.empty();
            }
        }
    }

    private boolean existsUsername(String usernameLower) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM user_accounts WHERE username_lower = ? LIMIT 1")) {
            ps.setString(1, usernameLower);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean existsEmail(String emailLower) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM user_accounts WHERE email_lower = ? LIMIT 1")) {
            ps.setString(1, emailLower);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String allocateSequentialUsername(String base) throws SQLException {
        if (!AuthenticatedUser.LOCAL_USER_ID.equalsIgnoreCase(base)
                && !existsUsername(base.toLowerCase(Locale.ROOT))) {
            return base;
        }
        for (int n = 2; n <= 9999; n++) {
            String suffix = "-" + n;
            int maxBase = Math.max(2, 40 - suffix.length());
            String prefix = base.length() > maxBase ? base.substring(0, maxBase) : base;
            prefix = prefix.replaceAll("[^a-z0-9]+$", "");
            if (prefix.isEmpty() || !Character.isLetterOrDigit(prefix.charAt(0))) {
                prefix = "user";
            }
            String candidate = prefix + suffix;
            if (!AuthenticatedUser.LOCAL_USER_ID.equalsIgnoreCase(candidate)
                    && !existsUsername(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return "user-" + randomHex(6);
    }

    static String deriveUsernameBaseFromEmail(String email) {
        int at = email.indexOf('@');
        String localPart = (at > 0 ? email.substring(0, at) : email).toLowerCase(Locale.ROOT);
        String clean = localPart
                .replaceAll("[^a-z0-9_-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[^a-z0-9]+", "")
                .replaceAll("[^a-z0-9]+$", "");
        if (clean.isEmpty()) {
            clean = "user";
        } else if (clean.length() < 2
                || !Character.isLetterOrDigit(clean.charAt(0))
                || AuthenticatedUser.LOCAL_USER_ID.equalsIgnoreCase(clean)) {
            clean = "user-" + clean;
        }
        if (clean.length() > 40) {
            clean = clean.substring(0, 40).replaceAll("[^a-z0-9]+$", "");
            if (clean.length() < 2) {
                clean = "user";
            }
        }
        if (AuthenticatedUser.LOCAL_USER_ID.equalsIgnoreCase(clean)) {
            clean = "user-local";
        }
        return clean;
    }

    private String allocateUniqueUsername(String base) throws SQLException {
        String candidate = base;
        for (int i = 0; i < 64; i++) {
            if (!existsUsername(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
            String suffix = "-" + (i == 0 ? randomHex(2) : (i + 1));
            int maxBase = Math.max(2, 40 - suffix.length());
            candidate = (base.length() > maxBase ? base.substring(0, maxBase) : base) + suffix;
        }
        return "gh-" + randomHex(8);
    }

    private String allocateUniqueEmail(String preferred, String ghId, String ghLogin) throws SQLException {
        if (!existsEmail(preferred.toLowerCase(Locale.ROOT))) {
            return preferred;
        }
        String cleanLogin = ghLogin.replaceAll("[^A-Za-z0-9._-]", "");
        if (cleanLogin.isEmpty()) cleanLogin = "user";
        String noreply = ghId + "+" + cleanLogin + "@users.noreply.github.com";
        if (!existsEmail(noreply.toLowerCase(Locale.ROOT))) {
            return noreply;
        }
        return ghId + "+" + randomHex(4) + "@users.noreply.github.com";
    }

    private int countTotalUsers() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM user_accounts");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private int countOtherActiveAdmins(String excludedUserId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM user_accounts WHERE role = 'ADMIN' AND disabled = 0 AND user_id != ?")) {
            ps.setString(1, excludedUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static Account readAccount(ResultSet rs) throws SQLException {
        String ghId = rs.getString("github_user_id");
        String ghLogin = rs.getString("github_login");
        String ghAvatar = rs.getString("github_avatar_url");
        String pwHash = rs.getString("password_hash");
        return new Account(
                rs.getString("user_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("role"),
                ghId != null && !ghId.isBlank(),
                ghId == null ? "" : ghId,
                ghLogin == null ? "" : ghLogin,
                ghAvatar == null ? "" : ghAvatar,
                rs.getInt("disabled") != 0,
                pwHash != null && !pwHash.isBlank(),
                rs.getLong("created_at_ms"),
                rs.getLong("updated_at_ms"));
    }

    private void execute(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    private void rollbackQuietly() {
        try {
            execute("ROLLBACK");
        } catch (SQLException ignored) {
        }
    }

    private static byte[] derivePbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(PBKDF2_ALGORITHM + " unavailable", impossible);
        } finally {
            spec.clearPassword();
        }
    }

    private String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String validateEmail(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 254
                || raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0 || raw.indexOf('\0') >= 0) {
            throw new AccountException(400, "INVALID_EMAIL", "Please enter a valid email address.");
        }
        String trimmed = raw.strip();
        if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
            throw new AccountException(400, "INVALID_EMAIL", "Please enter a valid email address.");
        }
        return trimmed;
    }

    private static String validateUsername(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AccountException(400, "INVALID_USERNAME",
                    "Username must be 2–40 characters (letters, digits, '.', '_', '-').");
        }
        String trimmed = raw.strip();
        if (AuthenticatedUser.LOCAL_USER_ID.equalsIgnoreCase(trimmed) || !USERNAME_PATTERN.matcher(trimmed).matches()) {
            throw new AccountException(400, "INVALID_USERNAME",
                    "Username must be 2–40 characters (letters, digits, '.', '_', '-') and not reserved.");
        }
        return trimmed;
    }

    private static String sanitizeGitHubUsername(String raw) {
        String clean = raw == null ? "" : raw.strip().replaceAll("[^A-Za-z0-9._-]", "-");
        if (clean.length() < 2 || !Character.isLetterOrDigit(clean.charAt(0))
                || AuthenticatedUser.LOCAL_USER_ID.equalsIgnoreCase(clean)) {
            clean = "gh-" + (clean.isEmpty() ? "user" : clean);
        }
        return clean.length() > 40 ? clean.substring(0, 40) : clean;
    }

    private static String validateDisplayName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AccountException(400, "INVALID_DISPLAY_NAME", "Display name must not be empty.");
        }
        String trimmed = raw.strip();
        if (trimmed.length() > 120 || trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\0') >= 0) {
            throw new AccountException(400, "INVALID_DISPLAY_NAME", "Display name exceeds 120 characters or contains invalid characters.");
        }
        return trimmed;
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < 8 || password.length > 128) {
            throw new AccountException(400, "WEAK_PASSWORD", "Password must be between 8 and 128 characters.");
        }
    }

    private static String normalizeRole(String rawRole) {
        if (rawRole == null || rawRole.isBlank()) {
            throw new AccountException(400, "INVALID_ROLE", "Role must be ADMIN or MEMBER.");
        }
        String upper = rawRole.strip().toUpperCase(Locale.ROOT);
        if (!ROLE_ADMIN.equals(upper) && !ROLE_MEMBER.equals(upper)) {
            throw new AccountException(400, "INVALID_ROLE", "Role must be ADMIN or MEMBER.");
        }
        return upper;
    }

    /* ── Scoped Personal Access Tokens (SHA-256 hash-only storage) ─────────── */

    /**
     * Mints a scoped Personal Access Token for {@code userId}. Stores ONLY the SHA-256 hex digest,
     * short display prefix, label, scopes, and timestamps in SQLite; returns the plaintext token
     * ({@code evo_pat_...}) exactly once.
     */
    public synchronized MintedToken createPersonalAccessToken(
            String userId,
            String rawLabel,
            List<String> rawScopes,
            Optional<Integer> expiresInDaysOpt) {
        Account owner = findById(userId)
                .orElseThrow(() -> new AccountException(404, "ACCOUNT_NOT_FOUND", "User account not found."));
        if (owner.disabled()) {
            throw new AccountException(403, "ACCOUNT_DISABLED", "Disabled accounts cannot mint access tokens.");
        }
        String label = validateTokenLabel(rawLabel);
        List<String> scopes = normalizeTokenScopes(rawScopes);
        int days = expiresInDaysOpt == null || expiresInDaysOpt.isEmpty()
                ? DEFAULT_PAT_EXPIRY_DAYS
                : expiresInDaysOpt.orElseThrow();
        if (days < 1 || days > MAX_PAT_EXPIRY_DAYS) {
            throw new AccountException(400, "INVALID_EXPIRY",
                    "Token expiry must be between 1 and " + MAX_PAT_EXPIRY_DAYS + " days.");
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + days * 86_400_000L;
        if (countActiveTokensForUser(owner.userId(), now) >= MAX_ACTIVE_PATS_PER_USER) {
            throw new AccountException(409, "TOKEN_LIMIT_EXCEEDED",
                    "Maximum active personal access tokens (" + MAX_ACTIVE_PATS_PER_USER + ") reached. Revoke unused tokens first.");
        }

        byte[] rawSecret = new byte[32];
        random.nextBytes(rawSecret);
        String plaintext = PAT_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(rawSecret);
        Arrays.fill(rawSecret, (byte) 0);

        String tokenHash = sha256HexUtf8(plaintext);
        String tokenPrefix = plaintext.substring(0, Math.min(14, plaintext.length())) + "…";
        String tokenId = "pat_" + randomHex(12);
        String scopesCsv = String.join(",", scopes);

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO personal_access_tokens (token_id, user_id, label, token_prefix, token_hash, scopes, "
                        + "created_at_ms, expires_at_ms, last_used_at_ms, revoked_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0)")) {
            ps.setString(1, tokenId);
            ps.setString(2, owner.userId());
            ps.setString(3, label);
            ps.setString(4, tokenPrefix);
            ps.setString(5, tokenHash);
            ps.setString(6, scopesCsv);
            ps.setLong(7, now);
            ps.setLong(8, expiresAt);
            ps.executeUpdate();
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to create personal access token", err);
        }
        var meta = new PersonalAccessToken(tokenId, owner.userId(), label, tokenPrefix, scopes, now, expiresAt, 0L, false, 0L);
        return new MintedToken(meta, plaintext);
    }

    /** Lists all PAT metadata belonging exclusively to {@code userId} (zero hashes or plaintexts). */
    public synchronized List<PersonalAccessToken> listPersonalAccessTokens(String userId) {
        if (userId == null || userId.isBlank()) return List.of();
        var out = new ArrayList<PersonalAccessToken>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT token_id, user_id, label, token_prefix, scopes, created_at_ms, expires_at_ms, last_used_at_ms, revoked_at_ms "
                        + "FROM personal_access_tokens WHERE user_id = ? ORDER BY created_at_ms DESC, token_id ASC")) {
            ps.setString(1, userId.strip());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readPat(rs));
                }
            }
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to list personal access tokens", err);
        }
        return List.copyOf(out);
    }

    /**
     * Revokes a PAT. Only the token owner or an active {@code ADMIN} may revoke it;
     * cross-user revocation by a non-admin throws {@code 403 TOKEN_ACCESS_DENIED}.
     */
    public synchronized PersonalAccessToken revokePersonalAccessToken(
            String tokenId, String callerUserId, boolean callerIsAdmin) {
        if (tokenId == null || tokenId.isBlank()) {
            throw new AccountException(400, "INVALID_REQUEST", "Missing tokenId.");
        }
        String cleanId = tokenId.strip();
        PersonalAccessToken existing = findPatById(cleanId)
                .orElseThrow(() -> new AccountException(404, "TOKEN_NOT_FOUND", "Personal access token not found."));
        if (!callerIsAdmin && !existing.userId().equals(callerUserId)) {
            throw new AccountException(403, "TOKEN_ACCESS_DENIED", "You do not have permission to revoke this token.");
        }
        if (existing.revoked()) {
            return existing;
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE personal_access_tokens SET revoked_at_ms = ? WHERE token_id = ?")) {
            ps.setLong(1, now);
            ps.setString(2, cleanId);
            ps.executeUpdate();
        } catch (SQLException err) {
            throw new IllegalStateException("Failed to revoke personal access token", err);
        }
        return new PersonalAccessToken(existing.tokenId(), existing.userId(), existing.label(), existing.tokenPrefix(),
                existing.scopes(), existing.createdAtMs(), existing.expiresAtMs(), existing.lastUsedAtMs(), true, now);
    }

    /**
     * Authenticates a Bearer {@code evo_pat_...} token via SHA-256 constant-time hash comparison.
     * Rejects revoked, expired, unknown, or disabled-owner tokens.
     */
    public synchronized Optional<ResolvedToken> authenticatePersonalAccessToken(String rawBearerToken) {
        if (rawBearerToken == null || !rawBearerToken.startsWith(PAT_PREFIX)
                || rawBearerToken.length() < 24 || rawBearerToken.length() > 160) {
            return Optional.empty();
        }
        String candidateHash = sha256HexUtf8(rawBearerToken);
        PersonalAccessToken pat = null;
        String storedHash = null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT token_id, user_id, label, token_prefix, token_hash, scopes, created_at_ms, expires_at_ms, last_used_at_ms, revoked_at_ms "
                        + "FROM personal_access_tokens WHERE token_hash = ? LIMIT 1")) {
            ps.setString(1, candidateHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    pat = readPat(rs);
                    storedHash = rs.getString("token_hash");
                }
            }
        } catch (SQLException err) {
            throw new IllegalStateException("PAT lookup failed", err);
        }
        if (pat == null || storedHash == null) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(
                storedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                candidateHash.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (pat.revoked() || (pat.expiresAtMs() > 0 && now > pat.expiresAtMs())) {
            return Optional.empty();
        }
        Optional<Account> accOpt = findById(pat.userId());
        if (accOpt.isEmpty() || accOpt.orElseThrow().disabled()) {
            return Optional.empty();
        }
        // Throttle last_used_at_ms writes to once per minute per token to avoid SQLite write amplification
        if (now - pat.lastUsedAtMs() >= 60_000L) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE personal_access_tokens SET last_used_at_ms = ? WHERE token_id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, pat.tokenId());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
            pat = new PersonalAccessToken(pat.tokenId(), pat.userId(), pat.label(), pat.tokenPrefix(),
                    pat.scopes(), pat.createdAtMs(), pat.expiresAtMs(), now, false, 0L);
        }
        return Optional.of(new ResolvedToken(accOpt.orElseThrow(), pat));
    }

    private Optional<PersonalAccessToken> findPatById(String tokenId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT token_id, user_id, label, token_prefix, scopes, created_at_ms, expires_at_ms, last_used_at_ms, revoked_at_ms "
                        + "FROM personal_access_tokens WHERE token_id = ? LIMIT 1")) {
            ps.setString(1, tokenId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readPat(rs)) : Optional.empty();
            }
        } catch (SQLException err) {
            throw new IllegalStateException("PAT lookup failed", err);
        }
    }

    private int countActiveTokensForUser(String userId, long now) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM personal_access_tokens WHERE user_id = ? AND revoked_at_ms = 0 AND (expires_at_ms = 0 OR expires_at_ms > ?)")) {
            ps.setString(1, userId);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException err) {
            throw new IllegalStateException("PAT count failed", err);
        }
    }

    private static PersonalAccessToken readPat(ResultSet rs) throws SQLException {
        String rawScopes = rs.getString("scopes");
        List<String> scopes = rawScopes == null || rawScopes.isBlank()
                ? List.of("mcp:read")
                : Arrays.stream(rawScopes.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        long revokedAt = rs.getLong("revoked_at_ms");
        return new PersonalAccessToken(
                rs.getString("token_id"),
                rs.getString("user_id"),
                rs.getString("label"),
                rs.getString("token_prefix"),
                scopes,
                rs.getLong("created_at_ms"),
                rs.getLong("expires_at_ms"),
                rs.getLong("last_used_at_ms"),
                revokedAt > 0L,
                revokedAt);
    }

    private static String validateTokenLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AccountException(400, "INVALID_TOKEN_LABEL", "Token label is required (1–80 characters).");
        }
        String trimmed = raw.strip();
        if (trimmed.length() > 80 || trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\0') >= 0) {
            throw new AccountException(400, "INVALID_TOKEN_LABEL", "Token label must be 1–80 printable characters.");
        }
        return trimmed;
    }

    private static List<String> normalizeTokenScopes(List<String> rawScopes) {
        if (rawScopes == null || rawScopes.isEmpty()) {
            return List.of("mcp:read");
        }
        var out = new TreeSet<String>();
        for (String s : rawScopes) {
            if (s == null || s.isBlank()) continue;
            String norm = s.strip().toLowerCase(Locale.ROOT);
            if (!VALID_PAT_SCOPES.contains(norm)) {
                throw new AccountException(400, "INVALID_TOKEN_SCOPE",
                        "Unsupported token scope: " + norm + ". Allowed: mcp:read, api:read, api:write.");
            }
            out.add(norm);
        }
        if (out.isEmpty()) {
            throw new AccountException(400, "INVALID_TOKEN_SCOPE", "At least one token scope is required.");
        }
        return List.copyOf(out);
    }

    private static String sha256HexUtf8(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
