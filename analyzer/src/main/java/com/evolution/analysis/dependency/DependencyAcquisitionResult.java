package com.evolution.analysis.dependency;

import com.evolution.analysis.classpath.ArtifactCoordinate;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Closed outcome and attempt ledger for one bounded exact-artifact acquisition request. */
public record DependencyAcquisitionResult(
        ContentDigest identity,
        String schemaVersion,
        ContentDigest requestIdentity,
        DependencyAcquisitionPolicy policy,
        VersionedIdentifier provider,
        List<Outcome> outcomes,
        List<Attempt> attempts,
        long bytesConsumed,
        List<String> limitations) {
    public static final String SCHEMA = "dependency-acquisition-result-v1";
    public static final List<String> LIMITATIONS = List.of(
            "Only exact release POM and JAR coordinates in standard Maven2 layout are acquired; metadata searches, version ranges, snapshots and relocations require separate evidence.",
            "Only caller-authorized credential-free HTTPS repositories are contacted; target repositories, redirects, settings, mirrors and credentials remain inert.",
            "JAR acquisition validates the required ZIP signature and archive structure but does not interpret manifest Class-Path or target-release multi-release views.");

    public DependencyAcquisitionResult {
        ContractChecks.notNull(identity, "dependency acquisition result identity");
        if (!SCHEMA.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported dependency acquisition result schema");
        }
        ContractChecks.notNull(requestIdentity, "dependency acquisition request identity");
        ContractChecks.notNull(policy, "dependency acquisition policy");
        ContractChecks.notNull(provider, "dependency artifact provider");
        outcomes = ContractChecks.distinctInOrder(outcomes, "dependency acquisition outcomes");
        attempts = ContractChecks.distinctInOrder(attempts, "dependency acquisition attempts");
        if (bytesConsumed < 0) throw new IllegalArgumentException("Consumed bytes must not be negative");
        limitations = ContractChecks.sortedStrings(limitations, "dependency acquisition limitations");
        for (int index = 0; index < attempts.size(); index++) {
            if (attempts.get(index).ordinal() != index) {
                throw new IllegalArgumentException("Dependency acquisition attempt ordinals must be contiguous");
            }
        }
        List<Attempt> validatedAttempts = attempts;
        Set<ArtifactCoordinate> requestedCoordinates = outcomes.stream()
                .map(value -> value.requirement().coordinate()).collect(Collectors.toSet());
        if (validatedAttempts.stream().anyMatch(value -> !requestedCoordinates.contains(value.coordinate()))) {
            throw new IllegalArgumentException("Dependency attempt does not belong to a requested coordinate");
        }
        for (Outcome outcome : outcomes) {
            outcome.artifact().ifPresent(artifact -> {
                boolean supported = validatedAttempts.stream().anyMatch(attempt ->
                        attempt.coordinate().equals(artifact.coordinate())
                                && attempt.origin() == artifact.origin()
                                && attempt.outcome() == AttemptOutcome.SUCCEEDED
                                && attempt.contentDigest().orElseThrow().equals(artifact.contentDigest()));
                if (!supported) {
                    throw new IllegalArgumentException("Resolved dependency artifact lacks a matching successful attempt");
                }
            });
        }
        ContentDigest expected = derive(requestIdentity, policy.identity(), provider,
                outcomes, attempts, bytesConsumed, limitations);
        if (!identity.equals(expected)) {
            throw new IllegalArgumentException("Dependency acquisition result identity mismatch");
        }
    }

    public static DependencyAcquisitionResult create(
            DependencyAcquisitionRequest request,
            VersionedIdentifier provider,
            List<Outcome> outcomes,
            List<Attempt> attempts) {
        return create(request, provider, outcomes, attempts,
                outcomes.stream().flatMap(value -> value.artifact().stream()).mapToLong(Artifact::size).sum());
    }

    public static DependencyAcquisitionResult create(
            DependencyAcquisitionRequest request,
            VersionedIdentifier provider,
            List<Outcome> outcomes,
            List<Attempt> attempts,
            long bytesConsumed) {
        ContractChecks.notNull(request, "dependency acquisition request");
        List<Outcome> copiedOutcomes = List.copyOf(outcomes);
        if (!copiedOutcomes.stream().map(Outcome::requirement).toList().equals(request.requirements())) {
            throw new IllegalArgumentException("Dependency outcomes must cover every requested artifact in order");
        }
        List<Attempt> copiedAttempts = List.copyOf(attempts);
        long resolvedBytes = copiedOutcomes.stream().flatMap(value -> value.artifact().stream())
                .mapToLong(Artifact::size).sum();
        if (bytesConsumed < resolvedBytes || bytesConsumed > request.policy().maxTotalBytes()) {
            throw new IllegalArgumentException("Consumed bytes do not match the bounded acquisition request");
        }
        List<String> limitations = LIMITATIONS.stream().sorted().toList();
        ContentDigest identity = derive(request.identity(), request.policy().identity(), provider,
                copiedOutcomes, copiedAttempts,
                bytesConsumed, limitations);
        return new DependencyAcquisitionResult(identity, SCHEMA, request.identity(), request.policy(), provider,
                copiedOutcomes, copiedAttempts, bytesConsumed, limitations);
    }

    public boolean hasFailures() {
        return outcomes.stream().anyMatch(value -> value.status() == Status.FAILED);
    }

    public long resolvedBytes() {
        return outcomes.stream().flatMap(value -> value.artifact().stream()).mapToLong(Artifact::size).sum();
    }

    private static ContentDigest derive(
            ContentDigest request,
            ContentDigest policy,
            VersionedIdentifier provider,
            List<Outcome> outcomes,
            List<Attempt> attempts,
            long bytesConsumed,
            List<String> limitations) {
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", SCHEMA,
                "request", request,
                "policy", policy,
                "provider", provider,
                "outcomes", outcomes,
                "attempts", attempts,
                "bytesConsumed", bytesConsumed,
                "limitations", limitations)));
    }

    public enum Status { CACHED, ACQUIRED, SKIPPED_POM, FAILED }
    public enum Origin { LOCAL_CACHE, REMOTE_REPOSITORY }
    public enum AttemptOutcome { SUCCEEDED, UNAVAILABLE, DENIED, FAILED, LIMIT_EXCEEDED }
    public enum FailureReason {
        CACHE_ROOT_NOT_FOUND,
        CACHE_ROOT_NOT_DIRECTORY,
        CACHE_ROOT_SYMBOLIC_LINK,
        CACHE_ROOT_READ_FAILED,
        PATH_OUTSIDE_CACHE,
        SYMBOLIC_LINK,
        NON_REGULAR_ARTIFACT,
        CACHE_READ_FAILED,
        CACHE_CHANGED_DURING_READ,
        CACHE_WRITE_FAILED,
        CORRUPTED_CACHE,
        CORRUPTED_DOWNLOAD,
        INVALID_POM,
        DIGEST_MISMATCH,
        SIZE_EXCEEDED,
        FETCH_TIMEOUT,
        NETWORK_FAILURE,
        ARTIFACT_NOT_FOUND,
        REMOTE_RESPONSE_DENIED,
        CREDENTIALS_REQUIRED,
        SNAPSHOT_UNSUPPORTED
    }

    public record Artifact(
            ArtifactCoordinate coordinate,
            Origin origin,
            String repositoryPath,
            ContentDigest contentDigest,
            long size) {
        public Artifact {
            ContractChecks.notNull(coordinate, "dependency artifact coordinate");
            ContractChecks.notNull(origin, "dependency artifact origin");
            repositoryPath = ContractChecks.repositoryRelativePath(repositoryPath, "dependency repository path");
            if (!repositoryPath.equals(coordinate.repositoryPath())) {
                throw new IllegalArgumentException("Dependency artifact path does not match its coordinate");
            }
            ContractChecks.notNull(contentDigest, "dependency artifact digest");
            if (size < 1) throw new IllegalArgumentException("Dependency artifact must not be empty");
        }
    }

    public record Outcome(
            DependencyAcquisitionRequest.Requirement requirement,
            Status status,
            Optional<Artifact> artifact,
            Optional<FailureReason> failureReason) {
        public Outcome {
            ContractChecks.notNull(requirement, "dependency requirement");
            ContractChecks.notNull(status, "dependency acquisition status");
            artifact = ContractChecks.notNull(artifact, "dependency artifact");
            failureReason = ContractChecks.notNull(failureReason, "dependency failure reason");
            boolean failed = status == Status.FAILED;
            if (failed == artifact.isPresent() || failed != failureReason.isPresent()) {
                throw new IllegalArgumentException("Dependency outcome status, artifact and failure reason disagree");
            }
            artifact.ifPresent(value -> {
                if (!value.coordinate().equals(requirement.coordinate())) {
                    throw new IllegalArgumentException("Dependency outcome artifact does not match its requirement");
                }
                requirement.expectedDigest().ifPresent(expected -> {
                    if (!expected.equals(value.contentDigest())) {
                        throw new IllegalArgumentException("Dependency artifact does not match the expected digest");
                    }
                });
            });
            boolean pom = requirement.coordinate().extension().equals("pom");
            if ((!failed && pom != (status == Status.SKIPPED_POM))
                    || (status == Status.CACHED && artifact.orElseThrow().origin() != Origin.LOCAL_CACHE)
                    || (status == Status.ACQUIRED && artifact.orElseThrow().origin() != Origin.REMOTE_REPOSITORY)) {
                throw new IllegalArgumentException("Dependency outcome status does not match its origin or packaging");
            }
        }
    }

    public record Attempt(
            int ordinal,
            ArtifactCoordinate coordinate,
            Origin origin,
            String subject,
            AttemptOutcome outcome,
            Optional<ContentDigest> contentDigest) {
        public Attempt {
            if (ordinal < 0) throw new IllegalArgumentException("Attempt ordinal must not be negative");
            ContractChecks.notNull(coordinate, "attempt coordinate");
            ContractChecks.notNull(origin, "attempt origin");
            subject = ContractChecks.text(subject, "attempt subject");
            if (origin == Origin.LOCAL_CACHE) {
                ContractChecks.repositoryRelativePath(subject, "cache attempt path");
            } else {
                URI remote;
                try {
                    remote = URI.create(subject);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Remote dependency attempt subject must be an HTTPS URI");
                }
                if (!remote.isAbsolute() || !"https".equalsIgnoreCase(remote.getScheme())
                        || remote.getHost() == null || remote.getHost().isBlank()
                        || remote.getUserInfo() != null || remote.getQuery() != null || remote.getFragment() != null) {
                    throw new IllegalArgumentException("Remote dependency attempt subject must be a credential-free HTTPS URI");
                }
            }
            ContractChecks.notNull(outcome, "attempt outcome");
            contentDigest = ContractChecks.notNull(contentDigest, "attempt digest");
            if ((outcome == AttemptOutcome.SUCCEEDED) != contentDigest.isPresent()) {
                throw new IllegalArgumentException("Only successful artifact attempts carry content evidence");
            }
        }
    }
}
