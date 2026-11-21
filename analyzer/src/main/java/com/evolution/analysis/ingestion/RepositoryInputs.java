package com.evolution.analysis.ingestion;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.SourceInput;
import java.util.*;

/** Already acquired/decoded repository bytes. Missing inventory entries remain observable to providers. */
public record RepositoryInputs(RepositorySnapshot snapshot, Map<String, SourceInput> files) {
    public RepositoryInputs {
        Objects.requireNonNull(snapshot);
        files = Collections.unmodifiableMap(new TreeMap<>(files));
        var inventory = new HashMap<String, ContentDigest>();
        snapshot.files().forEach(f -> inventory.put(f.path(), f.contentDigest()));
        files.forEach((path, input) -> {
            if (!path.equals(input.document().path()) || !snapshot.repository().equals(input.document().repository())
                    || !input.document().contentDigest().equals(inventory.get(path)))
                throw new IllegalArgumentException("Ingestion bytes must match the exact repository snapshot");
        });
    }
    private static final java.lang.ref.ReferenceQueue<RepositoryInputs> IDENTITY_QUEUE = new java.lang.ref.ReferenceQueue<>();
    private static final java.util.concurrent.ConcurrentHashMap<IdentityKey, ContentDigest> IDENTITY_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class IdentityKey extends java.lang.ref.WeakReference<RepositoryInputs> {
        private final int hash;
        IdentityKey(RepositoryInputs referent, java.lang.ref.ReferenceQueue<RepositoryInputs> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityKey that) || this.hash != that.hash) return false;
            RepositoryInputs a = this.get();
            return a != null && a == that.get();
        }
    }

    public ContentDigest identity() {
        var lookup = new IdentityKey(this, null);
        var cached = IDENTITY_CACHE.get(lookup);
        if (cached != null) return cached;
        java.lang.ref.Reference<? extends RepositoryInputs> stale;
        while ((stale = IDENTITY_QUEUE.poll()) != null) {
            IDENTITY_CACHE.remove(stale);
        }
        var rows = new ArrayList<Map<String, Object>>(files.size());
        for (var e : files.entrySet()) {
            rows.add(Map.of("path", e.getKey(),
                    "digest", e.getValue().document().contentDigest(), "document", e.getValue().document().identity(),
                    "decoding", e.getValue().decoding()));
        }
        var computed = IngestionEvidence.digest(Map.of("schema", "repository-ingestion-input-v1", "snapshot", snapshot.identity(),
                "files", rows));
        IDENTITY_CACHE.put(new IdentityKey(this, IDENTITY_QUEUE), computed);
        return computed;
    }
}
