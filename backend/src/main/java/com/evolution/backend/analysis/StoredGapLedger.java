package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Storage only. The canonical digest is still SHA-256 of the original ordered GapLedger JSON. */
final class StoredGapLedger {
    private static final String FORMAT = "job-gap-blocks:v1";
    private static final int ROWS = 64;
    private static final byte[] PREFIX = "{\"gaps\":[".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SUFFIX = "]}".getBytes(StandardCharsets.UTF_8);
    private final StreamingContentStore store;

    StoredGapLedger(StreamingContentStore store) { this.store = store; }

    record Block(long offset, ContentHandle bytes) {
        Block {
            if (offset < 0 || bytes.size() < 1) throw new IllegalArgumentException("Invalid gap block");
        }
    }

    record Index(String schema, int total, ContentDigest canonical, Optional<ContentHandle> pack, List<Block> chunks) {
        Index {
            chunks = List.copyOf(chunks);
            if (!FORMAT.equals(schema) || total < 0 || chunks.size() != (total + (long) ROWS - 1) / ROWS
                    || pack.isPresent() != (total != 0))
                throw new IllegalArgumentException("Invalid gap block index");
            long end = 0;
            for (var block : chunks) {
                if (block.offset() != end || block.bytes().size() > pack.orElseThrow().size() - end)
                    throw new IllegalArgumentException("Invalid gap block range");
                end += block.bytes().size();
            }
            if (pack.isPresent() && end != pack.orElseThrow().size())
                throw new IllegalArgumentException("Incomplete gap block ranges");
        }
    }

    ContentHandle write(List<CapabilityGapRecord> rows,BooleanSupplier cancelled) throws IOException {
        var chunks = new ArrayList<Block>();
        var digest = sha256();
        digest.update(PREFIX);
        Optional<ContentHandle> pack = rows.isEmpty() ? Optional.empty() : Optional.of(store.write(output -> {
            long packed = 0;
            for (int offset = 0; offset < rows.size();) {
                if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
                int end = offset + Math.min(ROWS, rows.size() - offset);
                if (offset > 0) digest.update((byte) ',');
                var chunkDigest = sha256();
                var range = new CountedOutput(new DigestOutputStream(output, chunkDigest), Long.MAX_VALUE);
                var deflater = new Deflater(Deflater.BEST_SPEED);
                try {
                    var zip = new DeflaterOutputStream(range, deflater, 8192);
                    var writer = new BufferedWriter(new OutputStreamWriter(
                            new CountedOutput(new DigestOutputStream(zip, digest), MAX_BLOCK_BYTES),
                            StandardCharsets.UTF_8));
                    for (int i = offset; i < end; i++) {
                        if (i > offset) writer.write(',');
                        CanonicalJson.writeTo(rows.get(i), writer);
                    }
                    writer.flush();
                    zip.finish();
                } finally { deflater.end(); }
                chunks.add(new Block(packed, new ContentHandle(finish(chunkDigest), range.written)));
                packed = Math.addExact(packed, range.written);
                offset = end;
            }
        }, cancelled));
        digest.update(SUFFIX);
        var index = new Index(FORMAT, rows.size(), finish(digest), pack, chunks);
        return store.write(out -> {
            var writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            CanonicalJson.writeTo(index, writer);
            writer.flush();
        }, cancelled);
    }

    Index index(ContentHandle handle) throws IOException {
        if (handle.size() > 128_000_000 || !store.verify(handle)) throw corrupt();
        try (var in = new InputStreamReader(store.open(handle), StandardCharsets.UTF_8)) {
            var index = CanonicalRecordReader.read(GraphJson.read(in,
                    new GraphJson.Limits(128_000_000, 16, 8_000_000)), Index.class);
            if (!CanonicalJson.sha256(index).equals(handle.digest())) throw corrupt();
            return index;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("JOB_GAP_LEDGER_CORRUPT", invalid);
        }
    }

