package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable small-object storage. The bounded address cache can be rebuilt from the pack headers. */
final class SmallBlobPacks {
    static final int MAX_BLOB = 65536, MAX_ROWS = 256;
    private static final int MAGIC = 0x45565031; // EVP1
    private static final long MAX_PACK = 8L + MAX_ROWS * (36L + MAX_BLOB);
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
    private boolean indexed;

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
        long size = 8L + rows.values().stream().mapToLong(b -> 36L + b.length).sum();
        if (Files.getFileStore(root).getUsableSpace() - size < reserve) throw new IOException("WAITING_FOR_DISK");
        StreamingContentStore.safeWithin(root, directory);
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("UNSAFE_STORAGE");
        Path pending = Files.createTempFile(root.resolve("staging"), "pack-", ".part");
        MessageDigest hash = StreamingContentStore.borrowSha256();
        try {
            try (var channel = FileChannel.open(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var output = new DataOutputStream(new BufferedOutputStream(new java.security.DigestOutputStream(Channels.newOutputStream(channel), hash), 65536));
                output.writeInt(MAGIC); output.writeInt(rows.size());
                for (var row : rows.entrySet()) {
                    output.write(HexFormat.of().parseHex(row.getKey().value().substring(7)));
                    output.writeInt(row.getValue().length); output.write(row.getValue());
                }
                output.flush(); store.force(channel);
            }
            Path target = directory.resolve(HexFormat.of().formatHex(hash.digest()) + ".pack");
            try { Files.createLink(target, pending); }
            catch (FileAlreadyExistsException existing) { validate(root, target); }
            // The forced pack is visible before a handle can reach the inventory journal.
            synchronized (this) {
                long offset = 8;
                for (var row : rows.entrySet()) {
                    var handle = new ContentHandle(row.getKey(), row.getValue().length);
                    addresses.put(handle, new Slice(target, offset + 36, size));
                    offset += 36 + handle.size();
                    if (verifiedInRun != null) verifiedInRun.add(handle);
                }
            }
        } finally {
            StreamingContentStore.releaseSha256(hash);
            Files.deleteIfExists(pending);
        }
    }

    synchronized boolean contains(ContentHandle handle) throws IOException {
        if (handle.size() > MAX_BLOB) return false;
        return indexed ? addresses.containsKey(handle) : index(handle, false) != null;
    }

    InputStream open(ContentHandle handle) throws IOException {
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
        if (!handle.digest().equals(ContentDigest.sha256(bytes))) throw new IOException("CAS_PACK_CORRUPT");
        return new ByteArrayInputStream(bytes);
    }

    /** A miss scans pack headers again so another store's publications and older
     * members evicted from the cache remain discoverable. */
    private Slice index(ContentHandle requested, boolean reading) throws IOException {
        StreamingContentStore.safeWithin(root, directory);
        indexed = true;
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        IOException malformed = null;
        Slice found = null;
        try (var paths = Files.newDirectoryStream(directory, "*.pack")) {
            for (Path pack : paths) {
                try {
                    var members = readAddresses(pack);
                    members.forEach(addresses::putIfAbsent);
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
        if (size > MAX_PACK) throw new IOException("CAS_PACK_CORRUPT");
        var addresses = new HashMap<ContentHandle, Slice>();
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(pack, LinkOption.NOFOLLOW_LINKS), 65536))) {
            if (input.readInt() != MAGIC) throw new IOException("CAS_PACK_CORRUPT");
            int count = input.readInt();
            if (count < 1 || count > MAX_ROWS) throw new IOException("CAS_PACK_CORRUPT");
            long offset = 8;
            for (int i = 0; i < count; i++) {
                byte[] digest = input.readNBytes(32);
                int length = input.readInt();
                if (digest.length != 32 || length < 0 || length > MAX_BLOB || offset + 36 + length > size)
                    throw new IOException("CAS_PACK_CORRUPT");
                var handle = new ContentHandle(new ContentDigest("sha256:" + HexFormat.of().formatHex(digest)), length);
                if (addresses.put(handle, new Slice(pack, offset + 36, size)) != null)
                    throw new IOException("CAS_PACK_CORRUPT");
                input.skipNBytes(length);
                offset += 36 + length;
            }
            if (offset != size || input.read() != -1) throw new IOException("CAS_PACK_CORRUPT");
        } catch (EOFException truncated) {
            throw new IOException("CAS_PACK_CORRUPT", truncated);
        }
        return addresses;
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
