package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Authenticates canonical JSON wrappers with one streamed, content-addressed body. */
public final class CanonicalFrameVerifier {
    private CanonicalFrameVerifier() {}

    public static boolean verify(InputStream input, long size, String prefix, String suffix, ContentDigest body,
                                 Optional<ContentDigest> envelope, BooleanSupplier cancelled) throws IOException {
        var first = prefix.getBytes(StandardCharsets.UTF_8);
        var last = suffix.getBytes(StandardCharsets.UTF_8);
        if (size <= (long) first.length + last.length) return false;
        long remaining = size - first.length - last.length;
        var digest = StreamingContentStore.sha256();
        var full = envelope.isPresent() ? StreamingContentStore.sha256() : null;
        if (!Arrays.equals(first, input.readNBytes(first.length))) return false;
        if (full != null) full.update(first);
        byte[] buffer = new byte[64 * 1024];
        while (remaining > 0) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) return false;
            digest.update(buffer, 0, count);
            if (full != null) full.update(buffer, 0, count);
            remaining -= count;
        }
        if (!Arrays.equals(last, input.readNBytes(last.length)) || input.read() != -1 || !matches(digest, body)) return false;
        if (full == null) return true;
        full.update(last);
        return matches(full, envelope.orElseThrow());
    }

    private static boolean matches(MessageDigest digest, ContentDigest expected) {
        return expected.value().equals("sha256:" + HexFormat.of().formatHex(digest.digest()));
    }
}
