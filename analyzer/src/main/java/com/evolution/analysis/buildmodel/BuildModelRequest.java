package com.evolution.analysis.buildmodel;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Verified POM bundle. Only these supplied bytes may participate in model evaluation. */
public record BuildModelRequest(RepositorySnapshot snapshot, String rootPom,
        Map<String, PomInput> workspacePoms, Map<MavenCoordinate, PomInput> artifactPoms,
        BuildModelPolicy policy) {
    public static final String SCHEMA = "build-model-input-v1";

    public BuildModelRequest {
        ContractChecks.notNull(snapshot, "snapshot");
        rootPom = ContractChecks.repositoryRelativePath(rootPom, "entry POM");
        workspacePoms = Map.copyOf(workspacePoms);
        artifactPoms = Map.copyOf(artifactPoms);
        ContractChecks.notNull(policy, "policy");
        var inventory = snapshot.files().stream().collect(Collectors.toMap(SnapshotFile::path, SnapshotFile::contentDigest));
        workspacePoms.forEach((path, input) -> {
            ContractChecks.repositoryRelativePath(path, "POM path");
            if (!input.digest().equals(inventory.get(path))) {
                throw new IllegalArgumentException("Workspace POM bytes must match snapshot inventory");
            }
        });
    }

    /** Includes external POM bytes and all evaluation options; no machine locators or timestamps. */
    public ContentDigest identity() {
        var workspace = new TreeMap<String, ContentDigest>();
        workspacePoms.forEach((key, value) -> workspace.put(key, value.digest()));
        var artifacts = new TreeMap<String, ContentDigest>();
        artifactPoms.forEach((key, value) -> artifacts.put(key.notation(), value.digest()));
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of("schema", SCHEMA,
                "snapshot", snapshot.identity(), "rootPom", rootPom,
                "workspacePoms", workspace, "artifactPoms", artifacts, "policy", policy)));
    }
}
