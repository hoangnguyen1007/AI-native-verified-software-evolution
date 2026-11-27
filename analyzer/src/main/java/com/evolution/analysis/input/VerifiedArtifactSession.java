package com.evolution.analysis.input;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Job-scoped cryptographic artifact verification session.
 *
 * <p>Within a single multi-module analysis job, identical immutable platform/dependency archives
 * (JDK JMODs, Maven/Gradle JARs, Android SDK JARs) are referenced across dozens or hundreds of
 * build contexts. Opening a {@link VerifiedArtifactSession} hashes each unique canonical file path
 * once for the lifetime of the session while preserving strict per-request disk verification for
 * standalone calls outside any active session.
 *
 * <p>Toggleable via {@code -Devo.perf.artifactSession=true|false} (default {@code true}).
 */
public final class VerifiedArtifactSession implements AutoCloseable {
    private static final String ENABLED_PROP = "evo.perf.artifactSession";
    private static final int IO_BUFFER_BYTES = 64 * 1024;
    private static final ThreadLocal<VerifiedArtifactSession> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    });

    /** Lightweight attachment scope for worker threads sharing a job's verification session. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public record VerifiedFile(Path realPath, ContentDigest digest, long size) {
        public VerifiedFile {
            Objects.requireNonNull(realPath);
            Objects.requireNonNull(digest);
        }
    }

    private final boolean enabled;
    private final VerifiedArtifactSession previous;
    private final ConcurrentHashMap<Path, VerifiedFile> verifiedFiles = new ConcurrentHashMap<>(128);
    private final Set<ContentDigest> validatedArchives = ConcurrentHashMap.newKeySet(128);
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    private VerifiedArtifactSession(boolean enabled, VerifiedArtifactSession previous) {
        this.enabled = enabled;
        this.previous = previous;
    }

    /** Returns whether session-scoped verification caching is enabled by JVM property. */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROP, "true"));
    }

    /**
     * Opens a new job-scoped verification session bound to the current thread.
     * Always close in a {@code try}-with-resources block so state never leaks across jobs.
     */
    public static VerifiedArtifactSession open() {
        return open(isEnabled());
    }

    /** Opens a new session with an explicit enabled flag (useful for deterministic tests). */
    public static VerifiedArtifactSession open(boolean enabled) {
        VerifiedArtifactSession prior = ACTIVE.get();
        VerifiedArtifactSession session = new VerifiedArtifactSession(enabled, prior);
        if (enabled) {
            ACTIVE.set(session);
        } else {
            ACTIVE.remove();
        }
        return session;
    }

    /** Returns the active verification session on the current thread, if any and still open. */
    public static Optional<VerifiedArtifactSession> current() {
        VerifiedArtifactSession session = ACTIVE.get();
        if (session == null || !session.enabled || session.closed.get()) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /** Attaches this session to the calling worker thread until the returned {@link Scope} closes. */
    public Scope attach() {
        if (!enabled || closed.get()) {
            return () -> {};
        }
        VerifiedArtifactSession prior = ACTIVE.get();
        ACTIVE.set(this);
        return () -> {
            if (prior == null || prior.closed.get()) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(prior);
            }
        };
    }

    /** Wraps a {@link Runnable} so it executes attached to this verification session. */
    public Runnable wrap(Runnable task) {
        Objects.requireNonNull(task);
        if (!enabled) {
            return task;
        }
        return () -> {
            try (Scope ignored = attach()) {
                task.run();
            }
        };
    }

    /** Wraps a {@link Callable} so it executes attached to this verification session. */
    public <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task);
        if (!enabled) {
            return task;
        }
        return () -> {
            try (Scope ignored = attach()) {
                return task.call();
            }
        };
    }

    /**
     * Returns the SHA-256 digest and byte length of {@code path}, computing it at most once per
     * canonical real path while this session is open.
     */
    public VerifiedFile digestFile(Path path) throws IOException {
        Objects.requireNonNull(path);
        Path real = resolveRegularRealPath(path);
        if (!enabled || closed.get()) {
            misses.incrementAndGet();
            return hashRegularFile(real);
        }
        VerifiedFile existing = verifiedFiles.get(real);
        if (existing != null) {
            hits.incrementAndGet();
            return existing;
        }
        VerifiedFile computed = hashRegularFile(real);
        VerifiedFile raced = verifiedFiles.putIfAbsent(real, computed);
        if (raced != null) {
            hits.incrementAndGet();
            return raced;
        }
        misses.incrementAndGet();
        return computed;
    }

    /**
     * Verifies that {@code path} has SHA-256 equal to {@code expected}, caching the verified file
     * digest within this session on first success.
     */
    public boolean verifyDigest(Path path, ContentDigest expected) throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(expected);
        return expected.equals(digestFile(path).digest());
    }

    /**
     * Records an already SHA-256-verified file (for example, right after streaming its bytes in
     * memory) so subsequent modules in the same session do not re-read it from disk.
     */
    public void recordVerified(Path path, ContentDigest digest, long size) {
        if (!enabled || closed.get() || path == null || digest == null || size < 0) {
            return;
        }
        try {
            Path real = resolveRegularRealPath(path);
            verifiedFiles.putIfAbsent(real, new VerifiedFile(real, digest, size));
        } catch (IOException ignored) {
            // Best-effort recording; unverified path will still hash normally on demand.
        }
    }

    /** Returns whether an archive with {@code digest} has already passed zip/CRC validation in this session. */
    public boolean isArchiveValidated(ContentDigest digest) {
        return enabled && !closed.get() && digest != null && validatedArchives.contains(digest);
    }

    /** Marks an archive with {@code digest} as structurally validated within this session. */
    public void markArchiveValidated(ContentDigest digest) {
        if (enabled && !closed.get() && digest != null) {
            validatedArchives.add(digest);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public int uniqueVerifiedFiles() {
        return verifiedFiles.size();
    }

    /**
     * Convenience helper that checks the active session if one exists on the current thread, or
     * performs a direct uncached disk SHA-256 verification for standalone calls.
     */
    public static boolean verifyFileOrDirect(Path path, ContentDigest expected) throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(expected);
        VerifiedArtifactSession active = ACTIVE.get();
        if (active != null && active.enabled && !active.closed.get()) {
            return active.verifyDigest(path, expected);
        }
        return expected.equals(hashRegularFile(resolveRegularRealPath(path)).digest());
    }

    private static Path resolveRegularRealPath(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Artifact path cannot be a symbolic link: " + path);
        }
        Path real = path.toRealPath();
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Artifact path is not a regular file: " + path);
        }
        return real;
    }

    private static VerifiedFile hashRegularFile(Path real) throws IOException {
        MessageDigest md = SHA256.get();
        md.reset();
        byte[] buffer = new byte[IO_BUFFER_BYTES];
        long total = 0;
        try (InputStream in = Files.newInputStream(real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    md.update(buffer, 0, read);
                    total += read;
                }
            }
        }
        ContentDigest digest = new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest()));
        return new VerifiedFile(real, digest, total);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            verifiedFiles.clear();
            validatedArchives.clear();
        }
        if (ACTIVE.get() == this) {
            if (previous == null || !previous.enabled || previous.closed.get()) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }
}
