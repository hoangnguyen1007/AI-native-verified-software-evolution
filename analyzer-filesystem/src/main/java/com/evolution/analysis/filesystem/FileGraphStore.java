package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static com.evolution.analysis.graph.GraphStoreException.Reason.*;

/** Bounded canonical snapshot I/O in an explicitly supplied existing directory. No database needed. */
public final class FileGraphStore implements GraphStore {
    private static final int IO_BUFFER_SIZE = 64 * 1024;
    private final Path directory;
    private final long maxBytes;
    private final GraphJson.StreamLimits jsonLimits;

    public FileGraphStore(Path directory) { this(directory, 64_000_000, GraphJson.Limits.defaults()); }
    public FileGraphStore(Path directory, int maxBytes, GraphJson.Limits jsonLimits) {
        this(directory, (long) maxBytes, legacyLimits(maxBytes, jsonLimits));
    }
    private static GraphJson.StreamLimits legacyLimits(int maximum, GraphJson.Limits limits) {
        if (maximum < 1 || maximum == Integer.MAX_VALUE) throw new IllegalArgumentException("Bounded positive graph store byte limit required");
        return GraphJson.StreamLimits.from(Objects.requireNonNull(limits));
    }
    public FileGraphStore(Path directory, long maxBytes, GraphJson.StreamLimits jsonLimits) {
        Objects.requireNonNull(directory); Objects.requireNonNull(jsonLimits);
        if (maxBytes < 1 || maxBytes == Long.MAX_VALUE) throw new IllegalArgumentException("Finite positive graph store byte budget required");
        this.directory = directory.toAbsolutePath().normalize(); this.maxBytes = maxBytes; this.jsonLimits = jsonLimits;
        this.binaryCache = new GraphBinaryCache(this.directory);
        checkDirectory();
    }

    private final GraphBinaryCache binaryCache;

    private void checkDirectory() {
        for (Path part = directory; part != null; part = part.getParent()) if (Files.isSymbolicLink(part)) throw new GraphStoreException(UNSAFE_PATH);
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw new GraphStoreException(UNSAFE_PATH);
    }

    private Path path(ContentDigest identity) {
        Objects.requireNonNull(identity); checkDirectory();
        Path path = directory.resolve(identity.value().substring(7) + ".graph.json").normalize();
        if (!path.getParent().equals(directory)) throw new GraphStoreException(UNSAFE_PATH);
        if (Files.isSymbolicLink(path)) throw new GraphStoreException(UNSAFE_PATH);
        return path;
    }

