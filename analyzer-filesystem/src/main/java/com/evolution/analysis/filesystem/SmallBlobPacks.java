package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable recovery authority for batched small CAS objects. Loose objects are a read cache.
 * No database/index is required to recover a missing cache entry. Legacy loose blobs stay valid. */
final class SmallBlobPacks {
    static final int MAX_BLOB = 65536, MAX_ROWS = 256;
    private static final int MAGIC = 0x45565031; // EVP1
    private static final long MAX_PACK = 8L + MAX_ROWS * (36L + MAX_BLOB);
    private final Path root;
    private final long reserve;
    SmallBlobPacks(Path root, long reserve) { this.root = root; this.reserve = reserve; }

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
            Path loose = store.path(handle);
            if (Files.exists(loose, LinkOption.NOFOLLOW_LINKS)) {
                if (verifiedInRun == null || !verifiedInRun.contains(handle)) {
                    if (!store.verify(handle)) throw new IOException("CAS_CORRUPT");
                    if (verifiedInRun != null) verifiedInRun.add(handle);
                }
            } else {
                rows.put(handle.digest(), bytes);
            }
        }
        if (rows.isEmpty()) return;
        long size = 8L + rows.values().stream().mapToLong(b -> 36L + b.length).sum();
        // Include pack and derived cache bytes in the reservation.
        if (Files.getFileStore(root).getUsableSpace() - size * 2 < reserve) throw new IOException("WAITING_FOR_DISK");
        Path packs = root.resolve("packs");
        StreamingContentStore.safeWithin(root, packs);
        Files.createDirectories(packs);
        if (!Files.isDirectory(packs, LinkOption.NOFOLLOW_LINKS)) throw new IOException("UNSAFE_STORAGE");
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
            Path target = packs.resolve(HexFormat.of().formatHex(hash.digest()) + ".pack");
            try { Files.createLink(target, pending); }
            catch (FileAlreadyExistsException existing) { validate(root, target); }
            // The pack is durable before a loose object is made visible or a handle is acknowledged.
            for (var row : rows.entrySet()) {
                store.cacheSmall(new ContentHandle(row.getKey(), row.getValue().length), row.getValue(), verifiedInRun);
            }
        } finally {
            StreamingContentStore.releaseSha256(hash);
            Files.deleteIfExists(pending);
        }
    }

    void recover(ContentHandle requested, StreamingContentStore store) throws IOException {
        if (requested.size() > MAX_BLOB) return;
        Path packs = root.resolve("packs"); StreamingContentStore.safeWithin(root, packs);
        if (!Files.exists(packs, LinkOption.NOFOLLOW_LINKS)) return;
        byte[] requestedDigestBytes = HexFormat.of().parseHex(requested.digest().value().substring(7));
        try (var paths = Files.newDirectoryStream(packs, "*.pack")) {
            for (Path pack : paths) {
                StreamingContentStore.safeWithin(root, pack);
                // Scan bounded headers first; do not hash every healthy pack on every miss.
                boolean found = false;
                try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(pack, LinkOption.NOFOLLOW_LINKS), 65536))) {
                    int count = header(input, pack);
                    for (int i = 0; i < count; i++) {
                        byte[] digest = input.readNBytes(32); int length = input.readInt(); checkRow(digest, length);
                        if (requested.size() == length && Arrays.equals(requestedDigestBytes, digest)) found = true;
                        input.skipNBytes(length);
                    }
                    if (input.read() != -1) throw new IOException("CAS_PACK_CORRUPT");
                }
                if (!found) continue;
                validate(root, pack);
                // Rebuild the whole bounded pack in one pass, avoiding one population scan per lost row.
                try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(pack, LinkOption.NOFOLLOW_LINKS), 65536))) {
                    int count = header(input, pack);
                    for (int i = 0; i < count; i++) {
                        byte[] digest = input.readNBytes(32); int length = input.readInt(); checkRow(digest, length);
                        byte[] bytes = input.readNBytes(length);
                        var handle = new ContentHandle(new ContentDigest("sha256:" + HexFormat.of().formatHex(digest)), length);
                        if (bytes.length != length || !ContentDigest.sha256(bytes).equals(handle.digest())) throw new IOException("CAS_PACK_CORRUPT");
                        store.cacheSmall(handle, bytes);
                    }
                }
                return;
            }
        }
    }

    private static int header(DataInputStream input, Path pack) throws IOException {
        if (!Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS) || Files.size(pack) > MAX_PACK || input.readInt() != MAGIC) throw new IOException("CAS_PACK_CORRUPT");
        int rows = input.readInt(); if (rows < 1 || rows > MAX_ROWS) throw new IOException("CAS_PACK_CORRUPT"); return rows;
    }

    private static void checkRow(byte[] digest, int length) throws IOException {
        if (digest.length != 32 || length < 0 || length > MAX_BLOB) throw new IOException("CAS_PACK_CORRUPT");
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
