package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable small-object storage. The bounded address cache can be rebuilt from the pack footer/headers. */
final class SmallBlobPacks {
    static final int MAX_BLOB = 65536, MAX_ROWS = 256;
    private static final int MAGIC_V1 = 0x45565031; // EVP1
    private static final int MAGIC_V2 = 0x45565032; // EVP2
    private static final int FOOTER_MAGIC = 0x53425032; // SBP2
    private static final long MAX_PACK_V1 = 8L + MAX_ROWS * (36L + MAX_BLOB);
    private static final long MAX_PACK = MAX_PACK_V1 + 8L + MAX_ROWS * 44L + 12L;
    private static final int CACHED_ADDRESSES = 131_072;
    private record Slice(Path pack, long offset, long packSize) {}
    private final Path root;
    private final Path directory;
    private final long reserve;
    private final Map<ContentHandle, Slice> addresses = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<ContentHandle, Slice> eldest) {
            return size() > CACHED_ADDRESSES;
        }
    };
    private final Set<Path> scannedPacks = new HashSet<>();
    private boolean indexed;
    private boolean evicted;

    SmallBlobPacks(Path root, long reserve) {
        this.root = root;
        this.directory = root.resolve("packs");
        this.reserve = reserve;
    }

    void put(Collection<byte[]> values, StreamingContentStore store) throws IOException {
        if (values.size() > MAX_ROWS) throw new IllegalArgumentException("Small CAS batch too large");
        var prehashed = new LinkedHashMap<ContentHandle, byte[]>(values.size() * 2);
        for (byte[] bytes : values) {
            if (bytes.length > MAX_BLOB) throw new IllegalArgumentException("Small CAS object too large");
            MessageDigest md = StreamingContentStore.borrowSha256();
            ContentHandle handle;
            try {
                handle = new ContentHandle(new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest(bytes))), bytes.length);
            } finally {
                StreamingContentStore.releaseSha256(md);
            }
            prehashed.putIfAbsent(handle, bytes);
        }
        putPrehashed(prehashed, null, store);
    }

    void putPrehashed(Map<ContentHandle, byte[]> entries, Set<ContentHandle> verifiedInRun, StreamingContentStore store) throws IOException {
        if (entries.size() > MAX_ROWS) throw new IllegalArgumentException("Small CAS batch too large");
        var rows = new TreeMap<ContentDigest, byte[]>();
        for (var item : entries.entrySet()) {
            var handle = item.getKey();
            byte[] bytes = item.getValue();
            if (bytes.length > MAX_BLOB) throw new IllegalArgumentException("Small CAS object too large");
            if (verifiedInRun != null && verifiedInRun.contains(handle)) continue;
            if (store.hasLoose(handle) || contains(handle)) {
                if (!store.verify(handle)) throw new IOException("CAS_CORRUPT");
                if (verifiedInRun != null) verifiedInRun.add(handle);
            } else {
                rows.put(handle.digest(), bytes);
            }
        }
        if (rows.isEmpty()) return;
        long payloadBytes = 8L + rows.values().stream().mapToLong(b -> 36L + b.length).sum();
        long footerBytes = 8L + rows.size() * 44L + 12L;
        long size = payloadBytes + footerBytes;
        if (Files.getFileStore(root).getUsableSpace() - size < reserve) throw new IOException("WAITING_FOR_DISK");
        StreamingContentStore.ensureSubdirectory(root, directory);
        store.ensureStaging();
        Path pending = Files.createTempFile(root.resolve("staging"), "pack-", ".part");
        MessageDigest hash = StreamingContentStore.borrowSha256();
        try {
            long[] payloadOffsets = new long[rows.size()];
            byte[][] rawDigests = new byte[rows.size()][];
            int[] lengths = new int[rows.size()];
            try (var channel = FileChannel.open(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var output = new DataOutputStream(new BufferedOutputStream(new java.security.DigestOutputStream(Channels.newOutputStream(channel), hash), 65536));
                output.writeInt(MAGIC_V2);
                output.writeInt(rows.size());
                long offset = 8L;
                int idx = 0;
                for (var row : rows.entrySet()) {
                    byte[] rawDigest = HexFormat.of().parseHex(row.getKey().value().substring(7));
                    byte[] value = row.getValue();
                    rawDigests[idx] = rawDigest;
                    payloadOffsets[idx] = offset + 36L;
                    lengths[idx] = value.length;
                    output.write(rawDigest);
                    output.writeInt(value.length);
                    output.write(value);
                    offset += 36L + value.length;
                    idx++;
                }
                long footerOffset = offset;
                output.writeInt(FOOTER_MAGIC);
                output.writeInt(rows.size());
                for (int i = 0; i < rows.size(); i++) {
                    output.write(rawDigests[i]);
                    output.writeLong(payloadOffsets[i]);
                    output.writeInt(lengths[i]);
                }
                output.writeLong(footerOffset);
                output.writeInt(FOOTER_MAGIC);
                output.flush();
                store.force(channel);
            }
            Path target = directory.resolve(HexFormat.of().formatHex(hash.digest()) + ".pack");
            try { StreamingContentStore.createLinkWithRetry(target, pending); }
            catch (FileAlreadyExistsException existing) { validate(root, target); }
            // The forced pack is visible before a handle can reach the inventory journal.
            synchronized (this) {
                scannedPacks.add(target);
                int idx = 0;
                for (var row : rows.entrySet()) {
                    var handle = new ContentHandle(row.getKey(), lengths[idx]);
                    if (addresses.size() >= CACHED_ADDRESSES) evicted = true;
                    addresses.put(handle, new Slice(target, payloadOffsets[idx], size));
                    if (verifiedInRun != null) verifiedInRun.add(handle);
                    idx++;
                }
            }
        } finally {
            StreamingContentStore.releaseSha256(hash);
            StreamingContentStore.deleteIfExistsQuietly(pending);
        }
    }

    synchronized boolean contains(ContentHandle handle) throws IOException {
        if (handle.size() > MAX_BLOB) return false;
        if (addresses.containsKey(handle)) return true;
        return index(handle, false) != null;
    }

    byte[] readVerified(ContentHandle handle) throws IOException {
        if (handle.size() > MAX_BLOB) return null;
        Slice slice;
        synchronized (this) {
            slice = addresses.get(handle);
            if (slice == null) slice = index(handle, true);
        }
        if (slice == null) return null;

        // An address is only a lookup hint. Authenticate the exact member on every read.
        StreamingContentStore.safeWithin(root, slice.pack());
        byte[] bytes = new byte[Math.toIntExact(handle.size())];
        try (var file = FileChannel.open(slice.pack(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (file.size() != slice.packSize()) throw new IOException("CAS_PACK_CORRUPT");
            var destination = ByteBuffer.wrap(bytes);
            while (destination.hasRemaining()) {
                if (file.read(destination, slice.offset() + destination.position()) == -1)
                    throw new IOException("CAS_PACK_CORRUPT");
            }
        }
        MessageDigest md = StreamingContentStore.borrowSha256();
        try {
            if (!handle.digest().value().equals("sha256:" + HexFormat.of().formatHex(md.digest(bytes))))
                throw new IOException("CAS_PACK_CORRUPT");
        } finally {
            StreamingContentStore.releaseSha256(md);
        }
        return bytes;
    }

    InputStream open(ContentHandle handle) throws IOException {
        byte[] bytes = readVerified(handle);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    /** A miss scans newly discovered pack footers/headers (or all packs if eviction occurred) so
     * another store's publications and evicted members remain discoverable in O(1) footer reads. */
    private Slice index(ContentHandle requested, boolean reading) throws IOException {
        StreamingContentStore.safeWithin(root, directory);
        indexed = true;
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        IOException malformed = null;
        Slice found = null;
        boolean rescanAll = evicted;
        try (var paths = Files.newDirectoryStream(directory, "*.pack")) {
            for (Path pack : paths) {
                if (!rescanAll && scannedPacks.contains(pack)) continue;
                try {
                    var members = readAddresses(pack);
                    scannedPacks.add(pack);
                    for (var entry : members.entrySet()) {
                        if (addresses.size() >= CACHED_ADDRESSES) evicted = true;
                        addresses.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                    if (found == null) found = members.get(requested);
                } catch (IOException unavailable) {
                    if (malformed == null) malformed = unavailable;
                }
            }
        }
        if (found != null) addresses.put(requested, found);
        else if (reading && malformed != null) throw malformed;
        return found;
    }

    private Map<ContentHandle, Slice> readAddresses(Path pack) throws IOException {
        StreamingContentStore.safeWithin(root, pack);
        if (!Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS)) throw new IOException("CAS_PACK_CORRUPT");
        long size = Files.size(pack);
        if (size < 8 || size > MAX_PACK) throw new IOException("CAS_PACK_CORRUPT");
        // Fast path: v2 compact footer directory (reads only the trailing footer block, zero payload bytes read).
        if (size >= 28) {
            try (var channel = FileChannel.open(pack, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                var trailer = ByteBuffer.allocate(12);
                readFully(channel, trailer, size - 12L);
                trailer.flip();
                long footerOffset = trailer.getLong();
                int trailerMagic = trailer.getInt();
                if (trailerMagic == FOOTER_MAGIC) {
                    long footerLength = (size - 12L) - footerOffset;
                    if (footerOffset < 8L || footerLength < 8L || footerLength > 8L + MAX_ROWS * 44L)
                        throw new IOException("CAS_PACK_CORRUPT");
                    var footer = ByteBuffer.allocate((int) footerLength);
                    readFully(channel, footer, footerOffset);
                    footer.flip();
                    if (footer.getInt() != FOOTER_MAGIC) throw new IOException("CAS_PACK_CORRUPT");
                    int count = footer.getInt();
                    if (count < 1 || count > MAX_ROWS || footerLength != 8L + count * 44L)
                        throw new IOException("CAS_PACK_CORRUPT");
                    var map = new HashMap<ContentHandle, Slice>(count * 2);
                    byte[] digest = new byte[32];
                    for (int i = 0; i < count; i++) {
                        footer.get(digest);
                        long payloadOffset = footer.getLong();
                        int length = footer.getInt();
                        if (length < 0 || length > MAX_BLOB || payloadOffset < 44L || payloadOffset + length > footerOffset)
                            throw new IOException("CAS_PACK_CORRUPT");
                        var handle = new ContentHandle(new ContentDigest("sha256:" + HexFormat.of().formatHex(digest)), length);
                        if (map.put(handle, new Slice(pack, payloadOffset, size)) != null)
                            throw new IOException("CAS_PACK_CORRUPT");
                    }
                    return map;
                }
            } catch (EOFException truncated) {
                throw new IOException("CAS_PACK_CORRUPT", truncated);
            }
        }
        // Backward-compatible v1 pack linear header scan.
        if (size > MAX_PACK_V1) throw new IOException("CAS_PACK_CORRUPT");
        var map = new HashMap<ContentHandle, Slice>();
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(pack, LinkOption.NOFOLLOW_LINKS), 65536))) {
            if (input.readInt() != MAGIC_V1) throw new IOException("CAS_PACK_CORRUPT");
            int count = input.readInt();
            if (count < 1 || count > MAX_ROWS) throw new IOException("CAS_PACK_CORRUPT");
            long offset = 8;
            for (int i = 0; i < count; i++) {
                byte[] digest = input.readNBytes(32);
                int length = input.readInt();
                if (digest.length != 32 || length < 0 || length > MAX_BLOB || offset + 36 + length > size)
                    throw new IOException("CAS_PACK_CORRUPT");
                var handle = new ContentHandle(new ContentDigest("sha256:" + HexFormat.of().formatHex(digest)), length);
                if (map.put(handle, new Slice(pack, offset + 36, size)) != null)
                    throw new IOException("CAS_PACK_CORRUPT");
                input.skipNBytes(length);
                offset += 36 + length;
            }
            if (offset != size || input.read() != -1) throw new IOException("CAS_PACK_CORRUPT");
        } catch (EOFException truncated) {
            throw new IOException("CAS_PACK_CORRUPT", truncated);
        }
        return map;
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, pos);
            if (n < 0) throw new EOFException("CAS_PACK_CORRUPT");
            pos += n;
        }
    }

    private static void validate(Path root, Path pack) throws IOException {
        StreamingContentStore.safeWithin(root, pack);
        String name = pack.getFileName().toString();
        if (!name.matches("[a-f0-9]{64}\\.pack") || !Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS) || Files.size(pack) > MAX_PACK) throw new IOException("CAS_PACK_CORRUPT");
        MessageDigest hash = StreamingContentStore.borrowSha256();
        byte[] buffer = StreamingContentStore.borrowBuffer();
        try (var input = Files.newInputStream(pack, LinkOption.NOFOLLOW_LINKS)) {
            int count; while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
            if (!name.equals(HexFormat.of().formatHex(hash.digest()) + ".pack")) throw new IOException("CAS_PACK_CORRUPT");
        } finally {
            StreamingContentStore.releaseBuffer(buffer);
            StreamingContentStore.releaseSha256(hash);
        }
    }
}
