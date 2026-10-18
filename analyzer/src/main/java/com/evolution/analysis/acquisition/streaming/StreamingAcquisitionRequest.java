package com.evolution.analysis.acquisition.streaming;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

public record StreamingAcquisitionRequest(RepositoryIdentity repository, Optional<String> revision,
        boolean dirty, List<String> exclusions) {
    public StreamingAcquisitionRequest {
        Objects.requireNonNull(repository); Objects.requireNonNull(revision);
        exclusions = exclusions.stream().map(p -> LogicalPath.utf8(p).utf8().orElseThrow()).distinct().sorted().toList();
    }
    public boolean excludes(String path) { return exclusions.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/")); }
    public ContentDigest identity() { return CanonicalJson.sha256(Map.of("schema", "repository-selection:m8.4-v2", "selection", this)); }
}
