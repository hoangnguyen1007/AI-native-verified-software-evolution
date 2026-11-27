package com.evolution.backend.auth;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.github.GitHubCoordinates;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Zero-environment-variable encrypted storage for in-app GitHub OAuth / App credentials and shared master key.
 *
 * <p>Stores an auto-generated 256-bit master key at {@code <store>/auth/master.key} (owner-only permissions; or uses
 * operator-supplied {@code EVO_SESSION_SECRET} >= 32 bytes when present) and encrypts
 * {@code <store>/auth/oauth-config.enc} with AES-256-GCM + fresh 96-bit IV + AAD on every save.
 * Zero plaintext {@code clientSecret} or {@code webhookSecret} values ever touch disk.
 */
public final class GitHubOAuthConfigStore {
    public static final String SCHEMA = "evo.github.oauth-config.aes256gcm:v1";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MASTER_KEY_BYTES = 32;
    private static final System.Logger LOG = System.getLogger(GitHubOAuthConfigStore.class.getName());

    public record StoredConfig(
            String clientId,
            String clientSecret,
            String host,
            String scopes,
            boolean deviceFlowEnabled,
            boolean githubAppEnabled,
            String webhookSecret,
            String appSlug,
            String appId,
            String publicBaseUrl,
            long updatedAtMs) {

        public StoredConfig {
            clientId = clientId == null ? "" : clientId.strip();
            clientSecret = clientSecret == null ? "" : clientSecret.strip();
            host = (host == null || host.isBlank()) ? GitHubCoordinates.DEFAULT_HOST : host.strip().toLowerCase(Locale.ROOT);
            scopes = (scopes == null || scopes.isBlank()) ? GitHubOAuthService.DEFAULT_SCOPES : scopes.strip();
            webhookSecret = webhookSecret == null ? "" : webhookSecret.strip();
            appSlug = appSlug == null ? "" : appSlug.strip();
            appId = appId == null ? "" : appId.strip();
            publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.strip();
        }
    }

    private final Path authDir;
    private final Path masterKeyFile;
    private final Path configFile;
    private final SecureRandom random = new SecureRandom();
    private final byte[] masterKeyBytes;
    private final SecretKey aesKey;

    public GitHubOAuthConfigStore(Path authDir, Function<String, String> envReader) {
        this.authDir = Objects.requireNonNull(authDir, "authDir").toAbsolutePath().normalize();
        this.masterKeyFile = this.authDir.resolve("master.key");
        this.configFile = this.authDir.resolve("oauth-config.enc");
        this.masterKeyBytes = loadOrCreateMasterKey(this.authDir, this.masterKeyFile, envReader, this.random);
        this.aesKey = deriveAesKey(this.masterKeyBytes);
    }

    public Path configFile() {
        return configFile;
    }

    public Path masterKeyFile() {
        return masterKeyFile;
    }

    /** Returns a defensive copy of the 32-byte master secret so {@link UserSessionStore} can share zero-env persistence. */
    public byte[] masterKeyBytes() {
        return masterKeyBytes.clone();
    }

