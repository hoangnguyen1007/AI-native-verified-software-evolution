package com.evolution.analysis.spring.binding;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;
import java.util.regex.Pattern;

/** Additive R0 identity envelope. No existing upstream preimage changes. */
final class BindingIdentity {
    private BindingIdentity() {}
    static ContentDigest digest(Object value) { return ContentDigest.sha256Utf8(CanonicalJson.write(value)); }
    static String derive(String kind, Map<String, ?> fields) {
        var payload = new TreeMap<String, Object>(fields);
        payload.put("schema", "m4-r0-identity-v1");
        return kind + ":" + digest(Map.of("components", List.of(CanonicalJson.write(payload)), "kind", kind, "version", 1)).value();
    }
    static String require(String value, String kind) {
        if (value == null || !Pattern.matches(Pattern.quote(kind) + ":sha256:[0-9a-f]{64}", value))
            throw new IllegalArgumentException("Invalid " + kind + " identity");
        return value;
    }
    static String text(String value) {
        Objects.requireNonNull(value); CanonicalJson.write(value);
        if (value.isEmpty()) throw new IllegalArgumentException("Empty binding key");
        return value;
    }
}
