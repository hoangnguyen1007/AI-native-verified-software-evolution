package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import java.util.regex.Pattern;

/** Additive M4-R0 envelope. Does not change any M1 identity preimage. */
final class ConditionIdentitySupport {
    static final String SCHEMA = "m4-r0-identity-v1";
    private ConditionIdentitySupport() {}

    static String derive(String kind, Map<String, ?> fields) {
        Map<String, Object> payload = new TreeMap<>(fields);
        payload.put("schema", SCHEMA);
        return kind + ":" + digest(Map.of("components", List.of(CanonicalJson.write(payload)),
                "kind", kind, "version", 1)).value();
    }

    static ContentDigest digest(Object value) { return ContentDigest.sha256Utf8(CanonicalJson.write(value)); }

    static String require(String value, String kind) {
        if (value == null || !Pattern.matches(Pattern.quote(kind) + ":sha256:[0-9a-f]{64}", value)) {
            throw new IllegalArgumentException("Invalid " + kind + " identity");
        }
        return value;
    }

    /** Raw semantic text: validate UTF-16 but never trim, fold case or normalize Unicode. */
    static String raw(String value) {
        Objects.requireNonNull(value);
        CanonicalJson.write(value);
        return value;
    }

    static String name(String value) {
        raw(value);
        if (value.isEmpty()) throw new IllegalArgumentException("Semantic name must not be empty");
        return value;
    }
}
