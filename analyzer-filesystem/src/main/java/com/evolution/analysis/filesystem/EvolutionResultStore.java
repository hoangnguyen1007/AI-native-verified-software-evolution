package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evolution.SnapshotComparison;
import com.evolution.analysis.evolution.SnapshotComparison.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Immutable ordered result sections. A root is addressable only after the complete row census is checked. */
public final class EvolutionResultStore {
    private static final String SCHEMA = "architecture.evolution.archive:v1";
    private static final int FANOUT = 32;
    private static final int LEAF_TARGET = 256 * 1024;
    private static final int MAX_OBJECT_BYTES = 192_000_000;
    private static final ThreadLocal<Boolean> DEFER_LEAF_FORCE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Set<String> SECTIONS = Arrays.stream(Section.values()).map(Enum::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final StreamingContentStore content;
    private final Path references;
    private final Path candidates;

    private record Node(ContentHandle handle, long rows) {
        Node {
            if (rows <= 0) throw new IllegalArgumentException("Invalid comparison node census");
        }
    }
    private record Root(String schema, Summary summary, Map<String,Node> sections, Map<String,Object> selection) {}

    public EvolutionResultStore(Path directory) throws IOException {
        this(directory, null);
    }

    EvolutionResultStore(Path directory, StreamingContentStore supplied) throws IOException {
        var root = StreamingContentStore.directory(directory);
        content = supplied == null
                ? new StreamingContentStore(root.resolve("cas"), 0, channel -> {
                    if (!DEFER_LEAF_FORCE.get()) channel.force(true);
                })
                : supplied;
        references = StreamingContentStore.directory(root.resolve("results"));
        candidates = StreamingContentStore.directory(root.resolve("candidates"));
    }

    public View publish(Result result, BooleanSupplier cancelled) throws IOException {
        var receipt = prepare(result, Map.of(), cancelled);
        return commit(verifyPrepared(receipt, cancelled), cancelled);
    }

    /** Forced immutable checkpoint. Only commit installs the public reference. */
    public ContentHandle prepare(Result result, Map<String,Object> selection, BooleanSupplier cancelled) throws IOException {
        var sections = new TreeMap<String,Node>();
        for (var section : Section.values()) {
            var root = writeSection(result, section, cancelled);
            root.ifPresent(node -> sections.put(section.name(), node));
        }
        return put(new Root(SCHEMA, result.summary(), sections, Map.copyOf(selection)), cancelled, false);
    }

    public final class Prepared {
        private final ContentHandle receipt;
        private final View view;
        private Prepared(ContentHandle receipt, View view) { this.receipt = receipt; this.view = view; }
    }

    public Prepared verifyPrepared(ContentHandle receipt, BooleanSupplier cancelled) throws IOException {
        var view = readRoot(receipt, cancelled);
        verify(view, cancelled);
        return new Prepared(receipt, view);
    }

    @FunctionalInterface public interface GraphVerifier { boolean verify(ContentDigest graph) throws IOException; }
    public boolean sameAvailableGraphs(ContentHandle receipt, ContentDigest left, ContentDigest right,
                                       GraphVerifier verifier, BooleanSupplier cancelled) throws IOException {
        var view = readRoot(receipt, cancelled);
        var values = view.rows(Section.INPUTS, 0, 2, cancelled);
        if (values.size() != 2) return false;
        var graphs = List.of(left, right);
        for (int index = 0; index < graphs.size(); index++) {
            var graph = graphs.get(index);
            var row = values.get(index);
            if (!(index == 0 ? "LEFT" : "RIGHT").equals(row.get("side")) ||
                    !graph.value().equals(row.get("graph")) || !"AVAILABLE".equals(row.get("status"))) return false;
            try {
                if (!verifier.verify(graph)) return false;
            } catch (IOException unavailable) { return false; }
        }
        return true;
    }

    public View commit(Prepared prepared, BooleanSupplier cancelled) throws IOException {
        var receipt = prepared.receipt;
        var view = prepared.view;
        check(cancelled);
        // Retain each competing root and fence automatic selection on the same realized operands.
        var folder = StreamingContentStore.directory(candidateFolder(view));
        var marker = folder.resolve(receipt.digest().value().substring(7) + ".json");
        var bytes = CanonicalJson.write(receipt).getBytes(StandardCharsets.UTF_8);
        link(marker, bytes);
        link(reference(receipt.digest()), bytes);
        requireUnambiguous(view);
        return view;
    }

    public void requireUnambiguous(View view) throws IOException {
        var folder = candidateFolder(view);
        StreamingContentStore.safeWithin(candidates, folder);
        try (var roots = Files.list(folder)) {
            if (roots.filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}\\.json")).limit(2).count() > 1)
                throw new IOException("EVOLUTION_RESULT_CONFLICT");
        }
    }

    private Path candidateFolder(View view) {
        return candidateFolder(view.summary().request(), view.summary().census(Section.INPUTS).digest());
    }

    private Path candidateFolder(ContentDigest request, ContentDigest inputs) {
        var tuple = CanonicalJson.sha256(List.of(SCHEMA, request, inputs));
        return candidates.resolve(tuple.value().substring(7));
    }

    public View open(ContentDigest identity, BooleanSupplier cancelled) throws IOException {
        var path = reference(identity);
        var receipt = pointer(path);
        if (!receipt.digest().equals(identity)) throw corrupt();
        return readRoot(receipt, cancelled);
    }

    public Optional<ContentHandle> findReusable(ContentDigest request, List<Map<String,Object>> inputRows,
                                                BooleanSupplier cancelled) throws IOException {
        ContentDigest inputsDigest = CanonicalJson.sha256(inputRows);
        var folder = candidateFolder(request, inputsDigest);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        StreamingContentStore.safeWithin(candidates, folder);
        try (var files = Files.list(folder)) {
            var known = files.filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}\\.json"))
                    .limit(2).toList();
            if (known.size() != 1) return Optional.empty();
            var name = known.getFirst().getFileName().toString();
            var id = new ContentDigest("sha256:" + name.substring(0, 64));
            var candidate = open(id, cancelled);
            if (!candidate.summary().request().equals(request) ||
                    !candidate.summary().census(Section.INPUTS).digest().equals(inputsDigest)) throw corrupt();
            verify(candidate, cancelled);
            requireUnambiguous(candidate);
            return Optional.of(candidate.receipt());
        } catch (NoSuchFileException uncommitted) { return Optional.empty(); }
    }

