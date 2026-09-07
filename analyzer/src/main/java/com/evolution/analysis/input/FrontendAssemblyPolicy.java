package com.evolution.analysis.input;

import com.evolution.analysis.contract.analysis.ManifestComponent;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;

/** Explicit stable components used to construct M1 analysis manifests. */
public record FrontendAssemblyPolicy(
        VersionedIdentifier manifestVersion,
        VersionedIdentifier configurationSchema,
        ManifestComponent analyzer,
        ManifestComponent ruleSet,
        ManifestComponent graphSchema) {
    public FrontendAssemblyPolicy {
        ContractChecks.notNull(manifestVersion, "manifest version");
        ContractChecks.notNull(configurationSchema, "configuration schema");
        ContractChecks.notNull(analyzer, "analyzer component");
        ContractChecks.notNull(ruleSet, "rule-set component");
        ContractChecks.notNull(graphSchema, "graph-schema component");
    }
}
