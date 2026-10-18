package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Length-prefixed UTF-8, bounded per metadata record; content bytes never travel through this codec. */
final class InventoryCodec {
    static void text(DataOutput out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1_048_576) throw new IOException("METADATA_RECORD_TOO_LARGE");
        out.writeInt(bytes.length); out.write(bytes);
    }
    static String text(DataInput in) throws IOException {
        int length = in.readInt(); if (length < 0 || length > 1_048_576) throw new IOException("CORRUPT_METADATA");
        byte[] bytes = new byte[length]; in.readFully(bytes); return new String(bytes, StandardCharsets.UTF_8);
    }
    static void optional(DataOutput out, Optional<String> value) throws IOException { out.writeBoolean(value.isPresent()); if (value.isPresent()) text(out, value.orElseThrow()); }
    static Optional<String> optional(DataInput in) throws IOException { return in.readBoolean() ? Optional.of(text(in)) : Optional.empty(); }
    static byte[] entry(InventoryEntry entry) throws IOException {
        var bytes = new ByteArrayOutputStream(); try (var out = new DataOutputStream(bytes)) {
            text(out, entry.path().rawBase64()); text(out, entry.kind().name()); text(out, entry.mode()); text(out, entry.population().name()); text(out, entry.disposition().name());
            handle(out, entry.content()); optional(out, entry.target()); optional(out, entry.encoding());
            out.writeInt(entry.ownershipCandidates().size()); for (String owner : entry.ownershipCandidates()) text(out, owner);
            optional(out, entry.problem());
        } return bytes.toByteArray();
    }
    static InventoryEntry entry(byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            var path = new LogicalPath(text(in)); var kind = InventoryEntry.Kind.valueOf(text(in)); String mode = text(in);
            var population = InventoryEntry.Population.valueOf(text(in)); var disposition = InventoryEntry.Disposition.valueOf(text(in));
            var content = handle(in); var target = optional(in); var encoding = optional(in);
            int size = in.readInt(); if (size < 0 || size > 4096) throw new IOException("CORRUPT_METADATA");
            var owners = new ArrayList<String>(); for (int i = 0; i < size; i++) owners.add(text(in));
            var result = new InventoryEntry(path, kind, mode, population, disposition, content, target, encoding, owners, optional(in));
            if (in.read() != -1) throw new IOException("CORRUPT_METADATA"); return result;
        } catch (IllegalArgumentException invalid) { throw new IOException("CORRUPT_METADATA"); }
    }
    static void handle(DataOutput out, Optional<ContentHandle> value) throws IOException {
        out.writeBoolean(value.isPresent()); if (value.isPresent()) { text(out, value.orElseThrow().digest().value()); out.writeLong(value.orElseThrow().size()); }
    }
    static Optional<ContentHandle> handle(DataInput in) throws IOException { return in.readBoolean() ? Optional.of(new ContentHandle(new ContentDigest(text(in)), in.readLong())) : Optional.empty(); }
    private InventoryCodec() {}
}