    /** Full closure verification for reuse and auditing. Ordinary paging verifies only visited nodes. */
    public void verify(View view, BooleanSupplier cancelled) throws IOException {
        for (var section : Section.values()) {
            var root = view.root.sections().get(section.name());
            MessageDigest hash = StreamingContentStore.borrowSha256();
            try {
                try (var writer = new BufferedWriter(new OutputStreamWriter(
                        new DigestOutputStream(OutputStream.nullOutputStream(), hash), StandardCharsets.UTF_8), 65536)) {
                    writer.write('[');
                    var first = new boolean[]{true};
                    if (root != null) visit(root, 0, root.rows(), row -> {
                        if (!first[0]) writer.write(',');
                        first[0] = false;
                        CanonicalJson.writeTo(row, writer);
                    }, cancelled, 0);
                    writer.write(']');
                }
                if (!("sha256:" + HexFormat.of().formatHex(hash.digest())).equals(view.summary().census(section).digest().value()))
                    throw corrupt();
            } finally {
                StreamingContentStore.releaseSha256(hash);
            }
        }
    }

    public final class View {
        private final ContentDigest identity;
        private final Root root;
        private View(ContentDigest identity, Root root) { this.identity = identity; this.root = root; }
        public ContentDigest identity() { return identity; }
        private ContentHandle receipt() throws IOException { return pointer(reference(identity)); }
        public Summary summary() { return root.summary(); }
        public Map<String,Object> selection() { return root.selection(); }
        public List<Map<String,Object>> rows(Section section, long offset, int size, BooleanSupplier cancelled) throws IOException {
            long count = summary().census(section).rows();
            if (offset < 0 || offset > count || size < 1 || size > 50)
                throw new IllegalArgumentException("Invalid comparison page");
            check(cancelled);
            var rows = new ArrayList<Map<String,Object>>();
            long amount = Math.min(count - offset, size);
            if (amount > 0) {
                long[] bytes = {0};
                try {
                    visit(root.sections().get(section.name()), offset, offset + amount, row -> {
                        var weight = CanonicalJson.write(row).length();
                        if (!rows.isEmpty() && bytes[0] + weight > 1_048_576) throw new PageFull();
                        rows.add(row);
                        bytes[0] += weight;
                    }, cancelled, 0);
                } catch (PageFull bounded) { /* Resume from the next ordinal. */ }
            }
            if (amount > 0 && rows.isEmpty()) throw corrupt();
            return List.copyOf(rows);
        }
        public void scan(Section section, RowVisitor visitor, BooleanSupplier cancelled) throws IOException {
            var node = root.sections().get(section.name());
            if (node != null) visit(node, 0, node.rows(), visitor, cancelled, 0);
        }
    }

