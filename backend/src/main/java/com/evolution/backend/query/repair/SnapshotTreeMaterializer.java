package com.evolution.backend.query.repair;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Reconstructs a cryptographic SHA-256-verified baseline repository tree into a fresh isolated
 * temporary directory from either:
 * <ol>
 *   <li>An optional local checkout (fast path: verifies every file's live SHA-256 matches the snapshot pin),</li>
 *   <li>{@link StreamingContentStore#open(ContentHandle)} (supports both loose CAS blobs and {@code SmallBlobPacks} with zero reflection), or</li>
 *   <li>Inline {@code source.content} / {@code source.bytes} graph evidence.</li>
 * </ol>
 * Works identically for local directories and remote GitHub repositories!
 */
public final class SnapshotTreeMaterializer {

    public record MaterializedTree(
            SnapshotIdentity snapshot,
            Path directory,
            int totalSnapshotFiles,
            int materializedFiles,
            List<String> missingPaths,
            String source) {
        public MaterializedTree {
            missingPaths = List.copyOf(missingPaths);
        }

        public boolean complete() {
            return missingPaths.isEmpty() && materializedFiles == totalSnapshotFiles;
        }
    }

    private SnapshotTreeMaterializer() {}

    public static MaterializedTree materialize(
            KnowledgeGraph graph,
            Optional<StreamingContentStore> contentStore,
            Optional<Path> localCheckoutHint,
            Path targetDir,
            BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(targetDir);
        Files.createDirectories(targetDir);
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();

        // 1. Read authoritative file manifest from repository.snapshot evidence
        var snapshotFiles = readSnapshotManifest(graph);
        // 2. Also collect known file sizes & inline bytes from graph evidence (source.content / source.bytes / polyglot.inventory-entry)
        var sizesByDigest = new HashMap<ContentDigest, Long>();
        var inlineBytesByDigest = new HashMap<ContentDigest, byte[]>();
        indexGraphContentMetadata(graph, sizesByDigest, inlineBytesByDigest);

        int written = 0;
        int fromCheckout = 0;
        int fromCas = 0;
        int fromInline = 0;
        var missing = new ArrayList<String>();

        Path checkoutRoot = localCheckoutHint.map(p -> p.toAbsolutePath().normalize())
                .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .orElse(null);

        for (var entry : snapshotFiles.entrySet()) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IOException("MATERIALIZE_CANCELLED");
            }
            String relPath = entry.getKey();
            ContentDigest expected = entry.getValue();
            if (!isSafeRelative(relPath)) {
                missing.add(relPath);
                continue;
            }
            byte[] bytes = null;
            // Fast path 1: local checkout with strict SHA-256 verification
            if (checkoutRoot != null) {
                Path candidate = checkoutRoot.resolve(relPath).normalize();
                if (candidate.startsWith(checkoutRoot) && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] disk = Files.readAllBytes(candidate);
                    if (ContentDigest.sha256(disk).equals(expected)) {
                        bytes = disk;
                        fromCheckout++;
                    }
                }
            }
            // Path 2: StreamingContentStore public API (handles both loose CAS + SmallBlobPacks cleanly!)
            if (bytes == null && contentStore != null && contentStore.isPresent()) {
                Long knownSize = sizesByDigest.get(expected);
                if (knownSize != null && knownSize >= 0L) {
                    var handle = new ContentHandle(expected, knownSize);
                    try (var in = contentStore.orElseThrow().open(handle)) {
                        byte[] raw = in.readAllBytes();
                        if (ContentDigest.sha256(raw).equals(expected)) {
                            bytes = raw;
                            fromCas++;
                        }
                    } catch (IOException ignored) {
                        // Fall through to inline evidence
                    }
                }
            }
            // Path 3: Inline graph evidence (source.content / source.bytes)
            if (bytes == null) {
                byte[] inline = inlineBytesByDigest.get(expected);
                if (inline != null && ContentDigest.sha256(inline).equals(expected)) {
                    bytes = inline;
                    fromInline++;
                }
            }
            if (bytes == null) {
                missing.add(relPath);
                continue;
            }
            Path dest = normalizedTarget.resolve(relPath).normalize();
            if (!dest.startsWith(normalizedTarget)) {
                throw new IOException("UNSAFE_MATERIALIZE_PATH: " + relPath);
            }
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            Files.write(dest, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            written++;
        }

        String sourceLabel = fromCas > 0 && fromCheckout == 0 ? "CAS_CONTENT_STORE"
                : fromCheckout > 0 && fromCas == 0 && fromInline == 0 ? "VERIFIED_LOCAL_CHECKOUT"
                : fromInline > 0 && fromCheckout == 0 && fromCas == 0 ? "GRAPH_INLINE_EVIDENCE"
                : "HYBRID_VERIFIED";
        return new MaterializedTree(graph.snapshot(), normalizedTarget, snapshotFiles.size(), written, missing, sourceLabel);
    }

    private static Map<String, ContentDigest> readSnapshotManifest(KnowledgeGraph graph) {
        var files = new TreeMap<String, ContentDigest>();
        for (var ev : graph.evidence()) {
            if (!"repository.snapshot".equals(ev.family())) continue;
            try {
                var raw = GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(16 * 1024 * 1024, 32, 2_000_000)));
                for (var item : GraphJson.array(raw.get("files"))) {
                    var row = GraphJson.object(item);
                    String path = GraphJson.string(row.get("path"));
                    var digest = new ContentDigest(GraphJson.string(row.get("contentDigest")));
                    files.put(path, digest);
                }
                if (!files.isEmpty()) return files;
            } catch (RuntimeException ignored) {}
        }
        // Fallback: reconstruct file list from source.bytes and polyglot.inventory-entry when no repository.snapshot node exists
        for (var ev : graph.evidence()) {
            try {
                if ("source.bytes".equals(ev.family())) {
                    var row = GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(2 * 1024 * 1024, 24, 4096)));
                    if (row.get("document") instanceof Map<?, ?> doc
                            && doc.get("path") instanceof String p && doc.get("contentDigest") instanceof String d) {
                        files.putIfAbsent(p, new ContentDigest(d));
                    }
                }
            } catch (RuntimeException ignored) {}
        }
        return files;
    }

    private static void indexGraphContentMetadata(KnowledgeGraph graph,
                                                  Map<ContentDigest, Long> sizes,
                                                  Map<ContentDigest, byte[]> inline) {
        for (GraphEvidence ev : graph.evidence()) {
            String family = ev.family();
            if (!"source.content".equals(family) && !"source.bytes".equals(family) && !"polyglot.inventory-entry".equals(family)) {
                continue;
            }
            try {
                var row = GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(2 * 1024 * 1024, 24, 4096)));
                if ("source.content".equals(family)) {
                    if (row.get("contentDigest") instanceof String dStr) {
                        var digest = new ContentDigest(dStr);
                        if (row.get("size") instanceof Number n) sizes.putIfAbsent(digest, n.longValue());
                        if (row.get("bytesBase64") instanceof String b64) {
                            byte[] decoded = Base64.getDecoder().decode(b64);
                            if (ContentDigest.sha256(decoded).equals(digest)) {
                                inline.putIfAbsent(digest, decoded);
                                sizes.putIfAbsent(digest, (long) decoded.length);
                            }
                        }
                    }
                } else if ("source.bytes".equals(family)) {
                    if (row.get("document") instanceof Map<?, ?> doc && doc.get("contentDigest") instanceof String dStr) {
                        var digest = new ContentDigest(dStr);
                        if (row.get("bytesBase64") instanceof String b64) {
                            byte[] decoded = Base64.getDecoder().decode(b64);
                            if (ContentDigest.sha256(decoded).equals(digest)) {
                                inline.putIfAbsent(digest, decoded);
                                sizes.putIfAbsent(digest, (long) decoded.length);
                            }
                        }
                    }
                } else if ("polyglot.inventory-entry".equals(family)) {
                    if (row.get("fields") instanceof Map<?, ?> fields && fields.get("content") instanceof Map<?, ?> content
                            && content.get("digest") instanceof String dStr && content.get("size") instanceof Number n) {
                        sizes.putIfAbsent(new ContentDigest(dStr), n.longValue());
                    }
                }
            } catch (RuntimeException ignored) {}
        }
    }

    private static boolean isSafeRelative(String path) {
        try { ContractChecks.repositoryRelativePath(path, "snapshot path"); }
        catch (IllegalArgumentException invalid) { return false; }
        return !path.contains("..") && !path.startsWith("/") && !path.equals(".git") && !path.startsWith(".git/");
    }
}
