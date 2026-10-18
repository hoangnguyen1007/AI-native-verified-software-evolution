package com.evolution.analysis.acquisition.streaming;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Original repository bytes. No case folding, Unicode normalization or host-path conversion. */
public record LogicalPath(String rawBase64) implements Comparable<LogicalPath> {
    public LogicalPath {
        byte[] bytes;
        try { bytes = Base64.getEncoder().encode(Base64.getDecoder().decode(rawBase64)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid raw path"); }
        if (!new String(bytes, StandardCharsets.US_ASCII).equals(rawBase64)) throw new IllegalArgumentException("Noncanonical path encoding");
        byte[] raw = Base64.getDecoder().decode(rawBase64);
        if (raw.length == 0 || raw[0] == '/' || raw[raw.length - 1] == '/') throw new IllegalArgumentException("Invalid logical path");
        int start = 0;
        for (int i = 0; i <= raw.length; i++) {
            if (i < raw.length && raw[i] == 0) throw new IllegalArgumentException("NUL in path");
            if (i == raw.length || raw[i] == '/') {
                int length = i - start;
                if (length == 0 || length == 1 && raw[start] == '.' || length == 2 && raw[start] == '.' && raw[start + 1] == '.')
                    throw new IllegalArgumentException("Traversal in logical path");
                start = i + 1;
            }
        }
    }
    public static LogicalPath of(byte[] raw) { return new LogicalPath(Base64.getEncoder().encodeToString(raw)); }
    public static LogicalPath utf8(String path) {
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(path));
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return of(bytes);
        } catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Unrepresentable local name"); }
    }
    public byte[] bytes() { return Base64.getDecoder().decode(rawBase64); }
    public Optional<String> utf8() {
        try { return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes())).toString()); }
        catch (CharacterCodingException invalid) { return Optional.empty(); }
    }
    /** Hex ordering preserves unsigned raw-byte ordering in storage indexes, including non-UTF8 names. */
    public String key() { return HexFormat.of().formatHex(bytes()); }
    @Override public int compareTo(LogicalPath other) { return Arrays.compareUnsigned(bytes(), other.bytes()); }
}