    private Optional<Node> writeSection(Result result, Section section, BooleanSupplier cancelled) throws IOException {
        var tree = new TreeWriter(cancelled);
        var census = result.summary().census(section);
        MessageDigest hash = StreamingContentStore.borrowSha256();
        long rows = 0;
        try {
            try (var canonical = new BufferedWriter(new OutputStreamWriter(
                    new DigestOutputStream(OutputStream.nullOutputStream(), hash), StandardCharsets.UTF_8), 65536)) {
                canonical.write('[');
                Optional<String> cursor = Optional.empty();
                var leaf = new ArrayList<String>();
                int bytes = 0;
                do {
                    check(cancelled);
                    var page = result.page(section, 50, cursor);
                    cursor = page.continuation();
                    for (var row : page.items()) {
                        if (rows++ > 0) canonical.write(',');
                        var encoded = CanonicalJson.write(row);
                        canonical.write(encoded);
                        // UTF-8 requires at most three bytes per UTF-16 code unit.
                        int weight = Math.addExact(Math.multiplyExact(encoded.length(), 3), 1);
                        if (!leaf.isEmpty() && bytes + weight > LEAF_TARGET) {
                            tree.add(new Node(putPreencodedArray(leaf, cancelled), leaf.size()), 0);
                            leaf.clear();
                            bytes = 0;
                        }
                        leaf.add(encoded);
                        bytes += weight;
                    }
                } while (cursor.isPresent());
                if (!leaf.isEmpty()) tree.add(new Node(putPreencodedArray(leaf, cancelled), leaf.size()), 0);
                canonical.write(']');
            }
            if (rows != census.rows() || !new ContentDigest("sha256:" + HexFormat.of().formatHex(hash.digest()))
                    .equals(census.digest())) throw corrupt();
            return tree.finish();
        } finally {
            StreamingContentStore.releaseSha256(hash);
        }
    }