    boolean verify(Index index, BooleanSupplier cancelled) throws IOException {
        if (index.pack().isPresent() && !store.verify(index.pack().orElseThrow(), cancelled)) return false;
        var digest = sha256();
        digest.update(PREFIX);
        byte[] block = new byte[64 * 1024];
        for (int i = 0; i < index.chunks().size(); i++) {
            var chunk = index.chunks().get(i);
            if (i > 0) digest.update((byte) ',');
            long remaining = MAX_BLOCK_BYTES;
            try (var raw = new InflaterInputStream(range(index, chunk))) {
                int read;
                while ((read = raw.read(block)) != -1) {
                    if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                    if ((remaining -= read) < 0) throw corrupt();
                    digest.update(block, 0, read);
                }
            }
        }
        digest.update(SUFFIX);
        return index.canonical().equals(finish(digest));
    }

    List<CapabilityGapRecord> page(Index index, int offset, int size) throws IOException {
        int end = offset + Math.min(size, index.total() - offset);
        var result = new ArrayList<CapabilityGapRecord>(Math.min(size, 1024));
        for (int start = offset; start < end;) {
            int number = start / ROWS;
            var block = index.chunks().get(number);
            int first = number * ROWS;
            int available = Math.min(ROWS, index.total() - first);
            int limit = Math.min(end - first, available);
            readChunk(index, block, start - first, limit, available, result);
            start = first + limit;
        }
        return List.copyOf(result);
    }

    private void readChunk(Index index, Block block, int start, int limit, int count,
            List<CapabilityGapRecord> result) throws IOException {
        // Stream rows rather than decoding the whole chunk into maps at once.
        var digest = sha256();
        try (var input = new DigestInputStream(range(index, block), digest);
             var raw = new InflaterInputStream(new FilterInputStream(input) {
                 @Override public void close() {} // The full compressed range is verified below.
             });
             var framed = new SequenceInputStream(Collections.enumeration(List.of(
                     new ByteArrayInputStream(PREFIX), raw, new ByteArrayInputStream(SUFFIX))));
             var reader = new InputStreamReader(framed, StandardCharsets.UTF_8.newDecoder())) {
            int[] seen = {0};boolean[] readRows={false};
            GraphJson.streamObject(reader, new GraphJson.StreamLimits(MAX_BLOCK_BYTES+PREFIX.length+SUFFIX.length, 128, MAX_BLOCK_BYTES),
                    (name, member) -> {
                        if (!name.equals("gaps")||readRows[0]) throw corrupt();
                        readRows[0]=true;
                        member.array(value -> {
                            int i = seen[0]++;
                            if (i >= count) throw corrupt();
                            if (i >= start && i < limit)
                                result.add(CanonicalRecordReader.read(value, CapabilityGapRecord.class));
                        });
                    });
            input.transferTo(OutputStream.nullOutputStream());
            if (!readRows[0] || seen[0] != count || !finish(digest).equals(block.bytes().digest())) throw corrupt();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("JOB_GAP_LEDGER_CORRUPT", invalid);
        }
    }

    private InputStream range(Index index, Block block) throws IOException {
        return store.range(index.pack().orElseThrow(), block.offset(), block.bytes().size());
    }

    private static final long MAX_BLOCK_BYTES = 256_000_000L;
    private static final class CountedOutput extends FilterOutputStream {
        private final long limit;
        private long written;
        CountedOutput(OutputStream out, long limit) { super(out); this.limit = limit; }
        @Override public void write(int value) throws IOException {
            advance(1);out.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            advance(length);out.write(bytes, offset, length);
        }
        private void advance(int length) throws IOException {
            if (length > limit - written) throw new IOException("JOB_GAP_BLOCK_WORKER_REQUIRED");
            written += length;
        }
    }
    private static IOException corrupt() { return new IOException("JOB_GAP_LEDGER_CORRUPT"); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static ContentDigest finish(MessageDigest digest) {
        return new ContentDigest("sha256:" + HexFormat.of().formatHex(digest.digest()));
    }
}
