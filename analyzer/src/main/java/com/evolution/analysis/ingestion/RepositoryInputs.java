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
    public ContentDigest identity() {
        return IngestionEvidence.digest(Map.of("schema", "repository-ingestion-input-v1", "snapshot", snapshot.identity(),
                "files", files.entrySet().stream().map(e -> Map.of("path", e.getKey(),
                        "digest", e.getValue().document().contentDigest(), "document",e.getValue().document().identity(),
                        "decoding", e.getValue().decoding())).toList()));
    }
}
