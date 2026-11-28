package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.polyglot.SourceCoordinates;
import com.evolution.analysis.treesitter.TreeSitterSourceFrontend;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Sharded per-file SHA-256-authenticated cache (v3) for polyglot syntax parsing,
 * {@code JavaGrpcSourceFrontend} candidate checks/parses, and OpenAPI JSON probes.
 * <p>
 * Layout: {@code <cacheRoot>/polyglot-v3/<2-hex>/<64-hex>.pg3}. Each entry is written once
 * atomically with a trailing 32-byte SHA-256 checksum, avoiding full-cache rewrites when only
 * a handful of files change. Bounded in-memory LRU prevents heap growth on large monorepos.
 * Falls back cleanly to legacy {@code polyglot-syntax.bin.gz} if present.
 */
final class IncrementalPolyglotCache {
    private static final byte[] MAGIC_V2 = "EVOPGC2\n".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION_V2 = 2;
    private static final byte[] MAGIC_V3 = "EVOPG3\n".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION_V3 = 3;
    private static final byte KIND_PARSED = 1;
    private static final byte KIND_SENTINEL = 2;
    private static final int MIN_COMPRESS_BYTES = 512;
    private static final int MAX_MEM_ENTRIES = Integer.getInteger("evo.incremental.polyglotMemEntries", 16_384);
    private static final int MAX_STR_BYTES = 16 * 1024 * 1024;

    enum ProbeStatus {
        NOT_CANDIDATE,
        NOT_OPENAPI,
        UNRENDERED_HELM_TEMPLATE
    }

    record Entry(TreeSitterSourceFrontend.Summary summary, List<TreeSitterSourceFrontend.Fact> facts) {}

    record Lookup(Entry entry, ProbeStatus sentinel) {
        boolean isSentinel() { return sentinel != null; }
        boolean isEntry() { return entry != null; }
    }

    private record Slot(Entry entry, ProbeStatus sentinel) {
        static Slot parsed(Entry entry) { return new Slot(Objects.requireNonNull(entry), null); }
        static Slot sentinel(ProbeStatus status) { return new Slot(null, Objects.requireNonNull(status)); }
    }

