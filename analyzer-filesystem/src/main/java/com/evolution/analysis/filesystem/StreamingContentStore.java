package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.BooleanSupplier;

/** Disk CAS. A failed write never publishes a handle. Existing evidence is verified, never overwritten. */
public final class StreamingContentStore implements RepositoryContentStore {
    @FunctionalInterface interface Force { void flush(FileChannel channel) throws IOException; }
    @FunctionalInterface interface Space { long usable() throws IOException; }

    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int MAX_POOLED = 16;
    private static final ConcurrentLinkedQueue<byte[]> BUFFER_POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger POOLED_BUFFERS = new AtomicInteger();
    private static final ConcurrentLinkedQueue<MessageDigest> DIGEST_POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger POOLED_DIGESTS = new AtomicInteger();

    private final Path root, staging, sha256Root;
    private final long reserveBytes;
    private final Force force;
    private final Space space;
    private final SmallBlobPacks packs;
    private final AtomicIntegerArray createdShards = new AtomicIntegerArray(256);
    private volatile int loosePresence; // 0 = unknown, 1 = empty, 2 = has shard(s)

    public StreamingContentStore(Path root) throws IOException { this(root, 0); }
    public StreamingContentStore(Path root, long reserveBytes) throws IOException {
        this(root, reserveBytes, channel -> channel.force(true));
    }
    StreamingContentStore(Path root, long reserveBytes, Force force) throws IOException {
        this(root, reserveBytes, force, null);
    }
    StreamingContentStore(Path root, long reserveBytes, Force force, Space space) throws IOException {
        if (reserveBytes < 0) throw new IllegalArgumentException("Negative disk reserve");
        this.force = Objects.requireNonNull(force);
        this.root = directory(root);
        this.staging = directory(this.root.resolve("staging"));
        this.reserveBytes = reserveBytes;
        this.sha256Root = directory(this.root.resolve("sha256"));
        this.packs = new SmallBlobPacks(this.root, reserveBytes);
        var store = space == null ? Files.getFileStore(staging) : null;
        this.space = space == null ? store::getUsableSpace : space;
    }

    static byte[] borrowBuffer() {
        byte[] buffer = BUFFER_POOL.poll();
        if (buffer != null) {
            POOLED_BUFFERS.decrementAndGet();
            return buffer;
        }
        return new byte[BUFFER_BYTES];
    }

    static void releaseBuffer(byte[] buffer) {
        if (buffer != null && buffer.length == BUFFER_BYTES && POOLED_BUFFERS.get() < MAX_POOLED) {
            POOLED_BUFFERS.incrementAndGet();
            BUFFER_POOL.offer(buffer);
        }
    }

    static MessageDigest borrowSha256() {
        MessageDigest digest = DIGEST_POOL.poll();
        if (digest != null) {
            POOLED_DIGESTS.decrementAndGet();
            digest.reset();
            return digest;
        }
        return sha256();
    }

    static void releaseSha256(MessageDigest digest) {
        if (digest != null && POOLED_DIGESTS.get() < MAX_POOLED) {
            digest.reset();
            POOLED_DIGESTS.incrementAndGet();
            DIGEST_POOL.offer(digest);
        }
    }

    static Path directory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        safe(absolute);
        Files.createDirectories(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("UNSAFE_STORAGE");
        return absolute.toRealPath();
    }

    /** Require existing storage without following linked ancestors; canonicalize
     * benign platform aliases such as Windows 8.3 names only after validation. */
    public static Path existingDirectory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        safe(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("UNSAFE_STORAGE");
        return absolute.toRealPath();
    }

