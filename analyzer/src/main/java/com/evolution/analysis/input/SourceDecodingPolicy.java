package com.evolution.analysis.input;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/** Explicit fallback policy used only when the build has no encoding declaration. */
public record SourceDecodingPolicy(Optional<String> fallbackCharset) {
    public SourceDecodingPolicy {
        fallbackCharset = ContractChecks.notNull(fallbackCharset, "fallback charset")
                .map(SourceDecodingPolicy::portableCharset);
    }

    public static SourceDecodingPolicy withholdWhenAbsent() {
        return new SourceDecodingPolicy(Optional.empty());
    }

    public static SourceDecodingPolicy assumeUtf8() {
        return new SourceDecodingPolicy(Optional.of(StandardCharsets.UTF_8.name()));
    }

    public ContentDigest identity() {
        return ContentDigest.sha256Utf8(CanonicalJson.write(this));
    }

    static String portableCharset(String name) {
        ContractChecks.text(name, "charset");
        return switch (name.toUpperCase(Locale.ROOT).replace('_', '-')) {
            case "UTF-8", "UTF8" -> "UTF-8";
            case "UTF-16", "UTF16" -> "UTF-16";
            case "UTF-16BE", "UTF16BE" -> "UTF-16BE";
            case "UTF-16LE", "UTF16LE" -> "UTF-16LE";
            case "ISO-8859-1", "ISO8859-1" -> "ISO-8859-1";
            case "US-ASCII", "ASCII" -> "US-ASCII";
            default -> throw new IllegalArgumentException("Charset is outside the portable decoding catalog");
        };
    }
}