    private final Path shardDir;
    private final Path legacyFile;
    private final Map<ContentDigest, Slot> memCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<ContentDigest, Slot> eldest) {
            return size() > MAX_MEM_ENTRIES;
        }
    };
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    private IncrementalPolyglotCache(Path shardDir, Path legacyFile) {
        this.shardDir = shardDir;
        this.legacyFile = legacyFile;
    }

    static IncrementalPolyglotCache open(Path cacheRoot) {
        Path norm = Objects.requireNonNull(cacheRoot).toAbsolutePath().normalize();
        var cache = new IncrementalPolyglotCache(norm.resolve("polyglot-v3"), norm.resolve("polyglot-syntax.bin.gz"));
        if (!Boolean.getBoolean("evo.incremental.disabled")) {
            cache.loadLegacyIfNeeded();
        }
        return cache;
    }

    static ContentDigest key(String provider, String toolIdentity, String language, ContentDigest contentDigest,
            long size, boolean fastArchitecture) {
        return ContentDigest.sha256Utf8(String.join("|",
                "polyglot-file-cache:v3",
                provider,
                toolIdentity == null ? "" : toolIdentity,
                language,
                contentDigest.value(),
                Long.toString(size),
                Boolean.toString(fastArchitecture)));
    }

    /** Legacy v2 key kept so existing polyglot-syntax.bin.gz entries remain readable without re-parsing. */
    static ContentDigest legacyV2Key(String provider, String toolIdentity, String language, ContentDigest contentDigest,
            long size, boolean fastArchitecture) {
        return ContentDigest.sha256Utf8(String.join("|",
                "polyglot-file-cache:v2",
                provider,
                toolIdentity == null ? "" : toolIdentity,
                language,
                contentDigest.value(),
                Long.toString(size),
                Boolean.toString(fastArchitecture)));
    }

    static ContentDigest probeKey(String probeFamily, String providerVersion, ContentDigest contentDigest, long size) {
        return ContentDigest.sha256Utf8(String.join("|",
                "polyglot-probe:v3",
                probeFamily,
                providerVersion == null ? "" : providerVersion,
                contentDigest.value(),
                Long.toString(size)));
    }

    Optional<Entry> get(ContentDigest key) {
        return get(key, null);
    }

    Optional<Entry> get(ContentDigest key, ContentDigest fallbackLegacyKey) {
        if (Boolean.getBoolean("evo.incremental.disabled")) {
            missCount.incrementAndGet();
            return Optional.empty();
        }
        Slot slot = getSlot(key);
        if (slot == null && fallbackLegacyKey != null) {
            synchronized (memCache) {
                slot = memCache.get(fallbackLegacyKey);
            }
        }
        if (slot != null && slot.entry() != null) {
            hitCount.incrementAndGet();
            return Optional.of(slot.entry());
        }
        missCount.incrementAndGet();
        return Optional.empty();
    }

    Optional<ProbeStatus> getProbe(ContentDigest probeKey) {
        if (Boolean.getBoolean("evo.incremental.disabled")) {
            missCount.incrementAndGet();
            return Optional.empty();
        }
        Slot slot = getSlot(probeKey);
        if (slot != null && slot.sentinel() != null) {
            hitCount.incrementAndGet();
            return Optional.of(slot.sentinel());
        }
        missCount.incrementAndGet();
        return Optional.empty();
    }

    /**
     * Single-slot lookup returning either a cached parsed {@link Entry} or a cached negative
     * {@link ProbeStatus} sentinel under one key, recording exactly one hit or one miss per task.
     */
    Optional<Lookup> lookup(ContentDigest key) {
        return lookup(key, null);
    }

    Optional<Lookup> lookup(ContentDigest key, ContentDigest fallbackLegacyKey) {
        if (Boolean.getBoolean("evo.incremental.disabled")) {
            missCount.incrementAndGet();
            return Optional.empty();
        }
        Slot slot = getSlot(key);
        if (slot == null && fallbackLegacyKey != null) {
            synchronized (memCache) {
                slot = memCache.get(fallbackLegacyKey);
            }
        }
        if (slot != null) {
            hitCount.incrementAndGet();
            return Optional.of(new Lookup(slot.entry(), slot.sentinel()));
        }
        missCount.incrementAndGet();
        return Optional.empty();
    }

    long hits() {
        return hitCount.get();
    }

    long misses() {
        return missCount.get();
    }

    void put(ContentDigest key, Entry entry) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return;
        Slot slot = Slot.parsed(entry);
        synchronized (memCache) {
            memCache.put(key, slot);
        }
        writeShardQuietly(key, slot);
    }

    void putProbe(ContentDigest probeKey, ProbeStatus status) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return;
        Slot slot = Slot.sentinel(status);
        synchronized (memCache) {
            memCache.put(probeKey, slot);
        }
        writeShardQuietly(probeKey, slot);
    }

    /** Sharded v3 writes entries durably on put; flushQuietly is retained as a no-op compatibility hook. */
    void flushQuietly() {
        // Each shard is atomically persisted on write; no monolithic rewrite needed.
    }

    Path shardPath(ContentDigest key) {
        String hex = key.value().substring(7);
        return shardDir.resolve(hex.substring(0, 2)).resolve(hex + ".pg3");
    }

    private Slot getSlot(ContentDigest key) {
        synchronized (memCache) {
            Slot mem = memCache.get(key);
            if (mem != null) return mem;
        }
        Slot disk = readShardQuietly(key);
        if (disk != null) {
            synchronized (memCache) {
                memCache.put(key, disk);
            }
        }
        return disk;
    }

    private void writeShardQuietly(ContentDigest key, Slot slot) {
        Path target = shardPath(key);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return;
        Path pending = null;
        try {
            Files.createDirectories(target.getParent());
            var rawOut = new ByteArrayOutputStream(slot.sentinel() != null ? 128 : 2048);
            try (var out = new DataOutputStream(rawOut)) {
                out.write(MAGIC_V3);
                out.writeInt(FORMAT_VERSION_V3);
                writeStr(out, key.value());
                if (slot.sentinel() != null) {
                    out.writeByte(KIND_SENTINEL);
                    out.writeByte(slot.sentinel().ordinal());
                } else {
                    out.writeByte(KIND_PARSED);
                    writeEntry(out, slot.entry());
                }
                out.flush();
            }
            byte[] raw = rawOut.toByteArray();
            boolean compressed = raw.length >= MIN_COMPRESS_BYTES;
            byte[] payload;
            if (compressed) {
                var gzBuf = new ByteArrayOutputStream(raw.length / 2);
                try (var gz = new FastGzipOutputStream(gzBuf)) {
                    gz.write(raw);
                }
                payload = gzBuf.toByteArray();
            } else {
                payload = raw;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((byte) (compressed ? 1 : 0));
            md.update(payload);
            byte[] checksum = md.digest();

            pending = Files.createTempFile(target.getParent(), "pg3-", ".tmp");
            try (var out = new BufferedOutputStream(
                    Files.newOutputStream(pending, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                    8192)) {
                out.write(compressed ? 1 : 0);
                out.write(payload);
                out.write(checksum);
            }
            moveWithRetry(pending, target);
        } catch (IOException | RuntimeException | NoSuchAlgorithmException ignored) {
            /* Best-effort persistence. */
        } finally {
            if (pending != null) {
                try { Files.deleteIfExists(pending); } catch (IOException ignored) {}
            }
        }
    }

    private Slot readShardQuietly(ContentDigest key) {
        Path file = shardPath(key);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            byte[] all = Files.readAllBytes(file);
            if (all.length < 34) {
                Files.deleteIfExists(file);
                return null;
            }
            int bodyLen = all.length - 32;
            byte[] expected = new byte[32];
            System.arraycopy(all, bodyLen, expected, 0, 32);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(all, 0, bodyLen);
            if (!MessageDigest.isEqual(expected, md.digest())) {
                Files.deleteIfExists(file);
                return null;
            }
            boolean compressed = all[0] == 1;
            InputStream bodyStream = new ByteArrayInputStream(all, 1, bodyLen - 1);
            if (compressed) bodyStream = new GZIPInputStream(bodyStream);
            try (var in = new DataInputStream(new BufferedInputStream(bodyStream, 8192))) {
                byte[] magic = new byte[MAGIC_V3.length];
                in.readFully(magic);
                if (!Arrays.equals(MAGIC_V3, magic)
                        || in.readInt() != FORMAT_VERSION_V3
                        || !key.value().equals(readStr(in))) {
                    Files.deleteIfExists(file);
                    return null;
                }
                int kind = in.readUnsignedByte();
                if (kind == KIND_SENTINEL) {
                    int ord = in.readUnsignedByte();
                    var values = ProbeStatus.values();
                    if (ord >= values.length) throw new IOException("Invalid probe sentinel");
                    return Slot.sentinel(values[ord]);
                }
                if (kind == KIND_PARSED) {
                    return Slot.parsed(readEntry(in));
                }
                Files.deleteIfExists(file);
                return null;
            }
        } catch (IOException | RuntimeException | NoSuchAlgorithmException corrupt) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            return null;
        }
    }

    private static void writeEntry(DataOutputStream out, Entry entry) throws IOException {
        var sum = entry.summary();
        out.writeLong(sum.nodes());
        out.writeLong(sum.facts());
        out.writeLong(sum.errors());
        writeStr(out, sum.grammar());
        writeStr(out, sum.toolIdentity());
        var facts = entry.facts();
        out.writeInt(facts.size());
        for (var f : facts) {
            writeStr(out, f.category());
            writeStr(out, f.nodeKind());
            writeStr(out, f.name());
            writeStr(out, f.written());
            out.writeInt(f.span().start());
            out.writeInt(f.span().end());
            out.writeInt(f.span().startLine());
            out.writeInt(f.span().startColumn());
            out.writeInt(f.span().endLine());
            out.writeInt(f.span().endColumn());
            writeStr(out, f.owner());
            out.writeBoolean(f.recovered());
            out.writeInt(f.attributes().size());
            for (var attr : f.attributes().entrySet()) {
                writeStr(out, attr.getKey());
                writeStr(out, attr.getValue());
            }
        }
    }

    private static Entry readEntry(DataInputStream in) throws IOException {
        var summary = new TreeSitterSourceFrontend.Summary(
                in.readLong(), in.readLong(), in.readLong(), readStr(in), readStr(in));
        int factCount = in.readInt();
        if (factCount < 0 || factCount > 1_000_000) throw new IOException("Invalid fact count");
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>(factCount);
        for (int f = 0; f < factCount; f++) {
            String category = readStr(in);
            String nodeKind = readStr(in);
            String name = readStr(in);
            String written = readStr(in);
            var span = new SourceCoordinates.Range(
                    in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
            String owner = readStr(in);
            boolean recovered = in.readBoolean();
            int attrCount = in.readInt();
            if (attrCount < 0 || attrCount > 100_000) throw new IOException("Invalid attribute count");
            var attrs = new TreeMap<String, String>();
            for (int a = 0; a < attrCount; a++) {
                attrs.put(readStr(in), readStr(in));
            }
            facts.add(new TreeSitterSourceFrontend.Fact(
                    category, nodeKind, name, written, span, owner, recovered, attrs));
        }
        return new Entry(summary, List.copyOf(facts));
    }

    private void loadLegacyIfNeeded() {
        if (Files.isDirectory(shardDir, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(legacyFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            byte[] all = Files.readAllBytes(legacyFile);
            if (all.length < 32) return;
            int bodyLen = all.length - 32;
            byte[] expected = new byte[32];
            System.arraycopy(all, bodyLen, expected, 0, 32);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(all, 0, bodyLen);
            if (!MessageDigest.isEqual(expected, md.digest())) return;
            try (var in = new DataInputStream(new BufferedInputStream(
                    new GZIPInputStream(new ByteArrayInputStream(all, 0, bodyLen)), 32 * 1024))) {
                byte[] magic = new byte[MAGIC_V2.length];
                in.readFully(magic);
                if (!Arrays.equals(MAGIC_V2, magic) || in.readInt() != FORMAT_VERSION_V2) return;
                int count = in.readInt();
                if (count < 0 || count > 200_000) return;
                int loaded = Math.min(count, MAX_MEM_ENTRIES);
                synchronized (memCache) {
                    for (int i = 0; i < loaded; i++) {
                        ContentDigest key = new ContentDigest(readStr(in));
                        memCache.put(key, Slot.parsed(readEntry(in)));
                    }
                }
            }
        } catch (IOException | RuntimeException | NoSuchAlgorithmException ignored) {
            /* Legacy fallback is strictly best-effort. */
        }
    }

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        FastGzipOutputStream(OutputStream out) throws IOException {
            super(out, 4096);
            this.def.setLevel(Deflater.BEST_SPEED);
        }
    }

    private static void moveWithRetry(Path src, Path dst) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                try {
                    Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException retryable) {
                if (attempt == 2) throw retryable;
                try { Thread.sleep(15L); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw retryable;
                }
            }
        }
    }

    private static void writeStr(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STR_BYTES) throw new IOException("String exceeds limit");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readStr(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_STR_BYTES) throw new IOException("Invalid string length: " + len);
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) throw new EOFException("Truncated string");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