    @Override public synchronized void save(KnowledgeGraph graph, BooleanSupplier cancelled) {
        Objects.requireNonNull(graph); Objects.requireNonNull(cancelled);
        var destination = path(graph.identity());
        if (cancelled.getAsBoolean()) throw new GraphStoreException(CANCELLED);
        var codec = new GraphSnapshotCodec();
        Path pending = null;
        try {
            if (Files.exists(destination, NOFOLLOW_LINKS)) {
                // Preserve the admission checks for an existing snapshot without encoding
                // another full copy or reconstructing the graph just to compare it.
                if (!CanonicalJson.fitsStructure(Map.of("format", GraphSnapshotCodec.FORMAT,
                        "identity", graph.identity(), "graph", graph.canonicalForm()), jsonLimits.maxDepth(), jsonLimits.maxValues()))
                    throw new GraphJson.LimitExceededException("Graph JSON structural limit");
                verifyExisting(graph.identity(), cancelled);
                return;
            }
            pending = Files.createTempFile(directory, "graph-pending-", ".tmp");
            try (var channel = FileChannel.open(pending, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) {
                var bufferedBytes = new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(channel), IO_BUFFER_SIZE);
                var stream = new LimitedBytes(bufferedBytes, maxBytes, cancelled);
                // Typed graph/evidence invariants were checked by construction. Exact structural,
                // character and UTF-8 byte bounds are checked without per-character synchronization locks.
                var writer = new FastLimitedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), jsonLimits.maxCharacters());
                codec.encodeTo(graph, writer, jsonLimits);
                writer.flush();
                channel.force(true);
            }
            if (cancelled.getAsBoolean()) throw new GraphStoreException(CANCELLED);
            checkDirectory(); path(graph.identity());
            // Never replace an already published snapshot, including from another store instance.
            if (Files.exists(destination, NOFOLLOW_LINKS)) { verifyExisting(graph.identity(), cancelled); return; }
            // Atomic create-if-absent publication: unlike ATOMIC_MOVE, cannot replace a racing destination.
            try { StreamingContentStore.createLinkWithRetry(destination, pending); }
            catch (FileAlreadyExistsException raced) { verifyExisting(graph.identity(), cancelled); binaryCache.write(graph, destination); return; }
            binaryCache.write(graph, destination);
        } catch (GraphStoreException failure) { throw failure; }
        catch (GraphJson.LimitExceededException | StorageLimit limit) { var failure = new GraphStoreException(LIMIT_EXCEEDED); failure.initCause(limit); throw failure; }
        catch (IOException failure) { throw new GraphStoreException(IO_FAILURE); }
        finally { if (pending != null) try { StreamingContentStore.deleteIfExistsQuietly(pending); } catch (IOException ignored) { /* Not published; existing snapshots remain intact. */ } }
    }

    private void verifyExisting(ContentDigest id, BooleanSupplier cancelled) throws IOException {
        Path source = path(id);
        long size = Files.size(source);
        if (size > maxBytes) throw new GraphStoreException(LIMIT_EXCEEDED);
        if (!verifyPublished(id, cancelled)) throw new GraphStoreException(CORRUPT_SNAPSHOT);
        if (jsonLimits.maxCharacters() < size) {
            // UTF-8 may have more bytes than characters. Check this uncommon tighter
            // bound without loading graph rows into memory.
            try (var reader = new BufferedReader(new InputStreamReader(
                    Files.newInputStream(source, StandardOpenOption.READ, NOFOLLOW_LINKS),
                    StandardCharsets.UTF_8.newDecoder()), IO_BUFFER_SIZE)) {
                long remaining = jsonLimits.maxCharacters();
                var buffer = new char[8192]; int count;
                while ((count = reader.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean()) throw new GraphStoreException(CANCELLED);
                    if ((remaining -= count) < 0) throw new GraphStoreException(LIMIT_EXCEEDED);
                }
            }
        }
    }

    private static class StorageLimit extends IOException {}
    private static final class ByteLimit extends StorageLimit {}
    private static final class CharacterLimit extends StorageLimit {}

    private static final class LimitedBytes extends FilterOutputStream {
        private long remaining; private final BooleanSupplier cancelled;
        LimitedBytes(OutputStream out, long maximum, BooleanSupplier cancelled) { super(out); remaining = maximum; this.cancelled = cancelled; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (cancelled.getAsBoolean()) throw new GraphStoreException(CANCELLED);
            if ((remaining -= length) < 0) throw new ByteLimit();
            out.write(bytes, offset, length);
        }
        @Override public void write(int value) throws IOException {
            if (cancelled.getAsBoolean()) throw new GraphStoreException(CANCELLED);
            if (--remaining < 0) throw new ByteLimit();
            out.write(value);
        }
    }

    /** Unsynchronized single-producer buffered Writer with exact character budget enforcement. */
    private static final class FastLimitedWriter extends Writer {
        private final Writer downstream;
        private final char[] buffer = new char[IO_BUFFER_SIZE];
        private int pos;
        private long remaining;

        FastLimitedWriter(Writer downstream, long maximum) {
            this.downstream = downstream;
            this.remaining = maximum;
        }

        private void spend(int n) throws CharacterLimit {
            if ((remaining -= n) < 0) throw new CharacterLimit();
        }

        private void flushBuffer() throws IOException {
            if (pos > 0) {
                downstream.write(buffer, 0, pos);
                pos = 0;
            }
        }

        @Override public void write(int c) throws IOException {
            spend(1);
            if (pos == buffer.length) flushBuffer();
            buffer[pos++] = (char) c;
        }

        @Override public Writer append(char c) throws IOException {
            write((int) c);
            return this;
        }

        @Override public Writer append(CharSequence csq) throws IOException {
            String s = String.valueOf(csq);
            write(s, 0, s.length());
            return this;
        }

        @Override public Writer append(CharSequence csq, int start, int end) throws IOException {
            CharSequence seq = csq == null ? "null" : csq;
            int len = end - start;
            spend(len);
            if (seq instanceof String s) {
                writeStringUnchecked(s, start, len);
            } else {
                for (int i = start; i < end; i++) {
                    if (pos == buffer.length) flushBuffer();
                    buffer[pos++] = seq.charAt(i);
                }
            }
            return this;
        }

        @Override public void write(char[] cbuf, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, cbuf.length);
            if (len == 0) return;
            spend(len);
            if (len >= buffer.length) {
                flushBuffer();
                downstream.write(cbuf, off, len);
                return;
            }
            if (len > buffer.length - pos) flushBuffer();
            System.arraycopy(cbuf, off, buffer, pos, len);
            pos += len;
        }

        @Override public void write(String str, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, str.length());
            if (len == 0) return;
            spend(len);
            writeStringUnchecked(str, off, len);
        }

        private void writeStringUnchecked(String str, int off, int len) throws IOException {
            int cur = off, rem = len;
            while (rem > 0) {
                if (pos == buffer.length) flushBuffer();
                int chunk = Math.min(rem, buffer.length - pos);
                str.getChars(cur, cur + chunk, buffer, pos);
                pos += chunk;
                cur += chunk;
                rem -= chunk;
            }
        }

        @Override public void flush() throws IOException {
            flushBuffer();
            downstream.flush();
        }

        @Override public void close() throws IOException {
            try { flushBuffer(); }
            finally { downstream.close(); }
        }
    }

    /** Returns the canonical {@code .graph.json} file path for {@code identity}. */
    public Path jsonPath(ContentDigest identity) {
        return path(identity);
    }

    /** Reads only the cached binary manifest if {@code .graph.json} and {@code .graph.bin} match. */
    public Optional<GraphManifest> readManifest(ContentDigest identity) {
        Path source = path(identity);
        if (!Files.isRegularFile(source, NOFOLLOW_LINKS)) return Optional.empty();
        return binaryCache.readManifest(identity, source);
    }

    @Override public Optional<GraphManifest> manifest(ContentDigest identity) {
        var cachedManifest = readManifest(identity);
        if (cachedManifest.isPresent()) return cachedManifest;
        return load(identity).map(KnowledgeGraph::manifest);
    }

    @Override public Optional<KnowledgeGraph> load(ContentDigest identity) {
        var source = path(identity);
        if (!Files.exists(source, NOFOLLOW_LINKS)) return Optional.empty();
        var cached = binaryCache.read(identity, source);
        if (cached.isPresent()) return cached;
        var graph = readPath(source);
        if (!graph.identity().equals(identity)) throw new GraphStoreException(CORRUPT_SNAPSHOT);
        binaryCache.write(graph, source);
        return Optional.of(graph);
    }

    @Override public boolean verifyPublished(ContentDigest identity, BooleanSupplier cancelled) {
        var source = path(identity);
        if (!Files.isRegularFile(source, NOFOLLOW_LINKS)) return false;
        try (var input = Files.newInputStream(source, StandardOpenOption.READ, NOFOLLOW_LINKS)) {
            long size = Files.size(source);
            return size <= maxBytes && CanonicalFrameVerifier.verify(input, size,
                    "{\"format\":\"" + GraphSnapshotCodec.FORMAT + "\",\"graph\":",
                    ",\"identity\":\"" + identity.value() + "\"}", identity, Optional.empty(), cancelled);
        } catch (IOException unavailable) {
            return false;
        }
    }

    public static KnowledgeGraph readSnapshot(Path file, int maximum, GraphJson.Limits limits) {
        Path source = file.toAbsolutePath().normalize();
        return new FileGraphStore(source.getParent(), maximum, limits).readPath(source);
    }

    public static KnowledgeGraph readSnapshot(Path file, long maximum, GraphJson.StreamLimits limits) {
        Path source = file.toAbsolutePath().normalize();
        return new FileGraphStore(source.getParent(), maximum, limits).readPath(source);
    }

    private KnowledgeGraph readPath(Path source) {
        if (!Files.isRegularFile(source, NOFOLLOW_LINKS)) throw new GraphStoreException(UNSAFE_PATH);
        try (var stream = new LimitedInput(new BufferedInputStream(Files.newInputStream(source, StandardOpenOption.READ, NOFOLLOW_LINKS), IO_BUFFER_SIZE), maxBytes)) {
            if (Files.size(source) > maxBytes) throw new GraphStoreException(LIMIT_EXCEEDED);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
                return new GraphSnapshotCodec().decode(reader, jsonLimits);
            }
        } catch (GraphStoreException failure) { throw failure; }
        catch (GraphJson.LimitExceededException limit) { throw new GraphStoreException(LIMIT_EXCEEDED); }
        catch (CharacterCodingException | IllegalArgumentException corrupt) { throw new GraphStoreException(CORRUPT_SNAPSHOT); }
        catch (IOException failure) { throw new GraphStoreException(IO_FAILURE); }
    }

    private static final class LimitedInput extends FilterInputStream {
        private long remaining; LimitedInput(InputStream in, long maximum) { super(in); remaining = maximum; }
        @Override public int read() throws IOException { int value = in.read(); if (value >= 0 && --remaining < 0) throw new GraphStoreException(LIMIT_EXCEEDED); return value; }
        @Override public int read(byte[] value, int offset, int length) throws IOException {
            int count = in.read(value, offset, (int) Math.min(length, remaining + 1));
            if (count > 0 && (remaining -= count) < 0) throw new GraphStoreException(LIMIT_EXCEEDED);
            return count;
        }
    }
}
