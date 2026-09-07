package com.evolution.analysis.classpath;

import com.evolution.analysis.buildmodel.BuildModelRequest;
import com.evolution.analysis.buildmodel.BuildModelResult;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.Map;

/** Exact build projection plus explicit bounded policy; the machine cache path stays in its adapter. */
public record ClasspathResolutionRequest(
        BuildModelRequest buildRequest,
        BuildModelResult buildModel,
        ClasspathResolutionPolicy policy) {
    public static final String SCHEMA = "classpath-resolution-input-v1";

    public ClasspathResolutionRequest {
        ContractChecks.notNull(buildRequest, "classpath build request");
        ContractChecks.notNull(buildModel, "classpath build model");
        ContractChecks.notNull(policy, "classpath policy");
        if (!buildRequest.identity().equals(buildModel.requestIdentity())) {
            throw new IllegalArgumentException("Classpath build result belongs to a different build request");
        }
    }

    public ContentDigest identity() {
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", SCHEMA,
                "buildRequest", buildRequest.identity(),
                "buildModel", buildModel.identity(),
                "policy", policy)));
    }
}
