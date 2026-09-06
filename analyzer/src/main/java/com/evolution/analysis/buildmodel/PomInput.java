package com.evolution.analysis.buildmodel;

import com.evolution.analysis.contract.common.ContentDigest;
import java.util.Objects;

/** Immutable untrusted XML bytes; an acquisition provider owns reading files or downloading artifacts. */
public final class PomInput {
    private final byte[] bytes;
    private final ContentDigest digest;

    public PomInput(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes).clone();
        this.digest = ContentDigest.sha256(this.bytes);
    }

    public byte[] bytes() { return bytes.clone(); }
    public int size() { return bytes.length; }
    public ContentDigest digest() { return digest; }
}
