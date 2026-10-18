package com.evolution.analysis.acquisition.streaming;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import java.util.*;

/** Sealed acquisition denominator. Semantic coverage is deliberately a separate obligation. */
public record InventoryManifest(String schema, RepositoryIdentity repository, ContentDigest selection,
        ContentDigest inventory, long entries, long files, long bytes, long excluded,
        Optional<SnapshotIdentity> compatibleV1Snapshot, Optional<ContentDigest> compatibleV1Content) {
    public static final String SCHEMA = "repository-inventory:m8.4-v2";
    public InventoryManifest {
        if (!SCHEMA.equals(schema) || entries < 0 || files < 0 || bytes < 0 || excluded < 0 || files > entries || excluded > entries)
            throw new IllegalArgumentException("Invalid manifest");
        Objects.requireNonNull(repository); Objects.requireNonNull(selection); Objects.requireNonNull(inventory);
        Objects.requireNonNull(compatibleV1Snapshot); Objects.requireNonNull(compatibleV1Content);
        if (compatibleV1Snapshot.isPresent() != compatibleV1Content.isPresent()) throw new IllegalArgumentException("Incomplete compatibility identity");
    }
}
