package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import java.util.regex.Pattern;

/** Accepted additive M4-R0 SHA-256 identity envelope. */
final class TruthIdentity {
    private TruthIdentity() {}

    static ContentDigest digest(Object value) {
        return ContentDigest.sha256Utf8(CanonicalJson.write(value));
    }

    static String derive(String kind, Map<String, ?> fields) {
        var payload = new TreeMap<String, Object>(fields);
        payload.put("schema", "m4-r0-identity-v1");
        return kind + ":" + digest(Map.of(
                "components", List.of(CanonicalJson.write(payload)),
                "kind", kind,
                "version", 1)).value();
    }

    static String require(String value, String kind) {
        if (value == null || !Pattern.matches(Pattern.quote(kind) + ":sha256:[0-9a-f]{64}", value)) {
            throw new IllegalArgumentException("Invalid " + kind + " identity");
        }
        return value;
    }
}
