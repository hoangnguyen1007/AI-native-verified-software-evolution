package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.polyglot.SourceCoordinates;
import com.evolution.analysis.treesitter.TreeSitterSourceFrontend;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Per-file SHA-256-authenticated syntax cache for {@link TreeSitterSourceFrontend}.
 * <p>
 * Parsing one non-Java source file is a pure function of
 * {@code (provider, toolIdentity, language, contentDigest, size, fastArchitecture)}.
 * Unchanged files hit in memory/disk immediately without invoking native Tree-sitter bindings.
 */
final class IncrementalPolyglotCache {
    private static final byte[] MAGIC = "EVOPGC2\n".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_ENTRIES = Integer.getInteger("evo.incremental.polyglotMaxEntries", 100_000);
    private static final int MAX_STR_BYTES = 16 * 1024 * 1024;

    record Entry(TreeSitterSourceFrontend.Summary summary, List<TreeSitterSourceFrontend.Fact> facts) {}

    private final Path storeFile;
    private final Map<ContentDigest, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private volatile boolean dirty;

    private IncrementalPolyglotCache(Path storeFile) {
        this.storeFile = storeFile;
    }

    static IncrementalPolyglotCache open(Path cacheRoot) {
        Path file = Objects.requireNonNull(cacheRoot).toAbsolutePath().normalize().resolve("polyglot-syntax.bin.gz");
        var cache = new IncrementalPolyglotCache(file);
        if (!Boolean.getBoolean("evo.incremental.disabled")) {
            cache.loadQuietly();
        }
        return cache;
    }

    static ContentDigest key(String provider, String toolIdentity, String language, ContentDigest contentDigest,
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

    Optional<Entry> get(ContentDigest key) {
        if (Boolean.getBoolean("evo.incremental.disabled")) {
            missCount.incrementAndGet();
            return Optional.empty();
        }
        Entry found = entries.get(key);
        if (found != null) {
            hitCount.incrementAndGet();
            return Optional.of(found);
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
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(key)) {
            return;
        }
        entries.put(key, entry);
        dirty = true;
    }

    synchronized void flushQuietly() {
        if (Boolean.getBoolean("evo.incremental.disabled") || !dirty) return;
        Path pending = null;
        try {
            Files.createDirectories(storeFile.getParent());
            pending = Files.createTempFile(storeFile.getParent(), "poly-cache-", ".tmp");
            var bodyBuffer = new ByteArrayOutputStream(32 * 1024);
            var snapshot = new TreeMap<ContentDigest, Entry>(entries);
            try (var gz = new GZIPOutputStream(bodyBuffer);
                 var out = new DataOutputStream(new BufferedOutputStream(gz, 32 * 1024))) {
                out.write(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeInt(snapshot.size());
                for (var e : snapshot.entrySet()) {
                    writeStr(out, e.getKey().value());
                    var sum = e.getValue().summary();
                    out.writeLong(sum.nodes());
                    out.writeLong(sum.facts());
                    out.writeLong(sum.errors());
                    writeStr(out, sum.grammar());
                    writeStr(out, sum.toolIdentity());
                    var facts = e.getValue().facts();
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
                out.flush();
            }
            byte[] compressed = bodyBuffer.toByteArray();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(compressed);
            try (var out = Files.newOutputStream(pending, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(compressed);
                out.write(digest);
            }
            moveWithRetry(pending, storeFile);
            dirty = false;
        } catch (IOException | RuntimeException | NoSuchAlgorithmException ignored) {
            /* Best-effort persistence. */
        } finally {
            if (pending != null) {
                try { Files.deleteIfExists(pending); } catch (IOException ignored) {}
            }
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

    private void loadQuietly() {
        if (!Files.isRegularFile(storeFile, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            byte[] all = Files.readAllBytes(storeFile);
            if (all.length < 32) {
                Files.deleteIfExists(storeFile);
                return;
            }
            int bodyLen = all.length - 32;
            byte[] expected = new byte[32];
            System.arraycopy(all, bodyLen, expected, 0, 32);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(all, 0, bodyLen);
            if (!MessageDigest.isEqual(expected, md.digest())) {
                Files.deleteIfExists(storeFile);
                return;
            }
            try (var in = new DataInputStream(new BufferedInputStream(
                    new GZIPInputStream(new ByteArrayInputStream(all, 0, bodyLen)), 32 * 1024))) {
                byte[] magic = new byte[MAGIC.length];
                in.readFully(magic);
                if (!Arrays.equals(MAGIC, magic) || in.readInt() != FORMAT_VERSION) {
                    Files.deleteIfExists(storeFile);
                    return;
                }
                int count = in.readInt();
                if (count < 0 || count > MAX_ENTRIES) {
                    Files.deleteIfExists(storeFile);
                    return;
                }
                for (int i = 0; i < count; i++) {
                    ContentDigest key = new ContentDigest(readStr(in));
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
                    entries.put(key, new Entry(summary, List.copyOf(facts)));
                }
            }
        } catch (IOException | RuntimeException | NoSuchAlgorithmException corrupt) {
            entries.clear();
            try { Files.deleteIfExists(storeFile); } catch (IOException ignored) {}
        }
    }
}
