package com.evolution.analysis.contract.identity;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class IdentitySupport {

    private IdentitySupport() {}

    static String derive(String kind, List<String> components) {
        String preimage = CanonicalJson.write(Map.of(
                "components", List.copyOf(components),
                "kind", kind,
                "version", 1));
        return kind + ":" + ContentDigest.sha256Utf8(preimage).value();
    }

    static String require(String value, String kind) {
        if (value == null
                || !Pattern.matches(Pattern.quote(kind) + ":sha256:[0-9a-f]{64}", value)) {
            throw new IllegalArgumentException(
                    kind + " identity must have form " + kind + ":sha256:<64 lowercase hex>");
        }
        return value;
    }
}
