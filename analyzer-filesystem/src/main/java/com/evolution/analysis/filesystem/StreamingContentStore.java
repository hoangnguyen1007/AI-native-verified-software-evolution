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
    @FunctionalInterface interface Force { void flush(FileChannel channel) throws IOException; }
    @FunctionalInterface interface Space { long usable() throws IOException; }
    private final Path root, staging;
    private final long reserveBytes;
    private final Force force;
    private final Space space;
    public StreamingContentStore(Path root) throws IOException { this(root, 0); }
    public StreamingContentStore(Path root, long reserveBytes) throws IOException {
        this(root,reserveBytes,channel->channel.force(true));
    }
    StreamingContentStore(Path root,long reserveBytes,Force force)throws IOException {
        this(root,reserveBytes,force,null);
    }
    StreamingContentStore(Path root,long reserveBytes,Force force,Space space)throws IOException {
        if (reserveBytes < 0) throw new IllegalArgumentException("Negative disk reserve");
        this.force=Objects.requireNonNull(force);
        this.root = directory(root); this.staging = directory(this.root.resolve("staging")); this.reserveBytes = reserveBytes;
        directory(this.root.resolve("sha256"));
        var store=space==null?Files.getFileStore(staging):null;
        this.space=space==null?store::getUsableSpace:space;
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
        Objects.requireNonNull(input);
        return writeInternal(output -> {
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) if (count > 0) output.write(buffer, 0, count);
        }, cancelled);
    }
    @Override public ContentHandle write(ContentWriter writer, BooleanSupplier cancelled) throws IOException {
        return writeInternal(writer, cancelled);
    }
    private ContentHandle writeInternal(ContentWriter writer, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(writer); Objects.requireNonNull(cancelled);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        Path temp = Files.createTempFile(staging, "blob-", ".part"); var hash = sha256(); long[] size = {0};
        try {
            ContentHandle handle;
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var budget=new DiskBudget();
                var output = new OutputStream() {
                    @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}, 0, 1); }
                    @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                        Objects.checkFromIndexSize(offset, count, bytes.length);
                        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                        if (count == 0) return;
                        while(count>0) {
                            int chunk=budget.claim(count);
                            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                            var buffer=ByteBuffer.wrap(bytes,offset,chunk);while(buffer.hasRemaining())channel.write(buffer);
                            size[0]=Math.addExact(size[0],chunk);hash.update(bytes,offset,chunk);
                            offset+=chunk;count-=chunk;
                        }
                    }
                };
                var destination = new BufferedOutputStream(output, 64 * 1024);
                writer.write(destination); destination.flush();
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                if(space.usable()<reserveBytes)throw new IOException("WAITING_FOR_DISK");
                handle=new ContentHandle(new ContentDigest("sha256:"+HexFormat.of().formatHex(hash.digest())),size[0]);
                if(Files.exists(path(handle),LinkOption.NOFOLLOW_LINKS)) {
                    if(!verify(handle))throw new IOException("CAS_CORRUPT");
                    return handle;
                }
                force(channel);
            }
            Path target = path(handle); directory(target.getParent());
            try { Files.createLink(target, temp); }
            catch (FileAlreadyExistsException exists) { if (!verify(handle)) throw new IOException("CAS_CORRUPT"); }
            catch (UnsupportedOperationException unavailable) { throw new IOException("ATOMIC_PUBLICATION_UNAVAILABLE"); }
            return handle;
        } catch (ArithmeticException overflow) { throw new IOException("CONTENT_SIZE_OVERFLOW"); }
        finally { Files.deleteIfExists(temp); }
    }
    /** Conservative allowance between fresh samples. Bounds probe frequency by bytes/time,
     * including a producer's single huge write; OS write failures still abort publication. */
    private final class DiskBudget {
        private static final int SAMPLE_BYTES=8*1024*1024;
        private long allowance,sampledAt;
        private int remaining;
        int claim(int requested)throws IOException {
            long now=System.nanoTime();
            if(remaining==0||now-sampledAt>=1_000_000_000L||allowance<Math.min(requested,remaining)) {
                long usable=space.usable();
                if(usable<reserveBytes)throw new IOException("WAITING_FOR_DISK");
                allowance=usable-reserveBytes;remaining=SAMPLE_BYTES;sampledAt=now;
            }
            int chunk=Math.min(requested,remaining);
            if(chunk>allowance)throw new IOException("WAITING_FOR_DISK");
            allowance-=chunk;remaining-=chunk;return chunk;
        }
    }
    Path path(ContentHandle handle) throws IOException {
        String hex = handle.digest().value().substring(7);
        Path path = root.resolve("sha256").resolve(hex.substring(0, 2)).resolve(hex.substring(2)); safe(path); return path;
    }
    @Override public InputStream open(ContentHandle handle) throws IOException {
        Path file = path(handle);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) new SmallBlobPacks(root, reserveBytes).recover(handle, this);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != handle.size()) throw new IOException("CAS_UNAVAILABLE");
        return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
    }
    /** One forced immutable pack precedes all derived loose files and any inventory commit.
     * Bounds apply to one batch, never to repository admission. */
    void putSmallBatch(Collection<byte[]> values) throws IOException {
        new SmallBlobPacks(root, reserveBytes).put(values, this);
    }
    void force(FileChannel channel)throws IOException { force.flush(channel); }
    void cacheSmall(ContentHandle handle, byte[] bytes) throws IOException {
        Path target=path(handle); directory(target.getParent());
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            if(!verify(handle))throw new IOException("CAS_CORRUPT");
            return;
        }
        Path pending=Files.createTempFile(staging,"packed-",".part");
        try {
            Files.write(pending,bytes,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try { Files.createLink(target,pending); }
            catch(FileAlreadyExistsException race) { if(!verify(handle))throw new IOException("CAS_CORRUPT"); }
        } finally { Files.deleteIfExists(pending); }
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
