package com.evolution.analysis.acquisition;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.Map;
import java.util.Optional;

/** Repository identity and explicit selection policy; the machine root locator stays in its adapter. */
public record RepositoryAcquisitionRequest(
        RepositoryIdentity repository,
        Optional<String> revision,
        boolean dirty,
        String rootPom,
        RepositoryAcquisitionPolicy policy) {
    public static final String SCHEMA = "repository-acquisition-input-v1";

    public RepositoryAcquisitionRequest {
        ContractChecks.notNull(repository, "repository");
        revision = ContractChecks.notNull(revision, "revision")
                .map(value -> ContractChecks.text(value, "revision"));
        rootPom = ContractChecks.repositoryRelativePath(rootPom, "entry POM");
        ContractChecks.notNull(policy, "acquisition policy");
    }

    /** Machine paths and clocks are intentionally excluded from this reproducible request identity. */
    public ContentDigest identity() {
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", SCHEMA,
                "repository", repository,
                "revision", revision,
                "dirty", dirty,
                "rootPom", rootPom,
                "policy", policy)));
    }
}
