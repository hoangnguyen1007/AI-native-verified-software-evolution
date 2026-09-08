package com.evolution.analysis.evidence;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.Map;
import java.util.regex.Pattern;

final class EvidenceIdentity {
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private EvidenceIdentity() {}

    static String derive(String kind, Object stableInputs) {
        return kind + ":" + ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "kind", kind, "stableInputs", stableInputs))).value();
    }

    static String require(String value, String kind) {
        String prefix = kind + ":sha256:";
        if (value == null || !value.startsWith(prefix)
                || !SHA256_HEX.matcher(value).region(prefix.length(), value.length()).matches()) {
            throw new IllegalArgumentException(kind + " identity must have form " + kind + ":sha256:<64 lowercase hex>");
        }
        return value;
    }
}
