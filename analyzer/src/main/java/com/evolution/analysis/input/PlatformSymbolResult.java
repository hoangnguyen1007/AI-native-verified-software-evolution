package com.evolution.analysis.input;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.PlatformInput;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Typed platform acquisition outcome with sanitized, portable evidence. */
public record PlatformSymbolResult(
        ContentDigest identity,
        String schemaVersion,
        ContentDigest requestIdentity,
        VersionedIdentifier provider,
        Status status,
        Optional<PlatformInput> platform,
        List<Problem> problems,
        List<Attempt> attempts,
        List<String> limitations) {
    public static final String SCHEMA = "platform-symbol-result-v1";
    public static final List<String> LIMITATIONS = List.of(
            "A configured JDK must match the requested feature release exactly; cross-release ct.sym selection is not performed.",
            "Java 8 uses an explicit rt.jar and Java 9+ uses explicit JMOD artifacts; no host fallback, toolchain discovery or network acquisition occurs.");

    public PlatformSymbolResult {
        ContractChecks.notNull(identity, "platform result identity");
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported platform result schema");
        ContractChecks.notNull(requestIdentity, "platform request identity");
        ContractChecks.notNull(provider, "platform provider");
        ContractChecks.notNull(status, "platform status");
        ContractChecks.notNull(platform, "platform input");
        problems = ContractChecks.sortedDistinct(problems, Comparator.comparing(CanonicalJson::write), "platform problems");
        attempts = ContractChecks.sortedDistinct(attempts, Comparator.comparing(CanonicalJson::write), "platform attempts");
        limitations = ContractChecks.sortedStrings(limitations, "platform limitations");
        if ((status == Status.COMPLETE) != platform.isPresent()
                || (status == Status.COMPLETE) != problems.isEmpty()) {
            throw new IllegalArgumentException("Platform result status does not match its input/problems");
        }
        if (!identity.equals(derive(requestIdentity, provider, status, platform, problems, attempts, limitations))) {
            throw new IllegalArgumentException("Platform result identity does not match inputs");
        }
    }

    public static PlatformSymbolResult create(
            PlatformSymbolRequest request,
            VersionedIdentifier provider,
            Optional<PlatformInput> platform,
            List<Problem> problems,
            List<Attempt> attempts) {
        platform.ifPresent(value -> {
            if (value.release() != request.release()) {
                throw new IllegalArgumentException("Platform symbols do not match the requested release");
            }
        });
        Status status = platform.isPresent() ? Status.COMPLETE : Status.WITHHELD;
        List<Problem> sortedProblems = problems.stream().distinct().sorted(Comparator.comparing(CanonicalJson::write)).toList();
        List<Attempt> sortedAttempts = attempts.stream().distinct().sorted(Comparator.comparing(CanonicalJson::write)).toList();
        List<String> limitations = LIMITATIONS.stream().sorted().toList();
        ContentDigest identity = derive(request.identity(), provider, status, platform,
                sortedProblems, sortedAttempts, limitations);
        return new PlatformSymbolResult(identity, SCHEMA, request.identity(), provider, status,
                platform, sortedProblems, sortedAttempts, limitations);
    }

    private static ContentDigest derive(
            ContentDigest request,
            VersionedIdentifier provider,
            Status status,
            Optional<PlatformInput> platform,
            List<Problem> problems,
            List<Attempt> attempts,
            List<String> limitations) {
        Optional<PlatformView> view = platform.map(value -> new PlatformView(
                value.entry(), value.release(), value.version(), value.vendor(), value.artifacts().stream()
                        .map(item -> new ArtifactView(item.logicalName(), item.contentDigest(), item.format())).toList()));
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", SCHEMA, "request", request, "provider", provider, "status", status,
                "platform", view, "problems", problems, "attempts", attempts, "limitations", limitations)));
    }

    public enum Status { COMPLETE, WITHHELD }
    public enum Reason {
        JDK_ROOT_NOT_FOUND, JDK_ROOT_NOT_DIRECTORY, JDK_ROOT_SYMBOLIC_LINK, RELEASE_FILE_MISSING,
        RELEASE_FILE_INVALID, RELEASE_MISMATCH, SYMBOL_ROOT_MISSING, SYMBOLIC_LINK,
        NON_REGULAR_ARTIFACT, ARTIFACT_COUNT_LIMIT, ARTIFACT_BYTE_LIMIT, TOTAL_BYTE_LIMIT,
        ARTIFACT_READ_FAILED, INVALID_SYMBOL_ARCHIVE
    }
    public enum Requirement { CONFIGURED_JDK, PLATFORM_METADATA, PLATFORM_SYMBOLS, ACQUISITION_POLICY }
    public enum Outcome { SUCCEEDED, UNAVAILABLE, DENIED, FAILED, LIMIT_EXCEEDED }

    public record Problem(Reason reason, String subject, Requirement requirement) {
        public Problem {
            ContractChecks.notNull(reason, "platform reason");
            ContractChecks.text(subject, "platform problem subject");
            ContractChecks.notNull(requirement, "platform requirement");
        }
    }

    public record Attempt(String subject, Outcome outcome, Optional<ContentDigest> evidence) {
        public Attempt {
            ContractChecks.text(subject, "platform attempt subject");
            ContractChecks.notNull(outcome, "platform attempt outcome");
            ContractChecks.notNull(evidence, "platform attempt evidence");
            if ((outcome == Outcome.SUCCEEDED) != evidence.isPresent()) {
                throw new IllegalArgumentException("Only successful platform reads carry evidence");
            }
        }
    }

    private record ArtifactView(String logicalName, ContentDigest contentDigest, PlatformInput.Format format) {}
    private record PlatformView(
            com.evolution.analysis.contract.analysis.ClasspathEntry entry,
            int release,
            String version,
            String vendor,
            List<ArtifactView> artifacts) {}
}
