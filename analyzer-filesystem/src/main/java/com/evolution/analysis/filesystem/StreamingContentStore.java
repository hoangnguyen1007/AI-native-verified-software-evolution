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
import java.util.function.BooleanSupplier;

/** Disk CAS. A failed write never publishes a handle. Existing evidence is verified, never overwritten. */
public final class StreamingContentStore implements RepositoryContentStore {
    private final Path root, staging;
    private final long reserveBytes;
    public StreamingContentStore(Path root) throws IOException { this(root, 0); }
    public StreamingContentStore(Path root, long reserveBytes) throws IOException {
        if (reserveBytes < 0) throw new IllegalArgumentException("Negative disk reserve");
        this.root = directory(root); this.staging = directory(this.root.resolve("staging")); this.reserveBytes = reserveBytes;
        directory(this.root.resolve("sha256"));
    }
    static Path directory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        safe(absolute); Files.createDirectories(absolute);
        return existingDirectory(absolute);
    }
    /** Require existing storage without following linked ancestors; canonicalize
     * benign platform aliases such as Windows 8.3 names only after validation. */
    public static Path existingDirectory(Path path) throws IOException {
        Path absolute=path.toAbsolutePath().normalize();safe(absolute);
        if(!Files.isDirectory(absolute,LinkOption.NOFOLLOW_LINKS))throw new IOException("UNSAFE_STORAGE");
        return absolute.toRealPath();
    }
    static void safe(Path path) throws IOException {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            try {
                var attributes=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                // Windows junctions are reparse points (isOther), not symbolic links.
                if(attributes.isSymbolicLink()||attributes.isOther())throw new IOException("UNSAFE_PATH");
            }catch(NoSuchFileException missing){/* Prospective storage paths may not exist yet. */}
        }
    }
    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public ContentHandle put(InputStream input, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(input); Objects.requireNonNull(cancelled);
        Path temp = Files.createTempFile(staging, "blob-", ".part"); var hash = sha256(); long size = 0;
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                    if (count == 0) continue;
                    if (Files.getFileStore(staging).getUsableSpace() - count < reserveBytes) throw new IOException("WAITING_FOR_DISK");
                    size = Math.addExact(size, count); hash.update(buffer, 0, count);
                    var bytes = ByteBuffer.wrap(buffer, 0, count); while (bytes.hasRemaining()) channel.write(bytes);
                }
                if (cancelled.getAsBoolean()) throw new CancellationException();
                channel.force(true);
            }
            var handle = new ContentHandle(new ContentDigest("sha256:" + HexFormat.of().formatHex(hash.digest())), size);
            Path target = path(handle); directory(target.getParent());
            try { Files.createLink(target, temp); }
            catch (FileAlreadyExistsException exists) { if (!verify(handle)) throw new IOException("CAS_CORRUPT"); }
            catch (UnsupportedOperationException unavailable) { throw new IOException("ATOMIC_PUBLICATION_UNAVAILABLE"); }
            return handle;
        } catch (ArithmeticException overflow) { throw new IOException("CONTENT_SIZE_OVERFLOW"); }
        finally { Files.deleteIfExists(temp); }
    }
    private Path path(ContentHandle handle) throws IOException {
        String hex = handle.digest().value().substring(7);
        Path path = root.resolve("sha256").resolve(hex.substring(0, 2)).resolve(hex.substring(2)); safe(path); return path;
    }
    @Override public InputStream open(ContentHandle handle) throws IOException {
        Path file = path(handle);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != handle.size()) throw new IOException("CAS_UNAVAILABLE");
        return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
    }
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
        try (var input = open(handle)) {
            var digest = sha256(); byte[] buffer = new byte[64 * 1024]; int count; long bytes = 0;
            while ((count = input.read(buffer)) != -1) { digest.update(buffer, 0, count); bytes = Math.addExact(bytes, count); }
            return bytes == handle.size() && handle.digest().value().equals("sha256:" + HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchFileException missing) { return false; }
    }
}
