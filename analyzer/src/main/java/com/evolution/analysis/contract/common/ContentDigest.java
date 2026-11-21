package com.evolution.analysis.contract.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** A lowercase SHA-256 digest with an explicit algorithm prefix. */
public record ContentDigest(String value) implements CanonicalIdentifier, Comparable<ContentDigest> {

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    });

    public ContentDigest {
        ContractChecks.notNull(value, "content digest");
        if (!isValidSha256(value)) {
            throw new IllegalArgumentException(
                    "content digest must have form sha256:<64 lowercase hex characters>");
        }
    }

    private static boolean isValidSha256(String s) {
        if (s.length() != 71 || !s.startsWith("sha256:")) {
            return false;
        }
        for (int i = 7; i < 71; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    public static ContentDigest sha256(byte[] content) {
        ContractChecks.notNull(content, "content");
        var md = SHA256.get();
        md.reset();
        byte[] digest = md.digest(content);
        return new ContentDigest("sha256:" + HexFormat.of().formatHex(digest));
    }

    public static ContentDigest sha256Utf8(String content) {
        return sha256(ContractChecks.notNull(content, "content").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int compareTo(ContentDigest other) {
        return value.compareTo(other.value);
    }
}
