package com.evolution.analysis.assessment;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import java.util.List;

/** Versioned, sanitized reasons shared by the additive policy metric and assessment services. */
public final class AssessmentGaps {
    public static final VersionedIdentifier CATALOG = new VersionedIdentifier("evidence.assessment-gaps", "m6.3-v1");
    public static CapabilityGapRecord create(EvidenceContext context, VersionedIdentifier provider, ContentDigest input,
                                              String reason, Object detail, AffectedOutput.Kind kind, String output) {
        var observation = ProviderObservationReference.create(provider, "assessment.observation", input,
                ContentDigest.sha256Utf8(CanonicalJson.write(List.of(reason, detail))));
        var subject = EvidenceSubject.observation(observation.identity());
        var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.CONFIGURATION, "assessment.complete-input",
                List.of(subject), EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of("Supply the identified compatible evidence or review the explicit provisional score model; no target execution is implied."));
        return CapabilityGapRecord.create(CATALOG, context, provider, "assessment.architecture", reason, subject, List.of(),
                List.of(observation), List.of(requirement), List.of(), List.of(new AffectedOutput(kind, output)), List.of(), List.of(),
                List.of("Missing evidence cannot establish architecture conformance or improve health."));
    }
    private AssessmentGaps() {}
}
