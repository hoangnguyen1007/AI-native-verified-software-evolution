package com.evolution.analysis.contract.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** A lowercase SHA-256 digest with an explicit algorithm prefix. */
public record ContentDigest(String value) implements CanonicalIdentifier, Comparable<ContentDigest> {

    private static final Pattern FORMAT = Pattern.compile("sha256:[0-9a-f]{64}");

    public ContentDigest {
        ContractChecks.notNull(value, "content digest");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "content digest must have form sha256:<64 lowercase hex characters>");
        }
    }

    public static ContentDigest sha256(byte[] content) {
        ContractChecks.notNull(content, "content");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return new ContentDigest("sha256:" + HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }

    public static ContentDigest sha256Utf8(String content) {
        return sha256(ContractChecks.notNull(content, "content").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int compareTo(ContentDigest other) {
        return value.compareTo(other.value);
    }
}
