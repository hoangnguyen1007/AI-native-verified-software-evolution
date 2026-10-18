package com.evolution.analysis.acquisition.streaming;

import com.evolution.analysis.contract.common.ContentDigest;
import java.util.Objects;

/** Raw bytes, never a host path. Sizes and offsets are signed 64-bit with checked arithmetic. */
public record ContentHandle(ContentDigest digest, long size) {
    public ContentHandle { Objects.requireNonNull(digest); if (size < 0) throw new IllegalArgumentException("Negative size"); }
}
