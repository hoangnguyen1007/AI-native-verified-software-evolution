package com.evolution.analysis.policy;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.query.ArchitectureQuery;
import java.util.*;

/** Versioned, deterministic policy-qualified impact records. */
public final class PolicyImpactRecord {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("policy.impact", "m7.3-v1");

    public record FindingImpact(
            ContentDigest findingIdentity,
            ContentDigest bundleIdentity,
            String ruleId,
            ArchitecturePolicy.Severity severity,
            ArchitectureQuery.Classification classification,
            boolean direct,
            List<String> matchedEntities,
            List<String> path,
            Optional<ArchitectureQuery.Witness> witness,
            Optional<ArchitectureQuery.Witness> counterWitness
    ) {
        public FindingImpact {
            Objects.requireNonNull(findingIdentity);
            Objects.requireNonNull(bundleIdentity);
            Objects.requireNonNull(ruleId);
            Objects.requireNonNull(severity);
            Objects.requireNonNull(classification);
            matchedEntities = List.copyOf(matchedEntities);
            path = List.copyOf(path);
            Objects.requireNonNull(witness);
            Objects.requireNonNull(counterWitness);
        }
    }

    public record RuleImpactSummary(
            String ruleId,
            ArchitecturePolicy.Kind kind,
            ArchitecturePolicy.Severity severity,
            int directFindingsCount,
            int transitiveFindingsCount
    ) {
        public RuleImpactSummary {
            Objects.requireNonNull(ruleId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(severity);
        }
    }

    public record Result(
            String changedEntity,
            List<ArchitectureQuery.Path> structuralPaths,
            List<String> affectedEntities,
            List<FindingImpact> directFindings,
            List<FindingImpact> transitiveFindings,
            List<RuleImpactSummary> affectedRules,
            Optional<ArchitecturePolicy.Severity> worstSeverity,
            Optional<ArchitectureQuery.Classification> highestQuantifier,
            AssessmentStatus status,
            List<CapabilityGapRecord> gaps
    ) {
        public Result {
            Objects.requireNonNull(changedEntity);
            structuralPaths = List.copyOf(structuralPaths);
            affectedEntities = List.copyOf(affectedEntities);
            directFindings = List.copyOf(directFindings);
            transitiveFindings = List.copyOf(transitiveFindings);
            affectedRules = List.copyOf(affectedRules);
            Objects.requireNonNull(worstSeverity);
            Objects.requireNonNull(highestQuantifier);
            Objects.requireNonNull(status);
            gaps = List.copyOf(gaps);
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION, this)));
        }
    }

    private PolicyImpactRecord() {}
}
