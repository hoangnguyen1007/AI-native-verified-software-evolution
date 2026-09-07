package com.evolution.analysis.input;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;

/** Explicit target release and finite local-read limits; the host JDK locator is adapter state. */
public record PlatformSymbolRequest(
        int release,
        int maxArtifacts,
        long maxArtifactBytes,
        long maxTotalBytes) {
    public static final String SCHEMA = "platform-symbol-request-v1";

    public PlatformSymbolRequest {
        if (release < 1 || maxArtifacts < 1 || maxArtifactBytes < 1 || maxTotalBytes < 1
                || maxArtifactBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("Platform request values and limits must be positive and bounded");
        }
    }

    public ContentDigest identity() {
        return ContentDigest.sha256Utf8(CanonicalJson.write(java.util.Map.of(
                "schema", SCHEMA, "request", this)));
    }
}