    static void safe(Path path) throws IOException {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            try {
                var attributes = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                // Windows junctions are reparse points (isOther), not symbolic links.
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("UNSAFE_PATH");
            } catch (NoSuchFileException missing) { /* Prospective storage paths may not exist yet. */ }
        }
    }

    /** Validate a descendant path up to an already-canonicalized root, including the root itself,
     * without re-statting invariant OS ancestors above the root on every CAS operation. */
    static void safeWithin(Path verifiedRoot, Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(verifiedRoot)) throw new IOException("UNSAFE_PATH");
        for (Path p = normalized; p != null; p = p.getParent()) {
            try {
                var attributes = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("UNSAFE_PATH");
            } catch (NoSuchFileException missing) { /* Prospective storage paths may not exist yet. */ }
            if (p.equals(verifiedRoot)) break;
        }
    }

    static void ensureSubdirectory(Path root, Path dir) throws IOException {
        safeWithin(root, dir);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir);
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("UNSAFE_STORAGE");
        }
    }

    void ensureStaging() throws IOException {
        ensureSubdirectory(root, staging);
    }

    static void createLinkWithRetry(Path target, Path source) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.createLink(target, source);
                return;
            } catch (FileAlreadyExistsException | NoSuchFileException | UnsupportedOperationException exact) {
                throw exact;
            } catch (FileSystemException transientLock) {
                last = transientLock;
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(25L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    static void deleteIfExistsQuietly(Path path) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (NoSuchFileException missing) {
                return;
            } catch (FileSystemException transientLock) {
                if (attempt == 2) throw transientLock;
            }
            try {
                Thread.sleep(25L << attempt);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void ensureShard(String hexPrefix, Path shard) throws IOException {
        int index = Integer.parseInt(hexPrefix, 16);
        if (createdShards.get(index) == 1 && Files.isDirectory(shard, LinkOption.NOFOLLOW_LINKS)) {
            safeWithin(root, shard);
            return;
        }
        ensureSubdirectory(root, shard);
        createdShards.set(index, 1);
        loosePresence = 2;
    }

    private boolean mayHaveLooseObjects() {
        int state = loosePresence;
        if (state != 0) return state == 2;
        try (var children = Files.newDirectoryStream(sha256Root)) {
            boolean any = children.iterator().hasNext();
            loosePresence = any ? 2 : 1;
            return any;
        } catch (IOException ignored) {
            return false;
        }
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public ContentHandle put(InputStream input, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(input);
        return writeInternal(output -> {
            byte[] buffer = borrowBuffer();
            try {
                int count;
                while ((count = input.read(buffer)) != -1) if (count > 0) output.write(buffer, 0, count);
            } finally {
                releaseBuffer(buffer);
            }
        }, cancelled);
    }

    /** Fast local-file capture: when the CAS already holds objects from an earlier scan, hash first to skip
     * writing multi-MB staging files on hits while preserving full SHA-256 verification and disk budgets. */
    ContentHandle putLocalFile(Path sourceFile, Set<ContentHandle> verifiedInRun, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(sourceFile);
        Objects.requireNonNull(cancelled);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        if (mayHaveLooseObjects()) {
            if (reserveBytes > 0 && space.usable() < reserveBytes) throw new IOException("WAITING_FOR_DISK");
            byte[] buffer = borrowBuffer();
            MessageDigest hash = borrowSha256();
            long bytes = 0;
            ContentHandle candidate;
            try (var input = Files.newInputStream(sourceFile, LinkOption.NOFOLLOW_LINKS)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                    hash.update(buffer, 0, count);
                    bytes = Math.addExact(bytes, count);
                }
                candidate = new ContentHandle(new ContentDigest("sha256:" + HexFormat.of().formatHex(hash.digest())), bytes);
            } catch (ArithmeticException overflow) {
                throw new IOException("CONTENT_SIZE_OVERFLOW");
            } finally {
                releaseSha256(hash);
                releaseBuffer(buffer);
            }
            Path existing = path(candidate);
            if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                if (verifiedInRun == null || !verifiedInRun.contains(candidate)) {
                    if (!verify(candidate, cancelled)) throw new IOException("CAS_CORRUPT");
                    if (verifiedInRun != null) verifiedInRun.add(candidate);
                }
                return candidate;
            }
        }
        try (var input = Files.newInputStream(sourceFile, LinkOption.NOFOLLOW_LINKS)) {
            ContentHandle stored = put(input, cancelled);
            if (verifiedInRun != null) verifiedInRun.add(stored);
            return stored;
        }
    }

    @Override public ContentHandle write(ContentWriter writer, BooleanSupplier cancelled) throws IOException {
        return writeInternal(writer, cancelled);
    }

    private ContentHandle writeInternal(ContentWriter writer, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(writer); Objects.requireNonNull(cancelled);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        ensureStaging();
        Path temp = Files.createTempFile(staging, "blob-", ".part");
        MessageDigest hash = borrowSha256();
        long[] size = {0};
        try {
            ContentHandle handle;
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var budget = new DiskBudget();
                var output = new OutputStream() {
                    @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}, 0, 1); }
                    @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                        Objects.checkFromIndexSize(offset, count, bytes.length);
                        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                        if (count == 0) return;
                        while (count > 0) {
                            int chunk = budget.claim(count);
                            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                            var buffer = ByteBuffer.wrap(bytes, offset, chunk);
                            while (buffer.hasRemaining()) channel.write(buffer);
                            size[0] = Math.addExact(size[0], chunk);
                            hash.update(bytes, offset, chunk);
                            offset += chunk;
                            count -= chunk;
                        }
                    }
                };
                var destination = new BufferedOutputStream(output, BUFFER_BYTES);
                writer.write(destination); destination.flush();
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                if (space.usable() < reserveBytes) throw new IOException("WAITING_FOR_DISK");
                handle = new ContentHandle(new ContentDigest("sha256:" + HexFormat.of().formatHex(hash.digest())), size[0]);
                if (hasLoose(handle) || packs.contains(handle)) {
                    if (!verify(handle, cancelled)) throw new IOException("CAS_CORRUPT");
                    return handle;
                }
                force(channel);
            }
            Path target = path(handle);
            ensureShard(handle.digest().value().substring(7, 9), target.getParent());
            try { createLinkWithRetry(target, temp); loosePresence = 2; }
            catch (FileAlreadyExistsException exists) { if (!verify(handle, cancelled)) throw new IOException("CAS_CORRUPT"); }
            catch (UnsupportedOperationException unavailable) { throw new IOException("ATOMIC_PUBLICATION_UNAVAILABLE"); }
            return handle;
        } catch (ArithmeticException overflow) { throw new IOException("CONTENT_SIZE_OVERFLOW"); }
        finally {
            releaseSha256(hash);
            deleteIfExistsQuietly(temp);
        }
    }

    /** Conservative allowance between fresh samples. Bounds probe frequency by bytes/time,
     * including a producer's single huge write; OS write failures still abort publication. */
    private final class DiskBudget {
        private static final int SAMPLE_BYTES = 8 * 1024 * 1024;
        private long allowance, sampledAt;
        private int remaining;
        int claim(int requested) throws IOException {
            long now = System.nanoTime();
            if (remaining == 0 || now - sampledAt >= 1_000_000_000L || allowance < Math.min(requested, remaining)) {
                long usable = space.usable();
                if (usable < reserveBytes) throw new IOException("WAITING_FOR_DISK");
                allowance = usable - reserveBytes; remaining = SAMPLE_BYTES; sampledAt = now;
            }
            int chunk = Math.min(requested, remaining);
            if (chunk > allowance) throw new IOException("WAITING_FOR_DISK");
            allowance -= chunk; remaining -= chunk; return chunk;
        }
    }

    private Path loosePath(ContentHandle handle) {
        String hex = handle.digest().value().substring(7);
        return sha256Root.resolve(hex.substring(0, 2)).resolve(hex.substring(2));
    }

    Path path(ContentHandle handle) throws IOException {
        Path path = loosePath(handle);
        safeWithin(root, path);
        return path;
    }

    boolean hasLoose(ContentHandle handle) { return Files.exists(loosePath(handle), LinkOption.NOFOLLOW_LINKS); }

    @Override public InputStream open(ContentHandle handle) throws IOException {
        if (hasLoose(handle)) {
            Path file = path(handle);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != handle.size()) throw new IOException("CAS_UNAVAILABLE");
            return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        }
        var packed = packs.open(handle);
        if (packed == null) throw new IOException("CAS_UNAVAILABLE");
        return packed;
    }

    /** One forced immutable pack precedes any inventory commit.
     * Bounds apply to one batch, never to repository admission. */
    void putSmallBatch(Collection<byte[]> values) throws IOException {
        packs.put(values, this);
    }

    void putSmallBatch(Map<ContentHandle, byte[]> entries, Set<ContentHandle> verifiedInRun) throws IOException {
        packs.putPrehashed(entries, verifiedInRun, this);
    }

    void force(FileChannel channel) throws IOException { force.flush(channel); }

    @Override public InputStream range(ContentHandle handle, long offset, long length) throws IOException {
        if (offset < 0 || length < 0 || offset > handle.size() || length > handle.size() - offset) throw new IllegalArgumentException("Invalid content range");
        var input = open(handle);
        try { input.skipNBytes(offset); }
        catch (IOException invalid) { input.close(); throw invalid; }
        return new FilterInputStream(input) {
            long remaining = length;
            @Override public int read() throws IOException { if (remaining == 0) return -1; int b = in.read(); if (b == -1) throw new EOFException(); remaining--; return b; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, b.length); if (len == 0) return 0; if (remaining == 0) return -1;
                int n = in.read(b, off, (int)Math.min(len, remaining)); if (n == -1) throw new EOFException(); remaining -= n; return n;
            }
        };
    }

    @Override public boolean verify(ContentHandle handle) throws IOException {
        return verify(handle, () -> false);
    }

    public boolean verify(ContentHandle handle, BooleanSupplier cancelled) throws IOException {
        byte[] buffer = borrowBuffer();
        MessageDigest digest = borrowSha256();
        try (var input = open(handle)) {
            int count; long bytes = 0;
            while ((count = input.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                digest.update(buffer, 0, count); bytes = Math.addExact(bytes, count);
            }
            return bytes == handle.size() && handle.digest().value().equals("sha256:" + HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchFileException missing) {
            return false;
        } finally {
            releaseSha256(digest);
            releaseBuffer(buffer);
        }
    }
}
