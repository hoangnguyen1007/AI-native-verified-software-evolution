package com.evolution.analysis.maven;

import com.evolution.analysis.classpath.ExactClasspathResult;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import com.evolution.analysis.dependency.DependencyAcquisitionResult;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Final classpath plus every bounded dependency acquisition round used to reach it. */
public record MavenDependencyArtifactResolution(
        ContentDigest identity,
        String schemaVersion,
        ContentDigest classpathRequestIdentity,
        ContentDigest policyIdentity,
        VersionedIdentifier provider,
        ExactClasspathResult classpath,
        List<DependencyAcquisitionResult> acquisitions,
        List<Problem> problems,
        int classpathPasses) {
    public static final String SCHEMA = "maven-dependency-artifact-resolution-v1";

    public MavenDependencyArtifactResolution {
        ContractChecks.notNull(identity, "dependency resolution identity");
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported dependency resolution schema");
        ContractChecks.notNull(classpathRequestIdentity, "classpath request identity");
        ContractChecks.notNull(policyIdentity, "dependency resolution policy identity");
        ContractChecks.notNull(provider, "dependency resolution provider");
        ContractChecks.notNull(classpath, "resolved classpath");
        if (!classpath.requestIdentity().equals(classpathRequestIdentity)) {
            throw new IllegalArgumentException("Resolved classpath belongs to a different request");
        }
        acquisitions = ContractChecks.distinctInOrder(acquisitions, "dependency acquisition rounds");
        problems = ContractChecks.sortedDistinct(
                problems, Comparator.comparing(CanonicalJson::write), "dependency resolution problems");
        if (classpathPasses < 1) throw new IllegalArgumentException("At least one classpath pass is required");
        ContentDigest expected = derive(classpathRequestIdentity, policyIdentity, provider,
                classpath.identity(), acquisitions, problems, classpathPasses);
        if (!identity.equals(expected)) throw new IllegalArgumentException("Dependency resolution identity mismatch");
    }

    static MavenDependencyArtifactResolution create(
            ContentDigest classpathRequestIdentity,
            DependencyAcquisitionPolicy policy,
            VersionedIdentifier provider,
            ExactClasspathResult classpath,
            List<DependencyAcquisitionResult> acquisitions,
            List<Problem> problems,
            int classpathPasses) {
        List<DependencyAcquisitionResult> copiedAcquisitions = List.copyOf(acquisitions);
        List<Problem> sortedProblems = problems.stream().distinct()
                .sorted(Comparator.comparing(CanonicalJson::write)).toList();
        ContentDigest identity = derive(classpathRequestIdentity, policy.identity(), provider,
                classpath.identity(), copiedAcquisitions, sortedProblems, classpathPasses);
        return new MavenDependencyArtifactResolution(identity, SCHEMA, classpathRequestIdentity,
                policy.identity(), provider, classpath, copiedAcquisitions, sortedProblems, classpathPasses);
    }

    private static ContentDigest derive(
            ContentDigest request,
            ContentDigest policy,
            VersionedIdentifier provider,
            ContentDigest classpath,
            List<DependencyAcquisitionResult> acquisitions,
            List<Problem> problems,
            int passes) {
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", SCHEMA,
                "request", request,
                "policy", policy,
                "provider", provider,
                "classpath", classpath,
                "acquisitions", acquisitions.stream().map(DependencyAcquisitionResult::identity).toList(),
                "problems", problems,
                "classpathPasses", passes)));
    }

    public enum ProblemReason { COORDINATE_LIMIT, TOTAL_BYTE_LIMIT, PASS_LIMIT, ACQUISITION_FAILED }

    public record Problem(ProblemReason reason, String subject) {
        public Problem {
            ContractChecks.notNull(reason, "dependency resolution problem reason");
            subject = ContractChecks.text(subject, "dependency resolution problem subject");
        }
    }
}
