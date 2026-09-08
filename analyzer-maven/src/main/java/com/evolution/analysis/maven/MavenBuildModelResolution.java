package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.BuildModelRequest;
import com.evolution.analysis.buildmodel.BuildModelResult;
import com.evolution.analysis.buildmodel.MavenCoordinate;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Final effective build input plus provenance for progressively acquired external POMs. */
public record MavenBuildModelResolution(
        ContentDigest identity,
        String schemaVersion,
        ContentDigest initialRequestIdentity,
        ContentDigest policyIdentity,
        VersionedIdentifier provider,
        BuildModelRequest request,
        BuildModelResult model,
        List<AcquiredPom> acquiredPoms,
        List<Attempt> attempts,
        List<Problem> problems,
        int passes) {
    public static final String SCHEMA = "maven-build-model-resolution-v1";

    public MavenBuildModelResolution {
        ContractChecks.notNull(identity, "Maven build resolution identity");
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported Maven build resolution schema");
        ContractChecks.notNull(initialRequestIdentity, "initial build request identity");
        ContractChecks.notNull(policyIdentity, "POM resolution policy identity");
        ContractChecks.notNull(provider, "Maven build resolution provider");
        ContractChecks.notNull(request, "resolved build request");
        ContractChecks.notNull(model, "resolved build model");
        if (!request.identity().equals(model.requestIdentity())) {
            throw new IllegalArgumentException("Resolved build model belongs to a different request");
        }
        acquiredPoms = ContractChecks.sortedDistinct(
                acquiredPoms, Comparator.comparing(value -> value.coordinate().notation()), "acquired POMs");
        attempts = ContractChecks.sortedDistinct(attempts, Comparator.comparing(CanonicalJson::write), "POM attempts");
        problems = ContractChecks.sortedDistinct(problems, Comparator.comparing(CanonicalJson::write), "POM problems");
        if (passes < 1) throw new IllegalArgumentException("At least one build-model pass is required");
        ContentDigest expected = deriveIdentity(initialRequestIdentity, policyIdentity, provider, request.identity(),
                model.identity(), acquiredPoms, attempts, problems, passes);
        if (!identity.equals(expected)) throw new IllegalArgumentException("Maven build resolution identity mismatch");
    }

    public static MavenBuildModelResolution create(
            ContentDigest initialRequestIdentity,
            MavenPomResolutionPolicy policy,
            VersionedIdentifier provider,
            BuildModelRequest request,
            BuildModelResult model,
            List<AcquiredPom> acquiredPoms,
            List<Attempt> attempts,
            List<Problem> problems,
            int passes) {
        List<AcquiredPom> sortedPoms = ContractChecks.sortedDistinct(
                acquiredPoms, Comparator.comparing(value -> value.coordinate().notation()), "acquired POMs");
        List<Attempt> sortedAttempts = ContractChecks.sortedDistinct(
                attempts, Comparator.comparing(CanonicalJson::write), "POM attempts");
        List<Problem> sortedProblems = ContractChecks.sortedDistinct(
                problems, Comparator.comparing(CanonicalJson::write), "POM problems");
        ContentDigest identity = deriveIdentity(initialRequestIdentity, policy.identity(), provider, request.identity(),
                model.identity(), sortedPoms, sortedAttempts, sortedProblems, passes);
        return new MavenBuildModelResolution(identity, SCHEMA, initialRequestIdentity, policy.identity(), provider,
                request, model, sortedPoms, sortedAttempts, sortedProblems, passes);
    }

    private static ContentDigest deriveIdentity(
            ContentDigest initialRequest,
            ContentDigest policy,
            VersionedIdentifier provider,
            ContentDigest resolvedRequest,
            ContentDigest model,
            List<AcquiredPom> poms,
            List<Attempt> attempts,
            List<Problem> problems,
            int passes) {
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", SCHEMA,
                "initialRequest", initialRequest,
                "policy", policy,
                "provider", provider,
                "resolvedRequest", resolvedRequest,
                "model", model,
                "acquiredPoms", poms,
                "attempts", attempts,
                "problems", problems,
                "passes", passes)));
    }

    public enum Origin { LOCAL_CACHE, REMOTE_REPOSITORY }
    public enum AttemptOutcome { SUCCEEDED, UNAVAILABLE, DENIED, FAILED, LIMIT_EXCEEDED }
    public enum ProblemReason {
        CACHE_ROOT_NOT_FOUND,
        CACHE_ROOT_NOT_DIRECTORY,
        CACHE_ROOT_SYMBOLIC_LINK,
        CACHE_ROOT_READ_FAILED,
        PATH_OUTSIDE_CACHE,
        SYMBOLIC_LINK,
        NON_REGULAR_POM,
        POM_READ_FAILED,
        POM_CHANGED_DURING_READ,
        POM_COUNT_LIMIT,
        POM_BYTE_LIMIT,
        TOTAL_BYTE_LIMIT,
        PASS_LIMIT,
        MISSING_POM,
        REMOTE_SNAPSHOT_UNSUPPORTED,
        REMOTE_REQUEST_FAILED,
        REMOTE_RESPONSE_DENIED,
        REMOTE_RESPONSE_INVALID
    }

    public record AcquiredPom(
            MavenCoordinate coordinate,
            Origin origin,
            String logicalLocation,
            ContentDigest contentDigest,
            long size) {
        public AcquiredPom {
            ContractChecks.notNull(coordinate, "acquired POM coordinate");
            ContractChecks.notNull(origin, "acquired POM origin");
            logicalLocation = ContractChecks.text(logicalLocation, "POM logical location");
            if (origin == Origin.LOCAL_CACHE) {
                ContractChecks.repositoryRelativePath(logicalLocation, "POM repository path");
            }
            ContractChecks.notNull(contentDigest, "acquired POM digest");
            if (size < 0) throw new IllegalArgumentException("Acquired POM size must not be negative");
        }
    }

    public record Attempt(
            MavenCoordinate coordinate,
            Origin origin,
            String subject,
            AttemptOutcome outcome,
            Optional<ContentDigest> contentDigest) {
        public Attempt {
            ContractChecks.notNull(coordinate, "POM attempt coordinate");
            ContractChecks.notNull(origin, "POM attempt origin");
            subject = ContractChecks.text(subject, "POM attempt subject");
            if (origin == Origin.LOCAL_CACHE) {
                ContractChecks.repositoryRelativePath(subject, "POM attempt repository path");
            }
            ContractChecks.notNull(outcome, "POM attempt outcome");
            ContractChecks.notNull(contentDigest, "POM attempt digest");
            if ((outcome == AttemptOutcome.SUCCEEDED) != contentDigest.isPresent()) {
                throw new IllegalArgumentException("Only successful POM reads carry content evidence");
            }
        }
    }

    public record Problem(ProblemReason reason, String subject) {
        public Problem {
            ContractChecks.notNull(reason, "POM problem reason");
            ContractChecks.text(subject, "POM problem subject");
        }
    }
}
