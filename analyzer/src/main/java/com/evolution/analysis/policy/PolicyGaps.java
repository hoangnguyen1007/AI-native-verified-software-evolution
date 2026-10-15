package com.evolution.analysis.policy;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import java.util.*;

/** Additive policy reason catalog; raw text and exception messages never enter diagnostics. */
final class PolicyGaps {
    static final VersionedIdentifier CATALOG = new VersionedIdentifier("evidence.policy-gaps", "m6.1-v1");
    static CapabilityGapRecord create(EvidenceContext context, VersionedIdentifier provider, ContentDigest input, String reason, Object detail) {
        var observation = ProviderObservationReference.create(provider, "policy.observation", input,
                ContentDigest.sha256Utf8(CanonicalJson.write(List.of(reason, detail))));
        var subject = EvidenceSubject.observation(observation.identity());
        var need = new EvidenceRequirement(EvidenceRequirement.Kind.CONFIGURATION, "policy.complete-input",
                List.of(subject), EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of("Supply schema-valid policy and compatible complete graph evidence within declared bounds."));
        var catalog=provider.equals(PolicyEvaluation.VERSION)||provider.equals(PolicyEvaluation.EVIDENCE_VERSION)
                ?new VersionedIdentifier("evidence.policy-gaps","m6.2-v1"):CATALOG;
        return CapabilityGapRecord.create(catalog, context, provider, "policy.architecture", reason, subject,
                List.of(), List.of(observation), List.of(need), List.of(), List.of(new AffectedOutput(AffectedOutput.Kind.POLICY, "policy.architecture")),
                List.of(), List.of(), List.of(catalog.equals(CATALOG)?"Incomplete policy compilation or mapping cannot establish conformance or a health score.":
                        "Incomplete policy evidence cannot establish conformance or a health score."));
    }
    private PolicyGaps() {}
}
