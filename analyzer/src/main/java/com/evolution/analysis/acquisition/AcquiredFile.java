package com.evolution.analysis.acquisition;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import java.util.Arrays;
import java.util.Objects;

/** Immutable exact file bytes retained before any source decoding or semantic interpretation. */
public final class AcquiredFile implements Comparable<AcquiredFile> {
    private final String path;
    private final byte[] bytes;
    private final ContentDigest contentDigest;

    public AcquiredFile(String path, byte[] bytes) {
        this.path = ContractChecks.repositoryRelativePath(path, "acquired file path");
        this.bytes = Objects.requireNonNull(bytes, "acquired file bytes must not be null").clone();
        this.contentDigest = ContentDigest.sha256(this.bytes);
    }

    public String path() {
        return path;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }

    public ContentDigest contentDigest() {
        return contentDigest;
    }

    @Override
    public int compareTo(AcquiredFile other) {
        return path.compareTo(other.path);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AcquiredFile file
                && path.equals(file.path)
                && Arrays.equals(bytes, file.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * path.hashCode() + Arrays.hashCode(bytes);
    }
}