    public synchronized Optional<StoredConfig> load() {
        if (!Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(configFile, StandardCharsets.UTF_8);
            var env = GraphJson.object(GraphJson.read(raw, new GraphJson.Limits(1024 * 1024, 32, 10_000)));
            if (!SCHEMA.equals(env.get("schema"))) {
                return Optional.empty();
            }
            byte[] iv = Base64.getDecoder().decode(String.valueOf(env.get("iv")));
            byte[] ciphertext = Base64.getDecoder().decode(String.valueOf(env.get("ciphertext")));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(SCHEMA.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(ciphertext);
            String json = new String(plaintext, StandardCharsets.UTF_8);
            Arrays.fill(plaintext, (byte) 0);

            var row = GraphJson.object(GraphJson.read(json, new GraphJson.Limits(1024 * 1024, 32, 10_000)));
            String clientId = str(row.get("clientId"), "");
            if (clientId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new StoredConfig(
                    clientId,
                    str(row.get("clientSecret"), ""),
                    str(row.get("host"), GitHubCoordinates.DEFAULT_HOST),
                    str(row.get("scopes"), GitHubOAuthService.DEFAULT_SCOPES),
                    !Boolean.FALSE.equals(row.get("deviceFlowEnabled")),
                    Boolean.TRUE.equals(row.get("githubAppEnabled")),
                    str(row.get("webhookSecret"), ""),
                    str(row.get("appSlug"), ""),
                    str(row.get("appId"), ""),
                    str(row.get("publicBaseUrl"), ""),
                    longVal(row.get("updatedAtMs"), 0L)));
        } catch (IOException | GeneralSecurityException | RuntimeException corrupt) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring unreadable encrypted OAuth config ({0})",
                    corrupt.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public synchronized void save(StoredConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");
        if (cfg.clientId().isBlank()) {
            throw new IllegalArgumentException("clientId is required");
        }
        try {
            Files.createDirectories(authDir);
            var row = new LinkedHashMap<String, Object>();
            row.put("clientId", cfg.clientId());
            row.put("clientSecret", cfg.clientSecret());
            row.put("host", cfg.host());
            row.put("scopes", cfg.scopes());
            row.put("deviceFlowEnabled", cfg.deviceFlowEnabled());
            row.put("githubAppEnabled", cfg.githubAppEnabled());
            row.put("webhookSecret", cfg.webhookSecret());
            row.put("appSlug", cfg.appSlug());
            row.put("appId", cfg.appId());
            row.put("publicBaseUrl", cfg.publicBaseUrl());
            row.put("updatedAtMs", cfg.updatedAtMs() > 0L ? cfg.updatedAtMs() : System.currentTimeMillis());

            byte[] plaintext = CanonicalJson.write(row).getBytes(StandardCharsets.UTF_8);
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(SCHEMA.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            Arrays.fill(plaintext, (byte) 0);

            var envelope = new LinkedHashMap<String, Object>();
            envelope.put("schema", SCHEMA);
            envelope.put("iv", Base64.getEncoder().encodeToString(iv));
            envelope.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
            byte[] encoded = CanonicalJson.write(envelope).getBytes(StandardCharsets.UTF_8);

            Path tmp = Files.createTempFile(authDir, "oauth-config-", ".tmp");
            try {
                Files.write(tmp, encoded);
                restrictOwnerOnly(tmp);
                try {
                    Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException fallback) {
                    Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING);
                }
                restrictOwnerOnly(configFile);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | GeneralSecurityException err) {
            throw new IllegalStateException("Failed to persist encrypted GitHub OAuth configuration", err);
        }
    }

    public synchronized boolean clear() {
        try {
            return Files.deleteIfExists(configFile);
        } catch (IOException err) {
            throw new IllegalStateException("Failed to delete encrypted GitHub OAuth configuration", err);
        }
    }

    private static byte[] loadOrCreateMasterKey(Path authDir, Path masterKeyFile,
            Function<String, String> envReader, SecureRandom random) {
        Optional<byte[]> fromEnv = UserSessionStore.secretFromEnvironment(envReader);
        if (fromEnv.isPresent()) {
            return fromEnv.orElseThrow();
        }
        try {
            if (Files.isRegularFile(masterKeyFile, LinkOption.NOFOLLOW_LINKS)) {
                String encoded = Files.readString(masterKeyFile, StandardCharsets.UTF_8).strip();
                byte[] decoded = Base64.getDecoder().decode(encoded);
                if (decoded.length >= MASTER_KEY_BYTES) {
                    return decoded;
                }
            }
            Files.createDirectories(authDir);
            byte[] generated = new byte[MASTER_KEY_BYTES];
            random.nextBytes(generated);
            String encoded = Base64.getEncoder().encodeToString(generated);
            Path tmp = Files.createTempFile(authDir, "master-key-", ".tmp");
            try {
                Files.writeString(tmp, encoded, StandardCharsets.UTF_8);
                restrictOwnerOnly(tmp);
                try {
                    Files.move(tmp, masterKeyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException fallback) {
                    Files.move(tmp, masterKeyFile, StandardCopyOption.REPLACE_EXISTING);
                }
                restrictOwnerOnly(masterKeyFile);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return generated;
        } catch (IOException | IllegalArgumentException err) {
            throw new IllegalStateException("Failed to initialize auth master key at " + masterKeyFile, err);
        }
    }

    private static void restrictOwnerOnly(Path path) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                return;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = acl.getOwner();
                if (owner != null) {
                    AclEntry ownerEntry = AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(owner)
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                            .build();
                    acl.setAcl(List.of(ownerEntry));
                }
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Best-effort owner-only file permissions on current filesystem.
        }
    }

    private static SecretKey deriveAesKey(byte[] masterKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update("evo-github-oauth-config:v1:".getBytes(StandardCharsets.UTF_8));
            sha256.update(masterKey);
            return new SecretKeySpec(sha256.digest(), "AES");
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String str(Object value, String fallback) {
        return value instanceof String s ? s : fallback;
    }

    private static long longVal(Object value, long fallback) {
        if (value instanceof java.math.BigDecimal bd) return bd.longValue();
        if (value instanceof Number n) return n.longValue();
        return fallback;
    }
}