    private ContentHandle putPreencodedArray(List<String> encodedItems, BooleanSupplier cancelled) throws IOException {
        DEFER_LEAF_FORCE.set(Boolean.TRUE);
        try {
            return content.write(output -> {
                var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), 65536);
                writer.write('[');
                for (int i = 0; i < encodedItems.size(); i++) {
                    if (i > 0) writer.write(',');
                    writer.write(encodedItems.get(i));
                }
                writer.write(']');
                writer.flush();
            }, cancelled);
        } finally {
            DEFER_LEAF_FORCE.remove();
        }
    }

    private final class TreeWriter {
        private final List<List<Node>> levels = new ArrayList<>();
        private final BooleanSupplier cancelled;
        TreeWriter(BooleanSupplier cancelled) { this.cancelled = cancelled; }
        void add(Node node, int level) throws IOException {
            while (levels.size() <= level) levels.add(new ArrayList<>());
            var nodes = levels.get(level);
            nodes.add(node);
            if (nodes.size() == FANOUT) {
                var parent = branch(nodes);
                nodes.clear();
                add(parent, level + 1);
            }
        }
        Optional<Node> finish() throws IOException {
            Node tail = null;
            for (var nodes : levels) {
                if (tail != null) nodes.add(tail);
                if (!nodes.isEmpty()) tail = nodes.size() == 1 ? nodes.getFirst() : branch(nodes);
            }
            return Optional.ofNullable(tail);
        }
        private Node branch(List<Node> nodes) throws IOException {
            long rows = 0;
            for (var node : nodes) rows = Math.addExact(rows, node.rows());
            return new Node(put(Map.of("children", nodes), cancelled, false), rows);
        }
    }

    @FunctionalInterface public interface RowVisitor { void accept(Map<String,Object> row) throws IOException; }
    private static final class PageFull extends IOException {}

    private void visit(Node node, long from, long until, RowVisitor consumer, BooleanSupplier cancelled, int depth) throws IOException {
        if (depth > 64) throw corrupt();
        try {
            var value = read(node.handle(), cancelled);
            if (value instanceof List<?> leaf) {
                if (leaf.size() != node.rows()) throw corrupt();
                for (long i = from; i < until; i++) {
                    check(cancelled);
                    consumer.accept(GraphJson.object(leaf.get((int) i)));
                }
                return;
            }
            var children = GraphJson.array(fields(value, "children").get("children"));
            if (children.isEmpty() || children.size() > FANOUT) throw corrupt();
            long start = 0;
            for (var valueChild : children) {
                var child = node(valueChild);
                long end = Math.addExact(start, child.rows());
                if (from < end && until > start)
                    visit(child, Math.max(0, from - start), Math.min(child.rows(), until - start),
                            consumer, cancelled, depth + 1);
                start = end;
            }
            if (start != node.rows()) throw corrupt();
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw corrupt();
        }
    }

    private View readRoot(ContentHandle handle, BooleanSupplier cancelled) throws IOException {
        if (handle.size() > 65_536) throw corrupt();
        try {
            var envelope = fields(read(handle, cancelled), "schema", "sections", "summary", "selection");
            if (!SCHEMA.equals(envelope.get("schema"))) throw corrupt();
            var raw = fields(envelope.get("summary"), "request", "identity", "status", "sections");
            var sections = new TreeMap<String,Census>();
            var rawSections = GraphJson.object(raw.get("sections"));
            if (!rawSections.keySet().equals(SECTIONS)) throw corrupt();
            rawSections.forEach((name, value) -> {
                var row = fields(value, "rows", "digest");
                sections.put(name, new Census(number(row.get("rows")), id(row.get("digest"))));
            });
            var nodes = new TreeMap<String,Node>();
            GraphJson.object(envelope.get("sections")).forEach((name, value) -> nodes.put(name, node(value)));
            if (!sections.keySet().containsAll(nodes.keySet())) throw corrupt();
            for (var section : sections.entrySet()) {
                var node = nodes.get(section.getKey());
                if (section.getValue().rows() != (node == null ? 0 : node.rows())) throw corrupt();
            }
            var summary = new Summary(id(raw.get("request")), id(raw.get("identity")),
                    Status.valueOf(GraphJson.string(raw.get("status"))), sections);
            return new View(handle.digest(), new Root(SCHEMA, summary, nodes, GraphJson.object(envelope.get("selection"))));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw corrupt();
        }
    }

    private Object read(ContentHandle handle, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        if (handle.size() > MAX_OBJECT_BYTES) throw corrupt();
        try (var input = content.open(handle)) {
            var bytes = input.readNBytes((int) handle.size() + 1);
            if (bytes.length != handle.size() || !ContentDigest.sha256(bytes).equals(handle.digest())) throw corrupt();
            return GraphJson.read(new String(bytes, StandardCharsets.UTF_8),
                    new GraphJson.Limits(MAX_OBJECT_BYTES, 130, MAX_OBJECT_BYTES));
        }
    }

    private ContentHandle put(Object value, BooleanSupplier cancelled, boolean deferForce) throws IOException {
        if (deferForce) DEFER_LEAF_FORCE.set(Boolean.TRUE);
        try {
            return content.write(output -> {
                var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), 65536);
                CanonicalJson.writeTo(value, writer);
                writer.flush();
            }, cancelled);
        } finally {
            if (deferForce) DEFER_LEAF_FORCE.remove();
        }
    }

    private Path reference(ContentDigest identity) throws IOException {
        var path = references.resolve(identity.value().substring(7) + ".json");
        StreamingContentStore.safeWithin(references, path);
        return path;
    }

    private static ContentHandle pointer(Path path) throws IOException {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var bytes = input.readNBytes(257);
            if (bytes.length > 256) throw corrupt();
            return handle(GraphJson.read(new String(bytes, StandardCharsets.UTF_8), new GraphJson.Limits(256, 4, 12)));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw corrupt();
        }
    }

    private static void link(Path target, byte[] bytes) throws IOException {
        StreamingContentStore.directory(target.getParent());
        var file = Files.createTempFile(target.getParent(), "result-", ".pending");
        try {
            try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                StreamingContentStore.createLinkWithRetry(target, file);
            } catch (FileAlreadyExistsException exists) {
                if (!Arrays.equals(bytes, CanonicalJson.write(pointer(target)).getBytes(StandardCharsets.UTF_8)))
                    throw new IOException("EVOLUTION_PUBLICATION_CONFLICT");
            }
        } finally {
            StreamingContentStore.deleteIfExistsQuietly(file);
        }
    }

    private static Map<String,Object> fields(Object value, String... names) {
        var map = GraphJson.object(value);
        if (!map.keySet().equals(Set.of(names))) throw new IllegalArgumentException("Invalid comparison archive");
        return map;
    }
    private static Node node(Object value) {
        var row = fields(value, "handle", "rows");
        return new Node(handle(row.get("handle")), number(row.get("rows")));
    }
    private static ContentHandle handle(Object value) {
        var row = fields(value, "digest", "size");
        return new ContentHandle(id(row.get("digest")), number(row.get("size")));
    }
    private static ContentDigest id(Object value) { return new ContentDigest(GraphJson.string(value)); }
    private static long number(Object value) { return ((BigDecimal) value).longValueExact(); }
    private static IOException corrupt() { return new IOException("EVOLUTION_ARCHIVE_CORRUPT"); }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
