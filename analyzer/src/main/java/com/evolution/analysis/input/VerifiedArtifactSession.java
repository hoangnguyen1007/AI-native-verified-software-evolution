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

    public record VerifiedJar(Path realPath, ContentDigest digest, long size, byte[] header) {
        public VerifiedJar {
            Objects.requireNonNull(realPath);
            Objects.requireNonNull(digest);
            header = header == null ? new byte[0] : header.clone();
        }

        @Override
        public byte[] header() {
            return header.clone();
        }
    }

    public record VerifiedSmallFile(Path realPath, ContentDigest digest, byte[] bytes) {
        public VerifiedSmallFile {
            Objects.requireNonNull(realPath);
            Objects.requireNonNull(digest);
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static final int MAX_CACHED_SMALL_FILES = 2048;
    private static final long MAX_CACHED_SMALL_FILE_BYTES = 512_000L;
    private static final long MAX_CACHED_SMALL_TOTAL_BYTES = 32L * 1024L * 1024L;

    private final boolean enabled;
    private final VerifiedArtifactSession previous;
    private final ConcurrentHashMap<Path, Path> verifiedRealPaths = new ConcurrentHashMap<>(128);
    private final ConcurrentHashMap<Path, VerifiedFile> verifiedFiles = new ConcurrentHashMap<>(128);
    private final ConcurrentHashMap<Path, VerifiedJar> verifiedJars = new ConcurrentHashMap<>(128);
    private final ConcurrentHashMap<Path, VerifiedSmallFile> verifiedSmallFiles = new ConcurrentHashMap<>(128);
    private final AtomicLong cachedSmallFileBytes = new AtomicLong();
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
        if (enabled && !closed.get()) {
            Path knownReal = verifiedRealPaths.get(path);
            if (knownReal != null) {
                VerifiedFile existing = verifiedFiles.get(knownReal);
                if (existing != null) {
                    hits.incrementAndGet();
                    return existing;
                }
            }
        }
        Path real = resolveRegularRealPath(path);
        if (!enabled || closed.get()) {
            misses.incrementAndGet();
            return hashRegularFile(real);
        }
        verifiedRealPaths.putIfAbsent(path.toAbsolutePath().normalize(), real);
        verifiedRealPaths.putIfAbsent(real, real);
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

    /**
     * Resolves {@code path} to its canonical real path at most once per normalized path in this session,
     * eliminating repeated OS realpath/directory-walk syscalls for immutable cache artifacts.
     */
    public Path resolveCachedRealPath(Path path) throws IOException {
        Objects.requireNonNull(path);
        Path normalized = path.toAbsolutePath().normalize();
        if (!enabled || closed.get()) {
            return resolveRegularRealPath(path);
        }
        Path cached = verifiedRealPaths.get(normalized);
        if (cached != null) {
            return cached;
        }
        Path real = resolveRegularRealPath(path);
        verifiedRealPaths.putIfAbsent(normalized, real);
        return real;
    }

    /** Returns a previously verified JAR header/digest for {@code normalizedPath} without extra disk syscalls. */
    public Optional<VerifiedJar> lookupVerifiedJarByPath(Path normalizedPath) {
        if (!enabled || closed.get() || normalizedPath == null) {
            return Optional.empty();
        }
        Path real = verifiedRealPaths.get(normalizedPath);
        if (real == null) {
            return Optional.empty();
        }
        VerifiedJar cached = verifiedJars.get(real);
        if (cached != null) {
            hits.incrementAndGet();
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    /** Returns previously verified small-file bytes for {@code normalizedPath} without extra disk syscalls. */
    public Optional<VerifiedSmallFile> lookupVerifiedSmallFileByPath(Path normalizedPath) {
        if (!enabled || closed.get() || normalizedPath == null) {
            return Optional.empty();
        }
        Path real = verifiedRealPaths.get(normalizedPath);
        if (real == null) {
            return Optional.empty();
        }
        VerifiedSmallFile cached = verifiedSmallFiles.get(real);
        if (cached != null) {
            hits.incrementAndGet();
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    /** Returns a previously verified JAR header/digest for {@code realPath} if its size still matches. */
    public Optional<VerifiedJar> lookupVerifiedJar(Path realPath, long expectedSize) {
        if (!enabled || closed.get() || realPath == null) {
            return Optional.empty();
        }
        VerifiedJar cached = verifiedJars.get(realPath);
        if (cached != null && cached.size() == expectedSize) {
            hits.incrementAndGet();
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    /** Records an already streamed & SHA-256-verified JAR (header + digest + size) in this session. */
    public void recordVerifiedJar(Path realPath, ContentDigest digest, long size, byte[] header) {
        if (!enabled || closed.get() || realPath == null || digest == null || size < 0) {
            return;
        }
        verifiedRealPaths.putIfAbsent(realPath, realPath);
        verifiedFiles.putIfAbsent(realPath, new VerifiedFile(realPath, digest, size));
        verifiedJars.putIfAbsent(realPath, new VerifiedJar(realPath, digest, size, header));
    }

    /** Returns previously verified small-file bytes (e.g. immutable Maven2 POM) if size still matches. */
    public Optional<VerifiedSmallFile> lookupVerifiedSmallFile(Path realPath, long expectedSize) {
        if (!enabled || closed.get() || realPath == null) {
            return Optional.empty();
        }
        VerifiedSmallFile cached = verifiedSmallFiles.get(realPath);
        if (cached != null && cached.bytes.length == expectedSize) {
            hits.incrementAndGet();
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    /** Records an already SHA-256-verified small immutable descriptor (e.g. cached Maven POM) within session budget. */
    public void recordVerifiedSmallFile(Path realPath, ContentDigest digest, byte[] bytes) {
        if (!enabled || closed.get() || realPath == null || digest == null || bytes == null) {
            return;
        }
        verifiedRealPaths.putIfAbsent(realPath, realPath);
        verifiedFiles.putIfAbsent(realPath, new VerifiedFile(realPath, digest, bytes.length));
        if (bytes.length <= MAX_CACHED_SMALL_FILE_BYTES && verifiedSmallFiles.size() < MAX_CACHED_SMALL_FILES) {
            long total = cachedSmallFileBytes.addAndGet(bytes.length);
            if (total <= MAX_CACHED_SMALL_TOTAL_BYTES) {
                if (verifiedSmallFiles.putIfAbsent(realPath, new VerifiedSmallFile(realPath, digest, bytes)) != null) {
                    cachedSmallFileBytes.addAndGet(-bytes.length);
                }
            } else {
                cachedSmallFileBytes.addAndGet(-bytes.length);
            }
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
            verifiedRealPaths.clear();
            verifiedFiles.clear();
            verifiedJars.clear();
            verifiedSmallFiles.clear();
            cachedSmallFileBytes.set(0L);
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
